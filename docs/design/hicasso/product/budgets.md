# Hicasso budgets: named reference profiles and pinned baselines

This page prepares [specification §6](specification.md#6-performance-contract)
for ratification. It does not ratify anything. **Ratification authority is the
re-frame2 product operator's (Mike Thompson)**; this document records the
profiles a budget is stated *on*, the estimand/instrument/control standard each
row must meet, and the state of every baseline the later gates will anchor to.
Enforcement lives in [`rf2-hic-089`](#7-where-each-row-is-enforced) (the early
framework) and `rf2-hic-071` (the full gates), never here. What this page does
carry, since 2026-08-12, is [§9's reconciliation ledger](#9-the-budget-line-reconciliation-ledger)
— one row per registered line, each stating its own verdict — and the gate that
keeps that ledger honest. **That gate enforces the record, not the budgets**:
it can tell you a breach is unrecorded, and it cannot tell you a budget is met.

Three things are deliberately *not* decided on this page, because each is
another bead's to decide:

| Question | Owner |
|---|---|
| The R=0 shell's breach of its paper-fail line — remediate, or disposition it | `rf2-hic-018` |
| The per-read K3 record and its three non-substitutable scoreboards | `rf2-hic-070` — decided in [`k3-disposition.md`](k3-disposition.md) |
| Turning any row below into a blocking gate | `rf2-hic-089`, `rf2-hic-071` |

---

## 1. The named reference profiles

A budget without a named machine is not a budget. Two profile classes are
registered, and they are **not interchangeable**: one may source a
distributional product budget, the other may never.

### P-DEV-1 — the pinned physical profile

The operator's development machine. This is the only profile from which a
**distributional product budget** (clock, heap) may be stated.

| Field | Value |
|---|---|
| Machine | Acer Predator PHN16S-71 |
| CPU | Intel Core Ultra 9 275HX |
| Cores / threads | 24 / 24 |
| Base clock | 2,700 MHz |
| RAM | 63.4 GB |
| OS | Windows 11 Home, build 26200 |
| node | 24.13.0 |
| React | 19.2.0 |
| Playwright | 1.59.1 |

Quiet-state verification for this page's run, read from real counters rather
than from `Win32_Processor.LoadPercentage` (which has misreported 93% against a
true 11%):

```
Get-Counter '\Processor(_Total)\% Processor Time'   16.8 / 32.2 / 15.8 %
Get-Counter '\System\Processor Queue Length'        0 / 0 / 0
```

**Processor queue length is the decisive number**, because it says whether
anything was waiting for a core. It read 0 on every sample.

### CI-RUNNER-A — the hosted runner class

GitHub-hosted runners. **Correctness gates and same-run relative drift only.**
A hosted runner may never source a distributional product budget: its hardware
identity varies between runs and it is shared silicon. Where a drift comparison
is made on it, both arms must come from the *same run* and the observed
hardware/runtime identity must be recorded with the result — a floor is never
imported from another run.

### The single-profile limitation, accepted explicitly

Bead `rf2-hic-006` asks for either two physical distributional profiles (a
low-tier and a mid-tier machine) **or** an explicit record that a
single-profile limitation is accepted and why. **This document takes the
second branch, and states the cost.**

There is one physical machine in the programme. No low-tier machine exists to
measure on, and none is funded. The consequence is precise and must not be
softened: **every distributional figure in this corpus is a P-DEV-1 figure, and
carries no cross-hardware generalisation whatever.** A budget met on P-DEV-1 is
not thereby met on a low-tier machine, and this programme currently has no way
to find out. The user-visible budgets in §4 below — 50 ms p95 to next paint and
the rest — are the rows where that gap bites hardest, because they are claims
about *users*, and users are not on P-DEV-1.

Lifting it needs one thing only: a second physical machine of a named lower
tier, with the same instruments run on it. Until then the limitation is
accepted, recorded here, and inherited by every page that quotes a
distributional number.

---

## 2. The two disposition families, which §6 already separates

§6's rows are not one kind of thing, and treating them as one is how a
measurement programme gets both halves wrong. The specification draws the line
itself:

> Deterministic correctness, residue, scaling-shape, and production-erasure
> gates block ordinary changes. Noisy clock/heap distributions are adjudicated
> in pinned, interleaved evidence runs rather than converted into flaky PR
> thresholds.

That sentence sorts every row below into one of two families, and the families
have opposite operational rules:

| | **Deterministic** | **Distributional** |
|---|---|---|
| Value | integers — body counts, residue counters, shape | bytes, milliseconds |
| Machine sensitivity | **none** — a counter reads the same on a loaded box | high — needs a quiet window |
| Where it belongs | ordinary blocking PR gates | pinned interleaved evidence runs |
| Profile needed | any | P-DEV-1 only |
| Anchored on `implementation/hicasso` today? | **yes** | **the heap rows S1–S5, yes** — since 2026-08-12, §6; the clock rows, no |

The practical consequence, and the reason it is worth naming: **the
deterministic family did not need this measurement window at all.** A body
count is a delta on a monotone integer counter; contention cannot move it. Only
the distributional family needs the quiet box — which is exactly why this page
could not re-pin that family when it was written, and why the re-pin it refused
had to be taken later, alone, in a window of its own.

---

## 3. Deterministic rows, pinned on the moved package

`D1`–`D25` are pinned **on `implementation/hicasso`** — the moved package
itself, not a donor — at commit `0c0aa22898`, through the supported test-kit
facade `re-frame.hicasso.test.mounted`. No `impl/` reach-through is involved.
**`D26` is the one row in this section taken on the bench tree**, and the note
below it says why that is the only place it can be taken.

The instrument for body counts is [`hm/bodies-run`][kit], which is page-wide,
handle-free, and takes its reading as a delta on the runtime's monotone
counter. Its docstring names specification §6's narrow-update row as the budget
it exists to make assertable.

[kit]: ../../../../implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs

| # | Estimand | Figure | Witness | Control that moves it |
|---|---|---:|---|---|
| D1 | Boundary bodies run per keystroke, 5×5 grid | **2** | `grid/scaling_dom_cljs_test` | D2 — same measurement at 10×10 |
| D2 | Boundary bodies run per keystroke, 10×10 grid | **2** | `grid/scaling_dom_cljs_test` | D3/D4 — coarse shape moves it to 26/101 |
| D3 | Same, coarse shape with closure props, 5×5 | **26** | `grid/scaling_dom_cljs_test` | D1 — the fine shape reads 2 |
| D4 | Same, coarse shape with closure props, 10×10 | **101** | `grid/scaling_dom_cljs_test` | D3 — quartering the grid moves it to 26 |
| D5 | Bodies run for a *refused* keystroke | **0** | `grid/scaling_dom_cljs_test` | D1 — an accepted keystroke reads 2 |
| D6 | Bodies run for `clear-row` on a 10-wide row | **11** | `grid/scaling_dom_cljs_test` | D5 — a refused edit reads 0 |
| D7 | Bodies run, first keystroke of an editor session | **2** | `editor/flow_dom_cljs_test` | D8 — the second keystroke reads 1 |
| D8 | Bodies run, every keystroke after the first | **1** | `editor/flow_dom_cljs_test` | D7 — the session's first reads 2 |
| D9 | Teardown residue in counters and frame ids | **zero** | `hm/assert-clean!`, every DOM witness | its own pre-mount baseline delta |
| D10 | Hiccup-walk entries a direct return drops (`codec/vec->element`) | **3** | `direct_return_cljs_test` | write the same body as hiccup — reads 4 against a floor of 1 |
| D11 | Prop-pipeline entries a direct return drops (`codec/convert-props`) | **3** | as D10 | as D10 — reads 3 against a floor of 0 |
| D12 | Event-lowering entries a direct return drops (`intent/lower-prop`) | **2** | as D10 | as D10 — reads 2 against a floor of 0 |
| D13 | Controlled-repair entries a direct return drops (`controlled/install!`) | **3** | as D10 | as D10 — reads 3 against a floor of 0 |
| D14 | Wrappers between the element type React reconciles and the author's own function, declared island | **0** | `three_way_parity_cljs_test` | the `:client-only` default answers a gate instead; UIx's route answers a generated component |
| D15 | Slots on the props object React carries, and they are the author's own | **1** | as D14 | UIx's route also reads 1 — but the slot is `argv`, which the author never wrote |
| D16 | Unwrapping hops per render, per component, native and handwritten React | **0** | as D14 | the UIx route reads 1 — the `argv` carrier being opened |
| D17 | Subscription recomputations per keystroke, four-field editor | **10** | `per_keystroke_dom_cljs_test` | D20 — a refused keystroke reads 0 |
| D18 | Subscription recomputations per keystroke, grid 5×5 | **31** | as D17 | D19 — the same measurement at 10×10 reads 111 |
| D19 | Subscription recomputations per keystroke, grid 10×10 | **111** | as D17 | D18 — quartering the grid moves it to 31 |
| D20 | Subscription recomputations for a *refused* keystroke | **0** | as D17 | D19 — an accepted keystroke reads 111 |
| D21 | Glass writes (`value` property) for an *accepted* keystroke | **0** | as D17 | D22 — a refused keystroke reads 1 |
| D22 | Glass writes for a *refused* keystroke | **1** | as D17 | D21 — an accepted keystroke reads 0 |
| D23 | DOM mutation records per keystroke, editor | **7** | as D17 | D25 — a refusal reads 3 |
| D24 | DOM mutation records per keystroke, grid | **8** | as D17 | D23 — the editor reads 7; the grid's extra one is the row total's text node |
| D25 | DOM mutation records for a *refused* keystroke | **3** | as D17 | D23 — an accepted keystroke reads 7 |
| D26 | Rows of markup built for a one-row write, fine topology | **1** at `B` = 100, 300 and 1,000 | `topo/census_dom_cljs_test` | the coarse arm builds `B` — 100, 300, 1,000 |

**These figures are cited, not re-derived.** D1–D8 were established by the two
witness apps that landed on `main` ahead of this page; re-deriving them here
would create a second source for one number and therefore a second thing to
drift. What this page adds is the same-instrument confirmation that they hold
at `0c0aa22898` (§8), and the control column above.

**On D1/D2 — the acceptance row.** Quadrupling the mounted cells changes
nothing, which is §6's *narrow-update body work scales with changed rows rather
than all mounted rows*, measured rather than asserted. D3/D4 are the positive
control that gives the row its meaning: the same app in a coarse shape scales
26 → 101 with the grid, so the instrument demonstrably *can* see amplification
and D1/D2's flatness is a property of the topology, not of a blind instrument.

**On D10–D13 — the direct-return delta, deterministic half (`rf2-hic-033`).**
Specification §5's Rung 3 lets a `defview` body answer an already-constructed
React element and skip hiccup lowering for that result. D10–D13 price what that
skips, on one page written twice — same props, same two ambient reads, same
data, and DOM that is **byte-equal** under React's own server renderer — by
counting entries into the four shipping functions React would otherwise have
reached. Every count is read against the crossing's own **floor**, not against
zero: a boundary is reached through a hiccup vector whatever its body answers,
so the crossing is still walked and only the body's own lowering is gone.

Two departures from this section's stated method, both deliberate:

- **These rows reach through `impl/`.** There is no test-kit facade for *how
  many times did the codec walk*, and inventing one would be a public export
  bought to make a measurement quotable. The counts are taken by wrapping the
  shipping var and restoring in a `finally`, which is the convention
  `walk_profile_baseline_cljs_test` already established for exactly this
  question.
- **They are counts, not clocks**, so they are deterministic and carry no
  hardware profile. That is what lets them sit in this section rather than §4,
  and it is also their limit: **they say what work is skipped, never how long
  that work took.**

**The clock half of the direct-return delta is TAKEN, and it is `S8` in §4**
(`rf2-5yn9`, 2026-08-13). Nothing in D10–D13 licenses a claim about how much
faster a direct return is — a count says what work is skipped, never how long
that work took — so a reader wanting the remedy's price reads `S8` and not
these rows. The two halves are different instruments on the same pair, and
neither re-derives the other: D10–D13 count entries during one server render;
`S8` clocks the same pair mounted in a browser.

**`S8`'s fairness gate is the STRONGER one, and that is worth reading back into
this section.** The rows above are settled on markup under
`renderToStaticMarkup`, which this section states as their limit — it says
nothing about a mounted node's properties or about what a commit does. `S8`
mounts both arms and compares their canonical DOM, attribute names sorted, and
the two pages agree exactly, at 601 elements and 23,004 bytes, on every run.
The equality D10–D13 could only assert in markup therefore holds in the
document as well.

**Half of §6's obligation is REFUSED, at source, and the refusal is not a
scheduling problem.** Specification §6 asked for a pinned interleaved run on
both named reference profiles. The CI-RUNNER-A half cannot be published here
under this page's own rules: §1 registers that profile class for *correctness
gates and same-run relative drift only* and states that a hosted runner **may
never source a distributional product budget**, and §9's gate mechanises it —
the lane vocabulary a distributional row is allowed to name carries no
CI-RUNNER-A value at all, by construction. `S8` is therefore a single-profile
P-DEV-1 figure like every other distributional row on this page, and it
inherits §1's single-profile limitation whole.

**That refusal is RATIFIED, and the obligation is DISCHARGED P-DEV-1-only**
(Ruling 1, 2026-08-13, `rf2-5yn9`). Reconciling §6's wording with §1's rule was
a ruling rather than a measurement, and here it is: **a distributional clock
obligation runs over all registered *physical* distributional profiles — today
exactly `P-DEV-1`.** The CI-RUNNER-A half is closed by refusal on the two
grounds above, neither of which more machine time would move. Reading a hosted
run as same-run relative drift was considered and rejected: §9's lane
vocabulary has nowhere to put such a number, so it could never enter the
ledger, shared silicon would yield a band that decides nothing, and the
precedent would owe every distributional row a CI shadow reading. Funding a
second physical machine is an operator hardware decision, and `S8` did not
change its urgency. **If a second physical distributional profile ever
registers, this ruling reopens automatically** on §1's standing invitation —
and [specification §6](specification.md#6-performance-contract) now states the
obligation in these terms, so the contradiction that forced the ruling cannot
resurface.

**On D14–D16 — the island band's structural half (`rf2-hic-034`).** These read the
construction cost of a native island as a structural fact rather than as a clock,
over one subject written three times: a cell that paints a label, as a declared
native island, as a handwritten React component, and as a UIx component. D14 is
the element type each route hands React — the native tier's is `identical?` to the
author's own function, and so is the handwritten arm's, so nothing sits between
React and the body. D15 reads the props object React then carries: one slot on
every route, and on those two arms it is the author's own `label`. D16 is what the
first two make of the third. UIx's element type is a generated component and its
one slot is `argv`, a carrier `uix/$` builds around the map the author wrote, so
that route pays one unwrapping hop per render per component where the other two
pay none.

**This is a stronger reading than a timing, not a weaker one.** A declared island
and a handwritten React component are the same element type carrying the same
props object, so there is no interposed work for a stopwatch to find — a thing a
counter can decide and a clock could only fail to detect. It is also why §6
requires the native tier to be co-instrumented against handwritten React and not
against UIx alone: a floor set by UIx would have D16's hop inside it.

Three qualifications, in the order a reader meets them. **The band is stated over
the DECLARED arm** — since `rf2-hic-046` the `:client-only` default answers a gate
rather than the author's function, costing one fiber and one hook the declared arm
does not, and that is the ruled price of a conservative default rather than a
figure about `n/defcomponent`. **They are counts and identities, not clocks**, so
like D10–D13 they carry no hardware profile, which is what lets them sit in this
section rather than §4. And **they are the deterministic half of C7 only**: they
say there is no interposed work, never how long a render takes, so C7 stays
`UNPINNED` and its clock half remains `rf2-hic-071`'s, along with the ladder re-pin
and the package-resident clock instrument that half needs.

**On D17–D25 — the per-keystroke census (`rf2-hic-045`).** These are the
remaining five stages of one keystroke, counted on the two public-package
witness applications and published in
[`per-keystroke.md`](per-keystroke.md). They are registered here rather than
left on that page because they are the only figures in the corpus that **scale
with the mounted page**, and this section is where a reader finds out which
budgets do. Their instrument is not `hm/bodies-run`: a counting wrapper on the
registrar's `:handler-fn` reads the subscriptions, a spy on the `value`
property setter of the input prototypes reads the glass, and a
`MutationObserver` over the container reads the commit — each installed around
the mount and removed in a `finally`, each named in that page's §1.1.

**Two of these nine rows are readings of a LINEAR quantity, and the ledger says
so rather than implying a flat one.** `D18` and `D19` are the same estimand at
two mount sizes: subscription recomputations go `31 → 111` as the grid goes
`5×5 → 10×10`, following `rows × cols + rows + 1`. **The mount size is part of
the registered line**, and the pair is registered rather than a single figure
precisely so that neither can be read as a ceiling that holds at another size.
`D17`'s `10` is likewise the editor's ten live layer-1 cells and not a
constant: change the page and the figure changes with it, which is the point.
The other seven — the refused keystroke's `0`, the two glass writes and the
three mutation-record counts — are flat, and `D24` against `D23` is what makes
that assertable rather than assumed. **What makes all nine gateable on a hosted
runner is that they are integers on monotone counters, not that they are
small**; a linear figure taken at a stated size reads the same on a loaded box
exactly as a flat one does.

**The contrast these rows exist to keep visible.** One keystroke in the
hundred-cell grid runs **111 subscription bodies to run 2 view bodies**
(`D19` against `D2`). The read topology buys narrow *notification* — which is
what `D1`/`D2` measure and what keeps the page from re-rendering — and it does
not buy narrow *recomputation*. Registering only the flat half of that would
have made the ledger say something the census does not.

**On D26 — the second counter `U5`'s estimand was missing (`rf2-mwr2`).**
`rf2-hic-036`'s topology tournament measured a coarse view-model arm that
**rebuilds all `B` rows for a one-row change and runs exactly one boundary
body**, because it does its `B` rows *inside* that body. Read against `U5`'s
registered estimand — boundary bodies run — that arm **passes**, at every row
count, untunably: the very behaviour `U5`'s English forbids. `D3`/`D4` do not
already cover it. They catch a coarse shape because *their* witness keeps
per-cell boundaries to count; a coarse shape with no boundaries beneath the
family has nothing for that instrument to see.

**The repair is a second counter, not a wider line.** `U5`'s registered line is
untouched and no threshold moves. What changes is that the claim is now decided
on **two** instruments — bodies run (`D1`–`D4`) *and* rows of markup built
(`D26`) — and [§9's gate](#9-the-budget-line-reconciliation-ledger) refuses a
row that states a scaling claim and names only one. The counter was already
built and already cross-checked when this row was registered: the tournament
increments `:markup` inside the single shared `row-markup` fn every arm calls,
and its census agrees with `arm1.runtime/body-runs` — two instruments sharing
no traversal — on all 48 cells.

**`D26` is a `bench-tree` figure, and that is the honest population rather than
a convenience.** The tournament's four arms are written on
`re-frame.bench.hicasso.arm1`, the prototype runtime, whose own docstring
places it *"off every production source path"*; the package ships one topology
and cannot mount four. So the subject is the bench tree, and this column names
the subject. **The package's own witness reaches the same limit from the other
side and already records it**: `scaling_dom_cljs_test`'s
`the-coarse-shape-with-scalar-props-is-INVISIBLE-to-this-instrument` reads `2`
at 25 cells and at 100 for a coarse shape whose props are plain values, and
says in terms that *"this gate CANNOT distinguish the two shapes"* while that
shape allocates `N` elements and compares `N` props maps per keystroke. Two
witnesses, two populations, one hole — and `D26` is the counter that closes it.

**`D26`'s own figure is flat and its control is linear**, which is the way
round a scaling row should read: the fine topology builds **one** row of markup
for a one-row write at `B` = 100, 300 and 1,000, and the coarse arm builds `B`
— a factor of a thousand at the largest size, on an exact integer counter with
no interval attached because none is needed. The chunked arm builds `k` = 25
and the windowed arm builds 1; both are recorded
[on the tournament page](topology-tournament.md#22-the-rung-2-teaching-table--rows-of-markup-built)
and neither is registered here, because a stated arm constant is a setting and
not a result.

**On D9.** This is teardown residue in **counters and objects**, which is a
deterministic reading the package can make. It is *not* S5, the teardown row
stated in **retained bytes** — a distributional figure, taken in its own quiet
window and re-pinned on the package only on 2026-08-12. The two must not be
conflated: a mount can leave zero residue counters and still retain bytes, and
the two halves are not interchangeable evidence even now that both are package
figures. D9's counters are **exactly** zero; S5's bytes are *indistinguishable
from* zero, which is the strongest form a distributional reading takes.

---

## 4. Distributional rows — S1–S5 re-pinned on the package, S6–S7 carried

**S1–S5 are package figures as of 2026-08-12.** They were re-measured on
`implementation/hicasso` in one solo quiet-window run, on the same P0 ladder
`rf2-hic-006` had to refuse — repointed at the package by PR #7939 and read
through by `rf2-fe0l`. **S6 and S7 are still carried unchanged** from
[the evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence)
and are **not** package figures: no package-resident clock instrument exists,
and the allocation row has no publishable claim to re-pin.

| # | Estimand | Pinned figure | Instrument | Status |
|---|---|---|---|---|
| S1 | R=0 boundary shell, Reagent segment | `1,100 B` [1,091–1,107] | P0 ladder, **package** candidate arm | **package figure** — over the frozen `1,024 B` line in every round |
| S2 | R=0 boundary shell, UIx segment | `1,095 B` [1,087–1,101] | as S1 | **package figure** — over `1,024 B` in every round |
| S3 | Per-read retained, Hicasso vs Reagent | `1,417` vs `948 B/read` | as S1 | **package figure**; K3 scoreboard (a) — dispositioned by [`rf2-hic-070`](k3-disposition.md#3-scoreboard-a--governed-viability-against-the-best-shipped-path) |
| S4 | Per-read retained, Hicasso vs UIx | `2,115` vs `2,980 B/read` | as S1 | **package figure**; K3 scoreboard (b) — dispositioned by [`rf2-hic-070`](k3-disposition.md#4-scoreboard-b--architecture-progress-against-the-uix-parent) |
| S5 | Teardown, retained **bytes** | indistinguishable from zero — all ten candidate rungs' bands straddle 0 | as S1 | **package figure** |
| S6 | Cold mount vs direct UIx-on-subs | `1.1718x` [1.1263–1.2190] n=8; `1.1976x` [1.1504–1.2468] n=6 | final K1 estimator | **bench-tree figure** — registered `1.10x` gate missed; `1.25x` is the *accepted price* for that miss (ratified 2026-08-13), never a line this row is judged on |
| S7 | Warm allocation | no publishable claim | allocation instrument | **bench-tree** — no fitted series clears the quality floor |
| S8 | Direct-return escape (Rung 3): mount time against the same page written as hiccup | `0.7418x`, per-round escapes [0.6458–0.8409] over run 1's five rounds — **25.8% of mount time recovered** [15.9–35.4%]. Run 2 is excluded: its positive control refuses under the strict rule | P0 lane direct-return clock arm | **package figure** — an observed range, not a confidence interval; it excludes 1.0 and it CROSSES C8's 20% line |

The run's evidence, its controls and its full provenance are
[on the ladder's studio page](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l);
what follows is only what the re-pin does to this page's rows.

**S3 is the one figure that moved, and the instrument says it is the
candidate's.** Nine of the ten quantities the run took — both donors, both
floors, both shells and the UIx-segment slope — came back on the prototype's
published anchors. S3's candidate slope did not: `1,278 → 1,417 B/read`,
`+139 B` and `+10.9%`, with the two bands disjoint. The governed contrast
against Reagent therefore deepens from `1.3492×` to `1.4953×`, while S4's win
against UIx is unchanged to the fourth decimal (`0.7099× → 0.7098×`).
**Attribution was deliberately not attempted in that window** — its terms are a
single invocation, and the ablations that would name a cause are exactly what a
measurement window may not run. It was taken afterwards, by `rf2-l50z`, and the
answer is **one line**: `interop/activate-derived-value!` in the collector's
`wire-cell!`, a ratom-only correctness repair that landed between the two
sessions. **The figure does not move** — S3 stays `1,417 B/read` — because the
attribution names the cost rather than removing it. The bisection is
[on the ladder](../studio/reads-per-boundary-heap-ladder.md#the-139-bread-attributed-to-one-line-and-the-premise-it-had-to-correct-first-rf2-l50z);
the disposition is [substrate-decision §6](substrate-decision.md#6-what-this-page-does-not-decide).

**S1 and S2 carry the substantive news for the shell.** The breach is not an
artefact of the prototype — it survives the move to the package essentially
unchanged, at `1,100` and `1,095 B` against `1,103` and `1,097 B`.

**S6 carries a standing prohibition, and ratification did not lift it.** The
`1.25x` cold-mount ceiling was ratified and made operative on 2026-08-13 by
operator ruling rather than by the sitting it was held for
([`k1-price-acceptance.md`](k1-price-acceptance.md)). It prices S6's miss; it
does not clear it. The registered `1.10x` line is still the only adjudicated
one, S6 stays `BREACH` against it, and **no evidence row may use the accepted
ceiling to mark K1 green.**

**S8 prices the Rung 3 escape, and the answer is that it is real and that it
does not by itself clear C8.** The witness is `direct_return_cljs_test`'s pair —
one page written twice, same props, same two ambient reads, same data — at 200
boundaries, mounted inside one `flushSync` window. The escape recovers
`25.8%` of the mount, per-round escapes running `[15.9–35.4%]`, and that range
excludes 1.0, so it is a real effect rather than a null. Read against
[C8](#the-comparative-and-regression-rules) it decides nothing on its own:

- **`≥ 20% recovered`** — the point estimate clears the line and **the range
  crosses it**. Under the rule the operator adopted with §5's freeze, an
  interval that crosses a line is UNRESOLVED and not a pass. So is this one,
  and §9's gate is what holds it there.
- **`≥ 2 ms p95`** — **UNASSESSED at this witness.** The instrument records six
  samples per round, reduces each round to a `p50`, and publishes the min and
  max across those round medians. **It never computes a `p95` at all**, and a
  threshold nothing was measured against cannot be met or missed. Assessing
  this disjunct needs an instrument that reports a `p95`, and building one is a
  rig change with its own window rather than a reading of this one.
- **converting a failed user-visible budget** — NOT ASSESSABLE. U1–U4 are
  `UNPINNED` on the package, so there is no failed budget available to convert.

**What that means for an author, which is the only form of this figure worth
acting on.** C8 is a rule about *an escape at a site* — an island missing its
threshold is simplified or removed — so one witness at one size cannot pass or
fail it corpus-wide, and S8 does not try to. What S8 gives is the price an
author measures their own site against: at this size the escape is worth about
a quarter of the mount, and the author has to decide whether a quarter of
*their* mount is a number their users can feel. **No per-boundary rate and no
break-even boundary count are published, and one witness is why.** A single
200-boundary reading fixes no scaling law — dividing it out would assume the
per-boundary cost is linear, which nothing here establishes — so an author with
a differently sized site measures it rather than extrapolating from this row.
**The escape's other prices are not on the clock at all** and both are already
recorded: it leaves the L2 assertion tier, and it gives up controlled-input
repair and intent lowering inside the returned element (§3's D10–D13 note, and
the pair's own source).

**S8 is one witness, one run, and the estimand is narrower than a band.** What
the row publishes is the **min and max across five per-round readings — each of
them a ratio between that round's floor-normalised `p50`s, and each `p50` the
median of six samples. That is an observed range, not a confidence interval**,
and nothing on this page may read it as one. Two runs
were taken back to back on the same binary with nothing changed
between them, reading `0.7418x` `[0.6458–0.8409]` and `0.7982x`
`[0.6909–0.8421]`. **Only run 1 is published.** Run 2's positive control
predicted `4.000` with a ±25% band of `[3.000–5.000]` and its rounds reach
`5.150`, so it refuses under the strict every-round-inside rule —
`lane/control-verdict`'s own docstring names its overlap `:ok?` rule the lane's
known defect and the strict rule the right one, and an ensemble whose control
refuses cannot contribute to a published figure. On run 1 the arm-order guard
returned `reportable`, the positive control predicted `4.500` and measured
`4.300` `[3.950–4.950]` — every round inside `[3.375–5.625]` — and all 225 of
its measured mounts were read back out of the document at their own far end.

### The §6 user-visible budgets

Transcribed with their estimands. All are P-DEV-1-only and all inherit §1's
single-profile limitation.

| # | Budget | Estimand | Family |
|---|---|---|---|
| U1 | Controlled updates correct same-turn, echoed within one 60 Hz frame at p95 | latency to visible echo | distributional |
| U2 | Ordinary discrete interactions reach next paint within 50 ms p95 / 100 ms p99 | latency to next paint | distributional |
| U3 | Broad application operations within 100 ms p95 unless classified background | operation latency | distributional |
| U4 | Dragging/animation stay inside frame budget | per-frame latency | distributional |
| U5 | Narrow-update body work scales with changed rows, not all mounted rows | boundary bodies run **and rows of markup built** | **deterministic** — pinned as D1–D4 and D26 |
| U6 | Teardown residue is zero after quiescence | residue counters / retained bytes | **split** — D9 deterministic, S5 distributional |

U5 and the deterministic half of U6 are pinned on the package today. U1–U4 are
not, and cannot be until a package-resident clock instrument exists.

**`U5`'s estimand carries two counters as of 2026-08-14 (`rf2-mwr2`), and its
line is unchanged.** Registered on bodies alone it read `PASS` on a coarse
view-model arm that rebuilds all `B` rows for a one-row change, because that
arm does its `B` rows inside one body — the exact behaviour the *Budget* column
above forbids. Adding `D26`'s rows-of-markup counter beside `D1`–`D4`'s bodies
is what closes that, and it is a **second counter and not a wider line**: no
threshold moved, and a coarse arm that is genuinely cheap still passes both.
The measurement, the four-arm table and the hole's shape are
[§3's `D26` note](#3-deterministic-rows-pinned-on-the-moved-package) and
[the tournament's §2.5](topology-tournament.md#25-u5-and-an-instrument-gap-in-it-that-this-tournament-exposed).

### The comparative and regression rules

The eight rules carry ids so that §9's ledger can reconcile each one by name.
The ids are this table's, not a second register: a rule is registered *here*
and its verdict is recorded there.

| # | Rule | Disposition |
|---|---|---|
| C1 | Pinned ordinary-Hicasso benchmark does not regress > 5% on same witness and instrument | same-instrument regression **blocks** until the benchmark owner validates the instrument and the adapter owner fixes or reverts |
| C2 | Cold mount ≤ 1.25x direct UIx (ratified 2026-08-13) | accepted price, not a gate; the `1.10x` registered line stands and C2 is judged on it |
| C3 | Broad updates ≤ 1.25x best relevant adapter after topology tuning | 1.25–1.5x is a **warning band**: attribute cause, one bounded topology pass, test a local island |
| C4 | Sustained > 1.5x | cannot graduate as ordinary Hicasso until fixed or deliberately classified a native-host use case |
| C5 | R=0 shell meets the frozen byte-exact `1,024 B` line | **not** governed by baseline-plus-10%; see §5 |
| C6 | Per-read retained ≤ 10% regression on same pinned witness | governed by the K3 disposition (`rf2-hic-070`) |
| C7 | Native island within 5% or 1 ms of the same component mounted directly | co-instrumented against both handwritten React and UIx |
| C8 | An escape **taken for a benefit** recovers ≥ 20%, saves ≥ 2 ms p95, or converts a failed budget to a pass. An **interoperability** escape — one whose alternative is not a slower spelling but no spelling at all — is outside the population | an island missing its threshold is simplified or removed — **thresholds do not widen to keep it** |

---

## 5. The read-free boundary shell: the byte-exact line, now FROZEN at 1,024 B

> **RULED, 2026-08-12 (operator-directed, operator-overturnable).** The paper-fail
> line is frozen at the literal **`1,024 B`**. Spell it that way everywhere; the
> ambiguous `1 KB` spelling retires. Adopted with it: **a confidence band that
> crosses 1,024 B is UNRESOLVED, not a pass**, and no substrate is selected on a
> point-estimate difference inside that band. The ruling text is on `rf2-fe0l`.
>
> The rest of this section is the record of *why* the freeze was needed and
> what it does and does not decide. It is kept as written — the recommendation
> below is the reasoning the ruling adopted, not a live question — because a
> page edited to look as though it had always known the answer stops being a
> record of how the answer was reached.

> **AMENDED 2026-08-13 — the breach is ACCEPTED, SCOPED. The line is UNMOVED.**
> The separate prospective disposition
> [`specification.md` §6](specification.md#6-performance-contract) requires —
> the one [the substrate decision's §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition)
> refused to draft and handed up — has been taken on `rf2-0xx2`. It is a
> **delegated ruling**: recorded by a decision agent under operator-authorised
> delegation after the reviewed dossier, and **reversible** by the operator. It
> is written here because this is the section the ledger rows `S1`, `S2` and
> `C5` are dispositioned into.
>
> **Read first what it does not do.** The `1,024 B` line frozen above stays
> registered, and stays at `1,024 B`. No figure on this page moves and no
> threshold widens. `S1`, `S2` and `C5` stay `BREACH` in §9's ledger — **red,
> now carrying a disposition** — because this record *prices* the breach and
> does not *pass* it. That is the K1 pattern: acceptance of a price is not a
> pass. Nor does it licence engineering: the propagation is **records-only** —
> do not remove the wrapper, do not adopt the frame-prop shell, do not
> introduce a `:memo` configuration, do not commission byte-shaving to change a
> ledger colour.
>
> The five fields `specification.md` §6 requires, as ruled:
>
> - **REASON**: Retain HD-028's value-equality memo wrapper as the internal
>   default: it removes measured parent-driven body cascades (under HD-006, one
>   page-chrome write re-rendered 300 value-equal card boundaries) without an
>   author-facing switch, and meeting 1,024 B today would require overturning
>   that ruled, evidence-driven default — the "breaking a higher-order law"
>   condition §6 sets. The registered estimand prices the design at the one rung
>   it is deliberately worst at: per-boundary machinery paid by a boundary that
>   reads nothing, the same structural choice that wins −692 B on every read
>   against the spine donor. The line stays registered at 1,024 B precisely
>   because it is reached by the unwrapped shell (994/992 B) — unreached, not
>   unreachable. This record prices the breach; it does not pass it (the K1
>   pattern: acceptance of a price is not a pass).
> - **CEILING**: Unchanged at 1,024 B. The acceptance covers the measured
>   breach only: up to 1,107 B (Reagent segment) and 1,101 B (UIx segment) — the
>   pinned bands' upper edges on the same instrument. These are acceptance
>   bounds, not replacement pass lines. Any same-instrument reading beyond
>   either bound is an unaccepted, undispositioned plain red this record cannot
>   be cited against.
> - **EFFECTIVE REVISION**: The 2026-08-12 package re-pin (rf2-fe0l, PRs
>   #7939/#7941; S1/S2 = 1,100/1,095 B as pinned in budgets.md §4; package
>   evidence anchor ce31a30b77). Operative from this ruling (2026-08-13), and
>   only while the shell, wrapper, comparator, React version (19.2.0), build
>   mode, and measurement contract remain materially equivalent.
> - **REOPEN CONDITIONS**: The next measured shell arm on the package (any
>   S1/S2 re-take); HD-028 reopened, amended, or overturned; any
>   substrate-decision §3 reopen condition firing; any material change to the
>   effective-revision inputs; a package A/B showing a cheaper design with the
>   same correctness and authoring properties; or real application/pilot
>   evidence that fixed shell retention is a meaningful constraint. NOT a
>   reopen: the mere existence of an unmeasured byte-saving idea.
> - **REVERT TRIGGER**: The acceptance is deleted — never kept as a floor — the
>   moment a qualifying shell arm lands under 1,024 B on the package: full band
>   at or below 1,024 B on BOTH canonical segments, with the
>   memo/correctness/browser witnesses preserved (equal-prop bail-out,
>   subscription/context propagation, changed-prop rendering, teardown). The
>   registered line then governs without exception and the Phase 1 exit's first
>   disjunct is met. Conversely the acceptance lapses immediately if a
>   same-instrument reading exceeds the scoped ceiling, whereupon the
>   undispositioned plain red resumes.
>
> The evidence bar for any later claim that the exception can be removed is one
> production-build package A/B/A run carrying the existing known-size, donor,
> floor, structural and arm-order controls, plus the mounted package witnesses
> named in the revert trigger. A point estimate is not sufficient, and neither
> is a green ledger-consistency gate. The third closure path — a shell arm
> landing under the line — stays live and deliberately unowned.

### What the package reading does to this row

The shell has now been measured on `implementation/hicasso` itself
([the run](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l)),
and the freeze is **not load-bearing for the present verdict** on either tree:

| R=0 shell | Reagent segment | UIx segment | worst round | vs `1,024 B` |
|---|---:|---:|---:|---|
| pinned baseline row (bench tree) | 1,103 B | 1,097 B | — | over |
| ladder re-take on the shipping bench tree | 1,099.5 B | 1,097 B | — | over |
| **the package (S1 / S2)** | **1,100 B** | **1,095 B** | 1,091 / 1,087 B | **1.074× / 1.069× — over, in every round** |
| frame-prop variant (bench tree) | 1,054 B | 1,051 B | 1,047 B | over |

Every one of the twelve package readings sits at or above **1,087 B**, so the
row is red under the frozen reading, red under the retired 1,000 B one, and
**not a band-crossing case at all**. What the freeze forestalls is still ahead:
the no-wrapper arm's `994` / `992 B` sits exactly in the window the two readings
disagreed about, so a remediation landing there would have been decided by a
coin-flip. It no longer can be.

**The breach is a property of the design, not of the prototype.** That is the
substantive finding the package re-pin adds, and it is `rf2-0xx2`'s to
disposition.

### The record: why the freeze was needed

`rf2-hic-006` asks this page to resolve, **from the registered instrument's
source of record**, whether the paper-fail line is 1,000 B or 1,024 B.

**The source of record is [`validation.md`](../validation.md), and it does not
resolve the question.** Its row reads, in full:

> Exclusive retained per boundary (**the R=0 boundary shell**) — target
> ~0.4–0.5 KB; > 1 KB fails on paper.

That is the registered text. It says `1 KB` and defines no byte expansion, and
no other registered document supplies one. The heap ladder — the instrument's
own studio page — does not assume one either; it deliberately reports **both**
readings side by side wherever the answer could turn on it. **So the honest
finding is that the byte-exact interpretation was never frozen, and this page
cannot discover a freeze that does not exist.** Freezing it is an operator
ruling, and it is put here rather than invented.

### The freeze is not load-bearing for today's disposition

It matters that this is recorded without overstating its urgency. **The current
row is red under either reading**, so the freeze changes nothing about the
present breach:

| R=0 shell, shipping (wrapper) arm | Reagent segment | UIx segment | vs 1,000 B | vs 1,024 B |
|---|---:|---:|---:|---:|
| pinned baseline row | 1,103 B | 1,097 B | over | over |
| ladder re-take on the shipping tree | 1,099.5 B | 1,097 B | 1.10x | 1.07x |
| frame-prop variant | 1,054 B | 1,051 B | 1.05x | 1.03x |

Where the freeze **will** become load-bearing is remediation. The ladder's
no-wrapper arm reads **994 B / 992 B** — 6 B and 8 B under 1,000, inside a
per-round band that straddles the line, and well inside the ~75 B
component-shape sensitivity `validation.md` states for it. A remediation
landing anywhere between 1,000 B and 1,024 B would be *green under one reading
and red under the other*, decided by a coin-flip nobody has called. That is
precisely the case the freeze exists to forestall, and it is now reachable.

**Recommendation to the operator, not a ruling: freeze it at 1,024 B**, on the
grounds that `KB` in a memory context conventionally expands to 2^10 and the
instrument reports raw bytes either way. Recorded as a recommendation; the
decision is the operator's.

> **This recommendation was adopted on 2026-08-12, on these grounds.** See the
> ruling at the head of this section. The sentence above is left standing as
> the reasoning that was put to the operator, not as a live question.

### The breach is carried, never normalised

The current breach stands at **R=0 = 1,100 B / 1,095 B on the package**, against
the frozen `1,024 B` line. It is a **live pressure owned by `rf2-0xx2`**, not a
disposition this page may make — the substrate arm's disposition has since been
taken, and it is [the substrate decision record](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition)'s:
the substrate arm is refused on the evidence, the breach is carried red, and the
line is not re-registered. The row here is never silently normalised into a
percentage allowance — §6 says in terms that a relative regression allowance
cannot recolour the red shell row.

**[Amended 2026-08-13.]** The pressure stands and every figure above is
unchanged. What has changed is that the pressure now carries a **disposition**:
`rf2-0xx2`'s scoped acceptance, recorded in this section's head-note on the five
fields `specification.md` §6 requires. Read the difference precisely — the
ceiling is still `1,024 B`, the acceptance reaches only as far as the measured
breach (`1,107 B` Reagent, `1,101 B` UIx, the pinned bands' upper edges), it is
deleted rather than kept as a floor the moment a shell arm lands under the line,
and `S1`, `S2` and `C5` stay `BREACH`. A priced breach is not a normalised one,
and the percentage allowance §6 forbids is still forbidden.

One drift is recorded rather than smoothed. The baseline's pinned Reagent
figure was `1,103 B`; the ladder's later paired re-takes on the shipping bench
tree read `1,101 B` and `1,099.5 B`, and the UIx segment reads `1,097 B` in all
of them. The arms overlap in band and the ladder page itself notes the
difference. Those rows are left exactly as measured, because a measurement
record edited to match a later run stops being a record. **What has changed is
which of them this page pins**: the package re-pin (§6) has now happened, so
S1/S2 are `1,100` and `1,095 B` and the earlier figures are the bench-tree
lineage behind them rather than the live rows. That the package reading landed
within 3 B and 2 B of the bench-tree one is a result, not a formality — it is
the reason the whole prior lineage remains readable as evidence about the same
design.

---

## 6. The re-pin on the moved package: REFUSED, then DISCHARGED

> **DISCHARGED 2026-08-12 by `rf2-fe0l`.** The refusal below stands as the
> record of why this page could not deliver the re-pin, and every word of its
> diagnosis held: no heap instrument pointed at the package, and the registered
> one had drifted from its own pin. Both were fixed in the order this section
> asked for. PR #7939 repointed the existing P0 ladder's four candidate seams at
> `re-frame.hicasso` — **reusing the driver, donors, floor, harness, fixtures,
> fit rules and order guard unchanged, so the estimator did not move** — and it
> merged, fixing the rig's blobs, *before* any sample existed. One solo
> quiet-window run then took the package arm and **both** donor arms in the same
> run set, as this section required. S1–S5 in §4 are package figures as a
> result.
>
> Two of this section's stated conditions were met differently from the way it
> guessed, and the difference is worth recording. **No new build id was needed
> and no hot-zone file was touched**: the driver already rides `:hicasso-bench`
> through `P0_BUILD` / `P0_INIT_FN`, and `hicasso/src` is already on that build's
> source paths, so `:hicasso-heap-bench` was deferred rather than built (to
> `rf2-hic-071`, reconsidered only if this becomes a standing gate). And the
> `~5% common-mode offset` caution below is about the **two harnesses** — P0
> against the freehand ladder — not about two runs of this one; the
> cross-session comparison in the studio section is licensed instead by the
> donors reproducing their published anchors within 1 B, which is this corpus's
> own standing rule.
>
> **The instrument-drift half is closed by supersession, not by repair.** The
> eleven pinned blobs tabulated below have not been made to match; they never
> can be, and `front/sub_index.cljs` no longer exists. What has changed is that
> the drift is no longer *un-attributable*: the run below took the package and
> both donors on one instrument in one session, so the delta between it and the
> prototype is read against same-run donors rather than against a stale pin.

`rf2-hic-006` asks for the shell, per-read and teardown baselines
**re-measured on `implementation/hicasso`** so later gates have a
same-instrument anchor. **This page refuses that deliverable and reports why.
The refusal is the finding.**

### The instrument does not measure the package

The pinned heap figures come from `p0_run.cjs --only ladder`, whose candidate
arm is `re-frame.bench.hicasso.arm1.*` in the benchmark tree. That runtime's
own docstring states its residence:

> Residence: the bench/test tree, off every production source path (HD-017).
> **Nothing under `implementation/*/src` requires this.**

`implementation/shadow-cljs.edn` states the same separation from the other
side — the bench tree "stays exactly where it is and keeps building under
`:hicasso-bench`; nothing in the package imports it, and
`hicasso/scripts/check_freeze.py` is what says so."

So `implementation/hicasso/src` is a **copy** of that runtime, deliberately
frozen apart from it, and **no heap instrument is pointed at the package at
all.** The merged-PR audit of #7759 reached the same conclusion from the
`npm run bench:hicasso` side and is recorded on the bead.

### The registered instrument has also drifted from its own pin

Independently of the above, the instrument is no longer the one that produced
the pinned rows. The heap ladder identifies itself **by content hash, not by
commit SHA**, and says plainly that "if a SHA does not resolve, the blobs are
what to trust". At `0c0aa22898`, **not one of the eleven pinned blobs matches**,
and one file no longer exists:

| file | pinned blob | at `0c0aa22898` |
|---|---|---|
| `reads_ladder_run.cjs` | `eabd226bcb6fe3877d056145cae496eccd5ab62c` | `7c10142110543bf67e2f9b8df57b14fdea46fddb` |
| `p0_run.cjs` | `4718aaead7035ae9a6cf74a89ef13141803742cc` | `30e168756279b37edc47f7a900d009f861903966` |
| `p0_heap.cljs` | `34c9210dfe39d3c7ee153c724fa63cf8e65dd1e1` | `0d3ef77db05bcec08d9f37f861bb4af30f88901d` |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` | `a24fcebc7a8a0ac11dafb215ffbe462b6e9ccf4d` |
| `arm1/runtime.cljs` | `69bfc6fc23af3035af88a2f69c4f4623a869fd83` | `202f7612ae356602b14db038bfd9249c16930acb` |
| `front/sub_index.cljs` | `394927d6f6493ea651daac84b9f140cd54f8f6c1` | **absent** |

Four other blobs in the same two tables have moved likewise. The point is not
that drift is wrong — the tree has legitimately advanced — but that **a number
taken today could not be compared with the pinned row**, because the delta would
be un-attributable between instrument drift and the package move. Two changes,
one reading, no way to tell them apart.

### Why it was not fixed inside this window

Building a package-pointed heap instrument is a **new instrument**, and it needs
a new build id in `implementation/shadow-cljs.edn` — a hot-zone file. Both are
out of bounds here:

- The window's own terms forbid improving the rig mid-measurement: *"no new
  instrument, no extra rungs, no third estimator, however obvious. File the
  improvement and run it as its own window."* Half the numbers coming from one
  instrument and half from another, with nothing in the record saying which, is
  the failure mode that rule exists to prevent.
- The hot-zone edit is the operator's to sequence, and was not requested in
  advance.
- `rf2-hic-018` already owns `implementation/hicasso/test/.../substrate_*` as
  **new benches** on its surface line. The package-resident heap instrument is
  most naturally that bead's, or a new one sequenced with it — not this one's.

**What it would take**, stated so the next window can be dispatched without
rediscovering it: a `:hicasso-heap-bench` build id whose candidate arm requires
`re-frame.hicasso` instead of `re-frame.bench.hicasso.arm1`, the existing
`p0_*` driver and fixtures reused unchanged so the estimator does not move, and
one quiet-machine run taking the package arm and both donor arms **in the same
run** — because the two harnesses differ by a measured ~5% common-mode offset
and no figure may be scaled from one onto the other.

---

## 7. Where each row is enforced

| Family | Enforcement home |
|---|---|
| Deterministic rows D1–D26 | ordinary blocking PR gates; framework in `rf2-hic-089`, full gates in `rf2-hic-071` |
| Distributional rows S1–S8, U1–U4 | pinned interleaved evidence runs on P-DEV-1; never converted into flaky PR thresholds |
| Shell breach disposition | `rf2-0xx2` |
| K3 per-read record | `rf2-hic-070` — [`k3-disposition.md`](k3-disposition.md), whose §8 makes the 10% same-witness per-read rule executable for `rf2-hic-071` |

Row by row, with each verdict beside its line, that table is
[§9's ledger](budgets.md#9-the-budget-line-reconciliation-ledger). The routing
above is what the ledger's own gate enforces: a deterministic row must name a
witness a pull request runs, and a distributional row must not.

---

## 8. This page's own run, and what would falsify it

The deterministic rows were confirmed at `0c0aa22898` on P-DEV-1 by the browser
lane, run alone on a quiet box:

| Run | Command | Captured exit | Result |
|---|---|---:|---|
| 1 | `npm run test:browser` | `0` | 1,284 tests, 7,971 assertions, 0 failures, 0 errors |
| 2 | same, with D1/D2's acceptance inverted to `3` | `1` | captured red naming the line — see below |
| 3 | same, assertion restored | `0` | restored byte-exact; file hash matches pre-sabotage |

**Run 2 is the sabotage control, and it is why run 1 means anything.** The
witness rows are `browser?`-guarded and degrade to stated skips off the browser
lane, so a green aggregate alone would not distinguish *ran and passed* from
*skipped*. Inverting the acceptance produced a captured failure naming the
assertion:

```
FAIL in (a-keystroke-costs-the-same-at-25-cells-and-at-100)
  (re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs:198:11)
expected: (= 3 at-25 at-100)
  actual: (not (= 3 2 2))
```

That report does two jobs at once, and the second is the more useful. It proves
the row **executes** rather than skipping — a skipped row cannot fail. And
because `cljs.test` prints the bound values, `(not (= 3 2 2))` **independently
witnesses D1 = 2 and D2 = 2** from the failure path, without the passing
assertion being the thing that reports them. The figures and the proof they
were measured are therefore not the same observation.

Run 3 restored the assertion and re-ran: the file's SHA-256 matches its
pre-sabotage value byte-for-byte, and the lane returned to 1,284 tests and
7,971 assertions with zero failures — the same totals as run 1, on bytes proven
identical to run 1's.

**What would falsify this page.** Any of: a second physical profile showing the
user-visible budgets behave differently in tier (§1's accepted limitation is
the standing invitation); a package-resident heap instrument producing shell or
per-read figures materially apart from S1–S4 (§6 says how to build it); the
operator freezing the byte line somewhere other than the §5 recommendation; or
any deterministic row D1–D16 moving without a topology change to explain it.

**On the scope of the run above.** The table records the confirmation this page
took at `0c0aa22898`, which is the browser lane and therefore D1–D9. D10–D13 and
D14–D16 landed later and run in the node lane, each with its own witness named in
§9's ledger and run by every pull request; the falsification clause is stated over
all sixteen because the claim it makes — a deterministic row does not move without
a topology change — is the same claim whichever lane decides it.

---

## 9. The budget-line reconciliation ledger

Every line this page registers has a verdict somewhere above, and above is
eight sections long. A page whose verdicts are spread that far can carry a
breach nobody has to look at — not by hiding it, but by never putting it next
to the rows that are fine. This section is the one place each registered line
states its own verdict, and
[`check_budget_ledger.py`][gate] is what stops the ledger drifting from the
sections it summarises.

[gate]: ../../../../implementation/hicasso/scripts/check_budget_ledger.py

**The gate does not enforce the budgets, and cannot.** Two thirds of the rows
below are distributional, and §1 has already said a hosted runner may never
source a distributional figure. What the gate enforces is that the *record*
tells the truth: every registered line has a row; a row that is not green names
who owns it and where it was dispositioned; a confidence band that crosses its
line reads `UNRESOLVED` rather than green; and no distributional row is quietly
routed into a pull-request threshold. It can tell you a breach has gone
unrecorded. It cannot tell you a budget is met.

### 9.1 How to read a row

**Status is four-valued, and exactly one value is a pass.** The fourth column
is the point of the whole table, so it uses a closed vocabulary rather than
prose:

| Status | What the evidence did | A pass? |
|---|---|---|
| `MET` | decided the row on the meeting side of its registered line | **yes** |
| `BREACH` | decided it on the failing side | no — carried, red, with a named disposition |
| `UNRESOLVED` | did **not** decide it: a confidence band that crosses the line, or a comparison whose two arms are not the same witness | **no, and never silently folded into a pass** |
| `UNPINNED` | never reached the row — no instrument for it exists on the governed population | no; there is nothing yet to decide |

`UNRESOLVED` is the status the operator's 2026-08-12 ruling exists to make
sayable. §5 froze the shell line at `1,024 B` *and* adopted the adjudication
rule that a band crossing it is unresolved rather than a pass; a ledger with
only pass and fail would have had to round that ruling to one or the other, and
whichever it chose would have been a fabrication. Where a row carries a
machine-readable byte ceiling and a machine-readable band, the gate recomputes
the verdict from those two numbers and refuses a status that disagrees — so
`MET` cannot be written over a crossing band by hand.

**A `BREACH` row does not redden the gate; an unrecorded one does.** The
distinction is the whole design. §5 says the shell breach is carried and never
normalised, and [the substrate decision](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition)
carried it red deliberately after refusing substrate remediation on the
evidence. A gate that failed the build on that would be demanding a fix the
programme has already ruled against; a gate that quietly greened it would be
the normalisation §5 forbids. So the gate asks the only question it is entitled
to ask — *is this breach on the record, with an owner and a disposition that
resolves?* — and reds when the answer is no.

**The other cells.** *Population* is `package`, `bench-tree` or `—`, and it is
pinned per row in the gate: `rf2-fe0l` made S1–S5 package figures and left S6,
S7 and U1–U4 where they were, so a later edit cannot quietly promote a
bench-tree figure by rewriting a cell. *Instrument* names the thing that took
the reading and, in parentheses, the lane it runs in — `PR gate`,
`P-DEV-1 evidence run`, or `none`. A deterministic row must name a witness file
that exists, runs in the first lane, and is *selected by a test build that
blocks a merge*; a distributional row may never name the first lane at all.
*Authority* is the bead that owns the row's disposition today. *Disposition* is a link that must resolve to a section naming the row.

**[Amended 2026-08-14, `rf2-mwr2`.] The lane is now verified rather than
believed.** Until this amendment the gate read that the lane was *spelled*
legally and that the witness file was *on disk*, and took the `PR gate` claim
itself on trust — so a row could name a witness that only a scheduled workflow
ever runs and stay green. A second audit of `rf2-mwr2` reasoned from exactly
that gap, and concluded `D26`'s counter gated nothing. **The conclusion was
wrong on the facts and the reasoning was right about the gate**, so the gap is
what got closed. For a `*_dom_cljs_test` witness the browser lane is decided by
a single selector, so the gate now reads the two selectors out of
`implementation/shadow-cljs.edn` and requires the PR-blocking `:browser-test`
build to select the witness's namespace. A witness reachable only through
`:browser-test-freehand-bench` — the lane `freehand-bench.yml` drives on
`schedule` and `workflow_dispatch`, which blocks no merge — now reds. Nineteen
rows carry a verified lane as a result, `U5` and `D26` among them.

Why the audit's conclusion did not hold, recorded here so it is not re-derived:
`:browser-test` excludes `re-frame.freehand.bench.*`, and `D26`'s witness lives
in the bench *tree* but declares the namespace
`re-frame.bench.hicasso.topo.census-dom-cljs-test`. The exclusion does not
reach it; the PR-blocking selector matches it; `implementation/freehand/test`
is on the global `:source-paths`; and the job that runs that build,
`cljs-browser`, is in `test.yml`'s required `all-required-passed` needs list
and is armed for this surface by the changed-surface classifier. The witness's
assertions do gate on a real DOM, which is a *stated skip under `:node-test`*
and not a skip in the lane that gates. Should a later worker tighten the
exclusion to the whole bench tree, the gate reds here instead of silently
unhooking `U5`'s second counter.

**[Amended 2026-08-14, `rf2-xcaph`.] The verification now covers every `PR gate`
witness, not only the DOM ones.** The amendment above checked seven of the
eight witnesses this ledger names and left the eighth class — the plain
`*_cljs_test` counters — exactly where it found them: lane spelled legally,
file on disk, lane itself believed. That remainder was not hypothetical.
`rf2-9vbl1` found `D9` and `U6` naming
`hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs`, the test-kit
**facade** — a real library file, under a real source root, containing no tests
and compiled into no test build — and the DOM-only rule returned green on it.
So the rule is now the general one: **every `PR gate` witness must be selected
by a build that blocks a merge**, and a witness no build compiles reds as a file
on disk rather than a gate.

Each witness class has exactly one such build and is checked against that one —
a `*_dom_cljs_test` witness against `:browser-test`, run by `test.yml`'s
`cljs-browser` job; every other against `:node-test`, run by its `cljs` job
(*CLJS (shadow-cljs :node-test)*). Both jobs sit in the required
`all-required-passed` `needs:` list, one line apart, and both are armed
per-surface by the changed-surface classifier: the node lane blocks a merge on
precisely the footing the paragraph above established for the browser one. The
mapping is deliberately **per class** and not *any build that selects it*,
because `:node-test`'s `cljs-test$` also matches every `*-dom-cljs-test`
namespace — whose assertions are the stated skip just described. Reading that
match as satisfaction would weaken the browser arm in the act of closing the
node one, letting a scheduled-lane DOM witness back through on the node build's
selector. All eight witnesses pass on arrival; the widening reds nothing here
and closes the class the last one could not see.

**[Amended 2026-08-14, `rf2-mwr2`.] Each selector is read out of its own
build's map, and a build declaring none refuses rather than borrowing.** The
two amendments above both read a selector by searching on from a build's key
across the rest of `implementation/shadow-cljs.edn`, which meant a build
declaring no `:ns-regexp` silently adopted the *next* build's and had its lane
reported verified against a selector belonging to something else. Nothing was
green that should have been red — all three builds the gate reads declare their
own selector — but the failure mode was the one these amendments exist to
close, rebuilt inside their own machinery, and a config edit was all it needed.
The search is now bounded at the build's own closing brace. Two details are
load-bearing and easy to strip by accident: the brace matching skips string
literals and `;` comments, because this config carries prose holding
`{:infer-warning false}` inside `:node-test`'s own map and a `--config-merge`
example inside another's; and comments are **blanked** rather than merely
skipped, because the same file spells `:ns-regexp "cljs-test$"` in a comment
two builds below the real declaration. A gate that cannot read a build's
selector now says which build and why, in both directions — no such build, or
no selector inside it.

**[Amended 2026-08-14, `rf2-mwr2`.] A row that claims work SCALES has to name
two counters, and the gate refuses one.** `U5` was registered on boundary
bodies alone, and read `MET` on a coarse view-model arm that rebuilds every
mounted row for a one-row change — it does its rows *inside* one body, so the
instrument counts 1 and reports a pass on the behaviour the line forbids. That
is a **fail-open**: not a row recorded wrongly, but a row that could not be
recorded wrongly, because its estimand cannot see the failure. So the gate now
holds a rule of its own for this shape. A registered line stating that work
scales with changed rows must name a companion ledger row carrying a **second,
different** work counter; that companion must itself be deterministic, in the
`PR gate` lane, with a witness file that exists; and the scaling row's *Current
value* must name it, so a reader following the row reaches both readings. `U5`'s
companion is `D26`. **Nothing here widens a line** — no threshold moved, and a
coarse topology that is genuinely cheap passes on both counters. What the rule
removes is the option of registering a scaling claim that only one instrument
is asked about.

**[Amended 2026-08-13.]** *Authority* is read in **two modes**, and which one
applies follows the state of the **disposition**, not the state of the bead.
While a live transition remains to be made — the `UNPINNED` rows waiting on
`rf2-hic-071`'s instruments; `S6` and `C2`, whose K1 price record is amendable
only through `rf2-hic-085`; `S3` and `C6`, handed onward because real work
remained — the cell names the **live** bead that can actually move the row, and
a closed id there is a defect. Once the row's *Disposition* cell records a
**taken, complete** ruling carrying its own reopen conditions and revert
trigger, the cell names the bead that **took** it, and a closed id is then
correct: the decision outlives the decider. That is why `S1`, `S2` and `C5`
keep `rf2-0xx2` after its close ([§9.2](#92-what-each-not-green-row-is-waiting-on)),
and it is not a loosening — the discriminator is never bead liveness, it is
whether a live transition remains. The column has never been a uniformly
live-owner field in practice: many `MET` rows in the table below name closed
beads, and this amendment describes what the ledger already does rather than
permitting something new. Should a taken disposition ever need amending with no
route to it, the fix is to give **that record** a named standing amendment-route
bead on the K1 pattern — `rf2-hic-085` is the worked example — never to read a
settled row back into live-route mode. The gate is indifferent to all of this:
it reads the cell for **shape, not for life**, and says so in its own output.

**[Amended 2026-08-15, `rf2-4h0l8`.]** `S6` and `C2` **leave the live-route
list above**, by this definition's own test rather than against it. The
operator's delegated ruling of 2026-08-15 reads their *Disposition* — the K1
price acceptance, ratified 2026-08-13 with its effective revision filled — as a
**taken, complete** ruling carrying its own reopen conditions
([`k1-price-acceptance.md` §7.1](k1-price-acceptance.md#71-reopen-conditions))
and revert trigger ([§7.2](k1-price-acceptance.md#72-revert-condition)), which
is the second mode's test verbatim; a remaining contingency is not a live
transition. Both cells therefore keep `rf2-hic-085` as the bead that **took**
the recording, exactly as `S1`, `S2` and `C5` keep `rf2-0xx2`, and the closed
id there is correct rather than a defect. The discriminator is untouched, and
it is what did the work: disposition state, never bead liveness.

With that classification the **worked example retires**. `rf2-hic-085` is no
longer a standing amendment-route bead, so the sentence above no longer has a
live one to point at, and the escape hatch it describes is read
**change-scoped**: should a taken disposition need amending, the fix is a route
bead filed **when a concrete amendment is proposed**, scoped to that amendment
and returning it to the record's named decider — not a bead held open against
the possibility. That is the same repair at a later moment, and it leaves the
sentence's prohibition exactly as written: a settled row is still never read
back into live-route mode. The record's own machinery does the summoning either
way, and `bd reopen rf2-hic-085` reuses the same id, so retiring the sentinel
loses no address.

<!-- rf2-hic-089: ledger -->

| # | Registered line | Current value | Population | Status | Instrument (lane) | Authority | Disposition |
|---|---|---|---|---|---|---|---|
| D1 | 2 bodies per keystroke at 5×5, and equal to D2 | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D2 | 2 bodies per keystroke at 10×10, and equal to D1 | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D3 | 26 bodies, coarse shape at 5×5 | 26 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D4 | 101 bodies, coarse shape at 10×10 | 101 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D5 | 0 bodies for a refused keystroke | 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D6 | 11 bodies for `clear-row` on a 10-wide row | 11 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D7 | 2 bodies, first keystroke of an editor session | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/editor/flow_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D8 | 1 body, every keystroke after the first | 1 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/editor/flow_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D9 | zero teardown residue in counters and frame ids | zero | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/test_kit_mounted_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D10 | 3 hiccup-walk entries dropped by a direct return (`codec/vec->element`) | 3 — the hiccup arm reads 4 against the crossing's floor of 1 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D11 | 3 prop-pipeline entries dropped by a direct return (`codec/convert-props`) | 3 — the hiccup arm reads 3 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D12 | 2 event-lowering entries dropped by a direct return (`intent/lower-prop`) | 2 — the hiccup arm reads 2 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D13 | 3 controlled-repair entries dropped by a direct return (`controlled/install!`) | 3 — the hiccup arm reads 3 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D14 | 0 wrappers between the element type React reconciles and the author's own function, declared island | 0 — the type is `identical?` to the function, on the native and handwritten-React routes alike; UIx's is a generated component | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| D15 | 1 slot on the props object React carries, and it is the author's own | 1 — `label`, the name the call site wrote; UIx also reads 1, and it is the `argv` carrier | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| D16 | 0 unwrapping hops per render, per component, native and handwritten React | 0 — the UIx route reads 1, opening `argv` | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| D17 | 10 subscription recomputations per keystroke, four-field editor | 10 — `::field` 4, `::committed` 4, `::revision` 1, `::dirty?` 1; one of the ten computes a new value | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D18 | 31 subscription recomputations per keystroke, grid at 5×5 — a LINEAR quantity at a stated mount size | 31 — `::cell` 25, `::row-total` 5, `::dimensions` 1 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D19 | 111 subscription recomputations per keystroke, grid at 10×10 — a LINEAR quantity at a stated mount size | 111 — `::cell` 100, `::row-total` 10, `::dimensions` 1; 109 of them compute what they computed last time | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D20 | 0 subscription recomputations for a refused keystroke | 0 — the event writes no address, so nothing is invalidated | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D21 | 0 glass writes for an accepted keystroke | 0 — the character is already on the glass and the model took it unchanged | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D22 | 1 glass write for a refused keystroke | 1 — the committed value going back over the character the model would not take; also this instrument's own positive control | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D23 | 7 DOM mutation records per keystroke, editor | 7 — `name` ×4, `type` ×2, `value` ×1; four of them React's churn on an attribute the application never wrote | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D24 | 8 DOM mutation records per keystroke, grid | 8 — the editor's seven plus one `characterData` on the row total's text node | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D25 | 3 DOM mutation records for a refused keystroke | 3 — `name`/`type`/`name` with no `value` write; the attribution that makes D23's other four the commit's | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs` (PR gate) | `rf2-hic-045` | — |
| D26 | 1 row of markup built for a one-row write under a fine topology, at every row count | 1 at B = 100, 300 and 1,000 — the coarse arm builds B, the chunked k=25, the windowed 1 | bench-tree | `MET` | `implementation/hicasso/test/re_frame/bench/hicasso/topo/census_dom_cljs_test.cljs` (PR gate) | `rf2-mwr2` | — |
| S1 | 1,024 B, R=0 shell, Reagent segment | 1,100 B [1,091–1,107] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-0xx2` | [substrate-decision §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition); scoped acceptance 2026-08-13, [§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b) — ceiling unchanged at 1,024 B, accepted to 1,107 B |
| S2 | 1,024 B, R=0 shell, UIx segment | 1,095 B [1,087–1,101] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-0xx2` | [substrate-decision §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition); scoped acceptance 2026-08-13, [§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b) — ceiling unchanged at 1,024 B, accepted to 1,101 B |
| S3 | ≤ 10% regression on the same pinned witness | 1,417 vs Reagent 948 per read | package | `UNRESOLVED` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-85og2` | [substrate-decision §6](substrate-decision.md#6-what-this-page-does-not-decide) |
| S4 | ≤ 10% regression on the same pinned witness | 2,115 vs UIx 2,980 per read | package | `MET` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-070` | — |
| S5 | teardown retained indistinguishable from 0 | indistinguishable from 0; all ten rungs' bands straddle it | package | `MET` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-089` | — |
| S6 | 1.10x cold mount against direct UIx-on-subs | 1.1718x [1.1263–1.2190] n=8; 1.1976x [1.1504–1.2468] n=6 | bench-tree | `BREACH` | final K1 estimator (P-DEV-1 evidence run) | `rf2-hic-085` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| S7 | warm allocation, a fitted series clearing the quality floor | no publishable claim | bench-tree | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| S8 | C8's first disjunct: an escape recovers ≥ 20% | `0.7418x` [0.6458–0.8409] — 25.8% recovered [15.9–35.4%], run 1 alone, on a 200-boundary mount; an observed range across per-round ratios, not a confidence interval | package | `UNRESOLVED` | P0 lane direct-return clock arm (P-DEV-1 evidence run) | `rf2-5yn9` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U1 | echo within one 60 Hz frame at p95 | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U2 | ≤ 50 ms p95 and ≤ 100 ms p99 to next paint | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U3 | ≤ 100 ms p95 for broad operations | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U4 | dragging and animation inside the frame budget | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U5 | body work scales with changed rows, not mounted rows | 2 bodies at 25 cells and at 100, and — on the second counter D26 — 1 row of markup for a one-row write where a coarse arm rebuilds every row | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| U6 | teardown residue zero after quiescence | zero counters (D9); bytes indistinguishable from 0 (S5) | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/test_kit_mounted_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| C1 | ≤ 5% regression on the same witness and instrument | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C2 | 1.10x cold mount, the registered line | see S6 | bench-tree | `BREACH` | final K1 estimator (P-DEV-1 evidence run) | `rf2-hic-085` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C3 | ≤ 1.25x the best relevant adapter on broad updates | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C4 | no sustained 1.5x as ordinary Hicasso | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C5 | 1,024 B byte-exact, not governed by baseline-plus-10% | 1,100 B / 1,095 B [1,087–1,107] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-0xx2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on); scoped acceptance 2026-08-13, [§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b) — ceiling unchanged at 1,024 B, accepted to 1,107 B / 1,101 B |
| C6 | ≤ 10% per-read regression on the same pinned witness | see S3 / S4 | package | `UNRESOLVED` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C7 | a native island within 5% or 1 ms of the same component mounted directly | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C8 | an escape taken for a benefit recovers ≥ 20%, saves ≥ 2 ms p95, or flips a failed budget; an interoperability escape is outside the population | — | — | `UNPINNED` | — (none) | `rf2-85og2` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| I9 | ≤ 2 React hooks per boundary shell, invariant in read count | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/hook_budget_cljs_test.cljs` (PR gate) | `rf2-hic-018` | — |

<!-- rf2-hic-089: end-ledger -->

Forty-nine rows: the twenty-six deterministic figures of §3, the eight distributional
rows and six user-visible budgets of §4, the eight comparative rules §4 now
gives ids to, and one row registered off this page — **I9**, the two-hook
ceiling frozen by
[the substrate decision](substrate-decision.md#4-the-two-hook-ceiling-frozen-with-its-measurement).
I9 is here because it is a budget with a package-resident witness and no other
ledger; its provenance is held in the gate rather than assumed.

**U6 is the split row, and the ledger shows only one half of it.** Its
deterministic half is D9's counters and its distributional half is S5's bytes,
and §3 has already said the two are not interchangeable evidence. The
instrument cell names the deterministic witness because that is the half a pull
request runs; the other half is S5's own row, one line above.

**[Corrected 2026-08-14, `rf2-9vbl1`.]** Both `D9` and `U6` named
`implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs` until
this correction. That file is the test-kit **facade** — a library file under
`test_kit/src` holding no tests, and selected by no test build: its namespace
`re-frame.hicasso.test.mounted` matches neither the `cljs-test$` selector nor
the `-dom-cljs-test$` one, and `implementation/shadow-cljs.edn` says as much
itself in the source-path comment for the kit. Its two `deftest` occurrences
are documentation prose. So both rows asserted the `PR gate` lane against a
file that gates nothing. The confusion was instrument for witness:
`hm/assert-clean!` is the **instrument** and does live there, but the
**witness** is a suite that runs it. Both rows now name the facade's own DOM
witness suite, whose namespace `re-frame.hicasso.test-kit-mounted-dom-cljs-test`
is matched by both selectors, and which drives `hm/assert-clean!` and
`hm/residue` directly and asserts that a destroyed frame is gone from
`rf/frame-ids`. [§3](#3-deterministic-rows-pinned-on-the-moved-package)'s own
`D9` row is unchanged and was always right — it names the instrument and the
population, which is what a registered line needs.

### 9.2 What each not-green row is waiting on

Three rows have a disposition record of their own and point at it: `S1` and
`S2` at [the substrate decision's §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition),
where the shell breach was carried red on the evidence, and `S3` at
[its §6](substrate-decision.md#6-what-this-page-does-not-decide), where the
`+139 B/read` package move is now attributed to one ratom-only correctness line
(`rf2-l50z`) — an attribution, not a change to the figure, so `S3` is still
`UNRESOLVED` against its own rule. Every other row that is not `MET`
points here, and here is what each is waiting on.

**[Amended 2026-08-13.]** `S1` and `S2` now name a second disposition beside
that one: the scoped acceptance ruled on `rf2-0xx2` and recorded in
[§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b).
It moves neither reading and neither status — both rows stay `BREACH` against
the unmoved `1,024 B` line, and the acceptance reaches no further than the
pinned bands' upper edges, `1,107 B` and `1,101 B`.

**[Amended 2026-08-13.]** `S1`, `S2` and `C5` keep `rf2-0xx2` as their
authority although that bead has closed, and keeping it is a decision rather
than an omission — these are the settled-mode rows of
[§9.1](#91-how-to-read-a-row), not live-route ones. What the three were waiting
on was a disposition, and the disposition has been **taken**: the scoped
acceptance ruled on `rf2-0xx2` and recorded in
[§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b)
on the five fields `specification.md` §6 requires, reopen conditions and revert
trigger among them. A ruling that carries its own amendment machinery needs no
live bead to hold it, and the cell naming who took it is the audit trail —
`rf2-0xx2`'s close reason is where the scoping is recorded. The remaining path
is not owed an owner either: a shell arm landing under `1,024 B` on the
package, on which day the acceptance is **deleted rather than kept as a floor**,
[stays live and deliberately unowned](correction-ledger.md#deferred-items-and-the-release-decision)
by the programme's own choice, so there is no live bead to name and naming one
would fabricate ownership the programme withheld. Contrast `S6` and `C2` below,
where a route to change the record does exist and the cell names it: same rule,
other mode.

**[Amended 2026-08-15, `rf2-4h0l8`.]** The contrast drawn in that last sentence
no longer holds, and it is kept because this page annotates and never erases.
`S6` and `C2` are **settled-mode rows too**, by the operator's delegated ruling
of 2026-08-15: the K1 price acceptance is a taken, complete ruling carrying its
own reopen and revert machinery, so the live route those two cells were said to
name has retired with the classification. Same rule, and now the **same** mode
— the reasoning is at the `S6` and `C2` bullet below. Nothing about `S1`, `S2`
or `C5` changes with it.

- **No package-resident clock instrument exists.** `U1`, `U2`, `U3` and `U4`
  are latency budgets, and §4 says in terms that they cannot be pinned until
  such an instrument exists. `C3` and `C4` are the comparative rules stated on
  the same missing readings. None of the six can be pinned by measuring harder
  on the rig that exists; each needs a clock instrument pointed at
  `implementation/hicasso`, on P-DEV-1, in its own window.
  **[Amended 2026-08-14, `rf2-hic-045`.]** `U1`'s **deterministic half is now
  published** — [`per-keystroke.md`](per-keystroke.md) is the per-keystroke
  census of the two witness applications, and its §6 records that on both
  pages the typed field shows the model's value at the instant
  `dispatchEvent` returns, inside the discrete event, with no flush performed
  and **no frame boundary crossed at all**. `U1` is **not** recoloured by it
  and stays `UNPINNED`, deliberately: the registered estimand is *latency to
  visible echo at p95*, a distributional row, and *the echo is present before
  the turn yields* does not imply *the echo reaches the glass within 16.7 ms
  at p95* on a machine where the event turn itself can be slow. Substituting
  the structural reading for the distributional one is the conflation §3
  refuses when it keeps `D9`'s counters apart from `S5`'s bytes. What the
  census narrows is what the clock will eventually be timing: work inside one
  discrete event, on a path whose other five stages are now counted.
  §9.3's *one-frame keystroke echo* deliverable is therefore still
  `rf2-hic-071`'s, and its scope is unchanged.
- **The 5% rule has no same-instrument anchor.** `C1` compares a reading
  against the pinned ordinary-Hicasso benchmark, and §6 records that the
  registered instrument's eleven pinned blobs are superseded rather than
  repaired — one of its files no longer exists. Until the ladder is re-pinned,
  "the same instrument" names nothing, and a comparison against it would be
  un-attributable in exactly the way §6 describes. The re-pin is `rf2-hic-071`'s
  along with the gate it enables.
- **The cold-mount line is missed, and the price accepted for it is not a
  second line.** `S6` and `C2` are red against the registered `1.10x`. The
  `1.25x` figure was ratified on 2026-08-13 by operator ruling rather than by a
  sitting, and it is still not written in either row's line cell, because an
  accepted price is not a gate: the gate refuses to let it be, and §4's
  standing prohibition — no evidence row may use the accepted ceiling to mark
  K1 green — is held as a pin on those two rows rather than as a sentence
  nobody re-reads. No line here is re-pinned by that ratification: the
  [correction ledger](correction-ledger.md) reopens a budget line only when a
  ceiling *changes*, and this one did not move.
  **[Amended 2026-08-13.]** `S6` and `C2` keep `rf2-hic-085` as their
  authority, and keeping it is a decision rather than an omission. What these
  two rows are waiting on is not a reading — the *"final K1 estimator"* in
  their instrument cell is the estimator that already took one, `rf2-diaud`'s,
  and it decided them on the failing side. What is unsettled is the
  disposition, and the disposition is the price accepted for the miss;
  [that record's §9](k1-price-acceptance.md#9-amendments-to-this-record) makes
  `rf2-hic-085`, which owns its effective-revision field, the only route to a
  change in it. No other live bead holds that route, and a bead filed to hold
  it would be the same standing recorder under a second id. So the cell names
  the bead that can actually move the row, which is what this column is for,
  and the consequence is written down rather than worked around: `rf2-hic-085`
  stays open while this record stands, and closing it would leave both rows
  pointing at nobody.
  **[Amended 2026-08-15, `rf2-4h0l8`.]** The last two sentences above are
  superseded, and are kept because this page annotates and never erases.
  `rf2-hic-085` does **not** stay open while this record stands, and closing it
  leaves neither row pointing at nobody. What was unsettled when that paragraph
  was written is now settled: the operator's delegated ruling of 2026-08-15
  classifies this disposition **settled mode** under
  [§9.1](#91-how-to-read-a-row) — the K1 price acceptance is a **taken,
  complete** ruling, ratified 2026-08-13 with its effective revision filled,
  carrying its own reopen conditions and revert trigger, and a remaining
  contingency is not a live transition. Both rows therefore keep `rf2-hic-085`
  **byte-unchanged** in their *Authority* cell, now read as the bead that
  **took** the recording rather than the one holding a route open, exactly as
  `S1`, `S2` and `C5` keep closed `rf2-0xx2` above. The closed id is the durable
  record and the reopen handle in one: `bd reopen rf2-hic-085` reuses this same
  id, and a fired
  [§7.1](k1-price-acceptance.md#71-reopen-conditions) condition or
  [§7.2](k1-price-acceptance.md#72-revert-condition) lapse returns that record
  to its decider by its own text, with or without a bead. The route survives;
  what retires is the sentinel. Nothing here re-pins a line, moves a figure or
  touches either row's `BREACH` status.
- **`S8`'s range crosses C8's own line, which is what `UNRESOLVED` is for.** The
  reading is not thin and the rig is not the problem: the arm-order guard
  returned `reportable`, the positive control saw the doubling its own
  arithmetic predicts, 225 of 225 measured mounts were read back at their far
  end, and a mounted-DOM fairness gate agreed and was driven to disagree on
  purpose. What the range does is straddle `20%` — `25.8%` recovered,
  `[15.9–35.4%]` — so the first of C8's three disjuncts is neither met nor
  missed, exactly the case §5's freeze ruling created this status for.
  **[Amended 2026-08-13.]** Three corrections to how this row was first
  published, none of which moves its status. The figure is **run 1 alone**,
  because run 2's positive control predicted `4.000` against a ±25% band of
  `[3.000–5.000]` and its rounds reach `5.150` — a refusal under the strict
  every-round-inside rule that `lane/control-verdict`'s own docstring says is
  the right one — and a pooled figure that includes a control-refused ensemble
  is not a published figure. The interval is an **observed range across
  per-round ratios, not a confidence interval**, and §4 now says so where the
  row is stated. And C8's `≥ 2 ms p95` disjunct is **UNASSESSED at this
  witness** rather than missed: the instrument computes no `p95`, so the
  per-boundary rate and the break-even boundary count first published beside
  this row are **withdrawn** — one 200-boundary witness fixes no scaling law.
  **Narrowing the range is a measurement and widening the line is forbidden**,
  and the re-run is not scheduled: under the site-level reading below nothing
  turns on whether the corpus-wide recovery is 18% or 26%. Buy one only when a
  real site adjudication under `rf2-hic-071` genuinely turns on the `20%`
  disjunct, when a user-visible `p95` claim is being made, or when a second
  physical profile exists — never to force a pass. The *Authority* cell keeps
  `rf2-5yn9` on [§9.1](#91-how-to-read-a-row)'s settled mode: the disposition
  has been taken, and what remains is a condition rather than a live transition.
- **[Ruling 1, 2026-08-13, `rf2-5yn9`.] The profile obligation is DISCHARGED
  P-DEV-1-only.** A distributional clock obligation runs over **all registered
  *physical* distributional profiles — today exactly `P-DEV-1`**; the
  CI-RUNNER-A half is closed by refusal, ratified, on §1's prohibition and §9's
  lane vocabulary rather than for want of machine time. It **reopens
  automatically if a second physical profile ever registers**. The reasoning is
  in [§3](#3-deterministic-rows-pinned-on-the-moved-package), and
  [specification §6](specification.md#6-performance-contract) now states the
  obligation in those terms, so the *both reference profiles* wording this row
  was waiting on is gone at its source rather than annotated here.
- **[Ruling 2, 2026-08-13, `rf2-5yn9`.] `C8` is adjudicated per landed escape
  site**, with authority `rf2-hic-071` — every landed escape carries its own
  measured benefit, and one below `20%` / `2 ms` / a budget flip is removed by
  that gate's acceptance. *"Simplify or remove"* is a site action, so **`S8` is
  the mechanism's published reference price that a site adjudication cites**,
  never a corpus-wide pass, fail, or veto. Rung 3 is retained on that reading:
  `codec/as-element` has had a total `:react-element` arm since `rf2-hic-030`,
  so removing the escape would *add* a rejection branch around otherwise-total
  handling — machinery, where C8 exists to prevent it. `C8` stays `UNPINNED`
  until a real site supplies its population.
  **[Clarified 2026-08-14, `rf2-m7xx0`; the ruling above is unchanged and no
  threshold moves.]** *Every landed escape* scopes the ruling over the escapes
  it was taken about — the ones taken **for a benefit**. An
  **interoperability** escape is outside it, because its alternative is not a
  slower spelling but no spelling at all: there is nothing for *"simplify or
  remove"* to name, and a gate adjudicating it would demand a removal with no
  destination. The one `h/as-element` call in the shipped example applications
  is exactly that one — `examples/ledger/views.cljs`, the ledger screen's
  `:render-row` handing a view to a vendor virtualizer whose callback contract
  is `(index, offsetPx) => ReactNode` — so
  [§4's `C8` row](#the-comparative-and-regression-rules) now states the
  population rather than leaving the gate author to infer it.
- **`S7` has no publishable claim to pin.** No fitted allocation series clears
  the quality floor. This is a property of the readings rather than of the
  rig, so it is `UNPINNED` rather than `UNRESOLVED`: nothing crossed a line,
  because nothing reached one.
- **`C8` has no population yet; `C7` now has one.** The native-island rule
  and the escape-benefit rule are both stated over landed escapes and islands,
  and the apps that would carry them are `rf2-hic-034`, `rf2-hic-047` and
  `rf2-hic-045`. What *kind* of population `C8` is waiting for is no longer an
  open question — Ruling 2 above settles it as one adjudication per landed
  escape site — but the sites themselves have not landed, so the row stays
  `UNPINNED`. `rf2-hic-034` has landed, bringing `C7` its population and the
  deterministic half of its band — D14–D16 above, the structural question a
  hosted runner is allowed to decide. `C7` stays `UNPINNED` for the other half
  only: no package-resident clock instrument exists to take the reading, the 5%
  rule has no same-instrument anchor until the ladder is re-pinned, and §7
  forbids converting a distributional row into a pull-request threshold in any
  case. All three are `rf2-hic-071`'s, which names these beads as the ones it
  extends over — the same statement from the other side.
- **`C5` and `C6` are rules whose readings have been dispositioned, and the
  rules have not.** `C5` is the shell rule; its evidence is `S1` and `S2`,
  carried red by the substrate decision's §5.2. `C6` is the per-read rule; its
  evidence is `S3` and `S4`, and that page's §6 has since attributed the `S3`
  move — `[SETTLED 2026-08-13, rf2-l50z]` — without moving the reading.
  Neither section adjudicates the *rule*, only the readings
  under it, so the two rows point here rather than borrowing a disposition
  that was not made about them. `C5` is settled the day a shell arm lands
  under `1,024 B` on the package; `C6` the day the first `rf2-hic-071`
  same-witness comparison actually decides it.
  **[Amended 2026-08-13.]** `C6`'s condition read *the day the K3 record is
  taken*, which promised a settlement the taking of a record cannot deliver.
  The K3 record has now been taken — [`k3-disposition.md`](k3-disposition.md)
  is **ratified by the operator ruling of 2026-08-13**, recorded on
  `rf2-hic-085` — and `C6` stays `UNRESOLVED` across it. The ruling freezes the
  governed baseline at `2,115 B/read`, fixes the trip point at `2,326.5 B/read`
  and routes future enforcement to `rf2-hic-071`; it cannot manufacture a
  passing comparison, and
  [that record's §8](k3-disposition.md#8-the-10-same-witness-per-read-regression-rule)
  says in terms that the first forward same-witness comparison has not yet been
  taken. `S3` is untouched by it and is not recoloured: the Reagent contrast is
  a viability scoreboard, never a test of the forward 10% rule.
  **[Amended 2026-08-13.]** `C5` now carries a disposition made about the rule
  as well as its readings: the scoped acceptance ruled on `rf2-0xx2` and
  recorded in
  [§5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b),
  on the five fields `specification.md` §6 requires. It prices the breach and
  does not pass it — `C5` stays `BREACH`, the registered line stays `1,024 B`,
  and the sentence above still holds without amendment: `C5` is settled the day
  a shell arm lands under `1,024 B` on the package, and on that day the
  acceptance is deleted rather than kept as a floor.
  **[Amended 2026-08-13.]** `C6`'s authority moves from `rf2-hic-085` to
  `rf2-hic-071`, and `S3`'s moves with it. The recorder's business with these
  two rows was the K3 ruling; that ruling has been given and recorded, so what
  it held them for is discharged. What remains is the forward same-witness
  comparison, and the K3 record names its enforcement home in its own field
  table — `rf2-hic-071`, with the early framework at `rf2-hic-089`
  ([§8](k3-disposition.md#8-the-10-same-witness-per-read-regression-rule)),
  which is also the paragraph above's *"the day the first `rf2-hic-071`
  same-witness comparison actually decides it"* read from the authority side.
  The handoff moves custody and nothing else: both rows stay `UNRESOLVED`,
  both readings stand, and `S3`'s disposition still points where it did.
  **[Amended 2026-08-15, `rf2-3e4iq`.]** The paragraph above stands as the
  record of a custody move that happened, and custody has moved once more:
  `rf2-hic-071` CLOSED on 2026-08-14 and its measurement remainder was split
  to `rf2-85og2` under the operator's direction, so an *Authority* cell naming
  it on a row whose disposition is still open names a bead that owns nothing.
  Under the two-mode reading of [§9.1](#91-how-to-read-a-row) ruled on
  `rf2-iay8` and confirmed by `rf2-4h0l8`, a live-route row must name a LIVE
  bead and only a settled row may keep a closed decider — which is why `S1`,
  `S2` and `C5` keep `rf2-0xx2` and `S6` and `C2` keep `rf2-hic-085`, and
  those five cells are deliberately untouched here. Twelve live-route cells
  therefore move from `rf2-hic-071` to `rf2-85og2`: `S3` and `C6`, whose
  remaining forward same-witness comparison is the ladder re-pin `rf2-85og2`
  carries, and the ten `UNPINNED` rows `S7`, `U1`–`U4`, `C1`, `C3`, `C4`, `C7`
  and `C8`, each waiting on one of the three things
  [§9.3](#93-where-this-ledger-stops-and-rf2-hic-071-begins) hands on — the
  clock instrument, the ladder re-pin, the escape-benefit population.
  **This moves custody and nothing else.** No reading, status, population,
  instrument or disposition cell is touched — the twelve rows differ from
  their predecessors in the *Authority* column and in no other — and the
  ledger gate reads the same 49 rows, 31 `MET`, 5 `BREACH`, 3 `UNRESOLVED`
  and 10 `UNPINNED` after the repoint as before it.

### 9.3 Where this ledger stops and rf2-hic-071 begins

This bead is the *early* framework: the half that can exist before the
population does. The boundary is not a matter of taste, and it is worth stating
as a rule rather than as a list.

**What can be gated early is what a hosted runner is allowed to decide.** §1
permits CI-RUNNER-A correctness gates and same-run relative drift, and forbids
it any distributional product budget; §2 sorts every row into the two families;
§7 routes them. So the early framework is *the deterministic family plus the
record*: the D rows and I9 already run as ordinary blocking witnesses in a pull
request, and this section adds the ledger and the gate that keeps it honest.
Everything the ledger says about a distributional row is a transcription of an
evidence run, checked for internal honesty and nothing more.

**Two of this bead's stated deliverables cannot be built early, and the reason
is on this page.** They are recorded here rather than quietly dropped:

- *The 5% same-instrument regression gate, running in CI per relevant PR.* The
  pinned ordinary-Hicasso benchmark is a heap figure — distributional, P-DEV-1
  only, and §7 says such a row is never converted into a flaky PR threshold.
  Wiring it to a hosted runner would breach §1 on the first green. It is `C1`
  in the ledger, `UNPINNED`, and it additionally has no anchor to compare
  against until §6's instrument drift is re-pinned. The gate below enforces
  that no one wires it there by mistake: a distributional row may not name the
  `PR gate` lane.
- *Slice-app user-visible gates at 50 ms p95 / 100 ms p99 and one-frame
  keystroke echo.* These are `U1`–`U4`, and §4 records that no package-resident
  clock instrument exists to pin them on. The deterministic half that *is*
  reachable — a controlled update landing same-turn, and the per-keystroke body
  count behind the echo claim — is already gated, as `U5`, `D7` and `D8`.
  Building a second instrument to say more would be building a second thing to
  drift, which is the rule §3 states and `rf2-fe0l` was dispatched to honour.

**`rf2-hic-071` therefore inherits three things, not one**: the clock
instrument and the U-row gates it makes possible, the ladder re-pin and the 5%
comparison it makes meaningful, and the escape-benefit rule over the escapes
its own dependencies land. This ledger is what it will report into — the bead
owns the status columns from the moment it takes them.

### 9.4 What rf2-hic-071 has taken so far, and what it still cannot take

**Taken, 2026-08-14.** Ten rows and one rule. `D17`–`D25` bring
[the per-keystroke census](per-keystroke.md)'s five remaining stages under
`L4`'s round trip, `L5`'s population pin and `L6`'s witness check, so a figure
that lived in prose on one page now has to keep agreeing with a witness a pull
request runs. `D26` and `L7` close the fail-open `rf2-hic-036`'s tournament
exposed in `U5`: a registered scaling claim can no longer be decided on one
counter, in either direction. Neither needed a measurement — every integer here
was already measured, published and merged, and this bead transcribed rather
than re-derived them, on §3's own rule that a second source for one number is a
second thing to drift.

**Not taken, and the reason is the same reason in three places: each needs a
measurement window this bead did not open.** They are recorded here rather than
quietly carried:

- **The user-visible gates** — `U1`–`U4`, and `C3`/`C4` stated on the same
  readings. §4 says in terms that no package-resident clock instrument exists,
  and §9.2 records that `rf2-hic-045`'s census narrowed *what* the clock will
  be timing without supplying one. A threshold guessed rather than measured
  would be a fabricated line, which is what `UNPINNED` exists to say instead.
- **The 5% same-instrument regression gate** — `C1`. §6 records that the
  registered instrument's eleven pinned blobs are superseded rather than
  repaired, one of its files no longer existing, so *"the same instrument"*
  still names nothing. The re-pin is a run, not an edit.
- **The escape-benefit rule** — `C8`, whose population is site-level by
  [Ruling 2](#92-what-each-not-green-row-is-waiting-on). No escape site taken
  **for a benefit** has landed in an application: the one `h/as-element` call
  in the shipped examples is the ledger screen handing a view to a vendor
  virtualizer's `renderRow`, which is interoperability rather than an escape
  claiming recovered time. That distinction is now stated in the rule itself
  ([§4's `C8` row](#the-comparative-and-regression-rules), `rf2-m7xx0`),
  because a `C8` gate written over *every* landed escape would demand
  the removal of an escape with no alternative — the mirror of the fail-open
  `L7` just closed, and a fail-closed one. Stating it moved no threshold and
  built no gate: `C8` stays `UNPINNED`, still waiting on a site inside the
  population.

**What this leaves.** Every deterministic budget this corpus has measured is
now a ledger row with a witness a pull request runs, and every remaining gate
is blocked on an instrument rather than on an edit. That is the honest boundary
today, and moving it needs a quiet box rather than another pass over this page.
