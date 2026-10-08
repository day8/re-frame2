(ns re-frame.spawn-carrier-currency-cljs-test
  "A single-`:spawn` completion carrier is delivered only while the SAME spawn
  attempt is current; a stale one gets no `:on-done` fold, no `:on-error`, no
  ancestor or root `:on`, and one `:rf.machine.spawn/stale-completion` trace
  (Spec 005 §Stale suppression). Every carrier is runtime-minted, queued behind
  the parent's own events by one plain handler."
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

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registry-slot [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- stale-traces []
  (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion))

(defn- stale-reasons []
  (mapv #(get-in % [:tags :rf.reply/stale-reason]) (stale-traces)))

(defn- reg-kick!
  "A plain handler that queues `events` at the back of the router queue, in order."
  []
  (rf/reg-event ::kick (fn [_ [_ events]] {:fx (mapv (fn [e] [:dispatch e]) events)})))

(defn- reg-child!
  "`:go <v>` finishes on a plain final leaf whose result is `v`; `:boom`
  finishes on an `:error?` leaf."
  [child-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {:id "first"}
     :states  {:running {:on {:go   {:target :done
                                     :action (fn [{:keys [data event]}]
                                               {:data (cond-> data
                                                        (some? (second event))
                                                        (assoc :id (second event)))})}
                              :boom :failed}}
               :done    {:final? true :output-key :id}
               :failed  {:final? true :error? true :output-key :id}}}))

(defn- conj-result [{:keys [data result]}]
  (update data :results (fnil conj []) result))

(defn- reg-done-parent!
  [parent-id child-id spawn-extra]
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:start :loading}}
               :loading {:spawn (merge {:machine-id child-id :on-done conj-result}
                                       spawn-extra)
                         :on    {:cancel :idle}}}}))

;; ---- done carrier ----------------------------------------------------------

