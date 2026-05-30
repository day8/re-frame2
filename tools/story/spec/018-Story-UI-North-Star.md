# Story UI — North Star

> The product contract for the Story workshop UI: the elegance bar, the
> Storybook parity-and-surpass thesis, the closed mode model, the five
> shell regions, the sidebar/toolbar/canvas surfaces, the keyboard and
> command-palette spine, the visual quality contract, responsive/scale/
> performance posture, the visual-system seam, and the FUTURE extension
> posture. This is the orientation read for the Story UI; the four
> companion docs (`019`–`022`) carry the deeper per-surface contracts.

## Builds on

- [`000-Vision.md`](000-Vision.md) — what Story is and isn't; the
  warm-slate + amber identity stance.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the current shell
  (sidebar, canvas, controls, workspaces) and the right-hand per-panel
  Xray embed. This spec composes that shell; it does not redefine the
  mount lifecycle or the panel inventory.
- [`007-Mode-Tabs.md`](007-Mode-Tabs.md) — the closed
  `:dev` / `:docs` / `:test` mode-tabs primitive.
- [`010-Toolbar.md`](010-Toolbar.md) — the five-cluster toolbar and the
  `reg-mode` chip surface.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) — the chrome-identity
  token contracts (typography, colour, motion, backdrop, iconography)
  and the toolbar 5-cluster structural contract.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the Story/testing
  substrate: variant plans, `:world` / `:script` / `:expect` /
  `:evidence`, runners, `render-variant`, `story/explain`, the
  `:cannot-run` third result state. **Source of truth where present;
  this UI references it, it does not restate its semantics.**

## Supersedes

- The destination-UI framing of the [`000-Vision.md`](000-Vision.md)
  §"What re-frame2-story is for" bullets is carried forward unchanged;
  this spec adds the product contract those bullets imply but does not
  yet lock.
- No existing capability doc's *behaviour* is superseded by this file.
  The behavioural supersessions live in `019`–`022` (controls, test
  mode, docs mode). This north-star file is additive: it names the
  product bar the existing shell pieces converge on.

## Depends on

- `017-Testing-Story.md` substrate for `render-variant` (canvas), the
  unified run result (status surfaces), and the epoch-tape evidence
  projection (evidence chips). These are CURRENT where 017 has landed
  and BLOCKED where it has not; see the status table in §6.

## Out of scope

- Detailed controls taxonomy and view-state fidelity — owned by
  [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md).
- The Xray embed interior, evidence-spine display, and Explain panel —
  owned by
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md).
- Test-mode result presentation and failure promotion — owned by
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md).
- Docs mode, sharing, static export, and egress redaction — owned by
  [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md).
- The Story/testing runtime substrate itself — owned by
  [`017-Testing-Story.md`](017-Testing-Story.md). This is a UI spec; it
  composes the substrate, it does not define it.

## Normative language

- **MUST** — required for the target Story UI.
- **SHOULD** — expected unless a concrete implementation constraint says
  otherwise.
- **MAY** — optional or later extension.

Status labels (local to the Story UI specs `018`–`022`):

| Label | Meaning |
|---|---|
| CURRENT | Implemented or specified today; useful grounding, not a compatibility promise. |
| TARGET | Required by this UI spec, but not necessarily implemented yet. |
| BLOCKED | Requires a substrate bead (`017-Testing-Story` work or a named seam) before the UI can truthfully implement it. |
| SUPERSEDES | Deliberately replaces a current spec or behaviour before alpha. |
| FUTURE | Plausible later work that is not required for the first Story UI EPIC. |
| OUT | Explicitly out of scope. |

Combined labels such as `CURRENT/TARGET` mean a partial surface exists
today, but more work is required before the alpha target is satisfied.
`CURRENT/BLOCKED` means a transitional surface exists, but the desired
converged path is blocked on substrate work. For planning, the
strongest non-current label controls the slice.

Pre-alpha posture: current implementation and existing specs are
grounding evidence, not compatibility constraints. Story MAY break,
rename, or supersede a current surface when that produces a clearer
tool. Any such break MUST be explicit, recorded as a supersession of
the affected spec, and delivered through one converged path — never by
layering a second UI over the first.

## 1. Product contract

Story is a workshop for application behaviour. A user should be able to
choose a variant, see it render, edit its explicit inputs, run it as a
test, read its documentation, inspect its evidence, and promote useful
failures without switching mental models.

The user-facing promise is not "learn our substrate." It is:

- open a familiar workshop quickly;
- make view states cheaply;
- know how trustworthy each state is;
- turn useful examples into tests and docs;
- follow a failure to its cause;
- hand the same state to another human or agent without re-encoding it.

The implementation answer is one selected artifact lowered through one
plan/result/evidence path. That answer is valuable only if it makes the
promise simpler — not if it becomes terminology the user has to carry.

The UI contract:

- one selected Story/variant/workspace at a time;
- one normalized plan behind canvas, controls, docs, tests, share, and
  agent calls;
- the human UI, Story MCP, and the Story-related skills are different
  entry points over the same variant/plan/result/evidence model;
- Xray embedded for detailed runtime diagnostics (the boundary is stated
  once in
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §1);
- Story-owned controls, variant navigation, docs, tests, fidelity,
  narrative, explain, and promotion;
