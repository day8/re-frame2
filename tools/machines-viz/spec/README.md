# Machines-Viz (`day8/re-frame2-machines-viz`) - spec

This folder defines the contract for the host-fed `MachineChart`, its
read-only viewer, and its export surfaces.

## Files

- [000-Vision.md](000-Vision.md) - scope, quality bar, viewer
  guarantees, and non-goals
- [001-Topology-Parity.md](001-Topology-Parity.md) - the current
  topology grammar and the parity bar against XState and Stately Studio
- [Principles.md](Principles.md) - bundle isolation, host independence,
  observation-only behaviour, share privacy, and motion constraints
- [API.md](API.md) - public component props, adapters, viewer URL,
  share envelope, Mermaid/SCXML/AI functions, and exporters
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) - major design decisions
  and their trade-offs

Read `000-Vision.md` and `Principles.md` first, then use `API.md` as the
public reference. `001-Topology-Parity.md` carries the detailed rendering
and cross-emitter invariants. Xray owns host-side concerns such as registry
queries, snapshot/trace projection, transition history, and source jumps;
see [Xray's Machine Inspector spec](../../xray/spec/003-Machine-Inspector.md).

## Implemented surfaces

- `MachineChart`: xyflow + elkjs rendering for flat, compound, and parallel
  machines, with event nodes, active/focused/fired treatments, context,
  overlays, and density/theme controls.
- React and UIx adapter shells over the same Reagent chart.
- read-only `#machine=` viewer and versioned Transit share URLs.
- PNG, SVG, Mermaid, and share-URL export and clipboard helpers.
- pure Mermaid `stateDiagram-v2` generation.
- SCXML import/export for the documented W3C subset.
- AI generation through a caller-supplied resolver. No provider bridge or
  credentials ship in this artefact.

## Test lanes

Three, not two:

- **JVM pure-data** - `clojure -M:test` from `tools/machines-viz`. The
  `.cljc` grammar / layout / projection / emitter corpus.
- **Artefact-owned CLJS (node)** - this artefact's own
  `tools/machines-viz/shadow-cljs.edn` `:machines-viz-node-test` build on
  its own `:cljs-test` classpath, run as `npm run test:tools-machines-viz`
  from `implementation/`. Its `-test$` selector reaches the suites the
  consolidated `cljs-test$` one does not (rf2-odlm3), and it carries the
  `page` root, so the viewer entry is covered here.
- **Consolidated CLJS + browser DOM** - `implementation/shadow-cljs.edn`
  borrows this artefact's `src`, `test` and `page` roots onto its
  `:node-test` (`cljs-test$`) and `:browser-test` (`-dom-cljs-test$`)
  builds. The real-DOM chart and export suites run there.

The viewer page is built and staged by `npm run build:machines-viz-viewer`
(the consolidated `:machines-viz-viewer` release build plus
`scripts/stage-viewer-page.cjs`).
