# The 2026-08-08 row is the arm's top level

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-nkeba` — V1's allocation control has an
across-time clause, the clause was read for the first time on 2026-08-17, and it failed
by 16 – 20%. This page settles what the corpus does about that.

Written 2026-08-20 on `worker/foldcorpus-nkeba`, off
`f638b3c9d19dd97479ff6841a1a18e4fe842bb0d`, which is `origin/main` at the time of
writing and therefore an anchor a fresh clone resolves.

**NO BENCH WINDOW WAS TAKEN.** No browser was launched, no rig file was touched, and no
new allocation figure is published. Every number below is re-derived from datasets
already committed, by
[`alloc_ladder_placement.cjs`](../../../../implementation/hicasso/test/re_frame/bench/hicasso/alloc_ladder_placement.cjs),
which reads the corpus and prints this page's tables:

```sh
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_ladder_placement.cjs
```

## The answer, first

**THE 2026-08-08 ROW IS NOT A DIFFERENT RIG. IT IS THE SAME ARM, ON ITS TOP LEVEL.**

- **The arm has a level ladder spanning `3,784 B` — the same figure on both segments —
  at the very revision the 2026-08-08 row was measured at.** `rf2-c4hhk` pinned
  `implementation/core/src` at `4a1537cb717dc6660aa449642f198a2cc970c93b`, the commit
  the 2026-08-08 row itself landed at, and read the arm 70 times. Sixty-nine scored:
  `19,100 / 19,540` in 32 of them, and four settled levels above it topping out at
  `22,884 / 23,324`.
- **The published shortfall is `4,034 – 5,018 B`. The ladder spans `3,784 B`.** The
  control is being asked to adjudicate an effect the quantity it reads varies by, at one
  revision, on one instrument, in one afternoon. **It cannot, and no amount of care in
  running it will change that.**
- **Placed on the ladder, the 2026-08-08 row lands on the top rung to within
  `2 – 373 B`** — 0.01% to 1.60%, across eight independent conversions. The row is
  reproduced, not contradicted. What looked like a 16 – 20% across-time failure is a
  16 – 20% *level* difference between two single runs, each honest.
- **The 2026-08-08 run cannot be adjudicated by the level witness, and not by accident.**
  It holds six rounds; the witness's AFTER window opens at round 6, which is exactly
  where 35 of 37 elevated runs make their step. The run stops one round before the
  transition it would need to show.

**THE DISPOSITION: the across-time clause is RETIRED, and the 2026-08-08 rows are marked
NOT-COMPARABLE wherever they are cited.** Neither reading is withdrawn and neither is
declared wrong. See [The disposition](#the-disposition).

**Nothing is widened.** No threshold, tolerance or budget moves in this change. The
disposition *removes* a licence — pages currently free to difference against the
2026-08-08 rows no longer are.

## What changed since 2026-08-18, and why this could not be said then

This bead carried an explicit hold from 2026-08-18: *"DO NOT TAKE A WINDOW FOR THIS BEAD
YET"*, on the ground that *"the mechanism is unnamed and, until it is, a single run's
`F_old` cannot be quoted with an uncertainty smaller than the effect."* Two beads were
named as the path forward and both have since closed.

| | what it was | what it now is |
|---|---|---|
| the arm's levels at one revision | two, maybe three, the high one at n = 1 – 2 runs | **four settled levels over 69 scored runs**, `rf2-c4hhk` |
| whether the census contaminates them | open — the counter compiles into the very path the mechanism lives in | **refuted from both directions**, `rf2-c4hhk` |
| the substrate the ladder was measured on | today's | **`4a1537cb71` — the 2026-08-08 commit itself** |
| a within-run level witness | a proposal, unarmable at n = 1 | **armed as a refusal**, `rf2-a233t`, PR #8536 |

The hold's condition is still not met — the mechanism is *still* unnamed, and
`rf2-6kxub` remains open on the mode's unstable rate. **But the hold was aimed at the
wrong question.** It assumed the bead needed `F_old` quoted precisely enough to decide
whether the rig moved. The ladder makes that unnecessary: once the same substrate is
shown to produce both readings, the across-time comparison does not need to be *made
better*, it needs to be *withdrawn*.

## 1. The 2026-08-08 row, re-derived from its own dataset

`alloc-2rtt6-138/run1.json`, six rounds, six writes a window, B = 24, `:p0/write-all`.
The published pair is the **median** of `rise/W`, to the byte — established by PR #8434
and re-derived here independently.

| segment | per-round `rise/W` | mean | **median (published)** |
|---|---|---|---|
| `reagent-subs` | 21,829.3 / 24,440.0 / 24,122.0 / 24,152.7 / 23,928.0 / 24,094.0 | 23,761.0 | **24,108.0** |
| `uix-subs` | 25,144.0 / 24,958.7 / 24,502.0 / 24,961.3 / 24,212.7 / 24,294.0 | 24,678.8 | **24,730.3** |

## 2. The same row on today's `legMedian` basis

The 2026-08-08 window ran six writes with no prime split; today's runs seven — one prime,
six measured — and reports `legMedian` over the six. Two conversions, chosen so they
share no term but `rise`:

- **`maxStep`**: the prime is the window's largest step, so the five legs left average
  `(rise − maxStep) / 5`.
- **`E`**: if six legs are one repeated unit `L` and the first carries a prime excess
  `E`, then `rise = 6L + E`, so `L = (rise − E) / 6` — with `E` taken from the *same
  substrate's* own preserved `primeExcess`, which the 2026-08-08 dataset does not record
  and therefore cannot have influenced.

`gaps out` additionally removes the six 32 B inter-leg gaps PR #8442's reconstruction
established for this dataset.

| segment | per-round, `maxStep` basis | median, rounds 1 – 5 | median, all rounds |
|---|---|---|---|
| `reagent-subs` | 20,798 / 23,352 / 23,146 / 23,010 / 22,913 / 23,117 | 23,116.8 | 23,063.2 |
| `uix-subs` | 24,094 / 23,879 / 23,515 / 23,879 / 23,168 / 23,270 | 23,515.2 | 23,697.2 |

**The check on the model.** The prime excess the `maxStep` conversion implies is
5,858 – 6,858 B on `reagent-subs` (mean 6,231) and 5,921 – 6,493 B on `uix-subs` (mean
6,267). The same substrate's *preserved* `primeExcess`, over 951 and 829 certified
rounds, has a median of 6,800 B and 6,864 B. The two agree to within a few hundred bytes
and no better — which is the accuracy this conversion is entitled to, and is why the
placement below is reported across eight variants rather than one.

## 3. The level ladder at the same substrate revision

`alloc-c4hhk`. **70 datasets committed; `armed-25` produced no reading (Chromium failed
to launch) and exited 1 exactly like the 69 good runs; 69 scored; 69 admissible with
zero control or read-back exclusions.** Estimator and admissibility are `rf2-77gz8`'s,
carried unchanged: the median, over certified windows at round index ≥ 6, of that
window's `legMedian`. **The runner's exit code is not a criterion.**

| level | `reagent-subs` | runs | `uix-subs` | runs |
|---|---|---|---|---|
| low | **19,100** | 32 | **19,540** | 32 |
| +2,532 | 21,620 / 21,632 / 21,640 | 9 / 20 / 2 | 22,060 / 22,072 / 22,076 | 9 / 21 / 1 |
| +2,628 | 21,728 | 5 | 22,168 | 5 |
| **+3,784** | **22,884** | **1** | **23,324** | **1** |

**Span `3,784 B` on both segments** — 19.81% of the low level on `reagent-subs`, 19.37%
on `uix-subs`. The elevated mode ran at 37 of 69 (18 of 34 armed, 19 of 35 unarmed),
which reproduces `rf2-c4hhk`'s figures exactly.

**The top rung is seen twice, in two independent windows.** `armed-13` reads
22,884 / 23,324 here; `rf2-9jrhi`'s `bisect-1`, at the same substrate revision and in a
different window on a different day, reads **22,892 / 23,332** — 8 B away on both
segments. So the rung is not one run's excursion.

## 4. Why a six-round run cannot be scored at all

Among the 37 elevated runs, the round index at which the reading first reaches the
21,000 B criterion is:

| first round at or above 21,000 | runs |
|---|---|
| round 4 | 2 |
| **round 6** | **35** |

The 2026-08-08 run holds rounds 0 – 5. The published estimator reads round index ≥ 6, and
`rf2-a233t`'s level witness takes its AFTER from that same window. **So the 2026-08-08
run stops exactly where the transition begins**, and neither the estimator nor the
witness can be applied to it.

This is not an inference. `alloc-9jrhi/pilot-rounds6-head-88411ed803.json` is a committed
six-round run of this arm, and the estimator returns **no value** for it on either
segment, for exactly this reason.

## 5. Placement inside the envelope

Every certified per-round `legMedian` from the 69 admissible runs, against the six
2026-08-08 rounds converted onto that basis:

| segment | envelope, n readings | round 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|---|
| `reagent-subs` | 18,908 – 23,950, n = 951 | 57.8th | 99.9th | 99.9th | 99.9th | 99.7th | 99.9th |
| `uix-subs` | 19,420 – 23,692, n = 829 | +402 B | +187 B | 99.9th | +187 B | 98.7th | 98.7th |

All six `reagent-subs` rounds fall **inside** the envelope; five of them in its top 0.3%.
Three of six `uix-subs` rounds fall **above** it, by **187 – 402 B**. That excursion is
the whole of what the same substrate's 69 runs do not cover — against a published
shortfall of 4,034 – 5,018 B, it is an order of magnitude smaller.

## 6. The row against the top rung

Eight conversions, all against `armed-13`'s estimator at the same substrate revision:

| segment | conversion | 2026-08-08 | top rung | delta | delta % |
|---|---|---|---|---|---|
| `reagent-subs` | `maxStep`, rounds 1 – 5 | 23,116.8 | 22,884 | +232.8 | +1.02% |
| `reagent-subs` | `maxStep`, rounds 1 – 5, gaps out | 23,078.4 | 22,884 | +194.4 | +0.85% |
| `reagent-subs` | `maxStep`, all rounds | 23,063.2 | 22,884 | +179.2 | +0.78% |
| `reagent-subs` | `maxStep`, all rounds, gaps out | 23,024.8 | 22,884 | +140.8 | +0.62% |
| `reagent-subs` | `E` = 6,800, rounds 1 – 5 | 22,988.7 | 22,884 | +104.7 | +0.46% |
| `reagent-subs` | `E` = 6,800, rounds 1 – 5, gaps out | 22,956.7 | 22,884 | +72.7 | +0.32% |
| `reagent-subs` | `E` = 6,800, all rounds | 22,974.7 | 22,884 | +90.7 | +0.40% |
| `reagent-subs` | `E` = 6,800, all rounds, gaps out | 22,942.7 | 22,884 | +58.7 | +0.26% |
| `uix-subs` | `maxStep`, rounds 1 – 5 | 23,515.2 | 23,324 | +191.2 | +0.82% |
| `uix-subs` | `maxStep`, rounds 1 – 5, gaps out | 23,476.8 | 23,324 | +152.8 | +0.66% |
| `uix-subs` | `maxStep`, all rounds | 23,697.2 | 23,324 | +373.2 | +1.60% |
| `uix-subs` | `maxStep`, all rounds, gaps out | 23,658.8 | 23,324 | +334.8 | +1.44% |
| `uix-subs` | `E` = 6,864, rounds 1 – 5 | 23,358.0 | 23,324 | +34.0 | +0.15% |
| `uix-subs` | `E` = 6,864, rounds 1 – 5, gaps out | 23,326.0 | 23,324 | +2.0 | +0.01% |
| `uix-subs` | `E` = 6,864, all rounds | 23,586.3 | 23,324 | +262.3 | +1.12% |
| `uix-subs` | `E` = 6,864, all rounds, gaps out | 23,554.3 | 23,324 | +230.3 | +0.99% |

**Every variant is positive and every variant is under 1.61%.** The sign is consistent —
the 2026-08-08 row sits marginally *above* the top rung on every reading, never below —
so a small residual is not excluded and is not claimed away. What has gone is its
*magnitude*: from 4,034 – 5,018 B to **2 – 373 B**.

For scale, the clause's own arithmetic:

| segment | 2026-08-08 target | certified 2026-08-17 | short by | % |
|---|---|---|---|---|
| `reagent-subs` | 24,108 | 19,349 / 19,650 / 19,816 | 4,759 / 4,458 / 4,292 B | −19.74 / −18.49 / −17.80% |
| `uix-subs` | 24,730 | 19,712 / 20,696 | 5,018 / 4,034 B | −20.29 / −16.31% |

## The disposition

The bead asked two questions. Both are answered here rather than deferred, because the
ladder answers them and a further window cannot.

**(a) The across-time clause is RETIRED — not amended, and not left failing.**

*Retired* rather than *amended*, because there is no comparable quantity to amend it to.
Stating one would require knowing which rung the 2026-08-08 run sat on, and all three
routes to that are closed: its per-leg samples were not preserved, its six rounds put it
outside the estimator's and the witness's reach, and the rung a run lands on is not
controllable anyway — `rf2-6kxub` records the mode's rate moving 0% → 10% → 53% at one
revision on one instrument.

*Retired* rather than *left failing*, because "FAILS by 16 – 20%" asserts something the
evidence does not support. A control reporting a 16 – 20% failure on a quantity whose own
ladder spans 19.4 – 19.8% is reporting the arm's dispersion. Leaving the verdict standing
would keep a number on the record that has been shown to be indistinguishable from the
instrument reading itself twice.

**V1's FIRST clause is untouched and stands.** `F_old` flat in B is confirmed on
certified windows, and it is the clause that does the work `F_old` was introduced for.
Nothing here weakens it.

**Nothing replaces the across-time clause, and nothing needs to.** The within-run half of
`F_old`'s job — licensing the comparison between the two writes, measured in the same
session on the same build — was never in question and is now guarded twice over: by the
leg witness, which asks whether a window's legs are alike, and by `rf2-a233t`'s level
witness, which refuses a run whose level moves under it. The across-time half is the one
that was never licensed, and no page should make it.

**(b) The 2026-08-08 rows are marked NOT-COMPARABLE wherever they are cited, and are NOT
withdrawn.**

They are a correct reading of the arm's top rung. Today's are correct readings of its
low rung. Neither is *the* value; the arm does not have one. So the rows keep their place
in the record as measured, and what goes is the licence to difference anything against
them.

The census of citing pages, taken at the tip rather than carried from the bead — the bead
named three, and the corpus holds six besides this page and the studio index:

| page | what it cites | disposition |
|---|---|---|
| [`allocation-instrument-rework.md`](../allocation-instrument-rework.md) | `F ≈ 24.4 KB`, and V1's clause itself | clause retired; rows marked |
| [`product/release-scans.md`](../product/release-scans.md) | `24.4 KB` as the `rf2-2rtt6.140` non-claim leg | rows marked; the non-claim is unaffected |
| [`the-survival-metrics-allocation-half.md`](the-survival-metrics-allocation-half.md) | the `F_old` table, and *"keeps its full force across the change"* | that sentence corrected; rows marked |
| [`the-write-halfs-floor-is-the-pipeline.md`](the-write-halfs-floor-is-the-pipeline.md) | the clause as UNASSESSED, and the `rise/W` basis | pointer added |
| [`the-floor-certifies-and-the-control-does-not.md`](the-floor-certifies-and-the-control-does-not.md) | the failing verdict, `17.8 – 19.7%` / `16.3 – 20.3%` | pointer added; readings stand |
| [`the-controls-target-is-not-the-quantity-it-is-read-against.md`](the-controls-target-is-not-the-quantity-it-is-read-against.md) | the re-derivation and the unresolved residual | pointer added; residual now bounded |

## What is NOT claimed

- **NOT that the 2026-08-08 figure was wrong.** It is reproduced to within 0.01 – 1.60%
  by a run of the same arm at the same substrate revision. This page finds it *correct*.
- **NOT that the 2026-08-17 figures were wrong.** They are the low rung, which 32 of 69
  runs also produce.
- **NOT that nothing moved.** Every variant leaves the 2026-08-08 row 2 – 373 B *above*
  the top rung, and the sign is consistent across all sixteen cells. A residual of that
  size is not excluded — it is merely two orders of magnitude short of a rig having
  changed. **Which of the two readings moved is still not determined, and this page does
  not determine it.** What it shows is that the question no longer needs an across-time
  term to answer it: no such term is *required*, and none is *established*.
- **NOT a mechanism.** The ladder is described, not explained. `rf2-6kxub` carries the
  rate; the mechanism behind the levels remains unnamed, exactly as `rf2-77gz8` and
  `rf2-c4hhk` left it.
- **NOT anything about τ, and no gate is widened.** No threshold, tolerance or budget
  moves in this change.

## Limits

- **The top rung is n = 2 runs** — `armed-13` and `rf2-9jrhi`'s `bisect-1` — out of 77
  scored across two windows. Enough to establish it is a rung rather than an excursion;
  not enough to speak to its rate, and `rf2-6kxub` records that the rate is not a stable
  property anyway.
- **The conversion in §2 is a MODEL of the 2026-08-08 window's leg structure, not a
  measurement of it.** The per-leg samples were not preserved. Two conversions sharing no
  term but `rise` agree to within 200 B, and the implied prime excess sits within a few
  hundred bytes of the preserved band, which is corroboration and not proof. This is the
  lesson `rf2-erre5` was filed for, met from the wrong side.
- **`rf2-c4hhk` pinned the SUBSTRATE, not the instrument.** Its 70 runs used today's rig
  against `implementation/core/src` at `4a1537cb71`. So what these figures exclude is a
  *substrate* explanation of the gap — corroborating `rf2-9jrhi`'s flat bisect on a
  sample 8× larger and at the endpoint revision itself. A residual *instrument*
  difference between the 2026-08-08 runner and today's is neither ruled in nor out, and
  the 2026-08-08 dataset records no runtime version.
- **The `uix-subs` excursion above the envelope is real and unexplained.** 187 – 402 B on
  three of six rounds. It is small enough to be the conversion's own error and is not
  claimed to be anything else.
- **`rf2-a233t`'s level witness is not yet on `main`.** It lands with PR #8536. Nothing
  on this page depends on it running; the argument in §4 is about what the witness
  *cannot* adjudicate, which its arrival does not change.
