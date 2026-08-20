# The rider follows the controls, not the round boundary — and `last` was never going to say so

Seat: MEASUREMENT RECORD, EP-0039. Bead `rf2-rs8q6`, option (b) of the mayor's
2026-08-20 22:40 AUSEST ruling: *move the three controls off the round boundary,
then read the result — and if the 748 B term is still unnamed, close on the
record.*

This is a **rig change plus nine new runs**, taken strictly one at a time on one
box at one revision, interleaved across the three schedules. It is the last
separation the schedule can perform.

**τ was not moved, in either direction.** No gate, band, threshold or budget
constant on this rig was widened, narrowed or touched.

## The answer, first

**The carrier is the three CONTROL WINDOWS. The round-opening position carries
nothing of its own.**

The previous record isolated a +748 B rider on the first arm window of a round
and named the two properties it could not separate: that window is both
**(A)** the first arm after the round's three control windows and **(B)** the
first arm after the round-loop boundary. Under the shipped schedule those are one
window. Under `P0_ALLOC_CONTROL_SLOT=mid` they are two, in every round.

| Control slot | (A) is | (B) is | Rider at (A) | at (B) | z |
|---|---|---|---|---|---|
| `first` (shipped) | position 0 | position 0 | **18 of 39** | — | 4.53 |
| `last` | position 0 | position 0 | **26 of 43** | — | 5.31 |
| `mid` | position **1** | position **0** | **9 of 37** | 3 of 44 | **2.21** |

Under `first` and `last` the z column compares the after-controls windows against
the rest; under `mid` it compares the two separated cells directly. The rider went
where the controls went.

- **(B) is refuted as a carrier.** Under `mid` the round-opening window reads
  3 of 44 (6.8%) against 5 of 90 (5.6%) in the corresponding not-after-controls
  cells of `first` and `last` — z = 0.29. That is the background rate, not a
  signal. Moving the controls off the round boundary took the rider with them and
  left nothing behind.
- **(A) is the carrier, but it does not carry all of it.** Decoupled, the
  after-controls rate is 9 of 37 (24.3%) against 44 of 82 (53.7%) where the two
  properties coincide — z = −2.98. Stated plainly rather than glossed: something
  about the two properties **together** roughly doubles the rate, and since (B)
  alone carries nothing, the schedule cannot resolve what.
- **`last` is the consistency control and it held.** Taken in the reader's
  canonical order — `first`'s 18 of 39 against `last`'s 26 of 43 — the two are
  not separated: z = −1.30.

## The rig change, and why it carries three slots and not two

`P0_ALLOC_CONTROL_SLOT` in `implementation/core/test/re_frame/bench/p0_run.cjs`,
off by default. It names where in a round's pass sequence the three controls are
driven.

| Slot | The round | (A) lands on | (B) lands on | Separated? |
|---|---|---|---|---|
| `first` | `C C C A₀ A₁` | position 0 | position 0 | never |
| `last` | `A₀ A₁ C C C` | position 0 | position 0 | round 0 only |
| `mid` | `A₀ C C C A₁` | position **1** | position **0** | every window |

**The ruling asked for controls-last, and controls-last alone does not
separate them.** The round loop is cyclic, so moving the three controls to the
end of round *r* puts them immediately before the first arm of round *r + 1*:
the stream `C C C A₀ A₁ | C C C A₀ A₁ | …` becomes
`A₀ A₁ C C C | A₀ A₁ C C C | …`, which is the same cyclic sequence phase-shifted.
Property (A) still attaches to position 0 in every round but the first. `last`
separates the two in exactly **one window per run** — round 0's position 0, which
under `last` opens the whole run and has no controls before it at all.

That is not a reading of the source; it is driven. `p0_ladder_structural.test.cjs`
flattens six rounds of each slot and reads both predicates off the sequence,
requiring `first` to separate on 0 windows, `last` on exactly 1 at round 0
position 0, and `mid` on all 12.

So the mode ships all three, and which one answers the question was fixed before
the runs:

- **`first`** — the pre-bead schedule to the call. Every published row is taken
  under it.
- **`last`** — the phase shift, and therefore the **consistency control**: it
  must reproduce `first`, because it drives the same cyclic stream. A schedule
  that merely re-ordered the driver's calls and moved the result would mean the
  carrier is a property of the run's head or tail rather than of the round, which
  would be a different finding entirely.
- **`mid`** — the **discriminator**, separating (A) from (B) at full n.

**Pre-registered outcomes**, written into the source before the first run: the
rider **moves to position 1** ⇒ the carrier is (A), the controls; it **stays at
position 0** ⇒ the carrier is (B), the round-opening position; it appears at
**both or neither** ⇒ neither property is the carrier as stated. The first of
those is what happened.

`first` is the identity — the driver makes exactly the calls it made before, in
the same order — and a mistyped slot is refused by name in the preflight beside
the plan, the write and the segment order. The row records `controlSlot` and each
round records the `windowOrder` it actually drove, on criterion 6's rule that a
reader of any row can tell **from the row** how it was taken.

