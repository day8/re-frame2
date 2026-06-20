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
      a quiet doubled ring inline on `state-node` — no glyph
      (rf2-az6e2 dropped the ✓); there is no separate final-marker
      node — rf2-ee38b.21 removed the dead one.)

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
  `:lift/open`, … to the same visible `open`.

  The projector (`chart.projection`) already stringifies each tag to
  its fully-qualified form (`\"door/open\"`) BEFORE xyflow `clj->js`-es
  the node `:data` (whose default keyword-fn is `name` and would
  otherwise strip the namespace). By the time the renderer reads tags
  back they are plain strings, so the string branch carries the live
  path; the keyword branch is kept defensive for any direct caller."
  [t]
  (cond
    (keyword? t) (if-let [ns (namespace t)]
                   (str ns "/" (name t))
                   (name t))
    :else        (str t)))

(defn- tag-testid-seg
  "The `data-testid` segment for a tag chip. A `/` in a testid breaks
  CSS / Playwright selectors, so collapse a namespaced tag to its
  trailing `name` segment (`door/open` → `open`). The full identity
  stays on `data-tag` / `title` for host introspection (rf2-vcnvj)."
  [tag]
  (let [label (tag-label tag)
        idx   (.lastIndexOf label "/")]
    (if (neg? idx) label (subs label (inc idx)))))

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
        testid-seg (tag-testid-seg tag)]
    [:span {:key   label
            :title label
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
  the active-theme `ct`.

  rf2-skhlw2.1 — when the named lifecycle action declares
  `:rf.cofx/requires` (EP-0017 consumer attachment), append a quiet
  `needs <id>` annotation + a `data-{entry,exit}-requires` DOM pin so a
  reader sees the replay-critical host facts the action consumes. `requires`
  is the CLJS vec of compact id strings, or nil — an action with no diet
  renders exactly as before."
  [{:keys [kind name-str requires vc ct]}]
  (let [{:keys [action-pill-height action-pill-pad-x action-pill-px
                action-pill-radius action-caption-px action-caption-gap]} vc
        caption (case kind :entry "Entry actions" :exit "Exit actions")]
    [:div (cond-> {:data-testid (case kind
                                  :entry "rf-mv-chart-state-entry"
                                  :exit  "rf-mv-chart-state-exit")
                   (case kind :entry :data-entry :exit :data-exit) name-str
                   :style {:display        "flex"
                           :flex-direction "column"
                           :gap            (str action-caption-gap "px")}}
            (seq requires)
            (assoc (case kind :entry :data-entry-requires :exit :data-exit-requires)
                   (str/join " " requires)))
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
      name-str]
     ;; rf2-skhlw2.1 — the consumer-attachment requirement annotation: a
     ;; quiet italic `needs <id>` line surfacing the lifecycle action's
     ;; declared `:rf.cofx/requires` (EP-0017). The QUIETEST tier (tertiary
     ;; colour, caption type, italic), subordinate to the action chip;
     ;; rendered ONLY when the action declared a non-empty diet.
     (when (seq requires)
       [:span {:data-testid (case kind
                              :entry "rf-mv-chart-state-entry-requires"
                              :exit  "rf-mv-chart-state-exit-requires")
               :style {:font-family chart-label-stack
                       :font-size   (str action-caption-px "px")
                       :font-weight 400
                       :font-style  "italic"
                       :color       (:text-tertiary ct)
                       :line-height "1"}}
        (str "needs " (str/join ", " requires))])]))

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
        ;; rf2-b4loj — error-terminal KIND. An `:error?` final (a re-frame2
        ;; EXTENSION that routes the spawning parent's `:on-error` rather
        ;; than `:on-done`) paints its outer ring in the error hue so the
        ;; chart does not hide a distinction the framework acts on. NOT
        ;; XState/Stately parity — re-frame2 semantic clarity.
        error-final?   (boolean (.-errorFinal d))
        tags           (js->clj (.-tags d))
        tags-attr      (tag-title-attr tags)
        entry          (.-entry d)
        exit           (.-exit d)
        ;; rf2-skhlw2.1 — the entry / exit lifecycle action's declared
        ;; `:rf.cofx/requires` (EP-0017 consumer attachment), a JS array of
        ;; compact id strings (nil when the action declares no facts).
        entry-reqs     (some-> (.-entryRequires d) js->clj)
        exit-reqs      (some-> (.-exitRequires d) js->clj)
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
       ;; Final-state double-ring (outer). The ✓ glyph is dropped.
       ;; rf2-b4loj — the ring COLOUR is conditional on terminal KIND:
       ;; an `:error?` final paints the static `:final-error` error hue
       ;; (re-frame2 semantic clarity — it routes the parent's `:on-error`);
       ;; a success final keeps the QUIET runtime-coupled `border-col`.
       ;; The main node border (above) STAYS `border-col` either way, so an
       ;; active error-final composes: error ring + runtime main-border,
       ;; neither clobbering the other.
       (when final?
         [:div {:data-testid (str "rf-mv-chart-final-ring-" (.-id props))
                :data-error-final (str error-final?)
                :style {:position      "absolute"
                        :top           "-3px"
                        :left          "-3px"
                        :right         "-3px"
                        :bottom        "-3px"
                        :border        (str "1px solid "
                                            (if error-final?
                                              (:final-error ct)
                                              border-col))
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
            (action-row {:kind :entry :name-str entry :requires entry-reqs :vc vc :ct ct}))
          (when exit
            (action-row {:kind :exit :name-str exit :requires exit-reqs :vc vc :ct ct}))])
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
        ;; rf2-34ff3 — a compound state is a real statechart state, so its
        ;; TITLE STRIP is a clickable `:on-state-click` target ("this
        ;; compound state"). The projector threads `:onClick` onto compound
        ;; `:data` (leaf + compound only); the body stays pointer-transparent
        ;; so child-leaf clicks still pass through.
        on-click (.-onClick d)
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
             ;; rf2-44v8lq — the painted box FILLS the xyflow node box ELK
             ;; sized (`width/height:100%`); it carries NO `min-width` /
             ;; `min-height`. The compound size floor lives in the ELK input
             ;; (`projection/elk-child` seeds every compound at
             ;; `compound-node-min-{width,height}`, and ELK grows the box to
             ;; enclose its laid-out children), so the xyflow box delivered via
             ;; `:style {:width :height}` is already authoritative. A CSS `min-*`
             ;; here SECOND-GUESSED that box: when ELK legitimately shrinks a
             ;; deeply-nested compound below the 260×150 seed to HUG its narrow
             ;; children (hvac `running`/`conditioning`, media `playing`), the
             ;; `min-*` re-inflated the PAINTED div past its allocated box, so
             ;; the border overflowed the box — and visually escaped the parent
             ;; container — while the children (positioned in the real box) sat
             ;; inside. Dropping the `min-*` lets the painted border track the
             ;; box exactly, matching the parallel-region node (which never
             ;; carried a `min-*`, which is why regions always enclosed) and
             ;; Stately's full-enclosure nesting.
             :style {:position         "relative"
                     :width            "100%"
                     :height           "100%"
                     :background       (:container-body-bg ct)
                     :border           (str border-w "px solid " border-col)
                     :border-radius    (str compound-radius "px")
                     :box-shadow       (when active?
                                         (str "0 0 0 2px " (:glow ct)))
                     ;; rf2-shv82 — body stays pointer-transparent so
                     ;; clicks pass through to nested leaves.
                     :pointer-events   "none"}}
       ;; FULL-WIDTH TITLE STRIP at top — solid neutral header band.
       ;; rf2-34ff3 — the title strip is the compound state's clickable
       ;; `:on-state-click` target. It re-enables pointer-events (the body
       ;; sets them to "none" for leaf pass-through), carries the cursor +
       ;; title affordance, and fires `(on-click (js->clj path))` with the
       ;; COMPOUND's own path — exactly mirroring the leaf state-node.
       [:div {:data-testid (str "rf-mv-chart-compound-title-" (.-id props))
              :title (when on-click "Click for details")
              :on-click (when on-click
                          (fn [_ev]
                            (on-click (js->clj path))))
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
                      :text-overflow "ellipsis"
                      ;; rf2-34ff3 — re-enable pointer-events on the strip
                      ;; ONLY (the parent body is `pointer-events:none`); the
                      ;; cursor signals the affordance when wired.
                      :pointer-events "auto"
                      :cursor      (if on-click "pointer" "default")
                      :user-select "none"}}
        label]
       ;; rf2-shv82 — invisible xyflow attachment points.
       (four-cardinal-handles)])))

