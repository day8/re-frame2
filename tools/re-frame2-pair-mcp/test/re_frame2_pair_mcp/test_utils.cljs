(ns re-frame2-pair-mcp.test-utils
  "Shared test helpers (rf2-ambfv).

  Home for fns that only the test corpus uses but that conceptually
  sit alongside the production code. Today's resident is
  `dedup-expand` — the inverse of `tools.dedup/dedup-value`, useful
  to assert round-trip exactness against an MCP-shaped wire payload
  without growing the production surface.

  Why not in `tools.dedup`: the agent host receives the deduped
  payload and reconstructs locally via `de-dupe.core/expand` directly
  — the MCP server never calls the inverse. Moving the helper here
  keeps the production ns minimal and signals \"test-only\" by
  location."
  (:require [de-dupe.core :as dedup]
            [re-frame.mcp-base.vocab :as base-vocab]))

(defn dedup-expand
  "Reverse `tools.dedup/dedup-value`. Given a value possibly wrapped in
  the `:rf.mcp/dedup-table` marker, reconstruct the original structure
  via `de-dupe.core/expand`. Idempotent on already-expanded values
  (returns the input unchanged when the wrapper isn't present)."
  [v]
  (if (and (map? v) (contains? v base-vocab/dedup-table-key))
    (dedup/expand (get v base-vocab/dedup-table-key))
    v))
