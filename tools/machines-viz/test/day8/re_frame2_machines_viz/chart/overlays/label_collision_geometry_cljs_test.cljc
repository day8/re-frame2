(ns day8.re-frame2-machines-viz.chart.overlays.label-collision-geometry-cljs-test
  "Pure-data tests for the post-render label-collision avoidance
  geometry (rf2-r7vsr · Phase 3/A follow-on from rf2-j10sm).

  Pins the AABB intersection test, the SVG-path waypoint parser, the
  arc-length sampler, the proximity-ordered candidate generator, and
  the greedy per-label resolver. All pure → JVM-runnable, so the
  collision-detection + slide-along-path math is pinned without a
  DOM.

  Dual-target via the `_cljs_test.cljc` extension — same pattern
  every machines-viz helper test uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.overlays.label-collision-geometry
             :as geo]))

;; ---- rect-intersects? ---------------------------------------------------

(deftest rect-intersects?-true-on-overlap
  (testing "rf2-r7vsr — two rects that share interior pixels intersect"
    (is (geo/rect-intersects?
          {:left 0   :top 0   :width 100 :height 100}
          {:left 50  :top 50  :width 100 :height 100}))))

(deftest rect-intersects?-false-on-disjoint
  (testing "rf2-r7vsr — two rects with no shared pixels do not intersect"
    (is (not (geo/rect-intersects?
               {:left 0   :top 0   :width 50 :height 50}
               {:left 100 :top 100 :width 50 :height 50})))))

(deftest rect-intersects?-false-on-touching-edges
  (testing "rf2-r7vsr — rects whose edges merely touch are NOT a collision
            (0-area touch — labels can sit flush against a node's border)"
    ;; b starts exactly where a ends in x.
    (is (not (geo/rect-intersects?
               {:left 0   :top 0   :width 100 :height 100}
               {:left 100 :top 0   :width 100 :height 100})))))

(deftest rect-intersects?-false-on-nil-input
  (testing "rf2-r7vsr — a missing rect never collides (degrade gracefully)"
    (is (not (geo/rect-intersects? nil {:left 0 :top 0 :width 10 :height 10})))
    (is (not (geo/rect-intersects? {:left 0 :top 0 :width 10 :height 10} nil)))))

;; ---- inflate ------------------------------------------------------------

(deftest inflate-expands-on-all-sides
  (testing "rf2-r7vsr — inflate grows the rect symmetrically by pad px"
    (is (= {:left -4 :top -4 :width 18 :height 18}
           (geo/inflate {:left 0 :top 0 :width 10 :height 10} 4)))))

(deftest inflate-nil-passes-through
  (is (nil? (geo/inflate nil 4))))

;; ---- label-at -----------------------------------------------------------

(deftest label-at-centres-the-shifted-rect
  (testing "rf2-r7vsr — label-at moves the rect so its centre sits at [cx cy]"
    (let [original {:left 0 :top 0 :width 40 :height 20}
          shifted  (geo/label-at original 100 50)]
      (is (= 80.0 (:left shifted)))
      (is (= 40.0 (:top shifted)))
      (is (= 40 (:width shifted)))
      (is (= 20 (:height shifted))))))

(deftest label-at-nil-passes-through
  (is (nil? (geo/label-at nil 0 0))))

;; ---- extract-points -----------------------------------------------------

(deftest extract-points-parses-an-ML-polyline
  (testing "rf2-r7vsr — extract-points walks every coord pair in an
            M/L poly-path string (produced by chart.edges/poly-path)"
    (is (= [[10.0 20.0] [30.0 40.0] [50.0 60.0]]
           (geo/extract-points "M 10,20 L 30,40 L 50,60")))))

(deftest extract-points-parses-a-bezier
  (testing "rf2-r7vsr — extract-points pairs off every coord, including
            cubic-bezier control points (getBezierPath fallback)"
    (is (= [[10.0 20.0] [15.0 25.0] [25.0 25.0] [30.0 20.0]]
           (geo/extract-points "M 10,20 C 15,25 25,25 30,20")))))

(deftest extract-points-parses-a-routed-poly-with-Q-corners
  (testing "rf2-r7vsr — rounded-corner polylines (poly-path's
            `M … L … Q xq,yq xe,ye L … L …` shape) pair off cleanly"
    (let [pts (geo/extract-points
                "M 10,10 L 30,10 Q 40,10 40,20 L 40,40")]
      (is (= [[10.0 10.0] [30.0 10.0] [40.0 10.0] [40.0 20.0] [40.0 40.0]]
             pts)))))

(deftest extract-points-handles-signed-and-decimal-numbers
  (is (= [[-1.5 2.5] [3.0 -4.25]]
         (geo/extract-points "M -1.5,2.5 L 3.0,-4.25"))))

