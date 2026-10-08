(ns re-frame.root-spawn-cofx-test
  "A guard declaring `:rf.cofx/requires` on the machine root's `:spawn :on-error`
  or transition-shaped `:on-done` joins the ensure-set for a carrier from the
  root's child (invoke-id `[]`), flat and parallel alike."
  (:require [clojure.test :refer [are deftest use-fixtures]]
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
  (are [ids spawn event] (= ids (ensured-ids (flat spawn) flat-snap event))
    #{:test/roll9} {:machine-id :x/kid :on-error {:target :errored :guard :g}} [:rf.machine.spawn/error [] {:boom 1} 1]
    #{:test/roll9} {:machine-id :x/kid :on-done {:target :done :guard :g}}     [:rf.machine.spawn/done [] {:ok 1} 1]
    ;; control: a carrier naming the state's child does not pull in the root's slot
    #{}            {:machine-id :x/kid :on-error {:target :errored :guard :g}} [:rf.machine.spawn/error [:working] {:boom 1} 1]
    ;; control: a fn :on-done is the :data fold and contributes nothing
    #{}            {:machine-id :x/kid :on-done (fn [data _] data)}            [:rf.machine.spawn/done [] {:ok 1} 1]))

(deftest parallel-root-spawn-carrier-guards-are-ensured
  (are [ids spawn event] (= ids (ensured-ids (parallel spawn) par-snap event))
    #{:test/roll9} {:machine-id :x/kid :on-error {:target [:x :x2] :guard :g}} [:rf.machine.spawn/error [] {:boom 1} 1]
    #{:test/roll9} {:machine-id :x/kid :on-done {:target [:x :x2] :guard :g}}  [:rf.machine.spawn/done [] {:ok 1} 1]
    ;; control: a carrier naming a region state's child does not pull in the root's slot
    #{}            {:machine-id :x/kid :on-error {:target [:x :x2] :guard :g}} [:rf.machine.spawn/error [:x :x1] {:boom 1} 1]))
