(ns day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry-cljs-test
  "Pure-data tests for the xyflow `:after`-ring overlay geometry
  (rf2-uv1on · xyflow Phase 2).

  The overlay walks the rendered DOM to find each bearing node's
  bounding rect; this geometry layer turns those rects + the overlay
  container's rect into the ring's `{:cx :cy :r}` in overlay-local
  coordinates. Pure → JVM-runnable, so the math is pinned without a
  DOM.

  Dual-target via the `_cljs_test.cljc` extension — same pattern every
  machines-viz helper test uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry
             :as geo]))

;; ---- rect-center --------------------------------------------------------

(deftest rect-center-is-midpoint
  (is (= [150.0 124.0]
         (geo/rect-center {:left 100 :top 100 :width 100 :height 48}))))

(deftest rect-center-handles-zero-origin
  (is (= [70.0 24.0]
         (geo/rect-center {:left 0 :top 0 :width 140 :height 48}))))

;; ---- ring-radius --------------------------------------------------------

(deftest ring-radius-half-longer-side-plus-gap
  ;; 140 × 48 → half longer side = 70; + gap (6) = 76.
  (is (= 76.0 (geo/ring-radius {:width 140 :height 48})))
  ;; portrait node — height is the longer side.
  (is (= 106.0 (geo/ring-radius {:width 48 :height 200}))))

(deftest ring-radius-scales-gap-by-zoom
  ;; At 2x zoom the gap doubles (12) but the measured half-side is
  ;; UNCHANGED — xyflow already scaled the rect, so only the gap scales.
  (is (= 82.0 (geo/ring-radius {:width 140 :height 48} 2.0))))

(deftest ring-radius-floors-at-minimum
  ;; A tiny / unmeasured node still draws a visible ring.
  (is (= (double geo/min-ring-radius-px)
         (geo/ring-radius {:width 4 :height 4})))
  (is (= (double geo/min-ring-radius-px)
         (geo/ring-radius {:width 0 :height 0}))))

;; ---- node->ring ---------------------------------------------------------

(deftest node->ring-translates-into-container-local-coords
  ;; Node centred at (170, 124) in the viewport; container origin at
  ;; (20, 10) → overlay-local centre (150, 114).
  (let [g (geo/node->ring {:left 100 :top 100 :width 140 :height 48}
                          {:left 20  :top 10  :width 900 :height 400})]
    (is (= 150.0 (:cx g)))
    (is (= 114.0 (:cy g)))
    (is (= 76.0  (:r g)))))

(deftest node->ring-nil-on-missing-or-degenerate-rect
  (is (nil? (geo/node->ring nil {:left 0 :top 0 :width 10 :height 10})))
  (is (nil? (geo/node->ring {:left 0 :top 0 :width 10 :height 10} nil)))
  (is (nil? (geo/node->ring {:left 0 :top 0 :width 0 :height 48}
                            {:left 0 :top 0 :width 10 :height 10}))
      "zero-width node (not laid out yet) → no ring"))

;; ---- state->node-testid -------------------------------------------------

(deftest state->node-testid-matches-state-node-contract
  (is (= "rf-mv-chart-node-idle" (geo/state->node-testid "idle")))
  (is (= "rf-mv-chart-node-auth_login__idle"
         (geo/state->node-testid "auth_login__idle"))))

(deftest state->node-testid-nil-on-blank
  (is (nil? (geo/state->node-testid nil)))
  (is (nil? (geo/state->node-testid "")))
  (is (nil? (geo/state->node-testid "   "))))

;; ---- overlay-rings (the seam the CLJS overlay drives) ------------------

(def ^:private container {:left 0 :top 0 :width 900 :height 400})

(deftest overlay-rings-positions-each-measured-spec
  (let [specs [{:node-id "idle"    :color :green :fraction 0.8}
               {:node-id "authing" :color :amber :fraction 0.5}]
        rects {"idle"    {:left 100 :top 100 :width 140 :height 48}
               "authing" {:left 300 :top 100 :width 140 :height 48}}
        rings (geo/overlay-rings specs rects container 1.0)]
    (is (= 2 (count rings)))
    (is (= 170.0 (-> rings first :cx)) "idle centre x")
    (is (= 124.0 (-> rings first :cy)))
    ;; Presentation payload is preserved alongside the geometry.
    (is (= :green (-> rings first :color)))
    (is (= 0.8    (-> rings first :fraction)))
    (is (= 370.0 (-> rings second :cx)) "authing centre x")))

(deftest overlay-rings-drops-unmeasured-nodes
  ;; A spec whose node has no measured rect (off-screen / not mounted /
  ;; compound parent without a leaf) is dropped — the overlay has
  ;; nothing to position.
  (let [specs [{:node-id "idle"  :color :green}
               {:node-id "ghost" :color :red}]   ;; no rect → dropped
        rects {"idle" {:left 100 :top 100 :width 140 :height 48}}
        rings (geo/overlay-rings specs rects container 1.0)]
    (is (= 1 (count rings)))
    (is (= "idle" (-> rings first :node-id)))))

(deftest overlay-rings-empty-on-no-specs
  (is (= [] (geo/overlay-rings [] {} container 1.0)))
  (is (= [] (geo/overlay-rings nil nil container 1.0))))
