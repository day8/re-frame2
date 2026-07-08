(ns re-frame.mcp-base.dedup
  "Structural dedup at the wire boundary — the cross-MCP forward
  (encode) direction, shared in the base by both servers.

  ## Why dedup at the wire boundary

  Persistent data structures share subtrees in memory; `pr-str` flattens
  the sharing. `day8/de-dupe` walks a persistent data structure,
  hash-identifies repeated subtrees, and rewrites the structure as a
  flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols.
  The library guarantees round-trip exactness via the companion `expand`
  function; the agent host can decode locally.

  Both MCP servers ship the same kinds of duplicate-rich payloads:
  re-frame2-pair-mcp's `:epochs` slice (`:db-before` + path-keyed
  `:db-after` diff), subscribe progress `:events`; story-mcp's
  `run-variant` results (`:app-db` + `:snapshot` + `:rendered-hiccup`),
  assertion vectors, recorder replay tuples. The encode step is
  byte-identical across both servers, so it lives here once.

  ## Why `de-dupe-eq` (equality), not `de-dupe` (identity)

  Values reaching the wire boundary are equality-shared, not identity-
  shared: re-frame2-pair-mcp reconstructs CLJS values from EDN over
  bencode (no identity sharing survives the transport); story-mcp
  synthesises assertion records and rendered hiccup fresh per call.
  Equality is what makes the cross-record share-pooling actually fire on
  the wire boundary.

  ## Wire shape

  A deduped payload is wrapped in a top-level marker:
  `{:rf.mcp/dedup-table <cache-map>}` (`vocab/dedup-table-key`). Agents
  reconstruct by calling `de-dupe.core/expand` on the cache-map value —
  a cross-MCP key by construction, so an agent that learned the slot on
  one server sees the byte-identical slot on the other.

  ## Idempotence on no-dedup-opportunity

  A payload with no repeated subtrees deduplicates to a one-entry cache
  (the wire shape is very slightly larger than the input). The encoder
  skips wrapping in two cases so no-repeat payloads stay raw:

  - Empty / scalar values short-circuit BEFORE `de-dupe-eq` via
    `empty-payload?`.
  - A NON-EMPTY collection with no repeated subtrees runs `de-dupe-eq`
    but produces a one-entry root-only cache
    (`{de-dupe.cache/cache-0 <original>}`, no `cache-N` substitutions);
    `no-substitutions?` detects that and returns the original value
    unchanged. Without this, ordinary no-repeat payloads would grow by
    the wrapper + `cache-0` slot on every response.

  ## What does NOT live here — the inverse (`dedup-expand`)

  The forward direction is byte-identical, so it is shared here. The
  INVERSE (`dedup-expand`) is NOT — neither MCP server calls it at
  runtime (the
  wire contract is that the agent host calls `de-dupe.core/expand`
  directly), so it is test-only, and each consumer's placement reflects
  its own test-corpus topology rather than a shared shape:

  - re-frame2-pair-mcp keeps it in `re-frame2-pair-mcp.test-utils`
    (its CLJS test corpus already has a test-utils ns; \"test-only\" is
    signalled by location).
  - story-mcp keeps it in its production `dedup.cljc` (its JVM test
    corpus has no test-utils ns; a sibling ns for one helper is more
    ceremony than the helper costs — it is harmlessly available at
    runtime and never invoked by the server).

  Sharing only the forward direction keeps each inverse's placement
  decision local and intact."
  (:require [de-dupe.core :as dd]
            [re-frame.mcp-base.vocab :as vocab]))

(defn empty-payload?
  "True for values where dedup yields no win — nil, empty collections,
  scalars. Skipping the wrap avoids the trivial cache-of-one shape
  bloating the wire for empty / single-record responses."
  [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))

(def ^:private root-cache-key
  "The slot `de-dupe-eq` always emits for the whole structure
  (`de-dupe.cache/cache-0`). Every substituted subtree adds a further
  `cache-N` entry, so a cache carrying ONLY this key made no
  substitutions."
  (dd/make-cache-element 0))

(defn no-substitutions?
  "True when a `de-dupe-eq` cache made NO substitutions — it holds
  exactly the one root entry (`cache-0`) and nothing else, because the
  payload had no repeated subtrees. A non-empty collection with no
  repeats deduplicates to exactly this one-entry root-only cache, whose
  wrapped wire shape is strictly LARGER than the raw input; `dedup-value`
  detects it and returns the original value so the documented
  no-repeat-payloads-stay-raw contract holds for ordinary (not just
  empty / scalar) payloads too.

  Checked on the cache SHAPE (single entry keyed by `cache-0`), not by
  re-walking the value: any repeated subtree would have added a second
  `cache-N` entry, so `(= 1 (count cache))` already implies root-only —
  the explicit key check guards against a future `de-dupe` change that
  keyed the root differently."
  [cache]
  (and (map? cache)
       (= 1 (count cache))
       (contains? cache root-cache-key)))

(defn dedup-value
  "Apply structural dedup to `v` and wrap the result in the cross-MCP
  marker (`vocab/dedup-table-key`). Returns `v` unchanged when
  `enabled?` is false, when `v` is empty / scalar (no dedup opportunity),
  or when the `de-dupe-eq` cache made no substitutions (a non-empty
  collection with no repeated subtrees — `no-substitutions?`). Uses
  `de-dupe.core/de-dupe-eq` (equality-based) — see the namespace
  docstring for the identity-vs-equality rationale."
  [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (dd/de-dupe-eq v)]
      (if (no-substitutions? cache)
        v
        {vocab/dedup-table-key cache}))))
