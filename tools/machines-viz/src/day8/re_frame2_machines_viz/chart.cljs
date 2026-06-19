(ns day8.re-frame2-machines-viz.chart
  "MachineChart — the xyflow-based state-chart component.

  rf2-gpzb4 (2026-05-21 xyflow override) — Mike's override of the
  2026-05-19 ELK+hand-rolled-SVG lock. The chart now sits on top of
  `@xyflow/react` (the same render engine Stately Studio uses) with
  custom Stately-style node + edge components from `chart.nodes` and
  `chart.edges`. elkjs runs as xyflow's layout backend so the same
  hierarchical-layered algorithm the previous engine used continues
  to drive node positions.

  ## What this owns

    - The `MachineChart` Reagent component (public surface per
      `tools/machines-viz/spec/API.md` §MachineChart).
    - The JS-interop ELK glue: the elkjs `compute-layout!` async
      pass + cache. (The pure-data projector that turns
      `chart.layout/project-definition` output into the xyflow
      `:nodes` + `:edges` shape lives in `chart.projection`.)

  ## What this does NOT own

    - **Topology parsing** — lives in `chart.layout`. This ns
      consumes the parsed graph but does not walk the definition
      tree.
    - **`countdown-ring` / `sparkline` primitives** — moved to
      `chart.primitives` since they are consumed OUTSIDE the chart
      (Xray overlays, cluster sparklines).
    - **Viewport state** — xyflow owns zoom/pan/fit internally;
      hosts no longer manage `{:scale :tx :ty}` slots. The previous
      `chart.controls` reducer + Xray's `machine_canvas` viewport
      machinery are obsolete with this migration.

  ## Bundle isolation

  `@xyflow/react` + `elkjs` are `devDependency`s of
  `implementation/package.json`. The chart is dev-only (Xray
  preload + the static viewer page). Production bundles MUST NOT
  pull either; the `check-bundle-isolation.cjs` sentinel pins this
  contract."
  (:require ["@xyflow/react" :as xyflow]
            ["elkjs/lib/elk.bundled.js" :as elkjs]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.layout-error :as layout-error]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.chart.post-elk :as post-elk]
            [day8.re-frame2-machines-viz.chart.nodes :as nodes]
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as after-rings]
            [day8.re-frame2-machines-viz.chart.overlays.spawn-all-join
             :as overlay-spawn-all]
            [day8.re-frame2-machines-viz.chart.overlays.cancellation-cascade
             :as overlay-cascade]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- xyflow React-class adapters ----------------------------------------

(def ^:private ReactFlow
  "xyflow `ReactFlow` React class. Used via Reagent's `:>` interop."
  (.-ReactFlow xyflow))

(def ^:private Background
  (.-Background xyflow))

(def ^:private Controls
  (.-Controls xyflow))

(def ^:private MiniMap
  (.-MiniMap xyflow))

(def ^:private BackgroundVariant
  (.-BackgroundVariant xyflow))

;; ---- elkjs layout backend ----------------------------------------------

(defonce ^:private elk-instance
  (let [Ctor (or (.-default elkjs) (.-ELK elkjs) elkjs)]
    (Ctor.)))

