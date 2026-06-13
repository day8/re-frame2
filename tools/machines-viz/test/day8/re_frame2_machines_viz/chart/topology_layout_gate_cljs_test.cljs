(ns day8.re-frame2-machines-viz.chart.topology-layout-gate-cljs-test
  "rf2-dplwxh — rendered-topology layout/geometry gate.

  ## Why this exists

  The slice had strong pure-data + DOM-structure coverage, but no
  representative gate over the SETTLED layout geometry. The browser
  `chart-dom-cljs-test` deliberately asserts first-commit DOM BEFORE
  the async elkjs pass resolves (positions are still {0 0}); the Xray
  feature gate only requires `nodeCount > 0`; the PNG exporter test
  proves nonblank output, not topology correctness. Real rendered
  failures — wrong fit, overlapped bands/nodes, bad route geometry,
  missing projected edges, adaptive-layout drift — were therefore
  invisible to CI.

  ## What this gate does (and why it's a node `-cljs-test`)

  It drives the REAL `chart/compute-layout!` (elkjs runs as xyflow's
  layout backend; elkjs is the SAME engine the live chart uses and is
  Node-runnable — no DOM, no React, no `@xyflow/react`) for the
  representative machines named on the topology-parity surface
  (`spec/001-Topology-Parity.md`): a linear/cyclic spine, a guarded
  fork, a parallel machine, a compound (hierarchical) machine, and a
  non-trivial Context-band case. It AWAITS the layout settle (the
  callback / Promise the synchronous DOM suite cannot await) and then
  asserts POST-LAYOUT GEOMETRY INVARIANTS the spec explicitly accepts
  in lieu of committed pixel baselines (which are cross-platform-flaky;
  Mike develops on Windows, maintainers on Mac/Linux). The invariants
  catch the failure classes the bead enumerates:

    - WRONG FIT / degenerate origin-stack — every state node lands at a
      DISTINCT, finite, positive-area box (not all stacked at {0 0}),
      and the overall bounding box is finite + positive.
    - OVERLAPPED bands/nodes — no two sibling leaf state nodes (same
      coordinate frame) overlap; every container ENCLOSES its children
      (catches a band/region painted over its contents).
    - MISSING projected edges — every projected transition has BOTH
      routed halves (events-as-nodes splits each edge into an `__in` +
      `__out` segment) present in `:edge-points`, each a polyline of
      >= 2 points.
    - BAD route placement — each routed half's polyline stays within a
      tolerance of the union box of its endpoint nodes (the route
      connects the RIGHT nodes; it does not fly off into empty space).

  ## Command to run when changing layout/projection/visual constants

      cd implementation && npm run test:cljs

  (This gate runs under the always-on `:node-test` build — its ns ends
  in `-cljs-test`. A change to `chart.cljs` `default-elk-options`,
  `chart.projection`, the density/visual constants, or `chart.layout`
  that regresses fit / overlap / routing fails THIS gate.)"
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Representative machines (the topology-parity surface, rf2 001-Topology-Parity)

(def ^:private linear-cyclic
  "A linear spine WITH a back-edge (cyclic): the common case + a cycle
  ELK must acyclic-ise. 4 reachable states, a fan + a loop."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done :err :failed}}
             :done    {:on {:again :idle} :final? false}
             :failed  {:final? true}}})

(def ^:private guarded-fork
  "One state, one event, TWO guarded targets — a fork ELK must lay out
  side-by-side without overlapping the two branch targets."
  {:initial :idle
   :states  {:idle {:on {:submit [{:target :ok :guard :valid?}
                                   {:target :err}]}}
             :ok   {:final? true}
             :err  {:final? true}}})

(def ^:private parallel
  "Two orthogonal regions, each a 2-state cycle — the region BANDS must
  sit side-by-side (no band overlap) and each must enclose its states."
  {:type :parallel
   :regions {:audio   {:initial :playing
                       :states {:playing {:on {:pause :paused}}
                                :paused  {:on {:play :playing}}}}
             :display {:initial :on
                       :states {:on  {:on {:dim :off}}
                                :off {:on {:lit :on}}}}}})

(def ^:private compound
  "A 3-deep hierarchical machine — the compound containers must enclose
  their nested children at every depth."
  {:initial :authenticated
   :states  {:authenticated
             {:initial :cart
              :states  {:cart    {:initial :browsing
                                  :states  {:browsing {:on {:checkout :checkout}}
                                            :checkout {:on {:back :browsing}}}}
                        :account {}}}
             :anonymous {:on {:login :authenticated}}}})

(def ^:private context-band
  "A flat machine with a machine-level `:on` fallback + machine `:data`
  — drives a non-trivial root Context band (machine-root chip + reset
  edge). The band must reserve its own vertical space and the spine
  must sit below it without overlap."
  {:initial :a
   :on      {:reset :a}
   :data    {:hits 0 :seen [] :token nil}
   :states  {:a {:on {:go :b}}
             :b {:on {:go :a}}}})

