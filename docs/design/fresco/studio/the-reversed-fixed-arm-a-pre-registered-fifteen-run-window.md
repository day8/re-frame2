# The reversed fixed arm: a pre-registered fifteen-run window

`rf2-csca8` asks which of three properties carries the ~1,050 – 1,224 B cluster
that [the cluster follows the mode, and the third arm is still
missing](the-cluster-follows-the-mode-and-the-third-arm-is-still-missing.md)
read off the committed corpus: the **uix substrate**, the **position** in the
round, or the segment-order **mode**. That record separated two of the three and
refused the third, because inside `P0_ALLOC_SEG_ORDER=fixed` the substrate and
the position are perfectly confounded — position 0 is always `reagent-subs` and
position 1 is always `uix-subs`, in all 81 fixed windows the corpus holds.

`P0_ALLOC_SEG_ORDER=fixed-reversed` landed in PR #8596 to break that confound:
the same plan every round, so the mode is held constant, with `uix-subs` moved
to position 0. **The arm existed and had answered nothing** — no dataset had
ever been recorded through it. This page records the first one.

## Pre-registration

**Declared before the first run, and committed in this file before the runner was
invoked once**, as the commit this file was added in on `worker/revarm-csca8`,
authored off `cd0f9ce9bcd0d46295190c964b9972fa50aa77f5`, which is an ancestor of
`origin/main` and is the anchor a fresh clone can resolve.

| Field | Value |
|---|---|
| **Run count** | **15 runs, taken regardless of outcome** — 5 `fixed-reversed`, 5 `fixed`, 5 `parity` |
| **Order** | interleaved, one run at a time, cycling `fixed-reversed` → `fixed` → `parity`, five times |
| Substrate revision | `implementation/core/src` at `921d9c99115bf5de9313ef2c86632d143a86c899`, the tree at `cd0f9ce9bc` |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) — identical to `segorder-rs8q6`'s six runs |
| Statistic | the **worst leg** of a collection-free window (`falls === 0`) over that window's **own leg median**, in B |
| Worst-leg reading | **signed-furthest** primary, which is what the published `8 of 38` is stated in; **largest-positive** printed beside it, never instead of it |
| Band | **1,050 – 1,224 B primary**, the bead's own; 1,000 – 1,300 B reported beside it |
| Admissibility | `alloc.controlVerdict.ok === true`, as `alloc_cluster_carrier.cjs` already enforces. A refused run is **named**, never dropped |
| **Stopping rule** | **exactly 15**; the series does not stop early on a positive and does not extend on a null |
| τ | **untouched in either direction**. `rf2-e9wr`'s refusal stands |

### The level read, declared as a read and not as a filter

The floor arm at this configuration is known to be **multi-modal** — see [the
second mode: a pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md)
— so the level each run settled at is recorded before any comparative is quoted.
The estimator is that window's own, unchanged:

> the **median**, over **certified** windows at **round index ≥ 6**, of that
> window's `legMedian` — per segment; **high mode** is either segment at or above
> **21,000 B/write**.

**No run is excluded on it.** If the arms of this window separate on level rather
than on mode, that is a finding about the window and it is reported as one.

### The tests, named before the data

**PRIMARY, and it is a WITHIN-RUN contrast.** Inside the `fixed-reversed` runs
alone, `uix-subs` at position 0 against `reagent-subs` at position 1. Both cells
come from the same runs, the same session, the same mode and the same box, so
every per-run term is held constant by construction. This is the one contrast
the corpus could not supply at any n.

The outcomes were pre-registered in the rig source itself when the arm landed
(`p0_run.cjs`, above `ALLOC_SEG_ORDERS`), and are restated here verbatim rather
than chosen after the run:

- the cluster **FOLLOWS `uix` to position 0** → the carrier is the **substrate**
  under fixed;
- the cluster **STAYS at position 1** → the carrier is the **second-driven slot**,
  whichever substrate occupies it;
- **BOTH or NEITHER** → neither property is the carrier as stated.

**SECONDARY, and matched by session rather than pooled.** The five `fixed` and
five `parity` runs are taken in the same session and interleaved with the
`fixed-reversed` ones so that the mode contrast has a matched baseline. The
corpus's matched contrast is 8/38 against 1/23 at window level (p = 0.1344) and
**3 of 3 runs against 1 of 3 (p = 0.4) at run level**, which is no power at all;
the pooled p = 3.02e-9 spans 107 runs across several sessions, dates and
revisions and is recorded as **associated, not established at a matched
baseline**. The right answer to three clusters is more clusters, and that is what
these ten companion runs supply.