;; ---- initial marker node ------------------------------------------------

;; rf2-i9d2ob — fixed initial-state glyph geometry.
;;
;; xstate-viz draws the start glyph as a SINGLE FIXED glyph anchored to
;; the node, NOT routed by ELK (`src/InitialEdgeViz.tsx`) — which is
;; exactly why it reads clean in every position. Before this bead xray
;; emitted the dot as one node + a SEPARATE `transition` entry edge that
;; the renderer drew as a `getBezierPath` curve between the dot's source
;; handle and the state's left handle; that bezier bends oddly when the
;; initial lands in an odd position (e.g. traffic's bottom-anchored
;; initials). The fix folds the WHOLE glyph (filled dot + single
;; quadratic Q-hook + a small filled triangle arrowhead) into THIS node's
;; own SVG, drawn in the node's local coordinate frame, so the geometry
;; no longer depends on ELK / xyflow edge routing. The companion entry
;; edge still exists in the projection (every-edge `:data` invariants)
;; but `chart.edges/transition-edge` paints NO visible path for it
;; (`entry?` short-circuit) — the dot + hook + arrow you see is this
;; node alone.
;;
;; The marker node is positioned by `chart.projection` at a FIXED, SMALL
;; offset from its state — `(state.x - initial-marker-x-offset, state.y +
;; initial-marker-y-offset)` — so the glyph reads as a SHORT hook ending
;; just OUTSIDE the state's near (left) edge regardless of where ELK placed
;; the state.
;;
;; rf2-d5s7yg — the glyph's arrow tip is DECOUPLED from the node offset: the
;; node sits `initial-marker-x-offset` (26, rf2-k7kiiq) px left of the state so
;; the dot's CENTRE lands `(offset - dot-x)` px outside the edge — `state.x - 19`
;; at regular density (`offset 26`, `dot-x = pseudo-radius + 1 = 7`) — but the
;; tip is drawn at node-local x = `(offset - tip-gap)` so the ABSOLUTE tip lands
;; at `state.x - tip-gap` — a clean ~`tip-gap`px gap OUTSIDE the edge, pointing
;; AT it, never into the interior. Before rf2-d5s7yg the arm == offset == 48
;; drew the tip at `state.x` (the edge) with a too-long arm; on nested initials
;; xyflow's `:extent "parent"` clamp then shoved it INSIDE the state.

