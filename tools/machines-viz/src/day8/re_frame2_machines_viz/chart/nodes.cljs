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
                     four-cardinal-handles
                     chart-constants palette-of]]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens
             :refer [sans-stack chart-label-stack]]))

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
;;
;; rf2-az6e2 — the structured grammar reads STRUCTURE, not annotation
;; colour: a resting state node is neutral (body bg + neutral border),
;; and runtime state (active / focused-event lens / sim) drives the
;; BORDER + HEADER + GLOW, NOT the whole fill. So `node-border` swaps the
;; resting border for the runtime accent while the body fill stays
;; neutral; only `state-header-bg` picks up a faint runtime tint so the
;; title strip reads as "this is where we are".

(defn- node-border
  "rf2-az6e2 — the resting border is the neutral structural colour; a
  runtime state (active / focus lens / sim) swaps it for the runtime
  accent so the border carries the signal, not the fill."
  [ct {:keys [active? from-highlight? to-highlight? sim?]}]
  (cond
    sim?            (:sim ct)
    to-highlight?   (:active ct)
    from-highlight? (:focus ct)
    active?         (:active ct)
    :else           (:state-border ct)))

(defn- header-bg
  "rf2-az6e2 — the title strip's fill. Neutral by default; a faint
  runtime wash when active / focused so the header reads the runtime
  signal without flooding the whole node fill."
  [ct {:keys [active? from-highlight? to-highlight? sim?]}]
  (cond
    sim?            (:sim-wash ct)
    to-highlight?   (:active-wash ct)
    from-highlight? (:focus-wash ct)
    active?         (:active-wash ct)
    :else           (:state-header-bg ct)))

(defn- tag-label
  "rf2-vcnvj — render a state-tag's DECLARED identity as a string,
  PRESERVING the namespace. A `:door/open` tag reads `door/open`, not
  the truncated `open` — the bead's tag-identity fix. The pre-vcnvj
  `(name tag)` dropped the namespace, collapsing `:door/open`,
  `:lift/open`, … to the same visible `open`."
  [t]
  (cond
    (keyword? t) (if-let [ns (namespace t)]
                   (str ns "/" (name t))
                   (name t))
    :else        (str t)))

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
  bulk without parsing the per-pill DOM.

  rf2-vcnvj — uses `tag-label` so the namespaced tag identity
  (`door/open`) survives here too, matching the visible pill."
  [tags]
  (when (seq tags)
    (->> tags
         (map tag-label)
         sort
         (str/join " "))))

(defn- tag-pill
  "rf2-az6e2 — render a single state-tag chip in ONE NEUTRAL style.
  Structure wins over annotation colour: the prior deterministic colour
  rotation (rf2-a2b55) is dropped for the topology view so the eye reads
  the chart's STRUCTURE (containment + transition flow), not a rainbow
  of tag hues. Tag IDENTITY is preserved in the `data-tag` / `title`
  attrs + the state-node's `data-tags` + the inspector surfaces, so host
  introspection + hover still resolve every tag.

  rf2-vcnvj — the VISIBLE label + the `data-tag` attr now preserve the
  DECLARED tag identity (`door/open`, not the truncated `open`) via
  `tag-label`. The `data-testid` keeps the namespace-collapsed `name`
  segment (a `/` in a testid breaks CSS / Playwright selectors) — host
  introspection reads the full identity off `data-tag` / `title`.

  Geometry + typography read off the resolved density `vc` map; colour
  is the neutral container-header fill + the structural border + the
  secondary text colour from the active-theme `ct` map."
  [tag ct {:keys [tag-pill-height tag-pill-pad-x tag-pill-px
                  tag-pill-radius tag-pill-gap]}]
  (let [label      (tag-label tag)
        testid-seg (if (keyword? tag) (name tag) (str tag))]
    [:span {:key   label
            :title (str tag)
            :data-testid (str "rf-mv-chart-state-tag-" testid-seg)
            :data-tag    label
            :style {:display          "inline-flex"
                    :align-items      "center"
                    :height           (str tag-pill-height "px")
                    :padding          (str "0 " tag-pill-pad-x "px")
                    :margin-right     (str tag-pill-gap "px")
                    :background       (:container-header-bg ct)
                    :border           (str "1px solid " (:state-border ct))
                    :border-radius    (str tag-pill-radius "px")
                    :font-family      chart-label-stack
                    :font-size        (str tag-pill-px "px")
                    :font-weight      500
                    :color            (:text-secondary ct)
                    :line-height      "1"
                    :white-space      "nowrap"}}
     label]))

