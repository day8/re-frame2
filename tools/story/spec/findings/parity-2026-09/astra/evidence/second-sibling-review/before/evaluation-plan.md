# Story evaluation protocol

Initial criteria recorded 2026-09-13 23:41 UTC (2026-09-14 09:41 Australia/Sydney), before current competitor scoring or reading sibling reports. Repository baseline: `98e8ffe9cb339295fe2fa459900d9d9647fab015`. Execution notes include sibling-driven follow-up at 2026-09-14 00:12 UTC. The initial ten jobs and their importance remain unchanged; added discriminating checks are identified below.

## Audience and interpretation

The primary audience builds a re-frame2 SPA. The comparison distinguishes a workshop newcomer who knows their application framework, a returning application/design-system maintainer, and an AI assistant collaborating with them. Knowledge gained by reading tool internals is an onboarding cost, not an assumed prerequisite.

Parity means completing the stated outcome through supported, discoverable routes at reasonable ceremony. It does not require identical UI or API nouns. A capability can exist while its quality, discoverability, or evidence is weak. Framework-native strengths are credited to Story only where Story exposes a useful workflow.

## Jobs and acceptance criteria

| ID | Job | Importance | Completion and discriminating observation |
|---|---|---|---|
| J01 | Install, discover, and iterate | Essential | Follow public setup to a rendered example, edit application code, observe an updated view, and identify each undocumented step. |
| J02 | Author and control reusable states | Essential | Register a simple component and two variants, change args through controls, reuse common context, and observe the expected state without duplicating application logic. |
| J03 | Exercise an asynchronous SPA journey | Essential | Establish loading, failure, retry, and success with controllable effects; increase fixture fidelity and state exactly which application behavior now runs. |
| J04 | Compare isolated states | Essential | Show two states simultaneously, interact/reset/switch, and verify that relevant state and late work do not cross variants. Identify boundaries outside frame-local isolation. |
| J05 | Navigate and inspect a useful state matrix | Important | Find a named state using search/keyboard, return to it, and compare relevant viewport/theme/locale or renderer combinations at a declared catalog size. |
| J06 | Reuse a visible scenario as a test | Essential | Run the same meaningful behavior through UI and appropriate automated runner, detect a deliberate fault, distinguish fail from unsupported execution, and retain regression coverage. |
| J07 | Diagnose and preserve a failure | Essential | Reach causal evidence from a visible failure, identify the triggering behavior, preserve a failing variant, correct the fault, and rerun without weakening assertions. |
| J08 | Review visual and accessibility results | Important | Detect an intentional visual/a11y change, identify the capture/comparison/review providers, and explain the limits of automated checks and the workshop's own accessibility. |
| J09 | Document and share a reproduction | Important | Read useful component/state docs and open a static/deep-linked reproduction in a fresh context; identify what state, setup, and external dependencies travel. |
| J10 | Complete the human/agent loop | Important | Discover, preview/run, read a failure, author a supported minimal scenario change, and verify the same artifact/results as the human UI. |

Extension, cleanup, production exclusion, build friction, and maintenance burden are assessed within the jobs they affect rather than inflated into separate cosmetic feature counts.

## Scoring and evidence

Capability status: complete for stated scope, partial, absent, intentional divergence, not applicable, or unknown. Evidence strength: exercised, source-traced, documented only, or unverified. Comparative advantages and discoverability are separate observations. Importance is ordinal, not an invented percentage weight. Essential failures and unknowns remain visible; minor successes cannot cancel them.

Primary comparator: Storybook 10.6.0, with ordinary documented integrations. Ladle 5.1.1, Histoire 1.0.0-beta.1 (latest stable 0.17.17), Portfolio 2026.03.1, and Lookbook 2.3.15 supply complementary authoring models. Date and version each source; separate official/core, community, hosted, and custom implementations. Alternative products were not executed locally.

## Execution plan

Use existing Story fixtures where possible. Execute a small meaningful subset of J01/J02, J03/J06, and J07 on Story and Storybook where the environment permits, including real rendered UI and deliberate failure controls. Source inspection covers breadth; execution supports consequential workflow claims. Record environment failures separately from product failures.

Record commands, absolute checkout roots, dependency versions, exit codes, skips, and artifact paths. Time a small sample only with explicit cold/warm state and sample count. Use unique ignored logs. Do not run an entire repository suite to compensate for an untested user journey.

## Environment and artifacts