(def default-elk-options
  "Canonical elk.js `layoutOptions` for the chart. Tuned for state-
  machine readability per the rf2-0yil0 audit cluster + rf2-gg7ws
  visual-quality lift (kept across the xyflow migration since the
  layout engine itself is unchanged; only the renderer swapped).

  rf2-cz8v6 (G2 — bend-point edge routing): two routing-relevant keys.

    - `elk.edgeRouting` is `ORTHOGONAL` (not `SPLINES`): the Layered
      algorithm then computes Manhattan bend-point routes that go
      AROUND nested/parallel containers, which the chart lifts into the
      xyflow edge geometry (`chart.edges/transition-edge` renders a
      smooth poly-path through them). With `SPLINES` elk emits a single
      control-rich curve that, once collapsed to its bend points, reads
      no better than the bezier fallback — ORTHOGONAL is what makes the
      around-the-container routing legible at machine sizes (§1.7 of
      `001-Topology-Parity.md`).
    - `elk.json.edgeCoords` is `ROOT`: elk reports every edge section's
      `startPoint` / `bendPoints` / `endPoint` relative to the ROOT
      graph (i.e. absolute flow coordinates) rather than the default
      per-container origin. xyflow hands the custom edge component
      ABSOLUTE `sourceX`/`sourceY`/… so the lifted bend-points must be
      in the same frame; `ROOT` makes elk do that rebasing for us
      (added ELK 0.9; elkjs 0.11 wraps a newer core).

  rf2-rlq97 — edge-CLEARANCE + label-placement keys. ORTHOGONAL routing
  (above) computes Manhattan routes, but without dedicated edge-to-node
  spacing elk lets a route hug — or clip — a node box it passes, which
  is the 'arrows route OVER state nodes' symptom Mike reported. Three
  spacing knobs reserve the channels:

    - `elk.spacing.edgeNode` (24) — the minimum gap elk keeps between an
      edge segment and ANY node box it routes past. This is the key that
      pushes a passing route AROUND a node instead of across it.
    - `elk.layered.spacing.edgeNodeBetweenLayers` (24) — the same
      clearance applied between layers (the Layered algorithm's
      per-layer analogue of `spacing.edgeNode`).
    - `elk.spacing.edgeEdge` (16) — minimum gap between two parallel
      edge segments so bundled routes (several transitions between the
      same layers) read as distinct lines, not one fused thick stroke.

  And the label-placement keys (the edge-label analogue of d9ro2's
  node-measure feed; today the transition text is on the event-NODE so
  edges carry empty labels, but the keys make elk reserve a channel for
  any MEASURED edge label `projection/->elk-edge` feeds in future):

    - `elk.edgeLabels.placement` (CENTER) — elk places a non-empty edge
      label on the route, centred, and budgets space for it so two
      labels never overlap (the renderer then reads elk's computed label
      position instead of the old middle-segment-midpoint heuristic).
    - `elk.spacing.edgeLabel` (6) — gap between a label box and its
      edge.

  rf2-ly51l — initial-state placement SOFT preference. `elk.layered.
  cycleBreaking.strategy` is `DEPTH_FIRST` (was the implicit `GREEDY`).
  This is the statechart-intent layer the bead asks for, expressed as a
  soft bias rather than a forced layout (NOT `forceNodeModelOrder`):

  A statechart graph is almost always CYCLIC (resets, retries, back
  transitions, machine-level `:on` fallbacks all loop back toward the
  initial state). The Layered algorithm must first make the graph
  ACYCLIC by reversing a set of edges; the layer ranking — and hence the
  top-to-bottom (or left-to-right) reading order — then follows the
  forward edges. GREEDY picks the reversal set that minimises reversed
  EDGE COUNT with no regard for where the flow STARTS, so for the door
  (`locked → closed → open → alarming`, with `alarming → locked` reset +
  a root `:door/audit → locked` fallback) it reversed the wrong arcs and
  ranked `open` at the TOP with `locked` (the initial) THIRD — the exact
  observed-diff (the reference strongly prefers the initial near the
  top). DEPTH_FIRST instead breaks cycles by a depth-first walk FROM THE
  SOURCES (the initial state is a source — only its entry-marker points
  in; the root-fallback anchor is the only other source), reversing just
  the true back-edges, so the natural forward spine starting at the
  initial state is honoured: `locked → closed → open → alarming`.

  It stays a PREFERENCE, not an invariant: DEPTH_FIRST only chooses WHICH
  edges are reversed to acyclic-ise the graph. ELK still runs full
  LAYER_SWEEP crossing minimisation + Brandes-Köpf node placement on top,
  so it remains free to arrange a state OFF the initial-on-top ideal when
  crossings/spacing demand it. For an ALREADY-ACYCLIC graph (a plain
  `idle → loading → done/failed` flow) there is no cycle to break, so
  DEPTH_FIRST is layout-identical to GREEDY — the common linear case is
  untouched. The preference also rides the child MODEL ORDER (the initial
  state leads its container's children, `projection/->elk-children`), so
  DEPTH_FIRST's source selection prefers the initial state as its walk
  root."
  {"elk.algorithm"                              "layered"
   "elk.direction"                              "DOWN"
   "elk.spacing.nodeNode"                       "40"
   ;; G-SHAPE compaction (rf2 layout-fidelity pass) — the between-layer
   ;; gap was 70; tightened to 50 so a flat cyclic statechart reads as a
   ;; tighter vertical column closer to the Stately/xstate reference
   ;; (door / gate noticeably less stretched). Stays comfortably above the
   ;; ORTHOGONAL route channel reserve (`edgeNodeBetweenLayers` 24 below)
   ;; so routes don't crowd, and it only SHRINKS inter-layer whitespace —
   ;; no node re-ranking — so nested / parallel / acyclic machines are
   ;; visually unchanged (the inner ELK pass + container padding dominate
   ;; those). It does NOT close the G-ROUTE back-edge detour (door/reset,
   ;; gate/reset, brew back-edges sink to the deepest layer): that is
   ;; STRUCTURAL to the events-as-nodes split + the DEPTH_FIRST initial-
   ;; on-top preference and is not reachable via an ELK option — see the
   ;; §Layout / G-ROUTE note in `001-Topology-Parity.md`.
   "elk.layered.spacing.nodeNodeBetweenLayers"  "50"
   "elk.layered.crossingMinimization.strategy"  "LAYER_SWEEP"
   ;; rf2-ly51l — break cycles by a depth-first walk from the sources
   ;; (the initial state) rather than GREEDY min-reversed-count, so a
   ;; cyclic statechart's forward spine starts at the initial state and
   ;; ranks near the top/left. Soft: only changes WHICH edges are
   ;; reversed to acyclic-ise; layer-sweep + node-placement still run.
   ;; Identical to GREEDY for already-acyclic graphs. See the docstring.
   "elk.layered.cycleBreaking.strategy"         "DEPTH_FIRST"
   ;; rf2-k504af — make ELK honour the input MODEL ORDER (nodes AND
   ;; edges) as a tiebreaker through layering + crossing-minimisation,
   ;; not just within-layer. Pairs with the per-edge
   ;; `elk.layered.priority.direction` lever the projector sets on an
   ;; INITIAL state's outgoing `__in` edge (`projection/->elk-edge`):
   ;; together they pull the initial state to the START of its region's
   ;; flow even in a PURE-CYCLIC parallel region (traffic's `red` /
   ;; `walk`), where the soft DEPTH_FIRST + child-model-order preference
   ;; alone SLIPS and the initial sinks to the bottom layer. This is the
   ;; xstate-viz recipe (`considerModelOrder NODES_AND_EDGES` +
   ;; per-initial-edge priority). General, not traffic-specific: a
   ;; spine-ordered machine (door / brew / session) already ranks its
   ;; initial first, so model-order agrees with the layout and nothing
   ;; moves.
   "elk.layered.considerModelOrder"             "NODES_AND_EDGES"
   "elk.edgeRouting"                            "ORTHOGONAL"
   "elk.json.edgeCoords"                        "ROOT"
   ;; rf2-rlq97 — edge-to-node + edge-to-edge clearance so ORTHOGONAL
   ;; routes go AROUND node boxes (kills arrows-over-states) and bundled
   ;; edges stay visually distinct.
   "elk.spacing.edgeNode"                       "24"
   "elk.layered.spacing.edgeNodeBetweenLayers"  "24"
   "elk.spacing.edgeEdge"                       "16"
   ;; rf2-rlq97 — elk places + reserves space for any non-empty edge
   ;; label (the renderer reads back elk's position; see `chart.edges`).
   "elk.edgeLabels.placement"                   "CENTER"
   "elk.spacing.edgeLabel"                      "6"})

(defn elk-direction-str
  "Map the chart's `:lr` / `:tb` direction keyword to elk's
  `elk.direction` string (`RIGHT` / `DOWN`; `DOWN` is the default)."
  [direction]
  (case direction
    :lr "RIGHT"
    :tb "DOWN"
    "DOWN"))

(defn elk-layout-options
  "Pure root `layoutOptions` (a CLJS map) for the given parsed graph,
  host overrides, and direction — the input `->elk-input` `clj->js`-es
  onto the elk root. Extracted as a named, directly-assertable fn so the
  cross-hierarchy switch below is regression-guarded (rf2-gpa9k).

  Layering on `default-elk-options`:

    1. host `layout-options` overrides are `merge`d on top;
    2. `elk.direction` is forced from the `direction` arg (`:lr`/`:tb`);
    3. `elk.hierarchyHandling` is set to `INCLUDE_CHILDREN` — and ONLY
       set — when the graph nests: it is parallel (`:parallel?`) OR some
       node has a `:parent-id` (parallel-region states rf2-lkwev OR
       compound substates rf2-54s5a). This is the G5 cross-hierarchy
       switch: `INCLUDE_CHILDREN` is what makes the Layered algorithm
       route edges ACROSS nesting levels (its default,
       `SEPARATE_CHILDREN`, lays each level out independently and never
       routes cross-hierarchy edges). It pairs with G2's
       `elk.edgeRouting ORTHOGONAL` + `elk.json.edgeCoords ROOT`
       (rf2-cz8v6) so those cross-level routes come back as legible
       absolute-coordinate bend-points. A FLAT, non-parallel graph keeps
       the key absent so elk's per-level default stands."
  [parsed layout-options direction]
  (-> default-elk-options
      (merge (or layout-options {}))
      (assoc "elk.direction" (elk-direction-str direction))
      (cond-> (or (:parallel? parsed)
                  (some :parent-id (:nodes parsed)))
        (assoc "elk.hierarchyHandling" "INCLUDE_CHILDREN"))
      ;; rf2-p75kbg — a guarded fork whose branches lay out at the ROOT (the
      ;; gate machine's `:gate/check` 3-way leaves the top-level `:idle`)
      ;; enables root `crossingMinimization.semiInteractive` so the branch
      ;; event-nodes' `elk.position` hints (projection/->elk-children) are
      ;; honoured and the dotted evaluation-order connector reads as a clean
      ;; monotonic line instead of weaving. Only nodes carrying a position
      ;; hint are constrained, so this never perturbs a non-fork root layout;
      ;; absent (the default) for any machine without a root-level fork.
      (cond-> (contains? (projection/fork-branch-container-ids parsed) nil)
        (assoc "elk.layered.crossingMinimization.semiInteractive" "true"))))

(defn compute-layout-key
  "rf2-9qbn0g — the memo key the chart uses to decide whether to re-run the
  ELK layout pass. Pure; extracted as a directly-assertable fn so the cache-
  invalidation contract is regression-guarded. (Named `compute-layout-key`,
  not `layout-key`, because the component binds a local `layout-key` r/atom
  that would shadow it.)

  The key folds together every input that changes the laid-out result:

    - `definition`     — the machine topology;
    - `elk-direction`  — the RESOLVED direction ELK is fed (`:tb`/`:lr`);
    - `layout-options` — host ELK overrides;
    - `density`        — drives the derived container padding (rf2-8q5pt);
    - `context-rows`   — the root-container Context band height (rf2-8z1rca);
    - `adaptive?`      — the post-ELK MODE flag (rf2-9qbn0g).

  `adaptive?` MUST be in the key even though `elk-direction` already is: the
  post-ELK transform (parallel transpose + back-edge reroute) is gated by the
  RAW `:auto` opt-in, NOT the resolved direction. When `:auto` resolves to the
  SAME direction as a forced/default `:tb` (a linear or parallel machine,
  where `post-elk/aspect-direction` returns `:tb`), `elk-direction` is
  identical for `:direction :tb` and `:direction :auto`. Without the flag a
  `:tb → :auto → :tb` flip would NOT invalidate the cache — the back-edge
  reroute / parallel transpose would never apply on opt-IN and would
  stale-stay on opt-OUT. The flag makes the flip re-run the pass (apply then
  remove the transform) while ELK is still fed the resolved `elk-direction`."
  [definition elk-direction layout-options density context-rows adaptive?]
  [definition elk-direction layout-options density context-rows adaptive?])

(defn- ->elk-input
  "Build an elk.js JS-side input graph for the given parsed nodes +
  edges + direction. Parallel machines (rf2-lkwev) get a hierarchical
  graph (region containers with nested state children) so elkjs sizes
  + positions each orthogonal zone and its states; flat machines get
  the original single-level child list. The root `layoutOptions` are
  computed by the pure `elk-layout-options` (cross-hierarchy switch +
  direction + host overrides) and `clj->js`-ed here.

  rf2-qo5xy — the events-as-nodes paradigm decomposes each parsed
  transition into TWO elk edges: source-state → event-node and
  event-node → target-state (the second omitted for internal
  transitions). elkjs lays out the synthetic event-node alongside
  the source state's siblings (per `projection/->elk-children`); the
  resulting positions land in `:positions` keyed by the event-node id
  (`projection/event-node-id`) and the chart's xyflow projection picks
  them up.

  rf2-d9ro2 — the optional `measured-dims` `{node-id {:width :height}}`
  map (xyflow's reported `node.measured` rendered boxes from a prior
  render) is threaded into `projection/->elk-children` so leaf states +
  event-nodes lay out at their REAL size, floored by the min constants.
  nil on the first pass; the measure-then-relayout lifecycle in
  `MachineChart` supplies it on the second pass.

  rf2-rlq97 — the EDGES are projected by the pure
  `projection/->elk-edges` (lifted out of an inline `mapcat` here so the
  JVM corpus pins the edge-feed). They carry the events-as-nodes
  `__in` / `__out` split + optional MEASURED edge-label dims (the
  edge-label analogue of d9ro2's node measure; nil today since the
  transition text rides on the event-NODE — see `projection/->elk-edge`).
  Feeding edges INTO elk (alongside the spacing + label-placement keys in
  `default-elk-options`) is what makes elk's Layered algorithm route the
  edges AROUND node boxes instead of the renderer drawing geometric paths
  that cut across states.

  rf2-8q5pt — `chart-vc` (the resolved density map from
  `vc/chart-for-density`) is forwarded to `projection/->elk-children` so
  each container's `elk.padding` tracks the active density's title-strip
  + body-pad constants (was a regular-only literal). nil falls back to
  the regular density inside the projection.

  rf2-8z1rca — `context-rows` (the count of context rows the root-container
  Context band paints, derived from `:machine-data`) is forwarded to
  `projection/->elk-children` so the ROOT-CONTAINER frame's TOP padding
  reserves the variable-height Context band (children laid out below the
  title strip would otherwise sit UNDER the band). 0 ⇒ no band, no extra
  reservation."
  [parsed direction layout-options measured-dims chart-vc context-rows]
  #js {:id "root"
       :layoutOptions (clj->js (elk-layout-options parsed layout-options
                                                   direction))
       :children (clj->js (projection/->elk-children parsed measured-dims chart-vc
                                                     context-rows))
       ;; rf2-rlq97 — edge-label dims share the `measured-dims` map (it is
       ;; keyed by elk-edge-id for any labelled edge); under events-as-nodes
       ;; the transition text is on the event-node so this is normally a
       ;; no-op, but threading it keeps the edge-feed symmetric with the
       ;; node-feed and ready for a labelled edge type.
       :edges (clj->js (projection/->elk-edges parsed measured-dims))})

(defn elk-edge-points
  "rf2-cz8v6 (G2) — lift one elk edge's routed bend-points into a flat
  `[{:x :y} …]` vector of absolute (flow) coordinates.

  An elk edge carries one or more `sections`; each section has a
  `startPoint`, an `endPoint`, and an optional `bendPoints` array. We
  chain them into start → bend… → end. With `elk.json.edgeCoords ROOT`
  set on the layout (`default-elk-options`) every coordinate is already
  root-relative — i.e. the same absolute frame xyflow hands the custom
  edge component as `sourceX`/`sourceY`/… — so no re-basing is needed.

  Returns nil when the edge has no usable section (elk gave no route),
  so the projector can fall back to the bezier path. A degenerate
  one-or-zero-point result is treated as no route (nothing to route
  through)."
  [^js edge]
  (let [sections (or (.-sections edge) #js [])
        pts      (->> (areduce sections i acc []
                        (let [^js s  (aget sections i)
                              ^js sp (.-startPoint s)
                              ^js ep (.-endPoint s)
                              bp     (or (.-bendPoints s) #js [])]
                          (-> acc
                              (cond-> sp (conj {:x (.-x sp) :y (.-y sp)}))
                              (into (map (fn [^js p] {:x (.-x p) :y (.-y p)})) bp)
                              (cond-> ep (conj {:x (.-x ep) :y (.-y ep)})))))
                      ;; Collapse consecutive duplicate points (a
                      ;; section's endPoint can repeat the next section's
                      ;; startPoint) so the rendered path has no zero-
                      ;; length segments.
                      (dedupe))]
    (when (> (count pts) 1)
      (vec pts))))

(defn elk-edge-label-pos
  "rf2-rlq97 — lift an elk edge's COMPUTED label position into a
  `{:x :y}` map (absolute / flow coords under `elk.json.edgeCoords
  ROOT`), or nil when elk placed no label (the events-as-nodes default,
  where the transition text rides on the event-NODE so the edge's label
  is empty and elk reserves no slot for it).

  elk attaches the placed label as the first entry of the edge's
  `labels` array with an `x` / `y` it computed (centred on the route per
  `elk.edgeLabels.placement CENTER`). A label with no `x` (elk did not
  place it — empty text) lifts to nil so the renderer keeps its
  geometric anchor. This is the LABEL analogue of `elk-edge-points`: elk
  owns label PLACEMENT, the renderer just paints where elk says, killing
  the old middle-segment-midpoint heuristic for any labelled edge."
  [^js edge]
  (let [labels (or (.-labels edge) #js [])]
    (when (pos? (alength labels))
      (let [^js l (aget labels 0)
            x     (.-x l)
            y     (.-y l)]
        (when (and (number? x) (number? y))
          {:x x :y y})))))

(defn elk-result->positions
  "Adapter: elk.js JS result → `{:positions {node-id {:x :y :width
  :height}} :edge-points {edge-id [{:x :y} …]} :edge-labels {edge-id
  {:x :y}}}`.

  rf2-rlq97 — `:edge-labels` carries elk's COMPUTED label position per
  edge (the LABEL analogue of `:edge-points`'s routed bend-points). It is
  empty under events-as-nodes (the transition text rides on the
  event-NODE so edges carry no label for elk to place); a labelled edge
  type would populate it and the renderer would paint at elk's position
  instead of the geometric midpoint.

  Public-by-convention as a test seam (mirrors `elk-edge-points` /
  `invoke-elk-layout!`): the rf2-r636q regression bridges this PRODUCER
  to the `projection/xyflow-graph` CONSUMER end-to-end, feeding a
  stubbed elk result through here and asserting the projector attaches
  the route — the integration the per-half pins (consumer-only /
  producer-only) never exercised together, which is how the dead-G2
  key-scheme mismatch shipped green. No production code outside this ns
  calls it directly.

  Used by `xyflow-graph` to merge xyflow-side node objects with
  elk-laid-out positions, and (rf2-cz8v6 / G2) to route edges through
  elk's computed bend-points.

  ## Positions

  Walks nested elk children (rf2-lkwev — parallel machines nest each
  region's states under the region container). elkjs reports a child's
  `x`/`y` RELATIVE to its parent container, which is exactly what
  xyflow's `parentId` sub-flow wants (rf2-xh1lm — xyflow v12 reads
  `parentId`, not the pre-v12 `parentNode`) — so we record each node's
  position AS elkjs gives it, no re-basing. Region containers get
  their root-relative position + the size elkjs computed for the zone.

  ## Edge points (rf2-cz8v6 / G2)

  elk attaches each edge's routed `sections` to whichever node it lays
  the edge out under (the LCA of its endpoints). We walk EVERY node's
  `edges` array so cross-hierarchy edges (laid out at a container or at
  the root) are all collected. Each edge's points are lifted via
  `elk-edge-points`; with `edgeCoords ROOT` they are already absolute,
  so a deeply-nested transition's route is in the same frame as a flat
  one. Edges with no route (no sections) are simply absent from the
  map — the projector / edge component then falls back to the bezier
  path."
  [elk-result]
  ;; Pure recursive walk threading a 3-key accumulator (rf2-crvxff —
  ;; folded out three parallel `swap!`-accumulator atoms). `acc` carries
  ;; `:positions` (node-id → box), `:edge-points` (edge-id → routed
  ;; bend-points), and `:edge-labels` (edge-id → elk's COMPUTED label
  ;; position; the label analogue of `:edge-points`, empty under
  ;; events-as-nodes since the transition text rides on the event-NODE).
  ;; `array-seq` lifts elk's JS `.children`/`.edges` arrays in index
  ;; order so `assoc` last-write-wins ordering matches the old walk.
  (letfn [(collect-edges [acc ^js node]
            (reduce (fn [acc ^js e]
                      (let [pts (elk-edge-points e)
                            lp  (elk-edge-label-pos e)]
                        (cond-> acc
                          pts (assoc-in [:edge-points (.-id e)] pts)
                          lp  (assoc-in [:edge-labels (.-id e)] lp))))
                    acc
                    (array-seq (or (.-edges node) #js []))))
          (walk [acc ^js node]
            ;; node's own edges first (matches the old walk!'s
            ;; collect-edges!-then-recurse order), then each child:
            ;; record its position, then walk it (which collects that
            ;; child's edges + descends).
            (reduce (fn [acc ^js c]
                      (-> acc
                          (assoc-in [:positions (.-id c)]
                                    {:x      (or (.-x c) 0)
                                     :y      (or (.-y c) 0)
                                     :width  (or (.-width c) projection/state-node-min-width)
                                     :height (or (.-height c) projection/state-node-min-height)})
                          (walk c)))
                    (collect-edges acc node)
                    (array-seq (or (.-children node) #js []))))]
    (walk {:positions {} :edge-points {} :edge-labels {}} elk-result)))

;; ---- error reporting (rf2-4lyvh) ----------------------------------------
;;
;; Before rf2-4lyvh, compute-layout! had two bare `(catch :default _ nil)`
;; clauses (one sync around `.layout`, one async on the rejection branch)
;; that discarded ELK errors entirely. The downstream `(when result ...)`
;; guard in MachineChart then silently no-op'd, leaving `layout-state` at
;; its initial `{:positions {} :edge-points {}}` — every node renders at
;; the default origin, all stacked. The operator saw nothing in the
;; console. The fix surfaces ELK failures three ways:
;;
;;   1. A `:rf.error/machines-viz-elk-layout-failed` trace event lands on
;;      the bus so tools (Xray Issues panel, off-box monitors) that
;;      subscribe to `:rf.error/*` see the failure.
;;   2. `js/console.error` (gated on `interop/debug-enabled?` so the path
;;      is elision-safe in production bundles) so an operator opening
;;      DevTools sees something immediately.
;;   3. The callback receives a result-map shape carrying `:layout-error`
;;      instead of `nil`; the projector renders a banner on the chart
;;      area so the failure is visible IN the panel, not just in console.
;;
;; The pure data side (input summary + error→data adapter + result-map
;; builder) lives in `chart.layout-error` so the .cljc test corpus can
;; pin every shape without loading elkjs / xyflow. The side-effect side
;; (trace emit + dev-only console.error + the elkjs interop indirection)
;; stays here next to the .layout call.

(defn ^:private report-layout-error!
  "Surface an ELK layout failure: emit the canonical error trace + a
  console.error in dev builds. Returns nothing — the caller still has to
  build the result-map shape that gets handed to `done-fn`. Split out so
  the side-effect surface (trace + console) is in one place + unit-
  testable via `with-redefs` on the trace fn."
  [error parsed direction layout-options machine-id]
  (let [summary (layout-error/input-summary parsed direction layout-options)
        err     (layout-error/error->data error)]
    (trace/emit-error! :rf.error/machines-viz-elk-layout-failed
                       {:elk-error     err
                        :machine-id    machine-id
                        :input-summary summary})
    (when interop/debug-enabled?
      (js/console.error "[machines-viz] ELK layout failed:" error
                        (pr-str (assoc summary :machine-id machine-id))))))

(defn invoke-elk-layout!
  "Indirection over `(.layout elk-instance input)`. Lives at the
  public surface (not `^:private`) ONLY as a test seam: the
  `compute-layout!` error-path tests (rf2-4lyvh) rebind this via
  `set!` to stub elkjs's behaviour (sync throw / async reject / async
  resolve) without reaching into the `defonce` elk instance. The
  `defonce` is intentional — we want one elk instance per process —
  and reaching into its internals from a test is fragile.

  No production code outside this ns should call this fn; treat it
  as private-by-convention. Returns whatever elkjs returns: a Promise
  on success; may throw synchronously on a malformed input (rare,
  but observed in the wild)."
  [^js input]
  (.layout elk-instance input))

(defn invoke-fit-view!
  "rf2-set3x — indirection over `(.fitView instance opts)`. Same
  test-seam shape as `invoke-elk-layout!` above: the regression test
  rebinds this via `set!` to spy the call without needing a real
  xyflow instance. No production code outside this ns should call it
  directly; the chart's auto-fit lifecycle (`MachineChart`) is the
  sole caller."
  [^js instance ^js opts]
  (.fitView instance opts))

(defn invoke-project-definition!
  "rf2-jl72i — indirection over `(layout/project-definition definition)`,
  the topology parser. Same test-seam shape as `invoke-elk-layout!` /
  `invoke-fit-view!` above: the per-chart parse-cache regression rebinds
  this via `set!` to SPY parser calls (counting how many renders actually
  walk the definition) without re-stubbing the whole `layout` ns — the
  rest of the suite calls `layout/project-definition` directly and must
  keep seeing the real fn.

  `MachineChart` routes its single topology projection through THIS seam,
  behind the per-chart parsed-topology cache (keyed only on `:definition`),
  so decoration-only re-renders (`:current-state` / `:from-highlight` /
  `:to-highlight` / overlay `:tick` / `:fit-signal` / a parent re-render)
  reuse the cached parse instead of re-walking the definition. No
  production code outside `MachineChart` calls it."
  [definition]
  (layout/project-definition definition))

(defn read-measured-dims
  "rf2-d9ro2 — read xyflow's measured node boxes off a captured
  ReactFlowInstance as a pure CLJS `{node-id {:width :height}}` map.

  rf2-6v4ci5 — the measured box lives on the INTERNAL node, reached via
  `instance.getInternalNode(id)`, NOT on the user-facing `instance
  .getNodes()` objects. In xyflow v12 the store merges the DOM-measured
  `{width height}` onto its internal node representation after React
  commits + the ResizeObserver fires; the user-facing nodes (the array
  we feed in as a controlled `:nodes` prop, returned unchanged by
  `getNodes()`) only carry `.measured` when dimension changes are
  applied back into that array — which this non-interactive chart
  deliberately does NOT do (positions are ELK-owned). Reading
  `(.-measured n)` off `getNodes()` therefore always saw nil, so the
  whole measure-then-relayout pass never reached `ready?` and every node
  laid out at its ELK floor — guard/action-widened event chips overran
  their floor-spaced same-layer neighbours (`door/close IF may-close?`
  over `door/hold`). Reading via `getInternalNode` feeds the REAL
  rendered box (incl. guard + action row) back to ELK.

  We iterate `getNodes()` for the id set and look each id's measured box
  up via `getInternalNode`; we keep only ids that carry a positive
  measured width AND height — a node still awaiting measurement (measured
  nil / 0) is omitted, so the `measured-then-relayout` caller can tell
  whether the WHOLE topology has been measured yet
  (`= (count dims) (count nodes)`).

  Public-by-convention as a test seam (mirrors `invoke-fit-view!` /
  `invoke-elk-layout!`): the relayout-lifecycle regression rebinds this
  via `set!` to feed a stubbed measured map without a real xyflow
  instance. No production code outside `MachineChart` calls it."
  [^js instance]
  (let [nodes (.getNodes instance)]
    (persistent!
      (reduce
        (fn [acc ^js n]
          (let [^js internal (.getInternalNode instance (.-id n))
                m (some-> internal .-measured)
                w (and m (.-width m))
                h (and m (.-height m))]
            (if (and (number? w) (number? h) (pos? w) (pos? h))
              (assoc! acc (.-id n) {:width w :height h})
              acc)))
        (transient {})
        nodes))))

(defn compute-layout!
  "Run elk.js layout on `parsed` (the output of
  `chart.layout/project-definition`); call `done-fn` with a map
  `{:positions {node-id {:x :y :width :height}} :edge-points {edge-id
  [{:x :y} …]}}` on success.

  On failure (rf2-4lyvh) the callback receives the SAME map shape with
  empty `:positions` + `:edge-points` AND an extra `:layout-error
  {:error … :input-summary …}` slot — the existing `(when result ...)`
  guard at the callsite is preserved, the projector can read
  `:layout-error` to render an in-panel indicator instead of silently
  rendering every node stacked at origin. The failure also fires a
  `:rf.error/machines-viz-elk-layout-failed` trace event + (in dev
  builds, gated on `interop/debug-enabled?`) a `js/console.error`.

  The `:edge-points` half (rf2-cz8v6 / G2) carries elk's routed bend-
  points so the chart routes edges AROUND nested containers instead of
  cutting across them. The async path is idiomatic xyflow + elkjs:

    1. `(->elk-input parsed direction layout-options)` builds a JS
       graph.
    2. `.layout` returns a Promise.
    3. Resolve → `elk-result->positions` → callback.
    4. Reject → callback with the layout-error result-map (above).

  Optional `:machine-id` is threaded onto the error trace's `:tags` so
  consumers (Xray Issues panel) can attribute the failure to a specific
  machine; nil when called without a machine id (e.g. unit tests).

  rf2-d9ro2 — the longest arity takes `measured-dims` (an optional
  `{node-id {:width :height}}` map of xyflow's reported rendered boxes),
  fed into `->elk-input` so the second (relayout) pass sizes each node
  to its real box. The shorter arities pass nil (first pass / unit
  tests) — identical to the pre-rf2-d9ro2 single-pass.

  rf2-8q5pt — the longest arity also takes `chart-vc` (the resolved
  density map from `vc/chart-for-density`), fed into `->elk-input` so
  container `elk.padding` tracks the active density. The shorter arities
  pass nil → the projection falls back to the regular density.

  rf2-8z1rca — the longest arity also takes `context-rows` (the count of
  Context-band rows the root-container frame paints, from `:machine-data`),
  fed into `->elk-input` so the ROOT-CONTAINER top padding reserves the
  variable-height Context band. The shorter arities pass 0 → no band, no
  extra reservation."
  ([parsed done-fn]
   (compute-layout! parsed :tb nil nil nil nil 0 done-fn))
  ([parsed direction layout-options done-fn]
   (compute-layout! parsed direction layout-options nil nil nil 0 done-fn))
  ([parsed direction layout-options machine-id done-fn]
   (compute-layout! parsed direction layout-options machine-id nil nil 0 done-fn))
  ([parsed direction layout-options machine-id measured-dims done-fn]
   (compute-layout! parsed direction layout-options machine-id measured-dims nil 0 done-fn))
  ([parsed direction layout-options machine-id measured-dims chart-vc done-fn]
   (compute-layout! parsed direction layout-options machine-id measured-dims chart-vc 0 done-fn))
  ([parsed direction layout-options machine-id measured-dims chart-vc context-rows done-fn]
   (let [input  (->elk-input parsed direction layout-options measured-dims chart-vc
                             context-rows)
         handle (fn handle-error [e]
                  (report-layout-error! e parsed direction layout-options
                                        machine-id)
                  (done-fn (layout-error/layout-error-result
                             e parsed direction layout-options)))
         p      (try (invoke-elk-layout! input)
                     (catch :default e
                       (handle e)
                       nil))]
     (when (and p (.-then p))
       (-> p
           (.then (fn [result]
                    (done-fn (elk-result->positions result))))
           (.catch (fn [e] (handle e))))))))

;; ---- graph projection (parsed + positions → xyflow nodes/edges) ---------
;;
;; rf2-0gmwp — the pure projector (`xyflow-graph` / the elk `children`
;; shape) moved to `chart.projection` so the JVM test corpus can pin it
;; without loading xyflow/elkjs. `chart.cljs` retains only the JS-interop
;; layout glue above + the React component below.

;; ---- inline keyframes ---------------------------------------------------

(def ^:private chart-stylesheet
  "Inline stylesheet carrying the transition-glow keyframes + the
  prefers-reduced-motion override. Mirrors the previous SVG render's
  `transition-glow-css` so the focused-edge animation continues to
  work post-migration.

  rf2-4o43j8 — the fired/focused glow is an event-driven, FINITE
  flash (per `spec/Principles.md` §chart animation): the consumers
  (`chart.edges` / `chart.nodes.event-node`) play it as ONE iteration
  via `tokens/glow-animation-css` (`… ease-out forwards`), NOT an
  `infinite` loop. The `100%` frame settles to full opacity so the
  static fired/focused affordance (stroke width / hue / glow ring)
  reads at full strength once the flash completes. Duration flows
  through `--rf-xray-motion-scale`, so `prefers-reduced-motion:
  reduce` collapses the flash to a single settle frame."
  (str
    ":root { --rf-xray-motion-scale: 1; }\n"
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-xray-motion-scale: 0.001; }\n"
    "}\n"
    "@keyframes mv-chart-transition-glow {\n"
    "  0%   { opacity: 0.55; }\n"
    "  20%  { opacity: 1.00; }\n"
    "  100% { opacity: 1.00; }\n"
    "}\n"
    ".react-flow__attribution { display: none !important; }\n"))

;; ---- memoised node/edge type maps ---------------------------------------

(def ^:private node-types-memo (nodes/node-types))
(def ^:private edge-types-memo (edges/edge-types))

;; ---- :overlays slot (rf2-7w4qr) -----------------------------------------
;;
;; Mike ruled (rf2-7w4qr): the host-fed, spec+tick+callbacks overlay
;; family is wired through ONE `:overlays` slot instead of a flat prop
;; per overlay. Each descriptor is a map `{:id <keyword> …}` keyed on
;; `:id`; the chart dispatches it to its already-modular rendering
;; namespace (`chart.overlays.after-rings` / `.spawn-all-join` /
;; `.cancellation-cascade`) by `:id`. This ns owns ONLY the wiring — the
;; overlay renderers (their per-overlay shapes + DOM walk + paint) are
;; untouched.
;;
;; The `:tick` story is unified: a per-descriptor `:tick` replaces both
;; the old `:after-ring-tick` and `:overlay-tick`. One rAF clock per
;; chart stays host-owned (Lock #8); the host just delivers the tick on
;; whichever descriptor(s) the clock drives.
;;
;; What did NOT fold (stays on the trunk): `:fired-edge-ids` (a simple
;; set, core to active-edge styling, not a spec+tick+callbacks overlay),
;; `:current-state` / `:from-highlight` / `:to-highlight` (runtime
;; highlight, not host-fed overlay specs), `:machine-data` (a corner
;; panel rendered inline, not a positioned DOM-walking overlay), and all
;; structural props (`:definition`, `:direction`, `:layout-options`,
;; parallel handling).

(def overlay-ids
  "The closed set of recognised `:overlays` descriptor `:id`s. A
  descriptor whose `:id` is outside this set is ignored (with a dev-only
  console.warn — see `render-overlay`). Per spec/API.md §`:overlays`
  slot."
  #{:after-rings :spawn-all-join :cancellation-cascade})

(defn- render-overlay
  "Dispatch one `:overlays` descriptor to its rendering namespace by
  `:id`, returning the overlay's hiccup (or nil when the descriptor is
  dormant / its `:id` is unknown).

  Per-overlay descriptor shapes (the renderers are unchanged — only the
  wiring is lifted here, rf2-7w4qr):

    {:id :after-rings          :specs <vec> :tick <opaque>
                               :on-hover <fn> :on-leave <fn>}
    {:id :spawn-all-join       :spec <map>  :tick <opaque>
                               :on-child-click <fn>}
    {:id :cancellation-cascade :spec <map>  :tick <opaque>}

  An unknown `:id` is ignored (a host data error, not a runtime
  fallback); in dev builds (gated on `interop/debug-enabled?`) it emits
  a `js/console.warn` so the misconfigured host is visible without
  failing the render."
  [{:keys [id tick] :as descriptor}]
  (case id
    :after-rings
    (let [{:keys [specs on-hover on-leave]} descriptor]
      (when (seq specs)
        [after-rings/AfterRingsOverlay
         {:ring-specs specs
          :tick       tick
          :on-hover   on-hover
          :on-leave   on-leave}]))

    :spawn-all-join
    (let [{:keys [spec on-child-click]} descriptor]
      (when (and spec (:node-id spec))
        [overlay-spawn-all/SpawnAllJoinOverlay
         {:join-spec      spec
          :tick           tick
          :on-child-click on-child-click}]))

    :cancellation-cascade
    (let [{:keys [spec]} descriptor]
      (when (and spec (:node-id spec) (seq (:steps spec)))
        [overlay-cascade/CancellationCascadeOverlay
         {:cascade-spec spec
          :tick         tick}]))

    ;; Unknown :id — ignore gracefully (dev-only warn).
    (do
      (when interop/debug-enabled?
        (js/console.warn
          "[machines-viz] MachineChart ignoring :overlays descriptor with"
          "unknown :id" (pr-str id)
          "— recognised ids:" (pr-str overlay-ids)))
      nil)))

;; ---- MachineChart Reagent component -------------------------------------

(defn MachineChart
  "Render a state-machine definition as an interactive xyflow chart.

  Args (map — see `tools/machines-viz/spec/API.md` §Props; the
  component destructures the keys below with defaults and ignores
  unknown keys):

    :machine-id        — keyword; identifies the machine. Surfaces as
                         the chart's aria-label and on every per-node
                         `:data` payload (read by tests + hosts).
    :definition        — the machine definition map. When nil the
                         chart renders an empty-state placeholder.
                         (The component does NOT subscribe to a
                         framework registry directly — hosts pull the
                         definition via `(rf/machine-meta machine-id)`
                         and pass it in. Keeps the component testable
                         in isolation.)
    :current-state     — the live `:state` keyword/vector for the
                         active-state highlight. Optional; nil renders
                         no highlight.
    :from-highlight    — focused-event lens origin (`:state` value).
    :to-highlight      — focused-event lens landing (`:state` value).
    :fired-edge-ids    — rf2-qeemm (closes parity gap G3). A SET of
                         canonical edge-ids (the EXACT scheme
                         `chart.layout` mints) that fired THIS epoch.
                         Each matching edge gets the FIRED treatment
                         (emphasised + animated stroke + `data-fired`)
                         along its routed path. Distinct from the
                         from/to lens (`:from-highlight` / `:to-highlight`,
                         which match by ENDPOINT state): this matches the
                         EDGE directly, lighting every traversed arm
                         (microsteps, guard-fork candidates). The host
                         (Xray) resolves it for the focused epoch via
                         `extract-fired-edge-ids`; nil / `#{}` → no fired
                         highlight (the standalone viewer / Story path).
    :guard-blocked-edge-ids — rf2-fzrzlw / rf2-4nxgqq / rf2-tjm3u2. A SET
                         of canonical edge-ids whose guard REJECTED the
                         event this epoch (a guard-blocked no-op: the
                         runtime emitted `:rf.machine/guard-evaluated`
                         fail/threw but NO `:rf.machine/transition`). The
                         matching EVENT-NODE and its `__in` (source-state →
                         event-node) HALF get the PINK guard-blocked
                         treatment (emphasised pink stroke + emphasised
                         pink `IF <guard>` chip), winning over
                         fired/focused/active so the attempted-and-rejected
                         arm stands out. rf2-4nxgqq — the highlight STOPS at
                         the guard event-node: the `__out` (event-node →
                         target) half stays STATIC/resting (a no-op never
                         reached the target; an onward pink arrow would
                         falsely imply the transition progressed — the
                         `__out` topology edge still renders, only the live
                         overlay is withheld). Without any of this a blocked
                         no-op gives ZERO signal which edge the event hit.
                         The host (Xray) resolves the set by `(source-path,
                         event, guard)` when trace state is available
                         (`extract-guard-blocked-edge-ids` reads the active
                         `:state` off the guard-evaluated trace + gates each
                         candidate by `:from-path`-prefix-of-active-path, so
                         a sibling state reusing the same `(event, guard)`
                         is NOT lit, rf2-tjm3u2; no-`:state` traces fall back
                         to the `(event, guard)` match). The canonical DOM
                         pins are the event-node (`data-guard-blocked`) +
                         chart-root (`data-guard-blocked-edge-ids`), NOT an
                         edge-half attribute (rf2-bdwolc). nil / `#{}` → no
                         guard-blocked highlight. SUPERSET of XState (which
                         highlights nothing on a block).
    :sim?              — flips the highlight palette to amber for
                         the simulator path.
    :on-state-click    — `(fn [path] ...)` invoked on node click.
    :on-edge-click     — rf2-u422r. `(fn [#js {:eventId :fromPath
                         :toPath}] ...)` invoked when a transition edge's
                         label is clicked. Only edges with a fireable
                         event (plain `:on` transitions) are clickable;
                         `:after` / `:always` auto edges stay inert. The
                         on-chart machine simulator wires this to send
                         the clicked event into the hermetic sim engine
                         (\"simulate ON the chart\"). nil = no wiring.
    :read-only?        — when true all `:on-*` callbacks are no-op'd.
                         The viewer page sets this.
    :direction         — `:tb` (top-to-bottom, default) or `:lr`
                         (left-to-right). **OPT-IN adaptive: `:auto`**
                         (rf2-lamdfl + rf2-gnrkke). The DEFAULT `:tb` (and
                         an explicit `:tb` / `:lr`) is byte-identical to the
                         historical render: the post-ELK adaptive pass is NOT
                         invoked. Pass `:auto` to OPT A MACHINE IN to the
                         adaptive-aspect heuristic (`chart.post-elk/aspect-
                         direction` — a branchy machine flows landscape
                         `:lr`, a chain stays a column `:tb`, a parallel
                         machine gets the region-stacking transpose) PLUS the
                         back-edge return-route reroute (§4.3.1 + §4.3.2 of
                         `001-Topology-Parity.md`). A host resolves `:auto`
                         from a machine's `:layout {:aspect :adaptive}` hint
                         (or its own layout intent). Fed to elkjs as
                         `elk.direction` (resolved to `:tb`/`:lr` on the
                         `:auto` path).
    :density           — rf2-k647w. `:compact` / `:regular` (default) /
                         `:cosy`. Resolves the geometry + typography
                         map via `visual-constants/chart-for-density`;
                         the resolved map is threaded through the
                         projector onto every node/edge `:data` so the
                         xyflow node/edge components render at the
                         chosen density. The chart root surfaces the
                         resolved density as `data-density`. nil ≡
                         `:regular` (pixel-identical to the historical
                         render). An unknown density throws at render
                         time (per `spec/API.md` §Density resolution
                         rules) — picking outside the closed set is a
                         programmer error, not a runtime fallback.
    :theme             — rf2-az6e2. `:dark` (default) / `:light`.
                         Resolves the chart palette + semantic token map
                         ONCE per render (`theme/tokens/theme-palette` +
                         `chart-tokens`), threaded through the projector
                         onto every node/edge `:data {:palette}` so the
                         renderers paint the ACTIVE theme — NOT the dark
                         alias. The chart's own chrome (canvas surface,
                         root title strip, Context panel, dot-grid,
                         minimap, layout-error banner) reads the same
                         palette. The resolved theme surfaces on the root
                         as `data-theme`. INDEPENDENT of `:density`
                         (geometry vs colour are orthogonal knobs). nil /
                         unknown → `:dark`. This bead builds no light/dark
                         toggle UI — hosts pass the prop.
    :layout-options    — host-side elk.js `layoutOptions` overrides
                         merged on top of `default-elk-options`.
    :height            — outer wrapper height (CSS string; default
                         `\"100%\"`). xyflow requires a non-zero
                         parent height.
    :show-minimap?     — when true (default false) render xyflow's
                         built-in MiniMap.
    :show-controls?    — when true (default true) render xyflow's
                         built-in zoom/pan/fit Controls.
    :show-background?  — when true (default true) render xyflow's
                         dot-pattern Background.
    :overlays          — rf2-7w4qr. Optional vector of host-fed overlay
                         DESCRIPTOR maps, each keyed on `:id`. Collapses
                         the former flat overlay-wiring props (the
                         `:after-ring-*` / `:spawn-all-*` /
                         `:cancellation-cascade` / `:overlay-tick`
                         family) into ONE slot; hosts compose overlays
                         here instead of growing the trunk signature.
                         The chart dispatches each descriptor to its
                         already-modular rendering namespace by `:id`
                         (`render-overlay`); the renderers are unchanged.
                         A per-descriptor `:tick` unifies the old
                         `:after-ring-tick` + `:overlay-tick` (one rAF
                         clock per chart stays host-owned — Lock #8 —
                         just delivered per-overlay). Unknown `:id` →
                         ignored (dev-only console.warn). Recognised
                         descriptors (see `render-overlay`):

                           {:id :after-rings :specs <vec> :tick <opaque>
                            :on-hover <fn> :on-leave <fn>}
                             rf2-uv1on. Presentation-ready `:after`-timer
                             ring-specs (each `{:node-id :fraction :color
                             :cancelled? :tooltip :testid}`). When `:specs`
                             is non-empty the chart mounts the
                             `chart.overlays.after-rings` overlay as a
                             sibling of the canvas; it walks the rendered
                             node DOM to position each ring. The host owns
                             the trace→spec projection + the scrubber-aware
                             fraction; `:tick` (Xray's `now-ms`) drives the
                             re-measure/repaint. Empty `:specs` → no overlay.

                           {:id :spawn-all-join :spec <map> :tick <opaque>
                            :on-child-click <fn>}
                             rf2-3ow55. Presentation-ready `:spawn-all`
                             join-spec (`{:node-id :join :children
                             :resolved? :on-all-complete :on-any-failed}`).
                             When `:spec` has a `:node-id` the chart mounts
                             the `chart.overlays.spawn-all-join` inspector
                             beside the spawn-all-bearing state. `:on-child-
                             click` `(fn [child-key] ...)` fires on a child-
                             row click (Xray pivots to the child instance).

                           {:id :cancellation-cascade :spec <map>
                            :tick <opaque>}
                             rf2-3ow55. Presentation-ready cascade-spec
                             (`{:node-id :parent-label :from-state :steps}`).
                             When `:spec` has a `:node-id` and a non-empty
                             `:steps` the chart mounts the
                             `chart.overlays.cancellation-cascade` waterfall
                             beneath the parent state. nil / no steps →
                             dormant.
    :machine-data      — rf2-qo5xy; rf2-q129z8. Optional CLJS map fed into
                         the Context BAND in the ROOT-CONTAINER frame header
                         (pre-q129z8 a top-LEFT corner panel; now folded into
                         the named frame that hugs the topology). Two host
                         projections feed it: the live `:data`
                         (`:rf.machine/data`, key→value) OR — the sole
                         production feeder today — the STATIC context
                         shape INFERRED from the definition's `:data`
                         (key→type-caption, via Xray's
                         `static-context-shape`). nil / empty → no band.
                         Purely presentation — the host owns the projection;
                         `chart.projection/xyflow-graph` threads it onto the
                         frame node's `:data {:context}`.
    :machine-data-inferred? — rf2-5tz9p. Optional boolean (DEFAULT true).
                         When true the Context band shows a subtle
                         \"inferred from :data\" badge marking its
                         contents as a type shape INFERRED from one sample
                         of the definition's `:data` — NOT a declared
                         schema and NOT the live runtime `:data`. Set
                         false when the host feeds live `:data` VALUES.
                         Ignored when no band renders.
    :fit-signal        — rf2-6tw7t. Optional opaque value (a nonce — any
                         `=`-comparable). When its value CHANGES between
                         renders the chart re-fits the viewport to frame
                         the whole topology, ORTHOGONAL to the layout-key
                         auto-fit (rf2-set3x). Hosts bump it on panel-
                         entry / tab-activation so re-entering a panel
                         frames the graph rather than restoring a stale
                         (possibly off-screen) zoom/pan. A STEADY signal
                         across ordinary re-renders is a no-op, so the
                         operator's manual zoom/pan still survives non-
                         entry re-renders. nil (the default) with no host
                         supplying it means the prop never changes, so the
                         entry-fit is inert and only the layout-key auto-
                         fit runs (the standalone viewer / Story path).
    :testid            — root wrapper `data-testid`; defaults to
                         `\"rf-mv-chart\"` so tests + hosts find it."
  [_initial-props]
  (let [;; rf2-cz8v6 (G2) — the layout atom now holds BOTH the node
        ;; positions and elk's routed edge bend-points
        ;; (`{:positions {…} :edge-points {…}}`) so the projector can
        ;; route edges around nested containers. Starts empty (no
        ;; positions, no routes) — the pre-layout render falls back to
        ;; origin + bezier until the async elk pass resolves.
        ;; rf2-rlq97 — `:edge-labels` (elk's computed label positions)
        ;; joins the same atom; empty until the async elk pass resolves.
        layout-state  (r/atom {:positions {} :edge-points {} :edge-labels {}})
        layout-key    (r/atom nil)
        ;; rf2-set3x — auto-fit lifecycle.
        ;;
        ;; xyflow's `:fitView true` prop fires ONCE on initial mount,
        ;; but mount happens BEFORE the async elk pass resolves: every
        ;; node renders at the default {x 0 y 0}, the one-shot fitView
        ;; fits to a degenerate cluster near the origin, and when real
        ;; positions arrive the viewport is never re-fit. The fix:
        ;;
        ;;   1. Capture the xyflow instance via `:onInit`.
        ;;   2. After EVERY layout settle whose `this-key` differs
        ;;      from the last key we fit, call `.fitView` once more —
        ;;      so the user sees the real topology framed.
        ;;
        ;; `:fit-key` records the layout-key we last fit; gating on
        ;; key-inequality means an operator's manual zoom/pan IS
        ;; preserved across re-renders that don't invalidate layout
        ;; (highlight changes, overlay ticks, etc.). A genuine layout
        ;; invalidation (definition / direction / layout-options
        ;; change — the load-bearing tuple per API.md §Layout-
        ;; invalidation boundary, AND the manual `Fit` Controls
        ;; button) is the only thing that re-fits.
        ;;
        ;; rf2-6tw7t — `:fit-sig` records the host-supplied `:fit-signal`
        ;; value we last fit on. The layout-key gate above deliberately
        ;; PRESERVES the operator's manual zoom/pan across non-layout
        ;; re-renders (tab re-entry with the SAME machine doesn't change
        ;; the layout-key). `:fit-signal` is the ORTHOGONAL escape hatch:
        ;; a host bumps an opaque nonce on panel-entry / tab-activation
        ;; and the chart re-fits the framed topology even though the
        ;; layout shape is unchanged — so re-entering the Machine tab
        ;; always frames the graph rather than restoring a stale (possibly
        ;; off-screen) viewport. Starts `::unfit` (a sentinel distinct
        ;; from any host value incl. nil) so the FIRST observed signal,
        ;; even nil, is treated as "fit once"; thereafter only a CHANGE
        ;; re-fits, so a steady signal across non-entry re-renders is a
        ;; no-op (manual zoom/pan still survives those).
        fit-state     (r/atom {:instance nil :fit-key nil :fit-sig ::unfit})
        ;; rf2-d9ro2 — measure-then-relayout lifecycle state.
        ;;
        ;; The bug: ELK was fed CONSTANT node dimensions (the
        ;; `state-node-min-{width,height}` floors), not the real rendered
        ;; box. `chart.nodes/state-node` renders at CONTENT size (label +
        ;; tag pills + entry/exit action pills, rf2-a2b55), so any node
        ;; wider/taller than the floor overlapped its neighbours and the
        ;; topology looked missized. The fix is the canonical React Flow +
        ;; ELK two-pass: mount nodes at content size, let xyflow measure
        ;; them (`node.measured`), then re-run ELK with the measured box.
        ;;
        ;; `relayout-state` gates the second pass so it runs at most ONCE
        ;; per layout-key and never loops:
        ;;
        ;;   :key      — the layout-key the stored measured signature
        ;;               belongs to (a new topology resets the signature).
        ;;   :measured — the `{node-id {:width :height}}` map ELK was last
        ;;               FED (nil before any relayout). The relayout fires
        ;;               only when xyflow's freshly-measured map differs
        ;;               from this AND every node has been measured.
        ;;
        ;; Loop-freedom: a relayout only moves node POSITIONS — it does
        ;; not change node CONTENT, so the next measurement reports the
        ;; SAME boxes, the signature matches, and no further relayout
        ;; fires. Position-only `onNodesChange` events never reach the
        ;; signature comparison (it keys on measured dims, not position),
        ;; so xyflow applying the new ELK positions cannot re-trigger.
        relayout-state (r/atom {:key nil :measured nil})
        ;; rf2-s5kyp — double-rAF deferral. A SINGLE rAF races
        ;; xyflow's internal node measurement on the focused-machine-
        ;; change path: by the time the chart re-renders with the new
        ;; `:definition`'s positions, React commits the new prop set, the
        ;; rAF fires, and xyflow may still be measuring the just-mounted
        ;; node DOM — `.fitView` then reads stale / zero-size bounds and
        ;; frames the chart on the prior topology's extent (or a
        ;; degenerate box if every new node is still default-sized).
        ;; Two rAFs guarantee the fit runs in the frame AFTER React's
        ;; commit + xyflow's measurement pass, the same trick xyflow's
        ;; own examples use for post-load fit calls. Single-rAF is kept
        ;; only as the test-runtime fallback (Node has no rAF).
        ;;
        ;; Calls go through `invoke-fit-view!` so the regression test can
        ;; spy via `set!` (mirrors `invoke-elk-layout!`).
        schedule-fit! (fn [^js instance]
                        (let [opts #js {:padding 0.1}]
                          (if (and (exists? js/requestAnimationFrame)
                                   (some? js/requestAnimationFrame))
                            (js/requestAnimationFrame
                              (fn [_]
                                (js/requestAnimationFrame
                                  (fn [_] (invoke-fit-view! instance opts)))))
                            ;; Test / Node fallback — fire immediately.
                            (invoke-fit-view! instance opts))))
        ;; rf2-dnmbs — per-render graph-projection + JS-marshalling cache.
        ;;
        ;; `projection/xyflow-graph` (the parsed→xyflow node/edge
        ;; projector) AND the deep `clj->js` of its node/edge arrays both
        ;; used to run on EVERY MachineChart render — including renders
        ;; that change nothing structural (a host `:tick` bump driving an
        ;; after-ring or highlight flicker up to ~60Hz, a `:fit-signal`
        ;; bump, a parent re-render). The ELK relayout is correctly gated;
        ;; this JS marshalling was the OTHER per-render cost and it was
        ;; ungated — landing on the animation hot path.
        ;;
        ;; The cache holds the LAST projected+converted result keyed on the
        ;; vector of inputs that actually change it. A render whose key
        ;; matches the stored key reuses the cached `clj->js`-ed `#js`
        ;; node/edge objects (xyflow receives the SAME object identity, so
        ;; it skips its own diff too); a render that touches a real input
        ;; (a highlight change, an ELK settle delivering new positions, a
        ;; new definition, a density/theme switch) recomputes once and
        ;; restores. Closure-held in the Form-2 outer scope, so it is
        ;; per-chart-instance and disposed with the component. The shape is
        ;; `{:key <input-vec> :js-nodes #js[…] :js-edges #js[…]}`.
        graph-cache   (atom nil)
        ;; rf2-jl72i — per-chart parsed-topology cache.
        ;;
        ;; The topology parser (`layout/project-definition`, the e0emp
        ;; rename of the former `parse-definition`) walks the WHOLE
        ;; definition and rebuilds the `{:nodes :edges :initial-path}`
        ;; graph. rf2-dnmbs gated the DOWNSTREAM `xyflow-graph` projection
        ;; + `clj->js` marshalling behind `graph-cache`, but the parse
        ;; itself still ran at the TOP of EVERY render — so a decoration-
        ;; only re-render (`:current-state` / `:from-highlight` /
        ;; `:to-highlight` change, an overlay `:tick`, a `:fit-signal`
        ;; bump, a bare parent re-render) paid the full O(topology)
        ;; parse + allocation cost despite the API (spec/API.md §Lock #11,
        ;; §Performance invariants) marking the topology and runtime-
        ;; highlight planes as STRICTLY separate: decoration props must
        ;; not reach the layout/topology pipeline.
        ;;
        ;; This cache memoises the parse keyed ONLY on `:definition`
        ;; (identity-or-value `=`). A render with the same `:definition`
        ;; reuses the cached `parsed` map (so `graph-cache`, the layout-
        ;; key tuple, and the relayout signature all see the SAME parsed
        ;; identity — their own `=`-keys then short-circuit too); a NEW
        ;; `:definition` reparses ONCE and replaces the entry, which in
        ;; turn busts every downstream cache (their keys embed `parsed`).
        ;; Density / direction / layout-options / theme / highlight
        ;; changes never reparse — they don't change `:definition`.
        ;; Closure-held in the Form-2 outer scope: per-chart-instance,
        ;; disposed with the component. Shape `{:definition <d> :parsed <p>}`.
        parse-cache   (atom nil)
        parse-topology!
        (fn [definition]
          (let [cached @parse-cache]
            (if (and cached (= (:definition cached) definition))
              (:parsed cached)
              (let [parsed (invoke-project-definition! definition)]
                (reset! parse-cache {:definition definition :parsed parsed})
                parsed))))
        project+convert!
        (fn [cache-key project-fn]
          ;; `project-fn` is a thunk that returns `{:nodes :edges}` (the
          ;; pure projection). We only call it — and re-`clj->js` — on a
          ;; cache MISS; a hit returns the stored JS arrays untouched.
          (let [cached @graph-cache]
            (if (and cached (= (:key cached) cache-key))
              cached
              (let [{:keys [nodes edges]} (project-fn)
                    fresh {:key      cache-key
                           :js-nodes (clj->js nodes)
                           :js-edges (clj->js edges)}]
                (reset! graph-cache fresh)
                fresh))))]
    (fn [{:keys [machine-id definition current-state from-highlight to-highlight
                 fired-edge-ids guard-blocked-edge-ids
                 sim? on-state-click on-edge-click read-only?
                 direction layout-options density theme
                 height show-minimap? show-controls? show-background?
                 overlays
                 machine-data
                 machine-data-inferred?
                 machine-data-sensitive
                 machine-data-large
                 machine-data-raw?
                 fit-signal
                 testid]
          :or   {direction         :tb
                 density           :regular
                 theme             :dark
                 height            "100%"
                 show-minimap?     false
                 show-controls?    true
                 show-background?  true
                 ;; rf2-27e38h (EP-0015) — Context-band egress defaults:
                 ;; local-redacted ON (raw? false), empty classification
                 ;; sets. A host feeding live `:data` declares the
                 ;; sensitive/large slots; the value-free type-caption
                 ;; production feeder is a redaction no-op.
                 machine-data-sensitive #{}
                 machine-data-large     #{}
                 machine-data-raw?      false
                 ;; rf2-5tz9p — the Context panel's sole production feeder is
                 ;; the static INFERRED shape (Xray's `static-context-shape`,
                 ;; key→type-caption). Default the inferred badge ON so the
                 ;; panel reads honestly today; a host wiring live `:data`
                 ;; values passes `:machine-data-inferred? false`.
                 machine-data-inferred? true
                 testid            "rf-mv-chart"}}]
      (let [;; rf2-jl72i — route the SINGLE topology parse through the
            ;; per-chart parse-cache (keyed only on `:definition`). A
            ;; decoration-only re-render reuses the cached `parsed`
            ;; identity, so neither the parser nor the downstream
            ;; graph/layout/relayout caches (whose keys embed `parsed`)
            ;; recompute. A changed `:definition` reparses once here and
            ;; busts every downstream cache.
            parsed     (parse-topology! definition)
            ;; rf2-lkwev — exclude synthetic parallel-region container
            ;; nodes from the state count + aria-label (they are zone
            ;; chrome, not states).
            ;; rf2-q129z8 — also exclude the synthetic ROOT-CONTAINER frame
            ;; (the named box wrapping the whole machine — structural chrome,
            ;; not a state).
            n-states   (count (remove #(or (:region? %) (:root-container? %))
                                      (:nodes parsed)))
            n-regions  (count (filter :region? (:nodes parsed)))
            n-trans    (count (:edges parsed))
            ;; rf2-8q5pt — resolve the density map ONCE so the ELK pass
            ;; sizes container `elk.padding` from the active density's
            ;; title-strip + body-pad constants (was a regular-only
            ;; literal). `chart-for-density` maps nil → regular and throws
            ;; on an unknown density (same total resolution the render body
            ;; does for `chart-vc` at L1177).
            elk-vc     (vc/chart-for-density density)
            ;; rf2-lamdfl + rf2-gnrkke — the OPT-IN adaptive gate. The whole
            ;; post-ELK subsystem (the per-machine aspect heuristic + the
            ;; parallel-region transpose + the back-edge reroute) is invoked
            ;; ONLY when the host opts a machine in with `:direction :auto`
            ;; (`post-elk/adaptive?`). The default `:tb` (and an explicit
            ;; `:tb`/`:lr`) takes the historical path: no heuristic
            ;; resolution, no `apply-post-elk` — the ELK result flows to the
            ;; projector byte-identical to main. The reverted #3453 made
            ;; `:auto` the DEFAULT and re-laid every machine; this redo keeps
            ;; every non-opted machine pixel-identical.
            adaptive?     (post-elk/adaptive? direction)
            ;; The direction ELK is actually fed. On the opt-in path the
            ;; `:auto` sentinel resolves to the per-machine heuristic
            ;; (`:tb`/`:lr`); otherwise `direction` passes straight through
            ;; unchanged (so `:tb`/`:lr` force exactly as before).
            elk-direction (if adaptive?
                            (post-elk/resolve-direction direction parsed)
                            direction)
            ;; Trigger an elk layout pass when the (definition,
            ;; direction, layout-options, density) tuple changes. Keep the
            ;; previous positions during in-flight layout to avoid an
            ;; empty-chart flash. rf2-8q5pt — `density` joins the key so a
            ;; density switch (which changes the derived container padding)
            ;; re-runs ELK rather than rendering the topology with the prior
            ;; density's header gaps. rf2-lamdfl — the RESOLVED ELK direction
            ;; keys the pass (identical to `direction` on the non-adaptive
            ;; path, so the historical key is unchanged for default/forced
            ;; machines) so an `:auto` machine whose heuristic verdict is
            ;; stable does not re-lay needlessly.
            ;; rf2-8z1rca — the count of Context-band rows the root-container
            ;; frame paints (one per `:machine-data` key). The band renders
            ;; only when `:machine-data` is non-empty (mirroring
            ;; `root-container-node`'s `(when (seq context) …)`), so an empty /
            ;; absent map is 0 rows — no band, no extra root top padding. Fed
            ;; into `compute-layout!` so the root frame reserves the band's
            ;; variable height ABOVE its title strip.
            context-rows (if (seq machine-data) (count machine-data) 0)
            ;; rf2-8z1rca — `context-rows` joins the layout key so adding /
            ;; removing context (which changes the reserved root top padding)
            ;; re-runs ELK rather than rendering against the prior band height.
            ;; rf2-9qbn0g — `adaptive?` (the post-ELK MODE flag) joins the key.
            ;; The pass is keyed by the RESOLVED `elk-direction`, but the post-
            ;; ELK transform (parallel transpose + back-edge reroute) is gated
            ;; by the RAW `:auto` opt-in, not the resolved direction. When
            ;; `:auto` resolves to the SAME direction as a forced/default `:tb`
            ;; (a linear / parallel machine, where `aspect-direction` returns
            ;; `:tb`), `elk-direction` is identical for `:direction :tb` and
            ;; `:direction :auto`, so toggling the prop did NOT invalidate the
            ;; cached layout — the back-edge reroute / parallel transpose never
            ;; applied on opt-IN, and stale-stayed on opt-OUT. Folding the
            ;; opt-in flag into the key makes a `:tb → :auto → :tb` flip re-run
            ;; the pass (apply then remove the transform) while still feeding
            ;; the resolved `elk-direction` to ELK.
            this-key   (compute-layout-key definition elk-direction layout-options
                                           density context-rows adaptive?)
            ;; rf2-d9ro2 — one ELK pass. `measured-dims` is nil on the
            ;; FIRST pass (nodes not yet rendered/measured) and the
            ;; xyflow-measured `{node-id {:width :height}}` map on the
            ;; measure-then-relayout SECOND pass. The settle reuses the
            ;; rf2-set3x auto-fit logic so BOTH passes frame the topology.
            run-layout!
            (fn [measured-dims]
              (compute-layout!
                parsed elk-direction layout-options machine-id measured-dims elk-vc
                context-rows
                (fn [raw-result]
                  ;; rf2-lamdfl + rf2-gnrkke — the OPT-IN post-ELK stage.
                  ;; On the adaptive path ONLY (and only for a successful
                  ;; result), after ELK settles + before the projector emits
                  ;; xyflow nodes/edges, run the cohesive post-ELK pass: the
                  ;; parallel-region stacking-axis transpose (§4.3.2) THEN the
                  ;; back-edge return-route detour (§4.3.1). On the DEFAULT /
                  ;; forced path `adaptive?` is false, so this is a no-op
                  ;; pass-through — the result is byte-identical to main. An
                  ;; error result is also passed through untouched (banner
                  ;; path unchanged).
                  (let [result (if (and adaptive?
                                        raw-result
                                        (seq (:positions raw-result))
                                        (nil? (:layout-error raw-result)))
                                 (post-elk/apply-post-elk raw-result parsed
                                                          elk-direction)
                                 raw-result)]
                  (when result
                    (reset! layout-state result)
                    ;; rf2-set3x — after a successful layout settle
                    ;; (positions present, no error), auto-fit the
                    ;; viewport ONCE per layout-key change so the operator
                    ;; sees the real topology framed. Gating on `:fit-key`
                    ;; differs from `this-key` preserves a manual zoom/pan
                    ;; across non-layout re-renders, and re-fits on every
                    ;; layout invalidation (definition / direction /
                    ;; layout-options change). The relayout pass shares
                    ;; the same gate — it re-fits the corrected topology
                    ;; (the measured box, not the floor-sized first pass).
                    (when-let [^js inst (and (seq (:positions result))
                                             (nil? (:layout-error result))
                                             (:instance @fit-state))]
                      (when (not= this-key (:fit-key @fit-state))
                        (swap! fit-state assoc :fit-key this-key)
                        (schedule-fit! inst))))))))
            ;; rf2-d9ro2 — the ELK-measurable node-id set: leaf states +
            ;; synthetic event-nodes. ELK sizes these from the rendered
            ;; box (`->elk-children` consults `measured-dims` for them).
            ;; CONTAINERS (region / compound) keep the floor seed — their
            ;; true extent comes from ELK laying out their measured
            ;; children, so a self-measurement would be circular — and
            ;; initial-marker glyphs are not ELK children. The relayout
            ;; signature is `read-measured-dims` RESTRICTED to this set, so
            ;; container/marker measurement noise never gates or re-fires
            ;; the relayout.
            measurable-ids
            (into (->> (:nodes parsed)
                       (remove #(or (:region? %) (:compound? %)))
                       (map :id)
                       set)
                  (map projection/event-node-id)
                  (:edges parsed))
            ;; rf2-d9ro2 — the measure-then-relayout trigger. xyflow calls
            ;; this (via `:onNodesChange`) after it measures the rendered
            ;; node DOM. We read the measured boxes off the captured
            ;; instance and, when the WHOLE topology has been measured AND
            ;; the measured map differs from what ELK was last fed for the
            ;; current layout-key, re-run ELK with the real boxes. The
            ;; `relayout-state` gate makes this fire at most once per
            ;; layout-key and never loop (a relayout moves positions, not
            ;; content → the next measurement matches the stored
            ;; signature). A new topology (`:key` mismatch) clears the
            ;; signature so the new machine gets its own single relayout.
            maybe-relayout!
            (fn []
              (when-let [^js inst (:instance @fit-state)]
                (let [dims    (select-keys (read-measured-dims inst)
                                           measurable-ids)
                      {:keys [key measured]} @relayout-state
                      fresh?  (not= key this-key)
                      ;; Only relayout once EVERY measurable node has a box
                      ;; (a partial map would lay out part of the graph at
                      ;; the floor and re-fire on the next measurement).
                      ready?  (and (seq measurable-ids)
                                   (= (count dims) (count measurable-ids)))
                      ;; On a fresh topology the prior signature belongs to
                      ;; the OLD key, so any measured map is a change.
                      changed? (or fresh? (not= dims measured))]
                  (when (and ready? changed?)
                    (reset! relayout-state {:key this-key :measured dims})
                    (run-layout! dims)))))
            ;; rf2-6tw7t — fit-on-entry. Orthogonal to the layout-key
            ;; auto-fit (rf2-set3x): that gate intentionally PRESERVES the
            ;; operator's manual zoom/pan across non-layout re-renders, so
            ;; re-entering the Machine tab with the SAME machine does NOT
            ;; refit and the graph can sit off-screen / wrongly scaled. The
            ;; host bumps `:fit-signal` (an opaque nonce) on panel-entry /
            ;; tab-activation; when it differs from the value we last fit on
            ;; we re-fit the framed topology through the same
            ;; `schedule-fit!` (double-rAF) path the layout settle uses. The
            ;; gate requires a captured instance + non-empty positions + no
            ;; layout-error — the same preconditions the settle fit checks —
            ;; so an entry that arrives before the first layout settles is
            ;; deferred to the settle/onInit fit (which frames the same
            ;; viewport). `::unfit` sentinel start means the first observed
            ;; signal fits once; a steady signal across ordinary re-renders
            ;; is a no-op.
            maybe-fit-on-signal!
            (fn []
              (let [{:keys [instance fit-sig]} @fit-state]
                (when (and instance
                           (not= fit-signal fit-sig)
                           (seq (:positions @layout-state))
                           (nil? (:layout-error @layout-state)))
                  (swap! fit-state assoc :fit-sig fit-signal)
                  (schedule-fit! instance))))]
        (when (and (seq (:nodes parsed))
                   (not= this-key @layout-key))
          (reset! layout-key this-key)
          ;; rf2-d9ro2 — a new topology starts a fresh measure cycle: drop
          ;; the prior measured signature so `maybe-relayout!` treats the
          ;; first measurement of the new machine as a change.
          (reset! relayout-state {:key this-key :measured nil})
          (run-layout! nil))
        ;; rf2-6tw7t — react to a host fit-signal bump every render. When
        ;; the signal changed AND the instance + positions are ready this
        ;; re-fits the framed topology (panel-entry / tab-activation). When
        ;; positions aren't ready yet the settle/onInit fit covers it.
        (maybe-fit-on-signal!)
        (cond
          (nil? definition)
          [:div {:data-testid (str testid "-no-definition")
                 :data-machine-id (str machine-id)
                 :role "img"
                 :aria-label (str "State machine"
                                  (when machine-id (str ": " (name machine-id)))
                                  " has no definition.")
                 :style {:padding     "16px"
                         :font-family tokens/sans-stack
                         :font-size   "12px"
                         :color       (:text-tertiary tokens/tokens)
                         :background  (:bg-2 tokens/tokens)
                         :border      (str "1px dashed " (:border-default tokens/tokens))
                         :border-radius "6px"}}
           "Machine definition is not introspectable — no topology to render."]

          (empty? (:nodes parsed))
          [:div {:data-testid (str testid "-empty")
                 :data-machine-id (str machine-id)
                 :role "img"
                 :aria-label (str "State machine"
                                  (when machine-id (str ": " (name machine-id)))
                                  " has no states.")
                 :style {:padding     "16px"
                         :font-family tokens/sans-stack
                         :font-size   "12px"
                         :color       (:text-tertiary tokens/tokens)}}
           "Machine has no states to render."]

          :else
          ;; rf2-k647w — resolve the `:density` prop ONCE per render to
          ;; its visual-constants map. `chart-for-density` throws on an
          ;; unknown density (per spec/API.md §Density), maps nil →
          ;; regular, and returns the closed-set map otherwise. The
          ;; resolved map threads through the projector onto every
          ;; node/edge `:data`; the resolved density name surfaces on
          ;; the root as `data-density`.
          (let [chart-vc          (vc/chart-for-density density)
                ;; `:density` defaults to `:regular` at the destructure
                ;; (the render `:or` map), so it is never nil here —
                ;; `(name density)` is total without the `(or … :regular)`
                ;; guard.
                density-name      (name density)
                ;; rf2-az6e2 — resolve the `:theme` prop to its palette
                ;; + the chart-semantic token map ONCE per render
                ;; (independent of `:density`). The token map threads
                ;; through the projector onto every node/edge `:data` so
                ;; the renderers paint the ACTIVE theme; the chart's own
                ;; chrome (below) reads `theme-palette` / `ct` too. The
                ;; resolved theme name surfaces on the root as
                ;; `data-theme`.
                theme-palette     (tokens/theme-palette theme)
                ct                (tokens/chart-tokens theme-palette)
                theme-name        (name (or theme :dark))
                ;; rf2-g2svr (G1) — `highlight-ids` resolves the WHOLE
                ;; `:current-state` to the SET of active leaves, so a
                ;; PARALLEL snapshot's N simultaneously-active leaves
                ;; (one per region) ALL light up, not just one. Flat /
                ;; compound snapshots resolve to a singleton set (same
                ;; result the prior scalar `highlight-id` produced).
                highlight-ids     (layout/highlight-ids current-state)
                from-highlight-id (layout/highlight-id from-highlight)
                to-highlight-id   (layout/highlight-id to-highlight)
                callback          (when-not read-only? on-state-click)
                edge-callback     (when-not read-only? on-edge-click)
                {:keys [positions edge-points edge-labels layout-error]}
                @layout-state
                fired-edge-id-set (set fired-edge-ids)
                ;; rf2-fzrzlw — the guard-blocked no-op edge-id set; the
                ;; projector marks each matching edge + event-node
                ;; `:guardBlocked`. nil → #{} default.
                guard-blocked-edge-id-set (set guard-blocked-edge-ids)
                ;; rf2-dnmbs — memoise the projection + JS marshalling
                ;; (`xyflow-graph` then `clj->js`) on the inputs that
                ;; actually change the projected graph. A tick-only /
                ;; fit-signal-only / parent re-render hits the cache and
                ;; reuses the converted `#js` arrays; a highlight, an ELK
                ;; settle (new positions / routes), a new definition, a
                ;; density/theme switch, or a callback change busts it.
                ;; `chart-vc` / `ct` are pure functions of `density` /
                ;; `theme`, so the cheaper keywords stand in for them in
                ;; the key. The resolved (read-only-gated) callbacks are
                ;; keyed directly so a changed handler identity rebuilds.
                ;; rf2-q129z8 — `machine-id` / `machine-data` /
                ;; `machine-data-inferred?` now feed the ROOT-CONTAINER frame
                ;; header (the named box's machine name + Context band), so a
                ;; change in any of them must rebuild the projected graph.
                cache-key  [parsed positions edge-points edge-labels
                            highlight-ids from-highlight-id to-highlight-id
                            fired-edge-id-set guard-blocked-edge-id-set
                            sim? callback edge-callback
                            density theme
                            machine-id machine-data machine-data-inferred?
                            ;; rf2-27e38h — a change in the Context-band
                            ;; egress classification / raw opt-in must
                            ;; rebuild the projected band display.
                            machine-data-sensitive machine-data-large
                            machine-data-raw?]
                {:keys [js-nodes js-edges]}
                (project+convert!
                  cache-key
                  (fn []
                    (projection/xyflow-graph parsed
                              positions
                              {:highlight-ids     highlight-ids
                               :from-highlight-id from-highlight-id
                               :to-highlight-id   to-highlight-id
                               :sim?              sim?
                               :on-state-click    callback
                               :on-edge-click     edge-callback
                               ;; rf2-cz8v6 (G2) — elk's routed bend-
                               ;; points; the projector attaches each
                               ;; edge's route to its :data {:points}.
                               :edge-points       edge-points
                               ;; rf2-rlq97 — elk's computed label
                               ;; positions; the projector attaches each
                               ;; labelled edge's position to :data
                               ;; {:labelPos} (empty under events-as-nodes,
                               ;; where the label rides on the event-node).
                               :edge-labels       edge-labels
                               ;; rf2-qeemm (G3) — the fired-this-epoch
                               ;; edge-id set; the projector marks each
                               ;; matching edge :fired. nil → #{} default.
                               :fired-edge-ids    fired-edge-id-set
                               ;; rf2-fzrzlw — the guard-blocked no-op
                               ;; edge-id set; the projector marks each
                               ;; matching edge + event-node :guardBlocked.
                               :guard-blocked-edge-ids guard-blocked-edge-id-set
                               :chart             chart-vc
                               ;; rf2-az6e2 — the resolved chart-semantic
                               ;; token map for the active theme; the
                               ;; projector threads it onto every node/edge
                               ;; `:data {:palette}`.
                               :palette           ct
                               ;; rf2-q129z8 — the ROOT-CONTAINER frame's
                               ;; header content. The projector overrides the
                               ;; synthetic frame node's label with the machine
                               ;; name and threads the inferred Context shape +
                               ;; inferred flag onto its `:data` so
                               ;; `nodes/root-container-node` paints the named
                               ;; frame header + Context band (absorbing the old
                               ;; corner-pinned title strip + Context overlay).
                               :machine-id        machine-id
                               :machine-data      machine-data
                               :machine-data-inferred? machine-data-inferred?
                               ;; rf2-27e38h (EP-0015) — local-redacted
                               ;; Context-band egress contract. The band is
                               ;; serialised into SVG/PNG/clipboard, so live
                               ;; `:data` values are redacted by default; the
                               ;; host declares which slots are sensitive/large
                               ;; (from the machine's `:data-schema`) and may
                               ;; opt into raw via `:machine-data-raw?`.
                               :machine-data-sensitive machine-data-sensitive
                               :machine-data-large machine-data-large
                               :machine-data-raw? machine-data-raw?})))
                aria-label (str "State machine"
                                (when machine-id
                                  (str ": " (name machine-id)))
                                " with " n-states " "
                                (if (= 1 n-states) "state" "states")
                                " and " n-trans " "
                                (if (= 1 n-trans) "transition" "transitions")
                                (when (pos? n-regions)
                                  (str " across " n-regions " parallel "
                                       (if (= 1 n-regions) "region" "regions")))
                                ".")
                ;; rf2-7w4qr — the `:overlays` descriptor vector. Each
                ;; descriptor carries its own `:tick` (one rAF clock per
                ;; chart, Lock #8 — the host's clock drives whichever
                ;; overlay it ticks).
                overlay-descriptors (filterv map? overlays)]
            [:div {:data-testid testid
                   :data-machine-id (str machine-id)
                   :data-node-count (str n-states)
                   :data-region-count (str n-regions)
                   :data-edge-count (str n-trans)
                   ;; rf2-shv82 — `data-edge-count-projected` exposes the
                   ;; xyflow-graph projector's edge-array length AFTER
                   ;; entry edges + parent-level adjustments. Pairs with
                   ;; the existing `data-edge-count` (parsed-transition
                   ;; count) so visual-regression tests can pin the
                   ;; parser→projector→DOM chain end to end and catch
                   ;; silent drops (like the rf2-shv82 compound-endpoint
                   ;; bug) at any layer.
                   :data-edge-count-projected (str (alength js-edges))
                   ;; rf2-8d7w1 — stash the export/share-relevant chart
                   ;; state on the root DOM node as a JS property so the
                   ;; `export` namespace can derive a share-URL / alt-text
                   ;; summary from `chart-element` without re-stamping the
                   ;; (potentially large) definition into a data-attribute.
                   ;; The seam carries ONLY topology + the active-state
                   ;; NAME + summary counts — never runtime `:data` (per
                   ;; Principles §No session data in shares). Plain
                   ;; per-render mutation (no animation-clock coupling); it
                   ;; lives on the topology plane (definition-derived), so
                   ;; it does not violate Lock #11's plane separation.
                   :ref (fn [^js el]
                          (when el
                            ;; Store the CLJS chart-state map directly as
                            ;; an opaque JS property (NOT a #js object —
                            ;; that would munge the dashed keyword keys).
                            ;; `export/chart-state-of` reads it back as a
                            ;; CLJS map. The `^js` hint marks
                            ;; `_rfMvChartState` as an extern so :advanced
                            ;; does NOT munge the property name (it is read
                            ;; back by literal name in `export`).
                            (set! (.-_rfMvChartState el)
                                  {:machine-id    machine-id
                                   :definition    definition
                                   :current-state current-state
                                   :node-count    n-states
                                   :edge-count    n-trans
                                   :region-count  n-regions})))
                   ;; rf2-k647w — the resolved density surfaces here so
                   ;; hosts + tests read the active density without
                   ;; re-reading the bound prop (per spec/API.md
                   ;; §Density resolution rules).
                   :data-density density-name
                   ;; rf2-az6e2 — the resolved theme surfaces here so
                   ;; hosts + tests read the active theme without
                   ;; re-reading the bound prop. Dark remains the Xray
                   ;; default. Independent of `data-density`.
                   :data-theme theme-name
                   ;; rf2-g2svr (G1) — the FULL active set surfaces as a
                   ;; sorted, space-joined attr so hosts + DOM tests can
                   ;; read every active leaf (one for a flat/compound
                   ;; snapshot, N for a parallel one).
                   :data-highlight-ids (str/join " " (sort highlight-ids))
                   :data-from-highlight-id (or from-highlight-id "")
                   :data-to-highlight-id (or to-highlight-id "")
                   ;; rf2-qeemm (G3) — the fired-this-epoch edge-id set
                   ;; surfaces as a sorted, space-joined attr so hosts +
                   ;; DOM tests can read every traversed arm at the chart
                   ;; root (mirrors `data-highlight-ids`). "" when none.
                   :data-fired-edge-ids (str/join " " (sort (set fired-edge-ids)))
                   ;; rf2-fzrzlw — the guard-blocked no-op edge-id set
                   ;; surfaces as a sorted, space-joined attr so hosts +
                   ;; DOM tests can read every attempted-and-rejected arm
                   ;; at the chart root (mirrors `data-fired-edge-ids`).
                   ;; "" when none.
                   :data-guard-blocked-edge-ids (str/join " " (sort (set guard-blocked-edge-ids)))
                   ;; rf2-4lyvh — surface ELK layout-failure as a root
                   ;; data-attr so DOM tests + hosts can pin the failure
                   ;; without inspecting the banner DOM. "true" / "false";
                   ;; the visible banner below carries the human-readable
                   ;; message.
                   :data-layout-error (if layout-error "true" "false")
                   :role "application"
                   :aria-label aria-label
                   :style {:position "relative"
                           :width    "100%"
                           :height   height
                           ;; rf2-az6e2 — the chart canvas reads the
                           ;; ACTIVE theme's surface, not the dark alias.
                           :background (:bg-1 theme-palette)
                           :border-radius "4px"
                           :overflow "hidden"}}
             ;; Inline keyframes + reduced-motion seam — mirrors the
             ;; previous SVG render's <style> block so the focused-edge
             ;; glow continues to work without an external stylesheet.
             [:style {:dangerouslySetInnerHTML {:__html chart-stylesheet}}]
             [:> ReactFlow
              ;; rf2-dnmbs — `js-nodes` / `js-edges` are the memoised
              ;; `clj->js` results from `project+convert!`; a tick-only /
              ;; overlay-only re-render reuses the SAME `#js` arrays so the
              ;; deep marshalling is skipped on the 60Hz hot path (and
              ;; xyflow sees stable object identity, skipping its own diff).
              {:nodes               js-nodes
               :edges               js-edges
               :nodeTypes           node-types-memo
               :edgeTypes           edge-types-memo
               :nodesDraggable      false
               :nodesConnectable    false
               :elementsSelectable  false
               :panOnDrag           true
               :zoomOnScroll        true
               :fitView             true
               :fitViewOptions      #js {:padding 0.1}
               :minZoom             0.2
               :maxZoom             4.0
               :proOptions          #js {:hideAttribution true}
               ;; rf2-set3x — capture the xyflow ReactFlowInstance so
               ;; the compute-layout! settle can re-fit the viewport
               ;; after the async elk pass resolves (the built-in
               ;; `:fitView true` fires only once on mount, while
               ;; every node still sits at the default origin). If
               ;; positions have ALREADY arrived by the time onInit
               ;; runs (fast layout / cached settle), fit immediately
               ;; — the post-mount xyflow fit was equally degenerate.
               :onInit              (fn [^js instance]
                                      (let [{:keys [positions
                                                    layout-error]} @layout-state
                                            key-now                @layout-key
                                            should-fit-now?
                                            (and (seq positions)
                                                 (nil? layout-error)
                                                 (not= key-now
                                                       (:fit-key @fit-state)))]
                                        (swap! fit-state assoc :instance instance)
                                        (when should-fit-now?
                                          (swap! fit-state assoc :fit-key key-now)
                                          (schedule-fit! instance))
                                        ;; rf2-d9ro2 — xyflow may have
                                        ;; already measured the nodes by the
                                        ;; time onInit fires (fast commit).
                                        ;; Kick the measure-then-relayout
                                        ;; check too, in case no further
                                        ;; dimension change fires.
                                        (maybe-relayout!)
                                        ;; rf2-6tw7t — a host `:fit-signal`
                                        ;; may have been pending BEFORE the
                                        ;; instance was captured (entry that
                                        ;; arrived ahead of onInit). Now that
                                        ;; the instance exists, honour it if
                                        ;; positions have already settled.
                                        (maybe-fit-on-signal!)))
               ;; rf2-d9ro2 — measure-then-relayout signal. xyflow emits a
               ;; `dimensions` NodeChange once it measures the rendered
               ;; node DOM; that is our cue to read the real boxes back and
               ;; re-run ELK with them (`maybe-relayout!` gates it to once
               ;; per layout-key, no loop). We do NOT feed the changes back
               ;; into `:nodes` (positions are ELK-owned, the chart is
               ;; non-interactive) — the handler is purely a measurement
               ;; trigger.
               :onNodesChange       (fn [^js _changes] (maybe-relayout!))}
              (when show-background?
                ;; rf2-k647w — dot-grid spacing + radius track the
                ;; resolved density (`:dot-grid-spacing-px` /
                ;; `:dot-grid-radius-px`); regular = 16 / 1.0, the
                ;; historical hardcoded pair.
                [:> Background {:variant (.-Dots BackgroundVariant)
                                :gap (:dot-grid-spacing-px chart-vc)
                                :size (:dot-grid-radius-px chart-vc)
                                ;; rf2-az6e2 — dot colour resolves through
                                ;; the active theme's accent (light hosts
                                ;; get a darker dot).
                                :color (tokens/with-alpha :accent 0.4 theme-palette)}])
              (when show-controls?
                [:> Controls {:showZoom true
                              :showFitView true
                              :showInteractive false}])
              (when show-minimap?
                [:> MiniMap {:zoomable true
                             :pannable true
                             :nodeColor (fn [_] (:bg-3 theme-palette))
                             :maskColor (tokens/with-alpha :bg-0 0.6 theme-palette)}])]
             ;; rf2-q129z8 — the ROOT MACHINE CHROME (machine title strip +
             ;; Context shape) no longer renders as a corner-pinned overlay
             ;; here. It is now the HEADER of the synthetic ROOT-CONTAINER
             ;; frame node (`chart.nodes/root-container-node`), an ELK-sized
             ;; box that HUGS + TRACKS the whole topology and reflows on
             ;; resize — eliminating the pre-q129z8 corner-pinning (the strip
             ;; welded to the drawing surface while xyflow re-fit the
             ;; topology) and the top-left chrome collision. The machine name
             ;; + Context band are threaded onto the frame node's `:data` by
             ;; `chart.projection/xyflow-graph`.
             ;; rf2-7w4qr — host-fed overlays, composed through the
             ;; single `:overlays` slot. Each descriptor map (keyed on
             ;; `:id`) is dispatched to its rendering namespace by
             ;; `render-overlay` (after-rings / spawn-all-join /
             ;; cancellation-cascade); the renderers are unchanged — only
             ;; the wiring is lifted here. Every overlay is a sibling of
             ;; the xyflow canvas inside this position:relative wrapper
             ;; and walks the rendered node DOM (`rf-mv-chart-node-<id>`)
             ;; to anchor itself; the host owns the trace→spec projection
             ;; + the per-descriptor `:tick` (Lock #8 — one rAF clock per
             ;; chart). Dormant descriptors + unknown `:id`s render nil.
             (map-indexed
               (fn [i descriptor]
                 ^{:key (str "rf-mv-overlay-" i "-"
                             (pr-str (:id descriptor)))}
                 [render-overlay descriptor])
               overlay-descriptors)
             ;; rf2-q129z8 — the `:machine-data` Context shape no longer
             ;; renders as a corner-pinned overlay panel here; it is now the
             ;; Context BAND in the synthetic ROOT-CONTAINER frame header
             ;; (`chart.nodes/root-container-node`), threaded onto the frame
             ;; node's `:data {:context}` by `chart.projection/xyflow-graph`.
             ;; This eliminates the top-left chrome collision (the panel used
             ;; to weld to the corner under the title strip) — the Context now
             ;; rides INSIDE the frame that hugs the topology.
             ;; rf2-4lyvh — ELK layout-failure indicator. When
             ;; `compute-layout!` surfaces a `:layout-error` slot the
             ;; chart would otherwise render every node stacked at
             ;; origin (no positions). Paint a small banner over the
             ;; canvas so the operator sees the failure IN the panel,
             ;; not just in the trace bus or DevTools console. The full
             ;; structured failure lives on the `:rf.error/machines-
             ;; viz-elk-layout-failed` trace event (tools/Xray Issues
             ;; panel surfaces it from there); this banner is the dev-
             ;; tool affordance for the operator running the live app.
             (when layout-error
               [:div {:data-testid (str testid "-layout-error-banner")
                      :role "status"
                      :aria-live "polite"
                      :style {:position      "absolute"
                              :top           "8px"
                              :left          "8px"
                              :right         "8px"
                              :z-index       10
                              :padding       "8px 12px"
                              :font-family   tokens/sans-stack
                              :font-size     "12px"
                              :line-height   1.4
                              :color         (:text-primary theme-palette)
                              :background    (tokens/with-alpha :error 0.15 theme-palette)
                              :border        (str "1px solid "
                                                  (:error theme-palette))
                              :border-radius "4px"}}
                [:strong {:style {:color (:error theme-palette)
                                  :margin-right "6px"}}
                 "Layout failed."]
                [:span (or (get-in layout-error [:error :message])
                           "ELK threw an unknown error.")]
                [:span {:style {:color (:text-tertiary theme-palette)
                                :margin-left "6px"}}
                 "See console / Issues panel."]])]))))))
