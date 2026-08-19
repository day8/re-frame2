# The sign follows the pass, not the write — V2's residual under the paired mode

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-0gjqi`, discharging the obligation
merged-PR audit #8424 set on it: *compare matched certified pairs once the
paired mode lands, and count independent run-pair/order blocks rather than rungs
as independent trials.* The mode landed as `rf2-irxrw` (PR #8461). Measured
2026-08-18 14:26–14:31 AUSEST on branch `worker/pairedwin-0gjqi`, built at
`1f004b15ff`.

Runtime, beside every figure below: Chromium via Playwright, shadow-cljs
`release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`. Both
runs report the same build (`194 files, 139 compiled, 0 warnings`).

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stayed the declared 0.25
placeholder, `ALLOC_FALL_THRESHOLD_B` stayed 600,000, `ALLOC_MIN_WRITES` stayed
6, W stayed 6 measured writes after one prime, three warm-up windows, six rounds,
R = 20 stayed on the ladder. **No rig file was edited by this window**, and the
estimator was written down before the first run was taken.

Both runs' raw records are committed beside this page at
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-0gjqi/`.

## The answer, first

**The eight-of-eight residual does not survive matched pairing, and the sign that
remains tracks WHICH PASS OF THE ROUND a write was measured in rather than which
write it was.** The rig ties pass order to round parity, so "pass order" here
means that ordering and whatever else travels with it — see
[what is controlled and what is not](#b-the-round-blocks-which-is-where-the-sign-lives).

- **The unanimity is gone.** Across the 16 mid-rung cell medians this window
  produces — 8 cells × 2 runs — `page` reads below `all` at **10 of 16**, which is
  consistent with chance rather than evidence of it. The published window read
  8 of 8.
- **The magnitude is about a quarter of the published one.** Cell differences run
  **0.06% – 2.98%** in absolute value, absolute median **0.46%**, against the
  published **0.51% – 4.28%** and absolute median **1.68%** on the same eight
  cells. The two are the old estimator's output and the new one's, not one
  quantity measured twice.
- **The sign is set by which leg pass ran SECOND.** Taking the round as the
  block, the pass measured second reads lower in **10 of 12 round blocks** —
  6 of 6 in run 1, 4 of 6 in run 2 — *whichever write occupied it*. That is a
  position effect; a write effect does not change sign when the order does.
- **Decomposed on that block statistic, the write term is not stable.** The
  order-free half-sum reads **−0.33%** in run 1 and **+0.24%** in run 2 —
  opposite signs on two runs — while the pass-order half-difference reads
  **+0.68%** and **+0.21%**, the same sign on both.
- **V2's floor-drop half holds, and is now measured within a round.** The floor
  drops **1,652 B (8.6%)** on `reagent-subs` and **1,652 – 1,658 B (8.4%)** on
  `uix-subs`, and the per-round drop sits between **1,644 and 1,664 B** across
  all 21 paired certified rounds. The published unpaired figures were 1,769 B
  (9.1%) and 1,765 B (8.9%).
- **The R = 0 null arm reads flat under both writes**, which is what licenses
  reading the mid-rung numbers at all: 38 paired certified observations, median
  difference **0 B/boundary**, absolute median **1.5**, 90th percentile **4.5**.
  That reading is now published as the instrument's **non-cancellation floor**,
  and it sets a **refusal bar of 45 B per boundary** — see
  [the non-cancellation floor](#the-non-cancellation-floor-and-the-refusal-bar-it-sets).
- **The R = 20 rung certifies on 1 of its 4 arm families, in both runs.** That
  makes three sessions in a row finding the same one. **No gate was widened to
  admit it.**

**What this does NOT say** is that the two writes are equal. It says the
published direction was not a property of them, and that this window could not
establish a direction at all. See [what was NOT concluded](#what-was-not-concluded).

## The run count was fixed before the first run

**Two runs, declared in advance, and two were taken.** No run was added after
seeing a result and none was dropped. Both are the same configuration; the second
exists to give a second independent process block, which is the unit the audit
asked to be counted.

| # | plan | write | `P0_ALLOC_CELLS` | B | rounds |
|---|---|---|---|---|---|
| 1 | `full` | `paired` | 1 | 4 | 6 |
| 2 | `full` | `paired` | 1 | 4 | 6 |

Reproduction, from `implementation/`:

```
P0_ALLOC_PLAN=full P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=paired node core/test/re_frame/bench/p0_run.cjs --only alloc
```

Both runs **captured exit 1**, which is this row's code for a run carrying any
refused window. Both failed on the falls gate — **55 and 54 collections inside
measured windows** — so **no slope is quotable from either run and none is
quoted here**. The falls gate refuses the arms' scale, not the arithmetic, and
every figure on this page is a per-window figure off certified windows.

### Why this window did not wait for a quiet box

An allocation estimand is a census of monotone byte counters, so it reads the
same on a loaded machine and takes no quiet-box slot. Every figure here is such
a census; **no clock quantity is computed on this page**. The counters were read
on their own, never inside a measured run, bracketing each run:

| bracket | wall clock (AUSEST) | cpu-seconds per 6 s, all processes | processes | `java` |
|---|---|---|---|---|
| before run 1 | 14:26:20 | 10.984 | 542 | 0 |
| between runs 1 and 2 | 14:28:59 | 11.578 | 538 | 0 |
| after run 2 | 14:31:21 | 8.156 | 534 | 0 |

The box has 24 cores, so 10.98 cpu-seconds over 6 s is about **7.6%** of the
machine and 8.16 is about **5.7%**; 88 of the resident processes were the
operator's own Chrome. No `java` process existed at any bracket, so no
shadow-cljs server could have served a stale build. **What the bracketing
licenses is one claim: the box was in the same state before and after each
run.** It says nothing about quietness during a run, and no figure here rests on
within-run quietness.

## Both runs' positive control passed

A run whose positive control fails contributes no data, so the controls come
first and per run, never pooled.

| run | idle (B/iteration) | direct D=1000 | direct D=400 | differential | verdict | unverified |
|---|---|---|---|---|---|---|
| 1 | 45 [32–111] | 8.08 B/double | 8.20 B/double | **8.00 B/double** | OK | 0 |
| 2 | 45 [32–111] | 8.08 B/double | 8.20 B/double | **8.00 B/double** | OK | 0 |

The differential — D = 1,000 less D = 400 — reads **8.00 B/double against a
predicted 8** in both runs, replicating every previous window. Transient garbage
is visible to this counter, which is the one claim the rest of the page rests on.

**And the run read this checkout.** The driver prints the config it built
against, and in both runs that line names this worktree's own
`implementation/shadow-cljs.edn` — so neither run compiled a sibling checkout's
sources.

## The estimator, written down before the first run

For every round *r*, segment *s* and arm *a*, the paired record carries four
windows: the arm and that segment's floor, each under each write, all four
measured in round *r* on the same page in the same process.

```
d_all (r,s,a)  = (arm@all .legMedian − floor@all .legMedian) / B
d_page(r,s,a)  = (arm@page.legMedian − floor@page.legMedian) / B
Δ     (r,s,a)  = d_page − d_all
```

**A round contributes to a cell only if ALL FOUR of its windows certified**,
which is what "matched certified pair" means and is the half the published
window could not have. `legMedian` is the window's own median work leg, which is
the quantity the published table was computed from; B = 4.

Two summaries of a cell are reported because they are different statistics and
the difference is not always small:

- **ratio-of-medians** — median of `d_page` over the cell's qualifying rounds,
  divided by the median of `d_all` over the same rounds, less 1. This is the
  form directly comparable to the published table.
- **median-of-ratios** — the median of the per-round `Δ / d_all`.

**The block is the ROUND, not the rung.** Within one round every arm of a
segment differences against the same floor pair, and `rf2-77gz8`'s term is
page-global, so a level term common to a round moves every cell in it together.
The round-block statistic is therefore the median of `Δ / d_all` over that
round's certified mid-rung (R3, R7) cells.

**And the blocks are split by which leg pass ran first**, which the rig makes
possible for the first time: `p0_run.cjs:2418` reverses the leg list on odd
rounds, so over six rounds each write leads three times. A within-round position
term flips sign with that order; a write term does not.

## A. The mid-rung cells, matched within the round

Eight cells per run — R3 and R7 on each of four arm families — with `n` the
number of rounds in which all four windows certified.

| run | segment \| arm | rung | n | `all` | `page` | ratio-of-medians | median-of-ratios |
|---|---|---|---|---|---|---|---|
| 1 | `reagent-subs` \| hicasso | R3 | 4 | 6,559 | 6,589 | +0.46% | +0.09% |
| 1 | `reagent-subs` \| hicasso | R7 | 4 | 13,713 | 13,768 | +0.41% | +0.04% |
| 1 | `reagent-subs` \| reagent | R3 | 5 | 7,582 | 7,565 | −0.22% | −0.78% |
| 1 | `reagent-subs` \| reagent | R7 | 5 | 15,064 | 15,047 | −0.11% | +0.06% |
| 1 | `uix-subs` \| hicasso | R3 | 5 | 6,638 | 6,440 | **−2.98%** | −1.21% |
| 1 | `uix-subs` \| hicasso | R7 | 5 | 13,346 | 13,521 | **+1.31%** | −0.02% |
| 1 | `uix-subs` \| uix | R3 | 4 | 5,157 | 5,076 | −1.57% | −1.02% |
| 1 | `uix-subs` \| uix | R7 | 5 | 11,206 | 11,098 | −0.97% | −1.12% |
| 2 | `reagent-subs` \| hicasso | R3 | 5 | 6,558 | 6,576 | +0.27% | +0.30% |
| 2 | `reagent-subs` \| hicasso | R7 | 2 | 13,741 | 13,654 | −0.64% | −0.63% |
| 2 | `reagent-subs` \| reagent | R3 | 5 | 7,565 | 7,560 | −0.06% | +0.03% |
| 2 | `reagent-subs` \| reagent | R7 | 4 | 15,027 | 15,017 | −0.06% | −0.01% |
| 2 | `uix-subs` \| hicasso | R3 | 4 | 6,608 | 6,492 | −1.75% | −1.25% |
| 2 | `uix-subs` \| hicasso | R7 | 5 | 13,343 | 13,404 | +0.46% | +0.33% |
| 2 | `uix-subs` \| uix | R3 | 5 | 5,072 | 5,114 | +0.83% | +0.70% |
| 2 | `uix-subs` \| uix | R7 | 6 | 11,166 | 11,123 | −0.39% | −0.34% |

**Ten of sixteen are negative on ratio-of-medians and nine of sixteen on
median-of-ratios.** Under a null of random sign, 10 of 16 is unremarkable. The
published window's eight cells were **8 of 8**, and every one of them was
negative.

**The magnitudes fell with the unanimity.** Absolute values run **0.06% –
2.98%**, absolute median **0.46%**. The published eight ran 0.51% – 4.28% with
an absolute median of 1.68%.

**One caveat that is arithmetic rather than judgement**: this table's cells and
the published ones are not the same estimator applied twice. The published cells
took each leg's median over *that leg's own* certified rounds, which differed
between legs in 6 of 8 cells; these take both legs over the *same* rounds, which
is the correction the audit asked for. So the two tables are not two readings of
one quantity, and the comparison above is between the old estimator's output and
the new one's, stated as such.

## B. The round blocks, which is where the sign lives

Twelve blocks — two runs of six rounds. `second-minus-first` is the same per-round
statistic re-signed by which pass ran second; it is arithmetic on the column
beside it, not a further estimator.

| run | round | pass that ran FIRST | n mid cells | median `(page−all)/all` | median second-minus-first |
|---|---|---|---|---|---|
| 1 | 0 | `page` | 3 | +0.35% | −0.35% |
| 1 | 1 | `all` | 8 | −1.00% | −1.00% |
| 1 | 2 | `page` | 7 | +0.35% | −0.35% |
| 1 | 3 | `all` | 8 | −1.03% | −1.03% |
| 1 | 4 | `page` | 4 | +0.74% | −0.73% |
| 1 | 5 | `all` | 7 | −0.78% | −0.78% |
| 2 | 0 | `page` | 4 | +1.51% | −1.48% |
| 2 | 1 | `all` | 8 | −0.93% | −0.93% |
| 2 | 2 | `page` | 7 | +0.45% | −0.45% |
| 2 | 3 | `all` | 5 | +0.03% | +0.03% |
| 2 | 4 | `page` | 6 | +0.28% | −0.28% |
| 2 | 5 | `all` | 6 | +0.36% | +0.36% |

**The pass that ran second read lower in 10 of the 12 blocks** — all six of
run 1 and four of run 2 — with a median of **−0.59%**. The two exceptions are
`+0.03%` and `+0.36%`, both in run 2, and both smaller than that run's own
typical block.

**How many independent blocks that is, stated rather than assumed.** There are
**two independent process blocks**. The twelve round blocks are not twelve
independent trials: rounds inside one run share a session, a build and an
allocator history. A sign test over the twelve gives a nominal one-sided
p = 79/4096 ≈ **0.019**, and that number is quoted *only* as what the nominal
test says, because its independence premise is the very one this bead exists to
police. **The claim that survives a stricter count is the weaker one: both
process blocks put the second pass lower, and 2 of 2 is not a small
probability.**

**What is controlled and what is not.** Leg-pass order alternates on round
parity, and so does segment order (`p0_run.cjs:2269`) — but both legs of a round
run under the *same* segment order and the same `slot-order(n, round)` arm order,
so an arm sits at the same within-pass position in both legs. What the
within-round difference isolates is the pass itself. **What it does not isolate
is round parity from leg order**, because the rig ties them together: every
`page`-first round is an even round. Any other even/odd property of a round that
acts differently on a first and a second pass would read the same way here, and
this window does not exclude one.

## C. Decomposing the block statistic

If a pass-position term enters additively, the half-sum of the two parities'
medians is the order-free write term and the half-difference is the position
term. **This is arithmetic on the table above under a stated model, not a third
estimator.**

| block set | `page`-first median | `all`-first median | order-free WRITE term | PASS-ORDER term |
|---|---|---|---|---|
| run 1 | +0.35% | −1.00% | **−0.33%** | **+0.68%** |
| run 2 | +0.45% | +0.03% | **+0.24%** | **+0.21%** |
| pooled | +0.40% | −0.86% | −0.23% | +0.63% |

**The write term changes sign between the two runs and the order term does not.**
That is the shape of a position effect sitting on top of a write term this
window cannot see, and it is the reason no interval is offered for the write
term below.

## D. The floor, paired inside the round

V2's other half. Every figure here differences a floor window under `page`
against a floor window under `all` **in the same round on the same page**, which
the unpaired 1,769 / 1,765 B could not do.

| run | segment | n paired rounds | `all` | `page` | drop | per-round drop range |
|---|---|---|---|---|---|---|
| 1 | `reagent-subs` | 5 | 19,288 | 17,636 | **1,652 B (8.6%)** | 1,652 – 1,652 |
| 1 | `uix-subs` | 5 | 19,732 | 18,080 | **1,652 B (8.4%)** | 1,650 – 1,658 |
| 2 | `reagent-subs` | 5 | 19,288 | 17,636 | **1,652 B (8.6%)** | 1,652 – 1,658 |
| 2 | `uix-subs` | 6 | 19,738 | 18,080 | **1,658 B (8.4%)** | 1,644 – 1,664 |

**The drop is the same number on two segments that share nothing but the page**,
to within 6 B, in both runs. That is what a page-global term would produce, and
it is the same coincidence `rf2-77gz8` reports for the second floor mode — but a
per-write cost that simply does not depend on the segment would produce it too,
and this window does not separate the two. It is also remarkably tight: 21 paired
rounds span **1,644 – 1,664 B**, a range of 20 B on a quantity of 1,650.

**V2's floor-drop half therefore holds, and holds more cleanly than the earlier
reading.** The paired figure is ≈ 110 B smaller than the unpaired
1,769 / 1,765 B on both segments.

**These figures are now the PUBLISHED floor drop, and the move is a CHANGE OF
BASIS rather than a correction** (`rf2-2rtt6.140`, 2026-08-19). The row read
**1,769 B (9.1%)** and **1,765 B (8.9%)** on the basis of
[the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md#the-floor-drops-which-is-half-the-criterion),
where the two writes were driven by **two sequential processes** — the only
thing the instrument could do before `rf2-irxrw` — and each leg's median was
taken over *that leg's own* certified rounds. This basis is
**within-round paired**: both writes in the same round, on the same page, in
the same process, over rounds where **both** floor windows certified. **Neither
figure is withdrawn as a reading**, and the old basis is not retired — it is
still selectable as `P0_ALLOC_WRITE=all|page`. What retires is its
*admissibility for this estimand*: a between-process difference cannot separate
the write from everything else that differs between two runs, which is exactly
the objection `rf2-irxrw` was filed on. **What the new figure rests on is this
window's own warrant** — two runs declared in advance, both positive controls
passing at 8.00 B/double, 21 paired certified rounds spanning 20 B — **and what
it does not settle is carried with it**: the drop's page-global-versus-per-write
cause is unresolved, above, and both runs captured exit 1 on the falls gate.

## E. The null arm, which is what licenses reading the rest

The R = 0 rung mounts boundaries that read nothing, so `arm − floor` must be
zero under either write and the paired difference must be zero. It is the only
arm on this page whose true value is known in advance.

| population | n | median `Δ` | absolute median | 90th percentile | max |
|---|---|---|---|---|---|
| R = 0, all four windows certified | 38 | 0 B/boundary | **1.5 B/boundary** | **4.5** | 96.5 |

**The instrument's own floor on `Δ` is a few bytes per boundary**, which is one
to two orders of magnitude below the mid-rung cell differences of 5 – 198
B/boundary. So the mid-rung numbers are being read above the null arm's noise;
what they lack is a consistent direction, not resolution.

### The non-cancellation floor, and the refusal bar it sets

**RULED 2026-08-19 00:05 AUSEST on `rf2-2rtt6.140`, on the table above.** That
table is the instrument's noise floor measured *directly* rather than inferred
or modelled — read off the one arm whose true value is known in advance to be
zero — and the ruling publishes it as a **named floor rather than as a
tolerance**:

> **NON-CANCELLATION FLOOR — `1.5 B` per boundary (absolute median) and
> `4.5 B` per boundary (90th percentile), over 38 paired certified
> observations.**

**It is stated in BYTES because bytes are what travels.** A percentage does
not: `4.5 B` is about 0.2% of the smallest per-boundary term this programme
publishes (`s(1) = 2,031 B/boundary/write`) and 100% of a `4.5 B` one. The
stable quantity is the floor itself, because bytes per boundary is what the
null arm measures.

**What the floor sets is a REFUSAL BAR, not a tolerance. Any per-boundary
figure that is not comfortably above the floor is REFUSED rather than
published, and the bar is ten times the p90 — `45 B` per boundary.** A figure
within an order of magnitude of the noise floor is not a measurement, and an
instrument that says *I cannot resolve this* is not a worse instrument. The bar
is a published number and a refusal the preflight can already express: **no
gate, constant or rung parameter was added for it, and none should be.**

**Against the ladder as it stands the bar refuses nothing published, and
exactly what it should.** Of the 68 per-boundary cell medians these two runs
produce — four arm families × five rungs × both writes, on the certified rounds
of each — the **52 non-null cells read `2,138 B/boundary/write` or more**, the
smallest of them **47× the bar**. The 16 the bar catches are the R = 0 cells,
whose medians span **−1.5 to +1.5 B/boundary** and whose true value is zero.
The null arm is where a floor-derived bar is *supposed* to bite.

**The two excursions are named rather than smoothed**: both are
`uix-subs | uix R0` at round 3, one in each run, reading −96.5 and −56.5
B/boundary. They reproduce across runs at the same round, which makes them a
property of the schedule rather than a stray. **No cause is assigned and nothing
here is excluded on their account.**

## F. The R = 20 rung, for the third time

| arm family | run 1 (`all` / `page`) | run 2 (`all` / `page`) |
|---|---|---|
| `reagent-subs` \| hicasso R20 | 0 of 6 / 0 of 6 | 0 of 6 / 0 of 6 |
| `reagent-subs` \| reagent R20 | 0 of 6 / 0 of 6 | 0 of 6 / 0 of 6 |
| `uix-subs` \| hicasso R20 | 0 of 6 / 0 of 6 | 0 of 6 / 0 of 6 |
| `uix-subs` \| uix R20 | **6 of 6 / 6 of 6** | **6 of 6 / 6 of 6** |

One of four families, which is exactly what 2026-08-13 and 2026-08-17 found.
Three sessions, three agreements: **this is structural.** The refusals are
dominated by a single leg reading roughly −800,000 B — a collection of about
800 KB landing inside the window — which the leg witness catches correctly.
**No gate was widened and none should be.**

## G. What this window can and cannot say about `rf2-77gz8`

The paired mode was expected to make `rf2-77gz8`'s open question askable: do the
ladder ARMS carry the floor's second mode? Two things, and the second is the
smaller claim.

- **The second mode did not appear in either run.** The certified floor's
  `legMedian` spans **46 – 58 B** over six rounds in every one of the four
  (run, segment) cells under both writes — 19,240 – 19,356 and 17,588 – 17,636
  on `reagent-subs`, 19,686 – 19,744 and 18,032 – 18,086 on `uix-subs`. There is
  no discrete 3,792 B step anywhere in them. **That is an observation, not a
  resolution**: `rf2-77gz8` was measured at B = 24 on the `floor` plan and this
  is B = 4 on the `full` plan, and absence in two runs is not absence.
- **The estimator no longer depends on the answer.** A page-global level term
  present in a round enters all four of that round's windows, so it cancels
  inside `(arm − floor)` under each write separately and again in the
  difference. That is what pairing the floor buys, and it is why this page's
  numbers do not turn on `rf2-77gz8` being settled.

**One arm-level behaviour is recorded because it is large and was not
anticipated.** The `lad/hicasso#R1` arm swings **4,232 – 4,848 B/write** on round
parity — even rounds high, odd rounds low — under **both** writes and in the
**same** direction, on both segments. Because it enters both legs of a pair it
cancels in `Δ`. **No cause is assigned here.** The arm order inside a pass is
`slot-order(n, round)` and alternates with the round, which is one candidate
among others, and this window did not test it.

## What was NOT concluded

- **No slope was fitted and none is published.** Both runs failed the falls gate
  — 55 and 54 collections inside measured windows — so no slope is quotable from
  either, and V2 quotes none in any case.
- **The two writes are NOT shown to be equivalent.** What is shown is that the
  published direction does not survive matched pairing. "No residual
  established" is not "no residual", and this page puts **no interval** on the
  write term: two process blocks give order-free estimates of −0.33% and +0.24%,
  which is consistent with zero and is not a bound. **The write term's magnitude
  is UNRESOLVED.**
- **The CAUSE of the published 8-of-8 is not established.** The pass-order term
  measured here (+0.21% to +0.68%) is of the sign that would produce it, but it
  is smaller than the published mid-rung absolute median of 1.68%, and it is a
  *within-round* pass-position effect where the published confound was between
  two *processes*. Those are not the same quantity, and this window did not
  measure the second one. It is of the right sign and the wrong size to be the
  whole of it.
- **The pass-order term itself is not explained.** No mechanism is proposed and
  none is excluded; nothing here separates it from any other even/odd property
  of a round, because the rig ties leg order to round parity.
- **`rf2-77gz8` is NOT resolved** — see section G — and no figure here is offered
  as evidence about the `floor`-plan B = 24 window it was measured on.
- **No ruling is made on tolerability *by this window*.** The published question
  — whether ≈ 1 – 4% of non-cancellation is acceptable — was posed of a figure
  this window does not reproduce, and whether there was anything left to rule on
  was for the ruling, not for this page. **The ruling has since been taken**
  (`rf2-2rtt6.140`, 2026-08-19) and it did not answer that question: it
  WITHDREW it, and published the null arm's reading as a floor in bytes instead
  — see [the non-cancellation floor](#the-non-cancellation-floor-and-the-refusal-bar-it-sets).
  Nothing this window measured changed when it landed.
- **Today's levels are NOT differenced against the published ones.** This
  window's `all` cells sit 0.8 – 6.1% below the 2026-08-17 published `all` cells,
  and that number is recorded rather than read: the two are different estimators
  over different certified-round subsets, which is precisely the comparison
  [the control's target is not the quantity it is read against](the-controls-target-is-not-the-quantity-it-is-read-against.md)
  found inadmissible.
- **Nothing is concluded about the R = 20 refusal's cause**, only that it has now
  reproduced three times.
- **No bound is claimed on within-run machine quietness.** The brackets are
  before-and-after readings and license only the before-and-after claim.
- **The intra-leg gate screened nothing here.** `P0_ALLOC_BY_SITE` was unset, so
  the run is stride 2 and `allocIntraLegRefusals` returns the empty list by
  construction. Certified means certified by the leg witness and the falls gate,
  and no more.
- **No gate, band, threshold or tolerance was touched, and no rig file was
  edited.** τ is where the previous windows left it.
