(ns re-frame.error-emit
  "Always-on error-emit substrate. Per rf2-hqbeh (per-frame `:on-error`
  policy fan-out) and rf2-bacs4 (corpus-wide listener fan-out).

  ## Why this namespace exists

  Spec 009's trace surface is compile-time elided in CLJS production
  builds via `re-frame.interop/debug-enabled?` — see Spec 009 §Production
  builds. The original `:on-error` integration rode the trace surface and
  elided with it; production `:on-error` callbacks did not fire, defeating
  the slot's framing as a runtime error-recovery surface. The bug surfaced
  in rf2-hqbeh.

  This namespace carves a small **always-on** delivery path that survives
  `goog.DEBUG=false`. It runs alongside the trace surface — not through
  it — so:

    - Trace events still emit through `re-frame.trace/emit-error!` in dev
      and on the JVM; that path remains gated and still elides in CLJS
      prod, as documented.
    - The per-frame `:on-error` policy fn fires through THIS path on
      both dev and prod. The policy receives the structured error event
      and may return a recovery map per Spec 009 §Error-handler policy.
    - Corpus-wide listeners registered through
      [[register-error-emit-listener!]] fire through THIS path on both
      dev and prod. Each listener receives one tight error-record per
      `:rf.error/*` event the runtime emits through this substrate.
      Sibling to the event-emit listener surface (rf2-rirbq).

  ## Two surfaces, one substrate

  The error-emit substrate carries two independent fan-out paths from
  one normative emission site (the router's handler-exception path):

  | Surface                              | Consumer concern                 | Bead       |
  |--------------------------------------|----------------------------------|------------|
  | Per-frame `:on-error` policy fn      | In-app recovery / retry / mark   | rf2-hqbeh  |
  | Corpus-wide listener registry        | Off-box observability shippers   | rf2-bacs4  |

  Both paths are independent (one bad listener cannot affect the
  policy fn; a policy-fn throw cannot affect listeners). Both paths
  are wrapped in try/catch. Both paths are always-on; both survive
  `:advanced` + `goog.DEBUG=false`.

  ## Surface

  - [[dispatch-on-error!]] — invoked by `router.cljc` on handler-
    exception. Builds the tight error-record once, runs
    `re-frame.elision/elide-wire-value` against `:event`, fans out
    to BOTH the registered corpus-wide listeners AND the per-frame
    `:on-error` policy. Each fan-out leg is independent and try/catch
    wrapped per Spec 009 §1052.
  - [[register-error-emit-listener!]]   — register a listener fn
    under an id. Re-registering the same id replaces.
  - [[unregister-error-emit-listener!]] — drop a listener by id.
  - [[clear-error-emit-listeners!]]     — wipe the registry; test
    isolation only.

  ## Record shape (TIGHT — Spec 009)

    {:error      <kw>        ;; e.g. :rf.error/handler-exception
     :event      <vector>     ;; the dispatched event vector (elided)
     :event-id   <kw>         ;; (first event)
     :frame      <kw>         ;; resolved frame-id
     :time       <millis>     ;; emit timestamp (host clock, ms since epoch)
     :exception  <ex>         ;; the thrown exception object
     :elapsed-ms <int>}       ;; wall-clock from queue → throw

  No trace-bus keys (`:dispatch-id`, `:parent-dispatch-id`,
  `:rf.trace/trigger-handler`, ...) — those ride the dev-only trace
  surface and DCE under `:advanced` + `goog.DEBUG=false`. Listeners
  that want them must run a `register-trace-cb!` listener too (dev-
  only path).

  ## goog.DEBUG framing

  The substrate is always-on (no `goog.DEBUG` check inside this
  namespace). Listener REGISTRATION sites SHOULD use `goog.DEBUG` as a
  belt-and-braces gate alongside the user's explicit config flag, e.g.

    (when (and (= \"production\" (:env config))
               (not ^boolean re-frame.interop/debug-enabled?)
               (:api-key config))
      (rf/register-error-emit-listener!
        :sentry/forward
        (fn [error-record]
          (sentry/capture-exception (:exception error-record)
                                    {:tags {:event-id (:event-id error-record)
                                            :frame    (:frame error-record)}}))))

  Catches the 'accidentally deployed a dev bundle with prod config'
  bug class — symmetric with rf2-rirbq's event-emit substrate.

  ## Listener responsibilities

  - Listeners are invoked synchronously after the handler exception is
    surfaced. Keep bodies cheap; ship work to a background channel if
    it cannot fit inside the drain step's wall-clock budget.
  - Listeners receive an error-record whose `:event` vector has
    ALREADY been passed through `re-frame.elision/elide-wire-value`
    with off-box defaults (large values → `:rf.size/large-elided`
    marker; sensitive values → `:rf/redacted`). Listeners SHOULD
    NOT re-run elision unless they want to widen the policy.
  - Exceptions raised by a listener are caught here; sibling
    listeners still run and the per-frame `:on-error` policy fn
    still fires. No retry, no recursive emit.

  ## What this namespace deliberately does NOT do

  - It does NOT allocate or push to the trace ring buffer.
  - It does NOT fan out to `register-trace-cb!` listeners. Cross-frame
    error observation through trace listeners remains a dev-only
    surface (per Spec 009 §Subscription / consumption).
  - It does NOT validate the return map's shape. Return-map validation
    (`:rf.error/bad-on-error-return`) is itself a trace emission and
    remains dev-side. Production callers should treat the policy fn's
    return as advisory until the validation path is widened.
  - It does NOT carry `:dispatch-id` / `:parent-dispatch-id` /
    source-coord enrichment — those are trace-surface-only.

  Per Spec 009 §Production debugging: this is the recommended way to
  wire production error monitoring (Sentry, Honeybadger, Rollbar, …)
  when full trace-surface preservation (`:closure-defines {goog.DEBUG
  true}`) is undesirable for bundle-size reasons. Use the per-frame
  `:on-error` slot for in-app recovery; use
  [[register-error-emit-listener!]] for off-box observability."
  (:require [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners
  ;; id -> listener fn. `defonce` so hot reload of this namespace
  ;; does not silently drop a long-lived production listener that
  ;; the consuming app registered at boot.
  (atom {}))

(defn register-error-emit-listener!
  "Register a listener `f` under `id`. The id can be any value usable
  as a map key; re-registering under the same id replaces. `f`
  receives a single error-record map (see ns docstring §Record
  shape) and its return value is ignored. Returns `id`.

  Always-on: the substrate is NOT gated on
  `re-frame.interop/debug-enabled?`. Registration sites SHOULD still
  use `goog.DEBUG=false` as a belt-and-braces gate around
  production-only listeners — see the ns docstring §goog.DEBUG
  framing.

  Per rf2-bacs4. Sibling of `rf/register-event-emit-listener!`
  (rf2-rirbq); the two listener surfaces are independent — register
  both when forwarding to a single hosted observability back-end."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn unregister-error-emit-listener!
  "Drop the listener registered under `id`. Returns nil. Per rf2-bacs4."
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-error-emit-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil. Per rf2-bacs4."
  []
  (reset! listeners {})
  nil)

;; ---- fan-out --------------------------------------------------------------

(defn- fan-out-listeners!
  "Fan an elided error-record out to every registered listener.
  Short-circuits to nil when the registry is empty so the per-error
  hot-path cost reduces to one deref + an empty-map check. Listener
  exceptions are caught — the cascade does NOT abort and sibling
  listeners still run. Per rf2-bacs4."
  [record]
  (let [reg @listeners]
    (when (seq reg)
      (doseq [[_id f] reg]
        (try
          (f record)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  nil)

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

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the two always-on error-emit
  fan-out paths. Always-on (NOT gated by `re-frame.interop/debug-
  enabled?`) — fires in CLJS production builds where the trace
  surface is elided.

  Builds the tight error-record (Spec 009; rf2-bacs4 §Record shape)
  ONCE, runs `re-frame.elision/elide-wire-value` against `:event`
  with off-box defaults (large → `:rf.size/large-elided`;
  sensitive → `:rf/redacted`), then fans out along two independent
  paths:

    1. **Corpus-wide listener registry** (rf2-bacs4) — every fn
       registered through [[register-error-emit-listener!]] receives
       the elided tight record. Listener exceptions are caught;
       siblings still run.

    2. **Per-frame `:on-error` policy fn** (rf2-hqbeh) — the
       frame's `:on-error` slot, when present, receives the
       supplied `error-event` map (the legacy structured shape with
       `:operation`/`:tags`/`:recovery` keys, used for in-app
       recovery decisions). Policy-fn exceptions are caught per
       Spec 009 §1052.

  Both fan-out paths are independent: a buggy listener cannot block
  the policy fn, and a buggy policy fn cannot block listeners. The
  runtime does NOT recursively invoke either path on its own
  exception.

  Called by `router.cljc` from the handler-exception path. The
  `start-ms` argument is the cascade-start wall-clock (already
  captured for the event-emit substrate); the elapsed-ms is computed
  here once at the substrate boundary, integer on both platforms per
  the contract.

  Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time error-event]
  (let [elided-event (elision/elide-wire-value event {:frame frame-id})
        record       {:error      error-kw
                      :event      elided-event
                      :event-id   event-id
                      :frame      frame-id
                      :time       time
                      :exception  exception
                      :elapsed-ms elapsed-ms}]
    (fan-out-listeners! record)
    (fire-on-error-policy! frame-id error-event))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` invokes `dispatch-on-error!` on the handler-exception
;; path. It already statically `:require`s this namespace (per
;; rf2-hqbeh; the substrate is a foundational always-on surface
;; alongside the router itself), so a late-bind hook isn't strictly
;; needed here. We publish one anyway for symmetry with rf2-rirbq's
;; `:event-emit/dispatch-on-event` hook and to keep the substrate
;; addressable from other artefacts that may want to fire error
;; records without static-requiring this ns.

(late-bind/set-fn! :error-emit/dispatch-on-error dispatch-on-error!)
