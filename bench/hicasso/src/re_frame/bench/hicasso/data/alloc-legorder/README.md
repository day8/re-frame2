# The pre-registration for `rf2-fk6pj`'s phase-4 window

**COMMITTED BEFORE RUN 1. Nothing in this file was written or edited after a
figure was seen.** The machine-readable half is `pre-registration.json` beside
it, and it is what `alloc_pass_position.cjs --declared` reads; this file is the
same declaration in prose, plus the parts a reader cannot check.

## The question

Phase 3 (PR #8615, record `the design control comes before the window`) took
four twelve-round runs on a design that identifies — `q·parity = 0` inside every
run and pooled — and its **pre-registered band refused the pass term**. It then
recorded two defects in its own design rather than repairing them, because an
estimator must not change between a pre-registration and the runs it is read on:

1. **The band was on the wrong object.** It compared an AGGREGATE — a contrast
   of medians over 316 cells — against a PER-OBSERVATION noise scale, one cell's
   p90. Too strict by roughly the square root of the count.
2. **Four runs are one session.** `runsInSession` 1..4 against a single
   `sessionStartedAt`, fourteen minutes on one box. The session-level
   independent count was 1.

And the merged-PR audit of #8615 found a third, in the arbiter rather than the
band: **it was not fail-closed on corpus shape.**

This window owes all three and no rig change.

## The run count, fixed before run 1

**EIGHT RUNS. TWO SESSIONS OF FOUR.** Fixed here, before the first run, and not
revisited: the number below is the number that will be taken whatever the
figures do.

**There is no replacement rule and no partial credit.** If any declared run is
refused by the boundary, the window returns a REFUSAL naming the run and the
clause, and publishes no figure. A stopping rule that let a refused run be
re-taken would let the corpus be selected on its controls.

**Every attempt is reported**, including any that produces no window at all.

## The design

Phase 3's design, unchanged, run twice. **That is the point rather than a
convenience:** repeating one design in a second session makes the SESSION the
only thing that differs between the two halves, which is the one contrast
neither this bead nor `rf2-0eu1s` has ever had. `rf2-0eu1s` asks for it in terms
— *"Re-running phase 3's twelve-round design unchanged in a fresh session is the
cheapest measurement that would."*

Twelve rounds. Four schedules per session, chosen by `alloc_pass_design.cjs`'s
enumeration RULE and not by a pick — the first admissible seed under the
`fk6pj-` prefix, the first subsequent one drawing neither that schedule nor its
complement, then the lowest-indexed seeds drawing the exact complements of both:

| run | session | seed | flips | `q·parity` | `q·linear` |
|---|---|---|---|---|---|
| 1 | A | `fk6pj-9` | `011001100110` | 0 | 0 |
| 2 | A | `fk6pj-11` | `011001101001` | 0 | 0 |
| 3 | A | `fk6pj-2386` | `100110011001` | 0 | 0 |
| 4 | A | `fk6pj-33` | `100110010110` | 0 | 0 |
| 5 | B | `fk6pj-9` | `011001100110` | 0 | 0 |
| 6 | B | `fk6pj-11` | `011001101001` | 0 | 0 |
| 7 | B | `fk6pj-2386` | `100110011001` | 0 | 0 |
| 8 | B | `fk6pj-33` | `100110010110` | 0 | 0 |

Runs 3 and 4 are the exact complements of 1 and 2, and 7 and 8 of 5 and 6.
Complementing inverts the pass order at every round INDEX, so each pair balances
the pass contrast against the index itself and not merely against its parity and
its trend.

### The session boundary, and the dial that was not touched

A session is defined by the rig as a run starting within
`P0_BOX_SESSION_GAP_MIN` of the previous run's END — **default 60 minutes, and
it stays 60.** Session B is taken after a genuine idle gap of **at least 61
minutes** measured from run 4's end.

**The dial was deliberately not lowered to buy a shorter gap.** Every committed
corpus on this instrument uses the 60-minute definition, and a window that
redefined "session" to fit its own wall clock would not be comparable to any of
them — and would be measuring its own declaration rather than the box.

## The estimator, unchanged and deliberately so

Verbatim from phase 3, which took it from phase 2, which took it from
`rf2-0gjqi`. For every round *r*, segment *s* and arm *a*:

```
d_all (r,s,a) = (arm@all .legMedian − floor@all .legMedian) / B
d_page(r,s,a) = (arm@page.legMedian − floor@page.legMedian) / B
Δ     (r,s,a) = d_page − d_all
```

A round contributes a cell only if **all four** of its windows certified. B = 4.
**THE FLOOR MUST BE SUBTRACTED** — reading the same blocks off the raw
`perBoundaryPerWrite` field gives 3 of 6 and the wrong magnitude, and
`alloc_pass_position.cjs --self-test` pins that disagreement in both directions.
**The block is the ROUND, not the rung:** the block statistic is the median of
`Δ / d_all` over that round's certified mid-rung (R3, R7) cells. Which pass ran
first is read off `perRound[r].writeLegs[0]`, never recomputed.

**The headline statistic is the MEAN OF THE PER-RUN PASS TERMS**, and that is a
change from phase 3's pooled-over-all-blocks term, declared here with its
reason. Each run is a complete balanced design on its own, so a run-level offset
cancels exactly out of every per-run contrast before the runs are combined.
Pooling all blocks first does not have that property, and phase 2 measured a
run-level offset of 20% on a floor. The pooled term is reported beside it.

## The band

**A RESTRICTED RANDOMISATION OF THE PASS LABELS, ON THE AGGREGATE.**

The reference distribution of a term is what that term reads when the pass
LABELS are re-drawn from the schedules the design would equally have admitted,
with the block VALUES left exactly where they were measured. The noise scale and
the signal scale are therefore **the same object**: the same statistic, on the
same blocks, at the same aggregation level. That is what phase 3's band was not.

Restricted, and the restriction is the design's own: a re-labelling is
admissible only if it is balanced with `q·parity = 0` and `q·linear = 0`. **At
twelve rounds there are exactly 48 such schedules and the set is CLOSED UNDER
COMPLEMENT**, so every reference distribution here is exactly symmetric about
zero by construction rather than by assumption, and any parity structure or
linear drift in the block values enters every re-labelling symmetrically.

Each run's own reference is **exact** — all 48 re-labellings, so a per-run
p-value is a rank among 48 and involves no sampling. Only the combination across
runs is sampled, because the product is 48⁸: **20,000 draws from the committed
seed `legorder-band-1`**, and the p-value counts the observation itself, so the
smallest attainable two-sided p is 1/20001.

**α = 0.05, two-sided.**

### What it does not control, named rather than assumed away

Residual functions of the round index that the two balanced columns do not span
— `r mod 4` among them — are as free in the reference as they are in the
observation. That is the same residual the design names and does not filter on.

### Why it is not built on the null arm's p90

`rf2-0eu1s` measured that the R = 0 null arm is **two disjoint populations** —
242 cells running 0..21, then nothing at all, then 44.5..135.5 — so a pooled
percentile of it is not a magnitude at all and the ten-times rule returns 45 on
one window and 610 on the next. **That bead is awaiting an operator ruling and
nothing here pre-empts it:** this band takes no percentile of the null arm,
cites no published floor, and is not a byte threshold in either direction. It is
a rank of one number among others computed the same way.

### The null arm is the band's own negative control

The identical machinery runs on the R = 0 null arm, whose true term is zero by
construction. At R = 0 the denominator `d_all` is the quantity the arm is
SUPPOSED to read as zero, so the null-arm block statistic is the median of `Δ`
in bytes per boundary rather than the ratio. **The two readings are never
compared to each other** — the null arm is a control on the BAND, not a noise
scale a magnitude is measured against.

## The outcomes, declared before the runs

1. **ESTABLISHED** — the signal's two-sided randomisation p ≤ 0.05 **and** the
   null-arm control's p > 0.05.
2. **NOT ESTABLISHED** — the signal's p > 0.05. The term sits inside the spread
   the same design returns on re-labelled data.
3. **NO VERDICT** — the null-arm control itself returns p ≤ 0.05. A band that
   fires on a known-zero population cannot adjudicate the mid rungs, and this is
   a refusal rather than a footnote.

**The session qualifier, which bounds the CLAIM rather than deciding it.** The
term is computed per session. If both sessions agree in sign with the pooled
term the claim reads "carried by both sessions taken"; otherwise it is capped at
a single session whatever outcome 1 says. **With two sessions that is a sign
agreement on two blocks and nothing stronger**, and it is quoted as exactly
that.

**The independent block count is 2 at the session level**, not 8 and certainly
not 96. This bead's own standing complaint, applied to its own successor.

## What is not moved

`P0_ALLOC_WRITES` stays **6**. `rf2-onozm` sized a five-write window at 884,280 B
on the one-sided window ceiling and closed saying **five writes is SIZED, NOT
SAFE** — only 5 of 12 cells land inside the certifying band and the worst clears
the ceiling by 0.6%. That is a sizing answer for a different estimand (R = 20
certification), moving it would change window totals, collection counts and the
certification pattern on top of the one change this window exists to make, and
no figure here is quoted from it.

`segOrder` stays **`parity`**. The window does **not** take
`P0_ALLOC_SEG_ORDER=fixed-reversed`: that arm belongs to `rf2-csca8`, which is
open with its readers under active repair, and a reading taken today would be
adjudicated by a reader about to change.

`ALLOC_LEG_TOLERANCE` (τ) is neither read nor moved in either direction.
`ALLOC_FALL_THRESHOLD_B` stays 600,000. No gate, band, threshold or budget
constant moves.

## The instrument, read before run 1

| file | blob hash |
|---|---|
| `implementation/core/test/re_frame/bench/p0_run.cjs` | blob `ebb08f9f10171d8b67cecee98cb7e85c0a5b9e42` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs` | blob `787ffde48a07ae539d0868feec2718c0727310d5` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs` | blob `ccfa3057b3c38ab1b3610f7dd3e4e5264571a171` |
| `implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/pre-registration.json` | blob `ba755a74d66bda250ea474f788a55606ac383ed6` |

**`p0_run.cjs` is byte-identical to `origin/main`** and to the blob phase 3 ran
its four runs against, so phase 3's session and this window's two are the same
instrument on the same estimand. They will be read again after the last run.

**If the rig is found to have moved mid-window, the window stops and publishes
nothing taken across the change.**

## Reproduction

From `implementation/`, one run at a time:

```
P0_ALLOC_PLAN=full P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=paired P0_ALLOC_ROUNDS=12 \
P0_ALLOC_PASS_ORDER=seeded P0_ALLOC_PASS_SEED=fk6pj-9 \
node core/test/re_frame/bench/p0_run.cjs --only alloc
```

and the adjudication, which needs no browser:

```
node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --controls
node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs --self-test
node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs \
  --declared hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/pre-registration.json \
  hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/run[1-8].json
```
