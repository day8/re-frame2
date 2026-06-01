(ns re-frame.trace.tooling
  "Trace tooling sibling of `re-frame.trace` — carries the public
  dev-tooling surface (`register-listener!` / `unregister-listener!` /
  `clear-listeners!` / `trace-buffer` / `clear-trace-buffer!` /
  `configure-trace-buffer!` / `configure`) and the per-frame
  cascade-keyed trace rings + listener state.

  ## Per-frame trace rings (rf2-g1b2m / rf2-8uwce)

  Each frame owns its own cascade-keyed ring. Storage shape (per frame):

      {:cascades-retained N             ;; the per-frame ring depth
       :cascade-order [<dispatch-id> ...]  ;; oldest-first cascade slots
       :cascades {<dispatch-id> [<event> ...]}}

  - The unit of retention is the cascade (one `:rf.trace/dispatch-id` =
    one slot). When cascade #N+1 arrives, the OLDEST cascade slot (and
    every trace event emitted under its `:dispatch-id`) is evicted as a
    unit.
  - `:cascades-retained` defaults to 50; per-frame override via
    `:rf.trace/cascades-retained` on `reg-frame`.
  - `:cascades-retained 0` disables retention (no slots allocated; the
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
            [re-frame.late-bind :as late-bind]))

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

;; ---- per-frame cascade-keyed trace rings (dev-only) ---------------------
;;
;; Storage shape (per Spec 009 §Per-frame trace rings):
;;
;;   trace-rings  = {<frame-id> {:cascades-retained N
;;                               :cascade-order [<dispatch-id> ...]
;;                               :cascades       {<dispatch-id> [<event>...]}}}
;;
;; - `:cascade-order` is the oldest-first vector of `:dispatch-id`s
;;   currently held in this frame's ring; eviction pops the head.
;; - `:cascades` is a flat map from `:dispatch-id` → trace events
;;   emitted under that dispatch, in arrival order.
;; - The slot count is bounded by `:cascades-retained` (default 50).
;; - Frames lazily allocate slots on first emit. Apps with one frame
;;   pay one slot of ring overhead.

(def ^:private default-cascades-retained
  "Per Spec 009 §Per-frame trace rings — the per-frame retention default
  when neither `:rf.trace/cascades-retained` on `reg-frame` nor
  `(configure :trace-buffer ...)` supplies one. 50 cascades — enough to
  cover a short interactive session without per-frame memory pressure."
  50)

(defonce ^:private trace-rings (atom {}))

;; Process-default cascades-retained — applies to `:rf/default` and any
;; frame that did not set per-frame `:rf.trace/cascades-retained` metadata.
;; Per Spec 009 §Retention contract — the single knob.
(defonce ^:private process-cascades-retained (atom default-cascades-retained))

(defn- empty-ring [retained]
  {:cascades-retained retained
   :cascade-order     []
   :cascades          {}})

(defn- effective-retained
  "Return the cascades-retained value for `frame-id`. Honours an
  existing per-frame override (`set-frame-cascades-retained!`'s prior
  write) before falling back to the process default."
  [rings frame-id]
  (or (get-in rings [frame-id :cascades-retained])
      @process-cascades-retained))

