# The arms' spread does not collapse — τ refused on the primed instrument

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-e9wr`, re-taking `rf2-2rtt6.140`
criterion 5's permitted witnesses V3 and V1 on the instrument `rf2-oiy1` primed.
Measured 2026-08-16 12:11–12:17 AUSEST, branch `worker/w-e9wr` off `2cf87aed5e`.

Runtime, beside every figure below: Chromium **147.0.7727.15** via Playwright,
shadow-cljs `release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`.

**No slope is published on this page and none was fitted**; both plans run here
carry no rungs. This page reports one quantity — the **relative dispersion of a
window's work legs** — and the decision that follows from it. It restates no
figure from [the write half's floor is the
pipeline](the-write-halfs-floor-is-the-pipeline.md), which stays as its date
left it; where a number here matches one there, that is a replication and is
labelled as one.

## The answer, first

`rf2-e9wr` listed three branches. **The evidence refutes (b), refutes (a), and
leaves (c) standing — τ stays a declared placeholder at 0.25.**

- **Branch (b) is REFUTED, and refuting it is this window's deliverable.** (b)
  predicted that once `rf2-oiy1` removed the first-leg term, *"the arms' spread
  may collapse toward the controls' and the stated rule works unchanged"*. The
  first half happened and the second did not. The floor arm, which refused 6 of
  6 at every page under both writes before the prime, now **certifies in 41 of
  72 windows** — but its leg dispersion stays one to three orders of magnitude
  wider than the controls'.
- **The prime itself is confirmed and reproduced.** The first-leg excess reads a
  median **6,864 B over all 72 arm windows** here against the **6,966 B** median
  published over 336 windows pre-prime — agreement to 1.5% — and it is now
  outside every measured cohort.
- **The controls reproduce exactly.** Worst relative leg deviation **0.99%**,
  17 of 18 windows exactly **0.00%**, matching the published 0.99% and 11-of-12.
- **The arms do not.** Over the 47 collection-free floor-arm windows the worst
  relative leg deviation runs **0.00% – 1,835.79%**. Setting aside five
  six-figure excursions leaves 42 windows at **0.00% – 38.91%, median 2.69%**.
- **And the arms' clean windows are not ONE population**, which is the finding
  that decides branch (a). In all six runs, **all 11** collection-free round-3
  windows read **≤ 0.19%** while **all 11** round-2 windows read **2.66% –
  20.37%**. τ calibrated on the arms' own data therefore moves by two orders of
  magnitude with the round it is read at: ≈ 0.004 at round 3, ≈ 0.41 at round 2.
- **So the witness is unusable as specified**, on V3's own instruction for this
  case — *report the spread rather than picking a τ that certifies everything*.

## The box, and why this window did not ask for a quiet one

**This window ran on a LOADED box, deliberately, and the page says so before it
says anything else.** The estimand is a census of a monotone byte counter, which
reads the same under load; the standing rule is that counter estimands take no
quiet-box slot. That classification was checked at source and it is **half
right, and the failing half is one this bead touches**:

- **The counter's READING is load-independent, and that is corroborated in-run
  rather than assumed.** Every one of the seven runs' positive controls landed
  at the same figures the granted quiet box produced.
- **A window's CLEANLINESS is not.** Whether a collection or a large excursion
  falls inside the measured region is discretionary V8 work, and 25 of 72 arm
  windows here carried an observed collection. **This window cannot separate the
  load contribution from the structural one**, and does not try.

**The verdict is made robust to that rather than argued around it.** Discard
every window with an observed collection *and* every six-figure excursion — the
whole class that load could plausibly explain — and 42 windows remain, running
to 38.91% against controls that read 0.99% in the same sessions. The refutation
of branch (b) survives on that subset, so it does not rest on any window whose
cleanliness is in doubt. Nothing else on this page does either.

One further property is load-independent by construction: a leg that reads
**above** its cohort with **zero** falling steps is allocation performed by this
renderer's own heap. No other process can add bytes to it, and the legs are
sampled in-page with no CDP round trip between them.

## Every run's positive control passed

A run whose positive control fails contributes no data, so the controls are
reported first and per run, never pooled. **All seven runs read the same, and
all seven passed.** Reproduction, from `implementation/`:

```
P0_ALLOC_PLAN=controls P0_ALLOC_CELLS=6 node core/test/re_frame/bench/p0_run.cjs --only alloc
P0_ALLOC_PLAN=floor P0_ALLOC_CELLS=<1|6|24> P0_ALLOC_WRITE=<page|all> node core/test/re_frame/bench/p0_run.cjs --only alloc
```

| run | plan | write | B | direct (B/double) | differential (B/double) | verdict | unverified |
|---|---|---|---|---|---|---|---|
| V3 controls-only | `controls` | — | — | 8.08 | 8.00 | OK | 0 of 18 |
| V1 floor | `floor` | `page` | 4 | 8.08 | 8.00 | OK | 0 of 12 |
| V1 floor | `floor` | `all` | 4 | 8.08 | 8.00 | OK | 0 of 12 |
| V1 floor | `floor` | `page` | 24 | 8.08 | 8.00 | OK | 0 of 12 |
| V1 floor | `floor` | `all` | 24 | 8.08 | 8.00 | OK | 0 of 12 |
| V1 floor | `floor` | `page` | 96 | 8.08 | 8.00 | OK | 0 of 12 |
| V1 floor | `floor` | `all` | 96 | 8.08 | 8.00 | OK | 0 of 12 |

The differential — D = 1,000 less D = 400, which cancels the sampler's own
footprint and every other constant — reads **8.00 B/double against a predicted
8** in every run, and the direct reading 8.08. Transient garbage is visible to
this counter, which is the one claim the whole row rests on.

## The prime works, and this window replicates it

`rf2-oiy1`'s repair drives `ALLOC_WRITES + 1` and treats the first work unit as
a prime — sampled, reported, excluded from the cohort and from every published
figure. Two independent checks say it did what it claimed:

| quantity | published, pre-prime (336 windows) | measured here (72 windows) |
|---|---|---|
| first-leg excess over the cohort median | median 6,966 B (p25 6,856, p75 8,056) | median **6,864 B** (p25 6,800, p75 6,888) |
| controls' own first-leg excess | — | **0 B in all 18 windows** |

Both columns are untrimmed over their whole arm population, so the quartiles
compare like for like. This window's tails are wider than the published ones at
the extremes (min −51,684 B, max 360,500 B against 3,441 B and 28,448 B), which
is the loaded box showing up exactly where the section above says it does — in
four windows, none of which enters any figure below.

The controls reading exactly zero is the bead's diagnosis made visible: the
control work unit is a dropped `.slice()` and has no first-write re-allocation
in it, so priming it changes no control figure. That is why the controls'
numbers below are directly comparable with the published pre-prime ones.

And the term the prime removes was decisive. The floor arm refused **6 of 6** at
every page under both writes before it; after it, **41 of 72** windows certify
at the 0.25 placeholder.

## The controls, re-measured — 0.99%, reproduced

`P0_ALLOC_PLAN=controls`, both D values, six rounds, six measured writes.

| population | worst relative leg deviation | windows exactly 0.00% |
|---|---|---|
| control windows, D = 1,000 (6) | **0.99%** | 5 of 6 |
| control windows, D = 400 (6) | 0.00% | 6 of 6 |
| idle windows (6) | 0.00% | 6 of 6 |

Identical to the published figure. The single non-zero window reads legs
`[8144, 8080, 8064, 8064, 8064, 8064]`; the other 17 are byte-identical across
all six legs. **The controls' calibration input is not in question and never
was** — what this window tests is whether it may govern the arms.

## The arms, post-prime — the spread did not collapse

Floor arm only, `P0_ALLOC_PLAN=floor`, at V1's three pages under both writes;
72 arm windows in all. Cleanliness is stated on the **falls** gate — an observed
collection — which is the driver's own untouched criterion and is independent of
τ. Selecting on τ would be selecting on the gate being calibrated.

| population | n | worst relative leg deviation | median |
|---|---|---|---|
| control windows (from the run above) | 18 | 0.99% | 0.00% |
| arm windows, no observed collection | 47 | 1,835.79% | 2.90% |
| — of those, excursions under 100% | 42 | **38.91%** | **2.69%** |
| arm windows with an observed collection | 25 | not read — the falls gate refuses them | — |

Distribution of the 42, by band: **19 windows at ≤ 0.20%**, 22 windows between
**2.66% and 38.91%**, 1 window at −11.07%. So a little under half the arms'
clean windows are as tight as the controls, and rather more than half are not.

### The bimodality is round-indexed, in all six runs

The two modes are not scattered. Split the collection-free windows by round:

| round | n (collection-free) | worst relative leg deviation, range |
|---|---|---|
| 0 | 5 | 16.67% – 1,407.95% |
| 1 | 6 | 0.07% – 1,835.79% |
| 2 | 11 | 2.66% – 20.37% — **none below 2.66%** |
| 3 | 11 | 0.00% – 0.19% — **all 11 at or below 0.19%** |
| 4 | 7 | 0.06% – 1,505.41% |
| 5 | 7 | 0.00% – 13.29% |

Rounds 2 and 3 are the clean statement, and they hold **in each of the six runs
separately** — six independent browser launches, each with both round-2 windows
wide and both round-3 windows tight. Machine load does not align itself to a
round index across six separate processes, so this is a property of the arm work
unit and its round schedule rather than of the box.

**The mechanism is not identified here and this page does not guess at one.**
Identifying it needs an instrument this window may not build.

## Why no τ follows, on either calibration population

V3's rule is *a stated multiple of the observed worst deviation, rounded up to a
round number; a small integer is the expected answer.*

| calibration population | observed worst | τ at a 2× multiple | what it does |
|---|---|---|---|
| the controls (V3 as specified) | 0.99% | ≈ 0.02 – 0.05 | 5× to 12× **tighter** than the placeholder |
| the arms' collection-free windows | 1,835.79% | no τ below 1 exists | — |
| the same, excursions discarded | 38.91% | ≈ 0.8 | 2τ certificate becomes vacuous |
| the arms at round 3 only | 0.19% | ≈ 0.004 | — |
| the arms at round 2 only | 20.37% | ≈ 0.41 | — |

Every row is a defensible reading of the same run, and they span **two orders of
magnitude**. That is what "not one population" costs: τ is **not identified** by
the arms' own data, and no multiple applied to any single figure in that column
is more honest than the others.

Branch (a) — *calibrate on the arms' own clean windows* — needed exactly one
thing the bead named in advance: *"a rule for which arm windows are corroborated
clean, when the arms have no 8 B/double prediction to be corroborated against."*
This window measured that the missing rule is not a formality. The falls gate is
the only independent cleanliness criterion available, and applying it leaves a
population whose spread depends on the round schedule.

And the two escape routes both fail on their own terms. Taken whole, the arms'
clean windows **admit no τ below 1**, which is V3's escape clause in its
original orientation: *"if the observed spread is so wide that no τ below 1
leaves margin, the witness is not usable as specified — report that rather than
picking a τ that certifies everything."* Reaching 38.91% instead needs the
discard rule that does not exist, and the τ ≈ 0.8 it yields makes the
instrument's own guarantee — *an admitted window under-reads its true allocation
by at most 2τ* — a bound of 160%, which bounds nothing.

## The verdict: branch (c), on stronger ground than when it was written

**τ stays a declared placeholder at 0.25.** The constant is unchanged, its
placeholder marker is unchanged, and the structural pin is unchanged.

What has changed is the *reason*, and that is the point of recording it. The
bead offered (c) on the ground that *"no honest calibration exists while the
first-leg term stands."* The first-leg term no longer stands, and there is still
no honest calibration. So (c) is no longer a holding position pending a repair —
the repair landed, and the objection survived it.

The bead's own objection to (c) was that *"a placeholder that never resolves is
a gate nobody can audit."* That objection is answered, though not in the way it
expected: what is now auditable is the **reason**. The controls' work unit is a
single fixed allocation and cannot vary; the arms' work unit is a dispatch-sync
plus a substrate drain and does, systematically and by round. A tolerance
calibrated on the first cannot govern the second, and the second does not
identify one for itself. That is a statement a maintainer can check against this
page's numbers and overturn with a better one.

One correction the record owes: `p0_run.cjs`'s header used to call 0.25 a
*conservative* stand-in, reasoning that τ is a tolerance so a smaller τ refuses
more, and that 0.25 *"refuses strictly more than any larger value V3 might land
on."* That assumed V3 would land above 0.25. It landed **below** — near 0.02 –
0.05 — so 0.25 is the more permissive value, not the conservative one. The
header has been corrected to say so.

## What was refused, and what was not done

- **No gate, band or threshold was widened, narrowed or touched.**
  `ALLOC_LEG_TOLERANCE` stays 0.25, `ALLOC_MIN_WRITES` stays 6,
  `ALLOC_FALL_THRESHOLD_B` stays 600,000, `ALLOC_CONTROL_SLACK` stays 0.75.
- **τ was not pinned**, and the constant was not moved in either direction.
- **No instrument change was made, and none was attempted mid-window.** No new
  estimator, no extra rung, no fourth warm-up, no change to the leg accounting.
  The round-indexed structure is filed, not chased.
- **Only criterion 5's permitted witnesses were run** — V3 and V1's floor shape.
  No `full` plan, no ladder rung, no V2, no re-run of `rf2-2rtt6.138`.
- **Nothing from V1's own criterion is reported here.** This window borrowed
  V1's *configuration* to obtain arm windows and read one quantity out of it.
  `F`, `F₀`, `F_old`, per-cell and per-boundary costs, and V1's verdict are not
  restated, re-derived or moved; they stay where 2026-08-14 left them.
- **The refused windows' numbers are reported and labelled at every appearance**,
  and no figure from a collection-carrying window enters any conclusion.
- **No bound is claimed from an impossible reading.** The six-figure excursions
  bound nothing; they are excluded and named.

## What this leaves open

1. **What makes the arms' leg dispersion depend on the round.** Reproduced in
   six of six runs and unexplained. It is the first thing to identify, because
   until it is, "a corroborated-clean arm window" has no definition.
2. **Whether the leg witness can certify an arm at all.** The 2026-08-14 page
   left this open on the ground that the floor could not pass τ. It can now, in
   41 of 72 windows — but at a placeholder no calibration supports, over a
   population that is not homogeneous. The question is unchanged in substance
   and is about the witness's design, not about τ's value.
3. **How much of the excursion and collection rate is the loaded box.** A
   repeat of exactly these seven runs on a quiet box would separate it. This
   window's conclusion does not depend on the answer, but the instrument's own
   refusal rate is not characterised until someone takes it.

These are the operator's, and this page takes none of them.
