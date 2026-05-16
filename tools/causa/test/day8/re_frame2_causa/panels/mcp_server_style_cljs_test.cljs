(ns day8.re-frame2-causa.panels.mcp-server-style-cljs-test
  "Per-leaf smoke test for `mcp-server-style` (rf2-nb8if).

  Pins the panel-local token map carries the inferential
  `:causa-mcp-cyan` origin colour (Tailwind cyan-500 #06B6D4 per
  helpers spec)."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.mcp-server-style :as style]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]))

(deftest tokens-include-causa-mcp-origin-colour
  (is (= h/causa-mcp-origin-colour (:causa-mcp-cyan style/tokens))
      "the leaf-local token map re-exposes the inferential origin colour"))

(deftest sans-and-mono-stacks-are-non-empty-strings
  (is (string? style/sans-stack))
  (is (string? style/mono-stack)))
