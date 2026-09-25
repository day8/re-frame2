(ns re-frame.root-spawn-cofx-test
  "Consumer attachment covers the machine root's own `:spawn` carriers.

  A carrier from the root's child (`[:rf.machine.spawn/error [] …]` /
  `[:rf.machine.spawn/done [] …]`) selects the root `:spawn :on-error` /
  transition-shaped `:on-done` (Spec 005 §The machine root), so a named guard
  there declaring `:rf.cofx/requires` must join the ensure-set exactly as the
  same guard on a state's `:spawn` does — flat and parallel alike.

  Controls: the state-level `:spawn :on-error` still contributes; a carrier
  naming a state's child does not pull in the root's slot; a fn `:on-done` (the
  `:data` fold) contributes nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private rolled-six
  {:rf.cofx/requires [:test/roll9]
   :fn (fn [{cofx :rf.cofx}] (= 6 (:test/roll9 cofx)))})

(defn- ensured-ids [m snap event]
  (set (map :id (rf.machines.cofx-attach/ensure-set-for
                  (rf.machines.cofx-attach/index-ensure-sets m) snap event))))

(defn- flat [spawn]
  {:initial :working
   :guards  {:g rolled-six}
   :spawn   spawn
   :states  {:working {:spawn {:machine-id :x/state-child}}
             :errored {}
             :done    {}}})

(defn- parallel [spawn]
  {:type    :parallel
   :guards  {:g rolled-six}
   :spawn   spawn
   :regions {:x {:initial :x1 :states {:x1 {} :x2 {}}}
             :y {:initial :y1 :states {:y1 {}}}}})

(def ^:private flat-snap {:state :working :data {}})
(def ^:private par-snap {:state {:x :x1 :y :y1} :data {}})

(deftest flat-root-spawn-carrier-guards-are-ensured
  (rf/reg-cofx :test/roll9 {:recordable? true} (fn [] 6))
  (testing "CASE: the guard on the root :spawn :on-error"
    (is (contains? (ensured-ids (flat {:machine-id :x/kid :on-error {:target :errored :guard :g}})
                                flat-snap [:rf.machine.spawn/error [] {:boom 1} 1])
                   :test/roll9)))
  (testing "CASE: the guard on the root :spawn's transition-shaped :on-done"
    (is (contains? (ensured-ids (flat {:machine-id :x/kid :on-done {:target :done :guard :g}})
                                flat-snap [:rf.machine.spawn/done [] {:ok 1} 1])
                   :test/roll9)))
  (testing "CONTROL: a carrier naming the state's child does not pull in the root's slot"
    (is (not (contains? (ensured-ids (flat {:machine-id :x/kid :on-error {:target :errored :guard :g}})
                                     flat-snap [:rf.machine.spawn/error [:working] {:boom 1} 1])
                        :test/roll9))))
  (testing "CONTROL: a fn :on-done is the :data fold and contributes nothing"
    (is (empty? (ensured-ids (flat {:machine-id :x/kid :on-done (fn [data _] data)})
                             flat-snap [:rf.machine.spawn/done [] {:ok 1} 1])))))

(deftest state-spawn-carrier-guard-is-still-ensured
  (rf/reg-cofx :test/roll9 {:recordable? true} (fn [] 6))
  (is (contains? (ensured-ids {:initial :working
                               :guards  {:g rolled-six}
                               :states  {:working {:spawn {:machine-id :x/kid
                                                           :on-error {:target :errored :guard :g}}}
                                         :errored {}}}
                              flat-snap [:rf.machine.spawn/error [:working] {:boom 1} 1])
                 :test/roll9)))

(deftest parallel-root-spawn-carrier-guards-are-ensured
  (rf/reg-cofx :test/roll9 {:recordable? true} (fn [] 6))
  (testing "CASE: the guard on the parallel root :spawn :on-error"
    (is (contains? (ensured-ids (parallel {:machine-id :x/kid :on-error {:target [:x :x2] :guard :g}})
                                par-snap [:rf.machine.spawn/error [] {:boom 1} 1])
                   :test/roll9)))
  (testing "CASE: the guard on the parallel root :spawn's transition-shaped :on-done"
    (is (contains? (ensured-ids (parallel {:machine-id :x/kid :on-done {:target [:x :x2] :guard :g}})
                                par-snap [:rf.machine.spawn/done [] {:ok 1} 1])
                   :test/roll9)))
  (testing "CONTROL: a carrier naming a region state's child does not pull in the root's slot"
    (is (not (contains? (ensured-ids (parallel {:machine-id :x/kid :on-error {:target [:x :x2] :guard :g}})
                                     par-snap [:rf.machine.spawn/error [:x :x1] {:boom 1} 1])
                        :test/roll9)))))