All commands ran against `C:/Users/miket/code/re-frame2`, with no tracked production changes. Findings and fixtures are ignored local artifacts. Windows 11 Home 10.0.26200, Intel Core Ultra 9 275HX (24 logical processors), Node 24.13.0, Temurin Java 21.0.10, Playwright 1.59.1, and its Chromium 147.0.7727.15 were used. Browser journey screenshots use 1440×1000. Existing repository dependencies and compiler caches were present. This is one machine and exploratory samples, not a controlled speed comparison.

Story used `:examples/login-form` and its actual Reagent-hosted UI. The root launch URL returned HTTP 404; `/index.html?variant=story.login-form%2Ferror#/stories` worked. The compiler logged 760 files, 540 compiled, zero warnings, 28.57 seconds. Storybook used the [isolated React fixture](evidence/storybook-fixture/package.json) with its retained lockfile. Its package install reported 226 packages in 24 seconds. Both startup contexts differ too much to rank performance.

The fixture deliberately pins Vitest 4.1.11 to meet the released Storybook addon peer contract. npm latest Vitest 5.0.0 is not the compatible baseline. The addon now installs project annotations automatically; an obsolete extra setup file was removed after its diagnostic. [Version receipt](evidence/npm-baselines.json), [Story watch receipt](evidence/story-watch-process.json), [Storybook server receipt](evidence/storybook-dev-process-2.json).

## Executed observations

| Jobs | Experiment | Result and evidential boundary |
|---|---|---|
| J01, J02 | Compile login testbed; open real UI; edit heading control | Build and rendered edit succeeded. Public-root URL discrepancy observed. Clean setup and application-code HMR edit not performed. |
| J03, J04 | Open all five login states, retaining the edited error-variant heading | All five cards rendered, heading edit confined to its intended variant. Real state-machine events were used; request transport and late-response isolation were not exercised. |
| J06 | Run selected variant, then all variants in Test pane | Five passes, zero reported failures. The fixture asserts headless machine state; this is not proof of DOM-script CI parity. |
| J06, J07 | Public `story/run` false/true assertion and unsupported DOM action | False expectation failed, nil expectation passed, headless click returned `:cannot-run`. Raw results retained. |
| J02, J03 | UI upgrade text → reader → intended valid body → plan | Emitted snippet unreadable. Manually completed child still inherited pinned outputs; its fidelity remained hybrid. This isolates a handoff defect without claiming an additional pixel test. |
| J07 | Failing terminal assertion → actual promotion helpers → registered child → public run | Before failed; promoted child passed with empty assertions. Integration helper path, not a full dialog click. Source independently checked against dialog defaults. |
| J02, J03, J06, J07 | Storybook controls and asynchronous failed-login/retry/success story | Controls and retry journey passed in browser. Auth spy counted two calls. Interactions showed success and deliberate failure. The fixture mocks an async function, not the network. |
| J06, J07 | Storybook Vitest browser run, including deliberate false expectation | Two pass, one expected fail, zero pending; process exit **1**. Expected `Welcome, grace@example.com`, received `Welcome, ada@example.com`. A zero exit would be a failed negative control. |
| J08 | Story's own axe button and one-time opt-in; explicit subject scan afterwards | axe 4.10.0 loaded and completed. Zero violations, but the explicit scan returned eight color-contrast nodes as **incomplete**. Computed heading color is pale on white. This is not a clean accessibility verdict or an introduced-regression experiment. |
| J09 | Fresh browser navigates named variant URL | Correct named state loaded; no static build or arbitrary edited-state sharing tested. |
| J10 | Configured Pair discovery, then direct browser public API | Pair discovery returned `:nrepl-unreachable`, including after new server startup. Browser API worked. No supported end-to-end MCP authoring loop claimed. |
| J02, J07, J10 | Follow-up: Explain, resolve-args, run and real heading on the same registered variant | Explain returned empty args; resolver/run returned the parent heading, which the actual view rendered. Pure compiler and scenario-facing adapter have different context. |
| J07 | Follow-up: run a false terminal assertion in the Test UI, expand details, inspect evidence row | Correct fail and expected/actual values; evidence row retained a noninteractive placeholder. New temporary variant selected via the UI's state transition after in-memory registration; this is not a public authoring-discovery test. |
| J07 | Follow-up positive control: preserve an assertion already in the captured dispatch program | Before and promoted child both failed with the same expected 42/actual nil. This narrows the terminal-assertion loss finding. |

Receipts: [Story journeys](evidence/story-journeys.json), [runtime results](evidence/story-public-run.json), [transformation probes](evidence/story-roundtrip-probes.json), [axe and colors](evidence/story-a11y.json), [Storybook journeys](evidence/storybook-journeys.json), [Vitest results](evidence/storybook-tests-auto-annotations.json). The initial journey assertion expected the wrong headline, and early axe probes missed asynchronous consent rendering or overlapped the UI's own scan. These were research-harness mistakes, not product failures; corrected results are the linked final files.