- no fourth Evidence mode tab;
- no second app-db/views/trace inspector competing with Xray;
- invalid args, schema failures, and cannot-run states are visible;
- view-state variants are first-class, with visible fidelity.

The UI contract MUST be read with the tension rules in §4. In
particular, "no fourth Evidence mode" is a design answer, not a dogma:
debugging workflows MUST still reach evidence fast enough that an
evidence-primary mode would feel unnecessary.

## 2. Stories served

Every major surface across `018`–`022` carries `Story pressure` tags so
the design can be checked outside-in.

| Id | Story |
|---|---|
| S1 | Experienced Storybook user evaluates Story |
| S2 | App developer creates UI states quickly |
| S3 | App developer upgrades a state to real integration |
| S4 | Developer debugs a failed variant |
| S5 | Tester turns a story into a test |
| S6 | Developer promotes a failure into a regression variant |
| S7 | Design-system maintainer reviews a state matrix |
| S8 | Developer authors executable documentation |
| S9 | AI agent uses Story through MCP and skills |
| S10 | Xray-heavy user trusts the diagnostic boundary |
| S11 | Programmer shares a safe reproduction |

Rule: a UI surface with no story pressure is `FUTURE`, `OUT`, or
infrastructure that must justify itself. It is not P1 product surface.

## 3. Elegance bar

The goal is to match Storybook where it is excellent and surpass it
where re-frame2 has a better substrate. The path is not feature parity
by accumulation; it is a small set of primitives that compose into more
power than Storybook can offer on a JS/TS component substrate.

Story should feel simple because the user is always manipulating the
same few things:

- a selected variant;
- explicit inputs;
- a script;
- expectations;
- evidence produced by the run.

Every UI feature MUST earn its place against this bar:

- **One Concept.** Do not create a second name for the same thing.
- **One Path.** Canvas, docs, tests, share, agent calls, and debugging
  use the same normalized plan/result path.
- **Progressive Disclosure.** The default view is calm; deeper substrate
  power appears when the user asks for it.
- **Honest Fidelity.** Lower-fidelity views are useful, but never
  presented as stronger proof than they are.
- **Composable Power.** Variants, controls, checks, scripts, evidence,
  and Xray open/focus affordances should compose, not spawn parallel
  workflows.
- **Beautiful Failure.** A failing run should produce a readable path
  from intent to cause, not a log dump.
- **Pre-alpha Courage.** Remove or supersede current surfaces when they
  obscure the model.

### 3.1 Storybook parity and surpass

Parity is a quality bar, not a checklist of nouns. Story should match
Storybook where experienced Storybook users have legitimate
expectations:

- navigation across stories and variants fast enough for daily use in
  large projects, including search and keyboard-first movement;
- controls/args that update the view quickly and make view states easy
  to explore without ceremony;
- docs that explain component and application states without leaving the
  workshop, and without reading like a debug log;
- visual/a11y/test results visible beside the rendered example;
- composition patterns that let common context be reused without
  decorator-style opacity;
- useful sharing/export of a selected state, with privacy honestly
  represented;
- enough polish that the tool feels like a daily workspace, not a debug
  panel.

Minimum parity bars:

| Area | Bar |
|---|---|
| Navigation | Fuzzy search and keyboard movement stay fast across large projects. |
| Selection | Returning to a story restores useful scroll, selection, and local panel state. |
| Controls | Edit-to-render feels immediate for ordinary args and gives inline validation for invalid input. |
| Docs | Docs are readable without becoming generated trace dumps. |
| Status | Test/visual/a11y status is visible where a Storybook user expects it, not hidden in a diagnostics panel. |
| Matrices | Variant grids remain scannable at design-system scale. |
| Sharing | Share/export is useful and honest about privacy, even when full safe sharing is blocked. |

These qualitative bars are now backed by concrete, enforceable budgets
(ratified rf2-ba86n.2): search/rebuild output and latency at large story
counts, edit-to-render and inline-validate latency for ordinary controls,
matrix size before paging, and the gesture/latency budget from a failed
assertion to useful evidence. The normative budget table is §10.1; the
deterministic enforcement gate and the documented-target latencies are
described there. (See §10 for the perf posture and §14 acceptance.)

Story should deliberately surpass Storybook where re-frame2 gives
leverage:

- variants are executable application plans, not only rendered component
  examples;
- tests, docs, canvas, agent calls, and replay all share one normalized
  plan;
- Story MCP and the Story-related skills can drive the same plan/result
  path the UI uses;
- every run can produce an epoch-backed evidence tape;
- failures can explain themselves through script spans, epoch beats,
  app-db changes, effects, traces, schema failures, subscriptions, and
  renders;
- view states can combine explicit args with honest state-fidelity
  rungs: sub-overrides, schema-checked db seeds, and real setup events;
- generated failures can promote to curated variants instead of living
  as separate repro files;
- Xray can expose runtime causality that ordinary component-story tools
  cannot reconstruct.

Story should not copy Storybook's weaker patterns:

- addon sprawl as the primary extension model;
- decorator chains that hide global behaviour;
- visual examples that look trustworthy but are disconnected from
  application state and effects;
- separate encodings for stories, tests, fixtures, repros, and debug
  traces.

## 4. Tension resolutions

The user stories create real tensions. This spec resolves them as
follows; the resolutions are normative across `018`–`022`.

### T1. Evidence-primary debugging versus a calm first screen

