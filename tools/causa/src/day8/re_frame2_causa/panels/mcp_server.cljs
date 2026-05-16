(ns day8.re-frame2-causa.panels.mcp-server
  "MCP Server panel shell.

  Follows the canonical panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — the facade owns the panel's
  single public `reg-view`; the body delegates to the plain Reagent
  fn `mcp-server-views/mcp-server-view`. Subs/events leaves expose
  `install!`.

  The panel is split by responsibility:

  - view shell — body in `mcp-server-views`.
  - header / filters / inline settings — `mcp-server-chrome`.
  - feed rows / empty states — `mcp-server-feed`.
  - style tokens / origin colour — `mcp-server-style`.
  - subscriptions — `mcp-server-subs`.
  - events — `mcp-server-events`.
  - pure projection/model — `mcp-server-helpers` (.cljc; JVM-testable).

  Per the convention §Re-exports are minimal and intentional, the
  facade re-exports `install!` (the panel's installation entry) and
  `mcp-server-view` (the view name `shell.cljs` references). Leaf
  subs/events/feed/chrome/style surfaces are NOT re-exported — they
  are internal organisation reached via the `install!` chain or
  direct sibling :require, and re-exporting them would invert the
  encapsulation the split exists to create."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-events :as events]
            [day8.re-frame2-causa.panels.mcp-server-subs :as subs]
            [day8.re-frame2-causa.panels.mcp-server-views :as views]))

(rf/reg-view mcp-server-view
  "The MCP Server panel's root view."
  []
  [views/mcp-server-view])

(defn install!
  "Idempotent install for the MCP Server panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  nil)
