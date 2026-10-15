(ns re-frame.mcp-conformance.indicator-field-test
  "Cross-MCP suppression-indicator routing.
  Tree-walking tools must use the shared emit path; helper semantics and
  slot schemas are pinned by their own tests."
  (:require [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

(def ^:private tree-walking-tool-sources
  "Per-tool source files for re-frame2-pair-mcp's tree-walking tools. Each
  source MUST contain at least one `wire/with-indicators` call —
  that's the centralised emit-path the contract pins. Adding a new
  tree-walking tool means extending this list AND wiring the helper
  call; the new entry without the wiring fails this gate."
  {:snapshot     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/snapshot.cljs"
   :get-path     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/get_path.cljs"
   :trace-window "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/trace_window.cljs"
   :watch-epochs "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/watch_epochs.cljs"})

(deftest every-tree-walking-tool-routes-through-the-helper
  (doseq [[tool rel] tree-walking-tool-sources]
    (testing (str "tool " tool " — wire/with-indicators call-site in " rel)
      ;; Match against `rf.mcp-conformance.fixtures/strip-comments-and-strings`-neutered source so a
      ;; docstring / comment MENTION of `wire/with-indicators` can't satisfy
      ;; the routing pin — only a real CODE reference counts. Any tool
      ;; source can name the helper in a docstring.
      (let [src      (rf.mcp-conformance.fixtures/read-source rel)
            stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings src)]
        (is (str/includes? stripped "wire/with-indicators")
            (str "Tool " tool " at " rel
                 " does not route its envelope through `wire/with-indicators`. "
                 "The centralised emit-path is the structural contract — a "
                 "tool that inlines `(assoc :dropped-sensitive ...)` or "
                 "`(assoc :elided-large ...)` directly violates the MUST-"
                 "level parity rule per Conventions §Cross-MCP indicator-field vocabulary / Spec 009 §Size elision in traces."))))))

(def ^:private pair-mcp-reexport-rel
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/wire.cljs")

(deftest pair-mcp-wire-re-exports-the-canonical-helper
  (is (str/includes? (rf.mcp-conformance.fixtures/read-source pair-mcp-reexport-rel)
                     "rf.mcp-base.envelope/with-indicators")
      (str "pair-mcp `with-indicators` does not delegate to "
           "`re-frame.mcp-base.envelope/with-indicators`. The emit-path "
           "MUST stay centralised in mcp-base — an "
           "inlined copy forks the omit-when-zero MUST across "
           "servers.")))
