(ns re-frame.machines-always-cljs-test
  "CLJS-side coverage for `:always` microsteps under the Reagent reactive
  substrate.

  Mirrors the conformance fixtures
  ../spec/conformance/fixtures/always-single-microstep.edn (single guarded
  fire, atomic commit) and always-depth-exceeded.edn (cycle hits the bounded
  depth limit).

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-always-cljs
  (testing ":always fires once after the resolving event under a true guard"
    (let [machine
          {:initial :asking
           :data    {:correct-count 9}
           :guards  {:enough? (fn [data _]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [data _]
                                {:data {:correct-count
                                        (inc (:correct-count data))}})}
           :states
           {:asking {:always [{:guard :enough? :target :winner}]
                     :on     {:answer-correct {:action :count}}}
            :winner {}
            :loser  {}}}]
      (rf/reg-machine :quiz/flow machine)
      ;; Initial state :asking with :data {:correct-count 9} synthesised
      ;; on first dispatch — no seed needed.
      ;; Pre-condition: count is 9; one :answer-correct ticks to 10; :always
      ;; guard becomes true; microstep transitions to :winner.
      (rf/dispatch-sync [:quiz/flow [:answer-correct]])
      (let [s (snapshot :quiz/flow)]
        (is (= :winner (:state s))
            "external observer sees only the macrostep — landed at :winner")
        (is (= 10 (get-in s [:data :correct-count]))
            "the action's data update is committed alongside the :always target"))))

  (testing ":always doesn't fire when the guard is false"
    (let [machine
          {:initial :asking
           :data    {:correct-count 5}
           :guards  {:enough? (fn [data _]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [data _]
                                {:data {:correct-count
                                        (inc (:correct-count data))}})}
           :states
           {:asking {:always [{:guard :enough? :target :winner}]
                     :on     {:answer-correct {:action :count}}}
            :winner {}}}]
      (rf/reg-machine :quiz2/flow machine)
      (rf/dispatch-sync [:quiz2/flow [:answer-correct]])
      (let [s (snapshot :quiz2/flow)]
        (is (= :asking (:state s))
            "guard false — microstep loop terminates with zero microsteps")
        (is (= 6 (get-in s [:data :correct-count]))
            "action ran; data updated; state unchanged"))))

  (testing ":always cycle hits depth limit — snapshot rolls back atomically"
    ;; Two states ping-pong via :always with always-true guards. The
    ;; microstep loop trips the depth limit; per Spec 005 §Bounded depth
    ;; the snapshot rolls back to the input.
    (let [machine
          {:initial :start
           :data    {}
           :always-depth-limit 5
           :guards  {:p? (fn [_ _] true)}
           :states
           {:start {:on {:go {:target :a}}}
            :a     {:always [{:guard :p? :target :b}]}
            :b     {:always [{:guard :p? :target :a}]}}}
          traces (atom [])]
      (rf/reg-machine :osc/flow machine)
      (rf/register-trace-cb! ::osc (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:osc/flow [:go]])
      (rf/remove-trace-cb! ::osc)
      ;; Atomic rollback: external snapshot stays at :start.
      (is (= :start (:state (snapshot :osc/flow)))
          "macrostep is atomic; cycle aborts; snapshot rolls back to input")
      (is (some (fn [ev]
                  (and (= :rf.error/machine-always-depth-exceeded
                          (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/machine-always-depth-exceeded trace"))))