(defn- action-row
  "rf2-az6e2 — render an entry / exit action METADATA ROW: a quiet
  section caption (\"Entry actions\" / \"Exit actions\") plus a subdued
  action chip carrying a bolt glyph + the action name. The chip stays
  SUBORDINATE to the state title (quieter colour + smaller type). Reads
  geometry/typography off the resolved density `vc` map and colour off
  the active-theme `ct`."
  [{:keys [kind name-str vc ct]}]
  (let [{:keys [action-pill-height action-pill-pad-x action-pill-px
                action-pill-radius action-caption-px action-caption-gap]} vc
        caption (case kind :entry "Entry actions" :exit "Exit actions")]
    [:div {:data-testid (case kind
                          :entry "rf-mv-chart-state-entry"
                          :exit  "rf-mv-chart-state-exit")
           (case kind :entry :data-entry :exit :data-exit) name-str
           :style {:display        "flex"
                   :flex-direction "column"
                   :gap            (str action-caption-gap "px")}}
     ;; rf2-vcnvj — quiet TITLE-CASE caption ("Entry actions" / "Exit
     ;; actions"), NOT uppercase. The uppercase transform competed with
     ;; the state title for attention against the structure-first grammar;
     ;; the caption is a quiet section label, so it reads in natural case.
     ;; rf2-ly51l — drop the 600 weight + 0.02em letter-spacing: the
     ;; bolded, tracked caption still read as a loud label competing with
     ;; the action chip below it. A normal-weight, un-tracked, tertiary-
     ;; colour caption is the QUIETEST tier of the topology type hierarchy
     ;; (state title > event chip > action chip > tag pill > section
     ;; caption), so it reads as a section hint, not a heading.
     [:span {:style {:font-family    chart-label-stack
                     :font-size      (str action-caption-px "px")
                     :font-weight    400
                     :color          (:text-tertiary ct)
                     :line-height     "1"}}
      caption]
     [:span {:style {:display       "inline-flex"
                     :align-items   "center"
                     :gap           "3px"
                     :align-self    "flex-start"
                     :height        (str action-pill-height "px")
                     :padding       (str "0 " action-pill-pad-x "px")
                     :background    (:container-header-bg ct)
                     :border        (str "1px solid " (:state-border ct))
                     :border-radius (str action-pill-radius "px")
                     :font-family   chart-label-stack
                     :font-size     (str action-pill-px "px")
                     :font-weight   500
                     :color         (:text-secondary ct)
                     :line-height   "1"
                     :white-space   "nowrap"}}
      ;; subordinate bolt/action glyph (text convention — no icon dep)
      [:span {:style {:opacity 0.7}} "⚡"]
      name-str]]))

;; ---- state node ---------------------------------------------------------

(defn state-node
  "rf2-az6e2 — Reagent component for a leaf state node, rendered as a
  STRUCTURED TITLE/BODY BOX (not a centred rounded pill). xyflow invokes
  this via `nodeTypes={:state state-node}`; we read the projected `:data`
  off the JS props.

  Structure:

    - Square-ish box, low radius (the rf2-g6cig 6px lock).
    - A full-width TITLE STRIP carrying the state label, LEFT-aligned,
      sans font (`chart-label-stack`).
    - A title/body DIVIDER (hairline) when body content exists.
    - A BODY area holding neutral tag chips + quiet entry/exit action
      rows, also left-aligned. Absent entirely when the state has no
      tags + no actions (then the title strip IS the box).

  Runtime affordance (structure-first — the runtime signal rides the
  BORDER + HEADER + GLOW, NOT the whole fill):

    - active / to-highlight: runtime accent border + faint header wash +
      glow ring.
    - from-highlight (focus lens origin): focus accent border + wash.
    - sim: amber accent.
    - final: a QUIET double border (outer ring). The prior ✓ check glyph
      is DROPPED (rf2-az6e2 decision recorded in the bead) — the doubled
      border is the unambiguous final-state signal and the glyph competed
      with the title for attention.

  Tag identity (`data-tags` / per-chip `data-tag` / `title`) + the
  `data-active*` / `data-state-path` attrs are PRESERVED for the DOM
  test suite + host introspection."
  [^js props]
  (let [d              (.-data props)
        vc             (chart-constants d)
        ct             (palette-of d)
        label          (or (.-label d) "")
        path           (.-path d)
        active?        (boolean (.-active d))
        from-highlight? (boolean (.-fromHighlight d))
        to-highlight?  (boolean (.-toHighlight d))
        sim?           (boolean (.-sim d))
        final?         (boolean (.-final d))
        tags           (js->clj (.-tags d))
        tags-attr      (tag-title-attr tags)
        entry          (.-entry d)
        exit           (.-exit d)
        on-click       (.-onClick d)
        emphasised?    (or active? from-highlight? to-highlight?)
        active-affordance? (or active? to-highlight?)
        styled         {:active?         active?
                        :from-highlight? from-highlight?
                        :to-highlight?   to-highlight?
                        :sim?            sim?}
        border-col     (node-border ct styled)
        header-fill    (header-bg ct styled)
        {:keys [corner-radius stroke-width stroke-width-emphasis
                state-title-height state-title-pad-x state-title-px
                state-body-pad-x state-body-pad-y state-body-gap
                state-divider-width state-shadow-blur
                tag-pill-row-gap]} vc
        stroke-w       (cond
                         active-affordance? (+ stroke-width-emphasis 0.75)
                         emphasised?        stroke-width-emphasis
                         :else              stroke-width)
        has-body?      (or (seq tags) entry exit)]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-node-" (.-id props))
             :data-active (str active?)
             :data-from-highlight (str from-highlight?)
             :data-to-highlight (str to-highlight?)
             :data-active-affordance (str active-affordance?)
             :data-final (str final?)
             :data-state-path (when path (pr-str (js->clj path)))
             :data-tags (or tags-attr "")
             :data-tag-count (count (or tags []))
             :title (or tags-attr (when on-click "Click for details"))
             :on-click (when on-click
                         (fn [_ev]
                           (on-click (js->clj path))))
             :style {:position         "relative"
                     :display          "flex"
                     :flex-direction   "column"
                     :align-items      "stretch"
                     :min-width        (str projection/state-node-min-width "px")
                     :min-height       (str projection/state-node-min-height "px")
                     :background       (:state-body-bg ct)
                     :border           (str stroke-w "px solid " border-col)
                     :border-radius    (str corner-radius "px")
                     :overflow         "hidden"
                     :font-family      chart-label-stack
                     :color            (:text-secondary ct)
                     :cursor           (if on-click "pointer" "default")
                     :user-select      "none"
                     :box-shadow       (if active-affordance?
                                         (str "0 0 0 2px " (:glow ct))
                                         (str "0 1px " state-shadow-blur "px rgba(0,0,0,0.25)"))
                     :transition       "border-color 120ms ease, background 120ms ease"}}
       ;; Final-state QUIET double-ring (outer). The ✓ glyph is dropped.
       (when final?
         [:div {:data-testid (str "rf-mv-chart-final-ring-" (.-id props))
                :style {:position      "absolute"
                        :top           "-3px"
                        :left          "-3px"
                        :right         "-3px"
                        :bottom        "-3px"
                        :border        (str "1px solid " border-col)
                        :border-radius (str (inc corner-radius) "px")
                        :pointer-events "none"}}])
       ;; TITLE STRIP — full-width, left-aligned label.
       [:div {:data-testid (str "rf-mv-chart-state-title-" (.-id props))
              :style {:display        "flex"
                      :align-items    "center"
                      :min-height     (str state-title-height "px")
                      :padding        (str "0 " state-title-pad-x "px")
                      :background      header-fill
                      :border-bottom   (if has-body?
                                         (str state-divider-width "px solid "
                                              (:divider ct))
                                         "none")
                      :font-size      (str state-title-px "px")
                      :font-weight    (if emphasised? 600 500)
                      :color          (if emphasised?
                                        (:text-primary ct)
                                        (:text-secondary ct))
                      :white-space    "nowrap"
                      :overflow       "hidden"
                      :text-overflow  "ellipsis"}}
        label]
       ;; BODY — neutral tag chips + quiet entry/exit action rows.
       (when has-body?
         [:div {:style {:display         "flex"
                        :flex-direction  "column"
                        :gap             (str state-body-gap "px")
                        :padding         (str state-body-pad-y "px "
                                              state-body-pad-x "px")}}
          (when (seq tags)
            [:div {:data-testid "rf-mv-chart-state-tags"
                   :style {:display    "flex"
                           :flex-wrap  "wrap"
                           :align-items "center"
                           :row-gap    (str tag-pill-row-gap "px")}}
             (->> tags
                  sort
                  (map (fn [t] (tag-pill t ct vc))))])
          (when entry
            (action-row {:kind :entry :name-str entry :vc vc :ct ct}))
          (when exit
            (action-row {:kind :exit :name-str exit :vc vc :ct ct}))])
       ;; xyflow attachment points (invisible — edges connect here)
       (four-cardinal-handles)])))

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
        ct    (palette-of d)
        label (or (.-label d) "")
        path  (.-path d)
        ;; rf2-80rm2 (G4) — a compound CONTAINER whose active descendant
        ;; leaf lit it (the projector folds that into `:active` via the
        ;; `:parent-id` chain) gets active chrome: the runtime accent
        ;; border + glow ring. Inactive compounds read NEUTRAL.
        active? (boolean (.-active d))
        {:keys [compound-radius container-title-height container-title-pad-x
                container-title-px container-divider-width
                stroke-width stroke-width-emphasis]} vc
        ;; rf2-az6e2 — solid SUBTLE NEUTRAL border by default (no dashed,
        ;; no accent wash — dashed/accent is reserved for parallel
        ;; regions + runtime state). Active swaps to the runtime accent.
        border-col  (if active? (:active ct) (:container-border ct))
        border-w    (if active? stroke-width-emphasis stroke-width)]
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
                     :background       (:container-body-bg ct)
                     :border           (str border-w "px solid " border-col)
                     :border-radius    (str compound-radius "px")
                     :box-shadow       (when active?
                                         (str "0 0 0 2px " (:glow ct)))
                     ;; rf2-shv82 — body stays pointer-transparent so
                     ;; clicks pass through to nested leaves.
                     :pointer-events   "none"}}
       ;; FULL-WIDTH TITLE STRIP at top — solid neutral header band.
       [:div {:data-testid (str "rf-mv-chart-compound-title-" (.-id props))
              :style {:position    "absolute"
                      :top         0
                      :left        0
                      :right       0
                      :display     "flex"
                      :align-items "center"
                      :height      (str container-title-height "px")
                      :padding     (str "0 " container-title-pad-x "px")
                      :background    (if active?
                                       (:active-wash ct)
                                       (:container-header-bg ct))
                      :border-bottom (str container-divider-width "px solid "
                                          (:divider ct))
                      :border-top-left-radius  (str compound-radius "px")
                      :border-top-right-radius (str compound-radius "px")
                      :font-family sans-stack
                      :font-size   (str container-title-px "px")
                      :font-weight 600
                      :color       (if active?
                                     (:text-primary ct)
                                     (:text-secondary ct))
                      :white-space "nowrap"
                      :overflow    "hidden"
                      :text-overflow "ellipsis"}}
        label]
       ;; rf2-shv82 — invisible xyflow attachment points.
       (four-cardinal-handles)])))

