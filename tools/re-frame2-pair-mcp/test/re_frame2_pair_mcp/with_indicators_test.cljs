(ns re-frame2-pair-mcp.with-indicators-test
  "Every tree-walking tool must carry `:dropped-sensitive` /
  `:elided-large` on its envelope when non-zero and omit them when zero
  (spec/Conventions.md §Cross-MCP indicator-field vocabulary). That rule
  lives in `wire/with-indicators`, a passthrough to
  `re-frame.mcp-base.envelope/with-indicators`, whose suite pins it. This
  ns checks that each emit site below routes through it, so a bypass is
  red; a new tree-walking tool needs its own row."
  (:require [cljs.test :refer-macros [deftest is]]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "The `tools/re-frame2-pair-mcp` directory, found by walking up from the
  test process's cwd."
  []
  (loop [d (.cwd js/process)]
    (cond
      (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
      (.join path d "tools/re-frame2-pair-mcp")

      (.existsSync fs (.join path d "src/re_frame2_pair_mcp"))
      d

      (= d (.dirname path d))
      (throw (ex-info "Could not locate re-frame2-pair-mcp root from cwd"
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(deftest every-pinned-emit-site-routes-through-with-indicators
  (doseq [file ["snapshot.cljs" "get_path.cljs" "trace_window.cljs" "watch_epochs.cljs"]]
    (is (re-find #"with-indicators"
                 (.toString (.readFileSync fs (.join path (repo-root) "src/re_frame2_pair_mcp/tools" file))))
        (str file " MUST route its envelope through wire/with-indicators"))))
