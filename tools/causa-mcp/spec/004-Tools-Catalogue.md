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

### get-app-db-diff (T-Insp-4, rf2-8xzoe.17)

App-db diff for a named epoch. Per MUST 13 in
[`spec/004-Wire-Pipeline.md`](004-Wire-Pipeline.md), the default mode
returns **changed-paths-with-cardinalities, NOT the nested
before/after diff** — a token-cheap envelope from which the agent
drills via `get-app-db` for any single changed path. Source-coord
pin: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
bead #17.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:frame` | keyword | nil | scope to one frame |
| `:epoch-id` | string | **required** | the epoch to diff |
| `:mode` | keyword | `:changed-paths` | `:changed-paths` or `:nested` |
| `:include-sensitive?` | bool | false | passes to runtime walker |
| `:include-large?` | bool | false | passes to runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape (default `:mode :changed-paths`):**

```clojure
{:ok? true
 :frame <kw>
 :epoch-id <id>
 :mode :changed-paths
 :diff {:added   [<path-vec> ...]
        :removed [<path-vec> ...]
        :changed [<path-vec> ...]
        :counts  {:added <int> :removed <int> :changed <int>}}}
```

**Return shape (`:mode :nested`):**

```clojure
{:ok? true :frame <kw> :epoch-id <id> :mode :nested
 :diff {:before <edn> :after <edn>}
 :elided-large <int?>}
```

**Diff semantics:** the changed-paths projection recurses into maps;
vectors / sets / scalars are leaves at the diff boundary (a changed
vector is one `:changed` entry, not per-element). Path vectors are
sorted by printed form so output is deterministic.

**Privacy contract:** runtime accessor routes `:db-before` +
`:db-after` through `re-frame.core/elide-wire-value` PRE-diff, so
declared-sensitive paths are scrubbed before path-comparison.

**Cap-reached hint:** `:switch-mode` (downshift from `:nested` to
`:changed-paths`) or `:slice` (drill into a single changed path via
`get-app-db`). Default fallback `:narrow-filter`.

Implementation: [`tools/causa-mcp/src/.../tools/get_app_db_diff.cljs`](../src/day8/re_frame2_causa_mcp/tools/get_app_db_diff.cljs).

## Mutation band

Mutation-band tools change framework state. Per `004-Wire-Pipeline.md`
§Authority classes, **Class-1 named mutations** (`dispatch`,
`restore-epoch`, `reset-frame-db`) carry **no per-call consent
gate** — consent is the server-launch enable signal. Every mutation
routes through the framework's normal dispatch / restore / reset
cascade; the runtime stamps `:tags :origin :causa-mcp` on emitted
traces via the `*current-origin*` dynamic binding (B-3 of the
wire-pipeline tranche). Trace egress from a mutation's cascade is
read via `subscribe :trace` (T-Stream-1) or the next
`get-trace-buffer` call.

### dispatch (T-Mut-1, rf2-8xzoe.23)

Fire a re-frame event through the frame's normal dispatch cascade.
Source-coord pin: `ai/findings/causa-epics-breakdown-2026-05-17.md`
§Part 1 bead #23.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:event` | EDN-vec str | **required** | event vector, e.g. `"[:cart/add 42]"` |
| `:frame` | keyword | nil | scope to one frame; nil → sole frame |
| `:sync?` | bool | false | `:sync` (true) vs `:queued` (false) |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true
 :event-id <kw>
 :frame    <kw>
 :origin   :causa-mcp
 :mode     <:queued|:sync>}
