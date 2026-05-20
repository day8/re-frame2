# 003-Machine-Inspector

> **2026-05-19 collapse note (rf2-y9xmf):** the Runtime Machines panel
> is now **event-driven only**. The panel is BLANK when the currently
> focused event triggered no machine transitions; it renders one
> per-machine section (topology + transition highlight + guards +
> actions + cancellation cascade + `:after` rings) when the focused
> event did trigger transitions. Per-machine prev/next navigation
> walks the spine's epoch history to the prior/next event that ALSO
> touched the focused machine.
>
> **What this collapse removed from the Runtime surface:** the Mode A/B/C
> picker chrome, the sub-strip (Topology/Sim/Instances/Cluster), the
> picker-driven Sim ribbon UI, the multi-instance aggregate (Mode C
> cluster view), the per-instance arc + mini-scrubber, and the
> Browse-all entry point. The **Sim engine** has since been re-hosted
> under the Static Machines surface's Sim sub-mode (sibling bead
> rf2-r4nao landed — sub/event family at
> `:rf.causa.static.machines/sim-*`, view at
> `tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs`); the
> **browse-all index** ships as the Static Machines surface's
> master-detail left pane. Sections below describing those removed UI
> ribbons are kept as historical design-reference; they no longer
> describe what the Runtime Machines panel renders.