**A moved-control row is a diagnostic row, not a publishable one.** The controls
are this row's null arm and every studio page reads them against the arms window
for window; a row whose controls were taken at another point in the round is not
the row those comparisons were made on.

## The corpus

| Corpus | Runs | Control slot | Arm windows | Collection-free |
|---|---|---|---|---|
| `ctrlslot-rs8q6/first-{1,2,3}` | 3 | `first` | 108 | 85 |
| `ctrlslot-rs8q6/mid-{1,2,3}` | 3 | `mid` | 108 | 81 |
| `ctrlslot-rs8q6/last-{1,2,3}` | 3 | `last` | 108 | 87 |

All nine are `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`,
`P0_ALLOC_CELLS=6` (B = 24), `P0_ALLOC_ROUNDS=18`, segment order left at the
shipped `parity` — so these runs differ from the published series on **one axis
only**. Windows carrying an observed collection are excluded on `falls === 0`,
the same restriction every record in this line uses and independent of τ.

Every run exited **1**, on the same two refusals every floor run in this corpus
carries: collections inside measured windows, and windows past the leg tolerance.
That is the phenomenon under study, not a fault in the run, and the record is
written before the refusal.

## It holds in every run separately

Rider windows, by within-round position. Under `first` and `last` position 0 is
the after-controls window; under `mid` position 1 is.

| Run | Position 0 | Position 1 |
|---|---|---|
| `first-1` | **4 of 13** | 0 of 15 |
| `first-2` | **9 of 15** | 1 of 16 |
| `first-3` | **5 of 11** | 1 of 15 |
| `last-1` | **10 of 14** | 1 of 15 |
| `last-2` | **8 of 13** | 1 of 16 |
| `last-3` | **8 of 17** | 1 of 12 |
| `mid-1` | 1 of 15 | **1 of 12** |
| `mid-2` | 1 of 15 | **5 of 13** |
| `mid-3` | 1 of 14 | **3 of 12** |

Nine independent browser launches. Every `first` and `last` run puts the rider on
position 0; every `mid` run drops position 0 to exactly **1 window** — the
background — and the three `mid` runs put 1, 5 and 3 on position 1. No single run
carries the result, and the `mid` collapse at position 0 is the cleanest thing on
this page: 1, 1, 1 out of 15, 15 and 14.

## It is the same term

| Slot | Rider legs at (A) | Modal values | Ordinals |
|---|---|---|---|
| `first` | 19 | 748 B (×10), 720/736/760 B (×2 each) | 3:4, 4:6, 5:9 |
| `last` | 29 | 736 B (×8), 748 B (×8), 754 B (×4) | 2:3, 3:9, 4:8, 5:9 |
| `mid` | 9 | 742/748/754 B (×2 each), 720 B (×1) | 2:2, 3:5, 4:1, 5:1 |

Same band, same modal values, same late ordinals — the population that moved to
position 1 under `mid` is the population that sat on position 0 under the other
two. And the **prime excess stays slot-invariant and cell-invariant**, replicating
the earlier positive discriminator against "the prime's term recurring": 6,848 vs
6,824 B under `first`, 6,884 vs 6,860 B under `last`, 6,854 vs 6,880 B under
`mid`.

**The controls remain the null arm.** Over **2,904 collection-free control legs**
across the nine runs — 960 under `first`, 972 under `last`, 972 under `mid` — the
count in the 700–800 B band is **zero**, in every slot.

## One trap this window laid, and it nearly took me

Pooled by slot, position 1 appears to carry a ~2,400 B term under `mid` and
`last` and nothing at all under `first` — median worst leg excess 2,508 B, 2,328 B
and 6 B. Read that way it contradicts everything above.

**It is a run-level property, not a slot-level one.** Per run, position 1's median
worst excess reads:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| `first` | −6 B | **2,520 B** | 6 B |
| `last` | **2,364 B** | 12 B | **2,400 B** |
| `mid` | **2,481 B** | **2,408 B** | **3,074 B** |

Six of nine runs carry it and three do not, split 1/3, 2/3 and 3/3 across the
slots — which on n = 3 per slot is not a separation at all. This is `rf2-rs8q6`'s
own originally-described recurring excess (median 2,640 B) reappearing as a
run-level bimodality, and it is **not** the rider: different magnitude, outside
the band, and the rider count at those same windows is 0–1 in every run
regardless. Nothing here chases it.

A future reader pooling nine runs by slot will see the same apparent effect. It
is an artefact of pooling three runs.

## What is left, and it is the stopping condition

**The 748 B term is still not named.** What this window bought is the last
separation the schedule can perform: the rider is carried by an arm window that
**follows the round's three control windows** — an arm dispatch after a run of
non-dispatching windows — and not by the round-opening position, which carries
nothing above background once the two are pulled apart.

That is a **localisation, not a name**. What allocates the 748 B is unidentified,
and the honest residue is the interaction: (A) alone reads 24.3% where (A) and (B)
together read 53.7%, while (B) alone reads background. No further re-ordering of
the schedule reaches that, because the schedule has now placed the controls in
all three positions a round admits.

