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
`parentNode`/`extent:"parent"`, `Handle` ids, `:data {:eventLabel}`).
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
xstate/SCXML semantics.

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
| **Hierarchy** | ✅ Compound parents render as a dashed container with a header-strip title; children nest via xyflow `parentNode` + `extent:"parent"`; elk lays children inside the parent box (`INCLUDE_CHILDREN`). Nesting recurses (compound-in-compound, compound-in-region). | `nodes.cljs` `compound-node`; `projection.cljc` `->elk-children` (`:parent-id` grouping), `xyflow-graph` (`parentNode`/`extent`); `layout.cljc` `parse-flat` (`:parent-id` on nested nodes). |
| **Parallel regions — structure** | ✅ **Every** region renders as a synthetic `:region?` compound node with a **distinct dashed boundary per region** (palette rotation) + `∥` glyph + region label; states carry `:region` + `:parent-id`; edges stay region-local. | `layout.cljc` `parse-parallel`/`region-node-id`; `projection.cljc` (`type "parallel-region"`); `nodes/parallel_region_node.cljs`. |
| **Parallel regions — active highlight** | ✅ **Closed (rf2-yoe6e / rf2-g2svr).** `highlight-ids` resolves the whole snapshot `:state` — flat keyword, compound path, **or region-map** `{region path}` — to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so a parallel snapshot's **N simultaneously-active leaves** all light up at once. A nested region value resolves to its deepest leaf. | `layout.cljc` `highlight-ids`; `projection.cljc` `xyflow-graph` (`:highlight-ids` set). |
| **Parallel regions — active CONTAINER chrome** | ✅ **Closed (rf2-80rm2 / G4).** The region (and, for self-consistency, the compound) **container** reads as active when ANY descendant leaf is active — the projector folds the container into `:active` by walking each active leaf UP its `:parent-id` chain (reusing the G1 active set; no duplicate highlight logic). `parallel-region-node` / `compound-node` then paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so an active region reads as active at the zone level, not just the leaf inside it. **Palette delegated to Figma.** | `projection.cljc` `xyflow-graph` (`active-container-ids` parent-id walk); `nodes/parallel_region_node.cljs`; `nodes.cljs` `compound-node`. |
| **Transition scoping** | ✅ `:on`, `:after`, `:always`, machine-level (top-level `:on`) fallback, external + internal self-transitions, vector-of-candidates forks. Self-loops render as a small loop (not a degenerate bezier); internal self-transitions render dashed + no arrowhead. | `layout.cljc` `collect-state-edges`, `collect-machine-edges`, `resolve-target-path`; `edges.cljs` (self-loop path, internal dash). |
| **Initial** | ✅ Filled-dot `initial-marker` node + unlabelled entry edge into the initial state, at **every** compound level (xstate/SCXML semantics). | `nodes.cljs` `initial-marker`; `projection.cljc` (marker-nodes + entry-edges); `layout.cljc` `collect-nodes` (per-level `:initial?`). |
| **Final** | ✅ Doubled border (outer ring 1px proud) + `✓` glyph inline on `state-node`. | `nodes.cljs` `state-node` (final ring + glyph). |
| **History** | ➖ **Intentionally absent** — Spec 005 v1 has no `:history`. Not a gap. | Spec 005 §History states (`:rf.error/machine-grammar-not-in-v1`). |
| **Event labels** | ✅ `event [guard] / action` composed label; `after(<ms>)`, `always`, `* (any)` wildcard segments; backplate for legibility; label is clickable when a fireable event-id + host callback are present. | `layout.cljc` `edge-label`/`event-segment`; `edges.cljs` `transition-edge`. |
| **Guards / actions** | ✅ Guard in `[...]`, action after `/`; entry/exit state actions render as `entry / <name>` / `exit / <name>` rows under the label; state-tag pills above. | `layout.cljc` `edge-label`, `name-of`; `nodes.cljs` `state-node` (entry/exit rows, tag pills). |
| **Layout** | ✅ elk Layered, `DOWN`/`RIGHT` direction, `INCLUDE_CHILDREN` when nested, per-container padding for header strips; async + cached pass; xyflow `fitView`. | `chart.cljs` `compute-layout!`/`default-elk-options`; `projection.cljc` `->elk-children` (per-container `layoutOptions`). |
| **Edge routing through nesting** | ✅ **Closed (rf2-cz8v6).** elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into an `{edge-id [{:x :y} …]}` map that the projector attaches to the edge `:data {:points}`. `transition-edge` then draws a smooth poly-path THROUGH the bends, so a deeply-nested transition routes **around** a container instead of cutting across it. Self-loops keep their dedicated loop path; an edge with no elk route falls back to the bezier. | `chart.cljs` `default-elk-options` (`ORTHOGONAL` + `edgeCoords ROOT`) / `elk-edge-points` / `elk-result->positions`; `projection.cljc` `xyflow-graph` (`:edge-points` → `:data {:points}`); `edges.cljs` `edge-path` (poly-path). |
| **Simulation** | ✅ (host-side, trace-driven + hermetic sim) — live highlight off `[:rf/machines <id>]` + the Spec 009 bus; the Static-Machines Sim sub-mode is a hermetic what-if walker. Edge labels carry `:eventId` + `:onClick` so a host wires "click to send". | `projection.cljc` (`:on-edge-click`/`:eventId`); Xray `static/machines/sim.cljs` (per 003). |
| **Interactivity / ergonomics** | ✅ xyflow pan/zoom/fit/minimap/background; density-resolved geometry + typography; node/edge click callbacks; `:after` countdown rings + `:spawn-all` join + cancellation overlays (re-frame2-native, no Stately peer). | `chart.cljs`; `visual-constants`; overlays per 003 / 000-Vision. |

