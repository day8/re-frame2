# 014-Registry-Catalogue

The normative enumeration of every `:rf.xray/*` registration —
subscriptions, events, and effects — that Xray installs into the
process-global registrar. This doc is reference material: an AI agent
or human reader handed only the Xray spec MUST be able to reconstruct
the registry's surface from this catalogue alone, without reading
`tools/xray/src/day8/re_frame2_xray/registry.cljs`.

> **rf2-qy0nu drift notice (2026-05-18) — superseded by the
> rf2-zerqv reconciliation below.** The 8-dead-panel sweep deleted the
> standalone `mcp-server`, `hydration-debugger`, `performance`,
> `schema-violation-timeline`, `effects`, and the dynamic `flows`
> panels from the source tree, along with `time-travel`'s
> panel-private rows. Routing and Schemas were NOT deleted — they
> survive as the Dynamic Routes tab + the Static Routes / Schemas
> catalogue tabs (`static/<name>/panel.cljs`, registered via
> `reg-l4-tab!` with `:modes #{:static}`); their sections below are
> normative. The registry's authoritative shape lives in
> `registry.cljs` +
> `tools/xray/test/day8/re_frame2_xray/registry_cljs_test.cljs`'s
> `all-sub-names` / `all-event-names` / `all-fx-names` enumerations,
> which the test suite asserts every registration matches exactly.
>
> **rf2-zerqv reconciliation (2026-06-04).** The stale per-panel
> sections this notice flagged have now been reconciled against the
> live source: §Schema-violation timeline + §Hydration debugger are
> marked UNBUILT (post-v1) with their absent subs/events called out;
> §Effects + §Performance are marked DROPPED (no live surface);
> §Flows is marked superseded by the Static Flows catalogue (its
> registrations live under §Static mode); and §Views tab is
> re-anchored onto the live `:rf.xray/reactive-data` /
> `:rf.xray/reactive-show-unchanged?` / `:rf.xray/reactive-toggle-unchanged`
> surface. The live panel inventory is the 6 Dynamic tabs
> (Epoch / App-DB / Views / Trace / Machine / Routes) + 5 Static tabs
> (Machines / Routes / Schemas / Flows / Interceptors) per
> `panel_enum.cljc` + the `reg-l4-tab!` call sites.
>
> **rf2-yhl5v follow-up (2026-06-02).** The §Time-travel scrubber
> subsection has been reconciled to the surviving live surface: the
> panel-private pin store, label-input, and confirmed-rewind-failure
> rows that died with the deleted panel (`:rf.xray/last-restore-failure`,
> `:rf.xray/restore-epoch-tick`, `:rf.xray/pin-store`,
> `:rf.xray/pinned-snapshots`, `:rf.xray/time-travel-label-input`,
> `:rf.xray/clear-selected-epoch`, `:rf.xray/pin-current`,
> `:rf.xray/unpin`, `:rf.xray/rename-pin`,
> `:rf.xray/dismiss-pin-overflow-toast`, `:rf.xray/bump-restore-epoch-tick`,
> `:rf.xray/reset-to-pinned`, `:rf.xray/time-travel-set-label-input`, and
> the `:rf.xray.fx/reset-frame-db!` effect) were removed. The
> cross-cutting epoch primitives that outlived the panel
> (`:rf.xray/select-epoch`, `:rf.xray/reset-to-epoch`,
> `:rf.xray/set-target-frame`, `:rf.xray/sync-epoch-history`,
> `:rf.xray/selected-epoch-record`, `:rf.xray.fx/restore-epoch`) remain,
> matched against `registry_cljs_test`'s enumerations. The subsection is
> now normative again.
>
> **rf2-ee38b.2 follow-up (2026-05-23).** The dead `:rf.xray/selected-
> dispatch-id` / `:rf.xray/selected-dispatch-frame` shim subs were
> deleted (zero production consumers; the Epoch panel reads focus off the
> spine `:rf.xray/focus`). Their rows are removed from §Event detail
> below. A full regeneration of this catalogue from `registry_cljs_test`'s
> `all-sub-names` / `all-event-names` / `all-fx-names` enumerations (the
> authoritative live shape) remains the proper fix and is left for the
> mayor — a 581-line blind regen inside a remediation PR carries its own
> drift risk.

