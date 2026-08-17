# The leg dispersion is in the dispatch site — the by-site allocation window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-ojehu`, the measurement half of
`rf2-rs8q6`, whose edit half landed the by-site instrument in PR #8380. Measured
2026-08-16 22:05–22:12 AUSEST, branch `worker/bysite-ojehu` off `63a3d44086`.

Runtime, beside every figure below: Chromium **147.0.7727.15** via Playwright,
shadow-cljs `release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `--enable-precise-memory-info`, `:init-fn
re-frame.bench.p0-app/-main`. The counter is in-page
`performance.memory.usedJSHeapSize`, sampled with no CDP round trip between
readings.

**No slope is published on this page and none was fitted**; the `floor` plan
carries no rungs. This page reports where a window's leg dispersion **sits**, and
it restates no figure from [the arms' spread does not
collapse](the-arms-spread-does-not-collapse.md) except where it is replicating
one, which is labelled as replication each time.

**τ was not moved, in either direction.** `ALLOC_LEG_TOLERANCE` stays 0.25 with
its uncalibrated marker intact, and no gate, band or threshold on this rig was
widened, narrowed or touched. Neither was the instrument: this window ran the
rig PR #8380 shipped and changed nothing in it.

## The answer, first

**The round-indexed leg dispersion is in the `dispatch` site — the
`dispatch-sync` through the event pipeline and the signal graph — and not in
React's commit.** Over the 41 windows that survive every cleanliness criterion
this page applies, `siteWitness.dominant` reads `dispatch` in **37**, `drain` in
**0**, and `null` in **4** (windows whose legs are byte-identical, so there is no
dispersion to place).

- **The mode has the resolution to say so.** The idle control prices its own
  extra counter read at **16 B per leg** — `dispatch` 16 B and `drain` 16 B,
  range [16 – 16] in all **36** idle windows across the six runs. Against the
  recurring excesses' **2,476 B** median that is **0.65%**, and against
  `rf2-rs8q6`'s published 2,640 B median it is **0.61%**. The bead's stop
  condition — *"if the idle figure is comparable to the arm excesses the
  resolution is insufficient and that is the finding"* — **does not fire.**
- **`siteWitness.dominant` is constant across the round sequence**, and in
  particular it is the **same site** at round 2 (wide) and round 3 (tight): 7 of
  7 `dispatch` at round 2, 10 of 12 `dispatch` plus 2 `null` at round 3. So the
  bimodality `rf2-rs8q6` found is a change of **magnitude within one site**, not
  a change of site.
- **`siteWitness.sameSite` comes back `agree`** — 37 of 37 windows that had a
  dispersion to place, with **no disagreement at all** in the clean population.
  On the bead's own rule that **NARROWS the same-term hypothesis and does not
  settle it**, and it sends the reader to the magnitudes, which are below.
- **The magnitudes do not match.** The prime excess is a near-constant
  **6,864 B** (p25 6,800, p75 6,880 — an interquartile width of 80 B, **1.17%**
  of its median; whole range 6,526 – 6,940 B, 6.0% of the median). The recurring
  excesses run **500 – 6,888 B, a 13.8× range**, median **2,476 B** = **36.1%**
  of the prime's.
- **The index is the window's ORDINAL, at a granularity finer than the round.**
  The two arm windows inside one round read 4.89× apart at the median, and the
  same arm reads 9.97× apart at adjacent ordinals.
- **The extra sampler read perturbs nothing.** The stride-2/stride-3 pair reads
  a **16 B** per-leg difference at the idle control and a **16 B median**
  difference at the arms — the mode's own falsifiable claim, met.
- **The quiet box changed neither the collection rate nor the excursion rate**,
  which closes `rf2-e9wr`'s open question 3.

## The box, and exactly what is claimed about it

The fleet was drained for this window, so this is the quiet-box counterpart to
`rf2-e9wr`'s deliberately loaded one. **What is claimed is bracketing and
nothing more.**

`\System\Processor Queue Length` — the counter that says whether anything is
actually waiting for a core — was read **on its own, between runs, never inside
one**, in eight brackets of 4 to 6 one-second samples: **34 of 37 samples read
0** and three read 1, on a 24-logical-core box.
`\Processor(_Total)\% Processor Time` over the same samples ran **4.0% – 25.5%**,
which is consistent with the queue-length reading rather than in tension with
it. **Nothing is claimed about within-run quietness beyond that bracketing**, and
the box carried this agent's own light tool activity — file reads, a `git log` —
between and occasionally during runs.

**The window's conclusions do not rest on the box being quiet**, and the section
on the excursion rate below is the evidence for that rather than the assertion of
it.

## Every run's positive control passed

A run whose positive control fails contributes no data, so the controls are
reported first and per run, never pooled. **All seven runs passed.**
Reproduction, from `implementation/`:

```
P0_ALLOC_BY_SITE=1 P0_ALLOC_PLAN=floor P0_ALLOC_CELLS=<1|6|24> P0_ALLOC_WRITE=<page|all> \
  node core/test/re_frame/bench/p0_run.cjs --only alloc

