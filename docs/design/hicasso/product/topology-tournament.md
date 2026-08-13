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

**Positive control — the changed-set doubling.** Page size is held fixed and
the *changed set* is doubled: update every 10th row, then every 5th row, on the
same table. Element count, layout and paint are constant; only the commit-side
work doubles, so the prediction is **2.00x**, adjudicated on a band registered
here before the run: **[1.60x, 2.50x]**, and the difference and the slope must
both be **positive** (a control certifying that more dirty work reads *faster*
is refused on the sign, which is the fix `rf2-7iqb5` landed in PR #7634).

This is deliberately not the control this lane used before. Every earlier
positive control for a bulk or update row **scaled the page**, and `rf2-7iqb5`
records why that cannot certify an update row: the work does not scale with the
page, so even a perfect instrument reads below the predicted factor. The
changed-set form is that bead's own prescribed repair.

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
the clock half does not publish, and the reason is a control, not a judgement
about the machine.**

### 2.1 What ran, and on what

| | |
|---|---|
| instrument | `implementation/freehand/test/re_frame/bench/hicasso/topo/census_dom_cljs_test.cljs` |
| substrate | the bench's **arm-1** runtime, not `implementation/hicasso` |
| witness | `npm run test:browser` (Chromium, real DOM), 1,500 tests / 9,482 assertions |
| profile | P-DEV-1 |
| box | `\System\Processor Queue Length` read **0.00** on every sample taken before, during and after |

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

### 2.5 The budget verdict the deterministic half CAN reach

One registered budget line is deterministic, and this census adjudicates it
directly:

> **U5** — Narrow-update body work scales with changed rows, not all mounted
> rows. ([budgets §4](budgets.md#the-6-user-visible-budgets), family:
> **deterministic**)

| arm | U5 on sparse/edit | basis |
|---|---|---|
| `fine` | **meets** | 1 row built per 1 row changed, at every `B` |
| `virtual` | **meets** | 1 row built per 1 row changed |
| `chunked` | **meets, bounded by k** | 25 rows built, constant in `B` |
| `coarse` | **BREACHES, at every row count** | `B` rows built for one changed row |

`coarse` cannot satisfy U5 at any row count, and no amount of tuning changes
that: rebuilding the whole family from a single view-model **is** the arm. This
is a verdict on a registered line, taken on the family of budget that needs no
quiet box, and it is the tournament's firmest result.

### 2.6 The clock half — REFUSED, and on what

**No clock cell is published, and the refusal is located in the source rather
than in an opinion about the machine.**

The instrument was built to its pre-registration —
`topo/control_app.cljs` carries the changed-set doubling, and
`topo/model.cljs`'s `:topo/bump-stride` is the write it needs. The refusal is
not that it could not be built. It is that **the control is degenerate on two
of the four arms, by construction**, and this was discovered from the census's
own integers rather than from a run:

| arm | markup at stride 10 | at stride 5 | predicted factor |
|---|---|---|---|
| `fine` | `B/10` | `B/5` | **2.00 — discriminating** |
| `virtual` | 2 | 4 | **2.00 — discriminating** |
| `coarse` | `B` | `B` | **1.00 — degenerate** |
| `chunked` | `B` | `B` | **1.00 — degenerate** |

`coarse` and `chunked` rebuild every row whichever stride runs, so doubling the
changed set doubles nothing they do. A control predicting 2.00x on those arms
would refuse a healthy instrument; a control predicting 1.00x on them
discriminates nothing and certifies nothing. **Either way those two arms have
no positive control, so no clock figure of theirs may publish** — and since
every interesting comparison in this tournament crosses between a fine-family
arm and a coarse-family one, that withholds the clock table entire.

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
unmeasured, so nothing could be above them; the one line that was adjudicated
(U5) has a breach that is structural rather than tunable.

Dispositions, one per cell class, with no cell left open:

| cell class | disposition | statement |
|---|---|---|
| `coarse` / sparse, edit | **NARROW** | Coarse is not offered for workloads with narrow updates at any row count. Its classification narrows to bulk-dominated workloads where boundary-shell cost dominates row-build cost — a condition this tournament cannot itself certify, so the narrowed claim is bounded by `rf2-hic-018`. |
| `chunked` / reorder | **STOP** | Chunked is not offered for reorder-bearing workloads. It builds every row *and* runs `⌈B/k⌉` bodies, strictly worse than both neighbours on both counters. |
| `fine` / sparse, edit, reorder | **PASS** | Meets U5; builds 1 row for 1 changed row and 0 for a permutation. |
| `virtual` / all four | **PASS, on a different page** | Constant in `B`, and every figure is about a DOM that holds `W` rows rather than `B`. |
| all arms / bulk | **UNRESOLVED** | Not above its kill line; not below it either. The line is in milliseconds and no millisecond here has a control. It is recorded as unresolved and is **not** carried as an open hope: the follow-up is one named, bounded control (§2.6), not a benchmark programme. |

### 2.8 What was NOT concluded

- **No clock figure at all**, for any arm, operation or row count.
- **No verdict against U1, U2, U3, U4, C3 or C4** — all are millisecond lines.
- **No `rf2-hic-080` scoring.** Its phase 2 is deliberately another worker's,
  and a tournament that graded its own blinded predictor would destroy the
  property the split exists to protect. What this page hands over is the
  measured ordering on the work census plus the explicit note that the clock
  orderings are **unaddressed** in that page's own frozen vocabulary.
- **No package figure.** Everything here is arm-1.
- **No chunk-width result.** `k = 25` was a stated setting; nothing here is
  evidence about it, and §1.1's refusal to derive one stands.