**No cluster-robust or mixed-effects estimate is offered here and none should
be.** With a handful of clusters per arm a variance component is not identifiable
and a cluster-robust standard error is badly biased. The run-level census is
published beside every window-level p instead.

## Reproduction

From the **repository root**, one run at a time — two heavyweight runs on one box
wedge rather than fail:

```bash
P0_PORT=8491 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 P0_ALLOC_SEG_ORDER=fixed-reversed \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/revarm-csca8/reversed-1.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

with `P0_ALLOC_SEG_ORDER` cycling `fixed-reversed` → `fixed` → `parity` and the
output name following it.

Every measured figure below is re-derived from the committed records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs --corpus
```

## The answer, first

**The SLOT hypothesis is REFUTED and the SUBSTRATE hypothesis is NOT SETTLED —
and the reason it is not settled is a defect in the pre-registered statistic, not
a shortage of runs.**

- **On the pre-registered statistic the answer is NEITHER.** Inside
  `fixed-reversed`, `uix-subs` at position 0 reads **1 of 68** and `reagent-subs`
  at position 1 reads **1 of 82**. The primary within-run contrast between them is
  p = 1.0000. The same-session `fixed` arm read **8 of 63** in four of its five
  runs over the same fifteen-run session, so the instrument was not asleep.
- **But that statistic is MASKED in the one cell the whole discriminator rests
  on.** It counts a window only when the in-band leg is that window's **worst**,
  and `fixed-reversed | uix-subs | pos0` has a median |worst leg| of **1,488 B** —
  above the top of the band — with 35 of its 68 windows worse than 1,224 B. The
  competing term is not a mystery: **position 0 is where the ~748 B rider lives**,
  and [the rider follows the position, not the substrate](the-rider-follows-the-position-not-the-substrate.md)
  established that it is position-locked rather than substrate-locked.
- **On a masking-free companion the cluster DOES follow `uix`, at about a third
  the rate.** Counting windows carrying an in-band leg whether or not it is the
  worst: `fixed | uix-subs | pos1` 20.8%, `fixed-reversed | uix-subs | pos0`
  **7.4% in four of five runs**, `parity | uix-subs | pos0` 1.3%,
  `fixed-reversed | reagent-subs | pos1` 1.2%. **That companion is POST-HOC** —
  written after this window was read — and it is labelled so everywhere it
  appears. It cannot carry a verdict on its own and none is rested on it.
- **What both readings agree on**: putting `reagent-subs` in the second-driven
  slot does not move the cluster there. On the pre-registered statistic that is
  8/63 against 1/82 (p = 0.0105); on the companion, 10/63 against 1/82
  (p = 0.0011).

**So the bead stays OPEN**, with a sharper question than it had and a named
defect in the instrument that was supposed to answer it.

### The methodological finding, which was knowable in advance

**The discriminator's design and its pre-registered statistic are in tension.**
The reversed arm exists to move `uix-subs` to position 0. Position 0 is where a
second, already-recorded term sits. The statistic is a per-window **maximum**, so
the two terms compete for it and the larger one wins. A reversed-arm read was
therefore always going to under-count at `uix-subs`, and nothing about that
needed the data to see — the rider record predates this window by days.

It is recorded here rather than repaired, because repairing it means
**pre-registering a masking-free statistic and taking the window again**, and
choosing a statistic after seeing which one gives the wanted answer is exactly
what pre-registration exists to prevent.

## The window

Fifteen runs, one session, one revision, one box, interleaved one at a time.
**All fifteen were taken; none was dropped, extended or re-rolled.**

| | `fixed-reversed` | `fixed` | `parity` |
|---|---|---|---|
| runs | 5 | 5 | 5 |
| control refused | 0 | 0 | 0 |
| arm windows | 180 | 180 | 180 |
| collection-free | 150 | 135 | 145 |

540 arm windows, **430 collection-free**, all 430 in the position tables
(`controlSlot = first` throughout), **0 control-refused**.

**Every run exited 1**, on the falls gate (4 – 16 collections inside measured
windows) and the leg-tolerance gate (9 – 24 windows) — the normal verdict for
this row, and by construction outside the statistic, which is defined over
`falls === 0` windows only. **No gate was widened or narrowed and τ was not
touched in either direction.**

