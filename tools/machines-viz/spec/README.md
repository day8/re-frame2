# Machines-Viz (`day8/re-frame2-machines-viz`) — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — What `MachineChart` is, what the read-only viewer guarantees, the share-URL encoding format; relationship to Stately Visualizer (visualizer-as-product is out of scope).
- **[Principles.md](Principles.md)** — Bundle isolation, EDN-first wire, observation-only, embedding-host-agnostic, no session data in shares, read-only by default.
- **[API.md](API.md)** — Consolidated public surface: `MachineChart` component contract, read-only viewer URL, share-URL encoding pipeline + payload schema, PNG / SVG exporters.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The locks. Question, options, pick, why, locker. Several locks lift content from [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md); cross-references retained.

## How to use

This folder is scaffolded for the v1.0 surface. Read
[`000-Vision.md`](000-Vision.md) first to anchor scope (one
embeddable component + a read-only viewer page; no
visualizer-as-product), then [`Principles.md`](Principles.md) for
the tie-breakers, then [`API.md`](API.md) for the consolidated
contract. [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) records the
locks; it cites [Causa 003](../../causa/spec/003-Machine-Inspector.md)
where decisions were originally specced on the embedding side.

Numbered capability docs (`001-Rendering.md`, etc.) land as
implementation work picks up; this scaffold is intentionally
**API + locks first, capability detail later**. Until the
capability docs land, [Causa 003](../../causa/spec/003-Machine-Inspector.md)
is the source of truth for unmigrated content (transition-history
ribbon UX, source-coord wiring, `:spawn-all` row layout details).

## Status

Scaffolded by [rf2-x50eu](../../../.beads/) — 2026-05-13. The spec
covers the v1.0 contract the implementation will satisfy; the first
shipped surface is the Mermaid `stateDiagram-v2` exporter.

### Shipped

- **Mermaid `stateDiagram-v2` exporter** — per
  [rf2-deo2i](../../../.beads/) and
  [`API.md`](API.md) §Mermaid `stateDiagram-v2`. Implemented at
  `src/day8/re_frame2_machines_viz/mermaid.cljc` (~500 LoC). Covers
  the read-only diagram surface enumerated in
  [`000-Vision.md`](000-Vision.md) §item 5 (single-state machines,
  composite states, parallel regions, transition labels, entry/exit
  actions, action/`do` labels, `invoke-all` row layout). JVM + CLJS
  test corpora cover the projection and the markdown-fence wrapping.

### Pending implementation

- `MachineChart` Reagent component — the live in-page renderer.
- Read-only viewer page — the standalone hosted surface that accepts
  a share URL and renders without an embedding host.
- Share-URL encoding pipeline — payload schema + compression +
  versioning per [`API.md`](API.md) §Share URL.
- PNG / SVG exporters — file-emitting surfaces alongside the
  Mermaid markdown export.
- Capability docs (`001-Rendering.md`, etc.) — author alongside
  each surface as it lands. The Mermaid exporter's contract
  currently lives in [`API.md`](API.md) only; a dedicated
  capability doc may land when sibling capabilities arrive and a
  shared surface emerges.
