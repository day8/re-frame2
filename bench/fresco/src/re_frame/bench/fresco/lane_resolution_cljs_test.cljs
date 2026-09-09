(ns re-frame.bench.hicasso.lane-resolution-cljs-test
  "THE LANE'S RESOLUTION FIGURE, WITNESSED (rf2-42w6).

  [[re-frame.bench.hicasso.lane/across-rounds]]'s `:straddles-1?` is the
  gate a reader applies before quoting any comparative range, and it is a
  SOUND NECESSARY CONDITION: an arm that never separated from an empty
  frame carries no ratio about a substrate, and under `rf2-9wmqd` that
  test refused a pair outright. What it cannot do is decide whether a
  comparison had the POWER to see the line it is read against — one arm
  against the floor and two arms against each other are different
  questions. [[re-frame.bench.hicasso.lane/resolution]] answers the
  second, and this file is what keeps it honest.

  ## Why the figure needs a witness at all

  Because the case that motivates it is one where every other published
  number looks fine. A paint-bounded window is `~96%` frame grid, so the
  wait for the next rendering opportunity sits in BOTH arms and enters
  the ratio as dead weight; only the arms' work ABOVE the floor can move
  a window-level ratio at all. A pair can therefore clear the floor in
  every round, read a clean `1.00x`, and still be incapable of showing a
  `1.5x` difference in the thing actually under test. Nothing on the
  record says so unless something computes it.

  ## The fixture is the measured case, not an invented one

  [[locale-like]] carries `rf2-9wmqd`'s own published figures for the
  `:locale` pair's first evidence run — a floor `p50` of `16.30 ms`, a
  denominator arm at `17.05 ms`, and a per-round spread of `3.75`
  percentage points. `the-fixture-reproduces-the-published-displacements`
  below asserts it reproduces that window's `1.011` and `1.022` before
  anything else asserts anything, so a fixture that drifted away from the
  record it stands for fails here rather than going on passing.

  ## AND EVERY CASE BELOW IS A FIGURE, NEVER A VERDICT

  `budgets.md` §7 routes every distributional row to a pinned evidence
  run and forbids converting one into a lane threshold, so
  `resolution` may publish no band and no pass/fail.
  `the-answer-carries-no-verdict` pins that bound as a test rather than
  leaving it to a docstring: a boolean appearing in the answer is a gate
  arriving by the back door, and it fails here.

  ## NO READING IS TAKEN HERE, and none may be

  This is a test over known inputs. It runs on a loaded box, touches no
  clock, mounts nothing, and pins no figure any budget row reads."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]))

;; ---------------------------------------------------------------------------
;; The fixture — rf2-9wmqd's `:locale` pair, evidence run 1
;; ---------------------------------------------------------------------------

(def summary
  "Per-arm `:p50`s. The floor and the denominator arm are the only two
  entries `resolution` reads, and `0.75 ms` between them is the window's
  own published excess over floor."
  {:idle-frame   {:p50 16.30}
   :donor-locale {:p50 17.05}
   :locale       {:p50 17.05}})

(def round-ratios
  "Three rounds of ARM-TO-FLOOR ratios, the shape `rf.bench.hicasso.lane/normalise`
  answers and the only shape `rf.bench.hicasso.lane/ratio-between` accepts.

  `:donor-locale` is held at `1.046` — `17.05 / 16.30`, so the two inputs
  describe one run rather than two — and `:locale` is that times
  `1.0000`, `1.0375` and `1.0200` in turn, which puts a `3.75` point
  spread on the pair ratio and straddles `1.0`."
  [{:locale 1.046    :donor-locale 1.046}
   {:locale 1.085225 :donor-locale 1.046}
   {:locale 1.06692  :donor-locale 1.046}])

(defn locale-like []
  (rf.bench.hicasso.lane/resolution (rf.bench.hicasso.lane/ratio-between round-ratios :locale :donor-locale)
                   summary
                   :idle-frame))

;; ---------------------------------------------------------------------------
;; The fixture discriminates before it is trusted
;; ---------------------------------------------------------------------------

(deftest the-fixture-reproduces-the-published-displacements
  (testing "`rf2-9wmqd` published that a `1.25x` difference in the arms'
           own work would move this pair's ratio to `1.011` and a `1.5x`
           difference to `1.022`. Both fall straight out of
           `:own-work-share`, so if the fixture ever stops standing for
           that run this fails rather than the cases below quietly
           testing something else."
    (let [{:keys [own-work own-work-share]} (locale-like)]
      (is (= 0.75 own-work)
          "the denominator arm's median less the floor's, in ms")
      (is (= 0.011 (rf.bench.hicasso.lane/round4 (* 0.25 own-work-share)))
          "a 1.25x difference displaces the window ratio to 1.011")
      (is (= 0.022 (rf.bench.hicasso.lane/round4 (* 0.5 own-work-share)))
          "a 1.5x difference displaces it to 1.022"))))

(deftest the-pair-clears-the-floor-and-still-cannot-resolve-the-line
  (testing "THE WHOLE POINT. Both arms separate from an empty frame in
           every round — `:over-floor` says so — and the pair still
           could not have shown a `1.5x` difference in the arms' own
           work, because `:resolves-at` sits above it. One test answering
           yes and the other no on the SAME run is what makes them
           different questions rather than two spellings of one."
    (let [over-floor (rf.bench.hicasso.lane/across-rounds round-ratios)]
      (is (false? (:straddles-1? (:locale over-floor)))
          "the measured arm separated from the floor in every round")
      (is (false? (:straddles-1? (:donor-locale over-floor)))
          "so did the donor arm — the shipped gate passes this pair")
      (is (true? (:straddles-1? (rf.bench.hicasso.lane/ratio-between round-ratios
                                                    :locale :donor-locale)))
          "and the pair itself reads ~1.00x, straddling 1.0")
      (is (> (:resolves-at (locale-like)) 1.5)
          "yet the run could not have resolved even the coarser of the
           two lines this window was read against"))))

;; ---------------------------------------------------------------------------
;; The arithmetic
;; ---------------------------------------------------------------------------

(deftest resolves-at-is-the-difference-whose-displacement-equals-the-spread
  (testing "The defining relation, asserted rather than restated: at
           `k = :resolves-at` the displacement `(k - 1) * :own-work-share`
           IS the scatter the run showed. Anything smaller is inside the
           noise."
    (let [{:keys [resolves-at own-work-share spread]} (locale-like)]
      (is (= 0.0375 spread)
          "the width of `ratio-between`'s `:per-round`")
      (is (= 1.8525 resolves-at))
      (is (= spread (rf.bench.hicasso.lane/round4 (* (- resolves-at 1.0) own-work-share)))
          "the figure and the spread are the same statement"))))

(deftest the-floor-and-not-the-noise-is-what-makes-a-run-coarse
  (testing "DISCRIMINATION. Hold the scatter fixed and lift the arms off
           the floor: the identical spread now resolves `1.04x` instead
           of `1.85x`. A figure that answered the same either way would
           be reporting noise and nothing about the frame grid, which is
           the term this whole finding is about."
    (let [lifted (rf.bench.hicasso.lane/resolution
                   (rf.bench.hicasso.lane/ratio-between round-ratios :locale :donor-locale)
                   {:idle-frame {:p50 1.0} :donor-locale {:p50 10.0}}
                   :idle-frame)]
      (is (= 0.9 (:own-work-share lifted))
          "nine tenths of the window is now the arms' own work")
      (is (= 1.0417 (:resolves-at lifted)))
      (is (= (:spread lifted) (:spread (locale-like)))
          "and the run's scatter is byte-for-byte the one above"))))

;; ---------------------------------------------------------------------------
;; The edges, and the bound
;; ---------------------------------------------------------------------------

(deftest an-arm-at-or-below-the-floor-resolves-nothing-at-any-size
  (testing "A pair with no work above the frame grid cannot be moved by
           any difference whatever, so the honest answer is `nil` rather
           than a large number that looks like an answer. `:own-work`
           stays published either way, because it is the reason."
    (let [pair (rf.bench.hicasso.lane/ratio-between round-ratios :locale :donor-locale)
          at   (rf.bench.hicasso.lane/resolution pair {:idle-frame   {:p50 16.30}
                                      :donor-locale {:p50 16.30}} :idle-frame)
          below (rf.bench.hicasso.lane/resolution pair {:idle-frame   {:p50 16.30}
                                       :donor-locale {:p50 16.00}} :idle-frame)]
      (is (nil? (:resolves-at at))
          "exactly at the floor")
      (is (= 0.0 (:own-work at)))
      (is (nil? (:resolves-at below))
          "and below it, where the arm is faster than an empty frame")
      (is (neg? (:own-work below))
          "the negative excess is published rather than clamped away"))))

(deftest rounds-that-agree-exactly-report-one-and-claim-nothing
  (testing "`:resolves-at` is bounded below by the run's own scatter, so
           a run with no scatter reports `1.0`. That says the scatter
           bounds nothing — not that any difference is visible — and the
           `:spread` of `0.0` sitting beside it is what tells a reader
           which of the two it is."
    (let [flat (rf.bench.hicasso.lane/resolution
                 (rf.bench.hicasso.lane/ratio-between [{:locale 1.046 :donor-locale 1.046}
                                      {:locale 1.046 :donor-locale 1.046}]
                                     :locale :donor-locale)
                 summary
                 :idle-frame)]
      (is (= 0.0 (:spread flat)))
      (is (= 1.0 (:resolves-at flat))))))

(deftest the-answer-carries-no-verdict
  (testing "`budgets.md` §7 forbids a distributional row a lane
           threshold, so this figure may publish no band and no
           pass/fail. The key set is pinned and every value is a number,
           an arm id or `nil` — a boolean here would be a gate arriving
           by the back door."
    (let [answer (locale-like)]
      (is (= #{:denominator :floor-p50 :denominator-p50 :own-work
               :own-work-share :spread :resolves-at}
             (set (keys answer))))
      (is (= :donor-locale (:denominator answer))
          "the figure names the arm it is stated against")
      (is (empty? (filter boolean? (vals answer)))
          "no verdict of any kind")
      (is (every? number? (vals (dissoc answer :denominator)))
          "and every other value is a figure"))))
