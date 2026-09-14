# Story vs Storybook-class workshops — closeness test and advantage

Asked by [`ai/findings/Story/grok/prompt.md`](prompt.md).

- **Written**: 2026-09-14 11:15:00 AUSEST; **synthesis** ~12:30; **second sibling review** 2026-09-14 ~14:30 AUSEST
- **Research SHA** (scoreboard, Playwright, JVM probe): `98e8ffe9cb339295fe2fa459900d9d9647fab015`
- **This review's HEAD**: `35e3c00fb99fc7b646e142edaa70bc4ab7599c5f` — Story honesty items from the three reports have largely **landed**. Do not treat §2 as current trunk.
- **Read**: Spec 007, `tools/story/spec/{000,017,018,DESIGN-RATIONALE,Feature-Parity-Audit,015}`, `docs/story/01–09` + API, `tool-descriptors.edn`, login-form testbed, story-review.md as a *prior* (then re-checked at this SHA)
- **Ran**: Playwright against the compiled login-form Story shell; JVM `no-adapter-installed-run-is-an-error-not-a-pass` (1 test / 12 assertions / 0 failures)
- **Did not run**: Story-MCP stdio, `shadow-cljs watch`, Chromatic, `npm create storybook`, the full Story suite
- **Siblings:** first draft did not read them. Later: Astra (report + Storybook fixture + promotion/fidelity probes) then Fable (`report.md`, `matrix.md`, `evidence.md` — they compiled the tutorial, drove MCP, timed Storybook init). Source-checked before absorbing. See [`intermediate/synthesis.md`](intermediate/synthesis.md).

> Posture is that we are pre-alpha and focused on elegance, power and a masterpiece.
> But we are not over-engineering or gold plating. Also, we trust the programmer.
> We're trying to facilitate high productivity for them (and the AI they use) via a
> library with excellent ergonomics and low friction. We don't need to litigate every
> last fine detail and drown in the minutiae.

---

## 1. Answers in brief

**How we test closeness.** Jobs, not Storybook nouns. Seventeen jobs (install, navigation, cheap view-states, isolation, fx stubs, SPA journeys, docs, tests, evidence, recorder, share, agent, matrices, a11y, visual-regression *hook*, extension, fidelity). Four independent fields per job: **capability / discoverability / evidence / comparison**. `DIVERGENT` is allowed only when the *job* is still served. Protocol: [`intermediate/closeness-test.md`](intermediate/closeness-test.md). This run: [`intermediate/scoreboard.md`](intermediate/scoreboard.md).

**This run, categorically — do not average:**

- **Adoption-blocking for a new user:** first hour (J1). The install page does not compile as written (`mount-shell!` is 1-arity; it passes 2. Adapter ns `my-app.adapters.reagent` is a placeholder. Shell closure needs `@xyflow/react` / `elkjs` unnamed). Storybook's bar is `npm create storybook@latest` (~2 minutes in Fable's timed run).
- **Adoption-blocking for the surpass thesis:** **intent is not preserved across projections.** Promote a failure → the assertion vanishes. Upgrade fidelity → snippet unreadable, pin remains. Explain says ARGS/ASSERTIONS "not available" for a variant whose canvas has both. Test pane still prints "evidence spine pending." Auto-grid sidebar counts `· 0` because it reads `:variants`, not `:for`.
- **Ahead, because of re-frame2:** many *application* states at once (J4), stub any `reg-fx` (J5), epoch-backed *inspection* (J9), `:cannot-run` (J8 run path), fidelity *labels* that refuse to let a pin satisfy `sub-equals`.
- **Match with a different shape:** controls chrome, docs (markdown, not MDX), recorder, share URL, MCP *existence*, extension points. Storybook 10.6 already runs stories as Vitest browser tests *and* can author fail/retry/success in `play()`. J6 is not "can do an async journey"; it is "five isolated app frames of a real machine."
- **Behind or unpaid:** install; everyday visual *review*; CSS/focus/portals vs iframes; schema-derived controls with no schema on any shipped demo view.
- **Unknown *this process*:** catalog-scale search; live MCP (Fable drove a 12s JVM loop — cited, not re-run).

