# 016-Auxiliary-Panels

The normative home for the per-tab content contracts beyond the hero
4-layer chrome architecture in
[`018-Event-Spine.md`](./018-Event-Spine.md). The 7-tab inventory in
[`000-Vision.md`](./000-Vision.md) §The 7-tab inventory is the
top-level navigation map; this doc gives the per-tab implementation
contract a one-shot implementer needs — inputs (subs / events
consumed), main interactions, observable outputs — without having to
reverse-engineer `tools/causa/src/day8/re_frame2_causa/panels/*.cljs`.

The per-tab content this doc covers:

| Tab content | `:rf.causa/panels.*` | Source / phase |
|---|---|---|
| Event tab content (fattened) | `event-detail` | Phase 2 (rf2-op3bz) + folds in former Effects panel content |
| Issues tab content | `issues-ribbon` | Phase 5 (rf2-d1p4o); unified feed per [`018-Event-Spine.md`](./018-Event-Spine.md) §5.4 |
| Routing tab content (7th tab) | `routing` | rf2-nrbs9 — promoted from "lives in App-db + Trace" to its own L3 lens tab; see §Routing tab below |
| Flows (lives in Views tab "Re-rendered" group) | `flows` | Phase 5 (rf2-83irn); see §Flows content below |

## 13-panel inventory (rf2-crhr8 + rf2-3r3ao)

Every Causa panel is independently mountable per
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract. The
4-tier surface inventory totals 13 panels — 11 independently
mountable, 2 internal sub-components. The canonical Panel-component
mount paths and L3-tab backing (when applicable) are:

| # | Tier | Panel | Mount path (`day8.re-frame2-causa.panels.*`) | Backs L3 tab |
|---|---|---|---|---|
| 1  | 1 | Event tab            | `event-detail/Panel`                | Event |
| 2  | 1 | App-db tab           | `app-db-diff/Panel`                 | App-db |
| 3  | 1 | Views tab            | `views/Panel`                       | Views |
| 4  | 1 | Trace tab            | `trace/Panel`                       | Trace |
| 5  | 1 | Machines tab         | `machine-inspector/Panel`           | Machines |
| 6  | 1 | Routing tab          | `routing/Panel`                     | Routing |
| 7  | 1 | Issues tab           | `issues-ribbon/Panel`               | Issues |
| 8  | 2 | App-DB segment-inspector popup    | `app-db-segment-inspector/Popup`        | — (overlay) |
| 9  | 2 | Cancellation-cascade side-panel    | `cancellation-cascade/SidePanel`        | — (overlay) |
| 10 | 2 | Cancellation-cascade popover       | `cancellation-cascade/Popover`          | — (overlay) |
| 11 | 3 | Managed-fx records list            | `panels/ManagedFxList`                  | embedded in Event tab |
| 12 | 4 | After-rings overlay                | `machine-after-rings/AfterRingsOverlay` | sub of Machines tab |
| 13 | 4 | Sim side-rail                      | `machine-inspector-sim/SimSideRail`     | sub of Machines tab |

Panel-by-panel detail (subs / events / interactions) lives in the
sections below. Tier 4 sub-components are geometry-coupled to
`machine-inspector/Panel` and are NOT independently mountable; they
ship under `mount-machine-inspector!`. Modal overlays the 4-layer
shell owns (Settings dialog, command palette, share modal) are
shell chrome and NOT counted here.

### Performance — dropped (cross-link to Chrome DevTools)

The Performance panel is dropped from Causa. The framework already
emits User-Timing entries via Spec 009 — `rf:event:<id>`,
`rf:sub:<id>`, `rf:fx:<id>`, `rf:render:<component>`,
`rf:cascade:<dispatch-id>` — which Chrome DevTools renders natively in
its **Performance** tab → Timings track. Causa stops duplicating a
surface Chrome does better at higher quality (flamegraph, per-tick
zoom, INP overlay, layout-shift markers, scroll-jank attribution all
free).

To use Chrome DevTools for performance analysis:

