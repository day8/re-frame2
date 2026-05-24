(ns causa.devtools
  "Causa DevTools - Single-file Reagent implementation"
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; ============================================================================
;; CSS Styles
;; ============================================================================

(def devtools-css
  "/* Causa DevTools - Developer tool typography scale anchored at 13px */

:root {
  /* Type scale for developer tools */
  --devtools-body: 13px;
  --devtools-body-tight: 12px;
  --devtools-caption: 11px;
  --devtools-micro: 10px;

  /* Developer tool colors */
  --devtools-chrome-bg: #f5f5f5;
  --devtools-border: #d1d1d1;
  --devtools-hover: #e8e8e8;
  --devtools-active: #0969da;
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
  --devtools-border: #373737;
  --devtools-hover: #2a2a2a;
  --devtools-active: #539bf5;
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
.devtools-body {
  font-size: var(--devtools-body);
  line-height: 1.4;
}

.devtools-body-tight {
  font-size: var(--devtools-body-tight);
  line-height: 1.3;
}

.devtools-caption {
  font-size: var(--devtools-caption);
  line-height: 1.3;
}

.devtools-micro {
  font-size: var(--devtools-micro);
  line-height: 1.2;
}

/* Syntax highlighting for code */
.syntax-keyword { color: #cf222e; }
.dark .syntax-keyword { color: #ff7b72; }
.syntax-string { color: #0a3069; }
.dark .syntax-string { color: #a5d6ff; }
.syntax-number { color: #0550ae; }
.dark .syntax-number { color: #79c0ff; }
.syntax-comment { color: #6e7781; font-style: italic; }
.dark .syntax-comment { color: #8b949e; }")

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

(defn plus []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M12 5v14M5 12h14"}]])

(defn chevron-down []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M6 9l6 6 6-6"}]])

(defn settings-icon []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path {:d "M12 1v6m0 6v6M5.64 5.64l4.24 4.24m4.24 4.24l4.24 4.24M1 12h6m6 0h6M5.64 18.36l4.24-4.24m4.24-4.24l4.24-4.24"}]])

(defn x-icon []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M18 6L6 18M6 6l12 12"}]])

(defn external-link []
  [:svg {:width "13" :height "13" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3"}]])

;; ============================================================================
;; Chrome Ribbon Component
;; ============================================================================

(defn chrome-ribbon []
  (let [selected-frame "Main"]
    [:div {:style {:height "34px"
                   :padding "0 12px"
                   :display "flex"
                   :align-items "center"
                   :justify-content "space-between"
                   :border-bottom "1px solid var(--devtools-border)"
                   :background "var(--devtools-chrome-bg)"}}
     ;; Left: Events navigation and Filters
     [:div {:style {:display "flex" :align-items "center" :gap "13px"}}
      [:span {:class "devtools-body" :style {:color "var(--devtools-text)" :font-weight "500"}} "Events"]

      ;; Navigation buttons
      [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
       [:button {:style {:padding "8px"
                         :border-radius "4px"
                         :background "var(--devtools-active)"
                         :color "white"
                         :border "none"
                         :cursor "pointer"
                         :opacity "0.9"}
                 :on-mouse-enter #(set! (.. % -target -style -opacity) "1")
                 :on-mouse-leave #(set! (.. % -target -style -opacity) "0.9")}
        [chevron-left]]
       [:button {:style {:padding "8px"
                         :border-radius "4px"
                         :background "var(--devtools-active)"
                         :color "white"
                         :border "none"
                         :cursor "pointer"
                         :opacity "0.9"}
                 :on-mouse-enter #(set! (.. % -target -style -opacity) "1")
                 :on-mouse-leave #(set! (.. % -target -style -opacity) "0.9")}
        [chevron-right]]
       [:button {:style {:padding "8px"
                         :border-radius "4px"
                         :background "var(--devtools-active)"
                         :color "white"
                         :border "none"
                         :cursor "pointer"
                         :opacity "0.9"}
                 :on-mouse-enter #(set! (.. % -target -style -opacity) "1")
                 :on-mouse-leave #(set! (.. % -target -style -opacity) "0.9")}
        [chevrons-right]]]

      [:span {:class "devtools-body" :style {:color "var(--devtools-text-muted)"}} "Filters:"]

      [:button {:style {:padding "8px"
                        :border-radius "4px"
                        :background "transparent"
                        :border "none"
                        :cursor "pointer"
                        :color "var(--devtools-text-muted)"}
                :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
       [plus]]]

     ;; Right: Dropdowns and action buttons
     [:div {:style {:display "flex" :align-items "center" :gap "13px"}}
      [:button {:class "devtools-body-tight"
                :style {:display "flex"
                        :align-items "center"
                        :gap "8px"
                        :padding "8px"
                        :border-radius "4px"
                        :background "transparent"
                        :border "none"
                        :cursor "pointer"
                        :color "var(--devtools-text)"
                        :width "89px"
                        :justify-content "space-between"}
                :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
       [:span selected-frame]
       [chevron-down]]

      [:button {:class "devtools-body-tight"
                :style {:display "flex"
                        :align-items "center"
                        :gap "8px"
                        :padding "8px"
                        :border-radius "4px"
                        :background "transparent"
                        :border "none"
                        :cursor "pointer"
                        :color "var(--devtools-text)"
                        :width "144px"
                        :justify-content "space-between"}
                :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
       [:span "Dynamic"]
       [chevron-down]]

      [:button {:style {:padding "8px"
                        :border-radius "4px"
                        :background "transparent"
                        :border "none"
                        :cursor "pointer"
                        :color "var(--devtools-text-muted)"}
                :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
       [settings-icon]]

      [:button {:style {:padding "8px"
                        :border-radius "4px"
                        :background "transparent"
                        :border "none"
                        :cursor "pointer"
                        :color "var(--devtools-text-muted)"}
                :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
       [x-icon]]]]))

;; ============================================================================
;; Events Ribbon Component
;; ============================================================================

(defn events-ribbon []
  (let [include-filters ["view" "fx"]
        exclude-filters ["timer"]
        filtered-out-count 12]
    [:div {:style {:height "34px"
                   :padding "0 12px"
                   :display "flex"
                   :align-items "center"
                   :gap "13px"
                   :border-bottom "1px solid var(--devtools-border)"
                   :background "var(--devtools-chrome-bg)"}}
     [:span {:class "devtools-caption" :style {:color "var(--devtools-warning)" :font-weight "500"}}
      (str filtered-out-count " events filtered out")]

     ;; Include filter pills
     (for [filter include-filters]
       ^{:key (str "include-" filter)}
       [:div {:class "devtools-micro"
              :style {:padding "2px 8px"
                      :border-radius "4px"
                      :border "1px solid var(--devtools-success)"
                      :background "transparent"
                      :color "var(--devtools-success)"
                      :display "flex"
                      :align-items "center"
                      :gap "8px"}}
        [:span filter]
        [:button {:style {:background "transparent"
                          :border "none"
                          :cursor "pointer"
                          :color "inherit"
                          :padding "0"
                          :display "flex"
                          :align-items "center"}
                  :on-mouse-enter #(set! (.. % -target -style -opacity) "0.75")
                  :on-mouse-leave #(set! (.. % -target -style -opacity) "1")}
         [x-icon]]])

     ;; Exclude filter pills
     (for [filter exclude-filters]
       ^{:key (str "exclude-" filter)}
       [:div {:class "devtools-micro"
              :style {:padding "2px 8px"
                      :border-radius "4px"
                      :border "1px solid var(--devtools-error)"
                      :background "transparent"
                      :color "var(--devtools-error)"
                      :display "flex"
                      :align-items "center"
                      :gap "8px"}}
        [:span filter]
        [:button {:style {:background "transparent"
                          :border "none"
                          :cursor "pointer"
                          :color "inherit"
                          :padding "0"
                          :display "flex"
                          :align-items "center"}
                  :on-mouse-enter #(set! (.. % -target -style -opacity) "0.75")
                  :on-mouse-leave #(set! (.. % -target -style -opacity) "1")}
         [x-icon]]])]))

;; ============================================================================
;; Event List Component
;; ============================================================================

(defn event-list []
  (let [height (r/atom 144)
        mock-events [{:source "fx" :event-id ":title/flow" :timestamp "12:30:05.123" :duration "1.2 ms"}
                     {:source "view" :event-id ":counter-inc" :timestamp "12:30:06.456" :duration "0.4 ms" :active? true}
                     {:source "timer" :event-id ":poll/tick" :timestamp "12:30:07.001" :duration "0.2 ms"}
                     {:source "view" :event-id ":user/profile" :timestamp "12:30:07.892" :duration "0.6 ms"}
                     {:source "machine" :event-id ":title/loaded" :timestamp "12:30:08.234" :duration "0.3 ms"}
                     {:source "view" :event-id ":form/submit" :timestamp "12:30:09.456" :duration "1.8 ms"}]]
    (fn []
      [:div {:style {:border-bottom "1px solid var(--devtools-border)" :position "relative"}}
       [:div {:style {:height (str @height "px") :overflow "auto"}}
        [:table {:class "devtools-body" :style {:width "100%" :border-collapse "collapse"}}
         [:thead {:style {:position "sticky" :top "0" :background "var(--devtools-chrome-bg)" :border-bottom "1px solid var(--devtools-border)"}}
          [:tr {:class "devtools-caption" :style {:color "var(--devtools-text-muted)"}}
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "source"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "event id"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "timestamp"]
           [:th {:style {:text-align "left" :padding "8px 13px" :font-weight "500"}} "duration"]]]
         [:tbody
          (for [[idx event] (map-indexed vector mock-events)]
            ^{:key idx}
            [:tr {:style {:border-bottom "1px solid var(--devtools-border)"
                          :background (when (:active? event) "var(--devtools-hover)")
                          :cursor "pointer"}
                  :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-hover)")
                  :on-mouse-leave #(set! (.. % -target -style -background) (when (:active? event) "var(--devtools-hover)"))}
             [:td {:style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:source event)]
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text)"}} (:event-id event)]
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:timestamp event)]
             [:td {:class "devtools-mono" :style {:padding "4px 13px" :color "var(--devtools-text-muted)"}} (:duration event)]])]]]

       ;; Resize handle
       [:div {:style {:position "absolute"
                      :bottom "0"
                      :left "0"
                      :right "0"
                      :height "4px"
                      :cursor "ns-resize"
                      :background "transparent"}
              :on-mouse-enter #(set! (.. % -target -style -background) "var(--devtools-active)")
              :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}]])))

;; ============================================================================
;; Event Panel Component
;; ============================================================================

(defn event-panel []
  [:div {:class "devtools-body" :style {:padding "21px" :overflow "auto" :color "var(--devtools-text)"}}
   [:div {:style {:margin-left "55px" :position "relative"}}
    ;; Vertical pipeline line
    [:div {:style {:position "absolute"
                   :width "2px"
                   :background "var(--devtools-border)"
                   :left "-34px"
                   :top "13px"
                   :bottom "0"}}]

    [:div {:style {:display "flex" :flex-direction "column" :gap "21px"}}
     ;; 1. DISPATCH
     [:section {:style {:position "relative"}}
      [:div {:style {:position "absolute"
                     :width "21px"
                     :height "21px"
                     :border-radius "50%"
                     :background "var(--devtools-text-muted)"
                     :color "white"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :left "-44px"
                     :top "0"
                     :font-size "11px"
                     :font-weight "600"}} "1"]
      [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
       "DISPATCH"]
      [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px"}}
       "[:counter-inc]"]
      [:div {:class "devtools-caption" :style {:margin-top "8px" :color "var(--devtools-text-muted)" :display "flex" :align-items "center" :gap "8px"}}
       "FROM:"
       [:a {:href "#" :style {:color "var(--devtools-active)" :text-decoration "none" :display "flex" :align-items "center" :gap "4px"}
            :on-mouse-enter #(set! (.. % -target -style -text-decoration) "underline")
            :on-mouse-leave #(set! (.. % -target -style -text-decoration) "none")}
        "view" [external-link]]]]

     ;; 2. COEFFECTS
     [:section {:style {:position "relative"}}
      [:div {:style {:position "absolute"
                     :width "21px"
                     :height "21px"
                     :border-radius "50%"
                     :background "var(--devtools-text-muted)"
                     :color "white"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :left "-44px"
                     :top "0"
                     :font-size "11px"
                     :font-weight "600"}} "2"]
      [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
       "COEFFECTS"]
      [:div {:style {:display "flex" :flex-direction "column" :gap "13px"}}
       [:div
        [:div {:style {:display "flex" :align-items "center" :gap "4px" :margin-bottom "8px"}}
         [:a {:class "devtools-mono" :href "#" :style {:color "var(--devtools-active)" :text-decoration "none" :display "flex" :align-items "center" :gap "4px"}
              :on-mouse-enter #(set! (.. % -target -style -text-decoration) "underline")
              :on-mouse-leave #(set! (.. % -target -style -text-decoration) "none")}
          ":now" [external-link]]]
        [:div {:class "devtools-mono"}
         [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
          [:span {:style {:color "var(--devtools-success)"}} "+"]
          [:span {:style {:color "var(--devtools-text-muted)"}} "[:now]"]
          [:span {:style {:color "var(--devtools-success)"}} "#inst \"2026-05-23T12:30:05.123Z\""]]]]]]

     ;; 3. EVENT HANDLER
     [:section {:style {:position "relative"}}
      [:div {:style {:position "absolute"
                     :width "21px"
                     :height "21px"
                     :border-radius "50%"
                     :background "var(--devtools-text-muted)"
                     :color "white"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :left "-44px"
                     :top "0"
                     :font-size "11px"
                     :font-weight "600"}} "3"]
      [:a {:class "devtools-caption" :href "#" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-active)" :text-decoration "none" :display "inline-flex" :align-items "center" :gap "4px" :margin-bottom "8px"}
           :on-mouse-enter #(set! (.. % -target -style -text-decoration) "underline")
           :on-mouse-leave #(set! (.. % -target -style -text-decoration) "none")}
       "EVENT HANDLER" [external-link]]
      [:div {:class "devtools-mono" :style {:background "var(--devtools-hover)" :padding "13px" :border-radius "4px" :overflow-x "auto"}}
       [:pre {:style {:font-size "13px"}}
        [:span {:class "syntax-keyword"} "(rf/reg-event-db "] [:span {:class "syntax-keyword"} ":counter-inc"] "\n"
        "  [" [:span {:class "syntax-keyword"} ":rf/db"] "]\n"
        "  (" [:span {:class "syntax-keyword"} "fn"] " [db _]\n"
        "    (" [:span {:class "syntax-keyword"} "update"] " db " [:span {:class "syntax-keyword"} ":counter"] " inc)))"]]]

     ;; 4. DB CHANGES
     [:section {:style {:position "relative"}}
      [:div {:style {:position "absolute"
                     :width "21px"
                     :height "21px"
                     :border-radius "50%"
                     :background "var(--devtools-text-muted)"
                     :color "white"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :left "-44px"
                     :top "0"
                     :font-size "11px"
                     :font-weight "600"}} "4"]
      [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
       "DB CHANGES"]
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

     ;; 5. FX
     [:section {:style {:position "relative"}}
      [:div {:style {:position "absolute"
                     :width "21px"
                     :height "21px"
                     :border-radius "50%"
                     :background "var(--devtools-text-muted)"
                     :color "white"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :left "-44px"
                     :top "0"
                     :font-size "11px"
                     :font-weight "600"}} "5"]
      [:h3 {:class "devtools-caption" :style {:text-transform "uppercase" :letter-spacing "0.05em" :color "var(--devtools-text-muted)" :margin-bottom "8px"}}
       "FX"]
      [:div {:class "devtools-mono" :style {:display "flex" :flex-direction "column" :gap "4px"}}
       [:div {:style {:display "flex" :align-items "flex-start" :gap "8px"}}
        [:span {:style {:color "var(--devtools-text-muted)"}} ":dispatch"]
        [:span {:style {:color "var(--devtools-text)"}} "→ [:title/flow [:rf/init]]"]]
       [:div {:style {:display "flex" :align-items "flex-start" :gap "8px"}}
        [:span {:style {:color "var(--devtools-text-muted)"}} ":http-xhrio"]
        [:span {:style {:color "var(--devtools-text)"}} "→ {:method :get, :uri \"/api/data\"}"]]]]]]]])

;; ============================================================================
;; App-DB Panel Component (simplified)
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

;; ============================================================================
;; Main App Component
;; ============================================================================

(defn main-app []
  (let [active-tab (r/atom "event")]
    (fn []
      [:div {:style {:height "100vh" :display "flex" :flex-direction "column" :background "var(--background)" :color "var(--devtools-text)" :font-family "system-ui, -apple-system, sans-serif"}}
       ;; CSS injection
       [:style devtools-css]

       ;; Region 1: Chrome Ribbon
       [chrome-ribbon]

       ;; Region 2: Events Ribbon
       [events-ribbon]

       ;; Region 3: Event List
       [event-list]

       ;; Region 4 & 5: Tabs and Panel Content
       [:div {:style {:flex "1" :display "flex" :flex-direction "column" :overflow "hidden"}}
        ;; Tab Strip
        [:div {:style {:height "34px"
                       :padding "0 12px"
                       :display "flex"
                       :align-items "center"
                       :gap "0"
                       :border-bottom "1px solid var(--devtools-border)"
                       :background "var(--devtools-chrome-bg)"
                       :flex-shrink "0"}}
         (for [tab [{:value "event" :label "Event"}
                    {:value "app-db" :label "app-db"}
                    {:value "views" :label "Views"}
                    {:value "trace" :label "Trace"}
                    {:value "machine" :label "Machine"}
                    {:value "routes" :label "Routes"}
                    {:value "issues" :label "Issues"}]]
           ^{:key (:value tab)}
           [:button {:class "devtools-body"
                     :style {:padding "0 16px"
                             :height "100%"
                             :border "none"
                             :border-bottom (if (= @active-tab (:value tab))
                                              "2px solid var(--devtools-active)"
                                              "2px solid transparent")
                             :background "transparent"
                             :color (if (= @active-tab (:value tab))
                                      "var(--devtools-text)"
                                      "var(--devtools-text-muted)")
                             :cursor "pointer"
                             :transition "all 0.2s"}
                     :on-click #(reset! active-tab (:value tab))
                     :on-mouse-enter #(when (not= @active-tab (:value tab))
                                        (set! (.. % -target -style -background) "var(--devtools-hover)"))
                     :on-mouse-leave #(set! (.. % -target -style -background) "transparent")}
            (:label tab)])]

        ;; Tab Content
        [:div {:style {:flex "1" :overflow "hidden"}}
         (case @active-tab
           "event" [event-panel]
           "app-db" [app-db-panel]
           [:div {:class "devtools-body" :style {:padding "21px" :color "var(--devtools-text-muted)"}}
            (str "Panel: " @active-tab " (not yet implemented)")])]]])))

;; ============================================================================
;; Application Mount
;; ============================================================================

(defn ^:export init []
  (rdom/render [main-app]
               (.getElementById js/document "app")))

;; Auto-initialize on load
(init)

;; For hot-reload during development
(defn ^:dev/after-load reload []
  (init))
