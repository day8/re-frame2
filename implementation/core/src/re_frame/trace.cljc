(ns re-frame.trace
  "Per-process trace event stream. Per Spec 009.

  Every dispatch, drain step, render, fx invocation, error, and machine
  transition emits a structured trace event. Listeners (10x,
  re-frame-pair, AI tools) subscribe and consume the stream. The shape
  is event-at-a-time (not span-shaped); cascade correlation rides on
  `:dispatch-id`. Production builds elide trace emission entirely via
  `re-frame.interop/debug-enabled?` — see `emit!` below. See Spec 009
  §Core fields, §Dispatch correlation, and §Handler-scope."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind])
  #?(:cljs (:require-macros [re-frame.trace])))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))    ;; id → fn (or nil for cleared)

(defn register-trace-cb!
  "Register a listener that receives every trace event. The id can be any
  comparable value; passing the same id twice replaces. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn remove-trace-cb!
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-trace-cbs!
  []
  (reset! listeners {})
  nil)

;; ---- event id counter (cheap, monotonic per process) ----------------------

(defonce ^:private event-counter (atom 0))

(defn- next-event-id []
  (swap! event-counter inc))

;; ---- handler-scope: the five-slot in-scope reading -----------------------
;;
;; Per Spec 009 §Handler-scope. Every handler-execution boundary (router,
;; subs, fx, cofx, views) publishes a HandlerScope record on
;; `*handler-scope*` so `emit!` / `emit-error!` can hoist the relevant
;; slots onto each emitted event. See Spec 009 for the slot semantics,
;; composition rule (innermost wins for meta-derived slots; call-site /
;; dispatch-id inherit), and elision contract.

(defrecord HandlerScope [trigger-handler call-site dispatch-id sensitive? no-emit?])

(def ^:dynamic *handler-scope*
  "HandlerScope record currently in scope for trace emission, or nil at
  top of stack. Bound by `with-handler-scope` (and its variants) at
  every handler-execution boundary. See Spec 009 §Handler-scope."
  nil)

(defn trigger-handler-from-meta
  "Build a `:rf.trace/trigger-handler` value `{:kind :id :source-coord
  {:ns :file :line :column}}` from a registrar slot's `meta` map.
  Returns nil when no source-coord keys are present (programmatic
  registration). Per Spec 009 §Handler-scope."
  [kind id meta]
  (let [coord (cond-> nil
                (:ns     meta) (assoc :ns     (:ns     meta))
                (:file   meta) (assoc :file   (:file   meta))
                (:line   meta) (assoc :line   (:line   meta))
                (:column meta) (assoc :column (:column meta)))]
    (when coord
      {:kind         kind
       :id           id
       :source-coord coord})))

(defn sensitive?-from-meta
  "True iff `meta` carries `:sensitive? true`. Used at queue-time
  `:event/dispatched` emit, before the handler-scope binding exists.
  Per Spec 009 §Privacy / sensitive data in traces."
  [meta]
  (true? (:sensitive? meta)))

(defn no-emit?-from-meta
  "True iff `meta` carries `:rf.trace/no-emit? true`. Used at queue-time
  `:event/dispatched` emit, before the handler-scope binding exists.
  Per Spec 009 §Trace-emission opt-out."
  [meta]
  (true? (:rf.trace/no-emit? meta)))

(defn sensitive?
  "Predicate: is `trace-event`'s top-level `:sensitive?` field truthy?
  The framework-published predicate every trace consumer gates on.
  Per Spec 009 §Privacy / sensitive data in traces."
  [trace-event]
  (and (map? trace-event)
       (true? (:sensitive? trace-event))))

(defn handler-scope-from-meta
  "Build a HandlerScope from a registrar slot's `meta` for a handler
  about to execute. Computes the three meta-derived slots
  (`:trigger-handler` / `:sensitive?` / `:no-emit?`); leaves
  `:call-site` and `:dispatch-id` nil — `with-handler-scope` fills
  them from the parent scope on bind. Per Spec 009 §Handler-scope."
  [kind id meta]
  (->HandlerScope (trigger-handler-from-meta kind id meta)
                  nil
                  nil
                  (true? (:sensitive? meta))
                  (true? (:rf.trace/no-emit? meta))))

(defn inherit-scope
  "Merge `parent`'s `:call-site` and `:dispatch-id` into `new-scope`
  where `new-scope`'s value is nil. Meta-derived slots are preserved
  as-is (innermost-wins). Per Spec 009 §Handler-scope §Composition."
  [new-scope parent]
  (if (nil? parent)
    new-scope
    (cond-> new-scope
      (nil? (:call-site new-scope))   (assoc :call-site   (:call-site parent))
      (nil? (:dispatch-id new-scope)) (assoc :dispatch-id (:dispatch-id parent)))))

#?(:clj
   (defmacro with-handler-scope
     "Bind `*handler-scope*` to `scope` (a HandlerScope record) for the
     duration of `body`, inheriting `:call-site` and `:dispatch-id`
     from the parent scope where `scope`'s slots are nil. Use at every
     handler-execution boundary (router, fx, cofx, subs, views):

         (trace/with-handler-scope (trace/handler-scope-from-meta :event id meta)
           (run-chain ...))

     Per Spec 009 §Handler-scope."
     [scope & body]
     `(binding [*handler-scope* (inherit-scope ~scope *handler-scope*)]
        ~@body)))

