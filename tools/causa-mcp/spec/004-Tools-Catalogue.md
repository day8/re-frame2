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

### get-machine-list (T-Insp-6, rf2-8xzoe.19)

Enumerate registered re-frame2 machines per frame with their current
metadata (transitions, initial-state, tags). No pagination — bounded by
the per-frame machine count which is typically single-digit. Source-
coord pin: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
bead #19.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:include-sensitive?` | bool | false | passes to the runtime walker |
| `:include-large?` | bool | false | passes to the runtime walker |
| `:max-tokens` | int | 5000 | per-call cap; clamped to `[500, 50000]` |

**Return shape:**

```clojure
{:ok? true
 :machines {<machine-id> <meta-map> ...}
 :count <int>
 :elided-large <int?>}     ; only when > 0
```

**Cap-reached hint:** `:narrow-filter` (default fallback — no per-tool
hint registered; a future bead may add per-frame slicing when the
catalogue surfaces a `:frame` arg here).

Implementation: [`tools/causa-mcp/src/.../tools/get_machine_list.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_machine_list.cljs).

### get-handlers (T-Insp-8, rf2-8xzoe.21)

Registrar surface — enumerate registered handlers grouped by `:kind`
(event / sub / fx / cofx / machine / flow / frame / view /
reg-machine). Default mode returns a kind-keyed map; pass
`:group-by-kind? false` for the flat vector. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #21.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:kind` | keyword | nil | narrow to one registrar kind |
| `:group-by-kind?` | bool | true | shape result as `{<kind> [...]}` vs flat vec |
| `:include-sensitive?` | bool | false | passes to the runtime walker |
| `:include-large?` | bool | false | passes to the runtime walker |
| `:max-tokens` | int | 5000 | per-call cap; clamped to `[500, 50000]` |

**Return shape (grouped, default):**

```clojure
{:ok? true
 :handlers {<kind> [{:id <any> :meta <map>} ...] ...}
 :count <int>
 :kinds <vec of kw>
 :elided-large <int?>}
```

**Return shape (flat, `:group-by-kind? false`):**

```clojure
{:ok? true :handlers [{:kind :id :meta} ...] :count <int>}
```

**Cap-reached hint:** `:narrow-filter` — re-call with `:kind` to slice.

Implementation: [`tools/causa-mcp/src/.../tools/get_handlers.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_handlers.cljs).

### get-source-coord (T-Insp-9, rf2-8xzoe.22)

Return the source coord (`:ns :line :column :file`) for a registered
handler. Aligns with editor-URI substitution so an agent host can
render a jump-to-definition link off the response. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #22.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:kind` | keyword | **required** | registrar kind |
| `:id` | keyword | **required** | the registered id |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true :kind <kw> :id <any> :source-coord {:ns :line :column :file}}
```

**Failure envelopes:**

- `{:ok? false :reason :missing-kind :hint ...}` — `:kind` arg absent.
- `{:ok? false :reason :missing-id   :hint ...}` — `:id` arg absent.
- `{:ok? false :reason :no-source-coord :kind <k> :id <i>}` — handler
  registered without `:source-coord` metadata (e.g. macroexpansion
  unable to capture file/line).

**Cap-reached hint:** `:narrow-filter` (default — source-coord is
small, overflow only happens with pathological metadata).

Implementation: [`tools/causa-mcp/src/.../tools/get_source_coord.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_source_coord.cljs).

### get-machine-state (T-Insp-5, rf2-8xzoe.18)

Per-machine snapshot — the registered FSM spec for the named machine.
Default `:mode :summary` returns the initial-state + tags +
state-names (the keys of the transitions table) so a top-level
inspection call ships a small payload; `:mode :full` returns the
entire metadata map. Path slicing via `:path` drills into a subtree
like `get-in`. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #18.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:machine-id` | keyword | **required** | the registered machine id |
| `:frame` | keyword | nil | scope to one frame; nil → resolve sole frame |
| `:path` | EDN-vec str | nil | slice into the spec, like `get-in` |
| `:mode` | keyword | `:summary` | `:summary` or `:full` |
| `:include-sensitive?` | bool | false | passes to the runtime walker |
| `:include-large?` | bool | false | passes to the runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true
 :frame <kw> :machine-id <kw>
 :mode <:summary|:full>
 :state <map>
 :path <vec?>            ; only when :path supplied
 :elided-large <int?>}
```

**Cap-reached hint:** `:slice` (drill in via `:path`) when `:mode
:full` overflowed; `:switch-mode` (downshift to `:summary`)
otherwise. Default fallback `:narrow-filter`.

Implementation: [`tools/causa-mcp/src/.../tools/get_machine_state.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_machine_state.cljs).

