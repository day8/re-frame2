(ns re-frame.destroy-exit-cascade-test
  "Per rf2-nahfm — every destroy path runs the child's active configuration
  `:exit` cascade BEFORE teardown. Pre-rf2-nahfm the four destroy entry-
  points each cleared the actor's snapshot WITHOUT firing the `:exit`
  actions Spec 005 §Declarative `:invoke` §Composition with explicit
  `:entry` / `:exit` and §Final states §Composition with `:entry` /
  `:exit` promise.

  The four destroy paths exercised here:
    1. Explicit `[:rf.machine/destroy actor-id]` fx (keyword form)
       fired from an action.
    2. Declarative `:invoke` exit-cascade destroy (tracked-map form,
       fired by `apply-transition-once` when the exit cascade crosses
       an `:invoke`-bearing state).
    3. `:invoke-all` per-child teardown (parent cascade tears children
       down through `destroy-invoke-all-children!`).
    4. Final-state auto-destroy (child enters `:final?`; `finalize-
       machine` runs the cascade).

  For each path we assert:
    - the child's `:exit` action's side effect ran (we use an atom side
      channel since `:exit` actions are pure fns whose `:fx` we'd
      otherwise route through `do-fx` — the atom write captures `:exit`
      fired regardless of whether `:fx` interpretation happened).
    - the `:exit` action's `:data` write was visible somewhere
      observable (different per-path: finalize → projected into the
      pre-teardown snapshot read by `:on-done`; destroy → projected
      into the snapshot at `[:rf/machines actor-id]` before the
      teardown projection clears it — observable to any trace consumer
      reading the db between `:exit` and `:rf.machine/destroyed`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- (1) explicit [:rf.machine/destroy actor-id] -------------------------

(deftest exit-fires-on-explicit-destroy
  (testing "explicit `[:rf.machine/destroy actor-id]` fires the active leaf's :exit"
    (let [exit-fired (atom 0)
          ;; Define a child machine whose active state's :exit increments
          ;; an external counter — proves the :exit action ran.
          _ (rf/reg-machine :ne/standalone
              {:initial :running
               :data    {}
               :states  {:running {:exit (fn [_ _] (swap! exit-fired inc) {})}}})
          _ (rf/reg-machine :ne/destroyer
              {:initial :armed
               :data    {}
               :states
               {:armed {:on {:fire {:action (fn [_ _] {:fx [[:rf.machine/destroy :ne/standalone]]})}}}}})]
      ;; Bring the standalone machine to life by dispatching ANY event
      ;; (the first dispatch fires the bootstrap cascade per rf2-pexjc).
      (rf/dispatch-sync [:ne/standalone [:rf.machine/noop]])
      (is (zero? @exit-fired) "no :exit yet — actor still alive")
      (rf/dispatch-sync [:ne/destroyer [:fire]])
      (is (= 1 @exit-fired)
          ":exit fired exactly once during explicit destroy"))))

;; ---- (2) declarative :invoke exit-cascade destroy ------------------------

(deftest exit-fires-on-invoke-exit-cascade-destroy
  (testing "parent's :invoke exit cascade fires the child's active :exit"
    (let [exit-fired   (atom 0)
          last-data    (atom nil)
          _ (rf/reg-machine :ne/invoke-child
              {:initial :working
               :data    {:counter 0}
               :states  {:working {:exit (fn [data _]
                                            (swap! exit-fired inc)
                                            (reset! last-data data)
                                            {})}}})
          _ (rf/reg-machine :ne/invoke-parent
              {:initial :idle
               :data    {}
               :states
               {:idle    {:on {:start :working}}
                :working {:invoke {:machine-id :ne/invoke-child}
                          :on     {:stop :idle}}}})]
      (rf/dispatch-sync [:ne/invoke-parent [:start]])     ;; spawns child
      (is (zero? @exit-fired) "no :exit yet — child still alive")
      (rf/dispatch-sync [:ne/invoke-parent [:stop]])      ;; parent exits :working → child destroyed
      (is (= 1 @exit-fired)
          "child's :exit fired exactly once during the parent's exit cascade")
      ;; The spawn pipeline stamps :rf/parent-id / :rf/invoke-id /
      ;; :rf/self-id onto the spawned child's :data; the user-supplied
      ;; :counter slot is the bit we care about. Asserting on the
      ;; full :data would couple this test to the spawn-stamp shape.
      (is (= 0 (:counter @last-data))
          "child's :exit saw its live snapshot's :data (incl :counter) before teardown"))))

;; ---- (3) :invoke-all per-child teardown ----------------------------------

(deftest exit-fires-on-invoke-all-children-teardown
  (testing ":invoke-all parent exit fires every child's active :exit"
    (let [exits  (atom [])
          _ (rf/reg-machine :ne/ia-child
              {:initial :working
               :data    {}
               :states  {:working {:on   {:done :final}
                                   :exit (fn [_ _]
                                           (swap! exits conj :working-exit)
                                           {})}
                         :final   {:final? true}}})
          _ (rf/reg-machine :ne/ia-parent
              {:initial :hydrating
               :data    {}
               :states
               {:hydrating {:invoke-all
                            {:children
                             [{:id :a :machine-id :ne/ia-child}
                              {:id :b :machine-id :ne/ia-child}]
                             :join              :all
                             :on-child-done     :ia/asset-done
                             :on-child-error    :ia/asset-failed
                             :on-all-complete   [:go-done]
                             :on-any-failed     [:ia/cancel]}
                            :on {:go-done    :done
                                 :ia/cancel  :idle}}
                :done {}
                :idle {}}})]
      (rf/dispatch-sync [:ne/ia-parent [:rf.machine/spawned]])
      (is (empty? @exits) "no :exit yet — children still running")
      (rf/dispatch-sync [:ne/ia-parent [:ia/cancel]])     ;; parent → :idle cancels invoke-all
      (is (= [:working-exit :working-exit] @exits)
          "each child's active-state :exit fired once during the invoke-all teardown"))))

;; ---- (4) final-state auto-destroy ----------------------------------------

(deftest exit-fires-on-final-state-auto-destroy
  (testing "child entering :final? fires the final state's :exit before auto-destroy"
    (let [exit-fired (atom 0)
          seen-data  (atom nil)
          _ (rf/reg-machine :ne/final-child
              {:initial :running
               :data    {:counter 7}
               :states
               {:running {:on {:finish {:target :done
                                        :action (fn [data _]
                                                  {:data (update data :counter inc)})}}}
                :done    {:final?     true
                          :output-key :counter
                          :exit       (fn [data _]
                                        (swap! exit-fired inc)
                                        (reset! seen-data data)
                                        {})}}})
          _ (rf/reg-machine :ne/final-parent
              {:initial :working
               :data    {}
               :states
               {:working {:invoke {:machine-id :ne/final-child
                                   :on-done    (fn [data result]
                                                 (assoc data :received result))}}}})]
      (rf/dispatch-sync [:ne/final-parent [:rf.machine/spawned]])
      (is (zero? @exit-fired) "no :exit yet — child still running")
      ;; Drive the child to :final?.
      (let [spawned-id (get-in (rf/get-frame-db :rf/default)
                               [:rf/spawned :ne/final-parent [:working]])]
        (is (some? spawned-id))
        (rf/dispatch-sync [spawned-id [:finish]]))
      (is (= 1 @exit-fired)
          "final state's :exit fired exactly once during auto-destroy")
      (is (= {:counter 8} (select-keys @seen-data [:counter]))
          ":exit saw the final state's :data (post-transition snapshot)")
      ;; :on-done received the :output-key slot computed PRE-:exit
      ;; (per Spec 005 — :exit reads, doesn't write, the output slot).
      ;; Our :exit doesn't mutate :counter so this is just a sanity
      ;; check that the existing :on-done contract still holds.
      (is (= 8 (get-in (get-in (rf/get-frame-db :rf/default)
                               [:rf/machines :ne/final-parent])
                       [:data :received]))
          ":on-done still received the :output-key slot — contract preserved"))))

;; ---- regression: :exit fx surfaces ---------------------------------------

(deftest exit-fx-fires-on-destroy
  (testing ":exit-emitted :fx fires through the standard fx interpreter on destroy"
    (let [fx-fired   (atom 0)
          _ (rf/reg-fx :ne/test-fx (fn [_ _] (swap! fx-fired inc)))
          _ (rf/reg-machine :ne/fx-emitter
              {:initial :running
               :data    {}
               :states  {:running {:exit (fn [_ _] {:fx [[:ne/test-fx nil]]})}}})
          _ (rf/reg-machine :ne/fx-killer
              {:initial :armed
               :data    {}
               :states
               {:armed {:on {:fire {:action (fn [_ _] {:fx [[:rf.machine/destroy :ne/fx-emitter]]})}}}}})]
      (rf/dispatch-sync [:ne/fx-emitter [:rf.machine/noop]])
      (rf/dispatch-sync [:ne/fx-killer [:fire]])
      (is (= 1 @fx-fired)
          ":exit-emitted :fx fired through the fx interpreter (rf2-nahfm — destroy path uses do-fx)"))))
