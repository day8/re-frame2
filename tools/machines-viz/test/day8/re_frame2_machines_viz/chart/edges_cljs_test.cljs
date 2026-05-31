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
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

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

;; ---- elk-layout-options: the G5 cross-hierarchy switch ------------------
;;
;; `chart/elk-layout-options` is the pure root `layoutOptions` computation
;; `->elk-input` `clj->js`-es onto the elk graph. The G5 capability —
;; routing edges ACROSS nesting levels — rides entirely on whether this
;; map carries `elk.hierarchyHandling INCLUDE_CHILDREN`. Without it elk
;; uses `SEPARATE_CHILDREN` (lay each level out independently, no
;; cross-hierarchy edge routes), so these pins are the regression guard
;; for the switch (rf2-gpa9k). The G2 routing keys it pairs with
;; (rf2-cz8v6) are pinned alongside so the pairing can't silently drift.

(defn- nested-parsed
  "A parsed graph with a compound substate — `:child` carries a
  `:parent-id`, so the graph nests."
  []
  {:parallel? false
   :nodes [{:id "parent" :compound? true}
           {:id "child" :parent-id "parent"}]
   :edges []})

(defn- parallel-parsed
  "A parsed graph flagged `:parallel?` (orthogonal regions) with no
  per-node `:parent-id` — parallelism alone must enable the switch."
  []
  {:parallel? true
   :nodes [{:id "regionA"} {:id "regionB"}]
   :edges []})

(defn- flat-parsed
  "A plain, single-level machine — no nesting, not parallel."
  []
  {:parallel? false
   :nodes [{:id "a"} {:id "b"}]
   :edges []})

(deftest elk-layout-options-nested-enables-cross-hierarchy
  (testing "rf2-gpa9k (G5) — a NESTED graph (a node has :parent-id, e.g.
            a compound substate) requests cross-hierarchy routing:
            elk.hierarchyHandling INCLUDE_CHILDREN"
    (is (= "INCLUDE_CHILDREN"
           (get (chart/elk-layout-options (nested-parsed) nil :tb)
                "elk.hierarchyHandling")))))

(deftest elk-layout-options-parallel-enables-cross-hierarchy
  (testing "rf2-gpa9k (G5) — a PARALLEL graph (:parallel? true) requests
            cross-hierarchy routing even with no per-node :parent-id"
    (is (= "INCLUDE_CHILDREN"
           (get (chart/elk-layout-options (parallel-parsed) nil :tb)
                "elk.hierarchyHandling")))))

(deftest elk-layout-options-flat-omits-cross-hierarchy
  (testing "rf2-gpa9k (G5) — a FLAT, non-parallel graph does NOT set
            elk.hierarchyHandling, so elk's per-level default
            (SEPARATE_CHILDREN) stands"
    (is (not (contains? (chart/elk-layout-options (flat-parsed) nil :tb)
                        "elk.hierarchyHandling")))))

(deftest elk-layout-options-pins-g2-routing-keys
  (testing "rf2-cz8v6 (G2) — the routing keys cross-hierarchy bend-points
            depend on are present on every graph: elk.edgeRouting
            ORTHOGONAL (Manhattan routes around containers) + edgeCoords
            ROOT (absolute coords so the lifted bends match xyflow's
            frame). G5's INCLUDE_CHILDREN only pays off when paired here."
    (doseq [parsed [(nested-parsed) (parallel-parsed) (flat-parsed)]]
      (let [opts (chart/elk-layout-options parsed nil :tb)]
        (is (= "ORTHOGONAL" (get opts "elk.edgeRouting")))
        (is (= "ROOT" (get opts "elk.json.edgeCoords")))))))

(deftest elk-layout-options-direction-from-arg
  (testing "rf2-gpa9k — elk.direction is forced from the direction arg
            (:lr → RIGHT, :tb → DOWN), independent of the switch"
    (is (= "RIGHT" (get (chart/elk-layout-options (flat-parsed) nil :lr)
                        "elk.direction")))
    (is (= "DOWN" (get (chart/elk-layout-options (flat-parsed) nil :tb)
                       "elk.direction")))))

(deftest elk-layout-options-host-overrides-merge
  (testing "rf2-gpa9k — host :layout-options merge on top of the canonical
            defaults without clobbering the G2 routing keys or the G5
            switch"
    (let [opts (chart/elk-layout-options
                 (nested-parsed) {"elk.spacing.nodeNode" "99"} :tb)]
      (is (= "99" (get opts "elk.spacing.nodeNode")) "host override applied")
      (is (= "ORTHOGONAL" (get opts "elk.edgeRouting")) "G2 key survives")
      (is (= "INCLUDE_CHILDREN" (get opts "elk.hierarchyHandling"))
          "G5 switch survives"))))

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

