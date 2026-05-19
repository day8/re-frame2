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

  ## Tab inventory (5 sub-tabs)

  This bead registers the 5 Static sub-tabs as PLACEHOLDERS — each
  renders a 'rf2-o5f5f.<N> will fill this' card. The sibling beads
  (.2 Machines, .3 Routes, .4 Schemas, .5 Views, .6 Events) replace
  the placeholders with real catalogue content.

  Tab order + mnemonics per the findings doc §5.2 (mode-scoped: same
  letter, different target per mode — `m` in Runtime opens the
  Machines instance inspector, `m` in Static opens the Machines
  registry browse):

      Machines (m, default) · Routes (r) · Schemas (c) · Views (v) · Events (e)

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

  ## What's deferred to siblings

  This bead registers the chrome + the empty tabs only. The siblings
  fill the placeholder content:

    - rf2-o5f5f.2 — Machines registry browse + Topology
    - rf2-o5f5f.3 — Routes registry browse + Simulate-URL
    - rf2-o5f5f.4 — Schemas registry browse + sample data
    - rf2-o5f5f.5 — Views registry browse (Fiber-walker consumer)
    - rf2-o5f5f.6 — Events registry browse + interceptor stack"
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.static.mode-pill :as mode-pill]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale layout sans-stack]]))

;; ---- tab inventory ------------------------------------------------------

(def tabs
  "The five L3 tabs Static mode exposes per the findings doc
  `2026-05-19-causa-explorer-mode.md` §2.4 + parent-epic rf2-o5f5f
  sub-bead list. Each entry carries:

    - `:id`     — keyword that lands on `:rf.causa.static/selected-tab`
                  when the tab is selected.
    - `:label`  — visible tab label.
    - `:mnem`   — keyboard mnemonic letter (rendered in the tab's
                  `title`; follow-on bead wires the actual keybinding).
    - `:placeholder-bead` — the sibling bead id that fills the tab.

  Order matches the findings doc. Default is `:machines` per Mike's
  call (the Machines registry is the densest Static surface; opening
  Static on a fresh slate should land on the highest-value tab)."
  [{:id :machines :label "Machines" :mnem "m" :placeholder-bead "rf2-o5f5f.2"}
   {:id :routes   :label "Routes"   :mnem "r" :placeholder-bead "rf2-o5f5f.3"}
   {:id :schemas  :label "Schemas"  :mnem "c" :placeholder-bead "rf2-o5f5f.4"}
   {:id :views    :label "Views"    :mnem "v" :placeholder-bead "rf2-o5f5f.5"}
   {:id :events   :label "Events"   :mnem "e" :placeholder-bead "rf2-o5f5f.6"}])

(def default-tab :machines)

(def tab-ids
  "Set of valid tab ids — used by `:rf.causa.static/select-tab` to
  reject unknown values from the dispatch arg. JVM-portable pure data."
  (into #{} (map :id) tabs))

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
  (let [glyph (if active? "◉" "○")
        color (if active? (:text-primary tokens) (:text-secondary tokens))]
    [:button {:data-testid   (str "rf-causa-static-tab-" (name id))
              :role          "tab"
              :aria-selected (if active? "true" "false")
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
     [:span {:style {:color (if active?
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
     (for [{:keys [id] :as tab} tabs]
       ^{:key id}
       [tab-button (assoc tab :active? (= id selected))])]))

;; ---- L4 detail panel (placeholders) -------------------------------------

(defn- placeholder-card
  "Render a placeholder card for an unfilled Static sub-tab. The card
  surfaces:

    - The tab label as an `<h1>` for screen-reader navigation.
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
   [:h1 {:style {:font-size     (:display type-scale)
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

(defn- tab-by-id
  "Pure helper. Look up a tab map by `:id`. Returns nil when the id is
  not in the inventory (the L4 panel falls back to a generic 'unknown'
  card)."
  [id]
  (some #(when (= id (:id %)) %) tabs))

(rf/reg-view detail-panel
  "L4 detail panel — case-switch on `:rf.causa.static/selected-tab`.
  Phase 1 (this bead) renders placeholder cards; the sibling beads
  replace each placeholder with real catalogue content.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [selected (or @(rf/subscribe [:rf.causa.static/selected-tab])
                     default-tab)
        tab      (tab-by-id selected)]
    [:div {:data-testid (str "rf-causa-static-detail-panel-" (name selected))
           :style {:flex        "1 1 auto"
                   :min-height  "0"
                   :overflow    "auto"
                   :background  (:bg-2 tokens)
                   :color       (:text-primary tokens)}}
     (if tab
       [placeholder-card tab]
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
