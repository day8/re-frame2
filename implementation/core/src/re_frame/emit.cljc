(ns re-frame.emit
  "Always-on event/error emit listener registration surface.
  Per Spec 009 §What IS available in production.

  This namespace is the single public registration facade for the
  two always-on emit substrates:

    - Event-emit listeners — one record per processed event for
      production observability shippers (Datadog, Sentry, Honeycomb).
    - Error-emit listeners — one record per `:rf.error/*` handler
      failure for off-box exception shippers.

  Survives `:advanced` + `goog.DEBUG=false`. Parallel to (not a
  fallback for) the dev-only `re-frame.trace` surface. Per rf2-ic1sv:
  consolidated naming · dev/prod axis encoded in the namespace
  (`re-frame.trace/*` = dev-only / DCE'd; `re-frame.emit/*` = always-
  on / survives prod).

  Public surface:

    - `register-event-listener!` / `unregister-event-listener!` / `clear-event-listeners!`
    - `register-error-listener!` / `unregister-error-listener!` / `clear-error-listeners!`

  Substrate machinery (the `dispatch-on-event!` / `dispatch-on-error!`
  fan-out paths and elision wiring) lives in the sibling
  `re-frame.event-emit` + `re-frame.error-emit` namespaces and is
  reached by the router through `re-frame.late-bind` hooks. Apps
  consume this surface; substrate-internals stay private.

  Per Spec 009 §Event-emit listener / §Error-handler policy."
  (:require [re-frame.event-emit :as event-emit]
            [re-frame.error-emit :as error-emit]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- event-emit listener surface -----------------------------------------

(def register-event-listener!
  "Register an event-emit listener `f` under `id`. Re-registering
  the same id replaces. `f` receives a single event-record map per
  processed event (see `re-frame.event-emit` ns docstring §Record
  shape); its return value is ignored. Returns `id`.

  Survives CLJS `:advanced` + `goog.DEBUG=false`. Listener
  REGISTRATION sites SHOULD wrap calls in an explicit production
  config check; the substrate itself carries no gate."
  event-emit/register-event-listener!)

(def unregister-event-listener!
  "Drop the event-emit listener registered under `id`. Returns nil."
  event-emit/unregister-event-listener!)

(def clear-event-listeners!
  "Drop every registered event-emit listener. Test-isolation only;
  production code should never call this. Returns nil."
  event-emit/clear-event-listeners!)

;; ---- error-emit listener surface -----------------------------------------

(def register-error-listener!
  "Register an error-emit listener `f` under `id`. Re-registering
  the same id replaces. `f` receives a single error-record map per
  handler-exception (see `re-frame.error-emit` ns docstring §Record
  shape); its return value is ignored. Returns `id`.

  Survives CLJS `:advanced` + `goog.DEBUG=false`. Listener
  REGISTRATION sites SHOULD wrap calls in an explicit production
  config check; the substrate itself carries no gate."
  error-emit/register-error-listener!)

(def unregister-error-listener!
  "Drop the error-emit listener registered under `id`. Returns nil."
  error-emit/unregister-error-listener!)

(def clear-error-listeners!
  "Drop every registered error-emit listener. Test-isolation only;
  production code should never call this. Returns nil."
  error-emit/clear-error-listeners!)
