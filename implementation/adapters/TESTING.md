# Testing the adapters

> Reference for the adapter-smoke browser harness — the three substrate
> smokes (Reagent / UIx / Helix) and the runner that drives them.

Each adapter (Reagent / UIx / Helix) ships a single browser smoke that mounts a
testbed app, dispatches an event, and asserts the resulting render — the minimal
"this substrate bridge actually mounts and reacts" check. The smokes and their
runner live **with the adapters they test**: the specs at
[`<name>/testbed/spec.cjs`](reagent/testbed/spec.cjs) and the harness that
compiles, serves, and runs them at [`scripts/`](scripts/).

This is deliberately a thin surface. Real-regression coverage for substrate
behaviour lives in the framework gates — substrate contract tests under
`npm run test:cljs`, the Xray feature-matrix gate
(`npm run test:xray-feature-gate`), bundle-isolation
(`npm run test:bundle-isolation`), the perf-bundle gate
(`npm run test:perf-bundle`), and mcp-conformance. The adapter smokes exist only
to catch a mount/dispatch/render break per substrate that those CLJS/JVM gates
structurally cannot reach (a real browser, a real React commit).

CI *tiering* — which gate runs when — lives in
[`../../TESTING.md`](../../TESTING.md). The adapter-smoke gate is not part of the
always-on PR spine; it wakes up when adapter or harness surfaces change, and
again in the scheduled/manual expensive workflow.

## The surface

| Command                       | What it runs                                                                                                                                                  | Where the orchestrator lives                                                       |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `npm run test:adapter-smokes` | The three adapter-level smokes at `implementation/adapters/{reagent,uix,helix}/testbed/spec.cjs` — mount + dispatch + assert per substrate.                    | [`scripts/serve-and-run-adapter-smokes.cjs`](scripts/serve-and-run-adapter-smokes.cjs) |

`test:adapter-smokes` is **N bundles, N pages, N specs** — one per adapter
testbed. Each adapter owns its own runtime, so cross-adapter interaction is
simply not possible. Nothing shared, nothing to leak.

## How the harness is wired

The harness is three files under [`scripts/`](scripts/):

- [`serve-and-run-adapter-smokes.cjs`](scripts/serve-and-run-adapter-smokes.cjs)
  — the **orchestrator**. It compiles each smoke's shadow-cljs build, stages its
  hand-written `index.html` (plus the shared `examples/_shared/` design-system
  tree) into `implementation/out/examples/`, resolves a free port, serves the
  staged root, and spawns the Playwright runner. It always tears the server
  down. The build/stage/serve mechanics reuse the implementation tree's shared
  harness helpers (`../../scripts/lib/local-browser-harness.cjs`) and the
  example tree's shared staging + port helpers
  (`../../../examples/scripts/examples-staging.cjs`,
  `../../../examples/scripts/examples-port.cjs`) — the SAME staging/port code the
  example dev runner and the Story launchers use, so all the browser harnesses
  contend for ports and clean their staging dirs identically.
- [`run-adapter-smokes.cjs`](scripts/run-adapter-smokes.cjs) — the **Playwright
  runner**. It launches headless Chromium and executes each selected spec
  against the served URL, with the same console-tap + pageerror discipline and
  exit-code contract as the implementation tree's `run-browser-tests.cjs`.
- [`adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs) — the **shared
  manifest + selection logic** both the orchestrator and the runner import. It
  declares each smoke once (build id + `index.html` source + staging dir +
  `spec.cjs` path) and exposes one `selectEntries(patterns)`, so a narrow
  `--filter` / `ADAPTER_SMOKE_FILTER` value selects the *identical* set in both
  phases regardless of whether it is build-id-shaped
  (`adapters/reagent-testbed`, `reagent-testbed`) or path-shaped
  (`adapters/reagent/testbed`, `reagent/testbed`).

The runner reconciles the manifest against the `spec.cjs` files actually on disk
under the adapter tree and fails loudly on drift in either direction — a
renamed/removed smoke still listed in the manifest, or a new `spec.cjs` added
without a matching manifest entry.

The assertion helpers the specs use (`expectTextEquals`, `expectVisible`,
`waitForValue`, …) are the repo-wide shared
[`examples/scripts/spec-helpers.cjs`](../../examples/scripts/spec-helpers.cjs) —
substrate- and surface-agnostic matchers shared with the Story browser scenarios
and the Xray feature-matrix scenarios. The adapter specs require it across-tree;
it stays in the examples tree as the single shared home for all the repo's
hand-rolled Playwright specs.

## Adding a new adapter smoke

The smoke set is declared **once** in
[`scripts/adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs) — each
entry pairs a shadow-cljs build id with its `index.html` source, its
`out/examples/` staging dir, and the `spec.cjs` it runs. Both the orchestrator
(compile + stage) and the Playwright runner (spec selection) import that manifest
and call its shared `selectEntries`, so a narrow
`--filter`/`ADAPTER_SMOKE_FILTER` value selects the *identical* set in both
phases. The runner also reconciles the manifest against the `spec.cjs` files on
disk and fails loudly on drift.

To add a smoke:

1. Add the adapter testbed's `index.html` + `spec.cjs` under
   `implementation/adapters/<name>/testbed/`.
2. Add the build to [`../shadow-cljs.edn`](../shadow-cljs.edn) (the
   `:adapters/<name>-testbed` build id).
3. Append an entry to
   [`scripts/adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs)
   (build + htmlSrc + outDir + specPath).

Each spec exports `{ name, url, run }` — `run(page)` drives the Playwright
assertions. There is no opt-out: every selected spec runs.

## Notes for the example/Story trees

The adapter-smoke harness shares three helper modules with the example dev
runner and the Story launchers, all of which still live in
[`../../examples/scripts/`](../../examples/scripts/):

- `examples-staging.cjs` — staging (`stageShared`, `cleanStageDirs`) over the
  shared `implementation/out/examples` root.
- `examples-port.cjs` (→ `port-resolver.cjs`) — free-port resolution.
- `spec-helpers.cjs` — the Playwright assertion matchers.

These stay in the example tree because the example dev runner
(`serve-example.cjs`) and the Story launchers depend on them there; the adapter
orchestrator imports them across-tree (the same shape it already uses to import
the implementation tree's `scripts/lib/local-browser-harness.cjs`). A regression
in any of the shared helpers fires BOTH the adapter-smoke gate and the Story
browser gate — see
[`.github/scripts/report-changed-surfaces.sh`](../../.github/scripts/report-changed-surfaces.sh).
