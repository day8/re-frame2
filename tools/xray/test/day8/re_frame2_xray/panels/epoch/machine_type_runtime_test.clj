(ns day8.re-frame2-xray.panels.epoch.machine-type-runtime-test
  "Every machine cascade row carries the registered TYPE its source resolves
  through, `:machine-type`, over the records real events leave.

  Machine traces address the live instance (`:actor-id`), which is the type
  only for a singleton. A plain event that destroys a machine names no
  machine in its own event id, and a spawned actor's id names no
  registration, so neither can be the lookup key; the type is. A spawned
  actor's type rides its snapshot (`[:rf/runtime :machine-type]`), which the
  record's frame state carries before and after the event."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- last-record [] (last (rf.epoch/epoch-history :rf/default)))

(defn- cascade-of
  "The HANDLER step's machine cascade `project` builds from `record`."
  [record]
  (get-in (some #(when (= :handler (:step %)) %) (proj/project record))
          [:machine :cascade]))

(defn- row [cascade kind]
  (some #(when (= kind (:kind %)) %) cascade))

(defn- action-source
  "The captured source of action `action-id` under the registration `id`."
  [id action-id]
  (get-in (rf/handler-meta {:kind :event :id id})
          [:rf/machine :actions action-id :source-code]))

(defn- reg-machines! []
  (rf/reg-machine :p/door
    {:initial :open
     :data    {}
     :actions {:bye (fn [_] {})}
     :states  {:open {:exit :bye :on {:poke {:action :bye}}}}})
  (rf/reg-machine :p/child
    {:initial :live
     :data    {:n 0}
     :guards  {:never? (fn [_] false)}
     :actions {:bump (fn [{data :data}] {:data (update data :n inc)})
               :bye  (fn [_] {})}
     :states  {:live {:exit :bye
                      :on   {:bump    {:action :bump}
                             :blocked {:guard :never? :action :bump}}}}})
  (rf/reg-machine :p/parent
    {:initial :idle
     :data    {}
     :states  {:idle {:on {:go {:action (fn [_] {:fx [[:rf.machine/spawn
                                                          {:machine-id :p/child
                                                           :id-prefix  :p/kid}]]})}}}}}))

(defn- spawn-child!
  "Spawn one `:p/child` and return its actor id."
  []
  (rf/dispatch-sync [:p/parent [:go]])
  (some #(when (str/starts-with? (name %) "kid#") %)
        (keys (get-in (rf/frame-state-value :rf/default)
                      [:rf.db/runtime :rf.runtime/machines :snapshots]))))

(deftest a-plain-event-destroying-a-machine-resolves-the-machine
  (reg-machines!)
  (rf/reg-event :p/teardown (fn [_ _] {:fx [[:rf.machine/destroy :p/door]]}))
  (rf/dispatch-sync [:p/door [:rf.machine/start]])
  (rf/dispatch-sync [:p/teardown])
  (let [action (row (cascade-of (last-record)) :action)]
    (is (= [:destroy-exit :bye :p/door] ((juxt :phase :action-id :machine-id) action))
        "the teardown's cascade runs the door's exit action")
    (is (= :p/door (:machine-type action))
        "the row names the door, the singleton being its own type")
    (is (string? (action-source (:machine-type action) :bye))
        "the action's source is captured under that type")
    (is (nil? (:rf/machine (rf/handler-meta {:kind :event :id :p/teardown})))
        "control: the handler's own id is no machine, so it cannot be the key")))

(deftest a-spawned-actors-own-event-resolves-its-type
  (reg-machines!)
  (let [actor (spawn-child!)]
    (rf/dispatch-sync [actor [:bump]])
    (let [record (last-record)
          action (row (cascade-of record) :action)]
      (is (= [:bump actor] ((juxt :action-id :machine-id) action))
          "the row addresses the live instance")
      (is (= :p/child (:machine-type action))
          "and names its registered type")
      (is (string? (action-source :p/child :bump))
          "the source is captured under the type")
      (is (nil? (rf/handler-meta {:kind :event :id actor}))
          "control: the actor id names no registration")
      (is (= (cascade-of record)
             (proj/machine-cascade-rows (:trace-events record)
                                        (proj/machine-actor-types record)))
          "the Machine Inspector's projection of the same record yields the same rows"))))

(deftest a-spawned-actor-with-no-snapshot-trace-resolves-its-type
  (testing "a cascade whose traces carry no snapshot still resolves the type
            off the record's frame state"
    (reg-machines!)
    (let [actor (spawn-child!)]
      (rf/dispatch-sync [actor [:blocked]])
      (is (= :p/child (:machine-type (row (cascade-of (last-record)) :guard)))
          "a guard-blocked no-op reads the type off the state before the event")
      (rf/reg-event :p/kill (fn [_ _] {:fx [[:rf.machine/destroy actor]]}))
      (rf/dispatch-sync [:p/kill])
      (let [action (row (cascade-of (last-record)) :action)]
        (is (= [:destroy-exit actor] ((juxt :phase :machine-id) action))
            "a plain event destroying the actor runs its exit action")
        (is (= :p/child (:machine-type action))
            "and the actor the event destroyed still resolves its type")))))

(deftest machine-actor-types-maps-only-spawned-actors
  (reg-machines!)
  (rf/dispatch-sync [:p/door [:rf.machine/start]])
  (let [actor (spawn-child!)]
    (is (= {actor :p/child} (proj/machine-actor-types (last-record)))
        "the spawned actor maps to its type; the singletons carry no type slot")))
