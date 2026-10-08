(ns re-frame.parent-destroy-reaps-tracked-children-test
  "An actor's destroy ends the children its own `:spawn` / `:spawn-all` slots
  track, after its own `:exit`, whatever destroys it (an explicit
  `[:rf.machine/destroy <id>]`, or its parent's exit). A hand-emitted actor
  carries no slot and outlives its spawner (Spec 005 §Declarative `:spawn`)."
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

(defn- spawned-root [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id]))

(defn- reg-logging-child!
  "A child resting in `:w`, whose `:exit` appends `[id :exit]` to `log`."
  [id log]
  (rf/reg-machine id
    {:initial :w
     :states  {:w {:exit (fn [_] (swap! log conj [id :exit]) {})}}}))

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

(deftest explicit-parent-destroy-reaps-its-spawn-child
  (testing "the child is destroyed once, after the parent's own :exit, its destroyed
            trace lands before the parent's, and no slot is left"
    (let [log (atom [])]
      (reg-logging-child! :pr/wa log)
      (reg-parent! :pr/pa :pr/wa log)
      (reg-destroy! :pr/kill-pa :pr/pa)
      (rf/dispatch-sync [:pr/pa [:go]])
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:pr/kill-pa])
      (is (= [nil nil [[:pr/pa :exit] [:pr/wa :exit]] nil [:pr/wa#1 :pr/pa]]
             [(snapshot :pr/pa)
              (snapshot :pr/wa#1)
              @log
              (spawned-root :pr/pa)
              (mapv (comp :actor-id :tags)
                    (rf.machines.test-support/events-of :rf.machine/destroyed))])))))

(deftest explicit-parent-destroy-reaps-its-spawn-all-children
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
    (rf/dispatch-sync [:pr/kill-jp])
    (is (= [nil nil [[:pr/jc :exit] [:pr/jc :exit]] nil]
           [(snapshot :pr/jc#1) (snapshot :pr/jc#2) @log (spawned-root :pr/jp)]))))

(deftest parent-transition-reaps-nested-children
  (testing "a child destroyed by its parent's transition reaps its own tracked
            grandchild, exits running root to leaf"
    (let [log    (atom [])
          parent :prt/parent
          child  :prt/child
          gc     :prt/gc]
      (reg-logging-child! gc log)
      (rf/reg-machine child
        {:initial :w
         :states  {:w {:spawn {:machine-id gc}
                       :exit  (fn [_] (swap! log conj [child :exit]) {})}}})
      (reg-parent! parent child log)
      (rf/dispatch-sync [parent [:go]])
      (rf/dispatch-sync [parent [:stop]])
      (is (= [nil nil [[parent :exit] [child :exit] [gc :exit]] nil]
             [(snapshot :prt/child#1) (snapshot :prt/gc#1) @log (spawned-root :prt/child#1)])))))

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
      (rf/dispatch-sync [:prh/kill])
      (is (= [nil true []]
             [(snapshot :prh/parent) (some? (snapshot :prh/free)) @log])))))
