(ns re-frame.bench.fresco.walk-profile-control-cljs-test
  "THE WALK PROFILE'S POSITIVE CONTROL MUST REFUSE — pinned.

  `walk_profile_app`'s positive control sets `window.FRESCO_CONTROL_FAILED`,
  which is what makes `run.cjs`'s control exit path live for this arm.
  Without it a run whose ablations had stopped biting would print a full
  table and exit 0.

  A planted fault proves the exit path live: with `walk-parse` at
  `M-PARSE-RAW` swapped back to `codec/cached-parse`, the arm does exactly
  the work `local` does, `parse-raw`'s p50 collapses onto `local`'s, the
  per-round deltas go to ~0 with one NEGATIVE, and the driver exits 1.
  That proof costs an `:advanced` build and a Chromium run, so it is not
  one anybody repeats routinely.

  So the RULE is pinned here instead, over synthetic readings. What that
  buys is narrow and specific: the adjudication cannot be quietly
  weakened without going red in a suite that costs nothing.

  ## The one assertion that carries the design

  [[strict-rule-beats-overlap]] is the reason this file is not merely
  belt-and-braces. `rf.bench.fresco.lane/control-verdict`'s `:ok?` asks whether the
  measured range OVERLAPS the band; the walk profile's control asks
  whether EVERY ROUND clears the bar. The lane keeps one rule per
  instrument, not one rule for every arm: legs sitting on Chrome's
  100 µs `performance.now()` clamp are judged on overlap, and a window
  clear of the quantum on every round (`lane_control_strict_cljs_test`).
  This control takes the every-round rule, and it can, because it has no
  published row that a stricter rule would re-adjudicate.

  That test drives ONE dataset through BOTH rules and asserts they
  disagree on it: overlap passes, every-round refuses. A worker who
  \"simplifies\" the control down to `rf.bench.fresco.lane/control-verdict` therefore
  discovers it here, rather than in a bench run six months later that
  quietly stopped having teeth.

  ## The assertions the planted fault could not reach

  A control can also FAIL OPEN on its own prediction: the bar is
  `n-tags x (fresh - hit)` less slack, and unless that difference is
  required to be positive, converged primitives put the bar at zero
  where every reading clears it. A browser proof cannot
  find that — mutating the measured ARM leaves the micro table healthy —
  so [[a-converged-prediction-refuses-however-healthy-the-deltas-look]]
  and its siblings below pin it by arithmetic instead. That is the second
  reason this file exists and not merely belt-and-braces either."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.bench.fresco.walk-profile-app :as rf.bench.fresco.walk-profile-app]))

;; ---------------------------------------------------------------------------
;; Fixtures — the shapes `-main` actually hands the control
;; ---------------------------------------------------------------------------

(def ^:private walks-per-sample
  "`timed-walks` measures a window of K walks and the control divides by
  K, so a fixture states ms-per-walk and is scaled up here. Kept in step
  with the app's own constant by [[fixture-scaling-matches-the-app]]."
  8)

(defn- round
  "One round's readings from a map of `{arm ms-per-walk}`. Three identical
  samples, so each arm's p50 is exactly the number asked for and the
  fixture says what it means."
  [per-walk]
  (into {} (map (fn [[id ms]] [id (vec (repeat 3 (* ms walks-per-sample)))])) per-walk))

(def ^:private micro
  "A micro table with the two rows the prediction is built from. 100 ns of
  fresh-minus-cached over 1,000 tags is a floor of 0.1 ms/walk, so the
  bar at the control's 25% slack is 0.075."
  [[:cached-parse-hit 50.0] [:parse-tag-fresh 150.0]])

(def ^:private census {:native 1000})
(def ^:private roster (make-array 1000))

(def ^:private floor-ms 0.1)
(def ^:private bar-ms 0.075)

(defn- healthy
  "A round whose `parse-raw` sits well clear of the bar and whose
  `ship-lazy` sits above `ship`."
  [delta]
  (round {:local 0.60 :parse-raw (+ 0.60 delta) :ship 0.50 :ship-lazy 1.50}))

;; ---------------------------------------------------------------------------
;; The fixture's own premises
;; ---------------------------------------------------------------------------

(deftest fixture-scaling-matches-the-app
  (testing "the fixture scales by the same K the control divides by — a
            drifted constant would silently move every bar below"
    (let [rows (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20)] census roster micro)]
      ;; 1000 tags x 100 ns = 0.1 ms; bar = 0.1 x (1 - 0.25).
      (is (= floor-ms (:predicted rows)))
      (is (= bar-ms (:bar rows)))
      (is (= [0.2] (:per-round rows))
          "0.2 ms/walk in, 0.2 ms/walk out — so the fixture's K is the app's K"))))

