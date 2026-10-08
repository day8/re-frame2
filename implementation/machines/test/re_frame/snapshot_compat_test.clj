(ns re-frame.snapshot-compat-test
  "Spec 005 §Snapshot shape stability invariants 3 & 4: a snapshot whose
  `:state` the definition cannot occupy, or whose `:rf/snapshot-version`
  disagrees with it, emits the named `:rf.error/*` and restarts from the
  initial state, carrying a spawned actor's identity envelope across."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- seed!
  "Install `snap` as `machine-id`'s live snapshot, as a hot reload or a restore would leave it."
  [machine-id snap]
  (rf.frame/swap-runtime-db! :rf/default assoc-in [:rf.runtime/machines :snapshots machine-id] snap))

(defn- traces! [f]
  (rf.machines.test-support/with-trace-capture seen
    (f)
    @seen))

(defn- ops [evs op] (filter #(= op (:operation %)) evs))

(deftest state-not-in-definition-resets-to-initial
  (testing "a vanished :state emits the error and restarts at :initial — whose :entry fires
            and whose birth reports :cause :reset — before the event runs"
    (let [entered (atom 0)]
      (rf/reg-machine :compat/m1 {:initial :idle
                                  :actions {:enter (fn [_] (swap! entered inc) nil)}
                                  :states  {:idle {:entry :enter :on {:go :next}}
                                            :next {}}})
      (seed! :compat/m1 {:state :gone :data {:user-stuff 42}})
      (let [evs (traces! #(rf/dispatch-sync [:compat/m1 [:go]]))
            ev  (first (ops evs :rf.error/machine-state-not-in-definition))]
        (is (= [{:machine-id :compat/m1 :state :gone} :reset-to-initial]
               [(select-keys (:tags ev) [:machine-id :state]) (:recovery ev)]))
        (is (= 1 @entered))
        (is (= [:reset] (map #(get-in % [:tags :cause]) (ops evs :rf.machine/started))))
        (is (= {:state :next :data {}} (select-keys (snapshot :compat/m1) [:state :data])))))))

(def ^:private two-regions
  {:type    :parallel
   :data    {}
   :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
             :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})

(defn- assert-resets-to-initial
  "Seed `id` (registered as `spec`) with `seeded`, dispatch `event`, and assert the
  state-not-in-definition reset fired and the snapshot restarted, then took `event`."
  [id spec seeded event expected]
  (rf/reg-machine id spec)
  (seed! id seeded)
  (let [ev (first (ops (traces! #(rf/dispatch-sync [id event]))
                       :rf.error/machine-state-not-in-definition))]
    (is (= [(:state seeded) :reset-to-initial] [(get-in ev [:tags :state]) (:recovery ev)]))
    (is (= {:state expected :data {}} (select-keys (snapshot id) [:state :data])))))

;; Parallel needs EXACT region-key parity: a partial map could otherwise read
;; all-final and auto-destroy the machine with a region missing.
(deftest parallel-missing-region-resets-to-initial
  (assert-resets-to-initial :compat/par-missing two-regions
                            {:state {:left :done} :data {:corrupt true}} [:noop] {:left :run :right :run}))

(deftest parallel-extra-region-resets-to-initial
  (assert-resets-to-initial :compat/par-extra two-regions
                            {:state {:left :run :right :run :middle :run} :data {}} [:noop]
                            {:left :run :right :run}))

;; A :type :history node is targetable but never an occupied state.
(deftest occupied-history-pseudo-state-resets-to-initial
  (assert-resets-to-initial :compat/hist
                            {:initial :playing
                             :data    {}
                             :states  {:playing {:type    :compound
                                                 :initial :a
                                                 :states  {:a {:on {:go :b}} :b {} :hist {:type :history}}}}}
                            {:state [:playing :hist] :data {:stale true}} [:go] [:playing :b]))

(deftest snapshot-version-mismatch-resets-and-emits
  (testing "a disagreeing :rf/snapshot-version emits the error and restarts, even though
            :state is still valid"
    (rf/reg-machine :compat/m2 {:initial :idle
                                :meta    {:rf/snapshot-version 2}
                                :states  {:idle {:on {:go :next}} :next {}}})
    (seed! :compat/m2 {:state :idle :data {:legacy true} :meta {:rf/snapshot-version 1}})
    (let [ev (first (ops (traces! #(rf/dispatch-sync [:compat/m2 [:go]]))
                         :rf.error/machine-snapshot-version-mismatch))]
      (is (= [{:machine-id :compat/m2 :version-recorded 1 :version-current 2} :reset-to-initial]
             [(select-keys (:tags ev) [:machine-id :version-recorded :version-current]) (:recovery ev)]))
      (is (= {:state :next :data {}} (select-keys (snapshot :compat/m2) [:state :data]))))))

;; A spawned actor has no registrar entry: `:rf/machine-type` is the only key
;; its handler resolves from, and the lineage keys are how its parent's exit
;; cascade and a `:spawn-all` join address it — so a reset must keep them.

(defn- bumping-child
  "A child type at `version` living in `state`, whose `:bump` increments `:n` from `n`."
  [version state n]
  (cond-> {:initial state
           :data    {:n n}
           :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
           :states  {state {:on {:bump {:action :bump}}}}}
    version (assoc :meta {:rf/snapshot-version version})))

(deftest spawned-actor-survives-version-mismatch-recovery
  (testing "a version reset keeps a spawned actor's identity envelope, so both events after
            the type's hot reload run against the new initial"
    (rf/reg-machine :compat/sc-child (bumping-child 1 :live 0))
    (rf/reg-machine :compat/sc-parent {:initial :idle
                                       :states  {:idle    {:on {:start :working}}
                                                 :working {:spawn {:machine-id :compat/sc-child}}}})
    (rf/dispatch-sync [:compat/sc-parent [:start]])
    (let [actor (get-in (snapshot :compat/sc-parent) [:data :rf/spawned [:working]])]
      ;; `:live` is still in the new definition, isolating the version check.
      (rf/reg-machine :compat/sc-child (bumping-child 2 :live 100))
      (let [evs   (traces! #(do (rf/dispatch-sync [actor [:bump]])
                                (rf/dispatch-sync [actor [:bump]])))
            after (snapshot actor)]
        (is (= 1 (count (ops evs :rf.error/machine-snapshot-version-mismatch))))
        (is (= {:rf/machine-type :compat/sc-child :meta {:rf/snapshot-version 2}}
               (select-keys after [:rf/machine-type :meta])))
        (is (= {:n 102 :rf/self-id actor :rf/parent-id :compat/sc-parent :rf/invoke-id [:working]}
               (select-keys (:data after) [:n :rf/self-id :rf/parent-id :rf/invoke-id])))))))

(deftest spawn-all-child-keeps-join-membership-across-recovery
  (testing "a :spawn-all child's :rf/join-child record survives a reset unchanged, so its
            completion still folds into the join attempt it belongs to"
    (rf/reg-machine :compat/ja-child (bumping-child 1 :live 0))
    (rf/reg-machine :compat/ja-parent
      {:initial :hydrating
       :data    {}
       :states  {:hydrating {:spawn-all
                             {:children        [{:id :a :machine-id :compat/ja-child}]
                              :join            :all
                              :on-all-complete [:go-done]
                              :on-any-failed   [:ja/cancel]}
                             :on {:go-done   :done
                                  :ja/cancel :idle}}
                 :done      {}
                 :idle      {}}})
    (rf/dispatch-sync [:compat/ja-parent [:rf.machine.spawn/spawned]])
    (let [actor (get-in (snapshot :compat/ja-parent) [:data :rf/spawned [:hydrating] :a])
          join  (get-in (snapshot actor) [:data :rf/join-child])]
      (is (some? join))
      (rf/reg-machine :compat/ja-child (bumping-child 2 :live 100))
      (rf/dispatch-sync [actor [:bump]])
      (rf/dispatch-sync [actor [:bump]])
      (is (= {:n 102 :rf/join-child join}
             (select-keys (:data (snapshot actor)) [:n :rf/join-child]))))))
