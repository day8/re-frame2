# API

The consolidated user-facing surface. Implementer-readable: every
symbol a consumer of Machines-Viz might reach for.

This doc is a **reference**. The normative descriptions for the
embedding-host side of these surfaces live in
[`tools/xray/spec/003-Machine-Inspector.md`](../../xray/spec/003-Machine-Inspector.md);
where the two drift, this doc owns the **component contract** and
the **share-URL encoding**, and Xray 003 owns the **panel chrome**.

## Installation

```clojure
;; consumer deps.edn — typically transitive through Xray or Story
{:deps {day8/re-frame2-machines-viz {:mvn/version "..."}}}
```

For Xray users: pulled transitively via `day8/re-frame2-xray`.
For Story users with a machine panel: pulled transitively via
`day8/re-frame2-story`. Direct dependents (a custom dev shell that
wants a chart without the rest of Xray) declare it themselves.

## `MachineChart` component

The single chart-rendering surface. Substrate-agnostic — exported
by every substrate adapter Machines-Viz ships against (Reagent /
UIx / Helix).

### Namespace

```clojure
(:require [day8.re-frame2-machines-viz.chart :as viz])
```

### Signature

```clojure
[viz/MachineChart props]
```

`props` is a map. The component destructures the keys below with
defaults and ignores unrecognised keys; it does not run a closed-
schema reject. The component is presentation-only — the host pulls
the definition + the live snapshot and passes them in (this keeps the
chart testable in isolation and avoids coupling it to a framework
registry).

