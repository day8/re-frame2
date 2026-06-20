(ns re-frame.story-mcp.tools.dedup
  "Structural dedup at the wire boundary — JVM-side mirror
  of `re-frame2-pair-mcp.tools.dedup`.

  ## Why dedup at the wire boundary

  Persistent data structures share subtrees in memory; `pr-str` flattens
  the sharing. `day8/de-dupe` walks a persistent data structure,
  hash-identifies repeated subtrees, and rewrites the structure as a
  flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols.
  The library guarantees round-trip exactness via the companion `expand`
  function; the agent host can decode locally.

  story-mcp's wire surfaces ship the same kinds of duplicate-rich
  payloads pair-mcp deduplicates: variant run results carry
  `:app-db` + `:snapshot` + `:rendered-hiccup` (the same sensitive
  values reappear across all three derived trees), assertion vectors
  repeat per-record envelope keys, and recorder captures replay event
  tuples whose argument structure repeats. The reduction-ratio
  measurement on a representative `run-variant` fixture (see
  `dedup_test.clj`) is the canonical evidence.

  ## When dedup runs

  Applied uniformly at `invoke-tool` time over the `:structuredContent`
  slot, BEFORE the wire-cap check (`re-frame.mcp-base.cap/apply-cap`).
  Ordering matters: dedup shrinks the payload first, so the cap-honest
  size is post-dedup. Same ordering invariant as pair-mcp's
  `wire-pipeline` (dedup → cap).

  ## Why `de-dupe-eq` (equality), not `de-dupe` (identity)

  Data arrives at the MCP server via Clojure-side dispatch — most
  structurally-equal subtrees are already identity-shared (the
  registry's persistent maps), but assertion records and rendered
  hiccup synthesized fresh per call are equality-shared, not
  identity-shared. Equality is what makes the cross-record share-
  pooling actually fire on the wire boundary.

  ## Wire shape

  A deduped payload is wrapped in a top-level marker:
  `{:rf.mcp/dedup-table <cache-map>}`. Agents reconstruct by calling
  `de-dupe.core/expand` on the cache-map value.

  Cross-MCP key by construction — sourced from
  `re-frame.mcp-base.vocab/dedup-table-key`. An agent that learned the
  slot on pair-mcp sees the byte-identical slot here.

  ## Opt-out

  `:dedup` MCP arg (boolean). Default `true`. `false` skips dedup
  entirely. The arg is parsed by the shared `parse-boolean` table-
  driven parser so string-form booleans work alongside
  the JSON `true` / `false`.

  ## Idempotence on no-dedup-opportunity

  A payload with no repeated subtrees deduplicates to a one-entry
  cache (the wire shape is very slightly larger than the input). The
  encoder skips wrapping in that case via an `empty-payload?` short-
  circuit — matching pair-mcp's behaviour byte-for-byte.

  ## Where the dedup encode step lives

  `empty-payload?` and `dedup-value` are byte-identical to pair-mcp's,
  so they live in `re-frame.mcp-base.dedup` and are delegated here.
  Only the INVERSE (`dedup-expand`) is local — see its docstring for
  why story-mcp keeps its inverse in production while pair-mcp keeps
  its inverse in test-utils."
  (:require [de-dupe.core :as dd]
            [re-frame.mcp-base.dedup :as base-dedup]
            [re-frame.mcp-base.vocab :as base-vocab]))

(defn empty-payload?
  "Delegates to `re-frame.mcp-base.dedup/empty-payload?`."
  [v]
  (base-dedup/empty-payload? v))

(defn dedup-value
  "Delegates to `re-frame.mcp-base.dedup/dedup-value`. Uses
  `de-dupe.core/de-dupe-eq` (equality-based) — see the namespace
  docstring for the identity-vs-equality rationale."
  [v enabled?]
  (base-dedup/dedup-value v enabled?))

(defn dedup-expand
  "Reverse `dedup-value`. Given a value possibly wrapped in the
  `:rf.mcp/dedup-table` marker, reconstruct the original structure
  via `de-dupe.core/expand`. Idempotent on already-expanded values
  (returns the input unchanged when the wrapper isn't present).

  ## Why this lives in production (unlike pair-mcp's)

  Pair-mcp's `dedup-expand` lives in `test-utils.cljs` because the
  MCP server itself never inverts the transform — only tests do
  (agent hosts call `de-dupe.core/expand` directly on the wire
  payload).

  Story-mcp's situation is identical, BUT the test corpus is JVM-side
  (`tools/story-mcp/test/.../*.clj`) and adding a sibling `test-utils`
  ns just for one helper is more ceremony than the helper costs in
  production. The fn is harmlessly available at runtime; the agent
  host still uses `de-dupe.core/expand` directly per the wire
  contract."
  [v]
  (if (and (map? v) (contains? v base-vocab/dedup-table-key))
    (dd/expand (get v base-vocab/dedup-table-key))
    v))
