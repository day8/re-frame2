# What does a WRITE cost in bytes, on Freehand and on Reagent?

Seat: EVIDENCE SPIKE. Bead `rf2-lcsjg`.

Measured 2026-07-27 against `main` at `7c666f3996`. Reagent **2.0.1**,
React **19.2.0**, headless Chromium 147 via Playwright, `:advanced` with
`goog.DEBUG false` — the artefact a consumer ships. Windows 11
workstation with other agents running concurrently.

**Only the interpreted tier is measured.** The compiled tier is ruled out
and no compiled arm appears here.

[`freehand-vs-reagent-memory.md`](freehand-vs-reagent-memory.md) settled
retained heap and Freehand lost it: **2,430 bytes per sub-free boundary
against Reagent's 410 and UIx's 251**, ranges disjoint, and widest on the
witness with no reactivity at all — so it is the ViewCell wrapper itself.
That page closes by naming the one memory question it could not answer:

> Nothing here measures heap *under* update, and a substrate can trade
> standing bytes for churn — Freehand's fine-grained path might allocate
> less per write than Reagent's, and this instrument cannot see it. That
> is the obvious next measurement, and it is now the only memory question
> with an argument left in it for Freehand.

This page runs that measurement.

---

## The answer, first

**Freehand does not win here either.** On every arm and both write
shapes it allocates more than Reagent, with disjoint ranges. The
hypothesis that a fine-grained substrate trades standing bytes for lower
churn is **falsified on this witness**.

But the two write shapes say very different things, and the second is
the one worth reading twice.

