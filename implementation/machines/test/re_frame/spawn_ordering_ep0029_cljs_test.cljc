(ns re-frame.spawn-ordering-ep0029-cljs-test
  "Two `:spawn`s crossed by ONE entry cascade allocate shallowest-first, and
  each child's `:data` fn sees the parent's post-action `:data` (Spec 005
  §Spawn lifecycle — ordering, EP-0029 §A7)."
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

(deftest both-spawns-see-post-action-data-deterministically
  (testing "the outer spawn is #1, the inner #2, and both :data fns see the action's :endpoint"
    (rf/reg-machine :ord3/leaf
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :ord3/parent
      {:initial :idle
       :data    {:base "https://api.example.com"}
       :actions {:assemble (fn [{data :data}]
                             {:data (assoc data :endpoint (str (:base data) "/v1/me"))})}
       :states
       {:idle  {:on {:go {:target :outer :action :assemble}}}
        :outer {:spawn   {:machine-id :ord3/leaf :id-prefix :ord3/leaf
                          :data (fn [{snap :snapshot}]
                                  {:url (-> snap :data :endpoint) :who :outer})}
                :initial :inner
                :states  {:inner {:spawn {:machine-id :ord3/leaf :id-prefix :ord3/leaf
                                          :data (fn [{snap :snapshot}]
                                                  {:url (-> snap :data :endpoint) :who :inner})}}}}}})
    (rf/dispatch-sync [:ord3/parent [:go]])
    (let [spawned (fn [invoke-id]
                    (get-in (rf.machines.test-support/runtime-db)
                            [:rf.runtime/machines :spawned :ord3/parent invoke-id]))
          url     "https://api.example.com/v1/me"]
      (is (= [:ord3/leaf#1 :ord3/leaf#2] [(spawned [:outer]) (spawned [:outer :inner])]))
      (is (= [{:url url :who :outer} {:url url :who :inner}]
             (mapv #(select-keys (rf.machines.test-support/machine-data %) [:url :who])
                   [:ord3/leaf#1 :ord3/leaf#2]))))))