**Headline:** structurally **at parity** on hierarchy, regions
(layout), initial, final, transition scoping, event labels,
guards/actions, layout, and ergonomics. The high-severity **parallel
multi-active highlight** gap (G1) is now **closed** (rf2-yoe6e /
rf2-g2svr), the bend-point edge-routing gap (G2) is now **closed**
(rf2-cz8v6), and the region-ACTIVE chrome polish (G4) is now **closed**
(rf2-80rm2) — the parallel-parity read is complete; the residue is the
two **fired-edge consistency** items the live-chart wiring needs.

## §3 — Gap analysis + deliberate divergences

### 3.1 Gaps to close for parity

Each gap: what's missing, severity, the closing bead (existing or
new), and the parity-bar row it serves.

| # | Gap | Severity | Serves bar | Bead |
|---|---|---|---|---|
| **G1** | ✅ **CLOSED (rf2-yoe6e / rf2-g2svr).** Was: a parallel snapshot's `:state` is a region-map `{region path}` with **N active leaves**; `highlight-id` returned nil for a map, so the chart highlighted **none** (or only a degenerate single id). Now: `highlight-ids` resolves the whole `:state` (flat / compound / region-map, nested values → deepest leaf) to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so all N regions light up at once — Stately's §1.2 read. | **High** | §1.2, §1.9 | **contract:** rf2-yoe6e ✅ · **impl:** rf2-g2svr ✅ |
| **G2** | ✅ **CLOSED (rf2-cz8v6).** Was: edges were beziers between handles; elk's Layered bend-points were discarded (§1.7), so in deep nesting an edge could cut **across** a region/compound container instead of routing **around** it. Now: elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into the projection (`:edge-points` → `:data {:points}`), and `edges.cljs/edge-path` draws a smooth poly-path THROUGH them — routing around non-incident containers (the [`000-Vision.md`](000-Vision.md) §Quality-bar "no edge-crossing collapse" floor). Self-loops keep their loop path; no-route edges fall back to the bezier; G1's active-edge highlight survives the new path. | **Medium** | §1.7, §1.9 | **impl:** rf2-cz8v6 ✅ |
| **G3** | **Fired-edge id consistency (live chart).** To highlight "the edge that fired this epoch" on the live chart, the host's trace→edge-id mapping MUST mint the **same** `edge-id` `chart.layout` mints. Today the helper chain needs consolidating so `extract-fired-edge-ids` emits machines-viz `edge-id`s. (The node-id scheme was already unified — rf2-m8kod.) | **Medium** | §1.3, §1.8 | **helpers:** rf2-8jzm1 (existing) · **wire:** rf2-qeemm (existing) |
| **G4** | ✅ **CLOSED (rf2-80rm2).** Was: once G1 lit N region LEAVES, the **region CONTAINER** still read structural-only — an active region was indistinguishable from an inactive one at the zone level. Now: `xyflow-graph` folds a container (region OR compound) into `:active` when any descendant leaf is active — walked UP the `:parent-id` chain every node already carries (reuses the G1 active set; no path-prefix reimplementation). `parallel-region-node` / `compound-node` paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so each active region reads as active at a glance and the N active regions read as a set. **Palette delegated to Figma.** | **Low–Medium** | §1.2 | **impl:** rf2-80rm2 ✅ |
| **G5** | **Cross-hierarchy edge-routing option not asserted.** elk routes cross-hierarchy edges only when the option is activated on the top level (§1.7). The default elk options set `INCLUDE_CHILDREN` for nesting but do not pin the cross-hierarchy edge-routing option; an edge from a deeply-nested leaf to a top-level state may not route cleanly even after G2. | **Low** | §1.7 | **NEW — see §5 N2** (pairs with rf2-cz8v6/G2) |