;; ---- initial marker node ------------------------------------------------

(defn initial-marker
  "rf2-az6e2 — Reagent component for the machine's initial-state pseudo-
  state marker. A small NEUTRAL filled dot (the SCXML/xstate initial
  pseudo-state convention), NOT an accent-blue runtime marker: the
  initial pseudo-state is STATIC topology, so it reads in the neutral
  pseudo-state colour (`:pseudo-marker`), reserving accent/active for
  runtime state. Rendered as a tiny xyflow node with an outgoing edge
  into the initial state.

  Geometry reads off the resolved density (`:pseudo-size` /
  `:pseudo-radius`)."
  [^js props]
  (let [d   (.-data props)
        vc  (chart-constants d)
        ct  (palette-of d)
        {:keys [pseudo-size]} vc]
    (r/as-element
      [:div {:data-testid "rf-mv-chart-initial-marker"
             :data-pseudo-kind "initial"
             :style {:display     "inline-flex"
                     :align-items "center"}}
       [:div {:data-testid "rf-mv-chart-initial-marker-dot"
              :style {:width         (str pseudo-size "px")
                      :height        (str pseudo-size "px")
                      :border-radius "50%"
                      :background    (:pseudo-marker ct)}}]
       [:> Handle {:type "source" :position pos-right
                   :style {:opacity 0}}]])))

