# HD-008 — the composed donor arm, both rungs

The EP-0038 stop-gate, measured. HD-008 asks whether the programme's central
hypothesis survives when it is assembled **out of parts already in this
repository, before any API is designed**: reagent-slim's `:f>`
function-component path and its runtime hiccup interpreter for markup, the
existing UIx `use-subscribe` spine for reactivity.

Two rungs, because the two halves of the claim have to be priced apart.

| | what it adds | hooks per boundary |
|---|---|---|
| **Rung 1** | `:f>` boundaries, runtime hiccup, `use-subscribe` with the frame pinned as a literal | 1 |
| **Rung 2** | plus the **product shell** — one frame-context hook, and **native event-vector lowering** (`:on-click [:hd8/touch i]` lowered by the codec, not by the author) | 2 |

Bead **`rf2-2rtt6.7`**. Decision **[HD-008](../decisions.md)**. The standard is
**`rf2-2rtt6.1`**.

> **THE STOP/CONTINUE RULING IS NOT ISSUED HERE, AND NOT BY THIS PAGE.**
> Per HD-013 and HD-014 it is a *delegated advisory* ruling — one adversarial
> and one creative pass — issued **only against the published P0 baseline
> table**, recorded on `rf2-2rtt6.1`, and operator-overturnable. The red-zone
> thresholds it is judged against (*the measured UIx ratios per witness family,
> on clock and on retained heap*) are set when P0 publishes. This page is
> measurement. There is no verdict in it and there must not be.

---

## Provenance

