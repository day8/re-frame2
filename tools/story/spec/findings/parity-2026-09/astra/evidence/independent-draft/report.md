# Story: parity, differentiation, and the next useful improvements

Independent draft completed 2026-09-14 10:00 Australia/Sydney / 2026-09-14 00:00 UTC. Repository examined: `98e8ffe9cb339295fe2fa459900d9d9647fab015`. Research task: `rf2-ax6vt`. This draft precedes the requested review of sibling reports; that synthesis will be recorded separately.

## Assessment

Story has the foundations of an unusually useful re-frame2 application-state workshop. Its controls, named states, workspace grids, assertions, application events, and Xray inspection are real capabilities. The shipped login example rendered all five states together, live controls changed the actual view, and the Test pane ran all five variants successfully. Public `story/run` distinguished a failing expectation, a passing expectation, and a DOM operation that a headless runner could not perform. These observations support a substantial implemented product, not just a design. [Browser journeys](evidence/story-journeys.json), [runtime witnesses](evidence/story-public-run.json).

**Broad feature coverage is ahead of completed workflow quality.** General Storybook parity is not yet a defensible conclusion. Two transitions central to Story's proposed advantage failed targeted checks: increasing a fixture's fidelity and preserving a discovered failure as a regression. Visual regression remains an integration outcome to demonstrate, and the browser/agent loop has an important host boundary. Setup documentation and the first rendered experience also need attention. A percentage would conceal these differences; the [matrix](parity-matrix.md) and [evaluation protocol](evaluation-plan.md) retain essential gaps and unknowns explicitly.

The right product thesis is: **Story should be the easiest place for a re-frame2 programmer to construct, compare, explain, and retain realistic SPA states, with the same executable artifact available to their AI assistant.** Its advantage should be fewer translations between these activities. Data-shaped examples, side-by-side rendering, network mocking, test reuse, and MCP presence individually do not establish superiority.

The first improvement should make **failure → regression** preserve the failure. In the measured case, an authored terminal assertion failed before promotion and disappeared afterwards, allowing the promoted variant to pass. Correct that transformation at the promotion boundary, then repair the fidelity-upgrade scaffold. These are small improvements to an existing model; neither calls for another runner, debugger, registry, or artifact format. [Round-trip evidence](evidence/story-roundtrip-probes.json).

## The baseline has moved

Storybook **10.6.0**, released September 2, is the current stable baseline verified independently through npm and GitHub. Version 11 was prerelease during this assessment. The executable fixture used 10.6.0, React 19.3.0, Vite 8.3.0, and compatible Vitest 4.1.11. Selecting compatible versions matters: npm's latest Vitest was 5.0.0, while the released Storybook test addon declared support for 3 or 4. [Official release](https://github.com/storybookjs/storybook/releases/tag/v10.6.0), [addon metadata](https://registry.npmjs.org/@storybook/addon-vitest/10.6.0), [recorded dependency baseline](evidence/npm-baselines.json).

