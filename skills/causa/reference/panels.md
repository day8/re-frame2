# panels — the 14-panel tour

Sources of truth: per-panel specs under
[`tools/causa/spec/`](../../../tools/causa/spec/) (one `00N-*.md` per
hero panel); [`007-UX-IA.md`](../../../tools/causa/spec/007-UX-IA.md)
for chrome and sidebar grammar. Sidebar order is set in `shell.cljs`
(`sidebar-items`).

## Sidebar groups + badges

Three groups, in `sidebar-items` order:

- **Always-active** — Event detail, Time travel, App-db,
  Subscriptions, Effects, Trace.
- **Conditional with activity** — Machines, Flows, Routes,
  Performance, Issues, Schemas.
- **Dormant until first signal** — Hydration (wakes on the first
  `:rf.ssr/hydration-mismatch`).

Activity badges (right-aligned, fade in once, never fade out): `●` =
recent activity · `●N` = unread count · `●●●` = multiplicity · `◌` =
dormant.

## Panel-by-panel

### Event detail *(hero)*

The default landing view. The six-domino cascade for the selected
dispatch: event vector + source coord, the diff (slices moved between
`:db-before` and `:db-after`), an inline mini-graph, fx fired, subs
recomputed, renders triggered, total duration.

**Open when:** you want the full picture of one dispatch. The hero
answers the five canonical questions (what fired, what changed, what
ran, what rendered, how long) on first paint.

Spec: [`004-App-DB-Diff.md`](../../../tools/causa/spec/004-App-DB-Diff.md)
(shares the diff surface);
[`007-UX-IA.md` §The default landing view](../../../tools/causa/spec/007-UX-IA.md).

### Time travel

Bottom-rail scrubber over the target frame's `epoch-history`. Passive
scrub rebases the view of history (App-db, Subscriptions, etc. follow
the selected epoch); explicit rewind invokes `restore-epoch` with the
six failure modes surfaced inline. Pinned snapshots live here too —
session-scoped, useful for A/B comparison.

**Open when:** walk recent history without dispatching; rewind and
replay.

Spec: [`002-Time-Travel.md`](../../../tools/causa/spec/002-Time-Travel.md).

### App-db

Slice-centric, not full-tree. Renders changed slices for the selected
epoch + pinned live slices + a `[runtime] — reserved app-db keys`
section for the `:rf/*` slots + "Show me when this changed" results
for focused paths. Read-only — App-db edits happen in source or via
the [`re-frame2-pair`](../../re-frame2-pair/SKILL.md) skill.

**Open when:** "what changed in app-db?", "show me when `[:cart :items]`
last moved", "what's in the `:rf/route` slot right now?"

Spec: [`004-App-DB-Diff.md`](../../../tools/causa/spec/004-App-DB-Diff.md).

### Subscriptions

Registered subs + their invalidation chains + cache status +
last-recomputed values. The invalidation-chain affordance recomputes
live as upstream slots move (`:cart/total` ← `:cart/items` ←
`[:cart :items]`).

**Open when:** "why didn't my view update?", "trace the recompute
chain for sub X", "is this sub cached or did it recompute?"

Spec: [`012-Views.md`](../../../tools/causa/spec/012-Views.md) (subs nest under views per the rewrite).

### Effects *(fx)*

Registered fxs + per-fx invocations + outcome status + a stub
indicator (handler exists as a pre-alpha no-op). Consumer of
`:rf.fx/*` trace events and `(rf/registrations :fx)`.

**Open when:** "which fx fired in this epoch?", "did `:http/get` get
skipped?", "is this fx stubbed?"