**How we are better.** Not "more panels." A variant is a **named application plan**. The product hypothesis, sharpened: **the same intent survives every projection** — canvas, Explain, Test, promote, upgrade, share, agent. `018` §3.1 is still the right five concepts. The failures are between working pieces, not missing subsystems.

**Do this first (at the research SHA):** make promotion and fidelity-upgrade keep intent. **At HEAD `35e3c00fb9` those generators, the install page, login schema, Explain args, evidence link, auto-grid count, run hashes, chapter 01/README, and host/isolation docs have landed.** Remaining: template/Clojars (D-1), orphan `register-variant` vs the skill promise, a visual-review recipe when a design-system team names the job. Do not start a Chromatic clone, skip pixel capture on unchanged input-hash, add MDX, or import a weighted score.

---

## 2. This run's scoreboard

Full table in the scoreboard file. Highlights:

| Jobs | Verdict vs Storybook 10.6 practical workflow |
|---|---|
| J1 install + HMR | `BEHIND` — page **does not compile as written**: 2-arg `mount-shell!` vs 1-arity; `my-app.adapters.reagent`; unnamed `@xyflow/react`/`elkjs`. README `:component` is a function (schema wants keyword). |
| J3 cheap view-states | `MATCH` chrome; **latent AHEAD** on schema→controls. No `:schema` on `login_form/views.cljs` — shipped UI says "no schema registered for the variant's :component". |
| J4 many live states | `AHEAD` — **exercised**: five login frames in one grid. Sidebar `GRID · 5` is honest; `VARIANTS-GRID · 0` on `:auto-grid` is a count bug (`workspace-grid-grouping` uses `:variants`, not `:for`). |
| J4 CSS/focus/portals | `BEHIND` — canvas is a stamped `div`, not an iframe |
| J5 any-fx stub | `AHEAD` — **exercised**. HTTP also has a `:network` slot that *lowers* to the same stubs (017) — not a third encoding. |
| J6 SPA journey | **Narrowed `AHEAD`:** isolated *application* states of a real machine. Storybook `play()` already does fail/retry/success against an async function (Astra fixture). |
| J8 variant is a test | `MATCH` on `run`/`is`/`explain` + `:cannot-run` `AHEAD`. **Promotion path `PARTIAL`:** a failing run can promote to a `:pass` with empty assertions (`artifact->variant-body` does not copy `:expect`). story-review P1s on the *run* path are **fixed** at this SHA (JVM no-adapter test green). |
| J9 explain failure | `AHEAD` on inspection — **exercised**: Xray Epoch on the assertion. `BEHIND` on *retain*: promotion is the broken handoff. |
| J17 fidelity ladder | Labels `AHEAD` (chips **exercised**). **Upgrade path `PARTIAL`:** `upgrade-snippet` comment swallows the closing `})`; `:extends` keeps parent `:sub-overrides`. |
| J12 agent | MCP exists on both sides (Storybook's is **preview**). Ours: 19 tools, fail-closed on JVM, skill split. Fable drove a write-gated stdio loop (~12s) — **sibling-exercised**, not this process. Skill promises orphan `register-variant` is refused; Fable's call returned `{:registered? true}`. |
| J14 a11y | `COMPLETE` panels. Astra: variant axe 0 violations; direct scan `color-contrast` incomplete on 8 nodes. Chrome a11y is opt-in. |
| J15 visual review | `DIVERGENT` on being a service; `BEHIND` on a daily review path. `snapshot-identity` hashes **declared inputs**, not pixels. **Do not skip capture when the hash is unchanged** (Fable R10) — CSS/fonts/view code can move with the same input tuple. |

Playwright shots: [`probe-login-form-idle.png`](intermediate/probe-login-form-idle.png), [`probe-login-form-workspace.png`](intermediate/probe-login-form-workspace.png).

Storybook extras included in the comparison, named: addon-vitest, addon-a11y, `@storybook/addon-mcp` 10.6, Chromatic (paid). Story extras: Xray (declared dep), Story-MCP (separate jar), skills.

---

## 3. What the May audits got wrong or went stale

The May Feature-Parity-Audit's "28 of 33, five gaps" is **not** today's reading.

| Then | Now |
|---|---|
| C-1 no global decorator | **Shipped** (`reg-global-decorator`). Untaught in 01–09 |
| C-2 plain-text docs | Already marked resolved in the audit file |
| C-3 no loader teardown | **Shipped** (`:loaders-teardown`) |
| C-4 no story rollup | **Shipped** (`docs-rollup-view`) |
| C-5 args undermarketed | **Still true** |
| D-1 no init scaffolder | **Still true** (and worse: not on Clojars) |
| D-2 mandatory vocabulary install | **Closed** (auto-install) |
| D-3 no Playwright recipe | **Closed as a doc** |
| "No peer MCP" (SOTA folklore) | **False.** Official Storybook MCP, preview, 10.3+ |

The September `story-review.md` P1s (`reg-check` inert, tape inheritance, vacuous no-adapter `:pass`) are **fixed on this SHA**. They remain a *class*: claimed test wins must stay gated. They are not a current comparison debit.

---

## 4. Improved analysis (after siblings + source checks)

Two scoreboards were getting mixed. Split them:

| Surface | Story vs Storybook 10.6 |
|---|---|
| **Workshop chrome** (install, search, controls widgets, docs, test tab, share, a11y addon) | Match or behind. The door is J1. |
| **Application state** (frames, machines, fx stubs, epoch tape, fidelity labels, Xray) | Ahead where the substrate is the product. The login workspace is the exhibit. |

The product hypothesis is not "have MCP / have grids / have stubs." It is **intent preservation**: an authored expectation, arg, and fidelity rung should survive canvas → Explain → Test → promote → upgrade → share → agent. Current failures are *between* working pieces.

**One mechanism, two broken upgrades.** Both "keep this failure" and "make this state more real" default to `:extends`. `:extends` is a reuse rule (child-only assertions, inherited sub-overrides). Using it as a *mutation* is why promotion drops `:expect` and why a fidelity child keeps `PINNED`. Do not add a general un-set API. Make those two generators emit the slots they mean (`:expect` copy; explicit drop of `:sub-overrides`), or stop using `:extends` for them.

**The same class, elsewhere:**

| Projection | What the canvas knows | What the other face says | Source |
|---|---|---|---|
| Explain | heading `"Sign in"`; script `[:assert … state-is]` | ARGS / EFFECTIVE ARGS / ASSERTIONS "not available" | Astra `story-a11y.json` Explain dump; Fable `explain` vs `resolve-args` |
| Test vs Play | Play: PASS (1 step) on select | TESTS · 0/5 until Run all | Our Playwright body text |
| Test vs Evidence | run result has a tape | "evidence spine pending — failures will link here once the evidence panel lands" | `test_mode/view.cljs:559` — the panel already exists |
| Auto-grid count | `:for :story.login-form` renders five cells | `VARIANTS-GRID · 0` | `workspace-grid-grouping` = `(count (:variants body))`; auto-grid has no `:variants` key |
| Frozen run contract | `017` and `docs/story/04` list `:plan-hash` / `:run-hash` | `story/run` identity slots only attach if `parts` already has them; `runtime.cljc` never writes those keys | Fable JVM probe; `result.cljc:927` |

**J6, narrowed.** Astra's Storybook fixture *did* fail login, retry, and assert welcome in `play()`, under Vitest. Scoring "SPA journey" as a Story WIN because Storybook cannot click retry is wrong. The surplus is **five isolated frames of a real state machine**, not the journey genre.

**J3, narrowed.** Schema→controls is real code. It is invisible because **no shipped `reg-view` in the login or nine-states examples carries `:schema`**. That is not undermarketing in the tutorial sense; it is a missing demonstration. Keep schemas optional (trust the programmer). Put one on the flagship card.

**J15, narrowed.** Hash the inputs for *which case this is*. Compare pixels (or a downstream service) for *whether it still looks right*. Unchanged content-hash is not permission to skip capture: CSS, fonts, and view implementation are outside the tuple (`identity.cljc`). Reject Fable R10's skip rule. Ladle's Playwright recipe remains the local peer.

**Do not import Fable's 0.82 vs 0.71.** Different job IDs, a "data-only" control that awards Story for the encoding `018` already chose, community-addon cap, and more Story execution than Storybook execution. Their *negative control* (install GAP) and several source contradictions are the useful part. Their promotion WIN is a different probe (registration succeeded) than Astra's (the failing assertion disappeared). We keep Astra's on that row.

**MCP:** Fable's stdio loop is evidence the JVM authoring/run path exists without a browser. It is not evidence the skill split is free: `register-variant` accepted an orphan parent the skill says must fail, and live-browser tools still `capability-unavailable`. Storybook MCP needs the running workshop; that is a different host trade, not "they have none."

---

## 5. Advantage thesis

Detail: [`intermediate/advantage.md`](intermediate/advantage.md).

**Keep (structural):**

1. **Frame-isolated application states** — Storybook's iframe isolates CSS; it does not give you five `app-db`s. Exercised.
2. **One EDN plan** for canvas, test, share, agent — **the encoding is one; the projections currently disagree** (Explain args, Test vs Play counts, promotion `:expect`). Storybook Test still writes JS modules.
3. **`force-fx-stub`** — one seam because `reg-fx` exists. MSW is HTTP.
4. **Epoch evidence via Xray** — Interactions/Chromatic see pixels and play steps, not the six dominoes.
5. **Computed fidelity** — cheap states stay useful and labelled. The *label* is built; the *upgrade* is not yet a trustworthy handoff.

**Disproved as advantages:** "we have MCP and they don't"; "many states at once" against Portfolio/devcards (they already did the *component* grid); "promote a failure and you have a regression" — **the transformation currently drops the expectation**.

**Latent:** schema → controls as the *front door* (built, untaught); machine-derived variant sets (not built — `generate.cljc` is seed/shrink); production-epoch → variant (replay exists, the product path does not); one skill for discover→run→fix (spec wants one path; skills split it).

**Limits to stop papering over:** non-serialisable args; live I/O; CSS/portals on an in-page canvas. Document; don't build machinery.

The five user-facing primitives in `018` §3 are still the set. They fail today at **projection boundaries**, not from missing primitives.

---

## 6. Recommendations

Sequence: **intent-preserving generators → install page that compiles → one visible schema**. Then the honesty cluster if still cheap. Then **stop**. No beads filed. Do not add a merge/un-set framework.

### Keep measuring

Re-run [`intermediate/closeness-test.md`](intermediate/closeness-test.md) after a Storybook major, a Story UI EPIC, a `docs/story/01` rewrite, or a test-path P1. Execute subset now includes: promote a known-failing variant; `read-string` of an upgrade snippet; Explain args vs canvas; auto-grid sidebar count; install snippet compile. Do not add a gate that needs a human per run.

### Intent-preserving transformations (do first)

1. **Promotion keeps the failed expectation (J8/J9).** Copy applicable `:expect`/script assertions onto the child. Do **not** change global `:extends` inheritance — that rule is correct for reuse; it is the wrong primitive for "this run, as a test." Acceptance: original fault still fails after promote; fixing the app makes it pass. Size S–M, `promotion.cljc` + pin. **Do first.**
2. **Fidelity upgrade compiles and drops the pin (J17).** Move the `;` comment off the last map line; emit an explicit absence of `:sub-overrides` (or do not `:extends` for this). Acceptance: `read-string` succeeds; compiled fidelity does not still contain `:sub-overrides` unless the author kept the pin. Size S, `view_state.cljc`. Same class as (1): stop using `:extends` as a mutation.

### First hour that compiles

3. **Install page is a program, not folklore (J1).** `docs/story/index.md`: 1-arity `mount-shell!`; real adapter require (`re-frame.adapter.reagent` or an explicit "your app's adapter ns"); name `@xyflow/react` and `elkjs` (or lazy-load machines-viz). README `:component` is a keyword. Watch URL in chapter 01. Size S, docs. Clojars/template wiring is operator-owned (`rf2-pv7p`); do not fake `npx`.
4. **One schema on the login card (J3).** Optional schemas stay optional. The flagship `reg-view` should carry one so derived controls appear on `:8043`. Size S. Acceptance: `/idle` does not show "no schema registered for the variant's :component."

### Honesty cluster (S; one pass if convenient)

5. **Evidence row is a link**, not `test_mode/view.cljs:559`'s "pending once the evidence panel lands." The panel exists. Acceptance: one click from a failed assertion to its beat.
6. **Explain shows story-level args** (and script assertions, or stop claiming ASSERTIONS). `explain` vs `resolve-args` disagree today.
7. **`:plan-hash` / `:run-hash` on `story/run`**, or strike them from the frozen contract (`017`, `docs/story/04`). Prefer attach — the fns exist; `render-variant` already carries one.
8. **Auto-grid count uses the same enumerator as the canvas** (`:for`, not only `:variants`). Acceptance: `VARIANTS-GRID · 5` for `:Workspace.login-form/auto-grid`.
9. **`register-variant` refuses a missing parent**, or the skill stops promising it.

### Then docs only

10. **J4 isolation honesty** — frames isolate state, not CSS/focus/portals.
11. **J15 recipe** — Playwright/Ladle/Argos keyed by `snapshot-identity` as *case id*, pixels compared separately. **Do not skip capture on unchanged hash.**
12. **J12** — chapter 9 first screen: JVM stdio vs Pair live-browser.

### Do not build

- Chromatic or a hosted snapshot service.
- Skip-on-hash visual gate (Fable R10).
- An iframe canvas "because Storybook has one."
- A second test runner, debugger, or story format.
- MDX, CSF importer, addon marketplace.
- A generalized `:extends` un-set / deletion API.
- Weighted parity scores as a product metric.
- Bead restore of decayed ids (mayor/tracker, not Story).

**Stopping point.** After (1)(2)(3)(4), stop unless (5)–(9) are still one afternoon. Those four serve the thesis (intent survives) and the door (J1/J3) without new concepts.

Hot-zone if later work touches Spec 007 / `spec/API.md` / workflows: sequence. Next code: `promotion.cljc`, `ui/view_state.cljc`; next docs: `docs/story/index.md`, `tools/story/README.md`, login-form `reg-view`.

---

## 7. How to re-run the test

See [`intermediate/closeness-test.md`](intermediate/closeness-test.md) §Re-run procedure. Inputs: SHA, date, current Storybook major, this job list. Execute: tutorial stall log **including compile of the install snippet**; login-form shell; JVM honesty test; MCP names; **promotion of a known-failing variant; `read-string` of an upgrade snippet; Explain args vs canvas; auto-grid count**. Output: a new `scoreboard.md`. Invalidation: Storybook major, Story UI EPIC, `01` rewrite, test-path P1, promotion/`view_state`/sidebar-count changes.

Landscape for competitors: [`intermediate/landscape.md`](intermediate/landscape.md). Claims: [`intermediate/evidence-ledger.md`](intermediate/evidence-ledger.md).

---

## 8. What this pass did not do

- Drive Story-MCP over stdio in *this* process (Fable did; cited).
- Compile a greenfield consumer from the install page in *this* process (Fable did; arity/npm/adapter checked here at source).
- Open Docs / Tests / a11y / Share / REC in the *this* Grok shell pass (Canvas + workspace only). Astra independently exercised Test Run-all (5/5) and a heading control edit; cited, not re-run.
- Re-execute Astra's promotion/fidelity probes in this process. The code paths that make their JSON inevitable were read at this SHA.
- Measure `018` search/edit-to-render budgets.
- Plant a CSS leak or a portal escape (reasoned `BEHIND`, not demonstrated).
- Run Histoire, Ladle, or Storybook locally in *this* pass (Astra did run Storybook 10.6.0).
- Time `npm create storybook`.
- File beads or edit tracked files.