1. Open Chrome DevTools → **Performance** tab.
2. Click record · perform the interaction · stop recording.
3. The Timings track shows the `rf:*` User-Timing entries inline with
   browser-level events (long tasks, layout shifts, INP).
4. Per-event hot-path information stays inside Causa's chrome —
   tier dots, ms duration — in the Event tab + event-list row gutters.

**Sensitive-data note for the cross-link:** Chrome DevTools cannot
mark-render `:rf/redacted` sentinels — any User-Timing entry name that
would leak sensitive data must be self-redacted at emission time by
the framework (Spec 009 / `re-frame.performance`). Causa documents
this as a constraint of using the DevTools cross-link; it is not a
Causa rendering surface.

### MCP Server panel — dropped

The MCP Server panel is dropped from Causa. The dedicated `causa-mcp`
artefact was envisaged but dropped entirely (rf2-hvl1g, 2026-05-19);
there is no Causa-curated MCP surface to render. AI access to the
running re-frame2 runtime goes through `tools/re-frame2-pair-mcp/`
over raw nREPL — see [`000-Vision.md`](./000-Vision.md) §Where Causa
fits and DESIGN-RATIONALE.md Lock #6 supersedence.

### AI co-pilot — dropped

The AI co-pilot panel is dropped from Causa. Causa is the human-only
observability surface; AI access is via `tools/re-frame2-pair-mcp/`. The
collapsed-rail cue glyph + the right-rail co-pilot panel + all
co-pilot panel namespaces (`panels/ai_co_pilot*`) die in a separate
deletion PR.

All tab content shares the cross-panel substrate:

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
- **Spine binding** — per-tab content reads
  `:rf.causa/focus` (see [`018-Event-Spine.md`](./018-Event-Spine.md)
  §6); selection in the L2 event list rebinds all per-tab content
  atomically. No panel reads `(peek history)`; no panel carries
  `:selected-*-id` slots.

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
  `:rf.causa/select-dispatch-id` (selection event).
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

## Effects content — folded into Event tab

The pre-rewrite Effects panel is GONE. Its content folds into the
**Event tab** (tab 1 of 7) as the "fx handlers that ran" block — see
[`018-Event-Spine.md`](./018-Event-Spine.md) §5.1 for the canonical
wireframe.

Per-fx invocation status (`:error` / `:overridden` / `:skipped` /
`:ok`) renders as inline chips next to the fx-id in that block.
Aggregate "registered fxs" data (which historically lived in the
Effects panel) is reachable via the Cmd-K palette under the `:fx`
source — registered handler ids + invocation counts. No standalone
tab.

## Flows content — Event tab FLOWS section (rf2-lo37i)

The pre-rewrite Flows panel is GONE. Flows surface as the **8th
section of the Event tab** — the canonical home for per-cascade flow
firings is [`018-Event-Spine.md`](./018-Event-Spine.md) §5.1 FLOWS
section. For each flow that fired during the focused cascade the
FLOWS section lists, in cascade order:

- `wrote <path>` — the flow's `:output` write target with the
  after-value rendered inline.
- `read <input-path-1> <input-path-2> …` — the flow's `:inputs`,
  shown so the reader can see which paths caused the recompute.

The FLOWS section sits as a peer of EFFECTS / HANDLERS RAN / the
returned-value slot under the per-event cascade view (see 018 §5.1
lines 442-451 wireframe, 507-531 row contract).

A **secondary** appearance is in the **Views tab** "Re-rendered"
group (cross-cutting): when a flow's downstream sub appears in a
view's *Rerendered because* list (per
[`012-Views.md`](./012-Views.md) §Three-group layout Re-rendered),
the sub-id carries a `⊳` flow-glyph prefix that distinguishes
flow-output subs from hand-written subs. Click-through from the
Views entry jumps to the Event tab's FLOWS section for that cascade.

A registered-flows-overview is reachable via the Cmd-K palette under
the `:flow` source: flow-id, inputs, output path, last recompute. No
standalone tab.

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

### `:ungrouped` escape-hatch lane (rf2-2f40y)

