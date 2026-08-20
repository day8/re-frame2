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

## Runtime, beside every figure

## The instrument did not move

## Admissibility, per run

## A. The round blocks

## B. The decomposition, on both groupings

## C. The band

## D. The null arm

## What this window establishes, and what it does not
