# The eight signs are one block — V2's sign residual re-examined, and no window taken

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-0gjqi`, which asks whether
`write-page` reading below `write-all` at 8 of 8 mid-rung comparisons is a real
property of the two writes or an artefact. Written 2026-08-18 on
`worker/armfloor-0gjqi`, written off `4cf5680a82` and rebased onto
`2a48dc2109`.

**No allocation window was taken for this page.** Nothing here is a new
measurement. Every figure below is either re-derived from a committed dataset,
re-derived from a figure already published on
[the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md),
or read out of the instrument's source. The instrument was not run, not
configured, not edited, and no rig file was touched.

Instrument revision, beside every re-derivation: the driver at
`implementation/core/test/re_frame/bench/p0_run.cjs` as it stands at
blob `e88d2be45efd59d023a9d23da9e4ff1f9800b5c0`. It was re-read at the end of the
analysis and had not moved: the blob is byte-identical at `4cf5680a82` and at
`2a48dc2109`, the two bases this page was written and rebased onto, so the early
and late derivations here are against one instrument.

## The answer, first

**The sign residual's INFERENTIAL claim is refuted; its CAUSE is UNRESOLVED, and
the committed corpus cannot decide it.**

- **The 1-in-256 figure does not hold, and the reason is structural rather than
  statistical.** The eight comparisons share three terms, each of which alone
  collapses them to far fewer than eight independent sign draws: one fixed run
  order, one floor per segment per write, and mismatched certified-round
  subsets. Under the third of those — a page-global floor-level term, the exact
  quantity `rf2-77gz8` measured — **all eight cells share ONE draw, so eight
  agreeing signs have probability 1, not 1/256.**
- **The measurement order is fixed by the rig's own construction, not by
  choice.** `P0_ALLOC_WRITE` is read once from the process environment
  (`p0_run.cjs:1504`), so one process drives one write and the two arms of every
  comparison are two sequential process runs. The published record declares the
  order: `all` was run 2, `page` was run 3. Write and run-position are therefore
  **perfectly confounded across all eight cells**, and any monotone
  session-level drift produces 8 of 8 in one direction with certainty.
- **The residual is not robust to a floor-level term smaller than one this
  instrument has been measured to carry.** A shared floor offset of **2,472
  B/write** on `reagent-subs` and **928 B/write** on `uix-subs` reverses all
  eight signs. The between-run spread of the certified floor median at a single
  revision and a single write, re-derived here from the committed
  `alloc-9jrhi` corpus, is **3,852 B/write** and **3,912 B/write** — larger than
  both.
- **But a floor-level term does not describe the residual's SHAPE either.** A
  shared floor offset enters every arm of a segment identically; the observed
  mid-rung offsets span 1,948 B/write within `reagent-subs` and 740 B/write
  within `uix-subs`. Within an arm family the residual is closer to a constant
  proportion. So the mode-transition account is not excluded, and it is not
  supported as a whole explanation.
- **The corpus cannot test it.** All eight `alloc-9jrhi` datasets carry
  `writeSelector: "all"`, plan `floor`, B = 24 — **no committed dataset carries
  `write-page`, and none carries a ladder rung under any write selector.** The
  V1/V2 re-run window that produced the finding committed no dataset at all.
- **The instrument already affords the fix in-page.** `allocWindow(n, kind,
  drain)` takes the write as a per-window `kind` (`p0_heap.cljs:1182`–`:1188`), so
  `write` and `write-all` can already be driven in one round on one page. Only
  the driver's process-global pin prevents it.

## What was checked, and what it is checked against

`rf2-0gjqi`'s finding is a table of thirteen cells published on
[the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md).
Eight of them — the R = 3 and R = 7 rungs — read `page` below `all`. This page
asks one question about those eight: **how many independent sign draws are
they?**

That question is answerable without new data, because it is a question about the
instrument and the estimator rather than about the bytes.

> **The audit on the bead reached the same conclusion from a different door,**
> and it is honoured rather than restated: PR #8424's merged-PR audit
> (2026-08-17) found that the two arms came from two sequential process runs
> instead of the same-page/same-run pair `allocation-instrument-rework.md`
> requires, that the per-cell medians use different certified-round subsets, and
> that the 1-in-256 figure is therefore unsupported. Two of the three shared
> terms below are that audit's. **The third — the shared floor — is new here,
> and it is the one that reaches probability 1.**

## The measurement order, established at source

Three facts from the driver, each cited to its line.

| what | where | consequence |
|---|---|---|
| `const ALLOC_WRITE = process.env.P0_ALLOC_WRITE \|\| 'page'` | `p0_run.cjs:1504` | the write is fixed for a whole process; one run drives one write |
| `ALLOC_WRITE_SPECS` has exactly two members, `page` and `all` | `p0_run.cjs:1490` | there is no paired or interleaved selector to choose |
| `ALLOC_WRITE_SPEC.kind` is passed into every arm window | `p0_run.cjs:2335`, `:2342` | the write cannot vary between windows of one run |

So the two arms of every one of the thirteen comparisons are two different
process runs, necessarily. The record states which came first: run 2 was
`P0_ALLOC_WRITE=all`, run 3 was `P0_ALLOC_WRITE=page`, and its own box brackets
put run 2 between 01:48:27 and 01:50:09 AUSEST and run 3 between 01:50:09 and
01:51:44. **`all` preceded `page` for every cell on the page.**

**Within-run ordering is matched, which sharpens rather than softens this.** The
segment order alternates on round parity (`p0_run.cjs:2269`) and the arm order
inside a segment is `slot-order(n, round)`
(`order_guard.cljc:149`), a pure function of the arm count and the round index.
Both runs had the same plan, the same eleven arms per segment and the same six
rounds, so **every arm was measured at the same within-run position in both
runs**. Within-run position is therefore controlled, and the only systematic
difference left between the two legs of a comparison is that one run happened
before the other.

## The three shared terms

Independence is what the 1-in-256 figure assumes, and each of these breaks it.
They are listed weakest-first.

**(1) One fixed run order, shared by all thirteen cells.** Established above.
Whatever differs between the second process and the third — session time,
allocator state, a V8 tier transition, a page-global level — enters every cell
with the same sign. The thirteen cells are one ordered run-pair, so as a test of
"does the write change the per-boundary figure" this page has **one block, not
thirteen trials**.

**(2) One floor per segment per write, shared by every arm of that segment.**
The estimator differences each arm window against that segment's floor window in
the same round. `reagent-subs`' floor certified 4 of 6 rounds under `all`, and
all four of that segment's mid-rung cells report `n = 4` under `all`, so those
four cells difference against **the same four floor windows**. A level term
common to those windows shifts all four cell medians by exactly the same amount
in exactly the same direction. The four mid-rung cells of a segment are not four
draws; with respect to a floor-level term they are **one**.

**(3) And the floor term is page-global, which merges even those two.**
`rf2-77gz8` measured the second floor mode as moving `reagent-subs` and
`uix-subs` by *exactly* the same 3,792 B — "the same number on two segments that
share nothing but the page". If the sign is set by a floor-level term, the two
segments do not draw separately either. **Eight cells, one draw, probability 1.**

To be exact about what that argument does and does not establish: it does not
show that a floor-level term was present in the V1/V2 pair. It shows that **if**
one was, the observed unanimity is not evidence of anything, because unanimity is
what that mechanism produces by construction. The bead's stated null — thirteen
independent random signs — is not the null this design admits.

**(4) Mismatched certified-round subsets, which is the audit's point and stands
as filed.** The two legs of a cell are medians over different sets of rounds:
`n_all` and `n_page` differ in **6 of the 8** mid-rung cells, and the run totals
are **56** certified arm-rounds under `all` against **65** under `page`. Equal
`n` does not imply the same rounds, so the true count of mismatched cells is
between 6 and 8. Each cell's two legs are therefore drawn from different
populations of rounds, on an instrument whose windows `rf2-rs8q6` found to be
round-indexed.

## How large a floor term would have to be

Population: the eight R = 3 and R = 7 cells published on
[the floor certifies and the control does not](the-floor-certifies-and-the-control-does-not.md).
Statistic: `Δ = page − all` in B per boundary, taken from that page's per-cell
medians as published. Those medians are printed rounded to the byte, so each `Δ`
carries about ±1 B and the percentages below differ from the source page's by up
to 0.03 percentage points; that is rounding, not disagreement.

A floor-level offset `ε` in B/write enters `(arm − floor)/B` as `−ε/B`, and this
page's B is 4. So `ε = 4·|Δ|` is the offset that takes a cell to zero.

| segment | arm | rung | `all` | `page` | `Δ` B/bnd | `Δ` % | `ε` to null, B/write |
|---|---|---|---|---|---|---|---|
| `reagent-subs` | hicasso | R3 | 6,984 | 6,687 | −297 | −4.25 | 1,188 |
| `reagent-subs` | hicasso | R7 | 14,447 | 13,829 | −618 | −4.28 | 2,472 |
| `reagent-subs` | reagent | R3 | 7,795 | 7,664 | −131 | −1.68 | 524 |
| `reagent-subs` | reagent | R7 | 15,408 | 15,180 | −228 | −1.48 | 912 |
| `uix-subs` | hicasso | R3 | 6,691 | 6,538 | −153 | −2.29 | 612 |
| `uix-subs` | hicasso | R7 | 13,752 | 13,520 | −232 | −1.69 | 928 |
| `uix-subs` | uix | R3 | 5,211 | 5,164 | −47 | −0.90 | 188 |
| `uix-subs` | uix | R7 | 11,405 | 11,347 | −58 | −0.51 | 232 |

**To flip every sign on a segment takes `ε` = 2,472 B/write on `reagent-subs`
and 928 B/write on `uix-subs`.** To flip the smallest cell takes 524 and 188.

### And how large one has been measured to be

Re-derived here, first-hand, from the eight committed `alloc-9jrhi` datasets.
Population: the floor arm's certified windows. Statistic: the median of
`rise/W` across the rounds that certified, per segment per run — the same shape
of estimator the source page uses, applied to the floor rather than to
`arm − floor`. Instrument revision: each dataset's own, named in its filename;
every one carries `writeSelector: "all"`, plan `floor`, B = 24, `unverified: 0`
and a passing positive control (`perDouble` 8.081, `differential` 8.001).

| `implementation/core/src` revision | runs | `reagent-subs` medians | `uix-subs` medians | spread |
|---|---|---|---|---|
| `4a1537cb71` | 3 | 22,984 / 19,236 / 19,132 | 23,468 / 19,736 / 19,556 | **3,852 / 3,912 B/write** |
| `88411ed803` | 2 | 19,429 / 19,427 | 20,107 / 21,752 | 2 / **1,645 B/write** |
| `a158c40288` | 1 | 19,430 | 20,146 | — |
| `48c715f97c` | 1 | 19,418 | 19,954 | — |
| `9d20be1d00` | 1 | 19,470 | 19,683 | — |

**Three runs at one revision, one write, one page, one instrument, and their
certified floor medians span 3,852 and 3,912 B/write.** Both exceed the 2,472
and 928 B/write that would reverse every sign on the corresponding segment. Two
runs at HEAD span 1,645 B/write on `uix-subs`, which still exceeds `uix-subs`'
928.

These re-derived spreads run slightly above `rf2-77gz8`'s exact 3,792 B because
the estimators differ and the difference is stated rather than reconciled: that
bead quotes the two steady-state LEVELS a bimodal run sits at, where this median
pools a run's certified rounds across a within-run step. **Both are correct
answers to different questions**, and this page uses the median because it is the
shape of estimator the disputed cells were computed with.

## The shape of the residual argues the other way

A shared floor offset is additive and identical across a segment's arms. The
observed offsets are not:

| segment | mid-rung `Δ` (B/boundary) | within-segment spread |
|---|---|---|
| `reagent-subs` | −297, −618, −131, −228 | 487 B/bnd = **1,948 B/write** |
| `uix-subs` | −153, −232, −47, −58 | 185 B/bnd = **740 B/write** |

No single `ε` fits four cells that far apart, so a floor-level term cannot be
the whole of the residual. What the eight cells look more like is a constant
PROPORTION within each arm family — `page/all` reads 0.9575 and 0.9572 for
`reagent-subs | hicasso`, 0.9832 and 0.9852 for `reagent-subs | reagent`, 0.9771
and 0.9831 for `uix-subs | hicasso`, 0.9910 and 0.9949 for `uix-subs | uix`.
Each family's two rungs agree to within 0.6 percentage points.

**That is offered as a shape and not as a mechanism, and it is two points per
family.** The four family factors themselves span 0.957 to 0.995, a spread of
3.8 percentage points — comparable to the residual being described — so no
single multiplicative session effect fits them either. A genuine per-boundary
work difference would look like this; so would several things that are not one.

## Why no window was taken, and what the corpus cannot answer

`rf2-erre5` made the record closed under re-analysis by retaining each window's
raw samples array, and `rf2-9jrhi` committed eight datasets. Both are true and
neither reaches this question.

| dataset | `writeSelector` | plan | B | rounds | rung arms | `samples` |
|---|---|---|---|---|---|---|
| `alloc-9jrhi/bisect-1` … `bisect-7`, `pilot-rounds6` | `all` (8 of 8) | `floor` | 24 | 18 / 18 / 18 / 18 / 18 / 18 / 18 / 6 | none | yes |
| `alloc-2rtt6-138/run1` | absent — predates the selector | full ladder | 24 | 6 | 20 | no |

- **No committed dataset carries `write-page`.** The comparison this bead is
  about has no second leg anywhere in the tree.
- **No committed dataset carries both a write selector and ladder rungs.** The
  only committed full ladder predates the selector entirely and predates
  `rf2-erre5`, so it carries neither `writeSelector` nor `samples`.
- **The V1/V2 re-run window committed no dataset.** Its three runs of
  2026-08-17 are not under
  `implementation/hicasso/test/re_frame/bench/hicasso/data/`, so the eight
  disputed cells cannot be recomputed, re-estimated or paired from anything in
  the repository. Their published medians are all that survives of them.

A fresh window would not have answered it either. The rig can only take one
write per process, so a new pair would reproduce the same confound at a new
timestamp — which is precisely why the answer here is a design finding and not a
measurement.

## What would decide it, and the instrument already half affords it

**Filed as `rf2-irxrw`, which now blocks `rf2-0gjqi`.** The bead carries the
build; what follows is the reasoning behind it.

**One run, both writes, same page, same round, matched pairs.** The in-page API
already takes the write as a per-window argument:

```clojure
;; p0_heap.cljs, allocWindow [n kind drain]
(let [all?   (= kind "write-all")
      write? (or (= kind "write") all?)]
  ...)