;; ---- self-loop fan (rf2-shv82, Issue 2) --------------------------------
;;
;; Multiple self-loops on one node fan around the perimeter so each
;; label gets its own slot (the bug: 3 self-loops on `:disconnected` in
;; the testdeck rendered overlapping garbled text). The projector
;; assigns each a per-source `loop-index` (0..N-1); `edge-path` rotates
;; the loop's anchor + label to a distinct slot per ordinal.

(deftest edge-path-self-loop-zero-index-is-historical-slot
  (testing "rf2-shv82 — loop-index 0 (the single-self-loop case) renders
            in the historical top-right slot: same start point, label
            roughly to the right of the source handle"
    (let [{:keys [d label-x label-y routed?]}
          (edges/edge-path (assoc base-coords
                                  :self-loop? true
                                  :loop-index 0))]
      (is (false? routed?))
      (is (str/starts-with? d "M 0,0"))
      ;; slot 0 is top-right (angle = -π/4) — label has positive x, negative y
      (is (pos? label-x) "slot 0 label sits to the right of the source")
      (is (neg? label-y) "slot 0 label sits above the source (top-right)"))))

(deftest edge-path-multi-self-loops-fan-to-distinct-slots
  (testing "rf2-shv82 (Issue 2) — three self-loops on the same source
            (loop-indexes 0/1/2) get DISTINCT label positions so their
            labels don't stack and garble"
    (let [labels-at (fn [i]
                     (-> (edges/edge-path (assoc base-coords
                                                 :self-loop? true
                                                 :loop-index i))
                         (select-keys [:label-x :label-y])))
          slot-0 (labels-at 0)
          slot-1 (labels-at 1)
          slot-2 (labels-at 2)]
      (is (not= slot-0 slot-1) "slot 0 != slot 1")
      (is (not= slot-1 slot-2) "slot 1 != slot 2")
      (is (not= slot-0 slot-2) "slot 0 != slot 2 (no collision)"))))

(deftest edge-path-self-loop-fan-label-separation
  (testing "rf2-shv82 (Issue 2) — adjacent fan slots have non-trivial
            Euclidean separation so labels DO NOT overlap (catches a
            regression to a tiny offset that would still read garbled)"
    (let [d-of (fn [a b]
                 (js/Math.hypot (- (:label-x a) (:label-x b))
                                (- (:label-y a) (:label-y b))))
          slot (fn [i] (edges/edge-path (assoc base-coords
                                               :self-loop? true
                                               :loop-index i)))
          ;; The minimum label-anchor separation: ~radius worth of motion
          ;; between adjacent slots. 24px is generous enough for the
          ;; backplate width (~one event-label segment).
          min-sep 24]
      (is (> (d-of (slot 0) (slot 1)) min-sep))
      (is (> (d-of (slot 1) (slot 2)) min-sep))
      (is (> (d-of (slot 0) (slot 2)) min-sep)))))

(deftest edge-path-self-loop-fan-wraps-past-eight-slots
  (testing "rf2-shv82 (Issue 2) — > 8 self-loops on one node (rare; >8
            distinct events on a single state is a code-smell the user
            owns) wraps via mod, so an out-of-range index does not crash"
    (let [{:keys [routed?]}
          (edges/edge-path (assoc base-coords :self-loop? true :loop-index 9))]
      (is (false? routed?))
      ;; If the slot lookup blew up the test would throw before this.
      (is true "loop-index past the slot count wraps cleanly"))))

(deftest edge-path-self-loop-nil-index-treated-as-zero
  (testing "rf2-shv82 (Issue 2) — a nil loop-index (a non-self-loop
            edge accidentally going down the self-loop branch, or pre-
            projector callers) treats it as slot 0 so behaviour stays
            historical"
    (let [a (edges/edge-path (assoc base-coords :self-loop? true :loop-index nil))
          b (edges/edge-path (assoc base-coords :self-loop? true :loop-index 0))]
      (is (= (:label-x a) (:label-x b)))
      (is (= (:label-y a) (:label-y b))))))

;; ---- cross-hierarchy label placement (rf2-shv82, Issue 3) --------------