### get-issues (T-Insp-7, rf2-8xzoe.20)

Recent issue-tier events from the re-frame2 trace bus — errors,
warnings, schema violations, hydration mismatches. Severity filter
(`:error` matches `:error` + `:rf.schema/violation` +
`:rf.hydration/mismatch`; `:warning` matches `:warning` only; `:all`
default). Pagination via `:limit` / `:offset`. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #20.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:severity` | keyword | `:all` | `:error`, `:warning`, or `:all` |
| `:frame` | keyword | nil | scope to one frame |
| `:since-ms` | int | nil | wall-clock cutoff (ms) |
| `:limit` | int | 50 | max issues returned |
| `:offset` | int | 0 | skip the first N post-filter |
| `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` items |
| `:include-large?` | bool | false | passes to the runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true
 :issues <vec>
 :count <int> :total <int>
 :limit <int> :offset <int>
 :severity <:error|:warning|:all>
 :dropped-sensitive <int?>
 :elided-large <int?>}
```

**Cap-reached hint:** `:paginate` (default fallback `:narrow-filter`).

Implementation: [`tools/causa-mcp/src/.../tools/get_issues.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_issues.cljs).

### get-epoch-history (T-Insp-2, rf2-8xzoe.15)

Per-frame epoch history (vector of `:rf/epoch-record` per Tool-Pair
§Time-travel). Oldest-first; depth-50 default page size with opaque
cursor pagination via `:next-cursor`. The runtime returns the full
history; this tool slices it cursor-relative on the MCP-server side
so multi-page walks don't roundtrip the full epoch ring twice.
Source-coord pin: `ai/findings/causa-epics-breakdown-2026-05-17.md`
§Part 1 bead #15.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:frame` | keyword | nil | scope to one frame; nil → resolve sole frame |
| `:limit` | int | 50 | page size (Tool-Pair depth-50 default) |
| `:cursor` | string | nil | opaque resume cursor from prior `:next-cursor` |
| `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` items |
| `:include-large?` | bool | false | passes to the runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true
 :frame <kw>
 :epochs <vec of :rf/epoch-record>
 :count <int> :total <int>
 :limit <int>
 :next-cursor <string?>      ; only when more pages remain
 :dropped-sensitive <int?>
 :elided-large <int?>}
```

**Cursor staleness:** if the cursor's `:after-id` no longer appears
in the epoch ring (evicted), the tool surfaces:

```clojure
{:ok? false :reason :cursor-stale :frame <kw>
 :requested-id <id> :hint "...re-call without :cursor..."}
```

**Cap-reached hint:** `:paginate` (default fallback `:narrow-filter`)
— re-call with a smaller `:limit` or use the `:next-cursor` to walk
multi-page.

Implementation: [`tools/causa-mcp/src/.../tools/get_epoch_history.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_epoch_history.cljs).

### get-app-db (T-Insp-3, rf2-8xzoe.16)

Direct-read app-db at a frame, optionally scoped by `:path` (`get-in`
semantics). Per the MUST inventory in
[`spec/004-Wire-Pipeline.md`](004-Wire-Pipeline.md) §Direct-read:
**MUST 8** (path slicing is the default), **MUST 9** (summary mode is
the default), **MUST 19** (both `:include-sensitive?` and
`:include-large?` default `false`). Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #16.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:frame` | keyword | nil | scope to one frame |
| `:path` | EDN-vec str | `nil` | slice into app-db |
| `:mode` | keyword | `:summary` | `:summary` or `:full` |
| `:include-sensitive?` | bool | false | passes to runtime walker |
| `:include-large?` | bool | false | passes to runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true
 :frame <kw>
 :path <vec>
 :mode <:summary|:full>
 :value <edn>
 :elided-large <int?>}
```

**Summary marker (`:mode :summary` default):**

```clojure
;; for a map :value
{:rf.mcp/summary {:type :map :top-keys [:cart :user :nav] :count 3}}
;; truncated when count > 64
{:rf.mcp/summary {:type :map :top-keys [...64...] :count 5000
                  :keys-truncated? true}}
;; scalars ride through unchanged in summary mode
:home
```

**Cap-reached hint:** `:slice` (drill via `:path`) when `:mode
:full`; `:switch-mode` (downshift to `:summary`) when `:full` was
explicitly requested without a path. Default fallback
`:narrow-filter`.

Implementation: [`tools/causa-mcp/src/.../tools/get_app_db.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_app_db.cljs).
