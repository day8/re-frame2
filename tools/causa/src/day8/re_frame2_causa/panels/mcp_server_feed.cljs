(ns day8.re-frame2-causa.panels.mcp-server-feed
  "Scrollable activity feed for the MCP Server panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]
            [day8.re-frame2-causa.panels.mcp-server-style :as style]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]))

(def ^:private tokens style/tokens)
(def ^:private sans-stack style/sans-stack)
(def ^:private mono-stack style/mono-stack)

(defn mcp-row
  "One row in the agent-activity feed. Click the row → pivot to the
  cascade in the event-detail panel. Click the source-coord chip →
  open in editor (via the open-in-editor module's stub event)."
  [{:keys [id time op-type operation tool description
           source-coord dispatch-id]
    :as _row}]
  (let [row-test-id (str "rf-causa-mcp-row-" id)]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         (when dispatch-id
                           (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id]
                                        {:frame :rf/causa})
                           (rf/dispatch [:rf.causa/select-panel :event-detail]
                                        {:frame :rf/causa})))
          :style       {:display       "grid"
                        :grid-template-columns "84px 90px minmax(120px, 1fr) auto 2fr auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        (if dispatch-id "pointer" "default")
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     [:span {:data-testid (str row-test-id "-time")
             :style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     [:span {:data-testid (str row-test-id "-op-type")
             :style       {:color       (:causa-mcp-cyan tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"
                           :font-weight 500}}
      (if op-type (name op-type) "—")]
     [:span {:data-testid (str row-test-id "-operation")
             :style       {:color       (:accent-violet tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}}
      (if operation (str operation) "—")]
     (if tool
       [:span {:data-testid (str row-test-id "-tool")
               :title       "causa-mcp tool"
               :style       {:color       (:causa-mcp-cyan tokens)
                             :font-size   "10px"
                             :background  (:bg-3 tokens)
                             :border      (str "1px solid " (:border-subtle tokens))
                             :border-radius "3px"
                             :padding     "1px 6px"
                             :white-space "nowrap"}}
        (str tool)]
       [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}} "—"])
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color (:text-secondary tokens)
                           :overflow "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}
             :title       description}
      description]
     (if source-coord
       [:button {:data-testid (str row-test-id "-source")
                 :on-click    (fn [e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}]
                                             {:frame :rf/causa}))
                 :style       {:background  "transparent"
                               :color       (:cyan tokens)
                               :border      (str "1px solid "
                                                 (:border-subtle tokens))
                               :padding     "1px 6px"
                               :border-radius "3px"
                               :cursor      "pointer"
                               :font-family mono-stack
                               :font-size   "10px"}}
        source-coord]
       [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}}
        "—"])]))

(defn empty-state-no-activity
  "Rendered when no causa-mcp-tagged events have landed in the buffer
  this session."
  []
  [:div {:data-testid "rf-causa-mcp-empty-no-activity"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "10px"
                  :margin-bottom "8px"}}
    [:span {:style {:color       (:causa-mcp-cyan tokens)
                    :font-size   "16px"
                    :font-weight 700}}
     "⌬"]
    [:span {:style {:color       (:text-primary tokens)
                    :font-weight 600}}
     "No agent activity"]]
   [:p {:style {:margin "0 0 8px 0" :color (:text-tertiary tokens)}}
    "Causa-MCP (the agent server) tags every operation it performs "
    "with " [:code {:style {:font-family mono-stack
                            :color (:causa-mcp-cyan tokens)}}
             ":origin :causa-mcp"]
    ". Nothing tagged that way has landed in the buffer this session."]
   [:p {:style {:margin 0 :color (:text-tertiary tokens)
                :font-size "12px"
                :font-style "italic"}}
    "Once an agent connects via the causa-mcp jar and performs an op, "
    "this feed lights up."]])

(defn empty-state-no-matches
  "Events exist but the active filters hide them all."
  []
  [:div {:data-testid "rf-causa-mcp-empty-no-matches"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-primary tokens)
                :font-weight 600}}
    "No agent events match the active filters."]
   [:p {:style {:margin "0 0 12px 0" :color (:text-tertiary tokens)}}
    "Adjust the op-type / since-ms chips above to widen the feed."]
   [:button {:data-testid "rf-causa-mcp-empty-clear-filters"
             :on-click    #(rf/dispatch [:rf.causa/clear-mcp-filters]
                                        {:frame :rf/causa})
             :style       {:background "transparent"
                           :color      (:cyan tokens)
                           :border     (str "1px solid " (:border-default tokens))
                           :padding    "4px 10px"
                           :border-radius "3px"
                           :cursor     "pointer"
                           :font-family sans-stack
                           :font-size  "12px"}}
    "Clear filters"]])

(defn activity-feed
  "Render either the empty state or the capped row list for the feed
  body."
  [{:keys [rows empty-kind]}]
  [:div {:style {:flex 1 :overflow "auto"}}
   (case empty-kind
     :no-activity (empty-state-no-activity)
     :no-matches  (empty-state-no-matches)
     nil          (overflow/capped-list
                    rows
                    {:panel-id "mcp"
                     :ul-attrs {:data-testid "rf-causa-mcp-feed"
                                :style       {:list-style "none"
                                              :margin     0
                                              :padding    0}}
                     :row-fn   mcp-row}))])
