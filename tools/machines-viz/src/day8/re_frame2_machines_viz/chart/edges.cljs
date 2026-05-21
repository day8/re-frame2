(ns day8.re-frame2-machines-viz.chart.edges
  "Custom xyflow edge components for the MachineChart.

  rf2-gpzb4 (2026-05-21 xyflow override) — edge components render the
  xstate-style label (`event [guard] / action`) with a small backplate
  for legibility against overlapping edges + the dot-grid background.
  Active-state and focused-event lens highlighting are reflected in
  stroke colour + width via the edge's `:data` payload.

  ## Edge kinds (per the bead's scope §Custom edges)

    - `transition-edge` — the canonical edge. Renders a curved
      path between source/target with the event label sitting at
      the midpoint behind a small backplate rect.
    - `after-edge` — `:after`-timer edge. Same path as transition-
      edge but renders the label as `after(<ms>)` and exposes a
      `:data-after-ms` attr so a host-side overlay can find the
      ring's bearing node.
    - `spawn-edge` — invocation edge to spawned children. Renders
      with a dashed stroke + a tiny ⤳ glyph on the label so a
      reader at-a-glance distinguishes 'invocation' from
      'transition'. (Reserved — Phase 1 surfaces the path; full
      spawn-all join inspector is a follow-on bead per the scope.)

  ## Substrate posture

  Reagent-only Phase 1; UIx + Helix follow-on. xyflow consumes
  these components via the `edgeTypes` prop.

  ## Implementation notes

  xyflow's `getBezierPath` / `getSmoothStepPath` helpers return a
  pre-computed SVG path string + a label-x / label-y position pair.
  We use `getBezierPath` for the polished curve aesthetic that
  competes with Stately Studio."
  (:require ["@xyflow/react" :as xyflow]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [mono-stack]]))

(def ^:private get-bezier-path (.-getBezierPath xyflow))
(def ^:private BaseEdge        (.-BaseEdge xyflow))
(def ^:private EdgeLabelRenderer (.-EdgeLabelRenderer xyflow))

;; ---- helpers ------------------------------------------------------------

(defn- edge-stroke
  [{:keys [active? focused?]}]
  (cond
    focused? (:cyan tokens/tokens)
    active?  (:cyan tokens/tokens)
    :else    (:border-default tokens/tokens)))

(defn- edge-stroke-width
  [{:keys [active? focused?]}]
  (cond
    focused? 2.5
    active?  2.0
    :else    1.5))

;; ---- transition-edge ----------------------------------------------------

(defn transition-edge
  "Reagent component for a transition edge. xyflow invokes this with
  source/target coords + a `:data` payload carrying the event label
  + state flags. Returns a `<BaseEdge>` + a label renderer in a
  React fragment."
  [^js props]
  (let [src-x      (.-sourceX props)
        src-y      (.-sourceY props)
        tgt-x      (.-targetX props)
        tgt-y      (.-targetY props)
        src-pos    (.-sourcePosition props)
        tgt-pos    (.-targetPosition props)
        marker-end (.-markerEnd props)
        d          (.-data props)
        label      (or (.-eventLabel d) "")
        active?    (boolean (.-active d))
        focused?   (boolean (.-focused d))
        after-ms   (.-afterMs d)
        ;; xyflow's getBezierPath returns [path-string label-x label-y
        ;; offset-x offset-y]. Use a JS-side destructure via aget.
        bz (get-bezier-path
             #js {:sourceX        src-x
                  :sourceY        src-y
                  :sourcePosition src-pos
                  :targetX        tgt-x
                  :targetY        tgt-y
                  :targetPosition tgt-pos})
        path  (aget bz 0)
        label-x (aget bz 1)
        label-y (aget bz 2)
        stroke  (edge-stroke {:active? active? :focused? focused?})
        stroke-w (edge-stroke-width {:active? active? :focused? focused?})]
    (r/as-element
      [:<>
       [:> BaseEdge {:id (.-id props)
                     :path path
                     :markerEnd marker-end
                     :style #js {:stroke stroke
                                 :strokeWidth stroke-w
                                 :animation (when focused?
                                              "mv-chart-transition-glow 720ms ease-out infinite")}}]
       [:> EdgeLabelRenderer
        [:div {:data-testid (str "rf-mv-chart-edge-" (.-id props))
               :data-event (when label label)
               :data-active (str active?)
               :data-focused-edge (str focused?)
               :data-after-ms (when after-ms (str after-ms))
               :style {:position       "absolute"
                       :transform      (str "translate(-50%, -50%) translate("
                                            label-x "px," label-y "px)")
                       :pointer-events "auto"
                       :font-family    mono-stack
                       :font-size      "11px"
                       :font-weight    400
                       :color          (:bg-0 tokens/tokens)
                       :background     (tokens/with-alpha :white 0.85)
                       :padding        "1px 5px"
                       :border-radius  "3px"
                       :border         (str "1px solid " (tokens/with-alpha :border-subtle 0.4))
                       :white-space    "nowrap"
                       :user-select    "none"}}
         label]]])))

