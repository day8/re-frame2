# `diff-encode` — path-keyed structural diff

> **Type:** Reference (`tools/mcp-base/spec/`)
> Each `:rf/epoch-record` carries near-identical `:db-before` and `:db-after` values. This namespace replaces `:db-after` with path-headed sections over a structural diff against `:db-before`, reducing repeated wire data while preserving lossless replay.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`diff-encode` owns:

- The diff transform from `:db-before` / `:db-after` pair → `:db-before` + section-encoded `:db-after`.
- The patch grammar (`[<path> :assoc <new-value>]` / `[<path> :dissoc]`), pinned as a Malli `patch-schema` / `patches-schema`.
- The decoder that reconstructs `:db-after` from `:db-before` + the per-section patch lists (flattened in section order).
- The encoder/decoder validation symmetry (both boundaries Malli-gate; soft-pass when Malli absent; `goog-define`-elidable on CLJS prod).
- The intra-record self-containedness invariant (each epoch's diff encodes against its OWN `:db-before`, not a sibling's).

The path-headed **cluster grouping** (the `:sections` projection) is owned by [`section-grouping.md`](section-grouping.md); `diff-encode` consumes it.

`diff-encode` does NOT own:

- The `:rf/epoch-record` shape itself — that is framework-owned per [`../../../spec/Tool-Pair.md`](../../../spec/Tool-Pair.md).
- The wire transport — diffed records ride on whatever the consumer's wire surface is.
- The `:rf.mcp/diff-from` marker key — that lives in [`vocab.md`](vocab.md).

## What the transform does

Replaces `:db-after` with a path-headed cluster projection of a structural diff against `:db-before`:

```clojure
{:db-before <full>
 :db-after  {:rf.mcp/diff-from :db-before
             :sections [{:section-path [:cart :items]
                         :section-kind :modified
                         :patches      [[<path> :assoc <new-value>]
                                        [<path> :dissoc]]}
                        {:section-path [:checkout :state]
                         :section-kind :modified
                         :patches      [...]}]}}
```

The flat patch list is grouped into **sections** — each headed by a `:section-path` breadcrumb + a `:section-kind` summary (`:added` / `:removed` / `:modified`) so an agent asking "what did this cascade do?" reads N scoped cluster summaries instead of a flat triple list (see [`section-grouping.md`](section-grouping.md)). Inside a section, a patch is a 2- or 3-element vector — `[path :assoc v]` for new or changed leaves, `[path :dissoc]` for keys that disappeared. The decoder flattens sections back to a patch list (`section-grouping/sections->patches`) and applies each patch in order via `assoc-in` / `update-in` / `dissoc` to reconstruct `:db-after`.

## Patch grammar

| Form | Meaning | Example |
|---|---|---|
| `[<path> :assoc <new-value>]` | Set the value at `path`. A missing/nil parent vivifies according to the next segment: a map for a non-integer key, or a vector for index `0`. A present non-associative parent or impossible vector index raises `:rf.error/bad-diff-replay`. | `[[:user :name] :assoc "alice"]` · `[[:items 0 :qty] :assoc 2]` |
| `[<path> :dissoc]` | Remove the key at `path`. A **no-op** when the key doesn't exist OR when the parent is not a map — vector-element `:dissoc` is unsupported (see vector section below). | `[[:user :temp-flag] :dissoc]` |

Patches are applied **in order** within the flattened list; later patches see the state after earlier patches. The section grouping is deterministic (sorted by `:section-path`), so concatenating every section's `:patches` reproduces a stable, replayable patch list.

The patch tuple grammar is pinned as a Malli schema (`patch-schema` / `patches-schema`) and validated at BOTH the encoder boundary (`diff-encode-db-after`) and the decoder boundary (`apply-patches`); the section grammar is pinned as `section-schema` / `sections-schema`. Validation soft-passes when Malli is not on the runtime classpath (mcp-base stays Malli-free at its own dep boundary; consumers bring Malli), and is `goog-define`-elidable on CLJS production builds via `validate-patches?`.

## Vectors: same-length structural diff, length changes whole-leaf

Maps recurse key-by-key; **same-length** vectors recurse element-by-element under numeric **index** paths. A single item update inside a large vector/list app-db value — a table row, a card, a queue/log entry — therefore yields an actionable item-level patch:

```clojure
;; {:items [{:qty 1}]} → {:items [{:qty 2}]}
[[:items 0 :qty] :assoc 2]      ; not [[:items] :assoc [{:qty 2}]]
```

This preserves the token-budget premise for the dominant in-place-update app-db shapes. Integer index keys ride through the existing `assoc-in` replay (which accepts integer vector indices) and the path-generic section-grouping unchanged — **no decoder change is required**.

A vector length change is emitted as one whole-vector replacement. Numeric `:assoc` only overwrites an existing index (or index 0 on a newly vivified vector), and `:dissoc` is a no-op against vector parents; there is no splice grammar. Same-length vectors may diff by index. Other sequential types are whole-leaf replacements.

