# Phase A — Competitive landscape

- **Written**: 2026-09-14 09:40:36 +10:00 (research start); competitor URLs fetched the same morning.
- **Trunk**: `98e8ffe9cb339295fe2fa459900d9d9647fab015` on `main`, porcelain empty.
- **Method**: live primary docs + GitHub/npm/pub.dev, not the May 2026 in-repo surveys. Marketing treated as claims.

## Selection

**Main comparator:** Storybook **10.6.0** (stable latest on 2026-09-02; v11 is alpha). Docs pages retrieved 2026-09-14 still brand 10.6 assets.

**Complementary set (five, plus one lineage group):**

| Pick | Why it is in the set |
|---|---|
| **Ladle** `@ladle/react@5.1.1` | Fastest honest Storybook-shaped React workshop; CSF-compatible; the "ceremony tax" control. |
| **Histoire** | Vue/Svelte Vite workshop whose `<Story>` / `<Variant>` split is closer to Spec 007 than CSF is. |
| **CLJS lineage** (devcards, Nubank workspaces, **Portfolio 2026.03.1**) | Native ancestors. Several of Story's "wins" are inheritances. Portfolio is the currently maintained CLJS workshop (Maven `2026.03.1`). |
| **Lookbook v2** (gem **2.3.15**; docs banner still 2.3.14) | Non-JS: preview + docs as one Rails artefact; method-as-scenario. |
| **Widgetbook** (stable 3.25.0; v4 beta.13) | Non-JS: constructor-as-source-of-truth, type-safe args, story-level *scenarios* as tests. |

**Adjacent, not workshops:** Chromatic (hosted VR + UI Review, Storybook-team product); `@storybook/addon-mcp` (preview); Playwright / Vitest component testing; MSW. Included only where they steal a job.

**Not given a page:** Bit (component packaging, not a workshop); Pattern Lab (lineage idea only); React Cosmos (does not change a job Ladle does not already pose); Figma Code Connect (design-tool round-trip, OUT as a Story job).

## 1. Storybook 10.6 — the reference

**Position.** The default workshop for JS component libraries and many app teams. ESM-only since 10. Maintained by the Chromatic company. AI capabilities are explicitly **preview**.

**Authoring.** CSF 3 is the released format (default export `meta` + named story objects: `args`, `decorators`, `parameters`, `play`, `tags`). **CSF Factories / CSF Next** (`definePreview` → `preview.meta` → `meta.story`) shipped in 10 as experimental, expanded in 10.3 to Vue/Angular/Web Components; CSF 1–3 remain supported and must not be mixed in one file. Stories are still **functions-plus-args at heart**; factories add type-safe construction, they do not make the artefact EDN.

**Isolation.** Preview iframe per story. CSS, focus, and portals are isolated because the subject is another document. Cost: extra document, slower HMR than Vite-native peers, "one story at a time" as the default (docs pages and play functions do not give you five live app-dbs).

**Controls / globals.** Three-level args (project / component / story). `argTypes` drive widgets. `globalTypes` + toolbar for theme/viewport/locale. **Chromatic Modes** (hosted) save combinations as independently baseline-able cells.

**Docs.** Autodocs + MDX + Doc Blocks (`Canvas`, `Story`, `ArgsTable`, …). Component-level docs pages are a first-class expectation.

**Test.** `@storybook/addon-vitest` runs portable stories in Vitest browser mode (Playwright under the hood). `play()` + Testing Library + the Interactions panel (step/pause/rewind). Storybook Test is the 2026 direction: the story *is* the test fixture. Assertions throw; first failure is the culture.

**A11y.** Official addon, axe-core; 10.3 advertised a large chrome a11y overhaul.

