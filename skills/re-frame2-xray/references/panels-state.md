# panels-state — changed state and what rendered: app-db and Views

The state-and-render family: the **app-db** tab (what does state look
like, what just changed) and the **Views** tab (what recomputed and
re-rendered, and why). Both focused-epoch lenses — pick the frame, click
the event row, and they rebind. Inventory + scope matrix:
[panels.md](panels.md).

## app-db — what changed in state

Question: **What does state LOOK LIKE — and what just changed?**
(Display label lowercase **app-db**.)

The complete state renders as **vertical sections by reserved area**,
each its own collapsible lazy-tree inspector card. Diff annotations are
carried **inline** as `← was X` on changed nodes — there is no separate
DIFF zone; ancestor chains are force-expanded so you never dig to find a
change. Section order, top → bottom, each card titled as shown:

- **`app-db`** (always shown, even when empty — the panel's anchor) —
 the app-db minus every reserved `:rf*` key: the application's own
 user-domain state.
- **`:rf/machines › <id>`** — one section per machine instance (e.g.
 `:rf/machines › :title/flow`).
- **`:rf/spawned › <id>`** — one section per parent machine, holding
 the actors it spawned.
- **`:rf/route`** — the current-route slice (a singleton section).
- **`:rf/pending-navigation`** · **`:rf/elision`** — singleton sections
 for the remaining reserved areas. There are no others.

Empty/absent reserved areas are omitted — no "no X here" placeholder
clutter. The `:rf/*` titles are labels: those areas are read from the
frame's runtime-db partition, not from app-db. The mapping is normative
in §021 §4.2, cited below; its mock-up draws the titles as uppercase
captions (`APP STATE`, `MACHINE :title/flow`), but the shipped cards use
the titles above.

When the L2 spine is at head (no historical epoch focused), sections show
the most-recent epoch's state with its inline diffs — current db,
sectioned. Same render shape, no second mode.

**Open when:** "what just changed in app-db?", "when did
`[:cart :items]` last change?", "show me the full db at this epoch."

Spec: [`021-Dynamic-Panel-Designs.md` §4](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`004-App-DB-Diff.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/004-App-DB-Diff.md).

## Views — what rendered, and why

Question: **What RENDERED as a result?** (Display label **Views**.)

The reactive cascade as a **left → right flow graph** (an inline-SVG
node-and-edge canvas), in four columns: a single **app-db** source node →
**Level-1 subs** (they read app-db) → **Level-2+ subs** (derived through
`:inputs`; present only when used) → **views**, the right-most column and
the focus. Nodes are instances: one per concrete query vector and one per
rendered view.

- **Changed** nodes are filled with an accent border, and their edges are
 solid arrows that propagate downstream; **unchanged** (short-circuited)
 nodes are dashed and dim, and their edges are cut. A sub read by two or
 more views carries a `×N` annotation.
- **Each view** is labelled `(rerendered)` or `(mounted)` and carries its
 **render cause**: `← :sub-id` when a subscription the view derefs
 changed value, or `← props` when none of the view's own subs changed
 (a prop changed / the parent re-rendered; the parent is never named).
 A first mount carries no cause.
- **Below the graph** — a collapsed `Show N unchanged subs` disclosure
 (the memo hits; the Settings popup can pin it open), then **Unmounted
 Views** and **Destroyed Subscriptions** (subs disposed when their last
 reader unmounted), then a legend.

Per-sub values are not listed here — the Epoch tab's SUBSCRIPTIONS step
carries them. Flows are NOT in the graph either: they are handling-side
(the Epoch FLOW step).

Clicking a sub or view node opens its **registration source** in the
editor (click-to-source) — there is **no** jump to app-db and no
cross-panel propagation (§021 §10.5). To
inspect a sub's input path, open the app-db tab and double-click /
`Enter` the container to zoom (`Esc` or the breadcrumb zooms back out).
Hovering a view node toggles a pink DOM highlight on the live element.

**Open when:** "why didn't my view update?", "why did this re-render —
sub or props?", "which views re-rendered this epoch?", "which subs
short-circuited?"

Spec: [`021-Dynamic-Panel-Designs.md` §3](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`012-Views.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/012-Views.md).
