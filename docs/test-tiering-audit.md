# Test Tiering Audit

Source bead: `rf2-24s9c`.

This is a proposal only. It does not change GitHub Actions workflows, package
scripts, test runners, or bead state.

## Goal

CI should be much faster while still catching high-value integration breakage.
The project can require responsible contributors to run rigorous local checks
before commit, but CI should not spend every pull request on every expensive
browser, conformance, example, bundle-size, and release-adjacent gate.

Keep the Mayor Method invariant:

- Green can be quiet: one-line summaries, no buffered browser logs unless a
  gate fails or `RF2_VERBOSE_TESTS=1` is set.
- Red must be actionable: failures must name the violated contract, the command
  to reproduce it, and the owning surface.

## Current Inventory

### GitHub Actions

`.github/workflows/test.yml` currently runs on every push to `main` and every
pull request to `main`. It fans out many independent jobs:

| Job family | Representative commands | Notes |
|---|---|---|
| Drift checks | `.github/scripts/verify-version-lockstep.sh`, `python scripts/check_skill_mcp_drift.py --verbose --ci` | Fast, high signal, pure repo scans. |
| JVM implementation artefacts | `clojure -M:test` under `implementation/core`, adapters, `schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch` | Many are real suites; adapter jobs are mostly classpath probes that intentionally allow empty JVM suites. |
| CLJS implementation | `npm run test:cljs`, `npm run test:browser`, prod browser elision gates | `test:cljs` is Node. `test:browser` and prod-mode variants require Playwright/Chromium. |
| Bundle gates | `npm run test:elision`, `test:bundle-isolation`, `test:bundle-comparison` | Advanced Closure builds plus sentinel greps. |
| Examples and testbeds | `npm run test:examples` | Compiles many independent browser apps, stages HTML, serves `out/examples`, runs Playwright specs. |
| Tools JVM | `clojure -M:test` under `tools/causa`, `story`, `story-mcp`, `mcp-base`, `template` | Template CI enables slow behavioural env vars. |
| Tools Node/CLJS | `npm test` under `tools/pair2-mcp`; `node test/stdio-roundtrip.js`, `node test/live-server.js` under `tools/story-mcp` | Tool-specific integration. |
| MCP conformance | `npm run test:pair2`, `test:pair2-live-overflow`, `test:pair2-live-overflow-hermetic`, `test:story`, `test:causa`, `clojure -M:test` in `wire-vocab` | SDK-client conformance plus a hermetic live pair2 fixture with Playwright and shadow-cljs. |

`.github/workflows/docs.yml` runs on `main`, on manual dispatch, and on
pull requests that touch documentation-related paths. It runs:

- `python scripts/check_doc_slugs.py --self-test --verbose`
- `python scripts/check_doc_slugs.py --verbose`
- `mkdocs build` after staging `spec/` into `docs/spec/`

`.github/workflows/release.yml` runs on version tags. Its pre-release gate is
already stricter and more release-like than the PR workflow: lockstep version
verification, all JVM implementation artefact tests, `npm run test:cljs`, and
`npm run test:elision`, followed by deploy jobs.

### Package Scripts And Local Commands

Implementation scripts in `implementation/package.json`:

| Command | Current role |
|---|---|
| `npm run test:cljs` | CLJS node-test sweep over implementation, examples wrappers, and some tools. |
| `npm run test:browser` | Browser-test sweep in headless Chromium. |
| `npm run test:browser-prod-elision` | Advanced prod browser smoke for trace/source-coordinate elision. |
| `npm run test:browser-schemas-boundary-prod` | Advanced prod browser smoke for schema boundary validation. |
| `npm run test:elision` | Advanced Closure probe/control grep for production elision. |
| `npm run test:bundle-isolation` | Advanced counter/UIx/Helix builds plus absence greps. |
| `npm run test:bundle-comparison` | Slim Reagent bundle comparison against stock Reagent. |
| `npm run test:examples` | Full examples/testbeds Playwright sweep. |
| `npm run test:perf-bundle` | Performance-instrumentation bundle-presence grep. |
| `npm run test:schemas-bundle` | Schema/Malli bundle-cost envelope. |
| `npm run story:build` | Story static export build. |
| `npm run test:story-static` | Story static export verification. |
| `npm run test:story-feature-load` | Narrow Story feature-load browser gate over Story testbeds. |
| `npm run test:script-policy` | JS path-policy unit test. |
| `npm run test:script-helpers` | JS report-helper unit tests. |

