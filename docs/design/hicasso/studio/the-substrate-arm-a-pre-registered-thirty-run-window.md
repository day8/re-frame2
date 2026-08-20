# The substrate arm: a pre-registered thirty-run window

`rf2-csca8` asks which of three properties carries the ~1,050 – 1,224 B cluster:
the **uix substrate**, the **position** in the round, or the segment-order
**mode**. [The reversed fixed arm: a pre-registered fifteen-run
window](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md) closed the
POSITION arm — with `reagent-subs` moved into the second-driven slot the cluster
did not go with it — and left the SUBSTRATE arm open. It left it open for a
reason it stated plainly, and the reason was not a shortage of runs:

> **the pre-registered statistic is MASKED in the one cell the whole
> discriminator rests on.** It counts a window only when the in-band leg is that
> window's **worst**, and `fixed-reversed | uix-subs | pos0` has a median |worst
> leg| of **1,488 B** — above the top of the band — with 35 of its 68 windows
> worse than 1,224 B.

The reversed arm exists to move `uix-subs` to position 0. Position 0 is where the
~748 B rider lives ([the rider follows the position, not the
substrate](the-rider-follows-the-position-not-the-substrate.md)). So a per-window
**maximum** was always going to under-count in exactly the cell the substrate
question turns on, and that record said so rather than repairing it in place:
choosing the statistic after seeing which one gives the wanted answer is what
pre-registration exists to prevent.

**This window is that repair, done the only way it can honestly be done.** The
masking-free statistic is declared here, and the reader that computes it is
committed here, **before its runner is invoked once**.

## Pre-registration

**Declared before the first run of this window, and committed in this file and in
`alloc_cluster_carrier.cjs` before the runner was invoked once**, as the commit
this file was added in on `worker/revorder-csca8`, authored off
`c33c2477ec693173938dbba0e857401dd2138a47`, which is an ancestor of `origin/main`
and is the anchor a fresh clone can resolve.

| Field | Value |
|---|---|
| **Run count** | **30 runs, taken regardless of outcome** — 10 `fixed-reversed`, 10 `fixed`, 10 `parity` |
| **Order** | interleaved, one run at a time, cycling `fixed-reversed` → `fixed` → `parity`, ten times |
| Substrate revision | `implementation/core/src` at `921d9c99115bf5de9313ef2c86632d143a86c899`, the tree at `c33c2477ec` — **the identical substrate the fifteen-run window was taken on**, which is what lets the two sessions be pooled as well as read apart |
| Rig | `p0_run.cjs` at blob `ebb08f9f10171d8b67cecee98cb7e85c0a5b9e42`, **frozen for the window and not edited**. The `fixed-reversed` arm is used exactly as PR #8596 shipped it |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) — identical to `revarm-csca8`'s fifteen and `segorder-rs8q6`'s six |
| **Statistic** | **`any-leg`, the masking-free reading**: a collection-free window (`falls === 0`) counts when **any** of its legs deviates from that window's **own leg median** into the band, **whether or not that leg is the window's worst** |
| Readings printed beside it | both maxima — `signed-furthest` and `largest-positive` — **printed beside the primary, never instead of it**. `any-leg` is a strict superset of `largest-positive`, not a different population |
| Band | **1,050 – 1,224 B primary**, the bead's own; 1,000 – 1,300 B reported beside it. **Neither bound moves in either direction** |
| Admissibility | `alloc.controlVerdict.ok === true`. A refused run is **named**, never dropped |
| **Stopping rule** | **exactly 30**; the series does not stop early on a positive and does not extend on a null |
| τ | **untouched in either direction**. `rf2-e9wr`'s refusal stands and `rf2-rs8q6`'s fence against widening is restated |

### One timing pilot preceded this, and it is not in it

A single `parity` run was taken before this pre-registration was written, purely
to measure wall-clock and size the run count. It was written **outside the
repository**, it is **not committed**, and **no figure on this page reads it**.
It established one run at ≈ 74 s including the cold compile, which is what sizes
30 runs at ≈ 37 min of runner time.

### What is pre-registered here and what is not

The `any-leg` reading is the same arithmetic the fifteen-run window published
**under the label POST-HOC**, as its MASKING DIAGNOSTIC. Promoting it to primary
does not relabel data it was chosen after seeing, and this page does not pretend
otherwise:

- over **`data/revorder-csca8/`** — this window's own thirty records, none of
  which existed when the reading was declared — `any-leg` is **PRIMARY and
  CONFIRMATORY**;
- over **every record that predates this window**, `any-leg` stays **POST-HOC**,
  and the reader prints it under that label.

That distinction is the whole methodological content of this window. A pooled
figure over both populations is **descriptive** and is reported as such.

### The band, and the null-arm figure it rests on

The band is **not a tolerance and not inherited from a published constant**. It
is a description of the extremes of the cluster the bead observed, and it is
fenced: it may not move in either direction.

