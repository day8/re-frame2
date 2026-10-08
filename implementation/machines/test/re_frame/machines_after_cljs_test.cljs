(ns re-frame.machines-after-cljs-test
  "`:after` delayed transitions under the Reagent reactive substrate: a
  literal delay schedules at the state's epoch on entry and fires on the
  matching synthetic timer event, and a subscription-vector delay's
  `:scheduled` trace names its subscription. The synthetic
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
        (is (= [:loading 1] [(:state s) (get-in s [:data :rf/after-epoch [:loading]])])
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
        (is (= [:timeout 2] [(:state s) (get-in s [:data :rf/after-epoch [:loading]])])
            "the matching-epoch firing transitioned :loading → :timeout and advanced
             the node's epoch on that exit"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/fired (:operation ev))
                       (true? (:fired? (:tags ev)))
                       (= 1    (:epoch  (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/fired trace with matching epoch")
      (rf.trace.tooling/unregister-listener! ::after))))

(deftest machine-after-subscription-delay-cljs
  (testing "subscription-vector delay: :scheduled trace carries :delay-source :sub + :rf.sub/id + :rf.sub/query-v"
    (rf/reg-event
      :a/sub-config-set
      (fn [{:keys [db]} [_ ms]] {:db (assoc db :timeout-config ms)}))
    (rf/reg-sub
      :a/timeout-config
      (fn [db _] (:timeout-config db)))
    (rf/dispatch-sync [:a/sub-config-set 4000])
    (let [m {:initial :idle
             :data    {}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {[:a/timeout-config] :timeout}}
              :timeout {}}}
          traces (atom [])]
      (rf/reg-machine :a/sub-cljs m)
      (rf.trace.tooling/register-listener! ::sub (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/sub-cljs [:fetch]])
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= :sub                (:delay-source (:tags ev)))
                       (= :a/timeout-config   (:rf.sub/id (:tags ev)))
                       (= [:a/timeout-config] (:rf.sub/query-v (:tags ev)))))
                @traces)
          ":scheduled trace emitted with :delay-source :sub + canonical :rf.sub/id + :rf.sub/query-v")
      (rf.trace.tooling/unregister-listener! ::sub))))
