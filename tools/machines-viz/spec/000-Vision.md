# 000-Vision: Machines-Viz — the re-frame2 state-chart component

## Quality bar

The v1.0 `MachineChart` is held to an explicit, named standard:

> **Good interactive, integrated visualisation, robust to
> complexity, beautiful, highly ergonomic.**

The comparator is **Stately Studio**. Machines-Viz v1.0 MUST match
Stately Studio's level on **interactivity** (clickable nodes /
edges / guards / actions with live response), **layout robustness**
(nested compound states, parallel regions, multi-source edges, no
edge-crossing collapse at machine sizes seen in real apps), and
**visual ergonomics** (legibility at glance, motion that aids
comprehension rather than ornament, density that fits a Causa
panel without scroll-thrash).

Stately Studio is the floor, not the ceiling:

- **Floor:** if we ship something weaker than Stately Studio on
  any of the three axes above, the chart is not done.
- **Ceiling-not-imposed-by-prior-art:** where divergence from
  Stately Studio is principled (aesthetic coherence with Causa,
  full visual control, native re-frame2 plumbing), Machines-Viz
  **does** diverge — by intent, with rationale captured in the
  decision-trace below. Stately Studio's choices are inputs, not
  defaults.

This bar is load-bearing for the rest of this doc. Every design
decision in §Rendering stack and every entry in §Decision trace
below is calibrated against it.

## Decision-trace requirement (XState / Stately prior art)

Every design decision in this spec MUST trace to Stately / XState
prior art using the four-row template:

```
**Decision:** <name>
**Stately / XState's choice:** <what they do + why>
**re-frame2's choice:** <same or different>
**Rationale if diverge:** <explicit reason — Lock #1, no-paid-
product, native-plumbing, aesthetic coherence, …>
```

The rule is not "copy Stately." The rule is **make every choice
traceable to the closest prior-art comparator**. The spec is then
auditable against the prior art rather than reading as an opinion
piece. Where re-frame2 diverges, the divergence is named and the
reason is on the page; where re-frame2 aligns, the agreement is
named and the input is acknowledged.

This template applies to:

- Rendering stack choices (layout engine, renderer, static export
  format) — see §Decision trace below.
- Data-model choices (machine-definition format, share-URL
  encoding, snapshot transport).
- Runtime-integration choices (active-state highlighting
  transport, instance-attach mechanism).
- UX choices that have a clear Stately analogue (zoom / pan,
  minimap, source-jump, sim playground).

New design decisions added to this spec follow the same template.
Where no clear Stately / XState analogue exists, the decision row
says so explicitly (`re-frame2-native; no Stately analogue`).

## Why it exists

re-frame2 ships state machines (Spec 005) as a first-class registry.
A registered machine's transition table is data; its runtime
snapshot lives at `[:rf/machines <id>]`; its lifecycle, transitions,
microsteps, `:after` timers, and `:spawn-all` joins all emit
structured trace events (Spec 009). Visualising a machine is a
direct projection of those substrates — no new runtime surface
required.

