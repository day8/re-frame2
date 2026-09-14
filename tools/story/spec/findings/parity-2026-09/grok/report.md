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

**This run, categorically — do not average.** Two clocks: the **research SHA** (`98e8ffe9cb`, §2 scoreboard) and **HEAD** (`35e3c00fb9`, §6). Shared reading; landings named after.

- **Adoption-blocking for a new user:** first hour (J1). At the research SHA the install page did not compile as written. At HEAD the page, npm names, 1-arity mount, chapter 01 watch URL, and README keyword `:component` have landed. **Still unpaid:** no template Story wiring, not on Clojars. Storybook's bar remains `npm create storybook@latest`.
- **Adoption-blocking for the surpass thesis (research SHA):** intent did not survive every projection (promote dropped `:expect`; upgrade snippet unreadable; Explain empty args; evidence-row placeholder; auto-grid `· 0`; missing run hashes). **At HEAD those seams were merged.** Re-execute the two transformation probes before calling them done.
- **Ahead, because of re-frame2:** many *application* states at once (J4), stub any `reg-fx` (J5), epoch-backed *inspection* (J9), `:cannot-run` (J8 run path), fidelity *labels* that refuse to let a pin satisfy `sub-equals`.
- **Match with a different shape:** controls chrome, docs (markdown, not MDX), recorder, share URL, MCP *existence*, extension points. Storybook 10.6 already runs stories as Vitest browser tests *and* can author fail/retry/success in `play()`. J6 is not "can do an async journey"; it is "five isolated app frames of a real machine."
- **Behind or unpaid:** everyday visual *review*; CSS/focus/portals vs iframes (now *documented*; demo card sets its own colour); agent loop still two hosts (now *named* in ch.09).
- **Unknown *this process*:** catalog-scale search; live MCP (Fable drove a 12s JVM loop — cited, not re-run).

**How we are better.** Not "more panels." A variant is a **named application plan**. The product hypothesis: **the same intent survives every projection**. `018` §3.1 is the right five concepts.

**Do this first at HEAD:** re-run promotion/upgrade probes; close orphan `register-variant` vs the skill; leave template/Clojars and a visual-review recipe to operator/design-system calls. Do not start a Chromatic clone, skip pixel capture on unchanged input-hash, add MDX, or import a weighted score.

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

The five user-facing primitives in `018` §3 are still the set. At the research SHA they failed at **projection boundaries**. At HEAD most of those seams have been closed; see §6.

---

## 6. Second sibling review

Fable's report was rewritten **after** our last synthesis (file mtime 14:10 AUSEST vs our 10:59). Astra's report (10:24) had already read an *earlier* Grok draft; their later citations of us are not a second independent measurement.

**What Fable added that we had not yet named this sharply**

- **Doorstep vs substance as two scores**, not one. Their A-rows vs B/C/X split (they quote 0.71 vs 0.84) is the same split as our chrome vs application-state table. Keep the split; still do not import the ratio as a product metric. They also note that collapsing the five "variant is a plan" rows makes the overall ratio ~1.0 — a useful caveat, not a reason to drop the jobs.
- **CSS leak specimen.** Login `h3` inherited Story canvas `:text-primary` (`#EDEBE6`) on a white card. That is document isolation, not frame isolation. At HEAD the card now sets `:color "#1f2328"` (`9a73081044`). Chapter 02 now says frames isolate state, not the page. The architecture limit remains; the specimen was patched in the demo.
- **Explain's actual mechanism.** Pure `plan/explain` folds ambient args only when given `:run-args`; the panel called it with none. That is a caller/integration bug, not a missing resolver. **Do not build a second resolver.** `05ce898c7a` / `f76cd19b08` fold those layers at HEAD.
- **MCP loop ≠ application repair.** Fable's 12s stdio pass changed the *expected* value from 2 to 1 while setup still wrote 1. Transport works. It does not prove "agent fixed the app while keeping the requirement." Astra said the same; keep that acceptance test.
- **They filed beads.** R0a `rf2-5vmog`, R0b `rf2-mw9th`, plus install/schema/explain/evidence/count/hashes. This Grok pass did not file tracker items (research constraint). Several of those beads **merged** before this review.

**What landed on HEAD `35e3c00fb9` since research SHA `98e8ffe9cb` (verified at source, not re-run in the browser):**

| Item | Commit / artefact |
|---|---|
| Install page compiles (1-arity mount, `re-frame.adapter.reagent`, named `@xyflow/react`/`elkjs`) | `4ec3fa6c57` |
| `story/run` carries `:plan-hash` / `:run-hash` | `9c03e228bb` |
| `explain` / View-State fold ambient args | `05ce898c7a`, `f76cd19b08` |
| Tests-pane evidence is a button to the Evidence panel | `dc767f800e` |
| Auto-grid count uses `resolve-layout` | `8dd880a697` |
| Login card `:rf/props` schema | `1d9b1f0056` |
| Login card own text colour | `9a73081044` |
| `upgrade-snippet` parses; pin dropped by walking the `:extends` chain | `f626f77092` |
| Promotion copies source `:assertions` / `:checks` | `15a850158a` |
| Chapter 01 watch URL + testbed id map; README `:component` is a keyword | `fe414770e0` |
| Chapter 02 document-isolation paragraph; chapter 09 two-host agent loop | present at HEAD |

**Still open (do not re-file the landed list):**