### 3.2 Deliberate non-parity divergences (intentional, NOT gaps)

Flagged per the skill convention — these are **principled choices**,
already recorded in [`000-Vision.md`](000-Vision.md) §Deliberate
divergences; restated here so the parity audit does not mis-file them
as gaps.

| Divergence | Stately does | re-frame2 does | Why |
|---|---|---|---|
| **Read-only inspector — no code↔diagram sync** | Bidirectional editor (edit diagram → regen code) | Read-only projection of an already-registered machine; no canvas authoring | [`000-Vision.md`](000-Vision.md) §What it isn't → "Not an editor"; Lock #1 component-not-product. |
| **Trace-driven sim, not a sandbox interpreter** | In-editor sim + `@xstate/inspect` WebSocket bridge | Spec 009 trace bus + hermetic what-if Sim, **in-process** | [`000-Vision.md`](000-Vision.md) §Active-state highlighting transport — the bus already carries every needed event at near-zero cost; no separate-window UX. |
| **No history pseudo-states** | History state node (last-active recall) | **No `:history` glyph** — Spec 005 v1 omits the grammar (`:rf.error/machine-grammar-not-in-v1`); the substitute is snapshot-as-value capture | Spec 005 §History states + §Substitutes; there is nothing in the model to render. |
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
    region's `parentNode`).
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

| Element | Must be distinguished by | Shipped baseline (replaceable) |
|---|---|---|
| **State (resting)** | rounded-rect body, mono label, neutral fill + 1px border | *`bg-2` fill, `border-default` stroke* |
| **State (active / live)** | tinted fill + **emphasised** stroke (one notch heavier) + soft outer glow | *`info` (cyan) tint + 2px-box-shadow* |
| **State (FROM — focused-event origin)** | distinct hue from active + **dashed** stroke (reads as "we left here") | *`accent` (violet), dashed* |
| **State (TO — focused-event landing)** | the **active** affordance, heavier than FROM (reads as "we arrived") | *`info` (cyan), heaviest stroke* |
| **State (sim — what-if, not live)** | a hue **reserved** for "informational, not a real event" — visually distinct from live | *`yellow` (amber) tint + stroke* |
| **Final state** | **doubled** border (outer ring proud of corner) + `✓` glyph | *`green` ring + glyph* |
| **Initial marker** | small **filled dot** + unlabelled arrow into the initial state, at every compound level | *`accent` dot* |
| **Compound container** | translucent box, **dashed** border, header-strip title | *`accent` 6% fill, dashed `accent`* |
| **Parallel region container** | **distinct dashed boundary per region** (rotation so adjacent regions differ) + `∥` glyph + uppercased region label | *`region-boundary-palette` rotation* |
| **Parallel region (ACTIVE) — G1/G4** | region header + boundary carry an **active affordance**; **every** active leaf inside lights with the active dimension **simultaneously**; the set reads as "all active at once," not N independent picks | ✅ shipped (rf2-80rm2): solid emphasised boundary + emphasised header tint + `info` glow ring (the state-node active token). Figma may re-key the region-active palette |
| **Edge (resting transition)** | thin stroke + arrowhead + label backplate | *`border-default`* |
| **Edge (active — touches active state)** | mid-weight stroke + arrow tinted to active hue | *`info`, midweight* |
| **Edge (focused — the fired FROM→TO)** | **emphasised** stroke + **animated glow** | *`info` + `mv-chart-transition-glow`* |
| **Edge (self-loop)** | a small loop off the node edge (not a degenerate bezier) | shipped path |
| **Edge (internal self-transition)** | **dashed** loop + **no arrowhead** (no exit/entry re-trigger) | shipped dash |
| **Edge (`:after` timer)** | `after(<ms>)` label + `data-after-ms` hook for the countdown-ring overlay | shipped |
| **Edge (`:always` eventless)** | `always` label segment | shipped |
| **Edge (machine-level fallback)** | `data-machine-level` hook (inherited-fallback affordance the host may style) | shipped flag |
| **Edge label** | `event [guard] / action`; clickable form (host sim) gets a button affordance + distinct border | *`yellow` border when clickable* |
| **Event label segments** | `after(ms)` / `always` / `* (any)` render in the event slot | shipped |

### 4.3 G2 / G5 — edge routing through nesting

