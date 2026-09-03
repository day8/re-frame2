# `dedup` — the structural-dedup codec and the wire-boundary encode step

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP structural dedup outright: the equality-based codec (`de-dupe-eq` / `expand`), the cache-element wire shape, and the encode policy (`empty-payload?`, `no-substitutions?`, `dedup-value`) layered on top. The codec was vendored from `day8/de-dupe` v0.3.0 under rf2-2ii52 — see [§Provenance](#provenance--vendored-from-day8de-dupe). Diff-encode (the epoch `:db-after` transform that runs just before dedup) lives in [`diff-encode.md`](diff-encode.md); the wire-cap that runs just after lives in [`cap.md`](cap.md).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

Persistent data structures share subtrees in memory; `pr-str` flattens the sharing, so a payload with N references to the same subtree serialises that subtree N times. The codec walks a persistent data structure, hash-identifies repeated subtrees, and rewrites the structure as a flat cache map keyed by `de-dupe.cache/cache-N` namespaced symbols; `expand` reconstructs it exactly.

Both shipped servers emit the same kinds of duplicate-rich payloads:

- **re-frame2-pair-mcp** — the `:epochs` slice (`:db-before` + a path-keyed `:db-after` diff against it) and the per-tick subscribe `:events` vector.
- **story-mcp** — `run-variant` results (`:app-db` + `:snapshot` + the evidence slots, the same sensitive values reappearing across several derived trees) and assertion vectors.

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

- `cache-element-ns` / `make-cache-element` — the cache-element wire shape, and with it the reference grammar and collision rule below.
- `de-dupe-eq` — the equality-based compression walk, producing a raw cache map (escaping colliding payload literals first).
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

### Reference grammar and the collision rule

Occupying the namespace is not the same as owning it. A re-frame app may legitimately hold `de-dupe.cache/whatever` in app-db, a story may emit one in rendered or diagnostic data, and — once JSON has erased the distinctions between symbols, keywords and strings — an ordinary payload value of that spelling is indistinguishable from a serialised reference symbol. A decoder that classified a value by its namespace alone therefore turned payload data into `nil`, into another slot's subtree, or into a thrown missing-entry error (rf2-kjv05). References are spelled, not merely namespaced.

**In value position**, a token `de-dupe.cache/<name>` — a symbol, keyword or string on the Clojure side, one JSON string once the wire has flattened them — is exactly one of:

| `<name>` | reads as |
| --- | --- |
| `cache-<digits>` | a **reference** to that cache slot |
| `!<rest>` | an **escaped literal** of `de-dupe.cache/<rest>` — strip one `!` |
| anything else | ordinary **payload data**, passed through verbatim |

**The collision rule.** The encoder escapes — one leading `!` — every payload token that would otherwise read as one of the first two forms, so its own output is unambiguous *by construction*: no ambiguous spelling can reach the wire, whatever the payload contains. Escaping is reversible under repetition, because the escaped form is itself escapable: a payload token spelled `de-dupe.cache/!cache-1` rides out as `de-dupe.cache/!!cache-1` and sheds exactly one marker on decode. The escape is type-preserving (a string comes back a string, a keyword a keyword), which is what keeps the JSON projection exact as well as the Clojure round-trip.

**Which types are escaped, and why exactly those three.** Symbols, **keywords** and strings — the set a JSON encoder flattens onto a bare namespaced string, and therefore the set that can collide once the value leaves Clojure. Cheshire renders `de-dupe.cache/cache-1`, `:de-dupe.cache/cache-1` and `"de-dupe.cache/cache-1"` identically, and identically to a real reference. `token-name` tests `ident?` and `string?` for that reason and nothing broader: no other Clojure scalar can produce a slash-bearing name in this namespace.

Escaping a proper subset is the failure mode worth naming, because it is invisible from the host: `cache-element?` classifies only symbols, so an unescaped payload keyword is never aliased *on the JVM or in CLJS* and every round-trip assertion passes — while the JSON projection is silently corrupt, in value and map-key position alike. rf2-kjv05's first cut escaped symbols and strings and left exactly that gap; a keyword is the ordinary spelling of both a value and a map key in re-frame app-db data, so it was the likeliest of the three to be hit. The host-side pins are therefore on the **wire spelling**, not only on the round-trip.

**What the grammar does not restore is the Clojure type across JSON.** A payload symbol, keyword and string in this namespace all reach a Node consumer as the same string — as they would anywhere else in the payload; JSON has no symbol or keyword. The guarantee is that the JSON *projection* passes through the codec unchanged (encode then decode is the identity on it), alongside the exact Clojure round-trip on the host.

**Cache keys are untouched.** Slot keys are the allocator's own `cache-N` symbols and never carry an escape; only values are subject to the grammar. Id allocation is likewise untouched — the encoder does not skip ids to dodge a colliding payload literal, because escaping has already removed the collision.

**A missing reference stays a loud failure.** `cache-<digits>` is a reference whether or not the table holds that slot, so a truncated or hand-mangled table is rejected rather than silently re-read as data — pinned in the Node decoder by its own positive control, alongside the tests that prove ordinary namespace-occupying values decode verbatim.

Both decoders implement this one grammar: `re-frame.mcp-base.dedup` (`cache-element?` / `escape-token` / `unescape-token`) and its Node mirror `tools/mcp-conformance/lib/dedup-envelope.cjs` (`CACHE_REF_RE` / `unescapeToken`). No prefix-only decoder remains. The Node half is deliberately **type-blind** — a JSON string is all it ever sees — which is why widening the encoder's escape set to keywords changed no decoder logic: the same three rules already decoded the wider output. The two halves stay in step through the wire *spellings*, pinned host-side by `colliding-payload-tokens-are-escaped-on-the-wire`, `colliding-payload-keywords-are-escaped-on-the-wire` and `colliding-payload-keywords-are-escaped-in-map-KEY-position-too` in `re-frame.mcp-base.dedup-test`, and consumed as fixtures by the Node counterparts in `tools/mcp-conformance/test/dedup-envelope.test.cjs`. Round-trip-as-data is pinned per type by `payload-symbols-…`, `payload-keywords-…`, `payload-strings-…` and `all-three-json-flattened-types-collide-and-all-three-are-escaped`.

No committed gate runs the whole `Clojure → Cheshire → Node` seam in one process — mcp-base's suites have no JSON encoder and no access to the Node decoder, and mcp-conformance's Node unit cluster deliberately boots no JVM. The seam is covered by the mirrored spellings above plus an out-of-band boundary probe run when the grammar changes.

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

A deduped payload is wrapped in a top-level marker `{:rf.mcp/dedup-table <cache-map>}`, sourced from `vocab/dedup-table-key` so both servers use the same slot key — an agent that learned the slot on one server recognises it on the other. Agents reconstruct by calling `re-frame.mcp-base.dedup/expand` on the cache-map value. What counts as a reference *inside* that cache map is [§Reference grammar and the collision rule](#reference-grammar-and-the-collision-rule).

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`diff-encode.md`](diff-encode.md) — the epoch `:db-after` transform that runs immediately before dedup in re-frame2-pair-mcp's pipeline.
- [`cap.md`](cap.md) — the wire-cap that runs immediately after dedup (dedup shrinks first).
- [`vocab.md`](vocab.md) — the `:rf.mcp/dedup-table` marker key.
