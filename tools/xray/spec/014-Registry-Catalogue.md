# 014-Registry-Catalogue

The normative enumeration of every `:rf.xray/*` registration —
subscriptions, events, and effects — that Xray installs into the
process-global registrar. This doc is reference material: an AI agent
or human reader handed only the Xray spec MUST be able to reconstruct
the registry's surface from this catalogue alone, without reading
`tools/xray/src/day8/re_frame2_xray/registry.cljs`.

> **rf2-qy0nu drift notice (2026-05-18).** The 8-dead-panel sweep
> deleted `mcp-server`, `hydration-debugger`, `performance`, `routes`,
> `schema-violation-timeline`, `effects`, `flows`, and `time-travel`
> from the source tree. Their per-panel sections below (and several
> cross-panel entries that lived inside those panels' install! fns) are
> now stale. The registry's authoritative shape lives in `registry.cljs`
> + `tools/xray/test/day8/re_frame2_xray/registry_cljs_test.cljs`'s
> `all-sub-names` / `all-event-names` / `all-fx-names` enumerations,
> which the test suite asserts every registration matches exactly.
> Rewriting this catalogue against the surviving surface is tracked
> separately; until then, treat any §MCP server / §Routes / §Schemas /
> §Hydration / §Effects / §Flows / §Performance subsection as historical
> reference, not normative spec.
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

### Production registration carries no test seams (rf2-e8330v / xxo3zz F3)

`register-xray-handlers!` (and the per-panel `install!` helpers it
calls) registers **no id ending in `-for-test`** and **no `*-override`
reader sub**. The production data subs read their live source directly —
they carry no `(or override …)` branch. This keeps the test-only
override surface off the public dispatch / subscribe contract.

The override seam is split out per panel as
`<panel-ns>/install-test-overrides!`, orchestrated by the test-only
`day8.re-frame2-xray.test-support/install-test-overrides!`. Each per-
panel `install-test-overrides!`:

1. registers the panel's `:rf.xray*/set-*-override-for-test` events +
   companion `*-override` subs (plus the Machine Inspector's
   `:rf.xray/set-epoch-history-for-test` /
   `:rf.xray/set-focus-epoch-id-for-test` SEEDING events, which write
   the real `:epoch-history` / `:focus` slots), and
2. RE-registers the affected production data subs to layer the override
   read on top (`(or override (real …))`) — re-frame's registrar
   replaces in place.

A test (or a feature-gate dev testbed that injects synthetic state via
those events) opts in by calling `install-test-overrides!` AFTER
`register-xray-handlers!`. The production-vs-seam split is asserted by
`registry_cljs_test.cljs`:
`production-registration-installs-no-for-test-ids` (no `-for-test`
events, no `*-override` subs after production registration) and
`test-seam-installs-exactly-the-override-surface` (the seam installs
exactly the `test-override-sub-names` / `test-override-event-names`
snapshot). The shared value-source `defn`s the production sub and the
seam's override sub both call (e.g. `routing/registered-routes-value`)
keep the projection logic single-sourced.

## Shared infrastructure

Subscriptions and events the entire panel set composes against. These
registrations have no single owning panel; they back the trace bus,
the time-travel scrubber, and the per-frame target selection.

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.xray/trace-buffer` | reads `(get db :trace-buffer [])` (populated by the coalesced microtask mirror sync) | Vector of `:rf/trace-event` records, oldest-first (per [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Consumer contract) — the merged snapshot across every registered host frame's ring + Xray's frameless secondary ring. | Layer-1 sub re-fires on every app-db write to `:trace-buffer`. The slot is populated by `trace-collector/refresh-trace-rings!` — production drives via microtask-coalesced mirror sync (one dispatch per JS tick regardless of trace volume); tests drive synchronously via the same entrypoint (per the rf2-3g9nw D3=b ruling). Per rf2-43koh — supersedes the rf2-e9s81 atom-thunk fall-through; the framework's per-frame rings own the data plane now. |
| `:rf.xray/suppressed-sensitive-count` | `db` (reads `:suppressed-counters`) | Integer — total suppressed `:sensitive? true` events under the current local-render egress profile (`:rf.xray/egress-profile`). | On `db` write to `:suppressed-counters` (rf2-0vxdn — reactive immediate update of the `[● REDACTED N]` bottom-rail indicator). |
| `:rf.xray/target-frame` | `db` | Keyword frame-id (default `:rf/default`). | On `db` write to `:target-frame`. |
| `:rf.xray/epoch-history` | `db` | Vector of `:rf/epoch-record`, oldest-first (cached snapshot of `(rf/epoch-history target)`). | On `:rf.xray/epoch-recorded` dispatch. |
| `:rf.xray/target-frame-db` | `:rf.xray/target-frame`, `:rf.xray/epoch-history` | The host frame's current `app-db` value (via `rf/app-db-value`). | Every settled epoch on the target frame. |
| `:rf.xray/cascades` | `:rf.xray/trace-buffer` | Vector of grouped cascade entries (per `projection/group-by-event`). Shared substrate for any panel that needs the cascade grouping without re-projecting (`:rf.xray/focused-cascade-detail`, etc. declare the dep via `:<-` so the projection runs once per buffer change). | On `:rf.xray/trace-buffer` recompute. |

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

## Cross-panel focused-cascade primitives (relocated from the retired event-detail panel · rf2-5gl5r; sub renamed off the retired-panel name · rf2-7ed9ms)

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
| `:rf.xray/focused-cascade-detail` | `:rf.xray/cascades`, `:rf.xray/focus` | `{:cascades [...] :selected-dispatch-id ... :selected-dispatch-frame ... :selected-cascade ...}` — composite. **rf2-7ed9ms** renamed this sub from `:rf.xray/event-detail` (retired-panel vocabulary) to the behaviour name `:rf.xray/focused-cascade-detail`: it means "the focused cascade's detail record", not "the Event Detail panel's data". Derives the focused cascade off the spine `:rf.xray/focus` (rf2-ee38b.2 removed the standalone `:rf.xray/selected-dispatch-id` / `-frame` shim subs — focus is the single source of truth; the `:selected-dispatch-id`/`-frame` KEYS in this composite's return map remain live). `:selected-cascade` is `nil` when no selection OR when the id is no longer in the buffer. | Cascades or focus change. |

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
| `:rf.xray/reset-flash-failed` | `[_]` | `:rf.trace/no-emit? true`. Sets the inline `:reset-flash` failure notice; dispatched from `:rf.xray.fx/restore-epoch` when `rf/restore-epoch!` returns false. |
| `:rf.xray/set-target-frame` | `[_ frame-id]` | Sets the active target frame for the scrubber and refreshes `:epoch-history` from `(rf/epoch-history target)`. `nil` resets to the default target. Mirrored by `core/set-target-frame!` from the public CLJS API. |
| `:rf.xray/sync-epoch-history` | `[_ history]` | `:rf.trace/no-emit? true`. Replaces the cached `:epoch-history` with the supplied vector AND focuses the LATEST seeded epoch — stamps the spine `[:focus :epoch-id]` (surfaced by `compose-focus` when no live cascade head is present) to `(:epoch-id (peek history))`; an empty `history` clears it. Pumped from the depth-shrink path so the scrubber reflects the trimmed history without an explicit re-read, and from history-only seeds (the panel-gallery Story variants) where no trace buffer exists for the trace-driven auto-follow to act on — without the head-focus the focus-keyed Dynamic panels (App-db, Epoch, …) would render their "nothing focused" empty-state (rf2-mdpfz). When a live trace buffer IS also seeded, `compose-focus`'s LIVE auto-follow re-derives `:epoch-id` from the head cascade; this stamp is authoritative only for history-only seeds. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/restore-epoch` | `{:frame :epoch-id}` | Calls `rf/restore-epoch!`; on failure dispatches `:rf.xray/reset-flash-failed` so the inline tab-ribbon flash surfaces the failed confirmed rewind (the framework also emits a structured `:rf.epoch/*` trace row the Trace panel shows). The fx indirection lets test fixtures stub the framework call. |

## App-DB Diff panel

Spec: [`004-App-DB-Diff.md`](./004-App-DB-Diff.md).

Current-state `app-db` inspector (rf2-okvit). Reads the observed
frame's `app-db` via `rf/app-db-value` + the target-frame's
epoch-history; the body shows the focused epoch's `:db-after`
(per-epoch delta, rf2-02j4r) sectioned by reserved `:rf/*` area.

> **rf2-p53m2 — dead diff-sub family pruned.** The `:rf.xray/selected-
> epoch-diff` → `:rf.xray/app-db-diff` composite (plus its
> `:rf.xray/selected-epoch-flow-writes` /
> `:rf.xray/selected-epoch-redacted-modified-count` inputs) had no
> production view consumer and was removed. The Epoch panel's `:db`
> diff reads `:rf.xray/selected-epoch-record`; the MCP `get-app-db-diff`
> tool projects directly through `diff.engine/project`. The catalogue
> rows are gone; this note is the audit trail.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/selected-epoch-record` | The focused epoch's `:rf/epoch-record` (the Epoch panel's `:db` diff source), or `nil`. |
| `:rf.xray/focused-slice-path` | The "Show me when this changed" focused path, or `nil`. |
| `:rf.xray/show-me-when-this-changed-result` | Vector of epoch hit-maps for epochs touching the focused path. `[]` when no focus. |
| `:rf.xray/app-db-current+diff` | Atomic `{:value :before :epoch-id}` — the focused epoch's `:db-after` / `:db-before` / id (cold boot → `:value` falls back to the live db, `:before`/`:epoch-id` nil). The app-db tab's primary read-model (rf2-yng0y / rf2-02j4r). |
| `:rf.xray/app-db-state` | Current-state section model derived from `:rf.xray/app-db-current+diff` — TOP user-domain section + one section per reserved `:rf/*` area, with the focused epoch's `:db-before` threaded as the inline diff pre-image. |
| `:rf.xray/segment-inspector-open?` | rf2-e9tb0 — true iff the segment-inspector popup is open. |
| `:rf.xray/segment-inspector-path` | rf2-e9tb0 — the inspected path (vector), or `nil` when closed. |
| `:rf.xray/segment-inspector-value` | rf2-e9tb0 — the value at the inspected path. rf2-jmucu — resolved against the FOCUSED epoch's `:db-after` (via `:rf.xray/app-db-current+diff`'s `:value`), the same image the panel body shows, so the popup agrees with the body it pops out of at every scrub position (on-head that image *is* the live db; off-head it follows the focused epoch — no later-event bleed). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/focus-slice-path` | `[_ path]` | Sets the "Show me when this changed" focused path. |
| `:rf.xray/clear-slice-focus` | `[_]` | Drops the focus. |
| `:rf.xray/copy-value-to-clipboard` | `[_ value]` | `event-fx` — routes `value` through `runtime/egress-value` (pinned to the observed frame: `[:focus :frame]` ⇒ `:target-frame`) **before** the clipboard write, then emits `{:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str <elided>)}]]}`. The clipboard is an off-box sink, so sensitive ⇒ `:rf/redacted`, large ⇒ `:rf.size/large-elided`, fail-closed (rf2-uo0rc.2; same class as the palette snapshot rf2-mxzgg + `get-app-db` rf2-a96xq). No raw opt-in. |
| `:rf.xray/copy-path-to-clipboard` | `[_ path]` | `event-fx` — emits `{:fx [[:rf.xray.fx/copy-to-clipboard {:text (pr-str path)}]]}`. The path vector carries only key names (no values), so it is not a value-egress site and is not elided. |
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

## Schema-violation timeline

Spec: [`005-Schema-Timeline.md`](./005-Schema-Timeline.md).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-schemas` | Vector of `path-or-id` row keys from `rf/app-schemas`. `[]` when the schemas artefact is not on the classpath. |
| `:rf.xray/selected-violation-id` | The trace event's `:id` (stable per-process per [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)). |
| `:rf.xray/schema-filter` | Schema-id or `nil`. Narrows the rendered rows to one schema. |
| `:rf.xray/schema-timeline-window` | `{:t0 :t1}` in ms; falls back to the default 60s window ending at now. |
| `:rf.xray/schema-violations-window` | Vector of projected violation rows in chronological order, filtered to the current window. |
| `:rf.xray/schema-timeline-prev-rows` | Cache of previously-rendered rows so the panel's flash cue can detect empty→non-empty transitions. |
| `:rf.xray/schema-violation-timeline` | Composite — `{:rows :window :total-violations :rendered-violations :selected-violation :schema-filter}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/clear-violation-selection` | `[_]` | Closes the detail side panel. |
| `:rf.xray/select-violation` | `[_ violation-id]` | Selects by trace-event `:id`. Passing `nil` clears. |
| `:rf.xray/set-schema-filter` | `[_ schema-id]` | Narrows to one schema. Passing `nil` clears the filter. |
| `:rf.xray/set-schema-timeline-window` | `[_ {:t0 :t1}]` | Sets the window. Invalid maps (`nil`, non-numeric, `t0 >= t1`) revert to the default window. |

## Hydration debugger

Spec: [`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/selected-mismatch-id` | Mismatch trace event's `:id`, or `nil` (composite picks the latest). |
| `:rf.xray/hydration-reroot-path` | Re-root path for the side-by-side tree view per spec §Render-tree hash bisector, or `nil`. |
| `:rf.xray/hydration-has-mismatch?` | Boolean — `true` iff the target-frame's trace stream carries at least one mismatch event. Cheap projection (composite of `:rf.xray/trace-buffer` + `:rf.xray/target-frame`) for chrome indicators (issues-ribbon, navigator badge) that need only the binary status without paying for the full `:rf.xray/hydration-debugger-data` composite. |
| `:rf.xray/hydration-debugger-data` | Composite — `{:has-mismatch? :mismatch-summary :selected-mismatch-id :detail :source-coord :re-root-path :target-frame}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-mismatch` | `[_ mismatch-id]` | Drives the side-by-side rebase. Drops the re-root (subtree-specific). |
| `:rf.xray/clear-mismatch-selection` | `[_]` | Clears selection + re-root. |
| `:rf.xray/reroot-tree-view` | `[_ path]` | Re-roots at `path` (per spec §Render-tree hash bisector). Empty path clears. |
| `:rf.xray/open-in-editor` | `[_ coord]` | Records the attempted source-coord. Full handler lives in `open-in-editor.cljs`; this is the thin record-the-attempt path. |

## Views tab (incl. nested subs — replaces the pre-rewrite Subscriptions panel)

Spec: [`012-Views.md`](./012-Views.md). Subs nest under views per the
rewrite; the sub-cache + sub-graph primitives below continue to back
the nested-sub-row renderer in the Views tab.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/sub-cache` | Target frame's live sub-cache via `rf/sub-cache`. CLJS-only; JVM returns `nil` (panel renders empty state). Test override via `:sub-cache-override` on Xray's db. |
| `:rf.xray/sub-error-cache` | `{query-v <error-info>}`. v1 wiring keeps it empty until the error-collector plumbing lands. |
| `:rf.xray/selected-sub` | Query-v of the user's selection (drives the chain affordance). |
| `:rf.xray/sub-filters` | Set of active filter-chip statuses. |
| `:rf.xray/sub-chain-open?` | Boolean — is the chain affordance open? |
| `:rf.xray/subscriptions-data` | Composite — `{:rows :status-counts :total :selected-query-v :active-filters :chain-open? :chain}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-sub` | `[_ query-v]` | Sets selection. |
| `:rf.xray/clear-selected-sub` | `[_]` | Clears selection + chain-open. |
| `:rf.xray/toggle-sub-filter` | `[_ status]` | Adds / removes a status from the filter set. |
| `:rf.xray/show-invalidation-chain` | `[_ query-v]` | Opens the chain affordance. Optional `query-v` sets selection in one shot. |
| `:rf.xray/hide-invalidation-chain` | `[_]` | Closes the chain. |
| `:rf.xray/set-sub-cache-override-for-test` | `[_ ov]` | Test-only override hook. Production code paths MUST NOT dispatch. `nil` clears. |

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

## Flows panel

Spec consumer: framework Spec 013 (registered-flow surface) + Spec 009
(`:rf.flow/*` trace vocabulary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-flows` | `(re-frame.flows/flows-snapshot)` — the per-frame `{frame-id {flow-id flow-map}}` store (the SOLE store after framework rf2-en00bk; the registrar `:flow` slot is reserved-but-empty, so the former `(rf/registrations :flow)` read now returns `{}`). Test override via `:registered-flows-override`. |
| `:rf.xray/flow-trace-events` | Trace-buffer's `:op-type :flow` slice. |
| `:rf.xray/selected-flow-id` | Flow-id or `nil`. |
| `:rf.xray/flows-data` | Composite — `{:rows :status-counts :total :selected-flow-id}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-flow-id` | `[_ flow-id]` | Sets selection. |
| `:rf.xray/clear-flow-selection` | `[_]` | Clears selection. |
| `:rf.xray/set-registered-flows-override-for-test` | `[_ ov]` | Test-only override hook. |

## Effects panel

Spec consumer: framework Spec 002 §reg-fx + Spec 009 (`:rf.fx/*` trace
vocabulary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-fxs` | `(rf/registrations :fx)` — process-global registry. Test override via `:registered-fxs-override`. |
| `:rf.xray/fx-trace-events` | Trace-buffer's fx-related slice (`:op-type :fx` + fx-layer error categories). |
| `:rf.xray/selected-fx-id` | Fx-id or `nil`. |
| `:rf.xray/effects-data` | Composite — `{:rows :outcome-counts :total :selected-fx-id}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-fx-id` | `[_ fx-id]` | Sets selection. |
| `:rf.xray/clear-fx-selection` | `[_]` | Clears selection. |
| `:rf.xray/set-registered-fxs-override-for-test` | `[_ ov]` | Test-only override hook. |

## Performance panel

Spec: [`000-Vision.md` L92](./000-Vision.md). Per-cascade duration
capture, perf-tier colour mapping, budget-warning markers. No
panel-owned events — reuses `:rf.xray/select-dispatch-id` and
`:rf.xray/select-panel` for the pivot-into-event-detail affordance.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/performance-budget-ms` | Over-budget threshold in ms; default per `perf-helpers/default-budget-ms`. |
| `:rf.xray/performance-data` | Composite — `{:rows :total :tier-counts :over-budget-count :budget-ms :empty?}`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-performance-budget-ms` | `[_ budget-ms]` | Sets the threshold. `nil` / non-positive resets to default. |

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

## Resources panel

Spec: [`024-Resources-Panel.md`](./024-Resources-Panel.md) (Xray-side
lens) + [`spec/016-Resources.md`](../../../spec/016-Resources.md)
(framework substrate). Declarative-server-state lens: the static resource
registry, the live per-frame instance + work-ledger tables, the
route/resource graph, the lifecycle timeline, the invalidation graph, the
cache-growth view, and the scope audit + lints. Read-only — the panel
registers NO `:rf.resource/*` event (observing pins no resource, Spec 016
§Active owners and causes). Decoupled from the optional Resources artefact
(reads `(rf/registrations :resource)` + the runtime-db slice; Xray never
`:require`s `re-frame.resources`).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-resources` | `(rf/registrations :resource)` — the static registry. Test override via `:registered-resources-override`. |
| `:rf.xray/registered-resources-override` | Test override slot. |
| `:rf.xray/resource-entries` | The live cache entries map from the target frame's runtime-db at `[:rf.runtime/resources :entries]`. Test override. |
| `:rf.xray/resource-entries-override` | Test override slot. |
| `:rf.xray/resource-work-ledger` | The live work-ledger map at `[:rf.runtime/work-ledger]`. Test override. |
| `:rf.xray/resource-work-ledger-override` | Test override slot. |
| `:rf.xray/resource-sub-reads` | Observed live subscription reads (`[{:resource-id :params :scope} …]`) backing the scope-mismatch lint. Empty by default. Test override. |
| `:rf.xray/resource-sub-reads-override` | Test override slot. |
| `:rf.xray/resource-routing-slice` | The live routing-runtime subtree at `[:rf.runtime/routing]` (current route + nav-token + per-nav-token unsettled-blocking set) backing the live route/resource graph. Test override. |
| `:rf.xray/resource-routing-slice-override` | Test override slot. |
| `:rf.xray/resources-tab-data` | View-facing composite — `{:silent? :registry :instances :work :route-graph :timeline :invalidations :cache-growth :audit}` over the registry + entries + ledger + route registry + trace buffer + routing slice. The `:route-graph` joins the static route plan against the live instance/work rows + routing slice (per-resource freshness rollup; the active route flagged `:current?`). PRIVACY: every param/scope/data/cause/outcome value is summarized (never raw). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-registered-resources-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-entries-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-work-ledger-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-sub-reads-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-routing-slice-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |

### Tool accessors (the AI / MCP read API)

Five read-only accessors on `day8.re-frame2-xray.runtime` (the Xray↔MCP
read seam), per [`024-Resources-Panel.md` §Tool accessors](./024-Resources-Panel.md):
`list-resources`, `list-resource-instances`, `get-resource-state`,
`get-resource-history`, `list-resource-invalidations` — filterable by
frame / scope / resource-id / params / tag / owner / status / stale? /
request-id / nav-token, with bounded history and the two-layer privacy
elision (in-panel summary + off-box `egress-*` walker).

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

## Module-view tab (EP-0023 image/frame model)

Spec: [`026-Module-View-Panel.md`](./026-Module-View-Panel.md). The cohesive
home for runtime-structure inspection: the EP-0023 `image -> frame` PUBLIC
model (the FRAMES/IMAGES section, §8). A BROWSE surface (registry-wide, not
event-coupled) — read-only, dispatches nothing. (The retired EP-0013 `(realm,
frame)` / module substrate this tab once also surfaced — the REALMS + MODULES
sections — was deleted in full; there is no `re-frame.realm` namespace.)

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/image-view` | Composite — the EP-0023 `image -> frame` model (rf2-32siq3.12). `{:frames [<frame-row> …] :frame-count :images?}` over the image-loaded frames: each as an execution context carrying its resolved image (the generation's `[kind id]` descriptors + per-descriptor provenance). EP-0024 (rf2-tu2vr7): the registries collapsed — an image-loaded frame is a single `re-frame.frame/frames` record carrying a `:generation`; the read goes through `re-frame.live-frame/image-view-frames` (which projects each such record into an inert frame view) + sealed generations (`re-frame.image-assembly/resolve-descriptor`) via the fail-soft `image_view_reads` seam; projects via `image_view_helpers/project-image-view`. `:images?` false → the no-image caption (the image/frame model is opt-in). Xray inspects the target frame as DATA here; Xray's OWN image (`image_view_reads/xray-image`) is a separate registration set that never mixes with a target frame's image (EP-0023 §Xray Beside The Target). |

This sub is L4-tab-internal — `module_view.cljs` registers no panel-internal
events (a browse surface). The tab is registered via `reg-l4-tab!` (id
`:module-view`, label **"Frames"**), so it is NOT in `panel_enum.cljc`.

## Static mode

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §Static mode +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface
architectural section. Static mode is unconditionally available
(per rf2-8l3uk — the prior `:rf.xray/static-mode?` feature gate
was removed). The mode pill mounts at ribbon-left, `Cmd-Shift-M` /
`Ctrl-Shift-M` toggles between Dynamic and Static surfaces, and the
selected mode + sub-tab persist to localStorage. Per rf2-o5f5f.1 +
rf2-o5f5f.2 + rf2-o5f5f.3 + rf2-ybjkx + rf2-8l3uk.

**Process-registrar browse.** The static browse panels read their
registrations off the process-global registrar via
`host-registry/registrations` (the generation-bypassing read — see
[`026`](./026-Module-View-Panel.md) §8.4 and [`007-UX-IA.md`](./007-UX-IA.md)
§Runtime-structure awareness). There is no realm dimension to qualify by: a
registration belongs to the process registrar, full stop. The former
realm-qualified browse (`static/shared/realm.cljs` and the Static Interceptors
`:rf.xray.static.interceptors/realm-pairs` sub) was removed with the realm
substrate.

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/mode` | `:dynamic` / `:static`. | Default `:dynamic`. Hydrated from `xray.mode` localStorage on boot. |
| `:rf.xray.static/selected-tab` | Keyword sub-tab id (`:machines` / `:routes` / `:schemas` / `:views` / `:events`). | Default `:machines` per `static/shell.cljs`. |
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

- **Source coords** — every `reg-sub` / `reg-event` / `reg-fx`
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