Spec: ns docstring at
[`panels/effects.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/effects.cljs);
no dedicated panel spec yet — see spec/0NN for the authoritative
effects surface once it lands.

### Trace

Raw event ribbon — every trace event in the buffer as a timestamped
row, filterable along the 13-axis vocabulary in
[`spec/009-Instrumentation.md` §Filter vocabulary](../../../spec/009-Instrumentation.md).
Where Issues collapses to issues-only, Trace surfaces every op-type.

**Open when:** you want the unfiltered event stream; when you need
detail the per-panel projections drop.

Spec: [`013-Trace-Bus.md`](../../../tools/causa/spec/013-Trace-Bus.md).

### Machines

State-chart per registered machine — the chart lives in
`tools/machines-viz/`; Causa embeds it as a thin wrapper that adds
the machine picker + transition-history ribbon + (future) source-coord
jump.

**Pre-alpha hedge:** `tools/machines-viz/` has not landed. Only the
spec scaffold exists (the `MachineChart` prop contract + share-URL
encoding are normative per rf2-x50eu). The panel renders a placeholder
until the viz impl is wired — see
[`spec/003-Machine-Inspector.md`](../../../tools/causa/spec/003-Machine-Inspector.md).

**Open when:** "what state is my checkout machine in?", "what
transition just fired?"

### Flows

Registered flows + per-flow inputs / output path / live recomputation
indicator. Filters the trace buffer to `:op-type :flow` and derives
status from the latest event.

**Open when:** "is this flow recomputing?", "which inputs feed this
flow?", "did the output change this epoch?"

Spec: [`013-Trace-Bus.md`](../../../tools/causa/spec/013-Trace-Bus.md)
for the trace vocabulary; see spec/0NN for the authoritative
flow-panel surface once it lands.

### Routes

Registered routes + the active `:rf/route` slice + recent navigation
history. Reads the target frame's `:rf/route` slice; the trace
stream's `:rf.route.nav-token/*` events drive the history ribbon.

**Open when:** "what route am I on?", "what params did the nav-token
resolve?", "show me the last few navigations."

Spec: [`spec/012-Routing.md`](../../../spec/012-Routing.md) (the
framework routing spec; Causa is a presentation consumer).

### Performance

Per-cascade duration capture surfacing the User Timing channel + the
trace stream's per-event `:time` fields. Each cascade renders as a
row with tier glyph + dispatch-id + event vector + duration + per-step
bar. Tiers per [`spec/007-UX-IA.md` §Colour system](../../../tools/causa/spec/007-UX-IA.md):
Fast `<16ms` (green) · Medium `16–50ms` (yellow) · Slow `50–100ms`
(orange) · Blocking `>100ms` (red, the INP threshold).

**Open when:** "which cascades blew the INP budget?", "is the
checkout dispatch slow?"

Spec: [`spec/009-Instrumentation.md` §Performance instrumentation](../../../spec/009-Instrumentation.md).

### Issues

Unified feed — errors + warnings + schema violations + hydration
mismatches. The top-strip Issues badge mirrors the count. Consumes
the ~95 categories enumerated in
[`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md).

**Open when:** "anything broken?", "show me all schema failures",
"what warnings have fired this session?"

### Schemas

One row per registered schema; coloured dot per failure with
recovery-mode mapping. X-axis is time (default 60s window, drag-zoom
expands); y-axis groups by schema. Dot colour encodes recovery mode
(replaced-with-default, raised, silenced, etc.). Silent schema
violations are real bugs in disguise; the timeline makes them
impossible to ignore.

**Open when:** "has any schema violated this session?", "when did
`:user/profile` start failing?", "is this validation silently
defaulting?"

Spec: [`005-Schema-Timeline.md`](../../../tools/causa/spec/005-Schema-Timeline.md).

### Hydration *(dormant)*

Dormant `◌` until the first `:rf.ssr/hydration-mismatch` trace lands;
clicking before then surfaces a "No SSR in this app" empty state. On
first mismatch, renders a side-by-side server-vs-client render-tree
view with the divergent node flagged and the hash-bisector path
highlighted. SSR hydration mismatches are structurally hard to debug;
no other JS devtool surfaces this view.

**Open when:** the dormant marker has woken (you've seen a hydration
error). Otherwise the panel has nothing to show.

Spec: [`006-Hydration-Debugger.md`](../../../tools/causa/spec/006-Hydration-Debugger.md).

For the user-question → panel routing table, see
[`SKILL.md` §The 14 panels](../SKILL.md).
