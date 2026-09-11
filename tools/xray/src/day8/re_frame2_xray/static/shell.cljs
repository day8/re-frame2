(ns day8.re-frame2-xray.static.shell
  "Xray's Static surface — 3-layer chrome.

  ## Static = Xray-in-a-quieter-key

  Static shares Dynamic's full design language — Inter +
  JetBrains Mono, the complete `theme/tokens.cljc` palette, the 4px
  spacing grid, Lucide-style ASCII glyphs, the 56px ribbon, the 40px
  tab-bar. Differentiation is temperature, not vocabulary.

  ## Surface inventory (3-layer chrome)

  Dynamic is 4 layers (L1 ribbon · L2 event list · L3 tab bar · L4
  detail panel). Static drops L2 — there is no spine in Static mode
  because Static is event-INDEPENDENT — and renders 3 layers:

      ┌───────────────────────────────────────────────────────┐
      │ L1  Top ribbon — mode pill · frame picker · icons     │
      ├───────────────────────────────────────────────────────┤
      │ L3  Tab bar (40px) — 5 tabs                           │
      ├───────────────────────────────────────────────────────┤
      │ L4  Detail panel (fills remaining canvas)             │
      └───────────────────────────────────────────────────────┘

  The L1 frame picker is mode-INDEPENDENT. Per Spec 001 the registrar
  is process-GLOBAL — frames isolate state, not registrations — so
  event / sub / route / interceptor / machine-definition catalogues are
  shared across every frame and read the same regardless of the picker.
  What the picker DOES scope is the genuinely per-frame surfaces each
  panel projects:

    - Machines    — live machine snapshots
                    (`[:rf.runtime/machines :snapshots]` in the target-
                    frame runtime-db); the definition catalogue is global.
    - Flows       — the flows registry is per-frame
                    (`{frame-id {flow-id ...}}`, Spec 013).
    - Schemas     — the app-db-schema side-table is per-frame
                    (`schemas-by-frame`); event/sub specs are global.
    - Routes      — the current-route slice
                    (`[:rf.runtime/routing :current]` in runtime-db) is per-frame;
                    the route-definition catalogue is global.
    - Interceptors— global (interceptor chains live on globally
                    registered events).

  So switching frames changes the per-frame projections above; the
  global catalogues are deliberately cross-frame. The picker uses the
  canonical `frame_switcher/frame-switcher-view` (same contract as
  Dynamic's ribbon); mode toggles preserve the selection.

  L2 is also a functional signal: its absence is one of the four
  stacked mode-signal mechanisms (chrome silhouette) the parent epic
  documents. Together with the cyan left-edge stripe, the mode-pill
  state, and motion dampening, the user reads Static at a glance even
  without looking at the pill.

  ## Tab inventory (5 sub-tabs)

  The Static surface mounts five sub-tabs. Each panel installs
  its own entry into the L4 tab registry:

      Machines (m, default) · Routes (r) · Schemas (c) · Flows (f) ·
      Interceptors (i)

  Tab order + mnemonics per the findings doc §5.2 (mode-scoped: same
  letter, different target per mode — `m` names the Machines instance
  inspector in Dynamic and the Machines registry browse in Static).

  Those letters are LABELS, not keys. They ride the registry entry's
  `:mnem` into each tab button's `title` and are read by nothing else;
  there is no `static/keybinding.cljs`, and the global
  `keybinding.cljs` binds no bare letter to a tab. A tab is reached by
  click or by the command palette's `select-static-tab` verb. See
  spec/018 §2.5 Mnemonic mode-scoping rule + spec/007 §Trimmed pending
  demand.

  Static has no standalone Views or Events sub-tabs — the information
  those would surface already lives in the source code.

  ## Frame isolation

  Same discipline as the Dynamic shell. The Static shell is wrapped
  in `[rf/frame-provider {:frame :rf/xray}]`; every subscribe +
  dispatch inside the shell resolves to `:rf/xray`.

  ## Substrate (rf2-k97c.3)

  The four regions below — [[ribbon]], [[tab-bar]], [[detail-panel]]
  and [[surface]] — are `rf.fresco/defview` BOUNDARIES, not
  `rf/reg-view`s. They read through `rf.fresco/sub` and dispatch
  through `(:dispatch (rf/capture-frame))`, and they resolve their
  frame from REACT CONTEXT — the same context `rf/frame-provider` and
  `rf.fresco/frame-provider` both write — so the chrome renders
  identically under today's Reagent-rendered mount and under the
  Fresco root Xray will eventually own. Nothing here consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  Boundary count tracks READS and head-position use, not file size.
  [[ribbon]] and [[surface]] read nothing and are boundaries anyway:
  [[surface]] because it is what the Dynamic composer mounts (so it is
  where the crossing sits — one bridge for the whole surface) and
  because a boundary is the only legal hiccup head for the three
  regions under it; [[ribbon]] because it carries a dispatcher and
  hosts the two Reagent islands below.

  TWO REAGENT ISLANDS REMAIN, both reached through an `as-child`
  seam — `identity` for a hiccup caller and the node lane,
  `reagent.core/as-element` for a boundary:

    * the L1 ribbon's `frame-switcher/frame-switcher-view` and
      `mode-pill/mode-pill`, both still `rf/reg-view`s. The Dynamic
      `shell.cljs` ribbon is a BOUNDARY as of rf2-k97c.3 and islands
      the same two widgets the same way, so migrating them now deletes
      FOUR islands in one slice — two here and two there — rather than
      two now and two later.
    * [[detail-panel]]'s `[(:panel tab)]`, because
      `panel-registry/reg-l4-tab!`'s `:pre` requires `:panel` to be
      CALLABLE. All five Static panels are boundaries as of PR #9648,
      so every one of them registers an `as-component` BRIDGE — a plain
      fn answering `[:> Component {}]` — rather than the view itself.
      A bridge is a plain fn, which Fresco grades `:invalid` as a head
      down the same arm a `reg-view` goes, so the island stands until
      the registry can take a boundary directly.

  Both are MIGRATION SCAFFOLDING WITH A DEFINED END: the ribbon seam
  goes when those two widgets are boundaries, and the L4 seam goes
  when `reg-l4-tab!` stores boundaries directly — the deletion each
  panel's own bridge comment already promises.

  ## Mode-signal mechanism (4 stacked signals)

  The parent epic locks four signals that telegraph Static state:

    1. **Mode pill** at ribbon-left — mode-`accent` active segment,
       200ms cross-fade. Owned by `static/mode_pill.cljs`. The pill
       lives at ribbon-left in BOTH modes (it's the toggle, not the
       indicator).
    2. **2-px left-edge ribbon stripe** — the single `:accent` (GitHub
       blue) in both modes (the Figma export carries one accent, no
       per-mode colour swap). Owned by both shells via the explicit
       `mode-stripe-colour` arg passed into the ribbon's outer div.
    3. **Motion dampening** — Dynamic ships the LIVE pulse + machine-
       active pulse + 180ms tab fade. Static drops the continuous
       pulses entirely; the 180ms tab fade collapses to 0ms (instant)
       so cluster swaps land without motion.
    4. **Chrome silhouette** — Dynamic is 4-layer; Static is 3-layer
       (no L2 / no spine). The shape itself is a signal.

  ## Tab panels

  Each sub-tab is backed by its own panel:

    - Machines     — registry browse + Topology
    - Routes       — registry browse + Simulate-URL
    - Schemas      — registry browse + sample data
    - Flows        — registry browse
    - Interceptors — lens"
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.substrate :as substrate]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            ;; Static panel views (Machines / Routes / Schemas /
            ;; Flows / Interceptors) are pulled in via the L4 tab
            ;; registry — each panel's `install!` registers
            ;; `{:panel <view-fn>}` with `panel-registry/reg-l4-tab!`
            ;; and `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id :static`. The shell does not
            ;; require those panel nses directly.
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens type-scale layout sans-stack]]))

