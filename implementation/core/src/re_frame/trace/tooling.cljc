(ns re-frame.trace.tooling
  "Trace tooling sibling of `re-frame.trace` — carries the public
  dev-tooling surface (`register-listener!` / `unregister-listener!` /
  `clear-listeners!` / `trace-buffer` / `clear-trace-buffer!` /
  `configure-trace-buffer!`) and the per-frame
  run-keyed trace rings + listener state.

  ## Per-frame trace rings (rf2-g1b2m / rf2-8uwce)

  Each frame owns its own per-event ring. Storage shape (per frame):

      {:events-retained N               ;; the per-frame ring depth (cap)
       :override?         <boolean>     ;; true iff N came from an explicit
                                        ;; per-frame `:rf.trace/events-retained`
       :run-order [<dispatch-id> ...]  ;; oldest-first per-event slots
       :runs {<dispatch-id> [<event> ...]}}

  - The unit of retention is the EVENT — one dequeued event / pipeline
    run (one `:rf.trace/dispatch-id`) = one slot, regardless of how many
    trace events that run emitted. When event #N+1 arrives, the OLDEST
    slot (and every trace event emitted under its `:dispatch-id`) is
    evicted as a unit.
  - `:events-retained` defaults to 50; per-frame override via
    `:rf.trace/events-retained` on the frame config.
  - `:override?` distinguishes an explicit per-frame override from an
    inherited process default. EVERY ring stores `:events-retained`
    (it is the live cap consulted on each push), so the cap value alone
    cannot tell the two apart — `configure-trace-buffer!` keys on
    `:override?` to decide which rings the new process default may
    retune (rf2-va65k).
  - `:events-retained 0` disables retention (no slots allocated; the
    live stream still fires).
  - Frameless emits (no `:rf.trace/dispatch-id` in scope) **skip the
    rings entirely** (B3 ruling, rf2-g1b2m, 2026-05-25); they stream
    live to registered listeners only.

  ## B4 hot-reload dedup-by-shape (rf2-g1b2m)

  A dev-only process-scoped dedup table tracks the last-emitted
  `:rf.registry/*` shape per `(kind, id)`. Identical shape on re-emit
  = suppress; changed shape = exactly one trace fires. The table is
  cleared by `clear-listeners!` and `clear-trace-rings!`. Per Spec
  009 §Hot-reload dedup — re-emits suppressed by shape.

  Per rf2-qwm0a: `re-frame.trace` itself carries only the hot emit fast
  path (`emit!` / `emit-error!` / `*handler-scope*` + bracket macros).
  The listener registry + ring storage + filter predicate are tooling
  concerns; production counter bundles never touch them.

  Wiring: at ns load this ns publishes the following hooks through
  `re-frame.late-bind`:
    - `:trace.tooling/deliver!`            (emit-time fan-out)
    - `:trace.tooling/dedup-allow?`        (B4 dedup check at registrar
                                            emit sites)
    - `:trace.tooling/release-frame-ring!` (frame-destroy cleanup)
    - `:trace.tooling/configure-trace-buffer!`
    - `:trace.tooling/register-listener!` / `:trace.tooling/unregister-listener!`

  Absent the load (production CLJS bundles that never `:require` this
  ns), every lookup returns nil and the trace fast path / registrar
  short-circuit cleanly.

  Per Spec 009 §Per-frame trace rings."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            ;; rf2-ih437c: `event-bundle` reuses the six-domino fold
            ;; (`absorb` over `empty-event-bundle`) from the projection ns
            ;; rather than re-inlining the classification cond. Both nss
            ;; are dev-side and bundle-isolated from production CLJS (the
            ;; `trace-tooling` bundle-isolation entry pins tooling's
            ;; absence from the counter bundle); projection carries no
            ;; requires of its own, so this edge introduces no cycle.
            [re-frame.trace.projection :as projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))    ;; id → fn

(defn register-listener!
  "Register a listener that receives every trace event. The id can be any
  comparable value; passing the same id twice replaces. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn unregister-listener!
  [id]
  (swap! listeners dissoc id)
  nil)

(declare clear-dedup-table!)

(defn clear-listeners!
  "Drop every registered listener. Also clears the B4 hot-reload
  dedup-by-shape table per Spec 009 §Hot-reload dedup — the dedup
  table is dev-only state, process-scoped, and `clear-listeners!` /
  test-runtime reset fixtures are the canonical clearance points."
  []
  (reset! listeners {})
  (clear-dedup-table!)
  nil)

;; ---- per-frame run-keyed trace rings (dev-only) ---------------------
;;
;; Storage shape (per Spec 009 §Per-frame trace rings):
;;
;;   trace-rings  = {<frame-id> {:events-retained N
;;                               :override?         <boolean>
;;                               :run-order [<dispatch-id> ...]
;;                               :runs       {<dispatch-id> [<event>...]}}}
;;
;; - `:run-order` is the oldest-first vector of `:dispatch-id`s
;;   currently held in this frame's ring; eviction pops the head.
;; - `:runs` is a flat map from `:dispatch-id` → trace events
;;   emitted under that dispatch, in arrival order.
;; - The slot count is bounded by `:events-retained` (default 50) — one
;;   slot per EVENT (one dequeued event / pipeline run), regardless of how
;;   many trace events that run emitted.
;; - `:override?` true iff the cap is an explicit per-frame override;
;;   false for an inherited default.
;;   `configure-trace-buffer!` retunes inherited rings, skips overrides.
;; - Frames lazily allocate slots on first emit. Apps with one frame
;;   pay one slot of ring overhead.

(def ^:private default-events-retained
  "Per Spec 009 §Per-frame trace rings — the per-frame retention default
  when neither `:rf.trace/events-retained` on the frame config nor
  `(configure :trace-buffer ...)` supplies one. 50 retained events (one
  slot per dequeued event / pipeline run) — enough to cover a short
  interactive session without per-frame memory pressure."
  50)

(defonce ^:private trace-rings (atom {}))

;; Process-default events-retained — applies to `:rf/default` and any
;; frame that did not set per-frame `:rf.trace/events-retained` metadata.
;; Per Spec 009 §Retention contract — the single knob.
(defonce ^:private process-events-retained (atom default-events-retained))

(defn- empty-ring
  "A fresh ring at cap `retained`. `override?` records whether the cap is
  an explicit per-frame override (default false = inherited process
  default) so `configure-trace-buffer!` can tell the two apart."
  ([retained] (empty-ring retained false))
  ([retained override?]
   {:events-retained retained
    :override?         override?
    :run-order     []
    :runs          {}}))

(defn- effective-retained
  "Return the live events-retained cap for `frame-id`. Honours the
  ring's stored cap (whether it came from an explicit per-frame override
  or a previously-inherited process default — both store
  `:events-retained`) before falling back to the current process
  default for a frame that has no ring yet."
  [rings frame-id]
  (or (get-in rings [frame-id :events-retained])
      @process-events-retained))

(defn- resize-ring
  "Return `existing` resized to `retained`, preserving the newest slots and
  stamping whether the cap is a frame override or the inherited process
  default. Always returns a complete ring."
  [existing retained override?]
  (let [order (or (:run-order existing) [])
        runs  (or (:runs existing) {})]
    (cond
      (zero? retained)
      (empty-ring 0 override?)

      (> (count order) retained)
      (let [drop-n     (- (count order) retained)
            evicted    (subvec order 0 drop-n)
            kept-order (subvec order drop-n)]
        {:events-retained retained
         :override?      override?
         :run-order      kept-order
         :runs           (apply dissoc runs evicted)})

      :else
      {:events-retained retained
       :override?      override?
       :run-order      order
       :runs           runs})))

(defn apply-frame-events-retained-policy!
  "Publish one frame config's retention policy when `current?` still says that
  config owns the authoritative frame record.

  `override? true` applies `retained` as the explicit
  `:rf.trace/events-retained` cap. `override? false` removes any prior override,
  resizes the existing ring to the live process default, and marks it inherited
  so later process-default changes continue to reach it. The liveness predicate
  runs INSIDE the trace-ring atom update; the frame engine serializes auxiliary
  publication around it. This makes the store respect the frame registry's
  winner without a second ownership registry."
  [frame-id override? retained current?]
  (when (and interop/debug-enabled?
             (or (not override?)
                 (and (number? retained) (not (neg? retained)))))
    (swap! trace-rings
           (fn [rings]
             (if-not (current?)
               rings
               (if override?
                 (assoc rings frame-id
                        (resize-ring (get rings frame-id) retained true))
                 (if-let [existing (get rings frame-id)]
                   (assoc rings frame-id
                          (resize-ring existing @process-events-retained false))
                   rings))))))
  nil)

