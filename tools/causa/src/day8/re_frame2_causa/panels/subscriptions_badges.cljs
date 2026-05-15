(ns day8.re-frame2-causa.panels.subscriptions-badges
  "Badges, pills, and filter chips for the Subscriptions panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn status-colour
  [status]
  (let [tok (get h/status->token status :text-tertiary)]
    (get tokens tok (:text-tertiary tokens))))

(defn status-badge
  [status]
  (let [glyph   (get h/status->glyph status "?")
        colour  (status-colour status)
        tooltip (get h/status->tooltip status "Unknown")]
    [:span {:data-testid  (str "rf-causa-sub-badge-" (name status))
            :title        tooltip
            :aria-label   tooltip
            :style        {:display      "inline-block"
                           :width        "14px"
                           :height       "14px"
                           :line-height  "14px"
                           :text-align   "center"
                           :color        colour
                           :font-family  mono-stack
                           :font-size    "14px"
                           :font-weight  700
                           :margin-right "8px"
                           :flex-shrink  0}}
     glyph]))

(defn layer-pill
  [layer]
  (let [label (cond
                (nil? layer) "?"
                (<= layer 1) "L1"
                (= layer 2)  "L2"
                :else        "L3+")]
    [:span {:style {:display       "inline-block"
                    :padding       "1px 6px"
                    :margin-right  "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"}}
     label]))

(defn- filter-chip
  [status active? count]
  (let [colour  (status-colour status)
        glyph   (get h/status->glyph status)
        tooltip (get h/status->tooltip status)]
    [:button {:data-testid (str "rf-causa-sub-filter-" (name status))
              :on-click    #(rf/dispatch
                               [:rf.causa/toggle-sub-filter status]
                               {:frame :rf/causa})
              :title       tooltip
              :style       {:display       "inline-flex"
                            :align-items   "center"
                            :gap           "4px"
                            :padding       "2px 8px"
                            :margin-right  "6px"
                            :border-radius "10px"
                            :background    (if active?
                                             "rgba(124, 92, 255, 0.18)"
                                             "transparent")
                            :border        (str "1px solid "
                                                (if active?
                                                  colour
                                                  (:border-default tokens)))
                            :color         (if active?
                                             (:text-primary tokens)
                                             (:text-secondary tokens))
                            :cursor        "pointer"
                            :font-family   sans-stack
                            :font-size     "11px"
                            :font-weight   (if active? 600 400)}}
     [:span {:style {:color colour
                     :font-family mono-stack
                     :font-weight 700}}
      glyph]
     [:span tooltip]
     (when count
       [:span {:style {:color (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "10px"}}
        (str "(" count ")")])]))

(defn filter-header
  [active-filters status-counts total]
  [:div {:data-testid "rf-causa-subscriptions-filters"
         :style       {:padding "8px 12px"
                       :display "flex"
                       :align-items "center"
                       :flex-wrap "wrap"
                       :gap "4px"
                       :border-bottom (str "1px solid "
                                           (:border-subtle tokens))}}
   (into [:div {:style {:flex 1 :display "flex" :flex-wrap "wrap"}}]
         (for [s h/statuses]
           ^{:key s}
           (filter-chip s (contains? active-filters s)
                        (get status-counts s 0))))
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"
                   :margin-left "8px"}}
    (str total " sub" (if (= 1 total) "" "s"))]])