(def ^:private initial-glyph-hook-drop
  "Vertical drop (px) of the Q-hook: the dot sits this far ABOVE the
  arrow row so the single quadratic curve hooks DOWN into the state's
  edge (the xstate-viz down-hook signature). Small + fixed so the glyph
  reads identically in every position."
  9)

(defn initial-marker
  "rf2-az6e2 / rf2-i9d2ob — Reagent component for the machine's initial-
  state pseudo-state marker. The SCXML/xstate UML initial pseudo-state
  glyph: a small NEUTRAL FILLED dot + a short hooked arrow into the
  state's near edge. NOT an accent-blue runtime marker — the initial
  pseudo-state is STATIC topology, so it reads in the neutral pseudo-
  state colour (`:pseudo-marker`), reserving accent/active for runtime
  state.

  rf2-i9d2ob / rf2-d5s7yg — the ENTIRE glyph (dot + Q-hook + triangle
  arrowhead) is drawn here, in the node's own local SVG frame, as ONE fixed
  SHORT hook:

    - a FILLED circle on the left, centred at node-local `dot-x =
      pseudo-radius + 1`. rf2-k7kiiq DISTINGUISHES two radii here: the
      GEOMETRY radius (`:pseudo-radius`, the full radius the arm/arrowhead
      math reads) and the PAINTED radius (`:pseudo-radius − 0.5px`, a tighter
      Stately-aligned dot, −1px diameter — `dot-paint-r`). So the dot's
      GEOMETRY-radius left edge is at node-local x=1 (`dot-x − pseudo-radius`)
      but its PAINTED left edge is at node-local x=1.5 (`dot-x − dot-paint-r`).
      The dot CENTRE lands `(offset − dot-x)` px outside the state's left edge
      — `state.x − 19` at regular density (offset 26, dot-x 7);
    - a single quadratic Bezier (`Q`) hook from the dot, control point
      at `(dot.x, arrow.y)` (the xstate-viz recipe), then a 1px straight
      run into the arrowhead;
    - a small filled triangle arrowhead (the shared `M0,0 L0,10 L10,5 z`
      shape, sized by the `:ah` side `projection/initial-marker-glyph`
      returns — a SMALL Stately-sized head, ≈ `(pseudo-radius − 1)` clamped
      to a 4–6px band, NOT the oversized `:arrow-width-entry` the pre-
      rf2-wwyx1u read used) whose tip lands at node-local x = `(offset -
      tip-gap)`, i.e. absolute `state.x - tip-gap` — a clean ~`tip-gap`px
      GAP just OUTSIDE the state's left edge, pointing AT it.

  rf2-d5s7yg — the tip is DECOUPLED from the node offset: the marker node
  sits `initial-marker-x-offset` (26, rf2-k7kiiq) px left of the state, but the
  tip is drawn at `(offset - tip-gap)` so it ends OUTSIDE the edge, never
  on/through it. Before this bead the arm == offset == 48 put the tip at
  `state.x` (the
  edge) with a too-long arm; nested initials then penetrated once xyflow's
  `:extent \"parent\"` clamp shoved the deeply-negative marker rightward (the
  clamp is dropped in `chart.projection` — only `:parentId` is kept).

  Because the geometry is node-local + fixed, the glyph reads as one tidy
  unit in EVERY position — it never depends on ELK edge routing (the
  bezier-routed entry edge it replaced bent wonky when the initial sat at an
  odd position). The companion entry edge (`chart.projection`) is kept for
  the every-edge `:data` invariants but paints nothing
  (`chart.edges/transition-edge` `entry?` short-circuit).

  Geometry reads off the resolved density's `:pseudo-radius` (the dot
  radius; the arrowhead side `:ah` is derived from it by
  `projection/initial-marker-glyph` — NOT the `:arrow-width-entry` key,
  which sizes the entry-EDGE markerEnd, a separate decorative concern) plus
  the shared `chart.projection` offset/gap; the hue is the `:pseudo-marker`
  token."
  [^js props]
  (let [d        (.-data props)
        vc       (chart-constants d)
        ct       (palette-of d)
        ;; rf2-wwyx1u — only `:pseudo-radius` is read now; the small glyph
        ;; arrowhead is sized off it (the prior `:arrow-width-entry` read
        ;; produced an oversized head — see the `ah` binding below).
        {:keys [pseudo-radius]} vc
        stroke   (:pseudo-marker ct)
        ;; Glyph in node-local coords. Row y=0 is the marker origin
        ;; (== state.y + offset). rf2-d5s7yg — the marker node sits `offset`
        ;; (26) px left of the state (`chart.projection/initial-marker-x-offset`),
        ;; so node-local x=`offset` IS the state's left edge. The arrow tip
        ;; lands at (`offset - gap`, 0) — a clean ~`gap`px gap OUTSIDE the
        ;; edge; the dot CENTRE sits at node-local `dot-x = pseudo-radius + 1`
        ;; (regular x=7, so the centre is `offset - dot-x = 19`px outside the
        ;; edge — absolute `state.x - 19`) and `hook-drop` ABOVE the arrow row
        ;; so the single Q hooks DOWN into the tip (xstate-viz signature).
        drop     initial-glyph-hook-drop
        ;; rf2-k7kiiq — `dot-r` is the GEOMETRY radius (the FULL
        ;; `pseudo-radius`) that feeds the glyph math + the forward-flow
        ;; invariant; the PAINTED circle uses the tighter `dot-paint-r` below.
        dot-r    pseudo-radius
        ;; rf2-k7kiiq — the PAINTED dot radius, 0.5px SMALLER than the
        ;; GEOMETRY radius `dot-r` (−1px diameter) for a tighter Stately-
        ;; aligned dot, WITHOUT touching `dot-r` (the full `pseudo-radius`)
        ;; that feeds the glyph geometry below — so the arm/arrowhead math +
        ;; the forward-flow invariant stay anchored to the full radius. Only
        ;; the painted circle shrinks: its painted LEFT EDGE is at node-local
        ;; `dot-x - dot-paint-r = 1.5` (vs the geometry-radius left edge at
        ;; `dot-x - pseudo-radius = 1`).
        dot-paint-r (- dot-r 0.5)
        ;; rf2-wwyx1u — the glyph geometry is single-sourced from the pure
        ;; `projection/initial-marker-glyph` (so the renderer + the projection
        ;; test agree on the FORWARD-FLOW invariant). It returns a SMALL
        ;; Stately-sized arrowhead (`:ah` ≈ dot diameter / 2, 4px floor) — the
        ;; prior `(max 6 arrow-width-entry)` produced a ~10–13px head that, on
        ;; the SHORTENED glyph, pushed `end-x` LEFT of `dot-x`, ran the hook
        ;; BACKWARDS and let the oversized triangle dominate. With the small
        ;; head + bumped offset `end-x > dot-x`: the single Q hooks dot →
        ;; down-and-RIGHT into the head, which points RIGHT into the edge.
        {:keys [ah tip-x dot-x end-x]} (projection/initial-marker-glyph dot-r)
        ah-half  (/ ah 2.0)
        dot-y    (- drop)            ;; dot above the arrow row
        end-y    0
        hook     (str "M " dot-x "," dot-y
                      ;; xstate-viz recipe: control point at (dot.x, end.y).
                      " Q " dot-x "," end-y " " end-x "," end-y
                      ;; a 1px straight run into the arrowhead base.
                      " L " (+ end-x 1) "," end-y)
        ;; Triangle: base at x = tip-x-ah spanning ±ah-half, tip at (tip-x, 0).
        tri      (str "M " (- tip-x ah) "," (- ah-half)
                      " L " (- tip-x ah) "," ah-half
                      " L " tip-x "," 0 " z")
        ;; SVG canvas width covers the dot (left) through the arrow tip.
        svg-w    (+ tip-x 1)
        ;; Padding so the dot (top, dot-y - dot-r) and the arrowhead
        ;; (±ah-half) are not clipped; SVG y origin shifts by `pad-top`.
        pad-top  (+ drop dot-r 1)
        pad-bot  (+ ah-half 1)
        svg-h    (+ pad-top pad-bot)]
    (r/as-element
      [:div {:data-testid "rf-mv-chart-initial-marker"
             :data-pseudo-kind "initial"
             :style {:display     "inline-flex"
                     :align-items "center"}}
       [:svg {:data-testid "rf-mv-chart-initial-marker-glyph"
              :width  svg-w
              :height svg-h
              ;; viewBox shifted up by pad-top so node-local y=0 (the
              ;; arrow row) sits at the marker origin; `overflow visible`
              ;; lets the short hook end just outside the state's edge.
              :viewBox (str "0 " (- pad-top) " " svg-w " " svg-h)
              :style {:overflow "visible"}}
        ;; FILLED dot — the UML initial pseudo-state.
        [:circle {:data-testid "rf-mv-chart-initial-marker-dot"
                  :cx dot-x :cy dot-y :r dot-paint-r
                  :fill stroke}]
        ;; single Q hook into the arrowhead base.
        [:path {:d hook :stroke stroke :stroke-width 2 :fill "none"}]
        ;; filled triangle arrowhead — its tip points AT the state's left
        ;; edge and stops just OUTSIDE it with a small visible gap (the
        ;; `initial-marker-tip-gap`); it never lands flush or penetrates the
        ;; state (see `chart.projection/initial-marker-glyph`).
        [:path {:d tri :fill stroke}]]
       [:> Handle {:type "source" :position pos-right
                   :style {:opacity 0}}]])))

