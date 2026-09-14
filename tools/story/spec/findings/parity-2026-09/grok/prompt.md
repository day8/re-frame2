# Research brief: measuring Story against Storybook-class workshops, and imagining the win

You are being asked to **research and report**, not to implement. Do not edit tracked files, specifications, tests, workflows, or tracker state. Do not open a PR. Intermediate notes and the report itself belong only under `ai/findings/Story/grok/` (this tree is local-only and gitignored). Leave this `prompt.md` untouched.

Sibling folders `ai/findings/Story/fable/` and `ai/findings/Story/astra/` may exist and may grow reports. **Do not read those reports until your own `report.md` is drafted.** Independence is load-bearing: two reports that have already absorbed each other are one report wearing two hats. If you do read them afterwards, say so, and treat the result as a synthesis note — not as this pass.

Resolve every path against the checkout you actually inspect. Pin material observations to `git rev-parse HEAD`. If that SHA moves during the work, re-check consequential claims before concluding.

---

## 0. Stance — paste, do not paraphrase

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

The first sentence is the bar. Everything after it is when to **stop**. A report that recommends a hundred small Storybook-shaped addons has failed the second half. A report that litigates chrome nouns Story has already refused has failed it too.

Apply the stance to both the analysis and the recommendations. The goal is an excellent workshop for creating SPAs: a small computational model, useful defaults, short feedback loops, and excellent tools. Pre-alpha permits simplifying or replacing an awkward surface. **It does not justify an unreliable everyday workflow.** Look for improvements that *remove* concepts, configuration, repeated work, and friction, not only ones that add capability. Do not recommend a framework inside the framework, a new policy system, or a large infrastructure project when a small primitive, a better integration, or a clearer path would do.

---

## 1. The two questions — they are co-equal

re-frame2's Story (`day8/re-frame2-story`, `tools/story/`) should have **feature parity** with Storybook and similar JavaScript (and other) alternatives — **except** that it should leverage re-frame2's strengths, idioms, and ethos rather than impersonate those tools.

Answer both, in this order, because the second is unintelligible without the first:

1. **How do we *test* how close we are to that objective?**
   Not "give us a score this week." A **standing, re-runnable method** for telling whether Story can do the jobs a Storybook-class workshop is for, whether those jobs are honestly shipped (not merely specced), and where we have chosen a different shape on purpose. The method has to survive Storybook shipping a new major version and Story landing a new EPIC. A one-shot matrix dated the day you write it is a finding, not a test.

2. **How do we imagine being *better*** — more powerful, more ergonomic, more AI-amenable — than those alternatives, because of re-frame2 rather than in spite of it?
   Not "list every re-frame2 primitive." A **product thesis**: which jobs Storybook-class tools do poorly *because of their substrate*, which of those jobs re-frame2 makes cheap, which of those cheap things Story has actually productized, and which remain latent. Then the smallest set of moves that would make a demanding Storybook user *prefer* Story, not merely tolerate it. **Try to disprove each advantage** before you keep it.

Recommendations follow from those two answers. They are not a third question; they are what the answers commit us to doing, refusing, measuring, or documenting.

---

## 2. What this is not

- **Not a feature-checklist audit of Storybook nouns.** Storybook's surface is addon-shaped (Controls, Actions, Viewport, MSW, a11y, Interactions, Chromatic, …). Story's surface is primitive-shaped (frames, events, schemas, fx stubs, one plan/result/evidence path, Xray embed). Scoring "do we have an Actions panel?" will undercount Story's wins and overcount Storybook's. Parity is a quality bar on *jobs*, not a covering of *names*. This is already stated in `tools/story/spec/018-Story-UI-North-Star.md` §3.1. Verify it; also ask whether that thesis is *the right thesis*, not only whether the tree delivers it.
- **Not a re-score of the May 2026 Feature-Parity-Audit as the main method.** `tools/story/spec/Feature-Parity-Audit.md` (2026-05-20) walked Storybook's React tutorial track against then-current spec + `docs/story/`. Treat it as **prior art to re-measure**, not as the answer. Same for `tools/story/spec/findings/re-frame-2-story-feature-set.md` (rf2-m6tu) and `re-frame-2-story-sota-refinement.md` (rf2-94b0). An appendix that re-scores the *named* May gaps (C-1…C-5, D-1…D-3, I-1…I-2) plus any then-WIN the September review made dishonest is enough; do not let those 33 tutorial axes become the standing test.
- **Not a correctness review of Story's implementation.** A senior read-only review already exists at `ai/findings/story-review.md` (2026-09-13). Read it as a **warning that shipped ≠ claimed** (its P1s include a `reg-check` that never executes, tape-scoped assertions that inherit prior runs, and a vacuous `:pass` when no adapter is installed). Do not re-do that review. Do cite it wherever a comparison of `AHEAD` depends on a surface it found dishonest.
- **Not a licence to reopen locked refusals** unless you have new evidence that the refusal now *costs a job* rather than a noun. `tools/story/spec/DESIGN-RATIONALE.md` §Rejected already names: first-party visual-regression service, CSF Factories-as-JS, addon-per-concern architecture, throw-on-first-failure, brand-pink commodity chrome, pixel-scrubber UI, BackstopJS-style baseline storage, first-party SSR pipeline, in-process MCP, built-in pixel diff under `:test`, full Xray reimplementation, component-co-located fixtures, a statechart engine inside Story. Multi-host composition is a v2 deferral, not a silent gap. A recommendation to reverse one of these must say which *job* is blocked, not which competitor ships the noun.
- **Not permission to quietly narrow parity.** Do not score only the jobs Story already looks strong on. Do not label an inconvenient omission `DIVERGENT` without naming the other way the *job* is served. A refusal of a *shape* (MDX, Chromatic-as-a-service, `preview.ts`) is not a pass on the *outcome* (rich docs, visual review, global wrapping).
- **Not an implementation plan, bead-filing session, or UI redesign.** Propose; do not build. Read existing beads so you do not recommend already-resolved work; do not create a speculative backlog. If a recommendation would become a bead, give it a one-line title, the job it serves, what it **removes** as well as adds, and why it is not gold-plating — then stop.

