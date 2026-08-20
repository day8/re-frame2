# The mode rate tracks elapsed session time — and the bisect's one high run does not follow

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-6kxub`, which asks why three
windows at one revision, on one instrument, with the same plan and the same
estimator produced three incompatible rates for the elevated floor mode.

**No allocation window was taken for this page, and no rig file was edited.**
Every figure is re-derived from datasets already committed under
`implementation/hicasso/test/re_frame/bench/hicasso/data/`. The analysis was run
against the tree at commit `a51138e93b`.

**τ was not moved, in either direction.** No gate, band, threshold, budget or
tolerance was widened, narrowed or touched. The 21,000 B/write high-mode
criterion used throughout is `rf2-77gz8`'s, carried unchanged through
[does arming the census move the high level](does-arming-the-census-move-the-high-level.md);
it is a LABEL on an already-measured bimodal population and not a gate — nothing
passes or fails on it and no run is refused by it.

## The answer, first

**The rate tracks how long the session had been running, and the association is
real but weaker than a quartile cut makes it look. It does NOT explain the
bisect's high run, and it does NOT discharge `rf2-9jrhi`.**

- **The three rates reproduce exactly**, and so does the finding that they are
  not an arming effect: 0 of 6, 2 of 20, 37 of 69 = 53.6%, with 18 of 34 armed
  against 19 of 35 unarmed. Both arms moved together, which is what an
  environment term does and an arming term does not.
- **Session duration orders them**: 8.2 min → 0%, 19.7 min → 10%, 88.3 min →
  53.6%. That relation is four points once the bisect is added and it is
  described, not fitted.
- **Within the long session the gradient is real but modest.** The
  boundary-free rank test gives **one-tail `p` = 0.0198**. A quartile cut gives
  0.0054 — and that figure moves eightfold, 0.0210 to 0.0025, across a two-run
  choice of where the cut falls. **The cut is a choice; quote the rank test.**
- **THE BOUND, and it is the half that must not be lost.** The bisect session's
  single high run sits at **+3.6 minutes, second of eight** in a 14.9-minute
  session. The gradient says an early run is where a high is LEAST likely, so
  **elapsed time does not explain this run**. `rf2-9jrhi`'s open question —
  whether the second mode is revision-dependent — is **not** discharged by
  anything here, and the two questions stay separate.

## The census

Statistic: the median of `legMedian` over each run's CERTIFIED floor windows,
per segment. A run is HIGH when either segment reads at or above 21,000 B/write.

| session | runs | high | rate | minutes | inadmissible |
|---|---|---|---|---|---|
| `workcount-n1b9h` | 6 | 0 | 0.0% | 8.2 | — |
| `alloc-9jrhi` | 8 | 1 | 12.5% | 14.9 | — |
| `alloc-77gz8` | 20 | 2 | 10.0% | 19.7 | — |
| `alloc-c4hhk` | 69 | 37 | **53.6%** | 88.3 | `armed-25-a4a1537cb71` |

`armed-25` carries no `alloc` block at all, so it holds no reading. It is named
as inadmissible rather than dropped silently and **is never counted low** —
counting it low would move the headline rate, and a dataset with no reading is
not a reading of zero.

**Not an arming effect.** `alloc-c4hhk` alternated armed and unarmed runs
strictly: **18 of 34 armed (52.9%) against 19 of 35 unarmed (54.3%)**. An arming
term would have separated them. This reproduces `rf2-c4hhk`'s own result and is
restated here only because it is the first thing the rate question has to
exclude.

## Duration orders the sessions

| session | minutes | rate |
|---|---|---|
| `workcount-n1b9h` | 8.2 | 0.0% |
| `alloc-9jrhi` | 14.9 | 12.5% |
| `alloc-77gz8` | 19.7 | 10.0% |
| `alloc-c4hhk` | 88.3 | 53.6% |

**Four sessions, monotone but for one inversion at 14.9 against 19.7 minutes,
where the two rates are 12.5% and 10% on 8 and 20 runs.** No test is offered on
this table and none should be: four points, three of them at durations that are
also three different clock times on two different dates, and the rates carry
sampling error of their own. It is stated as an ORDERING and not as a fit.

**The bisect is on this table rather than beside it**, which matters twice over.
Its rate fits the duration relation perfectly well. Its internal ordering, below,
does not fit the within-session gradient at all. Those are two different claims
about the same eight runs and this page keeps them apart.

## Within the long session, which is the only unconfounded arm

`alloc-c4hhk` is 69 runs on one box, at one revision, in one continuous session.
A comparison inside it holds revision, box, date and instrument fixed, which no
comparison ACROSS the sessions can. It is the only arm here that separates
elapsed time from everything elapsed time is otherwise confounded with.

### The quartile figure, and why this page does not lead with it

An earlier read quoted a one-tailed hypergeometric on the first quarter:
P(at most 5 high among the first 19 of 69, given 37 high overall) = 0.0054. That
reproduces exactly. **But it turns on where the quarter is cut**, and the cut is
a choice rather than a measurement:

| first `k` runs | high | one-tail `p` |
|---|---|---|
| 17 | 5 | 0.0210 |
| 18 | 5 | 0.0109 |
| **19** | 5 | **0.0054** |
| 20 | 5 | 0.0025 |
| 34 (half) | 15 | 0.0934 |

**An eightfold move across a two-run choice, and a first-half/second-half split
gives 0.093.** Any of those cuts is defensible and none is pre-registered. A
figure that mobile should not be the one a record leads with.

### The figure to quote instead

**Mann-Whitney rank-sum on each run's position in the session, high against low.
There is no cut point to choose.**

> U = **763** against a null mean of 592, z = 2.058, **one-tail `p` = 0.0198**.
> Mean position 39.6 for the 37 high runs against 29.7 for the 32 low ones.

One-tailed, and deliberately: the question asked is whether the EARLY runs read
low, which has a direction. The normal approximation is used for the tail at
n = 37 against 32, far past where it bites, and the exact U is printed beside it
so the approximation can be checked rather than trusted.

**So the within-session gradient is real at about `p` = 0.02, not `p` = 0.005.**
It is an association of moderate strength on a single session.

## The three rates stop being incompatible — conditionally

Read against the long session's own EARLY rate rather than its pooled rate, the
two short sessions are unremarkable. Read against the pooled rate, one of them is
extreme. One-tailed binomial, P(at most the observed count):

| reference rate | `workcount-n1b9h`, 0 of 6 | `alloc-77gz8`, 2 of 20 |
|---|---|---|
| early prefix, 5 of 19 = 26.3% | 0.160 | 0.072 |
| pooled, 37 of 69 = 53.6% | 0.0099 | **5.89e-5** |

**That contrast is the whole of the claim.** Against 53.6%, `alloc-77gz8`'s 2 of
20 is a 1-in-17,000 event and the three sessions genuinely do contradict each
other. Against 26.3% — the rate the long session itself ran at while it was as
young as the short ones were when they ended — nothing needs explaining.

**This is conditional and it is not a test.** The reference rate is taken from
the same data the gradient was found in, so the second row is not an independent
confirmation of the first. What it establishes is consistency: one within-session
gradient plus ordinary sampling accounts for all three observations, where three
properties of three revisions is not needed.

## The bound, stated at length because it is the half a summary loses

`alloc-9jrhi` is the corpus that should break the elapsed-time account, and it
partly does.

| elapsed | run | level, `reagent-subs` | mode |
|---|---|---|---|
| +0.0 min | `pilot-rounds6-head-88411ed803` | 19,256 | low |
| **+3.6 min** | **`bisect-1-a-4a1537cb71`** | **22,892** | **HIGH** |
| +5.7 min | `bisect-2-m-a158c40288` | 19,386 | low |
| +7.3 min | `bisect-3-b-48c715f97c` | 19,378 | low |
| +9.4 min | `bisect-4-p-9d20be1d00` | 19,054 | low |
| +11.3 min | `bisect-5-a-4a1537cb71-replicate` | 19,108 | low |
| +13.2 min | `bisect-6-a-4a1537cb71-replicate2` | 19,100 | low |
| +14.9 min | `bisect-7-head-88411ed803` | 19,378 | low |

**The single high run is the SECOND of eight, at +3.6 minutes of a 14.9-minute
session.** Under the within-`c4hhk` gradient — 27.8% in the first quarter against
52.9-68.8% after — an early run is precisely where a high run is least likely.
So one of two things is true, and this page does not choose between them: the
gradient is noisy at n = 1, or something else is also in play.

### What that means for `rf2-9jrhi`, exactly

`rf2-9jrhi` has an open question — **whether the second mode is
revision-dependent**, standing at n = 1. It would have been discharged here had
the bisect's high run landed LATE in its session, because then position-in-session
would have been confounded with revision and that record's flat verdict would
have needed re-reading.

**It landed early. So the question is untouched**, and the RATE (this bead) and
the mode's REVISION-DEPENDENCE (`rf2-9jrhi`) do not collapse into one another.
Nothing on this page should be cited as bearing on the second. See
[the bisect is flat and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md).

## Two corrections to the earlier read

- **The bisect's high run is the SECOND-earliest of eight, not the third.** The
  earlier note's own ordered list has `pilot-rounds6` at +0.0 and `bisect-1-a` at
  +3.6, which makes it second; the prose beside that list said third. The
  direction of the bound is unaffected — second is, if anything, earlier — but
  the figure is corrected.
- **The quarter boundaries were 19 / 18 / 17 / 15 there and 18 / 18 / 17 / 16
  here**, because equal-elapsed-time quarters and equal-count quarters do not
  cut the same session the same way. Both partition all 69 runs and both find 37
  high; the quarter rates read 26.3 / 66.7 / 58.8 / 66.7 under the first and
  27.8 / 66.7 / 52.9 / 68.8 under the second. **That the two disagree at all is
  the reason this page leads with a test that has no boundary.**

## What is not concluded

- **No mechanism is named.** Thermal state, V8 tier accumulation and heap
  fragmentation over a long session all survive this equally. An association with
  elapsed time is not a cause.
- **Across the three sessions, duration is confounded with date and clock time.**
  `workcount-n1b9h` opened at 11:23, `alloc-77gz8` at 13:52 and `alloc-c4hhk` at
  23:54, and the last ran past midnight. Only the within-`alloc-c4hhk` arm is
  free of that, and it is one session.
- **One session is one session.** The whole gradient rests on `alloc-c4hhk`; the
  other three corpora are too short to carry an internal trend and are not asked
  to.
- **The bead's SECOND move is not made here.** Recording the Chromium build
  string, the box's load at window open and the elapsed time since the previous
  run is a change to `implementation/core/test/re_frame/bench/p0_run.cjs` — hot,
  one-toucher, and shared with `rf2-fk6pj`, `rf2-csca8` and `rf2-onozm`. It needs
  no window and it is the mayor's to bundle.
- **The LEVELS are not in question and were not re-opened.** `rf2-c4hhk`
  established that arming moves neither, and the level values reproduce to the
  byte. This page is about the RATE only.

## Reproduction

Every measured figure on this page — every rate, duration, `p`, rank statistic
and byte level — is re-derived from the committed records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_mode_rate_session.cjs
```

It launches nothing, reads no rig file and writes nothing. Its fixtures run under
`--self-test` and pin the bound hardest of all — that the bisect's high run is
second of eight and in the early half — because that is the half a write-up is
most likely to lose.

The fixtures also **discriminate rather than restate**: they require the prefix
sweep to move at least fourfold across the cut, and require the boundary-free
rank test to come out WEAKER than the best cut. A change that made the quartile
figure look robust would red them.
