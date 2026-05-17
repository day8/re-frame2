(ns day8.re-frame2-causa.settings.view
  "Pure-hiccup view for the Causa Settings popup modal (rf2-9poxq).

  The view body is a plain Reagent fn; `settings/popup.cljs` wraps
  it in `reg-view` so subscribes route to `:rf/causa`. Visual style
  mirrors the palette modal (dim backdrop, centred dialog,
  `tokens/bg-1` body) so the user gets a consistent affordance
  class for transient overlays."
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

(defn- backdrop-style []
  {:position         "fixed"
   :top              0
   :left             0
   :right            0
   :bottom           0
   :background       "rgba(0,0,0,0.55)"
   :backdrop-filter  "blur(2px)"
   :display          "flex"
   :align-items      "flex-start"
   :justify-content  "center"
   :padding-top      "8vh"
   :z-index          2147483646})

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
  "Ordered tab list. Per the bead's contract the modal carries four
  sections (General | Filters | Theme | Telemetry). Tab id matches
  the `:rf.causa/settings-update` `section` for sections that map
  1:1 to a settings slot."
  [{:id :general   :label "General"}
   {:id :filters   :label "Filters"}
   {:id :theme     :label "Theme"}
   {:id :telemetry :label "Telemetry"}])

(defn- tab-button [{:keys [id label]} active?]
  [:button {:data-testid (str "rf-causa-settings-tab-" (name id))
            :data-active (str active?)
            :on-click    #(rf/dispatch [:rf.causa/settings-select-tab id])
            :style       (tab-style active?)}
   label])

;; ---- section: General ---------------------------------------------------

(defn- general-section []
  (let [text-size      @(rf/subscribe [:rf.causa/setting :general :text-size])
        panel-position @(rf/subscribe [:rf.causa/setting :general :panel-position])
        auto-open?     @(rf/subscribe [:rf.causa/setting :general :auto-open-on-error?])]
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
                                                 :general :text-size n]))))
                :style       {:flex 1}}]
       [:span {:data-testid "rf-causa-settings-text-size-value"
               :style       {:font-family mono-stack
                             :color       (:text-secondary tokens)
                             :min-width   "32px"
                             :text-align  "right"}}
        (str (or text-size 13) "px")]]
      [:p {:style (hint-style)}
       "Scales every Causa text surface. Slider range 10–18 px."]]

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
                                              :general :panel-position pos])}]
         label])
      [:p {:style (hint-style)}
       "Right-rail is the default inline mount; popout opens a window; "
       "fullscreen mounts as an overlay."]]

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
                                 (boolean (.. % -target -checked))])}]
       "Auto-open Causa when an issue is observed"]
      [:p {:style (hint-style)}
       "When ON, Causa opens itself the first time the issues feed "
       "carries a non-empty entry. Off by default — you're in your "
       "app, not asking Causa to interrupt you."]]]))

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
                 :on-click    #(rf/dispatch [:rf.causa.filters/open])
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
                                   :theme nil t])}]
         label])
      [:p {:style (hint-style)}
       "Light / dark only for v1. Accent picker arrives in a later "
       "release."]]]))

;; ---- section: Telemetry -------------------------------------------------

(defn- telemetry-section []
  (let [opt-in? @(rf/subscribe [:rf.causa/setting :telemetry :opt-in?])]
    [:div {:data-testid "rf-causa-settings-section-telemetry"}
     [:h2 {:style (section-heading-style)} "Telemetry"]
     [:div {:style (field-style)}
      [:label {:style {:display "flex" :align-items "center" :gap "8px"
                       :cursor "pointer"
                       :font-size (:body type-scale)
                       :color (:text-primary tokens)}}
       [:input {:data-testid "rf-causa-settings-telemetry-opt-in"
                :type        "checkbox"
                :checked     (boolean opt-in?)
                :on-change   #(rf/dispatch
                                [:rf.causa/settings-update
                                 :telemetry :opt-in?
                                 (boolean (.. % -target -checked))])}]
       "Send anonymous usage telemetry to help improve Causa"]
      [:p {:style (hint-style)}
       "Off by default. When on, Causa sends panel-open counts and "
       "feature-use frequency to the project. No event payloads, no "
       "app-db contents — see "
       [:a {:href   "https://github.com/day8/re-frame2/blob/main/tools/causa/spec/Conventions.md"
            :target "_blank"
            :rel    "noreferrer"
            :style  {:color (:cyan tokens) :text-decoration "underline"}}
        "the privacy doc"]
       " for the exact list."]]]))

;; ---- key handling -------------------------------------------------------

(defn- handle-keydown
  [^js e]
  (case (.-key e)
    "Escape" (do (.preventDefault e)
                 (.stopPropagation e)
                 (rf/dispatch [:rf.causa/settings-close]))
    nil))

;; ---- public view --------------------------------------------------------

(defn popup-view
  "Hiccup for the open settings popup. Caller (`popup/Modal`) gates
  the mount on `:rf.causa/settings-open?` — this fn assumes it's open
  and always renders. ESC closes; click outside the dialog closes;
  the ✕ button in the header closes."
  []
  (let [active-tab @(rf/subscribe [:rf.causa/settings-active-tab])]
    [:div {:data-testid "rf-causa-settings-backdrop"
           :on-click    #(rf/dispatch [:rf.causa/settings-close])
           :on-key-down handle-keydown
           :style       (backdrop-style)}
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
                 :on-click    #(rf/dispatch [:rf.causa/settings-close])
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
         :telemetry (telemetry-section)
         (general-section))]]]))
