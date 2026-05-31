(ns day8.re-frame2-machines-viz.chart.nodes
  "Custom xyflow node components for the MachineChart.

  rf2-gpzb4 (2026-05-21 xyflow override) — these components recover
  the Xray visual identity (rounded-rect node body, state-tag pills,
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
      large body containing nested children (xyflow's `parentId`
      mechanic handles the hierarchical layout; this node renders
      the outer container chrome). Carries invisible source+target
      `Handle`s on all four sides (rf2-shv82) so xstate/Stately-style
      PARENT-LEVEL TRANSITIONS (`:active → :disconnected`, `:active`
      self-loop, `:failed → :active`) render as edges anchored to the
      compound's BORDER. Without handles `getEdgePosition` returns
      nil for any edge whose endpoint is a compound, and xyflow
      silently drops the edge from the DOM — the gap rf2-shv82
      closes.
    - `initial-marker` — a small glyph node paired with the initial
      state to mark the machine's entry transition. (Final states paint
      a doubled ring + ✓ glyph inline on `state-node`; there is no
      separate final-marker node — rf2-ee38b.21 removed the dead one.)

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
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.event-node
             :as event-node]
            [day8.re-frame2-machines-viz.chart.nodes.parallel-region-node
             :as parallel-region-node]
            [day8.re-frame2-machines-viz.chart.nodes.xyflow-node
             :refer [Handle pos-top pos-right pos-bottom pos-left
                     chart-constants]]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [mono-stack sans-stack]]))

;; ---- density-resolved constants -----------------------------------------
;;
;; rf2-k647w — the renderer no longer hardcodes geometry/typography. The
;; projector threads the resolved density's `visual-constants` map onto
;; every node payload as `:data {:chart {...}}`; xyflow `clj->js`-es the
;; node array, so the map arrives as a JS object. `chart-constants`
;; (shared via `chart.nodes.xyflow-node`) recovers a kebab-keyword CLJS
;; map, falling back to `vc/chart-regular` so a node payload without a
;; `:chart` entry (legacy / direct construction) still renders the
;; regular default.

;; ---- xyflow Handle adapter ----------------------------------------------
;;
;; `Handle` + the four `pos-*` constants are the shared xyflow node-
;; interop preamble (`chart.nodes.xyflow-node`); referred above so every
;; custom node attaches edges identically.

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
    to-highlight?   (tokens/with-alpha :info           0.22)
    from-highlight? (tokens/with-alpha :accent  0.14)
    active?         (tokens/with-alpha :info           0.18)
    :else           (:bg-2 tokens/tokens)))

(defn- node-stroke
  [{:keys [active? from-highlight? to-highlight? sim? final?]}]
  (cond
    sim?            (:yellow tokens/tokens)
    to-highlight?   (:info tokens/tokens)
    from-highlight? (:accent tokens/tokens)
    active?         (:info tokens/tokens)
    final?          (:green tokens/tokens)
    :else           (:border-default tokens/tokens)))

(defn- tag-title-attr
  "Compose a state's `:tags` set (Spec 005 user-declared semantic
  tags) into a sorted space-joined string for the state-node's
  `:title` (HTML hover tooltip) + `:data-tags` (DOM tests + host
  introspection) attrs. Returns `nil` when the set is empty so the
  title attr is simply omitted (no \"\" tooltip flicker).

  rf2-so5b0 retired the visible per-tag pill row; rf2-a2b55 reinstates
  it BELOW the state name (Stately graph view convention) via the
  `tag-pill` helper. The title + data-tags attrs remain so the host
  inspector + DOM introspection still resolve a state's tags in
  bulk without parsing the per-pill DOM."
  [tags]
  (when (seq tags)
    (->> tags
         (map (fn [t] (if (keyword? t) (name t) (str t))))
         sort
         (str/join " "))))

