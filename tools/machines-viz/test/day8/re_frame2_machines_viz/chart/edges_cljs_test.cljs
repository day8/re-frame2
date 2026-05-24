(ns day8.re-frame2-machines-viz.chart.edges-cljs-test
  "CLJS tests for the MachineChart edge path-selection (rf2-cz8v6, G2).

  `chart.edges/edge-path` is the pure source-of-truth for the SVG path a
  transition edge renders: self-loop → elk bend-point route → bezier
  fallback. The renderer (`transition-edge`) calls it, so pinning the
  helper pins the rendered geometry without racing the async elkjs
  layout pass (the full-mount DOM suite can't await elk).

  The ns requires `chart.edges`, which `:require`s `@xyflow/react` — a
  pure-JS module that loads fine under Node (only its React-DOM
  COMPONENTS need a browser; `getBezierPath` is pure math). So this runs
  under the `:node-test` build (`cljs-test$` ns-regexp) — it is NOT a
  `-dom-cljs-test` (no DOM mount), and the JVM `clojure -M:test` runner
  skips it (it scans .clj/.cljc only, never .cljs)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.chart.edges :as edges]))

;; ---- fixtures ----------------------------------------------------------

(defn- pt [x y] #js {:x x :y y})

(def ^:private base-coords
  "Source/target handle coords + handle positions shared by the cases."
  {:src-x 0 :src-y 0 :tgt-x 100 :tgt-y 200
   :src-pos "bottom" :tgt-pos "top"})

;; ---- elk-edge-points: JS elk section → absolute point vector -----------
;;
;; `chart/elk-edge-points` is the JS-interop lift that turns one elk
;; edge's `sections` (start → bend… → end) into the `[{:x :y} …]` vector
;; the projector carries. It walks real elk JS objects, so it lives in
;; chart.cljs (which `:require`s elkjs — loads under Node); these pins
;; exercise it against synthetic section objects shaped exactly like
;; elk's output.

(defn- ->section
  "Build a synthetic elk edge `section` JS object."
  [start bends end]
  #js {:startPoint start
       :bendPoints  (apply array bends)
       :endPoint    end})

