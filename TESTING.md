# Testing policy

re-frame2 has 4 types of tests:

1. **Agent pre-checkin** — `scripts/test-fast-pr.sh` plus the surface-specific command for files touched. Run the always-on PR spine locally, then add the narrow changed surface: JVM artefact, browser, bundle, tool, template, or skill structural tests.
2. **PR CI** — `.github/workflows/test.yml`. Always runs lockstep drift, skill/MCP drift, core JVM, CLJS node integration, JS harness self-tests, and docs link validation for docs PRs. Expensive jobs run only when conservative path filters say the owning surface changed.
3. **Nightly / manual** — `.github/workflows/expensive-tests.yml`. The rigorous browser/examples/bundle matrix, Story/Causa gates, template emitted-app smoke, and live MCP conformance — kept off the PR critical path.
4. **Release** — `.github/workflows/release.yml` plus the latest green expensive workflow. The core pre-release gate; release is cut only after the scheduled/manual expensive suite is green on the release candidate.

The goal: fast, high-signal PR feedback without dropping the expensive browser, bundle, tool, template, and live-MCP coverage needed before release.

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
| `scripts/test-rigorous-local.sh` | Fast spine + JVM coordinators + rigorous browser/bundle/examples/Story/Causa gates. Expensive; use before release-sized changes. |

### `implementation/package.json` (run from `implementation/`)

| Command | Scope |
|---|---|
| `npm run test:cljs` | CLJS node-runtime tests via shadow-cljs `node-test` build. The consolidated default gate for CLJS unit coverage. |
| `npm run test:browser` | Browser CLJS tests (`browser-test` build) served with Playwright + http-server. Headless Chromium harness. |
| `npm run test:browser-prod-elision` | Release-built browser tests proving production elision under the `browser-test-prod-elision` shadow build. |
| `npm run test:browser-schemas-boundary-prod` | Release-built browser test proving the schemas boundary-warn-once contract in production. |
| `npm run test:elision` | Production-release elision probe: compiles `elision-probe` + `elision-probe-control` and asserts elision is total. Non-negotiable production invariant. |
| `npm run test:perf-bundle` | Compiles the counter + counter-perf examples in release mode and checks the perf-budget bundle delta. |
| `npm run test:schemas-bundle` | Compiles `schemas-bundle-probe` (Spec) + `schemas-bundle-probe-malli` and checks schemas-bundle isolation. |
| `npm run test:bundle-isolation` | Compiles release counter/counter-uix/counter-helix bundles and runs `check-bundle-isolation` + `check-uix-helix-reagent-free`. Tools must not leak into production bundles; UIx/Helix bundles must be Reagent-free. |
| `npm run test:reagent-slim:bundle-isolation` | Reagent Slim invariant: slim advanced bundles exclude stock Reagent impl sentinels and `react-dom/server`, with a stock-Reagent positive control. |
| `npm run test:examples` | Browser smoke across the runnable examples. |
| `npm run test:examples:realworld` | Narrow RealWorld (Conduit / Spec 014) smoke only (rf2-h9ut9). The full sweep above is rigorous local / nightly / release; this changed-surface gate compiles and smokes one example end-to-end for cross-artefact runtime changes. Accepts `--filter <substring>` (or `EXAMPLES_FILTER=<substring>`) for ad-hoc narrowing against any single example or testbed. |
| `npm run test:story-feature-load` | Story full-browser feature-load and resilience gate (`tools/story/test/story_feature_load.cjs`). Occasional / pre-commit until proven stable — not yet default CI. |
| `npm run test:causa-feature-gate` | Causa browser feature/load gate from `tools/causa/spec/017-Test-Coverage-Matrix.md`. Occasional; not default CI. |
| `npm run test:story-static` | Static-build contract and deployable-output sanity for the Story export. |
| `npm run story:build` | Build the Story static artefact. |
| `npm run test:script-policy` / `npm run test:script-helpers` | Self-tests for the JS harness helpers (path policy, changed-surface classifier port, browser-test report, gate report, local browser harness). |

