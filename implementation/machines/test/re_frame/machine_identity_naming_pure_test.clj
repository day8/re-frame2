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
      INSTANCE address (`<type>#<n>`) — never conflated."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

(deftest gensym-spawn-emits-distinct-type-instance-and-invoke-path
  (testing "rf2-ws5thu/rf2-0ggtr5 — a gensym spawn fx carries TYPE (:machine-id), INSTANCE (:rf/spawned-id) and invocation PATH (:rf/invoke-id) as distinct facts"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :working}}
                          :working {:spawn {:machine-id :idn/worker
                                            :data       {:k :v}}}}}
          {fx ::result/fx} (machines/machine-transition spec {:state :idle :data {}} [:go])
          [[fx-id args]]   fx]
      (is (= :rf.machine/spawn fx-id))
      (is (= :idn/worker (:machine-id args))
          ":machine-id is the registered TYPE")
      (is (= :idn/worker#1 (:rf/spawned-id args))
          ":rf/spawned-id is the live INSTANCE address <type>#<n>")
      (is (= [:working] (:rf/invoke-id args))
          ":rf/invoke-id is the declarative invocation PATH (state-path vector)")
      ;; The three identities are NOT conflated.
      (is (not= (:machine-id args) (:rf/spawned-id args))
          "TYPE and INSTANCE are distinct values")
      (is (vector? (:rf/invoke-id args))
          "the invocation path is a vector — never a bare id keyword")
      ;; The overloaded keys are not part of the emitted args.
      (is (not (contains? args :rf/spawn-id))
          "retired reserved arg :rf/spawn-id is gone (now :rf/invoke-id)")
      (is (not (contains? args :spawn-id))
          "retired InvokeSpec key :spawn-id is gone (now :fixed-actor-id)"))))

(deftest fixed-actor-id-pins-instance-distinct-from-invoke-path
  (testing "rf2-0ggtr5 — an explicit :fixed-actor-id pins the instance; the invocation path still rides :rf/invoke-id, distinct"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :working}}
                          :working {:spawn {:machine-id     :idn/worker
                                            :fixed-actor-id :idn/pinned}}}}
          {fx ::result/fx} (machines/machine-transition spec {:state :idle :data {}} [:go])
          [[_ args]]       fx]
      (is (= :idn/pinned (:rf/spawned-id args))
          "the explicit :fixed-actor-id is used verbatim as the instance address (no gensym, no #n)")
      (is (= [:working] (:rf/invoke-id args))
          "the invocation PATH is still the state-path — independent of the explicit address INPUT")
      (is (not= (:rf/spawned-id args) (:rf/invoke-id args))
          "explicit-address identity and invocation-path identity are distinct facts"))))

(deftest destroy-on-exit-carries-invoke-id-not-spawn-id
  (testing "rf2-0ggtr5 — exiting a :spawn-bearing state emits :rf.machine/destroy keyed by :rf/invoke-id (the invocation path)"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle    {:on {:go :working}}
                          :working {:spawn {:machine-id :idn/worker}
                                    :on    {:back :idle}}}}
          ;; Snapshot positioned in :working so :back exits the :spawn-bearing state.
          {fx ::result/fx} (machines/machine-transition spec {:state :working :data {}} [:back])
          destroy (some (fn [[fx-id args]] (when (= :rf.machine/destroy fx-id) args)) fx)]
      (is (some? destroy) "exiting the :spawn-bearing state emits a :rf.machine/destroy fx")
      (is (= [:working] (:rf/invoke-id destroy))
          "the destroy fx resolves the actor via the invocation PATH under :rf/invoke-id")
      (is (not (contains? destroy :rf/spawn-id))
          "retired reserved arg :rf/spawn-id is gone"))))
