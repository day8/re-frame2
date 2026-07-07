# Testing policy

## The challenge

re-frame2 has many kinds of tests, and they have very different costs. Fast ones (CLJS unit, JVM unit, drift checks) take seconds. Expensive ones (browser feature matrices, MCP live conformance, template emitted-app smoke) take many minutes — sometimes a small multiple of that when several stack.

Running everything everywhere makes PRs slow and the dev loop painful. Skipping the expensive ones at the wrong time lets regressions through to release. **The whole point of this document is the system we use to manage that tradeoff.**

## Kinds of tests

This table is also the command catalogue: each kind names the command that runs it (npm scripts run from `implementation/` unless noted; full script definitions live in the `package.json` files).

| Kind / command | Cost | What it proves |
|---|---|---|
| **CLJS unit** — `test:cljs` | fast | Per-namespace CLJS logic across `implementation/*` + `tools/*` (every `*_cljs_test.cljs`, including the DOM-tagged suffix). The consolidated default gate. |
| **JVM unit** — per-artefact `clojure -M:test` | fast | CLJC logic + pure helpers per artefact; the adapter probes double as classpath/deps wiring checks. |
| **Security tier** — `test:security` (also rides `test:cljs`) | fast | Adversarial-property nets on redaction / escaping / egress boundaries: hostile-input corpus + generated shapes, each verified to go RED if the protection is reverted. |
| **Lockstep + drift** — fast-pr spine | fast | Version drift between coordinated artefacts; skill / MCP-server schema drift. |
| **JS harness self-tests** — `test:script-policy`, `test:script-helpers` | fast | The Node-side plumbing that runs everything else. |
| **Skills structural** — `skills-structural` job | fast | Skill manifests + shared content stay valid against the schema. |
| **Browser unit** — `test:browser` | medium | DOM-dependent tests only (`*_dom_cljs_test.cljs`): real React mounts, context tier, browser-only runtime features. |
| **Bundle isolation** — `test:bundle-isolation`, `test:reagent-slim:bundle-isolation`, `test:schemas-bundle` | medium | Production bundles leak no `tools/*` symbols; UIx/Helix are Reagent-free; Slim excludes stock-Reagent impl. |
| **Production elision** — `test:elision`, `test:browser-prod-elision`, `test:browser-schemas-boundary-prod` | medium | Non-negotiable: dev-time sentinels elided from release builds; schemas boundary warn-once contract. |
| **Adapter smokes + example compile** — `test:adapter-smokes`, `test:examples-compile` | medium | The three adapter testbed smokes (mount + dispatch + assert). `examples/` is test-free; the compile gate builds every declared `:examples/*` build and fails on any error or warning. |
| **MCP conformance, wire** — `test:mcp-conformance` (single entry point; per-suite scripts in `tools/mcp-conformance/`) | medium | Both servers honour the strict `CallToolResultSchema`; exec-safety helpers. |
| **docs/cljs playground** — `tools-playground` job | medium | The live-CLJS-cell engine: bundle smokes + freshness gates (byte-diff on deterministic bundles; smoke + structural validity on the SCI bundle). |
| **MCP conformance, live** — `test:re-frame2-pair-live-hermetic-suite` | expensive | Real re-frame2-pair-mcp against a real shadow-cljs nREPL: eval cap, subscribe lifecycle, redaction, isError, cofx, event metadata. |
| **Story gates** — `test:story-play-scripts` (PR smoke), `test:story-feature-load` + `test:story-static` (nightly) | expensive | Every variant's `:play-script` runs as a regression test; the full feature-load + static-export sweep runs nightly. |
| **Xray gates** — `test:xray-feature-gate:smoke` (PR), `test:xray-feature-gate` (nightly) | medium / expensive | The Xray feature matrix; smoke = highest-signal scenarios over few staged surfaces, nightly = all scenarios, all surfaces. |
| **Template emitted-app smoke** — `jvm-tools-template` job | expensive | The emitted app boots and passes its own gates, plus the `re-frame2-setup` day-one scaffold materialise-and-compile drift net. Behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`. |

## Test surfaces and their owners

Each surface has one owner and one purpose. Real bugs land in framework contracts, adapter wire-up, and tool lens behaviour — not in "this example mounted and clicked" — so coverage lives with the owner and examples stay clean.

| Surface | Owner / purpose | Tests? |
|---|---|---|
| `examples/` | Humans — learning + demonstration. | **No.** Test-free by ruling; the compile gate covers build health. |
| `implementation/adapters/<name>/testbed/` | Per-adapter smoke (exactly three). | One `spec.cjs` per adapter. |
| `testbeds/` | Framework feature-matrix substrates (Xray / Story / pair-mcp observation targets). | CLJS/JVM unit tests only — no per-testbed browser specs. |
| `tools/xray/testbeds/`, `tools/story/testbeds/` | Tool-owned scenario substrates. | Yes — the tool's feature gates drive them. |
| Per-artefact `clojure -M:test` | Contract protection (the spec is the artefact). | Unit, not smoke. |

Don't grow example-level specs, and don't overload examples with internal canaries.

## How we manage the cost

- **Cheap tests** run on every PR + every checkin. No conditional gating.
- **Expensive tests** run only when needed — gated by a **changed-surface classifier** on PR CI, by **full sweeps** nightly, and by **release gates** before tagging.
- **Expensive browser gates run in two tiers**: a high-signal **PR smoke** on the critical path, and the **full sweep** nightly. The nightly set is a strict superset, so the smoke never becomes the only coverage.
- **Per-tier scenarios** package these into named workflows so contributors know which gate they're in.

The classifier maps "what files changed" → "which expensive jobs fire." The full matrix runs nightly and on release candidates regardless of the diff. Skipping the wrong gate at the wrong time is the failure mode this system exists to prevent — **Placement decision dimensions** below is the frame for placing a new test.

## The 4 tier scenarios

1. **Agent pre-checkin** — `scripts/test-fast-pr.sh` plus the surface-specific commands for the files touched (find them in the Kinds table).
2. **PR CI** — `.github/workflows/test.yml`. Always: lockstep + skill/MCP drift, core JVM, CLJS node integration, JS harness self-tests, docs link validation on docs PRs. Expensive jobs fire only on their changed surface, and browser gates run their smoke tiers.
3. **Nightly / manual** — `.github/workflows/expensive-tests.yml`. The rigorous browser/bundle matrix, full Story/Xray sweeps, template smoke, live MCP conformance.
4. **Release** — `.github/workflows/release.yml` plus the latest green expensive run on the release candidate.

**The Story/Xray two-tier split.** The `story-xray-browser` PR job runs `test:xray-feature-gate:smoke` + `test:story-play-scripts`; the full sweep runs nightly. The dominant cost is testbed compilation, so both tiers share a keyed shadow-cljs compile cache that any source change busts. When adding an Xray scenario, tag it `smoke: true` (in `tools/xray/testbeds/feature_matrix/scenarios.cjs`) only if it earns a slot on every PR **and** loads an already-staged smoke surface; everything else is nightly by default. The gate fails loud if the smoke set is ever empty.

## Test authoring policy

**New tests register and carry an explicit frame** — `{:frame …}` on dispatch/subscribe or a `with-frame` scope, per Spec 002 §Frame target resolution: frame identity is *carried, not found*. The `ensure-default-frame!` fixture is retained solely for the older suites that already use it and is **not an idiom to copy**; conformance fixtures must never dispatch framelessly expecting default stamping.

**Story-as-test runs headless first.** Variants and inline plans run through the `:headless` runner (fast, JVM/node, no browser) for state/effect/schema/trace assertions — consistent with the CLJS-unit-tests-not-Playwright default; reach for a browser-tier assertion only when it genuinely needs browser behaviour. Every gate that runs plans defines a `:cannot-run` policy (fail / inconclusive / route to a richer runner); the headless default reports browser-only assertions as **inconclusive with a count**, never a silent pass. The dedicated capability-routed (:pixels / :a11y-engine) browser-tier gate itself stays deferred (rf2-315cf) until a compound first-of trigger fires: the first committed authored variant/inline plan that intentionally carries a browser-only assertion wires a nightly gate in `expensive-tests.yml`, or the first genuine visual/a11y regression that the structural check + existing Story/Xray browser gates miss escalates it to a changed-surface PR gate (rf2-a0t1z5). Failed runs can emit a replayable `:rf.test/run-artifact`. The authority is [`tools/story/spec/017-Testing-Story.md`](tools/story/spec/017-Testing-Story.md) §CI policy.

## Local commands

| Command | Scope |
|---|---|
| `scripts/test-fast-pr.sh` | The fast PR spine: lockstep, skill/MCP drift, core JVM, JS harness self-tests, CLJS node integration. |
| `scripts/test-jvm-implementation.sh` | All implementation JVM artefacts (including adapter probes). |
| `scripts/test-jvm-tools.sh` | Tool JVM artefacts. |
| `scripts/test-rigorous-local.sh` | Local mirror of the nightly sweep (spine + JVM + browser/bundle/Story/Xray + examples-compile; inventory pinned by a self-test). Use before release-sized changes. |

Everything narrower is named in the **Kinds of tests** table; the full script catalogues are `implementation/package.json` and the per-tool `tools/*/package.json`. Per-artefact JVM suites run from the artefact directory: `cd implementation/<name> && clojure -M:test` (same under `tools/`).

Green output stays quiet; red must name the violated contract, owning surface, and reproduction command — see [`docs/quiet-tests.md`](docs/quiet-tests.md).

**Windows-local policy.** CI (Linux) is authoritative for the full JVM suite; the core gate is not split or weakened, and a Windows-local hang is a *file-lock* symptom (a stale orphaned shadow/Node/JVM holding `implementation/` or `out/`), not a correctness signal. When it happens: `scripts/test-core-jvm-windows.ps1` (bounded run; its timeout dump proves the lock-holder), `scripts/reap-stale-test-processes.ps1` (guarded reaper, dry-run by default), `scripts/test-jvm-nses-windows.ps1` (per-namespace sharding to localise a hang) — then push and let CI run the authoritative suite.

## Changed-surface classifier

The classifier is the **source of truth for "which jobs run when"** on a PR: the script [`.github/scripts/report-changed-surfaces.sh`](.github/scripts/report-changed-surfaces.sh) reads the diff and emits boolean outputs; every conditional job in [`.github/workflows/test.yml`](.github/workflows/test.yml) gates on one via `if: needs.detect_changed_surfaces.outputs.<surface> == 'true'`.

| Output | Fires when … | Gates |
|---|---|---|
| `implementation_jvm` | any JVM artefact under `implementation/` changes | the per-artefact JVM suites + src-less conformance tiers + `jvm-security` |
| `adapter_diagnostic` | an adapter artefact changes | the skip-ok classpath probes |
| `cljs_node_test` | anything compiled into `:node-test` changes (core, adapters, feature artefacts, conformance fixtures, build config + scripts, story/xray src+test CLJS, machines-viz) | the consolidated `cljs` job |
| `cljs_browser` | a `:browser-test`-covered surface changes (incl. machines-viz DOM suites) | the Playwright `cljs-browser` job |
| `cljs_prod` | a release-probe-covered surface changes | the prod-elision / schemas-boundary probes |
| `bundle_isolation` | a bundle-boundary surface changes (adapters, build scripts, probe examples, package metadata) | `bundle-isolation` |
| `reagent_slim_bundle` | the Slim adapter, its example, or its check script changes | `reagent-slim-bundle-isolation` |
| `adapter_testbed_smokes` | an adapter surface or the smoke orchestrator/manifest scripts change — **not** generic `examples/**`, `testbeds/**`, or core (the smokes catch adapter-mount bugs, not core regressions) | `adapter-testbed-smokes` |
| `tools_jvm` | story / xray / story-mcp / re-frame2-pair-mcp / mcp-base change | the per-tool JVM probes |
| `template_expensive` | `tools/template/*` changes | the emitted-app smoke |
| `mcp_conformance` | any MCP server, mcp-base, or mcp-conformance changes | the MCP conformance jobs |
| `mcp_live` | re-frame2-pair-mcp / mcp-base / mcp-conformance change | the live + hermetic MCP job |
| `story_xray_browser` | runtime-extension files under `tools/{story,xray}/{src,testbeds}` change — specs, JVM tests, deps.edn, READMEs do **not** fire it (they can't affect chrome); core doesn't either | the PR-smoke browser job |
| `skills_structural` | `skills/re-frame2-pair/*`, `skills/re-frame2-setup/*`, or `skills/shared/*` change | `skills-structural` |
| `playground` | the playground tool, a committed playground bundle, or `implementation/machines/*` changes (the SCI bundle bakes the machines artefact in) | `tools-playground` |

**Blast radius**: a change to either workflow file, the classifier script, or `TESTING.md` sets every output `true` — anything that re-tiers the matrix must re-run the matrix. `implementation/core/*` fans out to almost everything (core regressions can break every downstream invariant), deliberately excepting the two Playwright gates noted above.

**Adding a new artefact directory needs two matching changes**: a classifier rule (which outputs the path lights) and a workflow gate whose `if:` reads them. Either side missing is a silent hole — code can land that mutates a surface no PR-time job watches. When in doubt, over-classify — but set only the outputs whose jobs the artefact's tests actually exercise, since coarse rules clutter the matrix with `skipping` entries.

To see exactly what a diff fires, run the script — it is executable truth, and takes `--all` or an explicit path list:

```
.github/scripts/report-changed-surfaces.sh implementation/core/src/foo.cljs
.github/scripts/report-changed-surfaces.sh --all
```

## Diagnostic / skip-ok gates

Some checks intentionally exit 0 when their preconditions are absent. They are diagnostic, not coverage.

| Gate | Why skip-ok |
|---|---|
| Adapter JVM classpath probes (`jvm-{reagent,reagent-slim,uix,helix}`) | Adapter namespaces are `:cljs-only`; the probe proves deps + classpath wiring even with a zero-test alias. Real coverage: the adapter smokes + per-adapter CLJS unit tests. |
| Pair live-overflow without nREPL (`mcp-conformance-re-frame2-pair`) | Exits 0 with a SKIP marker when `$SHADOW_CLJS_NREPL_PORT` is unset — so the SKIP path itself is exercised every run. Real coverage: the hermetic-suite step that follows. |

Never treat a passing skip-ok diagnostic as evidence the behaviour was covered; the real coverage is the changed-surface, nightly, or release gate.

## Per-tool coverage matrices

The per-tool spec trees carry auditable feature-coverage matrices pinning every user-visible behaviour to a gate: [`tools/story/spec/015-Test-Coverage.md`](tools/story/spec/015-Test-Coverage.md) (driven by `test:story-feature-load`) and [`tools/xray/spec/017-Test-Coverage-Matrix.md`](tools/xray/spec/017-Test-Coverage-Matrix.md) (driven by `test:xray-feature-gate`). TESTING.md governs the meta-policy; the matrices govern per-feature contracts, with the nightly full sweep as the system of record. A `covered` row gated by its command is real; a `partial` or `missing` row is the owning team's backlog.

## Placement decision dimensions

Do not classify a test on one axis — a slow test is not automatically optional, and an essential test is not automatically global PR CI. Combine:

| Dimension | Question | Implication |
|---|---|---|
| Scenario | Pre-checkin, PR CI, nightly, or release? | A test can be mandatory at checkin and release without running on every unrelated PR. |
| Speed | Measured wall-clock, including setup? | Fast high-signal → PR CI. Slow → changed-surface, local, nightly, or release. |
| Essentiality | What invariant does it protect? | Essential invariants must be covered somewhere mandatory — not necessarily always-on. |
| Changed surface | Which files can plausibly break it? | Select tests from the diff's impact radius, not a fixed suite. |
| Dependency fan-out | Is the change upstream of other surfaces? | Core and shared substrate fan out broadly; leaf changes run their owning gates. |
| Unique signal | What would this catch that cheaper tests miss? | Keep expensive tests with distinct signal; demote duplicates. |
| Fixture role | Example, adapter smoke, or tool testbed? | Coverage lives with the owner; examples carry no canaries. |
| Naming | Does the name reveal the policy role? | A `:diagnostic` / skip-ok command must not read as real coverage. |
| Skip semantics | Can it exit 0 with prerequisites absent? | Document as skip-ok; never count a skip as coverage. |
| Failure quality | Is a red actionable from CI logs? | Red names the contract, owning surface, and repro command. |
| Security boundary | Does it touch redaction / escaping / egress? | Gets an **adversarial-property** net in the `security/` tier — hostile corpus + generated shapes, verified to go RED on revert. Extend the matching security namespace when touching such a boundary. |

Before adding any new Playwright, bundle, MCP, Story, or Xray gate to PR CI: prefer one small fixture that proves the owning contract over full-app sweeps; a minimal counter is enough for an adapter smoke; if the timing or unique signal is unknown, measure first.

| Test kind | Typical placement |
|---|---|
| Fast essential | Every PR/checkin. |
| Fast nice-to-have | Usually every PR unless noisy or duplicative. |
| Slow essential | Pre-checkin for relevant changes, changed-surface PR CI, nightly, release. |
| Slow nice-to-have | Local, nightly, or release — off the PR critical path. |

## Spec-impl-pair convention

Top-level `spec/*.md` has **no classifier rule**: a spec-only PR runs only the always-on jobs. This is intentional — a blanket `spec/* → mark_all` would re-run the rigorous matrix on every typo fix. The expected pattern is the **spec-impl-pair convention**: a spec change ships in the same PR as the impl/test change realising it, so the impl edit fires the right surface and the spec rides along. Spec-only PRs are fine for pure normative refinements; if a spec change implies any implementation change — even a test — pair them, and if reviewers want the rigorous matrix against a spec-only PR, run `expensive-tests.yml` before merge. First regression through this gap triggers a surgical per-spec-file rule, not a blanket one.
