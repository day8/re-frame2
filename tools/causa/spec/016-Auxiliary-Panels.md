# 016-Auxiliary-Panels

The normative home for the per-tab content contracts beyond the hero
4-layer chrome architecture in
[`018-Event-Spine.md`](./018-Event-Spine.md). The 6-tab inventory in
[`000-Vision.md`](./000-Vision.md) §The 6-tab inventory is the
top-level navigation map; this doc gives the per-tab implementation
contract a one-shot implementer needs — inputs (subs / events
consumed), main interactions, observable outputs — without having to
reverse-engineer `tools/causa/src/day8/re_frame2_causa/panels/*.cljs`.

The per-tab content this doc covers:

| Tab content | `:rf.causa/panels.*` | Source / phase |
|---|---|---|
| Event tab content (fattened) | `event-detail` | Phase 2 (rf2-op3bz) + folds in former Effects panel content |
| Issues tab content | `issues-ribbon` | Phase 5 (rf2-d1p4o); unified feed per [`018-Event-Spine.md`](./018-Event-Spine.md) §5.4 |
| Routes (lives in App-db tab + Trace tab) | `routes` | Phase 5 (rf2-6blai); see §Routes content below |
| Flows (lives in Views tab "Re-rendered" group) | `flows` | Phase 5 (rf2-83irn); see §Flows content below |

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

The MCP Server panel is dropped from Causa. The `tools/causa-mcp/`
artefact is dropped entirely (separate PR); there is no Causa-curated
MCP surface to render. AI access to the running re-frame2 runtime
goes through `tools/pair2-mcp/` over raw nREPL — see
[`000-Vision.md`](./000-Vision.md) §Where Causa fits.

### AI co-pilot — dropped

The AI co-pilot panel is dropped from Causa. Causa is the human-only
observability surface; AI access is via `tools/pair2-mcp/`. The
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

## Effects content — folded into Event tab

The pre-rewrite Effects panel is GONE. Its content folds into the
**Event tab** (tab 1 of 6) as the "fx handlers that ran" block — see
[`018-Event-Spine.md`](./018-Event-Spine.md) §5.1 for the canonical
wireframe.

Per-fx invocation status (`:error` / `:overridden` / `:skipped` /
`:ok`) renders as inline chips next to the fx-id in that block.
Aggregate "registered fxs" data (which historically lived in the
Effects panel) is reachable via the Cmd-K palette under the `:fx`
source — registered handler ids + invocation counts. No standalone
tab.

## Flows content — folded into Views tab

The pre-rewrite Flows panel is GONE. Flows surface where they actually
matter: in the **Views tab** (tab 3 of 6) "Re-rendered" group, when a
flow's downstream sub recomputed.

When a flow's output sub appears in a view's "invalidated by" list
(per [`012-Views.md`](./012-Views.md) §Three-group layout
Re-rendered), the sub-id renders with a `⊳` flow-glyph prefix to
distinguish flow-output subs from hand-written subs. The flow's input
paths + output path + recompute reason are surfaced in the
per-component drilldown's "Subs consumed" block.

A registered-flows-overview reachable via the Cmd-K palette under the
`:flow` source: flow-id, inputs, output path, last recompute. No
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

## Routes content — folded into App-db tab + Trace tab

The pre-rewrite Routes panel is GONE. Route state is a sub-tree of
app-db; the navigation timeline is trace data. Both surfaces are
reachable in their respective tabs:

- **Active route + params** — the focused frame's `:rf/route` slice
  appears in the **App-db tab** under the path `[:rf/route]` (or
  whatever the frame stores it at). Diff is rendered like any other
  app-db slice. Pin via right-click → "Pin watch".
- **Navigation history** — `:rf.route.nav-token/*` + `:rf.route/url-changed`
  trace events appear in the **Trace tab** when the `event` chip is
  ON (default).
- **Registered routes overview** — reachable via the Cmd-K palette
  under the `:route` source: route-id, path-pattern, `:doc`. No
  standalone tab.

The transition FSM state (`:idle` / `:loading` / `:error`) is part of
the app-db slice and renders inline with the same colour-with-glyph
treatment as before.

## MCP Server panel — dropped

The MCP Server panel is dropped from Causa. The `tools/causa-mcp/`
artefact is dropped entirely (separate PR); there is no Causa-curated
MCP surface to render. AI access to the running re-frame2 runtime
goes through `tools/pair2-mcp/` over raw nREPL — see
[`000-Vision.md`](./000-Vision.md) §Where Causa fits.

