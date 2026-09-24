(ns re-frame.spawn-on-done-transition-cljs-test
  "`:spawn :on-done` takes two forms, told apart by the value.

    - A fn is the `:data` fold `(fn [{:keys [data result]}] new-data)`, run
      against the parent's `:data` when the child completes. The parent then
      advances, if at all, through an explicit `:on {:rf.machine.spawn/done …}`
      arm or an `:always` over the folded `:data`.
    - A keyword target, vector-path target, single transition map or guarded
      candidate vector is a TRANSITION (XState's `invoke.onDone`), resolved
      at the spawning state's own level exactly as `:spawn :on-error` is. The
      completion carrier `[:rf.machine.spawn/done <invoke-id> <completion>
      <attempt>]` rides the transition's `:event`, so an action reads the
      child's result as `(:result (nth ev 2))`. When no candidate passes its
      guard, the carrier falls through to an explicit
      `:on {:rf.machine.spawn/done …}` arm.

  Registration refuses any other `:spawn :on-done` value with
  `:rf.error/machine-bad-on-done-clause`, and refuses a `:spawn-all` child's
  `:on-done` unless it is a fn: a join child's `:on-done` is a fold, and the
  join's own events own control flow. A transition-shaped `:on-done` is held
  to the same target, guard / action reference and transition-key checks as
  every other transition slot.

  Named `*-cljs-test.cljc` so both the JVM runner and shadow-cljs's
  `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- child-of
  "The child the parent's registry slot at `invoke-id` names."
  [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- refusal
  "Register `definition` under `machine-id` and return the refusal's ex-data,
  or nil when it registers."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch #?(:clj Exception :cljs :default) e
         (or (ex-data e) {:rf.error/id ::no-ex-data}))))

(defn- refusal-id [machine-id definition]
  (:rf.error/id (refusal machine-id definition)))

(defn- reg-child!
  "`:ok <v>` finishes on a plain `:final?` leaf whose result is `v`."
  [child-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:ok {:target :done
                                   :action (fn [{data :data ev :event}]
                                             {:data (assoc data :token (second ev))})}}}
               :done    {:final? true :output-key :token}}}))

(defn- store-result
  "A transition action storing the child's result, read off the completion
  carrier the transition rides."
  [{data :data ev :event}]
  {:data (assoc data :token (:result (nth ev 2)))})

(defn- login-parent
  "A parent that spawns `child-id` from `:authenticating` with `on-done`.
  `authenticating` merges extra keys onto the spawning state; `states` merges
  extra states."
  ([child-id on-done] (login-parent child-id on-done {} {}))
  ([child-id on-done authenticating states]
   {:initial :idle
    :data    {:token nil}
    :actions {:store store-result}
    :states  (merge {:idle           {:on {:login :authenticating}}
                     :authenticating (merge {:spawn {:machine-id child-id
                                                     :on-done    on-done}}
                                            authenticating)
                     :authenticated  {}}
                    states)}))

(defn- run-login!
  "Register `parent`, enter `:authenticating`, and finish the child with
  `result`. Returns the parent's snapshot."
  [parent-id child-id parent result]
  (reg-child! child-id)
  (rf/reg-machine parent-id parent)
  (rf/dispatch-sync [parent-id [:login]])
  (let [child (child-of parent-id [:authenticating])]
    (is (some? child) "the child spawned")
    (rf/dispatch-sync [child [:ok result]])
    (is (nil? (snapshot child)) "the finished child auto-destroyed"))
  (snapshot parent-id))

(defn- action-exceptions []
  (rf.machines.test-support/events-of :rf.error/machine-action-exception))

;; ---- the transition forms ---------------------------------------------------

(deftest transition-map-on-done-advances-the-parent
  (testing "the XState invoke.onDone shape: the parent moves to the target and
            its named action stores the child's result"
    (let [snap (run-login! :sodt-map/login :sodt-map/auth
                           (login-parent :sodt-map/auth
                                         {:target :authenticated :action :store})
                           "T")]
      (is (= :authenticated (:state snap)))
      (is (= "T" (get-in snap [:data :token])))
      (is (empty? (action-exceptions)) "nothing threw"))))

(deftest keyword-on-done-targets-a-sibling
  (testing "a keyword :on-done is a sibling target of the spawning state"
    (let [snap (run-login! :sodt-kw/login :sodt-kw/auth
                           (login-parent :sodt-kw/auth :authenticated)
                           "T")]
      (is (= :authenticated (:state snap)))
      (is (nil? (get-in snap [:data :token]))
          "a bare target runs no action and folds nothing"))))

(deftest vector-path-on-done-targets-an-absolute-path
  (testing "a vector :on-done is an absolute path, descending into a compound"
    (let [snap (run-login! :sodt-vec/login :sodt-vec/auth
                           (login-parent :sodt-vec/auth [:signed-in :dashboard] {}
                                         {:signed-in {:initial :home
                                                      :states  {:home      {}
                                                                :dashboard {}}}})
                           "T")]
      (is (= [:signed-in :dashboard] (:state snap)))
      (is (empty? (action-exceptions))
          "the vector is never invoked as a :data fold"))))

(defn- result-is [v]
  (fn [{ev :event}] (= v (:result (nth ev 2)))))

(deftest guarded-candidates-take-the-first-passing-one
  (testing "a guarded candidate vector: the first candidate whose guard passes wins"
    (let [on-done [{:guard (result-is "admin") :target :admin :action :store}
                   {:target :authenticated}]
          states  {:admin {}}]
      (is (= :admin (:state (run-login! :sodt-g1/login :sodt-g1/auth
                                        (login-parent :sodt-g1/auth on-done {} states)
                                        "admin")))
          "both candidates pass; the first is taken")
      (is (= :authenticated (:state (run-login! :sodt-g2/login :sodt-g2/auth
                                                (login-parent :sodt-g2/auth on-done {} states)
                                                "guest")))
          "the first guard fails; the unguarded candidate is taken"))))

(deftest all-guards-failing-falls-through-to-an-explicit-arm
  (testing "no candidate passes: the carrier reaches an explicit :on {:rf.machine.spawn/done …}"
    (let [snap (run-login! :sodt-ft/login :sodt-ft/auth
                           (login-parent :sodt-ft/auth
                                         {:guard (result-is "admin") :target :authenticated}
                                         {:on {:rf.machine.spawn/done :fallback}}
                                         {:fallback {}})
                           "guest")]
      (is (= :fallback (:state snap))))))

;; ---- the fold, unchanged ----------------------------------------------------

(defn- fold-token [{:keys [data result]}]
  (assoc data :token result))

(deftest fn-on-done-is-still-a-fold
  (testing "control: a fn :on-done folds :data and moves nothing"
    (let [snap (run-login! :sodt-fold/login :sodt-fold/auth
                           (login-parent :sodt-fold/auth fold-token)
                           "T")]
      (is (= :authenticating (:state snap)))
      (is (= "T" (get-in snap [:data :token]))))))

(deftest fold-plus-explicit-arm-advances
  (testing "control: a fold plus an explicit :on {:rf.machine.spawn/done …} arm"
    (let [snap (run-login! :sodt-fa/login :sodt-fa/auth
                           (login-parent :sodt-fa/auth fold-token
                                         {:on {:rf.machine.spawn/done :authenticated}} {})
                           "T")]
      (is (= :authenticated (:state snap)))
      (is (= "T" (get-in snap [:data :token]))))))

(deftest fold-plus-always-advances
  (testing "control: a fold plus an :always guarded on the folded :data"
    (let [snap (run-login! :sodt-fw/login :sodt-fw/auth
                           (login-parent :sodt-fw/auth fold-token
                                         {:always {:guard  (fn [{data :data}] (some? (:token data)))
                                                   :target :authenticated}}
                                         {})
                           "T")]
      (is (= :authenticated (:state snap)))
      (is (= "T" (get-in snap [:data :token]))))))

;; ---- registration ----------------------------------------------------------

(deftest malformed-on-done-is-refused-at-registration
  (doseq [[label v] [["a string" "oops"]
                     ["a number" 42]
                     ["nil" nil]
                     ["a set" #{:authenticated}]
                     ["an empty vector" []]]]
    (testing (str ":spawn :on-done as " label " is refused")
      (let [data (refusal :sodt-bad/login (login-parent :sodt-bad/auth v))]
        (is (= :rf.error/machine-bad-on-done-clause (:rf.error/id data)))
        (is (= :authenticating (:state data)) "the ex-data names the spawning state")))))

(deftest every-admitted-form-registers
  (doseq [[label v] [["a fn" fold-token]
                     ["a keyword" :authenticated]
                     ["a vector path" [:authenticated]]
                     ["a transition map" {:target :authenticated :action :store}]
                     ["a candidate vector" [{:guard (result-is "x") :target :authenticated}
                                            {:target :authenticated}]]]]
    (testing (str "control: :spawn :on-done as " label " registers")
      (is (nil? (refusal :sodt-ok/login (login-parent :sodt-ok/auth v)))))))

(defn- spawn-all-parent [child-on-done]
  {:initial :idle
   :states  {:idle    {:on {:go :joining}}
             :joining {:spawn-all {:children        [{:id         :c1
                                                      :machine-id :sodt/any
                                                      :on-done    child-on-done}]
                                   :on-all-complete [:all-done]}}}})

(deftest spawn-all-child-on-done-must-be-a-fold
  (doseq [[label v] [["a transition map" {:target :idle}]
                     ["a keyword" :idle]]]
    (testing (str "a :spawn-all child's :on-done as " label " is refused")
      (is (= :rf.error/machine-bad-on-done-clause
             (refusal-id :sodt-sa/parent (spawn-all-parent v))))))
  (testing "control: a fn :spawn-all child :on-done registers"
    (is (nil? (refusal :sodt-sa/parent (spawn-all-parent fold-token))))))

(deftest transition-on-done-is-checked-like-every-transition-slot
  (testing "an unresolved target is refused"
    (is (= :rf.error/machine-unresolved-target
           (refusal-id :sodt-chk/login (login-parent :sodt-chk/auth {:target :nowhere})))))
  (testing "a malformed target is refused"
    (is (= :rf.error/machine-bad-target
           (refusal-id :sodt-chk/login (login-parent :sodt-chk/auth {:target 42})))))
  (testing "a dangling guard reference is refused"
    (is (= :rf.error/machine-unresolved-guard
           (refusal-id :sodt-chk/login (login-parent :sodt-chk/auth
                                                     {:target :authenticated
                                                      :guard  :no-such-guard})))))
  (testing "a dangling action reference is refused"
    (is (= :rf.error/machine-unresolved-action
           (refusal-id :sodt-chk/login (login-parent :sodt-chk/auth
                                                     {:target :authenticated
                                                      :action :no-such-action})))))
  (testing "a misspelt transition key is refused, naming the slot"
    (let [data (refusal :sodt-chk/login (login-parent :sodt-chk/auth {:targt :authenticated}))]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id data)))
      (is (= :spawn/on-done (:slot data)))
      (is (= [:targt] (:offending-keys data))))))

;; ---- parallel regions ------------------------------------------------------

(deftest region-on-done-transition-is-region-scoped
  (testing "a region's :spawn :on-done moves that region and leaves its sibling alone"
    (reg-child! :sodt-r/auth)
    (rf/reg-machine :sodt-r/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :sodt-r/auth
                                                      :on-done    :ready}}
                                    :ready   {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:sodt-r/parent [:rf.machine.spawn/spawned]])
    (let [child (child-of :sodt-r/parent [:loader :working])]
      (is (some? child) "the region's child spawned")
      (rf/dispatch-sync [child [:ok "T"]])
      (is (= {:loader :ready :other :idle} (:state (snapshot :sodt-r/parent)))))))

(deftest region-on-done-guard-reads-the-region-relative-invoke-id
  (testing "the carrier a region's :on-done guard reads names the in-region path"
    (reg-child! :sodt-ri/auth)
    (rf/reg-machine :sodt-ri/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :sodt-ri/auth
                                                      :on-done    {:guard  (fn [{ev :event}]
                                                                             (= [:working] (nth ev 1)))
                                                                   :target :ready}}}
                                    :ready   {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:sodt-ri/parent [:rf.machine.spawn/spawned]])
    (rf/dispatch-sync [(child-of :sodt-ri/parent [:loader :working]) [:ok "T"]])
    (is (= :ready (get-in (snapshot :sodt-ri/parent) [:state :loader])))))

(deftest foreign-region-declines-the-spawn-on-done-arm
  (testing "a carrier addressed to region :a never fires region :b's :spawn :on-done,
            even where :b declares a spawn at the same in-region path"
    ;; Registration refuses two regions spawning at one in-region path; the
    ;; pure transition takes the definition as given, so this reaches the
    ;; resolver's region decline directly.
    (let [region (fn [target]
                   {:initial :loading
                    :states  {:loading {:spawn {:machine-id :sodt/any :on-done target}}
                              :a-done  {}
                              :b-done  {}}})
          r      (rf.machines/machine-transition
                   {:type    :parallel
                    :data    {}
                    :regions {:a (region :a-done)
                              :b (region :b-done)}}
                   {:state {:a :loading :b :loading} :data {}}
                   [:rf.machine.spawn/done [:a :loading] {:result "T" :error? false} 1])]
      (is (= :ok (:status r)))
      (is (= {:a :a-done :b :loading} (get-in r [:snapshot :state]))))))

;; ---- stale carriers --------------------------------------------------------

(defn- reg-kick!
  "A plain handler that queues `events` at the back of the router queue, in
  order, so the runtime mints the child's carrier behind them."
  []
  (rf/reg-event ::kick (fn [_ [_ events]] {:fx (mapv (fn [e] [:dispatch e]) events)})))

(defn- reg-stale-parent! [parent-id child-id]
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:start :loading}}
               :loading {:spawn {:machine-id child-id :on-done :loaded}
                         :on    {:cancel :idle}}
               :loaded  {}}}))

(deftest stale-carrier-after-exit-takes-no-transition
  (testing "the parent leaves the spawning state before the carrier arrives"
    (reg-kick!)
    (reg-child! :sodt-s1/auth)
    (reg-stale-parent! :sodt-s1/parent :sodt-s1/auth)
    (rf/dispatch-sync [:sodt-s1/parent [:start]])
    (rf/dispatch-sync [::kick [[:sodt-s1/auth#1 [:ok "T"]] [:sodt-s1/parent [:cancel]]]])
    (is (= :idle (:state (snapshot :sodt-s1/parent))))
    (is (= 1 (count (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion))))))

(deftest stale-carrier-after-reentry-takes-no-transition
  (testing "the parent leaves and re-enters the spawning state before the carrier arrives"
    (reg-kick!)
    (reg-child! :sodt-s2/auth)
    (reg-stale-parent! :sodt-s2/parent :sodt-s2/auth)
    (rf/dispatch-sync [:sodt-s2/parent [:start]])
    (rf/dispatch-sync [::kick [[:sodt-s2/auth#1 [:ok "T"]]
                               [:sodt-s2/parent [:cancel]]
                               [:sodt-s2/parent [:start]]]])
    (is (= :loading (:state (snapshot :sodt-s2/parent)))
        "child #1's completion does not advance attempt #2")
    (is (some? (snapshot :sodt-s2/auth#2)) "the replacement child is live")))

(deftest current-carrier-takes-the-transition
  (testing "control: nothing queued ahead of the carrier"
    (reg-kick!)
    (reg-child! :sodt-s0/auth)
    (reg-stale-parent! :sodt-s0/parent :sodt-s0/auth)
    (rf/dispatch-sync [:sodt-s0/parent [:start]])
    (rf/dispatch-sync [::kick [[:sodt-s0/auth#1 [:ok "T"]]]])
    (is (= :loaded (:state (snapshot :sodt-s0/parent))))))

;; ---- recorded facts --------------------------------------------------------

(def ^:private rolled-six
  {:rf.cofx/requires [:test/roll8]
   :fn (fn [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))})

(defn- ensured-ids [m snap event]
  (set (map :id (rf.machines.cofx-attach/ensure-set-for
                  (rf.machines.cofx-attach/index-ensure-sets m) snap event))))

(deftest spawn-on-done-guard-fact-is-ensured
  (rf/reg-cofx :test/roll8 {:recordable? true} (fn [] 6))
  (let [snap {:state :working :data {}}
        ev   [:rf.machine.spawn/done [:working] {:result 1 :error? false}]]
    (testing "CASE: the guard on the spawning state's transition :spawn :on-done"
      (is (contains? (ensured-ids {:initial :working :guards {:g rolled-six}
                                   :states  {:working {:spawn {:machine-id :x/child
                                                               :on-done {:target :loaded :guard :g}}}
                                             :loaded  {}}}
                                  snap ev)
                     :test/roll8)))
    (testing "CONTROL: the same guard on the explicit :on {:rf.machine.spawn/done …} arm"
      (is (contains? (ensured-ids {:initial :working :guards {:g rolled-six}
                                   :states  {:working {:spawn {:machine-id :x/child}
                                                       :on    {:rf.machine.spawn/done
                                                               {:target :loaded :guard :g}}}
                                             :loaded  {}}}
                                  snap ev)
                     :test/roll8)))))
