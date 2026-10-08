(ns re-frame.actor-liveness-cljs-test
  "A spawned actor's liveness IS its runtime-db snapshot, never a per-instance
  registrar entry, so reverting runtime-db reverts it (Spec 005 §Liveness is
  derived from runtime-db). The end-to-end `restore-epoch!` case is
  `implementation/epoch`'s actor_revertibility_restore_test."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.registrar :as rf.registrar]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registered-event-ids []
  (set (keys (rf.registrar/registrations :event))))

(defn- counter-child []
  {:initial :live
   :data    {:n 0}
   :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
   :states  {:live {:on {:bump {:action :bump}}}}})

(defn- spawning-parent
  "Spawns one `child-type` actor per `:go`; `:drop` destroys `<child-type>#1`."
  [child-type]
  (let [child-1 (keyword (namespace child-type) (str (name child-type) "#1"))]
    {:initial :idle
     :data    {}
     :states  {:idle {:on {:go   {:action (fn [_]
                                            {:fx [[:rf.machine/spawn
                                                   {:machine-id child-type
                                                    :id-prefix  child-type}]]})}
                           :drop {:action (fn [_] {:fx [[:rf.machine/destroy child-1]]})}}}}}))

(deftest spawn-and-destroy-are-pure-app-db-writes
  (rf/reg-machine :al/child  (counter-child))
  (rf/reg-machine :al/parent (spawning-parent :al/child))
  (let [reg-before (registered-event-ids)]
    (rf/dispatch-sync [:al/parent [:go]])
    (is (= [:al/child reg-before]
           [(:rf/machine-type (snapshot :al/child#1)) (registered-event-ids)])
        "the snapshot carries its TYPE; no per-instance handler was registered")
    (rf/dispatch-sync [:al/parent [:drop]])
    (is (= [nil reg-before] [(snapshot :al/child#1) (registered-event-ids)]))))

(deftest dispatch-to-gone-actor-is-clean-no-such-handler
  (rf/reg-machine :al3/child (counter-child))
  (let [errors (atom [])]
    (rf/register-listener! :trace ::al3 (fn [ev]
                                          (when (= :rf.error/no-such-handler (:operation ev))
                                            (swap! errors conj ev))))
    (rf/dispatch-sync [:al3/child#1 [:bump]])
    (rf/unregister-listener! :trace ::al3)
    (is (seq @errors) "the resolver declined: no live snapshot")
    (is (nil? (snapshot :al3/child#1)) "no snapshot was fabricated")))

(deftest actor-liveness-reverts-with-runtime-db
  (rf/reg-machine :al4/child  (counter-child))
  (rf/reg-machine :al4/parent (spawning-parent :al4/child))
  (rf/dispatch-sync [:al4/parent [:go]])
  (let [db-while-alive (:rf.db/runtime (rf/frame-state-value :rf/default))]
    (rf/dispatch-sync [:al4/parent [:drop]])
    (is (nil? (snapshot :al4/child#1)) "destroyed")
    (rf.frame/swap-runtime-db! :rf/default (constantly db-while-alive))
    (rf/dispatch-sync [:al4/child#1 [:bump]])
    (is (= 1 (get-in (snapshot :al4/child#1) [:data :n]))
        "the reverted snapshot answers its next event: liveness reverted with it")))

(deftest singletons-still-register-and-dispatch-unchanged
  ;; frame-destroy tells a spawned actor from a singleton by `:rf/machine-type`.
  (rf/reg-machine :al5/single (counter-child))
  (rf/dispatch-sync [:al5/single [:bump]])
  (is (= [1 nil] ((juxt (comp :n :data) :rf/machine-type) (snapshot :al5/single)))))

(deftest parallel-spawned-actors-each-resolve-independently
  (rf/reg-machine :al6/child  (counter-child))
  (rf/reg-machine :al6/parent
    {:initial :idle
     :data    {}
     :states  {:idle {:on {:go {:action (fn [_]
                                          {:fx [[:rf.machine/spawn {:machine-id :al6/child :id-prefix :al6/child}]
                                                [:rf.machine/spawn {:machine-id :al6/child :id-prefix :al6/child}]]})}}}}})
  (rf/dispatch-sync [:al6/parent [:go]])
  (rf/dispatch-sync [:al6/child#1 [:bump]])
  (rf/dispatch-sync [:al6/child#1 [:bump]])
  (rf/dispatch-sync [:al6/child#2 [:bump]])
  (is (= [2 1] (mapv #(get-in (snapshot %) [:data :n]) [:al6/child#1 :al6/child#2]))))