;; ---- root-container node (rf2-q129z8) -----------------------------------

(defn root-container-node
  "rf2-q129z8 — the synthetic ROOT-CONTAINER frame: the Stately-Studio-style
  NAMED box that wraps the WHOLE machine. xyflow places every top-level node
  (flat states, the `machine-root` chip, parallel regions) inside this
  container via the `parentId` mechanic (the same way `compound-node` holds
  its substates); ELK sizes the frame to HUG its children and reflows it on
  resize, so the frame TRACKS the topology instead of the pre-q129z8
  corner-pinned overlay that stuck to the drawing surface.

  The frame absorbs the old floating root chrome into a proper HEADER:

    - a full-width TITLE STRIP carrying the MACHINE NAME (`:machineName`,
      from the host's `:machine-id`), with a subtle `∥` glyph for a parallel
      root (`:parallel`);
    - an optional CONTEXT band UNDER the title (`:context` — the key→type-
      caption shape the host feeds via `:context-band`), with a provenance
      badge: a quiet `inferred from :data` badge when `:contextInferred` is
      true (the one-sample inference, rf2-5tz9p), or a positive `declared`
      badge when false (the authoritative `:data-schema` shape, rf2-3q4k5b /
      EP-0005).

  Structural chrome, not a state: NEUTRAL border (no runtime accent — the
  projector never marks it `:active`), no `:onClick`. The painted box FILLS
  the xyflow node box ELK sized (`width/height:100%`, no `min-*`), matching
  `compound-node` / `parallel-region-node` so the border tracks the box
  exactly. The body is `pointer-events:none` so clicks fall through to the
  nested states (the header re-enables nothing — the root frame is not
  selectable); the four invisible cardinal handles keep any future
  root-level edge endpoint legal (the same posture as `compound-node`)."
  [^js props]
  (let [d        (.-data props)
        vc       (chart-constants d)
        ct       (palette-of d)
        label    (or (.-machineName d) (.-label d) "machine")
        parallel? (boolean (.-parallel d))
        context  (js->clj (.-context d))
        inferred? (boolean (.-contextInferred d))
        {:keys [compound-radius container-title-height container-title-pad-x
                container-title-px container-divider-width container-body-pad
                stroke-width]} vc
        ;; rf2-8z1rca — the ELK top padding the frame reserves for its
        ;; header: the title strip + a body-pad band + the variable-height
        ;; Context band (`projection/context-band-height` for this context's
        ;; row count). Single-sourced from the SAME helper `->elk-children`
        ;; feeds ELK, so the rendered header and the reservation can never
        ;; drift. Surfaced as `data-reserved-top` so the DOM regression can
        ;; pin "the rendered header fits within what ELK reserved" — i.e. the
        ;; first child laid out at the reserved content edge starts BELOW the
        ;; header, never under the Context band.
        reserved-top (+ container-title-height container-body-pad
                        (projection/context-band-height
                          (if (seq context) (count context) 0)
                          container-divider-width))]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-root-container-" (.-id props))
             :data-node-id (.-id props)
             :data-root-container "true"
             :data-reserved-top (str reserved-top)
             :data-parallel (str parallel?)
             :style {:position       "relative"
                     :width          "100%"
                     :height         "100%"
                     :background     (:container-body-bg ct)
                     :border         (str stroke-width "px solid "
                                          (:container-border ct))
                     :border-radius  (str compound-radius "px")
                     ;; rf2-q129z8 — clicks fall through to nested states.
                     :pointer-events "none"}}
       ;; FULL-WIDTH HEADER — machine name (+ ∥ for parallel) and, under it,
       ;; the optional Context band. The header sits at the top of the frame
       ;; box (ELK reserves the container top padding for it).
       [:div {:data-testid (str "rf-mv-chart-root-container-header-" (.-id props))
              :style {:position "absolute"
                      :top      0
                      :left     0
                      :right    0
                      :display        "flex"
                      :flex-direction "column"}}
        ;; TITLE STRIP.
        [:div {:data-testid (str "rf-mv-chart-root-container-title-" (.-id props))
               :style {:display     "flex"
                       :align-items "center"
                       :gap         "6px"
                       :height      (str container-title-height "px")
                       :padding     (str "0 " container-title-pad-x "px")
                       :background    (:container-header-bg ct)
                       :border-bottom (str container-divider-width "px solid "
                                           (:divider ct))
                       :border-top-left-radius  (str compound-radius "px")
                       :border-top-right-radius (str compound-radius "px")
                       :font-family sans-stack
                       :font-size   (str container-title-px "px")
                       :font-weight 700
                       :letter-spacing "0.02em"
                       :color       (:text-primary ct)
                       :white-space "nowrap"
                       :overflow    "hidden"
                       :text-overflow "ellipsis"
                       :user-select "none"}}
         (when parallel?
           [:span {:data-testid (str "rf-mv-chart-root-container-parallel-glyph-"
                                     (.-id props))
                   :style {:opacity 0.6
                           :font-family sans-stack}}
            "∥"])
         [:span {:style {:overflow "hidden"
                         :text-overflow "ellipsis"}}
          label]]
        ;; CONTEXT band (under the title) — the inferred key→type shape.
        ;; rf2-8z1rca — the band's fixed-pixel geometry is single-sourced
        ;; from the `chart.projection` Context-band constants so the painted
        ;; height and the root-container ELK top-padding RESERVATION
        ;; (`projection/context-band-height`, fed via `->elk-children`) can
        ;; never drift — a band taller than its reservation would lay the
        ;; first child UNDER it (the rf2-8z1rca bug).
        (when (seq context)
          [:div {:data-testid (str "rf-mv-chart-root-container-context-" (.-id props))
                 :data-key-count (str (count context))
                 :style {:display        "flex"
                         :flex-direction "column"
                         :gap            (str projection/context-band-row-gap "px")
                         :padding        (str projection/context-band-pad-top "px "
                                              projection/context-band-pad-x "px "
                                              projection/context-band-pad-bottom "px")
                         :font-family    chart-label-stack
                         :font-size      (str projection/context-band-row-font-px "px")
                         :line-height    projection/context-band-row-line-height
                         :color          (:text-secondary ct)
                         :background      (:container-header-bg ct)
                         :border-bottom   (str container-divider-width "px solid "
                                               (:divider ct))}}
           [:div {:style {:display        "flex"
                          :align-items    "baseline"
                          :gap            "5px"
                          :margin-bottom  (str projection/context-band-header-margin-bottom "px")}}
            [:span {:style {:font-size      "8px"
                            :font-weight    700
                            :letter-spacing "0.06em"
                            :text-transform "uppercase"
                            :color          (:text-tertiary ct)}}
             "Context"]
            ;; rf2-5tz9p / rf2-3q4k5b — the provenance badge. The shape is
            ;; either INFERRED from one sample of the machine's `:data` (the
            ;; quiet caveat badge — a partial initial `:data` can mislead) OR
            ;; AUTHORITATIVE from a declared `:data-schema` (EP-0005). When
            ;; declared, the `inferred from :data` badge is dropped and a
            ;; positive `declared` badge marks the shape as authoritative.
            (if inferred?
              [:span {:data-testid (str "rf-mv-chart-root-container-context-inferred-"
                                        (.-id props))
                      :title "Inferred from one sample of the machine's :data — not a declared schema"
                      :style {:font-size   "8px"
                              :font-weight 500
                              :font-style  "italic"
                              :color       (:text-tertiary ct)}}
               "inferred from :data"]
              [:span {:data-testid (str "rf-mv-chart-root-container-context-declared-"
                                        (.-id props))
                      :title "Declared by the machine's :data-schema — authoritative context shape"
                      :style {:font-size      "8px"
                              :font-weight    600
                              :letter-spacing "0.04em"
                              :color          (:accent ct)}}
               "declared"])]
           (for [[k v] context]
             ^{:key k}
             [:div {:style {:display     "flex"
                            :gap         "5px"
                            :align-items "baseline"
                            :white-space "nowrap"
                            :overflow    "hidden"
                            :text-overflow "ellipsis"}}
              [:span {:style {:color (:accent ct) :font-weight 600}} k]
              [:span {:style {:color (:text-tertiary ct)}} ":"]
              [:span {:style {:overflow "hidden" :text-overflow "ellipsis"}} v]])])]
       (four-cardinal-handles)])))

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

