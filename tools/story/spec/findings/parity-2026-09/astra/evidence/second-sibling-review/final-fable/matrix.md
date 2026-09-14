# Matrix — Story vs Storybook-class workshops (rf2-rln91)

- **Written**: 2026-09-14 10:08 AUSEST. **Revised**: 2026-09-14 11:05 AUSEST at trunk `ece9b657be90e99461773b859a767acdd97662dd` — row A7 added; B6 re-scored in the body; three reference cells raised to `sx`; every total recomputed; the appendix folded into the rows (see §Revision history at the end).
- **Trunk pinned**: `98e8ffe9cb339295fe2fa459900d9d9647fab015` (read 09:41:33 AUSEST; re-read 09:59:57 AUSEST, unchanged) for every measurement; the Story-path diff from that sha to the revision sha is empty (`evidence.md` §0, §L). R1 (rf2-allgj) then landed at `4ec3fa6c57` and moved `docs/story/index.md`; A1's cell is as measured at the pin, and every `index.md` line cited below is pinned there (`evidence.md` §D).
- **Reference versions (read 2026-09-14)**: Storybook 10.6.0 (released 2026-09-02 per astra's citation of the GitHub release; v11 prerelease at the time; `npx storybook@latest init` in the scratch Vite app installed `storybook@^10.6.0`, `@storybook/react-vite@^10.6.0`, addon-vitest, addon-a11y, addon-docs, addon-mcp preview, chromatic); `@storybook/addon-vitest@10.6.0` declares Vitest `^3 || ^4` while npm latest Vitest is 5.0.0 (`astra/evidence/npm-baselines.json`); Playwright CT 1.63 (repo runs 1.59.1); MSW 2.15; Histoire 1.0.0-beta.1; Ladle 5.1.1; React Cosmos 7.4.1; Widgetbook 3.25 (v4 beta as a design reference only); elm-book 1.5.1; Nubank workspaces (2024); Portfolio 2026.03.1.
- **Rules**: `rubric.md`. Scores `S` (Story) / `B` (reference) on {0, 0.5, 1, 1.5, 2}; weight `w` ∈ {1,2,3}; evidence strength per side (`ex` exercised, `sx` sibling-exercised, `st` source-traced, `do` documented-only, `un` unverified). Ceremony counted per `rubric.md` §4.
- **Probes referenced**: `probe.clj` cases A–P, `probe2.clj` H2/H3/K/F2/Q/R/S/T/U, `probe3.clj` V1–V3, `probe4.clj` (promotion), `probe5.clj` (upgrade snippet), story-mcp sessions `mcp-requests.jsonl` #1–#18 and `mcp-requests2.jsonl` #1–#8, Playwright walks of `:8043` (login-form testbed) and `:8040` (nine-states), scratch consumer app on `:8090`, Storybook `init` timing; sibling receipts under `ai/findings/Story/astra/evidence/` where a cell says `sx`. Outputs in `evidence.md`.

## Summary table

| id | Job | w | S | B | Status | Story ev. | Ref ev. | Auto |
|---|---|---|---|---|---|---|---|---|
| A1 | Install into an existing app and see a working workshop in the first session (**NEGATIVE control**) | 3 | 0.5 | 2 | **GAP** | ex | ex | manual |
| A2 | Author the first story + variant for an existing view | 3 | 2 | 2 | MATCH | ex | ex | auto |
| A3 | Explore view states through controls without hand-writing them | 2 | 1 | 2 | **UNDERMARKETED** | ex/st | ex | auto |
| A4 | See many application STATES side by side, each its own app | 2 | 2 | 1 | WIN | ex | do | manual |
| A5 | Read docs beside the example without writing them | 2 | 2 | 2 | MATCH | ex | ex | auto |
| A6 | Share a specific state by URL, honestly | 1 | 2 | 1.5 | WIN | ex | do | manual |
| A7 | Isolate the DOCUMENT across side-by-side states: CSS, focus, portals, timers (**new in revision**) | 1 | 0.5 | 1.5 | **GAP** | st (+sx) | do | manual |
| B1 | Turn a bug report into a failing, re-runnable variant using data only (**POSITIVE control**) | 3 | 2 | 1 | **WIN** | ex | ex/sx | auto |
| B2 | Run variants as tests locally and in CI with one result shape | 3 | 2 | 2 | MATCH | ex | sx | auto |
| B3 | Stub a side effect without touching production code | 2 | 2 | 2 | MATCH | ex | do | auto |
| B4 | Seed state at a declared fidelity rung and be told which rung | 2 | 2 | 0.5 | WIN | ex | do | auto |
| B5 | See why a variant failed: beats, db change, effects, source | 3 | 1.5 | 1 | WIN | ex (+sx) | sx | manual |
| B6 | Capture an ad-hoc exploration and promote it to a curated variant | 1 | 1 | 0 | WIN (with defect) | ex (+sx) | do | auto |
| B7 | Run visual and a11y checks beside the example, locally | 2 | 1 | 1.5 | **GAP / TARGET** | st (+sx) | do | manual |
| C1 | Discover stories, variants and their contracts by tool call | 2 | 2 | 1.5 | MATCH | ex | do | auto |
| C2 | Run → read failure → fix → re-run from an agent, no browser (incl. gated writes) | 3 | 2 | 1 | **WIN** | ex | do | auto |
| C3 | Drive the live browser workshop from the agent | 1 | 1 | 1 | DIVERGENT | ex | do | manual |
| C4 | Explain a variant's effective plan before running it | 2 | 1.5 | 0 | WIN (with defect) | ex (+sx) | — | auto |
| X1 | Compose shared context without decorator opacity | 1 | 2 | 2 | MATCH | st | do | manual |
| X2 | Publish a static workshop build | 1 | 1 | 2 | GAP | do | do | manual |
| X3 | Extend the workshop | 1 | 1 | 2 | DIVERGENT | st | do | manual |
| X4 | Ecosystem breadth, hiring pool, hosted-service economics | — | — | — | OUT | — | — | — |

## Totals

Scored rows n = 21 (X4 OUT). Σw = 41. (First pass: n = 20, Σw = 40, B6 at S 1.5.)

| Total | Story | Reference |
|---|---|---|
| Unweighted Σ / (2·n) | **32 / 42 = 0.76** | **29.5 / 42 = 0.70** |
| Weighted Σ w·score / (2·Σw) | **65.5 / 82 = 0.80** | **58 / 82 = 0.71** |
| Parity ratio (weighted) | **1.13** (unweighted 1.08) | |

The number moved from 1.16 (first pass) to 1.15 (B6 re-scored after the sibling probes) to 1.13 (A7 added). Nothing in the surplus rows moved; the number went down because two things the first pass did not score — a promotion that registers a variant which cannot fail, and a canvas that shares one document — are now on the sheet.

**Doorstep versus substance** (`rubric.md` §5):

| Half | Rows | Σw | Story | Reference | Ratio |
|---|---|---|---|---|---|
| doorstep (first session) | A1–A7 | 14 | 20 / 28 = **0.71** | 25 / 28 = **0.89** | **0.80** |
| substance (once running) | B1–B7, C1–C4, X1–X3 | 27 | 45.5 / 54 = **0.84** | 33 / 54 = **0.61** | **1.38** |

Range (Story only, per `rubric.md` §5):

| Bound | Rule applied | Unweighted | Weighted | Ratio vs reference |
|---|---|---|---|---|
| pessimistic | X2 (`do`) −0.5, B7 (`st`+`un`) −0.5; A7 is `st` and keeps its score | 31 / 42 = 0.74 | 64 / 82 = 0.78 | 1.10 |
| point | as scored | 32 / 42 = 0.76 | 65.5 / 82 = 0.80 | 1.13 |
| optimistic | A1 +1 (docs + npm fix), A3 +1 (schema on shipped examples), B5 +0.5 (evidence link), C4 +0.5 (story-level args in explain), B6 +0.5 (promotion keeps the expectation) | 35.5 / 42 = 0.85 | 73.5 / 82 = 0.90 | 1.27 |

**Two cuts and a bound added in the second sibling review (2026-09-14 14:25 AUSEST).**

| Cut (grok's: chrome versus application state) | Rows | Σw | Story | Reference | Ratio |
|---|---|---|---|---|---|
| workshop chrome | A1, A2, A3, A5, A6, A7, B2, B7, C1, C3, X2, X3 | 22 | 31 / 44 = **0.70** | 40 / 44 = **0.91** | **0.78** |
| application state | A4, B1, B3, B4, B5, B6, C2, C4, X1 | 19 | 34.5 / 38 = **0.91** | 18 / 38 = **0.47** | **1.92** |

The two halves sum to the totals above (22 + 19 = 41; 31 + 34.5 = 65.5; 40 + 18 = 58). Assignment rule: a row is chrome when a JS workshop supplies the same machinery and the difference is shape or polish; it is application state when the answer comes from frames, plans, fx stubs, the epoch tape or fidelity.

| Bound (astra's objection: more of Story was executed than of the reference) | Rule | Reference weighted | Ratio vs Story point | Ratio vs Story pessimistic |
|---|---|---|---|---|
| reference-generous | every reference cell with evidence `do` and score < 2 gains 0.5: A4, A6, A7, B4, B6, B7, C1, C2, C3 (nine cells; +7.5 weighted, +4.5 unweighted) | 65.5 / 82 = **0.80** (unweighted 34 / 42 = 0.81) | **1.00** | **0.98** |

C4's reference cell is `—` (no Storybook equivalent to `explain`) and is not raised; X1–X3 are already at 2. Read beside the collapse caveat below: two independent corrections, one to Story's side and one to the reference's, both bring the ratio to 1.00, which is why the categorical headline leads.

**Collapse caveat.** B1, B2, B4, B5 and C2 are five views of one design decision (the variant is a plan and the result is a tape). Collapsing them into one w3 WIN row (S 2, B 1) reads Story 45 / 60 = 0.75 against reference 45 / 60 = 0.75 — ratio **1.00** exactly. A reader who rejects that design decision should read the ratio that way.

**Community cap removed** (`rubric.md` §5): the cap touched no cell; every total above is the same with it and without it.

**Symmetric haircut, shown not adopted** (`rubric.md` §7): the 13 reference cells that are `do` (A4, A6, A7, B3, B4, B6, B7, C1, C2, C3, X1, X2, X3; B2 and B5 left the set when astra's fixture exercised them) lose 0.5 → reference 23 / 42 = 0.55 unweighted, 48 / 82 = 0.59 weighted; ratio would read 1.36. Not adopted because those features are exercised daily by Storybook's market.

Where the surplus and the deficit sit (weighted contribution S−B per row): surplus B1 +3, B4 +3, C2 +3, C4 +3, A4 +2, B5 +1.5, B6 +1, C1 +1, A6 +0.5; deficit A1 −4.5, A3 −2, A7 −1, B7 −1, X2 −1, X3 −1. Six rows at 0 (A2, A5, B2, B3, C3, X1 — the first pass wrote "nine", which was a miscount). The deficit is the first hour and the parts of the workshop a Storybook user sees on day one; the surplus is the parts only a re-frame2 user can see.

## Rows

Each row: job · weight defence · Story layers (SPEC / CODE / DOCS / ERGONOMICS) · reference cell · evidence · notes. File paths are repo-relative; line numbers pinned at the sha above and re-verified at the revision sha (`evidence.md` §L).

### A — first-hour author

#### A1 · Install into an existing app and see a working workshop in the first session — w3 · S 0.5 · B 2 · **GAP** (NEGATIVE control)
- **w3**: the first session decides adoption.
- **SPEC**: `tools/story/spec/Feature-Parity-Audit.md:42` records [D-1] "No `npx storybook init`-style scaffolder" as open; `docs/story/index.md:92` states "The generator template emits no Story wiring" (confirmed: `tools/template/{deps.edn,shadow-cljs.edn}` carry no `story` token).
- **CODE**: `re-frame.story/mount-shell!` (`tools/story/spec/API.md:476`, 1-arity).
- **DOCS**: `docs/story/index.md` §Install Story — **wrong in two places and silent in one at this sha**: `:110` requires `my-app.adapters.reagent`, a namespace that does not exist (the adapter is `re-frame.adapter.reagent`); `:116` calls `(story/mount-shell! node {})`, 2-arity, against the 1-arity fn — shadow-cljs compiles it with `Wrong number of args (2) passed to re-frame.story/mount-shell!` (exercised); and the page says nothing about npm although the shell's dependency closure needs `@xyflow/react`, `elkjs` (via `tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs:37`) and the `markdown-it` family, declared only in `implementation/package.json` devDependencies. The `:local/root "../re-frame2/tools/story"` at `:100` assumes a clone beside the project — and `:94` says so in words, so it is a documented assumption and not a defect (astra's correction; the first pass counted it as a third wrong place).
- **ERGONOMICS**: ceremony **≥ 9** as exercised (deps.edn alias, shadow-cljs.edn, package.json, index.html, core.cljs edit, stories.cljs, three npm-install rounds discovered by compile failure, one arity fix). Scratch app timeline in `evidence.md` §J1: `npm install` 3 s; first compile failed at 21 s (`@xyflow/react`); second at 14 s (`elkjs`); third succeeded at 50 s with the arity warning; fourth compile after the arity fix 25 s, and the walk then works: shell mounts with a first-run "Welcome to the Story Playground" help dialog (Esc closes it), `/idle` renders "Sign in" with Explain/Tests/Docs tabs, 0 console errors. Once it runs, it is the same shell as the in-repo testbeds — the whole deficit is getting there. Grok's stall log adds two the literal walk did not meet: `tools/story/README.md:50` passes `:component login-form` (a referred fn) where `schemas.cljc:479` wants a keyword, and chapter 01 gives no watch URL (the command lives in a comment in `implementation/shadow-cljs.edn`).
- **Reference**: Storybook `npx storybook@latest init --yes --no-dev` in a fresh Vite React app: 105 s, 216 MB / 177 packages, installs addon-vitest, addon-a11y, addon-docs, addon-mcp, chromatic and Playwright Chromium; `npm run storybook` afterwards. Ceremony 2 (one command, one run). Exercised. (Astra did not time the initializer; its fixture install of 226 packages took 24 s on a warm cache — a different task, not a second timing.)
- **Evidence**: Story ex (`evidence.md` §J1), reference ex (§J0).
- **Notes**: the control behaves as predicted (GAP, S < B), so the rubric is not biased toward Story. The reasons are all S-sized and all in docs/packaging; none is in Story's model. Filed: rf2-allgj (page) — landed at `4ec3fa6c57` after this cell was scored; re-score A1 on the next run — blocking rf2-seb9h (npm closure); the scaffolder (R2) is held.

#### A2 · Author the first story + variant for an existing view — w3 · S 2 · B 2 · MATCH
- **w3**: the daily act.
- **SPEC**: `spec/007-Stories.md` (Story/Variant/Workspace split); `tools/story/spec/001-Authoring.md`.
- **CODE**: `re-frame.story/reg-story`, `reg-variant` (`tools/story/src/re_frame/story.cljc`); first registration auto-installs the canonical vocabulary (`install-canonical-vocabulary!` at `:648`, audit [D-2] landed).
- **DOCS**: `docs/story/01-first-variant.md`; `tools/story/testbeds/login_form/stories.cljs`; `examples/core/login/stories.cljs`.
- **ERGONOMICS**: ceremony 2 (one `stories.cljs`, one `mount-shell!` line). The variant is a map: `{:doc … :args … :setup […] :tags …}`. Exercised: `probe.clj` A/B, story-mcp `register-variant` #3.
- **Reference**: CSF3 `export default { component }` + `export const Idle = { args }` in one file, autodocs tag; CSF Next factories add typing. Ceremony 2. Exercised in the generated `Button.stories.ts`.
- **Evidence**: ex / ex.
- **Notes**: parity at the authoring act. The difference that matters is downstream (B1): Story's body is data with no fn slots, CSF's is a module.

#### A3 · Explore view states through controls without hand-writing them — w2 · S 1 · B 2 · **UNDERMARKETED**
- **w2**: a named `018` §3.1 bar ("Controls: edit-to-render feels immediate… inline validation").
- **SPEC**: `tools/story/spec/001-Authoring.md` §Schema-derivation pipeline; `Feature-Parity-Audit.md:96` scores `argTypes` as WIN "auto-derived from Malli"; `:52` [I-2] "Schema → controls auto-derivation is THE controls story".
- **CODE**: present and pinned by 7 test files (schema → controls); `plan.cljc:641` `validate-effective-args`.
- **DOCS**: taught in `docs/story/` but **no shipped testbed or example registers a view schema** — `reg-view` in `tools/story/testbeds/login_form/views.cljs`, `examples/core/login/`, `examples/patterns/nine_states/` carry no `:schema`/`:rf/props`, so the walk of `:8043` shows "no schema registered for the variant's :component" and no derived controls anywhere a first-hour author looks. (Astra scopes the finding to the login entry example it inspected; the three shipped sites above are the ones this pass read, and the claim is about them.)
- **ERGONOMICS**: ceremony 1 once a schema exists (add `:schema` to `reg-view`); ceremony unknown to the author because nothing they can open demonstrates it. The plain args control does work: astra edited the heading and the change stayed on its own cell in the grid (`astra/evidence/story-journeys.json`).
- **Reference**: controls inferred from TS props / `argTypes` in the init sample, immediately; astra's inferred heading control updated the rendered component too. Exercised.
- **Evidence**: Story ex (negative: walk shows the fallback text) + st (code path); reference ex.
- **Notes**: the audit's WIN is real in code and invisible in practice — exactly the UNDERMARKETED definition. Fix is S: put a schema on the login-form testbed view (rf2-wmoer).

#### A4 · See many application STATES side by side, each its own app — w2 · S 2 · B 1 · WIN
- **w2**: `018` §3.1 "Matrices: variant grids remain scannable".
- **SPEC**: `spec/007-Stories.md` workspaces; `tools/story/spec/018` §3.1.
- **CODE**: `:variants-grid` / `:grid` workspaces (`tools/story/src/re_frame/story/ui/workspace.cljc`), per-variant frame isolation (`frames.cljc:18–21` `:preset :story`).
- **DOCS**: `docs/story/02-every-state-side-by-side.md`; `examples/patterns/nine_states/stories.cljs`.
- **ERGONOMICS**: ceremony 0 for the auto-grid (it exists for every story). Exercised on `:8043`: 5 isolated cells rendered, Xray cascade per cell with source coords; astra's heading edit on the error variant stayed on that cell. **Defect**: the sidebar label reads "VARIANTS-GRID · 0" for a grid that renders 5 cells — root cause `ui/sidebar.cljs:621–627`: `workspace-grid-grouping` counts the body's explicit `:variants` and reports "else 0", and the auto grid enumerates by `:for` (grok's diagnosis, verified). Filed as the count half of rf2-dacnd. The first pass also reported the mode tabs as not ARIA tabs; that is **retracted** (`mode_tabs.cljs:174–182` carries `role="tablist"`/`role="tab"`; `evidence.md` §T).
- **Reference**: no built-in grid in Storybook 10.6; Chromatic or MDX `<Canvas>` composition; Histoire has `layout: grid`; Nubank workspaces and Portfolio are grid-first for COMPONENT states (grok's point: the grid is not the invention; five isolated app-dbs of one real machine is). `do`.
- **Evidence**: ex / do.
- **Notes**: this row is STATE isolation only. The document — CSS, focus, portals, timers — is A7, where the reading inverts.

#### A5 · Read docs beside the example without writing them — w2 · S 2 · B 2 · MATCH
- **w2**: `018` §3.1 "Docs: readable without becoming generated trace dumps".
- **SPEC**: `tools/story/spec/008` §Per-story rollup (rf2-8j7wg; audit [C-4] landed).
- **CODE**: `tools/story/src/re_frame/story/ui/docs.cljc:1175`, `ui/shell.cljs:836`; story-mcp `get-docs-markdown`.
- **DOCS**: `docs/story/api/mcp-surface.md`.
- **ERGONOMICS**: ceremony 0. Exercised: Docs tab on `:8043`; `get-docs-markdown` (mcp2 #6) returns a Markdown rollup with args/argtypes/tags/decorators/variants.
- **Reference**: `autodocs` tag + MDX. Exercised in init sample.
- **Evidence**: ex / ex.

#### A6 · Share a specific state by URL, honestly — w1 · S 2 · B 1.5 · WIN
- **w1**: valuable, not adoption-deciding.
- **SPEC**: `018` §3.1 "Sharing: honest about privacy".
- **CODE**: share dialog with `?variant=` and reproducibility labels; `preview-variant` returns `:share-url` (mcp2 #5).
- **DOCS**: `docs/story/08-snapshot-identity-and-sharing.md`.
- **ERGONOMICS**: ceremony 1 (open dialog). Exercised on `:8043`: labels distinguish what the URL reproduces from what it does not.
- **Reference**: `?path=/story/…&args=` reproduces args, says nothing about state/fidelity. `do`.
- **Evidence**: ex / do.

#### A7 · Isolate the DOCUMENT across side-by-side states: CSS, focus, portals, timers — w1 · S 0.5 · B 1.5 · **GAP** (new in revision)
- **w1**: not on the `018` §3.1 bar list (the file names isolation nowhere but bundle isolation — `evidence.md` §G), and no real user has named a CSS-isolation job; state isolation, which does carry the thesis, is A4 (`rubric.md` §6).
- **SPEC**: `spec/007-Stories.md` and `tools/story/spec/002-Runtime.md` promise per-variant FRAME isolation (app-db, effects, `:fx-overrides`, loader teardown); nothing promises document isolation. "—" for the document.
- **CODE**: the canvas is a stamped `div` in one page — `[:div {:data-rf-story-variant-root (pr-str variant-id)}]` at `ui/canvas.cljs:728` and `ui/workspace.cljc:450`; `iframe` reads 0 across `tools/story/src/re_frame/story/ui` (control: `Tutorial-Embed.md` 32, a different job). Frame-owned teardown exists for loaders (`:loaders-teardown`); nothing catches a stray timer, a portal to `document.body`, focus, or a stylesheet.
- **DOCS**: `docs/story/02-every-state-side-by-side.md` names none of CSS, portal, focus, iframe, stylesheet or document (grep 0). "—", and the isolation-honesty paragraph in `report.md` §6 is the fix.
- **ERGONOMICS**: a planted CSS leak was **not** run by this pass, astra, or grok — the score is source-traced. One leak was measured by accident: astra's axe pass found the login heading at `rgb(237,235,230)` on the card's white, and the colour is Story's own `:text-primary` token (`theme/colors.cljc:100`) set on the canvas wrap (`canvas.cljs:64`) and inherited by an `h3` that sets no colour (`login_form/views.cljs:111`) — chrome CSS leaking into the subject (`evidence.md` §C). Scored 0.5: the frame does part of the job (state, effects, loaders); the document half is manual.
- **Reference**: a per-story preview iframe isolates CSS, focus and portals by construction; Storybook's inline Docs stories (`inline` rendering, the default on docs pages) share the document and do not, and `inline:false` carries a documented controls limitation (astra's citation of the `Story` doc-block page). `do`. Histoire's single view is an iframe; its grid is not.
- **Evidence**: st (+sx for the accidental leak) / do.
- **Notes**: GAP by the rubric (S < B, SPEC empty for the document). The honest consequence is in `report.md` §6.6: no iframe canvas unless a design-system CSS-isolation job is named by a real user; the cheap answer is one paragraph in chapter 02 and, for the specimen above, possibly a `color` reset on the variant root (a product call, held).

### B — maintainer: bug → failing variant → fix

#### B1 · Turn a bug report into a failing, re-runnable variant using data only — w3 · S 2 · B 1 · **WIN** (POSITIVE control)
- **w3**: the core of `018` §3.1 "variants are executable application plans".
- **SPEC**: `tools/story/spec/017-Testing-Story.md` §Run result (`:2160`), `:rf.assert/*` record-don't-throw.
- **CODE**: `story/run` (`story.cljc:1563`), `story/is` (`:1659`), assertions in `assertions.cljc`.
- **DOCS**: `docs/story/04-the-variant-is-a-test.md`.
- **ERGONOMICS**: ceremony 1 (one map). Exercised: `probe.clj` C → `:fail`; mcp #2 → `:fail` with `:reason "expected 2 at [:v] but got 1"`, `:actual`/`:expected` on the record; `probe3` V3: an erroring effect handler fails the run even when every assertion passed (honest). Astra's public-run witnesses agree: `:fail` for a false path assertion, `:pass` for the nil one, `:cannot-run` for a DOM click under `:headless` (`astra/evidence/story-public-run.json`).
- **Reference**: `play` function with `expect`/`userEvent`, run by `@storybook/addon-vitest`; needs the addon, a browser and JS. `ex` (sample read) / `sx` (astra ran it: a deliberate false expectation failed with expected/received in the Interactions pane and the runner reported 2 pass / 1 fail / 0 pending — `evidence.md` §S2).
- **Evidence**: ex / ex+sx.
- **Notes**: the control behaves as predicted (WIN). The WIN is about the ENCODING (a map, no function slot, no browser); Storybook does the job.

#### B2 · Run variants as tests locally and in CI with one result shape — w3 · S 2 · B 2 · MATCH
- **w3**: `018` §3.1 "tests, docs, canvas, agent calls and replay share one plan".
- **SPEC**: `017` §Run result — "frozen public contract… one result language spoken identically across `story/run`, `story/is`, the Story UI Test mode, story-mcp `run-variant`" (`:2170–2183`).
- **CODE**: `re-frame.story.result/RunResult` Malli schema; `story/valid-run-result?`.
- **DOCS**: `docs/story/04-the-variant-is-a-test.md:107–119`.
- **ERGONOMICS**: ceremony 1 (`story/is` inside `deftest`). Exercised: same map shape on JVM (`probe`), over stdio (mcp #2/#4), and the UI Tests tab on `:8043` (astra: Run all → 5/5). **Drift**: `017:2197–2198` and `docs/story/04:118–119` list `:plan-hash`/`:run-hash` in the run result; the JVM result carries neither (`probe2` S: `:plan-hash? false :run-hash? false :evidence? false`) while `render-variant` does (`render_cljs_test.cljc:100`) and the fns exist. Root cause: `result.cljc:927–929` selects the two keys from `parts`, and `runtime.cljc` never writes them (0 occurrences; `evidence.md` §X). Filed rf2-7vz97.
- **Reference**: `@storybook/addon-vitest` (portable stories) runs every story as a Vitest test in browser mode; one CLI, watch mode, CI. `sx` — astra executed it at 10.6.0 with Vitest 4.1.11 pinned to the addon's peer range: 3 tests, 2 passed, 1 deliberate failure, 0 pending, exit 1 (`evidence.md` §S2). Raised from `do` in the revision.
- **Evidence**: ex / sx.
- **Notes**: MATCH on the job; the surpass claim ("one shape across surfaces") is verified on two of three surfaces and undercut by a two-key drift that is S to fix either way.

#### B3 · Stub a side effect without touching production code — w2 · S 2 · B 2 · MATCH
- **SPEC**: `tools/story/spec/005` §force-fx-stub; `017:2753` `[:world :network]` route replies feed `:plan-hash`.
- **CODE**: `tools/story/src/re_frame/story/fx_stubs.cljc:20` `[:rf.story/force-fx-stub <fx-id> <response>]`; frames get `:preset :story` + `:fx-overrides` (`frames.cljc:18–21`). The `:network` slot lowers to the same stubs (grok; `017` §Network) — one seam, not a third encoding.
- **DOCS**: `docs/story/03-fidelity-ladder.md`.
- **ERGONOMICS**: ceremony 1 (one decorator tuple). Exercised `probe3` V1/V2: unstubbed → `:pass`, real handler called once; stubbed → `:pass`, real calls 0, effect recorded as `:rf.story.fx-stub/force-fx-stub+probe.http`.
- **Reference**: `sb.mock` module automocking (10.x, first-party) + community `msw-storybook-addon` v3 for network. `do`.
- **Evidence**: ex / do.
- **Notes**: Story stubs at the effect boundary (the re-frame unit); MSW stubs at the network. Different unit, same job. The `:network` fixtures themselves were repaired under rf2-shx4 (closed 2026-09-07) and are not re-exercised here.

#### B4 · Seed state at a declared fidelity rung and be told which rung — w2 · S 2 · B 0.5 · WIN
- **SPEC**: `017` fidelity ladder `:real-setup` / `:db-seed` / `:sub-overrides` (`:707` "`:fidelity` lives in `:world`, participates in `:plan-hash`").
- **CODE**: `plan.cljc` (fidelity in `:world`); sub-overrides honesty in `runner`.
- **DOCS**: `docs/story/03-fidelity-ladder.md`.
- **ERGONOMICS**: ceremony 1. Exercised `probe2` Q: `:db-seed {:v 7}` → `:pass`, `explain` `:fidelity #{:db-seed}`; R: `:sub-overrides {[:probe/v] 42}` with `sub-equals 42` → `:fail`, `:actual 1` — the pinned value never satisfies the assertion, and `:fidelity #{:sub-overrides :real-setup}` says so. **Defect on the UPGRADE gesture** (not on the rungs): `ui/view_state.cljc:373` `upgrade-snippet` emits a scaffold whose last map line ends in a `;` comment, so the envelope's `})` is commented out and `edn/read-string` throws (`probe5`); astra hand-completed the body and the compiled child still carried `#{:real-setup :sub-overrides}` with the pin (`evidence.md` §P5). R0b. The score stands at 2 because the rungs are honest and exercised; the upgrade is the SPECIFIED half.
- **Reference**: decorators/providers inject state; nothing names the rung; `018` §3.1 calls this "visual examples that look trustworthy but are disconnected from application state". `do`.
- **Evidence**: ex / do.

#### B5 · See why a variant failed — w3 · S 1.5 · B 1 · WIN (with defect)
- **w3**: `018` §3.1 "failures can explain themselves" and the §10.1 gesture budget from failed assertion to evidence.
- **SPEC**: `017` §Run-result evidence projection; evidence spine; `tools/story/spec/009` §Play step-debugger.
- **CODE**: run result carries `:narrative`, `:epoch-tape` (4 epochs with `:db-before/:db-after/:effects/:trace-events` per record — `probe2` H2 output), `:effects` with `:outcome`; Xray per-panel embed with source coords (walk of `:8043`).
- **DOCS**: `docs/story/06-xray-earned-at-failure.md`.
- **ERGONOMICS**: ceremony 0–1. Exercised. **Defect**: the Tests-pane assertion row shows the placeholder "evidence spine pending — failures will link here once the evidence panel lands" (`ui/test_mode/view.cljs:559`) while the Evidence panel exists beside it; astra reproduced it on a genuinely failing variant — the evidence row has `data-evidence=true` and zero interactive descendants, with Xray and the Evidence narrative both present in the same shell (`sx`). The failed-assertion → evidence gesture the budget names is therefore not one click. Filed rf2-7etf3.
- **Reference**: Interactions panel step-through + Vitest failure output; no app-state diff, no effect log. `sx` — astra's fixture showed expected/received in the Interactions pane for the deliberate failure (raised from `do`).
- **Evidence**: ex (+sx) / sx.

#### B6 · Capture an ad-hoc exploration and promote it to a curated variant — w1 · S 1 · B 0 · WIN (with defect)
- **SPEC**: `tools/story/spec/017` §promotion (`materialize-variant-plan`, `promote-run-artifact!`); recorder → `:script` (`skills/re-frame2/references/tooling/story-recorder.md`); `018` §3.1 "generated failures promote to curated variants".
- **CODE**: `story/make-run-artifact`, `promote-run-artifact!` → `promotion.cljc:220` `artifact->variant-body`. Exercised `probe2` T (registration succeeds) and **`probe4` (what the promoted variant does)**: a source with `:assertions [[:rf.assert/path-equals [:v] 42]]` runs `:fail`; the promoted body has keys `(:doc :run-artifact)` and no `:assertions`/`:expect`; the promoted variant runs **`:pass` with 0 assertions**. Boundary: an expectation survives only when it rides the artifact's `:event-program` as a dispatched `:rf.assert/*` event (astra's positive control, `sx`); a declarative `:assertions`/`:expect` and a `[:assert …]` script step are both lost (`evidence.md` §P4). Save-current-as-variant dialog exercised on `:8043`: reports sub-overrides/db-seed/route/network/fx-overrides as "not yet projectable" (rf2-7pgiz, rf2-blw1q) — honest capture-or-warn.
- **DOCS**: `docs/story/05-recorder-and-cannot-run.md`.
- **ERGONOMICS**: ceremony 2 (record, save). Recorder itself `do` (not driven in the walk).
- **Reference**: none built in. `do`.
- **Evidence**: ex (+sx) / do.
- **Notes**: re-scored from 1.5 to **1** after the sibling probes: promotion registers a variant that cannot fail for the reason its source failed, which is half the job. Still a WIN on the row because the reference has no promotion at all; the `018` tier moves from BUILT to SPECIFIED (`report.md` §4.2). R0a.

#### B7 · Run visual and a11y checks beside the example, locally — w2 · S 1 · B 1.5 · **GAP / TARGET**
- **SPEC**: `017` DOM/visual/a11y assertion families; `identity.cljc:276` snapshot tuple, `:331` `snapshot-identity` "visual-regression services key against `[variant-id content-hash]`".
- **CODE**: assertion families present; `:cannot-run` headless (fail-closed, exercised elsewhere); story-mcp `read-a11y-violations` returns capability-unavailable on JVM (mcp #16). Snapshot identity exercised `probe2` K: stable across re-registration, moves on body change, differs across ids (by design, `017:2654`). **What the tuple contains** (`identity.cljc:294–329`, read for the revision): `:variant` and `:story` body slices, `:effective-args`, `:effective-tags`, `:view-schema-digest`, `:active-modes`, `:substrate` and the canonical-version tag — declarations, resolved args, schema and context; **not** the loaded CSS, the view implementation, fonts or any browser output. **Story's own visual assertion is a hash comparison**, not a pixel diff: `play/browser.cljc:177` passes `:rf.assert/visual-snapshot` "iff the content-hash matches", and a real-browser pixel diff is a separate `:pixels` requirement (`requirements.cljc:116,277`) nothing in the tree fulfils. No local baseline/diff runner exists.
- **DOCS**: `docs/story/08-snapshot-identity-and-sharing.md`.
- **ERGONOMICS**: needs a browser runner. UI a11y panel: astra drove it (`sx`) — axe loaded after the CDN opt-in, 0 violations on the variant, and a follow-up subject scan returned **8 `color-contrast` nodes INCOMPLETE** (`evidence.md` §C, §S2); the panel exists and works, and a clean bill must not absorb incompletes.
- **Reference**: addon-a11y (axe) installed by default; visual via Chromatic (cloud) or Vitest 5 browser `toMatchScreenshot` / Playwright `toHaveScreenshot`; Storybook itself ships no local VR. Ladle documents a compact Playwright capture/compare; Histoire's screenshot plugin only captures (astra). `do`.
- **Evidence**: st (+sx for the a11y panel) / do.
- **Notes**: TARGET: existing Playwright in the repo plus a stable comparison case is most of a local VR gate — but keyed by variant id plus named theme/viewport/browser, with the content hash as provenance only, never as a skip rule (`report.md` §6.3, R10 revised). Held for a named design-system job.

### C — agent: "make the login error state match the design"

#### C1 · Discover stories, variants and their contracts by tool call — w2 · S 2 · B 1.5 · MATCH
- **SPEC**: `tools/story-mcp/spec/002-Tool-Registry.md`; `tool-descriptors.edn` (19 tools: Dev 3 / Docs 10 / Testing 4 / Write 2).
- **CODE**: `tools/story-mcp/src/re_frame/story_mcp/`; `list-assertions` returns the canonical vocabulary with payload shape and semantics (mcp2 #7).
- **DOCS**: `docs/story/api/mcp-surface.md`; `skills/re-frame2/references/tooling/story-mcp-loop.md`.
- **ERGONOMICS**: ceremony 1 (start the server). Exercised: `tools/list` → 19; docs tools return EDN/Markdown.
- **Reference**: `@storybook/addon-mcp` (preview) `stories-preview`, manifests JSON, `llms.txt`, `storybook skills`. `do`.
- **Evidence**: ex / do.
- **Notes**: MATCH because Storybook's agent surface is real and shipping (preview); Story's is structured EDN in-process, which is what C2 turns into a WIN.

#### C2 · Run → read failure → fix → re-run from an agent, no browser — w3 · S 2 · B 1 · **WIN** (with defect)
- **w3**: `018` §3.1 "Story MCP… can drive the same plan/result path the UI uses".
- **SPEC**: `tools/story-mcp/spec/003-Write-Surface-Gating.md` (`--allow-writes`).
- **CODE**: `run-variant`, `read-failures`, `register-variant`, `preview-variant`.
- **DOCS**: `story-mcp-loop.md`.
- **ERGONOMICS**: ceremony 1 for the JVM loop. Exercised twice: mcp #1–#18 (discover → run `:fail` → read-failures → register fix → run `:pass`, 12 s wall for the whole session) and mcp2 #2–#5 (in-place re-register of the same id → `:pass`; `preview-variant` returns `:share-url`). What it proves is bounded (astra, verified against the transcript): request #3 changes the expectation from 2 to 1 while setup still sets 1, so the loop demonstrates transport and same-id re-registration, not a requirement-preserving application repair.
- **Reference**: `addon-mcp` `test-run` (preview) needs a running Storybook dev server, addon-vitest and a browser. `do`.
- **Evidence**: ex / do.
- **Defect**: `register-variant` accepted `:story.nostory/orphan` with no registered parent story (`registered? true`, mcp #17), contradicting `story-mcp-loop.md:79`. Filed rf2-bf12o.

#### C3 · Drive the live browser workshop from the agent — w1 · S 1 · B 1 · DIVERGENT
- **SPEC/DOCS**: `story-mcp-loop.md` splits authoring (this server) from running in the live browser (`re-frame2-pair` owns the door); `skills/re-frame2-pair/references/stories.md:3` — a variant in a pair session "is a frame in the browser heap on the other end of your nREPL connection"; `tools/story-mcp/README.md:9–18` — a JVM-side server whose handlers "call Story's public API in the same JVM process", blind to browser-heap state. **Two hosts, and `docs/story/09` does not say so**: its first screen (`:1–45`) is substrates; its §Story-MCP (`:46–64`) calls the server "a separate tool artifact" without saying it is a separate process that cannot see the browser's variants, and the loop at `:72–79` says "inspect … through pair or Xray tooling" without saying pair is the other host. Both siblings found the same split independently.
- **CODE**: capability-unavailable errors for CLJS-only state (`list-substrates`, `read-a11y-violations` — mcp #15/#16, exercised). Astra's configured Pair connector could not reach a freshly started nREPL in its session (`:nrepl-unreachable`) and fell back to direct browser calls — an environment limit, not evidence the route is absent.
- **ERGONOMICS**: ceremony 2 (two servers) plus one concept the docs do not name (which host holds the variant you mean).
- **Reference**: `addon-mcp` `stories-preview` + generic Playwright MCP — one HTTP MCP on the running workshop. `do`.
- **Evidence**: ex / do.
- **Notes**: DIVERGENT by recorded design (one door for the live browser), not a gap; the docs item in `report.md` §6.2 is the whole fix.

#### C4 · Explain a variant's effective plan before running it — w2 · S 1.5 · B 0 · WIN (with defect)
- **SPEC**: `017` §Explain API; `017:603–604` "`[:explain :effective-args]` — the same [args that feed the view], surfaced in `explain`".
- **CODE**: `story/explain` (`story.cljc:1609`, `plan.cljc:2005`); `tools/story/spec/API.md:139` carries its row (the brief's premise that it has none is wrong at this sha).
- **DOCS**: `docs/story/04`.
- **ERGONOMICS**: ceremony 1. Exercised `probe` F and `probe2` F2; astra reproduced it in the browser on the login variant (`sx`). **Defect**: for a variant whose story supplies `:args {:heading "Sign in"}`, `explain` returns `:args {}` and `:effective-args {}` while `story/resolve-args` returns `{:heading "Sign in"}`; the UI Explain panel shows "ARGS not available / EFFECTIVE ARGS not available" while Docs and Xray show the heading. **Root cause** (`evidence.md` §X): the pure compiler folds the ambient story/global layers only when handed `:run-args` (`plan.cljc:1393–1414`); `plan/explain` passes the caller's opts through (`:2005–2012`); the panel calls it with none (`explain_panel.cljc:101`) and by its own docstring "never invents data the compiler didn't emit" (`:30–33`); `plan/effective-args` (`:1996–2003`) and `story/resolve-args` (`story.cljc:1815`) already fold the layers. rf2-gwye.7 (closed 2026-09-13) repaired canvas/save args only. Filed rf2-noxox, dispatched.
- **Reference**: no plan concept. —
- **Evidence**: ex (+sx) / —.

### X — cross-cutting

#### X1 · Compose shared context without decorator opacity — w1 · S 2 · B 2 · MATCH
- **SPEC**: `spec/007-Stories.md` `:extends`/`:compose`; audit [C-1] global decorator landed (`reg-global-decorator`, `story.cljc:731`).
- **CODE**: `st` (not exercised).
- **DOCS**: `docs/story/07-workspaces-modes-composition.md`.
- **Reference**: decorators + parameters inheritance. `do`.
- **Notes**: `018` §3.1 lists "decorator chains that hide global behaviour" as a pattern not to copy; Story now ships `reg-global-decorator`. Not a contradiction if the decorator stack is visible in `explain` — not verified here.

#### X2 · Publish a static workshop build — w1 · S 1 · B 2 · GAP
- **CODE**: `implementation/package.json:55` `"story:build": "node scripts/story-build.cjs"` — `do` (not run).
- **Reference**: `storybook build`, hosted anywhere; Chromatic/Ladle/Cosmos all ship it. `do`.
- **Notes**: scored 1 because nothing teaches a consumer app how to run it outside this repo.

#### X3 · Extend the workshop — w1 · S 1 · B 2 · DIVERGENT
- **SPEC**: `018` §3.1 "addon sprawl as the primary extension model" is a pattern not to copy; `DESIGN-RATIONALE.md` §Rejected.
- **CODE**: `:custom` workspaces, modes, decorators as data. `st`.
- **Reference**: addon API + marketplace. `do`.
- **Notes**: DIVERGENT by design; scored 1 so the trade is visible, not hidden.

#### X4 · Ecosystem breadth, hiring pool, hosted-service economics — OUT
- Not a job the tool controls. Storybook: ~ten years, addon marketplace, Chromatic. Story: pre-alpha. Listed so the reader sees it was weighed and excluded.

## Automatable rows (template: 015-Test-Coverage style)

| Row | Probe that produced this run's cell | Re-run command |
|---|---|---|
| A2, B1, B2, B3, B4, C4 | `probe.clj` A/B/C/F, `probe2.clj` Q/R/S/F2, `probe3.clj` V1–V3 | `cd tools/story && clojure -Sdeps '{:deps {day8/re-frame2-epoch {:local/root "../../implementation/epoch"}}}' -M -i <scratch>/probeN.clj` |
| B6 (promotion keeps the expectation) | `probe4.clj` — source `:fail` → promote → child must `:fail` for the same expectation; a `:pass` with `:assertions []` is a red row | same runner; expected output `evidence.md` §P4 (red at this sha) |
| B4 (upgrade snippet parses and drops the pin) | `probe5.clj` — `edn/read-string` of `upgrade-snippet`'s output must succeed; the compiled child's `:fidelity` must not still carry `:sub-overrides` unless the author kept the pin | same runner; expected output §P5 (red at this sha) |
| A5, C1, C2 | `mcp-requests.jsonl` #1–#18, `mcp-requests2.jsonl` #1–#8 | `cd tools/story-mcp && clojure -M -e "(load-file \"<scratch>/mcp-prelude.clj\")" -m re-frame.story-mcp.server --allow-writes < <scratch>/mcp-requests.jsonl` |
| A3 (negative half), B6 (registration) | walk of `:8043`; `probe2.clj` T | `node <scratch>/walk2.cjs` with `npm run dev -- :testbeds/login-form` serving 8043 |
| A1 | scratch consumer app | `evidence.md` §J1 sequence |

Manual rows need a human to read the UI (A4, A6, A7, B5, B7, C3, X1–X3).

## Revision history

- **10:25 AUSEST, first pass appendix** (`probe4`/`probe5` after reading the siblings): B6 re-scored S 1.5 → 1; totals 65 / 80 = 0.81, ratio 1.15, range 1.12–1.28.
- **11:05 AUSEST, this revision** (this worker's edit list; provenance in `report.md` §10): A7 added (grok's isolation split, astra's per-job boundary rule); B1/B2/B5 reference cells raised to `sx` on astra's Vitest-addon receipts; A1 DOCS corrected (the beside-clone assumption is stated at `index.md:94` — astra); A4's ARIA note retracted at source (`mode_tabs.cljs:174–182`, astra's selector succeeded) and its count defect given grok's root cause; B2, C4 given their root causes at source; B4 carries the upgrade-snippet defect in the body; B6 carries the promotion boundary; B7 records what the identity tuple holds and that the visual assertion is a hash; C3 records the two-host finding both siblings made; the community cap measured at zero cells and removed; the "nine rows at 0" miscount corrected to six. Totals now 65.5 / 82 = 0.80 vs 58 / 82 = 0.71, ratio 1.13, range 1.10–1.27; doorstep 0.71 vs 0.89 (0.80), substance 0.84 vs 0.61 (1.38).

- **2026-09-14 14:25 AUSEST, second sibling review** (trunk `5e27aff193`): totals unchanged; the chrome/application-state cut and the reference-generous bound added under §Totals; A4 narrowed — Storybook can give a story its own store through a provider decorator (astra) and Portfolio/devcards already showed component grids (grok), so the WIN is "by default, of the real machine, no per-app wiring", not impossibility elsewhere. Since the pin the fixes behind the optimistic bound (A1, A3, B5, C4, B6) have all merged; the rows are NOT re-scored here because none was re-executed — see `report.md` §2.2 for the projected reading.