;; ---------------------------------------------------------------------------
;; The tag-cache floor
;; ---------------------------------------------------------------------------

(deftest an-instrument-with-signal-passes
  (testing "every round comfortably above the floor"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20) (healthy 0.25) (healthy 0.18)]
                                    census roster micro)]
      (is (true? (:ok? r)))
      (is (= 0.18 (:worst r))))))

(deftest an-instrument-with-no-signal-refuses
  (testing "the planted-fault shape: the ablation stops biting, so the
            deltas collapse toward zero and the control must refuse"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.01) (healthy 0.02) (healthy -0.01)]
                                    census roster micro)]
      (is (false? (:ok? r)))
      (is (re-find #"BELOW it" (:why r))
          "and says so in the terms the driver prints"))))

(deftest strict-rule-beats-overlap
  (testing "ONE dataset, TWO rules, opposite verdicts — this is why the
            control does not call `rf.bench.fresco.lane/control-verdict`"
    (let [readings [(healthy 0.20) (healthy 0.20) (healthy 0.03)]
          strict   (rf.bench.fresco.walk-profile-app/tag-cache-floor-row readings census roster micro)
          ;; The same three rounds offered to the lane's overlap rule, as
          ;; the `{:min :max :mean}` range it takes.
          overlap  (rf.bench.fresco.lane/control-verdict floor-ms {:min 0.03 :max 0.20 :mean 0.1433} 0.25)]
      (is (true? (:ok? overlap))
          "the overlap rule PASSES it — a good round vouches for a bad one")
      (is (false? (:ok? strict))
          "the every-round rule REFUSES it, which is the whole difference")
      (is (= 0.03 (:worst strict))))))

;; ---------------------------------------------------------------------------
;; The prediction must STATE something
;; ---------------------------------------------------------------------------
;;
;; A control can FAIL OPEN in the one direction its own subject makes
;; reachable. The bar is `n-tags x (fresh - hit)` less 25%, and a verdict
;; of `worst >= bar` with nothing requiring `fresh - hit` to be positive
;; fails open whenever the two primitives CONVERGE — which is precisely the
;; tag cache having stopped mattering, the ablation this row exists to
;; catch — because the prediction collapses to zero or below at the same
;; moment the measured delta does, the bar lands at or under zero, and any
;; reading at all clears it. Both directions, under that unguarded verdict:
;;
;;   cached 50 ns, fresh 50 ns, delta 0  ->  predicted 0,    bar 0,      ok TRUE
;;   cached 150 ns, fresh 50 ns, delta 0 ->  predicted -0.1, bar -0.075, ok TRUE
;;
;; The planted-fault proof cannot find this: it moves the WALK call site
;; and leaves the micro table's primitive difference healthy, so the
;; prediction stays positive and the bar stays real. A control's
;; own prediction going vacuous is a mode no mutation of the measured arm
;; can reach, and it is why these cases are pinned by arithmetic here
;; rather than by a browser run.

(def ^:private micro-converged
  "The two primitives priced the SAME. Predicted floor 0."
  [[:cached-parse-hit 50.0] [:parse-tag-fresh 50.0]])

(def ^:private micro-inverted
  "A cache hit priced ABOVE a fresh parse. Predicted floor -0.1 ms/walk,
  whose 25% slack makes the bar -0.075 — a bar the arithmetic moves UP
  from the floor rather than down, which is on its own enough to say the
  band has stopped meaning anything."
  [[:cached-parse-hit 150.0] [:parse-tag-fresh 50.0]])

