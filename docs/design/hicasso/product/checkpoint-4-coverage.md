# Checkpoint 4 — the coverage-matrix audit

> **Currency, 2026-09-04 (`rf2-l67a`, `rf2-nf8w`). This page is a dated review, not a statement about
> the tree today, and it is the stalest of the four.** Its newest amendment is **2026-08-15** and it
> **names no base commit anywhere** — it certifies against evidence that already existed and took no
> measurement of its own, as it says of itself below, so a re-runner has no anchor by
> which to tell what tree it was true of. That absence is itself a currency defect and is recorded
> here rather than repaired, because supplying a base after the fact would be inventing one. It was
> **reconciled to `main`@`4f54988b07` by this pass**, which re-scored nothing.
>
> **Three events since 2026-08-15 that its rows do not reflect.** (1) `137bd927db` (2026-08-21, PR
> #8646, `rf2-0brem`) rewrote the *Server and hydration* required result to owe mismatch attribution
> only on roots a re-frame2 door opens — this page has never read it, and a probe of it for the fixed
> strings `8646`, `137bd927db`, `r3dgc`, `0brem`, `vwo53` and `6cc52f60fb` returns **0 for each**,
> against a control of 4 for `rf2-hic-048` on the same file. (2) `rf2-s52w` closed on 2026-08-15 and
> its ledger row reads `resolved`; this page still calls it `open` in four places. (3) `aa01f0e8a6`
> (2026-08-29, `rf2-6c12m.31`) retired the native grammar, so the `n/$` route this page names eight
> times no longer exists.
>
> **None of that moves conjunct B, which is still NOT MET** — see the amendment at that conjunct for
> which clause leaves the enumeration and which four ids still fail it.

**Verdict: the Phase 4 exit is NOT MET.**

[Specification §12](specification.md#phase-4--close-the-application-coverage-matrix) states that exit as a conjunction —
*"every row in section 7 points to running evidence, an installable optional module, a tested recipe, or an explicit
non-goal with a React escape, **and** the canonical SSR/hydration and bulk/economic suites are green"*. Nineteen of the
twenty coverage rows point at running evidence in the tree today, which is the most this programme has ever been able to
say. One does not, one of the two remaining conjuncts is red and the other is unaddressed, and a conjunction with a
failed conjunct is failed however good the rest of it is.

**[Amended 2026-08-14, `rf2-c0agr`.]** This page first scored Accessibility **NOT MET** and read sixteen and four. The
deficit it named was the axe half, and `rf2-5q8o` has since ruled that half a **DECLINE** — so the row is met and the
counts move by one. Nothing was re-measured and no other row changed; §2's Accessibility cell shows the working. The
**verdict is untouched**: conjunct B fails this exit on its own and always did.

**[Amended 2026-08-15, `rf2-hic-048`, reopened by the merged-PR audit of #8268.]** This page then scored Migration
**NOT MET** and read seventeen and three, on the premise that migration's population is the in-repo corpus alone. **That
premise is false, and it had already been disproved twice before this amendment took it out.** `rf2-hic-055` ran three
repositories and pinned each by commit; the two external ones are cloned read-only and measured rather than vendored, so
this tree was never the place they would show. `rf2-gqp5s` is withdrawn in
[`correction-ledger.md`](correction-ledger.md), the row is met, and the counts move by one again. Nothing was
re-measured, no migration work was rerun and no other row changed; §2's Migration cell shows the working. The **verdict
is untouched** for the same reason as before: conjunct B fails this exit on its own.

**[Amended 2026-08-15, `rf2-hic-048`, reopened by the merged-PR audit of #8277.]** This is the first amendment where a
**fix landed** rather than a premise being disproved, and it is the largest. PR #8275 closed three of this page's five
filed misses — `rf2-y5x6j`, `rf2-2l8pw` and `rf2-cfriw` — on independent re-runs by closure bead `rf2-dybf9`, which
wrote none of the fixes. Their evidence is in [`correction-ledger.md`](correction-ledger.md). The consequence for this
page is that **Code splitting is met and the counts move to nineteen and one**, and that §3 no longer rests on the
findings it was written on. **The verdict is again untouched, and this time the reason is worth stating rather than
repeating**: conjunct B still fails, on gaps that were *always* recorded separately from the three closed findings and
that no part of #8275 touched — HS-33 satisfying neither policy, HS-17 and HS-18 unwitnessed on the server, HS-21's
attribution unreachable by construction, and two live remainders `rf2-dybf9` carried forward deliberately rather than
closing over. §3 takes them one at a time. **Closing a finding is not the same as closing the conjunct it was filed
under**, and a re-run that flipped this verdict on the arithmetic alone would have proved only that it had not read §3.

**[Amended 2026-08-15, `rf2-hic-048`, after PR #8286 landed the `rf2-fdg4w` ruling.]** This is the first amendment where
a **decision** landed rather than a fix or a disproved premise, and it is the one that moves the least. The operator ruled
HS-23's `n/$` Activity route — **Disposition 1, accept the render, docs only, no gate and no code** — and `rf2-fdg4w` is
closed. So the clause this page has carried since #8275, that conjunct B is failed in part by *a policy the measurement
disproves whose disposition is an open operator decision*, is retired: the policy is not disproven, it is
**declaration-scoped**, and a route carrying no declaration carries no Hicasso policy to disprove. **Nothing else moves.**
The count stays **nineteen and one** and the verdict stays **NOT MET**, because conjunct B was never failed by HS-23 alone:
HS-33, HS-17, HS-18, HS-21 and HS-34 fail it too, none of them was touched by #8286, and all five were re-read against
`main` for this amendment. §3 records the settled disposition and §7's live table is one row shorter and still not empty.
**A discharged blocker is not a moved row, and a moved row would not be a moved conjunct.**

This page is the audit `rf2-hic-048` owes. It is a review: it certifies against evidence that already exists and it took
no new measurement. Every miss below is filed as a `bd` issue and carries a row in
[`correction-ledger.md`](correction-ledger.md), on that file's [closure rule](correction-ledger.md#the-closure-rule) —
a row closes when the section that produced it is re-run against the landed fix, never when the fix merges.

## Scope, and what this checkpoint is not

Per the scope correction recorded on the bead, this is the **Phase 4 subset** audit: it asserts the rows whose evidence
Phase 4 schedules. The complete §7 exit, the §13 definition of done and the Phase 5 evidence are `rf2-hic-064`'s. Where
a §7 row's answer landed under Phase 5 — the shipped `re-frame.hicasso.forms` and `re-frame.hicasso.overlay` modules,
the resource-demand verdict — it is read here as landed evidence rather than re-adjudicated.

**Measurement is deferred by operator direction and no window was opened.** Nothing below reconstructs a time from a
counter. That substitution has been refused four times on this programme and it is refused again here.

## Where each fact lives

| Fact | Home |
|---|---|
| The twenty coverage rows and their required proof | [`specification.md` §7](specification.md#7-complete-use-case-coverage) |
| Each row's layer, and which rows stand as Gap | [`dispositions.md` §1.1](dispositions.md#11-classification-table) and [§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) |
| Per-surface server policy, by inventory id | [`dispositions.md` §2.1](dispositions.md#21-surface-inventory-and-dispositions) |
| Per-control conformance across three engines | [`dispositions.md` §2.3](dispositions.md#23-per-control-and-dom-conformance-dispositions) |
| What each row's witness actually is | [`requirements-mine.md`](requirements-mine.md) |
| Every registered budget line and its verdict | [`budgets.md` §9](budgets.md#9-the-budget-line-reconciliation-ledger) |
| The bulk topology result | [`topology-tournament.md`](topology-tournament.md) |
| This checkpoint's misses | [`correction-ledger.md`](correction-ledger.md) |

## 1. The exit, conjunct by conjunct

**Conjunct A — every §7 row points to running evidence, an installable module, a tested recipe, or an explicit non-goal
with a React escape. NOT MET, on one row.** Nineteen are witnessed; the one is §7's own SSR row. §2 takes them row by
row. **Conjunct A now fails on exactly what conjunct B fails on**, which is a narrowing rather than a coincidence: Code
splitting was the last row failing for a reason of its own, and its HMR witness landed.

**Conjunct B — the canonical SSR/hydration suite is green. NOT MET.** One inventory id is measured to satisfy *neither*
policy; two are unwitnessed on the server; one has an attribution clause unreachable by construction; and one names a
module that does not exist. **A fifth clause stood here until 2026-08-15** — an id whose policy the measurement
*disproved*, held open on an operator decision — and PR #8286 settled it: HS-23's policy is declaration-scoped and the
`n/$` route carries no declaration, so nothing is disproven and nothing is owed. **Dropping that clause does not move this
conjunct**, which the four clauses above fail it on, over five ids, without it. The denominator this conjunct is stated
over **was** short by three public surfaces and no longer is — that half is closed, and machine-checked. §3.

**[Amended 2026-09-04, `rf2-nf8w` — a second clause leaves the enumeration, and the conjunct does not move. It is still
NOT MET.]** The clause that leaves is *one has an attribution clause unreachable by construction*, `HS-21`, and it
leaves twice over. First by scope: `137bd927db` (2026-08-21, PR #8646, `rf2-0brem`) rewrote the *Server and hydration*
required result — and the SSR/hydration matrix's blanket sentence with it — to owe mismatch attribution only on roots a
re-frame2 door opens, citing [Spec 011's hydration-mismatch detection](../../../../spec/011-SSR.md#hydration-mismatch-detection),
which had said so normatively all along. So the clause was never unmet, only unstated. Second by deletion: the outward
bridge it is about was retired on 2026-08-29 by `aa01f0e8a6` (`rf2-6c12m.31`) with the whole native grammar, so there is
no longer a construction for it to be unreachable by. **Neither of those is a repair and neither is a re-score** — the
2026-08-15 reading is kept above exactly as written. **What is left still fails the conjunct**, on the same arithmetic
the paragraph above uses on `HS-23`: three clauses over four ids — `HS-33` measured to satisfy *neither* policy,
`HS-17`/`HS-18` unwitnessed on the server, and `HS-34` naming a module that still does not exist, `git ls-files` finding
no `re-frame.hicasso.routing` under `implementation/hicasso/src/` at `main`@`4f54988b07`. **Dropping `HS-21` does not
move this conjunct, for the same reason dropping `HS-23` did not.**

**[Corrected 2026-09-05, `rf2-nf8w`, after the merged-PR audit of #9185 and #9189. The amendment above is kept as
written; the conjunct is still NOT MET, no clause is re-scored and no row is recoloured.]** The amendment's **second**
reason is false. *The outward bridge it is about was retired … so there is no longer a construction for it to be
unreachable by* — **the outward bridge was not retired.** `h/as-component` is live at
`implementation/hicasso/src/re_frame/hicasso.cljc`, where the facade's own docstring calls it **the outward bridge**,
and at `impl/codec.cljs`; the docstring says in terms that it sits on the facade *rather than* on the native tier so
that a UIx or JavaScript parent need not require the native namespace, so `aa01f0e8a6` — which deleted the native
construction grammar, the `native_fence` conjunct and the `native_abi` witness — did not reach it. Its client-side
witness is live at `implementation/hicasso/test/re_frame/hicasso/foreign_root_bridge_dom_cljs_test.cljs`, which mints
the bridge and mounts it from raw-React and UIx parents. **An outward-bridged root can still be built.** What is gone is
`rf2-s52w`'s *a-consumer-built-root-hydrates-a-bridged-subtree-with-no-framework-reporter* row, which went with
`native_abi_dom_cljs_test.cljs` — **the witness, not the surface.** **`HS-21`'s clause still leaves the enumeration, and
this conjunct still does not move**, because the **first** reason above carries it on its own: `137bd927db` narrowed the
required result to owe mismatch attribution only on roots a re-frame2 door opens, and a consumer-built root is not one.
The arithmetic below is therefore unchanged — three clauses over four ids, `HS-33`, `HS-17`/`HS-18` and `HS-34` — and
**conjunct B remains NOT MET on exactly those.** Only the false second reason is withdrawn.

**Conjunct C — the bulk suite is green. UNADDRESSED, and that is neither red nor green.** The tournament published its
deterministic work census and its clock table is recorded NOT INSTRUMENTED; it concluded, in its own words, *"no verdict
against U1, U2, U3, U4, C3 or C4"* ([§2.8](topology-tournament.md#28-what-was-not-concluded)). Those are millisecond
lines and no package-resident clock instrument exists to read them. §4.

**Conjunct C — the economic suite is green. NOT MET, and long since recorded.** K1 stands MISSED against its registered
`1.10x` line, unrecoloured by the ratified `1.25x` ceiling ([`k1-price-acceptance.md`](k1-price-acceptance.md)); the
read-free boundary shell breaches its `1,024 B` line and carries a scoped acceptance that *prices* the breach rather
than passing it. Both are carried red with named dispositions, which is the correct state and not a green one.

**One conjunct fails the exit on its own.** Conjunct B does, and it is the one Phase 4 was written to close — *"the
complete countable SSR/hydration matrix"* is named verbatim in the phase description. So the verdict does not rest on
the unaddressed conjunct, and would not change if the clock landed tomorrow.

## 2. The twenty coverage rows

Scored against §7's own **Required proof** cell, and against
[`completeness-audit.md`](lanes/completeness-audit.md) where that lane names a proof suite §7 states only by summary.
Three values, and *unaddressed* never reads as *works*.

| §7 row | Score | The evidence, or what is absent |
|---|---|---|
| Ordinary pages, conditional UI, dynamic lists | **met** | `examples/slice/` and `examples/todo/` on public namespaces only, extended by `extension_dom_cljs_test.cljs`'s pagination and runtime-selected content |
| Forms and controlled fields | **met, with two findings on the row's own law** | `examples/editor/` and `examples/grid/`; the synthetic composition sequence green on all three engines. Two of §2.3's three findings are this row's — see §5 |
| Validation and async normalization | **met** | the two-gate settle: correlate against the slug the editor still holds, then merge field by field against `:touched` |
| Routing and navigation | **met** | deep link, back/forward and pending-mutation conduct in a real browser, with a marked decoy heading as the control on the focus lookup |
| Async resources and mutations | **met** | the typeahead against criteria frozen before the data was taken, and the adopt-or-stop verdict, which is **STOP** |
| Errors | **met** | thrown render, retry, and the nested region — inner catches, outer survives, retry works — in `extension_dom_cljs_test.cljs` §6–§7, landed in `rf2-hic-074`'s own commit `93f3040ea0` |
| Foreign React ecosystem and native hot work | **met** | compound library, render prop, provider through React's own `createContext`, portal, native hot row. Open corrections `rf2-b3gy` and `rf2-ap7w` |
| Large collections | **met on the work census; the clock half unaddressed** | ten thousand records in twenty-four mounted rows; one body per keystroke at both sizes. Time is `rf2-w01c`'s and is not claimed here |
| Imperative SDKs | **met** | StrictMode double-invoke, unmount, keyed remount, thrown render and its retry, with the leak itself as the instrument |
| Overlays and focus | **met** | nesting, dismissal, focus restore and zero idle listeners, with both predicate failures reproduced against the old predicate before the fix |
| Motion and high-rate input | **met** | interruption, rapid-toggle cancellation, mid-transition teardown. The frame budget is a work COUNT, and the witness says so rather than implying a stopwatch |
| Code splitting | **met** | §7's Required-proof cell for this row reads *load, fallback, error, retry, HMR*. The first four are `lazy_boundary_dom_cljs_test.cljs`'s (`rf2-hic-041`). **HMR is the fifth and it landed** — `testbed/hmr_spec.cjs`'s `native-lazy-island-across-a-save` drives one lazy island through a real `shadow-cljs watch` recompile on Chromium, Firefox and WebKit, with `pinned-lazy-head-sabotage` beside it as the arm that reds when the bridge caches its head across a save. **This row read NOT MET until 2026-08-15**, on the measurement that `hmr_registry_cljs_test.cljs` and `hmr_remount_cljs_test.cljs` carried zero occurrences of *lazy* between them. **That measurement is still exact and is no longer the deciding one**: both suites still return zero (re-measured 2026-08-15), and the witness landed in the browser HMR runner instead — a real recompile rather than an in-process re-mint, which is the stronger instrument of the two and the one §7's cell was always describing. `rf2-y5x6j` **closed** on a re-run by `rf2-dybf9`, which drove the runner rather than quoting it |
| Multiple frames and roots | **met** | two roots with isolated ownership, root-scoped hydration adoption, same-public-id reincarnation and delayed-callback routing |
| Suspense and Activity | **met** | hide/reveal releasing and reacquiring reads, and genuine abandonment, retry and rollback driven rather than simulated. Open correction `rf2-9ywe` |
| SSR and hydration | **NOT MET** | §3 of this page. **The two beads this cell used to name are both closed** and neither closure moved the row: `rf2-2l8pw` repaired the claim's *denominator*, and `rf2-cfriw` replaced *four ids with no witness* by four ids that have been server-rendered and read — of which, in [§1.1](dispositions.md#11-classification-table)'s own words, *"not one of them went green"*. What the row now turns on is inside the inventory rather than outside it: `rf2-s52w` (**open, and the operator's call**), HS-33's decided-and-unbuilt repair, HS-34's unbuilt module, and HS-17/HS-18. **`rf2-fdg4w` stood in that list until 2026-08-15 and no longer does** — PR #8286 ruled HS-23's `n/$` route dispositioned rather than blocked — and **the row does not move with it**, because the five ids beside it are untouched and each still fails on its own |
| Accessibility | **met** | §7's Required-proof cell for this row reads *names, roles, keyboard, virtualized/overlay focus*, and every limb of it is witnessed — `test_kit_a11y_cljs_test.cljs` over three L2 kit projections, `examples/slice/a11y_cljs_test.cljs`, `.../a11y_focus_dom_cljs_test.cljs` and `combobox_keyboard_dom_cljs_test.cljs` (`rf2-hic-043`); `examples/ledger/keyboard_dom_cljs_test.cljs`, `.../virtualized_dom_cljs_test.cljs` and `overlay_focus_dom_cljs_test.cljs` (`rf2-hic-049`). **This row read NOT MET until 2026-08-14** on one further deficit: an **axe** sweep, which is in no acceptance column here and which `rf2-5q8o` ruled a **DECLINE** — a non-goal, not an unlanded witness. With that the row's only remaining deficit is gone. `rf2-5q8o` |
| i18n and theming | **met** | the page is mounted once and never re-mounted, and `<main>`'s identity is asserted across every switch — so a mechanism that tore the tree down would show a different node |
| Testing | **met** | L0–L2 pure kit with its runtime-parity claim held, L3 mounted facade sabotaged across four leak kinds, production-sentinel erasure chained into the release build |
| Diagnostics | **met** | versioned projection with its schema held, four views, the cause-aware advisor. Open correction `rf2-t2d3` · **[Amended 2026-09-04, `rf2-l67a`: `rf2-t2d3` is not open.** Its row in [`correction-ledger.md`](correction-ledger.md) reads `closed`, transitioned on 2026-08-14 against `main`@`77bee1b9b7` on a §2 Correctness re-run by the re-dispatched `rf2-hic-038`. The score is **not** touched by this note — the row read **met** with the correction open and reads **met** with it closed, so nothing here moves a verdict; what is corrected is a live-state claim that went stale the day after it was written.**]** |
| Migration | **met** | reporter, refusal classes and shadow comparison all landed — `migration/reagent-to-hicasso/codemod/` and `.../test_kit/test/re_frame/hicasso/shadow_dom_cljs_test.cljs` — and so did the **population**. §7's Required-proof cell for this row reads *three representative repositories*, and `rf2-hic-055` ran three, each pinned by commit: the in-repo examples (81 files); `athensresearch/athens` @ `b463a97a0d28cbc0a29bec55feebcbe54c20de54`, canonically at [that repository's commit page](https://github.com/athensresearch/athens/commit/b463a97a0d28cbc0a29bec55feebcbe54c20de54), 131 files and 109 census sites; and `district0x/memefactory` @ `71bf92b4b4b935bda089783fd6c07041f84d34f8`, [likewise](https://github.com/district0x/memefactory/commit/71bf92b4b4b935bda089783fd6c07041f84d34f8), 102 files and 85 sites. Both SHAs are commits of those repositories rather than of this one — which is the reason each is typed with its permalink, and a second way of saying that this tree is not where they live. `day8/re-frame-10x` was the vendored-Reagent control and `implementation/hicasso/src` the clean 0/0/0 one, with determinism shown by two byte-identical athens runs. **This row read NOT MET until 2026-08-15**, on the premise that the population is the in-repo corpus alone. **Read this before re-asserting that premise, which has now been disproved twice: Migration's population spans three repositories, and the two external ones were checked out read-only and measured rather than vendored — so their absence from this tree is the method working, not the run missing.** [§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) records all three runs and scores this row Witnessed. `rf2-gqp5s` **withdrawn** |

**Nineteen met, one not met, none unaddressed at the row level.** The unaddressed category does real work one level down —
inside Large collections, where the clock half is unaddressed while the work census is met — and it is recorded there
rather than allowed to colour the row either way.

### What I did to §1.1, and why

[§1.1](dispositions.md#11-classification-table)'s Planned-witness column was left to this checkpoint by name. It read
`Claimed` on all twenty rows, several of them naming a bead that had closed. `Claimed` was the strongest word the column
had, and [§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) had already recorded why that no longer
worked: *"a closed bead owns nothing, and beads here close on PR-open, so closed does not even mean landed."*

So a column built to say *who owes this* was being read for *is this proved*, and it could not say yes. I added a third
value — **Witnessed**, defined in §1.2 with the reason — and rewrote all twenty cells: sixteen Witnessed with the
artefact named, four Gap with what is absent. `Claimed` now has no occupant, and I kept it rather than deleting it,
because a future surface will be owed before it is proved and the word will be needed again. **Two of those four Gaps
have since been corrected to Witnessed** — Accessibility, whose only deficit was the axe `rf2-5q8o` declined, and
Migration, whose population had already run over three repositories. `rf2-2ius2` made both amendments, and
[§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) carries their working; the sixteen-and-four in the
sentence above is what this checkpoint wrote on 2026-08-14, not what §1.1 reads today.

**[2026-08-15.] A third Gap should now move and has not, and it is not this page's cell to move.** §1.1's Code splitting
row still reads `Gap` on the ground that *"HMR … is not [witnessed], and no bead owns it"*. Both halves stopped holding
when `rf2-y5x6j` closed: the witness is `hmr_spec.cjs`'s `native-lazy-island-across-a-save`, driven on three engines,
and this page scores the row **met** on it above. The SSR cell beside it is current and was re-read rather than assumed —
it already records `rf2-cfriw`'s corrected diagnosis and `rf2-2l8pw`'s three minted ids in its own words. §1.1 is
[`dispositions.md`](dispositions.md)'s and a Planned-witness correction after this checkpoint's own §1.1 pass is an
ordinary amendment under [§3](dispositions.md#3-append-protocol-and-ownership) rather than a coverage finding, so it is
**filed rather than reached for**: `rf2-oc6rn`. Recorded here so a reader who checks §1.1 against this page's count
finds the discrepancy explained rather than fresh.
**[DISCHARGED 2026-08-15, `rf2-oc6rn` (PR #8297). The paragraph above is kept as the record of a cross-page
disagreement that was real when this page re-ran.]** §1.1's Code splitting cell now reads `Witnessed`, on
`hmr_spec.cjs`'s `native-lazy-island-across-a-save`, and
[§1.2](dispositions.md#12-rows-without-a-complete-planned-witness)'s Gap count recomputed from two to one, its own
2026-08-14 audit bullet carrying a beside-amendment in the same form. The two pages now agree — dispositions reads
`Witnessed` where this page scores the row **met**. **Nothing in this checkpoint's score moves with it**: that cell was
always §1.1's to move and never this page's count, which is exactly why the finding was filed rather than reached for.

**One thing found in the doing, and fixed in the same edit rather than filed.** §1.1's SSR row named `rf2-hic-056` as
its planned witness. That bead is the bounded Node service; the per-surface matrix is `rf2-hic-046`'s and `rf2-hic-005`'s.
The cell named a bead that owns a different obligation, which is a worse defect than naming a closed one — a closed bead
at least points at the right work. It is recorded here rather than in the ledger because correcting a Planned-witness
cell is the coverage-matrix owner's own amendment under [§3](dispositions.md#3-append-protocol-and-ownership), and a
finding I hold the fence for is a finding I fix.

## 3. The SSR/hydration conjunct, which is what fails this exit

§7 states this row's proof as a claim over the inventory: *every inventory id is green in the canonical matrix*. §13
repeats it as a definition-of-done clause. Two things are wrong with that claim today and they are different in kind.

**[Re-run 2026-08-15 against landed #8275.]** This section was written on two findings and both have closed. What
follows states what each closure actually bought, because they did not buy the same thing and neither bought the
conjunct.

**Four ids had no witness at all. They have one now, and not one of them went green.** As filed, HS-31 (forms), HS-32
(overlay) and HS-34 (routing integration) had no live owner, and HS-23 (Activity-hosted subtree) had only `rf2-9ywe`,
whose acceptance is the *client* Activity lifecycle. §2.1's own note 3 was the standard they failed: *"A Client-only row
still owes a witness. The refusal must be shown to fire, at source, with its recovery — an unproved refusal is not a
disposition."* `rf2-cfriw` landed `client_only_arms_ssr_cljs_test.cljs` — nine `deftest` rows driving all four through
the real `react-dom/server` — and closed on a re-run by `rf2-dybf9`. **The finding is discharged and the diagnosis
changed rather than the verdict**, which [§1.1](dispositions.md#11-classification-table) records independently of this
page: *"only ONE of the four owes an ordinary refusal at all, and not one of them went green"*. Measuring them is what
established that:

- **HS-31 and HS-32 are now honest, and they are the two that are finished with this checkpoint.** Forms emits its live
  `<input>` into the response and refuses nothing — the module is an *arrangement* over doors that are already Render —
  so what is retired is the claim that a refusal was owed. Overlay's `:open?` false is the application's flag answering
  and not a policy, and its one remaining ident was taken out of the bytes by `rf2-9zz0y`. Neither is upgraded, because
  [§2.4](dispositions.md#24-the-default-rule-and-how-a-row-is-upgraded) asks five clauses and `rf2-cfriw`'s scope was
  the refusal arm alone. Honest is not green, and this conjunct asks for green.
- **HS-23 is the one the measurement made worse, and it is the one that is now SETTLED.** It read here as an open
  operator decision until 2026-08-15, when PR #8286 landed `rf2-fdg4w`'s ruling — **Disposition 1: accept the render on
  the `n/$` route, docs only, no gate and no code** — and closed the bead. What the ruling changes is the *diagnosis*,
  never the measurement: a Hicasso server policy attaches to a **declaration**, `h/defhost` and `n/defcomponent` are the
  two declaration doors, and a foreign head under `n/$` passes through neither. So the Target policy is not *disproven*
  on that route — it never reached it. Through `h/defhost` the refusal fires at the declaration source, with its declared
  fallback and its `:server :render` control beside it: both arms, which is what note 3 asks of a Client-only row.
  Through the raw `[:>]` escape it is HS-19's hard refusal, **inherited** from that same gate rather than invented, which
  is why the `[:>]`/`n/$` split is a principled tier boundary and not the inconsistency this bullet used to call it.
  Through `n/$` there is **no Hicasso policy at all: React's own server semantics govern — bytes witnessed, no hydration
  claim made, and the route is NOT upgraded to Render**, because
  [§2.4](dispositions.md#24-the-default-rule-and-how-a-row-is-upgraded) asks five clauses and only the first of them is
  measured here. **A witness was never owed and none was added** —
  `client_only_arms_ssr_cljs_test.cljs`'s *through-n-dollar-a-visible-activity-subtree-reaches-the-response* is that
  witness, landed with `rf2-cfriw` and cited by HS-23's cell all along. The **disposition** that was owed now exists.
  **This checkpoint records a disposition and reads it as one**, which is the distinction scoring the row would lose.
- **HS-34 owes nothing until a module exists.** There is no `re-frame.hicasso.routing` namespace anywhere under
  `implementation/`; `check_optional_module_reachability.py`'s `MODULES` roster names five and routing is not among
  them. No refusal can fire at a declaration source that does not exist, so the row is a finding rather than a gap and
  its witness is owed by whichever bead lands the namespace, inside that bead. `h/route-link` — which is what a reader
  expects to find here — lives on the **core** facade as HS-40 and is **Render**; that is not this row's cover.

**HS-35 is deliberately not in that list, and it looks like it should be.** Its cell was conditional on a graduating
verdict, and `rf2-hic-050` returned one: **STOP**. There is no surface to disposition, so the row owes no witness at all
and its refusal is discharged rather than outstanding. Checked before scoring, because scoring it as a fifth gap would
have been the easy error.

**And the claim's denominator is short — which is the finding this checkpoint would not have got from any hand-off.**
[§3](dispositions.md#3-append-protocol-and-ownership)'s second constraint reads: *"Adding a public surface adds an id. A
surface that reaches the facade without a row here has escaped the inventory, and the Phase 4 exit — every inventory id
pointing at an applicable green row — silently stops meaning anything."* Three names on `re-frame.hicasso`'s alias block
have no row anywhere in §2.1 or §2.2:

- **`h/route-link`** — a node of the rendered tree with real server bytes. The census counts 106 sites, the
  second-most-frequent interactive element after buttons, and licenses them to stay *href-real and visible to the server
  renderer*. No SSR suite in the tree renders one. [`requirements-mine.md`](requirements-mine.md)'s Routing row and
  HS-34's cell both record the hole, and both read it as an ownership gap; it is an inventory gap, and that is the
  reading that makes the exit criterion unsound rather than merely unmet.
- **`h/use-subs`** — a second public read door, the grouped control to `h/sub`'s ambient collector. Live in shipped
  example applications; absent from all five server-render suites. HS-02 dispositions *"`h/sub` read during a server
  render"* and proves five distinct server properties for it, none of which has been asked of this door.
- **`h/reg-state`** — non-rendering, so §2.2 is its home, and it is absent from that section too.

Filed as `rf2-2l8pw`.

**[Re-run 2026-08-15.] The denominator is repaired, it is now derived by a gate rather than by a grep, and one sentence
this section wrote about it was wrong.** All three names carry rows — `h/route-link` → **HS-40** (§2.1, Render),
`h/use-subs` → **HS-41** (§2.1, Render), `h/reg-state` → **HS-42** (§2.2) — and `rf2-2l8pw` closed on a re-run by
`rf2-dybf9`. The mechanism that produced the escapes closed with it: `implementation/hicasso/scripts/check_facade_inventory.py`
(`rf2-gz4bq`) walks every `def` head on `re-frame.hicasso` as **code**, with strings, comments and reader-discarded
forms blanked, and diffs the result against §2.1 and §2.2. **Re-run for this checkpoint: 16 names on the door, 43
inventory rows read, ZERO escapes — 13 attributed by name, 3 by declaration — captured exit 0.** The claim is no longer
one a re-reader has to take on trust, which is a better outcome than the finding asked for.

**And the correction this section owes on its own account.** It closed by naming `h/as-component` and `h/hframe` as
inventoried *by description rather than by spelling*, at HS-21 and HS-14, so that a re-runner would not re-find them as
escapes. **Half of that was wrong, and in the direction that hides a defect.** `h/as-component` → HS-21 is right and the
gate attributes it by declaration. `h/hframe` → **HS-14 was not right**: `h/hframe` carried no row in §2.1 or §2.2 and
no declared entry, which `rf2-lvelh` established by measurement against `origin/main` and which the fix for `rf2-2l8pw`
had itself denied when it reported *no fourth escape*. It was a **fourth** escape, minted since as **HS-43** in §2.2.
The lesson is the one this page already learned on Migration, read the other way round: an inventory attributed *by
description* is an assertion about a document, and it wants the same check as any other — which is exactly the check
that now exists. Two things follow, and neither reopens this row. The denominator is complete over the larger count of
sixteen rather than the fifteen the fix claimed; and this page's own reasoning, not just the fix's, was resting on a
naked eye where a gate belonged.

**Three more rows are held rather than green, for stated reasons, and none of them is a defect.** HS-33 is measured to
satisfy *neither* policy — the third state the two-policy matrix exists to exclude — with its Render repair recorded as
DECIDED and unbuilt. HS-17 and HS-18 are unwitnessed on the server and say so precisely: a `:slots`-declared named
position is witnessed neither way, and `h/as-element` has no server-render row anywhere. HS-21's third clause is
unreachable by construction (`rf2-s52w`). Each is honest about itself, which is why none of them needed a bead from
this audit — but none of them is green, and the conjunct asks for green.

**[Re-run 2026-08-15.] All four cells were re-read against `main` and not one has moved**, which is the load-bearing
fact of this whole re-run. Nothing in PR #8275 touched them: `rf2-2l8pw` worked on the inventory's membership,
`rf2-cfriw` on four *other* ids, `rf2-y5x6j` on a different §7 row entirely. **Nothing in PR #8286 touched them either**
— it ruled on HS-23 and edited two other pages — so all four were re-read against `main` a second time, later on
2026-08-15, with the same result. **So the conjunct that decides this exit is failed today by the same rows that failed
it before the three findings closed, plus HS-34's absent module.** `rf2-s52w` remains `open` in both the tracker and
[`correction-ledger.md`](correction-ledger.md), re-checked on the day of writing. That is why the arithmetic moved and
the verdict did not, and it is the difference between reading §2 and reading §3.

## 4. The bulk and economic conjunct

**The clock is a documented non-claim, not a gap, and this checkpoint declines to convert it into one.**
`rf2-hic-036` closed on its deterministic census with the clock table recorded NOT INSTRUMENTED and split to `rf2-w01c`;
`rf2-m6i0` certified three arms; the windowed arm's clock control was ruled to have no discriminating power at the
committed window size. The tournament's [§2.8](topology-tournament.md#28-what-was-not-concluded) enumerates what it did
not conclude, including *"no clock figure at all, for any arm, operation or row count"*. A page allowed to end in
*unresolved* did, and did not end in *to be continued*. **Unaddressed is not red and it is not green**, and no ordering
was reconstructed from the work counters to fill the space.

**Every red cell in the bulk verdict has an explicit disposition**, which is what §1 of this checkpoint's protocol asks.
The instrument-limited broad row is refused rather than passed and §6 records it as an open obligation rather than a
waiver; `U5`'s fail-open — an estimand that could not see the arm it was written to forbid — is closed by the companion
counter `D26` and the rule `L7`, in the direction that narrows rather than widens.

**The budget ledger holds no silent breach.** I ran its gate rather than reading its summary:
`check_budget_ledger.py`, captured exit **0**, **49 rows — 31 MET, 5 BREACH, 3 UNRESOLVED, 10 UNPINNED**. Every one of
the eighteen not-MET rows names an authority and a disposition that resolves; no band crossing its line is recorded as a
pass; no distributional row is wired to a pull-request gate. The gate's own closing sentence is the one to quote: *"This
is a verdict about the RECORD. It is not a statement that any budget is met."* It is not, and §1's conjunct C is scored
on that.

## 5. The three conformance findings, read as open

[§2.3](dispositions.md#23-per-control-and-dom-conformance-dispositions) landed complete under `rf2-hic-040` — twenty
policies, zero `Owed | Owed` remaining, 190 checks across 25 sections green on Chromium 147.0.7727.15, Firefox 148.0.2
and WebKit 26.4. Three of its cells are **findings, not certifications**, and its own text instructs this audit to read
them as open. I do:

- `::h/value` on a `<select multiple>` reads the **first** selected option and discards the rest, inside the turn,
  silently (`rf2-42vlw`).
- A `:value` on a file input throws `InvalidStateError` in all three engines, and React 19.2 carries no
  controlled-file-input warning of its own (`rf2-u2tza`).
- A kebab keyword on a custom element is camelCased before React sees it, so the dashed attribute never appears, while
  a string key and `data-*` both survive (`rf2-n71ma`).

**No bead from this checkpoint, and the reason is a good fence rather than an omission.** All three have live beads and
are with an active worker. None was repaired by `rf2-hic-040` because a source-located refusal mints an error id, an
error id owes a `spec/009` row, and `spec/*` is hot zone that bead was fenced out of — the same fence applies here. Each
row is asserted **in the direction the runtime behaves**, so the witness reds on repair as well as on regression, which
is what makes the gate detect the fix.

**One cell is UNADDRESSED rather than passing**: native autofill, on all three engines, because no cross-engine drive
exists — Chromium's is a CDP method needing an address profile and neither Firefox nor WebKit exposes one at all. The
eventless-write proxy that *is* gated is named a proxy in its own row and is not offered as a substitute. Recorded, not
filed: there is no work here to dispatch, only a platform limitation that is already stated honestly.

## 6. What was not re-run, and why it cannot flip the verdict

This checkpoint's protocol nominates a clean-checkout run of the Phase 4 suites and one sabotage per witness family.
**Neither was taken.** Measurement is deferred by operator direction, and the box is contended. `rf2-5nijq` is the
precedent: Checkpoint 3 filed that omission against itself rather than let it pass unmentioned, and this checkpoint does
the same by recording it here.

**The omission is one-directional.** A clean-checkout re-run and a sabotage sweep can only turn a *met* row into a *not
met* one — they cannot discover evidence that is absent from the tree, and absence is what the failing row turns
on. It was decided by reading the tree, not by running it: §2.1 cells with no witness cited, and facade names
with no inventory row. Neither reading would move under a green suite. So the verdict is safe against the gap,
and only the nineteen *met* rows are held on evidence this checkpoint did not itself re-execute.

**[Re-run 2026-08-15.] Three of those readings have since been re-taken by somebody else, and that is worth recording
rather than absorbing.** `rf2-dybf9` closed `rf2-2l8pw`, `rf2-y5x6j` and `rf2-cfriw` by **executing** what this page
read: the node lane over `client_only_arms_ssr_cljs_test.cljs`, the facade gate over the door, and the real
`shadow-cljs watch` HMR runner on three engines. Every one confirmed the reading it re-took. **The omission above is
narrower than it was and it is not closed**, since the Phase 4 clean-checkout sweep and the per-family sabotage remain
untaken by this page; what has changed is that the three readings most exposed to it are no longer this page's alone.

**Where reading the tree is not one-directional, and this checkpoint learned it the hard way.** The argument above holds
only for a proof that would *be* in this tree if it existed. Migration's population is the case where it does not: the
external repositories are cloned read-only and measured, so a tree search returns empty by design and returning empty
proves nothing. This page scored that row NOT MET on exactly that reading and was wrong (§7 below). Where a method
leaves no trace here, the record of the run is the evidence — check the bead before scoring absence.

## 7. The misses

Five were filed, all `coverage`, all rowed in [`correction-ledger.md`](correction-ledger.md). **[2026-08-15: none of the
five blocks any longer — three closed on independent re-runs and two were withdrawn.]** All five are kept below rather
than deleted, on the ledger's own rule that a finding which proves mistaken is closed with its reason so the audit can
see it was considered. **A ledger with no live row of this checkpoint's is not a met exit**, and the section below the
table is where that distinction is drawn.

| Finding | bd id | What became of it |
|---|---|---|
| Three facade surfaces with no inventory id | `rf2-2l8pw` | **Closed 2026-08-15** on a §1 re-run by closure bead `rf2-dybf9`, which wrote none of the fix. HS-40, HS-41 and HS-42 minted; the roster re-derived from the door as code by `check_facade_inventory.py`, zero escapes. The fix's own *no fourth escape* claim was false when written — `h/hframe` was one, minted since as HS-43 (`rf2-lvelh`) — so the row closed over a **larger** denominator than the fix claimed |
| HMR through the `React.lazy` bridge, unwitnessed and unowned | `rf2-y5x6j` | **Closed 2026-08-15**, same closure bead. `npm run test:hicasso-hmr` driven rather than quoted: 45 real shadow reloads, 153 checks per engine on Chromium, Firefox and WebKit, with a section-name coverage floor so a skipped section cannot hide in the total. §7's Code splitting row is **met** above on it |
| HS-31, HS-32, HS-34, HS-23 owe an unproved refusal, no live owner | `rf2-cfriw` | **Closed 2026-08-15**, same closure bead, and it is the one whose closure left work standing rather than finishing it. The witness landed and was executed; the diagnosis changed and no id went green. **Two remainders were carried forward deliberately** — HS-23's disposition and HS-34's absent module. **The first is settled**: PR #8286 ruled it on 2026-08-15 and `rf2-fdg4w` is closed. The second stands. §3 |
| ~~The axe checks never landed and the routing chain terminates in nothing~~ | `rf2-5q8o` | **Withdrawn, and blocks nothing.** `rf2-7znnl` was closed as a duplicate of `rf2-5q8o`, whose ruling is **DECLINE**: no acceptance column asks for an axe sweep, so it is a non-goal rather than an unlanded witness. The Accessibility row it was filed against is met on §7's own Required proof |
| ~~Migration's population is the in-repo corpus alone~~ | `rf2-gqp5s` | **Withdrawn, and blocks nothing.** The premise is false: `rf2-hic-055` ran three repositories and pinned each by commit, and the two external ones are checked out read-only and measured rather than vendored, so this tree was never where they would appear. Withdrawn in [`correction-ledger.md`](correction-ledger.md); the bead is closed and owes no work |

### What is still live, which is not the same list

The table above is this checkpoint's **filed** misses and it is now empty of blockers. The exit is still not met, so a
reader who stops at the table draws the wrong conclusion — precisely the reading
[`correction-ledger.md`](correction-ledger.md) warns of when it says rows are not the verdict. Three things stand between
this page and a met conjunct B, and **not one of them is a row this checkpoint filed**:

| What stands | Owner | Kind |
|---|---|---|
| HS-21's outward-bridge mismatch attribution is unreachable by construction — no Spec 011 reporter on a hand-rolled `hydrateRoot` | `rf2-s52w`, open | A missing door; the ledger row is open too, and the door is the operator's call · **[Amended 2026-09-04, `rf2-nf8w`: every clause of this row is now wrong except the first, and the row is kept because a later reader will meet the wrong ones.** `rf2-s52w` is **closed** in the tracker, closed on 2026-08-15; its ledger row reads **`resolved`**, not `open`. And **both clauses of the stated cause are refuted**: it is not *a missing door* — `re-frame.hicasso.server/render` landed 2026-08-14 as `30317bfe0e` (PR #8236, `rf2-b6jkj`) and `impl/roots.cljs` names it one of two minters of the adoption window — and it is not *the operator's call*, because `rf2-s52w`'s own close record rules the finding **a scope, not a gap**: `onRecoverableError` is an option of an individual root, and the package sets none on a root it did not open. That is a mechanism, and it still holds at tip. What has gone is the *bridge*: `aa01f0e8a6` (2026-08-29, `rf2-6c12m.31`) deleted the native grammar, so the outward-bridged root this row describes cannot be built. **This is not a re-score** — the row stays in this table as filed, conjunct B stays NOT MET on `HS-33`, `HS-17`/`HS-18` and `HS-34`, and the `HS-21` clause's departure is recorded at conjunct B above.**]** · **[Corrected 2026-09-05, `rf2-nf8w`, after the merged-PR audit of #9185 and #9189. The sentence immediately above is kept because a later reader will meet it; nothing here re-scores this row.]** **The bridge has not gone, and that sentence is false at tip.** `h/as-component` is live at `implementation/hicasso/src/re_frame/hicasso.cljc`, where the facade's own docstring calls it **the outward bridge**, and at `impl/codec.cljs`; the docstring says it sits on the facade rather than the native tier so a UIx or JavaScript parent need not require the native namespace, so the native retirement did not reach it. Its witness `foreign_root_bridge_dom_cljs_test.cljs` still mints that bridge and mounts it from raw-React and UIx parents, so **an outward-bridged root can still be built.** What `aa01f0e8a6` deleted is the native construction grammar, the `native_fence` conjunct and the `native_abi` witness — including this row's *a-consumer-built-root-hydrates-a-bridged-subtree-with-no-framework-reporter* reading — **the witness, not the surface.** The row's own outcome is unchanged: `HS-21`'s clause still leaves conjunct B, on the scope narrowing of `137bd927db` alone, and conjunct B stays NOT MET on `HS-33`, `HS-17`/`HS-18` and `HS-34`.**]** |
| HS-33 satisfies neither policy; its Render repair is recorded DECIDED and unbuilt | unowned — recorded in `impl.roots/open-adoption-window!` | Work, and the only one of the four that is plainly that |
| HS-34's module does not exist, and HS-17/HS-18 are unwitnessed on the server | owed inside whichever bead lands `re-frame.hicasso.routing`; HS-17/HS-18 unowned | Work that is not yet scheduled |

**`rf2-fdg4w` was the fourth row of that table until 2026-08-15, and its removal is the whole of what this amendment
changes.** This page declined to score it while three dispositions were live, on the ground that scoring it would be
asserting a decision the operator had not made. The operator has now made it — Disposition 1, landed in PR #8286 — so
the reason to leave it standing went with it. **What replaced it is a disposition, not a green row**: HS-23 stays
Client-only at its declaration doors, and the `n/$` route is recorded as conduct outside the declaration policy with its
bytes witnessed and no hydration claim made. This page reads it that way and does not read it as an upgrade.
**`rf2-s52w` is the operator call still outstanding**, and it is a different one — a missing door on HS-21, not a policy
question — so it keeps its row above on its own evidence and is **not** swept out on the strength of HS-23's ruling.

**Why this one was got wrong, since the same mistake has now been made twice.** The finding was reached by reading the
tree for the population and not finding it — sound for every other row on this page, and unsound for this one.
[§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) records that the external repositories are **cloned
read-only and measured, never vendored**, so a tree search here is looking in the one place the method guarantees will
be empty. `rf2-hic-055`'s record is the evidence: three repositories, each with a commit hash, two controls, and two
byte-identical athens runs for determinism. A closed bead's record is evidence about what ran even when the tree cannot
show it. The three legitimate dispositions this section used to offer were all answers to a question that does not
arise, and `rf2-hic-064` has no ownerless §7 row to gate on here.

## 8. Considered, and not filed

- **`requirements-mine.md` states on three of its own rows that the nested error region did not land.** It did, in
  `rf2-hic-074`'s own commit `93f3040ea0`, and I confirmed it at source rather than inheriting it: `extension_dom_cljs_test.cljs`
  §6 asserts the inner region caught and the outer survived, then flips a page and re-asserts that nothing was healed by
  a remount; §7 drives the retry. Already filed as `rf2-gvysu`, so **no new bead and no ledger row** — a checkpoint that
  re-files another pass's finding inflates the ledger it is supposed to keep readable.
- **`git grep -w -i axe -- implementation/` returns one hit, not zero.** `requirements-mine.md` and `dispositions.md`
  §1.2 both said it returns none. The hit is `implementation/SECURITY.md`, prose recording the *story* tool's axe-core
  CDN egress — not a witness, not a dependency, not a gate. The claim's substance is exact and its arithmetic was off by
  one prose line. Recorded here rather than filed, and both pages have since taken the correction on their own: neither
  states a zero today.
- **Native autofill, unaddressed on all three engines.** §5 above. A platform limitation stated honestly is not a queue
  item.
- **P12's model-echo claim is not proved by what landed** and `rf2-hic-045`'s finding 1 is unmet. It is a §6
  per-keystroke row rather than a §7 coverage row, it has a live bead, and PR #8181 was open against it while this audit
  ran. Not scored as met, not filed again.
- **HS-33, HS-17, HS-18 and HS-21.** §3 above. Each is precise about what it does not hold, which is the condition for
  not needing a bead; each is counted against conjunct B, which is the condition for not being green.

## 9. What would change this verdict

**[Rewritten 2026-08-15 against landed #8275, because every bead the previous version named has closed.]**

**Conjunct A needs conjunct B and nothing else.** `rf2-y5x6j` landed, so Code splitting is met and the SSR row is the
only one left — and the SSR row is conjunct B stated as a coverage row. The two conjuncts have converged, which is the
clearest the exit has been about what it wants.

**Conjunct B needs three things and all three are work.** In the order a reader can act on them: HS-33's Render repair,
decided and unbuilt; witnesses for HS-17's named slot positions and HS-18's `h/as-element` on the server; and a
`re-frame.hicasso.routing` namespace, inside which HS-34's refusal is witnessed. **A fourth item stood at the front of
this list until 2026-08-15** — `rf2-fdg4w`'s decision on HS-23 — and PR #8286 answered it, which removed the item
rather than completing the list. `rf2-s52w`'s door is the fourth if it is taken; it is its own operator call and no
longer sits behind `rf2-fdg4w`'s, which has been made.

**Conjunct C is unchanged.** Its economic half needs no work at all: it is red by ratified decision and stays red until
a shell arm lands under `1,024 B`. Its bulk half needs a clock, which is `rf2-w01c`'s and is deferred to the
measurement lane.

**The exit does not need all of that to become adjudicable, and it does need all of it to become met.** Nothing here is
large — the three code items are a witness apiece, which is what they were when this page first said so. **What is
different after #8286 is that the front of the queue is a build again.** #8275 left a decision there, and a decision
does not land by anyone working harder at it; #8286 made it, and the three that remain are witnesses, which do.

## 10. What this record is not

It is not a re-run of the Phase 4 suites; §6 says so and files it against itself. It is not a measurement, and it
converts no counter into a time. It is not the §13 definition-of-done audit — that is `rf2-hic-064`'s and it reads this
page plus the ledger. And it is not a claim that nineteen met rows are a Phase 4 exit. They are nineteen met rows,
which is worth saying plainly and is not the same sentence.

**Nor is it a claim that an empty blocker table is a met exit**, which is the reading this page became most exposed to
on 2026-08-15, the day its last filed miss closed. Every finding this checkpoint filed is discharged and the exit is
still NOT MET, because the conjunct that decides it was never failed by those findings alone. A checkpoint's rows and a
checkpoint's verdict are different claims — [`correction-ledger.md`](correction-ledger.md) opens by saying so in four
directions — and this page is now the fifth.
