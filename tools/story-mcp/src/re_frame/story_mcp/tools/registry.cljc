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
  dispatcher (`invoke-tool`) lives in `re-frame.story-mcp.tools.wire-pipeline`.

  ## Handler arity (cross-MCP note)

  Every registered handler is 1-arity `(fn [args])` — story-mcp is
  JVM-side single-process; there is no remote runtime `conn` to thread
  through, and the server ships no streaming tool so the MCP `extra`
  payload (`signal` / `sendNotification` / `_meta.progressToken`)
  carries no useful slot either. Contrast pair-mcp's 3-arity shape
  `(fn [conn args extra])`. The divergence is deliberate and is
  documented at `tools/mcp-base/spec/handler-arity.md`; a phase-2
  unification awaits a third server instance and lands as a separate
  bead."
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
  reasons but belongs at the tail of the write category
  per IMPL-SPEC §7.3 — `recorder/descriptors` is a one-element vec
  so the assembly stays symmetric across every category ns."
  (into [] cat [dev/descriptors
                docs/descriptors
                testing/descriptors
                write/descriptors
                recorder/descriptors]))

;; Load-time invariant: every registry entry MUST carry a positive-integer
;; `:typicalTokens` hint. The same shape is asserted by
;; `typical-tokens-hint-on-every-tool` in the test corpus; pinning it at
;; load time lets `tool-descriptors` project a plain map literal without
;; a defensive `cond->` arm for the slot.
(assert (every? (fn [t] (and (integer? (:typicalTokens t))
                             (pos? (:typicalTokens t))))
                tool-registry)
        "tool-registry: every entry must carry positive-integer :typicalTokens")

;; Load-time invariant: every registry entry MUST carry an
;; `:outputSchema` map describing the structuredContent payload shape.
;; mcp-builder canonical pattern: "Define outputSchema wherever possible
;; for structured responses." Asserted at load time so future tool
;; landings can't silently drop the slot.
(assert (every? (fn [t] (map? (:outputSchema t))) tool-registry)
        "tool-registry: every entry must carry an :outputSchema map (rf2-3l3be)")

;; Load-time invariant: every registry entry MUST carry an
;; `:annotations` map advertising the MCP tool-annotation hints
;; (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`).
;; mcp_best_practices.md: agent hosts use these to auto-approve reads
;; and gate destructive ops. Asserted at load time so future tool
;; landings can't silently drop the slot.
(assert (every? (fn [t] (map? (:annotations t))) tool-registry)
        "tool-registry: every entry must carry an :annotations map (rf2-94p8q)")

(def gated-input-keys
  "The input-property keys the DEFAULT `tools/list` profile gates off
  behind an operator-only gate. Today: `:include-sensitive`
  — baked into five value-surfacing tools' descriptors at load time and
  stripped from the default wire surface by `strip-include-sensitive`
  when `--allow-sensitive-reads` is closed. As STRINGS (the
  descriptor-manifest row shape stringifies input keys), so the
  descriptor-manifest generator can pass this set straight to
  `dm/build-manifest` and the committed manifest distinguishes the
  default surface from the gate-open one. The single source of truth for
  WHICH inputs are gated — `strip-include-sensitive` and the generator
  both derive from it, so a new gated knob is added in one place."
  #{"include-sensitive"})

(def ^:private gated-input-prop-keys
  "`gated-input-keys` as keyword property keys (the descriptor's
  `:properties` map is keyword-keyed). The single set above is the
  string-shaped public source; this is its keyword projection for the
  `tools/list`-time strip."
  (into #{} (map keyword) gated-input-keys))

(defn- strip-include-sensitive
  "Remove the gated input slots (`gated-input-keys`, today
  `:include-sensitive`) from a tool's `:inputSchema` properties. The
  slot is baked into the descriptor at load time by
  `schemas/with-include-sensitive`; this fn runs at `tools/list` time
  and removes it when the operator-only gate is closed so
  the descriptor never advertises an opt-in the server is configured
  to ignore. Idempotent: tools whose schema never carried a gated slot
  are returned unchanged."
  [schema]
  (if (some (:properties schema) gated-input-prop-keys)
    (update schema :properties #(apply dissoc % gated-input-prop-keys))
    schema))

(defn tool-descriptors
  "Build the `tools/list` response payload: each tool's name +
  description + inputSchema + typicalTokens, in registry order. The
  MCP spec also allows a `title` field; we omit it (the names are
  already human-readable dash-separated forms).

  `typicalTokens` is an informational hint — an integer
  ballpark of the response payload size in tokens. AI clients use it
  to budget calls and pick size-conscious args without trial-and-error.
  Not a cap (the host enforces real budgets elsewhere); a hint only.

  The `:typicalTokens` slot is asserted on every entry by the
  `typical-tokens-hint-on-every-tool` test plus the registry-load
  assertion below, so the projection is a plain map literal rather
  than a defensive `cond->`.

  ## Sensitive-read gate

  The `:include-sensitive` slot is stripped from every tool's input
  schema when the operator-only gate (`config/sensitive-reads-allowed?`)
  is closed — agents shouldn't see an opt-in they can't exercise. The
  affected tools — the five that surface live or plan-resolved frame
  VALUES (`preview-variant`, `run-variant`, `read-failures`,
  `read-a11y-violations`, `record-as-variant`) — silently ignore
  caller-supplied `:include-sensitive true` at the helper layer
  regardless (`explain-variant` is NOT in this set (rf2-7k5mce): it is
  a no-run projection over the registry, so it ships author data raw
  like `get-variant` and carries no gate knob), so the descriptor
  strip is purely a UX improvement and a defence-in-depth signal."
  []
  (let [strip? (not (config/sensitive-reads-allowed?))]
    (mapv (fn [{:keys [name description inputSchema outputSchema annotations typicalTokens]}]
            (cond-> {:name          name
                     :description   description
                     :inputSchema   (cond-> inputSchema
                                      strip? strip-include-sensitive)
                     :typicalTokens typicalTokens}
              ;; Surface :outputSchema when declared. Lifted
              ;; via cond-> so omission is forward-compatible (e.g. a
              ;; future tool with no structured response).
              (some? outputSchema) (assoc :outputSchema outputSchema)
              ;; Surface :annotations when declared. Agent
              ;; hosts read these to auto-approve reads and gate
              ;; destructive ops behind a confirmation ceremony.
              (some? annotations)  (assoc :annotations annotations)))
          tool-registry)))

(defonce ^:private tool-by-name-index
  ;; `defonce` so a REPL `(require ... :reload)` of this ns
  ;; does NOT re-bind the index out from under callers that captured
  ;; the old map. The registry shape is frozen at load time
  ;; (`tool-registry` is a `def`), so re-binding adds nothing —
  ;; `defonce` is the right primitive when the value is computed once
  ;; and lives for the lifetime of the process.
  ;;
  ;; Pre-computed `name → descriptor` map so `tool-by-name` is O(1)
  ;; instead of the linear scan over `tool-registry`.
  (into {} (map (juxt :name identity)) tool-registry))

(defn tool-by-name
  "Look up a tool's registry entry by string name. Returns nil if no
  such tool — the caller (server dispatcher) turns that into a
  protocol-level method-not-found error."
  [tool-name]
  (get tool-by-name-index tool-name))
