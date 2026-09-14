# Phase C — Scoreboard (this checkout)

- **Date**: 2026-09-14 09:40–11:10 +10:00 (research run). **Second sibling review** notes HEAD `35e3c00fb9` has landed most honesty rows — this table is a **snapshot of `98e8ffe9cb`**, not current trunk.
- **SHA (this table)**: `98e8ffe9cb339295fe2fa459900d9d9647fab015`
- **Comparators**: Storybook **10.6.0** (docs 2026-09-14); Ladle/Histoire/Portfolio/Lookbook/Widgetbook as named in `landscape.md`
- **Executed**: (1) tutorial stall log; (2) Playwright against `http://127.0.0.1:8766/#/stories` on the compiled `login-form` testbed; (3) JVM `no-adapter-installed-run-is-an-error-not-a-pass` — 1 test, 12 assertions, 0 failures; (4) MCP tools from `tool-descriptors.edn` (read, not driven).
- **Synthesis:** after the independent draft, Astra's promotion/fidelity probes were source-checked at this SHA. Fable had no report. See `synthesis.md`.
- **Not executed (this process):** live `shadow-cljs watch` HMR; Story-MCP stdio; Chromatic; `npm create storybook`; large-catalog timing; axe scan; re-run of Astra's round-trip probes.

Playwright evidence: `probe-login-form-stories.png`, `probe-login-form-idle.png`, `probe-login-form-workspace.png`, `probe-login-form.txt`. Console note: `shadow-cljs watch for build :login-form not running!` (static serve).

## Job table