- **D-1 scaffolder** — `docs/story/index.md` still says the generator emits no Story wiring. Operator/Clojars (`rf2-pv7p`) is separate.
- **`register-variant` orphan parent** — `write.cljc` still `reg-variant*` with no parent check found. Skill still promises a documented error. Fable `rf2-bf12o`.
- **Visual-review recipe** as a *named design-system job*, hash as case id not skip signal. Held, not a Chromatic clone.
- **Standing test re-run at HEAD** — this review source-checked the diffs; it did not re-walk Playwright or re-promote a failing variant on the new commits.

**Rejected again:** Fable's weighted score; skip-capture-on-unchanged-hash (Fable's own revision now agrees); ARIA-tab absence; treating Astra-citing-Grok as new evidence.

### Consensus (this pass)

The three reports **converged on the thesis and the stop list**. They still **disagree on scoring and on how far to claim MCP/J6**. Detail in the session answer; the corpus does not need a fourth taxonomy.

| Shared | Split |
|---|---|
| Jobs not Storybook nouns; `018` five concepts; chrome/doorstep behind, application-state ahead | Fable publishes a weighted ratio; Grok and Astra refuse it as a product metric |
| Intent must survive promote / upgrade / Explain / Test | Astra: JS *can* instantiate stores — Story's edge is fewer translations, not impossibility |
| Do not build Chromatic, MDX, CSF importer, addon marketplace, iframe-by-default, hash-skip VR | Fable scored promotion WIN then reversed; Grok/Astra treated keep-the-assertion as the test |
| Remaining after landings: D-1/Clojars, orphan register, VR recipe if named, re-run probes at HEAD | Fable still leads the first-hour bullet with the *pre-fix* install page; Grok now clocks two SHAs |

---

## 7. Recommendations

**At the research SHA** the sequence was generators → install page → schema → honesty cluster. **At HEAD that sequence is mostly done.** Remaining, ranked:

### Keep measuring

Re-run [`intermediate/closeness-test.md`](intermediate/closeness-test.md) **on current trunk**, especially the two transformation probes and the install-snippet compile. Invalidation already fired: Story UI EPICs landed. Do not add a human-per-run gate.

### Remaining product work

1. **Re-execute promotion and upgrade probes at HEAD.** Source says they keep `:assertions` and drop the pin. A red-then-green pin is still required (`fail` after promote with the original fault). Size S, tests may already exist under those beads — read them before writing more.
2. **Orphan `register-variant`** matches the skill, or the skill stops promising a refusal. Size S. `tools/story-mcp/.../write.cljc`.
3. **Template Story wiring (D-1)** when Mike wants it — not a new workshop primitive. Held with Clojars.
4. **Visual-review recipe** only if a design-system team names the job. Playwright/Ladle; `snapshot-identity` as provenance; **never skip capture because the input hash is unchanged.**

### Do not build

- Chromatic or a hosted snapshot service.
- Skip-on-hash visual gate (Fable R10).
- An iframe canvas "because Storybook has one."
- A second test runner, debugger, or story format.
- MDX, CSF importer, addon marketplace.
- A generalized `:extends` un-set / deletion API.
- Weighted parity scores as a product metric.
- Bead restore of decayed ids (mayor/tracker, not Story).

**Stopping point.** After re-running the two transformation probes at HEAD and closing the orphan-register mismatch, **stop**. Template/Clojars and a visual-review recipe wait on operator/design-system calls. Do not reopen landed honesty items.

Hot-zone if later work touches Spec 007 / `spec/API.md` / workflows: sequence. Next code, if any: `tools/story-mcp` write tool (orphan parent). Do not re-edit `promotion.cljc` / `view_state.cljc` / install docs unless the HEAD probes go red.

---

## 8. How to re-run the test

See [`intermediate/closeness-test.md`](intermediate/closeness-test.md) §Re-run procedure. Inputs: SHA, date, current Storybook major, this job list. Execute: tutorial stall log **including compile of the install snippet**; login-form shell; JVM honesty test; MCP names; **promotion of a known-failing variant; `read-string` of an upgrade snippet; Explain args vs canvas; auto-grid count**. Output: a new `scoreboard.md`. Invalidation: Storybook major, Story UI EPIC, `01` rewrite, test-path P1, promotion/`view_state`/sidebar-count changes.

Landscape for competitors: [`intermediate/landscape.md`](intermediate/landscape.md). Claims: [`intermediate/evidence-ledger.md`](intermediate/evidence-ledger.md).

---

## 9. What this pass did not do

- Drive Story-MCP over stdio in *this* process (Fable did; cited).
- Compile a greenfield consumer from the install page in *this* process (Fable did; arity/npm/adapter checked here at source).
- Open Docs / Tests / a11y / Share / REC in the *this* Grok shell pass (Canvas + workspace only). Astra independently exercised Test Run-all (5/5) and a heading control edit; cited, not re-run.
- Re-execute Astra's promotion/fidelity probes, or the same probes **after** `15a850158a` / `f626f77092`. Those commits were source-read at HEAD; they were not re-run.
- Measure `018` search/edit-to-render budgets.
- Plant a CSS leak or a portal escape (reasoned `BEHIND`, not demonstrated).
- Run Histoire, Ladle, or Storybook locally in *this* pass (Astra did run Storybook 10.6.0).
- Time `npm create storybook`.
- File beads or edit tracked files. Fable filed several; this pass only read.
