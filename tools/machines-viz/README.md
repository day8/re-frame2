# tools/machines-viz/

`day8/re-frame2-machines-viz` — **Machines-Viz**, the re-frame2
state-chart component and viewer.

Machines-Viz turns a re-frame2 state-machine definition (Spec 005) into
a Stately-quality chart you can read, click, and export. It ships one
embeddable component (`MachineChart`), a read-only share-URL viewer page,
and a set of pure-data exporters — held to an explicit quality bar:
*good interactive, integrated visualisation, robust to complexity,
beautiful, highly ergonomic* (comparator: Stately Studio). See
[`spec/000-Vision.md`](./spec/000-Vision.md).

## What it is

A presentation-only chart surface. The component does **not** subscribe
to a framework registry — the host pulls the machine `:definition` (via
`rf/machine-meta`) and the live `:current-state` snapshot and passes them
in, which keeps the chart testable in isolation and decoupled from the
runtime. Its main consumer is **Xray's Machine Inspector** panel
([`tools/xray/spec/003-Machine-Inspector.md`](../xray/spec/003-Machine-Inspector.md)),
which embeds it; Story's machine-chart surface is that same Xray
panel, reached via the `[Machines]` chip inside Story's right-hand-pane
Xray embed — Story has no machine panel of its own. A custom dev
shell can depend on it directly for a chart without the rest of Xray.

Surfaces that ship today:

- **`MachineChart` component** — an xyflow + elkjs in-page renderer.
  Nested compound states, parallel regions, multi-source edges,
  `:spawn-all` join + cancellation-cascade overlays, `:after` countdown
  rings, focused-event from/to lens, and fired-edge highlighting.
  Substrate-agnostic: a Reagent component plus `$`-mountable UIx and
  Helix adapter shells.
- **Read-only viewer page** — `public/viewer.html` + `viewer.cljs` decode
  a `#machine=` URL fragment client-side and mount the chart with
  `:read-only? true`. Malformed / newer-version payloads render a banner,
  not a crash.
- **Share-URL encode / decode** — `ChartState → validate + canonicalise
  → versioned envelope → transit-write (json) → base64url → #machine=`.
  Runtime `:data`, source-coords, and definition metadata are dropped
  structurally — no session data crosses a share link.
- **Mermaid `stateDiagram-v2` emitter** — a pure `definition → string`
  fn. Relocated out of the runtime `machines` artefact into this tool jar
  (rf2-sqhqu) so the engine stays pure-engine; apps that want Mermaid
  require this tool, apps that don't pay nothing.
- **SCXML import / export (v1.1)** — pure-data round-trip over the
  supported W3C SCXML subset.
- **AI-generate-a-machine (v1.1)** — a pluggable LLM-resolver seam; the
  ns ships no default bridge.
- **Exporters** — `chart-as-png!`, `chart-as-svg`, `chart-as-mermaid`,
  `share-url`, and the four `copy-*-to-clipboard!` fns.

The full public surface is enumerated in
[`spec/API.md`](./spec/API.md) §Public CLJS API surface.

## File layout

```
tools/machines-viz/
├── README.md                                 ; this file
├── deps.edn                                  ; declares day8/re-frame2-machines-viz
├── public/viewer.html                        ; static read-only viewer host
├── spec/                                     ; normative contract (see below)
└── src/day8/re_frame2_machines_viz/
    ├── chart.cljs                            ; the MachineChart component
    ├── chart/                                ; layout, nodes, edges, overlays, projection
    ├── adapters/                             ; react-chart + UIx + Helix shells
    ├── viewer.cljs                           ; read-only viewer entry
    ├── share.cljs                            ; share-URL encode / decode
    ├── export.cljs                           ; PNG / SVG / Mermaid / share-URL exporters
    ├── mermaid.cljc                          ; stateDiagram-v2 emitter (pure)
    ├── scxml.cljc                            ; SCXML import / export (v1.1)
    ├── ai_generate.cljc                      ; AI-generate seam (v1.1)
    └── theme/ · visual_constants.cljc        ; design tokens
```

## How to test

```bash
# from this directory:
clojure -M:test
```

The `:test` alias runs the CLJS corpus through the silent-on-success
test-quiet runner — projection, layout, edges, overlay geometry, the
Mermaid / SCXML round-trips, the share-URL privacy + round-trip
properties, and the viewer decode → view-model layer. The browser-only
canvas / SVG rasterisation and visual pins run under the testbed builds
wired in `implementation/shadow-cljs.edn`.

## Bundle isolation

Machines-Viz lives under `tools/` so the bundle-isolation contract holds
(per [`tools/README.md`](../README.md)): nothing in `implementation/` may
`:require` from this jar. It consumes only the framework's public
surfaces (`rf/machine-meta`, the machine-snapshots slot, the trace bus)
and adds no framework primitives. `transit-cljs` is a runtime dependency
of the share-URL encode/decode path (`share.cljs`); it is "dev-only" only
because the whole Machines-Viz jar is dev-only and bundle-isolated, so a
consumer's production bundle never pulls transit through Machines-Viz.

## Publishing

Publishes to Clojars as `day8/re-frame2-machines-viz` in lockstep with
the framework: `:local/root` during development; the release workflow
rewrites it to a `:mvn/version` pinned to the repo-root
[`VERSION`](../../VERSION) file, so the published version equals the
framework's at every release (same posture as `tools/xray/deps.edn`).

## Spec

The contract lives in [`spec/`](./spec/):

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What `MachineChart` is, the quality bar, the read-only viewer guarantees, scope / non-goals. |
| [`spec/001-Topology-Parity.md`](./spec/001-Topology-Parity.md) | The machine-topology parity plan against xstate / Stately Studio; gap analysis; roadmap. |
| [`spec/API.md`](./spec/API.md) | Consolidated public surface: `MachineChart` props, the viewer URL, share-URL encoding, the exporters. |
| [`spec/Principles.md`](./spec/Principles.md) | Bundle isolation, EDN-first wire, observation-only, no session data in shares, read-only by default. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | The locks: question, options, pick, why. Several lift content from Xray 003 (cross-referenced). |

## See also

- [`tools/xray/spec/003-Machine-Inspector.md`](../xray/spec/003-Machine-Inspector.md) — the embedding-host contract; the source spec these surfaces lifted from.
- [Spec 005 — StateMachines](../../spec/005-StateMachines.md) — the registry the chart visualises.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace bus the live highlight consumes.
- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation contract.
