(ns re-frame.machines-always-cljs-test
  "CLJS-side coverage for `:always` microsteps under the Reagent reactive
  substrate.

  Mirrors the conformance fixture
  ../spec/conformance/fixtures/always-single-microstep.edn (single guarded
  fire, atomic commit). The depth-limited `:always` cycle runs on both hosts
  in machine-depth-abort-failure-cljs-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

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
            "action ran; data updated; state unchanged")))))

(deftest machine-always-bare-keyword-shorthand-cljs
  (testing "a bare-keyword :always (the :on / :after keyword-target shorthand)
            registers cleanly and desugars to {:target <kw>} — no
            raw platform throw (assoc on a keyword) at the first macrostep,
            and the eventless transition fires unconditionally on entry"
    (let [machine
          {:initial :asking
           :data    {}
           :states
           {:asking  {:always :sibling}
            :sibling {}}}]
      (rf/reg-machine :quiz4/flow machine)
      ;; The bare-keyword :always is unconditional (no :guard) — it settles
      ;; during the SAME birth macrostep, before the creation kick's
      ;; dispatch-sync returns. :asking is never externally observed.
      (rf/dispatch-sync [:quiz4/flow [:rf.machine/start]])
      (let [s (snapshot :quiz4/flow)]
        (is (= :sibling (:state s))
            "the bare-keyword :always shorthand desugars to {:target :sibling}
             and settles at the first (birth) macrostep")))))