---

## 3. The objective, stated so it can be tested

**Parity** means: an experienced Storybook (or Histoire / Ladle / Cosmos / Lookbook) user can sit down at Story and accomplish the jobs they came for, without a ceremonial map of re-frame2 internals, at comparable or lower ceremony. The chrome may look different. The authoring may be EDN rather than CSF. The debugger may be Xray rather than an Actions panel. Those are not gaps. *Being unable to do the job*, or only being able to do it by dropping into undocumented internals, is a gap.

Parity does **not** require reproducing another product's API, plugin count, commercial service, or accumulated complexity. It *does* require taking the alternatives' strongest *practical* workflow seriously — including the official addons and hosted services people actually use — and naming the dependency, setup, and cost of those extras rather than pretending Storybook-the-package includes Chromatic.

**Better** means: some of those jobs are cheaper, more honest, or newly possible because a variant is a frame running real events against real handlers, with schemas, traces, epochs, machines, resources, and an agent-readable data body. "Better" is not "more panels." It is fewer concepts doing more work, and a failure that explains itself. It is acceptable to find that a competitor is more ergonomic on a job, or that Story already has the right capability and only needs to be findable. Do not manufacture novelty.

**Ethos constraints** on both (from `spec/000-Vision.md`, `spec/Principles.md`, `tools/story/spec/Principles.md`, and the stance above):

- Data before magic. Variant bodies are EDN; no fn-slots in user-facing registration bodies.
- One obvious way. Do not recommend a second encoding for stories / tests / fixtures / repros / traces.
- Named, addressable things. Closures live at registration (`reg-decorator`), not in the variant.
- Trust the programmer. Do not recommend nagging diagnostics, mandatory ceremony, or marketplace-shaped extension for its own sake.
- AI-first is a property of the *shape* (queryable registry, serialisable variant, one plan/result path), not a bolt-on MCP demo.
- Story owns **no new framework primitives**. Downstream of Spec 007; Spec 007 wins if they disagree on the contract. **For "how close are we *today*," trunk code wins when spec and code disagree.** Say which you read.
- Pre-alpha: current implementation and current specs are grounding evidence, not compatibility constraints. A recommendation to supersede a current surface is in scope if it makes the model clearer. A recommendation to *layer* a second UI over the first is out.

Use the **current public vocabulary**. Investigate `:setup`, `:script`, `story/run`, `story/is`, and `story/explain` at source before copying examples from older documents. Do not mistake a retired spelling or an old design for a missing current capability. Distinguish the **subject's authoring substrate** (Reagent / UIx / Fresco views under test) from the **adapter hosting the Story shell**.

---

## 4. Starting evidence — claims, not findings

The observations below were true of the repository when this brief was written (2026-09-14). **Treat them as starting evidence.** Where a claim materially determines a recommendation, verify it at source and cite the file. If you find a contradiction, that is a valuable outcome — say so. Do not turn the exercise into a repository-wide audit.

### 4.1 What Story is (orientation reads)

Read these before surveying competitors, so you know what you are measuring *against*:

