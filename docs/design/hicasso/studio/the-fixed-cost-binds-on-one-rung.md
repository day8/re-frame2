# The fixed cost binds on one rung — `:p0/write-all`'s F, re-measured

Seat: RE-ANALYSIS, EP-0038. Bead `rf2-2rtt6.140`, whose subject — *does
`:p0/write-all`'s fixed per-write cost make the allocation ladder uncertifiable
at any page size?* — has been open since 2026-08-08 and untouched by the two
windows that ran in the interval. **No window was taken, no browser was launched,
no bundle was built and no rig file was edited.** Every figure is re-derived
first-hand from committed datasets, on `main` at `ca0abf8971`.

Corpus, runtime and provenance are the same as
[the window total is the ceiling](the-window-total-is-the-ceiling.md): the two
paired runs at
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-0gjqi/`, committed
at `e77c4969e9`, taken 2026-08-18 on branch `worker/pairedwin-0gjqi` built at
`1f004b15ff` — Chromium via Playwright, shadow-cljs `release` on
`:hicasso-bench`, `:optimizations :advanced`, `goog.DEBUG false`, `--expose-gc`.
Both runs' positive control passed at **8.00 B/double**; both captured **exit 1**
on the falls gate, so **no slope is quoted**. B = 4, W = 6 measured writes after
one prime, six rounds, 96 windows per rung across the two runs and 48 on the
floor.

Reproduction, from `implementation/`:

```bash
node hicasso/test/re_frame/bench/hicasso/alloc_window_ceiling.cjs
```

## The answer, first

**The bead's premise SURVIVES and has narrowed to ONE RUNG — but NOT on the terms
it was written in.** The bead states its uncertifiability argument in the
300,000 B masking bound, and **that bound was RETIRED AND DELETED on 2026-08-08**
(`rf2-2rtt6.141`, on this bead's own criterion 4), so every `max B` figure below
is a **RECONSTRUCTION of the bead's case, not a live constraint**. What carries
the conclusion instead is measured rather than modelled, and reaches the same
rung: **R = 20 certifies 24/96 where every rung beneath it certifies 88 – 95%**,
and `rf2-onozm`'s observed **884,280 B ceiling** separates that same rung on a
quantity rather than on a constant. **Stop the ladder at R = 7 and B = 1
certifies.**

- **F today is 19,280 B per write** — the floor arm's median `perWrite` over 48
  windows. Against the 42,857 B the *retired* bound allowed at W = 6 that is
  **45.0%**, where the bead was written on **24,108 / 24,730 B = 56 – 58%**.
  **The two readings are not differenced**, per `rf2-nkeba`: each is read against
  the allowance, which is a constant — **a historical yardstick here, used to
  restate the bead's case in its own units and not as a live threshold**.
- **F is the WRITE, not the mount.** The R = 0 null arm mounts four boundaries
  that read nothing, and its `perWrite` is **19,281 B — one byte from the
  floor's**. That check was not available when the bead was filed.
- **Re-running the bead's own arithmetic at today's F** — a reconstruction in the
  retired bound's units, since that is what the bead is written in: R = 1 admits
  B ≤ 6, R = 3 admits B ≤ 3, R = 7 admits B ≤ 1, **R = 20 admits B = 0**. The
  bead's table gave B ≤ 4 at R = 1; the shape is unchanged and only the top rung
  binds. **These numbers have no live warrant** — the constant they divide by is
  deleted — and they are kept because they show the bead's own case reaching the
  rung the measurements reach.
- **The empirical column agrees with where the refusals actually are.** R = 1
  certifies 86/96, R = 3 85/96, R = 7 89/96 — **88 – 93%** — against R = 20's
  **24/96 = 25.0%**, which is exactly 2 runs × 6 rounds × 2 writes on the single
  surviving arm family and nothing else.
- **This bead and `rf2-onozm` are ONE CONSTRAINT UNDER TWO NAMES.** R = 20 is the
  binding rung here and the whole subject there. Resolving R = 20 **discharges**
  this bead rather than re-measuring it.
- **A separate finding, and it is a corroboration rather than a question.** R = 3
  and R = 7 charge **319,137 B** and **521,521 B** against the **300,000 B**
  masking bound and certify anyway, at 88.5% and 92.7%; and `floor`, R = 0 and
  R = 1 all charge *under* the bound while still refusing 3, 5 and 10 windows.
  **The bound is not what the instrument enforces, and it disagrees with the
  certificate in both directions.** Worse, it is charged on a quantity that
  cannot see a collection at all. **That is not an open decision: the bound was
  RETIRED and DELETED on 2026-08-08** by `rf2-2rtt6.141`, on this bead's own
  criterion 4, and the deletion is gated by
  `p0_ladder_structural.test.cjs`. These measurements re-derive *why*
  independently. See [section 3](#3-the-bound-and-the-certificate-disagree).

**Nothing was widened.** `ALLOC_MIN_WRITES` stays 6, `ALLOC_FALL_THRESHOLD_B`
stays 600,000, `ALLOC_LEG_TOLERANCE` stays the declared 0.25 placeholder, R = 20
stays on the ladder, τ is where the previous windows left it. **No reading,
threshold, band or budget status moves on this page.**

## 1. F, re-measured

`:p0/write-all` rebuilds a 300-element vector and drives the whole event pipeline
whether one boundary is mounted or 1,200. The floor arm is that write with no
subscription under it, and F is what it costs.

| quantity | reading |
|---|---|
| masking bound (`rf2-n6w7o`) — **RETIRED 2026-08-08**, a historical yardstick below | (W+1) × `perWrite` ≤ **300,000 B** |
| allowance at the six-write averaging floor | **42,857 B** per write |
| **F**, floor arm median `perWrite`, 48 windows | **19,280 B** = **45.0%** of the allowance |
| F as the bead recorded it, 2026-08-08 | 24,108 / 24,730 B = 56 – 58% |
| boundary budget left | 42,857 − 19,280 = **23,577 B** per write |

The bead's central sentence — *"F alone is 57% of it, before a single boundary
has been measured"* — **now reads 45%**. That is a material loosening of the
squeeze, and it does not reach the top rung.

**What it is NOT is a measured 21% fall in the rig.** The two figures are read
against the same constant allowance and **are not differenced against each
other**, because
[the 2026-08-08 row is the arm's top level](the-2026-08-08-row-is-the-arms-top-level.md)
retired that clause: the floor arm's own level ladder spans **19.4 – 19.8% at a
single revision**, and the 2026-08-08 row sits on that ladder's **top rung**. A
gap of the same size as the arm's own dispersion is not evidence of movement in
anything, and none is claimed here.

**And F is now checkable in a way it was not.** The R = 0 rung mounts four
boundaries that subscribe to nothing, so its cost must be the write's alone. It
reads **19,281 B** against the floor's **19,280 B** — **one byte** — over 96
windows against 48. The fixed cost is the *write*, not the mount, and the bead's
"F DOES NOT SHRINK WHEN B DOES" is confirmed from the null arm rather than
inferred from the floor.

## 2. The ladder arithmetic, re-run

Modelling `perWrite ≈ F + B · s(R)` off the corpus's own arms, with
`s(R) = (perWrite − F) / B` at B = 4 and the largest page each rung admits being
`⌊(42,857 − F) / s(R)⌋`:

| rung | median `perWrite` | `s` (B/bnd/write) | max B at W = 6 | (W+1) × `perWrite` | certified | median `6 × legMedian` |
|---|---|---|---|---|---|---|
| `floor` | 19,280 | — | — | 134,960 | 45/48 (93.8%) | 115,440 |
| R = 0 | 19,281 | 0.25 | — | 134,967 | 91/96 (94.8%) | 112,182 |
| R = 1 | 33,630 | 3,588 | **6** | 235,412 | 86/96 (89.6%) | 197,424 |
| R = 3 | 45,591 | 6,578 | **3** | 319,137 | 85/96 (88.5%) | 271,134 |
| R = 7 | 74,503 | 13,806 | **1** | 521,521 | 89/96 (92.7%) | 438,702 |
| R = 20 | 145,410 | 31,533 | **0** | 1,017,872 | **24/96 (25.0%)** | 1,035,036 |

R = 0's `s` is the instrument's non-cancellation floor rather than a per-read
cost — its true value is zero — so no page size is quoted for it; a max-B derived
by dividing by 0.25 B/boundary/write is an artefact of the division. The
published non-cancellation floor is 1.5 B per boundary (median) and 4.5 B (p90),
ruled on this bead on 2026-08-19 and recorded on
[the sign follows the pass, not the write](the-sign-follows-the-pass-not-the-write.md#the-non-cancellation-floor-and-the-refusal-bar-it-sets).

**Every `max B` in that column divides by a DELETED constant** — the 42,857 B
allowance comes from the retired 300,000 B bound — so the column reconstructs the
bead's argument rather than stating a live one. On that reconstruction a ladder
holds ONE B across all rungs, so the binding rung decides and **R = 20 alone
forces B = 0**. The bead's own conclusion — *"AT SIX WRITES THERE IS NO PAGE OF
ONE BOUNDARY OR MORE THAT CERTIFIES THE 1/3/7/20 LADDER"* — **survives today's F
unchanged**, and it survives on one rung rather than on four.

**What gives that conclusion a LIVE warrant is the `certified` column beside it,
which is measured and owes the bound nothing**: R = 20 certifies 24/96 where
every rung beneath it certifies 88 – 95%. `rf2-onozm` reaches the same rung on
the observed 884,280 B ceiling, again without the bound. **The finding does not
depend on the retired constant; only the bead's original phrasing of it does.**

**Stop at R = 7 and B = 1 certifies.** That is not a widening: it is the ladder
the four remaining rungs already support, and it is the same disposition
`rf2-onozm` prices from the other side.

**One honest note on how well the model does.** The `certified` column is not
predicted by the max-B column and is not offered as agreeing with it — the two
answer different questions. What the empirical column shows is that the refusals
are **not spread across the ladder**: R = 1, R = 3 and R = 7 certify at 88 – 93%
at B = 4, a page four times what R = 7's arithmetic admits, while R = 20 fails
75% of the time. The squeeze the bead describes is real and it is concentrated.

## 3. The bound and the certificate disagree

R = 3 and R = 7 charge **319,137 B** and **521,521 B** against a **300,000 B**
bound. Both certify — 85/96 and 89/96. So **the masking bound is not what the
instrument enforces**; the leg witness and the falls gate are. **That is now a
matter of record rather than of discovery**: the bound was retired and deleted
on 2026-08-08 for reasons of exactly this kind, and this section re-derives them
from the corpus independently.

**And the disagreement has a mechanism, which makes it structural rather than a
matter of calibration.** The bound is charged on `perWrite`, and

```
perWrite == rise / writes                      0 mismatches of 528 windows
rise == Σ max(leg, 0) + Σ max(gap, 0)          0 mismatches of 528 windows
```

both checked by the reader over the whole corpus. `rise` sums the window's
**positive** deltas only, so **a collection inside a window makes that window's
`rise` SMALLER**, and its `perWrite` smaller, and its charge against the masking
bound smaller. **The bound is loosest exactly where masking is worst.** A window
that lost 800 KB to the collector is charged as though it had allocated 800 KB
less — which is the opposite of what a masking bound is for.

The 72 R = 20 refusals demonstrate it: they carry a median `rise` of 879,080 B
where their would-be total is 1,035,036 B, so the bound is charged on about 85%
of what the window actually asked for. See
[the window total is the ceiling](the-window-total-is-the-ceiling.md#1-why-rise-cannot-discriminate).

**THE DECISION WAS ALREADY TAKEN, AND AN EARLIER VERSION OF THIS PAGE RECORDED IT
AS OPEN.** Corrected 2026-08-21 on the merged-PR audit of PR #8591.

> **Is the 300,000 B masking bound (`rf2-n6w7o`) still a binding VALIDITY
> requirement, independent of the certificate? No. It is RETIRED and DELETED**,
> and has been since **2026-08-08** — twelve days before this page landed.
> `rf2-2rtt6.141` accepted the #7682 audit's two soundness objections, replaced
> the bound with the observed leg witness, and deleted `ALLOC_MASK_BUDGET_B` and
> `allocMaxWrites` outright. The reasoning is in
> [allocation-instrument-rework](../allocation-instrument-rework.md) under
> *Constraint semantics — what is retired and what stands*, which says the budget
> is **deleted, not loosened**, and records that retaining it as a
> belt-and-braces refusal was considered and **rejected on arithmetic**: at the
> composed operating point a window is ≈ 630 KB, so a retained bound would refuse
> everything the witness certifies. The deletion is **gated, not merely done** —
> `p0_ladder_structural.test.cjs` pins `lacks(/const ALLOC_MASK_BUDGET_B/)`.
>
> **`rf2-2rtt6.140`'s own criterion 4 sanctioned that replacement**, so this is
> not a constraint removed behind the bead's back; it is the bead's own route (b).

**What the two observations below are now worth**, restated because they were
sound measurements offered under a mistaken premise:

- **The bound disagrees with the certificate in BOTH directions** — an
  independent re-derivation of *why* it was retired, not a case for keeping it.
  It **refuses what the certificate admits**: R = 3 and R = 7 charge 319,137 B
  and 521,521 B, over the bound, and certify 85/96 and 89/96. And it **admits
  what the certificate refuses**: `floor`, R = 0 and R = 1 all charge under the
  bound and still refuse 3 of 48, 5 of 96 and 10 of 96 windows. The two criteria
  are **independent, not nested either way**. *(An earlier version of this page
  said the bound "refuses nothing the certificate admits and admits nothing the
  certificate refuses" — false in both directions, and contradicting both this
  section's own table and its heading.)*
- **The bound is charged on `rise / W`**, a quantity that structurally cannot see
  the collections it exists to bound. That is the mechanism above, and it is the
  same class of defect the #7682 audit named.

**No gate, constant or threshold is added, moved or removed on this page.** The
retirement is reported, not performed — it happened elsewhere and earlier.

## 4. The two beads are one constraint

`rf2-onozm` asks why R = 20 certifies on one arm family of four. This bead asks
whether F makes the ladder uncertifiable at any page size. **They terminate on
the same rung**, and the reader that produces both pages' figures is one script
for that reason.

| | `rf2-onozm` | `rf2-2rtt6.140` |
|---|---|---|
| what it observes | R = 20 certifies 24/96, all on one family | R = 20 admits B = 0 while R = 1/3/7 admit 6/3/1 |
| the quantity | window would-be total, ceiling at **884,280 B** — observed | `perWrite` against the **retired** 42,857 B allowance, plus the measured `certified` column |
| what binds | R = 20's total, 1,035,036 B median | R = 20's `s`, 31,533 B/bnd/write |
| what discharges it | fewer writes at the top rung, or stop below it | the same |

**Resolving R = 20 discharges both.** Dropping the top rung to five measured
writes projects every refusing cell to 857,225 – 879,190 B, under the 884,280 B
ceiling — sized, with its limits, in
[the window total is the ceiling](the-window-total-is-the-ceiling.md#5-the-five-write-projection-and-what-it-rests-on).
Stopping the ladder at R = 7 certifies B = 1 without a rig change at all, at the
cost of HD-002's own top rung.

**Neither is chosen here.** Both are rig or ladder changes and belong in their own
window, and `implementation/core/test/re_frame/bench/p0_run.cjs` is untouched by
this page.

## What was NOT concluded

- **The bead is NOT discharged.** Its premise survives. What this page changes is
  the *scope* — one rung rather than the whole ladder — the *route*, which now
  runs through `rf2-onozm`, and the *warrant*: the bead argues in a constant that
  no longer exists, so the finding is now carried by the measured `certified`
  column and by `rf2-onozm`'s observed ceiling. **Restating the bead in live
  terms is work this page does not do**, and it may be what finally discharges it.
- **No slope was fitted and none is published.** Both runs failed the falls gate.
- **The masking bound was retired ELSEWHERE and EARLIER, and nothing here retires
  it.** No gate, constant or threshold is added, moved, widened, recalibrated or
  removed on this page, and no alternative bound is proposed. **An earlier version
  of this page said the bound was "NOT retired" and recorded its status as an open
  operator decision** — both false since 2026-08-08, when `rf2-2rtt6.141` deleted
  `ALLOC_MASK_BUDGET_B` and `allocMaxWrites` on this bead's own criterion 4
  (corrected 2026-08-21, merged-PR audit of #8591;
  [section 3](#3-the-bound-and-the-certificate-disagree)).
- **The `s(R)` figures are a MODEL, not measurements.** `perWrite ≈ F + B · s(R)`
  is fitted at a single B = 4 with no second page to check linearity against, so
  each `s` is one point through the origin. The bead's original table read its
  `s(R)` off windows the run refused as under-reading and called them lower
  bounds; these come off the corpus's medians without regard to certification,
  which is a different basis and is stated as such rather than differenced
  against them.
- **Today's F is NOT differenced against the 2026-08-08 figures.** That
  comparison is inadmissible for the reason
  [the 2026-08-08 row is the arm's top level](the-2026-08-08-row-is-the-arms-top-level.md)
  established — the arm's own level ladder spans 19 – 20% at one revision, and
  the 2026-08-08 row is that ladder's top rung. The 45%-versus-57% contrast above
  is between each figure and the **allowance**, which is a constant; it is not a
  claim that the rig moved by 21%.
- **Routes (a) and (b) the bead names are not sized here** — a cheaper write that
  falls with B, and a bound charged on an in-window collection witness rather
  than on window size. Both remain design questions and both are untouched.
- **No rig file was edited and no window was taken.** τ untouched.
