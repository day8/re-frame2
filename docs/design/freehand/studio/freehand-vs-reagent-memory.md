# What does a boundary cost in memory, on Freehand and on Reagent?

Seat: EVIDENCE SPIKE. Beads `rf2-9oj7v` and `rf2-prjh0`.

Measured: 2026-07-27, against `worker/heap-and-mount-bench` at the commit
that carries this page — the instrument ships in the same PR. Reagent
**2.0.1**, UIx **1.4.4**, React **19.2.0**. Host: Windows 11, headless
Chromium 147 via Playwright, single developer workstation with other
agents running concurrently. Every arm is an `:advanced` ClojureScript
bundle with `goog.DEBUG false`. Current `main`, so the extra React commit
per render batch that PR #7133 arms is included.

**Only the interpreted tier is measured.** The compiled tier is ruled out
and no compiled arm appears here.

[`freehand-vs-reagent.md`](freehand-vs-reagent.md) closes with memory as
the one axis it did not touch — *"the obvious next measurement and might
well be the more decisive one"* — on the strength of
[`compiled-tier-browser-worth-it.md`](compiled-tier-browser-worth-it.md)
§1a, which found 6.26× retained heap per elidable boundary **within**
Freehand and measured no competing substrate at all. This page runs that
measurement.

---

## The answer, first

**Freehand loses on memory, and it loses by more than it loses on the
clock.** A boundary that does nothing costs Freehand **5.93× what it
costs Reagent** and **9.67× what it costs UIx**. A boundary that reads
its own fine-grained signal costs Freehand **4.19× what it costs
Reagent**. Every one of those ranges is disjoint across six rounds, and
two independent instruments agree on every figure to better than 1%.

This was the hypothesis that memory might be the axis where Freehand's
design wins outright. It is falsified. Memory is not a rescue; on this
witness it is the worst axis measured so far.

Three things to take, and the second is the one that closes the escape
hatch.

1. **Per reactive boundary, above the React floor: Freehand 4,346 bytes,
   Reagent 1,037 bytes.** At 300 boundaries that is 1.24 MB against
   0.30 MB; at 3,000, **12.4 MB against 3.0 MB**. The absolute figures
   are small enough that no ordinary page will run out of memory over
   them — this is a ratio result about a substrate's design, not a
   crash report.
2. **It is not re-frame's fault.** The obvious rebuttal is the one
   `freehand-vs-reagent.md` §2a used on the clock: the Freehand arm reads
   through `app-db` and a subscription graph while the Reagent arm reads a
   bare `reagent.core/atom`, so it carries more framework. But the worst
   ratio here is on the witness with **no reactivity on either side** —
   300 bodies that read nothing, no `app-db`, no subscription, no cursor.
   There Freehand costs **2,430 bytes a boundary against Reagent's 410**,
   and that 5.93× is the ViewCell wrapper alone. Adding the reactive leg
   *narrows* the gap to 4.19×, because Freehand's increment for going
   reactive (1,916 B) is only 3.06× Reagent's (627 B). The framework
   asymmetry is real and it is the *smaller* half of the finding.
3. **UIx costs less than Reagent, which costs far less than Freehand.**
   251 B, 410 B, 2,430 B a boundary. The ordering is the same one the
   mount clock gives, and the spread is much wider.

And a null result on a second question, reported here because it lands on
the same release row:

4. **B6's published mount row does NOT overstate the substrate**, and the
   bug behind `rf2-prjh0` does not exist. See [§3](#3-the-mount-window-does-not-contain-a-frame).

---

## Method, and the instrument that had to be rebuilt

**Retention, never allocation.** V8's CDP *sampling* heap profiler drops
the samples of collected objects. Pointed at a mount/unmount loop it
reports the residue of a page that has already been discarded, not the
cost of the page: the same 80,000 objects read **4.77 MB when a global
held them and 0.00 MB when nothing did**. That fault has already produced
one wrong table on this surface, and the reasoning that rationalised it —
*allocation is a counter, a collection inside the window cannot make it
smaller* — is simply false of a sampler.

So nothing here counts allocations. A reading is: **mount K roots and
keep them**, force a full collection, read the heap; release, collect,
read again. `K = 10` roots × 300 boundaries = **3,000 boundaries held**
per reading, six rounds, arm order rotating with the round.

**Three readers, and an honest account of how independent they are.**

| | reader |
|---|---|
| **A** | CDP `Runtime.getHeapUsage().usedSize`, after three forced collections |
| **B** | in-page `performance.memory.usedJSHeapSize`, same moment, under `--enable-precise-memory-info` |
| **C** | a full heap **snapshot**, with every node's `self_size` summed by a streaming scan |

**A and B are not independent of each other, and this page does not bank
them as if they were.** Pointed at 80,000 held objects they returned the
identical figure to the byte — 3,868,954 both — because they are two
doors onto one V8 counter. B is a cross-check that the page and the
debugger see the same heap, nothing more. **C is the independent
reader**: it walks the object graph and sums what is actually there, and
it is the one that makes the table falsifiable. It is slow, so it runs as
its own pass rather than in every round.

**The positive control rides every round, and its size is predicted
rather than observed.** A dense JS array of 587,500 doubles, which V8
stores as unboxed 8-byte slots: **4,700,000 bytes, known before anything
is measured** — deliberately the same ~4.7 MB the broken sampler reported
as 0.00 MB.

| control | predicted | measured | error |
|---|---:|---:|---:|
| **A** | 4,700,000 B | 4,692,044 B [4,654,514–4,700,974] | **−0.17%** |
| **B** | 4,700,000 B | 4,691,993 B | −0.17% |
| **C** | 4,700,000 B | **4,700,024 B** | **+0.0005%** |

> The control's own first draft was a flat one-byte string of 4,700,000
> characters, on the reasoning that a `SeqOneByteString` costs its length
> plus a header. **It read as six kilobytes on all three readers** — V8
> does not materialise `'x'.repeat(n)`. Had it shipped, the control would
> have missed by three orders of magnitude every round and the natural
> conclusion would have been that the instrument was broken, when the
> instrument was fine and the control was a fiction. It is recorded
> because it is the same class of mistake as the sampler, caught one step
> earlier, and because it is the argument for running a control rather
> than reasoning one out.

**Every mount is verified at the DOM, inside its own reading.** After
each mount the boundary elements the arm should have produced are counted
against `K × 300`. An arm that silently rendered nothing would otherwise
read as the cheapest substrate in the table. **0 unverified of 56
mounts.**

**A warm-up pass precedes the rounds and is never read.** The first mount
of any arm allocates things that are not the page and never go away —
compiled code for the paths it just took, inline caches, interned
keywords, one-time module state. A smoke run charged those to round 1 and
saw 400–700 KB of unreleasable residue per arm; after the warm-up the
residue is 4–30 KB against multi-megabyte retentions, which is what a
clean release looks like.

**The floor is subtracted, and that is what makes the figure a substrate
figure.** DOM nodes live in Blink's C++ heap, not V8's, so none of these
readings contain the elements themselves. Every arm builds the identical
DOM — B6's canonical-DOM parity gate is what establishes that — so the
omission cancels exactly in `arm − floor`, which is the column quoted.
The absolute column is a JS-heap figure and is labelled as one.

**Two witnesses.** **`storm`** is 300 sub-free leaf boundaries per root,
301 elements — B6's W2 shape, in four dialects. It prices the wrapper and
only the wrapper. **`reactive`** is 300 cells per root each reading its
own fine-grained signal, Freehand's `v/sub` against Reagent's
`r/cursor` — B6's update grid, held rather than driven. All K roots of an
arm share one state cell (one `reagent.core/atom`, one frame), because
that is how an application is shaped and K stores would price the store
rather than the boundary.

---

## 1. Retained heap per boundary

**Absolute**, JS heap only, mean of six rounds with the range in
brackets, instrument C beside it:

| arm | A — B/boundary | C |
|---|---:|---:|
| `storm` floor — no substrate | 244 [241–247] | 472 |
| `storm` **UIx** | 496 [488–501] | 725 |
| `storm` **Reagent** | 654 [647–661] | 879 |
| `storm` **Freehand** | **2,675** [2,662–2,693] | 2,899 |
| `reactive` floor — no substrate | 244 [241–246] | 474 |
| `reactive` **Reagent** `r/cursor` | 1,281 [1,276–1,284] | 1,508 |
| `reactive` **Freehand** `v/sub` | **4,590** [4,576–4,604] | 4,804 |

C sits about 228 B/boundary above A on *every* arm, which is a constant
offset in what the two readers count as heap and not a disagreement about
any substrate. Subtract the floor and the two agree almost exactly:

**Above the React floor**, paired within each round:

| arm | A — B/boundary above floor | C | A vs C |
|---|---:|---:|---:|
| `storm` **UIx** | 251 [248–255] | 253 | 0.60% |
| `storm` **Reagent** | 410 [406–416] | 407 | 0.85% |
| `storm` **Freehand** | **2,430** [2,417–2,450] | 2,427 | **0.16%** |
| `reactive` **Reagent** | 1,037 [1,035–1,039] | 1,034 | 0.29% |
| `reactive` **Freehand** | **4,346** [4,332–4,361] | 4,330 | 0.37% |

Two readers that share no code agree to within 1% on every row. This is
by a wide margin the best-conditioned measurement in the studio: the
clock rows in the sibling reports live with a floor that moves 20–58%
between rounds, and these ranges are ±0.7%.

**The ratios**, computed per round and then summarised, so the box's
drift cancels:

| | mean [min–max] |
|---|---|
| `storm` **Freehand ÷ Reagent** | **5.928** [5.867–5.993] |
| `storm` **Freehand ÷ UIx** | **9.668** [9.512–9.822] |
| `storm` Reagent ÷ UIx | 1.631 [1.598–1.643] |
| `reactive` **Freehand ÷ Reagent** | **4.190** [4.173–4.201] |

**What it comes to on a page**, reactive boundaries, above the floor:

| boundaries | Freehand | Reagent | difference |
|---:|---:|---:|---:|
| 300 | 1.24 MB | 0.30 MB | 0.95 MB |
| 1,000 | 4.15 MB | 0.99 MB | 3.16 MB |
| 3,000 | 12.44 MB | 2.97 MB | 9.47 MB |

A megabyte on a 300-boundary page will not sink an application, and this
page does not claim it would. What it claims is the ratio, and the ratio
is not close.

### 1a. It is the wrapper, not re-frame

The standing rebuttal to every cross-substrate figure in this studio is
that the Freehand arm carries more framework than the Reagent arm: it
reads `app-db` through a subscription graph where Reagent reads a bare
ratom. `freehand-vs-reagent.md` §2a used exactly that to cut the narrow
update result from 15× to roughly parity, and it was right to.

It does not work here, and the two witnesses are what show why.

| | Freehand | Reagent | ratio |
|---|---:|---:|---:|
| a boundary that reads **nothing** | 2,430 B | 410 B | **5.93×** |
| the **increment** for reading a signal | 1,916 B | 627 B | 3.06× |
| a boundary that reads a signal | 4,346 B | 1,037 B | 4.19× |

The `storm` witness has no `app-db`, no subscription, no cursor and no
reactivity of any kind on either side — 300 bodies that read nothing.
There is no framework asymmetry left to appeal to, and that is where the
gap is **widest**. Going reactive *narrows* it, because Freehand's
increment for a signal is 3.06× Reagent's while its wrapper is 5.93×.

So the finding decomposes the other way round from the clock's: the
larger term is the ViewCell wrapper, which is Freehand's own, and the
smaller term is the one re-frame could be blamed for.

### 1b. What it corroborates

The within-Freehand ablation in
[`compiled-tier-browser-worth-it.md`](compiled-tier-browser-worth-it.md)
§1a priced **one kept ViewCell at 2,348 bytes** of standing heap and a
reactive boundary at **4,134 bytes over the React floor**, from a
different instrument, a different comparison basis and a different
build. This page reads **2,430** and **4,346** — within 3.5% and 5%.

That is the answer `rf2-9oj7v` asked for in its own terms: **B2's elided
boundary is worth 2,430 bytes of standing heap** (2,427 on the
independent reader), and the elision count it gates can now be
multiplied out rather than merely counted. It also means the ablation's
figure was sound, which was not certain — it was taken with the same
family of tooling that produced the wrong table beside it.

---

## 2. What this does not cover

- **One shape, held still.** Two purpose-built witnesses, mounted and
  never touched. Nothing here measures heap *under* update, and a
  substrate can trade standing bytes for churn — Freehand's fine-grained
  path might allocate less per write than Reagent's, and this instrument
  cannot see it. That is the obvious next measurement, and it is now the
  only memory question with an argument left in it for Freehand.
- **Transient garbage is invisible, by construction.** Retention cannot
  see what the interpreted walk allocates and drops. §1a of the
  predecessor report flagged the same limit.
- **The JS heap only.** No DOM, no detached-node accounting, no
  Blink-side cost. The floor subtraction is what makes this safe, and it
  is safe only because the parity gate proves the pages identical.
- **No compiled tier.** Ruled out; not measured.
- **UIx on the reactive witness.** UIx's update story is `use-state` and
  React's own scheduler, a different mechanism that needs its own arm.
- **Bundle size, SSR, hydration.** None of it.
- **A real application.** Two witnesses on a loaded developer
  workstation — though the ranges here are tight enough that a quiet
  machine would move the third digit, not the first.

---

## 3. The mount window does not contain a frame

`rf2-prjh0` reported that B6's Freehand mount arm passes no `:frame`, so
`v/mount` would create a **fresh frame per sample inside the timed
`flushSync`** while the floor arm — a bare `createRoot` — never pays it.
If that were so, a measurable slice of interpreted W1's 2.987× would be
frame construction rather than view substrate, and a row on the release
gate would be too harsh.

**The premise does not hold.** `plan-for` in `root.cljs` answers `nil`
when `opts` carries no `:frame`, `preflight!` opens `(when (some? plan)
…)`, and `root-element` wraps the root form in the frame provider only
`(if (some? frame-id) …)`. A frameless mount runs no plan, creates no
frame and binds none. The published arm is not paying for a frame — it is
doing **less** than a mount that names one.

Which inverts the question rather than closing it, so it was measured.
Four arms differing in the `:frame` opt and nothing else, on B6's
harness unchanged, six interleaved rounds of 5 warm-up + 20 samples,
every figure a ratio to the floor of that same round:

| Witness | floor | `fh-no-frame` — **the published arm** | `fh-shared-frame` | `fh-frame-per-sample` | Reagent |
|---|---:|---:|---:|---:|---:|
| **W1** 1,203 el | 1.000 | **3.109** [2.889–3.588] | 3.229 [3.000–3.706] | 3.347 [3.111–4.059] | 1.545 [1.471–1.647] |
| **W2** 301 el | 1.000 | **8.667** [7.00–10.00] | 9.611 [7.67–11.00] | 9.917 [7.00–11.50] | 2.528 [1.67–3.00] |

`fh-shared-frame` scopes a frame stood up before the run — the studio
fixture's shape, the one the bead proposes as the correction.
`fh-frame-per-sample` ensures a **fresh** frame id every sample: the
counterfactual, priced so the report can say what the mistake would have
been worth had it been real.

**The three Freehand arms' ranges overlap, so on ranges alone they are
indistinguishable** — which is what this studio's method requires be said
first. But the arms are interleaved *within* each round, so the paired
comparison is available and is far stronger than the ranges:

| paired, per round | W1 | W2 |
|---|---|---|
| `fh-shared-frame` ÷ `fh-no-frame` | **×1.038**, higher in **6 of 6** rounds | **×1.109**, higher in **6 of 6** |
| `fh-frame-per-sample` ÷ `fh-no-frame` | **×1.074**, higher in **6 of 6** | ×1.138, higher in 5 of 6 |

Six of six in one direction is a real effect, and the direction is the
opposite of the bead's. **The published arm is the cheapest of the three
Freehand shapes.** Correcting the row to the studio fixture's shared
frame would move interpreted W1 mount *up* by 3.8% and W2 up by 10.9%;
naming a fresh frame per sample — the thing feared — would have cost 7.4%
on W1, so even had the bug been real it would have been worth a fraction
of a 2× gap.

**So the mount row stands, and no published figure changes.** If it is
generous to Freehand it is generous by ~4% on W1, in the direction of
flattering rather than punishing the substrate. The `:frame` opt costs
what a frame provider costs — a ledger read and one more React context
above the tree — and B6's arm does not pay it.

**The row reproduces.** This harness is B6's, re-run on a different day
against current `main`. Reagent W1 comes back at **1.545 [1.471–1.647]**
against B6's published **1.554 [1.520–1.583]**, and interpreted Freehand
W1 at **3.109 [2.889–3.588]** against **2.987 [2.840–3.167]** — both
overlapping, the Freehand arm ~4% higher, consistent with PR #7133's
extra React commit per render batch landing inside the window plus a
differently loaded box. The mount row is reproducible to within its own
stated ranges, which had not previously been shown.

---

## Provenance

- Fixtures: `storm` = 300 sub-free leaf boundaries, 301 elements (B6's
  W2); `reactive` = 300 cells each reading their own signal, 301 elements
  (B6's update grid); W1 = 300 rows, 1,203 elements.
- Heap sampling: 10 roots × 300 boundaries held per reading, 6 rounds,
  arm order rotating with the round, one un-read warm-up pass first.
  Snapshot pass separate, once per arm.
- Mount sampling: 6 rounds × (5 warm-up + 20 samples) per arm, arms
  interleaved at the sample level with the order rotating on the sample
  index; every figure a ratio to the floor of that round.
- Gates green before any figure was read: canonical-DOM equality across
  all five mount arms with the written element counts (1,203 / 301), and
  a DOM read-back of every heap mount — **0 unverified of 56**.
- Build: `:advanced`, `goog.DEBUG false`, via `--config-merge` on the
  existing `:freehand-release` build id. `implementation/shadow-cljs.edn`
  is unchanged.
- Source:
  `implementation/freehand/test/re_frame/freehand/bench/b7_heap.cljs`
  (the arms and the retention door),
  `b7_mount_frame.cljs` (the four `:frame` arms), `b7_app.cljs` (the
  `:advanced` entry) and `b7_run.cjs` (the collector, the three readers
  and the positive control).
- Reproduce:
  `node implementation/freehand/test/re_frame/freehand/bench/b7_run.cjs`.