(defn set-frame-cascades-retained!
  "Apply a per-frame `:rf.trace/cascades-retained` override (called
  from `frame.cljc`'s `reg-frame` when the config carries the key).
  Trims existing slots if the new value is lower than current
  occupancy; raising keeps existing cascades and grows the slot cap.

  Per Spec 009 §Lowering cascades-retained on a populated ring."
  [frame-id retained]
  (when (and interop/debug-enabled? (number? retained) (not (neg? retained)))
    (swap! trace-rings
           (fn [rings]
             (let [existing (get rings frame-id)
                   order    (or (:cascade-order existing) [])
                   cascades (or (:cascades existing) {})]
               (cond
                 ;; retained = 0: drop everything; slot persists with depth 0
                 ;; so reads return [] cleanly.
                 (zero? retained)
                 (assoc rings frame-id
                        (empty-ring 0))

                 ;; Trim if existing order exceeds the new cap.
                 (> (count order) retained)
                 (let [drop-n     (- (count order) retained)
                       evicted    (subvec order 0 drop-n)
                       kept-order (subvec order drop-n)]
                   (assoc rings frame-id
                          {:cascades-retained retained
                           :cascade-order     kept-order
                           :cascades          (apply dissoc cascades evicted)}))

                 :else
                 (assoc-in rings [frame-id :cascades-retained] retained))))))
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
  "Append `ev` to its frame's cascade-keyed ring. Frameless emits (no
  `:rf.trace/dispatch-id` in scope) bypass the ring entirely per the
  B3 ruling — they stream live to listeners only and are never retained.

  Routing chain for the destination frame-id:
    1. `:frame` under `:tags` (the router stamps it on most in-cascade
       emits).
    2. Top-level `:frame` on the envelope.
    3. The late-bound `:frame/current-frame-id` hook (published by
       `re-frame.frame` at ns-load) — covers sub recompute / view
       render emits where the call site doesn't stamp `:frame` but the
       in-flight cascade's `frame/*current-frame*` is bound.

  No-op in production (production never reaches the emit site)."
  [ev]
  (when interop/debug-enabled?
    (let [dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
          frame-id    (or (get-in ev [:tags :frame])
                          (:frame ev)
                          (when-let [current-frame
                                     (late-bind/get-fn-cached :frame/current-frame-id)]
                            (current-frame)))]
      ;; Frameless emits (no in-flight cascade) skip the ring. The B3
      ;; ruling is that frameless events stream live to listeners only
      ;; and are never retained. A `:dispatch-id` without a resolvable
      ;; frame-id (extremely rare — would require a manual emit from
      ;; outside any framework boundary) also skips the ring; there is
      ;; nowhere to put it.
      (when (and dispatch-id frame-id)
        (swap! trace-rings
               (fn [rings]
                 (let [retained (effective-retained rings frame-id)]
                   (if (zero? retained)
                     ;; Disabled-but-live: live stream still fires
                     ;; (push-to-ring! is one step in the pipeline);
                     ;; just don't allocate.
                     (assoc rings frame-id (empty-ring 0))
                     (let [existing (get rings frame-id (empty-ring retained))
                           order    (:cascade-order existing)
                           cascades (:cascades existing)
                           known?   (contains? cascades dispatch-id)
                           order'   (if known? order (conj order dispatch-id))
                           cascades' (update cascades dispatch-id
                                             (fnil conj []) ev)
                           ;; Evict oldest cascade slots while over cap.
                           [order'' cascades'']
                           (loop [o order' c cascades']
                             (if (> (count o) retained)
                               (let [oldest (first o)]
                                 (recur (subvec o 1) (dissoc c oldest)))
                               [o c]))]
                       (assoc rings frame-id
                              {:cascades-retained retained
                               :cascade-order     order''
                               :cascades          cascades''}))))))))))

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
;;     `:rf.trace.tooling/cleared`. A second clear-of-a-cleared id
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
;; Per Spec 009 §`trace-buffer` API — per-frame, cascade bundles by
;; default. `:flat true` opt returns raw trace events.

(defn- effects-bucket?
  [{:keys [op-type operation]}]
  (and (= op-type :rf.fx) (not= operation :rf.fx/do-fx)))

(defn- subs-bucket?
  [{:keys [op-type]}]
  (= op-type :rf.sub))

(defn- renders-bucket?
  [{:keys [op-type operation]}]
  (and (= op-type :rf.view) (= operation :rf.view/render)))

(defn- handler-marker?
  [{:keys [op-type operation]}]
  (and (= op-type :rf.event)
       (or (= operation :rf.event/run-start)
           (= operation :rf.event/run-end))))

(defn- dispatched-marker?
  [{:keys [op-type operation]}]
  (and (= op-type :rf.event) (= operation :rf.event/dispatched)))

(defn- fx-marker?
  [{:keys [operation]}]
  (= operation :rf.fx/do-fx))

(defn- cascade-bundle
  "Project the events for one cascade into a Spec 009 cascade-bundle
  (the `group-cascades` shape, per-cascade)."
  [frame-id dispatch-id events]
  (let [base {:dispatch-id  dispatch-id
              :frame        frame-id
              :trace-events events
              :event        nil
              :dispatched   nil
              :handler      nil
              :fx           nil
              :effects      []
              :subs         []
              :renders      []
              :other        []
              :parent-dispatch-id nil}]
    (reduce (fn [acc ev]
              (cond
                (dispatched-marker? ev)
                (assoc acc
                       :event              (get-in ev [:tags :rf.event/v])
                       :dispatched         ev
                       :parent-dispatch-id (get-in ev [:tags :rf.trace/parent-dispatch-id]))

                (handler-marker? ev) (assoc acc :handler ev)
                (fx-marker? ev)      (assoc acc :fx ev)
                (effects-bucket? ev) (update acc :effects conj ev)
                (subs-bucket? ev)    (update acc :subs conj ev)
                (renders-bucket? ev) (update acc :renders conj ev)
                :else                (update acc :other conj ev)))
            base
            events)))

(defn- match-cascade?
  "Filter a cascade bundle against the cascade-level filter keys."
  [bundle {:keys [event-id origin dispatch-id since-ms between pred]}]
  (let [[t0 t1] (when (and (sequential? between)
                           (= 2 (count between)))
                  between)
        events  (:trace-events bundle)
        ;; A cascade's "time" is its earliest event's time (where present)
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

(defn- frame-ring-cascades
  "Materialise `frame-id`'s ring as an oldest-first vector of cascade
  bundles. Returns an empty vector when the frame has no recorded
  cascades (including the destroyed-frame / unknown-frame case per
  Spec 009 §Reads against a destroyed / missing frame)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:cascade-order ring)
          cascades (:cascades ring)]
      (mapv (fn [dispatch-id]
              (cascade-bundle frame-id dispatch-id (get cascades dispatch-id)))
            order))
    []))

