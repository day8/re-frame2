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

## Result

*This section is written after the fifteenth run and not before it.*
