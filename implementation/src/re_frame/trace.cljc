(ns re-frame.trace
  "Per-process trace event stream. Per Spec 009.

  Every dispatch, drain step, render, fx invocation, error, and machine
  transition emits a structured trace event. Listeners (10x, re-frame-pair,
  AI tools) subscribe and consume the stream.

  Production builds elide trace emission entirely via the
  `re-frame.interop/debug-enabled?` flag — see emit! below.

  Trace event shape (per Spec 009 §Core fields):
    {:operation   :event/run               ;; required
     :op-type     :event                   ;; one of :event :sub :fx :machine :registry ...
     :id          <uuid-or-counter>        ;; required, unique per emit
     :time        <millis>                 ;; required
     :duration    <millis>                 ;; optional
     :tags        {...}}                   ;; required, open map"
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

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

  With opts, filters the result. Recognised keys:
    :operation  — keep only events with this :operation value.
    :op-type    — keep only events with this :op-type value.
    :since      — keep only events whose :id is strictly greater than this.
    :frame      — keep only events whose :tags :frame matches.

  Filters compose: every supplied key must match. Returns an empty vector
  in production (the buffer never receives events when interop/debug-enabled?
  is false at compile time).

  Per Spec 009 §Retain-N trace ring buffer."
  ([] (trace-buffer {}))
  ([opts]
   (if-not interop/debug-enabled?
     []
     (let [{:keys [operation op-type since frame]} opts
           pred (fn [ev]
                  (and (or (nil? operation) (= operation (:operation ev)))
                       (or (nil? op-type)   (= op-type   (:op-type ev)))
                       (or (nil? since)     (and (number? (:id ev))
                                                 (> (:id ev) since)))
                       (or (nil? frame)
                           (= frame (or (:frame ev)
                                        (get-in ev [:tags :frame]))))))]
       (filterv pred @trace-buffer-state)))))

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
  Spec 009 §`register-epoch-cb`."
  [event]
  (when-let [capture (late-bind/get-fn :epoch/capture-event)]
    (try
      (capture event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn emit!
  "Emit a trace event. In production builds (when interop/debug-enabled?
  is false at compile time), Closure DCE removes the body and the call
  becomes a no-op.

  In dev / JVM: builds the envelope and delivers to all registered
  listeners. Delivery is synchronous — listeners SHOULD be fast; per
  Spec 009 §Listener invocation rules, batching is the listener's choice.

  Per Spec 009 §Core fields: :source is hoisted to the top level of the
  envelope (origin of the trigger — :ui :timer :http :repl :machine).
  Tags retain everything else."
  [op operation tags]
  (when interop/debug-enabled?
    (let [source   (:source tags)
          recovery (:recovery tags)
          ;; Per Spec 009 §Required top-level fields: :source and
          ;; :recovery (when present) live at the top level of the
          ;; trace event, NOT inside :tags. Hoist them here.
          event    (cond-> {:operation operation
                            :op-type   op
                            :id        (next-event-id)
                            :time      (interop/now-ms)
                            :tags      (dissoc tags :source :recovery)}
                     source   (assoc :source source)
                     recovery (assoc :recovery recovery))]
      (push-to-buffer! event)
      (deliver-to-epoch-capture! event)
      (doseq [[_ f] @listeners]
        (try
          (f event)
          (catch #?(:clj Throwable :cljs :default) _
            ;; Listeners that throw don't break the runtime; the stream
            ;; continues. Per Spec 009: listener failures are isolated.
            nil))))))

(defn emit-error!
  "Emit a structured error trace event. Per Spec 009 §Error contract:
  `:operation` is the error category (e.g. `:rf.error/handler-exception`),
  `:op-type` is :error, and `:tags` includes `:category`, `:exception`,
  `:where`, etc."
  [error-operation tags]
  (when interop/debug-enabled?
    (let [event {:operation error-operation
                 :op-type   :error
                 :id        (next-event-id)
                 :time      (interop/now-ms)
                 :tags      (merge {:category error-operation} tags)
                 :recovery  (:recovery tags :no-recovery)}]
      (push-to-buffer! event)
      (deliver-to-epoch-capture! event)
      (doseq [[_ f] @listeners]
        (try
          (f event)
          (catch #?(:clj Throwable :cljs :default) _ nil))))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.registrar emits a trace event when a handler is replaced but
;; cannot `:require` this namespace without a cyclic load order.
;; Publish `emit!` through the late-bind hook registry. See
;; re-frame.late-bind.

(late-bind/set-fn! :trace/emit!       emit!)
(late-bind/set-fn! :trace/emit-error! emit-error!)
