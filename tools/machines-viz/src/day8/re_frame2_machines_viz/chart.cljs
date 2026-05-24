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
    - `xyflow-graph` — pure-data projector that turns
      `chart.layout/parse-definition` output into the xyflow
      `:nodes` + `:edges` shape, with per-node/per-edge `:data`
      payloads carrying the active/from-highlight/to-highlight
      flags, event labels, tags, etc.
    - The elkjs `compute-layout!` async pass + cache.

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
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
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
      (added ELK 0.9; elkjs 0.11 wraps a newer core)."
  {"elk.algorithm"                              "layered"
   "elk.direction"                              "DOWN"
   "elk.spacing.nodeNode"                       "40"
   "elk.layered.spacing.nodeNodeBetweenLayers"  "70"
   "elk.layered.crossingMinimization.strategy"  "LAYER_SWEEP"
   "elk.edgeRouting"                            "ORTHOGONAL"
   "elk.json.edgeCoords"                        "ROOT"})

(defn- ->elk-input
  "Build an elk.js JS-side input graph for the given parsed nodes +
  edges + direction. Parallel machines (rf2-lkwev) get a hierarchical
  graph (region containers with nested state children) so elkjs sizes
  + positions each orthogonal zone and its states; flat machines get
  the original single-level child list."
  [{:keys [edges] :as parsed} direction layout-options]
  (let [dir-str (case direction
                  :lr "RIGHT"
                  :tb "DOWN"
                  "DOWN")
        opts    (-> default-elk-options
                    (merge (or layout-options {}))
                    (assoc "elk.direction" dir-str)
                    ;; Hierarchical layout must descend into nested
                    ;; children — parallel-region states (rf2-lkwev) AND
                    ;; compound substates (rf2-54s5a). Enable whenever any
                    ;; node nests (has :parent-id) or the graph is parallel.
                    (cond-> (or (:parallel? parsed)
                                (some :parent-id (:nodes parsed)))
                      (assoc "elk.hierarchyHandling" "INCLUDE_CHILDREN")))]
    #js {:id "root"
         :layoutOptions (clj->js opts)
         :children (clj->js (projection/->elk-children parsed))
         :edges (clj->js
                  (mapv (fn [e]
                          {:id (:id e)
                           :sources [(:source e)]
                           :targets [(:target e)]
                           :labels [{:text (:event-label e)}]})
                        edges))}))

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

