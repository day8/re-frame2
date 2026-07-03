(ns re-frame.machines.error-emit
  "Two-channel fan-out for the machine `:rf.error/machine-action-exception`
  category — the fix for the broken production emit path (rf2-cprm0q).

  `:rf.error/machine-action-exception` is catalogued **always-on** (per
  [009 §Error event catalogue](../../../../spec/009-Instrumentation.md)):
  a machine action / guard body that throws is a production-reachable runtime
  failure an off-box shipper (Sentry / Datadog) MUST still see on a
  `:advanced` + `goog.DEBUG=false` build (a 2am machine throw). Every machine
  emit site (`lifecycle_fx/registration.cljc`, `lifecycle_fx/finalize.cljc`,
  `lifecycle_fx/traces.cljc`) previously called ONLY the dev-gated
  `trace/emit-error!`, so the record was DCE'd out of production entirely —
  an always-on-catalogued category silently swallowed. This ns is the single
  home the three sites route through so the always-on channel can never be
  forgotten again.

  Two channels fire in lock-step (mirroring core's
  `re-frame.error-emit/emit-error-both!`, adapted to the machine NON-EVENT
  union record):

    Axis 1 — the always-on corpus-wide error-emit substrate (surface #4,
    production-survivable, NOT gated by `re-frame.interop/debug-enabled?`):
    a tight, STRUCTURAL-ONLY union record, fanned out through the
    `:error-emit/dispatch-error-record` late-bind hook — the SAME axis + hook
    the machine fail-closed spawn reject
    (`:rf.error/machine-spawn-unregistered-type`) uses (`machines` ships above
    core's require graph, so it cannot static-require `re-frame.error-emit`).

    Axis 2 — the dev-only `re-frame.trace/emit-error!` surface (DCE'd under
    CLJS `:advanced` + `goog.DEBUG=false`): the FULL, rich `tags` for the
    in-process tooling surface (Xray / Story), UNCHANGED.

  ## Attribution (rf2-cprm0q defect 2)

  The always-on record carries the machine attribution the bead adds:
  `:failing-id` (the throwing action / guard's keyword) + `:state` (the active
  state path). These are machine-local CODE identifiers in the same privacy
  class as `:event-id`, so an off-box shipper learns WHICH action / guard in
  WHICH state threw — not just the bare category (\"machine :auth/flow threw\").

  ## Payload hygiene (production-surviving — enforced by the whitelist)

  The always-on record is NOT privacy-gated like the dev trace (which redacts
  value-bearing slots at the marks-projection egress chokepoint). So the
  always-on record is projected from `tags` through the
  `always-on-record-keys` WHITELIST: only structural machine-local
  identifiers (keyword ids / state paths) plus the raw `:exception` cross.
  The value-bearing slots — `:event`, `:input`, and crucially
  `:exception-data` (the developer's arbitrary ex-data, which may embed the
  same app secrets a `:sensitive` machine `:data` mark gates) — are
  DELIBERATELY EXCLUDED. The raw `:exception` IS carried: off-box shippers
  need the host exception + stack, exactly as every other always-on error
  record carries it."
  (:require [re-frame.interop   :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace     :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private always-on-record-keys
  "Whitelist of STRUCTURAL `tags` keys promoted onto the production-surviving
  always-on record. See the ns docstring §Payload hygiene: value-bearing
  slots (`:event` / `:input` / `:exception-data`) are excluded; `:exception`
  (the raw Throwable) is carried for off-box shippers."
  [:frame :actor-id :failing-id :state :transition
   :exception :exception-message :reason :recovery :phase])

(defn emit-machine-action-exception!
  "Fan `:rf.error/machine-action-exception` out through BOTH error channels
  from ONE `tags` map (see the ns docstring). `tags` is the full dev-trace tag
  map the emit site already built; the always-on record is projected from it
  via `always-on-record-keys` and stamped with `:error` + `:time`. Returns nil.

  Callers stamp `:failing-id` (the action / guard keyword) and `:state` (the
  active state path) into `tags` so the always-on record carries the
  rf2-cprm0q attribution."
  [tags]
  ;; Axis 1 — always-on (surface #4). Structural-only whitelist projection.
  ;; Late-bound: `machines` ships above core's require graph, so the hook is
  ;; nil (no-op) until `re-frame.error-emit` loads — bound once at that
  ;; ns-load, so the lookup never misses in a live runtime.
  (when-let [dispatch-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-record!
      (assoc (select-keys tags always-on-record-keys)
             :error :rf.error/machine-action-exception
             :time  (interop/now-ms))))
  ;; Axis 2 — dev-only trace (DCE'd under :advanced + goog.DEBUG=false).
  (trace/emit-error! :rf.error/machine-action-exception tags)
  nil)
