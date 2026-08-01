# The candidate's clock — mount, bulk K=100/300, narrow, per-keystroke

**The candidate is slower on the clock.** On the mount row — the only row of
five whose positive control passed — Hicasso Arm 1 mounts the 300-boundary
witness at **1.21× Reagent-on-subs** [0.9756 – 1.7208], above the ship bar's
`≤ 1.0×` and above
[the restated M1 red zone](../validation.md#the-clock-gates-restated-on-the-post-25-tree)
of `1.0150×`. **Five runs of this instrument put it at 1.01, 1.11, 1.10, 1.11
and 1.21 — every one above parity, none below.** The range straddles 1.0 at
n = 6, so a deficit is not *established*; it is equally true that a pass is not,
and a candidate that needs its range's lower edge to reach a bar has not reached
it.

On per-keystroke it is **indistinguishable from both donors**, and all three are
one frame. The three bulk rows **could not be measured honestly**; this page
publishes no magnitude for them and [§6](#6-the-three-rows-this-page-refuses)
says exactly why.

That is the first paragraph because the result is negative, and a negative here
is decisive information about the six-week clock rather than a failure to
report.

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
ratio of two floor-normalised ratios with the seam cancelled. That is a weaker
interleaving than one segment's, and this page says so rather than describing it
as though it were sample-level. The seam is measured and published on every row.

- **Witness**: the `M1` page — 300 sub-reading boundaries, 901 elements, one
  `[:p0/cell i]` read per boundary. Identical markup on all three substrates,
  and the canonical-DOM gate (attribute names sorted) proves it rather than this
  sentence: **6 non-control arms across 3 segments, byte-identical, 27,224
  bytes.** The keystroke witness is a controlled field over 100 boundaries,
  9,117 bytes, identical across all six.
- **B/E/Q stamp**: `B = 300` boundaries, `E = 300` boundary-query edges (one
  read each), `Q = 300` unique live query keys — so **Q = E**, fan-out 1, the
  mandatory distinct-query regime. The keystroke witness is `B = 101`,
  `E = 101`, `Q = 101`. Cache cardinality is part of the witness by ruling, and
  a clock row stamps it for the same reason a heap row does: two arms at
  different cardinalities are not the same experiment.
- **Schedule**: 6 rounds × (4 warm-up + 10 samples) per arm per segment, arms in
  the shared guard's reflecting order, segment order rotating with the round.
- **Verification**: every mount is read back against the arm's own element
  arithmetic; every write is read back at both ends of the range it claims to
  have changed. **0 unverified of 1,008** on every row (756 on the keystroke
  row).
- **Residue**: zero on both censuses after every row — the lane's
  (`body-children`, `sub-entries`, `sub-ref-count`) and the candidate runtime's
  own (`cells`, `cell-refs`, `boundaries`, `edges`, `entries`).

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
the final tree, and it has the tightest segment seams of any (3.8–8.4% against
22–34% on runs 3 and 4). All five are tabulated because selecting a run after
seeing its result is the fault this whole page is built to avoid, and the
selection here is on a stated pre-condition — a quiet box — with objective
evidence for it.

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

---

## 4. The mount row — the one row published as a magnitude

**Positive control: PASS.** `ctl-2x` measured 1.9534× [1.7258 – 2.1817] over 18
segment-rounds against 2.00× ±25%, every round inside the band. **Arm-order
guard: reportable.** **Canonical DOM: identical.** **0 unverified of 1,008.**
**Segment seam: 4.9%** — the same floor read 3.461 / 3.627 / 3.631 ms across the
three segments. **Granularity: 0.092 ms** smallest non-zero delta over 974
distinct values.

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
| **`hicasso / reagent-subs`** | **1.2107×** | [0.9756 – 1.7208] | above the `≤ 1.0×` win condition and above the `1.0150×` red zone; **range straddles 1.0** |
| **`hicasso / uix-subs`** | **1.1865×** | [0.9753 – 1.3722] | **range straddles 1.0** |

**What that means against the gates.** The P1 win condition is *mount ≤ 1.0×
Reagent-on-subs, same run and same instrument*, and the restated M1 red zone is
`1.0150×` because under Ruling 1 the red zone **is** the measured UIx ratio. The
candidate's point estimate is above both, in this run and in the four before it.
Its range straddles 1.0, so at n = 6 the deficit is not *established* — and
validation.md's own rule that *a margin under 5% is instrument-limited rather
than cleared* cuts the other way here: a 21% margin is well outside the
instrument-limited band, and a 10–21% margin repeated across five runs is not
noise about zero.

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
runtime and on a frame-inclusive clock.

### 4.1 The other instrument, on the same samples

Both instruments read the same operations in the same samples, so the gap
between them measures the error the in-page window makes:

| arm | in-page ×floor | frame-inclusive ×floor | in-page reads |
|---|---:|---:|---|
| `reagent-subs` (M1) | 5.6068 | 1.1321 | **+395%** |
| `uix-subs` (M1) | 4.7394 | 1.1530 | **+311%** |
| `hicasso` (M1) | 8.7318 | 1.3613 | **+541%** |
| `reagent-subs` (bulk300) | 5.8048 | 1.0243 | **+467%** |
| `uix-subs` (bulk300) | 4.3333 | 1.0242 | **+323%** |
| `hicasso` (bulk300) | 7.2986 | 1.0266 | **+611%** |
| `ctl-2x`, all segments and rows | 1.70 – 1.96 | 1.69 – 1.97 | −9.5% to +13% |

On the substrate arms the in-page window is wrong by a factor of three to nine
and — the part that matters — **wrong by a different factor per arm**. It is not
a scale error that cancels in a ratio: on M1 it would put `hicasso / reagent` at
1.56× where the frame-inclusive clock reads 1.21×.

The reason is visible in the decomposition. A substrate arm's mount spends
roughly a third of its frame-inclusive cost inside `flushSync` and the rest
afterwards in style, layout and paint — and the *afterwards* half is where the
three substrates differ least, because they build byte-identical DOM. Measuring
only the first half therefore exaggerates the difference between them. **The
control arms, which are pure React, differ by only 6–13%** — which is exactly
how a lane that checks only its controls in-page would never notice.

This does not overturn the published donor rows: those are Reagent against UIx,
taken on a different harness, on a page whose two readings are closer. What it
says is that no published clock row in this programme has ever been checked
against a frame-inclusive instrument, and that the check is now cheap. It is
filed, and it raises `rf2-rguy1`.

---

## 5. Per-keystroke — every arm is one frame

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
experiences, for all three, and not a finding *about* Hicasso.

The finer instrument does separate them, and reports differences far below the
frame budget:

| arm | task ms/keystroke | ×floor (tared) | range |
|---|---:|---:|---|
| `reagent-subs` | 2.310 | 1.2605 | [1.0038 – 1.9780] |
| `uix-subs` | 2.044 | 1.0885 | [0.9626 – 1.1547] |
| **`hicasso`** | **2.199** | **1.1405** | [0.9431 – 1.3397] |
| `floor` (per segment) | 2.08 – 2.19 | 1.0 | — |
| `ctl-50ms` (control) | 51.9 – 52.1 | 34.4 – 37.3 | — |

`hicasso / reagent-subs` = **0.9678×** [0.5318 – 1.3259] and
`hicasso / uix-subs` = **1.0500×** [0.9119 – 1.2033]; both straddle 1.0.

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
3. **On runs 3 and 4 the cross-segment floor seam exceeded the effect.** The
   *same* floor — identical work, no substrate — read 3.147 / 2.437 / 2.349 ms
   across the three segments on run 3's `bulk300`: a **34% spread**, against 23%
   and 22% on the other two bulk rows. Floor-normalisation cancels a
   *multiplicative* seam, not an additive one. **This reason does not apply to
   run 5**, whose seams are 3.8–8.4%, and it is kept because a refusal that
   quietly drops one of its grounds when a later run looks better is not a
   refusal.

**One qualitative result from these rows is robust and needs no control**,
because it does not depend on a magnitude. On the `narrow` row all three
substrate arms localise, by very nearly the same amount. Against a floor that
re-renders its whole tree, `reagent-subs` reads 0.2896× floor, `uix-subs`
0.3046× and `hicasso` 0.2952×, and layout falls from 1.35 ms (floor) to ~0.14 ms
on every substrate arm. **The candidate localises a one-cell write, and it is
neither better nor worse at it than the donors are.** The `narrow`-as-a-law win
condition — commit-side dirty-set flat in `B` across 300/600/1,200/2,400 — is a
different experiment and this run does not attempt it.

### What a future run would have to change

Filed rather than attempted here, because each is a different instrument:

- **A control whose arithmetic suits an update.** `ctl-2x` doubles the *page*.
  On a mount row the work doubles with it and the control passes; on an update
  row it does not — layout scaled 1.79× where the element count scaled 2.00×, so
  even a perfect instrument reads below 2.00 and the control is mis-specified
  for the row rather than the clock being wrong. A control that doubles the
  *changed set* at fixed page size is the right one.
- **The segment seam, measured rather than assumed to cancel.** It was 34% on
  one run and 3.8% on another with nothing changed but how busy the machine was.
  A run that alternates segments *within* a round, or prices the seam with a
  second floor at the segment's other end, would say whether it is an order
  effect, GC, or the adapter install itself.
- **More rounds.** The strict rule bites on round-level variance, and 6 × 10 is
  what the mount row needed rather than what these rows need.

---

## 7. Provenance

Every row on this page comes from **one run** — run 5 — of one instrument. The
four runs before it are tabulated in [§3.3](#33-the-published-run-and-the-four-before-it)
as the instrument's development record and are never averaged with it.

| | |
|---|---|
| Landed whole-tree anchor | *(filled on merge — a rebase-merge mints a new landed SHA, which is why the blob table below is the real pin)* |
| Authoring anchor | `fdff3fd48855e86b34ec88b5ebc07f62903a6c0a` on `worker/clock-0qj9w` |
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

---

## 8. What this hands the programme

- **The candidate has clock rows.** It did not before.
- **On mount it is 1.21× Reagent-on-subs** on the published run and above parity
  in all five, against a `≤ 1.0×` win condition and a `1.0150×` red zone. Not a
  clear K1 kill — K1 asks for `> Reagent` *after two serious runtime
  iterations*, and the range still straddles 1.0 — but not a pass, and the
  direction has never once come back the other way.
- **The deficit's location is measured, not inferred.** The candidate pays
  +1.06 ms over its own floor where the identical spine under the identical
  adapter pays +0.38 ms. It is the runtime hiccup codec walking 901 elements.
  That is a fixable thing, and it is the thing to fix.
- **On per-keystroke every arm is one frame**, and Event Timing cannot separate
  them. A pass on the user-facing axis, for the candidate and both donors alike.
- **The bulk rows remain unmeasured** — the same place they were before this
  page, but now with a named instrument, a named failure mode and three named
  repairs.
- **An in-page `performance.now()` window mis-reads a substrate arm by 300–610%,
  and by a different factor per arm.** That is the strongest methodological
  finding here. It does not overturn a published row, and it does mean no
  published clock row has ever been checked on a frame-inclusive instrument. It
  raises `rf2-rguy1`: an external instrument built on Chrome's timeline is the
  honest cross-check on whether *our* harness is telling the truth, and this page
  is evidence that the question is not academic.
- **It lowers `rf2-aqgr2`.** Decomposing the 415 B/read gap is optimising a
  proxy, and the clock row that would have justified it did not arrive: the
  candidate's mount deficit is a codec walking elements, and no per-read byte
  count explains it.