- ✅ **Consume elk bend-points (rf2-cz8v6/G2 — DONE).** elk's Layered
  result carries per-edge **bend-point sections**; `compute-layout!`
  (with `elk.edgeRouting ORTHOGONAL` + `elk.json.edgeCoords ROOT`) lifts
  them as absolute coords into `:edge-points`, the projection threads
  them onto each edge `:data {:points}`, and `edges.cljs/edge-path`
  renders a **rounded poly-path** through the bend points instead of a
  single bezier between handles. This is what stops an edge cutting
  across a nested container at machine sizes (§1.7).
- **Assert cross-hierarchy edge routing (rf2 NEW N2/G5).** Add the elk
  top-level cross-hierarchy edge-routing option to
  `default-elk-options` so an edge from a nested leaf to a top-level
  state routes cleanly. Pairs with G2 — bend-points are only useful if
  elk is allowed to compute cross-hierarchy routes.
- **Visual dimension:** an edge MUST visibly route **around** a
  container it does not enter (no overlap of edge path and a
  non-incident region/compound box). Figma owns stroke style; the
  **routing geometry** is the contract.

### 4.4 G3 — fired-edge id consistency

- **Single edge-id source of truth.** `chart.layout/edge-id` is the
  canonical minting fn. The host's `extract-fired-edge-ids`
  (Xray-side, rf2-8jzm1) MUST call into / reproduce **exactly** that
  scheme (source-id `__` target-id `__` event-segment `__g_<guard>`
  `__a_<action>` + per-key ordinal tiebreak) so a fired-this-epoch
  trace maps to a real chart edge-id. The node-id half is already
  unified (rf2-m8kod injective hex-escape).
- **Wire the highlight (rf2-qeemm).** With consistent ids, the host
  sets the focused FROM→TO edge to the **focused** dimension (§4.2)
  for the fired transition of the focused epoch.

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
| B2 | **N2 (NEW)** — `feat(machines-viz): assert ELK cross-hierarchy edge-routing in default-elk-options` | **NEW** | isolated (`chart.cljs` `default-elk-options` + projection test) | Closes **G5**. Small option add; pairs with B1 (bend-points need cross-hierarchy routing enabled to matter). Land with or just before B1. |

### Phase C — fired-edge live highlight (host wiring)

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| C1 | **rf2-8jzm1** — consolidate trace→state helpers; emit machines-viz edge-ids | existing | isolated (Xray helpers ns + tests) | Closes **G3** (helper half). `extract-fired-edge-ids` mints `chart.layout/edge-id`-identical ids. |
| C2 | **rf2-qeemm** — wire fired-this-epoch edge highlight to the live chart | existing | isolated (Xray panel wiring + tests) | Closes **G3** (wire half). Sets the focused FROM→TO edge dimension. Depends on C1 + A2. |

### Phase D — documentation

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| D1 | **rf2-3f03c** — `001-Rendering.md` capability doc | existing (optional) | isolated | **Re-scope / fold into THIS doc** — `001-Topology-Parity.md` now claims the next numbered slot and carries the per-concern capability detail. rf2-3f03c can close as folded-in, or re-aim at a narrower "rendering pipeline internals" reference (`002-Rendering.md`) if a distinct surface emerges. Mayor to decide. |

### Proposed NEW beads (for the mayor to file)

1. **N1 — `feat(machines-viz): parallel-region ACTIVE chrome`**
   ✅ **filed + closed as rf2-80rm2** (closes **G4**). Region-header +
   boundary active affordance + simultaneous N-active-leaf treatment
   that reads as one set. Isolated (`projection.cljc`
   `active-container-ids` parent-id walk + `nodes/parallel_region_node.cljs`
   + `nodes.cljs` `compound-node` + JVM tests). Depended on rf2-g2svr
   (A2). **Figma owns the region-active palette.**
2. **N2 — `feat(machines-viz): assert ELK cross-hierarchy edge-routing`**
   (closes **G5**). Add the top-level cross-hierarchy edge-routing
   option to `default-elk-options`; projection test pins it. Isolated
   (`chart.cljs` + test). Pairs with rf2-cz8v6 (B1). P3/P4 (small;
   land with bend-points).

> **Parity verdict after Phases A–C + N1/N2:** `MachineChart` reaches
> **full topology parity** with Stately Studio on all nine concerns
> except the three **intentional divergences** (no code↔diagram sync,
> trace-driven not sandbox sim, no history pseudo-states) — and remains
> **above** the bar on the re-frame2-native overlays (`:after` rings,
> microstep replay, `:spawn-all` join, cancellation cascade).

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
