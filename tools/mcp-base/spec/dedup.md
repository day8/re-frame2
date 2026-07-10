# `dedup` — structural-dedup encode step at the wire boundary

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP FORWARD (encode) half of wire-boundary structural dedup — `empty-payload?` and `dedup-value` over `day8/de-dupe`'s equality walk. The INVERSE (`dedup-expand`) is test-only and deliberately stays consumer-side. Diff-encode (the epoch `:db-after` transform that runs just before dedup) lives in [`diff-encode.md`](diff-encode.md); the wire-cap that runs just after lives in [`cap.md`](cap.md).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

Persistent data structures share subtrees in memory; `pr-str` flattens the sharing, so a payload with N references to the same subtree serialises that subtree N times. `day8/de-dupe` walks a persistent data structure, hash-identifies repeated subtrees, and rewrites the structure as a flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols; the companion `expand` reconstructs it exactly on the agent host.

Both shipped servers emit the same kinds of duplicate-rich payloads:

- **re-frame2-pair-mcp** — the `:epochs` slice (`:db-before` + a path-keyed `:db-after` diff against it) and the per-tick subscribe `:events` vector.
- **story-mcp** — `run-variant` results (`:app-db` + `:snapshot` + `:rendered-hiccup`, the same sensitive values reappearing across three derived trees), assertion vectors, recorder replay tuples.

Both servers require `re-frame.mcp-base.dedup` directly. The shared `.cljc` transform and marker shape are exercised on JVM and CLJS by `re-frame.mcp-base.dedup-test`.

## Scope

`dedup` owns:

- `empty-payload?` — the no-win short-circuit predicate (nil / empty / scalar, checked BEFORE `de-dupe-eq`).
- `no-substitutions?` — the post-`de-dupe-eq` one-entry-root-only-cache detector (a non-empty collection with no repeated subtrees).
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

True for values where dedup yields no win *without even running `de-dupe-eq`* — `nil`, empty collections, scalars. These short-circuit up front. (The related but distinct one-entry-root-only-cache case — a NON-empty collection that runs `de-dupe-eq` and turns out to have no repeated subtrees — is caught AFTER the walk by `no-substitutions?`, below.)

### `no-substitutions? cache` → boolean

```clojure
(defn no-substitutions? [cache]
  (and (map? cache)
       (= 1 (count cache))
       (contains? cache (de-dupe.core/make-cache-element 0))))
```

True when a `de-dupe-eq` cache contains only its `cache-0` root entry and therefore made no substitutions. Wrapping that cache would only grow the payload, so `dedup-value` returns the original collection. The explicit root-key check verifies the shape expected from the pinned dependency.

### `dedup-value v enabled?` → value

```clojure
(defn dedup-value [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (de-dupe.core/de-dupe-eq v)]
      (if (no-substitutions? cache)
        v
        {vocab/dedup-table-key cache}))))
```

Applies structural dedup to `v` and wraps the result in the cross-MCP marker `{:rf.mcp/dedup-table <cache-map>}`. Returns `v` unchanged when `enabled?` is false, when `empty-payload?` short-circuits, or when `de-dupe-eq` yielded a one-entry root-only cache (`no-substitutions?` — a non-empty collection with no repeated subtrees). Only a cache with an actual `cache-N` substitution is wrapped.

#### Why `de-dupe-eq` (equality), not `de-dupe` (identity)

Values reaching the wire boundary are equality-shared, not identity-shared: re-frame2-pair-mcp reconstructs CLJS values from EDN over bencode (no identity sharing survives the transport), and story-mcp synthesises assertion records and rendered hiccup fresh per call. Equality is what makes the cross-record share-pooling actually fire on the wire boundary.

## The inverse stays consumer-side

`dedup-expand` (the inverse of `dedup-value`) is not a production base API. Neither MCP server calls it at runtime; consumer tests keep small expansion helpers in their test-support namespaces:

- **re-frame2-pair-mcp** keeps it in `re-frame2-pair-mcp.test-utils`.
- **story-mcp** keeps it in `re-frame.story-mcp.test-support`.

Keeping the inverse test-side means no production consumer namespace re-exports a test-only base surface.

## Wire shape

A deduped payload is wrapped in a top-level marker `{:rf.mcp/dedup-table <cache-map>}`, sourced from `vocab/dedup-table-key` so both servers use the same slot key — an agent that learned the slot on one server recognises it on the other. Agents reconstruct by calling `de-dupe.core/expand` on the cache-map value.

## The `day8/de-dupe` dep

`dedup.cljc` is the one base namespace with an external runtime dep: `day8/de-dupe`, pinned to git-tag `v0.3.0`. It is a framework-agnostic `.cljc` persistent-data-structure walker, so it preserves the base's zero-implementation-dependency and no-transport-dependency rules.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`diff-encode.md`](diff-encode.md) — the epoch `:db-after` transform that runs immediately before dedup in re-frame2-pair-mcp's pipeline.
- [`cap.md`](cap.md) — the wire-cap that runs immediately after dedup (dedup shrinks first).
- [`vocab.md`](vocab.md) — the `:rf.mcp/dedup-table` marker key.
