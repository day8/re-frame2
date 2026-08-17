# The control's target is not the quantity it is read against — `F_old` re-derived from the 2026-08-08 dataset

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-nkeba`, the follow-on to
[the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md),
which found V1's across-time clause failing by 16 – 20% and left the cause open.
Analysed 2026-08-17 21:00 – 22:30 AUSEST on `2e993181f8`, which is
`origin/main`.

**No browser was launched and no figure on this page is new data.** Every number
below is re-derived from artefacts already in the tree: the committed
2026-08-08 raw dataset, the two record pages that quote it, and the shipped
instrument's own source at two revisions. The one thing that was *run* is the
instrument's own pure gate, driven over reconstructed sample streams. That
choice is defended under [why nothing was measured](#why-nothing-was-measured).

## The answer, first

- **The clause compares two different quantities, and the difference is
  documented nowhere.** `24,108` / `24,730` are the **median** of `rise/W`
  across the six 2026-08-08 rounds, to the byte. The runner of the day printed
  the **mean**, which is `23,761` / `24,679`. Today's figures are neither: they
  are **per-window** values on a **certified subset**, with the prime leg
  excluded. Three differences — statistic, population, sample stream — none of
  them stated on any page that quotes the pair.
- **The 2026-08-08 windows are the population the prime was built to remove.**
  Under `rf2-oiy1`'s own model of the first-leg term, all twelve of them refuse
  today's leg witness, at worst deviations of **25.18% – 29.81%** against
  τ = 0.25. The clause therefore asks a certified quantity to reproduce a
  number that only uncertified windows produced.
- **A residual survives all of that, and it is smaller than published.** Against
  the single most favourable 2026-08-08 round with its largest step removed,
  today's certified readings are still low by **≥ 982 B (4.7%)** on
  `reagent-subs` and **≥ 2,472 B (10.7%)** on `uix-subs`. The published
  16 – 20% is a comparison against the round median; the like-for-like floor is
  roughly a third to two thirds of it.
- **The reseeded page width is REFUTED as the cause**, on two independent
  grounds — source and the control's own first clause. It was one of the three
  candidates the previous window left open, and it can be struck.
- **The prime term, re-derived on the 2026-08-08 data itself, is ≈ 6,231 B
  (`reagent-subs`) and ≈ 6,267 B (`uix-subs`)**, i.e. **1,038** / **1,045** B
  per write — *smaller* than the 1,161 B the corpus subtracts. Using the
  dataset's own term makes the residual slightly **larger**, not smaller.
- **What moved is still not established.** With the seed width struck and the
  work unit shown byte-identical, the surviving candidate is drift in the event
  pipeline between 2026-08-08 and 2026-08-13. This page does not identify it and
  does not claim either reading is the wrong one.

## The estimand was classified before anything else

`rf2-nkeba` was dispatched beside four live workers on the standing rule that a
byte census takes no quiet-box slot, so the first act was to check at source
that the quantity really is a byte census.

| question | answer | where |
|---|---|---|
| what is sampled | `performance.memory.usedJSHeapSize`, `--enable-precise-memory-info` | `p0_heap.cljs` `mem` |
| what is accumulated | the sum of **positive deltas** between consecutive samples | `p0_run.cjs` `allocSteps` |
| what is published | `rise / ALLOC_WRITES` | `p0_run.cjs`, the `perWrite` field |
| any clock term on the path | **none** | see below |

A search for `Date.now`, `performance.now`, `hrtime` and `process.uptime` across
`p0_run.cjs` and `p0_heap.cljs` returns **exit 1, no matches**. That is a search
returning zero, which is not by itself a check that passed, so the same pattern
engine was run against a string that is present — `usedJSHeapSize` and `(mem)`
— and returned **exit 0 with six hits**. **The estimand is BYTES.** Nothing on
this page rests on a duration, and no part of it waited for a drain.

## Why nothing was measured

A measurement window that publishes no measurement owes the reason, so here it
is in full.

**A re-run of today's rig cannot bear on this question.** The clause is an
across-time comparison and only one of its two arms can still be taken. The
2026-08-08 arm is unrepeatable: the instrument that produced it has been
replaced twice over — `rf2-2rtt6.140` landed the boundary-proportional write and
the observed-collection witness, `rf2-oiy1` added the prime, `rf2-rs8q6` added
the by-site stride. Re-running the surviving arm a fourth time restates a figure
three sessions already agree on and answers nothing.

**The run that *would* bear on it is an extra rung, and a window may not add
one.** To reproduce the 2026-08-08 *seeded* width at the 2026-08-08 *mounted*
page you would need the seed decoupled from B — the driver passes
`prepare(segment, B)` and the two move together — which is a rig change, and
`rf2-nkeba` is filed against a rig that must not move mid-window. It is
filed as its own bead instead.

**What was run is the instrument's own gate, unmodified.** `allocSteps`,
`allocPrimeSplit` and `ALLOC_LEG_TOLERANCE` are exported pure functions,
exported precisely so a pin can *drive* the verdict rather than read the source
and hope. They were driven over sample streams reconstructed from the committed
2026-08-08 scalars. No rig file was read into anything but memory and **no rig
file was edited by this analysis.**

## The dataset this rests on, and the fact that it exists

`rf2-2rtt6.138`'s run of 2026-08-08 committed its **raw per-window record** —
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-2rtt6-138/run1.json`,
3,140 lines, landed by `4a1537cb71` and still tracked. It is the only allocation
run in the corpus whose data survives: the 2026-08-13, 2026-08-16 and 2026-08-17
windows published records but no dataset. **That asymmetry is the whole reason
this question can be interrogated at all**, and it is also the reason it cannot
be answered completely — see [what was NOT concluded](#what-was-not-concluded).

Configuration, read off the file rather than off the prose that describes it:
`roots` 4, `boundaries` 24, `writes` 6, `warmups` 3, `rounds` 6,
`preciseMemory` true, `fallThresholdB` 600,000, generated
`2026-08-07T23:37:56.882Z`. That is B = 24 under `:p0/write-all` at six writes —
the same page and the same write as 2026-08-17's run 1.

## The published figure is a median, and no page says so

Every floor window in the dataset has `falls: 0` and `rise == endpoints`, so the
counter never fell and `rise` is the whole climb.

| segment | round 1 | round 2 | round 3 | round 4 | round 5 | round 6 |
|---|---|---|---|---|---|---|
| `reagent-subs` `rise/W` | 21,829.3 | 24,440.0 | 24,122.0 | 24,152.7 | 23,928.0 | 24,094.0 |
| `uix-subs` `rise/W` | 25,144.0 | 24,958.7 | 24,502.0 | 24,961.3 | 24,212.7 | 24,294.0 |

| segment | mean | **median** | min | max | published |
|---|---|---|---|---|---|
| `reagent-subs` | 23,761 | **24,108** | 21,829 | 24,440 | **24,108** |
| `uix-subs` | 24,679 | **24,730** | 24,213 | 25,144 | **24,730** |

**Both published figures are the median, to the byte.** The per-boundary column
confirms it independently: the record page prints 1,005 and 1,030 B per
boundary, and the medians round to 1,005 and 1,030 where the means round to
990 and 1,028.

The runner could not have printed that. `p0_run.cjs` at `4a1537cb71` prints
`n0(flw.mean)` from a `stat` helper returning `{mean, min, max}`, and it has no
`median` function at all — `median` arrived later, with `legMedian`. So the
published pair was computed off the dataset by hand, as a median, and the choice
was never written down.

**Neither statistic is wrong. What is wrong is comparing either of them to a
per-window value without saying which it is** — and the gap between them,
347 B on `reagent-subs`, is itself a tenth of the residual the previous window
could not explain.

## The prime term, re-derived on the data it is being subtracted from

The corpus subtracts 1,161 B, from `6,966 / 6`, where 6,966 is the **median
first-leg excess pooled over all 2026-08-13 arm windows** — every arm, every
page size, not the floor at B = 24. The 2026-08-08 dataset prices the same term
on the windows actually in question.

With `falls: 0` every step is non-negative, and a six-write window at stride 2
is six legs and six gaps. A gap runs a loop increment and two array stores, and
the idle control prices the whole sampler at 32 B per iteration, so the gaps
contribute ≈ 0.1% of `rise` and the legs are effectively all of it. Dropping the
largest step and averaging the remaining five therefore gives the
**prime-equivalent** reading, and — because the largest step is an upper bound
on whatever the first leg was — it is a **lower bound** on it.

| segment | `(rise − maxStep)/5`, per round | min | largest step over the mean of the rest | ÷ W |
|---|---|---|---|---|
| `reagent-subs` | 20,798 / 23,352 / 23,146 / 23,010 / 22,913 / 23,117 | **20,798** | 6,186 / 6,528 / 5,858 / 6,858 / 6,091 / 5,863 → mean **6,231** | **1,038** |
| `uix-subs` | 24,094 / 23,879 / 23,515 / 23,879 / 23,168 / 23,270 | **23,168** | 6,302 / 6,477 / 5,921 / 6,493 / 6,268 / 6,142 → mean **6,267** | **1,045** |

Two things fall out. The excess is **constant to within ±8% across twelve
windows and both segments** while `rise` itself varies — the signature of a
fixed per-window term, and consistent with the 6,966 B and 6,864 B medians the
later windows measured. And it is **smaller** than the pooled figure the corpus
uses, so subtracting the dataset's own term leaves prime-equivalent targets of
**23,070** and **23,685** rather than 22,947 and 23,569: the correction the
corpus applies is *generous to the control* by about 120 B.

## Driving the shipped gate: would the 2026-08-08 windows certify today?

This is the question the clause turns on, and it is asked of the real gate
rather than argued.

Two sample streams were reconstructed per window, both reproducing that
window's recorded `rise`, `maxStep` and `falls: 0` exactly, and both handed to
the shipped `allocSteps` at the shipped τ = 0.25.

- **(A) `rf2-oiy1`'s model** — leg 1 carries the whole excess, legs 2 – 6 equal.
- **(B) an adversarial arrangement** — the same `rise` and the same `maxStep`,
  deliberately arranged to certify if any arrangement can.

| reconstruction | `reagent-subs` | `uix-subs` | total |
|---|---|---|---|
| (A) `rf2-oiy1` model | 0 of 6 certified | 0 of 6 certified | **0 of 12** |
| (B) adversarial | 2 of 6 certified | 6 of 6 certified | 8 of 12 |

Under (A) the worst leg deviations run **25.18% – 29.81%** — every window past
τ = 0.25, and every one of them only just past it. Under (B), eight certify.

**So the honest reading is conditional, and it is stated as one.** *If* the
first-leg term described by `rf2-oiy1` — confirmed in 336 of 336 arm windows on
2026-08-13 and replicated at a median 6,864 B by `rf2-e9wr` — was present in
these windows, then none of them would be certified by today's witness. The
recorded scalars alone do not compel that: `rise`, `maxStep` and `falls` are
consistent with arrangements that certify. **The per-leg samples were not
preserved, so the artefact cannot settle it.**

The driver was itself controlled. Six equal 20,000 B legs certify with worst
deviation 0; one leg at +30% refuses at 30.00%; one leg at exactly +25%
certifies, confirming the boundary is inclusive; and `allocPrimeSplit` over a
seven-write stream yields one prime leg and six measured, matching
`ALLOC_WRITES`.

## What the comparison looks like once the quantities are lined up

| comparison | `reagent-subs` | `uix-subs` |
|---|---|---|
| today's certified `rise/W` | 19,349 / 19,650 / 19,816 | 19,712 / 20,696 |
| vs the published median (as recorded) | −17.80% / −18.51% / −19.74% | −16.31% / −20.29% |
| vs the median less the corpus prime (22,947 / 23,569) | −3,131 / −3,297 / −3,598 B | −2,873 / −3,857 B |
| vs the median less the dataset's own prime (23,070 / 23,685) | −3,254 / −3,420 / −3,721 B | −2,989 / −3,973 B |
| **vs the lowest 2026-08-08 round, largest step removed** (20,798 / 23,168) | **−982 / −1,148 / −1,449 B (4.7 – 7.0%)** | **−2,472 / −3,456 B (10.7 – 14.9%)** |

The last row is the tightest like-for-like the surviving artefacts support: the
most favourable of the six 2026-08-08 rounds, with the term the prime removes
taken out at that round's own magnitude, against each certified reading
individually. **A gap remains at every cell of it.** It is not the published
16 – 20%, and on `reagent-subs` it is roughly a third of it, but it does not
close.

## The reseeded page width is refuted

The previous window left three candidates — the reseeded page width, the prime,
and substrate drift. The first can be struck, on two independent grounds.

**Source.** `:p0/write-all` is byte-for-byte identical at `4a1537cb71` and at
`2e993181f8`: `(assoc db :cells (vec (repeat cells-n v)))`, where `cells-n` is
the compile-time 300 at both revisions. It rebuilds 300 cells whatever the db
was seeded with, so the width of the vector it *replaces* changes nothing it
allocates. The floor arm has "no subscription, no hook, nothing on the page that
a write can re-render", and the seed runs inside `prepare!`, outside the
measured region. **No term in the measured work unit reads the seeded width.**

**The control's own first clause.** V1's first clause reads `F_old` flat across
B ∈ {4, 24, 96}, and under this driver the seeded width *is* B. It reads
19,898 / 19,781 / 19,898 on `reagent-subs` and 20,487 / 20,446 / 20,487 on
`uix-subs`. Over a 24× range of seeded width the movement is **≤ 117 B and
non-monotone** — under 1.3 B per seeded cell on `reagent-subs` and under 0.5 on
`uix-subs`. Extrapolating from 24 cells to 2026-08-08's 300 gives **at most
≈ 351 B and ≈ 123 B**, an order of magnitude short of a 2.5 – 3.5 KB residual.

**The clause that holds kills a hypothesis about the clause that fails.** That
is the flat-in-B reading doing work beyond confirming itself.

## The work unit did not change

For completeness, since a changed work unit would make everything above moot.
At the shipped stride of 2, `alloc-window!`'s `write-all` branch at
`2e993181f8` is:

    (vswap! alloc-tick inc)
    (arms/write-all! @alloc-tick)
    (react-dom/flushSync ...)

which is the same three statements in the same order as at `4a1537cb71`, with
the same Reagent-or-empty drain split and the same stride-2 sampling.
`write-all!` and `dispatch-sync!` are byte-identical at both revisions. The
toolchain pins are identical too — `playwright` 1.59.1, `react` and `react-dom`
19.2.0, `shadow-cljs` 3.4.10 — so a browser, React or compiler upgrade is not
available as an explanation. **That last point carries a caveat and it matters:
the 2026-08-08 dataset records no runtime version.** A pinned Playwright is
evidence about what was declared, not about which Chromium binary was resident.

## What this window did NOT do to the corpus

The bead observes that the 2026-08-08 rows are cited on `release-scans.md`,
`the-survival-metrics-allocation-half.md` and `allocation-instrument-rework.md`,
and asks what the corpus should do about it. **This page changes none of them,
deliberately.** Amending the clause in `allocation-instrument-rework.md` is
amending the control, which is a ruling and not a measurement; and the
survival-metrics page's claim that `F_old` "keeps its full force across the
change" is exactly what is now in question, which is a reason to put it to the
operator rather than to rewrite it from inside the window that found it.
`rf2-nkeba` stays open carrying that question.

## What was NOT concluded

- **No cause is assigned to the residual.** The seed width is struck and the
  work unit is shown unchanged; what is left is drift somewhere in the event
  pipeline between 2026-08-08 and 2026-08-13. Twenty-two commits landed under
  `implementation/core/src` in that interval and **none was shown to be it**.
  Naming one would take a bisection window this page may not spend.
- **Whether the 2026-08-08 windows would certify today is CONDITIONAL, not
  established.** Under `rf2-oiy1`'s model none of the twelve certifies; under an
  adversarial arrangement of the same recorded scalars, eight do. The per-leg
  samples are not in the artefact and the question is not decidable from it.
- **The selection effect is not quantified.** Today's certificate admits a
  subpopulation and 2026-08-08 had no certificate, so the two populations differ
  by an amount nothing here measures. All that is shown is that it cannot be the
  *whole* story, because today's readings sit below the entire 2026-08-08
  per-round range.
- **Nothing is concluded about τ.** It was not moved, not calibrated, and not
  read as a verdict on anything.
- **No new allocation figure is published.** Every number here is re-derived from
  committed artefacts; none is a fresh reading, and the three sessions that
  produced today's ~19 – 20 KB level are not re-confirmed by this page.
- **The runtime is not ruled out.** The pins are identical at both revisions and
  the 2026-08-08 dataset records no browser version, so a resident-binary
  difference can be neither ruled in nor out.
- **Which of the two readings is the wrong one is still not determined**, and
  this page does not claim either is.

## Provenance

| item | value |
|---|---|
| analysis head | `2e993181f8`, which is `origin/main` |
| 2026-08-08 dataset | `implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-2rtt6-138/run1.json`, landed by `4a1537cb71` |
| 2026-08-17 record re-read | [the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md), measured off `6a32dbf7e5` |
| instrument at the earlier revision | `p0_run.cjs`, `p0_heap.cljs`, `p0_arms.cljs`, `p0_fixture.cljc` at `4a1537cb71` |
| rig files edited | **none** |
| browser launched | **none** |
