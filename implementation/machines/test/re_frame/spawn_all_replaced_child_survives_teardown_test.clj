(ns re-frame.spawn-all-replaced-child-survives-teardown-test
  "A `:spawn-all` join's teardown leaves a hand-spawned replacement at a join
  child's address live and untouched, and prunes the stale join slot (Spec 005
  §Spawn-and-join). Parent destroy and leaving the joining state share the
  per-child ownership check this pins."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- spawned-root [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id]))

(deftest leaving-the-join-state-leaves-a-replaced-join-child-live
  (let [log (atom [])]
    (rf/reg-machine :sjx/kid
      {:initial :w
       :states  {:w {:exit (fn [_] (swap! log conj :exit) {})}}})
    (rf/reg-machine :sjx/parent
      {:initial :idle
       :states  {:idle    {:on {:go :forking}}
                 :forking {:spawn-all {:children        [{:id :k :machine-id :sjx/kid}]
                                       :join            :all
                                       :on-all-complete [:all/done]}
                           :on        {:stop :idle}}}})
    (rf/reg-event :sjx/squat
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :sjx/kid :fixed-actor-id :sjx/kid#1}]]}))
    (rf/dispatch-sync [:sjx/parent [:go]])
    (rf/dispatch-sync [:sjx/squat])
    (is (= :sjx/kid#1 (get-in (spawned-root :sjx/parent) [[:forking] :children :k]))
        "the join state still names the replaced address")
    (reset! log [])
    (rf/dispatch-sync [:sjx/parent [:stop]])
    (is (= [true [] nil]
           [(some? (rf.machines.test-support/snapshot :sjx/kid#1)) @log (spawned-root :sjx/parent)])
        "the replacement is live, its :exit did not run, and the stale join slot is pruned")))