The main feed is cascade-scoped (rf2-u6dhp) and `:ungrouped` cascades
are structurally unfocusable (rf2-fzbrw — `compose-focus` snaps to the
head of a real cascade). Together, the two invariants leave issues
with `:dispatch-id :ungrouped` — issues emitted outside any dispatch
context, e.g. `verify-hydration!` firing `:rf.ssr/hydration-mismatch`
during SSR — un-navigable via L2/focus. Both invariants are
individually correct; the gap was the missing surface.

The Issues panel renders a dedicated **`:ungrouped` lane** below the
cascade-scoped feed as the deliberate escape hatch. Per the rf2-2f40y
operator decision (option (a), recorded in the bead notes) the lane
preserves both invariants without relaxing `compose-focus` semantics.

**Inputs:** `:rf.causa.issues/ungrouped` — a thin derivation off
`:rf.causa/trace-buffer` returning `{:issues [<row> ...] :total <int>}`
(newest first). No chip-filter histograms; the lane is a compact
escape hatch, not a second filterable feed.

**Visibility contract:** the lane is rendered iff
- the cascade-scoped feed has no issues to surface (the panel's
  `:empty-kind` is one of `:no-issues`, `:no-issues-for-event`, or
  `:no-matches`), AND
- at least one `:ungrouped` issue exists.

While the focused cascade has its own issues the lane stays hidden
so it doesn't compete with the user's current lens.

**Visual treatment:** muted uppercase header `Issues outside any
cascade` + a one-line clarifier (`Emitted outside any dispatch — no
cascade to focus.`) above the per-issue rows. The rows reuse the same
chrome as the cascade-scoped feed (`issue-row`); per-row click pivots
are intentionally inert for `:ungrouped` (the existing `pivotable?`
guard in `issue-row` checks `(not= :ungrouped dispatch-id)`), so the
lane is a read-only surface — open-in-editor on the source-coord chip
is the only outgoing affordance.

## Performance — dropped (see §Performance cross-link at top of file)

The Performance panel is dropped from Causa. Use Chrome DevTools'
Performance tab — the framework emits `rf:*` User-Timing entries
DevTools renders natively. See the §Performance cross-link block at
the top of this file.

Per-cascade tier dots + duration ms stay inline in:

- L2 event-list row gutters (`●` colour tier).
- L4 Event tab content "event" header line (`⏱ 11ms · tier ●`).
- Event-list row hover tooltips (`⏱ 12ms · tier ●`).

That's the entire Causa-side perf surface post-rewrite.

## Routes tab — 7th L3 tab (rf2-nrbs9, reshaped per rf2-lq0ef)

Per Mike's design call (2026-05-18) Routing was promoted from "lives
in App-db + Trace" to its own L3 lens tab. The App-db panel was
getting busy and routing is a cohesive sub-domain (route catalogue +
current match + nav transitions); cohesive sub-domains earn their own
lens tab rather than overloading App-db. Parallel to the Machines tab
in posture — always-on registered topology + per-focused-event lens.

### Shape: flat catalogue (rf2-lq0ef)

The lens is a **flat catalogue sorted by `:path`** — never a tree.
The previous URL-path-segmentation indentation (`project-route-tree`)
was decorative: the routing-inheritance audit (2026-05-19) found that
routes are flat in the spec + impl, `:parent` plays no
role in matching, and the match-resolver is structural (6-rule rank
on URL pattern). Indenting routes by URL-segment count conflated
URL-prefix similarity with semantic hierarchy — those are independent
(a child route can live anywhere in the URL space).

The lens shape now mirrors the actual contract:

- **Flat list sorted by `:path`** (lexicographic). No indentation, no
  depth.
- **Per-row chips**: route-id + path + doc, with letter badges
  (`M` / `L` / `T` / `P`) for routes carrying `:on-match` /
  `:can-leave` / `:tags` / `:parent`. Click the row chevron to
  expand the full registrar meta inline.
