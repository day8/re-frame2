# The clock behind the published rows

**Audit (rf2-8nqsl).** Every wall-clock row this programme has published was
taken on one instrument: `performance.now()`, sampled around a `flushSync` and
closed the instant that call returns. No published clock row has ever been read
on an instrument that could see the style recalculation, layout and paint the
operation caused. This page establishes that from source, prices the error
against a frame-inclusive clock on the same samples, and says which rows survive
it.

The short version. The error is real and it is enormous — on substrate arms the
two clocks disagree by **+268% to +704%**, while the pure-React control arms
standing in the same rounds disagree by **−12.5% to +7.9%**. But it does not
land evenly across the record. **`M1 mount 1.0150×` is robust**: a
frame-inclusive clock puts the same components at `1.0110×`, inside the
published interval. **`bulk broad 0.6291×` is not**: on the same samples, the
in-page window reproduces the published figure at `0.6924×` and the
frame-inclusive clock reads `1.0509×` — parity. The row's verdict, *"UIx faster,
all 60 rounds below 1.0"*, does not survive the change of instrument.

> ## THIS PAGE'S CLOCK WAS SUBTRACTING THE OPERATION (rf2-yd52q)
>
> The re-take this page called for has landed —
> [bulk broad, re-taken](bulk-broad-re-taken.md) — and it found that the
> instrument here reports **`TaskDuration` less `DevToolsCommandDuration`**,
> while `DevToolsCommandDuration` **carries the page script a protocol command
> invokes**. The driver runs every arm's operation through exactly that door, so
> the subtraction removes the operation's own JavaScript. Measured: an arm's
> `devtools` term less the tare's baseline tracks that arm's in-page window
> (`floor` 0.62 ms against an in-page 0.40, `reagent-subs` 2.76 against 2.30,
> `uix-subs` 2.01 against 1.60, `hicasso` 3.26 against 2.80).
>
> **So the reading called *frame-inclusive* below is frame-ONLY**, and is nearer
> the in-page window's *complement* than its superset — which is why substrate
> arms diverge by hundreds of percent while pure-React controls do not, those
> being the arms whose script is small and proportional to their frame. The
> `+268%` to `+704%` separation in [§3](#3-the-cross-check) is real; the
> mechanism this page attaches to it is not.
>
> **The conclusions mostly survive, and one is strengthened.** On raw
> `TaskDuration` — script and frame in one number — `M1 mount` reads `1.0011×`
> against the published `1.0150×`, *closer* than the frame-only `1.0110×` here;
> and `bulk300` reads `0.8602×`, still nowhere near `0.6291×`. What changes is
> the `1.0509×` in the table below, which is a frame-only figure and is not the
> row. `rf2-aj15b` carries the restatement of this page.

---

## 1. What took each published row

Read from the harnesses, not from the prose around them. The lane defines one
clock and every published row reaches it:

| producer | clock, at file:line | window closes | class |
|---|---|---|---|
| `lane.cljs` — the shared clock | [`lane.cljs:86-92`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) `now-ms` → `js/performance.now()` | — | in-page |
| `lane/mount-arm!` — every single mount row | [`lane.cljs:185-187`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) `t0 (now-ms)` · `flushSync` · `{:ms (- (now-ms) t0)}` | when `flushSync` returns | in-page |
| `lane/mount-batch!` — every batched mount row | [`lane.cljs:208-213`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) same shape, `k` mounts inside one window | when `flushSync` returns | in-page |
| `p0_converge_app` — M1, M2, broad, narrow | [`p0_converge_app.cljs:750`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs) `(lane/mount-batch! arm props k)` | via the lane | in-page |
| `coldmount_app` — the `1.0054×` witness | [`coldmount_app.cljs:425`](../../../../implementation/freehand/test/re_frame/bench/hicasso/coldmount_app.cljs) `(lane/mount-batch! arm props 1)` | via the lane | in-page |
| `hd8_rows` — the HD-008 donor rows | [`hd8_rows.cljs:416`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) `(lane/mount-arm! arm props)`; own windows at [`665-678`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) and [`1005-1008`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) | when the drain returns | in-page |
| `p0_reagent_app` — the first author's baseline | [`p0_reagent_app.cljs:373`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_app.cljs) `(lane/mount-batch! arm props k)` | via the lane | in-page |
| `p0_harness` — the UIx frontier arm's rows, in a **different tree** | [`p0_harness.cljs:64-69, 220-221`](../../../../implementation/core/test/re_frame/bench/p0_harness.cljs) its own `now-ms` → `js/performance.now()`, `t0` then `flushSync` | when `flushSync` returns | in-page |
| `hicasso_narrow` — the ratom-spine narrow write, in a **third tree** | [`hicasso_narrow.cljs:179`](../../../../implementation/adapters/reagent/test/re_frame/bench/hicasso_narrow.cljs) `(defn now [] (js/performance.now))` | when the forced drain returns | in-page |

**The roster is closed and the answer is uniform — across all three producer
trees.** A sweep of the whole `bench/hicasso` tree finds no
`requestAnimationFrame` in any measuring path — the only occurrences are in DOM
correctness tests and in `z3vlz_probe`'s settle helper — and the only CDP traffic
in any driver before this audit is `HeapProfiler.collectGarbage` in
`retention_run.cjs`, which is a collector door for the heap rows and not a clock.
The two producers that live outside `bench/hicasso` reach the same answer by
their own code rather than through the lane: `p0_harness`'s docstring states it
outright — *"A reading is one `flushSync` window"* — and `hicasso_narrow` defines
its own bare `performance.now()`. There was no frame-inclusive instrument
anywhere in this programme until `rf2-0qj9w` built one.

Three published families are **not** clock rows and are out of scope:
`reads-per-boundary-heap-ladder`, `heap-fan-out-sweep` and
`uix-spine-per-read-decomposition` publish retained heap and allocation counts
only; `arm1-lean-react-dogfood-judgement` and
`controlled-input-two-implementations` publish no bar row at all.

## 2. Why the floor normalisation does not protect the ratio

The published bar figure is not a raw quotient. It is a **double ratio** —
[`p0_converge_app.cljs:1091-1092`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs),
with `:numerator :uix-subs` and `:denominator :reagent-subs` declared at
`1128-1129`:

```clojure
rz (mapv (fn [m] (/ (get-in m [:uix-subs      :ratio :uix-subs])
                    (get-in m [:reagent-subs  :ratio :reagent-subs])))
```

Each arm is first divided by the floor measured in *its own* segment of *that*
round. That normalisation is load-bearing for a different fault — the segment
seam, which this audit's runs read at **31.9%** in one round (`floor` at 3.348 ms
in the Reagent segment against 4.406 ms in the UIx segment) — and it is exactly
why `rf2-cvvb7` matters. But it gives no protection at all against the window
boundary, because the floor divides out of both legs:

> `(U/F) ÷ (R/F) = U/R` under either clock.

So the published number is the substrate arms' quotient, and it inherits their
error in full. The one arm whose reading is nearly instrument-independent — the
pure-React floor — is the one arm that cancels.

This is the mechanism the pure-React controls were never able to catch. A lane
that certifies its instrument on `ctl-2x` alone is certifying the arm least
affected by the fault: `ctl-2x` disagrees between the two clocks by under 13% on
every row measured here, while the arms whose ratio is published disagree by
several hundred percent.

## 3. The cross-check

**Design.** The `rf2-0qj9w` instrument — built and first reported on
[the candidate's clock](the-candidates-clock.md), whose §4.1 raised this
audit — takes both clocks over the same samples:
an in-page `performance.now()` window around the arm's own synchronous drain,
and Chrome's renderer counters over CDP (`Performance.getMetrics`, `TaskDuration`
less `DevToolsCommandDuration`) read after a `rAF + setTimeout` settle, i.e.
after the browser has produced the frame the operation caused. Its
`reagent-subs`, `uix-subs` and `floor` arms mount **the same components with the
same props as the published M1 witness** — `v/subs-root v/m1-subs`,
`ux/subs-root ux/m1` and `v/m1-floor` at `v/cells-n`
([`clock_app.cljs:191, 233, 241`](../../../../implementation/freehand/test/re_frame/bench/hicasso/clock_app.cljs)) — and it computes the same
floor-normalised, per-segment statistic, so dividing its two legs reproduces the
published quantity on a frame-inclusive clock.

