# 004-Tools-Catalogue: Causa-MCP tool catalogue

This file is the **normative tool-catalogue** for `tools/causa-mcp/`.
Every MCP tool the server exposes via `tools/list` is documented here
with its arg shape, return envelope, and wire-pipeline contract.

The catalogue is grouped by band (per
[`tools/causa-mcp/spec/000-Vision.md`](000-Vision.md) §What it is):

- **Inspection band** (read-only, T-Insp tranche) — rf2-8xzoe.14..22
- **Mutation band** (state-changing, T-Mut tranche) — rf2-8xzoe.23..25
- **Streaming band** (push-mode, T-Stream tranche) — rf2-8xzoe.26..28
- **Meta band** (discover-app, eval-cljs, tail-build, get-causa-instructions) — rf2-8xzoe.29..32

This file grows as each tranche lands; entries are append-only and
follow a uniform shape so an agent reading the catalogue can pattern-
match across tools.

## Inspection band

Inspection-band tools are **read-only**: they snapshot framework state
(trace bus, epoch history, app-db, registry) without firing any
re-frame side-effects. Every tool routes through the wire-pipeline
boundary:

| Mechanism | Where | Effect |
|---|---|---|
| **B-1 privacy default-suppress** | trace-stream tools (`get-trace-buffer`, `get-epoch-history`, `get-issues`) | drops `:sensitive? true` items unless `:include-sensitive? true` |
| **W-6 size elision** | every tool returning tree-typed payload | counts `{:rf.size/large-elided ...}` markers the runtime walker emitted, stamps `:elided-large` on the envelope |
| **W-1 token cap** | every tool | enforces `max-tokens` (default 5000, clamp `[500, 50000]`) — over-budget responses replaced with the `:rf.mcp/overflow` marker |

### get-trace-buffer (T-Insp-1, rf2-8xzoe.14)

Read a slice of the re-frame2 trace bus filtered by op-type / frame /
since-ms / event-id / origin. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #14.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:frame` | keyword | nil | scope to one frame; nil → all frames |
| `:op-type` | keyword | nil | filter by operation type |
| `:since-ms` | int | nil | wall-clock cutoff (ms) |
| `:event-id` | keyword | nil | filter by dispatched event id |
| `:origin` | keyword | nil | filter by `:tags :origin` |
| `:limit` | int | 50 | max events returned |
| `:offset` | int | 0 | skip the first N events post-filter |
| `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` items |
| `:include-large?` | bool | false | opt back in to large values |
| `:max-tokens` | int | 5000 | per-call cap; clamped to `[500, 50000]` |

**Return shape (happy path):**

```clojure
{:ok? true
 :events <vec>
 :count <int>                  ; events returned
 :total <int>                  ; events pre-strip
 :limit <int>
 :offset <int>
 :dropped-sensitive <int?>     ; only when > 0
 :elided-large <int?>}         ; only when > 0
```

**Overflow:**

```clojure
{:rf.mcp/overflow
 {:limit :reached :cap <n> :would-be <n>
  :hint :paginate
  :continuation {:next-args {:limit 25}}}}
```

**Cap-reached hint:** `:paginate` — re-call with a smaller `:limit`.

**Failure envelopes:**

- `{:ok? false :reason :runtime-not-preloaded :hint <setup>}` —
  Causa-the-panel preload isn't loaded.
- `{:ok? false :reason :no-frame-resolved ...}` — `:frame` arg absent
  in a multi-frame app.

Implementation: [`tools/causa-mcp/src/.../tools/get_trace_buffer.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_trace_buffer.cljs).
