(ns day8.re-frame2-causa.panels.mcp-server-views
  "Top-level view shell for the MCP Server panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-chrome :as chrome]
            [day8.re-frame2-causa.panels.mcp-server-feed :as feed]
            [day8.re-frame2-causa.panels.mcp-server-style :as style]))

(def ^:private tokens style/tokens)
(def ^:private sans-stack style/sans-stack)

(rf/reg-view mcp-server-view
  "The MCP Server panel's root view. Subscribes to
  `:rf.causa/mcp-server` (composite) + `:rf.causa/mcp-origin-filter-
  enabled?` (settings sub-pane state)."
  []
  (let [{:keys [rows total rendered op-type-counts distinct-op-types
                filters agent-attached? empty-kind]}
        @(rf/subscribe [:rf.causa/mcp-server])
        origin-filter-enabled?
        @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])
        {:keys [op-types since-ms]} filters
        any-filter? (boolean (or (seq op-types) (some? since-ms)))]
    [:section {:data-testid "rf-causa-mcp-server"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (chrome/header {:agent-attached?    agent-attached?
                     :total              total
                     :rendered           rendered
                     :active-op-types    (or op-types #{})
                     :distinct-op-types  distinct-op-types
                     :op-type-counts     op-type-counts
                     :since-ms           since-ms
                     :any-filter?        any-filter?})
     (chrome/settings-sub-pane
       {:origin-filter-enabled? origin-filter-enabled?})
     (feed/activity-feed {:rows rows :empty-kind empty-kind})]))
