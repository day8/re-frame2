(ns day8.re-frame2-causa.panels.mcp-server-chrome
  "Header, filters, and inline settings chrome for the MCP Server panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]
            [day8.re-frame2-causa.panels.mcp-server-style :as style]))

(def ^:private tokens style/tokens)
(def ^:private sans-stack style/sans-stack)
(def ^:private mono-stack style/mono-stack)

(defn- chip
  "One toggleable filter chip. `active?` drives the highlighted
  styling. Mirrors `issues_ribbon.cljs`'s chip helper so the visual
  rhythm matches across the two feeds."
  [{:keys [label active? on-click test-id colour]}]
  [:button {:data-testid test-id
            :on-click    on-click
            :style       {:background    (if active?
                                           (:bg-active tokens)
                                           "transparent")
                          :color         (if active?
                                           (:text-primary tokens)
                                           (or colour (:text-secondary tokens)))
                          :border        (str "1px solid "
                                              (if active?
                                                (or colour (:border-default tokens))
                                                (:border-subtle tokens)))
                          :border-radius "999px"
                          :padding       "2px 10px"
                          :cursor        "pointer"
                          :font-family   sans-stack
                          :font-size     "11px"
                          :font-weight   (if active? 600 400)
                          :letter-spacing "0.2px"
                          :margin-right  "6px"
                          :margin-bottom "4px"}}
   label])

(defn- op-type-chips
  "Filter chip row over the distinct op-types present in the feed.
  Each chip toggles membership in the active op-type filter set."
  [active-op-types distinct-op-types op-type-counts]
  (when (seq distinct-op-types)
    (into [:div {:data-testid "rf-causa-mcp-op-type-chips"
                 :style       {:display "flex" :flex-wrap "wrap"}}]
          (for [op-type distinct-op-types
                :let [active? (contains? active-op-types op-type)
                      n       (get op-type-counts op-type 0)]]
            (chip {:label    (str (name op-type) " · " n)
                   :active?  active?
                   :colour   (:causa-mcp-cyan tokens)
                   :test-id  (str "rf-causa-mcp-op-type-chip-" (name op-type))
                   :on-click #(rf/dispatch [:rf.causa/toggle-mcp-op-type
                                            op-type] {:frame :rf/causa})})))))

(defn- since-input
  "The `since-ms` filter — a numeric input rendered as a chip-row
  sibling. Values are in seconds for ergonomic typing; the helper
  converts to ms before dispatching. Mirrors the Issues ribbon's
  identical surface."
  [since-ms]
  [:div {:data-testid "rf-causa-mcp-since-input"
         :style {:display "flex"
                 :align-items "center"
                 :gap "6px"
                 :margin-top "4px"
                 :font-family sans-stack
                 :font-size "11px"
                 :color (:text-secondary tokens)}}
   [:span "since"]
   [:input {:type      "number"
            :min       "0"
            :step      "10"
            :value     (str (when (number? since-ms) (long (/ since-ms 1000))))
            :on-change (fn [e]
                         (let [v (.. e -target -value)
                               n (try
                                   (let [parsed (js/parseInt v 10)]
                                     (when-not (js/isNaN parsed) parsed))
                                   (catch :default _ nil))]
                           (rf/dispatch [:rf.causa/set-mcp-since-seconds n]
                                        {:frame :rf/causa})))
            :style     {:width "60px"
                        :background (:bg-3 tokens)
                        :color (:text-primary tokens)
                        :border (str "1px solid " (:border-subtle tokens))
                        :border-radius "3px"
                        :padding "2px 4px"
                        :font-family mono-stack
                        :font-size "11px"}}]
   [:span "s ago"]])

(defn settings-sub-pane
  "Inline Settings strip — origin colour swatch + origin-filter
  enable/disable toggle. Per the bead's contract these settings
  surface here (not in a separate Settings modal section) until the
  broader Settings work lands."
  [{:keys [origin-filter-enabled?]}]
  [:section {:data-testid "rf-causa-mcp-settings"
             :style       {:padding "8px 16px"
                           :border-bottom (str "1px solid " (:border-subtle tokens))
                           :background (:bg-1 tokens)
                           :font-family sans-stack
                           :font-size "11px"
                           :color (:text-secondary tokens)
                           :display "flex"
                           :align-items "center"
                           :gap "16px"
                           :flex-wrap "wrap"}}
   [:div {:data-testid "rf-causa-mcp-origin-swatch"
          :style {:display "flex" :align-items "center" :gap "6px"}}
    [:span {:style {:display "inline-block"
                    :width "10px"
                    :height "10px"
                    :border-radius "50%"
                    :background (:causa-mcp-cyan tokens)
                    :border (str "1px solid " (:border-default tokens))}}]
    [:span {:style {:color (:text-tertiary tokens)}}
     "origin :causa-mcp →"]
    [:code {:style {:font-family mono-stack
                    :font-size "10px"
                    :color (:causa-mcp-cyan tokens)}}
     h/causa-mcp-origin-colour]]
   [:label {:data-testid "rf-causa-mcp-origin-filter-toggle"
            :style {:display "flex"
                    :align-items "center"
                    :gap "6px"
                    :cursor "pointer"
                    :color (:text-secondary tokens)}}
    [:input {:type      "checkbox"
             :checked   (boolean origin-filter-enabled?)
             :on-change #(rf/dispatch [:rf.causa/toggle-mcp-origin-filter]
                                      {:frame :rf/causa})
             :style     {:cursor "pointer"}}]
    [:span "Highlight :causa-mcp events across panels"]]])

(defn header
  "Panel header — title + agent-attached badge + counts + filter
  chips. Mirrors `issues_ribbon.cljs`'s header for visual continuity."
  [{:keys [agent-attached? total rendered active-op-types
           distinct-op-types op-type-counts since-ms any-filter?]}]
  [:header {:style {:padding "12px 16px 6px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size "16px"
                  :font-weight 600
                  :margin 0
                  :color  (:text-primary tokens)}}
     "MCP"]
    [:span {:data-testid "rf-causa-mcp-attached-badge"
            :style {:font-size   "11px"
                    :font-family sans-stack
                    :color       (if agent-attached?
                                   (:causa-mcp-cyan tokens)
                                   (:text-tertiary tokens))
                    :border      (str "1px solid "
                                      (if agent-attached?
                                        (:causa-mcp-cyan tokens)
                                        (:border-subtle tokens)))
                    :border-radius "999px"
                    :padding     "1px 8px"}}
     (if agent-attached?
       "agent attached"
       "no activity")]
    [:span {:data-testid "rf-causa-mcp-counts"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered " / " total " in view")]
    (when any-filter?
      [:button {:data-testid "rf-causa-mcp-clear-filters"
                :on-click    #(rf/dispatch [:rf.causa/clear-mcp-filters]
                                           {:frame :rf/causa})
                :style       {:margin-left "auto"
                              :background  "transparent"
                              :color       (:cyan tokens)
                              :border      (str "1px solid "
                                                (:border-default tokens))
                              :padding     "2px 8px"
                              :border-radius "3px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "11px"}}
       "Clear filters"])]
   [:div {:style {:margin-top "8px"}}
    (op-type-chips active-op-types distinct-op-types op-type-counts)
    (since-input since-ms)]])
