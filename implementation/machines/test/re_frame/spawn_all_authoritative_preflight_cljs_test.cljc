(ns re-frame.spawn-all-authoritative-preflight-cljs-test
  "One schema-invalid child in the middle of a `:spawn-all` batch rejects the
  whole invoke: exactly the childless sentinel (no `:rf/prepared` scratch), no
  sibling installed, one spawn-phase schema failure."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.schemas]
   [re-frame.schemas.malli]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(deftest partial-reject-batch-rejects-atomically-with-no-prepared-scratch
  (rf/reg-machine :sa/strict {:initial :running
                              :data    {:n 1}
                              :schemas {:data [:map [:n pos-int?]]}
                              :states  {:running {}}})
  (rf/reg-machine :sa/plain {:initial :running :data {} :states {:running {}}})
  (rf/reg-machine :sup/partial
    {:initial :idle
     :states  {:idle    {:on {:start :forking}}
               :forking {:spawn-all {:children        [{:id :ok  :machine-id :sa/plain}
                                                       {:id :bad :machine-id :sa/strict :data {:n -1}}
                                                       {:id :ok2 :machine-id :sa/plain}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :ready}}
               :ready   {}}})
  (rf/dispatch-sync [:sup/partial [:start]])
  (is (= {:rf/spawn-all-rejected? true}
         (get-in (rf.machines.test-support/runtime-db)
                 [:rf.runtime/machines :spawned :sup/partial [:forking]])))
  (is (= [nil nil nil]
         (mapv rf.machines.test-support/snapshot [:sa/plain#1 :sa/strict#1 :sa/plain#2])))
  (is (= 1 (count (filterv #(= :spawn (get-in % [:tags :phase]))
                           (rf.machines.test-support/events-of :rf.error/schema-validation-failure))))
      "the invalid child was validated once, never again under a suppressed spawn"))
