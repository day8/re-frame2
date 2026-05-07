(ns re-frame-2.trace
  "Per-process trace event stream. Per Spec 009.

  Every dispatch, drain step, render, fx invocation, error, and machine
  transition emits a structured trace event. Listeners (10x, re-frame-pair,
  AI tools) subscribe and consume the stream.

  Production builds elide trace emission entirely via the
  `re-frame-2.interop/debug-enabled?` flag — see emit! below.

  Trace event shape (per Spec 009 §Core fields):
    {:operation   :event/run               ;; required
     :op-type     :event                   ;; one of :event :sub :fx :machine :registry ...
     :id          <uuid-or-counter>        ;; required, unique per emit
     :time        <millis>                 ;; required
     :duration    <millis>                 ;; optional
     :tags        {...}}                   ;; required, open map"
  (:require [re-frame-2.interop :as interop]))

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

;; ---- emission -------------------------------------------------------------

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
    (let [source (:source tags)
          ;; :source is mirrored at the top level (per Spec 009 §Core
          ;; fields) AND retained in :tags so consumers that read either
          ;; shape see it. Different fixtures expect different shapes.
          event  (cond-> {:operation operation
                          :op-type   op
                          :id        (next-event-id)
                          :time      (interop/now-ms)
                          :tags      tags}
                   source (assoc :source source))]
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
      (doseq [[_ f] @listeners]
        (try
          (f event)
          (catch #?(:clj Throwable :cljs :default) _ nil))))))
