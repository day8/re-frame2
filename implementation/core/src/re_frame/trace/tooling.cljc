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

  Routing chain for the destination frame-id:
    1. `:frame` under `:tags` (the router stamps it on most in-run
       emits).
    2. Top-level `:frame` on the envelope.
    3. The late-bound `:frame/current-frame-id` hook (published by
       `re-frame.frame` at ns-load) — covers sub recompute / view
       render emits where the call site doesn't stamp `:frame` but the
       in-flight run's `frame/*current-frame*` is bound.

  No-op in production (production never reaches the emit site)."
  [ev]
  (when interop/debug-enabled?
    (let [dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
          frame-id    (or (get-in ev [:tags :frame])
                          (:frame ev)
                          (when-let [current-frame
                                     (late-bind/get-fn-cached :frame/current-frame-id)]
                            (current-frame)))]
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
;; `*fanout-ctx*` is thread-local (a dynamic binding), so it only schedules
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
;; ---- the frame-drain-emit deadlock seam (rf2-jl75r) -----------------------
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
;; The fix keeps the whole synchronous, ordered, serialized fan-out for clean
;; emits but takes the monitor OUT of the cycle: a FRAME-DRAIN emit
;; (`frame-drain-emit?` — one that ROUTES TO A FRAME the way `push-to-ring!` does,
;; or a retentionless structural terminal fact) NEVER acquires the monitor. It
;; drives its fan-out inline (exactly as CLJS always does), so the "drain-lock
;; holder waits on the monitor" edge is removed and no cycle can form. A thread
;; that holds a `:drain-lock` only ever emits frame-routed emits, so it never
;; blocks on the monitor; a thread holding the monitor emitted a frameless
;; external `emit!` and holds no `:drain-lock`, so nothing waits on it through one.
;; Frame ROUTABILITY (not merely a `:rf.trace/dispatch-id`) is the load-bearing
;; discriminator: the epoch cascade trailers (`:rf.epoch/snapshotted` /
;; `:rf.epoch/outcome`) fire AFTER the buffer is harvested, `outside any cascade`
;; (nil dispatch-id), yet still on the drainer thread holding the `:drain-lock` —
;; a dispatch-id-only test misclassifies them and re-opens the deadlock, so the
;; predicate keys on the frame the emit routes to. Same-thread reentrant delivery
;; of the drain's own nested emits is unaffected — those take the `*fanout-ctx*`
;; append+drive branch, never the monitor. The remaining relaxation is bounded and
;; JVM-only: a frame-scoped clean emit, and two genuinely concurrent frame drains
;; (never a thing on the single-threaded CLJS target), may drive inline and
;; overlap a listener; frameless concurrent clean emits — the case rf2-uw7hg's
;; suite exercises — still serialize on the monitor, unchanged.
;;
;; Same-thread reentrancy does NOT re-acquire the monitor: a nested emit sees
;; `*fanout-ctx*` already bound and takes the append+drive branch below, so it
;; never reaches the lock — no self-deadlock, and the reentrant schedule advances
;; the SAME critical section. `locking` is a reentrant monitor regardless, so even
;; a hypothetical re-entry could not deadlock on itself. CLJS is single-threaded
;; (no concurrent emits): the `:cljs` arm runs the drive inline with no monitor,
;; preserving production elision.

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
     "Process-global JVM monitor serializing every EXTERNAL/clean OUTERMOST
     trace-listener fan-out across concurrent emits (rf2-uw7hg), so no registered
     listener callback is ever invoked on two threads at once and records reach
     each listener in one defined order. Held for the whole synchronous drive of
     such an emit. A frame-drain emit (`frame-drain-emit?`) NEVER acquires it —
     see the deadlock note below — and reentrant same-thread emits never acquire
     it (they advance the bound `*fanout-ctx*` schedule). CLJS is single-threaded
     and has no counterpart."
     (Object.)))

