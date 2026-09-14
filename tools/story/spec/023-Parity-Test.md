# 023 — Story parity test

> **What this is.** The standing, re-runnable test of how close Story is to
> Storybook-class workshops, and the dated reading it last produced. The
> protocol (§1–§6, §8, §9) is the test; the table in §7 and Appendix A are
> one reading of it. Do not mix them: a re-run changes the reading, not the
> protocol.
>
> **Owner:** rf2-hc3ur, under epic rf2-0ae7o. **Next re-run:** rf2-4gijz.
>
> **Builds on:** [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md#31-storybook-parity-and-surpass)
> §3.1, the thesis under test; [`017-Testing-Story.md`](017-Testing-Story.md),
> the run-result, explain and promotion contracts the probes exercise; and
> [`Feature-Parity-Audit.md`](Feature-Parity-Audit.md), the 2026-05-20
> manifest this test replaces as the standing measure.

## 1. Purpose and the thesis under test

`018` §3.1 says parity is a quality bar rather than a checklist of nouns:
Story should match Storybook where an experienced Storybook user has a
legitimate expectation, and deliberately surpass it where re-frame2's
substrate (frames, epochs, data-only plans, effects as data) gives leverage.
This page tests that claim. It names the jobs a person or an agent is trying
to get done, scores Story and the best Storybook-class answer for each job on
four layers, controls the instrument twice, and reports the result as
categories before any number. A reading says where Story is behind, where it
matches with a different shape, and where it is ahead because of re-frame2.
When the tree or the reference moves (§6), the next reading is a re-run of
this protocol, not a rewrite of it. Each surpass claim carries a tier in
`018` §3.1 that every run re-reads; what Story should not build is governed
by `018` §3.1's "should not copy" list, and this page adds no policy to it.

## 2. Jobs, not nouns

A row is a job both tools can be asked to do ("turn a bug report into a
failing, re-runnable variant"), never a feature name ("play functions"). The
test of a well-formed row is that both answers can be timed or counted. A job
that hides two jobs is split when the halves score differently: A4 and A7 are
one "side by side" job split into state and document isolation, because Story
is ahead on the first and behind on the second.

Rows are grouped by three journeys: the first-hour author (A); the maintainer
turning a bug into a failing variant and a fix (B); the agent asked to "make
the login error state match the design" (C); plus cross-cutting jobs (X).

| Id | Job | w | Why this weight |
|---|---|---|---|
| A1 | Install into an existing app and see a working workshop in the first session (**negative control**, §4) | 3 | The first session decides adoption. |
| A2 | Author the first story and variant for an existing view | 3 | The daily act. |
| A3 | Explore view states through controls without hand-writing them | 2 | A `018` §3.1 bar: Controls. |
| A4 | See many application STATES side by side, each its own app | 2 | A `018` §3.1 bar: Matrices. |
| A5 | Read docs beside the example without writing them | 2 | A `018` §3.1 bar: Docs. |
| A6 | Share a specific state by URL, honestly | 1 | Valuable; does not decide adoption. |
| A7 | Isolate the DOCUMENT across side-by-side states: CSS, focus, portals, timers | 1 | Not a `018` §3.1 bar and named by no real user; state isolation, which carries the thesis, is A4. |
| B1 | Turn a bug report into a failing, re-runnable variant using data only (**detection control**, §4) | 3 | Core of "variants are executable application plans". |
| B2 | Run variants as tests locally and in CI with one result shape | 3 | Core of "tests, docs, canvas, agent calls and replay share one plan". |
| B3 | Stub a side effect without touching production code | 2 | A legitimate Storybook-user expectation (mocking beside the example). |
| B4 | Seed state at a declared fidelity rung and be told which rung | 2 | A surpass item ("honest fidelity rungs") below the core plan thesis. |
| B5 | See why a variant failed: beats, db change, effects, source | 3 | "Failures can explain themselves", and `018` §10.1's failed-assertion-to-evidence budget. |
| B6 | Capture an ad-hoc exploration and promote it to a curated variant | 1 | Valuable; neither adoption nor the core thesis. |
| B7 | Run visual and a11y checks beside the example, locally | 2 | A `018` §3.1 bar: Status. |
| C1 | Discover stories, variants and their contracts by tool call | 2 | The agent's first act; Storybook ships an MCP addon in preview. |
| C2 | Run, read the failure, fix and re-run from an agent, with no browser (gated writes included) | 3 | Core of "Story MCP can drive the same plan/result path the UI uses". |
| C3 | Drive the live browser workshop from the agent | 1 | Valuable; the JVM loop in C2 carries the thesis. |
| C4 | Explain a variant's effective plan before running it | 2 | Composition without explanation is hidden global state (`017` §Explain API). |
| X1 | Compose shared context without decorator opacity | 1 | A `018` §3.1 expectation that rarely decides adoption. |
| X2 | Publish a static workshop build | 1 | Valuable; does not decide adoption. |
| X3 | Extend the workshop | 1 | Valuable; `018` §3.1 refuses addon sprawl as the extension model. |
| X4 | Ecosystem breadth, hiring pool, hosted-service economics | — | **OUT**: not a job the tool controls. Listed so a reader sees it was weighed. |

**Weight rules**, so a re-run checks the reasoning instead of re-arguing it:
**3** when the job decides adoption or is the core of the `018` §3.1 thesis;
**2** when it is a legitimate Storybook-user expectation on `018` §3.1's bar
list; **1** when it is valuable but neither. A job Storybook does well is not
thereby weight 2: the bar list is `018`'s, not Storybook's feature list.

**Reconciled with an independent taxonomy.** A second pass of the same
research used seventeen jobs (J1–J17). Each maps onto a row above, and none is
missing: J1→A1; J3→A3; J4→A4 and A7 (the split came from J4's CSS, focus and
portals note); J5→B3; J6, a real SPA journey, →B1 and A4; J7→A5; J8→B2;
J9→B5; J10→B6; J11→A6 and X2; J12→C1 and C2; J13's design-system matrix→A4,
with baselined matrices under B7; J14 and J15→B7; J16→X3; J17→B4. J2,
navigation at catalogue scale, is graded by `018` §10.1's budget gate and is
deliberately not re-measured here.

## 3. Vocabulary

### 3.1 Status

Exactly one status per row, **derived** from the two scores and the layers,
not chosen freehand, so the same evidence gives the same label on re-run. A
second flag (a WIN carrying a defect, say) goes in the notes.

| Status | Meaning | Derivation |
|---|---|---|
| **WIN** | Story does the job better, and Story's side is exercised or source-traced. | `S > B`, Story evidence at least source-traced |
| **MATCH** | Both do the job at comparable quality and ceremony. | `S = B` and `S ≥ 1` |
| **GAP** | The reference does the job; Story does not, or does it materially worse, and Story's SPEC does not promise it. | `S < B`, SPEC empty or future |
| **SPEC-ONLY** | Story's spec describes the job as delivered; the code does not do it, or does part. | SPEC present, CODE absent or partial |
| **UNDERMARKETED** | Story's code does the job at least as well, but docs, README, skills and examples do not teach it, or teach it wrongly. | CODE at least `B`, DOCS absent or wrong; scored at the CODE value |
| **DIVERGENT** | Story deliberately refuses the reference's mechanism, with the rationale recorded; judged on the job's outcome. | A recorded rejection exists; scores stand as measured |
| **OUT** | Not scored: outside what a workshop tool controls. | Excluded from every denominator |
| **TARGET** | Neither side does the job well and the substrate gives leverage: a build candidate, not a verdict. | `S ≤ 1` and `B ≤ 1`, or `S < B` with a named lever |

### 3.2 Four layers

Each row records, for Story: **SPEC**, the section promising the job (`—` if
none); **CODE**, the namespace or function doing it, with a line where one site
carries it; **DOCS**, the `docs/story/` chapter, README or skill leaf teaching
it (`—` if nothing does, "wrong" if CODE contradicts it); **ERGONOMICS**, the
ceremony count (§3.4) and the friction met when it was exercised. The
reference side collapses the four into one cell citing the upstream page and
version read: this is a reading of Story against a reference, not an audit of
Storybook.

### 3.3 Evidence strength

Recorded per side, per row.

| Strength | Meaning |
|---|---|
| **exercised** (`ex`) | Run at the pinned sha (a JVM probe, a story-mcp session, a browser walk, a timed install), with the output kept. |
| **sibling-exercised** (`sx`) | Run by a second researcher at the same sha, with a receipt that was opened and checked. Counts as exercised; labelled so a reader can tell whose run it was. |
| **source-traced** (`st`) | The code path was read at file and line, and not executed. |
| **documented-only** (`do`) | Only a doc, spec or upstream page claims it. |
| **unverified** (`un`) | Could not be checked; scored at face value and named in the reading. |

### 3.4 Ceremony count

The things an author must type or click that are not the story's own content,
counted from a working app to the job done: each new file (+1) and each
existing file touched (+1); each config key or alias added (+1); each command
run once (+1; an install of N packages is one command); each mandatory UI
gesture (+1). The story or variant body is free, because both tools need it.

### 3.5 Scores

Two scores per row, `S` for Story and `B` for the reference, each on
`{0, 0.5, 1, 1.5, 2}`.

| Score | Meaning |
|---|---|
| 2 | Does the job with ceremony of 4 or less and no caveat a first-hour author would trip on. |
| 1.5 | Does the job with one caveat the author will meet (a stale label, a doc-code drift, a missing link). |
| 1 | Does the job with real ceremony (5 or more), or only in some runners, or only if the author knows an undocumented thing. |
| 0.5 | Does part of the job; the rest is manual. |
| 0 | Cannot do the job at this sha or version. |

`B` is scored against the best Storybook-class answer for that job at the
versions in §8, naming the tool when a non-Storybook tool leads. A community
integration is scored like anything else, with its setup counted as ceremony
and its provenance named.

## 4. The two controls

Both are declared before scoring and scored by the same rules.

- **NEGATIVE — A1.** Installing into an existing app in the first session
  must read **GAP** (`S < B`) while the doorstep is open: no scaffolder
  (rf2-1bkoc, OPEN) and a repaired install page nobody has walked in a clean
  consumer. If A1 reads anything else, the instrument is biased toward Story:
  stop and re-read every WIN.
- **DETECTION — B1.** A known, planted defect (a variant whose setup sets
  `:v` to 1 while its assertion expects 2) must be **detected by both
  products**: Story returns `:fail` with actual and expected on the record,
  the reference shows an expected/received failure and exits non-zero. The
  comparative outcome is left open, because a control must not preselect
  Story's authoring model. If either side misses the plant, the probe is
  broken, not the product. B1 is still scored as a row.

## 5. The protocol

Read-only on the tracked tree. `<scratch>` is any directory outside the
repository. Record every command beside its captured exit code, and label
every measurement with its evidence strength (§3.3).

1. **Pin.** Record `git rev-parse HEAD`, `date "+%Y-%m-%d %H:%M:%S %Z"`, and
   the reference's current versions (`npm view storybook version`,
   `npm view @storybook/addon-mcp version`,
   `npm view @storybook/addon-vitest version`). If an invalidation in §6 has
   fired since the last reading, every row it names is re-scored.

2. **First hour, verbatim** (A1, A2). In `<scratch>`, create a fresh
   shadow-cljs app beside a re-frame2 clone (the install page states that
   assumption at [`docs/story/index.md:94`](../../../docs/story/index.md)).
   Follow [`docs/story/index.md`](../../../docs/story/index.md) and
   [`docs/story/01-first-variant.md`](../../../docs/story/01-first-variant.md)
   literally, supplying only what a page is silent about and recording each
   such supply. Log every stall as a row: what failed, the page line that
   caused it, what the reader had to do. Count ceremony. Stop when the page
   compiles as written and `#/stories` renders the first variant. For the
   reference: `npm create vite@latest`, then
   `npx storybook@latest init --yes --no-dev` timed, then `npm run storybook`.
   Timings taken in different cache states rank nothing; the stall and
   ceremony counts are what carry across runs.

3. **The UI walk** (A3–A7, B4–B7, C4). From `implementation/`, run
   `npx shadow-cljs watch :examples/login-form` and open
   `http://localhost:8043/index.html#/stories` in a browser or in headless
   Playwright. Close the first-run help with Escape, and select a variant
   before looking for `role="tab"`: an earlier walk that queried the tab strip
   with no variant selected found no tabs. Record each check as pass or fail
   with a DOM excerpt or a screenshot:
   - `/idle` shows schema-derived controls, with inline validation for an
     invalid value (A3).
   - The auto grid's sidebar header counts the cells it renders: five (A4).
   - The Docs tab renders the story rollup (A5); the Share dialog's URL
     reproduces the selected variant and says what it does not carry (A6).
   - The Explain panel's ARGS and EFFECTIVE ARGS show the story's heading (C4).
   - A deliberately failing variant's Tests-pane row renders a link to its
     Evidence beat (B5; see rf2-v5p6l).
   - The upgrade dialog's snippet pastes and compiles without the pin (B4).
   - Test-mode promotion: a login-form variant, whose `:script` dispatches
     nothing, and a variant whose `:script` dispatches (register one from the
     REPL; the testbed has none) each promote to a child that fails for the
     source's reason, passes after the fix and fails when the fault returns
     (B6).
   - The computed `color` of the subject's heading against its own
     background (A7; the canvas colour policy is rf2-w72ij).
   - The a11y panel's violations **and** incomplete counts (step 6).

4. **The JVM probes** (B2, B4, B6, C4). The scripts and their expected output
   live in [`findings/parity-probes/`](findings/parity-probes/). From
   `tools/story`, for each probe:

   ```sh
   clojure -Sdeps '{:deps {day8/re-frame2-epoch {:local/root "../../implementation/epoch"}}}' \
     -M -i spec/findings/parity-probes/probe7.clj
   ```

   and compare stdout with `probe7.expected.txt` beside the script. On
   Windows add `-J-Dstdout.encoding=UTF-8` after `clojure`: the default
   stdout there is cp1252, so the `…` and `—` in two probe labels differ
   byte-wise while the text is identical.

   | Probe | What it exercises | Rows | A red reading |
   |---|---|---|---|
   | `probe7.clj` | `story/explain` of a variant whose story carries `:args` returns them in `:args` and `:effective-args`, and a variant's own args win; `story/run` twice carries `:plan-hash` and `:run-hash`, both stable. | C4, B2 | A missing arg or hash, or a hash that moves between identical runs. |
   | `probe5b.clj` | `upgrade-snippet` for a `:sub-overrides` parent targeting `:real-setup` reads back as one form, and the compiled child's `:fidelity` is `#{:real-setup}`. | B4 | A read failure, or `:sub-overrides` in the child's fidelity. |
   | `probe6c.clj` | The promotion journey (fault, promote, fix, refault) on both routes — dialog: `ui.promotion/result->artifact` over `play/variant-play-events`; API: `determinism/->artifact` of `plan/variant-plan` — for four source shapes. The wanted triple is `[[:fail 1] [:pass 1] [:fail 1]]`. | B6 | Any other triple, on either route. |
   | `probe6d.clj` | The dialog route on setup-bearing shapes. P6d-5, setup plus `[:assert …]` checkpoints, yields `variant-play-events=[]`, so the dialog captures the source's stepped program (`promotion/source-program`) and `result->artifact` is not nil; every shape reads the wanted triple on both routes. | B6 | Any other triple, or `result->artifact nil? true` on P6d-5: the dialog has lost the dispatch-free capture. |

   Three readings of the probes' own text: `probe5b`'s last line prints
   `db :v=nil` because it reads `[:db :v]` where a run result carries
   `:app-db`, so that line proves nothing and is kept as it ran. `probe6c`
   labels P6c-4 "not in the merged tests", but that shape and P6d-5's are now
   pinned on both routes in
   `tools/story/test/re_frame/story/promotion_cljs_test.cljc`. And `probe6d`
   labels P6d-6 "TUTORIAL shape", but the first variant in chapter 01
   ([`docs/story/01-first-variant.md:28`](../../../docs/story/01-first-variant.md))
   now has P6d-5's shape, which the dialog captures.

5. **The story-mcp stdio loop** (A5, C1, C2). Write the prelude to
   `<scratch>/prelude.clj`:

   ```clojure
   (require '[re-frame.core :as rf]
            '[re-frame.frame :as rf.frame]
            '[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            '[re-frame.story :as rf.story])
   (rf/init! rf.substrate.plain-atom/adapter)
   (rf.story/install-canonical-vocabulary!)
   (rf.frame/ensure-default-frame!)
   (rf/reg-event :agent/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
   (rf.story/reg-story* :story.agent {:doc "An app slice the agent is asked to fix."})
   (rf.story/reg-variant* :story.agent/broken {:doc        "expects v=2 but setup sets 1"
                                               :setup      [[:agent/set 1]]
                                               :assertions [[:rf.assert/path-equals [:v] 2]]
                                               :tags       #{:dev :test}})
   ```

   and the requests to `<scratch>/requests.jsonl`:

   ```json
   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"parity","version":"0"}}}
   {"jsonrpc":"2.0","method":"notifications/initialized"}
   {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run-variant","arguments":{"variant-id":":story.agent/broken","dedup":false}}}
   {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read-failures","arguments":{"variant-id":":story.agent/broken"}}}
   {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"register-variant","arguments":{"variant-id":":story.agent/broken","body":"{:doc \"setup corrected in place\" :setup [[:agent/set 2]] :assertions [[:rf.assert/path-equals [:v] 2]] :tags #{:dev :test}}"}}}
   {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"run-variant","arguments":{"variant-id":":story.agent/broken","dedup":false}}}
   {"jsonrpc":"2.0","id":7,"method":"shutdown","params":{}}
   ```

   Then, from `tools/story-mcp`:

   ```sh
   clojure -M -e '(load-file "<scratch>/prelude.clj")' \
     -m re-frame.story-mcp.server --allow-writes < <scratch>/requests.jsonl
   ```

   Expected: exit 0; `tools/list` returns 19 tools; request 3 returns
   `:status :fail` carrying `:plan-hash` and `:run-hash`; request 4 returns
   `:status :fail` with `:total 1`; request 5 returns `:registered? true`;
   request 6 returns `:status :pass`. The loop proves transport and same-id
   re-registration over the unified result. It does not prove a
   requirement-preserving application repair, because the "fix" edits the
   variant rather than the app. A variant living in a browser heap is out of
   this server's reach; that host is re-frame2-pair
   ([`docs/story/09-multi-substrate-and-agent-loop.md`](../../../docs/story/09-multi-substrate-and-agent-loop.md)
   §Two hosts).

6. **The a11y rule** (B7). Beside "no violations", report the INCOMPLETE
   count, and never let a clean bill absorb incomplete findings. At the
   research pin the login card read 0 violations and 8 incomplete
   `color-contrast` nodes.

7. **Score and report.** Check the controls first (§4); if either fails,
   stop. Fill §7 by §3's rules. Lead with six categorical buckets, because
   one adoption-blocker is not offset by three matches: adoption-blocking for
   a new user; adoption-blocking for the surpass thesis; ahead because of
   re-frame2; match with a different shape; behind or unpaid; unknown. Then
   re-read the tiers in `018` §3.1 and move one only on evidence from this
   run. The weighted number is Appendix A, never the finding.

**Instrument traps met by earlier readings.** Port 8043 answers
`/index.html`, not `/`. A `reg-fx` handler is 2-arity (`(fn [ctx args])`); a
1-arity one throws, and the run then fails honestly, which is a probe error
and not Story's. `story/make-run-artifact` builds an artifact from parts, not
from a run result: handed a result, it yields an empty program. A role
selector run against an unmounted tab strip finds no roles.

## 6. Invalidation

A reading is stale, and the rows named are re-scored, when any of these
happens:

- a Storybook major lands, or its MCP or Test addon leaves preview (all rows);
- a Story UI epic lands (A3–A7, B5–B7, C3, C4);
- `docs/story/01-first-variant.md` is rewritten (A1, A2);
- a P1 honesty failure is found on the run or test path (B1, B2, B5);
- `re-frame.story.promotion` (`promotion.cljc`), `ui/view_state.cljc`,
  `play.cljc`'s `variant-play-events` or `ui/sidebar.cljs`'s
  `workspace-grid-grouping` changes (B6, B4, A4).

## 7. The current reading

Every scored cell was measured at trunk `98e8ffe9cb` (2026-09-14). The JVM
probes and the story-mcp loop were re-executed at `911c2fed80`, where the Story
paths are byte-identical to those the research re-executed on. No row has been
re-walked in a browser or a clean consumer since the pin, so **no score moves
here** except B6's, re-scored in its row from JVM probes 6c and 6d re-executed
at `2284727767`, after the Test-mode capture fix; its browser gesture is not
re-walked. To be re-run by rf2-4gijz.

**Categorical reading.** Adoption-blocking for a new user: A1 (no scaffolder;
the repaired page unwalked) and A3 (unwalked). Adoption-blocking for the
surpass thesis: B5's evidence gesture and B4's fragment pins. Ahead because of
re-frame2: A4, B1, B4, B5 in data, B6, C2, C4. Match with a different shape:
A2, A5, B2, B3, C1, X1, X3. Behind
or unpaid: A1, A3, A7, B7, X2, C3. Unknown: a11y beyond one run, navigation at
catalogue scale, determinism runs, the recorder as a capture path,
`story:build` outside this repository (answered: exercised once in a scratch
consumer, X2), and every comparative ergonomic claim (§9).

Answered since the pin: `story/explain` lists no decorators, global or
variant-level; `story/variant-plan` carries the resolved stack at
`[:world :decorators]`, and Docs mode's Decorators table reads it in the same
order (JVM probe at `2284727767`, PR #9822; re-run at `e4d07929c9`).

| Job | w | Pin S / B | Pin status | Trunk status | Bead |
|---|---|---|---|---|---|
| A1 | 3 | 0.5 / 2 | GAP | Install page fixed (PR #9797), README and chapter 01 aligned (PR #9807); source-verified only, no clean-consumer walk. | rf2-1bkoc (scaffolder, OPEN) |
| A2 | 3 | 2 / 2 | MATCH | Unchanged; not re-walked. | — |
| A3 | 2 | 1 / 2 | UNDERMARKETED | The login views carry a props schema (PR #9800); source-verified only. | — |
| A4 | 2 | 2 / 1 | WIN | The grid header counts rendered cells (PR #9795); source-verified only. | — |
| A5 | 2 | 2 / 2 | MATCH | Unchanged. | — |
| A6 | 1 | 2 / 1.5 | WIN | Unchanged. | — |
| A7 | 1 | 0.5 / 1.5 | GAP | Chapter 02 says what a frame does not isolate (PR #9801); chrome no longer leaks text styles or its backdrop into the subject; document isolation otherwise unchanged (CSS rules, focus, portals, timers still shared), and the canvas is still a stamped `div` in one page; recorded, not to be built. | rf2-w72ij (ruled B) |
| B1 | 3 | 2 / 1 | WIN | Unchanged; the detection control held on both sides. | — |
| B2 | 3 | 2 / 2 | MATCH | `:plan-hash` and `:run-hash` attached (PR #9796); re-executed by probe 7 and the MCP loop. | — |
| B3 | 2 | 2 / 2 | MATCH | Unchanged. | — |
| B4 | 2 | 2 / 0.5 | WIN | The upgrade snippet parses and drops a single parent's pin (PRs #9803, #9810); re-executed by probe 5b; pins composed from fragments survive. | rf2-yt6ak (held) |
| B5 | 3 | 1.5 / 1 | WIN | The Tests-pane row became a button (PR #9795), but its link never renders for a real failed assertion; the gesture is unpaid. | rf2-v5p6l (held) |
| B6 | 1 | 1 / 0 | WIN | Promotion carries the source's expectations (PR #9804), and the Test-mode dialog captures a variant whose `:script` dispatches nothing (PR #9819). Re-scored at `2284727767` from probes 6c and 6d, re-executed: every probed shape reads fail / pass / fail on both routes, so **S 1.5 / B 0, WIN**. Not 2, for one caveat: checks a source composes through `:compose` are dropped on both routes. The browser dialog is not re-walked. | rf2-6h2z3 (held) |
| B7 | 2 | 1 / 1.5 | GAP / TARGET | The a11y panel and `read-a11y-violations` now report axe-core's incomplete checks beside violations (login-form `/idle`: 0 violations and 1 incomplete `color-contrast` rule on 5 nodes, panel and a direct `axe.run` agree); the visual assertion still compares the identity key, not pixels ([`017`](017-Testing-Story.md#visual-a11y-and-browser-checks)). | rf2-ia2if (experiment) |
| C1 | 2 | 2 / 1.5 | MATCH | Re-executed by the MCP loop (19 tools). | — |
| C2 | 3 | 2 / 1 | WIN | The skill leaf was corrected (PR #9798); re-executed by the MCP loop. | — |
| C3 | 1 | 1 / 1 | DIVERGENT | Chapter 09 names the two hosts (PR #9801); source-verified only. | rf2-szjjx (host rule, OPEN) |
| C4 | 2 | 1.5 / 0 | WIN | `explain` folds story-level args (PR #9799); re-executed by probe 7; the panel not re-walked. | — |
| X1 | 1 | 2 / 2 | MATCH | Global decorators taught in chapter 07 as plan data (PR #9822); `story/explain` and the Explain panel do not list the decorator stack (JVM probe at `2284727767`, re-run at `e4d07929c9`), so the opacity bar is paid in the plan and in Docs mode, not in Explain. | — |
| X2 | 1 | 1 / 2 | GAP | Consumer recipe documented (chapter 08 §Static builds; 013 §Downstream pattern) and exercised once in a scratch consumer outside this repository: release build, headless catalogue and deep link checked. Not re-scored. | rf2-0vwg7 |
| X3 | 1 | 1 / 2 | DIVERGENT | Unchanged. | — |
| X4 | — | — | OUT | — | — |

## 8. The comparison set

Versions and read dates as recorded by the research (read 2026-09-14). A
re-run re-reads them at step 1.

| Tool | Version | Where it sits |
|---|---|---|
| Storybook | 10.6.0, released 2026-09-02 (v11 in prerelease) | The primary comparator, in its practical workflow: `init` installs `@storybook/addon-vitest` (which declares Vitest 3 or 4), `@storybook/addon-a11y`, `@storybook/addon-docs`, `@storybook/addon-mcp` (preview) and `chromatic`; MSW joins where the network is the job. |
| Chromatic | public pricing: 5,000 free snapshots, paid tiers from $179 a month | Hosted visual review, paid. Compared for the review job only (B7); being a hosted service is OUT. |
| Vitest browser mode, Playwright | Vitest 5.0.0; Playwright component testing 1.63 | The local screenshot comparators (`toMatchScreenshot`, `toHaveScreenshot`) a local visual-review path would reuse (B7). |
| Histoire | 1.0.0-beta.1 (latest non-prerelease 0.17.17) | Framework-native authoring, variant grids, and copyable source that follows the controls. |
| Ladle | 5.1.1 | A small React workshop that documents a compact Playwright capture-and-compare recipe. |
| React Cosmos | 7.4.1 | Fixtures, decorators and a static export. |
| Lookbook | 2.3.15 | Rails previews, where the docs are the workshop. |
| Widgetbook | 4.0 beta (stable 3.25) | A design reference only: generated, typed story declarations and args. Not a comparator. |
| Portfolio | 2026.03.1 | The ClojureScript lineage: concise scenes and component grids. |
| devcards | dormant since 2020 | The ClojureScript lineage: simultaneous states and enduring examples. |

## 9. What this test does not measure

- Performance budgets: `018` §10.1 carries them and its own gate.
- The shell's visual quality.
- Ecosystem size, hiring pool and hosted-service economics (X4, OUT).
- **Comparative ergonomic advantage.** Measured 2026-09-14 by rf2-a1v8a on
  Story and on Storybook 10.6 against one planted defect (the login submit
  drops the email); the tallies, transcripts and controls are in
  [`findings/parity-2026-09/fable/advantage-measured-2026-09-14.md`](findings/parity-2026-09/fable/advantage-measured-2026-09-14.md).
  No journey is an ergonomic win yet, so the surplus
  [§7](#7-the-current-reading) reports is still structural:
  - Explore, retain, strengthen: both caught the defect; Story took 26
    gestures, 4 source edits and 4 page reloads against 8, 1 and 1.
  - Failure to cause to regression: Story's Machines panel shows the failing
    action and the data it produced without opening the source, but reaching
    it took 5 gestures and 2 wrong turns against 3.
  - Human-named state to agent to verified revision: Storybook closed the loop
    through its MCP addon in 7 steps; on Story it could not run from the
    measuring session (the testbed is CLJS-only and no browser transport was
    registered).

## Appendix A. The number

The categorical reading in §7 is the finding. The number exists so the rubric
can be re-run and a second reader can check the arithmetic rather than
re-argue it; no trunk figure is offered, because no row has been re-walked.

Over the n = 21 scored rows (X4 excluded), with Σw = 41: unweighted is
Σ S / (2 · n); weighted is Σ w·S / (2 · Σw); the parity ratio is
(Σ w·S) / (Σ w·B).

At the pin, Story reads 65.5 / 82 = **0.80** weighted and the reference
58 / 82 = **0.71**: a ratio of **1.13** (doorstep, A1–A7: 0.71 against 0.89;
substance, the B, C and X rows: 0.84 against 0.61). Two corrections, each
independent of the other, bring it to **1.00**:

- **Reference-generous bound.** More of Story was executed than of the
  reference. Give every reference cell whose evidence is documented-only and
  whose score is below 2 the half point an exercised run might earn (A4, A6,
  A7, B4, B6, B7, C1, C2, C3): the reference reads 65.5 / 82 = 0.80, a ratio
  of 1.00. Against Story's pessimistic reading (each documented-only Story
  cell loses 0.5, each unverified cell drops to 0: 64 / 82 = 0.78) it is
  0.98.
- **Collapse.** B1, B2, B4, B5 and C2 are five views of one design decision:
  the variant is a plan and the result is a tape. Collapsed into one weight-3
  WIN row (S 2, B 1), Story reads 45 / 60 = 0.75 against the reference's
  45 / 60 = 0.75, a ratio of 1.00. A reader who rejects that design decision
  should read the ratio this way.
