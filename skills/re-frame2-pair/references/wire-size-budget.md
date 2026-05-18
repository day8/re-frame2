# Wire size budget — de-dupe decoding & size-conscious tool args

re-frame2-pair-mcp tool responses are shaped to fit a tight wire-token budget
(default ~5K tokens, `:rf.mcp/overflow` marker on cap breach). Two
things to know: (1) some payloads arrive structurally **deduped** and
must be expanded host-side to be useful, and (2) the read tools take
a small family of args that trade off detail for size. Reach for them
*before* a payload overflows.

---

## de-dupe — round-tripping the wire form

**What it is.** Structural-sharing preserver from `day8/de-dupe`
(`.cljc` per rf2-nw6sj). Persistent data structures share subtrees in
memory; `pr-str` flattens them. de-dupe walks the value, hash-pools
repeated subtrees, and rewrites the structure as a flat cache map
keyed by `de-dupe.cache/cache-N` namespaced symbols. The companion
`expand` function reconstructs the original.

**How to spot it.** A deduped payload arrives wrapped in the cross-
MCP marker:

```edn
{:rf.mcp/dedup-table
 {:de-dupe.cache/cache-0 <root>
  :de-dupe.cache/cache-1 <subtree>
  :de-dupe.cache/cache-N ...}}
```

The `:rf.mcp/dedup-table` key is the marker; the inner map is the
de-dupe library's flat cache shape.

**How to expand.** One call:

```clojure
(require '[de-dupe.core :as dedup])

(dedup/expand (get payload :rf.mcp/dedup-table))
;; => the original structure with sharing restored
```

Idempotent on already-expanded values — if the marker isn't present,
return the input unchanged. The expand is local to the agent host;
no extra round-trip to the runtime.

**Where it fires.** `:epochs` slice on `snapshot`, `trace-window`,
`watch-epochs`; per-tick `:events` vector on `subscribe`. Always
opt-out via `dedup false` if your host hasn't been taught the marker.

**Empty / scalar inputs** are passed through unmodified (no marker),
so the only thing that ever needs `expand` is something that already
benefits from sharing.

---

## Size-conscious args — which knob, when

Every read-tool descriptor carries a universal `max-tokens` cap plus
a feature-specific knob set. The knobs compose; reach for the
narrowest one first.

| Arg | Tools | Default | Use when |
|---|---|---|---|
| `max-tokens` | all read tools | 5000 | Hard cap. Lower for a probe; pass 0 to disable (rare). Overflow returns `{:rf.mcp/overflow ...}`. |
| `path` | `snapshot` (`:app-db` slice), `get-path` | nil | You already know roughly where the answer lives. Beats slicing the whole frame. |
| `mode "summary"` / `mode "full"` | `snapshot` | `"summary"` | Discovery snapshot first (summary is the default — counts, top keys, ~bytes). Switch to `"full"` only after the summary tells you which slice carries the answer. |
| `modes {"app-db" "full", ...}` | `snapshot` | per-slice | Mix: e.g. live state `"full"`, history `"summary"`. |
| `include ["app-db"]` | `snapshot` | all 5 slices | Drop slices you don't need (`app-db`, `sub-cache`, `machines`, `epochs`, `traces`). |
| `epochs-mode "diff"` / `"full"` | `snapshot`, `trace-window`, `watch-epochs` | `"diff"` | Keep diff (default). Only opt back to `"full"` when you need it for **time-travel restore** — the diffed `:db-after` can't drive `restore-epoch`. |
| `dedup` | epoch-carrying tools + `subscribe` | `true` | Keep on. Pass `false` only if your host can't `expand`, or for round-trip debugging. |
| `elision` | `snapshot`, `get-path` | `true` | Keep on. Pass `false` only when you have explicit override permission and need the raw bytes of a `:large?` slot. |
| `limit` + `cursor` | `trace-window`, `watch-epochs` | 50 / nil | Paginated epoch streams. First call returns up-to-`limit` records and a `:next-cursor`; pass that back to consume the next page. A stale cursor (id aged out of the ring) surfaces as `:reason :rf.mcp/cursor-stale` — drop it and restart. |
| `cache` | `snapshot`, `get-path`, `trace-window`, `watch-epochs`, `discover-app` | `false` | Repeated reads of the same (tool, args) within a session. On a hit the payload is replaced with `{:rf.mcp/cache-hit {:hash ... :unchanged-since <ms> :tool ... :hint ...}}` — you already have the byte-identical prior payload locally. 8-slot LRU, scoped to one MCP-server process. |
| `max-buffered-events` | `subscribe` | 500 | Runtime-side queue cap in **event count**. Overflow evicts oldest (drop-oldest FIFO) and reports `:overflow-reason :max-buffered-events`. |
| `max-buffered-bytes` | `subscribe` | 5_000_000 (~5MB) | Same queue, byte cap. OR-combined with the event cap; whichever trips first evicts. |
| `max-events` | `subscribe` | 0 (unbounded) | Terminate the subscription after N delivered events. Cheap kill-switch on a chatty stream. |
| `max-ms` | `subscribe` | 0 (unbounded) | Hard time-bound on the subscription. |
| `poll-ms` | `subscribe` | 100 | Server poll cadence. Raise to coalesce ticks; lower for tighter latency. |
| `include-sensitive?` | epoch-carrying tools + `subscribe` | `false` | Per-call privacy override. Off by default; turn on for a debug session inspecting `:sensitive? true` cascades. |

