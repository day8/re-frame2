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
