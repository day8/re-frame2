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

*Added by later commits on this branch. Until then this section is empty by
construction, and its emptiness is the pre-registration's proof.*
