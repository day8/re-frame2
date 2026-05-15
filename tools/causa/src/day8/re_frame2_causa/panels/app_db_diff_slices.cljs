(ns day8.re-frame2-causa.panels.app-db-diff-slices
  "Changed-slice rows for the App-DB Diff panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn empty-state
  [target-frame]
  [:div {:data-testid "rf-causa-app-db-diff-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    [:code {:style {:color (:accent-violet tokens)
                    :font-family mono-stack}}
     "app-db"]
    " for "
    [:code {:style {:color (:accent-violet tokens)
                    :font-family mono-stack}}
     (str target-frame)]
    " is at the boot value."]
   [:p {:style {:margin 0}}
    "No diffs yet — every dispatch will land here with the slices it touched."]])

(defn- value-block
  [label value tone]
  [:div {:style {:display     "flex"
                 :align-items "flex-start"
                 :gap         "8px"
                 :padding     "2px 0"}}
   [:div {:style {:flex          "0 0 64px"
                  :color         (tone tokens)
                  :font-family   sans-stack
                  :font-size     "10px"
                  :font-weight   600
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :padding-top   "2px"}}
    label]
   [:div {:style {:flex          1
                  :min-width     0
                  :font-family   mono-stack
                  :font-size     "12px"
                  :color         (:text-primary tokens)
                  :word-break    "break-word"
                  :white-space   "pre-wrap"}}
    (f/format-display-edn value)]])

(defn slice-row
  [{:keys [op path before after] :as _triple}]
  (let [tone (f/op->border op)]
    [:div {:data-testid (str "rf-causa-app-db-diff-slice-"
                             (pr-str path))
           :style       {:display       "flex"
                         :flex-direction "column"
                         :padding       "8px 12px 8px 10px"
                         :margin        "8px 12px"
                         :background    (:bg-3 tokens)
                         :border-left   (str "3px solid " (tone tokens))
                         :border-top    (str "1px solid " (:border-subtle tokens))
                         :border-right  (str "1px solid " (:border-subtle tokens))
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :border-radius "4px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :gap "8px"
                    :margin-bottom "4px"}}
      [:span {:style {:font-family mono-stack
                      :font-size   "12px"
                      :color       (:text-primary tokens)
                      :font-weight 600}}
       (f/truncate (f/format-edn path) 56)]
      [:span {:style {:font-family sans-stack
                      :font-size   "10px"
                      :color       (tone tokens)
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
       (f/op->label op)]]
     [:div {:style {:display "flex"
                    :gap "6px"
                    :margin-bottom "6px"}}
      [:button {:data-testid (str "rf-causa-app-db-diff-pin-" (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/pin-slice path]
                                           {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:cyan tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Pin"]
      [:button {:data-testid (str "rf-causa-app-db-diff-show-when-"
                                  (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/focus-slice-path path]
                                           {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:magenta tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Show me when this changed"]
      [:button {:data-testid (str "rf-causa-app-db-diff-copy-path-"
                                  (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/copy-path-to-clipboard path]
                                           {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:text-secondary tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Copy path"]
      [:button {:data-testid (str "rf-causa-app-db-diff-copy-value-"
                                  (pr-str path))
                :on-click    #(rf/dispatch
                                [:rf.causa/copy-value-to-clipboard
                                 (case op :removed before after)]
                                {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:text-secondary tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Copy value"]]
     (case op
       :added    (value-block "added" after :green)
       :removed  [:div {:style {:text-decoration "line-through"}}
                  (value-block "removed" before :red)]
       :modified [:div
                  (value-block "before" before :text-tertiary)
                  (value-block "after"  after  :yellow)])]))

(defn changed-slices-stack
  [non-reserved-triples]
  (if (empty? non-reserved-triples)
    [:p {:data-testid "rf-causa-app-db-diff-no-changes"
         :style {:padding "12px"
                 :color   (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "12px"
                 :margin 0}}
     "No slice changes in the selected epoch."]
    (into [:div {:data-testid "rf-causa-app-db-diff-slices"}]
          (for [t non-reserved-triples]
            ^{:key (pr-str (:path t))}
            (slice-row t)))))
