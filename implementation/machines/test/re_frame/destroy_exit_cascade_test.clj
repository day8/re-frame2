(ns re-frame.destroy-exit-cascade-test
  "Every destroy path runs the child's active configuration `:exit` cascade
  BEFORE teardown, per Spec 005 §Declarative `:spawn` §Composition with
  explicit `:entry` / `:exit` and §Final states §Composition with `:entry` /
  `:exit`.

  The four destroy paths exercised here:
    1. Explicit `[:rf.machine/destroy actor-id]` fx (keyword form)
       fired from an action.
    2. Declarative `:spawn` exit-cascade destroy (tracked-map form,
       fired by `apply-transition-once` when the exit cascade crosses
       a `:spawn`-bearing state).
    3. `:spawn-all` per-child teardown (parent cascade tears children
       down through `destroy-spawn-all-children!`).
    4. Final-state auto-destroy (child enters `:final?`; `finalize-
       machine` runs the cascade).

  The explicit and `:spawn-all` paths' `:exit` counts are pinned by the
  ordered logs in `destroyed_exit_order_test`, and the `:exit` action's
  `:fx` by `machine_exit_cascade_incarnation_fence_cljs_test`; the cases
  here assert, per path:
    - the child's `:exit` action's side effect ran (we use an atom side
      channel since `:exit` actions are pure fns whose `:fx` we'd
      otherwise route through `do-fx` — the atom write captures `:exit`
      fired regardless of whether `:fx` interpretation happened).
    - the `:exit` action's `:data` write was visible somewhere
      observable (different per-path: finalize → projected into the
      pre-teardown snapshot read by `:on-done`; destroy → projected
      into the snapshot at `[:rf.runtime/machines :snapshots actor-id]` before the
      teardown projection clears it — observable to any trace consumer
      reading the db between `:exit` and `:rf.machine/destroyed`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---- (2) declarative :spawn exit-cascade destroy ------------------------

(deftest exit-fires-on-spawn-exit-cascade-destroy
  (testing "parent's :spawn exit cascade fires the child's active :exit"
    (let [exit-fired   (atom 0)
          last-data    (atom nil)
          _ (rf/reg-machine :ne/invoke-child
              {:initial :working
               :data    {:counter 0}
               :states  {:working {:exit (fn [{data :data}]
                                            (swap! exit-fired inc)
                                            (reset! last-data data)
                                            {})}}})
          _ (rf/reg-machine :ne/invoke-parent
              {:initial :idle
               :data    {}
               :states
               {:idle    {:on {:start :working}}
                :working {:spawn {:machine-id :ne/invoke-child}
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
                                        :action (fn [{data :data}]
                                                  {:data (update data :counter inc)})}}}
                :done    {:final?     true
                          :output-key :counter
                          :exit (fn [{data :data}]
                                        (swap! exit-fired inc)
                                        (reset! seen-data data)
                                        {})}}})
          _ (rf/reg-machine :ne/final-parent
              {:initial :working
               :data    {}
               :states
               {:working {:spawn {:machine-id :ne/final-child
                                   :on-done (fn [{data :data result :result}]
                                                 (assoc data :received result))}}}})]
      (rf/dispatch-sync [:ne/final-parent [:rf.machine.spawn/spawned]])
      (is (zero? @exit-fired) "no :exit yet — child still running")
      ;; Drive the child to :final?.
      (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :spawned :ne/final-parent [:working]])]
        (is (some? spawned-id))
        (rf/dispatch-sync [spawned-id [:finish]]))
      (is (= 1 @exit-fired)
          "final state's :exit fired exactly once during auto-destroy")
      (is (= {:counter 8} (select-keys @seen-data [:counter]))
          ":exit saw the final state's :data (post-transition snapshot)")
      ;; :on-done received the :output-key slot computed PRE-:exit
      ;; (per Spec 005 — :exit reads, doesn't write, the output slot).
      ;; Our :exit doesn't mutate :counter so this is a sanity check on
      ;; the :on-done contract.
      (is (= 8 (get-in (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :snapshots :ne/final-parent])
                       [:data :received]))
          ":on-done received the :output-key slot"))))

;; ---- :rf.machine/action-ran attribution on the destroy path --------------
;;
;; Spec 005's `action-ran` tag set makes `:actor-id` (the live instance) and
;; `:frame` unconditional on every phase, `:destroy-exit` included. The Xray
;; Handler section attributes a teardown's `:exit` rows by `:actor-id`, and
;; epoch capture admits a trace only when it carries `:frame`.