```

so `write` and `write-all` are two values of a parameter the page reads once per
window. What pins them apart is the driver: `ALLOC_WRITE` is read from
`process.env` at module scope and the resolved `kind` is passed into every arm
window of the run. A paired mode drives both kinds at each arm inside one round
and records them as a pair.

That gives the estimator matched pairs — same round, same floor window, same
within-run position, same session — and reduces the question to a paired sign
test over rounds, which has a defensible null. **It also makes the count of
independent blocks legible**, which is what the audit asked for: independent
run-pair/order blocks rather than rungs.

Two further things it would need, stated so they are not rediscovered:

- **The floor must be paired too**, because term (2) above is the shared floor.
  A paired mode that pairs only the arms leaves the dominant shared term intact.
- **`rf2-77gz8` must be resolved first, or measured alongside.** Whether the
  ladder ARMS carry the floor's second mode is untested — the mode was observed
  on a `floor`-plan run with no arms in it. If they do, it cancels in
  `arm − floor` and the "against" reading holds; if they do not, it does not
  cancel and this page's `ε` arithmetic is live. **That single unknown decides
  which way the mechanism cuts, and no committed dataset contains an arm window
  and a floor window under a write selector to test it with.**

## What was NOT concluded

- **The residual is not withdrawn and no measured figure is disputed.** The
  thirteen cells stand exactly as published; what is refuted is one inferential
  sentence about eight of them.
- **The residual is not shown to be noise.** Nothing here estimates its
  magnitude, and "not established" is not "absent".
- **`rf2-77gz8`'s mode transition is not adopted as the cause.** It is shown to
  be of sufficient magnitude and of the wrong shape to be the whole of it, and
  it is not excluded. The premise it turns on — whether ladder arms carry the
  mode — is untested.
- **No ruling is made on whether ≈ 1 – 4% of non-cancellation is tolerable.**
  That was a ruling when the source page declined it and it was a ruling still
  when this page was written. This page moves it earlier in the queue rather
  than answering it: the residual is not established, so there may be nothing to
  rule on. **That last clause turned out to be the answer.** The ruling was
  taken on 2026-08-19 (`rf2-2rtt6.140`) and it WITHDREW the question — under
  matched pairing the residual has no direction — publishing the instrument's
  own noise floor as `1.5 B` / `4.5 B` per boundary, with a `45 B` refusal bar,
  in its place. See
  [the non-cancellation floor](the-sign-follows-the-pass-not-the-write.md#the-non-cancellation-floor-and-the-refusal-bar-it-sets).
- **Nothing is concluded about V2's floor-drop half**, which the source page
  found holds, or about the R = 20 rung's three-of-four refusal.
- **No gate, band, threshold or tolerance was touched, and no rig file was
  edited.** `ALLOC_LEG_TOLERANCE`, `ALLOC_FALL_THRESHOLD_B`, `ALLOC_MIN_WRITES`
  and τ are where the source page left them.
