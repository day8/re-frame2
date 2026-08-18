# The bisect is flat, and the floor has a second mode the certificate does not see

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-9jrhi`, whose operative brief is
its 2026-08-17 13:42 comment: run the floor arm at B = 24 under `:p0/write-all`
at revisions spanning 2026-08-08 → 2026-08-13, **on one instrument throughout**,
as a hypothesis test of the event-pipeline candidate that `rf2-nkeba` left
standing. Measured 2026-08-18 10:00–10:22 AUSEST, branch `worker/bisect-9jrhi`
off `88411ed803`, which is an ancestor of `origin/main`.

Runtime, beside every figure below: Chromium **147.0.7727.15** (the build pinned
by Playwright **1.59.1**, `playwright-core/browsers.json` revision 1217),
shadow-cljs **3.4.10** `release` on build id `:hicasso-bench`,
`:optimizations :advanced`, `goog.DEBUG false`, `--expose-gc`,
`--enable-precise-memory-info`, `:init-fn re-frame.bench.p0-app/-main`, Node
**v24.13.0**. Every reading is a browser reading.

**Nothing was widened and no rig file was edited.** `ALLOC_LEG_TOLERANCE` stayed
the declared 0.25 placeholder, `ALLOC_FALL_THRESHOLD_B` stayed 600,000,
`ALLOC_MIN_WRITES` stayed 6, W stayed 6 measured writes after one prime, stride
stayed 2. The only file this window changed in the working tree was
`implementation/core/src`, checked out whole at each bisect point and restored
to `88411ed803` afterwards.

## The answer, first

**The bisect is FLAT. No ~1 – 3.5 KB drop is located anywhere in
2026-08-08 → 2026-08-13, and the event-pipeline candidate is not supported —
the one commit carrying the whole event-pipeline change moves `F_old` by
+296 B on `reagent-subs` and +284 B on `uix-subs`, which is the wrong sign and
an order of magnitude too small.**

**And the reason the search had something to find is an instrument property, not
a substrate one. Two control-passing runs at the IDENTICAL revision, on the
identical instrument, differ by exactly 3,792 B per write on both segments.**
That spread is larger than the entire 982 – 3,456 B effect this bead was sent to
bisect. Both readings certify: the leg witness sees a byte-stable cohort in each
case and refuses neither.

- **HEAD, 2026-08-13 and 2026-08-12-post all read the same figure.** The
  steady-state certified floor is 19,378 / 19,826 B per write at `88411ed803`,
  19,378 / 19,818 at `48c715f97c`, 19,386 / 19,824 at `a158c40288` — a spread of
  8 B on `reagent-subs` and 8 B on `uix-subs` across five days of substrate
  change. Nothing after 2026-08-12 moved this quantity at all.
- **The single event-pipeline commit is priced, and it ADDS.**
  `a158c40288` (`feat(core): refuse a malformed effect-map envelope at the
  final-effects boundary`) is the only commit in the interval that touches
  `events.cljc` or `router.cljc` at all, and it touches those and `fx.cljc`
  together — 295 / 221 / 49 lines. (One other commit, `04543067d9`, touches
  `fx.cljc` alone, by 21 lines; it sits between points M and B, across which
  the quantity moves 8 B.) Pooling the two low-mode readings either side of it, the
  floor moves **+296 B** on `reagent-subs` and **+284 B** on `uix-subs`. Taken
  one edge at a time it is +322 / +284 across the immediate parent boundary and
  +286 / +284 against 2026-08-08's own low-mode reading — a range narrower than
  the step itself.
- **The 2026-08-08 substrate is not high.** Two of its three runs read
  19,100 / 19,540; the third read 22,892 / 23,332. The high reading is the
  outlier, and its own run contains the refutation — rounds 1 – 3 of that run
  read 18,908 – 18,980 on `reagent-subs`, i.e. LOW, and the run steps to the
  high mode at round 4 and stays there. A property of the revision would have
  been present from the first round.
- **The mode gap is a page-global constant, to the byte.** 22,892 − 19,100 =
  **3,792**; 23,332 − 19,540 = **3,792**. The same number on two segments that
  share nothing but the page.
- **The prime term did not move and is not the carrier.** The first-leg excess
  `rf2-oiy1` removes reads a certified-window median of 6,801 – 6,864 B at every
  one of the six control-passing runs, revision notwithstanding.
- **Both of the structural blind spots this bead was sequenced behind are now
  CLOSED.** `rf2-erre5` (PR #8452, `397c789db1`) retains the whole raw sample
  stream, so the prime region's gap step and the absolute heap level are both
  recoverable from a committed dataset. Neither was, three days ago.

**UNRESOLVED, and stated as such.** Whether the second mode is reachable at
*every* revision or only at some. It was observed once, in eight runs, all
three of which at `4a1537cb71` — one high, two low — against zero of four
elsewhere. One in three versus zero in four decides nothing. What is
established is that the mode exists, that it certifies, and that its size
exceeds the effect being bisected; what is not established is whether the
substrate can reach it.

## What was held fixed, and what moved

"On one instrument" is this bead's title and its whole constraint, so it is
stated as an operation rather than as an intention. The instrument is the tree
at `88411ed803`. At each bisect point `implementation/core/src` was deleted and
re-checked-out at the point's own commit; **nothing else in the tree was touched
at any point**, and after the last run it was restored to `88411ed803` and
verified clean.

The pieces that stayed byte-identical across all eight runs, with the object
each was read from:

| File | Blob hash |
|---|---|
| `core/test/re_frame/bench/p0_run.cjs` | `e88d2be45efd59d023a9d23da9e4ff1f9800b5c0` |
| `core/test/re_frame/bench/p0_arms.cljs` | `beced24315f740eede28cf5f32f855ff91bbd854` |
| `core/test/re_frame/bench/p0_heap.cljs` | `2d922d31f86bcafb251c7c8d5b9cab458e31df28` |
| `core/test/re_frame/bench/p0_floor.cljs` | `6b61e125f4bd4c479be9438b55d04c1d8d20e601` |
| `core/test/re_frame/bench/p0_fixture.cljc` | `de27135ce820229e782b86628c42f7fcca2b899f` |
| `shadow-cljs.edn` | `b571794efeae2f10e15505edfbb3eded7a535b0e` |
| `package-lock.json` | `c31fa9aff01d1b8c3e87fb0d60ea894712ede25f` |
| `adapters/reagent/src/re_frame/adapter/reagent.cljs` | `9a77ecbf255938778e28f7fd6e70ccc1494f8c92` |
| `adapters/uix/src/re_frame/adapter/uix.cljs` | `544392f4e3afddd5cb2414a286bb73232918a5ff` |

The Reagent adapter's source is the same object at all four bisect points as
well as at HEAD, so holding it fixed costs the `reagent-subs` column nothing.
The UIx adapter's source differs from HEAD only at `4a1537cb71`, and only by a
docstring.

**The trunk did not move a rig file mid-window.** `origin/main` advanced from
`88411ed803` to `98d056d959` while the runs were in flight; the whole of that
advance is `.beads/issues.jsonl`, and the diff over `implementation/core/src`,
the bench trees, `shadow-cljs.edn`, `deps.edn` and the package manifests is
empty.

**What this design does NOT vary, and therefore does not test.** The intervention
is `implementation/core/src` and nothing else. A drift living in the adapters,
in Hicasso, in the bench harness itself or in the resident Chromium binary is
outside it. That is deliberate: the bead names the event pipeline as the
candidate under test, and the event pipeline is in `core/src`.

## The bisect points

The interval contains eleven commits under `implementation/core/src`, and their
shape is what makes a four-point bisect enough: **exactly one of them touches
`events.cljc` or `router.cljc`, and that one commit carries the whole of the
interval's event-pipeline change.** A second, `04543067d9`, touches `fx.cljc`
alone by 21 lines; it falls between points M and B below and is bracketed by
them.

| Point | Commit | Position | What its `core/src` delta is, from the point above |
|---|---|---|---|
| A | `4a1537cb71` | 2026-08-08, the one window that committed a dataset | — |
| P | `9d20be1d00` | parent of the pipeline commit | `use_frame.cljs` (a React hook's memo key) + `source_coords` (comments) |
| M | `a158c40288` | the pipeline commit | `events.cljc` 295, `router.cljc` 221, `fx.cljc` 49 — and nothing else |
| B | `48c715f97c` | 2026-08-13, last `core/src` commit of the interval | `test_support`, `frame`, `error_emit`, `trace/projection`, `adapter/context`, `substrate/spine` |
| — | `88411ed803` | HEAD, the anchor to today | five further days |

`9d20be1d00` is named by its DAG position, not by its date: this repository
rebase-merges, so the parent carries a later author timestamp than its own
child. It is the parent, and `git diff` across that one edge is the three
pipeline files alone.

Neither of the two files between A and P is on the floor arm's measured path.
`source_coords.cljc` changed in comments only (`dd9c2a287e`, a docs commit), and
the real code in `use_frame.cljs` is inside the `use-frame` React hook, which a
floor arm built by hand out of `react/createElement` never calls.

## The run roster, and the count was fixed before the deciding runs

Eight runs. The first is a pilot at six rounds, declared a pilot the moment it
showed three certified windows per segment against an effect of one to three
kilobytes, and it is excluded from every figure above. The bisect proper is
seven runs at eighteen rounds under one configuration. **When the first attempt
to replicate point A failed its own positive control, exactly two further runs
were declared in advance — a third attempt at A and the HEAD anchor — and
exactly two were taken. No run was added after seeing a result and none was
dropped.**

| # | `core/src` at | Rounds | Positive control | Dataset |
|---|---|---|---|---|
| 1 | `88411ed803` | 6 | OK | `pilot-rounds6-head-88411ed803.json` (pilot, excluded) |
| 2 | `4a1537cb71` | 18 | OK | `bisect-1-a-4a1537cb71.json` |
| 3 | `a158c40288` | 18 | OK | `bisect-2-m-a158c40288.json` |
| 4 | `48c715f97c` | 18 | OK | `bisect-3-b-48c715f97c.json` |
| 5 | `9d20be1d00` | 18 | OK | `bisect-4-p-9d20be1d00.json` |
| 6 | `4a1537cb71` | 18 | **FAILED** | `bisect-5-a-4a1537cb71-replicate.json` (figures not quoted) |
| 7 | `4a1537cb71` | 18 | OK | `bisect-6-a-4a1537cb71-replicate2.json` |
| 8 | `88411ed803` | 18 | OK | `bisect-7-head-88411ed803.json` |

Run 6's control read **12.30 B/double direct and 15.04 B/double differential
against a predicted 8**, one control window having read 84,080 B where 8,000 was
predicted. The controls arbitrate, so its arm figures appear nowhere in this
page's conclusions — which costs the argument nothing, because they agree with
run 7's to the byte on both segments.

Every run exited **1** as captured by the invoking shell. That is the expected
code and not a failure of the window: `--only alloc` exits non-zero whenever any
window is refused or any collection falls inside a measured one, and both are
routine at this page. The controls, the read-back verification and the
per-window certificate are what decide admissibility here, and they are reported
per run below.

## The estimand

`F_old(rev, segment)` = **the median, over CERTIFIED floor windows at round index
≥ 6, of that window's `legMedian`** — the median of its six measured work legs —
at B = 24 (4 roots × 6 cells), under `:p0/write-all`, with the prime work unit
excluded, on the instrument above.

Three choices in that, each forced by something in the data rather than by
taste:

- **Certified windows only.** A refused window under-reads by an unknown amount
  and the row says so; the whole apparatus exists to keep those out.
- **`legMedian` rather than `rise/W`.** They agree to a few tens of bytes on a
  certified window, and `legMedian` is the statistic `rf2-nkeba` stated the
  2026-08-13 window in. The driver's own printed `B/write` column is **not** this
  quantity — it is taken over every window including the refused ones, which is
  why the six-round pilot printed 36,731 B/write on a page whose certified
  windows read 19,427.
- **Round index ≥ 6.** `legMedian` is a function of the round index in the early
  rounds at every revision measured, and at one of them it takes a step of
  nearly four kilobytes at round 4. A cut two rounds past that step is the
  cheapest way to stop the estimator averaging two regimes. The full
  round-by-round series is printed below so the cut can be second-guessed.

## The readings

Steady-state certified floor, `legMedian` median with the range over the
certified windows at round ≥ 6, B = 24, `:p0/write-all`, all on the one
instrument. `n` is certified windows of twelve.

| `core/src` at | Run | `reagent-subs` B/write | n | `uix-subs` B/write | n |
|---|---|---|---|---|---|
| `4a1537cb71` | 2 | **22,892** [22,892 – 23,122] | 9 | **23,332** [23,332 – 23,356] | 10 |
| `4a1537cb71` | 7 | **19,100** [19,100 – 19,124] | 10 | **19,540** [19,540 – 19,564] | 8 |
| `9d20be1d00` | 5 | 19,064 [19,028 – 19,140] | 9 | 19,540 [19,468 – 19,580] | 8 |
| `a158c40288` | 3 | 19,386 [19,372 – 19,622] | 10 | 19,824 [19,812 – 19,856] | 11 |
| `48c715f97c` | 4 | 19,378 [19,372 – 19,400] | 11 | 19,818 [19,812 – 19,840] | 9 |
| `88411ed803` | 8 | 19,378 [19,372 – 19,774] | 9 | 19,826 [19,812 – 19,856] | 8 |

Every row in that table carries `0 unverified` on both the mount read-backs and
the warm-write read-back, and a positive control adjudicated `ok`. Collections
inside measured windows ran 7 – 12 of 36 windows per run and refusals 8 – 14 of
36; the refused windows are excluded rather than netted.

Read down the table twice. Down the last four rows the quantity moves by a few
hundred bytes across a boundary that includes every line of the event pipeline
that changed in the interval, and by **8 B** across the five days from
2026-08-12 to HEAD. Across the first two rows — the *same* commit, the *same*
instrument, twelve minutes apart — it moves by **3,792 B**.

### The pipeline step, grouped

Setting run 2 aside as the high-mode reading, the low-mode rows fall into two
groups either side of `a158c40288`, and the groups are far tighter than the
distance between them:

| Segment | Pre-pipeline (`4a1537cb71` run 7, `9d20be1d00` run 5) | Post (`a158c40288`, `48c715f97c`, `88411ed803`) | Step |
|---|---|---|---|
| `reagent-subs` | 19,100 / 19,064 — spread **36 B** | 19,386 / 19,378 / 19,378 — spread **8 B** | **+296 B** |
| `uix-subs` | 19,540 / 19,540 — spread **0 B** | 19,824 / 19,818 / 19,826 — spread **8 B** | **+284 B** |

Five independent browser launches across five commits, and the within-group
spread is an order of magnitude under the step. So the pipeline commit's price
is *well resolved* — **conditional on the low mode**, which is the only mode
these five runs were in. It is also small, positive, and consistent across two
segments that share no view layer: the commit that replaced a per-event
`(vec (remove …))` over the effect map's keys with a pure first-defect validator
at the router boundary **added** about 290 B per write rather than removing
three and a half kilobytes.

## The second mode

The round-by-round series is what turns the outlier into a finding. Certified
windows are unmarked; `*` marks a refused one, whose figure is printed for
continuity and enters nothing.

`reagent-subs`, `core/src` at `4a1537cb71`, run 2:

```
r0 *19518   r1 *18944   r2  18980   r3  18908   r4 *22964   r5  22932
r6  23122   r7  22908   r8 *22908   r9 *22892  r10  22892  r11 *22892
r12 22892  r13  22892  r14  22892  r15  22892  r16  22892  r17  22892
```

`reagent-subs`, the same commit, run 7:

```
r0 *19634   r1 *18944   r2  18980   r3  18962   r4 *19712   r5  19140
r6  19124   r7  19116   r8  19116   r9 *19100  r10  19100  r11  19100
r12*19100  r13  19100  r14  19100  r15  19100  r16  19100  r17  19100
```

The two runs are indistinguishable for four rounds and then separate by 3,792 B
for the remaining fourteen. `uix-subs` does the same thing in the same run at the
same round, by the same 3,792 B.

**Both modes certify, and neither is a collection artefact.** Taking two windows
from run 2:

| Round | Mode | Certified | Refusals | Falls | Legs |
|---|---|---|---|---|---|
| 2 | low | yes | 0 | 0 | 18980, 19492, 18980, 18980, 18980, 18980 |
| 12 | high | yes | 0 | 0 | 22892, 22892, 22892, 22892, 22892, 23640 |

Six repetitions of one work unit, byte-stable to within the leg tolerance, no
falling step, on one page — twice, at two levels 3,792 B apart. The leg witness
asks whether a window's legs are alike, and in both windows they are. It has
nothing to say about which of two levels the window sits at, and that is not a
defect in the witness so much as a question nobody had asked it.

**It is not the heap trajectory.** The absolute used-heap level at each window's
opening — recoverable for the first time, because `rf2-erre5` retains
`samples[0]` — tracks within 8 KB between the high run and a low one across all
eighteen rounds, ending at 6,191,823 B against 6,184,236 B. The two runs climb
the same heap and allocate different amounts per write on it.

**What the mode IS was not identified, and identifying it needs an instrument
this window may not build.** Three candidates are open and none is preferred: a
V8 tier or deoptimisation transition in the compiled write path, a page-global
allocation the counter attributes to the leg, and a `:advanced` build artefact.
**Filed as `rf2-77gz8` rather than chased** — a measurement window may not
improve the rig it is measuring on, and every reading above would have been
taken on a different instrument from the ones after the fix.

## What this rules out, and what it does not

**Ruled out.** That the event pipeline is where the published across-time gap
went. The interval's whole `events.cljc`/`router.cljc` change is isolated to a
single commit, priced across its own parent edge on one instrument, and it is
+296 B and +284 B — positive, and about a ninth and a twelfth of the effect. The
one further `fx.cljc`-only commit is bracketed by two points 8 B apart. A drift of 1 – 3.5 KB in
`implementation/core/src` between 2026-08-08 and 2026-08-13 is not there to be
found.

**Ruled out.** That the quantity kept moving after 2026-08-12. From
`a158c40288` to `88411ed803` it moves 8 B on both segments.

**NOT ruled out, and now with a mechanism behind it.** That the published
across-time comparison was reading a mode rather than a trend. This page does not
show that the 2026-08-08 figures were taken in the high mode — that dataset
predates the retention that would settle it, and its window-level scalars do not
distinguish the two. What it does show is that a 3,792 B step is available on
this rig, that it certifies, and that a single run cannot tell which side of it
the run is on. Any `F_old` figure quoted from one run is therefore quoted at an
uncertainty larger than the difference `rf2-nkeba` was trying to resolve, whichever
direction that difference ran.

**Still unresolved, exactly as `rf2-nkeba` left them.** The resident-runtime
candidate and the selection effect. This window varied neither: the Chromium
build was constant across all eight runs by construction, and the certified
subset is still a subset chosen by the leg witness.

## Reproduction

From `implementation/`, on the instrument tree at `88411ed803`, one bisect point
at a time:

```bash
# put the substrate at the point, holding everything else at HEAD
rm -rf implementation/core/src
git checkout 4a1537cb71 -- implementation/core/src

# the floor arm, B = 24, write-all, eighteen rounds
P0_PORT=8449 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-9jrhi/bisect-1-a-4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# and put it back
rm -rf implementation/core/src
git checkout HEAD -- implementation/core/src
```

The eight datasets are committed beside this page under
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-9jrhi/`, on the
convention `rf2-2rtt6.138` set and `rf2-erre5` wrote down. Each retains every
window's raw sample stream, so the estimator above can be re-derived, and a
different one driven, without re-running a browser.

## Related

- [The control's target is not the quantity it is read against](the-controls-target-is-not-the-quantity-it-is-read-against.md)
  — `rf2-nkeba`, which this bead follows on from and whose residual term it
  leaves unresolved for a different reason than it found.
- [The floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md)
  — where the across-time clause failed, and the source of the 2026-08-08 targets.
- [The arms' spread does not collapse (τ refused)](the-arms-spread-does-not-collapse.md)
  — the round-indexed dispersion this page's round-4 step sits beside.
- [The leg dispersion is in the dispatch site](the-dispersion-is-in-the-dispatch-site.md)
  — the by-site instrument, and the loaded-versus-quiet finding that made this
  window dispatchable beside a working fleet.
