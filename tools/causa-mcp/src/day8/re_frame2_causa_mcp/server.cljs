(ns day8.re-frame2-causa-mcp.server
  "MCP server entry-point (placeholder; rf2-8xzoe.1 F-1 scaffold).

  At F-1 this ns exists only to give shadow-cljs a real `:main` to
  point the `:server` build at. The real server entry-point —
  stdio JSON-RPC handshake, persistent nREPL socket, Causa-shaped
  tool dispatch — lands in subsequent F-tranche beads of rf2-8xzoe.
  The architecture mirrors `tools/pair2-mcp/src/re_frame_pair2_mcp/server.cljs`
  one-for-one (different tool catalogue, different :origin tag).

  ## Namespace convention

  Lock #6 + Lock #11 of `tools/causa-mcp/spec/DESIGN-RATIONALE.md`:
  MCP-server-side code lives under `day8.re-frame2-causa-mcp.*`
  (matching the maven coord `day8/re-frame2-causa-mcp` and the npm
  coord `@day8/re-frame2-causa-mcp`). Injected-runtime code (which
  lands later) will live under `day8.re-frame2-causa.runtime` — the
  preload-classpath surface is kept distinct from the Node-only
  server surface.

  ## Why a banner-only `main` rather than a TODO comment

  The F-1 acceptance is a *real file that compiles*, not a sentinel.
  `main` is a one-line stderr write so the compiled `out/server.js`
  is exercisable end-to-end (`node out/server.js` prints the banner
  and exits 0) — the build substrate is verifiable before the wire
  pipeline lands."
  (:require [clojure.string :as str]))

(defn main
  "Placeholder entry-point. Prints a startup banner identifying this
  as the F-1 scaffold and exits cleanly. Replaced by the real MCP
  server wiring in subsequent rf2-8xzoe tranche beads."
  [& args]
  (let [banner (str "[re-frame2-causa-mcp] F-1 scaffold (rf2-8xzoe.1). "
                    "Real server entry-point lands in subsequent "
                    "rf2-8xzoe tranche beads. "
                    "args=" (pr-str (vec args)))]
    (binding [*print-fn* *print-err-fn*]
      (println (str/trim banner)))))
