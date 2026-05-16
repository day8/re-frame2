(ns day8.re-frame2-causa.panels.time-travel
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.time-travel-events :as events]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]
            [day8.re-frame2-causa.panels.time-travel-subs :as subs]
            [day8.re-frame2-causa.theme.tokens :refer [tokens mono-stack sans-stack]]))

(defn- empty-state
  [target-frame]
  [:div {:data-testid "rf-causa-time-travel-empty"
         :style       {:padding "16px" :color (:text-tertiary tokens)
                       :font-family sans-stack :font-size "13px"}}
   "No epoch history for "
   [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
    (str target-frame)]
   " yet. Trigger a dispatch in the host app and the scrubber will populate."])

(defn- chip-view
  [{:keys [pin attached] :as _chip-state}]
  (let [{:keys [epoch-id label]} pin]
    [:span {:data-testid (str "rf-causa-pin-chip-" (str epoch-id))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "2px 8px"
                          :margin-right  "6px"
                          :background    (:bg-3 tokens)
                          :color         (if attached (:text-primary tokens) (:text-tertiary tokens))
                          :border        (str "1px solid "
                                              (if attached (:border-default tokens) (:border-subtle tokens)))
                          :border-style  (if attached "solid" "dashed")
                          :border-radius "10px"
                          :font-family   sans-stack
                          :font-size     "11px"}}
     [:span {:style    {:color    (if attached (:accent-violet tokens) (:text-tertiary tokens))
                        :cursor   "pointer"}
             :on-click #(rf/dispatch [:rf.causa/select-epoch epoch-id] {:frame :rf/causa})}
      (str (if attached "●" "○") " " label)]
     [:button {:data-testid (str "rf-causa-pin-reset-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-pinned epoch-id] {:frame :rf/causa})
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:cyan tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Reset frame-db to this pin"}
      "↺"]
     [:button {:data-testid (str "rf-causa-pin-remove-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/unpin epoch-id] {:frame :rf/causa})
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:text-tertiary tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Remove pin"}
      "✕"]]))

(defn- chip-row
  [chip-states]
  (when (seq chip-states)
    (into [:div {:data-testid "rf-causa-time-travel-chips"
                 :style       {:padding "8px 12px"
                               :display "flex"
                               :flex-wrap "wrap"
                               :gap "4px"
                               :border-bottom (str "1px solid " (:border-subtle tokens))}}]
          (for [cs chip-states]
            ^{:key (str (:epoch-id (:pin cs)))}
            (chip-view cs)))))

(defn- track-row
  [{:keys [history selected-epoch-id selected-index] :as _data}]
  (let [n        (count history)
        max-idx  (max 0 (dec n))
        cur-idx  (or selected-index max-idx)
        on-step  (fn [delta]
                   (when-let [new-id (h/step-epoch history selected-epoch-id delta)]
                     (rf/dispatch [:rf.causa/select-epoch new-id] {:frame :rf/causa})))]
    [:div {:data-testid "rf-causa-time-travel-track"
           :style       {:padding "12px"
                         :display "flex"
                         :align-items "center"
                         :gap "10px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:button {:data-testid "rf-causa-time-travel-jump-oldest"
               :on-click    #(when (seq history)
                               (rf/dispatch [:rf.causa/select-epoch
                                             (:epoch-id (first history))]
                                            {:frame :rf/causa}))
               :title       "Jump to oldest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "◀◀"]
     [:input {:data-testid "rf-causa-time-travel-slider"
              :type        "range"
              :min         0
              :max         max-idx
              :value       cur-idx
              :style       {:flex 1}
              :on-change   (fn [e]
                             (let [idx (js/parseInt (.. e -target -value))]
                               (when-let [eid (h/epoch-id-at-index history idx)]
                                 (rf/dispatch [:rf.causa/select-epoch eid] {:frame :rf/causa}))))
              :on-key-down (fn [e]
                             (case (.-key e)
                               "[" (do (.preventDefault e) (on-step -1))
                               "]" (do (.preventDefault e) (on-step  1))
                               "ArrowLeft"  (do (.preventDefault e) (on-step -1))
                               "ArrowRight" (do (.preventDefault e) (on-step  1))
                               nil))}]
     [:button {:data-testid "rf-causa-time-travel-jump-newest"
               :on-click    #(when (seq history)
                               (rf/dispatch [:rf.causa/select-epoch
                                             (:epoch-id (peek history))]
                                            {:frame :rf/causa}))
               :title       "Jump to newest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "▶▶"]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "11px"
                     :min-width "76px"
                     :text-align "right"}}
      (str (inc cur-idx) "/" n " epochs")]]))

(defn- actions-row
  [{:keys [selected-epoch-id history cap-reached? label-input] :as _data}]
  (let [target-eid (or selected-epoch-id (h/newest-epoch-id history))
        history-empty? (empty? history)
        label       (or label-input "")]
    [:div {:data-testid "rf-causa-time-travel-actions"
           :style       {:padding "8px 12px"
                         :display "flex"
                         :align-items "center"
                         :gap "8px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :flex-wrap "wrap"}}
     [:input {:data-testid "rf-causa-pin-label-input"
              :type        "text"
              :placeholder "pin label (optional)"
              :value       label
              :on-change   #(rf/dispatch [:rf.causa/time-travel-set-label-input
                                          (.. % -target -value)]
                                         {:frame :rf/causa})
              :style       {:flex "1 1 160px"
                            :min-width "120px"
                            :background  (:bg-3 tokens)
                            :color       (:text-primary tokens)
                            :border      (str "1px solid " (:border-default tokens))
                            :border-radius "4px"
                            :padding     "4px 8px"
                            :font-family sans-stack
                            :font-size   "12px"}}]
     [:button {:data-testid "rf-causa-pin-current"
               :disabled    (or history-empty? cap-reached?)
               :on-click    #(when target-eid
                               (rf/dispatch [:rf.causa/pin-current target-eid label]
                                            {:frame :rf/causa})
                               (rf/dispatch [:rf.causa/time-travel-set-label-input ""]
                                            {:frame :rf/causa}))
               :style       {:background  (if cap-reached?
                                            (:bg-3 tokens)
                                            (:accent-violet tokens))
                             :color       (if cap-reached?
                                            (:text-tertiary tokens)
                                            "#fff")
                             :border      "none"
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      (if cap-reached? "not-allowed" "pointer")
                             :font-family sans-stack
                             :font-size   "12px"
                             :font-weight 600}}
      "Pin"]
     [:button {:data-testid "rf-causa-reset-to-epoch"
               :disabled    (or history-empty? (nil? target-eid))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-epoch target-eid] {:frame :rf/causa})
               :style       {:background  "transparent"
                             :color       (:text-secondary tokens)
                             :border      (str "1px solid " (:border-default tokens))
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family sans-stack
                             :font-size   "12px"}}
      "Reset to current"]
     (when cap-reached?
       [:span {:data-testid "rf-causa-pin-cap-warning"
               :style       {:color (:yellow tokens)
                             :font-family sans-stack
                             :font-size "11px"}}
        (str "Pin cap reached (" h/default-pin-cap "). Remove a pin to capture more.")])]))

(rf/reg-view time-travel-view
  []
  (let [{:keys [target-frame history] :as data}
        @(rf/subscribe [:rf.causa/time-travel])]
    [:section {:data-testid "rf-causa-time-travel"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Time Travel"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Scrub epoch history for "
       [:code {:data-testid "rf-causa-time-travel-target-frame"
               :style       {:color (:accent-violet tokens) :font-family mono-stack}}
        (str target-frame)]
       ". Rewind with "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to current"]
       " or "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to pinned"]
       "."]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (empty? history)
        (empty-state target-frame)
        [:div
         (track-row data)
         (actions-row data)
         (chip-row (:chip-states data))])]]))

(defn install!
  []
  (subs/install!)
  (events/install!)
  nil)
