(ns day8.re-frame2-causa.panels.mcp-server-style
  "MCP Server panel-local style adapter.

  The shared theme owns the dark palette and type stacks. The MCP
  panel adds the inferential `:causa-mcp-cyan` origin colour here so
  view leaves can share one token map without promoting the colour to
  the global palette prematurely."
  (:require [day8.re-frame2-causa.panels.mcp-server-helpers :as h]
            [day8.re-frame2-causa.theme.tokens :as theme]))

(def tokens
  (assoc theme/tokens :causa-mcp-cyan h/causa-mcp-origin-colour))

(def sans-stack theme/sans-stack)
(def mono-stack theme/mono-stack)
