# Research brief: Story parity, ergonomics, and opportunities to be better

You are researching Story, re-frame2's component and application-state development tool. Produce an independent, carefully evidenced product and technical assessment, with concrete recommendations, in this repository's `ai/findings/Story/astra/` directory.

The user wants Story to have feature parity with Storybook and comparable JavaScript and non-JavaScript alternatives, while taking full advantage of re-frame2's strengths, idioms, and ethos. Answer two questions:

1. How can we test how close Story actually is to that objective today?
2. Where could Story be meaningfully more powerful, more ergonomic, and more productive for programmers and their AI assistants?

Investigate and write the report. Recommendations need evidence and judgment; a catalogue of features or an aspirational roadmap alone will not answer these questions.

The primary audience is a programmer building a re-frame2 SPA and choosing a component/state workshop. Distinguish an experienced Storybook user new to Story, a returning application or design-system maintainer, and their AI assistant. State the framework knowledge each journey assumes; source-code familiarity acquired during this research must not silently become an onboarding prerequisite.

## Project posture

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

Apply this posture to the analysis and the recommendations. The goal is an excellent workshop for creating SPAs: minimal, expressive APIs; useful defaults; composable capabilities; short feedback loops; and excellent tools. Pre-alpha permits simplifying or replacing awkward designs. It does not justify unreliable everyday workflows.

Feature parity is a serious objective. Start by understanding what users can accomplish with the alternatives. Do not quietly narrow parity to the areas where Story already looks strong, or label an inconvenient omission a deliberate divergence without a reason. At the same time, parity does not require reproducing another product's API, implementation, plugin count, commercial service, or accumulated complexity. Explain which outcomes matter, how Story can provide them idiomatically, and which differences we should consciously accept.

Look for improvements that remove concepts, configuration, repeated work, and unnecessary friction as well as improvements that add capability. Do not recommend a framework within the framework, a new policy system, or a large infrastructure project when a small primitive, better integration, or clearer workflow would solve the problem.

## Working scope and deliverables

The repository root is `<HOME>/code/re-frame2`. Resolve paths against the checkout you actually inspect. Read applicable `AGENTS.md` and `CLAUDE.md` instructions and run `bd prime`. Use beads for task tracking as required by the repository. Read relevant existing beads, including later notes and rulings, to avoid proposing already-resolved work; treat their factual claims as leads to verify independently.

Write:

- `ai/findings/Story/astra/report.md`: the main report and recommendations.
- `ai/findings/Story/astra/parity-matrix.md`: the evidence-backed comparison, linked from the report.
- `ai/findings/Story/astra/evaluation-plan.md`: the practical, repeatable evaluation protocol and results obtained during this research.
- Supporting evidence under `ai/findings/Story/astra/evidence/` only where it improves reproducibility or keeps the main documents readable.

Keep this `prompt.md` intact. Preserve existing unrelated work. Findings under `ai/` are local artifacts; follow the repository's rule against adding them to Git. This is a research assignment, not authorization to implement recommended product changes or turn every comparison row into a new bead. Use isolated scratch fixtures for experiments where needed, following the repository's checkout and gate instructions. Record implementation proposals in the report and link existing beads rather than creating a speculative backlog.

Include the research timestamp with timezone, the inspected repository commit, relevant local changes, competitor versions, and exact test environments. If the checkout moves during research, pin material observations to the revision examined and check consequential drift before concluding. An unavailable browser, credential, dependency, or service is a limitation to report, not evidence of either parity or a product defect.

This brief incorporates methodological ideas from the sibling `grok/prompt.md` and `fable/prompt.md`. Their claims are not evidence. Establish your comparison criteria and initial assessment before reading any sibling reports; if you later compare conclusions, identify disagreements and verify them rather than voting between reports. Disclose that synthesis separately from your initial findings.

## 1. Establish what Story actually is today

Build a concise map from user-facing capability to public API, implementation, test coverage, and a runnable example. Useful starting points, to verify rather than treat as an exhaustive or authoritative inventory:

