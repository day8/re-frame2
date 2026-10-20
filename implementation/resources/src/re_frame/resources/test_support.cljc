(ns re-frame.resources.test-support
  "Test-only reset for the resources artefact, kept out of the
  `re-frame.resources` façade so no production build carries it; CLJS
  `:advanced` trims it as unreferenced. Mirrors `re-frame.routing.test-support`."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.mutation-events :as rf.resources.mutation-events]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.revalidate-listeners :as rf.resources.revalidate-listeners]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.timers :as rf.resources.timers]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

(defn reset-resources!
  "Per-test reset: clears the resource, mutation and scope registrar kinds and
  every host-side side table (generation cache, work-ledger handles, timers,
  revalidation listeners, one-shot dev-warning dedupe sets). The host-side
  tables are not runtime-db, so a runtime or frames reset leaves them behind,
  and a leftover timer or listener would fire into a later test's frame."
  []
  (rf.registrar/clear-kind! rf.resources.registry/resource-kind)
  (rf.registrar/clear-kind! rf.resources.mutation-registry/mutation-kind)
  (rf.registrar/clear-kind! rf.resources.scope-registry/scope-kind)
  (rf.resources.state/reset-cache!)
  (rf.resources.work-ledger/reset-cache!)
  (rf.resources.timers/reset-cache!)
  (rf.resources.revalidate-listeners/reset-cache!)
  (rf.resources.mutation-events/reset-mutation-scope-mismatch-warnings!)
  (rf.resources.mutation-events/reset-mutation-target-skipped-warnings!)
  nil)

;; The shared CLJS reset-fixture table consults this key and no-ops when this
;; namespace is absent, which it always is in production.
(rf.late-bind/set-fn! :resources/reset-resources! reset-resources!)
