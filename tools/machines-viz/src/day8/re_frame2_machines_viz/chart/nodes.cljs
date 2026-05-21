(ns day8.re-frame2-machines-viz.chart.nodes
  "Custom xyflow node components for the MachineChart.

  rf2-gpzb4 (2026-05-21 xyflow override) — these components recover
  the Causa visual identity (rounded-rect node body, state-tag pills,
  final-state double border, active-state cyan tint + emphasised
  stroke) on top of xyflow's React node-rendering pipeline.

  Each component is a plain JS-compatible function that destructures
  the xyflow `:data` prop and renders Reagent hiccup wrapped in
  `r/as-element` — xyflow calls these via React's createElement so
  the return value MUST be a React element. xyflow's `Handle`
  components sit invisibly on the node sides via the `:>` React-
  interop syntax so edges attach consistently.

  ## Node kinds (per the bead's scope §Custom nodes)

    - `state-node` — the canonical state node. Reads `:data {:label
      :path :active? :final? :tags :on-click ...}` and renders
      accordingly.
    - `compound-node` — compound parent with a header strip and a
      large body containing nested children (xyflow's `parentNode`
      mechanic handles the hierarchical layout; this node renders
      the outer container chrome).
    - `initial-marker` + `final-marker` — small glyph nodes paired
      with state nodes to mark the machine's initial / final
      transitions.

  ## Token integration

  All colours read from `theme/tokens` so a future palette swap
  flows through unchanged. Per the rf2-on4cm `var(--*)` landing the
  tokens themselves resolve through CSS custom properties; no hex
  literals appear in this ns.

  ## Substrate posture (Phase 1, per bead)

  Reagent-only. UIx and Helix adapters are follow-on beads — xyflow
  is a React lib so the underlying React-class boundary is fine for
  every substrate; only the Reagent `as-element` glue needs a
  substrate-specific shim."
  (:require ["@xyflow/react" :as xyflow]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.parallel-region-node
             :as parallel-region-node]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [mono-stack sans-stack]]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- density-resolved constants -----------------------------------------
;;
;; rf2-k647w — the renderer no longer hardcodes geometry/typography. The
;; projector threads the resolved density's `visual-constants` map onto
;; every node payload as `:data {:chart {...}}`; xyflow `clj->js`-es the
;; node array, so the map arrives as a JS object. `chart-constants`
;; recovers a kebab-keyword CLJS map (the keys `visual-constants` ships),
;; falling back to `vc/chart-regular` so a node payload without a `:chart`
;; entry (legacy / direct construction) still renders the regular default.

(defn- chart-constants
  "Recover the resolved visual-constants map off a node's `:data`
  (`(.-chart d)`). The projector emits a CLJS map; xyflow `clj->js`-es
  it into a JS object, so we `js->clj` it back with keyword keys.
  Returns `vc/chart-regular` when absent so the regular density stays
  pixel-identical to the pre-rf2-k647w hardcoded numbers."
  [^js d]
  (let [c (.-chart d)]
    (if (some? c)
      (js->clj c :keywordize-keys true)
      vc/chart-regular)))

;; ---- xyflow Handle adapter ----------------------------------------------

(def ^:private Handle
  "xyflow `Handle` React class. Used inside every custom node via
  Reagent's `:>` interop so xyflow knows where to attach edges."
  (.-Handle xyflow))

(def ^:private Position
  "xyflow position constants (`Top`, `Right`, `Bottom`, `Left`)."
  (.-Position xyflow))

(def ^:private pos-top    (.-Top Position))
(def ^:private pos-right  (.-Right Position))
(def ^:private pos-bottom (.-Bottom Position))
(def ^:private pos-left   (.-Left Position))

;; ---- node-size floor constants ------------------------------------------
;;
;; rf2-kra7h — single-sourced from `chart.projection`. The elk projection
;; and these CSS `min-width` / `min-height` floors MUST agree (the
;; laid-out slot vs. the measured box), so they live in one canonical,
;; JVM-readable home (`projection`, the pure layer the projection tests
;; pin) and the renderer reads them via `projection/<name>`.

;; ---- helpers ------------------------------------------------------------

(defn- node-fill
  [{:keys [active? from-highlight? to-highlight? sim?]}]
  (cond
    sim?            (tokens/with-alpha :yellow         0.18)
    to-highlight?   (tokens/with-alpha :cyan           0.22)
    from-highlight? (tokens/with-alpha :accent-violet  0.14)
    active?         (tokens/with-alpha :cyan           0.18)
    :else           (:bg-2 tokens/tokens)))

(defn- node-stroke
  [{:keys [active? from-highlight? to-highlight? sim? final?]}]
  (cond
    sim?            (:yellow tokens/tokens)
    to-highlight?   (:cyan tokens/tokens)
    from-highlight? (:accent-violet tokens/tokens)
    active?         (:cyan tokens/tokens)
    final?          (:green tokens/tokens)
    :else           (:border-default tokens/tokens)))

