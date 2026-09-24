(ns re-frame.parent-destroy-reaps-tracked-children-test
  "An actor's destroy ends the children its own `:spawn` / `:spawn-all` slots
  track, whatever destroys it: an explicit `[:rf.machine/destroy <id>]`, a
  replacement at an occupied `:fixed-actor-id`, or its own parent's exit.

  Spec 005 §Declarative `:spawn` binds such a child's lifetime to the state
  that spawned it, and the runtime records the relation at
  `[:rf.runtime/machines :spawned <parent> <invoke-id>]`. The reap walks that
  record after the parent's own `:exit` cascade, so the parent's `:exit` still
  reads a live child. Hand-emitted actors carry no slot and are untouched:
  teardown of an actor nothing tracks stays explicit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- spawned-root
  "`parent-id`'s whole slot map at `[:rf.runtime/machines :spawned parent-id]`."
  [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id]))

(defn- destroyed
  "Every `:rf.machine/destroyed` captured so far, as its tags, oldest first."
  []
  (mapv :tags (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- destroyed-of [actor-id]
  (filterv #(= actor-id (:actor-id %)) (destroyed)))

(defn- reg-logging-child!
  "A child resting in `:w`, whose `:exit` appends `[id :exit]` to `log`."
  [id log]
  (rf/reg-machine id
    {:initial :w
     :states  {:w {:exit (fn [_] (swap! log conj [id :exit]) {})
                   :on   {:ping {:action (fn [{d :data}] {:data (update d :pings (fnil inc 0))})}}}}}))

(defn- reg-parent!
  "A parent that spawns `child-id` declaratively on `:busy`, logging its own
  `:busy` `:exit` into `log`."
  [id child-id log]
  (rf/reg-machine id
    {:initial :idle
     :states  {:idle {:on {:go :busy}}
               :busy {:spawn {:machine-id child-id}
                      :exit  (fn [_] (swap! log conj [id :exit]) {})
                      :on    {:stop :idle}}}}))

(defn- reg-destroy! [event-id actor-id]
  (rf/reg-event event-id (fn [_ _] {:fx [[:rf.machine/destroy actor-id]]})))

;; ---- (a) a single :spawn child ---------------------------------------------

(deftest explicit-parent-destroy-reaps-its-spawn-child
  (testing "an explicit destroy of a parent resting in a :spawn state destroys
            the child after the parent's own :exit, and clears the slot"
    (let [log (atom [])]
      (reg-logging-child! :pr/wa log)
      (reg-parent! :pr/pa :pr/wa log)
      (reg-destroy! :pr/kill-pa :pr/pa)
      (rf/dispatch-sync [:pr/pa [:go]])
      (is (some? (snapshot :pr/wa#1)) "the child is live")
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:pr/kill-pa])
      (is (nil? (snapshot :pr/pa)) "the parent is gone")
      (is (nil? (snapshot :pr/wa#1)) "the child is gone with it")
      (is (= [[:pr/pa :exit] [:pr/wa :exit]] @log)
          "the parent's :exit runs first and reads a live child; the child's :exit runs once")
      (is (nil? (spawned-root :pr/pa)) "no slot is left under the destroyed parent")
      (is (= [:pr/wa#1 :pr/pa] (mapv :actor-id (destroyed)))
          "one destroyed trace for the child, landing before the parent's own")
      (let [child (first (destroyed-of :pr/wa#1))]
        (is (= :explicit (:reason child)))
        (is (= :pr/pa (:parent-id child)))
        (is (= [:busy] (:invoke-id child))))
      (is (= [[:pr/wa#1 :rf/default]]
             (into []
                   (comp (map :tags)
                         (filter #(and (= :destroy-exit (:phase %))
                                       (= :pr/wa#1 (:actor-id %))))
                         (map (juxt :actor-id :frame)))
                   (rf.machines.test-support/events-of :rf.machine/action-ran)))
          "the reaped child's :destroy-exit row names the child and the frame"))))

;; ---- (b) a :spawn-all join -------------------------------------------------

(deftest explicit-parent-destroy-reaps-its-spawn-all-children
  (testing "both children of a :spawn-all join are destroyed and the join slot is cleared"
    (let [log (atom [])]
      (reg-logging-child! :pr/jc log)
      (rf/reg-machine :pr/jp
        {:initial :idle
         :states  {:idle    {:on {:go :forking}}
                   :forking {:spawn-all {:children        [{:id :x :machine-id :pr/jc}
                                                           {:id :y :machine-id :pr/jc}]
                                         :join            :all
                                         :on-all-complete [:all/done]}
                             :on        {:all/done :idle}}}})
      (reg-destroy! :pr/kill-jp :pr/jp)
      (rf/dispatch-sync [:pr/jp [:go]])
      (is (and (some? (snapshot :pr/jc#1)) (some? (snapshot :pr/jc#2))) "both children are live")
      (rf/dispatch-sync [:pr/kill-jp])
      (is (nil? (snapshot :pr/jc#1)))
      (is (nil? (snapshot :pr/jc#2)))
      (is (= [[:pr/jc :exit] [:pr/jc :exit]] @log) "each child's :exit runs once")
      (is (nil? (spawned-root :pr/jp)) "the join slot is gone"))))

;; ---- (c) nested children ---------------------------------------------------

(defn- reg-nested! [prefix log]
  (let [gc     (keyword (name prefix) "gc")
        child  (keyword (name prefix) "child")
        parent (keyword (name prefix) "parent")]
    (reg-logging-child! gc log)
    (rf/reg-machine child
      {:initial :w
       :states  {:w {:spawn {:machine-id gc}
                     :exit  (fn [_] (swap! log conj [child :exit]) {})}}})
    (reg-parent! parent child log)
    [parent child gc]))

(deftest explicit-parent-destroy-reaps-nested-children
  (testing "a destroyed parent's child reaps its own tracked grandchild"
    (let [log                (atom [])
          [parent child gc]  (reg-nested! :prn log)
          child-id           (keyword "prn" "child#1")
          gc-id              (keyword "prn" "gc#1")]
      (reg-destroy! :prn/kill parent)
      (rf/dispatch-sync [parent [:go]])
      (is (some? (snapshot child-id)) "the child is live")
      (is (some? (snapshot gc-id)) "and so is its grandchild")
      (rf/dispatch-sync [:prn/kill])
      (is (nil? (snapshot child-id)))
      (is (nil? (snapshot gc-id)))
      (is (= [[parent :exit] [child :exit] [gc :exit]] @log)
          "exits run root to leaf, each owner's :exit ahead of what it owns")
      (is (nil? (spawned-root parent)))
      (is (nil? (spawned-root child-id))))))

(deftest parent-transition-reaps-nested-children
  (testing "a child destroyed by its parent's transition reaps its own tracked grandchild"
    (let [log               (atom [])
          [parent child gc] (reg-nested! :prt log)
          child-id          (keyword "prt" "child#1")
          gc-id             (keyword "prt" "gc#1")]
      (rf/dispatch-sync [parent [:go]])
      (is (some? (snapshot gc-id)) "the grandchild is live")
      (rf/dispatch-sync [parent [:stop]])
      (is (= :idle (:state (snapshot parent))))
      (is (nil? (snapshot child-id)))
      (is (nil? (snapshot gc-id)) "the grandchild ends with the child")
      (is (= [[parent :exit] [child :exit] [gc :exit]] @log))
      (is (nil? (spawned-root child-id))))))

;; ---- (d) replacement at an occupied :fixed-actor-id ------------------------

(deftest replacing-an-occupant-reaps-its-tracked-child
  (testing "a spawn onto an occupied :fixed-actor-id destroys the occupant and
            the child the occupant's :spawn slot tracks"
    (let [log (atom [])]
      (reg-logging-child! :prd/kid log)
      (reg-parent! :prd/parent :prd/kid log)
      (rf/reg-event :prd/hire
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :prd/parent
                                            :fixed-actor-id :prd/p}]]}))
      (rf/dispatch-sync [:prd/hire])
      (rf/dispatch-sync [:prd/p [:go]])
      (is (some? (snapshot :prd/kid#1)) "the occupant's child is live")
      (rf/dispatch-sync [:prd/hire])
      (is (= :idle (:state (snapshot :prd/p))) "the replacement is installed")
      (is (nil? (snapshot :prd/kid#1)) "the occupant's child ended with the occupant")
      (is (= [[:prd/parent :exit] [:prd/kid :exit]] @log))
      (is (nil? (spawned-root :prd/p))))))

;; ---- (e) a re-created parent re-mints its child's address ------------------

(deftest a-re-created-parent-spawns-its-child-at-the-first-address
  (testing "a parent destroyed and re-created at the same address starts its
            spawn counter fresh, and its child's generated address is free
            because the previous incarnation's child ended with it"
    (let [log (atom [])]
      (reg-logging-child! :pre/kid log)
      (reg-parent! :pre/parent :pre/kid log)
      (rf/reg-event :pre/hire
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :pre/parent
                                            :fixed-actor-id :pre/p}]]}))
      (reg-destroy! :pre/fire :pre/p)
      (rf/dispatch-sync [:pre/hire])
      (rf/dispatch-sync [:pre/p [:go]])
      (rf/dispatch-sync [:pre/kid#1 [:ping]])
      (is (= 1 (:pings (rf.machines.test-support/machine-data :pre/kid#1))))
      (rf/dispatch-sync [:pre/fire])
      (is (nil? (snapshot :pre/kid#1)) "the first incarnation's child is gone")
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:pre/hire])
      (rf/dispatch-sync [:pre/p [:go]])
      (is (empty? (rf.machines.test-support/events-of :rf.error/machine-spawn-all-duplicate-id))
          "no collision is reported")
      (is (some? (snapshot :pre/kid#1)) "the new child is installed at #1")
      (is (nil? (:pings (rf.machines.test-support/machine-data :pre/kid#1)))
          "and it is a fresh actor")
      (is (= :pre/kid#1 (get-in (rf.machines.test-support/machine-data :pre/p) [:rf/spawned [:busy]]))))))

;; ---- (f) the parent's own :exit already destroyed the child ----------------

(deftest a-child-the-parent-exit-destroyed-is-not-destroyed-twice
  (testing "a parent :exit that destroys its child by id leaves the reap only
            the slot to prune: one child :exit, one destroyed trace, no slot"
    (let [log (atom [])]
      (reg-logging-child! :prf/kid log)
      (rf/reg-machine :prf/parent
        {:initial :idle
         :states  {:idle {:on {:go :busy}}
                   :busy {:spawn {:machine-id :prf/kid}
                          :exit  (fn [{data :data}]
                                   {:fx [[:rf.machine/destroy (get-in data [:rf/spawned [:busy]])]]})}}})
      (reg-destroy! :prf/kill :prf/parent)
      (rf/dispatch-sync [:prf/parent [:go]])
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:prf/kill])
      (is (nil? (snapshot :prf/kid#1)))
      (is (= [[:prf/kid :exit]] @log) "the child's :exit ran once")
      (is (= 1 (count (destroyed-of :prf/kid#1))) "one destroyed trace for the child")
      (is (nil? (spawned-root :prf/parent)) "the slot naming the dead child is pruned"))))

;; ---- controls --------------------------------------------------------------

(deftest a-hand-emitted-child-outlives-its-spawner
  (testing "an actor no slot tracks survives the destroy of the actor that spawned it"
    (let [log (atom [])]
      (reg-logging-child! :prh/kid log)
      (rf/reg-machine :prh/parent
        {:initial :idle
         :states  {:idle {:on {:go {:action (fn [_]
                                              {:fx [[:rf.machine/spawn {:machine-id     :prh/kid
                                                                        :fixed-actor-id :prh/free}]]})}}}}})
      (reg-destroy! :prh/kill :prh/parent)
      (rf/dispatch-sync [:prh/parent [:go]])
      (is (some? (snapshot :prh/free)))
      (rf/dispatch-sync [:prh/kill])
      (is (nil? (snapshot :prh/parent)))
      (is (some? (snapshot :prh/free)) "the hand-emitted child is still live")
      (is (= [] @log) "and its :exit did not run"))))

(deftest a-transition-exit-destroys-the-child-once
  (testing "leaving the spawning state destroys the child exactly once"
    (let [log (atom [])]
      (reg-logging-child! :prx/kid log)
      (reg-parent! :prx/parent :prx/kid log)
      (rf/dispatch-sync [:prx/parent [:go]])
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:prx/parent [:stop]])
      (is (nil? (snapshot :prx/kid#1)))
      (is (= [[:prx/parent :exit] [:prx/kid :exit]] @log))
      (is (= 1 (count (destroyed-of :prx/kid#1)))))))

(deftest a-slot-naming-a-same-id-replacement-leaves-the-replacement-live
  (testing "the reap prunes a slot whose address now holds an actor the parent
            does not own, and leaves that actor alone"
    (let [log (atom [])]
      (reg-logging-child! :prr/kid log)
      (rf/reg-machine :prr/parent
        {:initial :idle
         :states  {:idle {:on {:go :busy}}
                   :busy {:spawn {:machine-id :prr/kid :fixed-actor-id :prr/x}}}})
      (rf/reg-event :prr/squat
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :prr/kid :fixed-actor-id :prr/x}]]}))
      (reg-destroy! :prr/kill :prr/parent)
      (rf/dispatch-sync [:prr/parent [:go]])
      (is (= :prr/parent (get-in (rf.machines.test-support/machine-data :prr/x) [:rf/parent-id])))
      (rf/dispatch-sync [:prr/squat])
      (is (nil? (get-in (rf.machines.test-support/machine-data :prr/x) [:rf/parent-id]))
          "a hand-emitted replacement now holds the address")
      (rf/dispatch-sync [:prr/x [:ping]])
      (reset! log [])
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:prr/kill])
      (is (some? (snapshot :prr/x)) "the replacement is still live")
      (is (= 1 (:pings (rf.machines.test-support/machine-data :prr/x))) "and untouched")
      (is (= [] @log))
      (is (empty? (destroyed-of :prr/x)))
      (is (nil? (spawned-root :prr/parent)) "the stale slot is pruned"))))
