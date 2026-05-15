(ns day8.re-frame2-causa.panels.subscriptions-rows
  "Subscription list rows."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.panels.subscriptions-badges
             :as badges]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- sub-row
  [{:keys [query-v sub-id status layer ref-count recomputed?] :as _row}
   selected?]
  [:li {:data-testid (str "rf-causa-sub-row-"
                          (h/format-sub-id sub-id))
        :on-click    #(rf/dispatch [:rf.causa/select-sub query-v]
                                   {:frame :rf/causa})
        :style       {:display "flex"
                      :align-items "center"
                      :padding "6px 12px"
                      :background (if selected?
                                    (:bg-active tokens)
                                    "transparent")
                      :border-bottom (str "1px solid "
                                          (:border-subtle tokens))
                      :cursor "pointer"
                      :font-family mono-stack
                      :font-size "13px"
                      :color (:text-primary tokens)}}
   (badges/status-badge status)
   (badges/layer-pill layer)
   [:span {:style {:flex 1
                   :min-width 0
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (h/format-query-v query-v)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :margin-left "8px"}}
    (str "refs " ref-count)]
   (when recomputed?
     [:span {:data-testid "rf-causa-sub-row-recomputed"
             :style {:color (:cyan tokens)
                     :font-size "10px"
                     :margin-left "8px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
      "ran"])
   [:button {:data-testid (str "rf-causa-sub-row-chain-"
                               (h/format-sub-id sub-id))
             :on-click (fn [e]
                         (.stopPropagation e)
                         (rf/dispatch [:rf.causa/select-sub query-v]
                                      {:frame :rf/causa})
                         (rf/dispatch
                           [:rf.causa/show-invalidation-chain query-v]
                           {:frame :rf/causa}))
             :title    "Show invalidation chain"
             :style    {:margin-left "8px"
                        :background "transparent"
                        :border (str "1px solid "
                                     (:border-default tokens))
                        :color (:text-secondary tokens)
                        :padding "1px 6px"
                        :border-radius "3px"
                        :cursor "pointer"
                        :font-family sans-stack
                        :font-size "10px"}}
    "chain"]])

(defn sub-list
  [rows selected-query-v]
  (if (empty? rows)
    [:div {:data-testid "rf-causa-subscriptions-empty-rows"
           :style       {:padding "16px"
                         :color (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size "13px"}}
     "No subscriptions match the current filter."]
    (overflow/capped-list
      rows
      {:panel-id "subscriptions"
       :ul-attrs {:data-testid "rf-causa-subscriptions-list"
                  :style {:list-style "none"
                          :margin 0
                          :padding 0
                          :background (:bg-2 tokens)}}
       :row-fn   (fn [row]
                   ^{:key (h/format-query-v (:query-v row))}
                   (sub-row row (= (:query-v row) selected-query-v)))})))
