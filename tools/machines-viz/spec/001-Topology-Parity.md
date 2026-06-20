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
  **re-frame2 has FIRST-CLASS history** — a `:type :history` pseudo-state
  (shallow / deep / `:default-target`) declared under a compound's
  `:states` ([Spec 005 §History states](../../../spec/005-StateMachines.md)).
  **rf2-m285a — WIRED END-TO-END.** A history pseudo-state renders as a
  small symbolic node inside the owning compound at the same child level
  (shallow `H` / deep `H*`), NOT a normal state box, with direct incoming
  transitions and no default fallback edge unless the topology explicitly
  carries a `:default-target`. The pipeline:
  - **Parse** (`chart.layout/collect-nodes`) — detects `:type :history`
    and emits a single `{:history? true :deep? <bool> :default-target …}`
    node that is NEVER occupiable (not initial / final / compound,
    declares no transitions; the machine's `:state` is never `[… :hist]`).
  - **Projection** (`chart.projection/xyflow-graph`) — maps a `:history?`
    node to xyflow type `"history-marker"` and threads `:data {:deep …}`;
    it carries no `:onClick` (not an on-state-click target — never
    occupied). The renderer `chart.nodes/history-marker` paints `H` / `H*`.
  - Incoming `:target :hist` edges are preserved (a history pseudo-state
    is a legitimate transition target; at runtime it resolves to the
    compound's recorded / default leaf configuration).

> **rf2-az6e2 final-state glyph decision.** The final-state marker is the
> quiet **doubled border** (the UML/SCXML convention) — the previously-
> shipped `✓` check glyph is **dropped**. The doubled border is the
> unambiguous final-state signal; the glyph competed with the state
> title for attention against the structure-first grammar.

> **rf2-b4loj error-final terminal KIND (re-frame2 semantic clarity, NOT
> XState/Stately parity).** Spec 005 §`:final?` lets a `:final?` leaf
> declare `:error?` true — an **error terminal**. This is a re-frame2
> **extension**: a child that finishes via an `:error?` final routes the
> spawning parent's `:spawn` **`:on-error`** transition instead of
> `:on-done`. XState v5 has final-states-with-output and actor `onError`
> but **no first-class error-final flag**, and Stately therefore draws no
> such marker — so this is *not* a parity feature to look up; it is a
> distinction **the re-frame2 framework acts on** that the chart must not
> hide.
>
> The affordance keeps the structure-first grammar (border/hue, not a
> competing glyph — consistent with the dropped `✓`): an `:error?` final's
> **outer ring** is painted in the `:final-error` palette token (the
> theme's `:error` hue, light + dark). A success final's outer ring stays
> the **quiet, runtime-coupled** resting border. The **main node border**
> is unchanged either way — it continues to carry the runtime
> active/focus/sim signal — so the two compose: an *active* error-final
> renders the runtime highlight on its main border **and** the static
> error hue on its outer ring, neither clobbering the other.
>
> The terminal KIND threads `:final?` leaf → `:error?` (gated on `:final?`)
> in `chart.layout/collect-nodes` → `:data {:errorFinal …}` in
> `chart.projection` → the conditional ring colour in `chart.nodes/`
> `state-node`. **Scope:** `:output-key` (the all-finals output-display
> property, Spec 005:2840) is *out* of this affordance — it belongs to a
> separate all-finals output decision if it earns one.
>
> **Text emitters (mermaid / SCXML) preserve the error-final distinction.**
> The error-final KIND is **not** chart-only trivia: it is the machine
> **completion status** the framework acts on. Under EP-0011 a child that
> finishes via an `:error?` final lowers to the uniform reply envelope as
> `:status :error` and routes the spawning parent's `:spawn` **`:on-error`**,
> while a plain `:final?` child completes `:status :ok` and routes
> **`:on-done`**. Collapsing the two terminal kinds on a text round-trip
> would silently turn an error completion into a success one — EP-0011
> reply-envelope semantic drift, not a harmless visual caveat. So both
> emitters carry the distinction, each in the idiom of its format:
>
> - **SCXML** has no first-class error-terminal element, so the bit rides a
>   re-frame2-specific custom attribute — `<final … data_rf_error_final="true"/>`
>   — exactly the `data_*` carrier posture the action-name round-trip
>   (`data_rf_action`) already uses. An ordinary SCXML consumer ignores the
>   unknown `data_*` attribute (the export stays consumable), while the
>   re-frame2 import recovers `:error? true` from it. The round-trip is
>   therefore **lossless on both `:final?` and `:error?`**.
> - **Mermaid** has no error-terminal glyph, and its emitter is one-way
>   (there is no `mermaid->spec`). Rather than collapse an `:error?` final
>   into a plain success terminal, the emitter **visibly marks** it with a
>   `note right of <error-final>` naming the error-terminal completion
>   (`:status :error` / parent `:on-error`) — the same `note` idiom the
>   action-only completion and history-default annotations use. A success
>   `:final?` terminal carries no such note.
>
> The chart's error-hue outer ring (above) is the *canvas* counterpart of
> the same distinction; the SCXML attribute and the Mermaid note are its
> text-surface counterparts. All three keep the re-frame2 completion-status
> routing visible rather than letting the portable formats erase it.

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
| **Hierarchy** | ✅ Compound parents render as a solid-bordered container with a header-strip title; children nest via xyflow `parentId` + `extent:"parent"` (rf2-xh1lm — v12 reads `parentId`, NOT the pre-v12 `parentNode`); elk lays children inside the parent box (`INCLUDE_CHILDREN`) and grows the box to ENCLOSE them. Nesting recurses (compound-in-compound, compound-in-region). **Full enclosure (rf2-44v8lq):** the painted `compound-node` box fills the xyflow node box ELK sized (`width/height:100%`) and carries NO CSS `min-width`/`min-height`. The compound size floor lives ONLY in the ELK input (`projection/elk-child` seeds every compound at `compound-node-min-{width,height}`; ELK then grows the box to enclose its laid-out children), so the box delivered via `:style {:width :height}` is authoritative. Previously a CSS `min-{width,height}` 260×150 on the painted div re-inflated it past the ELK box whenever ELK legitimately shrank a deeply-nested compound below the seed to HUG its narrow children (hvac `running`/`conditioning`, media `playing`) — so the painted border overflowed its box and visually escaped the parent (the children, positioned in the real box, stayed inside). Dropping the `min-*` makes the border track the box exactly, matching the parallel-region node (which never carried a `min-*`, hence regions always enclosed) and Stately's full nesting. | `nodes.cljs` `compound-node` (no CSS `min-*`; rf2-44v8lq); `projection.cljc` `->elk-children` (`:parent-id` grouping) + `elk-child` (compound seed floor), `xyflow-graph` (`parentId`/`extent` + container `:style {:width :height}`); `layout.cljc` `project-flat` (`:parent-id` on nested nodes). |
| **Parallel regions — structure** | ✅ **Every** region renders as a synthetic `:region?` compound node with a **distinct dashed boundary per region** (palette rotation) + `∥` glyph + region label; states carry `:region` + `:parent-id`; edges stay region-local. | `layout.cljc` `project-parallel`/`region-node-id`; `projection.cljc` (`type "parallel-region"`); `nodes/parallel_region_node.cljs`. |
| **Parallel regions — active highlight** | ✅ **Closed (rf2-yoe6e / rf2-g2svr).** `highlight-ids` resolves the whole snapshot `:state` — flat keyword, compound path, **or region-map** `{region path}` — to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so a parallel snapshot's **N simultaneously-active leaves** all light up at once. A nested region value resolves to its deepest leaf. | `layout.cljc` `highlight-ids`; `projection.cljc` `xyflow-graph` (`:highlight-ids` set). |
| **Parallel regions — active CONTAINER chrome** | ✅ **Closed (rf2-80rm2 / G4).** The region (and, for self-consistency, the compound) **container** reads as active when ANY descendant leaf is active — the projector folds the container into `:active` by walking each active leaf UP its `:parent-id` chain (reusing the G1 active set; no duplicate highlight logic). `parallel-region-node` / `compound-node` then paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so an active region reads as active at the zone level, not just the leaf inside it. **Palette delegated to Figma.** | `projection.cljc` `xyflow-graph` (`active-container-ids` parent-id walk); `nodes/parallel_region_node.cljs`; `nodes.cljs` `compound-node`. |
| **Transition scoping** | ✅ `:on`, `:after`, `:always`, machine-level (top-level `:on`) fallback, **`:on-done` (XState `onDone`) compound / parallel completion** (rf2-41goo), **parallel-ROOT `:on` (rf2-3v3gv1 / rf2-656ivk) + parallel-ROOT `:after` (rf2-m3otj2) ancestor fallbacks** (region-qualified targets; surfaced across chart + Mermaid + SCXML incl. multi-region + action-only forms), external + internal self-transitions, vector-of-candidates forks. **Machine-level fallback projects ONCE** (rf2-vcnvj): a top-level `:on` is sourced from a synthetic MACHINE-ROOT node into its target as a SINGLE chip — not one back-edge per leaf (the pre-vcnvj per-state expansion repeated the chip AND injected N back-edges that scrambled ELK's top-to-bottom ranking). **`:on-done` (rf2-41goo)**: a COMPOUND's `:on-done` projects a completion edge from the compound to its SIBLING target (resolved at the compound's own level), an `✓ done` chip distinct from an ordinary event arrow; a PARALLEL-ROOT's `:on-done` (action/fx-only — registration rejects a `:target`) renders as a TERMINAL completion affordance (a hanging done chip, no sibling segment). Self-loops render as a small loop (not a degenerate bezier); internal self-transitions render dashed + no arrowhead. | `layout.cljc` `collect-state-edges`, `on-done-edges`, `collect-machine-edges` (root-sourced), `machine-root-id`, `resolve-target-path`; `projection.cljc` (`machine-root` node type, `:onDone` / `:doneState` event-node data); `nodes.cljs` `machine-root-node`; `edges.cljs` (self-loop path, internal dash). |
| **Initial** | ✅ Fixed-glyph `initial-marker` node, at **every** compound level (xstate/SCXML semantics). **G-START / rf2-i9d2ob — the WHOLE initial glyph (filled dot + single quadratic `Q`-hook + a small filled triangle arrowhead) is one FIXED node-local shape drawn by `nodes.cljs/initial-marker`, anchored to the target state and INDEPENDENT of ELK / xyflow edge routing** — matching Stately's `InitialEdgeViz` (a fixed glyph, never routed). **rf2-d5s7yg — a SHORT hook ending just OUTSIDE the state's edge (the Stately signature), NOT a long arm landing ON it.** The marker node sits a SMALL fixed offset left of its state (`projection/initial-marker-x-offset` = 26, rf2-k7kiiq) so the filled dot's CENTRE lands `(offset − dot-x)` px outside the state's left edge — `state.x − 19` at regular density (offset 26, `dot-x = pseudo-radius + 1 = 7`); the arrowhead tip is **decoupled** from that offset — drawn at node-local x = `(offset − initial-marker-tip-gap)` so the ABSOLUTE tip lands at `state.x − tip-gap` (≈ 8px), a **clean visible GAP outside the edge pointing AT it, never penetrating the interior**. The dot sits one `initial-glyph-hook-drop` above the arrow row so the single `Q` hooks DOWN into the tip (the xstate-viz recipe: control point at `(dot.x, arrow.y)`). **rf2-wwyx1u — SMALL Stately-sized arrowhead + FORWARD-flowing hook.** The glyph geometry is single-sourced from the pure `projection/initial-marker-glyph` (dot / hook / arrowhead local-frame coords), so the renderer and the regression test agree. The arrowhead side is a **small** `≈ pseudo-radius / 2` clamped to a **4–6px** band (rf2-k7kiiq; Stately's marker is a fixed ~5×6), NOT the prior oversized `(max 6 :arrow-width-entry)` ~10–13px head: that head, on the shortened glyph, pushed the hook base `end-x = tip-x − ah` LEFT of `dot-x`, so the `Q`-hook ran BACKWARDS (dot down-LEFT) and the triangle dominated the short glyph. With the small head + the offset bumped 20→22 and tip-gap 8→7, `end-x > dot-x` holds in every density — the hook flows FORWARD (dot → short down-and-RIGHT curve → small arrow pointing RIGHT into the state's left edge), reading as ONE clean unit. **rf2-k7kiiq — glyph-dimension tuning (Stately-aligned), DECOUPLED from `pseudo-radius`:** the three glyph dimensions used to be coupled through `pseudo-radius` (a smaller dot and a bigger arrowhead cannot both come from one constant), so the tuning splits them — (1) the painted FILLED dot draws at `:r = pseudo-radius − 0.5` (−1px diameter, a tighter dot) WITHOUT changing the `pseudo-radius` the glyph geometry reads (arm/arrowhead stay on the full radius); (2) the arm is LENGTHENED by bumping `initial-marker-x-offset` 22→26 (raising `tip-x`, hence `end-x − dot-x`); (3) the arrowhead is slightly LARGER by raising the `ah` clamp ceiling 5→6. (2) and (3) are interdependent — a bigger `ah` lowers `end-x = tip-x − ah`, so the offset bump is REQUIRED to keep `end-x > dot-x` and the arrowhead clear of the dot in every density. **Nested-initial parity (rf2-d5s7yg):** the marker keeps `:parentId` for the container coordinate frame but carries **NO `extent:"parent"`** — the clamp would shove a nested marker sitting just outside its container's left padding back INSIDE the state (the root cause of the `red`/`walk` overshoot that top-level `door` did not show, since only nested markers have a parent to clamp against). With the clamp gone, top-level and nested initials land the SAME clean gap outside the edge. The earlier geometry (rf2-i9d2ob) used `initial-glyph-arm == offset == 48` so the tip landed flush ON the edge with a too-long arm — visibly overshooting INTO nested states. **Container LEFT-padding reserves the marker's leftward extent (rf2-lxk3h3).** The marker is an xyflow-only decorative node — it is NOT in the ELK graph — so ELK's `INCLUDE_CHILDREN` pass never grows the container box to enclose it. Without a reservation, a nested initial substate's glyph (positioned `initial-marker-x-offset` ≈ 26px LEFT of the state) spills PAST the container's left border (hvac `running`/`conditioning` — the dot + hook sat on/outside the box edge, since the plain `:container-body-pad` ≈ 14px inset was narrower than the glyph's leftward reach). The fix reserves `initial-marker-left-extent` (= `initial-marker-x-offset`, the glyph's leftward extent measured from the state's near edge — the reservation anchors to the dot's **geometry-radius** left edge at node-local x=1 (`dot-x − pseudo-radius`), the conservative wider anchor, NOT the slightly-narrower **painted** left edge at x=1.5 (`dot-x − (pseudo-radius − 0.5)`); since the painted ink only reaches x=1.5 it always lands inside the reservation) on the LEFT of **any container whose own initial substate carries a marker** — i.e. `LEFT = (max :container-body-pad initial-marker-left-extent)`. `projection/->elk-children` derives the holding-container set from the `:initial?` states' `:parent-id` (every compound / region has one; the synthetic ROOT-CONTAINER frame holds the machine's top-level initial, so it is reserved too) and threads the LEFT-widened `container-elk-padding` per-container; top/bottom/right and the plain (no-initial) containers are untouched. The reservation only ever GROWS the inset — a density whose body-pad already exceeds the glyph reach keeps it. This whole fixed-glyph approach REPLACES the pre-rf2-i9d2ob `transition` entry edge whose `getBezierPath` curve bent wonky at odd positions. The whole glyph paints the neutral `:pseudo-marker` hue (dot fill + hook stroke + triangle fill). The companion entry edge is still emitted in the projection ONLY for the every-edge `:data` invariants; `edges.cljs/transition-edge` short-circuits it (`entry?` → empty fragment) so it paints nothing. | `nodes.cljs` `initial-marker` (the fixed dot + `Q`-hook + triangle glyph; dot painted at `pseudo-radius − 0.5`; small arrowhead + forward hook off `projection/initial-marker-glyph`); `projection.cljc` (`initial-marker-x-offset` 26 / `initial-marker-tip-gap` 7 / `-y-offset`; `initial-marker-left-extent` = `initial-marker-x-offset`, the container LEFT-padding reservation rf2-lxk3h3; `initial-marker-glyph` pure geometry — small ah clamped 4–6, `end-x > dot-x`; `container-elk-padding` `reserve-initial-marker?` arm + `->elk-children` per-container `marker-container-ids`; marker-nodes carry `:parentId` but NO `:extent` + retained `:entry` edges); `edges.cljs` `transition-edge` (`entry?` → renders nothing); `layout.cljc` `collect-nodes` (per-level `:initial?`). |
| **Final** | ✅ Quiet doubled border (outer ring 1px proud) on `state-node`. **No glyph** — the prior `✓` check glyph is DROPPED (rf2-az6e2, §1.4); the doubled border is the unambiguous final-state signal. **Error-terminal KIND (rf2-b4loj, §1.4 — re-frame2 clarity, NOT XState/Stately parity):** an `:error?` final (Spec 005 §`:final?`, the re-frame2 extension that routes the spawning parent's `:spawn :on-error`) paints its outer ring in the `:final-error` token (theme `:error` hue) while a success final keeps the quiet runtime-coupled ring; the main border stays runtime-driven so an active error-final composes both signals. `:output-key` is out of scope. | `nodes.cljs` `state-node` (conditional error-ring colour); `layout.cljc` `collect-nodes` (`:error?` gated on `:final?`); `projection.cljc` (`:errorFinal` on `:data`); `theme/tokens.cljc` (`:final-error`). |
| **History** | ✅ **WIRED END-TO-END (rf2-m285a, §1.4).** First-class `:type :history` pseudo-states (Spec 005 §History states) are parsed, projected, and painted. `chart.layout/collect-nodes` detects a `:type :history` node and emits a single `{:history? true :deep? <bool> :default-target …}` marker (NEVER occupiable); `chart.projection/xyflow-graph` maps it to the xyflow `"history-marker"` type and threads `:deep`; the `history-marker` renderer paints shallow `H` / deep `H*` inside the owning compound (a small symbolic node, NOT a normal state box). | `layout.cljc` `collect-nodes`/`history-node?` (`:history?`/`:deep?` emit, :360-375); `projection.cljc` `xyflow-graph` (`"history-marker"` map :731, `:deep` thread :785); `nodes.cljs` `history-marker` (paints `H`/`H*`, :696). |
| **Event labels** | ✅ `event [guard] / action` composed label; `after(<ms>)`, `always`, `* (any)` wildcard segments; opaque chart-bg backplate (rf2-j10sm Phase 1) for legibility against overlapping ink; label is clickable when a fireable event-id + host callback are present. **Multi-event collapse SUPERSEDED + RETIRED** (rf2-j10sm Phase 2 → rf2-qo5xy → rf2-o6vh7): the old collapse rendered N transitions on one `[source target]` pair as ONE arrow + N stacked labels (`:siblingIndex` / `:siblingCount`). Under events-as-nodes (rf2-qo5xy) each event is its OWN event-node, so same-`[source target]` transitions stay DISTINCT event-nodes (no collapse); rf2-o6vh7 removed the dead `:siblingIndex` / `:siblingCount` + `data-sibling-*` machinery. (The rf2-r7vsr post-render collision-avoidance overlay was **retired** in rf2-0xbgx: events-as-nodes moved labels onto event-nodes and left the in/out edges label-less, so the edge-label sweep was inert; elk layout + events-as-nodes makes label-on-node-body collisions a non-issue.) | `layout.cljc` `edge-label`/`event-segment`; `edges.cljs` `transition-edge`; `projection.cljc` `xyflow-graph` (one event-node per parsed transition). |
| **Guards / actions** | ✅ Guard in `[...]`, action after `/`; entry/exit state actions render as `entry / <name>` / `exit / <name>` rows under the label; state-tag pills above. | `layout.cljc` `edge-label`, `name-of`; `nodes.cljs` `state-node` (entry/exit rows, tag pills). |
| **Layout** | ✅ elk Layered, `DOWN`/`RIGHT` direction, `INCLUDE_CHILDREN` when nested (G5, rf2-gpa9k), per-container padding for header strips **and the nested initial-marker's leftward extent (rf2-lxk3h3 — see §Initial; the marker is an xyflow-only glyph outside the ELK graph, so its enclosure is a LEFT-padding reservation, not an `INCLUDE_CHILDREN` grow)**; async + cached pass; xyflow `fitView`. **Initial-state placement soft preference** (rf2-ly51l): `elk.layered.cycleBreaking.strategy DEPTH_FIRST` breaks a cyclic statechart's loops by a depth-first walk **from the sources** (the initial state) rather than GREEDY min-reversed-count, so the forward spine starting at the initial state ranks near the top (`:tb`) / left (`:lr`) — fixing the door's `open`-on-top / `locked`-third mis-rank. The initial state also **leads its container's model order** (`order-state-children`; machine-root annotation sinks last), biasing DEPTH_FIRST's source selection + the within-layer tiebreak toward it. **Soft, not invariant**: only the cycle-reversal SET changes; full layer-sweep crossing-min + node-placement still run, so a state can land off the initial-on-top ideal when crossings demand. Acyclic graphs are layout-identical to GREEDY. **rf2-k504af — flow-start anchoring for parallel / pure-cyclic regions:** the soft DEPTH_FIRST + model-order preference SLIPS for a PURE cycle with no forward spine (traffic's `red → green → amber → red` vehicle region, `walk → dont-walk → walk` pedestrian region, hvac's `idle`/`heating`), letting the cycle break at the wrong edge so the initial sank to the BOTTOM layer. The xstate-viz levers anchor it reliably: **root `elk.layered.considerModelOrder NODES_AND_EDGES`** (model order is honoured through layering, not just within-layer) PLUS **per-edge `elk.layered.priority.direction 1` on the INITIAL state's outgoing `__in` edge** (`projection/->elk-edge`, set from the `:initial?` node-id set; every other edge stays unset / 0). Together they pull the initial to the START of its region's flow. **General, not traffic-specific:** an already-spine-ordered machine (door / brew / session / gate / quiz / modal) already ranks its initial first, so the model order agrees with the layout and NOTHING moves — verified across the 9-machine × 2-theme corpus (traffic `red`/`walk` + hvac `idle`/`heating` move to flow-start; door/brew/session/gate/quiz/modal/media unchanged; no new overlaps). **G-SHAPE compaction:** `elk.layered.spacing.nodeNodeBetweenLayers` is `50` (was `70`) so a flat cyclic statechart reads as a tighter vertical column closer to the Stately reference (door / gate visibly less stretched); the value stays comfortably above the ORTHOGONAL route-channel reserve (`edgeNodeBetweenLayers 24`) and only shrinks inter-layer whitespace (no node re-ranking), so nested / parallel / acyclic machines are visually unchanged. **G-ROUTE — ✅ CLOSED, OPT-IN (rf2-gnrkke, §4.3.1):** a cyclic statechart's back-edges (door's `alarming → reset → locked`, gate's `* → reset → idle`, brew's `ready → start → brewing`) sank their synthetic event-node to the DEEPEST layer; the **post-ELK reroute** (`chart.post-elk/reroute-back-edges`) detects the sunk event-node GEOMETRICALLY (no GREEDY spine inversion) and reroutes it as a compact mid-height side-detour. **G-ASPECT — ✅ CLOSED, OPT-IN (rf2-lamdfl, §4.3.2):** `chart.post-elk/aspect-direction` chooses the ELK direction PER MACHINE (branchy ⇒ `:lr`, chain ⇒ `:tb`) and `chart.post-elk/transpose-parallel-regions` re-stacks parallel regions vertically with horizontal intra-region flow WITHOUT disturbing the G5 `INCLUDE_CHILDREN` routing. **Both are OPT-IN behind `:direction :auto`** (`chart.post-elk/adaptive?`): the default `:tb` (and explicit `:tb`/`:lr`) skip the heuristic AND `apply-post-elk`, so a non-opted machine is byte-identical to main (the reverted #3453 auto-defaulted these and regressed the corpus; this redo gates them). | `chart.cljs` `compute-layout!`/`default-elk-options` (`cycleBreaking.strategy DEPTH_FIRST` + `considerModelOrder NODES_AND_EDGES` + `nodeNodeBetweenLayers 50`)/`elk-layout-options` (root `layoutOptions` + cross-hierarchy switch) + the opt-in `post-elk/adaptive?` gate → `post-elk/resolve-direction` (adaptive direction) + `post-elk/apply-post-elk` (region transpose + back-edge reroute) in the settle callback; `projection.cljc` `order-state-children` + `->elk-children` (initial-leads model order + per-container `layoutOptions`) + `->elk-edge`/`->elk-edges` (`elk.layered.priority.direction` on an `:initial?` state's `__in` edge, `initial-edge-priority-direction`); `post_elk.cljc` (`adaptive?` / `aspect-direction` / `max-out-degree` / `transpose-parallel-regions` / `reroute-back-edges`). |
| **Edge routing through nesting** | ✅ **Closed (rf2-cz8v6; key-scheme fixed rf2-r636q).** elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into an `{elk-edge-id [{:x :y} …]}` map keyed by the `<spec-edge-id>__in` / `<spec-edge-id>__out` ids (the two segments the events-as-nodes split mints, rf2-qo5xy), and the projector attaches each segment's route to the matching xyflow edge's `:data {:points}` — `__in` to the inbound edge, `__out` to the outbound edge. `transition-edge` then draws a smooth poly-path THROUGH the bends, so a deeply-nested transition routes **around** a container instead of cutting across it. Self-loops keep their dedicated loop path; an edge with no elk route falls back to the bezier. *(rf2-r636q: the consumer formerly looked up the bare `<spec-edge-id>` the producer never emits → the feature was silently dead until the producer/consumer key scheme was reconciled.)* | `chart.cljs` `default-elk-options` (`ORTHOGONAL` + `edgeCoords ROOT`) / `elk-edge-points` / `elk-result->positions`; `projection.cljc` `xyflow-graph` (`:edge-points` `__in`/`__out` → `:data {:points}`); `edges.cljs` `edge-path` (poly-path). |
| **Fired-this-epoch edge highlight** | ✅ **Closed (rf2-8jzm1 + rf2-qeemm / G3).** The Xray inspector resolves the focused epoch's traversed edges via `extract-fired-edge-ids` (CANONICAL machines-viz edge-ids, B7) and threads them as `:fired-edge-ids` (set) into `MachineChart`; the projector marks each matching edge `:fired` and `transition-edge` paints the FIRED treatment (emphasised + animated stroke + `data-fired`) along the routed path — coexisting with G2's bend-points + G1's active styling. Matches the EDGE directly (not the from/to ENDPOINT lens), so every microstep / guard-fork arm lights up. **Colour delegated to Figma** (`:accent` baseline, distinct from the focused/active `:info`). | `projection.cljc` `xyflow-graph` (`:fired-edge-ids` → `:data {:fired}`); `chart.cljs` (`:fired-edge-ids` prop); `edges.cljs` `transition-edge` (FIRED stroke + `data-fired`); Xray `trace_state/extract-fired-edge-ids` + `machine_inspector` / `machine_canvas` wiring. |
| **Simulation** | ✅ (host-side, trace-driven + hermetic sim) — live highlight off the runtime-db slot `[:rf.runtime/machines :snapshots <id>]` + the Spec 009 bus; the Static-Machines Sim sub-mode is a hermetic what-if walker. Edge labels carry `:eventId` + `:onClick` so a host wires "click to send". | `projection.cljc` (`:on-edge-click`/`:eventId`); Xray `static/machines/sim.cljs` (per 003). |
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
convergence loop. Self-loops are dissolved by events-as-nodes
(a spec self-transition is `state → event-node → state`, two ordinary
ELK-routed edges), so there is no renderer-side self-loop path at all
(rf2-hstzzj pruned the dead fan geometry).

> **rf2-6v4ci5 — measure-read accessor fix (implementation defect, not a
> behavioural change).** The d9ro2 two-pass relies on reading xyflow's
> DOM-measured box back per node (`read-measured-dims`). In xyflow v12 that
> box is merged onto the **internal** node (`instance.getInternalNode(id)
> .measured`), NOT onto the user-facing `instance.getNodes()` objects — and
> this non-interactive chart deliberately never syncs dimension changes back
> into its controlled `:nodes` array, so `getNodes()` always reported
> `measured = nil`. The read therefore returned an empty map, the relayout
> gate never reached `ready?`, and the *second* pass **never fired**: every
> node — leaf states AND event-nodes — laid out at its ELK floor, so a
> guard- or action-widened event chip (which renders at its true content
> width, e.g. `door/close IF may-close?` ≈ 148px vs the 96px event floor)
> overran its floor-spaced same-layer neighbour (`door/hold`). Reading via
> `getInternalNode` feeds ELK each node's real box, so the documented
> two-pass behaviour (above) actually takes effect and guarded/long event
> chips get correctly-spaced slots. General across the corpus — door, gate,
> hvac all had floor-spacing overlaps that this closes.

## §3 — Gap analysis + deliberate divergences

### 3.1 Gaps to close for parity

Each gap: what's missing, severity, the closing bead (existing or
new), and the parity-bar row it serves.

| # | Gap | Severity | Serves bar | Bead |
|---|---|---|---|---|
| **G1** | ✅ **CLOSED (rf2-yoe6e / rf2-g2svr).** Was: a parallel snapshot's `:state` is a region-map `{region path}` with **N active leaves**; `highlight-id` returned nil for a map, so the chart highlighted **none** (or only a degenerate single id). Now: `highlight-ids` resolves the whole `:state` (flat / compound / region-map, nested values → deepest leaf) to the **set** of active-leaf node-ids; `xyflow-graph` threads `:highlight-ids` and marks **every** active leaf, so all N regions light up at once — Stately's §1.2 read. | **High** | §1.2, §1.9 | **contract:** rf2-yoe6e ✅ · **impl:** rf2-g2svr ✅ |
| **G2** | ✅ **CLOSED (rf2-cz8v6) → HARDENED (rf2-rlq97).** Was: edges were beziers between handles; elk's Layered bend-points were discarded (§1.7), so in deep nesting an edge could cut **across** a region/compound container instead of routing **around** it. rf2-cz8v6: elk runs `ORTHOGONAL` routing with `elk.json.edgeCoords ROOT`; `compute-layout!` lifts each edge's `sections` bend-points (absolute coords) into the projection (`:edge-points` → `:data {:points}`), and `edges.cljs/edge-path` draws a smooth poly-path THROUGH them — routing around non-incident containers (the [`000-Vision.md`](000-Vision.md) §Quality-bar "no edge-crossing collapse" floor). rf2-rlq97 (d9ro2 follow-on): the edges are fed into the ELK graph through the pure `chart.projection/->elk-edges`, and `default-elk-options` gained edge-to-node / edge-to-edge **clearance** (`elk.spacing.edgeNode`, `elk.layered.spacing.edgeNodeBetweenLayers`, `elk.spacing.edgeEdge`) so routes keep a gap from node boxes (kills the "arrows over states" residue) **plus** label-placement keys (`elk.edgeLabels.placement CENTER`, `elk.spacing.edgeLabel`) so ELK *places* any labelled edge's text in a reserved channel (`:edge-labels` → `:data {:labelPos}`), the renderer painting ELK's position instead of the midpoint heuristic. Self-loops are dissolved by events-as-nodes (`state → event-node → state`), so there is no renderer-side loop path (rf2-hstzzj pruned the dead fan geometry); no-route edges fall back to the bezier; G1's active-edge highlight survives. | **Medium** | §1.7, §1.9 | **impl:** rf2-cz8v6 ✅ · **harden:** rf2-rlq97 ✅ |
| **G3** | ✅ **CLOSED (rf2-8jzm1 + rf2-qeemm).** Was: to highlight "the edge that fired this epoch" on the live chart, the host's trace→edge-id mapping had to mint the **same** `edge-id` `chart.layout` mints, and the ids weren't wired through to the canvas. Now: `extract-fired-edge-ids` (rf2-8jzm1) projects the definition through the public `project-definition` and reads ids off the projected edges — they agree with the live chart **by construction**; rf2-qeemm threads them as `:fired-edge-ids` (set) into `MachineChart`, the projector marks each matching edge `:fired`, and `transition-edge` paints the FIRED treatment (emphasised + animated stroke + `data-fired`) on the live canvas. Matches the EDGE directly so every traversed arm (microsteps, guard-fork candidates) lights up. **Palette delegated to Figma.** | **Medium** | §1.3, §1.8 | **helpers:** rf2-8jzm1 ✅ · **wire:** rf2-qeemm ✅ |
| **G4** | ✅ **CLOSED (rf2-80rm2).** Was: once G1 lit N region LEAVES, the **region CONTAINER** still read structural-only — an active region was indistinguishable from an inactive one at the zone level. Now: `xyflow-graph` folds a container (region OR compound) into `:active` when any descendant leaf is active — walked UP the `:parent-id` chain every node already carries (reuses the G1 active set; no path-prefix reimplementation). `parallel-region-node` / `compound-node` paint active chrome (solid emphasised boundary + the `:info` active-token glow ring, the same affordance state nodes use), so each active region reads as active at a glance and the N active regions read as a set. **Palette delegated to Figma.** | **Low–Medium** | §1.2 | **impl:** rf2-80rm2 ✅ |
| **G5** | ✅ **CLOSED (rf2-gpa9k).** Was: elk routes cross-hierarchy edges only when the switch is activated on the top level (§1.7), and while `->elk-input` already set it, **nothing asserted it** — drop the line and no test would catch the regression. Now: the cross-hierarchy switch is `elk.hierarchyHandling INCLUDE_CHILDREN` on the root `layoutOptions`, set by the pure `chart.cljs/elk-layout-options` whenever the graph nests (`:parallel?` OR some node has a `:parent-id` — compound substate or parallel-region leaf); it is what lets the Layered algorithm route edges ACROSS nesting levels (its default `SEPARATE_CHILDREN` lays each level out independently and never routes cross-hierarchy edges). So an edge from a deeply-nested leaf to a top-level state routes cleanly, and G2's bend-points (rf2-cz8v6) come back as legible absolute coords. The capability rode in with G2; rf2-gpa9k extracted the option-computation into the assertable `elk-layout-options` and added the regression guard (nested/parallel ⇒ switch present, flat ⇒ absent, G2 routing keys pinned). | **Low** | §1.7 | **impl:** rf2-cz8v6 ✅ (capability) · **assert:** rf2-gpa9k ✅ |
| **G6** | ✅ **CLOSED (rf2-shv82).** Was: any edge whose source or target was a compound (a parent-level transition like `:active → :disconnected`, a compound self-loop, an inbound `:failed → :active`) was SILENTLY DROPPED from the DOM. The projector emitted it, ELK routed it, but xyflow's `getHandleBounds` returned null for the compound (no `<Handle>` children) → `isNodeInitialized` returned false → `getEdgePosition` returned null → the edge never reached the DOM. No warning. The 5-layer probe trace in the bead proved 4 such edges survived to ELK's output then 0 in the DOM. Now: `compound-node` + `parallel-region-node` render invisible source + target `<Handle>` elements on all four sides; xyflow accepts the compound as an edge endpoint and ELK's routed bend-points anchor on its BORDER the way xstate/Stately Studio paints parent-level transitions. The chart root surfaces `data-edge-count-projected` alongside `data-edge-count` so the parser → projector → DOM parity is regression-guarded end to end. | **High** | §1.3, §1.7 | **impl:** rf2-shv82 ✅ |
| **G7** | ✅ **CLOSED (rf2-shv82) → SUPERSEDED by events-as-nodes (rf2-qo5xy), collapse RETIRED (rf2-o6vh7).** Was: N self-loops on the same node (e.g. testdeck `:disconnected` carries 3: `:ws/arm-fail`, `:ws/disarm-fail`, `:ws/clear`) all rendered at the same loop anchor → garbled glyph soup. rf2-shv82 shipped a perimeter fan (8 slots, rotated per `:loopIndex`); rf2-j10sm Phase 2 then collapsed same-`[source target]` events into ONE arc + N stacked labels via `:siblingIndex` / `:siblingCount`. **The rf2-qo5xy events-as-nodes paradigm supersedes both**: each self-event is its own `rf2-event` node (`state → event-node → state`), so N self-loops are N DISTINCT event-nodes — no fan, no collapse, no garbled overlap. rf2-o6vh7 RETIRED the dead collapse machinery (`:siblingIndex` / `:siblingCount` and `data-sibling-*` are gone); rf2-hstzzj then pruned the unreachable fan geometry too (`self-loop-geometry`, the `:selfLoop` / `:loopIndex` keys, and the `edge-path` self-loop branch). | **Medium** | §1.3, §1.9 | **fan:** rf2-shv82 ✅ (pruned rf2-hstzzj) · **collapse:** rf2-j10sm (retired rf2-o6vh7) |
| **G8** | ✅ **CLOSED (rf2-shv82).** Was: a cross-hierarchy edge (source and target in different parent containers — e.g. testdeck `:active.authenticating → :failed`) routed via ELK's bend-points had a midpoint that landed far from its visual origin (the label sat at the canvas bottom-left). Now: the projector flags the edge `:crossHierarchy true` when the source's `:parent-id` ≠ the target's (self-loops are never cross-hierarchy regardless of nesting); `chart.edges/edge-path` anchors the label NEAR the source-side first bend point (xstate/Stately convention — the label hugs the bend just outside the container the edge exits, with a small back-bias along the incoming segment so it sits in the routed channel). Degenerate two-point routes fall back to the segment midpoint; the bezier-fallback path is unchanged. Surfaces `data-cross-hierarchy` on each transition edge label. | **Medium** | §1.5, §1.9 | **impl:** rf2-shv82 ✅ |
| **G9** | ✅ **CLOSED (rf2-41goo).** Was: Spec 005's first-class `:on-done` (XState `onDone` — the COMPOUND / PARALLEL completion transition that advances the outer flow when a sub-flow reaches its `:final?` child; SCXML §3.7 `done.state.<id>`) was **NEVER projected** — zero matches for `on-done` / `onDone` / `done.state` across `chart/layout.cljc`, `mermaid.cljc`, AND `scxml.cljc`. So the chart drew the `:final?` leaf but NOT the "then advance to sibling" arrow that actually fires; the SCXML round-trip was silently lossy for `onDone`; the "COMPLETE" headline overstated parity (§1.3's onDone read was unmet). Now: `layout.cljc/on-done-edges` parses a node's `:on-done`, `collect-state-edges` emits a COMPOUND's completion edge to its SIBLING (resolved at the compound's own level — XState onDone placement) flagged `:on-done?` + carrying the `:done-path`; `project-parallel` emits the PARALLEL-ROOT's `:on-done` (action/fx-only — registration rejects a `:target`) as a TERMINAL completion affordance (self-anchored, `:internal?`) on a synthetic parallel-root node. `projection.cljc` buckets it as the `:on-done` event-variant and threads `:onDone` + the `done.state.<id>` `:doneState` label onto the event-node; the engine-raised `:rf.machine/done` is NOT click-to-send. `mermaid.cljc` renders the compound sibling edge (`✓ done`) + a parallel-root completion `note`; `scxml.cljc` emits + round-trips the W3C `<transition event="done.state.<id>">` (compound to sibling; parallel inside `<parallel>`). | **Medium** | §1.3, §1.5 | **impl:** rf2-41goo ✅ |

### 3.2 Deliberate non-parity divergences (intentional, NOT gaps)

Flagged per the skill convention — these are **principled choices**,
already recorded in [`000-Vision.md`](000-Vision.md) §Deliberate
divergences; restated here so the parity audit does not mis-file them
as gaps.

| Divergence | Stately does | re-frame2 does | Why |
|---|---|---|---|
| **Read-only inspector — no code↔diagram sync** | Bidirectional editor (edit diagram → regen code) | Read-only projection of an already-registered machine; no canvas authoring | [`000-Vision.md`](000-Vision.md) §What it isn't → "Not an editor"; Lock #1 component-not-product. |
| **Trace-driven sim, not a sandbox interpreter** | In-editor sim + `@xstate/inspect` WebSocket bridge | Spec 009 trace bus + hermetic what-if Sim, **in-process** | [`000-Vision.md`](000-Vision.md) §Active-state highlighting transport — the bus already carries every needed event at near-zero cost; no separate-window UX. |
| **History editing UX** (rendering is at parity — see §1.4) | History state node + full **editor** history-EDITING UX (add/configure history nodes on the canvas) | **Renders** first-class `:type :history` pseudo-states (shallow `H` / deep `H*`) — wired end-to-end (rf2-m285a, §1.4); parsed, projected, painted. Does NOT implement Stately's canvas history-EDITING UX — the read-only inspector never authors topology. | The residual is the **editing** UX, not the rendering: per the read-only-inspector divergence (Lock #1, component-not-product), no history node is *authored* on the canvas, but every registered history pseudo-state is *rendered* at parity. |
| **Guard label form** | Numbered `if / else if / else` + condition, joined by a dotted evaluation-order connector | `IF <guard>` predicate annotation per branch **+ a numbered priority badge** (①②③) on each branch of a genuine guarded multi-branch fork (rf2-uw3vmi — see §4.2 *Guarded-fork branch badge*) (+ Sim **lists** failed-guard transitions greyed rather than hiding them) | re-frame2 reads guard as a **predicate annotation** on one edge, not an ordered if-chain — that WORDING divergence is settled. The numbered badge is the ADDITIVE Stately affordance re-frame2 adopts: it surfaces the deterministic first-pass-wins evaluation order (the candidate-vector index) Stately communicates with its numbered `if/else-if` chain, without changing the per-branch `IF`-wording. The Sim's "list, don't hide" is a teaching stance (003 §Failed-guard handling). |
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
  `:highlight-ids` (set); a node is `:active` when its id ∈ the set. A
  flat / compound snapshot resolves (via `highlight-ids`) to a singleton
  set, so `:highlight-ids` is the single active-state option (rf2-hstzzj
  removed the legacy scalar `:highlight-id` convenience).
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
| **Final state (success)** | **quiet doubled** border (outer ring proud of corner). **No glyph** (rf2-az6e2 dropped the ✓) | *outer ring in the resting/runtime border colour* |
| **Final state (error — `:error?`)** | doubled border whose **outer ring is the error hue** (rf2-b4loj — re-frame2 clarity, the terminal routes the parent's `:on-error`; NOT XState/Stately parity). Main border stays runtime-driven so an active error-final composes both | *outer ring in `:final-error` (theme `:error`); main border unchanged* |
| **Initial marker** | small **neutral** filled dot **+ a clearly-visible short hooked arrow** whose head **points AT the initial state's near edge and stops just OUTSIDE it with a small visible gap** (the `initial-marker-tip-gap`) — it does **NOT** land flush on or penetrate the state, at every compound level (NOT accent-blue) — the SCXML/Stately initial icon (filled circle + arrow). **G-START / rf2-i9d2ob:** the WHOLE glyph (dot + `Q`-hook + triangle arrowhead) is ONE fixed node-local shape anchored to the state, never ELK-routed, so it reads identically in EVERY position (the prior bezier-routed entry edge bent wonky at odd positions); all three parts paint the `:pseudo-marker` hue | *fixed `:pseudo-marker` dot + `Q`-hook + triangle arrowhead, drawn in `initial-marker`* |
| **History pseudo-state** | small symbolic node — shallow `H` / deep `H*` — inside the owning compound; NOT a state box, never occupiable, keeps incoming `:target :hist` edges. **WIRED end-to-end (rf2-m285a):** `chart.layout` parses `:type :history` → `{:history? true :deep? …}`; `chart.projection` maps it to xyflow `"history-marker"` + `:data {:deep …}`; `chart.nodes/history-marker` paints `H` / `H*` | *`history-marker` node-type emitted by the projector; `:pseudo-*` constants carry the variant* |
| **Compound container** | **solid subtle-neutral** box + full-width title strip; NO dashed border, NO accent wash by default. **Fully encloses its descendants** — the painted box fills the ELK-sized xyflow box (`width/height:100%`) with NO CSS `min-*` floor of its own (rf2-44v8lq; the floor lives in the ELK input seed, ELK grows the box to enclose children), so a deeply-nested compound that ELK shrank to hug narrow children no longer overflows its parent | *`:container-body-bg` fill, `:container-border` solid, `:container-header-bg` strip; box from ELK `:style {:width :height}`, no rendered `min-*`* |
| **Parallel region container** | **dashed neutral** boundary + full-width region title strip + subtle `∥` glyph + uppercased label. Region identity from **containment/layout**, NOT a rotating border colour | *`:region-border` dashed (same for every region — rotation removed)* |
| **Parallel region (ACTIVE) — G1/G4** | region boundary firms to **solid** + header carries an **active affordance**; **every** active leaf inside lights **simultaneously** | ✅ shipped (rf2-80rm2 / rf2-az6e2): solid `:active` boundary + `:active-wash` header + `:glow` ring |
| **Edge (source→event — quiet half)** | **thinner** stroke + **small** arrowhead in the quiet colour | *`:edge-quiet`, ~−0.5 stroke, `:arrow-width-quiet` head (8px regular)* |
| **Edge (event→target — primary half)** | standard stroke + **small, thin** arrowhead — the pair reads as ONE route. Primary head trimmed toward Stately's small/thin heads (was 18px); stays **flush** on the target border | *`:edge-quiet` resting, `:arrow-width` head (12px regular)* |
| **Edge (active — SOURCED FROM an active state) — rf2-vd3q1i** | mid-weight stroke + arrow tinted to active hue. **Source-active only**: an edge lights iff its SOURCE state is active (the outgoing fan); an INCOMING edge whose only active endpoint is its target stays quiet (the traversed edge lights via the separate *fired* row instead) | *`:edge-active`, midweight* |
| **Edge (focused — the fired FROM→TO)** | **emphasised** stroke + a **single finite glow flash** (event-driven, settles to the static emphasis — see `Principles.md` §chart animation; rf2-4o43j8, NOT a continuous loop); lights **both** segments together | *`:edge-active` + `mv-chart-transition-glow` (one motion-scaled iteration via `tokens/glow-animation-css`)* |
| **Edge (fired THIS epoch) — G3** | matched by **edge-id** (not endpoint), **heaviest** stroke + a **single finite glow flash** (settles to the static emphasis; reduced-motion collapses it — rf2-4o43j8) + a hue distinct from focused/active; `data-fired` hook; both segments | *`:edge-fired` + `mv-chart-transition-glow` (one motion-scaled iteration via `tokens/glow-animation-css`)* |
| **Edge (self-loop)** | a small loop off the node edge (not a degenerate bezier) | shipped path |
| **Edge (internal self-transition)** | terminal **route chip** (dashed border ring) + **no outgoing target segment** | shipped (event-node, no `__out` edge) |
| **Edge (`:reenter?` external restart) — rf2-9dj21r** | a TARGETED transition is internal by DEFAULT; `:reenter? true` (Spec 005 §Self-transitions / XState v5: re-run `:exit`/`:entry`, restart `:after`/`:spawn`) is marked DISTINCTLY — a `↻` reenter chip on the event header (`data-reenter` hook), a `↻` Mermaid label marker, and SCXML `<transition type="external">`; the edge id folds `:reenter?` in so the with/without pair never collides | shipped (chart `:reenter` data + `↻` chip, mermaid `↻`, SCXML `type="external"` round-trip) |
| **Edge (`:after` timer)** | `⌚ <ms>` event chip + `data-after-ms` hook for the countdown-ring overlay | shipped |
| **Edge (`:always` eventless)** | `∞` event chip | shipped |
| **Edge (machine-level fallback)** | a SINGLE route from the MACHINE-ROOT chip into the target (rf2-vcnvj — projected once, not per-leaf); `data-machine-level` hook on the event chip; the loud "machine-level" label is **muted** by default (rf2-az6e2) | shipped flag + root-sourced route |
| **Edge (root parallel `:on` ancestor fallback — rf2-3v3gv1 / rf2-656ivk)** | a `:type :parallel` machine's OWN top-level `:on` is the **ancestor fallback** for its regions (Spec 005 §Root parallel `:on`; verified xstate@5.32.0): when no region-local transition handles the event the root `:on` fires, moving one or more **region-qualified targets** atomically. Projected as ONE route **per region-qualified target** from the synthetic MACHINE-ROOT chip into the **region-scoped** target node (`region-scoped-id`), flagged `:machine-level?` AND `:parallel-root-on?`; a **targetless action-only** root `:on` self-anchors on the chip as an `:internal?` affordance (moves no region). Pre-fix the projection modelled the per-region `:on` fallbacks but DROPPED the parallel root's own `:on` entirely — neither topology nor focused-event highlight could show it. **rf2-656ivk completes the three-emitter coverage**: SCXML now EMITS + round-trips the root `:on` as a DIRECT `<parallel>` `<transition>` (multi-region target = a SPACE-SEPARATED W3C `target` id list; targetless = no `target`) — pre-fix SCXML dropped it entirely; and mermaid renders the ACTION-ONLY root `:on` as a `note` (pre-fix the action-only form was dropped by the target `when-let`, and a map-form multi-region target `{:target [[:a :x] [:b :y]]}` was silently dropped). `chart.layout` `collect-parallel-root-edges` + `project-parallel`; `scxml` `emit-root-parallel-on` + `parse-root-parallel-transitions`; `mermaid` `collect-root-fallback-on-edges` + `root-fallback-internal-notes`. | shipped flag + root-sourced region-scoped route |
| **Edge (root parallel `:after` ancestor fallback — rf2-m3otj2)** | a `:type :parallel` root MAY declare its OWN `:after` (rf2-wox0vd) — the **timer-driven analog** of the root `:on` ancestor fallback (Spec 005 §Root-level `:after`): root-owned (scheduled at machine birth), and when it fires runs its `:action` once and atomically moves one or more **region-qualified targets** (untargeted regions stay put) — identical apply grammar to the root `:on`. Pre-fix all three emitters DROPPED it (chart collected only the root `:on`; mermaid destructured only `regions on on-done`; SCXML emit/import only `on-done`), so a valid machine-lifetime timeout was invisible across chart, Mermaid AND SCXML. Now: the chart projects ONE route per region-qualified target from the MACHINE-ROOT chip into the region-scoped node, carrying `:after <delay>` (so `event-segment` paints the `⌚ <delay>ms` glyph) + `:parallel-root-after? true` (it ALSO carries `:parallel-root-on? true` so it shares the root `:on` re-pointing path AND the fired-edge resolver arm); mermaid renders an `after(<delay>) (root fallback)` edge (action-only → a note); SCXML emits + round-trips a direct `<parallel>` `<transition event="after.<delay>" target="…"/>`. `chart.layout` `collect-parallel-root-after-edges`; `scxml` `emit-root-parallel-after`; `mermaid` `collect-root-fallback-after-edges`. | shipped flag + root-sourced region-scoped route |
| **Machine-root chip (rf2-vcnvj)** | a small NEUTRAL pill (root glyph `◆` + `root` caption) that anchors machine-level fallbacks; NOT a state box; `data-machine-root` hook | *`:container-header-bg` fill, `:state-border`, pill radius* |
| **Event chip** | subordinate route chip, **no title bar**; **capsule-pill** corner radius (≈ half the chip min-height — Stately's rounded transition/event pill, not a near-rectangle); event + guard on the first line as **`IF <guard>`**; action row only when present — a **subdued ENCLOSED action chip** (rf2-fokezq: subtle `:container-header-bg` fill + `:state-border` border + `:action-pill-*` padding/rounding, density-aware font off `:event-chip-action-px`), matching the state-node entry/exit action chip and Stately's quiet action treatment, so it reads as a **contained annotation** subordinate to the event name — NOT loose free-floating text inside the trigger box; the internal-transition chip keeps its **dashed** border; clickable (host sim) gets a button affordance + distinct border | *`:event-chip-bg` / `:event-chip-border`; `:event-chip-radius` 16px regular (≈ ½ `:event-chip-min-h` 32); action chip: `:container-header-bg` / `:state-border` / `:action-pill-*`; `:sim` border when clickable* |
| **Guarded-fork branch badge (rf2-uw3vmi)** | a small **numbered priority badge** (a circled 1-based index) LEADING the event line on **each branch of a genuine guarded multi-branch fork** — Stately's ①②③ on the gate `:gate/check` 3-way. Surfaces the deterministic first-pass-wins evaluation order (the source candidate-vector index `chart.layout` preserves). **Applies ONLY to a fork** — 2+ same-source / same-trigger candidates where at least one carries a `:guard`; a SINGLE transition (incl. a single guarded `:always`) gets NO badge, and distinct triggers on one source never merge. ADDITIVE to the settled `IF <guard>` per-branch wording (§3.2) — no ELSE-IF/ELSE text. `data-fork-order` hook on the chip + the leading badge span (`rf-mv-chart-event-fork-badge-<id>`); neutral + unobtrusive (an order annotation, not a second event), sized off the density's `:event-chip-px` | *circular badge, `:event-chip-border` fill + `:text-primary` numeral; threaded via projector `:data {:forkOrder}`* |
| **Guarded-fork evaluation-order connector (rf2-o3rkq1)** | a **dotted connector** linking the numbered fork branches **IN PRIORITY ORDER** (1→2→3) — Stately draws this between the gate `:gate/check` 3-way's branches to reinforce the first-pass-wins evaluation order the numbered badges annotate. Follow-on to the badges (rf2-uw3vmi); same fork definition (`projection/fork-groups`). For an N-branch fork the projector emits N-1 **decorative** edges, each from one branch's event-node to the next in order. **RENDER-ONLY** — appended to the xyflow `:edges` AFTER the ELK layout pass and **NEVER fed to ELK** (`->elk-edges` drives ELK off the parsed graph alone), so the connector **cannot move a single node** (verified: gate node positions identical pre/post). Quiet neutral `:pseudo-marker` hue (the badge's order-annotation posture, not a runtime edge), **no arrowhead**, **no label**, dotted (`1 3` dasharray, distinct from the internal self-transition's `4 3` dash); links branch event-nodes in their candidate-vector order. **rf2-p75kbg — the branch EVENT-NODES are pinned to lay out in PRIORITY ORDER (1,2,3) so the connector reads as a clean monotonic line, not a self-crossing bow-tie.** Without the pin, ELK's `LAYER_SWEEP` crossing-minimisation freely reorders the same-rank branches (the gate fork landed 3,1,2 left-to-right), forcing the 1→2→3 connector to cross over itself. The fix is SURGICAL: each fork-branch event-node carries an `elk.position` KVector hint `(<priority>,0)` (x = the within-layer cross-axis under the chart's DOWN direction), paired with the holding container's `crossingMinimization.semiInteractive` — which constrains ONLY the positioned (fork-branch) nodes, leaving every other node freely crossing-minimised. `projection/fork-branch-event-positions` + `fork-branch-container-ids` derive the pins from the same `fork-groups` the badges + connector use; `->elk-children` tags the branch event-children and `chart/elk-layout-options` enables semiInteractive on the ROOT when a top-level fork exists. The branch→target edges (each branch → its high/low/rejected) stay clean verticals — the pin straightens the connector without relocating crossings. Absent on a non-fork machine (the pins + semiInteractive are a no-op). **rf2-4vvywg — the connector is ANCHORED to EXPLICIT side handles**: each edge carries `:sourceHandle "right"` + `:targetHandle "left"` so it leaves a branch from its RIGHT source handle and enters the next from its LEFT target handle (the named cardinal handles `four-cardinal-handles` emits — RIGHT is a source, LEFT a target). The branches lay out LEFT-TO-RIGHT in priority order, so the chain must read side-to-side; without the explicit handles xyflow may attach BOTTOM→TOP off the unnamed cardinal handles, making the priority chain visually awkward and dependent on xyflow's default-handle internals | *`:forkConnector true` edge data, `:pseudo-marker` dotted stroke, `:sourceHandle "right"` / `:targetHandle "left"`; emitted by projector `fork-connector-edges`; branch order pinned via `fork-branch-event-positions` (`elk.position`) + `fork-branch-container-ids` (`semiInteractive`)* |
| **State tags** | **one neutral chip style** (structure wins over annotation colour — rotation dropped); the VISIBLE label + `data-tag` preserve the DECLARED namespaced identity (`door/open`, not truncated `open` — rf2-vcnvj); `data-testid` keeps the `name`-collapsed segment (a `/` breaks selectors) | *`:container-header-bg` fill, `:state-border`* |
| **Entry / Exit action caption** | quiet **TITLE-CASE** section label ("Entry actions" / "Exit actions") — NOT uppercase (rf2-vcnvj; the uppercase transform competed with the state title) | *`:text-tertiary`, small caption px* |
| **Root machine chrome (rf2-q129z8; rf2-3q4k5b)** | the machine name + Context shape ride the HEADER of the synthetic **ROOT-CONTAINER frame** (next row), NOT a corner-pinned overlay. The header is a full-width title strip carrying the machine name (subtle `∥` glyph for root-parallel machines) and, under it, a Context BAND showing the **static context shape** (keys + type captions) when available (the Xray topology path derives it via `topology-view/static-context-shape`), **declared over inferred** (rf2-3q4k5b · EP-0005): authoritative off a machine's `:data-schema` when present (badged `declared`), else inferred from one sample of the initial `:data` (badged `inferred from :data` — rf2-5tz9p). The provenance badge is gated by `:context-band-inferred?` (default true → inferred badge; false → `declared` badge), which the host also sets false when feeding live `:data` values. **Pre-q129z8** this was two `position:absolute` overlays welded to the chart's top-left corner — they did NOT contain or track the topology (on resize xyflow re-fit the topology but the chrome stuck to the corner) and collided with other top-left chrome. Folding them into the frame header eliminates both. | shipped (`rf-mv-chart-root-container-title-<id>` + `rf-mv-chart-root-container-context-<id>` + `rf-mv-chart-root-container-context-inferred-<id>` / `-declared-<id>`) |
| **Root-container frame (rf2-q129z8)** | a **named rounded CONTAINER box** wrapping the WHOLE machine — Stately Studio's frame around the machine root. Every top-level node (flat states, the `◆ root` chip, parallel regions) nests UNDER it via xyflow `parentId`; **ELK sizes the frame to HUG its children and reflows it on resize**, exactly as a compound-state container already hugs its substates (the painted box fills the ELK-sized xyflow box `width/height:100%`, no CSS `min-*` of its own — same posture as the compound + region containers). Solid subtle-neutral border, NEUTRAL header (it is structural chrome, never marked `:active`, never a click/selection target). Nesting is two levels deep (frame → compound → leaf) for a compound machine and one for a flat one; verified flat (door) AND compound (hvac/media) nesting still nest correctly. **Context-band TOP-padding reservation (rf2-8z1rca).** The frame header paints a title strip PLUS the variable-height **Context band** (above), but the band is NOT an ELK child, so ELK's `INCLUDE_CHILDREN` pass never grows the frame to enclose it. `container-elk-padding`'s plain TOP reserves only the title strip + a body-pad band, so with non-trivial context the first child ELK laid out at the reserved content edge sat UNDER the painted band. The fix ADDS the band's rendered height (`projection/context-band-height`, derived from the context-row count + the density divider — fixed-pixel CSS single-sourced from the `context-band-*` constants the renderer reads) to the ROOT-CONTAINER frame's TOP padding ONLY (threaded `:context-band`-row-count → `chart.cljs/compute-layout!` → `->elk-input` → `->elk-children`). 0 context rows ⇒ no band ⇒ no extra top, byte-identical to the prior padding; every non-root container is unaffected. | *`:container-body-bg` fill, `:container-border` solid, `:container-header-bg` strip; box from ELK `:style {:width :height}`, no rendered `min-*`; `chart.nodes/root-container-node`, node-type `"root-container"`, node-id `chart.layout/root-container-id`, `data-root-container` hook; `projection/context-band-height` + `container-elk-padding` `extra-top` arm* |
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
  loop. There is no renderer-side self-loop path (a spec self-transition
  routes as `state → event-node → state`, two ordinary ELK edges;
  rf2-hstzzj pruned the dead fan geometry).
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

#### 4.3.1 G-ROUTE — back-edge return-route detour ✅ CLOSED, OPT-IN (rf2-gnrkke — custom post-ELK reroute, gated behind `:direction :auto`)

A close-the-gap layout-fidelity pass (rf2 layout-fidelity) compared the
full dark corpus against the Stately/xstate references and isolated a
residual divergence in the **return route** of a cyclic statechart's
back-edges. On the Stately reference, a loop-closing transition (door's
`alarming → reset → locked`, gate's `{high,low,rejected} → reset → idle`,
brew's `ready → start → brewing`) places its **event chip at mid-height**
and routes the return compactly up one side. On `MachineChart` the
synthetic event-node of that back-edge sinks to the **deepest layer** (the
very bottom), so the return reads as a long vertical detour up the canvas
edge.

**Root cause — structural, not a missing option.** Under the
events-as-nodes paradigm (rf2-qo5xy) the back transition
`alarming → locked` is two ELK edges through a node:
`alarming → reset → locked`. ELK's Layered algorithm ranks `reset` one
layer **below** `alarming` because `alarming → reset` is a forward edge
and `alarming` is already the deepest state — so `reset` is forced to the
bottom regardless of the layering / node-placement strategy. Stately
avoids this because it keeps the transition as a **single** edge with the
chip ON the (reversed) back-edge, so the chip floats to the edge midpoint;
the events-as-nodes split (an intentional, Stately-parity design lock —
§Event labels) gives the chip its own layer instead.

**Empirically confirmed dead ends** (each captured + compared corpus-wide):

- `elk.layered.nodePlacement.strategy NETWORK_SIMPLEX` — tidies columns
  but does NOT lift the back-edge node (placement is within-layer).
- `elk.layered.layering.strategy COFFMAN_GRAHAM` — no lift; risks worse
  crossings elsewhere.
- `elk.layered.cycleBreaking.strategy GREEDY` — DOES lift the back-edge
  node to mid-height BUT **inverts the spine** (door renders `open` on top
  and the initial `locked` in the middle), a hard REGRESSION that
  re-breaks the rf2-ly51l initial-on-top guarantee. The DEPTH_FIRST ⇄
  GREEDY trade is mutually exclusive: you get the compact return OR the
  initial-on-top spine, never both.

**Close — custom post-ELK reroute, OPT-IN (rf2-gnrkke).** Since no ELK
option reaches it without the spine regression, the remaining §4.3 path is
taken: a **custom post-ELK reroute of the sunk back-edge event-nodes**, in
the new pure ns `chart.post-elk`. After ELK settles (and before the
projector emits xyflow nodes/edges) the pass:

1. **Detects** a back-edge GEOMETRICALLY (`post-elk/back-edge?`) — no
   GREEDY cycle-breaking, so the rf2-ly51l initial-on-top spine is
   untouched. A back-edge is an event-node whose centre sits PAST both its
   source AND its target along the flow axis (below both for a `:tb`
   layout, right of both for `:lr`) — exactly the sunk-to-the-deepest-layer
   signature above.
2. **Lifts** the chip to **mid-height** between its endpoints
   (`post-elk/back-edge-detour`) — Stately's compact mid-height return —
   bowed `back-edge-detour-offset` px to the quiet side of the spine.
3. **Reroutes** the back-edge's two segments (`<id>__in` / `<id>__out`) as
   a clean **side-detour** (source → out the side → lifted chip → in to the
   target) so the return reads as a path AROUND the cluster. The elbow
   **mirrors the flow axis** (rf2-hpe9ws): for `:tb` (vertical flow) the
   route leaves the source SIDEWAYS to the side lane then runs vertically to
   the lifted chip; for `:lr` (horizontal flow) it leaves the source
   VERTICALLY to the side lane then runs horizontally to the chip. A
   `:tb`-shaped elbow on an `:lr` layout would run along the flow row first,
   crossing the forward edges.

Forward edges and self/internal transitions are untouched (`back-edge?`
excludes them); a back-edge ELK already placed compactly keeps its route.

The opt-in `apply-post-elk` transform (back-edge reroute + parallel
transpose) is gated by the RAW `:auto` opt-in, while ELK is fed the RESOLVED
direction. So the `MachineChart` layout-key folds in the opt-in MODE flag
alongside the resolved direction (rf2-9qbn0g): when `:auto` resolves to the
same direction as a forced/default `:tb` (a linear / parallel machine), a
`:tb → :auto → :tb` flip still invalidates the cached layout so the transform
applies on opt-IN and is removed on opt-OUT rather than stale-caching.

**OPT-IN — not auto-applied (the rf2-lamdfl/rf2-gnrkke redo lock).** The
reroute runs ONLY when a machine OPTS IN via `:direction :auto`
(`chart.post-elk/adaptive?`). With the default `:direction :tb` (or an
explicit `:tb` / `:lr`) the pass is **not invoked** and the render is
byte-identical to the pre-feature output — every existing non-opted machine
moves zero pixels (the first build, the reverted #3453, made `:auto` the
DEFAULT and regressed the whole corpus; this redo gates it). The G-SHAPE
inter-layer compaction (`nodeNodeBetweenLayers 50`, §2 Layout) the earlier
pass shipped is independent and stands on the default path.

#### 4.3.2 G-ASPECT — per-machine layout aspect + parallel-region axis ✅ CLOSED, OPT-IN (rf2-lamdfl — direction heuristic + post-ELK region transpose, gated behind `:direction :auto`)

A follow-on layout-fidelity pass (rf2-lamdfl) measured the **diagram
aspect ratio** (content-bbox `width / height`, read from the exported
SVG `viewBox`) of every corpus machine against the Stately/xstate
reference, dark + light. Stately **adapts** its aspect per machine:
linear flows (`door` ≈ 0.45, `brew` ≈ 0.63) stay a tall **column**;
branchy / multi-event flows (`quiz` ≈ 1.45, `modal` ≈ 1.31, `gate` ≈ 1.14)
flow **landscape**; and parallel machines stack their regions **vertically**
with each region laid out **horizontally** (`traffic` ≈ 2.10: a `vehicle`
row over a `pedestrian` row). `MachineChart` forces `elk.direction DOWN`
corpus-wide, so it matches Stately on the genuinely-vertical machines
(`door` ≈ 0.52, `brew` ≈ 0.59) but renders the branchy ones squarer than
Stately (`quiz` ≈ 1.03, `modal` ≈ 1.04) and **inverts BOTH parallel axes**
(regions side-by-side, intra-region flow vertical — `traffic` ≈ 1.04).

**Root cause — structural, not a missing option. Two independent walls:**

1. **Flat-machine aspect — there is no ELK *balance* lever for Layered;
   the only width lever is layer *wrapping*, and wrapping a chain IS the
   regression.** ELK Layered does NOT read `elk.aspectRatio` as a
   width/height balance target (it is consumed only by the `force` /
   `stress` / `mrtree` algorithms, which would destroy the statechart
   ranking, initial-on-top, and ORTHOGONAL cross-hierarchy routing — out
   of scope). The only option through which a target aspect influences
   Layered is `elk.layered.wrapping.strategy`, which **cuts a long layer
   chain into stacked rows**. (Pairing `aspectRatio` *with* wrapping — the
   verbatim xstate-viz root combo, on the hypothesis that `aspectRatio`
   only binds once wrapping is enabled — was re-tested 2026-06-08 and is
   the same wall, only worse: see the `aspectRatio "2"` dead-end below.)
   But the machines that read too-narrow are
   precisely the genuinely-**linear** ones (`door`, `brew` are chains),
   and wrapping a chain is the exact REGRESSION the parity bar forbids —
   it shatters the clean vertical column and mangles initial-on-top.
   Stately renders `door` as a column for the same reason xray does (a
   chain has nowhere to branch); it renders `quiz` landscape because
   `quiz` genuinely **branches**, and ELK already spreads true siblings
   within a layer. The residual `quiz` / `modal` narrowness is not a
   missing aspect target — it is the **events-as-nodes rank cost** (each
   transition's event-node consumes its own layer, where Stately floats a
   self/internal event-chip *beside* the source — see §1.5 / the
   events-as-nodes lock), which is a separate, out-of-scope structural item.

2. **Parallel-region axis — `INCLUDE_CHILDREN` flattens to ONE global
   direction; a per-region `elk.direction` is ignored.** The natural fix
   is to give each region container `elk.direction RIGHT` (states flow
   left→right ⇒ a region becomes a wide, short band ⇒ the root `DOWN`
   stacks the regions vertically = the Stately shape). The projection DOES
   emit it correctly (`region__* → {"elk.direction" "RIGHT", …}`), but the
   render is **unchanged**: ELK's cross-hierarchy mode
   `hierarchyHandling INCLUDE_CHILDREN` — which `MachineChart` MUST set for
   parallel so the broadcast `traffic/shared` edge routes across regions
   (the G5 cross-hierarchy switch, rf2-gpa9k) — collapses the whole nesting
   to a single global layout direction and DISCARDS per-container
   `elk.direction`. Recovering per-region direction would require
   `SEPARATE_CHILDREN` (which breaks the cross-region edge routing G5 was
   built to enable) or a custom post-ELK region-axis transform (new
   machinery, not an ELK option).

**Empirically confirmed dead ends** (each compiled into the
`:examples/machine-epochs` testbed, hot-reloaded, re-captured + measured
corpus-wide via `ai/topology/capture.cjs`):

- `elk.aspectRatio "1.6"` ALONE — **complete no-op** for Layered (every
  machine byte-identical; refutes the "Layered auto-balances to a target
  aspect" hypothesis).
- `elk.aspectRatio "1.6"` + `elk.layered.wrapping.strategy SINGLE_EDGE` —
  touches only `quiz` (1.03 → **2.80**, overshooting the 1.45 target with
  a long looping return edge); leaves the genuinely-narrow `media` /
  `session` / `modal` untouched. Wrong machine, wrong amount.
- `elk.aspectRatio "1.4"` + `elk.layered.wrapping.strategy MULTI_EDGE` —
  **hard REGRESSION**: wraps the linear chains, flipping `door` 0.52 →
  1.31 and `brew` 0.59 → 1.56 into scrambled landscape blocks (chain
  broken, `open`/`alarming` thrown to the upper-right, initial-on-top
  mangled). This is the exact non-regression the parity bar forbids.
- **`elk.aspectRatio "2"` + `elk.layered.wrapping.strategy MULTI_EDGE`
  (+ `elk.layered.compaction.postCompaction.strategy LEFT`) — the
  VERBATIM xstate-viz root pairing, RE-TESTED 2026-06-08 (rf2-lamdfl).**
  The lead (`ai/topology/xstate-viz-research.md`): `aspectRatio` was
  suspected a no-op *alone* because it only binds when `wrapping.strategy`
  is enabled, so the PAIR was never properly evaluated. It was — the
  worker re-built the `:examples/machine-epochs` bundle with the pair and
  re-captured all 9 machines × both themes against a clean before/after
  baseline (`capture.cjs`, dark+light identical since aspect is layout,
  not palette). Result: **the pair is the SAME hard regression, only
  worse than the `"1.4"` row above** — it inflates the wrapping that
  shatters every genuinely-vertical / linear machine. Per-machine
  before → after diagram aspect (content-bbox `width/height`):
  `door` 0.52 → **2.02**, `brew` 0.59 → **1.52**, `session` 0.68 →
  **2.32**, `media` 0.68 → **1.53** — all four columns flipped to
  scrambled landscape blocks with the forward spine broken and the
  initial state no longer on top (verified visually: door's `closed`
  thrown to upper-centre, `alarming`/`door/reset` flung to the far
  upper-right, the `insert-coin → locked` back-loop a tangled knot). The
  branchy machines OVERSHOOT, not match, the Stately target:
  `quiz` 1.20 → **2.84** (target ≈ 1.45), `modal` 1.18 → **3.35**
  (target ≈ 1.31). The nested / parallel machines are UNCHANGED
  (`gate` 1.71→1.75, `hvac` 1.13→1.13, `traffic` 1.07→1.07) — confirming
  wall 2: `INCLUDE_CHILDREN` (mandatory for those) discards the wrapping
  just as it discards a per-region `elk.direction`, so the pair cannot
  even reach a parallel machine. NO machine moved CLOSER to its Stately
  target; four genuinely-vertical machines REGRESSED hard. There is no
  `aspectRatio`-binds-wrapping win to recover — the pairing simply turns
  wrapping ON, and wrapping a chain *is* the regression (wall 1). The
  combo was REVERTED; `default-elk-options` is unchanged.
- per-region `elk.direction "RIGHT"` on every `:region?` container — the
  projection emits it (JVM-verified), but the render is unchanged because
  `INCLUDE_CHILDREN` discards it (see wall 2).

**Verdict — closed via NEW machinery, OPT-IN (rf2-lamdfl).** The per-machine
aspect adaptation and the parallel-region axis are BOTH structural, not
reachable via an ELK direction / aspect / region-axis option without
regressing the genuinely-vertical machines (`door`, `brew`), the rf2-ly51l
initial-on-top guarantee, or the G5 cross-hierarchy edge routing (the
verbatim xstate-viz `aspectRatio "2"` + `wrapping.strategy MULTI_EDGE`
pairing — the most credible remaining "maybe ELK can do it" lead — was
re-tested in full and **confirmed the residual**, regressing four vertical
machines hard, the `aspectRatio "2"` dead-end above). So the close takes
the two NEW-machinery levers the dead-end research pointed to, both in the
pure ns `chart.post-elk`:

1. **Per-machine direction HEURISTIC (wall 1 — the flat-aspect lever).**
   `post-elk/aspect-direction` chooses the ELK layout direction PER MACHINE
   from the parsed graph's shape — the bead's "vertical vs landscape per
   machine". A LINEAR chain (max single-state out-degree below
   `landscape-branch-threshold` = 3) stays a **column** (`:tb` — door /
   brew / session / media); a genuinely-BRANCHY machine (a state fanning to
   ≥ 3 distinct targets — quiz / modal / gate's `:gate/check` 3-way) flows
   **landscape** (`:lr`), spreading the branch sideways into the Stately
   proportion. This is NOT a blanket flip and NOT wrapping (the wall-1
   regression): a column chain and a landscape hub both lay out cleanly,
   with DEPTH_FIRST + the model-order preference working identically in
   either direction.

2. **Post-ELK parallel-region TRANSPOSE (wall 2 — the region-axis lever).**
   `post-elk/transpose-parallel-regions` runs AFTER ELK (so
   `INCLUDE_CHILDREN` — mandatory for the G5 cross-region broadcast edge —
   is undisturbed) and TRANSPOSES the result: each region's interior
   children swap their within-region x/y (the intra-region flow that ran
   vertically under root `DOWN` now runs **horizontally** — the Stately
   intra-region axis), and the region containers re-stack into a single
   **vertical column** (`region-stack-gap` between bands). After the swap the
   flow ranks are **re-packed** along the new x-axis (`post-elk/respace-flow-
   ranks`) by each rank's actual WIDTH + `intra-region-flow-gap` — the bare
   swap inherits the flow pitch ELK budgeted for node HEIGHTS (state 58 / chip
   34), too tight once boxes occupy their WIDTHS along x (state 152 / chip
   96), which buried the intra-region event-node chips into the state boxes
   (rf2-vb359s). Region-touching edge routes are cleared so they re-route via
   the renderer's bezier fallback through the transposed handles.

**OPT-IN — not auto-applied (the redo lock).** Both levers fire ONLY when a
machine OPTS IN via `:direction :auto` (`chart.post-elk/adaptive?`); with
the default `:direction :tb` (or an explicit `:tb` / `:lr`) the heuristic is
NOT consulted and `apply-post-elk` is NOT invoked — the render is
byte-identical to the pre-feature output. The reverted first build (#3453)
made `:auto` the DEFAULT and re-laid the whole corpus (the regression); this
redo keeps every existing non-opted machine pixel-identical (verified: the
9-machine machine-epochs corpus is pixel-for-pixel unchanged on the default
path vs `main`). The selective-beside-the-source event-node placement the
research also floated (the flat-branchy event-node rank cost) remains a
SEPARATE, still-out-of-scope item — the direction heuristic recovers the
flat-aspect win without it. **Resolved rough edge (rf2-vb359s):** the
parallel transpose previously left intra-region event-node chips overlapping
state boxes after the bare coordinate swap (the swap inherited ELK's
height-sized flow pitch, too tight for the wider boxes along the new x-axis);
the transpose now re-packs the flow ranks by their actual widths
(`respace-flow-ranks` + `intra-region-flow-gap`), so the chips clear the
state boxes. The feature stays opt-in behind `:direction :auto`.

### 4.4 G3 — fired-edge id consistency ✅ CLOSED

- ✅ **Single edge-id source of truth (rf2-8jzm1 — DONE).**
  `chart.layout/edge-id` is the canonical minting fn. Rather than
  RE-IMPLEMENT the scheme (source-id `__` target-id `__` event-segment
  `__g_<guard>` `__a_<action>` + per-key ordinal tiebreak), the host's
  `extract-fired-edge-ids` (Xray-side) **projects the definition through
  the public `project-definition`** and reads ids off the projected edges,
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
- ✅ **Parallel fired-edge coverage (rf2-8ncxrf / rf2-85a9do / rf2-3v3gv1 / rf2-l8ls6w).**
  A `:type :parallel` transition emits ONE `:rf.machine/transition` whose
  `:before` / `:after` carry the WHOLE region-map. `extract-fired-edge-ids`
  (Xray-side) derives the fired edges per region from the region-map **plus**
  the structured `:cascade`. A region that **changed** is resolved through an
  ordered fallback (the edge shapes are mutually exclusive per region):
  (a) **region-local transition** — lights its region-scoped edge (rf2-8ncxrf);
  (b) **the region's OWN top-level `:on` fallback** — no region-local edge
  matched, but the region def carried a region-level `:on` whose machine-level
  fallback edge (`:machine-level?`, sourced from the region CONTAINER per
  §rf2-7i7t3, in-region `:to-path`, **not** `:parallel-root-on?`) moved it; it
  lights that edge, reserved between the region-local and root-`:on` arms
  (rf2-85a9do); (c) **the parallel ROOT `:on`** — neither a region-local nor a
  region-level `:on` edge matched, so the MACHINE-ROOT-sourced chip whose
  region-qualified `:to-path` names the region lights (rf2-3v3gv1). Separately,
  a region that was **HANDLED but UNCHANGED** — a real targetless/internal or
  external self transition with `before == after` and a non-empty cascade —
  lights its self/internal edge through an ordered pair: a **leaf**
  self/internal edge (region-scoped in-region source, rf2-l8ls6w) else a
  region-**ROOT targetless/action-only `:on`** fallback (`:machine-level? true`
  `:internal? true`, anchored to the region CONTAINER on BOTH ends per
  §rf2-pdvtxt — a shape `region-self-internal-fired-ids`' region-scoped source
  cannot reach), distinguished from a RESTING region (declined the event,
  absent from the cascade) by the `:cascade` `:region` stamps
  (rf2-l8ls6w). All arms mint ids that agree with the live chart by
  construction (the projection is the single edge-id source).

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
| C1 | **rf2-8jzm1** ✅ — consolidate trace→state helpers; emit machines-viz edge-ids | existing | isolated (Xray helpers ns + tests) | **Closed G3 (helper half).** `extract-fired-edge-ids` mints `chart.layout/edge-id`-identical ids (via `project-definition` projection — agree by construction). |
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

A 2026-06-05 R3 faithfulness review (rf2-mnp93) surfaced the F5–F7
follow-ons below — the SAME cross-emitter-asymmetry class as F2, generalised
to the ORDINARY internal transition (not just `:on-done`): an INTERNAL
(action-only, no-`:target`) `:on` / `:after` / `:always` candidate (Spec 005
§Transition slots: "omit for internal") was projected INCONSISTENTLY — the
chart charted internal `:on` but silently dropped internal `:after` /
`:always`; mermaid dropped ALL three; SCXML kept all three (rf2-mnp93.4). The
SAME SCXML internal action transition also round-tripped into a Spec 005
FORBIDDEN BLOCK — a semantic inversion (rf2-mnp93.5) — and the mermaid
node-id sanitiser was non-injective (rf2-mnp93.6, the same collision class
the chart's `node-id` was fixed for in rf2-ee38b.21). With F5–F7 every
internal transition is surfaced consistently across all three emitters, the
SCXML codec round-trips it faithfully, and the mermaid id is injective.

| Step | Bead | New? | Hot-zone | Notes |
|---|---|---|---|---|
| F1 | **rf2-41goo** ✅ — `feat(machines-viz): project :on-done (XState onDone) completion transition (chart + mermaid + scxml)` | existing | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `mermaid.cljc`, `scxml.cljc`, + JVM tests + this doc) | **Closes G9.** `:on-done` was never parsed/projected (zero matches across the three emitters). Now: the COMPOUND completion edge resolves to the SIBLING (`✓ done` chip); the PARALLEL-ROOT completion (action/fx-only) renders as a terminal affordance / mermaid note; the SCXML emitter round-trips the W3C `done.state.<id>` transition. Parses + projects faithfully to the engine's `[:rf.machine/done <path>]` raise. |
| F2 | **rf2-ay42f** ✅ — `fix(machines-viz): render compound action-only :on-done in mermaid` | new | isolated (`mermaid.cljc` + JVM tests + this doc) | **G9 faithfulness completion.** A COMPOUND whose `:on-done` is ACTION-ONLY (no `:target` — the engine runs an action when the sub-flow completes; the machine stays in the all-final config; a documented Spec 005 shape) surfaced in chart + SCXML but was SILENTLY DROPPED in mermaid (`collect-on-done-edges` kept only target-bearing candidates; the note path covered only the parallel-root). Now mermaid renders a `note right of <compound>` carrying `on-done: ✓ done / <action>` — the same affordance the parallel-root action-only `:on-done` uses — so the completion is visible across **all three** emitters. The G9 "faithful across all three emitters" claim is now accurate for the action-only compound shape. (`collect-compound-on-done-notes` walks the state tree incl. region-nested + deeply-nested compounds.) |
| F3 | **rf2-dblqx** ✅ — `fix(machines-viz): parallel-root :on-done anchor is inert (not a clickable phantom state)` | new | isolated (`chart/projection.cljc` + JVM tests + this doc) | The synthetic parallel-root completion anchor (F1) fell through the node-`:type` cond to `"state"` AND through the `:onClick` guard (which excluded only `:machine-root?` + region), so it projected as a CLICKABLE `parallel` state box — clicking it dispatched on-state-click against the rendering-sentinel path. The SAME inert-synthetic-chip class **rf2-34ff3** ruled for the machine-root chip + region containers. Fix: type the parallel-root anchor `"machine-root"` (the quiet root-context chip) AND exclude `:parallel-root?` from the `:onClick` guard, mirroring 34ff3. The anchor is now INERT. |
| F4 | **rf2-bs3us** ✅ — `fix(machines-viz): align chart parallel-root done-state label to SCXML` | new | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `scxml.cljc` + JVM tests + this doc) | The chart's parallel-root `:doneState` label was the degenerate `"done.state."` (the root sentinel path `[]` has an EMPTY `node-id`), diverging from the SCXML emitter's `"done.state.rf2_parallel_root"`. Fix: a shared canonical sentinel id (`layout/parallel-root-done-state-id` = `"rf2_parallel_root"`) is the SINGLE SOURCE OF TRUTH both the chart `:doneState` renderer label and the SCXML `<parallel id=...>` / `done.state.<id>` event read from, so the two emitters agree. Pure label fix (not load-bearing for topology / round-trip). |
| F5 | **rf2-mnp93.4** ✅ — `fix(machines-viz): surface internal action-only :on/:after/:always consistently across chart + mermaid + scxml` | new | isolated (`chart/layout.cljc`, `mermaid.cljc` + JVM/CLJS tests + this doc) | **G9 faithfulness — generalised from `:on-done` (F2) to the ordinary internal transition.** An INTERNAL (action-only, no-`:target`) `:on` / `:after` / `:always` candidate was projected INCONSISTENTLY: the CHART charted internal `:on` (self-anchored, `:internal?`) but SILENTLY DROPPED internal `:after` / `:always` (the `resolve-target-path` inside `keep` returned nil for a target-less candidate — inconsistent even WITHIN the chart); MERMAID dropped ALL three; SCXML kept all three. Three emitters, three projections of one machine. Fix: `chart/layout.cljc`'s `:after` / `:always` branches now detect the target-less candidate and self-anchor it (`:internal? true`), matching the `:on` branch; `mermaid.cljc` renders each internal candidate as a `note right of <state>` line (`<descriptor> [guard] / <action>`) — the SAME action-only-note affordance F2 introduced for `:on-done`. SCXML was already faithful. A new `cross_emitter_agreement_cljs_test` pins the three-emitter count agreement (chart 3 / mermaid 3 / scxml 3 for the bead repro). |
| F6 | **rf2-mnp93.5** ✅ — `fix(machines-viz): SCXML round-trips an internal action transition (not a forbidden block)` | new | isolated (`scxml.cljc` + JVM/CLJS tests + this doc) | **SCXML round-trip semantic inversion.** An internal action `:on {:tick {:action :log}}` emitted `<transition event="tick"><!-- action: log --></transition>`; the decoder's `strip-comments` discarded the action comment BEFORE tokenizing, so the candidate decoded to the EMPTY map `{}` — which Spec 005 §Forbidden transitions defines as a FORBIDDEN BLOCK (consume-the-event-and-block-inheritance). 'Run an action' → 'block the event entirely' is a SEMANTIC INVERSION, not lossy detail. Fix: a `lift-action-comments` pre-pass folds each `<!-- action: NAME -->` into a synthetic `data_rf_action` attribute on its own `<transition>` BEFORE comments are stripped, and the decoder recovers it into the candidate's `:action`. An internal action transition now round-trips as `{:action :log}` — a VALID Spec-005 internal action transition (the distinguishing feature is the PRESENCE of `:action`, Spec 005 §Forbidden L1346). A genuine `{}` forbidden block still round-trips to `{}` (the lift-pass only recovers a PRESENT action). |
| F7 | **rf2-mnp93.6** ✅ — `fix(machines-viz): mermaid sanitise-id is injective` | new | isolated (`mermaid.cljc` + JVM/CLJS tests + this doc) | **Mermaid node-id collision.** `sanitise-id` mapped every non-`[a-zA-Z0-9_]` char to `_`, so `:a/b`, `:a-b`, and `:a_b` all collapsed to `a_b`: two distinct states became ONE Mermaid node, mis-wiring every edge to/from either (hyphens are pervasive in re-frame keywords). The SAME collision class the chart's `node-id` was fixed for in rf2-ee38b.21. Fix: port the chart's injective hex-escape scheme (`:a/b` → `a_2fb`, `:a-b` → `a_2db`, `:a_b` → `a_5fb`; `/` ns/name marker → `_2f`, `__` path separator) into `sanitise-id`, so distinct keywords map to distinct mermaid nodes AND the chart + mermaid emitters mint the SAME id for the same path (a tool reading both addresses every node identically). Mermaid node-ids are internal — the visible label comes from `render-state-alias` — so the injective encoding costs no legibility. |
| F9 | **rf2-9dj21r** ✅ — `fix(machines-viz): represent the :reenter? external-restart axis across chart/Mermaid/SCXML` | new | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `chart/nodes/event_node.cljs`, `mermaid.cljc`, `scxml.cljc` + JVM/CLJS tests + this doc + `API.md`) | **The `:reenter?` external-restart axis was unrepresented in ALL THREE emitters.** A TARGETED transition is INTERNAL by default (XState v5 / Spec 005 §Self-transitions); `:reenter? true` is the EXTERNAL opt-in — re-run `:exit`/`:entry`, restart the target's `:after` timers + `:spawn` children. The viz collected edges from `:target`/`:guard`/`:action` + targetless `:internal?` only; projection exposed only `:internal`; SCXML emit/import had no `type=` handling. So `{:target :same-state}` and `{:target :same-state :reenter? true}` — two RUNTIME-DISTINCT machines — produced IDENTICAL chart topology, Mermaid, SCXML export AND SCXML import. Fix: `chart/layout.cljc` carries `:reenter?` on every parsed edge (`:on`/`:after`/`:always`/`:on-done`/machine-level) and folds it into the edge id so the with/without pair mints DISTINCT ids; `chart/projection.cljc` threads `:reenter` onto the event-node `:data`; `event_node.cljs` paints a `↻` reenter chip (`data-reenter` hook); `mermaid.cljc` appends a `↻` label marker; `scxml.cljc` maps the axis onto W3C SCXML's native `<transition type="external">` (emit + lossless round-trip on import — a target-only candidate that gains `:reenter? true` is no longer collapsed to the bare-keyword shorthand, so it survives verbatim). A new `cross_emitter_agreement_cljs_test` section pins with/without distinctness in chart + mermaid + SCXML-export + SCXML-import. Matches the engine's `(true? (:reenter? transition))` axis (`re-frame.machines.transition`, eicq0/tsq6g). |
| F10 | **rf2-656ivk** ✅ — `fix(machines-viz): preserve parallel-root :on in SCXML + action-only mermaid output` | new | isolated (`chart/layout.cljc` regression-only, `scxml.cljc`, `mermaid.cljc` + JVM/CLJS tests + this doc) | **The viz counterpart to the machines-core parallel-root `:on` fix (#4292).** The chart projected the parallel-root `:on` (rf2-3v3gv1) but the OTHER two emitters did not: **SCXML** dropped the root `:on` ENTIRELY (`emit-parallel` emitted only `:on-done`; import recovered only `:on-done`), and **mermaid** dropped the ACTION-ONLY root `:on` (`collect-root-fallback-edges`' target `when-let` discarded a targetless candidate) AND a map-form multi-region target (`resolve-target-path` only resolves a flat keyword-vector path). Fix: SCXML emits the root `:on` as a DIRECT `<parallel>` `<transition>` (multi-region = a SPACE-SEPARATED W3C `target` id list `a___x b___y`, targetless = no `target`) wrapped in a documenting comment, and `parse-root-parallel-transitions` recovers it on import (decoding the space-separated region-qualified targets via `decode-root-parallel-target`) — a full round-trip including multi-region + action-only forms. The root emitter uses a ROOT-aware candidate normaliser (`root-transition-candidates`) so a vector-of-vectors `:vec-target` is ONE multi-region target (matching `re-frame.machines.grammar/transition-value-form`), NOT a candidate fork. Mermaid renders the action-only root `:on` as a `note` (`root-fallback-internal-notes` — the F2/F5 affordance) and the multi-region map-form target via `collect-root-fallback-on-edges` (`root-region-qualified-candidates` explodes the multi-region target per region). |
| F11 | **rf2-m3otj2** ✅ — `fix(machines-viz): render parallel-root :after across chart + exporters` | new | isolated (`chart/layout.cljc`, `scxml.cljc`, `mermaid.cljc` + JVM/CLJS tests + this doc) | **The viz counterpart to the machines-core parallel-root `:after` fix (rf2-wox0vd / #4292).** A `:type :parallel` root MAY declare its OWN `:after` — the TIMER-DRIVEN analog of the root `:on` ancestor fallback (root-owned, scheduled at birth; same region-qualified apply grammar). ALL THREE emitters dropped it: the chart collected only the root `:on` (`project-parallel` called only `collect-parallel-root-edges`); mermaid destructured only `regions on on-done`; SCXML emit/import handled only `on-done` direct parallel transitions. So a valid machine-lifetime timeout executed while chart, Mermaid AND SCXML all reported the topology did not exist. Fix: `collect-parallel-root-after-edges` projects each root `:after` region-qualified target from the MACHINE-ROOT chip into the region-scoped node, carrying `:after <delay>` (the `⌚` glyph) + `:parallel-root-after? true` (and `:parallel-root-on? true` so it reuses the root `:on` re-pointing + fired-edge resolver path); mermaid renders an `after(<delay>) (root fallback)` edge (action-only → a note); SCXML emits + round-trips a direct `<parallel>` `<transition event="after.<delay>" target="…"/>` (single, multi-region, and action-only forms). A new `cross_emitter_agreement` section pins the three-emitter agreement for both the root `:on` (F10) and root `:after`. |
| F8 | **rf2-m285a** ✅ — `fix(machines-viz): history pseudo-states + SCXML inline-fn refs + parity` | new | isolated (`chart/layout.cljc`, `chart/projection.cljc`, `mermaid.cljc`, `scxml.cljc`, `share.cljs` + JVM/CLJS tests + this doc) | **History pseudo-states were parsed/exported as ordinary occupiable states** — `collect-nodes` made a normal node, projection fell through to xyflow `"state"`, mermaid emitted a bare leaf id, SCXML emitted `<state>`/`<final>` (never `<history>`). This contradicted Spec 005 (history = pseudo-state, never active; a transition to `:hist` resolves to the recorded/default leaf) and lost XState-v5/SCXML history parity. Fix: detect `:type :history` at parse → non-occupiable `{:history? true :deep? … :default-target …}` marker (the `chart.nodes/history-marker` HOOK becomes a live projector path → xyflow `"history-marker"`); mermaid declares a labelled `H` / `H*` marker + a `%% default-target` note; SCXML export/import preserve W3C `<history type="shallow|deep">` + the default `<transition>` and round-trip back to `:type :history`. **Also (F8b)** SCXML export crashed (`ClassCastException`) on an inline-fn `:guard`/`:action` (it passed the fn to `keyword->id-string`); a new `ref->label` tolerates fn refs as opaque/lossy labels (consistent with chart/mermaid + the API promise that fn bodies are lossy, not unsupported). **And (F8c)** the share encoder leaked macro-stamped `:source-coords`/`:source-code` + executable `:fn` DATA (which `strip-meta` — metadata only — never reached); `sanitise-definition` strips them structurally before Transit. |
| F12 | **rf2-5uhdaz** ✅ — `fix(machines-viz): surface targetless machine-level :on fallback in chart + mermaid` | new | isolated (`chart/layout.cljc`, `mermaid.cljc` + JVM/CLJS tests + this doc) | **The FLAT-machine counterpart to F10's parallel-root action-only `:on` fix.** A TARGETLESS (action-only, no-`:target`) MACHINE-LEVEL (top-level) `:on` fallback runs its action and leaves the state unchanged — the engine consults the root's own `:on` LAST (`re-frame.machines.transition/pick-transition` steps 6-7; XState v5 targetless-transition semantics; `machine_remediation_ee38b_test` proves an action-only root wildcard fires + leaves state unchanged). The CHART dropped it (`collect-machine-edges`' `(when tp …)` discarded a target-less candidate — inconsistent with the state-local `:on` AND the parallel-root `:on`, both of which already self-anchor an `:internal?` chip); MERMAID dropped it too (`collect-root-fallback-edges`' target `when-let` — the SAME class F10 fixed for the parallel-root). SCXML already surfaced it (`emit-machine-level-on` → `emit-transitions-for-on` emits a target-less `<transition>`). Fix: `collect-machine-edges` self-anchors a targetless candidate on the synthetic MACHINE-ROOT chip (`:to []`, `:internal? true`), and `project-flat` resolves its `:target` to `machine-root-id` (mirroring the parallel-root path); mermaid renders an `<event> [guard] / <action>` line in a `note right of <root fallback>` (the F2/F5/F10 action-only-note affordance). A `:same-state` machine-level fallback stays dropped (no concrete root state to self-transition against). With F12 the targetless machine-level `:on` is faithful across all three emitters. |
| F13 | **rf2-07gg7h** ✅ — `fix(machines-viz): preserve topology entries keyed :fn in share payloads` | new | isolated (`share.cljs` + CLJS tests + this doc) | **The share sanitiser over-deleted.** F8c's `sanitise-definition` dropped EVERY map entry whose key was `:fn` (`(= :fn k) → nil`) to strip the executable slot off a co-located `{:fn <fn> …}` guard/action entry. But `:fn` is also a valid TOPOLOGY id — a state id, event id, or region id — whose VALUE is a topology submap, never a function. So a state / transition / region named `:fn` was SILENTLY removed, producing an invalid or misleading shared machine. Fix: gate the drop on `(and (= :fn k) (fn? v))` — strip ONLY the executable slot (whose value is a fn), PRESERVE any topology entry keyed `:fn` (whose value is non-fn). The privacy guarantee is intact (the live fn is still dropped/labelled, no source/path leak); a `:fn` state-id / event-id / region-id now round-trips. |
| F14 | **rf2-pdvtxt** ✅ — `fix(machines-viz): region-level targetless :on fallback topology + mermaid + Xray highlight` | new | isolated (`chart/layout.cljc`, `mermaid.cljc`, `tools/xray/.../trace_state.cljs` + JVM/CLJS tests + this doc + `xray/003`) | **The REGION-level counterpart to F12's flat-machine targetless `:on` fix.** A parallel REGION def may carry a TARGETLESS/action-only top-level `:on` (`:on {:abort {:action :log}}`) — a legal Spec 005 region-level internal fallback (XState v5: a region is an orthogonal compound state). Three surfaces mis-handled it. **(1) Chart topology** minted a PHANTOM target: `project-parallel` drops the synthetic machine-root for a region's top-level `:on` and re-points the machine-level fallback edge's SOURCE to the region container, but always region-scoped the TARGET via `(region-scoped-id region-id (:to-path e))`; a targetless edge has `:to-path []`, so the target became the degenerate `region__<id>__` — the very node it removed. Fix: anchor an internal (`:machine-level?` + `:internal?`) region fallback's TARGET to the region container too (`rid`), mirroring `project-flat`'s self-anchored machine-root internal edge. **(2) Mermaid export** dropped it: `collect-root-fallback-edges` emits only target-bearing edges; the per-region note walk covered only region STATES; the root note helper covered only the parallel-root `:on`/`:after`. Fix: a `region-fallback-internal-notes` helper emits a `note right of <region root fallback>` per region (the F2/F5/F10/F12 action-only-note affordance). **(3) Xray fired-edge highlight** missed the HANDLED-but-UNCHANGED case: `region-self-internal-fired-ids` keys on a region-SCOPED in-region source, so a region-ROOT internal fallback (container-sourced) was never lit. Fix: a `region-machine-internal-fired-ids` arm matches the container source + `:machine-level?` + `:internal?` + event (excluding `:parallel-root-on?`), reserved after the leaf self/internal arm. With F14 the targetless region-level `:on` is faithful across chart topology + Mermaid and lit by the Xray focused-epoch highlight. |

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

> **Parity verdict after Phases A–F (incl. G9 + the F5–F7 internal-
> transition faithfulness pass):** `MachineChart` reaches **full topology
> parity** with Stately Studio on all nine concerns (transition scoping now
> includes the `:on-done` / XState `onDone` completion transition — rf2-41goo
> / G9 — AND the ordinary INTERNAL action-only `:on` / `:after` / `:always`
> transition surfaced consistently across all three emitters — rf2-mnp93.4)
> except the two **intentional divergences** (no code↔diagram sync,
> trace-driven not sandbox sim) — history pseudo-states are now
> FIRST-CLASS (rf2-m285a — Spec 005 §History states; §1.4) and no longer
> a divergence — and remains
> **above** the bar on the re-frame2-native overlays (`:after` rings,
> microstep replay, `:spawn-all` join, cancellation cascade). The three
> emitters agree on internal-transition projection (rf2-mnp93.4), the SCXML
> codec round-trips an internal action transition faithfully without
> inverting it to a forbidden block (rf2-mnp93.5), and the mermaid node-id
> is injective like the chart's (rf2-mnp93.6).

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
  states; **first-class `:type :history` pseudo-states** (shallow / deep
  / `:default-target` — §History states).
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  — the `:rf.machine/*` trace vocab the trace-driven highlight + fired-
  edge mapping consume.