The first screen remains render-first and workshop-like. Evidence is
**not** a fourth top-level mode. But failed assertions, selected result
rows, docs excerpts, inspector commands, and Xray links MUST make
evidence feel primary at the moment of need. The no-fourth-mode decision
fails if a developer debugging a failed variant has to hunt for the
causal evidence. (Evidence spine: see
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§3.)

### T2. Fast state exploration versus honest fidelity

Quick state exploration stays fast. Fidelity labels are compact during
exploration, then become more explicit when the user saves, shares,
tests, or claims proof from a lower-fidelity state. The UI MUST NOT
punish the cheap path; it should let users start with canned values and
upgrade toward real app behaviour in place. (Fidelity ladder: see
[`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
§5.)

### T3. Human UI versus agent mirror

Human-visible Story operations are the product source of truth. Story
MCP and the Story-related skills MAY expose gated or structured versions,
but they MUST NOT introduce a second artifact model. If an agent-only
operation becomes important, it creates pressure to add a human-visible
equivalent or mark the operation internal.

### T4. Safe sharing versus useful reproduction

Share/export commands must be useful, but not at the cost of leaking
application data. Redaction must be visible and explain what was removed.
A shared artifact MAY be partially reproducible; it MUST say so when
redaction removes required data. (Egress seam: see
[`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) §3.)

### T5. Storybook addons versus product simplicity

Do not build a broad addon ecosystem before the core workshop is
excellent. Early extension is typed and narrow: result renderers,
controls, evidence projections, commands, or Xray links only where a
concrete story proves demand. (See §11.)

## 5. Ownership boundary

Story pressure: S4, S9, S10.

The detailed Story/Xray boundary — what Xray owns, the mount contract,
the focus API — is stated **once** in
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§1. This section gives the top-level split so the north-star is readable
without jumping files.

**Story owns:** the Story/variant/workspace tree, matrices, and saved
failures; canvas composition; controls; fidelity badges; docs mode; test
mode; the evidence spine; the Explain panel; visual/a11y assertion
presentation; promotion from run artifact to named variant.

**Xray owns:** app-db diffing; views/subscription invalidation panels;
trace and epoch detail; machine/routing/issues inspectors; time-travel
and full diagnostic shell behaviour; Xray's diagnostic source chips and
redaction markers.

**Shared substrate owns:** epoch recording and redaction; runner
execution and run result; canonicalization and hashes; privacy/elision
for egress artifacts; production/debug elision.

Story MUST embed/share Xray for detailed diagnostics. Story MUST NOT
build a separate detailed inspector with State/Effects/Subs/Renders/
Schemas/Trace tabs that duplicates Xray's panels.

### 5.1 Human UI, MCP, and skill mirroring

Story pressure: S9.

Human-visible Story operations are the product source of truth. The
exact MCP tool names are owned by
[`006-MCP-Surface.md`](006-MCP-Surface.md) and
[`../../story-mcp/spec/002-Tool-Registry.md`](../../story-mcp/spec/002-Tool-Registry.md);
the operation crosswalk lives in
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§1.3. Asymmetries are allowed only when explicit: write operations are
gated, attached-frame operations require frame binding, redaction MAY
remove data an agent asks for, and internal diagnostic reads MAY remain
non-product surfaces.

## 6. Current versus target

Story pressure: S1–S11.

Because Story is pre-alpha, `CURRENT` is descriptive, not defensive. The
product question is not "what must we keep?" but "what has been learned,
and what should the alpha surface converge on?" This spec keeps the
three-mode shell and Xray embed because they are the better
architecture, not because they are untouchable.

| Surface | Status | Rule | Owning spec |
|---|---|---|---|
| Three mode tabs `:dev`, `:docs`, `:test` | CURRENT | Keep as the alpha target. Evidence is a shared spine, not a fourth tab, provided debugger workflows can reach it quickly. | this spec §7 |
| Right-hand Xray per-panel embed | CURRENT | Reuse Xray's panel ids, mount contract, and pop-out. | `020` §2 |
| Current docs pane | CURRENT | Evolve with evidence excerpts and fidelity; supersede only through one converged docs path. | `022` §1 |
| Current test pane/widget/stepper | CURRENT | Evolve to unified result when the substrate run-result lands. | `021` §1 |
| Toolbar clusters and `reg-mode` chips | CURRENT | Keep the toolbar axis separate from mode tabs unless deliberately superseded. | this spec §7 |
| `render-variant` as shared render API | BLOCKED | Required by `017-Testing-Story`; canvas can be designed around it now. | this spec §8 |
| Unified top-level run-result | BLOCKED | UI MAY adapt the current result shape until migration. | `021` §1 |
| Two-level narrative | BLOCKED | Requires script-span + epoch-beat projection. | `020` §3 |
| `story/explain` data API | CURRENT | Base compiler/explain data exists where `017-Testing-Story` has landed; panel UI is separate. | `020` §4 |
| Explain panel UI | TARGET | Net-new Story surface over explain data; does not depend on Xray. | `020` §4 |
| Controls widget taxonomy | TARGET | Built incrementally over the current args/schema surface. | `019` §2 |
| Save current state as variant | CURRENT/TARGET | Existing Story specs own the save affordance; this UI must place it coherently and keep it distinct from failure promotion. | `019` §3 |
| Story-to-Xray focus API | CURRENT/TARGET | `rf2-crtmq` is closed (opening/focusing Xray is current); StoryUI still has to wire focused links from results/evidence/docs. | `020` §2.1 |
| Share/export redaction across all egress | BLOCKED | Requires a common egress seam (`rf2-qarwq`) beyond epoch redaction. | `022` §3 |
| Unified Test-mode result shape | SUPERSEDES | Supersedes [`009-Test-Mode.md`](009-Test-Mode.md) result-reading once the substrate run-result lands. | `021` §1 |
| Third-party extension surface | FUTURE | Not justified by a primary P1 user story; keep only typed internal seams. | this spec §11 |
| Hosted visual review service | OUT | Browser-tier assertions are in scope; hosted review is not. | — |