- **Substring search** across route-id + path + doc.
- **Simulate-URL** — paste a URL into Try URL, the panel ranks every
  matching route by its 6-rule `:rf.route/rank` tuple and highlights
  the winner. This is the load-bearing interactive surface — it
  exposes the structural match contract per
  [`spec/012-Routing.md`](../../../spec/012-Routing.md) §Route
  ranking algorithm.
- **`:parent` annotation** — when a row carries `:parent`, render a
  compact `↑ :route/parent` inline pointer; expanding the row
  surfaces the full registrar meta (including the `:rf.route/chain`
  surface the impl exposes).

### Inputs

- `:rf.causa/registered-routes` — flat `{<route-id> <meta>}` map
  sourced from `(rf/registrations :route)` (the framework's
  registrar). Falls back to a test-only override slot for fixtures
  + JVM tests.
- `:rf.causa/current-route-slice` — composite over `:rf.causa/target-
  frame-db` reading the `:rf/route` slice. Switching the L1 frame
  picker re-binds the lens to the new frame's route slice.
- `:rf.causa/cascades` — the shared cascade projection. The composite
  scans the focused cascade's trace events for the routing-emit.
- `:rf.causa/focus` — the spine's focused dispatch-id + epoch.
- `:rf.causa.routing/query` — substring filter input (driven by
  `:rf.causa.routing/set-query`).
- `:rf.causa.routing/sim-url` — Simulate-URL input (driven by
  `:rf.causa.routing/set-sim-url`).
- `:rf.causa.routing/expanded` — set of expanded route-ids (driven by
  `:rf.causa.routing/toggle-row`).
- `:rf.causa/routing-tab-data` — view-facing composite folding all of
  the above into `{:silent? :routes :total-routes :filtered?
  :current :from-id :to-id :navigated? :query :sim-url :sim-result}`
  per `routing_helpers/project-data`.

### Per-focused-event highlighting

| Marker | Trigger | Visual |
|---|---|---|
| `◆ HERE` | Current matched route — always when no navigation happened | Violet chip (`accent-violet`); left-border accent |
| `◆ FROM` | Cascade caused navigation — the prior route | Cyan chip; left-border accent |
| `◆ TO` | Cascade caused navigation — the new route | Green chip; left-border accent; replaces `◆ HERE` (TO is the new HERE) |

When the focused cascade has no routing impact, only `◆ HERE`
surfaces — orientation glyph.

### Detection contract — how the panel knows the cascade caused navigation

The composite scans the focused cascade's trace events for a
`:rf.route.nav-token/allocated` emit (per
[`spec/012-Routing.md`](../../../spec/012-Routing.md) — the emit fires
inside both `:rf.route/navigate` and `:rf/url-changed`). Detection:

- The emit's `:tags :route-id` is the **TO** (the new route).
- The current `:rf/route` slice's `:id` (when different from TO) is
  the **FROM**.
- Same-route re-navigations (different params/query, same route-id)
  collapse FROM to nil — surfacing a FROM equal to TO is noise.

### Simulate-URL contract

The simulator walks the registered-routes map, calls
`re-frame.routing.match/match-against` on each route's compiled
pattern (or compiles it on the fly for test-only fixtures), and
ranks the matching candidates by `:rf.route/rank` descending — the
same order `match-url` walks the registry table. The first candidate
is the winner.

The result block surfaces:

- The normalised path (query and fragment stripped).
- Every matching candidate, with its `:rf.route/rank` tuple and the
  parsed `:params` map.
- The winner highlighted (green border + `WINNER` glyph).
- An empty result block when no route matches (i.e. `match-url` would
  return nil for this URL).

Query coercion and `:params` / `:query` schema validation are out of
scope for the simulator — the lens is about exposing the rank
cascade, not full match semantics.

### Active route slice — params + query + fragment

Below the catalogue the panel renders a labelled grid for the active
slice:

    Params:    {:order-id "ord-1234"}
    Query:     {:source "cart"}
    Fragment:  "#step-3"

Absent slots render as `—` so the lens always shows the same
skeleton (predictable scanning).