;; ---- machine-root node (rf2-vcnvj) --------------------------------------

(defn machine-root-node
  "rf2-vcnvj — the synthetic MACHINE-ROOT node: the in-graph source a
  machine-level (top-level `:on`) fallback transition routes FROM, so
  the fallback renders as exactly ONE route/chip into its target instead
  of one back-edge per leaf state (the pre-vcnvj per-state repetition
  that also scrambled ELK's main-column ordering).

  Painted as a quiet ROOT-CONTEXT chip — a small neutral pill with a
  subtle root glyph (`◆`) + a `root` caption — NOT a state box, so it
  reads as the machine-wide anchor. The frame-level root TITLE strip
  (`chart.cljs`) carries the machine name + Context shape; this in-graph
  chip is just the routing anchor for the fallback arrow. Carries an
  outgoing source `<Handle>` (the fallback's `__in` edge leaves here)."
  [^js props]
  (let [d   (.-data props)
        vc  (chart-constants d)
        ct  (palette-of d)
        {:keys [pseudo-px]} vc]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-machine-root-" (.-id props))
             :data-machine-root "true"
             :style {:display       "inline-flex"
                     :align-items   "center"
                     :gap           "5px"
                     :padding       "4px 9px"
                     :background    (:container-header-bg ct)
                     :border        (str "1px solid " (:state-border ct))
                     :border-radius "999px"
                     :font-family   chart-label-stack
                     :font-size     (str pseudo-px "px")
                     :font-weight   600
                     :letter-spacing "0.04em"
                     :color         (:text-tertiary ct)
                     :line-height   "1"
                     :white-space   "nowrap"
                     :user-select   "none"}}
       [:span {:style {:opacity 0.7}} "◆"]
       [:span (or (.-label d) "root")]
       [:> Handle {:type "source" :position pos-bottom
                   :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-right
                   :id "right"
                   :style {:opacity 0}}]])))

