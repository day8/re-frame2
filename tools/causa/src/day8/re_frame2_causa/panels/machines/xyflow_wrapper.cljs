(ns day8.re-frame2-causa.panels.machines.xyflow-wrapper
  "Reagent/React interop wrapper around `@xyflow/react`'s `ReactFlow`
  component (rf2-uwvyj · spec/021 §6.0 + §17.4 · 'Path B' locked).

  ## What this owns

  A thin shim — adapt-react-class around the npm-loaded `ReactFlow`
  React component — so the rest of Causa can render the xyflow canvas
  via pure Reagent hiccup:

      [xyflow-canvas
       {:nodes   [...]      ; vector of node maps (kw or string keys)
        :edges   [...]      ; vector of edge maps
        :style   {...}      ; outer wrapper :style map (passed through)
        :testid  \"rf-causa-machine-xyflow\"}]

  The wrapper is intentionally tiny: shape conversion (clj → JS) and
  the `adapt-react-class` call. The Machines-panel topology projector
  (`topology.cljs`) builds the `:nodes` + `:edges` vectors; this ns
  only worries about handing them to React in the shape xyflow
  expects.

  ## Bundle isolation

  `@xyflow/react` is a `devDependency` of `implementation/package.json`
  — it is NEVER pulled into a production bundle. The
  `check-bundle-isolation.cjs` sentinel (rf2-uwvyj) asserts that none
  of the xyflow internal-symbol strings appear in `examples/counter`,
  `examples/counter-uix`, or `examples/counter-helix` release blobs.
  Causa is dev-only (gated by `:devtools/preloads` in shadow-cljs); a
  consumer that doesn't install Causa never pays for xyflow.

  ## Why a wrapper ns rather than inline interop

  Three reasons (rf2-uwvyj acceptance):

    1. **One throat for the React-class adapt.** xyflow is consumed in
       multiple Causa surfaces (Machines panel Inspector, Machines
       Canvas tab); a single shim lets future spec changes (xyflow
       version bump, `<Background>` / `<Controls>` swap-in) land in
       one file.
    2. **CLJS-data → JS-prop boundary.** xyflow expects plain JS
       arrays/objects for `nodes` / `edges`; converting at the wrapper
       boundary keeps the projection layer (`topology.cljs`) pure
       CLJS-data → CLJS-data — testable without DOM / React harness.
    3. **Read-only render contract.** Per spec/021 §6.0 the integration
       is read-only: drag-to-create-edge, multi-select, etc. are
       disabled here at the wrapper level so the rest of Causa cannot
       accidentally enable them.

  ## Read-only render defaults

  Per spec/021 §6.0 + §17.4.4 the wrapper applies these defaults:

    - `nodes-draggable=false` · `nodes-connectable=false` ·
      `elements-selectable=false` · `pan-on-drag=true` ·
      `zoom-on-scroll=true` · `min-zoom=0.25` · `max-zoom=2`
    - `fitView=true` · `fit-view-options={padding: 0.05}`"
  (:require [reagent.core :as reagent]
            ["@xyflow/react" :as xyflow]))

;; ---- CSS note -----------------------------------------------------------

;; xyflow ships a stylesheet at `@xyflow/react/dist/style.css` that
;; provides the base node / edge / controls chrome. We deliberately do
;; NOT import it via a `(:require ["@xyflow/react/dist/style.css"])`
;; clause — shadow-cljs's npm-module resolver does not load `.css`
;; via the require pipeline. Instead the Causa global stylesheet
;; (`theme/global_styles`) inlines the minimum xyflow chrome rules
;; we need (zoom/pan transform on `.react-flow__pane`; the
;; `Controls` toolbar background); per-node + per-edge visual styling
;; is applied via the `:style` props on the node/edge maps Causa
;; emits (see `xyflow-style.cljs`).

;; ---- React-class adaptation ---------------------------------------------

(def ReactFlow
  "Reagent-adapted xyflow `ReactFlow` React component. Use via the
  `xyflow-canvas` Reagent fn below — direct use is fine when callers
  want full prop control."
  (reagent/adapt-react-class (.-ReactFlow xyflow)))

(def Controls
  "Reagent-adapted xyflow `Controls` React component. Renders the
  zoom-in / zoom-out / fit-to-view chrome inside the ReactFlow
  container. Re-styled via CSS variables — see
  `theme/global_styles motion-css` for the Causa overrides."
  (reagent/adapt-react-class (.-Controls xyflow)))

(def Background
  "Reagent-adapted xyflow `Background` React component. Optional dot
  pattern in the canvas background — Causa enables only when the
  caller sets `:show-background?` true."
  (reagent/adapt-react-class (.-Background xyflow)))

;; ---- clj → JS coercion --------------------------------------------------

(defn- ->js
  "Convert a CLJS map / vector to a fresh plain-JS object suitable for
  handing to xyflow as a `node` / `edge` prop. Mirrors `clj->js` but
  is documented at the boundary so future migrations (e.g. Helix-style
  hash-arg conversion) land in one place."
  [x]
  (clj->js x))

(defn coerce-nodes
  "Coerce a vector of CLJS node maps into the JS array xyflow expects.
  Exposed for direct callers; `xyflow-canvas` invokes it internally."
  [nodes]
  (->js (or nodes [])))

(defn coerce-edges
  "Coerce a vector of CLJS edge maps into the JS array xyflow expects."
  [edges]
  (->js (or edges [])))

;; ---- public Reagent component -------------------------------------------

(defn xyflow-canvas
  "Reagent-friendly wrapper around xyflow's `ReactFlow` component.

  Args (map):

    :nodes               — vector of CLJS node maps. xyflow requires
                           each node to have `:id`, `:position`
                           `{:x :y}`, and `:data` `{:label ...}` at
                           minimum. `:style` / `:className` /
                           `:type` flow through unchanged.
    :edges               — vector of CLJS edge maps. xyflow requires
                           each edge to have `:id`, `:source`, and
                           `:target` at minimum. `:label` / `:animated`
                           / `:style` flow through.
    :style               — outer wrapper `:style` map. Default
                           `{:width \"100%\" :height \"100%\"}` so the
                           canvas fills its parent — callers usually
                           wrap in a flex container with an explicit
                           height.
    :show-controls?      — when true (default) render xyflow's built-in
                           zoom/pan/fit Controls component. Per
                           spec/021 §6.0 + §17.4.4 the `Fit` button
                           re-runs `fitView`.
    :show-background?    — when true (default false) render xyflow's
                           Background dot pattern. Causa leaves false
                           — the panel chrome already provides the
                           dark surface; dots would add visual noise.
    :fit-view?           — when true (default) `fitView` on mount with
                           a small padding ratio. Per spec/021
                           §17.4.4.
    :min-zoom / :max-zoom — clamps (default 0.25 / 2 per
                           spec/021 §17.4.4).
    :testid              — outer wrapper `data-testid`. Defaults to
                           `rf-causa-machine-xyflow`.

  Returns hiccup. Caller is responsible for the surrounding flex /
  height context — xyflow needs a non-zero parent height to render."
  [{:keys [nodes edges style show-controls? show-background?
           fit-view? min-zoom max-zoom testid]
    :or   {show-controls?   true
           show-background? false
           fit-view?        true
           min-zoom         0.25
           max-zoom         2.0
           testid           "rf-causa-machine-xyflow"}}]
  (let [wrapper-style (merge {:width "100%" :height "100%"} style)
        flow-props    {:nodes               (coerce-nodes nodes)
                       :edges               (coerce-edges edges)
                       :nodesDraggable      false
                       :nodesConnectable    false
                       :elementsSelectable  false
                       :panOnDrag           true
                       :zoomOnScroll        true
                       :fitView             fit-view?
                       :fitViewOptions      #js {:padding 0.05}
                       :minZoom             min-zoom
                       :maxZoom             max-zoom
                       :proOptions          #js {:hideAttribution true}}]
    [:div {:data-testid testid
           :data-node-count (str (count nodes))
           :data-edge-count (str (count edges))
           :style wrapper-style}
     (into [ReactFlow flow-props]
           (cond-> []
             show-background? (conj [Background])
             show-controls?   (conj [Controls
                                     {:showZoom true
                                      :showFitView true
                                      :showInteractive false}])))]))