### Empty state

When the host app registers no routes the panel renders only the
header + a terse `No routes registered.` one-liner. No `(none)`
placeholder, no marketing copy — silent-by-default per rf2-g3ghh.
Search + Simulate-URL are hidden when the catalogue is empty.

### Pre-rewrite app-db / trace overlap

The transition FSM state (`:idle` / `:loading` / `:error`) is still
part of the app-db slice (Spec 012) and still visible in the App-db
tab's diff. The Routes tab is the dedicated lens; the App-db tab
shows the raw slice diff like any other key. Navigation trace events
(`:rf.route.nav-token/*` + `:rf.route/url-changed`) continue to
appear in the Trace tab when the `event` chip is ON (default) —
the Routes tab does not duplicate the firehose, it projects the
single nav-event that pertains to the focused cascade.

### Vision (future)

- **Nav-token timeline (swimlanes)** popover trigger from the tab.
- **`:on-match` chain explicit** in the Event tab's "fx handlers
  that ran" block when the focused cascade is a routing cascade
  (already noted under §Event tab — `:on-match` event chain (Routes)
  later in this doc).
- **Route-chain visualiser** — the `:parent`-chain walk for nested
  layouts (i.e. expand the inline `:parent` annotation into the full
  `:rf.route/chain` graph).

## MCP Server panel — dropped

The MCP Server panel is dropped from Causa. The dedicated `causa-mcp`
artefact was envisaged but dropped entirely (rf2-hvl1g, 2026-05-19);
there is no Causa-curated MCP surface to render. AI access to the
running re-frame2 runtime goes through `tools/re-frame2-pair-mcp/`
over raw nREPL — see [`000-Vision.md`](./000-Vision.md) §Where Causa
fits and DESIGN-RATIONALE.md Lock #6 supersedence.

Trace events tagged `:origin :re-frame2-pair-mcp` (the new agent-origin tag)
appear in the **Trace tab** like any other tagged trace event — visible
when the `event` chip is ON. No special-purpose tab; the Trace tab's
filter-pill UX (per [`018-Event-Spine.md`](./018-Event-Spine.md) §5.3)
covers "show me only what the agent did" via an IN pill on `:origin
re-frame2-pair-mcp`.

## Settings popup — v1 ships

The Settings popup modal (trigger: `,` / `s` / ribbon `⚙`) is the
transient overlay through which the user tunes Causa's preferences.
The architectural shape — modal not panel, persistence-on-commit,
section-per-row — is normatively specified in
[`018-Event-Spine.md`](./018-Event-Spine.md) §9. This section
backfills what v1 actually ships.

### v1 tab inventory

