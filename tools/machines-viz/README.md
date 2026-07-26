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
to a framework registry. Its host obtains the machine `:definition` and
live `:current-state` snapshot and passes them in, which keeps the chart
testable in isolation and decoupled from runtime state. Its main consumer
is **Xray's Machine Inspector** panel
([`tools/xray/spec/003-Machine-Inspector.md`](../xray/spec/003-Machine-Inspector.md)),
which imports it directly; Story's machine-chart surface is that same Xray
panel, reached via the `[Machines]` chip inside Story's right-hand-pane
Xray embed — Story has no machine panel of its own. A custom dev
shell can depend on it directly for a chart without the rest of Xray.

Surfaces that ship today:

- **`MachineChart` component** — an xyflow + elkjs in-page renderer.
  Nested compound states, parallel regions, multi-source edges,
  `:spawn-all` join + cancellation-cascade overlays, `:after` countdown
  rings, focused-event from/to lens, and fired-edge highlighting.
  Substrate-agnostic: a Reagent component plus a `$`-mountable UIx
  adapter shell.
- **Read-only viewer page** — `public/viewer.html` + `viewer.cljs` decode
  a `#machine=` URL fragment client-side and mount the chart with
  `:read-only? true`. Malformed / newer-version payloads render a banner,
  not a crash. You host it — see
  [§Building and hosting the viewer page](#building-and-hosting-the-viewer-page).
- **Share-URL encode / decode** — `ChartState → validate + canonicalise
  → versioned envelope → transit-write (json) → base64url → #machine=`.
  Runtime `:data`, source-coords, and definition metadata are dropped
  structurally — no session data crosses a share link.
- **Mermaid `stateDiagram-v2` emitter** — a pure `definition → string`
  function kept in this tool jar so the runtime machines artefact stays
  focused on execution.
- **SCXML import / export** — pure-data round-trip over the
  supported W3C SCXML subset.
- **AI-generate-a-machine** — a pluggable LLM-resolver seam; the
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
├── src/day8/re_frame2_machines_viz/          ; LIBRARY SURFACE — this ships
│   ├── chart.cljs                            ; the MachineChart component
│   ├── chart/                                ; layout, nodes, edges, overlays, projection
│   ├── adapters/                             ; react-chart + UIx shells
│   ├── share.cljs                            ; share-URL encode / decode
│   ├── export.cljs                           ; PNG / SVG / Mermaid / share-URL exporters
│   ├── mermaid.cljc                          ; stateDiagram-v2 emitter (pure)
│   ├── scxml.cljc                            ; SCXML import / export
│   ├── ai_generate.cljc                      ; AI-generate seam
│   └── theme/ · visual_constants.cljc        ; design tokens
└── page/day8/re_frame2_machines_viz/         ; THE APPLICATION — this does not
    └── viewer.cljs                           ; read-only viewer entry (^:export run)
```

**Why `page/` is a second source root (rf2-k7l2o).** `src` is library
surface a host requires; `page` holds the one application in this tree.
`viewer.cljs` is the `^:export run` entry `public/viewer.html` loads, and
it ships as the compiled `viewer.js` beside that HTML — the jar was never
its delivery vehicle. Only `src` is on `:paths` and on `:clein/build
:src-dirs`, so only `src` reaches a consumer.

The split earns its keep rather than merely tidying: the page is the only
namespace here that picks a **substrate** (`rf/init!` with the Reagent
adapter), and picking one is an application's job. Had it stayed in `src`,
the jar would have had to declare `day8/re-frame2-reagent` to be loadable
at all — and `day8/reagent-slim` publishes *its* adapter at the same
canonical `re-frame.adapter.reagent` namespace, so a slim app taking
machines-viz would have ended up with two implementations of that
namespace on one classpath, load order picking the substrate.

## Building and hosting the viewer page

**Nobody deploys this page for you.** Publishing
`day8/re-frame2-machines-viz` to Clojars ships the library jar and
nothing else; `release-machines-viz.yml` does not build or deploy the
page, and no other workflow does either. There is no hosted instance.

Until 2026-07-26 this repository claimed otherwise: `share/default-host`
and the docs named
`https://day8.github.io/re-frame2-machines-viz/viewer.html` as a
"canonical hosted instance". It returns **404** and always did — there is
no `day8/re-frame2-machines-viz` repository, because machines-viz ships
out of the re-frame2 monorepo. Every default share-URL was a dead link
that looked correct to whoever copied it. The default is gone and `:host`
is now required (rf2-8m344).

Hosting the page yourself is two files and no server:

```bash
cd implementation
npx shadow-cljs release machines-viz-viewer      # → out/machines-viz-viewer/viewer.js
cp ../tools/machines-viz/public/viewer.html out/machines-viz-viewer/
```

Serve that directory from anywhere that serves static files — your docs
site, an S3 bucket, a GitHub Pages branch of your own. Then point
share-URLs at it:

```clojure
(share/encode-share-url chart-state {:host "https://acme.example.com/viewer.html"})
```

The page decodes the `#machine=` fragment entirely client-side, so the
fragment never reaches your server and the host needs no application
logic. Per [DESIGN-RATIONALE Lock #7](./spec/DESIGN-RATIONALE.md).

Pass the page's URL **without a fragment of its own** — a query string is
fine. The machine payload *is* the fragment, and a URL has only one, so
`{:host ".../viewer.html#docs"}` would produce
`.../viewer.html#docs#machine=…`: a link that looks right and that the
viewer cannot read, because it stops at the first `#`. The encoder refuses
that rather than shipping it (`:reason :host-carries-fragment`, rf2-xld5m).
It does not otherwise parse the URL — a host that is not a URL comes back
to you as `"banana#machine=…"`, visibly wrong before you share it, so
`file:///…/viewer.html` and a relative `/viewer.html` both keep working.
See [spec/API.md §What `:host` is checked for](./spec/API.md).

## How to test

```bash
# from this directory:
clojure -M:test
```

The `:test` alias runs the JVM-loadable `.cljc` pure-data corpus through
the silent-on-success test-quiet runner. The consolidated `:node-test`
build in `implementation/shadow-cljs.edn` exercises the CLJS corpus;
browser-only canvas/SVG rasterisation and rendered topology gates use its
browser builds.

## Bundle isolation

Machines-Viz lives under `tools/` so the dependency direction remains
tool to implementation (per [`tools/README.md`](../README.md)): nothing in
`implementation/` may `:require` this jar. The chart is host-fed and uses
core only for shared error, trace, interop, and viewer-initialisation
surfaces; it does not query the machine registry or runtime-db itself.
`transit-cljs` is a runtime dependency of the share-URL encode/decode path
(`share.cljs`). It remains outside normal application bundles because
those bundles do not depend on the Machines-Viz tool artefact.

## Publishing

Machines-Viz publishes to Clojars as `day8/re-frame2-machines-viz` on a
tag push of the form **`machines-viz-v<VERSION>`** (e.g.
`machines-viz-v0.0.1.alpha`). The workflow lives at
[`.github/workflows/release-machines-viz.yml`](../../.github/workflows/release-machines-viz.yml)
and is triggered automatically — no manual deploy step.

The tag's version segment must equal the repo-root
[`VERSION`](../../VERSION) file (lockstep convention per
[`spec/Conventions.md`](../../spec/Conventions.md) §Packaging
conventions); a mismatched tag is refused before any deploy step runs.
In development the dep on `day8/re-frame2` is a `:local/root`; the
workflow rewrites it to a `:mvn/version` pinned to that same VERSION on
the throwaway runner checkout immediately before `clein deploy`, so the
published version equals the framework's at every release (same posture
as `tools/xray/deps.edn`).

To cut a release (Mike-only):

```bash
# 1. Ensure VERSION reads the target (e.g. 0.0.1.alpha)
# 2. Tag and push:
git tag machines-viz-v$(cat VERSION)
git push origin machines-viz-v$(cat VERSION)
```

The framework release (the matching `v<VERSION>` tag on
[`.github/workflows/release.yml`](../../.github/workflows/release.yml))
must precede it: the published pom depends on
`day8/re-frame2 {:mvn/version <VERSION>}` and that artefact must already
be discoverable on Clojars when `clein deploy` runs. The workflow checks
this structurally — it resolves the rewritten graph before deploying.

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
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace data hosts project into chart props and overlays.
- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation contract.
