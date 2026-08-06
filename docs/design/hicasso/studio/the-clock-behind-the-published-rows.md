# The clock behind the published rows

**Audit (rf2-8nqsl), of the record as it stood on 2026-08-01.** Every wall-clock
row the programme had published by that date was taken on one instrument:
`performance.now()`, sampled around a `flushSync` and closed the instant that
call returns. No published clock row **had** been read on an instrument that
could see the style recalculation, layout and paint the operation caused. This
page establishes that from source, prices it against a second clock on the same
samples, and says which rows survive. **That premise is now historical**, and
deliberately so: the audit is what moved the programme off it, and rows
published since — [bulk broad, re-taken](bulk-broad-re-taken.md),
[the candidate's rows](rows-re-adjudicated-on-the-corrected-clock.md),
[the band](the-band-re-calibrated.md) — are stated on raw `TaskDuration`, which
is script and frame in one number. Read the sentences above in the past tense
they are written in; the present tense is the paragraph that follows.

**Three clocks now appear in this programme's record, and every figure below
says which one produced it.** They are the **in-page** window; **`taskNet`**
(`TaskDuration − DevToolsCommandDuration`), which on this page's driver is the
frame with the arm's own script *removed*; and **raw `TaskDuration`**, script and
frame in one number, which is what the programme publishes on now. This page
originally called `taskNet` *frame-inclusive*. It is not — it is frame-**only**
(`rf2-yd52q`), and the restatement is worked through the body below rather than
annotated beside it.

The short version. **The two windows compared here are near complements rather
than a subset and its superset**, so what separates them is a *split* and not an
error rate: on substrate arms they divide the operation **+268% to +704%** apart,
while the pure-React control arms standing in the same rounds divide it by
**−12.5% to +7.9%** — because `floor` and `ctl-2x` are the two arms whose script
is small and roughly proportional to their frame. That separation is real and it
reproduces; the mechanism first attached to it does not. And it does not land
evenly across the record. **`M1 mount 1.0150×` is robust**: `taskNet` puts the
same components at `1.0110×` and raw `TaskDuration` at `1.0011×` — both inside
the published interval, the second 0.2% off the point estimate. **`bulk broad
0.6291×` is not**: the in-page window reproduces the published figure at
`0.6924×` on the same samples, and ~~`1.0509×` — parity~~ is a **frame-only**
figure, superseded by the re-take's **`0.8602×`** [0.7709 – 0.9058] on raw
`TaskDuration` ([bulk broad, re-taken](bulk-broad-re-taken.md)). The row's
verdict, *"UIx faster, all 60 rounds below 1.0"*, is withdrawn on every clock
that looks past `flushSync`, and replaced by a direction rather than a number.

> ## THIS PAGE'S COMPARATOR CLOCK WAS SUBTRACTING THE OPERATION (rf2-yd52q, rf2-emvod)
>
> The instrument here reports **`TaskDuration` less `DevToolsCommandDuration`**,
> and `DevToolsCommandDuration` **carries the page script a protocol command
> invokes**. Measured: an arm's `devtools` term less the tare's baseline tracks
> that arm's in-page window (`floor` 0.62 ms against an in-page 0.40,
> `reagent-subs` 2.76 against 2.30, `uix-subs` 2.01 against 1.60, `hicasso` 3.26
> against 2.80).
>
> **The door is what decides which rows that reaches, and this page's three all
> go through it.** `DevToolsCommandDuration` bills page script only when the
> script runs *inside* a protocol command. `M1`, `bulk300` and `narrow` are each
> driven by `page.evaluate(([r, arm]) => window.HCLOCK.sample(r, arm), …)`, which
> compiles to `Runtime.callFunctionOn` — verified in this page's own driver at
> the blob [§7](#7-provenance-and-reproduction) pins, `22b53abe9e…:433`, not from
> the prose around it. So the subtraction removes the operation's own script, and
> **the reading called *frame-inclusive* below is frame-ONLY**. The door table
> for every harness in the programme is
> [`rf2-emvod` §2](rows-re-adjudicated-on-the-corrected-clock.md#2-the-door).
>
> **What that does *not* reach matters as much.** An Input-domain command does
> not own the page's handler, so harnesses that click or type were never
> affected: the same driver's `keystroke` row (`keyboard.press`, `…:426` at the
> same blob) and the whole of the
> [outside cross-check](cross-checked-against-an-outside-instrument.md)
> (`page.click`) read script *and* frame on `taskNet` all along. Their figures
> stand, and must not be compared with this page's without saying which clock
> each is on — doing exactly that is how two complementary halves were once
> quoted against each other as one comparison.
>
> **The conclusions survive and one is strengthened**, and the figures below are
> restated in place rather than annotated. Full records:
> [bulk broad, re-taken](bulk-broad-re-taken.md) and
> [the corrected clock's page](rows-re-adjudicated-on-the-corrected-clock.md).
> `rf2-aj15b` carries this restatement; the band this page's runs are adjudicated
> against was calibrated on the same frame-only clock and is `rf2-ymi6j`'s.

---

## 1. What took each published row

Read from the harnesses, not from the prose around them. The lane defines one
clock and every published row reaches it:

| producer | clock, at file and symbol | window closes | class |
|---|---|---|---|
| `lane.cljs` — the shared clock | [`lane.cljs`'s `now-ms`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) → `js/performance.now()` | — | in-page |
| `lane/mount-arm!` — every single mount row | [`lane.cljs`'s `mount-arm!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) `t0 (now-ms)` · `flushSync` · `{:ms (- (now-ms) t0)}` | when `flushSync` returns | in-page |
| `lane/mount-batch!` — every batched mount row | [`lane.cljs`'s `mount-batch!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/lane.cljs) same shape, `k` mounts inside one window | when `flushSync` returns | in-page |
| `p0_converge_app` — M1, M2, broad, narrow | [`p0_converge_app.cljs`'s `mount-round!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs) `(lane/mount-batch! arm props k)` | via the lane | in-page |
| `coldmount_app` — the `1.0054×` witness | [`coldmount_app.cljs`'s `mount-round!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/coldmount_app.cljs) `(lane/mount-batch! arm props 1)` | via the lane | in-page |
| `hd8_rows` — the HD-008 donor rows | [`hd8_rows.cljs`'s `mount-round!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) `(lane/mount-arm! arm props)`; own windows in [`window-of`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) and [`yield-window!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs) | when the drain returns | in-page |
| `p0_reagent_app` — the first author's baseline | [`p0_reagent_app.cljs`'s `measure-mount!`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_app.cljs) `(lane/mount-batch! arm props k)` | via the lane | in-page |
| `p0_harness` — the UIx frontier arm's rows, in a **different tree** | [`p0_harness.cljs`'s `now-ms` and `mount-arm!`](../../../../implementation/core/test/re_frame/bench/p0_harness.cljs) its own `now-ms` → `js/performance.now()`, `t0` then `flushSync` | when `flushSync` returns | in-page |
| `hicasso_narrow` — the ratom-spine narrow write, in a **third tree** | [`hicasso_narrow.cljs`'s `now`](../../../../implementation/adapters/reagent/test/re_frame/bench/hicasso_narrow.cljs) `(defn now [] (js/performance.now))` | when the forced drain returns | in-page |

**The roster is closed and the answer is uniform — across all three producer
trees.** A sweep of the whole `bench/hicasso` tree finds no
`requestAnimationFrame` in any measuring path — the only occurrences are in DOM
correctness tests and in `z3vlz_probe`'s settle helper — and the only CDP traffic
in any driver before this audit is `HeapProfiler.collectGarbage` in
`retention_run.cjs`, which is a collector door for the heap rows and not a clock.
The two producers that live outside `bench/hicasso` reach the same answer by
their own code rather than through the lane: `p0_harness`'s docstring states it
outright — *"A reading is one `flushSync` window"* — and `hicasso_narrow` defines
its own bare `performance.now()`. There was no instrument anywhere in this
programme that could see past `flushSync` until `rf2-0qj9w` built one — and the
one it built read the frame *without* the script until `rf2-yd52q` corrected it,
so the first instrument in this programme to see a whole operation is the raw
`TaskDuration` clock, not the one this audit ran on.

Three published families are **not** clock rows and are out of scope:
`reads-per-boundary-heap-ladder`, `heap-fan-out-sweep` and
`uix-spine-per-read-decomposition` publish retained heap and allocation counts
only; `arm1-lean-react-dogfood-judgement` and
`controlled-input-two-implementations` publish no bar row at all.

## 2. Why the floor normalisation does not protect the ratio

The published bar figure is not a raw quotient. It is a **double ratio** —
[`p0_converge_app.cljs`'s `row-record`](../../../../implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs),
with `:numerator :uix-subs` and `:denominator :reagent-subs` declared in the same
function's `:red-zone` map:

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
affected by the fault: `ctl-2x` splits between the two clocks by under 13% on
every row measured here, while the arms whose ratio is published split by several
hundred percent. **And the reason is now measured rather than assumed** —
`ctl-2x` and `floor` are pure React, so their script is small and roughly
proportional to their frame, and the two windows therefore rank them alike
whichever half each one holds. A control that behaves the same under both halves
of a split cannot detect the split.

## 3. The cross-check

**Design.** The `rf2-0qj9w` instrument — built and first reported on
[the candidate's clock](the-candidates-clock.md), whose §4.1 raised this
audit — takes both clocks over the same samples:
an in-page `performance.now()` window around the arm's own synchronous drain,
and Chrome's renderer counters over CDP (`Performance.getMetrics`, `TaskDuration`
less `DevToolsCommandDuration`) read after a `rAF + setTimeout` settle, i.e.
after the browser has produced the frame the operation caused. **The settle point
is sound and the subtraction is not**: because every arm here is driven through
`page.evaluate`, the subtracted term carries the arm's own script, so this second
clock is the frame **without** the operation rather than the frame *and* the
operation — see the box at the top. Every figure in this section is therefore
`in-page` against `taskNet`, and is labelled that way. Its
`reagent-subs`, `uix-subs` and `floor` arms mount **the same components with the
same props as the published M1 witness** — `v/subs-root v/m1-subs`,
`ux/subs-root ux/m1` and `v/m1-floor` at `v/cells-n`
([`clock_app.cljs`'s `m1-arms` and `floor-mount-arm`](../../../../implementation/freehand/test/re_frame/bench/hicasso/clock_app.cljs)) — and it computes the same
floor-normalised, per-segment statistic, so dividing its two legs reproduces the
published quantity on the second clock.

**Predicted before the runs.** `ctl-2x` at `2.00×` the floor, band `[1.5 – 2.5]`,
every round (the lane's standing control); substrate arms diverging between the
clocks by several hundred percent and pure-React controls by under 15%; and the
published `uix ÷ reagent` statistic moving from below parity on the in-page
window to at-or-above parity on the second one.

**Outcome, per arm, across every quiet-box run.** Six runs each on `M1` and
`bulk300`, four on `narrow`; 6 rounds × (4 warm-up + 20 samples) per arm per
segment. **The column is the split between two near-complementary halves — the
script the in-page window holds against the frame `taskNet` holds — and not one
instrument's error against another's truth:**

| row | arm | in-page vs `taskNet` *(frame-only)*, n runs | mean |
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

**The published statistic, on this audit's two clocks over the same samples —
and, in the last column, on the clock that supersedes both.** The first two
columns are this audit's ensemble; the third is `rf2-yd52q`'s separate eight-run
re-take on raw `TaskDuration`, at the same page-half blobs, and is *not* the same
samples:

| row | in-page (n runs) | `taskNet` *(frame-only)* (n runs) | raw `TaskDuration` — script and frame | published figure |
|---|---|---|---|---|
| M1 mount | **0.8474×** (n=6) | ~~1.0110×~~ (n=6) | **1.0011×** [0.9464 – 1.0763] (n=8) | `1.0150×` [0.9820 – 1.0480] |
| bulk300 | **0.6924×** (n=6) | ~~1.0509×~~ (n=6) | **0.8602×** [0.7709 – 0.9058] (n=8) | bulk broad `0.6291×` [0.5792 – 0.6987] |
| narrow *(k=1)* | **0.9156×** (n=4) | ~~1.0062×~~ (n=4) | **not re-taken** — see below | bulk narrow `1.1754×` — *not comparable, see §5* |

The `taskNet` column is struck because it is a frame-only reading of a statistic
whose whole content is how two substrates divide script against frame, which is
the one thing that column cannot see. It is kept rather than deleted because the
gap between it and the in-page column *is* the measurement of the door.

**`narrow` has no corrected-clock donor bar, and this page does not manufacture
one.** `rf2-yd52q`'s ensemble ran `M1` and `bulk300` only. `rf2-emvod`'s
seven-run ensemble does cover `narrow` on raw `TaskDuration`, but publishes
`hicasso / reagent-subs` (`1.0236×` [0.9855 – 1.0900]) rather than the
`uix-subs ÷ reagent-subs` donor quotient this row needs — even though the driver
computes both, `uix-subs / reagent-subs` being one of its three `BAR_PAIRS`.

**That it cannot simply be read back off is a finding in its own right.** The
driver has written `roundsTask` and `inPageRounds` — every window's raw
per-sample readings — since blob `84aa25d9…`, which is the blob `rf2-emvod`'s
ensemble was taken at, so the recomputation *ought* to be a file read. **No
dataset from any published ensemble on this instrument survives**: the runs were
written to a gitignored `out/`, the authoring worktrees no longer hold them, and
nothing under `git ls-files` carries one. So the corrected-clock `narrow` donor
bar needs the box again rather than an afternoon with a JSON file, and it is
filed on that basis rather than asserted here.

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
moves the `taskNet` figure from `1.0078×` to `1.0092×` — because both arms sit
near the floor, an additive constant barely touches their quotient.
**Decompression does not recover the published `0.63×`; the move from `0.69` to
`1.01` is not an artefact of the instrument's own overhead.** Both ends of that
sentence are frame-only readings, and the conclusion is unaffected: the corrected
clock reaches `0.8602×`, which is likewise not `0.63×`.

The refused runs are excluded from no conclusion drawn here because they point
the same way as the passing ones on every row; where a magnitude is quoted the
control-passing subset agrees with the full set (`bulk300` on `taskNet`
`1.0078×` passing vs `1.0509×` all; `M1` `0.9992×` passing vs `1.0110×` all —
four frame-only figures, kept because they show the refused runs are not the
source of any conclusion).

## 4. Which rows change

**`bulk broad 0.6291×` — AT RISK, and this is the row to act on.** The
cross-check's in-page reading, `0.6924×`, lands **inside** the published row's
own run-mean spread of `0.5792 – 0.6987` — the two harnesses are measuring the
same quantity here. On those same samples ~~the frame-inclusive clock reads
`1.0509×`~~ **`taskNet` reads `1.0509×`, which is the frame with this row's
script removed and is superseded by the corrected clock's `0.8602×`**. The
published verdict is *"UIx faster. All 60 rounds below 1.0; all
20 strata wholly below it"*
([p0-converged-witness-set.md — the RED-ZONE table's `bulk broad` row](p0-converged-witness-set.md)); no reading past
the `flushSync` boundary is anywhere near it. A 37% margin measured
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

**`M1 mount 1.0150×` [0.9820 – 1.0480] — ROBUST, and STRENGTHENED by the
correction.** `taskNet` puts the same components at `1.0110×`, inside the
published interval; the control-passing subset reads `0.9992×`, also inside it.
**And the corrected clock lands closer still**: raw `TaskDuration` reads
`1.0011×` [0.9464 – 1.0763] over `rf2-yd52q`'s eight runs, 0.2% off the
published point estimate. The published verdict — *the interval contains 1.0, the
mount line sits at parity, the red zone has closed* — is what a clock that sees
the whole operation independently says, and it was already what the frame-only
half said. Note the honest asymmetry: the cross-check's *in-page* reading for
mount is `0.8474×`, which does **not** reproduce the published `1.0150×`, so the
two pages are not measuring an identical quantity on this row (`clock_app`
settles a frame between samples, which flushes Reagent's macrotask-scheduled
disposals that `p0_converge_app` lets accumulate across a whole row). The claim
here is therefore the weaker and safer one: **a clock that looks past `flushSync`
lands on the published value, so the row's verdict does not depend on the window
boundary.**

**`coldmount 1.0054×` [0.917 – 1.143] — ROBUST, by the same argument.** It is
the same 901-element / 300-boundary witness at parity, taken on a second
instrument by a second author. ~~`1.011×` is where a frame-inclusive clock puts
that witness~~ — that figure is this page's frame-only `M1` reading rounded, and
on the corrected clock the same witness reads `1.0011×`. Both are parity, which
is what the row claims. Its verdict is *"strata overlap, magnitude resolved,
excess mean 0.00 ms"* — a parity claim, and parity is what survives on every
clock that has looked at it.

**`bulk narrow 1.1754×` — EXPOSED, NOT ADJUDICATED.** The cross-check's `narrow`
row writes one commit per window (`k=1`) where the published row batches ten
commits into one window, so the numbers are not comparable and this audit does
not claim they are. What the row does establish is that the split between the two
halves is just as large on a narrow write (+398% to +484% on the substrate arms)
and that moving from the script half to the frame half moves the statistic toward
parity (`0.9156×` → `1.0062×`, +9.9%) — **both frame-only-versus-in-page figures,
and neither is the corrected clock**, which has no donor bar for this row (see
§3). A published row sitting 17.5% off parity, on a witness whose two halves
split this far apart, is not safe to quote without the check.

**The outstanding check is a ten-commits-per-window narrow row on raw
`TaskDuration`, and naming the clock is not pedantry.** `rf2-ph85f` was filed
asking for that row on "the frame-inclusive instrument", which is the label this
page then wore for `taskNet` — the clock that reads the frame with the row's own
script billed away, and the clock that produced the `1.0062×` struck above.
Taking the check on it would re-run the error rather than close it. The row needs
the box either way (§3: no dataset survives, and the corrected clock has no
`uix-subs ÷ reagent-subs` donor bar for `narrow`), so nothing here is a
recomputation and nothing here manufactures one.

**`M2 mount 1.0601×` *(diagnostic)* — unchanged in status.** It sits on the
100 µs clamp and was never quotable against the bar.

**The HD-008 donor rows — the mount half is now MEASURED, and this section's
conjecture held.** They were in-page by §1 and they published margins
(`donor-r1/reagent` 1.333–1.473, `donor-fh/uix` 1.358–1.746). This page asked for
a bounded check — add the donor arms to a harness reading raw `TaskDuration` and
re-read the `M` and `U` mount rows — and reasoned that these comparisons are
mostly *between* arms on the same React-hook spine, so they plausibly split their
work across the frame boundary more alike than Reagent and UIx do. **`rf2-2rtt6.31`
took that check** on a purpose-built instrument pair (`hd8_clock_app.cljs` /
`hd8_clock_run.cjs`), landed on `main` as `1ac48c4a0b`, and published it at
[the HD-008 donor page's re-take](hd8-composed-donor-arm.md#the-re-take-on-the-current-tree-rf2-2rtt631).
Three things came back.

- **The conjecture is upheld, with a number on it.** On the same samples the
  in-page window overstates the donor-vs-UIx margin by **~8–12%** and overstates
  UIx's advantage over Reagent — real, and one to two orders of magnitude short
  of the hundreds of points the Reagent-against-UIx rows split by. Same-spine
  arms do divide script against frame alike; that is now measured rather than
  assumed.
- **The published deficit against stock Reagent does not reproduce.** Every
  `donor / Reagent-path` range on the clock of record straddles 1.0, so the
  1.333–1.473× (`M`) and 1.448–1.542× (`U`) margins are withdrawn as magnitudes
  on that clock. It is a *thinner* result than it looks: replaying the driver's
  current `verdict()` over the committed datasets exits `5`, five of the six
  clock-of-record rows missed their positive control, and only `reagent-U`
  carries the non-reproduction unaided.
- **The write half is refused, not taken.** No bulk or narrow magnitude is
  published on the clock of record for these rows (`rf2-d2tzk`, `rf2-7iqb5`).

**What remains in-page here is `donor-fh`.** That arm was deliberately left out
of the re-take — it is `rf2-2rtt6.29`'s subject, and including it would have
changed `k` for a comparison that bead does not make — so `donor-fh/uix`
1.358–1.746 is still a `flushSync`-window margin and is the one HD-008 family
this audit's finding still reaches.

## 5. What could not be re-taken, and why

**The published harnesses cannot be made to see the frame in place.** A clock
that reads renderer counters needs one operation per frame; `p0_converge_app` runs an
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

    **This bullet is right and it was not the whole story (`rf2-yd52q`).** What
    is missing is the next step: `DevToolsCommandDuration` is a subset of
    `TaskDuration` on the same model, and it **includes the page script a
    protocol command invokes**. So the subtraction this instrument performs
    removes not just the protocol's own overhead but the operation itself,
    which on a substrate arm is most of what the row is measuring. Raw
    `TaskDuration` is the quantity the bullet describes; `TaskDuration −
    DevToolsCommandDuration` is that quantity with the arm's work taken out.
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
  `durationThreshold: 16` explicitly in
  [`clock_run.cjs`'s `EVENT_TIMING_INIT`](../../../../implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs).
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

    **And the inference was wrong in a way the undocumented status predicts
    (`rf2-yd52q`).** Subtracting an undocumented counter on the strength of an
    accounting model is exactly how a clock ends up measuring the complement of
    what it meant to. The check that would have caught it needed no
    documentation at all: print the absolutes beside the ratio, and
    `hicasso`'s in-page `2.938 ms` against its `taskNet` `2.466 ms` refutes a
    superset claim on sight. The driver prints them now.

## 7. Provenance and reproduction

Blob hashes, not commit SHAs — a rebase has already invalidated one publication
in this programme:

| artefact | blob |
|---|---|
| `clock_run.cjs` | `22b53abe9e2fcf172dbb752ed0c2d56c4ec6869c` |
| `clock_app.cljs` | `15c4d3b1dd770c7cea3f2efa7aca4a343c55d34a` |
| `lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `p0_converge_app.cljs` | `cf57b391e08797decbb1296742eb76b23d588a76` |

**The clock and the door, at that blob, for every figure on this page:**

| what | reading | door | class |
|---|---|---|---|
| the in-page column | `performance.now()` around the arm's drain | in-page | script, to where `flushSync` returns |
| the second column | `TaskDuration − DevToolsCommandDuration` (`taskNet`) | `page.evaluate` → `Runtime.callFunctionOn`, at the `clock_run.cjs` blob `22b53abe9e…:433` | **frame only** — the command billed the arm's script away |
| what supersedes it | raw `TaskDuration` | same door; the subtraction is simply not performed | script **and** frame |

The door is read from the driver at the blob above, not from the prose around
it, and `22b53abe9e…:426` is the same file's `keyboard.press` — the one call in
it that does *not* go through a command that owns the page's script. This page
publishes no keystroke row, so every figure on it is on the `page.evaluate` side.

Runtime: Chromium 147.0.7727.15 (Playwright), `:advanced`, `goog.DEBUG false`,
24 hardware threads, 32 GB. Taken 2026-08-01 AUSEST on a quiet box.

**No dataset from this ensemble survives.** The driver at this blob wrote only
`rounds` — the `taskNet` per-sample readings — to `HCLOCK_JSON`, and wrote it to
a gitignored `out/`; nothing was retained. So no figure on this page can be
restated by recomputation, and every corrected-clock number quoted here comes
from a *later ensemble* on a later blob, named where it appears. Raw readings for
all three windows have been written since blob `84aa25d9…` (`roundsTask`,
`inPageRounds`), which is what makes the next ensemble restatable without the box
— provided the file is kept.

```bash
cd implementation
HCLOCK_ONLY=M1      HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs
HCLOCK_ONLY=bulk300 HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs --no-build
HCLOCK_ONLY=narrow  HCLOCK_SAMPLES=20 node freehand/test/re_frame/bench/hicasso/clock_run.cjs --no-build
```

Exit 0 means the row's positive control passed strictly and its magnitudes are
reportable; exit 1 means the control refused and the run is directional only.

## 8. What this raises

~~`rf2-rguy1` — the external cross-check against
`krausest/js-framework-benchmark` — moves from a nicety to the next question.~~
**It landed** —
[cross-checked against an instrument nobody here wrote](cross-checked-against-an-outside-instrument.md)
— and it was pointed at bulk broad as this section asked. It also turns out to
be the one harness in the programme the correction never touched: it drives
through `page.click`, an Input-domain command that does not own the page's
handler, so its `taskNet` was script-and-frame all along and its ratios move at
most 1.7% between the two clocks (`rf2-emvod` §2.1, measured on that harness
rather than inferred from this one). **Its figures stand unaltered.** What must
not happen — and did — is quoting its readings and this page's in one table under
one label, which puts two complementary halves beside each other as though they
were the same quantity.

`rf2-cvvb7` — the segment seam that swung 3.8% → 34% on machine load — is
corroborated here at **31.9%** in a single round, and §2 shows the floor
normalisation that absorbs it is also what makes the published statistic inherit
the substrate arms' half of the split whole. **The band that study calibrated to
replace the seam as the gate was measured on the same frame-only clock as this
page**, on `bulk300` runs driven through the same `page.evaluate` door;
`rf2-ymi6j` carries that, and [the candidate's clock
§6.1–§6.2](the-candidates-clock.md#61-the-seam-measured-against-load) now says so
on every ladder figure.