| Read | Why |
|---|---|
| `spec/007-Stories.md` | Framework-normative Story / Variant / Workspace split, id grammar, variant-as-data, assertions, snapshot-identity. Wins contract conflicts with `tools/story/spec/`. |
| `tools/story/spec/000-Vision.md` | What Story is and is not; "one primitive beats a parade of addons"; identity stance. |
| `tools/story/spec/Principles.md` | EDN-first, no fn-slots, production elision, no new framework registries. |
| `tools/story/spec/DESIGN-RATIONALE.md` | Locked decisions and the §Rejected list. |
| `tools/story/spec/018-Story-UI-North-Star.md` | Product contract, user stories S1–S11, elegance bar, **parity-and-surpass thesis**, tension resolutions T1–T4. The four companions `019`–`022` are the per-surface contracts. Engage the thesis by name. |
| `tools/story/spec/017-Testing-Story.md` | Variant-plan substrate (`:world` / `:script` / `:expect` / `:evidence`), `run` / `is` / `explain`, `:cannot-run`. Source of truth for the test path. |
| `docs/story/index.md` plus `docs/story/01`–`09` and `docs/story/api/` | What a new user is actually taught. A capability that exists in spec/code but not here is *undermarketed*, not a win. Follow `01-first-variant.md` literally in Phase C. |
| `tools/story/README.md` and `tools/story/spec/README.md` | Artefact map. |
| `tools/story/spec/API.md` | Public surface. Distinguishes shipped operations from historical unimplemented vocabulary — believe that distinction only after a spot-check. |
| `tools/story/spec/015-Test-Coverage.md` | The in-tree template for "which contracts are already gated." Use it when naming automatable rows of the standing test. |
| `tools/story-mcp/spec/000-Vision.md`, `002-Tool-Registry.md`, `tools/story-mcp/tool-descriptors.edn` | Agent surface, **exact tool names**. Do not assume peers lack an MCP/AI loop; check on the run date. |
| `skills/re-frame2/references/tooling/stories.md`, `story-mcp-loop.md`, `story-recorder.md` | What an agent is taught to do. A loop the MCP can run but the skill does not mention (or the reverse) is a finding. |
| `tools/xray/spec/008-Embedding-Contract.md` | The Story/Xray mount boundary. Story must not grow a second debugger. |

Do **not** read all of `001`–`016` up front. Pull a capability doc when a job requires it.

For question 2, also pull the framework surfaces Story stands on, at need: `spec/002-Frames.md`, `spec/005-StateMachines.md`, `spec/009-Instrumentation.md`, `spec/010-Schemas.md`, `spec/Tool-Pair.md`. The Xray vision (`tools/xray/spec/000-Vision.md`) only if an advantage claim depends on it.

### 4.2 Prior research and tracker history (re-verify; do not recopy)

- `tools/story/spec/Feature-Parity-Audit.md` — 33 Storybook-tutorial axes, May 2026. Headline at the time: 28 wins-or-matches, 5 capability gaps, 3 DX gaps, 2 insight gaps, 3 divergent-by-design. At least `[C-2]` (markdown prose) is marked resolved in the file itself. Re-open the *named* gaps against **today's** spec, code, and docs; cite the commit or bead if a gap closed.
- `tools/story/spec/findings/re-frame-2-story-feature-set.md` and `re-frame-2-story-sota-refinement.md` — May 2026. Useful for *method* (blind then compare) more than for version-pinned competitor claims.
- `ai/findings/story-review.md` — September 2026 implementation review. Honesty check on claimed test/runtime wins, not a competitor survey.

Read relevant beads as **leads**, never as findings. Probe fields, not the rendering (`bd show <id> --json`; `bd show` wraps lines and strips backticks). Status filters must use the comma form (`--status open,deferred`) — a repeated `--status` flag keeps only the last. Close reasons of rf2-2jdh9 (parity audit), rf2-5x1wt (Story-as-test), rf2-m6tu (the seven design decisions), and rf2-sgdd3 (Actions-panel retirement) are normative records *of what was decided then*; they do not substitute for a fresh look. Do not `bd update`, `bd create`, or `bd close`.

### 4.3 Substrate advantages the specs already claim (verify; do not assume productized)

These are the lenses 000 / 005 / 018 already say Story should win on. Separate **substrate potential**, **specced contract**, **shipped behaviour**, and **discoverable by a new user**. For question 2, label each kept claim **BUILT** (file:line, and you ran it or a test pins it), **SPECIFIED** (spec section, not honestly in code), or **IMAGINED** (your proposal). Mixing those tiers in one paragraph is a failure mode.

- A variant **is a frame**. Side-by-side states do not share `app-db`, queues, subs, or trace.
- Variant bodies are **data**. The same artefact is canvas, docs, test, share URL, static build, recorder output, and MCP target.
- **One plan / result / evidence path.** Human UI, `story/run` / `story/is` / `story/explain`, CI, and Story-MCP are entry points over the same model — or they are not, and that is a finding.
- **Schema → controls.** Malli (or the host shape layer) derives the control panel; manual `:argtypes` is the override, not the default.
- **`force-fx-stub`.** One decorator stubs any `reg-fx` (HTTP, ws, storage, analytics, geo, navigation). Storybook needs an addon per seam.
- **Xray embed**, not a second debugger. Epoch / app-db / views / trace / machines / routing are Xray's; Story owns the example, the script, the result, the narrative, promotion.
- **Record, don't throw.** Play runs to completion; the full picture of failure is the point.
- **Fidelity ladder** with honest labels: real setup events → schema-checked db seed → sub-overrides. Lower rungs are useful and must not be presented as stronger proof than they are.
- **`:cannot-run`** as a third result state, distinct from fail.
- **Failure promotion** into a curated variant, not a stray repro file.
- **Multi-substrate** render (Reagent / UIx, …) with failures inline.
- **Workspaces** — many states at once. Storybook is weak here; devcards / Histoire / Lookbook are the comparators.
- **Agent loop** via a *separate* MCP jar (`tools/story-mcp/`), same artefact model.

