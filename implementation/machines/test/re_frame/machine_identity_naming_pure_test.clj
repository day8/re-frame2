(ns re-frame.machine-identity-naming-pure-test
  "Adversarial JVM (pure-surface) coverage for the machine-identity naming
  split. Exercises the pure `machine-transition` reducer directly (no live
  frame) so the emitted spawn/destroy fx args are the contract under test.

  Pins two identity distinctions:

   1. **Explicit actor-address INPUT vs declarative invocation PATH**.
      On the InvokeSpec, `:fixed-actor-id` (the explicit address) pins the
      spawned id; the runtime stamps the invocation path under
      `:rf/invoke-id` on the emitted `:rf.machine/spawn` /
      `:rf.machine/destroy` fx args. They are distinct facts with distinct
      shapes (a bare keyword vs a state-path vector).

   2. **Registered TYPE vs spawned INSTANCE**. The InvokeSpec `:machine-id`
      names the registered TYPE; the allocated `:rf/spawned-id` is the live
      INSTANCE address (`<type>#<n>`) — never conflated. The generated case
      is pinned by the `spawn-on-entry-destroy-on-exit` conformance fixture,
      which `machines_conformance_test` runs."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(deftest fixed-actor-id-pins-instance-distinct-from-invoke-path
  (testing "an explicit :fixed-actor-id pins the instance; the invocation path rides :rf/invoke-id, distinct"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :working}}
                          :working {:spawn {:machine-id     :idn/worker
                                            :fixed-actor-id :idn/pinned}}}}
          {fx :fx} (rf.machines/machine-transition spec {:state :idle :data {}} [:go])
          [[_ args]]       fx]
      (is (= :idn/pinned (:rf/spawned-id args))
          "the explicit :fixed-actor-id is used verbatim as the instance address (no gensym, no #n)")
      (is (= [:working] (:rf/invoke-id args))
          "the invocation PATH is still the state-path — independent of the explicit address INPUT")
      (is (not= (:rf/spawned-id args) (:rf/invoke-id args))
          "explicit-address identity and invocation-path identity are distinct facts"))))
