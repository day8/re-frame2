(ns re-frame.story-mcp.tools.registry
  "The MCP tool registry — assembled from each category ns's
  `descriptors` def, in documented IMPL-SPEC §7.2 / §7.3 order:
  dev → docs → testing → write. Agents tend to scan `tools/list`
  top-to-bottom, so order matters.

  Each tool descriptor is a map:

      {:name        \"<dash-separated-name>\"
       :description \"<one-line semantics>\"
       :category    :dev | :docs | :testing | :write
       :inputSchema { ... JSON schema ... }
       :typicalTokens <positive int>
       :handler     (fn [args] result-map-or-error)}

  The schema fragments + injection helpers live in
  `re-frame.story-mcp.tools.schemas`; the wire-boundary token-cap
  dispatcher (`invoke-tool`) lives in `re-frame.story-mcp.tools.cap`."
  (:require [re-frame.story-mcp.tools.dev :as dev]
            [re-frame.story-mcp.tools.docs :as docs]
            [re-frame.story-mcp.tools.recorder :as recorder]
            [re-frame.story-mcp.tools.testing :as testing]
            [re-frame.story-mcp.tools.write :as write]))

(def tool-registry
  "Canonical ordered vector of every tool the server exposes. Dev →
  docs → testing → write. The recorder bridge
  (`record-as-variant`) lives in `tools.recorder` for leaf-size
  reasons (rf2-zkca8) but belongs at the tail of the write category
  per IMPL-SPEC §7.3."
  (-> []
      (into cat [dev/descriptors
                 docs/descriptors
                 testing/descriptors
                 write/descriptors])
      (conj recorder/descriptor)))

(defn tool-descriptors
  "Build the `tools/list` response payload: each tool's name +
  description + inputSchema + typicalTokens, in registry order. The
  MCP spec also allows a `title` field; we omit it (the names are
  already human-readable dash-separated forms).

  `typicalTokens` (rf2-6sddv) is an informational hint — an integer
  ballpark of the response payload size in tokens. AI clients use it
  to budget calls and pick size-conscious args without trial-and-error.
  Not a cap (the host enforces real budgets elsewhere); a hint only."
  []
  (mapv (fn [{:keys [name description inputSchema typicalTokens]}]
          (cond-> {:name        name
                   :description description
                   :inputSchema inputSchema}
            typicalTokens (assoc :typicalTokens typicalTokens)))
        tool-registry))

(defn tool-by-name
  "Look up a tool's registry entry by string name. Returns nil if no
  such tool — the caller (server dispatcher) turns that into a
  protocol-level method-not-found error."
  [tool-name]
  (some (fn [t] (when (= tool-name (:name t)) t)) tool-registry))
