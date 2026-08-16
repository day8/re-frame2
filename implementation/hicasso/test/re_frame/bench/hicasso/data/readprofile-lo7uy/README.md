# `read_profile` phase-A/B transcripts — rf2-lo7uy's three-null window

Raw driver output from the three runs of `rf2-lo7uy`'s measurement window
(2026-08-17), one file per run, in run order. This is the first window taken on
the instrument with **three** negative controls at three different sweep slots,
the layout PR #8384 built so that the `+0.022 ms/commit` offset `rf2-3l6hf`
reported could have its CAUSE read rather than only its size.

**THE HEADLINE IS A NEGATIVE: THE OFFSET DID NOT REPRODUCE.** On the same arm
pair `rf2-3l6hf` measured it on — `c-local` at slot 1 against `c-null` at slot
2, both arms at the slots that window published them at — this series read
`+0.0109 / −0.0188 / +0.0313` ms/commit. Sign-changing, range `0.0501` (32 grid
steps). The predecessor read `+0.0234 / +0.0219 / +0.0234`, range `0.0015` — one
grid step. **The stable offset this bead exists to explain was not present in
this series to be diagnosed**, so its cause is still not established and
`rf2-07rnj` stays blocked.

What the window did establish is narrower and is set out under "The one
positive finding" below.

## What produced them

| | |
|---|---|
| **Base commit** | `3be82b04bc` (`origin/main` at the time of the window) |
| **Instrument blob** | `c220a8c23c44ca6e19f9cab90528d932f271a784` (`read_profile_app.cljs`, identical before and after the window — the working tree was clean at both ends and no tracked file was edited until after run 3) |
| **Window shape** | 32 frames/window, 8 × (2 + 8) = 64 kept samples per arm, grid 0.0015625 ms/commit — **unchanged** from `rf2-3l6hf` and `rf2-07rnj` |
| **Arms** | 11. Two more than the predecessor window: `c-null-twin` and `c-null-curve` were appended **before** the window opened, by PR #8384, which took no measurement |
| **Build** | `:advanced`, `goog.DEBUG false` |
| **Runtime** | HeadlessChrome 147.0.7727.15 (Playwright) |
| **Outcome** | `exit 0` on all three; both arm-order guards reportable on each; phase-A positive control passing on each; phase-B residue gate never firing |

**Eleven arms is a new series.** `n` is an input to `lane/slot-order`, so every
arm's position footprint differs from the nine-arm layout even where its slot
index does not. Absolutes here are **not arm-by-arm comparable** with
`readprofile-3l6hf/` or `readprofile-07rnj/`, and nothing below reads them as if
they were.

**The run count was fixed at three before run 1 started**, so no stopping rule
could end the series on a convenient answer. Three is what the bead's
2026-08-16T04:55:51Z comment specified.

## The estimator these files report, named as computed

Each `p50` in the phase-B arm table is a **pooled median over the 64 kept
samples of that arm**, each sample first divided by the 32-frame window; 64
being even, that median is the mean of the 32nd and 33rd **order statistics**.
That is the standard median — it is **not** an arithmetic mean of the samples,
and it is **not** a mean of per-round medians. Verified at source:
`lane.cljs`'s `summarise` sorts and indexes.

Each **run-level delta** is the difference of two such pooled medians.

Where a figure below is an **arithmetic mean of the three run-level deltas**, it
says so in those words. Where it is a **within-round p50 delta** — the median of
one round's 8 kept samples, differenced against `c-local`'s, as the rig records
under `:read-profile-commit-per-round-deltas` — it says that instead.

## The layout, and why it can discriminate

`rounds-async!` hands `lane/slot-order` the SAMPLE index, not the round, so the
schedule repeats identically every round and an arm's multiset of sweep
positions is fixed for the whole run. The transcripts carry it under
`:read-profile-slot-plan`. At `n = 11` and this window's `2 + 8` sampling:

| slot | arm | kept-sample positions | mean | positional variance |
|---|---|---|---|---|
| 1 | `c-local` | `[1 3 4 5 6 7 8 10]` | 5.500 | 7.25 |
| 2 | `c-null` | `[0 0 2 4 5 6 7 9]` | 4.125 | 9.359 |
| 9 | `c-null-twin` | `[1 3 4 5 6 7 8 10]` | 5.500 | 7.25 |
| 10 | `c-null-curve` | `[2 3 4 5 6 7 8 9]` | 5.500 | 5.25 |

