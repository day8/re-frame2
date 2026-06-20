(ns day8.re-frame2-machines-viz.chart.nodes.parallel-region-node
  "xyflow node component for a parallel-region (orthogonal-zone)
  container.

  ## Why this exists

  A `{:type :parallel :regions {...}}` machine renders every region as
  a distinct orthogonal zone with its own dashed boundary (Stately
  Studio parity — Stately paints parallel regions as side-by-side panes
  separated by a dashed divider).

  ## How it works with xyflow

  `chart.layout/project-parallel` mints a synthetic `:region?` compound
  node per region; `chart.projection/xyflow-graph` projects it as a
  `type: \"parallel-region\"` xyflow node and assigns every state in
  the region a `parentId` pointing at the region node (xyflow's
  sub-flow mechanic — xyflow v12 reads `parentId`, NOT the pre-v12
  `parentNode`). This component renders the region's CHROME —
  a large translucent box with a dashed border + the region label in
  the header strip. The child state nodes sit inside via xyflow's
  `parentId` positioning; this component only paints the surround.

  ## Region identity comes from layout, not colour

  Regions are distinguished by CONTAINMENT + LAYOUT (side-by-side dashed
  zones), NOT a rotating border colour: static colour rotation is not the
  topology signal — structure wins. Every region's boundary is the SAME
  neutral dashed treatment; the dashed style itself (vs the compound
  container's solid neutral) is what marks a zone as a parallel region.

  ## Token integration

  All colours resolve through the active-theme chart-tokens (`palette-of`
  off `:data`); no hex literals. The dashed boundary + header use the
  neutral region tokens; active / focus colour is reserved for RUNTIME
  state (`:active` accent + glow), so an active region reads live without
  the static identity competing for the accent.

  ## Border handles

  Invisible source + target `<Handle>` elements sit on all four
  sides so xstate/Stately-style edges incident on the region
  CONTAINER (parent-level inherited transitions, region-level fanouts)
  render through xyflow normally. Without them xyflow's
  `getHandleBounds` returns null, `isNodeInitialized` returns false,
  and `getEdgePosition` returns null — every edge whose endpoint is a
  region container is SILENTLY DROPPED from the DOM. The compound-node
  uses the same mechanic; the region-node mirrors it for consistency."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.xyflow-node
             :refer [four-cardinal-handles chart-constants palette-of]]
            [day8.re-frame2-machines-viz.theme.tokens
             :refer [sans-stack mono-stack]]))

;; ---- parallel-region node ----------------------------------------------

(defn parallel-region-node
  "Reagent component for a parallel-region container. xyflow
  invokes this via `nodeTypes={:parallel-region parallel-region-node}`.
  Reads `:data {:label :regionIndex :regionId ...}` off the xyflow props.

  Grammar (the dashed/accent treatment is reserved for regions — solid
  neutral is the compound container):

    - DASHED NEUTRAL rounded boundary. Region identity comes from
      CONTAINMENT + LAYOUT, NOT a rotating border colour — static colour
      rotation is not the topology signal.
    - A full-width REGION TITLE STRIP carrying the uppercased label, with
      a subtle `∥` parallel glyph (small, never dominating).
    - Active / focus colour is reserved for RUNTIME state: an active
      region (a descendant leaf is active, folded into `:active` via the
      `:parent-id` chain) firms its dashed boundary to a solid runtime-
      accent border + glow ring. Inactive regions read fully neutral."
  [^js props]
  (let [d            (.-data props)
        vc           (chart-constants d)
        ct           (palette-of d)
        label        (or (.-label d) "")
        region-index (.-regionIndex d)
        active?      (boolean (.-active d))
        {:keys [compound-radius region-title-height region-title-pad-x
                container-title-px container-divider-width
                stroke-width stroke-width-emphasis]} vc
        ;; NEUTRAL boundary by default (no rotation colour).
        ;; Active swaps the dashed neutral to a solid runtime accent.
        border-color (if active? (:active ct) (:region-border ct))
        border-style (if active? "solid" "dashed")
        border-w     (if active? stroke-width-emphasis stroke-width)]
    (r/as-element
      [:div {:data-testid    (str "rf-mv-chart-region-" (.-id props))
             :data-node-id    (.-id props)
             :data-region-id  (when-let [rid (.-regionId d)] (str rid))
             :data-region-index (when (some? region-index) (str region-index))
             :data-active     (str active?)
             :style {:position       "relative"
                     :width          "100%"
                     :height         "100%"
                     :background     (:container-body-bg ct)
                     ;; Dashed NEUTRAL orthogonal-zone delineation; solid
                     ;; runtime-accent when active.
                     :border         (str border-w "px " border-style " " border-color)
                     :border-radius  (str compound-radius "px")
                     :box-shadow     (when active?
                                       (str "0 0 0 2px " (:glow ct)))
                     :pointer-events "none"}}
       ;; Full-width REGION TITLE STRIP — neutral, with a subtle ∥ glyph.
       [:div {:data-testid (str "rf-mv-chart-regionhdr-" (.-id props))
              :style {:position       "absolute"
                      :top            0
                      :left           0
                      :right          0
                      :height         (str region-title-height "px")
                      :display        "flex"
                      :align-items    "center"
                      :padding        (str "0 " region-title-pad-x "px")
                      :background     (if active?
                                        (:active-wash ct)
                                        (:region-header-bg ct))
                      :border-bottom  (str container-divider-width "px "
                                           border-style " " border-color)
                      :border-top-left-radius  (str compound-radius "px")
                      :border-top-right-radius (str compound-radius "px")
                      :font-family    sans-stack
                      :font-size      (str container-title-px "px")
                      :font-weight    700
                      :letter-spacing "0.04em"
                      :text-transform "uppercase"
                      :color          (if active?
                                        (:text-primary ct)
                                        (:text-secondary ct))}}
        ;; Subtle orthogonal-region glyph — small, never dominating.
        [:span {:style {:margin-right "6px"
                        :opacity 0.6
                        :font-family mono-stack}}
         "∥"]
        label]
       ;; Invisible xyflow attachment points so an edge whose endpoint
       ;; is a region container has a handle to anchor to. Without them
       ;; xyflow silently drops the edge (same mechanic as the
       ;; compound-node).
       (four-cardinal-handles)])))
