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
   :color            "#fff"
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
  (General | Filters | Theme | Diff). Tab id matches the
  `:rf.causa/settings-update` `section` for sections that map 1:1
  to a settings slot.

  Telemetry was removed (rf2-jh9ws): Causa ships no telemetry
  endpoint, and the toggle in v1 was a broken affordance — silent
  by default, no broken claims (per text-audit rf2-yn86j). When
  telemetry actually ships, the tab returns with real wiring.

  Diff (rf2-i39w2 Phase 3) carries the hiccup-diff micro-engine's
  opt-in fn-ref-changes toggle."
  [{:id :general   :label "General"}
   {:id :filters   :label "Filters"}
   {:id :theme     :label "Theme"}
   {:id :diff      :label "Diff"}])

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
        auto-open?      @(rf/subscribe [:rf.causa/setting :general :auto-open-on-error?])]
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
       "Auto-open Causa when an issue is observed"]]]))

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

;; ---- key handling -------------------------------------------------------

(defn- handle-keydown
  [^js e]
  (case (.-key e)
    "Escape" (do (.preventDefault e)
                 (.stopPropagation e)
                 (rf/dispatch [:rf.causa/settings-close]
                              {:frame :rf/causa}))
    nil))

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
     [:div {:data-testid "rf-causa-settings-dialog"
            :data-rf-causa-mode "settings"
            :on-click    #(.stopPropagation %)
            :on-key-down handle-keydown
            :tab-index   "-1"
            :style       (dialog-style)}
      ;; Header
      [:div {:style (header-style)}
       [:span {:style {:font-weight 600
                       :color       (:text-primary tokens)
                       :font-size   (:display type-scale)}}
        "Settings"]
       [:button {:data-testid "rf-causa-settings-close"
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
         :general   (general-section)
         :filters   (filters-section)
         :theme     (theme-section)
         :diff      (diff-section)
         (general-section))]]]))