- `README.md`, `docs/story/`, and `docs/story/api/`.
- `spec/007-Stories.md`, `spec/Principles.md`, `spec/Ownership.md`, and relevant frame, schema, instrumentation, and state-machine contracts.
- `tools/story/README.md`, `tools/story/spec/README.md`, and the linked vision, principles, design rationale, API, runtime, authoring, testing, and UI contracts. Engage explicitly with the parity-and-surpass thesis in `018-Story-UI-North-Star.md`: does Story deliver it, and is it the right thesis? Use its user stories and existing budgets as inputs to challenge. Pull `017-Testing-Story.md` and other capability contracts when a journey needs them; a complete spec read-through is unnecessary.
- `tools/story/src/`, `tools/story/test/`, and `tools/story/testbeds/`, including `login_form`, `counter_with_stories`, and `fresco_counter`.
- `examples/patterns/nine_states/` and other real application examples where they illuminate everyday SPA work.
- `tools/story-mcp/`, `docs/story/api/mcp-surface.md`, and the Story references under `skills/re-frame2/references/tooling/`.
- The Xray, pair/MCP, renderer, and framework code actually used by the workflows under review.
- Current build scripts, `implementation/package.json`, compiler configuration, and the CI jobs that really exercise these paths.

Documentation, test names, a green aggregate job, and a public function's existence do not individually establish a working user journey. Trace important claims through the actual integration and exercise representative paths. Distinguish implemented behavior, documented contracts, historical proposals, and known limitations. Explicitly test prominent parity or superiority claims in the repository; do not repeat them as conclusions.

After drafting your current comparison criteria, consult `tools/story/spec/Feature-Parity-Audit.md`, the feature-set and SOTA-refinement research under `tools/story/spec/findings/`, and `ai/findings/story-review.md` if present. Recheck consequential claims against today's source and relevant bead history. Report material changes from the earlier audit—resolved gaps, remaining gaps, overstated wins, and changed assumptions—with evidence. Do not mechanically rescore every historical row or repeat the implementation review. A prior defect matters here when it invalidates a user outcome; an old finding may already be fixed. Labels such as `CURRENT`, `TARGET`, and `BLOCKED` in specs describe claims or intent, not runtime proof.

Use the current public vocabulary. For example, investigate `:setup`, `:script`, and `story/run`, `story/is`, and `story/explain` at source before copying examples from older documents. Do not mistake a retired spelling or old design for a missing current capability. Likewise, distinguish a subject's authoring substrate from the adapter hosting the Story shell.

## 2. Research a fair comparison set

Browse current primary sources: official documentation, release notes, maintained source repositories, and official examples. Establish the current stable Storybook baseline at the research date; do not inherit an old version number from this repository. Identify experimental or announced capabilities separately from released ones.

Use Storybook as the main comparison. Select a small, justified set of complementary alternatives, normally four to six products in total, including:

- A lighter JavaScript component workshop. Investigate candidates such as Ladle, Histoire, and React Cosmos and select based on current relevance and maintenance.
- An idiomatic Clojure/ClojureScript alternative, such as Portfolio, if its current scope makes it informative.
- At least one non-JavaScript example with a materially different approach: for example Lookbook, Flutter's Widgetbook, SwiftUI previews, or a Phoenix component workshop. Verify suitability and availability before choosing.

These are research candidates, not assertions about their current capabilities. Give the selection rationale and explain the limits of cross-language comparisons. Research adjacent visual testing, browser testing, design review, and agent tooling where they complete a common workflow, without expanding into an exhaustive survey of the whole frontend ecosystem.

Briefly check the ClojureScript lineage, including devcards and workspaces, before describing a familiar idea as a Story invention. Explicitly verify competitors' current agent/MCP/CLI surfaces; neither their absence nor Story's uniqueness is a premise. Follow an additional comparator only when it changes a relevant judgment, test, or recommendation.

