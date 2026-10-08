(ns re-frame.join-resolution-finished-sibling-cljs-test
  "Join resolution cancels only siblings still LIVE in this attempt, never one that
  already finished while its carrier was queued behind the decisive one."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- work-rows-for
  "Every `[<trace-op> <work-status>]` reply row on `spawned-id`'s work-id, in order."
  [spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (when-let [st (:rf.reply/work-status (:tags ev))]
                        [(:operation ev) st]))))
        (rf.machines.test-support/captured-events)))

(deftest a-finished-but-unfolded-sibling-is-not-cancelled
  (rf/reg-machine :jrf/child {:initial :running
                              :states  {:running {:on {:go :done}}
                                        :done    {:final? true}}})
  (rf/reg-machine :jrf/parent
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children         [{:id :a :machine-id :jrf/child :start [:go]}
                                                       {:id :b :machine-id :jrf/child :start [:go]}]
                                    :join             :any
                                    :on-some-complete [:race/won]}}}})
  ;; Both children finish at spawn; :a's carrier resolves the join before :b's lands.
  (rf/dispatch-sync [:jrf/parent [:start]])
  (is (= [[:rf.machine/done :completed]
          [:rf.machine.spawn-all/late-completion :suppressed]]
         (work-rows-for :jrf/child#2))
      "its own finality terminal, then its queued carrier suppressed as late — no cancellation"))
