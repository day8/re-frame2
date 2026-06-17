(ns day8.re-frame2-xray.settings.view
  "Pure-hiccup view for the Xray Settings popup modal (rf2-9poxq).

  The view body is a plain Reagent fn; `settings/popup.cljs` wraps
  it in `reg-view` so subscribes route to `:rf/xray`. Visual style
  mirrors the palette modal (dim backdrop, centred dialog,
  `tokens/bg-1` body) so the user gets a consistent affordance
  class for transient overlays.

  ## Why every deferred dispatch captures the surrounding frame (rf2-smvvz / rf2-r0o63 / rf2-nesy9)

  Subscribes resolve through the React-context tier at RENDER time —
  React's `_currentValue` for the `frame-context` is set to the
  instance frame while the body of the `frame-provider`'s children is
  rendering, so `(rf/subscribe …)` from inside the popup picks up the
  right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` fire
  LATER — after render commits and React has POPPED `_currentValue`
  back to the context's default (`:rf/default`). At click time the
  3-tier frame resolution chain (dynamic var → React-context tier →
  `:rf/default`) falls all the way through, the dispatch lands on
  `:rf/default`'s router, and the `:rf.xray/settings-*` handler
  reduces `:rf/default`'s db — leaving Xray's `:settings-open?` flag
  untouched. Symptom: X button does nothing, tabs do not switch, Esc
  does not close — the modal is stuck.

  An EARLIER fix pinned every deferred handler to a `{:frame :rf/xray}`
  literal — correct for the singleton shell, but it entrenched the
  one-frame lock (rf2-1w07r): two shells on a page collided on the one
  global app-db. The current contract (rf2-r0o63 / rf2-nesy9) captures
  the SURROUNDING instance frame instead: `settings/popup.cljs`'s
  `Modal` `reg-view` body has a frame-aware `dispatch` injected by the
  macro (closing over the render-time frame), and threads it into
  `popup-view`, which fans it out to every section helper. Each
  deferred handler calls that captured `dispatch` (never the global
  `rf/dispatch`, never a literal), so N isolated instances each route
  to their own frame."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- styles --------------------------------------------------------------
;;
;; The backdrop's `:position` and `:z-index` honour the
;; `:rf.xray/modal-positioning` opt published by `shell-view` (rf2-om6fa).
;; `:fixed` (default, production) — full-viewport overlay at the
;; chrome's max-int stacking layer. `:absolute` (Story testbeds) —
;; contained to the nearest positioned ancestor (the shell's outer
;; `<div>` is `position: relative` in `:inline` mode) with a sane
;; z-index so the cell's modals never paint over Story chrome.

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position         (if absolute? "absolute" "fixed")
     :top              0
     :left             0
     :right            0
     :bottom           0
     :background       "rgba(0,0,0,0.55)"
     :backdrop-filter  "blur(2px)"
     :display          "flex"
     :align-items      "flex-start"
     :justify-content  "center"
     :padding-top      (if absolute? "5%" "8vh")
     :z-index          (if absolute? 100 2147483646)}))

(defn- dialog-style []
  {:width            "600px"
   :max-width        "92vw"
   :max-height       "84vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "12px 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)})

(defn- tab-strip-style []
  {:display          "flex"
   :gap              "4px"
   :padding          "8px 16px 0 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)})

(defn- tab-style [active?]
  {:padding          "6px 12px"
   :cursor           "pointer"
   :background       (if active? (:bg-1 tokens) "transparent")
   :color            (if active? (:text-primary tokens) (:text-secondary tokens))
   :font-family      sans-stack
   :font-size        (:body type-scale)
   :font-weight      (if active? 600 400)
   :border           "none"
   :border-bottom    (str "2px solid "
                          (if active? (:accent tokens) "transparent"))
   :border-top-left-radius "4px"
   :border-top-right-radius "4px"
   :margin-bottom    "-1px"})

(defn- body-style []
  {:flex             1
   :overflow-y       "auto"
   :padding          "20px 24px"
   :color            (:text-primary tokens)
   :font-family      sans-stack
   :font-size        (:body type-scale)})

(defn- section-heading-style []
  {:font-size        (:display type-scale)
   :font-weight      600
   :margin           "0 0 12px 0"
   :color            (:text-primary tokens)})

(defn- field-style []
  {:display          "flex"
   :flex-direction   "column"
   :gap              "6px"
   :margin           "12px 0 18px 0"})

(defn- label-style []
  {:font-size        (:body type-scale)
   :font-weight      500
   :color            (:text-primary tokens)})

(defn- hint-style []
  {:font-size        (:caption type-scale)
   :color            (:text-tertiary tokens)
   :font-family      sans-stack})

(defn- close-button-style []
  {:background       "transparent"
   :border           "none"
   :color            (:text-secondary tokens)
   :font-size        "18px"
   :line-height      1
   :cursor           "pointer"
   :padding          "4px 8px"
   :border-radius    "3px"})

(defn- primary-button-style []
  {:background       (:accent tokens)
   :color            (:white tokens)
   :border           "none"
   :padding          "6px 14px"
   :border-radius    "4px"
   :cursor           "pointer"
   :font-family      sans-stack
   :font-size        (:body type-scale)
   :font-weight      500})

;; ---- tab strip ----------------------------------------------------------

(def ^:private tabs
  "Ordered tab list. The modal carries four sections
  (General | Keybindings | Buffer | Diff).
  Tab id matches the `:rf.xray/settings-update` `section` for
  sections that map 1:1 to a settings slot.

  Each tab carries a `:mnemonic` — a single bare letter the dialog's
  keydown handler captures while the modal is open (g/k/b/d).
  Modal-only; the outer global mnemonics (`,` / `s` / `c` / `?`)
  never fire while the dialog has focus because the dialog's
  `on-key-down` stops propagation on every consumed key. Per
  Mike's 2026-05-19 §0ter.4 walkthrough.

  Telemetry was removed (rf2-jh9ws): Xray ships no telemetry
  endpoint, and the toggle in v1 was a broken affordance — silent
  by default, no broken claims (per text-audit rf2-yn86j). When
  telemetry actually ships, the tab returns with real wiring.

  Theme tab was removed (rf2-ou3pn): the top-ribbon Theme icon
  (`ribbon-theme-toggle` in `shell.cljs`) is the canonical
  light/dark affordance and dispatches the same
  `:rf.xray/settings-update :theme nil <kw>` event the popup's
  radio used to drive. The `:use-system-colors?` HCM-override
  checkbox migrated to General → Power user (it always was a
  `:general` slot — its cosmetic home in the Theme tab is gone
  with the tab).

  Filters tab was removed (rf2-wknb3): v1 spec 016-Auxiliary-
  Panels.md called it a discoverability pointer into the ribbon
  pill UI, but the only affordance it exposed was an 'Open auto-
  filter UI' button dispatching `:rf.xray.filters/open` — an event
  with no handler registered anywhere. Filter management is fully
  covered by the canonical surfaces: the top-ribbon filter pill
  strip (`filters/pills.cljs`), the per-pill edit popup
  (`:rf.xray.filters/edit-popup-*` events in
  `filters/edit_popup.cljs`), and the mute manager modal
  (rf2-ikuwt). The popup tab carried no unique state and no
  working dispatch — pure redundancy.

  Diff (rf2-i39w2 Phase 3) carries the hiccup-diff micro-engine's
  opt-in fn-ref-changes toggle.

  Keybindings (rf2-ttnst) v1 is READ-ONLY — a table of every chord
  the global listener captures, plus a master 'Handle keys?' toggle
  (alias for `:rf.xray/keybinding-enabled?`). Rebind UI is the v1.1
  follow-on.

  Buffer (rf2-ttnst; rf2-pu9sb consolidation; rf2-5u03ig trim)
  surfaces the cascades-retained knob (writes through to
  `(rf/configure! :trace-buffer {:cascades-retained N})`) plus a
  'Clear buffer now' button with a confirmation modal (destructive
  action). The inert `:app-db/inspector-collapse-threshold` input was
  removed (rf2-5u03ig — no runtime consumer; the inspector already
  auto-collapses on depth/width). The epoch-history slider is NOT
  here: rf2-pu9sb moved it into Buffer, but Mike relocated it back to
  General on 2026-05-27 (it renders in `general-section`). The slot
  stays `:general :epoch-history` throughout — only the visual home
  moved."
  [{:id :general     :label "General"     :mnemonic "g"}
   {:id :keybindings :label "Keybindings" :mnemonic "k"}
   {:id :buffer      :label "Buffer"      :mnemonic "b"}
   {:id :diff        :label "Diff"        :mnemonic "d"}])

(def ^:private mnemonic->tab-id
  "Reverse-lookup table the dialog keydown handler consults to map
  a bare-letter keystroke to its target tab id. Built once at ns
  load."
  (into {} (for [{:keys [mnemonic id]} tabs] [mnemonic id])))

(defn- settings-tab-button-id
  "DOM id for a Settings tab button. Public-shaped helper so the
  tabpanel's `aria-labelledby` can compute the same id without
  duplicating the literal."
  [id]
  (str "rf-xray-settings-tab-button-" (name id)))

(defn- settings-tabpanel-id
  "DOM id for the Settings tabpanel — referenced by the tab button's
  `aria-controls` AND set on the body wrapper so the WAI-ARIA APG
  tabs pattern's id round-trip resolves (rf2-h4mnh)."
  [id]
  (str "rf-xray-settings-tabpanel-" (name id)))

(defn- tab-button [dispatch {:keys [id label]} active?]
  ;; rf2-h4mnh — Settings popup inner tabs now carry the full WAI-
  ;; ARIA tab role: `role="tab"` + `aria-selected` per state +
  ;; stable `id` (so the body's `aria-labelledby` resolves) +
  ;; `aria-controls` pointing at the body's tabpanel id. The same
  ;; pattern Xray already ships on the L3 Dynamic + Static tab
  ;; strips (shell.cljs/tab-button). Keeps `:data-active` for
  ;; styling-key parity with existing CSS selectors / tests.
  [:button {:data-testid    (str "rf-xray-settings-tab-" (name id))
            :id             (settings-tab-button-id id)
            :role           "tab"
            :aria-selected  (if active? "true" "false")
            :aria-controls  (settings-tabpanel-id id)
            :data-active    (str active?)
            :on-click       #(dispatch [:rf.xray/settings-select-tab id])
            :style          (tab-style active?)}
   label])

;; ---- section: General ---------------------------------------------------

;; Forward declarations for style helpers defined further down the
;; file (the ghost-button + danger-button styles are colocated with
;; the Buffer-tab affordances). The editor-override picker reaches
;; for `ghost-button-style` to render the "Reset to project default"
;; button.
(declare ghost-button-style)

;; ---- editor-override picker (rf2-dudqz) ---------------------------------
;;
;; Enumerated radio set + Custom escape hatch. Selecting an editor
;; writes `[:general :editor-override <value>]` via the same
;; `:rf.xray/settings-update` event every other General-tab knob uses
;; — round-trips through localStorage and the override wins
;; immediately because `config/get-editor` (the read seam) consults
;; the slot before the host's `editor` atom.
;;
;; The picker carries six options:
;;   - "(project default)" — clears the override (writes `nil`)
;;   - VS Code / Cursor / Windsurf / Zed / IntelliJ IDEA — the
;;     enumerated keywords `re-frame.source-coords.editor-uri` knows
;;     natively (one option per keyword in `:rf.xray/editor`)
;;   - Custom — reveals the URI-template input so users on Sublime /
;;     Emacs / Vim / etc. can paste their own template (`{path}` /
;;     `{file}` / `{line}` / `{column}` placeholders)
;;
;; The "(project default)" radio is the default selection when the
;; override is nil; selecting it clears the override so the host's
;; `configure!` choice wins again.

(def ^:private editor-override-options
  "Ordered list of the radio options. `:value` is the slot value
  the option writes (a keyword, nil, or a `{:custom <tpl>}` sentinel
  the picker recognises). The Custom option does NOT write through
  on radio click — it just reveals the URI-template input; the
  template input writes the actual `{:custom <tpl>}` slot value on
  change."
  [{:id :default  :value nil       :label "(project default)"}
   {:id :vscode   :value :vscode   :label "VS Code"}
   {:id :cursor   :value :cursor   :label "Cursor"}
   {:id :windsurf :value :windsurf :label "Windsurf"}
   {:id :zed      :value :zed      :label "Zed"}
   {:id :idea     :value :idea     :label "IntelliJ IDEA (any JetBrains IDE)"}
   {:id :custom   :value :custom   :label "Custom URI template"}])

(defn- override->radio-id
  "Map the persisted override slot value back to the radio option id
  it represents. `nil` selects `:default`; map (custom) selects
  `:custom`; an enumerated keyword selects its matching id; anything
  unrecognised falls back to `:default` so a stale persisted payload
  cannot leave the radio set unselected."
  [override]
  (cond
    (nil? override)                                   :default
    (map? override)                                   :custom
    (#{:vscode :cursor :windsurf :zed :idea} override) override
    :else                                              :default))

(defn- dispatch-editor-override! [dispatch value]
  (dispatch [:rf.xray/settings-update
             :general :editor-override value]))

(def ^:private custom-template-seed
  "Seed template the Custom radio writes when the user first selects
  it (rf2-rc35g). Echoes the framework-default `:vscode` URI shape so
  click-to-source resolves to a working URI immediately — the user
  edits from a known baseline rather than a blank that silently breaks
  the chip until they finish typing.

  Per `re-frame.source-coords.editor-uri/editor-uri`'s `:vscode`
  branch."
  "vscode://file/{path}:{line}:{column}")

(defn- editor-override-section [dispatch override host-default]
  (let [active-id   (override->radio-id override)
        custom-tpl  (when (map? override) (:custom override))
        host-label  (cond
                      (map? host-default)
                      (str "Custom (" (or (:custom host-default) "—") ")")

                      :else
                      (case host-default
                        :vscode   "VS Code"
                        :cursor   "Cursor"
                        :windsurf "Windsurf"
                        :zed      "Zed"
                        :idea     "IntelliJ IDEA"
                        (str (or host-default :vscode))))]
    [:div {:data-testid "rf-xray-settings-editor-override"
           :style       (field-style)}
     [:span {:style (label-style)} "Click-to-source links open in"]
     (for [{:keys [id value label]} editor-override-options]
       ^{:key id}
       [:label {:style {:display "flex" :align-items "center" :gap "8px"
                        :cursor "pointer"
                        :font-size (:body type-scale)
                        :color (:text-primary tokens)}}
        [:input {:data-testid (str "rf-xray-settings-editor-override-" (name id))
                 :type        "radio"
                 :name        "rf-xray-settings-editor-override"
                 :checked     (= id active-id)
                 :on-change   (fn [_]
                                (cond
                                  ;; Custom: seed a working template
                                  ;; (rf2-rc35g — was `{:custom ""}`,
                                  ;; which breaks click-to-source
                                  ;; until the user finishes typing).
                                  ;; The vscode-style URI is the most
                                  ;; common shape; users on other
                                  ;; editors edit from a baseline that
                                  ;; resolves cleanly today.
                                  (= id :custom)
                                  (when-not (map? override)
                                    (dispatch-editor-override!
                                      dispatch {:custom custom-template-seed}))

                                  :else
                                  (dispatch-editor-override! dispatch value)))}]
        label])

     ;; Custom URI-template input — visible only when Custom is the
     ;; active option. The input is uncontrolled-style (value tracks
     ;; the slot) so the user's typing lands on every keystroke.
     (when (= active-id :custom)
       [:div {:style {:margin "8px 0 0 24px"}}
        [:input {:data-testid "rf-xray-settings-editor-override-custom-input"
                 :type        "text"
                 :value       (str (or custom-tpl ""))
                 :placeholder "subl://open?url=file://{path}&line={line}"
                 :on-change   (fn [^js e]
                                (let [tpl (.. e -target -value)]
                                  (dispatch-editor-override!
                                    dispatch {:custom tpl})))
                 :style       {:width        "100%"
                               :padding      "4px 8px"
                               :background   (:bg-2 tokens)
                               :color        (:text-primary tokens)
                               :border       (str "1px solid " (:border-default tokens))
                               :border-radius "4px"
                               :font-family  mono-stack
                               :font-size    (:caption type-scale)}}]
        [:p {:style (hint-style)}
         "Placeholders: "
         [:code {:style {:font-family mono-stack
                         :color (:text-tertiary tokens)}}
          "{path}"] " "
         [:code {:style {:font-family mono-stack
                         :color (:text-tertiary tokens)}}
          "{file}"] " "
         [:code {:style {:font-family mono-stack
                         :color (:text-tertiary tokens)}}
          "{line}"] " "
         [:code {:style {:font-family mono-stack
                         :color (:text-tertiary tokens)}}
          "{column}"] ". "
         "Schemes outside the allowlist (`http:` / `https:` / "
         "`javascript:` / `data:`) are refused at click-time."]])

     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "12px"
                    :margin-top "8px"}}
      [:button {:data-testid "rf-xray-settings-editor-override-reset"
                :on-click    (fn [^js e]
                               (.stopPropagation e)
                               (dispatch-editor-override! dispatch nil))
                :disabled    (nil? override)
                :style       (merge (ghost-button-style)
                                    (when (nil? override)
                                      {:opacity 0.5
                                       :cursor "default"}))}
       "Reset to project default"]
      [:span {:data-testid "rf-xray-settings-editor-override-host-default"
              :style       {:font-size (:caption type-scale)
                            :color (:text-tertiary tokens)
                            :font-family sans-stack}}
       "Project default: " host-label]]
     [:p {:style (hint-style)}
      "Override the project's editor preference for your machine. "
      "Stored locally — never shared with the host app or your "
      "teammates. Useful when the project's "
      [:code {:style {:font-family mono-stack
                      :color (:text-tertiary tokens)}}
       ":rf.xray/editor"]
      " default doesn't match your installed editor."]]))

(defn- general-section [dispatch]
  (let [panel-position  @(rf/subscribe [:rf.xray/setting :general :panel-position])
        auto-open?      @(rf/subscribe [:rf.xray/setting :general :auto-open-on-error?])
        epoch-history   @(rf/subscribe [:rf.xray/setting :general :epoch-history])
        show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
        editor-override @(rf/subscribe [:rf.xray/setting :general :editor-override])
        host-editor     @(rf/subscribe [:rf.xray/editor-host-default])]
    [:div {:data-testid "rf-xray-settings-section-general"}
     [:h2 {:style (section-heading-style)} "General"]

     ;; Removed 2026-05-27 per Mike (UX cleanup pass):
     ;;   - Text-size slider — defaults suffice
     ;;   - Panel-width numeric input + reset — drag the resize
     ;;     handle (rf2-x8h9y); double-click resets
     ;; Both setting slots remain in config so existing
     ;; effects (`apply-text-size!`, `:rf.xray/set-panel-width-px`)
     ;; continue to honour any host-set defaults.

     ;; ── Panel position radio ────────────────────────────────────
     [:div {:style (field-style)}
      [:span {:style (label-style)} "Panel position"]
      ;; rf2-czcg5 — the `:popout` "Popout window" option was dropped:
      ;; the second-window pop-out is now launched from the chrome's
      ;; visible `⛶` button (canonical) + the programmatic
      ;; `(xray/popout!)` API, not via this panel-position radio.
      (for [[pos label] [[:right-rail "Right rail (inline)"]
                         [:fullscreen "Fullscreen overlay"]]]
        ^{:key pos}
        [:label {:style {:display "flex" :align-items "center" :gap "8px"
                         :cursor  "pointer"
                         :font-size (:body type-scale)
                         :color   (:text-primary tokens)}}
         [:input {:data-testid (str "rf-xray-settings-panel-position-" (name pos))
                  :type        "radio"
                  :name        "rf-xray-settings-panel-position"
                  :checked     (= panel-position pos)
                  :on-change   #(dispatch [:rf.xray/settings-update
                                           :general :panel-position pos])}]
         label])]

     ;; ── Auto-open-on-error checkbox ─────────────────────────────
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-xray-settings-auto-open-on-error"
                :type        "checkbox"
                :checked     (boolean auto-open?)
                :on-change   #(dispatch
                                [:rf.xray/settings-update
                                 :general :auto-open-on-error?
                                 (boolean (.. % -target -checked))])}]
       "Auto-open Xray when an issue is observed"]]

     ;; ── Epoch history slider ─────────────────────────────────────
     ;; Relocated from Buffer to General 2026-05-27 per Mike.
     ;; Drives BOTH `:depth` and `:trace-events-keep` via
     ;; `apply-epoch-history!` so trace is retained for every
     ;; retained epoch (when an epoch evicts, its trace evicts too).
     ;; Range 5–200 (default 50); the slot is `:general :epoch-history`.
     [:div {:style (field-style)}
      [:label {:html-for "rf-xray-settings-epoch-history-input"
               :style    (label-style)} "Epoch history"]
      [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
       [:input {:data-testid "rf-xray-settings-epoch-history-input"
                :id          "rf-xray-settings-epoch-history-input"
                :type        "range"
                :min         "5"
                :max         "200"
                :step        "5"
                :value       (str (or epoch-history 50))
                :on-change   (fn [^js e]
                               (let [n (js/parseInt (.. e -target -value) 10)]
                                 (when-not (js/isNaN n)
                                   (dispatch
                                     [:rf.xray/settings-update
                                      :general :epoch-history n]))))
                :style       {:flex 1}}]
       [:span {:data-testid "rf-xray-settings-epoch-history-value"
               :style       {:font-family mono-stack
                             :color       (:text-secondary tokens)
                             :min-width   "48px"
                             :text-align  "right"}}
        (str (or epoch-history 50))]]
      [:p {:style (hint-style)}
       "Number of epochs Xray retains per frame for time-travel "
       "inspection. Trace is retained for every retained epoch — "
       "when an epoch evicts, its trace evicts too. Default 50."]]

     ;; Removed 2026-05-27 — Density radio (Cosy / Compact).
     ;; Per Mike: the two options were visually indistinguishable in
     ;; practice; the operator gains nothing from the toggle. Stay
     ;; with the default (`:cosy`); the `:general :density` config
     ;; slot + `:rf.xray/density` sub remain so any incremental
     ;; per-panel padding/line-height consumer keeps reading the
     ;; default value.

     ;; Removed 2026-05-27 — Long-keyword threshold input had ZERO
     ;; consumers outside the settings UI itself (grep across
     ;; tools/xray/src/ confirmed). The sub `:rf.xray/long-keyword-
     ;; threshold` + config slot `:general :long-keyword-threshold`
     ;; remain for any future code that wants to honour the default
     ;; (24), but no UI surfaces it any more.

     ;; ── Editor override (rf2-dudqz) ─────────────────────────────
     ;;
     ;; Per-operator override for Xray's 'Open in editor' click-to-
     ;; source target. Default `nil` (use the project's
     ;; `:rf.xray/editor` default). Selecting an editor here writes
     ;; the slot via `:rf.xray/settings-update` and `config/get-editor`
     ;; consults the slot before the host atom — the next chip click
     ;; uses the override URI without a reload.
     (editor-override-section dispatch editor-override host-editor)

     ;; ── (epoch-history slider housekeeping, rf2-3zyyx) ──
     ;;
     ;; The epoch-history slider RENDERS HERE in General (above), not in
     ;; Buffer: rf2-pu9sb moved it to Buffer as a buffer-capacity knob,
     ;; then Mike relocated it back to General on 2026-05-27. The slot
     ;; stays `:general :epoch-history` (what `apply-epoch-history!`
     ;; reads + restores) throughout — only the visual home moved. The
     ;; dead `:buffer :retained-epochs` numeric input (no substrate
     ;; consumer) was removed in the rf2-pu9sb cleanup — pre-pu9sb the
     ;; popup carried two fields for the same conceptual knob, one wired
     ;; and one not.

     ;; ── Power user divider + show-tool-frames toggle (rf2-ttnst) ─
     ;;
     ;; Per Mike Q8: the `:show-tool-frames?` toggle lives under a
     ;; `── Power user ──` divider at the bottom of General. Default
     ;; OFF. Flipping on reveals `:rf/xray` + `:rf/pair2` in the L1
     ;; frame-picker dropdown (per spec/007-UX-IA.md §Frame-observation
     ;; isolation invariant I1).
     [:div {:data-testid "rf-xray-settings-power-user-divider"
            :style {:display      "flex"
                    :align-items  "center"
                    :gap          "10px"
                    :margin       "24px 0 12px 0"
                    :color        (:text-tertiary tokens)
                    :font-size    (:caption type-scale)
                    :font-family  sans-stack
                    :text-transform "uppercase"
                    :letter-spacing "0.06em"}}
      [:span {:style {:flex 1
                      :height "1px"
                      :background (:border-subtle tokens)}}]
      [:span "Power user"]
      [:span {:style {:flex 1
                      :height "1px"
                      :background (:border-subtle tokens)}}]]

     ;; Removed 2026-05-27 — "Show tool frames in picker" toggle.
     ;; The `:show-tool-frames?` setting slot stays; default OFF
     ;; enforces the spec/007-UX-IA frame-observation isolation
     ;; invariant (tool frames observing themselves = anti-pattern).
     ;; If a future use case needs the override it can re-add the UI.

     ;; ── Show :ungrouped pseudo-cascade events (rf2-r9lyy) ──────
     ;;
     ;; Opt-in surface for the `:ungrouped` bucket produced by
     ;; `re-frame.trace.projection/group-cascades` (registry-time
     ;; emits, frame lifecycle outside a drain, `:rf.ssr/hydration-
     ;; mismatch`, REPL evals). Default OFF preserves Xray's
     ;; silent-by-default posture (rf2-639lc filtered the bucket out
     ;; of L2 entirely); flipping ON reveals the bucket as a muted
     ;; L2 row so users debugging SSR / REPL flows can focus it and
     ;; populate downstream panels. Per Mike 2026-05-19 closure of
     ;; rf2-q60yf (Option B — opt-in chip/toggle).
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-xray-settings-show-ungrouped"
                :type        "checkbox"
                :checked     (boolean show-ungrouped?)
                :on-change   #(dispatch
                                [:rf.xray/settings-update
                                 :general :show-ungrouped?
                                 (boolean (.. % -target -checked))])}]
       "Show :ungrouped pseudo-cascade events in L2"]
      [:p {:style (hint-style)}
       "Reveals events outside any dispatch — "
       [:code {:style {:font-family mono-stack
                       :color (:text-tertiary tokens)}}
        ":rf.ssr/*"]
       ", registry-time emits, REPL evals, frame lifecycle. "
       "Default OFF — Xray is silent-by-default. Useful when "
       "debugging SSR / REPL flows."]]

     ;; Removed 2026-05-27 — "Always show unchanged subs in the
     ;; Reactive panel" toggle. The `:show-unchanged-subs?` setting
     ;; slot stays; default OFF keeps the per-cascade footer
     ;; disclosure pattern (spec/021 §3.4) — unchanged subs are
     ;; coverage signal, not signal-of-the-moment.

     ;; Removed 2026-05-27 — "Use system colors" toggle (the manual
     ;; HCM-mode activator). The OS-level `@media (forced-colors:
     ;; active)` detection still works automatically; the manual
     ;; in-app override was redundant for the common case + added
     ;; noise to the panel. The `:use-system-colors?` setting slot
     ;; + the `apply-use-system-colors!` effect remain so a future
     ;; UI can re-expose if needed.
     ]))

;; ---- section: Filters (removed rf2-wknb3) ------------------------------
;;
;; The Filters tab was retired in rf2-wknb3. It carried no unique
;; affordance: the only widget was an "Open auto-filter UI" button
;; dispatching `:rf.xray.filters/open` — an event with no handler
;; registered anywhere — plus a static explainer paragraph. Filter
;; management is fully covered by the canonical surfaces:
;;
;;   * Top-ribbon filter pill strip (`filters/pills.cljs`) — full
;;     pill management (add/remove/toggle) lives here per
;;     spec/018-Event-Spine.md §7.
;;   * Per-pill edit popup (`filters/edit_popup.cljs`) — the
;;     `:rf.xray.filters/edit-popup-*` event family.
;;   * Mute manager modal (rf2-ikuwt).
;;
;; The settings tab was a discoverability pointer per the v1 spec;
;; with the ribbon already exposing the management surface and the
;; tab's only button being dead chrome, the pointer was redundant.

;; ---- section: Theme (removed rf2-ou3pn) --------------------------------
;;
;; The Theme tab was retired in rf2-ou3pn — the top-ribbon Theme icon
;; (`ribbon-theme-toggle` in `shell.cljs`) is now the canonical
;; light/dark affordance and dispatches the same
;; `:rf.xray/settings-update :theme nil <kw>` event the popup radio
;; used to drive. Both affordances persisted via the identical event,
;; so removing the popup's copy is a pure-redundancy cleanup. The
;; `:use-system-colors?` HCM-override checkbox moved to
;; General → Power user — it has always been a `:general` slot; only
;; its cosmetic home in the Theme section is gone with the tab.
;; `config/default-settings :theme` (`:light`, Figma authority) and
;; `settings/effects/apply-theme!` are unchanged.

;; ---- section: Diff (rf2-i39w2 Phase 3) ----------------------------------

(defn- diff-section [dispatch]
  (let [highlight? @(rf/subscribe [:rf.xray/setting :diff :highlight-fn-ref-changes?])]
    [:div {:data-testid "rf-xray-settings-section-diff"}
     [:h2 {:style (section-heading-style)} "Diff"]
     [:p {:style {:color (:text-secondary tokens)
                  :line-height 1.5
                  :margin "0 0 16px 0"}}
      "Controls for the structural-diff engine that powers App-DB Diff, "
      "Sub-output diff, and the View-hiccup diff drilldown in the Views "
      "panel."]

     ;; ── Highlight fn-ref changes ────────────────────────────────
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-xray-settings-diff-highlight-fn-ref"
                :type        "checkbox"
                :checked     (boolean highlight?)
                :on-change   #(dispatch
                                [:rf.xray/settings-update
                                 :diff :highlight-fn-ref-changes?
                                 (boolean (.. % -target -checked))])}]
       "Highlight function-ref changes in view hiccup"]
      [:p {:style (hint-style)}
       "Off by default. The hiccup-diff engine treats function-valued "
       "props (`:on-click`, `:on-change`, `:ref`, …) as opaque — "
       "anonymous fns created fresh per render do NOT surface as a "
       "diff. Flip this on when diagnosing memoization issues (a "
       "child re-renders because the parent passes a new fn every "
       "time); identity-different fns will surface as a distinct "
       "accent-coloured `(fn ref changed)` chip."]]]))

;; ---- section: Keybindings (rf2-ttnst) -----------------------------------
;;
;; Read-only chord table in v1. Each row mirrors one binding the
;; global keydown listener (`keybinding.cljs`) captures, or one inner
;; chord that fires only inside a specific modal/popover. Source of
;; truth is `keybinding.cljs` + `spec/007-UX-IA.md §Keyboard`; this
;; table is a static catalogue rebuilt by hand on every keybinding
;; change. A future v1.1 rebind UI will replace the catalogue with a
;; live registry; for now the static table is the cheapest correct
;; thing.
;;
;; The 'Handle keys?' master toggle aliases the
;; `:rf.xray/keybinding-enabled?` config slot (rf2-4eyik) — flipping
;; it false disables the global listener until next page-load. The
;; effect is global; the popup is just the surface.

(def ^:private keybinding-rows
  "Static catalogue. Group key carries a section label; rows are
  `[chord action]` pairs. Mirrors the tables in spec/007-UX-IA.md
  §Keyboard."
  [{:group "Global shortcuts"
    :rows  [["Ctrl+Shift+C" "Toggle Xray visibility"]
            ["?"            "Keyboard cheat-sheet"]
            [", or s"       "Settings popup"]
            ["Esc"          "Close modal / collapse popover / focus event list"]
            ["Ctrl+K / ⌘K"  "Command palette"]
            ["Ctrl+F"       "Find within active tab"]
            ["o"            "Popout (window.open whole shell)"]]}
   {:group "Ribbon nav cluster"
    :rows  [["j"     "Back one event (◀)"]
            ["k"     "Forward one event (▶)"]
            ["G"     "Fast-forward to latest (⏭, snap LIVE)"]
            ["Space" "Pause/resume LIVE feed"]
            ["L"     "Snap to LIVE (jump to head)"]]}
   {:group "Event list (L2)"
    :rows  [["j / k"      "Next / previous"]
            ["J / K"      "Cascade-root skip"]
            ["g g / G"    "Top / bottom"]
            ["Enter"      "Activate (= click row)"]
            ["[ / ]"      "Previous / next (10x parity)"]
            ["*"          "Pin a cascade (session-scoped)"]
            ["r"          "Rewind to before this event"]
            ["R"          "Re-dispatch this event"]
            ["o"          "Open source in editor"]
            ["/"          "Focus filter add-pill"]
            ["Ctrl+click" "Copy cascade-id"]]}
   {:group "Tab bar (L3)"
    :rows  [["1-6"             "Switch tab by index"]
            ["e"               "Epoch tab"]
            ["a"               "App-db tab"]
            ["v"               "Views tab"]
            ["t"               "Trace tab"]
            ["m"               "Machines tab"]
            ["r"               "Routes tab"]
            ["Ctrl+→ / Ctrl+←" "Next / previous tab"]]}
   {:group "Settings popup (modal-only)"
    :rows  [["g" "General tab"]
            ["k" "Keybindings tab"]
            ["b" "Buffer tab"]
            ["d" "Diff tab"]]}])

(defn- keybinding-table-row-style [zebra?]
  {:display          "grid"
   :grid-template-columns "180px 1fr"
   :gap              "12px"
   :padding          "6px 10px"
   :background       (if zebra? (:bg-2 tokens) "transparent")
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :font-size        (:body type-scale)
   :align-items      "center"})

(defn- keybindings-section []
  ;; The Handle-keys? master toggle reads the
  ;; `:rf.xray/keybinding-enabled?` atom directly — it's process
  ;; global, not under `:settings`. The dispatch flips the atom via
  ;; the setter. NB: the underlying setter only suppresses ATTACH;
  ;; a host that pre-attached the listener needs to also call
  ;; `keybinding/detach!` for the change to land immediately.
  (let [keys-on? (try
                   (config/keybinding-attach-enabled?)
                   (catch :default _ true))]
    [:div {:data-testid "rf-xray-settings-section-keybindings"}
     [:h2 {:style (section-heading-style)} "Keybindings"]

     ;; Master 'Handle keys?' toggle.
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-xray-settings-keys-master-toggle"
                :type        "checkbox"
                :checked     (boolean keys-on?)
                :on-change   (fn [^js e]
                               (let [on? (boolean (.. e -target -checked))]
                                 (config/set-keybinding-enabled! on?)))}]
       "Handle keys?"]
      [:p {:style (hint-style)}
       "Master switch for Xray's global keydown listener. Off → "
       "Xray swallows no keystrokes; the host app's bindings fire "
       "unimpeded. May require a page reload to fully detach. "
       "(Setting: " [:code {:style {:font-family mono-stack
                                    :color (:text-tertiary tokens)}}
                     ":rf.xray/keybinding-enabled?"] ")"]]

     ;; Read-only chord table. v1.1 will add rebind UI; for now
     ;; the catalogue is enough to discover the bindings.
     [:p {:style (hint-style)}
      "Read-only in v1 — rebind UI lands in v1.1. The catalogue "
      "mirrors spec/007-UX-IA.md §Keyboard."]

     (into [:div {:data-testid "rf-xray-settings-keybindings-table"
                  :style {:border        (str "1px solid " (:border-subtle tokens))
                          :border-radius "4px"
                          :overflow      "hidden"
                          :margin-top    "8px"}}]
           (apply concat
                  (for [{:keys [group rows]} keybinding-rows]
                    (concat
                      [^{:key (str "g-" group)}
                       [:div {:style {:padding     "8px 10px"
                                      :background  (:bg-1 tokens)
                                      :color       (:text-tertiary tokens)
                                      :font-size   (:caption type-scale)
                                      :font-family sans-stack
                                      :font-weight 600
                                      :text-transform "uppercase"
                                      :letter-spacing "0.05em"
                                      :border-bottom (str "1px solid " (:border-subtle tokens))}}
                        group]]
                      (map-indexed
                        (fn [idx [chord action]]
                          ^{:key (str group "-" idx)}
                          [:div {:style (keybinding-table-row-style (odd? idx))}
                           [:span {:style {:font-family mono-stack
                                           :color       (:text-primary tokens)
                                           :font-size   (:body type-scale)}}
                            chord]
                           [:span {:style {:color (:text-secondary tokens)}}
                            action]])
                        rows)))))]))

;; ---- section: Buffer (rf2-ttnst; rf2-pu9sb epoch-history consolidation) -
;;
;; Surfaces buffer-capacity knobs plus a destructive 'Clear buffer
;; now' button. The Clear button opens a confirmation modal — a
;; small nested dialog mounted inside the Settings dialog body. The
;; user must confirm before the trace-buffer is dropped, because the
;; action is silent (no undo) and destroys debug context.
;;
;; Runtime plumbing.
;;
;; * Epoch history (rf2-3zyyx, slot `:general :epoch-history`) — wired
;;   to the framework's per-frame epoch ring depth via
;;   `(rf/configure! :epoch-history {:depth N})` (see
;;   `settings/effects.cljs §apply-epoch-history!`). Slot stays under
;;   `:general` for back-compat with the persisted settings shape;
;;   only the popup home moved here (rf2-pu9sb).
;; * Cascades retained (slot `:buffer :cascades-retained`, rf2-5u03ig)
;;   — wired to the framework's per-frame trace ring via
;;   `(rf/configure! :trace-buffer {:cascades-retained N})` (see
;;   `settings/effects.cljs §apply-cascades-retained!`). The matching
;;   `:rf.xray/settings-update :buffer :cascades-retained` event
;;   applies it live; `apply-all!` replays the persisted value on boot.
;;
;; Two inputs that once sat in this section were removed: the
;; `:buffer :retained-epochs` numeric input (rf2-pu9sb — no substrate
;; consumer; it was a duplicate of the wired `:general :epoch-history`
;; slider, which lives on the General tab) and the inert
;; `:buffer :app-db/inspector-collapse-threshold` input (rf2-5u03ig —
;; no runtime consumer; the App-db inspector already auto-collapses on
;; depth/width via `:default-expanded-depth` / `:max-depth` /
;; `:max-inline-width`).

(defn- numeric-field
  "Hiccup for a numeric setting input + label + hint. Common shape
  for the three Buffer-tab knobs. rf2-h4mnh — `:html-for` ↔ `:id`
  associates label with input so clicking the label focuses the
  input AND screen readers announce them paired."
  [{:keys [testid label value default on-commit min hint]}]
  [:div {:style (field-style)}
   [:label {:html-for testid
            :style    (label-style)} label]
   [:input {:data-testid testid
            :id          testid
            :type        "number"
            :min         (str (or min 0))
            :step        "1"
            :value       (str (or value default))
            :on-change   (fn [^js e]
                           (let [n (js/parseInt (.. e -target -value) 10)]
                             (when-not (js/isNaN n)
                               (on-commit n))))
            :style       {:width        "140px"
                          :padding      "4px 8px"
                          :background   (:bg-2 tokens)
                          :color        (:text-primary tokens)
                          :border       (str "1px solid " (:border-default tokens))
                          :border-radius "4px"
                          :font-family  mono-stack}}]
   (when hint
     [:p {:style (hint-style)} hint])])

(defn- danger-button-style []
  {:background       (:red-deep tokens)
   :color            (:white tokens)
   :border           "none"
   :padding          "6px 14px"
   :border-radius    "4px"
   :cursor           "pointer"
   :font-family      sans-stack
   :font-size        (:body type-scale)
   :font-weight      500})

(defn- ghost-button-style []
  {:background       "transparent"
   :color            (:text-secondary tokens)
   :border           (str "1px solid " (:border-default tokens))
   :padding          "6px 14px"
   :border-radius    "4px"
   :cursor           "pointer"
   :font-family      sans-stack
   :font-size        (:body type-scale)
   :font-weight      500})

(defn- clear-buffer-confirm-modal [dispatch]
  ;; Inner confirmation dialog mounted inside the Settings dialog
  ;; body when `:settings-clear-confirm-open?` is true. Click outside
  ;; (the inner backdrop) cancels; explicit Cancel button cancels;
  ;; explicit Clear button confirms.
  [:div {:data-testid "rf-xray-settings-clear-confirm-backdrop"
         :on-click    (fn [^js e]
                        (.stopPropagation e)
                        (dispatch [:rf.xray/settings-cancel-clear-buffer]))
         :style {:position "absolute"
                 :inset    "0"
                 :background "rgba(0,0,0,0.45)"
                 :display  "flex"
                 :align-items "center"
                 :justify-content "center"
                 :z-index  "10"}}
   [:div {:data-testid "rf-xray-settings-clear-confirm-dialog"
          :on-click    #(.stopPropagation %)
          :style {:width  "360px"
                  :max-width "92%"
                  :background (:bg-1 tokens)
                  :border (str "1px solid " (:border-default tokens))
                  :border-radius "6px"
                  :box-shadow "rgba(0,0,0,0.4) 0 12px 32px"
                  :padding "18px 20px"
                  :font-family sans-stack
                  :color (:text-primary tokens)}}
    [:div {:style {:font-weight 600
                   :font-size   (:display type-scale)
                   :margin-bottom "8px"}}
     "Clear buffer?"]
    [:p {:style {:color (:text-secondary tokens)
                 :line-height 1.5
                 :margin "0 0 18px 0"
                 :font-size (:body type-scale)}}
     "This deletes all retained epochs. The action cannot be undone."]
    [:div {:style {:display "flex"
                   :justify-content "flex-end"
                   :gap "10px"}}
     [:button {:data-testid "rf-xray-settings-clear-cancel"
               :on-click    (fn [^js e]
                              (.stopPropagation e)
                              (dispatch
                                [:rf.xray/settings-cancel-clear-buffer]))
               :style       (ghost-button-style)}
      "Cancel"]
     [:button {:data-testid "rf-xray-settings-clear-confirm"
               :on-click    (fn [^js e]
                              (.stopPropagation e)
                              (dispatch
                                [:rf.xray/settings-clear-buffer]))
               :style       (danger-button-style)}
      "Clear"]]]])

(defn- buffer-section [dispatch]
  (let [cascades-retained @(rf/subscribe [:rf.xray/setting :buffer :cascades-retained])
        confirm-open?     @(rf/subscribe [:rf.xray/settings-clear-confirm-open?])]
    [:div {:data-testid "rf-xray-settings-section-buffer"
           :style {:position "relative"}}
     [:h2 {:style (section-heading-style)} "Buffer"]
     [:p {:style {:color (:text-secondary tokens)
                  :line-height 1.5
                  :margin "0 0 16px 0"}}
      "Tune how much history Xray retains for inspection. Lower "
      "numbers keep memory smaller; higher numbers let you scroll "
      "further back through past epochs."]

     ;; Epoch history slider was here; moved to General 2026-05-27
     ;; per Mike. The slot stays `:general :epoch-history`; only
     ;; the visual home changed.

     (numeric-field
       {:testid    "rf-xray-settings-buffer-cascades-retained"
        :label     "Cascades retained (:buffer/cascades-retained)"
        :value     cascades-retained
        :default   50
        :min       1
        :on-commit #(dispatch
                      [:rf.xray/settings-update
                       :buffer :cascades-retained %])
        :hint      "Number of cascades retained in each frame's trace ring."})

     ;; Destructive action — opens confirm modal.
     [:div {:style {:margin-top "20px"}}
      [:button {:data-testid "rf-xray-settings-clear-buffer-now"
                :on-click    (fn [^js e]
                               (.stopPropagation e)
                               (dispatch
                                 [:rf.xray/settings-confirm-clear-buffer]))
                :style       (danger-button-style)}
       "Clear buffer now"]
      [:p {:style (hint-style)}
       "Drops every retained epoch and the redaction counter. "
       "This cannot be undone."]]

     (when confirm-open?
       [clear-buffer-confirm-modal dispatch])]))

;; ---- key handling -------------------------------------------------------

(defn- editable-target?
  "True when `event.target` is a text-input surface where unmodified
  letter keys would otherwise type characters into a field. The inner
  tab-mnemonic capture skips these so users can still type numbers
  into the panel-width / long-keyword / buffer-knob inputs without
  accidentally switching tabs."
  [^js event]
  (when-let [^js target (.-target event)]
    (let [tag (some-> target .-tagName .toUpperCase)]
      (or (= tag "INPUT")
          (= tag "TEXTAREA")
          (= tag "SELECT")
          (.-isContentEditable target)))))

(defn- handle-keydown
  "Build the dialog-level keydown handler, closing over the captured
  frame-aware `dispatch` (rf2-nesy9). Captures:

   - `Escape` → close the Settings popup (always).
   - Bare-letter mnemonics (g/t/f/k/b/d) → switch the active inner
     tab. Per Mike 2026-05-19 §0ter.4 the mnemonics are modal-only —
     they conflict with the outer global `,` / `s` / `c` / `?` only
     in theory; the dialog stops propagation on every consumed key
     so the outer listener never sees them. Mnemonics are suppressed
     when the focused element is an INPUT / TEXTAREA / SELECT /
     contenteditable surface so users typing into the numeric fields
     (panel-width, long-keyword threshold, buffer knobs) are not
     interrupted by an accidental letter.
   - Every other key falls through to the host."
  [dispatch]
  (fn [^js e]
    (cond
      (or (= "Escape" (.-key e)) (= "Esc" (.-key e)))
      (do (.preventDefault e)
          (.stopPropagation e)
          (dispatch [:rf.xray/settings-close]))

      ;; Bare-letter mnemonic — only fire when (a) no modifier is held,
      ;; (b) the focused element is not editable, (c) the key maps to a
      ;; known tab id.
      (and (not (.-ctrlKey e))
           (not (.-metaKey e))
           (not (.-altKey e))
           (not (.-shiftKey e))
           (not (editable-target? e))
           (contains? mnemonic->tab-id (.-key e)))
      (let [tab-id (get mnemonic->tab-id (.-key e))]
        (.preventDefault e)
        (.stopPropagation e)
        (dispatch [:rf.xray/settings-select-tab tab-id]))

      :else nil)))

;; ---- public view --------------------------------------------------------

(defn popup-view
  "Hiccup for the open settings popup. Caller (`popup/Modal`) gates
  the mount on `:rf.xray/settings-open?` — this fn assumes it's open
  and always renders. ESC closes; click outside the dialog closes;
  the ✕ button in the header closes.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher injected by the
  `Modal` `reg-view` body — threaded down to every section helper so
  deferred handlers land on the surrounding instance frame, not a
  `{:frame :rf/xray}` literal."
  [dispatch]
  (let [active-tab  @(rf/subscribe [:rf.xray/settings-active-tab])
        positioning @(rf/subscribe [:rf.xray/modal-positioning])
        on-keydown  (handle-keydown dispatch)]
    ;; rf2-7oxvd — shared backdrop + dialog scaffold. Keeps this modal's
    ;; own `backdrop-style` / `dialog-style`, its `tab-index "-1"` dialog
    ;; root, and its `handle-keydown` (Esc-closes + bare-letter tab
    ;; mnemonics) on BOTH the backdrop and the dialog. The
    ;; `data-rf-xray-mode "settings"` marker rides in via `:dialog-extra`.
    ;; `modal-chrome` owns the positioning attribute, the click-outside
    ;; dismiss, `a11y/dialog-attrs` (role/aria-modal/accessible name from
    ;; the title id) and the `a11y/dialog-ref` focus trap (focus lands
    ;; inside on open, Tab/Shift+Tab cycle, focus restores to the ⚙
    ;; opener on close).
    (modal-chrome/modal-chrome
      {:positioning          positioning
       :backdrop-style       (backdrop-style positioning)
       :dialog-style         (dialog-style)
       :on-dismiss           #(dispatch [:rf.xray/settings-close])
       :labelled-by          "rf-xray-settings-title"
       :backdrop-testid      "rf-xray-settings-backdrop"
       :dialog-testid        "rf-xray-settings-dialog"
       :on-backdrop-key-down on-keydown
       :on-dialog-key-down   on-keydown
       :dialog-tab-index     "-1"
       :dialog-extra         {:data-rf-xray-mode "settings"}}
      ;; Header
      [:div {:style (header-style)}
       [:span {:id "rf-xray-settings-title"
               :style {:font-weight 600
                       :color       (:text-primary tokens)
                       :font-size   (:display type-scale)}}
        "Settings"]
       [:button {:data-testid "rf-xray-settings-close"
                 :aria-label  "Close settings"
                 :title       "Close settings (Esc)"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (dispatch [:rf.xray/settings-close]))
                 :style       (close-button-style)}
        "✕"]]
      ;; Tab strip — rf2-h4mnh: the strip wrapper is an explicit
      ;; `role="tablist"` so assistive tech reads the row as a tab
      ;; group rather than a generic div of buttons. `aria-label`
      ;; names the group ("Settings sections") for screen readers
      ;; that announce the landmark on entry.
      (into [:div {:data-testid "rf-xray-settings-tab-strip"
                   :role        "tablist"
                   :aria-label  "Settings sections"
                   :style       (tab-strip-style)}]
            (for [tab tabs]
              [tab-button dispatch tab (= (:id tab) active-tab)]))
      ;; Body — rf2-h4mnh: closes the tabs/tabpanel loop. The body
      ;; carries `role="tabpanel"` + an `id` matching the active
      ;; tab button's `aria-controls`, and `aria-labelledby`
      ;; pointing back at the tab button so AT announces "<Tab>
      ;; tabpanel" on focus.
      [:div {:data-testid     "rf-xray-settings-body"
             :id              (settings-tabpanel-id active-tab)
             :role            "tabpanel"
             :aria-labelledby (settings-tab-button-id active-tab)
             :style           (body-style)}
       (case active-tab
         :general     (general-section dispatch)
         :diff        (diff-section dispatch)
         :keybindings (keybindings-section)
         :buffer      (buffer-section dispatch)
         (general-section dispatch))])))