(defn release-frame-ring!
  "Drop `frame-id`'s ring and per-frame retention setting entirely.
  Called from `destroy-frame!` so a destroyed frame leaves no residual
  ring state in memory. Per Spec 002 §Destroy and Spec 009 §Per-frame
  trace rings."
  [frame-id]
  (when interop/debug-enabled?
    (swap! trace-rings dissoc frame-id))
  nil)

(defn- push-to-ring!
  "Append `ev` to its frame's run-keyed ring. Frameless emits (no
  `:rf.trace/dispatch-id` in scope) bypass the ring entirely per the
  B3 ruling — they stream live to listeners only and are never retained.

  The destination frame-id is the event's own `[:tags :frame]` — the
  single canonical raw-event frame path (Spec 009 §Frame identity on
  the raw event; `re-frame.trace/trace-event-frame` is the accessor
  consumers outside the trace subsystem read it through, and this ns
  cannot require it — `re-frame.trace` requires THIS ns).

  This used to be a three-tier chain — the tag, then a top-level
  `:frame` on the envelope, then the late-bound
  `:frame/current-frame-id` hook — because emit sites that didn't stamp
  the tag left the ring nothing else to route on. `build-event` now
  supplies the tag from the ambient frame for every such site
  (`re-frame.trace/stamp-frame`, rf2-hbmeb), so the fallbacks were the
  SAME resolution done a second time; keeping them would let the ring
  and the event's own tag disagree about which frame a row belongs to.
  There is no top-level `:frame` on a raw trace event at all.

  No-op in production (production never reaches the emit site)."
  [ev]
  (when interop/debug-enabled?
    (let [dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
          frame-id    (get-in ev [:tags :frame])]
      ;; Frameless emits (no in-flight run) skip the ring. The B3
      ;; ruling is that frameless events stream live to listeners only
      ;; and are never retained. A `:dispatch-id` without a resolvable
      ;; frame-id (extremely rare — would require a manual emit from
      ;; outside any framework boundary) also skips the ring; there is
      ;; nowhere to put it.
      (when (and dispatch-id frame-id)
        (swap! trace-rings
               (fn [rings]
                 (let [retained  (effective-retained rings frame-id)
                       override? (true? (get-in rings [frame-id :override?]))]
                   (if (zero? retained)
                     ;; Disabled-but-live: live stream still fires
                     ;; (push-to-ring! is one step in the pipeline);
                     ;; just don't allocate. Preserve the override flag so
                     ;; a 0-cap override survives a push attempt.
                     (assoc rings frame-id (empty-ring 0 override?))
                     (let [existing (get rings frame-id (empty-ring retained override?))
                           ;; Defensive nil-coercion: a ring written before
                           ;; this frame's first emit is now always complete
                           ;; (set-frame-events-retained! writes a full
                           ;; ring), but coerce anyway so any future partial
                           ;; ring can't make `conj` build a list / `subvec`
                           ;; throw (rf2-va65k finding 2).
                           order    (or (:run-order existing) [])
                           runs (or (:runs existing) {})
                           known?   (contains? runs dispatch-id)
                           order'   (if known? order (conj order dispatch-id))
                           runs' (update runs dispatch-id
                                             (fnil conj []) ev)
                           ;; Evict oldest run slots while over cap.
                           [order'' runs'']
                           (loop [o order' c runs']
                             (if (> (count o) retained)
                               (let [oldest (first o)]
                                 (recur (subvec o 1) (dissoc c oldest)))
                               [o c]))]
                       (assoc rings frame-id
                              {:events-retained retained
                               :override?         override?
                               :run-order     order''
                               :runs          runs''}))))))))))

;; ---- B4 hot-reload dedup-by-shape (process-scoped) ----------------------
;;
;; Per Spec 009 §Hot-reload dedup — re-emits suppressed by shape.
;; The dedup table is `{(kind, id) → <shape>}` and is consulted by the
;; registrar via the `:trace.tooling/dedup-allow?` late-bind hook.
;; Identical shape on re-emit → suppressed; changed shape (or no prior
;; entry) → allowed.
;;
;; Shape composition:
;;   - For `:rf.registry/handler-registered`: the registration's
;;     `:handler-fn` identity + the metadata fields that affect the
;;     observable contract (`:doc`, `:schema`, `:interceptors`, `:tags`,
;;     plus per-kind extras carried directly on the meta map).
;;   - For `:rf.registry/handler-replaced`: same shape composed from the
;;     new metadata. Re-emit suppressed iff matches the prior entry.
;;   - For `:rf.registry/handler-cleared`: the shape is the sentinel
;;     `::cleared` (= `:re-frame.trace.tooling/cleared`). A second clear-of-a-cleared id
;;     (e.g. a double-clear) is suppressed.
;;
;; The same dedup keys both `:rf.registry/handler-registered` and
;; `:rf.registry/handler-replaced` (the registrar already routes
;; first-time vs re-registration to the right operation). Hot-reload
;; that re-evaluates a namespace with unchanged handlers emits ZERO
;; trace events from the registrar.

(defonce ^:private dedup-table (atom {}))

(defn- handler-shape
  "Compute a value representing the observable shape of a registration's
  metadata. Used to decide whether a re-emit is genuinely a change.

  Excludes source-coord keys (`:ns` / `:file` / `:line` / `:column`) —
  source-coord drift across a hot-reload is expected and not a
  user-visible change. Includes everything that affects the runtime
  contract (`:handler-fn` identity, `:doc`, `:schema`, `:interceptors`,
  `:tags`, plus the full set of remaining meta keys as a hash).

  Returns a small map suitable for `=` comparison."
  [meta]
  (let [strip (dissoc meta :ns :file :line :column)
        ;; Hash the residual meta-map (after pulling out the high-signal
        ;; slots) so semantically-equivalent meta maps compare equal
        ;; without an O(n) compare each time.
        residual (dissoc strip :handler-fn :doc :schema :interceptors :tags)]
    {:handler-fn   (:handler-fn meta)
     :doc          (:doc meta)
     :schema       (:schema meta)
     :interceptors (:interceptors meta)
     :tags         (:tags meta)
     :residual     (hash residual)}))

(defn dedup-allow?
  "Return true iff emitting `operation` for `[kind id]` is a genuine
  change (or has no prior entry). Updates the dedup table side-effecting:

  - `:rf.registry/handler-registered` / `:rf.registry/handler-replaced` —
    compute the new shape; allow iff the stored shape differs (or absent).
    On allow, record the new shape under `[kind id]`.
  - `:rf.registry/handler-cleared` — allow iff the stored shape is NOT
    already `::cleared`. On allow, record `::cleared` so a subsequent
    re-clear is suppressed.

  Dev-only — no-op (returns true, allow-by-default) in production. The
  registrar's emit sites stay gated on `interop/debug-enabled?` so prod
  never reaches this fn at all.

  Per Spec 009 §Hot-reload dedup."
  [operation kind id meta]
  (cond
    (not interop/debug-enabled?) true

    (or (= operation :rf.registry/handler-registered)
        (= operation :rf.registry/handler-replaced))
    (let [k         [kind id]
          new-shape (handler-shape meta)
          prev      (get @dedup-table k)]
      (if (= prev new-shape)
        false
        (do (swap! dedup-table assoc k new-shape)
            true)))

    (= operation :rf.registry/handler-cleared)
    (let [k    [kind id]
          prev (get @dedup-table k)]
      (if (= prev ::cleared)
        false
        (do (swap! dedup-table assoc k ::cleared)
            true)))

    :else true))

(defn clear-dedup-table!
  "Reset the B4 dedup-by-shape table to empty. Called by
  `clear-listeners!` and `clear-trace-rings!` so test-runtime reset
  fixtures don't leak dedup state across runs.

  Per Spec 009 §Hot-reload dedup — \"the dedup table is cleared by
  `clear-listeners!` / test-runtime reset fixtures\"."
  []
  (reset! dedup-table {})
  nil)

