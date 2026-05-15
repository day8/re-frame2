(ns day8.re-frame2-causa.panels.subscriptions-chain
  "Invalidation-chain view for the Subscriptions panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-badges
             :as badges]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- chain-link
  [{:keys [query-v status layer link-reason]}]
  [:li {:data-testid (str "rf-causa-chain-link-"
                          (h/format-sub-id (first query-v)))
        :style       {:padding "6px 12px"
                      :display "flex"
                      :align-items "center"
                      :border-bottom (str "1px solid "
                                          (:border-subtle tokens))
                      :font-family mono-stack
                      :font-size "12px"
                      :color (:text-primary tokens)}}
   (badges/status-badge status)
   (badges/layer-pill layer)
   [:span {:style {:flex 1}} (h/format-query-v query-v)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "10px"
                   :font-family sans-stack
                   :text-transform "uppercase"
                   :letter-spacing "0.5px"
                   :margin-left "8px"}}
    (case link-reason
      :slice-changed "slice changed"
      :input-changed "input changed"
      "")]])

(defn- chain-header []
  [:header {:style {:padding "8px 12px"
                    :display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :background (:bg-3 tokens)
                    :border-bottom (str "1px solid "
                                        (:border-subtle tokens))}}
   [:span {:style {:font-family sans-stack
                   :font-size "12px"
                   :font-weight 600
                   :color (:text-primary tokens)}}
    "Invalidation chain"]
   [:button {:data-testid "rf-causa-subscriptions-chain-close"
             :on-click    #(rf/dispatch
                              [:rf.causa/hide-invalidation-chain]
                              {:frame :rf/causa})
             :style       {:background "transparent"
                           :border (str "1px solid "
                                        (:border-default tokens))
                           :color (:text-secondary tokens)
                           :padding "1px 8px"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-family sans-stack
                           :font-size "11px"}}
    "Close"]])

(defn- app-db-paths-view [app-db-paths]
  (when (seq app-db-paths)
    [:div {:data-testid "rf-causa-subscriptions-chain-app-db"
           :style {:padding "8px 12px"
                   :border-top (str "1px solid "
                                    (:border-subtle tokens))
                   :font-family mono-stack
                   :font-size "12px"
                   :color (:text-primary tokens)}}
     [:div {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :margin-bottom "4px"}}
      "Originating slice(s)"]
     (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
           (for [path app-db-paths]
             ^{:key (pr-str path)}
             [:li {:style {:padding "2px 0"
                           :color (:accent-violet tokens)}}
              (pr-str path)]))]))

(defn chain-view
  [{:keys [focused inputs app-db-paths missing?] :as _chain}]
  [:section {:data-testid "rf-causa-subscriptions-chain"
             :style       {:border-top (str "1px solid "
                                            (:border-default tokens))
                           :background (:bg-1 tokens)}}
   (chain-header)
   (cond
     missing?
     [:div {:data-testid "rf-causa-subscriptions-chain-missing"
            :style       {:padding "12px"
                          :color (:text-tertiary tokens)
                          :font-family sans-stack
                          :font-size "12px"}}
      "Sub is not in the cache — nothing to chain. The cache may have "
      "evicted the entry, or the sub has never been subscribed."]

     :else
     [:div
      [:ul {:data-testid "rf-causa-subscriptions-chain-focused"
            :style {:list-style "none" :margin 0 :padding 0}}
       (chain-link {:query-v (:query-v focused)
                    :status (:status focused)
                    :layer (:layer focused)
                    :link-reason (if (or (nil? (:layer focused))
                                         (<= (:layer focused) 1))
                                   :slice-changed
                                   :input-changed)})]
      (if (empty? inputs)
        [:div {:data-testid "rf-causa-subscriptions-chain-no-inputs"
               :style {:padding "8px 12px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "11px"}}
         "No inputs re-ran this cascade. The sub may be layer-1 — "
         "see the originating slice(s) below."]
        (into [:ul {:data-testid "rf-causa-subscriptions-chain-inputs"
                    :style {:list-style "none"
                            :margin 0
                            :padding 0
                            :border-top (str "1px dashed "
                                             (:border-default tokens))}}]
              (for [in inputs]
                ^{:key (h/format-query-v (:query-v in))}
                (chain-link in))))
      (app-db-paths-view app-db-paths)])])
