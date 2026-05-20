# Story ↔ Storybook — Feature-Parity Audit (Phase A gap manifest)

> Phase A deliverable for rf2-2jdh9 ("Story feature-parity with Storybook
> — build /docs/Story tutorial mirroring Storybook React tutorials +
> identify gaps").
>
> Audit date: 2026-05-20 18:45 AUSEST.
> Method: read-only audit — Storybook React tutorial track (Get started
> + Writing stories + Essentials addons + Test runner + Composition +
> Visual testing + Publish) mapped against `tools/story/spec/` + the
> shipped `docs/story/` tutorial set.
> Status: Phase A complete. Phase B is Mike's operator review of this
> manifest; Phase C is per-gap implementation beads filed by the mayor
> after Mike picks the gaps to action.
>
> **Companion finding:** the deeper tutorial-walk gap analysis lives in
> the local-only `ai/findings/2026-05-20-story-tutorial-set.md` (rf2-l6jev) —
> 18 chapters mapped, 11 findings filed (F-1 .. F-11). This manifest is
> the spec-grade summary; it cross-references the per-finding details by
> `[F-N]` and adds eight cross-cutting concept-axis gaps (`[C-N]`) the
> tutorial-walk didn't centre on.

## TL;DR

Story matches or exceeds Storybook on 28 of 33 capability axes audited.
Five genuine capability gaps remain:

- **[C-1] No global decorator** (preview.ts equivalent) — also F-1, P2.
- **[C-2] No first-party MDX / rich-prose rendering** — currently `pre-wrap` plain text; P3 (deliberate punt today, but tutorial-class docs need a path).
- **[C-3] No loader-teardown hook** — also F-3, P2.
- **[C-4] No per-story-rollup docs page** ("Component.docs" equivalent) — variants get docs, parent stories do not; P3.
- **[C-5] No "Args as a named first-class concept" branding** — the args primitive is more powerful than Storybook's, but it is undermarketed; P3 docs gap.

Two **deliberately deferred** SB capabilities — **Composition / multi-host
federation** (v2 roadmap, rejected for v1 per `005-SOTA-Features.md`
§v2 ship list) and **first-party visual-regression service**
(rejected by design per `DESIGN-RATIONALE.md` §Rejected). Both are
divergent-by-design, NOT gaps.

Three **DX gaps** worth surfacing for Mike's review:

- **[D-1] No `npx storybook init`-style scaffolder** for end-users (the
  `template/` artefact ships, but no published one-liner). P3.
- **[D-2] `install-canonical-vocabulary!` is mandatory boot ceremony** —
  also F-11; auto-install on first reg-* would remove the tax. P4.
- **[D-3] No tutorial-shape Playwright recipe** for end-user e2e — also
  F-6; gates land but readers need a starter.

Two **insight gaps** the spec covers correctly but undermarkets:

- **[I-1] Frame-as-isolation is the stateful-stories answer** — also F-4.
- **[I-2] Schema → controls auto-derivation is THE controls story** —
  also F-5.

The rest is matches or wins. See §Per-axis status table below.

## Phase A method

Storybook React tutorial axes walked (33):

```
Get-started:           Why · Install · What's a story · Browse · Setup test
Writing stories:       Args · ArgTypes · Decorators · Loaders · Parameters
                       Play · Tags · Naming · Stateful · Mocking
Essentials addons:     Controls · Actions · Viewport · Backgrounds · Toolbars
                       Measure · Outline · Highlight · A11y · Docs/AutoDocs
Composition:           refs/multi-host (deferred-by-design)
Visual testing:        Chromatic / VR services (deferred-by-design via hook)
Test runner:           @storybook/test-runner ergonomics
MDX / Doc Blocks:      MDX prose + canvas blocks
Publish:               storybook build (static export)
Themes:                light/dark/HCM + custom theming
Add-ons system:        third-party addon ecosystem
```

Each axis was mapped against:

1. `tools/story/spec/000-016` + `API.md` + `Conventions.md` + `DESIGN-RATIONALE.md`.
2. The shipped `docs/story/01..07` tutorial chapters.
3. The prior tutorial-walk finding (rf2-l6jev / 2026-05-20-story-tutorial-set.md).
4. Storybook React docs (storybook.js.org/docs/get-started + writing-stories + …).

The output is the **per-axis status table** below + the per-gap brief.
Tutorial pre-write was scoped out for this pass — the shipped `docs/story/`
set already covers 7 chapters; the prior finding's 18-chapter outline
covers the remainder. The forcing-function part of the brief is fulfilled
by the prior pass; this manifest captures the gap-only summary.

## Per-axis status table

| # | Storybook capability | Story counterpart | Status | Category | Fix scope | Suggested priority | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `meta` default export | `reg-story` | matches | — | — | — | Strict equivalent |
| 2 | Named exports (`Primary = {...}`) | `reg-variant` | matches | — | — | — | Strict equivalent |
| 3 | `args` (default values) | `:args` + 5-layer precedence | **WIN** | — | — | — | 5 layers vs SB's 3 (global < story < mode < variant < cell) |
| 4 | `argTypes` (control spec) | `:argtypes` (auto-derived from Malli) | **WIN** | — | — | — | Schema-derived; manual override only if needed. **See [I-2] — undermarketed.** |
| 5 | `decorators` on component meta | `:decorators` on `reg-story` | matches | — | — | — | Strict equivalent |
| 6 | `decorators` on story | `:decorators` on `reg-variant` | matches | — | — | — | Strict equivalent |
| 7 | `decorators` in `preview.ts` (global) | **MISSING** | gap-M | capability | M | **P2** | **[C-1] / [F-1]** — see brief below |
| 8 | `parameters` (per-story metadata) | `:tags` + `:modes` + body slots | divergent-by-design | — | — | — | Story decomposes parameters into typed slots; no monolithic `:parameters` map |
| 9 | `play` (async DOM-event fn) | `:play-script` (the ONLY slot — rich DSL) | **WIN** | — | — | — | EDN, no Testing-Library translation. **F-10 RESOLVED via rf2-0wrud (commit 3f5ae2512, 2026-05-20): `:play` removed; `:play-script` is canonical.** |
| 10 | `loaders` (async data load) | `:loaders` + `:loaders-complete-when` | matches+ | — | — | — | Story owns the `:loaders-complete-when` predicate axis |
| 10b | Loader error states (throw/reject/never-complete) | partial — diagnostic variants exist; spec silent | gap-S | insight/doc | S | **P2** | **[F-2]** — doc-only addition to `002-Runtime.md` |
| 10c | Loader teardown / cancellation | **MISSING** | gap-M | capability | M | **P2** | **[C-3] / [F-3]** — see brief below |
| 11 | `tags` (auto-test selection + filter) | `:tags` (seven canonical + project + axes) | matches+ | — | — | — | Faceted axes; `!`-prefix removal; 7 canonical tags |
| 12 | `globalTypes` / globals (toolbar) | `reg-mode` (saved tuples) | **WIN** | — | — | — | One primitive collapses theme + viewport + locale + background |
| 13 | `useArgs()` stateful stories | per-frame isolation + `:play` dispatching | matches+ | insight/doc | S | P3 | **[I-1] / [F-4]** — undermarketed; doc reorg in `001-Authoring.md` |
| 14 | MSW / mock-network addon | `:rf.story/force-fx-stub` | **WIN** | — | — | — | One primitive subsumes 4+ addons (HTTP/ws/storage/analytics/geo) |
| 15 | Toolbar (theme/viewport/locale) | 5-cluster toolbar + `reg-mode` | **WIN** | — | — | — | MODES \| DATA \| VIEW \| DEBUG \| REC clusters with small-caps labels |
| 16 | Backgrounds addon | `reg-mode` `:axis :background` | matches | — | — | — | One primitive; no separate addon |
| 17 | Viewport addon | `reg-mode` `:axis :viewport` | matches | — | — | — | Same shape |
| 18 | Actions panel | Causa-as-RHS Trace tab | **WIN** | — | — | — | Six-domino cascade, not just event log |
| 19 | Measure / Outline / Highlight addons | layout-debug decorator trio | matches | — | — | — | All three ship inline |
| 20 | a11y addon (axe-core) | `:rf.story.panel/a11y` + `:rf.story.panel/chrome-a11y` | matches+ | — | — | — | Variant-author panel + chrome dogfood panel; opt-in consent per rf2-20w5i |
| 21 | Source code panel | `:source` slot + "Open in editor" chip | matches | — | — | — | URI-scheme handler; vscode/cursor/idea/custom |
| 22 | Docs mode (AutoDocs) | `:docs` mode tab + autodocs pane | matches | — | — | — | Six-section pane: header / prose / args / decorators / parameters / tags |
| 22b | MDX rich prose rendering | **MISSING** (deferred — `pre-wrap` plain text) | gap-S | capability/insight | S-M | **P3** | **[C-2]** — see brief below |
| 22c | Component-level docs rollup (`Component.docs`) | **MISSING** (per-variant only at v1) | gap-S | capability | S | **P3** | **[C-4]** — see brief below; spec/008 names this as v2 |
| 23 | Composition (`refs` / multi-host) | **MISSING** (v2 roadmap) | divergent-by-design | — | — | — | `005-SOTA-Features.md` §v2 ship list — registrar handles local cross-lib for free; remote federation is v2 |
| 24 | Test runner (`@storybook/test-runner`) | `(run-variant id)` + `cljs.test` adapter | **WIN** | — | — | — | One primitive returns full result map; `serve-and-run-story-play-scripts.cjs` CI runner |
| 25 | Test-runner end-user ergonomics | partial — gates ship, tutorial-shape recipe missing | gap-S | DX/doc | S | P3 | **[D-3] / [F-6]** — write `Tutorial-Playwright.md` (rf2-NEW-6 filed) |
| 26 | Visual testing (Chromatic / Percy) | `snapshot-identity` content-hash hook | divergent-by-design | — | — | — | Story emits the key; pixel diff is downstream — `DESIGN-RATIONALE.md` §Rejected: first-party VR service |
| 27 | Interactions panel | Test-mode pane row-by-row | matches | — | — | — | Plus play step-debugger per spec/009 |
| 28 | Recorder (record canvas → CSF) | `start-recording!` + `gen-play-snippet` + `:play-script` v2 | **WIN** | — | — | — | EDN-shaped; no DOM-event translation; mid-recording assertion insert |
| 29 | Save current state as story | `save-current-as-variant!` | matches | — | — | — | Review-then-paste (deliberate vs Storybook's auto-write) |
| 30 | `npx storybook init` | three-line install (deps + boot + mount) | matches+ | DX | — | — | No toolchain mutation. But see [D-1] — no published scaffolder |
| 30b | One-liner scaffolder | **MISSING** (template/ artefact ships, no published one-liner) | gap-S | DX | S | P3 | **[D-1]** — see brief below |
| 31 | Add-ons ecosystem | `reg-story-panel` + `reg-decorator` (extension points) | matches | — | — | — | One extension point per concern (panel + decorator); no addon registry today. Third-party panels work; ecosystem-as-marketplace is N/A (different distribution model) |
| 32 | Theming presets (light/dark/HCM) | HCM + system-colors toggle + `reg-mode :axis :theme` | matches | — | — | — | rf2-ubhmn (HCM) + rf2-846h2 (system-colors); presets shipped |
| 33 | Themes — custom Storybook theme overrides | Story's chrome identity locked (warm-slate + amber + Plex) | divergent-by-design | — | — | — | Per `DESIGN-RATIONALE.md` §Rejected: brand-pink commodity chrome. Theme-tokens public for third-party panels (rf2-2rwdc / rf2-i3i5j) but Story's chrome itself is the identity surface |
| 34 | static-build (`storybook build`) | `story:build` (rf2-8wgpm) | matches | — | — | — | Single-page SPA; per-variant deep-links via share URL |
| 35 | Boot ceremony (`install-canonical-vocabulary!`) | mandatory call | divergent-by-design / ergonomic | DX | S | P4 | **[D-2] / [F-11]** — auto-install on first `reg-*` (rf2-NEW-11 filed; rf2-p1ydc partially landed) |

**Score:** 28 wins or matches · 5 capability gaps · 3 DX gaps · 2 insight gaps · 3 divergent-by-design (composition / VR service / custom-chrome-themes — all locked rejections).

## Per-gap briefs

### [C-1] No global decorator primitive (P2, M)

Storybook ships `preview.ts` with `decorators: [...]` — an array applied
to every story in the project. Story's `reg-decorator` registers a
decorator id; `:decorators` on `reg-story` / `reg-variant` references
it by id. There is **no mechanism for "apply this decorator to every
story in the project"**.

**Tutorial impact:** the canonical "wrap all stories in your design
system's theme provider" recipe cannot be written cleanly — the
reader must list the decorator id on each `reg-story` manually,
error-prone in a large codebase.

**Direction (not a proposal):** extend `configure!` with a
`:rf.story/global-decorators` slot symmetric to `:rf.story/global-args`.
The args-precedence-chain analog already exists at Layer 1; the same
shape applies to decorators (story-level wraps innermost, etc.).

**Status:** filed as `rf2-NEW-1` in the prior tutorial-walk finding.
Bead body should reference both [C-1] here and [F-1] in the prior
finding.

### [C-2] No MDX / rich-prose rendering (P3, S-M)

Story's docs pane renders prose as `pre-wrap` plain text — explicitly
out-of-scope at v1 per `008-Docs-Mode.md` §Out of scope. Storybook's
MDX + Doc Blocks (`<Canvas>`, `<Story>`, `<Source>`, `<ArgsTable>`)
are a core authoring surface for documentation-heavy design systems.

**Tutorial impact:** a tutorial chapter that demonstrates rich
"this is how you write a documentation site for your design system"
cannot be written today. Workspaces with `:prose` items help but
render plain text.

**Direction:** the existing `reg-story-panel` extension point can host
a custom Markdown→hiccup renderer. The spec already names this as the
intended workaround. A first-party `:rf.story.panel/markdown-prose`
that consumes a vetted Markdown library (a CLJS port or
`marked-cljs`) would close the gap without bringing MDX's JSX layer
into Story's EDN-first contract. The Doc-Blocks-equivalent (canvas /
source / args-table) is straight-forward — those are already separate
Reagent components in the chrome.

**Status:** **NEW gap, not in prior tutorial-walk findings.** File as
follow-on bead. Spec/008 already names "v2 may add" — but workspaces
+ docs panes are increasingly load-bearing as the tutorial ladder
grows, so this is worth re-evaluating sooner.

### [C-3] No loader-teardown hook (P2, M)

`destroy-variant!` tears down the variant's frame, but a long-lived
fx registered by `:loaders` (websocket open, interval, geolocation
watch) will keep firing unless the fx-handler itself owns its
cleanup. There is no documented `:loaders-teardown` slot symmetric
to `:loaders`.

**Tutorial impact:** Chapter 8 of the tutorial outline (Loaders +
async) cannot land cleanly. Readers writing `:loaders
[[:ws/subscribe]]` find their browser console flooded with messages
after clicking "next variant." This is a real foot-gun.

**Direction:** add an optional `:loaders-teardown` slot on
`reg-variant` (or a `:teardown` slot symmetric to `:init` on
`:frame-setup` decorators). Spec drift candidate — not just a doc
fix.

**Status:** filed as `rf2-NEW-3` (= F-3) in the prior finding.

### [C-4] No per-story-rollup docs page (P3, S)

Storybook's `Component.docs` (or `<Meta of={...} />` in MDX) gives
each component a single docs page that aggregates all its variants.
Story's docs pane is per-variant; a parent-story-level rollup is
explicitly v2 per `008-Docs-Mode.md` §Out of scope ("Storybook-style
story-level docs pages").

**Tutorial impact:** a design-system-style "one page per component
showing every state" cannot be authored directly — readers route
this through `:prose` workspaces, which works but feels indirect.
A reader from Storybook expects `Component.docs` to "just exist".

**Direction:** add a `:docs` mode tab variant that selects "story
rollup" when a parent story id is selected in the sidebar (vs. a
specific variant). Composes the existing six-section autodocs across
the story's variants. No new authoring surface — purely a chrome
projection.

**Status:** **NEW gap, not in prior tutorial-walk findings.** The
spec acknowledges this as v2; this manifest surfaces it as a
candidate to promote to v1.1 since rollup docs are foundational to
Storybook's docs UX.

### [C-5] "Args" mental model is undermarketed (P3, S — doc-only)

Story has args. Five-layer precedence. Schema-derived controls. All
of this is strictly stronger than Storybook's args + argTypes —
**but a Storybook reader landing on `001-Authoring.md` sees `:args`
treated as one slot among many on the variant body, not as THE
central concept.** Storybook leads with Args; the tutorial set
should too.

**Tutorial impact:** Chapter 5 (`docs/story/01-first-story.md`)
mentions args but the conceptual frame ("Args are the typed
parameter surface for stories — one map describes the variant's
inputs, drives the controls panel, gates visual-regression keys,
feeds the MCP write surface") needs explicit branding.

**Direction:** doc-only — promote args to a top-level §Args section
in `001-Authoring.md` (currently it sits under the worked examples).
Pair with F-5 (schema → controls auto-derivation promotion).

**Status:** **NEW insight gap, partly overlaps F-5.** File as a
follow-on bead alongside F-5; consider folding them together.

### [D-1] No `npx storybook init`-style one-liner scaffolder (P3, S)

Storybook's `npx storybook init` walks the user through framework
detection, deps install, config scaffolding, and example-story
generation in one command. Story has the `tools/template/` artefact
which scaffolds a working app + stories — but there is no published
one-liner that wraps it. A new user has to read the template
README, copy files manually, and stitch the boot ceremony.

**Tutorial impact:** Chapter 2 (Install + first story) lands but
requires more reader effort than Storybook's `init`. Three lines
isn't bad, but it's not "one command."

**Direction:** wrap the template artefact with a `bb`-style one-liner
or `clojure -X` entry point that scaffolds the user's stories
namespace + the boot call. Optional: a `bb new` template that
`clojure -Sdeps` can resolve.

**Status:** **NEW DX gap, not in prior tutorial-walk findings.**
File as follow-on bead.

### [D-2] `install-canonical-vocabulary!` boot ceremony (P4, S)

Every Story consumer must call `(story/install-canonical-vocabulary!)`
exactly once at boot — or registrations fail with
`:rf.error/unknown-tag`. Storybook has no equivalent — boot is
implicit.

**Status:** filed as `rf2-NEW-11` (= F-11) in the prior finding.
**Partial fix already landed:** rf2-p1ydc (canonical-vocabulary
auto-install on first `reg-*`) per the most recent commit
(a7bed2e1e). Worth verifying whether F-11 is now closed or whether
there's a residual edge.

### [D-3] No tutorial-shape Playwright recipe for end-users (P3, S)

The CLJS Story project ships `story_feature_load.cjs` +
`story_browser_scenarios.cjs` (gate-level coverage) +
`serve-and-run-story-play-scripts.cjs` (CI runner for `:play-script`
variants). But there is no **"here's the minimum Playwright probe
for YOUR Story-using app"** recipe doc. A reader from Storybook's
test-runner docs lands and has to read the gate's source.

**Status:** filed as `rf2-NEW-6` (= F-6) in the prior finding. The
spec slot exists at `tools/story/spec/Tutorial-Playwright.md` (file
created); audit didn't verify whether content was written.

### [I-1] Frame-as-isolation is the stateful-stories answer (P3, S — doc-only)

Storybook recommends `useArgs()` and the Args panel for stateful
stories — with the limitation that hooks-inside-stories trip React
re-render. Story's answer is **structurally better**: every variant
IS a frame, two variants of the same story have independent app-dbs,
no cross-cell bleed in a `:variants-grid` workspace.

**Status:** filed as `rf2-NEW-4` (= F-4) in the prior finding.
Doc-only addition to `001-Authoring.md`.

### [I-2] Schema → controls auto-derivation is THE controls story (P3, S — doc-only)

The schema-derivation pipeline (`001-Authoring.md` §Schema-derivation
pipeline) reads as implementation detail. A reader expecting
`argTypes: { size: { control: { type: 'range', min: 0, max: 100 } } }`
needs to see Story's shorter answer (`[:int {:min 0 :max 100}]` on
the view's Malli schema) as the headline.

**Status:** filed as `rf2-NEW-5` (= F-5) in the prior finding.
Doc-only — promote the schema-derivation table to a top-level
§Controls section.

## Deliberately-deferred Storybook capabilities (NOT gaps)

These are SB capabilities Story rejects by design. They are NOT gaps;
they are divergent-by-design. The locks are in
`DESIGN-RATIONALE.md` §Rejected. Listing them here keeps the audit
honest — they appear in the per-axis table as
"divergent-by-design" with the corresponding line below as the
rationale pointer.

| SB capability | Story stance | Lock | Why it's not a gap |
|---|---|---|---|
| Composition / multi-host federation | v2 roadmap | `005-SOTA-Features.md` §v2 ship list | Local cross-lib already works via the registrar; remote-host federation is a Storybook-as-service feature, not a workshop feature. v2 if demand surfaces. |
| First-party visual-regression service | rejected | `DESIGN-RATIONALE.md` §Rejected: first-party VR service | Pixel diff + baseline storage + PR review UX is a service. Story emits the `snapshot-identity` hook; pixel capture is downstream. Same posture as Ladle / RC / Histoire. |
| Custom Storybook theme overrides (brand-pink-on-cold-grey commodity look) | rejected | `DESIGN-RATIONALE.md` §Rejected: brand-pink commodity chrome | Story's chrome identity (warm-slate + amber + Plex + motion) IS the identity surface. Theme tokens are public for third-party panels (rf2-2rwdc / rf2-i3i5j); the chrome itself doesn't theme. |
| CSF Factories (JS) | rejected | `DESIGN-RATIONALE.md` §Rejected: CSF Factories | EDN-first variant bodies are strictly stronger. Accepting `:render` fn-slots reimports closure-based authoring. |
| Component-co-located fixtures (RC `.fixture.tsx`) | rejected | `DESIGN-RATIONALE.md` §Rejected: component-co-located fixtures | Registry IS the structure mechanism; file-system convention would be a second source of truth. |
| Statechart visualisation engine | delegated | `DESIGN-RATIONALE.md` §Rejected: statechart visualisation engine | Owned by future `day8/re-frame2-machines-viz` — auto-layout for hierarchical statecharts with parallel regions is specialised work and shouldn't land on every Story consumer's bundle. |
| Pixel-scrubber UI (BackstopJS slider) | rejected | `DESIGN-RATIONALE.md` §Rejected: pixel-scrubber UI | Causa's epoch panel scrubs through data-space state — strictly better for re-frame2 apps than pixel-space scrubbing. |
| Throw-on-first-failure assertion semantics | rejected | `DESIGN-RATIONALE.md` §Rejected: throw-on-first-failure | Record-don't-throw shows the full picture of what went wrong vs. Storybook's "first failure halts everything." |
| In-process MCP server | rejected (delegated) | `DESIGN-RATIONALE.md` §separate-mcp-jar | stdio + JSON-RPC machinery doesn't belong in every Story consumer's bundle. `tools/story-mcp/` is the separate jar. |

## Polish posture vs Storybook

The prior tutorial-walk finding §5 (Visual polish observations) covers
this dimension; the verdict was **"chrome competitive with Storybook 9
at the chrome+ergonomics level. Identity ahead."** No new polish gaps
were found in this audit pass.

Three polish dimensions explicitly evaluated:

| Dimension | Story | Storybook | Verdict |
|---|---|---|---|
| Identity (palette, typography, motion) | warm-slate + amber + Plex + 180ms motion | cold-grey + brand-pink + Nunito Sans | **Story ahead** (rf2-38pb9 audit verdict; locked in §Rejected) |
| Toolbar information density | 5-cluster (MODES \| DATA \| VIEW \| DEBUG \| REC) with small-caps cluster labels | flat list of toggles | **Story ahead** |
| Sidebar glyph rhythm | 5 SVG glyphs, amber-diamond per-row, amber-active border | folder/document icons | **Story ahead** |
| Hot-reload speed | Reagent + shadow-cljs `:watch` (sub-second for variant-body edits) | Vite HMR (~100ms typical) | **Storybook marginally ahead** on raw HMR latency, but Story's fingerprint-based reactive reload is correctness-better — no module-graph quirks |
| Animation timing | 180ms tab fade, diff-flash on app-db change, `--motion-scale` seam | mostly instant | **Story ahead** |
| Focus rings + a11y posture | rf2-p1ai7 + rf2-u01y5 + rf2-07m13 (modal ARIA, arrow-key nav, accessible names) | mature; baseline | **Equivalent at this gate** |
| HCM + forced-colors | rf2-ubhmn + rf2-846h2 (toggle + dogfood) | not first-party | **Story ahead** |
| QR-share popover | local SVG via `qrcode-generator`, no third-party egress | brand-pink image proxy via `api.qrserver.com` | **Story ahead** (privacy posture + chrome identity) |

No polish-gap beads filed. The chrome differentiators are intentional
and the identity is ahead.

## Filing plan (Phase B input for Mike)

The candidates for Phase C implementation beads are categorised below by
priority. The prior tutorial-walk finding already filed `rf2-NEW-1`
through `rf2-NEW-12`; this manifest **adds five new candidates**
([C-2] / [C-4] / [C-5] / [D-1] / [D-3-followup]) and **catalogues all
prior findings under the [F-N] cross-reference**. Mike's Phase B
decision is which subset to action.

### P2 candidates (block tutorial completeness)

| Candidate | Source | Scope | Notes |
|---|---|---|---|
| **C-1 / F-1** — Global decorator primitive | prior finding | M | `rf2-NEW-1` filed; `configure! :global-decorators` slot |
| **C-3 / F-3** — Loader-teardown hook | prior finding | M | `rf2-NEW-3` filed; spec drift |
| **F-2** — Loader failure modes doc | prior finding | S (doc) | `rf2-NEW-2` filed; addition to `002-Runtime.md` |

### P3 candidates (polish / discoverability)

| Candidate | Source | Scope | Notes |
|---|---|---|---|
| **C-2** — MDX / rich-prose rendering | **THIS AUDIT (new)** | S-M | Re-evaluate the `pre-wrap` deferral; ship a `:rf.story.panel/markdown-prose` first-party panel |
| **C-4** — Per-story-rollup docs page | **THIS AUDIT (new)** | S | Promote v2 → v1.1; chrome projection only |
| **C-5** — "Args" headline branding | **THIS AUDIT (new)** | S (doc) | Doc reorg; pair with F-5 |
| **D-1** — `init`-style scaffolder one-liner | **THIS AUDIT (new)** | S | Wrap `tools/template/`; one-command boot |
| **D-3 / F-6** — Tutorial Playwright recipe | prior finding | S (doc) | `rf2-NEW-6` filed; verify Tutorial-Playwright.md content |
| **I-1 / F-4** — Stateful variants doc subsection | prior finding | S (doc) | `rf2-NEW-4` filed |
| **I-2 / F-5** — Schema-derived controls headline | prior finding | S (doc) | `rf2-NEW-5` filed |
| **F-7** — `:fx-override` last-wins asymmetry doc | prior finding | S (doc) | `rf2-NEW-7` filed |
| **F-8** — Tutorial-Embed.md / share+embed recipe | prior finding | S (doc) | `rf2-NEW-8` filed (file already exists; content TBD) |
| **F-10** — `:play` vs `:play-script` canonical decision | prior finding | — | **RESOLVED** via rf2-0wrud / commit 3f5ae2512 (2026-05-20): `:play` removed entirely; `:play-script` is the only canonical slot. Tutorial set should use `:play-script` everywhere |

### P4 candidates (naming / ergonomic nits)

| Candidate | Source | Scope | Notes |
|---|---|---|---|
| **F-9** — `:for` slot doc addition | prior finding | S (doc) | `rf2-NEW-9` filed |
| **D-2 / F-11** — Auto-install canonical vocabulary | prior finding | S | `rf2-NEW-11` filed; **partially shipped via rf2-p1ydc** — verify residual scope |

### Phase D (tutorial set) gate

The shipped `docs/story/01..07` tutorial chapters cover seven concepts.
The prior finding's 18-chapter outline maps the full tutorial ladder.
Phase D (tutorial set written end-to-end) gates on most P2 gaps
landing — specifically C-1, C-3, F-2 — plus the P3 doc-only items
(C-5, I-1, I-2, F-7, F-8, F-10) which are independent and parallel-
dispatchable.

## Cross-references

- Prior tutorial-walk finding: `ai/findings/2026-05-20-story-tutorial-set.md` (local-only; rf2-l6jev).
- Parallel API audit: `ai/findings/2026-05-20-tools-story-api-review.md` (local-only; rf2-u6o12).
- Shipped tutorial: `docs/story/index.md` + chapters 01..07.
- Story spec: `tools/story/spec/000-Vision.md` … `016-Design-Tokens.md`; `API.md`, `Conventions.md`, `DESIGN-RATIONALE.md`, `Principles.md`.
- Storybook React docs: https://storybook.js.org/docs/get-started; https://storybook.js.org/docs/writing-stories/.
- Audit bead: rf2-2jdh9.