(defn- tag-pill
  "Render a single state-tag pill. Hiccup. rf2-k647w — geometry +
  typography read off the resolved density `vc` map (height / pad-x /
  px / radius / gap) instead of hardcoded literals."
  [tag {:keys [tag-pill-height tag-pill-pad-x tag-pill-px
               tag-pill-radius tag-pill-gap]}]
  (let [label   (if (keyword? tag) (name tag) (str tag))
        token-k (tokens/tag-pill-color tag)
        fill    (tokens/with-alpha token-k 0.18)
        stroke  (get tokens/tokens token-k)]
    [:span {:key   label
            :title (str tag)
            :data-testid (str "rf-mv-chart-state-tag-" label)
            :data-tag    label
            :style {:display          "inline-flex"
                    :align-items      "center"
                    :height           (str tag-pill-height "px")
                    :padding          (str "0 " tag-pill-pad-x "px")
                    :margin-right     (str tag-pill-gap "px")
                    :background       fill
                    :border           (str "1px solid " stroke)
                    :border-radius    (str tag-pill-radius "px")
                    :font-family      sans-stack
                    :font-size        (str tag-pill-px "px")
                    :font-weight      600
                    :color            stroke
                    :line-height      "1"
                    :white-space      "nowrap"}}
     label]))

;; ---- state node ---------------------------------------------------------

(defn state-node
  "Reagent component for a standard state node. xyflow invokes this
  via the `nodeTypes={:state state-node}` map. xyflow passes a single
  `props` argument; we read `:data` off it (an object whose keys are
  the CLJS payload the projector emitted).

  Visual identity:

    - Rounded-rect body, corner-radius 6 (the rf2-g6cig lock).
    - Mono state label centred.
    - State-tag pills above the label.
    - Active state: cyan tint + emphasised stroke.
    - From-highlight (focused-event lens origin): violet dashed.
    - To-highlight (focused-event lens landing): emphasised cyan.
    - Final state: doubled border + small check glyph."
  [^js props]
  (let [d              (.-data props)
        vc             (chart-constants d)
        label          (or (.-label d) "")
        path           (.-path d)
        active?        (boolean (.-active d))
        from-highlight? (boolean (.-fromHighlight d))
        to-highlight?  (boolean (.-toHighlight d))
        sim?           (boolean (.-sim d))
        final?         (boolean (.-final d))
        tags           (js->clj (.-tags d))
        on-click       (.-onClick d)
        emphasised?    (or active? from-highlight? to-highlight?)
        active-affordance? (or active? to-highlight?)
        styled         {:active?        active?
                        :from-highlight? from-highlight?
                        :to-highlight?  to-highlight?
                        :sim?           sim?
                        :final?         final?}
        fill           (node-fill styled)
        stroke         (node-stroke styled)
        ;; rf2-k647w — stroke widths read off the resolved density.
        ;; The active-affordance stroke sits one notch above the
        ;; emphasis stroke (active-affordance = emphasis + 0.75,
        ;; preserving the shipped regular relationship 2.5 → 3.25).
        {:keys [corner-radius stroke-width stroke-width-emphasis
                state-label-px final-glyph-px tag-pill-row-gap]} vc
        stroke-w       (cond
                         active-affordance? (+ stroke-width-emphasis 0.75)
                         emphasised?        stroke-width-emphasis
                         :else              stroke-width)]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-node-" (.-id props))
             :data-active (str active?)
             :data-from-highlight (str from-highlight?)
             :data-to-highlight (str to-highlight?)
             :data-active-affordance (str active-affordance?)
             :data-state-path (when path (pr-str (js->clj path)))
             :on-click (when on-click
                         (fn [_ev]
                           (on-click (js->clj path))))
             :style {:position         "relative"
                     :display          "flex"
                     :flex-direction   "column"
                     :align-items      "center"
                     :justify-content  "center"
                     :min-width        (str projection/state-node-min-width "px")
                     :min-height       (str projection/state-node-min-height "px")
                     :padding          "8px 12px"
                     :background       fill
                     :border           (str stroke-w "px solid " stroke)
                     :border-radius    (str corner-radius "px")
                     :font-family      mono-stack
                     :font-size        (str state-label-px "px")
                     :font-weight      (if emphasised? 600 400)
                     :color            (if emphasised?
                                         (:text-primary tokens/tokens)
                                         (:text-secondary tokens/tokens))
                     :cursor           (if on-click "pointer" "default")
                     :user-select      "none"
                     :box-shadow       (when active-affordance?
                                         (str "0 0 0 2px "
                                              (tokens/with-alpha :cyan 0.18)))
                     :transition       "border-color 120ms ease, background 120ms ease"}}
       ;; Final-state double-ring (outer)
       (when final?
         [:div {:style {:position      "absolute"
                       :top           "-3px"
                       :left          "-3px"
                       :right         "-3px"
                       :bottom        "-3px"
                       :border        (str "1px solid " (:green tokens/tokens))
                       ;; outer ring sits 1px proud of the node corner
                       :border-radius (str (inc corner-radius) "px")
                       :pointer-events "none"}}])
       ;; State-tag pills
       (when (seq tags)
         (into [:div {:data-testid "rf-mv-chart-state-tags"
                      :data-tag-count (count tags)
                      :style {:display        "flex"
                              :flex-direction "row"
                              :justify-content "center"
                              :align-items    "center"
                              :margin-bottom  (str tag-pill-row-gap "px")}}]
               (for [t tags] (tag-pill t vc))))
       ;; Label
       [:div {:style {:line-height "1.2"}} label]
       ;; Final-state check glyph
       (when final?
         [:div {:style {:position    "absolute"
                       :top         "4px"
                       :right       "8px"
                       :font-family mono-stack
                       :font-size   (str final-glyph-px "px")
                       :color       (:green tokens/tokens)}}
          "✓"])
       ;; xyflow attachment points (invisible — edges connect here)
       [:> Handle {:type "target" :position pos-top
                   :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-bottom
                   :style {:opacity 0}}]
       [:> Handle {:type "target" :position pos-left
                   :id "left"
                   :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-right
                   :id "right"
                   :style {:opacity 0}}]])))

