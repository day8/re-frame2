(ns xray.devtools
  "Xray DevTools — authoritative SURFACE reference.

  This file is a self-contained, loadable Reagent render of the
  AUTHORITATIVE Xray surface design produced by Figma Make. It is a
  REFERENCE ARTEFACT, not the implementation — the live shell
  (`tools/xray/src/day8/re_frame2_xray/shell.cljs` + the per-panel
  views) carries the real subscriptions, filters, frame-switching,
  keybindings, settings, share modal, resize handles, etc. This file
  exists so a human (or agent) can see the TARGET LOOK at a glance and
  diff the live chrome against it.

  ## The Figma-Make surface, as rendered here

  1. **Chrome ribbon → DARK.** The top chrome ribbon paints the dark
     `--devtools-chrome-ribbon-bg` band with white
     `--devtools-chrome-ribbon-text` ink in BOTH themes. Label reads
     `Event History`. The filter affordance is a `+ filter` TEXT button.
     A sun/moon THEME TOGGLE flips light ⇄ dark.
  2. **Dark tabs ribbon.** The tab strip is a dark band carrying
     ROUNDED-TOP tab buttons (`border-radius 4px 4px 0 0`; active = light
     `--devtools-chrome-ribbon-tab-active` fill, inactive = translucent),
     prefixed with a `↳ for selected event` contextual label. The first
     tab is `Handler`.
  3. **Events ribbon layout.** LEFT → RIGHT: a `↳ filters:` label + an
     add-filter `+` button; the green/red filter pills (each with a
     vertical divider before the `✕`); then the `N events filtered out`
     count pushed to the RIGHT end.
  4. **Event-list column order:** `event id` FIRST, then `source`,
     `timestamp`, `duration`.
  5. **Handler panel** numbered lifecycle pipeline is 6 steps in operational
     order: 1 DISPATCH, 2 COEFFECTS, 3 EVENT HANDLER, 4 FLOWS,
     5 APP-DB CHANGES, 6 FX. FLOWS sits BETWEEN EVENT HANDLER and
     APP-DB CHANGES so the panel's section rhythm tracks the operational
     order (handler returns pending `:db` → flows reshape → atomic commit
     → fx).

  ## What this reference keeps richer than the Figma stub

  The Figma export stubs the `app-db` panel. This reference carries the
  richer `app-db-panel` section (APP STATE / MACHINE / ROUTE cards)
  rather than the stub — the live App-db panel is the contractual
  surface and the reference documents its shape.

  ## What this reference DELIBERATELY diverges from the Figma authority

  - **Inactive-tab background opacity = 0.12 (NOT the authority's 0.2)**
    — see `tab-button` below. The Figma authority paints inactive tabs
    at `rgba(255,255,255,0.2)`; both this mirror and the live shell
    (`tools/xray/src/day8/re_frame2_xray/shell.cljs`) use 0.12 instead.
    The quieter inactive fill lets the active tab POP in the rhythm
    of the dark tabs ribbon; 0.2 makes the inactive tabs read as
    competing siblings rather than backgrounded options. This is a
    deliberate, contractual divergence from the authority — codified
    here so a future audit doesn't raise it as drift.

  ## Out of scope (WIP)

  The Machine panel design is a work-in-progress. This reference carries
  NO Machine-panel design — the Machine TAB exists in the tab bar (one
  label) but its body is a placeholder here."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; ============================================================================
;; CSS Styles
;; ============================================================================

(def devtools-css
  "/* Xray DevTools - Developer tool typography scale anchored at 13px */

:root {
  /* Type scale for developer tools */
  --devtools-body: 13px;
  --devtools-body-tight: 12px;
  --devtools-caption: 11px;
  --devtools-micro: 10px;

  /* Developer tool colors */
  --devtools-chrome-bg: #f5f5f5;
  /* dark chrome ribbon band (DARK even under the light theme) */
  --devtools-chrome-ribbon-bg: #2a2a2a;
  --devtools-chrome-ribbon-text: #ffffff;
  --devtools-chrome-ribbon-text-muted: #b0b0b0;
  --devtools-chrome-ribbon-tab-active: #ffffff;
  --devtools-chrome-ribbon-tab-active-text: #24292f;
  --devtools-border: #d1d1d1;
  --devtools-hover: #e8e8e8;
  --devtools-active: #0969da;
  --devtools-active-bg: #0969da;
  --devtools-active-text: #ffffff;
  --devtools-text: #24292f;
  --devtools-text-muted: #656d76;
  --devtools-changed: #0969da;
  --devtools-unchanged: #8c959f;
  --devtools-error: #cf222e;
  --devtools-warning: #9a6700;
  --devtools-success: #1a7f37;
}

.dark {
  --devtools-chrome-bg: #1c1c1c;
  --devtools-chrome-ribbon-bg: #0d1117;
  --devtools-chrome-ribbon-text: #e6edf3;
  --devtools-chrome-ribbon-text-muted: #8b949e;
  --devtools-chrome-ribbon-tab-active: #2a2a2a;
  --devtools-chrome-ribbon-tab-active-text: #e6edf3;
  --devtools-border: #373737;
  --devtools-hover: #2a2a2a;
  --devtools-active: #539bf5;
  --devtools-active-bg: #1f6feb;
  --devtools-active-text: #ffffff;
  --devtools-text: #e6edf3;
  --devtools-text-muted: #8b949e;
  --devtools-changed: #539bf5;
  --devtools-unchanged: #6e7681;
  --devtools-error: #f85149;
  --devtools-warning: #d29922;
  --devtools-success: #3fb950;
}

/* Monospace for code/EDN values */
.devtools-mono {
  font-family: 'SF Mono', Monaco, 'Cascadia Code', 'Roboto Mono', Consolas, 'Courier New', monospace;
}

/* Type scale utilities */
.devtools-body { font-size: var(--devtools-body); line-height: 1.4; }
.devtools-body-tight { font-size: var(--devtools-body-tight); line-height: 1.3; }
.devtools-caption { font-size: var(--devtools-caption); line-height: 1.3; }
.devtools-micro { font-size: var(--devtools-micro); line-height: 1.2; }

/* Syntax highlighting for code */
.syntax-keyword { color: #cf222e; }
.dark .syntax-keyword { color: #ff7b72; }
.syntax-string { color: #0a3069; }
.dark .syntax-string { color: #a5d6ff; }
.syntax-number { color: #0550ae; }
.dark .syntax-number { color: #79c0ff; }")

;; ============================================================================
;; SVG Icons (simplified - replace with your icon library)
;; ============================================================================

(defn chevron-left []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M15 18l-6-6 6-6"}]])

(defn chevron-right []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M9 18l6-6-6-6"}]])

(defn chevrons-right []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M13 17l5-5-5-5M6 17l5-5-5-5"}]])

(defn chevron-down []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M6 9l6 6 6-6"}]])

(defn settings-icon []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path {:d "M12 1v6m0 6v6m5.196-15.804l-4.242 4.242m-5.908 5.908l-4.242 4.242m15.804-10.392l-4.242 4.242m-5.908 5.908l-4.242 4.242"}]])

(defn x-icon []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M18 6L6 18M6 6l12 12"}]])

(defn moon []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"}]])

(defn sun []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:circle {:cx "12" :cy "12" :r "5"}]
   [:path {:d "M12 1v2m0 18v2M4.22 4.22l1.42 1.42m12.72 12.72l1.42 1.42M1 12h2m18 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"}]])

(defn corner-down-right []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:polyline {:points "15 10 20 15 15 20"}]
   [:path {:d "M4 4v7a4 4 0 0 0 4 4h12"}]])

(defn external-link []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]
   [:polyline {:points "15 3 21 3 21 9"}]
   [:line {:x1 "10" :y1 "14" :x2 "21" :y2 "3"}]])

;; ============================================================================
;; Chrome Ribbon Component (DARK band + Event History + theme toggle)
;; ============================================================================

(defn chrome-ribbon [is-dark toggle-theme]
  [:div {:style {:height "34px"
                 :padding "0 12px"
                 :display "flex"
                 :align-items "center"
                 :justify-content "space-between"
                 :border-bottom "1px solid var(--devtools-border)"
                 :background "var(--devtools-chrome-ribbon-bg)"}}

   ;; Left side — `Event History` label + nav cluster + `+ filter`
   [:div {:style {:display "flex" :align-items "center" :gap "13px"}}
    [:span {:class "devtools-body" :style {:color "var(--devtools-chrome-ribbon-text)" :font-weight "500"}}
     "Event History"]

    ;; Navigation buttons (blue --devtools-active-bg fill, white icon)
    [:div {:style {:display "flex" :gap "8px"}}
     (for [[k icon] [["prev" chevron-left] ["next" chevron-right] ["head" chevrons-right]]]
       ^{:key k}
       [:button {:style {:width "21px" :height "21px" :border-radius "4px"
                         :background "var(--devtools-active-bg)" :color "var(--devtools-active-text)"
                         :border "none" :cursor "pointer" :display "flex"
                         :align-items "center" :justify-content "center"}}
        [icon]])]

    ;; `+ filter` TEXT button.
    [:button {:class "devtools-caption"
              :style {:height "21px" :padding "0 8px" :border-radius "4px"
                      :border "1px solid var(--devtools-chrome-ribbon-text-muted)"
                      :background "transparent" :color "var(--devtools-chrome-ribbon-text-muted)"
                      :cursor "pointer"}}
     "+ filter"]]

   ;; Right side — Frame + Mode dropdowns, theme toggle, settings, close
   [:div {:style {:display "flex" :align-items "center" :gap "13px"}}
    [:button {:class "devtools-body-tight"
              :style {:display "flex" :align-items "center" :gap "8px" :padding "8px"
                      :border-radius "4px" :background "transparent" :border "none"
                      :color "var(--devtools-chrome-ribbon-text)" :cursor "pointer"
                      :width "89px" :justify-content "space-between"}}
     [:span "Main"]
     [chevron-down]]

    [:button {:class "devtools-body-tight"
              :style {:display "flex" :align-items "center" :gap "8px" :padding "8px"
                      :border-radius "4px" :background "transparent" :border "none"
                      :color "var(--devtools-chrome-ribbon-text)" :cursor "pointer"
                      :width "144px" :justify-content "space-between"}}
     [:span "Dynamic"]
     [chevron-down]]

    ;; sun/moon THEME TOGGLE. In the live shell this flips the
    ;; `:theme` setting (toggling `rf-xray-theme-light/dark` on
    ;; the shell + <html> root); here it toggles the local `dark` class so
    ;; the reference previews both palettes.
    [:button {:style {:padding "8px" :border-radius "4px" :background "transparent"
                      :border "none" :color "var(--devtools-chrome-ribbon-text-muted)"
                      :cursor "pointer" :display "flex" :align-items "center" :justify-content "center"}
              :on-click toggle-theme}
     (if @is-dark [sun] [moon])]

    [:button {:style {:padding "8px" :border-radius "4px" :background "transparent"
                      :border "none" :color "var(--devtools-chrome-ribbon-text-muted)"
                      :cursor "pointer" :display "flex" :align-items "center" :justify-content "center"}}
     [settings-icon]]

    [:button {:style {:padding "8px" :border-radius "4px" :background "transparent"
                      :border "none" :color "var(--devtools-chrome-ribbon-text-muted)"
                      :cursor "pointer" :display "flex" :align-items "center" :justify-content "center"}}
     [x-icon]]]])

;; ============================================================================
;; Events Ribbon Component (filters: label + add(+) left, count right)
;; ============================================================================

(defn filter-pill [label tone]
  [:div {:class "devtools-micro"
         :style {:display "flex" :align-items "center" :overflow "hidden"
                 :border-radius "4px" :border (str "1px solid " tone)
                 :height "21px" :color tone}}
   [:span {:style {:padding "0 8px"}} label]
   ;; VERTICAL DIVIDER before the ✕.
   [:div {:style {:width "1px" :height "13px" :background tone}}]
   [:button {:style {:width "21px" :height "21px" :border "none" :background "transparent"
                     :color "inherit" :cursor "pointer" :display "flex"
                     :align-items "center" :justify-content "center"}}
    [x-icon]]])

(defn events-ribbon []
  [:div {:style {:height "34px" :padding-left "34px" :padding-right "12px"
                 :display "flex" :align-items "center" :gap "13px"
                 :border-bottom "1px solid var(--devtools-border)"
                 :background "var(--devtools-chrome-bg)"}}

   ;; LEFT — `↳ filters:` label + add-filter (+) button
   [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
    [:span {:class "devtools-caption" :style {:color "var(--devtools-text-muted)"
                                              :display "flex" :align-items "center" :gap "8px"}}
     [corner-down-right]
     "filters:"]

    [:button {:style {:width "21px" :height "21px" :border-radius "4px"
                      :border "1px solid var(--devtools-border)" :background "transparent"
                      :color "var(--devtools-text-muted)" :cursor "pointer"
                      :display "flex" :align-items "center" :justify-content "center"}}
     [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
      [:path {:d "M12 5v14m-7-7h14"}]]]]

   ;; Filter pills — green-bordered INCLUDE, red-bordered EXCLUDE.
   [filter-pill "view" "var(--devtools-success)"]
   [filter-pill "fx" "var(--devtools-success)"]
   [filter-pill "timer" "var(--devtools-error)"]

   ;; RIGHT — `N events filtered out` count pushed to the trailing edge.
   [:span {:class "devtools-caption" :style {:color "var(--devtools-warning)" :font-weight "500"
                                             :margin-left "auto"}}
    "12 events filtered out"]])

;; ============================================================================
;; Event List Component (event-id FIRST, then source)
;; ============================================================================

(defn event-list []
  (let [height (r/atom 144)
        ;; column order is event-id FIRST, then source.
        mock-events [{:event-id ":title/flow"  :source "fx"     :timestamp "12:30:05.123" :duration "1.2 ms"}
                     {:event-id ":counter-inc" :source "view"   :timestamp "12:30:06.456" :duration "0.4 ms" :active? true}
                     {:event-id ":poll/tick"   :source "timer"  :timestamp "12:30:07.001" :duration "0.2 ms"}
                     {:event-id ":user/profile" :source "view"  :timestamp "12:30:07.892" :duration "0.6 ms"}
                     {:event-id ":title/loaded" :source "machine" :timestamp "12:30:08.234" :duration "0.3 ms"}
                     {:event-id ":form/submit" :source "view"   :timestamp "12:30:09.456" :duration "1.8 ms"}]]
    (fn []
      [:div {:style {:border-bottom "1px solid var(--devtools-border)" :position "relative"}}
       [:div {:style {:height (str @height "px") :overflow "auto"}}
        [:table {:class "devtools-body" :style {:width "100%" :border-collapse "collapse"}}
         [:thead {:style {:position "sticky" :top "0" :background "var(--devtools-chrome-bg)" :border-bottom "1px solid var(--devtools-border)"}}
          [:tr {:class "devtools-caption" :style {:color "var(--devtools-text-muted)"}}
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "event id"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "source"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "timestamp"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "duration"]]]
         [:tbody
          (for [[idx event] (map-indexed vector mock-events)]
            ^{:key idx}
            [:tr {:style {:border-bottom "1px solid var(--devtools-border)"
                          :background (when (:active? event) "var(--devtools-hover)")
                          :cursor "pointer"}}
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text)"}} (:event-id event)]
             [:td {:style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:source event)]
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:timestamp event)]
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:duration event)]])]]]

       ;; Resize handle
       [:div {:style {:position "absolute" :bottom "0" :left "0" :right "0"
                      :height "4px" :cursor "ns-resize" :background "transparent"}}]])))

;; ============================================================================
;; Tabs Ribbon Component (DARK band, rounded-top tabs, Handler tab)
;; ============================================================================

(defn tab-button [active-tab tab-id label]
  (let [is-active (= @active-tab tab-id)]
    ;; inactive fill is rgba(...,0.12), NOT the Figma authority's 0.2.
    ;; Deliberate; see the docstring (§What this reference DELIBERATELY
    ;; diverges from the Figma authority).
    [:button {:class "devtools-body"
              :style {:padding "3px 16px" :border-radius "4px 4px 0 0" :border "none"
                      :background (if is-active "var(--devtools-chrome-ribbon-tab-active)" "rgba(255,255,255,0.12)")
                      :color (if is-active "var(--devtools-chrome-ribbon-tab-active-text)" "var(--devtools-chrome-ribbon-text-muted)")
                      :cursor "pointer" :transition "all 0.2s"}
              :on-click #(reset! active-tab tab-id)}
     label]))

(defn tabs-ribbon [active-tab]
  [:div {:style {:height "34px" :padding "0 12px" :display "flex" :align-items "flex-end" :gap "3px"
                 :border-bottom "1px solid var(--devtools-border)"
                 :background "var(--devtools-chrome-ribbon-bg)"}}

   ;; `↳ for selected event` contextual label.
   [:span {:class "devtools-caption" :style {:color "var(--devtools-chrome-ribbon-text-muted)"
                                             :margin-right "13px" :display "flex"
                                             :align-items "center" :gap "8px" :align-self "center"}}
    [corner-down-right]
    "for selected event"]

   ;; first tab is `Handler`. Machine TAB carries one label; its panel
   ;; body design is WIP and out of scope here.
   [tab-button active-tab :handler "Handler"]
   [tab-button active-tab :app-db "app-db"]
   [tab-button active-tab :views "Views"]
   [tab-button active-tab :trace "Trace"]
   [tab-button active-tab :machine "Machine"]
   [tab-button active-tab :routes "Routes"]
   [tab-button active-tab :issues "Issues"]])

;; ============================================================================
;; Handler Panel Component (6-step operational order:
;; DISPATCH / COEFFECTS / EVENT HANDLER / FLOWS / APP-DB CHANGES / FX)
;; ============================================================================

(defn step-circle [n]
  [:div {:class "devtools-caption"
         :style {:position "absolute" :width "21px" :height "21px" :border-radius "50%"
                 :background "var(--devtools-text-muted)" :color "var(--devtools-chrome-bg)"
                 :display "flex" :align-items "center" :justify-content "center"
                 :font-weight "600" :left "-44px" :top "0" :z-index "10"}}
   (str n)])

(defn step-label [title]
  [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em"
                                          :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
   title])

(defn handler-panel []
  [:div {:class "devtools-body" :style {:padding "21px" :overflow "auto" :color "var(--devtools-text)"}}
   [:div {:style {:position "relative" :margin-left "55px"}}
    ;; Vertical pipeline line
    [:div {:style {:position "absolute" :width "2px" :background "var(--devtools-border)"
                   :left "-34px" :top "13px" :bottom "0"}}]

    [:div {:style {:display "flex" :flex-direction "column" :gap "21px"}}
     ;; 1. DISPATCH
     [:section {:style {:position "relative"}}
      [step-circle 1]
      [step-label "DISPATCH"]
      [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px"}}
       "[:counter-inc]"]
      [:div {:class "devtools-caption" :style {:margin-top "8px" :color "var(--devtools-text-muted)"
                                               :display "flex" :align-items "center" :gap "8px"}}
       "FROM:"
       [:a {:href "#" :style {:color "var(--devtools-active)" :text-decoration "none"
                              :display "flex" :align-items "center" :gap "4px"}}
        "view" [external-link]]]]

     ;; 2. COEFFECTS — may show MULTIPLE coeffects.
     [:section {:style {:position "relative"}}
      [step-circle 2]
      [step-label "COEFFECTS"]
      [:div {:style {:display "flex" :flex-direction "column" :gap "13px"}}
       [:div
        [:a {:class "devtools-mono" :href "#" :style {:color "var(--devtools-active)" :text-decoration "none"
                                                      :display "flex" :align-items "center" :gap "4px" :margin-bottom "3px"}}
         ":now" [external-link]]
        [:div {:class "devtools-mono" :style {:display "flex" :align-items "center" :gap "8px"}}
         [:span {:style {:color "var(--devtools-success)"}} "+"]
         [:span {:style {:color "var(--devtools-text-muted)"}} "[:now]"]
         [:span {:style {:color "var(--devtools-success)"}} "#inst \"2026-05-23T12:30:05.123Z\""]]]
       [:div
        [:a {:class "devtools-mono" :href "#" :style {:color "var(--devtools-active)" :text-decoration "none"
                                                      :display "flex" :align-items "center" :gap "4px" :margin-bottom "3px"}}
         ":session" [external-link]]
        [:div {:class "devtools-mono" :style {:display "flex" :align-items "center" :gap "8px"}}
         [:span {:style {:color "var(--devtools-success)"}} "+"]
         [:span {:style {:color "var(--devtools-text-muted)"}} "[:session]"]
         [:span {:style {:color "var(--devtools-success)"}} "{:user-id 42, :token \"...\"}"]]]]]

     ;; 3. EVENT HANDLER
     [:section {:style {:position "relative"}}
      [step-circle 3]
      [:a {:class "devtools-caption" :href "#" :style {:text-transform "uppercase" :letter-spacing "0.05em"
                                                       :color "var(--devtools-active)" :text-decoration "none"
                                                       :display "inline-flex" :align-items "center" :gap "4px"
                                                       :margin-bottom "8px"}}
       "EVENT HANDLER" [external-link]]
      [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px"
                                            :border-radius "4px" :overflow-x "auto"}}
       [:pre {:style {:margin "0"}}
        [:span {:class "syntax-keyword"} "(rf/reg-event "] [:span {:class "syntax-keyword"} ":counter-inc"] "\n"
        "  (" [:span {:class "syntax-keyword"} "fn"] " [{:keys [db]} _]\n"
        "    {" [:span {:class "syntax-keyword"} ":db"] " (" [:span {:class "syntax-keyword"} "update"] " db " [:span {:class "syntax-keyword"} ":counter"] " inc)}))"]]]

     ;; 4. FLOWS (step 4, between EVENT HANDLER and APP-DB CHANGES,
     ;; matching operational order). Shows the flow(s) that recomputed
     ;; + their db contribution.
     [:section {:style {:position "relative"}}
      [step-circle 4]
      [step-label "FLOWS"]
      [:div {:class "devtools-mono"}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:a {:href "#" :style {:color "var(--devtools-active)" :text-decoration "none"
                               :display "flex" :align-items "center" :gap "4px"}}
         ":totals-flow" [external-link]]
        [:span {:style {:color "var(--devtools-text-muted)"}} "→"]
        [:span {:style {:color "var(--devtools-text)"}} "[:totals] recomputed"]]
       [:div {:style {:margin-left "21px" :margin-top "3px" :color "var(--devtools-text-muted)"}}
        [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
         [:span {:style {:color "var(--devtools-success)"}} "+"]
         [:span "[:totals :sum]"]
         [:span {:style {:color "var(--devtools-success)"}} "42"]]]]]

     ;; 5. APP-DB CHANGES (step 5, after FLOWS, matching operational
     ;; order). Carries the `committed diff` caption to clarify it's the
     ;; post-flow t4 observable.
     [:section {:style {:position "relative"}}
      [step-circle 5]
      [step-label "APP-DB CHANGES"]
      [:div {:class "devtools-caption" :style {:color "var(--devtools-text-muted)"
                                               :font-style "italic" :margin-bottom "4px"}}
       "committed diff (post-flow · atomic install boundary)"]
      [:div {:class "devtools-mono" :style {:display "flex" :flex-direction "column" :gap "4px"}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:span {:style {:color "var(--devtools-changed)"}} "~"]
        [:span {:style {:color "var(--devtools-text-muted)"}} "[:counter]"]
        [:span {:style {:color "var(--devtools-error)"}} "1"]
        [:span {:style {:color "var(--devtools-text-muted)"}} "→"]
        [:span {:style {:color "var(--devtools-success)"}} "2"]]
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:span {:style {:color "var(--devtools-success)"}} "+"]
        [:span {:style {:color "var(--devtools-text-muted)"}} "[:last-updated]"]
        [:span {:style {:color "var(--devtools-success)"}} "#inst \"2026-05-23T12:30:05\""]]]]

     ;; 6. FX (carries the post-commit · irreversible caption)
     [:section {:style {:position "relative"}}
      [step-circle 6]
      [step-label "FX"]
      [:div {:class "devtools-caption" :style {:color "var(--devtools-text-muted)"
                                               :font-style "italic" :margin-bottom "4px"}}
       "post-commit · irreversible (fx throws don't wind app-db back)"]
      [:div {:class "devtools-mono" :style {:display "flex" :flex-direction "column" :gap "4px"}}
       [:div {:style {:display "flex" :gap "8px"}}
        [:span {:style {:color "var(--devtools-text-muted)"}} ":dispatch"]
        [:span {:style {:color "var(--devtools-text)"}} "→ [:title/flow [:rf/init]]"]]
       [:div {:style {:display "flex" :gap "8px"}}
        [:span {:style {:color "var(--devtools-text-muted)"}} ":http-xhrio"]
        [:span {:style {:color "var(--devtools-text)"}} "→ {:method :get, :uri \"/api/data\"}"]]]]]]])

