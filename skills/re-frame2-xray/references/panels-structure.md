# panels-structure — live runtime structure: Graph, Frames, Hicasso

The structure family: the three Dynamic-shell tabs that do **not** follow
the focused event. **Graph** draws the dependency graph across families;
**Frames** shows which image loaded each live frame; **Hicasso** is the
evidence lens over the Hicasso view layer. Picking an epoch in L2 rebinds
none of them — Graph follows its own projection toggle (and the L1 frame
picker on the Realized side), Frames and Hicasso read the live runtime.
Inventory + scope matrix: [panels.md](panels.md).

## Graph — where does this value come from?

Question: **Where does this value come from — when is it evaluated,
where does it live, who owns it?** — across families, in one place.
(Display label **Graph**.)

Xray's UI over the **EP-0014 derivation/process graph**: every declared
fact and process in the host app — across all five contributor families
(subscriptions, flows, resources, route facts, machine processes +
selectors) — as one node-and-edge graph over the frame fold. A family
with no registrations contributes no nodes (the per-app no-machines /
no-resources story, not a per-tool boundary).

**The projection toggle is Graph-local.** A per-panel **Declared ↔
Realized** toggle flips registration-derived vs observed (Declared reads
the process-global registrar; Realized reads the observed frame). The
shipped UI labels the toggle static/live — it is **NOT** the L1
Dynamic/Static mode pill, and Graph is always a Dynamic tab: switching
Xray to Static mode shows the five registry catalogues, not Graph.

Two caveats this leaf owns:

> **Authority is an axis, not a storage class.** Remote-backed nodes
> (resources) carry an **authority** chip. A resource's *storage* class
> is still local (the frame's runtime-managed state); *remote* describes
> where the value is sourced/owned upstream. Read the chip as "locally
> stored, locally read, upstream source of truth" — never as
> app-db/runtime-db placement.

> **The graph accessor is internal, not a public API.** The Graph tab
> consumes an internal structured composer — not a `re-frame.core`
> facade export, and not a public app accessor. Route users to **open
> the Graph tab**; do not tell them to call a graph API from app code.

**Read-only** — observing the graph pins nothing, dispatches nothing.

**Open when:** "where does this value come from?", "show me the whole
derivation graph", "what's the dependency graph for this page?", "is
this ephemeral or materialized, and who owns it?"

Spec: [`spec/Derivations.md` §Graph inspection — internal but structured](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md)
+ [`docs/EP/EP-0014-derivation-and-process-algebra.md`](https://github.com/day8/re-frame2/blob/main/docs/EP/EP-0014-derivation-and-process-algebra.md).

## Frames — which image loaded which frame

Question: **Which images load which frames, and how do those frames
resolve their registrations?** (Tab-bar label **Frames**; the internal id
`:module-view` is not a user contract, and the old "Modules" label is
retired — route users to the **Frames** tab.)

Xray's UI over the EP-0023 **`image → frame`** public model — the
structural counterpart to Graph (Graph is the per-fact view; Frames is
the per-frame installation + image-provenance view). Each live frame
renders as an **execution context** carrying its resolved image (the
generation's `[kind id]` descriptor set), its capabilities, and how it
resolves `(kind id)` lookups through that image. This is the model a
consumer app developer reasons in: image assembly plus frame isolation
are the whole composition story — **no realm / app / module layer to
browse**.

- **Registry-wide, not epoch-coupled** — enumerates the process-global
 live-frame registry; picking an epoch does not rebind it.
- **L4-only registry tab** — focusable via the tab bar / command
 palette, but with **no** standalone mount facade: there is no
 `mount-module-view!`, and users never need one.
- **Read-only** — enumerating frames and images pins nothing.

**Open when:** "what frames exist, and which image loaded each?", "how
does this frame resolve its registrations?"

Spec: [`026-Module-View-Panel.md` §8](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/026-Module-View-Panel.md).

## Hicasso — the view-layer evidence lens

Question: **What is Hicasso actually doing — which boundaries are
mounted, what do they read, and why did one re-render?** The evidence
lens for Hicasso, re-frame2's re-frame-native view layer.

**Six views over four envelopes**, as a sub-strip inside the tab:

| View | The question it answers |
|---|---|
| **Mounted** | which boundaries are mounted, over which frames |
| **Reads** | which boundaries read each subscription |
| **Intents** | what was dispatched, in order, inside the retained window |
| **Why** | which reads changed, and what that can and cannot prove |
| **Advisor** | which boundary is hot, and the smallest route that addresses it |
| **Causal** | one dispatch walked link by link from event to paint, every missing link named |

The first four each read one evidence envelope. **Advisor and Causal
read no envelope of their own** — they are derivations over the *same*
one-turn take, which is why they are sub-views rather than tabs: a
second tab would take a second turn, and a mount landing between the two
would desynchronise the census. So "four" and "six" are both right,
about different things — **four envelopes, six views**.

**Every view states its own scope, basis, completeness and loss.** An
absence renders as a **named loss state with its own sentence**, never
an empty list — "nothing is mounted" is a clean bill of health, while
"the retained intent window is empty" is a cap that proves nothing about
what was dispatched. A host **not running Hicasso** shows the honest
no-evidence state, distinct from *running with nothing mounted*.

- **No read carries application data** — a boundary's identity is its
 read set; subscription arguments and return values never reach the
 page.
- **Not epoch-coupled** — the evidence is re-taken on each trace tick;
 picking an epoch in L2 does not rebind it.
- **L4-only registry tab** — focusable, no standalone mount facade.
- **Read-only** — the tab observes and dispatches nothing into the host.

**Open when:** "which boundaries are mounted?", "what reads
`:cart/items`?", "why did this boundary re-render?", "which boundary is
hot?", "walk this dispatch from event to paint".

Spec: [`027-Hicasso-Evidence.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/027-Hicasso-Evidence.md)
+ [`028-Hicasso-Advisor.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/028-Hicasso-Advisor.md).
