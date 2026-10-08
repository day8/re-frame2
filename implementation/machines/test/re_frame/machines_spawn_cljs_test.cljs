(ns re-frame.machines-spawn-cljs-test
  "Declarative `:spawn` under the Reagent substrate: a spawn emits both the
  fx-substrate `:rf.machine.spawn/spawned` and the registrar-substrate
  `:rf.machine.lifecycle/spawned` trace (Spec 009 §Two-axis machine
  observation)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest machine-spawn-two-axis-cljs
  (testing "a spawn emits BOTH :rf.machine.spawn/spawned (fx) and :rf.machine.lifecycle/spawned (registrar)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {}
                  :states  {:idle    {:on {:go :working}}
                            :working {:spawn {:machine-id :qpuk4/worker}}}}
          traces (atom [])]
      (rf/reg-machine :qpuk4/worker child)
      (rf/reg-machine :qpuk4/sup    parent)
      (rf.trace.tooling/register-listener! ::two-axis (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:qpuk4/sup [:go]])
      (rf.trace.tooling/unregister-listener! ::two-axis)
      (is (some (fn [ev]
                  (and (= :rf.machine.spawn/spawned (:operation ev))
                       (= :qpuk4/worker (:machine-id (:tags ev)))))
                @traces)
          "fx-substrate axis: expected :rf.machine.spawn/spawned")
      ;; registrar-substrate axis — the round-trip the Xray consumer keys on
      (is (some (fn [ev]
                  (and (= :rf.machine.lifecycle/spawned (:operation ev))
                       (= :qpuk4/worker   (:machine-id (:tags ev)))
                       (= :qpuk4/worker#1 (:spawned-id (:tags ev)))
                       (= :running        (:state (:tags ev)))))
                @traces)
          "registrar-substrate axis: expected :rf.machine.lifecycle/spawned carrying :spawned-id + initial :state"))))