(defn- action-ran-rows
  "Every `:rf.machine/action-ran` in `traces`, as `[phase actor-id action-id]`
  triples in emit order."
  [traces]
  (into []
        (comp (filter #(= :rf.machine/action-ran (:operation %)))
              (map :tags)
              (map (juxt :phase :actor-id :action-id)))
        traces))

(defn- destroy-exit-tags
  "The tags of every `:phase :destroy-exit` `:rf.machine/action-ran` in
  `traces`, in emit order."
  [traces]
  (into []
        (comp (filter #(= :rf.machine/action-ran (:operation %)))
              (map :tags)
              (filter #(= :destroy-exit (:phase %))))
        traces))

(def ^:private logging-actions
  {:ent-a (fn [_] {}) :ex-a (fn [_] {}) :tx (fn [_] {})
   :ent-b (fn [_] {}) :ex-b (fn [_] {})})

(deftest destroy-exit-row-names-an-explicitly-destroyed-singleton
  (testing "an explicit destroy of a singleton attributes its :exit row to the singleton and its frame"
    (rf/reg-machine :dea/single
      {:initial :a
       :actions logging-actions
       :states  {:a {:entry :ent-a :exit :ex-a :on {:go {:target :b :action :tx}}}
                 :b {:entry :ent-b :exit :ex-b}}})
    (rf/reg-event :dea/kill-single (fn [_ _] {:fx [[:rf.machine/destroy :dea/single]]}))
    (rf/dispatch-sync [:dea/single [:rf.machine/start]])
    (rf/dispatch-sync [:dea/single [:go]])
    (rf/dispatch-sync [:dea/kill-single])
    (let [traces (rf.machines.test-support/captured-events)]
      (is (nil? (rf.machines.test-support/snapshot :dea/single)) "the singleton is torn down")
      (is (= [[:initial-entry :dea/single :ent-a]
              [:exit          :dea/single :ex-a]
              [:transition    :dea/single :tx]
              [:entry         :dea/single :ent-b]
              [:destroy-exit  :dea/single :ex-b]]
             (action-ran-rows traces))
          "every phase, :destroy-exit included, names the live instance")
      (is (= [:rf/default] (mapv :frame (destroy-exit-tags traces)))
          "the :destroy-exit row carries the destroying frame"))))

(deftest destroy-exit-row-names-a-destroyed-spawned-child
  (testing "a declarative child destroyed by its parent's exit attributes its :exit row to the child instance"
    (rf/reg-machine :dea/kid
      {:initial :working
       :actions logging-actions
       :states  {:working {:exit :ex-a}}})
    (rf/reg-machine :dea/parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :dea/kid}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:dea/parent [:start]])
    (let [kid    (get-in (rf.machines.test-support/runtime-db)
                         [:rf.runtime/machines :spawned :dea/parent [:working]])
          _      (rf.machines.test-support/reset-captured!)
          _      (rf/dispatch-sync [:dea/parent [:stop]])
          traces (rf.machines.test-support/captured-events)]
      (is (= :dea/kid#1 kid))
      (is (nil? (rf.machines.test-support/snapshot kid)) "the child is torn down")
      (is (= [[kid :ex-a :rf/default]]
             (mapv (juxt :actor-id :action-id :frame) (destroy-exit-tags traces)))
          "the child's :destroy-exit row names the child instance and the frame"))))

(deftest destroy-exit-row-names-a-finalised-actor
  (testing "final-state auto-destroy attributes its :exit row to the finishing instance"
    (rf/reg-machine :dea/finisher
      {:initial :running
       :actions logging-actions
       :states  {:running {:on {:finish :done}}
                 :done    {:final? true :exit :ex-b}}})
    (rf/reg-machine :dea/final-parent
      {:initial :working
       :states  {:working {:spawn {:machine-id :dea/finisher}}}})
    (rf/dispatch-sync [:dea/final-parent [:rf.machine.spawn/spawned]])
    (let [kid    (get-in (rf.machines.test-support/runtime-db)
                         [:rf.runtime/machines :spawned :dea/final-parent [:working]])
          _      (rf.machines.test-support/reset-captured!)
          _      (rf/dispatch-sync [kid [:finish]])
          traces (rf.machines.test-support/captured-events)]
      (is (some? kid))
      (is (nil? (rf.machines.test-support/snapshot kid)) "the finished child is torn down")
      (is (= [[kid :ex-b :rf/default]]
             (mapv (juxt :actor-id :action-id :frame) (destroy-exit-tags traces)))
          "the final state's :destroy-exit row names the finishing instance and the frame"))))