### `tools/mcp-conformance/package.json` (run from `tools/mcp-conformance/`)

| Command | Scope |
|---|---|
| `npm test` | Runs the full MCP-client conformance suite via `scripts/test-all.cjs` — pair2 degraded, story end-to-end, causa placeholder, and exec-safety unit tests, with live-overflow flagged SKIP/RUN by env. |
| `npm run test:pair2` | Degraded-mode pair2-mcp conformance against the SDK's strict `CallToolResultSchema`. |
| `npm run test:pair2-live-overflow` | Live-runtime overflow conformance — SKIPs cleanly without `$SHADOW_CLJS_NREPL_PORT`. |
| `npm run test:pair2-live-overflow-hermetic` | Hermetic live overflow — boots shadow-cljs against the `skills/re-frame-pair2/tests/fixture/` counter and runs the live path with a real over-budget eval. Catches cap-trigger threshold drift, marker shape regressions, and SDK strict-schema rejection. |
| `npm run test:pair2-live-subscribe` | Live-runtime subscribe/unsubscribe conformance. Gated on `$SHADOW_CLJS_NREPL_PORT`. |
| `npm run test:story` | End-to-end story-mcp MCP-client conformance. |
| `npm run test:exec-safety` | Unit tests for the trusted-PATH-resolution and symlink-safe-unlink helpers shared with the hermetic orchestrator. |

### `tools/pair2-mcp/package.json` (run from `tools/pair2-mcp/`)

