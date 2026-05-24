# Machines-Viz (`day8/re-frame2-machines-viz`) — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — What `MachineChart` is, what the read-only viewer guarantees, the share-URL encoding format; relationship to Stately Visualizer (visualizer-as-product is out of scope).
- **[001-Topology-Parity.md](001-Topology-Parity.md)** — The machine-topology **parity plan** against xstate / Stately Studio: the per-concern parity bar (cited), `MachineChart`'s current state, the gaps to close, the Figma-ready visual design, and the prioritised roadmap. The numbered capability doc the §How-to-use note reserved (folds in the optional `001-Rendering.md` scope).
- **[Principles.md](Principles.md)** — Bundle isolation, EDN-first wire, observation-only, embedding-host-agnostic, no session data in shares, read-only by default.
- **[API.md](API.md)** — Consolidated public surface: `MachineChart` component contract, read-only viewer URL, share-URL encoding pipeline + payload schema, PNG / SVG exporters.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The locks. Question, options, pick, why, locker. Several locks lift content from [`tools/xray/spec/003-Machine-Inspector.md`](../../xray/spec/003-Machine-Inspector.md); cross-references retained.

## How to use

This folder is scaffolded for the v1.0 surface. Read
[`000-Vision.md`](000-Vision.md) first to anchor scope (one
embeddable component + a read-only viewer page; no
visualizer-as-product), then [`Principles.md`](Principles.md) for
the tie-breakers, then [`API.md`](API.md) for the consolidated
contract. [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) records the
locks; it cites [Xray 003](../../xray/spec/003-Machine-Inspector.md)
where decisions were originally specced on the embedding side.

Numbered capability docs land as implementation work picks up; this
scaffold is intentionally **API + locks first, capability detail
later**. The first numbered doc is
[`001-Topology-Parity.md`](001-Topology-Parity.md) (the parity bar +
gap analysis + roadmap against xstate / Stately Studio). Until further
capability docs land, [Xray 003](../../xray/spec/003-Machine-Inspector.md)
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
  `implementation/machines/src/re_frame/machines/mermaid.cljc`
  (namespace `re-frame.machines.mermaid`) — rf2-ee38b.21 corrected the
  prior `src/day8/re_frame2_machines_viz/mermaid.cljc` claim; the
  emitter lives under `implementation/machines/` per
  [`000-Vision.md`](000-Vision.md) §Static renderer, NOT under this
  tool. Covers the read-only diagram surface enumerated in
  [`000-Vision.md`](000-Vision.md) §item 5 (single-state machines,
  composite states, parallel regions, transition labels, entry/exit
  actions, action/`do` labels, `invoke-all` row layout). JVM + CLJS
  test corpora cover the projection and the markdown-fence wrapping.
- **SCXML import / export (v1.1)** — per
  [rf2-6urjd](../../../.beads/) and
  [`API.md`](API.md) §SCXML import/export. Implemented at
  `src/day8/re_frame2_machines_viz/scxml.cljc`. Pure-data round-trip
  `(= spec (-> spec spec->scxml scxml->spec))` for the supported
  W3C SCXML subset (flat / compound / parallel, `:initial`, `:on`,
  `:after`, `:always`, guards, `:final?`, namespaced ids). JVM +
  CLJS test corpus pins the round-trip property + error modes.
- **AI-generate-a-machine (v1.1)** — per
  [rf2-1bncf](../../../.beads/) and
  [`API.md`](API.md) §AI-generate-a-machine. Implemented at
  `src/day8/re_frame2_machines_viz/ai_generate.cljc`. Pluggable
  resolver seam (`:resolver`); the ns ships no default LLM bridge.
  JVM + CLJS test corpus uses a stub resolver to pin the parse /
  validate path; production callers wire their own LLM resolver in.

- **`MachineChart` interactive component** — the xyflow + elkjs
  in-page renderer (rf2-gpzb4 migration + Phase 2: parallel-region
  rendering, `:spawn-all` join + cancellation-cascade overlays,
  `:after` countdown rings, UIx/Helix substrate adapters). Implemented
  at `src/day8/re_frame2_machines_viz/chart.cljs` (+ `chart/`,
  `adapters/`). Browser visual-pin + JVM-side parse-layer tests cover
  it.
- **Share-URL encode / decode (rf2-8d7w1)** — per
  [`API.md`](API.md) §Share-URL encoding + [`Principles.md`](Principles.md)
  §EDN-first / §No session data in shares. Implemented at
  `src/day8/re_frame2_machines_viz/share.cljs`:
  `encode-share-url` / `decode-share-url` (+ `decode-share-url-safe` /
  `chart-state->props`). Pipeline is `ChartState → validate +
  canonicalise → versioned envelope → transit-write (json) → base64url
  → #machine= fragment`. Runtime `:data` + `:source-coords` +
  definition metadata are dropped structurally; the `:snapshot` schema
  is `{:closed true}` (`:state` only). CLJS test corpus pins the
  round-trip, reproducible-encoding, the privacy exclusions, the
  versioned envelope, and every `decode-failed` reason.
- **Read-only viewer page (rf2-8d7w1)** — per [`API.md`](API.md)
  §Read-only viewer + Lock #6 / Lock #7. The static
  `public/viewer.html` + the `src/day8/re_frame2_machines_viz/viewer.cljs`
  entry decode a `#machine=` fragment client-side and mount
  `MachineChart` with `:read-only? true`, a static-chart banner, and a
  'show idle' toggle. Malformed / newer-version payloads render a banner,
  not a crash. CLJS tests cover the pure decode → view-model layer.
- **PNG / SVG / Mermaid / share-URL exporters (rf2-8d7w1)** — per
  [`API.md`](API.md) §Exporters. Implemented at
  `src/day8/re_frame2_machines_viz/export.cljs`: `chart-as-png!`,
  `chart-as-svg`, `chart-as-mermaid`, `share-url`, and the four
  `copy-*-to-clipboard!` fns. They derive the payload from a JS seam
  the chart's root `:ref` stashes on the rendered element
  (`_rfMvChartState` — topology + active-state NAME + summary counts,
  never runtime `:data`). PNG/SVG are browser-DOM rasterisers; the
  Mermaid + share-URL paths delegate to
  `re-frame.machines.mermaid/emit` and `share/encode-share-url`. CLJS
  tests cover the seam-derivation + delegation paths (the canvas / SVG
  DOM rasterisation is browser-only).

### Pending implementation

- Capability docs (`001-Rendering.md`, etc.) — author alongside
  each surface as it lands. The Mermaid exporter's contract
  currently lives in [`API.md`](API.md) only; a dedicated
  capability doc may land when sibling capabilities arrive and a
  shared surface emerges.