```

**Failure envelopes:**

- `{:ok? false :reason :missing-event :hint ...}`
- `{:ok? false :reason :event-malformed :given <s> :hint ...}`
- `{:ok? false :reason :not-an-event-vector :hint ...}` — parsed
  value isn't a vector.
- `{:ok? false :reason :no-frame-resolved :hint ...}` — multi-frame
  app without an explicit `:frame` arg.

**Cap-reached hint:** `:narrow-filter` (default fallback — the
mutation envelope is small; overflow would only happen with
pathological runtime auxiliary metadata).

Implementation: [`tools/causa-mcp/src/.../tools/dispatch.cljs`](../src/day8/re_frame2_causa_mcp/tools/dispatch.cljs).

### restore-epoch (T-Mut-2, rf2-8xzoe.24)

Rewind a frame's `app-db` to the named epoch's `:db-after` via
`re-frame.core/restore-epoch`. Bound by the Tool-Pair §Time-travel
restore contract: returns `:ok? true` on success, `:ok? false`
with `:reason :rf.epoch/restore-failed` on any of the six
documented failure rows. The per-row `:rf.epoch/*` keyword surfaces
on the trace bus (read via `get-trace-buffer` or `subscribe :trace`)
— the tool envelope intentionally does not double-project it (the
framework wrapper returns a plain boolean; the row already lives
on the bus). Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #24.

**Six-row failure table** (read off the trace bus by `:op-type`):

| Row | Trace `:op-type` keyword |
|---|---|
| unknown frame | `:rf.error/no-such-handler` (kind `:frame`) |
| unknown epoch | `:rf.epoch/restore-unknown-epoch` |
| schema mismatch | `:rf.epoch/restore-schema-mismatch` |
| missing handler | `:rf.epoch/restore-missing-handler` |
| version mismatch | `:rf.epoch/restore-version-mismatch` |
| restore during drain | `:rf.epoch/restore-during-drain` |

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:epoch-id` | string | **required** | epoch-id from `get-epoch-history` |
| `:frame` | keyword | nil | scope to one frame; nil → sole frame |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape (success):**

```clojure
{:ok? true :frame <kw> :epoch-id <id> :origin :causa-mcp}
```

**Return shape (failure):**

```clojure
{:ok? false :frame <kw> :epoch-id <id> :origin :causa-mcp
 :reason :rf.epoch/restore-failed
 :hint "Restore failed — read the trace bus for the structured :rf.epoch/* row."}
```

**Cap-reached hint:** `:narrow-filter` (default fallback — ack
envelope is small).

Implementation: [`tools/causa-mcp/src/.../tools/restore_epoch.cljs`](../src/day8/re_frame2_causa_mcp/tools/restore_epoch.cljs).

### reset-frame-db (T-Mut-3, rf2-8xzoe.25)

Re-inject a value into a frame's `app-db`, bypassing the dispatch
cascade. Schema-validates against registered app-db schemas via
`re-frame.core/reset-frame-db!`. Same projection rationale as
`restore-epoch`: the framework wrapper returns a boolean, the per-row
`:rf.epoch/*` keyword lives on the trace bus, this tool surfaces
`:rf.epoch/reset-failed` and points at the bus. Every reset rides
the restore-audit ring tagged `:origin :causa-mcp`. Source-coord
pin: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
bead #25.

**Three-row failure table** (read off the trace bus):

| Row | Trace `:op-type` keyword |
|---|---|
| unknown frame | `:rf.error/no-such-handler` (kind `:frame`) |
| reset during drain | `:rf.epoch/reset-frame-db-during-drain` |
| schema mismatch | `:rf.epoch/reset-frame-db-schema-mismatch` |

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:value` | EDN-map str | **required** | the new `app-db` value |
| `:frame` | keyword | nil | scope to one frame; nil → sole frame |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape (success):**

```clojure
{:ok? true :frame <kw> :origin :causa-mcp}
```

**Return shape (failure):**

```clojure
{:ok? false :frame <kw> :origin :causa-mcp
 :reason :rf.epoch/reset-failed
 :hint "Reset failed — read the trace bus for the structured :rf.epoch/* row."}
```

**Cap-reached hint:** `:narrow-filter` (default fallback — ack
envelope is small).

Implementation: [`tools/causa-mcp/src/.../tools/reset_frame_db.cljs`](../src/day8/re_frame2_causa_mcp/tools/reset_frame_db.cljs).

## Streaming band

Streaming-band tools surface push-mode data over an MCP `tools/call`
that stays open across many polling ticks. The contract surface is
**per-drain-batch port-pinning** (the cross-MCP wire-batching idiom):
one `tools/call` ↔ one `progressToken` ↔ one stream of
`notifications/progress` ticks ↔ one terminal summary on close. The
agent reads ticks correlated by `progressToken` and pattern-matches
on the terminal envelope's `:reason` slot to discover why the stream
closed.

### subscribe (T-Stream-1, rf2-8xzoe.26)

Open a per-drain-batch streaming subscription on topic `:trace`,
`:epoch`, `:fx`, or `:error`. The `tools/call` stays open until the
client aborts, `:max-events` / `:max-ms` is reached, or `unsubscribe`
is called. Each non-empty polling tick emits one
`notifications/progress` notification carrying the batch's events;
the terminal `tools/call` result is a summary with cumulative
`:delivered` + `:ticks` + `:reason`. Source-coord pin:
`ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1 bead #26.

**Topic → trace-bus projection:**

| Topic | Source |
|---|---|
| `:trace` | full trace buffer; `:op-type` filter optional |
| `:epoch` | trace events with `:op-type :epoch/closed` |
| `:fx` | trace events with `:op-type :fx/run` |
| `:error` | issue-tier events (`:error` / `:warning` / `:rf.schema/violation` / `:rf.hydration/mismatch`) |

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:topic` | keyword | **required** | one of `:trace :epoch :fx :error` |
| `:filter` | map | nil | per-topic Spec 009 filter |
| `:frame` | keyword | nil | scope to one frame |
| `:poll-ms` | int | 100 | polling cadence |
| `:max-events` | int | 0 | terminate after N events (0 = unbounded) |
| `:max-ms` | int | 0 | terminate after N ms (0 = unbounded) |
| `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` |
| `:include-large?` | bool | false | passes to runtime walker |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Per-tick `notifications/progress` payload:**

```clojure
;; progressToken correlated to the originating tools/call
{:progressToken <token>
 :progress      <tick-int>
 :message       (pr-str {:sub-id <uuid>
                         :events [<event> ...]
                         :dropped-sensitive <int?>
                         :elided-large <int?>})}
```

**Terminal `tools/call` result:**

```clojure
{:ok? true
 :sub-id <uuid>
 :topic <kw>
 :delivered <int>
 :ticks <int>
 :reason <:aborted|:max-events-reached|:max-ms-reached|:unsubscribed>
 :dropped-sensitive <int?>
 :elided-large <int?>}
```

**Failure envelopes:**

- `{:ok? false :reason :unknown-topic :given <s> :hint ...}` — topic
  not in `{:trace :epoch :fx :error}`.
- `{:ok? false :reason :runtime-not-preloaded :hint <setup>}` —
  Causa-the-panel preload isn't loaded.

**Cap-reached hint:** `:paginate` (default fallback `:narrow-filter`)
— shorten the stream via `:max-events` / `:max-ms`, or narrow the
`:filter`.

Implementation: [`tools/causa-mcp/src/.../tools/subscribe.cljs`](../src/day8/re_frame2_causa_mcp/tools/subscribe.cljs).

### unsubscribe (T-Stream-2, rf2-8xzoe.27)

Close a streaming subscription by `:sub-id`. Idempotent: re-calling
for an already-closed sub-id returns `:existed? false`. An open
`subscribe` `tools/call` observes the missing sub-id on its next
polling tick and resolves with `:reason :unsubscribed`. Source-coord
pin: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
bead #27.

| Arg | Type | Default | Notes |
|---|---|---|---|
| `:sub-id` | string | **required** | sub-id from a prior `subscribe` |
| `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

**Return shape:**

```clojure
{:ok? true :sub-id <uuid> :existed? <bool>}
```

**Failure envelopes:**

- `{:ok? false :reason :missing-sub-id :hint ...}` — `:sub-id`
  arg absent / blank (returned as `isError`).
- `{:ok? false :reason :runtime-not-preloaded :hint <setup>}` —
  Causa-the-panel preload isn't loaded.

**Cap-reached hint:** `:narrow-filter` (default fallback — ack
envelope is small).

Implementation: [`tools/causa-mcp/src/.../tools/unsubscribe.cljs`](../src/day8/re_frame2_causa_mcp/tools/unsubscribe.cljs).