Specific probes the specs imply but may not have productized — answer each, do not assume:

- Diffing two variants as data; generating variants from a schema or a machine; replaying a production epoch as a variant.
- Deriving the variant set from machine states/tags, or from a recorded session, instead of writing them by hand.
- Whether frame isolation holds up for **CSS, focus, and portals** (the costs Storybook pays for iframes).
- Whether state *restoration* is distinct from *replaying* external effects.
- Whether fidelity-ladder and snapshot-identity are differentiators or vocabulary.

### 4.4 Spec vs code vs docs — three artefacts, three truths

A closeness test that reads only `tools/story/spec/` will congratulate us for contracts. A test that reads only `docs/story/` will miss power a user is never taught. A test that greps only `tools/story/src/` will count panels. You need all three, and you need to say which one a given verdict came from.

Particularly: `018`–`022` use status labels `CURRENT` / `TARGET` / `BLOCKED` / `FUTURE` / `OUT`. A TARGET row is not complete. A BLOCKED row is not a competitor's win until you check whether the substrate bead landed.

Documentation, a test *name*, a green aggregate job, and a public function's existence do not individually establish a working user journey. An unavailable browser, credential, dependency, or service is a **limitation to report**, not evidence of parity and not evidence of a defect.

---

## 5. Method

Work in phases. Commit each phase to a file under `intermediate/` **before** starting the next, so later phases cannot silently rewrite earlier evidence. Date-stamp competitor claims. Distinguish **source observation**, **runtime/docs measurement**, and **inference**.

### Phase A — Competitive landscape (current, not May)

Survey the field as it stands **now** (use live docs, not our May notes). Identify **experimental or announced** competitor capabilities separately from **released** ones. Treat marketing as a claim. When sources disagree, resolve consequential differences by opening the docs or source, or by a small experiment, and state remaining uncertainty.

**Storybook is the main comparison.** Establish the actual current stable major on the run date (do not inherit v8/v9/v10 from our files). Cover CSF, essentials, test widget / Vitest addon / "Storybook Test", play + interactions, autodocs / MDX, a11y, portable stories / CSF Factories, composition/`refs`, MCP or other AI surfaces, and the Chromatic relationship — and say which of those are built-in, official addons, community addons, or a hosted/paid service.

Then select a **small, justified complementary set — four to six products besides Storybook, not twenty.** Six compared well beats twenty listed. Give the selection rationale. A typical set:

- One lighter JS workshop. Investigate Ladle and Histoire (Histoire's `<Story>`/`<Variant>` split is closer to our three-way split than CSF is); pick by current relevance and maintenance. React Cosmos only if it changes a job the others do not.
- The **CLJS lineage as one group**, not three encyclopedias: devcards, Nubank workspaces, Portfolio if still informative, Day8 re-com demo-as-docs. Several of Story's "wins" (many-states-at-once, record-don't-throw, shadow-cljs-native) are inheritances, not inventions.
- At least one **non-JS** tool with a materially different approach. Candidates: Lookbook (Rails — preview + docs as one artefact), Flutter Widgetbook, SwiftUI Previews, Jetpack Compose `@Preview`, a Phoenix component workshop. Verify it still exists and is informative before choosing. Ask what it makes cheap that the JS field does not (IDE-hot preview, device matrices, design-token round-trips) and whether that idea transfers.
- Adjacent tools **only where they steal a job** a workshop used to own: Playwright / Vitest component testing, MSW, Chromatic / Percy / Argos as *visual-regression services*, Figma Dev Mode / Code Connect. Story has refused to *be* a VR service; the job is "stable key + honest iframe + a review path," which we may or may not ship.

For each capability, distinguish **built-in / official integration / maintained community integration / hosted-or-paid / custom application work.** Include the competitor's strongest ordinary workflow. Do not compare Story+Xray+MCP+skills against an artificially bare Storybook. Conversely, do not attribute Chromatic (or any paid service) to Storybook-the-package without naming setup, account, and cost. Verify commercial facts only when they would change a recommendation.

**Do not** produce a 40-tool encyclopedia. If a tool does not change a job, a recommendation, or the closeness test, a sentence is enough. Stop when another tool would not change Phase B.

Cite URLs and access dates. Do not rely on our May download counts, star counts, or version numbers.

Deliver Phase A as `intermediate/landscape.md`.

### Phase B — Design the closeness test (this is question 1)

Invent a **re-runnable test**, not a score. Cheap enough to run after a Story EPIC or a Storybook major; discriminating enough that a green run means something.

Design it as **jobs**, not features. Start from `018` §2 stories S1–S11, then add or cut jobs based on Phase A. A plausible starter set — refine it; do not treat it as a census. Stay under ~18 rows. Merge cosmetic variations; do not hide a major job.

