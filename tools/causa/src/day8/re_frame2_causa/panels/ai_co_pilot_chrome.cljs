(ns day8.re-frame2-causa.panels.ai-co-pilot-chrome
  "Title and collapsed-cue chrome for the AI Co-Pilot."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn cue-glyph
  "Collapsed `◇` cue. The caller reads whether the pulse is active; the
  actual animation is supplied by shell styling when present."
  [_cue-active?]
  [:button {:data-testid "rf-causa-copilot-cue"
            :on-click    #(rf/dispatch [:rf.causa/copilot-toggle] {:frame :rf/causa})
            :title       "Ask Causa (Ctrl+Shift+/)"
            :style       {:background  "transparent"
                          :border      "none"
                          :cursor      "pointer"
                          :padding     "8px"
                          :color       (:magenta tokens)
                          :font-size   "16px"
                          :line-height 1}}
   "◇"])

(defn title-bar
  "Rail title bar with provider-cycle and close affordances."
  []
  [:header {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :padding "8px 12px"
                    :background (:bg-1 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display "flex" :align-items "center" :gap "6px"
                  :font-family sans-stack :font-size "13px"
                  :font-weight 600 :color (:text-primary tokens)}}
    [:span {:style {:color (:magenta tokens)}} "◇"]
    [:span "AI Co-pilot"]]
   [:div {:style {:display "flex" :align-items "center" :gap "8px"
                  :color (:text-secondary tokens)
                  :font-family mono-stack :font-size "12px"}}
    [:button {:data-testid "rf-causa-copilot-provider-picker"
              :on-click    #(rf/dispatch [:rf.causa/copilot-cycle-provider] {:frame :rf/causa})
              :title       "Provider"
              :style       {:background "transparent"
                            :border "none"
                            :color (:text-secondary tokens)
                            :cursor "pointer"
                            :padding "2px 4px"}}
     "⌗"]
    [:button {:data-testid "rf-causa-copilot-close"
              :on-click    #(rf/dispatch [:rf.causa/copilot-toggle] {:frame :rf/causa})
              :title       "Close"
              :style       {:background "transparent"
                            :border "none"
                            :color (:text-secondary tokens)
                            :cursor "pointer"
                            :padding "2px 4px"}}
     "✕"]]])
