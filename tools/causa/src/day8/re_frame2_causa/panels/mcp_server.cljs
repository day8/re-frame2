(ns day8.re-frame2-causa.panels.mcp-server
  "Stable facade for Causa's MCP Server panel.

  The MCP panel is split by responsibility:

  - `mcp-server-views` owns the root shell and subscriptions.
  - `mcp-server-chrome` owns header, filters, and inline settings.
  - `mcp-server-feed` owns feed rows and empty states.
  - `mcp-server-subs` owns read-model registrations.
  - `mcp-server-events` owns write-event registrations.
  - `mcp-server-helpers` remains the pure CLJC projection/model layer.

  The public view var and `install!` entry stay here so shell/tests keep
  requiring one stable namespace."
  (:require [day8.re-frame2-causa.panels.mcp-server-events :as events]
            [day8.re-frame2-causa.panels.mcp-server-subs :as subs]
            [day8.re-frame2-causa.panels.mcp-server-views :as views]))

(def mcp-server-view
  "The MCP Server panel's root view."
  views/mcp-server-view)

(defn install!
  "Idempotent install for the MCP Server panel's Causa-side
  registrations."
  []
  (subs/install!)
  (events/install!)
  nil)
