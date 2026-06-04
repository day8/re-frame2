# 001-Topology-Parity: matching xstate / Stately Studio

> **What this doc is.** A **parity plan** for `MachineChart`'s
> machine-topology rendering against the named comparator —
> **xstate / Stately Studio** (the §Quality bar floor in
> [`000-Vision.md`](000-Vision.md)). It establishes the bar (what
> xstate/Stately render), assesses where `MachineChart` is today,
> identifies the **gaps** to close for parity, designs the
> representation + visual treatment that closes them, and lays out a
> prioritised, beaded **roadmap**. The topology renderer is **already
> built** (`chart.cljs` on `@xyflow/react` + `elkjs`); this is a
> close-the-gaps plan, **not** greenfield.
>
> **What this doc is NOT.** Not a re-derivation of the rendering-stack
> lock (that lives in [`000-Vision.md`](000-Vision.md) §Rendering stack
> + §Decision trace) and not a re-spec of the embedding-host chrome
> (that lives in [`tools/xray/spec/003-Machine-Inspector.md`](../../xray/spec/003-Machine-Inspector.md)).
> This doc owns the **topology-representation parity bar** and the
> close-the-gap roadmap; it is the numbered companion the
> [`README.md`](README.md) reserved as "capability detail" (it folds in
> the optional `001-Rendering.md` capability-doc scope — rf2-3f03c).
>
> **Figma-ready.** Per the Trace-panel spec convention, this doc lists
> the **visual dimensions** that must distinguish each topology element
> and **delegates the palette to Figma** — it does not fix hex values.
> The current implementation's token choices (cyan / violet / amber /
> green) are recorded as the **shipped baseline**, not a prescription;
> Figma is free to re-key the palette as long as the dimensions below
> stay distinguishable.

## Method — anchor, map, flag divergences

Per the re-frame2 skill convention (and the
[`000-Vision.md`](000-Vision.md) §Decision-trace requirement): every
parity claim **anchors on the JS-ecosystem tool** (xstate/Stately),
**maps it onto re-frame2**, and **names where re-frame2 deliberately
diverges**. The parity bar (§1) is the anchor; the current state (§2)
+ gap analysis (§3) are the map; the divergences (§3) are flagged as
**intentional, not gaps**.

