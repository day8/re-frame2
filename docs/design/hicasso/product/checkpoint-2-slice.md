# Checkpoint 2 — the slice review record

**Verdict: the Phase 2 exit is MET, and the ordinary authoring facade is frozen** —
[`facade-freeze.md`](facade-freeze.md) — **with one law it could not apply.**

Checkpoint 2 (`rf2-hic-026`) ran on 2026-08-13 in a worktree of its own, by a
reviewer who wrote none of the artefacts it reviews. All six of its dependencies had landed, and so
had the two authoring reports its ruling reads. It was first measured at `main`@`5d54f50d76` and
**re-measured whole at `f96aaf8d73`**, after this record was rebased past eleven concurrent landings
— including, at the end, the fixes for three of its own five findings. [§3](#3-what-was-re-run-and-what-it-measures)
is the final base throughout. The closure re-runs in [§4.2](#42-what-happened-next-and-the-closure-re-run)
carry their own commit, because a sabotage is only meaningful against the tree it was planted in.

Unlike [Checkpoint 1](checkpoint-1-kernel.md), whose exit clause is a conjunction with an open second
conjunct, **Phase 2's exit is a conjunction whose conjuncts both hold on measurement**:

> *"Exit when the application code contains no artificial boundaries or internal imports and its core
> behavior is readable without browser-test ceremony. Freeze the ordinary authoring facade from this
> evidence."* — [`specification.md` §12](specification.md#12-action-programme)

Both were measured here ([§1](#1-the-exit-conjunct-by-conjunct)), and the third sentence is an act
rather than a condition, so this checkpoint takes it.

**Five misses were filed** ([§5](#5-the-misses)). One of them is a `correctness` row and it deserves
its headline: **the slice's reset-law row stayed green when the mechanism it named was deleted**. That
did not indict the runtime — the controlled-input law is witnessed on three engines and was re-run
green by Checkpoint 1 — and it did not reach either exit conjunct. What it meant was that the Phase 2
evidence did not support the statement both authoring reports made about `::h/revision`.

**That row is already `closed`.** `rf2-36bd`'s fix landed as PR #8086 while this record was being
rebased, and it took the second of the bead's two acceptance clauses: the inert bookkeeping came out
of the slice and finding 5 was withdrawn in both reports with the reason. This checkpoint re-ran its
own producing section against the landed fix — it wrote none of it — and the evidence is in
[§4.2](#42-what-happened-next-and-the-closure-re-run) and in the
[ledger row](correction-ledger.md#the-ledger). **[`facade-freeze.md`](facade-freeze.md)'s membership
moved with it**: the slice no longer reaches `::h/revision`, so the ordinary surface is nine names,
**two** prop markers and one framework-minted event id.

## Where each fact lives

| Fact | Owner |
|---|---|
| What Phase 2 must deliver, and the words its exit is written in | [`specification.md` §12](specification.md#12-action-programme) |
| What the surface was like to use, from two independent applications | [`authoring-report-slice.md`](authoring-report-slice.md), [`authoring-report-todo.md`](authoring-report-todo.md) |
| The review protocol this record discharges — §1 Completeness, §2 Correctness, §3 Quality | `rf2-hic-026` |
| What the ordinary surface **is**, and which laws are frozen | [`facade-freeze.md`](facade-freeze.md) |
| Every spelling this checkpoint met | [`naming-findings-cp2.md`](naming-findings-cp2.md) |
| What each miss is, its severity, and what it takes to close it | [`correction-ledger.md`](correction-ledger.md), and the bead each row names |
| Whether the Phase 2 exit is met | this file |

One owner per fact. Bare `§1`, `§2` and `§3` mean the review protocol's three sections; specification
sections are always written as `specification.md §N`. Findings are **not** restated here: their text
lives in the ledger row and their full detail in the bead.

## 1. The exit, conjunct by conjunct

### 1.1 No artificial boundaries or internal imports

**Met, mechanically.** Neither application's import claim is asserted by reading `ns` forms. Both read
the ClojureScript analyzer's own dependency graph — `:requires`, `:require-macros`, `:uses`,
`:use-macros`, unioned — through one shared macro (`examples/require_graph.clj`), and each pins both a
predicate fence (no `impl`, no bench tree, no tool, no test kit) and an exact roster of the foreign
doors it may name. The slice's roster is four doors; the Todo class's is five, and the fifth is
`clojure.string`.

Both files carry a real sabotage row: each fence predicate is shown one name it must catch and two it
must not — including `re-frame.hicasso` itself, because a fence that swallowed the public door would
fail every application rather than protect one. Both ran green here.

The **boundaries** half was read rather than measured, because no instrument decides *artificial*. The
slice's thirteen `defview`s were read in full: the four block renderers exist because a head is a
value and the kind chooses one at render time (`specification.md` §3.3's claim, in one line);
`digest-body` is split from `digest` because the error region has to sit between them; `pager` and
`digest` are children of `feed-page` so that page's read set does not grow when pagination does.
None is a boundary put there to make a test possible.

**The caveat, and it is the population rather than the predicate**: both fences walk a hand-written
list of namespaces, written twice in each file, so an application namespace added tomorrow escapes the
fence entirely. Filed as `rf2-ccuw`. The claim is true today for the thirteen files that exist.

### 1.2 Core behaviour readable without browser-test ceremony

**Met.** The slice carries 24 `deftest`s at L0 and 25 at L2, and the Todo class its own pair; all of
them run on the **node** lane, with no DOM, no browser and no harness. What lives in the browser lane
is what needs a browser: real clicks, real focus, real element identity across a re-render.

The sharpest form of it is that the two tiers answer different questions rather than the same question
twice. `a-blocks-KIND-chooses-the-body-that-renders-it` and `one-page-renders-no-pager-and-reads-no-string`
are structural facts about a returned tree; `a-real-click-on-a-route-link-changes-the-page` is not.
No row in either L0 or L2 needed a mounted page to be written.

### 1.3 The freeze

Taken — [`facade-freeze.md`](facade-freeze.md). Nine names, two prop markers and one
framework-minted event id, fourteen laws transcribed from the pre-resolved sources, thirteen applied
and **one that could not be** (`rf2-j6fn`, [§5](#5-the-misses)). No name is frozen or changed; every
spelling question is in [`naming-findings-cp2.md`](naming-findings-cp2.md).

## 2. The three protocol sections

### 2.1 §1 Completeness

Every Phase 2 deliverable is on `main`, and each was checked at its own artefact rather than at its
bead's close note:

| Deliverable | Where it landed | Checked here by |
|---|---|---|
| The application flow — routing, keyed list, edit, async mutation, controlled fields, errors, reset | `examples/slice/*`, 7 namespaces + 8 suites | reading all seven; every row of the seven is present, and pagination, runtime-selected content and a nested error region were added later by `rf2-hic-074` |
| L0–L3 testing facade | `test_kit/src/re_frame/hicasso/test/{core,mounted}.cljs` | `mounted.cljs` read in full — 12 public doors, 1998 lines |
| First versioned evidence projection | `impl/evidence.cljs` | present, adapter-neutral, owned by `rf2-hic-023` |
| Xray mounted / read / intent / explain-render views | `tools/xray/src/day8/re_frame2_xray/panels/hicasso.cljs` | four view fns and the four-way dispatch at `:555-558`, under the `:hicasso` L4 tab |
| The complaint catalogue | [`complaints.md`](complaints.md) + `check_complaint_catalogue.py` | gate run: 74 live, 6 reserved, 1 pending retirement, 1 retired; every live row emitted and rowed in Spec 009, every anchor resolving |
| First bounded clj-kondo checks | `hicasso/resources/clj-kondo.exports/day8/re-frame2-hicasso/` | gate run: 6 checks fire on their fixtures, correct code silent, the artefact's own testbeds quiet |
| Production-erasure proof | `hicasso/scripts/check_production_erasure.cjs`, chained into `build:hicasso-release` | build run: 5 sentinels absent, 3 positive controls present — and the chaining re-read in `package.json`, so the bundle cannot be produced unchecked |

The second witness application (`rf2-hic-086`, the Todo class) is not a Phase 2 deliverable and is the
reason the freeze reads two applications rather than one.

### 2.2 §2 Correctness

A clean-checkout run of both lanes, then a sabotage per testing tier.
[§3](#3-what-was-re-run-and-what-it-measures) is the runs; [§4](#4-one-sabotage-per-testing-tier) is
the sabotages, and one of the three did not bite.

### 2.3 §3 Quality

**The application reads like consumer code.** Every handler is
`(fn [coeffects event-v] → effect-map)`; the events namespace requires `re-frame.core` and the
application's own `db` and does not know a view substrate exists; no view dispatches or subscribes
directly, because an intent is a vector and a read is `h/sub`; the async mutation is a `reg-fx` a real
application would replace with `day8/re-frame2-http` without changing anything above it; the reply
carries its subject and the handler drops a stale one. A re-frame2 user would recognise all of it.

**Every rough edge in both authoring reports is dispositioned**, none silently dropped —
[§6](#6-the-authoring-reports-rough-edges-dispositioned).

## 3. What was re-run, and what it measures

From a worktree of `origin/main`@`f96aaf8d73`. Exit codes are the values captured to file, not
the harness's report.

| Suite | Result | Captured exit |
|---|---|---|
| `compile-node-test.cjs node-test-hicasso` | 451 files, 0 warnings | **0** |
| `node out/node-test-hicasso.js` | **1129 tests, 4611 assertions, 0 failures, 0 errors** | **0** |
| `npm run test:browser` | **1475 tests, 9159 assertions, 0 failures, 0 errors** | **0** |
| `npm run test:hicasso-invariants` | freeze 1 row; motion / overlay / native / forms unreachable from the door; 74 live complaints; budget ledger 38 rows — 21 MET, 5 BREACH, 2 UNRESOLVED, 10 UNPINNED | **0** |
| `npm run test:hicasso-lint` | 6 checks fire on their fixtures, correct code silent, testbeds quiet | **0** |
| `npm run build:hicasso-release` | erasure: 5 sentinels absent, 3 positive controls present. Isolation: 4 absent, 4 present | **0** |

`npm run test:hicasso-hmr` was **not** run: it binds `:dev-http` 8061 and this machine had concurrent
workers. Checkpoint 1 ran it green two days ago and nothing in this checkpoint's scope touches it.

The budget-ledger line is quoted for completeness and is **not** a pass: seventeen of its
thirty-eight rows are not MET, and the gate's own closing sentence says so. It is a verdict about the
record.

### 3.1 The two lanes measure different halves, and both were needed

`:node-test-hicasso`'s `ns-regexp` matches `-dom-cljs-test`, so the DOM namespaces compile into the
node lane and are counted in the 1129 — and in that lane every DOM claim degrades to a stated skip.
Both witness applications' flow suites open with `(if-not (browser?) (skip! ":node-test has no React
DOM") …)`. A green assertion whose reason is *there is no DOM here* answers no question about the DOM,
so it is the 1475 that speaks for every mounted row quoted below.

## 4. One sabotage per testing tier

§2 asks for one per tier, independently re-run. Two bit. **The third did not**, which is
[§4.1](#41-the-l3-plant-and-why-green-was-the-finding); it has since been repaired and re-tested,
which is [§4.2](#42-what-happened-next-and-the-closure-re-run).

| Tier | The plant | Result |
|---|---|---|
| **L0** | the revision bump deleted from `::discard` | **RED.** `FAIL in (discarding-restores-the-article-and-bumps-the-revision) (l0_cljs_test.cljs:230:11)` |
| **L2** | `slice/views.cljs:113` — `(when-not published? …)` inverted to `(when published? …)` | **RED.** two assertions, `FAIL in (a-published-row-carries-no-draft-badge) (l2_cljs_test.cljs:77:7, :78:7)` |
| **L3** | the same revision-bump deletion, against the browser lane | **GREEN. 1474 tests, 9154 assertions, 0 failures, 0 errors, captured exit 0.** |
| **L3, after the repair** | `slice/events.cljs:121` — the draft `dissoc` deleted from the repaired `::discard` | **RED, captured exit 1.** Four assertions across both reset rows |

The two node-lane plants were run together in one lane pass — they redden rows with different names,
so attribution is unambiguous — and that pass captured **run exit 1** with exactly three failures and
nothing else. Every plant was reverted with `git checkout --`; `git diff` under `implementation/`
is empty and no source change is carried by this record's PR.

**Rows 1 and 3 cannot be reproduced on today's `main`, and that is the repair rather than a defect in
this record.** The code they plant into no longer exists: `rf2-36bd`'s fix removed the revision
bookkeeping from the slice entirely. Both were taken at `5d54f50d76` and again at `e4857cb69b`, with
identical results, before the fix landed. Row 4 is the same sabotage re-aimed at what replaced it, and
it is reproducible today.

### 4.1 The L3 plant, and why green was the finding

The row that should have reddened is `slice/flow_dom_cljs_test.cljs:381`,
`discarding-re-baselines-the-field-without-remounting-it`, whose own failure message names the
mechanism the plant removed:

```text
THE RESET LAW (HD-019): the model moved back to a value the field was already
showing, so React alone sees nothing to do — the changed `::h/revision` is what
re-baselines it
```

The build recompiled ten files, so the plant was in the bundle. The row stays green with the changed
`::h/revision` removed, which means it witnesses something else and cannot tell that something from
the revision.

**The reason is at source and the corpus already knows it.** `::subs/draft` falls back to the article
(`slice/subs.cljs:126-129` → `slice/db.cljs:183-191`), and `::edit` writes every keystroke into the
draft, so React's last rendered `:value` is the typed text and the discard moves the model to a
*different* string. React updates the DOM on its own. The slice's editor is an **accepting** field,
and `implementation/hicasso/testbed/hicasso_testbed/core.cljs:32` recorded exactly this against its
own accepting `revision` arm after the #7815 audit — which is why the testbed also carries a
`revision-strict` arm, a **rejecting** field whose model can diverge from the DOM. The slice
reproduced the shape that audit had already indicted.

Filed as `rf2-36bd`. What it costs beyond the row is that both authoring reports state, as findings,
when `::h/revision` is needed, and neither statement survives the measurement — the slice's *"does not
work without bookkeeping"* and the Todo report's bounding *"the reset door for a field that outlives
its reset"*. Both reports ask for a guide row teaching authors *you will need a counter*; written from
either as it stands, that row teaches a counter to the population that does not need one.

**The runtime is not indicted.** The reset law itself is witnessed by `test:hicasso-controlled` — 97
checks × 13 sections × three engines — re-run green by Checkpoint 1 on 2026-08-13.

### 4.2 What happened next, and the closure re-run

`rf2-36bd`'s fix landed as **PR #8086** while this record was being rebased, and it took the second of
the bead's two acceptance clauses rather than the first: no ordinary application shape makes the
revision load-bearing, so **the bookkeeping came out** — the `:revision` key, `db/revision`, both
`(fnil inc 0)`s, `::subs/revision` and both `::h/revision` props — and finding 5 was withdrawn in both
authoring reports with the reason.

This checkpoint was still running and is the ledger keeper for its own rows, so it re-ran §2's L3
sabotage against the landed fix. It wrote none of that fix.

| Re-run, at `main`@`7ae053c73d` | Result | Captured exit |
|---|---|---|
| `node out/node-test-hicasso.js` | 1129 tests, 4611 assertions, 0 failures | **0** |
| `npm run test:browser` | 1475 tests, 9158 assertions, 0 failures | **0** |
| the replacement L3 rows, with the draft `dissoc` deleted from `::discard` | **RED** — `discarding-moves-the-model-back-without-remounting-the-field` at `:411`, `:419`, `:420` and `a-discard-repairs-a-field-the-model-never-agreed-to` at `:454` | **1** |

The green pair is half the evidence and the weaker half: it shows the suites pass without the
bookkeeping, which is what *inert* means. **The red is the half that matters** — the replacement rows
redden when the one move the repaired `::discard` still makes is taken away, so the reset section now
witnesses something rather than nothing, which is exactly what the original row could not do.

The fix also states the mechanism, which the finding could only bound: `impl.codec`'s `revision-key`
delivers a revision by *re-running the body*, and the re-run's commit re-asserts the model over the
DOM — so any re-render of the boundary does the same work for free, and a discard that moves three of
the editor's reads has already done it. `::h/revision`'s population is therefore **a reset that leaves
every other read the body makes `=`**, which is neither report's original statement and is now in
[`facade-freeze.md` §5](facade-freeze.md#5-what-is-not-in-the-ordinary-surface).

**One thing the fix opened and did not close**, recorded here because it is this section's business:
the corrective worker found that `examples/editor`'s `what-the-revision-bump-is-actually-load-bearing-FOR`
presents itself as the measured counterpart to this finding and **stayed green on a deleted bump too**.
That is `rf2-5h9k`, it is outside Phase 2's witnesses, and the slice report now carries a caution
against writing the guide row from it until it is settled.

## 5. The misses

| bd id | Protocol section | Severity | One line |
|---|---|---|---|
| `rf2-36bd` | §2 Correctness | correctness | The slice's reset-law row stayed green with `::h/revision`'s trigger deleted; both reports' finding 5 named the wrong population. **Fixed and `closed`** — PR #8086, re-run at [§4.2](#42-what-happened-next-and-the-closure-re-run) |
| `rf2-jljf` | §1 Completeness | coverage | The L3 facade has no door for router-enqueued work, both reports assigned the remedy to a bead closed before they were written, and `settle!`'s docstring over-promised. **Fixed and `closed`** — PR #8088; the door itself is now `rf2-6m4w`, open |
| `rf2-j6fn` | §3 Quality | coverage | `:on-submit` auto-prevents where `specification.md` §4.1 and `ergonomics-api.md` both forbid a submit-only case, so the freeze cannot apply that law |
| `rf2-lu0s` | §3 Quality | quality | The door never stated the four event-value shapes; the key map was invisible unless an author opened the namespace the door tells them not to. **Fixed and `closed`** — PR #8088 |
| `rf2-ccuw` | §2 Correctness | coverage | Both witnesses' import fence walks a hand-written namespace roster, so a new application namespace escapes it entirely |

`rf2-jljf` and `rf2-j6fn` are the same species as Checkpoint 1's `rf2-zk87`: an obligation with
nobody attached. `rf2-jljf`'s owner was a **closed** bead — `rf2-hic-027`, closed 2026-08-10, named as
the remedy's home by two reports both written on 2026-08-11; it now has an open one, `rf2-6m4w`.
`rf2-j6fn`'s is a published normative sentence only the operator can move.

**`rf2-jljf` carried a premise that did not hold, and its fix corrected it.** The bead said both
witnesses *reach past* the L3 facade to `re-frame.test-support/poll-until`, which implies an
unsupported reach-around. It is not one: `poll-until` is a sibling **public** door on core's published
`:paths`, with its own Spec 008 audience-split section, and **fourteen** files under
`implementation/hicasso/` already use it rather than the two this checkpoint counted. That makes the
second-caller evidence stronger than either report states and the *authors have nowhere to go* half
weaker — what is missing is facade **vocabulary**, not capability. It is recorded here rather than
quietly absorbed, because a checkpoint that files a finding on a wrong premise owes the correction as
plainly as it owed the finding.

Nothing in this list is a defect in the shipped runtime. Four of the five are the governance and
evidence layers describing the runtime inaccurately, which is the failure class this programme treats
as load-bearing; the fifth is a documentation gap on a shipped door.

**Three of the five are already `closed`.** `rf2-36bd`, `rf2-jljf` and `rf2-lu0s` were all fixed and
merged while this record was being rebased, each by a worker who had not written the artefact under
review, and this checkpoint re-ran each producing section against the landed `main` before writing its
row — it is the ledger keeper for its own rows for as long as it runs, and it wrote none of the three
fixes. The evidence is in each [ledger row](correction-ledger.md#the-ledger), and the sharpest of them
is [§4.2](#42-what-happened-next-and-the-closure-re-run).

**Two stand**, and neither is a code defect a worker can simply take: `rf2-j6fn` is an operator
decision, and `rf2-ccuw` is in flight. So this checkpoint's standing contribution to the
`rf2-hic-064` gate is two `coverage` rows.

## 6. The authoring reports' rough edges, dispositioned

`rf2-hic-026`'s §3 requires that each becomes a facade decision or a corrective bead, and that none is
silently dropped. Both reports, in their own order:

| Rough edge | Disposition |
|---|---|
| slice 1 / todo 1 — `::h/value` and the canonical event-vector shape are incompatible, silently | **Facade decision, and it is against the convention.** `materialize` and `markers?` are `mapv`/`some` over the intent's top level (`impl/intent.cljs:782-795`), for a stated cost reason: a deep walk paid on every keystroke of every controlled field. `spec/Conventions.md`'s canonical shape is a nudge with a lint, not a law, and Hicasso's positional spelling is the one that works. **No bead**: a lowering-time deep walk buys a diagnostic at the price the design refused, and a lint check for a marker below the top level is a check for one keyword in one position — nag-diagnostic territory. The redaction consequence the report names is real and is EP-0025's, not the facade's. What *is* filed is that the door does not say which shapes it accepts at all (`rf2-lu0s`), which is the larger half of the same gap. |
| slice 2 / todo 2 — `route-link` is called; everything else that makes markup is a head | **Accepted as designed.** A link is not a unit of re-render, and a boundary at each of the corpus's 106 link sites would cost two hooks apiece. The mistake is loud (`:rf.error/hicasso-function-in-head-position`, and the lint export flags it at `:error` before the build), and both reports record it as a one-time cost paid once. No bead. |
| slice 3 / todo 3 — top-level `reg-route` does not survive the supported test fixture | **Not the facade's.** `re-frame.test-support/make-reset-runtime-fixture` restores a baseline captured when the `use-fixtures` form is evaluated; this is a Spec 008 finding with two independent reports. Out of Phase 2's scope and out of this freeze's. Recorded here so it is not lost when this page is read as the disposition of record. |
| slice 4 / todo 4 — `use-subs` reads well at two reads and badly at four | **Accepted.** Consistent with the operator's standing ruling that grouped `use-subs` sits below the ergonomics bar; one grouped read between two applications. Frozen as [`facade-freeze.md`](facade-freeze.md) law 4 — the ambient collector is the default and the grouped door is the control. No bead. |
| slice 5 / todo 5 — `::h/revision` and the counter the author has to invent | **`rf2-36bd`, filed and since closed** — [§4.1](#41-the-l3-plant-and-why-green-was-the-finding) found it, [§4.2](#42-what-happened-next-and-the-closure-re-run) re-ran it against the landed fix. Both reports have withdrawn finding 5 with the reason, and the inert bookkeeping is out of the slice. |
| slice 6 / todo 6 — two clicks on one page settle differently | **`rf2-jljf`, filed and since closed**; the docstring and both attributions are repaired and the door itself is `rf2-6m4w`, an open operator decision. |
| slice 7 / todo 7 — the virtual clock and `poll-until` cannot be used together | **Accepted, and it is a consequence of slice 6 rather than a defect of its own.** The clock deliberately does not drive macrotasks and its docstring is about durations; what a mutation witness waits for is a reply, not a duration. Folded into the slice-6 finding, since a door for *let the router land* is what would dissolve it, and it is recorded in `rf2-6m4w`'s own statement of the question. No separate bead. |
| slice 8 / todo 8 — route paths are global and route ids are not | **Already filed and landed** as `rf2-wqnl`, which made the prefix a written convention for the whole bundle and put a census gate behind it. The Todo report's one-step extension — `:rf.route/not-found` is itself a process-global route id — belongs with that bead. No new bead. |
| slice 9a — `reg-sub`'s two-fn form puts a one-argument fn beside a two-argument one | **Not the facade's** (core's `reg-sub`), and both applications avoided it by using the `:<-` chain, which is what the reports recommend. Minutiae; closed rather than actioned. |
| slice 9b / todo 9c — `false` attributes are recorded and `nil` ones dropped; `.class` sugar folds into `:class` in that order | **Two lines in `ht/attrs`'s docstring.** Both reports ask for the same thing. Folded into `rf2-lu0s`'s scope? **No** — `ht` is the test kit, not the door, and `rf2-lu0s` is fenced to `re-frame.hicasso.cljc`. Closed as minutiae: both facts are discoverable in one red row costing a minute, both reports say so, and neither is a defect. |
| slice 9c — the `h/boundary` fallback can only be asserted as data | **Accepted, and correctly.** Driving it needs something to throw and testbeds hold no deliberate bugs. The slice states the limit rather than inventing a crash — and `rf2-hic-074` has since given the digest region a *real* thrown render (`list-block` refuses a payload with no items), so the limit no longer binds. No bead. |
| slice 9d — `assert-clean!`'s page-wide residue message | **Recorded as a positive.** The one place the instrument was better than the author. |
| todo N1 — the key map is why the application has no callback, and the door never mentions it | **`rf2-lu0s`, filed and since closed.** The door now carries a *FOUR shapes* section in `defview`'s docstring, naming the central composition gate. |
| todo N2 — a `reg-state` concern belongs to neither `subs` nor `events` | **Naming/convention question** — [`naming-findings-cp2.md`](naming-findings-cp2.md) C2-1. **Not filed as a bead**, because [`naming-ledger.md`](naming-ledger.md) row 3 carries a live recommendation to remove `h/reg-state` from the adaptor core, which would dissolve the question; filing relocation work against a surface whose existence is open is premature. |
| todo N3 — an event handler is on neither side of `reg-state`'s pair | **Naming/mint question** — C2-2, **held for the same reason**, and recorded with its measured non-cost: the `:dispatch`-shaped clear costs no turn, because `dispatch-sync!` drains a seed handler's `:fx` to fixed point. |
| todo N4 — a `:ref` must be a stable function and nothing on the door says so | **Accepted, and not filed.** `:ref` is React's contract and [`facade-freeze.md`](facade-freeze.md) law 5 freezes *React owns refs*. The door restating React's identity rule would be the facade taking ownership of a contract it deliberately does not own, and a lint for it would need to know what a ref closes over. The report's own hoisting example is the answer and belongs in the guide. |
| todo N5 — `:on-submit`'s auto-prevent is exactly right | **`rf2-j6fn`** — recorded by the report as a positive, and it is the one thing in either report that both pre-resolved dispositions forbid. |
| todo N6 — what the mounted tier cannot drive, stated rather than papered over | **Recorded as a positive**, and not a Hicasso finding. A synthetic `KeyboardEvent` is untrusted so implicit form submission cannot be driven; the suite calls `requestSubmit` and states the boundary in its own docstring. |

## 7. Checked, and found sound

- **Both surface tests are real controls, not assertions.** `every-fence-predicate-fires` shows each
  predicate one name it must catch **and two it must not** — `re-frame.hicasso` and `re-frame.core` —
  because a fence that swallowed the public door would fail every application rather than protect
  one. `the-graph-is-populated` closes the vacuous-pass hole first, in as many words: *"a namespace
  the analyzer has not analysed answers an empty edge set, and an empty edge set passes every check
  below VACUOUSLY"*. Four positive controls sit beside it. This is the shape a control should have,
  and [§5](#5-the-misses)'s `rf2-ccuw` is about its population and nothing else.
- **A key map at a non-keyboard prop is loud, not silent.** `ergonomics-api.md` asks that key maps be
  *restricted to keyboard event props*, and the runtime does not restrict them — `lower-prop`
  (`impl/intent.cljs:1133-1136`) dispatches on the value's **shape** at any `:on-*` prop, with a
  stated reason: no roster of blessed prop names to keep in step with the DOM. The outcome the
  restriction exists for is delivered anyway, and more generally: `key-map-handler` runs the argument
  law before it looks anything up (`:1097`), so `{:on-click {"Enter" …}}` raises
  `:rf.error/hicasso-intent-needs-the-event` naming the position, the form and the missing `key`
  slot, with a recovery. A divergence of mechanism where the outcome is the disposition's own.
  **Not filed.**
- **The stale-reply check is present where it is needed and deliberately absent where it is not.**
  `::saved` and `::save-failed` compare the reply's slug against the request's; the digest pair does
  not, and the file says why at the point of absence — one region, one content source, nothing for a
  later reply to clobber. Ceremony copied from a neighbour is what that comment refuses, and it is
  right to.
- **The slice's error regions are nested, and the nesting is load-bearing.** `app` wraps the routed
  pane; `digest` wraps only the digest body. Whatever a content block does to itself, the article
  list, the pager and the chrome are still there. `:reset-key` is the **content** rather than a
  counter, and the file argues the half a counter gets wrong: a retry that changed nothing would
  otherwise flicker through an empty success.
- **The evidence claim survives its own instrument.** `require_graph.clj` reads all four analyzer edge
  kinds rather than `:requires` alone, which is the half that matters — `h/defview` is a macro, and a
  check blind to `:require-macros` would be blind to exactly the door being claimed.

## 8. Considered, and not filed

- **The witness applications' docstrings are essays.** `slice/views.cljs` opens with a 40-line
  namespace docstring and most of its thirteen bodies carry another. A consumer's `views.cljs` does
  not read like that, and the repo's standing rule is that testbeds are test surfaces and tutorials
  live in the guide. Not filed: the prose is comments, it changes no code shape, and it is the
  evidence record the authoring reports were written from. The rule it brushes against is about
  teaching *layers* — deliberate bugs, anti-patterns, staged mistakes — and there are none.
- **The slice's `expected-doors` roster describes `re-frame.hicasso` as carrying `boundary`.** The
  shipped name is `error-boundary` and the slice's own source uses it. A stale string in a comment
  inside a test's roster map; recorded in
  [`naming-findings-cp2.md`](naming-findings-cp2.md) so `rf2-hic-065`'s sweep does not read it as a
  live candidate, and not worth a bead.
- **`h/reg-state`'s setter cannot take the canonical event shape either.** The Todo report raises it
  as the sharper half of finding 1: `impl.state` registers `[::concern ikey v]`, positional by
  construction, so the collision is between a convention and a registration the framework performs.
  Not filed separately — it is the same disposition as finding 1 above, and its remedy is row 3's
  sitting rather than a code change.
- **`unmount!` was reached by one application and `use-subs` and `error-boundary` by one each.** A
  strict reading of *ordinary* would demote all three to one-application evidence. Not filed and not
  demoted: the Todo class reached a strict **subset**, so a name only the slice reached is a name the
  broader application needed and the narrower one had no occasion for — which is what a
  breadth-chosen witness is for. Recorded because the asymmetry is real and a later reader should not
  discover it as a surprise.

## 9. What this record is not

It is not a claim that the ordinary surface is finished: Phase 4 extends the coverage matrix and may
find a tenth name, and [`facade-freeze.md`](facade-freeze.md) says what would then have to happen. It
is not evidence about the host or hot-path facades, which Phase 3 freezes from their own witnesses. It
is not a re-adjudication of Phase 1, whose exit remains **not met** on
[Checkpoint 1's record](checkpoint-1-kernel.md) and whose open conjunct is `rf2-0xx2`. And it is not a
statement that every frozen law is witnessed by these two applications — the revision half of the
controlled-field law is not, which `rf2-36bd` established by measurement, and it is witnessed
elsewhere.

## 10. Where this page's words came from

Written by the reviewer who ran it, in the same session, from the suites and sources it cites. Every
figure in [§3](#3-what-was-re-run-and-what-it-measures) and [§4](#4-one-sabotage-per-testing-tier) is
a value captured to file by that run rather than a quotation; every line number was read at
`f96aaf8d73`. The reviewer wrote none of the artefacts under review and none of the beads filed
against them.

Checkpoint 1's *record in flight* note has been discharged by Checkpoint 1's own page — its
[§2.2](checkpoint-1-kernel.md) now reads *Row 2's record has landed*, and `rf2-5gb6` is closed — so
this checkpoint does not re-verdict it. One fact from that discharge is worth carrying here, because
it is this checkpoint's own finding in another file: [`globals.md`](globals.md)'s roster grew from
nineteen owners to twenty-one, and the reason is that **the four searches that produced it could not
see dynamic vars**. `rf2-ccuw` is the same species — a roster whose stated derivation cannot
regenerate it — in the two witness applications' import fence. Both landed on `main` while this
review ran; neither was found by the other.
