(ns re-frame.spawn-reentry-mirror-cljs-test
  "Exiting and re-entering a `:spawn`-bearing state in ONE macrostep leaves the
  parent's `[:data :rf/spawned <invoke-id>]` mirror naming the NEW child, as the
  registry slot does: the old child's teardown clears both, so the successor's
  install (and the `:spawn-all` seed) writes both."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registry-slot [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- mirror [parent-id invoke-id]
  (get-in (snapshot parent-id) [:data :rf/spawned invoke-id]))

(def ^:private idle-child
  {:initial :waiting
   :states  {:waiting {}}})

(defn- reg-bouncing-parent!
  "`:retry` exits `:loading` and `:bounce`'s `:always` re-enters it, in one macrostep."
  [parent-kw loading-node]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle    {:on {:start :loading}}
               :loading (assoc loading-node :on {:retry :bounce})
               :bounce  {:always :loading}}}))

(deftest reentered-spawn-keeps-the-mirror-on-the-successor
  (rf/reg-machine :srm/child idle-child)
  (reg-bouncing-parent! :srm/parent {:spawn {:machine-id :srm/child}})
  (rf/dispatch-sync [:srm/parent [:start]])
  (rf/dispatch-sync [:srm/parent [:retry]])
  (is (= [nil :srm/child#2 :srm/child#2]
         [(snapshot :srm/child#1) (registry-slot :srm/parent [:loading]) (mirror :srm/parent [:loading])])))

(deftest reentered-spawn-all-keeps-the-children-mirror
  (rf/reg-machine :srm3/child idle-child)
  (reg-bouncing-parent! :srm3/parent
    {:spawn-all {:children [{:id :a :machine-id :srm3/child}
                            {:id :b :machine-id :srm3/child}]
                 :on-all-complete [:srm3/all-done]}})
  (rf/dispatch-sync [:srm3/parent [:start]])
  (rf/dispatch-sync [:srm3/parent [:retry]])
  (let [successors {:a :srm3/child#3 :b :srm3/child#4}]
    (is (= [successors successors]
           [(:children (registry-slot :srm3/parent [:loading])) (mirror :srm3/parent [:loading])]))))

(deftest plain-exit-still-clears-the-mirror
  (testing "control: exiting the spawning state WITHOUT re-entry clears the mirror and prunes the map"
    (rf/reg-machine :srm4/child idle-child)
    (rf/reg-machine :srm4/parent
      {:initial :idle
       :states  {:idle    {:on {:start :loading}}
                 :loading {:spawn {:machine-id :srm4/child}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:srm4/parent [:start]])
    (is (= :srm4/child#1 (mirror :srm4/parent [:loading])))
    (rf/dispatch-sync [:srm4/parent [:stop]])
    (is (= [nil nil] [(registry-slot :srm4/parent [:loading])
                      (get-in (snapshot :srm4/parent) [:data :rf/spawned])]))))