Per the mayor's ruling, that is the stop. `rf2-e9wr`'s refusal to pin τ stands,
and `rf2-rs8q6`'s fence against discharging this by widening τ is restated rather
than relaxed.

## The caveat this line carries forward

On the floor arm subs and renders are **structural zeros** — the arm mounts no
subscription — so the work-count constancy behind this line rests on one counter
and is a window sum over seven legs rather than a per-leg reading. It does not
generalise to a subscribing arm, and nothing on this page claims it does.

## Reproduction

Producing tree: `6abd911252bddcc84df802e0dec64a4d096f2961`, the merge base of the
branch this page landed on. From the **repository root**, one run at a time — two
bench-class runs on one box wedge rather than fail:

```bash
P0_PORT=8481 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_ALLOC_CONTROL_SLOT=mid \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/ctrlslot-rs8q6/mid-1.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

`P0_ALLOC_CONTROL_SLOT` takes `first`, `mid` or `last`; omitting it is `first`.

Every measured figure on this page — every window and leg count, byte value,
percentage and `z` — is re-derived from the committed records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_position_confound.cjs \
  implementation/hicasso/test/re_frame/bench/hicasso/data/ctrlslot-rs8q6/*.json
```

**That reader is controlled.** Driven over the 14 committed `parity` runs it
reproduces the previous record exactly — 387 collection-free windows, 182 / 205 by
position, 101 + 11 rider legs, 3.908% / 0.184%, 4,284 control legs with zero in
the band — which is the control on the reader itself, unchanged by this window's
edits. Its own fixtures run under `--self-test` and in
`npm run test:script-helpers`, and they include the negative that matters here:
a reader whose window stream reset at a round boundary would report all six of
`last`'s round-opening windows as separated and manufacture exactly the result
this window tests for, so the fixture pins that count at 1.

### The `z` column, and the convention it is taken under

Every `z` on this page is a **two-proportion z-test on the pooled proportion**,
with no continuity correction and no conversion to a tail probability:

```text
p1 = k1/n1     p2 = k2/n2     p = (k1 + k2) / (n1 + n2)
z  = (p1 - p2) / sqrt( p (1 - p) (1/n1 + 1/n2) )
```

**The convention is written down because three of them land within half a z of
each other on these counts.** Worked on `last`'s 26 of 43 against 3 of 44, the
formula above gives **5.3070**, which is the published 5.31 and is what the
reader prints; the unpooled form gives 6.4106 and the continuity-corrected form
5.0796. Anyone re-deriving these figures under a different convention will land
near them and not on them, which is why the reader prints every `z` at four
decimals as well as at the published two.

The sign is `(first group − second group)`, the two taken in the reader's own
canonical order: the order its report already lists them in, ascending by
position and sorted by schedule name. So the sign belongs to the report and not
to whichever sentence cites it — which is why the `last`-against-`first`
comparison above is stated `first` first, where it would otherwise read +1.30.

The reader prints each comparison by name beside the two cell counts it was
taken on, so no figure here can drift from the counts behind it. Its fixtures
pin **all ten** z-scores across this page and the position record on those same
counts, as literals, **and require the unpooled and continuity-corrected forms
to miss every one of them** — so the pin discriminates rather than restates, and
a change to the formula reds `npm run test:script-helpers`.

### And the summaries the reader had not been emitting

The `--self-test` fixtures cover these too, so the claim above is a control and
not an assertion:

- the **per-run tables**, both of them, including the signed worst leg the
  pooling trap turns on — the reader now splits by browser launch rather than
  pooling;
- the **modal rider values and leg ordinals** per cell;
- the **prime excess by CELL rather than by position**, which is not a
  distinction without a difference: under `last` cell (A) holds 43 windows where
  position 0 holds 44, and their medians read 6,884 B and 6,882 B. This page's
  figure is the cell;
- the **control legs split by schedule** — 960 / 972 / 972 — where the reader
  previously reported only the pooled 2,904;
- and the position record's secondary 1,050–1,224 B cluster, which reads **8 of
  38** on the signed worst leg and 9 of 38 read as the largest *positive*
  excess. A fixture holds that difference.

### What that command does NOT print

Stated so the claim above is exact rather than generous. Four kinds of number on
this page are not figures of these nine runs and the reader does not emit them:
the **environment and producing tree** of the reproduction block, which is how
the runs were taken rather than something read out of them; the **seat and bead
identifiers**; the structural pin's **0 / 1 / 12**, which
`p0_ladder_structural.test.cjs` drives and which the page attributes to it where
it states them; and the **2,640 B** median in the pooling-trap section, which is
explicitly cited from this bead's own earlier corpus and is not a figure of the
runs here. Everything else on the page comes out of the command.

## The unit trap, restated because it has already misled two readers

`legWorstDeviation` is a **fraction**, not a percentage — `worst / legMedian` —
and it is **signed**, carrying the deviation furthest from the cohort median in
either direction. Both bite:

- Read as a percent it understates every figure 100×.
- A median over the signed field is not the published statistic; the magnitude is.

This page works in **absolute bytes** throughout for that reason. The rider is a
byte quantity (+748 B) and the sampler's own jitter is ±36 B, and neither is a
ratio.
