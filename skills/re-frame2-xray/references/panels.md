# panels — the canonical tab inventory

The one complete tab inventory for the package. Load this for an explicit
"list every tab" request; a deep question about one panel family loads
that family's own leaf (linked per row below), not this catalogue.

Sources of truth: the live tab inventory is the set of
`panel-registry/reg-l4-tab!` calls under
`tools/xray/src/day8/re_frame2_xray/panels/` (Dynamic) and `.../static/`
(Static), mirrored by `focus.cljc`'s `valid-panels`; the normative tab
list is
[`018-Event-Spine.md` §5](../../../tools/xray/spec/018-Event-Spine.md) +
[`021-Dynamic-Panel-Designs.md` §9.1](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
(Dynamic) + [`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md)
(Static). The executable drift gate is
`scripts/check_skill_xray_tab_inventory_drift.py`, which fails CI if this
inventory's ids, labels, tooltip letters or order diverge from the shipped
registry.

## Dynamic mode — 10 tabs

Left-to-right in tab order (mnemonics `e a v t m r s g u h`):
**Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph ·
Frames · Hicasso.**

The **Tooltip** column below is each tab's `:mnem` — the parenthesised
letter in the tab button's `title` (`Trace (t)`), and nothing more. It is
**not a key**: no bare letter selects a tab (`s` is a live bare key, but
for the Settings popup, not for Resources). Jump by keyboard with the
command palette, `Cmd/Ctrl+K` → "Open Trace panel"; from code, with
[`focus!`](launch-modes.md#programmatic-focus-focus).

| Tab | Tooltip | Scope | One-line purpose | Depth |
|---|---|---|---|---|
| **Epoch** *(default landing)* | `e` | focused epoch | The focused dispatch's full cascade as a numbered vertical timeline, per-step ✓/✗/⊘, exceptions inline under their step. | [panels-epoch.md](panels-epoch.md) |
| **app-db** | `a` | focused epoch | State sectioned by reserved area, collapsible lazy-trees, inline `← was X` diffs, downstream-subs hover. | [panels-state.md](panels-state.md) |
| **Views** | `v` | focused epoch | The reactive cascade (subs + views) as a DAG with render-cause chips (`← :sub-id` vs `← props`). | [panels-state.md](panels-state.md) |
| **Trace** | `t` | focused epoch | Raw trace events for the focused epoch — flat oldest-first rows, click to expand. | [panels-epoch.md](panels-epoch.md) |
| **Machine** | `m` | focused epoch | What this event did to machines — topology + transition + guards/actions; blank on a no-machine epoch. | [panels-domains.md](panels-domains.md) |
| **Routes** | `r` | focused epoch | Current matched route + this epoch's route activity + a Simulate-URL input. | [panels-domains.md](panels-domains.md) |
| **Resources** | `s` | mixed | The declarative server-state lens — registry, live instances, work ledger, mutation/invalidation evidence. | [panels-resources.md](panels-resources.md) |
| **Graph** | `g` | observed frame / process-global | The EP-0014 derivation/process graph across all five families; its own Declared ↔ Realized projection toggle. | [panels-structure.md](panels-structure.md) |
| **Frames** | `u` | process-global | The EP-0023 `image → frame` lens — which image loaded each live frame, and how it resolves registrations. | [panels-structure.md](panels-structure.md) |
| **Hicasso** | `h` | live runtime (not epoch-coupled) | The Hicasso evidence lens — six views (Mounted · Reads · Intents · Why · Advisor · Causal) over four envelopes taken in one turn. | [panels-structure.md](panels-structure.md) |

Cross-epoch signal lives on the L2 timeline (badges + the issue
pink-wash); **no Dynamic tab shows a cross-epoch aggregate** (binding,
§021 §1.2). Internal tab ids are not user labels — route users by the
visible labels above (the ninth tab is **Frames**; its internal id
`:module-view` and the retired "Modules" label are not answers).

## Scope matrix — three authority axes

"Dynamic" names the 4-layer shell, not a uniform data scope. Each tab
reads one of three scopes:

| Scope | Tabs | Picking an epoch in L2… |
|---|---|---|
| **Focused epoch** — the event-spine lenses | Epoch · app-db · Views · Trace · Machine · Routes | rebinds them to that epoch's captured cascade |
| **Observed frame** — live frame state | **Graph** (Realized projection) · Resources (live instances) | does nothing; they follow the **L1 frame picker** |
| **Process-global / registry-wide** | **Graph** (Declared projection) · **Frames** · **Hicasso** · Resources (static registry) | does nothing; identical for every epoch |

So "select an epoch and Graph/Frames/Hicasso update" is **false** — only
the six focused-epoch lenses rebind.

## Static mode — 5 registry-browse tabs

Event-INDEPENDENT browse of what's *registered* (3-layer chrome, no L2
spine). Flip in with the L1 mode pill or `Cmd/Ctrl+Shift+M`. Static has
its own tooltip letters, so a letter can appear in both modes' tab bars
meaning that mode's tab — `m` labels the Static **Machines** browse here
and the Dynamic instance-inspector there. As above, they are tooltip
letters, not keys: pressing one selects nothing, and the palette entry is
"Open Machines (Static)". Order per
[`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md):

| Tab | Tooltip | Question it answers |
|---|---|---|
| **Machines** *(default)* | `m` | "What machines are registered, and what do they look like?" Registry browse + full topology (picker + zoom/pan/fit) + a 4-mode sub-strip incl. the Sim engine. |
| **Routes** | `r` | "Which route would `/orders/42` match?" Every registered route + a Simulate-URL input. |
| **Schemas** | `c` | "Show me the shape of `:order/schema`." Every registered app-db / event / sub schema as its Malli EDN, with `:doc`, searchable, + jump-to-source. |
| **Flows** | `f` | "What flows are registered?" The flows catalogue. |
| **Interceptors** | `i` | "What runs, and in what order?" Pure-browse over the registered interceptor chains. |

Static is **mixed-scope**: the definition catalogues are process-global
(the registrar is shared across every frame, Spec 001); the L1 frame
picker scopes only the per-frame *live* projections each tab adds —
machine snapshots, the flows registry, the app-db-schema side-table, the
current-route slice. The mode and tab choices persist across reload; the
**frame pin does not** (it resets to the head-frame default).

## What's deliberately NOT here

<a id="whats-deliberately-not-here"></a>

Per §021 §15 + §007 §Static mode — these are not tabs, and their content
has a home:

- **Issues** → no tab, no session-wide triage list. Three inline
 channels: the Epoch cascade's per-step ✓/✗ + inline exception cards
 ("anything broken in this epoch?"), the L2 event-row pink-wash ("which
 epochs are broken?"), and the always-on issues-ribbon signal driving
 auto-open-on-error. Detail: [panels-epoch.md §Issues](panels-epoch.md#issues--inline-not-a-tab).
- **Event** → the Epoch tab is the "what happened" surface.
- **Subscriptions** → Views (the cascade tree) + the app-db hover
 popover; no peer Subs tab.
- **Effects** → the Epoch EFFECT HANDLERS step + Trace raw ops.
- **Flows** → the Epoch FLOW step; the registry → Static → Flows.
- **Performance** → L2 row stripes + per-step durations in Epoch +
 per-row `:time` in Trace.
- **Schemas** (violations) → Epoch inline + the L2 pink-wash; the
 registry catalogue → Static → Schemas.
- **Hydration** → Epoch inline + the issues-ribbon signal; no standalone
 tab.
- **A11y** → not Xray at all — Story's domain
 (`re-frame.story.ui.chrome-a11y`).
- **A standalone Dynamic "Machines Canvas"** → the browse-all topology
 lives under Static → Machines.
- **Master-detail coupling / multi-frame display / pattern-view** → tabs
 are peers bridged by app-db; single-frame focus via the L1 picker.

For the question → first-surface routing, see
[`SKILL.md` §The route card](../SKILL.md#the-route-card--question--first-surface).
