# The pass order, drawn independently of parity — rf2-fk6pj's measurement window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-fk6pj`, phase 2 of two. Phase 1
landed the rig (`P0_ALLOC_PASS_ORDER=seeded`, PR #8596) and took no window;
this page is the window taken against it.

**This section down to and including [the pre-registered outcomes](#the-pre-registered-outcomes)
was committed before the first run was taken.** The commit that carries it also
carries `alloc_pass_position.cjs` and its self-test, so the estimator, the two
seeds, the controls and the four outcomes are all in the tree with a timestamp
that precedes every figure below them.

## The question

`rf2-0gjqi`'s paired window measured that, under `P0_ALLOC_WRITE=paired`, **the
pass that ran SECOND reads lower** — 10 of 12 round blocks, 6 of 6 in run 1 and
4 of 6 in run 2, median second-minus-first −0.59%, whichever write occupied it.
Its record is
[the sign follows the pass, not the write](the-sign-follows-the-pass-not-the-write.md).

That window could not say whether the carrier is the **pass position** or some
other **even/odd property of the round**, because the rig tied them: the leg
order was `round % 2`, so every `page`-first round was an even round and the two
are one column in the design matrix. `rf2-fk6pj`'s own words: *separating them
needs a leg order that is not a function of round parity, which is a rig change
and therefore its own window.*

This is that window.

## The design, fixed before the first run

**Two runs, declared in advance.** Same configuration as `rf2-0gjqi`'s window in
every parameter except the pass-order mode and its seed, so the two windows are
read against each other on one changed thing.

| # | plan | `P0_ALLOC_CELLS` | B | write | rounds | W | warm-ups | pass order | seed |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `full` | 1 | 4 | `paired` | 6 | 6 | 3 | `seeded` | `fk6pj-1` |
| 2 | `full` | 1 | 4 | `paired` | 6 | 6 | 3 | `seeded` | `fk6pj-2` |

Reproduction, from `implementation/`:

```
P0_ALLOC_PLAN=full P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=paired \
P0_ALLOC_PASS_ORDER=seeded P0_ALLOC_PASS_SEED=fk6pj-1 \
node core/test/re_frame/bench/p0_run.cjs --only alloc
```

**`P0_ALLOC_WRITES` was NOT moved to 5, and that is a decision rather than an
oversight.** `rf2-onozm` sized a five-write window so the R = 20 rung might
certify, and its own conclusion is that **five writes is SIZED, NOT SAFE** —
only 5 of 12 cells land inside the certifying band, seven sit above by up to
18,556 B, and what survives is the one-sided window ceiling of **884,280 B**
(above it 0 of 72 certify; at or below it 420 of 456, 92.1%), which is necessary
and not sufficient, with the worst cell clearing it by 0.6%. That is a sizing
answer for a **different estimand**: this window is read at the **mid rungs**
(R3, R7), which certified in both `rf2-0gjqi` runs at W = 6, and changing W
would move the window totals, the collection counts and the certification
pattern all at once — three changes on top of the one this window exists to
make. R = 20 is not this window's question and no figure below is quoted from
it.

### The two seeds, and why these two

The seed is a window parameter, and choosing one is experimental design rather
than tuning: **both seeds were fixed, and both schedules read out and written
down here, before any run.** `allocPassFlips` is pure, so the schedule a seed
draws is computable without a build, a server or a Chromium.

| run | seed | drawn schedule (`all`-first rounds) | leg that runs FIRST, rounds 0–5 |
|---|---|---|---|
| 1 | `fk6pj-1` | 1, 1, 0, 1, 0, 0 | `all`, `all`, `page`, `all`, `page`, `page` |
| 2 | `fk6pj-2` | 0, 0, 1, 0, 1, 1 | `page`, `page`, `all`, `page`, `all`, `all` |

Both are balanced (three flipped rounds of six, exactly as parity gives) and
neither is parity-tied — the rig refuses a tied draw outright, so this is
enforced rather than hoped for.

**The two schedules are exact complements, and that is the property the pair was
chosen for.** Write `p(r) = +1` on an even round and `−1` on an odd one, and
`q(r) = +1` where `page` ran first. Their inner product over a run's six blocks
is `+6` on a `parity` corpus — the perfect tie this bead was filed about. It is
`+2` on run 1's schedule and `−2` on run 2's, so **pooled over the twelve blocks
it is exactly 0: the parity column and the pass column are orthogonal.** Because
the schedules are complements, the stronger statement also holds — at every
round index one run runs `page` first and the other runs `all` first — so the
pooled design balances the pass order against the **round index**, not merely
against its parity. Any per-round-index term, monotone drift within a run
included, cancels out of the pooled pass contrast.

Of the twenty balanced six-round schedules, two are the parity schedules (which
the rig rejects and redraws), nine give an inner product of `+2` and nine give
`−2`; no six-round balanced schedule does better than `±2` alone, so one of each
is the best available pair.

## The estimator, written down before the first run

Verbatim from `rf2-0gjqi`'s record, because a window read on a different
estimator would answer a different question. For every round *r*, segment *s*
and arm *a* the paired record carries four windows — the arm and that segment's
floor, each under each write, all four measured in round *r* on the same page in
the same process:

```
d_all (r,s,a) = (arm@all .legMedian − floor@all .legMedian) / B
d_page(r,s,a) = (arm@page.legMedian − floor@page.legMedian) / B
Δ     (r,s,a) = d_page − d_all
```

**A round contributes a cell only if ALL FOUR of its windows certified.** B = 4.

**THE FLOOR MUST BE SUBTRACTED.** The 10-of-12 reproduces to the block on `d` as
defined above; reading the same blocks off the raw `perBoundaryPerWrite` field
instead gives **3 of 6** in run 1 and the wrong magnitude. The floor is the
dominant shared term and the arms are read above it. The reader's self-test pins
both — the correct count and the floor-free estimator's disagreement with it —
so a change that dropped the subtraction reds rather than drifting.

**The block is the ROUND, not the rung**: the block statistic is the median of
`Δ / d_all` over that round's certified mid-rung (R3, R7) cells.

**Which pass ran first is read off `perRound[r].writeLegs[0]`, never recomputed.**
Under `seeded` nothing recovers it from the round index, which is the whole point
of the mode; a reader that recomputed `round % 2` would reproduce every block of
a `parity` corpus and mis-sign every block of a seeded one.

### What is new, and it is one line of arithmetic

The additive decomposition `rf2-0gjqi` published is applied **twice** — once on
the pass grouping and once on round parity:

```
term(grouping)      = (median m over group A − median m over group B) / 2
blind-to(grouping)  = (median m over group A + median m over group B) / 2
```

On a `parity` corpus the two groupings are the same partition and the second
call returns the first's answer, which is exactly the tie. On a `seeded` corpus
they are different partitions and the two half-differences are separately
readable.

## The controls, and they arbitrate

1. **The estimator's own positive control.** `alloc_pass_position.cjs --self-test`
   drives the shipped functions over the committed `alloc-0gjqi` corpus and
   requires them to reproduce **every figure that window published**: all twelve
   blocks with their `n` and median to the published digits, both runs' and the
   pooled decomposition, the 10-of-12 count, the parity tie reading `+n`, and
   the floor-free estimator's wrong 3 of 6. A reader that cannot reproduce the
   window it is being read against does not get to read the new one.
2. **Each run's positive control**, per run and never pooled: `controlVerdict.ok`
   true, and the D = 1,000 less D = 400 differential at 8 B/double against a
   predicted 8. A run whose control fails contributes no data.
3. **Zero unverified read-backs** (`verification.unverified`).
4. **The record's schedule and its drive agree.** `passSchedule.flips[r]` is what
   was drawn and `perRound[r].writeLegs` is what ran; the reader checks them
   against each other and a record where they disagree is a record whose own
   schedule is unknown.
5. **The mode ran.** `passOrder` reads `seeded`, `passSeed` names the declared
   seed, and `passSchedule.parityTied` is false.
6. **The separation is measured, not assumed.** The reader reports the
   parity·pass inner product off the blocks it actually read. Anything but 0
   pooled means the design did not come out as drawn, and the decomposition is
   reported as partial rather than as a separation.
7. **The null arm (R = 0) reads flat**, as it did in the window this is read
   against — the instrument's non-cancellation floor is what licenses reading
   mid-rung numbers at all.

## The pre-registered outcomes

Four, and which is which is fixed here rather than chosen after the run.

- **The PASS term reproduces and the PARITY term reads near zero** → the carrier
  is the within-round pass position. `rf2-fk6pj`'s observation stands, now
  separated from every other even/odd property of a round.
- **The PARITY term carries the magnitude and the PASS term reads near zero** →
  the carrier is some other even/odd property of the round, and the published
  reading of `rf2-0gjqi`'s section C was the parity column wearing the pass
  column's name.
- **BOTH read near zero** → the term did not reproduce in this window at all,
  which says the 10-of-12 was not stable across sessions and is itself an answer
  about the instrument.
- **BOTH read large** → two real terms, which the pooled design can carry
  because the two columns are orthogonal in it, and the window says so rather
  than attributing the sum to either.

**A magnitude comparison against `rf2-0gjqi`'s +0.68% / +0.21% is a comparison
of the same estimator on two sessions, and nothing more.** Neither is comparable
to the published mid-rung absolute median of 1.68%, which is a different
estimand between two processes.
