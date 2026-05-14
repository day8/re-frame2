# 016-Auxiliary-Panels

The normative home for the panels Causa ships beyond the hero
surfaces (causality graph, time-travel, machine inspector, app-db
diff, schema timeline, hydration debugger, subscriptions). These
seven panels round out the sidebar inventory enumerated in
[`000-Vision.md`](./000-Vision.md) and
[`007-UX-IA.md`](./007-UX-IA.md) §Sidebar groups; their registrar ids
are catalogued in [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md).
Each section below is the per-panel contract a one-shot implementer
needs — inputs (subs / events consumed), main interactions, observable
outputs — without having to reverse-engineer
`tools/causa/src/day8/re_frame2_causa/panels/*.cljs`.

The seven panels covered here:

| Panel | `:rf.causa/panels.*` | Phase | Sourced from |
|---|---|---|---|
| Event detail | `event-detail` | Phase 2 (rf2-op3bz) | The §10 Lock 7 default landing view. |
| Effects | `effects` | Phase 5 (rf2-ts41u, parent rf2-5aw5v) | Surfaces re-frame2's registered fxs + outcomes. |
| Flows | `flows` | Phase 5 (rf2-83irn) | Surfaces re-frame2's registered flows + recomputation status. |
| Issues ribbon | `issues-ribbon` | Phase 5 (rf2-d1p4o) | Unified feed of errors / warnings / schema violations / hydration mismatches. |
| Performance | `performance` | Phase 5 (rf2-75121) | Per-cascade duration + tier + budget warnings. |
| Routes | `routes` | Phase 5 (rf2-6blai) | Active `:rf/route` slice + registered routes + navigation history. |
| MCP Server | `mcp-server` | Phase 5 (rf2-81qjj) | Filter on `:tags :origin :causa-mcp` — what the AI agent did this session. |

All seven share the cross-panel substrate:

- **Pure hiccup** per [rf2-tijr](../../../spec/Conventions.md) — no
  Reagent / UIx / Helix references in the view. Frame isolation comes
  from the enclosing `[rf/frame-provider {:frame :rf/causa}]` in
  `shell.cljs`. Every `subscribe` / `dispatch` resolves to `:rf/causa`.
