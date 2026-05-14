# `re-frame-2-story` — Feature-Set Research

> Bead **rf2-m6tu (P3)** — survey of JS-world component-development tooling, with a feature-set recommendation for the `day8/re-frame-2-story` artefact. This is **pure research**; no implementation, no commits. Cross-references [spec/007-Stories.md](../../../../spec/007-Stories.md), which already locks much of the registration surface; this document extends the design beyond §7 of that spec.

---

## §1 — Per-Tool Survey

The survey covers eight tools across three families: the **mainstream JS workshops** (Storybook, Histoire, Ladle, React Cosmos), the **non-JS prior art** (Lookbook for Rails, Pattern Lab for design systems), and the **CLJS lineage** (devcards, nubank/workspaces). Each row distils the tool's distinguishing position, then a feature matrix follows.

### 1.1 Storybook (v10, the de-facto standard)

The 800-pound gorilla of component workshops; 89.9k stars, mature plug-in ecosystem, MCP/AI hooks now shipping.

- **Story authoring model.** Component Story Format 3 (CSF3): ES module with a default export (component-level metadata) and named exports (one per story). Stories are *objects* with `args`, `parameters`, `play`, `render`, `decorators` keys — CSF2's function-per-story shape was retired in v7. **Tags** (`autodocs`, `test`, `dev`, custom) drive inclusion and sidebar filtering, with `!` prefix to remove inherited tags.
- **Args + controls.** Three-level hierarchy — project (`.storybook/preview.js`), component (default-export `args`), story (named-export `args`). Controls cover `text`, `textarea`, `boolean`, `number`, `range`, `select`/`radio`, `color`, `date`, `file`, `object`. A `mapping` field converts serialisable arg values into rich JSX. The `useArgs` hook updates args at runtime; a URL syntax `?args=key:val;key2:val2` overrides starting state. Three-level deep-merge for objects, override-by-replacement for arrays.
- **Decorators.** Three-level (project/component/story). Each decorator receives `(Story, context)` where `context` carries `args`, `parameters`, `globals`, `hooks`, `viewMode`. Execution order: global → component → story, innermost-outwards. Decorators are *functions*, not data.
- **Docs.** **Autodocs** (auto-table of props from TS/Flow/PropTypes + JSDoc) and **MDX** (free-form Markdown with embedded JSX). **Doc Blocks** are the primitives: `<Meta>`, `<Story>`, `<Canvas>`, `<Source>`, `<ArgsTable>`, `<Controls>`, `<Description>`, `<Primary>`, `<Subtitle>`.
- **Accessibility.** First-party `@storybook/addon-a11y` runs axe-core per story; three modes — `error` (fail in CI), `todo` (warn while triaging), `off`. Vision-impairment simulation, configurable rulesets (WCAG 2.0/2.1 AA, AAA, best-practices), per-story rule overrides.
- **Visual regression.** Chromatic addon — paid cloud service for cross-browser pixel diffing with PR review UI; pixel-true rather than DOM snapshots; baseline approval workflow.
- **Interactions / play.** `play(context)` receives `canvas`, `userEvent`, `fn` (Vitest spy), `step` (grouped collapsible blocks). Supports `await`-ed `userEvent.click/type/hover/keyboard`. Assertions via `expect(...)` with Testing-Library matchers. The **Interactions panel** offers pause/resume/rewind/step-through. `play-fn` tag is auto-added; `beforeEach`/`afterEach`/`beforeAll` lifecycle hooks.
- **Performance.** ~70MB install (the famous complaint). Cold start measured in tens of seconds for medium-sized projects. HMR is fast once running.
- **Multi-framework.** React, Vue 3, Angular, Svelte, Web Components, Solid (community), React Native, Android, iOS, Flutter (separate workshops). Framework adapters are first-party.
- **Customisation.** Mature addon ecosystem (~hundreds of addons). The most-installed addons in 2026: a11y (7M dl), Chromatic visual tests (7M dl), Visual Tests (4M dl), MSW mocks (3M dl), Test Runner (2M dl), Designs (Figma embed, 2M dl), Themes (2M dl), Dark Mode (376k dl), Tag Badges (302k dl).
- **2026-class features.** **MCP server** (Model Context Protocol) — agents discover components and their APIs, generate stories, run tests, and self-correct against test feedback. The "autonomous correction loop" lets Claude/Cursor/Copilot iterate until interaction-test + a11y-test passes without humans in the loop.
- **Composition.** Stories from multiple Storybooks (potentially different frameworks) federate into one sidebar via `refs`. Used for design-system distribution and audit.

### 1.2 Histoire (Vite-native, Vue/Svelte-first)