What makes it safe to read at all is the **null arm** — the control windows,
which run the same machinery, the same sampler and the same round schedule as
the arms and dispatch nothing. **Measured first-hand for this page at
`c33c2477ec` on 2026-08-21, over the whole committed corpus: 41,862 control
legs, of which 0 in the 1,000 – 1,300 B band.** That figure is this page's own
measurement, taken today, not a constant read off an earlier record.

**This matters because a published floor has moved.** `rf2-0eu1s` established
that the instrument's published non-cancellation floor no longer describes the
corpus it produces now: 23.2% of 164 null-arm cells sit above the published
45 B/boundary bar and the p90 is 61 B against a published 4.5 B. The median
still holds — the instrument still cancels in the centre — but the tail is what
the published bar was derived from. **A band computed as a multiple of a
published constant would therefore be wrong by construction here.** This one is
not computed that way: its floor of 1,050 B is roughly seventeen times that
measured p90, and the direct in-band null count is 0 of 41,862. The moved tail
does not reach this band, and the reason is stated rather than assumed.

**This window measures and publishes its own null arm before any comparative is
quoted over it.**

### The level read, declared as a read and not as a filter

The floor arm at this configuration is multi-modal — see [the second mode: a
pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md)
— so the level each run settled at is recorded before any comparative. The
estimator is that window's own, unchanged:

> the **median**, over **certified** windows at **round index ≥ 6**, of that
> window's `legMedian` — per segment; **high mode** is either segment at or above
> **21,000 B/write**.

**No run is excluded on it.** If the arms separate on level rather than on the
property under test, that is a finding about the window and is reported as one.

### The contrasts, declared in advance

**PRIMARY — CARRIER, and it is the only WITHIN-RUN contrast on this list.**

> Inside `fixed-reversed`, `uix-subs` at position 0 against `reagent-subs` at
> position 1, under `any-leg`, at the 1,050 – 1,224 B band.

Both cells come from the **same runs**, the same session, the same mode and the
same level, so every per-run term — the box that minute, the revision, the mode,
the floor the run settled at — is held constant **by construction rather than by
matching**. It is the one contrast on this page that does not carry the
between-run bound.

**SECONDARY, and labelled secondary wherever they appear.** Each is between-mode
or between-session and carries the repeated-measures bound in full:

| Contrast | Cells |
|---|---|
| FOLLOWS | `uix-subs` under `fixed` at pos1 against `uix-subs` under `fixed-reversed` at pos0 |
| STAYS | position 1 under `fixed` (`uix-subs`) against position 1 under `fixed-reversed` (`reagent-subs`) |
| MODE | `uix-subs` pooled, `fixed-reversed` against `parity` |
| SAME-SESSION POSITIVE CONTROL | `uix-subs` under `fixed` at pos1 in this session, against the same cell in `revarm-csca8` and `segorder-rs8q6` |

**Every window-level count is published beside its run-level counterpart**, and
the run-level census is the honest denominator: Fisher treats each window as an
independent trial and they are not, because a run contributes many windows and
every per-run term is shared across all of them. **No cluster-robust or
mixed-effects estimate is offered and none should be** — at ten clusters per arm
a variance component is not identifiable and a cluster-robust standard error is
badly biased.

### The three pre-registered outcomes

Fixed in this file before the runner ran once:

- **`uix-subs | pos0` carries the cluster at a rate above `reagent-subs | pos1`**
  → **the SUBSTRATE is a carrier.** The substrate moved position and the cluster
  went with it, inside a single mode, within the same runs.
- **the two cells are indistinguishable and both sit near the parity baseline**
  → **the substrate is NOT the carrier**, and what remains standing is the
  **forward fixed order specifically** — `reagent-subs` driven first and
  `uix-subs` second, every round — which is where the fifteen-run window left it.
- **both cells sit high** → **neither property is the carrier as stated**, and
  the masking-free reading is counting something the band does not isolate. That
  outcome refuses the term rather than assigning it, and refusing is the
  deliverable.

**None of the three is discharged by moving τ, the band, or the `falls === 0`
condition.** If the window cannot separate the cells, it says so.

## Reproduction

From the **repository root**, one run at a time — two heavyweight runs on one box
wedge rather than fail:

```bash
P0_PORT=8491 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 P0_ALLOC_SEG_ORDER=fixed-reversed \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/revorder-csca8/reversed-1.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

with `P0_ALLOC_SEG_ORDER` cycling `fixed-reversed` → `fixed` → `parity` and the
output name following it. Every figure below is re-derived from the committed
records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs --corpus
```

and this window alone is read by naming its records:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs \
  implementation/hicasso/test/re_frame/bench/hicasso/data/revorder-csca8/*.json
```

## Result

*Committed deliberately empty. This section is written after the thirtieth run
and not before it.*