# the stride-2 partner of the pair, the shipped stride, mode off
P0_ALLOC_PLAN=floor P0_ALLOC_CELLS=6 P0_ALLOC_WRITE=page \
  node core/test/re_frame/bench/p0_run.cjs --only alloc
```

`P0_ROOTS` is left at its default 4, `P0_ALLOC_ROUNDS` at 6 and `P0_ALLOC_WARMUPS`
at 3 — `rf2-e9wr`'s own configuration, borrowed from V1, so the two windows line
up window for window.

| run | stride | write | B | direct (B/double) | differential (B/double) | verdict | unverified |
|---|---|---|---|---|---|---|---|
| `s3-c1-page` | 3 | `page` | 4 | 8.10 | 8.00 | OK | 0 of 12 |
| `s3-c1-all` | 3 | `all` | 4 | 8.10 | 8.00 | OK | 0 of 12 |
| `s3-c6-page` | 3 | `page` | 24 | 8.10 | 8.00 | OK | 0 of 12 |
| `s3-c6-all` | 3 | `all` | 24 | 8.10 | 8.00 | OK | 0 of 12 |
| `s3-c24-page` | 3 | `page` | 96 | 8.10 | 8.00 | OK | 0 of 12 |
| `s3-c24-all` | 3 | `all` | 96 | 8.10 | 8.00 | OK | 0 of 12 |
| `s2-c6-page` | 2 | `page` | 24 | 8.08 | 8.00 | OK | 0 of 12 |

The **differential** — D = 1,000 less D = 400, which cancels the sampler's own
footprint and every other constant — reads **8.0044 B/double at both strides, to
every digit the driver records**, against a predicted 8. The **direct** reading
moves 8.0827 → 8.0987, and that 0.016 B/double is arithmetic rather than
coincidence: 0.016 × 1,000 doubles × 6 writes = **96 B = 6 legs × 16 B**, which
is the extra read the mode takes. The differential is invariant because the
constant cancels; the direct reading moves by exactly the constant. Every run
reported **0 unverified of 12** on the mount and warm-write read-backs.

## The idle control, read first — the mode's resolution is sufficient

The bead requires this section before any attribution, because the mode's extra
counter read sits inside every arm site figure and the idle control is driven at
the same stride precisely to price it.

| control | leg median at stride 2 | leg median at stride 3 | Δ | by site at stride 3 |
|---|---|---|---|---|
| `idle` (nothing at all) | 16 B | 32 B | **+16 B** | `dispatch` 16 B, `drain` 16 B |
| `ctl1` (D = 1,000 doubles) | 8,064 B | 8,080 B | **+16 B** | `dispatch` 16 B, `drain` 8,064 B |
| `ctl2` (D = 400 doubles) | 3,264 B | 3,280 B | **+16 B** | `dispatch` 16 B, `drain` 3,264 B |

Every leg of every control moves by exactly 16 B, not merely the medians: `ctl1`
reads `[8144, 8080, 8064, 8064, 8064, 8064]` at stride 2 and
`[8160, 8096, 8080, 8080, 8080, 8080]` at stride 3, and the gaps are unchanged at
16 B. Across the six by-site runs the idle window's two site figures read
**16 B and 16 B in all 36 idle windows**, with no spread at all.

**16 B against a 2,476 B recurring excess is 0.65%, and against `rf2-rs8q6`'s
published 2,640 B median it is 0.61%.** The two quantities are not comparable and
the mode's resolution is sufficient for the question. Had the idle figure landed
near the arm excesses, the bead's instruction was to report that and stop; it did
not.

## The mechanism: `dispatch`, and the site does not change with the round

`siteWitness.dominant` is the site holding the **larger total absolute deviation
from its own per-site median, summed across the window's six measured legs**. It
is not the worst leg's site, though on this data the two agree; the summary's
column header spells it `worst-dev site`, which understates what the estimator
computes. **[Amended 2026-08-17, `rf2-stals`: the heading now reads `dominant
site (total |dev|)`. Nothing computed changed, so every figure below stands as
measured — only the column's label does.]**

Three populations are reported, narrowing left to right, because a reader is
entitled to see what the exclusions cost:

| population | n | `dominant` = dispatch | = drain | = null |
|---|---|---|---|---|
| all arm windows | 72 | 64 | 4 | 4 |
| collection-free (`falls` = 0) | 49 | 44 | 1 | 4 |
| **collection-free, no excursion, no intra-leg reclamation** | **41** | **37** | **0** | **4** |

Every one of the four `drain`-dominant windows in the whole measurement carries
either an observed collection or a six-figure excursion or both. **In the clean
population `drain` never wins, and `null` means the window's legs are
byte-identical rather than that the instrument declined to answer.**

The bead asks specifically whether the site differs between round 2 — wide, none
below 2.66% on `rf2-e9wr`'s data — and round 3 — tight, all at or below 0.19%.
**It does not.**

| round | n (clean) | worst leg deviation, range | `dominant` dispatch / drain / null |
|---|---|---|---|
| 0 | 0 | — (every round-0 window is excluded; see below) | — |
| 1 | 4 | 25.47% – 35.74% | 4 / 0 / 0 |
| 2 | 7 | **2.59% – 22.99%** — none below 2.59% | 7 / 0 / 0 |
| 3 | 12 | −0.20% – 13.44% — **10 of 12 at or below 0.20%** | 10 / 0 / 2 |
| 4 | 6 | −0.19% – 17.42% | 6 / 0 / 0 |
| 5 | 12 | −0.19% – 13.32% | 10 / 0 / 2 |

Round 2 replicates `rf2-e9wr` almost exactly — *none below 2.66%* there, none
below 2.59% here. **Round 3 replicates only in part**: 10 of the 12 clean
round-3 windows sit at or below 0.20%, and two do not, reading 12.85% and
13.44%. Both exceptions are `write=all` runs at the same window ordinal, and
that is taken up below rather than smoothed over.

### The floor arm's drain does 80 B, and that is the honest limit of this finding

`drain`'s median is **exactly 80 B in all 72 arm windows** — at B = 4, B = 24 and
B = 96, under both writes, on both substrates, with no other value occurring.
`dispatch`'s median is 19,192 B [17,492 – 67,540]. So the commit is **0.42% of a
leg** and the whole of the arm's per-write allocation is in the dispatch half.
Of the 41 clean windows' worst legs, **`drain` contributes exactly 0 B in 39**;
the two non-zero contributions are +820 B and +440 B. (Widen to the 43 windows
that pass the falls and excursion tests but not the intra-leg one, and a third
appears at −285,372 B — the subject of the next section.)

**The limit this places on the finding is stated rather than left for a reader to
notice.** The `floor` arm mounts no subscription, so its `flushSync` has nothing
to re-render and its commit is a near-no-op by construction. A null result at
`drain` on this arm is therefore weaker evidence than the counts alone suggest:
it says the dispersion is not in React's commit **on an arm whose commit does
almost nothing**. What the data does establish without that caveat is that the
arm's entire per-write allocation, and all of its dispersion, is in the
framework's write rather than the substrate's commit — which is the distinction
`rf2-rs8q6` filed the mode to make, on the arm its observation was made on.
Nothing here generalises to a subscribing arm, and this window did not measure
one.

## An intra-leg collection the falls gate cannot see

The by-site stream shows something the shipped stride cannot, and it is an
instrument finding rather than a result: **a reclamation can happen inside a
single leg, bracketed by a larger allocation, and turn no step negative on the
collapsed stream.**

24 of the 72 arm windows carry at least one negative site step. Twenty-one of
them are already refused by the falls gate. **Three have `falls` = 0, so the
falls gate saw nothing at all, and two of those three also certified at τ**:

| window | leg | `dispatch` | `drain` | leg total | window verdict |
|---|---|---|---|---|---|
| `s3-c6-page` round 0 `uix-subs` | 3 | +199,980 B | **−178,496 B** | +21,484 B | falls 0, **certified** |
| `s3-c6-page` round 2 `uix-subs` | 4 | +305,392 B | **−285,292 B** | +20,100 B | falls 0, **certified** |
| `s3-c24-page` round 0 `reagent-subs` | 2 | +279,024 B | −259,996 B | +19,028 B | falls 0, refused on another leg |

The falls gate is defined on the collapsed stride-2 stream and is structurally
blind to this: the leg's net step is positive, so nothing falls. The leg
tolerance is the gate written for that blind side, and it does not catch these
two either, because the leg's **net** is within τ of the cohort median. **Both
windows are excluded from every figure on this page** — that is what the
41-window population is — and the finding is **filed, not fixed**: the fence on
this bead is that a measurement window does not amend the instrument it is
measuring on.

## The same-term hypothesis: `agree`, which narrows and does not settle

`rf2-rs8q6` named one hypothesis to exclude first — that the recurring excesses
are the **same term** as the prime's, recurring, rather than a distinct one — and
`rf2-ojehu` fixed in advance what each reading of `siteWitness.sameSite` would
mean. **The reading is `agree`.**

| population | agree | disagree | null |
|---|---|---|---|
| all arm windows | 63 | 5 | 4 |
| collection-free | 44 | 1 | 4 |
| **clean (41)** | **37** | **0** | **4** |

Every one of the five disagreements in the whole measurement is a
collection-carrying or six-figure-excursion window. **`primeSite` reads
`dispatch` in 41 of 41 clean windows**, without a single exception anywhere in
the clean population.

On the bead's own asymmetry — *disagreement settles it, agreement narrows it,
because a site is two statements wide* — this is **NARROWED, NOT SETTLED**, and
the bead sends the reader to the magnitudes next. Here they are:

| quantity | n | median | spread |
|---|---|---|---|
| prime excess | 41 | **6,864 B** | p25 6,800, p75 6,880 — IQR 80 B = **1.17%** of median; whole range 6,526 – 6,940 B = 6.0% |
| recurring excess (positive worst-leg deviations) | 23 | **2,476 B** | 500 – 6,888 B — a **13.8×** range |

Both columns replicate their published counterparts closely, which is the check
that this is the same phenomenon and not a different one:

| quantity | published | measured here |
|---|---|---|
| prime excess, median | 6,864 B (`rf2-e9wr`, 72 windows) | **6,864 B** (41 clean windows) |
| prime excess, p25 / p75 | 6,800 / 6,888 B | 6,800 / 6,880 B |
| recurring excess, median | 2,640 B (`rf2-rs8q6`, 20 windows) | **2,476 B** (23 windows) |
| recurring excess, range | +476 to +7,456 B, 15.7× | +500 to +6,888 B, **13.8×** |
| recurring as a share of prime, at the median | 38% | **36.1%** |

**What the two together license, and no more.** The two terms share a site, and
the prime's magnitude is reproduced to the byte at the median while its whole
range is 6.0% wide. The recurring term spans 13.8× and — the next section — its
value is a reproducible function of where the window sits in the run. So the
same-term reading survives only in the form *"the prime's term recurs at a
position-dependent fraction of itself"*, which is a strictly stronger claim than
the one the bead put up, and this window does not establish it. **The hypothesis
is not excluded and it is not confirmed; what has changed is that it now costs
more to hold.**

## The index is the window ORDINAL, and the tick pair is a relabelling of it

`rf2-ojehu` asks for the excess plotted against `alloc-tick` rather than against
the round, on the ground that a round index is only a proxy for where a window
sits in the page's own work-unit sequence. **The plot was made and the first
thing it says is about the axis itself**: `tick0` reads
`21, 49, 77, …, 329` — twelve values, step 28, **identical in all seven runs**.
So on this configuration `tick0` is an exact affine function of the window's
ordinal `k` (`tick0 = 21 + 28k`) and carries no axis the ordinal does not.

That is not a null result, because the ordinal is **finer than the round** and
the finer index is the one the effect is on. Clean windows, worst leg deviation
in bytes, one column per run:

| k | tick0 | arm | c1-page | c1-all | c6-page | c6-all | c24-page | c24-all |
|---|---|---|---|---|---|---|---|---|
| 3 | 105 | `reagent-subs` | 4,476 | 6,888 | — | — | 4,740 | 5,472 |
| 4 | 133 | `reagent-subs` | 512 | 500 | 512 | — | — | 512 |
| 5 | 161 | `uix-subs` | — | 4,522 | — | — | 2,008 | 2,502 |
| 6 | 189 | `uix-subs` | −12 | −36 | 0 | −12 | −12 | −12 |
| 7 | 217 | `reagent-subs` | −36 | 2,580 | −30 | 2,476 | −12 | 0 |
| 9 | 273 | `uix-subs` | −12 | 3,464 | −12 | 2,696 | −36 | 2,708 |
| 10 | 301 | `uix-subs` | 608 | 608 | 1,336 | 1,050 | 608 | 1,356 |
| 11 | 329 | `reagent-subs` | −36 | 0 | −12 | 2,580 | 0 | −12 |

Ordinals 0, 1, 2 and 8 have no clean window at all and are the subject of the
next section. Three readings on the table:

- **The two windows of one round differ.** Round 2 is `k` = 4 and `k` = 5: 512 B
  against 2,502 B at the median, **4.89× apart**. Round 3 is `k` = 6, which reads
  between −36 and 0 B in all six runs, and `k` = 7, which splits between −36 and
  +2,580 B. A round index averages over that.
- **The arm is not the carrier.** `reagent-subs` reads a 5,106 B median at
  `k` = 3 and a 512 B median at `k` = 4 — **9.97× apart, same arm, adjacent
  ordinals**. `uix-subs` reads 2,502 B at `k` = 5 and −12 B at `k` = 6.
- **At `k` = 9 the six readings separate perfectly by the write**: the three
  `page` runs read −12, −12 and −36 B; the three `all` runs read 3,464, 2,696 and
  2,708 B. The same split holds in 2 of 3 pairs at `k` = 7 and 1 of 3 at
  `k` = 11. `rf2-rs8q6` states the dispersion is a function of the round index
  *"not of the page, the write or the substrate"*; **at `k` = 9 that is not the
  case**, on n = 6 at one ordinal. It is reported as a narrowing of that
  statement and not as a refutation of it — one ordinal is one ordinal.

## The excursions are indexed too, and they are not the box

`rf2-e9wr` set aside five six-figure excursions and named them. This window's
excursions turn out to be **as reproducible as the effect they sit beside**.
Counting a window as excursive when its worst leg deviates by 70,000 B or more:

| k | tick0 | arm | excursive in the 6 stride-3 runs | in the stride-2 run |
|---|---|---|---|---|
| 0 | 21 | `reagent-subs` | **6 of 6** | yes |
| 1 | 49 | `uix-subs` | 5 of 6 | yes |
| 2 | 77 | `uix-subs` | **6 of 6** | yes |
| 3 | 105 | `reagent-subs` | 2 of 6 | no |
| 4 | 133 | `reagent-subs` | 1 of 6 | no |
| 5 | 161 | `uix-subs` | 2 of 6 | no |
| 6 | 189 | `uix-subs` | 0 of 6 | no |
| 7 | 217 | `reagent-subs` | 0 of 6 | no |
| 8 | 245 | `reagent-subs` | **6 of 6** | yes |
| 9 | 273 | `uix-subs` | 0 of 6 | no |
| 10 | 301 | `uix-subs` | 0 of 6 | no |
| 11 | 329 | `reagent-subs` | 0 of 6 | no |

Four ordinals are excursive in six or seven independent browser launches; six
ordinals are excursive in none. **Machine load does not align itself to a window
ordinal across seven separate processes**, and neither does the by-site mode: the
stride-2 run, which takes no extra read, is excursive at exactly the same four
ordinals and at none of the others.

### Which closes `rf2-e9wr`'s open question 3

That page left open *"how much of the excursion and collection rate is the loaded
box"*, and said a repeat on a quiet box would separate it. **This is that
repeat, and the answer is: essentially none of it.**

| quantity | `rf2-e9wr`, loaded box, stride 2 | this window, quiet box, stride 3 |
|---|---|---|
| arm windows | 72 | 72 |
| carrying an observed collection | **25 of 72** | **23 of 72** |
| six-figure excursions among the collection-free | 5 of 47 | 6 of 49 |
| prime excess, min / max | −51,684 B / 360,500 B | −42,124 B / 492,500 B |
| prime excess, median | 6,864 B | 6,858 B |

**Draining the fleet moved the collection rate by two windows in seventy-two and
did not shrink the tails at all** — the maximum is larger here, not smaller. The
comparison is not perfectly like for like: `rf2-e9wr` took seven runs at stride 2
(six floor plus a controls-only run) and this window took six floor runs at
stride 3, so the stride differs. The stride pair below prices that difference at
16 B per leg and 3-versus-4 collection-carrying windows in twelve, which is far
too small to account for a 23-versus-25 comparison.

**The consequence for scheduling, stated plainly because it is worth a paragraph
of its own.** The standing rule is that only a *clock* estimand needs a quiet
box, because a census of monotone counters reads the same on a loaded one. This
estimand is an allocation census, so on that rule it should not need the drain —
and `rf2-e9wr` correctly flagged that its own six-figure excursions were a reason
to doubt the classification, since a pure counter census should not produce them.
**The doubt is now resolved in the rule's favour.** The excursions are real
allocation and reclamation performed by the renderer's own heap, indexed by the
window's position in the run and reproducing on a box with nothing else on it.
They are a property of the arm and V8's collector schedule, not of competing
processes. **On this evidence a future allocation window on this rig does not
need the fleet drained**, and the quiet-box slot is better spent on a clock
estimand. What a drained fleet does still buy is the absence of a *confound* a
reader would otherwise have to be argued out of — which is worth something, but
not a scheduling rule.

## The stride pair — the extra read costs 16 B and perturbs nothing

The mode's header makes a falsifiable claim: that the arms' leg-magnitude
difference between the two strides is accounted for by the idle control's own
difference. If it were not, the mid-leg read would be perturbing the work unit
and no site figure on this page would be clean. **The pair was taken on
`P0_ALLOC_CELLS=6`, `write=page`, B = 24, back to back.**

| population | Δ leg median (stride 3 − stride 2) |
|---|---|
| `idle` control — **the prediction** | **+16 B** |
| `ctl1` (D = 1,000) | +16 B |
| `ctl2` (D = 400) | +16 B |
| arm windows, collection-free on **both** sides (n = 7) | **median +16 B, range +10 to +16 B** |
| arm windows, all 12 matched | median +16 B, range −1,620 to +16 B |

**The prediction is met.** The two negative entries in the all-12 row are windows
carrying a collection on one side of the pair and are excluded from the
seven-window row for that reason. The 10 B entry is one window at
`tick0` = 217 and sits within one 8-byte quantum of 16.

Two further readings from the same pair:

- **Collection rate is unmoved by the mode**: 3 of 12 collection-carrying at
  stride 2, 4 of 12 at stride 3.
- **Prime excess is unmoved**: median 6,812 B at stride 2, 6,864 B at stride 3.
  The 52 B difference is inside the prime excess's own interquartile width of
  80 B and nothing is read from its sign.

## What was refused, and what was not done

- **No gate, band or threshold was widened, narrowed or touched.**
  `ALLOC_LEG_TOLERANCE` stays 0.25 with its uncalibrated marker,
  `ALLOC_MIN_WRITES` stays 6, `ALLOC_FALL_THRESHOLD_B` stays 600,000,
  `ALLOC_CONTROL_SLACK` stays 0.75.
- **τ was not pinned and no calibration was attempted.** `rf2-e9wr` established
  that none is honest on the arms' data and this window found nothing that
  changes it — the population is still not homogeneous, and this page has now
  shown the inhomogeneity is finer-grained than the round.
- **No instrument change was made and none was attempted mid-window.** No new
  estimator, no extra rung, no third site, no fourth warm-up. The two instrument
  findings this window turned up — the intra-leg reclamation invisible to both
  gates, and the `worst-dev site` column header naming an estimator it does not
  compute — are **filed, not built**. **[Amended 2026-08-17: both have since
  been built, outside this window and after it — the gate by PR #8420
  (`rf2-4ctls`) and the heading by `rf2-stals`. The sentence records what this
  window did, which is unchanged; it is no longer a description of the
  instrument's present state.]**
- **Every run's refusals are reported.** All seven runs exited **1** on the
  driver's own alloc refusals: 23 of 72 arm windows carried an observed
  collection and **33 of 72 carry a leg past τ**, so **37 of 72 certify** —
  against `rf2-e9wr`'s 41 of 72, a third replication. Those are the driver's
  verdicts on what may be quoted, and this page quotes nothing from a refused
  window.

  **[Amended 2026-08-17, `rf2-7rohx`.]** That count read **39 of 72** when this
  page was written and it was correct then. **No run was re-taken and no figure
  was re-measured.** PR #8420 built the instrument finding this page filed,
  adding a third allocation gate — `allocIntraLegRefusals`, which refuses a
  window carrying a negative measured site step — and the same raw record
  replayed through the shipped `allocWindowVerdict` now refuses two windows the
  older two gates both passed. They are the two in [An intra-leg collection the
  falls gate cannot see](#an-intra-leg-collection-the-falls-gate-cannot-see),
  whose verdict cells read `certified` on the gates of the day. **Exactly two
  windows change classification, and nothing else on this page moves.** Of the
  24 windows carrying a negative site step, 21 were already refused by the falls
  gate and the third was already refused at τ on other legs; the 41-window
  headline population had already excluded these two by hand, which is why it is
  unaffected. `rf2-e9wr`'s 41 of 72 is unmoved as well — that window ran at the
  shipped stride of 2, where `allocSiteSplit` yields no site legs and the new
  gate is inert by construction.
- **No figure from a collection-carrying window enters any conclusion**, and the
  headline population additionally excludes six-figure excursions and the two
  windows carrying an intra-leg reclamation.
- **No bound is claimed from an impossible reading.** The negative site steps
  bound nothing; they are named and excluded.
- **Nothing from V1's or V3's criteria is reported here.** This window borrowed
  V1's configuration to obtain arm windows and read one quantity out of it.

## What this does NOT conclude

- **It does not settle the same-term hypothesis.** `sameSite` reads `agree`, and
  agreement is necessary for the same-term reading and not sufficient. The
  magnitudes make the surviving form of the hypothesis more expensive; they do
  not refute it.
- **It does not identify a mechanism inside `dispatch`.** `dispatch` is the
  `dispatch-sync` through the event pipeline **and** the signal graph, plus
  whatever render the substrate schedules synchronously inside it. Which of
  those allocates the position-dependent term is a question this instrument
  cannot reach, because there is no third seam that does not mean instrumenting
  re-frame from inside the arm.
- **It does not exonerate React's commit in general.** The `floor` arm's commit
  is a near-no-op, as the 80 B drain median shows; a subscribing arm was not
  measured.
- **It does not explain the ordinal indexing.** That the excess and the
  excursions are both reproducible functions of the window ordinal is measured
  here; why they are is not, and no cause is offered.
- **It does not claim the write governs the dispersion.** The clean `page`/`all`
  split at `k` = 9 is one ordinal with n = 6.
- **It claims nothing about within-run machine quietness** beyond the bracketing
  described above.

## What this leaves open

1. **What allocates the position-dependent term inside `dispatch`.** The site is
   settled; the statement inside it is not, and reaching it needs a seam this
   instrument deliberately does not cut.
2. **Whether the same finding holds on a subscribing arm**, where the commit
   actually commits something. The `floor` plan cannot answer it.
3. **The two windows that passed both gates while carrying an intra-leg
   reclamation.** The falls gate is blind to a reclamation bracketed by a larger
   allocation within one leg, and the leg tolerance passes it when the net is
   inside τ. Whether the certificate should read the by-site stream is an
   instrument question, filed rather than answered.
4. **Why the excursions are locked to ordinals 0, 1, 2 and 8.** Reproduced in
   seven of seven browser launches, at both strides, and unexplained.

These are the operator's, and this page takes none of them.
