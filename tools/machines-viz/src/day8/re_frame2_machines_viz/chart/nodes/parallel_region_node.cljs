(ns day8.re-frame2-machines-viz.chart.nodes.parallel-region-node
  "xyflow node component for a parallel-region (orthogonal-zone)
  container (rf2-lkwev · xyflow Phase 2).

  ## Why this exists

  xyflow Phase 1 (#1806) projected only the FIRST region of a
  `{:type :parallel :regions {...}}` machine — full parallel layout
  was deferred. This component completes the deferral: every region
  renders as a distinct orthogonal zone with its own dashed boundary
  (Stately Studio parity — Stately paints parallel regions as
  side-by-side panes separated by a dashed divider).

  ## How it works with xyflow

  `chart.layout/parse-parallel` mints a synthetic `:region?` compound
  node per region; `chart.chart/xyflow-graph` projects it as a
  `type: \"parallel-region\"` xyflow node and assigns every state in
  the region a `parentNode` pointing at the region node (xyflow's
  sub-flow mechanic). This component renders the region's CHROME — a
  large translucent box with a dashed border + the region label in
  the header strip. The child state nodes sit inside via xyflow's
  parentNode positioning; this component only paints the surround.

  ## Distinct boundary per region

  Each region gets a deterministic accent colour from
  `region-boundary-color` (a rotation over the token palette) so two
  adjacent regions read as visually distinct zones — the dashed
  border + the header label both pick up the region's colour. This is
  the Stately-parity affordance: a reader sees N orthogonal regions
  at a glance, each clearly delineated.

  ## Token integration

  All colours read from `theme/tokens`; no hex literals. The dashed
  boundary uses the region's rotation colour; the body uses a faint
  tint of the same so the zone reads as a unit."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [sans-stack]]))

;; ---- region boundary palette --------------------------------------------

(def region-boundary-palette
  "Deterministic colour rotation for parallel-region boundaries
  (rf2-lkwev). Distinct from the tag-pill palette: regions are
  structural zones (not per-state badges), so the rotation leads with
  `:accent-violet` (the structural-container colour the compound-node
  chrome already uses) and spreads across cool/warm so adjacent
  regions read as distinct orthogonal zones."
  [:accent-violet :cyan :orange :green :magenta :yellow])

(defn region-boundary-color-key
  "Map a region's index to a stable token key from
  `region-boundary-palette`. Deterministic — region 0 is always the
  violet structural colour, region 1 the next, wrapping past the end.
  Pure data → keyword."
  [region-index]
  (nth region-boundary-palette
       (mod (or region-index 0) (count region-boundary-palette))))

;; ---- parallel-region node ----------------------------------------------

(defn parallel-region-node
  "Reagent component for a parallel-region container. xyflow invokes
  this via `nodeTypes={:parallel-region parallel-region-node}`. Reads
  `:data {:label :regionIndex :regionId ...}` off the xyflow props.

  Renders a translucent zone with a distinct dashed boundary (the
  region's rotation colour) + a header strip carrying the region
  label. xyflow's parentNode mechanic places the region's child state
  nodes inside; this component renders only the surrounding chrome."
  [^js props]
  (let [d            (.-data props)
        label        (or (.-label d) "")
        region-index (.-regionIndex d)
        color-key    (region-boundary-color-key region-index)
        border-color (get tokens/tokens color-key)
        body-tint    (tokens/with-alpha color-key 0.05)
        header-tint  (tokens/with-alpha color-key 0.12)]
    (r/as-element
      [:div {:data-testid    (str "rf-mv-chart-region-" (.-id props))
             :data-node-id    (.-id props)
             :data-region-id  (when-let [rid (.-regionId d)] (str rid))
             :data-region-index (when (some? region-index) (str region-index))
             :style {:position       "relative"
                     :width          "100%"
                     :height         "100%"
                     :padding-top    "26px"
                     :background     body-tint
                     ;; Distinct DASHED boundary per region — the
                     ;; Stately-parity orthogonal-zone delineation.
                     :border         (str "1.5px dashed " border-color)
                     :border-radius  "12px"
                     :pointer-events "none"}}
       ;; Header strip — the region label, tinted to the region colour.
       [:div {:data-testid (str "rf-mv-chart-regionhdr-" (.-id props))
              :style {:position       "absolute"
                      :top            0
                      :left           0
                      :right          0
                      :height         "22px"
                      :display        "flex"
                      :align-items    "center"
                      :padding        "0 10px"
                      :background     header-tint
                      :border-bottom  (str "1px dashed " border-color)
                      :border-top-left-radius  "11px"
                      :border-top-right-radius "11px"
                      :font-family    sans-stack
                      :font-size      "11px"
                      :font-weight    700
                      :letter-spacing "0.04em"
                      :text-transform "uppercase"
                      :color          border-color}}
        ;; A small orthogonal-region glyph (parallel bars) precedes the
        ;; label so the zone reads as "parallel region" at a glance.
        [:span {:style {:margin-right "6px"
                        :opacity 0.85
                        :font-family tokens/mono-stack}}
         "∥"]
        label]])))