;; ---- trace-buffer reader -------------------------------------------------
;;
;; Per Spec 009 §`trace-buffer` API — per-frame, event bundles by
;; default. `:flat true` opt returns raw trace events.

(defn- event-bundle
  "Project the events for one pipeline run into a Spec 009 event bundle
  (the `group-by-event` shape, per run / dequeued event).

  rf2-ih437c: folds with `re-frame.trace.projection/absorb` over
  `projection/empty-event-bundle` — the canonical six-domino classification
  + slot set — rather than re-inlining the bucketing cond + the six
  `*-bucket?`/`*-marker?` predicates a second time. The seed map carries
  the extra `:trace-events` slot the event-bundle wire shape needs;
  `absorb` only writes the domino slots, so `:trace-events` (and the
  pre-seeded `:dispatch-id` / `:frame`) survive the fold untouched."
  [frame-id dispatch-id events]
  (reduce projection/absorb
          (assoc projection/empty-event-bundle
                 :dispatch-id  dispatch-id
                 :frame        frame-id
                 :trace-events events)
          events))

(defn- match-event-bundle?
  "Filter an event bundle against the bundle-level filter keys."
  [bundle {:keys [event-id origin dispatch-id since-ms between pred]}]
  (let [[t0 t1] (when (and (sequential? between)
                           (= 2 (count between)))
                  between)
        events  (:trace-events bundle)
        ;; An event bundle's "time" is its earliest event's time (where present)
        first-time (some :time events)]
    (and (or (nil? event-id)
             (= event-id (first (:event bundle))))
         (or (nil? origin)
             (when-let [d (:dispatched bundle)]
               (= origin (get-in d [:tags :rf.event/origin]))))
         (or (nil? dispatch-id)
             (= dispatch-id (:dispatch-id bundle)))
         (or (nil? since-ms)
             (and (number? first-time) (> first-time since-ms)))
         (or (nil? t0)
             (and (number? first-time)
                  (<= t0 first-time t1)))
         (or (nil? pred) (pred bundle)))))

(defn- match-event?
  "Filter a raw trace event against the event-level filter keys."
  [ev {:keys [operation op-type since severity event-id handler-id source
              origin dispatch-id since-ms between sensitive? pred]
       :as opts}]
  (let [[t0 t1] (when (and (sequential? between)
                           (= 2 (count between)))
                  between)]
    (and (or (nil? operation) (= operation (:operation ev)))
         (or (nil? op-type)   (= op-type   (:op-type ev)))
         (or (nil? since)     (and (number? (:id ev)) (> (:id ev) since)))
         (or (nil? severity)  (= severity (:op-type ev)))
         (or (nil? event-id)
             (= event-id (get-in ev [:tags :rf.trace/event-id])))
         (or (nil? handler-id)
             (= handler-id (get-in ev [:tags :handler-id])))
         (or (nil? source)
             (= source (or (:source ev)
                           (get-in ev [:tags :source]))))
         (or (nil? origin)
             (= origin (get-in ev [:tags :rf.event/origin])))
         (or (nil? dispatch-id)
             (= dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])))
         (or (nil? since-ms)
             (and (number? (:time ev)) (> (:time ev) since-ms)))
         (or (nil? t0)
             (and (number? (:time ev)) (<= t0 (:time ev) t1)))
         (or (nil? sensitive?)
             (= (true? sensitive?) (true? (:sensitive? ev))))
         (or (nil? pred) (pred ev)))))

(defn- frame-ring-event-bundles
  "Materialise `frame-id`'s ring as an oldest-first vector of event
  bundles. Returns an empty vector when the frame has no recorded
  events (including the destroyed-frame / unknown-frame case per
  Spec 009 §Reads against a destroyed / missing frame)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:run-order ring)
          runs (:runs ring)]
      (mapv (fn [dispatch-id]
              (event-bundle frame-id dispatch-id (get runs dispatch-id)))
            order))
    []))

(defn- frame-ring-flat-events
  "Materialise `frame-id`'s ring as an oldest-first vector of raw trace
  events (the `:flat true` opt-in shape)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:run-order ring)
          runs (:runs ring)]
      (into [] (mapcat (fn [did] (get runs did))) order))
    []))