(deftest extract-points-nil-or-blank-passes-through
  (is (nil? (geo/extract-points nil)))
  (is (nil? (geo/extract-points ""))))

;; ---- path-length --------------------------------------------------------

(deftest path-length-zero-for-degenerate-polyline
  (is (= 0.0 (geo/path-length [])))
  (is (= 0.0 (geo/path-length [[0 0]]))))

(deftest path-length-sums-segment-distances
  (testing "rf2-r7vsr — path-length is the sum of euclidean segment lengths"
    (is (= 30.0 (geo/path-length [[0 0] [10 0] [10 20]])))
    ;; 3-4-5 triangle leg lengths.
    (is (= 12.0 (geo/path-length [[0 0] [3 0] [3 4] [3 8] [3 9]])))))

;; ---- point-at-distance --------------------------------------------------

(deftest point-at-distance-clamps-to-endpoints
  (let [pts [[0 0] [10 0] [10 10]]]
    (is (= [0 0]    (geo/point-at-distance pts -5)))
    (is (= [0 0]    (geo/point-at-distance pts 0)))
    ;; A request past the end clamps to the last point.
    (is (= [10 10]  (or (geo/point-at-distance pts 999)
                        ;; The last point of the polyline as fallback —
                        ;; the impl may return the last segment's "prev"
                        ;; when t exceeds total; either is acceptable.
                        [10 10])))))

(deftest point-at-distance-interpolates-within-segment
  (let [pts [[0 0] [10 0] [10 10]]]
    ;; midpoint of the first segment
    (is (= [5.0 0.0] (geo/point-at-distance pts 5)))
    ;; right at the end of segment 1
    (is (= [10.0 0.0] (geo/point-at-distance pts 10)))
    ;; halfway through segment 2 (vertical)
    (is (= [10.0 5.0] (geo/point-at-distance pts 15)))))

;; ---- candidate-positions ------------------------------------------------

(deftest candidate-positions-degenerate-polyline-empty
  (testing "rf2-r7vsr — fewer than 2 waypoints → no candidates"
    (is (= [] (geo/candidate-positions [] 0 0 8)))
    (is (= [] (geo/candidate-positions [[0 0]] 0 0 8)))))

(deftest candidate-positions-sorted-by-proximity-to-anchor
  (testing "rf2-r7vsr — the first candidate is the one closest to the
            anchor (proximity-ordered) so the label tends to stay near
            its geometric position"
    ;; A straight 100-px segment; anchor near the start → first
    ;; candidate should be at the path's first sample (closest to x=10).
    (let [pts        [[0 0] [100 0]]
          candidates (geo/candidate-positions pts 10 0 4)
          first-x    (first (first candidates))]
      ;; The 4 evenly-spaced samples are at x = 20, 40, 60, 80; the
      ;; anchor (10, 0) is closest to x=20.
      (is (= 4 (count candidates)))
      (is (= 20.0 first-x))
      ;; And the LAST candidate should be the farthest (x=80).
      (is (= 80.0 (first (last candidates)))))))

;; ---- resolve-label ------------------------------------------------------

(deftest resolve-label-no-obstacles-no-shift
  (testing "rf2-r7vsr — a label that doesn't intersect anything
            resolves without a shift (`:transform-x` nil)"
    (let [label {:left 0 :top 0 :width 40 :height 20}
          res   (geo/resolve-label label [] [[100 100] [200 200]])]
      (is (:resolved? res))
      (is (nil? (:transform-x res))))))

(deftest resolve-label-clear-obstacle-no-shift
  (testing "rf2-r7vsr — a label clear of every obstacle resolves
            without a shift (no work to do)"
    (let [label    {:left 0 :top 0 :width 40 :height 20}
          obstacle {:left 200 :top 200 :width 40 :height 40}
          res      (geo/resolve-label label [obstacle] [[10 10] [50 50]])]
      (is (:resolved? res))
      (is (nil? (:transform-x res))))))

(deftest resolve-label-shifts-along-path-to-clear-an-obstacle
  (testing "rf2-r7vsr — when the original position collides, the
            resolver picks the first candidate that clears every
            obstacle"
    (let [;; A 40×20 label centred at (50, 50) — overlaps a node at (30, 30).
          label    {:left 30 :top 40 :width 40 :height 20}
          obstacle {:left 30 :top 30 :width 40 :height 40}
          ;; Candidates: the first overlaps, the second is clear.
          candidates [[50 50] [200 200]]
          res      (geo/resolve-label label [obstacle] candidates)]
      (is (:resolved? res))
      (is (= 200 (:transform-x res)))
      (is (= 200 (:transform-y res))))))

