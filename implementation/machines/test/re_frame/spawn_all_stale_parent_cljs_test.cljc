(ns re-frame.spawn-all-stale-parent-cljs-test
  "rf2-3x7nj.9.1 — a `:spawn-all` join child finishing AFTER its parent was
  destroyed is a STALE completion, exactly as a single-`:spawn` child's is.

  An explicit destroy of a parent resting in a `:spawn-all` state leaves its
  children (and their join slot) alive, per Spec 005's explicit-destroy
  cascade. When one of those children later reaches `:final?`, the finalize
  cascade must classify the completion `:status :stale` and mint NO carrier
  into the dead parent's address. Before the fix the stale gate keyed on the
  child's public `:rf/invoke-id`, which a join child never carries (its
  coordinate lives on its `:rf/join-child` membership record), so the carrier
  was dispatched at the dead address: a `reg-machine` singleton parent's
  surviving DEFINITION answered it with D5 lazy re-creation, RESURRECTING the
  destroyed parent from its initial snapshot (its initial `:entry` ran again),
  and a spawned parent raised a spurious `:rf.error/no-such-handler`.

  The single-`:spawn` analogue is pinned in `machine_reply_lowering_test.clj`.

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

(deftest join-child-finishing-after-singleton-parent-destroy-is-stale
  (testing "a :spawn-all child reaching :final? after its SINGLETON parent was
            destroyed does not resurrect the parent: no lazy re-creation, no
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
    (rf/reg-event :sap/kill-parent
      (fn [_ _] {:fx [[:rf.machine/destroy :sap/parent]]}))

    (rf/dispatch-sync [:sap/parent [:start]])
    (is (= :hydrating (:state (snapshot :sap/parent))))
    (is (= 1 (get-in (snapshot :sap/parent) [:data :boots])) "one initial :entry")
    (is (some? (snapshot :sap/child#1)))
    (is (some? (snapshot :sap/child#2)))

    (rf/dispatch-sync [:sap/kill-parent])
    (is (nil? (snapshot :sap/parent)) "the parent INSTANCE is gone")
    (is (some? (snapshot :sap/child#1))
        "an explicit destroy leaves the :spawn-all children alive")

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

(deftest join-child-finishing-after-spawned-parent-destroy-raises-nothing
  (testing "a :spawn-all child finishing after its SPAWNED parent was destroyed
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
    (rf/reg-event :sap2/kill-parent
      (fn [_ _] {:fx [[:rf.machine/destroy :sap2/p1]]}))

    (rf/dispatch-sync [:sap2/spawn-parent])
    (rf/dispatch-sync [:sap2/p1 [:start]])
    (is (= :hydrating (:state (snapshot :sap2/p1))))
    (rf/dispatch-sync [:sap2/kill-parent])
    (is (nil? (snapshot :sap2/p1)))

    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:sap2/child#1 [:go :x]])
    (is (nil? (snapshot :sap2/child#1)) "the finishing child auto-destroyed")
    (is (empty? (rf.machines.test-support/events-of :rf.error/no-such-handler))
        "no carrier was dispatched at the dead spawned parent")
    (is (= :stale (:rf.reply/status (done-tags-for :sap2/child#1))))))
