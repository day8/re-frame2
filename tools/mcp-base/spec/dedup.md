# `dedup` — structural-dedup encode step at the wire boundary (rf2-ttspi7)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP FORWARD (encode) half of wire-boundary structural dedup — `empty-payload?` and `dedup-value` over `day8/de-dupe`'s equality walk. The INVERSE (`dedup-expand`) is test-only and deliberately stays consumer-side. Diff-encode (the epoch `:db-after` transform that runs just before dedup) lives in [`diff-encode.md`](diff-encode.md); the wire-cap that runs just after lives in [`cap.md`](cap.md).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

Persistent data structures share subtrees in memory; `pr-str` flattens the sharing, so a payload with N references to the same subtree serialises that subtree N times. `day8/de-dupe` walks a persistent data structure, hash-identifies repeated subtrees, and rewrites the structure as a flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols; the companion `expand` reconstructs it exactly on the agent host.

Both shipped servers emit the same kinds of duplicate-rich payloads:

- **re-frame2-pair-mcp** — the `:epochs` slice (`:db-before` + a path-keyed `:db-after` diff against it) and the per-tick subscribe `:events` vector.
- **story-mcp** — `run-variant` results (`:app-db` + `:snapshot` + `:rendered-hiccup`, the same sensitive values reappearing across three derived trees), assertion vectors, recorder replay tuples.

The encode step (`empty-payload?` + `dedup-value`) was byte-identical across both servers. rf2-ttspi7 lifted it here so the two servers cannot drift on a dedup-algorithm change; each consumer's `dedup` ns now delegates.

## Scope

`dedup` owns:

- `empty-payload?` — the no-win short-circuit predicate.
- `dedup-value` — the equality-based encode + cross-MCP wrap.

`dedup` does NOT own:

- **The inverse (`dedup-expand`).** Neither MCP server inverts the transform at runtime — the wire contract is that the agent host calls `de-dupe.core/expand` directly on the wire payload. The inverse is therefore test-only, and its placement is a *consumer* decision (see [§The inverse stays consumer-side](#the-inverse-stays-consumer-side)).
- **The `:rf.mcp/dedup-table` marker key** — pinned in [`vocab.md`](vocab.md) (`dedup-table-key`).
- **The diff-encode step that precedes it** — [`diff-encode.md`](diff-encode.md).
- **The wire-cap that follows it** — [`cap.md`](cap.md). Ordering matters: dedup shrinks first so the cap-honest size is post-dedup.

## Surface

### `empty-payload? v` → boolean

```clojure
(defn empty-payload? [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))
```

True for values where dedup yields no win — `nil`, empty collections, scalars. A payload with no repeated subtrees deduplicates to a one-entry cache whose wire shape is *very slightly larger* than the input; the predicate lets `dedup-value` skip the wrap for those degenerate cases so empty / single-record responses don't grow.

### `dedup-value v enabled?` → value

```clojure
(defn dedup-value [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    {vocab/dedup-table-key (de-dupe.core/de-dupe-eq v)}))
```

Applies structural dedup to `v` and wraps the result in the cross-MCP marker `{:rf.mcp/dedup-table <cache-map>}`. Returns `v` unchanged when `enabled?` is false or when `empty-payload?` short-circuits.

#### Why `de-dupe-eq` (equality), not `de-dupe` (identity)

Values reaching the wire boundary are equality-shared, not identity-shared: re-frame2-pair-mcp reconstructs CLJS values from EDN over bencode (no identity sharing survives the transport), and story-mcp synthesises assertion records and rendered hiccup fresh per call. Equality is what makes the cross-record share-pooling actually fire on the wire boundary.

## The inverse stays consumer-side

`dedup-expand` (the inverse of `dedup-value`) is NOT lifted. It is never called by either MCP server at runtime, so it is test-only, and each consumer keeps it where its own test-corpus topology wants it:

- **re-frame2-pair-mcp** keeps it in `re-frame2-pair-mcp.test-utils` — its CLJS test corpus already has a `test-utils` ns, so "test-only" is signalled by location.
- **story-mcp** keeps it in its production `tools/dedup.cljc` — its JVM test corpus has no `test-utils` ns, and a sibling ns for one helper is more ceremony than the helper costs. The fn is harmlessly available at runtime and never invoked by the server.

Lifting only the forward direction keeps each inverse-placement decision local and intact. This is the deliberate RULE recorded under rf2-ttspi7, not an oversight.

## Wire shape

A deduped payload is wrapped in a top-level marker `{:rf.mcp/dedup-table <cache-map>}`, sourced from `vocab/dedup-table-key` so the slot is byte-identical across servers — an agent that learned the slot on one server recognises it on the other. Agents reconstruct by calling `de-dupe.core/expand` on the cache-map value.

## The `day8/de-dupe` dep

`dedup.cljc` is the one base namespace with an external runtime dep: `day8/de-dupe`, pinned to git-tag `v0.3.0` (the same tag both consumers previously pinned, rf2-obpa9 / rf2-90eft / rf2-nw6sj). It is framework-agnostic — a pure persistent-data-structure walker, `.cljc` both arms — so it sits cleanly inside the base's zero-impl-dep / no-consumer-transport-dep rule. Pinning it here keeps the base + both servers in lockstep.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`diff-encode.md`](diff-encode.md) — the epoch `:db-after` transform that runs immediately before dedup in re-frame2-pair-mcp's pipeline.
- [`cap.md`](cap.md) — the wire-cap that runs immediately after dedup (dedup shrinks first).
- [`vocab.md`](vocab.md) — the `:rf.mcp/dedup-table` marker key.
- rf2-obpa9 / rf2-90eft — the beads that landed dedup in re-frame2-pair-mcp and story-mcp respectively.
- rf2-ttspi7 — the bead that lifted the byte-identical encode step here.
