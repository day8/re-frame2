(ns re-frame.story-mcp.tools.cap
  "Dispatcher + wire-boundary token-budget cap (rf2-rvyzy / rf2-zavp5 /
  rf2-eyelu).

  Per `spec/Cross-Cutting-Designs.md §3 Token budgets` every MCP
  `tools/call` response is bounded at ~5,000 tokens by default. The cap
  is enforced HERE (at the wire egress, after the handler runs), not in
  each handler — that keeps tool bodies free of token-accounting noise
  and pins one cross-MCP shape. When the serialised response would
  exceed the cap, the payload is replaced with a structured
  `{:rf.mcp/overflow {...}}` marker.

  ## Pipeline ownership

  The cap-enforcement ALGORITHM lives in `re-frame.mcp-base.cap`
  (rf2-eyelu) — token-summing across `:text` slots, comparing against
  the per-call cap, and building the overflow result. This ns supplies
  the per-server specialisation:

  - `result-io` reifies `mcp-base.cap/ResultIO` over the story-mcp
    result shape (`{:content [...] :structuredContent ...}` CLJ maps).
  - `overflow-hints` is the local hint table — the per-tool next-step
    prose stays here because the surfaces are domain-specific.

  Sized via `overflow/token-estimate` (the `(quot (count s) 4)` rule
  aligned with Anthropic's character→token rule-of-thumb). The cap is
  cumulative across every `:text` slot in the response's `:content`
  vector (multi-part responses share one budget, mirroring pair2-mcp).

  `:max-tokens` per-call override is read from `arguments`: integer
  cap, `0` disables (escape hatch when the caller has already
  paginated), absent ⇒ default. Lives on every tool's input schema
  via `registry/with-max-tokens`."
  (:require [re-frame.mcp-base.cap :as base-cap]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.registry :as registry]))

(def ^:private overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback (from `mcp-base.overflow`) when a tool isn't listed.

  Mirrors pair2-mcp's local hint table — the hint is the agent's
  shortest path back into budget."
  {"preview-variant"   "Tighten scope: drop `:cell-overrides` or pass a smaller `:active-modes`. The :app-db / :rendered-hiccup slots dominate; raise `max-tokens` (0 disables) if the full payload is genuinely needed."
   "run-variant"       "Tighten scope: pass `:cell-overrides` to shrink the run, or omit `:active-modes`. The :app-db / :rendered-hiccup slots dominate; raise `max-tokens` (0 disables) if the full payload is genuinely needed."
   "get-story"         "Story body is large — request `list-stories` for a slimmer overview, or raise `max-tokens` (0 disables)."
   "get-variant"       "Variant body is large — request `variant->edn` if you want EDN-only, or raise `max-tokens` (0 disables)."
   "variant->edn"      "Variant EDN body is large — narrow the variant or raise `max-tokens` (0 disables)."
   "list-stories"      "Story registry is large — narrow with `:tags`, or raise `max-tokens` (0 disables)."
   "read-failures"     "Failure log is large — assertions accumulator may be deep; clear with a fresh `run-variant`, or raise `max-tokens` (0 disables)."
   "record-as-variant" "Captured event stream is large — shorten `:duration-ms`, or raise `max-tokens` (0 disables)."})

(def ^:private result-io
  "ResultIO reify over story-mcp's CLJ-map result shape. The
  `:structuredContent` slot mirrors the wire conventions docs (an
  agent client that prefers JSON data reads it directly without
  re-parsing the text)."
  (reify base-cap/ResultIO
    (content-texts [_ result]
      (map :text (:content result)))
    (build-overflow-result [_ marker _original]
      {:content          [{:type "text" :text (h/pr-edn marker)}]
       :structuredContent marker})))

(defn invoke-tool
  "Invoke `tool-name` with `arguments` (a map of keyword-keyed args).
  Returns the tool's result map, or nil if no such tool. The caller
  serialises the result into a `tools/call` JSON-RPC response.

  ## Wire-boundary pipeline

  1. Dispatch the handler with `arguments`.
  2. `base-cap/apply-cap` (rf2-rvyzy / rf2-zavp5 / rf2-eyelu) — when
     the serialised response exceeds the per-call cap (`:max-tokens`
     arg, default `overflow/default-max-tokens`, `0` disables), the
     payload is replaced with a structured `{:rf.mcp/overflow ...}`
     marker. Per `spec/Cross-Cutting-Designs.md §3 Token budgets`.

  Catches any throw from the handler and returns it as a tool-execution
  error (`isError: true`) per MCP §Error Handling — handlers SHOULD
  return error-results themselves, but this is the belt-and-braces
  catch. The cap applies to error results too (large `:data` slots in
  an `ex-data` blow-up shouldn't bypass the budget)."
  [tool-name arguments]
  (when-let [t (registry/tool-by-name tool-name)]
    (let [args   (or arguments {})
          cap    (base-cap/max-tokens (get args :max-tokens))
          result (try
                   ((:handler t) args)
                   (catch Throwable e
                     (h/error-result (str "Tool handler threw: " (ex-message e))
                                     {:tool      tool-name
                                      :exception (.getName (class e))
                                      :data      (ex-data e)})))]
      (base-cap/apply-cap result-io result
                          {:tool tool-name
                           :cap  cap
                           :hint (get overflow-hints tool-name)}))))