(defn- tag-pill
  "rf2-a2b55 — render a single state-tag pill positioned BELOW the
  state name per Stately graph view convention. Hiccup. Geometry +
  typography read off the resolved density `vc` map (height / pad-x
  / px / radius / gap) so the chrome scales with `:density`. Colour
  comes from the deterministic `tokens/tag-pill-color` rotation so a
  given tag id paints the same hue across renders.

  Pre-rf2-so5b0 the pill row sat ABOVE the label and the diagnostic
  in that bead misread the resulting clutter as ancestor-path tag
  chips. Stately's graph view paints descriptive tags BELOW the
  state name as a quiet pill row — rf2-a2b55 follows that pattern."
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

(defn- action-pill
  "rf2-a2b55 — render an entry / exit action as a `+ <name>` (entry)
  or `- <name>` (exit) pill, the Stately graph view convention for
  state-actions. Geometry + typography read off the resolved density
  `vc` map. Colour uses the `:advisory` token so the pill reads as
  a subordinate annotation against the state name."
  [{:keys [kind name-str vc]}]
  (let [{:keys [action-pill-height action-pill-pad-x action-pill-px
                action-pill-radius action-pill-gap]} vc
        prefix (case kind :entry "+ " :exit "- ")]
    [:span {:data-testid (case kind
                           :entry "rf-mv-chart-state-entry"
                           :exit  "rf-mv-chart-state-exit")
            (case kind :entry :data-entry :exit :data-exit) name-str
            :style {:display          "inline-flex"
                    :align-items      "center"
                    :height           (str action-pill-height "px")
                    :padding          (str "0 " action-pill-pad-x "px")
                    :margin-right     (str action-pill-gap "px")
                    :background       (tokens/with-alpha :advisory 0.18)
                    :border           (str "1px solid "
                                            (tokens/with-alpha :advisory 0.6))
                    :border-radius    (str action-pill-radius "px")
                    :font-family      mono-stack
                    :font-size        (str action-pill-px "px")
                    :font-weight      500
                    :color            (:advisory tokens/tokens)
                    :line-height      "1"
                    :white-space      "nowrap"}}
     (str prefix name-str)]))

;; ---- state node ---------------------------------------------------------

(defn state-node
  "Reagent component for a standard state node. xyflow invokes this
  via the `nodeTypes={:state state-node}` map. xyflow passes a single
  `props` argument; we read `:data` off it (an object whose keys are
  the CLJS payload the projector emitted).

  Visual identity:

    - Rounded-rect body, corner-radius 6 (the rf2-g6cig lock).
    - Mono state label centred at the top.
    - User-declared `:tags` (Spec 005) render as a pill row directly
      BELOW the state name (rf2-a2b55, Stately graph view convention)
      with deterministic per-tag colours from `tokens/tag-pill-color`.
      The same set surfaces on the state-node's `:data-tags` +
      `:title` attrs (rf2-so5b0 contract) so host introspection +
      hover tooltips still resolve the tag set in bulk.
    - Active state: cyan tint + emphasised stroke.
    - From-highlight (focused-event lens origin): violet dashed.
    - To-highlight (focused-event lens landing): emphasised cyan.
    - Final state: doubled border + small check glyph.
    - Entry / exit actions render as `+ <name>` (entry) / `- <name>`
      (exit) pills BELOW the tag row (rf2-a2b55 — Stately graph view
      `Entry actions` convention; replaces the prior `entry / <name>`
      text rows from rf2-ee38b.21)."
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
        ;; rf2-so5b0 + rf2-a2b55 — tags surface BOTH as a visible pill
        ;; row BELOW the state name (Stately graph view convention,
        ;; restored in rf2-a2b55) AND as the sorted space-joined
        ;; tooltip / data-attr surface rf2-so5b0 added for host
        ;; introspection. `tags-attr` is nil when the state has no
        ;; tags so the title attr is simply omitted.
        tags-attr      (tag-title-attr tags)
        entry          (.-entry d)
        exit           (.-exit d)
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
                state-label-px final-glyph-px]} vc
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
             ;; rf2-so5b0 — user-declared `:tags` exposed for DOM tests +
             ;; host introspection without visible chart chrome.
             :data-tags (or tags-attr "")
             :data-tag-count (count (or tags []))
             :title (or tags-attr (when on-click "Click for details"))
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
                                              (tokens/with-alpha :info 0.18)))
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
       ;; Label
       [:div {:style {:line-height "1.2"}} label]
       ;; rf2-a2b55 — tag pill row positioned BELOW the state name
       ;; (Stately graph view convention). Pre-rf2-so5b0 the row sat
       ;; ABOVE the label; that bead diagnosed the resulting clutter
       ;; as ancestor-path tag chips and retired the row. rf2-a2b55
       ;; reinstates the row in its Stately-canonical position.
       (when (seq tags)
         [:div {:data-testid "rf-mv-chart-state-tags"
                :style {:display        "flex"
                        :flex-wrap      "wrap"
                        :justify-content "center"
                        :align-items    "center"
                        :margin-top     (str (:tag-pill-row-gap vc) "px")
                        :gap            "0"}}
          (->> tags
               sort
               (map (fn [t] (tag-pill t vc))))])
       ;; rf2-a2b55 — :entry / :exit actions render as `+ <name>` /
       ;; `- <name>` pills BELOW the tag row (Stately graph view
       ;; `Entry actions` convention; replaces the prior `entry /
       ;; <name>` text rows shipped by rf2-ee38b.21).
       (when (or entry exit)
         [:div {:data-testid "rf-mv-chart-state-actions"
                :style {:display        "flex"
                        :flex-direction "row"
                        :flex-wrap      "wrap"
                        :justify-content "center"
                        :align-items    "center"
                        :margin-top     (str (:action-pill-row-gap vc) "px")
                        :gap            "0"}}
          (when entry
            (action-pill {:kind :entry :name-str entry :vc vc}))
          (when exit
            (action-pill {:kind :exit  :name-str exit  :vc vc}))])
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

  xyflow's `parentId` mechanic places child state nodes inside
  this container; this component only renders the surrounding
  chrome.

  ## Border handles (rf2-shv82)

  Invisible source + target `<Handle>` elements sit on all four
  sides so xstate/Stately-style PARENT-LEVEL TRANSITIONS (an edge
  whose source or target is a compound: `:active → :disconnected`,
  `:active` self-loop, `:failed → :active`, …) render normally
  through xyflow's pipeline. Without these handles xyflow's
  `getHandleBounds` returns null for the compound node, `isNodeInitialized`
  returns false, `getEdgePosition` returns null, and xyflow SILENTLY
  DROPS every edge incident on a compound from the DOM — even though
  the projector emitted them and elk routed them (the 5-layer probe
  trace in the rf2-shv82 bead proves this). Adding handles makes the
  compound an edge endpoint xstate-style (the edge anchors to the
  compound's BORDER, the way Stately Studio paints parent-level
  arrows). Handles are visually-hidden (`opacity: 0`) so they don't
  add chrome; xyflow still measures them for `handleBounds`."
  [^js props]
  (let [d     (.-data props)
        vc    (chart-constants d)
        label (or (.-label d) "")
        path  (.-path d)
        ;; rf2-80rm2 (G4) — for self-consistency with the active-region
        ;; chrome, a compound CONTAINER whose active descendant leaf lit it
        ;; (the projector folds that into `:active` via the `:parent-id`
        ;; chain) also gets active chrome: a solid (not dashed) accent border
        ;; + the `:info` active-token glow ring — the same active affordance
        ;; state nodes and active regions use. Minimal: only the boundary
        ;; firms up and the glow appears; inactive compounds are unchanged.
        active? (boolean (.-active d))
        {:keys [compound-pad-y compound-radius compound-title-px]} vc]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-compound-" (.-id props))
             :data-node-id (.-id props)
             :data-state-path (when path (pr-str (js->clj path)))
             :data-active (str active?)
             :style {:position         "relative"
                     :width            "100%"
                     :height           "100%"
                     :min-width        (str projection/compound-node-min-width "px")
                     :min-height       (str projection/compound-node-min-height "px")
                     :padding-top      (str compound-pad-y "px")
                     :background       (tokens/with-alpha :accent (if active? 0.10 0.06))
                     :border           (str (if active? "1.5px solid " "1px dashed ")
                                            (:accent tokens/tokens))
                     :border-radius    (str compound-radius "px")
                     :box-shadow       (when active?
                                         (str "0 0 0 2px "
                                              (tokens/with-alpha :info 0.18)))
                     ;; rf2-shv82 — `pointer-events: auto` only on the
                     ;; handles (set per-handle below). The body stays
                     ;; pointer-events-transparent so clicks pass through to
                     ;; nested leaves.
                     :pointer-events   "none"}}
       [:div {:style {:position    "absolute"
                     :top         "4px"
                     :left        "10px"
                     :font-family sans-stack
                     :font-size   (str compound-title-px "px")
                     :font-weight 600
                     :color       (:accent tokens/tokens)}}
        label]
       ;; rf2-shv82 — invisible xyflow attachment points. Edges with a
       ;; compound endpoint anchor to one of these; without them xyflow
       ;; silently drops the edge (the bug rf2-shv82 closes).
       [:> Handle {:type "target" :position pos-top    :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-bottom :style {:opacity 0}}]
       [:> Handle {:type "target" :position pos-left   :id "left"
                   :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-right  :id "right"
                   :style {:opacity 0}}]])))

