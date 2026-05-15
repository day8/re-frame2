# 000-Vision: Machines-Viz — the re-frame2 state-chart component

## Why it exists

re-frame2 ships state machines (Spec 005) as a first-class registry.
A registered machine's transition table is data; its runtime
snapshot lives at `[:rf/machines <id>]`; its lifecycle, transitions,
microsteps, `:after` timers, and `:invoke-all` joins all emit
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
   macrostep semantics, and `:invoke-all` rows are omitted (Mermaid
   stateDiagram-v2 doesn't model those runtime semantics). The loss
   is flagged in a `%% comment` at the top of the output. Per
   [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.

## What it isn't

- **Not** a visualiser-as-product. The Stately Visualizer +
  Stately Studio occupy that lane — paste-and-render editors,
  saved-machine registries, multiplayer canvases, commercial
  SaaS shells. Per [`ai/findings/xstate-advanced-features-2026-05-13.md`](../../../ai/findings/),
  Stately's own Visualizer is officially deprecated within their
  docs (replaced by the commercial Stately Editor); investing in
  the visualiser-as-product lane chases a sunsetted product.
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
  trees, and `:invoke-all` rows are omitted. The loss is flagged in
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
| **`:rf.machine.invoke-all/*` events** | Parallel-child rows render with per-child state plus the join-condition label (`:all` / `:any` / `{:n N}` / `{:fn ...}`). |
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
  `:invoke-all` rows omitted; flagged inline). Per
  [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) §Lock #4.
- Compound / parallel / `:after` / `:invoke-all` / `:final?`
  rendering as specified in
  [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
  §What the panel shows + §`:invoke-all` viz + §Performance.
- Source-coord chips on every clickable element (the host wires
  the editor URL handler via `:on-state-click` /
  `:on-transition-click`).
- Accessibility: SVG `<title>` / `<desc>` + machine summary text
  alternative. **Full chart alt-view defers to v1.1** (same
  posture Causa 003 §Accessibility takes — the transition-history
  ribbon + machine picker on the host side carry the accessible
  surface in v1.0).

### v1.1 candidates (not committed)

- Chart alt-view for screen readers (the v1 commitment deferred
  from accessibility).
- "Paste a machine definition" web playground hosted under the
  viewer page (Stately-Visualizer-style onboarding flow).
- D2 / `xstate-json` emitters alongside Mermaid (the topology fn
  generalises; D2 + xstate-json are different output grammars over
  the same input). Per [Spec 005 §Future §Diagram export](../../../spec/005-StateMachines.md#diagram-export-from-transition-tables).

### Out of scope (any version)

- A canvas-driven machine editor.
- A multi-user / multiplayer canvas.
- A saved-machine registry (the share-URL is the only persistence
  affordance; long-term storage is the user's problem).
- Statechart-from-non-machine-code visualisation (Stately Studio
  2026 visualises Redux without XState; not Machines-Viz's lane).
- SCXML import / export.
- A Day8-hosted SaaS shell.

## The bar Machines-Viz sets

The v1 ship-list above is calibrated against three peer products:

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
- `:invoke-all` join visualisation with the join-condition label
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
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — the locks; questions, options, picks.
- [`API.md`](./API.md) — the consolidated public surface (`MachineChart` contract, viewer page URL, share-URL encoding).
- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — Causa's embedding-side spec; the source of truth for transition-history ribbon, source-coord integration, `:invoke-all` viz, share-affordance UX.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry Machines-Viz visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight consumes.