(defn- frame-ring-flat-events
  "Materialise `frame-id`'s ring as an oldest-first vector of raw trace
  events (the `:flat true` opt-in shape)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:cascade-order ring)
          cascades (:cascades ring)]
      (into [] (mapcat (fn [did] (get cascades did))) order))
    []))

(defn trace-buffer
  "Per-frame trace ring reader. Returns the named frame's retained
  cascades, oldest-first.

  Two arities:
    (trace-buffer frame-id)        — cascade bundles (default)
    (trace-buffer frame-id opts)   — opts filter map

  Cascade-bundle shape:
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
                      of cascade bundles. The escape hatch for callers
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
    :event-id       — cascade :event first-element OR (:flat) :tags :rf.trace/event-id
    :origin         — cascade root :rf.event/origin OR (:flat) per-event
    :dispatch-id    — cascade :dispatch-id OR (:flat) per-event
    :since-ms       — cascade earliest-time OR (:flat) per-event :time
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
         (filterv #(match-cascade? % opts) (frame-ring-cascades rings frame-id)))))))

(defn clear-trace-buffer!
  "Empty the named frame's cascade-keyed ring. Tooling uses this between
  sessions. No-op for an unknown frame, no-op in production. Per Spec
  009 §`trace-buffer` API."
  [frame-id]
  (when interop/debug-enabled?
    (swap! trace-rings
           (fn [rings]
             (if (contains? rings frame-id)
               (let [retained (effective-retained rings frame-id)]
                 (assoc rings frame-id (empty-ring retained)))
               rings))))
  nil)

(defn clear-trace-rings!
  "Drop EVERY frame's ring + reset the process-default
  cascades-retained back to its initial value + clear the B4 dedup
  table. Test-fixture / `clear-all!`-shaped helper — symmetric with
  the listener registry's `clear-listeners!`. Production no-op."
  []
  (when interop/debug-enabled?
    (reset! trace-rings {})
    (reset! process-cascades-retained default-cascades-retained)
    (clear-dedup-table!))
  nil)

(defn configure-trace-buffer!
  "Apply a process-default ring depth. Per Spec 009 §Retention contract:

      (configure :trace-buffer {:cascades-retained N})

  Sets the per-process default that applies to `:rf/default` and to
  every frame that did not set its own `:rf.trace/cascades-retained`
  metadata. Frames with explicit per-frame metadata are NOT overridden.

  When `:cascades-retained 0`, the ring is disabled but the surface
  remains live. Per Spec 009 §Cascades-retained-zero semantics.

  No-op in production. Returns nil."
  [{:keys [cascades-retained]}]
  (when (and interop/debug-enabled?
             (number? cascades-retained)
             (not (neg? cascades-retained)))
    (reset! process-cascades-retained cascades-retained)
    ;; Apply the new default to any frame whose ring did not set its own
    ;; per-frame retention — those frames inherit the default.
    (swap! trace-rings
           (fn [rings]
             (reduce-kv
              (fn [acc frame-id ring]
                (let [own-set? (some? (get-in rings [frame-id :cascades-retained]))]
                  (if own-set?
                    acc
                    (let [order   (:cascade-order ring)
                          cascades (:cascades ring)]
                      (cond
                        (zero? cascades-retained)
                        (assoc acc frame-id (empty-ring 0))

                        (> (count order) cascades-retained)
                        (let [drop-n     (- (count order) cascades-retained)
                              kept-order (subvec order drop-n)
                              evicted    (subvec order 0 drop-n)]
                          (assoc acc frame-id
                                 {:cascades-retained cascades-retained
                                  :cascade-order     kept-order
                                  :cascades          (apply dissoc cascades evicted)}))

                        :else
                        (assoc-in acc [frame-id :cascades-retained] cascades-retained))))))
              rings
              rings))))
  nil)

(defn configure
  "Generic config dispatch. Recognises :trace-buffer; future config knobs
  add cases here. Per Spec 009 §Per-frame trace rings
  (`(rf/configure! :trace-buffer {:cascades-retained N})`)."
  [k opts]
  (case k
    :trace-buffer (configure-trace-buffer! opts)
    nil))

;; ---- delivery hook ------------------------------------------------------
;;
;; `re-frame.trace/deliver!` looks this up via late-bind and calls it
;; once per emitted event (after the epoch-capture fan-out). Centralising
;; the ring-push + listener fan-out in one hook keeps trace.cljc free
;; of any reference to the rings state or listener atom — so a
;; production build that never `:requires` this ns DCEs the whole body.

(defn- deliver-to-tooling!
  "Push `event` onto its in-flight frame's cascade-keyed ring (when the
  cascade has a `:dispatch-id` and a `:frame`; frameless emits skip the
  ring per the B3 ruling), then fan out to every registered listener.
  Listener throws are isolated. No-op in production."
  [event]
  (push-to-ring! event)
  (doseq [[_ f] @listeners]
    (try
      (f event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(late-bind/set-fn! :trace.tooling/deliver! deliver-to-tooling!)

;; `re-frame.core/configure! :trace-buffer` routes through this hook so
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
(late-bind/set-fn! :trace.tooling/set-frame-cascades-retained!
                   set-frame-cascades-retained!)

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
