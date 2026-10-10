(ns re-frame.bench.fresco.lane-control-strict-cljs-test
  "THE LANE'S TWO CONTROL RULES MUST STAY TWO.

  `rf.bench.fresco.lane/control-verdict` adjudicates a positive control on
  OVERLAP — right for legs on Chrome's 100 µs clamp, where a low round is
  the quantum. `control-verdict-strict` requires EVERY ROUND inside the
  band — right for batched windows clear of the quantum, which is what
  `amp_merge_clock_app` and `direct_return_clock_app` run. The design
  rests on [[one-dataset-two-rules-opposite-verdicts]]: a pass that routed
  those instruments to the overlap rule goes red there.

  Companion to `walk_profile_control_cljs_test`, which pins the same
  every-round discipline for the walk profile's own control."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

;; `:ctl-2x` performs the judged operation twice per window, so the
;; prediction is 2.00x; ±25% puts the band at [1.5 – 2.5].
(def ^:private predicted 2.0)
(def ^:private slack 0.25)

(defn- strict [per-round] (rf.bench.fresco.lane/control-verdict-strict predicted per-round slack))

(def ^:private held [2.037 1.965 2.104 1.988 2.012])

(def ^:private one-bad-round
  "Round 4 at 1.40, below the band's 1.5 floor."
  [2.037 1.965 2.104 1.400 2.012])

(deftest one-dataset-two-rules-opposite-verdicts
  ;; The overlap rule passes the range — a good round vouches for a bad
  ;; one — and the every-round rule refuses it; each answer names its rule.
  (is (= [[:overlap true] [:every-round false]]
         [((juxt :rule :ok?) (rf.bench.fresco.lane/control-verdict
                               predicted {:min 1.400 :max 2.104 :mean 1.904} slack))
          ((juxt :rule :ok?) (strict one-bad-round))])))

(deftest every-round-inside-passes
  ;; The per-round values are carried so a strict verdict can be
  ;; re-adjudicated without re-running the window.
  (is (= {:ok? true :band [1.5 2.5] :outside [] :per-round held
          :measured {:n 5 :min 1.965 :max 2.104 :p50 2.012}}
         (-> (strict held)
             (select-keys [:ok? :band :outside :per-round :measured])
             (update :measured select-keys [:n :min :max :p50]))))
  (is (true? (:ok? (strict [1.5 2.5 2.0])))
      "the band is inclusive at both edges"))

(deftest one-bad-round-refuses-and-names-it
  ;; The round, its value, and its distance past the edge as a fraction of
  ;; the prediction — below the floor and above the roof.
  (is (= [[{:round 4 :measured 1.4 :off-by -0.05}]
          [{:round 4 :measured 3.0 :off-by 0.25}]]
         (mapv (comp :outside strict) [one-bad-round [2.0 2.0 2.0 3.0 2.0]]))))

(deftest the-refusal-is-attributed-to-the-right-half
  ;; No rounds, or a prediction of zero (whose band any reading clears),
  ;; is an unstated control; a real prediction the arms missed is stated.
  (is (= [[false false] [false false] [false true]]
         (mapv (juxt :ok? :stated?)
               [(strict [])
                (rf.bench.fresco.lane/control-verdict-strict 0.0 held slack)
                (strict one-bad-round)]))))
