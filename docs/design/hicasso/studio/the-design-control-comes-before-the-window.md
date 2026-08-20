# The design control comes before the window — rf2-fk6pj's twelve-round pass-position window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-fk6pj`, phase 3 of three. Phase 1
landed the rig (`P0_ALLOC_PASS_ORDER=seeded`, PR #8596). Phase 2 took the first
seeded window (PR #8601, record
[the pass order drawn independently of parity](the-pass-order-drawn-independently-of-parity.md))
and narrowed the question without settling it. This page is phase 3.

**This section down to and including [the pre-registered outcomes](#the-pre-registered-outcomes)
was committed before the first run was taken.** The commit that carries it also
carries `alloc_pass_design.cjs`, so the round count, the four seeds, the
selection rule, the admissibility boundary, the reading, the band and the
stopping rule are all in the tree with a timestamp that precedes every figure
below them — and so is the control that says the design can answer the question
at all.

## The question, unchanged since the bead was filed

Under `P0_ALLOC_WRITE=paired` the two write legs of every arm run as two passes
inside one round. The claim under test is that **the pass that runs SECOND reads
lower**, whichever write occupies it — a within-round position term, as against
some other even/odd property of the round wearing its name.

## What phase 2 established, and the one thing it did not

Phase 2 ran two seeded six-round runs. Taking its findings from its own record
and from the merged-PR audit of #8601 that followed it:

- **`rf2-0gjqi`'s headline 10-of-12 did not reproduce.** The pass that ran
  second read lower in 7 of 12 blocks — chance.
- **The decomposed PASS term reproduced in sign**, +0.56% and +0.64% against
  `rf2-0gjqi`'s +0.68% and +0.21%. Four runs, four positive.
- **Neither run separated the pass from the parity, and the record's claim that
  one of them did is false.** Phase 2 reported that run 1's two groupings
  returned identical medians and run 2's came apart, and read the second as a
  separation. The audit refuted that: two marginal median contrasts that merely
  DIFFER are not two separated terms.

**THE DEFECT WAS IN THE DESIGN, NOT THE RIG.** Phase 2 argued its seeds were
admissible because the parity indicator and the pass indicator are orthogonal
over the pooled twelve blocks. That is correct arithmetic on the indicator
columns. It does not deliver what it was used for: **the block statistic is a
MEDIAN, and a median is not a linear functional**, so orthogonality of the design
columns does not give independence of the block medians.

**And the successor plan phase 2 wrote down would have repeated it.** Its first
recommendation was both seeds at symmetric difference 4 from parity. Every
balanced six-round schedule has a parity·pass inner product of `+6`, `+2`, `−2`
or `−6`; symmetric difference 4 is the `−2` case, and no balanced six-round
schedule reaches 0 at all. Taking that recommendation would have bought a
per-run design that still cannot identify, four times over instead of twice.

## The control that decides whether this window is worth its wall clock

`alloc_pass_design.cjs` is new here, and it is the whole of what makes this
window different from the last one. It **drives the shipped `decompose`** — the
same function `alloc_pass_position.cjs` reads the real corpus with, imported and
not reimplemented — over synthetic block sets whose true terms are known by
construction, and requires it to recover them.

Three fixtures. Over a schedule's rounds, set the block statistic to a known
additive model and read the two half-differences back:

| fixture | the block statistic, by construction | required PASS term | required PARITY term |
|---|---|---|---|
| pure PARITY | `+1` on an even round, `−1` on an odd one | `0` | `+1` |
| pure PASS | `+1` where `page` ran first, `−1` where `all` did | `+1` | `0` |
| both, unequal | `0.5 + 1·pass + 3·parity` | `+1` | `+3` |

The third uses unequal coefficients on purpose: a reader that had simply swapped
the two columns would be invisible at equal ones.

**The fixture bites, which is the half that matters.** On phase 2's run-2
schedule — `page, page, all, page, all, all`, the symmetric-difference-4
schedule its successor plan named — the shipped decomposition reads:

| schedule | pure PARITY reads | pure PASS reads | identifies |
|---|---|---|---|
| phase 2, run 2 (6 rounds) | PASS `−1`, PARITY `+1` | PASS `+1`, PARITY `−1` | NO |
| the parity schedule (6 rounds) | one number twice | one number twice | NO |
| all four schedules below (12 rounds) | PASS `0`, PARITY `+1` | PASS `+1`, PARITY `0` | YES |

A schedule that reports a pass term of `−1` on a corpus with no pass effect in it
is not a schedule this window may be read on.

## The design, fixed before the first run

**Four runs at twelve rounds.** The two changes phase 2 said it owed, and one it
did not name.

**TWELVE ROUNDS, AND THE COUNT IS LOAD-BEARING RATHER THAN MERELY LARGER.** Write
`q(r) = +1` where `page` ran first and `p(r) = +1` on an even round. Over `R`
rounds with balanced legs, `q · p = 4a − R` where `a` is the number of even
`page`-first rounds — so `q · p = 0` requires `R` divisible by 4, and no balanced
six-round schedule can reach it. At `R = 12` it is exactly the **2 × 2 balance**:
three even and three odd rounds in each pass group. A pure parity effect then
enters both pass groups symmetrically, the median of each group sits at the
level, and the median contrast identifies. That is why the round count moved,
and it is not the same reason phase 2 gave (a group of six rather than three, so
the shared-member coincidence thins out) — both hold, but only the first is what
makes the estimator correct rather than merely less lucky.

**A seed is admissible if all three hold**, and `p0_run.cjs` is untouched — this
is a design change, not a rig change:

1. **Not parity-tied.** The rig's own draw enforces this and redraws.
2. **`q · p = 0` within the run.** Not pooled. Pooled is what failed.
3. **`q · l = 0` within the run**, where `l(r) = r − (R−1)/2` is the centred
   round index — equivalently, the `page`-first and `all`-first rounds have equal
   index sums. Elapsed session time is a **measured** term on this instrument
   ([the mode rate tracks elapsed session time](the-mode-rate-tracks-elapsed-session-time.md)),
   so a linear within-run drift is a named nuisance rather than a hypothetical,
   and a design that let it load onto the pass contrast would repeat this bead's
   own mistake one column over.

**The seeds are chosen by a rule, not picked.** Enumerate `fk6pj-1`, `fk6pj-2`,
… in integer order and keep the admissible draws. Run 1 takes the first. Run 2
takes the first subsequent admissible seed drawing neither run 1's schedule nor
its complement. Runs 3 and 4 take the lowest-indexed seeds drawing the exact
complements of runs 1 and 2. `allocPassFlips` is pure, so every schedule below
was read out without a build, a server or a Chromium.

| # | seed | drawn flips | `page` runs FIRST in rounds | `q·p` | `q·l` | `q·(r mod 4)` |
|---|---|---|---|---|---|---|
| 1 | `fk6pj-9` | `0110 0110 0110` | 0, 3, 4, 7, 8, 11 | 0 | 0 | +12 |
| 2 | `fk6pj-11` | `0110 0110 1001` | 0, 3, 4, 7, 9, 10 | 0 | 0 | +4 |
| 3 | `fk6pj-2386` | `1001 1001 1001` | 1, 2, 5, 6, 9, 10 | 0 | 0 | −12 |
| 4 | `fk6pj-33` | `1001 1001 0110` | 1, 2, 5, 6, 8, 11 | 0 | 0 | −4 |

A `1` in the flips column is a round that drove its legs REVERSED, which is the
rig's own encoding; the base order is `[page, all]`, so `page` ran first exactly
where the flip is `0`. The digits are grouped in fours — rounds 0–3, 4–7, 8–11 —
for legibility and because an ungrouped run of twelve is a hex token that
`scripts/check_provenance_pins.py` correctly refuses to classify.

**Runs 3 and 4 are runs 1 and 2 complemented, and that is the third change.**
Complementing a schedule negates `q`, so it preserves all three criteria while
inverting the pass order at **every round index**. Pooled over a complementary
pair the pass contrast is therefore balanced against the round index itself
rather than only against its parity and its trend — every per-index term, and
every residual the design did not name, cancels out of the pooled pass contrast.

**WHAT THIS DESIGN DOES NOT BALANCE, NAMED RATHER THAN CONSTRAINED AWAY.** Two
columns are balanced and every other function of the round index is residual.
Run 1's schedule is a function of `r mod 4` — `page` runs first exactly where
`r mod 4` is 0 or 3 — so a nuisance of that exact shape would be fully
confounded with the pass column inside runs 1 and 3, and partially (4 of 12)
inside runs 2 and 4. It cancels in the four-run pool and in each complementary
pair, and nowhere else. No fourth criterion was added to filter it out: that
would be choosing schedules against a nuisance nobody has measured, and the
column is reported for every run instead.

Reproduction, from `implementation/`, one run at a time:

```
P0_ALLOC_PLAN=full P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=paired P0_ALLOC_ROUNDS=12 \
P0_ALLOC_PASS_ORDER=seeded P0_ALLOC_PASS_SEED=fk6pj-9 \
node core/test/re_frame/bench/p0_run.cjs --only alloc
```

and the design control itself, which needs none of that:

```
node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --controls
node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --select
```

**Every other parameter is phase 2's, unchanged**, so the two windows are read
against each other on the round count and the schedules alone: `full` plan, one
cell, B = 4 boundaries, W = 6 measured writes after one prime, three warm-up
windows, R = 20 on the ladder, `segOrder: parity`, `controlSlot: first`.
`P0_ALLOC_WRITES` was **not** moved to 5, for the reason phase 2 gave and this
window inherits: `rf2-onozm` sized a five-write window at **884,280 B** on the
one-sided window ceiling and its own conclusion is that five writes is SIZED,
NOT SAFE. That is a sizing answer for a different estimand, and no figure here
is quoted from it.

## The estimator, unchanged and deliberately so

Verbatim from phase 2, which took it verbatim from `rf2-0gjqi`. For every round
*r*, segment *s* and arm *a* the paired record carries four windows — the arm and
that segment's floor, each under each write, all four measured in round *r* on
the same page in the same process:

```
d_all (r,s,a) = (arm@all .legMedian − floor@all .legMedian) / B
d_page(r,s,a) = (arm@page.legMedian − floor@page.legMedian) / B
Δ     (r,s,a) = d_page − d_all
```

**A round contributes a cell only if ALL FOUR of its windows certified.** B = 4.
**THE FLOOR MUST BE SUBTRACTED** — reading the same blocks off the raw
`perBoundaryPerWrite` field gives the wrong count and the wrong magnitude, and
`alloc_pass_position.cjs --self-test` pins that disagreement. **The block is the
ROUND, not the rung**: the block statistic is the median of `Δ / d_all` over that
round's certified mid-rung (R3, R7) cells. Which pass ran first is read off
`perRound[r].writeLegs[0]`, never recomputed.

**The estimator is not touched by this window and that is the point.** An
estimator that changed between a pre-registration and the runs it will be read
on is not pre-registered. `alloc_pass_position.cjs` carries the identical blob
hash `0d5b2c5f9df3d216f3aef4370b15557ba350e741` before this window and after it,
and `p0_run.cjs` the identical blob hash
`ddc4f137bc2b5ee5ac7952dcb4f2cded7fcd0ca0`. Both are restated in
[the instrument did not move](#the-instrument-did-not-move) below, read again
after the last run.

## The controls, and this time they arbitrate

**The audit's second finding was that they do not.** `alloc_pass_position.cjs`'s
`report()` prints `controlVerdict`, `verification.unverified`, `passOrder`,
`parityTied` and `scheduleDrove`, and then feeds every row to the blocks, the
headline and both decompositions regardless. A copy of a real record with
`controlVerdict.ok = false` still produces the headline.

**That defect is not repaired here, and the omission is deliberate.** Repairing
the estimator inside the window it is about to be read on is precisely what a
pre-registration exists to prevent. Instead the boundary runs in FRONT of it,
declared before the runs, in `alloc_pass_design.cjs --admit`. A run failing any
clause contributes no figure to this page, and the clause it failed is published
beside it.

1. `controlVerdict.ok` is true.
2. `verification.unverified` is 0.
3. `passOrder` is `seeded`, `passSeed` is the seed this run declared, and
   `passSchedule.parityTied` is false.
4. The drawn schedule is the one this run declared, and it satisfies all three
   design criteria and recovers all three fixtures.
5. The drive matches the draw: `passSchedule.flips[r]` against
   `perRound[r].writeLegs`, round by round.
6. `rounds` is 12, `writePaired` is true, `writeLegs` is `["page","all"]`,
   `segOrder` is `parity` and `controlSlot` is `first` — the two other
   diagnostic modes stayed off.

**The boundary is proved in both directions before the window**, which a control
that only ever passes is not: a well-formed run is admitted, and then each of
eight clauses is broken one at a time on that same run and each break must
refuse it. Phase 2's own committed six-round record is run through it and is
refused, on the round count and on `q·p = 2`, rather than admitted on its
(perfectly good) control fields.

Two further controls are read and reported whatever they say:

7. **The estimator's own positive control.** `alloc_pass_position.cjs
   --self-test` reproduces every figure `rf2-0gjqi` published — twelve blocks
   with their `n`, both decompositions, the 10-of-12, the parity tie, the null
   arm's 38 cells and the floor-free estimator's wrong 3 of 6.
8. **The null arm (R = 0) reads flat.** It is the only population here whose true
   value is known in advance, and it is what licenses reading mid-rung numbers at
   all.

## The reading, and the band

**The reading is the PASS term**: the half-difference of the two pass groups'
block medians, per run and pooled over all four. The **PARITY term** on the same
blocks is read at the same time and published whatever it says. The raw
`second read lower in k of 48` count is reported and is **not** the headline —
phase 2 established that the count conflates the term with the run's level.

**The band is the null arm's own noise floor, and it is not a tolerance.** The
block statistic is a ratio, so a term of *x* implies a Δ of `x · d_all` bytes per
boundary at the mid rungs. `rf2-2rtt6.140` published this instrument's
non-cancellation floor as **1.5 B per boundary** (median) and **4.5 B** (p90),
and set its refusal bar at **ten times the p90** on the ground that a figure
within an order of magnitude of the noise floor is not a measurement. The same
construction applies here, on **this window's own null arm rather than the
published one**:

> **A PASS term is READABLE only if its implied Δ at the mid rungs exceeds ten
> times this window's own null-arm p90 on Δ.** Below that it is reported as
> measured and explicitly not read.

Nothing is dialled by this. It moves no gate, no budget and no constant, it is
computed from the window's own R = 0 arm, and it is stated before the term is
known.

## The stopping rule

**Four runs. All four are taken and all four are reported**, in the seed order
above, strictly one at a time. No run is dropped, no seed is re-rolled, and no
replacement run is taken for a refused one — a refused run is published with the
clause that refused it. If a run produces no exit code at all it is re-run once
and **both attempts are reported**, because an absent exit code is no verdict
rather than a pass.

**Exit 1 on the falls gate is admissible**, as it was in all four previous
allocation runs on this estimand: it is this row's code for a run carrying
refused windows, every figure here is a per-window figure off certified windows,
and **no slope is quotable from such a run and none is quoted**. Any other
non-zero exit is not admissible.

## The pre-registered outcomes

Four, and which is which is fixed here rather than chosen after the run. In each
the PASS and PARITY terms are the pooled ones over 48 blocks, with the four
per-run readings reported beside them.

- **The PASS term reads above the band and the PARITY term below it** → the
  carrier is the within-round pass position. `rf2-fk6pj`'s observation is
  established, separated from every other even/odd property of a round by a
  design that is proved to separate them.
- **The PARITY term reads above the band and the PASS term below it** → the
  carrier is some other even/odd property of the round, and both `rf2-0gjqi`'s
  section C and phase 2's pass term were the parity column wearing the pass
  column's name.
- **BOTH read below the band** → the term did not reproduce at a magnitude this
  instrument can read, which is an answer about the instrument and closes the
  question at this precision.
- **BOTH read above the band** → two real terms, which this design can carry
  because it identifies both, and the window says so rather than attributing the
  sum to either.

**A magnitude comparison against phase 2's +0.56% / +0.64% or `rf2-0gjqi`'s
+0.68% / +0.21% is a comparison of the same estimator on different sessions and
nothing more.** None of them is comparable to the published mid-rung absolute
median of 1.68%, which is a different estimand between two processes.

---

*Everything above this line was committed before the first run, as this branch's
pre-registration commit — the one whose subject begins "pre-register rf2-fk6pj's
twelve-round window". Everything below was written after the last one. No commit
SHA is cited on this page on purpose: an authored head that has not landed is
unresolvable from a fresh clone, and the commit order is the evidence rather than
the identifier.*

## The answer, first

**The design did what phase 2's could not: it identified.** All four runs were
admitted, `q · p` read 0 inside every run and 0 over the pooled forty-eight
blocks, and the estimator had been proved to recover a pure pass effect and a
pure parity effect on each of those four schedules *before any of them ran*.

**And the pre-registered band refuses the answer it produced.** Both terms fall
an order of magnitude below it. Under the pre-registration this window therefore
reads as **outcome 3 — both terms below the band** — and it does **not** establish
the pass-position term.

- **The PASS term is positive in all four runs**: **+0.65%**, **+0.64%**,
  **+0.20%**, **+0.29%**, pooling to **+0.45%** over 48 blocks.
- **The PARITY term is not**: **−0.23%**, **−0.06%**, **+0.24%**, **+0.34%** — two
  of each sign — pooling to **+0.02%**. On the two complementary pairs, which are
  the only contrasts here balanced against the round INDEX rather than only its
  parity, it reads **+0.01%** and **−0.00%**.
- **The band is 610 B per boundary** — ten times this window's own null-arm 90th
  percentile of 61 B — and the pass term's implied Δ at the mid rungs is
  **21.4 – 77.0 B per boundary**, 37.8 B at the median `d_all` of 8,354. The
  parity term's is **1.7 B**. Both are below the band; the pass term by a factor
  of about 16.
- **Second-lower read in 34 of 48 blocks**, median second−first **−0.50%**. It is
  reported and it is not the headline, for the reason phase 2 established.
- **A defect in this window's OWN pre-registered band was found and is RECORDED
  rather than repaired.** See [the band, which refuses](#c-the-band-which-refuses).
- **The instrument's published non-cancellation floor does not hold on this
  corpus, and phase 2's explanation for that is refuted at four times the sample
  size.** 23.2% of null-arm cells sit above the published 45 B/boundary bar. See
  [the null arm](#d-the-null-arm-and-the-published-floor).

**`rf2-fk6pj` therefore stays OPEN.** No rig file was edited, τ was not read or
moved in either direction, and no gate, band, threshold or budget constant was
touched.

## Runtime, beside every figure

Chromium via Playwright at build **`chromium/147.0.7727.15`**, Node **v24.13.0**,
`win32/x64/10.0.26200` (all recorded by the run, `rf2-24o2z`). shadow-cljs
`release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`. Every
run's build reports **195 files, 140 compiled, 0 warnings**, and every run's
`shadow-cljs - config:` line names this worktree's own
`implementation/shadow-cljs.edn`, so none of them compiled a sibling checkout's
sources. No `java` process existed on the box when the window opened, so no
shadow-cljs server could have served a stale build.

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stayed the declared 0.25
placeholder and `ALLOC_FALL_THRESHOLD_B` stayed 600,000. W stayed 6 measured
writes after one prime (7 window writes), three warm-up windows, four roots,
B = 4 boundaries, R = 20 on the ladder, `segOrder: parity`, `controlSlot: first`.
The only parameters that moved from phase 2 are the round count and the seeds.

**All four runs captured exit 1**, this row's code for a run carrying refused
windows, on the falls gate and the leg-tolerance gate. **No slope is quotable
from such a run and none is quoted here**; every figure on this page is a
per-window figure off certified windows.

| run | opened (UTC) | wall clock | falls in measured windows | refused windows | busy fraction | free memory at open | session |
|---|---|---|---|---|---|---|---|
| 1 | 22:13:58 | 3 m 29 s | 99 | 98 | 0.238 | 14.46 GB of 68.11 | first of the session |
| 2 | 22:17:50 | 3 m 28 s | 102 | 96 | 0.225 | 14.89 GB of 68.11 | second, same session |
| 3 | 22:21:22 | 3 m 26 s | 104 | 101 | 0.231 | 15.11 GB of 68.11 | third, same session |
| 4 | 22:24:55 | 3 m 30 s | 102 | 95 | 0.203 | 14.55 GB of 68.11 | fourth, same session |

**ALL FOUR RUNS ARE ONE SESSION**, taken back to back on one box over fourteen
minutes — `session.runsInSession` reads 1, 2, 3, 4 against a single
`sessionStartedAt`. **The independent block count for anything session-level is
therefore 1, not 4 and certainly not 48**, which is this bead's own standing
complaint applied to its own successor. Every nominal test below is quoted as
what the nominal test says and nothing more.

**This is an ALLOCATION estimand, so it took no quiet-box slot** — a census of
monotone byte counters reads the same on a loaded machine (`rf2-ojehu`). The box
was not exempt for CAPACITY and it was not shared: the peer browser build that
was running when this window was dispatched had finished, no other bench-class
run was on the box, and the four runs were taken strictly one at a time.

**One attempt produced no window and is reported rather than dropped.** The first
invocation of run 1 failed in under a second with `Cannot find module …
shadow-cljs`: this worktree had no `implementation/node_modules`. It measured
nothing, wrote no dataset and is not a run of the window; the junction was made
and run 1 was taken. It is recorded because the stopping rule says every attempt
is reported.

## The instrument did not move

Read before the first run and again after the last, with the same command:

| file | blob hash, before and after |
|---|---|
| `implementation/core/test/re_frame/bench/p0_run.cjs` | the identical blob hash `ddc4f137bc2b5ee5ac7952dcb4f2cded7fcd0ca0` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs` | the identical blob hash `0d5b2c5f9df3d216f3aef4370b15557ba350e741` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs` | the identical blob hash `cffd220eb74f18ed9d9088b3b90774311440f5f5` |

The rig and both readers are byte-identical across the window. `p0_run.cjs` was
not edited by this window at all.

## Admissibility, per run

`alloc_pass_design.cjs --admit` over the four datasets, before anything was read:

| run | seed | pass order | parity-tied | schedule drove what it drew | `q·p` | control | B/double | differential | unverified | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `fk6pj-9` | `seeded` | no | yes | 0 | OK | 8.08 | **8.00** | 0 | ADMITTED |
| 2 | `fk6pj-11` | `seeded` | no | yes | 0 | OK | 8.08 | **8.00** | 0 | ADMITTED |
| 3 | `fk6pj-2386` | `seeded` | no | yes | 0 | OK | 8.08 | **8.00** | 0 | ADMITTED |
| 4 | `fk6pj-33` | `seeded` | no | yes | 0 | OK | 8.08 | **8.00** | 0 | ADMITTED |

The differential — D = 1,000 less D = 400 — reads **8.00 B/double against a
predicted 8** in all four runs, replicating every previous window. **The schedules
drove what they drew**, checked round by round against `passSchedule.flips` rather
than assumed. And the estimator reproduced the window it is read against:
`alloc_pass_position.cjs --self-test` passes, exit 0.

## A. The round blocks

Forty-eight blocks — four runs of twelve rounds — over **316 certified mid-rung
cells**, 3 to 8 per block. `second−first` is the same per-round statistic
re-signed by which pass ran second; it is arithmetic on the column beside it, not
a further estimator.

| run | round | pass that ran FIRST | parity | n mid cells | median `(page−all)/all` | second−first |
|---|---|---|---|---|---|---|
| 1 | 0 | `page` | even | 4 | +0.79% | -0.79% |
| 1 | 1 | `all` | odd | 8 | -0.78% | -0.78% |
| 1 | 2 | `all` | even | 7 | -1.05% | -1.05% |
| 1 | 3 | `page` | odd | 5 | +2.81% | -2.81% |
| 1 | 4 | `page` | even | 7 | +0.08% | -0.08% |
| 1 | 5 | `all` | odd | 4 | -0.34% | -0.34% |
| 1 | 6 | `all` | even | 8 | +0.14% | +0.14% |
| 1 | 7 | `page` | odd | 3 | +0.74% | -0.74% |
| 1 | 8 | `page` | even | 6 | +1.22% | -1.22% |
| 1 | 9 | `all` | odd | 4 | +0.43% | +0.43% |
| 1 | 10 | `all` | even | 8 | -0.71% | -0.71% |
| 1 | 11 | `page` | odd | 8 | +0.71% | -0.71% |
| 2 | 0 | `page` | even | 4 | +1.97% | -1.97% |
| 2 | 1 | `all` | odd | 7 | +0.02% | +0.02% |
| 2 | 2 | `all` | even | 7 | -1.10% | -1.10% |
| 2 | 3 | `page` | odd | 7 | +1.29% | -1.29% |
| 2 | 4 | `page` | even | 7 | +2.08% | -2.08% |
| 2 | 5 | `all` | odd | 8 | -1.84% | -1.84% |
| 2 | 6 | `all` | even | 8 | -0.20% | -0.20% |
| 2 | 7 | `page` | odd | 8 | +0.91% | -0.91% |
| 2 | 8 | `all` | even | 7 | -0.16% | -0.16% |
| 2 | 9 | `page` | odd | 7 | -0.97% | +0.97% |
| 2 | 10 | `page` | even | 7 | +0.00% | +0.00% |
| 2 | 11 | `all` | odd | 7 | +0.05% | +0.05% |
| 3 | 0 | `all` | even | 3 | -1.38% | -1.38% |
| 3 | 1 | `page` | odd | 7 | +0.27% | -0.27% |
| 3 | 2 | `page` | even | 6 | +0.54% | -0.54% |
| 3 | 3 | `all` | odd | 7 | +0.36% | +0.36% |
| 3 | 4 | `all` | even | 8 | +0.77% | +0.77% |
| 3 | 5 | `page` | odd | 6 | +0.09% | -0.09% |
| 3 | 6 | `page` | even | 7 | +1.10% | -1.10% |
| 3 | 7 | `all` | odd | 7 | -0.72% | -0.72% |
| 3 | 8 | `all` | even | 7 | -0.33% | -0.33% |
| 3 | 9 | `page` | odd | 4 | -0.13% | +0.13% |
| 3 | 10 | `page` | even | 8 | +0.82% | -0.82% |
| 3 | 11 | `all` | odd | 7 | +0.96% | +0.96% |
| 4 | 0 | `all` | even | 3 | +0.02% | +0.02% |
| 4 | 1 | `page` | odd | 7 | +0.47% | -0.47% |
| 4 | 2 | `page` | even | 8 | +1.03% | -1.03% |
| 4 | 3 | `all` | odd | 7 | -1.23% | -1.23% |
| 4 | 4 | `all` | even | 7 | +0.38% | +0.38% |
| 4 | 5 | `page` | odd | 7 | +1.34% | -1.34% |
| 4 | 6 | `page` | even | 8 | +0.56% | -0.56% |
| 4 | 7 | `all` | odd | 8 | +0.03% | +0.03% |
| 4 | 8 | `page` | even | 6 | +0.01% | -0.01% |
| 4 | 9 | `all` | odd | 7 | -1.27% | -1.27% |
| 4 | 10 | `all` | even | 8 | -0.16% | -0.16% |
| 4 | 11 | `page` | odd | 7 | -0.96% | +0.96% |

**The pass that ran second read lower in 34 of the 48 blocks**, median −0.50%.
Phase 2 read 7 of 12 and `rf2-0gjqi` 10 of 12, so across three windows this count
has read 83%, 58% and 71%. Its nominal one-sided binomial p over 48 is 0.0028,
and **that number is quoted only as what the nominal test says**: the 48 blocks
are 4 runs in 1 session, not 48 independent trials.

**The design came out as drawn.** `q · p` reads **0 inside every run** and 0 over
the pooled forty-eight — the first allocation corpus that can say the first of
those. On a `parity` corpus the same reader reads ±n.

## B. The decomposition, on both groupings

| block set | grouping | group A | group B | half-difference: THE TERM | half-sum |
|---|---|---|---|---|---|
| run 1 | PASS (`page`-first vs `all`-first) | +0.76% (n=6) | −0.53% (n=6) | **PASS +0.65%** | write +0.12% |
| run 1 | PARITY (even vs odd round) | +0.11% (n=6) | +0.57% (n=6) | PARITY −0.23% | write +0.34% |
| run 2 | PASS | +1.10% (n=6) | −0.18% (n=6) | **PASS +0.64%** | write +0.46% |
| run 2 | PARITY | −0.08% (n=6) | +0.04% (n=6) | PARITY −0.06% | write −0.02% |
| run 3 | PASS | +0.41% (n=6) | +0.01% (n=6) | **PASS +0.20%** | write +0.21% |
| run 3 | PARITY | +0.65% (n=6) | +0.18% (n=6) | PARITY +0.24% | write +0.42% |
| run 4 | PASS | +0.51% (n=6) | −0.07% (n=6) | **PASS +0.29%** | write +0.22% |
| run 4 | PARITY | +0.20% (n=6) | −0.47% (n=6) | PARITY +0.34% | write −0.13% |
| pair A (runs 1 + 3) | PASS | — | — | **PASS +0.53%** | — |
| pair A (runs 1 + 3) | PARITY | — | — | PARITY **+0.01%** | — |
| pair B (runs 2 + 4) | PASS | — | — | **PASS +0.45%** | — |
| pair B (runs 2 + 4) | PARITY | — | — | PARITY **−0.00%** | — |
| pooled 48 | PASS | +0.72% (n=24) | −0.18% (n=24) | **PASS +0.45%** | write +0.27% |
| pooled 48 | PARITY | +0.11% (n=24) | +0.07% (n=24) | PARITY **+0.02%** | write +0.09% |

**No two groupings returned the same pair of medians anywhere**, which is what
phase 2's design could not manage and what the round count was moved to buy.

**The two complementary pairs are the strongest contrasts on this page**, because
they are the only ones balanced against the round INDEX rather than only against
its parity and its trend. Both put the PARITY term at essentially exactly zero —
+0.01% and −0.00% — while the PASS term reads +0.53% and +0.45%. Two pairs is
two, and neither is a session of its own.

**A per-round-index term is visible, and the pooled design is what removes it.**
Within each complementary pair the PASS term is larger in the original schedule
than in its complement — +0.65% against +0.20%, and +0.64% against +0.29% — by
half-differences of +0.22% and +0.17%. Complementing inverts `q` at every round
index, so a contrast that changes with the complement is a term tied to the round
index rather than to the pass. **That is exactly the class of nuisance the
complementary pairing was chosen to cancel**, it cancels in each pair and in the
pool, and it is reported here rather than left as a residual nobody looked at.
`q · (r mod 4)` is one such column and it flips sign with the complement by
construction; nothing here identifies which index-tied column it is, and no
mechanism is proposed.

## C. The band, which refuses

The block statistic is a ratio, so a term of *x* implies a Δ of `x · d_all` bytes
per boundary. The mid-rung `d_all` population, over the same 316 certified cells:

| population | n | min | median | max | implied Δ of the pooled PASS term |
|---|---|---|---|---|---|
| R3 | 164 | 4,740 | 6,563 | 8,583 | 29.7 B/boundary |
| R7 | 152 | 10,466 | 13,668 | 17,032 | 61.8 B/boundary |
| both rungs | 316 | 4,740 | 8,354 | 17,032 | 37.8 B/boundary (21.4 – 77.0 over the range) |

**The pre-registered band is ten times this window's own null-arm 90th percentile
on Δ**, which is 61 B/boundary, so the band is **610 B/boundary**.

| term | pooled reading | implied Δ at the median `d_all` | band | verdict |
|---|---|---|---|---|
| PASS | +0.45% | 37.8 B/boundary | 610 B/boundary | **below — not read** |
| PARITY | +0.02% | 1.7 B/boundary | 610 B/boundary | **below — not read** |

**Under the pre-registration this is outcome 3, and the window does not establish
the pass-position term.** The reading is published anyway, because the
pre-registration says every term is reported whatever it says, and because a term
refused by the band is a different statement from a term that read zero: this one
read positive in four runs of four and is refused on magnitude, not on sign.

### The defect in this band, recorded and NOT repaired

**The band as written compares an aggregate against a per-observation noise
scale.** The PASS term is a contrast of two medians, each taken over 24 block
medians, each of those taken over 3 to 8 cells — 316 cells in all. The null arm's
90th percentile is a property of ONE cell. A statistic aggregated over hundreds
of observations does not have to clear a single observation's tail to be real,
and a band built that way is too strict by roughly the square root of the count.
The published 45 B/boundary bar it was modelled on was set for **per-boundary
cell medians** — single published figures — and this page's pre-registration said
it was applying "the same construction", which it was not entitled to.

**It is recorded and not repaired, and that is the correct outcome rather than a
concession.** Choosing the statistic after seeing the answer is the precise thing
a pre-registration exists to prevent, and the answer is already on the screen. A
band computed now, against a term already known, would carry none of the
authority the refused one carries. What the successor owes is a band derived from
the DISPERSION OF THE AGGREGATE — the spread of the per-run terms, or a null-arm
contrast pushed through the identical block-and-decompose pipeline so that the
noise scale and the signal scale are the same object — declared before its runs.

## D. The null arm, and the published floor

The R = 0 arm reads nothing, so `arm − floor` must be zero under either write. It
is the only population here whose true value is known in advance.

| window | n | median Δ | absolute median | 90th percentile | max | over the 45 B/boundary bar |
|---|---|---|---|---|---|---|
| run 1 | 37 | 0 | 3 | 59.5 | 62.5 | — |
| run 2 | 42 | 0 | 8.25 | 64 | 76 | — |
| run 3 | 42 | 0 | 2.25 | 53.5 | 61 | — |
| run 4 | 43 | 0 | 3 | 62.5 | 135.5 | — |
| this window, pooled | 164 | 0 | 3 | **61** | 135.5 | **38 of 164 (23.2%)** |
| phase 2, pooled | 40 | 0 | 3 | 56.5 | 62.5 | 4 of 40 (10.0%) |
| `rf2-0gjqi`, pooled | 38 | 0 | 1.5 | 4.5 | 96.5 | 2 of 38 (5.3%) |

**The median is 0 in every run and the absolute median holds at 3 B/boundary**, so
the instrument still cancels in the centre, and the mid-rung figures — thousands
of bytes per boundary — are read far above it.

**But phase 2's explanation of its own 90th percentile is refuted here.** Phase 2
read 56.5 against `rf2-0gjqi`'s 4.5 and wrote that the jump was *an artefact of
the count* — four outliers in forty putting one exactly at the 90th-percentile
index. **At n = 164 that no longer holds**: the fraction of the zero-signal
population above the published 45 B/boundary bar is 5.3%, then 10.0%, then
**23.2%**, and a quarter of the null arm sitting above the bar is a distribution
rather than an index. The full quantile ladder is p50 = 3, p75 = 19.5,
p80 = 54.5, p85 = 59.5, p90 = 61, p95 = 64.

**This is a claim about the instrument, not about this bead**, and it says the
non-cancellation floor `rf2-2rtt6.140` published — 1.5 B median, 4.5 B p90, bar at
45 B — **does not describe the corpus the instrument is producing now**. Nothing
is widened here and no bar is moved: the finding is filed and the published bar
stands until a window that owns it says otherwise.

## What this window establishes, and what it does not

**ESTABLISHED.**

1. **A design that identifies exists, is cheap, and is proved before it is run.**
   Twelve rounds with `q · p = 0` and `q · l = 0` inside each run, four runs, two
   complementary pairs. The estimator recovers a pure pass effect, a pure parity
   effect and an unequal mixture of both on every one of the four schedules, and
   fails all three fixtures on phase 2's schedules. The whole control costs no
   build, no server and no Chromium.
2. **No balanced six-round schedule can identify at all**, so phase 2's own
   successor plan — both seeds at symmetric difference 4 — would have bought four
   more runs of the same confound. `q · p = 4a − R` is never zero at R = 6.
3. **Under a design proved to separate them, the PARITY column is where the
   nothing is.** Both complementary pairs put it at +0.01% and −0.00% and the
   pooled forty-eight at +0.02%, while the PASS column reads +0.45% and is
   positive in four runs of four.
4. **The instrument's zero-signal Δ distribution has a much heavier tail than the
   published floor describes**, 23.2% of it above the published refusal bar at
   n = 164, and the count-artefact explanation offered for that is refuted.

**NOT ESTABLISHED, so it is not rediscovered as new.**

1. **THE PASS TERM IS NOT ESTABLISHED.** The pre-registered band refuses it, this
   window reads as outcome 3, and the band's own defect does not license reading
   past it inside the window that declared it.
2. **FOUR RUNS ARE ONE SESSION.** Every figure here is one session on one box in
   fourteen minutes. The session-level independent count is 1.
3. **NO MECHANISM IS PROPOSED AND NONE IS EXCLUDED.**
4. **THE MAGNITUDE IS STILL NOT COMPARABLE TO THE OLD EFFECT.** +0.45% is a
   within-round pass-position term; the published mid-rung absolute median of
   1.68% is a between-process quantity. Right sign, wrong size, different
   estimand.
5. **THE INDEX-TIED TERM IS SEEN, NOT IDENTIFIED.** The complementary pairs
   disagree by +0.22% and +0.17%, which locates a per-round-index nuisance and
   names no column.

**WHAT THE SUCCESSOR OWES**, in terms a dispatch can act on, and none of it a rig
change:

1. **A BAND ON THE AGGREGATE, declared before its runs** — the dispersion of the
   per-run terms, or a null-arm contrast pushed through the identical
   block-and-decompose pipeline. This is the one thing that would let a window of
   this design return a verdict rather than a refusal.
2. **MORE THAN ONE SESSION.** Four runs in fourteen minutes is one session, and
   the session is the block that has never been replicated on this estimand.
3. **THE READER'S FAIL-CLOSED BOUNDARY** (audit #8601, item 2) still belongs in
   `alloc_pass_position.cjs` rather than in front of it. It was left unrepaired
   here on purpose and it is not repaired by this page.