| Id | Capability | Discoverability | Evidence | Comparison | Notes |
|---|---|---|---|---|---|
| J1 | `PARTIAL` | `TAUGHT` | `EXERCISED` (stall log) | **`BEHIND`** Storybook | See stall log. Adoption-blocker. |
| J2 | `COMPLETE` (search + palette exist) | `TAUGHT` (welcome overlay names ⌘K) | `EXERCISED` (search input visible); scale **`UNVERIFIED`** | `MATCH` (unmeasured) | `sidebar-search` token-AND + command palette fuzzy. `018` §10.1 budgets not re-measured. |
| J3 | `COMPLETE` in code; **invisible on the flagship** | `UNDERMARKETED` | Astra edited heading. Login `reg-view` has **no `:schema`**; UI: "no schema registered for the variant's :component". | `MATCH` chrome; latent AHEAD only after a demo schema | Code is real. Shipped examples do not demonstrate it. Keep schemas optional. |
| J4 | `COMPLETE` for app-db isolation; `PARTIAL` for CSS/focus/portals | `TAUGHT` (`01`, `02`) | **`EXERCISED`** workspace grid | **`AHEAD`** Storybook on many live states; **`BEHIND`** Storybook iframe on CSS/focus/portals | Five login states rendered at once, different machine states, green assertions. Canvas is a `div[data-rf-story-variant-root]`, not an iframe (`canvas.cljs`). Portals to `document.body` would escape. |
| J5 | `COMPLETE` | `TAUGHT` (`index`, README, login testbed) | **`EXERCISED`** (`/submitting` cell `:rf.assert/effect-emitted :rf.http/managed`) | **`AHEAD`** Storybook+MSW | One decorator vs an addon per seam. Positive control holds. |
| J6 | `COMPLETE` | `TAUGHT` | **`EXERCISED`** all-states workspace | **Narrowed `AHEAD`:** isolated machine states, not the journey genre | Storybook `play()` already fail/retry/success (Astra). Surplus is five frames, not "can author async." |
| J7 | `COMPLETE` (markdown + rollup) | `TAUGHT` (ch.7 docs mode; API) | `SOURCE-TRACED` (not opened Docs tab this run) | `MATCH` on outcome; `DIVERGENT` on MDX/JSX | `docs-rollup-view` + `008` §Per-story rollup (C-4 closed). CommonMark, no MDX. Lookbook still warmer as "docs *are* the workshop." |
| J8 | `PARTIAL` (run path complete; **promotion drops `:expect`**) | `TAUGHT` (ch.4) | **`EXERCISED`** idle Play PASS; JVM no-adapter `:error`. Astra: Test Run-all 5/5. Promotion: their probe + our source read of `artifact->variant-body`. | `MATCH` Storybook Test on running; **`AHEAD`** on `:cannot-run`; **`BEHIND`** on "keep the failure as a regression" | `story/run` / `is` / `explain` work. Promotion copies script/setup, not assertions — promoted child can `:pass` with `:assertions []`. story-review *run-path* P1s fixed (`rf2-b2mt`, `rf2-3okc`, `rf2-poty`). |
| J9 | `PARTIAL` (inspect complete; retain broken) | `TAUGHT` (ch.6) | **`EXERCISED`** Xray Epoch on idle assertion | **`AHEAD`** on inspection; **`BEHIND`** on retain | Six-domino cascade is real. "Promote useful failures" in `018` is **SPEC-ONLY** until promotion keeps the expectation. |
| J10 | `COMPLETE` | `TAUGHT` (ch.5) | `SOURCE-TRACED` (REC button visible, not pressed) | `MATCH` / slight `AHEAD` (EDN vs JS play) | Recorder emits data. |
| J11 | `COMPLETE` | `TAUGHT` (ch.8) | `SOURCE-TRACED` (Share ▸ visible) | `MATCH` URL/static; `DIVERGENT` on hosted Storybook | Share URL, EDN, screenshot, `story:build`. Reproducibility honesty is the contract (`018` T4). No Chromatic hosting. |
| J12 | `COMPLETE` (19 tools) | `TAUGHT` with a **split** | `SOURCE-TRACED`; Fable **exercised** stdio loop | `MATCH` Storybook MCP (preview) | Skill split; orphan `register-variant` succeeded against the skill's promise (Fable). |
| J13 | `COMPLETE` | `TAUGHT` (`reg-mode` in ch.7; nine-states) | `EXERCISED` (theme chips visible) | `MATCH` toolbar; Chromatic Modes still richer for **baselined** matrices | Light/dark modes on login-form. No independent visual baselines (that's J15). |
| J14 | `COMPLETE` (two axe panels) | `UNDERMARKETED` in 01–09 | Astra: 0 variant violations; contrast incomplete | `MATCH` addon-a11y | Not clicked in this process. |
| J15 | `PARTIAL` | `TAUGHT` (ch.8) | `SOURCE-TRACED` | `DIVERGENT` on being Chromatic; `BEHIND` on review path | Hash = declared-input case id, **not** pixels. Reject skip-capture-on-unchanged-hash. |
| J16 | `COMPLETE` | `UNDERMARKETED` | `SOURCE-TRACED` | `MATCH` (fewer nouns, smaller ecosystem) | `reg-decorator` / `reg-story-panel` / `reg-global-decorator`. No marketplace — `DIVERGENT` on that *shape*, job still served. |
| J17 | `PARTIAL` (labels complete; upgrade handoff broken) | `TAUGHT` (ch.3) | `EXERCISED` (sidebar chips `real setup`). Upgrade: Astra probe + source of `upgrade-snippet`. | **`AHEAD`** on honesty of labels; **`BEHIND`** on "upgrade in place" | Fidelity computed, not self-reported. Snippet comment swallows `})`; `:extends` keeps parent `:sub-overrides`. |

## Headline (categorical)

- **Adoption-blocking for a new user:** J1.
- **Adoption-blocking for the surpass thesis:** J8/J9 promotion; J17 upgrade snippet.
- **Ahead where substrate pays:** J4 (many live *app* states), J5, J9 inspection, J8 `:cannot-run`. J6 only as isolated machines, not "async journeys."
- **Match with different shape:** J3, J7, J10, J11, J12, J16.
- **Behind or unpaid jobs:** J1; J15 review; J4 CSS/focus/portals; J12 agent *loop* ceremony (two skills).
- **Unknown this process:** J2 at catalog scale; live MCP (Fable drove one). J14: Astra scanned (0 variant violations; contrast incomplete).

Do **not** average these into a percentage. One J1 miss bounces the new user; one promotion miss bounces the "variant is a test" story.

## J1 stall log (executed)

Followed `docs/story/index.md` Install + `docs/story/01-first-variant.md` against the tree and the live testbed.

| # | Stall | Detail |
|---|---|---|
| 1 | Not on Clojars | README: artefact not published; install is `:local/root` assuming a clone *beside* the app. Storybook: `npm create storybook@latest`. |
| 2 | Template emits no Story wiring | `index.md` says so explicitly. Existing-app and greenfield are the same ritual. |
| 3 | Boot ceremony still real | `rf/init!` adapter, require stories ns, `mount-shell!` on `#/stories`. Auto-install of canonical vocabulary **does** land on first `reg-*` (D-2 closed). |
| 3b | **Install snippet does not compile** | `mount-shell!` is 1-arity (`story.cljc`); page passes `(story/mount-shell! node {})`. Require `my-app.adapters.reagent` is a placeholder. Shell pulls `@xyflow/react` + `elkjs` via machines-viz, unnamed on the page. Fable compiled this path; we checked the arity/requires at source. |
| 4 | `:component` is invalid in the README | Tutorial: keyword view id. README authoring example: **function** `login-form`. Schema (`schemas.cljc`) requires `:keyword`. Not two encodings — the README example will not register. |
| 5 | `:script` spelling split | Tutorial 01: `[:dispatch-sync [:rf.assert/state-is …]]`. Testbed: `[:assert [:rf.assert/state-is …]]`. Both may compile; a copier cannot tell which is canonical. Index claims the rename to `:script` is complete and `:play-script` is rejected. |
| 6 | Tutorial ids ≠ testbed ids | Tutorial `:story.login/idle`. Testbed `:story.login-form/idle`. Chapter 01 is not a copy-paste of the shipped example. |
| 7 | How to *run* the example | `01` says "Open `#/stories`." The watch command lives in a comment in `implementation/shadow-cljs.edn` (`npx shadow-cljs watch :examples/login-form`, port 8043). A first-hour reader following only `docs/story/` does not get a URL. |
| 8 | First-visit overlay | Welcome dialog before the canvas. Fine, but it is ceremony Storybook's onboarding also has. |
| 9 | HMR | Static serve logged `shadow-cljs watch … not running!`. Tutorial does not mention watch vs static. |
| 10 | README vs tutorial verbs | README still shows `run-variant` in places; tutorial teaches `story/run` / `story/is` / `story/explain`. |

Ceremony count vs Storybook: **~5 files/concepts** (deps alias, adapter, stories ns, hash router, mount) vs **one CLI**. That is a `BEHIND`, not a taste difference.

## story-review.md P1s vs this SHA

The 2026-09-13 review is **not current** as a gap list:

| Review | This SHA |
|---|---|
| F1 `reg-check` never executes | `runtime.cljc` concatenates `plan-checks` into terminal assertions; commit `ac36ed93bd` (`rf2-b2mt`) |
| F2 tape-scoped assertions inherit prior runs | `assertions.cljc` `run-slice` / epoch baseline; `40f80973e2` (`rf2-3okc`) |
| F3 no-adapter vacuous `:pass` | `run-error-result` frame-free `:error`; **EXERCISED** JVM test green (`rf2-poty`) |

Do not score J8 `AHEAD` as if those P1s were still live. Do not ignore them as a class: honesty gates must stay.

## May 2026 named gaps (appendix)

| Gap | Status 2026-09-14 |
|---|---|
| C-1 global decorator | **Closed in code + API.** `reg-global-decorator` / `configure! :rf.story/global-decorators`. Not in tutorial 01–09 → `UNDERMARKETED`. |
| C-2 markdown prose | **Closed** (`rf2-wl7yr`); audit file already says so. |
| C-3 `:loaders-teardown` | **Closed in spec + runtime** (`frames.cljc` `apply-loaders-teardown!`, `runtime.cljc`). |
| C-4 story rollup docs | **Closed.** `docs-rollup-view`; `008` §Per-story rollup (rf2-8j7wg). |
| C-5 Args branding | **Still `UNDERMARKETED`.** Primitive exists; tutorial does not lead with it. |
| D-1 one-liner scaffolder | **Open.** No Clojars, no `create storybook`. |
| D-2 mandatory `install-canonical-vocabulary!` | **Closed.** Auto-install on first `reg-*`. |
| D-3 Playwright recipe | **Closed as a doc.** `Tutorial-Playwright.md` (rf2-6qqry). |
| I-1 frame isolation | **Taught** (ch.1–2) and **exercised** (workspace). |
| I-2 schema → controls | **Still the undermarketed win.** |

Composition/federation and first-party VR service remain `DIVERGENT` as designed.

## 018 thesis — delivered? right?

The parity-and-surpass thesis is **the right thesis**. This run supports it:

- Jobs, not nouns, are what bounced or delighted.
- Surpass showed up where frames/events/schemas/Xray are the product (J4–J6, J9, J17).
- It failed where we still ask a Storybook user to learn a boot ritual (J1) or to go elsewhere for visual review (J15).

Copying iframe isolation, MDX, or Chromatic would spend the second half of the stance on nouns. Documenting CSS/portal limits is cheaper than becoming Storybook's preview iframe.