(deftest done-carrier-after-exit-does-not-fold
  (reg-kick!)
  (reg-child! :scc1/child)
  (reg-done-parent! :scc1/parent :scc1/child {})
  (rf/dispatch-sync [:scc1/parent [:start]])
  (rf/dispatch-sync [::kick [[:scc1/child#1 [:go]] [:scc1/parent [:cancel]]]])
  (is (= [:idle nil] ((juxt :state (comp :results :data)) (snapshot :scc1/parent))))
  (is (= [{:rf.reply/stale-reason  :rf.machine.spawn/state-exited
           :kind                   :done
           :rf.reply/status        :stale
           :rf.reply/work-status   :suppressed
           :invoke-id              [:loading]
           :actor-id               :scc1/parent}]
         (mapv #(select-keys (:tags %) [:rf.reply/stale-reason :kind :rf.reply/status
                                        :rf.reply/work-status :invoke-id :actor-id])
               (stale-traces)))))

(deftest done-carrier-after-reentry-does-not-fold-into-the-new-attempt
  (reg-kick!)
  (reg-child! :scc2/child)
  (reg-done-parent! :scc2/parent :scc2/child {})
  (rf/dispatch-sync [:scc2/parent [:start]])
  (rf/dispatch-sync [::kick [[:scc2/child#1 [:go]]
                             [:scc2/parent [:cancel]]
                             [:scc2/parent [:start]]]])
  (is (= [:loading :scc2/child#2 true nil]
         [(:state (snapshot :scc2/parent))
          (registry-slot :scc2/parent [:loading])
          (some? (snapshot :scc2/child#2))
          (get-in (snapshot :scc2/parent) [:data :results])]))
  (is (= [[:rf.machine.spawn/attempt-superseded {:carried 1 :current 2}]]
         (mapv #(vector (get-in % [:tags :rf.reply/stale-reason])
                        (get-in % [:tags :rf.reply/correlation :attempt]))
               (stale-traces)))))

(deftest done-carrier-after-fixed-id-reentry-does-not-fold
  ;; At a :fixed-actor-id the old and new child share one address, so only the
  ;; attempt can tell them apart.
  (reg-kick!)
  (reg-child! :scc3/child)
  (reg-done-parent! :scc3/parent :scc3/child {:fixed-actor-id :scc3/kid})
  (rf/dispatch-sync [:scc3/parent [:start]])
  (rf/dispatch-sync [::kick [[:scc3/kid [:go]]
                             [:scc3/parent [:cancel]]
                             [:scc3/parent [:start]]]])
  (is (= [:loading true nil 1]
         [(:state (snapshot :scc3/parent))
          (some? (snapshot :scc3/kid))
          (get-in (snapshot :scc3/parent) [:data :results])
          (count (stale-traces))])))

(deftest descendant-move-keeps-the-carrier-current
  ;; Control: a move INSIDE the spawning compound neither exits nor re-enters it.
  (reg-kick!)
  (reg-child! :scc5/child)
  (rf/reg-machine :scc5/parent
    {:initial :idle
     :data    {}
     :states  {:idle {:on {:start :p}}
               :p    {:spawn   {:machine-id :scc5/child :on-done conj-result}
                      :initial :a
                      :states  {:a {:on {:next :b}}
                                :b {}}}}})
  (rf/dispatch-sync [:scc5/parent [:start]])
  (rf/dispatch-sync [::kick [[:scc5/child#1 [:go]] [:scc5/parent [:next]]]])
  (is (= [[:p :b] ["first"] []]
         [(:state (snapshot :scc5/parent))
          (get-in (snapshot :scc5/parent) [:data :results])
          (stale-traces)])))

;; ---- error carrier ---------------------------------------------------------

(defn- reg-error-parent!
  "`:working` counts its entries in `:entered`; `:retry` re-enters it."
  [parent-id child-id root-on]
  (rf/reg-machine parent-id
    (cond-> {:initial :idle
             :data    {}
             :states  {:idle    {:on {:start :working}}
                       :working {:entry (fn [{:keys [data]}]
                                          {:data (update data :entered (fnil inc 0))})
                                 :spawn {:machine-id child-id :on-error :errored}
                                 :on    {:cancel :idle
                                         :retry  {:target :working :reenter? true}}}
                       :errored {}
                       :caught  {}}}
      root-on (assoc :on root-on))))

(deftest error-carrier-behind-a-retry-does-not-undo-it
  (reg-kick!)
  (reg-child! :sce1/child)
  (reg-error-parent! :sce1/parent :sce1/child nil)
  (rf/dispatch-sync [:sce1/parent [:start]])
  (rf/dispatch-sync [::kick [[:sce1/child#1 [:boom]] [:sce1/parent [:retry]]]])
  (is (= [:working 2 :sce1/child#2 true]
         [(:state (snapshot :sce1/parent))
          (get-in (snapshot :sce1/parent) [:data :entered])
          (registry-slot :sce1/parent [:working])
          (some? (snapshot :sce1/child#2))]))
  (is (= [[:error :rf.machine.spawn/attempt-superseded]]
         (mapv #(vector (get-in % [:tags :kind]) (get-in % [:tags :rf.reply/stale-reason]))
               (stale-traces)))))

(deftest error-carrier-after-exit-reaches-no-escape-hatch
  ;; Neither :on-error nor the root :on {:rf.machine.spawn/error …} may run.
  (reg-kick!)
  (reg-child! :sce2/child)
  (reg-error-parent! :sce2/parent :sce2/child {:rf.machine.spawn/error :caught})
  (rf/dispatch-sync [:sce2/parent [:start]])
  (rf/dispatch-sync [::kick [[:sce2/child#1 [:boom]] [:sce2/parent [:cancel]]]])
  (is (= [:idle [:rf.machine.spawn/state-exited]]
         [(:state (snapshot :sce2/parent)) (stale-reasons)])))

(deftest error-carrier-from-an-action-exception-is-gated-too
  (reg-kick!)
  (rf/reg-machine :sce3/child
    {:initial :running
     :data    {}
     :states  {:running {:on {:explode {:target :running
                                        :action (fn [_] (throw (ex-info "kaboom" {})))}}}}})
  (reg-error-parent! :sce3/parent :sce3/child nil)
  (rf/dispatch-sync [:sce3/parent [:start]])
  (rf/dispatch-sync [::kick [[:sce3/child#1 [:explode]] [:sce3/parent [:retry]]]])
  (is (= [:working true [:rf.machine.spawn/attempt-superseded]]
         [(:state (snapshot :sce3/parent))
          (some? (snapshot :sce3/child#2))
          (stale-reasons)])))