## 7. Shell information architecture

Story pressure: S1, S4, S7, S10, S11.

The shell has five conceptual regions:

| Region | Status | Purpose | Owning spec |
|---|---|---|---|
| Sidebar | CURRENT/TARGET | Navigate stories, variants, workspaces, matrices, saved failures, and search. | this spec §7.1 |
| Toolbar | CURRENT/TARGET | Chrome-wide context: modes, data, view, debug, recording/share commands. | this spec §7.2 |
| Canvas | CURRENT/BLOCKED | Render the active variant, eventually through `render-variant`. | this spec §8 |
| Controls | CURRENT/TARGET | Edit explicit world inputs and runner/view-state affordances. | `019` |
| Inspector | CURRENT/TARGET | Host the Xray embed, evidence spine, explain, and selected result/detail panels. | `020` |

On desktop, canvas remains central; sidebar and inspector support it. On
narrow screens, the sidebar collapses first, then inspector/controls
become bottom-sheet or tabbed panels (see §10).

### 7.1 Sidebar

Story pressure: S1, S2, S6, S7.

The sidebar MUST support:

- story rows, variant rows, and workspace rows;
- status badges: pass, fail, cannot-run, error, pending, blocked, dirty,
  redacted;
- fidelity badges: real-setup, db-seed, sub-overrides;
- world-input chips: args, route, network, fx-overrides;
- runner-requirement chips: headless, hiccup, DOM, browser;
- optional runner-requirement chip: cljs-reactive, deferred until there
  is a real reactive probe seam;
- frame-binding chips where relevant: fresh frame, attached frame,
  MCP-bound;
- filters by id, tag, source namespace, status, runner requirement,
  frame binding, world-input chip, and fidelity;
- saved/generated failures once run artifacts are loaded or promoted;
- visible grouping for `:variants-grid` generated variants.

Args are an input/control surface, not a fidelity rung. Network and
fx-overrides are world inputs. Browser-required is a runner requirement.
Attached-frame and MCP-bound are frame-binding signals, not runner tiers.
The UI MAY display these compactly together, but the labels MUST NOT
collapse them into one "fidelity" concept.

The sidebar SHOULD support recent and pinned variants, local
collapsed-folder state, and "changed since last run" only after
per-variant dirty tracking exists.

The sidebar MUST NOT become a docs pane. It is navigation and signal.

Empty states the sidebar MUST handle: no stories registered; no variants
under a story; filter returns no rows; saved-failures source
unavailable; variant has no runnable tests yet.

### 7.2 Toolbar and mode tabs

Story pressure: S1, S4, S5, S9, S10.

Story has two distinct axes:

| Axis | Owner | Meaning |
|---|---|---|
| Mode tabs | [`007-Mode-Tabs.md`](007-Mode-Tabs.md) | Per-variant main pane: `:dev`, `:docs`, `:test`. |
| Toolbar modes | [`010-Toolbar.md`](010-Toolbar.md) | Chrome-wide arg/mode tuples via `reg-mode`. |

The toolbar MUST preserve the five-cluster vocabulary from
[`010-Toolbar.md`](010-Toolbar.md) and
[`016-Design-Tokens.md`](016-Design-Tokens.md): MODES, DATA, VIEW, DEBUG,
REC.

The mode-tab list MUST remain `:dev`, `:docs`, `:test`. Evidence appears
as a shared spine inside Inspector/Test/Docs, not as a fourth mode tab.

The DEBUG cluster SHOULD expose: an active Xray panel selector; pop-out
full Xray shell; Xray panel/beat/path focus commands through the
Story-to-Xray focus API; the explain-panel toggle; an evidence-spine
toggle/focus; and cannot-run/result filter shortcuts where appropriate.

Frame binding is not a runner tier. UI labels MUST make this clear: the
substrate has two frame-binding values, `:fresh` and `:attached`.
"Selected live frame" is a UI affordance for choosing an attached frame,
not a third binding value.

## 8. Canvas

Story pressure: S1, S2, S3, S7, S8, S11.

Canvas MUST:

- render the active variant;
- show validation failure before rendering invalid effective args;
- reflect viewport, background, substrate, toolbar modes, and current
  controls state;
- preserve user overrides across mode-tab switches;
- render cannot-render states explicitly;
- expose source coordinates where available.

Canvas SHOULD converge on `(story/render-variant target opts)` once the
substrate ([`017-Testing-Story.md`](017-Testing-Story.md)) lands. Until
then it MAY adapt the current render path but MUST NOT pretend
`render-variant` is implemented.

Canvas SHOULD provide "inspect rendered view" commands. Opening the full
Xray shell is CURRENT; focusing a specific Xray panel, epoch, or path is
TARGET StoryUI wiring over the existing focus API (see
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§2.1).

