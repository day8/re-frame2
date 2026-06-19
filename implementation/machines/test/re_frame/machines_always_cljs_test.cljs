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
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support (rf2-3l8lqe finding #4)
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path. Inline trace
;; captures below keep their raw trace.tooling register/unregister.
(def ^:private snapshot mtest/snapshot)

(deftest machine-always-cljs
  (testing ":always fires once after the resolving event under a true guard"
    (let [machine
          {:initial :asking
           :data    {:correct-count 9}
           :guards  {:enough? (fn [{data :data}]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [{data :data}]
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
           :guards  {:enough? (fn [{data :data}]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [{data :data}]
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

  (testing ":always cycle hits depth limit — macrostep fails atomically (rf2-y3jv8q)"
    ;; Two states ping-pong via :always with always-true guards. The microstep
    ;; loop trips the depth limit; per Spec 005 §Bounded depth the macrostep
    ;; FAILS atomically — XState v5 throws on such a runaway (rf2-y3jv8q). The
    ;; abort routes through the failure path (the handler short-circuits to
    ;; `{}`), so no snapshot write reaches runtime-db and the already-committed
    ;; pre-event :start snapshot stays put. (Boot the machine FIRST so :start is
    ;; committed before the failing [:go] — otherwise a first-dispatch lazy
    ;; boot + [:go] is ONE atomic macrostep that rolls back wholesale, leaving
    ;; no snapshot installed at all.)
    (let [machine
          {:initial :start
           :data    {}
           :always-depth-limit 5
           :guards  {:p? (fn [_] true)}
           :states
           {:start {:on {:go {:target :a}}}
            :a     {:always [{:guard :p? :target :b}]}
            :b     {:always [{:guard :p? :target :a}]}}}
          traces (atom [])]
      (rf/reg-machine :osc/flow machine)
      (rf/dispatch-sync [:osc/flow [:rf.machine/start]])  ;; commit :start first
      (trace-tooling/register-listener! ::osc (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:osc/flow [:go]])
      (trace-tooling/unregister-listener! ::osc)
      ;; Atomic rollback: the committed snapshot stays at :start (the failing
      ;; [:go] macrostep commits nothing).
      (is (= :start (:state (snapshot :osc/flow)))
          "macrostep fails atomically; the committed snapshot stays at :start")
      ;; The runaway is a FAILED macrostep, NOT a benign no-op — the
      ;; depth-exceeded error trace fires, and no unhandled-no-op masks it.
      (is (some (fn [ev]
                  (and (= :rf.error/machine-always-depth-exceeded
                          (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/machine-always-depth-exceeded trace")
      (is (not-any? (fn [ev] (= :rf.machine.event/unhandled-no-op (:operation ev)))
                    @traces)
          "a runaway cycle is NOT a benign no-op — no unhandled-no-op fires"))))
