(ns day8.re-frame2-causa.panels.subscriptions-views
  "Root view composition for the Subscriptions panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-badges
             :as badges]
            [day8.re-frame2-causa.panels.subscriptions-chain
             :as chain]
            [day8.re-frame2-causa.panels.subscriptions-rows :as rows]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack]]))

(defn- empty-state []
  [:div {:data-testid "rf-causa-subscriptions-empty"
         :style       {:padding "16px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No subscriptions yet."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Subscribe to a sub in the host app — the cache populates as views render."]])

(defn subscriptions-panel []
  (let [{:keys [rows filtered-rows status-counts selected-query-v
                active-filters chain-open? chain]}
        @(rf/subscribe [:rf.causa/subscriptions-data])
        total (count rows)]
    [:section {:data-testid "rf-causa-subscriptions"
               :style       {:height "100%"
                             :display "flex"
                             :flex-direction "column"
                             :background (:bg-2 tokens)
                             :color (:text-primary tokens)
                             :font-family sans-stack
                             :font-size "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size "16px"
                    :font-weight 600
                    :margin 0
                    :color (:text-primary tokens)}}
       "Subscriptions"]
      [:p {:style {:font-size "12px"
                   :color (:text-tertiary tokens)
                   :margin "4px 0 0 0"}}
       "Cache state for every materialised sub in the target frame. "
       "Hover a badge for the status tooltip; click "
       [:em "chain"] " for the invalidation walk."]]
     (badges/filter-header (or active-filters #{}) status-counts total)
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (zero? total)
        (empty-state)
        (rows/sub-list filtered-rows selected-query-v))]
     (when chain-open?
       (chain/chain-view chain))]))