| Tab | What it carries |
|---|---|
| **General** (default) | Text-size slider (range 10–18 px; writes the `--rf-causa-text-size` CSS custom property on the shell root + `<html>` — the **user knob**, pre-existing) · Density radio (`:compact` / `:cosy`; writes the `--rf-causa-font-size` CSS custom property — the **type-scale anchor** per rf2-n8i2c, separately tracked from `--rf-causa-text-size`) · Panel-position radio (`:right-rail` / `:popout` / `:fullscreen` — routes to `mount/open!` / `mount/popout!` / `mount/open-overlay!` via the browser API exports) · Panel-width-px slot (number; default 480; written by the resize handle per [`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance); no in-popup widget — the panel's drag handle is the affordance) · "Auto-open Causa when an issue is observed" checkbox |
| **Filters** | Pointer into the auto-filter pills feature. When the `day8.re-frame2-causa.filters` ns is on the classpath the tab renders an "Open auto-filter UI" button that dispatches `:rf.causa.filters/open`; otherwise it shows the "install the feature first" hint. The full pill management surface lives in the ribbon (per [`018-Event-Spine.md`](./018-Event-Spine.md) §7) — this tab is a discoverability handle. |
| **Theme** | Dark (default) / Light radio. Toggles the `rf-causa-theme-{dark,light}` class on the shell root + `<html>`. Accent picker deferred. |

**v1 ships:** three tabs (General / Filters / Theme). The fuller
[`018-Event-Spine.md`](./018-Event-Spine.md) §9 catalogue
(Keybindings, Buffer, Popout, Actions) is deferred to follow-on
beads. A Telemetry tab shipped briefly in the initial popup landing
(rf2-9poxq) but was removed (rf2-jh9ws) — Causa transmits no
telemetry, and a toggle pretending to control a non-existent
endpoint was a broken affordance per the text audit (rf2-yn86j).
When telemetry actually ships, the tab returns with real wiring.

### Two CSS custom properties — `--rf-causa-text-size` vs `--rf-causa-font-size`

The General tab carries two independently-tracked CSS custom properties.
They are NOT the same var and they drive different surfaces:

| CSS var | Knob | Surface | Origin |
|---|---|---|---|
| `--rf-causa-text-size` | Text-size slider (10–18 px; default 13) | Causa surfaces that opt-in read `var(--rf-causa-text-size, 13px)` directly — primarily the event-list rows and a small set of inline-style call sites. | Pre-existing user knob |
| `--rf-causa-font-size` | Density radio (`:compact` 12 / `:cosy` 13 / `:comfy` 14 — `:comfy` catalogued for forward-compat, not surfaced in v1) | The whole `theme/tokens.cljc :type-scale` — every typographic size resolves through `calc(var(--rf-causa-font-size, 13px) * <multiplier>)`. Flipping the var rescales every typographic surface in lockstep on the next paint. | rf2-n8i2c / PR #1571 |

Each var has its own write path
(`settings/effects/apply-text-size!` for `--rf-causa-text-size`;
`settings/effects/apply-density-font-size!` for `--rf-causa-font-size`)
and they are persisted as separate settings slots
(`:general :text-size` and `:general :density`). The two knobs are
deliberately decoupled — a user who wants tighter row rhythm without
shrinking the type scale flips density to `:compact` while leaving
text-size at 13; a user who wants larger event-list rows without
rescaling the rest of the chrome bumps text-size while leaving
density at `:cosy`.

### Defaults

| Slot | Default | Rationale |
|---|---|---|
| `:general :text-size` | `13` (px) | Matches `theme/tokens.cljc :type-scale :body`. Writes `--rf-causa-text-size`. |
| `:general :density` | `:cosy` | Matches `theme/tokens.cljc :font-size-default` (13 px). Writes `--rf-causa-font-size` per rf2-n8i2c. |
| `:general :panel-position` | `:right-rail` | Matches the existing `:layout/host-selector` inline-host posture per [`015-Configuration.md`](./015-Configuration.md). |
| `:general :panel-width-px` | `480` | Matches the default inline-host `--rf-causa-inline-width` band. Clamped `[320, 0.9 × viewport-width-px]` on every write. Set by the drag handle (rf2-x8h9y); double-click resets to this default. |
| `:general :auto-open-on-error?` | `false` | The user is in their app, not asking Causa to interrupt them. |
| `:theme` | `:dark` | Causa is a dev tool; the canvas-and-chrome palette in `theme/tokens.cljc` is the dark one. |

### Persistence

- **Storage key:** `re-frame2.causa.settings.v1` (versioned so future
  schema changes can ignore stale payloads without colliding with the
  old shape).
- **One nested map, not one atom per knob** — the round-trip is a
  single `pr-str` of the whole settings shape; serialisation drift
  between knobs is structurally impossible. Loaded from localStorage
  at preload time via `config/load-settings-from-storage!`; applied to
  the live shell before first paint via
  `settings/effects/apply-all!`.
- **Dual-write on change.** `:rf.causa/settings-update [section key
  value]` writes through to (a) the in-process atom in `config.cljc`
  (canonical, drives the localStorage round-trip) AND (b) Causa's
  app-db at `[:settings <section> <key>]` (drives the immediate
  reactive re-render of the popup's controls). Without the dual-write
  the popup's radio buttons would not redraw until the user closed
  and reopened the modal.

### Auto-open-on-error semantics

When `:auto-open-on-error?` flips ON, a sub-watcher is installed
against the existing `:rf.causa/issues-ribbon` sub. On the **first
empty → non-empty** transition (and only when Causa is not already
visible) the watcher dispatches the late-bound `mount/open!` browser
API export. Two install triggers (both idempotent): (1) on toggle
flip-on inside `:rf.causa/settings-update`, and (2) on first Causa
open via `mount/ensure-causa-frame!` when the persisted toggle is
already on. The install is a **defensive no-op pre-mount** — if the
`:rf/causa` frame isn't yet registered, the subscribe would return
nil and `(add-watch nil …)` would throw; the frame-presence guard
makes the early call safe and the watcher lands on first frame
registration. Detached on flip-off.

### Bulk configure! escape hatch

`(causa-config/configure! {:settings <map>})` bulk-replaces the
whole settings map. Shape mirrors the defaults table above. The
popup's per-knob event surface is the normal write path; this key
is for hosts that want to ship a non-default starting posture (e.g.
a corporate fork that wants light theme as the factory default).

### Reset to defaults

`config/reset-settings-to-defaults!` clears the localStorage payload
and resets the in-memory atom to `default-settings`. No popup
affordance ships in v1 (deferred to the §018 §9 "Actions" tab
follow-on).

### Cross-references

- [`015-Configuration.md`](./015-Configuration.md) — host-facing
  `configure!` surface; `{:settings <map>}` bulk-set; `:editor` /
  `:project-root` / `:layout/host-selector` / `:launch/auto-open?` /
  `:trace/show-sensitive?` enumeration.
- [`018-Event-Spine.md`](./018-Event-Spine.md) §9 — full architectural
  contract (modal not panel, why; reset semantics; future sections).

## Vision — auxiliary content growth

### Event tab — per-fx wire-boundary diff

**Bug class:** "I dispatched event X; it issued an HTTP request; the
UI updated incorrectly. What went over the wire? What came back? What
did the handler apply?"

The Event tab's "fx handlers that ran" block grows a **rich expand
block per managed-effect fx** showing the entire wire interaction:
request payload (post-elision) → wire transit (status / headers /
timing waterfall) → response → handler dispatched → app-db slice
touched. One template; five surfaces (HTTP, WebSocket, machine
`:invoke`, SSR `:rf.server/*`, flows). See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.1.

### Event tab — `:on-match` event chain (Routes)

When the focused cascade is a routing cascade
(`:rf.route/navigate` or `:rf.route/handle-url-change`), the Event
tab's "fx handlers that ran" block adds a dedicated `:on-match`
dispatch chain sub-section showing each loader event, drain duration,
and any `:on-error` consequence. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.2.

### Event tab — retry timeline

When an `:rf.http/managed` retried, surface the per-attempt timeline
(attempt id · result · category · backoff interval · total elapsed)
under the fx row. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.3.

### Event tab — head model inspector (SSR)

When the focused cascade involved a `reg-head` resolution, surface
inputs (db slice + route) → head model output → rendered HTML head,
in three columns. See
[`006-Hydration-Debugger.md`](006-Hydration-Debugger.md)
§Vision §Head model inspector.

### Issues tab — pending-navigation card

When `:rf/pending-navigation` is set in app-db (a `:can-leave` guard
rejected), surface it as a yellow card at the top of the App-db tab
+ the Issues tab. Shows the requested URL, the reason, the rejecting
route, the rejecting guard, and three action buttons (re-evaluate /
force continue / cancel). See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.6.

### Issues tab — flow cascade-halt alarm

When `:rf.error/flow-eval-exception` fires, surface a high-priority
entry listing the subsequent flows that did NOT run (the four-rule
cascade-halt rule per Spec 013). See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.4.

### Issues tab — open-redirect / CRLF / trusted-shell advisories

Three security-class advisories surface in Issues at `:advisory`
severity (different from `:error` / `:warning`, not pushed to top):

- Open-redirect when `:rf.server/redirect` uses caller-untrusted
  input (Spec 011 rf2-zfm8v).
- CRLF in header value when `:rf.error/header-invalid-value` fires.
- Trusted-shell opt advisory when `:head` / `:body-end` /
  `:script-src` carry caller-controlled strings.

Causa is a debugger, not a linter — advisories are quiet by default;
configurable to "loud" via Settings → Trace → "Security advisories".

### App-db tab — `:rf/route` slice always-visible

The `:rf/route` slice is structured and small; it pins at the top of
the App-db tab under a `[reserved]` group banner, always-expanded,
with each sub-key on its own line. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.11.

Note that the Routing tab (§Routing tab above, rf2-nrbs9) is the
primary lens for routing — including the route tree, current match,
and FROM/TO nav transitions. The App-db slice pin is the raw-data
echo for users who want to inspect the slice alongside other app-db
state.

### Settings popup — full 6-tab catalogue

rf2-ttnst (Mike 2026-05-19 §0ter.4 walkthrough; shipped via PR #1518)
locked the Settings popup at **6 tabs**: **General · Theme · Filters ·
Keybindings · Buffer · Diff**. The locked inventory plus per-tab
content sits in [`007-UX-IA.md` §Settings popup](./007-UX-IA.md#settings-popup-modal-overlay)
(the canonical UX surface) and [`018-Event-Spine.md` §9](./018-Event-Spine.md#9-settings-popup)
(the architectural contract). This Vision block historically catalogued
a different 8-row aspiration (Theme / Density / Editor / Trace v1 +
Keybindings / Buffer / Popout / Actions future); it is superseded.

What landed under rf2-ttnst that the earlier aspiration did not anticipate:

- **Density** folds into **General** (no separate tab).
- **Editor** + **Trace** fold into **General** as power-user knobs.
- **Diff** is its own tab (hiccup-diff opt-in + density-sensitive
  layout), not a sub-section of another tab.
- **Keybindings** v1 ships READ-ONLY; the chord-rebind UI is the v1.1
  follow-on.
- **Buffer** ships with `:buffer/retained-epochs` /
  `:trace-buffer/keep` / `:app-db/inspector-collapse-threshold` + a
  "Clear buffer now" button (confirm modal).
- **Popout** folds into General's Panel-position radio — no own tab.
- **Actions** dropped — factory-reset stays code-only
  (`config/reset-settings!`); the "Clear buffer now" affordance under
  the Buffer tab covers the only destructive op users have asked for.

See [`007-UX-IA.md` §Settings popup](./007-UX-IA.md#settings-popup-modal-overlay)
"Dropped from earlier drafts" for the full deletion ledger.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) — the canonical-questions + 7-tab
  inventory.
- [`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md) —
  the 5-idioms × 4-areas matrix driving the per-tab content growth
  above.
- [`007-UX-IA.md`](./007-UX-IA.md) — typography, colour tokens,
  density gradients, keyboard map.
- [`018-Event-Spine.md`](./018-Event-Spine.md) — 4-layer chrome,
  spine binding (`:rf.causa/focus`), per-tab content placement,
  Settings popup, data-classification rendering contract.
- [`012-Views.md`](./012-Views.md) — Views tab content (where Flows
  surface).
- [`013-Trace-Bus.md`](./013-Trace-Bus.md) — the trace ring every tab
  filters from.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) — the
  exhaustive `:rf.causa/*` subs + events + fxs each tab registers.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  — the framework's trace-event vocabulary tabs read; the User-Timing
  entries Chrome DevTools' Performance tab renders (cross-link
  replacing the dropped Performance panel).
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) §`reg-fx`,
  §`:fx-overrides` — what the Event tab "fx handlers that ran" block
  surfaces.
- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — what the Views
  tab surfaces (under "Re-rendered" group).
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the
  framework substrate the Routing tab projects: the registrar
  (`reg-route` + `(rf/registrations :route)`), the `:rf/route` slice,
  and the `:rf.route.nav-token/allocated` emit the panel scans for
  the FROM/TO marker derivation.
