# 001-Causality-Graph

The causality graph is a **peer** panel — first-class, sidebar entry,
keyboard mnemonic `c`, but **not** the front door. The hero is the
event-detail panel. The graph is the deeper-walk view when the cascade
is more than two hops, spans frames, or when triaging a session with
30+ events.

## Data model

The graph's nodes and edges derive from the trace bus. No new
instrumentation is added; Causa reads what Spec 009 already emits.

### Nodes

| Node kind | Trace event | What it renders |
|---|---|---|
| **Dispatch** | `:op-type :event` with `:operation :event/dispatched` | Event vector, source coords, `:origin`, duration. |
| **Effect** | `:op-type :fx` | fx-id, args summary, outcome (`:ok` / `:error` / `:skipped-on-platform`). |
| **Machine transition** | `:op-type :rf.machine/transition` | Machine-id, from-state → to-state. |
| **Schema violation** | `:op-type :error` with `:operation :rf.error/schema-validation-failure` | Path, recovery mode, one-line cause. |
| **Hydration mismatch** | `:op-type :error` with `:operation :rf.ssr/hydration-mismatch` | Server vs client render-tree hash diff entry. |

Nodes other than dispatches are *significant traces* — they don't drive
new edges by themselves; they attach to the dispatch whose cascade they
ran inside (via the `:dispatch-id` carried on every event's `:tags`).

### Edges

Edges are causal:

- **Parent-child dispatch.** `:parent-dispatch-id` (per Spec 009
  §Dispatch correlation) links a child dispatch to its parent. The
  runtime sets `:parent-dispatch-id` when a dispatch is emitted as a
  side-effect of another event's processing — concretely, when an `fx`
  handler running inside dispatch D₁ invokes `(rf/dispatch ...)`, the
  new dispatch carries `:parent-dispatch-id = D₁`.

- **Dispatch contains significant trace.** Every effect, machine
  transition, schema violation, or hydration mismatch carries its
  cascade's `:dispatch-id` under `:tags`. Those traces attach inside
  their dispatch's node rather than spawning their own outbound edge.

- **Top-level root.** A dispatch with no `:parent-dispatch-id` is a
  *cascade root*. Roots render with a diamond glyph (◆); children
  render as circles (○).

### Graph identity

The graph is **per-frame plus cross-frame edges**. Each frame is a
horizontal swimlane; cross-frame fx (per Spec 002) draws an explicit
arrow from one swimlane to another. No other JS devtool renders this.

## Rendering

The graph lays out top-down (older → newer) on a vertical timeline.
Frames are horizontal swimlanes; the current frame is fully saturated,
non-current-frame nodes are greyed.

### Visual encoding

| Encoding | Meaning |
|---|---|
| **Glyph** | ◆ root dispatch · ○ child dispatch · ◉ currently selected. |
| **Fill colour** | `:origin` axis — violet `:app` · indigo `:pair` · cyan `:story`/`:test` · grey `:causa` (Causa's own re-dispatches). |
| **Border colour** | Status — red border when the cascade contained an error · amber when it contained a warning · default border otherwise. |
| **Size** | Currently-selected epoch's nodes are slightly larger. |
| **Background ribbon** | Faint INP-per-frame band; spike-detect at the long-task threshold. |

The colour-only signal is always paired with a glyph or icon (per
[`007-UX-IA.md`](./007-UX-IA.md) §Visual language) — no encoding is
colour-alone.

### Layout

- **Vertical axis:** time (older at top, newer at bottom).
- **Horizontal axis:** frame swimlanes. Active frame on the left;
  cross-frame arrows traverse swimlanes.
- **Edge style:** straight lines for in-frame parent → child; curved
  lines for cross-frame jumps.
- **Animation:** new edges fade in at 250ms ease-out; the just-landed
  dispatch's node pulses once for 600ms on entry (respects
  `prefers-reduced-motion`).

### Buffer caps

The graph reads from the trace buffer, which is bounded by Spec 009
(default 200 entries). Older dispatches age out of the visible graph
when they age out of the buffer; a "load older" affordance pulls from
the buffer's far tail until the buffer's actual oldest is reached.

The graph **caps at the last 200 dispatches by default** (matches the
trace-buffer depth). Deeper views require an explicit "load older"
click — Causa never silently lays out more nodes than the user asked
for.

## Interactions

| Action | Result |
|---|---|
| **Click node** | Side panel shows the trace event, `:tags`, source coords, affected sub-cache slots, the resulting `app-db` diff (if a dispatch). |
| **Right-click node** | Context menu: Re-dispatch · Copy event vector · Open source · Filter graph to this cascade · Pin to scrubber. |
| **Drag time-range** at top | Filters graph to that range; the rest of Causa synchronises. |
| **Find root cause** button on a node | Walks `:parent-dispatch-id` upward until a root is reached; highlights the path. |
| **`f` keyboard shortcut** on a focused node | Filter the graph to that dispatch's cascade only. |
| **`o`** on a focused node | Open the dispatching code in the editor (via the source-coord URL handler). |

## The inline mini-graph

A 3–5-node version of the graph renders **inside the event-detail
panel** above the event vector, whenever the current epoch has a
`:parent-dispatch-id`. The mini-graph is the local cascade — a causal
breadcrumb. Costs 80px of vertical space.

```
┌─ event 11 ───────────────────────────────────────────────╮
│                                                          │
│      ●  :user/clicked-checkout                           │
│      │                                                   │
│      ●  :cart/finalise                                   │
│      │                                                   │
│      ●  :cart/http-success                               │
│      │                                                   │
│      ◉  :checkout/submit          ← you are here         │
│                                                          │
│  ─────────────────────────────────────────────────────── │
│                                                          │
│  :checkout/submit                                        │
│  ...
```

The mini-graph **renders the chain at a glance** without committing
the user to opening the full graph view. Click any node in the
mini-graph → detail rebases to that epoch (no rewind).

## The causality strip

A flat horizontal version of the graph pinned at the top of every
panel:

```
◆──○──○──○──○──○──○──○──○──○──●
```

- 24×24px pills on cosy density, 4px gap. Compact/comfy adjust.
- Most-recent pill filled in the frame accent; older pills hollow.
- Cascade roots are diamonds (◆); children are circles (○).
- Hover → 240ms popover with event vector, source coords, duration,
  epoch-id.
- Click → event-detail rebases (no rewind).
- Right-click → context menu (Replay · Re-dispatch · Copy · Open source · Filter graph to this cascade).

The strip is **always visible**. It carries everyday weight; the full
graph is one click away when the user needs it.

## Performance

- **Live updates** are debounced to 16ms (one rAF). A burst of 1000
  events still updates the graph once per frame.
- **Re-layout** runs incrementally: a new node attaches to its parent
  without re-flowing the whole graph.
- **Hover popovers** mount lazily on first hover; cached for the
  session.
- **Filter operations** (drag-time-range, find-root-cause, cascade
  filter) materialise a new node set in memory but never re-fetch from
  the trace buffer — Causa reads the buffer once at panel mount.

## Empty state

Before any dispatches have landed:

```
   No cascades yet.
   Click around your app — every dispatch lands here as a node.
   Press [ to walk back through history; ] to step forward.
```

The strip shows a single hollow circle labelled `(boot)` for the
framework's boot epoch.

## What this doesn't do

- **No re-dispatch from the graph alone.** Re-dispatch fires only from
  right-click → "Re-dispatch" (a confirmed action) — never from a
  drag, never from a single click. The runtime is the source of truth;
  the graph reads from it but does not silently mutate it.
- **No retroactive correlation.** If a dispatch was emitted before the
  trace surface knew about it (a cold-boot dispatch before listeners
  registered), it appears as a root in the graph even if it was
  actually a child. Causa surfaces no causality it cannot prove.
- **No edge labels.** Earlier drafts proposed labelling parent → child
  edges with the fx that caused them. The signal-to-noise was poor
  (every edge was labelled `:fx [[:dispatch ...]]`). Removed.