`tools/machines-viz/` ships the projection as **one component**
(`MachineChart`) plus a **read-only viewer page** that renders a
chart from a URL fragment. Causa's Machine Inspector panel
([`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md))
embeds it as content; Story's per-variant machine panel embeds it
the same way. The component is the single source of charting truth;
the hosts add transition-history ribbons, source-coord jumps, and
panel chrome around it.

The read-only viewer is the receiving end of the "Copy machine as →
Share URL" affordance — a chart pasted into a PR description, a
design doc, a Slack thread, or a whiteboard.

## What it is

A standalone CLJS jar — `day8/re-frame2-machines-viz` — that
exports:

1. **The `MachineChart` component.** A React-flavoured CLJS
   component (substrate-agnostic via the v2 `:view` registration
   surface; substrates' adapters re-export) that renders a
   directional state-chart for one registered machine. It reads
   `[:rf/machines <id>]` for live-highlight and subscribes to the
   Spec 009 trace stream for transition / microstep / timer /
   invoke-all granularity.
2. **The read-only viewer page.** A statically hostable single-file
   page (`tools/machines-viz/public/viewer.html`) that decodes a
   `#machine=<base64-edn>` URL fragment and renders the encoded
   chart in a no-runtime `MachineChart` (the same component with
   click handlers no-op'd and live-trace subscription disabled).
3. **The share-URL encoding rules.** EDN → transit-write →
   base64url, with a `:rf.machines-viz.share/v1` envelope keying the
   encoding version. Roundtrips: `(encode-share-url chart-state)`
   and `(decode-share-url url)`.
4. **PNG and SVG exporters.** Client-side rasterisers that emit a
   chart image at 2x DPR (PNG) or as `image/svg+xml` (SVG) with
   embedded fonts. Both include `<title>` / `<desc>` (SVG) or alt-
   text sidecar (PNG) summarising the machine id, current state,
   and node / transition counts.
5. **Mermaid `stateDiagram-v2` exporter.** A pure-data emitter
   (`day8.re-frame2-machines-viz.mermaid/emit`) that takes a
   registered machine definition and returns a fenced markdown
   code block suitable for paste into a GitHub README / PR
   description / Notion. The emitter is lossy by design — `:after`
   target edges render but countdown rings are omitted, parallel
   regions render as independent region trees without broadcast
   macrostep semantics, and `:spawn-all` rows are omitted (Mermaid
   stateDiagram-v2 doesn't model those runtime semantics). The loss
   is flagged in a `%% comment` at the top of the output. Per
   [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.

## What it isn't

- **Not** a visualiser-as-product. The Stately Visualizer +
  Stately Studio occupy that lane — paste-and-render editors,
  saved-machine registries, multiplayer canvases, commercial
  SaaS shells. Per `ai/findings/xstate-advanced-features-2026-05-13.md`
  (gitignored working note), Stately's own Visualizer is officially
  deprecated within their docs (replaced by the commercial Stately
  Editor); investing in the visualiser-as-product lane chases a
  sunsetted product.
  Machines-Viz is the **component the framework's own tools
  embed**, plus a read-only viewer for shared URLs. See
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #1.
- **Not** a session recorder. The share-URL serialises **only**
  machine topology + a single current-state snapshot. No trace
  stream, no epoch buffer, no app-db slices, no conversation
  buffer. Per Causa Lock #4 (session export forbidden), lifted
  into Machines-Viz as load-bearing.
- **Not** an *interactive* Markdown-embed. Machines-Viz **does**
  ship a Mermaid `stateDiagram-v2` exporter at v1.0 (per rf2-deo2i;
  see [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4); it
  emits a fenced markdown code block for paste into a GitHub README
  / PR description / Notion. The emitter is **lossy by design**:
  `:after` target edges render but rings are omitted, parallel
  region broadcast macrosteps are flattened to independent region
  trees, and `:spawn-all` rows are omitted. The loss is flagged in
  a `%% comment` at the top of the output. The share-URL viewer is
  the interactive complement that renders the full topology; the
  Mermaid emitter is the static-paste affordance.
- **Not** an editor. Machines are authored in code via
  `reg-machine`; Machines-Viz visualises what's already
  registered. There is no canvas-driven authoring affordance, no
  edit-the-machine-and-export-EDN flow. Post-v1 candidates
  exist (a "paste a machine definition" web playground) but
  v1.0 ships read-only.
- **Not** a registrar, dispatch, effect, or component layer.
  Machines-Viz is a downstream consumer of the framework's
  instrumentation surface. If a chart needs data the framework
  doesn't emit, the answer is to file a `bd` bead against
  `spec/009-Instrumentation.md` — not to bolt a parallel
  surface onto Machines-Viz. Same posture Causa takes (per
  [Causa Principles §Observation only](../../causa/spec/Principles.md)).
- **Not** part of any production bundle. The bundle-isolation
  contract in [`tools/README.md`](../../README.md) holds: nothing
  under `implementation/` may `:require` from `machines-viz`.

## What re-frame2 unlocks for Machines-Viz

Each row is "new in re-frame2 → new charting story Machines-Viz
must tell."

| re-frame2 capability | Machines-Viz surface |
|---|---|
| **`[:rf/machines <id>]` snapshot location** (Spec 005) | Live-highlight reads the snapshot directly; deref drives node-pulse on the active state. |
| **`:rf.machine/transition` trace events** (Spec 009) | Edges glow on the matching event; the chart animates the from→to transition. |
| **`:rf.machine.microstep/transition`** | Microstep-granular replay within an `:always`-driven cascade; intermediate states render with a "microstep" badge. |
| **`:rf.machine.timer/*` events** | `:after`-bearing states render a countdown ring; the ring fills at 60Hz when the panel is visible. |
| **`:rf.machine.spawn-all/*` events** | Parallel-child rows render with per-child state plus the join-condition label (`:all` / `:any` / `{:n N}` / `{:fn ...}`). |
| **`:rf.machine/spawned` + `-destroyed`** | Dynamic actors appear / disappear in the parent chart's "spawned" tray; gensym'd ids surface with `:system-id` aliases parenthesised. |
| **State-tags** (Spec 005 §State tags) | Tag-membership coloured rings on state nodes; tags listed in the node tooltip. |
| **Source-coord stamping** (Spec 001) | Every state, transition, guard, and action carries a source-coord chip — click jumps to the registration. |
| **`reg-machine` definition as data** | The whole chart layout is a function of the transition table; no reflection, no instrumented build. |
| **Compound states + parallel regions** | Nested compound states render with recursive active-child highlighting; parallel regions render as side-by-side panes. |
| **Final states with `:on-done`** | `:final?` nodes render with a doubled border; entering one fires the `:on-done` edge highlight before the auto-destroy. |

## Where it lives in the stack

```
              ┌────────────────────────────────┐
              │  Causa (Machine Inspector)     │   ─  embeds  ─┐
              │   • transition-history ribbon  │               │
              │   • source-coord chips         │               │
              │   • frame picker               │               │
              └────────────────────────────────┘               │
                                                               ▼
              ┌────────────────────────────────┐
              │  Story (per-variant machine    │   ─  embeds  ─┐
              │  panel)                        │               │
              │   • per-variant scope          │               │
              │   • compact chrome             │               │
              └────────────────────────────────┘               │
                                                               ▼
                              ┌─────────────────────────────────────┐
                              │  tools/machines-viz/                │
                              │   • MachineChart component          │
                              │   • read-only viewer page           │
                              │   • share-URL encoder / decoder     │
                              │   • PNG / SVG export                │
                              └─────────────────────────────────────┘
                                                               │
                                                               ▼
                              ┌─────────────────────────────────────┐
                              │  implementation/machines + core     │
                              │   • reg-machine + transition tables │
                              │   • [:rf/machines <id>] snapshots   │
                              │   • Spec 009 trace bus events       │
                              └─────────────────────────────────────┘
```

`tools/causa/` and `tools/story/` both depend on
`tools/machines-viz/`. The arrow does not invert. Machines-Viz
does not know about Causa's chrome or Story's variant runtime —
it only knows it has a `:machine-id`, a `:frame-id`, and two
callbacks the host wired.

## Surface set

v1.0 `MachineChart` is one component rendered across **three
interactive surfaces** plus the existing **Mermaid static
emitter**:

1. **Causa panel — the canonical observability surface.** The
   Machines tab of Causa's 7-tab detail panel embeds
   `MachineChart` as its content view (per
   [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)).
   This is the front door for live machine observability — the
   panel wires the transition-history ribbon, source-coord jumps,
   frame picker, UC1 sim playground, and UC2 instance browser
   around the chart. Causa is the primary consumer; the chart's
   ergonomics are tuned for this surface first.

2. **User-app drop-in — production observability of running
   machines.** The component ships as a substrate-agnostic
   `:view` registration that consumer apps can mount into their
   own UI to surface a registered machine to end-users or
   internal operators. **v1.0 in scope** — not speculative; the
   bundle-isolation contract holds (the component is a tool jar,
   so consumers opting in pay the bundle cost deliberately).
   Typical uses: internal admin dashboards visualising the order-
   fulfilment machine, support tools showing the state of a
   stuck workflow, an operator console for a long-running pipeline.

3. **Docs cells — interactive embedding in mkdocs pages.** The
   chart renders inside narrative docs (the re-frame2 guide,
   per-tool spec pages) so a reader can scrub through a state
   machine's transitions inline with the prose. v1.0 covers
   embedding a registered machine from a docs-time `reg-machine`
   form. **Klipse-style paste-and-render (an arbitrary EDN
   pasted into a page that renders without registration) is
   deferred to v1.1** per
   [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #1 (no
   visualiser-as-product); the v1.0 docs-cell surface uses the
   chart against a registered machine, not pasted text.

The **fourth surface** is the existing Mermaid
`stateDiagram-v2` emitter
(`re-frame.machines.mermaid` per rf2-deo2i / rf2-yamkm — pure
text-to-SVG via the Mermaid DSL):

4. **Mermaid static surface — docs prose, README, AI-pair chat
   replies.** Same emitter, different consumption — a fenced
   markdown code block pasted into a GitHub README, a PR
   description, a Notion doc, or a re-frame2-pair chat reply
   when the user isn't in a running-app session. Lossy by design
   (`:after` rings, `:spawn-all` rows, parallel-region macrostep
   semantics absent). Per
   [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.

**One model, four surfaces.** All four consume the same
`reg-machine` body (EDN). The interactive three share the
`MachineChart` component + the ELK + hand-rolled-SVG renderer; the
fourth shares the model but emits Mermaid DSL instead of SVG. The
shared model is the lever — features that arrive on the
interactive surfaces (state tags, `:spawn-all` join condition
labels, source-coord chips) cascade automatically; nothing is
re-derived per surface.

## Rendering stack

The v1.0 `MachineChart` is built on:

- **Layout engine: ELK.js** (Eclipse Layout Kernel, JS port).
  Hierarchical / compound-graph layout, handles nested states,
  parallel regions, multi-source edges with bundling. Same engine
  Stately Studio runs on; VS Code's `mermaid-state-machine`
  preview also uses it. Bundle ~600 KB. License: **EPL-2.0** —
  compatibility with re-frame2's MIT posture verified 2026-05-19
  (rf2-9lath); see §License compatibility below and the
  §License chain decision-trace row.
- **Interactive renderer: `@xyflow/react` (React Flow).**
  Production-grade React graph renderer. The same engine Stately
  Studio uses for its commercial editor. Custom node + edge
  components (`chart.nodes` + `chart.edges`) recover the Causa
  visual identity (rounded-rect bodies, state-tag pills, final-
  state double border, active-state cyan tint + emphasised
  stroke, mono typography). xyflow handles pan / zoom / fit /
  minimap natively; elk.js runs as xyflow's layout backend
  inside the chart component. Per the **2026-05-21 override**
  recorded in §Decision trace §Interactive renderer below
  (rf2-gpzb4) — Mike's override of the 2026-05-19 hand-rolled-
  SVG lock after the polish-within-current-stack path repeatedly
  failed to reach the Stately Studio quality bar.
- **Static renderer: Mermaid** (already shipped, per the rf2-yamkm
  relocation to `implementation/machines/src/re_frame/machines/mermaid.cljc`).
  Stays in its lane: text-to-SVG via the `stateDiagram-v2` DSL for
  Markdown-embed cases; not extended into interactive territory.

**Three renderers, one shared model.** All three consume the same
`reg-machine` body. The interactive surfaces (Causa panel,
user-app drop-in, docs cells) share the xyflow + elkjs renderer;
the static surface (Mermaid) is a parallel emitter against the
same model.

| Renderer | Layout | Output | Surface | Status |
|---|---|---|---|---|
| Mermaid | Mermaid-internal (Dagre-derived) | static SVG via DSL | docs prose, README, AI-pair chat replies | shipped (`re-frame.machines.mermaid`) |
| xyflow + elkjs (`MachineChart`) | ELK.js inside `@xyflow/react` | interactive React canvas | Causa panel + user-app drop-in + docs cells | v1.0 commitment (Phase 1 — rf2-gpzb4 xyflow migration; Reagent-only adapter, UIx/Helix substrate adapters follow-on) |

### Alternatives rejected (with reasons)

- **Graphviz-WASM (hpcc-js-wasm).** Excellent quality but
  ~1.5 MB. The Causa panel must feel responsive; the bundle cost
  is not worth it.
- **Dagre / dagre-d3.** Smaller (~100 KB) but weak on nested
  compound states; maintenance stalled. Stately migrated AWAY
  from Dagre to ELK; re-frame2 will not migrate towards a
  sunsetted engine.
- **Cytoscape.js.** Full graph library; canvas-first; ~350 KB;
  more opinion than the bar requires.
- **D3-hierarchy + custom edge routing.** End up reinventing ELK
  badly.
- **React Flow with default look.** Opinionated default aesthetic
  (rounded-corner nodes, default edge style) collides with the
  "beautiful + integrated" bar without significant override work.
  Rejected 2026-05-19; **superseded 2026-05-21** — the override
  (rf2-gpzb4) accepts xyflow as the renderer BUT with extensive
  custom node + edge components that recover the Causa identity;
  the default look itself remains rejected.
- **JointJS / GoJS.** Commercial; wrong fit for re-frame2's
  open-source posture.

## Decision trace

The locked rendering-stack decisions (rf2-04gvh closure, locked
2026-05-19), expressed in the §Decision-trace template above.

### Layout engine

**Decision:** Layout engine for the interactive `MachineChart`.

**Stately / XState's choice:** **ELK.js** (Stately Studio's
production stack). Stately's legacy Visualizer used Dagre; Stately
migrated to ELK as machine sizes outgrew Dagre's nesting / parallel-
region handling.

**re-frame2's choice:** **ELK.js. Same choice.**

**Rationale if diverge:** No divergence. Stately got this right;
the migration story (Dagre → ELK) is the evidence. Adopting ELK
sets the same layout-robustness floor without re-walking the
exploration.

### Interactive renderer

**Decision:** Library for the interactive chart surface (where
ELK's layout result becomes interactive DOM).

**Stately / XState's choice:** **React Flow** (Stately Studio's
current stack). Stately's legacy Visualizer used custom SVG; the
Studio rebuild moved to React Flow for time-to-MVP and built-in
pan / zoom / minimap affordances.

**re-frame2's choice (2026-05-21 — superseded the 2026-05-19
lock):** **`@xyflow/react` (React Flow). Same as Stately.**

**Rationale (2026-05-21 override per rf2-gpzb4):** Mike's
override of the 2026-05-19 hand-rolled-SVG lock after the polish-
within-current-stack path was attempted and the result was still
poor relative to the §Quality bar (Stately Studio is the
explicit comparator). xyflow is the same engine Stately Studio
uses; adopting it is the faster path to that quality bar than
continuing to lift the hand-rolled stack.

**Accepted trade-offs** (captured here so future debate doesn't
re-litigate them — these were knowingly traded, not overlooked):

- **Substrate-agnostic hiccup contract LOST.** xyflow is a React
  component library; the chart becomes React-component-shaped
  rather than substrate-agnostic hiccup data. Phase 1 ships
  Reagent-only; UIx and Helix substrate adapters are follow-on
  beads. (The chart was always going to bottom out at a React
  renderer eventually; this just moves the boundary earlier.)
- **JVM-testability of the rendered output LOST.** The previous
  `chart/svg.cljc` was `.cljc`-testable from `clojure -M:test`;
  the new `chart.cljs` is CLJS-only because xyflow is a JS lib.
  The **parse layer** (`chart/layout.cljc`) stays JVM-testable —
  pure data → graph map, no xyflow dependency — so the
  substrate-agnostic graph contract still has JVM coverage.
- **Aesthetic coherence requires extensive custom node + edge
  components.** xyflow's default look (rounded-corner default
  nodes, generic edge style) collides with the Causa devtool
  aesthetic. Custom Reagent node + edge components in
  `chart.nodes` + `chart.edges` recover the Causa identity
  (rounded-rect 6px-radius lock per rf2-g6cig, mono typography
  per `theme/tokens`, state-tag pills per rf2-m1b88, final-
  state double border, active-state cyan tint + emphasised
  stroke).
- **Bundle cost.** xyflow ≈ 60 KB gzipped; elkjs ≈ 250 KB
  gzipped. Both `devDependency`-gated; production bundles MUST
  NOT pull them. The bundle-isolation script
  (`check-bundle-isolation.cjs`) pins sentinels for both.

**Previous (2026-05-19) rationale for hand-rolled SVG**
(retained here for the record; the 2026-05-21 override
supersedes it):

> Three reasons compounded — aesthetic coherence with Causa,
> full visual control of every pixel, and distinctive look as a
> feature not a bug. The cost was named: ~weeks of polish to
> reach React Flow's baseline. The pay-off was named: aesthetic
> coherence and visual distinctiveness against the Stately-clone
> risk.

The override (2026-05-21) accepts that the aesthetic-coherence
goal is reachable via custom xyflow node + edge components
(deferring a chunk of work into the component library) while
gaining xyflow's production-grade pan/zoom/fit/minimap and
edge-routing for free. The Stately-clone risk is mitigated by
the custom component layer; we don't ship the xyflow default
look.

### Static renderer

**Decision:** Renderer for the static / Markdown-embed surface
(README, AI-pair chat replies, prose docs).

**Stately / XState's choice:** **None.** Stately Studio replaces
static export with the live-editor canvas (the product offering).
The legacy Visualizer offered a "Copy as PlantUML" affordance but
no Mermaid `stateDiagram-v2` output.

**re-frame2's choice:** **Mermaid `stateDiagram-v2`. Diverge
(re-frame2 ships a static surface; Stately doesn't).**

**Rationale if diverge:** Mermaid is the markdown-paste workhorse
for GitHub READMEs, PR descriptions, Notion, Slack code blocks.
Stately's commercial model doesn't optimise for that surface
because their utility lever is the editor, not the embed. re-frame2
already shipped the Mermaid emitter (per rf2-deo2i and the rf2-yamkm
relocation); the static surface is essentially free value over the
shared model. Lossy by design (`:after` rings + `:spawn-all` rows
absent — flagged inline in the output's `%% comment`); the
interactive surfaces carry the lossless cases.

### Model format

**Decision:** Format the chart consumes as input.

**Stately / XState's choice:** **TypeScript object literals**
(XState v5: `createMachine({ ... })` over a TS-typed config
object). Stately Studio's editor produces and ingests the same
shape (plus JSON for the share / export pipeline).

**re-frame2's choice:** **EDN** — the `reg-machine` body, with
keywords, sets, nested maps, and namespaced keys preserved
verbatim. **Diverge.**

**Rationale if diverge:** re-frame2's authoring surface is EDN
(per [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md)); the
chart consumes the registered shape directly. Converting to JSON
would lose keyword types, set-vs-vector distinctions, and the
namespaced-keys idiom — all of which the chart wants to render
(state tags, fully-qualified guard / action names, the
`:rf.machine.spawn-all/...` namespace conventions). Same EDN-first
posture re-frame2 takes across the wire (transit on the share-URL,
EDN on every registry surface).

### License chain

**Decision:** License-compatibility posture for the rendering
stack.

**Stately / XState's choice:** Stately Studio is **closed
proprietary** on top of MIT / BSD libraries (React, React Flow,
ELK). The product's value-add lives behind a SaaS / commercial
licence; the OSS pieces sit underneath.

**re-frame2's choice:** **MIT throughout, with a deliberate
EPL-2.0 sign-off for ELK (verified 2026-05-19, rf2-9lath).** Same
OSS posture as the rest of re-frame2; no closed components; no
SaaS layer.

**Rationale if diverge:** This is divergence on **business
model**, not on the OSS pieces. EPL-2.0 (ELK's license) is
compatible with MIT use — EPL-2.0 is weak copyleft at the file
level: copyleft applies to modifications of the EPL-2.0 source
files themselves, not to consumers that import and link against
them as a runtime dependency. The 5-minute sign-off owed in
rf2-04gvh's closure was completed 2026-05-19 (rf2-9lath); see
§License compatibility below for the full verdict.

### Active-state highlighting transport

**Decision:** How the chart learns which state(s) are currently
active, and how live transitions trigger edge highlights.

**Stately / XState's choice:** **`@xstate/inspect` WebSocket
bridge.** XState's runtime inspect protocol opens a WebSocket to
a separate inspector window (or browser extension); the running
machine sends transition events over the wire; the inspector
renders the chart and highlights.

**re-frame2's choice:** **Spec 009 trace bus, in-process.** The
chart subscribes to the existing trace bus
([`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md))
for `:rf.machine/transition`, `:rf.machine.microstep/transition`,
`:rf.machine.timer/*`, and `:rf.machine.spawn-all/*` events; live
highlighting is a `swap!` on a local atom triggered by trace
callbacks. **Diverge.**

**Rationale if diverge:** re-frame2 already has a structured
trace bus carrying every event the chart needs, in-process, at
near-zero cost. Inventing a WebSocket bridge would duplicate
existing instrumentation and impose a separate-window UX where
the in-app Causa panel does not need one. The Stately choice is
optimised for "attach my external inspector to a running
production app from another window"; that use case is served by
`tools/re-frame2-pair-mcp/` over raw nREPL (the AI-pair surface),
not by `MachineChart`'s rendering plane.

## License compatibility

ELK.js ships under **EPL-2.0** (Eclipse Public License 2.0).
re-frame2 ships under **MIT**.

**Compatibility verified 2026-05-19 (rf2-9lath):** EPL-2.0 is a
weak copyleft license operating at the **file level**, not the
project level. Using ELK.js as a runtime dependency in a
re-frame2-derived consumer bundle is permitted: the ELK.js code
retains EPL-2.0 within the combined work; the consumer's own code
retains MIT. Modifications to the ELK.js source files (if any)
must remain EPL-2.0; the wider project does not inherit copyleft
obligations.

For this project specifically: Machines-Viz imports and consumes
ELK.js as a **black-box runtime dependency**. We do not patch,
fork, or modify ELK.js source. No additional obligations arise
from EPL-2.0 beyond preserving the license notice in
distributions (the standard attribution rule, satisfied by
shipping the upstream license alongside the dependency in any
distribution that bundles it — handled by the package manager's
license-passthrough in the WASM/JS asset chain).

The same posture applies transitively. Anything downstream of
re-frame2 that includes ELK.js — consumer apps embedding the
`MachineChart` component in their bundles — inherits the same
weak-copyleft-at-the-file-level obligation. ELK.js source stays
EPL-2.0 within their bundle; their app code stays under whatever
license they choose. No relicensing is required.

References:

- [EPL-2.0 full text](https://www.eclipse.org/legal/epl-2.0/)
- [EPL FAQ (eclipse.org)](https://www.eclipse.org/legal/eplfaq.php)
- [ELK project (eclipse.org)](https://www.eclipse.org/elk/)
- [`elkjs` npm package](https://www.npmjs.com/package/elkjs)

## Scope and roadmap

### v1.0 (the foundation)

The component + the read-only viewer + the encoding rules — the
surface Causa and Story require to ship their panels.

- `MachineChart` component (per [`API.md`](./API.md) §MachineChart).
- Read-only viewer page (per [`API.md`](./API.md) §Read-only viewer).
- Share-URL encoding (per [`API.md`](./API.md) §Share-URL encoding).
- PNG + SVG exporters.
- Mermaid `stateDiagram-v2` exporter (per
  [`API.md`](./API.md) §Mermaid `stateDiagram-v2`) — the
  Markdown-paste affordance. Lossy by design (`:after` rings +
  `:spawn-all` rows omitted; flagged inline). Per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.
- Compound / parallel / `:after` / `:spawn-all` / `:final?`
  rendering as specified in
  [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
  §What the panel shows + §`:spawn-all` viz + §Performance.
- Source-coord chips on every clickable element (the host wires
  the editor URL handler via `:on-state-click` /
  `:on-transition-click`).
- Accessibility: SVG `<title>` / `<desc>` + machine summary text
  alternative. **Full chart alt-view defers to v1.1** (same
  posture Causa 003 §Accessibility takes — the transition-history
  ribbon + machine picker on the host side carry the accessible
  surface in v1.0).

### What v1.0 does NOT do

The non-goals are as load-bearing as the goals. v1.0 explicitly
does **not** include:

- **No standalone Visualizer product.** The Machines-Viz jar is a
  component the framework's own tools embed plus a read-only
  viewer for shared URLs — not a Day8-hosted SaaS, not a
  paste-and-render editor product, not a multi-user canvas. Per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #1
  (component-not-product). Stately Studio's lane is deliberately
  not contested.
- **No paste-and-render at v1.0.** Pasting arbitrary EDN into a
  page that renders without registration (Klipse-style live
  evaluation) is **deferred to v1.1**. The v1.0 docs-cell surface
  renders against a registered machine; the v1.0 viewer page
  renders against a share-URL payload (also a registered shape,
  just encoded for transit). The Klipse-style affordance is a
  candidate, not a commitment.
- **No editor surface.** `MachineChart` is a read-only renderer.
  Machines are authored in code via `reg-machine`; the EDN in
  source files is the source of truth. There is no canvas-driven
  authoring affordance, no edit-the-machine-and-export-EDN flow,
  no round-trip between a visual canvas and source.
- **No closed-source dependencies.** Every library in the
  rendering stack is open-source-compatible with re-frame2's MIT
  posture (ELK is EPL-2.0, deliberately signed-off as MIT-
  compatible for consumers). No commercial layout engines
  (JointJS / GoJS); no SaaS-only rendering services; no
  Day8-hosted infrastructure in the dependency graph.

### v1.1 candidates (not committed)

- Chart alt-view for screen readers (the v1 commitment deferred
  from accessibility).
- "Paste a machine definition" web playground hosted under the
  viewer page (Stately-Visualizer-style onboarding flow).
- D2 / `xstate-json` emitters alongside Mermaid (the topology fn
  generalises; D2 + xstate-json are different output grammars over
  the same input). Per [Spec 005 §Future §Diagram export](../../../spec/005-StateMachines.md#diagram-export-from-transition-tables).

### v1.1 shipped (rf2-1bncf + rf2-6urjd, 2026-05-21)

- **SCXML import / export** (rf2-6urjd) — `(scxml/spec->scxml spec)`
  + `(scxml/scxml->spec xml)` pure-data round-trip for non-CLJS
  sharing (Erlang `gen_statem`, external workflow systems, the
  xstate-visualizer). Per [`API.md`](./API.md) §SCXML import/export.
  Promoted from "Out of scope (any version)" — the W3C standard
  for statecharts is the interop floor for sharing machines with
  non-Clojure tooling, and the round-trip implementation is small
  and pure-data (no XML parser dep — string-based for the SCXML
  subset we emit).
- **AI-generate-a-machine** (rf2-1bncf) — `(ai-generate/generate-machine
  user-prompt opts)` library fn with a pluggable `:resolver` seam.
  Production callers inject an LLM bridge (Anthropic / OpenAI /
  local Ollama / Causa's chat / re-frame2-pair-mcp); tests inject a
  stub. Mirrors Stately Studio 2026's AI-generation feature. Per
  [`API.md`](./API.md) §AI-generate-a-machine.

### Out of scope (any version)

- A canvas-driven machine editor.
- A multi-user / multiplayer canvas.
- A saved-machine registry (the share-URL is the only persistence
  affordance; long-term storage is the user's problem).
- Statechart-from-non-machine-code visualisation (Stately Studio
  2026 visualises Redux without XState; not Machines-Viz's lane).
- A Day8-hosted SaaS shell.

## Peer-product comparison (where the v1 ship-list lands)

Beyond the Quality bar (§Quality bar, above — Stately Studio as
the named comparator), the v1 ship-list is calibrated against
three peer products:

- **Stately Visualizer** — the gorilla. Pastable EDN-equivalent
  rendering, animation, zoom/pan. Officially deprecated within
  Stately's docs; their commercial Editor is the recommended
  replacement.
- **XState Inspector (`@xstate/inspect`)** — runtime attach;
  live highlights; transition log; send-event surface.
- **Mermaid statechart** (`stateDiagram-v2`) — static rendering
  from text-as-code; the Markdown-embed workhorse.

Where Machines-Viz **wins** against the peer set (per
`ai/findings/sweep-tools-vs-sota-2026-05-13.md` §machines-viz):

- `:after` countdown rings + microstep replay (no peer has both).
- `:spawn-all` join visualisation with the join-condition label
  (`:all` / `:any` / `{:n N}` / `{:fn ...}`).
- Live trace integration tied to the Spec 009 bus.
- Source-coord per node — every state, transition, guard, action
  jumps to source.
- Bundle isolation by classpath, not by tree-shaking discipline.
- Mermaid `stateDiagram-v2` export as a peer to the SVG / PNG /
  share-URL trio — paste into a GitHub README and it renders.
  Stately Studio has no Markdown-paste posture; the XState
  Inspector has no exporter at all. Per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.

Where Machines-Viz **defers** to peers:

- Paste-and-render onboarding — v1.1 candidate; the v1 share-URL
  viewer is the closest thing.
- Statechart-from-non-machine code — Stately Studio's lane.

## See also

- [`Principles.md`](./Principles.md) — the load-bearing principles.
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — the locks; questions, options, picks (Lock #1 = component-not-product).
- [`API.md`](./API.md) — the consolidated public surface (`MachineChart` contract, viewer page URL, share-URL encoding).
- [`tools/causa/spec/000-Vision.md`](../../causa/spec/000-Vision.md) — Causa's Vision; the sibling observability surface that embeds `MachineChart` as the Machines tab.
- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — Causa's embedding-side spec; the source of truth for transition-history ribbon, source-coord integration, `:spawn-all` viz, share-affordance UX.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry + authoring surface Machines-Viz visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight + active-state transport consumes.
