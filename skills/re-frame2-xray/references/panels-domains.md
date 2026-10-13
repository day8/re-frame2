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

**Event-driven and single-instance** — no picker, no browse modes, no
stacked machines. When the focused event targets no machine, the panel
shows one quiet placeholder line — "This event does not target a state
machine" ("No machines registered." when the app has none) — and nothing
else. When it does, the panel binds to the **one
machine instance** that event targeted (the first, when it touched
several) — a transition, a machine birth, or a guard-blocked no-op all
count — and renders exactly three things, in order:

1. **Prev / Next** in the panel header — walks the spine to the previous
 or next event that touched *this* machine, moving the focused epoch.
2. **The machine cascade** — the same numbered mini-pipeline the Epoch
 tab's EVENT HANDLER step renders: microstep, exit, action, entry and
 guard rows, each with click-to-source and its outcome / data write.
 Guard and action detail lives here; nothing else lists it.
3. **The topology chart** — the shared machines-viz **MachineChart**
 (nodes, edges, parallel-region containers, final-state double-rings)
 with this epoch's FROM state dashed, the TO state emphasised, fired
 edges highlighted, and `:after` countdown rings on armed timer states.
 It carries its own zoom / pan / fit controls. The current state is
 highlighted statically (accent border + soft glow ring); nothing
 pulses.

**Open when:** "what state is my checkout machine in?" (select the latest
event that touched it), "what transition fired this epoch?", "what guards
passed / failed?"

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
