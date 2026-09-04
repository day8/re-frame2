(ns re-frame.bench.hicasso.lane-schedule-async-cljs-test
  "ONE SCHEDULE, TWO LOOPS (rf2-xa8wo).

  [[re-frame.bench.hicasso.lane/rounds-async!]] exists because a window
  that ends at a PAINT cannot close inside one synchronous call, and
  [[re-frame.bench.hicasso.lane/rounds!]] takes a number back. It is a
  second driver over the same plan, and the failure mode of a second
  driver is the one this lane has already paid for twice: `slot-order`'s
  `k = 2` degeneracy survived a fix to its own sibling because
  `b6-harness` held a copy of the rule, and `rf.bench.hicasso.lane/observe!`'s missing call
  was repaired privately in two hand-rolled loops while the ten apps
  riding the shared one kept the fault.

  So the two loops are not argued to agree. They are RUN AGAINST EACH
  OTHER, on one deterministic stub, and the banked samples and readings
  are asserted `=`.

  ## Why the stub answers its own execution index

  The same reason `lane-schedule-cljs-test`'s does, and deliberately the
  same stub shape: a value that encodes WHERE IN THE TRUE EXECUTION
  SEQUENCE the call happened turns *did the two loops visit the arms in
  the same order?* into an equality over recorded data, with no second
  model of `slot-order` to be wrong in the same way as the first.

  ## Anti-vacuity

  [[the-two-loops-really-do-visit-something]] runs first. Two empty
  answers are `=` and would carry the whole file green over a
  `rounds-async!` that measured nothing at all — which is exactly what a
  promise chain that dropped its tail would produce."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]))

(def ^:private sampling
  "`lane-schedule-cljs-test`'s numbers, carried rather than chosen: this
  file's claim is invariant to them, and matching its sibling keeps the
  two readable side by side."
  {:warmup 3 :samples 6})

(def ^:private rounds 5)

(def ^:private arm-counts
  "Every arm count this lane's harnesses run at, plus the four
  `slice-echo-clock-app` runs. `2` is in for its own sake: `slot-order`
  drops the reflection at `k = 2` because it cancels the rotation, and a
  loop that walked the plan wrongly would be most likely to show it at
  the degenerate count."
  [2 4 5 7 8])

(defn- arms [n]
  (mapv (fn [i] {:id (keyword (str "arm-" i))}) (range n)))

(defn- sync-run
  "`rounds!` over `n` arms, answering `{:samples :readings :truth}` where
  `truth` is every execution in order — warm-up and measured alike."
  [n]
  (let [truth (atom [])
        out   (rf.bench.hicasso.lane/rounds! (arms n) sampling rounds
                            (fn [arm]
                              (let [i (count @truth)]
                                (swap! truth conj (name (:id arm)))
                                i)))]
    (assoc out :truth @truth)))

(defn- async-run
  "`rounds-async!` over the same stub, one microtask late so the answer is
  a genuine promise rather than a value in promise clothing.

  `:overlaps` counts visits that began while another was still in flight.
  A loop that fanned the plan out with `Promise.all` would produce the
  same readings and be a different instrument entirely — every arm
  measuring beside its siblings, which is the premise the arm-order guard
  adjudicates under."
  [n]
  (let [truth    (atom [])
        in-fl    (atom 0)
        overlaps (atom 0)]
    (.then (rf.bench.hicasso.lane/rounds-async! (arms n) sampling rounds
                               (fn [arm]
                                 (when (pos? @in-fl) (swap! overlaps inc))
                                 (swap! in-fl inc)
                                 (let [i (count @truth)]
                                   (swap! truth conj (name (:id arm)))
                                   (.then (js/Promise.resolve nil)
                                          (fn [_] (swap! in-fl dec) i)))))
           (fn [out] (assoc out :truth @truth :overlaps @overlaps)))))

;; ---------------------------------------------------------------------------
;; Anti-vacuity, first
;; ---------------------------------------------------------------------------

(deftest the-two-loops-really-do-visit-something
  (testing "Two empty answers are `=`. Unless the async loop actually
           executes the whole plan, every equality below is an assertion
           about nothing."
    (async done
      (let [n 4]
        (.then (async-run n)
               (fn [a]
                 (is (= (* rounds (+ (:warmup sampling) (:samples sampling)) n)
                        (count (:truth a)))
                     "every visit in the plan ran")
                 (is (= (* rounds (:samples sampling) n) (count (:samples a)))
                     "and the measured ones were banked")
                 (done)))))))

;; ---------------------------------------------------------------------------
;; The claim
;; ---------------------------------------------------------------------------

(deftest the-async-loop-walks-the-synchronous-loops-plan
  (testing "Same visits, same order, same warm-up boundary — asserted
           over the recorded execution rather than re-derived from
           `slot-order`, which would be a second copy of the rule under
           test."
    (async done
      (.then
        (rf.bench.hicasso.lane/chain nil arm-counts
                    (fn [_ n]
                      (.then (async-run n)
                             (fn [a]
                               (let [s (sync-run n)]
                                 (is (= (:truth s) (:truth a))
                                     (str "arm count " n ": the true execution order"))
                                 (is (= (:samples s) (:samples a))
                                     (str "arm count " n ": every banked sample, with its "
                                          ":predecessor and :position"))
                                 (is (= (:readings s) (:readings a))
                                     (str "arm count " n ": the per-round readings")))
                               nil))))
        (fn [_] (done))))))

(deftest the-async-loop-keeps-the-visits-serial
  (testing "`chain` starts a visit only once the previous one has
           resolved, so an arm still measures with no sibling running
           beside it."
    (async done
      (.then (async-run 5)
             (fn [a]
               (is (zero? (:overlaps a))
                   "no visit began while another was still in flight")
               (done))))))

(deftest a-plain-value-from-measure-one-is-accepted
  (testing "`js/Promise.resolve` on the arm's answer, so a caller whose
           window happens to close synchronously — a floor arm, a plumb
           tare — needs no wrapper of its own."
    (async done
      (let [as (arms 3)]
        (.then (rf.bench.hicasso.lane/rounds-async! as sampling rounds (fn [_] 1.0))
               (fn [{:keys [samples readings]}]
                 (is (= (* rounds (:samples sampling) 3) (count samples)))
                 (is (every? (fn [round]
                               (every? (fn [[_ xs]] (= (:samples sampling) (count xs)))
                                       round))
                             readings))
                 (done)))))))
