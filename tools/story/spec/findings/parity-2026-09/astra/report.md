# Story: parity, differentiation, and the next useful improvements

Revised 2026-09-14 after a second sibling review. **Current source assessment:** `35e3c00fb99fc7b646e142edaa70bc4ab7599c5f`. **Original browser comparison:** `98e8ffe9cb339295fe2fa459900d9d9647fab015`. These are different evidence baselines. Research tasks: `rf2-ax6vt`, then `rf2-ow0ci`. The [previous report](evidence/second-sibling-review/before/report.md) and original receipts remain intact. The [second review record](evidence/second-sibling-review.md) records corrections, convergence and verification.

## Assessment

**Story has substantial workshop capability, and the main correctness and onboarding defects identified by this research have now been repaired. Broad Storybook parity and superior ergonomics still need workflow evidence.** The next useful work is to demonstrate the repaired journeys, then close the remaining visual-review, sharing and human/agent gaps with existing tools. Repeating the original defect backlog would misdirect effort.

The original browser comparison showed five login states together, live controls changing the actual view, all five variants passing their authored state checks, and meaningful pass/fail/cannot-run distinctions. It also exposed broken failure promotion and fidelity-upgrade handoffs. Since then, promotion, upgrade generation, installation guidance, the example schema, resolved-input projections and failure navigation have been fixed. In this second review, **64 existing tests with 422 assertions passed** across six relevant JVM namespaces. That directly verifies the covered transformations and input/result contracts; it is not a fresh browser walkthrough or clean-consumer installation. [Original browser observations](evidence/story-journeys.json), [current test log](evidence/second-sibling-review/current-regressions-re-frame2-2.log), [result](evidence/second-sibling-review/current-regressions-result.edn).

The product thesis remains: **Story should be the easiest place for a re-frame2 programmer to construct, compare, explain and retain realistic SPA states, with the same executable artifact available to their AI assistant.** The advantage should be fewer translations between these activities. Data-shaped examples, grids, network mocking, test reuse and MCP presence each have credible counterparts elsewhere.

