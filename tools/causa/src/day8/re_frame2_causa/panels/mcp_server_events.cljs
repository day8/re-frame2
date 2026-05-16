(ns day8.re-frame2-causa.panels.mcp-server-events
  "Write events for the MCP Server panel."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install MCP Server panel events."
  []
  (rf/reg-event-db :rf.causa/toggle-mcp-op-type
    (fn [db [_ op-type]]
      (let [current (get db :mcp-active-op-types #{})]
        (assoc db :mcp-active-op-types
               (if (contains? current op-type)
                 (disj current op-type)
                 (conj current op-type))))))

  (rf/reg-event-db :rf.causa/set-mcp-since-seconds
    (fn [db [_ seconds]]
      (if (and (number? seconds) (pos? seconds))
        (assoc db :mcp-since-ms (* (long seconds) 1000))
        (dissoc db :mcp-since-ms))))

  (rf/reg-event-db :rf.causa/clear-mcp-filters
    (fn [db _event]
      (-> db
          (dissoc :mcp-active-op-types)
          (dissoc :mcp-since-ms))))

  (rf/reg-event-db :rf.causa/toggle-mcp-origin-filter
    (fn [db _event]
      (update db :mcp-origin-filter-enabled? not)))

  nil)
