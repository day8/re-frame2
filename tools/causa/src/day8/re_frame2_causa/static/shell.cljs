(ns day8.re-frame2-causa.static.shell
  "Causa's Static surface — 3-layer chrome (rf2-o5f5f.1).

  ## Static = Causa-in-a-quieter-key

  Per the parent epic rf2-o5f5f architectural lock + the audit at
  rf2-zhrwo: Static shares Runtime's full design language — Inter +
  JetBrains Mono, the complete `theme/tokens.cljc` palette, the 4px
  spacing grid, Lucide-style ASCII glyphs, the 56px ribbon, the 40px
  tab-bar. Differentiation is temperature, not vocabulary.

  ## Surface inventory (3-layer chrome)

  Runtime is 4 layers (L1 ribbon · L2 event list · L3 tab bar · L4
  detail panel). Static drops L2 — there is no spine in Static mode
  because Static is event-INDEPENDENT — and renders 3 layers:

      ┌───────────────────────────────────────────────────────┐
      │ L1  Top ribbon (56px) — mode pill + right icons       │
      ├───────────────────────────────────────────────────────┤
      │ L3  Tab bar (40px) — 5 tabs                           │
      ├───────────────────────────────────────────────────────┤
      │ L4  Detail panel (fills remaining canvas)             │
      └───────────────────────────────────────────────────────┘

  L2 is also a functional signal: its absence is one of the four
  stacked mode-signal mechanisms (chrome silhouette) the parent epic
  documents. Together with the cyan left-edge stripe, the mode-pill
  state, and motion dampening, the user reads Static at a glance even
  without looking at the pill.

  ## Tab inventory (7 sub-tabs)

  The Static surface now mounts seven sub-tabs. Each sibling bead
  installs its own panel into the L4 tab registry (rf2-2moh1):

      Machines (m, default) · Routes (r) · Schemas (c) · Views (v) ·
      Flows (f) · Events (e) · Interceptors (i)

  Tab order + mnemonics per the findings doc §5.2 (mode-scoped: same
  letter, different target per mode — `m` in Runtime opens the
  Machines instance inspector, `m` in Static opens the Machines
  registry browse).

  Tab-mnemonic mode-scoping lives in `static/keybinding.cljs`
  follow-on — Phase 1 ships only the click path.

  ## Frame isolation

  Same discipline as the Runtime shell. The Static shell is wrapped
  in `[rf/frame-provider {:frame :rf/causa}]`; every subscribe +
  dispatch inside the shell resolves to `:rf/causa`. Every subscribing
  region is `reg-view`-registered so its rendered component carries
  `:contextType frame-context` (rf2-in6l2 + Spec 004 §Plain Reagent
  fns do not pick up the surrounding frame).

  ## Mode-signal mechanism (4 stacked signals)

  The parent epic locks four signals that telegraph Static state:

    1. **Mode pill** at ribbon-left — accent-violet active segment,
       200ms cross-fade. Owned by `static/mode_pill.cljs`. The pill
       lives at ribbon-left in BOTH modes (it's the toggle, not the
       indicator).
    2. **2-px left-edge ribbon stripe** — `:accent-violet` in Runtime,
       `:cyan` in Static. Owned by both shells via the explicit
       `mode-stripe-colour` arg passed into the ribbon's outer div.
    3. **Motion dampening** — Runtime ships the LIVE pulse + machine-
       active pulse + 180ms tab fade. Static drops the continuous
       pulses entirely; the 180ms tab fade collapses to 0ms (instant)
       so cluster swaps land without motion.
    4. **Chrome silhouette** — Runtime is 4-layer; Static is 3-layer
       (no L2 / no spine). The shape itself is a signal.

  ## Sibling beads filling the tab inventory

  Each sibling bead owned its sub-tab panel:

    - rf2-o5f5f.2 — Machines registry browse + Topology
    - rf2-o5f5f.3 — Routes registry browse + Simulate-URL
    - rf2-o5f5f.4 — Schemas registry browse + sample data
    - rf2-o5f5f.5 — Views registry browse (Fiber-walker consumer)
    - rf2-uhsqb   — Flows registry browse
    - rf2-o5f5f.6 — Events registry browse + interceptor stack +
                    hermetic simulate (Events) + Interceptors lens"
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.static.mode-pill :as mode-pill]
            ;; Static panel views (Machines / Routes / Schemas / Views /
            ;; Flows) are pulled in via the L4 tab registry — each
            ;; panel's `install!` registers `{:panel <view-fn>}` with
            ;; `panel-registry/reg-l4-tab!` (rf2-2moh1) and
            ;; `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id :static`. The shell no longer
            ;; requires those panel nses directly.
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale layout sans-stack]]))