For each capability, distinguish built-in support, an official integration, a maintained community integration, a hosted/paid service, and custom application work. Include ordinary, idiomatic integrations in a competitor's strongest practical workflow. Do not compare Story plus its entire ecosystem against an artificially bare competitor. Conversely, do not attribute a separate service's capabilities to an open-source package without naming the dependency, setup burden, and any relevant cost or account requirement. Verify current commercial facts if they affect a recommendation.

Cite direct primary-source URLs near claims, with version or retrieval context. Treat marketing as a claim to investigate. When sources disagree, resolve consequential differences through source inspection or a small experiment and state any remaining uncertainty.

## 3. Define parity in terms of outcomes

Organize the matrix around useful tasks, with feature details underneath them. Cover the following areas at an appropriate depth; split or combine rows to avoid either hiding a major gap or counting a dozen cosmetic variations as a dozen capabilities:

- Getting started in a new or existing application; dependency and build setup; discovery; hot reload; editor navigation; understandable examples and errors.
- Authoring components and application states; args and controls; schemas/types; reusable setup, decorators, fixtures, and composition; source visibility; ordinary application code reuse.
- Real SPA behavior: forms, routing, asynchronous loading, errors and retries, state machines, resources, effects, clocks, external I/O, and controllable test data.
- Isolation, reset, lifecycle cleanup, parallel or side-by-side states, and comparison across supported renderers, themes, viewports, locales, and other meaningful axes.
- Interaction testing, assertions, test discovery, headless and browser execution, debugging failures, deterministic reproduction, recording, and converting a useful scenario into a regression test.
- Visual regression and review: capture, baseline comparison, actionable diffs, and review workflows. A declared-input fingerprint is not a pixel comparison or a visual approval service.
- Accessibility: testing the rendered subject and the usability of the workshop itself. Separate automated checks from keyboard/focus behavior and what still requires human judgment.
- Documentation, examples, API/args explanations, navigation/search, documentation embedding, static publication, deep links, sharing, and composition across projects where relevant.
- Runtime inspection and feedback: events, subscriptions, app state, effects, machines, traces, time travel, and connecting a failure to its cause.
- Extension and integration surfaces; installation and upgrade burden; startup, rebuild, interaction, and larger-catalog behavior; production exclusion of development tooling.
- AI-assisted work: discover, inspect, author, execute, diagnose, revise, and verify through the supported tool/skill/MCP surfaces, using the same artifacts and results as the human UI.

For each meaningful row provide the user outcome and importance; competitor support and requirements; Story's actual behavior and public entry point; supporting evidence; a concrete acceptance test; limitations; and confidence. Make disagreements among contract, implementation, documentation, and everyday usability visible. An undocumented internal route is evidence of technical possibility, not a finished user workflow.

Use separate columns for **capability status** and **evidence strength**. Suggested statuses are complete for the stated scope, partial, absent, intentional divergence, not applicable, and unknown. Evidence can be exercised, source-traced, documented only, or unverified. A supported capability can still be poorly evidenced, and a confirmed gap can have high confidence. Do not force an unverified claim into complete or absent. Describe comparative strengths and discoverability separately rather than awarding Story a special status that makes the matrix incomparable.

Summarize how close Story is by important workflow and capability area, clearly exposing adoption blockers and unknowns. If you calculate a score, show the denominator, weights, exclusions, and how unverified rows affect it; provide a range where warranted. Do not let many minor successes cancel one essential failure, or let a precise percentage conceal a weak evidence base. An honest categorical assessment is preferable to a misleading single number.

## 4. Design and exercise an evaluation that can be repeated

Develop a small benchmark application or use suitable existing fixtures. Include a simple component, a stateful form, and an asynchronous SPA journey. Compare equivalent user outcomes through each tool's idiomatic approach, with comparable assumptions and fixtures. Avoid making competitors adopt re-frame2 internals or requiring Story to copy JavaScript authoring syntax.

Record the initial jobs, acceptance criteria, and relative importance in `evaluation-plan.md` before scoring them. Explain later changes so the rubric cannot quietly move to favor a result. Give jobs stable IDs shared by the matrix, evidence, and recommendations. Keep the method small enough to rerun without this prompt.

