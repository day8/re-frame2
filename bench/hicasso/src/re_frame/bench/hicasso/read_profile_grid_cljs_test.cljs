(ns re-frame.bench.hicasso.read-profile-grid-cljs-test
  "PHASE B'S PRINTED GRID IS DERIVED FROM THE KEPT COUNT'S PARITY —
  pinned (rf2-3l6hf, merged-PR audit of #8328).

  `read_profile_app`'s phase B prints a `grid = …` figure that tells a
  reader the finest spacing any row or delta below it can land on. It is
  the number a reader uses to decide whether a term is one grid step or
  thirty, so a grid line that overstates the resolution invites exactly
  the reading the instrument exists to prevent.

  ## The defect this pins

  The line printed `0.05 / frames-per-window` as a CONSTANT over a
  variable, and the 0.05 was only ever true at even parity. The chain is
  three links: `rf.bench.hicasso.lane/now-ms` is clamped to 100 µs, so a raw window sample
  is a multiple of 0.1 ms; `rf.bench.hicasso.lane/summarise` takes the MEAN OF THE TWO
  MIDDLE order statistics when the kept count is even — putting the p50
  on a half-clamp grid — and a SINGLE order statistic when it is odd,
  where no halving happens; and the row then divides by the frame count.

  Nothing held the parity. `b-rounds` and `b-sampling` are independent
  vars in that file, their product is the kept count, and an editor
  moving either one to an odd product would have doubled the real grid
  while the line went on advertising the halved one. No test would have
  noticed: the arithmetic was in a docstring and the number was a
  literal.

  ## What is pinned, and what deliberately is not

  Rows 1–2 pin the MECHANISM — `rf.bench.hicasso.lane/summarise`'s parity behaviour — at
  the level the derivation depends on, because a change there is the
  other way the printed grid could quietly stop being true.

  Row 3 pins the DERIVATION at both parities, and row 4 pins that the
  design line prints what the derivation returns rather than a constant
  standing next to it. Row 4 is the one that actually forecloses the
  defect: it drives the line at an ODD kept count, which the live shape
  is not, so it fails the moment the number goes back to being a
  literal.

  Row 5 reads the LIVE shape through `rf.bench.hicasso.read-profile-app/phase-b-shape` and states its
  parity and grid. It is not a bar on the shape — the instrument is free
  to move to any parity, and the point of deriving is that it may — it
  simply records which arm of the derivation the shipped window is on,
  so a shape change shows up as a diff here rather than as a silently
  different grid in a published transcript.

  Nothing here reads a clock, times anything, or mounts a frame. The
  claim is arithmetic."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.read-profile-app :as rf.bench.hicasso.read-profile-app]))

(defn- multiple-of?
  "`x` is a multiple of `g`, to within the float slop of dividing two
  decimal quantities that are exact in neither base."
  [g x]
  (let [q (/ x g)]
    (< (js/Math.abs (- q (js/Math.round q))) 1e-9)))

;; ===========================================================================
;; 1 — an ODD kept count leaves the p50 on the raw clock grid
;; ===========================================================================

(deftest an-odd-sample-count-keeps-the-p50-on-the-full-clock-grid
  (testing "`rf.bench.hicasso.lane/summarise` on an odd count answers a member of its own
           input, so a set of clamp-multiples summarises to a
           clamp-multiple and nothing finer is reachable"
    (let [xs [0.1 0.2 0.2 0.3 0.5]
          p  (:p50 (rf.bench.hicasso.lane/summarise xs))]
      (is (= 5 (count xs)) "the fixture is odd, which is the whole point")
      (is (some #{p} xs)
          "an odd-count p50 IS one of the readings, not a blend of two")
      (is (multiple-of? rf.bench.hicasso.read-profile-app/clock-clamp-ms p)
          "so it lands on the full 0.1 ms clock grid"))))

;; ===========================================================================
;; 2 — an EVEN kept count halves it, and can land BETWEEN two clock ticks
;; ===========================================================================

(deftest an-even-sample-count-halves-the-grid
  (testing "`rf.bench.hicasso.lane/summarise` on an even count averages the two middle
           readings, which reaches values no single clock reading can
           take — that is the halving the design line depends on"
    (let [xs [0.1 0.2 0.3 0.5]
          p  (:p50 (rf.bench.hicasso.lane/summarise xs))]
      (is (= 4 (count xs)) "the fixture is even")
      (is (multiple-of? (/ rf.bench.hicasso.read-profile-app/clock-clamp-ms 2.0) p)
          "the p50 is on the half-clamp grid")
      (is (not (multiple-of? rf.bench.hicasso.read-profile-app/clock-clamp-ms p))
          "and this one is strictly OFF the full-clamp grid — 0.25 is a
           p50 no individual reading could have produced, which is what
           makes the halving real rather than nominal"))))

;; ===========================================================================
;; 3 — the derivation follows the parity
;; ===========================================================================

(deftest the-derived-grid-follows-the-kept-counts-parity
  (testing "even kept total → half-clamp over frames"
    (is (= (/ 0.05 32) (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 8 {:warmup 2 :samples 8} 32))
        "8 x 8 = 64 kept, even")
    (is (= (/ 0.05 4) (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 4 {:warmup 2 :samples 6} 4))
        "4 x 6 = 24 kept, even — the shape rf2-d360z published, whose
         deltas were all multiples of 0.0125"))

  (testing "odd kept total → the FULL clamp over frames, twice as coarse"
    (is (= (/ 0.1 32) (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 9 {:warmup 2 :samples 7} 32))
        "9 x 7 = 63 kept, odd")
    (is (= (/ 0.1 4) (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 1 {:warmup 2 :samples 1} 4))
        "1 x 1 = 1 kept, odd"))

  (testing "the two parities differ by exactly the factor the halving is"
    (is (= 2.0 (/ (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 9 {:warmup 2 :samples 7} 32)
                  (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 8 {:warmup 2 :samples 8} 32)))))

  (testing "frames scale it independently of parity"
    (is (= 8.0 (/ (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 8 {:warmup 2 :samples 8} 4)
                  (rf.bench.hicasso.read-profile-app/phase-b-grid-ms 8 {:warmup 2 :samples 8} 32))))))

;; ===========================================================================
;; 4 — the design LINE prints the derivation, not a constant
;; ===========================================================================

(deftest the-design-line-prints-the-derived-grid-at-both-parities
  (testing "at the even shape the window ships, the line carries the
           halved grid and says so"
    (let [line (rf.bench.hicasso.read-profile-app/phase-b-design-line 8 {:warmup 2 :samples 8} 32)]
      (is (re-find #"kept = 64 samples/arm \(EVEN" line))
      (is (re-find #"mean of two middle clock readings" line))
      (is (re-find #"grid = 0\.001563 ms/commit" line)
          "0.05/32, the figure the published transcripts carry")))

  (testing "it is the PRODUCT that decides, so moving the sample count
           alone need not flip anything — 8 x 7 is still even"
    (let [line (rf.bench.hicasso.read-profile-app/phase-b-design-line 8 {:warmup 2 :samples 7} 32)]
      (is (re-find #"kept = 56 samples/arm \(EVEN" line))
      (is (re-find #"grid = 0\.001563 ms/commit" line))))

  (testing "AT AN ODD PRODUCT THE SAME LINE DOUBLES. This is the row the
           defect could not have survived: the old line held 0.05 as a
           literal and would print 0.001563 here, understating the real
           grid by 2x"
    (let [line (rf.bench.hicasso.read-profile-app/phase-b-design-line 9 {:warmup 2 :samples 7} 32)]
      (is (re-find #"kept = 63 samples/arm \(ODD" line))
      (is (re-find #"a single clock reading" line))
      (is (re-find #"grid = 0\.003125 ms/commit" line)
          "0.1/32 — twice the even-parity grid, printed as such")))

  (testing "the line's grid figure IS `phase-b-grid-ms`'s, at every shape
           tried, rather than a second expression that happens to agree"
    (doseq [[r s f] [[8 8 32] [9 7 32] [4 6 4] [3 5 16] [7 3 128]]]
      (let [sampling {:warmup 2 :samples s}
            expected (.toFixed (rf.bench.hicasso.read-profile-app/phase-b-grid-ms r sampling f) 6)]
        (is (str/includes? (rf.bench.hicasso.read-profile-app/phase-b-design-line r sampling f)
                           (str "grid = " expected " ms/commit"))
            (str "shape " r "x" s " over " f " frames"))))))

;; ===========================================================================
;; 5 — which arm of the derivation the SHIPPED window is on
;; ===========================================================================

(deftest the-live-shape-is-recorded-with-its-parity
  (let [{:keys [rounds sampling frames]} (rf.bench.hicasso.read-profile-app/phase-b-shape)
        kept (* rounds (:samples sampling))]
    (testing "the live shape's own numbers, read off the instrument"
      (is (pos? rounds))
      (is (pos? (:samples sampling)))
      (is (pos? frames)))

    (testing "the grid the live shape derives is the grid its design line
             prints — the two cannot drift, because there is one
             expression"
      (is (re-find (re-pattern (str "grid = "
                                    (.toFixed (rf.bench.hicasso.read-profile-app/phase-b-grid-ms rounds sampling frames) 6)
                                    " ms/commit"))
                   (rf.bench.hicasso.read-profile-app/phase-b-design-line rounds sampling frames))))

    (testing "and the parity the line names is the parity the kept count
             actually has"
      (is (re-find (re-pattern (str "kept = " kept " samples/arm \\("
                                    (if (even? kept) "EVEN" "ODD")))
                   (rf.bench.hicasso.read-profile-app/phase-b-design-line rounds sampling frames))))))