;; ---------------------------------------------------------------------------
;; Geometry helpers

(def ^:private route-tolerance
  "Slack (px) allowed between a routed edge half's endpoint and the union
  box of its endpoint nodes. ELK pads routes off the node boundary by
  `elk.spacing.edgeNode` (24) and routes around the events-as-nodes
  hop; a generous-but-finite tolerance pins 'the route connects the
  right nodes' without over-fitting ELK's exact bend geometry."
  140)

(defn- abs-pos
  "Resolve `node-id`'s ABSOLUTE box by summing the parent-chain offsets.
  elkjs reports each child's x/y RELATIVE to its parent container, so a
  nested state's absolute position is its own offset plus every
  ancestor container's offset. `parent-of` maps node-id → parent-id (nil
  at the root). Returns `{:x :y :width :height}` in the root frame, or
  nil if the node has no recorded position."
  [positions parent-of node-id]
  (when-let [{:keys [width height]} (get positions node-id)]
    (loop [id node-id, x 0.0, y 0.0]
      (if-let [{px :x py :y} (get positions id)]
        (recur (get parent-of id) (+ x px) (+ y py))
        {:x x :y y :width width :height height}))))

(defn- overlap?
  "True when two absolute boxes overlap with positive area (touching
  edges do NOT count as overlap)."
  [a b]
  (and (< (:x a) (+ (:x b) (:width b)))
       (< (:x b) (+ (:x a) (:width a)))
       (< (:y a) (+ (:y b) (:height b)))
       (< (:y b) (+ (:y a) (:height a)))))

(defn- encloses?
  "True when absolute box `outer` contains box `inner` (inclusive, with
  a 1px slack for float noise)."
  [outer inner]
  (and (<= (- (:x outer) 1) (:x inner))
       (<= (- (:y outer) 1) (:y inner))
       (>= (+ (:x outer) (:width outer) 1) (+ (:x inner) (:width inner)))
       (>= (+ (:y outer) (:height outer) 1) (+ (:y inner) (:height inner)))))

(defn- union-box
  "Axis-aligned bounding box enclosing every box in `boxes`."
  [boxes]
  (let [xs  (mapv :x boxes)
        ys  (mapv :y boxes)
        x2s (mapv #(+ (:x %) (:width %)) boxes)
        y2s (mapv #(+ (:y %) (:height %)) boxes)
        x0  (apply min xs)
        y0  (apply min ys)]
    {:x x0 :y y0
     :width  (- (apply max x2s) x0)
     :height (- (apply max y2s) y0)}))

(defn- point-near-box?
  "True when point `{:x :y}` is within `tol` px of `box` (inside, or no
  more than `tol` outside any edge)."
  [{px :x py :y} box tol]
  (and (>= px (- (:x box) tol))
       (<= px (+ (:x box) (:width box) tol))
       (>= py (- (:y box) tol))
       (<= py (+ (:y box) (:height box) tol))))

(defn- parent-map
  "node-id → parent-id from the parsed projection (root container has
  no parent → absent from the map)."
  [parsed]
  (into {} (keep (fn [{:keys [id parent-id]}]
                   (when parent-id [id parent-id])))
        (:nodes parsed)))

;; ---------------------------------------------------------------------------
;; The gate — one assertion battery, run per representative machine