| Id | Job | Why it is in the set |
|---|---|---|
| J1 | First hour *and* existing-app install; change app code and see the feedback loop | Storybook `init` + HMR is the bar; ceremony and stale reload are the failure |
| J2 | Navigate a large catalog (search, keyboard, restore selection) | Daily-use quality bar in `018` §3.1 |
| J3 | Make a view-state cheaply (controls / args), including schema-derived controls | Storybook's best surface; our claimed win |
| J4 | Several states at once without leakage — including reset, stale async, CSS, focus, portals | Frame isolation; workspaces; the job Storybook is weak at *and* the costs it pays for iframes |
| J5 | Mock a failure of an effect (network, anything `reg-fx`) | `force-fx-stub` vs MSW-and-friends |
| J6 | Drive a real SPA journey (loading / empty / validation / server-error / retry) with clocks and the app's own events | Nine-states; the job component-args tools fake |
| J7 | Document a component and its states in the workshop | Autodocs / MDX / Lookbook |
| J8 | The visible state *is* a test, in the UI and in CI | Portable stories / CSF Factories vs `run-variant` / `story/is` |
| J9 | A failure explains itself (assertion → evidence → cause); restore vs replay | Xray + epoch vs Actions / Interactions |
| J10 | Record an interaction and keep it as data | Play + recorder |
| J11 | Share / export a selected state honestly | URL, static build, EDN, screenshot; reproducibility honesty |
| J12 | An agent discovers, runs, reads a failure, proposes a fix, on the same artefact | MCP + skills loop; check peers' AI surfaces too |
| J13 | Design-system matrix (theme × viewport × locale, or nine canonical UI states) | Chromatic Modes / globals; our `reg-mode` |
| J14 | Accessibility: subject *and* workshop chrome; automated checks vs keyboard/focus vs human judgment | addon-a11y vs Story a11y + chrome-a11y panels |
| J15 | Visual-regression *hook* (stable identity, skip unchanged) plus an honest review path — not a first-party service | `snapshot-identity` is not a pixel diff and not an approval UI |
| J16 | Extend the workshop (decorator, panel) without an addon marketplace | `reg-decorator` / `reg-story-panel` |
| J17 | Upgrade a cheap state toward real integration in place | Fidelity ladder (S3 in `018`) |

**Three independent fields per job** — do not collapse them into a single `WIN`. A special status that makes Story incomparable to the rest of the matrix is how a checklist lies.

| Field | Vocabulary |
|---|---|
| **Capability** (does Story do the job?) | `ABSENT` / `PARTIAL` / `COMPLETE` / `SPEC-ONLY` / `TARGET` / `OUT` |
| **Discoverability** | `TAUGHT` (a new user following `docs/story/` gets there) / `UNDERMARKETED` / `INTERNAL` |
| **Evidence** | `EXERCISED` (you ran it, or a pinning test you read actually asserts it) / `SOURCE-TRACED` / `DOCUMENTED-ONLY` / `UNVERIFIED` |
| **Comparison** (vs a *named* comparator, after including its ordinary extras) | `BEHIND` / `MATCH` / `AHEAD` / `DIVERGENT` |

`DIVERGENT` is allowed only when the *job* is still served another way you can name. If we refused MDX and have no other path to readable workshop docs, that row is `BEHIND`, not `DIVERGENT`. `AHEAD` requires a structural reason, not enthusiasm. `COMPLETE` + `DOCUMENTED-ONLY` is not as strong as `COMPLETE` + `EXERCISED`. A supported capability can be poorly evidenced; a confirmed gap can have high confidence.

Also record, cheaply: **ceremony** (concepts held, encodings produced, files touched, boot calls remembered). A `MATCH` with twice the ceremony is `BEHIND` in disguise. Trust the programmer — do not count "the programmer might misuse X" as a gap. Measure what helps a programmer decide: setup, repeated declarations, time to first useful result, feedback latency, recovery from an ordinary mistake, reproducibility, clarity of failure evidence, human/agent handoff. Actual timings need hardware, versions, cold/warm, and sample count; a single exploratory sample is an observation, not a performance claim. Code examples and annotated side-by-side steps often beat line counts.

**Honesty checks** the test must include, or it fails open:

- Spec-claimed vs code (use `ai/findings/story-review.md` as a prior on the test/runtime path). Code at trunk is ground truth for "today."
- "We have a primitive that *could* do this" vs "an author *does* this without ceremony."
- Built-in vs addon vs paid-service on the comparator side.
- A declared-input fingerprint is not a pixel comparison and not a visual approval service.
- A **negative control**: at least one job Story should *lose* or *refuse*, and at least one deliberate defect a claimed test must actually catch (a headless success cannot prove DOM interaction).
- A **positive control**: at least one job everyone agrees Story already does (frame isolation, or `force-fx-stub` if you verify it), so an all-red run is also suspicious.