**Construction, not mimicry.** Both Stately Studio and `MachineChart`
sit on the **same two primitives** — `@xyflow/react` for the renderer
and `elkjs` for layout (the no-divergence rows in
[`000-Vision.md`](000-Vision.md) §Layout engine + §Interactive
renderer). Stately's first-party
[`toXYFlow`](https://stately.ai/docs/packages/graph/src-formats-xyflow)
converter maps `position`, `width`/`height`, `shape → type`,
`parentId` (nesting), `sourcePort`/`targetPort → sourceHandle`/
`targetHandle`, and `edge.data.label` — **the same fields**
`chart.projection.cljc` emits (`:position`, node `:type`,
`parentId`/`extent:"parent"`, `Handle` ids, `:data {:eventLabel}`).
So a large slice of parity is **already structural**; the gaps below
are the residue that the shared primitives do not buy for free.

## §1 — The parity bar (xstate / Stately, cited)

The bar, decomposed by the nine topology concerns. Each row states
what xstate/Stately **render**, with a citation.

### 1.1 Hierarchy (compound states)

xstate models compound (nested) states; Stately draws a **parent
"compound" state as a rounded container** with its child states laid
out inside. "A parent state is a state that can contain more states,
also known as child states. These child states can only happen when
the parent state is happening."
([stately.ai/docs/parallel-states](https://stately.ai/docs/parallel-states),
[state-machines-and-statecharts](https://stately.ai/docs/state-machines-and-statecharts)).
The IR is `@xstate/graph`'s `toDirectedGraph`, a hierarchical
`{id, stateNode, children, edges}` tree
([stately.ai/docs/xstate-graph](https://stately.ai/docs/xstate-graph)).
Layout nests children inside the parent's bounding box; the
`toXYFlow` converter carries `parentId` "unchanged, enabling nested
node structures"
([xyflow converter](https://stately.ai/docs/packages/graph/src-formats-xyflow)).

### 1.2 Compound / parallel regions (orthogonality)

xstate models parallel states; Stately renders a **parallel state as
multiple regions all active at once**, conventionally separated by a
**dashed divider** (UML/SCXML orthogonal-region convention). "A
parallel state is a state that has multiple child states (also known
as regions) that are all active at the same time. You can easily
visualize and simulate parallel states in Stately's editor."
([stately.ai/docs/parallel-states](https://stately.ai/docs/parallel-states)).
The bar: **every region is drawn**, **delineated as a distinct zone**,
and **every region's active leaf is highlighted simultaneously**.

### 1.3 Transition scoping (source/target, self, eventless, delayed)

Stately: "the arrows are transitions, and the rounded rectangles on
the arrow's lines are events"; each transition has a source and a
target. **Self-transitions** are drawn ("a state transitions back to
itself"). **Delayed** transitions are labelled `after` + interval;
**eventless** transitions are labelled `always`
([editor-states-and-transitions](https://stately.ai/docs/editor-states-and-transitions)).
Hierarchical transitions resolve along the LCA (entry/exit cascade) —
xstate/SCXML semantics. **`onDone`** — XState draws the compound /
parallel **completion** transition (the arrow a compound's done-state
takes to advance the outer flow; SCXML §3.7's `done.state.<id>`); Stately
Studio renders it as the "do these sub-flows, then continue" arrow off
the compound. re-frame2 ships it first-class as `:on-done` (Spec 005
§The done-state signal) — **projected by rf2-41goo** (see §2 transition
scoping + §3.1 G9).

### 1.4 Initial / final / history

- **Initial** — "the filled circle with an arrow icon" pointing at
  the initial child, at **every** compound level
  ([editor-states-and-transitions](https://stately.ai/docs/editor-states-and-transitions)).
- **Final** — a final state, conventionally a doubled border (UML
  final-state ring); configured via the state-type menu in Stately
  ([editor-states-and-transitions](https://stately.ai/docs/editor-states-and-transitions)).
- **History** — "a history state remembers the last child state that
  was active before its parent state was exited"; Stately supports a
  history pseudo-state node
  ([stately.ai/docs/parallel-states](https://stately.ai/docs/parallel-states) §history,
  [state-machines-and-statecharts](https://stately.ai/docs/state-machines-and-statecharts)).
  **(re-frame2 has no `:history` in Spec 005 v1 — see §3 divergences.)**
  **rf2-az6e2 — VISUAL HOOK ONLY.** The structured grammar defines how a
  history pseudo-state *renders* (shallow `H` / deep `H*`, a small
  symbolic node inside the owning compound at the same child level, NOT
  a normal state box, with direct incoming transitions and no default
  fallback edge unless the topology explicitly carries one). The
  renderer (`chart.nodes/history-marker`) is registered in the
  node-types map, but `chart.layout/parse-definition` emits **no**
  history pseudo-state node today (the parsed node shape carries no
  `:history` data), so the projector never produces one. This bead adds
  the VISUAL rendering for parsed pseudo-state data that already exists —
  it does **not** add statechart history semantics. When Spec 005 history
  semantics + the parse land, flip the projector to emit a
  `{:type "history-marker" :data {:deep? …}}` node and `history-marker`
  paints it; file the follow-on render-wiring bead then.

> **rf2-az6e2 final-state glyph decision.** The final-state marker is the
> quiet **doubled border** (the UML/SCXML convention) — the previously-
> shipped `✓` check glyph is **dropped**. The doubled border is the
> unambiguous final-state signal; the glyph competed with the state
> title for attention against the structure-first grammar.

### 1.5 Event labels

Stately labels transitions by **type**: normal → event name; delayed
→ `after` + interval; eventless → `always`; **guarded → numbered
labels "if / else if" with the condition**
([editor-states-and-transitions](https://stately.ai/docs/editor-states-and-transitions)).
The label is the rounded "event" chip on the arrow line.

### 1.6 Guards / actions

Guards render as the conditional label on the transition (numbered
`if`/`else if` ordering). Actions: "you can ... trigger an action when
a state is entered or exited" — entry/exit actions are surfaced on the
state; transition actions on the edge
([editor-states-and-transitions](https://stately.ai/docs/editor-states-and-transitions)).

### 1.7 Layout

Stately migrated **Dagre → ELK** (`elkjs`) for compound nesting +
parallel regions (the [`000-Vision.md`](000-Vision.md) §Layout engine
no-divergence row). ELK's **Layered** algorithm (Sugiyama) routes
edges with **bend points** and supports three routing styles —
**ORTHOGONAL** (Manhattan), polyline, spline — plus **full layout of
compound graphs with cross-hierarchy edges** when the option is
activated on the top level
([ELK Layered](https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html),
[ELK Edge Routing](https://eclipse.dev/elk/reference/options/org-eclipse-elk-edgeRouting.html)).
The bar: edges route **around** nested containers via bend points,
not straight through them.

### 1.8 Simulation

Stately simulates in-editor (step through events, watch active state
move). xstate's runtime inspect (`@xstate/inspect`) attaches an
external inspector over a WebSocket and highlights live. **(re-frame2
is trace-driven from the Spec 009 bus, in-process — see §3
divergences.)**

### 1.9 Interactivity / ergonomics

Pan / zoom / fit / minimap (xyflow built-ins both sides); clickable
nodes / edges; legible labels at glance. This is the
[`000-Vision.md`](000-Vision.md) §Quality bar's three axes
(interactivity · layout robustness · visual ergonomics). xyflow buys
pan/zoom/fit/minimap for free on both sides — **structural parity**.

## §2 — Xray / `MachineChart` current state

What `MachineChart` renders **today**, cited to the cljs/cljc. The
render path is `chart.cljs` (`@xyflow/react` + `elkjs`) over a pure
parse (`chart/layout.cljc`) + pure projection (`chart/projection.cljc`)
+ custom node/edge components (`chart/nodes.cljs`, `chart/edges.cljs`,
`chart/nodes/parallel_region_node.cljs`).

| Concern | Status today | Where |
|---|---|---|
| **Hierarchy** | ✅ Compound parents render as a dashed container with a header-strip title; children nest via xyflow `parentId` + `extent:"parent"` (rf2-xh1lm — v12 reads `parentId`, NOT the pre-v12 `parentNode`); elk lays children inside the parent box (`INCLUDE_CHILDREN`). Nesting recurses (compound-in-compound, compound-in-region). | `nodes.cljs` `compound-node`; `projection.cljc` `->elk-children` (`:parent-id` grouping), `xyflow-graph` (`parentId`/`extent`); `layout.cljc` `parse-flat` (`:parent-id` on nested nodes). |
| **Parallel regions — structure** | ✅ **Every** region renders as a synthetic `:region?` compound node with a **distinct dashed boundary per region** (palette rotation) + `∥` glyph + region label; states carry `:region` + `:parent-id`; edges stay region-local. | `layout.cljc` `parse-parallel`/`region-node-id`; `projection.cljc` (`type "parallel-region"`); `nodes/parallel_region_node.cljs`. |
| **Parallel regions — active highlight** | ✅ **Closed (rf2-yoe6e / rf2-g2svr).** `highlight-ids` resolves the whole snapshot `:state` — flat keyword, compound path, **or region-map** `{region path}` — to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so a parallel snapshot's **N simultaneously-active leaves** all light up at once. A nested region value resolves to its deepest leaf. | `layout.cljc` `highlight-ids`; `projection.cljc` `xyflow-graph` (`:highlight-ids` set). |
| **Parallel regions — active CONTAINER chrome** | ✅ **Closed (rf2-80rm2 / G4).** The region (and, for self-consistency, the compound) **container** reads as active when ANY descendant leaf is active — the projector folds the container into `:active` by walking each active leaf UP its `:parent-id` chain (reusing the G1 active set; no duplicate highlight logic). `parallel-region-node` / `compound-node` then paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so an active region reads as active at the zone level, not just the leaf inside it. **Palette delegated to Figma.** | `projection.cljc` `xyflow-graph` (`active-container-ids` parent-id walk); `nodes/parallel_region_node.cljs`; `nodes.cljs` `compound-node`. |
| **Transition scoping** | ✅ `:on`, `:after`, `:always`, machine-level (top-level `:on`) fallback, **`:on-done` (XState `onDone`) compound / parallel completion** (rf2-41goo), external + internal self-transitions, vector-of-candidates forks. **Machine-level fallback projects ONCE** (rf2-vcnvj): a top-level `:on` is sourced from a synthetic MACHINE-ROOT node into its target as a SINGLE chip — not one back-edge per leaf (the pre-vcnvj per-state expansion repeated the chip AND injected N back-edges that scrambled ELK's top-to-bottom ranking). **`:on-done` (rf2-41goo)**: a COMPOUND's `:on-done` projects a completion edge from the compound to its SIBLING target (resolved at the compound's own level), an `✓ done` chip distinct from an ordinary event arrow; a PARALLEL-ROOT's `:on-done` (action/fx-only — registration rejects a `:target`) renders as a TERMINAL completion affordance (a hanging done chip, no sibling segment). Self-loops render as a small loop (not a degenerate bezier); internal self-transitions render dashed + no arrowhead. | `layout.cljc` `collect-state-edges`, `on-done-edges`, `collect-machine-edges` (root-sourced), `machine-root-id`, `resolve-target-path`; `projection.cljc` (`machine-root` node type, `:onDone` / `:doneState` event-node data); `nodes.cljs` `machine-root-node`; `edges.cljs` (self-loop path, internal dash). |
| **Initial** | ✅ Filled-dot `initial-marker` node + unlabelled entry edge into the initial state, at **every** compound level (xstate/SCXML semantics). | `nodes.cljs` `initial-marker`; `projection.cljc` (marker-nodes + entry-edges); `layout.cljc` `collect-nodes` (per-level `:initial?`). |
| **Final** | ✅ Quiet doubled border (outer ring 1px proud) on `state-node`. **No glyph** — the prior `✓` check glyph is DROPPED (rf2-az6e2, §1.4); the doubled border is the unambiguous final-state signal. | `nodes.cljs` `state-node` (final ring; no glyph). |
| **History** | 🪝 **Render-hook only (rf2-az6e2, §1.4) — NOT yet emitted.** The `history-marker` renderer (shallow `H` / deep `H*`) is registered in the node-types map, but `chart.layout/parse-definition` emits **no** history pseudo-state node today (the parsed node shape carries no history data), so the projector never produces one. First-class `:history` is NOT shipped; the hook is shaped to the grammar awaiting parsed history topology (and Spec 005 history semantics). | `nodes.cljs` `history-marker` (registered hook); `layout.cljc` (no history emission). |
| **Event labels** | ✅ `event [guard] / action` composed label; `after(<ms>)`, `always`, `* (any)` wildcard segments; opaque chart-bg backplate (rf2-j10sm Phase 1) for legibility against overlapping ink; label is clickable when a fireable event-id + host callback are present. **Multi-event collapse SUPERSEDED + RETIRED** (rf2-j10sm Phase 2 → rf2-qo5xy → rf2-o6vh7): the old collapse rendered N transitions on one `[source target]` pair as ONE arrow + N stacked labels (`:siblingIndex` / `:siblingCount`). Under events-as-nodes (rf2-qo5xy) each event is its OWN event-node, so same-`[source target]` transitions stay DISTINCT event-nodes (no collapse); rf2-o6vh7 removed the dead `:siblingIndex` / `:siblingCount` + `data-sibling-*` machinery. (The rf2-r7vsr post-render collision-avoidance overlay was **retired** in rf2-0xbgx: events-as-nodes moved labels onto event-nodes and left the in/out edges label-less, so the edge-label sweep was inert; elk layout + events-as-nodes makes label-on-node-body collisions a non-issue.) | `layout.cljc` `edge-label`/`event-segment`; `edges.cljs` `transition-edge`; `projection.cljc` `xyflow-graph` (one event-node per parsed transition). |
| **Guards / actions** | ✅ Guard in `[...]`, action after `/`; entry/exit state actions render as `entry / <name>` / `exit / <name>` rows under the label; state-tag pills above. | `layout.cljc` `edge-label`, `name-of`; `nodes.cljs` `state-node` (entry/exit rows, tag pills). |
| **Layout** | ✅ elk Layered, `DOWN`/`RIGHT` direction, `INCLUDE_CHILDREN` when nested (G5, rf2-gpa9k), per-container padding for header strips; async + cached pass; xyflow `fitView`. **Initial-state placement soft preference** (rf2-ly51l): `elk.layered.cycleBreaking.strategy DEPTH_FIRST` breaks a cyclic statechart's loops by a depth-first walk **from the sources** (the initial state) rather than GREEDY min-reversed-count, so the forward spine starting at the initial state ranks near the top (`:tb`) / left (`:lr`) — fixing the door's `open`-on-top / `locked`-third mis-rank. The initial state also **leads its container's model order** (`order-state-children`; machine-root annotation sinks last), biasing DEPTH_FIRST's source selection + the within-layer tiebreak toward it. **Soft, not invariant**: only the cycle-reversal SET changes; full layer-sweep crossing-min + node-placement still run, so a state can land off the initial-on-top ideal when crossings demand. Acyclic graphs are layout-identical to GREEDY. | `chart.cljs` `compute-layout!`/`default-elk-options` (`cycleBreaking.strategy DEPTH_FIRST`)/`elk-layout-options` (root `layoutOptions` + cross-hierarchy switch); `projection.cljc` `order-state-children` + `->elk-children` (initial-leads model order + per-container `layoutOptions`). |
| **Edge routing through nesting** | ✅ **Closed (rf2-cz8v6; key-scheme fixed rf2-r636q).** elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into an `{elk-edge-id [{:x :y} …]}` map keyed by the `<spec-edge-id>__in` / `<spec-edge-id>__out` ids (the two segments the events-as-nodes split mints, rf2-qo5xy), and the projector attaches each segment's route to the matching xyflow edge's `:data {:points}` — `__in` to the inbound edge, `__out` to the outbound edge. `transition-edge` then draws a smooth poly-path THROUGH the bends, so a deeply-nested transition routes **around** a container instead of cutting across it. Self-loops keep their dedicated loop path; an edge with no elk route falls back to the bezier. *(rf2-r636q: the consumer formerly looked up the bare `<spec-edge-id>` the producer never emits → the feature was silently dead until the producer/consumer key scheme was reconciled.)* | `chart.cljs` `default-elk-options` (`ORTHOGONAL` + `edgeCoords ROOT`) / `elk-edge-points` / `elk-result->positions`; `projection.cljc` `xyflow-graph` (`:edge-points` `__in`/`__out` → `:data {:points}`); `edges.cljs` `edge-path` (poly-path). |
| **Fired-this-epoch edge highlight** | ✅ **Closed (rf2-8jzm1 + rf2-qeemm / G3).** The Xray inspector resolves the focused epoch's traversed edges via `extract-fired-edge-ids` (CANONICAL machines-viz edge-ids, B7) and threads them as `:fired-edge-ids` (set) into `MachineChart`; the projector marks each matching edge `:fired` and `transition-edge` paints the FIRED treatment (emphasised + animated stroke + `data-fired`) along the routed path — coexisting with G2's bend-points + G1's active styling. Matches the EDGE directly (not the from/to ENDPOINT lens), so every microstep / guard-fork arm lights up. **Colour delegated to Figma** (`:accent` baseline, distinct from the focused/active `:info`). | `projection.cljc` `xyflow-graph` (`:fired-edge-ids` → `:data {:fired}`); `chart.cljs` (`:fired-edge-ids` prop); `edges.cljs` `transition-edge` (FIRED stroke + `data-fired`); Xray `trace_state/extract-fired-edge-ids` + `machine_inspector` / `machine_canvas` wiring. |
| **Simulation** | ✅ (host-side, trace-driven + hermetic sim) — live highlight off `[:rf/runtime :machines :snapshots <id>]` + the Spec 009 bus; the Static-Machines Sim sub-mode is a hermetic what-if walker. Edge labels carry `:eventId` + `:onClick` so a host wires "click to send". | `projection.cljc` (`:on-edge-click`/`:eventId`); Xray `static/machines/sim.cljs` (per 003). |
| **Interactivity / ergonomics** | ✅ xyflow pan/zoom/fit/minimap/background; density-resolved geometry + typography; node/edge click callbacks; `:after` countdown rings + `:spawn-all` join + cancellation overlays (re-frame2-native, no Stately peer). | `chart.cljs`; `visual-constants`; overlays per 003 / 000-Vision. |

**Headline:** structurally **at parity** on hierarchy, regions
(layout), initial, final, transition scoping, event labels,
guards/actions, layout, and ergonomics. The high-severity **parallel
multi-active highlight** gap (G1) is **closed** (rf2-yoe6e / rf2-g2svr),
the bend-point edge-routing gap (G2) is **closed** (rf2-cz8v6), the
region-ACTIVE chrome polish (G4) is **closed** (rf2-80rm2), and the
**fired-this-epoch edge highlight** (G3) is now **closed** (rf2-8jzm1
canonical-id helper + rf2-qeemm live-chart wiring), and the
low-severity ELK cross-hierarchy-routing assertion (G5) is now
**closed** (rf2-gpa9k) — the cross-hierarchy switch
(`elk.hierarchyHandling INCLUDE_CHILDREN`, set on the root
`layoutOptions` whenever the graph nests/parallels) rode in with G2 and
is now regression-guarded by test. The three **compound-endpoint
rendering gaps** surfaced post-rf2-xh1lm are now also **closed**
(rf2-shv82): compound-endpoint edges no longer silently drop (G6
below), multi-self-loop labels fan to distinct perimeter slots (G7),
cross-hierarchy labels anchor at the source-side bend point (G8). The
**`:on-done` (XState `onDone`) completion-transition** projection gap
(G9) — `:on-done` was NEVER projected in chart / mermaid / scxml
(pre-rf2-41goo the "COMPLETE" claim overstated parity; §1.3's onDone read
was unmet) — is now **closed** (rf2-41goo): a compound's `:on-done`
projects the sibling completion edge, a parallel-root's renders a terminal
completion affordance, and the SCXML emitter round-trips the W3C
`done.state.<id>` transition.
**The machine-topology parity roadmap is COMPLETE (G1 / G2 / G3 / G4 /
G5 / G6 / G7 / G8 / G9 all ✅).** Visual-readability follow-on (rf2-j10sm)
shipped padded label backgrounds (Phase 1, D). Its Phase 2, B
multi-event collapse (N transitions sharing a `[source target]` pair →
ONE arrow with N stacked labels via `:siblingIndex` / `:siblingCount`)
was **SUPERSEDED by events-as-nodes (rf2-qo5xy) and RETIRED in
rf2-o6vh7**: each event now projects as its own first-class event-node,
so same-`[source target]` transitions stay DISTINCT event-nodes (no
collapse, no grouping) — each independently click-addressable. The dead
`:siblingIndex` / `:siblingCount` machinery and `data-sibling-*` attrs
are gone. (Phase 3's post-render collision-avoidance overlay (rf2-r7vsr) was
later **retired** in rf2-0xbgx — events-as-nodes (rf2-qo5xy) moved
labels onto event-nodes, leaving the edge-label sweep inert; elk
layout + events-as-nodes makes label-on-node-body collisions a
non-issue.) **rf2-rlq97** then closed the remaining d9ro2 follow-on
class — d9ro2 sized the node BOXES from their measured DOM, but the
edges + their clearances were not yet fully ELK-budgeted, so arrows
could still clip node boxes and densely-packed event-nodes could crowd.
The fix fed the edges into the ELK graph through the pure
`chart.projection/->elk-edges` and added edge-to-node / edge-to-edge
clearance + edge-label-placement keys to `default-elk-options`
(`elk.spacing.edgeNode`, `elk.layered.spacing.edgeNodeBetweenLayers`,
`elk.spacing.edgeEdge`, `elk.edgeLabels.placement CENTER`,
`elk.spacing.edgeLabel`), so ORTHOGONAL routes go AROUND node boxes and
ELK places any labelled edge's text in a reserved channel — the
renderer reads ELK's computed label position (`:edge-labels` →
`:data {:labelPos}`) instead of the middle-segment-midpoint heuristic.
This stays the deterministic d9ro2 two-pass (measure → one relayout); no
convergence loop. Self-loops are already dissolved by events-as-nodes
(a spec self-transition is `state → event-node → state`, two ordinary
ELK-routed edges), so the renderer-side self-loop fan is fallback-only.

## §3 — Gap analysis + deliberate divergences

### 3.1 Gaps to close for parity

Each gap: what's missing, severity, the closing bead (existing or
new), and the parity-bar row it serves.

| # | Gap | Severity | Serves bar | Bead |
|---|---|---|---|---|
| **G1** | ✅ **CLOSED (rf2-yoe6e / rf2-g2svr).** Was: a parallel snapshot's `:state` is a region-map `{region path}` with **N active leaves**; `highlight-id` returned nil for a map, so the chart highlighted **none** (or only a degenerate single id). Now: `highlight-ids` resolves the whole `:state` (flat / compound / region-map, nested values → deepest leaf) to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so all N regions light up at once — Stately's §1.2 read. | **High** | §1.2, §1.9 | **contract:** rf2-yoe6e ✅ · **impl:** rf2-g2svr ✅ |
| **G2** | ✅ **CLOSED (rf2-cz8v6) → HARDENED (rf2-rlq97).** Was: edges were beziers between handles; elk's Layered bend-points were discarded (§1.7), so in deep nesting an edge could cut **across** a region/compound container instead of routing **around** it. rf2-cz8v6: elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into the projection (`:edge-points` → `:data {:points}`), and `edges.cljs/edge-path` draws a smooth poly-path THROUGH them — routing around non-incident containers (the [`000-Vision.md`](000-Vision.md) §Quality-bar "no edge-crossing collapse" floor). rf2-rlq97 (d9ro2 follow-on): the edges are fed into the ELK graph through the pure `chart.projection/->elk-edges`, and `default-elk-options` gained edge-to-node / edge-to-edge **clearance** (`elk.spacing.edgeNode`, `elk.layered.spacing.edgeNodeBetweenLayers`, `elk.spacing.edgeEdge`) so routes keep a gap from node boxes (kills the "arrows over states" residue) **plus** label-placement keys (`elk.edgeLabels.placement CENTER`, `elk.spacing.edgeLabel`) so ELK *places* any labelled edge's text in a reserved channel (`:edge-labels` → `:data {:labelPos}`), the renderer painting ELK's position instead of the midpoint heuristic. Self-loops are dissolved by events-as-nodes (`state → event-node → state`), so the renderer-side loop path is fallback-only; no-route edges fall back to the bezier; G1's active-edge highlight survives. | **Medium** | §1.7, §1.9 | **impl:** rf2-cz8v6 ✅ · **harden:** rf2-rlq97 ✅ |
| **G3** | ✅ **CLOSED (rf2-8jzm1 + rf2-qeemm).** Was: to highlight "the edge that fired this epoch" on the live chart, the host's trace→edge-id mapping had to mint the **same** `edge-id` `chart.layout` mints, and the ids weren't wired through to the canvas. Now: `extract-fired-edge-ids` (rf2-8jzm1) projects the definition through the public `parse-definition` and reads ids off the projected edges — they agree with the live chart **by construction**; rf2-qeemm threads them as `:fired-edge-ids` (set) into `MachineChart`, the projector marks each matching edge `:fired`, and `transition-edge` paints the FIRED treatment (emphasised + animated stroke + `data-fired`) on the live canvas. Matches the EDGE directly so every traversed arm (microsteps, guard-fork candidates) lights up. **Palette delegated to Figma.** | **Medium** | §1.3, §1.8 | **helpers:** rf2-8jzm1 ✅ · **wire:** rf2-qeemm ✅ |
| **G4** | ✅ **CLOSED (rf2-80rm2).** Was: once G1 lit N region LEAVES, the **region CONTAINER** still read structural-only — an active region was indistinguishable from an inactive one at the zone level. Now: `xyflow-graph` folds a container (region OR compound) into `:active` when any descendant leaf is active — walked UP the `:parent-id` chain every node already carries (reuses the G1 active set; no path-prefix reimplementation). `parallel-region-node` / `compound-node` paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so each active region reads as active at a glance and the N active regions read as a set. **Palette delegated to Figma.** | **Low–Medium** | §1.2 | **impl:** rf2-80rm2 ✅ |
| **G5** | ✅ **CLOSED (rf2-gpa9k).** Was: elk routes cross-hierarchy edges only when the switch is activated on the top level (§1.7), and while `->elk-input` already set it, **nothing asserted it** — drop the line and no test would catch the regression. Now: the cross-hierarchy switch is `elk.hierarchyHandling INCLUDE_CHILDREN` on the root `layoutOptions`, set by the pure `chart.cljs/elk-layout-options` whenever the graph nests (`:parallel?` OR some node has a `:parent-id` — compound substate or parallel-region leaf); it is what lets the Layered algorithm route edges ACROSS nesting levels (its default `SEPARATE_CHILDREN` lays each level out independently and never routes cross-hierarchy edges). So an edge from a deeply-nested leaf to a top-level state routes cleanly, and G2's bend-points (rf2-cz8v6) come back as legible absolute coords. The capability rode in with G2; rf2-gpa9k extracted the option-computation into the assertable `elk-layout-options` and added the regression guard (nested/parallel ⇒ switch present, flat ⇒ absent, G2 routing keys pinned). | **Low** | §1.7 | **impl:** rf2-cz8v6 ✅ (capability) · **assert:** rf2-gpa9k ✅ |
| **G6** | ✅ **CLOSED (rf2-shv82).** Was: any edge whose source or target was a compound (a parent-level transition like `:active → :disconnected`, a compound self-loop, an inbound `:failed → :active`) was SILENTLY DROPPED from the DOM. The projector emitted it, ELK routed it, but xyflow's `getHandleBounds` returned null for the compound (no `<Handle>` children) → `isNodeInitialized` returned false → `getEdgePosition` returned null → the edge never reached the DOM. No warning. The 5-layer probe trace in the bead proved 4 such edges survived to ELK's output then 0 in the DOM. Now: `compound-node` + `parallel-region-node` render invisible source + target `<Handle>` elements on all four sides; xyflow accepts the compound as an edge endpoint and ELK's routed bend-points anchor on its BORDER the way xstate/Stately Studio paints parent-level transitions. The chart root surfaces `data-edge-count-projected` alongside `data-edge-count` so the parser → projector → DOM parity is regression-guarded end to end. | **High** | §1.3, §1.7 | **impl:** rf2-shv82 ✅ |
| **G7** | ✅ **CLOSED (rf2-shv82) → SUPERSEDED by events-as-nodes (rf2-qo5xy), collapse RETIRED (rf2-o6vh7).** Was: N self-loops on the same node (e.g. testdeck `:disconnected` carries 3: `:ws/arm-fail`, `:ws/disarm-fail`, `:ws/clear`) all rendered at the same loop anchor → garbled glyph soup. rf2-shv82 shipped a perimeter fan (8 slots, rotated per `:loopIndex`); rf2-j10sm Phase 2 then collapsed same-`[source target]` events into ONE arc + N stacked labels via `:siblingIndex` / `:siblingCount`. **The rf2-qo5xy events-as-nodes paradigm supersedes both**: each self-event is its own `rf2-event` node (`state → event-node → state`), so N self-loops are N DISTINCT event-nodes — no fan, no collapse, no garbled overlap. rf2-o6vh7 RETIRED the dead collapse machinery (`:siblingIndex` / `:siblingCount` and `data-sibling-*` are gone); the fan geometry survives only in `chart.edges/edge-path` for direct callers (every self-loop carries `:loopIndex 0`). | **Medium** | §1.3, §1.9 | **fan:** rf2-shv82 ✅ · **collapse:** rf2-j10sm (retired rf2-o6vh7) |
| **G8** | ✅ **CLOSED (rf2-shv82).** Was: a cross-hierarchy edge (source and target in different parent containers — e.g. testdeck `:active.authenticating → :failed`) routed via ELK's bend-points had a midpoint that landed far from its visual origin (the label sat at the canvas bottom-left). Now: the projector flags the edge `:crossHierarchy true` when the source's `:parent-id` ≠ the target's (self-loops are never cross-hierarchy regardless of nesting); `chart.edges/edge-path` anchors the label NEAR the source-side first bend point (xstate/Stately convention — the label hugs the bend just outside the container the edge exits, with a small back-bias along the incoming segment so it sits in the routed channel). Degenerate two-point routes fall back to the segment midpoint; the bezier-fallback path is unchanged. Surfaces `data-cross-hierarchy` on each transition edge label. | **Medium** | §1.5, §1.9 | **impl:** rf2-shv82 ✅ |
| **G9** | ✅ **CLOSED (rf2-41goo).** Was: Spec 005's first-class `:on-done` (XState `onDone` — the COMPOUND / PARALLEL completion transition that advances the outer flow when a sub-flow reaches its `:final?` child; SCXML §3.7 `done.state.<id>`) was **NEVER projected** — zero matches for `on-done` / `onDone` / `done.state` across `chart/layout.cljc`, `mermaid.cljc`, AND `scxml.cljc`. So the chart drew the `:final?` leaf but NOT the "then advance to sibling" arrow that actually fires; the SCXML round-trip was silently lossy for `onDone`; the "COMPLETE" headline overstated parity (§1.3's onDone read was unmet). Now: `layout.cljc/on-done-edges` parses a node's `:on-done`, `collect-state-edges` emits a COMPOUND's completion edge to its SIBLING (resolved at the compound's own level — XState onDone placement) flagged `:on-done?` + carrying the `:done-path`; `parse-parallel` emits the PARALLEL-ROOT's `:on-done` (action/fx-only — registration rejects a `:target`) as a TERMINAL completion affordance (self-anchored, `:internal?`) on a synthetic parallel-root node. `projection.cljc` buckets it as the `:on-done` event-variant and threads `:onDone` + the `done.state.<id>` `:doneState` label onto the event-node; the engine-raised `:rf.machine/done` is NOT click-to-send. `mermaid.cljc` renders the compound sibling edge (`✓ done`) + a parallel-root completion `note`; `scxml.cljc` emits + round-trips the W3C `<transition event="done.state.<id>">` (compound to sibling; parallel inside `<parallel>`). | **Medium** | §1.3, §1.5 | **impl:** rf2-41goo ✅ |

### 3.2 Deliberate non-parity divergences (intentional, NOT gaps)

Flagged per the skill convention — these are **principled choices**,
already recorded in [`000-Vision.md`](000-Vision.md) §Deliberate
divergences; restated here so the parity audit does not mis-file them
as gaps.

| Divergence | Stately does | re-frame2 does | Why |
|---|---|---|---|
| **Read-only inspector — no code↔diagram sync** | Bidirectional editor (edit diagram → regen code) | Read-only projection of an already-registered machine; no canvas authoring | [`000-Vision.md`](000-Vision.md) §What it isn't → "Not an editor"; Lock #1 component-not-product. |
| **Trace-driven sim, not a sandbox interpreter** | In-editor sim + `@xstate/inspect` WebSocket bridge | Spec 009 trace bus + hermetic what-if Sim, **in-process** | [`000-Vision.md`](000-Vision.md) §Active-state highlighting transport — the bus already carries every needed event at near-zero cost; no separate-window UX. |
| **History rendering scope** | History state node (last-active recall), full editor semantics | **No history pseudo-states in v1** (Spec 005 §History — no `:history` in v1). The `history-marker` renderer (shallow `H` / deep `H*`) is a registered **render-hook only** (rf2-az6e2, §1.4): `chart.layout/parse-definition` emits no history node today, so the projector never produces one. When Spec 005 history semantics + the parse land, the projector flips to emit a `history-marker` node and the hook paints it. | Spec 005 §History states; the hook is shaped to the grammar awaiting parsed history topology (rf2-az6e2). |
| **Guard label form** | Numbered `if / else if` + condition | `event [guard]` bracket convention (+ Sim **lists** failed-guard transitions greyed rather than hiding them) | re-frame2 reads guard as a **predicate annotation** on one edge, not an ordered if-chain; the Sim's "list, don't hide" is a teaching stance (003 §Failed-guard handling). |
| **re-frame2-native overlays** | — (no peer) | `:after` countdown rings, microstep replay, `:spawn-all` join visualisation, cancellation cascade | Projections of re-frame2 substrate semantics with no Stately counterpart — **above** the parity bar, not gaps. |
| **EDN model** | TS object literals / JSON IR | EDN (`reg-machine` body) verbatim — keywords, sets, namespaced keys preserved | [`000-Vision.md`](000-Vision.md) §Model format. |

## §4 — Design to match (representation + visual)

The representation changes + the **visual dimensions** that close §3.1.
**Palette delegated to Figma** — each element lists the dimensions that
must distinguish it; the shipped token (in *italics*) is the baseline,
not a prescription.

### 4.1 G1 — parallel multi-active highlight (representation)

The contract (rf2-yoe6e) + impl (rf2-g2svr):

- **`highlight-id` → `highlight-ids`.** Add a pure resolver that takes
  the **whole snapshot `:state`** and returns a **set** of node-ids:
  - flat keyword / vector path → a one-element set (back-compat with
    the single-active case);
  - **region-map `{region path}`** → resolve each region's value to
    its node-id and return the **set of N leaves**. Region-scoped
    paths resolve **within** the region (a region's `path` is relative
    to that region's own state-tree; the node-id is minted under the
    region's `parentId`).
- **Projection threads a set.** `xyflow-graph` accepts
  `:highlight-ids` (set) alongside / superseding the scalar
  `:highlight-id`; a node is `:active` when its id ∈ the set. The
  scalar stays as a convenience that wraps to a singleton set so no
  caller breaks.
- **Pure + JVM-tested.** Both live in `chart.layout` / `chart.projection`
  (the JVM-runnable layers) so the parse/projection test corpus pins:
  region-map → correct N-leaf set; flat/compound → singleton; nil →
  empty set; a region whose leaf is itself compound → the deepest
  active leaf.

### 4.2 Visual dimensions (Figma-ready)

The dimensions each topology element MUST be distinguishable on. Figma
owns the palette; the table is the contract for **what must differ**,
not **which colour**.

**rf2-az6e2 — structured visual grammar.** The baselines below reflect
the structured topology grammar: structure-first reading (neutral by
default), runtime state on the **border + header + glow** (not the
fill), and colours resolved through the active-theme **chart-tokens**
(see [`API.md` §Theme](API.md#theme-rf2-az6e2)). The contract is still
*what must differ*, not *which colour*.

| Element | Must be distinguished by | Shipped baseline (replaceable) |
|---|---|---|
| **State (resting)** | **title/body box** — full-width left-aligned title strip (sans), hairline divider when body exists, neutral body band; low-radius square-ish box | *`:state-body-bg` fill, `:state-border` 1px, `:state-header-bg` strip* |
| **State (active / live)** | runtime accent **border** + faint **header** wash + soft outer **glow** — NOT a whole-fill tint | *`:active` border + `:active-wash` header + `:glow` ring* |
| **State (FROM — focused-event origin)** | distinct accent on the border (reads as "we left here") | *`:focus` border + `:focus-wash` header* |
| **State (TO — focused-event landing)** | the **active** affordance, heavier than FROM | *`:active`, heaviest stroke* |
| **State (sim — what-if, not live)** | a hue reserved for informational/not-live | *`:sim` border + `:sim-wash` header* |
| **Final state** | **quiet doubled** border (outer ring proud of corner). **No glyph** (rf2-az6e2 dropped the ✓) | *outer ring in the resting/runtime border colour* |
| **Initial marker** | small **neutral** filled dot + unlabelled arrow into the initial state, at every compound level (NOT accent-blue) | *`:pseudo-marker` dot* |
| **History pseudo-state (HOOK)** | small symbolic node — shallow `H` / deep `H*` — inside the owning compound; NOT a state box. **Renderer registered as a hook; projector emits none until parsed history topology exists** (this bead adds no statechart history semantics) | *`history-marker` node-type registered; `:pseudo-*` constants carry the variant* |
| **Compound container** | **solid subtle-neutral** box + full-width title strip; NO dashed border, NO accent wash by default | *`:container-body-bg` fill, `:container-border` solid, `:container-header-bg` strip* |
| **Parallel region container** | **dashed neutral** boundary + full-width region title strip + subtle `∥` glyph + uppercased label. Region identity from **containment/layout**, NOT a rotating border colour | *`:region-border` dashed (same for every region — rotation removed)* |
| **Parallel region (ACTIVE) — G1/G4** | region boundary firms to **solid** + header carries an **active affordance**; **every** active leaf inside lights **simultaneously** | ✅ shipped (rf2-80rm2 / rf2-az6e2): solid `:active` boundary + `:active-wash` header + `:glow` ring |
| **Edge (source→event — quiet half)** | **thinner** stroke + **small** arrowhead in the quiet colour | *`:edge-quiet`, ~−0.5 stroke, 10px arrowhead* |
| **Edge (event→target — primary half)** | standard stroke + **full** arrowhead — the pair reads as ONE route | *`:edge-quiet` resting, 18px arrowhead* |
| **Edge (active — touches active state)** | mid-weight stroke + arrow tinted to active hue | *`:edge-active`, midweight* |
| **Edge (focused — the fired FROM→TO)** | **emphasised** stroke + **animated glow**; lights **both** segments together | *`:edge-active` + `mv-chart-transition-glow`* |
| **Edge (fired THIS epoch) — G3** | matched by **edge-id** (not endpoint), **heaviest** stroke + **animated glow** + a hue distinct from focused/active; `data-fired` hook; both segments | *`:edge-fired` + `mv-chart-transition-glow`* |
| **Edge (self-loop)** | a small loop off the node edge (not a degenerate bezier) | shipped path |
| **Edge (internal self-transition)** | terminal **route chip** (dashed border ring) + **no outgoing target segment** | shipped (event-node, no `__out` edge) |
| **Edge (`:after` timer)** | `⌚ <ms>` event chip + `data-after-ms` hook for the countdown-ring overlay | shipped |
| **Edge (`:always` eventless)** | `∞` event chip | shipped |
| **Edge (machine-level fallback)** | a SINGLE route from the MACHINE-ROOT chip into the target (rf2-vcnvj — projected once, not per-leaf); `data-machine-level` hook on the event chip; the loud "machine-level" label is **muted** by default (rf2-az6e2) | shipped flag + root-sourced route |
| **Machine-root chip (rf2-vcnvj)** | a small NEUTRAL pill (root glyph `◆` + `root` caption) that anchors machine-level fallbacks; NOT a state box; `data-machine-root` hook | *`:container-header-bg` fill, `:state-border`, pill radius* |
| **Event chip** | subordinate route chip, **no title bar**; event + guard on the first line as **`IF <guard>`**; action row only when present (subdued bolt chip); clickable (host sim) gets a button affordance + distinct border | *`:event-chip-bg` / `:event-chip-border`; `:sim` border when clickable* |
| **State tags** | **one neutral chip style** (structure wins over annotation colour — rotation dropped); the VISIBLE label + `data-tag` preserve the DECLARED namespaced identity (`door/open`, not truncated `open` — rf2-vcnvj); `data-testid` keeps the `name`-collapsed segment (a `/` breaks selectors) | *`:container-header-bg` fill, `:state-border`* |
| **Entry / Exit action caption** | quiet **TITLE-CASE** section label ("Entry actions" / "Exit actions") — NOT uppercase (rf2-vcnvj; the uppercase transform competed with the state title) | *`:text-tertiary`, small caption px* |
| **Root machine chrome** | a **root title strip** pinned in the chart frame; subtle `∥` glyph for root-parallel machines; a Context section showing the **static context shape** (keys + type captions of the definition's `:data`) when available (rf2-vcnvj — the Xray topology path derives it via `topology-view/static-context-shape`); live runtime `:data` remains a separate optional overlay | shipped (`<testid>-root-title` + `<testid>-machine-data-panel`) |
| **Event label segments** | `⌚ <ms>` / `∞` / `* (any)` render in the event slot | shipped |

### 4.3 G2 / G5 — edge routing through nesting

- ✅ **Consume elk bend-points (rf2-cz8v6/G2 — DONE).** elk's Layered
  result carries per-edge **bend-point sections**; `compute-layout!`
  (with `elk.edgeRouting ORTHOGONAL` + `elk.json.edgeCoords ROOT`) lifts
  them as absolute coords into `:edge-points`, the projection threads
  them onto each edge `:data {:points}`, and `edges.cljs/edge-path`
  renders a **rounded poly-path** through the bend points instead of a
  single bezier between handles. This is what stops an edge cutting
  across a nested container at machine sizes (§1.7).
- ✅ **Feed edges + clear + place labels in ELK (rf2-rlq97/G2 harden —
  DONE; d9ro2 follow-on).** The edges are projected into the ELK graph
  by the pure `chart.projection/->elk-edges` (lifted out of the inline
  JS-side `mapcat` so the edge-feed is JVM-pinnable, mirroring
  `->elk-children`), and `default-elk-options` gained the **clearance**
  keys `elk.spacing.edgeNode` / `elk.layered.spacing.edgeNodeBetweenLayers`
  / `elk.spacing.edgeEdge`. ORTHOGONAL routing alone draws Manhattan
  routes but, with no edge-to-node spacing, lets a passing route hug or
  clip a node box — the "arrows route OVER state nodes" residue d9ro2's
  node-measure (node↔node sizing) could not converge. The clearance keys
  reserve the channel so routes go AROUND nodes. For LABELS, ELK now owns
  placement (`elk.edgeLabels.placement CENTER` + `elk.spacing.edgeLabel`):
  `->elk-edge` feeds each edge a `:labels` entry carrying the **measured**
  label box (the edge-label analogue of d9ro2's node measure) when an
  edge renders its own label; `elk-result->positions` lifts ELK's
  computed position into `:edge-labels` (`{elk-edge-id {:x :y}}`, the
  LABEL analogue of `:edge-points`); the projector threads it onto
  `:data {:labelPos}`; and `edges.cljs/edge-path` anchors the label at
  ELK's position rather than the middle-segment-midpoint heuristic.
  **Under events-as-nodes the transition text is on the event-NODE**
  (already ELK-measured + -placed by d9ro2's measure pass), so the
  `__in` / `__out` edges carry empty labels, `:edge-labels` is empty, and
  the midpoint heuristic survives only as the no-ELK-label fallback —
  but the clearance keys still apply to every route. Stays the
  deterministic d9ro2 two-pass (measure → one relayout); no convergence
  loop. The renderer-side self-loop fan is fallback-only (a spec
  self-transition routes as `state → event-node → state`, two ordinary
  ELK edges).
- ✅ **Assert cross-hierarchy edge routing (rf2-gpa9k/G5 — DONE).** The
  top-level cross-hierarchy switch is `elk.hierarchyHandling
  INCLUDE_CHILDREN` on the root `layoutOptions`, set by
  `chart.cljs/elk-layout-options` whenever the graph nests (`:parallel?`
  OR a node has a `:parent-id`). It lets elk compute routes ACROSS
  nesting levels (vs the default `SEPARATE_CHILDREN`), so an edge from a
  nested leaf to a top-level state routes cleanly — and G2's bend-points
  are only useful once elk is allowed to compute these cross-hierarchy
  routes, which is exactly what this switch grants. rf2-gpa9k extracted
  the pure `elk-layout-options` and added the regression guard
  (`chart/edges-cljs-test`: nested/parallel ⇒ switch present, flat ⇒
  absent, G2's `ORTHOGONAL` + `edgeCoords ROOT` pinned alongside).
- **Visual dimension:** an edge MUST visibly route **around** a
  container it does not enter (no overlap of edge path and a
  non-incident region/compound box). Figma owns stroke style; the
  **routing geometry** is the contract.

### 4.4 G3 — fired-edge id consistency ✅ CLOSED

- ✅ **Single edge-id source of truth (rf2-8jzm1 — DONE).**
  `chart.layout/edge-id` is the canonical minting fn. Rather than
  RE-IMPLEMENT the scheme (source-id `__` target-id `__` event-segment
  `__g_<guard>` `__a_<action>` + per-key ordinal tiebreak), the host's
  `extract-fired-edge-ids` (Xray-side) **projects the definition through
  the public `parse-definition`** and reads ids off the projected edges,
  so they agree with the live chart **by construction** — exactly one
  minting fn. The node-id half was already unified (rf2-m8kod injective
  hex-escape).
- ✅ **Wire the highlight (rf2-qeemm — DONE).** `MachineChart` accepts a
  `:fired-edge-ids` (set) prop; `xyflow-graph` marks each matching edge
  `:fired`; `edges.cljs/transition-edge` paints the FIRED treatment
  (heaviest stroke + animated glow + `data-fired`, in a hue distinct
  from the focused/active `:info`) along the routed path — coexisting
  with G2's bend-points + G1's active styling. The Xray inspector
  resolves the set per focused epoch (`extract-fired-edge-ids`) and
  threads it through `machine_canvas/Chart` → `MachineChart`. Because
  the match is by **edge-id** (not endpoint node-ids like the from/to
  lens), it lights **every** traversed arm — microsteps, guard-fork
  candidates — the lens cannot reach. **Palette delegated to Figma.**

## §5 — Roadmap

Prioritised. Each item marked **existing-bead** vs **NEW**, with
hot-zone flags. **Hot-zone files** (sequential, never parallel per the
project dispatch rules): `tools/xray/spec/003-Machine-Inspector.md`.
**Isolated surfaces** (safe to parallel): single-artefact
`tools/machines-viz/src/` + `tools/machines-viz/test/` +
`tools/machines-viz/spec/` new files.

> **Sequencing context.** The maturation roots **already merged**:
> rf2-ecday (003 renderer-drift fix), rf2-ijttt (Stately renderer/IR
> ground-truth in [`000-Vision.md`](000-Vision.md)), rf2-m8kod (node-id
> unification). The items below are the **remaining** parity work.

### Phase A — parallel parity (the high-severity gap)

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| A1 | **rf2-yoe6e** — spec the parallel multi-active-highlight contract | existing | **HOT** (touches 003 + machines-viz API) | Defines `highlight-ids` set semantics + region-map resolution. Serial vs any other 003 edit. |
| A2 | **rf2-g2svr** — implement parallel multi-active highlight | existing | isolated (`chart.layout`/`chart.projection` + tests) | Set-returning resolver + projection threads the set; JVM-tested. Depends on A1. |
| A3 | **rf2-80rm2** ✅ — `feat(machines-viz): parallel-region ACTIVE chrome (region-active affordance + simultaneous N-leaf read)` | existing | isolated (`projection.cljc` `active-container-ids` + `nodes/parallel_region_node.cljs` + `nodes.cljs` `compound-node` + JVM tests) | **Closed G4.** Container folds into `:active` via the `:parent-id` chain (reuses the G1 active set); region + compound containers paint the solid-boundary + `:info` glow active chrome (§4.2). **Figma owns the region-active palette.** |

### Phase B — edge-routing fidelity (the medium gap)

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| B1 | **rf2-cz8v6** ✅ — elk bend-point edge routing | existing | isolated (`chart.cljs` position pass + `chart.edges` polyline + tests) | **Closed G2.** elk `ORTHOGONAL` + `edgeCoords ROOT`; `compute-layout!` lifts bend-point sections; `edge-path` renders a rounded poly-path through them. |
| B2 | **rf2-gpa9k** ✅ — `test(machines-viz): assert ELK cross-hierarchy edge-routing (INCLUDE_CHILDREN)` | existing | isolated (`chart.cljs` `elk-layout-options` extract + `chart/edges-cljs-test`) | **Closed G5.** The capability rode in with B1/G2 (the switch `elk.hierarchyHandling INCLUDE_CHILDREN` was already set when nested) but was unasserted; rf2-gpa9k extracted the pure `elk-layout-options` and pinned it: nested/parallel ⇒ switch present, flat ⇒ absent, G2 routing keys present. |

### Phase C — fired-edge live highlight (host wiring)

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| C1 | **rf2-8jzm1** ✅ — consolidate trace→state helpers; emit machines-viz edge-ids | existing | isolated (Xray helpers ns + tests) | **Closed G3 (helper half).** `extract-fired-edge-ids` mints `chart.layout/edge-id`-identical ids (via `parse-definition` projection — agree by construction). |
| C2 | **rf2-qeemm** ✅ — wire fired-this-epoch edge highlight to the live chart | existing | isolated (machines-viz `chart`/`projection`/`edges` + Xray panel wiring + tests) | **Closed G3 (wire half).** `:fired-edge-ids` prop → `:fired` edge flag → FIRED treatment (`data-fired`). Depended on C1 + A2. |

### Phase D — documentation

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| D1 | **rf2-3f03c** — `001-Rendering.md` capability doc | existing (optional) | isolated | **Re-scope / fold into THIS doc** — `001-Topology-Parity.md` now claims the next numbered slot and carries the per-concern capability detail. rf2-3f03c can close as folded-in, or re-aim at a narrower "rendering pipeline internals" reference (`002-Rendering.md`) if a distinct surface emerges. Mayor to decide. |

### Phase E — compound-endpoint rendering (the post-rf2-xh1lm tail)

After rf2-xh1lm landed compound CONTAINMENT (substates nest inside
the parent's box), three rendering gaps around compound EDGES
surfaced in the 2026-05-25 pair-debug session and were closed
together in rf2-shv82.

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| E1 | **rf2-shv82** ✅ — `fix(machines-viz): compound-endpoint edges + self-loop label fan + cross-hierarchy label placement` | existing | isolated (`chart/nodes.cljs`, `chart/nodes/parallel_region_node.cljs`, `chart/projection.cljc`, `chart/edges.cljs`, `chart.cljs`, + JVM/CLJS/DOM tests + this doc + `API.md`) | **Closes G6 / G7 / G8.** Three independent fixes around one investigation context: (G6) container nodes carry invisible `<Handle>`s so compound-endpoint edges survive xyflow's `getEdgePosition` (which previously returned null for unhandled compounds and silently dropped the edge); (G7) self-loops fan to distinct perimeter slots per source via a `:loopIndex` ordinal; (G8) cross-hierarchy edge labels anchor at the source-side bend point rather than the routed midpoint. End-to-end parity gate `data-edge-count == data-edge-count-projected` is now pinned on every machine. |

### Phase F — `:on-done` completion projection (the senior-dev faithfulness pass)

A 2026-06-04 senior-dev review (rf2-60whl) surfaced two definition→visual
faithfulness gaps; the `:on-done` projection gap was the parity-relevant
one (the sibling region-id collision rf2-wnzha is a parse-injectivity
fix, not a parity row).

A 2026-06-04 R2 re-review (rf2-5nb2v) of the F1 three-emitter projection
surfaced the F2–F4 follow-ons below: a **compound action-only `:on-done`**
that F1's mermaid path silently dropped (rf2-ay42f — the G9 "faithful
across all three" claim was overstated for that shape), the **parallel-root
anchor** that F1 left clickable as a phantom state (rf2-dblqx — the same
inert-chip class rf2-34ff3 ruled), and the **parallel-root done-state label**
that diverged between chart + SCXML (rf2-bs3us). With F2–F4 the three-emitter
`:on-done` projection is faithful across every reachable Spec 005 shape, and
the parallel-root anchor is inert.

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| F1 | **rf2-41goo** ✅ — `feat(machines-viz): project :on-done (XState onDone) completion transition (chart + mermaid + scxml)` | existing | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `mermaid.cljc`, `scxml.cljc`, + JVM tests + this doc) | **Closes G9.** `:on-done` was never parsed/projected (zero matches across the three emitters). Now: the COMPOUND completion edge resolves to the SIBLING (`✓ done` chip); the PARALLEL-ROOT completion (action/fx-only) renders as a terminal affordance / mermaid note; the SCXML emitter round-trips the W3C `done.state.<id>` transition. Parses + projects faithfully to the engine's `[:rf.machine/done <path>]` raise. |
| F2 | **rf2-ay42f** ✅ — `fix(machines-viz): render compound action-only :on-done in mermaid` | new | isolated (`mermaid.cljc` + JVM tests + this doc) | **G9 faithfulness completion.** A COMPOUND whose `:on-done` is ACTION-ONLY (no `:target` — the engine runs an action when the sub-flow completes; the machine stays in the all-final config; a documented Spec 005 shape) surfaced in chart + SCXML but was SILENTLY DROPPED in mermaid (`collect-on-done-edges` kept only target-bearing candidates; the note path covered only the parallel-root). Now mermaid renders a `note right of <compound>` carrying `on-done: ✓ done / <action>` — the same affordance the parallel-root action-only `:on-done` uses — so the completion is visible across **all three** emitters. The G9 "faithful across all three emitters" claim is now accurate for the action-only compound shape. (`collect-compound-on-done-notes` walks the state tree incl. region-nested + deeply-nested compounds.) |
| F3 | **rf2-dblqx** ✅ — `fix(machines-viz): parallel-root :on-done anchor is inert (not a clickable phantom state)` | new | isolated (`chart/projection.cljc` + JVM tests + this doc) | The synthetic parallel-root completion anchor (F1) fell through the node-`:type` cond to `"state"` AND through the `:onClick` guard (which excluded only `:machine-root?` + region), so it projected as a CLICKABLE `parallel` state box — clicking it dispatched on-state-click against the rendering-sentinel path. The SAME inert-synthetic-chip class **rf2-34ff3** ruled for the machine-root chip + region containers. Fix: type the parallel-root anchor `"machine-root"` (the quiet root-context chip) AND exclude `:parallel-root?` from the `:onClick` guard, mirroring 34ff3. The anchor is now INERT. |
| F4 | **rf2-bs3us** ✅ — `fix(machines-viz): align chart parallel-root done-state label to SCXML` | new | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `scxml.cljc` + JVM tests + this doc) | The chart's parallel-root `:doneState` label was the degenerate `"done.state."` (the root sentinel path `[]` has an EMPTY `node-id`), diverging from the SCXML emitter's `"done.state.rf2_parallel_root"`. Fix: a shared canonical sentinel id (`layout/parallel-root-done-state-id` = `"rf2_parallel_root"`) is the SINGLE SOURCE OF TRUTH both the chart `:doneState` renderer label and the SCXML `<parallel id=...>` / `done.state.<id>` event read from, so the two emitters agree. Pure label fix (not load-bearing for topology / round-trip). |

> **Sibling note (rf2-wnzha — same review pass, NOT a parity row):**
> parallel regions sharing a state NAME minted COLLIDING node-ids (the
> Spec 005 `:ingest` shape — three regions each with a `:done`). The fix
> region-SCOPES every region-state node-id (`layout.cljc/region-scoped-id`)
> so the parse is injective across regions + the G1 multi-active highlight
> attributes per-region. A correctness/injectivity fix beneath the parity
> bar, not a new gap row.

### Proposed NEW beads (for the mayor to file)

1. **N1 — `feat(machines-viz): parallel-region ACTIVE chrome`**
   ✅ **filed + closed as rf2-80rm2** (closes **G4**). Region-header +
   boundary active affordance + simultaneous N-active-leaf treatment
   that reads as one set. Isolated (`projection.cljc`
   `active-container-ids` parent-id walk + `nodes/parallel_region_node.cljs`
   + `nodes.cljs` `compound-node` + JVM tests). Depended on rf2-g2svr
   (A2). **Figma owns the region-active palette.**
2. **N2 — `test(machines-viz): assert ELK cross-hierarchy edge-routing`**
   ✅ **filed + closed as rf2-gpa9k** (closes **G5**). The cross-hierarchy
   switch (`elk.hierarchyHandling INCLUDE_CHILDREN`) was already set on
   the root `layoutOptions` when the graph nests — the capability rode in
   with G2 (rf2-cz8v6) — but nothing asserted it. rf2-gpa9k extracted the
   pure `chart.cljs/elk-layout-options` and added the regression guard
   (`chart/edges-cljs-test`: nested/parallel ⇒ switch present, flat ⇒
   absent, G2 routing keys pinned). Isolated (`chart.cljs` + test).

> **Parity verdict after Phases A–F (incl. G9):** `MachineChart` reaches
> **full topology parity** with Stately Studio on all nine concerns
> (transition scoping now includes the `:on-done` / XState `onDone`
> completion transition — rf2-41goo / G9) except the three **intentional
> divergences** (no code↔diagram sync, trace-driven not sandbox sim, no
> history pseudo-states) — and remains **above** the bar on the
> re-frame2-native overlays (`:after` rings, microstep replay,
> `:spawn-all` join, cancellation cascade).

## See also

- [`000-Vision.md`](000-Vision.md) — the §Quality bar (Stately Studio
  floor), the §Rendering stack + §Decision trace (the locked
  xyflow+elkjs choice + the cited XState/Stately ground-truth + the
  deliberate divergences this doc operationalises).
- [`API.md`](API.md) — the `MachineChart` contract the parity work
  lands against.
- [`tools/xray/spec/003-Machine-Inspector.md`](../../xray/spec/003-Machine-Inspector.md)
  — the embedding host: transition-history ribbon, source-coord wiring,
  `:spawn-all` viz, Sim sub-mode, fired-edge highlight wiring (G3 host
  half).
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) —
  the machine model: compound paths, parallel region-map `:state`,
  microsteps, LCA cascade, self-transitions, `:spawn-all`, final
  states; **no `:history` in v1**.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  — the `:rf.machine/*` trace vocab the trace-driven highlight + fired-
  edge mapping consume.
