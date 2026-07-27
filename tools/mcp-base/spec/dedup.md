# `dedup` — the structural-dedup codec and the wire-boundary encode step

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP structural dedup outright: the equality-based codec (`de-dupe-eq` / `expand`), the cache-element wire shape, and the encode policy (`empty-payload?`, `no-substitutions?`, `dedup-value`) layered on top. The codec was vendored from `day8/de-dupe` v0.3.0 under rf2-2ii52 — see [§Provenance](#provenance-vendored-from-day8de-dupe). Diff-encode (the epoch `:db-after` transform that runs just before dedup) lives in [`diff-encode.md`](diff-encode.md); the wire-cap that runs just after lives in [`cap.md`](cap.md).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

Persistent data structures share subtrees in memory; `pr-str` flattens the sharing, so a payload with N references to the same subtree serialises that subtree N times. The codec walks a persistent data structure, hash-identifies repeated subtrees, and rewrites the structure as a flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols; `expand` reconstructs it exactly.

Both shipped servers emit the same kinds of duplicate-rich payloads:

- **re-frame2-pair-mcp** — the `:epochs` slice (`:db-before` + a path-keyed `:db-after` diff against it) and the per-tick subscribe `:events` vector.
- **story-mcp** — `run-variant` results (`:app-db` + `:snapshot` + `:rendered-hiccup`, the same sensitive values reappearing across three derived trees), assertion vectors, recorder replay tuples.

Both servers require `re-frame.mcp-base.dedup` directly. The shared `.cljc` transform and marker shape are exercised on JVM and CLJS by `re-frame.mcp-base.dedup-test`.

## Provenance — vendored from `day8/de-dupe`

The codec arrived here as an external runtime dependency, `day8/de-dupe {:git/url … :git/tag "v0.3.0"}`, and was absorbed under rf2-2ii52. The reason was packaging, not preference:

- `clein pom` can only express an `:mvn/version` coordinate. Handed a `:git/url` it prints `Skipping coordinate: …` and generates a pom **without** it — the same silent skip it has for `:local/root`, which the `:local/root → :mvn/version` rewrite exists to repair.
- There is no equivalent repair for a git coordinate. `day8/de-dupe` is not on Clojars and cannot be put there under that group: Clojars requires NEW projects to use a *verified* reverse-domain group, and a non-reverse-domain group cannot be verified. Publishing would have meant a new coordinate, a new tag, group verification, and a permanent standalone release surface — for a 271-line single-namespace library with one production call site in this repo.
- So `day8/re-frame2-mcp-base` and `day8/re-frame2-story-mcp` had **no publish path at all** while the dependency stood: any jar cut from them would have shipped a pom missing a runtime dependency, and Clojars has no yank.

Absorbing it makes the pom complete by construction — mcp-base's only remaining runtime dependency is Clojure itself.

The upstream MIT notice, and the list of changes made while absorbing, sit in `dedup.cljc` immediately above the vendored section, per the licence's terms. The changes are summarised here because two of them are contract-visible:

- **The compression-id counter is now call-local.** Upstream it was a namespace-global atom that each call `reset!` to 1, so two concurrent JVM encodes could interleave one call's reset with another's allocation and reuse a `cache-N` id inside one cache. `de-dupe-eq` now threads a call-local `volatile!`, which makes cache-id allocation a pure function of the input — pinned by `cache-ids-are-allocated-per-call-not-globally` and, on the JVM, `concurrent-encodes-do-not-corrupt-each-other`.
- **The identity-based encoder is gone.** Nothing on the wire boundary is identity-shared (see [§Why equality, not identity](#why-equality-not-identity)), so the `hash-fn` / `equivalent?` parameters that existed only to switch between the two variants collapsed to `hash` / `=`.

Unreached upstream surface (`map-from-seq`, `contains-compressed-elements?`, `partition-decompressed-elements`, `contains-only-keys?`) was dropped rather than carried, and everything that is not on the public surface below is `^:private`.

## Scope

`dedup` owns:

- `cache-element-ns` / `make-cache-element` — the cache-element wire shape.
- `de-dupe-eq` — the equality-based compression walk, producing a raw cache map.
- `expand` — its exact inverse, over a raw cache map.
- `empty-payload?` — the no-win short-circuit predicate (nil / empty / scalar, checked BEFORE `de-dupe-eq`).
- `no-substitutions?` — the post-`de-dupe-eq` one-entry-root-only-cache detector (a non-empty collection with no repeated subtrees).
- `dedup-value` — the encode + cross-MCP wrap.

`dedup` does NOT own:

- **The wrapper-aware test helper (`dedup-expand`).** `expand` takes a raw cache map, which is what an agent-side consumer holds after reading the `:rf.mcp/dedup-table` slot. The wrapper-unwrapping, idempotent-on-already-expanded convenience is a *test* affordance — neither MCP server inverts the transform at runtime — so its placement is a consumer decision (see [§The wrapper-aware helper stays consumer-side](#the-wrapper-aware-helper-stays-consumer-side)).
- **The `:rf.mcp/dedup-table` marker key** — pinned in [`vocab.md`](vocab.md) (`dedup-table-key`).
- **The diff-encode step that precedes it** — [`diff-encode.md`](diff-encode.md).
- **The wire-cap that follows it** — [`cap.md`](cap.md). Ordering matters: dedup shrinks first so the cap-honest size is post-dedup.

## Surface

### `cache-element-ns` → string, `make-cache-element id` → symbol

```clojure
(def cache-element-ns "de-dupe.cache")

(defn make-cache-element [id]
  (symbol cache-element-ns (str "cache-" id)))
```

Slot `0` is always the root. The namespace stayed `de-dupe.cache` through the absorb because it is a **wire** constant, not an implementation detail: the Node conformance decoder pins `de-dupe.cache/cache-0` (`tools/mcp-conformance/lib/dedup-envelope.cjs`), the wire-vocab `DedupTable` schema rejects a cache without that root, and Spec 009 / Tool-Pair document it. Renaming it would be a wire break bought for nothing; `cache-keys-are-de-dupe-cache-namespaced-symbols` makes an attempt a red build.

### `de-dupe-eq form` → cache map

Compresses `form` into a flat cache map. Slot `cache-0` holds the root; every further slot is a subtree that occurred more than once, replaced at each occurrence by its cache-element symbol. Cache-id allocation is call-local, so the same input always yields the same slot ids.

### `expand cache` → value

The exact inverse. This is the call an agent-side Clojure consumer makes on the value it reads out of `:rf.mcp/dedup-table`.

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
       (contains? cache (make-cache-element 0))))
```

True when a `de-dupe-eq` cache contains only its `cache-0` root entry and therefore made no substitutions. Wrapping that cache would only grow the payload, so `dedup-value` returns the original collection.

### `dedup-value v enabled?` → value

```clojure
(defn dedup-value [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (de-dupe-eq v)]
      (if (no-substitutions? cache)
        v
        {vocab/dedup-table-key cache}))))
```

Applies structural dedup to `v` and wraps the result in the cross-MCP marker `{:rf.mcp/dedup-table <cache-map>}`. Returns `v` unchanged when `enabled?` is false, when `empty-payload?` short-circuits, or when `de-dupe-eq` yielded a one-entry root-only cache (`no-substitutions?` — a non-empty collection with no repeated subtrees). Only a cache with an actual `cache-N` substitution is wrapped.

#### Why equality, not identity

Values reaching the wire boundary are equality-shared, not identity-shared: re-frame2-pair-mcp reconstructs CLJS values from EDN over bencode (no identity sharing survives the transport), and story-mcp synthesises assertion records and rendered hiccup fresh per call. Equality is what makes the cross-record share-pooling actually fire on the wire boundary.

## The wrapper-aware helper stays consumer-side

`dedup-expand` — unwrap the `:rf.mcp/dedup-table` marker, `expand` the cache, pass anything else through — is not a production base API. Neither MCP server calls it at runtime; consumer tests keep small helpers in their test-support namespaces:

- **re-frame2-pair-mcp** keeps it in `re-frame2-pair-mcp.test-utils`.
- **story-mcp** keeps it in `re-frame.story-mcp.test-support`.

Keeping it test-side means no production consumer namespace re-exports a test-only base surface.

## Wire shape

A deduped payload is wrapped in a top-level marker `{:rf.mcp/dedup-table <cache-map>}`, sourced from `vocab/dedup-table-key` so both servers use the same slot key — an agent that learned the slot on one server recognises it on the other. Agents reconstruct by calling `re-frame.mcp-base.dedup/expand` on the cache-map value.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`diff-encode.md`](diff-encode.md) — the epoch `:db-after` transform that runs immediately before dedup in re-frame2-pair-mcp's pipeline.
- [`cap.md`](cap.md) — the wire-cap that runs immediately after dedup (dedup shrinks first).
- [`vocab.md`](vocab.md) — the `:rf.mcp/dedup-table` marker key.
