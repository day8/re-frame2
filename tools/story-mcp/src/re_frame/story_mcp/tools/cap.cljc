(ns re-frame.story-mcp.tools.cap
  "Dispatcher + wire-boundary token-budget cap (rf2-rvyzy / rf2-zavp5).

  Per `spec/Cross-Cutting-Designs.md §3 Token budgets` every MCP
  `tools/call` response is bounded at ~5,000 tokens by default. The cap
  is enforced HERE (at the wire egress, after the handler runs), not in
  each handler — that keeps tool bodies free of token-accounting noise
  and pins one cross-MCP shape. When the serialised response would
  exceed the cap, the payload is replaced with a structured
  `{:rf.mcp/overflow {...}}` marker emitted via
  `re-frame.mcp-base.overflow/overflow-payload`.

  Sized via `overflow/token-estimate` (the `(quot (count s) 4)` rule
  aligned with Anthropic's character→token rule-of-thumb). The cap is
  cumulative across every `:text` slot in the response's `:content`
  vector (multi-part responses share one budget, mirroring pair2-mcp).

  `:max-tokens` per-call override is read from `arguments`: integer
  cap, `0` disables (escape hatch when the caller has already
  paginated), absent ⇒ default. Lives on every tool's input schema
  via `registry/with-max-tokens`."
  (:require [re-frame.mcp-base.overflow :as overflow]
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

(defn- max-tokens-arg
  "Resolve the per-call cap from MCP arguments. Returns an integer cap
  in tokens, or `nil` when the cap is disabled (`:max-tokens 0`).
  Defaults to `overflow/default-max-tokens` when absent or not a number."
  [arguments]
  (let [raw (get arguments :max-tokens)]
    (cond
      (nil? raw)                  overflow/default-max-tokens
      (and (integer? raw) (zero? raw)) nil
      (integer? raw)              raw
      :else                       overflow/default-max-tokens)))

(defn sum-text-tokens
  "Sum `token-estimate` across every `:text` slot in the
  `{:content [{:type \"text\" :text ...} ...]}` result. The serialised
  response's wire size is dominated by these slots; the JSON envelope
  is bounded and ignored."
  [result]
  (transduce (comp (map :text)
                   (filter string?)
                   (map overflow/token-estimate))
             +
             0
             (:content result)))

(defn- overflow-result
  "Build a fresh result carrying the overflow marker, mirroring
  pair2-mcp's `overflow-result`. Drops the over-budget payload entirely
  and emits the structured marker as the sole `:text` slot.

  The overflow marker is itself the response — `:isError` is NOT set
  (an over-budget success is a signal to retry with narrower args, not
  a tool-execution failure)."
  [tool-name token-count cap]
  (let [marker (overflow/overflow-payload
                 {:tool        tool-name
                  :token-count token-count
                  :cap         cap
                  :hint        (get overflow-hints tool-name)})]
    {:content          [{:type "text" :text (h/pr-edn marker)}]
     :structuredContent marker}))

(defn- apply-cap
  "Wire-boundary cap enforcement. Returns either `result` unchanged
  (under the cap, or cap disabled via `:max-tokens 0`) or a fresh
  result carrying the overflow marker.

  Cap is cumulative across every `:text` slot in `:content`. Mirrors
  pair2-mcp's `apply-cap` — the same `:truncate-with-marker` strategy
  is the only one wired today; future strategies (path-slicing, lazy
  summary) slot in here without touching tool bodies."
  [result tool-name cap]
  (cond
    (nil? cap)        result
    (nil? result)     result
    :else
    (let [tokens (sum-text-tokens result)]
      (if (<= tokens cap)
        result
        (overflow-result tool-name tokens cap)))))

(defn invoke-tool
  "Invoke `tool-name` with `arguments` (a map of keyword-keyed args).
  Returns the tool's result map, or nil if no such tool. The caller
  serialises the result into a `tools/call` JSON-RPC response.

  ## Wire-boundary pipeline

  1. Dispatch the handler with `arguments`.
  2. `apply-cap` (rf2-rvyzy / rf2-zavp5) — when the serialised response
     exceeds the per-call cap (`:max-tokens` arg, default
     `overflow/default-max-tokens`, `0` disables), the payload is
     replaced with a structured `{:rf.mcp/overflow ...}` marker. Per
     `spec/Cross-Cutting-Designs.md §3 Token budgets`.

  Catches any throw from the handler and returns it as a tool-execution
  error (`isError: true`) per MCP §Error Handling — handlers SHOULD
  return error-results themselves, but this is the belt-and-braces
  catch. The cap applies to error results too (large `:data` slots in
  an `ex-data` blow-up shouldn't bypass the budget)."
  [tool-name arguments]
  (when-let [t (registry/tool-by-name tool-name)]
    (let [args   (or arguments {})
          cap    (max-tokens-arg args)
          result (try
                   ((:handler t) args)
                   (catch Throwable e
                     (h/error-result (str "Tool handler threw: " (ex-message e))
                                     {:tool      tool-name
                                      :exception (.getName (class e))
                                      :data      (ex-data e)})))]
      (apply-cap result tool-name cap))))