| | |
|---|---|
| **Producing commit** | `d46ede4fb05a8f4c5af9900f0a010772f0b0883a` — every row except the `reagent-slim` write rows |
| **Producing commit, re-take** | `b943c7ed20d63d66fade4775059dad9fcf0012a7` — the `reagent-slim` write rows only (`rf2-b69lw`) |
| **Producing commit, re-take** | `d3f1c2fff6` on `worker/lane-control-cluster` — the **narrow write rows** only, on a batched window (`rf2-9zysg`) |
| **Producing instrument** | `hd8_rows.cljs` blob `e6bca24420b7fc4c9de2c6137f5b2f7144ad243d`, `lane.cljs` blob `671756751ecdb25c4c3d81e164c3204b022e93ae` |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs` — runs at every commit above |
| **Build** | `:hicasso-bench` (rf2-2rtt6.2's lane) — `:advanced`, `goog.DEBUG false` |
| **Runtime** | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), React 19.2.0, node v24.13.0 |
| **Rounds** | 6 · mount `{:warmup 4 :samples 12}` · write `{:warmup 3 :samples 10}` |

Every figure below is a **browser** figure, which is what HD-012 requires of
anything quotable against the bar.

**The narrow rows are anchored by BLOB HASH as well as by commit, and that is
not belt-and-braces.** The run was executed at `1c7c963e`; rebasing onto a main
that had moved rewrote it to `d3f1c2fff6`, and merging this branch will rewrite
it again. `git diff 1c7c963e d3f1c2fff6 -- implementation/` is empty, so the
instrument did not change — but a reader handed only a rewritten SHA cannot
check that. The two blob hashes above identify the exact instrument these
figures came off regardless of how the commit carrying it is rebased, and
`git rev-parse HEAD:implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs`
is how a reader confirms the checkout in front of them is the one that produced
this row.

**Three producing commits, and each later one re-took only the rows whose
window it changed.** The narrow write rows are the batched re-take's and are marked as
such in [their own section](#write--narrow-one-cell-in-a-300-cell-grid), where
the superseded unbatched ranges are kept rather than deleted; `rf2-9zysg`
batched ten writes under one clock and left every other window alone, so no
other row moved. Before that:

At `d46ede4f` the `reagent-slim` write rows read *78 of 78*
unverified and their figures were withheld. `rf2-z3vlz` diagnosed why and
`rf2-b69lw` repaired it; `b943c7ed` is that repair, and the `reagent-slim`
write rows below are its. **No other row was replaced.** The repair changes the
write window for arms on a **microtask-scheduled** substrate and leaves every
other arm's window byte-for-byte as it was, so the rows taken at `d46ede4f`
stand as published — see [the re-take](#the-re-take-rf2-b69lw) for what was
changed and the reproduction check that says the change was inert for them.

## The arms

Every arm reads **re-frame2 subscriptions** — the bar's like-for-like
condition. No arm reads a bare ratom. Every arm resolves its dispatch fn the
same way (one map lookup, primed outside render), so an arm that minted a fresh
ops map per render would not be carrying an allocation its rivals escape; the
frontier comparator in particular is never strawmanned.

| arm | hooks | markup | handler |
|---|---|---|---|
| `floor` | 0 | hand-written `createElement` | inert |
| `reagent` | 0 | Reagent hiccup (`reg-view`) | author closure |
| `reagent-slim` | 0 | slim hiccup (`reg-view`) | author closure |
| `uix` | 2 | `$` macro — resolved at compile time | author closure |
| `donor-r1` | 1 | slim hiccup + `:f>` | author closure |
| `donor-r2` | 2 | slim hiccup + `:f>` | **codec-lowered** |

So `donor-r2 / donor-r1` is the product shell's price and nothing else's;
`donor-r2 / uix` holds hooks and dispatch fixed and varies **the codec**;
`donor-r* / reagent*` is HD-008's ship comparison.

**The witnesses.** `M` — 300 rows, each a boundary with its own subscription
and its own handler (`3 + 3N` elements), markup-dominant. `U` — a 300-cell
grid of the same shape (`1 + N` elements), and the page the write rows drive.

## Three runs, and why

Spec 006 allows exactly **one installed adapter per process**. The two Reagent
paths need the ratom spine; the donor rungs need the React one. So the app runs
three times over one bundle.

A **mount is a one-shot read** — `use-subscribe` takes its first snapshot
correctly under either spine, and the canonical-DOM parity gate proves it — so
the mount rows carry every arm in every run and their donor-vs-Reagent
comparison lands **within one process**. Updates are where the spines part
company, so the write rows keep only the arms native to the installed adapter
and their donor comparison is made **through the floor**, which is a weaker
warrant and is labelled as one wherever it appears.

---

## Results

### Mount — the `M` page (300 rows, markup-dominant)

Every figure is a **range over 6 rounds**; a range including 1.0 means the two
arms are **indistinguishable** on this witness, and the mean is not quoted.
Within-run in every row.

| run | comparison | range |
|---|---|---|
| uix | `donor-r1 / uix` | 1.149 – 1.230 |
| uix | `donor-r2 / uix` | 1.162 – 1.286 |
| uix | **`donor-r2 / donor-r1` — the shell** | 1.012 – 1.049 |
| reagent | **`donor-r1 / reagent`** | 1.333 – 1.473 |
| reagent | **`donor-r2 / reagent`** | 1.353 – 1.460 |
| reagent | `donor-r1 / uix` | 1.184 – 1.267 |
| reagent | `donor-r2 / uix` | 1.209 – 1.250 |
| reagent | **`donor-r2 / donor-r1` — the shell** | 0.954 – 1.023 · *indistinguishable* |
| slim | **`donor-r1 / reagent-slim`** | 1.000 – 1.120 · *indistinguishable* |
| slim | **`donor-r2 / reagent-slim`** | 1.034 – 1.133 |
| slim | `donor-r1 / uix` | 1.086 – 1.184 |
| slim | `donor-r2 / uix` | 1.123 – 1.200 |
| slim | **`donor-r2 / donor-r1` — the shell** | 0.961 – 1.063 · *indistinguishable* |

Against the floor, for scale: `reagent` 3.500 – 3.895, `reagent-slim` 4.200 – 4.500, `uix` 3.889 – 4.111, `donor-r1` 4.500 – 4.790, `donor-r2` 4.667 – 5.000.

### Mount — the `U` page (300 cells)

| run | comparison | range |
|---|---|---|
| uix | `donor-r1 / uix` | 0.984 – 1.250 · *indistinguishable* |
| uix | `donor-r2 / uix` | 1.125 – 1.222 |
| uix | **`donor-r2 / donor-r1` — the shell** | 0.943 – 1.143 · *indistinguishable* |
| reagent | **`donor-r1 / reagent`** | 1.448 – 1.542 |
| reagent | **`donor-r2 / reagent`** | 1.250 – 1.542 |
| reagent | `donor-r1 / uix` | 1.125 – 1.333 |
| reagent | `donor-r2 / uix` | 1.078 – 1.299 |
| reagent | **`donor-r2 / donor-r1` — the shell** | 0.813 – 1.031 · *indistinguishable* |
| slim | **`donor-r1 / reagent-slim`** | 0.948 – 1.106 · *indistinguishable* |
| slim | **`donor-r2 / reagent-slim`** | 1.043 – 1.121 |
| slim | `donor-r1 / uix` | 1.028 – 1.364 |
| slim | `donor-r2 / uix` | 1.121 – 1.439 |
| slim | **`donor-r2 / donor-r1` — the shell** | 0.990 – 1.110 · *indistinguishable* |

Against the floor: `reagent` 4.800 – 6.700, `reagent-slim` 5.500 – 7.750, `uix` 5.400 – 6.400, `donor-r1` 6.300 – 7.300, `donor-r2` 6.600 – 7.200.

### Write — narrow (one cell in a 300-cell grid)

> **These figures were re-taken on a BATCHED window (`rf2-9zysg`)** — see
> [Provenance](#provenance) for the producing commit and the blob hashes that
> survive a rebase. Ten single-cell writes, to ten distinct cells, now share one
> clock; the per-write figure is the sample divided by ten. The ranges directly
> below **supersede** the ones taken on the unbatched window, which are kept at
> the end of this section so a reader can see the measurement was revisited
> rather than quietly replaced. Every other row on this page is unchanged and
> still carries its original producing commit — the batch touches the narrow
> window and nothing else.

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 0.947 – 1.056 · *indistinguishable* |
| `donor-r2 / uix` | 0.926 – 1.148 · *indistinguishable* |
| `donor-r2 / donor-r1` — the shell | 0.939 – 1.088 · *indistinguishable* |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 5.182 – 6.790
and `donor-r2` 5.400 – 6.579 against `reagent` 4.130 – 6.947 and
**`reagent-slim` 4.222 – 5.944**.

Absolute p50s, **per write** (the sample ÷ 10): floor 0.090 – 0.110 ms, `uix`
0.510 – 0.675, `donor-r1` 0.505 – 0.665, `donor-r2` 0.490 – 0.665; `reagent`
0.475 – 0.760 against its own floor 0.095 – 0.125; `reagent-slim` 0.380 – 0.535
against its own floor 0.090 – 0.095. **0 unverified of 780** on every arm of
every run.

**What the batch bought, and what it did not.** The denominator is no longer on
the clamp: the floor's *sample* p50 is 0.90 – 1.25 ms, nine to twelve quanta,
where the unbatched floor was 0.10 – 0.15 ms — one to one-and-a-half. That was
named as this instrument's weakest figure and it is now the same order of
resolution as everything else on the page. The ranges tightened where
quantisation was what made them wide (`reagent-slim` 2.667 – 6.000 → 4.222 –
5.944, a 2.25× spread down to 1.41×; `donor-r1` 5.000 – 8.000 → 5.182 – 6.790,
1.60× down to 1.31×). They did **not** tighten for `reagent` (1.67× → 1.68×),
where round-to-round drift, not the quantum, is what the range is made of.
Batching cannot help with drift and is not claimed to.

**The per-write absolutes reproduce the unbatched run**, which is the check that
says the batch measures the same operation: `reagent-slim` reads 0.380 – 0.535
ms per write here against 0.40 – 0.60 ms published, and its floor 0.090 – 0.095
against 0.10 – 0.15. The batched ratios sit *higher* than the unbatched ones for
the same reason — a denominator pinned at one quantum was rounded **up**, and a
floor rounded up understates every ratio taken against it.

<details><summary><b>Superseded — the same row on the unbatched window</b>
(`d46ede4f`, and `b943c7ed` for <code>reagent-slim</code>)</summary>

Within-run, `uix` run: `donor-r1 / uix` 0.909 – 1.200, `donor-r2 / uix`
0.960 – 1.200, `donor-r2 / donor-r1` 1.000 – 1.167 — all *indistinguishable*.

Cross-run, floor-normalised: `donor-r1` 5.000 – 8.000 and `donor-r2`
5.000 – 8.000 against `reagent` 3.000 – 5.000 and `reagent-slim` 2.667 – 6.000.
An independent slim-only replication at `f5bd4b49` read 3.000 – 5.500. Absolute
p50s: `reagent-slim` 0.40 – 0.60 ms, floor 0.10 – 0.15 ms. **0 unverified of
78.**

These were taken one write per clock, so both numerator and denominator carried
a 100 µs quantum. They are kept because a superseded measurement is evidence
about the instrument, and deleting it would hide that this row was ever
revisited.

</details>

### Write — bulk (all 300 cells in one commit)

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 1.185 – 1.313 |
| `donor-r2 / uix` | 1.278 – 1.469 |
| `donor-r2 / donor-r1` — the shell | 1.046 – 1.125 |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 7.750 – 11.000 and
`donor-r2` 8.250 – 11.750 against `reagent` 8.750 – 17.000 and **`reagent-slim`
11.000 – 13.667**. The independent slim-only replication at `f5bd4b49` read
9.500 – 13.000. Absolute p50s: `reagent-slim` 2.05 – 2.70 ms, floor
0.15 – 0.20 ms — four to eighteen quanta, so this row is far better resolved
than the narrow one beside it. **0 unverified of 78.**

### What the rungs cost

**Markup and reactivity (rung 1) is where essentially the whole cost sits.**

**The product shell (rung 2) is at or below this instrument's resolution.** One
frame-context hook per boundary plus codec-side event-vector lowering read
**indistinguishable from rung 1 on 6 of the 8 rows** above. Where it is
distinguishable at all it is small: mount-M (uix run) 1.012 – 1.049; write-bulk (uix run) 1.046 – 1.125. An earlier run of the same instrument
had it indistinguishable on every row, which is itself the finding — the shell
sits close enough to zero that whether it resolves depends on the round, and no
row shows it as a material cost.

---

## The gates, and what they caught

**Canonical-DOM parity** — every arm built the same page in all three runs, at
the stress size and at a small realistic size, compared with attribute names
sorted. The same comparison at two different sizes answers *false*, so the gate
is not passing vacuously.

**Positive control** — the floor arm building the `M` page at N and at N/2, the
two sizes interleaved as arms in one round. **Predicted 1.9934** from the
witness's own arithmetic `(3 + 3N) / (3 + 3(N/2))` at N = 300, before any clock
was read. Measured `1.800–2.000` (uix run), `1.750–2.000` (reagent run) and
`1.667–1.833` (slim run) — every round inside ±30%. The re-take predicted the
same `1.9934` before its own clock and measured `1.750–1.833`, `1.750–2.083`
and `1.714–1.846` — again every round inside the band, on the run that produced
the `reagent-slim` figures as much as on the two that did not.

**Event-vector lowering** — one click fired through rung 2's codec-lowered
handler, outside every measured window, read back out of the DOM:
`:before "0"` → `:after "T"`, with `:db-after "T"` beside it. Without this the
rung-2 clock could be pricing a lowering that produces a closure nobody can
call — the fastest possible implementation of the wrong thing.

**Arm-order guard** — every sample carries its predecessor **and its position in
the run**; the guard partitions on both and refuses any arm whose figure moves
with the plan. All **twelve** rows across the three runs came back
*reportable*, on both factors, with none refused — and the same twelve did on
the re-take. The two-arm write rows are the ones the `k = 2` degeneracy below
would have silenced, and they did not run in one order: each arm's 60 samples
split **30 / 30** across its two possible predecessors, which is the local
`slot-order` override working.

**DOM read-back — 0 unverified of 1,248**, which is every write the driver
executed across the three runs at `b943c7ed` (`0 of 960` counting only the timed
post-warmup samples). **On the batched re-take the narrow rows carry ten writes per
sample, so the same three runs execute 0 unverified of 6,864** — `0 of 780` on
each of the eight narrow arm-columns, plus the bulk rows' `0 of 78` each. The
denominator grew with the batch; the numerator did not.

At `d46ede4f` the same counts read **156 of 1,248** and **120 of 960**,
every one of them the `reagent-slim` arm, and its write figures were suppressed
rather than published. *An earlier version of this page reported that as "156
unverified of 936", which is not a like-for-like denominator and is corrected
here.*

### Five faults the instrument caught before they became numbers

1. **The floor ignored the witness's `n`** and built 300 rows for a 6-row
   witness. Caught by parity at the small size.
2. **The lowering check read the DOM synchronously after `.click`**, before
   re-frame's event queue had drained, and reported a working lowering as
   broken. It now yields, and reports `:db-after` beside `:after` so that a
   dispatch failure and a drain failure stop looking identical.
3. **The positive control measured its two sizes as consecutive blocks** and
   promptly read 0.42 in one round of three — the full page *faster* than the
   half page. A control measured as two blocks is subject to the very drift it
   exists to detect. The sizes are now interleaved arms in one round, and every
   round must sit inside the band rather than the range merely overlapping it.
4. **The arm-order guard refused four rows** — *"only 1 stratum, the question
   was never asked."* `slot-order` rotates by the sample index then reflects on
   odd indices, and **at k = 2 those two operations cancel** (a pair rotated by
   one *is* the pair reversed), so a two-arm plan runs in one order for ever.
   Three copies of that arithmetic carry the defect; filed as **`rf2-ouwh8`**
   and repaired locally, because sibling P0 arms were measuring on the shared
   copies at the time.
5. **The write window waited one fixed microtask for every arm**, and one
   fixed wait is not neutral across scheduler families. The `reagent-slim`
   write rows read **78 of 78** unverified on the first publication and were
   suppressed — which is the read-back doing its job, because unsuppressed
   they said `0.16–0.50×` the floor while the page never changed. The cause
   is below.

### Two findings that are not about the clock

**The React `use-subscribe` spine does not propagate over a ratom spine.** The
lowering check reported `:db-after "T"` with the DOM still at `"0"`: the click
dispatched, the event ran, `app-db` was written, and no view followed. No drain
fixes it — `ratom/flush!` settles the subscription graph and
`reagent.core/flush` renders the dirty components, and a
`useSyncExternalStore` subscriber watching a Reagent Reaction is notified by
neither. This bounds what *"composed from parts already in the repo"* can mean:
the composition cannot share a process with the thing it must beat.

**A benchmark harness cannot hold one wait for every substrate.** The
`reagent-slim` arm looked non-reactive and was in fact merely **late** — every
write landed, one macrotask after the window closed — because
`reagent2.impl.batching` schedules its render queue on the **microtask** queue
and the harness yielded a microtask between the write and the drain. Stock
Reagent survived the identical harness only because its queue is
`requestAnimationFrame`-scheduled and was therefore still full when the drain
arrived. `app-db` followed **every** write in every bundle, so the failure was
always on the view leg; and the positive control that reproduces it is a plain
`reagent2` component reading a plain `reagent2.core/atom`, with no frame, no
`subscribe` and no adapter hook, which puts **re-frame nowhere on the causal
path**. Diagnosed under **`rf2-z3vlz`**
([slim-non-reactive-arm-diagnosis.md](slim-non-reactive-arm-diagnosis.md)), and
neither the adapter nor the mixed bundle is implicated: four bundle
compositions from 96 to 114 compiled sources return byte-identical verdicts.
**No shipped code needs changing and no consumer is affected** — an application
does not write and then yield before flushing; the shipped reagent-slim adapter
smoke was green throughout, and it was right.

## The re-take (`rf2-b69lw`)

**What changed.** The write window's wait now belongs to the **arm** rather than
to the harness. `hd8-rows/arm-scheduler` names the queue each arm's own render
work is scheduled on — `:none` for the floor and for the React spine,
`:animation-frame` for stock Reagent, `:microtask` for reagent-slim — and
`window-of` gives a `:microtask` arm a window with **nothing between the write
and the drain**. Every other arm's window is the same three operations in the
same order as before, and the DOM read-back still runs in the same turn as the
drain, so a commit that arrives a turn late still reads as unverified rather
than as a pass.

**The suppressed figures were not rescued; they were re-taken.** They priced a
commit that had not happened, and the size of the correction says so: the narrow
write moved from `0.16–0.50×` the floor to `2.667–6.000×`, and the bulk write
to `11.000–13.667×`. A number roughly an order of magnitude below the truth is
what an unpaid commit looks like from inside a clock.

**Two windows do not bill the same wait, so the difference was measured.** The
`reagent-slim` window omits one harness microtask that the floor's contains.
`hd8-rows/yield-cost!` prices exactly that turn against the same clock, outside
every arm's window, and read **p50 0.0 ms, min 0.0, max 0.0 over 10 samples in
all three runs** — the harness microtask is below the 100 µs quantum, so the
asymmetry is beneath this instrument's resolution and nothing has to be
subtracted before the ratios above are read. Had it read anything else, this
page would owe the reader a subtraction.

**And since the narrow window now holds ten of those turns rather than one, ten
of them are priced under one clock as well** (`rf2-2rtt6.19`). That reading is
also **0.0 ms**, which bounds the whole ten-turn asymmetry at `< 100 µs` instead
of at ten times a sub-quantum reading — see [The ten-turn asymmetry is measured,
not multiplied](#the-ten-turn-asymmetry-is-measured-not-multiplied).

### The ten-turn asymmetry is measured, not multiplied

**The arithmetic that was wrong.** After the batched re-take, this page bounded
its own microtask asymmetry as *"ten times a quantity below the instrument's own
resolution"*. That does not follow, and it is the same argument the batch was
adopted to make: `hd8-rows/yield-cost!` read `p50 0.0, min 0.0, max 0.0` over
`n = 10`, which against Chrome's 100 µs clamp bounds **one** turn at `< 100 µs`.
It bounds **ten** turns at `< 1.0 ms` — up to **~26%** of a 3.8–7.6 ms batched
narrow sample. And the term is present in three columns and absent from the
fourth:

| arm | batched narrow, × floor | harness microtasks in the window |
|---|---|---|
| `reagent-slim` | 4.222 – 5.944 | **zero** |
| `donor-r1` | 5.182 – 6.790 | ten |
| `donor-r2` | 5.400 – 6.579 | ten |
| `reagent` | 4.130 – 6.947 | ten |

An unpriced term present in three columns and absent from the one they are read
against is exactly the shape of thing that decides whether `reagent-slim` reads
faster than the donor rungs — and HD-008 is the donor gate, so that comparison
is the point of the row.

**The repair is this page's own technique, applied to the control.**
`yield-cost!` now prices **ten harness microtasks inside one clock window**, the
way the narrow row prices ten writes, and reports the per-turn figure as the
sample divided by ten. The recursion mirrors `window-of`'s non-microtask branch
exactly — `(js/Promise.resolve nil)` with the continuation inside the `.then`,
so the next turn begins in the turn the previous one finished in — because
threading the turns through a promise-returning `.then` would add two resolution
ticks per step and price a window no arm runs. The two readings are taken
**sequentially, never through `Promise.all`**: two microtask chains in flight on
one queue would each be timing the other's turns.

**The measurement, all three runs:**

| reading | window | per turn | n |
|---|---|---|---|
| one turn (unchanged, as published) | **0.0 ms** | 0.0 ms | 10 |
| **ten turns under one clock** | **0.0 ms** | **0.0 ms** | 10 |

**Ten turns together still measure 0.0 ms against a 100 µs quantum.** So the
asymmetry is bounded at `< 100 µs` for the whole batch — not at `< 1.0 ms` — and
per turn at `< 10 µs`. Against the narrow row's 3.8–7.6 ms samples that is **at
most 2.6% of the smallest sample and 1.3% of the largest**, where the sentence
this replaces left a reader facing up to 26%.

**Nothing is subtracted, and now that is a finding rather than an assumption.**
The bound is ten times tighter than the one it replaces, and it is the bound the
batched window actually needs. Had the ten-turn window read anything above the
clamp, this page would have owed the reader the subtraction the old sentence
quietly assumed away.

**No published row moves.** The control is measured outside every arm's window,
the one-turn reading is unchanged and still reported, and no arm's window was
touched — so the ratios above are the ratios above.

**Second-order, worth stating in the same pass.** The same reasoning applies
wherever a sub-quantum per-item cost is asserted negligible across a batch.
`lane/verified-write!`'s `:gap-ms` is priced per window; if a future arm batches
*k* operations under one clock on the shared lane, the same multiplication
reappears there and wants the same treatment.

**Reproduction check — NOT a republication.** The re-take ran all three
adapters, so the rows published at `d46ede4f` were measured again by a driver
carrying the change. Every one of them reproduced: on the mount rows, which are
the well-resolved ones, `donor-r1 / uix` in the `uix` run read `1.150 – 1.233`
against the published `1.149 – 1.230`. On the write rows five of the six
published cross-run ranges are **contained in** the re-take's and the sixth
overlaps it (narrow `donor-r1` `4.000 – 8.000` ⊃ `5.000 – 8.000`; narrow
`reagent` `2.500 – 7.000` ⊃ `3.000 – 5.000`; bulk `donor-r1` `6.000 – 12.000`
⊃ `7.750 – 11.000`; bulk `reagent` `9.750 – 19.000` overlapping
`8.750 – 17.000`). The re-take's write ranges are **wider** — four sibling
workers were live on the host — which is why they are cited as a check and not
as a replacement. **No figure moved systematically, and nothing published was
invalidated.** `HD8_ONLY` exists so that a future re-take of one run cannot mint
a competing set of figures for rows published at another commit.

**Where the general fix belongs, and where it now lives.** The same fixed yield
lived in the shared `lane/verified-write!`, which every Hicasso arm uses, and
the general repair — an arm-declared scheduler, additive, today's path unchanged
when it is absent — is **`rf2-pq7d8`**. It was not made in the same pass as this
page's, because `lane.cljs` is a shared instrument with sibling arms measuring
on it and a shared instrument must not change under a measurement in flight;
HD-008's own `timed-write!` is a separate copy, which is why this repair could
land first without touching it. **`rf2-pq7d8` has since landed.** The lane now
takes the same `:scheduler` declaration this page's `window-of` takes and gives
it the same two shapes — lifted from here rather than invented a second time —
and an arm that declares no `:scheduler` gets the lane's unchanged window. No
arm outside HD-008 declares one, so **no published row moved.**

## Known limitations of this instrument

- ~~**The narrow-write row sits near Chrome's `performance.now()` clamp.**~~
  **Repaired by the batched re-take (`rf2-9zysg`).** Ten writes now share one clock, so
  the row's samples are 3.8–7.6 ms and its floor 0.90–1.25 ms, against a 100 µs
  quantum. What remains is that an early write in a batch is read back up to
  nine microtask turns after its own drain — the pre-batch window already
  granted one such turn by construction, and a commit React parks at the default
  lane needs a *macrotask*, which never occurs inside the window, so the
  read-back's actual target is unaffected. The batched window also contains ten
  harness microtasks for every arm except the microtask-scheduled one, which
  contains none. ~~Each such turn measures 0.0 ms against this clock, so the
  asymmetry is ten times a quantity below the instrument's own resolution.~~
  **That sentence was wrong, and `rf2-2rtt6.19` replaced the multiplication with
  a measurement — see [The ten-turn asymmetry is measured, not
  multiplied](#the-ten-turn-asymmetry-is-measured-not-multiplied).** Ten times a
  quantity below resolution is *not* below resolution: it bounds ten turns at
  `< 1.0 ms`, which is up to ~26% of a 3.8–7.6 ms sample.
- **The bulk write verifies one probe cell**, where the shared lane verifies a
  seq including the far end of the grid. Same bead.
- **The write rows' donor-vs-Reagent comparison is cross-run**, floor-normalised.
  The mount rows' is not. **The `reagent-slim` write column is normalised through
  the floor of a run taken at a different commit** from the donor and `reagent`
  columns beside it, which is weaker again; the reproduction check above is what
  that reading rests on.
- **Two window shapes now exist**, and only one of them contains the harness
  microtask. Measured at 0.0 ms against a 100 µs quantum **one turn at a time and
  ten turns at a time** (`rf2-2rtt6.19`), so the whole batched asymmetry is
  beneath this instrument's resolution rather than ten times something beneath it
  — but a future instrument with a finer clock inherits a real asymmetry, not a
  settled one, and `rf2-pq7d8` has since carried both shapes into the shared
  lane, which inherits that asymmetry rather than settling it.
- **No retained-heap leg.** The red-zone rule governs clock *and* retained heap;
  this arm measures the clock. The heap ladder is `rf2-2rtt6.5`'s
  ([reads-per-boundary-heap-ladder.md](reads-per-boundary-heap-ladder.md)).