### Props

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:machine-id` | no | `nil` | Identifies the machine. Surfaces as the chart's aria-label and on every per-node `:data` payload (read by tests + hosts). |
| `:definition` | no | `nil` | The machine definition map. When `nil` the chart renders an empty-state placeholder. The component does NOT subscribe to a framework registry directly — hosts pull the definition via `(rf/machine-meta machine-id)` and pass it in. |
| `:current-state` | no | `nil` | The live snapshot `:state` value for the active-state highlight. Accepts all three Spec 005 `:state` arms: a flat keyword (`:authing`), a hierarchical path (`[:auth :authing]`), **or a parallel region-map** (`{:data :loading :form :neutral}`). For a region-map the chart lights up **every** active region leaf simultaneously (the multi-active highlight — see [§Parallel multi-active highlight](#parallel-multi-active-highlight-rf2-yoe6e-rf2-g2svr)). `nil` renders no highlight. |
| `:from-highlight` | no | `nil` | Focused-event lens origin (a `:state` value). |
| `:to-highlight` | no | `nil` | Focused-event lens landing (a `:state` value). |
| `:fired-edge-ids` | no | `#{}` | rf2-qeemm (closes parity gap **G3**). A **set** of canonical edge-ids (the EXACT scheme `chart.layout` mints) that fired **this epoch**. Each matching edge gets the FIRED treatment — emphasised + animated stroke + `data-fired` — along its routed path (coexisting with G2 bend-points + G1 active styling). Distinct from the from/to lens (`:from-highlight` / `:to-highlight`, matched by ENDPOINT state): this matches the **edge** directly, so every traversed arm (microsteps, guard-fork candidates) lights up. The host (Xray) resolves it for the focused epoch via `panels.machines.trace-state/extract-fired-edge-ids`; the ids agree with the chart by construction. The chart root surfaces the sorted set as `data-fired-edge-ids`. `nil` / `#{}` → no fired highlight. **Colour delegated to Figma** (`:accent` baseline, distinct from the focused/active `:info`). |
| `:guard-blocked-edge-ids` | no | `#{}` | rf2-fzrzlw / rf2-4nxgqq / rf2-tjm3u2. A **set** of canonical edge-ids (same scheme as `:fired-edge-ids`) whose **guard REJECTED** the event this epoch — a guard-blocked **no-op** (the runtime emitted `:rf.machine/guard-evaluated` fail/threw but **NO** `:rf.machine/transition`, so the fired set is empty for it). The matching **event-node** AND its **`__in` (source-state → event-node) half** get the guard-blocked treatment — emphasised **PINK** stroke + emphasised pink `IF <guard>` chip — which **WINS** over fired/focused/active (`theme.tokens/edge-color` ranks `blocked? > fired? > focused?/active? > quiet`), so the attempted-and-rejected arm stands out from the all-exits affordance-blue. **The highlight STOPS at the guard event-node** (rf2-4nxgqq): the **`__out` (event-node → target) half stays STATIC/resting** — a blocked no-op never reached the target, so an onward pink arrow would falsely imply the transition progressed (the `__out` topology edge still renders; only the live overlay is withheld). Without any of this a blocked no-op gives ZERO signal which edge the event hit. The host (Xray) resolves the set by **`(source-path, event, guard)`** when trace state is available — `panels.machines.trace-state/extract-guard-blocked-edge-ids` reads the active `:state` the `:rf.machine/guard-evaluated` trace carries and gates each candidate edge whose declaring `:from-path` is a PREFIX of the active path, so a sibling state reusing the same `(event, guard)` is NOT lit (rf2-tjm3u2); a trace with no `:state` falls back to the `(event, guard)` match. **DOM pins are the event-node (`data-guard-blocked`, unconditional) + the chart root (`data-guard-blocked-edge-ids`)** — NOT an edge-half attribute (see the DOM-pin note in §Events-as-nodes below). `nil` / `#{}` → no guard-blocked highlight. **SUPERSET of XState/Stately** (which highlights nothing on a block) — only possible because re-frame2 emits guard-evaluated. Colour = `:edge-guard-blocked` (pink `:magenta-pink`). |
| `:sim?` | no | `nil` | Flips the highlight palette to amber for the simulator path. |
| `:on-state-click` | no | `nil` | `(fn [path] ...)`. Fires when the user clicks a **real statechart-state node** — a **leaf state** (its body) or a **compound state** (its **title strip** only; the compound's body stays pointer-transparent so a click inside it falls through to the nested leaf). `path` is the clicked state's path. **Not** fired for the synthetic **machine-root** chip or **parallel-region** containers (no region-selection concept yet) — the projector threads the callback onto leaf + compound `:data` only (rf2-34ff3). No-op'd when `:read-only?` is true. |
| `:read-only?` | no | `nil` | When `true`, all `:on-*` callbacks are no-op'd. The viewer page sets this. |
| `:direction` | no | `:tb` | Layout axis. `:tb` (the **default**) lays the chart top-to-bottom; `:lr` left-to-right; both are fed to elkjs as `elk.direction`. **OPT-IN adaptive — `:auto`** (rf2-lamdfl + rf2-gnrkke): the default `:tb` and an explicit `:tb` / `:lr` take the historical path — the post-ELK adaptive subsystem is **NOT invoked** and the render is byte-identical to the pre-feature output. Pass **`:auto`** to OPT A MACHINE IN to (a) the **adaptive-aspect heuristic** (`chart.post-elk/aspect-direction`: a genuinely-branchy machine — a state fanning to ≥ 3 distinct targets, quiz / modal / gate — flows **landscape** `:lr`; a linear chain stays a **column** `:tb`; a parallel machine resolves `:tb` and gets the region transpose below), **plus** (b) the **post-ELK transforms** (`chart.post-elk/apply-post-elk`: the parallel-region stacking-axis transpose §4.3.2 + the back-edge return-route detour §4.3.1). A host resolves `:auto` from a machine's `:layout {:aspect :adaptive}` hint or its own layout intent. The resolved direction is fed to elkjs as `elk.direction`. The opt-in gate is the single predicate `chart.post-elk/adaptive?` (true ONLY for `:auto`). See `001-Topology-Parity.md` §4.3.1 + §4.3.2. |
| `:layout-options` | no | `nil` | Host-side elkjs `layoutOptions` overrides merged on top of `default-elk-options` (`chart.cljs/default-elk-options`). `default-elk-options` carries `elk.edgeRouting ORTHOGONAL` + `elk.json.edgeCoords ROOT` (G2 bend-point routing) plus the edge-clearance keys (`elk.spacing.edgeNode` / `elk.layered.spacing.edgeNodeBetweenLayers` / `elk.spacing.edgeEdge`) and label-placement keys (`elk.edgeLabels.placement CENTER` / `elk.spacing.edgeLabel`) so ELK routes edges *around* nodes and *places* labels in reserved channels (rf2-rlq97). The `:direction` arg drives `elk.direction`, and the chart auto-sets `elk.hierarchyHandling INCLUDE_CHILDREN` on the root layout whenever the graph nests (parallel regions or compound substates) so elk routes edges ACROSS nesting levels (parity gap G5 / rf2-gpa9k — see `chart.cljs/elk-layout-options`). |
| `:density` | no | `:regular` | Density variant — `:compact` / `:regular` / `:cosy`. Resolves the geometry + typography map via `visual-constants/chart-for-density`; the resolved map is threaded through the projector onto every node/edge `:data` so the xyflow node/edge components render at the chosen density. The chart root surfaces the resolved density as `data-density`. `nil` ≡ `:regular`; an unknown density throws at render time. Per [§Density](#density) and rf2-32gw5. **Independent of `:theme`** (geometry vs colour are orthogonal knobs). |
| `:theme` | no | `:dark` | rf2-az6e2. `:dark` / `:light`. Resolves the chart palette + the chart-semantic token map ONCE per render (`theme/tokens/theme-palette` + `chart-tokens`); the token map is threaded through the projector onto every node/edge `:data` so the renderers paint the **active theme** — not the dark-tokens alias. The chart's own chrome (canvas surface, root title strip, Context panel, dot-grid, minimap, layout-error banner) reads the same palette. The resolved theme surfaces on the root as `data-theme`. `nil` / unknown → `:dark`. **Independent of `:density`.** This bead builds no light/dark toggle UI — hosts pass the prop. Per [§Theme](#theme-rf2-az6e2). |
| `:height` | no | `"100%"` | Outer wrapper height (CSS string). xyflow requires a non-zero parent height. |
| `:show-minimap?` | no | `false` | When `true`, render xyflow's built-in MiniMap. |
| `:show-controls?` | no | `true` | When `true`, render xyflow's built-in zoom/pan/fit Controls. |
| `:show-background?` | no | `true` | When `true`, render xyflow's dot-pattern Background. |
| `:fit-signal` | no | `nil` | rf2-6tw7t. Opaque value (any `=`-comparable nonce). When its value **changes** between renders the chart re-fits the viewport to frame the whole topology — **orthogonal** to the layout-key auto-fit. Hosts bump it on **panel-entry / tab-activation** so re-entering a panel re-frames the graph rather than restoring a stale (possibly off-screen) zoom/pan. A **steady** signal across ordinary re-renders is a no-op, so the operator's manual zoom/pan still survives non-entry re-renders. See [§Fit-on-entry signal](#fit-on-entry-signal-rf2-6tw7t). |
| `:overlays` | no | `nil` | rf2-7w4qr. A **vector of host-fed overlay descriptor maps**, each keyed on `:id`. The single slot through which hosts compose the host-fed, spec+tick+callbacks overlay family (after-rings / spawn-all-join / cancellation-cascade) — collapses the former flat per-overlay props so a new overlay adds **one descriptor variant**, not 3–5 trunk props. The chart dispatches each descriptor to its already-modular rendering namespace by `:id` (`chart.cljs/render-overlay`); the renderers are unchanged. A per-descriptor `:tick` unifies the former `:after-ring-tick` + `:overlay-tick` (one rAF clock per chart stays host-owned — Lock #8 — just delivered per-overlay). A descriptor whose `:id` is outside the recognised set is **ignored** (a host data error, not a runtime fallback; dev builds emit a `js/console.warn`). Non-map entries are skipped. `nil` / `[]` → no overlays. See [§`:overlays` slot descriptor schema](#overlays-slot-descriptor-schema-rf2-7w4qr) for the multispec. |
| `:context-band` | no | `nil` | rf2-qo5xy; rf2-q129z8; rf2-3q4k5b. CLJS map fed into the Context BAND in the ROOT-CONTAINER frame header (was a top-left corner panel pre-q129z8). Host projection: either live `:data` (key→value) or the **static context shape** (key→type-caption, via Xray's `static-context-shape`), which is **declared over inferred** — authoritative off a `[:schemas :data]` schema when present, else inferred from one `:data` sample (rf2-3q4k5b). `nil` / empty → no band. See [§Context band](#context-band-rf2-qo5xy-rf2-q129z8--now-in-the-frame-header). |
| `:context-band-inferred?` | no | `true` | rf2-5tz9p; rf2-3q4k5b. Provenance gate for the Context-band badge. When `true` (default) the band shows a subtle `inferred from :data` badge — a type shape **inferred** from one sample of the definition's `:data`, **not** a declared schema and **not** the live runtime `:data`. When `false` the inferred badge is **dropped** and a positive `declared` badge shows instead; hosts pass `false` when feeding live `:data` **values** OR an AUTHORITATIVE shape off a `[:schemas :data]` schema (rf2-3q4k5b · EP-0005 · EP-0029 A3). Ignored when no band renders. See [§Context band](#context-band-rf2-qo5xy-rf2-q129z8--now-in-the-frame-header). |
| `:context-band-sensitive` | no | `#{}` | rf2-27e38h · EP-0015. A SET of Context-band keys whose VALUES are redacted to `:rf/redacted` before they reach the DOM (and therefore the SVG/PNG/clipboard export). The host derives it from the machine's `[:schemas :data]` schema `:sensitive?` slot props. See [§Context-band egress contract](#context-band-egress-contract--local-redacted-by-default-rf2-27e38h--ep-0015). |
| `:context-band-large` | no | `#{}` | rf2-27e38h · EP-0015. A SET of Context-band keys whose values are elided to the canonical content-FREE `:rf.size/large-elided` marker before export. Unmarked over-cap values elide too; sensitive wins over large. |
| `:context-band-raw?` | no | `false` | rf2-27e38h · EP-0015. The explicit trusted-local (`:rf.egress/local-raw`) opt-in. `true` skips Context-band redaction and serialises raw values — an operator act for a developer inspecting their own process. Default `false` keeps the band local-redacted. |
| `:testid` | no | `"rf-mv-chart"` | Root wrapper `data-testid` so tests + hosts can find the chart. |

### `:overlays` slot descriptor schema (rf2-7w4qr)

`MachineChart` exposes the host-fed, spec+tick+callbacks overlay family
through a **single `:overlays` slot** — a vector of descriptor maps —
rather than 3–5 flat trunk props per overlay. Mike ruled (rf2-7w4qr):
adopt the slot. Hosts compose overlays through the slot; the trunk
signature stays narrow as overlays are added.

```clojure
[viz/MachineChart
 {:machine-id    :auth
  :definition    defn
  :current-state state
  :overlays [{:id :after-rings :specs after-ring-specs :tick now-ms
              :on-hover #(…) :on-leave #(…)}
             {:id :spawn-all-join :spec spawn-all-spec :tick now-ms
              :on-child-click #(…)}
             {:id :cancellation-cascade :spec cascade-spec :tick now-ms}]}]
```

Each descriptor is a map keyed on `:id`. The chart dispatches it to its
existing rendering namespace by `:id` (`chart.cljs/render-overlay`); the
overlay renderers (`chart.overlays.after-rings` / `.spawn-all-join` /
`.cancellation-cascade`) are unchanged — only the WIRING is lifted into
the slot.

**Multispec keyed on `:id`.** Common to every descriptor:

| Key | Required | Meaning |
|---|---|---|
| `:id` | yes | The overlay kind. One of `#{:after-rings :spawn-all-join :cancellation-cascade}` (the closed set `chart.cljs/overlay-ids`). An `:id` outside this set is **ignored** — a host data error, not a runtime fallback; dev builds (`re-frame.interop/debug-enabled?`) emit a `js/console.warn`. |
| `:tick` | no | Opaque value the host bumps to force the overlay to re-measure the DOM + repaint. One rAF clock per chart stays host-owned (Lock #8 — see [§One chart-level, visibility-gated animation clock](#one-chart-level-visibility-gated-animation-clock)); the clock just delivers its tick on the descriptor(s) it drives. Replaces the former flat `:after-ring-tick` + `:overlay-tick`. |

Per-`:id` variant keys:

**`:id :after-rings`** (rf2-uv1on) — `:after`-timer countdown rings.

| Key | Required | Meaning |
|---|---|---|
| `:specs` | yes | Vector of presentation-ready ring-specs, each `{:node-id :fraction :color :cancelled? :tooltip :testid}`. When non-empty the chart mounts the `chart.overlays.after-rings` overlay as a sibling of the canvas; it walks the rendered node DOM to position each ring. Empty / absent → the overlay is dormant. The host owns the trace→spec projection + the scrubber-aware fraction. |
| `:on-hover` | no | `(fn [node-id] ...)`. Hover-enter callback the overlay wires on each ring. |
| `:on-leave` | no | `(fn [node-id] ...)`. Hover-leave callback the overlay wires on each ring. |

**`:id :spawn-all-join`** (rf2-3ow55) — `:spawn-all` join inspector.

| Key | Required | Meaning |
|---|---|---|
| `:spec` | yes | Presentation-ready join-spec — `{:node-id <string> :join <:all\|:any> :children [{:key <kw> :done? :failed? :cancelled? :note}] :resolved? <bool?> :on-all-complete :on-any-failed}`. When present (and `:node-id` is set) the chart mounts the `chart.overlays.spawn-all-join` inspector beside the spawn-all-bearing state. The host (Xray) projects the spec from its `:rf.machine.spawn-all/*` trace buffer; machines-viz owns only positioning + paint. No `:node-id` → dormant. |
| `:on-child-click` | no | `(fn [child-key] ...)`. Fires on a join-inspector child-row click; Xray pivots to the child instance. |

**`:id :cancellation-cascade`** (rf2-3ow55) — cancellation cascade waterfall.

| Key | Required | Meaning |
|---|---|---|
| `:spec` | yes | Presentation-ready cascade-spec — `{:node-id <string> :parent-label <string?> :from-state <kw?> :steps [{:kind <:exit\|:destroy\|:abort\|:cleanup> :label <string> :note :delta-ms}]}`. When present (and `:node-id` is set and `:steps` is non-empty) the chart mounts the `chart.overlays.cancellation-cascade` waterfall beneath the parent state, turning the scattered abort/destroy traces into one decision laid out vertically. The host projects the spec from the cancellation trace cluster. No `:node-id` / no `:steps` → dormant. |

A descriptor whose per-`:id` `:spec` / `:specs` is dormant (empty,
missing `:node-id`, missing `:steps`) renders nothing — the same
dormant behaviour the former flat props had when passed `nil`.

### Parallel-region rendering (rf2-lkwev, xyflow Phase 2)

A `{:type :parallel :regions {...}}` machine renders EVERY region as a
distinct orthogonal zone (Stately parity), superseding the Phase 1
first-region-only projection. Each region surfaces a synthetic
`:region?` compound container node — `chart.layout/project-definition`
mints a `region__<region-id>` node-id for it, tags each region state
with `:region` + `:parent-id`, and flags the result `:parallel? true`.
`chart.projection/xyflow-graph` projects the container as a
`type: "parallel-region"` xyflow node (rendered by
`chart.nodes.parallel-region-node` with a distinct dashed boundary +
header label whose colour rotates per region index) and assigns each
state a `parentId`/`:extent "parent"` so xyflow's sub-flow mechanic
nests the states inside the zone (rf2-xh1lm — xyflow v12 reads
`parentId`, NOT the pre-v12 `parentNode`, which is silently ignored);
elkjs lays the states out inside
each region's bounding box via `elk.hierarchyHandling
INCLUDE_CHILDREN`. The chart root surfaces `data-region-count`; region
containers are excluded from `data-node-count` + the aria-label state
count (they are zone chrome, not states).

### Container sizing — `:style {:width :height}` from ELK (rf2-a64bi)

BOTH region containers (above) AND compound containers receive a
`:style {:width :height}` slot on their projected xyflow node, sourced
from elk's measured `{:x :y :width :height}` position entry. Container
renderers (`parallel-region-node`, `compound-node`) fill their box with
`width:100% height:100%`, so the projector MUST hand xyflow the box elk
measured — otherwise xyflow falls back to
`compound-node-min-{width,height}` (220×120, the renderer's minimum DOM
size), and substates whose parent-relative coordinates elk computed
against the FULL measured extent overflow the smaller fallback and
visually escape the container. Leaf (non-container) state nodes carry
NO `:style`; xyflow sizes them from the rendered DOM
(`state-node-min-{width,height}`).

### Measure-then-relayout — ELK sizes nodes to the rendered box (rf2-d9ro2)

ELK lays the graph out assuming each node is exactly the size its input
descriptor declares (`projection/elk-child` / `elk-event-child`). A
state-node, however, renders at CONTENT size — the state label (variable
length) plus the tag-pill row plus the entry / exit action-pill rows
(rf2-a2b55) — floored only by CSS `min-{width,height}`. Feeding ELK the
fixed `state-node-min-{width,height}` floor for EVERY node (the original
single-pass) understates every node wider/taller than the floor, so ELK
budgets slots too small and neighbours OVERLAP — the missized/cluttered
topology rf2-d9ro2 fixes.

The chart therefore runs the canonical React Flow + ELK **two-pass**
(the same shape the official React Flow ELK example and xstate-viz /
Stately use):

1. **First pass.** `MachineChart` runs ELK with NO measured dims; every
   leaf + event-node seeds at its floor. Nodes mount at content size.
2. **Measure.** xyflow measures each rendered node and populates
   `node.measured {width height}`. The chart reads them back off the
   captured ReactFlowInstance (`fit-state :instance`, via `getNodes`)
   through the `read-measured-dims` seam, driven by xyflow's
   `:onNodesChange` (a `dimensions` `NodeChange`) and the `:onInit`
   fast-commit branch.
3. **Relayout.** The measured `{node-id {:width :height}}` map threads
   through `compute-layout!` → `->elk-input` → `projection/->elk-children`;
   each LEAF + event-node lays out at `(max measured floor)` per
   dimension (`projection/leaf-elk-size`). COMPOUND / region CONTAINERS
   keep the floor seed — their true extent comes from ELK laying out
   their measured children (`elk.hierarchyHandling INCLUDE_CHILDREN`), so
   self-measuring their `100%`-of-the-box DOM would be circular.
4. **Fit.** The relayout settle re-fits the viewport via the rf2-set3x
   auto-fit (it shares the `fit-key` gate), framing the corrected
   topology.

The relayout MUST fire **at most once per layout-key** and MUST NOT
loop. The `relayout-state` gate stores the measured-dims map ELK was
last fed for the current layout-key and re-runs ELK only when (a) every
ELK-measurable node (leaf states + event-nodes — not containers, not
initial-marker glyphs) has a positive measured box, AND (b) the freshly
measured map differs from the stored signature. Loop-freedom is
structural: a relayout moves node POSITIONS only — it does not change
node CONTENT — so the next measurement reports the SAME boxes, the
signature matches, and no further pass fires. Position-only
`onNodesChange` events never reach the comparison (the signature keys on
measured dimensions, not position), so xyflow applying the new ELK
positions cannot re-trigger. A new layout-key (new `:definition` /
`:direction` / `:layout-options` / `:density`) clears the signature so
the new topology gets its own single relayout.

This second pass is keyed on the SAME `[:definition :direction
:layout-options :density]` layout-key as the first — it does not
introduce a new layout-invalidation trigger (it is the completion of the
existing pass once real sizes are known), so the load-bearing
[layout-invalidation boundary](#layout-invalidation-boundary-is-load-bearing)
below is unchanged: a decoration-prop change still never reaches the
relayout path. (`:density` is a STRUCTURAL prop — it sizes ELK's
container `elk.padding` from the active density's title-strip + body-pad
constants, rf2-8q5pt — so it legitimately joins the layout-key; see the
boundary section below.)

### Sub-flow nesting — `:parentId` (NOT `:parentNode`) (rf2-xh1lm)

Every nested xyflow node (region substate, compound substate, the
initial-marker glyph that pairs with a nested initial state) MUST emit
`:parentId <container-node-id>` + `:extent "parent"` on the projected
node. The container's `:position` is root-absolute (from ELK's root
frame); the child's `:position` is parent-relative (from ELK's nested
walk). xyflow's `adoptUserNodes` then adopts the child against the
parent's measured box and reports a `positionAbsolute` of `parent.x +
child.x`, so the rendered DOM sits inside the container.

This is **`:parentId`, not `:parentNode`**. xyflow v12 renamed the
sub-flow key from the pre-v12 `parentNode` to `parentId`
(`@xyflow/system` `types/nodes.d.ts` `NodeBase.parentId`; the v12
`adoptUserNodes` walk reads `userNode.parentId` exclusively).
**A `:parentNode` slot is silently ignored** — no warning, no
deprecation notice; the node is treated as root-level and its
`:position` is read as ABSOLUTE flow coordinates. With ELK's
parent-relative coords, that puts an `:active__connecting` child at
root `(14, 34)` instead of inside `:active` at root `(36, 274)` — the
exact symptom rf2-xh1lm fixed (substates rendered top-left of canvas
while the empty `:active` container sat bottom-right).

The projection test corpus guards this two ways: every assertion uses
`:parentId` (`xyflow-graph-region-children-wire-parent-id`,
`xyflow-graph-compound-children-wire-parent-id`,
`xyflow-graph-emits-compound-substate-initial-marker`), and a dedicated
regression test (`xyflow-graph-region-children-do-not-emit-pre-v12-parent-node`)
asserts the `:parentNode` key MUST NOT appear on any projected node —
catching a re-introduction at the projector level rather than waiting
for a downstream visual-regression catch.

### Container handles — compound + region nodes carry `<Handle>` (rf2-shv82)

Container nodes (`chart.nodes/compound-node` +
`chart.nodes.parallel-region-node`) MUST render invisible source +
target `<Handle>` elements on all four sides. xyflow's edge-render
pipeline calls `getHandleBounds`, which `querySelectorAll`s the node
DOM for `.source` / `.target` classes; absent any handle the call
returns `null` → `isNodeInitialized` returns false → `getEdgePosition`
returns null → **xyflow silently drops the edge from the DOM** — no
warning, no error, no `:layout-error` slot. Every edge whose source or
target is a container (a parent-level transition like
`:active → :disconnected` declared on the compound; an inbound
`:failed → :active`; a `:active` self-loop) vanishes before render,
even though the projector emitted it and ELK routed it (the 5-layer
probe in rf2-shv82 traced 4 surviving edges through parser → projector
→ ELK-in → ELK-out, then 0 in the DOM).

The fix is small: invisible handles (`{:opacity 0}`) on the four
sides, matching `chart.nodes/state-node`'s shape. xstate/Stately
Studio paints parent-level transitions as edges anchored to the
compound's BORDER (their `toXYFlow` converter exposes containers as
edge-able for the same reason); with handles attached, ELK's routed
bend-points anchor on the compound's perimeter the way Stately does
and the parent-level intent is preserved verbatim (no projection-time
rewrite to leaf-to-leaf form, no synthetic ghost nodes).

The chart root surfaces `data-edge-count-projected` (the projector's
edge-array length) alongside the existing `data-edge-count` (the
parser's transition count) so a visual-regression test can pin
parity at every stage of the parser → projector → DOM chain — the
silent-drop bug cannot recur at any layer without failing the gate.

### Rendered-topology geometry gate (rf2-dplwxh)

The browser chart suite (`chart-dom-cljs-test`) asserts FIRST-COMMIT
DOM — node count, edge count, class/testid contract — BEFORE the async
elkjs pass resolves, so it cannot catch layout-quality regressions
(wrong fit, overlapped bands/nodes, bad route geometry, missing
projected edges, adaptive-layout drift). The Xray feature gate only
requires `nodeCount > 0`; the PNG exporter test proves nonblank output,
not topology correctness. Those failure classes were therefore
invisible to CI.

`topology-layout-gate-cljs-test` closes the gap. It drives the REAL
`chart/compute-layout!` (elkjs — the SAME engine xyflow uses as its
layout backend — runs Node-side, no DOM/React/`@xyflow/react`),
**awaits the layout settle** (the callback the synchronous DOM suite
cannot await), and asserts **post-layout geometry invariants** for the
representative machines on the topology-parity surface
(`001-Topology-Parity.md`): a linear/cyclic spine, a guarded fork, a
parallel machine, a compound machine, and a non-trivial Context-band
case. Per-machine the gate pins:

- **Fit** — every leaf state node lands at a DISTINCT, finite,
  positive-area box (not a degenerate origin-stack), and the overall
  bounding box is finite + positive.
- **No overlap** — no two leaf siblings (same coordinate frame) overlap;
  every compound / region container ENCLOSES each of its children (a
  band painted over its contents fails here).
- **No missing edges** — every projected transition has BOTH routed
  halves present (events-as-nodes splits each edge into an `__in` +
  `__out` segment), each a polyline of ≥ 2 points.
- **Route placement** — each half's polyline stays within a finite
  tolerance of the union box of its endpoint nodes (the route attaches
  to the right nodes rather than flying off into empty canvas).

Geometry invariants are used in lieu of committed pixel/screenshot
baselines: pixel baselines are cross-platform-flaky (this project's
maintainer develops on Windows, the ecosystem maintainers on
Mac/Linux), whereas elkjs's positions are deterministic Node-side and
the invariants encode the actual quality contract rather than a
snapshot of one renderer's px output.

**When changing `chart.cljs` `default-elk-options`, `chart.projection`,
the density / visual constants, or `chart.layout`, run
`cd implementation && npm run test:cljs`** — the gate runs under the
always-on `:node-test` build (its ns ends in `-cljs-test`) and fails on
any fit / overlap / routing / missing-edge regression.

### Events as nodes — Stately graph view paradigm (rf2-qo5xy)

**This is a paradigm-shift section.** Pre-rf2-qo5xy the chart painted
transitions as edge labels between state boxes: `event [guard] /
action` floated on the line. Multiple candidates, long action names,
or stacked siblings degraded legibility quickly, and action
attribution was always a second-class citizen of the line text.

Stately Studio's graph view paints each transition as its own box —
`source-state → event-node → (optional) target-state`. The event box
carries the event name (header), an optional `[guard]` chip, and `+
action` pills for action attribution. Internal transitions (no
`:target`) get the event box but no outgoing edge — they read as
"this dispatches an action and we hang here". rf2-qo5xy adopts that
paradigm: every parsed transition emits exactly one xyflow node of
`type "rf2-event"` (registered in `chart.nodes/node-types`) plus one
or two edges, with these structural rules:

| Parsed transition shape | Event-node | Inbound edge | Outbound edge |
|---|---|---|---|
| `:on {:e :target}` — regular targeted | yes | state → ev-node | ev-node → target |
| `:on {:e :same-state}` — self-target, internal DEFAULT (rf2-9dj21r) | yes | state → ev-node | ev-node → state |
| `:on {:e {:target :same-state :reenter? true}}` — self-target, EXTERNAL restart (rf2-9dj21r) | yes (`:reenter true`) | state → ev-node | ev-node → state |
| `:on {:e {:action :a}}` — INTERNAL (no `:target`) | yes (`:internal true`) | state → ev-node | **none** |
| `:after {1000 ...}` — timer | yes (`:variant "after"`, `:afterMs 1000`) | state → ev-node | ev-node → target |
| `:after {1000 {:action :a}}` — INTERNAL timer (no `:target`) (rf2-mnp93.4) | yes (`:variant "after"`, `:internal true`) | state → ev-node | **none** |
| `:always [{:target ...}]` | yes (`:variant "always"`) | state → ev-node | ev-node → target |
| `:always [{:action :a}]` — INTERNAL (no `:target`) (rf2-mnp93.4) | yes (`:variant "always"`, `:internal true`) | state → ev-node | **none** |
| Wildcard `:*` | yes (`:eventId nil`, not user-fireable) | state → ev-node | ev-node → target |

**The `:reenter?` external-restart axis (rf2-9dj21r).** A TARGETED transition
is INTERNAL by default (XState v5 / Spec 005 §Self-transitions): a self /
ancestor / compound-declared-descendant target does NOT re-run the target's
own `:exit`/`:entry`. Only `:reenter? true` makes it EXTERNAL — re-running
`:exit`+`:entry` and restarting the target's `:after` timers + `:spawn`
children. Two such transitions are RUNTIME-DISTINCT, so the viz represents
the axis end-to-end: `chart.layout` carries `:reenter?` on the parsed edge
(and folds it into the edge id so the with/without pair mints DISTINCT ids),
the event-node `:data` exposes `:reenter` (the renderer paints a `↻` chip on
the header), the Mermaid emitter appends a `↻` marker to the edge label, and
the SCXML codec maps the axis onto W3C SCXML's native `<transition
type="external">` (emit + lossless round-trip on import). Pre-fix the
with/without forms produced identical chart topology, Mermaid, and SCXML.

The event-node carries everything the legacy edge `:data` used to
carry: `:eventLabel` (the `chart.layout/event-segment` glyph-aware
text), `:guard` (string), `:action` (string), `:variant` (`:on` /
`:after` / `:always`), `:eventId` (raw fireable keyword for the
on-chart sim — nil for `:after` / `:always` / wildcard), `:fromPath`
/ `:toPath`, `:focused`, `:fired`, `:guardBlocked` (rf2-fzrzlw — the
guard-blocked no-op marker; drives the PINK border + emphasised pink
`IF <guard>` chip), `:internal`, `:reenter` (rf2-9dj21r —
the external-restart axis; drives the `↻` reenter chip), `:machineLevel`,
`:guardRequires` / `:actionRequires` (rf2-skhlw2.1 — the named guard /
action consumer's declared **`:rf.cofx/requires`** as a vector of compact
id strings; EP-0017 consumer attachment — the replay-critical host facts
the callback reads before it runs; nil when the named callback declares no
facts, so an ordinary fact-free transition is visually unchanged; **ids
only — never the executable `:fn` nor any `:source-*` snippet**; drives the
quiet `needs <id>` chip + the `data-guard-requires` / `data-action-requires`
DOM pins), `:onClick` (the host-supplied callback), `:afterMs`, `:chart`
(resolved visual-constants).

**State-node consumer attachment (rf2-skhlw2.1).** A state node's `:data`
carries `:entryRequires` / `:exitRequires` — the `:entry` / `:exit`
lifecycle action's declared `:rf.cofx/requires` (same compact-id-string
vector, same ids-only contract), driving a quiet italic `needs <id>` line
under the entry / exit action row + the `data-entry-requires` /
`data-exit-requires` DOM pins. nil when the lifecycle action declares no
facts. Resolution is machine-scoped: `chart.layout/attach-cofx-requires`
resolves each guard / action / entry / exit ref against the machine
definition's top-level `:guards` / `:actions` named-entry registries
(XState v5 / Spec 005 §Registration — the machine is the event handler), so
the one registry pair covers flat, compound, AND parallel machines.

**Share / export handling (rf2-skhlw2.1).** A **share URL preserves** the
safe `:rf.cofx/requires` metadata: `share/sanitise-definition` drops the
entry's `:fn` / `:source-*` but keeps the requires vector (a plain vector of
coeffect-id keywords), so the decoded definition re-derives the chart's
`needs <id>` chips on the receiving side. **AI-generate is lossless by
construction** (it returns a full machine spec; nothing strips a
`:rf.cofx/requires` slot a generated `:guards` / `:actions` entry carries).
**Mermaid + SCXML INTENTIONALLY omit** the requires diet (documented +
test-locked lossy omission): Mermaid state-diagram syntax has no slot for
per-callback coeffect metadata, and W3C SCXML has no native attribute for it
(a re-frame2 import re-attaches it from its own registry) — both carry only
guard / action NAMES, and the interactive MachineChart is the EP-0017 "which
transitions consume which facts" surface.

The inbound + outbound edges carry the structural / styling state
that used to live on the single state→state edge: `:active`,
`:focused`, `:fired`, `:guardBlocked` (rf2-fzrzlw / rf2-4nxgqq — the
PINK guard-blocked stroke covers the `__in` source→event-node half ONLY;
the `__out` event-node→target half carries `:guardBlocked false` because a
guard-blocked transition is a no-op that never reached the target, so the
highlight STOPS at the guard event-node and the onward arrow stays resting),
`:crossHierarchy`, and elk-routed `:points`. Edge
ids: `<spec-edge-id>__in` / `<spec-edge-id>__out`. Event-node ids:
`event__<spec-edge-id>` via `chart.projection/event-node-id`. The
`:eventLabel` slot on these edges is empty (the event-node holds the
visible text).

> **Edge-half `:guardBlocked` is projection/rendering data, NOT a DOM pin
> (rf2-bdwolc).** The per-half `:guardBlocked` flag drives the half's
> STROKE + arrowhead colour (the `__in` half goes pink, the `__out` half
> stays resting). It is *not* surfaced as a `data-guard-blocked` DOM
> attribute on the edge halves: `chart.edges/transition-edge` emits the
> edge-label `<div>` (and its `data-guard-blocked` / `data-fired` /
> `data-active` attrs) **only when the edge carries a non-empty
> `:eventLabel`** (`(when (seq label) …)`), and under events-as-nodes the
> `__in` / `__out` halves carry an EMPTY label (the text rides the
> event-node). So the structural halves render no edge-label element and
> no edge-half `data-guard-blocked` attribute exists to read. The
> CANONICAL guard-blocked DOM pins are the **event-node**
> (`[data-testid^="rf-mv-chart-event-"]` carries `data-guard-blocked`
> unconditionally) and the **chart root**
> (`data-guard-blocked-edge-ids`). A DOM test asserting the per-half
> blocked highlight reads the `:guardBlocked` projection value (e.g. via
> a projection unit test), not an edge-half DOM attribute.

elk routes each of those two segments independently, so the
`:edge-points` map `chart.cljs/elk-result->positions` produces is keyed
by the **elk edge id** — `<spec-edge-id>__in` and `<spec-edge-id>__out`
— and the projector looks up each xyflow edge under its OWN id: the
inbound edge draws the `__in` route (source-state → event-node) and the
outbound edge draws the `__out` route (event-node → target-state)
(rf2-r636q). Each edge with no matching route entry (or a self-loop)
falls back to the bezier. *(Before rf2-r636q the consumer looked up the
bare `<spec-edge-id>`, which the producer never emits post-rf2-qo5xy, so
G2 routing was silently dead — every edge fell back to a straight
bezier.)*

**Edges + labels are ELK-routed/-placed, not renderer-drawn
(rf2-rlq97).** The edges are *fed into* the ELK graph
(`chart.projection/->elk-edges` — the pure projector lifted out of
`chart.cljs/->elk-input`, JVM-pinnable like the node feed), and the root
`default-elk-options` carry the edge-clearance keys
(`elk.spacing.edgeNode` / `elk.layered.spacing.edgeNodeBetweenLayers` /
`elk.spacing.edgeEdge`) so the `ORTHOGONAL` routing goes *around* node
boxes instead of clipping them (closes the "arrows route over states"
class d9ro2's node-measure did not). ELK also owns edge-LABEL
PLACEMENT: `default-elk-options` sets `elk.edgeLabels.placement CENTER`
+ `elk.spacing.edgeLabel`, `->elk-edge` feeds each edge a `:labels`
entry (carrying the MEASURED label box — the edge-label analogue of
d9ro2's node measure — when an edge renders its own label), and
`elk-result->positions` lifts ELK's computed position into the
`:edge-labels` map (`{elk-edge-id {:x :y}}`, the LABEL analogue of
`:edge-points`). The projector threads it onto the edge `:data
{:labelPos}` and `chart.edges/edge-path` anchors the label THERE
instead of the old middle-segment-midpoint heuristic. **Under
events-as-nodes the transition text rides on the event-NODE** (already
ELK-measured + -placed via `elk-event-child` + the d9ro2 measure pass),
so the `__in` / `__out` edges carry an empty label, `:edge-labels` is
empty, and the geometric midpoint anchor remains only as the
no-ELK-label fallback. There is also no live self-loop edge to special-
case: a spec self-transition projects as `state → event-node → state`
(two ordinary edges, both ELK-routed), so the renderer-side self-loop
fan + the cross-hierarchy source-bend anchor in `chart.edges` are
fallback-only paths, never hit by the live chart.

elkjs lays out event-nodes as siblings of their source state's
parent container: an event declared inside a compound substate nests
inside that compound; a region-local event nests inside its region
container. The layout engine treats them as ordinary layered-graph
children with explicit incoming/outgoing edges — no new layout
algorithm.

**Initial-state placement is a SOFT preference (rf2-ly51l).** A
statechart graph is almost always cyclic (resets, retries, back
transitions, machine-level `:on` fallbacks all loop back toward the
initial state), and the Layered algorithm must first reverse a set of
edges to make the graph acyclic before it ranks layers. `default-elk-
options` sets `elk.layered.cycleBreaking.strategy DEPTH_FIRST`: cycles
break by a depth-first walk **from the sources** (the initial state — its
only incoming edge is the entry marker) rather than GREEDY's
min-reversed-edge-count, so the natural forward spine starting at the
initial state ranks near the top (`:tb`) / left (`:lr`). The
preference also rides the child **model order**: `projection/order-state-
children` floats the `:initial?` state to the front of each container's
ELK children (and sinks the synthetic machine-root annotation to the
back), so DEPTH_FIRST's source selection + the LAYER_SWEEP within-layer
tiebreak both prefer the initial state. This is a **bias, not an
invariant**: only the cycle-reversal SET changes — full crossing-
minimisation + Brandes-Köpf node placement still run, so ELK remains
free to arrange a state off the initial-on-top ideal when crossings /
spacing demand. An already-acyclic graph has no cycle to break, so
DEPTH_FIRST is layout-identical to the prior GREEDY for the common
linear case. (`forceNodeModelOrder` — the HARD variant — is deliberately
NOT used: real machines exist where forcing the initial top/left worsens
the layout.)

#### Convention glyphs (rf2-qo5xy)

The event-node header renders the variant glyph an operator who
knows xstate/Stately reads in 30 seconds (per the bead's §Shift 4):

| Glyph | Variant | Source |
|---|---|---|
| `↳`   | initial-marker | `chart.nodes/initial-marker` (paired with the filled-dot source) |
| `⌚ <ms>ms` | `:after`-delay event-node | `chart.layout/event-segment` |
| `∞`   | `:always` event-node | `chart.layout/event-segment` |
| `+ <name>` | action pill (entry / exit / transition) | `chart.nodes/action-pill` + `chart.nodes.event-node` |
| `[name]` | guard chip | `chart.nodes.event-node` |
| filled dot | initial-marker source | `chart.nodes/initial-marker` |
| `◆ root` pill | machine-root chip — the SINGLE source of a machine-level (top-level `:on`) fallback (rf2-vcnvj) | `chart.nodes/machine-root-node` (type `"machine-root"`, node-id `chart.layout/machine-root-id`) |
| named frame box | root-container frame (rf2-q129z8) — the Stately-style NAMED box wrapping the whole machine; its header carries the machine name + Context band | `chart.nodes/root-container-node` (type `"root-container"`, node-id `chart.layout/root-container-id`) |

#### Active state on box border

The active-state affordance lives on the state-box BORDER (the
state-node's `:border` colour + `:box-shadow` ring), not on
duplicate text tags. Compound and region containers light the same
way when any descendant leaf is active (G4 / rf2-80rm2, preserved
under the paradigm shift). The legacy "tags row inside the state
box" (rf2-a2b55) is unchanged — those are user-declared semantic
`:tags`, distinct from active-state highlight.

#### Context band (rf2-qo5xy; rf2-q129z8 — now in the frame header)

The chart accepts an optional `:context-band` prop — a `(key, value)`
map painted read-only so the operator sees the machine context without
leaving the chart. The band is presentation-only — the host owns the
projection; the chart paints whatever shape it receives. nil / empty →
no band.

**rf2-q129z8** — the Context is no longer a `position:absolute` panel
welded to the canvas's top-left corner. It is now the Context BAND in
the synthetic ROOT-CONTAINER frame's HEADER (under the machine-name
title strip), threaded onto the frame node's `:data {:context}` by
`chart.projection/xyflow-graph` and painted by
`chart.nodes/root-container-node`. The Context now rides INSIDE the
frame that hugs + tracks the topology, eliminating the corner-pinning +
the old top-left chrome collision.

Two host projections feed it:

- **Live context** — the machine's current `:data` map (the
  `:rf.machine/data` slot from the snapshot), `(key → value)`.
- **Static context shape** (rf2-vcnvj; rf2-3q4k5b) — when no live
  snapshot is in hand (the Static-Machines blank-state topology), the
  Xray topology path derives `(key → type-caption)` via
  `topology-view/static-context-shape` (which delegates to the
  machines-viz `context-shape/static-context-shape` helper), so the
  root Context chrome renders the context KEYS + their TYPE shape (e.g.
  `{:opened-count "number" :trail "vector"}`). The shape is **declared
  over inferred** (rf2-3q4k5b · EP-0005 · EP-0029 A3): if the machine declares a
  `[:schemas :data]` schema (a Malli `[:map …]`), the shape is read AUTHORITATIVELY
  off the schema; otherwise it is INFERRED from one sample of the
  initial `:data`. This satisfies the rf2-vcnvj acceptance "root
  title/context chrome at the top when context shape is available"
  without a live runtime read.

**Provenance badge** (rf2-5tz9p; rf2-3q4k5b) — the Context band paints a
small badge beside the **Context** header marking the shape's
provenance, gated by the optional `:context-band-inferred?` prop
(**default `true`**):

- **`:context-band-inferred? true`** (the default; absent
  `[:schemas :data]` schema) — a subtle italic `inferred from :data` badge
  (`rf-mv-chart-root-container-context-inferred-<id>`). A type shape
  derived from one sample of the initial `:data` is **not** a declared
  schema and can mislead when the initial `:data` is partial or
  unrepresentative.
- **`:context-band-inferred? false`** — the inferred badge is **dropped**
  and replaced with a positive `declared`
  (`rf-mv-chart-root-container-context-declared-<id>`) badge. Two hosts
  pass false: one feeding **live `:data` values** (key→value), and — per
  rf2-3q4k5b — the Static path when the shape is AUTHORITATIVE off a
  declared `[:schemas :data]` schema (EP-0005's declared-over-inferred upgrade,
  closing the deferred `rf2-wto1k` option A). The badge distinguishes
  an authoritative/declared shape from the one-sample inference.

#### Context-band egress contract — local-redacted by default (rf2-27e38h · EP-0015)

The Context band is painted into the live viewport DOM, and the
**image exporters serialise that DOM**: `export/chart-as-svg` clones the
live `.react-flow__viewport` into a `<foreignObject>`, and the PNG +
clipboard-image lanes derive from that SVG. A framework-created
export/copy artefact is **egress** per
[EP-0015](../../../docs/EP/EP-0015-frame-owned-egress-policy.md) §96-110.
So when a host feeds **live `:data` VALUES** (the `:context-band-inferred?
false` value path above), a schema-marked sensitive or large slot would
otherwise be embedded in the exported SVG/PNG/clipboard verbatim.

The chart therefore applies a **local-redacted projection
(`:rf.egress/local-redacted`) to the Context band by default**, at the
single `chart.projection/xyflow-graph` projection chokepoint — so the
redaction is identical for the on-screen band AND every export derived
from it. The host declares which slots carry sensitive/large content
(the machine's `[:schemas :data]` schema `:sensitive?` / `:large?` per-slot props —
the EP-0005 mechanism; `context-redaction/derive-classification` extracts
them) via two optional props:

| Prop | Default | Meaning |
|------|---------|---------|
| `:context-band-sensitive` | `#{}` | A SET of band keys whose VALUES are redacted to the `:rf/redacted` sentinel before they reach the DOM/export. |
| `:context-band-large` | `#{}` | A SET of band keys whose values are elided to the canonical content-FREE `:rf.size/large-elided` marker (size diagnostic only — no content head). An unmarked value over a defensive char cap is elided too. |
| `:context-band-raw?` | `false` | The explicit **trusted-local** (`:rf.egress/local-raw`) opt-in. When `true`, redaction is skipped and the raw values are painted/serialised. An operator act, for a developer inspecting their own process. |

Sensitive **wins over** large (the EP-0015 ordering). The redaction
defaults are **on** (`:context-band-raw? false`, empty sets), so:

- A host feeding the **static type-caption shape** (the sole production
  feeder today — `topology-view/static-context-shape`, key→type-caption)
  is a redaction **no-op**: captions like `"string"` are neither
  sensitive markers nor large, so the production surface is unchanged.
- A host feeding **live values** gets local-redacted output unless it
  both declares the classification AND opts into raw.

The contract answers the "must `:context-band` be pre-projected?"
question: **the host MAY pass raw live `:data` and declare the
sensitive/large slots; the chart applies the local-redacted projection
itself.** A host that has already projected its own values may pass an
empty classification (the values are then treated as non-sensitive). The
sentinels render as content-free text (`🔒 :rf/redacted`, `… :rf.size/large-elided
{:bytes N}`) in both the band and any export.

#### Legacy paradigm sections below

The remaining sections in this spec (label collapse, edge label
geometry, cross-hierarchy label placement, etc.) describe the
RENDERING of the inbound / outbound edges in the events-as-nodes
paradigm. The structural model `source-state → event-node →
target-state` supersedes the pre-rf2-qo5xy single-edge model in
every other respect; where the legacy sections describe edge
behaviour, read "edge" as "inbound or outbound edge attached to an
event-node" unless explicitly stated otherwise.

### Multi-event label collapse — SUPERSEDED and RETIRED (rf2-j10sm Phase 2, B → rf2-qo5xy → rf2-o6vh7)

> **SUPERSEDED + RETIRED.** rf2-j10sm (Phase 2, B) once collapsed N
> transitions sharing a `[source target]` pair into ONE arrow with N
> vertically-stacked labels (the old xstate/Stately "multiple events
> on one transition" rendering), assigning each edge a `:siblingIndex`
> / `:siblingCount` and suppressing the SVG path on every non-leader.
>
> The rf2-qo5xy **events-as-nodes** paradigm supersedes that model:
> each event projects as its own first-class `rf2-event` node, so N
> transitions on one `[source target]` pair emit N **distinct**
> event-nodes (no collapse, no grouping, no leader/follower). rf2-o6vh7
> RETIRED the dead collapse machinery: `:siblingIndex` / `:siblingCount`
> no longer appear on any edge `:data`, and the renderer carries no
> `data-sibling-*` attrs or path-suppression. The legibility rationale
> (N transitions on one source/target pair) is honoured in the new
> paradigm by each event-node being its own scannable box. Same
> `[source target]` event-node grouping, if ever wanted, would be a new
> explicit feature — it is not reimplemented here.

### Self-loop fan — `:loopIndex` (rf2-shv82 Issue 2 — REMOVED, rf2-hstzzj)

> **REMOVED (rf2-hstzzj).** The historical multi-self-loop perimeter fan
> (rf2-shv82, Issue 2) rotated N self-loops around a node's perimeter so
> their labels did not stack. The rf2-qo5xy events-as-nodes paradigm
> superseded it: a self-transition projects as `state → event-node →
> state` (two ordinary ELK-routed edges), so multiple self-loops on one
> state are multiple distinct event-nodes — there was never a fan or a
> collapse to reconcile. The projector emitted `:selfLoop false` on every
> edge, so the fan geometry (`self-loop-geometry`, the `:selfLoop` /
> `:loopIndex` edge-`:data` keys, the `edge-path` self-loop branch, and
> the `data-loop-index` DOM attr) was unreachable dead code; rf2-hstzzj
> pruned it. (The rf2-o6vh7 retirement had already removed the related
> `:siblingIndex` / `:siblingCount` collapse machinery.)

### Label rendering — padded backgrounds (rf2-j10sm Phase 1, D)

> **Mostly moot under events-as-nodes (rf2-qo5xy).** The event /
> guard / action text rides on the event-NODE; the structural in/out
> edges carry an empty `:eventLabel`, so most edges paint no label.
> The rare labelled edge (a directly-constructed edge) still paints a
> padded backplate per the geometry below.

A painted edge label uses an **opaque chip backplate** (the active
theme's `:event-chip-bg` token — the same neutral fill the event-node
route chip uses) and **light text** (`:text-primary`) on top, so a
label sitting over a node, an edge, or another label visually "punches
through" with the surrounding canvas colour rather than merging into
the ink below.

Geometry is invariant across densities:

| Style key | Value | Why |
|---|---|---|
| `border-radius` | 4px | Softens the chip without reading as product chrome (matches the chart's `:corner-radius` family). |
| `padding` | `2px 6px` | Adequate breathing room for the label glyphs at the regular chart-floor font sizes. |
| `border` | `1px solid :event-chip-border` (idle) / `1px solid :sim` (clickable, host-fed sim) | Idle labels read as a "card" against the canvas; clickable labels stay distinct from inert auto edges (`:after` / `:always`). |
| `background` | `:event-chip-bg` (theme token) | An opaque neutral chip fill so adjacent labels don't form a solid stacked tile and the text punches through the ink below. |
| `color` | `:text-primary` | Light text on dark canvas — matches the chart's text-on-bg posture. |
| `z-index` | 5 | Above edges + node borders, below xyflow's `Controls` chrome (which renders at z 6+). |

The backplate fill is a flat theme token (`:event-chip-bg`), not a
density-scaled opacity — its contrast budget doesn't scale with type
size, so it reads the same across the three densities. (rf2-dt5b1 — the
unread `:edge-label-backplate-opacity` density key it once referenced
was removed; the renderer always painted the opaque token, never an
alpha-scaled `:bg-1`.)

### Cross-hierarchy label placement (rf2-shv82, Issue 3)

A cross-hierarchy edge — one whose source and target sit in different
parent containers (e.g. testdeck `:active.authenticating → :failed`)
— has an ELK-routed midpoint that can land far from where the user
perceives the edge to originate (the bug: the label sat at the
canvas bottom-left, nowhere near `:authenticating`). xstate/Stately
Studio's convention is to anchor the label NEAR the source-side bend
point (just outside the container the edge exits), so the label
visually tracks the edge's origin.

The projector flags an edge `:crossHierarchy true` when its source
and target have different `:parent-id`s (computed from the parsed
nodes' parent-id map; self-loops are never cross-hierarchy
regardless of nesting). `chart.edges/edge-path`, when given a routed
multi-point path with `cross-hierarchy?` true, anchors the label
near the first bend after the source handle (with a small back-bias
along the incoming segment so the label sits in the routed channel,
not on top of the bend itself). A degenerate two-point route falls
back to the segment midpoint (no bend to anchor on); the bezier
fallback is unchanged.

The chart surfaces `data-cross-hierarchy` on each transition edge
label so DOM tests + hosts can pin the convention.

### Post-render label-collision avoidance — RETIRED (rf2-0xbgx)

There is no post-render label-collision pass. Under elk layout +
events-as-nodes (rf2-qo5xy) the event/guard/action label rides on the
event **node**, so label-on-node-body collisions are a non-issue and
elk reserves a channel for the rare painted edge label. The historical
`LabelCollisionsOverlay` sweep (rf2-r7vsr) was removed in rf2-0xbgx.

### Parallel multi-active highlight (rf2-yoe6e, rf2-g2svr)

> Closes parity gap **G1** in
> [`001-Topology-Parity.md`](001-Topology-Parity.md) §3.1 — the single
> most concrete missing capability vs Stately. Stately lights up
> **every** active region of a parallel state at once
> ([§1.2 parity bar](001-Topology-Parity.md)); this contract makes
> `MachineChart` do the same.

A `{:type :parallel}` machine's snapshot `:state` is a **region-map** —
`{region <keyword-or-path>}` — with **N simultaneously-active leaves**,
one per region (Spec 005 §Snapshot shape, third arm). The chart marks
**every** active leaf `:active` at once, so a reader sees the active
state in each region simultaneously (not a single arbitrary highlight).

The resolution is pure and JVM-tested, in two layers:

- **`chart.layout/highlight-ids`** — the resolver. Takes the whole
  snapshot `:state` and returns a **set of active-leaf node-ids**:
  - flat keyword (`:authing`) → `#{(node-id [:authing])}` — singleton.
  - hierarchical path (`[:auth :authing]`) → `#{(node-id [...])}` — the
    singleton holding the **deepest leaf** (`node-id` of the full path).
  - **region-map** (`{:data :loading :form :neutral}`) → the **set of N
    leaves**. Each region value is a keyword-or-path **relative to that
    region's own state-tree**, resolved via `region-scoped-id` of the
    region + the in-region path (rf2-wnzha — the parse REGION-SCOPES a
    region state's node-id, prefixing it with the region container id, so
    two regions sharing a state NAME mint DISTINCT ids; the resolver mints
    the same scoped id). A **nested region value** (a region whose value
    is itself a vector path) resolves to its **deepest leaf**, exactly as
    the single-compound case does.
  - `nil` / anything else → the empty set.

  It **subsumes** the single-active `chart.layout/highlight-id` — for a
  scalar `:state`, `highlight-ids` is exactly `#{(highlight-id state)}`.
  `highlight-id` is retained for the focused-event lens
  (`:from-highlight` / `:to-highlight`), which is genuinely single-state.

- **`chart.projection/xyflow-graph`** threads the set as the
  `:highlight-ids` option; a node is `:active` when its id ∈ the set. A
  flat / compound snapshot resolves (via `highlight-ids`) to a singleton
  set, so `:highlight-ids` is the single active-state option (rf2-hstzzj
  removed the legacy scalar `:highlight-id` convenience). An edge is
  `:active` when its **source** state is in the active set (rf2-vd3q1i —
  **source-active only**, not incident-to-active): the outgoing
  "transitions available from here" fan lights, while an **incoming**
  edge whose only active endpoint is its target stays quiet. Each
  region's outgoing edges light independently (orthogonality preserved).
  The actually-traversed edge of a transition lights separately via
  `:fired` (matched by edge-id, direction-agnostic), so the source-active
  rule loses no "what just happened" cue.

The chart root surfaces the full active set as `data-highlight-ids` (a
sorted, space-joined list of node-ids) — one id for a flat / compound
snapshot, N for a parallel one. (The focused-event lens surfaces
separately as `data-from-highlight-id` / `data-to-highlight-id`.)

**Colour delegated to Figma.** This contract fixes *which* nodes are
active and that *all* of them light up at once; the active palette + the
"reads as a set" region-active chrome are owned by Figma per
[`001-Topology-Parity.md`](001-Topology-Parity.md) §4.2 (the
region-ACTIVE chrome polish is the follow-on G4 / N1, not this bead).

### Substrate adapters (rf2-yg9he, xyflow Phase 2)

The xyflow `MachineChart` is a Reagent component, but xyflow IS React,
so the chart bottoms out at a React element tree.
`reagent.core/reactify-component` lifts it to a plain React class any
host mounts. The shared bridge `adapters.react-chart` reactifies once
(`MachineChartReactClass`) + exposes `chart-element` (CLJS props map →
React element); thin per-substrate shells present an idiomatic surface:

```clojure
;; UIx host
(:require [day8.re-frame2-machines-viz.adapters.uix :as mv-uix])
($ mv-uix/MachineChart {:machine-id :auth/flow :definition defn})

;; Helix host
(:require [day8.re-frame2-machines-viz.adapters.helix :as mv-helix]
          [helix.core :refer [$]])
($ mv-helix/MachineChart {:machine-id :auth/flow :definition defn})
```

All three substrates render the SAME component through one bridge —
there is no per-substrate fork of the chart.

The chart's click surface is `:on-state-click`, invoked with the
clicked node's `path`; the host resolves source coords for that path
against `(rf/machine-meta machine-id)` and opens the editor (per
[Xray 003 §Source-coord integration](../../xray/spec/003-Machine-Inspector.md#source-coord-integration)).
The overlay callbacks — the `:on-child-click` on the `:spawn-all-join`
descriptor and the `:on-hover` / `:on-leave` on the `:after-rings`
descriptor (under the `:overlays` slot) — fire with the relevant
child-key / node-id.

### What renders

For the supplied `:definition`, the chart shows:

- **A directional state-chart — events-as-nodes paradigm
  (rf2-qo5xy).** State boxes are first-class nodes (compound states
  nested visually via xyflow sub-flows; `{:type :parallel}` machines
  render every region as a distinct orthogonal zone — see
  [§Parallel-region rendering](#parallel-region-rendering-rf2-lkwev-xyflow-phase-2)).
  Each transition projects as ITS OWN `rf2-event` xyflow node sitting
  between the source state and the (optional) target state, the
  paradigm Stately Studio's graph view paints. The event-node carries
  the event header (`event-id`, or `⌚ <ms>` for `:after`, or `∞` for
  `:always` per `chart.layout/event-segment`, rf2-a2b55), an optional
  `[guard]` chip, and a `+ <action>` pill row when the transition
  declares an action. Internal transitions (omit `:target`) emit an
  inbound edge into the event-node but no outbound (Stately
  convention — "runs an action and we hang here").
- **The current state highlights.** When `:current-state` is set every
  active leaf carries a static active tint + bolder stroke. A flat /
  compound snapshot has one active leaf; a **parallel** snapshot (a
  region-map) has **N** simultaneously-active leaves and **every one**
  lights up at once (see [§Parallel multi-active highlight](#parallel-multi-active-highlight-rf2-yoe6e-rf2-g2svr)).
  The whole `:state` value resolves to the set of active node-ids via
  `chart.layout/highlight-ids`; compound states' active child resolves
  to the deepest leaf. The highlight is a static affordance (the
  heartbeat pulse was retired 2026-05-20 per rf2-2sez0); the only
  continuous animation is the `:after` countdown rings overlay, which
  pauses when the chart is backgrounded.
- **Focused-event lens highlights.** `:from-highlight` / `:to-highlight`
  tint the origin + landing nodes of a focused transition; `:sim?`
  flips that palette to amber for the simulator path.
- **`:final?` states.** Rendered with a quiet doubled border (outer
  ring proud of the corner) on `chart.nodes/state-node`. **No glyph** —
  the prior `✓` check glyph was dropped (rf2-az6e2); the doubled border
  is the unambiguous final-state signal.
- **State tags render as a visible pill row + hover tooltip
  (rf2-a2b55; rf2-so5b0).** A state's declared `:tags` set (Spec 005
  §State tags) renders as a row of **neutral** pills (one structural
  fill `:container-header-bg` + `:state-border` border + `:text-
  secondary` text) positioned BELOW the state name (Stately graph view
  convention). Structure wins over annotation colour for the topology
  view, so the chart reads as containment + transition flow, not a
  rainbow of tag hues (rf2-az6e2; the deterministic per-tag colour
  rotation `tag-pill-color` / `tag-pill-palette` it once used was
  removed rf2-dt5b1 — it coloured nothing the renderer paints). Each
  pill carries a `rf-mv-chart-state-tag-<name>` testid + a `data-tag`
  attr preserving the declared tag identity. In parallel, the whole set
  surfaces on the state-node's `title` attr (native HTML hover tooltip)
  + `data-tags` attr (sorted space-joined string for DOM tests + host
  introspection) — the rf2-so5b0 host-introspection contract. (The
  rf2-so5b0 retirement of the visible row was reverted by rf2-a2b55
  on a paradigm-matched look at Stately's graph view, which paints
  the row.)
- **Entry / exit actions render as `+ <name>` (entry) / `- <name>`
  (exit) pills (rf2-a2b55).** Declared `:entry` / `:exit` state
  actions surface as action pills BELOW the tag row inside the state
  box (Stately graph view `Entry actions` convention; replaces the
  prior `entry / <name>` text rows from rf2-ee38b.21). Each pill
  carries an `rf-mv-chart-state-entry` / `rf-mv-chart-state-exit`
  testid + the action name on a `data-entry` / `data-exit` attr.
- **`:after` countdown rings (overlay, host-fed).** When the host
  passes an `{:id :after-rings :specs <vec> …}` descriptor in the
  `:overlays` slot with a non-empty `:specs` vector the chart mounts the
  `chart.overlays.after-rings` overlay as a sibling of the canvas; it
  walks the rendered node DOM to position a filling arc on each source
  state. The host owns the trace→spec projection and bumps the
  descriptor's `:tick` to repaint at up to 60Hz when the chart is visible
  (per [DESIGN-RATIONALE Lock #8](./DESIGN-RATIONALE.md)). The chart
  emits no `:spawn` / `:spawn-all` edge of its own.
- **`:spawn-all` join inspector (overlay, host-fed).** When the host
  passes an `{:id :spawn-all-join :spec <map> …}` descriptor in the
  `:overlays` slot the chart mounts the `chart.overlays.spawn-all-join`
  inspector beside the spawn-all-bearing state, showing the spawned
  children + the join state. There is deliberately no `spawn` topology
  edge — `:spawn` / `:spawn-all` are state-entry actions, so spawned
  children surface through this overlay, not as a row of nodes (per
  the [§Events as nodes](#events-as-nodes--stately-graph-view-paradigm-rf2-qo5xy)
  paradigm — every projected edge is the canonical `transition` type
  and the parser emits no spawn edge — and
  [Xray 003 §`:spawn-all` viz](../../xray/spec/003-Machine-Inspector.md#spawn-all-viz)).
- **Cancellation cascade (overlay, host-fed).** When the host passes an
  `{:id :cancellation-cascade :spec <map> …}` descriptor in the
  `:overlays` slot whose `:spec` carries non-empty `:steps` the chart
  mounts the `chart.overlays.cancellation-cascade` waterfall beneath the
  parent state.

What does **not** render (the chart is presentation-only — the host
supplies the data and decides on the callbacks):

- Transition history ribbon (Xray's chrome; lives in
  `tools/xray/`).
- Source-coord chips with editor-URL handler wiring (the chart fires
  `:on-state-click`; the host opens the file).
- A machine picker dropdown (the host owns machine selection and pulls
  `:definition` + `:current-state` itself).
- Microstep flashes for an `:always` cascade — the shipped chart renders
  `:always` transitions as plain `∞`-labelled edges; per-microstep
  flash animation is not part of the presentation-only component.

### Data sources

The chart is **presentation-only**: it consumes nothing from the
framework registry or the trace bus directly. The host (Xray, Story,
the viewer page) reads the framework's public surfaces and projects
them into the chart's props. The table below maps each framework
surface to the prop the host derives from it.

| Framework surface (host reads) | Chart prop the host derives |
|---|---|
| `(rf/machine-meta machine-id)` | `:definition` — the registered topology (states, transitions, guards, actions). |
| `[:rf.runtime/machines :snapshots <id>]` slot in the frame's runtime-db | `:current-state` — the live `:state` driving the active highlight. |
| `:rf.machine/transition` trace events | `:from-highlight` / `:to-highlight` — the focused-event lens. |
| `:rf.machine.timer/scheduled` / `-fired` / `-stale-after` | an `{:id :after-rings :specs … :tick …}` descriptor in `:overlays` — the countdown-ring overlay. |
| `:rf.machine.spawn-all/started` / `-all-completed` / `-some-completed` / `-any-failed` | an `{:id :spawn-all-join :spec … :tick …}` descriptor in `:overlays` — the join inspector overlay. |
| cancellation trace cluster (`:rf.machine` abort / destroy events) | an `{:id :cancellation-cascade :spec … :tick …}` descriptor in `:overlays` — the cascade overlay. |

The host owns every trace→spec projection; the chart only positions and
paints what it is handed. This keeps the chart testable in isolation and
avoids coupling it to a framework registry.

Source: the host-side surfaces are lifted from
[Xray 003 §Data sources](../../xray/spec/003-Machine-Inspector.md#data-sources).

### Trace events emitted (rf2-4lyvh)

The chart is otherwise presentation-only, but it owns one diagnostic
seam: ELK layout failures surface as a public trace event so tools
that subscribe to `:rf.error/*` (Xray's Issues panel, off-box error
monitors) see the failure with enough context to diagnose without
loading xyflow / elkjs.

| Trace operation | When | Op-type |
|---|---|---|
| `:rf.error/machines-viz-elk-layout-failed` | `chart/compute-layout!` (`chart.cljs`) hits a failing ELK pass — either a synchronous throw from `(.layout elk-instance input)` (rare; a malformed input) or an async rejection of the returned Promise. The result-map handed to the layout `done-fn` carries an extra `:layout-error` slot (`{:positions {} :edge-points {} :layout-error {…}}`) and the chart paints the in-panel banner (per [§What renders](#what-renders) — the red `Layout failed.` status row at the top of the canvas); the trace event is the parallel off-box surface. | `:error` |

**Payload** (`:tags`), authored by `report-layout-error!` in
`chart.cljs` from the pure shape in
`day8.re-frame2-machines-viz.chart.layout-error`:

```clojure
{:elk-error     {:message <string>          ;; from (.message err); "unknown error" when missing
                 :name    <string>}         ;; from (.name err); absent when the JS error has no .name
                                            ;; (or the name is blank). The error's :stack is
                                            ;; deliberately omitted — stacks are long, environment-
                                            ;; specific, and bloat the bus.
 :machine-id    <keyword-or-nil>            ;; the failing chart's :machine-id prop; nil when
                                            ;; compute-layout! is called without one (the 2- and
                                            ;; 3-arity overloads used by unit tests).
 :input-summary {:node-count       <int>    ;; (count (:nodes parsed))
                 :edge-count       <int>    ;; (count (:edges parsed))
                 :region-count     <int>    ;; (count (filter :region? (:nodes parsed)))
                 :parallel?        <bool>   ;; (boolean (:parallel? parsed))
                 :direction        <kw>     ;; :tb (default) / :lr / :auto (opt-in adaptive)
                 :layout-option-ks <vec>}}  ;; sorted vector of (keys layout-options); the VALUES
                                            ;; are deliberately omitted (caller-supplied + may drift)
                                            ;; so the trace stays safe to ship off-box.
```

The pure summary + error-adapter shapes (`input-summary`,
`error->data`, `layout-error-result`) live in
[`chart/layout_error.cljc`](../src/day8/re_frame2_machines_viz/chart/layout_error.cljc)
so the JVM test corpus can pin every payload field without loading
xyflow / elkjs; the side-effect side (the `trace/emit-error!` call +
the dev-only `js/console.error` gated on `interop/debug-enabled?`)
sits at the
[`chart/compute-layout!` callsite](../src/day8/re_frame2_machines_viz/chart.cljs)
next to the elkjs interop.

**Production elision.** `re-frame.trace/emit-error!` rides the
framework's `interop/debug-enabled?` gate (per
[`spec/009-Instrumentation.md` §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)),
so the emit is dead-code-eliminated from `:advanced` production
bundles alongside the rest of the trace substrate. Consumers MUST
treat the event as a dev-and-debug-build surface — production
deployments expose no machines-viz trace at all (the chart itself
is bundle-isolated from production builds per [§Installation](#installation)).

**Consumers.** Xray's inline issue surfaces
([Xray 016 §Issues](../../xray/spec/016-Auxiliary-Panels.md#issues--the-dedicated-tab-was-removed-rf2-gbz39-option-c))
pick this event up via the `:rf.xray/issues-ribbon` projection and
render it with its machine-id attribution and ELK error message — in
the Epoch panel's exception block, the L2 event-row pink-wash, and the
always-on issues ribbon signal; off-box monitors that
forward `:rf.error/*` via the framework's error-emit listener (per
[Spec 009 §What IS available in production](../../../spec/009-Instrumentation.md#what-is-available-in-production))
see the same record under the dev-and-debug-build gate.

This is the only trace event the chart emits. The chart consumes
many trace events through host-side projection (see
[§Data sources](#data-sources)), but the only emit it owns is this
ELK-layout-failure surface.

## Density

The chart ships **three named density variants**. Hosts pick one via
the `:density` prop on `MachineChart`; the same machine renders at
different physical sizes without forking the renderer.

| Density | When to pick it |
|---|---|
| `:compact` | Story's 50-chart panel grid; thumbnail listings; any surface where the chart is one of many and the user is scanning the grid for shape rather than reading individual labels. Walks the typography back to the spec/007-UX-IA refused-floor (state 11px / edge 9px) — the refused-floor was set for dense data-grid surfaces, and this density IS that surface. Geometry tightens ~25% (paddings, dot-grid spacing, pill height). |
| `:regular` | The default. Xray's machines tab; the read-only viewer page; any embedded host that picks no density at all. Typography sits at the chart-floor (state 13px / edge 11px per rf2-gg7ws). The constants in `visual-constants/chart-regular` are the same constants `visual-constants/chart` aliases. |
| `:cosy` | Single-chart presentation displays — Xray's machines tab on a wide monitor, a standalone viewer on a projector, an editor's docs-page screenshot. Walks the typography up to state 15px / edge 13px. Geometry loosens ~25%. |

### Identity vs. quantity

Density scales **quantity**, not **identity**. The same chart at all
three densities reads as the same chart — the rounded-rect 'data, not
product' character (rf2-g6cig) holds. Specifically:

- **`corner-radius` is locked at 6 across every density** per the
  rf2-g6cig lock. A 'compact' chart with `corner-radius 4` would
  read as a different chart, not a smaller one; a 'cosy' chart with
  `corner-radius 10` would read as 'product chrome'. The lock means
  the chart's silhouette is the same at every scale.

The chart's two contrast-budget constants — the edge-label backplate
fill and the dot-grid backdrop subtlety — also do not scale with type
size, but they are NOT density-map keys: the backplate paints the flat
`:event-chip-bg` theme token (no opacity key), and the dot grid is
xyflow's `<Background>` `:gap` / `:size` with no alpha. (rf2-dt5b1 —
the unread `:edge-label-backplate-opacity` / `:dot-grid-alpha` density
keys these bullets once named were removed; no renderer read them.)

Every other geometry / typography knob (`stroke-width`, paddings,
pill geometry, every `*-px` font size, the edge `arrow-width` family,
the dot-grid `spacing-px` / `radius-px`) tracks the density axis
monotonically: `:compact < :regular < :cosy`. The three named maps in
`visual-constants` share the SAME key set (asserted by
`visual-constants-cljs-test`).

### Resolution rules

- `:density nil` or unspecified ≡ `:regular`.
- `:density` ∈ `:compact` / `:regular` / `:cosy` resolves to the
  matching named map via `visual-constants/chart-for-density`.
- Any other value throws an `ex-info` at render time — picking an
  unknown density is a programmer error, not a runtime fallback.
- The resolved density surfaces on the chart's root wrapper element
  as `data-density="<compact|regular|cosy>"` so hosts and tests can
  read the active density without re-reading the bound prop.

### Implementation notes

- `MachineChart` resolves the `:density` prop ONCE per render via
  `visual-constants/chart-for-density` (rf2-k647w). The resolved
  map is threaded through the projector (`chart.projection/xyflow-
  graph`) onto every node + edge `:data` as `{:chart <density-map>}`.
- The xyflow node + edge components recover that map off their
  `:data` prop (`chart.nodes/chart-constants` does `js->clj` on the
  `clj->js`-ed `:chart` entry) and read their geometry / typography
  off it. xyflow invokes these components OUTSIDE any dynamic-binding
  scope, so the density travels in the data, not in a dynamic Var.
- A node/edge payload without a `:chart` entry falls back to
  `visual-constants/chart-regular`, so the regular density stays
  pixel-identical to the pre-rf2-k647w hardcoded numbers.
- Direct projection tests that want a non-default density pass it
  through `xyflow-graph`'s `:chart` option and assert on the emitted
  `:data`; production code always goes through the `:density` prop.

Per rf2-32gw5 (resolves the `visual-constants.cljc` doc-string's
'a future density toggle (compact / cosy / comfy) a one-knob change'
forward-promise; naming settled on compact / regular / cosy per the
bead title — `regular` is the load-bearing default name now that the
chart-floor lift (rf2-gg7ws) put the previous-default size at the
floor of three rungs rather than the only rung).

## Theme (rf2-az6e2)

`MachineChart` accepts a `:theme` prop — `:dark` (default) or `:light`
— and **theme support is real**: the chart resolves the active palette
ONCE per render and the renderers paint that palette, rather than
reading a hardwired dark-tokens alias.

### Theme vs density — orthogonal knobs

`:theme` (colour) and `:density` (geometry + typography) are
**independent**. A host picks a density for its surface (Story grid →
`:compact`, Xray tab → `:regular`/`:cosy`) and a theme for its surround
(dark Xray → `:dark`, a light embed → `:light`) without either knob
constraining the other.

### Chart-semantic tokens

Rather than add a bespoke role key per surface, the structured
grammar's semantic roles are **derived** from the existing base-palette
entries by `theme/tokens/chart-tokens`, parameterised by the active
palette. The Xray↔machines-viz drift gate (rf2-593jn, was rf2-z7ms8)
asserts machines-viz's dark-palette key set is a **subset** of Xray's
and that the **shared** keys agree on value — machines-viz publishes
only the tokens its chart consumes, so it never mirrors Xray's
chrome-only tokens (`:chrome-ribbon-*`, `:diff-*`, `:syntax-*`,
`:bg-issue-row`, `:selected-row-bg`) as no-ops:

| Role | Resolves to |
|------|-------------|
| `:state-header-bg` / `:state-body-bg` / `:state-border` | leaf-state title strip / body / resting border |
| `:divider` | title/body hairline |
| `:container-header-bg` / `:container-body-bg` / `:container-border` | compound chrome (solid neutral, no accent wash) |
| `:region-border` / `:region-header-bg` | parallel-region chrome (dashed neutral) |
| `:event-chip-bg` / `:event-chip-border` | route-chip fill / border (subordinate) |
| `:pseudo-marker` | initial / history pseudo-state (neutral, NOT accent) |
| `:edge-quiet` / `:edge-active` / `:edge-fired` | source→event quiet / event→target+active / fired-epoch |
| `:edge-guard-blocked` | rf2-fzrzlw — the guard-blocked no-op edge (a transition whose guard rejected the event): PINK (`:magenta-pink`), distinct from the blue fired/active hues AND the red `:final-error` ring. Wins over fired/active in `edge-color` (`blocked? > fired? > focused?/active? > quiet`). |
| `:active` / `:focus` / `:sim` / `:final` | runtime-state accents (reserved for runtime, NOT static structure) |

### Resolution rules

- `:theme nil` or an unrecognised value ≡ `:dark` (the Xray default
  surface). Unlike `:density` (which throws on an unknown value),
  `:theme` degrades to dark — a colour fallback is graceful, a geometry
  fallback would silently mis-size.
- The resolved theme surfaces on the chart's root wrapper as
  `data-theme="<dark|light>"`.

### Implementation notes

- `MachineChart` resolves `:theme` ONCE per render via
  `theme/tokens/theme-palette` → `chart-tokens`. The token map is
  threaded through the projector (`chart.projection/xyflow-graph`) onto
  every node + edge `:data` as `{:palette <chart-tokens-map>}`.
- The xyflow node + edge components recover the map off `:data`
  (`chart.nodes.xyflow-node/palette-of`) — xyflow invokes them OUTSIDE
  any dynamic-binding scope, so the palette travels in the data, not a
  dynamic Var (the same discipline `:chart` uses for density).
- A node/edge payload without a `:palette` entry falls back to
  `chart-tokens` of the dark palette, so a theme-less / directly-
  constructed node still renders the dark grammar.
- The chart's own canvas chrome (root canvas surface, dot-grid, minimap,
  layout-error banner) reads the resolved `theme-palette` directly. The
  root machine title strip + Context band are NO LONGER canvas chrome —
  rf2-q129z8 folded them into the ROOT-CONTAINER frame node's header, so
  they read the per-node `:palette` like every other node.

## Visual grammar (rf2-az6e2)

The structured topology grammar reads STRUCTURE first; **annotation
colour is subordinate to structure**.

- **Leaf states** render as title/body boxes — a full-width, left-
  aligned **title strip** (sans `chart-label-stack`), a hairline divider
  when body content exists, and a body band carrying **one neutral tag
  chip style** (the VISIBLE label + `data-tag` preserve the DECLARED
  namespaced identity — `door/open`, not truncated `open`; rf2-vcnvj) +
  quiet **title-case** "Entry actions" / "Exit actions" caption rows
  (NOT uppercase — rf2-vcnvj; **normal weight, no letter-spacing,
  tertiary colour** — rf2-ly51l, the quietest tier so the caption reads
  as a section hint, not a heading) with subdued bolt-glyph action chips.
  Square-ish, low radius (the 6px `corner-radius` lock). Runtime state
  (active / focus lens / sim) rides the **border + header + glow**, never
  the whole fill. A **final state** is a quiet double border (the ✓ check
  glyph is **dropped** — rf2-az6e2 decision).
- **Event/transition nodes** stay synthetic layout nodes (source-state →
  event-node → target-state) but render as **subordinate route chips** —
  no title bar, event + guard on the first line as **`IF <guard>`**, an
  action row only when an action exists, machine-level **muted** (data-
  attr only). An internal / action-only transition is a terminal chip
  with a dashed border ring and no outgoing target segment.
- **Machine-level (top-level `:on`) fallback** projects as a SINGLE
  route from the synthetic **machine-root chip** (`◆ root` pill) into its
  target (rf2-vcnvj) — NOT one chip per leaf. The per-state expansion
  repeated the chip around every state AND injected back-edges that
  scrambled ELK's top-to-bottom ranking; one root-sourced route reads as
  the machine-wide fallback it is and leaves the main column to the real
  state transitions.
- **Compound containers** render as **solid subtle-neutral** title-strip
  containers (no dashed border, no saturated accent wash by default).
- **Parallel regions** render as **dashed neutral** title-strip
  containers; region identity comes from **containment + layout**, NOT a
  rotating border colour (the prior `region-boundary-palette` rotation is
  removed). Active / focus colour is reserved for runtime state.
- **Pseudo-states**: initial markers are small **neutral** dots (not
  accent-blue). A **history** pseudo-state (shallow `H` / deep `H*`, small
  symbolic node, never occupiable) is **wired end-to-end** (rf2-m285a):
  `chart.layout` parses a `:type :history` node into a non-occupiable
  `{:history? true :deep? …}` marker, `chart.projection` maps it to xyflow
  `"history-marker"` + `:data {:deep …}`, and `chart.nodes/history-marker`
  paints it. The SCXML / Mermaid emitters preserve the history semantics
  (SCXML uses W3C `<history type="shallow|deep">` + the default
  `<transition>`). See
  [`001-Topology-Parity.md`](001-Topology-Parity.md) §1.4.
- **Arrow/routing**: the two-edge event-node route reads as **one
  transition** — a quiet source→event segment (thinner stroke, small
  `:arrow-width-quiet` arrowhead, `:edge-quiet`) and a primary
  event→target segment (full `:arrow-width` arrowhead). Both arrowhead
  WIDTHS ride the density map alongside the stroke, and the arrowhead
  COLOUR resolves through the same `theme.tokens/edge-color` helper the
  SVG stroke uses (so a head and its stroke can never disagree on
  colour — rf2-dt5b1). Fired/focused lights **both** segments together
  (`fired?` > `focused?`/`active?` > quiet).
- **Typography hierarchy** (loudest → quietest): **state title**
  (`state-title-px`, weight 500/600) › **event chip** (`event-chip-px`)
  › **action chip** (`action-pill-px`, weight 500, `text-secondary`) ›
  **tag pill** (`tag-pill-px`, muted) › **section caption**
  (`action-caption-px`, normal weight, `text-tertiary`, no letter-
  spacing — rf2-ly51l). Each tier is visibly subordinate to the one
  above so the eye reads structure (state titles) first.
- **Initial-state placement** (rf2-ly51l): a **soft** preference biases a
  cyclic statechart so its initial state sits near the top (`:tb`) / left
  (`:lr`) and the forward spine reads from there — via DEPTH_FIRST
  cycle-breaking + an initial-leads child model order (see
  [`001-Topology-Parity.md`](001-Topology-Parity.md) §Layout). NOT a hard
  invariant: ELK's crossing-min + node-placement can still arrange a
  state off the ideal when the graph demands it.
- **Root chrome** (rf2-q129z8): the machine name + Context shape ride the
  HEADER of the synthetic **ROOT-CONTAINER frame** — a named rounded box
  wrapping the WHOLE machine (Stately Studio's frame around the machine
  root). Every top-level node nests under it via xyflow `parentId`; ELK
  sizes the frame to HUG its children and reflows it on resize (exactly as
  a compound container hugs its substates). The header's title strip
  carries the machine name (subtle `∥` glyph for root-parallel machines)
  and, under it, a Context BAND rendered from the optional `:context-band`
  prop — live context `(key → value)` when a snapshot is in hand, or the
  static **context-shape** `(key → type-caption)` the Xray topology path
  derives via `topology-view/static-context-shape` (rf2-vcnvj),
  **declared over inferred** — authoritative off a `[:schemas :data]` schema when
  present, else inferred from one `:data` sample (rf2-3q4k5b · EP-0005 · EP-0029 A3);
  the badge beside the header reads `declared` for the authoritative shape
  and `inferred from :data` for the inference. **Pre-q129z8** the
  title strip + Context were two `position:absolute` overlays welded to the
  chart's top-left corner; being absolute, they did not contain/track the
  topology (they stuck to the corner while xyflow re-fit the topology on
  resize) and collided with other top-left chrome. The frame header fixes
  both. The frame is structural chrome: NEUTRAL (never marked `:active`),
  not a click/selection target. Rendered by
  `chart.nodes/root-container-node` (node-type `"root-container"`, node-id
  `chart.layout/root-container-id`).

## Performance invariants

This section is **load-bearing and conformance-level**, not guidance.
The chart's runtime hot path is the single largest regression
surface for Machines-Viz (per `ai/findings/perf-audit-machines-viz-2026-05-14.md`
findings 1+2 — gitignored working note; audit-bead rf2-j3iwt). Once an
implementation couples the live snapshot / decoration props to graph
recompute, the coupling is hard to remove without rewriting the
rendering pipeline. The MUSTs below exist so that coupling never lands.
The shipped chart is presentation-only — the host projects the
framework's registry + trace bus into props (per [§Data sources](#data-sources))
— so these invariants govern how the component reacts to **prop
changes**, not to direct registry / trace subscriptions.

### Topology props and runtime-highlight props MUST be strictly separate

`MachineChart` maintains **two disjoint planes**, fed by two disjoint
prop groups:

- **Topology / layout plane.** The node positions, edge routes, and
  compound-state nesting. Derived solely from the structural props
  `:definition`, `:direction`, `:layout-options`, and `:density` (the
  host pulls `:definition` from `(rf/machine-meta machine-id)`;
  `:density` sizes ELK container padding per rf2-8q5pt — see the
  [layout-invalidation boundary](#layout-invalidation-boundary-is-load-bearing)).
  This plane is **structural**: changing it requires re-laying out the
  graph via elkjs. The parser that walks `:definition` into the
  `{:nodes :edges}` graph (`layout/project-definition`) is itself a
  topology-plane computation — implementations MUST cache its result
  keyed ONLY on `:definition` (rf2-jl72i), so a decoration-only render
  never re-walks the definition (see §Highlight / overlay prop changes
  below).
- **Runtime-highlight plane.** The active-state affordance (static
  tint + bolder stroke; pulse retired 2026-05-20 per rf2-2sez0), the
  focused-event lens tint, the `:after` countdown-ring overlay, the
  `:spawn-all` join inspector, the cancellation cascade, and every
  other per-trace decoration. Derived solely from the decoration props
  `:current-state`, `:from-highlight`, `:to-highlight`, `:sim?`, and the
  `:overlays` slot (each host-fed overlay descriptor + its per-descriptor
  `:tick`). This plane is **decorative**: changing it MUST NOT touch the
  topology plane.

The decoration props MUST NOT participate in any computation whose
output reaches the layout plane. The two planes share no caches.

### Highlight / overlay prop changes MUST change attrs/classes only

A render driven by a change to any decoration prop (a new
`:current-state`, `:from-highlight` / `:to-highlight`, an `:overlays`
descriptor `:tick` bump, a new / changed `:overlays` descriptor `:spec` /
`:specs`) MUST mutate **only**:

- DOM attributes (`class`, `style.opacity`, `style.transform` on
  decoration layers, SVG `stroke-dasharray` / `stroke-dashoffset`,
  ARIA labels).
- CSS-driven animations (the transition glow, the
  `prefers-reduced-motion` step animation). The heartbeat pulse
  was retired 2026-05-20 (rf2-2sez0).

Such a render MUST NOT:

- Re-parse / re-project the topology. The definition→graph parse
  (`layout/project-definition`) MUST be cached keyed only on
  `:definition` (rf2-jl72i); a decoration-prop change reuses the cached
  parsed graph (and thus the downstream layout / projection caches keyed
  on it), never re-walking the definition.
- Re-run layout (no `elk`/`dagre`/custom layout call).
- Re-measure topology nodes (no `getBBox`, no `getBoundingClientRect`
  in any code path reached by a decoration-prop change — overlay
  re-measure of its own anchor DOM is permitted and is gated by the
  host's tick props).
- Insert or remove topology DOM nodes (state nodes, edge paths,
  compound containers). Decoration overlays (rings, the join
  inspector, the cascade) MAY mount and unmount; topology MUST NOT.
- Mutate any value the topology plane recomputes from.

A decoration-prop update arriving at 60Hz (e.g. an `:overlays`
descriptor `:tick` bump) MUST cost less than one paint frame end-to-end
on the chart's hot path.

### Layout-invalidation boundary is load-bearing

`MachineChart` keys its elkjs layout pass on the
`[:definition :direction :layout-options :density]` tuple (per
`chart.cljs`): a new layout runs **only** when that tuple changes, and
the previous positions are kept in-flight to avoid an empty-chart flash.
The **only** triggers permitted to invalidate the topology / layout
plane are:

1. **A new `:definition`.** A changed definition map — including a
   `reg-machine` hot-reload re-registration the host re-pulls via
   `(rf/machine-meta machine-id)` and re-passes — re-runs layout.
2. **A `:direction` change** (`:tb` ⇄ `:lr`).
3. **A `:layout-options` change** — host-side elkjs `layoutOptions`
   overrides.
4. **A `:density` change** (`:compact` ⇄ `:regular` ⇄ `:cosy`).
   `:density` is **structural for layout**: the ELK pass sizes each
   compound / region container's `elk.padding` from the active density's
   title-strip + body-pad constants (rf2-8q5pt), so a density switch
   changes the geometry ELK must lay out — not merely the painted size.
   It therefore re-runs layout (and re-fits), unlike `:theme` — which is
   purely a colour swap and stays decorative. Highlight / overlay props
   likewise stay decorative; only `:density` among the visual knobs is
   load-bearing for layout.
5. **Container resize** of the chart's bounding box (xyflow's own
   fit/measure; the elk pass itself is keyed on the tuple above).

No other code path may invalidate layout. In particular:

- A `:current-state` / `:from-highlight` / `:to-highlight` change
  MUST NOT reach the layout pipeline — it only re-tints existing nodes.
- An `:overlays` descriptor `:tick` bump or a new / changed `:overlays`
  descriptor `:spec` / `:specs` MUST NOT reach the layout pipeline —
  these mount / repaint decoration overlays only.

Implementations MUST place an explicit comment marking the
layout-invalidation boundary as load-bearing in the code that owns
it (the function that decides "should layout re-run?" — the
`this-key`/`layout-key` guard in `chart.cljs`). The comment MUST cite
this section and DESIGN-RATIONALE Lock #9 and Lock #11.

### Auto-fit on async layout settle

`MachineChart` MUST re-fit the xyflow viewport **once** after each
successful elkjs layout settle whose layout-key (the
`[:definition :direction :layout-options :density]` tuple above) differs
from the last key that was fit. xyflow's `:fitView true` prop fires only
on the initial mount, but mount happens BEFORE the async elk pass
resolves — every node sits at the default `{x 0 y 0}` and the
one-shot fitView would frame the operator on a degenerate cluster
near the origin (rf2-set3x). Implementations MUST capture the xyflow
ReactFlowInstance via `:onInit` and call `.fitView` from the
`compute-layout!` settle (and the symmetric onInit-after-settle race
branch).

The auto-fit MUST be gated:

- It runs **once** per layout-key change. A manual operator zoom/pan
  survives every non-layout re-render (highlight changes, overlay
  ticks, fired-edge sets) — these do not invalidate the
  `layout-key`, so they do not refit.
- It does NOT run on a `:layout-error` settle (positions are empty;
  the error banner paints instead).
- The xyflow Controls' built-in `Fit` button remains the manual
  re-fit escape hatch.

A layout invalidation (any of the five triggers above) implicitly
re-fits, by design — the topology shape has changed and the
operator's prior framing is no longer meaningful.

The `.fitView` call MUST be deferred through **two** nested
`requestAnimationFrame` tasks (rf2-s5kyp). A single rAF races
xyflow's internal node-measurement on the focused-machine-change
path: React commits the new prop set with the new machine's
positions; the single rAF fires before xyflow has finished
measuring the just-mounted node DOM; `.fitView` then reads stale
or zero-size bounds and frames the viewport on either the prior
topology's extent or a degenerate box. Two rAFs guarantee the fit
runs in the frame AFTER React's commit AND xyflow's measurement
pass — the same trick xyflow's own examples use for post-load fit
calls. Test runtimes without rAF (Node) MAY fire the fit
immediately as a fallback.

### Fit-on-entry signal (rf2-6tw7t)

The layout-key auto-fit above deliberately **preserves** the operator's
manual zoom/pan across non-layout re-renders. That is the right
behaviour while the operator stays on the chart — but it leaves a chart
**re-entered** from a panel/tab switch at its prior viewport even though
the operator's intent on re-entry is "show me the whole graph again."
xyflow's one-shot `:fitView` mount prop does not help (it fires only on
the *initial* mount, and a reconciled-in-place chart never re-mounts).

`MachineChart` therefore accepts an optional **`:fit-signal`** prop — an
opaque, `=`-comparable nonce. When the prop's value **changes** between
renders the chart MUST re-fit the viewport, **independent** of the
layout-key gate. The re-fit reuses the same `schedule-fit!`
(double-rAF) deferral and the same preconditions as the layout-settle
fit:

- A ReactFlowInstance MUST have been captured (`:onInit`); a signal that
  arrives before the instance is deferred to `:onInit` (which re-checks
  the signal once the instance + settled positions are both present).
- Positions MUST be non-empty and there MUST be no `:layout-error` — a
  fit on empty positions would frame the degenerate origin cluster.
- The chart records the value it last fit on; a **steady** signal across
  ordinary re-renders is a no-op, so manual zoom/pan still survives
  non-entry re-renders. The recorded value starts at a sentinel distinct
  from every host value (including `nil`) so the **first** observed
  signal fits once.

This is the **orthogonal escape hatch** to the layout-key gate: a host
(e.g. the Xray Machine tab) bumps a monotonic counter on
panel-entry / tab-activation and forwards it as `:fit-signal`. A host
that never supplies the prop (the standalone viewer / Story path) leaves
it at `nil`; the value never changes, so the entry-fit is inert and only
the layout-key auto-fit runs.

### One chart-level, visibility-gated animation clock

`:after` countdown rings and any continuous animation in the chart
MUST be driven by a **single, per-chart-instance animation clock**.
(The active-state heartbeat pulse was retired 2026-05-20 per
rf2-2sez0; only the `:after` countdown rings remain on the clock.
The transition glow is event-driven and resolves to a stable end-
state, so it does not consume the clock.)

- The clock is **one** `requestAnimationFrame` loop (or equivalent)
  per `MachineChart` instance. It MUST NOT be one loop per ring,
  per node, per state, or per timer.
- The clock is **visibility-gated**. It MUST start when the chart
  becomes visible (per `IntersectionObserver` and/or
  `document.visibilityState`) and MUST stop when the chart leaves
  the viewport or the document is hidden.
- The clock drives ring fills by reading the framework's
  authoritative timer state on each tick; it does not own the
  timer. The framework's clock keeps running regardless of the
  chart's visibility (per Lock #8); the chart's clock is purely
  presentational.
- A chart with no scheduled `:after` timers MUST stop its clock
  entirely until the next snapshot or trace tick wakes it.

Implementations MUST NOT create `setInterval`, `setTimeout`, or
`requestAnimationFrame` registrations per-node, per-ring, or
per-timer. A 50-chart Story grid with 5 rings each MUST run at most
50 animation loops total — not 250.

Per [DESIGN-RATIONALE Lock #8](./DESIGN-RATIONALE.md) (visibility
gating) and [Lock #11](./DESIGN-RATIONALE.md) (the layout/runtime
separation this section codifies).

## Read-only viewer

A static page at the canonical hosted URL (or self-hosted by the
consumer) that renders a chart decoded from a URL fragment.

### URL shape

```
https://day8.github.io/re-frame2-machines-viz/viewer.html#machine=<base64-edn>
```

Or, when self-hosted:

```
https://acme.example.com/path/to/viewer.html#machine=<base64-edn>
```

### Behaviour

- On page load, the viewer reads `location.hash`, strips the
  leading `#machine=`, base64url-decodes, transit-reads, and
  validates the envelope (per [§Share-URL payload schema](#share-url-payload-schema)
  below).
- Validation failure → a banner: "This share-URL is malformed or
  was produced by a newer Machines-Viz." No chart renders.
- Validation success → the viewer mounts `MachineChart` with:
  - `:machine-id` from the payload's `:chart-state` (per
    [§Share-URL payload schema](#share-url-payload-schema)).
  - `:definition` from the payload's `:definition`.
  - `:current-state` set to the payload snapshot's `:state`
    configuration — a flat keyword, a compound vector-path, or a
    parallel region-map (the share schema carries the state
    configuration only; there is no runtime `:data` to render).
  - `:read-only?` set to `true`, which no-op's `:on-state-click`.
  - The `:overlays` slot left unset (`nil`) — a static share has no
    live trace bus to project the host-fed overlay descriptors from.
- A single banner at the top of the page reads: **"This is a
  static machine chart, not a Xray session — interactions are
  disabled."**
- A "show idle" toggle below the banner clears `:current-state` so the
  chart renders the machine at rest. Per
  [DESIGN-RATIONALE Lock #5](./DESIGN-RATIONALE.md).
- The page is statically hostable. Per
  [DESIGN-RATIONALE Lock #7](./DESIGN-RATIONALE.md), the
  canonical hosted instance at `day8.github.io` is a convenience,
  not a contract; consumers can self-host.

### What the viewer never does

- It never transmits the URL fragment to a server. The fragment is
  read client-side via `location.hash`; nothing sends it.
- It never loads transit events, app-db slices, or any data
  outside the validated payload schema.
- It never receives runtime `:data` — the share payload's
  `:snapshot` carries `:state` only (per
  [§Share-URL payload schema](#share-url-payload-schema)). The
  viewer cannot display data values because there are none to
  display.
- It never receives local-filesystem `:source-coords` — they are
  not part of the share schema. The viewer has no editor handler
  wired, so source coords would be inert anyway; excluding them
  prevents accidental disclosure of workstation paths.
- It never enables `:on-*` callbacks. Hosts requesting a "click in
  the viewer goes to my docs site" would have to fork the page;
  the canonical viewer is read-only end-to-end.

Source: lifted from
[Xray 003 §Share affordance](../../xray/spec/003-Machine-Inspector.md#share-affordance).

## Share-URL encoding

### Encoder

```clojure
(:require [day8.re-frame2-machines-viz.share :as share])

(share/encode-share-url chart-state)
;; => "https://day8.github.io/re-frame2-machines-viz/viewer.html#machine=..."

(share/encode-share-url chart-state {:host "https://acme.example.com/viewer.html"})
;; => "https://acme.example.com/viewer.html#machine=..."
```

`chart-state` is a map with the schema in
[§Share-URL payload schema](#share-url-payload-schema) below. The
encoder:

1. Allowlists `chart-state` (anything outside the allowlist is
   silently dropped — including runtime `:data` on `:snapshot` and any
   `:source-coords` the caller passes, per
   [Principles §No session data in shares](./Principles.md)), then
   validates the allowlisted result against the schema with the **same**
   predicate the decoder uses. Encode and decode are therefore symmetric:
   the encoder rejects (with `:invalid-chart-state`) anything the decoder
   could not accept — e.g. a `:snapshot` `:state` that is none of the
   three configuration arms — rather than emitting an undecodable URL.
2. Strips metadata off `:definition` AND structurally sanitises it
   (rf2-m285a). A macro-stamped spec (Spec 005 §Source-coord stamping)
   co-locates `:source-coords` / `:source-code` + executable `:fn` values
   as ordinary DATA inside `:states` / `:guards` / `:actions` /
   `:on-spawn-actions` — NOT as metadata — so `strip-meta` alone never
   reached them (a local-filesystem-path leak, and a live `:fn` would make
   Transit encoding fail). `sanitise-definition` recursively drops the
   `:source-coords` / `:source-code` debug fields and EXECUTABLE `:fn`
   values (a `:fn` entry is stripped ONLY when its value is a function —
   the co-located `{:fn <fn> …}` slot — so a TOPOLOGY key that happens to
   be named `:fn`, i.e. a state id, event id, or region id whose value is
   a topology submap, is PRESERVED, rf2-07gg7h), and replaces any inline-fn
   slot with a names-only opaque label, so the payload is a viewer-safe
   topology with no source/paths/fns. The topology references (state ids,
   transition targets, guard/action NAMES) survive.
3. Canonicalises map / set ordering (per
   [Principles §Reproducible from the registry alone](./Principles.md)).
4. Wraps in the versioned envelope.
5. EDN-prints → transit-writes → base64url-encodes.
6. Wraps the fragment into the `:host` URL.

### Decoder

```clojure
(share/decode-share-url url)
;; => {:rf.machines-viz.share/v "2"
;;     :rf.machines-viz.share/chart {:machine-id :auth/login-flow ...}
;;     :rf.machines-viz.share/created 1736000000000}
;; or throws :rf.machines-viz.share/decode-failed with :reason
```

Failure modes:

| `:reason` | Meaning |
|---|---|
| `:malformed-fragment` | The `#machine=` fragment isn't valid base64url. |
| `:malformed-payload` | Decoded bytes aren't valid transit. |
| `:missing-envelope` | The payload is missing `:rf.machines-viz.share/v` or `:rf.machines-viz.share/chart`. |
| `:unknown-version` | `:rf.machines-viz.share/v` is newer than the decoder knows. |
| `:invalid-chart-state` | `:rf.machines-viz.share/chart` doesn't validate. |

### Pipeline

```
chart-state  →  validate + canonicalise  →  envelope wrap
             →  EDN-print  →  transit-write  →  base64url  →  URL fragment
```

Per [DESIGN-RATIONALE Lock #3](./DESIGN-RATIONALE.md).

### Share-URL payload schema

Share URLs are **viewer-side artefacts** — they exist solely to let
a remote recipient render the chart. The schema is deliberately
narrow: it carries the machine topology and the active-state
**configuration** (its name/address) for visual continuity, and
nothing else. Two classes of data are structurally excluded:

- **Runtime `:data`** — the machine's per-snapshot data value is
  not part of the share payload. A well-intentioned operator
  clicking "Copy as Share URL" must not be able to exfiltrate
  tokens, form contents, request payloads, or any other sensitive
  value the running machine has accumulated. Per
  [Principles §No session data in shares](./Principles.md).
- **Local-filesystem `:source-coords`** — source coordinates carry
  absolute file paths (per [`spec/Spec-Schemas.md` §`:rf/source-coord-meta`](../../../spec/Spec-Schemas.md#rfsource-coord-meta))
  which reveal usernames, workstation layout, and internal repo
  structure. Source-coord chips are an editor-side affordance for
  the operator running Xray; the viewer page has **no editor
  handler wired** and cannot use them. They are dropped at encode
  time.

```clojure
(def ShareEnvelope
  [:map
   [:rf.machines-viz.share/v      :string]   ;; encoding version, "2" (was "1" pre-EP-0023)
   [:rf.machines-viz.share/chart  ChartState]
   [:rf.machines-viz.share/created :int]])   ;; wall-clock ms at encode time

;; A snapshot `:state` is a STATE CONFIGURATION — one of the three
;; Spec 005 §Snapshot-shape arms (the same arms `MachineChart`
;; `:current-state` accepts). Vector paths + region-maps are state
;; names/addresses, NOT runtime data.
(def SnapshotState
  [:or
   keyword?                              ;; flat machine        — :idle
   [:and vector? [:sequential {:min 1} keyword?]] ;; compound machine    — [:auth :authing]
   [:map-of keyword?                     ;; parallel machine    — {:data :loading
    [:or keyword?                        ;;                         :form [:edit :dirty]}
     [:and vector? [:sequential {:min 1} keyword?]]]]])

(def ChartState
  [:map
   [:machine-id  keyword?]              ;; the registered machine's id
   [:frame-id   {:optional true}        ;; OPTIONAL frame-target id (v2 / EP-0023) — payload provenance only
    keyword?]                           ;; the process-local live-frame id captured at share time; omit when unknown
   [:definition  MachineDefinition]     ;; the topology (states, transitions, guards, actions, :spawn, :spawn-all, :after)
   [:snapshot   {:optional true}        ;; the current-state configuration at share time — state CONFIGURATION only; no runtime data
    [:map {:closed true}
     [:state    SnapshotState]]]])      ;; the active state name/address; nothing else permitted in :snapshot
```

The `:snapshot` map is `{:closed true}` and carries `:state` only.
Its `:state` value is a **state configuration** — a flat keyword, a
compound vector-path, or a parallel region-map (the three Spec 005
§Snapshot-shape arms) — so a compound or parallel machine's active
configuration shares + round-trips intact. Vector paths and region-maps
are state names/addresses, **not** runtime data. Runtime `:data` is
structurally absent from the share payload; the encoder neither reads
nor serialises it. **Encode and decode validate the same schema** (the
encoder validates the allowlisted chart state — including the snapshot
shape — *before* serialising), so the two stay symmetric: the encoder
never emits a payload the decoder would reject, and a malformed `:state`
(none of the three configuration arms) is rejected at encode rather than
producing an undecodable URL. **The top-level ChartState map is CLOSED
over `#{:machine-id :frame-id :definition :snapshot}` on BOTH sides**
(rf2-dplwxh): a hand-crafted share URL that bypasses the encoder cannot
smuggle an extra top-level key (`:source-coords`, `:data`, or any future
unreviewed field) past `decode-share-url` / `decode-share-url-safe` — the
decoder rejects it with `:invalid-chart-state`, so the viewer never loads
anything outside the validated payload schema. The decoder likewise
rejects any `:snapshot` carrying additional keys, or a `:state` outside
the three arms, with `:invalid-chart-state`. The viewer page mounts `MachineChart` with
`:current-state` set to the snapshot's `:state` configuration (there is
no runtime `:data` to render); the chart highlights every active leaf
without any data-driven affordance.
(`:frame-id` is an **optional** payload-provenance field — an EP-0023
**frame-target id**, i.e. the process-local id of the live frame the
machine was registered against, captured at share time and decoupled
from any (realm, frame) pairing. It is **not** a `MachineChart` prop:
the viewer hands the chart the `:definition` directly rather than
resolving a frame, so the viewer never reads `:frame-id` at all. A
machine topology + active-state configuration is shareable without
naming a live frame, so `:frame-id` is omitted when the sharer does not
know it — a presentation-only chart (e.g. one shared straight off the
rendered DOM seam) shares cleanly rather than fabricating a provenance
id. `:frame-id` became optional in encoding **v2** (it was required and
documented in pre-EP-0023 (realm, frame) terms in v1); making it
optional is a v1→v2 share-schema change, gated behind the
`:rf.machines-viz.share/v` bump so a v2 payload omitting `:frame-id`
handed to a v1 decoder is refused with `:unknown-version` rather than
silently mis-decoded — CI-enforced per the encoding-version contract.)

`:source-coords` is **not a top-level key** of `ChartState`. Source
coords live only in the operator-side `(rf/machine-meta machine-id)`
return value and never traverse the share pipeline. The viewer page
renders with `:read-only?` true so `:on-state-click` is no-op'd (per
[§Read-only viewer](#read-only-viewer)), so the absence of source
coords is observationally invisible.

The `MachineDefinition` shape matches the `reg-machine` registered
definition (per Spec 005 §Snapshot shape + §Transition table grammar)
with **only** the topology slots included — `:guards` and `:actions`
maps are encoded as **names only** (their fn bodies are not
serialised; consumers re-resolve against their own registry if they
want to run the machine). The encoder **strips metadata** off the
definition before serialisation (registered definitions carry
source-coord meta per Spec 001; that meta does not propagate). The
viewer page never resolves guards or actions; it only renders.

Anything not in the schema is silently dropped by the encoder AND
rejected by the decoder (rf2-dplwxh — the top-level map is closed on
both sides; a forged URL adding an extra top-level key fails decode
with `:invalid-chart-state`). New top-level keys (and any future
expansion of `:snapshot`) require an explicit
`:rf.machines-viz.share/allow?` opt-in plus an operator-controlled
redaction hook (per
[Principles §No session data in shares](./Principles.md)); CI
enforces.

### URL length

For typical machines (~20 states, ~30 transitions) the encoded URL
is under 4KB. Common URL limits sit around 8KB; we have headroom
for moderately-sized charts.

Charts large enough to exceed 8KB surface a fallback affordance
(in the host that called `encode-share-url` — typically Xray's
share menu): **"Copy as EDN fragment instead"** which puts the
EDN on the clipboard instead of the URL. Per
[Xray 003 §Share affordance §Performance](../../xray/spec/003-Machine-Inspector.md#performance_1).

## Exporters

### What the image exporters capture (rf2-sr6l3)

`chart-as-svg` / `chart-as-png!` capture the **full rendered React Flow
viewport** — the boxed state nodes, compound / region containers, event
chips, final rings, labels, the **active-state border + glow
affordance**, and the edge layer — **not** just the edge `<svg>`.

The chart's visual grammar renders as **inline-styled DOM `<div>`
nodes** inside `.react-flow__viewport`, *around* xyflow's edge
`svg.react-flow__edges`. The exporters embed the live viewport DOM in a
standalone SVG `<foreignObject>`; because every node component renders
with inline styles, the `<foreignObject>` paints the same grammar the
operator sees — including the current-state highlight — with no external
stylesheet to embed. The viewport's own pan/zoom transform is
neutralised and the SVG is sized to the union of the node flow-boxes, so
the export frames the **whole topology at 1:1** regardless of the live
zoom/pan.

**Export-root discovery is keyed on the `_rfMvChartState` seam, not the
default `data-testid`.** `MachineChart` stamps the seam on its root via
`:ref` regardless of the host's `:testid` prop, so `chart-element` may
be the chart **root**, a **descendant** node inside it, or a **wrapping
element** around it, and the lookup resolves the same root — for the
default *and* any custom `:testid`. (The prior implementation hard-coded
`[data-testid='rf-mv-chart']`, which failed to find a custom-`:testid`
chart from a wrapper / descendant.)

### PNG

```clojure
(:require [day8.re-frame2-machines-viz.export :as export])

(export/chart-as-png! chart-element)
;; => Promise resolving to a Blob (image/png at 2x DPR)

(export/copy-png-to-clipboard! chart-element)
;; => Promise; clipboard contains the PNG + a text/plain alt-text sidecar
```

The PNG is rasterised at 2x DPR on a transparent background from the
complete viewport SVG (above), so it includes the **state boxes** and
the **current-state highlight** (the active node's accent border +
box-shadow glow). An alt-text sidecar (a `text/plain` clipboard payload)
summarises the machine: id, current state, node count, transition count.

### SVG

```clojure
(export/chart-as-svg chart-element)
;; => String — image/svg+xml (a <foreignObject>-embedded viewport
;;    capture) with embedded fonts

(export/copy-svg-to-clipboard! chart-element)
;; => Promise; clipboard contains the SVG as image/svg+xml
```

The SVG carries the full viewport (state boxes + active-state
affordance + edges, per [§What the image exporters capture](#what-the-image-exporters-capture-rf2-sr6l3))
plus `<title>` and `<desc>` elements summarising the machine (same
content as the PNG sidecar). Fonts are embedded so the SVG renders with
the same typography when pasted into a doc or a Figma frame.

The `<title>` / `<desc>` text is **XML-escaped** (rf2-85a9do): the
viewport clone is serialised with `XMLSerializer` (which escapes for us),
but the `<title>` / `<desc>` are constructed by hand from the raw machine
id + summary, so the five XML-significant characters (`& < > " '`) are
escaped to entities before injection. A programmatic machine id or
current-state label carrying `&`, `<`, or `>` therefore cannot malform
the SVG, break the PNG rasterisation that loads the SVG into an `<img>`,
or inject markup into a copied / exported artifact.

Both image exporters throw `:rf.machines-viz.export/no-svg` when the
chart has not rendered a viewport yet (an empty / nil-definition
placeholder renders no chart), and `:rf.machines-viz.export/no-chart-state`
when `chart-element` is not a rendered `MachineChart` root (or descendant
of one). Per the thrown-error contract ([Spec 009 §The thrown-error
shape](../../spec/009-Instrumentation.md)) the `ex-message` is a human
sentence naming the public concept (a rendered MachineChart element) plus
the trailing `[:rf.error/<id>]` token; `:rf.error/id` is the canonical
machine discriminator and `:reason` carries the same human sentence — the
private chart-state internals are never named in the user-facing message.

### Share URL

```clojure
(export/share-url chart-element)
;; => URL string

(export/copy-share-url-to-clipboard! chart-element)
;; => Promise; clipboard contains the URL as text/plain
```

`chart-element` is the in-DOM element rendered by `MachineChart`
(or a hiccup-equivalent reference). The export functions derive the
payload from the element's bound props + the live snapshot.

### Mermaid `stateDiagram-v2`

```clojure
(:require [day8.re-frame2-machines-viz.mermaid :as mermaid]
          [day8.re-frame2-machines-viz.export  :as export])

(mermaid/emit definition)
;; => String — a fenced ```mermaid block containing a
;;    `stateDiagram-v2` rendering of the machine's static topology

(mermaid/emit definition {:fenced? false :header-comment? false})
;; => String — just the diagram body, no markdown fence, no caveat

(export/chart-as-mermaid chart-element)
;; => String — convenience wrapper that pulls `definition` off the
;;    bound chart-element and calls `mermaid/emit`

(export/copy-mermaid-to-clipboard! chart-element)
;; => Promise; clipboard contains the fenced markdown block as
;;    text/plain — paste into a GitHub README / PR description /
;;    Notion / any Mermaid-aware renderer and it renders inline.
```

`mermaid/emit` is the load-bearing pure function; it takes the same
normalised machine definition `(rf/machine-meta machine-id)` returns
(per [Spec 005 §Transition table grammar](../../../spec/005-StateMachines.md#transition-table-grammar))
and emits a string suitable for paste. It is substrate-independent
and DOM-independent — callable from JVM tests, from the JS bundle,
and from the read-only viewer.

The emitter is **static-topology only**:

- States render as Mermaid nodes; compound `:states` render as
  `state X { ... }` blocks with their own `[*] --> initial`.
- Event transitions render as `from --> to : event` edges. `:*`
  wildcard edges render with `*` as the label. Multiple-candidate
  vectors render every target-bearing guarded branch.
- `:after` transitions render as plain edges labelled
  `after(<delay>)`; the countdown-ring semantics are lossy.
- `:always` transitions render as plain edges labelled `always`;
  microstep timing remains lossy.
- Top-level fallback `:on` renders from a synthetic `root fallback`
  node because Mermaid has no exact deepest-wins fallback primitive.
- `:type :parallel` machines render as independent region state
  trees inside a synthetic parallel root. Broadcast macrostep
  semantics are lossy.
- `:final?` states render a `state --> [*]` terminal edge.
- `:initial` becomes `[*] --> <initial>`.

The following data does **not** survive the round-trip:

- `:after` timer rings — Mermaid `stateDiagram-v2` has no
  countdown-ring vocabulary. The timer's `:target` edge still
  renders (as a plain event-less edge), but the countdown semantics
  are lost.
- `:spawn-all` rows of mini-machines — Mermaid has no
  spawn-and-join row grammar that maps cleanly; the row is omitted
  entirely.
- Parallel-region broadcast macrosteps — regions render, but Mermaid
  cannot express that one event is broadcast through every active
  region before the snapshot commits.
- Microstep flashes, transition glow, `:tags`, guard evaluation,
  actions — none of these are static topology and none round-trip.
  Guard ids may appear on edge labels, but their runtime truth
  semantics do not.

The omission is flagged in a `%% comment` at the top of the emitted
block, so a reader who pastes the output into a doc sees the
lossy-round-trip caveat without consulting the spec. The full
topology renders correctly in the SVG / share-URL viewer; Mermaid
is the Markdown-paste lane only.

Source: per [DESIGN-RATIONALE Lock #4](./DESIGN-RATIONALE.md) (the
"Mermaid covers the static-paste lane" lift, revised 2026-05-15 per
rf2-deo2i — Mermaid emit promoted from v1.1 to v1.0 as a thin
static-topology exporter), and
[Spec 005 §Future §Diagram export](../../../spec/005-StateMachines.md#diagram-export-from-transition-tables)
(the framework-level forward-pointer the tool-side exporter
realises).

### Accessibility

Per [Xray 003 §Accessibility](../../xray/spec/003-Machine-Inspector.md#accessibility)
(the share-affordance subsection; not the panel-level one — that's
the second `#accessibility_1`) and
[Lock #10 above](./DESIGN-RATIONALE.md):

- **SVG**: `<title>` + `<desc>` summarise the machine textually.
- **PNG**: a `text/plain` alt-text payload rides on the clipboard
  alongside the image, with the same summary.
- **Both**: a screen reader pasting the artefact into a document
  has the same overview the sighted user has.

The chart's in-place alt-view (for screen-reader navigation of the
live chart itself, not the export) is a **COMMITMENT, re-anchored to
first external alpha** (or earlier if the first external consumer
needs assistive-tech access to chart topology), per
[`000-Vision.md` §Committed with a trigger](./000-Vision.md#committed-with-a-trigger)
and Xray 003 §Accessibility. v1.0 ships without it; the embedding
host's transition-history ribbon + machine picker carry the
accessible surface in the meantime.

## SCXML import / export (v1.1, rf2-6urjd)

SCXML is the W3C standard for statecharts. Round-tripping through
SCXML lets re-frame2 machines be shared with non-CLJS tooling —
external workflow systems, Erlang `gen_statem`-derived tools,
Stately's importers, the xstate-visualizer. Same pure-data posture
as the Mermaid emitter: a machine definition in, an XML string out;
and the inverse on the read side.

```clojure
(:require [day8.re-frame2-machines-viz.scxml :as scxml])

(scxml/spec->scxml machine-spec)
;; => "<?xml version=\"1.0\" ...?>\n<scxml ...>...</scxml>"

(scxml/scxml->spec scxml-string)
;; => the parsed machine spec
```

### Round-trip

```clojure
(= machine-spec (-> machine-spec scxml/spec->scxml scxml/scxml->spec))
```

holds for the supported subset. It is **not** exact for the shapes
listed under [Not supported](#not-supported-lossy-or-omitted) below —
notably a machine-level (top-level) `:on` fallback, which W3C SCXML
cannot host as a root `<transition>`, so it is exported as a
documenting comment and does **not** survive the parse back.

### Supported grammar subset

| Re-frame2 | SCXML mapping |
|---|---|
| `:initial`                            | `<scxml initial="...">` |
| `:states` (flat)                      | `<state id="...">` |
| `:states` (compound)                  | nested `<state>` with `initial` |
| `:final? true`                        | `<final id="...">` |
| `:on {:event :target}`                | `<transition event="event" target="target" type="internal"/>` |
| `:on {:event {:target ... :guard G}}` | `<transition cond="G" .../>` |
| Self-target (`:target :same-state`, or a keyword naming the state's OWN key) (rf2-0pp6as) | `<transition … target="<source-id>" type="internal"/>` — references the SOURCE state's REAL declared id (NEVER the dangling `same_2dstate` phantom); `type="internal"` is the explicit XState-v5 internal default. Round-trips to the canonical `:same-state`. |
| `:reenter? true` (any target) (rf2-9dj21r) | `type="external"` — the EXTERNAL restart axis (re-run `:exit`+`:entry`); a target-bearing transition WITHOUT `:reenter?` carries `type="internal"`. |
| `:after {ms :target}`                 | `<transition event="after.ms" target="target"/>` |
| `:always [...]`                       | `<transition target="..."/>` (eventless) |
| `{:type :parallel :regions ...}`      | `<parallel>` containing region `<state>`s |
| `{:type :history :deep? <b> :default-target <t>}` (rf2-m285a) | W3C `<history type="shallow\|deep">` inside the owning compound; a `:default-target` rides a default `<transition target="…"/>`. NEVER occupiable (a transition TO it resolves to the recorded/default leaf), so it emits `<history>`, never `<state>`/`<final>`. Round-trips back to `:type :history`. |
| Namespaced ids (`:auth/login`)        | `auth__login` (hex-escaped; `__` separates ns from name) |
| Multi-dot-ns ids (`:my.app/login`)    | `my_2eapp__login` (ns dots escaped to `_2e`) |
| Vector-path targets (`[:parent :child]`) | `parent___child` (`___` joins path SEGMENTS) |
| Nested-state ids                      | fully qualified (root→leaf, `___`-joined) — unique xsd:ID |

> **Id codec — injective + xsd:ID-conformant (rf2-mnp93.1/.7,
> supersedes rf2-csq75).** SCXML state ids are `xsd:ID` (XML NCName):
> letters / digits / `-` `.` `_`, and crucially **no `:`**. The codec
> HEX-ESCAPES every keyword namespace/name char outside `[A-Za-z0-9]` to
> `_<2-hex>` (`.` → `_2e`, `?` → `_3f`, and the underscore itself → `_5f`)
> and joins parts with two RESERVED MARKERS the escaper can provably
> never emit:
>
> - `__` (double underscore) separates a *namespaced keyword's* namespace
>   from its name: `:auth/login` → `auth__login`,
>   `:my.app.auth/login` → `my_2eapp_2eauth__login` (the namespace dots
>   are escaped, so the single `__` marks the ns/name boundary regardless
>   of how many dots the namespace has).
> - `___` (triple underscore) joins the *segments of a vector path*:
>   `[:authenticated :browsing]` → `authenticated___browsing`;
>   a namespaced segment keeps its own `__`, e.g. `[:auth/region :browsing]`
>   → `auth__region___browsing`.
>
> Because the segment escaper never produces two consecutive underscores,
> neither marker can collide with segment content or with each other, so
> the codec is **fully injective for ANY keyword** — including
> multi-segment namespaces and dotted names, which the pre-mnp93.1
> `.`-as-ns/name scheme corrupted (`:my.app.auth/login` and
> `:my/app.auth.login` both encoded to `my.app.auth.login`). State ids
> are additionally **fully PATH-QUALIFIED** (root→leaf), so two
> same-named nested states emit UNIQUE xsd:IDs and transition targets
> reference those same unique ids — valid SCXML a strict external
> consumer accepts (the pre-fix bare-name ids produced duplicate xsd:IDs;
> the csq75 `:`-path separator was not even a valid xsd:ID char). The
> guard `cond=` attribute is the same hex-escaped keyword encoding, and a
> user event named `after.*` / `done.state.*` no longer aliases the
> synthetic timer / `:on-done` encodings (those carry a literal `.` the
> codec never emits for a real keyword — rf2-mnp93.2/.3).

### Not supported (lossy or omitted)

- `:spawn-all` rows — omitted; the parent state renders without
  spawn affordances.
- **Internal-default self / proper-ancestor self-transition semantics
  (rf2-0pp6as).** re-frame2 / XState v5 make a targeted transition
  INTERNAL by default — the targeted state's own `:exit` / `:entry` do
  **not** re-run (see [Spec 005 §Self-transitions](../../../spec/005-StateMachines.md)).
  W3C SCXML's `type="internal"` only changes the transition domain for a
  **compound source whose target is a proper descendant** (the one case
  where the export is the exact equivalent — it emits
  `target="<descendant-id>" type="internal"`). For a **self-target**
  (source == target) or a **proper-ancestor** target, SCXML re-enters the
  source regardless of `type`, so the re-frame2 internal default has no
  exact SCXML equivalent. The export still references the source state's
  **real declared id** (never the pre-fix dangling `same_2dstate`
  phantom) and emits `type="internal"` to record the intended axis, and
  the local `scxml->spec` round-trips the `:same-state` sentinel exactly —
  but a strict external SCXML engine executing the imported self-target
  will re-run the source's exit/entry where re-frame2 would not. This
  irreducible loss is documented rather than masked by the round-trip
  oracle.
- Machine-level (top-level) `:on` fallback transitions — W3C SCXML
  has no clean root-fallback slot (`<scxml>` does not host
  `<transition>` children per the schema, and the import side drops
  root-level transitions), so these are exported as a documenting XML
  comment and do **not** round-trip back through `scxml->spec`.
- `:tags` — re-frame2-specific; not part of W3C SCXML.
- `:action`s and guard FN bodies — only the *names* survive
  (SCXML `cond="name"` for guards; entry/exit `<script>` would
  require evaluation context, so names are preserved as XML
  comments on imports/exports). An INLINE-FN `:guard` / `:action`
  (the Spec 005 escape hatch) is **lossy-not-crash** (rf2-m285a): the
  exporter surfaces the fn's `:name` meta or a stable `"fn"` fallback
  (consistent with the chart + Mermaid emitters, and the API promise
  that fn bodies are lossy, NOT unsupported) — pre-fix it threw a
  `ClassCastException` when passed to the keyword id codec.
- Source-coord metadata — stripped at export time (same posture as
  share-URL encoding; see [Principles §No session data in shares](./Principles.md)).

### Error modes

Per the thrown-error contract ([Spec 009 §The thrown-error
shape](../../spec/009-Instrumentation.md)): `:rf.error/id` is the canonical
machine discriminator (tools branch on it), `:reason` is the human
sentence, and `ex-message` leads with the sentence and trails the
`[:rf.error/<id>]` token.

| `:rf.error/id` | Meaning |
|---|---|
| `:scxml/invalid-spec` | Input spec missing `:initial` / `:states` (or `:type :parallel` / `:regions`). |
| `:scxml/parse-error`  | Input XML is malformed or missing the `<scxml>` root. |

## AI-generate-a-machine (v1.1, rf2-1bncf)

A pure library fn that takes a natural-language prompt and returns
a normalised re-frame2 machine spec. The LLM call is pluggable —
the fn accepts an injected `:resolver` so callers wire in whichever
LLM bridge fits their environment (Anthropic API / OpenAI API /
local Ollama / Xray's chat seam / re-frame2-pair-mcp).

```clojure
(:require [day8.re-frame2-machines-viz.ai-generate :as ai])

(ai/generate-machine "a login flow with idle, loading, success and error states"
                     {:resolver (fn [prompt] (call-anthropic prompt))})
;; => {:initial :idle
;;     :states  {:idle    {:on {:login :loading}}
;;               :loading {:on {:ok :success :err :failed}}
;;               :success {:final? true}
;;               ;; EP-0011 — :err is a FAILURE terminal, so it carries
;;               ;; :error? true: the machine completes :status :error and
;;               ;; routes a spawning parent's :on-error (vs the success
;;               ;; terminal's :status :ok / :on-done). A non-error terminal
;;               ;; stays a plain {:final? true}.
;;               :failed  {:final? true :error? true}}}
```

The system prompt (`ai/system-prompt`) teaches this distinction: a
terminal that represents the machine *failing* (a load error, an auth
rejection, a payment decline) is `{:final? true :error? true}`; a
successful terminal stays `{:final? true}`. An ordinary in-flow error
state the user can retry from is **not** terminal at all (it keeps
running), so it carries neither bit. This keeps a generated child
machine's completion **status** (Spec 005 §`:final?` /
[EP-0011](../../../spec/005-StateMachines.md)) honest — a failure
outcome does not silently complete as a success.

### Contract

- `(generate-machine user-prompt opts)` returns the validated spec
  the same shape `reg-machine` accepts and `(rf/machine-meta id)`
  returns.
- `opts` recognises:
  - `:resolver` — `(fn [prompt-string] llm-response-string)`. Required.
    The namespace ships no default LLM bridge — production callers
    inject one, tests inject a stub returning canned EDN.

### Two-layer design

The implementation separates the I/O boundary (the injected
resolver) from the parse/validate step (this ns). The fn:

1. Composes `system-prompt + user-prompt` into a single string via
   `ai/build-prompt` (the canonical system prompt lives at
   `ai/system-prompt`, exposed as a Var for audit / multi-turn
   composition).
2. Hands the prompt to `:resolver` and waits for a string response.
3. Strips fenced code blocks (```clojure / ```edn / bare) tolerantly,
   so the LLM may emit prose around the EDN form.
4. Parses the EDN form and validates it carries `:initial` + non-
   empty `:states` (or `:type :parallel` + non-empty `:regions`).

### Reserved namespaces

Generated machines use re-frame2's normal id conventions — feature-
prefixed keywords (`:auth/idle`, `:cart/loading`), hyphenated bare
names (`:idle`, `:loading-failed`). The system prompt asks the LLM
to follow them; the parser does not enforce them (an LLM that
emits `:loadingFailed` produces a working spec the caller can
clean up or accept as-is).

### Error modes

Per the thrown-error contract ([Spec 009 §The thrown-error
shape](../../spec/009-Instrumentation.md)): `:rf.error/id` is the canonical
machine discriminator (tools branch on it), `:reason` is the human
sentence, and `ex-message` leads with the sentence and trails the
`[:rf.error/<id>]` token.

| `:rf.error/id` | Meaning |
|---|---|
| `:ai-generate/no-resolver`  | `:resolver` opt was not provided. |
| `:ai-generate/parse-failed` | Resolver output could not be parsed as EDN. |
| `:ai-generate/invalid-spec` | Parsed value was not a valid machine shape. |

### Determinism

The fn itself is deterministic given a deterministic resolver. LLM
resolvers are not deterministic by default; for reproducible tests
inject a stub mapping known prompts to canned EDN responses (see
the AI-generate test ns for examples).

## Public CLJS API surface — summary

```clojure
day8.re-frame2-machines-viz.chart/MachineChart    ; component (Reagent)
day8.re-frame2-machines-viz.adapters.react-chart/MachineChartReactClass ; rf2-yg9he — reactified React class
day8.re-frame2-machines-viz.adapters.react-chart/chart-element          ; rf2-yg9he — CLJS props → React element
day8.re-frame2-machines-viz.adapters.uix/MachineChart   ; rf2-yg9he — UIx shell ($-mountable)
day8.re-frame2-machines-viz.adapters.helix/MachineChart ; rf2-yg9he — Helix shell ($-mountable)
day8.re-frame2-machines-viz.share/encode-share-url
day8.re-frame2-machines-viz.share/decode-share-url
day8.re-frame2-machines-viz.mermaid/emit          ; pure fn — definition → string (rf2-sqhqu — relocated into this tool jar; runtime artefact is pure-engine)
day8.re-frame2-machines-viz.scxml/spec->scxml     ; v1.1 — pure fn
day8.re-frame2-machines-viz.scxml/scxml->spec     ; v1.1 — pure fn
day8.re-frame2-machines-viz.ai-generate/generate-machine ; v1.1 — pluggable LLM seam
day8.re-frame2-machines-viz.ai-generate/build-prompt     ; v1.1 — prompt composer
day8.re-frame2-machines-viz.ai-generate/system-prompt    ; v1.1 — Var
day8.re-frame2-machines-viz.export/chart-as-png!
day8.re-frame2-machines-viz.export/chart-as-svg
day8.re-frame2-machines-viz.export/chart-as-mermaid
day8.re-frame2-machines-viz.export/share-url
day8.re-frame2-machines-viz.export/copy-png-to-clipboard!
day8.re-frame2-machines-viz.export/copy-svg-to-clipboard!
day8.re-frame2-machines-viz.export/copy-mermaid-to-clipboard!
day8.re-frame2-machines-viz.export/copy-share-url-to-clipboard!
```

No global state, no init function. The component is referentially
transparent over its props; the share / export functions are pure
(modulo the clipboard). The v1.1 SCXML + AI-generate surfaces are
pure-data and JVM-callable.

## See also

- [`000-Vision.md`](./000-Vision.md) — scope + non-goals + roadmap.
- [`Principles.md`](./Principles.md) — load-bearing principles.
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — locks; cites Xray 003 lift-points. Lock #8, Lock #9, and Lock #11 are the rationales behind [§Performance invariants](#performance-invariants).
- [`tools/xray/spec/003-Machine-Inspector.md`](../../xray/spec/003-Machine-Inspector.md) — embedding-host contract; the source spec these surfaces lifted from.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry the chart visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight consumes.