**Do not let many minor successes cancel one essential failure.** If you compute a weighted reading, show the denominator, the weights (one-line defence each), exclusions, and how `UNVERIFIED` rows move it; give a range; say what the number is *not* (it is not a promise that a user will find Story pleasant). An honest categorical assessment — "adoption-blocking on J1, ahead on J4/J5, unknown on J12" — is preferable to a precise percentage over a weak evidence base.

**Automatable residue.** Name which jobs could be pinned by a structural test in `tools/story/test/` or by extending the `015-Test-Coverage.md` matrix, so that parity, once reached, is *guarded* rather than re-audited. Do not propose a gate for anything that needs a human judgement per run. A new permanent benchmark service is out of scope for this research.

**What the test is not:** a 200-row addon-name matrix; a recreation of `018`'s latency budget table (cite it; only reopen it if you find it unmeasurable); a walk of every panel under `tools/story/src/re_frame/story/ui/`.

Write the test so a future agent can re-run it: named jobs, named fields, named controls, named sources (spec path, code path, doc path, competitor URL), and a split between **the reusable protocol** and **this run's measurements**.

Deliver Phase B as `intermediate/closeness-test.md` **before** you apply it.

### Phase C — Apply the test to Story as it is

Run the Phase B test against **this** checkout.

For every job: walk Story from docs + spec + a spot-check of code (testbeds: `tools/story/testbeds/login_form/`, `counter_with_stories/`, `fresco_counter/`; examples: `examples/core/login/` + `login-with-stories`, `examples/patterns/nine_states/`). Walk the comparator from *current* docs, naming a second comparator when Storybook is the wrong one (workspaces → Histoire/devcards; preview-as-docs → Lookbook). Assign the four fields + ceremony notes + citations. Pull UI source only when a job's status depends on it.

**You must execute a small consequential subset, not merely read about it.** Separate the protocol from what you actually obtained. The minimum, unless a named limitation blocks it:

1. **First-hour stall log.** Follow `docs/story/01-first-variant.md` (and the install path it assumes) *literally*. Record every place it stalls, skips a boot call, or uses a retired spelling.
2. **Authoring / controls in the real shell.** A testbed in the browser: edit a control, see the canvas update, note feedback latency as an observation.
3. **Stateful async or isolation.** Side-by-side states, or a loading/error/retry journey, including at least one reset or stale-async check.
4. **Failure diagnosis.** A deliberate failing assertion → evidence → cause. Distinguish restoring state from replaying effects.
5. **Agent loop, even if only source-traced.** Name the tools from `tool-descriptors.edn`. If you cannot drive MCP, mark J12 `UNVERIFIED` rather than `AHEAD`.

If the environment cannot open a browser, that is a limitation on the UI-shaped jobs — not a `COMPLETE` and not an `ABSENT`. Do not invent timings or imply execution. Do not "fix" what you find.

Produce a **small** scoreboard. The interesting output is layer disagreement (`COMPLETE` + `UNDERMARKETED`, `SPEC-ONLY` + comparison `AHEAD`, `DIVERGENT` with a weak "other way") and the jobs that would bounce a Storybook user.

Appendix, not the main table: current status of the May audit's named gaps, with commit/bead if closed.

Deliver Phase C as `intermediate/scoreboard.md`.

### Phase D — The advantage thesis (this is question 2)

Now, and only now, write how Story is *better* — or would be. Generate candidates from *real friction you just measured* and from what re-frame2 makes unusually simple. Then try to **disprove** each one against the competitor's strongest practical workflow (including addons and paid extras).

Structure:

1. **Jobs the comparators do poorly because of substrate**, not because they haven't shipped a plugin yet. Candidates to verify or drop: stateful isolation; "this example is also the test"; "mock any effect not just HTTP"; "the failure has an epoch tape"; "the artefact an agent reads is the artefact the human authored"; "many states at once"; "upgrade fidelity in place"; "machines / resources / fresco as first-class"; the specific probes in §4.3 (machine-derived variants, epoch-as-variant, CSS/focus/portals, restore vs replay). Drop any candidate you cannot tie to a real job, or that a competitor already covers idiomatically.
2. **Which of those Story has productized** (a user can do it without knowing the substrate name) vs **which remain latent**. Evidence tier on every claim: `BUILT` / `SPECIFIED` / `IMAGINED`.
3. **The small set of primitives that should compose into more power than Storybook**, restated in *user* language. `018` §3 already lists one such set (selected variant, explicit inputs, script, expectations, evidence). If you keep it, say why it is still the set. If you replace it, say which job the current set fails.
4. **Limits of the data-shaped artefact.** What happens when real external I/O, clocks, or non-serialisable behaviour are involved? Do not invent elaborate machinery to paper over those limits without a concrete user need.
5. **What "better" looks like in a year**, as a handful of scenes (a design-system maintainer, an app developer, an agent, a tester) — not as a roadmap of panels. Each scene should be impossible, or humiliating, in Storybook *for a structural reason*, or you should drop it. Include the agent loop it should run, with which current MCP tools get it how far, and which missing pieces are cheap.
6. **What we should stop copying.** `018` §3.1 already names addon sprawl, opaque decorator chains, visual examples disconnected from app state, separate encodings for stories/tests/fixtures/repros/traces. Confirm, extend, or dissent — with evidence from Phase A/C, not taste.

