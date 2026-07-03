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

  This ns owns AXIS 1 ONLY — the always-on corpus-wide error-emit substrate
  (surface #4, production-survivable, NOT gated by
  `re-frame.interop/debug-enabled?`): a tight, STRUCTURAL-ONLY union record,
  fanned out through the `:error-emit/dispatch-error-record` late-bind hook —
  the SAME axis + hook the machine fail-closed spawn reject
  (`:rf.error/machine-spawn-unregistered-type`) uses (`machines` ships above
  core's require graph, so it cannot static-require `re-frame.error-emit`).

  ## Why axis 2 (the dev trace) is NOT routed through this ns (rf2-cprm0q elision)

  The dev-only `re-frame.trace/emit-error!` surface (DCE'd under CLJS
  `:advanced` + `goog.DEBUG=false`) carries the FULL, rich `tags` — INCLUDING
  the dev-only diagnostic PROSE (`:reason` / `:exception-message`) the 009
  elision probe pins as an eliminated sentinel (e.g. the destroy-`:exit`
  `\"An :exit action threw …\"` string). That prose MUST DCE in production.

  `trace/emit-error!` is a DIRECT call whose body folds to `nil` under
  `goog.DEBUG=false`, so Closure drops the map literal it is handed — prose and
  all — AS DEAD, but ONLY when that map is a DIRECT argument to the folded
  call. If the dev `tags` map were instead handed to THIS ns's fn (an INDIRECT,
  non-folding call — it fans out on the always-on late-bind hook), Closure
  would have to retain the whole opaque map, and every prose string literal
  inside it would survive into the production bundle (the exact rf2-cprm0q
  regression: rerouting a fold-elided direct emit through an always-on helper
  resurrects its prose).

  So each emit site (`registration.cljc`, `finalize.cljc`, `traces.cljc`) keeps
  its dev trace as its OWN DIRECT `trace/emit-error!` call — the fold-elidable
  form — and calls THIS ns only with the structural always-on record. The two
  channels never share a map, and the prose has no non-folding referent.

  ## Attribution (rf2-cprm0q defect 2)

  The always-on record carries the machine attribution the bead adds:
  `:failing-id` (the throwing action / guard's keyword) + `:state` (the active
  state path). These are machine-local CODE identifiers in the same privacy
  class as `:event-id`, so an off-box shipper learns WHICH action / guard in
  WHICH state threw — not just the bare category (\"machine :auth/flow threw\").

  ## Payload hygiene (production-surviving — STRUCTURAL-ONLY)

  The always-on record is NOT privacy-gated like the dev trace (which redacts
  value-bearing slots at the marks-projection egress chokepoint). Per the
  `:rf.error/machine-action-exception` catalogue row
  ([009 §Error event catalogue](../../../../spec/009-Instrumentation.md):
  \"the always-on record is STRUCTURAL-ONLY\"), the caller builds it from
  structural VALUES only — the machine-local identifiers `:actor-id` /
  `:failing-id` / `:state`, the discriminator enums `:phase` / `:recovery`,
  `:frame`, and the raw `:exception` cross. The value-bearing slots — `:event`,
  `:input`, the developer's arbitrary `:exception-data` (which may embed the
  same app secrets a `:sensitive` machine `:data` mark gates), the free-text
  `:reason` / `:exception-message` PROSE — are DELIBERATELY EXCLUDED: they ride
  the dev trace only. The raw `:exception` IS carried: off-box shippers need the
  host exception + stack, exactly as every other always-on error record does."
  (:require [re-frame.interop   :as interop]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(defn emit-machine-action-exception!
  "Fan the STRUCTURAL-ONLY `:rf.error/machine-action-exception` record out on
  the ALWAYS-ON axis (surface #4) ONLY. Returns nil.

  `always-on` is the caller-built structural record (see the ns docstring
  §Payload hygiene): `:actor-id` / `:failing-id` / `:state` (the rf2-cprm0q
  attribution — machine-local code identifiers), the discriminator enums
  `:phase` / `:recovery`, `:frame`, and the raw `:exception`. This fn stamps
  `:error` + `:time` and fans it out UNCHANGED. It carries NO free-text prose
  and NO value-bearing slots.

  Axis 2 (the dev `trace/emit-error!`) is NOT routed through here — each emit
  site keeps its own DIRECT `trace/emit-error!` call so the dev-only prose
  folds away under `:advanced` + `goog.DEBUG=false` (see the ns docstring §Why
  axis 2 is NOT routed through this ns). This fn is the single always-on home,
  so the production-survivable channel can never be forgotten."
  [always-on]
  ;; Always-on (surface #4). Late-bound: `machines` ships above core's require
  ;; graph, so the hook is nil (no-op) until `re-frame.error-emit` loads — bound
  ;; once at that ns-load, so the lookup never misses in a live runtime.
  (when-let [dispatch-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-record! (assoc always-on
                             :error :rf.error/machine-action-exception
                             :time  (interop/now-ms))))
  nil)
