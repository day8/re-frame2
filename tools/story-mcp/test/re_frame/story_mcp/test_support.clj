(ns re-frame.story-mcp.test-support
  "Shared test helpers for the story-mcp suite.

  Home for fns that only the test corpus uses but that conceptually sit
  alongside the production code — the story-mcp counterpart to
  pair-mcp's `re-frame2-pair-mcp.test-utils`.

  ## `dedup-expand`

  The inverse of `re-frame.mcp-base.dedup/dedup-value`, used to assert
  round-trip exactness against a wire payload. The MCP server itself
  NEVER inverts the transform — the wire contract is that the agent host
  calls `re-frame.mcp-base.dedup/expand` directly on the `:rf.mcp/dedup-table`
  cache-map — so the inverse is test-only and lives here, signalling
  \"test-only\" by location (rf2-ywkiss moved it out of the production
  `tools.dedup` namespace, which itself was removed as a pass-through
  facade over `re-frame.mcp-base.dedup`)."
  (:require [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(defn dedup-expand
  "Reverse `re-frame.mcp-base.dedup/dedup-value`. Given a value possibly
  wrapped in the `:rf.mcp/dedup-table` marker, reconstruct the original
  structure via `re-frame.mcp-base.dedup/expand`. Idempotent on already-expanded
  values (returns the input unchanged when the wrapper isn't present)."
  [v]
  (if (and (map? v) (contains? v rf.mcp-base.vocab/dedup-table-key))
    (rf.mcp-base.dedup/expand (get v rf.mcp-base.vocab/dedup-table-key))
    v))