(defn trace-buffer
  "Per-frame trace ring reader. Returns the named frame's retained
  events, oldest-first (one bundle per dequeued event / pipeline run).

  Two arities:
    (trace-buffer frame-id)        — event bundles (default)
    (trace-buffer frame-id opts)   — opts filter map

  Event-bundle shape:
    {:dispatch-id <id>
     :frame       <frame-id>
     :trace-events [<ev> ...]
     :event       <event-vector or nil>
     :dispatched  <:rf.event/dispatched event or nil>
     :handler     <:rf.event/run-end event or nil>
     :fx          <:rf.fx/do-fx event or nil>
     :effects     [...]
     :subs        [...]
     :renders     [...]
     :other       [...]
     :parent-dispatch-id <causal-parent-id or nil>}

  Opt keys recognised (per Spec 009 §Filter vocabulary):
    :flat true      — return raw trace events (oldest-first) instead
                      of event bundles. The escape hatch for callers
                      with pre-existing flat-stream code.
    :operation      — (:flat-only) exact :operation match
    :op-type        — (:flat-only) exact :op-type match
    :since          — (:flat-only) :id strictly greater than this
    :severity       — (:flat-only) :op-type ∈ #{:error :warning :info}
    :handler-id     — (:flat-only) :tags :handler-id match
    :source         — (:flat-only) :source match (per rf2-1ve9h, this is
                      now the single closed-enum functional-origin axis
                      — the prior `:rf/dispatch-origin` filter key was
                      collapsed)
    :sensitive?     — (:flat-only) :sensitive? match
    :event-id       — bundle :event first-element OR (:flat) :tags :rf.trace/event-id
    :origin         — bundle root :rf.event/origin OR (:flat) per-event
    :dispatch-id    — bundle :dispatch-id OR (:flat) per-event
    :since-ms       — bundle earliest-time OR (:flat) per-event :time
    :between [t0 t1]— same axis, window
    :pred           — arbitrary predicate, receives bundle or event

  Returns `[]` for a destroyed / never-registered frame (per Spec 009
  §Reads against a destroyed / missing frame) and `[]` in production
  (the ring is never allocated under `goog.DEBUG=false`).

  Per Spec 009 §`trace-buffer` API."
  ([frame-id] (trace-buffer frame-id {}))
  ([frame-id opts]
   (if-not interop/debug-enabled?
     []
     (let [rings @trace-rings]
       (if (:flat opts)
         (filterv #(match-event? % opts) (frame-ring-flat-events rings frame-id))
         (filterv #(match-event-bundle? % opts) (frame-ring-event-bundles rings frame-id)))))))

(defn clear-trace-buffer!
  "Empty the named frame's run-keyed ring. Tooling uses this between
  sessions. No-op for an unknown frame, no-op in production. Per Spec
  009 §`trace-buffer` API."
  [frame-id]
  (when interop/debug-enabled?
    (swap! trace-rings
           (fn [rings]
             (if (contains? rings frame-id)
               (let [retained  (effective-retained rings frame-id)
                     ;; Preserve the override flag — emptying the buffer
                     ;; must not silently downgrade an explicit per-frame
                     ;; override to inherited (rf2-va65k).
                     override? (true? (get-in rings [frame-id :override?]))]
                 (assoc rings frame-id (empty-ring retained override?)))
               rings))))
  nil)

(defn clear-trace-rings!
  "Drop EVERY frame's ring + reset the process-default
  events-retained back to its initial value + clear the B4 dedup
  table. Test-fixture / `clear-all!`-shaped helper — symmetric with
  the listener registry's `clear-listeners!`. Production no-op."
  []
  (when interop/debug-enabled?
    (reset! trace-rings {})
    (reset! process-events-retained default-events-retained)
    (clear-dedup-table!))
  nil)

(defn configure-trace-buffer!
  "Apply a process-default ring depth. Per Spec 009 §Retention contract:

      (configure :trace-buffer {:events-retained N})

  Sets the per-process default that applies to `:rf/default` and to
  every frame that did not set its own `:rf.trace/events-retained`
  metadata. Frames with explicit per-frame metadata are NOT overridden.
  The retained unit is one slot per EVENT (one dequeued event / pipeline
  run), regardless of how many trace events that run emitted.

  When `:events-retained 0`, the ring is disabled but the surface
  remains live. Per Spec 009 §Events-retained-zero semantics.

  `:events-retained` is the SOLE recognised opt. An opts map that
  lacks a usable `:events-retained` (e.g. the retired `{:depth N}`
  shape, or a negative / non-numeric value) is a no-op that emits a
  `:rf.warning/trace-buffer-unrecognised-opts` trace rather than
  failing silently — a misconfigured retention knob would otherwise
  leave the ring at its default while the caller believes it was
  tuned (per Spec 009 §Error & warning catalog).

  No-op in production. Returns nil."
  [{:keys [events-retained] :as opts}]
  (when (and interop/debug-enabled?
             (not (and (number? events-retained)
                       (not (neg? events-retained)))))
    ;; Loud-not-silent: a config call that supplied opts we can't apply
    ;; is a misuse the runtime recovers from (the ring stays at its
    ;; current default) but must surface. Routed through the late-bound
    ;; `:trace/emit!` (NOT `:trace/emit-error!`, which hardcodes
    ;; op-type `:error`) so this ns carries no static dep on
    ;; `re-frame.trace`; the category's `:rf.warning/*` root and its
    ;; Spec 009 catalogue row are both `:op-type :warning` — a
    ;; `{:severity :warning}` `trace-buffer` filter must catch this
    ;; emit, which `match-event?`'s `:severity` key matches against
    ;; `(:op-type ev)` (rf2-ho20xj).
    (when-let [emit! (late-bind/get-fn :trace/emit!)]
      (emit! :warning :rf.warning/trace-buffer-unrecognised-opts
             {:category :rf.warning/trace-buffer-unrecognised-opts
              :opts     opts
              :reason   (str "(configure! {:trace-buffer ...}) accepts only "
                              "{:events-retained N} where N is a non-negative "
                              "integer; got " (pr-str opts) ". Retention is "
                              "unchanged. The retired {:depth N} shape is not "
                              "supported — use {:events-retained N}.")})))
  (when (and interop/debug-enabled?
             (number? events-retained)
             (not (neg? events-retained)))
    (reset! process-events-retained events-retained)
    ;; Apply the new default to every frame whose cap was INHERITED
    ;; (`:override? false`), retuning + trimming the already-allocated
    ;; ring. Frames carrying an explicit per-frame override
    ;; (`:override? true`) are left untouched. Keying on `:override?`
    ;; (not the always-present `:events-retained` key) is the fix for
    ;; rf2-va65k finding 1: every allocated ring stores
    ;; `:events-retained`, so the old `(some? (get-in ... :events-retained))`
    ;; guard was always true and silently skipped EVERY existing ring —
    ;; lowering the default never trimmed an already-used inherited frame.
    (swap! trace-rings
           (fn [rings]
             (reduce-kv
              (fn [acc frame-id ring]
                (if (true? (:override? ring))
                  acc
                  (let [order    (or (:run-order ring) [])
                        runs (or (:runs ring) {})]
                    (cond
                      (zero? events-retained)
                      (assoc acc frame-id (empty-ring 0))

                      (> (count order) events-retained)
                      (let [drop-n     (- (count order) events-retained)
                            kept-order (subvec order drop-n)
                            evicted    (subvec order 0 drop-n)]
                        (assoc acc frame-id
                               {:events-retained events-retained
                                :override?         false
                                :run-order     kept-order
                                :runs          (apply dissoc runs evicted)}))

                      :else
                      (assoc acc frame-id
                             {:events-retained events-retained
                              :override?         false
                              :run-order     order
                              :runs          runs})))))
              rings
              rings))))
  nil)

;; ---- delivery hook ------------------------------------------------------
;;
;; `re-frame.trace/deliver!` looks this up via late-bind and calls it
;; once per emitted event (after the epoch-capture fan-out). Centralising
;; the ring-push + listener fan-out in one hook keeps trace.cljc free
;; of any reference to the rings state or listener atom — so a
;; production build that never `:requires` this ns DCEs the whole body.

;; ---- reentrant fan-out scheduler (rf2-1zxlsm ordering + rf2-s522m sync) ----
;;
;; A listener callback may reentrantly emit a trace (dispatch, re-register a
;; flow, create/destroy a frame — all emit). Two delivery laws must BOTH hold for
;; such a nested emit (Spec 009 §Listener invocation rules + §Emitting trace
;; events):
;;
;;   1. EMISSION ORDER (rf2-1zxlsm). Every listener sees events in the order the
;;      runtime fired them. If a listener handling outer event A emits B, no
;;      listener may observe B before A — a nested fan-out must not overtake the
;;      still-in-progress outer one.
;;   2. SYNCHRONOUS COMPLETION (rf2-s522m). `emit!` returns only after its event
;;      has reached every eligible listener — for a NESTED emit too. A listener
;;      that emits B and then inspects another listener's state must see B already
;;      delivered (Spec 009: "the emit returns once every listener has been
;;      invoked").
;;
;; These pull against each other at the reentrant seam. When A's fan-out is paused
;; inside listener Lk's callback and Lk emits B, law 2 says B must reach the
;; not-yet-visited listeners before `emit!` returns, but law 1 says those
;; listeners must see A before B. The ONLY schedule satisfying both is: advance A
;; to the remaining listeners FIRST, then deliver B to all — all before the nested
;; `emit!` returns. A fire-and-drain-later append (the prior design) satisfied
;; law 1 but broke law 2: the nested emit returned before ANY listener saw B.
;;
;; The scheduler is a shared, resumable driver over a FIFO event queue. A single
;; `*fanout-ctx*` (bound for the duration of the outermost fan-out) holds:
;;   :q       — FIFO vector of `[event continue?]`, appended by reentrant emits;
;;   :head    — index of the event currently being delivered; `[0,:head)` done;
;;   :entries — the listener snapshot for the current event (taken once, so a
;;              resumed delivery neither re-snapshots nor double-delivers);
;;   :lcursor — how many of `:entries` have received the current event.
;; `drive-fanout!` walks this to completion: for each event, deliver to every
;; remaining listener (advancing `:lcursor` BEFORE the call so a reentrant emit
;; resumes at the NEXT listener), then advance `:head`. Delivery is strictly FIFO
;; across events, so every listener sees A fully before B — law 1.
;;
;; A reentrant emit APPENDS its event and then calls `drive-fanout!` itself:
;; because `:head` / `:lcursor` are shared, that call advances the paused outer
;; delivery to completion and then delivers the reentrant event, all before the
;; nested `emit!` returns — law 2. When control unwinds to the outer driver, its
;; loop finds the queue drained (`:head` past the end) and stops, so no work is
;; repeated. The delivery SCHEDULE (which listener sees which event, and in what
;; order) is the same FIFO schedule the prior drain produced; only the nested
;; `emit!`'s RETURN is now blocked until its event has been delivered. The ring
;; push still happens inline in emission order (A pushed before the outer drive,
;; B pushed when its reentrant `deliver-to-tooling!` runs), epoch capture already
;; fired in `re-frame.trace/deliver!` before this hook, and classification ran in
;; `emit!`.
;;
;; Composes with rf2-eaxnai: each queued event carries its OWN `continue?`
;; snapshot; the driver consults it per event, so a listener that destroys the
;; outer incarnation flips A's `continue?` false and suppresses A's remaining
;; fan-out without touching a later event's authority. The listener body already
;; ran under the neutral continuation scope `re-frame.trace/deliver!` established.
;;
;; ---- cross-thread fan-out serialization (rf2-uw7hg) -----------------------
;;
;; `*fanout-ctx*` is per-thread (a dynamic binding), so it only schedules
;; SAME-thread reentrant emits. It says nothing about two emits racing on two JVM
;; threads: each opens its OWN outermost fan-out and each would invoke the SAME
;; registered listener callback CONCURRENTLY. That violates the public listener
;; contract (`docs/api/re-frame.core.md`: "Delivery is synchronous: the callback
;; returns before the next record") and Spec 009 §The listener contract, which
;; require synchronous, in-order, event-at-a-time delivery PER listener — a
;; callback the tool author was told can never re-enter itself would be run on two
;; threads at once, and B could reach a listener before A even when A's emit began
;; first.
;;
;; A single process-global monitor (`fanout-monitor`) serializes each OUTERMOST
;; EXTERNAL/clean fan-out — its whole `drive-fanout!` (outer advance + reentrant
;; deliveries) included. Concurrent clean emits therefore linearize on
;; monitor-acquisition order: the second thread cannot begin its fan-out until the
;; first thread's outermost drive has fully drained. No listener callback ever
;; overlaps itself, records reach each listener in a single defined order, and an
;; emit still returns only after its record's callback has run (the monitor is
;; held for the whole synchronous drive and released before `emit!` returns).
;;
;; ---- the post-drain deferral seam (rf2-jl75r + rf2-rakqk + rf2-wxy1c) -------
;;
;; Holding `fanout-monitor` across arbitrary listener bodies is NOT safe for an
;; emit issued while the emitting thread holds a frame's `:drain-lock`. Public
;; trace listeners are allowed to call `dispatch-sync`, which forms a hard AB-BA
;; cycle:
;;   - T1 acquires `fanout-monitor` (a clean emit), enters a listener, calls
;;     `dispatch-sync` into frame F, and spin-waits on F's `:drain-lock`;
;;   - T2 already holds F's `:drain-lock` through `run-one-pass!`, reaches an
;;     ordinary in-run emit (e.g. `:rf.event/run-start`), and would block
;;     acquiring `fanout-monitor`.
;; Neither can progress. `drain-block!`'s bounded-wait assumption is false because
;; the active drainer (T2) is itself waiting on the caller (T1).
;;
;; jl75r broke that cycle by routing a drain-owned emit's fan-out INLINE, off the
;; monitor; rakqk made the discriminator real drain-lock OWNERSHIP rather than
;; event-shape inference. Both fixes shared one assumption — that an inline
;; drain-owned fan-out is already mutually exclusive, because a frame has a single
;; drainer. It is not. The drain-lock serializes ONE FRAME's drain; the listener
;; registry is PROCESS-global. Two INDEPENDENT frames draining on two JVM threads
;; each own their own lock, so both qualified for the inline path and both entered
;; the same arbitrary listener callback at once (rf2-wxy1c) — the rf2-uw7hg serial
;; law lost again, this time drain-vs-drain, with arbitrary programmer / tool
;; listener code additionally running while the framework held a drain lock.
;;
;; The repair is to the TIMING, not the classifier. A drain-owned emit no longer
;; fans out at all while the lock is held: it is APPENDED to a per-thread pending
;; vector and delivered at ONE explicit post-drain boundary
;; (`call-with-deferred-fanout`, established by `re-frame.router/drain-try!` /
;; `drain-block!` and `re-frame.frame/call-serialized-with-drain!` around their
;; whole acquire → run → release region), where the batch is flushed under
;; `fanout-monitor` before the enclosing dispatch / drain call returns.
;;
;; That single change discharges both laws at once, and the dynamic scope IS the
;; ownership evidence — no frame resolution, no ownership probe, no shape guess:
;;
;;   - SERIAL (rf2-uw7hg / rf2-wxy1c). Every listener fan-out — clean or
;;     drain-owned — now runs under `fanout-monitor`. No callback overlaps itself
;;     and records reach each listener in one process-wide order.
;;   - DEADLOCK-FREE (rf2-jl75r). The monitor is acquired at exactly two places:
;;     an outermost emit OUTSIDE any deferral scope, and the post-drain flush. The
;;     scope is bound for the entire dynamic extent in which the thread can hold a
;;     drain-lock (entered before the acquire, exited after the release), so an
;;     unscoped emit PROVES its thread holds no drain-lock, and the flush runs
;;     with the lock already dropped and the scope rebound nil. No thread ever
;;     waits on the monitor while owning a drain-lock, so the AB-BA edge cannot
;;     exist — an invariant of the seam's construction, not a property of any
;;     particular interleaving.
;;
;; Delivery CONTENT is unchanged — only its timing. Two things are captured at
;; append time so a deferred fan-out delivers exactly what an inline one would
;; have: the listener SNAPSHOT (so a listener registered / unregistered later in
;; the same drain does not gain or lose an earlier event), and a baseline-relative
;; reading of `continue?` (`deferred-continue`) so that only a suppression a
;; listener causes DURING the fan-out stops it — the ordinary post-drain falsity of
;; a context-dependent fence never does. See `deferred-continue` for why a bare
;; live re-read of that fence is unsound once the drain has unwound.
;;
;; Emission ORDER is preserved:
;; the ring push and epoch capture still run inline, the pending vector
;; is FIFO, and the whole batch is flushed under one monitor hold, so a drain's
;; own traces reach listeners contiguously and in order. The WHOLE captured batch
;; is queued onto ONE fan-out schedule before any callback runs (rf2-t6vs3) —
;; `run-outermost-fanout!` for a top-level flush, `into` the active `*fanout-ctx*`
;; for an integrated one — so a listener that authors a later trace while an
;; earlier deferred entry is being fanned out appends BEHIND the still-pending
;; later entries rather than overtaking them, exactly as an inline delivery of the
;; same run would have ordered them.
;;
;; Same-thread reentrancy does NOT re-acquire the monitor: a nested emit sees
;; `*fanout-ctx*` already bound and takes the append+drive branch below, so it
;; never reaches the lock — no self-deadlock, and the reentrant schedule advances
;; the SAME critical section. `locking` is a reentrant monitor regardless, so a
;; listener whose `dispatch-sync` drains a frame and flushes that drain's deferred
;; batch while its own fan-out still holds the monitor simply re-enters it.
;;
;; CLJS is single-threaded: no concurrent emits, no monitor, no deferral (the
;; scope is never bound and `call-with-deferred-fanout` is the identity call).
;; Delivery stays inline exactly as before, preserving production elision.

(def ^:private ^:dynamic *fanout-ctx*
  "When bound, the shared fan-out schedule for the outermost listener fan-out in
  progress on this thread — a map of volatiles `{:q :head :entries :lcursor}`
  (see the section note above). A trace emitted reentrantly from inside a listener
  body appends to `:q` and drives the same schedule, so every listener observes
  the outer event before the reentrant one (rf2-1zxlsm) AND the nested `emit!`
  returns only after its event reached every listener (rf2-s522m). nil at the top
  of the stack."
  nil)

#?(:clj
   (def ^:private ^Object fanout-monitor
     "Process-global JVM monitor serializing EVERY outermost trace-listener
     fan-out across concurrent emits (rf2-uw7hg / rf2-wxy1c), so no registered
     listener callback is ever invoked on two threads at once and records reach
     each listener in one defined order. Held for the whole synchronous drive of a
     clean emit, and for the whole post-drain flush of a deferred batch. Reentrant
     same-thread emits never acquire it (they advance the bound `*fanout-ctx*`
     schedule), and a thread that owns a frame's `:drain-lock` never acquires it
     (it appends to `*deferred-drain-fanout*` instead — see the seam note above).
     CLJS is single-threaded and has no counterpart."
     (Object.)))

#?(:clj
   (def ^:private ^ThreadLocal deferred-drain-fanout
     "When set, the post-drain deferral scope for THIS thread: a volatile FIFO
     vector of `[event continue? entries]` triples awaiting listener delivery.

     `call-with-deferred-fanout` sets it around each region in which the thread
     can hold a frame's `:drain-lock` — `re-frame.router/drain-try!` /
     `drain-block!` and `re-frame.frame/call-serialized-with-drain!`, each
     bracketing its whole acquire → run → release — and flushes the batch under
     `fanout-monitor` on the way out. So `set` is exactly \"this thread may
     currently own a drain-lock\", which is why the outermost emit branch below
     needs no frame resolution and no ownership probe: an emit that finds this nil
     PROVES its thread holds no drain-lock and may safely block on the monitor.

     A `ThreadLocal`, deliberately NOT a `^:dynamic` Var. Drain-lock ownership is
     a property of a THREAD, and a Var binding is a property of a logical call
     context that Clojure will happily CONVEY to other threads: JVM
     `re-frame.interop/next-tick` and `set-timeout!` both wrap their callback in
     `bound-fn`. An `ensure-drain-scheduled!` issued mid-drain would therefore
     hand the executor thread the draining thread's scope, and an armed
     `:dispatch-later` would hand it to the timer thread — each then appending its
     own, entirely unrelated, trace events to a batch whose owner has long since
     flushed and walked away. Those events would never be delivered to any
     listener. A `ThreadLocal` is not conveyed, so a scheduled task starts clean
     and opens its own scope. Cleared with `.remove` on the way out, so the flush
     — and any drain a listener body starts from it — sees no scope. The CLJS
     single-threaded analogue is `deferred-drain-fanout-cljs` below (rf2-uoy6m)."
     (ThreadLocal.)))

#?(:cljs
   (def ^:private deferred-drain-fanout-cljs
     "The CLJS post-drain deferral scope (rf2-uoy6m): a volatile holding this
     drain region's `pending` FIFO vector while a drain is active, else nil. The
     single-threaded analogue of the JVM `deferred-drain-fanout` ThreadLocal.

     A plain top-level volatile suffices where the JVM needs a ThreadLocal
     precisely because JS is single-threaded: a drain runs to completion — its
     post-drain flush included — before any other drain or any scheduled
     `next-tick` / `set-timeout!` callback runs, and `call-with-deferred-fanout`
     clears the volatile synchronously on the way out. So a later async task
     always starts with a clean (nil) scope and opens its own, exactly the
     not-conveyed property the JVM ThreadLocal buys against `bound-fn` conveyance."
     (volatile! nil)))

(defn- current-deferred-fanout-queue
  "The deferred-fanout queue of the post-drain deferral scope active on this thread of
  execution, or nil when none is — i.e. nil PROVES the caller holds no frame
  `:drain-lock` (`call-with-deferred-fanout` brackets every acquire → run →
  release region). The JVM reads its `ThreadLocal`; CLJS reads the
  single-threaded volatile."
  []
  #?(:clj  (.get ^ThreadLocal deferred-drain-fanout)
     :cljs @deferred-drain-fanout-cljs))

(defn- set-current-deferred-fanout-queue! [deferred-queue]
  #?(:clj  (.set ^ThreadLocal deferred-drain-fanout deferred-queue)
     :cljs (vreset! deferred-drain-fanout-cljs deferred-queue)))

(defn- clear-current-deferred-fanout-queue! []
  #?(:clj  (.remove ^ThreadLocal deferred-drain-fanout)
     :cljs (vreset! deferred-drain-fanout-cljs nil)))

(defn- drive-fanout!
  "Drive the shared fan-out schedule `ctx` to completion: deliver every queued
  event to every registered listener, FIFO across events and in registration
  order within each event, resuming from the shared `:head` / `:entries` /
  `:lcursor` cursors so a reentrant emit can advance a paused outer delivery to
  every remaining listener BEFORE its own event is delivered.

  Per-listener throws are isolated (rf2-1zxlsm exception isolation). Each event is
  delivered under its OWN `continue?` snapshot (rf2-eaxnai): a listener that
  destroys the outer incarnation flips that event's `continue?` false, which stops
  its remaining fan-out and advances to the next queued event (evaluated under its
  own predicate). `:lcursor` advances BEFORE the callback runs, so a reentrant
  emit resuming this event delivers to the NEXT listener, never re-invoking the
  current one. The listener snapshot per event is taken once (`:entries` nil ->
  snapshot) so a resumed delivery is consistent even if the registry changes
  mid-event."
  [ctx]
  (let [q       (:q ctx)
        head    (:head ctx)
        entries (:entries ctx)
        lcursor (:lcursor ctx)]
    (loop []
      (when (< @head (count @q))
        (when (nil? @entries)
          ;; A queued triple `[event continue? snapshot]` carries the listener
          ;; snapshot captured when a DEFERRED drain-owned event was appended
          ;; (rf2-6t6qk integration), so integrating a nested drain's traces
          ;; into a paused outer schedule reaches exactly the listeners an inline
          ;; delivery would have. A plain pair re-snapshots live, as a reentrant
          ;; listener-body emit always does.
          (let [qelem (nth @q @head)]
            (vreset! entries (if (> (count qelem) 2)
                               (nth qelem 2)
                               (vec (seq @listeners))))
            (vreset! lcursor 0)))
        (let [[event continue?] (nth @q @head)
              es                @entries]
          (if (and (< @lcursor (count es)) (continue?))
            (let [[_ f] (nth es @lcursor)]
              (vreset! lcursor (inc @lcursor))
              (try
                (f event)
                (catch #?(:clj Throwable :cljs :default) _ nil))
              (recur))
            (do (vswap! head inc)
                (vreset! entries nil)
                (recur))))))))

(defn- run-outermost-fanout!
  "Seed ONE fresh fan-out schedule for a whole `batch` of queue entries, bind it
  as `*fanout-ctx*` so reentrant emits from listener bodies advance the SAME
  schedule, and drive it to completion. Always runs under `fanout-monitor` on the
  JVM so concurrent emits serialize (rf2-uw7hg) — either a single clean emit
  (`batch` is one entry) or the whole post-drain flush of a deferred batch
  (rf2-wxy1c); see the `deliver-to-tooling!` outermost branch and
  `drain-deferred-batch!`. The binding + drive are the whole critical section.

  Seeding the WHOLE batch before driving is what keeps a deferred batch FIFO
  (rf2-t6vs3): a listener that authors a later trace while an EARLIER deferred
  entry is being fanned out appends BEHIND the still-pending later entries rather
  than overtaking them. `:entries` starts nil and `drive-fanout!` re-reads it per
  event as it advances `:head`, so a queued triple `[event continue? snapshot]`
  delivers under the listener snapshot captured when it was APPENDED (exactly the
  listeners an inline delivery would have reached), while a plain pair
  `[event continue?]` — a single clean emit, or a reentrant child queued behind
  the batch — re-snapshots live, as an inline listener-body emit always does."
  [batch]
  (let [ctx {:q       (volatile! (vec batch))
             :head    (volatile! 0)
             :entries (volatile! nil)
             :lcursor (volatile! 0)}]
    (binding [*fanout-ctx* ctx]
      (drive-fanout! ctx))))

(def ^:private unread
  "Distinct baseline sentinel for `deferred-continue` — a fresh object, held by
  identity. Deliberately NOT a keyword: CLJS keyword literals are not guaranteed
  `identical?` across evaluation sites, so a keyword sentinel would fail its own
  first-check `identical?` on CLJS and silently break the deferral-continuation
  distinction (rf2-uoy6m)."
  #?(:clj (Object.) :cljs (js-obj)))

(defn- deferred-continue
  "Wrap an appended event's `continue?` snapshot so deferral cannot change
  WHETHER the event is delivered — only when.

  THE PROBLEM DEFERRAL CREATES. `drive-fanout!` consults `continue?` before
  every listener, and rf2-eaxnai gives a false reading ONE meaning: a listener
  body just suppressed this event, so stop fanning it out. That reading is only
  sound while every check is taken in the SAME dynamic context, which is what
  inline delivery guaranteed — the whole fan-out ran inside `deliver!`, inside
  the drain.

  A deferred fan-out runs after the drain has unwound, and these predicates are
  not pure functions of registry state: the dispatch-path fence
  (`re-frame.router/dispatch!`'s `target-live?`) reaches
  `frame/event-owner-live?`, which reads the DYNAMIC `frame/*event-owner*`. Once
  the drain returns that var is unbound, so the fence reads false for every
  cascaded dispatch — no destroy, no suppression, just an unwound stack. Under a
  bare live re-read the driver would take that as suppression and abandon
  listeners 2..N, silently dropping the `:rf.event/dispatched` envelope of every
  cascaded event for every app with more than one listener. Level-testing a
  context-dependent predicate outside its context is the defect; the reading has
  to be made relative to the context the fan-out actually runs in.

  THE DISTINCTION. Detect a TRANSITION, not a level. The first call — taken in
  the flush, immediately before listener 1 — records the BASELINE and always
  returns true (mirroring inline, whose first check passed in the same breath as
  `deliver!`'s own `(continuing?)` gate). Every check thereafter runs in that
  same flush context, so the only thing that can have changed the reading
  between two consecutive checks is the listener body that ran between them:

    - baseline TRUE  -> consult the live predicate. A later false is a genuine
      mid-fan-out suppression and stops the remainder, exactly as rf2-eaxnai
      requires. This is the case for every context-free fence — notably
      `process-event!`'s `#(frame/event-continuation-live? frame-id owner-token)`,
      which reads only the frame registry — so a listener that destroys the
      outer incarnation still fences that incarnation's remaining fan-out.
    - baseline FALSE -> the predicate was ALREADY false when the fan-out began,
      before any listener could act. That is ordinary post-drain state (the
      unwound owner binding above), never evidence of suppression, so it cannot
      abandon the remaining listeners. The event was authorised at emission and
      is delivered in full — just later.

  Deferral therefore changes delivery TIMING only, never the recipient set.
  Cross-platform (rf2-uoy6m): CLJS now defers drain-owned emits too, and the
  same context-dependent-fence unwinding applies there, so this reasoning is not
  JVM-specific. The `unread` sentinel is a dedicated object, NOT a keyword: CLJS
  keyword literals are not guaranteed `identical?` across evaluation sites, so a
  keyword baseline sentinel would make the first-check branch unreachable there —
  every check would level-test the live predicate, and a context-dependent
  post-drain fence (a cascaded `:rf.event/dispatched` envelope's `target-live?`,
  unbound once the drain unwinds) would read false and DROP the event. That was
  the CLJS-only face of rf2-eaxnai's REGRESSION 1."
  [continue?]
  (let [baseline (volatile! unread)]
    (fn []
      (let [b @baseline]
        (cond
          ;; First check, taken in the flush: record the baseline, always pass.
          (identical? b unread)   (do (vreset! baseline (boolean (continue?)))
                                      true)
          ;; Already false before any listener ran — ordinary post-drain
          ;; falsity, not suppression. Never abandon the remaining listeners.
          (false? b)              true
          ;; Was live when the fan-out began: a false reading now is a
          ;; listener-caused suppression (rf2-eaxnai). Honour it.
          :else                   (boolean (continue?)))))))

(defn- drain-deferred-batch!
  "The body of the post-drain flush (see `flush-deferred-fanout!`), factored out
  so the JVM can run it under `fanout-monitor` and CLJS can run it bare. Re-reads
  `deferred-queue` each turn so an append that somehow raced the flush still settles here
  — exactly once — rather than being stranded."
  [deferred-queue]
  (loop []
    (let [batch @deferred-queue]
      (when (seq batch)
        (vreset! deferred-queue [])
        (if-let [ctx *fanout-ctx*]
          ;; A listener-initiated `dispatch-sync` (rf2-6t6qk): the flush runs
          ;; while an OUTER listener fan-out is still paused inside the very
          ;; listener body that opened this drain. INTEGRATE into that active
          ;; schedule rather than seeding fresh outermost fan-outs. Enqueue the
          ;; WHOLE captured batch FIRST, then drive once (rf2-t6vs3): `drive-
          ;; fanout!` advances the paused outer event to its remaining listeners
          ;; FIRST, so every listener still observes the outer event before these
          ;; nested drain-owned ones (rf2-1zxlsm); the whole batch is delivered
          ;; before the enclosing `dispatch-sync` returns (rf2-s522m); and a
          ;; listener that authors a later trace while an EARLIER batch item is
          ;; being driven appends BEHIND the still-pending later items instead of
          ;; overtaking them (rf2-t6vs3 FIFO). Each entry already carries its
          ;; append-time listener snapshot, which `drive-fanout!` honours. No
          ;; monitor re-acquire is needed: the outer drive already holds it (JVM),
          ;; and CLJS has none.
          (do (vswap! (:q ctx) into batch)
              (drive-fanout! ctx))
          ;; Outermost drain (a top-level dispatch-sync / async drain): seed ONE
          ;; fresh outermost schedule for the WHOLE batch (rf2-t6vs3), so the
          ;; entire batch is queued before any callback runs and a listener's
          ;; reentrant emit during an earlier deferred event lands behind the
          ;; later ones rather than overtaking them. Each event is still delivered
          ;; under its own append-time snapshot.
          (run-outermost-fanout! batch))
        (recur)))))

(defn- flush-deferred-fanout!
  "Deliver a drain's appended trace events to their listeners at the post-drain
  boundary. The caller has already released the frame's `:drain-lock`, so this
  is the first point at which arbitrary listener code may run and — on the JVM —
  the first at which blocking on `fanout-monitor` is safe.

  On the JVM the whole batch is flushed under ONE monitor hold, so a drain's
  traces reach every listener contiguously and in emission order rather than
  interleaved with a concurrent drain's. CLJS is single-threaded — no concurrent
  drains, no monitor — so it flushes bare (rf2-uoy6m). When this flush runs while
  an outer listener fan-out is still in progress on this thread (a listener-
  initiated `dispatch-sync`, rf2-6t6qk), the JVM monitor hold is a reentrant re-
  acquire of the one the outer drive already owns."
  [deferred-queue]
  (when (seq @deferred-queue)
    #?(:clj  (locking fanout-monitor (drain-deferred-batch! deferred-queue))
       :cljs (drain-deferred-batch! deferred-queue))))

(defn ^:no-doc call-with-deferred-fanout
  "Run `f` as one post-drain listener-delivery region: trace events emitted inside
  it while the framework owns a frame's `:drain-lock` are appended rather than
  fanned out, and the batch is delivered here, on the way out (under
  `fanout-monitor` on the JVM).

  Wrap the WHOLE acquire → run → release region of every path that takes a
  `:drain-lock` (`re-frame.router/drain-try!` / `drain-block!` and
  `re-frame.frame/call-serialized-with-drain!`). Two properties of that placement
  are load-bearing (see the seam note above): the scope must OPEN before the
  acquire, so no drain-owned emit can escape deferral and block on the monitor
  while holding a lock; and it must CLOSE after the release, so the flush — which
  runs arbitrary listener code and does block on the monitor — owns no lock. The
  flush runs in a `finally`, so a drain that throws still settles its traces
  exactly once.

  `flush-scope` receives the flush thunk and must run it under the ORDINARY
  delivery scope `re-frame.trace/deliver!` establishes around an inline fan-out —
  epoch capture, frame policy and ring retention restored to their defaults, and
  the exact-owner continuation neutralised (rf2-vf2qke / rf2-eaxnai), so a
  listener's nested authored work is not strangled by the scope the DRAIN was
  running under. Inline delivery gets that binding for free because the fan-out
  happens inside `deliver!`; a deferred fan-out happens after `deliver!` has
  returned, so the scope has to be re-established here. It is supplied as an
  argument because those dynamic vars are private to `re-frame.trace`, which this
  sibling deliberately cannot see — the same reason `deliver!` passes `retain?`
  and `continue?` explicitly rather than letting this ns read them.

  Nesting is inherited, not re-established: a reentrant drain (`drain-reentrant!`
  under a cold serialized section, an `:rf.fx/reg-flow` mid-drain) already runs
  inside its owner's scope and appends to the same batch, which the OWNER flushes
  once it has dropped the lock.

  Cross-platform (rf2-uoy6m). CLJS defers exactly as the JVM does — a single-
  threaded post-drain queue with no monitor — so a CLJS listener observes only
  settled state, matching the JVM and the Spec 009 contract. The mechanism differs
  only where the platform forces it: a plain volatile stands in for the JVM
  ThreadLocal, and the flush needs no monitor. Isolation is preserved by the
  CALLER: `re-frame.trace/call-with-deferred-listener-delivery` reaches this fn
  through late-bind on CLJS (never a static reference from the production-reachable
  drain path), so a production bundle that never loads this tooling sibling DCEs
  the whole deferral machinery — and there, with no listener registry, there is
  nothing to defer regardless."
  [f flush-scope]
  (if (or (not interop/debug-enabled?)
          (current-deferred-fanout-queue))
    (f)
    (let [deferred-queue (volatile! [])]
      (try
        (set-current-deferred-fanout-queue! deferred-queue)
        (f)
        (finally
          ;; Clear BEFORE flushing: the flush runs listener bodies, and a
          ;; listener that dispatches must open its own scope rather than
          ;; append to the batch currently draining.
          (clear-current-deferred-fanout-queue!)
          (flush-scope #(flush-deferred-fanout! deferred-queue)))))))

(defn- deliver-to-tooling!
  "Push `event` onto its in-flight frame's run-keyed ring (when the run has a
  `:dispatch-id` and a `:frame`; frameless emits skip the ring per the B3
  ruling), then fan out to every registered listener. Listener throws are
  isolated. No-op in production.

  `retain?` (rf2-vxgfnd.244) gates ONLY the ring push. Under retentionless
  structural delivery (`re-frame.trace/call-with-structural-delivery`) it is
  false: an obsolete incarnation's terminal fact still streams live to every
  listener, but no per-frame ring retains it — the fact carries the bare frame id
  a same-id successor now shares, so a ring push would leak predecessor evidence
  into the successor's ring. The default `true` arity preserves the ordinary emit
  path. Retention is the ONLY thing gated; listener fan-out is unconditional so
  the required terminal fact reaches live consumers exactly once either way.

  `continue?` (rf2-eaxnai) is the caller's exact-owner continuation SNAPSHOT,
  consulted per event by the driver so that if a listener destroys the outer
  incarnation A, A's remaining fan-out is suppressed. It is a standalone snapshot
  rather than a live read of the (neutralised) `*continuation-predicate*` so a
  listener BODY's nested authored work runs under ordinary always-continue
  authority, not A's fence.

  Reentrant fan-out is SCHEDULED, not fired immediately, to preserve BOTH delivery
  laws (see the section note above): a nested emit from inside a listener body
  appends to the bound `*fanout-ctx*` and drives it, advancing the paused outer
  delivery to every remaining listener BEFORE delivering the nested event — so
  every listener observes the outer event before the reentrant one (rf2-1zxlsm)
  AND the nested `emit!` returns only after its event reached every listener
  (rf2-s522m). The ring push still happens inline (emission order)."
  ([event continue?] (deliver-to-tooling! event continue? true))
  ([event continue? retain?]
   (when retain? (push-to-ring! event))
   (if-let [deferred-queue (current-deferred-fanout-queue)]
     ;; DEFER (rf2-wxy1c / rf2-6t6qk / rf2-uoy6m). A drain-owned emit — one raised
     ;; while the framework owns a frame's `:drain-lock` — is appended, never
     ;; fanned out, until the post-drain boundary.
     ;;
     ;; This check takes PRECEDENCE over the reentrant `*fanout-ctx*` fast path
     ;; below (rf2-6t6qk): when an outer listener fan-out is in progress AND that
     ;; listener's `dispatch-sync` has opened a nested drain scope, the nested
     ;; drain's traces must DEFER — driving the outer schedule here would run
     ;; arbitrary listener code inside the framework's critical section while the
     ;; lock is held, the exact negation of the rf2-wxy1c charter. The post-drain
     ;; flush integrates the batch back into that paused outer schedule so
     ;; outer-before-inner ordering and synchronous completion survive.
     ;;
     ;; The deferral scope also PROVES lock ownership (`call-with-deferred-fanout`
     ;; brackets every acquire → run → release region), so a drain-owned emit
     ;; never blocks acquiring `fanout-monitor` — the rf2-jl75r AB-BA edge cannot
     ;; form. Capture the listener snapshot and latch `continue?` so the deferred
     ;; delivery reaches exactly what an inline one would have. CLJS opens the same
     ;; scope now (rf2-uoy6m), so a single-threaded host defers too and its
     ;; listeners never observe partial state.
     (do (vswap! deferred-queue conj [event (deferred-continue continue?)
                                      (vec (seq @listeners))])
         nil)
     (if-let [ctx *fanout-ctx*]
       ;; Reentrant emit from inside a listener body with NO drain scope open:
       ;; append and drive the shared schedule. `drive-fanout!` advances the
       ;; paused outer delivery to every remaining listener, then delivers this
       ;; event, before we return — so the nested `emit!` completes synchronously
       ;; (rf2-s522m) while every listener still sees the outer event first
       ;; (rf2-1zxlsm). Ring retention already ran above, in emission order.
       ;; `*fanout-ctx*` is bound only on THIS thread, so this branch keeps a
       ;; reentrant emit off `fanout-monitor` — no self-deadlock.
       (do (vswap! (:q ctx) conj [event continue?])
           (drive-fanout! ctx)
           nil)
       ;; Outermost emit, no drain scope: by the same deferral bracketing this
       ;; thread provably holds no drain-lock — so it serializes on
       ;; `fanout-monitor` across concurrent emits (rf2-uw7hg) with the monitor
       ;; held for the whole drive, and nothing can wait on it through a
       ;; drain-lock. CLJS is single-threaded and runs every outermost emit inline
       ;; with no monitor.
       #?(:clj  (locking fanout-monitor (run-outermost-fanout! [[event continue?]]))
          :cljs (run-outermost-fanout! [[event continue?]]))))))

(late-bind/set-fn! :trace.tooling/deliver! deliver-to-tooling!)

;; rf2-uoy6m: the drain seam reaches the post-drain deferral wrapper through this
;; hook on CLJS. `re-frame.trace/call-with-deferred-listener-delivery` is called
;; from the always-reachable production drain path, so a STATIC reference to this
;; sibling from there would defeat the `:advanced` DCE that keeps the whole tooling
;; body out of the counter bundle (`check-bundle-isolation.cjs`). Late-bind carries
;; no static edge — identical isolation posture to `:trace.tooling/deliver!` above.
;; The JVM drain calls `call-with-deferred-fanout` directly (no DCE concern there).
(late-bind/set-fn! :trace.tooling/call-with-deferred-fanout call-with-deferred-fanout)

;; `re-frame.core/configure!`'s `:trace-buffer` key routes through this hook so
;; consumer call sites don't have to thread the tooling-ns require
;; into the host's boot path. Keeping just THIS one knob hook (vs the
;; full set the listener / ring surface uses) is deliberate: the
;; per-fn hooks would each pay a keyword-intern cost in every
;; consumer of `re-frame.core`, even when the wrappers' bodies were
;; dead code (the keyword constructor runs at module init).
;; `configure` is a low-traffic op so the extra indirection costs
;; nothing on the hot path.

(late-bind/set-fn! :trace.tooling/configure-trace-buffer! configure-trace-buffer!)

;; Per rf2-r1ciy: `re-frame.frame/fire-on-destroy-event!` installs a one-
;; shot trace listener around the `:on-destroy` dispatch so it can
;; observe the router's `:rf.error/handler-exception` trace and re-emit
;; it under the dedicated `:rf.error/on-destroy-handler-exception`
;; category. The listener-install must run only when the tooling sibling
;; is loaded (otherwise the trace fan-out is dead anyway and there's
;; nothing to observe), so we route through late-bind here — identical
;; pattern to `:trace.tooling/deliver!` above.

(late-bind/set-fn! :trace.tooling/register-listener!   register-listener!)
(late-bind/set-fn! :trace.tooling/unregister-listener! unregister-listener!)

;; Per rf2-g1b2m / rf2-8uwce — published hooks for B4 dedup-by-shape
;; (consulted by the registrar at emit time) and frame-destroy ring
;; cleanup (consulted by `destroy-frame!` per Spec 002 §Destroy).

(late-bind/set-fn! :trace.tooling/dedup-allow?        dedup-allow?)
(late-bind/set-fn! :trace.tooling/clear-dedup-table!  clear-dedup-table!)
(late-bind/set-fn! :trace.tooling/release-frame-ring! release-frame-ring!)
(late-bind/set-fn! :trace.tooling/apply-frame-events-retained-policy!
                   apply-frame-events-retained-policy!)

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-qwm0a: `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives
;; ONLY in this file's source body — no other namespace, no docstring,
;; no test fixture references it — so its presence in the production
;; counter bundle proves that the tooling sibling's body got pulled in
;; (most likely via a stray `:require` from a core/* ns). The sentinel
;; survives `:advanced` because string literals are not renamed; it
;; sits outside any `interop/debug-enabled?` gate so DCE cannot drop
;; the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.trace.tooling/sentinel:rf2-qwm0a-2026-05-16:do-not-rename")