;; ---- after-edge ---------------------------------------------------------

;; rf2-gpzb4 — :after-timer edges share the transition-edge body but
;; carry the after-ms duration as a data-attribute so a host-side
;; overlay (Causa's machine_after_rings ns) can find the bearing
;; node and paint a countdown ring on top. The default styling is
;; identical to transition-edge; future polish (a small countdown-
;; ring glyph rendered inline on the label) can land in a follow-on
;; bead.

(def after-edge
  "Alias for `transition-edge` — `:after`-edge specifics ride on the
  `:data {:after-ms}` attr; the rendering shape is the same."
  transition-edge)

;; ---- spawn-edge ---------------------------------------------------------

(defn spawn-edge
  "Reagent component for a spawn / spawn-all invocation edge. Renders
  with a dashed stroke and a tiny ⤳ glyph on the label so a reader
  at-a-glance distinguishes 'invocation' from 'transition'.

  Phase 1 — the visual is in place; the full `:spawn-all` parallel-
  child viz + join inspector is a follow-on bead per the bead's
  scope §Overlays."
  [^js props]
  (let [src-x      (.-sourceX props)
        src-y      (.-sourceY props)
        tgt-x      (.-targetX props)
        tgt-y      (.-targetY props)
        src-pos    (.-sourcePosition props)
        tgt-pos    (.-targetPosition props)
        marker-end (.-markerEnd props)
        d          (.-data props)
        label      (or (.-eventLabel d) "")
        bz (get-bezier-path
             #js {:sourceX        src-x
                  :sourceY        src-y
                  :sourcePosition src-pos
                  :targetX        tgt-x
                  :targetY        tgt-y
                  :targetPosition tgt-pos})
        path  (aget bz 0)
        label-x (aget bz 1)
        label-y (aget bz 2)]
    (r/as-element
      [:<>
       [:> BaseEdge {:id (.-id props)
                     :path path
                     :markerEnd marker-end
                     :style #js {:stroke (:accent-violet tokens/tokens)
                                 :strokeWidth 1.5
                                 :strokeDasharray "5 3"}}]
       [:> EdgeLabelRenderer
        [:div {:data-testid (str "rf-mv-chart-spawn-edge-" (.-id props))
               :style {:position       "absolute"
                       :transform      (str "translate(-50%, -50%) translate("
                                            label-x "px," label-y "px)")
                       :pointer-events "auto"
                       :font-family    mono-stack
                       :font-size      "11px"
                       :color          (:accent-violet tokens/tokens)
                       :background     (tokens/with-alpha :white 0.85)
                       :padding        "1px 5px"
                       :border-radius  "3px"
                       :white-space    "nowrap"
                       :user-select    "none"}}
         "⤳ " label]]])))

;; ---- edge-types map -----------------------------------------------------

(defn edge-types
  "The `edgeTypes` prop value for `<ReactFlow>`. Returns a fresh
  plain-JS object on every call; callers SHOULD memoise this map at
  component-construction time to avoid re-render churn."
  []
  #js {"transition" transition-edge
       "after"      after-edge
       "spawn"      spawn-edge})
