(ns re-frame.spawn-all-stale-parent-cljs-test
  "A `:spawn-all` join child reaching `:final?` while its parent is not live (a
  restored frame value holding the child but not the parent) completes `:stale`
  and mints no carrier, so a singleton parent is not lazily re-created. The
  liveness gate reads the child's join membership, since a join child carries
  no public `:rf/invoke-id`. The single-`:spawn` analogue is in
  `machine_reply_lowering_test.clj`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest join-child-finishing-without-a-live-singleton-parent-is-stale
  (rf/reg-machine :sap/child
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/reg-machine :sap/parent
    {:initial :idle
     :states  {:idle      {:on {:start :hydrating}}
               :hydrating {:spawn-all {:children         [{:id :a :machine-id :sap/child}
                                                         {:id :b :machine-id :sap/child}]
                                       :join             :any
                                       :on-some-complete [:hydrate/done]}
                           :on        {:hydrate/done :ready}}
               :ready     {}}})
  (rf/dispatch-sync [:sap/parent [:start]])
  ;; Remove the parent's snapshot and nothing else, as installing a restored
  ;; value that holds its children but not it would.
  (rf.frame/swap-runtime-db! :rf/default
                             #(update-in % [:rf.runtime/machines :snapshots] dissoc :sap/parent))
  (rf/dispatch-sync [:sap/child#1 [:go]])
  (is (= [nil nil] [(snapshot :sap/child#1) (snapshot :sap/parent)])
      "the finishing child auto-destroyed, and no carrier lazily re-created the singleton parent")
  (is (= {:rf.reply/status       :stale
          :rf.reply/work-status  :suppressed
          :rf.reply/stale-reason :rf.machine/actor-not-live}
         (-> (filter #(= :sap/child#1 (get-in % [:tags :actor-id]))
                     (rf.machines.test-support/events-of :rf.machine/done))
             first
             :tags
             (select-keys [:rf.reply/status :rf.reply/work-status :rf.reply/stale-reason])))))