## Why patches, not `clojure.data/diff`

`clojure.data/diff`'s parallel-vector sparse form (with `nil` placeholders meaning "common at this position") loses information once you only carry one half plus the original — you can't tell `nil` (the leaf value `nil`) apart from `nil` (the no-change sentinel). Path-keyed patches are unambiguous for any value the runtime can produce: a changed vector element rides under a numeric **index** path (`[[:items 0 :qty] :assoc 2]`), which carries the position explicitly and never relies on a positional `nil` sentinel.

Added-key detection uses key presence (`find`), never an in-band marker value. This distinguishes a missing key from a present key holding nil or any sentinel-shaped application value.

## Self-contained records

The diff is **intra-record** — each epoch's `:db-after` is encoded against the SAME record's `:db-before`. Records remain self-contained and decodable without reference to siblings. The slice can be reordered, paginated, or filtered without breaking decode.

The alternative — diffing each `:db-after` against the *previous* epoch's `:db-after` to chain the diffs — would yield smaller wire payloads at the cost of self-containedness; a single dropped or reordered record would break every subsequent decode. The intra-record choice trades a small wire-size loss for the resilience the agent gets.

## Cross-MCP vocabulary

The `:rf.mcp/diff-from` marker key lives in [`vocab.md`](vocab.md); the same shape applies wherever an MCP tool ships an epoch-shaped record. Agents pattern-match on the marker to invoke their local decoder.

`:diff-from` is the slot pointer naming the sibling base. The closed marker grammar currently permits only `:db-before`.

## Decoder algorithm

The `:db-after` marker carries `:sections`, so the decoder first flattens the per-section patch lists (in section order) via `section-grouping/sections->patches`, then replays the flattened list:

```clojure
(defn decode-diff [record]
  (let [{:keys [db-before db-after]} record]
    (if (and (map? db-after) (contains? db-after :rf.mcp/diff-from))
      (let [base     (get record (:rf.mcp/diff-from db-after))
            patches  (sections->patches (:sections db-after))]  ; flatten sections
        (reduce
          (fn [acc patch]
            (let [[path op v] patch]
              (case op
                :assoc  (if (empty? path) v (assoc-in-safe acc path v))  ; structured error on non-assoc parent
                :dissoc (dissoc-in acc path))))                          ; missing-/scalar-parent no-op
          base
          patches))
      db-after)))   ; not diff-encoded; passthrough
```

> **`:dissoc` is a no-op when the key does not exist.** Nested dissociation only acts when the parent resolves to a map. Missing, scalar, and vector parents remain unchanged rather than manufacturing branches or leaking a host exception.

> **`:assoc` raises a structured error on an impossible intermediate shape.** Missing/nil parents vivify as a map or index-0 vector according to the next path segment. A present scalar, a non-integer key into a vector, or an out-of-range vector index is a base/patch mismatch and raises `:rf.error/bad-diff-replay`. Ex-data reports path and parent type, never the parent value. This structural guard runs on both hosts independently of Malli.

The shipped decoder (`decode-db-after`) Malli-validates `:sections` at the decode boundary (`validate-sections!`, symmetric with the encoder's gate) then replays via the non-validating `apply-patches*` to avoid a redundant second Malli walk on the JVM decode hot path. Both story-mcp and re-frame2-pair-mcp consume this shape; the agent-host decoder is small.

> **`decode-db-after` enforces the closed marker-body contract before decoding.** A marker is exactly `{:rf.mcp/diff-from :db-before, :sections [...]}`. Extra keys or another source slot raise value-free `:rf.error/bad-diff-marker` data on both hosts, independent of Malli. A map without `:rf.mcp/diff-from` is a full value and passes through.

The decoder validates the raw `:sections` slot rather than defaulting nil/false to `[]`. Missing sections fail the marker-body gate; invalid present sections fail section validation; explicit `[]` is the valid no-change diff.

> **Note** root-path patches: an `[[] :assoc <full>]` patch replaces `base` outright (whole-DB replacement, e.g. `replace-app-db!`); an `[[] :dissoc]` is a no-op by convention.

## Cross-platform

Pure-data tree walk over the path-keyed grammar. Loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp). No transport, no runtime, no framework dep beyond `org.clojure/clojure` for `assoc-in` / `update-in` / `dissoc`.

## Conformance posture

The conformance harness at `tools/mcp-conformance/` drives epoch-shaped tool responses through the decoder and asserts `:db-after-decoded` equals the expected post-event app-db value. Cross-server: the same diff produced by story-mcp must decode to the same value when re-frame2-pair-mcp emits it; the conformance corpus pins the diff shape in EDN.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`section-grouping.md`](section-grouping.md) — the path-headed cluster grouping (`:sections` projection) this ns consumes.
- [`../../../spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) — the pair-tool runtime contract this ns is downstream of (the time-travel slice that produces `:rf/epoch-record`s).
- [`vocab.md`](vocab.md) — the `:rf.mcp/diff-from` marker key.