### Quick decision tree

- **First contact with an unknown app-db?** `snapshot` (default mode
  `"summary"`) — counts and top-level keys come back small.
- **Know the key already?** `get-path` with a deep `path`.
- **Same read twice in a row?** Add `cache true` on the second call.
- **Investigating recent activity?** `trace-window` with default
  `limit 50`; paginate via `cursor` if `:has-more?`.
- **Live-watching an event landing?** `watch-epochs` (pull) or
  `subscribe topic "epoch"` (push). For push, set
  `max-buffered-events`/`max-buffered-bytes` to your wire budget and
  `max-events` or `max-ms` as a kill-switch.
- **Got an `{:rf.size/large-elided ...}` marker?** Re-call `get-path`
  on the handle's `:path` to drill into a non-elided child, or pass
  `elision false` if you actually need the raw slot.
- **Got a `{:rf.mcp/overflow ...}` marker?** You tripped `max-tokens`.
  Narrow `path`, switch a slice to `"summary"`, lower `limit`, or add
  a tighter `filter`/`pred` — pick whichever knob the overflow's
  `:tool` hint suggests.

### Cross-MCP marker family

All four are returned **in place of** the requested payload (or slot)
when their condition fires. Check for them at the top level (or
inside the relevant slice) before treating a result as raw data:

- `{:rf.mcp/dedup-table <cache-map>}` — call `dedup/expand`.
- `{:rf.mcp/overflow {:tool ... :estimated-tokens ... :hint ...}}` —
  narrow the call.
- `{:rf.mcp/cache-hit {:hash ... :unchanged-since ... :hint ...}}` —
  reuse the prior call's payload.
- `{:rf.size/large-elided {:path ... :handle [:rf.elision/at ...] ...}}` —
  drill into the handle (or pass `elision false`). Markers come from
  app-schema slots carrying `{:large? true}`. If a large value appears
  without schema metadata, dev builds emit `:rf.warning/large-value-unschema'd`;
  authors should add `{:large? true}` to the schema slot when elision is desired.
- `{:rf.mcp/summary {:type :map :keys [...] :count N :bytes ~B}}` —
  ask for `"full"` mode (per-slice or globally) when you need detail.

---

## See also

- `references/streaming-subscriptions.md` — full `subscribe` lifecycle
  and filter vocab.
- `references/ops.md` — the structured op catalogue (the args above
  are the descriptor knobs; the ops are how the agent invokes them).
- `references/errors.md` — translating `:reason` codes to recovery.
