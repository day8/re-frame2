# The candidate's rows, re-adjudicated on the corrected clock

**The mount deficit ~~is worse than published~~ reads larger on the corrected
clock than on the clock it replaced, and the other three rows are still
refused.** On raw `TaskDuration` — the arm's own JavaScript *and* the frame it
causes — `hicasso / reagent-subs` on `M1` reads **1.4896×** [1.3488 – 1.5989] on
~~the ensemble that has standing~~ the ensemble this page was written against,
against the **1.2107×** ~~the candidate's clock publishes~~ the candidate's
clock read before the correction *(2026-08-06, `rf2-jcm3p`: that page publishes
no mount magnitude either — both readings are annotated there as historical
observations stated under a failing `ctl-2x`)*. This page's own
seven runs, taken at a heavier load regime, read **1.3737×** [1.3289 – 1.4331]
and are published as a second regime rather than as the magnitude, because the
corroboration control they were pre-registered against **failed**
([§5](#5-what-this-ensemble-may-not-say)).

~~**AND NEITHER MOUNT FIGURE IS A MAGNITUDE ANY LONGER (`rf2-jcm3p`, ruled
2026-08-06).**~~ **`M1` PUBLISHES A MAGNITUDE AGAIN, CONDITIONALLY LABELLED
(`rf2-t2flm`, ruled 2026-08-07 — [§4.3](#43-the-published-m1-row)).** The
regime-only statement is **superseded**, and the paragraph below is kept because
the reasoning that fell is part of the record.

~~`M1`'s own positive control fails — `ctl-2x` reads **1.8173×**
against a predicted 2.00× on this very ensemble
([§4.2](#42-ctl-2x-undershoots-on-the-mount-row-too-and-that-changes-its-diagnosis)),
reproduced at 1.8443× and 1.8567× on two verifiably idle boxes — and a mount row
has no changed-set axis a control could be linear in, so no control can
adjudicate it. The strict rule that refuses `bulk300` and `bulk100` below binds
on `M1` too, and the row is restated as a **regime**: *Hicasso mounts materially
slower than both adapters, every corroborated reading is above the amended
`≤ 1.10×` UIx gate, and `≤ 1.10×` has not been demonstrated.*~~ **What that
argument got wrong is the rule it attributed to the instrument**: `controlVerdict`
never required `ctl-2x`'s *mean* to reach 2.00×, it requires every one of 18
blocks inside `2.00× ± 25%`, and on two retained quiet-box ensembles **7 of 14
runs pass it on both clocks**. `rf2-8a746` supplies the mechanism that makes those
passes predictable rather than lucky. The row now publishes **`~1.184×` against
direct UIx-on-subs** and two ensemble-specific estimates against Reagent-on-subs,
under the labels [§4.3](#43-the-published-m1-row) states — reportable-subset
conditional, with the control yield and the raw quotient beside every figure.
**That conditioning rule has since been retired and the row re-adjudicated
under a calibrated mount check standard** *(2026-08-08, `rf2-x7x10`:
`14 of 14` runs in control rather than 7 of 14, so the reportable subset is the
whole ensemble; the figures move to the whole-ensemble column already tabled,
and `rf2-8a746`'s publication rule — reaching this row for the first time —
returns `INSTRUMENT-LIMITED` on every pair. The magnitudes are **held pending a
ruling**, not withdrawn — [§4.3](#43-the-published-m1-row) carries the whole of
it.)*
Both historical figures stay on this page as dated observations, annotated with
the control status they were taken under; nothing is erased. The superseded
restatement is on
[the clock page's §4](the-candidates-clock.md#4-the-mount-row--a-regime-not-a-magnitude).

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

**The subset that program draws is now the driver's whole exit path, and it
fails closed** *(2026-08-07, `rf2-emvod`)*. It asked for the positive control
and the two ceilings and nothing else, while printing four further verdicts it
then ignored; and the driver writes its dataset *before* its own fatal checks
run, so a run the page threw on was a well-formed file that reached the
published mean. What that repair does and does not reach — including the
datasets this page's own tables were computed from, which **were never
retained** — is [§7.1](#71-what-this-page-cannot-regenerate).

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
(756 on `keystroke`), residue back to baseline on every counter, **0 page
errors**. `body-children` reads **2** throughout — the page's own `#app` and its
script tag, constant, not residue — and every other counter is zero; a row
reading 3 would be a container that survived its unmount *(2026-08-06,
`rf2-moe9a`: this said ~~zero~~ on every counter, the same wrong baseline
[the clock page](the-candidates-clock.md#3-the-design) carried)*.

| row | `hicasso / reagent-subs`, raw `TaskDuration` | on `taskNet` | reportable runs | disposition |
|---|---:|---:|---:|---|
| **`M1` mount** | **1.3737×** [1.3289 – 1.4331] *(historical — see §4.3)* | 1.1143× | **7 of 7** | ~~every run clears its own band (margins 32.9–43.3% against bands 4.9–10.8%)~~ ~~**REGIME, no magnitude** *(2026-08-06, `rf2-jcm3p`)*~~ **A MAGNITUDE AGAIN, CONDITIONALLY LABELLED** *(2026-08-07, `rf2-t2flm` — [§4.3](#43-the-published-m1-row))*: `~1.184×` against direct UIx-on-subs, `1.1402×` / `1.1100×` against Reagent-on-subs, on the two **retained** ensembles and under the labels §4.3 states. `1.3737×` in this table is **this** ensemble's own reading and stays as a dated historical observation — taken at a heavier load regime, and no figure in §4.3 derives from it. See also [§5](#5-what-this-ensemble-may-not-say) |
| `bulk300` | 1.1494× [1.1102 – 1.2032] | 1.0703× | 3 of 7 | **refused** — control failed on 4, ceiling breached on 1 |
| `bulk100` | 1.1089× [1.0649 – 1.1545] | 0.9859× | 2 of 7 | **refused** — control failed on 5 |
| `narrow` | 1.0236× [0.9855 – 1.0900] | 1.0352× | 4 of 7 | **parity, instrument-limited** — every reportable run's margin is inside its band |
| `keystroke` | 1.0049× [0.9235 – 1.1192] | 1.0078× | — | ~~**unadjudicated** — no proportional control~~ **DIAGNOSTIC — UNADJUDICATED, never a magnitude** *(2026-08-06, `rf2-swwud`: the row is adjudicated by Event Timing instead — indistinguishable at one frame, on the two retained runs)*; and the row needed no correction |

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

### 4.3 The published M1 row

> **RULED AND PUBLISHED 2026-08-08 (`rf2-diaud`). THIS BLOCK IS THE LIVE ROW;
> everything below it is the record of how it got here.** The magnitudes held by
> the `rf2-x7x10` block that follows are **unheld** — and restated, because the
> ruling found that they could not have published as they stood.
>
> **The defect was not a wrong number. It was two estimators in one figure.**
> `validation.md` defines canonical `M1` as **floor-normalised** on the clock of
> record; the effect-size interval was computed on the **level ratio that
> touches neither floor**. Both are correct arithmetic and they are not the same
> quantity. The publication proposed below spliced floor-normalised *points*
> onto unfloored geometric *intervals*, and a point and an interval must
> describe the same estimand. **No spliced figure ever publishes.**
>
> **So the tool now computes K1's own estimand and takes the point and the
> interval from that one estimator.** Per round, per segment, tared:
>
> ```
> ((H − plumbH) / (floorH − plumbH)) ÷ ((U − plumbU) / (floorU − plumbU))
> ```
>
> through the same run-preserving bootstrap — outer runs resampled before inner
> rounds, 4,000 draws, fixed seed `20260807`.
>
> **And the threshold is the mount's own.** `rf2-8a746`'s whole-interval
> discipline is retained with no exceptions, but `1.0` and `1.5` are the *bulk*
> row's bar and architecture-kill. `M1`'s decision threshold is **K1's `1.10×`
> against direct UIx-on-subs**: ship only when the whole interval sits at or
> below it, trip K1 only when the whole interval sits above it, otherwise
> instrument-limited.
>
> #### The published row — K1 is MISSED, decisively
>
> | ensemble | outer runs | `hicasso / uix-subs`, floor-normalised | verdict against K1's `1.10×` |
> |---|---:|---|---|
> | `clock-emvod` | 8 | **1.1718×** [1.1263 – 1.2190] | whole interval above — **K1 MISSED** |
> | `clock-w3yxd` | 6 | **1.1976×** [1.1504 – 1.2468] | whole interval above — **K1 MISSED** |
>
> Both intervals lie entirely above `1.10×` on the losing side, so the mount
> premium is a **magnitude and not a direction**: the candidate mounts at
> roughly **1.17 – 1.20× direct UIx-on-subs**, and K1's gate is missed on two
> independently launched ensembles.
>
> #### Reagent-on-subs, co-instrumented and gating nothing
>
> `validation.md` states K1's gate against **direct UIx-on-subs** and says in
> terms that Reagent-on-subs is *"co-instrumented and reported beside the mount
> row, not gating it"*. These are the same estimand and the same bootstrap; they
> adjudicate no threshold, and the tool labels them `CO-INSTRUMENTED` rather
> than giving them a verdict.
>
> | pair | `clock-emvod` *(n=8)* | `clock-w3yxd` *(n=6)* |
> |---|---|---|
> | `hicasso / reagent-subs` | 1.1326× [1.0775 – 1.1955] | 1.1282× [1.0217 – 1.2301] |
> | `uix-subs / reagent-subs` | 0.9665× [0.9179 – 1.0228] | 0.9420× [0.8517 – 1.0294] |
>
> #### The unfloored level ratio, kept as a labelled diagnostic
>
> It is a **different quantity** from the row above and never the headline. It
> is printed because the two estimators agreeing on a row's direction is what
> bounds what the floor normalisation is doing — and because the figures below
> are the ones the superseded proposal drew its intervals from, so they are
> where a reader checks the splice for themselves. **No figure in this table may
> be quoted beside a figure in the tables above.**
>
> | pair | `clock-emvod` *(n=8)* | `clock-w3yxd` *(n=6)* |
> |---|---|---|
> | `hicasso / uix-subs` | 1.1679× [1.1259 – 1.2096] | 1.1621× [1.1209 – 1.2090] |
> | `hicasso / reagent-subs` | 1.1142× [1.0238 – 1.1988] | 1.1151× [1.0314 – 1.1955] |
> | `uix-subs / reagent-subs` | 0.9540× [0.8835 – 1.0139] | 0.9596× [0.8962 – 1.0209] |
>
> Every pair moves the same way on both estimators, and on the gated pair the
> whole interval sits above `1.10×` under **either** — so the verdict does not
> turn on the choice. What turned on it was the *citable figure*, which is why
> the repair was owed before publication and not after.
>
> #### The cross-run max-band second veto is retired from publication authority
>
> `rf2-8a746`'s rule carried a second condition: the effect had to exceed the
> **widest same-run reproducibility band** among the pooled runs. It is retired
> here — not because it was inconvenient, but because it is not an interval for
> the claimed effect, it borrows a control statistic belonging to *one* run of
> *one* ensemble, and it grows **harder** to clear merely by adding runs.
>
> The corpus makes the objection concrete, and this is an argument the ruling
> did not have. On `clock-emvod` the effect is 17.2% against a widest band of
> **22.34%** — the retired condition **refuses**. On `clock-w3yxd` it is 19.8%
> against **18.29%** — the retired condition **admits**. Two ensembles measuring
> the same effect to within 2.6 pp are sent to *opposite verdicts* by it.
>
> **What is not retired:** the **35% band ceiling** remains a run-eligibility
> guard that refuses a whole run before any interval is formed, and the same-run
> bands are printed beside every verdict as sensitivity diagnostics.
>
> #### Residual uncertainty this row carries, and it is not optional
>
> - **Eight and six outer runs only.** The bootstrap resamples runs as the outer
>   unit, and eight and six are thin ensembles for an outer distribution.
> - **The mount check standard is not independent of this row.** Its limits were
>   **calibrated on these same 14 runs** rather than on a separate baseline, so
>   `14 of 14 in control` is a consistency check and not a false-refusal
>   measurement. A check standard earns its name on a baseline it is not
>   afterwards judged on, and this one has not had that yet.
> - **Both ensembles record the same Chromium**, `147.0.7727.15`. They are
>   independent of each other in launch, box state and session — not in browser
>   build. A browser-level effect would appear in both and be invisible to the
>   replication.
>
> #### What replication this row actually claims
>
> **Two independently launched ensembles, whole-ensemble figures, overlapping
> intervals.** That is the whole of it, and it is the ruling's own wording. The
> published points sit **2.58 pp** apart (1.1718× against 1.1976×), the two
> intervals overlap over `[1.1504 – 1.2190]`, and each contains the other's
> point. No selection stands behind either figure: `14 of 14` runs are in
> control, so the reportable subset is the whole ensemble in both cases.
>
> The section below used to rest weight on a much tighter agreement between the
> two ensembles on this pair. **That claim is withdrawn** (`rf2-diaud` part 3):
> it was a property of the *selection* those figures were means over and not of
> the measurement, and it disappears the moment the selection does. It is
> withdrawn rather than restated, because a corroboration that survives only
> under a selection is not corroboration.
>
> **Reproduce it.** Every figure in this block is arithmetic over the committed
> corpus, printed by the canonical tool and quoted from nowhere else:
>
> ```bash
> node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs \
>   freehand/test/re_frame/bench/hicasso/data/clock-emvod/run*.json
> # exit 0 — ROW M1, PAIR hicasso / uix-subs:
> #   point 1.1718x  95% CI [1.1263 – 1.2190]  over 8 reportable run(s)
> #   VERDICT K1 MISSED, DECISIVELY — the whole interval is above the 1.1x mount gate
> ```
>
> **No measurement was taken for any of this**, and no window was consumed.

> **RE-ADJUDICATED 2026-08-08 UNDER A CALIBRATED MOUNT CLASS (`rf2-x7x10`), AND
> THE CONDITIONING LABEL BELOW IS SUPERSEDED.** Nothing in this section is
> erased and no figure in it was found wrong. What changed is the *rule* the
> figures are conditioned on, and therefore which runs they are means over.
>
> **What broke.** Every bolded figure below is labelled *reportable-subset
> conditional*, and the subset is named as "the runs passing the strict
> every-block `ctl-2x` rule on both clocks", yield `7 of 14`. `rf2-8a746`
> retired that rule **everywhere on this instrument** — its arithmetic is
> `p^18`, and a control fully meeting its premise passed 4 of 42 runs — and
> PR #7698 landed the retirement. So from that merge the label named a rule
> nothing implements, and `clock_readjudicate.cjs` — which `rf2-t2flm`'s own
> ruling records as reproducing every figure below **exactly** — printed
> `reportable subset: NONE` on all three `M1` pairs of both ensembles instead.
> No number moved and nothing was invalidated; the new posture is *stricter*.
> What was lost was the **reproduction path** of a published row.
>
> **What replaced it.** The check standard's `mount` class is calibrated (v2)
> from the mount's own 14 committed row-runs, in the derivation `rf2-8a746`
> froze `bulk` under and *not* by importing `bulk`'s limits: centre **1.7956×**
> (the median of the 14 run medians), location limits **[1.6765 – 1.9147]**
> (centre ± 3 × between-run SD `0.0397`), dispersion ≤ **0.577** (a lognormal
> upper 3σ on the robust scale `IQR/1.349`). The classes are 4.4% apart on the
> identical statistic through the identical door, and the gap is load-bearing:
> `bulk`'s ceiling of `1.8905` would have refused `clock-emvod` run 3, whose
> mount reads `1.8978×`, on a limit derived from a different row class.
>
> **The eligible subset therefore changed, so this row is re-adjudicated rather
> than relabelled — `7 of 14` becomes `14 of 14`.** Every `M1` run in both
> ensembles is `IN CONTROL` under the mount standard, and every other gate on
> them was already clean, so **the reportable subset is now the whole ensemble**
> and the two are the same number:
>
> | pair | `clock-emvod` published → now | `clock-w3yxd` published → now |
> |---|---|---|
> | `hicasso / uix-subs` | 1.1837× *(n=4)* → **1.1798× *(n=8)*** | 1.1848× *(n=3)* → **1.2057× *(n=6)*** |
> | `hicasso / reagent-subs` | 1.1402× *(n=4)* → **1.1429× *(n=8)*** | 1.1100× *(n=3)* → **1.1492× *(n=6)*** |
>
> Both "now" columns are the whole-ensemble figures already tabled below, so
> **no new number enters the record** — what changes is which column is the
> headline.
>
> **And one claim below does not survive the change of subset.** The section
> rested weight on the two ensembles agreeing to about a tenth of a percentage
> point on the UIx pair. That agreement was a property of the *selected*
> subsets: unselected, the two ensembles sit **2.6 pp** apart (1.1798 against
> 1.2057). The Reagent pair moves the other way — 3.0 pp apart on the subsets,
> **0.6 pp** apart unselected. Neither direction was chosen; both are what
> removing a selection on a control does, and the honest reading is that the
> selection was making the UIx replication look tighter than it is and the
> Reagent replication looser. **The claim is retired everywhere on this page**
> (`rf2-diaud` part 3), and the sentence that asserted it is gone rather than
> struck: a corroboration figure left legible in strikethrough is still a figure
> a reader can lift.
>
> **`rf2-8a746`'s publication rule now reaches this row, and it refuses a
> magnitude.** *(**Superseded 2026-08-08 by `rf2-diaud`** — see the block at the
> head of this section. The refusal below is correct for the rule as it stood
> and for the estimand it was computed on; the ruling replaced both. The table
> that follows survives as the **labelled diagnostic** it is quoted as up
> there.)* It could not reach it before: with no run eligible, no interval
> was formed. With all 14 eligible the run-preserving effect-size interval
> (paired same-round level ratios, outer runs resampled before inner rounds,
> 4000 draws, at the fixed bootstrap seed 20260807) computes, and on **every
> pair of both ensembles**
> the verdict is `INSTRUMENT-LIMITED`:
>
> | pair | `clock-emvod` | `clock-w3yxd` |
> |---|---|---|
> | `hicasso / uix-subs` | 1.1679× [1.1259 – 1.2096] *(n=8)* | 1.1621× [1.1209 – 1.2090] *(n=6)* |
> | `hicasso / reagent-subs` | 1.1142× [1.0238 – 1.1988] *(n=8)* | 1.1151× [1.0314 – 1.1955] *(n=6)* |
> | `uix-subs / reagent-subs` | 0.9540× [0.8835 – 1.0139] *(n=8)* | 0.9596× [0.8962 – 1.0209] *(n=6)* |
>
> The reason is stated precisely, because it is **not** that the row is noisy:
> on the two `hicasso` pairs the whole interval lies **above 1.0** — the
> direction is intact and parity is excluded — but the rule publishes a
> magnitude only when the whole interval clears the `1.0` ship bar *below* or
> the `1.5` architecture-kill *above*, and an interval at ~1.16× clears neither
> **decision threshold**.
>
> **So the bolded magnitudes below are HELD, not withdrawn, and a ruling is
> owed.** `rf2-t2flm` (2026-08-07) publishes a magnitude on this row;
> `rf2-8a746` part 4 (2026-08-07, same day) says a row publishes a magnitude
> only on a whole interval clearing a threshold. On `M1` those two rulings now
> disagree, and which governs is a **ruling** rather than a worker's call — the
> same reason §7.2 stopped rather than published. Recorded, not decided.
>
> **THE RULING CAME (2026-08-08, `rf2-diaud`), AND THE HOLD IS LIFTED.** It went
> further than the collision it was asked about: the whole-interval discipline
> is kept but its threshold is **row-class-specific**, and the row is
> re-computed on **K1's own floor-normalised estimand** rather than published
> from the level-ratio interval quoted above. The magnitudes below are therefore
> **superseded rather than restored** — the live row is at the head of this
> section. Which is why holding rather than withdrawing was right: a withdrawal
> would have thrown away the row that a corrected estimator went on to publish.
>
> **No measurement was taken for any of this.** Every figure in this block is
> arithmetic over the committed 42-run corpus, reproduced by the command in
> [§7.2](#72-the-re-take-that-was-taken-and-what-it-refuses) over each
> ensemble's `run*.json`.

**Ruled 2026-08-07, `rf2-t2flm`. `M1` regains a magnitude.** The regime-only
statement of `rf2-jcm3p` (2026-08-06) is **superseded** — not because a new
measurement was taken, but because the ground it stood on was re-examined and did
not hold. It is published on the two retained quiet-box ensembles,
[`clock-emvod`](#72-the-re-take-that-was-taken-and-what-it-refuses) and
`clock-w3yxd`, and on **both estimators**.

**Against direct UIx-on-subs — `~1.184×`, floor-normalised.**

| ensemble | reportable subset | whole ensemble | raw quotient, reportable subset |
|---|---:|---|---:|
| `clock-emvod` | **1.1837×** *(n=4)* | 1.1798× [1.1346 – 1.2347] *(n=8)* | 1.1561× *(n=4)* |
| `clock-w3yxd` | **1.1848×** *(n=3)* | 1.2057× [1.1731 – 1.2390] *(n=6)* | 1.1537× *(n=3)* |

Two independently launched ensembles on the programme's floor-normalised
`TaskDuration` estimator. **The corroboration claim that stood here — that the
two ensembles agreed to about a tenth of a percentage point — is RETIRED**
(2026-08-08, `rf2-diaud` part 3): it was a property of the *selected subsets*
rather than of the measurement, and unselected the two sit 2.6 pp apart. The
honest replication statement is at the head of this section. The **raw quotient —
the direct arm-to-arm comparison touching neither floor — reads `~1.155×`**, and
it is stated here beside the headline rather than behind it: on the reportable
subsets the two estimators differ by **2.8 pp** (`clock-emvod`) and **3.1 pp**
(`clock-w3yxd`), the floor-normalised one is the higher in both, and a reader is
entitled to both numbers without going looking. **All fourteen whole-ensemble run
means on this pair sit above `1.10×`** — the amended UIx gate — with no exception
in either ensemble.

**Against Reagent-on-subs — two ensemble-specific estimates, and no pooled
point.**

| ensemble | reportable subset | whole ensemble | raw quotient, reportable subset |
|---|---:|---|---:|
| `clock-emvod` | **1.1402×** *(n=4)* | 1.1429× [1.0723 – 1.2640] *(n=8)* | 1.1545× *(n=4)* |
| `clock-w3yxd` | **1.1100×** *(n=3)* | 1.1492× [1.0492 – 1.3339] *(n=6)* | 1.0969× *(n=3)* |

The replication was read as **looser** on this pair — 3.0 pp apart
floor-normalised and 5.8 pp raw — so the two estimates are published side by
side and **a single pooled figure is refused**. *(The comparison this sentence
drew against the UIx pair is retired with the claim it rested on — `rf2-diaud`
part 3. On the whole ensembles the ordering reverses: the Reagent pair is the
**tighter** replication of the two.)* Averaging them would
manufacture a precision the data does not carry, and the honest statement is that
the candidate is above Reagent-on-subs on mount by something in the low tens of
percent, measured twice, at two values.

#### The labels these figures carry, and they are not optional

~~**Reportable-subset conditional.** Every bolded figure above is a mean over the
runs that passed the **strict every-block `ctl-2x` rule on both clocks** — not
over the ensemble. The subset is a *selection on a control*, and the
whole-ensemble band is printed beside each one so the selection's effect is
visible rather than asserted away.~~

~~**The control yield, stated plainly: `7 of 14` runs eligible** — 4 of 8 on
`clock-emvod`, 3 of 6 on `clock-w3yxd`. Wilson 95% on that proportion is
**≈ 0.27 – 0.73**. The correct reading of this control is that it **CAN
adjudicate `M1`** — not that it usually will. A third ensemble might yield two
eligible runs or five, and nothing here promises otherwise.~~

**BOTH LABELS ARE SUPERSEDED (2026-08-08, `rf2-x7x10`) — the rule they name no
longer exists on this instrument.** They are struck rather than deleted because
the *shape* of the caution was right and is worth keeping legible: a figure
conditioned on a control's verdict is a selection, and a selection has to be
declared. What replaces them:

**Run-in-control conditional, under the mount check standard v2.** Every figure
above is a mean over the runs certified `IN CONTROL` by
`clock_check_standard.json`'s `mount` class — the run's own block **median**
inside `[1.6765 – 1.9147]` about an empirical centre of `1.7956×`, and its
robust scale `IQR/1.349` at or under `0.577`. Nothing per-block rejects a run;
the ±25% tolerance band about the centre is **reported and decides nothing**,
at 246 of 252 blocks inside.

**The yield, stated plainly: `14 of 14` runs in control** — 8 of 8 on
`clock-emvod`, 6 of 6 on `clock-w3yxd`, so **the reportable subset is the whole
ensemble and there is no selection left to declare**. The honest rider is a
different one, and it is about the *limits* rather than the runs: v2 is seeded
from the same 14 row-runs it is quoted against, so `14 of 14` is a **consistency
check and not a false-refusal measurement** (`0.4%` nominal per run, from a
two-sided 3σ location term and a one-sided 3σ dispersion term). A check standard
earns its name on a baseline it is not afterwards judged on, and at the time of
writing this one had not had that; the JSON's `provenance.independence` said so
where a recalibrator will read it, and on this class 14 runs is thin for fixing
the dispersion term's σ.

*(2026-08-08, `rf2-c1974`: **the hold-out has since been taken, and on this class
it came back split.** v3 of the standard replaces the admission above with a
measurement. Limits fitted on `clock-emvod`'s 8 mount row-runs admit all 6 of
`clock-w3yxd`; limits fitted on `clock-w3yxd`'s 6 **refuse 4 of `clock-emvod`'s
8**, every refusal on location and none on dispersion. The cause is not a large
shift between the sittings — their centres are 1.81% apart, *less* than the bulk
rows' 2.44% — but `clock-w3yxd`'s own tightness: a between-run SD of `0.0113`
against `clock-emvod`'s `0.0486`, so a ±3σ budget only `0.0678` wide, which the
offset alone all but fills. **The limits quoted above are not the failing ones** —
they pool both sittings, carry the between-session term in their `0.0397` SD, and
still admit all 14. What the split impeaches is *single-sitting* calibration on
this class, now a named recalibration trigger. And note what the hold-out is: it
crosses a **sitting**, same day and same tree, ~90 minutes apart. Commit-level
independence has still never been measured on this instrument.)*

**For comparison, the retired rule's arithmetic on this row**, since it is what
the struck labels quoted: every block inside `2.00× ± 25%` put 240 of 252 blocks
(95.2%) in band and passed 9 of 14 runs on the published clock, `7 of 14` on
both — the yield above. Re-centring the same band on the empirical `1.7956`
raises the in-band fraction to 97.6% and still passes only 10 of 14, because
`0.976^18 = 64.6%`. The mis-derived centre and the all-blocks rule were two
separate defects here exactly as on the bulk rows; what differs is severity, and
that is the whole reason this row kept a subset where the bulk rows kept none.

**Disclosure — the row-level pool is looser than the run-level gate**
*(`rf2-t2flm`, disclosed and deliberately not repaired)*. The readjudicator's
row-level pool accepts a bar carrying an adjudication record **even when that bar
is marked `clear:false`** — i.e. a run can contribute to a reportable subset on
the strength of its run-level control while its own bar sits inside its noise
band. Among the ~~seven~~ **fourteen** *(2026-08-08, `rf2-x7x10`: the eligible
set is now the whole of both ensembles, so the disclosure is restated over it —
the wrinkle is unchanged and neither pool was tightened)* eligible runs:

| pair | clears its own noise band | detail |
|---|---:|---|
| `hicasso / uix-subs` | ~~6 of 7~~ **11 of 14** | `clock-w3yxd` 6 of 6; `clock-emvod` 5 of 8 |
| `hicasso / reagent-subs` | ~~5 of 7~~ **10 of 14** | `clock-w3yxd` 5 of 6; `clock-emvod` 5 of 8 |

Tightening the pool is **not** required by this ruling and is not done. It is a
disclosed wrinkle, the disclosure is what the reader needs, and building new
adjudication machinery for it would be the wrong trade at this stage.

#### Why `rf2-jcm3p`'s ground fell

The 2026-08-06 ruling read `ctl-2x`'s mean of **1.8173× against a predicted
2.00×** as categorical failure, and concluded the control could not adjudicate
this row **anywhere**. **The implemented rule never required the mean to equal
2.00×.** `clock_run.cjs`'s `controlVerdict` tests **per-block band membership** —
every one of 18 blocks (3 segments × 6 rounds) inside `2.00× ± 25%`, strict — and
the `1.80×` centre is the *known additive undershoot* the driver itself documents
as a signal check rather than an exact model ([§4.2](#42-ctl-2x-undershoots-on-the-mount-row-too-and-that-changes-its-diagnosis)
prices the constant at `c ≈ 1.04 ms`). Seven of fourteen runs pass the
implemented rule on both clocks.

**That rule is genuine pre-registration.** It was committed on **2026-08-01** in
`2bf3027347`, six days before either ensemble was captured — so no outcome was
visible when it was written.

**And the passes are mechanistic, not luck** — `rf2-8a746`'s diagnosis supplies
the mechanism. `ctl-2x` is a ratio of two **levels**; the three-point control is a
ratio of two **differences**, which throws away the pedestal that was keeping the
relative noise small. On `M1`, `ctl-2x`'s centre sits **+20%** (`clock-emvod`)
and **+18%** (`clock-w3yxd`) above the band's 1.50 rejection edge, with per-block
in-band rates of **95.1%** and **95.4%**; those two numbers — distance above the
edge, and per-block scatter — raised to the 18th power reproduce the pass rates
actually observed. The same pair explains why the three-point control refuses
everything: its centre sits **+4%** above the edge at a 47–48% per-block rate.
**Nothing here is a property of "row class"** — `ctl-2x`'s denominator is the
floor's whole tared reading carrying ~9% relative error, the three-point
control's is a 1.25 ms difference carrying the same error at ~48% — and nothing
was tuned until it passed.

#### The standing condition this ruling attaches

A pre-committed k-of-n decision function was considered and **rejected as a
retrofit**: writing one with the outcomes already visible is not
pre-registration and cannot remove researcher degrees of freedom. It is the right
tool only **prospectively**. So: *if stronger precision on this row ever becomes
decision-critical, pre-commit the estimator, the pair-level clear rule, the
required eligible count and the precision target **before** collecting a
genuinely new ensemble.* That condition is part of this ruling.

**No new window was consumed to reach any of this.** Every figure above is a
function of committed data, recomputable by the command in
[§7.2](#72-the-re-take-that-was-taken-and-what-it-refuses) over each ensemble's
`run*.json`. `rf2-0qj9w`'s third `M1` quiet window was closed on exactly that
ground.

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

- ~~**The published `M1` magnitude is `rf2-yd52q`'s `1.4896×`** [1.3488 – 1.5989],
  whose corroboration control passed at `1.0011×` on a quiet box.~~ ~~**There is no
  published `M1` magnitude** *(2026-08-06, `rf2-jcm3p`)*. `rf2-yd52q`'s `1.4896×`
  [1.3488 – 1.5989] remains the best-corroborated reading and its corroboration
  control did pass at `1.0011×` on a quiet box — but the row's own **positive**
  control fails there too, at `ctl-2x` `1.8173×` against `2.00×`, so the figure
  is a historical observation and the row publishes a regime.~~ **There IS a
  published `M1` magnitude again** *(2026-08-07, `rf2-t2flm` —
  [§4.3](#43-the-published-m1-row))*, and it is **not** `1.4896×`. It is drawn
  from the two **retained** ensembles, which no earlier statement on this row
  could use because their datasets did not exist. `1.4896×` and `1.3737×` both
  stay visible as dated historical observations; neither is withdrawn, and
  neither is the published figure.
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
> set it measures `P(band > 25%)` at **9.1% per run**, so three firings in
> two days is the rate rather than an event, and the ceiling is now **35%**, at
> a per-run false-fire rate of 2.7%. (It was first published as the statistic's
> bootstrap `q99`; that derivation is **withdrawn** — the model pooled blocks
> across runs, and 35% is the `q99` of neither model.) Run 8 would today be *adjudicated*
> rather than refused — and **no magnitude changes**, because `bulk300` is
> refused by `rf2-7iqb5`'s three-point control and `narrow`'s 2–9% margin is
> inside its own 28.4% band at any ceiling.

---

## 6. What is refused, and what is not

**Refused as magnitudes:** `bulk300`, `bulk100`, `narrow`, `keystroke` — and
~~`M1`~~ **no longer `M1`** *(2026-08-07, `rf2-t2flm`)*. ~~*(2026-08-06,
`rf2-jcm3p`: the qualifier is spent. `M1` is refused as a magnitude on every
ensemble, this one and the two landed ones, because its control cannot adjudicate
it anywhere. It publishes a mount **regime**.)*~~ **`M1` is refused on THIS
ensemble and published on the two retained ones**
([§4.3](#43-the-published-m1-row)) — the qualifier struck above turns out to have
been the correct one, and striking it was the error: the control's reach is a
property of the *run*, not of the row.

**What each refused row publishes instead** *(2026-08-06, `rf2-jcm3p`,
`rf2-swwud`; `M1`'s entry superseded 2026-08-07, `rf2-t2flm`)*: ~~`M1` a **mount
regime** — direction only, materially slower than
both adapters, `≤ 1.10×` not demonstrated.~~ `keystroke` a **responsiveness
regime** — indistinguishable from both donors at Event Timing's resolution,
every observed interaction one frame, with the rider that the instrument
resolves 8 ms buckets above a 16 ms floor and states no tie below it. The three
bulk rows publish direction and no magnitude, as below.

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

### 7.1 What this page cannot regenerate

**The seven datasets were not retained, and the command above is therefore not
a reproduction** *(2026-08-07, `rf2-emvod`, recorded rather than repaired)*.
`out/run*.json` names files that existed only in the author's working tree; the
merge landed no Hicasso dataset at all. So a fresh clone cannot recompute
`1.3737×`, its range, the 7/3/2/4 reportable counts, the controls, the bands,
the absolutes, the door spreads or [§4.2](#42-ctl-2x-undershoots-on-the-mount-row-too-and-that-changes-its-diagnosis)'s
additive-residual table. **Every table on this page is unreproducible from the
tree, and that is stated here rather than left to be discovered.** Re-taking the
ensemble is a *measurement*, not a repair, and none was taken to write this
section: no figure above has been restated, widened or recomputed.

This is [`rf2-cvvb7`'s recorded gap](the-candidates-clock.md#71-the-seam-study-rf2-cvvb7)
landing on the very page that quotes it — which is the reason the repair below
is in the *instrument* rather than in this prose. A page can only promise
reproducibility; a program can refuse to pretend.

**What the tree enforces now.** `clock_readjudicate.cjs`'s reportable subset is
the driver's own exit path, read back off the serialised record, fail-closed at
every seat — a verdict that is missing, null or silent has not been passed, it
has been lost. Its first gate is [`rf2-2rtt6.31`'s two-tier write
policy](hd8-composed-donor-arm.md) seen from the reading end: a dataset records
`canonical` and `notCanonicalWhy` **in the file**, and a missing `canonical`
field is not a pass. Nothing is selected away — every run stays in every table
with its own magnitude beside it and its refusals named — and the program exits
`3` over evidence it may not publish from.

| gate | what refuses the run | serialised |
|---|---|---|
| `canonical` | the file does not say it is the published evidence set | yes *(`rf2-e87sk`)* |
| `page-errors` | Chromium threw during the run | yes *(`rf2-e87sk`)* |
| `guard-net`, `guard-task` | the arm-order guard refused, on either clock | yes |
| `canonical-dom` | the arms built different pages | yes *(`rf2-e87sk`)* |
| `ctl3-parity` | the three-point control's own arms built different pages | yes |
| `keystroke-witness` | the per-keystroke accounting does not close | yes |
| `unverified` | a window whose value never reached the page | yes |
| `ceiling-net`, `ceiling-task` | the reproducibility band exceeds its ceiling | yes |
| `control` | the three-point control failed, or `ctl-2x` on either clock | yes |
| `event-timing` | the Event-Timing witness refused | yes *(`rf2-e87sk`)* |
| `adjudication` | a published bar carries no band | yes |

**Four of those verdicts were computed, printed and exited on but never stored**,
so when this section was first written no dataset in the tree could satisfy the
filter — the correct fail-closed reading of an *incomplete* record, and not a
defect in it. `rf2-e87sk` serialised them, and the column above says `yes` for
every gate the roster carries: a run of this driver now writes a record the
readjudicator can adjudicate on all thirteen axes without re-running the box.

**The one command, and what it does on the datasets that were kept:**

```bash
cd implementation
node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs \
  freehand/test/re_frame/bench/hicasso/data/clock-0qj9w/run1.json \
  freehand/test/re_frame/bench/hicasso/data/clock-0qj9w/run2.json
# exit 3 — neither file carries a `canonical` verdict, both are printed in
#          full, and no reportable subset is drawn from either
```

Its refusals are fixtures rather than an assertion — one deliberate corruption
*and* one deliberate erasure per gate, plus the whole program run over a file
in both directions:

```bash
cd implementation
node freehand/test/re_frame/bench/hicasso/clock_exit_path.test.cjs
```

The cross-check probe in [§2.1](#21-what-the-correction-therefore-does-not-reach)
and [§3](#3-the-three-mount-numbers) was taken with `jsfb_ours_run.cjs` at the
blob it landed at in this change, against arms built by `jsfb_build.cjs` and
served by `jsfb_serve.cjs` from a bare `--dest`. **Its stylesheet was absent**
and the server says so at startup; it certifies no ratio and ran without the
`create10k` positive control, which its own output states.

### 7.2 The re-take that was taken, and what it refuses

**A retained ensemble now exists** *(2026-08-07, `rf2-emvod`, on a granted quiet
box)*. Nothing in §7.1 is thereby repaired: the seven runs this page publishes
were never written to a file, so no command will ever recompute *them*, and that
stays permanently true. What is different is that the tree now carries **an
ensemble of its own** — eight runs of the full five-row roster, taken at
`c172f22bb0`, each written by the serialiser `rf2-e87sk` completed, each
`canonical: true` in the file, and each adjudicable on all thirteen gates above
off the record rather than off a console log.

| file | blob |
|---|---|
| `data/clock-emvod/run1.json` | `c53c8af9dfe2e4af19d6e41ae9a4614f1c62e949` |
| `data/clock-emvod/run2.json` | `e150449931c677c7aef301c34fb5889225c0b95d` |
| `data/clock-emvod/run3.json` | `223cfedcd09e774cefb8b59d163b7504790aa6ca` |
| `data/clock-emvod/run4.json` | `b57e659cdba5e9b1df3d60932d77da7a1c57d254` |
| `data/clock-emvod/run5.json` | `dfaaaec0c9d6314cd814fdb44c74c698d73c845e` |
| `data/clock-emvod/run6.json` | `5938fcf93c78cc636859dba63f5f924dd21e3772` |
| `data/clock-emvod/run7.json` | `be960330307c89d138f3272a7a4efae9c25be543` |
| `data/clock-emvod/run8.json` | `2e9d27fc6efa9070bc84e08512ec30024c56c56a` |

| | |
|---|---|
| Ensemble | **8 runs**, all five rows in each, 2026-08-07 15:31–16:22 AUSEST, one at a time and never two at once |
| Driver | `clock_run.cjs` at blob `c6a4f249153f4bce6e85daa25121a06335d5c68d`, `clock_readjudicate.cjs` at `b85e312b734cf2051abd542429e8d6eddef1ec22` — **both later than this page's own §7 table, which is left un-re-pinned** |
| Design | the published depth on every run — 6 rounds, 4 warmup, 10 samples, tare on, no falsification knob, no `--no-build`, no `HCLOCK_ONLY`. That is what makes each file `canonical: true`; a run that narrows any of them writes `canonical: false` and names why, and the readjudicator's first gate refuses it |
| Box | **quiet, and measured rather than asserted.** `\System\Processor Queue Length` **0** on every sample before the ensemble and after it, with `\Processor(_Total)\% Processor Time` 9.3–12.2% before and 10.2–18.2% after. One mid-series sample read a queue length of 1 while a run's Chromium was winding down. No other worker was dispatched during the window |
| Exit code | **1** on all eight, scoped to a control on rows the run then refuses |

**The re-take does not restate one figure on this page.** It is a later
measurement on a moved instrument, and the reason to land it is that it can be
re-adjudicated by a reader instead of believed. What it says about itself:

| row | runs | reportable | what refused the rest |
|---|---:|---:|---|
| `M1` | 8 | **4** | `ctl-2x` on `taskNet` — runs 1, 2, 4 and 6 |
| `bulk300` | 8 | **0** | the three-point control, on every run |
| `bulk100` | 8 | **0** | the three-point control, on every run |
| `narrow` | 8 | **0** | the three-point control on every run; run 1 also breached the band ceiling, at 41.8% and 49.9%, before any control was consulted |
| `keystroke` | 8 | **0** | the row carries no proportional control, so no bar it publishes carries an adjudication verdict — the structural refusal `rf2-y7mw7` records, unchanged |

**Every control's prediction against what it measured**, which is the part of
this that was worth a box:

| control | predicted | measured over 8 runs | runs it passed |
|---|---|---|---|
| `ctl-2x`, `M1` | 2.00× | mean 1.8000×, runs 1.7045 – 1.8756 | 5 of 8 on the published clock, **4 of 8 on both clocks** |
| `ctl-2x`, `bulk300` | 2.00× | mean 1.7880×, runs 1.7004 – 1.8603 | 2 of 8 |
| `ctl-2x`, `bulk100` | 2.00× | mean 1.6965×, runs 1.6065 – 1.7520 | 2 of 8 |
| `ctl-2x`, `narrow` | 2.00× | mean 1.7626×, runs 1.6650 – 1.9173 | 0 of 8 |
| `ctl-3pt`, `bulk300` | 2.0101× | mean 1.6045×, runs 1.3516 – 1.7838 | **0 of 8** |
| `ctl-3pt`, `bulk100` | 2.0101× | mean 2.1777×, runs 1.4953 – 5.5726 | **0 of 8** |
| `ctl-3pt`, `narrow` | 2.0101× | mean 2.7141×, runs 1.4466 – 8.0908 | **0 of 8** |
| Event Timing, `keystroke` | `ctl-50ms` above the arms | 56.0 ms against 16.0 ms | **8 of 8** |
| arm-order guard, every row | no arm reads differently for its position | no refusal on either clock | **8 of 8** |

**The three-point control refused twenty-four times out of twenty-four**, and
that is the finding this window produced. It is `rf2-7iqb5`'s repair — the
control built to difference away the additive constant that makes `ctl-2x`
undershoot — declining to certify a single bulk run on a box whose queue length
never left zero. Two shapes of failure are visible in the table and they are not
the same shape: `bulk300` sits *low* and tight, at 1.35–1.78 against 2.01, which
is the undershoot `ctl-2x` shows on the same samples and not obviously a box
effect; `bulk100` and `narrow` each carry one run whose blocks went degenerate
(the three-point statistic ranges to 86× on `bulk100` run 7 and to 114× on
`narrow` run 7, with negative blocks on both), which the control's sign gate is
built to catch and did. **Whether that is the instrument or the workload is a
measurement this window did not take**, and nothing here is widened to
accommodate it — `rf2-5xrcd` owns the control's calibration.

~~`M1`'s four reportable runs are **not** a magnitude, and this page does not
publish one from them. Its standing ruling (`rf2-jcm3p`, §6 above) is that the
mount row publishes a regime because its control cannot adjudicate it — and on
these eight runs `ctl-2x` did pass, on both clocks, on four of them. That is
evidence bearing on a ruling, not a ruling; it is recorded here and left to be
ruled on.~~

**IT WAS RULED ON, AND `M1`'S FOUR REPORTABLE RUNS ARE NOW PART OF A PUBLISHED
MAGNITUDE** *(2026-08-07, `rf2-t2flm`)*. The paragraph above was right to stop:
whether a control passing on four of eight runs is the same claim as a control
that can adjudicate the row is a **ruling**, and a worker holding a measurement
is not the one to make it. The ruling went **for** adjudication — the passes are
mechanistic (`rf2-8a746`), the rule they pass was pre-registered six days before
this ensemble, and a second independently launched ensemble replicates the result.
This ensemble's ~~four~~ **eight** runs supply `hicasso / uix-subs`
~~**1.1837×**~~ **1.1798×** and `hicasso / reagent-subs` ~~**1.1402×**~~
**1.1429×** to the published row at [§4.3](#43-the-published-m1-row), where they
are stated with their conditional labels, their whole-ensemble bands and their
raw quotients. *(2026-08-08, `rf2-x7x10`: the four-run subset was the
every-block `ctl-2x` rule's, which `rf2-8a746` retired; under the calibrated
mount check standard all eight of this ensemble's `M1` runs are in control, so
the subset and the whole ensemble are now one set and one figure.)* **Nothing on
this page is restated by that** — §7.2's own sentence, *"the re-take does not
restate one figure on this page"*, still holds: §4.3 publishes from the retained
ensembles, and the seven runs this page was written on are untouched.

**The one command, over evidence it may publish from:**

```bash
cd implementation
node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs \
  freehand/test/re_frame/bench/hicasso/data/clock-emvod/run*.json
# exit 0 — all eight datasets are eligible published evidence, every run is
#          printed with its own magnitude and its own refusals beside it, and
#          the reportable subset is drawn only where the gates left one
```
