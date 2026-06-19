(ns day8.re-frame2-xray.static.shell
  "Xray's Static surface — 3-layer chrome (rf2-o5f5f.1).

  ## Static = Xray-in-a-quieter-key

  Per the parent epic rf2-o5f5f architectural lock + the audit at
  rf2-zhrwo: Static shares Dynamic's full design language — Inter +
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

  The Static surface mounts five sub-tabs. Each sibling bead installs
  its own panel into the L4 tab registry (rf2-2moh1):

      Machines (m, default) · Routes (r) · Schemas (c) · Flows (f) ·
      Interceptors (i)

  Tab order + mnemonics per the findings doc §5.2 (mode-scoped: same
  letter, different target per mode — `m` in Dynamic opens the
  Machines instance inspector, `m` in Static opens the Machines
  registry browse).

  rf2-b2fif removed the standalone Static Views + Events sub-tabs —
  the info those sub-tabs surfaced is already in the source code; the
  tabs were not pulling their weight.

  Tab-mnemonic mode-scoping lives in `static/keybinding.cljs`
  follow-on — Phase 1 ships only the click path.

  ## Frame isolation

  Same discipline as the Dynamic shell. The Static shell is wrapped
  in `[rf/frame-provider-existing {:frame :rf/xray}]`; every subscribe +
  dispatch inside the shell resolves to `:rf/xray`. Every subscribing
  region is `reg-view`-registered so its rendered component carries
  `:contextType frame-context` (rf2-in6l2 + Spec 004 §Plain Reagent
  fns do not pick up the surrounding frame).

  ## Mode-signal mechanism (4 stacked signals)

  The parent epic locks four signals that telegraph Static state:

    1. **Mode pill** at ribbon-left — mode-`accent` active segment,
       200ms cross-fade. Owned by `static/mode_pill.cljs`. The pill
       lives at ribbon-left in BOTH modes (it's the toggle, not the
       indicator).
    2. **2-px left-edge ribbon stripe** — the single `:accent` (GitHub
       blue) in both modes (rf2-ad7zx.13: the Figma export carries one
       accent, no per-mode colour swap). Owned by both shells via the
       explicit `mode-stripe-colour` arg passed into the ribbon's outer
       div.
    3. **Motion dampening** — Dynamic ships the LIVE pulse + machine-
       active pulse + 180ms tab fade. Static drops the continuous
       pulses entirely; the 180ms tab fade collapses to 0ms (instant)
       so cluster swaps land without motion.
    4. **Chrome silhouette** — Dynamic is 4-layer; Static is 3-layer
       (no L2 / no spine). The shape itself is a signal.

  ## Sibling beads filling the tab inventory

  Each sibling bead owned its sub-tab panel:

    - rf2-o5f5f.2 — Machines registry browse + Topology
    - rf2-o5f5f.3 — Routes registry browse + Simulate-URL
    - rf2-o5f5f.4 — Schemas registry browse + sample data
    - rf2-uhsqb   — Flows registry browse
    - rf2-o5f5f.6 — Interceptors lens"
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            ;; Static panel views (Machines / Routes / Schemas / Views /
            ;; Flows) are pulled in via the L4 tab registry — each
            ;; panel's `install!` registers `{:panel <view-fn>}` with
            ;; `panel-registry/reg-l4-tab!` (rf2-2moh1) and
            ;; `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id :static`. The shell no longer
            ;; requires those panel nses directly.
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens type-scale layout sans-stack]]))