(defn- ->edge [sections]
  #js {:sections (apply array sections)})

(deftest elk-edge-points-chains-start-bends-end
  (testing "rf2-cz8v6 — a single-section edge lifts to start → bend… →
            end as a CLJS vector of {:x :y} maps (absolute coords)"
    (let [edge (->edge [(->section (pt 0 0) [(pt 0 50) (pt 80 50)] (pt 80 100))])]
      (is (= [{:x 0 :y 0} {:x 0 :y 50} {:x 80 :y 50} {:x 80 :y 100}]
             (chart/elk-edge-points edge))))))

(deftest elk-edge-points-collapses-duplicate-seam-points
  (testing "rf2-cz8v6 — across two sections the first's endPoint can
            repeat the next's startPoint; consecutive duplicates collapse
            so the path has no zero-length segment"
    (let [edge (->edge [(->section (pt 0 0) [] (pt 0 50))
                        (->section (pt 0 50) [] (pt 60 50))])]
      (is (= [{:x 0 :y 0} {:x 0 :y 50} {:x 60 :y 50}]
             (chart/elk-edge-points edge))
          "the shared (0,50) seam point appears once"))))

(deftest elk-edge-points-nil-without-sections
  (testing "rf2-cz8v6 — an edge elk gave no route (no sections) lifts to
            nil so the projector falls back to the bezier"
    (is (nil? (chart/elk-edge-points #js {})))
    (is (nil? (chart/elk-edge-points #js {:sections #js []})))))

(deftest elk-edge-points-nil-for-degenerate-single-point
  (testing "rf2-cz8v6 — a section that collapses to one point is not a
            real route (nothing to route THROUGH) → nil"
    (let [edge (->edge [(->section (pt 5 5) [] (pt 5 5))])]
      (is (nil? (chart/elk-edge-points edge))))))

;; ---- elk-route case (the G2 capability) --------------------------------

(deftest edge-path-routes-through-bend-points
  (testing "rf2-cz8v6 — when elk supplies a multi-point route, the path
            runs THROUGH every bend (the path string mentions each
            interior bend's coords), NOT a straight/bezier shortcut"
    (let [;; an L-shaped route around a container corner:
          ;; (0,0) → (0,120) → (160,120) → (160,200)
          points (array (pt 0 0) (pt 0 120) (pt 160 120) (pt 160 200))
          {:keys [d routed?]} (edges/edge-path
                                (assoc base-coords
                                       :self-loop? false :points points))]
      (is (true? routed?) "a multi-point route is :routed?")
      (is (str/starts-with? d "M 0,0")
          "the path starts at the route's first point")
      ;; the corner-rounding emits the bend vertices as quadratic control
      ;; points, so each interior bend's coords appear verbatim in the `d`.
      (is (str/includes? d "120") "the path bends at the y=120 corner row")
      (is (str/includes? d "160") "the path bends at the x=160 corner column")
      (is (str/ends-with? d "160,200")
          "the path ends at the route's last point")
      ;; a bezier shortcut would be a single C command; a routed path is
      ;; a poly-path of L/Q segments.
      (is (str/includes? d "Q") "rounded corners use quadratic segments")
      (is (not (str/includes? d "C"))
          "a routed path is NOT a single bezier curve"))))

(deftest edge-path-routed-label-sits-on-the-route
  (testing "rf2-cz8v6 — a routed edge's label anchors on the route
            (the midpoint of its middle segment), not at a bezier
            midpoint floating away from the bends"
    (let [points (array (pt 0 0) (pt 0 100) (pt 100 100) (pt 100 200))
          {:keys [label-x label-y routed?]}
          (edges/edge-path (assoc base-coords :self-loop? false :points points))]
      (is (true? routed?))
      ;; middle segment is (0,100)→(100,100): midpoint (50,100).
      (is (= 50 label-x))
      (is (= 100 label-y)))))

(deftest edge-path-two-point-route-is-a-straight-line
  (testing "rf2-cz8v6 — a degenerate two-point route (no interior bend)
            renders a straight M…L… line, still flagged :routed?"
    (let [points (array (pt 10 10) (pt 90 90))
          {:keys [d routed?]}
          (edges/edge-path (assoc base-coords :self-loop? false :points points))]
      (is (true? routed?))
      (is (= "M 10,10 L 90,90" d)))))

;; ---- bezier fallback (the no-bend-point case) --------------------------

(deftest edge-path-falls-back-to-bezier-without-points
  (testing "rf2-cz8v6 — a simple edge with NO elk points falls back to
            the bezier path (xyflow `getBezierPath` → a single C curve),
            and is NOT flagged :routed?"
    (let [{:keys [d routed?]}
          (edges/edge-path (assoc base-coords :self-loop? false :points nil))]
      (is (false? routed?) "no points → not routed (bezier fallback)")
      (is (string? d))
      (is (str/includes? d "C")
          "the bezier fallback is a cubic curve (C command)"))))

(deftest edge-path-empty-points-falls-back-to-bezier
  (testing "rf2-cz8v6 — a single-point (or empty) route is not a real
            route; the edge falls back to the bezier"
    (let [{:keys [routed?]}
          (edges/edge-path (assoc base-coords :self-loop? false
                                  :points (array (pt 5 5))))]
      (is (false? routed?) "a one-point route is too degenerate to route"))))

;; ---- self-loop (unchanged) ---------------------------------------------

(deftest edge-path-self-loop-keeps-its-loop-path
  (testing "rf2-cz8v6 — a self-loop keeps its dedicated small-loop path
            and is NEVER routed, even if elk emitted bend-points for it"
    (let [{:keys [d routed?]}
          (edges/edge-path (assoc base-coords
                                  :self-loop? true
                                  ;; elk route present but must be ignored
                                  :points (array (pt 0 0) (pt 9 9) (pt 18 0))))]
      (is (false? routed?) "self-loops are never routed")
      ;; the loop path is the shipped cubic loop off the source handle.
      (is (str/starts-with? d "M 0,0") "loop starts at the source handle")
      (is (str/includes? d "C") "the self-loop is a cubic loop")
      (is (not (str/includes? d "Q"))
          "the self-loop is NOT the routed poly-path"))))