;; ---- tab inventory ------------------------------------------------------
;;
;; The Static-mode L3 tab inventory lives in the internal
;; `panel-registry`. Each Static panel's `install!` registers
;; its own tab metadata (`{:modes #{:static} :order ...}`); the
;; helpers below read the registry so external callers (registry.cljs
;; for `:rf.xray.static/select-tab`'s contains? guard, tests asserting
;; the canonical order) see one source of truth.

(defn tabs
  "Ordered Static-mode tab entries. Each entry carries `:id`,
  `:label`, `:mnem`, `:modes`, `:order`, and `:panel`. Order matches
  the parent-epic findings doc `2026-05-19-xray-explorer-mode.md` §2.4
  — machines, routes, schemas, views, flows, events.

  Default landing tab is `:machines` per Mike's call (the densest
  Static surface; opening Static on a fresh slate should land on the
  highest-value tab) — see `default-tab` below.

  A zero-arg `defn` reading the registry. Callers must invoke
  `(static-shell/tabs)` not bare `static-shell/tabs`."
  []
  (panel-registry/tabs-for-mode :static))

(def default-tab
  "Default landing tab when `:rf.xray.static/selected-tab` is unset.
  Pinned to `:machines` per the parent-epic findings doc — the densest
  Static surface is the highest-value landing."
  :machines)

