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

**Amended 2026-08-18 (`rf2-w01c`): the clock half now publishes.** The driver
those paragraphs said did not exist landed as `topo/clock_app.cljs`, and its
window ran three times on a drained box — [§2.9](#29-the-clock-table--the-window-rf2-w01c).
Thirty-six operation cells publish across `fine`, `coarse` and `chunked`, with
a nine-cell floor row beside them; `virtual` stays UNADDRESSED under `rf2-4t36`
and no clock was started on it. **The sentences above are left standing as the
record of the state before that run**, which is the discipline §2.6 already
keeps for the control it replaced. What did *not* change is the estimator: the
window produces no `p95`, so §2.8's "no verdict against U1, U2, U3, U4, C3 or
C4" survives the table intact and [§2.9.9](#299-what-was-not-concluded) says why.

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
  including itself as a finding about the arms. **This is the one bullet
  §2.9 overturns**: the table exists as of 2026-08-18 for three arms of four
  ([§2.9.5](#295-the-clock-table)). Every other bullet below still holds.
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

> **[2026-08-22, `rf2-w01c` — the audit's second remedy, taken. A narrowing
> ruling, not a re-run and not a re-score.]** The merged-PR audit of #8466
> (2026-08-20) found that the series below did not satisfy its own
> fixed-before-run plan — [§2.9.1](#291-the-plan-fixed-before-the-first-run)
> pre-registered that the runs are serial and *"nothing else is run beside
> them"*, and a second agent was demonstrably active on the box during run 1 —
> and it named exactly two remedies: a fresh exclusive three-run series, or an
> explicit methodological ruling narrowing the condition.
> [Part 3](#part-3--the-re-take-rf2-w01c-the-exclusive-window) attempted the
> first. That exclusive window ran on **2026-08-22 between 04:52 and 05:13**
> and returned **two admissible runs of a pre-registered three**: two
> invocations were voided by the condition's own peer-write rule, and
> [§2.9.2](#292-two-attempts-and-only-the-second-one-publishes)'s *a series
> completed later is two instruments* forbids finishing the third another day.
>
> **The ruling, given on `rf2-w01c` on 2026-08-22 — the pre-registered
> exclusivity condition binds AGENT OVERLAP ONLY, not the whole box.** Read as
> the whole box it is unsatisfiable on this machine: at the quietest moment the
> project reaches, with no worker in flight at all, the box still carries
> resident build servers and browser processes belonging to no live agent, so
> that reading would retroactively void every window ever taken here, this
> section's included. Under the ruling as given, Part 3's two runs stand as
> **evidence rather than as a failed attempt**, and **no third window is
> owed**. The ruling is the mayor's and the operator's to overturn; if it is
> overturned, this note is the single thing to withdraw.
>
> **What the two exclusive runs reproduce.**
> [§2.9.7](#297-the-floor-row-and-the-thing-it-turned-out-not-to-be)'s
> falsification is **confirmed in all six readings** — the floor row is
> arm-specific, always larger on the arm with more boundaries and reads. The
> narrow/broad reversal holds in **all eight** readings, and `bulk` still
> orders the arms. Every ordering [§2.9.8](#298-what-the-table-settles) states
> is reproduced under exclusivity. **The one half that does not reproduce** is
> §2.9.8's *"and the three runs agree"* — which is the incompleteness itself,
> two runs having no third to agree with, rather than a disagreement about the
> table.
>
> **The absolute cells moved with the box and the arm-to-arm ratios did not**,
> measured on the published series' own statistic: raw cell spread reads a
> published median of **9.0%** against the exclusive series' **28.6%**, while
> ratio-cell spread reads **3.9%** published against **3.8%** exclusive.
>
> **The narrowing itself is one sentence: the defect the audit found in this
> section's published series does not reach this section's conclusions** — and
> the exclusive window is the evidence for that which the audit could not have
> had, because at the time of the audit nobody had spent the window.
>
> **Nothing below is replaced and nothing is re-scored.** Two runs are not
> three, so Part 3 is a **record** and not a window: every figure in §2.9.5 and
> §2.9.6 stands exactly as published, and **no ledger row moves** in either
> direction. See
> [§3.4](#34-why-run-3-was-not-re-taken-and-the-series-is-incomplete) and
> [§3.7](#37-the-adjudication-rule-by-rule-and-what-two-runs-may-close).

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

#### 2.9.2 Two attempts, and only the second one publishes

The window was attempted twice, and the first attempt is **void** rather than
partial. On 2026-08-17 a first worker read the box, took runs, and died on an
API quota limit while parsing their summary records. No figure from it was
ever published and none is salvaged here: a series interrupted mid-way and
completed later on a different day is two instruments, which is the fence
§1.7 keeps. What survived and is honoured is [§2.9.1](#291-the-plan-fixed-before-the-first-run) — the
plan, committed before any invocation.

**Everything from §2.9.3 down is the second attempt**, run end to end on
2026-08-18 under the same plan, unchanged. Its box bracket is §2.9.3's; the
2026-08-17 bracket is recorded below as the void attempt's and is *not* a
bracket for the published series.

| counter (2026-08-17, VOID attempt) | reading |
|---|---|
| `\System\Processor Queue Length` | **0.00** on all 5 samples |
| `\Processor(_Total)\% Processor Time` | 11.99 / 11.48 / 7.83 / 6.36 / 5.98 |
| `Win32_Processor.LoadPercentage` (second source) | 31 |

**§2.6 records this box as 24 threads / 32 GB.** It reads 64 GB from two
sources (`Win32_PhysicalMemory` sum, 63.4 GB usable). The discrepancy is
recorded and nothing is concluded from it. The runtime the page itself reports
is `deviceMemory 32`, which is a browser-capped value and not a third reading.

#### 2.9.3 The box, bracketed around each run and never sampled inside one

Counters were read standalone, before run 1, between runs, and after run 3.
Sampling them alongside the benchmark would measure the sampler and sampling
them inside a run would measure the benchmark, so **the claim made here is
about the brackets and nothing is claimed about within-run quietness beyond
them.**

| bracket | `\System\Processor Queue Length` (5 samples) | `\Processor(_Total)\% Processor Time` | `Win32_Processor.LoadPercentage` |
|---|---|---|---|
| before run 1 | 0 / 0 / 1 / 0 / 0 | 21.80 / 23.30 / 35.72 / 26.68 / 25.29 | 47 |
| after run 1 | 0 / 0 / 0 / 0 / 0 | 19.40 / 16.17 / 24.56 / 14.20 / 20.86 | 44 |
| before run 2 | 0 / 0 / 0 / 0 / 0 | 19.59 / 14.54 / 19.78 / 26.80 / 16.29 | 37 |
| between runs 2 and 3 | 0 / 0 / 0 / 0 / 0 | 24.73 / 22.89 / 19.61 / 23.94 / 20.48 | 63 |
| after run 3 | 0 / 0 / 0 / 0 / 0 | 20.55 / 20.73 / 19.11 / 17.13 / 17.87 | 42 |

The box itself, unchanged between the two attempts:

| | |
|---|---|
| logical processors | 24 |
| physical memory | 64 GB (`Win32_PhysicalMemory` sum); 63.4 GB usable |
| OS | Windows 11 Home 10.0.26200 |
| browser | HeadlessChrome 147.0.7727.15 via Playwright, `:advanced`, `goog.DEBUG` false |
| profile | P-DEV-1 |

**The queue length is the preferred counter and it read 0 on 24 of the 25
samples above**, because it is the one that reports whether anything is
*waiting* for a core rather than estimating how busy one was. One one-second
sample carried a queue of 1. It is recorded; **what was waiting during that
second is not established here** and nothing is built on it.

**The two CPU sources disagree, and a third reading decides which to believe.**
`LoadPercentage` ran 37–63 while `\Processor(_Total)\% Processor Time` ran
14–36. Two standalone per-process CPU-delta censuses, over 4 and 5 seconds,
summed to **3.5 and 3.2 busy cores of 24 — 15% and 13%**. Those agree with the
`_Total` counter and not with `LoadPercentage`, so `LoadPercentage` is treated
as the outlier on that evidence. The same two-source disagreement, in the same
direction, appears in the void attempt's bracket above.

**This box was busier than the void attempt's bracket and the difference is
recorded rather than smoothed.** The named consumers, from that same
standalone census, were the operator's desktop: an editor's language service
at ~0.94 core and browser renderers at ~1.85 cores between them. **No `java`,
`shadow-cljs` or `clojure` process was running at any bracket.** Twenty `node`
processes were resident throughout, every one an idle MCP or harness server
with under 2 seconds of *cumulative* CPU — process age, not load.

**The fleet was not at zero, and that is stated rather than assumed away.**
The drain was announced and no implementation worker was compiling, but a
second agent working a documentation bead was demonstrably active on the box
during run 1: it wrote into the session-shared scratch directory at the minute
run 1 began. It ran no compiler and no benchmark, and it is inside the
per-process census above rather than outside it — but it means the correct
description of this window is *a drained box with one light concurrent agent*,
not *an idle box*.

#### 2.9.4 What ran, and what each run returned

Three invocations of `topo/clock_app.cljs` through the lane's generic
`run.cjs`, serial, nothing between them, nothing tuned. All three reached the
page, so **the pre-registered count was spent on runs and no invocation was a
rig fault.**

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| exit code | **0** | **0** | **0** |
| build | 157 files, 102 compiled, **0 warnings**, 30.69 s | 28.71 s | 30.41 s |
| positive control | passed on `fine`, `coarse`, `chunked` | same | same |
| arm-order guard | **18 of 18 reportable** | 18 of 18 | 18 of 18 |
| write verification | controls: **0 unverified of 300** per arm. Table rows: every one passed `lane/assert-verified!`, which throws on any unverified write, and no `PROBE MISS` line was emitted | same | same |
| cells taken | 45 (3 arms × 5 rows × 3 row counts) | 45 | 45 |

The series ran between 13:48 and 14:01 AUSEST on 2026-08-18; each run took
about four minutes including its own rebuild, well inside `run.cjs`'s
20-minute sentinel.

**The control certified every arm in every round of every run.** The
registered manipulation is the rendered-scale doubling with an indexed write
(§1.5), predicted **1.9996**, band **[1.4997, 2.4995]**:

| arm | run 1 rounds | run 2 rounds | run 3 rounds |
|---|---|---|---|
| `fine` | 2.1068 2.2052 2.2031 2.1204 2.1561 | 2.1410 2.1762 2.1923 2.2119 2.2461 | 2.1159 2.2168 2.1121 2.2566 2.0746 |
| `coarse` | 2.0407 2.0262 2.0023 2.0493 1.9966 | 2.0377 2.0276 2.0873 2.0511 2.0368 | 2.0463 2.0700 2.0560 2.0683 2.0648 |
| `chunked` | 2.0981 2.0554 2.1146 2.2166 2.0680 | 2.1585 2.1145 2.1824 2.1383 2.2141 | 2.1412 2.0173 2.1411 2.1627 2.1184 |

All 45 round ratios lie in **[1.9966, 2.2566]**, inside the band, positive,
and verified in every round. `fine` and `chunked` sit consistently above the
predicted 1.9996 and `coarse` sits closest to it; that is recorded and nothing
is concluded from it, because a band is what the control is adjudicated on and
this window did not register a second, tighter test.

**`virtual` was not measured and no clock was started on it**, per `rf2-4t36`.
Its cells remain UNADDRESSED, no band was widened and no window was enlarged.

#### 2.9.5 The clock table

**Read the unit before the numbers.** Each cell is the elapsed milliseconds
for **one window of 20 commits** of that operation on an already-mounted page,
under one clock, with the read-back outside the window. It is *not* a
per-commit latency, and dividing by 20 yields a **mean** per commit rather
than any percentile.

The centre is the **median across the run's 5 rounds of each round's
within-round median** of 12 samples. Three figures per cell, one per run, in
run order — the runs are shown rather than pooled, because pooling them would
be a fourth estimator this window did not pre-register.

| operation | `B` | `fine` r1/r2/r3 | `coarse` r1/r2/r3 | `chunked` r1/r2/r3 |
|---|---|---|---|---|
| sparse | 100 | 4.05 / 4.40 / 4.05 | 9.30 / 9.95 / 9.10 | 3.90 / 4.20 / 3.75 |
| sparse | 300 | 12.35 / 10.80 / 10.00 | 29.40 / 26.25 / 24.65 | 6.20 / 5.60 / 5.05 |
| sparse | 1000 | 37.90 / 37.95 / 33.70 | 88.40 / 91.05 / 86.50 | 13.05 / 13.45 / 12.20 |
| bulk | 100 | 21.80 / 21.60 / 24.25 | 9.85 / 9.30 / 10.05 | 10.75 / 9.95 / 11.15 |
| bulk | 300 | 79.00 / 82.60 / 76.55 | 29.35 / 29.65 / 27.20 | 31.75 / 32.65 / 29.50 |
| bulk | 1000 | 330.80 / 332.45 / 331.55 | 96.50 / 99.05 / 100.60 | 108.35 / 112.85 / 115.30 |
| reorder | 100 | 4.65 / 4.55 / 4.50 | 8.80 / 8.50 / 8.45 | 10.70 / 10.45 / 10.05 |
| reorder | 300 | 14.40 / 15.80 / 12.75 | 29.00 / 30.70 / 25.20 | 34.45 / 37.60 / 30.05 |
| reorder | 1000 | 46.95 / 46.95 / 44.85 | 88.65 / 92.25 / 90.90 | 118.65 / 122.65 / 120.05 |
| edit | 100 | 4.00 / 4.05 / 3.60 | 9.25 / 9.25 / 8.05 | 3.90 / 3.85 / 3.40 |
| edit | 300 | 10.00 / 11.05 / 10.40 | 24.25 / 25.60 / 26.00 | 5.30 / 5.75 / 5.65 |
| edit | 1000 | 36.65 / 36.25 / 36.70 | 84.85 / 85.20 / 92.95 | 12.20 / 12.25 / 12.60 |
| **noop (floor)** | 100 | 3.70 / 3.55 / 3.15 | 1.70 / 1.60 / 1.45 | 1.80 / 1.75 / 1.50 |
| **noop (floor)** | 300 | 9.00 / 8.75 / 9.55 | 2.90 / 2.95 / 3.10 | 3.20 / 3.15 / 3.40 |
| **noop (floor)** | 1000 | 31.75 / 31.60 / 31.85 | 8.00 / 8.20 / 8.40 | 9.40 / 9.40 / 9.55 |

**Resolution.** The smallest window in the table is 1.45 ms — about 14 of
Chrome's 100 µs quanta — and the largest is 332.45 ms. That is what batching
20 commits under one clock bought. **It does not rescue the windowed arm and
was not meant to**: `rf2-4t36`'s refusal rests on the floor being 47% of that
arm's commit, a fraction batching leaves exactly where it was, and no clock
was started on `virtual` here.

#### 2.9.6 The ratios, against `fine`

`fine` is the denominator throughout: it is the tournament's reference
topology and the one arm both refused controls could address. **Each figure is
the arithmetic MEAN of the run's 5 per-round ratios, and each of those is a
ratio of within-round medians.** It is not itself a median, though a median
does sit one level down inside it — which is exactly how PR #8326 came to
publish values of this shape as "run-medians", corrected under `rf2-pqyxz`.

A value **below 1.00 means that arm is faster than `fine`**; above 1.00,
slower.

| operation | `B` | `coarse` / `fine` r1/r2/r3 | `chunked` / `fine` r1/r2/r3 |
|---|---|---|---|
| sparse | 100 | 2.324 / 2.241 / 2.250 | 0.976 / 0.934 / 0.927 † |
| sparse | 300 | 2.343 / 2.422 / 2.479 | 0.504 / 0.520 / 0.509 |
| sparse | 1000 | 2.299 / 2.403 / 2.531 | 0.334 / 0.352 / 0.357 |
| bulk | 100 | 0.451 / 0.428 / 0.418 | 0.495 / 0.458 / 0.453 |
| bulk | 300 | 0.367 / 0.365 / 0.358 | 0.398 / 0.401 / 0.389 |
| bulk | 1000 | 0.292 / 0.303 / 0.295 | 0.331 / 0.340 / 0.337 |
| reorder | 100 | 1.900 / 1.845 / 1.922 | 2.292 / 2.221 / 2.261 |
| reorder | 300 | 1.977 / 1.970 / 1.985 | 2.378 / 2.379 / 2.377 |
| reorder | 1000 | 1.890 / 2.005 / 2.038 | 2.516 / 2.685 / 2.701 |
| edit | 100 | 2.282 / 2.247 / 2.276 | 0.949 / 0.936 / 0.956 ‡ |
| edit | 300 | 2.398 / 2.393 / 2.489 | 0.526 / 0.520 / 0.541 |
| edit | 1000 | 2.321 / 2.384 / 2.511 | 0.335 / 0.338 / 0.341 |
| **noop (floor)** | 100 | 0.456 / 0.451 / 0.459 | 0.496 / 0.488 / 0.484 |
| **noop (floor)** | 300 | 0.321 / 0.326 / 0.324 | 0.350 / 0.351 / 0.357 |
| **noop (floor)** | 1000 | 0.254 / 0.259 / 0.268 | 0.292 / 0.300 / 0.306 |

† straddles 1.00 in run 1 only. ‡ straddles 1.00 in run 3 only.

**Two of the 90 ratio readings carry the indistinguishable flag**, and neither
replicates across the series. Both are narrow operations at `B = 100`, where
`fine` and `chunked` each sit near 4 ms over 20 commits and the two arms differ
by at most 0.30 ms in any run. The honest reading is that `chunked` and `fine`
are close there and this instrument does not reliably separate them — not that
one of the three runs was wrong.

**Run-to-run agreement.** Across the 30 ratio cells, the spread of the three
run means, taken as `(max − min) / min`, has a **median of 3.9%** and a
**maximum of 10.1%** (`coarse`/`fine` on sparse at `B = 1000`: 2.299, 2.403,
2.531). That is a statement about this three-run series on this box and is not
offered as a precision figure for the instrument.

#### 2.9.7 The floor row, and the thing it turned out not to be

`[:topo/noop-write]` moves a key no arm reads. The driver's premise, stated in
its own docstring, was that its window therefore holds *"exactly the cost that
does not move with the arm"*.

**The measurement falsifies that premise, and this is the window's most
consequential result.** At `B = 1000` the floor row reads **31.75 / 31.60 /
31.85 ms on `fine`** against **8.00 / 8.20 / 8.40 on `coarse`** — a factor of
**3.97, 3.85 and 3.79** in runs 1, 2 and 3 respectively. The floor is
**arm-specific**, not a shared constant.

> **[2026-08-22, `rf2-w01c`'s exclusive re-take — the claim replicates and the
> factor does not.]** [Part 3](#part-3--the-re-take-rf2-w01c-the-exclusive-window)
> re-ran this instrument under the exclusivity condition the #8466 audit asked
> for. **The falsification above is confirmed and strengthened**: the floor is
> arm-specific in all six readings it took, always larger on the arm with more
> boundaries and reads, and at `B = 100` and `B = 300` its factors sit on top of
> this series' — 2.15× / 2.12× against 2.18 / 2.22 / 2.17, and 3.19× / 3.00×
> against 3.10 / 2.97 / 3.08.
>
> **At `B = 1000` the factor does NOT replicate**: 3.54× and 3.35× against the
> 3.97 / 3.85 / 3.79 above, on cells that both read higher (`fine` 35.75 / 39.85,
> `coarse` 10.10 / 11.90). So *"about 4×"* is this series' number and not the
> instrument's; what survives re-measurement is *the floor is larger on the arm
> with more boundaries and reads*, which is the sentence §2.9.7 already limits
> itself to, plus the observation that **the factor itself grows with `B`** —
> roughly 2.1× at 100, 3.0× at 300, 3.4× at 1000 — which one row count could not
> show.
>
> **No figure in this section is replaced.** Part 3's series is two admissible
> runs against a pre-registered three, so it is a record and not a window; the
> values above stand as published. See
> [§3.7](#37-the-adjudication-rule-by-rule-and-what-two-runs-may-close).

What it varies with is not settled here. The three arms differ in boundary
count (1001 / 1 / 41 at `B = 1000`), in subscription count and in nothing else
that this window separates; the DOM element count is deliberately identical
across arms, which excludes that one and only that one. So the correct
statement is *the floor is larger on the arm with more boundaries and reads*,
not *the floor is boundary-count times a per-boundary cost* — no such cost is
fitted here, and fitting one would be adding an estimator mid-series, which
§1.7 forbids.

The share each cell's window gives to its own arm's floor, as the floor row's
centre over the operation row's centre at the same arm and `B`. **It corrects
nothing**: `rf2-4t36` rejected subtracting a floor, and its sharpest reason —
that there is nothing stable to subtract — is strengthened rather than
weakened by the row being arm-specific.

| operation | `B` | `fine` r1/r2/r3 | `coarse` r1/r2/r3 | `chunked` r1/r2/r3 |
|---|---|---|---|---|
| sparse | 100 | 0.914 / 0.807 / 0.778 | 0.183 / 0.161 / 0.159 | 0.462 / 0.417 / 0.400 |
| sparse | 300 | 0.729 / 0.810 / 0.955 | 0.099 / 0.112 / 0.126 | 0.516 / 0.562 / 0.673 |
| sparse | 1000 | 0.838 / 0.833 / 0.945 | 0.090 / 0.090 / 0.097 | 0.720 / 0.699 / 0.783 |
| bulk | 100 | 0.170 / 0.164 / 0.130 | 0.173 / 0.172 / 0.144 | 0.167 / 0.176 / 0.135 |
| bulk | 300 | 0.114 / 0.106 / 0.125 | 0.099 / 0.100 / 0.114 | 0.101 / 0.097 / 0.115 |
| bulk | 1000 | 0.096 / 0.095 / 0.096 | 0.083 / 0.083 / 0.084 | 0.087 / 0.083 / 0.083 |
| reorder | 100 | 0.796 / 0.780 / 0.700 | 0.193 / 0.188 / 0.172 | 0.168 / 0.168 / 0.149 |
| reorder | 300 | 0.625 / 0.554 / 0.749 | 0.100 / 0.096 / 0.123 | 0.093 / 0.084 / 0.113 |
| reorder | 1000 | 0.676 / 0.673 / 0.710 | 0.090 / 0.089 / 0.092 | 0.079 / 0.077 / 0.080 |
| edit | 100 | 0.925 / 0.876 / 0.875 | 0.184 / 0.173 / 0.180 | 0.462 / 0.455 / 0.441 |
| edit | 300 | 0.900 / 0.792 / 0.918 | 0.120 / 0.115 / 0.119 | 0.604 / 0.548 / 0.602 |
| edit | 1000 | 0.866 / 0.872 / 0.868 | 0.094 / 0.096 / 0.090 | 0.770 / 0.767 / 0.758 |

**The received formula for reading a mostly-floor cell does not apply to this
table, and the reason is the same falsification.** *"A cell whose window is
mostly floor has its arm-to-arm ratio compressed toward 1"* is true when both
arms carry the **same** floor. These do not. The limit here is a different
number: were an operation to add nothing at all to either arm, the ratio would
read this table's own floor ratio — `coarse`/`fine` of 0.254–0.268 at
`B = 1000` — and not 1.00. **No corrected ratio is published**, because
computing one requires the additive cost model `rf2-4t36` refused, and this
window did not relitigate that refusal.

#### 2.9.8 What the table settles

- **`bulk` orders the three arms, and it is the one operation whose ordering
  is not floor-laden.** Its floor share is at most 0.176 at any arm or row
  count and falls to ≤ 0.096 at `B = 1000`. There, `coarse`/`fine` reads
  0.292 / 0.303 / 0.295 and `chunked`/`fine` reads 0.331 / 0.340 / 0.337 —
  **`fine` costs about 3.3–3.4× `coarse` and about 2.9–3.0× `chunked`**, with
  no straddle and the three runs agreeing to within 3.8%. One boundary per row
  is expensive when every row changes, and the size of that price is now
  measured rather than argued.
- **The ordering reverses between the broad and the narrow operations, and the
  reversal is stable.** On `sparse` and `edit` at `B ≥ 300`, `chunked` is the
  fastest arm in all twelve readings — 4 cells × 3 runs — at 0.334–0.541
  against `fine`, and `coarse` is the slowest in all twelve at 2.299–2.531
  against `fine`. On `bulk` the order is
  exactly inverted. That is the useful-middle claim
  §1.1 made for `chunked` showing up as a clock reading for the first time.
- **`reorder` behaves like neither**, and it is the cell a reader should be
  most careful with: `fine` is fastest (`coarse`/`fine` 1.85–2.04,
  `chunked`/`fine` 2.22–2.70) but `fine`'s own `reorder` window is 55–80%
  floor at every row count, while its competitors' are under 20%. The ordering
  is reported; **no ordering of the operation itself is claimed**, for the
  reason §2.9.7 gives.
- **The instrument certified on every run, and the three runs agree.** Nine
  arm-runs and 45 control rounds all in band and positive, 54 reportable
  arm-order verdicts, controls at 0 unverified of 300 per arm, every table row
  through `lane/assert-verified!`, and no `PROBE MISS` line in any run. That is
  repeatability across three runs inside one hour on one box; **it is not a
  claim about another box or another day**, and the census half remains the
  half anyone can re-run.

#### 2.9.9 What was NOT concluded

- **No U-line or C-line verdict, and the reason is the estimator rather than
  the numbers.** `U2` (50 ms p95), `U3` (100 ms p95) and `U1` are
  distributional rows; `lane/summarise` computes `n`, `min`, `max` and `p50`
  and **never a `p95` at all**. Dividing a 20-commit window by 20 gives a mean
  per commit, and a mean is not a percentile. Every cell here is therefore
  **UNASSESSED** against those lines, in the same sense
  [the budgets page](budgets.md#the-comparative-and-regression-rules) already
  records `C8`'s `≥ 2 ms p95` disjunct as unassessed at its witness. `S1`–`S8`
  are not this page's to move and are not moved.

    **[2026-08-18, `rf2-xa8wo`.]** *Never a `p95` at all* was true of the lane
    when this window was taken and is no longer true of the lane: `lane/quantile`
    and `summarise`'s `:p95`/`:p99` have since landed. **Every cell above stays
    `UNASSESSED` all the same, and nothing on this page moves**, for the reason
    that outlived the estimator — this table's window is a commit bracketed by
    `flushSync` on a synthetic bench page, and `U1`–`U3` are stated over a slice
    application's interactions through to a paint. A `p95` is now computable
    here; it would still be a `p95` of the wrong population. See
    [budgets.md §9.4](budgets.md#94-what-rf2-hic-071-has-taken-so-far-and-what-it-still-cannot-take).
- **`C3` and `C4` are not addressed at all**, and not merely unresolved. They
  compare Hicasso against **the best relevant adapter**; this table compares
  four read *topologies* against each other inside one runtime. The two are
  different populations, and reading an arm-to-arm ratio as a `C3` figure
  would be the substitution `§1.4` forbids in a second direction.
- **No `implementation/hicasso` generalisation.** Like the census, every figure
  here is an **arm-1** figure, taken through the bench's UIx-adapter runtime.
  §2.1's substrate limitation applies unchanged and is not softened by the
  clock half existing.
- **No `virtual` cell, and no bound on one.** `rf2-4t36`'s ruling stands; the
  arm was not measured, so this window supplies no reading about it in either
  direction — not even an upper bound, because a bound has to come from a
  control or a null arm that was actually run.
- **No per-boundary or per-row cost.** §2.9.7 records that the floor is
  arm-specific; it deliberately fits no coefficient, and the three candidate
  causes it names are not separated by anything in this window.
- **No kill-rule disposition, and therefore no `STOP` or `NARROW` for any
  cell.** §1.6's lines are stated in milliseconds at `p95`, which this
  instrument does not produce, so no cell is established as above or below its
  line. Recording a disposition without one would be the work-census-wearing-a-
  clock's-clothes substitution in yet another direction.
- **No claim about within-run quietness.** §2.9.3's counters bracket the runs;
  nothing was sampled inside one, and a second agent was active on the box
  during run 1.
- **No `rf2-hic-080` scoring.** Unchanged: that is its phase 2 and another
  worker's. What this window hands that bead is the clock ordering it was
  blinded against, for `fine`, `coarse` and `chunked` only.
- **No tuning iteration was spent.** §1.6 allows two per red cell; no cell was
  established red, nothing was tuned between runs, and the two iterations
  remain unspent.

---

## Part 3 — the re-take (`rf2-w01c`, the exclusive window)

### 3.1 Why there is a second window at all

[§2.9](#29-the-clock-table--the-window-rf2-w01c) published a three-run series
and the merged-PR audit of that publication (#8466, 2026-08-20) found it did
not satisfy its own fixed-before-run plan. The finding is narrow and it is
mechanical: [§2.9.1](#291-the-plan-fixed-before-the-first-run) pre-registered
that the runs are serial and *"nothing else is run beside them"*, and
[§2.9.3](#293-the-box-bracketed-around-each-run-and-never-sampled-inside-one)
then records in terms that a second agent working a documentation bead was
demonstrably active on the box during run 1.

**Passing controls do not repair that, and the audit says why.** Disclosing an
overlap is honest; it does not convert a pre-registered condition into an
optional caveat after the fact. And replacing only run 1 is refused by
[§2.9.2](#292-two-attempts-and-only-the-second-one-publishes)'s own rule — a
series completed later is two instruments. The audit named exactly two
remedies: a fresh end-to-end three-run series under the registered exclusive
condition, or an explicit methodological ruling narrowing that condition. This
part is the first of those.

**§2.9's table is preserved and not deleted.** It is a transparent record of
what was measured and under what disclosure. What this part settles is which
series is the honoured `rf2-w01c` window.

### 3.2 The plan, fixed before the first invocation

Committed as its own commit, ahead of the first invocation of the runner, so
that no stopping rule and no adjudication can be chosen after seeing an answer.
Nothing below was decided later. Where a clause repeats §2.9.1 it is because
[§1.7](#17-what-this-page-will-not-do) forbids changing the plan between
windows, not because it was re-derived.

**Carried unchanged from §2.9.1:**

- **Three runs.** Not "until they agree" and not "until one is clean". All
  three are taken whatever the first two say.
- **A RUN is an invocation that reaches the page and returns a verdict** —
  exit 0, 1 or 2. Its result is recorded whichever of the three it is.
- **A RIG FAULT is an invocation that never reaches the page** — a build
  failure, a missing dependency, a port already held. It is reported and it
  does **not** consume one of the three, because it measured nothing.
- **Nothing is tuned between runs.** Not the sampling, not `batch-k`, not the
  band, not the arms.
- **Runs are serial.** One at a time, never two, and nothing else is run beside
  them.
- **The controls arbitrate, not the operator.** A refused control withholds the
  cells for that arm under §1.5, and the refusal is the result.

**Added by this window, because §2.9 did not need them and this one does:**

- **THE EXCLUSIVITY CONDITION, made falsifiable rather than asserted.** The
  defect being repaired was detected *after the fact*, from a peer's writes into
  the session-shared scratch directory at the minute run 1 began. That is the
  check, so it is registered as the check: the scratch directory's modification
  times are enumerated immediately before and immediately after each run, and
  **any file not this worker's own, modified inside a run's wall-clock bracket,
  voids that run**. A voided run is reported and re-taken; it does not consume
  one of the three, for the same reason a rig fault does not — a run taken
  beside a peer is the defect this window exists to repair, so it is not a
  reading.
- **The box is bracketed with §2.9.3's own counters** — `\System\Processor
  Queue Length` over five samples, `\Processor(_Total)\% Processor Time`, and
  `Win32_Processor.LoadPercentage` — read standalone before run 1, between
  runs, and after run 3. Never sampled inside a run. Nothing is claimed about
  within-run quietness beyond the brackets. The counters are RECORDED; **no
  counter reading is an admissibility criterion**, because setting a threshold
  on one after seeing §2.9.3's values would be choosing a bar known to be
  clearable.
- **THE ADJUDICATION RULE, and it is comparative.** This window's question is
  not *what are the cells* — §2.9 already published cells — but *does the
  exclusive series change the published values or the inference drawn from
  them*. So the verdict is fixed here, before any number:
    1. **The inference is adjudicated first**, on the four claims §2.9.8 makes
       and the one §2.9.7 makes. Each is re-evaluated against this series alone.
       A claim survives if this series supports it on its own evidence.
    2. **The values are adjudicated against §2.9.6's own published run-to-run
       spread**, which is `(max - min) / min` over the three run means with a
       recorded median of **3.9%** and a maximum of **10.1%**. A cell whose
       exclusive-series figure sits within the published series' own three-run
       envelope for that cell is **CONSISTENT**; one outside it is **MOVED** and
       is named individually. This is a pre-registered comparison against a
       number the previous window published, not a band invented for this one.
    3. **A MOVED value does not by itself overturn an inference.** §2.9.7's
       falsification and §2.9.8's orderings are qualitative; they are overturned
       only by this series contradicting them qualitatively.
    4. **If the inference survives and the values are consistent, the published
       table stands** and this part records that the honoured window is the
       exclusive series. **If either moves, `clock_app.cljs`'s docstring and
       §2.9.7 are updated in the same commit**, which is the #8471 audit's
       requirement and is not optional.
- **NO BAND IS WIDENED AND NO WINDOW IS ENLARGED.** `rf2-4t36`'s ruling stands
  unchanged: `virtual` is UNADDRESSED, no clock is started on it, and this
  window supplies no bound on it in either direction. The control's band stays
  §1.5's. `batch-k`, `sampling` and `rounds` are the instrument's own and are
  read out of it rather than chosen here.
- **THE PORT.** The driver takes `HICASSO_PORT=8148`, which is its own
  docstring's value and not a choice made here. A stray idle `ssr:hicasso-serve`
  holds **8139** on this box; that is a different port, so nothing is moved and
  nothing is killed. Had it collided, §2.9.1's rig-fault clause already covers
  *a port already held*.
- **WHAT THIS WINDOW DOES NOT DECIDE, registered here rather than discovered
  later.** It adjudicates **no ledger row**. §2.9.9's reasoning is unchanged and
  is not weakened by the series being exclusive: this table's window is a commit
  bracketed by `flushSync` on a synthetic bench page, and `U1`-`U3` are stated
  over a slice application's interactions through to a paint. Every cell stays
  `UNASSESSED` against them, `C3` and `C4` stay unaddressed as different
  populations, and no `implementation/hicasso` generalisation is drawn from an
  arm-1 figure. **No row of
  [`budgets.md` §9](budgets.md#9-the-budget-line-reconciliation-ledger)
  moves as a result of this part, in either direction.**

**The tree.** This window runs the instrument exactly as it stands at
`2a93f2c112fe81ebaccff5dafa085671fc5e6857`, which is an ancestor of `main`. The
pre-registration commit above touches this page and nothing else, so
`topo/clock_app.cljs`, `topo/control_app.cljs`, `topo/arms.cljs`,
`topo/model.cljs` and `run.cjs` are byte-identical to that landed tree.
**Nothing about the instrument moved between the two windows either**, which is
what makes rule 2's comparison one of boxes rather than of instruments.

### 3.3 What ran, and the two invocations that were VOIDED

Five invocations of `topo/clock_app.cljs` through the lane's generic `run.cjs`,
serial, nothing tuned, on 2026-08-22 between 04:52 and 05:13 AUSEST. All five
reached the page and returned exit 0, so none was a rig fault. **Two of the
five were then voided by §3.2's exclusivity condition, and the condition was
applied mechanically rather than judged.**

| invocation | wall clock | exit | peer write inside the bracket? | disposition |
|---|---|---|---|---|
| 1 | 04:52:34–04:56:07 | 0 | **yes**, 04:55:55 | **VOID** — not a reading |
| 1 (re-take) | 04:57:49–05:01:17 | 0 | no | **run 1 of 3** |
| 2 | 05:02:09–05:05:36 | 0 | no | **run 2 of 3** |
| 3 | 05:09:26–05:13:04 | 0 | **yes**, 05:12:14 | **VOID** — not a reading |
| — | — | — | — | run 3 **NOT TAKEN**; see §3.4 |

**What the two peer writes were, because the condition is only as good as what
it detects.** The 04:55:55 write was the dispatching loop's own tick recording a
fence clearance; it merged a pull request during the invocation, which is a
network fetch, a rebase and a trunk fast-forward on the same box. The 05:12:14
write was a **gate artefact belonging to another worker** — a link-validation
log named for a worktree that is not this one — and that worker's checkout had
been created at 05:13:33 and was running a documentation site build seconds
after the invocation ended.

**Neither void was a close call, and neither needed a counter to see.** That is
the point of registering the check as a directory listing rather than as a
threshold: it answers *was anything else running* with a file name and a
timestamp, which a reader can check later, rather than with a percentage that
has to be believed.

### 3.4 Why run 3 was not re-taken, and the series is INCOMPLETE

A peer worker held the machine from 05:13 onward. §3.2 carries §2.9.1's *runs
are serial and nothing else is run beside them* unchanged, and this bead's own
fence is the strict reading of it — an exclusive box, clearing only when no
other worker holds the machine at all. So a third run could not be taken.

**And it may not be taken later either.** §2.9.2's rule is that a series
interrupted and completed afterwards is *two instruments*, and it is exactly
the rule the #8466 audit used to refuse replacing only run 1 of the published
series. Completing this one after a peer's site build would repeat that defect
in the same shape. **The pre-registered count was three; two were taken; the
series is therefore INCOMPLETE and it does not supersede §2.9's table.**

What follows is published as a **RECORD of two admissible exclusive runs**, not
as a window. Every figure below is labelled with the number of runs behind it,
and §3.7 states in terms which of §3.2's four adjudication rules it is entitled
to close and which it is not.

### 3.5 The box, bracketed around every invocation

Counters read standalone, never inside an invocation, with §2.9.3's own three
sources. **The two voided invocations' brackets are shown too**, because a
bracket that only accompanies admitted runs cannot be checked for selection.

| bracket | `\System\Processor Queue Length` (5) | `\Processor(_Total)\% Processor Time` | `LoadPercentage` |
|---|---|---|---|
| before invocation 1 (voided) | 0 / 0 / 0 / 0 / 0 | 38.68 / 30.30 / 31.45 / 37.34 / 39.10 | 51 |
| after invocation 1 (voided) | 0 / 2 / 0 / 0 / 0 | 40.99 / 53.04 / 44.51 / 45.32 / 41.78 | 60 |
| before run 1 | 0 / 0 / 0 / 0 / 0 | 56.38 / 46.94 / 41.39 / 33.74 / 54.27 | 82 |
| after run 1 | 0 / 0 / 0 / 0 / 6 | 59.15 / 43.70 / 37.46 / 48.89 / 42.76 | 74 |
| before run 2 | 1 / 1 / 0 / 0 / 0 | 14.32 / 12.58 / 12.30 / 16.12 / 9.01 | 8 |
| after run 2 | 0 / 0 / 0 / 0 / 0 | 23.98 / 22.09 / 24.38 / 10.71 / 24.89 | 18 |
| before invocation 3 (voided) | 0 / 0 / 0 / 0 / 0 | 17.75 / 20.82 / 20.45 / 12.76 / 17.76 | 1 |
| after invocation 3 (voided) | 0 / 0 / 0 / 0 / 0 | 20.23 / 13.14 / 10.36 / 19.34 / 11.23 | 32 |

**No `java`, `shadow-cljs` or `clojure` process was resident at any bracket**,
and no headless browser outside an invocation. Twenty-two `node` processes were
resident throughout, every one an idle harness server with under two seconds of
*cumulative* CPU across days of uptime — process age, not load. **The
`ssr:hicasso-serve` stray on port 8139 was resident at every bracket and was
neither used nor killed**; the driver takes 8148, which was free at every
bracket, so §3.2's port clause never bound.

**The brackets do not predict the readings, and that is a finding rather than a
caveat.** Run 1 began at the busiest bracket in the table (`LoadPercentage` 82)
and run 2 at the quietest (8), yet run 2 is the *slower* run on 27 of the 45
cells. An admissibility rule keyed to these counters would have admitted and
refused the wrong runs, which is the strongest argument available for §3.2
having recorded them and declined to gate on them.

**A per-process CPU census, 5 s, taken after the series.** 2.29 busy cores of
24 — 9.5%. The named consumers were the operator's own desktop: an editor's
language service at **1.07 cores**, terminals and shells at 0.32, a screen-
capture helper and device services at 0.15, browsers at 0.05. **Every agent
process on the box summed to 0.52 cores — 23% of the busy time and 2% of the
machine** — and that total includes this worker. §3.8 is what follows from it.

### 3.6 The clock table — TWO admissible exclusive runs

Same unit as §2.9.5: elapsed milliseconds for **one window of 20 commits** of
that operation on an already-mounted page, under one clock, read-back outside
the window. Not a per-commit latency. The centre is the median across the run's
5 rounds of each round's within-round median of 12 samples — §2.9.5's estimator
unchanged, because §3.2 forbids moving it.

**Two figures per cell, one per run, in run order.** `virtual` is absent because
no clock was started on it.

| operation | `B` | `fine` r1/r2 | `coarse` r1/r2 | `chunked` r1/r2 |
|---|---|---|---|---|
| sparse | 100 | 3.45 / 3.85 | 8.10 / 8.65 | 3.50 / 3.70 |
| sparse | 300 | 9.60 / 14.00 | 23.95 / 34.75 | 4.85 / 7.10 |
| sparse | 1000 | 51.25 / 29.20 | 112.70 / 79.10 | 16.40 / 10.90 |
| bulk | 100 | 19.70 / 24.25 | 8.75 / 9.90 | 9.25 / 10.95 |
| bulk | 300 | 78.00 / 101.95 | 29.15 / 37.50 | 32.25 / 40.90 |
| bulk | 1000 | 358.10 / 396.85 | 112.70 / 129.45 | 120.50 / 135.80 |
| reorder | 100 | 4.35 / 5.70 | 8.15 / 11.25 | 9.80 / 13.20 |
| reorder | 300 | 15.75 / 14.15 | 31.45 / 29.40 | 39.75 / 34.75 |
| reorder | 1000 | 64.30 / 48.90 | 123.45 / 113.75 | 150.10 / 138.90 |
| edit | 100 | 3.30 / 5.45 | 7.95 / 12.00 | 3.25 / 5.30 |
| edit | 300 | 13.00 / 10.70 | 34.10 / 26.50 | 7.75 / 4.95 |
| edit | 1000 | 44.30 / 34.25 | 104.00 / 94.10 | 13.90 / 13.65 |
| **noop (floor)** | 100 | 2.80 / 3.60 | 1.30 / 1.70 | 1.40 / 1.80 |
| **noop (floor)** | 300 | 11.00 / 7.20 | 3.45 / 2.40 | 3.80 / 2.65 |
| **noop (floor)** | 1000 | 35.75 / 39.85 | 10.10 / 11.90 | 10.90 / 13.85 |

The ratios against `fine`, each the arithmetic mean of that run's 5 per-round
ratios of within-round medians — §2.9.6's estimator, unchanged. Below 1.00 means
faster than `fine`.

| operation | `B` | `coarse`/`fine` r1/r2 | `chunked`/`fine` r1/r2 |
|---|---|---|---|
| sparse | 100 | 2.331 / 2.233 | 0.977 † / 0.954 |
| sparse | 300 | 2.477 / 2.482 | 0.504 / 0.518 |
| sparse | 1000 | 2.248 / 2.663 | 0.312 / 0.365 |
| bulk | 100 | 0.442 / 0.414 | 0.470 / 0.450 |
| bulk | 300 | 0.367 / 0.382 | 0.412 / 0.419 |
| bulk | 1000 | 0.307 / 0.342 | 0.334 / 0.360 |
| reorder | 100 | 1.924 / 1.935 | 2.279 / 2.309 |
| reorder | 300 | 2.020 / 1.992 | 2.434 / 2.406 |
| reorder | 1000 | 1.897 / 2.314 | 2.338 / 2.822 |
| edit | 100 | 2.369 / 2.264 | 0.997 † / 0.984 † |
| edit | 300 | 2.641 / 2.550 | 0.556 / 0.513 |
| edit | 1000 | 2.376 / 2.641 | 0.335 / 0.389 |
| **noop (floor)** | 100 | 0.475 / 0.479 | 0.511 / 0.505 |
| **noop (floor)** | 300 | 0.328 / 0.326 | 0.357 / 0.361 |
| **noop (floor)** | 1000 | 0.282 / 0.289 | 0.307 / 0.334 |

† straddles 1.00. Three of the 60 ratio readings carry the flag, all of them
`chunked` against `fine` on a narrow operation at `B = 100` — the same two cells
§2.9.6 flagged, reading the same way. Where those two arms sit near 3–5 ms over
20 commits this instrument does not separate them, and that replicates.

**The instrument certified on both runs.** The registered rendered-scale
doubling, predicted 1.9996, band [1.4997, 2.4995]: 30 control rounds across the
two runs, every one in band, positive and verified, spanning **[2.0118,
2.4092]**. Controls at **0 unverified of 300 writes** per arm per run.
**18 of 18 reportable arm-order verdicts in each run.** No `PROBE MISS` line was
emitted in either. Both builds: 157 files, 102 compiled, **0 warnings** (20.73 s
and 26.47 s). Runtime `HeadlessChrome 147.0.7727.15` via Playwright, `:advanced`,
`goog.DEBUG` false — the same as §2.9's.

### 3.7 The adjudication, rule by rule, and what two runs may close

**Rule 1 — the inference. IT SURVIVES, and one half of one claim does not.**

- **§2.9.7's falsification is confirmed, twice more and on an exclusive box.**
  The floor row is arm-specific in all six readings, and always larger on the arm
  with more boundaries and reads: `fine`/`coarse` reads **2.15× and 2.12×** at
  `B = 100`, **3.19× and 3.00×** at `B = 300`, **3.54× and 3.35×** at
  `B = 1000`. The premise this driver was built on — that the row holds the cost
  that does *not* move with the arm — stays falsified, and nothing here rescues
  it.
- **§2.9.8's first claim survives with its number loosened.** `bulk` at
  `B = 1000` still orders the arms with the smallest floor share in the table
  (0.090–0.102 across arms and runs): `coarse`/`fine` reads 0.307 and 0.342,
  `chunked`/`fine` 0.334 and 0.360 — so `fine` costs **2.9–3.3×** `coarse` and
  **2.8–3.0×** `chunked`. §2.9.8 published *3.3–3.4×* and *2.9–3.0×*; the lower
  end of both is not reproduced. The ordering is unchanged, the size is nearer
  3× than 3.4×, and the *"three runs agreeing to within 3.8%"* half is not
  reproduced at all — these two runs disagree by 11.4% on that cell.
- **§2.9.8's second claim survives intact.** The reversal is stable in **all
  eight** readings: on `sparse` and `edit` at `B ≥ 300`, `chunked` is fastest
  (0.312–0.556 against `fine`) and `coarse` slowest (2.248–2.641), and `bulk`
  inverts it.
- **§2.9.8's third claim survives.** `fine` is fastest on `reorder`
  (`coarse`/`fine` 1.90–2.31, `chunked`/`fine` 2.28–2.82) while its own
  `reorder` window is 51–82% floor against competitors at or under 16%. The published
  bracket was *55–80%*; two runs put it at 50.9–81.5%, which is the same
  statement and not a tighter one.
- **§2.9.8's fourth claim splits.** *The instrument certified on every run* is
  confirmed. *The runs agree* is **NOT** confirmed — see rule 2.

**Rule 2 — the values. THEY MOVED, and the direction is the opposite of the one
the audit's premise implies.**

Against §2.9.6's own published three-run envelope for each cell:
**1 of 45 cells is CONSISTENT, 12 are SPLIT (one run in, one out) and 32 are
MOVED.** The exclusive series reads **slower on 36 of the 45 cells**, with a
cell-by-cell ratio to the published centre of min 0.901, median **1.094**, max
1.341.

And it is **noisier**, on the published series' own statistic:

| statistic, `(max − min) / min` | published (3 runs) | exclusive (2 runs) |
|---|---|---|
| raw cell spread, 45 cells | median **9.0%**, max **25.1%** | median **28.6%**, max **75.5%** |
| ratio-cell spread, 30 cells | median **3.9%**, max **10.1%** | median **3.8%**, max **22.0%** |

**Read the two rows against each other, because the contrast is the result.**
Three runs give a range more chances to widen than two do, so a two-run series
ought to score *lower* on both. On the raw cells it scores three times higher.
On the ratios it scores the same at the median. **The absolute cells moved with
the box; the arm-to-arm ratios did not** — which is what an estimator built on
within-round interleaving is supposed to do, and it is the first direct evidence
on this lane that it does.

**Rule 3 — a MOVED value does not overturn a qualitative inference.** It does
not here. Every §2.9.8 ordering above is reproduced.

**Rule 4 — both surfaces are revalidated in the commit that carries this
section**, which is the #8471 audit's requirement. What that revalidation may
say is bounded by §3.4: **two runs are not three, so no published value is
replaced.** §2.9.5 and §2.9.6 stand exactly as published, and
`clock_app.cljs`'s docstring keeps its figures. What both gain is a dated note
recording that the `≈ 4×` floor factor at `B = 1000` did **not** replicate under
an exclusive box, reading 3.35–3.54× instead — the claim it carries survives,
the magnitude beside it is now known to be softer than one series suggested.

### 3.8 What this window actually establishes, and the ruling it asks for

**The finding is about the remedy, not about the arms.** The #8466 audit offered
two remedies and this part took the expensive one. Taking it produced a series
that is **worse by every stability statistic the published one reports** — three
times the cell spread on two thirds of the runs — while reproducing every
qualitative conclusion. That is evidence about the remedy itself, and it was not
available before somebody spent the window.

**The mechanism is measured, not guessed.** §3.5's census puts every agent
process on this box at **0.52 of 2.29 busy cores**, against 1.07 for a single
editor language service. The exclusivity condition governs the 23%. **A
condition that can only reach a quarter of the load cannot deliver a quiet box
on this machine**, and §3.5's brackets show the other three quarters moving
between 1 and 82 on `LoadPercentage` across twenty minutes, uncorrelated with
which run read fast.

**So the question this part hands the operator is one sentence, and it is
methodological rather than numerical:** *does the pre-registered exclusivity
condition bind agent overlap only, or the whole box?* Both answers are
defensible and they lead to different places.

- **If it binds agent overlap only**, §2.9's published series has a defect that
  this part has now shown does not reach its conclusions — every §2.9.8 ordering
  reproduced under exclusivity — and the honest repair is a narrowing ruling
  plus a note, not a third full window. That is the audit's own second remedy,
  and this part is the evidence for it that the audit could not have had.
- **If it binds the whole box**, then no window taken on this machine has ever
  met the condition, §2.9's included and this part's included, and what is
  needed is not another attempt but a different box — which is a programme
  decision and outside a worker's authority.

**What is NOT asked for, and would be the wrong repair.** Not a wider band: the
control's band is §1.5's and stayed there. Not a longer window: `rf2-4t36`
refuses enlarging it and this part started no clock on `virtual`. Not a
threshold on the bracket counters: §3.5 shows they do not predict the readings,
so a threshold fitted to them would be a bar chosen after seeing which side it
falls on.

### 3.9 What this part does not decide

- **No ledger row moves**, in either direction, exactly as §3.2 registered
  before any number was seen. Every cell here stays **UNASSESSED** against
  `U1`–`U3` for §2.9.9's reason, which exclusivity does not touch: this window
  is a commit bracketed by `flushSync` on a synthetic bench page, and those lines
  are stated over a slice application's interactions through to a paint. `C3` and
  `C4` are a different population and are not addressed. The ledger reads the
  same 49 rows, 32 `MET`, 5 `BREACH`, 3 `UNRESOLVED`, 9 `UNPINNED` after this
  part as before it.
- **`U1`–`U4`, `S1`–`S8` and `C1`–`C8` are the ledger's DISTRIBUTIONAL rows and
  `D1`–`D26`, `U5`, `U6` and `I9` are its deterministic ones** — twenty against
  twenty-nine. That is not read off this page's prose, which is what the gate is
  for: planting the `PR gate` lane onto `U2` returns *"L6 U2 is a distributional
  row wired to the PR-gate lane"*, and moving `U5` off it returns *"L6 U5 is a
  deterministic row in the `P-DEV-1 evidence run` lane"*, so the partition is the
  checker's and both sides of it were exercised. **No row of either family is
  touched here.**
- **No third run, and no bound derived from two.** The pre-registered count was
  three and it was not met, so §3.6 is a record and §2.9's table remains the
  published one.
- **`virtual` is still UNADDRESSED.** `rf2-4t36` stands; no clock was started on
  it and this part supplies no bound on it in either direction, not even an upper
  one.
- **No claim about within-run quietness.** §3.5's counters bracket the
  invocations and nothing was sampled inside one. What §3.3 establishes about the
  admitted runs is narrower and it is what the condition actually checks: no
  process other than this worker wrote into the shared scratch directory while
  they ran.
- **No `implementation/hicasso` generalisation.** Every figure here is an
  **arm-1** figure taken through the bench's UIx-adapter runtime, as §2.1
  requires, and nothing about the box condition softens that.
- **No tuning iteration was spent.** §1.6 allows two per red cell; no cell was
  established red and nothing was tuned between invocations, so the two remain
  unspent.
