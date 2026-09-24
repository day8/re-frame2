(ns re-frame.spawn-carrier-currency-cljs-test
  "A single-`:spawn` completion carrier is
  delivered only while the SAME spawn attempt is current.

  A child's completion carrier (`[:rf.machine.spawn/done …]`, or
  `[:rf.machine.spawn/error …]` from either failure trigger) is QUEUED. Parent
  events already in the queue can therefore make the parent leave the spawning
  state, or leave and re-enter it with a fresh child, before the carrier
  arrives. Such a carrier is stale (Spec 005 §Stale suppression): no `:on-done`
  fold, no `:on-error`, no ancestor / root `:on` for its event, and one
  `:rf.machine.spawn/stale-completion` trace. Delivered anyway, the done
  carrier would fold into whichever attempt is current and the error carrier
  would fire `:on-error` against the re-entered attempt, destroying its
  healthy child.

  Every row is producer-derived: one plain handler queues the child's
  finishing event and then the parent's own events, so the runtime mints the
  carrier behind them. Nothing is hand-dispatched.

  Named `*-cljs-test.cljc` so both the JVM runner and shadow-cljs's
  `cljs-test$` build discover it."
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

(defn- registry-slot [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- stale-traces []
  (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion))

(defn- reg-kick!
  "A plain handler that queues `events` at the back of the router queue, in
  order — the producer for every race below."
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

(deftest current-carrier-folds
  (testing "control: nothing queued ahead of the carrier — it folds, with the
            registry slot already cleared by the child's own finality"
    (reg-kick!)
    (reg-child! :scc0/child)
    (reg-done-parent! :scc0/parent :scc0/child {})
    (rf/dispatch-sync [:scc0/parent [:start]])
    (rf/dispatch-sync [::kick [[:scc0/child#1 [:go]]]])
    (is (= :loading (:state (snapshot :scc0/parent))))
    (is (= ["first"] (get-in (snapshot :scc0/parent) [:data :results])))
    (is (nil? (registry-slot :scc0/parent [:loading])))
    (is (empty? (stale-traces)))))

(deftest done-carrier-after-exit-does-not-fold
  (testing "the parent's :cancel is queued ahead of the carrier: the carrier
            arrives with the parent resting in :idle and folds nothing"
    (reg-kick!)
    (reg-child! :scc1/child)
    (reg-done-parent! :scc1/parent :scc1/child {})
    (rf/dispatch-sync [:scc1/parent [:start]])
    (rf/dispatch-sync [::kick [[:scc1/child#1 [:go]] [:scc1/parent [:cancel]]]])
    (is (= :idle (:state (snapshot :scc1/parent))))
    (is (nil? (get-in (snapshot :scc1/parent) [:data :results]))
        "no :on-done fold after the spawning state was exited")
    (let [[t & more] (stale-traces)]
      (is (nil? more) "exactly one stale trace")
      (is (= :rf.machine.spawn/state-exited (get-in t [:tags :rf.reply/stale-reason])))
      (is (= :done (get-in t [:tags :kind])))
      (is (= :stale (get-in t [:tags :rf.reply/status])))
      (is (= :suppressed (get-in t [:tags :rf.reply/work-status])))
      (is (= [:loading] (get-in t [:tags :invoke-id])))
      (is (= :scc1/parent (get-in t [:tags :actor-id]))))))

(deftest done-carrier-after-reentry-does-not-fold-into-the-new-attempt
  (testing ":cancel then :start are queued ahead of the carrier: the parent
            re-enters :loading with child#2 running, and child#1's result must
            not be attributed to it"
    (reg-kick!)
    (reg-child! :scc2/child)
    (reg-done-parent! :scc2/parent :scc2/child {})
    (rf/dispatch-sync [:scc2/parent [:start]])
    (rf/dispatch-sync [::kick [[:scc2/child#1 [:go]]
                               [:scc2/parent [:cancel]]
                               [:scc2/parent [:start]]]])
    (is (= :loading (:state (snapshot :scc2/parent))))
    (is (= :scc2/child#2 (registry-slot :scc2/parent [:loading])))
    (is (some? (snapshot :scc2/child#2)) "the replacement is live")
    (is (nil? (get-in (snapshot :scc2/parent) [:data :results]))
        "child#1's result was not folded into attempt #2")
    (is (= [:rf.machine.spawn/attempt-superseded]
           (mapv #(get-in % [:tags :rf.reply/stale-reason]) (stale-traces))))
    (is (= {:carried 1 :current 2}
           (get-in (first (stale-traces)) [:tags :rf.reply/correlation :attempt])))))

(deftest done-carrier-after-fixed-id-reentry-does-not-fold
  (testing "the same race at a :fixed-actor-id, where the old and new child
            share one address — the actor id cannot tell the attempts apart"
    (reg-kick!)
    (reg-child! :scc3/child)
    (reg-done-parent! :scc3/parent :scc3/child {:fixed-actor-id :scc3/kid})
    (rf/dispatch-sync [:scc3/parent [:start]])
    (rf/dispatch-sync [::kick [[:scc3/kid [:go]]
                               [:scc3/parent [:cancel]]
                               [:scc3/parent [:start]]]])
    (is (= :loading (:state (snapshot :scc3/parent))))
    (is (some? (snapshot :scc3/kid)) "the replacement is live at the same address")
    (is (nil? (get-in (snapshot :scc3/parent) [:data :results])))
    (is (= 1 (count (stale-traces))))))

(deftest replacement-that-also-finished-folds-exactly-once
  (testing "child#2 finishes before child#1's carrier arrives: child#1's
            carrier is stale and child#2's folds exactly once"
    (reg-kick!)
    (reg-child! :scc4/child)
    (reg-done-parent! :scc4/parent :scc4/child {})
    (rf/dispatch-sync [:scc4/parent [:start]])
    (rf/dispatch-sync [::kick [[:scc4/child#1 [:go "one"]]
                               [:scc4/parent [:cancel]]
                               [:scc4/parent [:start]]
                               [:scc4/child#2 [:go "two"]]]])
    (is (= :loading (:state (snapshot :scc4/parent))))
    (is (= ["two"] (get-in (snapshot :scc4/parent) [:data :results])))
    (is (= [:rf.machine.spawn/attempt-superseded]
           (mapv #(get-in % [:tags :rf.reply/stale-reason]) (stale-traces))))))

(deftest descendant-move-keeps-the-carrier-current
  (testing "control: a move INSIDE the spawning compound neither exits nor
            re-enters it, so the carrier still folds"
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
    (is (= [:p :b] (:state (snapshot :scc5/parent))))
    (is (= ["first"] (get-in (snapshot :scc5/parent) [:data :results])))
    (is (empty? (stale-traces)))))

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

(deftest current-error-carrier-fires-on-error
  (testing "control: nothing queued ahead of the failure — :on-error fires"
    (reg-kick!)
    (reg-child! :sce0/child)
    (reg-error-parent! :sce0/parent :sce0/child nil)
    (rf/dispatch-sync [:sce0/parent [:start]])
    (rf/dispatch-sync [::kick [[:sce0/child#1 [:boom]]]])
    (is (= :errored (:state (snapshot :sce0/parent))))
    (is (= 1 (get-in (snapshot :sce0/parent) [:data :entered])))
    (is (empty? (stale-traces)))))

(deftest error-carrier-behind-a-retry-does-not-undo-it
  (testing "a :retry queued ahead of child#1's failure re-enters
            :working; the stale failure must not fire :on-error against the
            new attempt or destroy child#2"
    (reg-kick!)
    (reg-child! :sce1/child)
    (reg-error-parent! :sce1/parent :sce1/child nil)
    (rf/dispatch-sync [:sce1/parent [:start]])
    (rf/dispatch-sync [::kick [[:sce1/child#1 [:boom]] [:sce1/parent [:retry]]]])
    (is (= :working (:state (snapshot :sce1/parent))))
    (is (= 2 (get-in (snapshot :sce1/parent) [:data :entered])))
    (is (= :sce1/child#2 (registry-slot :sce1/parent [:working])))
    (is (some? (snapshot :sce1/child#2)) "child#2 is live")
    (let [[t & more] (stale-traces)]
      (is (nil? more))
      (is (= :error (get-in t [:tags :kind])))
      (is (= :rf.machine.spawn/attempt-superseded (get-in t [:tags :rf.reply/stale-reason]))))))

(deftest error-carrier-after-exit-reaches-no-escape-hatch
  (testing "the parent exits before the failure arrives: neither :on-error nor
            the root :on {:rf.machine.spawn/error …} escape hatch may run"
    (reg-kick!)
    (reg-child! :sce2/child)
    (reg-error-parent! :sce2/parent :sce2/child {:rf.machine.spawn/error :caught})
    (rf/dispatch-sync [:sce2/parent [:start]])
    (rf/dispatch-sync [::kick [[:sce2/child#1 [:boom]] [:sce2/parent [:cancel]]]])
    (is (= :idle (:state (snapshot :sce2/parent))))
    (is (= [:rf.machine.spawn/state-exited]
           (mapv #(get-in % [:tags :rf.reply/stale-reason]) (stale-traces))))))

(deftest error-carrier-from-an-action-exception-is-gated-too
  (testing "the action-exception trigger carries the attempt as well: a throw
            queued behind a :retry leaves the re-entered attempt alone"
    (reg-kick!)
    (rf/reg-machine :sce3/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:explode {:target :running
                                          :action (fn [_] (throw (ex-info "kaboom" {})))}}}}})
    (reg-error-parent! :sce3/parent :sce3/child nil)
    (rf/dispatch-sync [:sce3/parent [:start]])
    (rf/dispatch-sync [::kick [[:sce3/child#1 [:explode]] [:sce3/parent [:retry]]]])
    (is (= :working (:state (snapshot :sce3/parent))))
    (is (some? (snapshot :sce3/child#2)) "child#2 is live")
    (is (= [:rf.machine.spawn/attempt-superseded]
           (mapv #(get-in % [:tags :rf.reply/stale-reason]) (stale-traces))))))

;; ---- :spawn-all control ----------------------------------------------------

(defn- reg-spawn-all-parent!
  [parent-id child-id]
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:start :loading}}
               :loading {:spawn-all {:children        [{:id :a :machine-id child-id :on-done conj-result}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:cancel :idle :all/done :loaded}}
               :loaded  {}}}))

(defn- join-child [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:loading] :children :a]))

(deftest spawn-all-carriers-keep-their-own-fence
  (testing "control: a :spawn-all child's carrier carries no single-:spawn
            attempt, so this gate never sees it — with nothing queued it folds
            and the join resolves"
    (reg-kick!)
    (reg-child! :sca0/child)
    (reg-spawn-all-parent! :sca0/parent :sca0/child)
    (rf/dispatch-sync [:sca0/parent [:start]])
    (rf/dispatch-sync [::kick [[(join-child :sca0/parent) [:go]]]])
    (is (= :loaded (:state (snapshot :sca0/parent))))
    (is (= ["first"] (get-in (snapshot :sca0/parent) [:data :results])))
    (is (empty? (stale-traces))))
  (testing "control: behind a :cancel the :spawn-all carrier is dropped by the
            join's own exact-attempt fence"
    (reg-child! :sca1/child)
    (reg-spawn-all-parent! :sca1/parent :sca1/child)
    (rf/dispatch-sync [:sca1/parent [:start]])
    (rf/dispatch-sync [::kick [[(join-child :sca1/parent) [:go]] [:sca1/parent [:cancel]]]])
    (is (= :idle (:state (snapshot :sca1/parent))))
    (is (nil? (get-in (snapshot :sca1/parent) [:data :results])))
    (is (empty? (stale-traces)) "no single-:spawn stale trace for a join carrier")))
