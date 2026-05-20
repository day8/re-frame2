# panels — the 8 L4 lenses on one epoch

Sources of truth: per-panel content design lives in
[`021-Dynamic-Panel-Designs.md`](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
(the canonical Dynamic-panel doc; per-panel content layout, locked
decisions, palette / iconography / animation specs);
[`007-UX-IA.md`](../../../tools/causa/spec/007-UX-IA.md) for chrome,
palette tokens, density; the per-panel scaffolds at
`tools/causa/spec/00N-*.md` cover surface-specific contracts that
predate §021. Panel order is set in `panel_registry.cljc` and rendered
by `shell.cljs`.

## The two-zone shape — L2 timeline above, L4 panels below

Causa's chrome is two zones, one purpose each (per §021 §1):

```
┌──────────────────────────────────────────────────────────────────────┐
│  L1 ribbon · L2 epoch timeline                ← MOVING BETWEEN epochs│
├──────────────────────────────────────────────────────────────────────┤
│  L4 panels (8 lenses on the focused epoch)    ← DEPTH INTO one epoch │
└──────────────────────────────────────────────────────────────────────┘
```

**Top** carries the only cross-epoch signal. **Bottom** is 8 L4 panels,
each answering "what happened in this epoch?" through its own lens.
**No third axis. No cross-epoch L4 panels** (§021 §1.2 — binding).

### L2 timeline grammar

The L2 timeline above the panels carries:

- **Dispatch-origin prefix glyph** per row — one of
  `:user :router :websocket :http :ssr :fx-emit :timer :test-harness :tool :internal`
  (per Spec 002; §021 §1.5 is the universal classifier).
- **Activity badge cluster** per row: `⚠` issue · `◆` machine
  transition · `🌐` route nav · `⚡` HTTP lifecycle · `⏲` timer-triggered.
  Glyphs from §021 §17.1.5; HCM remap is automatic (colour is never alone).

Implementation lives at
[`panels/l2_timeline.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/l2_timeline.cljc).

### Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in **this** epoch?" — through its
own lens. Cross-epoch signals belong on L2 badges, never inside L4.

### Inspection vs Rewind

Clicking an L2 row is **INSPECTION** — L4 panels rebind to that epoch's
captured snapshots; app-db is NOT rolled back. Rewind is a separate
affordance in the focused-epoch header (`002-Time-Travel.md`).

## Panel-by-panel

Eight panels, in their fixed L3-tab order (§021 §11.4): **Event ·
App-db · Reactive · Trace · Machines · Routing · Issues · Chrome A11y.**

Each panel shares the same chrome: panel icon (left of stripe) · panel
title · focused-epoch id · `[◀ Prev] [Next ▶]` film-strip walking the
L2 spine chronologically (per §021 §17.1.5; shared component at
[`panels/shared/film_strip/header.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/shared/film_strip/header.cljc)).

### Event — `⚡` · stripe `:accent-violet`

Question: **What did this event DO?** — the handling pipeline.

Six-step linear pipeline rendered top-to-bottom with explicit arrows
(per §021 §2.2):

1. **DISPATCH** — event vector, origin tag, call-site (open-in-editor), timestamp
2. **COEFFECTS ASSEMBLED** — `:db` slice, `:now`, registered coeffects
3. **HANDLER INVOKED** — handler-id, file:line (open-in-editor), DEBUG-gated source string
4. **EFFECTS RETURNED** — handler intent (`:db` + `:fx`), inline diff for `:db`
5. **EFFECTS APPLIED** — what actually happened (db written, fx settlement/in-flight markers)
6. **FLOWS RECOMPUTED** — per-flow recompute or `(input unchanged · skipped)` dim row

All six steps default-expanded (the pipeline IS the punch, §021 §2 +
§17.3). Ends with the `db committed for epoch #N` marker — the pivot
to the Reactive panel. Header film-strip walks the L2 spine
chronologically.

**Open when:** "what did this event do?", "what fx fired?", "what did
the handler return?", "did the flow recompute?"

Spec: [`021-Dynamic-Panel-Designs.md` §2](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md);
implementation at
[`panels/event_detail.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/event_detail.cljs)
+ `panels/event/`.

### App-db — `◐` · stripe `:cyan`

Question: **What does state LOOK LIKE — and what just changed?**

Two-zone layout (§021 §4.2):

- **DIFF zone** — changed paths for the focused epoch (`← changed`,
  `← changed from <prior>`, `← added`). Narrow, dense, scannable.
- **STATE zone** — the full db at end of epoch, rendered via the
  shared lazy-tree data-display (depth-3-collapsed default per §021
  §10.4) with diff annotations inline.

**Downstream-subs hover popover** (§021 §4.4) — hover any changed path
to surface the subs depending on it + the views rendered + an inline
`⤴` to jump to the Reactive panel scrolled to those subs. Popover is
Causa-owned (not a browser title), keyboard-dismissable. Walks subs
from the registry's `:input-paths` — see
[`panels/shared/sub_input_paths.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/shared/sub_input_paths.cljc).

