(ns re-frame.spawn-reentry-mirror-cljs-test
  "Exiting and re-entering a `:spawn`-bearing state in ONE
  macrostep leaves the parent's `[:data :rf/spawned <invoke-id>]` mirror naming
  the NEW child, exactly as the runtime registry slot does.

  The transition reducer commits the parent with the mirror already re-pointed
  at the successor; the fx drain then runs the OLD child's tracked destroy
  before the successor's spawn. A teardown that cleared the mirror by
  invoke-id regardless of what it named would delete the successor's id, and
  a spawn that re-populated only the registry slot would leave a live child
  the parent could no longer name (Spec 005 — the mirror 'mirrors the
  runtime registry slot', and exists so an action can read its child's id).
  The teardown clears slot and mirror together, so the spawn install (and the
  `:spawn-all` seed) writes them together too.

  The file is named `*-cljs-test.cljc` so it is discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (the `cljs-test$` ns-regexp)."
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
  "A parent whose `:retry` exits `:loading` and whose `:bounce` `:always`
  re-enters it — exit + re-entry of the spawning state in ONE macrostep."
  [parent-kw loading-node]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle    {:on {:start :loading}}
               :loading (assoc loading-node :on {:retry :bounce})
               :bounce  {:always :loading}}}))

(deftest reentered-spawn-keeps-the-mirror-on-the-successor
  (testing "a generated-address :spawn child re-spawned by exit + re-entry in
            one macrostep is named by BOTH the registry slot and the mirror"
    (rf/reg-machine :srm/child idle-child)
    (reg-bouncing-parent! :srm/parent {:spawn {:machine-id :srm/child}})
    (rf/dispatch-sync [:srm/parent [:start]])
    (is (= :srm/child#1 (registry-slot :srm/parent [:loading])))
    (is (= :srm/child#1 (mirror :srm/parent [:loading])) "control: they agree")

    (rf/dispatch-sync [:srm/parent [:retry]])
    (is (= :loading (:state (snapshot :srm/parent))))
    (is (nil? (snapshot :srm/child#1)) "the old child was torn down")
    (is (some? (snapshot :srm/child#2)) "the successor is live")
    (is (= :srm/child#2 (registry-slot :srm/parent [:loading])))
    (is (= :srm/child#2 (mirror :srm/parent [:loading]))
        "the parent can still name its live child")))

(deftest reentered-fixed-address-spawn-keeps-the-mirror
  (testing "the same holds at a :fixed-actor-id, where the old and new child
            share one address"
    (rf/reg-machine :srm2/child idle-child)
    (reg-bouncing-parent! :srm2/parent {:spawn {:machine-id     :srm2/child
                                                :fixed-actor-id :srm2/kid}})
    (rf/dispatch-sync [:srm2/parent [:start]])
    (is (= :srm2/kid (mirror :srm2/parent [:loading])) "control")
    (rf/dispatch-sync [:srm2/parent [:retry]])
    (is (some? (snapshot :srm2/kid)) "the successor is live")
    (is (= :srm2/kid (registry-slot :srm2/parent [:loading])))
    (is (= :srm2/kid (mirror :srm2/parent [:loading]))
        "the parent can still name its live child")))

(deftest reentered-spawn-all-keeps-the-children-mirror
  (testing "a :spawn-all state exited and re-entered in one macrostep keeps
            the parent's children-map mirror naming the successor batch"
    (rf/reg-machine :srm3/child idle-child)
    (reg-bouncing-parent! :srm3/parent
      {:spawn-all {:children [{:id :a :machine-id :srm3/child}
                              {:id :b :machine-id :srm3/child}]
                   :on-all-complete [:srm3/all-done]}})
    (rf/dispatch-sync [:srm3/parent [:start]])
    (is (= {:a :srm3/child#1 :b :srm3/child#2} (mirror :srm3/parent [:loading])) "control")
    (rf/dispatch-sync [:srm3/parent [:retry]])
    (is (= {:a :srm3/child#3 :b :srm3/child#4}
           (:children (registry-slot :srm3/parent [:loading]))))
    (is (= {:a :srm3/child#3 :b :srm3/child#4} (mirror :srm3/parent [:loading]))
        "the parent can still name its live children")))

(deftest reentered-fixed-address-spawn-all-keeps-the-children-mirror
  (testing "and for :spawn-all children at :fixed-actor-id addresses, where the
            old and new batch share one children map"
    (rf/reg-machine :srm5/child idle-child)
    (reg-bouncing-parent! :srm5/parent
      {:spawn-all {:children [{:id :a :machine-id :srm5/child :fixed-actor-id :srm5/ka}
                              {:id :b :machine-id :srm5/child :fixed-actor-id :srm5/kb}]
                   :on-all-complete [:srm5/all-done]}})
    (rf/dispatch-sync [:srm5/parent [:start]])
    (is (= {:a :srm5/ka :b :srm5/kb} (mirror :srm5/parent [:loading])) "control")
    (rf/dispatch-sync [:srm5/parent [:retry]])
    (is (some? (snapshot :srm5/ka)) "the successor batch is live")
    (is (= {:a :srm5/ka :b :srm5/kb}
           (:children (registry-slot :srm5/parent [:loading]))))
    (is (= {:a :srm5/ka :b :srm5/kb} (mirror :srm5/parent [:loading]))
        "the parent can still name its live children")))

(deftest plain-exit-still-clears-the-mirror
  (testing "control: exiting the spawning state WITHOUT re-entry still clears
            both the registry slot and the mirror"
    (rf/reg-machine :srm4/child idle-child)
    (rf/reg-machine :srm4/parent
      {:initial :idle
       :states  {:idle    {:on {:start :loading}}
                 :loading {:spawn {:machine-id :srm4/child}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:srm4/parent [:start]])
    (is (= :srm4/child#1 (mirror :srm4/parent [:loading])))
    (rf/dispatch-sync [:srm4/parent [:stop]])
    (is (nil? (snapshot :srm4/child#1)))
    (is (nil? (registry-slot :srm4/parent [:loading])))
    (is (nil? (get-in (snapshot :srm4/parent) [:data :rf/spawned]))
        "the emptied mirror map is pruned")))