All four arms are `C-FULL` built from the same `mk-local`, so each of the three
`c-local − null` deltas has a true cost of **exactly zero by construction** and
the arms differ in nothing but their slot.

`c-null-twin` occupies `c-local`'s footprint exactly. `c-null-curve` shares its
mean position on a different footprint. `c-null` is displaced in both.

## The three nulls

Run-level deltas (`c-local −` the arm), in ms/commit, straight off each file's
own `PHASE B DELTAS` block:

| null | slot | run 1 | run 2 | run 3 | arith. mean of the three | range | range in grid steps |
|---|---|---|---|---|---|---|---|
| `c-null` (displaced) | 2 | +0.0109 | −0.0188 | +0.0313 | +0.0078 | 0.0501 | 32 |
| **`c-null-twin`** (same footprint) | 9 | **−0.0031** | **+0.0047** | **−0.0047** | **−0.0010** | **0.0094** | **6** |
| `c-null-curve` (same mean) | 10 | −0.0281 | −0.0250 | +0.0203 | −0.0109 | 0.0484 | 31 |

For scale, the same three columns for the ablation terms, which are **not**
nulls and whose true costs are unknown and positive:

| term (`c-local −` the arm) | run 1 | run 2 | run 3 | range |
|---|---|---|---|---|
| reaction build + cache insert (`c-nosub`) | +0.3891 | +0.3859 | +0.4344 | 0.0485 |
| watch wiring (`c-nowatch`) | +0.0344 | +0.0156 | +0.0641 | 0.0485 |
| cell-map insert (`c-nomap`) | +0.0062 | +0.0375 | +0.0500 | 0.0438 |
| activation capture (`c-noactivate`) | +0.0359 | −0.0109 | +0.0406 | 0.0515 |
| reader membership (`c-noreaders`) | −0.0219 | −0.0125 | +0.0141 | 0.0360 |

## The one positive finding

**The twin is the only one of the eight deltas whose run-level range is small**
— `0.0094` (6 grid steps) against `0.0360`–`0.0515` (23–33 steps) for the other
seven, which include the two other nulls whose true cost is also exactly zero.

The raw pooled medians show the mechanism directly. Across the three runs:

| arm | footprint | run 1 | run 2 | run 3 | its own range |
|---|---|---|---|---|---|
| `c-local` | `[1 3 4 5 6 7 8 10]` | 0.5922 | 0.5875 | 0.6406 | 0.0531 |
| `c-null-twin` | `[1 3 4 5 6 7 8 10]` | 0.5953 | 0.5828 | 0.6453 | 0.0625 |
| `c-null` | `[0 0 2 4 5 6 7 9]` | 0.5812 | 0.6063 | 0.6094 | 0.0282 |
| `c-null-curve` | `[2 3 4 5 6 7 8 9]` | 0.6203 | 0.6125 | 0.6203 | 0.0078 |

Between run 2 and run 3 `c-local` rose `+0.0531` and the twin rose `+0.0625`,
while `c-null` moved `+0.0031` and the curve `+0.0078`. The two arms sharing a
position footprint moved together; the two that do not share it did not.

That is a position-linked component in the instrument's **run-level** error —
which is what the twin was placed to detect, and it detected something.

**Three caveats travel with that finding and none of them is optional.**

1. **It rests on ONE transition.** Three runs give two run-to-run transitions.
   The co-movement is clean in run 2 → run 3 and weak in run 1 → run 2
   (`c-local` −0.0047, twin −0.0125, `c-null` +0.0251, curve −0.0078).
2. **There is no second null pair to corroborate it.** Of the five distinct
   footprints in this layout, `c-local`'s is shared only with the twin, and the
   other footprint-sharing pairs (`c-null`/`b-build`, `commit`/`c-null-curve`,
   `c-noactivate`/`c-nomap`, `c-nowatch`/`c-noreaders`) each pair arms doing
   **different work**, so none of them is a second null.
3. **The effect is absent at round granularity.** Over the 24 rounds of the
   window (3 runs × 8), the standard deviations of the within-round p50 deltas
   are `0.0415` (twin), `0.0447` (`c-null`) and `0.0460` (curve) — ratios of
   1.08 and 1.11 to the twin. Position control removes essentially nothing from
   the round-to-round scatter. Whatever the twin cancels lives in the run-level
   term only.

## What this window found out about its own rig, and did not act on

**`c-null-curve` cannot test linearity under a median estimator, and the rig's
docstring says it can.** The claim is that an arm sharing `c-local`'s MEAN
position cancels a linear within-sweep drift. That is exact for an arithmetic
mean and only first-order true for a median, which is what `lane/summarise`
actually computes.

