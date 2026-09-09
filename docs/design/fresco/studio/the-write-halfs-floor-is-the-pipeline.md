# The write half's floor is the pipeline — V1, V2 and V3 measured

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-2rtt6.140`, criterion 5's permitted
witnesses. Measured 2026-08-14 04:18–04:42 AUSEST on the granted quiet box,
branch `worker/allocv1-140` off `457ac7f173`.

**No slope is published on this page and none was fitted.** V1, V2 and V3 are
validity witnesses: they judge the instrument, they do not produce a row. What
follows is what they measured, what they refused, and — with equal weight —
which of their criteria this window leaves **unassessed** rather than answered.

## The answer, first

- **The write half narrows the gap and does not close it.** The brief's largest
  open question was how much of `F ≈ 24.4 KB` is the 300-cell rebuild and how
  much is the event pipeline. **It is the pipeline.** `F_old − F_new` is
  **1.1 – 1.9 KB per write** at every page size, so the rebuild is roughly **7%
  of F** and the pipeline residue `F₀ ≈ 18 – 19.4 KB` is the rest. The per-cell
  rebuild cost falls out at **`w` ≈ 3.6 – 7.3 B/cell**, consistent across six
  independent readings.
- **`F_new` is flat in B, exactly as `F_old` is.** That is the finding, not a
  detail: the write half made the write proportional to the page, but what the
  floor arm measures is dominated by a term the page size does not touch.
- **V1's criterion is met at B = 24 and B = 96 and missed at B = 4** —
  `F_new(B)/B` reads 4,497 / 4,854 B per boundary per write at B = 4 against the
  2 KB bar, 767 / 798 at B = 24, and 192 / 200 at B = 96.
- **The two constraints the brief hoped would cross do not cross.** Clearing
  V1's fixed-residue bar needs **B ≳ 9**; keeping a six-write R = 20 window under
  the measured 600,000 B collection onset needs **B ≲ 2**. They are separated by
  a factor of four to five, and the smallest page this rig can mount is B = 4,
  which satisfies neither.
- **The R = 20 rung refused at six writes, as `rf2-qomo`'s ruling predicted** —
  and the prediction under-estimated the magnitude. It forecast ≈ 630 KB; the
  measured windows run **900 KB – 1.5 MB**.
- **Every arm window in every run carries a fixed first-leg excess of ≈ 7 KB**,
  336 of 336, and that single term refuses the floor arm at every page size under
  both writes. It is the reason V1's and V2's criteria are unassessed rather than
  answered.
- **τ, calibrated as V3 specifies, would refuse the whole ladder.** The controls'
  worst leg deviation is **0.99%**, so a small-integer multiple puts τ near
  0.02 – 0.05 — an order of magnitude *below* the 0.25 placeholder, which already
  refuses every floor window. **τ was not pinned by this window**, and the reason
  is set out below.

## The box

Queue length is the decisive counter and it was sampled before the window, during
every measured phase, and at close.

| phase | `\System\Processor Queue Length` | `\Processor(_Total)\% Processor Time` |
|---|---|---|
| before (5 samples) | 0, 0, 0, 0, 0 | 23.9 – 12.2% |
| V1 batch, measured phase (8 samples) | 1, 0, 0, 0, 1, 0, 0, 0 | 17.8 – 42.6% |
| V2 measured phase (7 samples) | 0, 0, 0, 1, 0, 0, 0 | 9.3 – 17.1% |

Two transient spikes were seen — queue 26 and 14 at 04:23, and 38 at 04:34 — and
both fall inside a `shadow-cljs release` compile belonging to this window's own
driver, not inside a measured phase. Measured directly rather than assumed: over
a six-second interval 96 resident `node`/`java`/`chrome` processes accrued
54.97 cpu-seconds, of which **53.41 was a single `java` pid — this window's own
compile**. The remaining 95 processes accrued 1.56 cpu-seconds, i.e. 0.26 of one
core on a 24-thread box, and 73 of them are the operator's Chrome session.

## The instrument was verified before it was believed

`rf2-gxrr` shipped the two switches this window needed, and both were exercised
against a wrong value before either was relied on. Each refused **by name, before
a browser was launched**, and the exit code quoted is the one captured from the
runner rather than the one the harness reported:

| check | captured exit | refusal |
|---|---|---|
| `P0_ALLOC_WRITE=pge` | 1 | `unknown P0_ALLOC_WRITE "pge" — the allocation window drives one of page \| all` |
| `P0_ALLOC_PLAN=contols` | 1 | `unknown P0_ALLOC_PLAN "contols" — the allocation row runs one of full \| floor \| controls` |
| `p0_ladder_structural.test.cjs` on this worktree | 0 | 59 passed |

Every run below states its own write in its own summary, so the record names the
configuration it was taken under rather than leaving it to be inferred.

## Every run's positive control passed

A run whose positive control fails contributes no data, so the controls are
reported first and per run, never pooled. **All nine runs read the same, and all
nine passed.**

| run | direct (B/double) | differential (B/double) | verdict |
|---|---|---|---|
| V1 `all` B = 4 / 24 / 96 | 8.08 | 8.00 | OK |
| V1 `page` B = 4 / 24 / 96 | 8.08 | 8.00 | OK |
| V2 `all` B = 4 | 8.08 | 8.00 | OK |
| V2 `page` B = 4 | 8.08 | 8.00 | OK |
| V3 controls-only | 8.08 | 8.00 | OK |

The differential — D = 1,000 less D = 400, which cancels the sampler's own
footprint and every other constant — reads **8.00 B/double against a predicted 8**
in every run. The idle window reads 32 B per iteration throughout. Transient
garbage is visible to this counter, which is the one claim the whole row rests on.

## V1 — the floor at three page sizes, under both writes

Floor arm only (`P0_ALLOC_PLAN=floor`), `P0_ROOTS=4`, `P0_ALLOC_CELLS` ∈ {1, 6, 24}
so B ∈ {4, 24, 96}, six rounds, six writes a window, three full-size warm-ups,
both segments, each page measured under `:p0/write-all` and `:p0/write-page`.
Six runs, all six captured **exit 1**.

**All 72 floor windows were refused by the leg witness** — 6 of 6 in every arm of
every run. The falls gate additionally refused five of the six runs; only
`page`/B = 96 came back with zero falling steps. So the figures below are read off
refused windows and are labelled as such wherever they appear.

The statistic quoted is `legMedian`, which is the rig's own per-window robust
figure and the one its refusal messages are stated against. The published
`rise/W` column is contaminated in exactly these windows, by the first-leg excess
and by the occasional 200 – 380 KB collection leg, and it is reported beside the
median rather than instead of it.

| segment | B | `F_old` legMedian | `F_new` legMedian | `F_old − F_new` | `F_new(B)/B` | vs the 2 KB bar |
|---|---|---|---|---|---|---|
| reagent-subs | 4 | 19,898 | 17,987 | 1,911 | 4,497 | **over** |
| reagent-subs | 24 | 19,781 | 18,403 | 1,378 | 767 | under |
| reagent-subs | 96 | 19,898 | 18,420 | 1,478 | 192 | under |
| uix-subs | 4 | 20,487 | 19,416 | 1,071 | 4,854 | **over** |
| uix-subs | 24 | 20,446 | 19,146 | 1,300 | 798 | under |
| uix-subs | 96 | 20,487 | 19,247 | 1,240 | 200 | under |

Ranges across the six rounds, never a mean alone, and read off refused windows:

| run | segment | `rise/W` range | legMedian range | falls |
|---|---|---|---|---|
| `all` B = 4 | reagent-subs | 20,403 – 82,047 | 19,256 – 23,132 | 4 in the run |
| `all` B = 4 | uix-subs | 20,962 – 65,389 | 19,696 – 23,572 | |
| `page` B = 4 | reagent-subs | 18,703 – 22,581 | 17,556 – 21,432 | 2 in the run |
| `page` B = 4 | uix-subs | 19,340 – 48,426 | 17,996 – 21,872 | |
| `all` B = 24 | reagent-subs | 20,399 – 24,691 | 19,256 – 23,132 | 1 in the run |
| `all` B = 24 | uix-subs | 20,968 – 86,114 | 19,808 – 23,572 | |
| `page` B = 24 | reagent-subs | 18,783 – 61,915 | 17,636 – 21,512 | 1 in the run |
| `page` B = 24 | uix-subs | 19,344 – 23,339 | 18,076 – 21,952 | |
| `all` B = 96 | reagent-subs | 20,401 – 28,821 | 19,256 – 23,132 | 3 in the run |
| `all` B = 96 | uix-subs | 20,968 – 111,851 | 19,696 – 23,944 | |
| `page` B = 96 | reagent-subs | 19,171 – 72,370 | 18,028 – 21,904 | 0 in the run |
| `page` B = 96 | uix-subs | 19,738 – 23,992 | 18,468 – 22,344 | |

### What the control says, and what it cannot say

`F_old`'s job is to say the rig has not moved and to license the comparison
between the two writes. It has two clauses and they come back differently.

- **Flat in B — HOLDS.** reagent-subs reads 19,898 / 19,781 / 19,898 across
  B ∈ {4, 24, 96}; uix-subs reads 20,487 / 20,446 / 20,487. That is flat to
  within 0.6% across a 24× page range, which is what "flat by construction"
  predicts and is the strongest single piece of evidence that the instrument is
  behaving.
- **Lands on the 2026-08-08 figures at B = 24 — ~~UNASSESSED~~ RETIRED.** Those
  figures
  (24,108 / 24,730 B per write) are `rise/W`, and at B = 24 under `write-all`
  this window's `rise/W` reads 22,000 (reagent-subs) and 33,512 (uix-subs), from
  windows the witness refused. The published figures sit inside the per-round
  ranges but no certified window exists to read the clause from, so the honest
  verdict is that it was not assessed — not that it was missed.
  **RETIRED 2026-08-20 (`rf2-nkeba`)**, and the verdict above ages well: the
  clause was assessed on 2026-08-17 and failed by 16 – 20%, which is the size of
  the **3,784 B level ladder** the arm carries at a single revision, so it never
  could have been read. The 2026-08-08 figures are marked **NOT-COMPARABLE** and
  are a reading of the ladder's top rung. See
  [the 2026-08-08 row is the arm's top level](the-2026-08-08-row-is-the-arms-top-level.md).

> **AMENDED 2026-08-17 22:30 AUSEST (`rf2-nkeba`).** "Those figures are
> `rise/W`" is true and incomplete, and the missing half is what the clause
> turned on once it could be read. They are the **median** of `rise/W` across the
> six 2026-08-08 rounds, to the byte — the runner of the day printed the
> **mean**, 23,761 and 24,679 — taken on a **pre-prime, pre-certificate**
> instrument over an **unselected** population. So the comparison a later window
> makes against a per-window value on a certified subset differs in statistic,
> in population and in sample stream at once. Re-derived from the committed
> dataset in
> [the control's target is not the quantity it is read against](the-controls-target-is-not-the-quantity-it-is-read-against.md).
> **Nothing on this page is withdrawn**, and the two figures quoted here for
> this window's own `rise/W` are unaffected.

### The per-cell cost, which is the number the write half turns on

`F_old` rebuilds a fixed 300 cells; `F_new` rebuilds B. The saving therefore
prices the per-cell rebuild directly, and six independent readings agree:

| segment | B | cells saved | bytes saved | `w` (B/cell) |
|---|---|---|---|---|
| reagent-subs | 4 | 296 | 1,911 | 6.46 |
| reagent-subs | 24 | 276 | 1,378 | 4.99 |
| reagent-subs | 96 | 204 | 1,478 | 7.25 |
| uix-subs | 4 | 296 | 1,071 | 3.62 |
| uix-subs | 24 | 276 | 1,300 | 4.71 |
| uix-subs | 96 | 204 | 1,240 | 6.08 |

At `w` ≈ 5 – 7 B/cell the entire 300-cell rebuild is **1.5 – 2.1 KB**, so the
remaining `F₀ ≈ 18 – 19.4 KB` is the event pipeline, the empty `flushSync` and
the substrate's drain. **The pipeline is over 90% of F**, and the brief's
contingency ladder is entered at its first step.

### The two constraints do not cross

Contingency (i) is "raise B", and the brief asked V1 plus the witness to say
whether that constraint and the collection onset cross. On this window's own
measurements they do not.

- **From below**, V1's bar `F_new(B)/B ≤ 2,000` with `F_new ≈ F₀ + B·w` and
  `F₀ ≈ 18,000 – 19,400` gives **B ≥ 9.0 – 9.7**.
- **From above**, a six-write window must hold `W · perWrite ≤ 600,000`, i.e.
  `perWrite ≤ 100,000`. At R = 20 the measured per-boundary signal is
  `s(20)` = 32,058 – 43,263 B per boundary per write (below), so
  `B ≤ (100,000 − F₀)/s(20)` gives **B ≤ 1.9 – 2.5**.

The admissible page would have to be at least nine boundaries and at most two.
**The smallest page this rig can mount is B = 4** — `P0_ROOTS = 4` with one cell —
and it satisfies neither bound. Raising B relieves the fixed-residue bar and
worsens the onset bound at the same rate the ladder's worst rung sets, so the gap
does not close by moving B.

## V2 — the equivalence cross-check, run at six writes under `rf2-qomo`

Full 1/3/7/20 ladder plus the floor, both segments, B = 4, **six writes per
window under the operator's ruling**, six rounds, measured twice — once under
each write. Both runs captured **exit 1**. Both positive controls passed.

### The R = 20 rung's verdict: REFUSED, and the prediction was optimistic

The ruling accepted a prediction of ≈ 630 KB against a measured 600,000 B onset
and instructed that a refusal be reported as the result. The rung refused, and
the windows are larger than forecast:

| run | arm | window `rise` range | falls |
|---|---|---|---|
| `all` | reagent-subs \| hicasso R20 | 920,386 – 1,172,582 | 7 |
| `all` | reagent-subs \| reagent R20 | 983,866 – 1,510,710 | 9 |
| `all` | uix-subs \| hicasso R20 | 903,386 – 1,494,586 | 8 |
| `all` | uix-subs \| uix R20 | 912,926 – 933,082 | **0** |
| `page` | reagent-subs \| hicasso R20 | 908,454 – 1,095,490 | 7 |
| `page` | reagent-subs \| reagent R20 | 976,130 – 1,013,450 | 7 |
| `page` | uix-subs \| hicasso R20 | 897,054 – 1,412,554 | 5 |
| `page` | uix-subs \| uix R20 | 904,014 – 1,030,330 | 1 |

The R = 20 windows run **1.5× to 2.5× the onset**, not the predicted 1.05×, so
the brief's estimate was low by roughly 45%. Collections concentrate there:
24 of the `all` run's 27 in-window falls and 20 of the `page` run's 28 land on an
R = 20 arm.

**One arm is worth naming rather than averaging away.** `uix-subs | uix R20`
under `write-all` certified 6 of 6 windows with zero falls — it is the cheapest
R = 20 arm on the rig (leg median 150,342 – 153,968 B against 178,526 – 201,538
for the others) and it sat just under the onset. Under `write-page` the same arm
refused 1 of 6. That single arm is the whole of the R = 20 rung that survived, on
one write, on one segment; it is not the rung passing.

### V2's own criterion is UNASSESSED

V2 compares `(arm − floor)/B` between the two writes and requires the floor to
drop. **The floor certified 0 of 6 windows in both runs, on both segments**, so
there is no certified floor to subtract at any rung, and the criterion cannot be
evaluated on certified data. That is an unassessed criterion, not a failed one.

Read off refused windows, and offered as an indication rather than a result, the
comparison has the shape V2 asks for. Per-window `legMedian`, median across the
six rounds:

| arm | `all` `(arm−floor)/B` | `page` `(arm−floor)/B` | difference |
|---|---|---|---|
| reagent-subs \| reagent R1 | 4,439 | 4,434 | 0.1% |
| reagent-subs \| reagent R3 | 8,451 | 8,420 | 0.4% |
| reagent-subs \| reagent R7 | 16,680 | 16,692 | 0.1% |
| reagent-subs \| reagent R20 | 43,204 | 43,263 | 0.1% |
| reagent-subs \| hicasso R1 | 3,766 | 3,752 | 0.4% |
| reagent-subs \| hicasso R3 | 7,089 | 6,922 | 2.4% |
| reagent-subs \| hicasso R7 | 14,488 | 14,402 | 0.6% |
| reagent-subs \| hicasso R20 | 40,338 | 40,180 | 0.4% |
| uix-subs \| uix R1 | 2,269 | 2,244 | 1.1% |
| uix-subs \| uix R3 | 5,376 | 5,419 | 0.8% |
| uix-subs \| uix R7 | 11,734 | 11,700 | 0.3% |
| uix-subs \| uix R20 | 32,095 | 32,058 | 0.1% |
| uix-subs \| hicasso R1 | 3,673 | 3,644 | 0.8% |
| uix-subs \| hicasso R3 | 6,886 | 6,883 | 0.0% |
| uix-subs \| hicasso R7 | 13,962 | 14,076 | 0.8% |
| uix-subs \| hicasso R20 | 39,443 | 40,236 | 2.0% |

The floor drops as the equivalence argument requires — 23,116 → 21,428 B on
reagent-subs and 23,627 → 21,908 B on uix-subs, a fall of 1,688 / 1,719 B (7.3%).
The R = 0 rung is omitted from the table because `arm − floor` there is within
±12 B of zero, which is the shell rung reading as the brief expects and carries
no ratio worth quoting.

**This is not V2 passing.** It is what the refused windows contain, and it is
recorded because the next design step will want it.

## V3 — the tolerance calibrated, and what the calibration implies

Controls only (`P0_ALLOC_PLAN=controls`), both D values, six rounds, six writes a
window. **This was the only run of the nine to exit 0.**

The controls are astonishingly tight. Across all 12 corroborated-clean control
windows — every one of them inside `ALLOC_CONTROL_SLACK` of 8 B/double — the
**worst relative leg deviation is 0.99%**, and 11 of the 12 read exactly **0.00%**,
with the six legs byte-identical. The idle windows read 0.00% at 16 B a leg.

| population | worst relative leg deviation |
|---|---|
| control windows, D = 1,000 (6) | 0.99% |
| control windows, D = 400 (6) | 0.00% |
| idle windows (6) | 0.00% |

So the natural spread of a corroborated-clean window on this rig is **≤ 1%**, and
τ set at a small integer multiple of the observed worst deviation — the rule V3
states — lands at roughly **0.02 – 0.05**.

**τ was not pinned by this window.** Pinning it means editing the source constant,
deleting the placeholder line and amending a structural pin, which is rig work;
this window measures and does not modify the instrument it is measuring on. The
calibration figure is the deliverable and the pinning is separate work.

### The floor arm's leg spread — V3's harder case, and it is the finding

V3 asks for the floor's leg spread at each of V1's three pages beside the
controls'. It is between **26× and 46×** wider:

| page | write | floor leg-1 excess over cohort median |
|---|---|---|
| B = 4 | `all` | 26.1 – 42.5% |
| B = 4 | `page` | 28.1 – 41.1% |
| B = 24 | `all` | 25.8 – 45.7% |
| B = 24 | `page` | 27.9 – 43.2% |
| B = 96 | `all` | 25.7 – 42.5% |
| B = 96 | `page` | 27.5 – 45.4% |

**Calibrating τ on the controls, as V3 specifies, would therefore refuse the
entire ladder** — at τ ≈ 0.03 a window certifies only if its leg median exceeds
roughly 230 KB, which no arm on this rig reaches except R = 20, and R = 20 is
already refused by the collector. The 0.25 placeholder is far *more* permissive
than an honest calibration, and it already refuses every floor window.

V3's own escape clause is written for the mirror case — a spread so wide that no
τ below 1 leaves margin — and the substance carries over: the answer is to report
it, not to pick a τ that certifies what one wants certified. The controls are not
a valid calibration population for the arms, because their work unit is a dropped
`.slice()` with no first-write re-allocation in it and the arms' work unit is a
`dispatch-sync` plus a substrate drain, which has one.

## The first-leg excess, which explains every refusal

One term accounts for the refusal pattern across all nine runs, and it is not
noise.

**Every arm window measured in this window — 336 of 336, across eight
browser runs — has a positive first-leg excess**, and its size barely moves:

| statistic | leg 1 − cohort median (B) |
|---|---|
| min | 3,441 |
| p25 | 6,856 |
| median | **6,966** |
| p75 | 8,056 |
| max | 28,448 |

It is **constant in absolute bytes across a 24× page range** (B ∈ {4, 24, 96}),
**identical under both writes**, and it survives the three full-size warm-up
windows the driver already runs. The tail legs of a clean window are
byte-identical to one another — one B = 4 floor window reads
`[26044, 19256, 19256, 19256, 19256, 19256]` — so the instrument's precision is
excellent and leg 1 is the sole deviant. A collector event looks different and
appears separately, as a negative leg (one window read −42,160 B).

Because the excess is a fixed ≈ 7 KB, the leg rule refuses exactly those windows
whose median is small enough for 7 KB to exceed τ of it — median below
`6,966 / 0.25` ≈ **27,900 B**. That prediction agrees with the observed refusal in
**290 of 336 windows (86.3%)**, and every one of the 46 disagreements is a window
refused for an additional reason (another leg, or a collection) rather than one
the rule spared. The pattern falls straight out:

| arm | leg median | excess as % of median | observed |
|---|---|---|---|
| floor | 17.6 – 24.0 KB | 29 – 41% | refused everywhere, 6/6 |
| R = 0 | 18.3 – 24.4 KB | 29 – 38% | refused everywhere, 6/6 |
| R = 1 | 30.6 – 44.6 KB | 16 – 22% | mostly certified |
| R = 3 | 43.0 – 61.9 KB | 11 – 16% | certified |
| R = 7 | 68.0 – 91.4 KB | 8 – 10% | certified |
| R = 20 | 148.7 – 201.5 KB | 3 – 5% | refused by the **collector**, not the legs |

### Why that is structural and not a tuning problem

The admissible band is squeezed from both ends. A window must allocate **more**
than ≈ 28 KB per write for the fixed first-leg excess to fall inside τ, and
**less** than ≈ 100 KB per write for six writes to stay under the 600,000 B
collection onset.

**The floor arm is below the band, at every page size, under both writes, in
every run.** Its leg median is 17,556 – 23,982 B in all sixteen run × segment
cases measured here, and — this is the part that does not go away — **the floor
is F, and F is flat in B**. It does not grow when the page grows. So no choice of
page moves the floor into the band.

And `arm − floor` is the quantity every one of these witnesses is stated over.
The rungs that certify cannot be differenced against a floor that never does.

This is the same shape as the bead this window belongs to, reappearing one level
down: the bead recorded that shrinking the measured unit has a floor and the
floor is F; this window records that certifying the measured unit has a floor
too, and it is the same F.

## What was refused, and what was not done

- **No gate, band or threshold was widened or touched.** `ALLOC_MIN_WRITES` stays
  6, `ALLOC_FALL_THRESHOLD_B` stays 600,000, `ALLOC_LEG_TOLERANCE` stays the 0.25
  placeholder, R = 20 stays, `ALLOC_CONTROL_SLACK` stays 0.75.
- **The R = 20 rung was not dropped and was not re-run until it passed.** It ran
  once per write, at six writes, and its refusal is reported as the result the
  ruling asked for.
- **τ was not pinned**, though this window measured the figure that would set it.
  Pinning is a source edit and this window does not edit the instrument it
  measures on.
- **The first-leg excess was not repaired.** It is roughly a one-line change to
  the warm-up or the window's leg accounting and the box was idle and granted.
  A window taken on an instrument amended mid-window is the uncertified-instrument
  window criterion 5 exists to prevent. It is filed instead.
- **No slope was fitted or published.** The `floor` and `controls` plans carry no
  rungs and the driver reports no fitted line under them; the two V2 runs do fit,
  and nothing from those fits is quoted here.
- **The refused windows' numbers are reported, and labelled at every appearance.**
  Omitting them would have hidden the mechanism; presenting them as certified
  would have been the contamination the lane has already paid for once.

## What this leaves open

1. **Whether the first-leg excess is removable.** If it is warm-up, a fourth
   full-size warm-up may clear it; if it is re-allocation of something the forced
   collection reclaimed, it will not, and the window's leg accounting has to
   exclude the first leg or the certificate has to be restated over `W − 1` legs.
   Nobody has measured which, and this window did not.
2. **Whether the leg witness can certify a floor arm at all.** On this evidence
   it cannot, at any page size, and that is a question about the witness's design
   rather than about τ.
3. **Where the write half stands.** It works and it is measured: 1.1 – 1.9 KB per
   write, `w` ≈ 5 – 7 B/cell. It is also, on these numbers, roughly 7% of the
   problem. Contingency (ii) — carry `F₀` as a stated constant of the instrument
   that the floor subtracts — is the step the data points at, and contingency (i)
   is closed by the non-crossing above.

These are the operator's, and this page takes none of them.