The [sibling follow-up](evidence/sibling-followup.json) and [failed UI screenshot](evidence/story-failure-followup.png) were collected after reading Fable's matrix and Grok's report. An initial attempt to find a newly registered variant in the sidebar timed out; the corrected probe explicitly selected it through the same state transition used by the UI. No product conclusion is drawn from that harness discovery attempt.

Later-arriving Fable evidence includes a raw standalone JVM MCP loop, retained with attribution under [fable-mcp-receipt](evidence/fable-mcp-receipt/README.md). We inspected its requests and replies; we did not rerun it. It proves same-ID registration and result changes, but its “fix” edits the expected value to match the existing setup. It does not discharge J07's application repair or J10's full browser/agent loop.

The failure witnesses are deliberately false **assertions**, not an application bug introduced and repaired. They establish detection and failure-preservation behavior. The full J07 application-fault/correction loop remains an acceptance experiment below. Browser automation elapsed times include waits and locator work; they are not feedback-latency measurements.

## Reproduce the compact subset

Prerequisites: the recorded checkout/dependencies, a working JDK/CLJS toolchain, npm access if rebuilding the isolated fixture, and installed Playwright Chromium. Keep Story and Storybook in separate processes. Run the following from the repository root, using the absolute script paths. The source and lockfile of the fixture are the reproducibility contract; do not silently upgrade packages.

```powershell
# Story watcher: separate terminal/process, cwd must be implementation.
Set-Location C:/Users/miket/code/re-frame2/implementation
node C:/Users/miket/code/re-frame2/implementation/node_modules/shadow-cljs/cli/runner.js watch :examples/login-form

# Storybook: separate terminal/process.
Set-Location C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-fixture
npm ci --no-audit --no-fund
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-fixture/node_modules/storybook/dist/bin/dispatcher.js dev --port 6106 --ci --no-open --disable-telemetry

# Browser observations: repo root is required by these scratch scripts.
Set-Location C:/Users/miket/code/re-frame2
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/story-journeys.cjs
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/story-public-run.cjs
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/story-roundtrip-probes.cjs
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/story-a11y.cjs
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-journeys.cjs
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/sibling-followup.cjs
```

These scratch scripts are evidence collectors and can exit zero while recording a product defect. Read their JSON assertions/statuses. Preserve original evidence before rerunning because their JSON/screenshot output names are fixed. Temporary registrations exist only in the browser runtime. The scripts create fresh browser contexts; startup help and first-use axe opt-in are intentional steps.

For the automated Storybook control, the executed command was the fixture's local Vitest CLI with JSON reporting. This example uses fresh attempt **3** after the two retained research attempts; increment again on any subsequent run. The fixture config names its story project and the JSON names each actual story; verify those paths and test counts before accepting an exit code.

```powershell
Set-Location C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-fixture
node C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-fixture/node_modules/vitest/vitest.mjs run --reporter=json --outputFile=C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-tests-attempt-3.json > C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-tests-re-frame2-3.log 2>&1
$storyTestExit = $LASTEXITCODE
Set-Content C:/Users/miket/code/re-frame2/ai/findings/Story/astra/evidence/storybook-tests-re-frame2-3.exit $storyTestExit
```

Expected result remains **exit 1 with exactly the deliberate failure**. The retained successful investigation is [attempt 2 log](evidence/storybook-tests-re-frame2-2.log), [exit](evidence/storybook-tests-re-frame2-2.exit), and [JSON](evidence/storybook-tests-auto-annotations.json). This independent fixture is not a repository gate; no broad repository suite was run for ignored research documents. For future repository gates, use absolute paths and the required checkout banner or deliberate negative control, and retain unique ignored log/exit pairs.

## Completing the unexecuted portions

Each row is a bounded next experiment, not a new permanent gate or a task backlog. Use the original job's acceptance condition above. A partial row becomes complete only for the scope actually demonstrated.

