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

## 5. What this does not cover

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
  Collection-free windows only.
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
