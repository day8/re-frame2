# The band, re-calibrated on the clock it judges

**The band is widest on an idle box.** That is the finding, it is the opposite
of what everyone including this page's author expected, and it settles a
question that has been open since a ceiling calibrated never to fire fired
three times in two days.

A fresh nineteen-run load ladder — `rf2-cvvb7`'s design, the same 24-core box,
silent throughout — puts the band at **24.6% at zero competing cores** and
**8.3–13.2%** everywhere from 4 cores to 20. `corr(band, floor) = −0.49`. The
per-sample dispersion of the floor inside a block falls monotonically with
load, **30.0% → 15.2%**, while the floor absolute *rises* 3.78 → 6.93 ms. A
busy box is slower and steadier; an idle one is faster and jumpier.

**So the ceiling's three firings are not a regime departure.** Both of this
ladder's breaches are at **zero load**. `rf2-h8o80`'s hypothesis — that the
band does not extrapolate above the calibrated load range — is refuted in the
direction it was posed, and its evidence turns out to be a clock mismatch worth
1.34 ms ([§4](#4-the-second-explanation-the-regime-and-why-its-evidence-dissolves)).

**And they are only partly the clock.** The frame-only band *is* systematically
wider — 14 of 19 runs, mean 15.3% against 14.0% — for a reason that is
arithmetic rather than physical, and the gate has been moved onto the published
clock accordingly. But both breaches breach on *both* clocks, so that is not
what fired the ceiling either.

**What fired it is that 25% was never above the tail; it was above nineteen
draws of the tail.** Bootstrapping the band's own sampling distribution puts
`P(band > 25%)` at **9.1%** per run. Three firings in two days is what a
one-in-eleven rate produces. The ceiling is now **35%**, set above the
largest of nineteen observations by a margin the bootstrap prices rather than
by eye.

> **RETRACTED (audit of PR #7368): this page derived that ceiling from the
> wrong resampling model, and 35% is not the `q99` of anything.** The bootstrap
> it published draws each synthetic run's eighteen blocks from all 342 blocks
> of all nineteen runs, which shuffles away the regime the eighteen blocks of a
> real run share. **Run-preserving resampling** — draw one of the nineteen runs,
> then resample *its* eighteen blocks — is what a future run looks like, and it
> reads a `q99` of **41.4%** against the pooled model's 29.4%. So the derivation
> is withdrawn, not restated at a new number: **the ceiling in force is 35%, its
> measured per-run false-fire rate is 2.7% and not the 0.2% this page
> advertised**, and the run-preserving distribution is now the one
> [§5](#5-the-third-explanation-which-is-the-one-the-data-names) reports.
> The correction is 13× against us on the advertised rate and still leaves 35%
> a real improvement on 25%, whose rate is **9.1%**. Recomputed from the
> committed dataset; the ceiling constant and the analysis script are code and
> are `rf2-nk1hq`.

**No published magnitude changes** ([§8](#8-what-changes-for-the-record-nothing-and-why-that-is-the-check)).

Owner: `rf2-ymi6j`. Companion question: `rf2-h8o80`.

---

## 0. Predictions, written before the run

Committed in `295f26651a`, before the first ladder result was read; the commit
order is the pre-registration. The box: 24 cores (Intel Core Ultra 9 275HX),
68.1 GB, node v24.13.0, Windows 11, sole occupant. Registered 2026-08-02 07:21
AUSEST.

**P1 — the two clocks' floors are not the same number.** Raw `TaskDuration`
exceeds `taskNet` at every rung by `0.8–1.5 ms`. *If true*, `rf2-h8o80`'s
central evidence is comparing figures on two clocks.

**P2 — the corrected-clock band runs tighter, not wider.**

**P3 — 25% is a tail threshold and nineteen runs cannot bound its tail.** The
ceiling fires on at least one run, and `P(band > 25%)` lands between **2% and
15%** on the frame-only clock.

**P4 — multiplicativity is the finding most at risk.** The corrected-clock
`corr(ctl-2x/floor, floor)` is **less positive than `rf2-cvvb7`'s +0.41**, and
a negative one would not surprise me.

**P5 — the seam still does not track load.**

**P6 — the instrument's blocks are longer than they were**, because
`rf2-7iqb5` added three `ctl-3pt` arms to every bulk block, so the rung-0 band
is **at or above** `rf2-cvvb7`'s 9.3%.

**The discard rule, fixed in advance.** A run that dies part-way is discarded.
A run that completes and then fails a **gate** is a completed measurement and
is kept — the ceiling firing is the datum under study, and dropping the runs
that fire would calibrate the ceiling on the runs that pass it.

### 0.1 How they came out

| | prediction | outcome | |
|---|---|---|---|
| **P1** | task floor − `taskNet` floor = 0.8–1.5 ms at every rung | **+1.337 ms** mean, positive on **19 of 19** runs | **confirmed** |
| **P2** | corrected band no wider than frame-only | frame-only wider on **14 of 19**; mean 15.3% against 14.0% | **confirmed** |
| **P3** | fires at least once; `P(fire)` 2–15% frame-only | fires **2 of 19**; frame-only `P(fire)` **11.3%** run-preserving (4.7% under the pooled model this page has since withdrawn) | **confirmed** |
| **P4** | `corr` less positive than +0.41 | **−0.04** on the published clock, −0.03 frame-only | **confirmed — and the statistic turns out not to be diagnostic at all** ([§6](#6-multiplicativity-is-withdrawn-and-the-correlation-that-carried-it-was-never-diagnostic)) |
| **P5** | no monotone trend in the seam | 12.2 / 3.4 / 3.9 / 9.1 / 3.3 / 4.8% by rung — no trend | **confirmed** |
| **P6** | rung-0 band at or above 9.3% | **25.0%** frame-only, 24.6% published | **confirmed, and by a factor of nearly three** |

One prediction I did not write down and should have: that the band would be
**flat** in load or rise with it. It falls. Nothing in [§0](#0-predictions-written-before-the-run)
anticipated the finding this page leads with, which is worth saying plainly —
the pre-registration constrained the arithmetic, not the imagination.

---

## 1. The instrument, the door, and what is different since the calibration

The row is `bulk300`, driven through **`page.evaluate` → `Runtime.callFunctionOn`**.
Saying so is not ceremony: `rf2-emvod` found that `DevToolsCommandDuration`
carries an operation's script only when the operation runs *inside* a protocol
command, so the door decides what `taskNet` even means. Through this door
`taskNet` is **frame-only**; the published clock is raw `TaskDuration`, script
and frame in one number.

Three things differ between `rf2-cvvb7`'s ladder and this one, and all three
are the instrument moving rather than the box:

- **The driver now writes `roundsTask` and `inPageRounds`.** At the calibration
  blob the token `roundsTask` did not appear in the file at all, which is why
  the original ladder cannot be recomputed and this is a re-run.
- **Every bulk block carries three more arms.** `rf2-7iqb5`'s three-point
  control added `ctl-d1` / `ctl-d100` / `ctl-d200` to the plan, so a `floor`
  sample and the `ctl-2x` sample it is divided by are further apart in time
  than they were. P6 predicted this would widen the band and it did.
- **The ceiling gate consults both clocks**, which it did not at the blob where
  it first fired. That is checkable from the code rather than from prose:
  at `e145597127` the gate reads `o.verdict.seam.verdict.ceilingBreached` and
  nothing else, so **the first firing was on the frame-only clock**, provably.

---

## 2. The ladder, both clocks side by side

Nineteen runs, `bulk300`, at 0 / 2 / 4 / 8 / 12 / 20 competing busy cores —
four replicates at rung 0, three at each other rung, matching `rf2-cvvb7`.
`seam_ladder.cjs` forks *N* spinners each walking a 4 MB array, runs the clock,
and kills them; `--load` is capped four cores below the box, so 20 is the top
rung this design can reach.

**ABSOLUTES BESIDE EVERY RATIO**, because all three of this instrument's
defects were visible in the milliseconds on run 1 and invisible in the ratio.

### 2.1 Raw `TaskDuration` — the published clock

| competing cores | runs | floor ms | per-sample CV | seam | SEGMENT | ROUND | POSITION | band |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 4 | 3.780 | 30.0% | 12.2% | 14.7% | 25.4% | 11.7% | **24.6%** |
| 2 | 3 | 4.386 | 22.4% | 3.4% | 6.3% | 28.3% | 6.4% | 11.8% |
| 4 | 3 | 5.185 | 24.0% | 3.9% | 6.2% | 32.1% | 8.0% | **8.3%** |
| 8 | 3 | 5.901 | 23.9% | 9.1% | 7.5% | 25.9% | 8.1% | 12.8% |
| 12 | 3 | 6.933 | 21.3% | 3.3% | 3.2% | 15.0% | 4.7% | 13.2% |
| 20 | 3 | 6.927 | 15.2% | 4.8% | 3.8% | 8.8% | 4.2% | 9.6% |

### 2.2 `taskNet` — frame-only, superseded, reported

| competing cores | runs | floor ms | per-sample CV | seam | SEGMENT | ROUND | POSITION | band |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 4 | 2.569 | 38.0% | 22.2% | 17.8% | 30.6% | 14.6% | **25.0%** |
| 2 | 3 | 3.203 | 27.8% | 3.9% | 5.3% | 27.2% | 7.8% | 12.4% |
| 4 | 3 | 3.935 | 29.2% | 3.7% | 5.4% | 31.3% | 8.7% | **8.3%** |
| 8 | 3 | 4.582 | 29.1% | 10.4% | 8.7% | 29.0% | 8.3% | 14.7% |
| 12 | 3 | 5.417 | 25.6% | 4.5% | 3.7% | 16.5% | 5.5% | 17.4% |
| 20 | 3 | 5.343 | 18.4% | 5.6% | 4.5% | 9.2% | 3.6% | 11.0% |

### 2.3 The two, held against each other

| | `taskNet` (frame-only) | raw `TaskDuration` (published) |
|---|---:|---:|
| floor absolute | 2.456 – 5.469 ms, mean **4.090** | 3.670 – 7.074 ms, mean **5.427** |
| floor tared, per-block mean | 2.408 – 5.148 ms | 3.064 – 6.179 ms |
| tare (`plumb`) pooled | 0.287 ms | 0.837 ms |
| per-sample CV inside a block | **28.5%** | **23.2%** |
| band | 4.9% – 32.6%, mean **15.3%**, median 14.0% | 4.4% – 31.1%, mean **14.0%**, median 11.7% |
| seam | 0.5% – 33.5%, mean 9.1% | 0.5% – 15.6%, mean 6.5% |
| `ctl-2x / floor` | 1.7079 [1.5885 – 1.8712] | 1.7138 [1.6228 – 1.8417] |
| `corr(band, floor)` | −0.37 | **−0.49** |
| `corr(ctl-2x/floor, floor)` | −0.03 | **−0.04** |
| runs breaching 25% | 2 of 19 | 2 of 19 |

The floors differ by **1.337 ms** on the same samples, which is the whole of
[§4](#4-the-second-explanation-the-regime-and-why-its-evidence-dissolves).

---

## 3. The band is widest on an idle box

Both tables in [§2](#2-the-ladder-both-clocks-side-by-side) say it and the
correlation prices it: **−0.49** between the band and the floor on the
published clock. The profile is not monotone — it dips to 8.3% at four cores
and rises a little through twelve — but the idle rung is nearly three times the
tightest rung and there is no reading of the ladder in which load makes the
instrument *less* reproducible.

**The per-sample dispersion is the mechanism, and it is measured rather than
argued.** Inside a block, the floor's coefficient of variation falls
monotonically with load — 30.0 / 22.4 / 24.0 / 23.9 / 21.3 / **15.2%** — while
the floor absolute rises 3.78 → 6.93 ms. The arm's work gets *more expensive*
under load and *less variable*. The band is a relative statistic, so it follows
the dispersion and not the level.

The leading physical explanation, offered as a hypothesis because this ladder
did not measure frequency or C-state residency: an idle machine parks cores and
drops clocks between samples, so each sample pays a different wake-up, while
competing spinners keep every core awake and the clock pinned. It predicts
exactly the observed pattern — slower and steadier under load — and it is
falsifiable by anyone willing to pin the governor and re-run rung 0.

**The consequence for `rf2-h8o80` is direct.** Its proposed remedy is a
floor-level precondition: *a row may only be adjudicated when the floor is
inside the calibrated range.* On this evidence that rule refuses the most
reproducible runs the instrument can take and passes the least.

---

## 4. The second explanation, the regime, and why its evidence dissolves

`rf2-h8o80` rests on one comparison: `rf2-emvod`'s ensemble ran an `M1` floor
of **5.98 – 6.95 ms**, against `rf2-cvvb7`'s ladder sweep of **3.06 – 5.50 ms**
— *"above the entire ladder, including its most saturated rung."*

Those are two clocks.

| | figure | clock | row |
|---|---:|---|---|
| `rf2-emvod`'s ensemble floor | 5.98 – 6.95 ms | raw `TaskDuration` | `M1` |
| `rf2-cvvb7`'s ladder sweep | 3.06 – 5.50 ms | `taskNet` | `bulk300` |

The gap between them is measured here on the same samples at **+1.337 ms**
(mean over nineteen runs, positive on every one), and `rf2-emvod`'s own
absolutes table puts the `M1` gap at **+1.401 ms** — `task` 6.242 against
`taskNet` 4.841. Subtracting its own figure, that ensemble's floors are
**≈4.58 – 5.55 ms on the ladder's clock**: inside the calibrated range, its top
end level with the ladder's top rung, not above the whole thing.

Two further mismatches sit under the same comparison and neither needs this
ladder to see: it holds an **`M1`** floor — a 901-element cold mount — against
a **`bulk300`** range — a 300-cell update; and it uses the floor as a load
gauge when [§3](#3-the-band-is-widest-on-an-idle-box) shows the band moving the
*other* way from load.

**So `rf2-h8o80` is answered rather than deferred.** The band does not fail
outside a load regime, because it is not a function of load in the direction
assumed. What it *is* a function of is the floor's per-sample dispersion, and
that is a quantity every run already prints.

---

## 5. The third explanation, which is the one the data names

Neither option in the original framing is the main cause. The main cause is
that **25% was set above nineteen draws of a tail, which is not the same as
being above the tail.**

The band is a p10–p90 half-width over eighteen blocks. Eighteen is not many,
and nineteen runs bound a one-in-twenty event at exactly one observation.
Resampling recovers the statistic's sampling distribution — but **which blocks
you are allowed to draw together decides the answer**, and this page got that
wrong the first time.

**The two models.** *Pooled-block*: draw eighteen blocks with replacement from
this ladder's 342, ignoring which run each came from. *Run-preserving*: draw one
of the nineteen runs, then resample **that run's own** eighteen blocks. Only the
second is a model of a future run, because the eighteen blocks of a real run
share a box, a rung and a twenty-second window; pooling shuffles that regime
away and, contrary to what this page originally argued, makes the upper tail
**narrower** rather than wider:

| | pooled-block | **run-preserving** |
|---|---:|---:|
| median | 13.0% | 11.5% |
| q90 | 19.9% | 23.4% |
| q95 | 22.7% | 31.0% |
| q99 | 29.4% | **41.4%** |

*(raw `TaskDuration`, 200,000 draws each, recomputed from the committed compact
dataset. The pooled column is the one this page first published, where it read
13.0 / 19.8 / 22.8 / 29.1% at 20,000 draws — the fourth-figure differences are
Monte-Carlo noise and not a change of statistic. The frame-only clock reads
12.5 / 26.2 / 33.1 / **45.9%** on the same four quantiles under the
run-preserving model.)*

And the exceedance at candidate ceilings, with the observed count beside it so
the bootstrap can be checked against the runs it came from:

| candidate ceiling | `P(fire)` pooled-block | **`P(fire)` run-preserving** | runs of 19 that breach |
|---:|---:|---:|---:|
| 20% | 9.8% | **15.0%** | 4 |
| **25%** *(the ceiling that fired)* | 2.7% | **9.1%** | **2** |
| 30% | 0.9% | **5.9%** | 1 |
| **35%** *(the ceiling now)* | 0.2% | **2.7%** | **0** |
| 40% | 0.06% | **1.2%** | 0 |

*(raw `TaskDuration`. **The run-preserving column is the operative one.** This
page originally led with the pooled column on the argument that it "carries
between-run variation" and is therefore the conservative choice. That argument
is refuted by its own arithmetic: pooling is narrower at every quantile above
the median, and it is narrower because averaging blocks across regimes is
exactly what a real run does not get to do. The correct figure was printed all
along, in the column this page then declined to use.)*

**What that costs the ceiling, stated plainly.** `35%` was published as "the
statistic's own q99, at a measured `P(fire)` of 0.2%". It is the q99 of neither
model — pooled says 29.4%, run-preserving says 41.4% — and its real per-run
false-fire rate is **2.7%**, thirteen times the advertised one. **The derivation
is withdrawn.** What survives is the comparison the change was made for: at 25%
the rate was **9.1%**, so 35% is still a 3.4× improvement, and it is the ceiling
in force. Whether the constant should move again — 41% is where a genuine q99
sits — is a judgement about code and is `rf2-nk1hq`, together with the analysis
script that still resamples the wrong way.

**Three firings in two days is what a one-in-eleven rate produces.** The gate
did not start failing. It was never a tripwire — it was a lottery ticket with
good odds, described as a tripwire because nineteen draws had not yet drawn one.
(This page originally gave the rate as "between one-in-eleven and one-in-forty",
the low end of which is the pooled model's 2.6% and is not a rate a run
experiences.)

**And the band itself has widened since the calibration.** `rf2-cvvb7` measured
4.4 – 18.5%, mean 8.9%, on `taskNet`. The same design on the same box today
reads 4.9 – 32.6%, mean **15.3%**, on that clock, and 4.4 – 31.1%, mean
**14.0%**, on the published one. Part of that is P6 — the three-point control's
arms lengthened every block — and part is unattributed. Either way, a threshold
constructed as *"above everything the calibration produced"* has no margin for
the instrument moving, and the instrument moved.

### 5.1 The recalibrated band

| | |
|---|---|
| **The band, on the clock the rows are stated on** | **4.4% – 31.1%, mean 14.0%, median 11.7%**, over 19 runs at 0–20 competing cores |
| Its run-level q99 | **41.4%** run-preserving (~~29.1%~~ was the pooled-block model, withdrawn) |
| **`BAND_CEILING`** | **35%**, `P(fire)` **2.7% per run** (~~0.2%~~ was the pooled figure). Not a q99 of anything; see the retraction in [§5](#5-the-third-explanation-which-is-the-one-the-data-names) and `rf2-nk1hq` |
| What the ceiling gate reads | **raw `TaskDuration`** only |
| What the frame-only band does now | computed, printed and stored on every run; never a ground of refusal |
| The gate that actually bites | unchanged — a margin inside the run's own band is instrument-limited |

**Why the gate stops consulting the frame-only clock, stated as an argument
because it makes the gate more permissive.** `taskNet` is `TaskDuration` less
`DevToolsCommandDuration`: a *difference of two counters*, and a smaller number
than either. Its relative dispersion is therefore larger by construction, and
this ladder measures it — **28.5% against 23.2%** per-sample inside a block,
giving a wider band on **14 of 19** runs. Nothing is published on that reading;
`rf2-emvod` demoted it to a diagnostic. Refusing a run because the noise of a
subtraction nobody quotes came out wide is refusing on a criterion that does
not match what it is judging, which is the precise shape of the defect this
bead was opened to find.

**The counterfactual, so the change is priced rather than asserted.** On this
ladder it moves nothing: both breaches breach on both clocks. On the companion
ladder of [§7](#7-the-companion-ladder-and-the-pre-condition-it-failed) it
would move five refusals to two.

---

## 6. Multiplicativity is withdrawn, and the correlation that carried it was never diagnostic

`rf2-cvvb7` concluded that the ambient perturbation is **multiplicative**, so
floor-normalisation cancels it and `(k·H)/(k·F) = H/F` is exact. The evidence
was `ctl-2x / floor` correlating **+0.41** with the floor, read as the wrong
sign for an additive `c` because `(2W + c)/(W + c)` *falls* as `c` grows.

**The level refutes it, and the level is not a subtle statistic.** A purely
multiplicative perturbation predicts `ctl-2x / floor = 2.00` **exactly, at
every rung, with no variance at all** — that is what "cancels exactly" means.
Nineteen runs read **1.7138 [1.6228 – 1.8417]** on the published clock and
1.7079 [1.5885 – 1.8712] frame-only. Floor-normalisation does not cancel
exactly, and the band is the measurement of what it fails to cancel.

**The correlation was never diagnostic, and the reason is a modelling slip
worth naming.** `(2W + c)/(W + c)` falls as `c` grows *with `W` held fixed*.
Load does not vary `c`; it varies `W`. Under a fixed `c` and a load that
inflates `W`, the same expression *rises* toward 2.00 — so a positive
correlation is equally the additive signature, and the sign carries no
information either way.

The measurements make the point without any modelling. On the silent ladder the
correlation is **−0.04**. On the companion ladder taken twenty-five minutes
earlier on the same box it is **+0.88**. A statistic that swings from −0.04 to
+0.88 between two nineteen-run ensembles on one machine in one morning cannot
carry a structural conclusion about how a perturbation enters.

**What survives, and it is the useful part.** The residue that fails to cancel
behaves like a per-sample cost that does not scale with the page:

| competing cores | floor tared ms | `ctl-2x / floor` | implied `c` ms | `c/W` |
|---:|---:|---:|---:|---:|
| 0 | 3.162 | 1.7347 | 0.913 | 0.289 |
| 2 | 3.543 | 1.7272 | 0.989 | 0.279 |
| 4 | 4.414 | 1.6818 | 1.412 | 0.320 |
| 8 | 5.034 | 1.6604 | 1.740 | 0.346 |
| 12 | 6.017 | 1.7111 | 1.738 | 0.289 |
| 20 | 6.006 | 1.7604 | 1.450 | 0.241 |

`c = W·(2 − ctl-2x/floor)`, raw `TaskDuration`, tared. It runs **0.91 – 1.74 ms**
across a floor that nearly doubles, against `rf2-emvod`'s independently
inverted **0.79 – 1.04 ms** on four different rows and `rf2-7iqb5`'s re-measured
**1.0397 ms**. It is not a constant — `c/W` is flatter than `c` is, which says
it is not purely additive either — and that is exactly why `rf2-7iqb5` built a
control that **differences it away** instead of estimating it. Nothing on this
page needs `c` to be a constant; the band needs only to measure what survives,
and it does.

---

## 7. The companion ladder, and the pre-condition it failed

**A first nineteen-run ladder was taken and is not the published one.** While it
ran, this page's author was issuing shell commands on the same box — `git
cat-file` over large blobs, a commit, a push, and a polling loop that spawned a
process every twenty seconds. That is load, it is the pre-condition this lane
refuses runs for, and it was noticed after the fact from the run timestamps
rather than at the time.

It is reported rather than deleted, because two nineteen-run ensembles on one
box hours apart are evidence about the band that one is not:

| | companion (contaminated) | published (silent) |
|---|---:|---:|
| window | 07:18 – 07:41 AUSEST | 07:46 – 08:08 AUSEST |
| floor, published clock | 3.213 – 6.020 ms, mean 4.589 | 3.670 – 7.074 ms, mean 5.427 |
| band, published clock | 8.5% – 29.9%, mean **17.5%** | 4.4% – 31.1%, mean **14.0%** |
| band, frame-only | 12.8% – 38.0%, mean **22.3%** | 4.9% – 32.6%, mean **15.3%** |
| frame-only wider on | 16 of 19 runs, ratio 1.27× | 14 of 19 runs, ratio 1.10× |
| `corr(ctl-2x/floor, floor)` | **+0.88** | **−0.04** |
| `corr(band, floor)` | −0.27 | −0.49 |
| breaches at 25% | 5 of 19 (4 frame-only, 2 published) | 2 of 19 (both, both clocks) |

**Two candidate causes and this ladder cannot separate them**, so both are
stated: the operator's shell activity, and a box that had been running the
benchmark for twenty-five minutes and was correspondingly hotter. The second is
not idle speculation — the companion's floors are *lower* at every rung, which
is a faster machine, not a busier one.

**What replicates across both, and is therefore the durable part:** the band is
widest at rung 0 in both (20.4% and 24.6% on the published clock); the
frame-only band is wider than the published one in both; `corr(band, floor)` is
negative in both; and `ctl-2x / floor` sits far below 2.00 in both.

**What does not replicate is `corr(ctl-2x/floor, floor)`** — +0.88 against
−0.04 — which is [§6](#6-multiplicativity-is-withdrawn-and-the-correlation-that-carried-it-was-never-diagnostic)'s
point arriving from the data rather than from the algebra.

### 7.1 Two runs the arm-order guard refused, and they are not in either count

Of twenty-three runs taken for the published ladder, **two exited 2** — the
arm-order guard found an arm reading differently for where in the plan it was
measured, both at rung 4. Their figures are not figures and they are excluded
by the driver's own rule rather than by a judgement of mine; replacements were
taken until rung 4 had its three. Their datasets are kept beside the ladder
under `out/ladder-ymi6j-refused/` so the exclusion is inspectable.

Re-running rung 4 overwrote two datasets from the same rung taken minutes
earlier. That was a mistake in the harness rather than a selection: the
replacements are what is reported, whatever they said. It is recorded because a
reader cannot tell the difference from the outside and is entitled to be told.

### 7.2 One run reproduced the seam that nothing could reproduce

`rf2-cvvb7` recorded a **34%** cross-segment floor seam that sat beyond all
38,000 of its own null draws and *"remains unexplained by anything this study
could reproduce"* (`rf2-1zvdu`).

Run `L0-r3` of the published ladder read a pooled seam of **33.5%** on
`taskNet` — at **zero load**, on a silent box, in nineteen runs. The same run's
seam on the published clock is 12.9%.

That does not explain the 34%; it locates it. It is an **idle-box,
frame-only** phenomenon, it is reproducible at roughly one run in twenty, and
it is the same rung that produces the widest bands. Anyone re-opening
`rf2-1zvdu` should start at rung 0 on the frame-only clock rather than looking
for a busy neighbour.

---

## 8. What changes for the record: nothing, and why that is the check

The ceiling moved from 25% to 35%. Every run it ever refused stays refused as a
magnitude, for reasons that were never the ceiling:

- **The first firing** (26.2%, `rf2-yd52q`'s earlier `bulk300` ensemble) was on
  `bulk300`, which `rf2-7iqb5` refuses on the three-point control — ~6 µs/cell
  of non-layout cost at low dirty-set size that is gone by 100, i.e. paint
  saturating, against a noise floor requirement of ~3.5% and a measured
  28–48%. Bulk is not adjudicable and the ceiling is not why.
- **The second** (26.2% on `bulk300`, `rf2-emvod` run 8) — the same row, the
  same refusal.
- **The third** (28.4% on `narrow`, same run) — `narrow` reads **parity**,
  1.0236× [0.9855 – 1.0900], and every reportable run's margin is inside its
  own band. A run whose band is 28.4% and whose margin is 2–9% is
  instrument-limited at either ceiling.
- **`M1`, the one row published as a magnitude**, carried bands of **4.9–10.8%**
  across `rf2-emvod`'s seven runs — a third of either ceiling. Nothing about
  that row is near this change.

**That is the check on the recalibration rather than a happy accident.** A
threshold change that moved a published magnitude would need arguing for on the
magnitude's own terms; one that moves none is a change to the instrument's
description of itself, which is what it was meant to be.

The one thing that *is* now different: a future run at 26–34% on the published
clock is adjudicated instead of refused, and its rows are adjudicated against a
band of 26–34%, which is a gate almost nothing clears. The strictness moved from
a cliff to the slope it was always supposed to be.

---

## 9. Provenance

| | |
|---|---|
| Runtime | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), Playwright 1.59.1, node v24.13.0, `hardware-concurrency` 24, `device-memory` 32 |
| Box | 24 cores (Intel Core Ultra 9 275HX), 68.1 GB. **Sole occupant for the published ladder**; see [§7](#7-the-companion-ladder-and-the-pre-condition-it-failed) for the companion's failed pre-condition |
| Design | 6 rounds × (4 warm-up + 10 samples) per arm per segment, 3 segments, arm order rotating with the round — unchanged from `rf2-cvvb7` |
| Ladder | 19 runs of `bulk300` at 0 / 2 / 4 / 8 / 12 / 20 competing busy cores; 4 replicates at rung 0, 3 elsewhere |
| Windows | published 07:46 – 08:08 AUSEST 2026-08-02, in three blocking chunks so no shell command ran during a measurement; companion 07:18 – 07:41 |
| Discarded | **2 runs, both arm-order-guard refusals (exit 2) at rung 4**, kept under `out/ladder-ymi6j-refused/`. No run died part-way; the driver writes no dataset for a run that did |
| Exit code | **1** on every run — the three-point control refuses `bulk300` (`rf2-7iqb5`), which is the row's standing verdict and not a fault of this ladder. Every whole-run gate cleared: no page errors, canonical DOM identical, 0 unverified of 1,764 writes, teardown clean |

The instrument, by blob rather than by SHA, because a SHA does not survive a
rebase. These are the blobs the **published** ladder was taken at — before this
page's own changes to `seam.cjs` and `clock_run.cjs`, which is what makes the
ladder a measurement of the instrument in force rather than of itself:

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/seam_ladder.cjs` | `ae0ddf6e1df15c8d5ad2e90a35154258762a553a` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs` | `0d54998103649cd8479d3a73eca9e8745a026a8c` |
| `implementation/freehand/test/re_frame/bench/hicasso/seam.cjs` | `0c98377896f95e7d28ac173cb9e17898179e8e84` |

`seam_ladder.cjs` is at the **same blob** as `rf2-cvvb7`'s ladder, unchanged, so
the load half of the design is identical and not merely equivalent.

```bash
cd implementation
# build once
HCLOCK_ONLY=bulk300 node freehand/test/re_frame/bench/hicasso/clock_run.cjs

# then one rung, three replicates — and issue NO other command while it runs
for r in 1 2 3; do
  node freehand/test/re_frame/bench/hicasso/seam_ladder.cjs \
    --load 12 --label "L12-r$r" --json "out/ladder-ymi6j/L12-r$r.json"
done

# recompute every figure on this page from the datasets
node freehand/test/re_frame/bench/hicasso/ladder_band.cjs out/ladder-ymi6j/L*.json
```

### 9.1 The dataset survives this time, and that was the point

`rf2-cvvb7`'s ladder could not be restated for two independent reasons: the
quantity a restatement needs was never written, and the datasets went to a
gitignored `out/`. Both are closed here.

- Every figure on this page is recomputed from the driver's own datasets by
  **`ladder_band.cjs`**, which calls `seam.cjs`'s exported adjudicators rather
  than reimplementing them — so a figure here and a figure the driver printed
  are the same figure by construction. Checked run by run: the analysis
  reproduces all nineteen runs' printed bands on both clocks, including the two
  breaches, digit for digit. **And that durability is what caught the
  bootstrap**: [§5](#5-the-third-explanation-which-is-the-one-the-data-names)'s
  retraction is a recomputation over the committed file, taking no new run.
  The **one figure on this page the script does not yet reproduce** is the
  run-preserving distribution itself — `ladder_band.cjs` still pools blocks
  across runs, which is `rf2-nk1hq`.
- A raw dataset is ~220 KB and nineteen are ~4 MB, which does not belong in a
  repository. So `--emit` writes the reduced quantities every statistic is a
  function of — the eighteen per-block tared `floor` and `ctl-2x` cells on each
  clock, the pooled floor medians per segment, the bar row's per-round legs —
  and **`data/ladder-ymi6j.json` is committed**. `ladder_band.cjs --from` that
  file reproduces every band, every `ctl-2x / floor` and every bar row exactly;
  a floor absolute and the odd seam move in the fourth significant figure,
  which is the file's stated rounding.
- What the compact file does **not** carry is the per-sample distribution
  inside a block. A question about within-block shape needs the raw datasets,
  which are kept beside the run under `out/ladder-ymi6j/`.

---

## 10. What this hands the programme

- **The band is widest on an idle box** — 24.6% at zero load against 8.3% at
  four cores, `corr(band, floor) = −0.49`, with the floor's per-sample
  dispersion falling 30.0% → 15.2% as the load rises. Load makes this
  instrument slower and steadier.
- **`rf2-h8o80` is answered, not deferred.** The band does not fail outside a
  load regime, and the evidence that it did was an `M1` raw-`TaskDuration`
  floor read against a `bulk300` `taskNet` range — two clocks 1.34 ms apart.
- **A gate calibrated never to fire, fired because it was calibrated against
  nineteen draws rather than against a distribution.** `P(band > 25%)` is
  **9.1%** per run. The general lesson costs nothing to reuse: a threshold set
  above the largest of *n* observations has an unmeasured false-fire rate, and
  the bootstrap that measures it is twenty lines.
- **And the second lesson is that twenty lines is enough to get wrong.** This
  page's own bootstrap pooled blocks across runs, which destroys the regime the
  eighteen blocks of one run share and **narrows** the upper tail: it put the
  q99 at 29.4% where run-preserving resampling puts it at **41.4%**, and the
  per-run false-fire rate at 0.2% where it is **2.7%**. The claim that 35% is a
  q99 is withdrawn ([§5](#5-the-third-explanation-which-is-the-one-the-data-names)).
- **The ceiling is 35%, at a measured per-run false-fire rate of 2.7% against
  25%'s 9.1%, and the gate adjudicates the clock the rows are stated on.** The
  frame-only band is reported on every run and refuses nothing. Whether 35%
  should move to a genuine q99 is `rf2-nk1hq`.
- **`rf2-cvvb7`'s multiplicativity finding is withdrawn.** Pure
  multiplicativity predicts `ctl-2x / floor = 2.00` with no variance; nineteen
  runs read 1.71 [1.62 – 1.84]. The correlation that carried it reads +0.88 on
  one ensemble and −0.04 on another taken twenty-five minutes later.
- **`(k·H)/(k·F) = H/F` is not free**, so the pages that lean on it lean on the
  band instead — which is the quantity that was always doing the work.
- **The unexplained 34% seam is located**: an idle-box, frame-only phenomenon,
  reproduced here at 33.5% at zero load in nineteen runs (`rf2-1zvdu`).
- **No published magnitude changes**, which is the check on all of it.
