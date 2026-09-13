# panels-domains — machine and route activity: Machine and Routes

The domain-activity family: the **Machine** tab (what this event did to
state machines) and the **Routes** tab (what this event did to routing).
Both focused-epoch lenses — pick the frame, click the event row, and
they rebind. Their registry counterparts live in **Static mode**
(Machines / Routes catalogues). Inventory + scope matrix:
[panels.md](panels.md).

## Machine — what this event did to machines

Question: **What did this event do to my machines?** (Singular label
**Machine**.)

**Event-driven** — no picker, no browse modes: the panel is **blank**
(a single calm placeholder line, no topology) when the focused event had
no machine activity, and renders one per-machine section when it does:
topology + transition highlight + guards + actions + cancellation
cascade + `:after` rings. Per-machine prev/next nav walks the spine to
the next event that touched that machine.

Each machine renders as an interactive chart through the shared
machines-viz **MachineChart** — nodes, edges, current-state highlight,
parallel-region containers, final-state double-rings. The resolved
current state carries a **static** highlight (accent border + soft glow
ring); it does not pulse — no continuous animation, by ruling.

**Current-state precedence** — a 4-source walk-back resolves the
machine's current state for the focused epoch:

1. **Explicit** — operator override (sticky selection)
2. **Focused-epoch transition** — if this epoch fired one for the machine
3. **Epoch-history walk-back** — the most recent transition in the buffer
4. **Snapshot** — the substrate's per-frame machine state

The focused-epoch **transition row** is a single prominent row: a header
verb `<before-state → after-state>` (doubling as click-to-source) over a
logical-state DELTA box — a before→after diff of the machine's
`{:state :tags}` only (`:data` is carried by the per-action `↳ data Δ`).
Guards / actions / cancellation chips list inline in the per-canvas
footer — no modal, no popout.

**Open when:** "what state is my checkout machine in?", "what transition
fired this epoch?", "what guards passed / failed?"

> **Browse-all machine canvas → Static mode.** The spine-INDEPENDENT
> "what does this machine LOOK like overall?" canvas (picker +
> interactive zoom / pan / fit, regardless of focused event) is **not a
> Dynamic tab** — it lives under Static mode's Machines tab. There is no
> standalone Dynamic "Machines Canvas" tab.

Spec: [`021-Dynamic-Panel-Designs.md` §6](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`003-Machine-Inspector.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/003-Machine-Inspector.md).

## Routes — what this event did to routing

Question: **What did this event do to my routes?** (Display label
**Routes**.)

Three stacked sections, top → bottom:

- **Current route** (always visible) — the active route id, its params,
 and the matched path. Reads a calm caption when the host has no current
 route.
- **Navigation this epoch** — `from ──► to` plus the matched params and
 the outcome. Quiet when the focused event is not a navigation: the empty
 state reads "No route activity in this epoch." and the sections above and
 below stay visible.
- **Route table** (always visible) — the full registered route graph as an
 indented tree, the current row highlighted, with `from` / `to` overlay
 glyphs for this epoch's navigation.

Reads the focused cascade's routing trace ops, correlated by dispatch
id; silent (no sections at all) when no routes are registered.

**Open when:** "what route am I on?", "what params resolved?", "did the
route change this epoch?" **There is no URL input on this tab.** To rank
an arbitrary URL against every registered route, go to **Static →
Routes**, which mounts the Simulate-URL input and where each row's
Simulate navigation button also previews what a navigation would land
(matched params, the `:on-match` event, the route slice) without
dispatching anything.

Spec: [`021-Dynamic-Panel-Designs.md` §7](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`spec/012-Routing.md`](https://github.com/day8/re-frame2/blob/main/spec/012-Routing.md).
