(ns re-frame.spawn-all-stale-parent-cljs-test
  "A `:spawn-all` join child reaching `:final?` while its parent is not live (a
  restored frame value holding the child but not the parent) completes `:stale`
  and mints no carrier: a singleton parent is not lazily re-created, and a
  spawned parent's dead address raises no `:rf.error/no-such-handler`. The
  single-`:spawn` analogue is in `machine_reply_lowering_test.clj`."
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

(defn- reg-child! [child-kw]
  (rf/reg-machine child-kw
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}}))

(defn- hydrating [child-kw]
  {:spawn-all {:children         [{:id :a :machine-id child-kw}
                                  {:id :b :machine-id child-kw}]
               :join             :any
               :on-some-complete [:hydrate/done]}})

(defn- done-tags-for [actor-id]
  (some #(when (= actor-id (get-in % [:tags :actor-id])) (:tags %))
        (rf.machines.test-support/events-of :rf.machine/done)))

(defn- drop-instance!
  "Remove `actor-id`'s snapshot and nothing else, as installing a restored value
  that holds its children but not it would."
  [actor-id]
  (rf.frame/swap-runtime-db! :rf/default
                             #(update-in % [:rf.runtime/machines :snapshots] dissoc actor-id)))

(deftest join-child-finishing-without-a-live-singleton-parent-is-stale
  (reg-child! :sap/child)
  (rf/reg-machine :sap/parent
    {:initial :idle
     :states  {:idle      {:on {:start :hydrating}}
               :hydrating (assoc (hydrating :sap/child) :on {:hydrate/done :ready})
               :ready     {}}})
  (rf/dispatch-sync [:sap/parent [:start]])
  (drop-instance! :sap/parent)
  (rf/dispatch-sync [:sap/child#1 [:go]])
  (is (nil? (snapshot :sap/child#1)) "the finishing child auto-destroyed")
  (is (nil? (snapshot :sap/parent)) "no carrier lazily re-created the singleton parent")
  (is (= {:rf.reply/status       :stale
          :rf.reply/work-status  :suppressed
          :rf.reply/stale-reason :rf.machine/actor-not-live}
         (select-keys (done-tags-for :sap/child#1)
                      [:rf.reply/status :rf.reply/work-status :rf.reply/stale-reason]))))

(deftest join-child-finishing-without-a-live-spawned-parent-raises-nothing
  (reg-child! :sap2/child)
  (rf/reg-machine :sap2/parent
    {:initial :idle
     :states  {:idle      {:on {:start :hydrating}}
               :hydrating (hydrating :sap2/child)}})
  (rf/reg-event :sap2/spawn-parent
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :sap2/parent :fixed-actor-id :sap2/p1}]]}))
  (rf/dispatch-sync [:sap2/spawn-parent])
  (rf/dispatch-sync [:sap2/p1 [:start]])
  (drop-instance! :sap2/p1)
  (rf/dispatch-sync [:sap2/child#1 [:go]])
  (is (= :stale (:rf.reply/status (done-tags-for :sap2/child#1))))
  (is (empty? (rf.machines.test-support/events-of :rf.error/no-such-handler))))
