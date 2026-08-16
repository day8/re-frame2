# The floor certifies and the control does not — V1 and V2 re-run on the primed instrument

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-2rtt6.140`, criterion 5's remaining
item: a re-run of validity witnesses V1 and V2, both of which came back
UNASSESSED on 2026-08-13 for want of a certified floor window. Measured
2026-08-17 01:45–01:51 AUSEST, branch `worker/v1v2-140` off `6a32dbf7e5`, which
is an ancestor of `origin/main`.

Runtime, beside every figure below: Chromium **147.0.7727.15** via Playwright,
shadow-cljs `release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`.

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stayed the declared 0.25
placeholder, `ALLOC_FALL_THRESHOLD_B` stayed 600,000, `ALLOC_MIN_WRITES` stayed
6, W stayed 6 measured writes after one prime, three warm-up windows, six
rounds, R = 20 stayed on the ladder. No rig file was edited by this window.

## The answer, first

**Both witnesses are now ASSESSED. The floor certifies for the first time, so
V2 could be evaluated on certified data — and V1's control fails on the clause
that was outstanding.**

- **V1's second clause is ASSESSED and it does NOT hold.** `F_old` was to land
  on the 2026-08-08 figures at B = 24. On the five floor windows that certified,
  it reads **19,349 – 19,816 B/write** on `reagent-subs` against a target of
  **24,108**, and **19,712 – 20,696** on `uix-subs` against **24,730** — short
  by **17.8 – 19.7%** and **16.3 – 20.3%**. The prime `rf2-oiy1` introduced
  accounts for about 1,161 B of that gap; the remaining ≈ 3.1 – 3.6 KB is not
  explained by anything this window measured.
- **V1's first clause is re-confirmed, and now on certified windows.** `F_old`
  is flat in B: `reagent-subs` reads a certified leg median of 19,250 – 19,360
  at B = 24 and 19,318 – 19,408 at B = 4; `uix-subs` reads 19,696 – 19,866 and
  19,792 – 20,056. Previously this clause held only off refused windows.
- **V2's criterion is ASSESSED, and the floor-drop half HOLDS.** The floor went
  from certifying **0 of 6 in both runs on both segments** to **4 of 6 and 5 of
  6** under `write-all` and **5 of 6 and 6 of 6** under `write-page`, and it
  **drops by 1,769 B (9.1%)** on `reagent-subs` and **1,765 B (8.9%)** on
  `uix-subs`.
- **The agreement half is close but not clean.** Across the 13 comparable rungs
  the two writes differ by **−4.28% to +8.81%**, absolute median **1.48%**.
  Ten of the 13 certified per-round ranges overlap between writes.
- **And a systematic difference appears that pooling would have hidden**: at
  every one of the **8** R = 3 and R = 7 comparisons — both segments, both
  substrates — `write-page` reads **below** `write-all`, by 0.51% to 4.28%.
  Eight of eight in one direction is not what a random-sign account predicts.
- **The R = 20 rung is still unassessable on 3 of its 4 arm families.** Only
  `uix-subs | uix R20` produced certified windows under both writes, reproducing
  the 2026-08-13 window's finding exactly.

## The run count was fixed before the first run

**Three runs, declared in advance, and three were taken.** No run was added
after seeing a result and none was dropped:

| # | witness | plan | write | `P0_ALLOC_CELLS` | B |
|---|---|---|---|---|---|
| 1 | V1 control | `floor` | `all` | 6 | 24 |
| 2 | V2 | `full` | `all` | 1 | 4 |
| 3 | V2 | `full` | `page` | 1 | 4 |

Reproduction, from `implementation/`:

```
P0_ALLOC_PLAN=floor P0_ALLOC_CELLS=6 P0_ALLOC_WRITE=all node core/test/re_frame/bench/p0_run.cjs --only alloc
P0_ALLOC_PLAN=full  P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=<all|page> node core/test/re_frame/bench/p0_run.cjs --only alloc
```

All three runs captured **exit 1**, which is the expected code for a run
carrying any refused window.

### Why every run was taken at the shipped stride

`P0_ALLOC_BY_SITE` was left unset, so all three runs are stride 2. That is not
an oversight and it is not available to be changed: V1's second clause is a
**byte-for-byte** comparison against figures published at stride 2, and the
instrument states in source that absolute leg magnitudes at stride 3 are not
comparable byte for byte with those at stride 2, because each leg carries one
extra sampler read.

The consequence is stated rather than buried: **the third allocation gate did
not adjudicate one window on this page.** `allocSiteSplit` returns an empty
`siteLegs` at stride 2, so `allocIntraLegRefusals` returns the empty list by
construction. Measured, not assumed — **0 intra-leg refusals across all 276 arm
windows in the three runs**. What that gate catches, a collection bracketed
inside one leg, is therefore unscreened here exactly as it was in every window
published before it existed.

## The box, and what the bracketing does and does not license

**This window ran on a LOADED box**, on the standing rule that a census of a
monotone byte counter takes no quiet-box slot. The counters were read **on their
own, never inside a measured run**, bracketing each run:

| bracket | wall clock (AUSEST) | cpu-seconds per 6 s, all processes | processes | `java` |
|---|---|---|---|---|
| before run 1 | 01:44:32 | 4.359 | 529 | 0 |
| between runs 1 and 2 | 01:48:27 | 4.266 | 524 → 528 | 0 |
| between runs 2 and 3 | 01:50:09 | 2.641 | 523 | 0 |
| after run 3 | 01:51:44 | 3.094 | 523 | 0 |

The box has 24 cores, so 4.36 cpu-seconds over 6 s is about **3% of the
machine**; 84 of the resident processes were the operator's own Chrome. No
`java` process existed at any bracket, so no shadow-cljs server could serve a
stale build — the two `server.pid` files in the tree are from July and have no
live process behind them.

**What the bracketing licenses is exactly one claim: the box was in the same
state before and after each run.** It says nothing about quietness *during* a
run, and no figure here rests on within-run quietness. Every run's positive
control is reported separately below for that reason.

## Every run's positive control passed

A run whose positive control fails contributes no data, so the controls come
first and per run, never pooled.

| run | plan | write | B | direct (B/double) | differential (B/double) | verdict | unverified |
|---|---|---|---|---|---|---|---|
| 1 | `floor` | `all` | 24 | 8.08 | 8.00 | OK | 0 |
| 2 | `full` | `all` | 4 | 8.08 | 8.00 | OK | 0 |
| 3 | `full` | `page` | 4 | 8.08 | 8.00 | OK | 0 |

The differential — D = 1,000 less D = 400 — reads **8.00 B/double against a
predicted 8** in all three runs, replicating every previous window. Transient
garbage is visible to this counter, which is the one claim the rest of the page
rests on.

## V1 — the control's second clause, assessed and failing

Floor arm only, B = 24, `write-all`, six rounds, both segments. **Five of the
twelve arm windows certified.** The comparable 2026-08-13 run certified none, so
this clause could be read for the first time.

The estimator is `rise/W` — the published quantity the 2026-08-08 figures are
stated in — computed per window over the measured region with the prime leg
excluded. Per-window values are listed rather than averaged, because
`rf2-rs8q6` established that the arms' dispersion is round-indexed and a pooled
statistic over rounds would not answer.

| segment | certified windows | rounds | certified `rise/W` | certified leg median | 2026-08-08 target | shortfall |
|---|---|---|---|---|---|---|
| `reagent-subs` | 3 of 6 | 2, 3, 5 | 19,349 / 19,650 / 19,816 | 19,250 – 19,360 | **24,108** | 17.8 – 19.7% |
| `uix-subs` | 2 of 6 | 3, 4 | 19,712 / 20,696 | 19,696 – 19,866 | **24,730** | 16.3 – 20.3% |

**The control does not land, and the gap is larger than the prime explains.**
The 2026-08-08 windows were taken before `rf2-oiy1`, so their `rise/W` carried
the first-leg excess spread across six writes — about 6,966 / 6 ≈ **1,161 B**.
Subtracting it leaves a pre-prime-equivalent target near 22,947 and 23,569, and
the certified readings are still ≈ 3.1 – 3.6 KB below that.

**What that costs is specific and bounded.** `F_old`'s stated job is to say the
rig has not moved and thereby license comparison between the two writes. The
across-time half of that job is not discharged: **rows measured today may not be
differenced against the 2026-08-08 rows.** The within-run half is untouched —
V2 below compares two writes measured in the same sessions on the same build,
and nothing in this failure bears on it.

**This is not a claim that the 2026-08-08 figure was wrong.** Three independent
sessions since the prime landed — 2026-08-13, 2026-08-16 and this one — agree on
a floor near 19 – 20 KB, and the 2026-08-08 reading stands alone at 24.1 – 24.7
KB. Which of the two moved, and whether the cause is the reseeded page width,
the prime, or drift in the substrate, **is not determined by this window.**

## V2 — the equivalence cross-check, on certified data at last

Full 1/3/7/20 ladder plus the floor, B = 4, both segments, six writes per window
under `rf2-qomo`'s recommendation to run at the floor and let the top rung refuse
if it refuses, six rounds, measured twice — once under each write.

### The floor drops, which is half the criterion

| segment | `all` floor (certified) | `page` floor (certified) | drop |
|---|---|---|---|
| `reagent-subs` | 4 of 6 · 19,318 – 19,408 | 5 of 6 · 17,588 – 17,636 | **1,769 B (9.1%)** |
| `uix-subs` | 5 of 6 · 19,792 – 20,056 | 6 of 6 · 18,032 – 18,144 | **1,765 B (8.9%)** |

The drop figures are the difference of the medians across each write's certified
rounds. The 2026-08-13 indication off refused windows was 1,688 / 1,719 B
(7.3%), so the certified drop is slightly larger than the refused windows
suggested.

### The agreement, rung by rung

`(arm − floor) / B`, computed from each window's own leg median, using only
rounds where **both** the arm and that segment's floor certified. The figure
quoted per cell is the **median across those certified rounds** — not a run
mean, and not pooled across rounds beyond that median. R = 0 is omitted because
`arm − floor` there sits within ±12 B of zero and carries no ratio worth
quoting.

| segment \| arm | rung | n `all` | n `page` | `all` | `page` | difference |
|---|---|---|---|---|---|---|
| `reagent-subs` \| hicasso | R1 | 3 | 5 | 3,256 | 3,292 | +1.11% |
| `reagent-subs` \| hicasso | R3 | 4 | 4 | 6,984 | 6,687 | **−4.25%** |
| `reagent-subs` \| hicasso | R7 | 4 | 5 | 14,447 | 13,829 | **−4.28%** |
| `reagent-subs` \| reagent | R1 | 4 | 5 | 4,196 | 4,152 | −1.05% |
| `reagent-subs` \| reagent | R3 | 4 | 5 | 7,795 | 7,664 | −1.67% |
| `reagent-subs` \| reagent | R7 | 4 | 4 | 15,408 | 15,180 | −1.48% |
| `uix-subs` \| hicasso | R1 | 5 | 4 | 3,290 | 3,579 | **+8.81%** |
| `uix-subs` \| hicasso | R3 | 4 | 6 | 6,691 | 6,538 | −2.28% |
| `uix-subs` \| hicasso | R7 | 5 | 6 | 13,752 | 13,520 | −1.69% |
| `uix-subs` \| uix | R1 | 4 | 6 | 2,169 | 2,176 | +0.31% |
| `uix-subs` \| uix | R3 | 5 | 4 | 5,211 | 5,164 | −0.90% |
| `uix-subs` \| uix | R7 | 5 | 6 | 11,405 | 11,347 | −0.51% |
| `uix-subs` \| uix | R20 | 5 | 5 | 31,270 | 31,481 | +0.67% |

**Ten of the thirteen certified per-round ranges overlap between the two
writes.** The three that do not are `reagent-subs | hicasso R3`,
`reagent-subs | reagent R3` and `reagent-subs | reagent R7`, and all three miss
by less than the width of the ranges themselves.

### The finding a pooled read would have missed

**At every one of the eight R = 3 and R = 7 comparisons, `write-page` reads
below `write-all`** — both segments, both substrates, 8 of 8 in one direction,
by 0.51% to 4.28%. Under a null of random sign that is a 1-in-256 arrangement.
The two R = 1 hicasso rungs, whose round-to-round spread runs 22 – 35%, are the
noisiest cells on the page and carry the two largest disagreements in the other
direction; the mid rungs, whose spread is 1 – 7%, are where the sign is
consistent.

**Stated as plainly as the evidence allows: the magnitudes agree to a few
percent, and the residual is small but does not look like noise.** V2 exists to
detect the case where "the new write provokes different per-boundary work", and
`arm − floor` is meant to cancel the write's fixed cost exactly. A residual with
a consistent sign says the cancellation is not exact. Whether ≈ 1 – 4% of
non-cancellation is tolerable is a ruling, not a measurement, and this window
does not make it.

### The R = 20 rung, again

| arm family | certified pairs |
|---|---|
| `reagent-subs` \| hicasso R20 | **none** |
| `reagent-subs` \| reagent R20 | **none** |
| `uix-subs` \| hicasso R20 | **none** |
| `uix-subs` \| uix R20 | 5 under each write |

One of four families, which is exactly what 2026-08-13 found. The top rung is
V2's most informative and it remains unassessed on three quarters of the arms.

## What was NOT concluded

- **No slope was fitted and none is published.** V2 quotes no slope and this
  page adds none.
- **Nothing is concluded about τ.** It was not moved, not calibrated and not
  read as a verdict on anything. `rf2-e9wr` closed with it reported unusable as
  specified and this window does not reopen that.
- **No cause is assigned to V1's control failure.** The page establishes that
  today's certified floor is 17 – 20% below the 2026-08-08 figure and that the
  prime explains about a third of the gap. It does not identify what explains
  the rest, and it does not claim either reading is the wrong one.
- **No bound is claimed on within-run machine quietness.** The brackets are
  before-and-after readings and license only the before-and-after claim.
- **The intra-leg gate screened nothing here**, so no window on this page is
  certified against a collection bracketed inside a single leg. Certified means
  certified by the leg witness and the falls gate, and no more.
- **Whether the ≈ 1 – 4% sign-consistent residual is acceptable is not ruled
  on**, and nor is whether the R = 20 rung's three-of-four refusal is a fault of
  the rung, the page size or the write.
- **`rf2-rs8q6`'s round-indexed dispersion did not reproduce in the form it was
  reported.** The certified windows here fall at rounds 2 – 5 with worst leg
  deviations of 0.00 – 13.33%, not the clean round-3 ≤ 0.19% against round-2
  2.66 – 20.37% split. That is an observation on 12 windows against that bead's
  66, and it is offered as a note rather than a refutation.
