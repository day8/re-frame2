# 003-Machine-Inspector

The Machines tab (tab 5 of 6 in the 4-layer chrome — see
[`018-Event-Spine.md`](018-Event-Spine.md) §5) renders a Stately-quality
state-chart per registered machine, plus an interactive simulation
mode (UC1) and dynamic multi-instance views (UC2 Mode A/B/C). The
state-chart layout primitive lives Causa-internal at
`tools/causa/src/day8/re_frame2_causa/chart/{layout,svg,interaction}.cljc`.

## Architectural posture

**The `tools/machines-viz/` artefact is gone** (collapsed into Causa
in a separate PR; this spec already reflects the post-collapse shape).
The ELK+SVG layout primitive is Causa-internal. The Mermaid text
emitter relocates to `implementation/machines/src/re_frame/machines/mermaid.cljc`
so the framework can re-export `(rf/machine->mermaid <id>)` without a
tool-jar dependency.

```
tools/causa/  ─requires→  implementation/machines/
   (chart primitive: chart/{layout,svg,interaction}.cljc)
```

The Causality popover (see [`018-Event-Spine.md`](018-Event-Spine.md)
§10) reuses the same ELK+SVG primitive for ancestor-chain +
descendants-tree rendering. Two consumers, one layout engine.

## Tab placement

- **Tab id:** 5 of 6 (`m` mnemonic).
- **Spine binding:** reads `:rf.causa/focus`. The Machines tab inherits
  the ribbon's selected frame; if a user has a machine spawned in
  `:app/dialog`, they need to select `:app/dialog` in the picker to see
  it.