Storybook already makes stories into browser tests through its official Vitest addon. In this research, the same CSF3 story exercised a failed login, corrected the password, retried, and asserted the welcome state. The Interactions pane displayed the authored steps. Its automated runner reported two passing stories and the deliberately failing control, with zero pending tests; the expected nonzero exit was retained. This was a real browser interaction test against an asynchronous function fixture, not a live backend or an MSW transport experiment. [Fixture](evidence/storybook-fixture/src/Login.stories.tsx), [browser observations](evidence/storybook-journeys.json), [automated results](evidence/storybook-tests-auto-annotations.json), [official test integration](https://storybook.js.org/docs/writing-tests/integrations/vitest-addon).

Storybook also has an official MCP addon. Its current surface includes documentation, previews, affected-story discovery, and test execution; local agentic review is experimental. Testing depends on the Vitest integration, and documentation depends on supported manifests. Story's agent advantage therefore needs to be demonstrated through application-state evidence and a better handoff, not inferred from having an MCP server. [MCP overview](https://storybook.js.org/docs/ai/mcp/overview), [release-pinned API](https://github.com/storybookjs/storybook/blob/a77777356be2aeaff89d7a2b25254db7b2318392/docs/ai/mcp/api.mdx), [agentic review](https://storybook.js.org/docs/ai/agentic-review).

Four complementary alternatives sharpen the comparison:

| Tool | Baseline | Lesson for Story |
|---|---|---|
| Ladle | 5.1.1 | A small React workshop can bundle useful network mocking and accessibility support, expose simple automation metadata, and document pixel tests without owning a hosted review service. |
| Histoire | npm latest 1.0.0-beta.1; latest non-prerelease 0.17.17 | Framework-native authoring, variant grids, and copyable source that follows current controls can make exploration easier to retain. Distinguish beta documentation from stable-release status. |
| Portfolio | 2026.03.1 | ClojureScript users already have concise scene definitions, stateful atoms, REPL-driven exploration, and comparison panes. Story should remain pleasant for a single component. |
| Lookbook | 2.3.15; docs banner still 2.3.14 | Reusing ordinary application preview methods and their framework's tests can be more ergonomic than inventing a workshop-specific subsystem. |

Sources: [Ladle release](https://github.com/tajo/ladle/releases/tag/%40ladle/react%405.1.1), [Histoire releases](https://github.com/histoire-dev/histoire/releases), [Portfolio release](https://github.com/cjohansen/portfolio/tree/v2026.03.1), [Lookbook release](https://github.com/lookbook-hq/lookbook/releases/tag/v2.3.15). Their capabilities were researched in current primary documentation and selected released source, not exercised locally. Detailed outcome comparisons and limitations are in the matrix.

Devcards and Workspaces establish the ClojureScript lineage of simultaneous states, enduring development examples, and UI/CI test reuse. Story can improve their integration without claiming those ideas as inventions. [Devcards](https://github.com/bhauman/devcards), [Workspaces](https://github.com/nubank/workspaces).

## What the executable comparison established

### The ordinary workshop is present

At 1440×1000 in Chromium 147.0.7727.15, Story's login example rendered idle, submitting, error, retry-submitting, and authenticated states in one workspace. A heading edit on the error variant remained confined to that variant when the grid appeared. This is useful evidence of a real multi-state workflow. It does not prove all cleanup, same-ID repetition, CSS, portals, timers, or global effects are isolated. [Workspace capture](evidence/story-workspace.png), [observations](evidence/story-journeys.json).

Storybook's inferred heading control also updated the rendered component. Its retry story and its deliberate failure were visible in the browser, including expected and received values in Interactions. These are credible baseline ergonomics. Story's opportunity is to connect that kind of failure display directly to the re-frame event, subscription, machine, and effect evidence already visible in Xray. [Story controls](evidence/story-controls.png), [Storybook controls](evidence/storybook-controls.png), [Storybook failure](evidence/storybook-failure.png).

This was not a clean-machine installation study or a controlled performance race. Story ran the repository's compiled testbed with existing dependencies; Storybook used a small manually configured fixture. The Story compiler reported 760 files, 540 compiled, zero warnings, and 28.57 seconds. Storybook's install reported 226 packages in 24 seconds, and its dev server reported separate manager/preview startup times. Those tasks and cache conditions are different; ranking the tools by those numbers would be misleading. The protocol retains commands and distinguishes observed waits from performance claims.

### The result model has meaningful distinctions

The public runtime returned `:fail` for `[:rf.assert/path-equals [:missing] 42]`, `:pass` for the corresponding nil expectation, and `:cannot-run` for a DOM click under `:headless`. This supports Story's claim that unsupported execution need not masquerade as a successful test. The result also contains substantial epoch/narrative evidence. The next product step is making that information concise and actionable at the point of failure. [Run witnesses](evidence/story-public-run.json), [public API](../../../../tools/story/src/re_frame/story.cljc).

The login testbed's states use real machine events and effect overrides, but some states explicitly dispatch response events to land at a chosen state. This proves the relevant state transition and view, not HTTP serialization, a production server, or arbitrary application I/O. The Storybook fixture similarly stubs its asynchronous authentication function. A stronger transport comparison would use Story's managed network fixtures and the current MSW integration on equivalent request/retry behavior. That additional experiment is specified rather than claimed as executed. [Login declarations](../../../../tools/story/testbeds/login_form/stories.cljs), [Story network fixtures](../../../../tools/story/src/re_frame/story/network.cljc), [Storybook network mocking](https://storybook.js.org/docs/writing-stories/mocking-data-and-modules/mocking-network-requests).

### Failure promotion can discard the reason the test failed

The decisive fixture registered a variant that dispatches ordinary login behavior and declares a false terminal assertion. `story/run` returned `:fail`. The probe then called the browser-loaded UI's `result->artifact`, the promotion body's actual builder, and the registrar with the dialog's normal inheritance choice. The generated variant retained its script and source link but no terminal assertion. Rerunning returned `:pass` with `:assertions []`; the expected condition had not become true. [Executable probe](evidence/story-roundtrip-probes.cjs), [result](evidence/story-roundtrip-probes.json).

This is an integration-level reproduction through the same helper functions, not a recorded click through the promotion dialog. Independent source review confirmed that the normal dialog defaults are equivalent for this case. The distinction matters, but does not rescue the generated test: the false expectation is lost by the transformation itself. [UI adapter](../../../../tools/story/src/re_frame/story/ui/promotion.cljc), [body construction](../../../../tools/story/src/re_frame/story/promotion.cljc), [Test-mode event extraction](../../../../tools/story/src/re_frame/story/ui/test_mode/state.cljs).

Ordinary terminal assertions deliberately do not inherit through `:extends`. That is a coherent reuse rule. Promotion should explicitly preserve the applicable declarative expectations, rather than changing inheritance globally or copying old result records as if they were new assertions. Acceptance should reintroduce the original fault and require the promoted variant to fail. DOM-driven scenarios also need a representative preservation test because flat event extraction is not automatically equivalent to retaining clicks, typing, waits, and assertions.

### The fidelity upgrade is a good idea with an incomplete handoff

The generated real-setup snippet failed `cljs.reader/read-string`: an inline comment consumes its closing delimiters. After constructing the intended completed body with valid syntax, the compiled plan still retained the parent's subscription override. The source had only `#{:sub-overrides}` fidelity; the child had `#{:real-setup :sub-overrides}` and still pinned `[:login/email]` to `"PINNED"`. The claim established is about the emitted text and compiled plan, not a separate rendered-pixel comparison. [Probe and before/after plans](evidence/story-roundtrip-probes.json), [upgrade helper](../../../../tools/story/src/re_frame/story/ui/view_state.cljc).

The smallest useful repair is a readable scaffold that actually removes the pinned picture while retaining the necessary context, or a clear recipe for editing the original declaration. A new generalized deletion/merge API is unnecessary unless a broader real use case requires it. Test the completed generated artifact, not merely the presence of `:setup` in the snippet.

### Isolation and visual proof need precise boundaries

Story's frame-local state and effects are valuable. Workspace cells nevertheless share a browser document. An iframe can provide a different DOM/CSS boundary, but does not solve every application-global or external effect issue either. Storybook's inline docs stories and network fixtures have their own shared-state considerations. Evaluate the boundary required by the job rather than declaring either isolation architecture universally superior. [Story workspace](../../../../tools/story/src/re_frame/story/ui/workspace.cljc), [Storybook inline/iframe documentation](https://storybook.js.org/docs/api/doc-blocks/doc-block-story).

Story's snapshot identity fingerprints declared inputs. Its inspected visual assertion compares content hashes, while the broader result requirements separately demand pixel evidence. It is therefore wrong both to call the fingerprint a finished visual regression system and to infer a false passing full run from the local comparator alone. Behavioral golden slices are useful but answer a different question from pixel diffs and review. [Visual evaluator](../../../../tools/story/src/re_frame/story/play/browser.cljc), [requirements](../../../../tools/story/src/re_frame/story/requirements.cljc), [goldens](../../../../tools/story/src/re_frame/story/golden.cljc).

Story's own axe scan loaded and completed after the first-use CDN opt-in. It reported no violations. An explicit subsequent scan of the login card returned eight color-contrast nodes as **incomplete**, while computed styles showed pale `rgb(237,235,230)` heading text on a white card. The screenshot also makes the readability problem visible. This is an example/style interaction and a reason for human review, not a full workshop accessibility verdict. “No reported violations” should never absorb incomplete findings into a clean bill of health. [Axe/colors receipt](evidence/story-a11y.json), [rendered example](evidence/story-controls.png).

Ladle documents a compact Playwright capture/compare workflow. Histoire's screenshot plugin, despite its label, only captures files; its Lost Pixel and Percy integrations provide comparison routes. Story can close this outcome through an existing browser harness with explicit baseline and review ownership. A first-party hosted visual service would add far more responsibility than this gap requires. [Ladle recipe](https://ladle.dev/docs/visual-snapshots/), [Histoire Lost Pixel](https://histoire.dev/examples/visual-regression-testing/lost-pixel), [Histoire screenshot source](https://github.com/histoire-dev/histoire/blob/f04001937b86d330d1b8df3483adbad4bfbcc57c/packages/histoire-plugin-screenshot/src/index.ts).

## What changed since the earlier research

The May audit's aggregate score should be retired as a current claim. Its definitions mix implemented outcomes, possible uses of primitives, and subjective comparative judgments. Several concrete gaps have since closed. The September correctness review also predates meaningful repairs; importing its defect list would create false recommendations. [Earlier parity audit](../../../../tools/story/spec/Feature-Parity-Audit.md), [earlier correctness review](../../story-review.md).

| Earlier concern | Current evidence and implication |
|---|---|
| Missing global decorators | Global configuration and `reg-global-decorator` now exist; do not build another global-context layer. |
| Missing loader teardown | Lifecycle and owned teardown exist, including repairs recorded in `rf2-gwye.5/.6/.7`; verify the integration needed by a scenario. |
| Missing parent/component docs rollup | Current Docs rendering and sidebar parent navigation include it. Improve discoverability if necessary. |
| Mandatory vocabulary bootstrap | First registration installs the canonical vocabulary automatically. |
| Missing test tutorials / old script spelling | Unit/browser tutorials exist; `:setup` and `:script` are current, and `:play-script` is retired. |
| Empty checks or no-adapter false success | Current source and closed beads `rf2-b2mt`, `rf2-jjhy`, and `rf2-poty` contain repairs. The present runtime failure controls work. |
| Reruns inherit old facts; debugger runs a different program | Source repairs exist under `rf2-3okc`, `rf2-499z`, and `rf2-ad25`. These historical bugs are not recommendations here. |
| Inert network fixtures | `rf2-shx4` includes a further explicit-image repair; current public-runtime tests cover that case. |

Current implementation references: [registration/API](../../../../tools/story/src/re_frame/story.cljc), [runtime](../../../../tools/story/src/re_frame/story/runtime.cljc), [plan](../../../../tools/story/src/re_frame/story/plan.cljc), [component docs](../../../../tools/story/src/re_frame/story/ui/docs.cljc), [network runtime tests](../../../../tools/story/test/re_frame/story/network_runtime_test.clj). Bead status was read live and consequential repairs checked at source; not every referenced test was executed in this research.

There is still a concrete front-door discrepancy: the tool README passes a function as `:component`, while current registration validation requires a keyword view ID. The main tutorial teaches the keyword correctly. A programmer should not need to determine which entry document is current by reading the registrar. [README](../../../../tools/story/README.md), [schema](../../../../tools/story/src/re_frame/story/schemas.cljc), [registrar](../../../../tools/story/src/re_frame/story/registrar.cljc).

## The most useful ways to be better

**Make an explored state progressively more meaningful.** A programmer begins with a cheap pinned picture, gives it a name, replaces the pin with real setup events, and adds a behavior check. Controls, documentation, and identity remain useful throughout. Story already has most of this machinery. The advantage is established when the transformation preserves intent and clearly increases what has been tested—not when the UI merely changes a fidelity label.

**Make the path from failure to regression short and trustworthy.** A failed retry should reveal the responsible event/effect/machine transition, offer a minimal reproducible scenario, and retain the expectation that exposed the fault. The programmer fixes their application and sees that scenario pass. Their assistant can inspect the same evidence. Xray and the plan/result model make this credible; the measured promotion defect identifies a small missing link. Storybook plus MSW and ordinary framework DevTools is the fair comparison, and should remain in the evaluation.

**Keep the first component simple while making realistic SPA work additive.** Portfolio's concise scene and Ladle's ordinary component exports set a useful minimum. A basic Story author should need a view ID, useful args, and a variant—not an early lesson in every registry, frame, trace, and runner option. Richer setup can appear when the job demands it. The value of existing primitives increases when their names and defaults disappear from the common path.

**Give the assistant the same useful room the programmer occupies.** The standalone Story-MCP server runs in its own JVM; it is not the live browser's heap. The browser route uses the Pair runtime and the public Story functions. In this session the configured Pair connector could not reach the newly started nREPL; direct browser calls were used for the runtime probes instead. That is an environment limitation, not evidence that the supported Pair route is absent. A complete first-use recipe should make the host distinction and frame ownership unmistakable. [Story-MCP README](../../../../tools/story-mcp/README.md), [browser Story recipe](../../../../skills/re-frame2-pair/references/stories.md).

## Recommended sequence

| Order | Recommendation | Jobs | Smallest coherent change and acceptance |
|---|---|---|---|
| 1 | Preserve the failure through promotion | J06, J07, J10 | Carry the applicable authored expectations and behavior into the generated declaration. The original false expectation remains false and the promoted variant still fails; correcting the fault makes it pass. Focus on the existing promotion/UI adapter. Small-to-medium effort. |
| 2 | Complete the fidelity upgrade handoff | J02, J03 | Repair snippet delimiters and generate an upgrade that actually excludes inherited pinned outputs. Read/compile the emitted declaration and verify its intended fidelity and behavior. Small effort. |
| 3 | Provide one current, executable front door | J01, J02, J06, J10 | Align README/tutorial/API examples around view IDs and `run/is/explain`; document the exact launch URL and separate JVM/browser agent routes. Walk component → async state → meaningful failure from public docs. Small-to-medium effort. |
| 4 | Publish a real pixel/a11y integration recipe | J08, J09 | Reuse Playwright/static/deep-link hooks, explicit baselines, and appropriate axe settings. Introduce a visual and accessibility fault and demonstrate actual detection. No new hosted service. Medium effort, depending on existing harness fit. |
| 5 | Make evaluation part of the next workflow changes | All | Retain the ten-job protocol, cheap smoke subset, and evidence invalidation rules. Reuse existing budget fixtures and perform targeted human checks. Avoid a permanent gate for every judgment. Small documentation/fixture effort. |

These sizes are estimates, not implementation plans. The first two defects were not found under an existing live owner in the bounded bead search; the report records them without creating a feature backlog. Existing publication work remains operator-owned under `rf2-pv7p` and is not authorized by this research.

Do not build an addon marketplace, another debugger beside Xray, another scenario encoding, a separate statechart engine, a hosted approval platform, or a universal inheritance-removal mechanism to solve these findings. Do not add mandatory ceremony to prevent every possible misuse. Finish the few transformations that make the existing model more productive.

## Bounds of the conclusion

The comparison establishes specific browser and runtime outcomes, current source paths, and current competitor documentation. It does not establish full cross-renderer parity, clean-machine onboarding time, large-catalog latency, mobile/screen-reader usability, whole-document isolation, hosted visual review, or an end-to-end connected MCP authoring session. Those claims remain bounded or unknown in the matrix. No sibling report informed this independent draft. No production feature changes were made.