**Visual regression.** **Not in the OSS package.** Official path is `@chromatic-com/storybook` → Chromatic cloud. Pricing retrieved 2026-09-14 from [chromatic.com/pricing](https://www.chromatic.com/pricing): Free $0 / 5k billed snapshots (Chrome); Starter **$179/mo** / 35k; Pro $399/mo / 85k; Enterprise custom. TurboSnap bills unchanged stories at 1/5. UI Review, cross-browser, and accessibility-as-a-service live here. Community `storybook-addon-vis` exists for local Vitest image snapshots.

**Share / publish.** `storybook build` static site; deep links; Chromatic also *hosts* the published Storybook.

**Agent / MCP.** Official `@storybook/addon-mcp` 10.6.0 (npm, published ~2026-09-02). HTTP endpoint on the *dev server* (`http://localhost:6006/mcp`). Toolsets: **dev** (`get-storybook-story-instructions`, `stories-preview`, `stories-find-by-component`, `stories-changed`, `review-create`), **docs** (`docs-list`, `docs-show`, `docs-show-story` — needs components manifest, off by default), **test** (`test-run` — needs addon-vitest). 10.6 adds CLI bindings for agent **skills**. Status: preview. This **refutes** any claim that "no peer has MCP."

**Install DX.** `npm create storybook@latest` — framework detect, Recommended vs Minimal (docs/test/a11y), example stories, onboarding wizard, `npm run storybook`. One command. That is the J1 bar.

**Extension.** Addon marketplace. Essentials are first-party; MSW, designs/Figma, themes, measure/outline, coverage are addons. This is the "parade of single-purpose addons" Story's vision names.

**Best in the field at:** daily-use chrome at design-system scale; the story-as-test *direction* with a real browser runner; the agent loop that can preview + test in the same workshop (preview-quality, but shipped); the commercial VR review path.

**Structural weaknesses (jobs, not nouns):** isolation is an iframe, not an application runtime; a story is not automatically a real event-sourced app state; mocking is per-seam (MSW, module mocks, decorators); many states at once is awkward; the artefact an agent writes (JS/TS module) is not the same bytes a human shares as a URL hash.

## 2. Ladle 5.1

**Position.** Drop-in CSF-compatible React workshop on Vite. Latest `@ladle/react@5.1.1` (2025-11-04). React-only. ~1MB vs Storybook's historically large manager+preview.

**Best at:** time-to-first-story and HMR. The control for "is Storybook's power worth the ceremony?"

**Missing as jobs:** play functions, MDX docs, MCP, Chromatic-native path. Composition is file/global decorators, not a marketplace. **Present without owning a host:** documented Playwright capture/compare ([ladle.dev/docs/visual-snapshots](https://ladle.dev/docs/visual-snapshots/)) — the right J15 recipe peer, not a reason to build Chromatic.

**Implication for Story:** Ladle is what a CLJS workshop is often *accused* of needing to beat on install/start. If Story loses J1 to Storybook *and* to Ladle, that is an adoption blocker, not a taste issue.

## 3. Histoire

**Position.** Vite-native playground for Vue 3 and Svelte 4+. Docs (histoire.dev, retrieved 2026-09-14): Story + Variant as first-class template tags; markdown docs via `<docs>` block or sibling `.story.md`; Auto-CodeGen on Vue; Auto-Docs still "in construction." No React (points at an alternative). **Release hygiene:** npm `latest` was `1.0.0-beta.1` at Astra's check; latest non-prerelease **0.17.17**. Do not treat the beta as the stable baseline. Screenshot *plugin* captures files only; Lost Pixel / Percy are the compare routes.

**Best at:** the three-way split Story already chose; seeing variants as named scenarios inside a story file; fast Vite loop.

**Missing:** play/interaction runner, first-class a11y/VR, MCP, SPA-state isolation (it wraps Vue/Svelte components, not app runtimes).

**Implication:** Histoire is the right second comparator for **workspaces / many states** and for **Story vs Variant naming**. It is the wrong comparator for test/agent/SPA jobs.

## 4. CLJS lineage

**devcards.** Card-as-namespace; many states on one page; figwheel live reload; record-don't-throw culture. Ancestor of "see the family of states." Unmaintained as a 2026 SOTA product; the *job* survives.

**Nubank workspaces.** Shadow-cljs target; cards + tests in one UI; workspace tabs with layout persistence. Closest prior re-frame-shaped workshop. Still the mental model for Story's `reg-workspace`.

**Portfolio** (`no.cjohansen/portfolio {:mvn/version "2026.03.1"}`). Actively released in 2026. "Visual REPL"; scenes; simultaneous states, viewports, configurations; explicitly **does not aspire to Storybook feature-parity**; REPL-oriented. Generate-scenes-from-component-and-specs is on its own wishlist, not shipped as the headline.

**Implication:** Story must not score `AHEAD` on "many states at once" against Storybook and forget Portfolio/devcards already did that in this ecosystem. The differentiator vs *this* lineage is application-frame isolation, the variant-as-test/MCP artefact, and Xray — not the grid.

## 5. Lookbook v2 (Rails)

**Position.** Rails engine for ViewComponent / Phlex / ActionView partials. Previews are Ruby classes; methods are scenarios; args inferred; Markdown docs colocated. GitLab uses it in development.

**Best at:** documentation and previews as **one artefact**; fitting the host framework instead of importing CSF.

**Implication:** Lookbook is the comparator for **docs-as-the-workshop** (J7) and for "idiomatic to the host." Story's EDN variant is the CLJS analogue of a Lookbook preview method — if we make authors learn a second encoding, we have lost Lookbook's lesson.

## 6. Widgetbook (Flutter)

**Position.** Stable v3 (`3.25.0`); **v4 beta** (beta.13 ~2026-09, announced 2026-03-31): generated Storybook-aligned API, type-safe args from the widget constructor, Addons + Modes, **Scenarios** as story-level test configurations that generate snapshots.

**Best at:** constructor/schema as the control source of truth; embedding tests next to stories without a second fixture file.

**Implication:** Widgetbook v4's "the widget constructor is the source of truth" is the same job as Story's schema → controls. If Story's schema derivation is real but untaught, Widgetbook is the existence proof that this job can be the *front door*.

## 7. Adjacent

| Tool | Job it steals | Cost / caveat (2026-09-14) |
|---|---|---|
| **Chromatic** | Visual review + hosted Storybook + interaction/a11y in CI | Paid after 5k snapshots; Storybook-lock-in; not OSS Storybook |
| **Percy / Argos** | Same VR job, less Storybook-native | Argos Pro ~$100/mo / 35k (July 2026 blog); Percy much dearer |
| **MSW + addon** | Network mock per story | One seam of many |
| **Playwright / Vitest CT** | Component test without a workshop | Steals "the example is a test" if the workshop's test story is weak |
| **Storybook MCP** | Agent discover / preview / test | Preview API; HTTP on the *running* Storybook, not a separate jar |

## Cross-cutting: what is SOTA on this date

1. **Agent loop on the workshop** — Storybook MCP + skills (preview) vs Story-MCP (separate jar, 19 tools, write gated). Both exist. Neither may be scored absent.
2. **Story-as-test** — Storybook Test / portable stories / CSF Factories vs `story/run` / `story/is` / `:cannot-run`.
3. **Modes as combinatorial matrix** — Chromatic Modes vs `reg-mode`.
4. **Schema/constructor-derived controls** — Widgetbook v4, Story's Malli path, not Storybook's default `argTypes`.
5. **Many states at once** — Histoire, Portfolio, Lookbook, Story workspaces; Storybook is weak.
6. **Install in one command** — `npm create storybook@latest`; Ladle is "add one dep." Story is not on Clojars yet (`tools/story/README.md`).
7. **VR as a service** — still Chromatic's job; OSS workshops emit keys/iframes.

## Fair-comparison rules used below

- Storybook's strongest practical workflow **includes** addon-vitest, addon-a11y, addon-mcp, and Chromatic if the job is visual review — with those named as extras.
- Story's strongest workflow **includes** Xray (declared dependency), Story-MCP (separate coordinate), and skills.
- Do not score a fingerprint as a pixel diff.
- Experimental/preview capabilities (CSF Factories, Storybook MCP) are labelled preview, not treated as absent and not treated as baked-in defaults.