;; ---- history pseudo-state renderer (rf2-m285a — WIRED END-TO-END) -------
;;
;; rf2-az6e2 defined the VISUAL rendering of history pseudo-states
;; (shallow `H` / deep `H*`, small symbolic node inside the owning
;; compound, direct incoming transitions, NO normal state-box styling).
;; rf2-m285a then WIRED first-class history END-TO-END (Spec 005 §History
;; states): `chart.layout/collect-nodes` detects a `:type :history` node
;; and emits a single `{:history? true :deep? <bool> :default-target …}`
;; marker (NEVER occupiable — layout.cljc:360-375); `chart.projection/
;; xyflow-graph` maps a `:history?` node to xyflow type `"history-marker"`
;; and threads `:data {:deep …}` (projection.cljc:731/785); this renderer
;; paints `H` / `H*` (below, :696). History topology is therefore PARSED,
;; EMITTED, and PAINTED — no longer a render-hook awaiting data. Constants
;; (`:pseudo-*`) carry the shallow/deep variant.

(defn history-marker
  "rf2-az6e2 / rf2-m285a — small symbolic pseudo-state node for a history
  marker. Shallow history renders `H`; deep history renders `H*`. Reads
  `:data {:deep <bool>}`. NOT a normal state box — a small neutral
  rounded square so it reads as a pseudo-state inside its owning
  compound. WIRED END-TO-END (see the section comment above): the
  projector emits `{:type \"history-marker\" :data {:deep …}}` for every
  parsed `:type :history` node (Spec 005 §History states) and this
  renderer paints it."
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
;; `initial-marker` nodes; final states paint the quiet doubled ring
;; inline on `state-node` (no glyph — rf2-az6e2 dropped the ✓). The
;; end-state-as-node `[*]` pattern,
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
       ;; rf2-q129z8 — the synthetic ROOT-CONTAINER frame (the Stately-style
       ;; named box wrapping the whole machine; absorbs the old corner-pinned
       ;; root title strip + Context overlay into a proper frame header).
       "root-container"  root-container-node
       "initial-marker"  initial-marker
       ;; rf2-vcnvj — the synthetic root-context chip a machine-level
       ;; (top-level `:on`) fallback routes from (projected once, not
       ;; per-state).
       "machine-root"    machine-root-node
       ;; rf2-m285a — history pseudo-state renderer; the projector emits a
       ;; `history-marker` node for every parsed `:type :history` pseudo-
       ;; state (wired end-to-end; see `history-marker` docstring).
       "history-marker"  history-marker
       "rf2-event"       event-node/event-node})
