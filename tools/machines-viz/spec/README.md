# Machines-Viz (`day8/re-frame2-machines-viz`) - Spec

This folder defines the contract for the host-fed `MachineChart`, its
read-only viewer, and its export surfaces.

## Files

- **[000-Vision.md](000-Vision.md)** - Scope, quality bar, viewer
  guarantees, and non-goals.
- **[001-Topology-Parity.md](001-Topology-Parity.md)** - The current
  topology grammar and the parity bar against XState and Stately Studio.
- **[Principles.md](Principles.md)** - Bundle isolation, host independence,
  observation-only behaviour, share privacy, and motion constraints.
- **[API.md](API.md)** - Public component props, adapters, viewer URL,
  share envelope, Mermaid/SCXML/AI functions, and exporters.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** - Major design decisions
  and their trade-offs.

Read `000-Vision.md` and `Principles.md` first, then use `API.md` as the
public reference. `001-Topology-Parity.md` carries the detailed rendering
and cross-emitter invariants. Xray owns host-side concerns such as registry
queries, snapshot/trace projection, transition history, and source jumps;
see [Xray's Machine Inspector spec](../../xray/spec/003-Machine-Inspector.md).

## Implemented Surfaces

- `MachineChart`: xyflow + elkjs rendering for flat, compound, and parallel
  machines, with event nodes, active/focused/fired treatments, context,
  overlays, and density/theme controls.
- React and UIx adapter shells over the same Reagent chart.
- Read-only `#machine=` viewer and versioned Transit share URLs.
- PNG, SVG, Mermaid, and share-URL export and clipboard helpers.
- Pure Mermaid `stateDiagram-v2` generation.
- SCXML import/export for the documented W3C subset.
- AI generation through a caller-supplied resolver; no provider bridge or
  credentials ship in this artefact.

The JVM-runnable pure-data suite runs with `clojure -M:test`. CLJS and DOM
coverage use the consolidated builds in `implementation/shadow-cljs.edn`.