JVM aliases:

- Each `implementation/<artefact>/deps.edn` exposes `:test`.
- `implementation/deps.edn` exposes an aggregate `:test` for ad-hoc combined
  work, but CI intentionally runs per artefact.
- Each `tools/<tool>/deps.edn` exposes `:test`.
- `tools/deps.edn` exposes an aggregate `:test` across JVM-runnable tools.
- `tools/mcp-conformance/wire-vocab/deps.edn` exposes `:test`.

Tool package scripts:

- `tools/pair2-mcp`: `npm test`, `npm run build`,
  `npm run stdio-roundtrip`.
- `tools/pair2-mcp/pilot`: `npm test` for pilot CLJS tests.
- `tools/mcp-conformance`: `npm test`, `test:pair2`,
  `test:pair2-live-overflow`, `test:pair2-live-subscribe`,
  `test:pair2-live-overflow-hermetic`, `test:story`, `test:causa`,
  `test:exec-safety`.
- Template fixtures under `tools/template/src/clj/new/re_frame2/*` each ship
  `npm test`, `npm run release`, and `npm run watch`.

Skills:

- `skills/re-frame-pair2/docs/TESTING.md` documents bb runtime/shim/prompt
  tests and live browser E2E tests. The doc claims some surfaces run per-push
  or nightly, but the repo-level workflows do not currently wire most of
  those skill tests.
- `skills/shared/tests/README.md` documents a fast bb structural regression
  test for `skills/shared/retro-protocol.md`; it is explicitly not wired into
  `.github/workflows/`.
- Behavioural skill fixtures are document-runnable and diagnostic, not CI
  commands.

Docs:

- `docs/quiet-tests.md` is the current policy anchor for quiet green output
  and verbose opt-in via `RF2_VERBOSE_TESTS=1`.
- `examples/TESTING.md` is the current anchor for the difference between
  `test:browser` and `test:examples`.

## Proposed Tiers

### Required Fast CI

Run on every non-draft PR unless the workflow can prove the PR is docs-only.
These should be required checks.

| Gate | Command | Why it stays mandatory |
|---|---|---|
| Lockstep drift | `.github/scripts/verify-version-lockstep.sh` | Very fast. Catches broken release topology before deeper tests waste time. |
| Skill/MCP drift | `python scripts/check_skill_mcp_drift.py --verbose --ci` | Very fast. Prevents agent-host tool catalogue drift. |
| Core JVM contract | `cd implementation/core && clojure -M:test` | Highest-value JVM integration surface; includes core conformance and many cross-cutting runtime contracts. |
| CLJS node integration | `cd implementation && npm run test:cljs` | Single broad CLJS compile/run surface across implementation, examples wrappers, Story, and Causa CLJS tests. Catches platform split regressions without a browser. |
| Production elision | `cd implementation && npm run test:elision` | Mandatory despite cost. Zero-prod-overhead is a shipping invariant and cannot be inferred from JVM tests. |
| Bundle isolation | `cd implementation && npm run test:bundle-isolation` | Mandatory for implementation/tool split trust. It catches accidental reverse imports that unit tests can miss. |
| JS harness self-tests | `cd implementation && npm run test:script-policy && npm run test:script-helpers` | Cheap, keeps failure output and path-safety infrastructure trustworthy. |
| Docs link validator on doc PRs | `python scripts/check_doc_slugs.py --self-test --verbose` and `python scripts/check_doc_slugs.py --verbose` | Path-filtered already. Broken docs links are cheap to detect and expensive to clean up later. |

Rationale: this set catches broken dependency topology, obvious runtime
contract failures, CLJS compilation failures, production elision leaks, and
bundle-boundary regressions. It avoids full browser/example/MCP-live cost on
unrelated PRs.

### PR / Changed-Surface CI

Run when changed paths intersect the owning surface. These checks may remain
required when they run, but should not run on every PR.

