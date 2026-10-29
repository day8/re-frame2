(ns re-frame.mcp-base.overflow
  "Wire-boundary token-budget cap shape builder.

  Per `spec/Principles.md` §Tight token budget per response, every MCP
  `tools/call` response is bounded at ~5,000 tokens by default. When
  the serialised response would exceed the cap, the wrapper replaces
  the payload with a structured `{:rf.mcp/overflow {...}}` marker and
  emits that instead. Silent truncation is unacceptable — it corrupts
  the agent's conversation without telling the agent.

  This namespace owns the SHAPE of the marker (so it stays byte-
  identical across the pair); `re-frame.mcp-base.cap` owns the shared
  algorithm that counts tokens and replaces the payload, and each
  consumer supplies its payload accessors and per-tool hint table.

  ## Token rule

  `token-estimate s = (quot (count s) 4)`. Cheap character→token
  approximation aligned with Anthropic's rule-of-thumb for English /
  EDN. Not exact; the goal is a bounded wire payload, not a precise
  meter."
  (:require [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(def ^:const default-max-tokens
  "The convention's documented cap. Sized for a typical 5K-token MCP
  response envelope after diff-encode + dedup."
  5000)

(defn token-estimate
  "Cheap character→token approximation: `(quot (count s) 4)`. Aligned
  with the published Anthropic rule-of-thumb for English / EDN. The
  goal is a bounded wire payload, not a precise per-token meter."
  [s]
  (quot (count s) 4))

(def overflow-hint-fallback
  "Generic overflow hint used when a tool isn't listed in the per-tool
  hint table. The marker replaces the reply AFTER the handler ran, so
  the hint must be safe for a tool that changed state: a blind re-call
  would repeat the change."
  "Response over budget. If this call changes state it has already run: do not repeat it, read the state back instead. Otherwise narrow the arguments, or raise `max-tokens` (0 disables the cap).")

(defn overflow-payload
  "Build the structured overflow marker that replaces an over-budget
  response. Shape is stable per spec/Principles.md §Tight token
  budget: callers pattern-match on the top-level `:rf.mcp/overflow`
  key. `:limit :reached` is a fixed sentinel; `:token-count` is the
  estimate that tripped the cap; `:hint` is tool-specific.

  `opts` keys:
    :tool        - the tool name (string)
    :token-count - estimated token count of the over-budget payload
    :cap         - the cap (tokens)
    :hint        - tool-specific next-step hint (optional;
                   `overflow-hint-fallback` if absent)"
  [{:keys [tool token-count cap hint]}]
  {rf.mcp-base.vocab/overflow-key {:limit       :reached
                       :token-count token-count
                       :cap-tokens  cap
                       :tool        tool
                       :hint        (or hint overflow-hint-fallback)}})