;; ---- tab inventory ------------------------------------------------------
;;
;; Per rf2-2moh1 the Static-mode L3 tab inventory now lives in the
;; internal `panel-registry`. Each Static panel's `install!` registers
;; its own tab metadata (`{:modes #{:static} :order ...}`); the
;; helpers below read the registry so external callers (registry.cljs
;; for `:rf.causa.static/select-tab`'s contains? guard, tests asserting
;; the canonical order) see one source of truth.

(defn tabs
  "Ordered Static-mode tab entries. Each entry carries `:id`,
  `:label`, `:mnem`, `:modes`, `:order`, `:panel`, and (for tabs
  awaiting their sibling-bead content) `:placeholder-bead`. Order
  matches the parent-epic findings doc
  `2026-05-19-causa-explorer-mode.md` §2.4 — machines, routes,
  schemas, views, flows, events.

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
  "Default landing tab when `:rf.causa.static/selected-tab` is unset.
  Pinned to `:machines` per the parent-epic findings doc — the densest
  Static surface is the highest-value landing."
  :machines)

(defn tab-ids
  "Set of valid Static tab ids — used by `:rf.causa.static/select-tab`
  to reject unknown values from the dispatch arg. Reads through the
  registry so a new `reg-l4-tab!` is picked up without modifying this
  ns."
  []
  (panel-registry/tab-ids-for-mode :static))

;; ---- mode signal #2 — left-edge stripe colour ---------------------------