;; ---- initial marker node ------------------------------------------------

(defn initial-marker
  "Reagent component for the machine's initial-state marker — a filled
  dot followed by the `↳` glyph (Stately graph view convention,
  rf2-qo5xy). Rendered as a tiny xyflow node with an outgoing edge
  into the initial state.

  Pre-rf2-qo5xy the marker was JUST the filled dot; the bead's
  paradigm-shift checklist calls for the `↳` glyph as an additional
  affordance an operator who knows xstate/Stately reads in 30
  seconds — 'this is THE initial state' rather than 'a state node
  with an upstream marker'. Composing the glyph on the marker
  (rather than as separate node) keeps the topology hop short and
  the elk layered layout pleasant."
  [^js _props]
  (r/as-element
    [:div {:data-testid "rf-mv-chart-initial-marker"
           :style {:display          "inline-flex"
                   :align-items      "center"
                   :gap              "4px"}}
     [:div {:data-testid "rf-mv-chart-initial-marker-dot"
            :style {:width            "10px"
                    :height           "10px"
                    :border-radius    "50%"
                    :background       (:accent tokens/tokens)}}]
     [:span {:data-testid "rf-mv-chart-initial-marker-glyph"
             :style {:font-family   mono-stack
                     :font-size     "14px"
                     :font-weight   600
                     :line-height   "1"
                     :color         (:accent tokens/tokens)}}
      "↳"]
     [:> Handle {:type "source" :position pos-right
                 :style {:opacity 0}}]]))

;; rf2-ee38b.21 — the dead `final-marker` component + its node-type
;; registration were removed. The projector only ever emits
;; `initial-marker` nodes; final states paint the doubled ring + ✓
;; glyph inline on `state-node`. The end-state-as-node `[*]` pattern,
;; if it ever lands, files its own bead (same posture the codebase
;; took when it removed the dead `spawn-edge` registration).

;; ---- node-types map -----------------------------------------------------

(defn node-types
  "The `nodeTypes` prop value for `<ReactFlow>`. Returns a fresh
  plain-JS object on every call — xyflow caches by reference, so
  callers SHOULD memoise this map at component-construction time
  to avoid re-render churn.

  rf2-qo5xy — `rf2-event` joins the registered set: events render as
  first-class xyflow nodes (one per spec transition) rather than as
  edge LABELS between state boxes. Same node-type pattern the state /
  compound / region nodes use."
  []
  #js {"state"           state-node
       "compound"        compound-node
       "parallel-region" parallel-region-node/parallel-region-node
       "initial-marker"  initial-marker
       "rf2-event"       event-node/event-node})
