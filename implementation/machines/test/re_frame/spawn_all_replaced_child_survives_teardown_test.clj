(ns re-frame.spawn-all-replaced-child-survives-teardown-test
  "A `:spawn-all` join's teardown, by parent destroy or by leaving the joining
  state, leaves a hand-spawned replacement at a join child's address live and
  untouched, and prunes the stale join slot (Spec 005 §Spawn-and-join)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- spawned-root [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id]))

(defn- replace-join-child!
  "Start `parent-id`'s join over one `kid-id` child (whose `:exit` logs to `log`),
  then hand-spawn a replacement at the child's address `kid-addr` and clear `log`."
  [parent-id kid-id kid-addr log]
  (rf/reg-machine kid-id
    {:initial :w
     :states  {:w {:exit (fn [_] (swap! log conj :exit) {})}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :states  {:idle    {:on {:go :forking}}
               :forking {:spawn-all {:children        [{:id :k :machine-id kid-id}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :idle
                                     :stop     :idle}}}})
  (let [squat (keyword (namespace parent-id) "squat")]
    (rf/reg-event squat
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id kid-id :fixed-actor-id kid-addr}]]}))
    (rf/dispatch-sync [parent-id [:go]])
    (rf/dispatch-sync [squat]))
  (is (= kid-addr (get-in (spawned-root parent-id) [[:forking] :children :k]))
      "the join state still names the replaced address")
  (reset! log []))

(defn- replacement-untouched [parent-id kid-addr log]
  (is (some? (rf.machines.test-support/snapshot kid-addr)) "the replacement is live")
  (is (= [] @log) "its :exit did not run")
  (is (nil? (spawned-root parent-id)) "the stale join slot is pruned"))

(deftest parent-destroy-leaves-a-replaced-join-child-live
  (let [log (atom [])]
    (replace-join-child! :sjd/parent :sjd/kid :sjd/kid#1 log)
    (rf/reg-event :sjd/kill (fn [_ _] {:fx [[:rf.machine/destroy :sjd/parent]]}))
    (rf/dispatch-sync [:sjd/kill])
    (replacement-untouched :sjd/parent :sjd/kid#1 log)))

(deftest leaving-the-join-state-leaves-a-replaced-join-child-live
  (let [log (atom [])]
    (replace-join-child! :sjx/parent :sjx/kid :sjx/kid#1 log)
    (rf/dispatch-sync [:sjx/parent [:stop]])
    (replacement-untouched :sjx/parent :sjx/kid#1 log)))
