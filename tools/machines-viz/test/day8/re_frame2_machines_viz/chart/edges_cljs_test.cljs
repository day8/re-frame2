(ns day8.re-frame2-machines-viz.chart.edges-cljs-test
  "The ELK interop and edge path-selection the chart renders through:
  `chart/elk-edge-points` / `elk-edge-label-pos` / `elk-result->positions`
  lift elk's JS result, `chart/elk-layout-options` builds the root
  `layoutOptions`, and `chart.edges/edge-path` picks the SVG path — elk route
  or bezier fallback — that `transition-edge` paints. All pure, so pinning
  them pins the rendered geometry without racing the async elk pass.

  `@xyflow/react` loads under Node (`getBezierPath` is pure math), so this
  runs on `:node-test`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

;; ---- fixtures ----------------------------------------------------------

(defn- pt [x y] #js {:x x :y y})

(def ^:private base-coords
  {:src-x 0 :src-y 0 :tgt-x 100 :tgt-y 200
   :src-pos "bottom" :tgt-pos "top"})

(def ^:private l-route
  "An L-shaped route around a container corner: (0,0) → (0,120) → (160,120)
  → (160,200)."
  (array (pt 0 0) (pt 0 120) (pt 160 120) (pt 160 200)))

(defn- ->section
  "A synthetic elk edge `section`."
  [start bends end]
  #js {:startPoint start
       :bendPoints  (apply array bends)
       :endPoint    end})

(defn- ->edge [sections]
  #js {:sections (apply array sections)})

(defn- ->edge-with-label [lbl]
  #js {:sections (array (->section (pt 0 0) [] (pt 0 100)))
       :labels   (array lbl)})

(defn- nested-parsed []
  {:parallel? false
   :nodes [{:id "parent" :compound? true}
           {:id "child" :parent-id "parent"}]
   :edges []})

(defn- parallel-parsed
  "Parallel, with no per-node `:parent-id`: parallelism alone must enable
  the cross-hierarchy switch."
  []
  {:parallel? true
   :nodes [{:id "regionA"} {:id "regionB"}]
   :edges []})

(defn- flat-parsed []
  {:parallel? false
   :nodes [{:id "a"} {:id "b"}]
   :edges []})

;; ---- elk-edge-points / elk-edge-label-pos ------------------------------

(deftest elk-edge-points-collapses-duplicate-seam-points
  (testing "a section's endPoint repeating the next section's startPoint
            appears once, so the path has no zero-length segment"
    (is (= [{:x 0 :y 0} {:x 0 :y 50} {:x 60 :y 50}]
           (chart/elk-edge-points (->edge [(->section (pt 0 0) [] (pt 0 50))
                                           (->section (pt 0 50) [] (pt 60 50))]))))))

(deftest elk-edge-points-nil-without-a-real-route
  (testing "no sections, or a section collapsing to one point, is no route —
            nil, so the projector falls back to the bezier"
    (is (nil? (chart/elk-edge-points #js {})))
    (is (nil? (chart/elk-edge-points (->edge [(->section (pt 5 5) [] (pt 5 5))]))))))

(deftest elk-edge-label-pos-lifts-placed-position
  (is (= {:x 33 :y 77}
         (chart/elk-edge-label-pos
           (->edge-with-label #js {:text "evt" :x 33 :y 77 :width 40 :height 16})))))

(deftest elk-edge-label-pos-nil-without-a-placed-label
  (testing "no labels, or a label elk did not place (no x/y — the empty-text
            label under events-as-nodes), lifts to nil"
    (is (nil? (chart/elk-edge-label-pos #js {})))
    (is (nil? (chart/elk-edge-label-pos (->edge-with-label #js {:text "" :width 0 :height 0}))))))

;; ---- elk-layout-options -------------------------------------------------

(deftest elk-layout-options-cross-hierarchy-follows-nesting
  (testing "a nested or parallel graph requests cross-hierarchy routing
            (elk.hierarchyHandling INCLUDE_CHILDREN); a flat graph leaves it
            unset so elk's per-level SEPARATE_CHILDREN default stands"
    (doseq [[label parsed expected] [["nested"   (nested-parsed)   "INCLUDE_CHILDREN"]
                                     ["parallel" (parallel-parsed) "INCLUDE_CHILDREN"]
                                     ["flat"     (flat-parsed)     ::absent]]]
      (is (= expected (get (chart/elk-layout-options parsed nil :tb)
                           "elk.hierarchyHandling" ::absent))
          label))))

(deftest elk-layout-options-layers-host-overrides-direction-and-routing-levers
  (testing "host :layout-options merge onto the defaults, elk.direction comes
            from the direction arg, and the root levers hold:
            - ORTHOGONAL routing + edgeCoords ROOT: absolute bend-points around
              containers, which INCLUDE_CHILDREN's cross-level routes need;
            - cycleBreaking DEPTH_FIRST: a cyclic chart ranks the forward spine
              from the initial state near the top/left;
            - considerModelOrder NODES_AND_EDGES: with the per-initial-edge
              priority it pulls a region's initial state to the start of its
              flow even in a pure-cyclic region"
    (let [want {"elk.spacing.nodeNode"               "99"
                "elk.direction"                      "RIGHT"
                "elk.hierarchyHandling"              "INCLUDE_CHILDREN"
                "elk.edgeRouting"                    "ORTHOGONAL"
                "elk.json.edgeCoords"                "ROOT"
                "elk.layered.cycleBreaking.strategy" "DEPTH_FIRST"
                "elk.layered.considerModelOrder"     "NODES_AND_EDGES"}]
      (is (= want (select-keys (chart/elk-layout-options
                                 (nested-parsed) {"elk.spacing.nodeNode" "99"} :lr)
                               (keys want)))))
    (is (= "DOWN" (get (chart/elk-layout-options (flat-parsed) nil :tb) "elk.direction")))))

;; ---- real elk: guarded-fork branch order --------------------------------
;;
;; The projection pins the INPUT to ELK (elk.position hints + semiInteractive
;; on the root-container child); this runs real elkjs and checks the OUTPUT.
;; Without those pins ELK's crossing minimisation reorders the same-rank
;; branches freely.

(def ^:private gate-fork-machine
  {:initial :idle
   :data    {:level 0}
   :states  {:idle     {:on {:gate/set   {:action :set-level}
                             :gate/check [{:guard :gate-high? :target :high}
                                          {:guard :gate-low?  :target :low}
                                          {:target :rejected}]}}
             :low      {:on {:gate/reset :idle}}
             :high     {:on {:gate/reset :idle}}
             :rejected {:on {:gate/reset :idle}}}})

(deftest real-elk-orders-gate-fork-branches-left-to-right
  (testing "real elkjs lays the three `:gate/check` branch event-nodes out
            left-to-right in priority order (`:tb` stacks them on x)"
    (async done
      (let [parsed   (layout/project-definition gate-fork-machine)
            by-guard (into {} (comp (filter #(= :gate/check (:event %)))
                                    (map (juxt :guard identity)))
                           (:edges parsed))
            ev-id    #(projection/event-node-id (get by-guard %))]
        (chart/compute-layout!
          parsed :tb nil :test/gate
          (fn [result]
            (try
              (let [[x1 x2 x3] (map #(get-in (:positions result) [(ev-id %) :x])
                                    [:gate-high? :gate-low? nil])]
                ;; `<` reads nil as 0, so the number? check is load-bearing.
                (is (and (every? number? [x1 x2 x3]) (< x1 x2 x3))
                    (str "branches resolve left-to-right 1,2,3 (x: " x1 " " x2 " " x3 ")")))
              (finally (done)))))))))

;; ---- edge-path: elk route ------------------------------------------------

(deftest edge-path-routes-through-bend-points
  (testing "a multi-point elk route renders THROUGH every bend as a
            rounded poly-path, not a bezier shortcut"
    (let [{:keys [d routed?]} (edges/edge-path (assoc base-coords :points l-route))]
      (is (true? routed?))
      (is (str/starts-with? d "M 0,0"))
      (is (str/includes? d "120") "bends at the y=120 corner row")
      (is (str/includes? d "160") "bends at the x=160 corner column")
      (is (str/ends-with? d "160,200"))
      (is (str/includes? d "Q") "rounded corners use quadratic segments")
      (is (not (str/includes? d "C")) "not a single bezier curve"))))

(deftest edge-path-two-point-route-is-a-straight-line
  (is (= {:d "M 10,10 L 90,90" :routed? true}
         (select-keys (edges/edge-path (assoc base-coords :points (array (pt 10 10) (pt 90 90))))
                      [:d :routed?]))))

(deftest edge-path-falls-back-to-bezier-without-a-route
  (testing "no points, or a single point, is not a route: the edge takes
            xyflow's bezier (one C curve), not flagged :routed?"
    (doseq [points [nil (array (pt 5 5))]]
      (let [{:keys [d routed?]} (edges/edge-path (assoc base-coords :points points))]
        (is (false? routed?))
        (is (str/includes? d "C"))))))

;; ---- edge-path: label anchor ---------------------------------------------

(deftest edge-path-elk-label-position-beats-cross-hierarchy-anchor
  (is (= [200 10]
         ((juxt :label-x :label-y)
          (edges/edge-path (assoc base-coords
                                  :points l-route
                                  :cross-hierarchy? true
                                  :label-pos {:x 200 :y 10}))))))

(deftest edge-path-cross-hierarchy-label-near-source-bend
  (testing "a routed edge labels at its middle-segment midpoint, but a
            cross-hierarchy one anchors near the first bend after its source,
            where the reader sees it originate"
    (let [plain (edges/edge-path (assoc base-coords :points l-route))
          xhier (edges/edge-path (assoc base-coords :points l-route :cross-hierarchy? true))]
      (is (= [80 120] ((juxt :label-x :label-y) plain)))
      (is (< (js/Math.abs (:label-x xhier)) 10) "hugs the source-side bend's x")
      (is (< (js/Math.abs (- (:label-y xhier) 120)) 10) "hugs the source-side bend's y"))))

(deftest edge-path-cross-hierarchy-two-point-route-falls-back-to-mid
  (testing "with no interior bend to anchor on, a cross-hierarchy label sits
            at the segment midpoint"
    (is (= [50 50]
           ((juxt :label-x :label-y)
            (edges/edge-path (assoc base-coords
                                    :points (array (pt 10 10) (pt 90 90))
                                    :cross-hierarchy? true)))))))

;; ---- producer → consumer bridge ----------------------------------------
;;
;; `->elk-input` splits each transition into `<spec-edge-id>__in` and
;; `__out` elk edges; `elk-result->positions` keys `:edge-points` by those
;; ids and `projection/xyflow-graph` must look routes up by the same ids. A
;; consumer keyed on the bare spec id would miss every route and silently
;; fall back to the bezier while each half stayed green alone.

(deftest producer-consumer-bridge-routes-in-and-out-segments
  (let [parsed  (layout/project-definition {:initial :idle
                                            :states  {:idle    {:on {:start :loading}}
                                                      :loading {}}})
        spec-id (:id (first (filter #(= :start (:event %)) (:edges parsed))))
        result  #js {:id       "root"
                     :children #js []
                     :edges    (array
                                 #js {:id       (str spec-id "__in")
                                      :sections (array (->section (pt 0 0) [(pt 0 40) (pt 50 40)] (pt 50 80)))}
                                 #js {:id       (str spec-id "__out")
                                      :sections (array (->section (pt 50 80) [(pt 50 120) (pt 120 120)] (pt 120 160)))})}
        graph   (projection/xyflow-graph parsed {} (select-keys (chart/elk-result->positions result)
                                                                [:edge-points]))
        points  (fn [suffix]
                  (some #(when (= (str spec-id suffix) (:id %)) (:points (:data %))) (:edges graph)))]
    (is (= [{:x 0 :y 0} {:x 0 :y 40} {:x 50 :y 40} {:x 50 :y 80}] (points "__in")))
    (is (= [{:x 50 :y 80} {:x 50 :y 120} {:x 120 :y 120} {:x 120 :y 160}] (points "__out")))))