(defn- elk-result->positions
  "Adapter: elk.js JS result → `{:positions {node-id {:x :y :width
  :height}} :edge-points {edge-id [{:x :y} …]}}`.

  Used by `xyflow-graph` to merge xyflow-side node objects with
  elk-laid-out positions, and (rf2-cz8v6 / G2) to route edges through
  elk's computed bend-points.

  ## Positions

  Walks nested elk children (rf2-lkwev — parallel machines nest each
  region's states under the region container). elkjs reports a child's
  `x`/`y` RELATIVE to its parent container, which is exactly what
  xyflow's parentNode sub-flow wants — so we record each node's
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
  (let [positions   (atom {})
        edge-points (atom {})]
    (letfn [(collect-edges! [^js node]
              (let [edges (or (.-edges node) #js [])
                    n     (alength edges)]
                (dotimes [i n]
                  (let [e   (aget edges i)
                        pts (elk-edge-points e)]
                    (when pts
                      (swap! edge-points assoc (.-id e) pts))))))
            (walk! [^js node]
              (collect-edges! node)
              (let [children (or (.-children node) #js [])
                    n        (alength children)]
                (dotimes [i n]
                  (let [c (aget children i)]
                    (swap! positions assoc (.-id c)
                           {:x      (or (.-x c) 0)
                            :y      (or (.-y c) 0)
                            :width  (or (.-width c) projection/state-node-min-width)
                            :height (or (.-height c) projection/state-node-min-height)})
                    (walk! c)))))]
      (walk! elk-result))
    {:positions   @positions
     :edge-points @edge-points}))

(defn compute-layout!
  "Run elk.js layout on `parsed` (the output of
  `chart.layout/parse-definition`); call `done-fn` with a map
  `{:positions {node-id {:x :y :width :height}} :edge-points {edge-id
  [{:x :y} …]}}` when ready (or with `nil` on failure). The
  `:edge-points` half (rf2-cz8v6 / G2) carries elk's routed bend-points
  so the chart routes edges AROUND nested containers instead of cutting
  across them. The async path is idiomatic xyflow + elkjs:

    1. `(->elk-input parsed direction layout-options)` builds a JS
       graph.
    2. `.layout` returns a Promise.
    3. Resolve → `elk-result->positions` → callback.
    4. Reject → callback with nil; caller may render a 'laying out…'
       placeholder."
  ([parsed done-fn]
   (compute-layout! parsed :tb nil done-fn))
  ([parsed direction layout-options done-fn]
   (let [input (->elk-input parsed direction layout-options)
         p     (try (.layout elk-instance input)
                    (catch :default _ nil))]
     (if (and p (.-then p))
       (-> p
           (.then (fn [result]
                    (done-fn (elk-result->positions result))))
           (.catch (fn [_e] (done-fn nil))))
       (done-fn nil)))))

;; ---- graph projection (parsed + positions → xyflow nodes/edges) ---------
;;
;; rf2-0gmwp — the pure projector (`xyflow-graph` / `choose-edge-type`
;; / the elk `children` shape) moved to `chart.projection` so the JVM
;; test corpus can pin it without loading xyflow/elkjs. `chart.cljs`
;; retains only the JS-interop layout glue above + the React component
;; below.

;; ---- inline keyframes ---------------------------------------------------

(def ^:private chart-stylesheet
  "Inline stylesheet carrying the transition-glow keyframes + the
  prefers-reduced-motion override. Mirrors the previous SVG render's
  `transition-glow-css` so the focused-edge animation continues to
  work post-migration."
  (str
    ":root { --rf-xray-motion-scale: 1; }\n"
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-xray-motion-scale: 0.001; }\n"
    "}\n"
    "@keyframes mv-chart-transition-glow {\n"
    "  0%   { opacity: 0.55; }\n"
    "  20%  { opacity: 1.00; }\n"
    "  100% { opacity: 0.85; }\n"
    "}\n"
    ".react-flow__attribution { display: none !important; }\n"))

;; ---- memoised node/edge type maps ---------------------------------------

(def ^:private node-types-memo (nodes/node-types))
(def ^:private edge-types-memo (edges/edge-types))

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
    :direction         — `:tb` (top-to-bottom, default) or `:lr`.
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
    :after-ring-specs  — rf2-uv1on. Optional vector of presentation-
                         ready `:after`-timer ring-specs (each
                         `{:node-id :fraction :color :cancelled?
                         :tooltip :testid}`). When non-empty the chart
                         mounts the `chart.overlays.after-rings`
                         overlay as a sibling of the canvas; it walks
                         the rendered node DOM to position each ring.
                         The host owns the trace→spec projection +
                         the scrubber-aware fraction (Xray supplies
                         these from its trace buffer). nil / empty →
                         no overlay layer.
    :after-ring-tick   — opaque value the host bumps to force the
                         overlay to re-measure the DOM + repaint the
                         swept arcs (Xray passes `now-ms`; Lock #8 —
                         one rAF clock per chart, owned host-side).
    :on-after-ring-hover / :on-after-ring-leave — `(fn [node-id] ...)`
                         hover callbacks the overlay wires on each ring.
    :spawn-all-join    — rf2-3ow55. Optional presentation-ready
                         `:spawn-all` join-spec (`{:node-id :join
                         :children :resolved? :on-all-complete
                         :on-any-failed}`). When present the chart
                         mounts the `chart.overlays.spawn-all-join`
                         inspector beside the spawn-all-bearing state;
                         it shows the spawned children + join state.
                         The host owns the trace→spec projection from
                         its `:rf.machine.spawn-all/*` buffer. nil →
                         no inspector.
    :on-spawn-child-click — `(fn [child-key] ...)`; fires on a join-
                         inspector child-row click (Xray pivots to
                         the child instance).
    :cancellation-cascade — rf2-3ow55. Optional presentation-ready
                         cascade-spec (`{:node-id :parent-label
                         :from-state :steps}`). When present (and the
                         step list is non-empty) the chart mounts the
                         `chart.overlays.cancellation-cascade`
                         waterfall beneath the parent state. The host
                         owns the trace→spec projection from the
                         cancellation trace cluster. nil / no steps →
                         dormant (no overlay).
    :overlay-tick      — opaque value the host bumps to force the
                         spawn-all + cascade overlays to re-measure +
                         repaint (mirrors `:after-ring-tick`).
    :testid            — root wrapper `data-testid`; defaults to
                         `\"rf-mv-chart\"` so tests + hosts find it."
  [_initial-props]
  (let [;; rf2-cz8v6 (G2) — the layout atom now holds BOTH the node
        ;; positions and elk's routed edge bend-points
        ;; (`{:positions {…} :edge-points {…}}`) so the projector can
        ;; route edges around nested containers. Starts empty (no
        ;; positions, no routes) — the pre-layout render falls back to
        ;; origin + bezier until the async elk pass resolves.
        layout-state  (r/atom {:positions {} :edge-points {}})
        layout-key    (r/atom nil)]
    (fn [{:keys [machine-id definition current-state from-highlight to-highlight
                 fired-edge-ids
                 sim? on-state-click on-edge-click read-only?
                 direction layout-options density
                 height show-minimap? show-controls? show-background?
                 after-ring-specs after-ring-tick
                 on-after-ring-hover on-after-ring-leave
                 spawn-all-join on-spawn-child-click
                 cancellation-cascade overlay-tick
                 testid]
          :or   {direction         :tb
                 height            "100%"
                 show-minimap?     false
                 show-controls?    true
                 show-background?  true
                 testid            "rf-mv-chart"}}]
      (let [parsed     (layout/parse-definition definition)
            ;; rf2-lkwev — exclude synthetic parallel-region container
            ;; nodes from the state count + aria-label (they are zone
            ;; chrome, not states).
            n-states   (count (remove :region? (:nodes parsed)))
            n-regions  (count (filter :region? (:nodes parsed)))
            n-trans    (count (:edges parsed))
            ;; Trigger an elk layout pass when the (definition,
            ;; direction, layout-options) tuple changes. Keep the
            ;; previous positions during in-flight layout to avoid an
            ;; empty-chart flash.
            this-key   [definition direction layout-options]]
        (when (and (seq (:nodes parsed))
                   (not= this-key @layout-key))
          (reset! layout-key this-key)
          (compute-layout! parsed direction layout-options
                           (fn [result]
                             (when result
                               (reset! layout-state result)))))
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
                density-name      (name (or density :regular))
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
                {:keys [positions edge-points]} @layout-state
                {:keys [nodes edges]}
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
                               ;; rf2-qeemm (G3) — the fired-this-epoch
                               ;; edge-id set; the projector marks each
                               ;; matching edge :fired. nil → #{} default.
                               :fired-edge-ids    (set fired-edge-ids)
                               :chart             chart-vc})
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
                                ".")]
            [:div {:data-testid testid
                   :data-machine-id (str machine-id)
                   :data-node-count (str n-states)
                   :data-region-count (str n-regions)
                   :data-edge-count (str n-trans)
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
                   ;; rf2-g2svr (G1) — the FULL active set surfaces as a
                   ;; sorted, space-joined attr so hosts + DOM tests can
                   ;; read every active leaf (N for a parallel snapshot).
                   ;; `data-highlight-id` is kept as the single-active
                   ;; convenience: the lone id when the set is a
                   ;; singleton, "" otherwise (flat/compound stay
                   ;; observationally identical to pre-G1).
                   :data-highlight-ids (str/join " " (sort highlight-ids))
                   :data-highlight-id (if (= 1 (count highlight-ids))
                                        (first highlight-ids)
                                        "")
                   :data-from-highlight-id (or from-highlight-id "")
                   :data-to-highlight-id (or to-highlight-id "")
                   ;; rf2-qeemm (G3) — the fired-this-epoch edge-id set
                   ;; surfaces as a sorted, space-joined attr so hosts +
                   ;; DOM tests can read every traversed arm at the chart
                   ;; root (mirrors `data-highlight-ids`). "" when none.
                   :data-fired-edge-ids (str/join " " (sort (set fired-edge-ids)))
                   :role "application"
                   :aria-label aria-label
                   :style {:position "relative"
                           :width    "100%"
                           :height   height
                           :background (:bg-1 tokens/tokens)
                           :border-radius "4px"
                           :overflow "hidden"}}
             ;; Inline keyframes + reduced-motion seam — mirrors the
             ;; previous SVG render's <style> block so the focused-edge
             ;; glow continues to work without an external stylesheet.
             [:style {:dangerouslySetInnerHTML {:__html chart-stylesheet}}]
             [:> ReactFlow
              {:nodes               (clj->js nodes)
               :edges               (clj->js edges)
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
               :proOptions          #js {:hideAttribution true}}
              (when show-background?
                ;; rf2-k647w — dot-grid spacing + radius track the
                ;; resolved density (`:dot-grid-spacing-px` /
                ;; `:dot-grid-radius-px`); regular = 16 / 1.0, the
                ;; historical hardcoded pair.
                [:> Background {:variant (.-Dots BackgroundVariant)
                                :gap (:dot-grid-spacing-px chart-vc)
                                :size (:dot-grid-radius-px chart-vc)
                                :color (tokens/with-alpha :accent 0.4)}])
              (when show-controls?
                [:> Controls {:showZoom true
                              :showFitView true
                              :showInteractive false}])
              (when show-minimap?
                [:> MiniMap {:zoomable true
                             :pannable true
                             :nodeColor (fn [_] (:bg-3 tokens/tokens))
                             :maskColor (tokens/with-alpha :bg-0 0.6)}])]
             ;; rf2-uv1on — `:after`-timer countdown rings. The overlay
             ;; is a sibling of the xyflow canvas inside this
             ;; position:relative wrapper; it walks the rendered node DOM
             ;; (`rf-mv-chart-node-<id>`) to position each ring. Hosts
             ;; that want rings pass presentation-ready `:after-ring-specs`
             ;; (see `chart.overlays.after-rings`); the host owns the
             ;; trace→spec projection + the rAF clock that bumps
             ;; `:after-ring-tick` (Lock #8 — one clock per chart). Xray
             ;; mounts the same overlay via its `machine_canvas` wrapper;
             ;; this prop path serves standalone hosts (viewer, Story).
             (when (seq after-ring-specs)
               [after-rings/AfterRingsOverlay
                {:ring-specs after-ring-specs
                 :tick       after-ring-tick
                 :on-hover   on-after-ring-hover
                 :on-leave   on-after-ring-leave}])
             ;; rf2-3ow55 — `:spawn-all` join inspector. Same
             ;; sibling-overlay shape as the after-rings overlay: it
             ;; walks the node DOM to anchor a join-state card beside
             ;; the spawn-all-bearing state. The host projects the
             ;; presentation-ready join-spec from its
             ;; `:rf.machine.spawn-all/*` trace buffer.
             (when (and spawn-all-join (:node-id spawn-all-join))
               [overlay-spawn-all/SpawnAllJoinOverlay
                {:join-spec      spawn-all-join
                 :tick           overlay-tick
                 :on-child-click on-spawn-child-click}])
             ;; rf2-3ow55 — cancellation-cascade visualiser. Dormant
             ;; unless the host supplies a cascade-spec with steps;
             ;; when a parent transition cancels children the host
             ;; projects the cascade from the cancellation trace
             ;; cluster and the overlay paints the waterfall beneath
             ;; the parent state.
             (when (and cancellation-cascade
                        (:node-id cancellation-cascade)
                        (seq (:steps cancellation-cascade)))
               [overlay-cascade/CancellationCascadeOverlay
                {:cascade-spec cancellation-cascade
                 :tick         overlay-tick}])]))))))
