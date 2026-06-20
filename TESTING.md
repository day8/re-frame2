# Testing policy

## The challenge

re-frame2 has many kinds of tests, and they have very different costs. Fast ones (CLJS unit, JVM unit, lockstep drift) take seconds. Expensive ones (full browser matrix, MCP live conformance, template emitted-app smoke) take many minutes — sometimes a small multiple of that when several stack.

Running everything everywhere makes PRs slow and the dev loop painful. Skipping the expensive ones at the wrong time lets regressions through to release. **The whole point of this document is the system we use to manage that tradeoff.**

## Kinds of tests

| Kind | Cost | What it proves |
|---|---|---|
| **CLJS unit** (`test:cljs`, node-test build) | fast | Per-namespace CLJS logic across `implementation/*` + `tools/*`. |
| **JVM unit** (per-artefact `clojure -M:test`) | fast | CLJC logic + pure helpers (machines, schemas, routing, flows, http, ssr, …); the JVM probes for the React adapters double as classpath/deps wiring checks. |
| **Lockstep + drift checks** (drift, skill/MCP) | fast | Per-version drift between coordinated artefacts + skill / MCP-server schema. |
| **JS harness self-tests** (`test:script-policy`, `test:script-helpers`) | fast | The Node-side CI plumbing that runs everything else. |
| **Browser unit** (`test:browser`, Playwright + headless Chromium) | medium | CLJS behaviour under a real DOM (Reagent / UIx / Helix render paths, event bindings, lifecycle). |
| **Bundle isolation** (`test:bundle-isolation`, `test:reagent-slim:bundle-isolation`, `test:schemas-bundle`) | medium | Production bundles do not leak `tools/*` symbols; adapter-specific isolation invariants (UIx/Helix Reagent-free; Reagent-Slim without stock-Reagent impl). |
| **Production-elision** (`test:elision`, `test:browser-prod-elision`, `test:browser-schemas-boundary-prod`) | medium | Non-negotiable production invariants: dev-time sentinels elided in release builds; schemas boundary warn-once contract. |
| **MCP conformance — wire** (`tools/mcp-conformance` suite) | medium | MCP servers honour the strict `CallToolResultSchema`; trusted-PATH + symlink-safe-unlink helpers. |
| **MCP conformance — live** (`test:re-frame2-pair-live-hermetic-suite`, `test:re-frame2-pair-live-subscribe`) | expensive | Real re-frame2-pair-mcp behaviour against a real shadow-cljs nREPL; over-budget eval cap; subscribe/unsubscribe lifecycle. |
| **Adapter smoke** (`test:adapter-smokes`) + **compile gate** (`test:examples-compile`) | medium | `test:adapter-smokes` = the three adapter-level testbed smokes (Reagent / UIx / Helix — mount + dispatch + assert). The `examples/` tree itself is test-free (one smoke per adapter; no per-example specs). `test:examples-compile` compiles every declared standalone `:examples/*` build. |
| **Story feature gates** (`test:story-feature-load`, `test:story-play-scripts`) | expensive | Story testbed exercises the feature/load matrix; play-scripts double every story's `:play-script` as a regression test. `test:story-play-scripts` is single-testbed + high-signal (it renders the live shell + assertion-strip) and stays on the PR critical path; `test:story-feature-load` is nightly-full only (see the Story/Xray PR-smoke vs nightly-full split below). |
| **Xray feature gates** (`test:xray-feature-gate`, `test:xray-feature-gate:smoke`) | expensive (full) / medium (smoke) | Xray feature matrix (4-layer chrome, tab navigation, exception/issue surfacing). The `--smoke` tier runs the 4 highest-signal scenarios over 3 staged surfaces on the PR critical path; the full 15-scenario / 10-surface sweep runs nightly. |
| **Template emitted-app smoke** (`jvm-tools-template`) | expensive | The emitted app from `tools/template/` boots + passes its own gates — proves the template stays viable. Also carries a second fixture that materialises the `skills/re-frame2-setup/*` MANUAL day-one scaffold from the skill's own snippets and compiles it against the in-repo source (semantic-drift net for the skill's hand-written boot ceremony, which diverges from the generator template — rf2-ae98go). Both are gated behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`. |
| **Skills structural** (`skills-structural`) | fast | Skill manifests + shared content stay structurally valid against the schema. |
| **docs/cljs playground** (`tools-playground`, Playwright + headless Chromium) | medium | The roll-your-own live-CLJS-cell engine behind the docs reading guide + interactive chapters: rebuilds both committed bundles, smokes plain-eval + live re-frame2 (v2) render cells against them, then gates freshness — strict `git diff --exit-code` on the byte-deterministic esbuild artefacts (`docs/cljs/playground.{js,css}`) plus a smoke + structural-validity gate on the Closure `:advanced` SCI bundle (`docs/cljs/playground-rf2.js`), whose minified symbols are not byte-stable cross-platform so a byte-diff would be flaky. |

## Test surface ownership

Each surface in this repo has one owner and one purpose. Don't overload
examples with internal canaries; don't grow per-example smoke specs
where adapter / framework gates already carry the signal (rf2-eceuv).

| Surface | Audience | Carries smoke? |
|---|---|---|
| `examples/` | **Humans** — learning + demonstration. | **No.** Per-example smoke specs are intentionally absent. |
| `implementation/adapters/<name>/testbed/` | Per-adapter smoke. One tiny standalone counter per adapter (Reagent / UIx / Helix). Proves the adapter wires up end-to-end (mount, subscribe, dispatch, re-render). | Yes — one `spec.cjs` per adapter. |
| `testbeds/` (top-level) | Framework feature-matrix substrates. Cross-cutting fixtures consumed by multiple tools (Xray, Story, re-frame2-pair-mcp). | Yes — per-testbed `spec.cjs` driving the matrix. |
| `tools/xray/testbeds/` | Xray-rich demos and the deterministic `feature_matrix` substrate driven by `test:xray-feature-gate`. The `panel_gallery` substrate is a local-only visual gallery — its rf2-kgn0c workspace-switch regression class is covered by CLJS unit tests in `tools/story/test/re_frame/story_ui_cljs_test.cljs` (variant-id-keyed React identity) plus the `workspace-switch-no-stale-subscribe-derefs-rf2-kgn0c` Playwright scenario in `tools/story/test/story_browser_scenarios.cjs` (driven per-PR by `test:story-feature-load`). | Yes — `feature_matrix` carries the per-PR Xray scenarios; other substrates here are local dev surfaces. |
| `tools/story/testbeds/` | Story testbeds (counter-with-stories, login-form). | Yes — Story-owned scenarios. |
| Framework tests (`clojure -M:test` per artefact) | The spec is the artefact; these protect contracts. | N/A (unit, not smoke). |
| Xray feature-matrix gate (`test:xray-feature-gate`) | 15 scenarios across the matrix. | N/A (feature-gate, not smoke). |

Real bugs land in framework contracts + adapter wire-up + Xray lens
behaviour, not in "this example mounted + clicked." Coverage value
lives in the framework + feature-matrix gates + per-adapter smoke.
Don't grow example-level specs.

## Test authoring policy — new tests carry explicit frames

**New tests register and carry an explicit frame.** A new test sets up
its own frame with `reg-frame` and targets it explicitly — `{:frame …}`
on dispatch / subscribe, or a `with-frame` scope — per Spec 002
[§Frame target resolution](spec/002-Frames.md#frame-target-resolution--the-carried-invariant)
(EP-0002): frame identity is *carried, not found*, so a test must never
rely on the runtime synthesising a frame from absence. The legacy
test-only `frame/ensure-default-frame!` fixture, which establishes an
ambient `:rf/default` scope, is **grandfathered for the existing suites
that already use it** — it is deliberately retained (and bannered in
`frame.cljc`) so the ~70 pre-EP-0002 test files keep passing without a
poor-ROI mechanical rewrite. It is **not** an idiom to copy: do not
reach for it in new tests, because the spec-is-the-artefact repo teaches
idioms from its dominant source pattern, and a new test modelling the
retired ambient-default shape lets the obsolete idiom self-perpetuate.
**Conformance fixtures must never dispatch framelessly expecting default
stamping** (per the EP-0002 Audit §SS-11) — a conformance run that
leans on ambient `:rf/default` is asserting a behaviour the runtime no
longer guarantees. (A lint that flags `ensure-default-frame!` usage
outside the grandfathered files is a reasonable future guard, but is not
required today.)

## How we manage the cost

The cost-management shape is simple:

- **Cheap tests** run on every PR + every checkin. No conditional gating.
- **Expensive tests** run only when they're needed — gated by a **changed-surface classifier** on PR CI, by **full sweeps** on nightly/manual workflows, and by **release gates** before tagging.
- **Per-tier scenarios** package these into named workflows so contributors know which gate they're in.

The classifier maps "what files changed" → "which expensive jobs fire on this PR." The full matrix runs nightly + on release candidates regardless of the diff. Skipping the wrong gate at the wrong time is the failure mode the system exists to prevent — the **Placement decision dimensions** section below is the frame for deciding where a new test belongs.

## The 4 tier scenarios (outputs of the management approach above)

1. **Agent pre-checkin** — `scripts/test-fast-pr.sh` plus the surface-specific command for files touched. Run the always-on PR spine locally, then add the narrow changed surface: JVM artefact, browser, bundle, tool, template, or skill structural tests.
2. **PR CI** — `.github/workflows/test.yml`. Always runs lockstep drift, skill/MCP drift, core JVM, CLJS node integration, JS harness self-tests, and docs link validation for docs PRs. Expensive jobs run only when conservative path filters say the owning surface changed. The `story-xray-browser` job runs the **PR-smoke tier** (a fast high-signal subset — see the Story/Xray split below), not the full sweep.
3. **Nightly / manual** — `.github/workflows/expensive-tests.yml`. The rigorous browser/examples/bundle matrix, the **full** Story/Xray sweep, template emitted-app smoke, and live MCP conformance — kept off the PR critical path.
4. **Release** — `.github/workflows/release.yml` plus the latest green expensive workflow. The core pre-release gate; release is cut only after the scheduled/manual expensive suite is green on the release candidate.

### Story/Xray gate — PR-smoke vs nightly-full split

The `story-xray-browser` Playwright gate used to run the full sweep
(full Xray feature matrix + Story feature-load + Story static-export +
Story play-scripts) on the critical path of every Story/Xray PR. That
made it the single slowest CI gate at ~700s (≈6× the next slowest job),
and the **identical** sweep already runs nightly in
`expensive-tests.yml` — so PR time ran the full matrix twice over. The
dominant cost is shadow-cljs testbed compilation (each bundle is 400+
files, ~24s; the full Xray gate stages 10 surfaces).

The gate is now split into two tiers:

- **PR tier (`story-xray-browser` in `test.yml`)** — a fast smoke on the
  critical path. It runs only:
  - `npm run test:xray-feature-gate:smoke` — the 4 highest-signal Xray
    scenarios (6-tab shell handoff, deterministic-exception →
    Issues/Trace surfacing, Cmd-K palette, panel-gallery theme-token
    CSS-variable resolution) over just 3 staged surfaces
    (`counter` + `deliberate-throw` + `panel-gallery`), compiling 3
    testbed bundles instead of 10. Scenarios opt into the smoke via `smoke: true` in
    `tools/xray/testbeds/feature_matrix/scenarios.cjs`; the gate fails
    loud if the smoke set is ever empty.
  - `npm run test:story-play-scripts` — single-testbed
    (`counter-with-stories`), drives the live Story shell, and is the
    render path that exercises the assertion-strip. Kept on the PR path
    so the assertion-strip stays covered per-PR.

  Target PR-tier wall-clock: **<180s** (down from ~700s), helped by the
  keyed `.shadow-cljs/` + `.cpcache` compile cache (next bullet).

- **Nightly tier (`expensive-tests.yml`)** — the full sweep:
  `test:xray-feature-gate` (all 15 scenarios / 10 surfaces),
  `test:story-feature-load`, `test:story-play-scripts`, and
  `test:story-static`. Off the PR critical path; the nightly-full set is
  a strict superset of the PR-smoke set, so nothing the smoke covers is
  lost.

Both tiers restore a keyed shadow-cljs compile cache
(`implementation/.shadow-cljs` + `implementation/.cpcache`), keyed on
the build config + the Story/Xray src/testbed source hashes + shared
implementation source. The nightly run repopulates the cache so the
first PR-smoke of the day lands a warm partial cache (shared
`<os>-story-xray-shadow-` `restore-keys` prefix). Any change under the
keyed source trees busts the cache, so a stale cache can never serve
wrong compile output.

When adding a new Xray scenario, decide its tier: tag it `smoke: true`
only if it is high-signal enough to earn a slot on every PR's critical
path **and** it loads one of the already-staged smoke surfaces (or
accept the extra compile if it must stage a new one). Everything else
runs nightly by default.

### Story-as-test: headless plan gate, `:cannot-run` policy, fast-vs-browser split (NewTestStory EPIC rf2-5x1wt)

The NewTestStory testing substrate is **complete and promoted** — the
normative contract is
[`tools/story/spec/017-Testing-Story.md`](tools/story/spec/017-Testing-Story.md)
(§CI policy is the authority for the gate rules below). The substrate
ships the variant-plan compiler, the three execution verbs
(`story/run` / `story/is` / `story/explain`), inline plans, the shared
run-result + `:cannot-run` third status, the schema floor, the
epoch-tape evidence projection, the determinism gate, the semantic diff,
and run-artifact replay/promotion. This section records how that maps
onto CI policy; the concrete workflow files are not restructured by the
substrate work.

**Headless plan gate (the default per-PR Story-as-test path).** Story
variants and inline plans run through the `:headless` runner — fast,
JVM/node, no browser — and that is the default for ordinary
state/effect/schema/trace assertions. Consistent with the project's
CLJS-unit-tests-not-Playwright default, new Story/test coverage targets
the headless path first; reach for a browser-tier assertion only when it
genuinely needs browser behaviour. The pure substrate (compiler,
requirement registry, determinism verdict logic, evidence projection)
runs under `clojure -M:test` and `npm run test:cljs` with no runtime.

**Fast vs browser-tier separation (already in place).** The gate split
documented above (PR-smoke vs nightly-full) IS the fast-vs-browser-tier
separation for Story-as-test, and it predates this work:

- the **fast** path is the per-PR CLJS/JVM unit suite (`npm run
  test:cljs`, the per-artefact `clojure -M:test` jobs, `jvm-tools-story`)
  plus the single-testbed browser-rendered `test:story-play-scripts`
  PR-smoke in the `story-xray-browser` job (§Story/Xray gate above);
- the **browser tier** is the live-shell sweep on the nightly path
  (`test:story-feature-load`, `test:story-static`, the full
  `test:story-play-scripts` and `test:browser*`) in
  `expensive-tests.yml`.

A *dedicated* browser-tier gate that selects variants by their
`:required-runner` capability set (`:pixels` / `:a11y-engine`) and runs
ONLY those under a real-browser runner is the spec's deferred follow-on
(017 §Browser-tier gate policy explicitly scopes the workflow wiring out
of the spec change). The structural-a11y check
(`:rf.assert/a11y-structural`) carries no browser dependency and runs on
the normal `npm run test:cljs` / `clojure -M:test` path.

**`:cannot-run` policy (MUST, per gate).** Every CI gate that runs plans
MUST define a `:cannot-run` policy. `:cannot-run` is a distinct third
result state (not pass, fail, or skip): a runner returns it when an
assertion or step requires evidence the selected runner cannot produce
(e.g. a `:browser`-only visual snapshot under a `:headless` runner). Per
gate, the policy is one of: **fail the gate** (the assertion was
required), **report inconclusive** (record but do not fail), or **route
to a richer runner** (re-run the affected variant/assertion under a
browser-tier gate). The headless plan gate's default policy is to treat a
browser-tier `:cannot-run` as **inconclusive** and surface the count,
deferring browser-only assertions to the browser-tier gate that can prove
them — never silently passing them. A variant whose only unmet
expectations are `:cannot-run` is itself `:cannot-run`, never a silent
pass (017 §`:cannot-run` aggregation rule).

**Failed-run artifacts.** A failed plan run can emit a
`:rf.test/run-artifact` — the serializable, data-shaped record of one run
(seed, scripted event stream, fx decisions, epoch tape, trace, result) —
enough to replay it deterministically and re-derive the same evidence
(017 §Artifacts — Run artifact, §Run artifact and replay). A gate MAY
capture and upload that artifact for a failed run so the failure is
replayable off-CI; the artifact also feeds the determinism gate and the
semantic diff, and MAY be promoted into a curated regression variant via
`story/promote-run-artifact!`. Wiring an `upload-artifact` step into a
specific workflow gate is a CI-mechanics decision, not part of the
substrate; the substrate guarantees the artifact exists and is
canonicalizable.

## Local commands

The repo-root coordinator scripts run the canonical bundles. The per-
artefact npm scripts (under `implementation/package.json` and the per-tool
`tools/*/package.json`) are listed below for targeted reruns and for the
agent pre-checkin "narrow to the changed surface" workflow.

### Repo-root coordinators

| Command | Scope |
|---|---|
| `scripts/test-fast-pr.sh` | Fast PR spine: lockstep, skill/MCP drift, core JVM, JS harness self-tests, CLJS node integration. |
| `scripts/test-jvm-implementation.sh` | All implementation JVM artefacts, including adapter diagnostic classpath probes. |
| `scripts/test-jvm-tools.sh` | Tool JVM artefacts. |
| `scripts/test-rigorous-local.sh` | Fast spine + JVM coordinators + rigorous browser/bundle/examples/Story/Xray gates (local mirror of the `expensive-tests.yml` browser-bundle-and-story sweep, plus `test:examples-compile`; kept in lockstep by `_rigorous-local-inventory.test.cjs` in `test:script-policy`). Expensive; use before release-sized changes. |
| `scripts/test-core-jvm-windows.ps1` | **Windows-local** bounded wrapper for the full core JVM suite (`cd implementation/core && clojure -M:test`) under a timeout. On timeout it dumps the java/node/clojure lock-holders + tree-kills only its own child subtree, exits 124 with a handoff. See **Windows-local test policy** below. |
| `scripts/test-jvm-nses-windows.ps1` | **Windows-local** per-namespace sharding fallback: runs each core test namespace in its own bounded `clojure -M:test -n <ns>`, so a hang is attributed to a specific namespace instead of hanging the whole suite. |
| `scripts/reap-stale-test-processes.ps1` | **Windows-local** guarded reaper for stale repo/worktree test/dev processes (orphaned shadow watch / http-server testbeds / stale worktree JVMs). **DRY-RUN by default** (`-Execute` to kill; `-SelfTest` to verify classification). Never kills live MCP servers, Codex, or a running worker's JVM. |

### Windows-local test policy (rf2-c3hffe)

**CI (Linux) is authoritative for the full JVM suite.** The cross-spec core
JVM gate — `cd implementation/core && clojure -M:test` — runs green on
every PR on CI's Linux runners, and it is **not** split, trimmed, or
weakened: its examples-laden breadth is load-bearing (the in-core
`examples_test.clj` requires the example *source* namespaces, so the
`../../examples/reagent` test path must stay). That gate is the system of
record; a Windows-local hang is **not** a framework-correctness signal.

**Why Windows is different.** On Mike's Windows host the unsharded cross-spec
run has been observed to deadlock past 10 minutes on a *file-lock* — not a
test failure. The root cause is a stale/orphaned repo-or-worktree
shadow-cljs/Node/JVM process from a prior test or worker run still holding a
lock on `implementation/` / `tools/` / `out/`; when the cross-artefact
classpath suite compiles, it contends with that holder. (Mac/Linux do not
exhibit this — the OS reparents orphans and the orchestrators' POSIX
process-group teardown reaps them. The cross-platform orchestrator teardown
is in `implementation/scripts/lib/local-browser-harness.cjs`.)

**The leak is fixed at source.** Every `serve-and-run-*.cjs` test
orchestrator tears its spawned server down on exit/SIGINT/SIGTERM via the
shared `createHarnessCleanup` helper (Windows `taskkill /T /F`; Mac/Linux
POSIX process-group kill), pinned by
`implementation/scripts/_orchestrator-teardown-policy.test.cjs` (in
`test:script-policy`) and exercised by
`_orchestrator-teardown-integration.test.cjs` (in `test:script-helpers`).
That stops new orphans being generated.

**Windows workers, when the full local gate hangs.** Use the bounded
wrapper and targeted runs rather than waiting out a 10-minute deadlock:

1. `pwsh -File scripts/test-core-jvm-windows.ps1` — runs the full suite
   under a timeout (default 600s). On timeout it dumps the java/node/clojure
   command lines + (if Sysinternals `handle.exe` is on PATH) the open
   handles into the worktree — **the instrument that proves the
   lock-holder** — tree-kills *only* its own child subtree, and exits 124
   with a handoff. A real test failure passes through as the suite's own
   non-zero exit (distinct from 124).
2. `pwsh -File scripts/reap-stale-test-processes.ps1` — DRY-RUN first to see
   the stale orphans, then `-Execute` to reap them. It spares live MCP
   servers, Codex, and any process whose parent is still alive (a running
   worker's JVM). Pairs with the junction-safe worktree-cleanup pattern
   (`cmd /c rmdir` a junctioned `node_modules` before `git worktree
   remove`).
3. `pwsh -File scripts/test-jvm-nses-windows.ps1` — the sharding fallback:
   runs each namespace under its own bounded `clojure -M:test -n <ns>` so a
   hang is localised to one namespace ("namespace X held the lock") instead
   of hanging the whole suite. `-Pattern <substr>` filters; `-ListOnly`
   prints the discovered namespaces.
4. Then push and let **CI (Linux)** run the authoritative full JVM suite.

**Interim, still in use:** brief core-touching Windows workers to verify
bounded namespace subsets locally (the wrapper / sharding runner above, plus
`npm run test:cljs` and api-manifest JVM-load) and rely on CI Linux for the
full JVM suite. The bounded wrapper makes that interim faster and gives the
holder dump that closes the bead's "prove the lock-holder" gap.

### `implementation/package.json` (run from `implementation/`)

| Command | Scope |
|---|---|
| `npm run test:cljs` | CLJS node-runtime tests via shadow-cljs `node-test` build. The consolidated default gate for CLJS unit coverage. Loads every `*_cljs_test.cljs` file (the `cljs-test$` ns-regexp matches both `-cljs-test` and the DOM-tagged `-dom-cljs-test` suffix). |
| `npm run test:browser` | Browser CLJS tests (`browser-test` build) served with Playwright + http-server. Headless Chromium harness. **Narrowed to DOM-dependent tests only (rf2-2hrj8)**: the `:browser-test` ns-regexp is `-dom-cljs-test$`, matching files named `*_dom_cljs_test.cljs` — these tests mount real React via `react-dom/client`, exercise the React-context tier, or otherwise depend on browser-runtime features Node can't fake. Pure-fn + sub-chain reactivity tests run under `:node-test` only. |
| `npm run test:browser-prod-elision` | Release-built browser tests proving production elision under the `browser-test-prod-elision` shadow build. |
| `npm run test:browser-schemas-boundary-prod` | Release-built browser test proving the schemas boundary-warn-once contract in production. |
| `npm run test:elision` | Production-release elision probe: compiles `elision-probe` + `elision-probe-control` and asserts elision is total. Non-negotiable production invariant. |
| `npm run test:perf-bundle` | Compiles the counter + counter-perf examples in release mode and checks the perf-budget bundle delta. |
| `npm run test:schemas-bundle` | Compiles `schemas-bundle-probe` (Spec) + `schemas-bundle-probe-malli` and checks schemas-bundle isolation. |
| `npm run test:bundle-isolation` | Compiles release counter/counter-uix/counter-helix bundles and runs `check-bundle-isolation` + `check-uix-helix-reagent-free`. Tools must not leak into production bundles; UIx/Helix bundles must be Reagent-free. |
| `npm run test:reagent-slim:bundle-isolation` | Reagent Slim invariant: slim advanced bundles exclude stock Reagent impl sentinels and `react-dom/server`, with a stock-Reagent positive control. |
| `npm run test:adapter-smokes` | The three adapter-level testbed smokes (`implementation/adapters/{reagent,uix,helix}/testbed/spec.cjs`) — mount + dispatch + assert per substrate. The `examples/` tree is test-free; this orchestrator drives the adapter smokes only. Scope with `ADAPTER_SMOKE_FILTER` (build-id form `reagent-testbed` or path form `reagent/testbed`; comma-separated OR-match). |
| `npm run test:examples-compile` | Compile-coverage gate (rf2-0vav5.1 + rf2-cn6kc.1): `shadow-cljs compile` over EVERY declared standalone `:examples/*` build (list derived from `shadow-cljs.edn`, so new example builds are swept automatically). Fails on any compile error AND on any warning (`compile` exits 0 on warnings; a typo'd `:init-fn` surfaces as an `:undeclared-var`). Closes the gap where standalone examples (`login-uix`, `dashboard-uix`, `login-helix`, `process-monitor-helix`, …) were declared but compiled by no gate. Runs in the `cljs-browser` CI job; teeth pinned by `check-examples-compile.test.cjs` in `test:script-policy`. |
| `npm run test:story-feature-load` | Story full-browser feature-load and resilience gate (`tools/story/test/story_feature_load.cjs`). **Nightly-full tier** — runs in `expensive-tests.yml`, not on the PR critical path (per the Story/Xray split above). |
| `npm run test:story-play-scripts` | Story `:play-script` CI-as-test gate (rf2-3qcxk). Discovers every registered variant whose body carries a non-empty `:play-script` slot, navigates the live shell to each, waits for the auto-run's terminal status, and reports per-variant pass/fail. Variants whose id contains `failing` or `expected-fail` invert the assertion (expected `:fail`); everything else asserts `:pass`. **PR-smoke tier** — single-testbed, renders the assertion-strip, runs in the `story-xray-browser` PR job (and nightly). |
| `npm run test:xray-feature-gate` | Xray browser feature/load gate from `tools/xray/spec/017-Test-Coverage-Matrix.md` — the full 15-scenario / 10-surface sweep. **Nightly-full tier** (`expensive-tests.yml`). |
| `npm run test:xray-feature-gate:smoke` | PR-smoke tier of the Xray gate (`--smoke`). Runs only the scenarios tagged `smoke: true` over just the surfaces those scenarios load (4 scenarios / 3 surfaces today). On the `story-xray-browser` PR critical path. |
| `npm run test:story-static` | Static-build contract and deployable-output sanity for the Story export. |
| `npm run story:build` | Build the Story static artefact. |
| `npm run test:script-policy` / `npm run test:script-helpers` | Self-tests for the JS harness helpers (path policy, changed-surface classifier port, browser-test report, gate report, local browser harness). |
| `npm run test:mcp-conformance` | Single operator-side entry-point (rf2-gt4pf) chaining the six PR-time MCP gates that CI runs as separate jobs: JVM story-mcp `clojure -M:test`, Node story-mcp stdio-roundtrip, Node re-frame2-pair-mcp `:server-test`, MCP-client conformance for both servers (via `tools/mcp-conformance/scripts/test-all.cjs`), and JVM wire-vocab `clojure -M:test`. Compiles the re-frame2-pair-mcp server bundle as a prerequisite. Requires the implementation/ devDependencies installed (`npm install` in implementation/ — the one-time bootstrap); a stale tree missing `cross-spawn` fails loud with an install hint rather than a cryptic loader stack (rf2-ocfiq). CI keeps the six gates split for differential surface attribution. |

### `tools/mcp-conformance/package.json` (run from `tools/mcp-conformance/`)

| Command | Scope |
|---|---|
| `npm test` | Runs the full MCP-client conformance suite via `scripts/test-all.cjs` — re-frame2-pair degraded, story end-to-end, xray placeholder, and exec-safety unit tests, with live-overflow flagged SKIP/RUN by env. |
| `npm run test:re-frame2-pair` | Degraded-mode re-frame2-pair-mcp conformance against the SDK's strict `CallToolResultSchema`. |
| `npm run test:re-frame2-pair-live-overflow` | Live-runtime overflow conformance — SKIPs cleanly without `$SHADOW_CLJS_NREPL_PORT`. |
| `npm run test:re-frame2-pair-live-hermetic-suite` | Hermetic live suite — boots shadow-cljs against the `skills/re-frame2-pair/tests/fixture/` counter and runs the WHOLE live inner-test inventory (overflow plus subscribe/redaction/isError/cofx/event-metadata). The overflow member runs a real over-budget eval; catches cap-trigger threshold drift, marker shape regressions, and SDK strict-schema rejection. |
| `npm run test:re-frame2-pair-live-subscribe` | Live-runtime subscribe/unsubscribe conformance. Gated on `$SHADOW_CLJS_NREPL_PORT`. |
| `npm run test:story` | End-to-end story-mcp MCP-client conformance. |
| `npm run test:exec-safety` | Unit tests for the trusted-PATH-resolution and symlink-safe-unlink helpers shared with the hermetic orchestrator. |

### `tools/re-frame2-pair-mcp/package.json` (run from `tools/re-frame2-pair-mcp/`)

| Command | Scope |
|---|---|
| `npm run build` | Build the re-frame2-pair-mcp server (`shadow-cljs compile server`). |
| `npm test` | Compile + run the re-frame2-pair-mcp `server-test` build under node. |
| `npm run stdio-roundtrip` | Stdio JSON-RPC round-trip smoke against the built server. |

### Per-artefact JVM tests

Each artefact under `implementation/<name>/` and `tools/<name>/` carries
its own `:test` alias. Run from the artefact directory:

```
cd implementation/<artefact> && clojure -M:test
cd tools/<artefact>          && clojure -M:test
```

The repo-root coordinators (`scripts/test-jvm-implementation.sh`,
`scripts/test-jvm-tools.sh`) iterate these. Adapter probes
(`reagent`, `reagent-slim`, `uix`, `helix`) and the `tools/xray`
JVM probe are diagnostic skip-ok (see below).

Green output should stay quiet. Failures must name the violated contract, owning
surface, and reproduction command. See [`docs/quiet-tests.md`](docs/quiet-tests.md)
for the output contract that makes this real.

## Changed-surface classifier

PR CI tiers expensive jobs through a conservative changed-surface
classifier. The classifier is the **source of truth for "which jobs run
when"** on a pull request.

- **Script**: [`.github/scripts/report-changed-surfaces.sh`](.github/scripts/report-changed-surfaces.sh)
- **Workflow consumer**: the `detect_changed_surfaces` job in
  [`.github/workflows/test.yml`](.github/workflows/test.yml) (every
  downstream job gates on one of its outputs via
  `if: needs.detect_changed_surfaces.outputs.<surface> == 'true'`).

The script reads the changed-files list (PR diff against
`origin/${GITHUB_BASE_REF}`, or `HEAD^..HEAD` locally) and emits
boolean GitHub-Actions outputs per surface:

| Output | Triggers when … |
|---|---|
| `implementation_jvm` | JVM artefact under `implementation/` changed; gates JVM unit + conformance jobs. |
| `adapter_diagnostic` | Adapter artefact changed; gates the diagnostic skip-ok JVM classpath probes. |
| `cljs_node_test` | A surface that compiles into the consolidated `:node-test` build changed (core, any adapter, any feature artefact, `spec/conformance/fixtures/*`, the build config in `implementation/{shadow-cljs.edn,package.json,package-lock.json}` + `implementation/scripts/*`, a `.cljs`/`.cljc` file under `tools/{story,xray}/{src,test}` — rf2-f79t8 — or any non-spec-md change under `tools/machines-viz/*` — rf2-z0cw6s, whose `{src,test}` are also `:node-test` source paths); gates the `cljs` job (`shadow-cljs compile node-test` + `node out/node-test.js`). |
| `cljs_browser` | CLJS surface that the browser tests cover changed (incl. any non-spec-md `tools/machines-viz/*` change — rf2-z0cw6s, whose `*-dom-cljs-test` export/chart-DOM suites run under `:browser-test` where EP-0015 image-export egress is verified); gates the separate `cljs-browser` job (`:browser-test` DOM build via Playwright). |
| `cljs_prod` | Surface that release-mode probes (`browser-test-prod-elision`, schemas boundary prod) cover changed. |
| `bundle_isolation` | Surface that can affect bundle boundaries (adapters, build scripts, examples used as probes, package metadata) changed. |
| `reagent_slim_bundle` | Reagent Slim adapter / its example / its check script changed. |
| `adapter_testbed_smokes` | Adapter surface changed (`implementation/adapters/*`) or the orchestrator scripts that drive the smokes (`examples/scripts/{serve-and-run-adapter-smokes,run-adapter-smokes,spec-helpers,adapter-smoke-filter}.cjs` — the last is the rf2-l72e2 shared adapter-smoke manifest + selection logic both orchestrator and runner import). Per rf2-bxdk8 + rf2-cjp0i + rf2-8cevm + rf2-t5slp: generic `examples/**` and `testbeds/**` paths no longer fire this gate (examples/ is test-free; testbed surfaces stay as Xray observation targets with no paired Playwright spec.cjs — see the rf2-tglku migration waves). Not set by `implementation/core/*`, per-feature artefacts, or build-config changes (rf2-8jz9t — adapter smokes only catch adapter-mount-specific bugs). |
| `tools_jvm` | Story / Xray / Story-MCP / Xray-MCP / Pair2-MCP / MCP-base changed; gates the four per-tool JVM probes (`jvm-tools-{xray,story,story-mcp,mcp-base}`). Not set by `tools/template/*` or `tools/mcp-conformance/*` — those don't share runtime with the per-tool probes. |
| `template_expensive` | `tools/template/*` changed; gates the template emitted-app smoke. |
| `mcp_conformance` | Any MCP-server tool, `tools/mcp-base/*`, or `tools/mcp-conformance/*` changed. |
| `mcp_live` | re-frame2-pair-mcp / mcp-base / mcp-conformance changed; gates the live MCP coverage. |
| `story_xray_browser` | Story / Xray runtime source changed under `tools/{story,xray}/{src,testbeds}/**` AND the changed file has a runtime extension (`.cljs`, `.cljc`, `.js`, `.cjs`, `.css`, `.scss`). Per rf2-k9ekz the trigger is narrowed: Markdown specs under `tools/{story,xray}/spec/**`, JVM unit tests under `tools/{story,xray}/test/**`, `deps.edn`, `README.md`, and `*.txt` do NOT fire it — they cannot affect chrome and so cannot invalidate the Playwright gate. Not set by the `-mcp` wrappers (they don't run in a browser). |
| `skills_structural` | `skills/re-frame2-pair/*`, `skills/re-frame2-setup/*`, or `skills/shared/*` changed. (The `re-frame2-setup` skill carries `tests/setup_drift_test.clj`; its day-one scaffold is additionally covered by a real materialise+compile fixture in the nightly `template_expensive` slice — see the template emitted-app note below.) |
| `playground` | `docs/tools/playground/*` changed, OR one of the three committed bundles (`docs/cljs/playground.js`, `docs/cljs/playground.css`, `docs/cljs/playground-rf2.js`) was hand-edited, OR (rf2-2h1yhk) `implementation/machines/*` changed — the SCI bundle bakes in the machines artefact + its reserved `:rf.machine/*` lifecycle keywords, so a keyword rename there can stale the committed `playground-rf2.js` with no other surface firing. Gates `tools-playground` (smoke + bundle-drift + SCI-bundle freshness guard `scripts/check-playground-sci-freshness.sh`). |

A few "blast-radius" inputs force the full sweep:

- A change to `.github/workflows/test.yml`, `.github/workflows/expensive-tests.yml`,
  the classifier script itself, or `TESTING.md` sets every output to
  `true` (defensive: anything that re-tiers the matrix must re-run the
  full matrix).
- Changes under `implementation/core/*` fan out broadly (they touch
  almost every output) because core regressions can break every
  downstream substrate, tool, and bundle invariant. Exceptions: the
  two Playwright gates (`adapter_testbed_smokes` and
  `story_xray_browser`) are **not** fired by `implementation/core/*`
  changes (rf2-8jz9t + rf2-k9ekz). The Playwright gates exist to
  catch surface-specific browser bugs (adapter mount lifecycle,
  Story variant boot, Xray panel layout) — none of which are core
  regressions. Core renames are caught by `node-test` (which
  exercises every public `re-frame.core` fn), and the nightly cron +
  post-merge gate runs the full matrix on main.

**Adding a new artefact directory**: a new artefact (e.g. a new tool,
new substrate, new SSR runtime) needs **two** matching changes:

1. A classifier rule in `.github/scripts/report-changed-surfaces.sh` —
   the rule decides which output(s) the new path lights.
2. A corresponding workflow gate in `.github/workflows/test.yml` (and
   `.github/workflows/expensive-tests.yml` for the rigorous variants) —
   one or more jobs whose `if:` condition reads the output(s) the rule
   sets.

Either side missing creates a silent hole: code can land that mutates
a surface no PR-time job watches. This pattern has bitten the repo
before (e.g. `implementation/ssr-ring/*` was added without a
matching classifier rule); when in doubt, prefer over-classifying
("fire `implementation_jvm` for the new directory") to under-
classifying.

When writing a new per-tool rule, set only the outputs whose jobs the
artefact's tests *actually exercise*. Coarse rules push unrelated jobs
into the matrix as `skipping` entries — runner-minute-free, but
they consume API quota, force branch-protection bookkeeping, and
clutter the PR-checks UI. rf2-os0c1 split four such over-firing rules:
`tools/template/*` no longer fires `tools_jvm` (template doesn't share
runtime with the per-tool JVM probes); `tools/story-mcp/*` no longer
fires `story_xray_browser` (MCP wrappers don't run in a browser);
`tools/mcp-conformance/*` no longer fires
`tools_jvm` (its wire-vocab JVM tests already run under
`mcp-conformance-wire-vocab`, which is gated by `mcp_conformance`).

The script also has a `--all` flag (forces every output `true`) and
accepts an explicit path list for local exploration:

```
.github/scripts/report-changed-surfaces.sh implementation/core/src/foo.cljs
.github/scripts/report-changed-surfaces.sh --all
```

The agent pre-checkin and `scripts/test-fast-pr.sh` spine cover the
always-on PR jobs; for the conditional surfaces, run the targeted
commands from the [Local commands](#local-commands) tables matching
whichever classifier outputs your diff trips.

### Dependency matrices

Two views of the same surface → output → jobs graph. The tables are
hand-maintained against [`.github/scripts/report-changed-surfaces.sh`](.github/scripts/report-changed-surfaces.sh)
(classifier rules) and [`.github/workflows/test.yml`](.github/workflows/test.yml)
(`if:` gates); update both halves whenever a classifier rule or job-`if:`
condition changes (per the **Adding a new artefact directory** rule above).

**Surface → output** — read this to verify "did my PR fire the right
classifier outputs?" Rows are surface groups; columns are the 15
classifier outputs (exact names from the script). A `✓` means a change
under that surface sets that output to `true`. The **blast-trigger row
(S1)** is bold: any change to those four files calls `mark_all` and
lights every output (defensive — anything that re-tiers the matrix
must re-run the matrix).

| # | Surface | `implementation_jvm` | `adapter_diagnostic` | `cljs_node_test` | `cljs_browser` | `cljs_prod` | `bundle_isolation` | `reagent_slim_bundle` | `adapter_testbed_smokes` | `tools_jvm` | `template_expensive` | `mcp_conformance` | `mcp_live` | `story_xray_browser` | `skills_structural` | `playground` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **S1** | **`.github/workflows/test.yml`, `.github/workflows/expensive-tests.yml`, `report-changed-surfaces.sh`, `TESTING.md` (blast trigger — `mark_all`)** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** |
| S2 | `implementation/core/*` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |   | ✓ | ✓ | ✓ | ✓ |   |   |   |
| S3 | `implementation/adapters/reagent-slim/*`, `examples/reagent-slim/counter_slim_and_fast/*`, `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` | ✓ | ✓ | ✓ | ✓ | ✓ |   | ✓ |   |   |   |   |   |   |   |   |
| S4 | `implementation/adapters/*` (other) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   | ✓ | ✓ | ✓ | ✓ | ✓ |   |   |   |
| S5 | `implementation/{schemas,machines,routing,flows,http,ssr,ssr-ring,resources,epoch}/*`, `implementation/deps.edn` (note: `schemas/*` also fires `template_expensive`; `machines/*` also fires `playground` — see S5a; `resources/*` added rf2-dxndhc) | ✓ |   | ✓ | ✓ | ✓ | ✓ |   |   |   |   |   |   |   |   |   |
| S5a | `implementation/machines/*` (rf2-2h1yhk — the SCI bundle bakes in the machines artefact + its reserved `:rf.machine/*` lifecycle keywords, so a keyword rename can stale the committed `playground-rf2.js`; in ADDITION to the S5 columns it fires `playground`) | ✓ |   | ✓ | ✓ | ✓ | ✓ |   |   |   |   |   |   |   |   | ✓ |
| S6 | `spec/conformance/fixtures/*` | ✓ |   | ✓ | ✓ | ✓ |   |   |   |   |   |   |   |   |   |   |
| S6a | `implementation/{reply-conformance,derivation-conformance,event-conformance}/*` (rf2-dxndhc — src-less `.cljc` cross-conformance tiers; the JVM `:clj`-arm jobs fire on `implementation_jvm`, the always-on `:node-test` `:cljs` arm on `cljs_node_test`; no production src so no bundle/browser/prod columns — mirrors the `security` tier) | ✓ |   | ✓ |   |   |   |   |   |   |   |   |   |   |   |   |
| S6b | `implementation/test-quiet/{src,test}/**`, `implementation/test-quiet/deps.edn` (rf2-am7grp — the quiet-reporter artefact: the JVM runner is the `:main-opts` of every per-artefact `:test` alias, the CLJS shadow-node runner is the `:node-test` build's `:main`; fires both so the JVM + CLJS quiet-reporter contracts run) | ✓ |   | ✓ |   |   |   |   |   |   |   |   |   |   |   |   |
| S7 | `implementation/shadow-cljs.edn`, `implementation/package.json`, `implementation/package-lock.json`, `implementation/scripts/*` |   |   | ✓ | ✓ | ✓ | ✓ | ✓ |   |   |   |   |   |   |   |   |
| S8 | `examples/*` (excluding the orchestrator scripts called out in S8a) |   |   |   | ✓ |   |   |   |   |   |   |   |   |   |   |   |
| S8a | `examples/scripts/{serve-and-run-adapter-smokes,run-adapter-smokes,spec-helpers,adapter-smoke-filter}.cjs` (orchestrator + runner + helpers + shared adapter-smoke manifest — rf2-bxdk8 + rf2-cjp0i + rf2-l72e2) |   |   |   |   |   |   |   | ✓ |   |   |   |   |   |   |   |
| S9 | `testbeds/*` (rf2-7vsfm — surfaces retained as Xray observation targets; rf2-t5slp retired the Playwright gate after all spec.cjs migrated to unit tests) |   |   |   | ✓ |   |   |   |   |   |   |   |   |   |   |   |
| S10 | `tools/template/*` |   |   |   |   |   |   |   |   |   | ✓ |   |   |   |   |   |
| S11 | `tools/story/{src,testbeds}/**`, `tools/xray/{src,testbeds}/**` (runtime-extension files — rf2-k9ekz; `.cljs`/`.cljc` also fire `cljs_node_test` — rf2-f79t8) |   |   | ✓ |   |   |   |   |   | ✓ |   | ✓ |   | ✓ |   |   |
| S11a | `tools/story/{spec,test,bench}/**`, `tools/xray/{spec,test}/**`, `tools/{story,xray}/{deps.edn,README.md}` (non-runtime under story/xray; a `.cljs`/`.cljc` file under `test/**` still compiles into `:node-test` and fires `cljs_node_test` — rf2-f79t8 — but `spec/**.md`, `bench/**`, `deps.edn`, `README.md` do not) |   |   | ✓ |   |   |   |   |   | ✓ |   | ✓ |   |   |   |   |
| S11b | `tools/machines-viz/*` (rf2-z0cw6s — `day8/re-frame2-machines-viz`, the shipped MachineChart + viewer + Mermaid/SCXML/PNG/SVG/share-URL export tool; CLJS-only, `{src,test}` are `:node-test` + `:browser-test` source paths. Every non-spec-md change fires `cljs_node_test` + `cljs_browser` — the latter runs the `*-dom-cljs-test` export/redaction suites verifying EP-0015 image-export egress. `spec/**.md` fires nothing. No JVM/MCP/template fan-out: CLJS-only, no MCP wrapper, not in the deps-new `:app`) |   |   | ✓ | ✓ |   |   |   |   |   |   |   |   |   |   |   |
| S12 | `tools/story-mcp/*` |   |   |   |   |   |   |   |   | ✓ |   | ✓ |   |   |   |   |
| S13 | `tools/re-frame2-pair-mcp/*`, `tools/mcp-base/*` |   |   |   |   |   |   |   |   | ✓ |   | ✓ | ✓ |   |   |   |
| S14 | `tools/mcp-conformance/*` |   |   |   |   |   |   |   |   |   |   | ✓ | ✓ |   |   |   |
| S15 | `skills/re-frame2-pair/tests/fixture/*` |   |   |   |   |   |   |   |   |   |   | ✓ | ✓ |   | ✓ |   |
| S16 | `skills/re-frame2-pair/*` (other), `skills/shared/*` |   |   |   |   |   |   |   |   |   |   |   |   |   | ✓ |   |
| S17 | `docs/tools/playground/*`, `docs/cljs/playground.js`, `docs/cljs/playground.css`, `docs/cljs/playground-rf2.js` (rf2-ee38b.22) |   |   |   |   |   |   |   |   |   |   |   |   |   |   | ✓ |

**Output → jobs** — read this to answer "if this output is `true`, what
runs?" Job counts are grouped (the matrix expands to 30+ leaf jobs at
PR time; one row per output here so the table stays scannable).

| Output | Jobs |
|---|---|
| `implementation_jvm` | JVM artefact unit suites ×13 (`jvm-core`, `jvm-flows`, `jvm-schemas`, `jvm-machines`, `jvm-routing`, `jvm-http`, `jvm-ssr`, `jvm-ssr-ring`, `jvm-resources`, `jvm-epoch`, plus the src-less cross-conformance tiers `jvm-reply-conformance`, `jvm-derivation-conformance`, `jvm-event-conformance` — rf2-dxndhc; note `jvm-security` is also armed by `implementation_jvm` but is listed under [Diagnostic / skip-ok gates]) |
| `adapter_diagnostic` | Adapter classpath probes ×4 (`jvm-reagent`, `jvm-reagent-slim`, `jvm-uix`, `jvm-helix`) |
| `cljs_node_test` | `cljs` (the `CLJS (shadow-cljs :node-test)` job — consolidated CLJS unit suite: `shadow-cljs compile node-test` + `node out/node-test.js`, covering core + every adapter + every feature artefact + the `tools/{story,xray}/{src,test}` source paths). |
| `cljs_browser` | `cljs-browser` (the `CLJS (shadow-cljs :browser-test, headless Chromium)` job — DOM `:browser-test` build served + driven by the Playwright runner; a distinct job from `cljs`, split off node-test gating in rf2-f79t8). |
| `cljs_prod` | Release-mode probes ×3 (`browser-test-prod-elision`, schemas boundary prod, etc.) |
| `bundle_isolation` | `bundle-isolation` |
| `reagent_slim_bundle` | `reagent-slim-bundle-isolation` |
| `adapter_testbed_smokes` | `adapter-testbed-smokes` (Playwright; the 3 adapter smokes only — rf2-9grp6 split out the framework + top-level testbeds into a separate gate, which rf2-t5slp then retired after all four rf2-tglku migration waves moved every framework + top-level testbed assertion to CLJS/JVM unit tests) |
| `tools_jvm` | Per-tool JVM probes ×4 (`jvm-tools-xray`, `jvm-tools-story`, `jvm-tools-story-mcp`, `jvm-tools-mcp-base`) |
| `template_expensive` | `jvm-tools-template` (emitted-app smoke) |
| `mcp_conformance` | MCP conformance ×3 (`mcp-conformance-{story,re-frame2-pair,wire-vocab}`) |
| `mcp_live` | `mcp-conformance-re-frame2-pair` (live + hermetic) |
| `story_xray_browser` | `story-xray-browser` (PR-smoke, Playwright — Xray feature-matrix `--smoke` + Story play-scripts only; the full Xray matrix, Story feature-load, and Story static run nightly in `expensive-tests.yml` — see the Story/Xray split above) |
| `skills_structural` | `skills-structural` |
| `playground` | `tools-playground` (Playwright smoke of both bundles + byte-diff `git diff --exit-code` of the deterministic esbuild `docs/cljs/playground.{js,css}` + smoke/structural-validity gate on the non-deterministic Closure `docs/cljs/playground-rf2.js` + (rf2-2h1yhk) the SCI-bundle freshness guard `scripts/check-playground-sci-freshness.sh`, which content-checks the committed `playground-rf2.js` against the current machine lifecycle creation marker so a keyword rename cannot leave it stale) |


## Diagnostic / skip-ok gates

Some checks intentionally exit 0 when their preconditions are absent. They are
diagnostic, not required coverage. Each row below reflects what the
corresponding job in [`.github/workflows/test.yml`](.github/workflows/test.yml)
actually does today:

| Gate | Workflow job | Why skip-ok |
|---|---|---|
| Adapter JVM classpath probes (Reagent / Reagent Slim / UIx / Helix) | `jvm-reagent`, `jvm-reagent-slim`, `jvm-uix`, `jvm-helix` | Adapter namespaces are `:cljs-only`. The job runs `clojure -M:test` with an `or-echo` fallback so a zero-test alias still proves the artefact's deps + classpath wiring stay green. Real adapter coverage is the browser counter + login specs (rf2-3yij / rf2-2qit Decision 7) under the adapter-testbed-smokes job, and per-adapter CLJS unit tests under the consolidated `node-test` build. |
| Pair2 live-overflow without nREPL | `mcp-conformance-re-frame2-pair` — step `Run re-frame2-pair-mcp live-overflow conformance (SKIPPED without nREPL)` | The step runs `npm run test:re-frame2-pair-live-overflow` (no env). The script exits 0 with a SKIP marker when `$SHADOW_CLJS_NREPL_PORT` is unset — so the SKIP path is exercised on every CI run (a regression that broke the SKIP, e.g. crashing on missing env, surfaces here). Real live coverage is the hermetic step that follows: `npm run test:re-frame2-pair-live-hermetic-suite` (which spawns shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`, sets `SHADOW_CLJS_NREPL_PORT`, and runs the whole live inner-test suite). |

Do not treat a skip-ok diagnostic as evidence that the underlying behaviour was
covered. The real coverage is the changed-surface, nightly/manual, or release
gate named in the table above.

## Per-tool coverage matrices

The per-tool spec trees carry auditable feature-coverage matrices that
enumerate every user-visible behaviour and pin it to a gate. TESTING.md
governs the meta-policy ("which scenario, which speed, which surface");
the per-tool matrices govern the contract for individual features.

| Tool | Coverage spec | Driving gate |
|---|---|---|
| Story | [`tools/story/spec/015-Test-Coverage.md`](tools/story/spec/015-Test-Coverage.md) | [`tools/story/test/story_feature_load.cjs`](tools/story/test/story_feature_load.cjs) (browser feature-load + 20-event re-check, run via `npm run test:story-feature-load` from `implementation/`). |
| Xray | [`tools/xray/spec/017-Test-Coverage-Matrix.md`](tools/xray/spec/017-Test-Coverage-Matrix.md) | [`implementation/scripts/serve-and-run-xray-feature-gate.cjs`](implementation/scripts/serve-and-run-xray-feature-gate.cjs) (browser feature/load matrix slice + 20-event re-check, run via `npm run test:xray-feature-gate` from `implementation/`). |

Both feature gates are split per the Story/Xray PR-smoke vs
nightly-full tiers above: a high-signal smoke runs on the PR critical
path (`test:xray-feature-gate:smoke` + `test:story-play-scripts`) and
the full sweep runs nightly. A coverage row that says `covered` in the
per-tool matrix and is gated by the feature command above is real (the
nightly-full sweep is the system of record for matrix coverage); a
`partial` or `missing` row is the owning team's backlog.

## Placement decision dimensions

Do not classify a test on one axis. A slow test is not automatically optional,
and an essential test is not automatically global PR CI. Decide placement by
combining these dimensions.

| Dimension | Question to answer | Policy implication |
|---|---|---|
| Scenario | Is this an agent pre-checkin, PR CI, nightly/manual, or release gate? | The same test can be mandatory before checkin and release without running on every unrelated PR. |
| Speed | What is the measured wall-clock cost, including browser/server/build setup? | Fast high-signal tests usually belong in PR CI. Slow tests need changed-surface, local, nightly, or release placement. |
| Essentiality | What product invariant does this protect? | Essential invariants must be covered somewhere mandatory; they do not have to be globally always-on if changed-surface coverage is reliable. |
| Changed surface | Which files can plausibly break this behaviour? | Select tests from the impact radius of the diff, not from a fixed one-size suite. |
| Dependency fan-out | Does the changed code sit upstream of other surfaces? | Core and shared MCP/tool substrate changes fan out broadly; leaf tool or adapter changes should run their owning gates and direct dependants. |
| Unique signal | What failure would this catch that cheaper tests would miss? | Keep expensive tests when they provide distinct signal; demote or narrow tests that mostly repeat cheaper coverage. |
| Fixture role | Is the app a human-facing example, an adapter smoke fixture, or a tool testbed? | Do not overload examples with internal canaries. Put adapter smoke tests with adapters and tool feature matrices under tool testbeds. |
| Naming | Does the command name reveal whether this is fast CI, local rigorous, release, changed-surface, or diagnostic? | Prefer names that encode the policy role. A passing `:diagnostic` or `:skip-ok` command must not be mistaken for real coverage. |
| Skip semantics | Can the command exit 0 because prerequisites are absent? | Name/document it as diagnostic or skip-ok; do not count a skipped diagnostic as behavioural coverage. |
| Failure quality | Will a failure be actionable from CI logs? | Green output should be quiet, but red must name the contract, owning surface, and reproduction command. |
| Security boundary | Does the change touch a redaction / escaping / off-box-egress boundary (SSR attr/script-body escaping, `:sensitive?` schema redaction, MCP `strip-sensitive`/`scrub-snapshot`/`projected-record` egress)? | These boundaries get an **adversarial-property** net, not just pin-and-assert — a hostile-input corpus PLUS generated casings/shapes/nestings, each verified to go RED if the protection is reverted. The net lives in the `security/` tier (`npm run test:security`, also ridden by always-on `test:cljs`). When you touch `html_helpers.cljc`, `schemas/walker.cljc`/`validate.cljc`, or `mcp_base/sensitive.cljc`, extend the matching `security/test/re_frame/security/*_security_cljs_test.cljc` namespace and re-run the revert-to-RED check for the surface you changed (rf2-3cfvt / rf2-bcams F2). |

Use this frame before adding any new Playwright, bundle, MCP, Story, or Xray
gate to PR CI. Browser and live-tool tests are valuable, but their cost grows
quickly. Prefer one small fixture that proves the owning contract over repeated
full-app sweeps. For adapter smoke tests, a minimal counter-style app is enough
when it proves render, dispatch, subscription update, cleanup, late-bind hooks,
and one owned failure path. Rich application behaviour belongs in the relevant
tool, example, or runtime tests, not duplicated once per adapter. If the timing
or unique signal is unknown, measure first and record the owning path before
changing the tier.

Concrete examples from the current repo:

- `npm run test:elision` protects a non-negotiable production invariant, but it
  does not need to run on every unrelated PR. It belongs in local pre-checkin
  for relevant runtime/tooling changes, changed-surface PR CI, nightly/manual
  runs, and release.
- `npm run test:bundle-isolation` is also essential, but should be selected by
  surfaces that can affect bundle boundaries, adapters, build scripts,
  examples used as probes, package metadata, or release tooling.
- Changes under `implementation/core/**` have broad fan-out. Assume they can
  require the full rigorous matrix unless the diff is obviously narrow.
- Changes under a tool are not automatically isolated. `tools/mcp-base/**`
  fans out to Story MCP, Pair2 MCP, future Xray MCP, and the shared
  conformance harness. `tools/story/**` fans out to Story MCP and Story browser
  gates. `tools/xray/**` fans out to Xray feature gates, production elision
  sentinels, and future Xray MCP coverage.
- Docs-only pushes should run docs, not tests. Bead-only pushes should run
  neither docs nor tests. PR CI still needs safe required-check behaviour, so
  avoid PR-level path filters that leave required checks pending forever.

The default placement rule is:

| Test kind | Typical placement |
|---|---|
| Fast essential signal | Every PR/checkin. |
| Fast nice-to-have signal | Usually every PR unless noisy or duplicative. |
| Slow essential signal | Agent pre-checkin for relevant changes, changed-surface PR CI, nightly/manual, and release. |
| Slow nice-to-have signal | Local, nightly/manual, or release; keep off the PR critical path unless the changed surface directly needs it. |

## Spec-impl-pair convention (rf2-4zqn7)

Top-level `spec/*.md` files have **no rule** in
`.github/scripts/report-changed-surfaces.sh`. A spec-only PR runs only the
always-on jobs (jvm-core, cljs, js-harness-self-tests, lockstep + skill/MCP
drift, and — for `docs/**` PRs — the MkDocs build). Per-feature JVM artefact
gates and the broad CLJS browser matrix do NOT fire.

This is intentional. `spec/*` is the normative artefact; not every spec edit
needs the full impl test matrix, and a blanket `spec/* → mark_all` rule would
be a sledgehammer that re-runs the rigorous matrix on every typo fix.

The expected pattern is the **spec-impl-pair convention**: a spec change
ships in the same PR as the impl/test change that realises it, so the impl
edit fires the relevant classifier rule and the spec edit rides along. The
2026-05-16 routing audit confirmed every spec edit in a 16-PR sample was
paired this way.

Rules for spec-only PRs:

- A spec-only PR is acceptable for pure normative refinements (clarifications,
  prose-only fixes, cross-reference updates) where the spec text is the only
  thing changing and no implementation behaviour is in scope.
- If a spec change implies an implementation change — even a test — pair the
  two in the same PR so the classifier picks the right surface.
- If a spec-only PR is genuinely needed and reviewers want the rigorous
  matrix run against it, push it through the nightly/manual
  `expensive-tests.yml` workflow before merge.

This is policy-by-convention, not by classifier. The audit's
recommendation is to revisit only if a real spec-only PR slips through and
causes a regression; first incident triggers a surgical per-spec-file rule
(e.g. `spec/011-SSR.md → fire jvm-ssr + jvm-ssr-ring`) rather than a blanket
rule.