This fits the five concepts in [North Star §3.1](../../../../tools/story/spec/018-Story-UI-North-Star.md#31-storybook-parity-and-surpass): variant, inputs, script, expectations, evidence. Keep that vocabulary small. Make ordinary component work easy and realistic SPA work additive. Schemas remain optional; direct I/O and unusual application behavior remain under the programmer's control.

The [matrix](parity-matrix.md) retains six essential and four important jobs. Most are partial verification, which is different from a demonstrated missing capability. No overall percentage can substitute for completing the important remaining journeys.

## What changed at trunk

The following are current-source findings, checked against implementation and the closed beads. The last column distinguishes our new execution from inspection of landed tests and reported merge verification.

| Original finding | Current disposition | Evidence in this review |
|---|---|---|
| Promotion discarded terminal expectations; the Test dialog also flattened non-dispatch steps. | Repaired in `rf2-5vmog`; copy-to-source now also preserves network/effect slots (`rf2-siyxz`). Source-owned assertions/checks are carried explicitly; an exact dispatch-only projection can recover the source program. Ordinary inheritance stays unchanged. | Source inspected; promotion and UI-helper JVM tests passed, including fail/pass/fail and snippet round trips. Browser DOM preservation tests inspected, not rerun here. |
| Upgrade scaffold was unreadable and retained the pin. | Repaired in `rf2-mw9th`. The simple pinned-source upgrade parses, removes pins and exposes a real handler defect. | Upgrade acceptance tests executed. They inspect the subscription render path, so a retained pin cannot falsely satisfy the check. |
| Installation recipe, launch path and introductory declarations disagreed with the APIs. | `rf2-allgj`, `rf2-ot2xp`, `rf2-rhn3z` and `rf2-78s0d` repair the recipe, mount examples and browser root route. `rf2-seb9h` was discharged by the documented dependency closure. | Docs and source inspected; the clean-consumer compile/browser receipt is the fixing worker's evidence, not a new Astra run. |
| Derived controls were invisible in the login example; its white card inherited pale text. | `rf2-wmoer` adds the optional heading schema; a separate commit makes the card own its text color. Live schema validation follows Controls and modes (`rf2-lzzrw`). | Current view metadata/styles and validation implementation/tests inspected. Old screenshots remain historical. |
| Explain and related projections omitted ambient args; results omitted promised identity. | `rf2-noxox`, `rf2-851t0`, `rf2-7vz97` and `rf2-hzf6a` repair scenario-facing args and result slots. | Executed Explain/View-State tests and run/render hash agreement tests. No claim that every browser panel was exercised. |
| Failed-row evidence link and auto-grid count were incomplete. | `rf2-7etf3` connects failures with coordinates to their evidence beat; `rf2-dacnd` counts the resolved layout's cells. | Current UI code and tests inspected. A row without evidence coordinates deliberately has no link. |
| Host/isolation guidance and orphan-registration promises were inaccurate. | `rf2-b7o66`, `rf2-idm5u`, `rf2-a5qsn` and `rf2-bf12o` correct the documentation. Parentless/inline variants remain legitimate. | Docs, registrar contract and bead dispositions inspected. No new parent-validation rule is warranted. |

Primary local paths: [promotion](../../../../tools/story/src/re_frame/story/promotion.cljc), [promotion snippet](../../../../tools/story/src/re_frame/story/ui/promotion.cljc), [upgrade generator](../../../../tools/story/src/re_frame/story/ui/view_state.cljc), [install recipe](../../../../docs/story/index.md#install-story), [login view](../../../../tools/story/testbeds/login_form/views.cljs), [resolved render](../../../../tools/story/src/re_frame/story/render.cljc), [Explain](../../../../tools/story/src/re_frame/story/ui/explain_panel.cljc), [Test UI](../../../../tools/story/src/re_frame/story/ui/test_mode/view.cljs). The [source map](evidence/source-map.md) lists tests and revision boundaries.

Two corrections prevent this update from overstating completeness. The upgrade remains an **author-completed scaffold**: when it bypasses pinned ancestors, its comment names context the author may need to copy. We verified the simple pinned-source outcome, not arbitrary `:compose` chains. Promotion can recover source expectations only when that source is registered; an unrelated or source-less captured program remains its own program. These are useful boundaries to explain, not reasons to add universal merge or serialization machinery.

The npm closure is also smaller than our earlier discussion suggested: the measured extra packages are **`@xyflow/react` and `elkjs`**, alongside React/ReactDOM. `markdown-it` and `launch-editor` were not required by the clean consumer. Naming the two packages meets the current setup job; a separate manifest, lazy-loader or scaffolder needs demonstrated benefit before being added.

## The competitor baseline

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

Sibling research also pointed to Widgetbook's Flutter authoring model. Its official v4 beta announcement uses generated, typed story declarations and args; the stable documentation describes explicit knobs. The useful lesson is to make the component's existing contract the easiest control source. This is an additional design reference, not a fifth fully assessed comparator or a stable-v4 claim. Storybook already infers argTypes from component metadata too, as our heading control demonstrated. [Widgetbook beta announcement](https://www.widgetbook.io/blog/widgetbook-v4-beta-is-here), [stable knobs](https://docs.widgetbook.io/knobs/overview), [Storybook inference](https://storybook.js.org/docs/api/arg-types).

## What the comparison proves, and what it does not

### Working components and complete journeys are different evidence

At the original baseline, Story rendered idle, submitting, error, retry-submitting and authenticated states together; a heading edit remained confined to its variant. Public runs distinguished a false assertion, a true assertion and an unsupported headless DOM action. These remain useful observed capabilities. The five login passes prove the authored machine-state checks, not HTTP serialization or browser-CI equivalence. Some fixtures dispatch response events directly. Storybook's exercised retry story likewise used an asynchronous function stub, not a live server or MSW transport. [Story runtime](evidence/story-public-run.json), [login declarations](../../../../tools/story/testbeds/login_form/stories.cljs), [Storybook fixture](evidence/storybook-fixture/src/Login.stories.tsx).

The original false-assertion promotion probe established loss of a requirement; it was not an application bug we repaired. The new implementation tests now establish fail/pass/fail for covered source expectations and an upgraded subscription read that reveals a handler defect. The remaining product-level demonstration should connect the human's actual failure, useful causal evidence, promotion dialog, application fix and browser CI without changing the requirement. Existing passing unit/integration tests reduce uncertainty; they do not measure diagnosis effort. [Historical probe](evidence/story-roundtrip-probes.json), [current promotion tests](../../../../tools/story/test/re_frame/story/promotion_cljs_test.cljc), [DOM tests](../../../../tools/story/test/re_frame/story/promotion_dom_cljs_test.cljs), [upgrade acceptance](../../../../tools/story/test/re_frame/story/ui/view_state_upgrade_test.clj).

There is no controlled speed comparison. Story used the repository's dependencies and compiler cache; Storybook used an isolated manually configured fixture. Their original compile/install durations measure different work. Neither warm browser automation time nor a sibling's short MCP transcript establishes an ergonomic advantage. Use the same task and count repeated input, mandatory concepts, context switches and wrong turns as well as elapsed time.

### Isolation needs a named boundary

Separate **application state isolation** from **document isolation**. Story frames own app-db, event queues, subscription caches and epoch histories. Workspace cells share CSS, focus and body-level portals. The handler registrar is process-global. Managed `dispatch-later` and machine timers have frame/lifecycle ownership; a blanket claim that all timers leak would be wrong. Arbitrary application globals and external I/O still need appropriate fixtures. [Frame model](../../../../spec/002-Frames.md), [document boundary](../../../../docs/story/02-every-state-side-by-side.md), [frame implementation](../../../../tools/story/src/re_frame/story/frames.cljc).

Storybook's preview iframe separates the preview from its manager; it does not imply a separate browser document for every simultaneous story. Its Docs stories can render inline or in an iframe. Judge the boundary needed by the use case, rather than awarding universal isolation to either architecture. [Storybook Story block](https://storybook.js.org/docs/api/doc-blocks/doc-block-story).

The login card's inherited-color defect is fixed. The broader policy of whether Story should provide inherited canvas text color remains an open operator decision, `rf2-w72ij`. That decision is distinct from general CSS isolation and from visual regression. A color reset alone proves neither of those outcomes. An iframe mode should follow a concrete need for a separate document, not the existence of a checkbox in a competitor.

### Each result display should say what it covers

Grok's observation of a Play pass beside an unrun Tests count is a useful prompt to inspect semantics, not by itself proof of contradictory results. The Play chip observes runner state for the selected script/play. Test summaries are recorded separately when Test runs complete. Explain already separates script steps from **terminal** assertions, with the empty text “no terminal assertions.” An empty terminal section does not establish that script assertions were lost. [Play status](../../../../tools/story/src/re_frame/story/ui/play_status.cljs), [Test recording](../../../../tools/story/src/re_frame/story/ui/test_mode/state.cljs), [Explain sections](../../../../tools/story/src/re_frame/story/ui/explain_panel.cljc).

The practical test is agreement for the **same variant, resolved inputs, execution scope and run**. A concise scope label or useful link may help if an actual walkthrough reveals confusion. Do not merge independent histories or copy success into an unexecuted test suite merely to make the counts match. This adds a discriminating check to the existing protocol, not a new status subsystem.

### Visual and accessibility review remain outcomes to demonstrate

Story has input fingerprints, behavioral goldens and accessibility tooling. These are useful ingredients, but they do not themselves establish a pixel baseline/comparison/approval workflow. The original axe scan reported zero violations while a direct subject scan returned eight color-contrast findings as incomplete. Those receipts describe the earlier card and browser run; the card's fixed color is not a new full accessibility audit. Preserve incomplete results and include keyboard/focus inspection. [Original axe receipt](evidence/story-a11y.json), [visual evaluator](../../../../tools/story/src/re_frame/story/play/browser.cljc), [requirements](../../../../tools/story/src/re_frame/story/requirements.cljc), [goldens](../../../../tools/story/src/re_frame/story/golden.cljc).

Use an existing browser screenshot runner, a small selected suite and explicit baseline updates. Keep a stable comparison case—variant plus named theme/viewport/browser—while attaching the content hash as provenance. A declaration change must still compare with the approved predecessor. An unchanged input hash must not skip capture: CSS, fonts, view implementation and browser output are not all represented by the input tuple. All three reports now agree on this correction. [Identity tuple](../../../../tools/story/src/re_frame/story/identity.cljc), [Playwright reference comparisons](https://playwright.dev/docs/test-snapshots), [Ladle recipe](https://ladle.dev/docs/visual-snapshots/).

Feature parity makes everyday visual review a legitimate job already. It need not wait for a hypothetical future design-system team to ask again. Demonstrate the modest integration before deciding what product surface is missing. The provider can be a local runner or a hosted integration with its cost and review responsibility made explicit. This does not imply building a hosted visual service. Histoire's screenshot plugin illustrates why capture alone should not be credited as comparison; its Lost Pixel/Percy routes supply the latter. [Histoire comparison integration](https://histoire.dev/examples/visual-regression-testing/lost-pixel), [capture implementation](https://github.com/histoire-dev/histoire/blob/f04001937b86d330d1b8df3483adbad4bfbcc57c/packages/histoire-plugin-screenshot/src/index.ts).

### Human and agent should share a case in the appropriate host

The standalone Story-MCP JVM and the browser reached through Pair own different registries and frames. Current docs now explain that distinction. Two transports are reasonable; the user needs one obvious route for the task at hand. The same variant ID across hosts does not mean the same live frame. [Two-host recipe](../../../../docs/story/09-multi-substrate-and-agent-loop.md#two-hosts), [MCP README](../../../../tools/story-mcp/README.md).

Our original Pair connection was unavailable, so direct browser API calls were used. Fable supplied a real JVM MCP run/register/rerun transcript, preserved with attribution. Its successful edit changes expected 2 to expected 1 while setup still sets 1. This proves transport and re-registration; it does not prove application repair with the original requirement preserved. Nor does it show the human's browser state. Keep those claims separate when evaluating Storybook's official MCP route too. [Peer transcript and boundary](evidence/fable-mcp-receipt/README.md).

## The most useful ways to be better

The strongest opportunity is **less reconstruction of intent**. A single named application plan should carry useful inputs, behavior, expectations and provenance between exploration, explanation, testing and collaboration. Framework coupling earns its cost when it removes work the programmer or assistant would otherwise repeat.

| Workflow | Benefit to demonstrate | Fair comparison and stopping point |
|---|---|---|
| Explore → retain → strengthen | Keep a useful edited state, replace its pin with real setup, retain its check, and expose a handler defect the picture could not detect. | Compare Histoire's current source and Storybook args/providers/MSW. Count re-entry and lost context. The simple upgraded case is now covered by tests; do not add transformation UI unless the human task benefits. |
| Failure → cause → regression | Reach the relevant event/effect/machine evidence, preserve the failing behavior, fix the application, then reintroduce the fault and see the same test fail. | Compare Storybook Interactions plus framework DevTools. Record time, wrong turns and preserved expectations. More evidence is not a win unless it helps diagnosis. |
| Human state → agent → verified revision | Both select the same case in the intended host, see its inputs/failure, edit the ordinary declaration and verify it. | Compare Storybook MCP with supported browser tests. Count host switches and manual translations. Separate JVM/browser transports are acceptable when choosing correctly is easy. |

Keep the first component simple. Portfolio's concise scenes and Ladle's component exports remain useful benchmarks. One schema-backed flagship example can teach derived controls without making every author learn Malli. Machine-derived scenario sets and production-epoch promotion are possible future investigations; neither should displace these ordinary workflows without a concrete task showing their value.

## Recommended sequence

| Order | Next action | Completion criterion and scope |
|---|---|---|
| 1 | Rewalk the repaired first session and failure workflow. | In a fresh consumer, follow the current recipe, edit a component, run a meaningful browser scenario, navigate its evidence and retain a regression that fails/passes/fails with the application defect. Reuse existing tests and fixtures; inspect effort as well as correctness. J01/J02/J06/J07. |
| 2 | Complete one supported human/agent journey. | Human and assistant operate on the intended case/host, agree on resolved inputs and evidence, make a requirement-preserving correction and rerun. The host docs are now present; test whether they remove confusion. J10. |
| 3 | Demonstrate modest visual/a11y review and static sharing. | A real pixel and accessibility defect is detected, approval is explicit, and a fresh-context static/deep link carries the promised case/settings. Start with Playwright and the existing static rig. J08/J09. |
| 4 | Use one realistic catalog and async isolation case to test the remaining claims. | Measure discovery/navigation at a declared size and delay/reset a managed request across two frames. Add fixes only for observed problems. J03/J04/J05. |

These are bounded evaluations, not a request for four new subsystems or an exhaustive gate. Publication/template decisions remain separate adoption work; an optional template should reuse the proven recipe if repeated setup is an observed cost. No new debugger, runner, story format, merge API, addon marketplace or hosted review platform is justified by this evidence.

## Are the sibling reports converging?

**Yes: there is strong consensus on direction and the original practical priorities. There is not complete consensus on scoring, comparative superiority or the next stopping point.** Both siblings' second revisions now acknowledge the landed fixes. Their older scoreboards and much recommendation prose still describe the original baseline, so a header update should not be read as a new execution result.

| Topic | Agreement and remaining difference |
|---|---|
| Product direction | All three prioritize ergonomic SPA work, a small set of concepts, preserved intent and reuse of re-frame2/Xray. All reject expanding the architecture to fix the original handoffs. |
| Original priorities | All converge on promotion, fidelity upgrade, truthful setup, visible schema controls and coherent evidence/inputs. These are now largely landed, so the priorities become acceptance journeys and regression coverage. |
| Competitive baseline | All recognize Storybook's real interaction/testing/MCP capabilities. Async journeys, grids or an MCP endpoint alone do not prove a unique advantage. |
| Isolation and visual identity | All separate state from document isolation and reject skipping pixels solely because an input hash is unchanged. Fable explicitly withdrew its earlier optimization. |
| Measurement | Astra and Grok reject an overall weighted parity score. Fable keeps one with categorical findings first and sensitivity analyses; its 1.13 ratio falls to roughly 1.00 under alternative assumptions. That is not measured superiority, and its projected post-fix score is not a rerun. |
| Visual-review priority | Fable and Grok defer a recipe until a design-system need is named. Astra considers the user's stated feature-parity objective sufficient reason to test a small existing integration now. All oppose building a hosted service merely for parity. |
| Strength of advantage claims | The siblings make more categorical “ahead” claims from application-state capabilities. Astra treats reduced authoring/diagnosis effort as a hypothesis until an equivalent workflow demonstrates it. |

Agreement is not three independent confirmations of every claim. The reports intentionally cite each other's original experiments; later agreement can be shared evidence. We preserve provenance and independently reran the focused current tests instead of counting repeated citations as corroboration. The [review record](evidence/second-sibling-review.md) identifies corrections that survive consensus: the orphan-registration promise is already fixed in docs, script assertions are not terminal assertions, and the npm closure is only two additional packages.

## Bounds of the conclusion

This report combines an executed original browser comparison, current source inspection, six freshly executed JVM test namespaces and attributed sibling evidence. It does not claim a current full browser walkthrough, full clean-machine onboarding/HMR comparison, cross-renderer parity, catalog-scale performance, complete workshop accessibility, hosted visual review or connected browser/MCP application repair. The [protocol](evaluation-plan.md) specifies those bounded next experiments. No production changes were made during this research.
