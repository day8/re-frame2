# 003-Machine-Inspector

The machine inspector renders a Stately-quality state-chart per
registered machine, embedded in Causa as one of the 16 panels. The
state-chart component itself lives in `tools/machines-viz/` —
Causa embeds it.

## Embedding posture

`tools/machines-viz/` owns the chart component. Causa embeds it. The
dependency arrow:

```
tools/10x/  ─requires→  tools/machines-viz/  ─requires→  implementation/machines/
```

Same direction as Story → Causa (per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md)). The
inverse is forbidden: `machines-viz` does **not** depend on Causa, so
programmers who want a chart without the rest of Causa can pull
`machines-viz` standalone.

The contract: `machines-viz` exports a `MachineChart` component that
accepts a `machine-id` and a `frame-id`, renders the chart, handles
the live-highlight. Causa's machine-inspector panel is a thin wrapper
that adds the transition-history ribbon and the source-coord jump
affordance.

```clojure
;; In Causa's machine-inspector panel namespace:
(ns day8.re-frame2-causa.panels.machine
  (:require [day8.re-frame2-machines-viz.chart :as viz]))

(defn machine-inspector-panel [{:keys [machine-id frame-id]}]
  [:div.causa-machine-inspector
   [viz/MachineChart {:machine-id machine-id
                      :frame-id   frame-id
                      :on-state-click  causa-jump-to-source
                      :on-transition-click causa-jump-to-transition-history}]
   [transition-history-ribbon frame-id machine-id]])
```

## What the panel shows

For the selected machine:

- **A directional state-chart.** Nodes are states (compound states
  nested visually). Edges are transitions, labelled with their event
  id.
- **The current state pulses softly.** Compound states' active child
  is highlighted recursively.
- **Hover an edge** → see the guard and action functions; click to
  jump to source.
- **`:invoke` / `:invoke-all` spawned children** appear as smaller
  machines next to their parent, each with their own state.
- **`:after` timers show a countdown ring** on the source state.
- **Transition history ribbon** below the chart: a scrubbable list of
  the last *N* `:rf.machine/transition` events; clicking one rewinds
  the chart to that microstep.

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
| Source-coord stamping (rf2-8bp3) | Every clickable element jumps to source. |

## Selection and switching

The panel header carries a machine picker — a dropdown over
`(rf/machines frame-id)`. The dropdown shows machine-id, current
state, and a tiny activity indicator (filled green if transitioned in
the last 5s; grey otherwise).

Switching the active frame re-binds the picker to that frame's
machines. Machines spawned via `:rf.machine/spawn` (dynamic actors)
appear in the picker with their gensym'd id; named addressing via
`:system-id` is surfaced as a parenthetical (e.g. `:gensym-42
(:auth/main)`).

## Transition history ribbon

A horizontal scrubbable list under the chart. Each entry is one
`:rf.machine/transition`:

```
[14:32:01 :login    → :authing ] [14:32:02 :authing  → :error  ]
[14:32:04 :error    → :idle    ] [14:32:11 :idle     → :login  ]
```

- **Click an entry** → chart rewinds to show the state pre-transition,
  with the inbound edge highlighted. The rewind is **view-only** (same
  passive-scrubbing rule as [`002-Time-Travel.md`](./002-Time-Travel.md))
  — Causa does not call `restore-epoch` from this affordance.
- **Hover** → tooltip with the triggering event vector and guard
  result.
- **Microstep entries** (from `:rf.machine.microstep/transition`) are
  rendered slightly indented under their outer transition.

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
Settings → Source. No protocol-handler dependency (lock #11 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)).

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
Causa auto-pans on every transition so the active state stays in
view. The user can disable auto-pan via the panel header toggle (kept
state per-machine in localStorage).

## Performance

- **Chart re-layout** runs only on registry change (machine
  re-registered) or compound-state expand/collapse. Live transitions
  do not re-layout — they just update node highlights.
- **`:after` countdown rings** update at 60Hz only when the panel is
  visible; backgrounded panels pause the countdown render (the timer
  itself runs at framework-time).
- **Transition history** virtualises past 200 entries; older entries
  scroll into view but are not retained in DOM.

## Empty state

When no machines are registered:

```
   No machines registered.
   Once your app registers a machine via reg-machine (Spec 005),
   it will appear here with:
   • Live state-chart highlighting
   • Transition history
   • :after countdown rings
   → Read about machine integration
```

## Accessibility

The state-chart is a graph, which is hard for screen readers. v1.0
ships **without** a chart alt-view; the alt-view is a v1.1
commitment (per the original v2 design's accessibility plan). Until
then, the transition-history ribbon and the machines picker are the
accessible surfaces — both are text-heavy and reach the same data.