The Machines tab (tab 5 of 7 in the 4-layer chrome — see
[`018-Event-Spine.md`](018-Event-Spine.md) §5) renders a Stately-quality
state-chart per registered machine. Post-rf2-y9xmf the panel surfaces
the focused event's machine activity only; the interactive simulation
(UC1) + dynamic multi-instance views (UC2 Mode A/B/C) descriptions in
later sections are normative for the Static re-host, NOT for the
Runtime tab. The state-chart layout primitive is **owned by
`tools/machines-viz/`** as its own tool jar (canonical implementation
at `tools/machines-viz/src/day8/re_frame2_machines_viz/chart/{layout,svg,interaction}.cljc`,
per rf2-o9arp / PR #1570); Causa re-exports the public chart API via
thin shim namespaces (`day8.re-frame2-causa.chart.svg/...` →
`day8.re-frame2-machines-viz.chart.svg/...`) so the panel surface
imports a Causa-namespaced public name while the implementation lives
one tool over.

The Machines tab is the **single most distinctive Causa surface** because
re-frame2's machine substrate (Spec 005) carries the richest runtime
behaviour in the framework — cancellation cascades, `:after` timers,
`:invoke-all` joins, microstep loops, hierarchical state transitions,
parallel regions, supervision trees. Causa is the only place these
contracts become legible. Bug-class motivation for each major feature in
[§The bug catalogue](#the-bug-catalogue) below; see also
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.1.

## Post-collapse Runtime panel shape (rf2-y9xmf)

The Runtime Machines panel renders one of three states:

1. **No machines registered** → empty-state message: "No machines
   registered."
2. **Machines registered, focused event has no transitions** → BLANK
   state: "No machine activity in the focused event."
3. **Focused event triggered ≥1 transitions** → one section per
   transitioned machine, document order:
    - Header: `<from-state> → <to-state>` with the event vector right-
      aligned.
    - Topology chart (ELK+SVG primitive) with the FROM state drawn
      dashed/accent-violet and the TO state bold/cyan; connecting edge
      emphasised. `:after` countdown rings overlay armed timer
      states.
    - Guards list (when the trace carried guard-evaluated events for
      this transition).
    - Actions list (when the trace carried action-ran events).
    - Cancellation cascade (inline, via the existing
      `[cancellation-cascade/SidePanel]` reg-view — dormant when no
      cancellation lands in the trace window).

The header carries:
- A **Share button** (right-aligned).
- A **prev/next nav** (`◀ Prev` / `Next ▶`) that walks the spine's
  epoch history to the prior/next epoch whose cascade ALSO touched
  the focused machine. The "focused machine" is the head section's
  `machine-id` (the cascade may touch several; nav scope is the first).
  Hidden when no machine is in scope (the blank or empty-state branches).

## What is NOT in the Runtime panel post-rf2-y9xmf

- No machine picker (the panel is event-driven; no exploratory
  selection).
- No sub-strip (Topology / Sim / Instances / Cluster).
- No Mode A/B/C dynamic instance views; no `:rf.causa/forced-machine-
  mode` slot.
- No Sim toggle / Sim side-rail UI in the Runtime panel header. The
  `SimRail` view (post-rf2-r4nao rename of the historical
  `SimSideRail`) ships under
  `tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs` and
  is mounted by the Static Machines surface; the
  `:rf.causa.static.machines/sim-*` engine events / subs are
  registered against the `:rf/causa` frame from that ns.
- No per-instance arc overlay; no mini-scrubber. The
  `:rf.causa/machine-scrubber-position` SLOT survives (read/write
  events still registered) because the share-URL round-trips it; the
  scrubber UI itself is gone.
- No Browse-all UI entry. The browse-all index algebra in the
  helpers ns remains for the Static re-host.

The historical Mode A/B/C / Sim sub-strip / arc / scrubber / cluster
descriptions in subsequent sections are preserved as design-reference
for the rf2-r4nao Static re-host; they DO NOT describe the Runtime
panel.

## Architectural posture

**`tools/machines-viz/` owns the chart.** Per rf2-o9arp / PR #1570 the
MachineChart primitive (ELK+SVG layout, the layered fallback, the
interaction layer) lives in its own tool jar at
`tools/machines-viz/src/day8/re_frame2_machines_viz/chart/{layout,svg,interaction}.cljc`.
Causa is a **consumer** of that primitive: the panel surface
(`panels/machine_inspector*.cljs`) imports Causa-namespaced public
names (e.g. `day8.re-frame2-causa.chart.svg/MachineChart`) which are
thin re-export shims pointing at the machines-viz implementation
(`day8.re-frame2-machines-viz.chart.svg/MachineChart`). One
implementation, one layout cache, one ELK loader — re-used by Causa,
Story (per-variant observability ribbons), the read-only viewer page,
and any host-app drop-in that wants the chart without Causa's panel
chrome.

The Mermaid text emitter lives at
`implementation/machines/src/re_frame/machines/mermaid.cljc` so the
framework can re-export `(rf/machine->mermaid <id>)` without a
tool-jar dependency.

```
tools/causa/  ─requires→  tools/machines-viz/  ─requires→  implementation/machines/
   (panel surface)           (chart primitive:                (machine registry +
   (chart re-export           chart/{layout,svg,interaction}    runtime substrate)
    shims)                    .cljc)
```

### Re-export shim contract

The thin shims keep Causa's public surface stable while letting the
chart move independently. The contract is one-for-one: every
Causa-namespaced public name re-exports a single machines-viz
implementation name; no shim adds behaviour. Adding a chart feature
means landing it in machines-viz first and re-exporting from Causa
second.

| Causa-namespaced public | Re-exports |
|---|---|
| `day8.re-frame2-causa.chart.svg/MachineChart` | `day8.re-frame2-machines-viz.chart.svg/MachineChart` |
| `day8.re-frame2-causa.chart.layout/layered-fallback` | `day8.re-frame2-machines-viz.chart.layout/layered-fallback` |
| `day8.re-frame2-causa.chart.interaction/on-state-click` | `day8.re-frame2-machines-viz.chart.interaction/on-state-click` |

(Indicative — the live shim ns enumerates the full surface; the
machines-viz API itself is the source of truth.) Embedders who want
the chart alone depend on `tools/machines-viz/` directly; embedders
who want Causa's panel chrome get the chart transitively via Causa.

### See also

- [`000-Vision.md`](000-Vision.md) §Where Causa fits — the
  Causa → machines-viz → implementation/ dependency arrow at the
  whole-tool level.
- [`008-Embedding-Contract.md`](008-Embedding-Contract.md) — the
  full-shell embed contract. Hosts that want only the chart skip
  Causa entirely and depend on `tools/machines-viz/` directly.

## Tab placement

- **Tab id:** 5 of 7 (`m` mnemonic). Routing is now its own L3 tab per rf2-nrbs9.
- **Spine binding:** reads `:rf.causa/focus`. The Machines tab inherits
  the ribbon's selected frame; if a user has a machine spawned in
  `:app/dialog`, they need to select `:app/dialog` in the picker to see
  it.
- **Isolation invariant:** the tab shows ONLY the selected frame's
  machines per [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3.

<!-- ============================================================ -->
<!--  STATIC MACHINES SURFACE (normative; shipped per rf2-o5f5f.2)  -->
<!-- ============================================================ -->

## Static Machines surface

The Static-mode Machines surface is **a peer** to the post-collapse Runtime Machines panel described above, **not a successor**. Static is the event-INDEPENDENT registry browse — what's REGISTERED — and runs alongside the Runtime panel within Causa's two-mode chrome. The architectural contract for the Two-modes split lives in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) Lock #14 + [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Static surface; this section owns the concrete Static Machines surface description.

### Tab placement

The Static Machines tab is **tab 1 of 5** in the Static L3 strip (per [`007-UX-IA.md`](007-UX-IA.md) §Static mode sub-tab inventory). Mnemonic `m` (mode-scoped — see [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Mnemonic mode-scoping rule: `m` in Runtime opens the instance inspector, `m` in Static opens the registry browse). Static Machines is the **default Static tab** because the Machines registry is the densest Static surface; opening Static on a fresh slate lands on the highest-value tab.

### Master-detail layout

```
┌──────────────────────────┬───────────────────────────────────────────────┐
│ L4-left  (280px fixed)   │ L4-right  (fills)                             │
│                          │                                               │
│ ─ search box             │ <machine-id> · src/cart/.../checkout.cljs:42 ↗│
│ ─ sort cycle (Name /     │   · 6 states · 2 live (→ Runtime)             │
│   States / Live)         │                                               │
│ ─ scrollable rows:       │ ─ 4-mode sub-strip [T][S][I][C]               │
│   ◉ :checkout    src:42  │ ─ per-mode body (Topology · Sim body ·        │
│   ○ :auth/main   src:18  │   Instances JUMP · Cascade dimmed)            │
│   ○ :wizard      src:90  │                                               │
│   …                      │                                               │
│   each row carries:      │                                               │
│   - selection glyph      │                                               │
│   - mono machine-id      │                                               │
│   - source-coord chip    │                                               │
│   - state-count chip     │                                               │
│   - live-instance pips   │                                               │
│   - → Runtime JUMP chip  │                                               │
└──────────────────────────┴───────────────────────────────────────────────┘
```

**Left pane — browse-all list.** Scrollable list of every registered machine; search box + sort-cycle button (`Name → States → Live → Name`) at the top. Each row carries: a selection glyph (`◉` active / `○` inactive — same vocabulary as the Static tab-bar), the machine-id rendered in monospace accent-violet, a source-coord chip (jump-to-source via the existing open-in-editor affordance), a state-count chip, a live-instance pip cluster (capped at 12; beyond that → textual count), and a per-row `→ Runtime` JUMP chip. Empty-state: "No machines registered. `rf/reg-machine` to add the first."

**Right pane — definition detail.** Header carries `<machine-id> · <source-coord ↗> · <N> states · <M> live`. Below the header, the **4-mode sub-strip** drives the per-mode body.

### 4-mode sub-strip

```
┌────────────┬────────────┬───────────────┬─────────────────────┐
│ [Topology] │ [Sim]      │ [Instances]   │ [Cascade]           │
│  (t)       │  (s)       │  (i — JUMP)   │  (c — DIMMED)       │
└────────────┴────────────┴───────────────┴─────────────────────┘
```

The 4 sub-modes (mnemonic letters `t/s/i/c` surfaced in each pill's `title`) live inside the same DOM shape the Runtime Machines sub-strip used pre-collapse — same pill DOM, same letter mnemonics — so muscle-memory carries between the two modes. The Static-mode behaviours differ:

| Pill | Behaviour in Static | Body renderer |
|---|---|---|
| **Topology** (`t`, default) | Static-read of the machine's state graph — the SAME `chart/svg` MachineChart primitive the Runtime panel uses (single implementation), but with **NO `:highlight-id`** because Static is event-INDEPENDENT (there is no active state to spotlight). Click on a state node fires `:rf.causa.static.machines/state-clicked` for a per-state metadata rail (follow-on bead). Carries an "Open chart in pop-out" affordance. | inline SVG chart |
| **Sim** (`s`) | Hermetic 'what-if' simulator (rf2-r4nao — landed). Clones the registered machine definition into Causa's app-db at `[:rf.causa.static.machines/sim-by-machine <machine-id>]`; production registry is untouched. Event-INDEPENDENT — Sim does NOT read the live snapshot; the seed is the definition's declared `:initial` + `:data`. Engine events/subs live under the `:rf.causa.static.machines/sim-*` namespace (`sim-start`, `sim-step`, `sim-reset`, `sim-stop`, `sim-set-pending-event`, `sim-set-pending-data`). View at `tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs` exports `pill` (the strip cell), `body` (the per-machine Sim panel) and `SimRail` (the geometry-coupled side rail). Failed-guard handling + sim-trail described in §UC1 — Sim sub-mode below remain the design reference for v1 mechanics. | Sim body panel (banner + topology highlight + mock-`:data` form + sim-trail) |
| **Instances** (`i`) | **JUMP to Runtime.** Clicking the pill (or the per-row `→ Runtime` chip in the browse-list) dispatches three events against `:rf/causa`: `:rf.causa/set-mode :runtime` · `:rf.causa/select-tab :machines` · `:rf.causa/select-machine-id <mid>`. The user lands on the Runtime Machines tab with this machine pre-selected. Mode B/C auto-detection (Mode B for 2-8 live, Mode C for ≥8) is the Runtime panel's responsibility — the Static-side JUMP just lands the selection. | no body — the click is the surface |
| **Cascade** (`c`) | **Dimmed + disabled** with a tooltip: *"Cancellation cascade is a Runtime-only surface. Switch to Runtime mode to view."* The pill renders for muscle-memory consistency with the Runtime sub-strip (same DOM, same letter mnemonic) but is non-interactive — `disabled` + `aria-disabled="true"` + dashed border + 0.5 opacity. The cancellation cascade composes against the trace ring buffer which is event-coupled — there is no spine in Static mode, so the surface has no source data. | no body — the pill IS the surface |

The sub-strip mnemonics are mode-scoped under the same rule the L3 tabs follow (see [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Mnemonic mode-scoping rule).

### Per-row → Runtime chip

Every browse-list row carries a trailing `→ Runtime` chip that fires the same three-dispatch JUMP the Instances pill fires (centralised in one handler so the two surfaces never drift). Click semantics: stop propagation on the row's own select-handler; flip mode to Runtime; surface the Runtime Machines tab; select this machine. The user gets a per-row shortcut from Static into the Runtime instance inspector without first having to select the row in Static.

### localStorage persistence

The user's Static-Machines state survives reloads via **two** localStorage slots under the `causa.static.machines.*` prefix (the broader Static mode slot is `causa.mode` per [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 localStorage persistence):

| Slot | Type | Notes |
|---|---|---|
| `causa.static.machines.selected-id` | bare string (machine-id keyword name; namespaced ids store as `ns/name`) | currently selected machine-id; mirrors the `causa.mode` pattern — bare string keeps it cheap to inspect from devtools |
| `causa.static.machines.sub-mode-by-id` | EDN map `{machine-id sub-mode}` | per-machine sub-mode choice. EDN because the map will grow new keys as new sub-modes land; modes are an enum but the per-machine keying needs structured serialisation |

`hydrate!` is called on Static-Machines `install!` so the first render after a reload restores the prior selection + per-machine sub-mode choices. Test fixtures call `clear!` in their `:each` setup.

### Frame isolation

Same discipline as the Runtime Machines panel (per §Tab placement above + [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3). The Static Machines surface is wrapped in the Static shell's `[rf/frame-provider {:frame :rf/causa}]`; every subscribe + dispatch inside the surface resolves to `:rf/causa`. The browse-list, definition-detail, sub-strip pills, and Topology renderer are all `reg-view`-registered.

### See also

- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) Lock #14 — the direction-setting decision behind Two modes (Runtime + Static).
- [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Static surface — the architectural spine for the Static mode (3-layer chrome · 4 mode signals · mode-state lifecycle · localStorage `causa.mode` · feature flag · mnemonic mode-scoping).
- [`007-UX-IA.md`](007-UX-IA.md) §Static mode — the visual-language details (mode pill chrome, edge stripe tokens, motion dampening, sub-tab inventory).
- §Sim re-host reference (rf2-r4nao — landed) below — the historical UC1 Sim + UC2 Mode A/B/C prose preserved as design-reference. The Sim sub-mode now ships per rf2-r4nao at `tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs` with engine events/subs under `:rf.causa.static.machines/sim-*`.

<!-- ============================================================ -->
<!--  SIM RE-HOST REFERENCE (rf2-r4nao — landed)                    -->
<!-- ============================================================ -->

## Sim re-host reference (rf2-r4nao — landed)

> The sections below describe the UC1 Sim engine and UC2 Mode A/B/C
> dynamic-instance UI as they existed pre-collapse (rf2-y9xmf). They
> remain preserved as design-reference for the **Sim re-host effort
> (rf2-r4nao — landed)** — the sibling bead that landed the Sim
> machinery under the Static Machines surface's Sim sub-mode. The
> shipped engine + view live at
> `tools/causa/src/day8/re_frame2_causa/static/machines/sim.cljs`
> with events/subs under `:rf.causa.static.machines/sim-*`; the
> §4-mode sub-strip row above is the normative description of the
> shipped Sim sub-mode shape.
>
> **They DO NOT describe what the Runtime Machines panel renders
> today** (see the collapse note at the top of this doc and the
> "Post-collapse Runtime panel shape" section above) — that surface
> is event-driven only. They also do NOT describe the shipped Static
> Machines surface above (which is master-detail with a 4-mode
> sub-strip, NOT the Mode A/B/C dynamic-instance UI sketched in the
> following sections — Mode B/C live-instance views remain a
> Runtime-side responsibility, reached from Static via the JUMP).
>
> Read everything below this divider as historical design-reference
> for the Sim re-host effort, not as a normative description of any
> currently-shipped surface.

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

### Per-instance state-arc — v1 ships (rf2-nqw0v, Phase 5)

The mini-scrubber's companion overlay. A thin SVG strip mounted ABOVE
the chart that traces the focused instance's chronological
state-trajectory:

- **Origin** is the machine's initial state (`@idx 0`); each subsequent
  point is one outer or microstep transition for the focused instance.
- **Segments** fade between marker centres in the accent-violet palette
  (matches the Causa brand violet; cyan / amber are reserved for
  live-highlight / sim-mode respectively).
- **Per-marker tooltip** is the browser-native SVG `<title>` element —
  no Causa hover-tooltip machinery. Each title carries
  `#<idx> <from> → <to> (<event>) @<ms>` so the developer reads the
  trajectory by hovering markers in sequence.
- **Trim semantics tied to scrubber position.** When the mini-scrubber
  (below) is at `:present`, the arc renders the full trajectory; when
  scrubbed to `idx N`, the arc trims to `[0..N]` so the strip mirrors
  the chart's rewound state.

Pure-data algebra (`machine_inspector_arc_helpers.cljc`) is
JVM-runnable; the view module (`machine_inspector_arc.cljs`) is a
thin CLJS renderer mounted via the chart primitive's overlay slot.

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

#### v1 ships — concrete widget shape (rf2-nqw0v, Phase 5)

The shipped mini-scrubber is a horizontal `<input type="range">`
beneath the chart (NOT the prose `◀ scrub ▶` widget — the spec text
above is the user-facing mental model; the v1 mechanics use a native
range input for keyboard-accessibility and OS-native drag semantics).

- **Slider write surface.** Dragging dispatches
  `:rf.causa/set-scrubber-position` into the per-slot reducer; the
  chart's active-state highlight overrides to the scrubbed-to state;
  the per-instance arc trims to `[0..idx]`.
- **Domain.** `[0, max-idx]` where `max-idx` is the last transition
  recorded for the focused instance.
- **"⏭ present" button** sits next to the slider — snaps the position
  back to head and re-engages live-tracking semantics. Equivalent to
  setting position = `max-idx` AND re-arming the head-follow flag.
- **Auto-flip to `:present` on max-idx drag.** Dragging the slider to
  the right-edge max value auto-flips position state to `:present` so
  head-tracking survives a future-tense scrub (vs. sticking at
  numeric `max-idx` and silently lagging the next live transition).

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

The layout engine ships in **`tools/machines-viz/`** (per §Architectural
posture above); the namespace paths below are relative to
`tools/machines-viz/src/day8/re_frame2_machines_viz/`. Causa consumes
them through the re-export shims (see §Re-export shim contract).

Two layout engines flow through `chart/svg.cljc`'s renderer behind the
same `{:nodes :edges :width :height :initial-id}` data shape:

| Engine | Source ns | When used |
|---|---|---|
| **ELK.js** (preferred) | `chart/elk_layout.cljs` | Browser session once the lazy import resolves; produces orthogonal edge routing + layered placement. |
| **Layered fallback** | `chart/layout.cljc` (`layered-fallback`) | Sync, pure, JVM-runnable. Used by the JVM + node-runtime test corpus, and as the runtime fallback whenever ELK is unavailable. Simple BFS-rank placement + straight-line edges. |

### Lazy load + render-pulse cadence

ELK.js is a ~250 kB browser bundle (gzipped). Bundling it into every
Causa dev session would inflate the preload cost whether the user
opens the Machines tab or not, so the loader is lazy:

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
status indicator on the Machines tab itself (the chart just renders).

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
- **Unified ELK loader** — this surface holds its own loader atom.
  Future consumers can either share it or fold into a shared
  `chart/elk_loader.cljs`.

## Share affordance

### v1 ships — share-URL (rf2-nqw0v, Phase 5)

The v1 Share button is a `⤴ Share` chip in the panel header that
opens a modal carrying a copyable URL encoding the current Machines-
tab inspection posture. NOT the PNG / SVG / Mermaid copy-as
sub-menu — that is the broader vision (preserved below as v1.1
deferred work).

Encoded slots, as a flat URL query-string:

| Param | Source slot | Meaning |
|---|---|---|
| `causa-share=1` | sentinel | "this URL carries a Causa share." Without it the loader skips share-restore on mount. |
| `machine=<id>` | `:focused-machine-id` | The machine the inspector is bound to. |
| `pos=<int>` | `:scrubber-position` | The mini-scrubber's `[0, max-idx]` position (omitted when `:present`). |
| `mode=<mode-x>` | `:view-mode` | UC2 dynamic mode pin (`mode-a`/`mode-b`/`mode-c`). |
| `tab=<id>` | active L3 tab id | The L3 tab the recipient lands on (defaults to `machines`). |

Encoded form is human-legible and trivially diff-friendly — `?causa-
share=1&machine=auth/login&pos=3&mode=mode-b&tab=machines` — so the
URL can be pasted into a PR comment, a code-review note, or a chat
without explanation overhead.

On load, `maybe-restore-from-location!` parses the query-string and
dispatches `:rf.causa/restore-from-share-url` into the per-slot
reducers. The dispatch is one-shot per page-load: subsequent
in-session navigation does not re-read the URL.

Lock #4 (no session export) is preserved by scope: the URL encodes
only the **visible inspection posture** (which machine, which
scrubber index, which view mode, which tab). It does NOT serialise
the trace stream, the epoch buffer, or the app-db — the recipient
sees the same inspector view against THEIR runtime state, not the
sender's.

The share modal itself mounts at the shell-view root (parallel to the
palette / settings popup pattern); `share.cljs` + `share_modal.cljs`
hold the URL parsing + the modal renderer; both are
production-elided per [`Principles.md`](Principles.md) §Production
elision is non-negotiable.

### v1.1 deferred — Copy machine as PNG / SVG / Mermaid

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

## The bug catalogue

Every Machines-tab feature is grounded in a concrete bug-class. Format per
[`000-Vision.md`](000-Vision.md) §Bug-driven, not feature-driven: bug class
→ example → insight → affordance.

### M.1 — Guard rejection (silent)

**Bug class:** An event fires; the chosen `:on` entry's guard returns
`false`; the snapshot doesn't move. The
`:rf.machine.transition/suppressed` trace is the only signal, buried in
the Trace firehose.

**Example bug:** You dispatched `:auth/cancel` on machine `:checkout` in
state `:authing`, expected a transition to `:idle`, nothing happened. The
event landed; the guard `:no-pending-payment?` returned `false` because
`:data.pending-payment` was `4232`.

**Insight Causa provides:** The transition's edge in the chart **flashes
red 400ms** with a tooltip naming the rejected event and the guard's
return value. In the metadata rail (right of chart), a "Recent guard
rejections (N)" section lists the rejected transitions; click-to-expand
shows the `:data` snapshot at evaluation time and the guard's source-coord.

**Affordance:** Guard-verdict overlay (M-C1). Three clicks from "huh,
nothing happened" to "ah, my guard is wrong."

**v1 ships:** the existing chart with no overlay. **Future:** the
red-flash overlay + the metadata rail's rejections section.

### M.2 — Stale `:after` timer / cancelled-on-resolution

**Bug class:** Wall-clock time-bound bug. The timer arms; the wall clock
advances; the timer expires; the snapshot doesn't move. Five
possibilities: (1) epoch stale because we exited and re-entered the state;
(2) guard returned false; (3) the synthetic event hit a `nil` `:on` entry;
(4) the timer fired in SSR mode and was suppressed; (5) subscription-driven
re-resolution cancelled the old timer in favour of a new one that hasn't
fired yet. The user can't tell from snapshots alone.

**Example bug:** You entered `:loading` with `:after 5000ms → :timeout`.
30 seconds passed. The snapshot is still `:loading`. The Trace shows both
`:rf.machine.timer/scheduled` (epoch 12) and `:rf.machine.timer/stale-after`
(epoch 13) — the state re-entered between schedule and fire.

**Insight Causa provides:** On each state with a live `:after` timer, the
node has a **thin countdown ring** with an animated arc representing
time-elapsed/total. Starts at 12 o'clock, rotates clockwise to fill.

- **Live mode:** the ring animates in real time.
- **Retro mode (scrubber-driven):** the ring is static at the
  elapsed-fraction the timer had reached at the focused-cascade's
  timestamp.
- **Stale timer** (epoch mismatch): the ring is rendered dashed/grey +
  tooltip "this timer was scheduled in a prior visit and is stale."
- **Cancelled-on-resolution** (sub-driven re-resolve): the old ring
  fades out (200ms); new ring fades in.

Click any ring → opens a timer detail popover: `:scheduled-at`,
`:delay`, `:epoch`, `:source` (`:literal` / `:sub` / `:timeout-config`
/ `:fn`).

**Affordance:** `:after` countdown rings + scrubber-aware retro-replay
(M-C2). Time IS the bug surface; the ring makes wall clock visible on a
tool that previously rendered only the snapshot. **Stately doesn't ship
this. Nobody does.**

**v1 ships:** no rings (transition-history ribbon only). **Future:** the
full countdown-ring system + retro-replay (Phase 2 per
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §6).

### M.3 — Cancellation cascade ambiguity

**Bug class:** Parent state exits; child's `:invoke` destroyed; child had
N in-flight HTTP requests; each aborts. The author sees a flurry of
`:rf.http/aborted-on-actor-destroy` traces in the Trace firehose and one
`:rf.machine.lifecycle/destroyed`. They cannot reconstruct which abort
belongs to which destroy.

**Example bug:** You clicked Cancel on a checkout flow. The Trace tab
shows 4 abort traces + 1 destroyed trace, scattered through 200 unrelated
rows. You can't tell which abort came from the cancel vs which were
independent.

**Insight Causa provides:** A **"Cancellation cascade" detail panel**
that appears inline in the Machines tab when the focused cascade triggered
a destroy. Header: "Parent `:checkout/main` exited `:processing` at
16:42:14. Destroyed 1 child actor (`:http/post#347`) and aborted 3
in-flight HTTP requests." Body: vertical waterfall showing the parent's
exit → destroy → per-HTTP-abort → final destroyed trace, indented under
its parent decision, each row with source-coord.

What was "a flurry of confusing trace lines" becomes "one decision and
its consequences, laid out vertically."

**Affordance:** Cancellation cascade visualiser (M-C3) — the Machines
tab's hero growth. Also a template for SSR cancellation cascade (when a
streaming SSR boundary times out, the same waterfall idiom shows what
cleanup ran).

**v1 ships:** scattered Trace rows. **Future:** the cascade-grouping
projection + the detail panel (Phase 3).

### M.4 — `:invoke-all` never joins

**Bug class:** N children spawned; join condition is `:all` (or `:any` /
`{:n M}` / `{:fn ...}`); some complete, some don't, the parent stays
stuck. Author wants per-child status, what each is doing right now, the
join-state map (`:done #{:cfg :flag} :failed #{} :resolved? false`), and
whether `:on-any-failed` is wired.

**Example bug:** You entered `:hydrating` which declares `:invoke-all
{:children {:cfg ... :flag ... :user ... :dash ...} :join :all}`. Two
children completed in <200ms; two are still "running" 2 seconds in. The
machine hasn't advanced.

**Insight Causa provides:** When the focused machine is in an
`:invoke-all`-bearing state, the metadata rail shows a **dedicated join
card**:

```
┌─ :invoke-all  ·  invoke-id [:hydrating :invoke-all] ─────────────┐
│ Join condition: :all                                              │
│ Resolved: ✗   (waiting for 2 of 4)                                │
│  ✓ :cfg     :load-config#1         done @ +124ms                  │
│  ✓ :flag    :load-feature-flags#2  done @ +89ms                   │
│  ⧖ :user    :load-user-profile#3   running 2.3s                   │
│  ⧖ :dash    :load-dashboards#4     running 2.4s                   │
│  :on-all-complete  → [:assets-loaded]                             │
│  :on-any-failed    → [:asset-load-failed]                         │
│  :cancel-on-decision?  true                                        │
└────────────────────────────────────────────────────────────────────┘
```

Each running child row: click → pivots to that child's machine instance.
Each done/failed: click → opens the per-child completion event.

**Affordance:** `:invoke-all` join inspector card (M-C4).

**v1 ships:** the `:invoke-all` viz row (children rendered inline; basic
`done?` / `failed?` colouring). **Future:** the full join inspector card
with click-to-pivot.

### M.5 — Per-instance "why am I stuck"

**Bug class:** Mode C debugging at scale. The user picks one instance
out of 47 (e.g. `:checkout#c-047` stuck in `:authing` for 30s); they
need the last few trace events filtered to THAT instance only. No
cross-instance chatter.

**Example bug:** Of 47 `:request/protocol` instances, `#c-047` is stuck
in `:authing` for 30s. The Mode C table tells you the state and the
duration but not WHY.

**Insight Causa provides:** Click an instance row → opens a per-instance
trace strip below the table showing the last 5 traces for THIS instance
only. The "32s in state" auto-callout flags suspiciously-long state
occupancy.

```
┌─ #c-047  ·  current state :authing  ·  in state for 32s ────────────────┐
│ Last 5 traces for this instance:                                          │
│ 16:42:14.103  :rf.machine.transition  :idle → :authing                   │
│ 16:42:14.108  :rf.machine.timer/scheduled  :after 30000ms epoch 4         │
│ 16:42:14.110  :rf.http/managed-issued  POST /api/auth/login              │
│ 16:42:14.140  :rf.http/handled  POST /api/auth/login → 200 (30ms)         │
│ 16:42:14.142  :rf.machine.transition/suppressed  :auth/ok                │
│                  guard :2fa-not-required? = FALSE                         │
│                  (data: {:requires-2fa? true})                            │
│                                                                            │
│ ⓘ Instance has not transitioned for 32s. 1 guard rejection pending.       │
└────────────────────────────────────────────────────────────────────────────┘
```

**Affordance:** Per-instance trace strip (M-C5). Phase 1 quick win.

### M.6 — Hierarchical state cascade (entry/exit along the LCA)

**Bug class:** A transition crosses multiple hierarchy levels. The
exit-cascade and entry-cascade interleave per Spec 005 §Entry/exit
cascading along the LCA. Today the chart highlights the new active state
but doesn't show the cascade.

**Insight Causa provides:** When a transition fires, the chart plays the
cascade in sequence:

1. The old leaf's `:exit` fires (node ring pulses red, then dims).
2. Walking up to LCA: each intermediate `:exit` (rings pulse in
   sequence; 80ms each).
3. The LCA's level (no action).
4. Walking down from LCA to new leaf: each intermediate `:entry` (rings
   pulse green; 80ms each).
5. The new leaf's `:entry` (ring settles to active-state amber/cyan).

Total: ~500ms for a 3-level cascade. Skippable via Settings → View →
"Reduced motion."

**Affordance:** Hierarchical state cascade highlighter (M-C6). Phase 5.
LCA semantics is the most subtle part of XState parity; the cascade
visualisation makes it obvious in ways no doc ever could.

### M.7 — Microstep loop visualiser

**Bug class:** `:always` fires; lands in a state with `:always`; fires
again; eventually hits the bounded-depth ceiling and emits
`:rf.machine.microstep/depth-exceeded`. The microstep chain wants to be
the diagnostic.

**Insight Causa provides:** When a focused cascade contains ≥3 microsteps,
render them as a **strip of micro-arrows** in the Machine tab header:

```
Microsteps (4 of max 12):
:idle ──always→ :checking ──always→ :checking-deep ──always→ :ready ──always→ :idle
                                                                                  ↑
                                                              (loop detected — see ⚠)
```

If the chain returns to a previously-seen state, mark it `⚠ loop
detected; will hit microstep depth limit in N more iterations`.

**Affordance:** Microstep loop visualiser (M-C7). Phase 5.

### M.8 — Path-walked transition explainer

**Bug class:** In a hierarchical machine with parent fallthrough, the
resolution rule is "deepest wins; parent fallthrough on miss." When a
child consumes an event the parent expected to handle, the author is
surprised.

**Insight Causa provides:** In the Event tab's "fx handlers that ran"
block, add a sub-row for each `:rf.machine/transition` showing the
**path walked**:

```
:rf.machine/transition  :checkout
  Path walked:
    [:processing :paying]  :on {:pay/cancel ...}     ← MATCHED ✓
    [:processing]          :on {:pay/cancel ...}     ← not reached (deepest wins)
    [<top>]                :on {:pay/cancel ...}     ← not reached
```

**Affordance:** Path-walked sub-row (M-C8). Phase 1 quick win.

### M.9 — Spawn ancestry

**Bug class:** "Why is this instance still alive? Who's referencing it?"
A spawned actor should have been destroyed but wasn't. Could be:
`:system-id` reference held it alive; parent didn't fully exit
(hierarchical sticking); manual `:rf.machine/destroy` was never
dispatched; OR the destroy WAS dispatched but the snapshot's old state
references it.

**Insight Causa provides:** When an instance is focused, the metadata
rail shows the **spawn tree leading to it**:

```
Spawn ancestry:
  :app/start
   └── spawned :auth/main#m-001
        └── spawned :http/post#h-018  ← currently focused
```

Each ancestor click → focuses that instance. Bottom of card: "Will be
auto-destroyed when `:auth/main#m-001` exits `:authing`."

**Affordance:** Spawn-ancestry tree in metadata rail (M-C9). Phase 1.

### M.10 — Snapshot diff across transitions

**Bug class:** Each transition mutates the machine's snapshot
(`:state` + `:data`). Today the chart highlights state changes; `:data`
mutations are invisible unless the user opens the app-db diff.

**Insight Causa provides:** A **diff card below the chart** when a
transition fires: side-by-side `:data` before/after, action-attribution
per slot (`:data.retry-count incremented from 2 → 3 by action
:increment-retry`).

**Affordance:** Snapshot diff visualisation (M-C10). Phase 5. Needs
per-action attribution.

## Cross-references

- [`018-Event-Spine.md`](018-Event-Spine.md) — Machines tab placement
  in the 4-layer chrome; spine-binding contract; isolation invariants.
- [`007-UX-IA.md`](007-UX-IA.md) — typography, colour tokens, editor
  protocol matrix.
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.1 —
  the bug-class catalogue this spec normalises.
- [Spec 005 — StateMachines](../../../spec/005-StateMachines.md) — the
  framework contract Causa renders.
- [Spec 009 — Instrumentation](../../../spec/009-Instrumentation.md) —
  the `:rf.machine/*` trace contract Causa consumes.
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — the
  `:rf.causa/*` registry ids for the Machines tab.
- [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) — Sim
  mode + Mode A/B/C dynamic instance test rows.
