# Testing policy

re-frame2 uses tiered tests. The goal is fast, high-signal PR feedback without
dropping the expensive browser, bundle, tool, template, and live-MCP coverage
needed before release.

## Scenarios

| Scenario | Command / workflow | Contract |
|---|---|---|
| Agent pre-checkin | `scripts/test-fast-pr.sh` plus the surface-specific command for files touched | Run the always-on PR spine locally, then add the narrow changed surface: JVM artefact, browser, bundle, tool, template, or skill structural tests. |
| PR CI | `.github/workflows/test.yml` | Always runs lockstep drift, skill/MCP drift, core JVM, CLJS node integration, JS harness self-tests, and docs link validation for docs PRs. Expensive jobs run only when conservative path filters say the owning surface changed. |
| Nightly/manual | `.github/workflows/expensive-tests.yml` | Runs the rigorous browser/examples/bundle matrix, Story/Causa gates, template emitted-app smoke, and live MCP conformance outside the PR critical path. |
| Release | `.github/workflows/release.yml` plus the latest green expensive workflow | Release keeps the core pre-release gate and should be cut only after the scheduled/manual expensive suite is green on the release candidate. |

## Local commands

| Command | Scope |
|---|---|
| `scripts/test-fast-pr.sh` | Fast PR spine: lockstep, skill/MCP drift, core JVM, JS harness self-tests, CLJS node integration. |
| `scripts/test-jvm-implementation.sh` | All implementation JVM artefacts, including adapter diagnostic classpath probes. |
| `scripts/test-jvm-tools.sh` | Tool JVM artefacts. |
| `scripts/test-rigorous-local.sh` | Fast spine + JVM coordinators + rigorous browser/bundle/examples/Story/Causa gates. Expensive; use before release-sized changes. |
| `cd implementation && npm run test:reagent-slim:bundle-isolation` | Adapter-owned Reagent Slim bundle-isolation invariant: slim advanced bundles exclude stock Reagent impl sentinels and `react-dom/server`, with a stock-Reagent positive control. |

Green output should stay quiet. Failures must name the violated contract, owning
surface, and reproduction command.

## Diagnostic / skip-ok gates

Some checks intentionally exit 0 when their preconditions are absent. They are
diagnostic, not required coverage:

| Gate | Why skip-ok |
|---|---|
| Adapter JVM classpath probes | React adapters are CLJS-only; the JVM run proves deps/classpath wiring and may have zero runnable tests. |
| `tools/causa` JVM probe | Causa can have no JVM-runnable tests on some intermediate cuts. |
| `tools/causa-mcp` MCP conformance | Placeholder until the server implementation lands. |
| Pair2 live-overflow without nREPL | The skip path is tested, but real live coverage is the hermetic/nightly `test:pair2-live-overflow-hermetic` run. |

Do not treat a skip-ok diagnostic as evidence that the underlying behaviour was
covered. The real coverage is the changed-surface, nightly/manual, or release
gate named in the table above.

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
