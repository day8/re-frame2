(ns re-frame.error-emit
  "Always-on error-emit substrate. Per Spec 009 §What IS available in
  production §Error-handler policy.

  Survives `:advanced` + `goog.DEBUG=false`. Carries two independent
  fan-out paths from one normative emission site (the router's
  handler-exception path):

    1. Corpus-wide listener registry — every fn registered through
       [[register-error-listener!]] receives a tight error-record:

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

  NOTE: handler-meta `:sensitive?` is no longer consulted here.
  Sensitive data marking is path-based per the upcoming data-
  classification mechanism (separate spec doc; in progress) — the
  per-path elision wire-walker is the load-bearing redaction surface
  on this path."
  (:require [re-frame.elision        :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.frame          :as frame]
            [re-frame.late-bind      :as late-bind]
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

(def register-error-listener!
  "Register a listener `f` under `id`. Re-registering the same id
  replaces. `f` receives a single error-record map (see ns docstring
  §Record shape); its return value is ignored. Returns `id`. Per
  rf2-bacs4."
  (:register registry))

(def unregister-error-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-error-listeners!
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

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the two always-on error-emit
  fan-out paths. Always-on (NOT gated by `re-frame.interop/debug-
  enabled?`) — fires in CLJS production builds where the trace
  surface is elided.

  Builds the tight error-record ONCE, runs
  `re-frame.elision/elide-wire-value` against `:event` with off-box
  defaults (large → `:rf.size/large-elided`; per-path sensitive
  declarations → `:rf/redacted`), then fans out along two independent
  paths (listener registry, per-frame `:on-error` policy).

  Sensitive-data redaction on this path is path-based: the per-frame
  `:rf/elision` registry's `:sensitive-declarations` drive the wire-
  walker's per-slot substitutions. Handler-meta `:sensitive?` is no
  longer consulted (path-marked classification is the v2 mechanism;
  separate spec doc; in progress).

  Called by `router.cljc` from the handler-exception path. Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time error-event]
  (let [;; Per rf2-3un2g §Always-on error-coord registry: source-coords
        ;; for the failing handler ride the always-on parallel registry
        ;; (NOT the public registry-meta — which is stripped of coord-
        ;; keys under CLJS `:advanced + goog.DEBUG=false`). The lookup
        ;; here surfaces `{:ns :file :line}` for Sentry-style shippers
        ;; and the per-frame `:on-error` policy fn in BOTH dev AND
        ;; production. Returns nil for programmatic registrations that
        ;; bypassed the macro path — that's fine; the slot is absent
        ;; from the record / tags rather than nil.
        source-coord (when event-id (source-coords/error-coords-for :event event-id))
        ;; Per-path wire-walker: paths flagged `:sensitive?` / `:large?`
        ;; via the per-frame `:rf/elision` registry get their per-path
        ;; substitutions.
        elided-event (elision/elide-wire-value event {:frame frame-id})
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
        policy-event (if (and source-coord
                              (map? error-event)
                              (map? (:tags error-event))
                              (not (contains? (:tags error-event) :source-coord)))
                       (update error-event :tags assoc :source-coord source-coord)
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
