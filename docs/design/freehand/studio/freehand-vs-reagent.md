# How fast is Freehand, relative to Reagent?

Seat: EVIDENCE SPIKE. Bead `rf2-dq20a`, answering the operator's question
of 2026-07-27 verbatim.

Measured: 2026-07-27, against branch `worker/bench-vs-reagent` at the
commit that carries this page — every arm in it ships in the same PR.
Reagent **2.0.1**, UIx **1.4.4**, React **19.2.0**. Host: Windows 11,
headless Chromium 147 via Playwright 1.59.1, single developer workstation
with other agents running concurrently. Every arm is an `:advanced`
ClojureScript bundle with `goog.DEBUG false` — the artefact a consumer
ships. Nothing here is a release-worker number, and the
[method](#method-and-what-it-refuses-to-claim) says where that matters.

This is the companion to
[`compiled-tier-browser-worth-it.md`](compiled-tier-browser-worth-it.md),
which measured Freehand against *itself* — interpreted against compiled,
both against a hand-built React floor. It measured no competing
substrate. This one adds Reagent, and UIx, on the same floor, building
the same DOM, so the rows slot into that report's table rather than
starting a second incomparable one.

---

## The answer, first

**On mount, compiled Freehand is slightly faster than Reagent and
interpreted Freehand is about twice as slow. On update, Reagent is
between 9× and 15× faster — and the decomposition says most of that gap
is not Freehand at all. It is `re-frame`'s write path.**

Four findings, and the third is the one that changes what to do about it.

1. **Mount: compiled Freehand wins narrowly, interpreted Freehand loses
   clearly.** On the large template compiled Freehand is **1.30× the
   floor** against Reagent's **1.55×** — a 16% advantage, with disjoint
   ranges. Interpreted Freehand is **2.99×**, which is **1.9× slower than
   Reagent**. On the ordinary form all four substrate arms are within a
   whisker of each other and of the floor.
2. **Update: Reagent is far ahead on the wall clock.** A write that
   repaints 300 boundaries costs Freehand **6.4–7.4 ms** and Reagent
   **0.6–0.7 ms** — **≈10×**. A write that repaints one costs Freehand
   **≈1.0 ms** and Reagent **≈0.065 ms** — **≈15×**. On the narrow write
   Freehand is also **8.8× slower than a plain top-down React re-render
   of all three hundred cells**, which is the reading that should be
   uncomfortable.
3. **But on a narrow write, ~90% of Freehand's cost is the `app-db`
   write and its subscription recompute, not rendering.** Splitting the
   window into its legs: of Freehand's ≈1.0 ms, **≈0.9 ms is
   `frame/replace-app-db!` plus the signal graph** and only ≈0.075 ms is
   React render and commit — which is *the same* as Reagent's ≈0.06 ms.
   **A Reagent application reading re-frame subscriptions would pay that
   same write leg.** The 15× is mostly a re-frame-versus-bare-ratom
   result wearing a Freehand-versus-Reagent label, and the honest
   Freehand-specific number is much smaller.
4. **Where Freehand's view layer really is slower is bulk re-render.** On
   the broad write, render is ~72% of Freehand's cost and Freehand's
   render leg is **4.0–5.4 ms against Reagent's 0.6–0.7 ms** — a genuine
   **≈7–8× on re-rendering 300 boundaries**, which no accounting moves.

And one property that is not a number: **Freehand could not commit a
state change synchronously.** A write made inside `react-dom/flushSync`
had not reached the DOM when the flush returned; the notification rode a
microtask. Reagent and plain React both commit in that window. It was
filed as `rf2-w2m25`, it forced this report's whole measurement shape,
and it was the finding most likely to matter to somebody writing an
application. **`rf2-w2m25` is now fixed** — see §2b — but every figure
below was taken before the fix, through the window it forced.

**What this does *not* say.** It does not say Freehand should adopt
ratoms, and it does not say the compiled tier is vindicated. The mount
advantage is real but small; the update deficit is real and large; and
the largest single term in the update deficit is owned by `re-frame`
rather than by either view substrate.

---

## Method, and what it refuses to claim

The instrument is
[`compiled-tier-browser-worth-it.md`](compiled-tier-browser-worth-it.md)'s,
reused rather than rebuilt: the same hand-written `react/createElement`
floor, the same shapes, the same in-run floor normalisation, the same
refusal to quote a number whose range straddles 1.0. Source is
`implementation/freehand/test/re_frame/freehand/bench/b6_*`.

**Five arms, one page.** The React floor, interpreted Freehand, compiled
Freehand, Reagent, and UIx each carry their *own* declaration of each
witness, in their own dialect. The Reagent arm is written as a Reagent
user writes it — plain Hiccup with class sugar, ordinary `defn`
components, `^{:key i}` metadata on seq children, a form-2 component with
`reagent.core/cursor` over a `reagent.core/atom`, and
`reagent.dom.client` for the mount. No `:>` shortcut appears, nothing is
pre-adapted, and no React class is hand-built. The UIx arm uses the `$`
macro, because the point of including it is that `$` expands to
`react/createElement` at compile time.

**The fairness gate is canonical DOM, and it runs before any clock.**
Every arm mounts simultaneously and the browser's own DOM is serialised
with attribute names *sorted* — comparing `innerHTML` compares the
serialiser's insertion order, which is how the predecessor report's first
run nearly concluded that two identical pages differed. All five arms
agree exactly, at both the stress and the small size, on all three
witnesses, and the element counts match written arithmetic
(1203/301/51). Without that gate this document would be comparing five
different pages.

**Interleaved at the sample level.** Every sample index mounts every arm,
in an order that rotates with the index, so no arm is always first into a
cold cache. Six rounds. Every figure is a **ratio to the floor measured
in that same round** — the floor contains no substrate and cannot be
changed by anything here, so it is the in-run calibrator for a box that
drifts more across rounds than several of these effects are large.

**The update window, and the fact that forced its shape.** The first
update instrument bracketed each write in `react-dom/flushSync`, exactly
as the mount row does. It measured nothing at all for two of the four
arms, and the DOM read-back is what caught it: after every Freehand write
the cell still held its old value. Measured directly:

| how the write was driven | did the DOM change? |
|---|---|
| `flushSync(write)` | **no** |
| `write`, one microtask, `flushSync(noop)` | yes, ~1 ms |
| `write`, `setTimeout 0` | yes, ~2 ms |
| `write`, `requestAnimationFrame` | yes, ~32 ms (two frames) |

So every arm is timed through one shape, and it is the shape the slowest
arm requires: **write, yield one microtask, force that substrate's own
documented synchronous drain, stop the clock** — an empty `flushSync` for
the floor and both Freehand arms, `reagent.core/flush` for Reagent.
Nothing runs inside `act`, which diverts work to its own queue and,
measured, cost 600 ms a call.

**Every measured write is verified at the DOM, inside the window.** The
cell just written is read back and the sample is rejected if it does not
hold the value written. Both published rows report **0 rejected of 384**.
This is not ceremony: it caught two instrument faults that would each
have produced a confident wrong table. The first is the one above. The
second is that an *empty* `flushSync` flushes only React's sync lane, so
the floor arm's `root.render` — scheduled at the default lane — has to be
issued *inside* the flush; until it was, 80 of 320 floor samples ended
with the old value on screen and every other arm's ratio was inflated
against a floor that had not rendered.

**Production compilation, deliberately.** Spec 009 instrumentation,
schema validation and trace emission are all `goog.DEBUG`-gated and all
sit on the path the update row measures. Reagent has no counterpart to
any of them, so a development build penalises Freehand in one direction
only — and measurably: under `:none`/`goog.DEBUG true` the same rows put
Freehand's broad update at 6.6–8.0× the floor, where production puts it
at 24.8–27.8×, and put mount W2's interpreted arm at 5.4× where
production says 7.5×. Neither build is "the" answer, but the shipped one
is the one a user experiences. `:advanced` cannot compile shadow's
`:browser-test` target — `cljs-test-display`'s `goog.define`s collide —
so the production reading rides a plain `:browser` entry point built by
merging an output directory and an `:init-fn` into the existing
`:freehand-release` build id. `implementation/shadow-cljs.edn` is
unchanged.

**Six things this report refuses to claim.**

- **No claim about `:none` builds** beyond the direction noted above.
- **No absolute number is comparable across rounds.** The floor moved
  20–30% within this run.
- **W2 and W3's absolute times sit near Chrome's 100 µs `performance.now`
  clamp** (0.3–1.0 ms). Their ratios are correspondingly coarse — read W1
  for a resolved mount reading. The narrow update row batches 20 writes
  to a sample for exactly this reason; the broad row does not need to.
- **Any range that straddles 1.0 is reported as indistinguishable**, not
  as a win. One does: UIx on W3.
- **No memory claim of any kind** *in this report*. It has since been
  measured: see
  [`freehand-vs-reagent-memory.md`](freehand-vs-reagent-memory.md), and
  §3 below.
- **No claim about a real application.** These are six purpose-built
  witnesses.

---

## 1. Mount

Fixture: **W1** 300 rows under one boundary each, multi-class sugar, a
style map, a `data-*` passthrough, 1,203 elements. **W2** 300 sub-free
leaf boundaries, 301 elements. **W3** a 12-field form with controlled
inputs, per-field error lines and a disabled submit gate, 51 elements.
6 rounds × (5 warm-up + 20 samples) per arm, arms interleaved.

**Mount cost as a ratio to the in-run React floor** — mean of six rounds,
range in brackets:

| Witness | Freehand interpreted | Freehand compiled | **Reagent** | UIx |
|---|---:|---:|---:|---:|
| **W1** large template, 1,203 el | 2.987 [2.840–3.167] | **1.304** [1.280–1.333] | 1.554 [1.520–1.583] | 1.163 [1.120–1.208] |
| **W2** 300 elidable boundaries † | 7.472 [6.250–8.333] | 2.139 [1.750–2.333] | 2.500 [2.000–3.000] | **1.361** [1.250–1.667] |
| **W3** ordinary form, 51 el ‡ | 1.664 [1.400–2.000] | 1.325 [1.167–1.667] | 1.394 [1.200–1.667] | 1.153 [1.000–1.333] |

† **W2 overstates Freehand's advantage and is not a headline.** Its
bodies read nothing, so Freehand's compiled tier can *prove* them
sub-free and drop 300 ViewCells. Reagent has no elision concept and
neither does UIx. It is here because the predecessor report measured it.

‡ W3's absolute times are 0.3–1.0 ms against a 0.1 ms clock quantum. All
four arms overlap; UIx's range includes 1.0, so **UIx and the floor are
indistinguishable on the form**, and the four substrate arms are barely
separable from each other.

> **Does the Freehand mount arm bill frame construction to the
> substrate?** It was put to this row as `rf2-prjh0`, on the grounds that
> the arm passes no `:frame` and would therefore create one per sample
> inside the timed `flushSync`. **It does not** — a frameless `v/mount`
> runs no plan and creates no frame — and measuring the three shapes
> found the published arm is the *cheapest* of them: a shared
> pre-created frame costs 3.8% **more** on W1 and 10.9% more on W2, in
> 6 of 6 paired rounds. No figure in this table changes. The row also
> reproduced on a later day against current `main`, Reagent W1 at 1.545
> [1.471–1.647] against the 1.554 above. Detail in
> [`freehand-vs-reagent-memory.md`](freehand-vs-reagent-memory.md) §3.
>
> **The row is therefore ~4% generous to Freehand on W1, and the table
> keeps it** (`rf2-g88fx`). Every real application binds a frame; this arm
> passes only a `:disambiguator`, so it prices the cheapest mount shape
> rather than the shipped one. Re-taken on `main` at `7c41225b4b` on
> 2026-07-27 the published arm reads **3.056 × floor** [2.931–3.226]
> against Reagent's **1.588** [1.500–1.742] — **1.92× Reagent**. Applying
> the measured ×1.038 puts the shape an application actually ships at
> **≈2.00× Reagent**. That is the number to quote for a consumer, and it
> is the same conclusion: interpreted mount is about twice Reagent.
> Changing the arm was rejected because the correction is smaller than
> the row's own round-to-round spread and re-basing a release-gate row
> costs more than the third digit it buys.

**And in milliseconds, because a ratio on a sub-millisecond operation is
not a product decision** (p50, round 4 of six — the same round §2a
decomposes):

| Witness | floor | FH interp | FH compiled | Reagent | UIx |
|---|---:|---:|---:|---:|---:|
| W1 | 2.4 | 7.3 | 3.2 | 3.8 | 2.9 |
| W2 | 0.3 | 2.4 | 0.7 | 0.8 | 0.4 |
| W3 | 0.5 | 0.7 | 0.6 | 0.6 | 0.5 |

**The three readings that matter.**

**Compiled Freehand beats Reagent on the large template, by 16%.** 1.304
against 1.554, ranges disjoint (1.333 vs 1.520). In substrate-overhead
terms — subtracting the floor — that is 0.304 against 0.554, so compiled
Freehand's own cost above React is **45% lower than Reagent's**. It is a
real win and it is a modest one: 3.2 ms against 3.8 ms on a 1,203-element
page.

**Interpreted Freehand loses to Reagent, clearly, on every witness.**
2.987 against 1.554 on W1 — **1.9× slower**, or 3.6× on substrate
overhead alone. That is the tier most applications would actually be
running.

**UIx sits at the floor, and the operator's hypothesis holds.** 1.163 on
W1, 1.361 on W2, 1.153 on W3 — nearest the floor on every witness. `$`
expands to `react/createElement` at compile time, so there is no runtime
walk to pay for, exactly as predicted.

**And UIx beats compiled Freehand on the boundary storm** — 1.361 against
2.139 — which is the contrast worth pausing on. W2 is the shape that
maximises ViewCell elision, UIx has no elision at all, and UIx still
wins. Elision buys the compiled tier a large gain *over interpreted
Freehand* (7.472 → 2.139); it does not buy it a lead over a substrate
that never created a wrapper to elide.

---

## 2. Update

This is the half the predecessor report returned a null result on
(0.2–0.5 ms p50 on every arm, indistinguishable at the timer), and the
half Reagent is actually designed for.

Fixture: a 300-cell grid, one reactive leaf per cell. Freehand reads
`(v/sub [:b6/cell i])` per cell and is written with
`frame/replace-app-db!` on its own frame. Reagent reads an `r/cursor` per
cell over a `reagent.core/atom` and is written with `swap!`. The floor
has no substrate and re-renders top-down. 6 rounds × (4 warm-up + 12
samples).

**BROAD** is one write every cell reads — 300 boundaries repaint. It
prices throughput, where fine grain buys nothing because all the work is
required. **NARROW** is one write one cell reads. It prices localisation.

| Row | Freehand interpreted | Freehand compiled | **Reagent** |
|---|---:|---:|---:|
| **broad** — 300 cells repaint | 27.78 [21.33–35.00] | 24.83 [19.00–30.50] | **2.639** [2.000–3.500] |
| **narrow** — 1 cell repaints | 8.802 [7.636–9.954] | 8.194 [7.000–9.636] | **0.587** [0.565–0.609] |

Ratios to the in-run floor, as everywhere else — but **the update floor
is a plain top-down React re-render and is NOT a lower bound.** A
fine-grained substrate can and should beat it on a narrow write, and
Reagent does: 0.587 means Reagent moves one cell in 59% of the time it
takes plain React to re-render all three hundred. Freehand takes **8.8×**
that.

In milliseconds (p50; the narrow row's sample is 20 writes, so its
per-write figure is one twentieth):

| Row | floor | FH interp | FH compiled | **Reagent** |
|---|---:|---:|---:|---:|
| broad, per write | 0.2–0.3 | 6.4–7.4 | 5.7–6.9 | **0.6–0.7** |
| narrow, per write | 0.11–0.12 | 0.84–1.12 | 0.77–1.09 | **0.065–0.070** |

**So: Reagent is ~10× faster than Freehand on a broad write and ~15×
faster on a narrow one.** That is the answer to the question as asked,
and it should be quoted with the next section attached to it.

### 2a. Where the update time actually goes

The measured window is split into three legs, and the split is the most
useful thing in this report. `write` is the state install and everything
it synchronously triggers — for Freehand that is `frame/replace-app-db!`
and the subscription recompute. `gap` is the microtask boundary. `force`
is React render and commit.

p50 milliseconds per sample, round 4 of six throughout. Each leg is its
own p50, so the three do not sum exactly to the sample's p50 — medians do
not add; the totals column is the measured figure the ratios use.

| Row / arm | write | gap | force | total |
|---|---:|---:|---:|---:|
| **broad** floor | 0.0 | 0.0 | 0.2 | 0.3 |
| **broad** FH interpreted | 1.5 | 0.3 | **4.7** | 6.5 |
| **broad** FH compiled | 1.4 | 0.4 | **4.1** | 5.7 |
| **broad** Reagent | 0.0 | 0.0 | **0.6** | 0.6 |
| **narrow** floor (20 writes) | 0.0 | 0.0 | 2.4 | 2.4 |
| **narrow** FH interpreted (20) | **19.8** | 0.2 | 1.4 | 22.4 |
| **narrow** FH compiled (20) | **19.2** | 0.1 | 1.1 | 21.7 |
| **narrow** Reagent (20) | 0.0 | 0.0 | 1.3 | 1.4 |

Three things fall straight out.

**On a narrow write, Freehand's rendering is not the problem — the write
is.** The write leg is **19.8 ms** against a render leg of **1.4 ms**:
`frame/replace-app-db!` and the signal graph are ~90% of the sample.
And that 1.4 ms of React render sits against Reagent's 1.3 ms and the
floor's 2.4 ms. **Freehand's view layer is doing a narrow update at
Reagent's speed and beating plain React.** Everything else is re-frame's
write path, which a Reagent application reading re-frame subscriptions
would pay too. The honest Freehand-specific narrow-update number is
therefore roughly parity, not 15×.

**On a broad write, rendering *is* the problem.** 4.7 ms of a 6.5 ms
sample is React render and commit, against Reagent's 0.6 ms — **≈7–8×**
on re-rendering 300 boundaries, with the write leg only 1.5 ms.
Compiling moves it a little (4.7 → 4.1 ms) and does not close it. This is
the one number in the update section that is squarely Freehand's own, and
it is the one to act on.

**The microtask yield costs nothing, and the control is in situ.** The
floor and Reagent arms — which do not need it — report `gap` of exactly
0.0 ms in every round of both rows. What appears in Freehand's gap is
Freehand's own notification callback running there, and it scales as it
should: ≈0.35 ms when a broad write moves 300 subscriptions, ≈0.01 ms
when a narrow one moves one.

> A first attempt priced the yield by chaining 2,000 bare
> `js/Promise.resolve`s and dividing, and reported ≈1 ms a hop — larger
> than an entire measured Reagent write, and therefore impossible. It was
> measuring the construction of a 2,000-deep promise chain. It was
> deleted rather than published with a caveat, and it is recorded here
> because it is the same class of mistake as the predecessor report's
> heap sampler.

### 2b. Freehand could not commit synchronously — FIXED

Not a timing result, and probably the most consequential thing here.

> **Resolved.** `rf2-w2m25` landed after this report was taken: the
> ViewCell pending window gained a second closer that React can see, so a
> write made inside `react-dom/flushSync` now commits before the flush
> returns, through every door named below. The microtask closer is
> untouched, so ordinary batching is unchanged. The rest of this section
> is kept in the past tense as the record of what was measured, because
> it is what shaped this report's whole instrument. The figures in §2 were
> **not** re-taken and still carry the microtask yield the defect forced.

A write made inside `react-dom/flushSync` had **not** reached the DOM when
the flush returned — through any door: `frame/replace-app-db!`,
`rf/dispatch-sync` with an explicit `:frame`, or a
`capture-frame` handle's `dispatch-sync`. The store was written and the
subscription recomputed; React learnt about it on a microtask. Reagent
(`swap!` + `reagent.core/flush`) and plain React (`root.render` inside
`flushSync`) both commit inside that window. The UIx adapter's own
`flush-views!` did not commit the write when measured, and `react/act`
did but cost ≈600 ms a call.

The consequence for an application author was that **there was no public
door that made a state change observable in the DOM before the current
task ended** — which anything that measures, scrolls, focuses or asserts
immediately after a write had to work around, undocumented. Filed as
`rf2-w2m25` (P1) and now fixed; the case is pinned in **both** directions
by `a-freehand-write-commits-inside-flushsync`, which asserts the commit
lands inside the flush AND that the microtask window this row is measured
through still lands its write. The update row is therefore re-takeable
without the microtask yield; it has not been re-taken.

---

## 3. What this does not cover

Stated plainly, because the omissions are load-bearing.

- **Memory.** Nothing, in this report — and it turned out to matter.
  [`freehand-vs-reagent-memory.md`](freehand-vs-reagent-memory.md) ran
  the comparison this bullet asked for and **the answer went against
  Freehand, harder than the clock does**: a boundary that reads nothing
  costs Freehand 2,430 bytes of standing heap against Reagent's 410 and
  UIx's 251 — **5.93×** and **9.67×** — and a boundary reading its own
  signal costs 4,346 against Reagent's 1,037, **4.19×**. Two independent
  readers agree to within 1% and every range is disjoint. Crucially the
  §2a rebuttal below does *not* apply: the widest gap is on the witness
  with no reactivity and no `app-db` on either side, so the larger term
  is the ViewCell wrapper rather than re-frame's write path. Heap *under
  update* is still unmeasured and is the one memory question left with an
  argument in it.
- **Reagent reading re-frame.** The Reagent arm reads a bare
  `reagent.core/atom`, which is Reagent's own idiom but is *less
  framework* than the Freehand arm carries. §2a bounds the effect rather
  than removing it: the write leg is measured separately so a reader can
  see how much of the gap a re-frame-shaped Reagent app would also have
  paid. A direct Freehand-vs-Reagent-vs-re-frame row is not possible in
  one page today — Freehand's `v/sub` is inert under a ratom adapter
  (`rf2-8cnxg`, `rf2-jt8vz`) — and would need two adapter phases bridged
  by the floor.
- **The event leg.** Every arm is driven by a direct state install.
  `dispatch` and the event queue are excluded, identically, from all of
  them.
- **Handlers and interaction.** W1 carries no `:on-click`: Freehand's
  paved path is an event vector and Reagent's is a closure, and putting
  one in each would compare two mechanisms inside a markup row. W3's
  inputs are `:read-only` rather than carrying a change handler, so the
  `value` slot exercises the controlled-input door without dragging a
  second dispatch mechanism into a mount measurement. Editing latency
  under contention is B4's row, and is Freehand-only.
- **UIx on update.** Mount only. UIx's update story is `use-state` and
  React's own scheduler, a different mechanism that needs its own row.
- **Bundle size.** Not measured here for any substrate.
- **SSR, hydration, streaming, error boundaries, suspense.** None.
- **A real application.** Six purpose-built witnesses on a loaded
  developer workstation. A quiet pinned machine would turn several
  directional claims into numbers.

## 4. What would change the answer

1. **Cheapen the `app-db` write path.** It is 90% of a narrow update and
   it is not owned by the view layer at all, so it is the one fix that
   would help every re-frame consumer on every substrate. Measuring
   where inside `commit-frame-transition!` plus the signal graph the
   ≈0.85 ms goes is a small, high-leverage spike.
2. **Cheapen bulk re-render of boundaries.** The ≈7–8× on 300 repainting
   boundaries is Freehand's own, and compiling barely moves it, which
   suggests the cost is in the ViewCell wrapper rather than in the
   markup walk — the same term §1a of the predecessor report priced at
   2,348 bytes of standing heap per kept ViewCell.
3. **~~A synchronous commit door.~~ DONE — `rf2-w2m25`.** Re-take the
   update row without the microtask yield, which would tighten every
   figure in §2. Note that the fix arms one extra React commit per render
   batch (a two-fiber detached root rendering nil); it lands outside the
   current window but would move inside a re-taken one, so whoever
   re-takes the row should report it rather than absorb it.
4. **~~The memory row.~~ DONE — and it did not help.**
   [`freehand-vs-reagent-memory.md`](freehand-vs-reagent-memory.md).
   Freehand costs 5.93× Reagent per sub-free boundary in standing heap
   and 4.19× per reactive one. What remains open is heap *under update*,
   where a fine-grained substrate could still come out ahead.

## Provenance

- Fixtures: W1 300 rows / 1,203 elements; W2 300 sub-free boundaries /
  301 elements; W3 12-field form / 51 elements; update grid 300 reactive
  cells / 301 elements.
- Versions: Reagent 2.0.1, UIx 1.4.4 (`uix.core/$`), React and react-dom
  19.2.0, ClojureScript via shadow-cljs 3.4.10.
- Mount sampling: 5 warm-up + 20 samples per arm per round, arms
  interleaved at the sample level with the order rotating on the sample
  index, 6 rounds.
- Update sampling: 4 warm-up + 12 samples per arm per round, 6 rounds;
  broad = 1 write a sample, narrow = 20.
- Determinism gates, green before any timing was read: canonical-DOM
  equality across all five arms at both sizes on all three witnesses;
  element counts against written arithmetic; and, per measured write, a
  DOM read-back of the written cell — **0 rejected of 384** in each
  update row.
- Build: `:advanced`, `goog.DEBUG false`, via `--config-merge` on the
  existing `:freehand-release` build id.
  `implementation/shadow-cljs.edn` is unchanged.
- Source, all of it shipped rather than deleted:
  `implementation/freehand/test/re_frame/freehand/bench/b6_witnesses.cljc`
  and `b6_witnesses_compiled.cljc` (Freehand, two lowerings),
  `b6_reagent.cljs`, `b6_uix.cljs`, `b6_floor.cljs`, `b6_harness.cljs`
  (canonical DOM, interleaving, floor normalisation), `b6_rows.cljs` (the
  arms and both measurements), `b6_mount_dom_cljs_test.cljs` and
  `b6_update_dom_cljs_test.cljs` (the deterministic gates, which ride the
  ordinary `npm run bench:freehand-browser` lane), and `b6_prod_app.cljs`
  + `b6_prod_run.cjs` (the `:advanced` reading).
- Reproduce:
  `node implementation/freehand/test/re_frame/freehand/bench/b6_prod_run.cjs`.
