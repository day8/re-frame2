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
| **Machine transition** | `:op-type :machine` with `:operation :rf.machine/transition` | Machine-id, from-state → to-state. |
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

## Algorithm

The graph is computed as a pure data → data pipeline: trace events →
cascades → graph (nodes + arrows + roots) → layout (per-node `:x`/`:y`
pixel positions). The pipeline is JVM-runnable so it can be tested
without a DOM. Live updates re-run the pipeline against the current
buffer slice; per §Performance the runner is debounced to one rAF.

### Input model

The pipeline consumes two streams off the trace bus (per Spec 009 §
Dispatch correlation):

- **Cascades** — `re-frame.trace.projection/group-cascades` produces
  one record per `:dispatch-id`, bundling `:event`, `:handler`, `:fx`,
  `:effects`, `:subs`, `:renders`, and `:other` for that cascade.
  Events without a `:dispatch-id` (registry-time emits, frame
  lifecycle, REPL evals) collect under `:dispatch-id :ungrouped`.
- **Raw trace events** — used in an *enrich* pass to stitch each
  cascade's `:event/dispatched` trace event back onto the cascade
  record under `:event-trace`. `group-cascades` retains the event
  *vector* under `:event` but drops the originating trace event; the
  graph needs `:tags :origin` and `:tags :parent-dispatch-id` off that
  event for visual encoding and edge construction, so the enrich step
  walks the raw stream once and indexes `:event/dispatched` events by
  `:tags :dispatch-id`.

The enrich step is MUST: a cascade without its `:event-trace`
defaults `:origin` to `:app` and `:parent-dispatch-id` to `nil`, which
silently mis-classifies non-root cascades as roots. Callers MUST run
enrichment before graph projection.

### Graph construction

The projection step turns enriched cascades into `{:nodes :arrows
:roots :index}`:

- **Filter `:ungrouped`.** The `:ungrouped` cascade has no
  `:dispatch-id` and no causal lineage; it MUST be dropped before
  projection so it never spawns a node. Consumers inspect ungrouped
  events via the trace panel's `:op-type` filter.
- **Nodes = one per dispatch cascade**, keyed by `:dispatch-id`. Each
  node carries `:event`, `:origin`, `:root?`, `:error?`, `:warning?`,
  `:parent`, plus count fields (`:effect-count`, `:sub-count`,
  `:render-count`, `:other-count`) used by the hover summary.
- **Arrows = `[parent-id child-id]` pairs**, emitted only when the
  parent cascade is *also* present in the projected set. Orphan
  children (parent aged out of the buffer, or never present)
  contribute no arrow and become roots themselves — per §What this
  doesn't do §No retroactive correlation.
- **Roots = the set of `:dispatch-id`s with no in-graph parent.** A
  cascade is a root if its `:parent-dispatch-id` is `nil` OR its
  parent is not in the projected id-set (the orphan-as-root rule).
- **Index = `{:dispatch-id <node>}`** for O(1) lookup during render.

### Layout algorithm

Layout is a stable, deterministic, two-pass BFS over the graph:

1. **`assign-levels`** — BFS outward from `roots`, recording each
   node's level (distance from the nearest root). Roots sit at level
   0; their children at level 1; etc. A node entering the queue is
   admitted at most once: `(if (contains? acc id) acc (assoc acc id
   lvl))`. The vertical axis is **time-by-causal-depth**, not
   wall-clock time — children always sit below their parent.
2. **`assign-columns`** — a second BFS from `roots` produces a stable
   encounter order; within each level, nodes are assigned columns
   0, 1, 2, … in encounter order. The per-level counter MUST be
   per-level (siblings of unrelated parents do not share a column
   space). The BFS encounter order is the canonical horizontal-tie
   breaker; consumers MUST NOT post-process for force-directed
   layouts at v1 (per §Performance — the budget assumes O(n)).

Missed nodes — any in `all-ids` not visited by the level walker —
default to level 0 and are appended to the encounter order before
column assignment. This keeps the helper total over arbitrary inputs
even when the orphan-as-root rule (above) hasn't pre-classified every
disconnected node as a root.

