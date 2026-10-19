(ns re-frame.machines-after-cljs-test
  "`:after` delayed transitions under the Reagent reactive substrate: a
  literal delay schedules at the state's epoch on entry and fires on the
  matching synthetic timer event. The synthetic
  `:rf.machine.timer/after-elapsed` event is dispatched directly, so the test
  is deterministic under Node."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest machine-after-cljs
  (testing ":after schedules with current epoch on entry; fires on synthetic timer event"
    (let [machine
          {:initial :idle
           :data    {}
           :states
           {:idle    {:on {:fetch :loading}}
            :loading {:after {5000 :timeout}}
            :timeout {}}}
          traces (atom [])]
      (rf/reg-machine :http/flow machine)
      (rf.trace.tooling/register-listener! ::after (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:http/flow [:fetch]])
      ;; Per Spec 005 §Hierarchy interaction the epoch is per-decl-path.
      (let [s (snapshot :http/flow)]
        (is (= [:loading 1] [(:state s) (get-in s [:rf/runtime :after-epoch [:loading]])])
            "entering the :after-bearing state advanced its epoch"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 5000     (:delay (:tags ev)))
                       (= 1        (:epoch (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled trace with :delay-source :literal")
      (reset! traces [])
      (rf/dispatch-sync [:http/flow [:rf.machine.timer/after-elapsed 5000 1 [:loading]]])
      (let [s (snapshot :http/flow)]
        (is (= [:timeout 2] [(:state s) (get-in s [:rf/runtime :after-epoch [:loading]])])
            "the matching-epoch firing transitioned :loading → :timeout and advanced
             the node's epoch on that exit"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/fired (:operation ev))
                       (true? (:fired? (:tags ev)))
                       (= 1    (:epoch  (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/fired trace with matching epoch")
      (rf.trace.tooling/unregister-listener! ::after))))