**Predicted before the runs.** `ctl-2x` at `2.00×` the floor, band `[1.5 – 2.5]`,
every round (the lane's standing control); substrate arms diverging between the
clocks by several hundred percent and pure-React controls by under 15%; and the
published `uix ÷ reagent` statistic moving from below parity on the in-page
window to at-or-above parity on the frame-inclusive one.

**Outcome, per arm, across every quiet-box run.** Six runs each on `M1` and
`bulk300`, four on `narrow`; 6 rounds × (4 warm-up + 20 samples) per arm per
segment:

| row | arm | in-page vs frame-inclusive, n runs | mean |
|---|---|---|---|
| M1 | `reagent-subs` | +384.1% to +497.8% (n=6) | +429.8% |
| M1 | `uix-subs` | +317.4% to +376.1% (n=6) | +343.4% |
| M1 | `hicasso` | +619.6% to +704.2% (n=6) | +646.3% |
| M1 | `ctl-2x` *(pure React)* | −9.3% to +2.3% (n=6) | −4.8% |
| bulk300 | `reagent-subs` | +436.4% to +542.7% (n=6) | +479.5% |
| bulk300 | `uix-subs` | +267.8% to +296.1% (n=6) | +281.9% |
| bulk300 | `hicasso` | +511.0% to +575.2% (n=6) | +531.1% |
| bulk300 | `ctl-2x` *(pure React)* | −5.9% to +7.9% (n=6) | +0.9% |
| narrow | `reagent-subs` | +409.1% to +484.4% (n=4) | +454.0% |
| narrow | `uix-subs` | +398.4% to +412.0% (n=4) | +403.2% |
| narrow | `hicasso` | +374.2% to +448.9% (n=4) | +418.0% |
| narrow | `ctl-2x` *(pure React)* | −0.2% to +7.8% (n=4) | +2.7% |

`ctl-2x` ranges are the union over both segments' control arms. **This
independently reproduces `rf2-0qj9w`'s finding on different hardware** — that
run read +311% to +541% on substrate arms and 6–13% on controls; this one reads
+268% to +704% and under 13%. The separation between the two classes is the
finding, and it is not a one-box artefact.

**The published statistic, both clocks, same samples:**

| row | in-page | frame-inclusive | published figure |
|---|---|---|---|
| M1 mount | **0.8474×** (n=6) | **1.0110×** (n=6) | `1.0150×` [0.9820 – 1.0480] |
| bulk300 | **0.6924×** (n=6) | **1.0509×** (n=6) | bulk broad `0.6291×` [0.5792 – 0.6987] |
| narrow *(k=1)* | **0.9156×** (n=4) | **1.0062×** (n=4) | bulk narrow `1.1754×` — *not comparable, see §5* |

**Controls.** `ctl-2x` passed strictly (every one of 18 segment-rounds inside
`[1.5 – 2.5]`) on 2 of 6 `M1` runs, 1 of 6 `bulk300` runs and 3 of 4 `narrow`
runs. Every refusal was an **undershoot** — measured means 1.69–1.79 against a
2.00 prediction — which is this host, not transient load: the first ensemble ran
while two sub-agents loaded the box and refused 4 of 5; the quiet-box ensemble
refused at the same rate. The undershoot direction is a known property of this
lane, recorded on
[the UIx frontier page](p0-uix-on-subs-frontier-arm.md) as an 8% control
undershoot implying *"the clock instrument compresses ratios slightly toward
1.0"*.

**That compression is quantified rather than waved at, because it biases toward
the conclusion.** Solving `(f + 2)/(f + 1) = 1.84` puts the fixed per-sample cost
at `f ≈ 0.19` floor-units. Removing it from the `bulk300` control-passing run
moves the frame-inclusive figure from `1.0078×` to `1.0092×` — because both arms
sit near the floor, an additive constant barely touches their quotient.
**Decompression does not recover the published `0.63×`; the move from `0.69` to
`1.01` is not an artefact of the instrument's own overhead.**

The refused runs are excluded from no conclusion drawn here because they point
the same way as the passing ones on every row; where a magnitude is quoted the
control-passing subset agrees with the full set (`bulk300` frame-inclusive
`1.0078×` passing vs `1.0509×` all; `M1` `0.9992×` passing vs `1.0110×` all).

## 4. Which rows change

**`bulk broad 0.6291×` — AT RISK, and this is the row to act on.** The
cross-check's in-page reading, `0.6924×`, lands **inside** the published row's
own run-mean spread of `0.5792 – 0.6987` — the two harnesses are measuring the
same quantity here. On those same samples the frame-inclusive clock reads
`1.0509×`. The published verdict is *"UIx faster. All 60 rounds below 1.0; all
20 strata wholly below it"*
([p0-converged-witness-set.md:347](p0-converged-witness-set.md)); the
frame-inclusive reading is indistinguishable from parity. A 37% margin measured
on a window that closes before the frame is a margin in the fraction of the work
that happens to land inside `flushSync`, and the two substrates divide that
fraction very differently — Reagent commits inside its own drain, while the UIx
arm pays in the write leg before React is involved at all, which the programme
has already documented on both pages. ~~**This row needs a re-take before it is
quoted again.**~~ **The re-take has landed** —
[bulk broad, re-taken](bulk-broad-re-taken.md) — and **withdraws the row**: on a
clock that sees the operation's script as well as its frame it reads `0.8602×`
[0.7709 – 0.9058], the direction survives and no magnitude replaces it. The
`1.0509×` above is a frame-only figure, for the reason in the box at the top of
this page.

**`M1 mount 1.0150×` [0.9820 – 1.0480] — ROBUST.** The frame-inclusive clock
puts the same components at `1.0110×`, inside the published interval; the
control-passing subset reads `0.9992×`, also inside it. The published verdict —
*the interval contains 1.0, the mount line sits at parity, the red zone has
closed* — is what a frame-inclusive instrument independently says. Note the
honest asymmetry: the cross-check's *in-page* reading for mount is `0.8474×`,
which does **not** reproduce the published `1.0150×`, so the two pages are not
measuring an identical quantity on this row (`clock_app` settles a frame between
samples, which flushes Reagent's macrotask-scheduled disposals that
`p0_converge_app` lets accumulate across a whole row). The claim here is
therefore the weaker and safer one: **a frame-inclusive clock lands on the
published value, so the row's verdict does not depend on the window boundary.**

**`coldmount 1.0054×` [0.917 – 1.143] — ROBUST, by the same argument.** It is
the same 901-element / 300-boundary witness at parity, taken on a second
instrument by a second author, and `1.011×` is where a frame-inclusive clock puts
that witness. Its verdict is *"strata overlap, magnitude resolved, excess mean
0.00 ms"* — a parity claim, and parity is what survives.

**`bulk narrow 1.1754×` — EXPOSED, NOT ADJUDICATED.** The cross-check's `narrow`
row writes one commit per window (`k=1`) where the published row batches ten
commits into one window, so the numbers are not comparable and this audit does
not claim they are. What the row does establish is that the divergence is just as
large on a narrow write (+398% to +484% on the substrate arms) and that the
correction again moves the statistic toward parity (`0.9156×` → `1.0062×`,
+9.9%). A published row sitting 17.5% off parity on a clock with an error of this
size is not safe to quote without the check.

**`M2 mount 1.0601×` *(diagnostic)* — unchanged in status.** It sits on the
100 µs clamp and was never quotable against the bar.

**The HD-008 donor rows — EXPOSED, UNMEASURED.** They are in-page by §1 and they
publish margins (`donor-r1/reagent` 1.333–1.473, `donor-fh/uix` 1.358–1.746).
Their comparisons are mostly *between* arms on the same React-hook spine, which
plausibly split their work across the frame boundary more alike than Reagent and
UIx do — but *plausibly* is not measured. The bounded check is to add the donor
arms to the frame-inclusive page and re-read the `M` and `U` mount rows: about
one run per row per arm-set at the cost recorded in §6.

## 5. What could not be re-taken, and why

**The published harnesses cannot be made frame-inclusive in place.** A
frame-inclusive clock needs one operation per frame; `p0_converge_app` runs an
entire row — `dotimes` inside `doseq` inside `mapv` — in **one macrotask** with
no yield, which is a deliberate design its own `lane/settle!` docstring explains
and defends. Adding a settle between samples changes what the row measures (it
flushes Reagent's macrotask disposals that currently accumulate), so the result
would be a new row rather than a re-take of the published one. This is why the
audit is a **cross-check on the same components** and not a re-run of the
published ensemble, and why §4 phrases the mount conclusion the way it does.

Nothing in this audit re-takes a published row. ~~`bulk broad` needs one, on a
harness that does not yet exist.~~ **`rf2-yd52q` built it and took it** —
[bulk broad, re-taken](bulk-broad-re-taken.md).

## 6. Instrument facts, from primary sources

Verified against MDN, the W3C specs, the WHATWG HTML spec, the CDP protocol
definition and Chromium source — not from the summaries in this tree:

- **`performance.now()` is coarsened to 100 µs, and to 5 µs only under
  cross-origin isolation.** Confirmed in W3C *High Resolution Time*'s "coarsen
  time" algorithm and in Chromium's `time_clamper.h`
  (`kCoarseResolutionMicroseconds = 100`, `kFineResolutionMicroseconds = 5`). The
  runs here report the page is not cross-origin isolated and measure a smallest
  non-zero delta of 0.08 ms, consistent with the 100 µs quantum. One nuance worth
  recording: Chrome **randomises the rounding direction** per interval rather
  than snapping to a fixed grid, so repeated reads of one instant need not agree.
- **`Performance.getMetrics` `TaskDuration` is cumulative renderer main-thread
  task time and does include style, layout and paint, and does exclude
  raster/compositing.** Provable from Chromium's
  `inspector_performance_agent.cc`, which derives `TaskOtherDuration` by
  *subtracting* style, layout, script and DevTools time from `TaskDuration` — a
  subtraction that is only coherent if each is a subset of it — and which
  registers its observer on the renderer main thread only, while raster and
  compositing run on worker threads per `how_cc_works.md`.
- **`rAF + setTimeout(0)` is a sound settle point for "the main thread has
  finished producing this frame", and not for "pixels are presented."** The
  WHATWG "update the rendering" steps run animation-frame callbacks before style,
  layout and the rendering update, and a task queued from inside a rAF callback
  cannot run until those steps complete. Presentation happens afterwards, off the
  main thread.

**Two things checked in `rf2-0qj9w`'s instrument, one of which needed a fix.**

- **The Event Timing limits are stated correctly and are worth confirming,
  because the number invites a mistake.** `clock_run.cjs` says `duration` is
  rounded to the nearest 8 ms and that **the minimum `durationThreshold` an
  observer may ask for is 16 ms** — both accurate — and it passes
  `durationThreshold: 16` explicitly at
  [`clock_run.cjs:293`](../../../../implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs).
  The trap nearby is that the *default* threshold, absent that argument, is
  **104 ms** (W3C *Event Timing* §3.4: "let minDuration be 104"), not 16. The
  instrument does not rely on the default and does not claim it does. **No
  change needed; recorded so the distinction is not lost on the next reader.**
- **Nothing about `TaskDuration` or `DevToolsCommandDuration` is documented by
  the CDP — FIXED.** The protocol defines `Metric` as a bare `{name, value}` and
  documents no metric names, units or semantics at all. Subtracting
  `DevToolsCommandDuration` is well-supported by Chromium's own accounting model
  but is an inference from source, not a documented practice, and DevTools' own
  front-end does not do it. The comment implying Chrome documents this now cites
  `inspector_performance_agent.cc` instead.

## 7. Provenance and reproduction

Blob hashes, not commit SHAs — a rebase has already invalidated one publication
in this programme:

| artefact | blob |
|---|---|
| `clock_run.cjs` | `22b53abe9e2fcf172dbb752ed0c2d56c4ec6869c` |
| `clock_app.cljs` | `15c4d3b1dd770c7cea3f2efa7aca4a343c55d34a` |
| `lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `p0_converge_app.cljs` | `cf57b391e08797decbb1296742eb76b23d588a76` |

Runtime: Chromium 147.0.7727.15 (Playwright), `:advanced`, `goog.DEBUG false`,
24 hardware threads, 32 GB. Taken 2026-08-01 AUSEST on a quiet box.

```bash
cd implementation
HCLOCK_ONLY=M1      HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs
HCLOCK_ONLY=bulk300 HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs --no-build
HCLOCK_ONLY=narrow  HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs --no-build
```

Exit 0 means the row's positive control passed strictly and its magnitudes are
reportable; exit 1 means the control refused and the run is directional only.

## 8. What this raises

`rf2-rguy1` — the external cross-check against `krausest/js-framework-benchmark`,
which drives chromedriver and reads Chrome's own timeline — moves from a nicety
to the next question, and this audit sharpens its target: it should be pointed at
**bulk broad** first. Two independent frame-inclusive instruments agreeing that
the bulk margin is parity would settle the row; two disagreeing would be worth
more than either reading.

`rf2-cvvb7` — the segment seam that swung 3.8% → 34% on machine load — is
corroborated here at **31.9%** in a single round, and §2 shows the floor
normalisation that absorbs it is also what makes the published statistic inherit
the substrate arms' window error whole.