When the L2 spine is at head (no historical epoch focused), the DIFF
zone shows the most-recent epoch's diff; STATE shows current db. Same
render shape — no second mode.

**Open when:** "what just changed in app-db?", "what's downstream of
`[:cart :items]`?", "show me the full db at this epoch."

Spec: [`021-Dynamic-Panel-Designs.md` §4](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`004-App-DB-Diff.md`](../../../tools/causa/spec/004-App-DB-Diff.md);
implementation at
[`panels/app_db_diff.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/app_db_diff.cljs)
+ siblings under `panels/app_db_diff_*.cljs` +
[`panels/app_db_diff_downstream.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/app_db_diff_downstream.cljs).

### Reactive — `◉` · stripe `:cyan`

Question: **What RENDERED as a result?**

Renamed from `Views` → `Reactive` per §021 §11.5. **The L3 tab key
stays `:views`** (it's an internal id, not a user contract — share URLs
are local-only dev surface). Only the display label rebases.

The reactive cascade (Spec 009 ops 7-8) rendered as a depth-first DAG
with explicit indentation showing sub-of-sub layering:

- **Step 7 — SUBS RECOMPUTED** — each sub with input-path → output-value
  change inline (`:idle → :submitting`, `+1 entry`), with skipped subs
  collapsed under a footer `[Show N unchanged subs ▾]` (§021 §3.4).
- **Step 8 — VIEWS RE-RENDERED** — each view with file:line
  (open-in-editor) + `caused-by ← sub ← path` causation chain on every
  leaf (no expand-to-see, §021 §3.2).

Flows are NOT in the reactive cascade — they're handling-side (Event
step 6) per §021 §3.2. The cascade nodes are exactly: db-paths (seed)
→ subs (intermediate) → views (leaf).

"Show unchanged subs" toggle defaults OFF (§021 §3.4 + §11.4).
Per-cascade clicks propagate cross-panel: sub row → App-db at that
input path; `caused-by ← sub ← path` chip → App-db at that path.

**Open when:** "why didn't my view update?", "what re-rendered?",
"trace the recompute chain for sub X", "which subs short-circuited?"

