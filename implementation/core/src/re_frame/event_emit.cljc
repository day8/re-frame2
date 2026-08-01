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
     :outcome     <kw>          ;; dispatch outcome (see below)
     :elapsed-ms  <int>}        ;; wall-clock from queue → settle

  `:outcome` is one of — it covers every cascade-failure path, not
  just the interceptor-chain exception:

    :ok          — clean settle (db committed, flows ran, :fx walked).
    :error       — the interceptor chain (handler or interceptor) threw.
    :rolled-back — candidate `:db` schema validation REJECTED the
                   transition BEFORE install (Spec 010 §Per-step
                   recovery row 4, rf2-uhk9ko): the container was never
                   written and keeps its pre-handler value. This
                   substrate survives the production gate but THIS
                   OUTCOME HAS NO PRODUCER IN A PRODUCTION BUILD —
                   app-db candidate validation is dev-only (Spec 010
                   §Production builds, rf2-bkvu5), so in a release build
                   a candidate that violates a registered schema simply
                   installs and the dispatch reports `:ok`. An off-box
                   shipper must not read the absence of `:rolled-back`
                   as evidence that no schema was violated.
    :flow-error  — a flow's `:output` threw (Spec 013 §Failure
                   semantics rule 3); the cascade halted before `:fx`.
    :rejected    — the `:rf.schema/at-boundary` interceptor REFUSED the
                   event's payload against the handler's `:schema`
                   (Spec 010 §Production builds, rf2-mwv4e). The handler
                   never ran; entered interceptors still unwound in
                   full. This is the exact COMPLEMENT of `:rolled-back`
                   on the production question: boundary validation is
                   UNGATED per Spec 010, so `:rejected` DOES have a
                   producer in a release build. It is not the only
                   ungated schema check (C-000.35 settles that by
                   what the check is for), but it is the only one this `:outcome`
                   vocabulary names in its own right — the others throw,
                   and a throw reports `:error` —
                   it is the outcome to alert on for hostile or
                   malformed input at an untrusted ingress. Reported
                   only when the boundary skip is the whole story: a
                   chain throw (`:error`), a flow throw
                   (`:flow-error`) or a candidate rollback
                   (`:rolled-back`) during the unwind still wins.
                   Paired with one always-on
                   `:rf.error/schema-validation-failure` record
                   (`:source :boundary`) on the `:errors` stream, which
                   carries the identifiers; the offending VALUE rides
                   the dev-only trace and never egresses.

  Every non-`:ok` value surfaces a failed dispatch to off-box shippers
  so a rolled-back / flow-aborted / boundary-refused dispatch is never
  mis-reported as a clean `:ok`.

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The
  substrate proper carries no gate.

  `:rf.trace/no-emit? true` on the event's registered handler-meta
  drops the record entirely — framework-internal bookkeeping handlers
  (Xray, Story) are not user-domain observable signal.

  Sensitive data marking is path-based per the data-classification
  mechanism (separate spec doc); handler-meta `:sensitive?` is not
  consulted here."
  (:require [re-frame.elision       :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.late-bind     :as late-bind]
            [re-frame.registrar     :as registrar]
            [re-frame.trace         :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners
  ;; id -> listener fn. `defonce` so hot reload of this namespace
  ;; does not silently drop a long-lived production listener that
  ;; the consuming app registered at boot.
  (atom {}))

(def ^:private registry
  (emit/make-listener-registry {:listeners listeners}))

(def register-event-listener!
  "Register a listener `f` under `id`. Re-registering the same id
  replaces. `f` receives a single event-record map (see ns docstring
  §Record shape); its return value is ignored. Returns `id`."
  (:register registry))

(def unregister-event-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-event-listeners!
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
  drops the record when `:rf.trace/no-emit?` is set.
  Surviving records run through `re-frame.elision/elide-wire-value`
  ONCE with off-box defaults (large → `:rf.size/large-elided`;
  sensitive paths → `:rf/redacted`), then fan out through the emit-
  substrate registry. Listener exceptions are caught inside the
  registry's fan-out.

  Called by `router.cljc` once per processed event after the cascade
  body settles (`:db` committed, flows run, `:fx` walked). Published
  under the late-bind key `:event-emit/dispatch-on-event` so the
  router does NOT statically `:require` this namespace."
  [event event-id frame time outcome elapsed-ms]
  (when (and (trace/continuation-live?) (seq @listeners))
    (let [handler-meta (try
                         (registrar/lookup :event event-id)
                         (catch #?(:clj Throwable :cljs :default) e
                           (when (trace/continuation-live?) (throw e))))]
      ;; Handler resolution may execute a generation resolver.
      (when (and (trace/continuation-live?)
                 (not (trace/no-emit?-from-meta handler-meta)))
        (let [elided-event (try
                             (elision/elide-wire-value event {:frame frame})
                             (catch #?(:clj Throwable :cljs :default) e
                               (when (trace/continuation-live?) (throw e))))]
          ;; Elision/classification is callback-bearing. Listener sibling fanout
          ;; is one already-linearized publication and remains failure-isolated.
          (when (trace/continuation-live?)
            ((:fan-out registry)
             {:event      elided-event
              :event-id   event-id
              :frame      frame
              :time       time
              :outcome    outcome
              :elapsed-ms elapsed-ms}
             trace/continuation-live?))))))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` invokes `dispatch-on-event!` once per processed event.
;; The router looks the fn up through the late-bind hook table at call
;; time rather than `:require`ing this namespace directly.

(late-bind/set-fn! :event-emit/dispatch-on-event dispatch-on-event!)
