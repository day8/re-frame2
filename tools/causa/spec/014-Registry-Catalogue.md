# 014-Registry-Catalogue

The normative enumeration of every `:rf.causa/*` registration —
subscriptions, events, and effects — that Causa installs into the
process-global registrar. This doc is reference material: an AI agent
or human reader handed only the Causa spec MUST be able to reconstruct
the registry's surface from this catalogue alone, without reading
`tools/causa/src/day8/re_frame2_causa/registry.cljs`.

The thesis: per [`spec/Conventions.md` §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes)
and [`008-Embedding-Contract.md` §Registry-key isolation via `:rf.causa/*` prefix](./008-Embedding-Contract.md#registry-key-isolation-via-rfcausa-prefix),
Causa namespaces every registrar id under `:rf.causa/*` to keep
process-global collisions impossible. The prefix is the contract;
this doc enumerates what sits inside it.

## Naming convention

| Prefix | Used for |
|---|---|
| `:rf.causa/<id>` | Every subscription, every cofx, and every cross-panel event (consumed from ≥2 panels) or shared-infrastructure event (trace-buffer pump, epoch-history pump, etc.). |
| `:rf.causa.<panel>/<id>` | Every panel-internal event — owned by exactly one panel, never dispatched from another. The namespace itself encodes "panel-internal, no cross-panel callers"; renaming the panel renames the namespace. Per the rf2-nmc1f cleanup the issues-ribbon panel uses this convention (`:rf.causa.issues/clear-filters`, `:rf.causa.issues/toggle-severity`, etc.). Other panels MAY adopt the convention as their event surface stabilises. |
| `:rf.causa.fx/<id>` | Every effect (fx). The trailing `.fx/` segment is the canonical effect-id marker — agents grepping for the fx subset MAY use `:rf.causa.fx/` as the discriminator. |

Causa MUST NOT register a handler under any non-`:rf.causa*/` keyword.
A host registering `:user/login` and Causa registering
`:rf.causa/select-panel` cannot stamp on each other; the prefix is the
collision-avoidance contract enforced by code review and the registry
namespace docstring.

The catalogue below groups registrations by **owning panel** (per
[`007-UX-IA.md`](./007-UX-IA.md) §Information architecture). Where a
registration is shared across panels (e.g. `:rf.causa/select-dispatch-id`
is consumed by both the event-detail panel and the causality graph
per [`001-Causality-Graph.md`](./001-Causality-Graph.md) §10 Lock 7),
it appears once under its primary owner with cross-panel use noted.

Cross-panel infrastructure (the trace-buffer sub, the target-frame
sub, the epoch-history pump) is enumerated under
[§Shared infrastructure](#shared-infrastructure).

## Idempotency

Every registration is installed inside a `compare-and-set!` idempotency
gate (`register-causa-handlers!`) so shadow-cljs `:after-load` reloads
do NOT re-register. Tests MAY use `reset-for-test!` to drop the
sentinel and drive multiple registration cycles. Production code MUST
NOT call `reset-for-test!`. Per-panel `install!` helpers (e.g. the AI
Co-Pilot's) run inside the same gate so panel-owned registrations
inherit idempotency without re-doing the dance.

## Shared infrastructure

Subscriptions and events the entire panel set composes against. These
registrations have no single owning panel; they back the trace bus,
the time-travel scrubber, and the per-frame target selection.

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.causa/trace-buffer` | thunks `trace-bus/buffer` (the process-global ring atom) | Vector of `:rf/trace-event` records, oldest-first (per [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Consumer contract). | Layer-1 sub re-fires on every app-db change of the resolved frame; under Causa's normal usage (panels rendered inside a host app) the host's next dispatch dirties the frame's db and the sub picks up whatever the trace-cb has accumulated in the atom since the previous recompute (rf2-e9s81 — supersedes rf2-iw5ym's app-db-mirror path; see [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Reactivity for the trade-off). |
| `:rf.causa/suppressed-sensitive-count` | `db` (reads `:suppressed-counters`) | Integer — total suppressed `:sensitive? true` events under the current `:trace/show-sensitive?` setting. | On `db` write to `:suppressed-counters` (rf2-0vxdn — reactive immediate update of the `[● REDACTED N]` bottom-rail indicator). |
| `:rf.causa/target-frame` | `db` | Keyword frame-id (default `:rf/default`). | On `db` write to `:target-frame`. |
| `:rf.causa/epoch-history` | `db` | Vector of `:rf/epoch-record`, oldest-first (cached snapshot of `(rf/epoch-history target)`). | On `:rf.causa/epoch-recorded` dispatch. |
| `:rf.causa/target-frame-db` | `:rf.causa/target-frame`, `:rf.causa/epoch-history` | The host frame's current `app-db` value (via `rf/get-frame-db`). | Every settled epoch on the target frame. |

### Events

| Event | Vector shape | Returns | Notes |
|---|---|---|---|
| `:rf.causa/epoch-recorded` | `[_ frame-id]` | `{:db ...}` | Pumped from the epoch-cb registered in `preload.cljs` on every settled epoch. Re-reads `rf/epoch-history` to keep the cached snapshot consistent. No-ops when `frame-id` ≠ the current target. |
| `:rf.causa/note-sensitive-suppressed` | `[_ frame-id]` | `{:db ...}` | rf2-0vxdn — bumps `[:suppressed-counters (or frame-id :global)]` in Causa's app-db. Dispatched from `trace-bus/collect-trace!` (CLJS) when the privacy gate drops a `:sensitive? true` event. Drives the `:rf.causa/suppressed-sensitive-count` sub reactively. |
| `:rf.causa/reset-suppressed-counters` | `[_]` or `[_ frame-id]` | `{:db ...}` | rf2-0vxdn — clears all buckets (no arg) or just the named bucket. Dispatched from `trace-bus/clear-buffer!` (CLJS) — clearing the trace ring buffer also drops the `[● REDACTED N]` indicator state. |
| `:rf.causa/select-panel` | `[_ panel-id]` | `{:db ...}` | Drives the canvas switch logic in `shell.cljs`. Default per [`007-UX-IA.md`](./007-UX-IA.md) §10 Lock 7 is `:event-detail`. |

## Event-detail panel

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §The default landing view, §10 Lock 7.

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.causa/selected-dispatch-id` | `db` | Dispatch-id keyword or `nil` (empty-state). | On selection-change events. |
| `:rf.causa/event-detail` | `:rf.causa/trace-buffer`, `:rf.causa/selected-dispatch-id` | `{:cascades [...] :selected-dispatch-id ... :selected-cascade ...}` — composite. `:selected-cascade` is `nil` when no selection OR when the id is no longer in the buffer. | Buffer or selection change. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-dispatch-id` | `[_ dispatch-id]` | Sets selection. Also consumed by the causality graph per §10 Lock 7. |
| `:rf.causa/clear-selected-dispatch-id` | `[_]` | Drops selection. |

## Time-travel scrubber

Spec: [`002-Time-Travel.md`](./002-Time-Travel.md).

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.causa/selected-epoch-id` | Epoch-id or `nil` (= newest, no scrub in flight). | Per §The passive-scrubbing rule — scrubbing rebases panels; rewind is opt-in. |
| `:rf.causa/selected-epoch-record` | `:rf/epoch-record` or `nil`. | Resolved from history + selected-id. |
| `:rf.causa/pin-store` | `{frame-id [<pin> ...]}` map. | Lock 4 session-scoped per §Pinned snapshots — never persisted to disk. |
| `:rf.causa/pinned-snapshots` | Vector of pins for current target-frame. | Decoupled from pin-store so unrelated frames' pins don't re-render the view. |
| `:rf.causa/time-travel` | Composite — `{:target-frame :history :selected-epoch-id :selected-record :selected-index :pins :chip-states :cap-reached?}`. | Mirrors the per-panel composite pattern. `chip-states` runs chip-state projection over each pin against current history. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-epoch` | `[_ epoch-id]` | Passive scrub — does NOT call `restore-epoch`. |
| `:rf.causa/clear-selected-epoch` | `[_]` | Drops view selection. |
| `:rf.causa/pin-current` | `[_ epoch-id label]` | Eager-copies `:db-after` off the live history record. Enforces the 32-pin cap; surfaces `:pin-overflow-toast` when reached. |
| `:rf.causa/unpin` | `[_ epoch-id]` | Drops a pin. |
| `:rf.causa/rename-pin` | `[_ epoch-id new-label]` | Rewrites a pin's `:label`; other 4-tuple slots are immutable. |
| `:rf.causa/dismiss-pin-overflow-toast` | `[_]` | Dismisses the cap-reached toast. |
| `:rf.causa/reset-to-epoch` | `[_ epoch-id]` | `event-fx` — emits `{:fx [[:rf.causa.fx/restore-epoch {:frame-id ... :epoch-id ...}]]}`. The confirmed-rewind branch (per spec §rewind = explicit). |
| `:rf.causa/reset-to-pinned` | `[_ epoch-id]` | `event-fx` — emits `{:fx [[:rf.causa.fx/reset-frame-db! {:frame-id ... :frame-db ...}]]}`. Per spec §Why reset-frame-db! not restore-epoch — pins hold the value directly. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.causa.fx/restore-epoch` | `{:frame-id :epoch-id}` | Thin delegation to `rf/restore-epoch`. The indirection lets test fixtures stub the write. |
| `:rf.causa.fx/reset-frame-db!` | `{:frame-id :frame-db}` | Thin delegation to `rf/reset-frame-db!`. |

## Causality graph

Spec: [`001-Causality-Graph.md`](./001-Causality-Graph.md).

Reuses the event-detail panel's `:rf.causa/select-dispatch-id` /
`:rf.causa/clear-selected-dispatch-id` (per §10 Lock 7) and the
scrubber's `:rf.causa/clear-selected-epoch` (for the cascade-filter
affordance). The panel adds one composite sub; no new events.

### Subscriptions

| Sub | Inputs | Returns |
|---|---|---|
| `:rf.causa/causality-graph-data` | `:rf.causa/trace-buffer`, `:rf.causa/selected-dispatch-id`, `:rf.causa/selected-epoch-id`, `:rf.causa/epoch-history` | `{:graph :layout :selected-dispatch-id :selected-epoch-id :filtered?}`. When the scrubber's selected-epoch resolves to a cascade-id in the graph, filters to that cascade family. |

## App-DB Diff panel

Spec: [`004-App-DB-Diff.md`](./004-App-DB-Diff.md).

Slice-centric `app-db` inspector. Reads the host frame's `app-db` via
`rf/get-frame-db` + the target-frame's epoch-history; produces the
`[op path before after]` diff triples the view consumes.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/selected-epoch-diff` | Diff triples for the selected (or newest) epoch. Composite over history + selection. |
| `:rf.causa/pinned-slices-store` | `{frame-id [path ...]}` — separate from time-travel's `:pin-store` (whole-epoch pins); this is per-frame slice-path pinning. |
| `:rf.causa/pinned-slices` | Live-derefed pinned slices for the current target-frame: `[{:path <vec> :value <current>} ...]`. |
| `:rf.causa/focused-slice-path` | The "Show me when this changed" focused path, or `nil`. |
| `:rf.causa/show-me-when-this-changed-result` | Vector of epoch hit-maps for epochs touching the focused path. `[]` when no focus. |
| `:rf.causa/app-db-diff` | Composite — `{:target-frame :history-empty? :changed-non-reserved :changed-reserved :pinned-slices :focused-path :focused-hits}`. The `[runtime]` group always renders current `:rf/*` slot contents per spec §Reserved-keys group. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/pin-slice` | `[_ path]` | Pins a slice path. Duplicates are dropped at the helper layer. |
| `:rf.causa/unpin-slice` | `[_ path]` | Unpins. |
| `:rf.causa/reorder-pinned-slices` | `[_ new-order]` | Replaces pin order; the caller computes the permutation. |
| `:rf.causa/focus-slice-path` | `[_ path]` | Sets the "Show me when this changed" focused path. |
| `:rf.causa/clear-slice-focus` | `[_]` | Drops the focus. |
| `:rf.causa/copy-value-to-clipboard` | `[_ value]` | `event-fx` — emits `{:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str value)}]]}`. |
| `:rf.causa/copy-path-to-clipboard` | `[_ path]` | `event-fx` — same shape, `pr-str path`. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.causa.fx/copy-to-clipboard` | `{:text <string>}` | Best-effort write via `navigator.clipboard.writeText`. No-op on non-browser targets (Node test, JVM). |

## Schema-violation timeline

Spec: [`005-Schema-Timeline.md`](./005-Schema-Timeline.md).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/registered-schemas` | Vector of `path-or-id` row keys from `rf/app-schemas`. `[]` when the schemas artefact is not on the classpath. |
| `:rf.causa/selected-violation-id` | The trace event's `:id` (stable per-process per [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)). |
| `:rf.causa/schema-filter` | Schema-id or `nil`. Narrows the rendered rows to one schema. |
| `:rf.causa/schema-timeline-window` | `{:t0 :t1}` in ms; falls back to the default 60s window ending at now. |
| `:rf.causa/schema-violations-window` | Vector of projected violation rows in chronological order, filtered to the current window. |
| `:rf.causa/schema-timeline-prev-rows` | Cache of previously-rendered rows so the panel's flash cue can detect empty→non-empty transitions. |
| `:rf.causa/schema-violation-timeline` | Composite — `{:rows :window :total-violations :rendered-violations :selected-violation :schema-filter}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/clear-violation-selection` | `[_]` | Closes the detail side panel. |
| `:rf.causa/select-violation` | `[_ violation-id]` | Selects by trace-event `:id`. Passing `nil` clears. |
| `:rf.causa/set-schema-filter` | `[_ schema-id]` | Narrows to one schema. Passing `nil` clears the filter. |
| `:rf.causa/set-schema-timeline-window` | `[_ {:t0 :t1}]` | Sets the window. Invalid maps (`nil`, non-numeric, `t0 >= t1`) revert to the default window. |

## Hydration debugger

Spec: [`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/selected-mismatch-id` | Mismatch trace event's `:id`, or `nil` (composite picks the latest). |
| `:rf.causa/hydration-reroot-path` | Re-root path for the side-by-side tree view per spec §Render-tree hash bisector, or `nil`. |
| `:rf.causa/hydration-debugger-data` | Composite — `{:has-mismatch? :mismatch-summary :selected-mismatch-id :detail :source-coord :re-root-path :target-frame}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-mismatch` | `[_ mismatch-id]` | Drives the side-by-side rebase. Drops the re-root (subtree-specific). |
| `:rf.causa/clear-mismatch-selection` | `[_]` | Clears selection + re-root. |
| `:rf.causa/reroot-tree-view` | `[_ path]` | Re-roots at `path` (per spec §Render-tree hash bisector). Empty path clears. |
| `:rf.causa/open-in-editor` | `[_ coord]` | Records the attempted source-coord. Full handler lives in `open-in-editor.cljs`; this is the thin record-the-attempt path. |

## Subscriptions panel

Spec: [`012-Subscriptions.md`](./012-Subscriptions.md).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/sub-cache` | Target frame's live sub-cache via `rf/sub-cache`. CLJS-only; JVM returns `nil` (panel renders empty state). Test override via `:sub-cache-override` on Causa's db. |
| `:rf.causa/sub-error-cache` | `{query-v <error-info>}`. v1 wiring keeps it empty until the error-collector plumbing lands. |
| `:rf.causa/selected-sub` | Query-v of the user's selection (drives the chain affordance). |
| `:rf.causa/sub-filters` | Set of active filter-chip statuses. |
| `:rf.causa/sub-chain-open?` | Boolean — is the chain affordance open? |
| `:rf.causa/subscriptions-data` | Composite — `{:rows :status-counts :total :selected-query-v :active-filters :chain-open? :chain}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-sub` | `[_ query-v]` | Sets selection. |
| `:rf.causa/clear-selected-sub` | `[_]` | Clears selection + chain-open. |
| `:rf.causa/toggle-sub-filter` | `[_ status]` | Adds / removes a status from the filter set. |
| `:rf.causa/show-invalidation-chain` | `[_ query-v]` | Opens the chain affordance. Optional `query-v` sets selection in one shot. |
| `:rf.causa/hide-invalidation-chain` | `[_]` | Closes the chain. |
| `:rf.causa/set-sub-cache-override-for-test` | `[_ ov]` | Test-only override hook. Production code paths MUST NOT dispatch. `nil` clears. |

## Issues ribbon

Spec: [`000-Vision.md` L94](./000-Vision.md), [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-namespace-convention--five-prefix-shapes).

Unified feed across errors, warnings, schema violations, hydration
mismatches. Filter axes: `:severities`, `:prefixes`, `:since-ms`. Each
axis independent; empty / `nil` disables the axis.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/issues-filters` | `{:severities :prefixes :since-ms}` — single read for atomic re-render. |
| `:rf.causa/issues-ribbon` | Composite — `{:issues :total :rendered :severity-counts :distinct-prefixes :filters :empty-kind}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa.issues/toggle-severity` | `[_ severity]` | Toggles a severity chip in/out. |
| `:rf.causa.issues/toggle-prefix` | `[_ prefix]` | Toggles a prefix chip in/out. |
| `:rf.causa.issues/set-since-seconds` | `[_ seconds]` | Converts s → ms; `nil` / non-positive clears the axis. |
| `:rf.causa.issues/clear-filters` | `[_]` | Clears every axis. |

## Flows panel

Spec consumer: framework Spec 013 (registered-flow surface) + Spec 009
(`:rf.flow/*` trace vocabulary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/registered-flows` | `(rf/registrations :flow)` — process-global registry. Test override via `:registered-flows-override`. |
| `:rf.causa/flow-trace-events` | Trace-buffer's `:op-type :flow` slice. |
| `:rf.causa/selected-flow-id` | Flow-id or `nil`. |
| `:rf.causa/flows-data` | Composite — `{:rows :status-counts :total :selected-flow-id}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-flow-id` | `[_ flow-id]` | Sets selection. |
| `:rf.causa/clear-flow-selection` | `[_]` | Clears selection. |
| `:rf.causa/set-registered-flows-override-for-test` | `[_ ov]` | Test-only override hook. |

## Effects panel

Spec consumer: framework Spec 002 §reg-fx + Spec 009 (`:rf.fx/*` trace
vocabulary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/registered-fxs` | `(rf/registrations :fx)` — process-global registry. Test override via `:registered-fxs-override`. |
| `:rf.causa/fx-trace-events` | Trace-buffer's fx-related slice (`:op-type :fx` + fx-layer error categories). |
| `:rf.causa/selected-fx-id` | Fx-id or `nil`. |
| `:rf.causa/effects-data` | Composite — `{:rows :outcome-counts :total :selected-fx-id}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-fx-id` | `[_ fx-id]` | Sets selection. |
| `:rf.causa/clear-fx-selection` | `[_]` | Clears selection. |
| `:rf.causa/set-registered-fxs-override-for-test` | `[_ ov]` | Test-only override hook. |

## Performance panel

Spec: [`000-Vision.md` L92](./000-Vision.md). Per-cascade duration
capture, perf-tier colour mapping, budget-warning markers. No
panel-owned events — reuses `:rf.causa/select-dispatch-id` and
`:rf.causa/select-panel` for the pivot-into-event-detail affordance.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/performance-budget-ms` | Over-budget threshold in ms; default per `perf-helpers/default-budget-ms`. |
| `:rf.causa/performance-data` | Composite — `{:rows :total :tier-counts :over-budget-count :budget-ms :empty?}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/set-performance-budget-ms` | `[_ budget-ms]` | Sets the threshold. `nil` / non-positive resets to default. |

## Trace panel

Consumer of the canonical 9-axis filter vocabulary documented in
[`spec/009-Instrumentation.md` §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/trace-filters` | The current 9-axis filter map. |
| `:rf.causa/trace-feed` | Composite — `{:rows :total :rendered :distinct :counts :filters :any-filter? :empty-kind}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/set-trace-filter` | `[_ axis value]` | Sets / clears one axis. `nil` value clears that axis. v1 is single-value per axis; multi-value rides a follow-on. |
| `:rf.causa/clear-trace-filters` | `[_]` | Clears every axis. |

## MCP Server panel

Spec: [`010-MCP-Server.md`](./010-MCP-Server.md) §Origin tagging.
Filters the trace-buffer to events tagged `:tags :origin :causa-mcp`.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/mcp-filters` | `{:op-types :since-ms}` — single atomic read. |
| `:rf.causa/mcp-server` | Composite — `{:rows :total :rendered :op-type-counts :distinct-op-types :filters :agent-attached? :empty-kind}`. |
| `:rf.causa/mcp-origin-filter-enabled?` | Boolean — cross-panel highlight toggle. Default `false` (opt-in). Other panels MAY honour to dim non-agent events. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/toggle-mcp-op-type` | `[_ op-type]` | Toggles an op-type chip. |
| `:rf.causa/set-mcp-since-seconds` | `[_ seconds]` | s → ms; `nil` / non-positive clears. |
| `:rf.causa/clear-mcp-filters` | `[_]` | Clears every axis. |
| `:rf.causa/toggle-mcp-origin-filter` | `[_]` | Toggles the cross-panel highlight. |

## Routes panel

Spec: [`spec/012-Routing.md`](../../../spec/012-Routing.md). Surfaces
registered routes, the active `:rf/route` slice, and recent navigation
history (the `:rf.route.nav-token/*` + `:rf.route/url-changed` trace
stream).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/registered-routes` | `(rf/registrations :route)`. Wrapped in `try` for older builds without the `:route` kind. Test override via `:registered-routes-override`. |
| `:rf.causa/active-route-slice` | The `:rf/route` slot off the target-frame's `app-db`. |
| `:rf.causa/active-route-slice-override` | Test override for the slice — wired separately from the live slice sub so integration tests can override the slice without disturbing the target-frame-db chain. |
| `:rf.causa/route-history-events` | Trace-buffer's route-history slice — filtered to the three operations Spec 012 §Trace events enumerates. |
| `:rf.causa/selected-route-id` | Route-id or `nil`. |
| `:rf.causa/routes-data` | Composite — `{:rows :total :active-route :selected-route-id :history :empty-kind}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-route` | `[_ route-id]` | Sets selection. |
| `:rf.causa/clear-route-selection` | `[_]` | Clears selection. |
| `:rf.causa/set-registered-routes-override-for-test` | `[_ ov]` | Test-only override hook. |
| `:rf.causa/set-active-route-slice-override-for-test` | `[_ ov]` | Test-only slice override. |

## Machine inspector

Spec: [`003-Machine-Inspector.md`](./003-Machine-Inspector.md). Reads
`(rf/machines)`, the live `:rf/machine` snapshots, and the
trace-buffer's `:rf.machine/transition` slice. Read-only at v1 —
share-affordance and source-coord jumps live in `tools/machines-viz/`.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/registered-machines` | Vector of machine-ids from `(rf/machines)`. Wrapped in `try` so future API changes collapse to `[]` rather than throwing. Test override via `:registered-machines-override`. |
| `:rf.causa/machine-snapshots` | `{machine-id <snapshot>}` map from the target-frame's `:rf/machines` slot. |
| `:rf.causa/machine-snapshots-override` | Test override hook. |
| `:rf.causa/selected-machine-id` | Machine-id or `nil` (composite defaults to first row). |
| `:rf.causa/machine-inspector-data` | Composite — `{:machines :total :selected-id :selected :chart-props :transitions :empty-kind}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/select-machine-id` | `[_ machine-id]` | Sets selection. |
| `:rf.causa/clear-machine-selection` | `[_]` | Clears selection. |
| `:rf.causa/set-registered-machines-override-for-test` | `[_ ov]` | Test-only override hook. |
| `:rf.causa/set-machine-snapshots-override-for-test` | `[_ ov]` | Test-only override hook. |

## AI Co-Pilot panel

Spec: [`009-AI-CoPilot.md`](./009-AI-CoPilot.md). The Co-Pilot owns
its own subs / events / fxs — chip parsing, slash commands,
conversation buffer, provider streaming. The panel's `install!` is
called from inside the central `register-causa-handlers!`
idempotency gate so the Co-Pilot's registrations install once per
process and reload-safely.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.causa/copilot-open?` | Boolean — rail open/closed. Default `false` (collapsed) per Lock 8. |
| `:rf.causa/copilot-conversation` | Vector of turns `{:role :question/:answer :text :streaming?}`. Per-session, in-memory only (Lock 12). |
| `:rf.causa/copilot-provider` | `:claude :openai :gemini :local :custom`. Default `:claude`. |
| `:rf.causa/copilot-cue-active?` | Boolean — `true` until first use; drives the pulse cue per spec §The AI co-pilot collapsed cue. |
| `:rf.causa/copilot-redaction-settings` | Per-category redaction toggles; defaults are privacy-by-default per spec §Redaction defaults. |
| `:rf.causa/copilot-streaming-token-count` | Integer — tokens streamed into the in-flight answer. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.causa/copilot-toggle` | `[_]` | Toggles open/closed; counts as first-use. |
| `:rf.causa/copilot-mark-first-use` | `[_]` | Marks first-use without flipping open/closed (sidebar hover-stop affordance). |
| `:rf.causa/copilot-set-provider` | `[_ provider]` | Sets the active provider. |
| `:rf.causa/copilot-cycle-provider` | `[_]` | Round-robins through the 5 providers. |
| `:rf.causa/copilot-set-redaction` | `[_ settings]` | Merges over defaults; full settings map at once. |
| `:rf.causa/copilot-submit-question` | `[_ {:text :parsed}]` | `event-fx` — appends question, starts streaming-answer turn, routes payload via `:rf.causa.fx/llm-stream`. Per spec §Pull-only model this is the ONLY surface that initiates an outbound LLM call. |
| `:rf.causa/copilot-stream-token` | `[_ token]` | Appends one streamed token to the in-flight answer. |
| `:rf.causa/copilot-stream-end` | `[_]` | Marks the in-flight turn as no-longer streaming. |
| `:rf.causa/copilot-clear-conversation` | `[_]` | Clears the buffer. Per spec §Ephemeral conversation, in-memory only. |
| `:rf.causa/copilot-chip-clicked` | `[_ {:chip-key :value}]` | `event-fx` — resolves the target server-side from the fixed `chip-targets` allowlist in `ai-co-pilot-helpers` and dispatches it with the chip's value as arg. Unknown chip-keys no-op. Per rf2-cm93v the handler accepts only `:chip-key` + `:value`; any caller-supplied `:target` slot is ignored. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.causa.fx/llm-stream` | `{:provider :text :parsed :redaction-settings}` | The provider streaming surface per spec §Provider abstraction. v1 ships a no-op stub; the follow-on bead wires the per-provider fetch + SSE stream parser. The contract — emit tokens via `:rf.causa/copilot-stream-token`, end via `:rf.causa/copilot-stream-end` — is the integration point. |

## Cross-references

The catalogue is reference material; per-panel specs (000–013) are
the normative source for *why* each panel registers what it does.
Cross-reference structure:

- Each panel doc SHOULD link here for "what subs/events this panel
  uses" rather than re-enumerating the registry surface in-line. The
  linking convention is a markdown link to the panel's section in this
  doc (`014-Registry-Catalogue.md#event-detail-panel` and peers).
- This doc cross-refs back to the owning panel spec for *meaning*. The
  panel spec MUST own the panel's semantic contract (sub status
  taxonomy, layout, locks); this doc MUST own the registry's surface
  enumeration. Voice split: panel spec = *why and how*; this doc = *what is named*.

The naming convention itself is owned by
[`008-Embedding-Contract.md` §Registry-key isolation](./008-Embedding-Contract.md#registry-key-isolation-via-rfcausa-prefix);
this doc enumerates what sits inside the namespace.

For consumers reading the buffer (the substrate every composite sub
projects from), see [`013-Trace-Bus.md`](./013-Trace-Bus.md).

For the API surface this catalogue describes from the *outside*
(the consolidated user-facing reference), see
[`API.md`](./API.md). API.md is consumer-facing; this doc is
contributor-facing — the catalogue lets a new agent or human reader
audit the registry surface without grepping the source.