Spec: [`021-Dynamic-Panel-Designs.md` §3](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`012-Views.md`](../../../tools/causa/spec/012-Views.md);
implementation at
[`panels/reactive_panel.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/reactive_panel.cljs)
+ siblings under `panels/reactive_panel_*.cljs`.

### Trace — `⬢` · stripe `:orange`

Question: **What raw trace events fired during this epoch?**

The underlying stream that Event + Reactive summarise. **Focused-epoch
scoped** (per §021 §1.2 — no aggregate-across-epochs view) — each row
is a single mono line `#id  +Xms   op-kw   inline-summary`, filterable
by `[op-type ▾] [tag ▾]` chips (panel-local; do not affect L1 ribbon).

Per-row click expands the payload inline via the shared
[`data_display/render`](../../../tools/causa/src/day8/re_frame2_causa/data_display/render.cljs)
component at depth-2-expanded (§021 §10.4 + §17.3). Film-strip walks
the L2 spine — closest Causa comes to a time-step replay UX.

The filter vocabulary lines up with Spec 009 §Filter vocabulary
(`:op-type`, `:dispatch-id`, plus the 13-axis tags).

**Open when:** "show me every raw op in this epoch", "is `:rf.fx/*`
firing as expected?", "what order did these emit in?"

Spec: [`021-Dynamic-Panel-Designs.md` §5](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`013-Trace-Bus.md`](../../../tools/causa/spec/013-Trace-Bus.md)
+ [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md);
implementation at
[`panels/trace.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/trace.cljs).

### Machines — `◆` · stripe `:green`

Question: **What did this event do to my machines?**

Topology-plus-overlay (§021 §6 + §17.4). Each registered machine
renders as an xyflow canvas (path B locked per §021 §6.0 — xyflow with
Causa-palette styling; not Stately Inspect, not native Reagent). Nodes,
edges, current-state pulse, parallel-region containers, final-state
double-rings all render through
[`machines/xyflow_style.cljs`](../../../tools/causa/src/day8/re_frame2_causa/machines)
(per §021 §17.4.5).

**Current-state precedence** — a 4-source walk-back resolves the
machine's current state for the focused epoch:

1. **Explicit** — operator override (sticky selection)
2. **Focused-epoch transition** — if this epoch fired a transition for the machine
3. **Epoch-history walk-back** — scan the buffer back to the most-recent transition
4. **Snapshot** — fall back to the substrate's per-frame machine state

The resolved current state node carries the `rf-causa-machine-pulse`
keyframe (1.2s ease-in-out, interpolated through
`--rf-causa-motion-scale` so reduced-motion collapses it; §021
§17.4.5).

Per-canvas footer lists guards / actions / cancellation cascade chips
inline (no modal, no popout). Empty state (machines registered, no
activity this epoch) renders the topology with `current ●` annotation
intact — topology is always visible (§021 §6.2 Case B).

**Open when:** "what state is my checkout machine in?", "what
transition fired this epoch?", "what guards passed / failed?"

Spec: [`021-Dynamic-Panel-Designs.md` §6 + §17.4](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`003-Machine-Inspector.md`](../../../tools/causa/spec/003-Machine-Inspector.md);
implementation at
[`panels/machine_inspector.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/machine_inspector.cljs)
+ `panels/machines/` + `panels/machines_canvas/` +
[`panels/machine_canvas.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/machine_canvas.cljs).

### Routing — `🌐` · stripe `:yellow`

Question: **What did this event do to my routes?**

Same topology-plus-overlay pattern as Machines, rendered as a textual
tree (route trees are typically ≤ 4 levels deep, so a tree with `├─ └─`
box-drawing is denser AND simpler than xyflow — per §021 §7.1).

Two blocks:

- **Active route tree** (always visible) — each node with one of three
  markers per current state and per-epoch activity:
  - `◉` active this epoch, on the resolved match
  - `◇` registered, traversed (`:can-leave` / `:can-enter`) this epoch
  - `●` current active node (no activity this epoch)
- **This epoch** — short dense block: `Phase`, `From`, `To`, `Match`,
  `Events`. Empty state ("No route activity in this epoch.") keeps
  the tree visible above.

Reads `:rf.route/can-leave`, `:rf.route/can-enter`, `:rf.route/on-match`,
`:rf.route/url-changed` filtered by `:dispatch-id`.

**Open when:** "what route am I on?", "what params did the nav-token
resolve?", "did the route change this epoch?"

Spec: [`021-Dynamic-Panel-Designs.md` §7](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`spec/012-Routing.md`](../../../spec/012-Routing.md);
implementation at
[`panels/routing.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/routing.cljs).

### Issues — `⚠` · stripe `:red`

Question: **What's wrong in this epoch?**

Per-epoch errors + warnings + schema violations + a11y violations.
**Focused-epoch scoped** (§021 §8.1). Each issue renders as a 4-6 row
block (severity · op-key · handler / schema · message · path / ex-data)
with the ex-data laid out via the shared data-display renderer at
depth-2-expanded.

**Head-fallback contract** — when the L2 spine is at head (no
historical epoch focused), the panel scopes to the most-recent epoch's
issues (matches the same head-fallback the other focused-epoch panels
use; resolved via the shared
[`panels/shared/focus_resolver.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/shared/focus_resolver.cljc)).
Empty state is a single line.

Stretch film-strip: "next epoch with ⚠ badge" — operator stepping
through a bug repro lands on issue-bearing epochs only.

**Open when:** "anything broken in this epoch?", "show me all schema
failures here", "what warnings fired?"

Spec: [`021-Dynamic-Panel-Designs.md` §8](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md);
implementation at
[`panels/issues_ribbon.cljs`](../../../tools/causa/src/day8/re_frame2_causa/panels/issues_ribbon.cljs)
+ [`panels/issues_ribbon_helpers.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/issues_ribbon_helpers.cljc).

### Chrome A11y — `✦` · stripe `:red`

Causa's own chrome accessibility dogfood (per §021 §9). Spine-
independent — same shape pre- and post-rebuild. The audit-list layout
(one row per violation, inline severity + rule-id + affected element +
remediation hint) is the template every other panel's empty/dense
state follows.

**Open when:** "is Causa's own chrome accessible right now?" — meta
panel for Causa-on-Causa development.

Spec: [`021-Dynamic-Panel-Designs.md` §9](../../../tools/causa/spec/021-Dynamic-Panel-Designs.md)
+ [`007-UX-IA.md`](../../../tools/causa/spec/007-UX-IA.md);
implementation under
[`panels/chrome_a11y/`](../../../tools/causa/src/day8/re_frame2_causa/panels).

## Shared components

Three components are consumed by every (or nearly every) L4 panel.
Citing them when you answer "where does X live?" beats describing the
behaviour from each panel's perspective.

### `data_display/render`

The single canonical data renderer — lazy collapsible tree + inline
diff highlighting + keyword accent + clickable paths. Lives at
[`tools/causa/src/day8/re_frame2_causa/data_display/render.cljs`](../../../tools/causa/src/day8/re_frame2_causa/data_display/render.cljs)
per §021 §10. Every panel that shows data — App-db, Event coeffects /
returned effects, Reactive sub values, Trace expanded payloads, Issues
`ex-data` — goes through this renderer (§021 §10.6 — binding).

Locked capabilities (§021 §10.1): lazy collapsible tree · inline diff
(no side-by-side) · minimal keyword-only type coloring · clickable
paths for **cross-panel propagation only** (no blame popover, no
copy-path, no copy-value, no "show epoch that last changed this" —
explicitly stripped per §021 §10.5).

Lazy-expansion heuristic (§021 §10.4): depth ≤ 2 expanded · depth 3
expanded if ≤ 10 children · depth ≥ 4 collapsed · changed children
force ancestor chain open · per-panel `:default-depth` override (App-db
defaults depth-3-collapsed; Event payload defaults depth-2-expanded).

Operator expansion state persists in app-db
(`:rf.causa.data-display/expansion {<path>}`) per epoch + path.

### `film_strip/header`

Shared `[◀ Prev] [Next ▶]` header consumed by every L4 panel. Lives
at
[`tools/causa/src/day8/re_frame2_causa/panels/shared/film_strip/header.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/shared/film_strip/header.cljc).
MVP: chronological walk through the L2 spine. Hit-target sizing per
§021 §17.1.5 (28×20px, 4px vertical padding for AA target-size).
Keyboard `←` / `→` global binding. Disabled state at spine ends.

Per-panel stretch filters (e.g. "next epoch with ⚠" for Issues, "next
route activity" for Routing, "next epoch that touched THIS machine"
for Machines per rf2-y9xmf) slot into the header's filter slot.

### `focus_resolver` + `find-epoch-record`

Shared focus-resolution at
[`tools/causa/src/day8/re_frame2_causa/panels/shared/focus_resolver.cljc`](../../../tools/causa/src/day8/re_frame2_causa/panels/shared/focus_resolver.cljc).
Resolves the focused epoch's record from `:rf.causa/focus` (per
[`018-Event-Spine.md`](../../../tools/causa/spec/018-Event-Spine.md))
with the **head-fallback contract** — when no historical epoch is
focused, every L4 panel scopes to the most-recent epoch in the buffer
(not "no data" — head IS a valid focus). Used by Issues, Reactive,
App-db, Trace, Machines, Routing for symmetric "spine at head" empty
states.

The evicted-epoch placeholder (§021 §10.7 — `"Epoch evicted from
buffer — increase :epoch-history to retain more"`) is also resolved
here, so the film-strip ◀ / ▶ keeps working when the operator scrubs
past an evicted row.

## Iconography quick reference

Per §021 §17.1.5 (binding; HCM-safe because glyph alone carries
signal, colour is never alone):

| Panel | Icon | Stripe token |
|---|---|---|
| Event | `⚡` | `:accent-violet` |
| App-db | `◐` | `:cyan` |
| Reactive | `◉` | `:cyan` |
| Trace | `⬢` | `:orange` |
| Machines | `◆` | `:green` |
| Routing | `🌐` | `:yellow` |
| Issues | `⚠` | `:red` |
| Chrome A11y | `✦` | `:red` |

L2 row badges: `⚠` issue · `◆` machine transition · `🌐` route nav ·
`⚡` HTTP lifecycle · `⏲` timer dispatch.

Cross-panel arrows: `⤴` jump-to-panel from popover (`:accent-violet`,
12px) · `↳` cause-attribution chip (`:text-tertiary`, 11px) · `→`
inline state transition (`:text-primary`, mono).

## What's deliberately NOT here

Per §021 §15:

- **No 4th L4 panel.** The 8-panel set is the contract; sub-layer
  surfaces inline in Reactive + the App-db hover popover (no peer Subs
  panel).
- **No cross-epoch L4 views.** Aggregate signals live on L2 badges only.
- **No pattern-view (4th lens).** Deferred.
- **No master-detail Event-vs-Reactive coupling.** Peers, bridged by
  App-db.
- **No simultaneous multi-frame display.** Single-frame focus (§021
  §1.6); switch focus via the L1 frame picker.
- **No legacy panels.** Subscriptions, Effects, Flows, Performance,
  Schemas, Hydration are NOT separate L4 panels. Their content is
  surfaced through the 8 above:
  - Subscriptions → Reactive (cascade tree) + App-db (hover popover)
  - Effects → Event step 4 (returned) + step 5 (applied) + Trace (raw `:rf.fx/*` ops)
  - Flows → Event step 6
  - Performance → L2 row stripe colours + per-step `:time` in Trace
  - Schemas / Hydration / a11y → Issues (unified feed)

For the user-question → panel routing table, see
[`SKILL.md` §The 13 panels — what each surfaces](../SKILL.md#the-13-panels--what-each-surfaces)
(table label is still its pre-rebuild form; the table itself routes
into the 8 panels described here via the post-rebuild surface — a
follow-on sweep retires the legacy rows).
