# Bulk broad, re-taken — and the clock that was subtracting the operation

**The 37% win is withdrawn. The direction survives.** On a clock that sees the
whole operation — the arm's own JavaScript *and* the frame it causes —
`UIx-on-subs ÷ Reagent-on-subs` on a broad commit reads **0.8602×**
[0.7709 – 0.9058] over eight runs, against a published **0.6291×**
[0.5792 – 0.6987]. Every run mean is below 1.0 and 43 of 48 rounds are, so UIx
*is* faster on this row; it is faster by something near 14%, not by 37%, and on
half the runs that margin sits inside the band this instrument measured for
itself. **No replacement magnitude is published** ([§6](#6-what-is-refused)).

**And the re-take found something larger than the row.** The clock `rf2-0qj9w`
built and *called* frame-inclusive, which `rf2-8nqsl`'s audit used to read this
row at `1.0509×`, subtracts `DevToolsCommandDuration` from `TaskDuration` — and
`DevToolsCommandDuration` **carries the operation's own script**, because the
driver runs every arm through a protocol command and Chromium bills the whole
command to that counter. So `taskNet` is style, layout and paint with the
*script removed*: a frame-**only** clock, and very nearly the **complement** of
the in-page window rather than a superset of it. Measured, not argued
([§2](#2-the-instrument-fault-measured)).

The corrected clock then **validates itself on a row it was not fitted to**: the
same donor bar on `M1 mount`, in the same runs, reads **1.0011×**
[0.9464 – 1.0763] against a published `1.0150×` [0.9820 – 1.0480]. The
frame-only clock reads that row 4% high and the in-page clock 10% low. One
instrument, two rows, and it lands on the published value for the row the audit
independently found robust while landing far from it on the row under question.

Owner: the operator-owned standard bead `rf2-2rtt6.1`; this page `rf2-yd52q`.

---

## 1. What was asked, and what a re-take had to be

`rf2-8nqsl` established that every wall-clock row this programme has published
was taken on an in-page `performance.now()` window closing the instant
`flushSync` returns, and that
[`bulk broad 0.6291×`](the-clock-behind-the-published-rows.md#4-which-rows-change)
was the one published verdict that did not survive a change of instrument. It
could not re-take the row: a clock that sees the frame needs one operation per
frame, and `p0_converge_app` runs an entire row in one macrotask by design.

So the row needed a purpose-built harness, and the bead named it — the
`rf2-0qj9w` instrument, the published row's arms and props, one write per frame,
both clocks over the same samples, `ctl-2x` predicted before the run.

**The published row's arithmetic was already computable from that instrument and
had never been computed.** `clock_run.cjs` printed `hicasso / reagent-subs` and
`hicasso / uix-subs` and stopped there; `uix-subs / reagent-subs` — the donor
quotient that *is* `bulk broad`, and `M1 mount` — had to be derived by hand from
the driver's readings for the audit to reach `1.0509×`. A statistic recomputed
off-instrument carries no band, no regime and no control verdict. It is now a
bar pair like the other two and goes through the same adjudication.

The witness, the write and the arms are the published row's. `bulk300` here is
one `frame/replace-app-db!` changing all 300 of the 300 mounted boundaries,
drained by each arm's own door, read back at both ends of the range — the same
operation `p0_converge_app`'s `:broad` performs as a batch of one, on the same
components at the same props.

---

## 2. The instrument fault, measured

### 2.1 The tell needed no arithmetic

The driver reported the two clocks only as a **ratio to each arm's floor**. Print
the absolutes instead and the fault is on the page:

| `bulk300` arm | in-page p50 | `taskNet` p50 |
|---|---:|---:|
| `reagent-subs` | **2.375 ms** | 2.335 ms |
| `uix-subs` | 1.669 ms | 2.463 ms |
| `hicasso` | **2.938 ms** | 2.466 ms |
| `floor` | 0.431 ms | 2.372 ms |
| `ctl-2x` | 0.725 ms | 3.898 ms |

On two of the three substrate arms the in-page window reads **larger** than the
supposedly frame-inclusive one. **No superset can be smaller than its subset.**
The two numbers were both in the driver from its first run and neither was ever
printed.

### 2.2 What the subtraction removes

`taskNet = TaskDuration − DevToolsCommandDuration`. Chromium bills a
`Runtime.callFunctionOn` to `DevToolsCommandDuration` **including the page script
the command invokes**, and this driver invokes every arm's operation through
exactly that door: `page.evaluate(() => window.HCLOCK.sample(row, arm))`.

So the prediction is that an arm's `devtools` term, less the tare's baseline,
should track that arm's own in-page window. It does:

| `bulk300` arm | `devtools` | less the tare's 0.382 | its in-page p50 |
|---|---:|---:|---:|
| `plumb` *(the tare — no script)* | 0.382 ms | — | 0.000 ms |
| `floor` | 1.004 ms | **0.62 ms** | 0.400 ms |
| `ctl-2x` | 1.395 ms | **1.01 ms** | 0.800 ms |
| `uix-subs` | 2.399 ms | **2.01 ms** | 1.600 ms |
| `reagent-subs` | 3.145 ms | **2.76 ms** | 2.300 ms |
| `hicasso` | 3.647 ms | **3.26 ms** | 2.800 ms |

The excess runs 0.2–0.5 ms above each arm's in-page span throughout, which is
the part of the command outside the `performance.now()` marks — promise setup and
result marshalling — and is what the tare exists to carry.

`ScriptDuration` corroborates it from the other side. It reads **0.013–0.029 ms**
for every arm on every row, including a mount that builds 901 elements. The
renderer's script counter does not see script run through a protocol command
either.

### 2.3 What that makes `taskNet`

Style, layout, paint and the browser's per-frame overhead, **with the
operation's own JavaScript removed**. Not a frame-inclusive clock — a
frame-**only** one, and near enough the in-page window's complement that adding
the two is closer to the truth than either.

Three things fall out, and all three were visible in the published record without
being recognised:

- **The huge in-page-versus-`taskNet` divergences on substrate arms** — the
  audit's +268% to +704% — are what two near-complementary windows look like
  when one arm's work is mostly script.
- **The tiny divergence on the pure-React controls** — under 13% — is not
  because controls are well behaved. It is because `floor` and `ctl-2x` are the
  two arms whose script is small and roughly proportional to their frame, so
  both clocks rank them alike. Certifying on those arms could never have caught
  this.
- **`ctl-2x` undershooting 2.00× on every configuration** is untouched by the
  correction: it reads 1.69–1.83× the floor on *both* clocks, agreeing to within
  2%. The undershoot is the row's, not the subtraction's, and
  [`the-candidates-clock.md` §6.3](the-candidates-clock.md#63-what-a-future-run-would-have-to-change)
  had already diagnosed it — layout scales 1.79× where element count scales
  2.00×.

### 2.4 The repair

Raw `TaskDuration`: the arm's script and the frame it caused, in one number,
with the protocol's own round trip still carried by the `plumb` tare exactly as
before. It is reported **beside** the banked `taskNet` reading rather than
replacing it, because every other row this driver has published is stated on
`taskNet` and a silent swap would restate them without saying so. `rf2-emvod`
carries that re-adjudication; `rf2-aj15b` and `rf2-ymi6j` carry the audit's page
and the band calibration.

---

## 3. The design, and the predictions written before the runs

Unchanged from the instrument that produced the audit's reading, so the two are
comparable: **6 rounds × (4 warm-up + 20 samples) per arm per segment**, three
segments with one substrate arm each, segment order rotating with the round,
arms in the shared guard's reflecting order, one arm visible at a time.
`clock_app.cljs` and `clock_views.cljs` are at the **same blobs** as the audit's
runs and the published run 5 — the page half was not touched
([§7](#7-provenance)).

| control | prediction, registered in the driver's own pre-run block | outcome |
|---|---|---|
| `ctl-2x` | 2.00× the floor, ±25%, on **every** segment-round, strict | **FAILED** on 7 of 8 runs (`bulk300`) and 4 of 8 (`M1`); every failure an undershoot, 1.59–1.81× |
| **the row** | the frame-inclusive figure lands near parity and **not** near 0.63; a reading at or below **0.70 refutes it** | **PARTLY REFUTED.** The corrected clock reads 0.8602× — nowhere near 0.63, and not at parity either. Three of 48 rounds fell below 0.70 |
| **corroboration** | the same donor bar on `M1`, in the same runs, reproduces the published `1.0150×` [0.9820 – 1.0480]; if it does not, the instrument has no standing to move `bulk300` | **PASSED.** 1.0011× [0.9464 – 1.0763] over 8 runs, 0.9996× over the 4 control-passing runs |
| the in-page window | reads the row **below** 1.0 and near the published 0.63–0.69 | **PASSED.** 0.7120× [0.6390 – 0.7964] |

The row prediction is recorded as *partly refuted* rather than quietly widened.
It was written expecting parity, on the strength of three prior readings that
were *believed* frame-inclusive and were in fact `taskNet` — so the prediction
inherited the mislabel it was written to test. The corrected clock puts the row
at a real but much smaller win, and saying so is the point of registering a
prediction that could fail.

Whole-run gates, all sixteen row-runs of the published ensemble: **0 unverified
of 1,728** writes each, **27,224-byte canonical DOM identical** across all six
non-control arms in all three segments, **arm-order guard reportable**, candidate
runtime residue zero on every counter. `body-children` reads 2 throughout — the
page's own `#app` and its script tag, constant, not residue.

**The arm-order guard on this ensemble ran on `taskNet`, not on the clock the
row is published on.** At the blob in [§7](#7-provenance) the driver held one
`guard.verdict`, over the `taskNet` samples, and — the same mislabel again —
printed it as *"frame-inclusive task time"*. So the sentence above is true of
the diagnostic and was never established for raw `TaskDuration`: the `0.8602×`
and the 43-of-48 direction are **guarded by inheritance, not by verdict**. The
guard is a statement about a particular quantity — *this arm does not read
differently for WHERE in the plan it was measured* — and it does not carry
across a change of quantity. The driver now runs it on **both** clocks and
either refusal refuses the row (`rf2-emvod`), so a re-take is guarded; this
ensemble is not, and that is a limit on the figures rather than on the
direction, which three instruments agree on.

---

## 4. The row, on three clocks

Eight runs, quiet box, same samples read three ways.

### 4.1 `bulk300` — one commit, all 300 boundaries change

| clock | what it measures | `uix-subs ÷ reagent-subs` | run means |
|---|---|---:|---|
| in-page `performance.now()` | the script, to where `flushSync` returns | **0.7120×** | [0.6390 – 0.7964] |
| `taskNet` *(the audit's)* | the frame, script removed | **1.0455×** | [0.8995 – 1.1408] |
| **raw `TaskDuration`** | **script and frame** | **0.8602×** | **[0.7709 – 0.9058]** |
| *published* | in-page, converged harness | *0.6291×* | *[0.5792 – 0.6987]* |

Per-run, and every run is here:

| run | in-page | `taskNet` | **`TaskDuration`** | band | `ctl-2x` | adjudication on the corrected clock |
|---:|---:|---:|---:|---:|---:|---|
| 21 | 0.6472 | 1.1408 | **0.9039** | 18.7% | 1.778 | margin 9.6% inside the band — instrument-limited |
| 22 | 0.7072 | 1.0465 | **0.8717** | 12.0% | 1.595 | margin 12.8% clears 12.0% |
| **23** | 0.7340 | 1.0250 | **0.8433** | 12.2% | **1.745 PASS** | margin 15.7% clears 12.2% |
| 24 | 0.7964 | 0.8995 | **0.8104** | 18.5% | 1.746 | margin 19.0% clears 18.5% |
| 25 | 0.7928 | 1.1109 | **0.9038** | 13.0% | 1.656 | margin 9.6% inside the band |
| 26 | 0.6827 | 0.9304 | **0.7709** | 15.0% | 1.673 | margin 22.9% clears 15.0% |
| 27 | 0.6390 | 1.0784 | **0.8714** | 19.7% | 1.813 | margin 12.9% inside the band |
| 28 | 0.6965 | 1.1328 | **0.9058** | 11.7% | 1.770 | margin 9.4% inside the band |

**One run of eight is fully reportable** — run 23, the only one whose control
passed strictly — and it reads **0.8433×**, clearing its own 12.2% band. The
other seven agree with it in direction and in rough size and are refused as
magnitudes under the rule this instrument pre-registered.

**48 rounds: 43 below 1.0, 3 below 0.70, none below 0.57.** The published
verdict is *"UIx faster. All 60 rounds below 1.0; all 20 strata wholly below
it"*. On a clock that sees the whole operation the first half of that sentence
is very nearly reproduced — and the 37% that gave it its force is not.

### 4.2 `M1 mount` — the corroboration control, in the same runs

| clock | `uix-subs ÷ reagent-subs` | run means | against the published `1.0150×` [0.9820 – 1.0480] |
|---|---:|---|---|
| in-page | 0.8982× | [0.8249 – 0.9618] | 10% low, and outside |
| `taskNet` | 1.0557× | [0.9884 – 1.2377] | 4% high, and outside |
| **raw `TaskDuration`** | **1.0011×** | [0.9464 – 1.0763] | **inside, and 0.2% off the point estimate** |

Control-passing subset, n = 4: **0.9996×**. Every one of the eight runs
adjudicates this row as instrument-limited from parity, at margins of 0.2–7.6%
against bands of 8.8–16.3% — which is what a row *at* parity should look like.

**This is the check that makes the `bulk300` reading worth something.** The
corrected clock is not a clock that flattens everything toward 1.0: in the same
eight runs it puts `hicasso / reagent-subs` on `M1` at **1.4896×**
[1.3488 – 1.5989], a reading far from parity that clears every run's band. It
reproduces a published parity row to two parts in a thousand and simultaneously
separates a large deficit elsewhere. **What that `M1` reading is NOT is the
published magnitude** — ~~`rf2-jcm3p` ruled on 2026-08-06 that `M1` mount states
a **REGIME** and not an adjudicated magnitude, because its own `ctl-2x` fails
(1.8173× against a predicted 2.00×, the additive constant `c ≈ 1.04 ms`
explaining the undershoot); `1.4896×` remains visible here as a historical
observation *stated under a failing `ctl-2x`; withdrawn as a magnitude
2026-08-06*.~~ *(Superseded 2026-08-07, `rf2-t2flm`: the row does publish a
magnitude, drawn from the two retained quiet-box ensembles and conditionally
labelled —
[`rf2-emvod` §4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row).
`1.4896×` is not it, and stays here as what these eight runs read on
2026-08-01.)* **The check above is unaffected**, because it turns on this clock
discriminating a far-from-parity reading from a parity one — a direction and a
band, not a number.

---

## 5. Where the 37% went

The whole difference is which half of the operation the window contains, and the
absolutes say it in milliseconds. Pooled p50 per sample over the eight runs:

| `bulk300` arm | script *(in-page)* | frame *(`taskNet`)* | script + frame |
|---|---:|---:|---:|
| `reagent-subs` | **2.375 ms** | 2.335 ms | 4.710 ms |
| `uix-subs` | **1.669 ms** | 2.463 ms | 4.132 ms |
| `floor` *(pure React, same page)* | 0.431 ms | 2.372 ms | 2.803 ms |

**On the script the two arms differ by 42%.** Reagent-on-subs spends 2.375 ms
getting a 300-cell commit to React against UIx-on-subs' 1.669 ms — a real
difference, in the reactive plumbing, and it is what the published row measured.

**On the frame they differ by 5%, the wrong way.** Both writes produce the same
901-element page and the browser charges the same 2.3–2.5 ms for it. That frame
is *half of what the operation costs*, it is identical work in both arms, and an
in-page window cannot see any of it.

So the published 0.6291× is the ratio of the two arms' *script*, quoted as
though it were the ratio of their *operations*. Add the half that was missing
and 0.71 becomes 0.86. The programme had already written down the mechanism from
the other side — that the two substrates divide their work across the frame
boundary very differently — and this is that sentence in milliseconds.

Two footnotes belong here rather than in a conclusion. **The floor is not a
lower bound**: a pure-React re-render of the same page costs 2.803 ms against the
substrate arms' 4.1–4.7 ms, so a subscription graph over 300 boundaries costs
roughly 1.5–1.7× a top-down React render on this witness, on a clock that sees
everything. And **`hicasso` is the most script-heavy arm of the three** at
2.938 ms, which is the codec walking 901 elements and is the same finding
[the candidate's clock](the-candidates-clock.md#4-the-mount-row--a-regime-not-a-magnitude)
reached on the mount row.

---

## 6. What is refused

**No magnitude for `bulk broad` is published here**, and the reasons are the
ones this instrument pre-registered rather than reasons found afterwards:

1. **The positive control failed under the strict rule on 7 of 8 runs.** Every
   failure is an undershoot, 1.59–1.81× against 2.00×, on both clocks alike.
   `ctl-2x` doubles the *page*; an update row's work does not double with it,
   and `rf2-7iqb5` is the bead for a control that doubles the *changed set* at
   fixed page size. **That bead is now blocking this row**, not merely related
   to it: it is the only one of the three refusal grounds that a better box
   cannot fix.
2. **The margin is inside the band on 4 of 8 runs.** 14% against bands of
   8.8–19.7%. The row sits exactly on the edge of what this instrument can
   resolve, which is a result about the instrument.
3. **The rows still move between runs**, 0.7709 to 0.9058, a 17% spread. Less
   than the 59% that `the-candidates-clock.md` §6 recorded on `taskNet`, and
   more than a point estimate can survive.

**What needs no control, and is therefore stated:**

- **`0.6291×` is not reproduced by any instrument that has looked at the row past
  the `flushSync` boundary** — this page's `0.8602×`, and
  [the outside instrument](cross-checked-against-an-outside-instrument.md)'s
  `0.9740×` on replace-all and `1.1419×` on swap-rows, read by a driver nobody
  here wrote. Its own instrument is now understood: it measured the script and
  not the frame, and the frame is half the operation.
- **The direction survives.** Eight run means below 1.0, 43 of 48 rounds below
  1.0. UIx-on-subs is faster than Reagent-on-subs on a broad commit.
- **The size is bounded even where it is not pinned.** No run of the corrected
  clock read below 0.7709, and no round below 0.5745. A 37% win is outside
  everything this ensemble produced.

**The published verdict is therefore withdrawn and replaced by a direction, not
by a number.** *UIx-on-subs is faster on a broad commit; the margin is near 14%
and is at the edge of this instrument's resolution.*

---

## 7. Provenance

Blob hashes, not commit SHAs — a rebase has already invalidated one publication
in this programme. **The page half is at the same blobs as the audit's runs and
as the published run 5**, which is what makes the three sets of readings
comparable:

| file | blob | |
|---|---|---|
| `clock_app.cljs` | `15c4d3b1dd770c7cea3f2efa7aca4a343c55d34a` | unchanged from run 5 and the audit |
| `clock_views.cljs` | `7e48dbc0b3a974cd61a5c61e606333848877a31f` | unchanged from run 5 and the audit |
| `seam.cjs` | `a6789197e1bd9744879a2c8a143e48dc643b7f26` | unchanged from `rf2-cvvb7` |
| `clock_run.cjs` | `e145597127a87983377bd1a7ca40ca0388dfc18c` | **this page's driver** |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
git rev-parse <candidate>:$P   # must print e145597127a87983377bd1a7ca40ca0388dfc18c
```

| | |
|---|---|
| Published ensemble | **8 runs**, `M1` and `bulk300` in each, all at the blob above, 2026-08-02 01:49–02:06 AUSEST |
| Earlier ensemble | 14 runs at two earlier blobs of the same driver, taken 00:58–01:43, agreeing on every conclusion. Not averaged with the published ensemble and tabulated in [§8](#8-the-earlier-ensemble) |
| Discarded | **one run, and it is named.** The 15th run of the earlier ensemble was killed part-way by a harness timeout. The driver writes no dataset for a run that died, so nothing partial reached the analysis; its log is kept under a `DISCARDED` name |
| Runtime | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64, Playwright), React 19.2.0, node v24.13.0, `hardware-concurrency` 24, `device-memory` 32 |
| Build | `:hicasso-bench`, `:advanced`, `goog.DEBUG false`, via `--config-merge` only — no build id added, `implementation/shadow-cljs.edn` untouched |
| Box | quiet. Sampled at 0–19% total CPU before the ensemble, no competing agent, no editing during a run. The absolute floor — the load indicator `rf2-cvvb7`'s ladder established — read 2.37–2.44 ms on `bulk300`, at the bottom of the 3.06–5.50 ms range that ladder produced |
| Exit code | **1** on every run. Exit 1 from a control is scoped to the row that failed it; every whole-run gate cleared on every row of every run |
| Retained dataset | **NONE, and permanently so. Every table on this page was assembled by hand from console logs, and no run of either ensemble was taken with `HCLOCK_JSON` set.** So `0.8602×`, its `[0.7709 – 0.9058]`, the bands, the controls and the 43-of-48 count are *not recomputable from this repository* — they are readings, recorded, not re-derivable evidence. `rf2-ymi6j` established the same thing from the other side: the seam ladder's `data/ladder-ymi6j.json` is the only published ensemble on this instrument whose dataset survives. **A re-take with its datasets retained now exists and it is a different ensemble, not a reproduction of this one** — [§7.1](#71-the-re-take-was-taken-and-the-row-is-refused) |

**The gap is closed for the next run, not for this one.** At this page's blob
the driver's serializer wrote `taskNet` rounds and rounded summaries only, so
even a dataset from these runs could not have reproduced the figures the page
quotes. It now writes the raw per-sample readings on **all three windows** —
`rounds` (`taskNet`), `roundsTask` (raw `TaskDuration`) and `inPageRounds` —
plus both guard verdicts and the whole control record, and
`clock_readjudicate.cjs` pools an ensemble of those files and prints the table
that gets published. **A re-take must set `HCLOCK_JSON` on every run**, and no
figure from this page should be restated without one:

```bash
cd implementation && npm ci
for i in $(seq 1 8); do
  HCLOCK_ONLY=M1,bulk300 HCLOCK_SAMPLES=20 HCLOCK_JSON=out/run$i.json \
    node freehand/test/re_frame/bench/hicasso/clock_run.cjs
done
node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs out/run*.json
```

The single-run form this page's ensemble was taken with, retained because it is
what produced the readings above:

```bash
cd implementation && npm ci
HCLOCK_ONLY=M1,bulk300 HCLOCK_SAMPLES=20 \
  node freehand/test/re_frame/bench/hicasso/clock_run.cjs
```

**The driver has moved since the blob above, and this page is not re-pinned to
where it went.** `rf2-emvod` gave the published clock the guards the superseded
one had, `rf2-yd52q` gave the serializer the raw windows and retired the
*frame-inclusive* label from every printed reading, and `rf2-7iqb5` repaired the
changed-set control. Each is a change to the instrument, not to these runs; the
blob table pins what measured them and stays as it is.

### 7.1 The re-take was taken, and the row is refused

*(2026-08-07, `rf2-yd52q`, on a granted quiet box.)* Eight runs at
`c172f22bb0`, datasets retained under
[`data/clock-emvod/`](rows-re-adjudicated-on-the-corrected-clock.md#72-the-re-take-that-was-taken-and-what-it-refuses),
where the blobs, the box counters and every control's prediction against its
reading are tabulated. **`bulk300` is one of the five rows in each of those
runs**, so this row is now re-adjudicable from the tree — which is the whole of
what the re-take was for.

**The form this section prescribed above cannot produce publishable evidence,
and that is a correction to it rather than to the runs.** `HCLOCK_ONLY` narrows
the roster and `HCLOCK_SAMPLES=20` overrides the published design depth, and
`rf2-e87sk`'s two-tier write policy makes a run that does either write
`canonical: false` into its own file with the reason beside it — after which
`clock_readjudicate.cjs`'s first gate refuses the file and exits `3`. The
re-take therefore ran **the full five-row roster at the published depth**, with
`HCLOCK_JSON` set on every run and nothing else overridden. The narrowed form
above still measures; it just no longer certifies.

**What the row reads, and it is refused.** `uix-subs / reagent-subs` on
`bulk300` — the donor row this page withdrew `0.6291×` from:

| window | ensemble | reportable subset |
|---|---|---|
| raw `TaskDuration` (published) | 0.8539× [0.7232 – 0.9785] over 8 runs | **NONE** |
| `taskNet` (frame-only, diagnostic) | 1.0130× | — |
| in-page `performance.now()` (diagnostic) | 0.7497× [0.6885 – 0.8540] | — |

**Every one of the eight runs was refused by the three-point control**, which
read 1.3516 – 1.7838× against a predicted 2.0101×. No magnitude is drawn from
them and none is offered here: `0.8602×` above is neither confirmed nor
replaced. What the ensemble adds is that it is **retained**, so the next reader
re-adjudicates it rather than taking this paragraph's word for it.

The direction is the one thing the refusal does not touch — all eight runs read
below 1.0 on the published clock, as the withdrawal above concluded — and the
page's own rule is that a direction is not a magnitude.

## 8. The earlier ensemble

Fourteen runs taken before the instrument fault was found, at two earlier blobs
of the same driver. They are tabulated because selecting a run after seeing its
result is the fault this lane is built to avoid, and because they are the runs
that produced the fault: the first ten had no absolute printed at all, and the
absolutes added for runs 11–14 are what exposed it.

| | in-page | `taskNet` | runs |
|---|---:|---:|---:|
| `bulk300` donor bar | 0.6890 [0.6136 – 0.7466] | 1.0920 [0.9772 – 1.1780] | 14 |
| `M1` donor bar | 0.8953 [0.7914 – 0.9749] | 1.0445 [0.9405 – 1.1696] | 14 |

They agree with the published ensemble on both readings they share, and they
carry one figure worth keeping: of the first ten, **seven runs' in-page means
fall inside the published row's own run-mean spread** of 0.5792 – 0.6987.
That is the check that the two harnesses are measuring the same quantity on the
same clock, which is what licences comparing them on a different one.

One run of that ensemble is worth recording for a reason of its own:
**`rf2-cvvb7`'s band ceiling fired for the first time.** Its `bulk300` band came
out at **26.2%**, above the 25% tripwire and above the 4.4–18.5% the
nineteen-run ladder that calibrated it produced. The gate did what it was
written to do — refused every magnitude from that run before any control was
consulted — and its donor bar, 1.0952 on `taskNet`, was in line with the rest,
which is the shape a tripwire on a noisy statistic should have. No run of the
published ensemble came near it (8.8–19.7% on the corrected clock, 10.3–23.6% on
`taskNet`).

> **AND IT WAS THE TRIPWIRE RATHER THAN THE RUN (`rf2-ymi6j`).** This firing was
> on the **frame-only** clock, provably: at this page's driver blob the gate
> reads `o.verdict.seam.verdict.ceilingBreached` and nothing else. A re-take of
> the ladder that set the threshold measures `P(band > 25%)` at **9.1% per
> run**, so 25% sat inside the bulk of the statistic's own distribution rather
> than above its tail — which is how a gate calibrated never to fire came to
> fire three times in two days. The ceiling is now **35%**, whose own per-run
> false-fire rate is 2.7%, and the gate reads the published clock. (That
> ceiling was first published as the statistic's `q99`; the derivation is
> **withdrawn** — it used a resampling model that pooled blocks across runs.)
> See
> [the band re-calibrated](the-band-re-calibrated.md#5-the-third-explanation-which-is-the-one-the-data-names).
> This run's `bulk300` row stays refused: `rf2-7iqb5` refuses every bulk row on
> the three-point control.

The two earlier blobs differ from the published one in what the driver
**prints** and in nothing it measures or adjudicates; `git log -p` over the three
commits is the check.

---

## 9. What this hands the programme

- **`bulk broad 0.6291×` is withdrawn**, and the row it leaves behind is a
  direction rather than a magnitude. The strongest row on the converged page is
  now its most heavily qualified one.
- **The mechanism is measured, in milliseconds, not inferred.** The two arms
  differ by 42% on script and by 5% on frame, and the frame is half the
  operation. A window that closes when `flushSync` returns publishes the first
  number as though it were the second.
- **The clock everyone called frame-inclusive was subtracting the operation.**
  That is the finding to carry: `DevToolsCommandDuration` absorbs page script run
  through a protocol command, so `taskNet` is a frame-only reading, and the
  driver's own absolutes said so from its first run and were never printed.
  **The label is the fault, not a symptom of it** — it is what let a whole audit
  read a complement as a superset, and it is now refused by a test rather than
  by a sweep (`clock_exit_path.test.cjs`). Three beads carry the consequences —
  `rf2-emvod` for the candidate's rows, `rf2-aj15b` for the audit's page,
  `rf2-ymi6j` for the band calibration.
- **A control certified on pure-React arms cannot catch a fault in the substrate
  arms' half of the frame.** `ctl-2x` agreed between the two clocks to within
  2% while the arms whose ratio is published disagreed by hundreds of percent —
  the same shape the audit found, now with the reason attached.
- **`rf2-7iqb5` is promoted from related to blocking.** No amount of quiet box
  will make a page-doubling control adjudicate an update row; that is arithmetic,
  and it is the one thing standing between this row and a magnitude.
- **The candidate's own mount deficit is worse than published**, at `1.4896×`
  Reagent-on-subs on the corrected clock against `1.2107×` on the frame-only
  one. Recorded here because it is in these runs; adjudicating it is
  `rf2-emvod`'s, not this page's. ~~**That adjudication has since happened, and
  it publishes no magnitude**: `rf2-jcm3p` restated `M1` mount as a **REGIME**
  on 2026-08-06 — materially slower than both adapters, every corroborated
  reading above the amended `≤ 1.10×` UIx gate, `≤ 1.10×` not demonstrated —
  because the row's `ctl-2x` fails. `1.4896×` above is a reading stated under
  that failing control, not a published magnitude.~~ **That adjudication has
  since happened twice, and it publishes a magnitude** *(2026-08-07,
  `rf2-t2flm`, superseding the 2026-08-06 regime statement struck above —
  `rf2-jcm3p` read `ctl-2x`'s mean against `2.00×` where the implemented rule
  tests per-block band membership)*: the row and its conditional labels are at
  [`rf2-emvod` §4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row).
  `1.4896×` above is this ensemble's own reading and stays as such; it is not
  the published figure.