(def runtime-stripe-token
  "Token-key for the Runtime mode's 2-px left-edge ribbon stripe per
  the parent-epic mode-signal mechanism (signal #2). Held as a token
  KEY (not the resolved hex) so per-theme palette switching (rf2-
  5kfxe.6 light theme) flows through naturally."
  :accent-violet)

(def static-stripe-token
  "Token-key for the Static mode's 2-px left-edge ribbon stripe per
  the parent-epic mode-signal mechanism (signal #2). Cyan is already
  in the palette (rf2-5kfxe.6) at hex #43C3D0 — no new token
  introduced, per the rf2-zhrwo audit constraint 'Zero new tokens'."
  :cyan)

(defn stripe-token-for-mode
  "Pure helper. Returns the token KEY (`:accent-violet` / `:cyan`)
  the L1 ribbon should paint as its 2-px left-edge stripe for the
  given mode. JVM-portable so the test corpus can cover the round-
  trip without a CLJS runtime."
  [mode]
  (case mode
    :static  static-stripe-token
    runtime-stripe-token))

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
  Runtime ribbon (`shell.cljs/ribbon-right-icons`) but inlined here so
  the Static shell stays self-contained and we don't form a cycle by
  reaching back into the Runtime ns."
  []
  (let [icon-style {:background     "transparent"
                    :border         "none"
                    :color          (:text-secondary tokens)
                    :cursor         "pointer"
                    :font-size      (:body type-scale)
                    :padding        "2px 6px"}]
    [:div {:data-testid "rf-causa-static-ribbon-icons"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     [:button {:data-testid "rf-causa-static-icon-settings"
               :title       "Settings (,)"
               :aria-label  "Open Causa settings"
               :on-click    #(rf/dispatch [:rf.causa/settings-open] {:frame :rf/causa})
               :style       icon-style}
      "⚙"]
     [:button {:data-testid "rf-causa-static-icon-close"
               :title       "Close (Ctrl+Shift+C)"
               :aria-label  "Close Causa"
               :on-click    #(rf/dispatch [:rf.causa/close-shell] {:frame :rf/causa})
               :style       icon-style}
      "✕"]]))

(rf/reg-view ribbon
  "L1 ribbon — 56px chrome, Static-flavoured. Per parent-epic rf2-
  o5f5f mode-signal mechanism the ribbon paints a 2-px left-edge
  stripe in CYAN (Static) vs VIOLET (Runtime). Mode pill sits at
  ribbon-left; right-icons (Settings · Close) sit at ribbon-right.
  Runtime's nav / frame / filter clusters are HIDDEN — Static is
  event-independent, those clusters have no meaning here.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  [_props]
  [:div {:data-testid "rf-causa-static-ribbon"
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
   [mode-pill/mode-pill]
   [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
    [ribbon-right-icons]]])

;; ---- L3 tab bar (Static) ------------------------------------------------

(defn- tab-button
  "One Static tab. Same `●` / `○` glyph language as the Runtime
  `tab-button` (`shell.cljs/tab-button`) — design language is shared
  per the rf2-zhrwo audit's 'Causa-in-a-quieter-key' framing.

  Per the canonical chrome ARIA pattern (rf2-lvf8t / rf2-q7who Thread
  B), the tab carries `role='tab'` + `aria-selected` so assistive
  tech reads it as a tab, not a generic button."
  [{:keys [id label mnem active?]}]
  (let [glyph    (if active? "◉" "○")
        color    (if active? (:text-primary tokens) (:text-secondary tokens))
        ;; rf2-plajx — mirror the Runtime tab-button pattern: stable
        ;; tab-id + matching tabpanel id so the L4 panel's
        ;; `aria-labelledby` resolves.
        tab-id   (str "rf-causa-static-tab-button-" (name id))
        panel-id (str "rf-causa-static-tabpanel-" (name id))]
    [:button {:data-testid   (str "rf-causa-static-tab-" (name id))
              :id            tab-id
              :role          "tab"
              :aria-selected (if active? "true" "false")
              :aria-controls panel-id
              :on-click      #(rf/dispatch [:rf.causa.static/select-tab id]
                                           {:frame :rf/causa})
              :title         (str label " (" mnem ")")
              :aria-label    (str "Static " label " tab")
              :style {:background    "transparent"
                      :border        "none"
                      :border-bottom (if active?
                                       (str "2px solid " (:cyan tokens))
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
                              (:cyan tokens)
                              (:text-tertiary tokens))
                     :margin-right "4px"}}
      glyph]
     label]))

(rf/reg-view tab-bar
  "L3 tab bar — five Static tabs. Same height (40px), same row anatomy,
  same ARIA pattern as the Runtime tab-bar. The selected-tab slot is
  Static-scoped (`:rf.causa.static/selected-tab`) so flipping modes
  doesn't clobber the Runtime tab choice and vice-versa.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [selected @(rf/subscribe [:rf.causa.static/selected-tab])]
    [:div {:data-testid "rf-causa-static-tab-bar"
           :role        "tablist"
           :aria-label  "Causa Static-mode panel tabs"
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
       [tab-button (assoc tab :active? (= id selected))])]))

;; ---- L4 detail panel (placeholders) -------------------------------------

