# Checkpoint 4 — the coverage-matrix audit

**Verdict: the Phase 4 exit is NOT MET.**

[Specification §12](specification.md#phase-4--close-the-application-coverage-matrix) states that exit as a conjunction —
*"every row in section 7 points to running evidence, an installable optional module, a tested recipe, or an explicit
non-goal with a React escape, **and** the canonical SSR/hydration and bulk/economic suites are green"*. Sixteen of the
twenty coverage rows point at running evidence in the tree today, which is the most this programme has ever been able to
say. Four do not, one of the two remaining conjuncts is red and the other is unaddressed, and a conjunction with a
failed conjunct is failed however good the rest of it is.

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
with a React escape. NOT MET, on four rows.** Sixteen are witnessed; the four are §7's own SSR and Migration rows,
Accessibility, and Code splitting. §2 takes them row by row.

**Conjunct B — the canonical SSR/hydration suite is green. NOT MET.** Four inventory ids await a first witness with no
live owner; one is measured to satisfy *neither* policy; two are unwitnessed on the server. And the inventory the
conjunct is stated over is itself short by three public surfaces. §3.

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
| Code splitting | **NOT MET** | load, fallback, error and retry are witnessed. **HMR**, named in the same §7 cell, is not: the lazy suite states the hot-reload fact in prose with no row, and the two HMR suites carry zero occurrences of *lazy* between them. `rf2-y5x6j` |
| Multiple frames and roots | **met** | two roots with isolated ownership, root-scoped hydration adoption, same-public-id reincarnation and delayed-callback routing |
| Suspense and Activity | **met** | hide/reveal releasing and reacquiring reads, and genuine abandonment, retry and rollback driven rather than simulated. Open correction `rf2-9ywe` |
| SSR and hydration | **NOT MET** | §3 of this page. `rf2-2l8pw`, `rf2-cfriw` |
| Accessibility | **NOT MET** | names, roles, keyboard and virtualized/overlay focus are all witnessed. The **axe** checks [`completeness-audit.md`](lanes/completeness-audit.md) lists among the proof suites never landed and nothing owns them. `rf2-7znnl` |
| i18n and theming | **met** | the page is mounted once and never re-mounted, and `<main>`'s identity is asserted across every switch — so a mechanism that tore the tree down would show a different node |
| Testing | **met** | L0–L2 pure kit with its runtime-parity claim held, L3 mounted facade sabotaged across four leak kinds, production-sentinel erasure chained into the release build |
| Diagnostics | **met** | versioned projection with its schema held, four views, the cause-aware advisor. Open correction `rf2-t2d3` |
| Migration | **NOT MET** | reporter, refusal classes and shadow comparison all landed. The **population** is the in-repo corpus alone, which [§1.2](dispositions.md#12-rows-without-a-complete-planned-witness) states does not satisfy §7. `rf2-gqp5s` |

**Sixteen met, four not met, none unaddressed at the row level.** The unaddressed category does real work one level down —
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
because a future surface will be owed before it is proved and the word will be needed again.

**One thing found in the doing, and fixed in the same edit rather than filed.** §1.1's SSR row named `rf2-hic-056` as
its planned witness. That bead is the bounded Node service; the per-surface matrix is `rf2-hic-046`'s and `rf2-hic-005`'s.
The cell named a bead that owns a different obligation, which is a worse defect than naming a closed one — a closed bead
at least points at the right work. It is recorded here rather than in the ledger because correcting a Planned-witness
cell is the coverage-matrix owner's own amendment under [§3](dispositions.md#3-append-protocol-and-ownership), and a
finding I hold the fence for is a finding I fix.

## 3. The SSR/hydration conjunct, which is what fails this exit

§7 states this row's proof as a claim over the inventory: *every inventory id is green in the canonical matrix*. §13
repeats it as a definition-of-done clause. Two things are wrong with that claim today and they are different in kind.

**Four ids are not green, and nothing is scheduled to make them.** HS-31 (forms), HS-32 (overlay) and HS-34 (routing
integration) have no live owner at all; HS-23 (Activity-hosted subtree) has `rf2-9ywe`, whose acceptance is the *client*
Activity lifecycle and does not reach this row's refusal. Two of the four ship as modules a consumer can require today.
§2.1's own note 3 is the standard they fail: *"A Client-only row still owes a witness. The refusal must be shown to
fire, at source, with its recovery — an unproved refusal is not a disposition."* Filed as `rf2-cfriw`.

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

Filed as `rf2-2l8pw`. `h/as-component` and `h/hframe` return no literal match either but **are** inventoried, by
description rather than by spelling (HS-21 and HS-14); they are named here so a re-runner does not re-find them as
escapes.

**Three more rows are held rather than green, for stated reasons, and none of them is a defect.** HS-33 is measured to
satisfy *neither* policy — the third state the two-policy matrix exists to exclude — with its Render repair recorded as
DECIDED and unbuilt. HS-17 and HS-18 are unwitnessed on the server and say so precisely: a `:slots`-declared named
position is witnessed neither way, and `h/as-element` has no server-render row anywhere. HS-21's third clause is
unreachable by construction (`rf2-s52w`). Each is honest about itself, which is why none of them needed a bead from
this audit — but none of them is green, and the conjunct asks for green.

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
met* one — they cannot discover evidence that is absent from the tree, and absence is what all four failing rows turn
on. Every one of them was decided by reading the tree, not by running it: two HMR suites with zero occurrences of
*lazy*, one prose hit for *axe* under `implementation/`, a `migration/` tree with no external population, four §2.1
cells with no witness cited, and three facade names with no inventory row. None of those readings would move under a
green suite. So the verdict is safe against the gap, and only the sixteen *met* rows are held on evidence this
checkpoint did not itself re-execute.

## 7. The misses

Five, all `coverage`, all filed and all rowed in [`correction-ledger.md`](correction-ledger.md).

| Finding | bd id | Why it blocks |
|---|---|---|
| Three facade surfaces with no inventory id | `rf2-2l8pw` | §7's SSR proof is a claim over a denominator that is short by three |
| HMR through the `React.lazy` bridge, unwitnessed and unowned | `rf2-y5x6j` | §7 names it in the same cell as the four states that landed |
| HS-31, HS-32, HS-34, HS-23 owe an unproved refusal, no live owner | `rf2-cfriw` | §2.1 note 3: an unproved refusal is not a disposition |
| The axe checks never landed and the routing chain terminates in nothing | `rf2-7znnl` | `completeness-audit.md` lists them among the proof suites |
| Migration's population is the in-repo corpus alone | `rf2-gqp5s` | §1.2 states in terms that this does not satisfy §7 |

`rf2-gqp5s` may well be an operator decision rather than work, and its bead says so: naming two external repositories is
a choice about what the programme points at in public, and §13 already routes real external applications through
`rf2-hic-063`'s deferred pilot row. Three dispositions are legitimate and the bead presumes among none. Only doing
nothing is wrong, because `rf2-hic-064` gates on a §7 row with no owner.

## 8. Considered, and not filed

- **`requirements-mine.md` states on three of its own rows that the nested error region did not land.** It did, in
  `rf2-hic-074`'s own commit `93f3040ea0`, and I confirmed it at source rather than inheriting it: `extension_dom_cljs_test.cljs`
  §6 asserts the inner region caught and the outer survived, then flips a page and re-asserts that nothing was healed by
  a remount; §7 drives the retry. Already filed as `rf2-gvysu`, so **no new bead and no ledger row** — a checkpoint that
  re-files another pass's finding inflates the ledger it is supposed to keep readable.
- **`git grep -w -i axe -- implementation/` returns one hit, not zero.** Both `requirements-mine.md` and
  `dispositions.md` §1.2 say it returns none. The hit is `implementation/SECURITY.md`, prose recording the *story*
  tool's axe-core CDN egress — not a witness, not a dependency, not a gate. The claim's substance is exact and its
  arithmetic is off by one prose line. Recorded here; not worth a bead, and `rf2-7znnl` will make the sentence moot.
- **Native autofill, unaddressed on all three engines.** §5 above. A platform limitation stated honestly is not a queue
  item.
- **P12's model-echo claim is not proved by what landed** and `rf2-hic-045`'s finding 1 is unmet. It is a §6
  per-keystroke row rather than a §7 coverage row, it has a live bead, and PR #8181 was open against it while this audit
  ran. Not scored as met, not filed again.
- **HS-33, HS-17, HS-18 and HS-21.** §3 above. Each is precise about what it does not hold, which is the condition for
  not needing a bead; each is counted against conjunct B, which is the condition for not being green.

## 9. What would change this verdict

Conjunct A needs `rf2-y5x6j`, `rf2-7znnl` and `rf2-gqp5s` landed — or, for the last, an operator ruling that amends §7.
Conjunct B needs `rf2-cfriw` and `rf2-2l8pw`, and then HS-33's decided repair. Conjunct C's economic half needs no work
at all: it is red by ratified decision and stays red until a shell arm lands under `1,024 B`. Its bulk half needs a
clock, which is `rf2-w01c`'s and is deferred to the measurement lane.

**The exit does not need all of that to become adjudicable, and it does need all of it to become met.** Nothing here is
large. Four of the five misses are a witness apiece, and the fifth is a decision. What none of them is, is already done.

## 10. What this record is not

It is not a re-run of the Phase 4 suites; §6 says so and files it against itself. It is not a measurement, and it
converts no counter into a time. It is not the §13 definition-of-done audit — that is `rf2-hic-064`'s and it reads this
page plus the ledger. And it is not a claim that sixteen met rows are a Phase 4 exit. They are sixteen met rows, which
is worth saying plainly and is not the same sentence.
