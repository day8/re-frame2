# The candidate's clock — mount, bulk K=100/300, narrow, per-keystroke

**The candidate is slower on the clock, and the correction made it slower
still.** On the mount row, Hicasso Arm 1 mounts the 300-boundary witness at
**1.4896× Reagent-on-subs** [1.3488 – 1.5989] — on raw `TaskDuration`, which
holds the operation's own script as well as the frame it causes, above the ship
bar's `≤ 1.0×` and above
[the restated M1 red zone](../validation.md#the-clock-gates-restated-on-the-post-25-tree)
of `1.0150×`, with an interval that does not straddle 1.0. **That figure is a
historical observation and not the published magnitude**: ~~the row's positive
control fails, so [§4](#4-the-mount-row--a-regime-not-a-magnitude) states the
mount as a **regime** — *Hicasso mounts materially slower than both adapters,
and `≤ 1.10×` has not been demonstrated* — rather than as a number.~~
**Superseded 2026-08-07 (`rf2-t2flm`): the row publishes a magnitude again**,
`~1.184×` against direct UIx-on-subs and conditionally labelled. The figures and
the labels they must carry are at
[`rf2-emvod` §4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row)
and are not restated here. `1.4896×` remains what this programme read on
2026-08-01, on a heavier load regime than the published row is drawn from.
*(2026-08-06, `rf2-jcm3p` — **the withdrawal, since superseded**: this called
`1.4896×` ~~the figure of record~~ and
the mount ~~the only row of five whose positive control passed~~, and read the
deficit as ~~established and not merely indicated~~. The control that carries
this figure — `rf2-emvod`'s seven-run ensemble — reads `ctl-2x` at `1.8173×`
against a predicted `2.00×`, and two re-takes on verifiably idle boxes
reproduced the miss. The strict rule that refuses the three bulk rows in
[§6](#6-the-three-rows-this-page-refuses) binds here too; the asymmetry ends.
The direction survives intact — it is what the regime states.)*

**The number this page itself measured is `1.21×`** [0.9756 – 1.7208], and every
magnitude below is on that same superseded clock — `taskNet`, which subtracts the
operation's own script and is therefore frame-**only** (`rf2-yd52q`). Five runs
of this instrument put the mount at 1.01, 1.11, 1.10, 1.11 and 1.21 — every one
above parity, none below — and the corrected clock lands above all five. The
frame-only range *did* straddle 1.0 at n = 6, which is why this page originally
declined to call the deficit established; that hedge is a property of the
frame-only reading and does not carry onto the clock the row is now stated on.

On per-keystroke it is **indistinguishable from both donors at Event Timing's
resolution**, and all three are one frame — the row's published verdict, and
[§5](#5-per-keystroke--every-arm-is-one-frame) says what that does and does not
mean. The three bulk rows **could not be measured honestly**; this page
publishes no magnitude for them and [§6](#6-the-three-rows-this-page-refuses)
says exactly why — including one ground it has since **withdrawn as refuted**,
and the measured band that replaced it
([§6.1](#61-the-seam-measured-against-load), [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)).
Every row now carries the regime it was taken in.

That is the first paragraph because the result is negative, and a negative here
is decisive information about the six-week clock rather than a failure to
report.

> ## EVERY MAGNITUDE BELOW IS A FRAME-ONLY FIGURE (rf2-yd52q)
>
> This page's clock is `TaskDuration` **less `DevToolsCommandDuration`**, and
> `rf2-yd52q` has since measured what that subtraction removes: **the
> operation's own script**. Chromium bills a `Runtime.callFunctionOn` to
> `DevToolsCommandDuration` including the page script the command invokes, and
> `clock_run.cjs` runs every arm's operation through exactly that door. An arm's
> `devtools` term less the tare's baseline tracks that arm's in-page window —
> `floor` 0.62 ms against an in-page 0.40, `reagent-subs` 2.76 against 2.30,
> `hicasso` 3.26 against 2.80 — and `ScriptDuration` reads 0.013–0.029 ms for
> every arm, including a mount that builds 901 elements.
>
> So the figures below are style, layout and paint **with the script taken out**:
> frame-only rather than frame-inclusive, and nearer the in-page window's
> complement than its superset. The tell was on this instrument from its first
> run — on a substrate arm the in-page *absolute* exceeds the `taskNet`
> absolute, which no superset can do — and went unseen because the driver
> printed only their ratio.
>
> **The direction of §4's finding survives and its size does not.** On raw
> `TaskDuration` — script and frame in one number — `rf2-yd52q`'s eight-run
> ensemble reads `hicasso / reagent-subs` on `M1` at **1.4896×**
> [1.3488 – 1.5989] against the **1.2107×** below, and `hicasso / uix-subs` at
> **1.5001×** against **1.1865×**. The candidate's mount deficit is *materially
> worse* than ~~this page publishes~~ this page read before the correction, not
> better. *(2026-08-06, `rf2-jcm3p`: ~~neither mount figure is a published
> magnitude any longer — both are stated under a failing `ctl-2x`, and
> [§4](#4-the-mount-row--a-regime-not-a-magnitude) states the row as a regime.~~
> **Superseded 2026-08-07, `rf2-t2flm`**: the row publishes a magnitude again,
> and it is neither of the two figures above — see
> [`rf2-emvod` §4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row).
> Both stay here as dated readings of the corrected clock.)*
> `ctl-2x` is untouched — it reads
> 1.69–1.83× the floor on both clocks — so [§6](#6-the-three-rows-this-page-refuses)'s
> refusals stand unchanged.
>
> The driver now reports every bar, the band and the control on **both** clocks.
> `rf2-ymi6j` carries the band calibration. Full record:
> [bulk broad, re-taken](bulk-broad-re-taken.md).
>
> **`rf2-emvod` HAS NOW RE-ADJUDICATED THESE ROWS** —
> [the corrected clock's page](rows-re-adjudicated-on-the-corrected-clock.md) is
> the operative one and this page is superseded on every magnitude below.
> It also drew the boundary this banner did not have: the subtraction removes
> the operation's script only when the operation runs **inside** a protocol
> command, so §5's `keystroke` row — driven through the Input domain, because
> Event Timing reports user interactions — was **never affected**, and its two
> clocks agree to 0.3%. §4's `M1` and §6's three rows were. `taskNet` is
> therefore a label that meant two different things on two harnesses, and the
> [outside cross-check](cross-checked-against-an-outside-instrument.md), which
> clicks rather than evaluates, needs no restatement either.

Owner: the operator-owned standard bead `rf2-2rtt6.1`; this page `rf2-0qj9w`.

---

## 1. Why this page exists

Until it, **the programme had no wall-clock measurement of its own candidate.**
Two axes were measured — hook count (2, flat across 1/7/20 reads, counted at
React's own dispatcher) and per-read retained heap
([the ladder's §6](reads-per-boundary-heap-ladder.md#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index))
— and every clock figure the programme has published, `M1` mount `1.0150×` and
bulk-broad `0.6291×` on [the converged page](p0-converged-witness-set.md), is
about the **donors**: UIx against Reagent. Nothing on the clock was about
Hicasso.

That gap mattered more than the heap result, and the operator said so on
2026-08-01: *"the real test for performance is not bytes, that's a potentially
poor proxy, it is browser performance."* Heap is indirect. Allocation rate
drives GC pressure and GC pauses land in frame time, but the direction and
magnitude of that transfer were never measured, so the candidate's 415 B/read
excess over Reagent could have been invisible on the clock or could have
dominated a bulk update. Nothing distinguished those.

---

## 2. The instrument, and why it is not `performance.now()`

Every other clock entry in this lane wraps `performance.now()` around a
`flushSync` and banks the span. **That window ends when the JavaScript returns**
— before the style recalculation, the layout, the pre-paint and the paint the
mutation causes. The error would be tolerable if it were common-mode. It is
not: how much work a substrate leaves for the browser after its own stack
unwinds is precisely what differs between these arms, and Hicasso's whole design
concerns *when* work happens, so an in-page window systematically flatters
whichever arm defers most. [§4.1](#41-the-other-instrument-on-the-same-samples)
measures how badly.

So the clock here is **Chrome's own**. `Performance.getMetrics` over the
DevTools protocol reports the renderer's cumulative counters; the delta across
one operation, taken after the page has been made to produce the frame that
follows it, is main-thread task time **including** style, layout and paint
recording.

| what | how |
|---|---|
| the clock | CDP `Performance.getMetrics`, `TaskDuration` less `DevToolsCommandDuration`, on either side of one operation |
| the frame | the operation resolves only after `requestAnimationFrame` → `setTimeout(0)`, so the rendering lifecycle it caused has run |
| per-keystroke | `PerformanceEventTiming`, `type: 'event'`, `durationThreshold: 16`, plus `first-input` — paint-inclusive by construction |
| the key | Playwright `keyboard.press`, through the protocol's input domain; a JavaScript-dispatched event is not a user interaction, and Event Timing reports user interactions |
| what it does **not** see | off-main-thread rasterisation and compositing. Every figure below is main-thread cost, and no row implies otherwise |

### 2.1 The resolution figures, verified rather than remembered

Checked against primary sources on 2026-08-01 and quoted with the source,
because a remembered clock figure is how an instrument gets built on a number
that moved three versions ago.

| quantity | figure | source |
|---|---|---|
| `performance.now()`, not cross-origin isolated | **100 µs** from Chrome 91, all platforms (before 91: 5 µs desktop, 100 µs Android) | [Chrome for Developers, *Aligning timers with cross origin isolation restrictions*](https://developer.chrome.com/blog/cross-origin-isolated-hr-timers) |
| `performance.now()`, cross-origin isolated | **5 µs** | the same, and [MDN `Performance.now`](https://developer.mozilla.org/en-US/docs/Web/API/Performance/now) §Security requirements |
| `PerformanceEventTiming.duration` | rounded to the nearest **8 ms** | [MDN `PerformanceEventTiming`](https://developer.mozilla.org/en-US/docs/Web/API/PerformanceEventTiming) |
| Event Timing reporting floor | default `durationThreshold` 104 ms, **minimum 16 ms** | the same |

**The bench page is not cross-origin isolated**, so its in-page span carries the
100 µs quantum. The CDP counters do not: `TaskDuration` is a protocol value
rather than a web-exposed one, so the Spectre coarsening does not reach it. That
is a claim about the instrument, so the run **measures** it rather than
asserting it — the smallest non-zero per-sample delta observed is reported on
every row below, and it is 0.092–0.153 ms over ~980 distinct values per row.
Roughly one quantum's worth of granularity, from a counter that does not carry
the quantum.

The 8 ms rounding and the 16 ms floor are load-bearing for
[the keystroke row](#5-per-keystroke--every-arm-is-one-frame), and are why that
row publishes two instruments rather than one.

---

## 3. The design

Three segments, one substrate arm each, and the third is forced rather than
chosen. `install-adapter!` is once per process, so Reagent and UIx cannot be
interleaved inside one round — that much this lane already knew. What this row
adds is sharper: a bulk write here is `frame/replace-app-db!`, and **every** arm
mounted against that frame re-renders when it lands. Two substrate arms standing
in one segment would each pay for the other's writes.

| segment | arms | adapter |
|---|---|---|
| `reagent-subs` | `plumb`, `floor`, `reagent-subs`, `ctl-2x` | Reagent |
| `uix-subs` | `plumb`, `floor`, `uix-subs`, `ctl-2x` | UIx |
| `hicasso` | `plumb`, `floor`, `hicasso`, `ctl-2x` | UIx — Arm 1's React-hook spine is built over it, and its own witnesses install it |

The floor runs in all three: it holds no re-frame state, reads no subscription
and is untouched by which adapter is installed. Every figure is a ratio to the
floor measured in **that round of that segment**, so a cross-segment figure is a
ratio of two floor-normalised ratios ~~with the seam cancelled~~ — **and the two
floors are two readings, not one, so the seam does not divide out**:
`(H/F_H) ÷ (R/F_R) = (H/R) × (F_R/F_H)`, and the second term is the seam. That is
a weaker interleaving than one segment's, and this page says so rather than
describing it as though it were sample-level. ~~**That the seam cancels is now
measured rather than asserted**: the perturbation a busy box applies is
multiplicative, and a multiplicative perturbation cancels exactly.~~
*(2026-08-07, `rf2-s6qg4`: **withdrawn** —
[§6.1](#61-the-seam-measured-against-load)'s own banner records the re-take that
refuted multiplicativity.)* What §6.1 leaves standing is weaker, and is what this
design actually rests on: **the seam does not track load and is not attributable
to the segment**, so it is *controlled for* rather than cancelled. Nor is it
nominal — this page's own seams reach 22–34% on runs 3 and 4. Every row
publishes the seam, the null of the seam's own statistic, an orthogonal
decomposition of where the floor's variation lives, and the **band** a magnitude
must clear to be one.

- **Witness**: the `M1` page — 300 sub-reading boundaries, 901 elements, one
  `[:p0/cell i]` read per boundary. Identical markup on all three substrates,
  and the canonical-DOM gate (attribute names sorted) proves it rather than this
  sentence: **6 non-control arms across 3 segments, byte-identical, 27,224
  bytes.** The keystroke witness is a controlled field over 100 boundaries,
  **9,320 bytes**, identical across all six. *(2026-08-06, `rf2-8smbe`: this
  read `9,117` — the figure the published run measured, when the form had **one**
  field. `rf2-0qj9w` later took it to four, and the rebooked window in
  [§3.5](#35-the-rebooked-window-which-refuses-in-the-same-two-places-rf2-0qj9w-2026-08-04)
  reports the four-field form's canonical DOM at 9,320 B. The `M1` figure beside
  it is unaffected — that page carries no form — and is unchanged at 27,224.)*
- **B/E/Q stamp**: `B = 300` boundaries, `E = 300` boundary-query edges (one
  read each), `Q = 300` unique live query keys — so **Q = E**, fan-out 1, the
  mandatory distinct-query regime. The keystroke witness is `B = 104`,
  `E = 104`, `Q = 104`. Cache cardinality is part of the witness by ruling, and
  a clock row stamps it for the same reason a heap row does: two arms at
  different cardinalities are not the same experiment. *(2026-08-06,
  `rf2-uu81y`: the keystroke figures read `101` — the one-field form's, moved by
  the same `rf2-0qj9w` change that took 9,117 → 9,320 above. The `104` is read
  off a run rather than counted off this page: `clock_witness.cjs`'s `format`
  stamps `100 grid cells + 4 fields = 104 layer-1 recomputes per keystroke on a
  substrate arm`, over the census
  [§3.5](#35-the-rebooked-window-which-refuses-in-the-same-two-places-rf2-0qj9w-2026-08-04)
  reports. One edge per boundary, each on its own indexed key, so `Q = E = B`.
  No line in this driver's output is labelled `B/E/Q` — that name is this
  page's, for what the shape stamp and that census jointly say.)*
- **Schedule**: 6 rounds × (4 warm-up + 10 samples) per arm per segment, arms in
  the shared guard's reflecting order, segment order rotating with the round.
- **Verification**: every mount is read back against the arm's own element
  arithmetic; every write is read back at both ends of the range it claims to
  have changed. **0 unverified of 1,008** on every row (756 on the keystroke
  row).
- **Residue**: back to baseline on both censuses after every row — the lane's
  (`body-children`, `sub-entries`, `sub-ref-count`) and the candidate runtime's
  own (`cells`, `cell-refs`, `boundaries`, `edges`, `entries`). Every counter
  reads zero except `body-children`, whose baseline is **2**: `lane/residue`
  reads `document.body`'s attached element children, and the harness page has
  two of its own — the `<div id="app">` and the `<script>`. **The criterion is
  therefore 2, and a row that reads 3 is a container that survived its
  unmount**, left standing as a page the next row would be measured on top of.
  *(2026-08-06, `rf2-moe9a`: this read "zero on both censuses". `body-children`
  is not zero on any row and cannot be — a reader holding zero as the criterion
  reads a healthy run as already failing, which is the way round that explains a
  real leak away.)*

### 3.1 Two instrument repairs, both forced by a control that refused

Neither was a tidy-up. Each was predicted before the run that tested it, and the
run that failed is published beside the run that passed.

**Repair 1 — the teardown left the window, and a tare arm was added.** Run 1
ran clean end to end and its doubling control **failed at 1.5909×**
[1.1635 – 2.1509] against 2.00×. The decomposition said why: layout doubled
almost exactly (1.56 → 3.08 ms) while 3.6 ms of every sample did not move at
all. An additive constant reads `(2W + c)/(W + c)`, below 2 for any positive
`c`. Two things carried it — unmounting 300 or 600 boundaries *inside* the
measured window, and the driver's own per-sample round trip — so the mount row's
teardown moved out of the window and a `plumb` arm was added that mounts
nothing, writes nothing and settles the same frame. **The prediction registered
before run 2: subtracting a measured constant restores the control to 2.00×.**
Run 2's mount control passed at **1.9103×**, every round inside the band.

`plumb` is not the *zero-reading NOOP arm* this lane records as an instrument
fault. That fault is an arm that was supposed to do work and did none, so its
cheapness was mistaken for speed. This one is supposed to do none: its reading
**is** the quantity, it is published on every row, and it is subtracted rather
than compared. Every table below also carries the untared figure, and the tare
is small — 0.17–0.29 ms — once the teardown is out of the window.

**Repair 2 — one arm on the page at a time.** Run 2's *mount* control passed
strictly while its three *bulk* controls failed at 1.4304–1.5214×. One
instrument, two answers, and the difference between those rows is structural: a
mount row has one arm standing at any instant, and a bulk row had four —
901 + 901 + 1,801 elements. A frame in which nothing is dirty is nearly free
(the tare read 0.33 ms); a frame in which anything is dirty runs pre-paint and
paint over the whole document. Every bulk sample carried ~1.2 ms belonging to
arms not under test. **The prediction registered before run 3: hiding every arm
but the one under test moves the bulk controls toward 2.00×, and if it does not,
the co-mounted document was not the cause.** It moved them from 1.43–1.52 to
**1.70–1.82**. Cause confirmed; the rows still did not clear the strict rule
([§6](#6-the-three-rows-this-page-refuses)).

`display: none` and not an unmount, because the arm must stay **warm**: its
React tree, its subscription cache and its cells are what a steady-state write
meets, and remounting per sample would price a cold first write instead. The
show happens outside the window and is followed by a settle, so the full layout
of the arm about to be measured has already happened when the clock starts.

### 3.2 The positive controls, predicted before each run

Published on every run, passing or not, and adjudicated under the **strict**
rule — every round inside the band, not merely a range that overlaps it. The
lane's own `lane/control-verdict` uses the weaker overlap rule and its docstring
argues at length that the strict one is right; nothing on this page was
published under the weaker rule, so there is nothing here to re-adjudicate and
the strict rule is simply used. Under the overlap rule every row on this page
would pass, including the three it refuses — which is the difference the lane's
docstring is about.

| control | mechanism | prediction, written before the run |
|---|---|---|
| `ctl-2x` | the floor at exactly twice the boundaries — 600 cells, 1,801 elements | **2.00× the floor**, ±25%, on **every** segment-round |
| `ctl-50ms` (task) | 50 ms burned in a busy loop inside the keystroke handler | **≥ 40 ms** of extra main-thread task time, every segment-round. A *difference*, so the tare cancels in it |
| `ctl-50ms` (Event Timing) | the same | interactions with **duration p50 ≥ 48 ms** — the instrument must see the paint move |

`ctl-2x` and `ctl-50ms` are exempt from the canonical-DOM gate, and each
exemption is derived from the fact that makes that arm a control rather than
declared beside it.

### 3.3 The published run, and the four before it

Run 5 is the published run: it is the only one taken on a genuinely idle box at
the final tree. All five are tabulated because selecting a run after seeing its
result is the fault this whole page is built to avoid, and the selection here is
on a stated pre-condition — a quiet box — with objective evidence for it.

**The evidence originally cited for that pre-condition was the wrong one, and
[§6.1](#61-the-seam-measured-against-load) is why.** This paragraph used to
offer run 5's tight segment seams (3.8–8.4% against 22–34% on runs 3 and 4) as
the objective sign of a quiet box. A nineteen-run load ladder has since shown
the seam does not track load at all — 4.3% at idle against 4.6% with twenty of
twenty-four cores saturated — so it cannot be evidence of quietness, and
selecting on it would have been selecting on noise. The evidence that *does*
hold is the one the next paragraph already used: **the absolute floor level**,
which over that same ladder rose from 3.06 to 5.50 ms, an 80% span, and tracks
load exactly as a busy box should make it.

| run | instrument | `hicasso / reagent-subs` on M1 | `ctl-2x` | box |
|---|---|---|---|---|
| 1 | no tare, teardown inside the window, arms co-mounted | 1.0124 [0.7488 – 1.5479] | 1.5909 **FAIL** | quiet |
| 2 | + tare, teardown out | 1.1073 [0.9272 – 1.3221] | 1.9103 PASS | quiet |
| 3 | + one arm at a time | 1.1003 [0.8385 – 1.4147] | 1.8450 PASS | quiet |
| 4 | same as 3 | 1.1128 [0.6598 – 1.6208] | 1.7692 **FAIL** | **NOT quiet** |
| **5 — published** | same as 3 | **1.2107 [0.9756 – 1.7208]** | **1.9534 PASS** | quiet |

**Run 4 is discarded, and the reason is a pre-condition rather than its
result.** Its author was editing files while it ran. The evidence is in the row
that should be identical across runs: the M1 floor read 4.297 / 4.159 / 4.385 ms
in run 4 against 3.461 / 3.627 / 3.631 in run 5 and 3.565 / 3.586 / 3.373 in
run 3 — about 20% slower, on an arm with no substrate and nothing to vary. Its
numbers stay in the table above; its point estimate agrees with runs 2 and 3
anyway, so nothing about the verdict turns on dropping it.

**Five estimates, one direction.** 1.01, 1.11, 1.10, 1.11, 1.21. Across two
instrument configurations and one discarded box, the candidate's mount row is
above parity with Reagent-on-subs in every run and below it in none.

### 3.4 The quiet window, and why it publishes nothing (`rf2-0qj9w`, 2026-08-04)

A measurement window was booked for this page's two outstanding rows — the ruled
payload was `HCLOCK_ONLY=M1,keystroke` with the raw dataset retained — on a box
held exclusively idle for the run. **It publishes no magnitude, and this section
is the record of why.** The window is the sixth run of this instrument; it is
not in [§3.3](#33-the-published-run-and-the-four-before-it)'s table because that
table is a table of point estimates and this run yielded none that may be
transcribed.

| | |
|---|---|
| Tree | `65b45ba0632e277d6a269f3804c23c33bbc12c84`, the code tree of `origin/main` at open |
| Box at open | occupancy **1.48%**, 24 logical cores, 503 processes, 32.5 GB free |
| Box at close | occupancy **1.37%**, same process counts, 32.4 GB free |
| Occupancy method | summed per-process CPU-time deltas over a 10 s wall interval, divided by core count. **Not** `Win32_Processor.LoadPercentage`, which read 24% and 45% on this same idle box |
| Driver exit code | **1** — the positive control on `M1` |
| Retained dataset | `implementation/freehand/test/re_frame/bench/hicasso/data/clock-0qj9w/run1.json`, re-adjudicable with `clock_readjudicate.cjs` — which prints every table and **exits 3**, because the file predates `rf2-2rtt6.31`'s two-tier contract and carries no in-file `canonical` verdict *(2026-08-07, `rf2-emvod`)* |

**One refusal fired, and it is `M1`'s positive control.** `ctl-2x` measured
**1.8443×** [1.3837 – 2.4233] on raw `TaskDuration` against 2.00× ±25% under the
strict every-round rule of [§3.2](#32-the-positive-controls-predicted-before-each-run);
at least one segment-round fell below the 1.5 floor, so the row is refused and no
mount magnitude from it is reportable. Every *whole-run* gate cleared on both
rows — no page error, arm-order guard reportable, canonical DOM identical, 0
unverified of 1,008 on `M1` and of 756 on `keystroke`, band 21.4% against the
35% ceiling. The refusal is scoped to the row, exactly as the driver scopes it.

**The refusal is consistent with the mechanism [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)
already measured, which is that an idle box is the *hard* case.** The band is
widest at zero load — 24.6% against 8.3% at four competing cores, with
`corr(band, floor) = −0.49` — and this run's 21.4% sits in that regime. The
additive constant that makes a doubling control undershoot was re-measured here
at **c(2×) = 0.83 ms**, or 17.8% of a floor sample that does not scale with the
page. One run does not establish that quiet hurts this control; it does mean the
quiet precondition, inherited as non-negotiable, is not sufficient for it, and
the rebook should not assume more quiet is the repair.

**The `keystroke` row cleared every gate it has and still cannot be published,
for a reason that is about the rig rather than the box.** Both its controls
passed — `ctl-50ms` produced 49.84 ms [49.43 – 50.27] of extra task time over 18
segment-rounds against a predicted ≥ 40 ms, and Event Timing saw its paint move
at duration p50 48.0 ms over n = 180 against a predicted ≥ 48 ms. The repaired
witness reconciled exactly: 540 keys pressed, 466 interactions observed and 74
censored under the 16 ms floor, summing to the keys sent; and the recompute
census read `p0/cell ×100, p0/draft ×4` on all three substrate arms and no
subscriptions at all on every floor arm, which is validation.md's 104 and is
only reachable if the census-instrumented registration is the one the arms ran.
But **the driver labels all three of its bars `UNADJUDICATED`**: this row's
control burns a fixed 50 ms rather than doubling the page, so `control / floor`
reads `(F + 50)/F` and moves with `F`, which is not a pair whose true ratio is a
property of the page and therefore supplies no band. Under [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)'s
rule a bar with no band is not adjudicated, so the row has no magnitude to
publish however clean its witness is. **§5's published per-keystroke figures
predate that rule and would be labelled `UNADJUDICATED` if re-taken today.**

**A second reason bites independently, and it is the quantisation floor.** The
smallest non-zero per-sample delta on this row was **0.146 ms** over 1,003
distinct values. The separation between the candidate and the Reagent donor is
**0.052 ms** per sample on raw `TaskDuration` — about a third of one grid step.
A median over many samples can resolve below the grain, but a sign asserted from
a third of a step is not a finding, and the driver's own ranges agree: every bar
straddles 1.0.

**What the rebook needs is a change to the rig, not a longer window.** The
`keystroke` row wants a control whose ratio to the floor is a property of the
page — the three-point construction of
[a control that differences the constant away](a-control-that-differences-the-constant-away.md)
is the obvious donor — because until it has one, no amount of quiet produces an
adjudicable per-keystroke magnitude. Two smaller items belong with it: the
dataset above carries `label: null` and `load: null` because `HCLOCK_LABEL` and
`HCLOCK_LOAD` exist but were not part of the ruled payload, and a window's box
state ought to be stamped into its own artefact rather than only into prose.

### 3.5 The rebooked window, which refuses in the same two places (`rf2-0qj9w`, 2026-08-04)

[§3.4](#34-the-quiet-window-and-why-it-publishes-nothing-rf2-0qj9w-2026-08-04)
closed with a rebook. The rebook ran, on the same ruled payload and a box held
idle by the same discipline, and **it publishes no magnitude either.** It is the
seventh run of this instrument. What it adds is not a number: it is that the two
refusals are now **reproduced independently, on a different tree and a different
box state**, which moves them out of the category of things a second attempt
might have dissolved.

| | |
|---|---|
| Tree | `070be81738ca6d071de746d6d73ebbe94b4af5ab`, the code tree of `origin/main` at open |
| Box at open | occupancy **1.36%**, 24 logical cores, 529 processes, 28.8 GB free |
| Box at close | occupancy **3.12%**, 534 processes, 28.8 GB free |
| Occupancy method | summed per-process CPU-time deltas over a 6 s wall interval, divided by core count. **Not** `Win32_Processor.LoadPercentage` |
| Driver exit code | **1** — the positive control on `M1`, again |
| Retained dataset | `implementation/freehand/test/re_frame/bench/hicasso/data/clock-0qj9w/run2.json`, re-adjudicable with `clock_readjudicate.cjs` — which prints every table and **exits 3**, because the file predates `rf2-2rtt6.31`'s two-tier contract and carries no in-file `canonical` verdict *(2026-08-07, `rf2-emvod`)* |

**`M1`'s positive control refused again, and it refused from the other side.**
`ctl-2x` measured **1.8567×** [1.6562 – 2.6112] against 2.00× ±25% under the
strict every-round rule. Run six failed low — a segment-round beneath the 1.5
floor. This one fails **high**: no round fell below 1.5, and a round read 2.61
against the 2.5 ceiling. A control that misses its prediction in both directions
across two idle-box runs is describing a **wide** statistic, not a biased one,
which is the reading [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)
already argued from the load ladder. The additive constant re-measured at
**c(2×) = 0.777 ms**, 15.3% of a floor sample that does not scale with the page
(run six: 0.83 ms, 17.8%).

Every *whole-run* gate cleared on both rows, as it did last time: no page error,
arm-order guard **reportable** on both, canonical DOM identical (27,224 B on
`M1`, 9,320 B on `keystroke`), **0 unverified** of 1,008 and of 756, and the
segment seam published with its null on both rows (`M1` spread 2.9%, p = 0.874;
`keystroke` spread 5.2%, p = 0.717).

**The `keystroke` row again cleared every gate it has and again has no band.**
Both controls passed — `ctl-50ms` produced 49.84 ms [49.36 – 50.45] over 18
segment-rounds against a predicted ≥ 40 ms, and Event Timing saw its paint at
duration p50 56.0 ms over n = 180 against a predicted ≥ 48 ms. The repaired
witness reconciled exactly a second time: **540 keys pressed, 449 interactions
observed, 91 censored under the 16 ms floor, summing to the keys sent**, with 60
sent on every one of the nine arms; of 1,913 raised entries, 848 carried
`interactionId` 0 and were counted rather than made into records. The recompute
census read `p0/cell ×100, p0/draft ×4` on all three substrate arms and **no
subscriptions at all** on every floor and control arm — `validation.md`'s 104.
And all three bars are `UNADJUDICATED` for the reason §3.4 gave: a control that
burns a fixed 50 ms supplies no band. **Two independent runs now agree that this
row's blocker is the rig, not the box.**

**The quantisation check separates the two refusals cleanly, and this is worth
having.** On `M1` the grain was **0.095 ms** over 974 distinct values, and the
tared per-sample separations are 13 to 19 grid steps — 1.243 ms between the
candidate and the Reagent donor, 1.776 ms against UIx. `M1` is therefore **not**
quantisation-limited; its only limiter is the control. On `keystroke` the grain
was **0.181 ms** over 1,002 distinct values, and the candidate-to-UIx separation
is **0.043 ms — under a quarter of one grid step**, so that pair is **not
resolved as to sign** on top of being unadjudicated. The candidate-to-Reagent
separation there is 0.277 ms, about one and a half steps.

**One instrument finding the window did not go looking for.** The driver's
row-level control verdict and its bar-level adjudication are computed
independently, and **only the first reaches the exit code**. Had this window run
`HCLOCK_ONLY=keystroke` alone, `ctlFailed` would have been empty, the driver
would have printed `[clock] ok` and **exited 0** — for a row whose every bar it
had just labelled `UNADJUDICATED`. Even in this run, which exits 1 on `M1`, the
summary line reads `REPORTABLE: keystroke … Publish those and mark the rest`.
The exit code is sound for what it claims to gate; it simply does not gate
adjudication, and the summary line invites exactly the publication the bars
forbid. That is filed rather than fixed — the window takes measurements, it does
not repair the rig.

**The rig gap §3.4 named is closed in passing.** `HCLOCK_LABEL` and
`HCLOCK_LOAD` were part of this invocation, so `run2.json` carries
`label: "quiet-window-2 rf2-0qj9w 2026-08-04"` and `load: 1.355` rather than two
nulls. A window's box state is now stamped into its own artefact.

**What this run retires is the scheduling hypothesis.** Two windows, two idle
boxes, the same two refusals in the same two places. More quiet is not the
repair for either row, and a third window taken on the rig as it stands should
be expected to buy the same pair of refusals.

---

## 4. The mount row — a REGIME, not a magnitude

> ## THE ROW PUBLISHES A MAGNITUDE AGAIN, AND THIS SECTION'S TITLE IS SUPERSEDED (`rf2-t2flm`, ruled 2026-08-07)
>
> **The regime-only statement below is superseded.** `M1` publishes `~1.184×`
> against direct UIx-on-subs, conditionally labelled, on the two retained
> quiet-box ensembles. The figures, the estimators they are drawn on and the
> labels every one of them must carry are at
> [`rf2-emvod` §4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row),
> and they are **not** duplicated here: two copies of a published row is how a
> record starts disagreeing with itself, which is the failure this sweep exists
> to repair.
>
> **What fell was the ground, not a measurement.** `rf2-jcm3p` read `ctl-2x`'s
> *mean* against `2.00×` and called that categorical failure. The implemented
> rule never asked the mean to equal `2.00×` — `controlVerdict` tests per-block
> band membership, committed 2026-08-01, before either retained ensemble was
> captured — and **seven of fourteen runs pass it on both clocks**. So no new
> window was taken and nothing here was re-measured; a rule already in the tree
> was read correctly for the first time.
>
> **This section keeps its title and its anchor.** `#4-the-mount-row--a-regime-not-a-magnitude`
> is cited from five other pages, including the re-adjudication that carries the
> published row, and an anchor is part of how this record is addressed. Renaming
> it would strand those citations to spare a reader one line of reading; the
> banner does that job instead.
>
> **The withdrawal below stays in full**, because the reasoning it records is
> the reason the premise was worth re-examining.

> ## THE MOUNT MAGNITUDE IS WITHDRAWN AND THE ROW IS RESTATED AS A REGIME (`rf2-jcm3p`, ruled 2026-08-06)
>
> ***Superseded 2026-08-07 by `rf2-t2flm` — see the banner immediately above.
> Kept in full and unedited below.***
>
> **`1.4896×` [1.3488 – 1.5989] against Reagent-on-subs and `1.5001×` against
> UIx-on-subs are withdrawn as MAGNITUDES.** The strict rule that refuses the
> three bulk rows in [§6](#6-the-three-rows-this-page-refuses) binds here too,
> and the asymmetry this section used to carry — a magnitude published under a
> control that fails, beside three rows refused for that same failure — ends.
>
> **What the row publishes instead is a REGIME:** *Hicasso mounts materially
> slower than both adapters.* Every corroborated reading sits above the amended
> `≤ 1.10×` UIx gate — landed ensembles `1.5001×` and `1.4656×` against UIx,
> `1.4896×` [1.3488 – 1.5989] and `1.3737×` against Reagent, raw quiet-window
> recomputations `1.3484×` and `1.2066×` against UIx — and the direction is
> corroborated three ways: worst-case witnesses, the census rows, and an outside
> benchmark. **`≤ 1.10×` has NOT been demonstrated.**
>
> **Why no magnitude.** The row's positive control fails. `ctl-2x` reads
> `1.8173×` against a predicted `2.00×` over `rf2-emvod`'s seven runs, and two
> re-takes on verifiably idle boxes reproduced the miss at `1.8443×`
> ([§3.4](#34-the-quiet-window-and-why-it-publishes-nothing-rf2-0qj9w-2026-08-04))
> and `1.8567×`
> ([§3.5](#35-the-rebooked-window-which-refuses-in-the-same-two-places-rf2-0qj9w-2026-08-04)).
> The additive constant explains the undershoot arithmetically — `c ≈ 1.04 ms`,
> and `(2W + c)/(W + c)` is below 2 for any positive `c` — so it is not a
> property of mounts, and
> [§4.2 of the re-adjudication](rows-re-adjudicated-on-the-corrected-clock.md#42-ctl-2x-undershoots-on-the-mount-row-too-and-that-changes-its-diagnosis)
> measures the same constant on four rows that do wildly different work. **And
> no changed-set control can reach a mount**: a mount row's operation *is* the
> mount, so there is no standing page to write a changed set into and no
> changed-set axis to be linear in. Two clean windows on two idle boxes bought
> two refusals and no magnitude, which is what settled that more measuring
> cannot move this.
>
> **The figures below stay visible and are annotated rather than erased.** Every
> `1.4896×` on this page is a historical observation *stated under a failing
> `ctl-2x`; withdrawn as a magnitude 2026-08-06*. History is annotated, never
> deleted.
>
> **What would reopen it, precisely.** A sitting must declare in writing that
> its go/stop turns on locating `M1` against the `1.10×` line at a precision
> inside the corroborated spread — that the difference between ~`1.10×` and
> ~`1.20×`, or finer, would change its ruling. Only then: **one** pre-registered
> mount-axis three-point control (mount 150 / 300 / 600 boundaries, adjudicated
> `(T(600) − T(150)) / (T(300) − T(150))` → `3.00×`), accepting up front that it
> inherits the noise condition — `σ/T ≈ 3.5%` needed against a measured
> within-block IQR of 28–48%, and the band is **widest on an idle box**
> ([§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear): 24.6% at zero
> competing cores against 8.3% at four, `corr(band, floor) = −0.49`), so the
> quietest achievable box is that construction's *worst* case and it may refuse
> after the spend. A refusal there is a valid terminal answer. Absent that
> declaration, **no further `M1` instrument work.**
>
> `clock_run.cjs` labels this row `mount-regime` and prints its control status
> on every run, so a reader of the driver's output meets the disposition rather
> than inferring it.

**Positive control: PASS.** `ctl-2x` measured 1.9534× [1.7258 – 2.1817] over 18
segment-rounds against 2.00× ±25%, every round inside the band. **Arm-order
guard: reportable.** **Canonical DOM: identical.** **0 unverified of 1,008.**
**Segment seam: 4.9%** — the same floor read 3.461 / 3.627 / 3.631 ms across the
three segments. **Granularity: 0.092 ms** smallest non-zero delta over 974
distinct values.

**A sixth run refused this control on a verifiably idle box**, at 1.8443×
[1.3837 – 2.4233] with a round below the 1.5 floor, and published no mount
magnitude as a result —
[§3.4](#34-the-quiet-window-and-why-it-publishes-nothing-rf2-0qj9w-2026-08-04).
**A seventh refused it again on another idle box**, at 1.8567× [1.6562 – 2.6112]
— this time with no round below 1.5 and one above 2.5, so the two refusals sit
on *opposite* sides of the prediction —
[§3.5](#35-the-rebooked-window-which-refuses-in-the-same-two-places-rf2-0qj9w-2026-08-04).
Nothing in this section moves on either: they are refusals, not readings. What
they jointly establish is that this control's failures are not a quiet problem.

Ratio to the floor measured in that round of that segment, tared, 6 rounds:

| arm | ×floor | range | untared |
|---|---:|---|---:|
| `reagent-subs` | 1.1321 | [1.0157 – 1.2953] | 1.1255 |
| `uix-subs` | 1.1530 | [0.9350 – 1.4180] | 1.1454 |
| **`hicasso`** | **1.3613** | [1.0632 – 1.7917] | 1.3423 |
| `ctl-2x` (control) | 1.9680 / 1.9382 / 1.9540 by segment | — | — |

The bar arithmetic — two floor-normalised ratios, one against the other:

| row | measured | range | disposition |
|---|---:|---|---|
| ~~**`hicasso / reagent-subs`**~~ | ~~1.2107×~~ | [0.9756 – 1.7208] | ~~**SUPERSEDED — frame-only.**~~ ~~**REGIME, no magnitude** *(2026-08-06, `rf2-jcm3p`)*~~ **A MAGNITUDE AGAIN, CONDITIONALLY LABELLED** *(2026-08-07, `rf2-t2flm` — [§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row))*. Read **1.4896×** [1.3488 – 1.5989] on the corrected clock and **1.3737×** [1.3289 – 1.4331] on `rf2-emvod`'s heavier-regime ensemble — both above the `≤ 1.0×` win condition and the `1.0150×` red zone, neither straddling 1.0, **both dated historical observations, and neither is the published figure** |
| ~~**`hicasso / uix-subs`**~~ | ~~1.1865×~~ | [0.9753 – 1.3722] | ~~**SUPERSEDED.**~~ ~~**REGIME, no magnitude** *(2026-08-06, `rf2-jcm3p`)*~~ **A MAGNITUDE AGAIN, CONDITIONALLY LABELLED** *(2026-08-07, `rf2-t2flm` — [§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row))*. Read **1.5001×** on the corrected clock and **1.4656×** [1.3819 – 1.5088] on `rf2-emvod`'s ensemble; neither straddles 1.0, and both are dated readings rather than the published figure |

**What that means against the gates.** The P1 win condition is *mount ≤ 1.0×
Reagent-on-subs, same run and same instrument*, and the restated M1 red zone is
`1.0150×` because under Ruling 1 the red zone **is** the measured UIx ratio. The
candidate's point estimate is above both, in this run and in the four before it.
~~**On the clock of record the deficit is established**: `1.4896×`
[1.3488 – 1.5989] does not straddle 1.0, and `rf2-emvod`'s independent ensemble
agrees in direction at `1.3737×` [1.3289 – 1.4331].~~ ~~**The deficit's DIRECTION
is established and its SIZE is not published** *(2026-08-06, `rf2-jcm3p`: an
interval that misses 1.0 establishes a magnitude only against a control that can
adjudicate it, and this row's cannot — see the banner above. What the readings
jointly establish is the regime: above the `≤ 1.10×` UIx gate on every
corroborated reading, with `≤ 1.10×` not demonstrated.)*~~ **Both the direction
and a size are published, the size conditionally** *(2026-08-07, `rf2-t2flm`:
this row's control can adjudicate it after all — the rule tests per-block band
membership, not the mean — so the deficit has a magnitude again, at
[§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row)
and under the labels stated there. Neither figure struck above is that
magnitude.)* ~~Its range straddles
1.0, so at n = 6 the deficit is not *established*~~ — that was the frame-only
reading's hedge and it is spent. What still holds either way is
validation.md's own rule that *a margin under 5% is instrument-limited rather
than cleared*, which cuts against the candidate here: a 21% margin was already
well outside the instrument-limited band, a 49% one more so, and a 10–21% margin
repeated across five runs was never noise about zero.

**The deficit is the codec, not the spine, and the decomposition says so.**
Mean task milliseconds per mount, run 5:

| segment | `floor` | substrate arm | the arm's excess over its own floor |
|---|---:|---:|---:|
| `reagent-subs` | 3.891 | 4.181 | **+0.29 ms** |
| `uix-subs` | 4.226 | 4.608 | **+0.38 ms** |
| `hicasso` | 4.151 | 5.215 | **+1.06 ms** |

`hicasso` and `uix-subs` sit on the *same* React-hook spine, under the *same*
adapter, making the *same* 300 subscription reads into the *same* DOM. The
candidate pays 2.8× what that spine pays over the same floor, and the one thing
it does that the spine does not is walk 901 hiccup elements at runtime. That is
the same conclusion
[the HD-008 gate](../validation.md#p1-gate--the-composed-donor-arm-hd-008)
reached from the other side — *"the residual mount deficit is the hiccup
interpreter rather than the spine"* — reached here on the candidate's own
runtime and on a clock that reads past `flushSync`.

### 4.1 The other instrument, on the same samples

Both instruments read the same operations in the same samples, so the gap
between them measures **how the operation divides** between the two windows —
not the error one makes against the other, for the reason in the box below:

| arm | in-page ×floor | `taskNet` *(frame-only)* ×floor | in-page reads |
|---|---:|---:|---|
| `reagent-subs` (M1) | 5.6068 | 1.1321 | **+395%** |
| `uix-subs` (M1) | 4.7394 | 1.1530 | **+311%** |
| `hicasso` (M1) | 8.7318 | 1.3613 | **+541%** |
| `reagent-subs` (bulk300) | 5.8048 | 1.0243 | **+467%** |
| `uix-subs` (bulk300) | 4.3333 | 1.0242 | **+323%** |
| `hicasso` (bulk300) | 7.2986 | 1.0266 | **+611%** |
| `ctl-2x`, all segments and rows | 1.70 – 1.96 | 1.69 – 1.97 | −9.5% to +13% |

On the substrate arms the two windows differ by a factor of three to nine and —
the part that matters — **by a different factor per arm**. It is not a scale
error that cancels in a ratio: on M1 the in-page window puts
`hicasso / reagent` at 1.56× where `taskNet` reads 1.21×, and raw
`TaskDuration` — the clock that holds both halves — reads **1.4896×** (~~a
reading under a failing `ctl-2x`; the row publishes no magnitude —
[§4](#4-the-mount-row--a-regime-not-a-magnitude)~~ a dated reading, and not the
published magnitude, which is at
[§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) —
*2026-08-07, `rf2-t2flm`*). What this table is doing is comparing **windows**,
and that comparison is unaffected either way.

> **THE SEPARATION IS REAL AND THIS MECHANISM STATEMENT IS WRONG (`rf2-yd52q`,
> measured by `rf2-emvod`).** The `+300–610%` above is not one window
> mis-reading another; it is what two **near-complementary** windows look like
> when one arm's work is mostly script. The right-hand column is the frame with
> the script removed and the left-hand column is very nearly the script alone,
> so the two are not a subset and a superset at all. In milliseconds on `M1`,
> per sample, pooled over `rf2-emvod`'s seven runs: `hicasso` reads **8.507 ms**
> in-page and **5.520 ms** on `taskNet`, and its raw `TaskDuration` is
> **14.738 ms** — the two windows add to the whole, less the tare, on every arm.
> **A percentage difference between complements is not an error rate**, and the
> figures in this table should be read as the split between the two halves
> rather than as one instrument's inaccuracy.

The reason is visible in the decomposition. A substrate arm's mount spends
roughly a third of its **whole** cost inside `flushSync` and the rest afterwards
in style, layout and paint — and the *afterwards* half is where the three
substrates differ least, because they build byte-identical DOM. Measuring only
the first half therefore exaggerates the difference between them, and measuring
only the second dilutes it toward 1.0. **The control arms, which are pure React,
differ by only 6–13%** — which is exactly how a lane that checks only its
controls in-page would never notice, and equally why it could not have noticed
that the second window was frame-only either.

This does not overturn the published donor rows: those are Reagent against UIx,
taken on a different harness, on a page whose two readings are closer. What it
says is that no published clock row in this programme had ever been checked
against an instrument that sees past `flushSync`, and that the check is now
cheap. It was filed and it has been done — `rf2-8nqsl` did the audit,
[`rf2-rguy1`](cross-checked-against-an-outside-instrument.md) the outside
cross-check, and `rf2-yd52q` the re-take that found this second window was
itself only half an operation.

---

## 5. Per-keystroke — every arm is one frame

> **THIS ROW WAS TAKEN ON A WITNESS THAT HAS SINCE BEEN REPAIRED, AND ITS
> `interactions` COLUMN IS THE DEFECT (`rf2-0qj9w`, PR #7439).** Two faults,
> both since fixed and neither of them a property of the arms. The witness
> rendered **one** field per arm where `validation.md` specifies a four-field
> form over a 100-cell grid, and it gated no subscription recomputes at all.
> And the entries were grouped by `${interactionId || 0}` *inside* an
> already-known physical sample, so the zero-id `beforeinput` / `input` entries
> became a second pseudo-interaction beside the real keyboard one — which is how
> 60 keys per arm came to be reported as **109–115 "interactions"** below. Under
> web-vitals' rules a zero-id entry is part of **no** interaction, and one
> physical key forms at most one record.
>
> The repaired witness is four controlled fields with all four read back, one
> record per physical key, keys that raised no entry published as **censored**
> under the 16 ms floor rather than dropped, and a recompute census that must
> read 104 on a substrate arm and none on a floor arm — each of them a refusal
> that exits non-zero naming itself.
>
> ~~**Two re-takes exist and neither is published here.**~~ **Two re-takes exist
> and BOTH are now published — as the re-adjudication in the banner below**
> *(2026-08-06, `rf2-swwud`: this said the ruled posture was ~~to publish no
> magnitude and rebook~~. No magnitude is published, and the rebook is spent:
> the row is adjudicated by Event Timing rather than by the band, which the two
> retained datasets already answer.)* They were taken in the quiet windows of
> [§3.4](#34-the-quiet-window-and-why-it-publishes-nothing-rf2-0qj9w-2026-08-04)
> and [§3.5](#35-the-rebooked-window-which-refuses-in-the-same-two-places-rf2-0qj9w-2026-08-04),
> and both cleared every one of those gates — the second reconciling 540 keys
> pressed against 449 observed and 91 censored, with the census reading 104 on
> each substrate arm. The bars of both are `UNADJUDICATED` for want of a band —
> this row's fixed-50 ms control supplies none — and they are now labelled
> **DIAGNOSTIC** rather than left as magnitudes waiting for one.
> Both raw datasets are retained and re-adjudicable. **The figures
> below therefore stand as the last published reading and not as a current one**;
> the direction they report survives the correction (`rf2-emvod`), but their `n`
> and their interaction accounting do not.

> ## THIS ROW IS ADJUDICATED BY EVENT TIMING, NOT BY THE BAND (`rf2-swwud`, ruled 2026-08-06)
>
> **The section heading is the row's published verdict**, not a caveat on the
> way to a number. Two clean runs on two verifiably idle boxes each cleared
> every gate this row has and each produced three `UNADJUDICATED` bars, because
> `ctl-50ms` burns a fixed 50 ms and `control / floor` therefore reads
> `(F + 50) / F`, which moves with `F`. That is an excellent *sensitivity*
> control and not a pair whose true ratio is a property of the page, so it
> supplies no band, and [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)'s
> rule is defined on exactly such a pair. **The obstruction is the rig, not the
> box; no scheduling moves it, and the ruling adjudicates the row on the
> instrument that can.**
>
> **RE-ADJUDICATED FROM THE TWO RETAINED RUNS — no new window was taken.**
> `clock_readjudicate.cjs` reads the driver's own stored witness and prints
> this; the datasets are under
> `implementation/freehand/test/re_frame/bench/hicasso/data/clock-0qj9w/`.
>
> | arm | run 1 observed / censored | run 1 ET p50 | run 2 observed / censored | run 2 ET p50 |
> |---|---:|---:|---:|---:|
> | `reagent-subs` | 49 / 11 | **16.0 ms** | 47 / 13 | **16.0 ms** |
> | `uix-subs` | 45 / 15 | **16.0 ms** | 41 / 19 | **16.0 ms** |
> | **`hicasso`** | 49 / 11 | **16.0 ms** | 46 / 14 | **16.0 ms** |
> | `floor` (per segment) | 46 – 49 / 11 – 14 | 16.0 ms | 44 – 46 / 14 – 16 | 16.0 ms |
> | `ctl-50ms` (control) | 60 / 0 | 48.0 ms | 60 / 0 | 56.0 ms |
>
> Key accounting closes on both: **540 keys sent = 466 observed + 74 censored**
> (run 1) and **540 = 449 + 91** (run 2), 60 sent on each of nine arms, censoring
> published rather than dropped.
>
> **THE VERDICT.** *On this four-field / 100-cell witness, Hicasso,
> Reagent-on-subs and UIx-on-subs are indistinguishable at Event Timing's
> resolution; every observed interaction was one frame — 16.0 ms, both runs.*
> The control moves when the work moves — `ctl-50ms` at 48.0 and 56.0 ms against
> the arms' 16.0 — so the instrument is demonstrably not stuck.
>
> **POWERED TO DETECT, and this rider travels with the verdict.** Event Timing
> resolves **8 ms buckets above a 16 ms floor**, so this row detects only a
> difference that *crosses a bucket boundary*. The arms' separations are
> sub-frame and sit two orders below that; the finest per-sample step either run
> could resolve is 0.146 ms (run 1) and 0.181 ms (run 2); and every diagnostic
> `TaskDuration` bar straddles 1.0 at this `n`, in both runs. **Do not read
> "indistinguishable at one frame" as a measured tie below that resolution** —
> it is a statement about what the instrument can separate, and the honest claim
> the row is here to support is that no arm is near the frame budget.
>
> **The `TaskDuration` and `taskNet` bars below are DIAGNOSTIC and never
> magnitudes.** A repair that merely gave this row a band would not survive the
> resolution obstruction, so neither the three-point control on the dirty-cell
> axis nor a second band-supplying control was built: both are instrument work
> for a magnitude no current decision turns on, and the second would import
> [§4](#4-the-mount-row--a-regime-not-a-magnitude)'s additive-constant
> undershoot. They remain available **only** if a decision ever turns on a
> pre-declared sub-frame effect size, at which point the experiment is powered
> for that effect before its control is chosen. `clock_run.cjs` labels this row
> `responsiveness-regime` and reports it only when key accounting, the recompute
> census, the arm-order and canonical-DOM gates and **both** fixed-work controls
> pass.

**Both controls: PASS.** `ctl-50ms` produced 49.95 ms [49.44 – 50.49] of extra
task time (predicted ≥ 40 ms, every segment-round) and Event Timing interactions
at duration p50 48.0 ms (predicted ≥ 48 ms). **Guard reportable. Canonical DOM
identical. 0 unverified of 756. Seam 8.1%.**

Event Timing, grouped by `interactionId`. One keypress raises about five entries
— the run saw `keydown` 517, `keypress` 517, `beforeinput` 496, `input` 490,
`keyup` 290 — and they all end at the same paint, so reporting them individually
multiplies n by five and adds nothing. The latency of an interaction is the
longest of its entries, which is INP's own definition:

| arm | interactions | duration p50 | duration range |
|---|---:|---:|---|
| `reagent-subs` | 115 | **16.0 ms** | [16.0 – 16.0] |
| `uix-subs` | 109 | **16.0 ms** | [16.0 – 16.0] |
| **`hicasso`** | 115 | **16.0 ms** | [16.0 – 16.0] |
| `floor` (per segment) | 102 – 108 | 16.0 ms | [16.0 – 16.0] |
| `ctl-50ms` (control) | 120 | 48.0 ms | [48.0 – 56.0] |

**Every arm is at the instrument's floor and the instrument cannot separate
them.** 16.0 ms is one frame rounded to the nearest 8; the control proves the
instrument moves when the work moves. The honest statement is that on
paint-inclusive input latency the candidate and both donors are
**indistinguishable, and all three are one frame** — a pass on the axis a user
experiences, for all three, and not a finding *about* Hicasso. *(2026-08-06,
`rf2-swwud`: that sentence is now the row's **published verdict** rather than a
reading offered on the way to the finer one below. The re-adjudication in the
banner above states it on the repaired witness and both retained runs.)*

~~The finer instrument does separate them, and reports differences far below the
frame budget:~~ **The finer instrument's readings are DIAGNOSTIC —
UNADJUDICATED, never magnitudes** *(2026-08-06, `rf2-swwud`: this said the finer
instrument ~~does separate them~~. It does not separate them at this `n` — every
bar straddles 1.0, in this run and in both re-takes — and it carries no band to
adjudicate one if it did. The numbers stay, as the diagnostics they are.)*

| arm | task ms/keystroke | ×floor (tared) | range |
|---|---:|---:|---|
| `reagent-subs` | 2.310 | 1.2605 | [1.0038 – 1.9780] |
| `uix-subs` | 2.044 | 1.0885 | [0.9626 – 1.1547] |
| **`hicasso`** | **2.199** | **1.1405** | [0.9431 – 1.3397] |
| `floor` (per segment) | 2.08 – 2.19 | 1.0 | — |
| `ctl-50ms` (control) | 51.9 – 52.1 | 34.4 – 37.3 | — |

`hicasso / reagent-subs` = **0.9678×** [0.5318 – 1.3259] and
`hicasso / uix-subs` = **1.0500×** [0.9119 – 1.2033]; both straddle 1.0. **Both
are `DIAGNOSTIC — UNADJUDICATED`**: this row has no band-supplying control, so
neither is a magnitude and neither ever will be under this rig.

> **THIS ROW SURVIVES THE CORRECTION UNCHANGED, and it is the only one that
> does (`rf2-emvod`).** Its operation is a real key through the protocol's
> **Input domain**, not a `page.evaluate`, so `DevToolsCommandDuration` never
> held its script — `ScriptDuration` reads **49.97 ms** on the `ctl-50ms` arm
> here against 0.02–0.06 ms on every arm of every other row, and the `devtools`
> term varies by **0.069 ms** across arms that differ by 50 ms of work. On raw
> `TaskDuration` the same bar reads **1.0049×** against the 1.0078× above: a
> **0.3%** move, where `M1` moves 27%. The figures in this section are
> script-and-frame readings already.

Two things belong beside them. **The keystroke floor is not a lower bound**, and
the first draft of this witness labelled it one. A `useState` write re-renders
the whole 101-element tree top-down; a subscription write re-renders only the
boundary whose value moved. Which is cheaper depends on what else is on the
page, and it changed sign between run 2 and run 3. It is a calibrator and
nothing else, and it is published as one. And **2.0–2.3 ms of main-thread work
inside a 16.7 ms frame** is the shape of the whole row: no arm here is near the
budget on this witness, so the row does not discriminate and is not being asked
to.

---

## 6. The three rows this page refuses

> **STILL REFUSED ON THE CORRECTED CLOCK, and two of them change sign
> (`rf2-emvod`).** On raw `TaskDuration`, `hicasso / reagent-subs` reads
> **1.1494×** on `bulk300` (from 1.0703×), **1.1089×** on `bulk100` (from
> 0.9859× — below parity to above it), and **1.0236×** on `narrow`. The
> controls still fail: 3, 2 and 4 runs of seven. A frame-**only** clock reads
> the half of an update the two arms share — both writes produce the same
> 901-element page — and dilutes the half where they differ toward 1.0, which
> is why these rows looked quieter than they are. `narrow` is the exception and
> genuinely reads parity. Full adjudication:
> [the corrected clock's page](rows-re-adjudicated-on-the-corrected-clock.md#4-the-rows).

`bulk300`, `bulk100` and `narrow` were measured, and their magnitudes are **not
reportable**. In run 5 the numbers are perfectly quiet-looking — seams of 3.8%,
8.4% and 5.7%, and `hicasso / reagent-subs` at 1.0100, 0.9902 and 1.0369, every
range straddling 1.0 — and that is exactly the situation in which a control
earns its place. It refused.

1. **The positive control failed under the pre-registered strict rule, in every
   instrument configuration and every run.** Run 5's means land inside the band
   (1.7450 / 1.6642 / 1.8298 against 2.00 ±25%) but individual rounds do not:
   the worst reads **1.2181×**, and in run 3 a round read **0.9376×** — a round
   in which the control saw no doubling at all. Under this lane's published
   *overlap* rule they would pass. Under the strict rule this page
   pre-registered they do not, and a control whose worst round is wrong has
   caught something.
2. **The rows move more between runs than the effect they report.**
   `hicasso / reagent-subs` on `bulk300` reads **0.8662×** (run 2), **1.3767×**
   (run 3), **0.9802×** (run 4) and **1.0100×** (run 5). A 59% spread that
   changes the sign of the verdict. The mount row's five estimates span 1.01 to
   1.21 and never change sign; these do.
3. ~~**On runs 3 and 4 the cross-segment floor seam exceeded the effect.**~~
   **WITHDRAWN — measured and refuted, `rf2-cvvb7`.** The observation stands:
   the *same* floor — identical work, no substrate — read 3.147 / 2.437 /
   2.349 ms across the three segments on run 3's `bulk300`, a **34% spread**,
   against 23% and 22% on the other two bulk rows. The *inference* does not.
   ~~Floor-normalisation cancels a multiplicative seam and not an additive one,
   and [§6.1](#61-the-seam-measured-against-load) measures which this is: it is
   multiplicative, it cancels exactly,~~ *(2026-08-07, `rf2-s6qg4`: the
   multiplicativity clause is **withdrawn** — §6.1's own banner records the
   re-take that refuted it, and a seam of this size does not divide out of a
   cross-segment ratio.)* **The withdrawal stands on its other leg**, which is
   the one that was never about cancellation:
   [§6.1](#61-the-seam-measured-against-load)'s relabelling null puts the
   variation on the **round**, so the seam is not attributable to the
   segment at all — a spread between three segments' floors is not a property of
   the segments being compared, and this ground read it as one.
   **The rows are still refused, on grounds 1 and 2 and on the
   band `rf2-cvvb7` put in this ground's place** — which refuses them on a
   quantity that was measured rather than assumed. A ground is withdrawn here
   because it was shown wrong, which is the only reason a refusal may drop one.

**One qualitative result from these rows is robust and needs no control**,
because it does not depend on a magnitude. On the `narrow` row all three
substrate arms localise, by very nearly the same amount. Against a floor that
re-renders its whole tree, `reagent-subs` reads 0.2896× floor, `uix-subs`
0.3046× and `hicasso` 0.2952×, and layout falls from 1.35 ms (floor) to ~0.14 ms
on every substrate arm. **The candidate localises a one-cell write, and it is
neither better nor worse at it than the donors are.** The `narrow`-as-a-law win
condition — commit-side dirty-set flat in `B` across 300/600/1,200/2,400 — is a
different experiment and this run does not attempt it.

### 6.1 The seam, measured against load

> **EVERY FIGURE IN §6.1 AND §6.2 IS ON THE FRAME-ONLY CLOCK (`rf2-ymi6j`).**
> Established from the driver rather than the prose: `seam_ladder.cjs` forks its
> spinners and then spawns `clock_run.cjs --no-build` with `--only` defaulting to
> **`bulk300`**, and `clock_run.cjs` drives that row through `page.evaluate` →
> `Runtime.callFunctionOn`. So every ladder number below — the seam, its null,
> the orthogonal decomposition, the band and its ceiling — was computed on
> `taskNet`, which `rf2-yd52q` showed is the frame with the arm's own script
> subtracted out. **It cannot be recomputed**: at this study's driver blob
> (`f5bb751d…`, [§7.1](#71-the-seam-study-rf2-cvvb7)) the term `roundsTask` did
> not exist, `HCLOCK_JSON` carried only the `taskNet` per-sample readings, and no
> dataset from the nineteen runs survives.
>
> **THE LADDER HAS SINCE BEEN RE-TAKEN ON BOTH CLOCKS — see
> [the band re-calibrated](the-band-re-calibrated.md).** Nineteen fresh runs of
> this exact design, on this box, at the same six rungs. Three of this section's
> conclusions survive it and one does not:
>
> - **the seam still does not track load**, on either clock;
> - **the variation still lives on the round** rather than the segment;
> - **the band is the right statistic**, and it is now calibrated on the
>   published clock at 4.4% – 31.1%, mean 14.0%;
> - **the multiplicativity argument is WITHDRAWN.** Pure multiplicativity
>   predicts `ctl-2x / floor = 2.00` at every rung with no variance at all;
>   nineteen runs read **1.71 [1.62 – 1.84]**. The +0.41 correlation below was
>   never diagnostic — it reads +0.88 on one nineteen-run ensemble and −0.04 on
>   another taken twenty-five minutes later on the same box.
>
> And it found something this section did not look for: **the band is widest on
> an IDLE box**, `corr(band, floor) = −0.49`.

Ground 3 above rested on a comparison between two runs — 34% on one, 3.8% on
another — with *"nothing changed but how busy the machine was"* offered as the
difference. Two points are a line through two dots. `rf2-cvvb7` put a stated
number of competing busy cores on the box and took the row nineteen times.

`seam_ladder.cjs` forks *N* spinners, each walking a 4 MB array so it competes
for cache and memory bandwidth as well as cycles, runs the clock, and kills
them. Every spinner carries its own deadline, so it cannot outlive its window if
the parent dies, and `--load` is capped four cores below the box.

All eight columns are `taskNet`, the frame-only clock:

| competing cores | runs | floor ms | seam | SEGMENT | ROUND | POSITION | band |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 4 | 3.433 | 4.3% | 5.5% | 30.2% | 6.1% | 9.3% |
| 2 | 3 | 3.728 | 7.0% | 4.9% | 41.4% | 9.9% | 6.8% |
| 4 | 3 | 4.442 | 5.9% | 6.1% | 17.0% | 5.9% | 8.0% |
| 8 | 3 | 4.696 | 3.5% | 4.0% | 27.4% | 5.0% | 9.5% |
| 12 | 3 | 5.365 | 3.7% | 4.8% | 20.3% | 3.8% | 13.5% |
| 20 | 3 | 4.659 | 4.6% | 4.0% | 8.2% | 3.2% | 6.4% |

**The ladder unquestionably moved the box**: the absolute floor rose from 3.06
to 5.50 ms, an **80% span**. It is monotone to twelve cores and turns over at
twenty, which is not explained here and is left in rather than smoothed.

**The seam did not move with it.** Over all nineteen runs it read **0.1%–16.4%,
mean 4.8%**, with no trend — 4.3% at idle against 4.6% at twenty saturated
cores. Row position inside the process does not explain it either: a five-row
run read 4.9 / 2.4 / 1.1 / 9.4 / 3.3% across its five rows. So the published
34% is not what a busy box does to this design, and **what produced it remains
unidentified**.

**And it is not attributable to the segment.** The seam is a max-over-min of
three noisy block medians, which has a long right tail even when segment
identity means nothing at all, so the instrument now takes that tail
explicitly: relabel the three segments *within* each round — keeping every
round's three blocks together and destroying only which segment owned which —
and recompute the same statistic.

| the seam's own null — **Monte Carlo**, pooled over 19 runs × 2,000 sampled relabellings | |
|---|---:|
| median | 5.5% |
| q95 | 14.1% |
| q99 | 17.6% |
| q99.9 | 23.9% |
| largest of the 38,000 draws | 26.8% |

**That null is SAMPLED and its draw count is not pinned** *(2026-08-06,
`rf2-cvvb7`)*. `seamNull()` in `seam.cjs` takes seeded random within-round
relabellings — one of the six permutations of three segments per round, so
6⁶ = 46,656 assignments over a six-round run — and does not enumerate them. So
*"beyond all draws"* below means beyond every draw **taken**, not beyond every
relabelling possible; the two are not the same claim and this section used to
read as though they were. Nor is the 2,000 checkable from the tree:
`NULL_DRAWS` is **4,000** both at this study's `seam.cjs` blob (`a6789197…`,
[§7.1](#71-the-seam-study-rf2-cvvb7)) and at HEAD, and no landed caller passes
2,000. The finite null is small enough to enumerate exactly — but that is a
re-run rather than a prose repair, and these nineteen runs cannot be re-analysed
at all, because their datasets do not survive.

Every observed seam sat at or below that null's median, and **not one of the
nineteen runs reached p < 0.2**. The published 22% is a 1-in-400 draw from it.
The published 34% is beyond every one of those 38,000 draws — which is the
honest shape of the finding: it is not noise of this kind either, and nothing
this study could produce reproduced it.

Where the floor's variation actually lives is the **round**, not the segment.
The rotation makes segment, round and position-in-round mutually orthogonal
whenever the number of rounds is a multiple of the number of segments, and the
instrument checks that balance rather than assuming it.

~~**The perturbation is multiplicative, so floor-normalisation cancels it, and
the arithmetic is exact**: `(k·H)/(k·F) = H/F`, to the last bit, for any `k`.~~
**WITHDRAWN by the re-take ([the band re-calibrated](the-band-re-calibrated.md#6-multiplicativity-is-withdrawn-and-the-correlation-that-carried-it-was-never-diagnostic)).**
The table below is left standing because it is what was measured; what it does
not support is the conclusion drawn from it. The measurement offered as saying
the perturbation is multiplicative rather than additive is
`ctl-2x / floor` — two arms in the *same* block whose true ratio is a property
of the page and not of the box:

| across the ladder's 80% floor span, all on `taskNet` | |
|---|---:|
| `ctl-2x / floor` moved | 6.5% (1.657 → 1.765) |
| correlation of that ratio with the floor | **+0.41** — an additive `c` reads `(2W+c)/(W+c)`, which *falls* as `c` grows, so the sign is wrong for additivity |
| correlation of the published bar row with the floor | **−0.10** |
| correlation of the published bar row with the seam | **−0.18** |
| ~~`hicasso / reagent-subs` over all 19 runs~~ | ~~1.0161, [0.9643 – 1.1041]~~ — frame-only; on the corrected clock `bulk300` reads **1.1494×** (`rf2-emvod`) |

So a bar row does not move when the floor moves by 80%, and does not move with
the seam. **The bar row was not quoting the seam.** That much stands.

**The multiplicativity conclusion does not, and the re-take says why
(`rf2-ymi6j`).** Two things are wrong with the reasoning above, and neither
needed a second clock to see.

*The correlation's sign carries no information.* `(2W + c)/(W + c)` falls as `c`
grows **with `W` held fixed** — but load does not vary `c`, it varies `W`, and
under a fixed `c` and an inflating `W` the same expression *rises* toward 2.00.
A positive correlation is equally the additive signature. The measurements
settle it without any modelling: the re-take reads this correlation at **−0.04**
on one nineteen-run ensemble and **+0.88** on another taken twenty-five minutes
earlier on the same machine.

*And the level refutes the conclusion outright.* A perturbation that cancels
exactly predicts `ctl-2x / floor` = **2.00, at every rung, with no variance**.
Nineteen fresh runs read **1.7138 [1.6228 – 1.8417]**. Floor-normalisation does
not cancel exactly; the residue behaves like a per-sample cost that does not
scale with the page, measured at `c` = 0.91–1.74 ms by rung and independently at
0.79–1.04 ms (`rf2-emvod`) and 1.0397 ms (`rf2-7iqb5`). **`(k·H)/(k·F) = H/F`
is not free**, which is why `rf2-7iqb5` built a control that differences the
residue away rather than assuming it cancels — and why the **band**, which
measures what fails to cancel, is the statistic these rows are adjudicated
against.

**Reconciled with the window audit**, which landed alongside this and reads the
seam at **31.9%** on its own runs
([the clock behind the published rows](the-clock-behind-the-published-rows.md)).
That figure is a **single round's** cross-segment spread, and single-round
spreads of 30–45% are ordinary here — this ladder saw 45% in one round of an
*idle* run whose pooled seam was 3.8%. So the audit corroborates the
*observation* and not the *load attribution*; the pooled statistic is the one
§6 quoted and the one this ladder moved a box under without shifting.

~~The two pages also reach cancellation by different routes, and conflating them
would overstate both. The audit's `(U/F) ÷ (R/F) = U/R` is **algebra**, and it
is exact only where both legs are divided by the *same* floor — the converged
witness, whose arms share one segment. Here the three segments have three
different floors, so nothing cancels algebraically; what makes it cancel is the
measured fact that the perturbation is multiplicative. Same conclusion, and only
one of the two is free.~~

**WITHDRAWN — the contrast was drawn wrongly on both sides (2026-08-07,
`rf2-es04f`).** The audit has since withdrawn the identity itself.
`(U/F) ÷ (R/F) = U/R` holds only where the two `F`s are the *same reading*;
where each arm is normalised against the floor measured in its own segment the
identity is `(U/F_U) ÷ (R/F_R) = (U/R) × (F_R/F_U)`, and the floor is not the arm
that cancels but the arm that contributes a second term
([the clock behind the published rows §2](the-clock-behind-the-published-rows.md#2-why-the-floor-normalisation-does-not-protect-the-ratio)).

So the exception carved out above is empty. **The converged witness's published
red zone crosses two segments**, and
[its own page](p0-converged-witness-set.md#two-estimators-published-together)
says so outright — *"every threshold above divides one floor-normalised ratio by
another, so the two segments' floors enter it"* — which is why it publishes a
floor-free estimator beside every threshold and reports the two together. What
*does* cancel there is its
[reactive leg](p0-converged-witness-set.md#the-reactive-leg-from-a-second-author-rf2-2rtt621),
formed inside one segment of one round from two Reagent arms against a single
floor reading. One reading, so the algebra is exact — that is the shape the
sentence above was reaching for, and it is not the shape of a cross-segment row,
here or there.

And the second route is gone with it: cancellation *"by the measured fact that
the perturbation is multiplicative"* is precisely what the re-take withdrew a few
paragraphs up. **Nothing on this page cancels the seam.** What survives is
weaker: the seam does not track load and does not belong to the segment, so it is
*controlled for* rather than divided out, and
[§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)'s band — which
measures what fails to cancel — is what a magnitude is adjudicated against.

### 6.2 What replaces it: the band a magnitude must clear

The seam was the wrong statistic to gate on. What bounds a bar row is not how
far apart two segments' floors are; it is **how much of a block's perturbation
fails to cancel when that block's own floor is divided out** — and `ctl-2x /
floor` measures exactly that, because its true value is fixed. The **band** is
the half-width of the p10–p90 interval of that ratio over the run's eighteen
segment-blocks, relative to its median. An interior quantile rather than a
max/min, because a max/min over eighteen blocks is the tail-heavy statistic this
section exists to stop trusting.

It is deliberately conservative: `ctl-2x` and `floor` differ in size (1,801
against 901 elements), so a perturbation that is multiplicative but
size-dependent lands in this ratio and would not land between a substrate arm
and its own same-size floor. The band therefore over-states the noise a real bar
row carries, which is the direction a gate should err in.

It is also calibrated. Over the ladder the band averaged **8.9%** while the bar
row's own run-to-run spread was ±7% around 1.016 — it predicts the noise a bar
row actually carries.

> **SUPERSEDED — THE BAND HAS BEEN RE-CALIBRATED ON THE PUBLISHED CLOCK
> (`rf2-ymi6j`).** The `8.9%` average, the `4.4%–18.5%` range and the `25%`
> ceiling were all computed on `taskNet` and could not be recomputed, so the
> ladder was re-run: [the band re-calibrated](the-band-re-calibrated.md).
> On raw `TaskDuration`, nineteen runs at the same six rungs read
> **4.4% – 31.1%, mean 14.0%** — half again as wide as the range this ceiling
> was set above.
>
> **The ceiling is now 35%**, set from the band's own bootstrap sampling
> distribution (run-level q99 **29.1%**, `P(fire)` **0.2%**) rather than from
> the largest of nineteen observations. **And that is why the old one fired
> three times in two days after being calibrated never to**: `P(band > 25%)` is
> **2.6%** pooled and **9.0%** within-run, so 25% sat inside the bulk of the
> distribution. It was never above the tail; it was above nineteen draws of it.
>
> **The gate now adjudicates raw `TaskDuration` only.** `taskNet` is a
> difference of two counters and a smaller number, so its relative dispersion is
> larger by construction — 28.5% against 23.2% per-sample, a wider band on 14 of
> 19 runs — and nothing is published on it. The frame-only band is still
> computed, printed and stored; it refuses nothing.
>
> **`rf2-h8o80`'s warning is answered rather than sharpened, and in the opposite
> direction.** The band does not widen outside the calibrated load regime; it is
> **widest on an IDLE box** — 24.6% at zero competing cores against 8.3% at
> four, `corr(band, floor) = −0.49` — and both of the re-take's breaches are at
> zero load. Its evidence, an `M1` floor of 5.98–6.95 ms held against this
> ladder's 3.06–5.50 ms, compares raw `TaskDuration` with `taskNet`; the
> same-sample gap is **1.34 ms**.
>
> **No published magnitude moves.** All three firings were on rows refused on
> independent grounds, and `M1`'s bands ran 4.9–10.8%, a third of either ceiling.

**The rule, and it is a generalisation of one this programme already has.**
`validation.md` holds that *a margin under 5% is instrument-limited rather than
cleared*. That 5% is assumed. The band is the same rule with the figure
**measured by the run itself**: a magnitude whose distance from 1.0 is inside
the band is instrument-limited and the row says so. A run whose band exceeds
~~**25%**~~ **35%** ([`rf2-ymi6j`](the-band-re-calibrated.md#51-the-recalibrated-band))
has no reportable magnitude at all. The 25% was a tripwire that did *not* fire
anywhere on the ladder that calibrated it (bands 4.4%–18.5%, all on `taskNet`)
and then **fired three times in two days**, because being above nineteen draws
of a tail is not being above the tail: the re-take measures `P(band > 25%)` at
2.6–9.0% per run. The observation the old paragraph reached for — *the widest
band of the nineteen was taken at zero load* — turns out to be the finding
rather than the caveat, and it replicates: the band is **widest on an idle
box**.

**The new rule reproduces both verdicts this page already reached**, from one
measured quantity instead of three assorted grounds — which is the check on it,
since a gate that changed the answers would need arguing for rather than
adopting.

Run 5 predates the band and its raw readings were not kept, so the band cannot
be recomputed for it. Only `M1` publishes both ends of its control's range and
so admits a conservative max/min stand-in — `(2.1817 − 1.7258) / (2 × 1.9534)`
= 11.7%, which the shipped p10–p90 definition would put *below*. The bulk rows
publish only their worst round, which is not enough to reconstruct a band; what
settles them is that their margins are smaller than **any** band this
instrument has ever produced, the tightest of twenty runs being 4.4%.

Both tables below are frame-only throughout — margin, band and bar row alike.
The **adjudications** are the durable part and they do not turn on the clock,
because a margin and the band it is compared against were computed on the same
readings; the **magnitudes** are superseded row by row on
[the corrected clock's page](rows-re-adjudicated-on-the-corrected-clock.md#4-the-rows).

| run 5 row *(all figures `taskNet`)* | margin from 1.0 | against | verdict |
|---|---:|---|---|
| `M1`, `hicasso / reagent-subs` ~~1.2107~~ | 21.1% | its own band, ≤ 11.7% | **clears the band** — ~~published, as it was; the magnitude is now **1.4896×**~~ ~~**and still publishes no magnitude** *(2026-08-06, `rf2-jcm3p`)*. The corrected clock read **1.4896×** [1.3488 – 1.5989] on this row, under a failing `ctl-2x`; clearing the band is not clearing the control, and [§4](#4-the-mount-row--a-regime-not-a-magnitude) states the row as a regime~~ **and publishes a magnitude again, conditionally labelled** *(2026-08-07, `rf2-t2flm` — [§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row))*. The corrected clock read **1.4896×** [1.3488 – 1.5989] on this row, which stays a dated observation; the published figure is drawn from the two retained ensembles and stated at §4.3 |
| `bulk300` ~~1.0100~~ | 1.0% | smaller than every band ever measured here | instrument-limited — refused, as it was; **1.1494×** corrected |
| `bulk100` ~~0.9902~~ | 1.0% | the same | instrument-limited — refused, as it was; **1.1089×** corrected, a sign change |
| `narrow` 1.0369 | 3.7% | the same | instrument-limited — refused, as it was; **1.0236×** corrected |

And on the first run taken with the band instrument itself in place — five rows,
idle box, same design, still `taskNet` — every row now carries its regime:

| row | seam | band | `hicasso / reagent-subs` *(frame-only)* | disposition |
|---|---:|---:|---:|---|
| `M1` | 6.7% | 9.4% | 1.0952 | margin 9.5% **clears**, barely |
| `bulk300` | 3.3% | 7.7% | 1.0084 | margin 0.8% — instrument-limited |
| `bulk100` | 3.9% | 7.7% | 1.0299 | margin 3.0% — instrument-limited |
| `narrow` | 6.2% | 10.6% | 1.0358 | margin 3.6% — instrument-limited |
| `keystroke` | 0.7% | — | 0.9284 | **unadjudicated** — no proportional control; and this is the one row whose door was never the corrupted one |

`keystroke` has no band and is marked rather than passed. Its control burns a
fixed 50 ms instead of doubling the page, so `control / floor` reads `(F+50)/F`
and moves with `F`; it is not a pair whose true ratio is a property of the page,
and a row with no gate must not report as though it had cleared one. That is a
result about the keystroke row's control, and it is [§6.3](#63-what-a-future-run-would-have-to-change)'s
first bullet arriving from the other direction.

### 6.3 What a future run would have to change

Filed rather than attempted here, because each is a different instrument:

- **A control whose arithmetic suits an update.** `ctl-2x` doubles the *page*.
  On a mount row the work doubles with it and the control passes; on an update
  row it does not — layout scaled 1.79× where the element count scaled 2.00×, so
  even a perfect instrument reads below 2.00 and the control is mis-specified
  for the row rather than the clock being wrong. A control that doubles the
  *changed set* at fixed page size is the right one.

    **NECESSARY AND NOT SUFFICIENT (`rf2-emvod`).** The mount row undershoots
    too — `ctl-2x` reads **1.8173×** on `M1` over seven runs, where this bullet
    predicts it should be clean. A single additive per-sample cost the tare does
    not remove fits all four rows at once: inverting `(2W + c)/(W + c)` gives
    `c` = **1.040 / 1.043 / 0.873 / 0.790 ms** on `M1` / `bulk300` / `bulk100` /
    `narrow`, on floors spanning 3.58–5.70 ms and work spanning a 901-element
    cold mount to a one-cell write. Doubling the changed set removes the
    update-row confound and leaves `c` exactly where it is, because
    `(2D + c)/(D + c)` has the same shape. What removes `c` is a **three-point**
    control — dirty ε, `D`, `2D`, adjudicated as
    `(T(2D) − T(ε)) / (T(D) − T(ε))`. Carried by `rf2-7iqb5`.
- ~~**The segment seam, measured rather than assumed to cancel.**~~ **Done —
  [§6.1](#61-the-seam-measured-against-load), `rf2-cvvb7`.** A nineteen-run load
  ladder and a **Monte Carlo** within-round relabelling null answered it without
  needing a second floor arm: the variation is on the **round**, ~~the
  perturbation is multiplicative and cancels exactly,~~ and the seam is not
  attributable to the segment. *(2026-08-06, `rf2-cvvb7`: the multiplicativity
  clause is **withdrawn** — [§6.1](#61-the-seam-measured-against-load)'s banner
  records the re-take that refuted it. The null was never an enumeration
  either.)* The band in [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)
  replaces it as the gate.
- **More rounds.** The strict rule bites on round-level variance, and 6 × 10 is
  what the mount row needed rather than what these rows need.

---

## 7. Provenance

Every **magnitude** on this page comes from **one run** — run 5 — of one
instrument. The four runs before it are tabulated in
[§3.3](#33-the-published-run-and-the-four-before-it) as the instrument's
development record and are never averaged with it.

**[§6.1](#61-the-seam-measured-against-load) and [§6.2](#62-what-replaces-it-the-band-a-magnitude-must-clear)
are a separate study on separate runs and are kept separate deliberately.** They
are twenty runs taken for `rf2-cvvb7` on the same box, at the blobs in the
second table below, and they contribute **no magnitude** to any row above — they
are about the instrument, not about the candidate. The one figure they publish
about an arm, `hicasso / reagent-subs` over nineteen `bulk300` runs, is there to
show that the bar row does not move with the floor or with the seam, and it is
not offered as a `bulk300` result: `bulk300` is refused.

| | |
|---|---|
| Landed whole-tree anchor | **`a878d71ab9`** — on main, so it resolves. Recovered from the authoring anchor by identical `git patch-id --stable`; every blob that commit contributed is unchanged there |
| Authoring anchor | `fdff3fd48855e86b34ec88b5ebc07f62903a6c0a` on `worker/clock-0qj9w` — the tree the run executed on. The branch was rebase-merged, so this SHA is on no branch and **will not resolve in a fresh clone**; the landed anchor above is the one to check out, and the blob table below is what pins the instrument |
| Runtime | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), Playwright 1.59.1, React 19.2.0, node v24.13.0, `hardware-concurrency` 24, `device-memory` 32 |
| Build | `:hicasso-bench`, `:advanced`, `goog.DEBUG false`, via `--config-merge` only — no build id added, `implementation/shadow-cljs.edn` untouched |
| Reproduction | `cd implementation && npm ci && node freehand/test/re_frame/bench/hicasso/clock_run.cjs` |
| Exit code | **1** on every run. Exit 1 from a control is scoped to the row that failed it, and the driver names both sets: `REPORTABLE: M1, keystroke`. Every whole-run gate — page errors, the arm-order guard, canonical DOM, unverified writes, teardown — cleared on every row of every run |

The instrument, by blob rather than by SHA, because a SHA does not survive a
rebase:

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs` | `22b53abe9e2fcf172dbb752ed0c2d56c4ec6869c` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_app.cljs` | `15c4d3b1dd770c7cea3f2efa7aca4a343c55d34a` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_views.cljs` | `7e48dbc0b3a974cd61a5c61e606333848877a31f` |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
git rev-parse <candidate>:$P    # must print 22b53abe9e2fcf172dbb752ed0c2d56c4ec6869c
```

Runs 4 and 5 were taken at exactly these blobs. Run 3 was taken one commit
earlier, whose `clock_app.cljs` and `clock_views.cljs` blobs are the ones above
and whose `clock_run.cjs` differs by a single hunk in the driver's final failure
message — no measurement, no adjudication, no arm — which is what makes runs 3
and 5 comparable, and `git diff` between the two commits is the check. Runs 1
and 2 were taken on the two commits before that and are the *different*
instruments §3.1 describes.

### 7.1 The seam study (`rf2-cvvb7`)

Twenty runs, on the same box and the same Chromium, taken **after** the rows
above and contributing no magnitude to them. The page half was untouched, which
is what makes the seam study's floor comparable with run 5's: `clock_app.cljs`
and `clock_views.cljs` are at the blobs in the table above, unchanged.

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/seam.cjs` | `a6789197e1bd9744879a2c8a143e48dc643b7f26` |
| `implementation/freehand/test/re_frame/bench/hicasso/seam_ladder.cjs` | `ae0ddf6e1df15c8d5ad2e90a35154258762a553a` |
| `implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs` | `f5bb751d6692b894cfc361ff1c54bfd782cf95d0` |

| | |
|---|---|
| Design | 6 rounds × (4 warm-up + 10 samples) per arm per segment, unchanged from run 5 |
| Ladder | 19 runs of `bulk300` at 0 / 2 / 4 / 8 / 12 / 20 competing busy cores, 3–4 replicates a rung, plus one five-row run at zero load for the row-position control |
| Band run | 1 five-row run at zero load, the first taken with the band instrument in place |
| Load windows | two, 23:10–23:17 and 23:18–23:23 AUSEST 2026-08-01, each rung a single ~40 s run with the load released between runs |
| Reproduction | `node freehand/test/re_frame/bench/hicasso/seam_ladder.cjs --load 12 --label x --json out/x.json`, and `node freehand/test/re_frame/bench/hicasso/seam.cjs` for the adjudicator's own self-test |
| Discarded | none. Every run that started finished; the driver writes no dataset for a run that died part-way, so a partial one cannot be analysed by mistake |
| Producing commit | **`362b883285726adf1458e43be4f897d770545a33`** — on `main`, so it resolves. `seam.cjs` and `seam_ladder.cjs` are at the blobs above there; `clock_run.cjs` is not, since at that commit `clock_run.cjs` is `ede7bf73…` and the driver had moved on before the page landed |
| Driver blob, unlanded | the blob the runs executed, `f5bb751d…`, is on **no** commit reachable from `main` — thirty commits touch `clock_run.cjs` there and not one carries it. It is annotated rather than re-pinned, because recovering a landed SHA restores the patch and not the tree |
| Analysis inputs | **none survive** — the nineteen runs' datasets went to a gitignored `out/`, as the paragraphs below record. `NULL_DRAWS` is `4,000` at the adjudicator blob above, against the 2,000 a run that [§6.1](#61-the-seam-measured-against-load)'s null table states, and no landed caller settles which was used |

The 19 ladder runs were taken with the seam adjudicator **not yet wired in**, so
nothing they measured was influenced by the gate they calibrate; the ladder
figures in [§6.1](#61-the-seam-measured-against-load) ~~are recomputed from
their stored raw readings using the shipped `seam.cjs`, so the published figures
and the instrument's figures are the same figures~~ **were computed from raw
readings that were then thrown away** *(2026-08-06, `rf2-cvvb7`)*. They did come
out of the shipped `seam.cjs`, so the published figures and the instrument's
figures were the same figures *when they were taken* — but nothing in the landed
tree recomputes them now, as the paragraph below and
[§6.1](#61-the-seam-measured-against-load)'s banner both record. The durable
answer was a **re-run**, not a restatement.

**The clock and the door for the whole study (`rf2-ymi6j`).** `seam_ladder.cjs`
does not measure anything itself: it forks its spinners, spawns
`clock_run.cjs --no-build` with `HCLOCK_ONLY` set from `--only` — whose default
is **`bulk300`** — and kills the spinners when the child exits. `clock_run.cjs`
drives `bulk300` through `page.evaluate`, so the whole ladder is on `taskNet`,
the frame-only clock.

**And the study is not restatable from its own artefacts, for two independent
reasons.** At the driver blob above, `roundsTask` did not exist in the file at
all and `HCLOCK_JSON` wrote only `rounds` — the `taskNet` per-sample readings —
so the quantity a restatement needs was never recorded. Separately, no dataset
from the nineteen runs survives: they were written to a gitignored `out/` and
nothing was kept. **A corrected-clock ladder was therefore a re-run, not a
recomputation** — and it has been run: nineteen runs across the same six rungs,
both clocks, [the band re-calibrated](the-band-re-calibrated.md). Neither
failure mode can recur: `ladder_band.cjs` recomputes every figure from the
driver's own datasets using `seam.cjs`'s exported adjudicators, and the compact
per-block dataset it emits is **committed** at
`implementation/freehand/test/re_frame/bench/hicasso/data/ladder-ymi6j.json`.
That is the one command this section's own audit asked for, and it runs from a
clean clone against a file the clone already has:

```bash
cd implementation
node freehand/test/re_frame/bench/hicasso/ladder_band.cjs \
  --from freehand/test/re_frame/bench/hicasso/data/ladder-ymi6j.json
```

It regenerates every published aggregate of the re-taken ladder — every band,
every `ctl-2x / floor`, every bar row — and `--selftest` fails closed on the
resampling model. What it cannot do is restore **these** nineteen runs, whose
inputs are gone; the durable path starts at the re-run and not before it.

---

## 8. What this hands the programme

- **The candidate has clock rows.** It did not before.
- **On mount it is materially slower than Reagent-on-subs, and the row publishes
  a magnitude** *(2026-08-07, `rf2-t2flm`;
  [§4.3](rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row))* —
  ~~a REGIME, and not a magnitude *(2026-08-06, `rf2-jcm3p`;
  [§4](#4-the-mount-row--a-regime-not-a-magnitude))*~~. ~~On mount it is 1.4896×
  Reagent-on-subs on the clock of record [1.3488 – 1.5989], against a `≤ 1.0×`
  win condition and a `1.0150×` red zone.~~ That reading stands as a historical
  observation: it sits above the `≤ 1.0×` win condition and the `1.0150×` red
  zone, as does every other corroborated reading. What the row now publishes,
  with the conditional labels it must carry, is stated at §4.3 and not here.
  This page's own `1.21×` is the frame-only reading of the same row, above
  parity in all five of its runs; the correction moved the figure *up*. Still
  not a clear K1 kill — K1 asks for `> Reagent` *after two serious runtime
  iterations* — but no longer a range that straddles parity, and the direction
  has never once come back the other way.
- **The deficit's location is measured, not inferred.** The candidate pays
  +1.06 ms over its own floor where the identical spine under the identical
  adapter pays +0.38 ms. It is the runtime hiccup codec walking 901 elements.
  That is a fixable thing, and it is the thing to fix.
- **On per-keystroke every arm is one frame**, and Event Timing cannot separate
  them. A pass on the user-facing axis, for the candidate and both donors alike.
- **The bulk rows remain unmeasured** — the same place they were before this
  page, but now with a named instrument, a named failure mode and three named
  repairs, and refused on a **measured** quantity rather than an assumed one.
- **Every row now says which regime produced it.** The seam is published with
  the null of its own statistic and an orthogonal decomposition; the band — the
  part of a block's perturbation that survives floor-normalisation — is measured
  on every row and a magnitude inside it is marked instrument-limited. That
  turns `validation.md`'s assumed *"a margin under 5% is instrument-limited"*
  into a figure each run measures for itself.
- **One of this page's own refusal grounds was wrong, and a load ladder found
  it.** The seam does not track load and is not attributable to the segment —
  ~~and is multiplicative, so it cancels, exactly~~ *(2026-08-07, `rf2-s6qg4`:
  that third clause is **withdrawn**; see
  [§6.1](#61-the-seam-measured-against-load). The seam is controlled for, not
  cancelled.)*. The ground is withdrawn and the
  measurement that withdrew it is published beside it. The general lesson is
  cheap and reusable: a max-over-min of three noisy block medians has a long
  right tail, and quoting one without its null invites a reader to treat 6% as a
  finding.
- **An in-page `performance.now()` window holds only part of a substrate arm's
  operation, and a different part per arm — the two windows split 300–610%.**
  That is the strongest methodological finding here. It does not overturn a
  published row, and it does mean no published clock row had ever been checked on
  an instrument that sees past `flushSync`. ~~It raises `rf2-rguy1`.~~ **It did,
  and the answer arrived from three directions**: `rf2-8nqsl` audited the record,
  [`rf2-rguy1`](cross-checked-against-an-outside-instrument.md) cross-checked it
  against a driver nobody here wrote, and `rf2-yd52q` then found **this page's
  own second window was frame-only** — so the finding above understates the
  problem. Neither of the two clocks compared here held a whole operation.
- **It lowers `rf2-aqgr2`.** Decomposing the 415 B/read gap is optimising a
  proxy, and the clock row that would have justified it did not arrive: the
  candidate's mount deficit is a codec walking elements, and no per-read byte
  count explains it.