;; ---- history pseudo-state renderer (rf2-az6e2 — HOOK ONLY) --------------
;;
;; rf2-az6e2 — the bead defines the VISUAL rendering of history pseudo-
;; states (shallow `H` / deep `H*`, small symbolic node inside the owning
;; compound, direct incoming transitions, NO normal state-box styling)
;; for parsed topology that ALREADY contains pseudo-state data. The
;; current `chart.layout/parse-definition` does NOT yet emit history
;; pseudo-state nodes (no `:history` key in the parsed node shape), and
;; the bead is explicit that it "must not add statechart history
;; semantics to re-frame2". So this renderer is a HOOK: it is shaped to
;; the grammar + wired into the node-types map below, but the projector
;; never emits a `history-marker` node today. When the parse + Spec 005
;; history semantics land (follow-on bead rf2-az6e2-history-render),
;; flip the projector to emit `{:type "history-marker" :data {:deep?
;; …}}` and this renderer paints it. Constants (`:pseudo-*`) already
;; carry the variant.

(defn history-marker
  "rf2-az6e2 — small symbolic pseudo-state node for a history marker.
  Shallow history renders `H`; deep history renders `H*`. Reads
  `:data {:deep? <bool>}`. NOT a normal state box — a small neutral
  rounded square so it reads as a pseudo-state inside its owning
  compound. HOOK ONLY today (see the section comment above): no
  projector path emits it until history topology data exists."
  [^js props]
  (let [d   (.-data props)
        vc  (chart-constants d)
        ct  (palette-of d)
        deep? (boolean (.-deep d))
        {:keys [pseudo-size pseudo-radius pseudo-px]} vc
        side (+ pseudo-size 6)]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-history-" (.-id props))
             :data-pseudo-kind (if deep? "history-deep" "history-shallow")
             :style {:display         "inline-flex"
                     :align-items     "center"
                     :justify-content "center"
                     :width           (str side "px")
                     :height          (str side "px")
                     :border          (str "1px solid " (:pseudo-marker ct))
                     :border-radius   (str pseudo-radius "px")
                     :background      (:state-body-bg ct)
                     :font-family     chart-label-stack
                     :font-size       (str pseudo-px "px")
                     :font-weight     700
                     :color           (:pseudo-marker ct)
                     :line-height     "1"
                     :user-select     "none"}}
       (if deep? "H*" "H")
       [:> Handle {:type "target" :position pos-top    :style {:opacity 0}}]
       [:> Handle {:type "target" :position pos-left :id "left"
                   :style {:opacity 0}}]])))

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
       ;; rf2-vcnvj — the synthetic root-context chip a machine-level
       ;; (top-level `:on`) fallback routes from (projected once, not
       ;; per-state).
       "machine-root"    machine-root-node
       ;; rf2-az6e2 — history pseudo-state renderer registered as a HOOK
       ;; (the projector emits no `history-marker` node until history
       ;; topology data lands; see `history-marker` docstring).
       "history-marker"  history-marker
       "rf2-event"       event-node/event-node})