Write a kept sample as `Y + g(p)`, `Y` the noise and `g` the drift. An arm's
pooled sample is the mixture `(1/8) Σ_p F(x − g(p))` over its footprint. When
two arms share a footprint **exactly** the two mixtures are identical, so their
medians are identical for **any** `g` — the twin's argument survives the
estimator. When they share only the mean, the mixtures agree to first order in
`g` and differ at second order by a term in the **variance** of the position
multiset — and those variances are not equal here: 7.25 for `c-local`, 5.25 for
the curve.

So a non-zero curve reading does not establish curvature, and **this window
therefore does not conclude anything about whether the drift is linear.**

This is recorded, not repaired. `n` feeds `lane/slot-order`, so touching the
roster moves every published footprint and starts a third series — the same
rule that made PR #8384 a separate, measurement-free edit half.

## The box

`\System\Processor Queue Length` was sampled **on its own** and **outside every
measured run** — five samples at 1 s, immediately before the window and between
every pair of runs — on a 24-core host. All times +10:00.

| bracket | time | PQL | `% Processor Utility` |
|---|---|---|---|
| before run 1 | 00:42:46–50 | `0,0,0,0,0` | not sampled |
| after run 1 | 00:45:40–44 | `0,0,0,0,1` | 14.7, 12.2, 18.9, 15.2, 12.2 |
| after run 2 | 00:47:05–09 | `0,0,0,0,0` | 12.2, 11.4, 9.3, 10.9, 9.6 |
| after run 3 | 00:48:32–36 | `0,0,0,0,0` | 9.7, 17.2, 8.7, 32.3, 27.4 |

Two counters rather than one, because a headline utilisation figure can be
wrong by a wide margin; the queue length is the one that says whether anything
is actually waiting for a core, and it read 0 in 19 of 20 samples. The single
`1` and the 32.3% utility reading were both taken **between** runs, and the
utility figures include the sampler itself.

**Nothing was sampled inside a run**, deliberately — a counter read inside a
measured window makes the sampler part of the measurement. **The quietness
claim here is a bracketing claim and nothing stronger.** No open PRs and no
other worker were in flight when the window was taken. Each run's own `run.cjs`
rebuilds the `:advanced` bundle before measuring, and that compile saturates
this box; the bracket readings sit outside those compiles.

## What was NOT concluded

- **NOT** that the offset is fixed, gone, or was an artefact of nine arms.
  `n` changed, so the two series are not comparable arm by arm. What is
  established is only that a `+0.022` offset stable to one grid step is **not a
  reproducible property of this instrument across a change of layout**.
- **NOT** that candidate (a) is the cause of that offset. The offset was not
  observed here, so nothing here can be its cause.
- **NOT** that the drift is linear or non-linear — see the estimator caveat
  above.
- **NOT** any separation of (a) from (b). The twin cancels **any** function of
  sweep position whatever its physical cause, so a residual arm-position effect
  and a within-sweep thermal or cache drift fall on the same side of it. This
  rig cannot split them.
- **NOT** any bound on any term. No null was subtracted from any term, and no
  null spread is quoted as an upper or lower bound on anything. Both
  prohibitions carried from `rf2-3l6hf` stand: `c-null` calibrates slot 1
  against slot 2 while reader membership differences slot 1 against slot 6, and
  a null is a measured property of the ESTIMATOR, not of a cost.
- **NOT** any claim about within-run quietness beyond the bracketing above.
- **NOT** a restatement of `rf2-07rnj`'s or `§1`'s published terms. The five
  ablation figures in the second table are this eleven-arm series' own and
  supersede nothing.

## Reproduction

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main \
HICASSO_OUT_DIR=out/hicasso-readprof \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

## The one redaction, stated so nobody reads these as doctored

Each file is the driver's output verbatim **except for exactly one line**, the
`shadow-cljs - config:` banner, whose absolute path is replaced by `<worktree>`
and marked inline as redacted. Nothing else was touched — no figure, no guard
verdict, no exit line, and the line counts are unchanged (260 / 259 / 259).

The path was the window's proof that every build read the worker's own worktree
rather than the mayor checkout, which is why the line is kept rather than
deleted. It cannot be committed as it stood: the portability gate
(`scripts/check-no-hardcoded-paths.sh`) refuses any tracked file carrying a
personal home path, correctly and with no escape hatch. Re-running the command
above prints the same banner with the reader's own checkout in it.