Specify a compact set of approximately eight to ten scenarios, adapting this starting set where evidence suggests a better one:

1. Follow the public getting-started instructions literally to reach a useful first story; record stalls, undocumented boot steps, and source-code lookups. Change application code and observe the feedback loop. Distinguish this walkthrough from a real novice-user study.
2. Author a simple props/args-driven component, controls, and reusable variants. Then try increasing one variant's fidelity from pinned inputs or seeded state to real setup events without rewriting the scenario; record what additional behavior the stronger version actually proves.
3. Explore loading, success, empty, validation-error, server-error, and retry states of a real form or data view, with predictable effects and time.
4. Render independent states side by side; interact, reset, and switch variants without cross-contamination or stale asynchronous results. Check relevant isolation boundaries such as CSS, focus/portals, global state, and external effects; frame-local state alone does not prove whole-page isolation.
5. Exercise supported theme, viewport, locale, and renderer combinations and find a layout or interaction defect. State exactly which combinations were verified.
6. Record or author a behavior, turn it into an automated test, and rerun it locally and through the appropriate CI path.
7. Diagnose a deliberately introduced application failure, find the causal event/state/effect, preserve it as a failing regression variant, then correct the fault and rerun. Keep assertions capable of detecting the original fault. Distinguish state restoration from replaying external effects.
8. Detect an intentional visual and accessibility regression; explain baseline ownership and human review boundaries.
9. Publish or share a useful reproduction/documentation page and open it in a fresh context, identifying what state and dependencies do or do not travel with it.
10. Have an AI assistant discover a scenario, run it, inspect the same failure a human sees, propose or author a minimal change, and verify the result through supported tools.

For each scenario specify prerequisites, steps, expected observations, failure signals, the simplest adequate verification method, and what proves completion. Separate the proposed reusable protocol from the measurements you actually obtained.

During this research, execute a small but consequential subset on Story and at least the main Storybook baseline where the environment permits: an authoring/controls journey, a stateful asynchronous interaction/test journey, and a failure-diagnosis journey. Inspect the real rendered browser UI when judging interaction or usability. Use source and documentation for breadth and deeper execution for the claims that could change the recommendations. If a comparison cannot run, give the exact limitation and the smallest experiment needed next; do not invent timings or imply execution.

Measure what helps a programmer decide: manual setup, files and concepts touched, repeated declarations, time to first useful result, feedback latency, recovery from an ordinary mistake, reproducibility, clarity of failure evidence, and human/agent handoffs. Report actual timings with hardware, versions, cold/warm state, and sample count. Small exploratory samples are observations, not general performance claims. Code examples and annotated workflow comparisons often reveal ergonomics better than raw line counts.

Explain how to repeat the evaluation: prerequisites and commands, a short smoke subset, deeper checks for affected workflows, and the observed or estimated cost of each. Identify what invalidates a row—relevant code, docs, integration, or competitor-version changes—and retain unaffected evidence. Separate deterministic checks suitable for existing automation from usability judgments requiring a person. Reuse existing parity budgets where applicable. Propose a lightweight rerun cadence or triggers, not a new permanent gate for every row.

Use a few deliberate failures or negative controls to establish that a claimed test detects the defect in question. A headless success cannot prove DOM interaction, and a skipped browser test cannot prove parity. Read the runner's actual exit status and coverage conditions; follow the repository's absolute-path, checkout-verification, and unique ignored-log instructions for gates. Prefer existing harnesses. A new permanent benchmark service or a broad testing-platform rewrite is not required for this research.

## 5. Imagine better workflows and try to disprove the advantage

Generate candidate improvements from real development friction and from what re-frame2 makes unusually simple. Investigate, without presuming superiority:

- Named application states as reusable data that can serve a workshop, documentation, tests, reproductions, and agent tools without parallel definitions.
- Frames as a basis for reliable, simultaneous state exploration and repeatable experiments.
- Events, subscriptions, effects, resources, and machines as a shared vocabulary for driving behavior and explaining why a view looks as it does.
- Schema-derived controls, representative data, and useful state exploration without a second configuration language or uncontrolled combinatorial expansion.
- Xray and trace/epoch evidence as a way to move from a visible failure to a causal explanation with fewer manual steps.
- One productive human/AI loop with inspectable artifacts, clear results, and minimal translation between interfaces.
- Composition that makes rich scenarios easier to express while keeping the common case short; improvements obtained by deleting, consolidating, or improving defaults.

