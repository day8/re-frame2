(ns re-frame.bench.hicasso.walk-profile-control-cljs-test
  "THE WALK PROFILE'S POSITIVE CONTROL MUST REFUSE — pinned (rf2-1huc).

  `walk_profile_app` had no positive control at all until rf2-1huc: it
  never set `window.HICASSO_CONTROL_FAILED`, so `run.cjs`'s control exit
  path was dead for this arm and a run whose ablations had stopped biting
  would still print a full table and exit 0.

  The control now exists, and it was proven against a planted fault —
  `walk-parse` at `M-PARSE-RAW` swapped back to `codec/cached-parse`, so
  the arm did exactly the work `local` does. `parse-raw`'s p50 collapsed
  onto `local`'s, the per-round deltas went to ~0 with one NEGATIVE, and
  the driver exited 1. That is the proof the exit path is live; it is not
  a proof anyone will repeat, because it costs an `:advanced` build and a
  Chromium run.

  So the RULE is pinned here instead, in the always-on `cljs-test$` gate,
  over synthetic readings. What that buys is narrow and specific: a later
  worker cannot quietly weaken the adjudication — the failure mode this
  whole bead is about — without going red in a suite that costs nothing.

  ## The one assertion that carries the design

  [[strict-rule-beats-overlap]] is the reason this file is not merely
  belt-and-braces. `lane/control-verdict`'s `:ok?` asks whether the
  measured range OVERLAPS the band; the walk profile's control asks
  whether EVERY ROUND clears the bar. Its own docstring records the
  overlap rule as a KNOWN DEFECT reserved to an operator ruling
  (rf2-egdaq / rf2-2rtt6.1), so this control could not adopt it and could
  not tighten it either. It took the stricter rule from birth instead —
  legal precisely because it is NEW and has no published row to
  re-adjudicate.

  That test drives ONE dataset through BOTH rules and asserts they
  disagree on it: overlap passes, every-round refuses. A worker who
  \"simplifies\" the control down to `lane/control-verdict` therefore
  discovers it here, rather than in a bench run six months later that
  quietly stopped having teeth."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.walk-profile-app :as wp]))

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
    (let [rows (wp/tag-cache-floor-row [(healthy 0.20)] census roster micro)]
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
    (let [r (wp/tag-cache-floor-row [(healthy 0.20) (healthy 0.25) (healthy 0.18)]
                                    census roster micro)]
      (is (true? (:ok? r)))
      (is (= 0.18 (:worst r))))))

(deftest an-instrument-with-no-signal-refuses
  (testing "the planted-fault shape: the ablation stops biting, so the
            deltas collapse toward zero and the control must refuse"
    (let [r (wp/tag-cache-floor-row [(healthy 0.01) (healthy 0.02) (healthy -0.01)]
                                    census roster micro)]
      (is (false? (:ok? r)))
      (is (re-find #"BELOW it" (:why r))
          "and says so in the terms the driver prints"))))

(deftest strict-rule-beats-overlap
  (testing "ONE dataset, TWO rules, opposite verdicts — this is why the
            control does not call `lane/control-verdict` (rf2-egdaq)"
    (let [readings [(healthy 0.20) (healthy 0.20) (healthy 0.03)]
          strict   (wp/tag-cache-floor-row readings census roster micro)
          ;; The same three rounds offered to the lane's overlap rule, as
          ;; the `{:min :max :mean}` range it takes.
          overlap  (lane/control-verdict floor-ms {:min 0.03 :max 0.20 :mean 0.1433} 0.25)]
      (is (true? (:ok? overlap))
          "the overlap rule PASSES it — a good round vouches for a bad one")
      (is (false? (:ok? strict))
          "the every-round rule REFUSES it, which is the whole difference")
      (is (= 0.03 (:worst strict))))))

(deftest a-roster-that-is-not-the-walks-parse-population-refuses
  (testing "the prediction is per-tag over the micro roster, so a roster
            that is not what the walk parses prices the wrong thing —
            and every delta below is otherwise healthy"
    (let [r (wp/tag-cache-floor-row [(healthy 0.20) (healthy 0.20)]
                                    {:native 999} roster micro)]
      (is (false? (:ok? r)))
      (is (= {:micro-roster 1000 :walk-parses 999} (:population r))))))

;; ---------------------------------------------------------------------------
;; The lazy-tail direction
;; ---------------------------------------------------------------------------

(deftest the-lazy-arm-must-read-above-the-eager-one-in-every-round
  (testing "ship-lazy does strictly more work than ship by construction"
    (is (true? (:ok? (wp/lazy-tail-direction-row [(healthy 0.2) (healthy 0.2)])))))
  (testing "a single inverted round refuses, even beside two good ones —
            an inversion means the window is not pricing the walk"
    (let [r (wp/lazy-tail-direction-row
              [(healthy 0.2)
               (round {:local 0.6 :parse-raw 0.8 :ship 1.50 :ship-lazy 1.40})
               (healthy 0.2)])]
      (is (false? (:ok? r)))
      (is (= -0.1 (:worst r))))))