#?(:clj
   (defmacro with-call-site
     "Bind `*handler-scope*` with `:call-site` set to `cs`, inheriting
     the rest. For surface macros (`subscribe`, `inject-cofx`) and
     synchronous error emits in `dispatch!` / `dispatch-sync!`. Per
     Spec 009 §Handler-scope and §`:rf.trace/call-site`."
     [cs & body]
     `(let [cs# ~cs
            parent# *handler-scope*]
        (binding [*handler-scope* (if parent#
                                    (assoc parent# :call-site cs#)
                                    (->HandlerScope nil cs# nil false false))]
          ~@body))))

#?(:clj
   (defmacro with-dispatch-id+call-site
     "Bind `*handler-scope*` with `:dispatch-id` and `:call-site` set,
     inheriting the rest. Used by `router/process-event!` to publish
     the cascade's `:dispatch-id` and the envelope's `:call-site` once
     on entry to the drain. Per Spec 009 §Handler-scope and §Dispatch
     correlation."
     [dispatch-id call-site & body]
     `(let [did# ~dispatch-id
            cs# ~call-site
            parent# *handler-scope*]
        (binding [*handler-scope* (if parent#
                                    (assoc parent#
                                           :dispatch-id did#
                                           :call-site   cs#)
                                    (->HandlerScope nil cs# did# false false))]
          ~@body))))

;; ---- retain-N trace ring buffer (dev-only) -------------------------------
;;
;; Per Spec 009 §Retain-N trace ring buffer. Holds the most recent N completed
;; trace events; queryable via (trace-buffer). All ring-buffer machinery is
;; gated on interop/debug-enabled? (the same compile-time flag the rest of the
;; trace surface rides) so production builds drop the buffer entirely — no
;; allocation, no append, no storage.

(def ^:private default-buffer-depth
  "Per Spec 009 §Retain-N trace ring buffer: default 200 events."
  200)

(defonce ^:private buffer-depth (atom default-buffer-depth))

(defonce ^:private trace-buffer-state
  ;; The buffer is a plain vector held under an atom. Append is conj+slice;
  ;; the slot count caps memory. depth=0 disables the buffer entirely (the
  ;; delivery path still works — see configure-trace-buffer!).
  (atom []))

(defn- push-to-buffer!
  "Append ev to the ring buffer, evicting the oldest entry when the slot
  count is exceeded. No-op when the configured depth is 0."
  [ev]
  (when interop/debug-enabled?
    (let [depth @buffer-depth]
      (when (pos? depth)
        (swap! trace-buffer-state
               (fn [v]
                 (let [v' (conj v ev)
                       n  (count v')]
                   (if (> n depth)
                     (subvec v' (- n depth))
                     v'))))))))

(defn trace-buffer
  "Return the trace ring buffer's current contents, oldest first.

  With opts, filters the result. Recognised keys (all compose AND-wise;
  an absent key means \"no constraint on that axis\"):

    :operation     — keep only events with this :operation value.
    :op-type       — keep only events with this :op-type value.
    :since         — keep only events whose :id is strictly greater than
                     this. Useful for cursor-based polling.
    :frame         — keep only events whose `:tags :frame` (or top-level
                     :frame fallback) matches.
    :severity      — keep only events whose :op-type matches one of
                     `:error` / `:warning` / `:info`. Synonym for
                     `:op-type` restricted to the three severity tiers.
    :event-id      — keep only events whose `:tags :event-id` matches.
                     The event-id is the first element of the dispatched
                     event vector (e.g. `:user/login`).
    :handler-id    — keep only events whose `:tags :handler-id` matches.
                     Carried on `:rf.error/handler-exception` and other
                     handler-scoped emits.
    :source        — keep only events whose :source (top-level, hoisted
                     from `:tags :source` by `emit!`) matches. Source
                     identifies the trigger origin — one of `:ui` /
                     `:timer` / `:http` / `:repl` / `:machine` /
                     `:ssr-hydration`. Matched against the top-level slot.
    :origin        — keep only events whose `:tags :origin` matches.
                     Origin tags the actor that issued the dispatch
                     (`:app` / `:pair` / `:story` / `:test` / ...) per
                     Spec 002 §Dispatch origin tagging.
    :dispatch-id   — keep only events whose `:tags :dispatch-id` matches.
                     Cascade-wide post rf2-g6ih4 — every emit inside a
                     drain carries the in-flight cascade's dispatch-id.
    :since-ms      — keep only events whose :time is strictly greater
                     than this numeric host-clock timestamp.
    :between       — `[t0 t1]` two-element vector — keep only events
                     whose :time falls in [t0, t1] inclusive.
    :sensitive?    — boolean. Match the top-level `:sensitive?` field
                     (per Spec 009 §Privacy filter-vocab row, rf2-isdwf).
                     Pass `false` to exclude sensitive events; pass
                     `true` to select only sensitive events. Absent ⇒
                     no constraint.
    :pred          — `(fn [ev] -> truthy)` arbitrary predicate. Receives
                     the full event map. Returning truthy keeps the event.

  Filters compose: every supplied key must match. Returns an empty vector
  in production (the buffer never receives events when interop/debug-enabled?
  is false at compile time).

  Per Spec 009 §Retain-N trace ring buffer."
  ([] (trace-buffer {}))
  ([opts]
   (if-not interop/debug-enabled?
     []
     (let [{:keys [operation op-type since frame
                   severity event-id handler-id source origin
                   dispatch-id since-ms between pred]} opts
           sensitive-filter (:sensitive? opts)
           [between-t0 between-t1] (when (and (sequential? between)
                                              (= 2 (count between)))
                                     between)
           predicate
           (fn [ev]
             (and (or (nil? operation) (= operation (:operation ev)))
                  (or (nil? op-type)   (= op-type   (:op-type ev)))
                  (or (nil? since)     (and (number? (:id ev))
                                            (> (:id ev) since)))
                  (or (nil? frame)
                      (= frame (or (:frame ev)
                                   (get-in ev [:tags :frame]))))
                  (or (nil? severity) (= severity (:op-type ev)))
                  (or (nil? event-id)
                      (= event-id (get-in ev [:tags :event-id])))
                  (or (nil? handler-id)
                      (= handler-id (get-in ev [:tags :handler-id])))
                  (or (nil? source)
                      (= source (or (:source ev)
                                    (get-in ev [:tags :source]))))
                  (or (nil? origin)
                      (= origin (get-in ev [:tags :origin])))
                  (or (nil? dispatch-id)
                      (= dispatch-id (get-in ev [:tags :dispatch-id])))
                  (or (nil? since-ms)
                      (and (number? (:time ev))
                           (> (:time ev) since-ms)))
                  (or (nil? between-t0)
                      (and (number? (:time ev))
                           (<= between-t0 (:time ev) between-t1)))
                  ;; Per rf2-isdwf: top-level `:sensitive?` is hoisted
                  ;; (NOT nested under :tags). Match against the
                  ;; top-level slot only; absent reads as false.
                  (or (nil? sensitive-filter)
                      (= (true? sensitive-filter)
                         (true? (:sensitive? ev))))
                  (or (nil? pred) (pred ev))))]
       (filterv predicate @trace-buffer-state)))))

(defn clear-trace-buffer!
  "Empty the ring buffer. Tooling uses this between sessions. No-op in
  production. Per Spec 009 §Retain-N trace ring buffer."
  []
  (when interop/debug-enabled?
    (reset! trace-buffer-state []))
  nil)

(defn configure-trace-buffer!
  "Set the ring buffer's depth. depth=0 disables the buffer (the delivery
  path still works). The new depth applies on the next append; existing
  entries are trimmed to fit immediately. No-op in production.

  Per Spec 009 §Retain-N trace ring buffer."
  [{:keys [depth]}]
  (when (and interop/debug-enabled? (number? depth) (not (neg? depth)))
    (reset! buffer-depth depth)
    (swap! trace-buffer-state
           (fn [v]
             (let [n (count v)]
               (cond
                 (zero? depth) []
                 (> n depth)   (subvec v (- n depth))
                 :else         v)))))
  nil)

(defn configure
  "Generic config dispatch. Recognises :trace-buffer; future config knobs
  add cases here. Per Spec 009 §Retain-N trace ring buffer
  (`(rf/configure :trace-buffer {:depth N})`)."
  [k opts]
  (case k
    :trace-buffer (configure-trace-buffer! opts)
    nil))

;; ---- emission -------------------------------------------------------------

(defn- deliver-to-epoch-capture!
  "Forward the assembled trace event to the epoch-capture buffer if
  re-frame.epoch has registered its capture hook. The capture hook
  is published through `re-frame.late-bind` (key `:epoch/capture-event`);
  routing through there keeps this namespace free of a require on
  re-frame.epoch and ensures `clear-trace-cbs!` (a user-facing API) does
  NOT wipe the internal capture path. Per Tool-Pair §Time-travel and
  Spec 009 §`register-epoch-cb!`."
  [event]
  (when-let [capture (late-bind/get-fn :epoch/capture-event)]
    (try
      (capture event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ---- shared emit substrate ------------------------------------------------
;;
;; `emit!` (success) and `emit-error!` (error) share an envelope-
;; construction core (`build-event`, pure; reads `*handler-scope*`) and
;; a delivery path (`deliver!`, side-effect: ring buffer + epoch capture
;; + listener fan-out). The two public emit fns are thin wrappers
;; carrying the prod-DCE outer gate.
;;
;; The outer `(when interop/debug-enabled? ...)` and inner `(when-not
;; (:no-emit? *handler-scope*) ...)` guards stay in the wrappers — NOT
;; in `build-event` — because Spec 009 §Production builds mandates the
;; outermost form of an emit call be `(when interop/debug-enabled? ...)`
;; alone for Closure DCE to elide the expression under `:advanced` +
;; `goog.DEBUG=false`.

(defn- compute-sensitive?
  "Hoist `:sensitive?` from the in-scope handler's registration meta,
  with caller-supplied `:sensitive?` in `tags` winning (queue-time
  `:event/dispatched` computes its own reading). Per Spec 009
  §Privacy / sensitive data in traces."
  [tags scope]
  (let [tag-sensitive? (:sensitive? tags)]
    (cond
      (some? tag-sensitive?) (true? tag-sensitive?)
      :else                  (true? (some-> scope :sensitive?)))))

(defn- stamp-cascade-id
  "Merge the cascade's `:dispatch-id` into `base-tags` so consumers
  can group raw trace events by cascade without inferring from
  sequence. Caller-supplied `:dispatch-id` wins. Per Spec 009
  §Dispatch correlation."
  [base-tags cascade-id]
  (if (and cascade-id (not (contains? base-tags :dispatch-id)))
    (assoc base-tags :dispatch-id cascade-id)
    base-tags))

(defn- build-event
  "Assemble the trace envelope (pure construction; reads
  `*handler-scope*`). `op-type` discriminates the success vs error
  paths — see Spec 009 §Core fields and §Error event shape for the
  hoist contract (`:source` / `:recovery` / `:rf.trace/trigger-handler`
  / `:rf.trace/call-site` / `:sensitive?`)."
  [op-type operation tags]
  (let [scope       *handler-scope*
        trigger     (some-> scope :trigger-handler)
        cascade-id  (some-> scope :dispatch-id)
        sensitive?  (compute-sensitive? tags scope)
        error?      (= op-type :error)
        source      (:source tags)
        recovery    (if error?
                      (:recovery tags :no-recovery)
                      (:recovery tags))
        call-site   (when error? (some-> scope :call-site))
        ;; Strip hoisted slots from `:tags` so they don't double-up at
        ;; the top level. Exception: the error path KEEPS `:source`
        ;; under `:tags` because boundary-interceptor / error-emit
        ;; sites use it as an emission-site discriminator (e.g.
        ;; `:source :boundary` per Spec 010 §Production builds), and
        ;; consumers already fall back top-level → `:tags :source`.
        ;; Error path additionally merges `{:category operation}`.
        base-tags   (cond-> (dissoc tags :recovery :sensitive?)
                      (not error?) (dissoc :source)
                      error?       (->> (merge {:category operation})))
        tags+       (stamp-cascade-id base-tags cascade-id)]
    (cond-> {:operation operation
             :op-type   op-type
             :id        (next-event-id)
             :time      (interop/now-ms)
             :tags      tags+}
      source               (assoc :source source)
      ;; Success path hoists :recovery only when caller supplied one;
      ;; error path always stamps (defaulting to :no-recovery above).
      (or error? recovery) (assoc :recovery recovery)
      trigger              (assoc :rf.trace/trigger-handler trigger)
      ;; `:rf.trace/call-site` rides error traces only.
      call-site            (assoc :rf.trace/call-site call-site)
      ;; Top-level `:sensitive? true` stamp. Absent (not `false`)
      ;; when not sensitive — consumers treat absent as false.
      sensitive?           (assoc :sensitive? true))))

(defn- deliver!
  "Side-effect dispatch for an assembled trace envelope: ring-buffer
  push, epoch-capture fan-out, and listener fan-out. Synchronous;
  throwing listeners are isolated. Per Spec 009 §Listener invocation
  rules."
  [event]
  (push-to-buffer! event)
  (deliver-to-epoch-capture! event)
  (doseq [[_ f] @listeners]
    (try
      (f event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn emit!
  "Emit a trace event. Production builds elide the body entirely
  (Closure DCE on the `interop/debug-enabled?` gate); in dev / JVM
  the envelope is built and delivered to the ring buffer, epoch
  capture, and all registered listeners synchronously.

  Reads `*handler-scope*` to hoist the in-scope slots onto the
  envelope: `:trigger-handler` on every emit, `:dispatch-id` merged
  into `:tags`, `:sensitive?` stamped at top level. Short-circuits
  before allocation when the scope's `:no-emit?` slot is true.

  Per Spec 009 §Emitting trace events and §Handler-scope."
  [op operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds
    ;; (the outer gate must stand alone for Closure DCE — see
    ;; §Production-elision verification).
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (build-event op operation tags)))))

(defn emit-error!
  "Emit a structured error trace event. `:operation` is the error
  category (e.g. `:rf.error/handler-exception`), `:op-type` is
  `:error`, and `:tags` includes `:category`, `:exception`,
  `:where`, etc.

  Reads `*handler-scope*` to hoist `:trigger-handler`, `:call-site`,
  and `:dispatch-id` onto the envelope, and to honour the `:no-emit?`
  short-circuit (symmetric with `emit!`). Per Spec 009 §Error contract
  and §Handler-scope."
  [error-operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds.
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (build-event :error error-operation tags)))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.registrar emits a trace event when a handler is replaced but
;; cannot `:require` this namespace without a cyclic load order.
;; Publish `emit!` through the late-bind hook registry. See
;; re-frame.late-bind.

(late-bind/set-fn! :trace/emit!       emit!)
(late-bind/set-fn! :trace/emit-error! emit-error!)
