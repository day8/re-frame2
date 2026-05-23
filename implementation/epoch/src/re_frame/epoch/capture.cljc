(ns re-frame.epoch.capture
  "Per-cascade trace-buffer write/read and the two read-only walks the
  drain-settle assembly path runs against the harvested events:

    capture-event!     -- the late-bind seam `re-frame.trace` invokes
                          on every emit, gated on `:frame`-tag presence
                          and the `skip-ops` self-emit catalogue.
    project-all        -- one fused reducer pass producing the
                          `:sub-runs`, `:renders`, `:effects` slots.
    find-trigger-event -- one walk extracting `:event-id` + `:event`
                          + `:dispatch-id` from the cascade's first
                          `:event/run-start`, with a `:event-id`-only
                          fallback.

  Per rf2-0wi86 Phase-2 seam B: this namespace owns the cascade-buffer
  *behaviour*; the buffer atom itself and the low-level
  buffer-event! / harvest-buffer! mutators live in
  `re-frame.epoch.state` (seam A). Splitting the catalogue + capture
  + projection trio out of the facade keeps the cascade pipeline a
  single grep target."
  (:require [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

;; ---- skip-ops catalogue --------------------------------------------------
;;
;; Operations this namespace itself emits with a `:frame` tag, all of
;; which fire OUTSIDE a cascade (the drain has either not started, or
;; has just settled and the buffer has been harvested). If `capture-
;; event!` didn't skip them they would accrete into `capture-buffers`
;; and leak into the NEXT cascade's harvested record for the same
;; frame — a silent correctness bug surfacing as phantom `:trace-events`
;; and a wrong `:trigger-event` via `find-trigger-event`'s fallback arm.
;;
;; Enumeration (not a `:rf.epoch/*` namespace-prefix filter) is the
;; deliberate choice: a future in-cascade `:rf.epoch/*` op (e.g. an
;; in-drain cascade-rollback trace) must continue to surface in epoch
;; records. The companion test `skip-ops-catalogue-pins-every-rf-epoch-op`
;; pins this catalogue against every `:rf.epoch/*` op the namespace
;; emits, so an addition that forgets to update one or the other will
;; fail loudly rather than drift silently.
;;
;; `:rf.epoch.cb/silenced-on-frame-destroy` is a different op-type
;; (`:rf.epoch.cb`) and emits AFTER the frame's ring buffer has been
;; dropped, so it can never race a future cascade for that frame.
(def skip-ops
  #{;; Drain-settle emit (after harvest-buffer! has emptied the buffer).
    :rf.epoch/snapshotted
    ;; restore-epoch success + the five documented failure modes.
    :rf.epoch/restored
    :rf.epoch/restore-unknown-epoch
    :rf.epoch/restore-schema-mismatch
    :rf.epoch/restore-missing-handler
    :rf.epoch/restore-version-mismatch
    :rf.epoch/restore-during-drain
    :rf.epoch/restore-non-ok-record
    ;; reset-frame-db! success + its two failure modes (Tool-Pair §Pair-
    ;; tool writes, rf2-zq55). All three fire after the synthetic record
    ;; has been built and the cascade-buffer (if any) has been harvested.
    :rf.epoch/db-replaced
    :rf.epoch/reset-frame-db-during-drain
    :rf.epoch/reset-frame-db-schema-mismatch
    ;; Redact-fn exception warning (rf2-wp70d / Tool-Pair §Time-travel
    ;; §Redaction hook). Emitted by `maybe-redact` AFTER
    ;; `harvest-buffer!` has emptied the cascade buffer for this
    ;; frame; if left un-skipped, the `:frame`-tagged emit would
    ;; otherwise accrete into the NEXT cascade's harvested record
    ;; for this frame.
    :rf.warning/epoch-redact-fn-exception})

;; ---- render ops (rf2-qs6dl) -----------------------------------------------
;;
;; The three view-render ops that fire at React COMMIT time — AFTER the
;; causing cascade settled (Reagent batches re-renders onto a later tick
;; via `re-frame.interop/after-render`). When one of these arrives with
;; no in-flight cascade for its frame, it must be attributed back to the
;; cascade that CAUSED it (the most-recently-settled epoch) rather than
;; buffered into the next cascade — see `re-frame.epoch.state/back-fill-
;; render!` and `re-frame.epoch.listeners/record-render!`.
(def ^:private render-ops
  #{:rf.view/render
    :rf.view/rendered
    :rf.view/rendered-cap-reached})

;; ---- sub-run ops (rf2-wi900) ----------------------------------------------
;;
;; The subs sibling of render-ops. A `:sub/run` (reactive recompute) or
;; `:sub/skip` (memo hit) fires when a reaction is derefed — and reactions
;; deref LAZILY at React render time, which Reagent batches onto a later
;; tick AFTER the causing cascade settled. So like a render, a sub-run
;; arriving with no in-flight cascade for its frame is a post-settle async
;; recompute and must be attributed back to the cascade that CAUSED it (the
;; most-recently-settled epoch), not buffered into the next cascade — see
;; `re-frame.epoch.state/back-fill-sub-run!` and
;; `re-frame.epoch.listeners/record-sub-run!`. An in-flight (synchronous)
;; sub-run — one that recomputes while a cascade is draining (an event
;; handler that subscribes, an SSR render) — genuinely belongs to that
;; cascade and is buffered normally.
(def ^:private sub-run-ops
  #{:rf.sub/run
    :rf.sub/skip})

