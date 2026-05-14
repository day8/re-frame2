(ns re-frame.event-emit
  "Always-on event-emit substrate for production observability
  (Datadog, Sentry, Honeycomb). Per Spec 009 §What IS available in
  production §Event-emit listener.

  Survives `:advanced` + `goog.DEBUG=false`. Parallel to (not a
  fallback for) the dev-only trace surface. One record per processed
  event — NOT subs, NOT fxs, NOT `:event/db-changed`. Tight record
  shape per Spec 009 §Event-emit listener §Record shape:

    {:event       <vector>     ;; the dispatched event vector (elided)
     :event-id    <kw>          ;; (first event)
     :frame       <kw>          ;; resolved frame-id
     :time        <millis>      ;; emit timestamp (host clock, ms)
     :outcome     :ok | :error  ;; handler exception → :error
     :elapsed-ms  <int>}        ;; wall-clock from queue → settle

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The
  substrate proper carries no gate.

  If the event's registered handler-meta carries `:sensitive? true`,
  the record is dropped entirely (listeners are NOT invoked).
  `:rf.trace/no-emit? true` likewise drops — framework-internal
  bookkeeping handlers (Causa, Story) are not user-domain observable
  signal."
  (:require [re-frame.elision       :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.late-bind     :as late-bind]
            [re-frame.privacy       :as privacy]
            [re-frame.registrar     :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners
  ;; id -> listener fn. `defonce` so hot reload of this namespace
  ;; does not silently drop a long-lived production listener that
  ;; the consuming app registered at boot.
  (atom {}))

(def ^:private registry
  (emit/make-listener-registry {:listeners listeners}))

(def register-event-emit-listener!
  "Register a listener `f` under `id`. Re-registering the same id
  replaces. `f` receives a single event-record map (see ns docstring
  §Record shape); its return value is ignored. Returns `id`. Per
  rf2-rirbq."
  (:register registry))

(def unregister-event-emit-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-event-emit-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil."
  (:clear registry))

;; ---- emission -------------------------------------------------------------

(defn dispatch-on-event!
  "Fan an elided event-record out to every registered listener.
  Always-on (NOT gated by `re-frame.interop/debug-enabled?`) — fires
  in CLJS production builds where the trace surface is elided.

  Short-circuits to a no-op when the registry is empty (one deref +
  empty-map check). Otherwise looks up the event's handler-meta and
  drops the record when `:sensitive?` (rf2-6hklf) or
  `:rf.trace/no-emit?` (rf2-qsjda) is set. Surviving records run
  through `re-frame.elision/elide-wire-value` ONCE with off-box
  defaults (large → `:rf.size/large-elided`; sensitive →
  `:rf/redacted`), then fan out through the emit-substrate registry.
  Listener exceptions are caught inside the registry's fan-out.

  Called by `router.cljc` once per processed event after the cascade
  body settles (`:db` committed, flows run, `:fx` walked). Published
  under the late-bind key `:event-emit/dispatch-on-event` so the
  router does NOT statically `:require` this namespace."
  [event event-id frame time outcome elapsed-ms]
  (when (seq @listeners)
    (let [handler-meta (registrar/lookup :event event-id)]
      (when-not (or (privacy/sensitive?-from-meta handler-meta)
                    (:rf.trace/no-emit? handler-meta))
        (let [elided-event (elision/elide-wire-value event {:frame frame})
              record       {:event      elided-event
                            :event-id   event-id
                            :frame      frame
                            :time       time
                            :outcome    outcome
                            :elapsed-ms elapsed-ms}]
          ((:fan-out registry) record)))))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` invokes `dispatch-on-event!` once per processed event.
;; The router looks the fn up through the late-bind hook table at call
;; time rather than `:require`ing this namespace directly. Per
;; rf2-rirbq.

(late-bind/set-fn! :event-emit/dispatch-on-event dispatch-on-event!)
