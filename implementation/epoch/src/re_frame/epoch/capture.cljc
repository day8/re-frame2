(ns re-frame.epoch.capture
  "Per-cascade trace-buffer write/read and the two read-only walks the
  drain-settle assembly path runs against the harvested events:

    capture-event!     -- the late-bind seam `re-frame.trace` invokes
                          on every emit, gated on `:frame`-tag presence
                          and the `skip-ops` self-emit catalogue.
    project-all        -- one fused reducer pass producing the
                          `:sub-runs`, `:renders`, `:effects` slots.
    find-trigger-event -- one walk extracting `:event-id` + `:event`
                          + `:dispatch-id` + `:rf.cofx` from the cascade's
                          first `:event/run-start`, with a `:event-id`-only
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
    ;; rf2-18g1w — consumer-facing `{:ok :blocked :error}` outcome op.
    ;; Emitted at the same cascade-trailer point as `:rf.epoch/snapshotted`
    ;; (immediately after, with the buffer already harvested). Must be
    ;; skipped for the same reason: re-buffering it would leak it into
    ;; the next cascade's record.
    :rf.epoch/outcome
    ;; restore-epoch! success + the five documented failure modes.
    :rf.epoch/restored
    :rf.epoch/restore-unknown-epoch
    :rf.epoch/restore-schema-mismatch
    :rf.epoch/restore-missing-handler
    :rf.epoch/restore-version-mismatch
    :rf.epoch/restore-during-drain
    :rf.epoch/restore-non-ok-record
    ;; replace-app-db! success + its two failure modes (Tool-Pair §Pair-
    ;; tool writes). All three fire after the synthetic record
    ;; has been built and the cascade-buffer (if any) has been harvested.
    :rf.epoch/db-replaced
    :rf.epoch/replace-during-drain
    :rf.epoch/replace-schema-mismatch
    ;; Redact-fn exception warning (EP-0015 §15 + open-issue 6, RULED /
    ;; Tool-Pair §Time-travel §Redaction hook). Emitted by
    ;; `assembly/apply-redact-fn` at PROJECTION time (`projected-record`),
    ;; which runs outside any cascade; if left un-skipped, the
    ;; `:frame`-tagged emit could accrete into a cascade's harvested
    ;; record for this frame.
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

;; ---- unmount op (rf2-59hx3) -----------------------------------------------
;;
;; The view-teardown sibling of render-ops. `:rf.view/unmounted`
;; (`re-frame.views/emit-view-unmounted!`) fires when a registered-view
;; instance tears down — at React `componentWillUnmount` / `useEffect`
;; cleanup time, AFTER the cascade that removed the view settled. Like a
;; render and a reactive sub-run it fires post-settle (no in-flight cascade
;; for its frame) and carries a `:frame` tag but NO `:rf.trace/dispatch-id`.
;;
;; With no in-flight cascade AND no `:dispatch-id`, it would otherwise fall
;; through the orphan-drop branch below and leave a view unmount with NO
;; signal anywhere in the epoch record — so Xray's VIEWS-step
;; `unmounted-views-rows` (which reads it off `:trace-events`) would have
;; nothing to surface.
;;
;; Instead it is attributed through the SAME post-settle back-fill mechanism
;; renders + sub-runs use (no parallel correlate-by-render-key path): it is
;; back-filled into the cascade that CAUSED the teardown — the frame's
;; most-recently-settled epoch — via the `:epoch/record-unmount!` hook. The
;; unmount carries no structured projection row (it is neither a `:renders`
;; nor a `:sub-runs` entry), so it rides ONLY the `:trace-events` slot,
;; which is exactly where the Xray VIEWS projection reads it.
(def ^:private unmount-ops
  #{:rf.view/unmounted})

(defn in-flight-cascade?
  "True when the frame's in-flight capture buffer already holds an
  `:event/run-start` emit — the canonical signal that a cascade has
  begun draining into this buffer. The single home for the
  'is a cascade in flight for this frame?' question; both the
  post-settle back-fill routing below and
  `re-frame.epoch.listeners/on-frame-destroyed!`'s mid-drain detection
  call it so the gate stays one predicate.

  A render that fires WITH a cascade in flight (synchronous flush — SSR,
  a render triggered inside another render's reactive read) genuinely
  belongs to that cascade and is buffered normally; a render that fires
  with NO cascade in flight is a post-settle async re-render and is
  back-filled to the cascade that caused it. The destroy hook reads the
  same signal to tell a mid-drain destroy (commit a `:halted-destroy`
  partial record) from a registration-time tagalong."
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
  the in-flight cascade and is buffered like any other in-flight emit.

  Per rf2-59hx3: the same post-settle timing applies to a view UNMOUNT
  (`:rf.view/unmounted`) — it fires at React teardown time, AFTER the
  cascade that removed the view settled. With no in-flight cascade and no
  `:dispatch-id` it would otherwise fall through the orphan-drop branch and
  leave a view teardown with no signal, so it is back-filled into the
  causing cascade (the most-recently-settled epoch) via the
  `:epoch/record-unmount!` hook (sibling of the two above), where it rides
  the epoch's `:trace-events` so Xray's VIEWS step can surface it. An
  in-flight unmount (a synchronous teardown inside a drain) belongs to that
  cascade and is buffered like any other in-flight emit."
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
        (when-let [reader-render-key (:rf.sub/reader-render-key tags)]
          (when (= :rf.sub/run op)
            (state/record-render-deps! frame-id reader-render-key (:rf.sub/id tags)))))
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

          ;; Post-settle view unmount — the teardown sibling (rf2-59hx3).
          ;; A `:rf.view/unmounted` fires at React teardown time, AFTER the
          ;; cascade that removed the view settled, so it carries a `:frame`
          ;; tag but no `:dispatch-id` and arrives with an empty buffer.
          ;; Without this arm it would hit the orphan-drop branch below and
          ;; leave the view teardown unrecorded. Back-fill
          ;; it into the causing cascade (the most-recently-settled epoch)
          ;; via `:epoch/record-unmount!` so it lands in that epoch's
          ;; `:trace-events`, where Xray's VIEWS-step `unmounted-views-rows`
          ;; reads it. An in-flight unmount (a synchronous teardown inside a
          ;; drain) is buffered normally by the `:else` arm. Falls through to
          ;; the normal buffer path during the pre-facade-install load-order
          ;; window.
          (and (contains? unmount-ops op)
               (not (in-flight-cascade? frame-id)))
          (if-let [record-unmount! (late-bind/get-fn-cached :epoch/record-unmount!)]
            (record-unmount! frame-id event)
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
          ;; This is the third out-of-cascade emit class: the post-settle
          ;; render/sub-run branches above gate on the same
          ;; `in-flight-cascade?` signal, and an orphan emit with no cascade
          ;; in flight is left uncorrelated here.
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

;; ---- run-cause (for :rf.view/rendered attribution, rf2-25zo2) --------
;;
;; SENSE (rf2-p4cd9c): event-pipeline-run — this attributes a render/sub to
;; the EVENT RUN (one event's traversal) that drove it, keyed off the buffer's
;; `:rf.event/run-start` marker. Renamed cascade-cause -> run-cause per the
;; glw1bh event-pipeline vocabulary; NOT the reactive-graph sense.
;;
;; The Xray Reactive panel needs to attribute each view re-render to the
;; run that drove it: which event kicked off this run, and which
;; subs ran during it. The data is captured at trace-bus emission time
;; (the in-flight run buffer is walked at view-render-emit time), NOT
;; derived post-hoc on inspection — same dataset the rest of the epoch
;; record's projections share.
;;
;; The walk visits the in-flight buffer for the frame at the moment the
;; substrate fires its render. Returns `{:cause-event-id <eid>
;; :cause-subs <sub-ids vector>}`. The first `:event/run-start` we see
;; supplies `:cause-event-id`; every `:sub/run` contributes to
;; `:cause-subs` (deduped, preserving first-seen order). Empty buffer
;; (render outside any run — e.g. fixture-driven direct invocations,
;; or React's post-settle async batch) yields `{}` so consumers see the
;; slots simply absent.

(defn run-cause
  "Walk the frame's in-flight run buffer and return a small map
  attributing the current render to the dispatching run. Used by
  `:rf.view/rendered` emission (per [Spec 009 §`:rf.view/rendered`]
  (009-Instrumentation.md#rfviewrendered) and rf2-25zo2). One pass
  over the buffer; bounded `sub-cap` distinct sub-ids (default 100,
  matching the per-run view-render cap).

  Return-map slots:

    :cause-event-id — the event-id of the first :event/run-start seen
                      in the run (i.e. the dispatching run's
                      trigger event).
    :cause-subs     — distinct sub-ids that ran in the run so far,
                      in first-seen order, capped at sub-cap.
    :value-changed-subs — the SUBSET of subs whose :sub/run reported
                      :rf.sub/value-changed? true (rf2-8wrzz.1). A `set`
                      of sub-ids, independently bounded by sub-cap: the
                      walk gates value-changed accumulation on its own
                      count, mirroring the first-seen scan's cap on :subs
                      (rf2-7n0kf — the value-changed scan is independent
                      of the first-seen scan, so it needs its own guard).
                      Used by the views.cljs emit site to derive
                      :rf.view/triggered-by — the first sub in THIS view's
                      own read-set whose value changed (the precise
                      per-view re-render cause). Empty when no sub changed
                      value (a structural re-render).
    :rendered-so-far — count of :rf.view/rendered already emitted into
                      this run. Used by the views.cljs emit site to
                      enforce the per-run view-render cap.

  Empty buffer (render outside any run — e.g. headless direct
  invocations, or React's post-settle async batch) yields a map with
  `:rendered-so-far 0` and the other slots omitted."
  ([frame-id]
   (run-cause frame-id 100))
  ([frame-id sub-cap]
   (when interop/debug-enabled?
     (let [events  (state/buffer-for frame-id)
           ;; Single reduce: capture first :event/run-start, accumulate
           ;; distinct sub-ids in first-seen order up to `sub-cap`, and
           ;; count the existing :rf.view/rendered emits so the views.cljs
           ;; emit site can enforce the per-run view-render cap.
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
                           ;; site can derive :rf.view/triggered-by. A sub
                           ;; that ran multiple times in the cascade
                           ;; contributes once (set semantics). rf2-7n0kf —
                           ;; independently bounded by `sub-cap`: this scan
                           ;; gates value-changed accumulation on its OWN
                           ;; count, just as the first-seen scan above caps
                           ;; `:subs`. Without this guard a pathological
                           ;; full-page cascade with > sub-cap distinct
                           ;; value-changed sub-ids would grow the set past
                           ;; the cap, contradicting the documented bound and
                           ;; defeating the per-cascade view-render cap's
                           ;; intent for this slot.
                           (and (= :rf.sub/run op)
                                (some? (:rf.sub/id tags))
                                (true? (:rf.sub/value-changed? tags))
                                (< (count (:value-changed-subs acc)) sub-cap))
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
;; `:view/render` projection lands on both surfaces automatically, avoiding
;; a lockstep maintenance hazard across two duplicate row literals.

(defn sub-run-row
  "Project a `:rf.sub/run` trace event into its structured `:sub-runs`
  row, or nil for any non-`:sub/run` op (a `:rf.sub/skip` memo hit
  projects no `:sub-runs` row — it rides only `:trace-events`).

  Per rf2-l1jz8 the reactive recompute path enriches the `:sub/run` tag
  with value-change + cascade attribution (`:value-changed?` /
  `:prev-value` / `:value` / `:cascade?` / `:cause-sub`); they are
  threaded onto the structured projection so Xray's Reactive panel reads
  them off the epoch record (not the raw trace). `compute-sub`'s base-
  shape emit omits them — the slots are simply absent there, which the
  panel's `sub-changed?` / `sub-cascaded?` predicates tolerate (false /
  not-cascaded). The `:sensitive?` stamp on the trace event (rf2-isdwf)
  governs whether `:prev-value` / `:value` already carry the
  `:rf/redacted` sentinel — they ride elide-wire-value at the emit site.

  Per rf2-okz1u / rf2-1cc03 the reactive recompute path additionally
  stamps `:rf.sub/cause-event-id` — the event-id (head keyword of the
  dispatching cascade's trigger event vector) of the cascade whose
  handler-body invalidated this sub's reactive input. The tag is
  OMITTED at the emit site (key absent, not nil) when the sub runs
  outside any in-flight cascade (a post-settle reactive flush against
  no live drain) or when the optional `re-frame.epoch` artefact is not
  on the classpath. The projection threads it `cond->` so the row slot
  is likewise absent in those cases — consumers reading `(:cause-event-id row)`
  see nil and treat as no-attribution, parity with the OMITTED-vs-nil
  semantics of the trace tag.

  PAYLOAD-BEARING value slots (rf2-at60h): `:prev-value` and `:value`
  carry the sub's computed app-data, so this row is NOT value-free. The
  whole-output `:sensitive?` stamp (rf2-isdwf) is already honoured at the
  marks emit site (`re-frame.classification/project-sub-tags`) — `:value` /
  `:prev-value` arrive carrying the `:rf/redacted` sentinel, so no further
  projection is needed for the sensitive case. The whole-output `:large?`
  stamp, however, only marks largeness on the trace tag and leaves the raw
  value intact (the on-box ring must keep the exact value for Xray diff /
  REPL / `restore-epoch!`). We thread that tag onto the row as `:large?` so
  the off-box `projected-record` egress boundary can substitute the
  `:rf.size/large-elided` marker for `:value` / `:prev-value` under the
  `:include-large? false` default. Threaded `cond->` (absent, not false,
  when the sub's output is not large) — parity with the trace tag's
  presence semantics."
  [event]
  (when (= :rf.sub/run (:operation event))
    (let [tags (:tags event)]
      (cond-> {:sub-id         (:rf.sub/id tags)
               :query-v        (:rf.sub/query-v tags)
               :recomputed?    true
               :value-changed? (:rf.sub/value-changed? tags)
               :prev-value     (:rf.sub/prev-value tags)
               :value          (:rf.sub/value tags)
               :cascade?       (:rf.sub/cascade? tags)
               :cause-sub      (:rf.sub/cause-sub tags)}
        (contains? tags :rf.sub/cause-event-id)
        (assoc :cause-event-id (:rf.sub/cause-event-id tags))

        (:large? tags)
        (assoc :large? true)))))

(defn render-row
  "Project a `:rf.view/rendered` trace event into its structured
  `:renders` row, or nil for any other render op (`:rf.view/render` —
  the render-START marker — and `:rf.view/rendered-cap-reached` ride
  only `:trace-events`).

  Per rf2-8wrzz.1 the projection sources from the POST-render
  `:rf.view/rendered` op rather than the render-START `:rf.view/render`,
  because only the post-render op carries the per-view cause + timing the
  Xray Views panel needs — `:triggered-by` (the sub-id that caused this
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
    :cause-event-id — (when present) the event-id of the cascade whose
                    handler-body invalidated a reactive input this view
                    deref'd, triggering the re-render (rf2-1cc03 /
                    rf2-9gquv). The `:rf.view/rendered` op stamps
                    `:rf.view/cause-event-id` (views.cljs) under the same
                    OMITTED-vs-nil semantics as the sub-row's
                    `:rf.sub/cause-event-id`: absent (not nil) for a render
                    outside any in-flight cascade (mount / structural render)
                    or when no cause was derivable. This is the slot the
                    causal/cascade `:view` surface reads
                    (`evidence/reactive-counts` `:by-cause`,
                    `result/causal-count`) — without it every `:renders` row
                    keys as nil and view-render attribution silently reads 0.

  The optional slots are threaded only when the trace tag carries them so
  the row stays minimal for renders outside a cascade / structural
  re-renders, matching the open-map schema."
  [event]
  (when (= :rf.view/rendered (:operation event))
    (let [tags (:tags event)]
      (cond-> {:render-key (or (:rf.view/render-key tags)
                               [:rf.view/anonymous nil])}
        (contains? tags :rf.view/mount?)
        (assoc :mount? (:rf.view/mount? tags))
        (some? (:rf.view/triggered-by tags))
        (assoc :triggered-by (:rf.view/triggered-by tags))
        (some? (:rf.view/elapsed-ms tags))
        (assoc :elapsed-ms (:rf.view/elapsed-ms tags))
        (contains? tags :rf.view/cause-event-id)
        (assoc :cause-event-id (:rf.view/cause-event-id tags))))))

(defn project-all
  "Walk the captured trace events ONCE and emit the three `:sub-runs`,
  `:renders`, `:effects` projections in a single reducer pass. Returns
  `{:sub-runs <v> :renders <v> :effects <v>}` with each value a
  persistent vector built via transient accumulators.

  Fusing the three projections into one reducer pass keeps the buffer walk
  to N operation reads for an N-event drain (three independent `into []`
  transducer walks would cost 3·N). Mirrors `find-trigger-event`'s style
  (rf2-txrq9): one traversal, multiple accumulators, single allocation
  budget.

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
                (let [op   (:operation ev)
                      tags (:tags ev)]
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
                                   {:fx-id   (:rf.fx/id tags)
                                    :args    (:rf.fx/args tags)
                                    :outcome :ok}))

                    (= :rf.fx/skipped-on-platform op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id   (:rf.fx/id tags)
                                    :args    (:rf.fx/args tags)
                                    :outcome :skipped-on-platform}))

                    (= :rf.error/fx-handler-exception op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:rf.fx/id tags)
                                    :args        (:rf.fx/args tags)
                                    :outcome     :error
                                    :error-trace (:id ev)}))

                    (= :rf.error/no-such-fx op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:rf.fx/id tags)
                                    :args        (:rf.fx/args tags)
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
  That carries the `:event`, `:event-id`, `:dispatch-id`, the
  post-generation `:rf.cofx` replay token, AND (rf2-yigokd) the envelope's
  serializable `:fx-overrides` / `:interceptor-overrides` for the cascade.

  Per rf2-yigokd: `:fx-overrides` (already marker-ized for fn-valued entries
  at the router's emission site) and `:interceptor-overrides` are the
  envelope's OWN per-call + lexical override maps (never the per-frame tier)
  — surfaced here so `build-record` can pin them as first-class epoch-record
  slots beside `:rf.cofx`, letting a Tool-Pair strict replay re-supply the
  exact overrides the original run had active.

  Per rf2-1xdotm: the run-start's `:rf.event/cofx` tag is the POST-
  GENERATION flat `:rf.cofx` map (the causal cofx as it was after the
  router's declared-only delivery ran — every generator-backed recordable
  fact minted at processing-start written back into the in-flight
  `:rf.cofx`, plus the framework `:rf/time-ms`). It is surfaced here so
  `build-record` can pin it as a first-class `:rf.cofx` slot, and a
  Tool-Pair replay can re-present the EXACT facts the original run consumed
  under `:rf.cofx/mint-policy :strict` (EP-0017 §Recordable coeffects +
  Tool-Pair §Replay-mint-policy). The slot is dev-only at the source (the
  run-start emit is gated on `interop/debug-enabled?`) and absent on the
  fallback arm (a no-run-start cascade carried no delivered cofx).

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
  nil-`:dispatch-id` events). Xray's `:rf.xray/focus` epoch-id
  correlation walks this slot rather than `:trace-events` tags, which
  return nil whenever the focused cascade's epoch has its raw stream
  elided — that would starve the epoch-scoped Views + Trace panels.

  Per rf2-txrq9: single-walk reduction over `events`. We accumulate the
  first `:event/run-start`, the first fallback `:event-id`, AND
  (rf2-cheez6.1, below) the cascade's mid-drain machine-minted generator
  facts in one traversal and prefer the run-start.

  Per rf2-cheez6.1 / rf2-08br0v — augment the run-start's `:rf.cofx`
  replay token with generator facts MINTED MID-DRAIN. The run-start
  token (`:rf.event/cofx`) is the cofx as it was after the router's
  PRE-handler declared-only delivery — every fact `assemble-initial-ctx`
  minted for the OUTER event's declared `:rf.cofx/requires`. But a state
  machine declares no requires on its outer event; its guard/action
  facts are minted INSIDE the handler (`ensure-ctx-cofx` pre-drain,
  `ensure-raised-cofx` in-drain for a raise-selected guard), AFTER
  run-start was emitted, and are written onto the engine-local machine
  def — they never flow back to the ctx coeffects the run-start read.
  Without this merge the token would carry only externally-supplied facts,
  and a `:strict` replay (mint-policy-aware, rf2-n0myjq) of a machine
  decision gated on such a fact would diverge: the missing fact →
  missing-required while the live run minted a value.

  Every actual recordable mint — pre-handler in `assemble-initial-ctx`
  AND mid-drain in the machine ensure steps — emits a dev-only
  `:rf.cofx/generated` trace (`re-frame.cofx/run-generator`) carrying
  `:rf.cofx/id` + the marks-PROJECTED `:rf.cofx/value` (sensitive slots
  already `:rf/redacted` at the emit site, rf2-0mjgx6). Those emits ride
  the SAME in-flight cascade buffer this walks. So we collect them and
  merge `{id → value}` onto the run-start token. Properties:

    - LIVE behaviour is untouched — this is the read-only epoch-assembly
      walk; nothing in the dispatch path changes.
    - No OVER-capture — only facts a generator ACTUALLY minted emit
      `:rf.cofx/generated` (an ambient supplier emits `:rf.cofx/run`, NOT
      generated; a replayed/supplied recordable fact emits nothing). The
      recordability + redaction contract is honoured at the trace source.
    - IDEMPOTENT for the ordinary event path: a fact minted pre-handler
      is ALREADY in the run-start token verbatim AND re-presented here
      with the identical value, so the merge is a no-op there. The merge
      adds value only for the mid-drain machine mints the token missed.

  On `:strict` replay a token that now carries the minted fact short-
  circuits `deliver-declared-cofx`'s present-on-token branch (delivers
  the recorded value verbatim, no host read) — so the replayed machine
  decision matches the live one. Determinism restored."
  [events]
  (let [result
        (reduce
          (fn [acc ev]
            (let [op   (:operation ev)
                  tags (:tags ev)]
              (cond
                ;; run-start beats the fallback. We DO NOT short-circuit:
                ;; the cascade's `:rf.cofx/generated`
                ;; mint traces fire AFTER run-start, so the walk must
                ;; continue to gather them (below). A second run-start for
                ;; the same buffer is impossible (one per cascade), so the
                ;; first-seen guard keeps the slot stable.
                ;;
                ;; Tag-key reads use the :rf.* namespaced scheme
                ;; (rf2-y4qpy.2); the run-start's :dispatch-id is surfaced
                ;; as a first-class return slot (rf2-rly4a) read off the
                ;; namespaced :rf.trace/dispatch-id tag. Per rf2-1xdotm the
                ;; post-generation `:rf.cofx` replay token rides the run-start
                ;; under `:rf.event/cofx` (dev-only at source) and is surfaced
                ;; here so `build-record` pins it as a first-class slot.
                ;;
                ;; Per rf2-yigokd: the envelope's serializable
                ;; `:fx-overrides` / `:interceptor-overrides` ride the SAME
                ;; run-start emit under `:rf.event/fx-overrides` /
                ;; `:rf.event/interceptor-overrides` (dev-only at source,
                ;; already marker-ized for fn-valued fx entries at the
                ;; router's emission site) — surfaced here under the BARE
                ;; record-layer spelling (matching the dispatch-opts key
                ;; names, so `build-record` can pin them as first-class
                ;; slots a Tool-Pair strict replay splats straight back into
                ;; dispatch opts beside `:rf.cofx`). Static per cascade (the
                ;; envelope is fixed at build-envelope time) — unlike
                ;; `:rf.cofx` there is no mid-drain augmentation to merge.
                (and (= :rf.event/run-start op) (nil? (:run-start acc)))
                (assoc acc :run-start {:event-id      (:rf.trace/event-id tags)
                                       :event         (:rf.event/v tags)
                                       :dispatch-id   (:rf.trace/dispatch-id tags)
                                       :rf.cofx       (:rf.event/cofx tags)
                                       :fx-overrides  (:rf.event/fx-overrides tags)
                                       :interceptor-overrides
                                       (:rf.event/interceptor-overrides tags)})

                ;; rf2-cheez6.1 / rf2-08br0v — accumulate every generator-
                ;; minted recordable fact in cascade order. `:rf.cofx/value`
                ;; is the marks-projected produced value (redaction already
                ;; applied at the emit site). A later same-id mint (a raise
                ;; that re-mints) wins last-write — the LAST value the cascade
                ;; produced for that id is the one a same-id re-presentation
                ;; consumed, matching the in-drain write-back semantics.
                (= :rf.cofx/generated op)
                (cond-> acc
                  (some? (:rf.cofx/id tags))
                  (assoc-in [:minted (:rf.cofx/id tags)] (:rf.cofx/value tags)))

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
                ;; ABSENT. Pinning `:dispatch-id` here would surface the id
                ;; of an arbitrary non-run-start trace (e.g. an error trace
                ;; from a rejected dispatch); the run-start arm (rf2-rly4a)
                ;; is the canonical source for it, so only that arm above
                ;; pins `:dispatch-id`.
                (and (nil? (:fallback acc)) (some? (:rf.trace/event-id tags)))
                (assoc acc :fallback {:event-id (:rf.trace/event-id tags)
                                      :event    (:rf.event/v tags)})

                :else acc)))
          {}
          events)
        ;; rf2-cheez6.1 — merge the cascade's minted facts onto the run-start
        ;; replay token. Token base, minted overlaid: the ordinary event path
        ;; already carries pre-handler mints verbatim (no-op merge), while a
        ;; machine's mid-drain mints — absent from the token — fill in. Only
        ;; when run-start carried a `:rf.cofx` slot at all (dev builds; a
        ;; cascade whose envelope carried no cofx map omits it — there is then
        ;; no token to augment and no mints to merge for that degenerate case).
        run-start (when-let [rs (:run-start result)]
                    (cond-> rs
                      (and (some? (:rf.cofx rs)) (seq (:minted result)))
                      (update :rf.cofx merge (:minted result))))]
    (or run-start (:fallback result))))