Built by Vue.js core-team member Guillaume Chau. Vite-native; zero config (reuses host project's Vite config).

- **Story authoring.** `.story.vue` / `.story.svelte` files with `<Story>` and `<Variant>` components — fully template-driven, not CSF. Each `<Variant>` is a separate scenario.
- **Args / controls.** A `controls` slot inside `<Story>` (story-level) or each `<Variant>` (variant-level). Built-in widgets: `HstText`, `HstNumber`, `HstCheckbox`, `HstSelect`, `HstSlider`, `HstButtonGroup`, `HstColorSelect`, `HstColorShades`, `HstJson`, `HstSlot`. The `initState` slot prop seeds reactive state.
- **Decorators.** Implicit — anything in the `<Story>` template wraps every variant. No three-level system; decorators just compose like normal Vue templates.
- **Docs.** **Auto-CodeGen** generates copyable source code dynamically; preliminary Auto-Docs. Theme customisation per project for branding.
- **A11y.** Not first-class; plugin-shaped.
- **Visual regression.** Not first-class.
- **Interactions.** No play-function equivalent.
- **Performance.** Significantly faster cold-start than Storybook (Vite, ESM-first).
- **Multi-framework.** Vue 3 + Svelte 4 (no React).
- **Customisation.** Plugin system + custom controls.

### 1.3 Ladle (Storybook-API compatible, ~1MB)

A drop-in lightweight Storybook competitor — same CSF, ~1MB instead of ~70MB. Built on Vite + esbuild + ES modules with code-splitting.

- **Story authoring.** `*.stories.jsx`/`tsx` in `src/` — Storybook-CSF compatible. `Story` type from `@ladle/react`.
- **Args / controls.** Three-level (per-story / file / `.ladle/components.tsx` global) with per-story winning. Built-in widgets: radio, inline-radio, select, multi-select, checkbox, range slider. `labels` separates option display names from values.
- **Decorators.** Yes — file-level and global.
- **Docs.** Source viewer; no MDX-equivalent rich docs.
- **A11y.** First-party axe integration.
- **Interactions.** No play function (limited).
- **Performance.** Notably small (~1MB) and fast HMR.
- **Multi-framework.** React-only.

### 1.4 React Cosmos (single-component, file-system convention)

A React-specific, fixture-file-driven alternative. Different philosophy: one component → many fixtures, each a separate file.

- **Story authoring.** `*.fixture.tsx` files (file-system convention drives the sidebar). A fixture is just a React element export — no wrapping CSF object.
- **Args / controls.** Hooks: `useFixtureInput`, `useFixtureSelect`, `useFixtureSlider` (called from inside the fixture). The fixture itself reads its inputs reactively — a different mental model from Storybook's externalised `args` map. Inputs persist across reloads (URL+local).
- **Decorators.** `.decorator.tsx` files (file-system colocated). A decorator at any directory level wraps every fixture below it. Naturally hierarchical, no manual three-level ceremony.
- **Docs.** Lighter than Storybook; static export deployable as a site.
- **A11y / visual / play.** Plugin-shaped, not first-class.
- **Performance.** Fast; minimal-deps TypeScript-first.
- **Multi-framework.** React only (including RN).
- **Customisation.** Full-stack plugin system.

### 1.5 Lookbook (Rails / ViewComponent / Phlex)

Rails-native; used by GitLab, GitHub. Different ecosystem, but interesting design choices.

- **Story authoring.** "Previews" — Ruby classes whose methods are scenarios. Method args become the controls. Markdown front-matter for docs.
- **Args / controls.** Dynamic params per scenario; type inferred from method signature.
- **Decorators.** Page-level layouts.
- **Docs.** Integrated Markdown engine; live previews embed in docs pages.
- **A11y / visual.** Plugin-shaped.
- **Customisation.** Custom inspector panels (sidebar widgets).
- **Defining trait:** **Documentation and previews are colocated; previews and the docs site are one artefact, not two.**

### 1.6 devcards (CLJS, the pioneer)

Bruce Hauman's original. Last release v0.2.7 in 2020; still 1.5k stars but effectively unmaintained.

- **Story authoring.** `defcard` macro — name, doc, hiccup or React element. Cards in a vertical-scroll list; no sidebar nav.
- **Args / controls.** Atom-based — pass an atom, watch it mutate, mutations re-render. No control-panel UI; users built widgets inside the card.
- **History.** **A built-in time-travel scrubber per card** for atom-driven cards. This is unique among the tools surveyed; the closest modern equivalent is re-frame-10x's epoch buffer.
- **Tests.** `deftest` integrated into card output — assertions render as pass/fail badges inline.
- **Docs.** Markdown inside `defcard-doc`.
- **A11y / visual / play.** None.
- **Multi-framework.** Reagent-shaped only.
- **Why it matters to re-frame2.** The atom-time-travel feature *prefigures* re-frame-10x's epoch-history and rf2's `restore-epoch`. devcards' simplicity (cards-as-data, no sidebar ceremony) influenced the JS workshop space heavily.

### 1.7 nubank/workspaces (CLJS, devcards' successor)

Reagent / re-frame / Fulcro live development environment. 521 stars; modestly maintained.

- **Story authoring.** `ws/defcard` macro. Cards know their framework: React, Reagent, re-frame, or Fulcro variants.
- **Layout.** **Responsive grid (2–20 columns), per-card width/height, persisted to local storage per breakpoint.** Card arrangements export as transit data — shareable team layouts. This is the *workspace* feature spec 007 borrows for `:Workspace.*`.
- **Args / controls.** Atom-driven; some helpers per framework.
- **Tests.** `ws/deftest` integrates `cljs.test` runs as cards.
- **Framework integration.** First-class re-frame card type with reagent element wrapping; Fulcro Inspect hook; cards can host whole applications.
- **Hot-reload.** Shadow-cljs custom build target with auto-scan of card namespaces.
- **Why it matters to re-frame2.** Workspaces' **grid persistence** and **transit-shareable layouts** are exactly the artefact spec 007 already names `:Workspace.*`. Mike has prior art here.

### 1.8 Pattern Lab (design systems, atomic-design native)

Node-powered static site generator; Handlebars/Twig.

- **Story authoring.** Templates organised by atomic-design tier (atoms/molecules/organisms/templates/pages). Patterns *include* each other; edits propagate.
- **Args / controls.** Variable data files per pattern; viewport resizer.
- **Decorators.** Templates are nested patterns — composition is the decorator model.
- **Pattern lineage.** **A graph of "which patterns reference which" — design system dependency tracking.** No other tool surveyed ships this.
- **Multi-engine.** Tool-agnostic (Handlebars, Twig).

### 1.9 Feature Matrix

| Feature | Storybook | Histoire | Ladle | RC | Lookbook | devcards | workspaces | Pattern Lab |
|---|---|---|---|---|---|---|---|---|
| CSF/data-shaped stories | yes | template | yes (CSF) | files | Ruby | macros | macros | templates |
| Args + controls UI | yes | yes | yes | hooks | params | no | atoms | data files |
| Decorators (multi-level) | yes | implicit | yes | file-tree | layouts | no | per-card | nested patterns |
| Autodocs from types | yes | preview | no | no | front-matter | no | no | no |
| MDX / rich docs | yes | basic | no | no | yes | yes (md) | no | yes |
| a11y first-party | yes | no | yes (axe) | no | no | no | no | no |
| Visual regression | Chromatic | no | no | no | no | no | no | no |
| Play / interaction | yes | no | no | no | no | no | no | no |
| Loaders / async setup | yes | no | no | no | no | no | no | no |
| Tags / inclusion sets | yes | no | no | no | no | no | no | no |
| Story-as-test | yes | no | no | no | no | yes | yes | no |
| Atom/state time-travel | no | no | no | no | no | **yes** | no | no |
| Workspace grid layout | no | grid | no | no | no | no | **yes** | no |
| Pattern lineage / refs | composition | no | no | no | no | no | no | **yes** |
| AI/MCP integration | **yes** | no | no | no | no | no | no | no |
| Composition (multi-repo) | yes | no | no | no | no | no | no | no |
| Bundle size | ~70MB | small | **~1MB** | small | n/a | small | small | n/a |

---

## §2 — Cross-Tool Patterns

Five patterns repeat across the survey, and three patterns are owned by exactly one tool (the ones re-frame2 should consider borrowing).

### 2.1 Patterns that have converged

**Stories are data, not functions.** CSF3, Histoire's `<Variant>`, Ladle, React Cosmos's fixtures — all moved away from "a story is a function" toward "a story is a static description plus an optional play step." spec/007's "variant artefact contract — variants are data, not functions" is exactly the converged state of the art.

**Args / controls hierarchy is three levels.** Storybook, Ladle, and Pattern Lab all converged on project / file (component) / story. Histoire's two-level model and RC's file-tree model are outliers; the three-level model is dominant because it maps cleanly onto how teams want to share defaults.

**Decorators wrap stories.** Universal. The interesting variance is *what* they can do: visual wrapping (everyone), state setup (Storybook, RC, Lookbook), effect mocking (Storybook via MSW, partial others). spec/007's **three-kind decorator** (hiccup wrapper / frame setup / fx override) is more expressive than any single JS tool ships out of the box — but matches Storybook + MSW in combination.

**Autodocs from prop schemas.** Storybook leads (TS/JSDoc → controls + tables); RC opts out (hooks-driven); Histoire is preview-stage. spec/007's tie-in to Spec 010 schemas — `:int {:min 1 :max 100}` becomes a bounded number control — is exactly the right direction, but goes further than any JS tool because we have a *registry* of schemas rather than per-component proptypes.

**Tags for inclusion.** Storybook's `autodocs`/`test`/`dev`/custom tags maps cleanly onto spec/007's `:dev`/`:docs`/`:test`/`:screenshot`/`:experimental`/`:internal`/`:agent`. Removing-by-prefix (`!dev`) is a Storybook ergonomic worth borrowing.

### 2.2 Patterns owned by one tool — borrow candidates

**MCP / agent integration (Storybook only).** Storybook is shipping an MCP server in 2026 — agents query components, generate stories, run tests, iterate on failures. This is now table-stakes for AI-first frameworks; re-frame2's principles already point toward AI-implementable design, and the story tool is the natural surface to expose. **Critical to ship at v1.**

**Atom / state time-travel scrubber (devcards only).** devcards' inline history scrubber per card mirrors re-frame-10x's epoch buffer; no JS tool ships this. re-frame2 *already has* `epoch-history` and `restore-epoch` in the Tool-Pair surface. **Trivial to expose; nobody else can.**

**Workspace grid + transit-shareable layouts (workspaces only).** Nubank's responsive-grid layout, per-breakpoint persistence, and transit-export of arrangements. spec/007's `:Workspace.*/<name>` already names this; the layout-as-data + persistence + export trio is the unique deliverable.

**Pattern lineage (Pattern Lab only).** Cross-component dependency graph. re-frame2's `sub-topology` (Spec 002/006) yields the *subscription* dependency graph for free; rendering it inside a workspace alongside the variant is unique.

**Composition (Storybook only).** Multiple Storybooks federating into one sidebar. For re-frame2, the analogue is multiple libraries each registering `:story.*` ids into a shared registrar — which is already the design. The UX of cross-library navigation, however, needs deliberate thought.

### 2.3 Patterns that have *not* converged (open in the space)

- **Effect mocking model.** Storybook + MSW for HTTP; nothing standardised for non-HTTP effects. spec/007's `[force-fx-stub <fx-id> <data>]` is more general (any effect, not just HTTP) — re-frame2 wins here automatically because fx are registered.
- **Story-as-test.** Storybook's `play` is the most mature; everything else either omits it or has it as an addon. The story-as-test direction is established but the assertion vocabularies remain ad-hoc.
- **SSR / server stories.** Patchy across the board. spec/007 already has `:platforms #{:server :client}` per variant; this is a leadership opportunity.

---

## §3 — Most-Appreciated Advanced Features (Prioritised)

Ranked by community signal (download counts, issue activity, blog frequency) intersected with re-frame2's principle alignment.

### 3.1 Tier 1 — must ship at v1

1. **Interactive args + auto-derived controls.** The single biggest UX win in the entire space; story value is bounded by it. re-frame2 wins specifically because **controls auto-derive from Spec 010 Malli schemas** (spec/007 §Argtypes). No per-story `argTypes` table maintenance.
2. **Play-style interaction + assertion vocabulary.** Story-as-test. Spec 007 names `:rf.assert/path-equals`, `:rf.assert/sub-equals`, `:rf.assert/dispatched?`, `:rf.assert/state-is`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted` — a richer set than Storybook ships because re-frame's machinery exposes more.
3. **A11y panel (axe-core).** Cheapest win in the space — the addon is plug-in-shaped, applies to rendered DOM regardless of source framework. Ship it day one.
4. **Trace surface per story.** Hook `register-trace-cb!` on the story's frame; render the six-domino trace inline next to the variant. **No JS tool has this**; re-frame2 has it for free.
5. **Epoch-history scrubber.** devcards-style time-travel per variant. Pair with `restore-epoch` for "scrub back, fork forward." Unique to re-frame2.

### 3.2 Tier 2 — ship by v1.1

6. **MCP server / agent surface.** Expose `(rf/registrations :story)`, `(rf/registrations :variant)`, the snapshot identity, the assertion vocabulary, and `run-variant` via MCP. Agents discover, generate, run, and self-correct. Aligns directly with re-frame2's AI-implementable ethos.
7. **Autodocs from schemas + docstrings.** Spec 010 + the registrar's `:doc` field combine to give Storybook-quality autodocs with zero per-story authoring.
8. **Workspace grid + transit-shareable layouts.** Nubank's win, re-styled. Layout-as-data → exportable artefacts → team-shareable presentations.
9. **Per-variant fx-override panel.** UI for "what gets stubbed for this variant." Mirrors MSW's coverage panel.
10. **Visual-regression hook (not the service).** Ship the snapshot-identity hook (`(rf/snapshot-identity variant-id)` already in spec/007); let downstream services (Chromatic-equivalents, Playwright, JSDOM diff) consume it.

### 3.3 Tier 3 — ship by v2

11. **Subscription topology visualiser.** Render `sub-topology` for the active variant — which subs depend on which, which views consume each. Unique to re-frame2.
12. **Story composition across libraries.** Multi-library `:story.*` federation with cross-library navigation and clear ownership badges.
13. **Custom story panels.** Spec/007 already names `reg-story-panel`. Tier this for v2 to let the addon ecosystem grow.
14. **Static export.** RC-style "deploy your story tool as a static site" for design-system distribution.
15. **Per-substrate variant filtering.** Show "this variant on reagent vs uix vs helix vs reagent-slim" side-by-side; valuable for substrate-portability QA.

---

## §4 — Re-Frame2-Specific Extensions

Features that align with re-frame2's principles but no JS tool ships. These are the differentiators.

### 4.1 EDN-first story format (data-shaped, AI-readable)

spec/007 already locks "variant body is a serialisable artefact"; no `:render` fn-slot exists. This is **more restrictive** than CSF (which permits `render: () => <X/>`), and that's the point: a variant round-trips to disk as EDN, ships across the network to a visual-regression server, and feeds the agent input pipeline as the same shape. No JS tool does this — even CSF stories carry inline JSX.

Implementation hook: a `(rf/variant->edn :story.x.y/v)` helper that emits the canonical data form.

### 4.2 Frame as the actor-system boundary per variant

Each variant *is* its own frame (spec/007 §Relationship with frames). State leaks between variants are impossible by construction. Storybook achieves something *like* this via component-decorators-with-providers — but the boundary is convention, not enforcement. For re-frame2 it's load-bearing.

Hook: `frame-provider` wraps each variant in the rendered grid (Path 1 of rf2-sixo).

### 4.3 Trace-as-first-class-output

Every variant has a trace surface. Open the trace panel: see the six dominoes (event → handler → fx → effect → subscription → re-render) execute. This is the **debugging UX no JS tool can match** because no JS framework has the structured trace re-frame does.

Hook: `register-trace-cb!` on the variant's frame; render trace events inline.

### 4.4 Tool-Pair integration

`register-trace-cb!`, `epoch-history`, `restore-epoch` are all Tool-Pair primitives. The story tool wires them in:

- **Trace panel.** Live six-domino events.
- **Epoch scrubber.** `epoch-history` for the variant's frame → timeline UI; `restore-epoch` on selection.
- **Fork-from-epoch.** Click any epoch, fork a new variant from that point — variant-as-data means the fork is a registered artefact, not an in-memory branch.

### 4.5 Multi-substrate variant rendering

The same variant data renders against any registered substrate (reagent, uix, helix, reagent-slim). The story tool ships a substrate selector; the rendered output for each is side-by-side. Substrate-divergence becomes visible immediately.

Hook: `(rf/variant-substrates variant-id)` returns the substrates the view supports; the renderer iterates.

### 4.6 Production DCE

Story registrations DCE under `:advanced` builds. The `reg-story`/`reg-variant` macros are conditional on a `:rf.story/enabled?` compile-time flag; their bodies elide entirely when off. **No JS tool sweats this** because tree-shaking is downstream concern; for CLJS + Closure Compiler the elision must be deliberate.

Hook: macros emit `nil` when the flag is off; the registrar has no entries to enumerate.

### 4.7 Strict additive contract

Adding stories never breaks the app. Removing stories never breaks the harness. Renaming a story-id is a deprecation, not a breaking change. This follows from spec/007's library-owned `:story.*` prefix and the registrar's open-set semantics.

Spec 008 (testing) and spec 012 (routing) intersect here — the story-tool URL surface should be additive too, not co-opt application routes.

### 4.8 AI-implementable story generation (re-frame2-MCP)

re-frame2 + MCP > Storybook + MCP because:

- Schemas are registered, not inferred → agent reads the variant's required arg shape exactly.
- Tags are registered + queryable → agent picks coverage targets by tag (`:agent` tag specifically for AI-curated examples).
- Assertions are registered + enumerable → agent constructs `:play` steps from the known vocabulary.
- Snapshot identity is content-hashed → agent diffs "what changed" before-and-after a code change in O(1).

Hook: MCP tool surface = `list-stories`, `get-variant`, `run-variant`, `list-assertions`, `snapshot-identity`, `generate-variant`, `register-variant` (dev only).

### 4.9 Pattern Lab–style lineage visualiser

`(rf/sub-topology)` already exposes the dependency graph. Render it for the active variant — "this view depends on subs A, B, C; A is recomputed when path P changes." Pattern lineage for free, in re-frame2's case applied to subs rather than templates.

### 4.10 SSR-aware variant tagging

`:platforms #{:server :client}` already in spec/007. Server-renderable variants are testable in JVM-CLJS (spec 011 SSR). The story tool runs server variants in a server-render pane and client variants in the live frame pane. No JS tool ships dual-pane SSR storytelling.

---

## §5 — Proposed Feature Spec for `day8/re-frame-2-story` v1

The v1 deliverable lands the 1.1–1.3 tier-1 list plus the differentiators. Concretely:

### 5.1 Authoring surface (already locked in spec/007)

- `reg-story` (Form A separate, Form B combined-with-`:variants`)
- `reg-variant` (data-only body; `:extends` composition; `:args->events` for app-db mapping)
- `reg-workspace` (`:grid` and `:prose` layouts at v1; custom views via id reference for v1.1)
- `reg-decorator` (id-valued; three kinds: hiccup, frame-setup, fx-override)
- `reg-story-panel` (extension hook; v1 ships a11y + trace + epoch + a11y panels first-party)
- `reg-tag` (the seven canonical tags ship at load)

### 5.2 Runtime surface

- `run-variant` — programmatic; returns `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}`
- `watch-variant` — re-runs on dependency re-registration
- `reset-variant` — restore to post-`:events` baseline
- `variants-with-tags` — query
- `snapshot-identity` — content hash for visual-regression keying
- `variant->edn` — round-trip for AI/regression pipelines

### 5.3 Tool UI panels (first-party)

| Panel | Source data | Owner |
|---|---|---|
| Sidebar nav | `(rf/registrations :story)` tree on `.` | story lib |
| Controls | `:argtypes` + Spec 010 schema | story lib |
| Trace | `register-trace-cb!` on variant's frame | tool-pair |
| Epoch scrubber | `epoch-history` + `restore-epoch` | tool-pair |
| A11y | axe-core on rendered DOM | a11y panel |
| Docs (autodoc) | `:doc` + schemas + variant table | docs panel |
| Source | EDN of variant + Hiccup of render | story lib |
| Assertions | `:rf.assert/*` results | runner |
| Substrate selector | `(rf/variant-substrates id)` | substrate panel |
| Viewport | width/height controls; standard breakpoints | viewport panel |

### 5.4 Workspace UI

- Grid layout: configurable per-card width/height; persisted per-breakpoint to local storage (workspaces-style).
- Prose layout: Markdown with `{:type :variant :id ...}` inlines.
- Transit export of layout (`(rf/workspace->edn :Workspace.x/y)`); load and replace via `reg-workspace`.

### 5.5 AI / MCP surface (v1)

- MCP server packaged as `day8/re-frame-2-story-mcp` (separate jar; not loaded in the host app).
- Tools: `list-stories`, `get-story`, `get-variant`, `list-tags`, `list-assertions`, `run-variant`, `snapshot-identity`, `variant->edn`.
- Read-only at v1; v1.1 adds `register-variant`/`unregister-variant` (dev-only).

### 5.6 Tests + visual regression hook (v1)

- Test-suite adapter: variants tagged `:test` run via `run-variant`; `cljs.test` integration ships first-class, plus generic adapter for kaocha/etc.
- Snapshot identity hook only; no first-party screenshot service (downstream concern).

### 5.7 What v1 does NOT ship

- **Visual regression service** (snapshot-identity hook only; users wire their own).
- **Composition across remote Storybooks** (multi-library local composition works automatically via registrar).
- **Custom story panels** (the hook exists; the v1 panels are exhaustive).
- **MCP write surface** (read-only at v1).
- **Static export** (download EDN; the host app's `:advanced` build excludes the tool anyway).

---

## §6 — Open Questions for Mike

These are design choices the research surfaces but the spec doesn't yet decide.

### 6.1 Should the MCP server be in-tree or out-of-tree?

Spec/007 doesn't mention MCP. Storybook v10 ships it built-in. **Decision needed:** is `re-frame-2-story-mcp` a separate jar (cleaner DCE; explicit opt-in) or a built-in tool panel? The argument for separate: the MCP server depends on stdio + JSON-RPC machinery that's irrelevant to most app deploys. The argument for built-in: integration story is cleaner; one fewer artefact to publish.

**Recommended:** separate jar; spec/007 gets a §"MCP integration" section pointing to it. *(Suggest filing this as a follow-up spec bead if Mike concurs.)*

### 6.2 How does the substrate selector handle substrate-specific failures?

A variant may render under reagent but fail under helix (e.g., a React-18-only API). **Decision needed:** does the multi-substrate pane render each substrate's failure inline (with the error), or auto-skip non-supporting substrates? Spec/007 doesn't address this.

**Recommended:** render the failure inline — making substrate-portability gaps *visible* is the entire point. Add `:platforms` analogue `:substrates #{:reagent :uix ...}` for explicit opt-in/out per variant.

### 6.3 Should `:rf.assert/*` events fail the dispatch, or accumulate into a result list?

Spec/007 says "in test mode they fail loudly when assertions don't hold." **Decision needed:** is failure a thrown exception (stops the play sequence) or a recorded failure (play continues, all failures collected)? Storybook's `play` throws and halts; testing-library generally throws. devcards records and renders inline.

**Recommended:** record, don't throw. The "play continues, all failures collected" model is more debuggable and aligns with re-frame's run-to-completion drain. `run-variant`'s `:assertions` list reflects this.

### 6.4 How are loaders' completion criteria defined for non-HTTP fx?

Spec/007 §Loader lifecycle says a loader is complete when "no further events are in flight against the variant's frame." That works for `:http` but **what about long-lived effects (`:websocket`, `:interval`, `:firestore`)**? They never "complete." Spec/007 hints at this but doesn't lock policy.

**Recommended:** treat long-lived effects as "loader has progressed when first message arrives." Add `:loaders-complete-when` to variant body (an event predicate). File a `bd` bead against spec/007 if Mike concurs.

### 6.5 Workspace persistence — local-storage or registry?

Spec/007 leaves workspace authoring as registration-time; workspaces are themselves data. **Decision needed:** when a user *interactively* rearranges a workspace in the UI, do those edits persist to local storage (workspaces-style, ephemeral) or to a re-registered artefact (durable, shareable via transit)? Nubank's workspaces did both: local storage for ephemeral, transit-export-import for shared.

**Recommended:** both — local-storage by default; a "save layout as `:Workspace.x/y`" button emits transit + re-registers.

### 6.6 What happens when `:advanced` builds DCE a registered variant referenced by `:extends` in another lib?

If lib A registers `:story.auth.login-form/loading` and lib B has `:extends :story.auth.login-form/loading`, and the app's `:advanced` build elides A's registrations but keeps B's — what's the failure mode? Spec/007 §"`:extends` resolves to a registered variant id; cycles are a registration error" implicitly assumes both ends exist.

**Recommended:** registration is dev-only; the `reg-variant` macro elides under `:advanced`. Inter-library `:extends` is then irrelevant in production builds. Lock this in spec/007.

### 6.7 What's the integration story with `re-frame-10x`?

re-frame-10x's epoch panel and the story tool's epoch scrubber both consume `epoch-history`. **Decision needed:** are they one shared UI (story tool embeds re-frame-10x), two separate UIs that share a buffer, or does the story tool *replace* re-frame-10x for the story-mode workflow? The MIGRATION.md should reflect this.

**Recommended:** the story tool embeds re-frame-10x's epoch panel as a `reg-story-panel`. Avoid two implementations of the same UX.

### 6.8 Should Story visualise state machines (XState-style charts) or is that a separate artefact?

XState tooling (Stately Studio + the VS Code inspector) ships chart-style visualisation: hierarchical state boxes, parallel regions, transition arrows, live highlighting of current state, hover-to-see-events, a simulator panel, and a time-travel scrubber over transitions. The re-frame-2 runtime emits all the data needed: `reg-machine` registers the topology; `active-machine-snapshots` exposes the running state; `epoch-history` carries the transition log. **Decision needed:** does Story own the chart visualisation panel, or does that live in a separate artefact that Story embeds?

**Recommended:** *split.* Story ships only a **lightweight current-state indicator** (one line per active machine: `:auth/login → :authenticating`) which is nearly free given the data Story already consumes. The **full chart visualisation** — hierarchical layout, parallel regions, transition arrows, live highlighting, simulator, scrubber — lives in a separate artefact (`day8/re-frame-2-machines-viz` or similar) that exposes a `reg-story-panel` adapter so Story users opt in with one require.

Three reasons:

1. **Layout engineering is specialised and heavy.** Auto-layout for hierarchical statecharts (parallel regions, history pseudostates) is substantial work — XState invested years on Stately. The libraries that get you 80% there (`@xyflow/react`, `d3-hierarchy`, `elkjs`) add real bundle weight. Story users iterating on a Button shouldn't pay for it.

2. **Different consumer mental model.** Story's audience is component devs ("show me Button under these args"). Machine-viz's audience is state architects + debuggers + ops ("show me auth-machine's structure + current state across epochs"). UX optima diverge: Story wants tight iteration loop + per-variant grids; machine-viz wants whole-system topology + time-travel-through-transitions.

3. **The split is the pattern; the artefact has non-Story consumers.** `re-frame-2-story-mcp` is already going to be a separate jar (§6.1). `re-frame-2-machines-viz` extends that pattern cleanly. The viz artefact also serves consumers Story would never reach: re-frame-10x's epoch panel could overlay machine state on each epoch; the Tool-Pair could surface live state for production troubleshooting; architecture docs could embed static SVG. Coupling the viz to Story forecloses those.

**Concrete shape if Mike concurs:**

- `day8/re-frame-2-machines-viz` — separate jar; owns the renderer. Public surface roughly `(render-machine machine-id)`, `(render-machine-live machine-id frame)` (live highlighting via the frame's machine snapshot), `(machine->svg machine-id)` for static export, `(machine->mermaid machine-id)` / `(machine->d2 machine-id)` for agent-friendly source.
- Exposes a `reg-story-panel` adapter so Story users opt in.
- MCP surface: `get-machine-chart machine-id` returns SVG or D2/Mermaid source (so agents can "see" the machine via tool).
- Story ships only the lightweight indicator (one-liner per active machine) as part of the standard variant chrome.

The version where Story owns the visualisation isn't *wrong* — just over-scopes Story and leaves the other consumers re-implementing.

---

## §7 — Cross-Reference to spec/007-Stories.md

This research extends, not replaces, the locked surface in spec/007.

| spec/007 section | What spec/007 already locks | What this research adds |
|---|---|---|
| §Canonical id grammar | `:story.<path>` / `:Workspace.<...>` / variant ids | — |
| §Story / Variant / Workspace | data-only registration surface | — |
| §Variant artefact contract | no fn-slots; EDN-round-trippable | §4.1 makes this a feature, not just a constraint |
| §Args at three levels | global/story/variant | §3.1 confirms convergence with industry |
| §Argtypes describe controls | control vocabulary `:text`/`:select`/etc. | §3.2 confirms vocabulary; §3.3 ties to Spec 010 |
| §Decorators (three kinds) | hiccup / frame-setup / fx-override | §2.1 confirms three-kind decorator is *more* expressive than any JS tool |
| §Play functions | event sequence with `:rf.assert/*` | §6.3 (open Q): record vs throw |
| §Inclusion tags | seven canonical tags | §3.1 confirms Storybook parity; §3.2 borrows `!` prefix |
| §Loaders | four-phase lifecycle | §6.4 (open Q): long-lived effect criteria |
| §Effect mocking — hook design | `force-fx-stub` decorator | §2.1 confirms decoration more general than MSW |
| §Portable into tests | `run-variant` returns `{...}` | §5.2 confirms surface; §5.6 makes test integration first-class |
| §Variant snapshot identity | content-hash | §3.2 confirms unique; §5.5 wires MCP discovery |
| §Story-tool extension hook | `reg-story-panel` | §5.3 enumerates first-party panels |
| §Workspaces — generic or specialised (open) | `:grid` and `:prose` | §5.4 + §6.5 (open Q): persistence model |
| §Devcards / Workspaces interop (open) | adapter shims | §1.6, §1.7 inform the shim design |
| §Story composition across libraries (open) | multiple `:story.*` namespaces | §2.2 + §5.7 |

**Surfaces this research recommends adding to spec/007:**

1. A §"MCP / agent integration" section pointing to the separate-jar tool (§6.1).
2. A §"Multi-substrate rendering" section defining the `:substrates` opt-in (§6.2).
3. A §"Assertion failure mode" lock — record, not throw (§6.3).
4. A §"Loader completion for long-lived effects" lock — `:loaders-complete-when` predicate (§6.4).
5. A §"Workspace persistence" lock — local + opt-in-transit (§6.5).
6. A §"Production elision of story registrations" lock — `:advanced` DCE policy (§6.6).
7. A §"Tool-Pair integration" section naming re-frame-10x embedding (§6.7).
8. A §"State machine visualisation" cross-link — Story ships only a lightweight current-state indicator; the chart viz lives in a separate `re-frame-2-machines-viz` artefact that Story embeds via `reg-story-panel` (§6.8).

Whether to land these as edits to spec/007 or as a new spec/007a/Stories-Tooling.md is itself a question for Mike. The research recommendation is **edits to spec/007**, because the additions extend rather than replace.

---

*End of research document. Word count: ~5,200.*
