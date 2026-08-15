(ns re-frame.bench.hicasso.lane-control-strict-cljs-test
  "THE LANE'S TWO CONTROL RULES MUST STAY TWO — pinned (rf2-egdaq).

  `lane/control-verdict` adjudicates a positive control on OVERLAP: the
  measured range need only meet the ±slack band. `lane/control-verdict-
  strict` requires EVERY ROUND inside it. Both are correct for their own
  case — the 2026-07-31 ruling keeps overlap for legs sitting on Chrome's
  100 µs `performance.now()` clamp, where a low round is the quantum
  rather than a defect, and names a batched window clear of the quantum
  as the condition under which the strict rule becomes adoptable.

  The failure this file exists to prevent is a later worker collapsing
  the two into one, in either direction. So the assertion that carries the
  design is [[one-dataset-two-rules-opposite-verdicts]]: the SAME readings
  are driven through both rules and the two must disagree. A `simplify`
  pass that routes `amp_merge_clock_app` back to the overlap rule goes red
  here, in the always-on `cljs-test$` gate, rather than in a bench run
  months later that quietly stopped having teeth.

  The rest pin the modes a browser run cannot reach. A control adjudicated
  on an AGGREGATE cannot be re-adjudicated afterwards — that is the
  durability hole rf2-egdaq's audit of PR #8326 found, and it is why the
  strict answer carries `:per-round`. A control whose own prediction has
  gone vacuous passes on any reading whatever — the walk profile shipped
  exactly that (`rf2-1huc`, merged-PR audit #8149), and no mutation of a
  measured arm can find it.

  Companion to `walk_profile_control_cljs_test`, which pins the same
  every-round discipline for the walk profile's own bespoke control."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as lane]))

;; ---------------------------------------------------------------------------
;; The shape `amp_merge_clock_app` hands the rule
;; ---------------------------------------------------------------------------
;;
;; `:ctl-2x` performs `:expanded`'s own operation twice inside one window,
;; so the prediction is 2.00x by construction and each round is divided by
;; its OWN denominator. Five rounds, ±25%, so the band is [1.5 – 2.5].

(def ^:private predicted 2.0)
(def ^:private slack 0.25)

(defn- strict [per-round] (lane/control-verdict-strict predicted per-round slack))

(def ^:private held
  "Five rounds a real instrument with signal produces."
  [2.037 1.965 2.104 1.988 2.012])

(def ^:private one-bad-round
  "Four good rounds and a fourth-round excursion to 1.40 — 30% below the
  prediction, outside the band's 1.5 floor."
  [2.037 1.965 2.104 1.400 2.012])

;; ---------------------------------------------------------------------------
;; The assertion that carries the design
;; ---------------------------------------------------------------------------

(deftest one-dataset-two-rules-opposite-verdicts
  (testing "ONE set of readings, BOTH rules, and they must part — this is
            the whole of rf2-egdaq and the reason the lane carries two"
    (let [s (strict one-bad-round)
          ;; The same five rounds offered to the overlap rule as the
          ;; `{:min :max :mean}` range it takes.
          o (lane/control-verdict predicted
                                  {:min 1.400 :max 2.104 :mean 1.904}
                                  slack)]
      (is (true? (:ok? o))
          "the overlap rule PASSES it — the range meets the band, so a good
           round vouches for a bad one")
      (is (false? (:ok? s))
          "the every-round rule REFUSES it, which is the whole difference")
      (is (= :overlap (:rule o)))
      (is (= :every-round (:rule s))
          "and each answer says which rule decided it, so a published record
           cannot be read under the other one"))))

;; ---------------------------------------------------------------------------
;; The rule itself
;; ---------------------------------------------------------------------------

(deftest every-round-inside-passes
  (testing "a control with signal in every round"
    (let [r (strict held)]
      (is (true? (:ok? r)))
      (is (= [1.5 2.5] (:band r)))
      (is (empty? (:outside r)))
      (is (re-find #"EVERY round" (:why r))))))

(deftest one-bad-round-refuses-and-names-it
  (testing "an operator told only FAILED goes looking at the arms, where
            four rounds out of five are healthy"
    (let [r (strict one-bad-round)]
      (is (false? (:ok? r)))
      (is (= [{:round 4 :measured 1.4 :off-by -0.05}] (:outside r))
          "the round, its value, and how far past the edge it sat as a
           fraction of the prediction")
      (is (re-find #"round 4" (:why r))))))

(deftest a-high-excursion-refuses-too
  (testing "the band is two-sided: a control reading 3.0x against a 2.0x
            prediction has not confirmed the instrument, it has found
            something else"
    (let [r (strict [2.037 3.000 1.988])]
      (is (false? (:ok? r)))
      (is (= [2] (mapv :round (:outside r))))
      (is (= 0.25 (:off-by (first (:outside r))))))))

(deftest the-edges-are-inside
  (testing "the band is inclusive at both edges — a rule that refused its
            own stated tolerance would be a different tolerance"
    (is (true? (:ok? (strict [1.5 2.5 2.0]))))))

;; ---------------------------------------------------------------------------
;; What the audit of PR #8326 found: the answer must be re-adjudicable
;; ---------------------------------------------------------------------------

(deftest the-per-round-values-are-carried-into-the-answer
  (testing "the three runs published on 2026-08-15 recorded only an
            aggregate, so their strict verdict is unrecoverable and the
            window would have to be re-run to ask a question its own data
            had already answered"
    (let [r (strict held)]
      (is (= held (:per-round r)))
      (is (= 5 (:n (:measured r))))
      (is (= 2.012 (:p50 (:measured r))))
      (is (= 1.965 (:min (:measured r))))
      (is (= 2.104 (:max (:measured r)))))))

;; ---------------------------------------------------------------------------
;; A control that cannot go red is not a control
;; ---------------------------------------------------------------------------

(deftest no-rounds-refuses
  (testing "a control with no data has not passed"
    (let [r (strict [])]
      (is (false? (:ok? r)))
      (is (false? (:stated? r)))
      (is (re-find #"no rounds" (:why r))))))

(deftest a-vacuous-prediction-refuses-however-good-the-rounds-look
  (testing "the mode merged-PR audit #8149 found on the walk profile: a
            band around a prediction of zero is cleared by any reading
            whatever, so passing there is passing on nothing"
    (let [r (lane/control-verdict-strict 0.0 [0.0 0.0 0.0] slack)]
      (is (false? (:ok? r)))
      (is (false? (:stated? r)))
      (is (re-find #"states no prediction" (:why r)))))
  (testing "and a NEGATIVE prediction inverts the band rather than
            narrowing it, which is on its own enough to refuse"
    (is (false? (:ok? (lane/control-verdict-strict -2.0 [-2.0 -2.0] slack))))))

(deftest the-refusal-is-attributed-to-the-right-half
  (testing "two refusals, two causes, two repairs — the arms are innocent
            in one of them"
    (let [vacuous (lane/control-verdict-strict 0.0 held slack)
          missed  (strict one-bad-round)]
      (is (false? (:stated? vacuous)) "the PREDICTION failed")
      (is (true? (:stated? missed)) "the prediction was real; the ARMS missed it"))))
