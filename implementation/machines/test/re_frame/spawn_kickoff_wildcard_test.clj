(ns re-frame.spawn-kickoff-wildcard-test
  "The spawn kick-off `[:rf.machine.spawn/spawned]` resolves only through an
  exact clause: a spawned child's `:*` and `:rf.machine.spawn/*` wildcards are
  for its own events and never catch the framework's kick-off (Spec 005
  §Synthetic `[:rf.machine.spawn/spawned]` on spawn)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- child [on]
  {:initial :idle
   :data    {}
   :actions {:note (fn [{:keys [data event]}] {:data (assoc data :tripped-by event)})
             :boom (fn [_] (throw (ex-info "unknown event" {})))}
   :states  {:idle  {:on (merge {:go :next} on)}
             :next  {}
             :error {}}})

(defn- kid [] (select-keys (rf.machines.test-support/snapshot :kickoff/kid) [:state :data]))

(defn- spawn-kid!
  "Register `child-spec` as the child type and spawn it from a parent, with
  `start` as the spawn's `:start` when given. Returns the kid's snapshot."
  ([child-spec] (spawn-kid! child-spec nil))
  ([child-spec start]
   (rf/reg-machine :kickoff/child child-spec)
   (rf/reg-machine :kickoff/parent
     {:initial :idle
      :states  {:idle    {:on {:begin :working}}
                :working {:spawn (cond-> {:machine-id :kickoff/child :fixed-actor-id :kickoff/kid}
                                   start (assoc :start start))}}})
   (rf/dispatch-sync [:kickoff/parent [:begin]])
   (kid)))

(deftest a-total-wildcard-does-not-catch-the-kick-off
  (is (= {:state :idle :data {}} (spawn-kid! (child {:* {:target :error :action :note}})))))

(deftest the-reserved-namespace-wildcard-does-not-catch-the-kick-off
  (is (= {:state :idle :data {}}
         (spawn-kid! (child {:rf.machine.spawn/* {:target :error :action :note}})))))

(deftest a-fail-loudly-wildcard-raises-nothing-at-birth
  (spawn-kid! (child {:* {:action :boom}}))
  (is (= :idle (:state (kid))))
  (is (empty? (rf.machines.test-support/events-of :rf.error/machine-action-exception))))

(deftest an-exact-kick-off-clause-still-fires
  (is (= :next (:state (spawn-kid! (child {:rf.machine.spawn/spawned :next :* :error}))))))

(deftest a-spawn-start-event-reaches-the-child
  (is (= :next (:state (spawn-kid! (child {:* :error}) [:go])))))

(deftest the-wildcard-still-catches-the-childs-own-unknown-events
  (spawn-kid! (child {:* {:target :error :action :note}}))
  (rf/dispatch-sync [:kickoff/kid [:mystery]])
  (is (= {:state :error :data {:tripped-by [:mystery]}} (kid))))