(deftest a-converged-prediction-refuses-however-healthy-the-deltas-look
  (testing "fresh == cached predicts NO extra cost, so there is nothing for
            the walk to have seen — and a control with nothing to see must
            not report that it saw it"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20) (healthy 0.25)] census roster
                                    micro-converged)]
      (is (= 0.0 (:predicted r)) "the fixture really does state a zero floor")
      (is (false? (:ok? r))
          "a bar of zero is cleared by any measurement whatever, so passing
           here is passing on a vacuous test")
      (is (false? (:stated? r))
          "and the refusal is attributed to the PREDICTION, not to the arms"))))

(deftest the-audits-exact-converged-case
  (testing "cached 50 ns, fresh 50 ns, observed delta 0 — which an
            unguarded verdict reports ok TRUE"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.0)] census roster micro-converged)]
      (is (= 0.0 (:predicted r)))
      (is (= 0.0 (:bar r)))
      (is (= 0.0 (:worst r)))
      (is (false? (:ok? r))))))

(deftest the-audits-exact-inverted-case
  (testing "cached 150 ns, fresh 50 ns, observed delta 0 — which an
            unguarded verdict reports ok TRUE, because a NEGATIVE floor puts
            the bar below every real measurement"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.0)] census roster micro-inverted)]
      (is (= -0.1 (:predicted r)))
      (is (= -0.075 (:bar r)))
      (is (false? (:ok? r)))
      (is (false? (:stated? r))))))

(deftest a-roster-with-no-tags-refuses
  (testing "the other route to a vacuous bar: nothing to predict over. The
            population rule cannot catch it, because an empty roster and a
            walk that parses nothing agree with each other"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20)] {:native 0} (make-array 0) micro)]
      (is (= 0.0 (:predicted r)))
      (is (false? (:ok? r)))
      (is (false? (:stated? r))))))

(deftest an-absent-prediction-is-reported-DIFFERENTLY-from-a-missed-bar
  (testing "two refusals, two causes, two repairs — an operator told only
            `FAILED` would go looking at the arms, where nothing is wrong"
    (let [vacuous (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20)] census roster micro-converged)
          missed  (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.01)] census roster micro)]
      (is (false? (:ok? vacuous)))
      (is (false? (:ok? missed)))
      (is (false? (:stated? vacuous)))
      (is (true? (:stated? missed))
          "the missed bar had a real prediction; it is the ARMS that missed it")
      (is (re-find #"states no prediction" (:why vacuous)))
      (is (re-find #"BELOW it" (:why missed)))
      (is (= "REFUSED — no prediction" (rf.bench.fresco.walk-profile-app/control-status vacuous)))
      (is (= "FAILED" (rf.bench.fresco.walk-profile-app/control-status missed)))
      (is (= "ok" (rf.bench.fresco.walk-profile-app/control-status (rf.bench.fresco.walk-profile-app/tag-cache-floor-row
                                       [(healthy 0.20)] census roster micro)))))))

(deftest a-real-prediction-still-passes-on-real-signal
  (testing "refusing a vacuous prediction must not close the door on the healthy case —
            the whole point is a control that can still say yes"
    (is (true? (:ok? (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20) (healthy 0.18)]
                                             census roster micro))))))

(deftest a-roster-that-is-not-the-walks-parse-population-refuses
  (testing "the prediction is per-tag over the micro roster, so a roster
            that is not what the walk parses prices the wrong thing —
            and every delta below is otherwise healthy"
    (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20) (healthy 0.20)]
                                    {:native 999} roster micro)]
      (is (false? (:ok? r)))
      (is (= {:micro-roster 1000 :walk-parses 999} (:population r))))))

;; ---------------------------------------------------------------------------
;; The lazy-tail direction
;; ---------------------------------------------------------------------------

(deftest the-lazy-arm-must-read-above-the-eager-one-in-every-round
  (testing "ship-lazy does strictly more work than ship by construction"
    (is (true? (:ok? (rf.bench.fresco.walk-profile-app/lazy-tail-direction-row [(healthy 0.2) (healthy 0.2)])))))
  (testing "a single inverted round refuses, even beside two good ones —
            an inversion means the window is not pricing the walk"
    (let [r (rf.bench.fresco.walk-profile-app/lazy-tail-direction-row
              [(healthy 0.2)
               (round {:local 0.6 :parse-raw 0.8 :ship 1.50 :ship-lazy 1.40})
               (healthy 0.2)])]
      (is (false? (:ok? r)))
      (is (= -0.1 (:worst r))))))