(defn- in-flight-cascade?
  "True when the frame's in-flight capture buffer already holds an
  `:event/run-start` emit — the canonical signal that a cascade has
  begun draining into this buffer (mirrors the gate
  `re-frame.epoch.listeners/on-frame-destroyed!` uses for mid-drain
  detection). A render that fires WITH a cascade in flight (synchronous
  flush — SSR, a render triggered inside another render's reactive read)
  genuinely belongs to that cascade and is buffered normally; a render
  that fires with NO cascade in flight is a post-settle async re-render
  and is back-filled to the cascade that caused it."
  [frame-id]
  (some (fn [ev]
          (= :rf.event/run-start (:operation ev)))
        (state/buffer-for frame-id)))

(defn capture-event!
  "Internal trace-capture entry point published through `re-frame.late-bind`
  under `:epoch/capture-event`. `re-frame.trace/emit!` and
  `re-frame.trace/emit-error!` invoke this for every event so the
  cascade buffer is populated regardless of which user listeners are
  registered.

  Going through late-bind (rather than registering as a listener via
  `register-listener!`) ensures the user-facing `clear-listeners!`
  call does NOT wipe the internal capture path — pair tools that reset
  the trace stream between sessions can do so without losing epoch
  recording.

  Events whose tags don't carry `:frame` are skipped — they can't be
  tied to a specific cascade. The `:rf.epoch/*` trace events this
  namespace emits OUTSIDE a cascade (catalogued in `skip-ops`) are
  also skipped, so a snapshotted/restored/db-replaced emit cannot leak
  into the next cascade's harvested record.

  Per rf2-qs6dl: a view-render op (`:view/render` / `:rf.view/rendered`
  / `:rf.view/rendered-cap-reached`) that arrives with no in-flight
  cascade for its frame is a post-settle async re-render — it fired at
  React commit time, AFTER the causing cascade settled. Routing it into
  the now-empty buffer would mis-attribute it to the NEXT cascade (the
  one-epoch lag the bead documents). Instead it is back-filled into the
  cascade that caused it via the `:epoch/record-render!` hook. A render
  that fires WITH a cascade in flight (synchronous flush) belongs to
  that cascade and is buffered as before.

  Per rf2-wi900: the identical post-settle timing applies to reactive
  sub-runs (`:sub/run` / `:rf.sub/skip`) — reactions recompute lazily at
  React deref time, AFTER the causing cascade settled, so a sub-run with
  no in-flight cascade is back-filled into the causing epoch via the
  `:epoch/record-sub-run!` hook (sibling of `:epoch/record-render!`). An
  in-flight sub-run (a handler that subscribes, an SSR render) belongs to
  the in-flight cascade and is buffered as before."
  [event]
  (when interop/debug-enabled?
    (let [op       (:operation event)
          tags     (:tags event)
          frame-id (or (:frame tags)
                       (:frame event))]
      ;; rf2-vh1k3 — learn which subs each view reads from the
      ;; `:reader-render-key` stamp the runtime sets on a `:sub/run`
      ;; that recomputes SYNCHRONOUSLY inside a view's render (the mount /
      ;; first-paint deref). A post-settle reactive recompute fires
      ;; outside any render binding and carries no stamp, so this learns
      ;; the read-set at mount; a view's sub set is stable across its
      ;; life. The render back-fill (`state/value-changed-epoch-for`)
      ;; uses the read-set to tell a view's genuine re-render from a
      ;; mount-burst tail. Fires regardless of the routing branch below.
      (when frame-id
        (when-let [reader-rk (:rf.sub/reader-render-key tags)]
          (when (= :rf.sub/run op)
            (state/record-render-deps! frame-id reader-rk (:rf.sub/id tags)))))
      (when (and frame-id (not (contains? skip-ops op)))
        (cond
          ;; Post-settle render — attribute to the causing cascade.
          ;; The orchestrator (state back-fill + listener re-notify)
          ;; lives in `re-frame.epoch.listeners`; reaching it through
          ;; late-bind keeps `capture` free of a require on `listeners`
          ;; (which requires `assembly` → `capture`, a cycle). The hook
          ;; is published at `re-frame.epoch` ns-load; when absent (the
          ;; degenerate load-order window before the facade installs it)
          ;; the render falls through to the normal buffer path.
          (and (contains? render-ops op)
               (not (in-flight-cascade? frame-id)))
          (if-let [record-render! (late-bind/get-fn-cached :epoch/record-render!)]
            (record-render! frame-id event)
            (state/buffer-event! frame-id event))

          ;; Post-settle sub-run — the subs sibling (rf2-wi900). Same
          ;; back-fill shape, distinct hook. Falls through to the normal
          ;; buffer path during the pre-facade-install load-order window.
          (and (contains? sub-run-ops op)
               (not (in-flight-cascade? frame-id)))
          (if-let [record-sub-run! (late-bind/get-fn-cached :epoch/record-sub-run!)]
            (record-sub-run! frame-id event)
            (state/buffer-event! frame-id event))

          ;; Out-of-cascade orphan — drop from the capture buffer (rf2-avvwm).
          ;; An emit with NO cascade context (no in-flight cascade for the
          ;; frame AND no `:dispatch-id` on its tags) belongs to no cascade:
          ;; a frame-lifecycle emit (`:frame/created` / `:frame/re-registered`)
          ;; fired between the last settled event and the next dequeue, or a
          ;; registry-time emit. Per Spec 009 §Dispatch correlation it stays
          ;; UNCORRELATED — neither a new epoch nor folded into another
          ;; epoch's `:trace-events`. It still rides the raw trace ring +
          ;; listener fan-out (those run independently of this capture seam);
          ;; only the epoch-record capture buffer skips it.
          ;;
          ;; Pre-rf2-avvwm such an orphan was buffered, then the NEXT
          ;; dequeued event's harvest vacuumed it in as the first
          ;; `:trace-events` entry (the per-event-epoch boundary from
          ;; rf2-nj6p7 left it stranded; the post-settle render/sub-run
          ;; branches above already gate on the same `in-flight-cascade?`
          ;; signal — this is the third out-of-cascade emit class).
          ;;
          ;; A `:dispatch-id`-bearing emit is ALWAYS buffered even when no
          ;; cascade is in flight for THIS frame at the instant of the emit:
          ;; a child's `:event/dispatched` marker fires during the PARENT's
          ;; do-fx carrying the child's id, and rides the child's later
          ;; `harvest-buffer-for-event!` settle.
          (and (not (in-flight-cascade? frame-id))
               (nil? (:rf.trace/dispatch-id tags)))
          nil

          :else
          (state/buffer-event! frame-id event))))))