| Surface | Commands | Trigger proposal |
|---|---|---|
| Per-artefact implementation JVM | `clojure -M:test` under `schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `ssr-ring`, `epoch`, adapters | Run for changes under the artefact, shared `implementation/core`, relevant `spec/conformance`, or release/package metadata. Keep adapter classpath probes changed-surface only. |
| Browser unit tests | `npm run test:browser` | Run for adapter/view/substrate/browser harness/example-wrapper changes, or any change that touches DOM/rendering/timing assumptions. |
| Prod browser elision | `npm run test:browser-prod-elision`, `npm run test:browser-schemas-boundary-prod` | Run for `interop`, trace, schemas, source-coordinate, adapter DOM annotation, and prod-gate changes. |
| Examples Playwright | `npm run test:examples` | Run for `examples/**`, `testbeds/**`, example build entries, adapters, route/HTTP/SSR surfaces, Story/Causa testbeds. |
| Slim bundle comparison | `npm run test:bundle-comparison` | Run for `implementation/adapters/reagent-slim/**`, Reagent adapter, counter-slim example, bundle sentinel scripts, React/Reagent deps. |
| Schema bundle cost | `npm run test:schemas-bundle` | Run for `implementation/schemas/**`, Malli dependency changes, schema bundle probes, size thresholds. |
| Perf bundle cost | `npm run test:perf-bundle` | Run for `implementation/core` perf instrumentation, `tools/causa`, perf counter testbed, perf bundle scripts. |
| Story static and feature-load | `npm run story:build`, `npm run test:story-static`, `npm run test:story-feature-load` | Run for `tools/story/**`, Story testbeds, Story docs that embed executable assumptions, Story static scripts. |
| Tool JVM | `clojure -M:test` in the changed `tools/<tool>` | Run only when that tool or its direct implementation deps changed. |
| Template behavioural smoke | `cd tools/template && RF2_TEMPLATE_DEPS_RESOLVE=1 RF2_TEMPLATE_RUN_EMITTED_TESTS=1 clojure -M:test` | Run for template changes, generated fixture changes, or release packaging changes. Otherwise keep local/release. |
| Pair2 MCP Node tests | `cd tools/pair2-mcp && npm test` | Run for `tools/pair2-mcp/**`, `tools/mcp-base/**`, wire vocab changes, or pair2 skill/runtime changes. |
| Story MCP integration | `node test/stdio-roundtrip.js`, `node test/live-server.js` | Run for `tools/story-mcp/**`, `tools/story/**`, MCP base, and wire vocab changes. |
| MCP client conformance | `npm run test:pair2`, `npm run test:story`, `npm run test:exec-safety`, wire-vocab `clojure -M:test` | Run for MCP server, MCP base, conformance harness, or skill catalogue changes. |
| Causa MCP placeholder | `npm run test:causa` | Diagnostic or changed-surface only until `tools/causa-mcp` has an implementation. |
| Skill structural tests | `bb skills/re-frame-pair2/tests/prompts/prompt_regression_test.clj`, `bb skills/re-frame-pair2/tests/shim/shim_test.clj`, `bb skills/shared/tests/retro_protocol_test.clj` | Run for changed skill docs/scripts/tests only. |

### Explicit Pre-Commit / Local Rigorous

This is the suite contributors should run before pushing non-trivial runtime,
adapter, tool, or example work. CI should not run all of it on every PR.

Suggested command groups:

```bash
# Implementation rigorous
cd implementation
npm ci
npm run test:cljs
npm run test:browser
npm run test:browser-prod-elision
npm run test:browser-schemas-boundary-prod
npm run test:elision
npm run test:bundle-isolation
npm run test:bundle-comparison
npm run test:schemas-bundle
npm run test:perf-bundle
npm run test:examples
```

```bash
# JVM rigorous, per touched artefact
cd implementation/core && clojure -M:test
cd implementation/schemas && clojure -M:test
cd implementation/machines && clojure -M:test
cd implementation/routing && clojure -M:test
cd implementation/flows && clojure -M:test
cd implementation/http && clojure -M:test
cd implementation/ssr && clojure -M:test
cd implementation/ssr-ring && clojure -M:test
cd implementation/epoch && clojure -M:test
```

```bash
# Tools rigorous, per touched tool
cd tools/causa && clojure -M:test
cd tools/story && clojure -M:test
cd tools/story-mcp && clojure -M:test
cd tools/mcp-base && clojure -M:test
cd tools/template && RF2_TEMPLATE_DEPS_RESOLVE=1 RF2_TEMPLATE_RUN_EMITTED_TESTS=1 clojure -M:test
cd tools/pair2-mcp && npm install && npm test
cd tools/mcp-conformance && npm install && npm test
```

```bash
# Skill rigorous, per touched skill
bb skills/re-frame-pair2/tests/prompts/prompt_regression_test.clj
bb skills/re-frame-pair2/tests/shim/shim_test.clj
bb skills/shared/tests/retro_protocol_test.clj
```

### Release / Nightly / Manual

Use this tier for expensive, live, flaky, or release-adjacent confidence. These
checks are still important, but not per-PR critical path.

| Gate | Why not every PR | Recommended cadence |
|---|---|---|
| Full `npm run test:examples` on all examples/testbeds | Many advanced builds plus Playwright. High integration value but expensive. | Changed-surface PR, nightly full sweep, release preflight. |
| Pair2 hermetic live MCP conformance | Boots shadow-cljs fixture, browser, pair2 server, and SDK client. Highest fidelity, highest setup cost. | Changed-surface for pair2/MCP changes; nightly; release. |
| Pair2 live subscribe/overflow with real nREPL | Requires live runtime and has orchestration cost. | Nightly and release; local before MCP protocol changes. |
| Story feature-load gate | Narrow but browser-based and Story-specific. | Changed-surface Story PRs; nightly. |
| Story static export build | Release-like static artefact check. | Changed-surface Story PRs; release. |
| Template emitted-app behavioural compile/run | 30-60s per substrate cold-cache and needs Node. | Changed-surface template PRs; release. |
| Schema/perf bundle cost | Advanced builds and thresholds are valuable but mostly size-budget drift. | Changed-surface and release; nightly if thresholds are noisy. |
| Docs full `mkdocs build` | Already path-filtered. | Docs PRs and `main`; no need on code-only PRs. |

### Diagnostic Only

These should not be required CI until they gain a real failure signal:

- `tools/mcp-conformance` Causa MCP placeholder, while `tools/causa-mcp` is
  spec-only and the test exits with a clean SKIP.
- `test:pair2-live-overflow` without `$SHADOW_CLJS_NREPL_PORT`; useful to test
  the SKIP path, but not evidence of live conformance by itself.
- Skill behavioural fixtures under `skills/shared/tests/fixtures/`; they are
  human/AI replay fixtures, not deterministic CI.
- `skills/re-frame-pair2/tests/e2e/run.cjs` when it soft-skips because no
  fixture is running; useful in local workflows but not a hard CI signal unless
  CI owns fixture startup.
- Adapters' JVM empty-suite classpath probes as currently written. They are
  useful changed-surface smoke checks, but `|| echo` makes them inappropriate
  as always-on trust anchors.

## Redundant In CI But Valuable Locally

- Most per-artefact JVM jobs duplicate broad signal already covered by
  `implementation/core` plus `npm run test:cljs` for unrelated PRs. Keep them
  as changed-surface and local rigorous gates.
- `test:browser` overlaps with `test:cljs` for pure CLJS logic. Keep browser
  CI for DOM/rendering/substrate/timing changes, not pure data changes.
- `test:examples` overlaps with browser wrappers for many examples, but is
  still valuable locally because each example runs as its own app page. Use it
  before committing example or adapter changes.
- `node-test-tools-story-mcp` hand-rolled stdio tests overlap with MCP SDK
  conformance. Keep both for MCP changes, but do not run both on unrelated
  runtime PRs.
- Template emitted-app compile/run is valuable before template changes land;
  running it on every PR is low leverage.
- Skill prompt/retro structural tests are valuable when skill content changes,
  but add no signal for implementation-only PRs.

## Mandatory Despite Cost

- `npm run test:elision`: production zero-overhead is a core product invariant.
- `npm run test:bundle-isolation`: prevents tools and split artefacts from
  leaking into consumer bundles.
- `implementation/core clojure -M:test`: highest-value JVM contract sweep and
  conformance runner.
- `npm run test:cljs`: broadest CLJS compile/run signal without browser setup.
- MCP SDK conformance for MCP server changes: hand-rolled JSON-RPC probes are
  not enough because real clients validate responses through SDK schemas.
- RealWorld example coverage, when any cross-artefact runtime surface changes:
  it is the canonical multi-artefact integration app and should remain in the
  changed-surface set even if the full examples suite moves out of always-on CI.

## Naming And Documentation Changes Needed

The current command names mostly describe mechanism, not policy. Humans and
agents need stable names for "what CI runs" versus "what I run before commit".

Proposed additions, implemented later:

- Add a tracked `docs/testing-policy.md` or extend this file after decision to
  become the canonical policy.
- Add a short matrix to `implementation/README.md` that separates:
  `Fast CI`, `Changed-surface CI`, `Local rigorous`, and `Release/nightly`.
- Add package aliases under `implementation/package.json`, without changing
  existing commands:
  - `test:ci:fast`
  - `test:local:rigorous`
  - `test:browser:rigorous`
  - `test:bundle:rigorous`
  - `test:story:rigorous`
- Add coordinator scripts for JVM artefacts rather than asking humans/agents to
  remember a long list of directories.
- Rename or document SKIP-capable commands with `:diagnostic` or `:skip-ok`
  semantics so an exit code 0 is not confused with covered behaviour.
- Update `docs/quiet-tests.md` with the tiering rule: green summaries remain
  quiet in all tiers; verbose diagnostics stay opt-in or red-only.
- Update `examples/TESTING.md` to say `test:examples` is the rigorous
  per-example integration suite, while CI may run a changed-surface subset.
- Fix drift between `skills/re-frame-pair2/docs/TESTING.md` and actual repo CI;
  the doc currently describes per-push/nightly gating that is not present in
  `.github/workflows/`.

## Risk Analysis

| Risk fast CI may miss | Why it can be missed | Mitigation |
|---|---|---|
| Browser-only React/substrate timing bugs | Fast CI keeps Node CLJS but may skip Playwright. | Changed-surface browser CI, mandatory local `test:browser` before adapter/view changes, nightly browser sweep. |
| Example-only wiring breakage | Full examples suite may not run for unrelated PRs. | Path-filter examples CI; require `test:examples` before example/adapter/runtime-composition PRs; nightly full sweep. |
| MCP live-runtime regressions | Degraded SDK tests can pass without a live shadow-cljs runtime. | Changed-surface hermetic pair2 conformance; nightly live conformance; release gate. |
| Story/Causa testbed feature-load bugs | Tool testbeds are browser-heavy and not always-on. | Changed-surface Story/Causa gates; local rigorous commands in tool docs; nightly. |
| Bundle-size budget drift | Fast CI keeps isolation/elision but may skip perf/schema bundle cost. | Changed-surface size gates; release gate; thresholds report clear deltas. |
| Per-artefact JVM regression outside core | Always-on core may not exercise every artefact-specific JVM test. | Changed-surface per-artefact CI and local rigorous per touched artefact. |
| Skill content regression | Implementation fast CI does not run skill tests. | Path-filter skill tests; keep behavioural fixtures diagnostic/manual. |
| False trust from SKIP gates | Some commands exit 0 on intentional SKIP. | Name/document SKIP-capable commands as diagnostic, and do not make SKIP-only gates required. |

Trust stays high if the required fast set remains stable, red failures are
actionable, changed-surface matching is conservative, and release/nightly runs
the full expensive matrix often enough to catch classification mistakes.

## Implementation Plan As Proposed Follow-Up Beads

Do not create these automatically from this audit without mayor approval. The
following are proposed beads or bead clusters.

1. `ci: define fast required workflow and path filters`
   - Split `.github/workflows/test.yml` into always-on fast jobs and
     path-filtered changed-surface jobs.
   - Preserve current job names or add compatibility aliases until required
     branch protection is updated.

2. `test: add command aliases for tiered local runs`
   - Add `test:ci:fast`, `test:local:rigorous`, and focused rigorous aliases.
   - Keep existing command names as stable lower-level entry points.

3. `docs: publish test policy and command matrix`
   - Promote this audit into the canonical testing policy after decisions.
   - Update `implementation/README.md`, `examples/TESTING.md`,
     `docs/quiet-tests.md`, and tool/skill testing docs.

4. `ci: make SKIP-capable gates explicit`
   - Rename, annotate, or split SKIP-path checks so required checks represent
     real coverage.
   - Start with Causa MCP placeholder and pair2 live-overflow without nREPL.

5. `ci: wire skill structural tests by changed path`
   - Add bb setup and run `skills/shared` plus `skills/re-frame-pair2`
     structural tests only when corresponding skill files change.

6. `ci: move expensive live/browser gates to nightly plus changed-surface`
   - Add a scheduled full browser/examples/MCP-live workflow.
   - Keep changed-surface PR gates required when they run.

7. `test: create JVM artefact coordinator`
   - Provide a single non-interactive command for "all implementation JVM
     artefacts" and one for "all tool JVM artefacts".
   - Make output quiet on green and red actionable.

8. `ci: add changed-surface RealWorld smoke selector`
   - If the full examples suite is too expensive, introduce a narrow
     RealWorld-only Playwright command for cross-artefact runtime PRs.

## Verification For This Proposal

This proposal should be verified with lightweight documentation checks only:

- `git diff --check`
- Optional path/link existence checks for referenced local files