- **Read-only by default** per [`Principles.md`](./Principles.md). The
  panels write only to Causa's own `:rf.causa/*` app-db slots
  ([`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)).
- **Pure-data helpers under `_helpers.cljc`** — projection, filter
  application, status / outcome classification all live next to each
  panel as a `.cljc` sibling so the algebra runs under the JVM unit-
  test target. View files only render.
- **Trace-bus consumer** — every panel filters the
  `:rf.causa/trace-buffer` ring (per
  [`013-Trace-Bus.md`](./013-Trace-Bus.md)) to its slice; nothing here
  reads framework-level state directly.

## Event-detail panel

The §10 Lock 7 default landing view (per
[`007-UX-IA.md`](./007-UX-IA.md) §The default landing view). The hero
panel for "what just happened, in detail" — the first surface a
user sees on opening Causa.

### Inputs

- `:rf.causa/trace-buffer` — the ring of `:rf/trace-event` records
  (per [`013-Trace-Bus.md`](./013-Trace-Bus.md) §Consumer contract).
- `:rf.causa/selected-dispatch-id` — keyword dispatch-id or `nil`.
- `:rf.causa/event-detail` — composite sub merging the two above
  ([`014`](./014-Registry-Catalogue.md) §Event-detail panel).

### Main interactions

- **Click a cascade row** in the cascade list →
  `:rf.causa/select-dispatch-id` (selection event). The selection is
  also consumed by the causality graph per §10 Lock 7.
- **Clear selection** → `:rf.causa/clear-selected-dispatch-id`.
- **Source-coord click-through** (non-interactive in v1) → planned
  jump-to-editor via rf2-evgf5 / the
  `:rf.causa/open-in-editor` event (the actual editor jump rides on
  the open-in-editor module).

### Outputs

- The **cascade list** view (no selection) — one row per cascade,
  oldest first.
- The **six-domino hero** (selection set) — the cascade rendered as
  six labelled rows in order:
  1. The event vector (the cascade root — from `:event/dispatched`).
  2. The handler trace event (the `:run-end` emit — populated by
     `re-frame.trace.projection/absorb`).
  3. The `:event/do-fx` emit (the effects map about to be walked).
  4. The list of `:rf.fx/handled` / override / skipped effects.
  5. The list of `:sub/run` + `:sub/create` events.
  6. The list of `:view/render` events.
- An **`:other` bucket** for traces that don't fit the six-domino
  vocabulary (errors, warnings, machine transitions, etc.).
- A small mono-font **source-coord caption** under each event when
  `:rf.trace/trigger-handler` is present (per rf2-3nn8 / rf2-lf84g).

### Empty state

The cascade list with `:rf.causa/empty-trace` cue when the buffer
holds no `:event/dispatched` events.

## Effects panel

Surfaces re-frame2's registered fxs (per Spec 002 §`reg-fx`) plus
their per-fx invocation outcomes and stub indicators.

### Inputs

- `(rf/handlers :fx)` — `{fx-id metadata}` projection from the
  framework registrar.
- `:rf.causa/trace-buffer` filtered to the fx-related slice:
  `:rf.fx/handled`, `:rf.fx/override-applied`,
  `:rf.fx/skipped-on-platform`, and the fx-layer error events
  (`:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`).
- The current `:fx-overrides` map (per Spec 002 §`:fx-overrides`)
  for the `STUB` indicator.

### Main interactions

- **Click a row** → selects the fx via `:rf.causa/select-fx-id` and
  dispatches the panel pivot to `:event-detail` filtered to the
  fx's recent invocations (v1: selection only; cross-panel filter
  wiring rides the selection).

### Outputs

One row per registered fx, left to right:

- **Status badge** — one of the canonical five:
  `:error` / `:overridden` / `:skipped` / `:ok` / `:never-invoked`.
  Colour follows [`007-UX-IA.md`](./007-UX-IA.md) §Colour system §Colour
  is never alone (every hue pairs with a glyph).
- **fx-id** (mono column).
- **`:platforms` chip** — `any` / `client` / `server` per Spec 002
  §`reg-fx` (defaults to both).
- **Most-recent operation** in a small caption.
- **Invocation count** for the current buffer.
- **`STUB` indicator** when an `:fx-overrides` entry is active for
  that id.

### Empty state

"No fx registered."

## Flows panel

Surfaces re-frame2's registered flows (per Spec 013 §Flows) plus
their per-flow inputs, output path, and live recomputation indicator.

### Inputs

- `(rf/handlers :flow)` — `{flow-id metadata}` projection.
- `:rf.causa/trace-buffer` filtered to `:op-type :flow` (the
  `:rf.flow/*` instrumentation per Spec 009).
- The current cascade's dispatch-id for the `RAN` cue
  (per-cascade scope).

### Main interactions

- **Click a row** → selects that flow (v1 minimum-viable contract:
  selection only; the deeper detail strip — recent events,
  jump-to-event-detail — lands when cross-panel wiring stabilises).

### Outputs

One row per registered flow, left to right:

- **Status badge** — one of the canonical four:
  `:failed` / `:computing` / `:skipping` / `:idle`.
- **flow-id** (mono column).
- **Inputs paths** (` · `-separated).
- **Output path**.
- **`RAN` cue** when the flow recomputed *this* cascade (the live
  recomputation indicator).
- **Most-recent operation** in a small caption (at-a-glance
  flow-state debugging).

### Empty state

"No flows registered."

## Issues ribbon

The unified feed of errors, warnings, schema violations, and
hydration mismatches per [`000-Vision.md`](./000-Vision.md) L94 +
[`007-UX-IA.md`](./007-UX-IA.md) §Sidebar groups. Consumer of the
~95-category emit stream catalogued in
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Error event catalogue.

### Inputs

- `:rf.causa/trace-buffer` filtered to issue trace events
  (severity ∈ #{`:error` `:warning` `:advisory`}).
- The active filter set across three independent axes:
  - **severity** (chip row): error / warning / advisory.
  - **category-prefix** (chip row): `:rf.error/*` vs
    `:rf.warning/*` etc.
  - **since-ms** (numeric input): within this many ms of now.

Empty filter sets disable the axis.

### Main interactions

- **Click a row** → `:rf.causa/select-dispatch-id` for the parent
  dispatch and pivot to the event-detail panel for the cascade that
  produced the issue.
- **Click the source-coord chip** → `:rf.causa/open-in-editor` (the
  editor jump itself rides on the open-in-editor module).
- **Toggle a chip** → flips the corresponding filter axis.
- **Adjust since-ms** → narrows the temporal window.

### Outputs

One row per issue trace event, oldest-first (per the bead's
minimum-viable contract):

    timestamp · category · severity · short description · jump-to-source

The bottom-rail issue-count badge mirrors the visible-after-filter
count.

### Empty states

Two distinguished states:

- **`:no-issues`** — "No issues observed in this session." Carries
  the `✓ All clear` badge — the desired state.
- **`:no-matches`** — issues exist but the active filters hide them
  all. Carries a `Clear filters` affordance.

## Performance panel

Per-cascade duration capture, perf-tier colour mapping, and
budget-warning markers (per the [`000-Vision.md`](./000-Vision.md) L92
panel-inventory row). Consumer of the User Timing channel +
trace-stream `:time` fields Spec 009 §Performance publishes.

### Inputs

- `:rf.causa/trace-buffer` projected per-cascade.
- `:rf.causa/performance-data` — composite sub merging the buffer
  with the per-tier colour palette.
- `default-budget-ms` — currently `16` (one frame at 60 fps). The
  budget-marker threshold.

### Main interactions

- **Click an `over-budget` marker** → pivot to the event-detail panel
  for that cascade (the "why was this slow?" drill-in).

### Outputs

One row per dispatch cascade in the trace buffer:

    tier-glyph · dispatch-id · event vector · duration · per-step bar

The tier-glyph + colour follows [`007-UX-IA.md`](./007-UX-IA.md)
§Colour system §Perf scale (Colour is never alone — every hue pairs
with a shape):

    ● green   :fast      <16ms
    ● yellow  :medium    16-50ms
    ▲ orange  :slow      50-100ms
    ▲ red     :blocking  >=100ms

Rows whose duration crosses `default-budget-ms` carry an extra
`over-budget` marker chip on the right edge — the panel's hero
affordance per the bead's contract.

A **tier histogram** chip row at the panel header summarises the
visible buffer (one chip per tier with the count + colour swatch).

### What v1 does NOT include

Per the bead's minimum-viable contract:

- No `PerformanceObserver` integration (INP / long-task / layout-
  shift overlays). These ride a follow-on bead once the shared
  `rf.causa.fx/install-performance-observer!` effect lands — the
  Trace panel will share that effect once both panels are live.
- No drag-zoom horizontal ribbon. v1 ships the row view; the ribbon
  canvas is follow-on work alongside time-axis synchronisation with
  the Trace panel.
- No re-render-counts-per-epoch projection from the epoch-record's
  `:renders` slot. The row's render-count is a cheap proxy (count of
  `:view/render` traces in the cascade) until that slot surfaces.

## Routes panel

Surfaces re-frame2's registered routes (per Spec 012 §Routing) +
the active `:rf/route` slice + recent navigation history.

### Inputs

- `(rf/handlers :route)` — `{route-id metadata}` projection.
- The target frame's `:rf/route` slice (per Spec 012 §The
  `:rf/route` slice) — route-id, params, query, fragment,
  transition (FSM: `:idle / :loading / :error`).
- `:rf.causa/trace-buffer` filtered to
  `:rf.route.nav-token/*` + `:rf.route/url-changed` events.

### Main interactions

- **Click a registered-routes row** → selects the route (v1: selection
  only; click → source-coord jump lands when the cross-panel jump
  API stabilises).
- **Click a navigation-history row** → pivots to the event-detail
  panel for that navigation (parity with the Issues ribbon
  row-click).

### Outputs

Three stacked sections (top to bottom):

1. **Active route** — breadcrumb-style strip: route-id · params ·
   query · fragment · transition. Transition is rendered with the
   spec's transition swatch:
   - `:idle` → green ● per [`007-UX-IA.md`](./007-UX-IA.md)
   - `:loading` → cyan ◐
   - `:error` → red ●
   Colour is never alone — each transition pairs with a glyph.
2. **Registered routes** — one row per `(rf/handlers :route)`
   entry, sorted by `:path`. Each row: route-id, path-pattern,
   `:doc`. The active-route row is highlighted.
3. **Navigation history** — recent route-history trace events,
   newest first, capped at 50.

### Empty state

"No routes registered."

## MCP Server panel

The panel-side surface for `tools/causa-mcp/` (per
[`010-MCP-Server.md`](./010-MCP-Server.md)). Filters the trace buffer
to events tagged `:tags :origin :causa-mcp` — the canonical tag the
causa-mcp jar stamps on every side-effect it performs (per
[`010-MCP-Server.md`](./010-MCP-Server.md) §Origin tagging +
[`tools/causa-mcp/spec/Principles.md`](../../causa-mcp/spec/Principles.md)
§Origin tagging is the convention).

### Inputs

- `:rf.causa/trace-buffer` filtered to `:origin :causa-mcp` tagged
  events.
- `:rf.causa/mcp-origin-filter-enabled?` — the cross-panel toggle the
  panel ships (Trace / Causality / Event-detail panels can read this
  to dim non-agent events — cross-panel wiring is follow-on).

### Main interactions

- **Click a row** → `:rf.causa/select-dispatch-id` for the cascade,
  pivots to event-detail. Empty `:dispatch-id` events
  (registry-time / lifecycle) stay non-clickable.
- **Toggle origin-filter** → flips
  `:rf.causa/mcp-origin-filter-enabled?`.

### Outputs

A read-only, scrollable, timestamped ribbon of every trace event
the agent produced this session. Each row:

    timestamp · op-type · operation · tool · description · source-coord

Origin colour is **cyan `#06B6D4`** — the v1 inferential decision
(per the panel source's INFERENTIAL DECISIONS block — a follow-on
bead promotes the colour to [`007-UX-IA.md`](./007-UX-IA.md) §Colour
system and surfaces a full picker). The colour is distinct from
`:pair`'s indigo and `:story` / `:test`'s lighter cyan so the agent's
footprint is visually unambiguous across panels.

A two-line **settings sub-pane** sits above the feed (in lieu of a
separate Settings modal section — those land with the broader
Settings work per [`007-UX-IA.md`](./007-UX-IA.md) §Modal layers):

- **Origin colour** — read-only swatch + label (cyan `#06B6D4`).
- **Origin-filter enable / disable** — drives the
  `:rf.causa/mcp-origin-filter-enabled?` toggle.

### Inferential decisions (rf2-81qjj, the spec-deficient bead's locks)

Three open questions the bead-filing agent flagged, locked in v1:

- (a) **Sidebar panel y/n** → yes (dedicated `:mcp-server` panel).
- (b) **Origin colour for `:causa-mcp`** → cyan `#06B6D4`.
- (c) **Bidirectional Causa→agent surface** → out of scope for v1
  (causa-mcp jar implementation concern, not a Causa panel concern).

Each decision is also documented inline at the panel source as a
`;; DECISION` comment so a follow-on bead can refine without
spelunking history.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) — the canonical-questions panel
  inventory.
- [`007-UX-IA.md`](./007-UX-IA.md) §Sidebar groups — where each panel
  sits in the IA.
- [`013-Trace-Bus.md`](./013-Trace-Bus.md) — the trace ring every
  panel filters from.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) — the
  exhaustive `:rf.causa/*` subs + events + fxs each panel registers.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  — the framework's trace-event vocabulary panels read.
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) §`reg-fx`,
  §`:fx-overrides` — what the Effects panel surfaces.
- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — what the Flows
  panel surfaces.
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — what the
  Routes panel surfaces.
- [`010-MCP-Server.md`](./010-MCP-Server.md) — the agent-side jar
  whose footprint the MCP Server panel renders.
