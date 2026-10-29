# Testing the adapters

> Reference for the adapter-smoke browser harness — the two substrate
> smokes (Reagent / UIx) and the runner that drives them.

Each adapter (Reagent / UIx) ships a single browser smoke that mounts a
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
| `npm run test:adapter-smokes` | The two adapter-level smokes at `implementation/adapters/{reagent,uix}/testbed/spec.cjs` — mount + dispatch + assert per substrate.                    | [`scripts/serve-and-run-adapter-smokes.cjs`](scripts/serve-and-run-adapter-smokes.cjs) |

`test:adapter-smokes` is **N bundles, N pages, N specs** — one per adapter
testbed. Each adapter owns its own runtime, so cross-adapter interaction is
simply not possible. Nothing shared, nothing to leak.

## How the harness is wired

The harness is three files under [`scripts/`](scripts/):

- [`serve-and-run-adapter-smokes.cjs`](scripts/serve-and-run-adapter-smokes.cjs)
  — the **orchestrator**. It compiles each smoke's shadow-cljs build, stages its
  hand-written `index.html` into `implementation/out/examples/`, resolves a free
  port, serves the staged root, and spawns the Playwright runner. It always
  tears the server down. The serve mechanics reuse the implementation tree's
  shared harness helpers (`../../scripts/lib/local-browser-harness.cjs`), and
  the port resolver and stage cleaner are the example tree's
  (`../../../examples/scripts/examples-port.cjs`,
  `../../../examples/scripts/examples-staging.cjs`) — the SAME code the
  example dev runner and the Story launchers use, so all the browser harnesses
  contend for ports and clean their staging dirs identically.
- [`run-adapter-smokes.cjs`](scripts/run-adapter-smokes.cjs) — the **Playwright
  runner**. It launches headless Chromium and executes each selected spec
  against the served URL. A spec fails on an uncaught page error, or on a
  console error its `expectedConsoleErrors` does not allow, even when its
  assertions passed; the listeners attach before the page loads, so a boot-time
  error counts.
- [`adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs) — the **smoke
  set + selection** both the orchestrator and the runner import. It derives
  each smoke (build id + `index.html` source + staging dir + `spec.cjs` path)
  from the adapter's name, and its one `selectEntries(patterns)` picks the
  smokes whose build id contains any comma-separated `--filter` /
  `ADAPTER_SMOKE_FILTER` pattern (`adapters/` selects both, `reagent` or
  `uix-testbed` one), so both phases select the *identical* set.

The runner reconciles the set against the `spec.cjs` files actually on disk
under the adapter tree and fails loudly on drift in either direction — a
renamed/removed smoke still in the set, or a new `spec.cjs` added without its
adapter's name.

The assertion helpers the specs use (`expectTextEquals`, `expectVisible`,
`waitForValue`, …) are the repo-wide shared
[`examples/scripts/spec-helpers.cjs`](../../examples/scripts/spec-helpers.cjs) —
substrate- and surface-agnostic matchers shared with the Story browser scenarios
and the Xray feature-matrix scenarios. The adapter specs require it across-tree;
it stays in the examples tree as the single shared home for all the repo's
hand-rolled Playwright specs.

## Adding a new adapter smoke

The smoke set is declared **once**, as the `ADAPTERS` names in
[`scripts/adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs); each
name gives a shadow-cljs build id, an `index.html` source, an `out/examples/`
staging dir and the `spec.cjs` it runs.

To add a smoke:

1. Add the adapter testbed's `index.html` + `spec.cjs` under
   `implementation/adapters/<name>/testbed/`.
2. Add the build to [`../shadow-cljs.edn`](../shadow-cljs.edn) (the
   `:adapters/<name>-testbed` build id).
3. Add the adapter's name to `ADAPTERS` in
   [`scripts/adapter-smoke-filter.cjs`](scripts/adapter-smoke-filter.cjs).

Each spec exports `{ name, url, run }` — `run(page)` drives the Playwright
assertions — and may add `expectedConsoleErrors`, an array of `RegExp` naming
console errors the page is allowed to log. There is no opt-out: every selected
spec runs.

## Notes for the example/Story trees

The adapter-smoke harness shares three helper modules with the example dev
runner and the Story launchers, all of which still live in
[`../../examples/scripts/`](../../examples/scripts/):

- `examples-staging.cjs` — the path-guarded `cleanStageDirs` over the shared
  `implementation/out/examples` root.
- `examples-port.cjs` (→ `port-resolver.cjs`) — free-port resolution.
- `spec-helpers.cjs` — the Playwright assertion matchers.

These stay in the example tree because the example dev runner
(`serve-example.cjs`) and the Story launchers depend on them there; the adapter
orchestrator imports them across-tree (the same shape it already uses to import
the implementation tree's `scripts/lib/local-browser-harness.cjs`). A regression
in any of the shared helpers fires BOTH the adapter-smoke gate and the Story
browser gate — see
[`.github/scripts/report-changed-surfaces.sh`](../../.github/scripts/report-changed-surfaces.sh).
