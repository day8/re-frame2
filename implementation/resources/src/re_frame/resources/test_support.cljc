(ns re-frame.resources.test-support
  "Test-support namespace for the resources artefact (Spec 016).

  This namespace is the home for resources test-only fixtures and reset
  helpers — kept OUT of the always-on `re-frame.resources` production
  façade so a test-runner-internal surface never reaches a production
  registry (the rf2-dbiv8 / rf2-cdmle posture: test fixtures live behind
  an explicit test-support require, mirroring
  `re-frame.routing.test-support` and `re-frame.http-test-support`).

  Production posture: this namespace is unreferenced from any production
  module, so CLJS `:advanced` trims it wholesale and JVM/SSR sees
  classpath absence through the normal artefact-require boundary.

  ## What lives here

  - `reset-resources!` — the per-test reset thunk the shared CLJS
    `make-reset-runtime-fixture` reset-hooks table fires (via the
    `:resources/reset-resources!` late-bind hook the façade publishes):
    clears the `:resource`-kind registrar entries and the host-side
    transient generation high-water marks (which are NOT runtime-db
    state, so a runtime/frames reset does not clear them).

  SKELETON slice (rf2-p10npe): the reset helper clears the registrar +
  host-side generation cache; the canned-transport stub fixtures (the
  resources analogue of the managed-HTTP canned stubs — for deterministic
  ensure/refetch replay without a live Fetch) land with the runtime +
  managed-HTTP slices, behind this same test-support require."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

(defn reset-resources!
  "Per-test reset: clear every `:resource`-kind registrar entry and the
  host-side transient generation high-water marks. Fired by the shared
  CLJS `make-reset-runtime-fixture` reset-hooks table via the
  `:resources/reset-resources!` late-bind hook this namespace publishes
  at ns-load (kept behind this explicit test-support require so the
  production façade carries no test-fixture surface — rf2-dbiv8). Per
  Spec 016 (test isolation)."
  []
  (registrar/clear-kind! registry/resource-kind)
  (state/reset-cache!)
  nil)

;; Publish the reset hook from this test-support ns-load — the shared CLJS
;; make-reset-runtime-fixture reset-hooks table consults
;; `:resources/reset-resources!` by key and no-ops when this namespace is
;; absent (production builds never load it). Mirrors the
;; re-frame.routing.test-support / re-frame.http-test-support posture.
(late-bind/set-fn! :resources/reset-resources! reset-resources!)