;; ---- compound node ------------------------------------------------------

(defn compound-node
  "Reagent component for a compound state container. Renders a
  translucent boxed background with a header strip carrying the
  compound state's label.

  xyflow's `parentNode` mechanic places child state nodes inside
  this container; this component only renders the surrounding
  chrome."
  [^js props]
  (let [d     (.-data props)
        vc    (chart-constants d)
        label (or (.-label d) "")
        path  (.-path d)
        {:keys [compound-pad-y compound-radius compound-title-px]} vc]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-compound-" (.-id props))
             :data-node-id (.-id props)
             :data-state-path (when path (pr-str (js->clj path)))
             :style {:position         "relative"
                     :width            "100%"
                     :height           "100%"
                     :min-width        (str projection/compound-node-min-width "px")
                     :min-height       (str projection/compound-node-min-height "px")
                     :padding-top      (str compound-pad-y "px")
                     :background       (tokens/with-alpha :accent-violet 0.06)
                     :border           (str "1px dashed " (:accent-violet tokens/tokens))
                     :border-radius    (str compound-radius "px")
                     :pointer-events   "none"}}
       [:div {:style {:position    "absolute"
                     :top         "4px"
                     :left        "10px"
                     :font-family sans-stack
                     :font-size   (str compound-title-px "px")
                     :font-weight 600
                     :color       (:accent-violet tokens/tokens)}}
        label]])))

;; ---- initial / final marker nodes --------------------------------------

(defn initial-marker
  "Reagent component for the machine's initial-state marker — a
  small filled dot. Rendered as a tiny xyflow node with an outgoing
  edge into the initial state."
  [^js _props]
  (r/as-element
    [:div {:data-testid "rf-mv-chart-initial-marker"
           :style {:width            "12px"
                   :height           "12px"
                   :border-radius    "50%"
                   :background       (:accent-violet tokens/tokens)}}
     [:> Handle {:type "source" :position pos-right
                 :style {:opacity 0}}]]))

(defn final-marker
  "Reagent component for a final-state marker — concentric circle
  glyph. Reserved for the `[*]` end-state-as-node pattern when a
  future bead surfaces it; v1 paints the doubled ring inline on
  `state-node`."
  [^js _props]
  (r/as-element
    [:div {:data-testid "rf-mv-chart-final-marker"
           :style {:width            "14px"
                   :height           "14px"
                   :border-radius    "50%"
                   :border           (str "2px solid " (:green tokens/tokens))
                   :background       "transparent"
                   :position         "relative"}}
     [:div {:style {:position         "absolute"
                   :top              "3px"
                   :left             "3px"
                   :width            "4px"
                   :height           "4px"
                   :border-radius    "50%"
                   :background       (:green tokens/tokens)}}]
     [:> Handle {:type "target" :position pos-left
                 :style {:opacity 0}}]]))

;; ---- node-types map -----------------------------------------------------

(defn node-types
  "The `nodeTypes` prop value for `<ReactFlow>`. Returns a fresh
  plain-JS object on every call — xyflow caches by reference, so
  callers SHOULD memoise this map at component-construction time
  to avoid re-render churn."
  []
  #js {"state"           state-node
       "compound"        compound-node
       "parallel-region" parallel-region-node/parallel-region-node
       "initial-marker"  initial-marker
       "final-marker"    final-marker})
