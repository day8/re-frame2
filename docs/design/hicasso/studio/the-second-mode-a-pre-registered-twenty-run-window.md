# The second mode: a pre-registered twenty-run window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-77gz8` — the floor arm's steady-state
`rise/W` is **bimodal at exactly 3,792 B** and **both modes certify**, so the leg
witness cannot tell them apart and any single-run figure quoted from this arm is a
coin toss between two stable levels about 20% apart.

Written 2026-08-18 on `worker/bimodal-77gz8`, off `58cf2df4f1`, which is an
ancestor of `origin/main`.

**This page is a MEASUREMENT WINDOW with a stopping rule fixed in advance.** The
run count below was written down and committed *before the first run was taken*,
and exactly that many runs were taken regardless of what they showed. A series
that stops when it gets a good answer is not a measurement, and the mode's low
rate makes that failure mode the live one here rather than a theoretical one.

## The question, and the instrument that answers it

`rf2-77gz8`'s re-analysis narrowed the mode to **two** surviving candidates and
showed the committed corpus cannot separate them:

- **(a) DIFFERENT WORK per write** — a re-entrant registration, a duplicated
  reaction, an extra pass.
- **(b) A V8 TIER/DEOPT in the compiled write path** — most specifically a loss
  of escape analysis, which turns elided allocations into real ones at a fixed
  cost per invocation.

`rf2-n1b9h` (PR #8505, merged to `main` as `408dfb0aa8`) built the instrument that
splits them: three monotone counters — event-handler invocations, subscription
recomputations, boundary renders — read at every measured window's open and close
and recorded as `work0` / `work` / `workDelta`. **So the decision rule is a read,
not a measurement**: any run that REACHES the high mode answers the bead from its
own record.

| Reading | Verdict |
|---|---|
| Work counts DIFFER across the step | **(a) established**, (b) excluded |
| Work counts IDENTICAL across the step | **(a) excluded**, residue is per-invocation allocation |

**The hard part is reaching the mode**, and it is the only hard part.

## Pre-registration

**Declared before the first run, and committed in this file before the runner was
invoked once.**

| Field | Value |
|---|---|
| **Run count** | **20 runs, taken regardless of outcome** |
| Substrate revision | `implementation/core/src` at `4a1537cb717dc6660aa449642f198a2cc970c93b`, an ancestor of `origin/main` |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) |
| Census | `P0_WORK_COUNT=1` on every run |
| Estimator | the **median**, over **certified** windows at **round index ≥ 6**, of that window's `legMedian` — per segment |
| High-mode criterion | either segment's estimator at or above **21,000 B/write**, midway between the two observed levels |
| Stopping rule | **exactly 20**; the series does not stop early on a positive and does not extend on a null |

### Why twenty, and why all at one revision

The rate is re-derived from the committed corpus rather than taken from prose.
Every 18-round floor run ever committed, scored by the estimator above:

| Revision | Runs | High-mode runs |
|---|---|---|
| `4a1537cb71` | 7 | **1** (`alloc-9jrhi/bisect-1`) |
| every other revision measured | 7 | 0 |

So the observed rate **at the revision where the mode has ever appeared** is
**1 in 7 (≈14%)**, not the "one in three" the earlier corpus suggested — six runs
at that revision from `rf2-n1b9h` have since come back low and diluted it. Twenty
runs at p = 1/7 gives **P(at least one high) = 1 − (6/7)²⁰ ≈ 95%**.

All twenty go at `4a1537cb71` because it is the **only** revision with a positive
observation. If the mode is revision-dependent that is where it lives; if it is
revision-independent, concentrating there costs nothing.

**What a null bounds.** Zero high runs in twenty puts a 95% upper bound of
**p ≤ 14%** on the mode's per-run rate at this revision from this series alone,
which excludes the one-in-three rate the earlier corpus suggested.

### The rig is not touched

The rig blobs are the constancy guarantee the whole published allocation series
rests on. Nothing under `implementation/core/test/re_frame/bench/` is modified by
this window. The census is off at compile time by construction — `counting?` is a
`goog-define` defaulting to false and all three call sites are macros — so an
unarmed build compiles the bundle this rig compiled before the counters existed.

| File | Blob at this page's base |
|---|---|
| `core/test/re_frame/bench/p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` |
| `core/test/re_frame/bench/p0_heap.cljs` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` |
| `core/test/re_frame/bench/p0_workcount.cljc` | `033f00470c380a17664a1dabffa0768f0e22c671` |
| `core/test/re_frame/bench/p0_fixture.cljc` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` |
| `core/test/re_frame/bench/p0_floor.cljs` | `3a14ff96414f9a77a7612f56181444155b582620` |

No bench file has changed since `408dfb0aa8`, so this window runs the instrument
that produced the `workcount-n1b9h` datasets.
