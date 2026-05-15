(ns day8.re-frame2-causa.panels.ai-co-pilot-conversation
  "Conversation rendering for the AI Co-Pilot."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- chip-view
  [{:keys [chip-key value glyph] :as _resolved}]
  [:button {:data-testid (str "rf-causa-copilot-chip-" (name chip-key))
            :on-click    #(rf/dispatch [:rf.causa/copilot-chip-clicked
                                        {:chip-key chip-key
                                         :value    value}] {:frame :rf/causa})
            :title       (str chip-key " " (pr-str value))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "4px"
                          :margin        "0 2px"
                          :padding       "1px 6px"
                          :background    (:bg-3 tokens)
                          :color         (:text-primary tokens)
                          :border        (str "1px solid " (:border-default tokens))
                          :border-radius "4px"
                          :cursor        "pointer"
                          :font-family   mono-stack
                          :font-size     "12px"}}
   [:span {:style {:color (:magenta tokens)}} glyph]
   [:span (pr-str value)]])

(defn- segment-view
  "Render parsed answer segments, falling back to literal text for
  malformed or unsupported chip fragments."
  [segment]
  (case (:kind segment)
    :text
    [:span (:text segment)]

    :chip
    (if-let [resolved (h/resolve-chip segment)]
      [chip-view resolved]
      [:span (:raw segment)])

    [:span (str segment)]))

(defn- turn-view
  [{:keys [role text streaming?] :as turn}]
  (case role
    :question
    [:div {:data-testid "rf-causa-copilot-turn-question"
           :style       {:padding "8px 12px"
                         :font-family sans-stack
                         :font-size "13px"
                         :color (:text-primary tokens)
                         :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (:accent-violet tokens) :margin-right "6px"}} "▸"]
     [:span text]]

    :answer
    [:div {:data-testid "rf-causa-copilot-turn-answer"
           :style       {:padding "8px 12px"
                         :font-family sans-stack
                         :font-size "13px"
                         :color (:text-secondary tokens)
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :line-height 1.5}}
     (into [:div]
           (map-indexed
             (fn [i segment] ^{:key i} [segment-view segment])
             (h/parse-streamed-answer text)))
     (when streaming?
       [:span {:data-testid "rf-causa-copilot-stream-cursor"
               :style       {:color (:accent-violet tokens)
                             :margin-left "4px"
                             :font-family mono-stack}} "▍"])]

    [:div (pr-str turn)]))

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-copilot-empty"
         :style       {:padding "16px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :color       (:text-secondary tokens)
                       :line-height 1.5}}
   [:p {:style {:margin "0 0 12px 0" :color (:text-primary tokens)}}
    "Ask Causa anything about this runtime."]
   [:p {:style {:margin "0 0 4px 0" :color (:text-tertiary tokens) :font-size "12px"}}
    "Try:"]
   [:ul {:style {:margin "0 0 12px 16px"
                 :padding 0
                 :font-family mono-stack
                 :font-size "12px"
                 :color (:text-secondary tokens)}}
    [:li "Why did :checkout/submit fire?"]
    [:li "What changed in the last 10 epochs?"]
    [:li "Why is :cart/total returning 0?"]
    [:li "/state :auth/login-flow"]]
   [:p {:style {:margin 0
                :color (:text-tertiary tokens)
                :font-size "12px"
                :font-style "italic"}}
    "The co-pilot reads the same data you see — it cites every claim "
    "with a source coord or epoch id. Verify before trusting."]])

(defn conversation-view
  "Render conversation turns newest-at-bottom, or the empty-state when
  the in-memory buffer is empty."
  [conversation]
  (if (empty? conversation)
    [empty-state]
    (into [:div {:data-testid "rf-causa-copilot-conversation"
                 :style       {:display "flex"
                               :flex-direction "column"}}]
          (map-indexed
            (fn [i turn] ^{:key i} [turn-view turn])
            conversation))))