- **Isolation invariant:** the tab shows ONLY the selected frame's
  machines per [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3.

## Definition view — Mode A resting state

The Machines tab opens in **registry-index** mode when no specific
machine is selected (showing live count per definition + dormant
defs). When a machine is selected, the inspection view renders:

```
╭─ Machine Inspector · :checkout ─── frame: :app/main ── Sim ○ ──╮
│ 6 states · 11 transitions · 2 spawned children · src/cart/...:42 │
├──────────────────────────────────────────────┬─────────────────┤
│  [diagram canvas: ELK+SVG primitive]         │ Metadata          │
│                                              │ ─────────────     │
│  (states, edges, source coords inline)       │ id   :checkout    │
│                                              │ tags #{:user-flow}│
│                                              │ data {...}        │
│                                              │ Transitions (11)  │
│                                              │ ▶ :checkout/begin │
│                                              │ ▶ :pay/approved   │
│                                              │ ▶ :pay/declined   │
│                                              │ ...               │
│                                              │ Source            │
│                                              │ src/cart/...:42 ↗ │
├──────────────────────────────────────────────┴─────────────────┤
│ Live instances (0)                                              │
│  ⬚ idle — toggle Sim ↑ to walk topology                         │
╰─────────────────────────────────────────────────────────────────╯
```

When 0 instances: empty hint nudges Sim mode (UC1). When ≥1 instances:
roster (Mode B/C per UC2 §Dynamic Mode A/B/C below).

## UC1 — Sim sub-mode

The interactive simulation mode. **A sub-mode of Mode A** (entry only
when no live instances OR when explicitly toggled on against live
instances — in the latter case, sim runs on a parallel mocked
instance, never touching the live runtime).

### Toggle and persistence

- **Per-(frame-id, machine-id) toggle** persisted to localStorage.
- **`Sim ●` indicator** in the panel header — visually distinct from
  live (cyan) by being amber.
- **`E` key** opens the event picker for "what event do you want to
  fire?"
- **`R` key** resets to initial state.
- **`Esc`** from chart exits Sim mode.

### Visual cues (three reinforcing)

1. **24px tinted banner** across top of panel canvas:
   `▓▓▓▓▓ SIMULATING — no live data; clicks walk the topology ▓▓▓▓▓ ?`
2. **Amber tint** on active-state highlight (live uses cyan; sim uses
   amber — the existing palette reserves cyan for live, red for
   errors, amber for "informational, not a real event").
3. **`Sim ●` header indicator** alongside the toggle.

### Mock `:data` form

Schema-derived (Malli, when registered) row-per-key form in the right
rail; type-inferred when no schema; EDN fallback for unknown shapes.
Actions execute against mocked `:data` so it evolves through the walk.

### Failed-guard handling (strongest opinion in UC1)

Transitions whose guards fail against current `:data` are **LISTED but
greyed**, not silently hidden. **`Shift+Enter` fires-despite-guard**
with the message *"firing despite guard — this would not happen in a
live instance."*

This diverges deliberately from Stately (who silently hides failed
transitions); listing them with the verdict makes the guard's role
part of the lesson:

```
Available transitions from :authing (current mocked :data: {…}):

  ▸ :auth/login-ok             → :ready         (guard ✓ passed)
  ▸ :auth/login-fail           → :error         (guard ✓ passed)
  ▸ :auth/two-factor-required  → :awaiting-2fa  (guard ✓ passed)
  ▸ :auth/admin-override        → :admin        (guard ✗ failed — :data.user.role ≠ :admin)
       Shift+Enter to fire despite guard
```

### Skip-guards toggle

Opt-in (`G` chord or "Ignore guards" checkbox). For the legitimate use
case "walk topology before writing guards." Default off.

### Sim trail

Below diagram: horizontal `state→state` sequence; hover rewinds without
truncating; click truncates ("forked here"). Distinct from live
transition-history ribbon (no timestamps, no source-coord, no
causation — just `state→state`).

### Save as scenario

Clipboard-only for v1; emits `(machines.test/simulate-scenario {...})`
form for pasting into a test file. No in-app saved scenarios library
(violates Lock #4 / Causa-as-product creep).

## UC2 — Dynamic Mode A / B / C

Three view modes adapt to instance count. The mode is selected
automatically based on `(count (rf/machine-instances <frame-id>
<machine-id>))`; user can pin a mode via the panel header toggle.

| Mode | Trigger | Diagram | Above diagram | Below diagram |
|---|---|---|---|---|
| **A — Zero instances** (or Sim on) | 0 live | Neutral OR sim'd walk | Sim toggle | Empty hint OR sim trail |
| **B — Few (1–3)** | 1–3 live | All on same diagram (different hues) | Inline instance tabs | Instance roster + active-instance arc |
| **C — Many (4+)** | ≥4 live | Diagram + cluster-by-state count badges | Search/filter/sort bar + selected breadcrumb | Virtualised instance table + focused-instance arc |

### Per-instance highlighting on SAME diagram

Two reasons (Lock #11 topology/runtime separation):

1. **Layout cost.** Per-instance diagrams = N layout passes. Same
   diagram = 1 layout pass + O(N) attr mutations.
2. **Topology IS topology.** Two instances of `:checkout` walk the
   same graph. Separate charts imply they could diverge; they can't.

In Mode B, each instance gets a stable hue (assigned at spawn,
persisted per id) — `#c-001` always cyan, `#c-047` always magenta. The
focused instance's active state has the strong amber ring; others
render as thinner rings with `◀── #c-047 ●` side-tag arrows.

In Mode C (4+ instances), per-instance overlays would saturate the
diagram. Switch to **cluster-by-state count badges** on each state
node (`●12` = 12 live instances in `:authing`); state background tint
scales with count.

### Mode C — Erlang-Observer pattern

The Mode C table is the closest peer for "thousands of instances, see
them grouped, click into one." Erlang's Observer Process tab is the
structural template:

- **Virtualised table** above the diagram — 8 rows visible at default
  density; scrollable; row icons per status (`●` focused, `◯` live,
  `╳` errored, `⊘` final, `⊕` recently spawned, `⊖` recently
  destroyed); sortable by any column.
- **Cluster-by-state count badges** on the topology — the aggregate
  read.
- **Aggregate row** (top of table or sticky footer):
  `Total: 47 live · 12 :authing · 28 :sending · 3 :error · 4 :ok`.
- **Shift-click multi-select** for divergence highlight: shift-click an
  instance → secondary focus (violet); diagram renders two highlights
  + arc strip splits into two stacked lanes; the **first event where
  the arcs diverge** auto-marks with `⚠` in both lanes. The diff that
  answers "I clicked Submit twice and got different results."

Shift-click a third instance → tertiary focus (cyan); cap at 4 lanes.

### Per-instance mini-scrubber

When a machine instance is focused, an in-arc-strip mini-scrubber lets
the user rewind THAT instance without affecting the rest of Causa:

- **Global timeline** = L2 event list + ribbon `[◀ ▶ ⏭]` (every Causa
  surface rebinds).
- **Per-instance scrubber** = inline `◀ scrub ▶` widget in arc strip +
  the focused-instance highlight gets a `⏪` glyph appended (only the
  diagram's focused-instance highlight changes; other instances
  continue live).

This mini-scrubber is intra-tab content (inside the Machines tab); it
is NOT related to the (now-dead) bottom rail / global scrubber. The
global scrubber surface is the ribbon `[◀ ▶ ⏭]` cluster + the L2 event
list per [`018-Event-Spine.md`](018-Event-Spine.md) §6.

## Spec 005 actor lifecycle — full XState parity

Causa renders the supervision tree. The framework contract behind it:

- **Invoke auto-cleanup** — `:invoke`d actors are state-scoped; leaving
  the invoking state destroys the child. Stately/XState semantics.
- **Spawn explicit destroy** — `[:rf.machine/spawn]` returns an
  instance id; explicit `[:rf.machine/destroy <id>]` to clean up.
- **Parent-stop cascades** — destroying a parent cascades destroy to
  all children (supervision tree, Erlang OTP style).

Causa surfaces this in:

- **Parent/child relationship section** in metadata rail.
- **Spawn ancestry** in instance tab labels (`#c-001 ... by [:app/start]`).
- **`⊕`/`⊖` markers** in the arc strip at spawn/destroy moments.
- **Recent-deaths buffer** — 10s after destroy, the row red-fades and
  disappears; arc is preserved in `recently-destroyed` sub-list (last
  10 entries) for 30s; still reachable from time-travel.
- **`:invoke-all` inline children** — render as decorated rows beneath
  the invoking state, per Spec 005.

## Selection and switching

The panel header carries a machine picker — a dropdown over
`(rf/machines frame-id)`. The dropdown shows machine-id, current
state, and a tiny activity indicator (filled green if transitioned in
the last 5s; grey otherwise).

Switching the active frame via the ribbon (Layer 1) re-binds the
picker to that frame's machines. Machines spawned via
`:rf.machine/spawn` (dynamic actors) appear in the picker with their
gensym'd id; named addressing via `:system-id` is surfaced as a
parenthetical (e.g. `:gensym-42 (:auth/main)`).

## Transition history ribbon

A horizontal scrubbable list under the chart. Each entry is one
`:rf.machine/transition`:

```
[14:32:01 :login    → :authing ] [14:32:02 :authing  → :error  ]
[14:32:04 :error    → :idle    ] [14:32:11 :idle     → :login  ]
```

- **Click an entry** → chart rewinds to show the state pre-transition,
  with the inbound edge highlighted. The rewind is **view-only** (same
  passive-scrubbing rule as the global spine in
  [`018-Event-Spine.md`](018-Event-Spine.md) §6) — Causa does not call
  `restore-epoch` from this affordance.
- **Hover** → tooltip with the triggering event vector and guard
  result.
- **Microstep entries** (from `:rf.machine.microstep/transition`) are
  rendered slightly indented under their outer transition.

## Data sources

Per Spec 005 and Spec 009:

| Surface | Used for |
|---|---|
| `(rf/machines frame-id)` | Enumerate registered machines (drop-down in panel header). |
| `[:rf/machines <id>]` slot in `app-db` | Read current snapshot; deref drives the live-highlight. |
| `:rf.machine/transition` traces | Build the transition-history ribbon. |
| `:rf.machine.microstep/transition` traces | Microstep replay within an `:always`-driven cascade. |
| `:rf.machine.timer/scheduled` / `-fired` / `-stale-after` | Drive `:after` countdown rings. |
| `:rf.machine.invoke-all/*` traces | Render `:invoke-all` join state (started, all-completed, some-completed, any-failed). |
| `:rf.machine/spawned` / `-destroyed` | Render spawn/destroy lifecycle in the parent's chart. |
| `:rf.machine/done` | Mark `:final?`-state entry, before the auto-destroy. |
| `:rf.machine/system-id-bound` / `-released` | Surface `:system-id` reverse-index activity in a sidebar. |
| Source-coord stamping | Every clickable element jumps to source. |

## Source-coord integration

Every clickable element on the chart jumps to source:

| Element | Source surface |
|---|---|
| State node | `:rf.machine/source-coords {:state <prefix-path>}` — opens the state's registration in the editor. |
| Edge | `:rf.machine/source-coords {:transition [<from> <event>]}` — opens the transition entry's source line. |
| Guard | The guard function's `:rf.machine/source-coords {:guard <name>}` — opens its definition. |
| Action | The action function's `:rf.machine/source-coords {:action <name>}`. |

Source coords are surfaced as copyable `file:line` chips; clicking
opens the file via the editor URL handler the user configured in
Settings → Source. See [`007-UX-IA.md`](007-UX-IA.md) §Editor protocol
matrix.

When the dispatch coord is missing (e.g., a synthetic dispatch from a
machine action), Causa falls back to the registered handler's source
coord with an inline `(?)` annotation that hovers a tooltip ("This
coord is the handler's; the dispatch was synthesised by `:auth/main`
at state `:authing`.")

## `:invoke-all` viz

When a state declares `:invoke-all`, the chart shows the N parallel
children as a horizontal row of mini-machines, each with their own
state. The join condition (`:all` / `:any` / `{:n N}` / `{:fn ...}`)
renders as a label below the row.

As children complete or fail, the row updates:

- A child reaching `:final?` colours green and marks `done?`.
- A child failing colours red and marks `failed?`.
- When the join resolves, the parent state advances; the children
  collapse to a summary (`3/5 completed, 2 cancelled`).

## Auto-pan

Large machines (many states, deep nesting) are wider than the panel.
Causa auto-pans on every transition so the active state stays in view.
The user can disable auto-pan via the panel header toggle (kept state
per-machine in localStorage).

## Performance

- **Chart re-layout** runs only on registry change (machine
  re-registered) or compound-state expand/collapse. Live transitions
  do not re-layout — they just update node highlights.
- **`:after` countdown rings** update at 60Hz only when the panel is
  visible; backgrounded panels pause the countdown render (the timer
  itself runs at framework-time).
- **Transition history** virtualises past 200 entries; older entries
  scroll into view but are not retained in DOM.

## Layout engine — ELK with layered fallback

Two layout engines flow through `chart/svg.cljc`'s renderer behind the
same `{:nodes :edges :width :height :initial-id}` data shape:

| Engine | Source ns | When used |
|---|---|---|
| **ELK.js** (preferred) | `chart/elk_layout.cljs` | Browser session once the lazy import resolves; produces orthogonal edge routing + layered placement. |
| **Layered fallback** | `chart/layout.cljc` (`layered-fallback`) | Sync, pure, JVM-runnable. Used by the JVM + node-runtime test corpus, and as the runtime fallback whenever ELK is unavailable. Simple BFS-rank placement + straight-line edges. |

### Lazy load + render-pulse cadence

ELK.js is a ~250 kB browser bundle (gzipped). Bundling it into every
Causa dev session would inflate the preload cost whether the user
opens the Machines tab or not, so the loader mirrors the Causality
popover pattern (per [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md)
§Causality popover):

1. First open of the Machines tab triggers a `js/import` of
   `"elkjs/lib/elk.bundled.js"`.
2. The loader atom tracks `nil → :loading → {:elk <inst>} | {:failed <msg>}`.
3. ELK's `layout` returns a Promise — the synchronous renderer can't
   wait. So the panel renders the **layered-fallback layout
   immediately**, kicks the ELK pass in the background, caches the
   result keyed on `[definition direction]`, and dispatches a no-op
   render-pulse event so the subscribe-driven view re-runs and picks
   up the cached ELK positions on the next tick.
4. Subsequent renders of the same `[definition direction]` resolve
   synchronously from the cache; the cache holds the most-recent
   layout (one machine inspected at a time is the common case).

### Fallback semantics

The fallback engages whenever ELK is not ready or the layout call
rejects — three concrete paths:

| Trigger | Cause |
|---|---|
| Offline / CSP block | The dynamic `js/import` rejects with a module-not-found. |
| Test rigs | Node + JVM runtimes have no bundler-resolvable `elkjs`; the import is unavailable. |
| Transient layout failure | ELK's `.layout` Promise rejects (rare). The cache is left untouched; the next render-pulse retries. |

In every fallback path the chart still mounts — the `layered-fallback`
shape is data-compatible with ELK's positioned output. The user gets
a readable chart that loses orthogonal routing but keeps the
node/edge content. v1 does NOT surface a "layout engine: fallback"
status indicator on the Machines tab itself (the chart just renders);
the Causality popover surfaces an equivalent hint because its graph
density makes the missing-orthogonal-router more visually jarring.

### v1 ships (deferred follow-ons)

- **Edge polylines from ELK's bend points** — v1 collapses ELK's
  multi-point routes to straight lines between source/target centres.
  `chart/svg.cljc` already supports the multi-point case via
  `path-from-points`; lifting ELK's bend points into the `:points`
  slot is follow-on work.
- **Compound-state hierarchical containment in ELK** — v1 ships every
  state as a flat ELK child + relies on the existing dashed-border
  treatment for visual grouping. ELK's hierarchical mode (parent
  containers with child layout) is a richer follow-on.
- **Unified ELK loader** — both this surface and the Causality
  popover (`popover/causality_graph.cljs`) hold their own loader atom
  so the two consumers fail independently. In practice once one
  succeeds the other will too. A future bead can fold the loader into
  a shared `chart/elk_loader.cljs`.

## Share affordance

Right-click on the machine chart (or use the panel header's `⋯`
overflow menu) surfaces a **Copy machine as…** sub-menu:

| Format | Output |
|---|---|
| **PNG** | Rasterised chart at 2x DPR, transparent background, current-state highlight included. Copied to clipboard as an image. |
| **SVG** | Vector chart with embedded fonts (so it renders identically when pasted into a doc or a Figma frame), current-state highlight included. Copied to clipboard as `image/svg+xml`. |
| **Mermaid text** | Markdown-friendly Mermaid block. Emitted by `(rf/machine->mermaid <id>)` (lives in `implementation/machines/`). Copied as plain text. |

Inspired by Stately Visualizer's registry-style share. Use cases:
dropping a chart into a pull-request description, into a design doc,
into a Slack thread, or onto a whiteboard during a design discussion.

### No Stately compatibility

Causa is the canonical rendering surface for re-frame2 machines. There
is no `machine->xstate-json` export; no Stately Studio bridge. Stately
compat would constrain re-frame2 machine semantics to XState's subset;
we want to evolve freely.

### NOT a session export

This is **not** a session export. Lock #4 holds: Causa never
serialises the running trace stream, the epoch buffer, the app-db
history, or the conversation — those are session-local by design.

PNG / SVG / Mermaid serialise a **static machine definition** only —
topology + current-state hint (purely visual). The serialised form is
reproducible from the registry alone.

### Performance

- PNG / SVG rendering: client-side via the same SVG primitive the
  inspector uses, at the chart's natural size. Sub-50ms for charts up
  to ~80 nodes.
- Mermaid emit: pure data → text projection; sub-1ms for typical
  machines.

### Accessibility

The PNG / SVG outputs include `<title>` and `<desc>` element (SVG) or
alt-text companion (PNG, as a sidecar `text/plain` payload on the
clipboard) summarising the machine: its id, its current state, and
the number of states / transitions.

## Empty state

When no machines are registered:

```
   No machines registered.
   Once your app registers a machine via reg-machine (Spec 005),
   it will appear here with:
   • Live state-chart highlighting
   • Transition history
   • :after countdown rings
   • UC1 simulation (Sim toggle)
   → Read about machine integration
```

## Accessibility

The state-chart is a graph, which is hard for screen readers. v1.0
ships **without** a chart alt-view; the alt-view is a v1.1 commitment.
Until then, the transition-history ribbon and the machines picker are
the accessible surfaces — both are text-heavy and reach the same data.

## Cross-references

- [`018-Event-Spine.md`](018-Event-Spine.md) — Machines tab placement
  in the 4-layer chrome; spine-binding contract; isolation invariants.
- [`007-UX-IA.md`](007-UX-IA.md) — typography, colour tokens, editor
  protocol matrix.
- [Spec 005 — StateMachines](../../../spec/005-StateMachines.md) — the
  framework contract Causa renders.
- [Spec 009 — Instrumentation](../../../spec/009-Instrumentation.md) —
  the `:rf.machine/*` trace contract Causa consumes.
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — the
  `:rf.causa/*` registry ids for the Machines tab.
- [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) — Sim
  mode + Mode A/B/C dynamic instance test rows.