| Command | Scope |
|---|---|
| `npm run build` | Build the pair2-mcp server (`shadow-cljs compile server`). |
| `npm test` | Compile + run the pair2-mcp `server-test` build under node. |
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
(`reagent`, `reagent-slim`, `uix`, `helix`) and the `tools/causa`
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
| `cljs_browser` | CLJS surface that the browser tests cover changed. |
| `cljs_prod` | Surface that release-mode probes (`browser-test-prod-elision`, schemas boundary prod) cover changed. |
| `bundle_isolation` | Surface that can affect bundle boundaries (adapters, build scripts, examples used as probes, package metadata) changed. |
| `reagent_slim_bundle` | Reagent Slim adapter / its example / its check script changed. |
| `examples_browser` | Examples surface changed. |
| `tools_jvm` | Story / Causa / Story-MCP / Causa-MCP / Pair2-MCP / MCP-base changed; gates the four per-tool JVM probes (`jvm-tools-{causa,story,story-mcp,mcp-base}`). Not set by `tools/template/*` or `tools/mcp-conformance/*` — those don't share runtime with the per-tool probes. |
| `template_expensive` | `tools/template/*` changed; gates the template emitted-app smoke. |
| `mcp_conformance` | Any MCP-server tool, `tools/mcp-base/*`, or `tools/mcp-conformance/*` changed. |
| `mcp_live` | Pair2-mcp / mcp-base / mcp-conformance changed; gates the live MCP coverage. |
| `story_causa_browser` | `tools/story/*` or `tools/causa/*` runtime changed. Not set by the `-mcp` wrappers (they don't run in a browser). |
| `skills_structural` | `skills/re-frame-pair2/*` or `skills/shared/*` changed. |

A few "blast-radius" inputs force the full sweep:

- A change to `.github/workflows/test.yml`, `.github/workflows/expensive-tests.yml`,
  the classifier script itself, or `TESTING.md` sets every output to
  `true` (defensive: anything that re-tiers the matrix must re-run the
  full matrix).
- Changes under `implementation/core/*` fan out broadly (they touch
  almost every output) because core regressions can break every
  downstream substrate, tool, and bundle invariant.

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
fires `story_causa_browser` (MCP wrappers don't run in a browser);
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
classifier outputs?" Rows are surface groups; columns are the 13
classifier outputs (exact names from the script). A `✓` means a change
under that surface sets that output to `true`. The **blast-trigger row
(S1)** is bold: any change to those four files calls `mark_all` and
lights every output (defensive — anything that re-tiers the matrix
must re-run the matrix).

| # | Surface | `implementation_jvm` | `adapter_diagnostic` | `cljs_browser` | `cljs_prod` | `bundle_isolation` | `reagent_slim_bundle` | `examples_browser` | `tools_jvm` | `template_expensive` | `mcp_conformance` | `mcp_live` | `story_causa_browser` | `skills_structural` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **S1** | **`.github/workflows/test.yml`, `.github/workflows/expensive-tests.yml`, `report-changed-surfaces.sh`, `TESTING.md` (blast trigger — `mark_all`)** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** |
| S2 | `implementation/core/*` | ✓ | ✓ | ✓ | ✓ | ✓ |   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| S3 | `implementation/adapters/reagent-slim/*`, `examples/reagent/counter_slim_and_fast/*`, `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` | ✓ | ✓ | ✓ | ✓ |   | ✓ | ✓ |   |   |   |   |   |   |
| S4 | `implementation/adapters/*` (other) | ✓ | ✓ | ✓ | ✓ | ✓ |   | ✓ | ✓ | ✓ | ✓ | ✓ |   |   |
| S5 | `implementation/{schemas,machines,routing,flows,http,ssr,ssr-ring,epoch}/*`, `implementation/deps.edn` | ✓ |   | ✓ | ✓ | ✓ |   | ✓ |   |   |   |   |   |   |
| S6 | `spec/conformance/fixtures/*` | ✓ |   | ✓ | ✓ |   |   |   |   |   |   |   |   |   |
| S7 | `implementation/shadow-cljs.edn`, `implementation/package.json`, `implementation/package-lock.json`, `implementation/scripts/*` |   |   | ✓ | ✓ | ✓ | ✓ | ✓ |   |   |   |   | ✓ |   |
| S8 | `examples/*` |   |   | ✓ |   |   |   | ✓ |   |   |   |   |   |   |
| S9 | `testbeds/*` |   |   | ✓ |   |   |   | ✓ |   |   |   |   |   |   |
| S10 | `tools/template/*` |   |   |   |   |   |   |   |   | ✓ |   |   |   |   |
| S11 | `tools/story/*`, `tools/causa/*` |   |   |   |   |   |   |   | ✓ |   | ✓ |   | ✓ |   |
| S12 | `tools/story-mcp/*` |   |   |   |   |   |   |   | ✓ |   | ✓ |   |   |   |
| S13 | `tools/pair2-mcp/*`, `tools/mcp-base/*` |   |   |   |   |   |   |   | ✓ |   | ✓ | ✓ |   |   |
| S14 | `tools/mcp-conformance/*` |   |   |   |   |   |   |   |   |   | ✓ | ✓ |   |   |
| S15 | `skills/re-frame-pair2/tests/fixture/*` |   |   |   |   |   |   |   |   |   | ✓ | ✓ |   | ✓ |
| S16 | `skills/re-frame-pair2/*` (other), `skills/shared/*` |   |   |   |   |   |   |   |   |   |   |   |   | ✓ |

**Output → jobs** — read this to answer "if this output is `true`, what
runs?" Job counts are grouped (the matrix expands to 30+ leaf jobs at
PR time; one row per output here so the table stays scannable).

| Output | Jobs |
|---|---|
| `implementation_jvm` | JVM artefact unit suites ×9 (`jvm-core`, `jvm-flows`, `jvm-schemas`, `jvm-machines`, `jvm-routing`, `jvm-http`, `jvm-ssr`, `jvm-ssr-ring`, `jvm-epoch`) |
| `adapter_diagnostic` | Adapter classpath probes ×4 (`jvm-reagent`, `jvm-reagent-slim`, `jvm-uix`, `jvm-helix`) |
| `cljs_browser` | `node-test` (consolidated CLJS unit + browser-test) |
| `cljs_prod` | Release-mode probes ×3 (`browser-test-prod-elision`, schemas boundary prod, etc.) |
| `bundle_isolation` | `bundle-isolation` |
| `reagent_slim_bundle` | `reagent-slim-bundle-isolation` |
| `examples_browser` | `examples-browser` (Playwright, testbeds + examples) |
| `tools_jvm` | Per-tool JVM probes ×4 (`jvm-tools-causa`, `jvm-tools-story`, `jvm-tools-story-mcp`, `jvm-tools-mcp-base`) |
| `template_expensive` | `jvm-tools-template` (emitted-app smoke) |
| `mcp_conformance` | MCP conformance ×4 (`mcp-conformance-{story,pair2,wire-vocab,...}`) |
| `mcp_live` | `mcp-conformance-pair2` (live + hermetic) |
| `story_causa_browser` | `story-causa-browser` (feature gates, Playwright) |
| `skills_structural` | `skills-structural` |


## Diagnostic / skip-ok gates

Some checks intentionally exit 0 when their preconditions are absent. They are
diagnostic, not required coverage. Each row below reflects what the
corresponding job in [`.github/workflows/test.yml`](.github/workflows/test.yml)
actually does today:

| Gate | Workflow job | Why skip-ok |
|---|---|---|
| Adapter JVM classpath probes (Reagent / Reagent Slim / UIx / Helix) | `jvm-reagent`, `jvm-reagent-slim`, `jvm-uix`, `jvm-helix` | Adapter namespaces are `:cljs-only`. The job runs `clojure -M:test` with an `or-echo` fallback so a zero-test alias still proves the artefact's deps + classpath wiring stay green. Real adapter coverage is the browser counter + login specs (rf2-3yij / rf2-2qit Decision 7) under the examples-browser job, and per-adapter CLJS unit tests under the consolidated `node-test` build. |
| `tools/causa` JVM probe | `jvm-tools-causa` | Causa ships two JVM tests today (`config_test.clj`, `trace_bus_test.clj`); an `or-echo` fallback keeps the job green if that set shrinks to zero on an intermediate cut. The CLJS surface is already covered by the consolidated `node-test` build (shadow-cljs.edn lists `tools/causa/test` as a source path). |
| Pair2 live-overflow without nREPL | `mcp-conformance-pair2` — step `Run pair2-mcp live-overflow conformance (SKIPPED without nREPL)` | The step runs `npm run test:pair2-live-overflow` (no env). The script exits 0 with a SKIP marker when `$SHADOW_CLJS_NREPL_PORT` is unset — so the SKIP path is exercised on every CI run (a regression that broke the SKIP, e.g. crashing on missing env, surfaces here). Real live coverage is the hermetic step that follows: `npm run test:pair2-live-overflow-hermetic` (which spawns shadow-cljs + Chromium against `skills/re-frame-pair2/tests/fixture/`, sets `SHADOW_CLJS_NREPL_PORT`, and runs the same script). |

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
| Causa | [`tools/causa/spec/017-Test-Coverage-Matrix.md`](tools/causa/spec/017-Test-Coverage-Matrix.md) | [`implementation/scripts/serve-and-run-causa-feature-gate.cjs`](implementation/scripts/serve-and-run-causa-feature-gate.cjs) (browser feature/load matrix slice + 20-event re-check, run via `npm run test:causa-feature-gate` from `implementation/`). |

Both feature gates are deliberately occasional / pre-commit, not
default CI, while their flake rate and runtime stabilise. A coverage
row that says `covered` in the per-tool matrix and is gated by the
feature command above is real; a `partial` or `missing` row is the
owning team's backlog.

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

Use this frame before adding any new Playwright, bundle, MCP, Story, or Causa
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
  fans out to Story MCP, Pair2 MCP, future Causa MCP, and the shared
  conformance harness. `tools/story/**` fans out to Story MCP and Story browser
  gates. `tools/causa/**` fans out to Causa feature gates, production elision
  sentinels, and future Causa MCP coverage.
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
