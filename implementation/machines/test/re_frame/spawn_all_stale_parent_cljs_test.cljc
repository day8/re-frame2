(ns re-frame.spawn-all-stale-parent-cljs-test
  "A `:spawn-all` join child reaching `:final?` while its parent is not live
  is a STALE completion, exactly as a single-`:spawn` child's is.

  A parent's destroy ends the children its `:spawn-all` join tracks, so an
  ordinary destroy never leaves one to finish later. A frame value can still
  hold a join child whose parent is not live — a restored value that carries
  the child but not the parent — and there the finalize cascade must classify
  the completion `:status :stale` and mint NO carrier into the dead parent's
  address. The stale gate reads the child's `:rf/join-child` membership
  record, because a join child carries no public `:rf/invoke-id`. A carrier
  dispatched at the dead address would meet a `reg-machine` singleton
  parent's surviving DEFINITION, whose D5 lazy re-creation would RESURRECT
  the parent from its initial snapshot (its initial `:entry` running again),
  and would raise a spurious `:rf.error/no-such-handler` at a spawned parent's
  address.

  The single-`:spawn` analogue is pinned in `machine_reply_lowering_test.clj`.

  The file is named `*-cljs-test.cljc` so it is discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (the `cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
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
     :data    {:out nil}
     :actions {:record (fn [{d :data ev :event}] {:data (assoc d :out (second ev))})}
     :states  {:running {:on {:go {:target :done :action :record}}}
               :done    {:final? true :output-key :out}}}))

(defn- done-tags-for [actor-id]
  (some #(when (= actor-id (get-in % [:tags :actor-id])) (:tags %))
        (rf.machines.test-support/events-of :rf.machine/done)))

(defn- drop-instance!
  "Remove `actor-id`'s snapshot from the frame value and nothing else, as
  installing a restored value that holds its children but not it would."
  [actor-id]
  (rf.frame/swap-runtime-db! :rf/default
                             #(update-in % [:rf.runtime/machines :snapshots] dissoc actor-id)))

(deftest join-child-finishing-without-a-live-singleton-parent-is-stale
  (testing "a :spawn-all child reaching :final? while its SINGLETON parent is
            not live does not resurrect the parent: no lazy re-creation, no
            second :entry, and the completion is recorded :stale/:suppressed"
    (reg-child! :sap/child)
    (rf/reg-machine :sap/parent
      {:initial :idle
       :data    {:boots 0}
       :actions {:count-boot (fn [{d :data}] {:data (update d :boots inc)})}
       :states  {:idle      {:entry :count-boot
                             :on    {:start :hydrating}}
                 :hydrating {:spawn-all {:children         [{:id :a :machine-id :sap/child}
                                                            {:id :b :machine-id :sap/child}]
                                         :join             :any
                                         :on-some-complete [:hydrate/done]}
                             :on        {:hydrate/done :ready}}
                 :ready     {}}})

    (rf/dispatch-sync [:sap/parent [:start]])
    (is (= :hydrating (:state (snapshot :sap/parent))))
    (is (= 1 (get-in (snapshot :sap/parent) [:data :boots])) "one initial :entry")
    (is (some? (snapshot :sap/child#1)))
    (is (some? (snapshot :sap/child#2)))

    (drop-instance! :sap/parent)
    (is (nil? (snapshot :sap/parent)) "the parent INSTANCE is gone")
    (is (some? (snapshot :sap/child#1)) "its :spawn-all child is still in the frame value")

    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:sap/child#1 [:go :a-result]])

    (is (nil? (snapshot :sap/child#1)) "the finishing child auto-destroyed")
    (is (nil? (snapshot :sap/parent))
        "the destroyed parent was NOT resurrected by the child's completion")
    (is (empty? (rf.machines.test-support/events-of :rf.machine/started))
        "no lazy re-creation of the destroyed parent")
    (let [tags (done-tags-for :sap/child#1)]
      (is (some? tags) ":rf.machine/done fired for the late child")
      (is (= :stale (:rf.reply/status tags))
          "the late join-child completion is classified :stale")
      (is (= :suppressed (:rf.reply/work-status tags)))
      (is (= :rf.machine/actor-not-live (:rf.reply/stale-reason tags))))))

(deftest join-child-finishing-without-a-live-spawned-parent-raises-nothing
  (testing "a :spawn-all child finishing while its SPAWNED parent is not live
            mints no carrier, so no :rf.error/no-such-handler is raised at the
            dead address"
    (reg-child! :sap2/child)
    (rf/reg-machine :sap2/parent
      {:initial :idle
       :states  {:idle      {:on {:start :hydrating}}
                 :hydrating {:spawn-all {:children         [{:id :a :machine-id :sap2/child}
                                                            {:id :b :machine-id :sap2/child}]
                                         :join             :any
                                         :on-some-complete [:hydrate/done]}}}})
    (rf/reg-event :sap2/spawn-parent
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :sap2/parent
                                          :fixed-actor-id :sap2/p1}]]}))

    (rf/dispatch-sync [:sap2/spawn-parent])
    (rf/dispatch-sync [:sap2/p1 [:start]])
    (is (= :hydrating (:state (snapshot :sap2/p1))))
    (drop-instance! :sap2/p1)
    (is (nil? (snapshot :sap2/p1)))
    (is (some? (snapshot :sap2/child#1)) "its :spawn-all child is still in the frame value")

    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:sap2/child#1 [:go :x]])
    (is (nil? (snapshot :sap2/child#1)) "the finishing child auto-destroyed")
    (is (empty? (rf.machines.test-support/events-of :rf.error/no-such-handler))
        "no carrier was dispatched at the dead spawned parent")
    (is (= :stale (:rf.reply/status (done-tags-for :sap2/child#1))))))
