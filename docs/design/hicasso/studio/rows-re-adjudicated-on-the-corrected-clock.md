# The candidate's rows, re-adjudicated on the corrected clock

**The mount deficit is worse than published and the other three rows are still
refused.** On raw `TaskDuration` — the arm's own JavaScript *and* the frame it
causes — `hicasso / reagent-subs` on `M1` reads **1.4896×** [1.3488 – 1.5989] on
the ensemble that has standing, against the **1.2107×** the candidate's clock
publishes. This page's own seven runs, taken at a heavier load regime, read
**1.3737×** [1.3289 – 1.4331] and are published as a second regime rather than
as the magnitude, because the corroboration control they were pre-registered
against **failed** ([§5](#5-what-this-ensemble-may-not-say)).

**And the correction has a boundary nobody had drawn.** `rf2-yd52q` established
that `DevToolsCommandDuration` carries the page script a protocol command
invokes. It does — but only for script that runs **inside** the command, and the
harnesses in this programme do not all use the same door. The four
`page.evaluate`-driven rows were corrupted; the `keystroke` row and the whole of
the [outside cross-check](cross-checked-against-an-outside-instrument.md), which
drive through the Input domain, never were ([§2](#2-the-door)).

That boundary is what reconciles the three mount numbers, and it is a **third
defect in the same family as the two already found**: not a clock that measured
the wrong thing, but one label — `taskNet` — denoting two different quantities
on two harnesses, and two pages quoting them against each other as though it
denoted one ([§3](#3-the-three-mount-numbers)).

Owner: the operator-owned standard bead `rf2-2rtt6.1`; this page `rf2-emvod`.

---

## 1. What was re-adjudicated, and against what

[The candidate's clock](the-candidates-clock.md) states every magnitude on
`taskNet = TaskDuration − DevToolsCommandDuration`. `rf2-yd52q` showed that
subtraction removes the operation's own script, so each of those magnitudes is
style, layout and paint with the *work* taken out. The rows to settle were
`M1`, `bulk300`/`bulk100`, `narrow` and `keystroke`.

The instrument is unchanged in what it measures. What changed is which figure it
publishes and which gates adjudicate that figure:

| | before | now |
|---|---|---|
| published clock | `taskNet` | **raw `TaskDuration`** — script and frame |
| `taskNet` | the primary | reported as a **frame-only diagnostic**, labelled as superseded |
| arm-order guard | ran on `taskNet` only | runs on **both**; either refusal refuses the row |
| band ceiling | consulted `taskNet` | consults **both** |
| positive control | adjudicated on `taskNet` | must also hold on the **published** clock |
| the dataset | `taskNet` per-sample readings only | **all three windows**, so a run's published figures are recomputable from the file |

That last row is the [seam study's recorded durable-evidence
gap](the-candidates-clock.md#71-the-seam-study-rf2-cvvb7) arriving on this
instrument: the ensemble arithmetic is now a program,
`clock_readjudicate.cjs`, which reads the driver's own datasets and prints the
tables below. It does not select runs — every dataset handed to it appears, and
the reportable subset is shown *beside* the whole ensemble rather than instead
of it.

---

## 2. The door

`DevToolsCommandDuration` bills script that runs **synchronously inside a
protocol command**. `Runtime.callFunctionOn` — what `page.evaluate` compiles to
— owns the arm's whole `flushSync` write, so subtracting it removes the
operation. An Input-domain command does not: `Input.dispatchKeyEvent` and
`Input.dispatchMouseEvent` deliver an event, and the page's handler runs in an
input task the command does not own.

`ScriptDuration` reports the same partition from the other side, and the two
counters agree exactly. Pooled over the seven runs:

| harness and row | how the operation is driven | `ScriptDuration` on the arms | `devtools` spread across arms |
|---|---|---:|---:|
| `clock_run.cjs` — `M1` | `page.evaluate` | **0.063 ms** | 7.836 ms — **tracks the arm** |
| `clock_run.cjs` — `bulk300`, `bulk100`, `narrow` | `page.evaluate` | 0.029 – 0.045 ms | 0.56 – 3.53 ms — tracks the arm |
| `clock_run.cjs` — `keystroke` | `page.keyboard.press` | **49.97 ms** | **0.069 ms** — constant |
| `jsfb_ours_run.cjs` | `page.click` | 20.5 – 61.9 ms | **0.126 ms** — constant |

0.063 ms is what a **901-element cold mount** costs on the renderer's script
counter when the script is invisible to it. 49.97 ms is the keystroke control's
busy loop, seen in full, on the same instrument in the same run.

The absolutes say it a second way, and this is the arithmetic the driver now
prints on every row. On `M1`, per sample, pooled:

| arm | `task` | `taskNet` | in-page | `devtools` |
|---|---:|---:|---:|---:|
| `plumb` *(the tare)* | 0.613 | 0.201 | — | 0.412 |
| `floor` | 6.242 | 4.841 | 0.914 | 1.401 |
| `reagent-subs` | 10.535 | 4.728 | 5.336 | **5.807** |
| `hicasso` | **14.738** | 5.520 | **8.507** | **9.218** |

`devtools` tracks the in-page window arm for arm — 5.807 against 5.336, 9.218
against 8.507 — and `task − tare ≈ taskNet + in-page` closes to within a few
percent on every arm. **The two clocks are complements**, which is exactly what
`rf2-yd52q` found and is here in milliseconds on the mount row.

### 2.1 What the correction therefore does *not* reach

- **The `keystroke` row was never corrupted.** Its operation is a real key
  through the Input domain — the harness sends it that way because Event Timing
  reports user interactions — so `DevToolsCommandDuration` never held its
  script. Its two clocks agree to **0.3%** (1.0078× against 1.0049×), and no
  keystroke figure is restated.
- **The [outside cross-check](cross-checked-against-an-outside-instrument.md)
  was never corrupted either.** It clicks. Measured on that harness rather than
  inferred from this one: `run1k` reads 1.4030× on `taskNet` and 1.3913× on raw
  `TaskDuration` (−0.8%), `replace1k` 1.6265 against 1.5985 (−1.7%). Its
  published figures stand.

---

## 3. The three mount numbers

They are not three readings of one quantity, and that is the whole finding.

| reading | app | clock | `hicasso / reagent` |
|---|---|---|---:|
| ours, `M1` | 901 elements, cold mount | **frame-only** | ~~`1.2107×`~~ |
| ours, `M1` | the same | script **and** frame | **`1.4896×`** |
| ours, benchmark | ~8,000 elements, click | script **and** frame | `1.2789×` |
| theirs, benchmark | the same | wall clock, click → paint commit | `1.1756×` |

The first is not a member of the comparison. Removing it leaves two legs, each
holding one factor fixed:

- **workload**, our instrument throughout — `1.4896 → 1.2789`
- **instrument**, the same app throughout — `1.2789 → 1.1756`, **8.8%**, with a
  mechanism this programme already published: our window runs to
  `setTimeout(0)` and contains the post-paint macrotask where the candidate's
  disposals and reaper passes land; theirs ends at the paint commit.

**That the two legs multiply to the whole gap is arithmetic, not evidence** —
`a/b × b/c = a/c` holds for any three numbers — so it is not offered as a
verification. What carries the reconciliation is that the previously invisible
leg, the **clock definition**, is now *eliminated* rather than estimated, and
that the two that remain were each measured independently before this bead
existed.

**The workload leg is loose and is stated as loose.** Re-running the benchmark
app on our instrument for this page read `1.3913×`, which would put it near 7%
rather than 16.5% — but that run was taken against a bare `--dest` with the
benchmark's own stylesheet **absent**, so its layout and style costs are not
the published page's and its ratio is not comparable. It is reported for the
two-clock comparison it was run for. The honest bound: **workload ≈ 7–17%,
instrument 8.8%.**

### 3.2 An attempt to measure the instrument leg failed, and it is recorded

The instrument leg's mechanism — our window contains the post-paint macrotask,
theirs stops at the paint commit — is *named* on the cross-check page and
supported there by the `clear rows` decomposition, but it has never been
measured on the **mount** row, which is the row whose ratio is in dispute. This
page tried to measure it by reading the counters between the click and the
settle, splitting the operation near the boundary the two instruments differ
over.

**The split does not work and its output is incoherent.** On `run1k` it put
93.3% of `rf2-reagent`'s time after the mid-point and **1.2%** of
`rf2-hicasso`'s — which would mean the two arms do their work in opposite
halves of the same operation. The cause is that `page.click` resolves when the
event is *dispatched*, not when the renderer has finished with it, so the
mid-point metrics read — itself a protocol round trip — races the page's work
and lands at a different phase per arm.

The instrumentation was **removed rather than left in place with a caveat**, and
the attempt is recorded here because a reader who reaches for the same idea
should know it has been tried. What would measure it is a boundary the page can
report rather than one the driver guesses at: a `requestAnimationFrame` callback
that stamps `performance.now()`, or the `Commit` events in a real trace, which
is what the benchmark's own driver reads. **So the instrument leg stands at
8.8% with a named and independently-supported mechanism, and without a direct
measurement on this row.**

### 3.1 The published decomposition was arithmetic across two clocks

The cross-check page reported *"changing the workload moves the ratio by 5.6%;
changing the instrument moves it by 8.8%"*, from `1.2107` against `1.2789`.
Those are a frame-only reading and a script-and-frame reading. Its **instrument**
leg is sound — both sides of that one are script-and-frame — and its conclusion,
that the mount deficit is not a harness artefact, stands. What does not stand is
that the three readings *"span 1.18 – 1.28"*. On one clock they span
**1.18 – 1.49**.

---

## 4. The rows

Seven runs, `6 × (4 warm-up + 10 samples)` per arm per segment — the published
run 5 design, unchanged, so the two are comparable. Whole-run gates on all seven:
**arm-order guard reportable on both clocks**, canonical DOM **identical**
(27,224 bytes, 6 non-control arms, 3 segments), **0 unverified of 1,008** per row
(756 on `keystroke`), residue zero on every counter, **0 page errors**.

| row | `hicasso / reagent-subs`, raw `TaskDuration` | on `taskNet` | reportable runs | disposition |
|---|---:|---:|---:|---|
| **`M1` mount** | **1.3737×** [1.3289 – 1.4331] | 1.1143× | **7 of 7** | every run clears its own band (margins 32.9–43.3% against bands 4.9–10.8%) — but see [§5](#5-what-this-ensemble-may-not-say) |
| `bulk300` | 1.1494× [1.1102 – 1.2032] | 1.0703× | 3 of 7 | **refused** — control failed on 4, ceiling breached on 1 |
| `bulk100` | 1.1089× [1.0649 – 1.1545] | 0.9859× | 2 of 7 | **refused** — control failed on 5 |
| `narrow` | 1.0236× [0.9855 – 1.0900] | 1.0352× | 4 of 7 | **parity, instrument-limited** — every reportable run's margin is inside its band |
| `keystroke` | 1.0049× [0.9235 – 1.1192] | 1.0078× | — | **unadjudicated** — no proportional control; and the row needed no correction |

**The two update rows change sign.** `bulk100` reads 0.9859× on the frame-only
clock and **1.1089×** on the corrected one; `bulk300` goes 1.0703 → 1.1494. A
frame-only clock is reading the part of an update the two arms share — both
writes produce the same 901-element page — and diluting the part where they
differ toward 1.0. Neither is published as a magnitude, and the **direction** is:
on a broad commit the candidate is slower than Reagent-on-subs, by more than the
frame-only clock could see.

### 4.1 The predictions, and the one that was refuted

Registered in the driver's own pre-run block before the first run of the
ensemble.

| id | prediction | outcome |
|---|---|---|
| **E1** | the arm-order guard added on raw `TaskDuration` does not refuse a row the `taskNet` guard passes | **PASSED** — clean on both clocks, every row, all seven runs |
| **E2** | `ctl-2x` on raw `TaskDuration` reads within 2% of its `taskNet` value, so it **fails** the strict rule on the update rows and may pass on `M1` | **PASSED**, including the predicted failure: `M1` passed 7 of 7, the update rows 2–4 of 7 |
| **E3** | `narrow` moves **below** 1.0 on the corrected clock | **REFUTED.** It reads 1.0236× over the ensemble and 1.0036× over the reportable subset — parity, not a win |
| **E4** | `bulk300` and `bulk100` move **above** 1.0 | **PASSED** — 1.1494× and 1.1089×, the latter a sign change |
| **E5** | the two clocks differ **largely** on the `page.evaluate` rows and **little** on `keystroke` | **PASSED** — 23–27% against **0.3%** |

**E3 is recorded as refuted rather than widened.** It was written on the
strength of the outside instrument's partial-update row, where the candidate is
24–28% faster than Reagent-on-subs. That row dirties 100 of 1,000 table rows;
`narrow` dirties **1 of 300 boundaries**. The prediction assumed those were the
same experiment and they are not, which is the prediction's fault and not the
row's. What `narrow` says on the corrected clock is what
[the clock page](the-candidates-clock.md#6-the-three-rows-this-page-refuses)
already said qualitatively: **all three substrates localise a one-cell write and
the candidate is neither better nor worse at it.**

### 4.2 `ctl-2x` undershoots on the mount row too, and that changes its diagnosis

`ctl-2x` builds exactly twice the floor's page and reads **1.73–1.82×** rather
than 2.00×, on every row, on both clocks. The standing diagnosis
(`rf2-7iqb5`) is that a page-doubling control is mis-specified for an *update*
row, whose work does not double with the page. That diagnosis predicts the mount
row should be clean. **It is not** — `M1` reads 1.8173×.

An additive per-sample cost `c` that the tare does not remove explains all five
at once, because a ratio of two arms one of which is twice the other reads
`(2W + c) / (W + c)` — below 2 for any positive `c`, and indifferent to whether
the row is a mount or an update. Inverting it:

| row | `ctl-2x` on `task` | implied `c/W` | implied `c` | floor, tared |
|---|---:|---:|---:|---:|
| `M1` | 1.8173 | 0.224 | **1.040 ms** | 5.695 ms |
| `bulk300` | 1.7334 | 0.364 | **1.043 ms** | 3.911 ms |
| `bulk100` | 1.7696 | 0.299 | **0.873 ms** | 3.788 ms |
| `narrow` | 1.7796 | 0.283 | **0.790 ms** | 3.584 ms |

**0.79–1.04 ms across four rows that do wildly different work** — a 901-element
cold mount and a one-cell write — on floors spanning 3.58 to 5.70 ms. A single
additive constant fits all four; "mis-specified for updates" fits none of the
mount row.

So `rf2-7iqb5`'s repair is **necessary and not sufficient**, and this page did
not attempt it. A control that doubles the changed set at fixed page size
removes the update-row confound and leaves `c` exactly where it is, because
`(2D + c) / (D + c)` has the same shape. What removes `c` is a **three-point**
control — dirty ε, dirty `D`, dirty `2D` — adjudicated as
`(T(2D) − T(ε)) / (T(D) − T(ε))`, which differences the constant away. That is a
new arm in the page half of the harness, which would have changed the blobs this
ensemble was taken at, so it is filed rather than done here.

---

## 5. What this ensemble may not say

**The corroboration control failed, and it was pre-registered as the one that
decides whether this instrument may restate anything.** The same donor bar the
published record puts at `M1 mount 1.0150×` [0.9820 – 1.0480], and which
`rf2-yd52q` reproduced at `1.0011×`, reads **0.9394×** [0.8958 – 0.9837] here —
outside the published interval on the ensemble mean and on five of seven runs.

The box explains it and does not excuse it. `rf2-cvvb7`'s nineteen-run load
ladder established the **absolute floor** as this lane's load indicator and swept
it from 3.06 to 5.50 ms across 0 to 20 competing cores. This ensemble's `M1`
floor ran **5.98 – 6.95 ms** — *above the entire ladder*, including its most
saturated rung. A sibling worker was running the fast-PR spine on the same box
for part of the window. So these runs were taken **outside the regime the band
was calibrated in**, and a run outside its calibration is not rescued by its
gates passing.

> **THE ARITHMETIC IN THIS PARAGRAPH IS WRONG, AND THE CONCLUSION SURVIVES IT
> ON OTHER GROUNDS (`rf2-ymi6j`).** The two figures are on **two clocks**: the
> 5.98–6.95 ms is raw `TaskDuration` and this page's own table above gives that
> `M1` floor as `task` 6.242 against `taskNet` **4.841**, while the ladder's
> 3.06–5.50 ms range is `taskNet` throughout. Subtracting this page's own
> 1.401 ms gap puts the ensemble's floors at **≈4.58 – 5.55 ms on the ladder's
> clock** — *inside* the calibrated range, its top level with the top rung. (It
> also holds an `M1` floor against a `bulk300` range.) And
> [the re-taken ladder](the-band-re-calibrated.md#3-the-band-is-widest-on-an-idle-box)
> finds the band **widest on an idle box**, `corr(band, floor) = −0.49`, so a
> high floor is not the direction in which the instrument stops reproducing.
>
> **What still stands is the refusal itself.** The corroboration control failed,
> that is a pre-registered gate, and a failed gate refuses the ensemble whatever
> the box was doing. The box remains a *candidate* explanation — a sibling
> worker really was running the spine — but "outside the calibrated load regime"
> is not the mechanism, and this page's decision to publish `1.3737×` as a
> second regime rather than as the magnitude is unaffected.

Therefore:

- **The published `M1` magnitude is `rf2-yd52q`'s `1.4896×`** [1.3488 – 1.5989],
  whose corroboration control passed at `1.0011×` on a quiet box.
- **This ensemble's `1.3737×` is published as a second regime**, not as the
  magnitude, and its agreement with `1.4896×` in direction and rough size is
  what it contributes.
- One arithmetic observation, flagged and **not used**: scaling `1.3737×` by the
  donor bar's own offset would land near `1.484×`. That is exactly the
  after-the-fact rescaling this lane refuses, it is recorded because suppressing
  it would be worse, and **no row above is stated on it.**

**One run was discarded and the reason is a pre-condition rather than a result.**
Run 2 of the eight overlapped a busy-wait loop this page's author started by
mistake and killed about a minute later. It is excluded on that ground alone;
its numbers were never inspected before excluding it. Seven runs remain.

**Run 8 breached the band ceiling** at **26.2%** on `bulk300` and 28.4% on
`narrow`, against the 25% tripwire — the second and third firings that gate has
ever had, after
[its first at 26.2%](bulk-broad-re-taken.md#8-the-earlier-ensemble). It
contributes no magnitude to those two rows and the driver refused them before
any control was consulted.

> **THE TRIPWIRE WAS THE PROBLEM (`rf2-ymi6j`).** A re-take of the ladder that
> set it measures `P(band > 25%)` at **2.6–9.0% per run**, so three firings in
> two days is the rate rather than an event, and the ceiling is now **35%**, set
> from the statistic's own bootstrap q99. Run 8 would today be *adjudicated*
> rather than refused — and **no magnitude changes**, because `bulk300` is
> refused by `rf2-7iqb5`'s three-point control and `narrow`'s 2–9% margin is
> inside its own 28.4% band at any ceiling.

---

## 6. What is refused, and what is not

**Refused as magnitudes:** `bulk300`, `bulk100`, `narrow`, `keystroke`, and —
on this ensemble — `M1`.

**Not refused, because they do not depend on a magnitude or on this box:**

- **The door.** Which counter holds which script is a structural fact about
  Chromium's accounting, measured here at 0.063 ms against 49.97 ms of
  `ScriptDuration` in one run. Load does not move it.
- **The two clocks are complements**, in milliseconds, on the mount row.
- **The direction of every row.** `M1` and both bulk rows above 1.0 in every
  one of seven runs; `narrow` at parity in every one; `keystroke`
  indistinguishable.
- **`ctl-2x`'s undershoot is additive**, and the mount row proves the standing
  diagnosis incomplete.
- **`taskNet` should stop being reported as a clock.** It is a frame-only
  reading on four of five rows and an honest one on the fifth, which makes it a
  label that means two things. It stays in the driver's output as a labelled
  diagnostic — the gap between the two windows is how the door is measured — and
  it is no longer a figure any row is stated on.

---

## 7. Provenance

Blob hashes rather than commit SHAs, because a rebase has already invalidated
one publication in this programme. **The page half is unchanged from run 5, the
audit's runs and `rf2-yd52q`'s ensemble**, which is what makes the four sets of
readings comparable.

| file | blob | |
|---|---|---|
| `clock_app.cljs` | `15c4d3b1dd770c7cea3f2efa7aca4a343c55d34a` | unchanged from run 5, the audit and `rf2-yd52q` |
| `clock_views.cljs` | `7e48dbc0b3a974cd61a5c61e606333848877a31f` | unchanged |
| `seam.cjs` | `a6789197e1bd9744879a2c8a143e48dc643b7f26` | unchanged from `rf2-cvvb7` |
| `clock_run.cjs` | `84aa25d93b65ee55f3d28d339d57720e3a504da3` | **this page's driver** — every run of the ensemble |
| `clock_readjudicate.cjs` | `eeba10e24bef0c23e0547cc9e91735cf274eb9be` | the ensemble arithmetic, which produced every table above |
| `jsfb_ours_run.cjs` | `10e6e2fa80a98522694468934b1385eeed5b2a8c` | the cross-check probe in [§2.1](#21-what-the-correction-therefore-does-not-reach) |
| `jsfb_serve.cjs` | `c198a54c42a28557b99b36f650ffc744ee32e6c4` | serves the built arms without the upstream clone |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
git rev-parse <candidate>:$P   # must print 84aa25d93b65ee55f3d28d339d57720e3a504da3
```

| | |
|---|---|
| Published ensemble | **7 runs**, all five rows in each, all at the blob above, 2026-08-02 03:16–03:46 AUSEST |
| Discarded | **one run, and it is named** — run 2 of eight, on the pre-condition in [§5](#5-what-this-ensemble-may-not-say) |
| Runtime | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64, Playwright), React 19.2.0, node v24.13.0, `hardware-concurrency` 24, `device-memory` 32 |
| Build | `:hicasso-bench`, `:advanced`, `goog.DEBUG false`, via `--config-merge` only — `implementation/shadow-cljs.edn` untouched |
| Box | **NOT quiet, and the row that says so is the floor.** Sampled 18–51% CPU before the ensemble; `M1` floor 5.98–6.95 ms against `rf2-cvvb7`'s 3.06–5.50 ms ladder range. A sibling worker held the fast-PR spine for part of the window |
| Exit code | **1** on six runs, scoped to a control; **0** on run 3 — the first fully clean five-row run this instrument has produced. Every whole-run gate cleared on every row of every run |

```bash
cd implementation && npm ci
node freehand/test/re_frame/bench/hicasso/clock_run.cjs           # one run, all five rows
node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs out/run*.json
```

The cross-check probe in [§2.1](#21-what-the-correction-therefore-does-not-reach)
and [§3](#3-the-three-mount-numbers) was taken with `jsfb_ours_run.cjs` at the
blob it landed at in this change, against arms built by `jsfb_build.cjs` and
served by `jsfb_serve.cjs` from a bare `--dest`. **Its stylesheet was absent**
and the server says so at startup; it certifies no ratio and ran without the
`create10k` positive control, which its own output states.
