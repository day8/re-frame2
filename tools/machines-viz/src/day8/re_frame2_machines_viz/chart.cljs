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
    - The substrate-agnostic re-export hook (`MachineChart`
      registered against the v2 `:view` registration surface).

  ## What this does NOT own

    - **Topology parsing** — lives in `chart.layout`. This ns
      consumes the parsed graph but does not walk the definition
      tree.
    - **`countdown-ring` / `sparkline` primitives** — moved to
      `chart.primitives` since they are consumed OUTSIDE the chart
      (Causa overlays, cluster sparklines).
    - **Viewport state** — xyflow owns zoom/pan/fit internally;
      hosts no longer manage `{:scale :tx :ty}` slots. The previous
      `chart.controls` reducer + Causa's `machine_canvas` viewport
      machinery are obsolete with this migration.

  ## Bundle isolation

  `@xyflow/react` + `elkjs` are `devDependency`s of
  `implementation/package.json`. The chart is dev-only (Causa
  preload + the static viewer page). Production bundles MUST NOT
  pull either; the `check-bundle-isolation.cjs` sentinel pins this
  contract."
  (:require ["@xyflow/react" :as xyflow]
            ["elkjs/lib/elk.bundled.js" :as elkjs]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.nodes :as nodes]
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

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
  layout engine itself is unchanged; only the renderer swapped)."
  {"elk.algorithm"                              "layered"
   "elk.direction"                              "DOWN"
   "elk.spacing.nodeNode"                       "40"
   "elk.layered.spacing.nodeNodeBetweenLayers"  "70"
   "elk.layered.crossingMinimization.strategy"  "LAYER_SWEEP"
   "elk.edgeRouting"                            "SPLINES"})

(defn- ->elk-input
  "Build an elk.js JS-side input graph for the given parsed nodes +
  edges + direction."
  [{:keys [nodes edges]} direction layout-options]
  (let [dir-str (case direction
                  :lr "RIGHT"
                  :tb "DOWN"
                  "DOWN")
        opts    (-> default-elk-options
                    (merge (or layout-options {}))
                    (assoc "elk.direction" dir-str))]
    #js {:id "root"
         :layoutOptions (clj->js opts)
         :children (clj->js
                     (mapv (fn [n]
                             {:id     (:id n)
                              :width  (if (:compound? n) 220 nodes/state-node-min-width)
                              :height (if (:compound? n) 120 nodes/state-node-min-height)
                              :labels [{:text (:label n)}]})
                           nodes))
         :edges (clj->js
                  (mapv (fn [e]
                          {:id (:id e)
                           :sources [(:source e)]
                           :targets [(:target e)]
                           :labels [{:text (:event-label e)}]})
                        edges))}))

