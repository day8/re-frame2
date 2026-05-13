(ns re-frame.error-emit
  "Always-on error-emit substrate for the per-frame `:on-error` slot.
  Per rf2-hqbeh / Spec 002 §`:on-error` and Spec 009 §Error-handler
  policy.

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

  ## Surface (tiny by design)

  - [[dispatch-on-error!]] — invoke the `:on-error` policy fn for the
    given frame with the supplied error event map. Catches policy-fn
    exceptions per Spec 009 §1052 so a buggy policy fn cannot break the
    cascade. Returns the policy's return value (the runtime uses it to
    apply recovery; the return-map contract is dev-side material today
    — production callers ignore the return for now). No-op when the
    frame's config has no `:on-error` slot.

  ## What this namespace deliberately does NOT do

  - It does NOT allocate or push to the trace ring buffer.
  - It does NOT fan out to `register-trace-cb!` listeners. Cross-frame
    error observation through trace listeners remains a dev-only surface
    (per Spec 009 §Subscription / consumption).
  - It does NOT validate the return map's shape. Return-map validation
    (`:rf.error/bad-on-error-return`) is itself a trace emission and
    remains dev-side. Production callers should treat the policy fn's
    return as advisory until the validation path is widened.

  Per Spec 009 §Production debugging: this is the recommended way to
  wire production error monitoring (Sentry, Honeybadger, Rollbar) when
  full trace-surface preservation (`:closure-defines {goog.DEBUG true}`)
  is undesirable for bundle-size reasons."
  (:require [re-frame.frame :as frame]))

(defn dispatch-on-error!
  "Invoke the frame's `:on-error` policy fn with `error-event`. Always-on
  (NOT gated by `re-frame.interop/debug-enabled?`) — fires in CLJS
  production builds where the trace surface is elided.

  - `frame-id` — the keyword id of the frame whose `:on-error` slot
    should be consulted. When the frame is unregistered or destroyed,
    no-op (returns nil).
  - `error-event` — a structured error-event map per Spec 009 §Core
    fields shape, at minimum carrying `:operation`, `:op-type :error`,
    and a `:tags` map.

  Per Spec 009 §1052: exceptions raised by the policy fn are caught
  here; the runtime does NOT recursively invoke the policy on its own
  exception. The cascade caller falls back to the documented per-
  category recovery in that case.

  Returns the policy fn's return value, or nil when no policy was
  registered / the frame is missing / the policy threw."
  [frame-id error-event]
  (when-let [f (frame/frame frame-id)]
    (when-let [policy (get-in f [:config :on-error])]
      (when (fn? policy)
        (try
          (policy error-event)
          (catch #?(:clj Throwable :cljs :default) _ nil))))))