For each promising idea: a concrete before/after authoring or debugging sketch, a small idiomatic API/data shape where that helps (labelled as proposed, not as shipping), a falsifiable hypothesis about user benefit, what re-frame2 contributes, and what it costs (concepts, coupling, configuration, runtime weight, maintenance). **A proposal that adds a concept must say what it removes.**

Select **roughly three to five opportunities** worth pursuing. Explain why other plausible ideas should wait or be declined. It is a valid outcome that Story already has the right capability and needs teaching, or that a competitor is ahead on a job we care about.

Gold-plating check, applied to every "we should also…": does this add a concept, a second encoding, or a ceremony the programmer did not ask for? If yes, it is not better. Kill it in the report, with one sentence of reasoning, rather than litigating it into a recommendation.

Deliver Phase D as `intermediate/advantage.md`.

### Phase E — Recommendations

A short, ranked list. Rank by *job leverage per concept added* and by evidence, not by how much a Storybook user would nod. Cluster symptoms that share a remedy. Separate measured defects from research hypotheses. Offer a sequence for the next few improvements, a **stopping point**, and what would change the ordering.

Categories:

1. **Keep measuring** — the standing test (Phase B) as an artefact: where it lives, how often to re-run, what invalidates a row (Storybook major, Story UI EPIC, tutorial rewrite, a P1 honesty failure like `story-review.md`). Name the automatable residue.
2. **Make claimed wins honest** — `SPEC-ONLY` rows and any `AHEAD` that rests on a dishonest surface. These outrank new features.
3. **Make shipped wins discoverable** — `UNDERMARKETED` / `INTERNAL` rows. Often a docs/tutorial/skill change, not a feature.
4. **Close real `BEHIND`s** that would bounce a Storybook user — only those. Pair each with the smallest Story-shaped fix, not the Storybook-shaped one.
5. **Productize latent advantages** — the Phase D items that are substrate-true but not yet a user-visible path. Smallest productization, not a new subsystem.
6. **Reaffirm refusals** — `DIVERGENT` / `OUT` items that should stay dead, so a future worker does not "close the gap."
7. **Do not build** — explicit anti-recommendations. This list is as important as the build list. The stance's second half lives here.

Each build recommendation: one title; the job id(s); the concrete problem and who hits it; evidence and confidence; the smallest coherent change; a before/after sketch; what it **removes** as well as adds; likely owning surfaces; guessed size (S/M — no L unless a smaller cut cannot serve the job); a testable acceptance criterion; related beads if any. Do not file beads. Do not assign P1/P2 unless you are ranking *within this report*.

If a recommendation would touch a hot-zone file (`spec/007-Stories.md` is sequential with other Spec 007 work; `spec/Conventions.md`, `spec/API.md` + manifests, `.github/workflows/*` are sequential), say so. That is sequencing information, not a reason to skip the recommendation. Where a recommendation challenges a locked design, state the tradeoff and the new evidence; one paragraph, then move on.

---

## 6. Report shape

Write the report as if a sceptical maintainer will use it, in one sitting, to decide what to measure and what to build next. Lead with the answers. No throat-clearing. Do not restate the tutorial. Do not write a README for Story. Link intermediate files instead of pasting them. Prefer useful depth over encyclopedic coverage.

```
ai/findings/Story/grok/
  prompt.md                 ← this brief; do not edit
  report.md                 ← the deliverable
  intermediate/
    landscape.md            ← Phase A
    closeness-test.md       ← Phase B (the standing test / reusable protocol)
    scoreboard.md           ← Phase C (this checkout, this date, including May-gap appendix)
    advantage.md            ← Phase D
    evidence-ledger.md      ← claims you verified or refuted; citations
```

`report.md` structure (headings you may rename; sections you may not skip):

