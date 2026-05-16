(ns day8.re-frame2-causa.panels.mcp-server-subs
  "Read-model subscriptions for the MCP Server panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]))

(defn install!
  "Install MCP Server panel subscriptions."
  []
  (rf/reg-sub :rf.causa/mcp-filters
    (fn [db _query]
      {:op-types (get db :mcp-active-op-types #{})
       :since-ms (get db :mcp-since-ms)}))

  (rf/reg-sub :rf.causa/mcp-server
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/mcp-filters]
    (fn [[buffer filters] _query]
      (h/project-feed buffer filters (h/now-ms))))

  (rf/reg-sub :rf.causa/mcp-origin-filter-enabled?
    (fn [db _query]
      (boolean (get db :mcp-origin-filter-enabled? false))))

  nil)