#?(:clj
   (defn- frame-drain-emit?
     "True when this outermost emit originates from INSIDE a frame's runtime
     activity — i.e. the emitting thread may hold that frame's `:drain-lock`. Such
     an emit MUST NOT block acquiring `fanout-monitor`: a listener already holding
     the monitor is allowed to `dispatch-sync` into that same frame and would spin
     on its `:drain-lock` — a hard AB-BA cycle (rf2-jl75r, `deliver-to-tooling!`).
     It drives its fan-out INLINE instead, so no process-global lock is ever held
     by, or awaited by, a frame-drain emit.

     The discriminator is FRAME ROUTABILITY, computed exactly as `push-to-ring!`
     routes an emit to a per-frame ring: an emit that resolves to a frame — via an
     explicit `[:tags :frame]`, a top-level `:frame`, or the in-flight run's
     `frame/*current-frame*` (the late-bound `:frame/current-frame-id` hook) —
     originates from within that frame's drain / settle / lifecycle activity. This
     is the ONLY signal that covers EVERY drain emit, including ones the drain's
     `:rf.trace/dispatch-id` scope does not reach: the epoch cascade trailers
     (`:rf.epoch/snapshotted` / `:rf.epoch/outcome`) fire AFTER the buffer is
     harvested, `outside any cascade` (nil dispatch-id), yet still on the drainer
     thread holding the `:drain-lock` — a dispatch-id-only test would misclassify
     them and re-open the deadlock. A retentionless structural terminal fact
     (`retain?` false: `:rf.frame/destroyed` / `:rf.frame/drain-interrupted`,
     emitted while `destroy-frame!` holds the frame's drain serialization) is
     included for the same reason even if it were ever frameless.

     An external/clean emit — a tool or REPL `emit!` with NO frame in scope (no
     `:frame` tag, no ambient `*current-frame*`), the shape rf2-uw7hg's suite
     exercises — resolves no frame and returns false, so concurrent clean emits
     still serialize on the monitor (no listener callback overlaps itself). The
     residual relaxation is bounded and JVM-only: a frame-scoped clean emit, and
     two genuinely concurrent frame drains (never a thing on the single-threaded
     CLJS target), may drive inline and overlap a listener.

     JVM-only: the `:cljs` outermost arm has no monitor, so it needs no predicate."
     [event retain?]
     (or (not retain?)
         (some? (get-in event [:tags :frame]))
         (some? (:frame event))
         (some? (get-in event [:tags :rf.trace/dispatch-id]))
         (when-let [current-frame (late-bind/get-fn-cached :frame/current-frame-id)]
           (some? (current-frame))))))

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
          (vreset! entries (vec (seq @listeners)))
          (vreset! lcursor 0))
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
  "Seed a fresh fan-out schedule for `event`, bind it as `*fanout-ctx*` so
  reentrant emits from listener bodies advance the SAME schedule, and drive it to
  completion. On the JVM a CLEAN outermost emit runs this under `fanout-monitor`
  so concurrent emits serialize (rf2-uw7hg); a frame-drain emit runs it inline,
  off the monitor, to break the drain-lock↔monitor cycle (rf2-jl75r — see the
  `deliver-to-tooling!` outermost branch). Either way the binding + drive are the
  whole critical section."
  [event continue?]
  (let [ctx {:q       (volatile! [[event continue?]])
             :head    (volatile! 0)
             :entries (volatile! nil)
             :lcursor (volatile! 0)}]
    (binding [*fanout-ctx* ctx]
      (drive-fanout! ctx))))

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
   (if-let [ctx *fanout-ctx*]
     ;; Reentrant emit from inside a listener body: append and drive the shared
     ;; schedule. `drive-fanout!` advances the paused outer delivery to every
     ;; remaining listener, then delivers this event, before we return — so the
     ;; nested `emit!` completes synchronously (rf2-s522m) while every listener
     ;; still sees the outer event first (rf2-1zxlsm). Ring retention already ran
     ;; above, in emission order. `*fanout-ctx*` is bound only on THIS thread, so
     ;; this branch is what keeps a reentrant emit off `fanout-monitor` — no
     ;; self-deadlock.
     (do (vswap! (:q ctx) conj [event continue?])
         (drive-fanout! ctx)
         nil)
     ;; Outermost fan-out.
     ;;
     ;; A FRAME-DRAIN emit (`frame-drain-emit?`: one that ROUTES TO A FRAME the way
     ;; `push-to-ring!` does, or a retentionless structural terminal fact) runs on
     ;; a thread that may hold a frame's `:drain-lock`. It MUST NOT block acquiring
     ;; `fanout-monitor`: a listener already holding the monitor is free to
     ;; `dispatch-sync` into that same frame and spin on its `:drain-lock`, closing
     ;; a hard AB-BA cycle (rf2-jl75r). Such an emit drives its fan-out inline —
     ;; exactly as the single-threaded CLJS host always does — so no process-global
     ;; lock is ever held by, or awaited by, a frame-drain emit. This is the "no
     ;; fan-out lock across arbitrary listener code that may dispatch-sync into the
     ;; draining frame" seam.
     ;;
     ;; An EXTERNAL/clean emit (a tool or REPL `emit!` with NO frame in scope) still
     ;; serializes on `fanout-monitor` across concurrent emits (rf2-uw7hg) so no
     ;; listener callback overlaps itself and records reach each listener in
     ;; monitor-acquisition order — the monitor is held for that whole drive. Such
     ;; a thread holds no `:drain-lock`, so nothing waits on it through one; the
     ;; drain-lock↔monitor cycle cannot form. CLJS is single-threaded and runs
     ;; every outermost emit inline with no monitor.
     #?(:clj  (if (frame-drain-emit? event retain?)
                (run-outermost-fanout! event continue?)
                (locking fanout-monitor (run-outermost-fanout! event continue?)))
        :cljs (run-outermost-fanout! event continue?)))))

(late-bind/set-fn! :trace.tooling/deliver! deliver-to-tooling!)

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
