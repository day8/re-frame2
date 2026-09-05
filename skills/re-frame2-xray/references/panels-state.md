# panels-state — changed state and what rendered: app-db and Views

The state-and-render family: the **app-db** tab (what does state look
like, what just changed) and the **Views** tab (what recomputed and
re-rendered, and why). Both focused-epoch lenses — pick the frame, click
the event row, and they rebind. Inventory + scope matrix:
[panels.md](panels.md).

## app-db — what changed in state

Question: **What does state LOOK LIKE — and what just changed?**
(Display label lowercase **app-db**.)

The complete app-db renders as **vertical sections by reserved area**,
each headed by an uppercase caption and rendering as a collapsible
lazy-tree inspector (depth-3-collapsed default). Diff annotations are
carried **inline** as `← was X` on changed nodes — there is no separate
DIFF zone; ancestor chains are force-expanded so you never dig to find a
change. Section order, top → bottom:

- **APP STATE** (always shown, even when empty — the panel's anchor) —
 the app-db minus every reserved `:rf/*` area: the application's own
 user-domain state.
- **MACHINE `<id>`** — one section per machine (e.g.
 `MACHINE :title/flow`).
- **SPAWNED `<id>`** — one section per spawned instance.
- **ROUTE** — the current-route slice (a singleton section).
- **SYSTEM-IDS · PENDING-NAVIGATION · ELISION** — singleton sections for
 the remaining reserved areas.

Empty/absent reserved areas are omitted — no "no X here" placeholder
clutter. The operator-facing section labels map onto framework state in
the runtime-db partition; the mapping is normative in
[`004-App-DB-Diff.md` §Reserved-keys group](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/004-App-DB-Diff.md).

When the L2 spine is at head (no historical epoch focused), sections show
the most-recent epoch's state with its inline diffs — current db,
sectioned. Same render shape, no second mode.

**Open when:** "what just changed in app-db?", "when did
`[:cart :items]` last change?", "show me the full db at this epoch."

Spec: [`021-Dynamic-Panel-Designs.md` §4](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`004-App-DB-Diff.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/004-App-DB-Diff.md).

## Views — what rendered, and why

Question: **What RENDERED as a result?** (Display label **Views**.)

The reactive cascade — the SUBSCRIPTIONS + VIEWS trailing edge — as a
depth-first DAG with indentation showing sub-of-sub layering:

- **SUBS RECOMPUTED** — each sub with its input-path → output-value
 change inline (`:idle → :submitting`, `+1 entry`); unchanged subs
 collapse under a `[Show N unchanged subs ▾]` footer (toggle defaults
 OFF).
- **VIEWS RE-RENDERED** — each view with file:line (open-in-editor) and
 a **render-cause chip** on every re-render leaf: `← :sub-id` when a
 subscription the view derefs changed value, or `← props` when none of
 the view's own subs changed (the cause is the orthogonal props
 channel — a prop changed / the parent re-rendered; the parent is never
 named). A first **mount** carries no cause — the `(mounted)` label
 conveys it.

Flows are NOT in the reactive cascade — they are handling-side (the
Epoch FLOW step). The cascade nodes are exactly: db-paths (seed) → subs
(intermediate) → views (leaf).

Per-cascade clicks propagate cross-panel — a sub row jumps to app-db at
that input path. Hovering a view node toggles a pink DOM highlight on
the live element.

**Open when:** "why didn't my view update?", "why did this re-render —
sub or props?", "which views re-rendered this epoch?", "which subs
short-circuited?"

Spec: [`021-Dynamic-Panel-Designs.md` §3](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`012-Views.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/012-Views.md).