;; ---- cascade-cause (for :rf.view/rendered attribution, rf2-25zo2) --------
;;
;; The Causa Reactive panel needs to attribute each view re-render to the
;; cascade that drove it: which event kicked off this cascade, and which
;; subs ran during it. The data is captured at trace-bus emission time
;; (the in-flight cascade buffer is walked at view-render-emit time), NOT
;; derived post-hoc on inspection — same dataset the rest of the epoch
;; record's projections share.
;;
;; The walk visits the in-flight buffer for the frame at the moment the
;; substrate fires its render. Returns `{:cause-event-id <eid>
;; :cause-subs <sub-ids vector>}`. The first `:event/run-start` we see
;; supplies `:cause-event-id`; every `:sub/run` contributes to
;; `:cause-subs` (deduped, preserving first-seen order). Empty buffer
;; (render outside any cascade — e.g. fixture-driven direct invocations,
;; or React's post-settle async batch) yields `{}` so consumers see the
;; slots simply absent.

(defn cascade-cause
  "Walk the frame's in-flight cascade buffer and return a small map
  attributing the current render to the dispatching cascade. Used by
  `:rf.view/rendered` emission (per [Spec 009 §`:rf.view/rendered`]
  (009-Instrumentation.md#rfviewrendered) and rf2-25zo2). One pass
  over the buffer; bounded `sub-cap` distinct sub-ids (default 100,
  matching the per-cascade view-render cap).

  Return-map slots:

    :cause-event-id — the event-id of the first :event/run-start seen
                      in the cascade (i.e. the dispatching cascade's
                      trigger event).
    :cause-subs     — distinct sub-ids that ran in the cascade so far,
                      in first-seen order, capped at sub-cap.
    :value-changed-subs — the SUBSET of :cause-subs whose :sub/run
                      reported :rf.sub/value-changed? true (rf2-8wrzz.1).
                      A `set` of sub-ids, NOT capped independently — it is
                      drawn from the already-capped first-seen scan, so it
                      is bounded by sub-cap. Used by the views.cljs emit
                      site to derive :rf.view/triggered-by — the first sub
                      in THIS view's own read-set whose value changed (the
                      precise per-view re-render cause). Empty when no sub
                      changed value (a structural re-render).
    :rendered-so-far — count of :rf.view/rendered already emitted into
                      this cascade. Used by the views.cljs emit site to
                      enforce the per-cascade view-render cap.

  Empty buffer (render outside any cascade — e.g. headless direct
  invocations, or React's post-settle async batch) yields a map with
  `:rendered-so-far 0` and the other slots omitted."
  ([frame-id]
   (cascade-cause frame-id 100))
  ([frame-id sub-cap]
   (when interop/debug-enabled?
     (let [events  (state/buffer-for frame-id)
           ;; Single reduce: capture first :event/run-start, accumulate
           ;; distinct sub-ids in first-seen order up to `sub-cap`, and
           ;; count the existing :rf.view/rendered emits so the views.cljs
           ;; emit site can enforce the per-cascade view-render cap.
           result  (reduce
                     (fn [acc ev]
                       (let [op   (:operation ev)
                             tags (:tags ev)]
                         (cond-> acc
                           (and (nil? (:cause-event-id acc))
                                (= :rf.event/run-start op))
                           (assoc :cause-event-id (:rf.trace/event-id tags))

                           (and (= :rf.sub/run op)
                                (some? (:rf.sub/id tags))
                                (not (contains? (:seen acc) (:rf.sub/id tags)))
                                (< (count (:subs acc)) sub-cap))
                           (-> (update :subs conj (:rf.sub/id tags))
                               (update :seen conj (:rf.sub/id tags)))

                           ;; rf2-8wrzz.1 — accumulate the value-changed
                           ;; subset (a set, deduped) so the views.cljs emit
                           ;; site can derive :rf.view/triggered-by. Tracked
                           ;; alongside the first-seen scan above; a sub that
                           ;; ran multiple times in the cascade contributes
                           ;; once. Bounded by the same buffer the scan walks.
                           (and (= :rf.sub/run op)
                                (some? (:rf.sub/id tags))
                                (true? (:rf.sub/value-changed? tags)))
                           (update :value-changed-subs conj (:rf.sub/id tags))

                           ;; Count both :rf.view/rendered AND the one-shot
                           ;; :rf.view/rendered-cap-reached marker: once
                           ;; the marker fires for a cascade, n-so-far
                           ;; remains > cap so the emit site's `:else nil`
                           ;; branch suppresses subsequent emits (the
                           ;; marker is one-shot per cascade).
                           (or (= :rf.view/rendered op)
                               (= :rf.view/rendered-cap-reached op))
                           (update :rendered-so-far inc))))
                     {:cause-event-id     nil
                      :subs               []
                      :seen               #{}
                      :value-changed-subs #{}
                      :rendered-so-far    0}
                     events)]
       (cond-> {:rendered-so-far (:rendered-so-far result)}
         (:cause-event-id result) (assoc :cause-event-id (:cause-event-id result))
         (seq (:subs result))     (assoc :cause-subs (:subs result))
         (seq (:value-changed-subs result))
         (assoc :value-changed-subs (:value-changed-subs result)))))))

;; ---- record projection ----------------------------------------------------
;;
;; The two structured-row projectors below are shared between
;; `project-all`'s fused reducer (the settle-time projection) and the
;; post-settle back-fill in `re-frame.epoch.listeners` (which projects a
;; single late-arriving event into the same row shape). Keeping the row
;; literal in ONE place means a future field added to the `:sub/run` /
;; `:view/render` projection lands on both surfaces automatically — the
;; rf2-ee38b clarity review flagged the prior verbatim duplication as a
;; lockstep maintenance hazard.

(defn sub-run-row
  "Project a `:rf.sub/run` trace event into its structured `:sub-runs`
  row, or nil for any non-`:sub/run` op (a `:rf.sub/skip` memo hit
  projects no `:sub-runs` row — it rides only `:trace-events`).

  Per rf2-l1jz8 the reactive recompute path enriches the `:sub/run` tag
  with value-change + cascade attribution (`:value-changed?` /
  `:prev-value` / `:value` / `:cascade?` / `:cause-sub`); they are
  threaded onto the structured projection so Causa's Reactive panel reads
  them off the epoch record (not the raw trace). `compute-sub`'s base-
  shape emit omits them — the slots are simply absent there, which the
  panel's `sub-changed?` / `sub-cascaded?` predicates tolerate (false /
  not-cascaded). The `:sensitive?` stamp on the trace event (rf2-isdwf)
  governs whether `:prev-value` / `:value` already carry the
  `:rf/redacted` sentinel — they ride elide-wire-value at the emit site."
  [event]
  (when (= :rf.sub/run (:operation event))
    (let [t (:tags event)]
      {:sub-id         (:rf.sub/id t)
       :query-v        (:rf.sub/query-v t)
       :recomputed?    true
       :value-changed? (:rf.sub/value-changed? t)
       :prev-value     (:rf.sub/prev-value t)
       :value          (:rf.sub/value t)
       :cascade?       (:rf.sub/cascade? t)
       :cause-sub      (:rf.sub/cause-sub t)})))

(defn render-row
  "Project a `:rf.view/rendered` trace event into its structured
  `:renders` row, or nil for any other render op (`:rf.view/render` —
  the render-START marker — and `:rf.view/rendered-cap-reached` ride
  only `:trace-events`).

  Per rf2-8wrzz.1 the projection sources from the POST-render
  `:rf.view/rendered` op rather than the render-START `:rf.view/render`,
  because only the post-render op carries the per-view cause + timing the
  Causa Views panel needs — `:triggered-by` (the sub-id that caused this
  view to re-render) and `:elapsed-ms` (the render duration). Both ops
  fire once per render carrying the same `:rf.view/render-key`, so this is
  a 1:1 re-source, not a count change, for cascades under the 100-render
  `:rf.view/rendered` cap (a full-page storm beyond the cap truncates the
  `:renders` projection alongside the raw op, by design).

  Row slots:

    :render-key   — the `[<view-id> <instance-token>]` tuple; renders
                    bypassing reg-view (plain Reagent fns) use
                    `[:rf.view/anonymous nil]` as the documented fallback
                    (Spec-Schemas §`:rf/epoch-record`).
    :mount?       — `true` on the instance's first render (rf2-9hoos).
    :triggered-by — (when derivable) the single sub-id that caused this
                    re-render (rf2-8wrzz.1); absent on a structural render.
    :elapsed-ms   — (when present) the render duration in fractional ms
                    (rf2-8wrzz.1).

  The optional slots are threaded only when the trace tag carries them so
  the row stays minimal for renders outside a cascade / structural
  re-renders, matching the open-map schema."
  [event]
  (when (= :rf.view/rendered (:operation event))
    (let [t (:tags event)]
      (cond-> {:render-key (or (:rf.view/render-key t)
                               [:rf.view/anonymous nil])}
        (contains? t :rf.view/mount?)
        (assoc :mount? (:rf.view/mount? t))
        (some? (:rf.view/triggered-by t))
        (assoc :triggered-by (:rf.view/triggered-by t))
        (some? (:rf.view/elapsed-ms t))
        (assoc :elapsed-ms (:rf.view/elapsed-ms t))))))

(defn project-all
  "Walk the captured trace events ONCE and emit the three `:sub-runs`,
  `:renders`, `:effects` projections in a single reducer pass. Returns
  `{:sub-runs <v> :renders <v> :effects <v>}` with each value a
  persistent vector built via transient accumulators.

  Per rf2-ecu37 (audit rf2-fzrav §M1): the prior shape was three
  independent `into []` transducer walks of the same buffer — for an
  N-event cascade that's 3·N operation reads where N suffice. The
  fused reducer mirrors `find-trigger-event`'s style (rf2-txrq9):
  one traversal, multiple accumulators, single allocation budget.

  Per-projection contracts preserved verbatim (no schema change):

    :sub-runs — Spec-Schemas §`:rf/epoch-record`. One entry per
      `:sub/run` trace event. Cache-hit subs (rf2-719e fast-path) do
      NOT emit `:sub/run` and are correctly absent.

    :renders — Spec-Schemas §`:rf/epoch-record` and Spec 004 §Render-tree
      primitives (rf2-t5tx Option C / rf2-piag). One entry per
      `:rf.view/rendered` op (the POST-render marker, rf2-8wrzz.1).
      `:render-key` is the `[<view-id> <instance-token>]` tuple; renders
      bypassing reg-view (plain Reagent fns) use `[:rf.view/anonymous nil]`
      as the documented fallback. Each row also carries the per-view
      `:triggered-by` (cause sub-id) + `:elapsed-ms` (render timing) +
      `:mount?` when the op tag carries them.

    :effects — Spec-Schemas §`:rf/epoch-record` `:effects`. Every
      dispatched fx emits exactly one of:

        :fx :rf.fx/handled                    → :outcome :ok
        :warning :rf.fx/skipped-on-platform   → :outcome :skipped-on-platform
        :error :rf.error/fx-handler-exception → :outcome :error
        :error :rf.error/no-such-fx           → :outcome :error

      `:error-trace` (when present) references the corresponding error
      trace event by `:id`."
  [events]
  ;; Single reduce; the accumulator is a 3-key transient map of
  ;; transient vectors. `conj!` may rebind the inner transient vector
  ;; identity at chunk boundaries (every 32 elements), so we thread
  ;; the result back through `assoc!`. The outer transient map is
  ;; mutated in place — no per-step map allocation.
  ;;
  ;; Internal slot keys are `:s` / `:r` / `:e` purely to keep this
  ;; transient namespace local; the documented `:sub-runs` /
  ;; `:renders` / `:effects` shape is materialised once at the end.
  (let [acc (reduce
              (fn [acc ev]
                (let [op (:operation ev)
                      t  (:tags ev)]
                  (cond
                    ;; Per rf2-l1jz8 — the reactive recompute path enriches
                    ;; the `:sub/run` tag with value-change + cascade
                    ;; attribution; `sub-run-row` threads them onto the
                    ;; structured projection (shared with the post-settle
                    ;; back-fill in `listeners`).
                    (= :rf.sub/run op)
                    (assoc! acc :s (conj! (get acc :s) (sub-run-row ev)))

                    ;; rf2-8wrzz.1: source the :renders projection from the
                    ;; POST-render :rf.view/rendered op (carries per-view
                    ;; cause + timing), not the render-START :rf.view/render.
                    (= :rf.view/rendered op)
                    (assoc! acc :r (conj! (get acc :r) (render-row ev)))

                    (= :rf.fx/handled op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id   (:rf.fx/id t)
                                    :args    (:rf.fx/args t)
                                    :outcome :ok}))

                    (= :rf.fx/skipped-on-platform op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id   (:rf.fx/id t)
                                    :args    (:rf.fx/args t)
                                    :outcome :skipped-on-platform}))

                    (= :rf.error/fx-handler-exception op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:rf.fx/id t)
                                    :args        (:rf.fx/args t)
                                    :outcome     :error
                                    :error-trace (:id ev)}))

                    (= :rf.error/no-such-fx op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:rf.fx/id t)
                                    :args        (:rf.fx/args t)
                                    :outcome     :error
                                    :error-trace (:id ev)}))

                    :else acc)))
              (transient {:s (transient [])
                          :r (transient [])
                          :e (transient [])})
              events)]
    {:sub-runs (persistent! (get acc :s))
     :renders  (persistent! (get acc :r))
     :effects  (persistent! (get acc :e))}))

