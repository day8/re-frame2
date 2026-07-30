(ns re-frame.freehand.bench.order-schedule-cljs-test
  "rf2-ouwh8 — the arm-order SCHEDULE, priced at the three arm counts that
  matter, and pinned to ONE authority.

  ## What this file exists to stop

  The rule 'rotate, then reflect on odd indices' was written out three
  times: `re-frame.bench.order-guard/slot-order`, `b6-harness/slot-order`
  and `order_guard.cjs`'s `schedule`. All three carried the same defect,
  and it is the defect a restated method always eventually has — reversing
  a PAIR is rotating it by one, so at `k = 2` the two operations cancel and
  the schedule returns `[0 1]` at every index. A two-arm plan therefore ran
  in ONE ORDER for ever, which is the single-order result the guard exists
  to refuse; it duly refused four rows with `only 1 stratum — the question
  was never asked`.

  Two of the three copies are structurally forced — a Node driver owning a
  Chromium page cannot consume ClojureScript, and `implementation/core` may
  not require out of `implementation/freehand` — and their shared self-test
  fixtures hold them in step. The third was not forced, and is now a `def`
  onto the guard. This suite is the other end of that: it asserts the
  ClojureScript harnesses hold the guard's own function rather than a
  lookalike, so a future restatement fails here rather than in a published
  row.

  ## Why the assertions are about ORDERS and STRATA, not about a fix

  A repair that satisfies the guard without demonstrably varying the order
  is the same defect wearing a passing verdict. So the checks below count
  the DISTINCT ORDERS a schedule produces and the PREDECESSOR STRATA the
  guard can then partition on, at `k = 2`, `3` and `4`, and they carry the
  degenerate schedule alongside so the contrast is measured rather than
  asserted."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.freehand.bench.b6-harness :as h]))

(defn- always-reflect
  "The schedule as it was: rotate forward, then reflect on odd indices at
  EVERY arm count. Kept here so the defect is reproducible rather than
  merely described."
  [k s]
  (let [xs (mapv #(mod (+ % s) k) (range k))]
    (if (odd? s) (vec (rseq xs)) xs)))

(def ^:private rounds 6)

(defn- distinct-orders [sched k]
  (count (set (map #(sched k %) (range rounds)))))

(defn- strata-per-arm
  "Predecessor strata per arm, as the guard counts them, over `rounds`
  rounds run back to back in one process. The seams are part of the run —
  the last arm of a round really does precede the first of the next — and
  at `k = 2` they are the ONLY adjacencies there are, since a round of two
  contains exactly one."
  [sched k]
  (->> (guard/adjacency k rounds sched)
       :arms
       (sort-by key)
       (mapv (comp :distinct val))))

;; ---------------------------------------------------------------------------
;; One authority
;; ---------------------------------------------------------------------------

(deftest the-cljs-harnesses-hold-the-guards-own-schedule
  (testing "Identity, not equivalence. `=` on two functions that agree
            everywhere tested would pass for a lookalike that drifts at
            some `k` nobody thought to test — which is precisely how this
            bug survived a fix to one copy. `identical?` cannot."
    (is (identical? guard/slot-order h/slot-order)
        "b6-harness must hold order-guard's schedule, not a restatement of it")
    (is (identical? guard/slot-order lane/slot-order)
        "the Hicasso lane must hold order-guard's schedule, not a restatement of it")))

;; ---------------------------------------------------------------------------
;; k = 2 — where the reflection cancels
;; ---------------------------------------------------------------------------

(deftest at-two-arms-the-old-schedule-ran-one-order-for-ever
  (testing "The defect, reproduced. Rotating a pair by one IS reversing it,
            so composing the two is the identity and every index yields the
            same slot vector."
    (is (= [[0 1] [0 1] [0 1] [0 1] [0 1] [0 1]]
           (mapv #(always-reflect 2 %) (range rounds))))
    (is (= 1 (distinct-orders always-reflect 2)))
    (is (some #{1} (strata-per-arm always-reflect 2))
        "an arm with ONE predecessor stratum is what the guard calls :unchecked")))

(deftest at-two-arms-slot-order-alternates
  (testing "Both of the orders two arms have, alternating on the index."
    (is (= [[0 1] [1 0] [0 1] [1 0] [0 1] [1 0]]
           (mapv #(guard/slot-order 2 %) (range rounds))))
    (is (= 2 (distinct-orders guard/slot-order 2)))
    (is (every? #(>= % 2) (strata-per-arm guard/slot-order 2))
        "every arm must reach at least two predecessor strata for the question to be asked")))

(deftest at-two-arms-the-guard-stops-refusing-a-balanced-plan
  (testing "The stratum count is the point, and the verdict is what a driver
            acts on. The same synthetic plan — one flat reading per sample,
            so nothing but the ORDER differs between the two runs — is
            REFUSED under the old schedule and reportable under the new."
    (let [plan (fn [sched k]
                 (:samples
                   (reduce (fn [acc s]
                             (reduce (fn [{:keys [pos prev samples]} j]
                                       {:pos     (inc pos)
                                        :prev    (str "A" j)
                                        :samples (conj samples
                                                       {:arm         (str "A" j)
                                                        :value       1.0
                                                        :predecessor prev
                                                        :position    pos})})
                                     acc
                                     (sched k s)))
                           {:pos 0 :prev nil :samples []}
                           (range rounds))))
          before (guard/verdict (plan always-reflect 2) {:tolerance 0.10})
          after  (guard/verdict (plan guard/slot-order 2) {:tolerance 0.10})]
      (is (:unchecked? before) "the old schedule leaves the order question unasked")
      (is (:refuse? before)    "and an unasked question refuses as loudly as a failed one")
      (is (not (:unchecked? after)))
      (is (not (:refuse? after))
          "every reading here is 1.0, so a refusal after the repair could only be the plan"))))

;; ---------------------------------------------------------------------------
;; k = 3 and k = 4 — unchanged, and that is an assertion
;; ---------------------------------------------------------------------------

(deftest above-two-arms-the-schedule-is-untouched
  (testing "The repair is confined to `k = 2`. Three and four arms must come
            back the schedule the published multi-arm rows were measured
            on — if they did not, this change would have moved a measuring
            device under numbers already in the studio."
    (doseq [k [3 4]]
      (is (= (mapv #(always-reflect k %) (range rounds))
             (mapv #(guard/slot-order k %) (range rounds)))
          (str "k=" k " must be byte-identical to the pre-repair schedule")))
    (is (= 6 (distinct-orders guard/slot-order 3)))
    (is (= 4 (distinct-orders guard/slot-order 4)))
    (is (every? #(>= % 2) (strata-per-arm guard/slot-order 3)))
    (is (every? #(>= % 2) (strata-per-arm guard/slot-order 4)))))

;; ---------------------------------------------------------------------------
;; The self-test travels with the harness
;; ---------------------------------------------------------------------------

(deftest the-shared-self-test-passes-in-this-runtime
  (testing "`guard/self-test` is what every harness runs before it measures
            anything, and it is the fixture set that holds the ClojureScript
            copy of the rule in step with `order_guard.cjs`'s. Running it
            here means a drift is a red suite rather than a refused run
            three hours into a measurement."
    (let [st (guard/self-test)]
      (is (:ok? st)
          (str "failing checks: "
               (pr-str (mapv :name (remove :ok (:checks st)))))))))