;; ---- tab inventory ------------------------------------------------------
;;
;; Per rf2-2moh1 the Static-mode L3 tab inventory now lives in the
;; internal `panel-registry`. Each Static panel's `install!` registers
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

  Per rf2-2moh1 this changed shape from a literal `def` vector to a
  zero-arg `defn` reading the registry. Callers must invoke
  `(static-shell/tabs)` not bare `static-shell/tabs` — `:no-back-
  compat` pre-alpha posture."
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
  KEY (not the resolved hex) so per-theme palette switching (rf2-
  5kfxe.6 light theme) flows through naturally.

  Post rf2-ad7zx.13 the Figma export carries a SINGLE accent (GitHub
  blue), so the stripe paints `:accent` in BOTH modes — there is no
  per-mode accent colour swap. The Dynamic/Static MODE stays functional
  (it drives motion/pulse), it just no longer drives stripe colour."
  :accent)

(def static-stripe-token
  "Token-key for the Static mode's 2-px left-edge ribbon stripe per
  the parent-epic mode-signal mechanism (signal #2). Post rf2-ad7zx.13
  this is the single `:accent` (GitHub blue) — same as Dynamic; the
  Figma export has no per-mode accent colour swap."
  :accent)

(defn stripe-token-for-mode
  "Pure helper. Returns the token KEY (`:accent`) the L1 ribbon should
  paint as its 2-px left-edge stripe for the given mode. Post
  rf2-ad7zx.13 both modes resolve to the single `:accent` (the Figma
  export's one blue accent). JVM-portable so the test corpus can cover
  the round-trip without a CLJS runtime."
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

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  `ribbon` reg-view body so the settings / close clicks land on the
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

(rf/reg-view ribbon
  "L1 ribbon — 56px chrome, Static-flavoured. Per parent-epic rf2-
  o5f5f mode-signal mechanism the ribbon paints a 2-px left-edge
  stripe in CYAN (Static) vs VIOLET (Dynamic). Mode pill sits at
  ribbon-left; the L1 frame-switcher sits between mode-pill and the
  right-icons cluster; right-icons (Settings · Close) sit at ribbon-
  right.

  The frame picker is MODE-INDEPENDENT — Static is also frame-scoped
  (registrations — events · subs · machines · routes · schemas · flows
  · interceptors — live in a particular frame, so the user must be
  able to pick which frame they are browsing). Reaching through
  `frame_switcher.cljs` (the canonical contract surface) keeps Static
  on the same picker as Dynamic; mode toggles preserve the selection.
  Dynamic's nav cluster (`[◀ ▶ ⏭]`) and filter pills remain HIDDEN in
  Static — those clusters are spine-coupled and have no meaning in an
  event-independent surface.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  [_props]
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
   ;; the Dynamic chrome ribbon's left cluster (rf2-4vp5j). The frame
   ;; picker is mode-INDEPENDENT — same contract as Dynamic's ribbon
   ;; (`shell.cljs/ribbon`): reads `:rf.xray/current-frame` +
   ;; `:rf.xray/available-frames`, writes via `:rf.xray/select-frame`.
   ;; The picker persists across mode toggles so the user keeps browsing
   ;; the same frame's registrations as they flip between event-coupled
   ;; (Dynamic) and event-independent (Static) lenses.
   [:div {:data-testid "rf-xray-static-ribbon-selectors"
          :style {:display "flex" :align-items "center" :gap "8px"}}
    [frame-switcher/frame-switcher-view]
    [mode-pill/mode-pill]]
   [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
    [ribbon-right-icons dispatch]]])

;; ---- L3 tab bar (Static) ------------------------------------------------

(defn- tab-button
  "One Static tab. Same `●` / `○` glyph language as the Dynamic
  `tab-button` (`shell.cljs/tab-button`) — design language is shared
  per the rf2-zhrwo audit's 'Xray-in-a-quieter-key' framing.

  Per the canonical chrome ARIA pattern (rf2-lvf8t / rf2-q7who Thread
  B), the tab carries `role='tab'` + `aria-selected` so assistive
  tech reads it as a tab, not a generic button."
  [dispatch {:keys [id label mnem active?]}]
  ;; rf2-nesy9 — `dispatch` is the frame-bound `dispatch` injected by
  ;; the `tab-bar` reg-view body and threaded in here (a plain fn
  ;; invoked as a Reagent component renders in its OWN cycle, so
  ;; `current-frame-id` would fall through to :rf/default — threading the
  ;; injected `dispatch` is the reliable path).
  (let [glyph    (if active? "◉" "○")
        color    (if active? (:text-primary tokens) (:text-secondary tokens))
        ;; rf2-plajx — mirror the Dynamic tab-button pattern: stable
        ;; tab-id + matching tabpanel id so the L4 panel's
        ;; `aria-labelledby` resolves.
        tab-id   (str "rf-xray-static-tab-button-" (name id))
        panel-id (str "rf-xray-static-tabpanel-" (name id))]
    [:button {:data-testid   (str "rf-xray-static-tab-" (name id))
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
     ;; rf2-vxpq1 — `aria-hidden` on decorative ●/○ glyph.
     [:span {:aria-hidden "true"
             :style {:color (if active?
                              (:accent tokens)
                              (:text-tertiary tokens))
                     :margin-right "4px"}}
      glyph]
     label]))

(rf/reg-view tab-bar
  "L3 tab bar — five Static tabs. Same height (40px), same row anatomy,
  same ARIA pattern as the Dynamic tab-bar. The selected-tab slot is
  Static-scoped (`:rf.xray.static/selected-tab`) so flipping modes
  doesn't clobber the Dynamic tab choice and vice-versa.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  []
  (let [selected @(rf/subscribe [:rf.xray.static/selected-tab])]
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
     ;; rf2-2moh1 — iterate the registry's static-mode entries.
     (for [{:keys [id] :as tab} (tabs)]
       ^{:key id}
       [tab-button dispatch (assoc tab :active? (= id selected))])]))

;; ---- L4 detail panel ----------------------------------------------------
;;
;; rf2-o5f5f roll-out complete (rf2-sdqsla): all five Static sub-tabs ship
;; a real panel, so the unfilled-tab `placeholder-card` scaffold was
;; removed. `detail-panel` always mounts the selected tab's `:panel` view;
;; an unknown `:selected-tab` falls through to the unknown-tab stub.

(rf/reg-view detail-panel
  "L4 detail panel — registry-driven mount (rf2-2moh1).

  Each Static panel's `install!` registers its tab entry via
  `panel-registry/reg-l4-tab!` with `:modes #{:static}` + a `:panel`
  view fn. The Static-mode L4 tabs are:

    :machines     → `static.machines.panel/panel`         (rf2-o5f5f.2)
    :routes       → `static.routes.panel/Panel`           (rf2-o5f5f.3)
    :schemas      → `static.schemas.panel/Panel`          (rf2-o5f5f.4)
    :flows        → `static.flows.panel/Panel`            (rf2-uhsqb)
    :interceptors → `static.interceptors.panel/Panel`     (rf2-o5f5f.6)

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  []
  (let [selected (or @(rf/subscribe [:rf.xray.static/selected-tab])
                     default-tab)
        tab      (panel-registry/tab-by-id :static selected)]
    [:div {:data-testid (str "rf-xray-static-detail-panel-" (name selected))
           ;; rf2-plajx — Static L4 closes the tab/tabpanel loop.
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
       [(:panel tab)]
       [:div {:data-testid "rf-xray-static-tab-unknown"
              :style {:padding     "16px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack}}
        "Unknown Static tab: " [:code (pr-str selected)]])]))

;; ---- Static surface ------------------------------------------------------

(rf/reg-view surface
  "The full Static surface — 3 stacked layers (ribbon · tab bar ·
  detail panel). The Static surface plugs into the Dynamic shell's
  outer envelope (`shell.cljs/shell-view`) which owns the
  frame-provider + global-styles install + modal mounts; this surface
  just renders the chrome that swaps in when Static mode is active.

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region."
  []
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
   [ribbon {}]
   [tab-bar]
   [detail-panel]])
