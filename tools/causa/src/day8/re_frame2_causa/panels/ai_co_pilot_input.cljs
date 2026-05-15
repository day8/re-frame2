(ns day8.re-frame2-causa.panels.ai-co-pilot-input
  "Input row and slash-command popover for the AI Co-Pilot."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- slash-popover
  [input]
  (let [matches (h/slash-popover-matches input)]
    (when (seq matches)
      [:div {:data-testid "rf-causa-copilot-slash-popover"
             :style       {:position "absolute"
                           :bottom "100%"
                           :left 0
                           :right 0
                           :margin-bottom "4px"
                           :background (:bg-3 tokens)
                           :border (str "1px solid " (:border-default tokens))
                           :border-radius "4px"
                           :max-height "180px"
                           :overflow-y "auto"
                           :font-family mono-stack
                           :font-size "12px"
                           :z-index 10}}
       (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
             (for [{:keys [command usage doc]} matches]
               ^{:key command}
               [:li {:data-testid (str "rf-causa-copilot-slash-" (name command))
                     :on-click    #(rf/dispatch [:rf.causa/copilot-set-input-text
                                                usage]
                                               {:frame :rf/causa})
                     :style       {:padding "6px 10px"
                                   :cursor "pointer"
                                   :color (:text-primary tokens)
                                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
                [:div {:style {:color (:accent-violet tokens)}} usage]
                [:div {:style {:color (:text-tertiary tokens)
                               :font-family sans-stack
                               :font-size "11px"
                               :margin-top "2px"}}
                 doc]]))])))

(rf/reg-view input-row
  "Question input. `/clear` is handled locally; all other non-empty
  submissions dispatch the pull-only LLM submit event."
  []
  (let [input  (or @(rf/subscribe [:rf.causa/copilot-input-text]) "")
        parsed (h/parse-slash-command input)
        submit (fn []
                 (when (seq (str/trim input))
                   (rf/dispatch [:rf.causa/copilot-set-input-text ""] {:frame :rf/causa})
                   (if (= :clear (:command parsed))
                     (rf/dispatch [:rf.causa/copilot-clear-conversation] {:frame :rf/causa})
                     (rf/dispatch
                       [:rf.causa/copilot-submit-question
                        {:text input :parsed parsed}] {:frame :rf/causa}))))]
    [:div {:style {:position "relative"
                   :padding "8px 12px"
                   :border-top (str "1px solid " (:border-subtle tokens))
                   :background (:bg-1 tokens)}}
     (slash-popover input)
     [:div {:style {:display "flex" :gap "6px" :align-items "stretch"}}
      [:input {:data-testid "rf-causa-copilot-input"
               :type        "text"
               :value       input
               :placeholder "Ask anything…"
               :on-change   #(rf/dispatch [:rf.causa/copilot-set-input-text
                                          (.. % -target -value)]
                                         {:frame :rf/causa})
               :on-key-down (fn [e]
                              (when (= "Enter" (.-key e))
                                (.preventDefault e)
                                (submit)))
               :style       {:flex 1
                             :background (:bg-3 tokens)
                             :color (:text-primary tokens)
                             :border (str "1px solid " (:border-default tokens))
                             :border-radius "4px"
                             :padding "6px 10px"
                             :font-family sans-stack
                             :font-size "12px"}}]
      [:button {:data-testid "rf-causa-copilot-submit"
                :on-click    submit
                :title       "Submit (Enter)"
                :style       {:background (:accent-violet tokens)
                              :color "#fff"
                              :border "none"
                              :padding "0 12px"
                              :border-radius "4px"
                              :cursor "pointer"
                              :font-family sans-stack
                              :font-size "12px"
                              :font-weight 600}}
       "↑"]]
     [:div {:style {:margin-top "4px"
                    :font-family sans-stack
                    :font-size "10px"
                    :color (:text-tertiary tokens)}}
      "/slash for commands"]]))
