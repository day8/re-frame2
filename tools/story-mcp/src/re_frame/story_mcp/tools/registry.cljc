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
  dispatcher (`invoke-tool`) lives in `re-frame.story-mcp.tools.cap`.

  ## Handler arity (cross-MCP note)

  Every registered handler is 1-arity `(fn [args])` — story-mcp is
  JVM-side single-process; there is no remote runtime `conn` to thread
  through, and the server ships no streaming tool so the MCP `extra`
  payload (`signal` / `sendNotification` / `_meta.progressToken`)
  carries no useful slot either. Contrast pair-mcp's 3-arity shape
  `(fn [conn args extra])`. The divergence is deliberate and is
  documented at `tools/mcp-base/spec/handler-arity.md`; a phase-2
  unification awaits a third server instance (causa-mcp) and lands as
  a separate bead."
  (:require [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.dev :as dev]
            [re-frame.story-mcp.tools.docs :as docs]
            [re-frame.story-mcp.tools.recorder :as recorder]
            [re-frame.story-mcp.tools.testing :as testing]
            [re-frame.story-mcp.tools.write :as write]))

(def tool-registry
  "Canonical ordered vector of every tool the server exposes. Dev →
  docs → testing → write. The recorder bridge
  (`record-as-variant`) lives in `tools.recorder` for leaf-size
  reasons (rf2-zkca8) but belongs at the tail of the write category
  per IMPL-SPEC §7.3 — `recorder/descriptors` is a one-element vec
  so the assembly stays symmetric across every category ns."
  (into [] cat [dev/descriptors
                docs/descriptors
                testing/descriptors
                write/descriptors
                recorder/descriptors]))

;; Load-time invariant: every registry entry MUST carry a positive-integer
;; `:typicalTokens` hint (rf2-6sddv). The same shape is asserted by
;; `typical-tokens-hint-on-every-tool` in the test corpus; pinning it at
;; load time lets `tool-descriptors` project a plain map literal without
;; a defensive `cond->` arm for the slot.
(assert (every? (fn [t] (and (integer? (:typicalTokens t))
                             (pos? (:typicalTokens t))))
                tool-registry)
        "tool-registry: every entry must carry positive-integer :typicalTokens")

;; Load-time invariant (rf2-3l3be): every registry entry MUST carry an
;; `:outputSchema` map describing the structuredContent payload shape.
;; mcp-builder canonical pattern: "Define outputSchema wherever possible
;; for structured responses." Asserted at load time so future tool
;; landings can't silently drop the slot.
(assert (every? (fn [t] (map? (:outputSchema t))) tool-registry)
        "tool-registry: every entry must carry an :outputSchema map (rf2-3l3be)")

(defn- strip-include-sensitive
  "Remove the `:include-sensitive` slot from a tool's `:inputSchema`
  properties. The slot is baked into the descriptor at load time by
  `schemas/with-include-sensitive`; this fn runs at `tools/list` time
  and removes it when the operator-only gate is closed (rf2-g9fje) so
  the descriptor never advertises an opt-in the server is configured
  to ignore. Idempotent: tools whose schema never carried the slot are
  returned unchanged."
  [schema]
  (if (contains? (:properties schema) :include-sensitive)
    (update schema :properties dissoc :include-sensitive)
    schema))

(defn tool-descriptors
  "Build the `tools/list` response payload: each tool's name +
  description + inputSchema + typicalTokens, in registry order. The
  MCP spec also allows a `title` field; we omit it (the names are
  already human-readable dash-separated forms).

  `typicalTokens` (rf2-6sddv) is an informational hint — an integer
  ballpark of the response payload size in tokens. AI clients use it
  to budget calls and pick size-conscious args without trial-and-error.
  Not a cap (the host enforces real budgets elsewhere); a hint only.

  The `:typicalTokens` slot is asserted on every entry by the
  `typical-tokens-hint-on-every-tool` test plus the registry-load
  assertion below, so the projection is a plain map literal rather
  than a defensive `cond->`.

  ## Sensitive-read gate (rf2-g9fje)

  The `:include-sensitive` slot is stripped from every tool's input
  schema when the operator-only gate (`config/sensitive-reads-allowed?`)
  is closed — agents shouldn't see an opt-in they can't exercise. The
  three affected tools (`preview-variant`, `run-variant`, `read-failures`)
  silently ignore caller-supplied `:include-sensitive true` at the
  helper layer regardless, so the descriptor strip is purely a UX
  improvement and a defence-in-depth signal."
  []
  (let [strip? (not (config/sensitive-reads-allowed?))]
    (mapv (fn [{:keys [name description inputSchema outputSchema typicalTokens]}]
            (cond-> {:name          name
                     :description   description
                     :inputSchema   (cond-> inputSchema
                                      strip? strip-include-sensitive)
                     :typicalTokens typicalTokens}
              ;; rf2-3l3be — surface :outputSchema when declared. Lifted
              ;; via cond-> so omission is forward-compatible (e.g. a
              ;; future tool with no structured response).
              (some? outputSchema) (assoc :outputSchema outputSchema)))
          tool-registry)))

(def ^:private tool-by-name-index
  "Pre-computed `name → descriptor` map so `tool-by-name` is O(1)
  instead of the linear scan over `tool-registry`. The registry shape
  is frozen at load time (`tool-registry` is a `def`), so the index
  is similarly stable."
  (into {} (map (juxt :name identity)) tool-registry))

(defn tool-by-name
  "Look up a tool's registry entry by string name. Returns nil if no
  such tool — the caller (server dispatcher) turns that into a
  protocol-level method-not-found error."
  [tool-name]
  (get tool-by-name-index tool-name))