1. **Answers in brief** — how we test closeness (pointer to the standing test + this run's headline, categorical not theatrical); how we are / could be better (the thesis in one screen); the one thing you would do first.
2. **This run's scoreboard** — the job table with the four fields. Date, timezone (`date "+%Y-%m-%d %H:%M:%S %Z"` or Windows equivalent), trunk SHA, relevant local changes, comparator versions/URLs, what you executed vs only read.
3. **What the May audits got wrong or went stale** — named gaps only, cited. Do not recap the 33-axis table.
4. **Advantage thesis** — condensed from Phase D; every claim tagged `BUILT` / `SPECIFIED` / `IMAGINED`.
5. **Recommendations** — ranked, in the seven categories above. Include the do-not-build list, the sequence, and the stopping point.
6. **How to re-run the test** — a procedure a future agent can follow without this prompt. Inputs, jobs, fields, controls, outputs, invalidation rules, automatable residue.
7. **What you did not do** — browsers not walked, suites not run, MCP not driven, tools not opened, claims not verified. A report that hides its bounds cannot be a test.

The report must make it easy to distinguish: what exists and works now; what is only source-traced or documented; what failed, under which conditions; what remains unknown; what is proposed, why it would help, and how that benefit would be tested.

House style:

- Citations as repo paths with enough specificity to re-find (file, section, or symbol). Competitor claims as URL + access date.
- Status words from Phase B, consistently. Do not reintroduce `WIN` as a row status.
- No AI attribution, no "as an AI," no trailer.
- Timestamp with timezone on the report header.
- If you quote the stance, quote it verbatim.
- Put this brief's path at the top of the report so a reader knows what was asked.

---

## 7. Failure modes for *this* assignment

These are how a report of this kind fails. Check yourself against them before you stop.

- **Noun matching.** Scoring "do we have Controls / Actions / Viewport / MDX / Chromatic / `preview.ts`." If a row is a competitor noun rather than a job, delete it.
- **Quiet narrowing.** Scoring only Story's strong jobs; labelling a missing outcome `DIVERGENT` because we refused a shape.
- **Unfair ecosystem.** Story+Xray+MCP against bare Storybook; or Chromatic treated as a free Storybook feature.
- **Spec as reality.** Counting TARGET / BLOCKED / unimplemented API.md rows as `COMPLETE`.
- **Substrate as product.** "re-frame2 has frames, therefore Story is `AHEAD` on isolation" without a user-visible path, or without CSS/focus/portals.
- **Fingerprint as visual regression.** `snapshot-identity` scored as Chromatic.
- **Stale competitor.** Using May 2026 Storybook notes as if they were current; assuming peers have no AI/MCP surface.
- **Retired vocabulary.** Copying an old spelling from a May finding and calling it a gap.
- **Re-doing story-review.md.** Implementation P1s are in scope only as honesty constraints on claimed wins.
- **Reopening refusals for taste.** Chrome palette, fonts, first-party VR service, addon marketplace.
- **Gold-plated matrix.** More than ~18 jobs, or a second taxonomy beside jobs. Cut until a re-run is cheap.
- **A single percentage over weak evidence.** Many minor `MATCH`es cancelling one adoption blocker.
- **Advantage-as-inventory.** A list of re-frame2 features is not a thesis. Mixing `BUILT` / `SPECIFIED` / `IMAGINED` in one sentence.
- **Manufactured novelty.** Claiming a competitor cannot do a thing because its core README does not mention it.
- **Recommendations that add encodings.** A new "story format" beside EDN variants, a new test runner beside `run-variant`, a new debugger beside Xray. A proposal that adds a concept and does not say what it removes.
- **Implied execution.** Timings, UI judgements, or MCP loops you did not run.
- **Quiet dependence on sibling reports.** If you read `fable/` or `astra/` before drafting, say so.
- **Pipeline-green theatre.** If you run a gate, do not pipe it through `tail`/`head`/`grep`; capture the runner's own exit code; believe `gate root:` if printed.

---

## 8. Practical constraints

- **Read-only** on the git-tracked tree. Scratch files only under `ai/findings/Story/grok/`. Isolated throwaway probes are fine; do not leave them in tracked paths.
- **No `bd` writes, no commits, no `git add` under `ai/`.** Reading beads is in scope; mutating them is not.
- You may `git rev-parse`, `git log`, and read files. You may web-search and open competitor docs.
- You may run small, targeted probes (a single namespace load, a grep with a positive control, a testbed in the browser) if a status depends on it. You may not start a multi-hour suite "for completeness." Prefer existing harnesses.
- If you are in a worker worktree, run the worker-worktree guard before *any* edit — but this brief should not require edits outside `ai/`. If you find yourself wanting to edit spec or code, you have left the assignment; put it in recommendations instead.
- Windows/MSYS instrument traps in `CLAUDE.md` apply if you search or count: use the ripgrep-backed search tool rather than bash `grep`; do not pipe censuses through `head`; `jq` writes CRLF here (`tr -d '\r'` before joining ids); do not trust a zero without a positive control. Web fetches: if a page cannot be fetched, say so rather than filling in from memory.

---

## 9. Done when

You can hand a maintainer `report.md` and they can:

- Re-run the closeness test in a later session without this prompt.
- See, in one table, Story's capability, discoverability, evidence, and comparison — including `SPEC-ONLY` pretence and `UNVERIFIED` unknowns.
- Repeat the advantage thesis in their own words, with tiers, and name the three-to-five opportunities plus the declined list.
- Act on a short ranked list that includes explicit do-not-build items, a sequence, and a stopping point.

Before finishing, challenge your own conclusions: Did you verify the loudest claims? Did you compare complete workflows fairly, including ordinary extras? Did UI-shaped jobs see a browser? Did you mistake a hosted service for built-in, a fingerprint for visual regression, or an existing primitive for a finished workflow? Have you recommended the smallest powerful design, and have you explicitly declined attractive work that does not earn its cost?

If the report is long because the intermediate files are copied into it, it is not done — link to them. If the report is short because it only restates `018` §3.1, it is not done — the test has to have been *applied*, including the executed subset.
