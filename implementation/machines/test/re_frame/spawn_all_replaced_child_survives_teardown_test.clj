(ns re-frame.spawn-all-replaced-child-survives-teardown-test
  "A `:spawn-all` join's teardown ends only the children that are this join's
  own, whether the parent is destroyed or leaves the joining state.

  The join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`
  names each child's address under `:children`. An address can be re-occupied
  by an actor the join does not own — a hand-emitted spawn at the child's
  `:fixed-actor-id` replaces the child — and the join's `:children` entry then
  names a stranger. Teardown authenticates each occupant's `:rf/join-child`
  membership against this exact parent, invoke path, logical child and attempt
  (Spec 005 §Spawn-and-join): an owned child is destroyed, and a replacement is
  left live and untraced while the stale join slot is pruned."
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
(def ^:private machine-data rf.machines.test-support/machine-data)

(defn- spawned-root
  "`parent-id`'s whole slot map at `[:rf.runtime/machines :spawned parent-id]`."
  [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id]))

(defn- destroyed-of
  "The tags of every captured `:rf.machine/destroyed` naming `actor-id`."
  [actor-id]
  (into []
        (comp (map :tags) (filter #(= actor-id (:actor-id %))))
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- reg-kid!
  "A child resting in `:w`: its `:exit` appends `[id :exit]` to `log`, and
  `:ping` stamps `:marker :replacement` into its `:data`."
  [id log]
  (rf/reg-machine id
    {:initial :w
     :states  {:w {:exit (fn [_] (swap! log conj [id :exit]) {})
                   :on   {:ping {:action (fn [{d :data}] {:data (assoc d :marker :replacement)})}}}}}))

(defn- reg-join-parent!
  "A parent whose `:forking` state joins one `:spawn-all` child `:k` of type
  `kid-id`, and which leaves `:forking` on `:stop`."
  [id kid-id]
  (rf/reg-machine id
    {:initial :idle
     :states  {:idle    {:on {:go :forking}}
               :forking {:spawn-all {:children        [{:id :k :machine-id kid-id}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :idle
                                     :stop     :idle}}}}))

(defn- replace-join-child!
  "Start `parent-id`'s join, then hand-spawn `kid-id` at the join child's
  address `kid-addr`, replacing it, and ping the replacement once.
  Clears `log` and the captured traces afterwards."
  [parent-id kid-id kid-addr log]
  (let [squat (keyword (namespace parent-id) "squat")]
    (rf/reg-event squat
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     kid-id
                                          :fixed-actor-id kid-addr}]]}))
    (rf/dispatch-sync [parent-id [:go]])
    (is (= parent-id (get-in (machine-data kid-addr) [:rf/join-child :parent-id]))
        "the join's child holds the address")
    (rf/dispatch-sync [squat])
    (is (nil? (:rf/join-child (machine-data kid-addr)))
        "a hand-emitted replacement holds the address")
    (is (nil? (:rf/parent-id (machine-data kid-addr))))
    (is (= kid-addr (get-in (spawned-root parent-id) [[:forking] :children :k]))
        "the join state names the replaced address")
    (rf/dispatch-sync [kid-addr [:ping]])
    (is (= :replacement (:marker (machine-data kid-addr))))
    (reset! log [])
    (rf.machines.test-support/reset-captured!)))

(defn- replacement-untouched
  "Assert the replacement at `kid-addr` is live, untouched and untraced, and
  that `parent-id` holds no join slot."
  [parent-id kid-addr log]
  (is (some? (snapshot kid-addr)) "the replacement is live")
  (is (= :replacement (:marker (machine-data kid-addr))) "and untouched")
  (is (= [] @log) "its :exit did not run")
  (is (empty? (destroyed-of kid-addr)) "no destroyed trace names it")
  (is (nil? (spawned-root parent-id)) "the stale join slot is pruned"))

;; ---- a replaced join child survives its old parent -------------------------

(deftest parent-destroy-leaves-a-replaced-join-child-live
  (testing "destroying the parent prunes a join slot whose address holds an
            actor the join does not own, and leaves that actor alone"
    (let [log (atom [])]
      (reg-kid! :sjd/kid log)
      (reg-join-parent! :sjd/parent :sjd/kid)
      (rf/reg-event :sjd/kill (fn [_ _] {:fx [[:rf.machine/destroy :sjd/parent]]}))
      (replace-join-child! :sjd/parent :sjd/kid :sjd/kid#1 log)
      (rf/dispatch-sync [:sjd/kill])
      (is (nil? (snapshot :sjd/parent)) "the parent is gone")
      (replacement-untouched :sjd/parent :sjd/kid#1 log))))

(deftest leaving-the-join-state-leaves-a-replaced-join-child-live
  (testing "the parent's exit from the joining state treats a replaced child
            the same way its destroy does"
    (let [log (atom [])]
      (reg-kid! :sjx/kid log)
      (reg-join-parent! :sjx/parent :sjx/kid)
      (replace-join-child! :sjx/parent :sjx/kid :sjx/kid#1 log)
      (rf/dispatch-sync [:sjx/parent [:stop]])
      (is (= :idle (:state (snapshot :sjx/parent))) "the parent left the joining state")
      (replacement-untouched :sjx/parent :sjx/kid#1 log))))

;; ---- control: an owned join child is destroyed -----------------------------

(deftest parent-destroy-destroys-an-owned-join-child-once
  (testing "a join child the parent owns is destroyed with the parent, with
            one owned-child destroyed trace"
    (let [log (atom [])]
      (reg-kid! :sjo/kid log)
      (reg-join-parent! :sjo/parent :sjo/kid)
      (rf/reg-event :sjo/kill (fn [_ _] {:fx [[:rf.machine/destroy :sjo/parent]]}))
      (rf/dispatch-sync [:sjo/parent [:go]])
      (is (= :sjo/parent (get-in (machine-data :sjo/kid#1) [:rf/join-child :parent-id])))
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:sjo/kill])
      (is (nil? (snapshot :sjo/parent)))
      (is (nil? (snapshot :sjo/kid#1)) "the owned child is gone")
      (is (= [[:sjo/kid :exit]] @log) "its :exit ran once")
      (let [traces (destroyed-of :sjo/kid#1)]
        (is (= 1 (count traces)) "one destroyed trace names it")
        (is (= {:reason :explicit :parent-id :sjo/parent :invoke-id [:forking] :child-id :k}
               (select-keys (first traces) [:reason :parent-id :invoke-id :child-id]))))
      (is (nil? (spawned-root :sjo/parent)) "the join slot is gone"))))