| Jobs | Prerequisites and smallest next experiment | Failure signal / completion proof | Estimated effort |
|---|---|---|---|
| J01, J02 | Fresh application with framework knowledge but no Story-internal knowledge. Follow one public tutorial literally; add a simple view with two states and edit its code. Compare equivalent Storybook initialization. | Record every undocumented step and lookup. Both render and hot-update using the taught route. | 1–2 hours per tool, exploratory estimate. |
| J03, J04 | Equivalent request/retry fixtures: Story managed network and current MSW addon. Render two states, delay one response, switch/reset while it is pending. | Wrong-frame state, stale completion, or live unexpected I/O fails. Correct states and owned teardown observed in UI and runtime. | Half a day for both, estimate. |
| J05 | Existing Story budget fixtures and one realistic app catalog; one extra supported renderer/theme/viewport combination. | Verify actual budget runner/catalog counts; record keyboard discovery and rendering behavior. An 8 ms target without a run is not a result. | 1–3 hours once builds work, estimate. |
| J06, J07 | A real defect in retry handling with a DOM-driven script and meaningful assertion. Run from UI and browser CI, use Xray to diagnose, promote, fix app, then reintroduce fault. | Promoted artifact fails before fix, passes after, fails again with fault restored. No lost assertions or skipped DOM execution. | Half a day, estimate; promotion repair prerequisite for affected case. |
| J08 | Stable browser/font/viewport, committed baseline and existing screenshot runner, axe CI policy. Change visible color/layout and remove an accessible name. Keep a stable semantic comparison-case name when the content hash changes; also introduce a CSS-only change with declarations unchanged. | Pixel comparison fails in both cases; new content must not silently become an accepted baseline and an unchanged input hash must not skip capture. Axe reports intended violation; incomplete and manual keyboard/focus findings remain visible. Repeat clean control. | 2–4 hours, estimate. |
| J09 | Existing static rig with named variants/docs; local fresh-context HTTP server. | Built page, code/docs links, variant deep link and intended captured settings work without development runtime. Document dependencies that do not travel. | 1–2 hours, estimate. |
| J10 | Pair attached to the active browser build; standalone JVM if that job needs it; Storybook addon prerequisites. | Agent discovers, runs, reads the same failure, edits scenario via supported route, verifies it. Record host, frame, returned evidence and manual translations. | 1–3 hours after connection, estimate. |

## Cadence, cost and stopping rules

With servers already warm, the recorded browser observations and three small Storybook tests take seconds to minutes, with setup and inspection dominating. Reserve **10–15 minutes** for a smoke rerun and checking screenshots/JSON; this is a planning estimate, not an observed total benchmark time. A fresh environment adds dependency/compiler setup and may take longer. Do not turn this exploratory fixture into infrastructure merely to make a score.

Rerun J01/J02 when public setup, registration, controls or plan merging changes; J03/J04 for network, lifecycle or frame changes; J06/J07 for assertions, runner, promotion or evidence UI changes; J08 for rendering, accessibility or capture integration changes; J09 for docs/share/static changes; J10 for Pair/MCP or runtime-host changes. Revisit the affected comparator rows at a major integration change or before making a public parity claim. Preserve unaffected receipts and their revision pins.

Deterministic regression tests belong next to the repaired implementation: the round trip must retain the failing expectation and upgraded generated text must read/compile. Keyboard usability, clarity, recovery effort, and comparative ergonomics need a person. Existing budgets are useful workload contracts, not reasons to create an exhaustive new performance platform. Stop after the relevant outcome and deliberate failure control are demonstrated; broaden only for new uncertainty.

## Refinements after sibling review

The independent protocol snapshot and its [hash receipt](evidence/independent-draft-receipt.json) precede the sibling read. No job or importance was changed. Fable's material prompted the resolved-args agreement and failed-row navigation checks; the dispatched-assertion positive control challenged the scope of our promotion conclusion. Grok reinforced the use of existing gesture budgets and first-session outcomes. [Synthesis notes](evidence/sibling-synthesis.md) record what was adopted, rejected or left unverified.

For the proposed future workflows, record **manual re-entry, context changes, mandatory concepts, wrong turns, and preserved expectations**, alongside elapsed time. A file/config/command/gesture count can describe ceremony but is not a validated exchange rate between those activities. Do not deduct quality merely for a community integration or require the rubric to produce a predetermined Story win. The controls prove that the experiment detects a known fault; the comparative conclusion remains open.

The new visual-case recommendation follows from the comparison semantics: reference identity must survive the content change under review, while content hashes remain useful provenance. This is an acceptance refinement of J08, not an executed pixel-regression result. The practical advantage hypotheses and stopping rules are in the report's workflow table.

Fable's main report arrived before handoff and was read in full. Its proposed skip-on-unchanged-content-hash was checked against Story's snapshot tuple and rejected: CSS/view code/browser changes are not all represented. The added CSS-only negative control guards the intended outcome without requiring a new invalidation engine. Both owned research servers were stopped after the runtime experiments; [cleanup receipt](evidence/process-cleanup.json). The fixture/evidence files remain available for reruns.