Canvas MUST NOT implement its own app-db diff surface. Structural app-db
diffing belongs to Xray.

## 9. Keyboard and command palette

Story pressure: S1, S4, S5, S9, S10, S11.

Required commands (per-command status; rows link to the owning spec):

| Command | Status | Owning spec |
|---|---|---|
| search story/variant | CURRENT/TARGET | this spec §7.1 |
| switch mode tab | CURRENT | [`007-Mode-Tabs.md`](007-Mode-Tabs.md) |
| run/re-run active variant | CURRENT/TARGET | `021` §1 |
| run with richer runner | TARGET/BLOCKED | `021` §2 |
| save current state as variant where representable | CURRENT/TARGET | `019` §3 |
| promote current run artifact/failure to variant where available | BLOCKED | `021` §3 |
| focus sidebar/canvas/controls/inspector | TARGET | this spec §7 |
| open full Xray shell | CURRENT | `020` §2 |
| focus Xray panel/beat/path | TARGET | `020` §2.1 |
| open source for variant/view/event/assertion | TARGET | this spec §8 |
| copy share URL | BLOCKED on the egress seam for safe sharing | `022` §3 |
| copy inline plan | TARGET | `022` §3 |
| copy run artifact | BLOCKED | `021` §3 |
| toggle failed-only result rows | TARGET | `021` §1 |
| jump previous/next narrative beat | BLOCKED | `020` §3 |
| open explain panel | TARGET, over CURRENT explain data | `020` §4 |

The command palette MUST operate over structured registry/run data, not
screen-text scraping.

## 10. Responsive, scale, and performance

Story pressure: S1, S7, S11.

Desktop is primary. Narrow layouts MUST still be readable for shared
links and static builds.

Responsive rules:

- canvas gets priority;
- sidebar collapses before canvas;
- inspector becomes a bottom sheet or tabbed panel;
- controls become a tabbed panel;
- badges remain legible;
- text MUST NOT overflow controls, rows, badges, or buttons.

Performance risks: large app-db snapshots; many epoch beats; many
`:variants-grid` cells; expensive Xray diffs; deep schema-derived
controls.