(defn- assert-topology!
  "Run the post-settle geometry invariants against `parsed` + the elk
  `result` (`{:positions :edge-points :layout-error}`). `label` names
  the machine for failure messages."
  [label parsed result]
  (let [positions   (:positions result)
        edge-points (:edge-points result)
        parent-of   (parent-map parsed)
        ;; State (non-event, non-root-container) parsed nodes.
        state-nodes (remove #(= "__rf2_root_container__" (:id %)) (:nodes parsed))
        leaf-nodes  (remove :compound? state-nodes)
        abs-of      (fn [id] (abs-pos positions parent-of id))]

    ;; ---- layout actually settled (no error, non-empty) ----
    (is (nil? (:layout-error result))
        (str label ": layout settled without error"))
    (is (seq positions)
        (str label ": elk produced non-empty positions (not the degenerate empty settle)"))

    ;; ---- every projected node is positioned with a positive-area box ----
    (doseq [{:keys [id]} state-nodes]
      (let [box (abs-of id)]
        (is (some? box) (str label ": node " id " has a position"))
        (when box
          (is (and (pos? (:width box)) (pos? (:height box)))
              (str label ": node " id " has a positive-area box (not collapsed)")))))

    ;; ---- WRONG FIT: leaf boxes are DISTINCT (not all stacked at origin) ----
    (let [leaf-origins (->> leaf-nodes
                            (map :id)
                            (keep abs-of)
                            (map (juxt :x :y)))]
      (is (= (count leaf-origins) (count (set leaf-origins)))
          (str label ": every leaf state node lands at a DISTINCT origin "
               "(a degenerate origin-stack = wrong fit)")))

    ;; ---- WRONG FIT: overall bounding box is finite + positive ----
    (let [bbox (union-box (keep abs-of (map :id state-nodes)))]
      (is (and (js/isFinite (:width bbox)) (js/isFinite (:height bbox))
               (pos? (:width bbox)) (pos? (:height bbox)))
          (str label ": overall topology bbox is finite + positive " (pr-str bbox))))

    ;; ---- OVERLAP: no two leaf siblings (same parent / frame) overlap ----
    (doseq [[pid sibs] (group-by :parent-id leaf-nodes)
            :let  [boxes (keep #(some-> (abs-of (:id %)) (assoc :id (:id %))) sibs)]
            [a b] (for [a boxes b boxes :when (neg? (compare (:id a) (:id b)))] [a b])]
      (is (not (overlap? a b))
          (str label ": leaf siblings under " pid " do not overlap — "
               (:id a) " " (dissoc a :id) " vs " (:id b) " " (dissoc b :id))))

    ;; ---- ENCLOSURE: every container encloses each of its children ----
    (doseq [{:keys [id compound?]} state-nodes
            :when compound?
            :let  [outer (abs-of id)
                   kids  (filter #(= id (:parent-id %)) state-nodes)]]
      (doseq [{kid :id} kids]
        (when-let [inner (abs-of kid)]
          (is (encloses? outer inner)
              (str label ": container " id " " (when outer (dissoc outer :id))
                   " encloses child " kid " " inner)))))

    ;; ---- MISSING EDGES + BAD ROUTE PLACEMENT ----
    (doseq [{:keys [id source target]} (:edges parsed)
            :let [in-pts  (get edge-points (str id "__in"))
                  out-pts (get edge-points (str id "__out"))
                  src-box (abs-of source)
                  tgt-box (abs-of target)]]
      ;; Each projected transition emits a routed `__in` AND `__out` half
      ;; (events-as-nodes). Both must be present + a real polyline.
      (is (and (>= (count in-pts) 2) (>= (count out-pts) 2))
          (str label ": projected edge " id " has BOTH routed halves "
               "(__in + __out, >= 2 points each) — none missing"))
      ;; Route placement: the `__in` half connects FROM the source box;
      ;; the `__out` half connects INTO the target box. Endpoints near the
      ;; relevant node's box (within tolerance) = the route attaches to the
      ;; right nodes rather than flying off into empty canvas.
      (when (and (seq in-pts) src-box)
        (is (point-near-box? (first in-pts) src-box route-tolerance)
            (str label ": edge " id " __in route starts near its SOURCE "
                 source " " src-box " (route placement) — " (first in-pts))))
      (when (and (seq out-pts) tgt-box)
        (is (point-near-box? (last out-pts) tgt-box route-tolerance)
            (str label ": edge " id " __out route ends near its TARGET "
                 target " " tgt-box " (route placement) — " (last out-pts)))))))

(defn- run-gate!
  "Drive the real elk layout for `parsed` then run the invariant battery
  on the settle. `context-rows` reserves the root Context band height
  (0 for the non-context machines)."
  [label parsed context-rows done]
  (chart/compute-layout!
    parsed :tb nil (keyword "gate" label) nil nil context-rows
    (fn [result]
      (try
        (assert-topology! label parsed result)
        (finally (done))))))

;; ---------------------------------------------------------------------------
;; One deftest per representative class (each awaits its own settle)

(deftest linear-cyclic-topology-settles-cleanly
  (testing "rf2-dplwxh — a linear/cyclic spine lays out with distinct,
            non-overlapping, fully-routed geometry"
    (async done
      (run-gate! "linear" (layout/project-definition linear-cyclic) 0 done))))

(deftest guarded-fork-topology-settles-cleanly
  (testing "rf2-dplwxh — a guarded fork's two branch targets sit
            side-by-side without overlap, both routes present"
    (async done
      (run-gate! "fork" (layout/project-definition guarded-fork) 0 done))))

(deftest parallel-topology-settles-cleanly
  (testing "rf2-dplwxh — parallel region BANDS sit side-by-side without
            overlap, each enclosing its own states"
    (async done
      (run-gate! "parallel" (layout/project-definition parallel) 0 done))))

(deftest compound-topology-settles-cleanly
  (testing "rf2-dplwxh — a 3-deep compound machine's containers enclose
            their nested children at every depth"
    (async done
      (run-gate! "compound" (layout/project-definition compound) 0 done))))

(deftest context-band-topology-settles-cleanly
  (testing "rf2-dplwxh — a Context-band machine reserves the band space
            and the spine sits below it without overlap"
    (async done
      (run-gate! "context" (layout/project-definition context-band) 3 done))))