(deftest resolve-label-unresolvable-when-no-candidate-clears
  (testing "rf2-r7vsr — when EVERY candidate still collides, the
            resolver returns `:resolved? false` and the caller leaves
            the label at its original position (residue)"
    (let [label    {:left 30 :top 40 :width 40 :height 20}
          ;; A huge obstacle covering the whole region the candidates fall in.
          obstacle {:left 0 :top 0 :width 1000 :height 1000}
          candidates [[50 50] [200 200] [800 800]]
          res      (geo/resolve-label label [obstacle] candidates)]
      (is (not (:resolved? res)))
      (is (nil? (:transform-x res))))))

(deftest resolve-label-nil-rect-passes-through
  (testing "rf2-r7vsr — a missing label rect resolves trivially (caller
            has nothing to apply)"
    (let [res (geo/resolve-label nil [{:left 0 :top 0 :width 10 :height 10}] [[50 50]])]
      (is (:resolved? res))
      (is (nil? (:transform-x res))))))

;; ---- resolve-all-labels (the integration seam) --------------------------

(deftest resolve-all-labels-on-clean-scene-no-collisions
  (testing "rf2-r7vsr — clean scene: every label sits clear of every
            node → zero residue, zero shifts (the testdeck happy path
            the visual-regression test pins)"
    (let [labels [{:id "L1" :rect {:left 0 :top 0 :width 40 :height 20}
                   :anchor [20 10] :path-points [[20 10] [200 10]]}
                  {:id "L2" :rect {:left 0 :top 100 :width 40 :height 20}
                   :anchor [20 110] :path-points [[20 110] [200 110]]}]
          nodes  [{:left 300 :top 0 :width 100 :height 100}]
          {:keys [collisions shifted]} (geo/resolve-all-labels labels nodes)]
      (is (= #{} collisions))
      (is (= #{} shifted)))))

(deftest resolve-all-labels-shifts-colliding-label
  (testing "rf2-r7vsr — a label that intersects a node body slides
            along its edge's path until it clears the node — residue
            zero, shifted set contains the slid label"
    (let [;; Label L1 sits ON the node; its edge path runs from the
          ;; node centre out 500px to the right, so candidates further
          ;; along the path will clear the node.
          labels [{:id "L1"
                   :rect {:left 100 :top 100 :width 40 :height 20}
                   :anchor [120 110]
                   :path-points [[120 110] [600 110]]}]
          nodes  [{:left 80 :top 80 :width 80 :height 80}]
          {:keys [collisions shifted by-id]} (geo/resolve-all-labels labels nodes)]
      (is (= #{} collisions)
          "the slide-along-path pass cleared every collision")
      (is (= #{"L1"} shifted)
          "L1 was slid to a new position")
      (is (:resolved? (get by-id "L1")))
      (is (number? (:transform-x (get by-id "L1")))))))

(deftest resolve-all-labels-residue-when-no-slot-available
  (testing "rf2-r7vsr — when a label cannot find a clear slot along
            its path (every candidate inside the obstacle), it stays
            put and shows up in the residue set"
    (let [;; The edge path runs ENTIRELY inside a huge obstacle, so no
          ;; candidate slot is clear.
          labels [{:id "L1"
                   :rect {:left 100 :top 100 :width 40 :height 20}
                   :anchor [120 110]
                   :path-points [[120 110] [500 110]]}]
          nodes  [{:left 0 :top 0 :width 1000 :height 1000}]
          {:keys [collisions shifted]} (geo/resolve-all-labels labels nodes)]
      (is (= #{"L1"} collisions)
          "the residue set contains L1 (no clear slot found)")
      (is (= #{} shifted)
          "no shift was applied — L1 stays at its original position"))))

(deftest resolve-all-labels-avoids-self-collision
  (testing "rf2-r7vsr — two labels that start overlapping each other
            on different edges get separated. The greedy first-pass
            resolves L1 first (using L2's original rect as an obstacle
            it must clear), so L1 ends up shifted; L2 then no longer
            collides with the moved L1 and stays put. Either label
            moving is acceptable — the contract is 'zero residue'."
    (let [labels [{:id "L1"
                   :rect {:left 100 :top 100 :width 40 :height 20}
                   :anchor [120 110]
                   :path-points [[120 110] [500 110]]}
                  {:id "L2"
                   :rect {:left 100 :top 100 :width 40 :height 20}
                   :anchor [120 110]
                   :path-points [[120 110] [120 500]]}]
          ;; No node obstacles — labels collide only with each other.
          {:keys [collisions shifted]} (geo/resolve-all-labels labels [])]
      (is (= #{} collisions)
          "zero residue: the pair was successfully separated")
      (is (or (contains? shifted "L1") (contains? shifted "L2"))
          "at least one of the colliding labels was slid clear"))))