The thesis: per [`spec/Conventions.md` §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes)
and [`008-Embedding-Contract.md` §Registry-key isolation via `:rf.xray/*` prefix](./008-Embedding-Contract.md#registry-key-isolation-via-rfxray-prefix),
Xray namespaces every registrar id under `:rf.xray/*` to keep
process-global collisions impossible. The prefix is the contract;
this doc enumerates what sits inside it.

## Naming convention

| Prefix | Used for |
|---|---|
| `:rf.xray/<id>` | Every subscription, every cofx, and every cross-panel event (consumed from ≥2 panels) or shared-infrastructure event (trace-buffer pump, epoch-history pump, etc.). |
| `:rf.xray.<panel>/<id>` | Every panel-internal event — owned by exactly one panel, never dispatched from another. The namespace itself encodes "panel-internal, no cross-panel callers"; renaming the panel renames the namespace. Per the rf2-nmc1f cleanup the issues-ribbon panel uses this convention (`:rf.xray.issues/clear-filters`, `:rf.xray.issues/toggle-severity`, etc.). Other panels MAY adopt the convention as their event surface stabilises. |
| `:rf.xray.fx/<id>` | Every effect (fx). The trailing `.fx/` segment is the canonical effect-id marker — agents grepping for the fx subset MAY use `:rf.xray.fx/` as the discriminator. |

Xray MUST NOT register a handler under any non-`:rf.xray*/` keyword.
A host registering `:user/login` and Xray registering
`:rf.xray/select-tab` cannot stamp on each other; the prefix is the
collision-avoidance contract enforced by code review and the registry
namespace docstring.

The catalogue below groups registrations by **owning panel** (per
[`007-UX-IA.md`](./007-UX-IA.md) §Information architecture). Where a
registration is shared across panels (e.g. `:rf.xray/select-dispatch-id`
is consumed across the event-detail and other panels), it appears once
under its primary owner with cross-panel use noted.

Cross-panel infrastructure (the trace-buffer sub, the target-frame
sub, the epoch-history pump) is enumerated under
[§Shared infrastructure](#shared-infrastructure).

## Idempotency

Every registration is installed inside a `compare-and-set!` idempotency
gate (`register-xray-handlers!`) so shadow-cljs `:after-load` reloads
do NOT re-register. Tests MAY use `reset-for-test!` to drop the
sentinel and drive multiple registration cycles. Production code MUST
NOT call `reset-for-test!`. Per-panel `install!` helpers run inside
the same gate so panel-owned registrations inherit idempotency
without re-doing the dance.

## Shared infrastructure

Subscriptions and events the entire panel set composes against. These
registrations have no single owning panel; they back the trace bus,
the time-travel scrubber, and the per-frame target selection.

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.xray/trace-buffer` | reads `(get db :trace-buffer [])` (populated by the coalesced microtask mirror sync) | Vector of `:rf/trace-event` records, oldest-first (per [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Consumer contract) — the merged snapshot across every registered host frame's ring + Xray's frameless secondary ring. | Layer-1 sub re-fires on every app-db write to `:trace-buffer`. The slot is populated by `trace-collector/refresh-trace-rings!` — production drives via microtask-coalesced mirror sync (one dispatch per JS tick regardless of trace volume); tests drive synchronously via the same entrypoint (per the rf2-3g9nw D3=b ruling). Per rf2-43koh — supersedes the rf2-e9s81 atom-thunk fall-through; the framework's per-frame rings own the data plane now. |
| `:rf.xray/suppressed-sensitive-count` | `db` (reads `:suppressed-counters`) | Integer — total suppressed `:sensitive? true` events under the current `:rf.privacy/show-sensitive?` setting. | On `db` write to `:suppressed-counters` (rf2-0vxdn — reactive immediate update of the `[● REDACTED N]` bottom-rail indicator). |
| `:rf.xray/target-frame` | `db` | Keyword frame-id (default `:rf/default`). | On `db` write to `:target-frame`. |
| `:rf.xray/epoch-history` | `db` | Vector of `:rf/epoch-record`, oldest-first (cached snapshot of `(rf/epoch-history target)`). | On `:rf.xray/epoch-recorded` dispatch. |
| `:rf.xray/target-frame-db` | `:rf.xray/target-frame`, `:rf.xray/epoch-history` | The host frame's current `app-db` value (via `rf/app-db-value`). | Every settled epoch on the target frame. |
| `:rf.xray/cascades` | `:rf.xray/trace-buffer` | Vector of grouped cascade entries (per `projection/group-cascades`). Shared substrate for any panel that needs the cascade grouping without re-projecting (`:rf.xray/event-detail`, etc. declare the dep via `:<-` so the projection runs once per buffer change). | On `:rf.xray/trace-buffer` recompute. |

### Events

| Event | Vector shape | Returns | Notes |
|---|---|---|---|
| `:rf.xray/epoch-recorded` | `[_ frame-id]` | `{:db ...}` | Pumped from the epoch-cb registered in `preload.cljs` on every settled epoch. Re-reads `rf/epoch-history` to keep the cached snapshot consistent. No-ops when `frame-id` ≠ the current target. |
| `:rf.xray/note-sensitive-suppressed` | `[_ frame-id]` | `{:db ...}` | rf2-0vxdn — bumps `[:suppressed-counters (or frame-id :global)]` in Xray's app-db. Dispatched from `trace-collector/collect-trace!` (CLJS) when the privacy gate drops a `:sensitive? true` event. Drives the `:rf.xray/suppressed-sensitive-count` sub reactively. |
| `:rf.xray/reset-suppressed-counters` | `[_]` or `[_ frame-id]` | `{:db ...}` | rf2-0vxdn — clears all buckets (no arg) or just the named bucket. Dispatched from `trace-collector/retroactive-scrub!` (CLJS) — the wholesale clear (privacy toggle-off, Settings clear, palette clear) drops the `[● REDACTED N]` indicator state alongside the rings. |
| `:rf.xray/clear-trace-buffer` | `[_]` | `{:db ...}` | `:rf.trace/no-emit? true`. Drops the `:trace-buffer` slot. Dispatched from `trace-collector/retroactive-scrub!` (CLJS) when the user clears the rings or the privacy gate transitions true → false. |
| `:rf.xray/sync-trace-buffer` | `[_ buffer]` | `{:db ...}` | `:rf.trace/no-emit? true`. Wholly replaces the `:trace-buffer` slot with the supplied buffer vector. Dispatched from `trace-collector/refresh-trace-rings!` — the microtask-coalesced production path snapshots the framework's per-frame rings + Xray's frameless secondary ring on every tick; tests drive the same entrypoint synchronously for deterministic ordering. |

### Callback identifiers

Not subs/events/fxs — these are the keyword ids Xray registers
with the framework's instrumentation surfaces in `preload.cljs`.
They live under the `:rf.xray/*` namespace for the same isolation
discipline as the rest of the registry.

| Id | Surface | Behaviour |
|---|---|---|
| `:rf.xray/trace-collector` | `rf/register-listener!` | Xray's trace consumer listener. Drops self-noise (`:frame :rf/xray`), applies the privacy gate, pushes frameless events into Xray's secondary ring, and requests a coalesced microtask sync into `:rf/xray`'s `:trace-buffer` slot — the framework's per-frame rings own the frame-bound data plane (per rf2-43koh). Idempotent per preload installation. |
| `:rf.xray/epoch-collector` | `rf/register-epoch-listener!` | Xray's epoch-settle pump. Dispatches `:rf.xray/epoch-recorded` per settled epoch so the cached `:rf.xray/epoch-history` snapshot stays consistent with `(rf/epoch-history target)`. Short-circuits when Xray is not mounted. |

## Cross-panel focused-cascade primitives (relocated from the retired event-detail panel · rf2-5gl5r)

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §The default landing view.
rf2-5gl5r retired the Event/Handler panel in favour of the Epoch
panel ([`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
§9.1). The composite sub + spine-shim events listed below were
**relocated** from the deleted `panels/event_detail.cljs` to
`registry.cljs` as cross-panel primitives — multiple consumers
(the trace panel's status bar, machine-inspector, cancellation-cascade,
the unit-test corpus) read them and they outlived the panel that
originally owned them. (rf2-nugvv removed `share.cljs`'s cascade-export,
which was also a consumer.)

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.xray/event-detail` | `:rf.xray/cascades`, `:rf.xray/focus` | `{:cascades [...] :selected-dispatch-id ... :selected-dispatch-frame ... :selected-cascade ...}` — composite. Derives the focused cascade off the spine `:rf.xray/focus` (rf2-ee38b.2 removed the standalone `:rf.xray/selected-dispatch-id` / `-frame` shim subs — focus is the single source of truth; the `:selected-dispatch-id`/`-frame` KEYS in this composite's return map remain live). `:selected-cascade` is `nil` when no selection OR when the id is no longer in the buffer. | Cascades or focus change. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-dispatch-id` | `[_ dispatch-id]` | Sets selection (writes through the spine via `spine/focus-cascade-reducer`). |
| `:rf.xray/clear-selected-dispatch-id` | `[_]` | Drops selection (resets spine focus to LIVE per rf2-s0s5x Phase A). |

## Time-travel scrubber

Spec: [`002-Time-Travel.md`](./002-Time-Travel.md).

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/selected-epoch-record` | `:rf/epoch-record` or `nil`. | Resolved from history + the spine focus epoch (`:rf.xray/focus-epoch-id`). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-epoch` | `[_ epoch-id]` | Passive scrub — does NOT call `restore-epoch`. Spine shim (rf2-adve5): stamps the spine `[:focus :epoch-id]` slot surfaced by `:rf.xray/focus-epoch-id`; a `nil` epoch-id clears the focus. |
| `:rf.xray/reset-to-epoch` | `[_ frame epoch-id]` | `event-fx` — emits `{:fx [[:rf.xray.fx/restore-epoch {:frame frame :epoch-id epoch-id}]]}`. The confirmed-rewind affordance (rf2-hga49); a nil frame / epoch-id is a guarded no-op. |
| `:rf.xray/reset-flash-failed` | `[_]` | `:rf.trace/no-emit? true`. Sets the inline `:reset-flash` failure notice; dispatched from `:rf.xray.fx/restore-epoch` when `rf/restore-epoch` returns false. |
| `:rf.xray/set-target-frame` | `[_ frame-id]` | Sets the active target frame for the scrubber and refreshes `:epoch-history` from `(rf/epoch-history target)`. `nil` resets to the default target. Mirrored by `core/set-target-frame!` from the public CLJS API. |
| `:rf.xray/sync-epoch-history` | `[_ history]` | `:rf.trace/no-emit? true`. Replaces the cached `:epoch-history` with the supplied vector AND focuses the LATEST seeded epoch — stamps the spine `[:focus :epoch-id]` (surfaced by `compose-focus` when no live cascade head is present) to `(:epoch-id (peek history))`; an empty `history` clears it. Pumped from the depth-shrink path so the scrubber reflects the trimmed history without an explicit re-read, and from history-only seeds (the panel-gallery Story variants) where no trace buffer exists for the trace-driven auto-follow to act on — without the head-focus the focus-keyed Dynamic panels (App-db, Epoch, …) would render their "nothing focused" empty-state (rf2-mdpfz). When a live trace buffer IS also seeded, `compose-focus`'s LIVE auto-follow re-derives `:epoch-id` from the head cascade; this stamp is authoritative only for history-only seeds. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/restore-epoch` | `{:frame :epoch-id}` | Calls `rf/restore-epoch`; on failure dispatches `:rf.xray/reset-flash-failed` so the inline tab-ribbon flash surfaces the failed confirmed rewind (the framework also emits a structured `:rf.epoch/*` trace row the Trace panel shows). The fx indirection lets test fixtures stub the framework call. |

## App-DB Diff panel

Spec: [`004-App-DB-Diff.md`](./004-App-DB-Diff.md).

Slice-centric `app-db` inspector. Reads the host frame's `app-db` via
`rf/app-db-value` + the target-frame's epoch-history; produces the
`[op path before after]` diff triples the view consumes.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/selected-epoch-diff` | Diff triples for the selected (or newest) epoch. Composite over history + selection. |
| `:rf.xray/focused-slice-path` | The "Show me when this changed" focused path, or `nil`. |
| `:rf.xray/show-me-when-this-changed-result` | Vector of epoch hit-maps for epochs touching the focused path. `[]` when no focus. |
| `:rf.xray/app-db-diff` | Composite — `{:target-frame :history-empty? :changed-non-reserved :changed-reserved :focused-path :focused-hits :redacted-modified-count}`. The `[runtime]` group always renders current `:rf/*` slot contents per spec §Reserved-keys group. |
| `:rf.xray/segment-inspector-open?` | rf2-e9tb0 — true iff the segment-inspector popup is open. |
| `:rf.xray/segment-inspector-path` | rf2-e9tb0 — the inspected path (vector), or `nil` when closed. |
| `:rf.xray/segment-inspector-value` | rf2-e9tb0 — the current value at the inspected path against the target-frame db. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/focus-slice-path` | `[_ path]` | Sets the "Show me when this changed" focused path. |
| `:rf.xray/clear-slice-focus` | `[_]` | Drops the focus. |
| `:rf.xray/copy-value-to-clipboard` | `[_ value]` | `event-fx` — emits `{:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str value)}]]}`. |
| `:rf.xray/copy-path-to-clipboard` | `[_ path]` | `event-fx` — same shape, `pr-str path`. |
| `:rf.xray/open-segment-inspector` | `[_ path]` | rf2-e9tb0 — opens the segment-inspector popup at `path` (vector). |
| `:rf.xray/close-segment-inspector` | `[_]` | rf2-e9tb0 — closes the popup. |

> **rf2-e9tb0 — pinned-slices removed.** The `:rf.xray/pin-slice`,
> `:rf.xray/unpin-slice`, `:rf.xray/reorder-pinned-slices` events
> and the `:rf.xray/pinned-slices-store` + `:rf.xray/pinned-slices`
> subs were dropped when the pinned-watches strip was superseded by
> the segment-inspector popup. Catalogued here for the audit trail.

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/copy-to-clipboard` | `{:text <string>}` | Best-effort write via `navigator.clipboard.writeText`. No-op on non-browser targets (Node test, JVM). |

## Schema-violation timeline — UNBUILT (post-v1)

Spec: [`005-Schema-Timeline.md`](./005-Schema-Timeline.md) (§Affordance
v1-status note).

**No registrations exist.** The standalone schema-violation timeline
panel is unbuilt — `git grep` across `tools/xray/src` returns zero hits
for every sub/event the original design named
(`:rf.xray/schema-violation-timeline`, `…/schema-timeline-window`,
`…/schema-violations-window`, `…/schema-timeline-prev-rows`,
`…/selected-violation-id`, `…/schema-filter`, `…/select-violation`,
`…/set-schema-filter`, `…/registered-schemas`,
`…/clear-violation-selection`, `…/set-schema-timeline-window`). They
are NOT in `registry.cljs` / `registry_cljs_test`'s enumerations.

**Today** schema-fail surfacing ships inline in the Epoch panel
(rf2-kt6js) and as the Static Schemas registry catalogue
(`static/schemas/panel.cljs`, registrations enumerated under §Static
mode below). When the timeline lands post-v1 its registrations get
catalogued here against the then-live source.

## Hydration debugger — UNBUILT (post-v1; renderer-only)

Spec: [`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md)
(§Affordance v1-status note).

**No registrations exist.** The hydration-mismatch bisector panel is
unbuilt — there is no Hydration L4 tab in the live inventory
(`panel_enum.cljc` + the `reg-l4-tab!` call sites), and `git grep`
across `tools/xray/src` returns zero hits for every sub/event the
original design named (`:rf.xray/hydration-debugger-data`,
`…/hydration-has-mismatch?`, `…/hydration-reroot-path`,
`…/selected-mismatch-id`, `…/select-mismatch`,
`…/clear-mismatch-selection`, `…/reroot-tree-view`). The only present
artefact is a pure per-pane element-diff renderer
(`panels/hydration_pane_render.cljs`) that no panel mounts. None of the
above are in `registry.cljs` / `registry_cljs_test`'s enumerations.

(`:rf.xray/open-in-editor` — the shared jump-to-source event the
original design listed here — IS live, but it belongs to the shared
source-coord wiring, not this panel; it is catalogued under §Shared
infrastructure / the panels that use it.)

## Views tab (reactive perspective)

Spec: [`021-Dynamic-Panel-Designs.md` §3](./021-Dynamic-Panel-Designs.md#3-the-view-panel-reactive-perspective--steps-7-8)
(the normative Views design; [`012-Views.md`](./012-Views.md) is
superseded historical exploration). The Views tab renders the focused
cascade's reactive perspective.

**Re-anchored (rf2-zerqv).** The pre-rewrite Subscriptions-panel family
this section used to list — `:rf.xray/subscriptions-data`,
`…/select-sub`, `…/clear-selected-sub`, `…/sub-cache`, `…/sub-filters`,
`…/sub-chain-open?`, `…/show-invalidation-chain`,
`…/hide-invalidation-chain`, `…/set-sub-cache-override-for-test` —
no longer exists (zero hits in `tools/xray/src`). The live Views/
Reactive panel registers exactly the ids below
(`reactive_panel_subs.cljs` + `reactive_panel_events.cljs`).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/reactive-data` | Composite the view reads — `:<-` over `:rf.xray/focus` + `:rf.xray/epoch-history`; projects the focused cascade's reactive perspective: `{:focus :frame :dispatch-id :triggered-by :seed-paths :has-cascade?}` merged with the per-record projection (L1 / L2+ sub partition, inputs + code columns). JVM/empty-focus paths degrade to the empty projection. |
| `:rf.xray/reactive-show-unchanged?` | Boolean — panel-local UI toggle reading `:reactive/show-unchanged?` off Xray's db (whether unchanged subs render). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/reactive-toggle-unchanged` | `[_]` | Flips the `:reactive/show-unchanged?` UI flag. |

## Issues ribbon

Spec: [`000-Vision.md` L94](./000-Vision.md), [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-namespace-convention--five-prefix-shapes).

Unified feed across errors, warnings, schema violations, hydration
mismatches. Filter axes: `:severities`, `:prefixes`, `:since-ms`. Each
axis independent; empty / `nil` disables the axis.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/issues-filters` | `{:severities :prefixes}` — single read for atomic re-render. |
| `:rf.xray/issues-ribbon` | Composite — `{:issues :total :rendered :severity-counts :distinct-prefixes :filters :epoch-id :empty-kind}` over the focused epoch's `:trace-events`. `:empty-kind` ∈ `#{:no-focus :epoch-evicted :no-issues :no-matches nil}` per spec/021 §8 + §10.7 (rf2-jio48). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray.issues/toggle-severity` | `[_ severity]` | Toggles a severity chip in/out. |
| `:rf.xray.issues/toggle-prefix` | `[_ prefix]` | Toggles a prefix chip in/out. |
| `:rf.xray.issues/clear-filters` | `[_]` | Clears every chip axis. |

## Flows panel — superseded by the Static Flows catalogue

Spec consumer: framework Spec 013 (registered-flow surface) + Spec 009
(`:rf.flow/*` trace vocabulary).

**The dynamic Flows panel was dropped.** Its sub/event family —
`:rf.xray/flows-data`, the dynamic `:rf.xray/registered-flows` sub,
`…/flow-trace-events`, `…/selected-flow-id`, `…/select-flow-id`,
`…/clear-flow-selection`, `…/set-registered-flows-override-for-test` —
no longer exists (zero hits in `tools/xray/src`). The live Flows
surface is the **Static catalogue** (`static/flows/panel.cljs`),
registered as an `:modes #{:static}` L4 tab and namespaced under
`:rf.xray.static.flows/*` (`…/query`, `…/set-query`, `…/tab-data`,
`…/registered-flows`, `…/registered-flows-override`,
`…/set-registered-flows-override-for-test`), one of the Static
sub-tabs alongside Machines / Routes / Schemas / Interceptors (see
§Static mode below). (The bare `:rf.xray/registered-flows` string that
remains in `static/flows/panel.cljs` is a doc comment describing the
underlying `(rf/registrations :flow)` read, not a registered sub.)

## Effects panel — DROPPED (no live surface)

**No Effects tab exists.** The dynamic Effects panel was dropped; its
sub/event family — `:rf.xray/effects-data`, `…/registered-fxs`,
`…/fx-trace-events`, `…/selected-fx-id`, `…/select-fx-id`,
`…/clear-fx-selection`, `…/set-registered-fxs-override-for-test` — no
longer exists (zero hits in `tools/xray/src`) and there is no Effects
L4 tab in the live inventory (`panel_enum.cljc` + the `reg-l4-tab!`
call sites). The shipped managed-effects surface is the inline
`mount-managed-fx!` list (`panel_enum.cljc` `:managed-fx` entry) and
the per-cascade fx rows in the Epoch/Trace panels — not a standalone
Effects panel. Interceptors get their own Static sub-tab; see §Static
mode.

## Performance panel — DROPPED (use Chrome DevTools)

**No Performance panel exists.** The panel was explicitly dropped (see
[`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) §Performance —
"Use Chrome DevTools"); its subs/events —
`:rf.xray/performance-data`, `…/performance-budget-ms`,
`…/set-performance-budget-ms` — no longer exist (zero hits in
`tools/xray/src`) and there is no Performance L4 tab in the live
inventory.

## Trace panel

Epoch-scoped raw-event ribbon (rf2-td380): reads the focused epoch
record's `:trace-events` (the complete domino trail for one event —
both the synchronous event-side rows AND the async nil-dispatch-id
reactive rows) via `:rf.xray/focus` + `:rf.xray/epoch-history`,
resolved through `panels.shared.focus-resolver`. No chip-filtering
(rf2-gkczt) and no top header row (rf2-o6yqq) — the focused epoch IS
the scope, and the per-row payload-expand affordance is the drill-down.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/trace-feed` | Composite over the focused epoch's `:trace-events` — `{:rows :total :rendered :epoch-id :empty-kind}`. `:empty-kind` ∈ `#{:no-focus :epoch-evicted :no-events nil}`. |
| `:rf.xray/trace-expanded-row-ids` | The set of trace-row ids whose inline payload is expanded (spec/021 §5.4). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/toggle-trace-row-expand` | `[_ row-id]` | Toggle a row's inline payload expansion membership. |
| `:rf.xray/clear-trace-expand` | `[_]` | Drop every expanded trace-row id. |

## Routes panel

Spec: [`spec/012-Routing.md`](../../../spec/012-Routing.md) (framework
substrate) + [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md)
§Routes tab (Xray-side lens). Surfaces registered routes as a flat
catalogue, the active `:rf/route` slice, and per-focused-event
FROM/TO markers. Search + Simulate-URL drive the interactive
surface; the previous URL-path-segmentation tree was dropped per
rf2-lq0ef (audit verdict B).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-routes` | `(rf/registrations :route)`. Test override via `:registered-routes-override`. |
| `:rf.xray/registered-routes-override` | Test override slot. |
| `:rf.xray/current-route-slice` | The `:rf/route` slot off the target-frame's `app-db`. |
| `:rf.xray/current-route-slice-override` | Test override slot. |
| `:rf.xray/routing-tab-data` | View-facing composite per `routing_helpers/project-data` — `{:silent? :routes :total-routes :filtered? :current :from-id :to-id :navigated? :query :sim-url :sim-result}`. |
| `:rf.xray.routing/query` | Substring search input value. |
| `:rf.xray.routing/sim-url` | Simulate-URL input value. |
| `:rf.xray.routing/expanded` | Set of route-ids whose meta-expander is open. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray.routing/set-query` | `[_ s]` | Sets the substring search filter; blank clears. |
| `:rf.xray.routing/set-sim-url` | `[_ s]` | Sets the Simulate-URL input; blank clears. |
| `:rf.xray.routing/toggle-row` | `[_ route-id]` | Toggles the meta-expander for a row. |
| `:rf.xray/set-registered-routes-override-for-test` | `[_ ov]` | Test-only override hook. |
| `:rf.xray/set-current-route-slice-override-for-test` | `[_ ov]` | Test-only slice override. |

## Machine inspector

Spec: [`003-Machine-Inspector.md`](./003-Machine-Inspector.md). Reads
`(rf/machines)`, the live `:rf/machine` snapshots, and the
trace-buffer's `:rf.machine/transition` slice. Read-only at v1.
(rf2-nugvv removed the panel's Share affordance; source-coord jumps
remain deferred.)

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-machines` | Vector of machine-ids from `(rf/machines)`. Wrapped in `try` so future API changes collapse to `[]` rather than throwing. Test override via `:registered-machines-override`. |
| `:rf.xray/machine-snapshots` | `{machine-id <snapshot>}` map from the target-frame's `:rf/machines` slot. |
| `:rf.xray/machine-snapshots-override` | Test override hook. |
| `:rf.xray/selected-machine-id` | Machine-id or `nil` (composite defaults to first row). |
| `:rf.xray/machine-inspector-data` | Composite — `{:machines :total :selected-id :selected :chart-props :transitions :empty-kind}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-machine-id` | `[_ machine-id]` | Sets selection. |
| `:rf.xray/clear-machine-selection` | `[_]` | Clears selection. |
| `:rf.xray/set-registered-machines-override-for-test` | `[_ ov]` | Test-only override hook. |
| `:rf.xray/set-machine-snapshots-override-for-test` | `[_ ov]` | Test-only override hook. |

## Static mode

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §Static mode +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface
architectural section. Static mode is unconditionally available
(per rf2-8l3uk — the prior `:rf.xray/static-mode?` feature gate
was removed). The mode pill mounts at ribbon-left, `Cmd-Shift-M` /
`Ctrl-Shift-M` toggles between Dynamic and Static surfaces, and the
selected mode + sub-tab persist to localStorage. Per rf2-o5f5f.1 +
rf2-o5f5f.2 + rf2-o5f5f.3 + rf2-ybjkx + rf2-8l3uk.

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/mode` | `:dynamic` / `:static`. | Default `:dynamic`. Hydrated from `xray.mode` localStorage on boot. |
| `:rf.xray.static/selected-tab` | Keyword sub-tab id — one of the live Static tabs (`:machines` / `:routes` / `:schemas` / `:flows` / `:interceptors`, registered via `reg-l4-tab!` with `:modes #{:static}`). | Default `:machines` per `static/shell.cljs`. |
| `:rf.xray.static.machines/selected-id` | Selected machine-id keyword for the Static Machines master-detail. | `nil` until first selection; persisted via the Static Machines persistence fx. |
| `:rf.xray.static.machines/sub-mode` | Effective sub-mode keyword for the focused machine (`:topology` / `:sim` / `:instances` / `:cascade`). | Composite of `:rf.xray.static.machines/sub-mode-by-id` + the focused machine-id; default `:topology`. |
| `:rf.xray.static.routes/expanded-id` | Set of route-ids whose meta-expander is open (per-row inline expand surface in the Static Routes panel). | Default `#{}`; sourced from the Static Routes panel's UI-state slot. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-mode` | `[_ mode]` | Sets `:rf.xray/mode` to `:dynamic` / `:static` and fires `:rf.xray.static/persist-mode` so the selection round-trips through localStorage. |
| `:rf.xray/toggle-mode` | `[_]` | Flips between `:dynamic` and `:static`. The `Cmd-Shift-M` / `Ctrl-Shift-M` chord dispatches this. |
| `:rf.xray.static/select-tab` | `[_ tab-id]` | Selects a Static sub-tab. Persists via the Static persistence fx. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.static/persist-mode` | `mode` keyword | Writes the bare string (`"dynamic"` / `"static"`) to localStorage key `xray.mode`. No-ops on JVM / when localStorage is unavailable. |

## Command palette

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §Command palette. Per
rf2-ybjkx / PR #1572 the palette extensions ship six new verbs, a
mode-aware command index (the palette's source list filters by
`:rf.xray/mode`), and a recents slot that boosts the most-recently-
invoked commands to the head of the result list (top-3 persisted to
localStorage).

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray.palette/open?` | Boolean — palette dialog mounted? | Toggled by the `Cmd-K` / `Ctrl-K` chord. |
| `:rf.xray.palette/recents` | Vector of command-ids in MRU order, capped at 3. | Persisted under localStorage key `re-frame2.xray.palette.recents.v1`. Lazy-seeded from localStorage on first open. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray.palette/open` | `[_]` | Mounts the dialog + focuses the input. |
| `:rf.xray.palette/close` | `[_]` | Unmounts the dialog. |
| `:rf.xray.palette/invoke` | `[_ command-id]` | Fires the verb's dispatch + records the id into recents + persists the recents slot. |

### Command-item names (the 6 new verbs landed by rf2-ybjkx)

| Command-id | Mode filter (`:modes`) | Behaviour |
|---|---|---|
| `:toggle-theme` | `#{:dynamic :static}` | Flips the Settings `:theme` slot between `:dark` and `:light` via `:rf.xray/settings-update`. |
| `:toggle-reduced-motion` | `#{:dynamic :static}` | Flips the user-override reduced-motion axis (rides the axis-3 of theme/density/motion in §Settings). |
| `:snapshot-db` | `#{:dynamic}` | Pins the current target-frame's app-db snapshot via `:rf.xray/pin-current`. |
| `:clear-epoch` | `#{:dynamic}` | Clears the framework's per-frame rings + Xray's frameless secondary ring + the epoch history via `trace-collector/retroactive-scrub!`. |
| `:toggle-mode` | `#{:dynamic :static}` | Dispatches `:rf.xray/toggle-mode` (the Cmd-Shift-M chord's verb form, surfaced as a palette entry for discoverability). |
| `:jump-to-settings` | `#{:dynamic :static}` | Opens the Settings popup. |

The `:modes` filter is the normative convention for palette command
authoring: a command's `:modes` set MUST include every mode in which
the command should appear in the palette's result list. Commands
without a `:modes` slot default to `#{:dynamic :static}` (both modes).

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
[`008-Embedding-Contract.md` §Registry-key isolation](./008-Embedding-Contract.md#registry-key-isolation-via-rfxray-prefix);
this doc enumerates what sits inside the namespace.

For consumers reading the buffer (the substrate every composite sub
projects from), see [`013-Trace-Consumer.md`](./013-Trace-Consumer.md).

For the API surface this catalogue describes from the *outside*
(the consolidated user-facing reference), see
[`API.md`](./API.md). API.md is consumer-facing; this doc is
contributor-facing — the catalogue lets a new agent or human reader
audit the registry surface without grepping the source.

## Vision — per-id metadata for golden-path navigation

**Bug class:** "I'm reading an unfamiliar Xray codebase; I see
`:rf.xray/cascades` in the source; what's its shape? where is it
registered? what consumes it?"

Today the catalogue enumerates names + roles. The next-step affordance
is **per-id metadata stamped at registration**:

- **Source coords** — every `reg-sub` / `reg-event-*` / `reg-fx`
  registration carries a `:source-coord` stamp (per Spec 001 + 006).
  Xray's own registrations should expose theirs through this
  catalogue so a human reader can jump directly to the registration
  site.
- **Version stamps** — when the registration shape changes (new input
  sub, removed output key), bump a per-id version. This catalogue
  surfaces the version alongside the id; downstream consumers can
  audit "is my code calling the v1 or v2 shape?"
- **Dependency arrows** — for composite subs, surface the input subs
  inline so the catalogue reads as a topology, not a flat list.

The catalogue grows from "reference list of names" to **"navigable
golden-path map of Xray's internal registry"** — a contributor
opening the spec can trace any path from a panel's high-level
behaviour all the way down to the source file where the registration
lives, in one click each step.