(defn tab-ids
  "Set of valid Static tab ids — used by `:rf.xray.static/select-tab`
  to reject unknown values from the dispatch arg. Reads through the
  registry so a new `reg-l4-tab!` is picked up without modifying this
  ns."
  []
  (panel-registry/tab-ids-for-mode :static))

;; ---- mode signal #2 — left-edge stripe colour ---------------------------

(def dynamic-stripe-token
  "Token-key for the Dynamic mode's 2-px left-edge ribbon stripe per
  the parent-epic mode-signal mechanism (signal #2). Held as a token
  KEY (not the resolved hex) so per-theme palette switching (light
  theme) flows through naturally.

  The Figma export carries a SINGLE accent (GitHub blue), so the
  stripe paints `:accent` in BOTH modes — there is no per-mode accent
  colour swap. The Dynamic/Static MODE drives motion/pulse, not stripe
  colour."
  :accent)

(def static-stripe-token
  "Token-key for the Static mode's 2-px left-edge ribbon stripe per
  the parent-epic mode-signal mechanism (signal #2). The single
  `:accent` (GitHub blue) — same as Dynamic; the Figma export has no
  per-mode accent colour swap."
  :accent)

(defn stripe-token-for-mode
  "Pure helper. Returns the token KEY (`:accent`) the L1 ribbon should
  paint as its 2-px left-edge stripe for the given mode. Both modes
  resolve to the single `:accent` (the Figma export's one blue
  accent). JVM-portable so the test corpus can cover the round-trip
  without a CLJS runtime."
  [mode]
  (case mode
    :static  static-stripe-token
    dynamic-stripe-token))

(defn stripe-hex-for-mode
  "Resolve the mode's stripe token through `tokens` to the rendered
  hex. CLJS-side helper that closes over the current `tokens` (the
  dark palette today; the light-theme path overlays via CSS custom
  properties)."
  [mode]
  (get tokens (stripe-token-for-mode mode)))

;; ---- L1 ribbon (Static) -------------------------------------------------

(defn- ribbon-right-icons
  "Right-icons cluster — `⚙` settings · `✕` close. Same content as the
  Dynamic ribbon (`shell.cljs/ribbon-right-icons`) but inlined here so
  the Static shell stays self-contained and we don't form a cycle by
  reaching back into the Dynamic ns.

  `dispatch` is the frame-aware dispatcher captured by the
  [[ribbon]] boundary's body so the settings / close clicks land on the
  surrounding instance frame, not a `{:frame :rf/xray}` literal."
  [dispatch]
  (let [icon-style {:background     "transparent"
                    :border         "none"
                    :color          (:text-secondary tokens)
                    :cursor         "pointer"
                    :font-size      (:body type-scale)
                    :padding        "2px 6px"}]
    [:div {:data-testid "rf-xray-static-ribbon-icons"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     [:button {:data-testid "rf-xray-static-icon-settings"
               :title       "Settings (,)"
               :aria-label  "Open Xray settings"
               :on-click    #(dispatch [:rf.xray/settings-open])
               :style       icon-style}
      "⚙"]
     [:button {:data-testid "rf-xray-static-icon-close"
               :title       "Close (Ctrl+Shift+C)"
               :aria-label  "Close Xray"
               :on-click    #(dispatch [:rf.xray/close-shell])
               :style       icon-style}
      "✕"]]))

(defn ribbon-tree
  "The Static L1 ribbon's WHOLE chrome, as a pure function of the
  frame-bound `dispatch` and the `as-child` spelling for the two
  REAGENT ISLANDS (see the ns docstring).

  SPLIT OUT OF [[ribbon]] BY rf2-k97c.3, and the split is `defview`'s
  own documented extract-a-helper spelling rather than an invention: a
  boundary's body may only run inside a React render window, so
  `(ribbon)` is no longer a callable that answers hiccup — while the
  chrome itself is ordinary data → data and is worth walking in the fast
  node lane.

  `as-child` is `identity` for a hiccup caller (the node lane, and any
  Reagent caller), which leaves each island a fn-headed hiccup vector
  exactly as it has always been; the boundary passes
  `reagent.core/as-element`, which answers a React element — a legal
  child anywhere per Fresco's component ABI.

  PURE: `ribbon-right-icons` is CALLED rather than headed, and answers
  keyword hiccup all the way down."
  [dispatch as-child]
  [:div {:data-testid "rf-xray-static-ribbon"
         :style {:display          "flex"
                 :align-items      "center"
                 :justify-content  "space-between"
                 :gap              "12px"
                 :height           (:top-strip-height layout)
                 :padding          "0 12px"
                 :background       (:bg-1 tokens)
                 :border-bottom    (str "1px solid " (:border-subtle tokens))
                 :border-left      (str "2px solid " (stripe-hex-for-mode :static))
                 :font-family      sans-stack
                 :font-size        (:body type-scale)}}
   ;; LEFT cluster — scope selectors (Frame + Dynamic/Static), mirroring
   ;; the Dynamic chrome ribbon's left cluster. The frame
   ;; picker is mode-INDEPENDENT — same contract as Dynamic's ribbon
   ;; (`shell.cljs/ribbon`): reads `:rf.xray/current-frame` +
   ;; `:rf.xray/available-frames`, writes via `:rf.xray/select-frame`.
   ;; The picker persists across mode toggles so the user keeps browsing
   ;; the same frame's registrations as they flip between event-coupled
   ;; (Dynamic) and event-independent (Static) lenses.
   [:div {:data-testid "rf-xray-static-ribbon-selectors"
          :style {:display "flex" :align-items "center" :gap "8px"}}
    ;; rf2-k97c.3 — the two REAGENT ISLANDS. Both are still
    ;; `rf/reg-view`s, which grade `:invalid` as a Fresco head down the
    ;; same arm a plain `defn` does, and both are ALSO headed by the
    ;; Dynamic `shell.cljs` ribbon. See the ns docstring.
    (as-child [frame-switcher/frame-switcher-view])
    (as-child [mode-pill/mode-pill])]
   [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
    (ribbon-right-icons dispatch)]])

(rf.fresco/defview ribbon
  "L1 ribbon — 56px chrome, Static-flavoured, and a FRESCO BOUNDARY
  (rf2-k97c.3) rather than an `rf/reg-view`. Per the parent-epic
  mode-signal mechanism the ribbon paints a 2-px left-edge stripe in
  the single `:accent` (GitHub blue), same in both modes. Mode pill
  sits at ribbon-left; the L1 frame-switcher sits between mode-pill and
  the right-icons cluster; right-icons (Settings · Close) sit at
  ribbon-right.

  The frame picker is MODE-INDEPENDENT — Static is also frame-scoped
  (registrations — events · subs · machines · routes · schemas · flows
  · interceptors — live in a particular frame, so the user must be
  able to pick which frame they are browsing). Reaching through
  `frame_switcher.cljs` (the canonical contract surface) keeps Static
  on the same picker as Dynamic; mode toggles preserve the selection.
  Dynamic's nav cluster (`[◀ ▶ ⏭]`) and filter pills remain HIDDEN in
  Static — those clusters are spine-coupled and have no meaning in an
  event-independent surface.

  IT READS NOTHING. It is a boundary because it carries the DISPATCHER
  the settings / close icons need — `(:dispatch (rf/capture-frame))`,
  core's own door, which answers the boundary's DECLARED frame inside a
  body and replaces the `dispatch` the `reg-view` body used to inject
  lexically. Same guarantee, one call, and it is the spelling every
  migrated view now uses.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[surface]] mounts it with none, so it is destructured away."
  [_props]
  (ribbon-tree (:dispatch (rf/capture-frame)) substrate/as-element))

;; ---- L3 tab bar (Static) ------------------------------------------------

(defn- tab-button
  "One Static tab. Same `●` / `○` glyph language as the Dynamic
  `tab-button` (`shell.cljs/tab-button`) — design language is shared
  under the 'Xray-in-a-quieter-key' framing.

  Per the canonical chrome ARIA pattern, the tab carries `role='tab'` +
  `aria-selected` so assistive tech reads it as a tab, not a generic
  button."
  [dispatch {:keys [id label mnem active?]}]
  ;; `dispatch` is the frame-bound dispatcher [[tab-bar]]'s boundary body
  ;; captures and threads in here. A plain fn invoked as a Reagent
  ;; component would render in its OWN cycle with no `:contextType`, so
  ;; `current-frame-id` would resolve nil and an ambient dispatch would
  ;; RAISE `:rf.error/no-frame-context` — there is no `:rf/default` floor
  ;; under EP-0002 (Spec 006 §Plain-fn footgun). Threading the captured
  ;; `dispatch` is the reliable path, and under a Fresco boundary it is
  ;; the only one — an AMBIENT dispatch inside a boundary body is a loud
  ;; refusal too.
  ;;
  ;; rf2-k97c.3 (RULING 2) — the React `:key` rides in this button's own
  ;; ATTRIBUTE MAP. It used to be `^{:key id}` reader metadata on the
  ;; call site's vector literal, which Reagent reads and Fresco's codec
  ;; reads NOWHERE, so it would have reached React as no key at all once
  ;; the tab bar rendered through a boundary. The key EXPRESSION is
  ;; unchanged; the button is the seq element, so the key belongs on it
  ;; and no extra node is needed to carry one.
  (let [glyph    (if active? "◉" "○")
        color    (if active? (:text-primary tokens) (:text-secondary tokens))
        ;; Mirror the Dynamic tab-button pattern: stable
        ;; tab-id + matching tabpanel id so the L4 panel's
        ;; `aria-labelledby` resolves.
        tab-id   (str "rf-xray-static-tab-button-" (name id))
        panel-id (str "rf-xray-static-tabpanel-" (name id))]
    [:button {:key           id
              :data-testid   (str "rf-xray-static-tab-" (name id))
              :id            tab-id
              :role          "tab"
              :aria-selected (if active? "true" "false")
              :aria-controls panel-id
              :on-click      (fn [_]
                               (dispatch [:rf.xray.static/select-tab id]))
              :title         (str label " (" mnem ")")
              :aria-label    (str "Static " label " tab")
              :style {:background    "transparent"
                      :border        "none"
                      :border-bottom (if active?
                                       (str "2px solid " (:accent tokens))
                                       "2px solid transparent")
                      :color         color
                      :cursor        "pointer"
                      :padding       "6px 12px"
                      :font-family   sans-stack
                      :font-size     (:body type-scale)
                      :font-weight   (if active? 600 400)
                      :white-space   "nowrap"}}
     ;; `aria-hidden` on decorative ●/○ glyph.
     [:span {:aria-hidden "true"
             :style {:color (if active?
                              (:accent tokens)
                              (:text-tertiary tokens))
                     :margin-right "4px"}}
      glyph]
     label]))

(defn tab-bar-tree
  "The Static L3 tab bar's WHOLE chrome, as a pure function of the
  frame-bound `dispatch` and the selected tab id.

  SPLIT OUT OF [[tab-bar]] BY rf2-k97c.3, for the reason every migrated
  view splits: a boundary's body may only run inside a React render
  window, so `(tab-bar)` is no longer a callable that answers hiccup.

  PURE: `tab-button` is CALLED rather than headed, and answers keyword
  hiccup. The tab INVENTORY still comes from `(tabs)` — the registry
  read this bar has always made, and process-global rather than
  frame-scoped."
  [dispatch selected]
  [:div {:data-testid "rf-xray-static-tab-bar"
         :role        "tablist"
         :aria-label  "Xray Static-mode panel tabs"
         :style {:display       "flex"
                 :align-items   "center"
                 :gap           "4px"
                 :height        "40px"
                 :padding       "0 8px"
                 :background    (:bg-1 tokens)
                 :border-top    (str "1px solid " (:border-subtle tokens))
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   ;; iterate the registry's static-mode entries. The `:key` rides in
   ;; each button's attribute map — see `tab-button` (rf2-k97c.3).
   (for [{:keys [id] :as tab} (tabs)]
     (tab-button dispatch (assoc tab :active? (= id selected))))])

(rf.fresco/defview tab-bar
  "L3 tab bar — five Static tabs, and a FRESCO BOUNDARY (rf2-k97c.3)
  rather than an `rf/reg-view`. Same height (40px), same row anatomy,
  same ARIA pattern as the Dynamic tab-bar. The selected-tab slot is
  Static-scoped (`:rf.xray.static/selected-tab`) so flipping modes
  doesn't clobber the Dynamic tab choice and vice-versa.

  The READ is `rf.fresco/sub`, a plain call the shipped collector
  records an edge for — no deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value. That is the third of the epic's three
  couplings, and the one a first-paint smoke test cannot see.

  It is its OWN boundary rather than folded into [[surface]] because
  the two read different things at different rates: hoisting this read
  up would re-render the whole Static surface — L4 panel included — on
  every tab click. Boundary count tracks reads.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[surface]] mounts it with none, so it is destructured away."
  [_props]
  (tab-bar-tree (:dispatch (rf/capture-frame))
                (rf.fresco/sub [:rf.xray.static/selected-tab])))

;; ---- L4 detail panel ----------------------------------------------------
;;
;; All five Static sub-tabs ship a real panel. `detail-panel` always
;; mounts the selected tab's `:panel` view; an unknown `:selected-tab`
;; falls through to the unknown-tab stub.

(defn detail-panel-tree
  "The Static L4 detail panel's WHOLE chrome, as a pure function of the
  selected tab id, that tab's registry entry (or `nil`) and the
  `as-child` spelling for the L4 REAGENT ISLAND.

  SPLIT OUT OF [[detail-panel]] BY rf2-k97c.3, for the reason every
  migrated view splits: a boundary's body may only run inside a React
  render window.

  THE MOUNT IS AN ISLAND, and deliberately. `reg-l4-tab!`'s `:pre`
  requires `:panel` to be CALLABLE, so ALL FIVE Static panels register
  an `as-component` BRIDGE — a plain fn answering `[:> Component {}]` —
  rather than the boundary itself (`static.routes.panel/Panel`, the last
  `rf/reg-view` of the five, became a boundary behind its own bridge in
  PR #9648). A plain fn grades `:invalid` as a Fresco head, so the
  island stands. `as-child` is `identity` for a hiccup caller and the
  node lane, which leaves `[(:panel tab)]` exactly the vector it has
  always been, and `reagent.core/as-element` for the boundary, which
  answers a React element — a legal child anywhere per Fresco's
  component ABI, and the crossing every Static panel's own bridge
  comment already describes.

  MIGRATION SCAFFOLDING WITH A DEFINED END: when `reg-l4-tab!` stores
  boundaries directly the `[:>]` bridges go, and this seam goes with
  them. Widening that `:pre` is a registry change with both shells'
  panels behind it, so it is not this slice's."
  [selected tab as-child]
  [:div {:data-testid (str "rf-xray-static-detail-panel-" (name selected))
           ;; Static L4 closes the tab/tabpanel loop.
           ;; Pairs with the per-tab `id` set by `tab-button` so
           ;; assistive tech reads the panel as "labelled by <tab
           ;; name>". Same shape as the Dynamic detail-panel.
           :id              (str "rf-xray-static-tabpanel-" (name selected))
           :role            "tabpanel"
           :aria-labelledby (str "rf-xray-static-tab-button-" (name selected))
           :style {:flex        "1 1 auto"
                   :min-height  "0"
                   :overflow    "auto"
                   :background  (:bg-2 tokens)
                   :color       (:text-primary tokens)}}
     (if tab
       (as-child [(:panel tab)])
       [:div {:data-testid "rf-xray-static-tab-unknown"
              :style {:padding     "16px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack}}
        "Unknown Static tab: " [:code (pr-str selected)]])])

(rf.fresco/defview detail-panel
  "L4 detail panel — registry-driven mount, and a FRESCO BOUNDARY
  (rf2-k97c.3) rather than an `rf/reg-view`.

  Each Static panel's `install!` registers its tab entry via
  `panel-registry/reg-l4-tab!` with `:modes #{:static}` + a `:panel`
  view fn. The Static-mode L4 tabs are:

    :machines     → `static.machines.panel/panel`
    :routes       → `static.routes.panel/Panel`
    :schemas      → `static.schemas.panel/Panel`
    :flows        → `static.flows.panel/Panel`
    :interceptors → `static.interceptors.panel/Panel`

  The READ is `rf.fresco/sub`. It is its OWN boundary rather than
  folded into [[surface]] for the reason [[tab-bar]] is: the L4 mount
  is the most expensive subtree in the surface, and hoisting the tab
  read above it would re-render the ribbon and the tab bar with it.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[surface]] mounts it with none, so it is destructured away."
  [_props]
  (let [selected (or (rf.fresco/sub [:rf.xray.static/selected-tab])
                     default-tab)]
    (detail-panel-tree selected
                       (panel-registry/tab-by-id :static selected)
                       substrate/as-element)))

;; ---- Static surface ------------------------------------------------------

(defn surface-tree
  "The Static surface's outer envelope, as a pure function of its three
  already-composed layers. Genuinely shared between the two lanes
  rather than reproduced for them: [[surface]] passes the three
  BOUNDARY-headed vectors and `test-helpers.static-shell-tree` passes
  the three layers' already-expanded plain hiccup, so a node-lane row
  that walks this envelope walks the real thing.

  SPLIT OUT OF [[surface]] BY rf2-k97c.3."
  [ribbon* tab-bar* detail-panel*]
  [:div {:data-testid "rf-xray-static-surface"
         :data-rf-xray-mode "static"
         :style {:display          "flex"
                 :flex-direction   "column"
                 :flex             "1 1 auto"
                 :min-height       "0"
                 :background       (:bg-0 tokens)
                 :color            (:text-primary tokens)
                 :font-family      sans-stack
                 :font-size        (:body type-scale)}}
   ribbon*
   tab-bar*
   detail-panel*])

(rf.fresco/defview surface
  "The full Static surface — 3 stacked layers (ribbon · tab bar ·
  detail panel), and a FRESCO BOUNDARY (rf2-k97c.3) rather than an
  `rf/reg-view`. The Static surface plugs into the Dynamic shell's
  outer envelope (`shell.cljs/shell-view`) which owns the
  frame-provider + global-styles install + modal mounts; this surface
  just renders the chrome that swaps in when Static mode is active.

  IT READS NOTHING, and it is still a boundary for the two reasons the
  ns docstring gives: it is what the Dynamic composer mounts, so it is
  where the Reagent→Fresco crossing sits — one bridge for the whole
  surface — and a boundary is the only legal hiccup head for the three
  region boundaries below it.

  The argument is the ordinary one-props-map vector every `defview`
  takes. `shell.cljs`'s `surface-composer` mounts it with none, so it is
  destructured away."
  [_props]
  (surface-tree [ribbon {}] [tab-bar] [detail-panel]))

;; ---- THE MIGRATION BRIDGE IS GONE (rf2-k97c.3) ---------------------------
;;
;; #9644 shipped `surface-component` + a PUBLIC `surface-bridge` here,
;; because `shell.cljs`'s `surface-composer` was an `rf/reg-view` and a
;; React component is not a legal hiccup head in a Reagent tree. That
;; comment named its own end condition: "when `shell.cljs` is itself a
;; Fresco tree, `surface-composer` heads `surface` directly, `[:>]` goes,
;; and both defs below are deleted."
;;
;; `surface-composer` IS a `rf.fresco/defview` now, so it heads
;; `[static-shell/surface {}]` directly and both defs are deleted. The
;; ONE crossing into this tree moved up a level to `shell.cljs`'s own
;; private `surface-bridge`, which is what `shell-view` — still the
;; Reagent root the installed adapter renders — mounts. When the epic's
;; coupling (1) is severed and `mount.cljs` owns a Fresco root, that last
;; bridge goes too.