### The arm did what it says

A positive control on the rig rather than on the finding: every round of every
`fixed-reversed` run drove `uix-subs` then `reagent-subs`, and every round of
every `fixed` run drove `reagent-subs` then `uix-subs`, while `parity`
alternated. Pinned in the reader's self-test.

### The level read, before any comparative

| level (reagent / uix, B/write) | runs | which |
|---|---|---|
| 19,370 – 19,384 / 19,816 – 19,824 | 12 | four `fixed`, four `parity`, four `fixed-reversed` |
| 21,844 / 22,278 – 22,284 | 2 | `fixed-5`, `reversed-1` |
| 23,068 / 23,508 | 1 | `parity-2` |

**The three high-mode runs are ONE PER ARM**, so the level is not confounded with
the property under test. **No unexplained excursion**: every level here sits
inside the corpus's already-documented modes, and `reversed-1` settled on the
**identical** `reagent-subs` level to `segorder-rs8q6/fixed-1` and agrees with it
to **6 B** on `uix-subs` — across two sessions, two dates and two segment-order
modes, which is the strongest single check available that this is the same
instrument on the same estimand.

**The null arm carries nothing**: 4,818 control legs in this window, **0** in the
1,000 – 1,300 B band.

## The census

Worst leg per collection-free window over its own leg median, **signed-furthest**,
in the primary 1,050 – 1,224 B band. This window only.

| mode \| segment \| position | windows | in band | rate | runs w/ a hit |
|---|---|---|---|---|
| `fixed` \| `uix-subs` \| pos1 | 63 | **8** | 12.7% | **4 of 5** |
| `fixed` \| `reagent-subs` \| pos0 | 72 | 1 | 1.4% | 1 of 5 |
| `fixed-reversed` \| `uix-subs` \| pos0 | 68 | 1 | 1.5% | 1 of 5 |
| `fixed-reversed` \| `reagent-subs` \| pos1 | 82 | 1 | 1.2% | 1 of 5 |
| `parity` \| `uix-subs` \| pos0 | 41 | 0 | 0.0% | 0 of 5 |
| `parity` \| `uix-subs` \| pos1 | 32 | 0 | 0.0% | 0 of 5 |
| `parity` \| `reagent-subs` \| pos0 | 31 | 0 | 0.0% | 0 of 5 |
| `parity` \| `reagent-subs` \| pos1 | 41 | 0 | 0.0% | 0 of 5 |

Read as **largest-positive** the `fixed | uix-subs | pos1` cell is 9 of 63 rather
than 8; every other cell is identical under both readings. The record quotes
signed-furthest because that is what the published `8 of 38` was stated in.

| contrast, primary band | window | p | run level | p |
|---|---|---|---|---|
| **CARRIER** — `fixed-reversed`, uix pos0 vs reagent pos1 | 1/68 vs 1/82 | 1.0000 | 1 of 5 vs 1 of 5 | 1.0000 |
| **FOLLOWS** — uix, `fixed` pos1 vs `fixed-reversed` pos0 | 8/63 vs 1/68 | 0.0142 | 4 of 5 vs 1 of 5 | 0.2063 |
| **STAYS** — pos1, `fixed` uix vs `fixed-reversed` reagent | 8/63 vs 1/82 | 0.0105 | 4 of 5 vs 1 of 5 | 0.2063 |
| **MODE** — uix pos1, `fixed` vs `parity` | 8/63 vs 0/32 | 0.0483 | **4 of 5 vs 0 of 5** | **0.0476** |
| **MODE** — uix pooled, `fixed-reversed` vs `parity` | 1/68 vs 0/73 | 0.4823 | 1 of 5 vs 0 of 5 | 1.0000 |

### The masking diagnostic, POST-HOC

Written after this window was read. **ANY-LEG** counts a window carrying an
in-band leg whether or not it is that window's worst; **share** is how often it
IS the worst, which is the masking rate itself.

| cell, this window | windows | any-leg | worst-leg | share | median \|worst\| |
|---|---|---|---|---|---|
| `fixed` \| `uix-subs` \| pos1 | 63 | 10 | 8 | 80% | 1,056 B |
| `fixed-reversed` \| `uix-subs` \| pos0 | 68 | **5** | **1** | **20%** | **1,488 B** |
| `fixed-reversed` \| `reagent-subs` \| pos1 | 82 | 1 | 1 | 100% | 24 B |
| `fixed` \| `reagent-subs` \| pos0 | 72 | 1 | 1 | 100% | 748 B |