For each promising idea, show a concrete before/after authoring or debugging workflow, a small idiomatic API/data sketch where helpful, and a falsifiable hypothesis about user benefit. Label proposed APIs clearly; verify any example presented as working today. Distinguish an existing but undiscoverable strength from an integration gap and from a new capability.

Compare the idea with the strongest practical competitor solution, including suitable integrations. Explain what re-frame2 contributes and what it costs: additional concepts, coupling, configuration, runtime/tooling weight, and maintenance. Consider the limits of data-shaped artifacts, state isolation, and replay when real external I/O or non-serializable behavior is involved. Do not turn those limits into elaborate machinery without a concrete user need.

Synthesize a concise product thesis: which users should prefer Story, for which jobs, and why its few underlying concepts make those jobs easier. Illustrate it with two or three concrete future workflows, distinguishing capabilities already available from the smallest missing pieces. A useful advantage can be a common task made substantially easier; novelty or a task impossible elsewhere is not required.

Select roughly three to five opportunities worth pursuing, and explain why other plausible ideas should wait or be declined. It is acceptable to find that an alternative is more ergonomic, or that Story already has the right capability and needs better presentation. Do not manufacture novelty or claim competitors cannot do something merely because their core documentation does not mention it. Separate benefits supplied by re-frame2 itself from benefits Story makes accessible, and check that its simplest component workflow remains pleasant alongside its richer SPA workflows.

## 6. Make recommendations that can guide decisions

Rank a short set of recommendations by user impact, evidence, reach across workflows, and implementation/maintenance cost. Separate:

- Essential parity gaps or correctness failures that obstruct ordinary adoption.
- Existing capabilities whose discoverability, integration, or ergonomics need improvement.
- Distinctive improvements that earn their complexity through demonstrable user benefit.
- Deliberate differences, low-value imitations, and work to defer.

For each recommendation provide the concrete problem and affected user; evidence and confidence; the smallest coherent change; a before/after example; likely owning surfaces; rough effort and dependencies; and a testable acceptance criterion linked to the affected job IDs. State what it removes or simplifies as well as what it adds. Cite related beads and relevant current decisions, without treating past decisions as substitutes for fresh analysis. Where a recommendation challenges an existing design, state the tradeoff and the evidence that warrants revisiting it.

Avoid a long task inventory. Cluster symptoms with a common remedy, and separate measured defects from research hypotheses. Offer a sensible sequence for the next few improvements, with a clear stopping point and an explanation of what would change the ordering.

## Report shape and quality bar

Lead `report.md` with the answer: how close Story is for the named users, which essential journeys still prevent a credible parity claim, the product thesis, and the first improvement you would choose. Then present the relevant evidence, material changes from prior research, workflow comparisons, strongest opportunities, recommended sequence, and remaining uncertainties. Link the detailed matrix and evaluation plan instead of duplicating them. Keep the main report decision-focused; put bulky receipts in the evidence directory. Prefer useful depth over encyclopedic coverage.

The report must make it easy to distinguish:

- What exists and works now.
- What is supported only by source inspection or documentation.
- What failed, and under which conditions.
- What remains untested or unknown.
- What is proposed, why it would help, and how that benefit would be tested.

Before finishing, challenge your own conclusions. Did you verify the loudest claims? Did you compare complete workflows fairly? Did the tests exercise the relevant browser or runtime? Did you mistake a hosted service for a built-in feature, a fingerprint for visual regression, or an existing primitive for a user-friendly finished workflow? Have you recommended the smallest powerful design, and have you explicitly declined attractive work that does not earn its cost?

Deliver a report that lets the user make a few well-founded product decisions and gives the team a practical way to measure progress toward parity and superiority over time.
