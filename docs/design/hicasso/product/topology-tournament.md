# The topology tournament — pre-registration, then the rung-2 teaching table

Owned by `rf2-hic-036`. This page is written in two parts and they landed in
that order, which is the only reason the second part is worth reading.

**Part 1 is the pre-registration.** It fixes the arms, the operations, the row
counts, the estimand, the controls, and — the clause the bead singles out — the
number of tuning iterations a red cell is allowed before it stops or narrows.
It was committed **before a single measurement was taken**, on a tree based at
`101142e395a15a7d278dda9effacc24e3f556124`, which is an ancestor of `main`.

**Part 2 is the result**, added by later commits on the same branch. Where it
records a refusal, that refusal is the deliverable and is not a failed attempt
at a number.

The tournament is one of the bounded [decisive
experiments](lanes/hot-path-architecture.md#decisive-experiments), and
[the decision brief](decision-brief.md#part-iii--the-plan) closes that set with
*"no open-ended benchmark programme after it"*. This page is therefore allowed
to end in *unresolved*; it is not allowed to end in *to be continued*.

---

## Part 1 — the pre-registration

### 1.1 The arms

Four read topologies over one row family, taken from
[the read-topology guidance](lanes/hot-path-architecture.md#read-topology-guidance).
Every arm renders the **same rows, the same markup and the same DOM**; only the
placement of boundaries and reads differs.

| arm | boundary placement | reads | what it is buying |
|---|---|---|---|
| `fine` | one boundary per row | each row reads its own cells | sparse invalidation, paid for in mount and heap |
| `coarse` | one boundary for the whole family | the family boundary reads one view-model | cheap mount and cheap bulk replacement |
| `chunked` | one boundary per chunk of `k` rows | each chunk reads its chunk's slice | the useful middle for mixed workloads |
| `virtual` | one boundary per **visible** row, windowed | visible rows only | fewer rows exist in the DOM at all |

`k` is **not derived from a model**. [The counterfactual
worksheet](counterfactual-topology-prediction.md#what-is-refused-rather-than-predicted)
refuses to pin a chunk width because the committed evidence carries no value for
`c` (the cost of one row of markup over the cost of one membership slot), and
this page does not invent one. `k` is pinned here as a **stated constant, 25**,
chosen before any run and held fixed across every row count and operation. It is
a setting of the arm, not a result.

### 1.2 The operations

| operation | what the event does | class |
|---|---|---|
| `sparse` | change one field of **one** row | narrow |
| `bulk` | replace **every** row's data in one commit | broad |
| `reorder` | permute the row order, same identities and same keys | broad |
| `edit` | one controlled-input keystroke inside one row | narrow |

### 1.3 The row counts

`B ∈ {100, 300, 1000}`, and `R = 2` reads per row in the `fine` arm — the same
shape [the counterfactual
worksheet](counterfactual-topology-prediction.md#1-membership-savings) holds
fixed while it moves `B`. That worksheet's final caution is repeated here
because this page is the thing it cautioned about: if the tournament's table
reads more than two keys per row, every `B·R` figure on that page is low. It
reads exactly two.

### 1.4 The estimand, and the one substitution that is refused

**The primary estimand is the clock**: main-thread task duration for the
commit the operation causes, frame-settled, on P-DEV-1. That is the quantity
[C3 and U3](budgets.md#the-comparative-and-regression-rules) are stated on, and
it is what "arm A beats arm B" means to a user.

A **deterministic work census** is taken on the same operations — boundary
bodies run, rows of markup rendered, and read edges touched. It is machine
insensitive: [§2 of the budgets page](budgets.md#2-the-two-disposition-families-which-6-already-separates)
establishes that a counter reads the same on a loaded box, so this half needed
no quiet window and could have been taken at any time.

**The two are never pooled, and the census never stands in for the clock.**
Pre-registered, so it cannot be softened later: if an arm's clock control
refuses, that cell is **unresolved on the clock**, and its work census is
published as a separate row that is explicitly *not* an ordering of the arms by
speed. [`rf2-hic-080`'s frozen scoring
rule](counterfactual-topology-prediction.md#scoring-rule-frozen-with-the-predictions)
already names the correct outcome for that case — *"a prediction the
tournament's design cannot address is scored unaddressed, never right"* — and
this page hands such cells over as **unaddressed** rather than as a work-census
ordering wearing a clock's clothes.

### 1.5 The controls, and what each refuses

Each arm carries both. A published clock figure requires **both** to pass for
that arm at that row count; either failing withholds the figure.

**Positive control — the rendered-scale doubling** (`rf2-m6i0`; it replaces the
changed-set doubling registered here first, which was run, refused, and is
recorded with its refusal in [§2.6](#26-the-clock-half--the-first-control-refused-the-replacement-certifies-three-arms-of-four)).
The arm's **rendered page** is doubled and every 5th rendered row is written by
index, so the event handler, the subscription layer and the render all scale
together. The predicted factor is **derived from `model/elements-for`** at the
two rendered row counts rather than assumed to be 2.00 — the `ul` does not
double — and it is adjudicated on the lane's standing `CONTROL_SLACK` of ±25%
under the strict rule that **every** round must sit inside:

| arm | scaled | small → large | predicted | band |
|---|---|---|---|---|
| `fine` | `B` | 500 → 1000 | 1.9996 | [1.4997, 2.4995] |
| `coarse` | `B` | 500 → 1000 | 1.9996 | [1.4997, 2.4995] |
| `chunked` | `B` | 500 → 1000 | 1.9996 | [1.4997, 2.4995] |
| `virtual` | `w` | 20 → 40 | 1.9901 | [1.4926, 2.4876] |

The measured ratio must also be **positive** in every round (a control
certifying that more work reads *faster* is refused on the sign, which is the
fix `rf2-7iqb5` landed in PR #7634), and the run must read back **0 unverified
of M** cell-addressed probes, or the clock measured a page that did not commit.

**Why the scaled quantity is `rendered-rows` and not `B`.** Doubling `B` on the
windowed arm moves nothing it renders, so that arm's page is its *window* and
the window is what doubles. This is the mirror image of the fault that refused
the changed-set control, and it is why no single scaled quantity serves all
four arms.

**Sabotage control — the topology witness must bite.** The deterministic census
is itself the sabotage detector for the arms: an arm is planted with a defect
that makes it behave as a different topology (the `fine` arm made to read a
cell every row shares, so a one-row change invalidates all `B`), and the census
must **red**. A census that stays green under the plant proves nothing about
the arms it certifies, and no clock figure guarded by it may publish.

### 1.6 The kill rule, and the iteration count — pre-registered

Registered before any measurement, per
[the decision brief's kill rules](decision-brief.md#part-iii--the-plan)
(*"Bulk above its kill line after the allowed iterations stops or narrows"*):

> **Two tuning iterations per red cell. Not three, and not "until it goes
> green".**

A *tuning iteration* is one bounded change to the arm's topology — chunk width,
key scheme, memo placement, view-model shape — re-measured on the same
instrument. It is **not** a change to the instrument, the band, the control, or
the kill line. Changing any of those is not a tuning iteration; it is a
different tournament.

The **kill lines**, taken from the ratified budgets rather than chosen here:

| line | source | applies to |
|---|---|---|
| broad operation within **100 ms p95** | [U3](budgets.md#the-6-user-visible-budgets) | `bulk`, `reorder` |
| discrete interaction to next paint within **50 ms p95** | [U2](budgets.md#the-6-user-visible-budgets) | `sparse`, `edit` |
| broad update ≤ **1.25x** the best arm in the same cell | [C3](budgets.md#the-comparative-and-regression-rules) | `bulk`, `reorder` |
| sustained > **1.5x** | [C4](budgets.md#the-comparative-and-regression-rules) | all |

A cell still above its line after two iterations takes one of exactly two
dispositions, written into the table and never left open:

- **STOP** — that topology is not offered for that workload at that row count.
- **NARROW** — the use-case classification shrinks to the row counts where it
  passes, stated as a bound rather than as a hope.

There is no third disposition, and no cell carries "needs more investigation".

### 1.7 What this page will not do

- It will not widen a band, a kill line or a tolerance to admit a run. Every
  gate here exists because something once passed that should not have.
- It will not add an instrument, a rung or an estimator mid-series. An
  improvement discovered during the run is filed as its own bead and run as its
  own window; a rung added between runs makes the series two instruments.
- It will not restate a published figure on thin evidence. S1–S8 in
  [the budgets page](budgets.md#4-distributional-rows--s1s5-re-pinned-on-the-package-s6s7-carried)
  are not this page's to move.
- It will not score `rf2-hic-080`'s predictions. That is that bead's phase 2,
  deliberately held by a different worker, and a tournament that graded its own
  blinded predictor would destroy the property the split exists to protect.

---

## Part 2 — the result

Two halves were attempted and they came back differently, which is the honest
headline: **the deterministic half is complete at all 48 cells and publishes;
the clock half still publishes no cell, and the reason is a control and a
missing driver, not a judgement about the machine.**

That second clause has moved once since this page was first published, and
only part of the way. `rf2-m6i0` replaced the refused control with one that
**passes on three of the four arms** ([§2.6](#26-the-clock-half--the-first-control-refused-the-replacement-certifies-three-arms-of-four)),
so *want of a control* no longer withholds `fine`, `coarse` and `chunked`.
What withholds those three cells now is narrower and more ordinary: **the
tournament's clock cells were never instrumented.** Only the control was built,
it refused first, and no driver for the 4 arms × 4 operations table exists to
run.

The fourth arm is withheld for a different and now settled reason. `rf2-4t36`
ruled on `virtual`'s refusal without taking a new measurement: at the window
this tournament commits to, the control has **no discriminating power on that
arm in either direction**, so its clock cells are **unaddressed** rather than
pending an instrument. The arithmetic and the two rejected repairs are in
[§2.6](#26-the-clock-half--the-first-control-refused-the-replacement-certifies-three-arms-of-four).

### 2.1 What ran, and on what

| | |
|---|---|
| instrument | `implementation/freehand/test/re_frame/bench/hicasso/topo/census_dom_cljs_test.cljs` |
| substrate | the bench's **arm-1** runtime, not `implementation/hicasso` |
| witness | `npm run test:browser` (Chromium, real DOM), 1,507 tests / 9,533 assertions, 0 failures / 0 errors |
| clock control | `topo/control_app.cljs` via the lane's generic `run.cjs` — the changed-set form **refused**; its rendered-scale replacement **certifies `fine`, `coarse` and `chunked`, and refuses `virtual`**; see §2.6 |
| profile | P-DEV-1 |
| box | `\System\Processor Queue Length` read **0.00** on every sample taken before, during and after both runs |

**The substrate limitation is stated rather than buried.** Every figure below
is an arm-1 figure and carries no `implementation/hicasso` generalisation. That
is the same discipline [the budgets page](budgets.md#4-distributional-rows--s1s5-re-pinned-on-the-package-s6s7-carried)
applies to its own rows when it labels S6 and S7 *bench-tree figures* beside
S1–S5's *package figures*, and it is not softened here.

**The box did not have to be quiet for this half, and saying so is the point.**
Body runs and rows of markup are integers on monotone counters;
[§2 of the budgets page](budgets.md#2-the-two-disposition-families-which-6-already-separates)
establishes that a counter reads the same on a loaded box. The census is
therefore an ordinary PR gate that anyone can re-run, which is exactly what a
one-shot clock session on one machine is not.

### 2.2 The rung-2 teaching table — rows of markup built

The quantity that separates the arms. One row of markup is one row's worth of
element construction; a body that builds none did not run, or ran and bailed.

| operation | `B` | `fine` | `coarse` | `chunked` (k=25) | `virtual` (W=20) |
|---|---|---|---|---|---|
| sparse | 100 | **1** | 100 | 25 | **1** |
| sparse | 300 | **1** | 300 | 25 | **1** |
| sparse | 1000 | **1** | 1000 | 25 | **1** |
| bulk | 100 | 100 | 100 | 100 | **20** |
| bulk | 300 | 300 | 300 | 300 | **20** |
| bulk | 1000 | 1000 | 1000 | 1000 | **20** |
| reorder | 100 | **0** | 100 | 100 | 1 |
| reorder | 300 | **0** | 300 | 300 | 1 |
| reorder | 1000 | **0** | 1000 | 1000 | 1 |
| edit | 100 | **1** | 100 | 25 | **1** |
| edit | 300 | **1** | 300 | 25 | **1** |
| edit | 1000 | **1** | 1000 | 25 | **1** |

### 2.3 The same table, boundary bodies run

Reported beside it and never instead of it, because the two answer different
questions and a reader given only one of them draws the wrong conclusion about
bulk.

| operation | `B` | `fine` | `coarse` | `chunked` | `virtual` |
|---|---|---|---|---|---|
| sparse | any | 1 | 1 | 1 | 1 |
| bulk | 100 | 100 | **1** | 4 | 20 |
| bulk | 300 | 300 | **1** | 12 | 20 |
| bulk | 1000 | 1000 | **1** | 40 | 20 |
| reorder | 100 | 1 | 1 | 4 | 2 |
| reorder | 300 | 1 | 1 | 12 | 2 |
| reorder | 1000 | 1 | 1 | 40 | 2 |
| edit | any | 1 | 1 | 1 | 1 |

Both counters agree with `arm1.runtime/body-runs`, which is incremented inside
`run-once` and shares no traversal with the census's own. 48 of 48 cells agree.

### 2.4 The four findings

**1. Sparse and edit separate the arms linearly in `B`, and the separation is
not a clock artefact.** `fine` builds one row for a one-row change at every row
count; `coarse` builds all `B`. At `B = 1000` that is a factor of 1,000, on an
exact integer counter, with no interval attached because none is needed.

**2. Bulk does not separate `fine`, `coarse` and `chunked` on markup at all** —
all three build exactly `B`. This is the finding most likely to be misread.
Whatever separates those three arms on a bulk commit is **not** the number of
rows built; it is per-boundary overhead, which at `B = 1000` is 1,000 boundary
shells against one. The shell is a registered, breached line — `1,100 B` and
`1,095 B` against a frozen `1,024 B`
([S1/S2](budgets.md#4-distributional-rows--s1s5-re-pinned-on-the-package-s6s7-carried)) —
so the bulk question routes into `rf2-hic-018`'s open disposition rather than
being answerable here.

**3. Reorder is where the arms genuinely invert, and `chunked` is the worst of
them.** A permutation moves no row's read. Under `fine` the list body re-runs,
every row's props are unchanged, the memo bails, and **zero rows of markup are
built for a table that visibly reorders** — React moves DOM nodes and runs no
row body. Under `coarse` all `B` rows are rebuilt. Under `chunked` all `B` rows
are rebuilt **and** `⌈B/k⌉` bodies run, because a rotation changes the contents
of every chunk: at `B = 1000` that is 1,000 rows and 40 bodies, strictly worse
than both neighbours on both counters.

**4. `virtual` is the only arm whose cost does not scale with `B` on any
operation** — 20 rows on a bulk commit at every row count, 1 elsewhere. It is
also the only arm whose figures are about a different page, and the two facts
are the same fact.

### 2.5 U5, and an instrument gap in it that this tournament exposed

One registered budget line is deterministic, so the census can speak to it:

> **U5** — Narrow-update body work scales with changed rows, not all mounted
> rows. ([budgets §4](budgets.md#the-6-user-visible-budgets), family:
> **deterministic**, estimand **boundary bodies run**, pinned as D1–D4)

**Read against its registered estimand, every arm passes — including the one
that plainly should not.** A narrow update runs exactly one boundary body in
all four arms:

| arm | bodies on a narrow update | rows of markup built | U5 as registered |
|---|---|---|---|
| `fine` | 1 | 1 | passes |
| `virtual` | 1 | 1 | passes |
| `chunked` | 1 | 25 | passes |
| `coarse` | 1 | **`B`** | **passes** |

The coarse arm rebuilds a thousand rows for a one-row change and satisfies a
budget whose English forbids exactly that, because the budget counts *bodies*
and the arm does its thousand rows **inside one body**.

**This is not a quibble; it is a hole with a known shape.** D3/D4 exist
precisely to catch a coarse topology — *"coarse shape with closure props moves
it to 26/101"* — and they catch it because that witness's coarse shape still
holds per-cell boundaries. **A coarse shape that inlines its rows has no
boundaries to count, so the same instrument reads 1 and reports a pass.** The
tournament's coarse arm is that shape, and it is the ordinary spelling of a
view-model arm rather than an exotic one.

The honest verdict is therefore split, and neither half is allowed to stand in
for the other:

- **On U5 as registered: no breach, in any arm.** This page does not report one.
- **On the property U5 is written to protect: `coarse` fails at every row
  count**, on an exact integer counter, and untunably — rebuilding the family
  from one view-model *is* the arm.

Closing that gap is `rf2-hic-071`/`rf2-hic-089`'s, since they own turning these
rows into gates, and it is filed rather than patched here.

### 2.6 The clock half — the first control refused; the replacement certifies three arms of four

**No clock cell is published, and two different things are responsible at two
different times.** The control registered first was built, run on a
verified-quiet box, and REFUSED. Its replacement (`rf2-m6i0`) was built, run on
a verified-quiet box, and **passes on `fine`, `coarse` and `chunked` while
refusing `virtual`**. Both are measurements, not opinions about the machine.

#### The first control's run — the changed-set doubling

`topo/control_app.cljs`, driven by the lane's generic `run.cjs`, Chromium
147.0.7727.15 headless, `:advanced`, `goog.DEBUG false`, 24 threads / 32 GB.
`\System\Processor Queue Length` read **0.00** on all five samples immediately
before the run. Page structure was checked before the clock and matched its
arithmetic exactly: **1,001 boundaries, 2,001 read edges, 5,001 elements.**
`B = 1000`, `fine` arm, 20 commits under one clock, 12 samples × 5 rounds,
arms interleaved in `slot-order`.

| round | `d10` p50 (ms/20 commits) | `d5` p50 | ratio |
|---|---|---|---|
| 1 | 44.80 | 59.65 | 1.331 |
| 2 | 44.35 | 61.50 | 1.387 |
| 3 | 44.45 | 63.30 | 1.424 |
| 4 | 44.50 | 65.45 | 1.471 |
| 5 | 48.35 | 64.05 | 1.325 |

**Verdict: `:ok? false` — REFUSED ON THE BAND.** Predicted 2.00, registered
band [1.60, 2.50], measured 1.325–1.471. Exit code **1**, captured from the
runner itself.

#### Why this refusal is worth more than a green one would have been

Everything that could have made it dismissible held. The **arm-order guard
passed** — *"no arm reads differently for its position in the plan"*, both by
predecessor and by phase, within 10%. The ranges are tight, the five rounds
agree to within 0.15, and the sign is right in every round. **This is not
noise; it is a strong, stable signal that is not 2.00.**

So the model behind the prediction is wrong, and the census says where. The
window contains three costs and only one of them doubles: the event handler
`reduce-kv`s the whole thousand-row table at both strides, the subscription
layer re-evaluates all 1,000 `[:topo/row i]` cells at both strides, and only
the 100→200 rows of markup actually double. **A large constant term is shared
between the arms, so the ratio is compressed toward 1** — which is precisely
the failure `rf2-7iqb5` recorded for page-scaling controls, reappearing in the
control built to replace them.

**The band is not widened to 1.32, and the prediction is not re-derived now
that the data is in.** Either move would retro-admit every run this gate was
built to catch. The cell refuses.

#### And the control could not have covered two arms anyway

Independent of the run, and derived from the census's own integers rather than
from any clock: **the control is degenerate on two of the four arms.**

| arm | markup at stride 10 | at stride 5 | predicted factor |
|---|---|---|---|
| `fine` | `B/10` | `B/5` | **2.00 — discriminating** |
| `virtual` | 2 | 4 | **2.00 — discriminating** |
| `coarse` | `B` | `B` | **1.00 — degenerate** |
| `chunked` | `B` | `B` | **1.00 — degenerate** |

`coarse` and `chunked` rebuild every row whichever stride runs, so doubling the
changed set doubles nothing they do. A control predicting 2.00x on those arms
would refuse a healthy instrument; a control predicting 1.00x on them
discriminates nothing and certifies nothing. **Either way those two arms had no
positive control at all**, and since every interesting comparison in this
tournament crosses between a fine-family arm and a coarse-family one, that
withheld the clock table entire.

That table is measured rather than argued, and it stays measured:
`topo/control_witness_dom_cljs_test.cljs` asserts it as exact integers on every
PR, so the degeneracy above cannot quietly stop being true.

#### The replacement — the rendered-scale doubling (`rf2-m6i0`)

Same lane, same driver, same box discipline: Chromium 147.0.7727.15 headless,
`:advanced`, `goog.DEBUG false`, 24 threads / 32 GB, `\System\Processor Queue
Length` **0.00** on every sample before, during and after. 20 commits under one
clock, 12 samples × 5 rounds, the two page sizes interleaved in `slot-order`,
**the arm-order guard `[ok] by predecessor` on all four arms**, and **0
unverified of 300** probes on all four.

Each arm's page was checked against its own arithmetic before its clock started,
and each matched exactly — including the rows-of-markup asymmetry the prediction
rests on, which is where the changed-set control was degenerate:

| arm | boundaries | read edges | elements | markup built per commit |
|---|---|---|---|---|
| `fine` | 501 → 1001 | 1001 → 2001 | 2501 → 5001 | 100 → 200 |
| `coarse` | 1 → 1 | 1 → 1 | 2501 → 5001 | 500 → 1000 |
| `chunked` | 21 → 41 | 21 → 41 | 2501 → 5001 | 500 → 1000 |
| `virtual` | 21 → 41 | 41 → 81 | 101 → 201 | 4 → 8 |

| arm | predicted | band | round ratios | verdict |
|---|---|---|---|---|
| `fine` | 1.9996 | [1.4997, 2.4995] | 1.9331 / 2.0296 / 2.0856 / 2.2588 / 2.0972 | **PASS** |
| `coarse` | 1.9996 | [1.4997, 2.4995] | 2.1029 / 2.0024 / 2.0459 / 1.9853 / 2.0877 | **PASS** |
| `chunked` | 1.9996 | [1.4997, 2.4995] | 2.0236 / 2.0816 / 2.1415 / 2.1876 / 2.1802 | **PASS** |
| `virtual` | 1.9901 | [1.4926, 2.4876] | 1.5490 / 1.5577 / 1.5200 / 1.4600 / 1.5111 | **REFUSED ON THE BAND** |

Exit code **1**, captured from the runner itself — a control that refuses any
arm refuses the run.

**The diagnosis of the first refusal is confirmed directly.**
`fine` is the one arm both controls could address, and it moved from 1.325–1.471
under the changed-set form to 1.9331–2.2588 under this one. The only thing that
changed is that the handler and the subscription layer now scale with the
manipulation instead of standing still. That is the shared constant term being
removed, watched directly.

**Why `virtual` refuses, and why it is not the same fault.** Its ratios are
tight (1.46–1.5577 across five rounds), correctly signed, guard-clean and
fully verified — a stable signal that is not 1.99, exactly as the first refusal
was. Its *work* doubles exactly (4 → 8 rows of markup, 21 → 41 boundaries,
41 → 81 read edges), so the prediction is not a model error. The cause is a
**per-commit floor** — dispatch, the `flushSync` boundary, the commit React
schedules regardless — which does not double, and which against a whole commit
of **0.125 ms** at `w = 20` and **0.195 ms** at `w = 40` is close to half the
reading.

**It is not the `performance.now()` clamp**, and `rf2-4t36` corrects this page
on that point. `batch-k` is 20, so the clock is read once per twenty commits —
`topo/control_app.cljs`'s `window!` takes one `now-ms` pair around the whole
batch — and the readings are **2.50 ms** and **3.90 ms**, twenty-five and
thirty-nine of Chrome's 100 µs quanta. Quantisation can move a reading by at
most one quantum: ≤ 4.0% of the small window and ≤ 2.6% of the large, against a
measured compression of 24%. The batch had already lifted the window clear of
the clamp. What a batch cannot lift is a cost paid *per commit*, because
batching scales that with everything else.

**Nothing is widened and nothing is re-derived.** `virtual`'s band was
registered before the run, from the same `elements-for` arithmetic as the other
three, and it stands. What to do about the refusal was filed rather than
attempted here — adding a rung between runs would make the series two
instruments — and it is ruled on next.

#### The ruling on the windowed arm (`rf2-4t36`) — its clock cells are UNADDRESSED

`rf2-m6i0` filed three candidate repairs and attempted none, which was correct:
improving a rig mid-window makes the series two instruments. They are ruled on
here, from the arithmetic of the run above and from this page's own
pre-registration. **The ruling is the third. The windowed arm's clock cells are
not resolvable on this instrument at the window this tournament commits to, and
they are handed to `rf2-hic-080` phase 2 as unaddressed** under
[§1.4](#14-the-estimand-and-the-one-substitution-that-is-refused)'s disposition
for exactly this case. No new measurement was taken and none is owed.

**The finding is not the refusal; it is that this control has no discriminating
power on this arm, in either direction.** Fit the lane's own additive constant —
`census_clock_run.cjs`'s `c = tared × (P − R) / (P − 1)` — to the run above and
it gives **c ≈ 0.059 ms against a 0.125 ms commit, 47% of the reading**. (The
straight two-point fit on the rounded per-commit figures gives 0.055 ms, 44%;
the two agree to rounding.) An affine cost of that shape predicts a ratio of
**1.52**, which is what was measured — and 1.52 sits just **1.8% above the band
floor of 1.4926**, or **0.70** of the run's own round-to-round standard
deviation of 0.0385. Under the strict every-round rule over five rounds, a
perfectly healthy instrument on a perfectly quiet box therefore certifies this
arm about **one run in four**. A control that refuses three healthy runs in four
is not adjudicating the instrument, it is adjudicating noise — and a PASS from
it would have been exactly as uninformative as this refusal.

**Candidate 1, a larger window pair, is rejected because it certifies the wrong
regime.** The margin is genuinely recoverable: with the floor above and a
per-row scaling cost of 0.0033–0.0035 ms, the expected ratio reaches 1.75 at
`w ≈ 50` and comes within 5% of its prediction at `w ≈ 160–180`, so `w = 100 →
200` would pass comfortably. But this tournament's committed window is **20**,
and the cells the control exists to license are taken there. At `w = 100` the
floor is about 15% of the reading; at `w = 20` it stays at 47%. That is a green
light issued in one regime over cells published in another — the precise failure
a positive control exists to prevent. The objection is not that it is
unfaithful. It is that it would certify nothing about the cells.

**Candidate 2, subtracting a separately-measured floor, is rejected on three
counts, any one sufficient.**

- **The band cannot see the correction.** Adjudicating
  `R′ = (t_large − c) / (t_small − c)` against [1.4926, 2.4876] admits any `c`
  in **[−0.007, 0.081] ms** against a true value near 0.059 — everything from
  *no correction at all* to a 37% over-correction certifies. Such a control
  tests whether the floor estimate is right to within roughly a factor of two.
  It does not test whether the clock can see the arm's work double.
- **There is no stable floor to measure.** The same formula on the three
  certified arms returns **negative** constants — `fine` −8.1%, `coarse` −4.5%,
  `chunked` −12.3% of their small reading — because all three read *above* their
  derived prediction rather than below it. A quantity that is +47% on one arm
  and negative on three is the intercept of an assumed cost model, not a
  property of the rig that can be measured once and subtracted.
- **This page forbade it in advance.**
  [§1.7](#17-what-this-page-will-not-do): *"It will not add an instrument, a
  rung or an estimator mid-series."* A subtracted floor is an estimator, and one
  registered after the data.

The other half of candidate 2 — *many more operations under one clock* — is
**arithmetically inert**. The floor is paid per commit, so a window of `N`
commits reads `N(c + s)` and the ratio `(c + P·s)/(c + s)` does not depend on
`N` at all. Raising `batch-k` from 20 to 200 would change nothing but
quantisation, which is already negligible.

**What holds the refusal in place, and it is not this page.** No gate was
loosened and none needed to be:
`topo/control_witness_dom_cljs_test.cljs` already asserts `(< 1.471 lo)` on
every arm's band floor, and admitting `virtual`'s low round of 1.4600 requires a
floor below 1.471. **The widening this ruling declines is already red in CI**,
on every PR, without a browser and without a quiet box.

**What this ruling does not conclude.** It does not say the windowed arm is
unmeasurable, only that *this* control cannot license *these* cells: a different
instrument — one that does not pay a per-commit floor, or one that states the
arm at a window where the floor is small — remains open, and is `rf2-hic-080`
phase 2's to choose or decline. It does not re-open the three certified arms,
whose verdicts stand untouched. And it takes no position on why those three read
2.04–2.12 against a derived 1.9996; that they run consistently *above* their
prediction is visible in the table and unexplained here, in band and therefore
not this page's to chase.

This sits on top of a refusal the lane already carried, which this window
verified rather than assumed:

> bulk-class rows cannot hold a difference-statistic control at the ~3.5% floor
> a magnitude needs (`rf2-7iqb5`, 28–48% within-block IQR), and the narrow class
> sits on the clock clamp (`rf2-d2tzk`)
> — `shapes/census_clock_run.cjs`, refusing its own write rows by construction

**What was NOT done, deliberately.** The obvious move from inside the run is to
invent a second control for the coarse family — page-doubling works there,
because coarse's update cost genuinely does scale with the page. That is a new
rung, and a rung added between runs makes the series two instruments. It is
filed rather than built, and it is the single highest-value follow-up this
window produced.

### 2.7 The kill rule, and how many iterations were spent

Pre-registered: **two tuning iterations per red cell.** Spent: **zero**, and
the reason is recorded rather than convenient — no cell was red *against a line
the instrument could adjudicate*. The clock lines (U2, U3, C3, C4) went
unmeasured because the control refused before any cell was taken, so nothing
could be above them.

**Repairing the control would not have been a tuning iteration, and it was not
spent as one.** §1.6 defines the allowance as a bounded change to *the arm's
topology*, and says in terms that a change to the instrument, the band, the
control or the kill line is not a tuning iteration but a different tournament.
The control's 2.00 prediction is wrong for a reason the run diagnosed; fixing
it is `rf2-m6i0`, run as its own window.

Dispositions, one per cell class, with no cell left open:

| cell class | disposition | statement |
|---|---|---|
| `coarse` / sparse, edit | **NARROW** | Coarse is not offered for workloads with narrow updates at any row count. Its classification narrows to bulk-dominated workloads where boundary-shell cost dominates row-build cost — a condition this tournament cannot itself certify, so the narrowed claim is bounded by `rf2-hic-018`. |
| `chunked` / reorder | **STOP** | Chunked is not offered for reorder-bearing workloads. It builds every row *and* runs `⌈B/k⌉` bodies, strictly worse than both neighbours on both counters. |
| `fine` / sparse, edit, reorder | **PASS** | Meets U5; builds 1 row for 1 changed row and 0 for a permutation. |
| `virtual` / all four | **PASS, on a different page** | Constant in `B`, and every figure is about a DOM that holds `W` rows rather than `B`. |
| all arms / bulk | **UNRESOLVED** | Not above its kill line; not below it either. The line is in milliseconds and no millisecond here has a control. It is recorded as unresolved and is **not** carried as an open hope: the follow-up is one named, bounded control (§2.6), not a benchmark programme. |

### 2.8 What was NOT concluded

**This section closes the CENSUS window and the two control windows, which is
the state Part 2 reached before a clock driver existed.** The clock table was
instrumented later and run as its own window; that run is
[§2.9](#29-the-clock-table--the-window-rf2-w01c), and where it changes one of
the statements below it says so there rather than editing the record here.

- **No clock figure at all**, for any arm, operation or row count. The ratios
  in §2.6's table are the CONTROL's, and a refused control publishes nothing —
  including itself as a finding about the arms.
- **No verdict against U1, U2, U3, U4, C3 or C4** — all are millisecond lines.
- **No breach recorded against U5**, because as registered it is not breached.
  What is recorded is that its estimand cannot see the arm that fails the
  property it is written to protect.
- **No `rf2-hic-080` scoring.** Its phase 2 is deliberately another worker's,
  and a tournament that graded its own blinded predictor would destroy the
  property the split exists to protect. What this page hands over is the
  measured ordering on the work census plus the explicit note that the clock
  orderings are **unaddressed** in that page's own frozen vocabulary.
- **No package figure.** Everything here is arm-1.
- **No chunk-width result.** `k = 25` was a stated setting; nothing here is
  evidence about it, and §1.1's refusal to derive one stands.

---

### 2.9 The clock table — the window (`rf2-w01c`)

`topo/clock_app.cljs` landed as the driver §2.6 said did not exist. This
section is the window that ran it. It is written in the order it happened: the
plan first, committed before the driver was invoked, then what the runs
returned.

#### 2.9.1 The plan, fixed before the first run

Committed as its own commit, ahead of the first invocation, so that no stopping
rule can be chosen after seeing an answer. Nothing below was decided later.

- **Three runs.** Not "until they agree" and not "until one is clean". All
  three are taken whatever the first two say.
- **A RUN is an invocation that reaches the page and returns a verdict** —
  exit 0, 1 or 2. Its result is recorded whichever of the three it is.
- **A RIG FAULT is an invocation that never reaches the page** — a build
  failure, a missing dependency, a port already held. It is reported and it
  does **not** consume one of the three, because it measured nothing.
- **Nothing is tuned between runs.** Not the sampling, not `batch-k`, not the
  band, not the arms. §1.7 forbids adding an instrument, a rung or an estimator
  mid-series and that binds this window as it bound the last one.
- **Runs are serial.** One at a time, never two, and nothing else is run beside
  them.
- **The controls arbitrate, not the operator.** A refused control withholds the
  cells for that arm under §1.5, and the refusal is the result.

#### 2.9.2 The box, read before the first run and never inside one

Counters read standalone. Sampling them alongside the benchmark would measure
the sampler, so the claim made here is about the bracket and not about
within-run quietness.

| counter | reading |
|---|---|
| `\System\Processor Queue Length` | **0.00** on all 5 samples |
| `\Processor(_Total)\% Processor Time` | 11.99 / 11.48 / 7.83 / 6.36 / 5.98 |
| `Win32_Processor.LoadPercentage` (second source) | 31 |
| logical processors | 24 |
| physical memory | 64 GB (`Win32_PhysicalMemory` sum); 63.4 GB usable |
| OS | Windows 11 Home 10.0.26200 |

**The two CPU sources disagree and the queue length is preferred**, because it
is the counter that reports whether anything is *waiting* for a core rather
than estimating how busy one was. No `node`, `java`, `shadow-cljs` or
`clojure` process was running. A browser session was open with idle background
tabs; its processes carry large *cumulative* CPU totals, which is process age
and not current load.

**§2.6 records this box as 24 threads / 32 GB.** It reads 64 GB here from two
sources. The discrepancy is recorded and nothing is concluded from it.
