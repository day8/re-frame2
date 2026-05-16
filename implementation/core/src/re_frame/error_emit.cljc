(ns re-frame.error-emit
  "Always-on error-emit substrate. Per Spec 009 §What IS available in
  production §Error-handler policy.

  Survives `:advanced` + `goog.DEBUG=false`. Carries two independent
  fan-out paths from one normative emission site (the router's
  handler-exception path):

    1. Corpus-wide listener registry — every fn registered through
       [[register-error-emit-listener!]] receives a tight error-record:

         {:error        <kw>     ;; e.g. :rf.error/handler-exception
          :event        <vector> ;; dispatched event vector (elided)
          :event-id     <kw>
          :frame        <kw>
          :time         <millis>
          :exception    <ex>
          :elapsed-ms   <int>
          :source-coord {:ns :file :line}  ;; rf2-3un2g; absent if
                                           ;; the failing handler was
                                           ;; registered programmatically
                                           ;; (no macro capture)
          }

       For off-box observability shippers (Sentry, Honeybadger,
       Rollbar). Per rf2-3un2g the `:source-coord` slot rides the
       always-on parallel `error-coords-by-id` registry so it survives
       CLJS `:advanced` + `goog.DEBUG=false` builds where public
       registry-meta has been stripped of coord-keys.

    2. Per-frame `:on-error` policy fn — the frame's `:on-error` slot,
       when present, receives a structured error event (`:operation` /
       `:tags` / `:recovery` shape) for in-app recovery decisions.

  Both paths are independent (a buggy listener cannot block the policy
  fn; a buggy policy fn cannot block listeners). Both are try/catch
  wrapped.

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The substrate
  proper carries no gate.

  When the failing event's registered handler-meta carries
  `:sensitive? true`, the always-on error path ENFORCES privacy — it
  does NOT merely warn. Both fan-out paths surface the event slot as
  `:rf/redacted` rather than the raw event vector. The exception
  object, event-id, frame, and recovery decision flow through
  unchanged (operators need them for triage); the event payload —
  which may carry credentials / PII — is scrubbed at the substrate
  boundary."
  (:require [re-frame.elision        :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.frame          :as frame]
            [re-frame.late-bind      :as late-bind]
            [re-frame.privacy        :as privacy]
            [re-frame.registrar      :as registrar]
            [re-frame.source-coords  :as source-coords]))

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
  break the cascade. Returns the policy fn's return value or nil."
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
  unchanged when the shape doesn't match the documented form. The
  structured error-event passed to the per-frame `:on-error` policy
  fn surfaces the redacted event when the failing handler is
  registered `:sensitive? true`."
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
  `:rf/redacted`), then fans out along two independent paths
  (listener registry, per-frame `:on-error` policy).

  When the failing event's handler is registered `:sensitive? true`,
  BOTH fan-out paths surface the event slot as `:rf/redacted` (in the
  tight record's `:event` slot AND the structured error-event's
  `:tags :event` slot). The exception, event-id, frame, and
  elapsed-ms ride through unchanged so operators retain the triage
  signal.

  Called by `router.cljc` from the handler-exception path. Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time error-event]
  (let [handler-meta (when event-id (registrar/lookup :event event-id))
        sensitive?   (privacy/sensitive?-from-meta handler-meta)
        ;; Per rf2-3un2g §Always-on error-coord registry: source-coords
        ;; for the failing handler ride the always-on parallel registry
        ;; (NOT the public registry-meta — which is stripped of coord-
        ;; keys under CLJS `:advanced + goog.DEBUG=false`). The lookup
        ;; here surfaces `{:ns :file :line}` for Sentry-style shippers
        ;; and the per-frame `:on-error` policy fn in BOTH dev AND
        ;; production. Returns nil for programmatic registrations that
        ;; bypassed the macro path — that's fine; the slot is absent
        ;; from the record / tags rather than nil.
        source-coord (when event-id (source-coords/error-coords-for :event event-id))
        ;; Redact the event slot at the substrate boundary when the
        ;; failing handler is declared sensitive. Otherwise run the
        ;; wire-walker as before (paths flagged `:sensitive?` /
        ;; `:large?` via the per-frame `:rf/elision` registry still
        ;; get their per-path substitutions).
        elided-event (if sensitive?
                       privacy/redacted-sentinel
                       (elision/elide-wire-value event {:frame frame-id}))
        record       (cond-> {:error      error-kw
                              :event      elided-event
                              :event-id   event-id
                              :frame      frame-id
                              :time       time
                              :exception  exception
                              :elapsed-ms elapsed-ms}
                       source-coord (assoc :source-coord source-coord))
        ;; The structured policy-event's `:tags` get the same
        ;; `:source-coord` slot (innermost-wins: caller-supplied
        ;; `:source-coord` already on `:tags` would survive
        ;; intentionally; we only `assoc-when-missing`).
        policy-event (let [pe (if sensitive?
                                (redact-tags-event error-event)
                                error-event)]
                       (if (and source-coord
                                (map? pe)
                                (map? (:tags pe))
                                (not (contains? (:tags pe) :source-coord)))
                         (update pe :tags assoc :source-coord source-coord)
                         pe))]
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