(deftest edge-path-cross-hierarchy-label-near-source-bend
  (testing "rf2-shv82 (Issue 3) — when a cross-hierarchy edge has a
            routed path, the label anchors NEAR the first bend after
            the source handle (Stately convention), NOT at the routed
            midpoint (which can land far from the visual origin)"
    (let [;; L-shaped path: (0,0) → (0,120) → (160,120) → (160,200).
          ;; midpoint of the middle segment is (80, 120); the source-
          ;; side bend is at (0, 120). The cross-hierarchy label MUST
          ;; sit near (0, 120), NOT (80, 120).
          points (array (pt 0 0) (pt 0 120) (pt 160 120) (pt 160 200))
          plain  (edges/edge-path (assoc base-coords
                                         :self-loop? false
                                         :points points))
          xhier  (edges/edge-path (assoc base-coords
                                         :self-loop? false
                                         :points points
                                         :cross-hierarchy? true))]
      (is (true? (:routed? plain)))
      (is (true? (:routed? xhier)))
      ;; non-cross-hierarchy edge: midpoint of middle segment
      (is (= 80 (:label-x plain)) "plain routed label at middle-segment midpoint")
      (is (= 120 (:label-y plain)))
      ;; cross-hierarchy edge: near the first bend (0, 120) — within 10px
      (is (< (js/Math.abs (- (:label-x xhier) 0)) 10)
          "cross-hierarchy label hugs the source-side bend's x")
      (is (< (js/Math.abs (- (:label-y xhier) 120)) 10)
          "cross-hierarchy label hugs the source-side bend's y"))))

(deftest edge-path-cross-hierarchy-flag-does-not-affect-self-loop
  (testing "rf2-shv82 (Issue 3) — :cross-hierarchy? on a self-loop is a
            no-op (a self-loop is never cross-hierarchy per the
            projector); the self-loop branch still wins"
    (let [a (edges/edge-path (assoc base-coords :self-loop? true))
          b (edges/edge-path (assoc base-coords :self-loop? true
                                    :cross-hierarchy? true))]
      (is (= (:d a) (:d b)) "self-loop path is identical")
      (is (= (:label-x a) (:label-x b))
          "self-loop label is identical (cross-hierarchy is ignored)"))))

(deftest edge-path-cross-hierarchy-two-point-route-falls-back-to-mid
  (testing "rf2-shv82 (Issue 3) — a routed cross-hierarchy edge with a
            degenerate two-point route (no interior bend to anchor on)
            falls back to the segment midpoint so the label still
            renders SOMEWHERE on the path"
    (let [points (array (pt 10 10) (pt 90 90))
          {:keys [label-x label-y routed?]}
          (edges/edge-path (assoc base-coords
                                  :self-loop? false
                                  :points points
                                  :cross-hierarchy? true))]
      (is (true? routed?))
      (is (= 50 label-x) "midpoint x")
      (is (= 50 label-y) "midpoint y"))))

(deftest edge-path-cross-hierarchy-bezier-fallback-unchanged
  (testing "rf2-shv82 (Issue 3) — a cross-hierarchy edge with NO route
            (the bezier fallback before elk resolves) takes the same
            bezier path as a same-parent edge — cross-hierarchy only
            matters for the routed label anchor"
    (let [plain (edges/edge-path (assoc base-coords :self-loop? false
                                        :points nil))
          xhier (edges/edge-path (assoc base-coords :self-loop? false
                                        :points nil
                                        :cross-hierarchy? true))]
      (is (false? (:routed? plain)))
      (is (false? (:routed? xhier)))
      (is (= (:d plain) (:d xhier))
          "bezier fallback is identical regardless of cross-hierarchy"))))

;; ---- producer → consumer bridge (rf2-r636q) ----------------------------
;;
;; The dead-G2 bug shipped GREEN because no test bridged the two halves
;; of the :edge-points contract:
;;
;;   PRODUCER  `chart/elk-result->positions` keys :edge-points by the elk
;;             edge id — `<spec-edge-id>__in` / `<spec-edge-id>__out`
;;             (the two edges `->elk-input` splits each transition into
;;             under the events-as-nodes paradigm, rf2-qo5xy).
;;   CONSUMER  `projection/xyflow-graph` looked up the BARE canonical
;;             `<spec-edge-id>`, which the producer never emits → every
;;             lookup missed → :points always nil → silent bezier
;;             fallback (a real visual regression: cross-hierarchy edges
;;             cut straight across containers).
;;
;; The producer half (`elk-edge-points`) and the consumer half (the
;; projection pins above) were each green in isolation. This test feeds
;; a STUBBED elk result (shaped exactly like elkjs's output, with the
;; `__in` / `__out` edge ids) through the real producer, then through
;; the real consumer, and asserts the routes land on the right xyflow
;; edges — the integration that was never exercised. It FAILS before the
;; fix (bare-id lookup → nil points) and PASSES after (per-segment
;; `__in` / `__out` lookup).

