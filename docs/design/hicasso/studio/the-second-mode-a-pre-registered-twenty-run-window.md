# The second mode: a pre-registered twenty-run window

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-77gz8` — the floor arm's steady-state
`rise/W` is **bimodal at exactly 3,792 B** and **both modes certify**, so the leg
witness cannot tell them apart and any single-run figure quoted from this arm is a
coin toss between two stable levels about 20% apart.

Written 2026-08-19 on `worker/bimodal-77gz8`, off `58cf2df4f1`, which is an
ancestor of `origin/main`.

**This page is a MEASUREMENT WINDOW with a stopping rule fixed in advance.** The
run count below was written down and committed *before the first run was taken*,
and exactly that many runs were taken regardless of what they showed. A series
that stops when it gets a good answer is not a measurement, and the mode's low
rate makes that failure mode the live one here rather than a theoretical one.

## The answer, first

- **THE MODE REPRODUCED.** Two of the twenty runs reached it — `run09` and
  `run19` — both with a **passing positive control** and **zero** unverified
  read-backs.
- **THE WORK COUNT IS IDENTICAL ACROSS THE STEP.** In both high-mode runs the
  census reads `events = 7, subs = 0, renders = 0` in **every round on both
  sides** of the transition, while the level moves by about 2,600 B/write.
- **So candidate (a) DIFFERENT WORK per write is EXCLUDED**, in every form the
  census can see, and **candidate (b) survives**: the residue is *per-invocation
  allocation* — the same work allocating more, which is a runtime codegen effect.
- **The comparison is WITHIN one run**, which is stronger than the between-run
  comparison the bead asked for. Same page, same build, same schedule, same
  counter — the level moves and the work does not.
- **The arm is MULTI-modal, not bimodal.** The elevated level here is
  **+2,532 B/write**, not the +3,792 B the bead is named for. At least three
  settled levels now exist at this revision.
- **The controls never move**: 1,080 control windows across all twenty runs take
  exactly one value each — 16 / 8,064 / 3,264 — the same values the entire prior
  corpus takes.

## Pre-registration

**Declared before the first run, and committed in this file before the runner was
invoked once**, as commit `5927981798` on `worker/bimodal-77gz8`, authored off
`58cf2df4f1` — which is an ancestor of `origin/main` and is the anchor a fresh
clone can resolve.

| Field | Value |
|---|---|
| **Run count** | **20 runs, taken regardless of outcome** |
| Substrate revision | `implementation/core/src` at `4a1537cb717dc6660aa449642f198a2cc970c93b`, an ancestor of `origin/main` |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) |
| Census | `P0_WORK_COUNT=1` on every run |
| Estimator | the **median**, over **certified** windows at **round index ≥ 6**, of that window's `legMedian` — per segment |
| High-mode criterion | either segment's estimator at or above **21,000 B/write**, midway between the two observed levels |
| Stopping rule | **exactly 20**; the series does not stop early on a positive and does not extend on a null |

**Twenty runs were taken. Exactly twenty.** The second high-mode run arrived at
run 19; the series had already been committed to run 20 and did, and run 20 read
low. Nothing was stopped early and nothing was extended.

### Why twenty, and why all at one revision

The rate was re-derived from the committed corpus rather than taken from prose.
Every 18-round floor run committed before this page, scored by the estimator
above:

| Revision | Runs | High-mode runs |
|---|---|---|
| `4a1537cb71` | 7 | **1** (`alloc-9jrhi/bisect-1`) |
| every other revision measured | 7 | 0 |

So the observed rate **at the revision where the mode has ever appeared** was
**1 in 7 (≈14%)**, not the "one in three" the earliest corpus suggested — six runs
at that revision from `rf2-n1b9h` had since come back low and diluted it. Twenty
runs at p = 1/7 gives **P(at least one high) = 1 − (6/7)²⁰ ≈ 95%**.

All twenty went at `4a1537cb71` because it is the **only** revision with a
positive observation. **The realised rate was 2 of 20 (10%)**, consistent with
that prior.

## The reading

The bead's decision rule needs a high-mode window and a low-mode window read for
*how much ran* as well as *how much was allocated*. Both high-mode runs supply
**both windows inside themselves**: they hold the low level for rounds 1–3, ramp
across rounds 4–5, and settle at the high level from round 6.

| Run | Segment | Rounds 1–3 | Rounds 6–17 | Step | Work census, both sides |
|---|---|---|---|---|---|
| `run09` | `reagent-subs` | 19,004 | 21,632 | +2,628 | `7 / 0 / 0` |
| `run09` | `uix-subs` | 19,408 | 22,072 | +2,664 | `7 / 0 / 0` |
| `run19` | `reagent-subs` | 19,004 | 21,632 | +2,628 | `7 / 0 / 0` |
| `run19` | `uix-subs` | 19,444 | 22,072 | +2,628 | `7 / 0 / 0` |

Against the **settled** low-mode level the elevated level is a single discrete
number on both segments:

- `21,632 − 19,100 = 2,532`
- `22,072 − 19,540 = 2,532`

The same quantity on two segments that share nothing but the page — page-global
and discrete rather than proportional, exactly the signature the bead describes,
at a different magnitude.

### The census, across the whole series

| Population | Windows | Distinct `workDelta` (events / subs / renders) |
|---|---|---|
| Arm windows, all 20 runs | 720 | **one value**: `7 / 0 / 0` |
| Control windows, all 20 runs | 1,080 | **one value**: `0 / 0 / 0` |

`work0.events` advances by exactly 28 per segment-round in every run — high and
low alike — so the two modes did identical work in identical order, confirmed by
a monotone counter rather than inferred from the schedule.

### What this excludes, and what it does not

**Excluded**: a re-entrant registration (would raise `events`), a duplicated
reaction (would raise `subs`), an extra render pass (would raise `renders`). The
whole *counted* form of candidate (a) is gone.

**Not excluded, and stated plainly**: work that increments none of the three
counters — extra iterations inside one handler body, a longer path through the
same call. On the floor arm `subs` and `renders` are **structural zeros** (it
holds no subscription and re-renders nothing), so the census here rests on **one
counter**, and that counter is a **window sum over seven writes**, not a per-leg
reading. A within-window redistribution of work is invisible to it.

**What survives is therefore per-invocation allocation**: the same counted work
allocating a fixed extra amount per write. A V8 tier or deopt transition in the
compiled write path — most specifically a loss of escape analysis — remains the
candidate consistent with everything measured. **Nothing here establishes it.**
No dataset records V8 tier state, and this window did not build an instrument
that would.

## Three levels, not two

The bead is named for a 3,792 B second mode. This window found an elevated level
of **2,532 B** above the same low baseline, twice, at the same revision.

| Level | `reagent-subs` | `uix-subs` | Rise over low | Seen in |
|---|---|---|---|---|
| low | 19,100 | 19,540 | — | 18 of 20 here, and the prior corpus |
| **new** | **21,632** | **22,072** | **+2,532** | `run09`, `run19` (armed census) |
| high | 22,892 | 23,332 | +3,792 | `alloc-9jrhi/bisect-1` (unarmed) |

**So the rider is not a single fixed quantum**, and "bimodal" understates the
arm: it is multi-modal, and a future reading must not assume the step it finds is
3,792 B.

**One confound is open and must not be glossed.** The two runs here were taken
with the census **armed**; `bisect-1` was **unarmed**. `rf2-n1b9h` established
that arming does not move the **low** level — armed runs reproduce 19,100 and
19,540 to the byte — but it never reached the high mode, so **nothing establishes
that arming leaves the HIGH level alone**. Whether 2,532 and 3,792 are two
genuinely distinct levels, or one level shifted by the counter's presence in the
hot path, is **not decided here**. It is the obvious next question and it needs
unarmed runs, which cannot answer the work-count question at all.

## Admissibility

Nineteen of the twenty runs pass the positive control with zero unverified
read-backs. **`run12` failed its control** — 11.79 B/double direct and 14.19
differential against a predicted 8 — and is excluded from the level population.
It read the low mode, so excluding it does not touch the verdict. **Both
high-mode runs are admissible.**

Every run exits **1** as captured by the invoking shell. That is the expected code
and not a failure of the window, exactly as `rf2-9jrhi` and `rf2-n1b9h` recorded:
`--only alloc` exits non-zero whenever any window is refused or any collection
falls inside a measured one, and both are routine at this page. The controls, the
read-back verification and the per-window certificate are what decide
admissibility.

## A correction to the corpus, and a broken pin

**The bead's run numbers are chronological, not dataset labels**, and a reader who
matches them to filenames gets the wrong files. `rf2-77gz8`'s "run 2" is
`alloc-9jrhi/bisect-1-a-4a1537cb71.json` and its "run 7" is
`alloc-9jrhi/bisect-6-a-4a1537cb71-replicate2.json` — numbering the pilot as run 1
and counting in `generatedAt` order. Read as `bisect-2` and `bisect-7` the two
figures do not reproduce; read correctly every figure on the bead reproduces
exactly, including both runs sitting at `4a1537cb71` with passing controls.

**A blob pin in the corpus does not resolve.** The instrument table on
[The work count is a constant, and the mode did not
reproduce](the-work-count-is-a-constant-and-the-mode-did-not-reproduce.md) pins
`p0_workcount.cljc` at `1787bc0053772c6bd72c3b665db8e8f9be87b2cd`, which is in no
fresh clone. Both on that branch and as merged, the file's real blob is
`033f00470c380a17664a1dabffa0768f0e22c671`. The provenance gate classes blob
hashes as digests by context and does not check them for resolvability, which is
why it passed. Recorded, not repaired — the page is another bead's record.

## The rig is not touched

The rig blobs are the constancy guarantee the whole published allocation series
rests on. Nothing under `implementation/core/test/re_frame/bench/` was modified by
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

No bench file has changed since `408dfb0aa8`, so this window ran the instrument
that produced the `workcount-n1b9h` datasets.

## Reproduction

From the **repository root**. The substrate is checked out at the revision the
mode has been seen at; the file set is identical either side (88 files, none added
and none removed), so the plain checkout is exact and nothing is left behind.

```bash
git checkout 4a1537cb71 -- implementation/core/src

# repeated for runs 01..20
P0_WORK_COUNT=1 P0_PORT=8461 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-77gz8/run01-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

git checkout HEAD -- implementation/core/src
```

The twenty datasets are committed beside this page under
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-77gz8/`, on the
convention `rf2-2rtt6.138` set and `rf2-erre5` wrote down. Each retains every
window's raw sample stream and its work census, so both can be re-derived without
a browser.

## Related

- [The work count is a constant, and the mode did not reproduce](the-work-count-is-a-constant-and-the-mode-did-not-reproduce.md)
  — `rf2-n1b9h`, which built the census this page reads and refused for want of a
  high-mode window.
- [The second mode is per-write, and the controls never move](the-second-mode-is-per-write-and-the-controls-never-move.md)
  — `rf2-77gz8`'s re-analysis, which narrowed the mode to the two candidates this
  page decides between.
- [The bisect is flat and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md)
  — `rf2-9jrhi`, which found the mode and filed it rather than chasing it.
- [The dispersion follows the controls, not the round](the-dispersion-follows-the-controls-not-the-round.md)
  — `rf2-rs8q6`, whose once-per-window **+748 B** rider is a different and smaller
  term than this page's per-write step; do not conflate them.
