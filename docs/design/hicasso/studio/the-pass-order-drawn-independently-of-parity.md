# The pass order, drawn independently of parity — rf2-fk6pj's measurement window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-fk6pj`, phase 2 of two. Phase 1
landed the rig (`P0_ALLOC_PASS_ORDER=seeded`, PR #8596) and took no window;
this page is the window taken against it.

> **THE SEPARATION READING ON THIS PAGE IS SUPERSEDED, AND THE PROGRAMME RAN ON
> PAST IT — 2026-08-21 (`rf2-j0szk`).** This record reads run 2 as separating the
> pass column from the parity column where run 1 did not, and rests its outcome
> on that one run. **Neither run separated them.** Phase 3 built a control that
> decides it in both directions: over `R` balanced rounds the parity·pass inner
> product is `q · p = 4a − R`, so **no balanced six-round schedule can identify
> at all**, and the shipped decomposition **fails all three pure-pass /
> pure-parity fixtures on this window's schedules** — reading a PASS term of
> `−1` on a corpus built with no pass effect in it. Two marginal median contrasts
> that merely DIFFER are not two separated terms.
>
> **Every figure below is left exactly as it was published and nothing measured
> here is withdrawn**; the corrections are marked in place at three sites. **This
> page's terminal conclusion survives** — the pass term is not established free
> of round parity *here* — though it survives for a stronger reason than the one
> given. The seat line above reads "phase 2 of two" because that is what was
> committed before run 1; the programme ran to **four** phases, phase 4
> established the term, and `rf2-fk6pj` is now CLOSED. See
> [the design control comes before the window](the-design-control-comes-before-the-window.md)
> and [the band on the aggregate, and the second session](the-band-on-the-aggregate-and-the-second-session.md).

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

---

*Everything above this line was committed at 2026-08-21 05:38:50 AUSEST, as this
branch's first commit — the one whose subject begins "pre-register rf2-fk6pj's
seeded window". Run 1 opened at 05:40:07 and run 2 at 05:42:38, and both
timestamps are in their own records. Everything below was written after both.
No SHA is cited here on purpose: an authored head that has not landed is
unresolvable from a fresh clone, which is exactly what `check_provenance_pins.py`
refuses, and the commit order is the evidence rather than the identifier.*

## The answer, first

> **CORRECTED 2026-08-21 (`rf2-j0szk`, on phase 3's design control and the
> merged-PR audit of PR #8601).** The bullets below are left as written, and two
> of them are wrong in the same way — the reading rather than the arithmetic:
> ~~"only ONE of the two runs separates anything"~~ and ~~"the independent block
> count for the separation claim is therefore 1, not 2"~~. **Neither run
> separated the two columns.** Run 1's two groupings returning the identical pair
> of medians is a real observation and is visible in section B; run 2's groupings
> differing is also real, and is **not** the same thing as identifying, because
> at six rounds the two columns are correlated — `q · p` reads `+6`, `+2`, `−2`
> or `−6` on every balanced six-round schedule and never 0. So the separation
> claim rests on **no** independent block rather than on one. The window still
> reads between outcomes 1 and 4 without choosing, and **`rf2-fk6pj` did not stay
> open**: phase 3 built a design that identifies, and phase 4 established the
> term. Everything else in this section stands as taken — the 7-of-12
> non-reproduction, both decomposed signs, run 2's level shift, and the reader
> defect the estimator's own positive control caught.

**The window separates the two columns exactly as it was drawn — and it narrows
the question without settling it.** Both runs' controls certify, the pooled
design came out orthogonal as designed, and the pass term survives; but only ONE
of the two runs separates anything, for a reason the design did not anticipate
and the data found.

- **`rf2-0gjqi`'s headline 10-of-12 DID NOT REPRODUCE.** The pass that ran second
  read lower in **7 of 12 blocks**, which is what chance gives, at a median
  second-minus-first of **−0.56%** against the published −0.59%. The raw sign
  count is not a stable property of this instrument.
- **The DECOMPOSED pass term did reproduce, in sign, on both runs**: **+0.56%**
  and **+0.64%**, against `rf2-0gjqi`'s +0.68% and +0.21%. Four runs across two
  sessions and two pass-order modes, four positive, median **+0.60%**. Over the
  same four the order-free WRITE term reads −0.33%, +0.24%, +0.13% and **−0.95%**
  — two of each sign. That is the same shape the earlier window reported, and it
  now holds across a rig change.
- **The count and the term disagree because the count conflates them.** A block's
  sign is the order term plus that run's level; run 2's level sat at −0.95%,
  large enough to put three `page`-first blocks slightly negative and flip their
  raw sign. The decomposition is what separates the two, and it is the statistic
  `rf2-0gjqi`'s section C was read on.
- **But only run 2 separates the pass from the parity.** Run 1's two groupings
  returned the **identical pair of medians** — +0.69% and −0.43% — although the
  partitions genuinely differ. **The independent block count for the separation
  claim is therefore 1, not 2**, which is weaker than the 2-not-12 the bead
  already insisted on. See
  [why one run separated nothing](#why-one-run-separated-nothing-and-it-is-a-defect-in-this-windows-design).
- **In the run that does separate, the pass term is the larger and the parity
  term is opposite to it**: PASS **+0.64%**, PARITY **−0.22%**. Pooled over the
  orthogonal twelve blocks: PASS **+0.30%**, PARITY **+0.17%**.
- **So under the pre-registration this window reads between outcome 1 and outcome
  4 and cannot choose.** The one separating run reads as outcome 1 — the pass
  carries it, the parity is small and of the other sign. The pooled twelve read
  as outcome 4 — two terms, the parity about half the pass. With n = 1 separating
  run there is nothing here that decides between those, and **this record does
  not claim the pass term is established free of parity.**
- **A session-level shift sits under run 2 and nothing in the record explains
  it**: its floor read **23,146 / 23,584 B** against **19,288 B** and
  **19,732 – 19,738 B** in run 1 and in both `rf2-0gjqi` runs, about 20% higher,
  with its mid-rung `all` levels 4–10% higher with it. See
  [the floor moved in run 2](#the-floor-moved-in-run-2-and-the-box-rider-does-not-explain-it).
- **A reader defect was caught by the estimator's own positive control**, not by
  inspection, and it lived in a section nothing else pinned. See
  [what the control caught](#what-the-control-caught-in-the-reader-itself).

**`rf2-fk6pj` therefore stays OPEN**, and what the next window owes is stated
below in terms a dispatch can act on. **No rig file was edited by this window**,
τ was not read or moved in either direction, and no gate, band, threshold or
budget constant was touched.

## Runtime, beside every figure

Chromium via Playwright at build **`chromium/147.0.7727.15`** (recorded by the
run, `rf2-24o2z`), shadow-cljs `release` on build id `:hicasso-bench`,
`:optimizations :advanced`, `goog.DEBUG false`, `--expose-gc`,
`:init-fn re-frame.bench.p0-app/-main`. Run 1's build reports **195 files, 140
compiled, 0 warnings**. Both runs' `shadow-cljs - config:` line names this
worktree's own `implementation/shadow-cljs.edn`, so neither compiled a sibling
checkout's sources. No `java` process existed on the box when the window opened,
so no shadow-cljs server could have served a stale build.

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stayed the declared 0.25
placeholder, `ALLOC_FALL_THRESHOLD_B` stayed 600,000, W stayed 6 measured writes
after one prime, three warm-up windows, six rounds, R = 20 on the ladder, and the
records confirm `segOrder: parity` and `controlSlot: first` — the two other
diagnostic modes stayed off.

**Both runs captured exit 1**, this row's code for a run carrying refused
windows, on the falls gate — **47 and 51 collections inside measured windows**
(49 and 54 refused windows). `rf2-0gjqi`'s two runs captured exit 1 on the same
gate at 55 and 54. **No slope is quotable from either run and none is quoted
here**; every figure on this page is a per-window figure off certified windows.

The box rider each run carries (`rf2-24o2z`, riding free on this window):

| run | opened (AUSEST) | busy fraction over the run | free memory at open | session |
|---|---|---|---|---|
| 1 | 05:40:07 | 0.212 | 16.04 GB of 68.11 | first run of the session |
| 2 | 05:42:38 | 0.172 | 16.19 GB of 68.11 | 31.3 s after run 1 ended, same session |

**This is an ALLOCATION estimand, so it took no quiet-box slot** — a census of
monotone byte counters reads the same on a loaded machine (`rf2-ojehu`). The box
was not exempt for CAPACITY, and it was not shared: no other bench-class run was
on it, and the two runs were taken strictly one at a time.

## Both runs' positive control passed

| run | seed | pass order | parity-tied | schedule drove what it drew | B/double | differential | verdict | unverified |
|---|---|---|---|---|---|---|---|---|
| 1 | `fk6pj-1` | `seeded` | no | yes | 8.08 | **8.00** | OK | 0 |
| 2 | `fk6pj-2` | `seeded` | no | yes | 8.08 | **8.00** | OK | 0 |

The differential — D = 1,000 less D = 400 — reads **8.00 B/double against a
predicted 8** in both runs, replicating every previous window. **The schedules
drove what they drew**, checked round by round against `passSchedule.flips`
rather than assumed: run 1 drove `all, all, page, all, page, page` and run 2
drove `page, page, all, page, all, all`, which are the two schedules written down
above before either run.

**And the estimator reproduced the window it is read against.** `--self-test`
drives the shipped functions over the committed `alloc-0gjqi` corpus and requires
all twelve of its published blocks with their `n`, both runs' and the pooled
decomposition, the 10-of-12 count, the parity tie, the null arm's 38 cells and
the floor-free estimator's wrong 3 of 6. It passes.

## A. The round blocks

Twelve blocks — two runs of six rounds. `second−first` is the same per-round
statistic re-signed by which pass ran second; it is arithmetic on the column
beside it, not a further estimator.

| run | round | pass that ran FIRST | parity | n mid cells | median `(page−all)/all` | second−first |
|---|---|---|---|---|---|---|
| 1 | 0 | `all` | even | 4 | +0.24% | +0.24% |
| 1 | 1 | `all` | odd | 8 | −0.43% | −0.43% |
| 1 | 2 | `page` | even | 6 | +0.69% | −0.69% |
| 1 | 3 | `all` | odd | 7 | −0.73% | −0.73% |
| 1 | 4 | `page` | even | 6 | +1.18% | −1.18% |
| 1 | 5 | `page` | odd | 8 | −0.28% | +0.28% |
| 2 | 0 | `page` | even | 2 | −0.31% | +0.31% |
| 2 | 1 | `page` | odd | 8 | −0.01% | +0.01% |
| 2 | 2 | `all` | even | 8 | −1.59% | −1.59% |
| 2 | 3 | `page` | odd | 6 | −0.33% | +0.33% |
| 2 | 4 | `all` | even | 8 | −0.76% | −0.76% |
| 2 | 5 | `all` | odd | 7 | −1.88% | −1.88% |

**The pass that ran second read lower in 7 of the 12 blocks**, median −0.56%.
The published window read 10 of 12 at −0.59%.

**The design came out as drawn.** The parity·pass inner product over the twelve
pooled blocks reads **0**: the parity column and the pass column are orthogonal,
which is the first time any allocation corpus has been able to say that. On a
`parity` corpus the same reader reads **+12** — one column, up to sign.

## B. The decomposition, on both groupings

| block set | grouping | group A | group B | half-difference: THE TERM | half-sum |
|---|---|---|---|---|---|
| run 1 | PASS (`page`-first vs `all`-first) | +0.69% (n=3) | −0.43% (n=3) | **PASS +0.56%** | write +0.13% |
| run 1 | PARITY (even vs odd round) | +0.69% (n=3) | −0.43% (n=3) | PARITY +0.56% | write +0.13% |
| run 2 | PASS (`page`-first vs `all`-first) | −0.31% (n=3) | −1.59% (n=3) | **PASS +0.64%** | write −0.95% |
| run 2 | PARITY (even vs odd round) | −0.76% (n=3) | −0.33% (n=3) | **PARITY −0.22%** | write −0.54% |
| pooled | PASS (`page`-first vs `all`-first) | −0.14% (n=6) | −0.74% (n=6) | **PASS +0.30%** | write −0.44% |
| pooled | PARITY (even vs odd round) | −0.03% (n=6) | −0.38% (n=6) | **PARITY +0.17%** | write −0.21% |

**Run 1's two rows are identical, and that is not a copy-and-paste.** The two
partitions differ — `page`-first is rounds {2, 4, 5} and even is rounds {0, 2, 4}
— and both nevertheless return +0.69% and −0.43%.

### Why one run separated nothing, and it is a defect in this window's design

The orthogonality argument in [the two seeds](#the-two-seeds-and-why-these-two)
is correct arithmetic **on the indicator columns**. The block statistic is a
**median**, which is not a linear functional, and column orthogonality does not
carry to orthogonality of median contrasts.

At six rounds each group is three blocks, and **the median of three numbers is
one of them**. Two partitions that share two of their three members will return
the same member whenever that shared member is the middle one of both — which is
common, not exotic. Run 1's schedule was chosen at symmetric difference **2**
from parity, precisely because that is what bought the pooled orthogonality, and
symmetric difference 2 means the groups share two of three. Round 2 is the median
of both `page`-first and even; round 1 is the median of both `all`-first and odd.
So run 1's PARITY TERM of +0.56% is not a parity reading at all — **it is the
pass reading wearing another name**, which is exactly the failure mode this bead
exists to police, reproduced one level up in the estimator instead of in the rig.

Run 2's schedule sits at symmetric difference 4, the groups share one of three,
and there the two readings come apart: +0.64% against −0.22%.

> **CORRECTED 2026-08-21 (`rf2-j0szk`, on phase 3's design control).** The median
> argument above is right, and phase 3 kept it: at three blocks per group the
> median is one of them, and two partitions sharing two of three members will
> often return the same one. What does not follow is the sentence it is used for.
> Run 2's schedule sitting at symmetric difference 4 makes its two readings
> **differ**; it does not make them identify. Phase 3's `alloc_pass_design.cjs`
> drives the **shipped** `decompose` over synthetic block sets whose true terms
> are known by construction, and on this window's run-2 schedule it reads
> **PASS `−1` on a pure-parity corpus** and **PARITY `−1` on a pure-pass one**.
> It fails all three fixtures on this window's schedules and passes all three on
> each of phase 3's four twelve-round ones. The heading above is left standing
> because it names this section and is linked from
> [the answer](#the-answer-first), but it should be read as *why neither run
> separated anything*.
>
> **The three-item list below was superseded rather than adopted, and item 1 was
> refuted.** Both seeds at symmetric difference 4 is the `q · p = −2` case;
> `q · p = 4a − R` is never zero at `R = 6`, so it would have bought four runs of
> the same confound instead of two. Phase 3 took item 2 instead and made the
> round count load-bearing — at `R = 12` the criterion is `q · p = 0` **within**
> each run, which is the 2 × 2 balance — added `q · l = 0` against within-run
> drift, and took item 3 as four twelve-round runs in two complementary pairs.

**What this window owes its successor, and it is a DESIGN change rather than a
rig change** — `p0_run.cjs` needs nothing:

1. **Both seeds at symmetric difference 4**, so each run separates on its own. The
   pooled inner product is then −4 rather than 0, which is a worse pooled design
   and a better per-run one, and per-run is what a median statistic can actually
   use. Nine of the twenty balanced six-round schedules qualify.
2. **More rounds**, so the block statistic is a median over more than three. The
   coincidence above is a property of medians at n = 3 and it thins out quickly.
   Rounds are repeats: they cost wall clock and change no window total, no
   collection budget and no certification basis.
3. **A third and fourth run**, because the separation claim currently rests on one.

## C. The mid-rung cells

Eight cells per run — R3 and R7 on each of four arm families — with `n` the
number of rounds in which all four windows certified.

| run | segment \| arm | rung | n | `all` | `page` | ratio-of-medians | median-of-ratios |
|---|---|---|---|---|---|---|---|
| 1 | `reagent-subs` \| hicasso | R3 | 4 | 6,556 | 6,595 | +0.59% | +0.63% |
| 1 | `reagent-subs` \| hicasso | R7 | 5 | 13,795 | 13,660 | −0.98% | −0.98% |
| 1 | `reagent-subs` \| reagent | R3 | 5 | 7,561 | 7,563 | +0.03% | +0.03% |
| 1 | `reagent-subs` \| reagent | R7 | 4 | 15,056 | 15,083 | +0.18% | +0.40% |
| 1 | `uix-subs` \| hicasso | R3 | 5 | 6,435 | 6,531 | +1.48% | +0.70% |
| 1 | `uix-subs` \| hicasso | R7 | 4 | 13,757 | 13,658 | −0.72% | −0.32% |
| 1 | `uix-subs` \| uix | R3 | 6 | 5,140 | 5,144 | +0.07% | +0.35% |
| 1 | `uix-subs` \| uix | R7 | 6 | 11,160 | 11,195 | +0.32% | +0.08% |
| 2 | `reagent-subs` \| hicasso | R3 | 5 | 6,878 | 6,764 | −1.66% | −1.66% |
| 2 | `reagent-subs` \| hicasso | R7 | 5 | 14,307 | 14,337 | +0.21% | +0.06% |
| 2 | `reagent-subs` \| reagent | R3 | 4 | 8,312 | 8,281 | −0.37% | −0.85% |
| 2 | `reagent-subs` \| reagent | R7 | 5 | 16,585 | 16,433 | −0.92% | −0.92% |
| 2 | `uix-subs` \| hicasso | R3 | 6 | 6,722 | 6,640 | −1.21% | −1.04% |
| 2 | `uix-subs` \| hicasso | R7 | 6 | 14,125 | 14,000 | −0.89% | −0.92% |
| 2 | `uix-subs` \| uix | R3 | 4 | 5,245 | 5,156 | −1.71% | −1.74% |
| 2 | `uix-subs` \| uix | R7 | 4 | 11,349 | 11,432 | +0.73% | +0.90% |

**Eight of sixteen are negative on ratio-of-medians** — two of eight in run 1 and
six of eight in run 2 — against `rf2-0gjqi`'s 10 of 16, which was itself already
consistent with chance. Absolute values run **0.03% – 1.71%**, absolute median
**0.73%**. The two runs' cell signs disagree with each other far more than they
agree, which is the level shift below rather than a cell property.

## The floor moved in run 2, and the box rider does not explain it

Read on `rf2-0gjqi`'s own convention — the drop is the median `all` less the
median `page`, with the per-round range beside it — so the two windows' tables
are the same statistic.

| run | segment | n paired rounds | `all` | `page` | drop | per-round drop range |
|---|---|---|---|---|---|---|
| 1 | `reagent-subs` | 5 | 19,288 | 17,636 | **1,652 B (8.6%)** | 1,652 – 1,658 |
| 1 | `uix-subs` | 6 | 19,738 | 18,080 | **1,658 B (8.4%)** | 1,646 – 1,664 |
| 2 | `reagent-subs` | 5 | **23,146** | **21,440** | **1,706 B (7.4%)** | 1,676 – 1,950 |
| 2 | `uix-subs` | 6 | **23,584** | **21,887** | **1,697 B (7.2%)** | 1,644 – 1,712 |

**Run 1's floor is byte-identical to `rf2-0gjqi` run 2's, on both segments** —
19,288 / 17,636 / 1,652 B / 8.6% and 19,738 / 18,080 / 1,658 B / 8.4%, with
per-round ranges of 1,652 – 1,658 and 1,646 – 1,664 against that window's
1,652 – 1,658 and 1,644 – 1,664. The published floor drop was re-derived here
from the committed records as a check on this reading and reproduces all four of
its rows exactly. That the same four numbers come back on a different day under a
different pass-order rule is the strongest single check available that this
window is the same instrument on the same estimand.

**Run 2 is not.** Its floor sits about **20% higher on both segments**, its
mid-rung `all` levels 4–10% higher with it, and its drop is **39 – 54 B larger**
in absolute terms while reading more than a point lower as a fraction — which is
what a roughly constant drop on a raised base looks like. Nothing in the record picks it
out: same Chromium build string, same session 31 s after run 1, and its busy
fraction was **lower** than run 1's (0.172 against 0.212). **No mechanism is
proposed and none is excluded.** This is the level that run 2's write half-sum of
−0.95% is reporting, and it is the reason no write-term interval is offered here
any more than in the window before.

**It does not invalidate run 2's pass contrast**, which is a within-round paired
difference measured on the same page in the same process, and which is the run
that separated the two columns. It does mean run 2's *levels* may not be quoted
beside run 1's or beside `rf2-0gjqi`'s.

## The null arm, which is what licenses reading the rest

| window | n | median `Δ` | absolute median | 90th percentile | max | over the 45 B/boundary bar |
|---|---|---|---|---|---|---|
| this window, pooled | 40 | 0 B/boundary | **3** | **56.5** | 62.5 | **4 of 40** |
| `rf2-0gjqi`, pooled | 38 | 0 B/boundary | 1.5 | 4.5 | 96.5 | 2 of 38 |

**The centre is intact and the tail is thicker.** The absolute median of 3
B/boundary sits two orders below this window's mid-rung per-cell absolute median
of **107.75 B/boundary** (n = 78, running 0 – 759.5), so the mid-rung numbers are
being read above the instrument's own noise. **The 90th percentile jumping from
4.5 to 56.5 is an artefact of the count**, not of a broadly worse distribution:
four outliers in forty put one exactly at the 90th-percentile index where two in
thirty-eight do not. The four are −62.5 and −59.5 (run 1, rounds 3 and 5) and
+56.5 and +62.5 (run 2, both in round 4); `rf2-0gjqi`'s two were −96.5 and −56.5,
so the largest single null-arm excursion in the corpus is still its, not this
window's.

**Four of forty above the published 45 B/boundary refusal bar is worth carrying
forward** — that bar is ten times `rf2-0gjqi`'s p90, and a window whose own null
arm crosses it four times in forty is a window whose per-cell readings under
about 60 B/boundary should not be leaned on. Twenty-three of this window's 78
mid-rung per-cell `|Δ|` readings sit below 56.5.

## What the control caught in the reader itself

`alloc_pass_position.cjs`'s first revision dropped every cell whose `d_all` was
exactly zero, as a division hazard. That is right for the block statistic and
**wrong for the null arm, where `d_all = 0` is the value the arm is supposed to
take** — so the guard discarded precisely the observations in which the
instrument had behaved perfectly. On the committed `alloc-0gjqi` corpus it left
**24 of 38** cells, moved the absolute median from 1.5 to 2.25 and the 90th
percentile from 4.5 to 5.

**Nothing caught it except the published corpus.** Every mid-rung figure the
reader was pinned on was unaffected, and remains unaffected — the twelve blocks
with their `n` and both decompositions are byte-identical either side of the fix,
which is what proves the fix reached only the null arm and not the pre-registered
primary statistic. The self-test now pins all five of section E's published
figures for that reason.

**This is a reader fix taken after the runs, and it is not a mid-window rig
change.** `p0_run.cjs` was not touched, both records were already written and are
immutable, and the primary statistic is pinned unchanged by a control that
predates the runs.

## What was NOT concluded

- **The pass term is NOT established free of round parity.** One of two runs
  separated the columns; the other returned the same medians for both groupings.
  A separation claim on n = 1 is not a separation claim.
- **No mechanism is proposed for the pass term** and none is excluded, exactly as
  `rf2-fk6pj` already said.
- **The magnitude is not comparable to the old between-process effect.** This is
  a within-round pass-position term and the published 1.68% mid-rung absolute
  median is a different estimand. Right sign, wrong size, different question.
- **No slope, and no R = 20 reading.** Both runs refused on the falls gate, and
  R = 20 was not this window's question — `rf2-onozm`'s five-write sizing was
  deliberately not taken, for the reasons in
  [the design](#the-design-fixed-before-the-first-run).
- **Run 2's level shift is unexplained**, and its absolute levels may not be
  quoted beside any other run's.
- **Nothing here is a gate.** No run passes or fails on any figure above, no
  threshold moved, and τ was neither read nor calibrated against in either
  direction.

> **CORRECTED 2026-08-21 (`rf2-j0szk`, on phase 3's design control).** The first
> bullet's conclusion stands; its reason does not. ~~"One of two runs separated
> the columns; the other returned the same medians for both groupings. A
> separation claim on n = 1 is not a separation claim."~~ — **neither run
> separated the columns**, so this is not a separation claim on `n = 1` but on
> `n = 0`, which is the weaker position of the two and the one the bullet's
> heading already takes. **The pass term is still NOT established free of round
> parity by this window**, unchanged. It was established later and elsewhere:
> phase 3 built a twelve-round design proved to identify *before* it was run, and
> [phase 4](the-band-on-the-aggregate-and-the-second-session.md) read eight runs
> of that design across two sessions against a band declared before run 1 — PASS
> **+0.31%**, eight of eight runs positive — which closed `rf2-fk6pj`. The other
> four bullets on this list are untouched, and so is every figure on this page.