(defn- ->elk-node-with-edges
  "A synthetic elk result node carrying laid-out child node positions +
  an `edges` array (each elk edge has an `id` + `sections`). Mirrors the
  shape `elk-result->positions` walks."
  [children elk-edges]
  #js {:id       "root"
       :children (apply array children)
       :edges    (apply array elk-edges)})

(defn- ->elk-edge [id sections]
  #js {:id id :sections (apply array sections)})

(deftest producer-consumer-bridge-routes-in-and-out-segments
  (testing "rf2-r636q — end-to-end: a stubbed elk result whose edges use
            the `<spec-edge-id>__in` / `<spec-edge-id>__out` ids flows
            through `chart/elk-result->positions` (PRODUCER) into
            `projection/xyflow-graph` (CONSUMER); the `__in` route lands
            on the inbound xyflow edge and the `__out` route on the
            outbound xyflow edge. (Pre-fix the consumer keyed on the bare
            id and BOTH segments fell back to a straight bezier.)"
    (let [parsed     (layout/parse-definition
                       {:initial :idle
                        :states  {:idle    {:on {:start :loading}}
                                  :loading {}}})
          start      (->> (:edges parsed)
                          (filter #(= :start (:event %)))
                          first)
          spec-id    (:id start)
          ;; An L-shaped __in route (source-state → event-node) and a
          ;; distinct L-shaped __out route (event-node → target). Each
          ;; has an interior bend so it is a genuine multi-point route,
          ;; not a degenerate straight line.
          in-edge    (->elk-edge
                       (str spec-id "__in")
                       [(->section (pt 0 0) [(pt 0 40) (pt 50 40)] (pt 50 80))])
          out-edge   (->elk-edge
                       (str spec-id "__out")
                       [(->section (pt 50 80) [(pt 50 120) (pt 120 120)] (pt 120 160))])
          elk-result (->elk-node-with-edges [] [in-edge out-edge])
          ;; PRODUCER: lift the elk result. :edge-points is keyed by the
          ;; elk edge ids (__in / __out).
          {:keys [edge-points]} (chart/elk-result->positions elk-result)
          ;; Sanity: the producer keyed by the elk edge ids, NOT the bare
          ;; canonical id (the exact mismatch rf2-r636q fixes).
          _          (is (contains? edge-points (str spec-id "__in")))
          _          (is (contains? edge-points (str spec-id "__out")))
          _          (is (not (contains? edge-points spec-id))
                         "producer never emits a bare-canonical key")
          ;; CONSUMER: feed the produced :edge-points straight in.
          graph      (projection/xyflow-graph parsed {} {:edge-points edge-points})
          xy-in      (first (filter #(= (str spec-id "__in")  (:id %)) (:edges graph)))
          xy-out     (first (filter #(= (str spec-id "__out") (:id %)) (:edges graph)))]
      (is (some? xy-in))
      (is (some? xy-out))
      (is (= [{:x 0 :y 0} {:x 0 :y 40} {:x 50 :y 40} {:x 50 :y 80}]
             (:points (:data xy-in)))
          "the __in route reaches the inbound edge end-to-end")
      (is (= [{:x 50 :y 80} {:x 50 :y 120} {:x 120 :y 120} {:x 120 :y 160}]
             (:points (:data xy-out)))
          "the __out route reaches the outbound edge end-to-end")
      ;; And the rendered geometry actually routes THROUGH the bends —
      ;; the path string is a poly-path (Q segments), not a bezier (C).
      (let [{:keys [d routed?]}
            (edges/edge-path (assoc base-coords
                                    :self-loop? false
                                    :points (apply array
                                                   (map #(pt (:x %) (:y %))
                                                        (:points (:data xy-out))))))]
        (is (true? routed?) "the outbound segment renders as a routed path")
        (is (str/includes? d "Q") "routed path uses quadratic corner segments")
        (is (not (str/includes? d "C")) "routed path is NOT a single bezier")))))
