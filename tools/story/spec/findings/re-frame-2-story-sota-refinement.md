# re-frame-2-story — SOTA Refinement (rf2-94b0)

Independent SOTA refinement of the re-frame-2-story feature set, compared against rf2-m6tu.
Bead: rf2-94b0 (P3). Date: 2026-05.

## §1 Method

This work was performed in two strictly-ordered phases.

**Phase 1 (independent, blind).** I surveyed component-development tools through a *state-of-the-art* lens — i.e. "what would make re-frame-2-story best-in-class?" rather than "what is the MVP?" I deliberately did **not** read `findings/re-frame-2-story-feature-set.md` (rf2-m6tu's output) during Phase 1. The independence is load-bearing for the §4–§5 comparison: any feature both passes name independently is high-confidence; anything one pass missed is signal worth examining.

**Phase 2 (compare).** After Phase 1 was committed to file, I read rf2-m6tu's findings and produced a per-feature comparison: convergence, blind spots on each side, and disagreements about priority.

The scope is component-dev tooling that ships UIs — Storybook (v9/v10), Histoire, Ladle, React Cosmos, Lookbook, devcards, Nubank Workspaces, re-com, Pattern Lab, Bit, Chromatic, plus adjacent visual-regression tools (BackstopJS) and the AI/MCP integrations that recently landed across the ecosystem. The lens stays SOTA throughout: I tried to identify capabilities that *exceed* current re-frame-2-story scope, not just match minimum-viable.

Constraints honoured: pure research, no implementation, no PR, no `decision-beads.md` edit, `findings/` is local-only.

## §2 Phase 1 — Independent SOTA inventory

### 2.1 Per-tool standouts

**Storybook v9.** The "test widget" is the differentiator: click-to-run interaction + a11y + coverage in a sidebar with status dots per story. Three further v9 capabilities are clearly SOTA-class: (a) interaction testing via the Vitest addon running stories in real browser mode (Playwright/WebdriverIO under the hood), (b) test codegen — record interactions in the canvas and emit assertions automatically, and (c) story-level globals (theme, viewport, locale, RTL) that compose with decorators without bespoke wrapping. v9 also halved bundle size (48% leaner), which is a tooling-quality signal more than a user feature.

**Storybook v10 (Oct 2025).** Five items stand out: (i) ESM-only + un-minified dist for debuggability; (ii) `sb.mock` for module automocking, Vitest-grade and uniform across dev and static builds; (iii) **CSF Factories** — type-safe factories that infer args/argTypes and let stories be reused as test fixtures without `composeStories`; (iv) QR codes in the share menu — quick mobile preview without devices on Wi-Fi being a pain; (v) story-level "Open in editor". Of these, CSF Factories is the one I'd most want to mirror in re-frame-2-story: it turns the story into a first-class testable artifact rather than a description of one.

**Storybook MCP (React, 2026).** This is the single highest-bar capability in the field right now. The server exposes three toolsets to AI agents: Development (`get-storybook-story-instructions`, `preview-stories`), Docs (`list-all-documentation`, `get-documentation`, `get-documentation-for-story`), and Testing (`run-story-tests`). The agent loop is: agent reads design-system manifest → generates a component → writes a story → previews it → runs interaction + a11y → reads failures → fixes → repeats. The "self-healing loop" framing is real and load-bearing; this is what turns Storybook from a workshop into an agent substrate. The companion `ds-mcp-experiment-reshaped` RFC adds component-manifest emission (component names, prop types, descriptions, examples) so agents stop reinventing shadcn-flavoured components instead of using the host design system. **This is what re-frame-2-story has to chase to be SOTA.**

**Chromatic + Story Modes.** Modes save combinations of globals (theme × viewport × locale) and apply them at project/component/story scope. Each mode has independent baselines and approvals. This is the cleanest answer in the field to combinatorial visual testing — historically people generated 27 stories by hand for 3 themes × 3 viewports × 3 locales. UI Review (1up/2up/diff views, comment pinning) and TurboSnap/SteadySnap baseline diffing round out the visual story.

**BackstopJS.** Standout: the **before/after snapshot scrubber** — drag a slider between before and after pixels. This is the interactive snapshot scrubber I went looking for. Storybook doesn't ship this natively; Chromatic's diff view is comparable but less tactile.

**Storybook addon ecosystem (essentials and best-in-class community).**
- **addon-a11y** (axe-core, official): violations inline + run in CI via Vitest addon
- **addon-interactions / play()**: scripted user flows with debug stepper
- **addon-coverage**: Istanbul/v8 with low/high watermarks (quality gates)
- **addon-test / Vitest addon**: 5–10× faster than the legacy test-runner, real-browser mode
- **addon-themes**: provider-decorator + global toolbar (JSX, class, attribute strategies)
- **addon-viewport**: device presets + custom breakpoints, iframe-resize
- **addon-measure / addon-outline**: layout-debugging without DevTools — measure draws ruler+gap overlay, outline draws Pesticide-style outlines
- **addon-pseudo-states**: forces `:hover/:focus/:active/:visited` via stylesheet rewrite; pairs with Chromatic for state-coverage snapshots
- **MSW addon**: network mocks per-story, composable at project/component/story scope
- **addon-designs**: Figma link in the panel; integrates with Code Connect
- **storybook-design-token (UX-and-I)**: parses Style-Dictionary-emitted CSS-custom-property docs into a panel
- **GitHub performance-panel / Atlassian addon-performance / addon-profiler**: FPS, INP, long tasks, CLS, DOM churn, React profiling, memory pressure — *per story*, every 50 ms while the story is active

**Composition / federation.** Storybook composition via `refs` in `main.js` is essentially HTTP-level story federation — you can browse a remote published Storybook inside your local one. This matters for design systems consumed by app teams.

**Portable stories.** `composeStories` / `composeStory` / `setProjectAnnotations` recreate the Storybook pipeline inside Vitest/Jest/Playwright. CSF Factories in v10 makes this seamless. The story-as-test-fixture concept is now first-class.

**Histoire.** Vite-powered, Vue/Svelte focus, dark mode + theming. Distinct from Storybook mainly by being lighter, but doesn't ship anything I'd call SOTA-exceeds-Storybook.

**Ladle.** Zero-config, CSF-compatible, Vite + esbuild + ESM + HMR per story, axe built-in. Optimised for *speed of feedback*. The standout is the "barely any framework" experience — one dependency, one command. This is a posture re-frame-2-story should at least consider.

**React Cosmos.** File-system fixtures (vs. CSF stories), static export, plugin system, framework-agnostic. The fixture-as-file model is interesting but the ecosystem and momentum are with CSF.

**Lookbook (Rails).** Notable for marrying preview + markdown docs + parameter editor + multi-viewport. Bridges to ViewComponent / Phlex / ActionView partials — analogous to how re-frame-2-story has to bridge Reagent 2, Hicada, vanilla Reagent forms 1/2/3.

**devcards.** Card-as-a-namespace ClojureScript-native ancestor; multiple component states on one page; figwheel-driven live reload. The "see many states at once" UX is something Storybook actually *doesn't* do well — Storybook is one-story-at-a-time. This is a legacy feature with revivable value.

**Nubank Workspaces.** Shadow-cljs-native, supports re-frame demo cards. The closest direct precedent for a CLJS-native workshop on top of re-frame. Less momentum than the JS ecosystem, but proves the model.

**re-com.** Day8's component library; demo app *is* the documentation, with linked source. Pattern is: clickable demo → live example → source view. Less a workshop, more a styled showcase.

**Pattern Lab.** Atomic-design nesting, "pattern lineage" (where is this used?), viewport tools, language-agnostic templating. Standout: **pattern lineage / X-ray vision** — showing where a component is composed into other components. Storybook doesn't do this natively.

**Bit.** Composable-component build system with cross-repo sharing, dependency tracking, AI/MCP integration, Ripple CI. Bit's pitch is closer to "monorepo for components" than "workshop"; the SOTA contribution is dependency-aware versioning of components.

**re-frame-10x.** Day8's own debugging dashboard. Epoch-oriented inspection, time-travel via `app-db-follows-events?`, event replay, fully-qualified mutation API (`load-epoch`, `previous-epoch`, `next-epoch`, `replay-epoch`, `reset-app-db-event`). This is *the* nearest analogue inside the re-frame ecosystem to a SOTA observability ribbon — and it ships features Storybook doesn't have (epoch buffer, replay, time-travel against app-db). re-frame-2-story should make 10x integration a marquee feature.

### 2.2 Cross-tool synthesis — what's SOTA right now

Reading across the inventory, ten patterns recur:

1. **Agent-substrate posture** — the workshop emits a machine-readable manifest (components, props, examples, design tokens) and exposes MCP tools so AI agents do the building. Storybook MCP + ds-mcp-experiment is the clearest example. Bit is the second. Everyone else is behind.
2. **Self-healing test loops** — agent writes story → runs interaction + a11y → reads failures → fixes. Requires the test runner to be addressable from outside the UI, and failures to be expressed in agent-readable form.
3. **Modes / globals as combinatorial test matrix** — themes × viewports × locales × RTL × density, with each cell independently baseline-able. Chromatic Story Modes is the SOTA.
4. **Snapshot scrubber UX** — drag a slider between before/after pixels (BackstopJS), with comment-pinning on diffs (Chromatic UI Review).
5. **In-canvas observability** — performance panel updating live (FPS, INP, long tasks, memory, React profiling) per story, every 50 ms. GitHub's addon is best-in-class here.
6. **Layout-debug overlays** — measure (gap+ruler) and outline (Pesticide) without leaving the canvas.
7. **Pseudo-state forcing** — `:hover/:focus/:active` forced visually, capturable as snapshots.
8. **Story portability** — story-as-test-fixture, runnable in Vitest, Playwright, Jest with no rewrite (CSF Factories).
9. **Federation / composition** — remote Storybook stories appearing in local sidebar.
10. **Pattern lineage** — where is this component used? cross-component dependency graph.

The features re-frame-2-story (or any new workshop) should consider but I don't see anywhere *yet*:

- **Time-travel scrubber driven by re-frame epochs.** BackstopJS scrubs pixels; 10x scrubs events. Combine the two and you get a story that's a recorded epoch sequence you can scrub through, see app-db at each step, and re-dispatch from any frame. *Nothing in the JS ecosystem has this because there's no app-db substrate to time-travel against.*
- **Hot-swap handlers from agent**. MCP for handler/effect/sub registration, not just stories.
- **App-db differential snapshot.** Snapshot `app-db` at story-end and diff against a baseline — the data-layer equivalent of Chromatic.
- **JVM-side story rendering for tests.** Reagent SSR via JVM interop → run the workshop's "snapshot at this state" check headless on the JVM, faster than headless browser.

## §3 Top-N SOTA capabilities (Phase 1's prioritised list)

Ranked by ship-or-be-left-behind for a 2026 component workshop:

1. **MCP server + component manifest** — agent discovers components, reads props/docs, generates stories, runs tests, reads failures, fixes. This is the table-stakes-becoming-marquee feature; without it re-frame-2-story is yesterday's news.
2. **Real-browser interaction testing** with play()-style script + assertion API (analogous to Storybook's Vitest addon). Stories *are* tests, runnable headlessly in CI.
3. **a11y (axe) baked in.** Run inline in canvas; run in CI; fail PRs on regressions. Non-negotiable in 2026.
4. **Story-level globals + Chromatic-style Modes.** Theme × viewport × locale × density combinatorial matrix with independent baselines per cell.
5. **Visual regression with snapshot scrubber.** Even if rf2-story doesn't host pixels, it must emit stable iframes so Chromatic/Percy/Argos/BackstopJS can target it.
6. **In-canvas performance panel** — FPS, INP, long tasks, layout thrashing, memory. Live per story.
7. **Layout-debug overlays** — measure, outline, pseudo-state forcing, design-token panel.
8. **CSF-Factory-style story-as-test-fixture.** A story imported into a test namespace runs with all decorators applied. No `composeStories` boilerplate.
9. **MSW-style network mocking per story.** Composable at project/component/story scope.
10. **10x integration as observability ribbon.** Epoch buffer surfaced in canvas; replay-from-here button; app-db scrubber. *This is the differentiator only a re-frame workshop can ship.*
11. **Module mocking for fx/sub/event handlers.** Story declares `:overrides {:reg-fx [...] :reg-sub [...]}`; story-local registry mutation.
12. **Federation** — `refs` to another deployed re-frame-2-story instance.
13. **Pattern lineage** — where is this component composed?
14. **App-db snapshot diff** — re-frame-specific take on visual regression, in data space.
15. **Story sharing UX** — QR codes, embed URLs, oEmbed for Notion.

Items 1–9 are what every JS workshop now has or is racing toward. Items 10–14 are the re-frame-native SOTA opportunities — what gives the workshop a reason to exist beyond "Storybook for CLJS".

---

## §4 Phase 2 — comparison vs rf2-m6tu

(Phase 1 ended above; I now read `findings/re-frame-2-story-feature-set.md`. Important update: rf2-m6tu is anchored to spec/007-Stories.md and the existing re-frame-2 Tool-Pair primitives — it's substantially more re-frame-2-native than the "minimum-viable" framing I expected. The comparison below reflects what rf2-m6tu actually says, not what I imagined it would say.)

### 4.1 What rf2-m6tu actually covered

rf2-m6tu is a 5,200-word per-tool survey + cross-tool synthesis + tiered priority list + re-frame-2-specific extensions + v1 feature spec + open questions, all cross-referenced to spec/007. Its outline:

- §1 per-tool survey (Storybook, Histoire, Ladle, React Cosmos, Lookbook, devcards, workspaces, Pattern Lab) — same tools Phase 1 covered
- §2 cross-tool patterns: converged (data-shaped stories, three-level args, decorator wrappers, autodocs, tags) and one-tool-only (MCP, atom-time-travel, workspace grid, pattern lineage, composition)
- §3 prioritised tiered list (Tier 1 v1, Tier 2 v1.1, Tier 3 v2)
- §4 re-frame-2-specific extensions: EDN-first format, frame-as-actor-boundary, trace-as-output, Tool-Pair integration, multi-substrate rendering, DCE under :advanced, additive contract, MCP, sub-topology lineage, SSR
- §5 proposed v1 feature spec (authoring surface, runtime surface, panels, workspace UI, MCP, tests, what v1 does NOT ship)
- §6 seven open questions for Mike (MCP in/out of tree, substrate failures, assertion fail-mode, loader completion for long-lived fx, workspace persistence, DCE + cross-lib :extends, 10x integration)
- §7 cross-reference table to spec/007 plus seven recommended spec/007 additions

This is substantially deeper and more re-frame-2-specific than I expected when I wrote §3 above. The comparison below reflects that.

### 4.2 Per-feature comparison

**Strong convergence (both passes name independently):**

| Feature | Phase 1 | rf2-m6tu |
|---|---|---|
| MCP / agent integration as SOTA priority | Top-1 | §1.1 + §2.2 + §3.2 + §4.8 + §5.5 — "Critical to ship at v1" |
| a11y (axe) panel inline + CI | Top-3 | Tier 1 #3 — "Cheapest win in the space" |
| play()-style scripted interactions + assertions | Top-2 | Tier 1 #2 + spec/007 `:rf.assert/*` vocabulary |
| Visual-regression as external integration (snapshot identity, no built-in pixel service) | Top-5 | §5.7 "what v1 does NOT ship" — "snapshot-identity hook only" |
| Story-level globals (theme/viewport/locale/RTL) | Top-4 | §2.1 — three-level args hierarchy |
| MSW-style network/effect mocking per story | Top-9 | §2.3 — "spec/007's `force-fx-stub` more general than MSW" |
| Module mocking (fx/sub/event override) | Top-11 | §5.3 fx-override panel + spec/007's three-kind decorator |
| re-frame-10x integration / epoch scrubber | Top-10 | Tier 1 #5 + §4.4 + §6.7 — "embed 10x's epoch panel as a reg-story-panel" |
| Story portability (as test fixture) | Top-8 | §3.2 + §5.2 `run-variant` returns `{:frame :app-db :assertions ...}` |
| Federation / composition across libraries | Top-12 | §2.2 + §5.7 deferred ("registrar handles automatically") |
| Autodocs (CSF/EDN-shaped + docstrings) | Top-13 (implied) | Tier 2 #7 + §2.1 |
| Tags for inclusion (autodocs/test/dev/agent) | implied | §2.1 + spec/007's seven canonical tags |

**rf2-m6tu's blind spots — what Phase 1 caught that rf2-m6tu missed or under-weighted:**

1. **In-canvas live performance ribbon** (FPS, INP, long tasks, CLS, memory, React/Reagent profiling, 50 ms refresh, per-story). rf2-m6tu touches performance only in passing (the "~70 MB" Storybook size critique). Phase 1 identifies live perf ribbons (GitHub's, Atlassian's, addon-profiler) as a SOTA pattern. **Recommendation: add to rf2-m6tu's Tier 2 or Tier 3.**

2. **BackstopJS-style snapshot scrubber UX** (drag a slider between before/after pixels). rf2-m6tu treats visual regression as a snapshot-identity hook (correctly) but doesn't name the *scrubber interaction* as an explicit UX target — and Phase 1 specifically calls out applying that interaction to **epochs**, not just pixels. The crossover idea (BackstopJS scrub × 10x epochs = scrub through events) is not in rf2-m6tu.

3. **Layout-debug overlay trio** (measure + outline + pseudo-state forcing). rf2-m6tu doesn't list these as first-party panels in §5.3. Phase 1 names them as bundled essentials. **Recommendation: add a "layout-debug" panel to rf2-m6tu §5.3's first-party panel list.**

4. **Design-token panel** with Style-Dictionary-style input. rf2-m6tu mentions Spec 010 schemas drive controls but doesn't name a design-token panel as a separate surface. Phase 1 flags this because three addons in the JS ecosystem are converging on it as table stakes.

5. **Chromatic Story Modes as a combinatorial primitive.** rf2-m6tu treats themes/viewports/locales as args + decorators (correct mechanics), but doesn't name the *mode-as-saved-combination* abstraction with independent baselines per cell. Phase 1 calls it the SOTA solution to combinatorial visual testing.

6. **devcards' "many states at once" viewing mode** — render multiple variants on one page side-by-side. rf2-m6tu names devcards' time-travel as the unique borrow but skips the "many cards in one view" UX. **Recommendation: rf2-m6tu's `:grid` workspace layout *can* express this, but it's not called out as a viewing mode for one story's variants.**

7. **Story-sharing UX** — QR codes, oEmbed for Notion, embed URLs. rf2-m6tu §5.7 says "static export" is deferred. Phase 1 picks up the sharing-UX touches as 2026 polish.

8. **App-db snapshot diff** (data-space visual regression). Both passes float this speculatively; neither commits to v1. Convergent omission.

**Phase 1's blind spots — what rf2-m6tu nailed that Phase 1 under-weighted or missed:**

1. **EDN-first variant artefact contract** (§4.1) — variants round-trip as data; no `:render` fn slot. This is **more restrictive than CSF Factories** and is the foundation for MCP discovery, network shipment to visual-regression servers, and AI input pipelines. Phase 1 thought "CSF Factories is SOTA"; rf2-m6tu correctly identifies that *pure EDN* is one step beyond, and re-frame-2 can ship it because there's no JSX-equivalent escape hatch.

2. **Frame-as-actor-boundary per variant** (§4.2) — each variant is its own frame; state leaks between variants are impossible by construction. Phase 1 didn't see this because it's an artifact of re-frame-2's frame primitive, not a Storybook-borrowable pattern. **rf2-m6tu is right that this is load-bearing — no JS tool can match it.**

3. **Six-domino trace per variant** (§4.3) — the trace panel renders event → handler → fx → effect → subscription → re-render live. Phase 1 mentioned re-frame-10x's observability ribbon generically; rf2-m6tu specifically commits to embedding the trace surface as a first-party panel. **This is the debugging UX no JS tool can match because no JS framework has the structured trace.**

4. **Sub-topology lineage** (§4.9) — `sub-topology` already exposes the dependency graph; rendering it for the active variant is "pattern lineage for free." Phase 1 named Pattern Lab's lineage as a SOTA borrow but missed that re-frame-2 has the *machinery* to deliver it cheaply.

5. **Multi-substrate variant rendering** (§4.5) — same variant rendered against reagent / uix / helix / reagent-slim side-by-side. This is unique to re-frame-2 because of the multi-substrate goal in the EPs. Phase 1 entirely missed this.

6. **DCE under `:advanced`** (§4.6) — story registrations elide in production builds via a `:rf.story/enabled?` compile-time flag. Phase 1 didn't address production-build hygiene for the workshop.

7. **Strict additive contract** (§4.7) — adding/removing/renaming stories never breaks the app. Phase 1 was framework-neutral; rf2-m6tu connects this to spec/007's library-owned `:story.*` prefix and the EP-level "no new registries" discipline from project memory.

8. **SSR-aware variant tagging via `:platforms`** (§4.10) — server variants run server-render-pane; client variants run live-frame-pane. No JS tool ships dual-pane SSR storytelling. Phase 1 floated "JVM-side story rendering" as a speculative aside; rf2-m6tu makes it a concrete spec-007 feature.

9. **The :extends composition + content-hashed snapshot identity** for cheap variant inheritance and O(1) "what changed since last snapshot" agent diffing. Phase 1 missed both.

10. **The seven open questions** rf2-m6tu surfaces (§6) are design-decision-shaped, not feature-shaped — Phase 1 didn't produce comparable design questions. These are where the real design work lives:
    - MCP in/out of tree (recommendation: separate jar)
    - Substrate-specific render failures (recommendation: render inline)
    - Assertion fail-mode (recommendation: record-don't-throw)
    - Long-lived loader completion (recommendation: `:loaders-complete-when` predicate)
    - Workspace persistence (recommendation: local + opt-in transit)
    - `:advanced` DCE + cross-lib `:extends` (recommendation: dev-only registration)
    - 10x integration shape (recommendation: embed 10x as `reg-story-panel`)

11. **Tier-3 v2 features** rf2-m6tu plans: per-substrate variant filtering, custom story panels, static export. Phase 1 didn't structure into v1/v1.1/v2.

### 4.3 Disagreements where the passes prioritise differently

| Area | Phase 1 (94b0) | rf2-m6tu | Recommended resolution |
|---|---|---|---|
| **MCP priority** | Top-1, marquee | Tier 1 must-ship for v1 + dedicated §4.8 + §5.5 | Agree. Both pass on this. Phase 1 over-stated rf2-m6tu's de-emphasis on first read; it's actually equally weighted. |
| **10x integration** | Surface epoch buffer in canvas as first-class scrubber UX | Embed re-frame-10x's existing epoch panel as a `reg-story-panel` (§6.7) | Adopt rf2-m6tu — re-using 10x's existing UI is better than reimplementing. The scrubber-with-app-db-diff Phase 1 wanted is *already* what 10x's epoch panel does; the answer is "embed it." |
| **Visual regression** | External (Chromatic/Percy/Argos/BackstopJS) | External (snapshot-identity hook; no first-party service) | Agree. Both converge. |
| **Performance** | Live in-canvas ribbon (Phase 1 priority) | Not specifically listed | **Disagreement: add to rf2-m6tu Tier 2.** Phase 1 is right that a live ribbon is now SOTA-table-stakes. |
| **devcards "many states at once"** | Preserve as opt-in viewing mode | Not explicitly named (but `:grid` workspace can express it) | **Add to rf2-m6tu §5.4** — "a workspace can render multiple variants of one story side-by-side as a 'many states' view." |
| **Pattern lineage** | Worth shipping | §4.9 sub-topology lineage (re-frame-2's analogue) is in Tier 3 v2 | Agree. Defer to v2; not a v1 blocker. |
| **Story format** | CSF Factories as inspiration | EDN-first, more restrictive than CSF, no render-fn slot | **Adopt rf2-m6tu**. EDN-first is strictly stronger than CSF Factories for MCP and SSR pipelines. |
| **JVM rendering / SSR** | Speculative aside | First-class via `:platforms #{:server :client}` (§4.10) | **Adopt rf2-m6tu**. The dual-pane SSR storytelling is a leadership opportunity. |
| **Federation** | High priority (composition is SOTA) | Defer to v2 (registrar handles multi-lib automatically; remote-Storybook composition is v2) | **Adopt rf2-m6tu**. Phase 1 over-weighted; cross-library local federation is already free in re-frame-2's registrar; remote-host federation is genuinely v2. |
| **Modes-as-saved-combinations** | SOTA primitive | Three-level args + decorators (mechanically equivalent but unnamed as a primitive) | **Compromise: add a "modes" abstraction layer.** Saved (theme × viewport × locale) tuples with independent snapshot identities. Cheap to layer on top of rf2-m6tu's existing args mechanism. |
| **Layout-debug overlays** | First-class trio (measure / outline / pseudo) | Not listed in §5.3 panels | **Add to rf2-m6tu §5.3.** Cheap to ship as DOM-mutating addon-style panels; equivalent JS-side maturity. |
| **Design-token panel** | First-party | Not listed | **Add to rf2-m6tu §5.3** if re-com or downstream lib emits Style-Dictionary-shaped tokens. Otherwise defer. |
| **Story sharing UX** | QR + oEmbed | Static export deferred to v2 | **Add QR for current variant** as v1 polish; defer oEmbed to v2. |
| **App-db snapshot diff** | Speculative v2 | Not mentioned | **Mark as v2 roadmap.** Both agree by omission; worth flagging for the future. |
| **Substrate-portability filtering** | Not mentioned | Tier 3 v2 (§3.3 #15) | **Phase 1 blind spot — adopt rf2-m6tu.** Side-by-side reagent vs uix vs helix vs reagent-slim render is unique to re-frame-2. |

## §5 Convergence + divergence

### 5.1 High-confidence ship list (both passes converge — these are settled)

These twelve items get an independent two-vote endorsement; treat them as decided:

1. **MCP server + component manifest** with the Dev / Docs / Testing toolset shape (Storybook MCP-style). rf2-m6tu §4.8 specifies the surface in re-frame-2 terms (`list-stories`, `get-variant`, `run-variant`, `snapshot-identity`, etc.).
2. **a11y (axe-core) panel** inline + CI integration.
3. **Play-style scripted interactions + structured assertion vocabulary** (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, `:rf.assert/dispatched?`, `:rf.assert/state-is`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`).
4. **External visual-regression integration** via `snapshot-identity` hook; no first-party pixel service.
5. **Three-level args + auto-derived controls** from Spec 010 schemas.
6. **MSW-shaped effect mocking** via `force-fx-stub` decorator (re-frame-2's generalisation is strictly stronger than MSW because *any* fx is mockable, not only HTTP).
7. **Six-domino trace panel** per variant via `register-trace-listener!` (Phase 1 missed how cheap this is; rf2-m6tu got it).
8. **re-frame-10x epoch panel embedded** as a `reg-story-panel` (rf2-m6tu's §6.7 recommendation is the right shape — no reimplementation needed).
9. **Story portability** — `run-variant` returns `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}`, runnable in cljs.test and JVM-CLJS contexts.
10. **EDN-first variant artefact** — no `:render` fn-slot, round-trippable as data. This is *stronger* than CSF Factories.
11. **Tags for inclusion** — the seven canonical tags from spec/007 (`:dev`, `:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`, `:agent`) with Storybook's `!`-prefix removal syntax.
12. **Workspace grid + transit-shareable layouts** (Nubank-style). rf2-m6tu owns this; Phase 1 didn't see it as deeply.

### 5.2 Phase-1 additions to rf2-m6tu's spec (the actual refinement deliverable)

These six items Phase 1 surfaced as SOTA that rf2-m6tu did *not* explicitly land. Each is a concrete edit-the-spec recommendation:

1. **In-canvas live performance ribbon** — FPS, INP, long tasks, CLS, memory pressure, Reagent-render profiling. Updates every 50 ms while a variant is active. Per-variant baseline. **Add to rf2-m6tu §5.3 as a first-party panel; tier as v1.1.** GitHub's, Atlassian's, and addon-profiler's convergence shows this is now SOTA-table-stakes.

2. **Layout-debug overlay trio** — measure (rulers + gap visualisation on hover), outline (Pesticide-style), pseudo-state forcing (`:hover/:focus/:active/:visited` via class-swap). **Add to rf2-m6tu §5.3 as a "layout-debug" panel; tier as v1.** All three are pure DOM concerns; framework-agnostic.

3. **Mode primitive** (Chromatic Story Modes) — a named saved combination of globals (theme × viewport × locale × density × RTL) appliable at story/component/project scope with **independent snapshot identities per cell**. rf2-m6tu has args+decorators that *can* do this, but doesn't surface "mode" as a saved-tuple abstraction. **Recommend: add `reg-mode` to the authoring surface; cells generate independent snapshot identities for free.** This is the cleanest answer in the field to the combinatorial-baseline problem.

4. **"Many variants at once" viewing mode** (devcards revival) — a workspace layout that renders all variants of one story side-by-side, no per-cell sidebar interaction. rf2-m6tu's `:grid` workspace can express this; **make it a named-layout in §5.4** ("`:variants-grid` workspace layout"). Cheap; high UX win; no JS competitor.

5. **Design-token panel** — Style-Dictionary-shaped tokens emitted by the host project (or re-com's CSS-custom-property scheme) surfaced as a `reg-story-panel`. **Add to §5.3 if re-com or downstream design system emits tokens; otherwise mark as v1.1 dependent on token-emission upstream.**

6. **Variant-sharing UX touches** — QR code for current variant (v1 polish), "open in editor" per variant (v1.1, deferred to editor MCP), oEmbed URL for Notion-embed (v2). **Add the QR addition to §5.7 — the spec currently defers all static export to v2; the QR is small enough to be a v1 polish.**

### 5.3 rf2-m6tu items Phase 1 should adopt verbatim

These were Phase 1's blind spots; they're load-bearing in re-frame-2 context:

1. **EDN-first variant artefact contract** (§4.1) — strictly stronger than CSF Factories.
2. **Frame-as-actor-boundary per variant** (§4.2) — load-bearing for state isolation; only re-frame-2 can ship this.
3. **Sub-topology lineage** (§4.9) — pattern-lineage-for-free using `(rf/sub-topology)`.
4. **Multi-substrate variant rendering** (§4.5) — reagent/uix/helix/reagent-slim side-by-side.
5. **Strict additive contract** (§4.7) — adding/removing/renaming stories never breaks the app; flows from EP-level "no new registries" discipline.
6. **DCE under `:advanced`** (§4.6) — `:rf.story/enabled?` compile-time flag elides registrations.
7. **`:platforms #{:server :client}` dual-pane SSR** (§4.10) — first-class server-render-pane + client-frame-pane.
8. **The seven open design questions** (§6) — these are the real design work; the feature list is settled by comparison.

### 5.4 Open questions where Phase 1 + rf2-m6tu disagree on priority — recommended resolutions

| Disagreement | Resolution |
|---|---|
| 10x integration — scrubber UX vs. embed | Embed (rf2-m6tu wins; reuse 10x's existing UI). |
| Performance — in-canvas live ribbon vs. test-runner metric | Both, but ship the in-canvas ribbon as the SOTA marker (Phase 1's add). |
| devcards "many states" — preserve vs. omit | Preserve as `:variants-grid` workspace layout (Phase 1's add). |
| Pattern lineage — ship vs. defer | Defer to v2 as `sub-topology` panel (rf2-m6tu's tiering). |
| Story format — CSF Factories vs. EDN-only | EDN-only (rf2-m6tu wins — strictly stronger). |
| SSR — speculative vs. first-class | First-class via `:platforms` (rf2-m6tu wins). |
| Federation — high priority vs. v2 | v2 (rf2-m6tu wins; local multi-lib is free via registrar). |
| Modes — saved-tuple primitive vs. args+decorators only | Saved-tuple primitive added (Phase 1's add layered on rf2-m6tu's mechanism). |
| Layout-debug overlays — first-party trio vs. unlisted | First-party trio (Phase 1's add). |
| Design-token panel — first-party vs. unlisted | Conditional — first-party if upstream emits tokens (Phase 1's add, qualified). |
| App-db snapshot diff — v2 roadmap vs. unmentioned | v2 roadmap (Phase 1's add to roadmap, not v1 scope). |
| Substrate-portability filtering — unmentioned vs. v2 | v2 (rf2-m6tu wins; Phase 1 missed). |

## §6 Recommendations to refine the rf2-m6tu feature spec

Concrete edits I would push into rf2-m6tu, ordered by priority. Each is a *delta* on the existing spec rather than a rewrite — rf2-m6tu's foundation is sound and re-frame-2-native.

1. **Add §5.3 panel: "Performance (live)"** — FPS, INP, long tasks, CLS, layout writes, memory, Reagent-render profiling. 50 ms refresh while a variant is active. Per-variant baseline; failures bubble to assertion list. Tier as v1.1. Justification: SOTA-table-stakes in 2026; GitHub's, Atlassian's, addon-profiler's convergence proves the pattern.

2. **Add §5.3 panel: "Layout-debug (measure + outline + pseudo)"** — three DOM-mutating utilities, framework-agnostic, default-on as an opt-in panel. Tier as v1. Justification: Storybook's measure + outline are essentials; pseudo-state forcing is a small extension; all three together are best-in-class.

3. **Add a `reg-mode` authoring primitive in §5.1** — `(reg-mode :Mode.app/dark-mobile {:theme :dark :viewport :mobile :locale :en})`. Applied via `:modes [#{:Mode.app/dark-mobile :Mode.app/light-desktop}]` on story/variant/workspace. Each (variant × mode) pair has its own `snapshot-identity`. Mechanically equivalent to args + decorators rf2-m6tu already specifies, but the saved-tuple abstraction is itself the SOTA primitive — addonable visual-regression services can iterate the cross-product cleanly. Tier as v1.

4. **Add §5.4 workspace layout: `:variants-grid`** — render all variants of one story side-by-side in a responsive grid, no per-cell sidebar interaction needed. Reuses workspaces' `:grid` plumbing. Tier as v1. Justification: devcards' "many states at once" UX is genuinely unique; no JS workshop ships it.

5. **Add §5.3 panel: "Design tokens (conditional)"** — surfaces tokens emitted by upstream (re-com or host design system) in Style-Dictionary or CSS-custom-property form. Iff upstream emits tokens. Tier as v1.1, conditional. Justification: three Storybook addons converged on this; the pattern is well-defined; rf2-m6tu should at least scaffold the panel shape.

6. **Add a v1 polish detail in §5.7**: **per-variant QR code in the share menu** — Storybook v10 popularised this. Mobile preview without Wi-Fi pain. Tiny implementation; high signal.

7. **Add a v2 roadmap entry: "app-db snapshot diff"** — data-space visual regression. Snapshot `app-db` post-loaders; diff against baseline; pure-data analogue to Chromatic pixel diff. Mark explicitly as v2, not v1. Justification: unique to re-frame-2's data-centric model; no JS analogue exists.

8. **Promote MCP to *the* marquee feature in the spec's intro paragraph.** rf2-m6tu correctly lists it as Tier 1 v1 must-ship, but the abstract/intro frames the work as "feature-set research" generically. The single most consequential 2026 framing for any new component workshop is "agents can build for this." Make that the opening sentence of §5's v1 proposal.

9. **Tighten §6.1's MCP-tree question with a concrete recommendation**: separate jar (`day8/re-frame-2-story-mcp`), exposed via stdio + JSON-RPC, with the toolset surface matching Storybook MCP's Dev/Docs/Testing split for agent-portability:
    - **Dev**: `get-story-instructions`, `preview-variant`, story-generation guidance.
    - **Docs**: `list-stories`, `get-story`, `get-variant`, `list-tags`, `list-modes`, `list-assertions`, `variant->edn`.
    - **Testing**: `run-variant`, `snapshot-identity`, `run-a11y`, `read-failures`.
    - **Write surface (v1.1, dev-only)**: `register-variant`, `unregister-variant`.

10. **Add a §"Production hygiene" cross-cutting section** that consolidates §4.6 (DCE), §4.7 (additive contract), and §6.6 (cross-lib `:extends` under `:advanced`). These three together are what makes re-frame-2-story safe to depend on in shipped apps; collecting them under one heading clarifies the production story.

11. **Add a §"Open SOTA roadmap" section** that names the v2/v3 items both passes converged on as forward-looking (app-db diff, pattern lineage via sub-topology, custom panels, static export, multi-host federation, per-substrate filtering). Forms a public roadmap.

### Big picture

rf2-m6tu is a stronger foundation than I expected when I started Phase 1 — it's anchored in spec/007, uses re-frame-2's actual primitives (frames, traces, epochs, sub-topology, force-fx-stub, snapshot-identity, multi-substrate), and lands the SOTA marquee (MCP, EDN-first artefacts, trace panel, 10x embed, multi-substrate, SSR) cleanly. Phase 1's independent SOTA lens contributes seven concrete additions — performance ribbon, layout-debug trio, mode primitive, variants-grid layout, design-token panel, QR sharing, app-db-diff roadmap — that round out the JS-ecosystem parity and add one legacy-revival win (variants-grid).

The two-pass exercise converged strongly: ~12 high-confidence ship items, ~6 Phase-1 SOTA additions, ~8 rf2-m6tu items Phase 1 missed, ~7 design questions only rf2-m6tu surfaced. The strongest convergence is on MCP-as-marquee, EDN-first variants, and 10x integration; the strongest Phase-1-only signal is the live performance ribbon and the mode primitive. There are no fundamental disagreements about *what* re-frame-2-story should be; the deltas are scoping, panel-list completeness, and one or two SOTA-table-stakes parity adds.
