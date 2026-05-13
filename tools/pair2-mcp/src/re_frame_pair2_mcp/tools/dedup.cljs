(ns re-frame-pair2-mcp.tools.dedup
  "Diff-encoded epoch slice (rf2-1wdzp) + structural dedup at the wire
  boundary (rf2-obpa9).

  ## Diff-encoded epoch slice (rf2-1wdzp)

  Each `:rf/epoch-record` carries `:db-before` and `:db-after` —
  near-identical full app-db snapshots. `pr-str` doesn't preserve
  structural sharing, so on the wire the pair is roughly 2× app-db per
  epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db. The
  transform replaces `:db-after` with a path-keyed structural diff
  against `:db-before`; records remain self-contained and decodable
  without reference to siblings. Opt-back-in to the full pair via
  `:full` mode. Default is `:diff`.

  ## Structural dedup (rf2-obpa9)

  Persistent data structures share subtrees in memory; `pr-str` flattens
  the sharing. `day8/de-dupe` walks a persistent data structure,
  hash-identifies repeated subtrees, and rewrites the structure as a
  flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols.
  The library guarantees round-trip exactness via the companion `expand`
  function; the agent host can decode locally.

  ### When dedup runs

  - **Inside the epoch encoder**: the `:epochs` slice on `snapshot`,
    `trace-window`, and `watch-epochs` is wrapped after diff-encoding
    and before the wire-cap check.
  - **Inside subscribe streaming**: each emitted progress frame's
    `:events` vector is deduped per-tick.

  ### Why `de-dupe-eq` (equality), not `de-dupe` (identity)

  Data arrives at the MCP server via bencode over nREPL; CLJS values
  reconstructed from EDN don't share identity with values the runtime
  emitted earlier. Equality is what makes the cross-record share-pooling
  actually fire on the wire boundary.

  ### Wire shape

  A deduped payload is wrapped in a top-level marker:
  `{:rf.mcp/dedup-table <cache-map>}`. Agents reconstruct by calling
  `de-dupe.core/expand` on the cache-map value.

  ### Opt-out

  `dedup` MCP arg (boolean). Default `true`. `false` skips dedup
  entirely.

  ### Idempotence on no-dedup-opportunity

  A payload with no repeated subtrees deduplicates to a one-entry cache
  (the wire shape is very slightly larger than the input). The encoder
  skips wrapping in that case via an `empty-payload?` short-circuit."
  (:require [de-dupe.core :as dedup]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.diff-encode :as base-diff]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Diff-encoded epoch slice.
;; ---------------------------------------------------------------------------

(defn diff-encode-db-after
  "Delegates to `re-frame.mcp-base.diff-encode/diff-encode-db-after`."
  [epoch]
  (base-diff/diff-encode-db-after epoch))

(defn diff-encode-epochs
  "Delegates to `re-frame.mcp-base.diff-encode/diff-encode-epochs`."
  [epochs mode]
  (base-diff/diff-encode-epochs epochs mode))

(defn parse-epochs-mode
  "Normalise the `epochs-mode` MCP arg. Delegates to
  `re-frame.mcp-base.args/parse-mode` (rf2-vw4sq)."
  [raw]
  (base-args/parse-mode raw :diff #{:diff :full}))

;; ---------------------------------------------------------------------------
;; Structural dedup.
;; ---------------------------------------------------------------------------

(defn parse-dedup-arg
  "Normalise the `dedup` MCP arg into a boolean. Default `true` —
  the budget-sensitive default fires dedup. Delegates to
  `re-frame.mcp-base.args/parse-boolean` (rf2-vw4sq)."
  [raw]
  (base-args/parse-boolean raw true))

(defn empty-payload?
  "True for values where dedup yields no win — nil, empty collections,
  scalars. Skipping the wrap avoids the trivial cache-of-one shape
  bloating the wire for empty / single-record responses."
  [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))

(defn dedup-value
  "Apply structural dedup to `v` and wrap the result in the cross-MCP
  marker (`base-vocab/dedup-table-key` — rf2-vw4sq). Returns `v`
  unchanged when `enabled?` is false or when `v` is empty / scalar
  (no dedup opportunity). Uses `de-dupe-eq` (equality-based) — see
  the section header for the identity-vs-equality rationale."
  [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (dedup/de-dupe-eq v)]
      {base-vocab/dedup-table-key cache})))

(defn dedup-expand
  "Reverse `dedup-value`. Given a value possibly wrapped in the
  `:rf.mcp/dedup-table` marker, reconstruct the original structure
  via `de-dupe.core/expand`. Idempotent on already-expanded values
  (the marker check returns the input unchanged when the wrapper
  isn't present). Provided for round-trip parity and for the unit
  tests; the agent host can call the same shape locally."
  [v]
  (if (and (map? v) (contains? v base-vocab/dedup-table-key))
    (dedup/expand (get v base-vocab/dedup-table-key))
    v))