(defn- placeholder-card
  "Render a placeholder card for an unfilled Static sub-tab. The card
  surfaces:

    - The tab label as an `<h2>` for screen-reader navigation
      (rf2-vxpq1 — was previously `<h1>` which nested a second top-
      level heading inside the host document's outline; `<h2>` keeps
      the placeholder a subheading under the host's `<h1>`).
    - The sibling bead id ('rf2-o5f5f.<N> will fill this').
    - A muted hint paragraph naming the upcoming content.

  The card is a single `<section>` painted on `bg-2` with a thin
  `:cyan` accent stripe — mirrors the Runtime panels' per-domain
  stripe convention (`tokens/accent-stripe-style`) but uses cyan as
  the Static-mode accent."
  [{:keys [label placeholder-bead id]}]
  [:section {:data-testid (str "rf-causa-static-placeholder-" (name id))
             :style {:padding       "16px"
                     :background    (:bg-2 tokens)
                     :color         (:text-primary tokens)
                     :font-family   sans-stack
                     :font-size     (:body type-scale)
                     :line-height   (:line-height-tight type-scale)}}
   [:h2 {:style {:font-size     (:display type-scale)
                 :margin        "0 0 8px 0"
                 :padding-left  "10px"
                 :border-left   (str "3px solid " (:cyan tokens))}}
    label]
   [:p {:style {:color  (:text-secondary tokens)
                :margin "0 0 12px 0"}}
    [:strong {:style {:color (:cyan tokens)}}
     placeholder-bead]
    " will fill this."]
   [:p {:style {:color  (:text-tertiary tokens)
                :margin 0
                :font-size (:caption type-scale)}}
    "Static mode is event-INDEPENDENT — this tab will browse what's "
    "registered, not what just fired. See parent epic rf2-o5f5f."]])

(rf/reg-view detail-panel
  "L4 detail panel — registry-driven mount (rf2-2moh1).

  Each Static panel's `install!` registers its tab entry via
  `panel-registry/reg-l4-tab!` with `:modes #{:static}` + a `:panel`
  view fn. The Static-mode L4 tabs are:

    :machines     → `static.machines.panel/panel`         (rf2-o5f5f.2)
    :routes       → `static.routes.panel/Panel`           (rf2-o5f5f.3)
    :schemas      → `static.schemas.panel/Panel`          (rf2-o5f5f.4)
    :views        → `static.views.panel/Panel`            (rf2-o5f5f.5)
    :flows        → `static.flows.panel/Panel`            (rf2-uhsqb)
    :events       → `static.events.panel/Panel`           (rf2-o5f5f.6)
    :interceptors → `static.interceptors.panel/Panel`     (rf2-o5f5f.6)

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [selected (or @(rf/subscribe [:rf.causa.static/selected-tab])
                     default-tab)
        tab      (panel-registry/tab-by-id :static selected)]
    [:div {:data-testid (str "rf-causa-static-detail-panel-" (name selected))
           ;; rf2-plajx — Static L4 closes the tab/tabpanel loop.
           ;; Pairs with the per-tab `id` set by `tab-button` so
           ;; assistive tech reads the panel as "labelled by <tab
           ;; name>". Same shape as the Runtime detail-panel.
           :id              (str "rf-causa-static-tabpanel-" (name selected))
           :role            "tabpanel"
           :aria-labelledby (str "rf-causa-static-tab-button-" (name selected))
           :style {:flex        "1 1 auto"
                   :min-height  "0"
                   :overflow    "auto"
                   :background  (:bg-2 tokens)
                   :color       (:text-primary tokens)}}
     (if tab
       [(:panel tab)]
       [:div {:data-testid "rf-causa-static-tab-unknown"
              :style {:padding     "16px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack}}
        "Unknown Static tab: " [:code (pr-str selected)]])]))

;; ---- Static surface ------------------------------------------------------

(rf/reg-view surface
  "The full Static surface — 3 stacked layers (ribbon · tab bar ·
  detail panel). The Static surface plugs into the Runtime shell's
  outer envelope (`shell.cljs/shell-view`) which owns the
  frame-provider + global-styles install + modal mounts; this surface
  just renders the chrome that swaps in when Static mode is active.

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region."
  []
  [:div {:data-testid "rf-causa-static-surface"
         :data-rf-causa-mode "static"
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
