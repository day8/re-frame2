(ns day8.re-frame2-causa.settings.view
  "Pure-hiccup view for the Causa Settings popup modal (rf2-9poxq).

  The view body is a plain Reagent fn; `settings/popup.cljs` wraps
  it in `reg-view` so subscribes route to `:rf/causa`. Visual style
  mirrors the palette modal (dim backdrop, centred dialog,
  `tokens/bg-1` body) so the user gets a consistent affordance
  class for transient overlays.

  ## Why every dispatch carries `{:frame :rf/causa}` (rf2-smvvz)

  Subscribes resolve through the React-context tier at RENDER time —
  React's `_currentValue` for the `frame-context` is set to `:rf/causa`
  while the body of the `frame-provider`'s children is rendering, so
  `(rf/subscribe …)` from inside the popup picks up the right frame
  with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` fire
  LATER — after render commits and React has POPPED `_currentValue`
  back to the context's default (`:rf/default`). At click time the
  3-tier frame resolution chain (dynamic var → React-context tier →
  `:rf/default`) falls all the way through, the dispatch lands on
  `:rf/default`'s router, and the `:rf.causa/settings-*` handler
  reduces `:rf/default`'s db — leaving Causa's `:settings-open?` flag
  untouched. Symptom: X button does nothing, tabs do not switch, Esc
  does not close — the modal is stuck. Same shape as the
  `filters/edit_popup.cljs` + `share_modal.cljs` fix that already
  passes the opt explicitly.

  The fix is mechanical: every `rf/dispatch` from a deferred handler
  carries `{:frame :rf/causa}` so the envelope's `:frame` is set at
  call time and never depends on the click-time React-context read.
  Sister modals (palette) carry the same bug; fixed separately under
  their own beads to keep this PR scoped."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.theme.a11y :as a11y]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- feature detection --------------------------------------------------
;;
;; The auto-filter pills feature lives in a sibling ns
;; `day8.re-frame2-causa.filters` (rf2-ak4ms). If the ns is on the
;; classpath the Filters tab renders an "Open auto-filter UI" button
;; that dispatches the feature's open event; otherwise the tab shows
;; an "Install auto-filter pills feature first" hint. The detection
;; rides through `find-ns` so the popup loads cleanly whether or not
;; the sibling artefact is present.

(defn- filters-feature-present? []
  ;; Resolve at render time so a late-loaded feature lights up the
  ;; button on next render. `find-ns` is cheap; the cost is dwarfed
  ;; by the popup's other paint.
  (boolean (find-ns 'day8.re-frame2-causa.filters)))

;; ---- styles --------------------------------------------------------------
;;
;; The backdrop's `:position` and `:z-index` honour the
;; `:rf.causa/modal-positioning` opt published by `shell-view` (rf2-om6fa).
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
                          (if active? (:accent-violet tokens) "transparent"))
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
  {:background       (:accent-violet tokens)
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
  "Ordered tab list. The modal carries six sections
  (General | Filters | Theme | Diff | Keybindings | Buffer).
  Tab id matches the `:rf.causa/settings-update` `section` for
  sections that map 1:1 to a settings slot.

  Each tab carries a `:mnemonic` — a single bare letter the dialog's
  keydown handler captures while the modal is open (g/t/f/k/b/d).
  Modal-only; the outer global mnemonics (`,` / `s` / `c` / `?`)
  never fire while the dialog has focus because the dialog's
  `on-key-down` stops propagation on every consumed key. Per
  Mike's 2026-05-19 §0ter.4 walkthrough.

  Telemetry was removed (rf2-jh9ws): Causa ships no telemetry
  endpoint, and the toggle in v1 was a broken affordance — silent
  by default, no broken claims (per text-audit rf2-yn86j). When
  telemetry actually ships, the tab returns with real wiring.

  Diff (rf2-i39w2 Phase 3) carries the hiccup-diff micro-engine's
  opt-in fn-ref-changes toggle.

  Keybindings (rf2-ttnst) v1 is READ-ONLY — a table of every chord
  the global listener captures, plus a master 'Handle keys?' toggle
  (alias for `:launch.keybinding/enabled?`). Rebind UI is the v1.1
  follow-on.

  Buffer (rf2-ttnst) surfaces the trace-buffer / epoch-buffer /
  inspector-collapse-threshold knobs + a 'Clear buffer now' button
  with a confirmation modal (destructive action)."
  [{:id :general     :label "General"     :mnemonic "g"}
   {:id :theme       :label "Theme"       :mnemonic "t"}
   {:id :filters     :label "Filters"     :mnemonic "f"}
   {:id :keybindings :label "Keybindings" :mnemonic "k"}
   {:id :buffer      :label "Buffer"      :mnemonic "b"}
   {:id :diff        :label "Diff"        :mnemonic "d"}])

(def ^:private mnemonic->tab-id
  "Reverse-lookup table the dialog keydown handler consults to map
  a bare-letter keystroke to its target tab id. Built once at ns
  load."
  (into {} (for [{:keys [mnemonic id]} tabs] [mnemonic id])))

(defn- tab-button [{:keys [id label]} active?]
  [:button {:data-testid (str "rf-causa-settings-tab-" (name id))
            :data-active (str active?)
            :on-click    #(rf/dispatch [:rf.causa/settings-select-tab id]
                                       {:frame :rf/causa})
            :style       (tab-style active?)}
   label])

;; ---- section: General ---------------------------------------------------

(defn- general-section []
  (let [text-size       @(rf/subscribe [:rf.causa/setting :general :text-size])
        panel-position  @(rf/subscribe [:rf.causa/setting :general :panel-position])
        panel-width-px  @(rf/subscribe [:rf.causa/panel-width-px])
        auto-open?      @(rf/subscribe [:rf.causa/setting :general :auto-open-on-error?])
        density         @(rf/subscribe [:rf.causa/density])
        long-kw-thresh  @(rf/subscribe [:rf.causa/long-keyword-threshold])
        show-tool?      @(rf/subscribe [:rf.causa/show-tool-frames?])
        show-ungrouped? @(rf/subscribe [:rf.causa/show-ungrouped?])]
    [:div {:data-testid "rf-causa-settings-section-general"}
     [:h2 {:style (section-heading-style)} "General"]

     ;; ── Text size slider ────────────────────────────────────────
     [:div {:style (field-style)}
      [:label {:style (label-style)} "Text size"]
      [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
       [:input {:data-testid "rf-causa-settings-text-size-input"
                :type        "range"
                :min         "10"
                :max         "18"
                :step        "1"
                :value       (str (or text-size 13))
                :on-change   (fn [e]
                               (let [n (js/parseInt (.. e -target -value) 10)]
                                 (when-not (js/isNaN n)
                                   (rf/dispatch [:rf.causa/settings-update
                                                 :general :text-size n]
                                                {:frame :rf/causa}))))
                :style       {:flex 1}}]
       [:span {:data-testid "rf-causa-settings-text-size-value"
               :style       {:font-family mono-stack
                             :color       (:text-secondary tokens)
                             :min-width   "32px"
                             :text-align  "right"}}
        (str (or text-size 13) "px")]]]

     ;; ── Panel width (rf2-x8h9y resize handle numeric override) ─
     [:div {:style (field-style)}
      [:label {:style (label-style)} "Panel width (px)"]
      [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
       [:input {:data-testid "rf-causa-settings-panel-width-input"
                :type        "number"
                :min         "320"
                :step        "10"
                :value       (str (or panel-width-px 560))
                :on-change   (fn [^js e]
                               (let [n (js/parseInt (.. e -target -value) 10)]
                                 (when-not (js/isNaN n)
                                   (rf/dispatch
                                     [:rf.causa/set-panel-width-px n]
                                     {:frame :rf/causa}))))
                :style       {:width        "120px"
                              :padding      "4px 8px"
                              :background   (:bg-2 tokens)
                              :color        (:text-primary tokens)
                              :border       (str "1px solid " (:border-default tokens))
                              :border-radius "4px"
                              :font-family  mono-stack}}]
       [:button {:data-testid "rf-causa-settings-panel-width-reset"
                 :title       "Reset to default (560px)"
                 :on-click    #(rf/dispatch [:rf.causa/reset-panel-width]
                                            {:frame :rf/causa})
                 :style       {:background "transparent"
                               :border     (str "1px solid " (:border-default tokens))
                               :color      (:text-secondary tokens)
                               :cursor     "pointer"
                               :padding    "4px 10px"
                               :border-radius "4px"
                               :font-family sans-stack
                               :font-size  (:body type-scale)}}
        "Reset"]]
      [:p {:style (hint-style)}
       "Drag the left edge of the Causa panel to resize, or set "
       "an exact pixel width here. Double-click the handle to reset."]]

     ;; ── Panel position radio ────────────────────────────────────
     [:div {:style (field-style)}
      [:span {:style (label-style)} "Panel position"]
      (for [[pos label] [[:right-rail "Right rail (inline)"]
                         [:popout     "Popout window"]
                         [:fullscreen "Fullscreen overlay"]]]
        ^{:key pos}
        [:label {:style {:display "flex" :align-items "center" :gap "8px"
                         :cursor  "pointer"
                         :font-size (:body type-scale)
                         :color   (:text-primary tokens)}}
         [:input {:data-testid (str "rf-causa-settings-panel-position-" (name pos))
                  :type        "radio"
                  :name        "rf-causa-settings-panel-position"
                  :checked     (= panel-position pos)
                  :on-change   #(rf/dispatch [:rf.causa/settings-update
                                              :general :panel-position pos]
                                             {:frame :rf/causa})}]
         label])]

     ;; ── Auto-open-on-error checkbox ─────────────────────────────
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-causa-settings-auto-open-on-error"
                :type        "checkbox"
                :checked     (boolean auto-open?)
                :on-change   #(rf/dispatch
                                [:rf.causa/settings-update
                                 :general :auto-open-on-error?
                                 (boolean (.. % -target -checked))]
                                {:frame :rf/causa})}]
       "Auto-open Causa when an issue is observed"]]

     ;; ── Density radio (rf2-ttnst — Cosy / Compact; no Comfy) ───
     ;;
     ;; Per Mike 2026-05-19 §0ter.4 the Comfy tier is dropped; v1 ships
     ;; only two densities. The setting drives Views detail-row padding
     ;; + App-db diff-row line-height — runtime plumbing into individual
     ;; panels lands incrementally; consumers read `:rf.causa/density`
     ;; (see `settings/subs.cljs`).
     [:div {:style (field-style)}
      [:span {:style (label-style)} "Density"]
      (for [[d label] [[:cosy    "Cosy (default)"]
                       [:compact "Compact"]]]
        ^{:key d}
        [:label {:style {:display "flex" :align-items "center" :gap "8px"
                         :cursor "pointer"
                         :font-size (:body type-scale)
                         :color (:text-primary tokens)}}
         [:input {:data-testid (str "rf-causa-settings-density-" (name d))
                  :type        "radio"
                  :name        "rf-causa-settings-density"
                  :checked     (= density d)
                  :on-change   #(rf/dispatch
                                  [:rf.causa/settings-update
                                   :general :density d]
                                  {:frame :rf/causa})}]
         label])
      [:p {:style (hint-style)}
       "Vertical-rhythm knob for Views detail rows and App-db diff rows."]]

     ;; ── Long-keyword wrap threshold ─────────────────────────────
     [:div {:style (field-style)}
      [:label {:style (label-style)} "Long-keyword threshold (chars)"]
      [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
       [:input {:data-testid "rf-causa-settings-long-keyword-threshold"
                :type        "number"
                :min         "8"
                :max         "120"
                :step        "1"
                :value       (str (or long-kw-thresh 24))
                :on-change   (fn [^js e]
                               (let [n (js/parseInt (.. e -target -value) 10)]
                                 (when-not (js/isNaN n)
                                   (rf/dispatch
                                     [:rf.causa/settings-update
                                      :general :long-keyword-threshold n]
                                     {:frame :rf/causa}))))
                :style       {:width        "120px"
                              :padding      "4px 8px"
                              :background   (:bg-2 tokens)
                              :color        (:text-primary tokens)
                              :border       (str "1px solid " (:border-default tokens))
                              :border-radius "4px"
                              :font-family  mono-stack}}]]
      [:p {:style (hint-style)}
       "Fully-qualified keywords longer than this elide in compact list cells."]]

     ;; ── Power user divider + show-tool-frames toggle (rf2-ttnst) ─
     ;;
     ;; Per Mike Q8: the `:show-tool-frames?` toggle lives under a
     ;; `── Power user ──` divider at the bottom of General. Default
     ;; OFF. Flipping on reveals `:rf/causa` + `:rf/pair2` in the L1
     ;; frame-picker dropdown (per spec/007-UX-IA.md §Frame-observation
     ;; isolation invariant I1).
     [:div {:data-testid "rf-causa-settings-power-user-divider"
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

     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-causa-settings-show-tool-frames"
                :type        "checkbox"
                :checked     (boolean show-tool?)
                :on-change   #(rf/dispatch
                                [:rf.causa/settings-update
                                 :general :show-tool-frames?
                                 (boolean (.. % -target -checked))]
                                {:frame :rf/causa})}]
       "Show tool frames in picker"]
      [:p {:style (hint-style)}
       "Reveals " [:code {:style {:font-family mono-stack
                                  :color (:text-tertiary tokens)}}
                   ":rf/causa"]
       " + " [:code {:style {:font-family mono-stack
                             :color (:text-tertiary tokens)}}
              ":rf/pair2"]
       " in the L1 frame-picker dropdown. Default OFF — tool frames"
       " observing themselves is a known anti-pattern (see "
       "spec/007-UX-IA.md §Frame-observation isolation invariants)."]]

     ;; ── Show :ungrouped pseudo-cascade events (rf2-r9lyy) ──────
     ;;
     ;; Opt-in surface for the `:ungrouped` bucket produced by
     ;; `re-frame.trace.projection/group-cascades` (registry-time
     ;; emits, frame lifecycle outside a drain, `:rf.ssr/hydration-
     ;; mismatch`, REPL evals). Default OFF preserves Causa's
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
       [:input {:data-testid "rf-causa-settings-show-ungrouped"
                :type        "checkbox"
                :checked     (boolean show-ungrouped?)
                :on-change   #(rf/dispatch
                                [:rf.causa/settings-update
                                 :general :show-ungrouped?
                                 (boolean (.. % -target -checked))]
                                {:frame :rf/causa})}]
       "Show :ungrouped pseudo-cascade events in L2"]
      [:p {:style (hint-style)}
       "Reveals events outside any dispatch — "
       [:code {:style {:font-family mono-stack
                       :color (:text-tertiary tokens)}}
        ":rf.ssr/*"]
       ", registry-time emits, REPL evals, frame lifecycle. "
       "Default OFF — Causa is silent-by-default. Useful when "
       "debugging SSR / REPL flows."]]]))

;; ---- section: Filters ---------------------------------------------------

(defn- filters-section []
  (let [present? (filters-feature-present?)]
    [:div {:data-testid "rf-causa-settings-section-filters"}
     [:h2 {:style (section-heading-style)} "Filters"]
     [:p {:style {:color (:text-secondary tokens)
                  :line-height 1.5
                  :margin "0 0 16px 0"}}
      "Auto-filter pills hide high-volume noise (mouse-move, "
      "anim-frame, etc.) from the event list so you can focus on the "
      "events that actually drive your app. Pills live in the top "
      "ribbon; this section is the management surface."]
     (if present?
       [:button {:data-testid "rf-causa-settings-filters-open"
                 :on-click    #(rf/dispatch [:rf.causa.filters/open]
                                            {:frame :rf/causa})
                 :style       (primary-button-style)}
        "Open auto-filter UI"]
       [:div {:data-testid "rf-causa-settings-filters-install-hint"
              :style       {:padding       "12px 14px"
                            :background    (:bg-2 tokens)
                            :border        (str "1px solid " (:border-subtle tokens))
                            :border-radius "4px"
                            :color         (:text-secondary tokens)
                            :font-size     (:caption type-scale)
                            :line-height   1.5}}
        "Install the auto-filter pills feature first. The "
        [:code {:style {:font-family mono-stack
                        :color (:text-tertiary tokens)}}
         "day8.re-frame2-causa.filters"]
        " namespace is not on the classpath."])]))

;; ---- section: Theme -----------------------------------------------------

(defn- theme-section []
  (let [theme @(rf/subscribe [:rf.causa/setting :theme nil])]
    [:div {:data-testid "rf-causa-settings-section-theme"}
     [:h2 {:style (section-heading-style)} "Theme"]
     [:div {:style (field-style)}
      [:span {:style (label-style)} "Colour theme"]
      (for [[t label] [[:dark  "Dark (default)"]
                       [:light "Light"]]]
        ^{:key t}
        [:label {:style {:display "flex" :align-items "center" :gap "8px"
                         :cursor "pointer"
                         :font-size (:body type-scale)
                         :color (:text-primary tokens)}}
         [:input {:data-testid (str "rf-causa-settings-theme-" (name t))
                  :type        "radio"
                  :name        "rf-causa-settings-theme"
                  :checked     (= theme t)
                  :on-change   #(rf/dispatch
                                  [:rf.causa/settings-update
                                   :theme nil t]
                                  {:frame :rf/causa})}]
         label])]]))

;; ---- section: Diff (rf2-i39w2 Phase 3) ----------------------------------

(defn- diff-section []
  (let [highlight? @(rf/subscribe [:rf.causa/setting :diff :highlight-fn-ref-changes?])]
    [:div {:data-testid "rf-causa-settings-section-diff"}
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
       [:input {:data-testid "rf-causa-settings-diff-highlight-fn-ref"
                :type        "checkbox"
                :checked     (boolean highlight?)
                :on-change   #(rf/dispatch
                                [:rf.causa/settings-update
                                 :diff :highlight-fn-ref-changes?
                                 (boolean (.. % -target -checked))]
                                {:frame :rf/causa})}]
       "Highlight function-ref changes in view hiccup"]
      [:p {:style (hint-style)}
       "Off by default. The hiccup-diff engine treats function-valued "
       "props (`:on-click`, `:on-change`, `:ref`, …) as opaque — "
       "anonymous fns created fresh per render do NOT surface as a "
       "diff. Flip this on when diagnosing memoization issues (a "
       "child re-renders because the parent passes a new fn every "
       "time); identity-different fns will surface as a distinct "
       "violet `(fn ref changed)` chip."]]]))

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
;; `:launch.keybinding/enabled?` config slot (rf2-4eyik) — flipping
;; it false disables the global listener until next page-load. The
;; effect is global; the popup is just the surface.

(def ^:private keybinding-rows
  "Static catalogue. Group key carries a section label; rows are
  `[chord action]` pairs. Mirrors the tables in spec/007-UX-IA.md
  §Keyboard."
  [{:group "Global shortcuts"
    :rows  [["Ctrl+Shift+C" "Toggle Causa visibility"]
            ["?"            "Keyboard cheat-sheet"]
            [", or s"       "Settings popup"]
            ["Esc"          "Close modal / collapse popover / focus event list"]
            ["Ctrl+K / ⌘K"  "Command palette"]
            ["Ctrl+F"       "Find within active tab"]
            ["o"            "Popout (window.open whole shell)"]
            ["c"            "Causality popover (from any tab)"]]}
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
            ["e"               "Event tab"]
            ["a"               "App-db tab"]
            ["v"               "Views tab"]
            ["t"               "Trace tab"]
            ["m"               "Machines tab"]
            ["i"               "Issues tab"]
            ["Ctrl+→ / Ctrl+←" "Next / previous tab"]]}
   {:group "Settings popup (modal-only)"
    :rows  [["g" "General tab"]
            ["t" "Theme tab"]
            ["f" "Filters tab"]
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
  ;; `:launch.keybinding/enabled?` atom directly — it's process
  ;; global, not under `:settings`. The dispatch flips the atom via
  ;; the setter. NB: the underlying setter only suppresses ATTACH;
  ;; a host that pre-attached the listener needs to also call
  ;; `keybinding/detach!` for the change to land immediately.
  (let [keys-on? (try
                   (config/keybinding-attach-enabled?)
                   (catch :default _ true))]
    [:div {:data-testid "rf-causa-settings-section-keybindings"}
     [:h2 {:style (section-heading-style)} "Keybindings"]

     ;; Master 'Handle keys?' toggle.
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-causa-settings-keys-master-toggle"
                :type        "checkbox"
                :checked     (boolean keys-on?)
                :on-change   (fn [^js e]
                               (let [on? (boolean (.. e -target -checked))]
                                 (config/set-keybinding-enabled! on?)))}]
       "Handle keys?"]
      [:p {:style (hint-style)}
       "Master switch for Causa's global keydown listener. Off → "
       "Causa swallows no keystrokes; the host app's bindings fire "
       "unimpeded. May require a page reload to fully detach. "
       "(Setting: " [:code {:style {:font-family mono-stack
                                    :color (:text-tertiary tokens)}}
                     ":launch.keybinding/enabled?"] ")"]]

     ;; Read-only chord table. v1.1 will add rebind UI; for now
     ;; the catalogue is enough to discover the bindings.
     [:p {:style (hint-style)}
      "Read-only in v1 — rebind UI lands in v1.1. The catalogue "
      "mirrors spec/007-UX-IA.md §Keyboard."]

     (into [:div {:data-testid "rf-causa-settings-keybindings-table"
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

;; ---- section: Buffer (rf2-ttnst) ----------------------------------------
;;
;; Three numeric inputs plus a destructive 'Clear buffer now' button.
;; The Clear button opens a confirmation modal — a small nested dialog
;; mounted inside the Settings dialog body. The user must confirm
;; before the trace-buffer is dropped, because the action is silent
;; (no undo) and destroys debug context.
;;
;; Runtime plumbing: the three numeric knobs persist into
;; `:settings :buffer` via the standard `:rf.causa/settings-update`
;; event. Consumption is incremental — trace-bus' set-buffer-depth!
;; is wired here for `:trace-buffer/keep`; the other two knobs are
;; stored for future consumers (inspector + epoch buffer) until those
;; layers land.

(defn- numeric-field
  "Hiccup for a numeric setting input + label + hint. Common shape
  for the three Buffer-tab knobs."
  [{:keys [testid label value default on-commit min hint]}]
  [:div {:style (field-style)}
   [:label {:style (label-style)} label]
   [:input {:data-testid testid
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

(defn- clear-buffer-confirm-modal []
  ;; Inner confirmation dialog mounted inside the Settings dialog
  ;; body when `:settings-clear-confirm-open?` is true. Click outside
  ;; (the inner backdrop) cancels; explicit Cancel button cancels;
  ;; explicit Clear button confirms.
  [:div {:data-testid "rf-causa-settings-clear-confirm-backdrop"
         :on-click    (fn [^js e]
                        (.stopPropagation e)
                        (rf/dispatch [:rf.causa/settings-cancel-clear-buffer]
                                     {:frame :rf/causa}))
         :style {:position "absolute"
                 :inset    "0"
                 :background "rgba(0,0,0,0.45)"
                 :display  "flex"
                 :align-items "center"
                 :justify-content "center"
                 :z-index  "10"}}
   [:div {:data-testid "rf-causa-settings-clear-confirm-dialog"
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
     [:button {:data-testid "rf-causa-settings-clear-cancel"
               :on-click    (fn [^js e]
                              (.stopPropagation e)
                              (rf/dispatch
                                [:rf.causa/settings-cancel-clear-buffer]
                                {:frame :rf/causa}))
               :style       (ghost-button-style)}
      "Cancel"]
     [:button {:data-testid "rf-causa-settings-clear-confirm"
               :on-click    (fn [^js e]
                              (.stopPropagation e)
                              (rf/dispatch
                                [:rf.causa/settings-clear-buffer]
                                {:frame :rf/causa}))
               :style       (danger-button-style)}
      "Clear"]]]])

(defn- buffer-section []
  (let [retained-epochs   @(rf/subscribe [:rf.causa/setting :buffer :retained-epochs])
        trace-keep        @(rf/subscribe [:rf.causa/setting :buffer :trace-buffer/keep])
        collapse-thresh   @(rf/subscribe [:rf.causa/setting :buffer
                                          :app-db/inspector-collapse-threshold])
        confirm-open?     @(rf/subscribe [:rf.causa/settings-clear-confirm-open?])]
    [:div {:data-testid "rf-causa-settings-section-buffer"
           :style {:position "relative"}}
     [:h2 {:style (section-heading-style)} "Buffer"]
     [:p {:style {:color (:text-secondary tokens)
                  :line-height 1.5
                  :margin "0 0 16px 0"}}
      "Tune how much history Causa retains for inspection. Lower "
      "numbers keep memory smaller; higher numbers let you scroll "
      "further back through past epochs."]

     (numeric-field
       {:testid    "rf-causa-settings-buffer-retained-epochs"
        :label     "Retained epochs (:buffer/retained-epochs)"
        :value     retained-epochs
        :default   200
        :min       1
        :on-commit #(rf/dispatch
                      [:rf.causa/settings-update
                       :buffer :retained-epochs %]
                      {:frame :rf/causa})
        :hint      "Number of epochs kept in the Causa epoch buffer."})

     (numeric-field
       {:testid    "rf-causa-settings-buffer-trace-keep"
        :label     "Trace buffer keep (:trace-buffer/keep)"
        :value     trace-keep
        :default   1000
        :min       1
        :on-commit #(rf/dispatch
                      [:rf.causa/settings-update
                       :buffer :trace-buffer/keep %]
                      {:frame :rf/causa})
        :hint      "Number of raw trace events Causa retains."})

     (numeric-field
       {:testid    "rf-causa-settings-buffer-inspector-collapse"
        :label     "App-db inspector collapse threshold"
        :value     collapse-thresh
        :default   50
        :min       1
        :on-commit #(rf/dispatch
                      [:rf.causa/settings-update
                       :buffer :app-db/inspector-collapse-threshold %]
                      {:frame :rf/causa})
        :hint      "Branch factor above which the App-db inspector collapses by default."})

     ;; Destructive action — opens confirm modal.
     [:div {:style {:margin-top "20px"}}
      [:button {:data-testid "rf-causa-settings-clear-buffer-now"
                :on-click    (fn [^js e]
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.causa/settings-confirm-clear-buffer]
                                 {:frame :rf/causa}))
                :style       (danger-button-style)}
       "Clear buffer now"]
      [:p {:style (hint-style)}
       "Drops every retained epoch and the redaction counter. "
       "This cannot be undone."]]

     (when confirm-open?
       [clear-buffer-confirm-modal])]))

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
  "Dialog-level keydown handler. Captures:

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
  [^js e]
  (cond
    (or (= "Escape" (.-key e)) (= "Esc" (.-key e)))
    (do (.preventDefault e)
        (.stopPropagation e)
        (rf/dispatch [:rf.causa/settings-close]
                     {:frame :rf/causa}))

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
      (rf/dispatch [:rf.causa/settings-select-tab tab-id]
                   {:frame :rf/causa}))

    :else nil))

;; ---- public view --------------------------------------------------------

(defn popup-view
  "Hiccup for the open settings popup. Caller (`popup/Modal`) gates
  the mount on `:rf.causa/settings-open?` — this fn assumes it's open
  and always renders. ESC closes; click outside the dialog closes;
  the ✕ button in the header closes."
  []
  (let [active-tab  @(rf/subscribe [:rf.causa/settings-active-tab])
        positioning @(rf/subscribe [:rf.causa/modal-positioning])]
    [:div {:data-testid "rf-causa-settings-backdrop"
           :data-rf-causa-modal-positioning (name (or positioning :fixed))
           :on-click    #(rf/dispatch [:rf.causa/settings-close]
                                      {:frame :rf/causa})
           :on-key-down handle-keydown
           :style       (backdrop-style positioning)}
     [:div (merge
             ;; rf2-7389r — WAI-ARIA dialog contract: role/aria-modal/
             ;; aria-labelledby pointing at the visible title span.
             ;; `:ref` focuses the first focusable descendant on mount
             ;; so keyboard users land inside the modal rather than on
             ;; <body> (audit finding #3 + #8).
             (a11y/dialog-attrs {:labelled-by "rf-causa-settings-title"})
             {:data-testid "rf-causa-settings-dialog"
              :data-rf-causa-mode "settings"
              :ref         (a11y/focus-on-mount-ref)
              :on-click    #(.stopPropagation %)
              :on-key-down handle-keydown
              :tab-index   "-1"
              :style       (dialog-style)})
      ;; Header
      [:div {:style (header-style)}
       [:span {:id "rf-causa-settings-title"
               :style {:font-weight 600
                       :color       (:text-primary tokens)
                       :font-size   (:display type-scale)}}
        "Settings"]
       [:button {:data-testid "rf-causa-settings-close"
                 :aria-label  "Close settings"
                 :title       "Close settings (Esc)"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/settings-close]
                                             {:frame :rf/causa}))
                 :style       (close-button-style)}
        "✕"]]
      ;; Tab strip
      (into [:div {:data-testid "rf-causa-settings-tab-strip"
                   :style       (tab-strip-style)}]
            (for [tab tabs]
              [tab-button tab (= (:id tab) active-tab)]))
      ;; Body
      [:div {:data-testid "rf-causa-settings-body"
             :style       (body-style)}
       (case active-tab
         :general     (general-section)
         :filters     (filters-section)
         :theme       (theme-section)
         :diff        (diff-section)
         :keybindings (keybindings-section)
         :buffer      (buffer-section)
         (general-section))]]]))
