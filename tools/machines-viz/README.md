# tools/machines-viz/

`day8/re-frame2-machines-viz` — **Machines-Viz**, the state-chart
component for re-frame2 machines. *Stately-quality charts; live; embedded.*

Status: **spec ratified; impl bootstrapping.** This directory
holds the normative contract; implementation lands incrementally
as beads under the spec. First impl-side artefact is the pure-data
Mermaid `stateDiagram-v2` emitter (per rf2-deo2i) at
[`src/day8/re_frame2_machines_viz/mermaid.cljc`](./src/day8/re_frame2_machines_viz/mermaid.cljc);
the React-flavoured `MachineChart` component and the read-only
viewer page land in later beads.

## What it is

A standalone CLJS jar that ships a single React-flavoured component —
`MachineChart` — and a static read-only viewer page. The chart renders
any re-frame2 machine registered via `reg-machine` (Spec 005), live-
highlights the active state on transitions, and surfaces the
microstep / `:after` / `:invoke-all` granularity that re-frame2's
trace bus emits.

`tools/machines-viz/` owns the chart. Causa (via
[`tools/causa/spec/003-Machine-Inspector.md`](../causa/spec/003-Machine-Inspector.md))
embeds it as the Machine Inspector panel's content; Story's machine
panel embeds it the same way. The chart itself depends only on
`implementation/core` + `implementation/machines` — never on Causa
or Story. The dependency arrow flows:

```
tools/causa/   ─requires→  tools/machines-viz/  ─requires→  implementation/machines/
tools/story/   ─requires→  tools/machines-viz/  ─requires→  implementation/core/
```

The inverse is forbidden, per [`tools/README.md`](../README.md) §the
bundle-isolation contract. A consumer who wants a chart without the
rest of Causa pulls `machines-viz` alone.

A separate **read-only viewer page** lives in this same jar and
renders charts decoded from a `#machine=<base64-edn>` URL fragment.
The viewer is statically hostable, ships under the same coord, and
is the receiving end of the Causa "Copy machine as → Share URL"
affordance.

## What it isn't

- **Not** a visualiser-as-product. Stately's commercial
  Visualizer / Studio occupy that lane (and are even an officially
  deprecated surface within Stately's own docs — see
  [DESIGN-RATIONALE Lock #1](./spec/DESIGN-RATIONALE.md)). Machines-Viz
  is a component the framework's own tools embed, plus a static
  read-only viewer for shared URLs. It does **not** ship a paste-and-
  render editor, a registry, a multiplayer canvas, or
  collaboration features.
- **Not** a registrar, dispatch, effect, or component layer.
  Machines-Viz is a downstream consumer of the framework's
  instrumentation surface (Spec 009 trace bus + `[:rf/machines <id>]`
  snapshots).
- **Not** a session export. The share-URL serialises **only** the
  machine topology + a single current-state snapshot — no trace
  stream, no epoch buffer, no app-db slices, no conversation. Per
  Causa Lock #4 (no session export), lifted into Machines-Viz as a
  load-bearing principle.
- **Not** reachable from production CLJS bundles. Per
  [`tools/README.md`](../README.md) the dependency arrow flows
  tool → implementation; this jar is on a separate classpath root.

## Where the depth lives

Per the per-tool spec-folder convention in
[`tools/README.md`](../README.md), the substantive contract for this
jar is decomposed into [`spec/`](./spec/):

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What `MachineChart` is, what the read-only viewer guarantees, share-URL encoding format; relationship to Stately Visualizer (out-of-scope). |
| [`spec/Principles.md`](./spec/Principles.md) | Bundle-isolation, EDN-first, observation-only, embedding-host-agnostic, no session data in shares. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | The locks: scope (component-not-product), share-URL encoding, current-state-only snapshot, source migrated from Causa 003. |
| [`spec/API.md`](./spec/API.md) | `MachineChart` component contract (`:machine-id`, `:frame-id`, `:on-state-click`, `:on-transition-click`); read-only viewer URL fragment; PNG / SVG / share-URL export functions. |

## Status

In design. Spec scaffolded via rf2-x50eu (2026-05-13). Decisions
extracted from `tools/causa/spec/003-Machine-Inspector.md`; cross-
references back to Causa for the embedding-side contract.
Implementation work begins after the spec ratifies.

## See also

- [`tools/causa/spec/003-Machine-Inspector.md`](../causa/spec/003-Machine-Inspector.md) — the embedding host's side of the contract.
- [`spec/005-StateMachines.md`](../../spec/005-StateMachines.md) — the registry the chart visualises.
- [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) — the trace events the live-highlight consumes.
- [`tools/README.md`](../README.md) — per-tool jar convention + bundle isolation.