(defn- elk-result->positions
  "Adapter: elk.js JS result → `{node-id {:x :y :width :height}}` map.
  Used by `xyflow-graph` to merge xyflow-side node objects with
  elk-laid-out positions."
  [elk-result]
  (let [children (or (.-children elk-result) #js [])
        n        (alength children)]
    (loop [i 0
           acc {}]
      (if (< i n)
        (let [c (aget children i)]
          (recur (inc i)
                 (assoc acc (.-id c)
                        {:x      (or (.-x c) 0)
                         :y      (or (.-y c) 0)
                         :width  (or (.-width c) nodes/state-node-min-width)
                         :height (or (.-height c) nodes/state-node-min-height)})))
        acc))))

(defn compute-layout!
  "Run elk.js layout on `parsed` (the output of
  `chart.layout/parse-definition`); call `done-fn` with a map of
  `{node-id {:x :y :width :height}}` when ready (or with `nil` on
  failure). The async path is idiomatic xyflow + elkjs:

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

(defn- choose-edge-type
  [edge]
  (cond
    (:after edge)  "after"
    :else          "transition"))

(defn xyflow-graph
  "Project the parsed graph + a `{node-id position}` map into the
  xyflow `:nodes` + `:edges` arrays. Pure fn (no DOM, no React).

  Options:

    :highlight-id        — node-id of the active state.
    :from-highlight-id   — node-id of the focused-event lens's
                           origin state.
    :to-highlight-id     — node-id of the focused-event lens's
                           landing state.
    :sim?                — flips the highlight palette to amber.
    :on-state-click      — `(fn [path] ...)` invoked when a state
                           node is clicked."
  [{:keys [nodes edges]}
   positions
   {:keys [highlight-id from-highlight-id to-highlight-id sim? on-state-click]}]
  {:nodes
   (mapv (fn [n]
           (let [pos (get positions (:id n) {:x 0 :y 0})
                 active? (= (:id n) highlight-id)
                 from-hi? (= (:id n) from-highlight-id)
                 to-hi?   (= (:id n) to-highlight-id)]
             {:id       (:id n)
              :type     (if (:compound? n) "compound" "state")
              :position {:x (:x pos) :y (:y pos)}
              :data     {:label          (:label n)
                         :path           (:path n)
                         :active         active?
                         :fromHighlight  from-hi?
                         :toHighlight    to-hi?
                         :sim            (boolean (and active? sim?))
                         :final          (boolean (:final? n))
                         :compound       (boolean (:compound? n))
                         :tags           (vec (:tags n))
                         :onClick        on-state-click}
              :draggable false
              :selectable false}))
         nodes)
   :edges
   (mapv (fn [e]
           (let [from-active? (or (= (:source e) highlight-id)
                                  (= (:target e) highlight-id))
                 focused?     (and (some? from-highlight-id)
                                   (some? to-highlight-id)
                                   (= (:source e) from-highlight-id)
                                   (= (:target e) to-highlight-id))]
             {:id     (:id e)
              :source (:source e)
              :target (:target e)
              :type   (choose-edge-type e)
              :data   {:eventLabel (:event-label e)
                       :active     from-active?
                       :focused    focused?
                       :afterMs    (:after e)
                       :guard      (some-> (:guard e) name)
                       :action     (some-> (:action e) name)}}))
         edges)})

;; ---- inline keyframes ---------------------------------------------------

(def ^:private chart-stylesheet
  "Inline stylesheet carrying the transition-glow keyframes + the
  prefers-reduced-motion override. Mirrors the previous SVG render's
  `transition-glow-css` so the focused-edge animation continues to
  work post-migration."
  (str
    ":root { --rf-causa-motion-scale: 1; }\n"
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-causa-motion-scale: 0.001; }\n"
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

  Args (map — `:closed` schema enforced at registration time per
  `tools/machines-viz/spec/API.md` §Props):

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
    :sim?              — flips the highlight palette to amber for
                         the simulator path.
    :on-state-click    — `(fn [path] ...)` invoked on node click.
    :read-only?        — when true all `:on-*` callbacks are no-op'd.
                         The viewer page sets this.
    :direction         — `:tb` (top-to-bottom, default) or `:lr`.
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
    :testid            — root wrapper `data-testid`; defaults to
                         `\"rf-mv-chart\"` so tests + hosts find it."
  [_initial-props]
  (let [positions     (r/atom {})
        layout-key    (r/atom nil)]
    (fn [{:keys [machine-id definition current-state from-highlight to-highlight
                 sim? on-state-click read-only?
                 direction layout-options
                 height show-minimap? show-controls? show-background?
                 testid]
          :or   {direction         :tb
                 height            "100%"
                 show-minimap?     false
                 show-controls?    true
                 show-background?  true
                 testid            "rf-mv-chart"}}]
      (let [parsed     (layout/parse-definition definition)
            n-states   (count (:nodes parsed))
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
                           (fn [poss]
                             (when poss
                               (reset! positions poss)))))
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
          (let [highlight-id      (layout/highlight-id current-state)
                from-highlight-id (layout/highlight-id from-highlight)
                to-highlight-id   (layout/highlight-id to-highlight)
                callback          (when-not read-only? on-state-click)
                {:keys [nodes edges]}
                (xyflow-graph parsed
                              @positions
                              {:highlight-id      highlight-id
                               :from-highlight-id from-highlight-id
                               :to-highlight-id   to-highlight-id
                               :sim?              sim?
                               :on-state-click    callback})
                aria-label (str "State machine"
                                (when machine-id
                                  (str ": " (name machine-id)))
                                " with " n-states " "
                                (if (= 1 n-states) "state" "states")
                                " and " n-trans " "
                                (if (= 1 n-trans) "transition" "transitions") ".")]
            [:div {:data-testid testid
                   :data-machine-id (str machine-id)
                   :data-node-count (str n-states)
                   :data-edge-count (str n-trans)
                   :data-highlight-id (or highlight-id "")
                   :data-from-highlight-id (or from-highlight-id "")
                   :data-to-highlight-id (or to-highlight-id "")
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
                [:> Background {:variant (.-Dots BackgroundVariant)
                                :gap 16
                                :size 1
                                :color (tokens/with-alpha :accent-violet 0.4)}])
              (when show-controls?
                [:> Controls {:showZoom true
                              :showFitView true
                              :showInteractive false}])
              (when show-minimap?
                [:> MiniMap {:zoomable true
                             :pannable true
                             :nodeColor (fn [_] (:bg-3 tokens/tokens))
                             :maskColor (tokens/with-alpha :bg-0 0.6)}])]]))))))

;; ---- empty-graph convenience for callers --------------------------------

(defn render-from-definition
  "Legacy entry-point — renders the chart from a definition map. Kept
  for callers still using the pre-migration `render-from-definition`
  surface; prefer the `MachineChart` component directly."
  ([definition] (render-from-definition definition {}))
  ([definition opts]
   [MachineChart (assoc opts :definition definition)]))
