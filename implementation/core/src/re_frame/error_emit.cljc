(ns re-frame.error-emit
  "Always-on error-emit substrate. Per rf2-hqbeh (per-frame `:on-error`
  policy fan-out) and rf2-bacs4 (corpus-wide listener fan-out). Per
  Spec 009 §What IS available in production §Error-handler policy.

  Survives `:advanced` + `goog.DEBUG=false`. Carries two independent
  fan-out paths from one normative emission site (the router's
  handler-exception path):

    1. Corpus-wide listener registry (rf2-bacs4) — every fn
       registered through [[register-error-emit-listener!]] receives
       a tight error-record (Spec 009 §Record shape):

         {:error      <kw>       ;; e.g. :rf.error/handler-exception
          :event      <vector>    ;; dispatched event vector (elided)
          :event-id   <kw>
          :frame      <kw>
          :time       <millis>
          :exception  <ex>
          :elapsed-ms <int>}

       For off-box observability shippers (Sentry / Honeybadger /
       Rollbar).

    2. Per-frame `:on-error` policy fn (rf2-hqbeh) — the frame's
       `:on-error` slot, when present, receives a structured error
       event (`:operation` / `:tags` / `:recovery` shape) for in-app
       recovery decisions.

  Both paths are independent (a buggy listener cannot block the
  policy fn; a buggy policy fn cannot block listeners). Both are
  try/catch wrapped per Spec 009 §1052.

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The
  substrate proper carries no gate. Sibling to the event-emit
  listener surface (rf2-rirbq); register both when forwarding to a
  single hosted observability back-end.

  Per rf2-vnjfg (security audit): when the failing event's registered
  handler-meta carries `:sensitive? true`, the always-on error path
  ENFORCES privacy — it does NOT merely warn. Both fan-out paths
  surface the event slot as the `:rf/redacted` sentinel rather than
  the raw event vector. Errors still observe (the exception object,
  the failing event-id, the frame, the recovery decision all flow
  through unchanged — operators need them for triage), but the
  dispatched event payload — which may carry credentials, payment
  details, PII — is scrubbed at the substrate boundary. Mirrors the
  rf2-6hklf event-emit drop policy; we redact rather than drop here
  because errors are a recovery surface that MUST be observable."
  (:require [re-frame.elision       :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.frame         :as frame]
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

(def register-error-emit-listener!
  "Register a listener `f` under `id`. Re-registering the same id
  replaces. `f` receives a single error-record map (see ns docstring
  §Record shape); its return value is ignored. Returns `id`. Per
  rf2-bacs4."
  (:register registry))

(def unregister-error-emit-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-error-emit-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil."
  (:clear registry))

;; ---- emission -------------------------------------------------------------

(defn- fire-on-error-policy!
  "Invoke the frame's `:on-error` policy fn with `error-event`. No-op
  when the frame is unregistered, when no `:on-error` slot is
  configured, or when the slot is not a fn. Per Spec 009 §1052
  policy-fn exceptions are caught here so a buggy policy fn cannot
  break the cascade. Returns the policy fn's return value or nil.
  Per rf2-hqbeh."
  [frame-id error-event]
  (when-let [f (frame/frame frame-id)]
    (when-let [policy (get-in f [:config :on-error])]
      (when (fn? policy)
        (try
          (policy error-event)
          (catch #?(:clj Throwable :cljs :default) _ nil))))))

(defn- redact-tags-event
  "Substitute `:tags :event` (and `:tags :emit-event` if present) in
  `error-event` with `:rf/redacted`. Defensive: returns the input
  unchanged when the shape doesn't match the documented form.
  Per rf2-vnjfg: the structured error-event passed to the per-frame
  `:on-error` policy fn MUST surface the redacted event when the
  failing handler is registered `:sensitive? true`."
  [error-event]
  (if (and (map? error-event) (map? (:tags error-event)))
    (update error-event :tags
            (fn [tags]
              (cond-> tags
                (contains? tags :event)      (assoc :event privacy/redacted-sentinel)
                (contains? tags :emit-event) (assoc :emit-event privacy/redacted-sentinel))))
    error-event))

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the two always-on error-emit
  fan-out paths. Always-on (NOT gated by `re-frame.interop/debug-
  enabled?`) — fires in CLJS production builds where the trace
  surface is elided.

  Builds the tight error-record ONCE, runs
  `re-frame.elision/elide-wire-value` against `:event` with off-box
  defaults (large → `:rf.size/large-elided`; sensitive →
  `:rf/redacted`), then fans out along two independent paths:

    1. Corpus-wide listener registry (rf2-bacs4) — emit-substrate
       fan-out. Listener exceptions caught; siblings still run.
    2. Per-frame `:on-error` policy fn (rf2-hqbeh) — receives the
       supplied `error-event` map. Policy-fn exceptions caught per
       Spec 009 §1052.

  Per rf2-vnjfg (security audit): consults the failing event's
  registered handler-meta `:sensitive?` flag. When the handler is
  `:sensitive? true`, BOTH fan-out paths surface the event slot as
  `:rf/redacted`:

    - The tight error-record's `:event` slot is the `:rf/redacted`
      sentinel (not the elided event vector).
    - The structured error-event's `:tags :event` slot is the
      `:rf/redacted` sentinel (in place of `emit-event`).

  This is the substrate-level guarantee — even when the handler
  failed before `with-redacted` ran (or no `with-redacted` was
  declared at all), the always-on error path does NOT ship the raw
  event payload to listeners or to the policy fn. The exception
  object, the event-id, the frame, the elapsed-ms ride through
  unchanged so operators retain the triage signal.

  Called by `router.cljc` from the handler-exception path. The
  `elapsed-ms` is the wall-clock from cascade-start to throw,
  rounded to an integer at the substrate boundary per the contract
  (mirrors rf2-ph8pa / rf2-rirbq). Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time error-event]
  (let [handler-meta (when event-id (registrar/lookup :event event-id))
        sensitive?   (privacy/sensitive?-from-meta handler-meta)
        ;; Per rf2-vnjfg: redact the event slot at the substrate boundary
        ;; when the failing handler is declared sensitive. Otherwise run
        ;; the wire-walker as before (paths flagged `:sensitive?` /
        ;; `:large?` via the per-frame `:rf/elision` registry still get
        ;; their per-path substitutions).
        elided-event (if sensitive?
                       privacy/redacted-sentinel
                       (elision/elide-wire-value event {:frame frame-id}))
        record       {:error      error-kw
                      :event      elided-event
                      :event-id   event-id
                      :frame      frame-id
                      :time       time
                      :exception  exception
                      :elapsed-ms elapsed-ms}
        policy-event (if sensitive?
                       (redact-tags-event error-event)
                       error-event)]
    ((:fan-out registry) record)
    (fire-on-error-policy! frame-id policy-event))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` already statically `:require`s this namespace (per
;; rf2-hqbeh; the substrate is a foundational always-on surface
;; alongside the router itself), so a late-bind hook isn't strictly
;; needed here. We publish one anyway for symmetry with rf2-rirbq's
;; `:event-emit/dispatch-on-event` hook and to keep the substrate
;; addressable from other artefacts that may want to fire error
;; records without static-requiring this ns.

(late-bind/set-fn! :error-emit/dispatch-on-error dispatch-on-error!)