Trace events tagged `:origin :pair2-mcp` (the new agent-origin tag)
appear in the **Trace tab** like any other tagged trace event — visible
when the `event` chip is ON. No special-purpose tab; the Trace tab's
filter-pill UX (per [`018-Event-Spine.md`](./018-Event-Spine.md) §5.3)
covers "show me only what the agent did" via an IN pill on `:origin
pair2-mcp`.

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
| **General** (default) | Text-size slider (range 10–18 px; writes the `--rf-causa-text-size` CSS custom property on the shell root + `<html>`) · Panel-position radio (`:right-rail` / `:popout` / `:fullscreen` — routes to `mount/open!` / `mount/popout!` / `mount/open-overlay!` via the browser API exports) · Panel-width-px slot (number; default 480; written by the resize handle per [`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance); no in-popup widget — the panel's drag handle is the affordance) · "Auto-open Causa when an issue is observed" checkbox |
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

### Defaults

| Slot | Default | Rationale |
|---|---|---|
| `:general :text-size` | `13` (px) | Matches `theme/tokens.cljc :type-scale :body`. |
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

## Causality popover — v1 ships

The Causality popover (trigger: `c` key from any tab; mouse:
ancestor-graph icon next to source-coord in Event tab) renders the
focused event's causal graph: ancestor chain + descendants tree on a
centred floating overlay. Architectural shape is normatively
specified in [`018-Event-Spine.md`](./018-Event-Spine.md) §10. This
section backfills v1 implementation specifics.

### Geometry

- **Default size:** 640×480 (matches spec §10). `max-width: 92vw` /
  `max-height: 82vh` so the dialog adapts to small viewports without
  overflowing.
- **Backdrop dim:** 15% black (spec §10).
- **Resize handle:** deferred. v1 ships the default size only; the
  resize-and-remember affordance per spec §10 §Interaction is a
  follow-on bead.

### Layout direction — single-axis with footer toggle

**v1 ships:** the popover renders **a single direction at a time** —
TB (descendants-tree dominant, default) OR LR (ancestor-chain
dominant) — with a footer **LR ↔ TB toggle** persisting the choice
in `:rf.causa/causality-popover-layout` per session.

The §018 §10 "Q12 hybrid: ancestors-LR + descendants-TB in the same
popover" remains the intent, but v1 ships single-axis-at-a-time
because:

- ELK's per-region direction support requires laying out two graphs
  separately and stitching them into one SVG with a divider; that's
  a stretch v1 doesn't yet do.
- The footer toggle is one click; switching between "show me the
  ancestor chain" and "show me the descendants tree" is the common
  mode anyway. The hybrid is the polish.

The Q12 hybrid lands when ELK's per-region wiring is tackled in a
follow-on bead.

### Lazy ELK load + fallback list

The popover uses the same lazy-load pattern as the Machine Inspector
chart (see [`003-Machine-Inspector.md`](003-Machine-Inspector.md)
§Layout engine — ELK with layered fallback): ELK.js loads
asynchronously on first popover open; the fallback path is a flat
list render until ELK resolves.

**Fallback render shape.** When ELK is unavailable (test rig, CSP
block, offline) the popover drops into a `fallback-list` render — a
flat `<ul>` listing the focused event + its ancestors and descendants
with edges shown as `parent → child` lines. The cascade lineage is
fully readable; the visual graph affordance is the only thing lost.
The footer surfaces a "Causality graph unavailable (ELK.js failed to
load)" status hint so the user understands why the layout looks
different.

The fallback is also what the test corpus asserts against — node
runtimes have no bundler-resolvable `elkjs` package and the import
rejects immediately.

### Close + interaction (recap of spec §10)

- **Close:** `Esc`, click backdrop, `c` again, or the `×` icon in the
  header.
- **Node click:** dispatches `:rf.causa/focus-cascade <id>` AND
  `:rf.causa/causality-popover-close` — the user landed somewhere new
  and the popover's job is done. Spine rebinds; tab content reflects
  the new focus.
- **Tab switch under popover (`1`–`6`)**: popover stays open; user
  can survey the cascade graph against changing tab content
  underneath.

### Cross-references

- [`018-Event-Spine.md`](./018-Event-Spine.md) §10 — full
  architectural contract (trigger, geometry, interaction matrix,
  depth limits, empty states).
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Layout
  engine — sibling consumer of the same ELK loader pattern.

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

### Settings popup — full 6-section catalogue

v1 ships the Settings popup with **4 sections** (Theme, Density,
Editor, Trace). Future: **6 sections** (add **Keybindings** for in-app
rebind UI; add **Buffer** for retained-epochs control; add **Popout**
for popout-geometry; add **Actions** for factory-reset + clear-buffer):

| Section | v1 | Future |
|---|---|---|
| **Theme** | dark / light / dim | + custom palettes |
| **Density** | compact / cosy / comfy | (stable) |
| **Editor** | URI scheme + project-root | (stable) |
| **Trace** | sensitive-data gate + filter algebra | + security-advisory toggle |
| **Keybindings** | — | Full rebind UI; conflict-detection; reset-to-defaults |
| **Buffer** | — | Retained-epochs slider; clear-buffer button; filter persistence schema bump warning |
| **Popout** | — | Window-geometry restore; "always popout" toggle |
| **Actions** | — | Factory-reset (red button); clear-buffer-now; reload-without-state |

### Causality popover — full Q12 hybrid layout

The Causality popover ships v1 with single-axis vertical layout.
Future: hybrid LR-ancestors + TB-descendants per-region; resizable +
per-session persistence; depth-disclosure pills + per-segment hover
tooltips; machine-edge types. See
[`001-Causality-Graph.md`](001-Causality-Graph.md) §Vision.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) — the canonical-questions + 6-tab
  inventory.
- [`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md) —
  the 5-idioms × 4-areas matrix driving the per-tab content growth
  above.
- [`007-UX-IA.md`](./007-UX-IA.md) — typography, colour tokens,
  density gradients, keyboard map.
- [`018-Event-Spine.md`](./018-Event-Spine.md) — 4-layer chrome,
  spine binding (`:rf.causa/focus`), per-tab content placement,
  Settings popup, Causality popover, data-classification rendering
  contract.
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
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — route slice
  appears in App-db tab; navigation timeline in Trace tab.
