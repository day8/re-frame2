# A control that differences the instrument's own constant away

**The repair `rf2-emvod` ruled was built exactly as ruled, and it refuses.** The
three-point control — dirty ε, dirty *D*, dirty *2D* at fixed page size,
adjudicated as `(T(2D) − T(ε)) / (T(D) − T(ε))` — reads **1.4280× / 2.1722× /
1.6014×** on `bulk300` / `bulk100` / `narrow` against a prediction of **2.0101×**
registered before the run. Under the strict rule that is a refusal on all three,
and **bulk is still not adjudicable**.

**The arithmetic is not what failed.** Run the same statistic on `LayoutDuration`
alone — the half of a commit that *must* scale with the dirty set — and it reads
**1.9703× / 2.1218× / 1.9889×**, within 5.5% of its prediction on every row. The
additive constant really does cancel; the construction really does remove what
was wrong with `ctl-2x`. What refuses it is the page: about 6 µs per dirty cell
of **non-layout** cost exists over `[1, 100]` and has gone by `[100, 200]`, which
is paint saturating once the damage region covers the viewport
([§3](#3-what-refuted-it-measured-three-ways)).

**A saturating term is not a constant, so no differencing removes it** — and the
second thing this page records is that the instrument's noise floor forbids
moving the control anywhere it would not matter
([§5](#5-the-condition-this-instrument-does-not-meet)).

The control is nonetheless real, and it is shown **refusing** under a
falsification in which every other gate passes
([§6](#6-the-control-is-shown-failing)).

Owner: the operator-owned standard bead `rf2-2rtt6.1`; this page `rf2-7iqb5` and
`rf2-5xrcd`.

---

## 1. What was wrong with the control this replaces

`ctl-2x` is the floor at twice the boundaries, against the floor, predicted
2.00×. Over `rf2-emvod`'s seven runs it read **1.8173×** on the `M1` *mount* row
and 1.7334 / 1.7696 / 1.7796× on the three bulk rows — every row short by 9–13%.

`rf2-5xrcd`'s diagnosis was that doubling the **page** does not double an
**update**'s work. That is true, and it does not reach the mount row, where the
work does scale with the page. One additive constant fits all four at once: a
ratio of two arms one of which is twice the other reads

```
(2W + c) / (W + c)
```

which is below 2 for any positive `c` and does not care what kind of row it is.

`rf2-7iqb5`'s filed repair — double the **changed set** at fixed page size —
removes the update-row confound and leaves `c` exactly where it was, because
`(2D + c)/(D + c)` has the same shape. It would have produced a differently wrong
number. What removes `c` is not a better two-point ratio but a **difference of
differences**, in which `c` is neither estimated nor bounded:

```
R₃ = (T(2D) − T(ε)) / (T(D) − T(ε))  →  (2D − ε) / (D − ε)
```

Both perturbations this lane has measured die in it. An **additive** per-sample
constant — protocol round trip, settle, React's whole-tree reconciliation walk,
the document's pre-paint — cancels in each difference whatever its size. A
**multiplicative** block-level perturbation, which is what `rf2-cvvb7`'s
nineteen-run load ladder found ambient load to be, cancels in the quotient.
`ctl-2x` survives the second only.

### The epsilon arm dirties one cell and not zero

Zero would make the prediction exactly 2.00 instead of 2.0101, which is tidier
and wrong. A commit that changes nothing produces no style recalculation, no
layout and no paint, so `T(0)` would omit the fixed cost of **producing a dirty
frame at all** — a constant `T(D)` and `T(2D)` both pay. Differencing against it
would leave that constant in both halves of the quotient and push the reading
below 2: the defect being removed, reintroduced by the arm meant to remove it.

So the prediction is `(2D − ε)/(D − ε)` and the driver derives it from the page's
own declared counts rather than carrying a literal.

### Which door the arms go through

All three arms are driven through `page.evaluate → HCLOCK.sample` — the same door
as the floor, `ctl-2x` and every substrate arm on a bulk row. That is
load-bearing rather than incidental. `rf2-emvod`'s third defect was that
`DevToolsCommandDuration` bills page script only when the script runs *inside* a
protocol command, so the same subtraction is frame-only through `page.evaluate`
and script-and-frame through `page.click`. A control whose arms went through two
different doors would be differencing two different quantities. These go through
one, so whatever the door costs is common-mode, additive, and cancels with
everything else constant.

## 2. The additive constant, re-measured rather than quoted

`rf2-emvod` inferred `c` by inverting the doubling control. That inference is
exact and has no free parameter: if `floor = W + c` and `ctl2x = 2W + c`, their
measured ratio `R` gives

```
c = floor_tared × (2 − R)
```

The driver now computes it on every run from that run's own readings. Reproduced
here, against `rf2-emvod`'s published figures:

| row | floor tared | `ctl-2x`/floor | `c(2x)` this run | `c` published by `rf2-emvod` |
|---|---|---|---|---|
| `bulk300` | 2.9772 ms | 1.6647× | **1.0397 ms** | 1.043 ms |
| `bulk100` | 2.6950 ms | 1.7554× | **0.6763 ms** | 0.873 ms |
| `narrow` | 2.7731 ms | 1.6771× | **0.9470 ms** | 0.790 ms |

`bulk300` reproduces to three decimal places on an independently taken run. The
constant is real, it is 25–35% of a floor sample, and it is the whole of why a
doubling control cannot read 2.00.

The inversion is **degenerate as `R → 2`**, which is worth stating: a doubling
control that passed cleanly would recover `c = 0` and say nothing. `ctl-2x` was
never able to measure the thing that was wrong with it. It is also degenerate the
other way — a block whose `R` overshot 2.0 returns a *negative* `c`, which is not
a small constant but a statement that the model does not apply to that block, and
the driver withholds any verdict that would rest on one.

## 3. What refuted it, measured three ways

### The marginal cost per dirty cell is not constant

Six rounds × three segments × 20 samples, box quiet:

| row | `[1, 100]` | `[100, 200]` | disagreement |
|---|---|---|---|
| `bulk300` | 9.348 µs/cell | 3.124 µs/cell | 99.7% |
| `bulk100` | 7.654 µs/cell | 4.512 µs/cell | 51.7% |
| `narrow` | 7.434 µs/cell | 4.084 µs/cell | 58.2% |

The statistic **is** that disagreement: with near-equal spacing `R₃ = 1 + Δ₂/Δ₁`,
so the ±25% band is exactly a 50% tolerance on the marginal cost moving between
the two intervals. It moves by more.

### The same statistic on `LayoutDuration` holds

`LayoutDuration` is collected per block for this purpose — the driver's existing
decomposition reports it as a pooled per-arm mean, and a pooled mean cannot be
adjudicated because a control is a per-block statistic.

| row | `ctl-2x` | three-point on `task` | three-point on `LayoutDuration` | layout marginals |
|---|---|---|---|---|
| `bulk300` | FAIL 1.6545× | **FAIL 1.4280×** | 1.9703× [1.4442 – 2.3451] | 3.543 → 3.354 µs/cell |
| `bulk100` | PASS 1.7765× | **FAIL 2.1722×** | 2.1218× [1.6351 – 2.6791] | 3.167 → 3.443 µs/cell |
| `narrow` | FAIL 1.6625× | **FAIL 1.6014×** | 1.9889× [1.7099 – 2.7500] | 3.321 → 3.186 µs/cell |

Layout's marginal cost is flat to within 8% on every row and the statistic lands
within 5.5% of 2.0101 on all three. This is the line that decides whose fault a
refusal is, and it says: **the page, not the clock.**

### The mechanism

Dirtying cells `0…d−1` damages a region that grows with `d` only until it covers
the **viewport**. Past that the extra dirty rows are laid out but never painted.
The viewport holds a few tens of rows, so paint is still growing at `d = 1` and
has stopped by `d = 100` — which is the shape in both tables. Roughly 6 µs/cell
of non-layout cost over `[1, 100]`, ~1 µs/cell over `[100, 200]`.

A saturating function of the very axis the control varies is **not a constant**,
so differencing does not remove it.

## 4. A bigger page was tried and did not rescue it

Moving the three points above the knee leaves `[100, 300]` on a 300-cell page, so
the widest spacing is 100 cells — 0.60 ms of signal. Adjudicated at 100/200/300
the same dataset put only **7 of 18** blocks inside the band, the denominator
collapsing to 0.10 ms in one of them. A control that refuses because its
denominator went to noise has not caught anything.

So the control was rebuilt on a **3,000-boundary page of its own**, points at
1,000 / 2,000 / 3,000, on the reasoning that signal scales with the page while
harness jitter does not. Both halves of that are wrong, and the run is recorded
rather than discarded:

* **the constant scales with the page too**, because most of it is React's
  whole-tree reconciliation walk. `c(3pt)` came out **7.93 ms** on the 3,000-cell
  page against 1.24 ms on the 300-cell one, so `T/ΔT` — the factor by which a
  difference amplifies relative noise — barely moved;
* **the arms got noisier, not merely bigger.** Within-block IQR over the raw
  samples ran 30–48% of the median on the 3,000-cell arms against 28% on the
  floor, an implied standard error of the median of 1.7–2.0 ms against the
  floor's 0.33 ms. Every sample allocates a 9,001-element React tree and the
  garbage lands inside the window.

The result was a per-block ratio scattered over `[0.09, 9.63]`, with the
denominator going **negative** — `T(2000)` reading below `T(1000)` — in one block
of eighteen. Worse conditioned than the build it was meant to rescue, and it
perturbs the page under test ten times as hard, so the 300-cell construction is
what ships.

That choice also keeps a guarantee the bigger page had to give up: at 300 cells
the control's arms build the **floor's own page**, so the canonical-DOM fairness
gate checks them against every substrate arm instead of exempting them. All 15
non-control arms hashed identically on every row of the published run. Choosing
the dirty set as the axis is what bought that, and it is the one part of
`rf2-7iqb5`'s filed repair that survives intact.

## 5. The condition this instrument does not meet

A difference of differences amplifies relative noise by `T/ΔT`. With equally
spaced points and slack `s`, the control can hold only if a block's own reading
satisfies roughly

```
σ(T)/T  ≤  s / (2 √2 · T/ΔT)
```

For `s = 0.25` and the measured `T/ΔT ≈ 2.5` that is about **3.5%**. On this
instrument the within-block IQR of the raw samples is **28–48%** of the median
and the median's own standard error is ~9% of the floor. That is an order of
magnitude short. It is the same number the 13.8–20.1% band reports from the other
direction, and — this is the part that matters — **it does not depend on which
three points are chosen or how large the page is**, because both `T` and `ΔT`
scale with the page together.

So the refusals in [§3](#3-what-refuted-it-measured-three-ways) have two
independent causes stacked on one another: a page whose cost is not affine in the
dirty set, and a noise floor that would forbid a strict per-block verdict even if
it were.

## 6. The control is shown failing

A control nobody has seen refuse is a control of unmeasured sensitivity, and this
lane has found that defect repeatedly. Two demonstrations ship with it.

**Live.** `HCLOCK_CTL3_SABOTAGE=140` makes the *2D* arm render 140 cells while
still **declaring 200** to the driver, so the prediction stays 2.0101×. The
control refused at **1.1309×** [0.804 – 1.546] — and everything else passed: the
arm-order guard reportable on both clocks, **0 unverified of 3024** writes (the
probes follow the actual dirty set, and a cold cell is probed as well as two hot
ones), canonical DOM identical across all 15 non-control arms, band 10.9%. The
control was the only gate that fired, which is the whole claim.

**Offline.** `node clock_run.cjs --selftest` runs eleven fixtures with no browser
and is fatal in every run. They include: superlinear work refuses; an arm
declaring 200 while rendering 140 refuses; a degenerate denominator refuses
rather than passing quietly; one nonlinear block in nine refuses, so eight good
blocks cannot vouch for it; a large additive constant does *not* move the
statistic; a multiplicative block perturbation does not either; and the doubling
control is biased low on a world the three-point one reads exactly.

### What it cannot catch, asserted rather than described

At points 1 / 100 / 200 a power law `d^k` reads `(200^k − 1)/(100^k − 1)`, so the
control refuses below about `k = 0.55` and above about `k = 1.33`. That span is
asserted as a fixture.

The comparison worth recording: an **equally spaced** 1 : 2 : 3 design reads
`(3^k − 1)/(2^k − 1)`, which never falls below `ln 3 / ln 2 = 1.585` — inside the
band — and therefore **could not refuse any sublinear workload, however
sublinear**. A saturating paint term is exactly a sublinear shape. The ruled
point placement, which was refuted first, is the one that can see the thing that
refuted it. That is why it is what ships.

## 7. Is bulk adjudicable?

**No, and the reason has moved.** It was previously blocked because the only
available control was mis-specified. It is now blocked because a correctly
specified control **refuses**, for two measured reasons that are properties of
the page and the instrument rather than of the control.

Everything else about the published run is clean, which is what makes the refusal
worth trusting: arm-order guard reportable on both clocks on all three rows, 0
unverified of 3024 writes per row, canonical DOM identical across 15 non-control
arms, bands 20.1 / 13.8 / 15.7% (taskNet), all
under the 25% ceiling — 16.1 / 12.3 / 17.5% on the published `TaskDuration`
clock. **No bulk magnitude is published from these runs**, and the figures the
run printed are deliberately not repeated here.

Two further constraints on any future attempt:

* **`M1` is untouched.** A mount row's operation *is* the mount, so it has no
  standing page and no changed-set axis. `M1` keeps `ctl-2x` and keeps its known
  1.8173× undershoot, so the programme's headline mount figure remains stated
  under a control that fails. That is a separate gap and it is filed as one.
* **Regime, and it is outside the calibrated one.** `rf2-h8o80` warns the band
  may not extrapolate outside its calibrated load regime. The floors here are
  **2.70–2.98 ms** tared, which is **below** the 3.06 ms bottom of `rf2-cvvb7`'s
  3.06–5.50 ms nineteen-run ladder — a quieter box than any rung that ladder
  calibrated, not a rung inside it. That is the opposite end from `rf2-emvod`'s
  problem, whose `M1` floor ran 5.98–6.95 ms above the top, and it is the benign
  direction: the failure mode the ladder warns about is a box too loud to
  reproduce itself. It is still an extrapolation, it is still stated rather than
  assumed harmless, and the precedent for reading a bulk floor against that range
  is [Bulk broad, re-taken](bulk-broad-re-taken.md), which recorded 2.37–2.44 ms
  the same way.

## 8. Provenance

| artefact | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/clock_app.cljs` | `703f5074e14839460984542597e031bd632662b4` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs` | `46b34911fd4e9a70d218dadaa503731e29e4fa52` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_views.cljs` | `7e48dbc0b3a974cd61a5c61e606333848877a31f` |

These are the blobs **that produced the figures above**, and they are what a
reproduction needs. Both harness files have since taken **comment-only**
corrections — an over-stated negative-denominator count, a stale comment block
left from the abandoned 3,000-cell design — and now hash
`c5e8801569715e27c5a1ecb543badb88182063fc` and
`0d54998103649cd8479d3a73eca9e8745a026a8c`.
Behaviour is unchanged and the adjudicators' fixtures were re-run against them,
but the table above names the blobs that **ran** rather than the blobs that
**ship**, because those are different questions and only the first is
provenance.

Commit `ef30c639162db98492cf0ebb53f00d411deb4bc4` — authored, and rebase-merged,
so it is on no branch and **will not resolve in a fresh clone**. It landed on
main as **`93ad80f097`** (same patch — identical `git patch-id --stable`), with
the blob it contributed unchanged; check out the landed SHA, which sits on a
later base and so carries the change rather than the whole measured tree.
Chromium 147.0.7727.15
(Playwright), `:advanced`, `goog.DEBUG` false. Design 6 rounds × (4 warm-up + 20
samples) per arm per segment, three segments, segment order rotating with the
round. Box quiet, 24 logical cores.

Reproduce the published run:

```bash
HCLOCK_ONLY=bulk300,bulk100,narrow HCLOCK_SAMPLES=20 \
  node implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
```

Reproduce the falsification, which exits 1 naming the control:

```bash
HCLOCK_ONLY=bulk300 HCLOCK_SAMPLES=20 HCLOCK_CTL3_SABOTAGE=140 \
  node implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
```

Reproduce the adjudicators' fixtures, with no browser and in under a second:

```bash
node implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs --selftest
```

Every figure on this page is recomputable from the dataset the run writes when
`HCLOCK_JSON` is set: it carries `roundsTask`, `rounds`, `roundsLayout` and
`inPageRounds` per block, the control's per-block terms and marginals, the page's
**declared** plan, and what the 2D arm actually rendered when the falsification
knob was on.