Mitigations: virtualize long narrative/assertion/trace/diff lists; lazily
mount Xray panels and unmount inactive panels cleanly; cap or page
variants-grid cells; compute expensive diffs on demand; show summaries
before expanding large values; lazy-load nested schema editors and render
deep controls as summaries until expanded; preserve scroll/focus across
re-renders. The UI SHOULD fail by summarizing and offering expansion, not
by freezing or flooding the screen. (Deep-controls risk is repeated where
it bites in
[`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
§4.)

The cap-and-page mitigations are WIRED into the render paths (rf2-ba86n.18):
the sidebar bounds variant + captured-artifact rows (N1/N2); the
variants-grid renderer bounds visible cells at the G1 cap, surfaces the
G2/G3 matrix-size advisory, and never renders past the G3 hard cap (it
pages instead, so a generated matrix can never freeze the canvas); the
controls panel bounds flat-panel rows at the C2 cap and renders nested
controls as summaries until expanded (C1/C4). Each bound is the SAME
`re-frame.story.budgets/bound-cells` cap-and-page primitive (F1), and each
expander is additive — revealing more never reorders the rows/cells already
on screen, so scroll/focus survives the re-render (stable React keys:
variant-id-keyed cells, arg-key-keyed rows, monotonic repeater row ids).
Lazy Xray-diff mounting is now CURRENT (rf2-ba86n.19): the RHS Xray embed
defers the panel MOUNT — and therefore the panel's expensive diff compute
(app-db structural diff, epoch timeline) — until the embed is expanded. The
embed already mounted ONE panel at a time with deferred microtask unmount
(rf2-4l7t2); the upgrade gates that mount on a `:xray-embed-collapsed?`
shell slot, so a collapsed embed renders only the (cheap, pure-data)
chip-row picker plus a quiet placeholder and never instantiates the
panel-host component that drives `mount-<panel>!`. Collapsing drops the
panel-host from the tree, which releases the Xray React root via the same
existing microtask path (rf2-4l7t2) — no teardown is duplicated. The embed
e2e CLJS gate asserts no panel-host slot (hence no diff compute) renders
while collapsed.

### 10.1 Parity budgets

Story pressure: S1, S7, S11.

These budgets turn the §3.1 parity bars into measurable numbers
(ratified rf2-ba86n.2). They are the normative source the implementation
and the enforcement gate share; the code-side single source of truth is
`re-frame.story.budgets`, and the deterministic gate
(`re-frame.story.budgets-cljs-test`, run under `clojure -M:test` and
`npm run test:cljs`) asserts the structural budgets at the floor scale.

The scale approach is **cap-and-page** (F1): a bounded prefix plus a
`+N more` / page affordance everywhere, the same idiom the sidebar already
ships. True virtualization (windowed render) is **FUTURE** — it is not
required for the first Story UI EPIC, and unlike cap-and-page it cannot be
a pure-data gate (it needs DOM measurement). If a concrete project hits a
cap as real pain, virtualization is the named upgrade.

| # | Surface | Budget | Status | Enforcement |
|---|---|---|---|---|
| N1 | Sidebar — per-story variant rows before `+N more` | **40** | CURRENT | gate: bounded output (`bound-variants` / `sidebar-variant-cap`) |
| N2 | Sidebar — captured-artifact rows before `+N more` | **20** | CURRENT | gate: bounded output (`captured-artifact-cap`) |
| N3 | Realistic project floor (must stay scannable) | **2 000 variants / 200 stories / 50 workspaces** | TARGET | gate: floor fixture, derivation stays bounded + single-pass |
| N4 | Sidebar — filtered-tree rebuild per search keystroke | **≤ 8 ms** (documented target); gate asserts single bounded pass, no O(n²) | TARGET | gate: structural (single bounded pass); latency is a documented target |
| C1/C4 | Controls — nested controls render depth | **lazy past depth 1** (summarise before expand) | CURRENT | spec/019 §4; `summarize-value` / `path-expanded?` |
| C2 | Controls — flat-panel control rows before `+N more` | **60** | CURRENT | gate: `controls-flat-row-cap`; render-wired in `ui/controls` `args-editor` (rf2-ba86n.18) |
| C3 | Controls — inline-validation of one edited field | **≤ 4 ms** (documented target) | TARGET | documented target; structural validate is single-field, bounded |
| G1 | Variants-grid — visible cells before page / `+N more` | **100** | CURRENT | gate: bounded output (`bound-cells` / `grid-visible-cell-cap`); render-wired in `ui/workspace` capped-grid renderer (rf2-ba86n.18) |
| G2 | Variants-grid — matrix dimension product (soft warn) | **warn at ≥ 12×12 = 144** | CURRENT | gate: `matrix-warn?`; render-wired advisory (rf2-ba86n.18) |
| G3 | Variants-grid — matrix dimension product (hard cap) | **render ≤ 400; paginate beyond** | CURRENT | gate: `matrix-over-hard-cap?` / `matrix-page-count`; render-wired — grid never renders past the hard cap (rf2-ba86n.18) |
| X1 | Failure → first useful evidence | **≤ 1 gesture; inline excerpt ≤ 2 beats** | CURRENT/TARGET | gate: excerpt-beat cap; one-gesture reach is a review-checklist bar |
| X2 | Evidence-spine first paint (typical run, ≤ ~200 beats) | **≤ 100 ms** (documented target) | TARGET | documented target (React-bound; review-checklist / manual) |
| X3 | Bundle size | **NO NEW BUDGET** — reference `npm run test:perf-bundle` + bundle-isolation | CURRENT | existing gate (reference, not duplicated) |

Enforcement classification (ratified F2 — the gate is DETERMINISTIC, not a
flaky wall-clock micro-bench):

- **Structurally enforced** (the gate asserts these): N1, N2, N3 (floor
  fixture), N4 (single bounded pass / no O(n²)), C2, G1, G2, G3, X1
  (excerpt-beat cap). The gate asserts **bounded output** and a
  **single-pass derivation**, never wall-clock milliseconds.
- **Documented latency targets** (data in `budgets/latency-targets-ms`;
  NOT asserted as wall-clock — flaky in CI): N4 (≤ 8 ms), C3 (≤ 4 ms),
  X2 (≤ 100 ms). These are review-checklist bars and the contract for any
  future opt-in micro-bench; the gate enforces the structural shape that
  makes them achievable.
- **No new bundle budget** (X3): StoryUI is tool-tier and bundle-isolated;
  bundle size stays with the existing `test:perf-bundle` and
  bundle-isolation gates rather than duplicating the concern here.

## 11. Visual system seam

Story pressure: S1, S7, S10, S11.

Story and Xray keep distinct identities but must meet cleanly. The
detailed visual quality bar for the seam is §12.9 below; the Xray-side of
the seam is owned by
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§5.

Rules:

- Story owns the surrounding card, chip row, panel title, and pop-out
  affordance.
- Xray owns the panel interior and diagnostic colour semantics.
- Shared statuses (pass/fail/cannot-run/error) use one tool-wide colour
  vocabulary (§12.6).
- Redaction markers and source chips should come from shared tool tokens,
  not per-panel styling.
- The embed seam should not read as two unrelated products jammed
  together.

## 12. Visual quality contract

Story pressure: S1, S7, S10, S11.

This section is the first-class visual quality bar for the Story
workshop — not a vague "polish" note. It is not a pixel-perfect mockup
and not a component-implementation spec; its job is to make "Story should
look good" concrete enough that future implementation beads can be judged
against a shared bar. It builds on the token contracts in
[`016-Design-Tokens.md`](016-Design-Tokens.md) and the identity stance in
[`000-Vision.md`](000-Vision.md) §"Identity stance".

### 12.1 Visual thesis

Story should feel like a focused workbench:

- the rendered application state is the visual protagonist;
- navigation, controls, status, and evidence support the work without
  competing with it;
- powerful substrate details appear when needed, not all at once;
- failure investigation is beautiful because it is legible, not because
  it is decorated;
- Story and Xray feel like one tool with two responsibilities.

The target feeling: familiar enough that a Storybook user is oriented
quickly; quieter and denser than a landing page; more causal and
trustworthy than Storybook when something fails; polished enough that
programmers leave it open all day.

### 12.2 Product aesthetic

Use the visual language of an operational developer tool: restrained
colour; high information density with stable spacing; strong alignment
and grouping; typography sized for scanning, not hero presentation; clear
focus states and keyboard paths; compact chips, badges, and icon buttons
for repeated status; larger text only for actual page or panel headings.

Avoid: oversized hero-like panels; decorative gradient blobs or
atmospheric backgrounds beyond the locked backdrop token; cards inside
cards; large rounded decorative surfaces; one-note colour themes; stock
illustration language; trace dumps presented as product UI.

Cards are appropriate for repeated items, modals, and framed tools. Page
regions should be structural bands or panes, not nested decorative cards.

### 12.3 Region character

- **Sidebar.** Fast orientation and movement. Shows story hierarchy,
  variants, selected state, compact status, and the compact fidelity /
  world-input / runner-requirement / frame-binding chip groups, plus
  generated/matrix grouping. It MUST NOT show long docs text, detailed
  evidence, full app-db/trace data, or verbose explanations. Search MUST
  feel first-class.
- **Canvas.** Seeing the selected state. Shows the rendered variant,
  cannot-render state, arg/schema validation state, runner-limitation
  state, viewport/background/mode context, and short links into Controls,
  Test, or Inspector when the render is affected by them. It MUST NOT
  become a metrics dashboard.
- **Controls.** Changing the selected state. Shows args and effective
  args, view-state controls such as sub-overrides, setup/world summaries,
  reset and diff-from-saved affordances, and save-current-state-as-
  variant. Controls are a peer workshop region even when the responsive
  layout stacks them near the Inspector; they are not an Xray panel and
  not diagnostic evidence.
- **Inspector.** Understanding and diagnosing the selected state. Few
  top-level sections: Explain, Evidence, Xray, and possibly Share when
  egress work lands. The visual boundary MUST make Story-owned vs
  Xray-owned interiors clear without feeling stitched together.
- **Toolbar.** Compact, mostly icon-led where icons are standard; text
  buttons for clear destructive or authoring commands (Save Variant,
  Promote, Run, Share). Do not put every diagnostic command in the
  toolbar — frequent global commands there, context-sensitive commands
  in the command palette, result rows, or inspector.

### 12.4 Visual hierarchy

1. Selected rendered state.
2. Current mode and status.
3. Controls needed to alter the selected state.
4. Evidence needed to understand a result.
5. Deep Xray diagnostics.
6. Secondary metadata and provenance.

Failure-state hierarchy:

1. What failed.
2. Which user-authored step or assertion it belongs to.
3. Whether the runner could actually observe the required evidence.
4. The causal span and epoch beats.
5. The relevant Xray panel.
6. Raw details.

This order matters. A failed run MUST NOT first confront the user with an
app-db diff, trace tree, or raw EDN blob.

### 12.5 Empty, loading, and first-run states

Empty states should be useful, not explanatory posters. Required states:
no story selected; selected story has no variants; selected variant
cannot render; controls have no schema/arg metadata; Xray frame not
available; run result not available; run result exists but evidence
projection is blocked; share/copy disabled because egress redaction is
unavailable.

Each state should answer: what is missing; what command is available
now; and whether this is a project-setup issue, a runner limitation, or a
blocked substrate feature.

### 12.6 Status and colour

Use a shared tool-wide status vocabulary:

| Status | Visual meaning |
|---|---|
| pending | not yet run, currently running, or awaiting evidence |
| pass | settled success; low emphasis unless filtering |
| fail | expectation failed; primary attention |
| error | tool/runtime/schema problem; distinct from a failed expectation |
| cannot-run | required evidence or runner missing; neutral warning, not failure |
| blocked | known missing substrate or disabled unsafe operation |
| dirty | current controls/render differ from the saved variant |
| redacted | data was intentionally removed or hidden |

Do not encode everything as red/green. `pending`, `fail`, `error`,
`cannot-run`, `blocked`, `dirty`, and `redacted` MUST remain
distinguishable in colour, icon, text, and shape.

Fidelity is not a status. World inputs are not fidelity. Runner
requirements are not fidelity. They appear as adjacent compact chip
groups with different labels and tooltips: fidelity (real setup, db seed,
sub overrides); world inputs (args, route, network, fx overrides); runner
requirements (headless, hiccup, DOM, browser); deferred runner
requirement (cljs-reactive, only once a real probe seam exists); frame
binding (fresh frame, attached frame, MCP-bound).

### 12.7 Controls visual model

Controls should feel close to Storybook Controls in immediacy, but more
honest about application state. They MUST support a widget taxonomy for
common scalar/enum/boolean/colour/collection/structured inputs; nested
editing that starts summarized and expands on demand; inline schema
errors; reset to default; diff from saved variant; save current state as
a named variant; and a clear distinction between args and view-state
inputs. View-state controls MUST NOT shame the user for low-fidelity
exploration — the fidelity badge stays calm during exploration and
becomes explicit when saving, testing, sharing, or claiming proof. (Full
taxonomy: `019` §2.)

### 12.8 Evidence visual model

Evidence is a narrative, not a log dump. The primary evidence visual is a
two-level spine — author-level spans (script steps, assertions, waits,
interactions) over epoch-level beats (committed effects, db changes,
trace events, renders, sub-runs, schema failures). Rows are short by
default; expand for detail; link to Xray for deep diagnostic views; do
not duplicate Xray panel interiors. Evidence presentation MUST
distinguish directly-captured facts, attributed facts (heuristic
render/sub-run attribution), redacted facts, unavailable facts, and facts
omitted for performance until expanded. (Full contract: `020` §3.)

### 12.9 Xray seam (visual)

Story and Xray should look related but not identical. Story owns outer
inspector chrome, panel title, chip row, selection, and context commands;
Xray owns diagnostic panel interiors. Shared status colours, typography
scale, redaction markers, and source chips come from common tokens where
possible. The seam should be quiet: one border, one title row, one
pop-out affordance. Do not restyle Xray panel interiors to look like
Story — align tokens and spacing instead. The visual test is simple: a
user should understand "Story brought me here; Xray is showing the
detail."

### 12.10 Key screens to design

Before the implementation EPIC, the spec SHOULD carry rough layouts or
low-fidelity mockups for: browse and preview a normal variant; edit args
and save current state as a variant; render loading/empty/error/success
states through sub-overrides; upgrade a low-fidelity state to db seed or
real setup; review a variants grid with many cells; run a variant with
all passing checks; investigate a failed assertion through evidence and
Xray; show cannot-run for a runner/evidence mismatch; promote a generated
failure into a curated variant; share or copy a reproduction with
redaction warnings. Each screen needs a happy path and a floor state (the
earliest acceptable version when later substrate is still blocked).

### 12.11 Motion

Use motion sparingly — panel open/close, row expansion, running/progress
state, focus handoff to Xray, dirty-to-saved transition — and honour the
`--motion-scale` reduced-motion seam from
[`016-Design-Tokens.md`](016-Design-Tokens.md) §Motion. Avoid constant
animated diagnostics; the tool should feel alive through responsiveness
and good state changes, not ornament.

### 12.12 What not to build visually

Do not build: a landing-page hero for Story; a decorative dashboard; a
second Xray; a trace viewer as the default first screen; a giant addon
marketplace chrome; a visual-review SaaS product; a UI that hides
low-fidelity state behind attractive screenshots; a theme that fights the
existing Story/Xray token contract.

## 13. Extension surface (FUTURE)

Story pressure: none primary. This is FUTURE unless a story is added, or
an internal first-party seam is required to deliver another section.

Third-party panels are FUTURE, not required for P1 UI. The user-story
sweep currently finds no primary story that requires a broad extension
surface before the first-party workshop is excellent. This section
defines constraints for a later surface; it does not justify P1 work.

Panel authors SHOULD receive: the current plan; the current run result;
the current narrative selection; the selected variant id; the selected
frame binding; theme tokens; and a command-registration hook. Panel
authors MUST NOT mutate the plan outside registered Story commands. All
writes go through Story APIs so explain, hashes, and evidence stay
coherent. This surface is dev-tool territory and needs a production/DCE
contract before being considered stable outside debug builds.

P1 MAY still include typed internal extension seams when they directly
serve first-party Story UI: result renderers, control widgets, evidence
projections, command registration, or Xray links. Those seams MUST NOT be
marketed or stabilized as a Storybook-style addon ecosystem.

## 14. Acceptance criteria

The Story UI north star is satisfied when:

- an experienced Storybook user recognizes the core workshop affordances
  immediately;
- Storybook-level parity is met as workflow quality (fast search,
  keyboard movement, live controls, readable docs, useful status at
  realistic project scale);
- the same user can do things Storybook cannot normally do (epoch-backed
  causality, promote executable failures, run one artifact as story /
  test / replay / doc / agent target);
- the three mode tabs remain canonical: Dev, Docs, Test;
- the everyday workflow feels simpler than Storybook while exposing
  deeper proof and debugging when needed;
- evidence is a shared spine, not a fourth mode, and debugger workflows
  can reach it without hunting;
- Xray is embedded for detailed diagnostics and not duplicated;
- Story owns controls, docs, tests, narrative, explain, fidelity, and
  promotion;
- the selected rendered state is the first visual priority, with
  navigation, controls, status, and evidence supporting it;
- pending/pass/fail/error/cannot-run/blocked/dirty/redacted are visually
  distinct;
- large app-db, long narrative, and large variants-grid cases remain
  usable AT the parity budgets in §10.1, enforced by the deterministic
  budget gate (`re-frame.story.budgets-cljs-test`);
- human UI, Story MCP, and Story-related skills do not diverge into
  separate artifact models;
- extension work is deferred or constrained unless tied to a concrete
  user story;
- the UI feels like one product despite embedding Xray, and looks like a
  polished programmer workshop — not a generic dashboard, a marketing
  surface, or a raw debug console.

## Cross-references

| Concern | Source |
|---|---|
| What Story is / identity stance | [`000-Vision.md`](000-Vision.md) |
| Current shell + Xray per-panel embed | [`003-Render-Shell.md`](003-Render-Shell.md) |
| Mode-tabs primitive | [`007-Mode-Tabs.md`](007-Mode-Tabs.md) |
| Toolbar + `reg-mode` | [`010-Toolbar.md`](010-Toolbar.md) |
| Design tokens | [`016-Design-Tokens.md`](016-Design-Tokens.md) |
| Testing/Story substrate | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Controls + view states | [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md) |
| Inspector + Xray boundary | [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md) |
| Test + evidence | [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md) |
| Docs + share | [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md) |
| Xray host-facing focus API (`rf2-crtmq`) | [`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md) |
| Story MCP boundary | [`006-MCP-Surface.md`](006-MCP-Surface.md), [`../../story-mcp/spec/002-Tool-Registry.md`](../../story-mcp/spec/002-Tool-Registry.md) |