Pixel positions are then `{:x (+ margin (* col (+ node-width
column-gap))) :y (+ margin (* lvl (+ node-height row-gap)))}`. SVG
width and height are derived from `(inc max-col)` × column-pitch and
`(inc max-lvl)` × row-pitch plus the outer margin.

### Cycles

The model is a DAG by construction: `:parent-dispatch-id` is allocated
monotonically before the child dispatch is enqueued, so a cycle cannot
form in well-formed trace data. Both BFS walkers are nonetheless
defensive — a node is admitted to the level/column accumulator at
most once, so a malformed input (e.g. a hand-edited trace stream with
a forged `:parent-dispatch-id` pointing at a descendant) terminates
rather than loops. The graph silently absorbs the malformation; Causa
MUST NOT project a separate `:rf.causa/error` for it (the source of
truth is the runtime, and the runtime cannot emit cycles).

### Layout invariants

- **Level pitch** = `default-node-height + default-row-gap` (44 + 28 =
  72px at v1). MUST be uniform across the layout — variable row
  heights break the eye's vertical-time mapping.
- **Column pitch** = `default-node-width + default-column-gap` (140 +
  24 = 164px at v1). MUST be uniform within a level.
- **Outer margin** = `default-margin` (16px). MUST surround the
  layout's bounding box so edge nodes don't clip the panel chrome.
- **Node cap** = the last 200 dispatches per
  [`007-UX-IA.md`](./007-UX-IA.md) §Performance budget. The cap is
  enforced upstream at the trace buffer (Spec 009 default 200
  entries); the layout itself does not re-cap. Deeper views MUST
  require the "load older" affordance (per §Buffer caps) — silent
  layout of 1000+ nodes is forbidden.
- **Determinism**: the same enriched-cascades input MUST produce the
  same `{:positions :width :height}` output, so two consecutive
  layouts can be diffed for animation (the 250ms edge fade-in + 600ms
  just-landed pulse per §Layout).

### Cascade-filter integration

When Time-Travel's `:rf.causa/selected-epoch-id` resolves to a
cascade-id present in the graph (via `dispatch-id-of-epoch` walking
the epoch-record's `:trace-events`), the graph filters to that
cascade's family — every ancestor walked up through `:parent`, plus
every descendant walked down through the children index, recomputing
roots for the filtered sub-graph. Per §Filter graph to this cascade
this is the `f`-keyboard / right-click affordance's data path; the
scrubber drives it passively (no rewind).

### Rendering contract

The pipeline hands the projected `{:graph :layout}` map to the panel
view. The view consumes the index for node lookup and the
`:positions` map for SVG placement; visual encoding (glyph,
fill-colour, border-colour) is computed per-node off `:root?`,
`:origin`, `:error?`, `:warning?` per §Visual encoding. The pipeline
MUST NOT emit SVG or React: keeping projection + layout pure data
preserves the JVM-test surface and lets the inline mini-graph
(§The inline mini-graph) and the causality strip (§The causality
strip) consume the same projection without re-implementing it.

### Registry surface

The pipeline is exposed to the panel via one composite subscription,
`:rf.causa/causality-graph-data`, enumerated in
[`014-Registry-Catalogue.md` §Causality graph](./014-Registry-Catalogue.md#causality-graph).
The composite's inputs are `:rf.causa/trace-buffer`,
`:rf.causa/selected-dispatch-id`, `:rf.causa/selected-epoch-id`, and
`:rf.causa/epoch-history`; its output shape is `{:graph :layout
:selected-dispatch-id :selected-epoch-id :filtered?}`. The graph adds
no new events — it reuses the event-detail panel's
`:rf.causa/select-dispatch-id` /
`:rf.causa/clear-selected-dispatch-id` (per §10 Lock 7) and the
scrubber's `:rf.causa/clear-selected-epoch` for the cascade-filter
affordance.

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