;; ============================================================================
;; App-DB Panel Component (RETAINED — richer than the Figma stub)
;; ============================================================================

(defn app-db-panel []
  [:div {:class "devtools-body" :style {:padding "21px" :display "flex" :flex-direction "column" :gap "21px" :overflow "auto"}}
   [:section
    [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
     "APP STATE"]
    [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px" :color "var(--devtools-text)"}}
     "{:counter 2, :user {:name \"Alice\" :role :admin}}"]]

   [:div {:style {:border-top "1px solid var(--devtools-border)"}}]

   [:section
    [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
     "MACHINE :title/flow"]
    [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px" :color "var(--devtools-text)"}}
     "{:state :loaded, :context {:data [...]}}"]]

   [:div {:style {:border-top "1px solid var(--devtools-border)"}}]

   [:section
    [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
     "ROUTE"]
    [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px" :color "var(--devtools-text)"}}
     "{:id :home, :params {}}"]]])

(defn placeholder-panel [name]
  [:div {:class "devtools-body" :style {:padding "21px" :color "var(--devtools-text-muted)"}}
   [:p (str name " panel content here")]])

;; ============================================================================
;; Main App Component
;; ============================================================================

(defn main-app []
  (let [active-tab (r/atom :handler)
        is-dark    (r/atom false)
        toggle-theme #(do
                        (swap! is-dark not)
                        (if @is-dark
                          (.add (.-classList js/document.documentElement) "dark")
                          (.remove (.-classList js/document.documentElement) "dark")))]
    (fn []
      [:div {:style {:height "100vh" :display "flex" :flex-direction "column"
                     :background "var(--devtools-chrome-bg)" :color "var(--devtools-text)"
                     :font-family "system-ui, -apple-system, sans-serif"}}
       ;; CSS injection
       [:style devtools-css]

       ;; Region 1: Chrome Ribbon (DARK)
       [chrome-ribbon is-dark toggle-theme]

       ;; Region 2: Events Ribbon
       [events-ribbon]

       ;; Region 3: Event List
       [event-list]

       ;; Region 4 & 5: Tabs Ribbon (DARK) and Panel Content
       [:div {:style {:flex "1" :display "flex" :flex-direction "column" :overflow "hidden"}}
        [tabs-ribbon active-tab]
        [:div {:style {:flex "1" :overflow "hidden"}}
         (case @active-tab
           :handler [handler-panel]
           :app-db  [app-db-panel]
           ;; Machine panel design is WIP (out of scope) — placeholder.
           [placeholder-panel (name @active-tab)])]]])))

;; ============================================================================
;; Application Mount
;; ============================================================================

(defn ^:export init []
  (rdom/render [main-app]
               (.getElementById js/document "app")))

;; For hot-reload during development
(defn ^:dev/after-load reload []
  (init))
