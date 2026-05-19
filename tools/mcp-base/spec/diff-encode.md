# `diff-encode` — path-keyed structural diff (rf2-1wdzp)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Each `:rf/epoch-record` carries `:db-before` and `:db-after` — near-identical full app-db snapshots. `pr-str` doesn't preserve structural sharing, so on the wire the pair is roughly 2× app-db per epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db. This ns replaces `:db-after` with a path-keyed structural diff against `:db-before` so the wire payload approaches the structural-sharing cost rather than the deep-copy cost.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md).

## Scope

`diff-encode` owns:

- The diff transform from `:db-before` / `:db-after` pair → `:db-before` + patch-encoded `:db-after`.
- The patch grammar (`[<path> :assoc <new-value>]` / `[<path> :dissoc]`).
- The decoder that reconstructs `:db-after` from `:db-before` + patches.
- The intra-record self-containedness invariant (each epoch's diff encodes against its OWN `:db-before`, not a sibling's).

`diff-encode` does NOT own:

- The `:rf/epoch-record` shape itself — that's framework-side per [`../../../spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) (the time-travel slice) and the `day8/re-frame2-epoch` artefact (rf2-lt4e).
- The wire transport — diffed records ride on whatever the consumer's wire surface is.
- The `:rf.mcp/diff-from` marker key — that lives in [`vocab.md`](vocab.md).

## What the transform does

Replaces `:db-after` with a path-keyed structural diff against `:db-before`:

```clojure
{:db-before <full>
 :db-after  {:rf.mcp/diff-from :db-before
             :patches [[<path> :assoc <new-value>]
                       [<path> :dissoc]]}}
```

A patch is a 2- or 3-element vector — `[path :assoc v]` for new or changed leaves, `[path :dissoc]` for keys that disappeared. The decoder applies each patch in order via `assoc-in` / `update-in` / `dissoc-in` to reconstruct `:db-after`.

## Patch grammar

| Form | Meaning | Example |
|---|---|---|
| `[<path> :assoc <new-value>]` | Set the value at `path` to `new-value` (creates the slot if it didn't exist). | `[[:user :name] :assoc "alice"]` |
| `[<path> :dissoc]` | Remove the key at `path` (no-op if it doesn't exist). | `[[:user :temp-flag] :dissoc]` |

Patches are applied **in order**; later patches see the state after earlier patches. The order is preserved on the wire; reorderings break decode.

## Why patches, not `clojure.data/diff`

`clojure.data/diff`'s parallel-vector sparse form (with `nil` placeholders meaning "common at this position") loses information once you only carry one half plus the original — you can't tell `nil` (the leaf value `nil`) apart from `nil` (the no-change sentinel). Path-keyed patches are unambiguous for any value the runtime can produce.

## Self-contained records

The diff is **intra-record** — each epoch's `:db-after` is encoded against the SAME record's `:db-before`. Records remain self-contained and decodable without reference to siblings. The slice can be reordered, paginated, or filtered without breaking decode.

The alternative — diffing each `:db-after` against the *previous* epoch's `:db-after` to chain the diffs — would yield smaller wire payloads at the cost of self-containedness; a single dropped or reordered record would break every subsequent decode. The intra-record choice trades a small wire-size loss for the resilience the agent gets.

## Cross-MCP vocabulary

The `:rf.mcp/diff-from` marker key lives in [`vocab.md`](vocab.md); the same shape applies wherever an MCP tool ships an epoch-shaped record. Agents pattern-match on the marker to invoke their local decoder.

`:diff-from` is the slot pointer — a keyword naming which sibling slot in the same record holds the base for the diff. Currently always `:db-before`; the indirection is preserved for forward compatibility (e.g. a future `:db-mid-microstep` slot).

## Decoder algorithm

```clojure
(defn decode-diff [record]
  (let [{:keys [db-before db-after]} record]
    (if (and (map? db-after) (contains? db-after :rf.mcp/diff-from))
      (let [base    (get record (:rf.mcp/diff-from db-after))
            patches (:patches db-after)]
        (reduce
          (fn [acc patch]
            (let [[path op v] patch]
              (case op
                :assoc  (assoc-in acc path v)
                :dissoc (let [parent (drop-last path)
                              k      (last path)]
                          (update-in acc parent dissoc k)))))
          base
          patches))
      db-after)))   ; not diff-encoded; passthrough
```

Both story-mcp and re-frame2-pair-mcp implement this shape; the consumer-side decoder is ~20 lines.

## Cross-platform

Pure-data tree walk over the path-keyed grammar. Loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp). No transport, no runtime, no framework dep beyond `org.clojure/clojure` for `assoc-in` / `update-in` / `dissoc`.

## Conformance posture

The conformance harness at `tools/mcp-conformance/` drives epoch-shaped tool responses through the decoder and asserts `:db-after-decoded` equals the expected post-event app-db value. Cross-server: the same diff produced by story-mcp must decode to the same value when re-frame2-pair-mcp emits it; the conformance corpus pins the diff shape in EDN.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) — the pair-tool runtime contract this ns is downstream of (the time-travel slice that produces `:rf/epoch-record`s).
- [`vocab.md`](vocab.md) — the `:rf.mcp/diff-from` marker key.
- rf2-1wdzp — the bead that landed this encoding.