1. **The broad write — 300 cells change.** Freehand allocates **4,572,680
   bytes** against Reagent's **269,229** and a bare React floor's
   **144,039**: **17.0×** Reagent overall, **13.5×** on the view leg
   alone. Ranges disjoint. **But that view-leg figure is an UPPER BOUND
   on the substrate difference, not the substrate difference** — see
   [§3](#3-the-figure-that-is-an-upper-bound-not-a-result).

2. **The narrow write — one cell changes — is where Freehand comes
   closest to parity of anything measured in this studio.** Strip the
   re-frame write leg and Freehand's view substrate allocates **21,376
   bytes against Reagent's 19,307** — **1.107×** [worst case 1.082],
   disjoint but within eleven percent. And both are about **seven times
   better than the top-down React floor's 139,461 B**. Fine-grained
   reactivity is doing exactly what it claims on this shape. Freehand
   simply does not do it *better* than Reagent does.

3. **The narrow write's headline 20.2× is almost entirely re-frame, not
   Freehand.** Of Freehand's 478,787 bytes for changing one cell,
   **457,181 — 95.5% — is the write leg**: `frame/replace-app-db!` and a
   subscription graph re-evaluating all 300 `:b6/cell` subscriptions to
   discover that 299 of them did not change. The view substrate's share
   is 21,376. A Reagent application built on re-frame subscriptions would
   pay that write leg too; this one resets a bare `reagent.core/atom` and
   pays 4,018.

So the honest summary is that **memory is not a rescue on either axis**,
and that the one number in this page that flatters Freehand — the narrow
view leg at 1.107× — is a near-tie rather than a win.

---

## Method, and the instrument that had to be built third

Three instruments now exist on this surface and each answers a different
question. Getting the wrong one is how two published tables went wrong.

| | instrument | sees |
|---|---|---|
| **B7** | retention: mount, collect, read | what a boundary KEEPS |
| **B7 (rejected)** | CDP *sampling* heap profiler | what SURVIVED — useless for churn |
| **B8, here** | a window with no collection in it | what a write ALLOCATES, garbage included |

**The method in one sentence.** If no garbage collection runs between two
readings of V8's used-heap counter, the difference between them is the
number of bytes allocated in between — **including every byte already
unreachable**, because nothing has reclaimed it yet. Garbage is visible
precisely because it has not been collected.

**Every adjacent pair of readings is one STEP.** The counter is sampled
at every leg boundary of every write. Rising steps accumulate to an
allocation total; falling steps are collections and accumulate
separately, so a collection is *excluded* from the total rather than
netted against it. Where nothing fell, the rising-step sum, the endpoint
difference and the driver's independent CDP bracket are three ways of
computing one number.

**Windows are sized per arm, and that mattered.** The arms differ by
three orders of magnitude in what they allocate — the empty-harness arm
438 B a write, Freehand's broad write four and a half megabytes — so one
write count cannot suit them all. A first run used 25 writes for
everything: comfortably collection-free for Reagent, and a dozen
collections for Freehand, whose windows were then all discarded by the
gate. That is a selection effect that would have produced a table with no
Freehand row in it. Each arm now gets a calibration probe and the number
of writes that fits its own allocation into one nursery — Freehand's
broad window is **2 writes**, Reagent's narrow window is **40**.

**Only collection-free windows are quoted.** Every figure in this page
comes from a window in which the counter never fell. Windows with a
collection in them are retained in the raw data and excluded from the
tables.

### The controls, and all five fired

- **The bookkeeping identity, exact.** Rising steps plus falling steps
  must equal the last reading minus the first, in every window, by
  construction. **120 of 120 mirror windows exact, residual 0 bytes.**

- **THE DECISIVE ONE — can the counter see garbage as well as it sees
  survivors?** This is the question that broke the sampler, and an
  instrument that fails it is a retention instrument wearing a different
  name. So the identical loop was run twice on the empty-harness arm at
  the same control size, with one difference: whether each control copy
  is kept or dropped.

  | | allocated per copy | retained per copy afterwards |
  |---|---:|---:|
  | copies **dropped** | 125,121 B | **11 B** |
  | copies **kept** | 125,139 B | **99,417 B** |
  | **kept ÷ dropped** | **1.0001** | |

  The counter reports the same bytes to within **0.01%** whether the
  object survives or is thrown away. **It does not care.** That is the
  claim this whole page rests on, demonstrated rather than asserted.

- **The positive control, predicted and then corrected by measurement.**
  The control allocates one `.slice()` copy of a prebuilt array of 12,500
  doubles per write and drops it. **Predicted 8 bytes a double —
  100,000 B — and that prediction was WRONG.** Priced independently by
  retention on the same object it reads **16.002 B/double (200,029 B a
  copy, identical on 20 and 40 copies, residue exactly 0)**, because
  `.slice()` of a *holey* double array does not take the packed fast path.
  A third reading — the kept-vs-dropped pass, same counter, same window —
  puts the copy at 99,417 B retained and 125,139 B allocated, ≈8 and ≈10
  B/double, so the *construction* differs between the two call sites and
  the two references are not the same object.

  > This is B7's lesson repeating one bead later. Its own control was a
  > 4.7 MB `'x'.repeat(n)` string that read as six kilobytes, and the
  > instrument was fine — the control was a fiction. Here the arithmetic
  > was again the fiction. **The finding is that a predicted size is a
  > hypothesis, not a calibration**, and the only reason it was caught is
  > that the same object was priced a second way.

- **The negative control — the tool that would have been used.** The CDP
  sampling heap profiler, pointed at a window whose garbage content is
  known and read *after* a forced collection, reports **1,204,640 B where
  the counter saw 9,253,676** — **13%**. It reports what survived. It is
  not usable for this question and it is not used.

- **Every write verified at the DOM, inside its own window.** **0
  unverified of 2,400** on each of the two rows (the empty-harness arm
  renders no cell and is excluded from the count).

- **The measured window is B6's, and that is a measured claim.** The
  instrument reads the counter where `b6-rows/timed-write!` reads the
  clock, which makes it a mirror rather than the published function. So
  the driver *also* runs the published `timed-write!` verbatim over the
  same window and brackets it with CDP. **Mirror against published:
  Freehand +0.4% broad, +0.3% narrow; Reagent −0.3% and +0.2%; the floor
  +0.3% and −0.9%.** The mirror's five counter reads a write do not
  disturb what it measures.

- **The harness reads the keys it writes, proved inside the shipped
  bundle.** See [§4](#4-an-eighth-instrument-fault-class-caught-by-a-crash-that-was-luck).

---

## 1. Bytes allocated per write

Collection-free windows only; mean with the range across windows in
brackets. The legs are `timed-write!`'s own — the state install, the
single microtask yield, and the arm's synchronous drain.

**Broad write — all 300 cells change:**

| arm | total B/write | write leg | **view leg** (gap + force) |
|---|---:|---:|---:|
| floor — top-down React, no substrate | 144,039 [143,575–144,828] | 2,390 | 141,346 [140,897–142,269] |
| **Reagent** | 269,229 [266,080–271,279] | 5,952 | **262,954** [259,880–264,811] |
| **Freehand interpreted** | **4,572,680** [4,526,429–4,607,207] | 1,017,629 | **3,554,871** [3,509,533–3,581,467] |

Freehand ÷ Reagent: **16.98×** on the total, **13.52×** on the view leg
[worst case for Freehand 13.25×]. Both disjoint.

**Narrow write — one cell changes:**

| arm | total B/write | write leg | **view leg** (gap + force) |
|---|---:|---:|---:|
| floor — top-down React re-render | 140,158 [139,349–140,621] | 405 | 139,461 [138,677–139,872] |
| **Reagent** `r/cursor` | 23,648 [23,526–23,844] | 4,018 | **19,307** [19,295–19,350] |
| **Freehand interpreted** `v/sub` | 478,787 [474,377–483,475] | 457,181 | **21,376** [20,944–21,859] |

Freehand ÷ Reagent: **20.25×** on the total, **1.107×** on the view leg
[worst case 1.082×]. Both disjoint.

**The narrow floor is not a lower bound and must not be read as one.**
B6's update floor is a plain top-down React re-render, so it repaints all
301 elements to change one — 139,461 B where Reagent spends 19,307 and
Freehand 21,376. Both fine-grained substrates are ≈7× *better* than the
floor here, which is the entire point of fine-grained reactivity and is
the one place in this studio where the mechanism visibly pays.
Subtracting this floor would produce a negative number for Reagent, which
is why the narrow row is quoted absolute.

---

## 2. Where Freehand's broad write puts its bytes

Freehand's broad write allocates 4.57 MB. It divides:

| leg | B/write | share | what it is |
|---|---:|---:|---|
| write | 1,017,629 | 22.3% | `frame/replace-app-db!` and the signal graph |
| **gap** | **3,554,823** | **77.7%** | the microtask — Freehand's notification, and React's render and commit |
| force | 48 | 0.0% | the empty `flushSync`, which finds nothing left to do |

**Read the last row.** Freehand's synchronous drain allocates
48 bytes because by the time it runs, everything has already happened in
the microtask. Reagent is the mirror image: its gap is 212 B and its
`r/flush` inside `flushSync` carries 262,742. A table that reported only
"write leg" and "force leg" — the two legs the clock report names — would
have shown Freehand allocating almost nothing for its render and been
completely wrong. The legs are not comparable arm-to-arm; only their sum
is.

This also corroborates the clock report's account of the broad update
from an instrument that shares no code with it.
[`bulk-rerender-where-the-time-goes.md`](bulk-rerender-where-the-time-goes.md)
concluded the deficit is *"allocation churn spread thin across a lot of
bookkeeping"* — a profile inference it explicitly declined to call a
memory measurement:

> **No memory claim.** The allocation reading the profile implies — that
> this is churn — is not a heap measurement and is not offered as one.

It is now a heap measurement, and the inference was right.

---

## 3. The figure that is an upper bound, not a result

**The 13.5× broad view-leg ratio overstates the substrate difference, and
this page will not quote it as a substrate result.**

The two arms do not carry the same framework. Freehand's grid reads 300
`v/sub` subscriptions through re-frame's subscription graph; the Reagent
grid reads 300 `r/cursor` derefs on a bare `reagent.core/atom`. That is
each substrate's own idiom — it is what B6 measures and why its rows are
comparable to each other — but it is *not* the same amount of machinery,
and `freehand-vs-reagent.md` §2a has used exactly this to cut an earlier
result from 15× to roughly parity.

It matters more here than it did there. `rf2-40kdm` (PR #7151) measured
the read path directly and found that **an idiomatic Reagent view body
reading `@(subscribe q)` pays 95.3–97.4% of what Freehand's port pays**
per read. Nearly all of the per-read render allocation is *shared
reactive-system cost*, not substrate cost. Freehand's arm pays it 300
times a broad write; this Reagent arm pays none of it.

So the broad view-leg gap is **an upper bound that contains an unknown
but large amount of re-frame**. Closing it needs a third arm — Reagent
mounted on re-frame subscriptions — which B6 does not have. Filed as
`rf2-mapni`.

**The narrow row is much less exposed to this**, and that is why it
carries the more trustworthy substrate reading: a narrow write re-renders
one boundary, so each arm performs one read rather than three hundred and
the shared term is small in absolute bytes. **1.107× is the closest thing
to a like-for-like view-substrate comparison in this page.**

---

## 4. An eighth instrument fault class, caught by a crash that was luck

The first draft of this harness used `#js {:h …}` literals with `(.-h o)`
accessors. Under `:advanced`, Closure renames the accessor and not the
literal key:

```
construction   return {h:[f,g,k,l,p], ok:…}
read           var l = k.Me, p = l[0]
```

`k.Me` is `undefined`, `undefined[0]` throws, and that is the only reason
it was found. **In the same build, three other keys were renamed and none
of them would have thrown.** `(.-bad a)` became `e.Mc` and
`(.-decreases a)` became `e.fc`, while their literal keys stayed `bad`
and `decreases`. `undefined + 1` is `NaN`, the literals' zeros would have
stood untouched, and the driver would have read **zero unverified writes,
always** — and for `decreases`, which is the gate that rejects a window
with a collection in it, **a constant zero: every truncated window
accepted and averaged in.**

The keys that survived did so by accident. `ok`, `control`, `write`,
`gap`, `force`, `total`, `first` and `last` all appear in Closure's
browser externs — `Response.ok`, `Touch.force`, `document.write`, CSS
`gap` — and Closure will not rename a name it finds there. `h`, `bad`,
`decreases` and `worstDrop` do not appear, so they were renamed.

Every boundary name is now a string literal through `js-obj` /
`goog.object`, which compiles to a dynamic index and is never renamed.
And because "it looks right" is what failed, `integrity-probe` writes
known values through the measured path's own accessors and reads them
back **inside the shipped `:advanced` bundle**; the driver refuses to
measure until it passes. This is a general hazard for any measurement
harness in this repository, not a B8 quirk.

---

## 5. A tenth fault class — and the mitigation this page claimed was not one

`rf2-jr76s` filed `rf2-88pie` after a control read **16.1052 B/slot in
forward arm order and 8.0027 in reversed**, against a prediction of
8.000. The bead named the cause: *a large-object arm doubles the reading
of its immediate successor*. The remedy it proposed was to run every
plan in both orders.

**Two things came out of building that remedy, and the second is the
one that matters.**

### The mitigation this page's provenance claims does not exist

The Provenance below says "arm order rotating with the round", and
`b8_run.cjs` really did select the arm for slot `j` of round `r` as
`ARMS[(j + r) % n]`. **A cyclic rotation changes which arm goes first.
It does not change which arm follows which.** Arm `a` sits at slot
`(a − r) mod n` in every round, so its predecessor is arm `(a − 1) mod n`
in every round. `order_guard.cjs` proves it arithmetically: over six
rounds of four arms, rotation gives **every arm exactly one within-round
predecessor**, and the only sample that differs is the one at the round
seam — one in six.

The same bare rotation was in `b6-harness/round!` and
`b6-rows/update-round!`, and both published the same sentence in their
`:measurement-method`. So every figure this page and the B6 rows carry
was taken under a mitigation that was not one. **The figures are not
withdrawn** — nothing here says the contamination occurred, only that
this page could not have detected it — and §4's discipline applies:
a mitigation that is not checked is an assumption.

All three now rotate **and reflect** on odd indices, which replaces every
`a → a+1` adjacency with `a → a−1` and gives each arm two predecessors in
balance.

### What a live reproduction says the cause actually is

`order_guard.cjs --live` rebuilds the control in plain node — a
`.slice()` of a packed SMI array, predicted 8.000 B/slot plus a
16-byte header — and separates the factors a reversal moves together.
**node 24.13.0, V8 13.6, pointer compression OFF**, `--expose-gc
--min-semi-space-size=32 --max-semi-space-size=64`:

| factor | reading | |
|---|---|---|
| **position in the run** — the same control, nothing else varying, sixteen consecutive windows | `10.32 10.26 10.26 10.26 10.33 10.28` then `8.12` ×10 | **+27% for six windows, then a step to the prediction** |
| **the immediate predecessor** — position held fixed, warm | 8.1377 after an 87 KB-object arm, 8.1377 after a 3-element arm | **identical** |
| the same, fresh process per reading, both measured first | 10.2900 after the large arm, 10.2588 after the small one | **0.3%** |
| **window size** — a knob unrelated to order | cold: 16.50 at 32 slices against 8.12 at 8; warm: 8.2515 against 8.1377 | **2.03× cold, +1.4% warm** |

Positive control: predicted **8.016** B/slot, settled **8.1221**
(+1.3%), cold **10.3193** (+28.7%).

**The immediate predecessor is worth a third of a percent. Everything
else is one effect wearing three hats** — a site that has not run enough
times reads 1.26× to 5.3× its settled value — and *both* a plan reversal
*and* a change of window size move where in that curve an arm is
measured. That is not a claim about what happened inside `rf2-jr76s`'s
own harness, which is not reproduced here and whose mechanism stays
undiagnosed. It is a claim about the remedy: **running a plan in both
orders confounds adjacency with position, so it cannot say which of them
it caught.**

### The property, and what it refuses

`order_guard.cjs` partitions every arm's samples by **what ran
immediately before it** and by **where in the run it was measured**
(first third against last third, in run order), and applies this
repository's own rule: overlapping ranges mean indistinguishable, so a
stratification is a finding only when the ranges are disjoint *and* the
medians differ by more than a stated tolerance. An arm with one stratum
on a factor is `unchecked`, and **unchecked refuses as loudly as
contaminated** — a single-order result has not been checked and may not
be quoted.

`b8_run.cjs` runs the guard's self-test before the browser starts, the
way §4's integrity probe runs before any figure is read, and **exits
non-zero on a refusal** with the table printed but marked unquotable.
The self-test is fixtures replayed from the recorded readings, so it is
deterministic: it refuses the 2.01× on strata of one sample each, passes
the same study's 0.43% slope, refuses a single-order plan, declines to
fire on medians 20% apart whose ranges overlap, and catches the
measured warm-up step — **which the bead's own proposed remedy would
have missed**, because the predecessor never changes across it.

### What it did on its first real run

`B8_ROUNDS=4 node b8_run.cjs --kind narrow`, in this repository's own
Chromium harness. **Exit 2. Two findings, neither of them adjacency.**

- **The `instrument` arm reads 4,361 B/write in its first window and
  440 B in its last** — 9.9×, on the pseudo-arm that installs no state
  and drains nothing, whose entire purpose is to price the harness's own
  footprint as a constant. Both factors caught it. It is the same warm-up
  curve the plain-node sweep above shows, in the real harness, on real
  data.
- **`reagent` reads 24,050 B/write early and 30,307 late**, 1.26×.

And the factor the bead named came out **clean on every substantive
arm**:

| arm | after `instrument/D=12500` | after `reagent/D=12500` | verdict |
|---|---:|---:|---|
| floor | 142,167 [141,682–142,652] | 146,232 [143,663–148,801] | within 25% |
| freehand-interpreted | 171,870 [149,547–194,193] | 157,567 [152,056–163,078] | ranges overlap |

Those predecessors are the ladder's **100 KB-per-write** windows — the
large-object arms the bead says should double their successor. They do
not move either arm past this instrument's noise. **The contaminant on
this surface is how long the process has been running, not what ran
immediately before.**

**Not fixed here: the warm-up itself.** `b8_run.cjs` warms each arm with
one four-write calibration window, and both sweeps say a site can take
six. The guard detects an insufficient warm-up rather than preventing
it; widening it, and running enough rounds that the phase strata are not
single samples, is unmeasured work and is why that run exits 2 rather
than green.

Raw: `ai/findings/2026-07-28.88pie-b8-order-guard-raw.txt` (local-only).

### The warm-up, widened — and the run goes green (rf2-tb345)

Four writes size a window; they do not warm one. Each `(arm, D)` now gets
a floor of six FULL-SIZE windows that are thrown away, and keeps warming
while the guard's own phase rule still separates the trajectory's first
third from its last, to a ceiling of ten. Asking the settling question
the way the guard will ask it is the only way the loop can promise
anything about the guard — and a first draft that instead asked whether
the last three windows agreed within 10% could not tell a site that is
still warming from one that is merely noisy, so `reagent/D=0` (which
spreads 23,977–28,515 B/write when fully warm) would have warmed to the
ceiling for ever.

**The `instrument` arm is the one that justifies the ceiling**, and it does
not decay smoothly — it is bimodal. Three consecutive `--kind narrow` runs
in this repository's Chromium harness, its D=0 warm-up trajectory each
time:

```
5,969   466  10,968  440   9,512  440  440  440
11,824  6,041 16,372 440   5,846  440  440  440  440  14,109
7,047   466  29,156  440   8,862  440
```

Its value in all 84 MEASURED windows of each run was **440 [440–440]**.
So the first full-size window read 13.6×, 26.9× and 16.0× the figure the
arm actually has, and the spiking runs several windows deep. The 9.9×
this page recorded above understated it, and a single four-write
calibration window charged all of it to round 1.

`ROUNDS` also moves from five to six, because the guard splits an arm's
samples into thirds: at five a third is ONE sample, which carries no
range, and the phase question has to be adjudicated on a bare ratio.

**With both changes the run exits 0 and the guard reports `reportable` —
no arm reads differently for its position in the plan** — where the same
driver exited 2 before. Three consecutive runs agree. Per run: 84 of 84
windows verified at the DOM, **0 unverified writes of 2,400**, bookkeeping
identity exact (worst residual 0 B), in-page rising-step sum against the
CDP bracket worst 3.3–4.7%, every site settled inside the ceiling.

The positive control still misses, and the miss is the documented one: the
control object is priced at **16.002 B/double by the retention pass** and
the allocation instrument's slope reads **11.907** on `instrument`
(−25.6%), 10.770 on `freehand-interpreted` (−32.7%) and 6.934 on
`reagent` (−56.7%). A collection inside a window nets the reclaimed bytes
away inside whichever rising step contained it, so the slope UNDER-states
— which is why the ladder rungs are 100 KB-per-write windows and why the
per-arm figures are taken at D=0. Warming the sites did not change that
and was never going to.

### What the same guard did on the CORE harnesses (rf2-om73r)

`order_guard.cjs` is a CommonJS module for a Node driver holding a
Chromium page. Neither of core's allocation harnesses can consume it —
`read-attribution` is JVM Clojure with no JavaScript runtime in the
process, and `write-attribution` is ClojureScript inside
`implementation/core`, which may not require out of
`implementation/freehand`. So the rule is expressed twice, as
`re-frame.bench.order-guard` (CLJC), with both self-tests replaying the
same recorded fixtures so neither can drift quietly. Eight checks each.

- **B7 carried the same false rotation** — `HEAP_ARMS[(j + round) % n]`,
  published as "order rotating with the round" — and now rotates and
  reflects. Guarded, it **passes**: `reportable` on both factors for all
  seven arms, 0 unverified of 49 mounts, and its own positive control at
  **4,700,288 B measured against 4,700,000 predicted (+0.006%)** on
  reader A. It is a retention harness, so this says nothing about
  allocation; it says the arm order was not moving its figures.
- **`write-attribution` refused**, and on the three arms whose own
  allocation is nearest zero — all of them the SHIPPED half of a paired
  control. `P-SCOPEH` read 32.00 B/call in one stratum and 637.96 in the
  other; `P-INHERH` 48.00 against 653.96; `P-RKV` 80.94 against 686.93.
  Zero variance inside each stratum, and the same ~606 B/call step on all
  three. **Node, uncompressed pointers** — a tagged slot is 8 B there and
  4 in Chrome, so shares transfer and absolutes do not. The cause is now
  known, and is below.

#### What the step was (rf2-xu0ma)

The three arms were being charged for **a boxed number that belonged to
another arm**.

Every arm feeds `keep!`, which increments one shared counter. The
increment is type-PRESERVING: given an Smi it yields an Smi and allocates
nothing, given a double it yields a double, and storing that double back
into the volatile's tagged field boxes a fresh `HeapNumber` — 16 B with
pointer compression off. The four `DBL-*` control arms used to *store*
their sampled element into that same counter, and `packed-doubles`
element 0 is `0.5`. So after any `DBL-*` arm the counter was a double,
and it stayed one until an `SMI-*` arm put an integer back.

`slot-order` runs the plan ascending on even rounds — where `SMI-200`
resets the counter before the body — and descending on odd ones, where a
`DBL-*` arm is the last control the body sees. An arm with a
300-iteration inner loop therefore carried **300 × 16 = 4800 B/call on
odd rounds and nothing on even ones**, deterministically, which is the
step and its zero within-stratum variance. `P-RKV`'s contaminated
stratum is labelled with the predecessor `DBL-100` in the guard's own
report — the arm that put the double there.

Priced against a prediction stated before the measurement — 16 B × 300
iterations = 4800 B/call — the probe reads **+0.00% at reps 64, 128, 256,
512 and 1024**, and `P-EMIT`, the one 4000-rep arm with no `keep!` in it,
does not move at all (0.0 B/call under both seeds).

The bead's second puzzle — the step reading ~606 B/call in one run and
~4800 in another, "a fixed block whose size varies run to run" — is the
same 4800, **truncated**. `calibrate` caps reps at 4000, and at that cap
these arms' contaminated windows are 19.2 MB, far past the nursery, so
scavenges run *inside* the measurement window and the counter reads
survivors rather than allocation. The residue is 2,423,840 B, to the
byte, in every run that hit the cap. Which figure a run showed was
decided by nothing more than whether `calibrate`'s four-rep probe landed
on the cap that time.

With the control arms feeding `keep!` instead of clobbering it, `keep!`
is the sole writer of the counter, the counter is an Smi for the whole
run, and the harness returns **`reportable` on all 33 arms** in three
consecutive runs. The `DBL-100` control falls from 864.9 to **848.2
B/copy against 848 predicted (+0.02%)** — that is the box leaving, and it
is the cleanest confirmation available that the 16 B was real and is
gone.

**What it cost.** Every arm with a 300-iteration inner loop was
over-read by 16.0 B/sub; the ladder's `RFWRITE-*` rungs and the
`Q-SCHED*` pair contain no `keep!` and were untouched. Corrected against
contaminated, per sub, node: `P-SCOPEM` 744.2 (was 760.2), `P-INHER`
240.2 (was 256.2), `P-SCOPE` 120.1 (was 136.1), `P-BODY` 280.9 (was
296.2), `P-VALS` 308.6 (was 321.8), `P-MEMO` 641.3 (was 644.9). The
shipped halves move by more than their own size: `P-INHERH` **0.2 (was
16.2)**, `P-RKV` **0.3 (was 16.3)**, `P-SCOPEH` **0.1 (was 2.1)** — the
shipped spellings allocate essentially nothing, and the retired-vs-shipped
*differences* mostly survive because both halves carried the same 16 B in
the same stratum. The exception is the hoist, where the shipped half was
also truncated: **744.1 B/sub saved, not 758.1**.

- **A second discrepancy this exposed, since isolated and fixed.** The
  `SMI-*` control read **almost exactly twice** its own prediction —
  1664.3 B/copy at D=100 against 848 predicted, 3275.7 against 1648 — so
  the printed `SMI slope` of 16.11 B/slot was 2× the 8 B a tagged slot
  occupies with pointer compression off. It was *not* the box above: the
  `SMI-*` arms store an Smi and the 2× survived that fix unchanged. The
  fault was the **instrument's, not the prediction's** (`rf2-l3jv4`).
  `.slice()`'s clone fast path is keyed on the receiver's elements kind,
  and V8 keeps one inline cache per call *site* — per function body,
  shared by every closure made from it. The six control arms were built
  from one factory body, so the harness had **one `.slice()` site that
  saw both `PACKED_SMI_ELEMENTS` and `PACKED_DOUBLE_ELEMENTS`**, and at
  that polymorphic site the SMI receiver loses the fast path and the
  clone allocates its elements store *twice*: `32 + 2 × (16 + 8D)` =
  1664 at D=100, measured 1664.3. The double receivers keep their fast
  path, which is why only half the pair was ever wrong. Splitting the
  site per elements kind returns the arm to **848.3 B/copy against 848
  predicted (+0.04%)** and the slope to **8.0027 B/slot (+0.03%)**. The
  regime conclusion the pair exists to draw was the right one throughout
  (compression OFF), and no figure in this document rested on the
  absolute, so nothing here moves.
- **`read-attribution` passes**, both factors, every arm, with its
  controls landing at **+0.000%** predicted against measured on both
  rungs (JVM, exact TLAB accounting).

One limitation is recorded rather than papered over: under a
rotate-and-reflect schedule an arm's predecessor is a function of the
round's PARITY, so a `predecessor` stratum establishes that *the figure
moves with the plan* — reason enough to refuse — but not that adjacency
is the mechanism. Separating them needs a third distinct order per arm.

---

## 6. What this does not cover

- **The broad view-leg ratio is an upper bound**, not a substrate result.
  §3 says why, and `rf2-mapni` is the arm that would close it.
- **Absolute bytes are this counter's, not a second opinion.** Every
  figure comes from V8's used-heap counter; the CDP bracket and the
  in-page reading are two doors onto it and agree to 2.3% worst case, but
  they are not independent of each other. The kept-vs-dropped test
  establishes that the counter does not discriminate against garbage; it
  does not establish that its absolute scale matches a heap snapshot's.
  **Ratios between arms are read by one instrument in one window and are
  unaffected by any common scale.**
- **One fixture, two write shapes.** B6's 300-cell grid, held to the
  published window. Nothing here is a real application, and a page whose
  boundaries are not all subscribed to one changing key will behave
  differently.
- **No mount.** This is the update path only. Mount allocation is
  unmeasured.
- **No GC-pause claim.** Allocation drives collection frequency, but no
  pause was timed and none is claimed. That 4.57 MB a broad write fills a
  nursery every two writes is visible in the window sizing and nowhere
  else.
- **No compiled tier**, and **no UIx** — UIx has no update arm in B6.
- **The JS heap only.** DOM nodes live in Blink's C++ heap.

---

## Provenance

- Fixture: B6's update grid — 300 reactive cells, 301 elements, one
  `v/sub` per cell against one `r/cursor` per cell; broad write = one
  `frame/replace-app-db!` every cell reads, narrow = one cell.
- Window: `b6-rows/timed-write!`'s legs, mirrored so the counter is read
  where the clock is, and cross-checked against the published function
  run verbatim in the same driver (agreement ≤0.9% on every arm).
- Sampling: 6 rounds, arm order rotating with the round, per-arm window
  sizing from a calibration probe, one un-read warm-up window per cell.
  Collection-free windows only. **That rotation was not the mitigation it
  reads as, and §5 says why**: it varies which arm goes first, not which
  arm follows which, so these figures were taken with each arm's
  predecessor effectively fixed. The driver now rotates and reflects, and
  refuses a figure that either the predecessor or the position in the run
  separates — a re-take under the guard would be needed to say the
  figures below were unaffected, and none is claimed. **The single
  calibration window was also not the warm-up it reads as**: §5 shows the
  `instrument` arm reading 13.6× to 26.9× its measured value in its first
  full-size window. Each `(arm, D)` now warms for at least six discarded
  full-size windows, and the same re-take caveat applies.
- Gates green before any figure was read: bookkeeping identity exact in
  120/120 windows; kept÷dropped 1.0001; DOM read-back 0 unverified of
  2,400 per row; harness integrity probe passing inside the `:advanced`
  bundle; sampling profiler negative control at 13%.
- Build: `:advanced`, `goog.DEBUG false`, via `--config-merge` on the
  existing `:freehand-release` build id. `implementation/shadow-cljs.edn`
  is unchanged. Chromium launched with `--enable-precise-memory-info`
  (asserted at runtime) and an enlarged nursery.
- Source:
  `implementation/freehand/test/re_frame/freehand/bench/b8_alloc.cljs`
  (the instrument and the controls), `b8_app.cljs` (the `:advanced`
  entry), `b8_run.cjs` (the driver, the window sizing and the four
  controls).
- Raw data: `ai/findings/2026-07-27.b8-alloc-under-update.json`.
- Reproduce:
  `node implementation/freehand/test/re_frame/freehand/bench/b8_run.cjs`.