**Four of the five `fixed-reversed` runs carry an in-band `uix` leg at position
0**, against one of five carrying one at `reagent-subs`, and zero of five parity
runs at `uix-subs` in either position. The 20% share against `fixed`'s 80% is the
masking, stated as a number.

## Read against the corpus

With this window the admissible corpus is **131 runs / 3,688 collection-free /
3,520 positional**, against 116 / 3,258 / 3,090 before it. The two
control-refused runs are still named and still refused; nothing here relaxes
that rule.

| cell | windows | primary band | any-leg (post-hoc) |
|---|---|---|---|
| `fixed` \| `uix-subs` \| pos1 | 101 | 16 (15.8%) | 21 (20.8%) |
| `fixed-reversed` \| `uix-subs` \| pos0 | 68 | 1 (1.5%) | 5 (7.4%) |
| `fixed-reversed` \| `reagent-subs` \| pos1 | 82 | 1 (1.2%) | 1 (1.2%) |
| `fixed` \| `reagent-subs` \| pos0 | 115 | 1 (0.9%) | 2 (1.7%) |
| `parity` \| `uix-subs` \| pos0 | 774 | 8 (1.0%) | 10 (1.3%) |
| `parity` \| `uix-subs` \| pos1 | 744 | 3 (0.4%) | 21 (2.8%) |
| `parity` \| `reagent-subs` \| pos0 | 697 | 2 (0.3%) | 2 (0.3%) |
| `parity` \| `reagent-subs` \| pos1 | 939 | 0 (0.0%) | 0 (0.0%) |

On the post-hoc companion the reversed `uix` cell sits **between** the two:
7.4% against `fixed`'s 20.8% (p = 0.0179) and against `parity | uix | pos0`'s
1.3% (p = 0.0047). On the pre-registered statistic it sits at the parity floor,
for the masking reason above. **Neither reading is discarded and neither is
allowed to stand alone.**

### What this window does settle: the matched MODE baseline

The bead recorded MODE as *associated, not established at a matched baseline*,
because the strictly matched same-session contrast was 8/38 against 1/23
(p = 0.1344) at window level and **3 of 3 runs against 1 of 3 (p = 0.4)** at run
level — no power at all. This window adds a second matched session at **4 of 5
against 0 of 5, p = 0.0476**. Taken together the two matched windows are **7 of 8
`fixed` runs against 1 of 8 `parity` runs, p = 0.0101** — the first run-level
separation the record has had that controls session, date and revision.

**But "MODE" must now be read narrowly.** `fixed-reversed` is exactly as
non-alternating as `fixed`, and it reads 1.5% / 7.4% at `uix-subs` rather than
`fixed`'s 15.8% / 20.8%. So what separates from `parity` is **the forward fixed
order specifically** — `reagent-subs` driven first and `uix-subs` second, every
round — and not "a plan that does not alternate".

## What is NOT concluded

- **No mechanism is named**, here or anywhere in this window. What allocates
  ~1,056 B on some `uix-subs` legs is not identified and no candidate is offered.
- **No cluster-robust or mixed-effects estimate is offered**, and none should be:
  five clusters per arm will not identify a variance component and a
  cluster-robust standard error on that many clusters is badly biased. Every
  window-level p is published beside its run-level counterpart instead.
- **The post-hoc companion is not promoted.** It is reported because suppressing
  it would be dishonest, not because it settles anything.
- **τ is untouched in either direction.** `rf2-e9wr`'s refusal stands and
  `rf2-rs8q6`'s fence against widening is restated. No gate, threshold, band or
  budget moved.
- **The `parity | uix-subs | pos0` last-leg term is still nobody's.** It is the
  reason the 1,000 – 1,300 B band and the 1,050 – 1,224 B band do not answer the
  same question, it is untouched by this window, and it is described rather than
  chased.

## What would settle it

**A masking-free statistic, pre-registered as such, and the reversed arm taken
again under it.** The rig needs no change — the arm exists and this window shows
it drives correctly. What needs changing is the statistic the next window
declares in advance, and the choice must be made and committed before the runner
is invoked, exactly as this window's was.