;; ---- trigger-event resolution --------------------------------------------

(defn find-trigger-event
  "Walk the buffered events to find the first :event/run-start trace.
  That carries the `:event`, `:event-id` AND `:dispatch-id` for the
  cascade.

  When the cascade had no successful event handler (e.g. an unknown
  event id or a frame-destroyed dispatch), no :run-start fires; fall
  back to the first event we can find with an `:event-id` tag. Per
  rf2-7kxxx (audit r3 §F2): if that fallback event carries no `:event`
  tag we DO NOT synthesise `[eid]` — that would misrepresent an event
  that originally carried payload as payload-less. `build-record`'s
  conditional `cond->` (rf2-kl5p1) omits the `:trigger-event` slot
  when `:event` is nil, which the schema's open map admits.

  Per rf2-rly4a: the run-start's `:dispatch-id` is surfaced here so
  `build-record` can pin it as a first-class `:dispatch-id` slot on the
  epoch record. The settling cascade's id is the record's stable
  cross-counter-space link to the raw trace stream's cascade list — and
  must NOT depend on `:trace-events` (which `:trace-events-keep` elides
  on older records and the post-settle reactive back-fill pads with
  nil-`:dispatch-id` events). Causa's `:rf.causa/focus` epoch-id
  correlation walks this slot; pre-rf2-rly4a it walked `:trace-events`
  tags, which returned nil whenever the focused cascade's epoch had its
  raw stream elided — starving the epoch-scoped Views + Trace panels.

  Per rf2-txrq9: single-walk reduction over `events` — the original
  two-pass `or`-of-`some` reordered both walks across the buffer
  on the degenerate path. We now accumulate the first
  `:event/run-start` AND the first fallback `:event-id` in one
  traversal and prefer the run-start. Either match short-circuits
  at the earliest moment it can — a run-start hit immediately
  reduces to the final result; a fallback-only stream walks once."
  [events]
  (let [result
        (reduce
          (fn [acc ev]
            (let [tags (:tags ev)]
              (if (= :rf.event/run-start (:operation ev))
                ;; run-start beats the fallback; short-circuit.
                ;; Tag-key reads use the :rf.* namespaced scheme
                ;; (rf2-y4qpy.2); the run-start's :dispatch-id is surfaced
                ;; as a first-class return slot (rf2-rly4a) read off the
                ;; namespaced :rf.trace/dispatch-id tag.
                (reduced {:run-start {:event-id    (:rf.trace/event-id tags)
                                      :event       (:rf.event/v tags)
                                      :dispatch-id (:rf.trace/dispatch-id tags)}})
                ;; Capture the first :event-id we see as the fallback.
                ;; Per rf2-7kxxx: do NOT fabricate `:event` — when the
                ;; tag is absent we leave the field nil so downstream
                ;; build-record omits a misleading synthesised vector.
                ;;
                ;; Per rf2-ee38b (§correctness): the fallback arm does NOT
                ;; pin `:dispatch-id`. Spec-Schemas §`:rf/epoch-record`
                ;; documents `:dispatch-id` as "pinned from the
                ;; `:event/run-start` tag … absent when the cascade carried
                ;; no dispatch-id (rejected dispatch / pre-run-start halt)"
                ;; — the strictly-spec shape for a no-run-start cascade is
                ;; ABSENT. The prior fallback could surface the dispatch-id
                ;; of an arbitrary non-run-start trace (e.g. an error trace
                ;; from a rejected dispatch), which the run-start arm
                ;; (rf2-rly4a) is the canonical source for. Only the
                ;; run-start arm above pins `:dispatch-id`.
                (if (or (:fallback acc) (nil? (:rf.trace/event-id tags)))
                  acc
                  (assoc acc :fallback {:event-id (:rf.trace/event-id tags)
                                        :event    (:rf.event/v tags)})))))
          {}
          events)]
    (or (:run-start result) (:fallback result))))
