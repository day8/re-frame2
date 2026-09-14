# Evidence — Story vs Storybook-class workshops (rf2-rln91)

- **Written**: 2026-09-14 10:14 AUSEST (session start 09:41:33 AUSEST). **Revised**: 2026-09-14 11:05 AUSEST — sections §T, §C, §G, §K, §L, §X and §S2 added; §J1 stall 1 and §P4 annotated; nothing measured in the first pass was altered.
- **Trunk pinned**: `98e8ffe9cb339295fe2fa459900d9d9647fab015` — read at 09:41:33, re-read 09:59:57 (unchanged); every measurement below was taken at that sha. **Revision sha**: `ece9b657be90e99461773b859a767acdd97662dd` (11:05:33 AUSEST); `git diff --stat 98e8ffe9cb..HEAD -- tools/story tools/story-mcp docs/story skills/re-frame2 skills/re-frame2-pair tools/template implementation/package.json tools/machines-viz` is empty, so every line number cited here holds at both shas (§L re-verifies the ones the report leans on).
- **Machine**: Windows 11 Home 10.0.26200, Git Bash; Clojure CLI 1.12.4.1618; node v24.13.0; shadow-cljs 3.4.10; Playwright 1.59.1 (`implementation/node_modules`); react/react-dom 19.3.0.
- **Scratch root** (nothing under the repo): `C:\Users\miket\AppData\Local\Temp\claude\C--Users-miket-code-re-frame2\42c31ef4-cd81-4ccf-920a-6070745a6efa\scratchpad` — referred to below as `<scratch>`. Files: `probe.clj`, `probe2.clj`, `probe3.clj`, `probe4.clj`, `probe5.clj`, `mcp-prelude.clj`, `mcp-requests.jsonl`, `mcp-requests2.jsonl`, `mcp-out*.jsonl`, `mcp-err*.txt`, `walk-login-form.cjs`, `walk2.cjs`, `walk-myapp.cjs`, `shots/*.png`, `sb-first-hour/sb-app/`, `story-first-hour/my-app/`.
- **Rule kept**: no tracked file edited, no build that changes the tree, no PR, no `bd` write. `bd show` reads only; the four decayed beads were read out of git history (§B). The revision ran no suite and no browser; it re-read source, beads and the sibling receipts.

Every output block below is verbatim from this session unless marked "recorded from the earlier part of the session" (the first JVM probe and the first two walks ran before a context compaction; their outputs are in the session transcript and are summarised here from the notes kept at the time).

## J0 · Storybook first hour (reference)

Scratch: `<scratch>/sb-first-hour/sb-app`, fresh Vite React app, then `npx storybook@latest init --yes --no-dev`.

| Step | Wall time |
|---|---|
| `npm create vite@latest` scaffold | 4 s |
| `npm install` | 4 s |
| `npx storybook@latest init --yes --no-dev` | **105 s** |
| Result | 216 MB / 177 packages added; `storybook@^10.6.0`, `@storybook/react-vite@^10.6.0`; installs `@storybook/addon-vitest`, `@storybook/addon-a11y`, `@storybook/addon-docs`, `@storybook/addon-mcp` (preview), `chromatic`, Playwright Chromium; writes `.storybook/{main,preview}.ts`, `src/stories/*.stories.ts` (Button/Header/Page with `args`, `argTypes`, `autodocs`, a `play` in Page) |

Ceremony to a running workshop: 2 (one command, then `npm run storybook`). Nothing had to be discovered by failure.

**Timing caveat (revision, from astra).** This 105 s and astra's numbers (Story compile 760 files / 540 compiled / 28.57 s on a warm checkout; Storybook fixture install 226 packages / 24 s) measure different tasks in different cache states; none of them ranks the tools. What holds across runs is the stall count and the ceremony count in §J1.

## J1 · Story first hour (the literal tutorial)

Scratch: `<scratch>/story-first-hour/my-app`. Built by following `docs/story/index.md` §Install Story and `docs/story/01-first-variant.md` literally; where the page was silent, the minimum shadow-cljs skeleton was supplied (`deps.edn`, `shadow-cljs.edn` with `:deps true` and `:dev-http {8090 "public"}`, `package.json` with shadow-cljs/react/react-dom, `public/index.html`, views/events/subs copied from `tools/story/testbeds/login_form/` with the ns renamed).

Stalls, in the order met, each pinned to the page that caused it:

| # | Stall | Source line | What a reader must do |
|---|---|---|---|
| 1 | `:local/root "../re-frame2/tools/story"` assumes the clone sits beside the project | `docs/story/index.md:100` — **and `:94` says so in words** ("the path assumes a re-frame2 clone sitting **beside** your project directory"). Revision: this is a documented assumption, not a page defect (astra's correction, verified); it stays in the ceremony count and leaves the defect list. | rewrite the path |
| 2 | `[my-app.adapters.reagent :as reagent-adapter]` — no such namespace anywhere | `docs/story/index.md:110` | discover `re-frame.adapter.reagent` |
| 3 | no npm step at all on the page; first compile fails: `The required JS dependency "@xyflow/react" is not available, it was required by "day8/re_frame2_machines_viz/chart.cljs"` (trace: `my_app/core.cljs → re_frame/story.cljc → re_frame/story/ui/shell.cljs → re_frame/story/ui/xray_embed.cljs → day8/re_frame2_xray/panels.cljs → … → day8/re_frame2_machines_viz/chart.cljs`) | page silent; dependency at `tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs:37` | `npm install @xyflow/react` |
| 4 | second compile fails: `"elkjs/lib/elk.bundled.js" is not available` (same trace) | same | `npm install elkjs` (+ the `markdown-it` family and `launch-editor`, found by reading `implementation/package.json` devDependencies rather than by a fourth failure) |
| 5 | third compile succeeds with a warning: `Wrong number of args (2) passed to re-frame.story/mount-shell!` — the page's `(story/mount-shell! node {})` | `docs/story/index.md:116` vs `tools/story/spec/API.md:476` `(mount-shell! dom-node)` | drop the `{}` |

Timeline (`date` stamps from the task logs):

| Step | Wall time |
|---|---|
| `npm install --no-audit --no-fund` (shadow-cljs, react, react-dom) | 3 s (16 packages) |
| compile 1 → fail (`@xyflow/react`) | 21 s |
| `npm install @xyflow/react` | 3 s (20 packages) |
| compile 2 → fail (`elkjs`) | 14 s |
| `npm install elkjs markdown-it markdown-it-block-image markdown-it-footnote markdown-it-texmath markdown-it-toc-done-right launch-editor` | 3 s |
| compile 3 → `Build completed`, 1 warning (arity), `public/js/main.js` 38 MB dev build | 50 s |
| arity fix + compile 4 → `Build completed. (702 files, 1 compiled, 0 warnings, 14.06s)` | 25 s |
| `node_modules` after | 43 MB, 45 top-level entries |

Walk (`walk-myapp.cjs`, headless Chromium, `http://localhost:8090/index.html#/stories`, served by `http-server` from the repo's `node_modules`):

```
load+settle ms= 7437 body chars= 1436          (6 000 ms of that is the script's fixed wait)
has story.login? true has idle? true
help dialog still open after Escape? 0
after click: has "Sign in"? true has Explain/Tests/Docs tabs? true true true
errors: 0
body head:  no modes registeredView[ ]Full▾Light▾DebugInspectShareShare ▸RecRECStoriesOTHER:dev:docs:test
  :story.login/idledevdocstest◌Pendingreal setupheadlessfresh frameTests · 0/1✓ 0✗ 0○ 1Run all○ watch
  ◆No variant selectedPick a story, variant, or workspace from the sidebar to render it here.
  XraydiagnosticSelect a variant to inspect via Xray.Controlsargs + modessave as new variant…add expectations…?Welco
```

First run of the walk failed on a modal: `<div role="dialog" aria-modal="true" aria-label="Story playground help"> intercepts pointer events` — the "Welcome to the Story Playground" first-run help (screenshot `shots/myapp-shell.png`: mode tabs, sidebar, inspectors, keyboard shortcuts `f s a t Ctrl-K Esc`, "Got it"). `Escape` closes it. The sidebar chips for `/idle` read `dev docs test · Pending · real setup · headless · fresh frame`.

Ceremony as exercised: ≥ 9 (`rubric.md` §4). With the page fixed and the npm closure named, it would be 6 (deps alias, shadow build, package.json, index.html, core.cljs mount, stories.cljs) — still three times Storybook's, because Story is a library and not a scaffolder (audit [D-1] is still open; `docs/story/index.md:92` says so in its own words: "The generator template emits no Story wiring"). R1 landed at `4ec3fa6c57` after this walk (§D): the page now names the real adapter namespace, the 1-arity mount, and `@xyflow/react@12.4.2` + `elkjs@0.11.1`; the stall table above describes the page as it was at the pin.

## P1 · First JVM probe (`probe.clj`, recorded from the earlier part of the session)

Run from `tools/story` with `clojure -Sdeps '{:deps {day8/re-frame2-epoch {:local/root "../../implementation/epoch"}}}' -M -i <scratch>/probe.clj` (~10 s, exit 0). Fixture pattern copied from `tools/story/test/re_frame/story/story_is_test.clj`: `story/clear-all!`, `rf.registrar/clear-all!`, `(reset! rf.frame/frames {})`, `(rf/init! plain-atom/adapter)`, `(reset! runner-events/run-state {})`, `install-canonical-vocabulary!`, `ensure-default-frame!`.

| Case | What | Result |
|---|---|---|
| D | the `story-review.md` P1 "silent `:pass` on a throwing setup" | `:error` — fixed at trunk |
| C | failing assertion | `:fail` with `:actual`/`:expected`/`:reason` on the record |
| E2 | the `story-review.md` P2 case | `:fail` — fixed at trunk |
| A/B | register + run passing variant, `story/is` inside `deftest` | `:pass`; `is` reports through `clojure.test` |
| F | `story/explain` | returns resolution/composition/expectations/`:required-runner`/`:fidelity` |
| G | inline plan through `story/run` | ran; result had **no `:plan-hash` key** (confirmed in P2 case S) |
| H | `force-fx-stub` decorator | stubbed run passes with no real call |
| H2 | unstubbed fx | `:fail` with 0 real calls — **my probe's error, not Story's**: see P3 |
| K | `snapshot-identity` | 8-char hex, stable across runs |

All three `story-review.md` (2026-09-13, trunk `1c9ff9697b`) P1/P2 defects are therefore fixed at `98e8ffe9cb`. The repair beads are read in §K.

## P2 · Second JVM probe (`probe2.clj`, verbatim)

```
H2 unstubbed status=:fail real-calls=0
   effects=[{:fx-id :probe/http, :args {:url "/x"}, :outcome :error, :error-trace 105, :epoch-id 5}]
   warnings=[]
   assertion={:assertion :rf.assert/effect-emitted, :status :pass, :reason "fx :probe/http was emitted during play", :actual #{:probe/http}, :expected :probe/http}
   epoch-tape count=4 first-epoch keys=(:committed-at :db-after :db-before :dispatch-id :effects :epoch-id :event-id :frame :frame-state-after :frame-state-before :kind :outcome :renders :rf.cofx :schema-digest :sub-runs :trace-events :trigger-event :rf.epoch/redacted-modified-paths-count :rf.epoch/sensitive?)
   control: dispatch on :rf/default → real-calls=0
H3 unstubbed via :setup status=:fail real-calls=0 effects=[{:fx-id :probe/http, :args {:url "/x"}, :outcome :error, :error-trace 151, :epoch-id 10}]
K  same-id same-body stable? true  body-change moves? true  renamed-same-body equal? false (5da6faa2 vs 44e8d9a2)
F2 explain args={} effective-args={} resolve-args={:heading "Sign in"}
Q  db-seed status=:pass fidelity=#{:db-seed} app-db={:rf.story/lifecycle :ready, :v 7, :rf.story/assertions [{:path [:v], :source-coord nil, :payload [[:v] 7], :reason "path equals expected", :passed? true, :expected 7, :elapsed-ms 1, :actual 7, :assertion :rf.assert/path-equals}]}
R  sub-overrides status=:fail (expect :fail — override never satisfies sub-equals) fidelity=#{:sub-overrides :real-setup} record={:actual 1, :expected 42, :status :fail}
S  plan-hash fn="9b39e3e4" run-hash fn="78cf0fec" result has :plan-hash? false :run-hash? false :evidence? false
T  run-artifact? true materialized plan keys=(:expect :explain :required-runner :run-artifact :script :source-chain :tags :world :variant/id)
   promoted registered? true body keys=(:doc :run-artifact)
U  :test-tagged=#{} all variants=8
DONE
real 0m8.807s
```

Readings:
- **H2/H3**: the `:rf/default` control also made 0 real calls, which pointed at the probe, not Story. `implementation/core/src/re_frame/fx.cljc:84` — "Handler signature: `(fn [ctx args] ...)` — **v2 changed from v1**". The probe registered a 1-arity handler; the effect threw; Story recorded `:outcome :error` on the effect and failed the run **although the only assertion passed**. That is the honesty property, not a defect. Re-done correctly in P3.
- **K**: the snapshot tuple keeps `:variant-id` by design (`017-Testing-Story.md:2654` "the snapshot tuple keeps its `:variant-id` slot").
- **F2**: `explain` drops story-level `:args`; `resolve-args` has them. Contradicts `017:603–604` and matches the UI Explain panel reading in W1. Root cause at source in §X.
- **S**: the run result carries neither `:plan-hash` nor `:run-hash` nor `:evidence`, although `017:2197–2198` and `docs/story/04-the-variant-is-a-test.md:118–119` list them and the fns produce them. `render-variant` does carry `:plan-hash` (`tools/story/test/re_frame/story/render_cljs_test.cljc:100`). Root cause at source in §X.
- **R**: the sub-override rung is honest (pinned value never satisfies `sub-equals`) and `:fidelity` names both rungs in play.
- **T**: run artifact → materialised plan → promoted variant; promoted body is `{:doc … :run-artifact …}` — registration succeeds, which is all this case checked; P4 checks what the promoted variant DOES.

## P3 · Third JVM probe (`probe3.clj`, verbatim, correct `reg-fx` arity)

```
V1 unstubbed status=:pass real-calls=1 effects=[{:fx-id :probe/http, :outcome :ok}]
V2 stubbed status=:pass real-calls=0 effects=[{:fx-id :rf.story.fx-stub/force-fx-stub+probe.http, :outcome :ok}] assertions=[[:rf.assert/effect-emitted :pass]]
V3 erroring-fx status=:fail (all assertions pass? true) effects=[{:fx-id :probe/boom, :outcome :error}]
DONE
```

- V1: headless, unstubbed effects **really run** (`real-calls=1`).
- V2: `[:rf.story/force-fx-stub :probe/http {:status 200}]` (`fx_stubs.cljc:20`) → no real call, the stub shows up as its own effect id, `effect-emitted :probe/http` still passes.
- V3: an effect handler that throws fails the run even when every assertion passes.
- A first attempt used a map payload for the decorator and got `:status :error` with `[:rf.error/exception :error]` — the shape is positional; the error was loud, not silent.

## P4 · Promotion probe (`probe4.clj`, run after reading the sibling reports; verbatim)

```
P4 source status=:fail assertions=1
   promoted body keys=(:doc :run-artifact) has :assertions? false has :expect? false
   promoted run status=:pass assertions=0 (sibling claim: :pass with 0 — expectation dropped)
P4 control (assert in :script) source=:fail promoted=:pass assertions=0
DONE
```

Source: `{:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 42]]}` → `story/run` → `make-run-artifact` → `promote-run-artifact!` (`re-frame.story`, through `promotion.cljc:220 artifact->variant-body`) → `story/run` of the promoted id. The control put the same assertion as a `[:assert …]` script step; it was dropped too.

**The boundary, stated precisely (revision).** `artifact->variant-body` builds `:setup`/`:script` from the artifact's `:event-program` (`promotion.cljc:200–214` `partition-program`) and copies `:network`, `:fx-overrides`, `:extends`, `:tags`, `:args` and the provenance link (`:220–264`); it never reads `:assertions` or `:expect`. So an expectation survives promotion **only when it rides the event program as a dispatched `:rf.assert/*` event** — astra's positive control did exactly that, and its promoted body reads `:script [[:dispatch [:login/flow [:login/dismiss]]] [:dispatch [:rf.assert/path-equals [:missing] 42]]]` and still fails (`ai/findings/Story/astra/evidence/sibling-followup.json`). A declarative `:assertions`/`:expect` (P4 main case) and a `[:assert …]` script step (P4 control — a runner step, not a dispatched event, so not in the program) are both lost. The fix belongs at that boundary, not in `:extends` inheritance, which deliberately keeps terminal assertions child-only (astra's Explain dump shows the merge decision `:assertions :child-only`).

## P5 · Fidelity-upgrade snippet probe (`probe5.clj`, verbatim)

```
(story/reg-variant :story.login-form/idle-upgraded
  {:extends :story.login-form/idle
   :setup   [[:dispatch [:your/setup-event {}]]] ; real events — proves handlers + app-db})
;; upgrade of :story.login-form/idle — drop :sub-overrides; the artifact stays a variant.
P5 read-string → THREW EOF while reading
```

`re-frame.story.ui.view-state/upgrade-snippet` (`tools/story/src/re_frame/story/ui/view_state.cljc:373`; the slot strings at `:392` and `:394` end in a `;` comment and `reg-variant-envelope` at `:395` closes the map on that line). Confirms the sibling finding. Astra additionally hand-completed the body and compiled it: the child's `:fidelity` read `#{:real-setup :sub-overrides}` with `[:login/email]` still pinned to `"PINNED"` (`astra/evidence/story-roundtrip-probes.json`) — the docstring at `:381–382` promises the upgrade "drops the `:sub-overrides`", and nothing in the fn emits a drop.

## M1 · story-mcp stdio session 1 (`mcp-requests.jsonl`, recorded from the earlier part of the session)

`cd tools/story-mcp && clojure -M -e "(load-file \"<scratch>/mcp-prelude.clj\")" -m re-frame.story-mcp.server --allow-writes < <scratch>/mcp-requests.jsonl > mcp-out.jsonl 2> mcp-err.txt`. The prelude installs the plain-atom adapter and registers `:story.agent` with `:story.agent/broken` (assertion expects 2, setup sets 1).

- 18 requests, exit 0, **12 s wall** for the whole session including JVM boot.
- `tools/list` → **19 tools** (Dev 3 / Docs 10 / Testing 4 / Write 2), matching `tools/story-mcp/resources/.../tool-descriptors.edn`.
- Loop: `list-stories` → `run-variant` (`:status :fail`) → `read-failures` (the failing record with `:actual 1 :expected 2`) → `register-variant` with the corrected body → `run-variant` (`:status :pass`).
- #15 `list-substrates`, #16 `read-a11y-violations` → capability-unavailable errors (CLJS-only state; the skill leaf says `re-frame2-pair` owns the live door).
- **#17** `register-variant` for `:story.nostory/orphan` with no registered parent story → `{:registered? true}`. `skills/re-frame2/references/tooling/story-mcp-loop.md:79`: "`register-variant`'s parent story must already exist… The agent fails into a documented error when the `:story.<path>` parent isn't registered".

## M2 · story-mcp stdio session 2 (`mcp-requests2.jsonl`, verbatim excerpts)

`real 0m8.055s`, exit 0. stderr: `prelude: adapter installed, 1 story / 1 variant registered` · `booted; allow-writes?= true protocol= 2025-06-18 server= re-frame2-story-mcp` · `Sensitive reads: gated (default; pass --allow-sensitive-reads to opt in)` · `stdin closed; exiting`.

```
#2 run-variant :story.agent/broken → {... :status :fail, :elapsed-ms 60,
   :assertions [{:path [:v], :payload [[:v] 2], :reason "expected 2 at [:v] but got 1", :passed? false, :status :fail, :expected 2, :actual 1, :assertion :rf.assert/path-equals}],
   :snapshot {:variant-id :story.agent/broken, :active-modes [], :substrate nil, :content-hash "8f15cdfd"}, :effects [], :warnings [], :schema-violations [] ...}
#3 register-variant (same id, corrected body) → {:variant-id :story.agent/broken, :registered? true}
#4 run-variant → {... :status :pass, :elapsed-ms 13, :assertions [{... :reason "path equals expected", :passed? true, :status :pass, :expected 1, :actual 1 ...}] ...}
#5 preview-variant → {:effective-args {}, :share-url "variant=story.agent%2Fbroken", :lifecycle :ready, :status :pass, :elapsed-ms 7, :snapshot {... :content-hash "8f15cdfd"} ...}
#6 get-docs-markdown :story.agent → "# Story `:story.agent`\n\nAn app slice the agent is asked to fix.\n\n## Default args\n\n—\n\n## Argument types\n\n—\n\n## Tags\n\n—\n\n## Decorators\n\n—\n\n## Variants\n\n### `:story.agent/broken`\n\nfixed in place\n\n**Args**\n\n—\n**Tags**\n\n- `:dev`\n- `:test`"
#7 list-assertions → {:canonical [{:id :rf.assert/path-equals, :payload "[path expected]", :semantics "(= (get-in @app-db path) expected)"} {:id :rf.assert/path-matches ...} {:id :rf.assert/sub-equals ...} {:id :rf.assert/dispatched? ...} {:id :rf.assert/state-is ...} {:id :rf.assert/no-warnings ...} {:id :rf.assert/effect-emitted ...} {:id :rf.assert/schema-error ...}] ...}
```

Readings: in-place re-registration of the same variant id is accepted and the next run sees the new body (the loop an agent needs); the run result over the wire is the same map the JVM returns (`:status`, `:assertions`, `:effects`, `:warnings`, `:schema-violations`, `:snapshot`, `:app-db`), with no `:plan-hash`/`:run-hash` here either; the content-hash is unchanged between #2 and #4 because it hashes the plan, not the outcome. **Astra's reading of this transcript is right and the report now says it**: request #3 changes the expectation from 2 to 1 while setup still sets 1, so the loop proves transport and same-id re-registration, not a requirement-preserving application repair (`astra/evidence/fable-mcp-receipt/`).

## W1 · Playwright walk of the login-form testbed (`:8043`, recorded from the earlier part of the session)

Served by the repo's `npm run dev -- :testbeds/login-form` (`implementation/shadow-cljs.edn` `:dev-http` 8043); `walk-login-form.cjs` then `walk2.cjs`. Screenshots in `<scratch>/shots/`.

- `/` returned 404, `/index.html` 200 — walked `/index.html#/stories`.
- Warm load to a rendered sidebar ≈ 4 s.
- Role-based tab selectors (`getByRole('tab', {name: 'Tests'})`) found nothing; `getByText(/^Tests$/)` worked. **RETRACTED in the revision — see §T**: the mode tabs carry `role="tab"` inside a `role="tablist"` at source, and astra's role selector found them; the first pass's selector ran against a shell with no variant selected, where the strip is not mounted.
- **Grid**: the story's auto `:variants-grid` rendered **5 isolated cells**; each cell's Xray cascade showed the event → db → sub → render chain with **source coordinates**. The sidebar label for the same grid read **"VARIANTS-GRID · 0"** (root cause in §X).
- **Tests tab**: results beside the canvas; the assertion row carries the placeholder **"evidence spine pending — failures will link here once the evidence panel lands"** (`tools/story/src/re_frame/story/ui/test_mode/view.cljs:559`) while the Evidence panel is present in the same shell. Astra reproduced it on a genuinely failing variant: the evidence row has `data-evidence=true` and zero interactive descendants (`astra/evidence/sibling-followup.json`, `story-failure-followup.png`).
- **Explain panel**: for a variant whose story supplies `:args {:heading "Sign in"}` it shows **"ARGS not available / EFFECTIVE ARGS not available"**; the Docs tab and the Xray Views panel show the heading.
- **Controls**: "no schema registered for the variant's :component" — none of `tools/story/testbeds/login_form/views.cljs`, `examples/core/login/`, `examples/patterns/nine_states/` puts a schema on a `reg-view`; schema→controls is pinned only by 7 test files.
- **Share dialog**: `?variant=` URL with reproducibility labels distinguishing what the URL carries from what it does not.
- **Save current as variant**: the dialog lists `sub-overrides`, `db-seed`, `route`, `network`, `fx-overrides` as "not yet projectable" (rf2-7pgiz, rf2-blw1q) — capture-or-warn, not silent loss.

## W2 · Playwright walk of nine-states (`:8040`, recorded from the earlier part of the session)

`examples/patterns/nine_states/stories.cljs`: the matrix workspace renders every awkward state on one screen; per-cell isolation as in W1. No new defects beyond W1's.

## W3 · Walk of the scratch consumer app (`:8090`)

See §J1. Same shell, same chips, same tabs as W1; 0 console errors.

## T · Retraction: the mode tabs ARE ARIA tabs (revision)

`tools/story/src/re_frame/story/ui/mode_tabs.cljs:173–186`: the strip is `[:div {:role "tablist" :aria-label "Story mode" :data-test "story-mode-tabs"}` and each mode is a `[:button {:role "tab" :aria-selected … :aria-current … :data-mode-tab …}]`; `workspace.cljc:638` carries a second `role "tab"`. Astra's `getByRole('tab', {name:'Tests'})` succeeded (`astra/evidence/sibling-synthesis.md` §Actual extra work). The first pass's W1 note and the ARIA half of R9 are therefore wrong; the likely cause is visible in §J1's body dump — with no variant selected the strip is not rendered, and the walk's role selector ran there. The count half of R9 stands (§X). rf2-dacnd was filed with both halves; Mike is told in the report's revision history.

## C · The pale heading is Story's own text colour, inherited (revision; astra's measurement, fable's source trace)

Astra's axe pass (`astra/evidence/story-a11y.json`) recorded the login card heading at `color: rgb(237, 235, 230)` on the card's `rgb(255, 255, 255)` background, with eight `color-contrast` nodes reported INCOMPLETE and zero violations (`{"incomplete":[{"id":"color-contrast","nodes":8}]}` in the receipt; the `.axe` summary block carries `violations: 0` and no `incomplete` key, which is astra's own point about clean bills). The revision did not re-run the browser; it traced where the colour comes from, and it is a literal:

| Link | File:line | What it says |
|---|---|---|
| the token | `tools/story/src/re_frame/story/theme/colors.cljc:100` | `:text-primary "#EDEBE6"   ; body / labels (slightly warm white)` — `#EDEBE6` is rgb(237, 235, 230) |
| applied on the canvas wrap | `tools/story/src/re_frame/story/ui/canvas.cljs:58–65` | `:wrap {… :background (:bg-canvas tokens) … :color (:text-primary tokens) …}` — the region the variant renders inside |
| and on the grid cells | `tools/story/src/re_frame/story/ui/workspace.cljc:587`, `:681` | `:color (:text-primary rf.story.theme.colors/tokens)` |
| the subject sets its own background but not its colour | `tools/story/testbeds/login_form/views.cljs:103–113` | `[:section {:style {… :background "#fff" …}} [:h3 {:style {:margin "0 0 1em"} :data-test "login-heading"} …]]` |

So the heading inherits the workshop chrome's near-white `color` through the stamped `div` (`canvas.cljs:728`) and paints it on the card's own white. It is not a testbed styling quirk and not a computed theme result; it is a CSS leak from chrome into subject, which is the job row A7 scores, demonstrated by accident rather than by a planted rule. A per-story iframe would not inherit `color`; a `color` reset on the variant root would stop this particular leak and no other. Control: `tools/story/src/re_frame/story/theme/colors.cljc:13–14` bans hex literals at use sites, so the grep for `237, ?235, ?230|edebe6` over `tools/story` and `implementation` (node_modules excluded) reads exactly 1 hit, the token, and 0 in the testbed.

## G · Greps behind the IMAGINED tier (revision; ripgrep-backed tool, controls taken from the target)

| Question | Pattern | Scope | Count | Control |
|---|---|---|---|---|
| Is there a production-epoch → variant path? | `replay-epoch\|epoch->variant\|from-epoch` | `tools/story/src` | **0** files | `epoch-tape\|run-artifact` over the same scope: **296** occurrences in 22 files; `(defn replay` reads `artifact.cljc:300,353,402` (`replay-into-frame!`, `replay-result`, `replay-run-artifact`), `story.cljc:1194`, `recorder/play_export_events.cljc:62`, `implementation/epoch/src/re_frame/epoch/tool_pair.cljc:698` — replay of a RUN ARTIFACT exists; nothing turns a production epoch into a variant |
| Is a machine walk built? | `state-machine walk\|hand-rolled` | `generate.cljc` | 1 hit, `:31` — "a `clojure.test.check` generator, a hand-rolled state-machine walk, or a recorded-interaction fuzzer all fit by wrapping their draw in a `gen-fn`" | the file's own header `:1–44`: seed sequence, `gen-fn` seam, delta-debug shrink; no walker |
| Is the canvas an iframe? | `iframe` | `tools/story/src/re_frame/story/ui` | **0** | `tools/story/spec/Tutorial-Embed.md`: 32 (embedding Story elsewhere — a different job); the canvas is `[:div {:data-rf-story-variant-root …}]` at `canvas.cljs:728` and `workspace.cljc:450` |
| Is `018` §3.1 a bar for isolation? | `iframe\|isolat\|CSS` | `018-Story-UI-North-Star.md` | 3 hits, all bundle isolation (`:624,638,640`) | — |
| Does chapter 02 name the CSS/focus/portal limit? | `CSS\|portal\|iframe\|focus\|stylesheet\|document` | `docs/story/02-every-state-side-by-side.md` | **0** | chapter 09 `:117` names `tools/story-mcp/spec` (the only hit for `story-mcp\|re-frame2-pair\|nREPL\|JVM\|Pair` in that chapter) |

## X · Root causes read at source for the drifts the first pass measured (revision; grok named three, fable verified all)

| Drift | Where it is decided | What the source says |
|---|---|---|
| Explain omits story-level args (C4, rf2-noxox) | `plan.cljc:1393–1414` | "the ambient (global / story) + per-run … layers live OUTSIDE the body and arrive via the `:run-args` opt … Absent (a pure plan-compile / explain / render-prep with no run opts) ⇒ the variant layer alone"; `arg-map (if run-args (deep-merge-all …) variant-arg-map)`. `plan.cljc:2005–2012` `explain` passes the caller's `opts` straight to `variant-plan`; `ui/explain_panel.cljc:101` calls `(rf.story.plan/explain variant-id)` with none, and its docstring `:9–10`, `:30–33` says it renders "the `:explain` map the pure plan compiler attaches" and "never invents data the compiler didn't emit". `plan.cljc:1996–2003` `effective-args` already folds `(rf.story.args/run-arg-layers variant-id opts)`, and `story.cljc:1815–1822` `resolve-args` delegates to it — the resolution exists; the scenario-facing explain does not route through it. |
| Run result lacks `:plan-hash`/`:run-hash` (B2, rf2-7vz97) | `result.cljc:927–929` | `identity-slots (select-keys parts [:variant/id :plan-hash :run-hash :runner :required-runner :fidelity :elapsed-ms])` — the keys ride only if `parts` carries them; `runtime.cljc` contains **0** occurrences of either key (control: `result.cljc` 6). The fns exist (`story.cljc:1102 run-hash`; P2 case S prints both). |
| Sidebar "VARIANTS-GRID · 0" (A4/R9 count half, rf2-dacnd) | `ui/sidebar.cljs:621–627` | `workspace-grid-grouping` docstring: "`:count` is the number of cells the grid enumerates (the body's `:variants` count; a `:variants-grid` that enumerates from the registry reports its explicit `:variants` count when present, else 0)" — the auto grid enumerates by `:for` and has no `:variants` key, so 0 is the documented output of a counter that cannot see it. |
| Promotion drops the expectation (B6, R0a) | `promotion.cljc:200–264` | §P4 above. |
| Upgrade snippet does not parse / keeps the pin (B4 ergonomics, R0b) | `view_state.cljc:373–400` | §P5 above. |
| Visual assertion is a hash, not pixels (B7, R10) | `play/browser.cljc:162–177`, `requirements.cljc:116,277` | `:rf.assert/visual-snapshot` "requires `:pixels`" and, at `:177`, is `:passed?` "iff the content-hash matches" — the comparator compares snapshot IDENTITY; a real-browser pixel diff is a separate `:pixels` requirement nothing in the tree fulfils. |

## K · Beads read at the revision (`bd show <id> --json`, `tr -d '\r'`, array checked, fields by `jq`)

Repair beads astra names for the `story-review.md` defects — all exist, all closed, close reasons match the grouping:

| id | status / closed | title (truncated) | close reason (head) |
|---|---|---|---|
| rf2-b2mt | closed 2026-09-12 | a reg-check's assertions never execute — checks aggregate `:pass` over an empty group | "Fix in PR #9717 … settle-terminal-assertions! now dispatches the distinct union of [:expect :assertions] and every expanded check atom" |
| rf2-jjhy | closed 2026-09-12 | compile-time guards skip every `:plays` entry after the first and every check body | "Fix in PR #9717 (one PR with rf2-b2mt) … fails :rf.error/story-check-unknown on a miss" |
| rf2-poty | closed 2026-09-12 | with no adapter installed, core run-variant returns `:pass` with zero assertions and no frame | "FIXED in PR #9723 … run-error-result … resolves a frame-free" `:error` |
| rf2-3okc | closed 2026-09-12 | dispatched?/effect-emitted/no-warnings read the frame's whole epoch ring, so a same-id rerun inherits | "FIXED in PR #9723 … per-frame run baseline (set-run-epoch-baseline! / clear-run-epoch-baseline!)" |
| rf2-499z | closed 2026-09-12 | the step-debugger and the scrubber walk the raw `:script`, not the compiled plan's script | "Fixed in PR #9718 … read the compiled plan's auto-run program … compiled with the run's arg layers" |
| rf2-ad25 | closed 2026-09-12 | thread the run opts into the scrubber and stepper readers (rf2-499z residual) | "Fixed in PR #9724 … the :test pane's scrubber and step-debugger now compile against the run's opts" |
| rf2-shx4 | closed 2026-09-07 | realize authored network fixtures in normal variant and inline runs | "AUDIT RESIDUAL closed by PR #9407 … original deliverable landed in PR #9398 … reached only the DEFAULT image" |
| rf2-gwye.7 | closed 2026-09-13 | resolve inherited and composed effective args for canvas and saved variants | "Fixed in PR #9761 … rf2-gwye.5/.6/.7 … each with a regression test shown red first" — canvas/save args only; Explain is rf2-noxox |

Beads this worker filed from the first pass (statuses at 11:00 AUSEST; they move):

| id | R | status | dependency |
|---|---|---|---|
| rf2-allgj | R1 install page | in_progress | blocks rf2-seb9h (`dependency_type "blocks"` on rf2-seb9h's record) |
| rf2-seb9h | R3 npm closure | open | blocked by rf2-allgj |
| rf2-wmoer | R4 schema on login-form | open | — |
| rf2-7vz97 | R5 result hash keys | in_progress | — |
| rf2-noxox | R6 explain args | in_progress | — |
| rf2-7etf3 | R7 Tests-pane link | open | — |
| rf2-bf12o | R8 orphan register-variant | open | — |
| rf2-dacnd | R9 sidebar count + ARIA roles | open | the ARIA half is retracted in §T |

## L · Line re-verification at the revision sha (`sed -n`, each printed and read)

`docs/story/index.md:92` (generator sentence — the first pass cited `:94`, which is the beside-clone sentence), `:94`, `:100`, `:110`, `:116`; `tools/story/spec/API.md:139`, `:476`; `017-Testing-Story.md:603–604`, `:2170–2172`, `:2197–2198`; `docs/story/04-the-variant-is-a-test.md:118–119`; `skills/re-frame2/references/tooling/story-mcp-loop.md:79`; `Feature-Parity-Audit.md:96`; `tools/story/README.md:50`; `schemas.cljc:479`; `ui/docs.cljc:1175`; `implementation/package.json:55`; `frames.cljc:18–21`; `fx_stubs.cljc:20`; `render_cljs_test.cljc:100`; `plan.cljc:641`, `:1599`, `:2005`; `story.cljc:648`, `:731`, `:1563`, `:1609`, `:1815`; `promotion.cljc:220`; `view_state.cljc:373`; `identity.cljc:276`, `:331`; `generate.cljc:31`; `test_mode/view.cljs:559`; `canvas.cljs:64`, `:728`; `workspace.cljc:450`; `explain_panel.cljc:9–10`, `:30–33`, `:101`; `result.cljc:927`; `sidebar.cljs:621`; `mode_tabs.cljs:174`, `:182`; `theme/colors.cljc:100`; `login_form/views.cljs:106`, `:111`. All hold; the only correction is `index.md:92` vs `:94`.

## B · Bead recovery (the tracker does not carry the audit's normative beads)

`bd show rf2-2jdh9 --json` (and `rf2-5x1wt`, `rf2-m6tu`, `rf2-sgdd3`) → exit 1, "no issue found", a JSON **error object** on stdout (`jq '.[0]'` → `Cannot index object with number`). Cause: `d7d7b61dcc` (2026-07-17) "chore(beads): GC compaction (14-day retention) — decayed 9524 closed beads (restorable via bd restore), 30M->5.4M / 11234->1710 lines; live set 88 intact". Recovery, read-only:

```
MSYS_NO_PATHCONV=1 git show "d7d7b61dcc^:.beads/issues.jsonl" | jq -r --arg i rf2-2jdh9 'select(._type=="issue" and .id==$i) | ...' | tr -d '\r'
```

| id | status at `d7d7b61dcc^` | closed | title (truncated) | close_reason chars |
|---|---|---|---|---|
| rf2-2jdh9 | closed | 2026-05-21 | Story feature-parity with Storybook — build /docs/Story tutorial mirroring Storybook React tutorials + identif… | 338 |
| rf2-5x1wt | closed | 2026-05-31 | [NewTestStory] Implement the Story/testing vision | 432 |
| rf2-m6tu | in_review | — | Research Storybook + JS-world component-dev tools to design re-frame-2-story artefact feature set | 0 |
| rf2-sgdd3 | closed | 2026-05-18 | design(story): deprecate Story's RHS scrubber/trace/actions inspectors in favor of embedded Causa… | 305 |

Cited by: `tools/story/spec/Feature-Parity-Audit.md:3,425` (rf2-2jdh9), `tools/story/spec/README.md:16,21,30` and `DESIGN-RATIONALE.md:5,444,677` (rf2-m6tu, rf2-5x1wt, rf2-sgdd3). `CLAUDE.md` § Beads durability states routine decay is banned because close reasons are normative; the compaction predates that rule's enforcement, and `bd restore` is named in the commit as the recovery — a `bd` write, so not run here.

## A · Audit re-verification (`tools/story/spec/Feature-Parity-Audit.md`, Phase A of rf2-2jdh9)

| Gap | Audit line | State at `98e8ffe9cb` | Evidence |
|---|---|---|---|
| C-1 global decorator | 28, 99 | landed | `re-frame.story/reg-global-decorator` `tools/story/src/re_frame/story.cljc:731` |
| C-2 rich prose | 29, 117 | landed (audit says so: rf2-wl7yr) | markdown rollup rendered on `:8043` Docs tab; `get-docs-markdown` M2 #6 |
| C-3 loader teardown | 30, 104 | landed | `:loaders-teardown` present in `frames.cljc`, `plan.cljc`, `runtime.cljc`, `schemas.cljc`, `identity.cljc` |
| C-4 per-story rollup docs | 31, 118 | landed | `tools/story/src/re_frame/story/ui/docs.cljc:1175`, `ui/shell.cljs:836`; `tools/story/spec/008-Docs-Mode.md` §Per-story rollup (rf2-8j7wg) |
| C-5 args as first-class | 32 | landed in docs; **undermarketed in examples** | `docs/story/01`, `API.md`; no shipped example carries a schema (W1) |
| D-1 scaffolder | 42 | **open** | `tools/template/{deps.edn,shadow-cljs.edn,package.json}` carry no Story wiring; `docs/story/index.md:92` |
| D-2 mandatory `install-canonical-vocabulary!` | 44 | landed | first `reg-*` auto-installs (`docs/story/index.md:119–121`; `story.cljc:648`) |
| D-3 Playwright tutorial | 46 | landed | `tools/story/spec/Tutorial-Playwright.md` |
| I-1 frame-as-isolation | 51, 107 | landed | grid cells isolated (W1); sidebar chip "fresh frame" (J1) |
| I-2 schema → controls | 52, 96 | landed in code, absent from every shipped example | 7 test files; W1 fallback text |

## R · README component example (read after the sibling reports)

`tools/story/README.md:50` — `:component  login-form`, with `login-form` referred from `app.auth.views` (a function); `tools/story/src/re_frame/story/schemas.cljc:479` — `[:component {:optional true} :keyword]`; `README.md:449` itself says "`:component` — keyword id of a registered `:view`". The README's front-door example is invalid against its own schema; `docs/story/01` teaches the keyword.

## D · Drift checks

| When | `git rev-parse HEAD` | `git status --porcelain` |
|---|---|---|
| 09:41:33 AUSEST | `98e8ffe9cb339295fe2fa459900d9d9647fab015` | clean |
| 09:59:57 AUSEST | same | — |
| 10:22:46 AUSEST (first pass concluded) | same | clean |
| 10:57:54 AUSEST (revision started) | `f17ba993f930bd9062071104d4d776aae2a3226c` | clean |
| 11:05:33 AUSEST (revision pinned) | `ece9b657be90e99461773b859a767acdd97662dd` | clean; Story-path diff from the pin: empty |
| 11:24:24 AUSEST (first write of the revised `report.md`) | `a34c318c8db56100a48ae4250cbdb59c5c0ba90a` | clean; Story-path diff from the pin: empty |
| 11:31:21 AUSEST (final write) | `4ec3fa6c5796fb5160dbc2d1300aca3622578627` | clean; **Story-path diff from the pin is no longer empty: `docs/story/index.md` +18/−7** — the install-page repair (R1, rf2-allgj) landed on trunk while this revision was being written. Every `index.md` line number in these four files is pinned to `98e8ffe9cb`/`ece9b657be`, where the page was unchanged; at `4ec3fa6c57` and later they have moved. Nothing else under the Story paths changed. |

## S · Sources (all read 2026-09-14)

In-repo (at the pinned sha; paths repo-relative): `spec/007-Stories.md`; `tools/story/spec/{README,000-Vision,Principles,001-Authoring,002-Runtime,003-Render-Shell,005-SOTA-Features,008-Docs-Mode,009-Test-Mode,013-Static-Build,015-Test-Coverage,017-Testing-Story,018-Story-UI-North-Star,019-Story-UI-Controls-And-View-States,020-Story-UI-Inspector-And-Xray,021-Story-UI-Test-And-Evidence,022-Story-UI-Docs-And-Share,API,Feature-Parity-Audit,DESIGN-RATIONALE,Tutorial-Playwright,Tutorial-CLJS-Unit}.md`; `docs/story/index.md` and chapters `01`–`09`, `docs/story/api/{index,mcp-surface}.md`; `tools/story-mcp/spec/{000-Vision,002-Tool-Registry,003-Write-Surface-Gating}.md`, `tool-descriptors.edn`, `README.md`, `src/.../server.cljc`, `tools/{dev,result,schemas}.cljc`; `skills/re-frame2/references/tooling/{story-mcp-loop,story-recorder}.md`; `skills/re-frame2-pair/references/stories.md` (revision); `tools/story/README.md`; `tools/story/src/re_frame/story.cljc`, `story/{plan,frames,identity,fx_stubs,promotion,generate,args,result,runtime}.cljc`, `story/play/browser.cljc`, `story/requirements.cljc`, `story/theme/colors.cljc`, `story/ui/{docs.cljc,shell.cljs,canvas.cljs,workspace.cljc,sidebar.cljs,mode_tabs.cljs,explain_panel.cljc,view_state.cljc,test_mode/view.cljs}`; `tools/story/test/re_frame/story/{story_is_test.clj,result_test.cljc,render_cljs_test.cljc}`; `tools/story/testbeds/login_form/*`; `examples/core/login/stories.cljs`; `examples/patterns/nine_states/stories.cljs`; `tools/template/*`; `implementation/{package.json,shadow-cljs.edn}`; `implementation/core/src/re_frame/fx.cljc`; `tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs`; `tools/xray/spec/000-Vision.md`; `ai/findings/story-review.md` (2026-09-13); `CLAUDE.md`.

Reference tools (upstream pages, read 2026-09-14; those marked † were read through the research agents whose reports are in the session transcript, the rest directly):
- Storybook 10.6 † — https://storybook.js.org/docs (CSF3 / CSF Next factories, controls & argTypes, interactions/`play`, `@storybook/addon-vitest` & portable stories, `sb.mock` automocking, addon-docs/MDX & autodocs, addon-a11y, composition refs, `@storybook/addon-mcp` preview with `test-run`/`stories-preview`/`review-create`, manifests JSON, `storybook skills`, `llms.txt`); Chromatic pricing † https://www.chromatic.com/pricing (Free 5,000 snapshots; $179; $399); the generated init sample read locally (J0).
- Vitest 5.0.0 browser mode † https://vitest.dev/guide/browser/ (`toMatchScreenshot`); Playwright component testing 1.63 † https://playwright.dev/docs/test-components; Testing Library † https://testing-library.com; MSW 2.15 † https://mswjs.io; `msw-storybook-addon` v3 † (community); Percy † https://percy.io (free 5,000); Argos † https://argos-ci.com (free 5,000; Pro $100); BackstopJS † https://github.com/garris/BackstopJS (no commits since 2024-09).
- Histoire 1.0.0-beta.1 — https://histoire.dev/guide/vue3/stories.html, …/controls.html, …/app-setup.html, …/docs.html, https://histoire.dev/guide/plugins/official.html, https://histoire.dev/reference/config.html; registry https://registry.npmjs.org/histoire; https://api.github.com/repos/histoire-dev/histoire (last commit 2026-06-14).
- Ladle 5.1.1 — https://ladle.dev/docs/stories, /controls, /msw, /a11y, /visual-snapshots, /addons, /cli, /links, /width/; https://registry.npmjs.org/@ladle/react; https://api.github.com/repos/tajo/ladle.
- React Cosmos 7.4.1 — https://reactcosmos.org/docs/fixtures/file-conventions, /fixture-modules, /fixture-inputs, /decorators, https://reactcosmos.org/docs/user-interface, /node-api, /static-export, /plugins; https://registry.npmjs.org/react-cosmos.
- Pattern Lab 6.1.0 (archived 2026-05-13) — https://patternlab.io/, https://patternlab.io/docs/using-pseudo-patterns/, /documenting-patterns/; https://api.github.com/repos/pattern-lab/patternlab-node (`archived: true`).
- Bit 2.2.45 — https://github.com/teambit/bit (README, `.mcp.json`, `contrib/claude-skill-bit-cli/CLI_REFERENCE.md`), https://bit.dev/ ; pricing page unreachable (see `report.md` §8).
- Non-JS and CLJS lineage † — SwiftUI `#Preview`/`@Previewable` https://developer.apple.com/documentation/swiftui/previews-in-xcode; Jetpack Compose `@Preview`/multipreview/`@PreviewParameter` and screenshot testing https://developer.android.com/develop/ui/compose/tooling/previews; Widgetbook 3.25 / 4.0-beta https://docs.widgetbook.io; elm-book 1.5.1 https://package.elm-lang.org/packages/dtwrks/elm-book/latest/; devcards https://github.com/bhauman/devcards (dormant since 2020); Nubank workspaces https://github.com/nubank/workspaces; Portfolio 2026.03.1 https://github.com/djblue/portfolio; re-com demo https://re-com.day8.com.au (args-desc drives validation + docs); Lookbook 2.3.15 https://lookbook.build.

## S2 · Sibling receipts cited in the revision (read, not re-run; each opened and its counts checked)

| File | What it establishes | Checked value |
|---|---|---|
| `ai/findings/Story/astra/evidence/storybook-tests-auto-annotations.json` | Storybook 10.6.0 + `@storybook/addon-vitest` ran the CSF story file as browser tests | `numTotalTests 3, numPassedTests 2, numFailedTests 1, numPendingTests 0`; `Login.stories.tsx`: Idle passed, Retry To Success passed, Deliberate Failure failed; the sibling `storybook-tests-re-frame2-2.exit` records the expected non-zero exit |
| `astra/evidence/storybook-journeys.json` | the same fixture in the browser: inferred heading control updates the view; async retry succeeds with steps in Interactions; a deliberate expectation fault shows expected/actual | three checks, all `passed` |
| `astra/evidence/npm-baselines.json` | version facts | `@storybook/addon-vitest 10.6.0` peer `vitest ^3.0.0 || ^4.0.0`; npm latest `vitest 5.0.0`; `vite 8.3.0`; `msw-storybook-addon 3.0.0` (peer `msw >=2`, `storybook >=9`); checked 2026-09-13T23:44Z |
| `astra/evidence/story-a11y.json` | §C above | heading `rgb(237, 235, 230)` on `rgb(255, 255, 255)`; `color-contrast` incomplete 8 nodes; violations 0 |
| `astra/evidence/sibling-followup.json` | Explain `{}` vs resolver/run/view heading on one variant; failed-row evidence span non-interactive; promotion positive control with the assertion dispatched as an event | body quoted in §P4 |
| `astra/evidence/story-roundtrip-probes.json` | promotion drops a declarative assertion; upgrade snippet unreadable; hand-completed child keeps `PINNED` | cited in §P4/§P5 |
| `astra/report.md` §The baseline has moved | Storybook 10.6.0 released 2026-09-02 (astra cites the GitHub release page); v11 prerelease at the time | attributed, not re-fetched |
| `grok/intermediate/scoreboard.md`, `closeness-test.md`, `advantage.md` | the categorical headline, the two transformation probes in the execute subset, the IMAGINED rows for machine walks and epoch→variant, the CSS/focus/portals split of isolation | read; every source claim re-verified above before being absorbed |

## D2 · Drift at the second sibling review (2026-09-14 14:25 AUSEST)

`git rev-parse HEAD` = `5e27aff193`; `git status --porcelain` empty. Commits since the pin touching the files this report cites for A1/A3/B7 and the first hour: `1d9b1f0056` (login views carry a props schema — R4), `398fb4a94c` (chapter 01 says where Controls and the schema panel are), `9a73081044` (login card sets its own text colour — the contrast item), `fe414770e0` (chapter 01 and the README agree with the login_form testbed — rf2-ot2xp items 2–3), `e5c6121308` (chapter 01 prints the URL the dev server answers — rf2-ot2xp item 1). Bead statuses read by `bd show --json` at the same time: rf2-allgj, rf2-seb9h, rf2-wmoer, rf2-7vz97, rf2-noxox, rf2-7etf3, rf2-bf12o, rf2-dacnd, rf2-5vmog, rf2-mw9th, rf2-b7o66, rf2-ot2xp, rf2-851t0 all `closed`, each with a `Merged as PR #…` close reason (PRs 9795–9810). `run-variant` remains a public fn (`tools/story/src/re_frame/story.cljc:1026`) and Spec 008 names it (`spec/008-Testing.md:788`), so the README's use of it is not a stale verb. No row was re-executed at this trunk.
