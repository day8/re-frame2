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

**The bead's RUNG survives. Its CONCLUSION does not.** The bead states its
uncertifiability argument in the 300,000 B masking bound, and **that bound was
RETIRED AND DELETED on 2026-08-08** (`rf2-2rtt6.141`, on this bead's own
criterion 4), so every `max B` figure in [section 2](#2-the-ladder-arithmetic-re-run)
is a **RECONSTRUCTION of the bead's case, not a live constraint**. **The
impossibility falls with the constant it divides by, and nothing live replaces
it.** Re-running the identical arithmetic against `rf2-onozm`'s observed
**884,280 B** ceiling — [section 4](#4-the-same-question-in-live-terms) — projects
**B ≤ 3** rather than B = 0.

**THAT B ≤ 3 IS A MODEL-CONDITIONED PROJECTION, NOT A NECESSARY CONDITION**, and
the condition is *treat the corpus's largest observed success as a cap*.
Corrected 2026-08-21 on the merged-PR audit of PR #8602, which found this page
presenting it as a necessity. **884,280 B is the highest would-be total any
window in this corpus certified at, and the largest observed success bounds a
real maximum from BELOW, not from above.** Zero certified windows above it, in a
finite corpus sampled only at B = 4, cannot exclude a certifying window higher
up; the record puts the whole 884,280 – 1,028,670 B interval **unsampled**
([the window total is the ceiling](the-window-total-is-the-ceiling.md#3-the-ceiling-which-is-what-the-sixteen-cells-were-measuring)),
and `t(R)` is a one-point linear fit. So read the projection as *"if the observed
ceiling caps the ladder, the ladder admits B ≤ 3"* — never as *"the ladder cannot
exceed B = 3"*. **A real page-size necessity would need evidence at another B,
and no committed dataset holds a window at any page but B = 4.**

**What survives on the evidence alone is a one-rung EMPIRICAL result, and it is
not an impossibility result.** At the B = 4 this corpus runs, R = 20 binds hard —
**0/72 in six of the eight arm families, 24/24 in the other two** — where every
rung beneath it certifies 88 – 95%. That is measured, and it owes the projection
nothing. **Nor is the ceiling SUFFICIENT**: 36 windows sit at or below it and
refuse anyway, 20 of them with no collection at all, so whether B = 3 certifies
is **unmeasured** in the other direction too.

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
  surviving arm family and nothing else. **That is a sharply lower rate in the
  sampled configuration; it is not, and is no longer offered as, a demonstration
  that no page size certifies.**
- **The same arithmetic on the OBSERVED ceiling** — [section 4](#4-the-same-question-in-live-terms),
  fitted per arm family because at R = 20 the pooled rung median falls in a gap no
  family occupies. **Under the cap assumption and nowhere outside it**: R = 1
  projects **B ≤ 31**, R = 3 **B ≤ 16**, R = 7 **B ≤ 8**, R = 20 **B ≤ 3**, taking
  the binding family at each rung. **Stop the ladder at R = 7 and the same
  projection gives B ≤ 8** — where the reconstruction allowed B = 1.
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

**That sentence held on this page until 2026-08-21, and the live restatement
below withdraws it.** `survives today's F unchanged` is true of the
*reconstruction* and of nothing else: the conclusion is an artefact of the
allowance it divides by, and the allowance is deleted.
[Section 4](#4-the-same-question-in-live-terms) re-runs the identical arithmetic
against the observed ceiling and **projects B ≤ 3 on the full ladder, under the
cap assumption that section states**.

**What the `certified` column beside it does and does not establish.** It is
measured and owes the bound nothing, and it locates the binding rung: R = 20
certifies 24/96 where every rung beneath it certifies 88 – 95%. **What it does
not do is carry the bead's conclusion** — a sharply lower certification rate in
the sampled configuration is not a demonstration that no page size certifies, and
**24 R = 20 windows certify at B = 4 already**. `rf2-onozm` reaches the same rung
on the observed 884,280 B ceiling, again without the bound, and that ceiling is
**one-sided in both directions**: 36 windows below it refuse, so it is not
sufficient; and it is the largest observed success, so it caps nothing except by
assumption. **So the RUNG does not depend on the retired constant. The
IMPOSSIBILITY did, and it does not survive.**

**Stop at R = 7 and the reconstruction allows B = 1; the same projection on the
observed ceiling gives B ≤ 8.** Either way it is not a widening — it is the
ladder the four remaining rungs already support, and it is the same disposition
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

## 4. The same question in live terms

Added 2026-08-21 on `rf2-2k3vo`, whose whole subject is that
[section 2](#2-the-ladder-arithmetic-re-run) answers the bead in a constant that
no longer exists. **No window was taken for this section either** — it is the same
528 committed windows read a second way, on base `551d7acb9a`.

The instrument enforces the leg witness and the falls gate, and what those two
exhibit at the window level is the **would-be total**, `6 × legMedian`, whose
highest certifying observation in this corpus is **884,280 B**. So run the bead's
own arithmetic against that instead of against the retired allowance:

> **THE MODEL CONDITION, stated before the arithmetic that assumes it.**
> Everything in this section holds **only if** the corpus's largest observed
> success is treated as a cap on what can certify. It is not one on the evidence:
> 884,280 B is a **lower** bound on any real maximum certifiable total, the
> 168,036 B directly above it is unsampled, and `t(R)` is a single-point linear
> fit at the only page size the corpus holds. **Every `max B` below is therefore
> a PROJECTION under that assumption, not a necessary condition on the ladder.**

```
total(R, B) ≈ T0 + B · t(R)        projected max B = floor((884,280 − T0) / t(R))
                                   ^ conditioned on 884,280 B being a cap
```

where `T0` is the family's **own** floor-arm would-be total and `t(R)` is fitted
at B = 4, the only page the corpus holds. **Fitted per arm family, not per rung**,
because at R = 20 the pooled rung median is representative of nothing: six
families sit at 1.03 – 1.05 MB and two at 0.85 – 0.86 MB, and the pooled 1,035,036 B
falls in a gap no family occupies.

| family | `T0` (B) | projected max B, R = 1 | R = 3 | R = 7 | R = 20 |
|---|---|---|---|---|---|
| `reagent-subs \| lad/reagent @page` | 105,816 | 32 | 17 | 8 | **3** |
| `reagent-subs \| lad/hicasso @page` | 105,816 | 36 | 19 | 9 | **3** |
| `reagent-subs \| lad/reagent @all` | 115,728 | 31 | 16 | 8 | **3** |
| `reagent-subs \| lad/hicasso @all` | 115,728 | 35 | 19 | 9 | **3** |
| `uix-subs \| lad/uix @page` | 108,480 | 60 | 25 | 11 | **4** |
| `uix-subs \| lad/hicasso @page` | 108,480 | 37 | 20 | 9 | **3** |
| `uix-subs \| lad/uix @all` | 118,392 | 59 | 24 | 11 | **4** |
| `uix-subs \| lad/hicasso @all` | 118,392 | 36 | 19 | 9 | **3** |

A ladder holds **one B across all rungs and all families**, so the binding cell
decides — **under the cap assumption above, and only there**:

- **full 1/3/7/20 ladder — projected B ≤ 3**, binding at R = 20;
- **reduced 1/3/7 ladder — projected B ≤ 8**, binding at R = 7.

**The bead's conclusion does not survive**, and it does not survive because the
constant carrying it was deleted rather than because this projection replaces it.
*"At six writes there is no page of one boundary or more that certifies the
1/3/7/20 ladder"* is carried entirely by the retired bound's tightness — a
300,000 B window bracket against an observed 884,280 B ceiling, **2.95×
looser**. **Take the observed ceiling as a cap and the same arithmetic projects
B ≤ 3**: one boundary *under* the page this corpus already runs, not zero. **Do
not read that as the page-size necessity the bead claimed** — it is the same
shape of claim, with an observation in place of the constant, and an observed
maximum is not a cap.

### What the empirical column can and cannot check

At B = 4 the prediction is exact — the two R = 20 families the ceiling admits
certify **24/24**, the six it excludes certify **0/72**, eight of eight families
on the right side. **That agreement is not independent evidence.** $t$ is fitted
at B = 4, so at B = 4 the test `max B ≥ 4` reduces *algebraically* to
`total ≤ ceiling`, which is
[the window total is the ceiling](the-window-total-is-the-ceiling.md)'s own test
restated per family. It confirms the ceiling. It says nothing about the linear
extrapolation to any other page.

### And the ceiling is neither necessary nor sufficient

This is the qualification that keeps the restatement from becoming a second
reconstruction, and it has **two halves**. An earlier version of this section
carried only the second, calling B ≤ 3 a *necessary* condition — struck
2026-08-21 on the merged-PR audit of PR #8602.

- **NOT NECESSARY.** 884,280 B is the corpus's largest observed success, so it
  bounds a real maximum certifiable total **from below**. **Zero of 72 windows
  above it certified**, but a finite corpus sampled at one page size cannot
  exclude a certifying window higher up, and the 168,036 B directly above it was
  never sampled. **So B ≤ 3 is what the arithmetic yields when the observation is
  ASSUMED to be a cap, and nothing follows from it about a ladder that exceeds
  B = 3.** Deciding that needs evidence at another B.
- **NOT SUFFICIENT.** **36 windows sit at or below 884,280 B and refuse anyway,
  20 of them carrying no collection at all.** So B ≤ 3 is **not a certifying
  page**, and nothing here says the ladder certifies at B = 3.
- **No committed dataset holds a window at any page size but B = 4.** Whether
  B = 3 certifies is **unmeasured**, and measuring it needs a window. None was
  taken here, and this page does not propose one.

**What survives, at the strength the evidence carries**: R = 20 is where the
ladder binds, on the observed ceiling as on the retired bound; at B = 4 it binds
hard, 0/72 in six of eight families, against 88 – 95% on every rung beneath it.
**That is a measured one-rung result, not an impossibility result**, and it is
weaker than the claim this page carried before because the evidence never
supported the stronger one. **The B ≤ 3 projection travels beside it as a
conditional, not as a result.**

Every figure above is printed by section 7 of the reader; run it and diff.

## 5. The two beads are one constraint

`rf2-onozm` asks why R = 20 certifies on one arm family of four. This bead asks
whether F makes the ladder uncertifiable at any page size. **They terminate on
the same rung**, and the reader that produces both pages' figures is one script
for that reason.

| | `rf2-onozm` | `rf2-2rtt6.140` |
|---|---|---|
| what it observes | R = 20 certifies 24/96, all on one family | R = 20 is the binding rung: **projected B ≤ 3** on the observed ceiling, B = 0 on the retired reconstruction |
| the quantity | window would-be total, ceiling at **884,280 B** — observed | the same ceiling in [section 4](#4-the-same-question-in-live-terms), taken there as a cap **by assumption**; `perWrite` against the **retired** 42,857 B allowance in [section 2](#2-the-ladder-arithmetic-re-run) |
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

- **The bead is NOT discharged, and what remains of it is smaller than this page
  once said.** Its *rung* survives; its *conclusion* does not. The scope is one
  rung rather than the whole ladder, the route runs through `rf2-onozm`, and the
  warrant is the measured B = 4 certification column rather than a deleted
  constant. **What the live restatement removes is the impossibility**, and it
  puts **no replacement necessity in its place**: the open question is no longer
  *"is any page certifiable?"* but *"does B = 3 certify, and does anything above
  it?"* — both **unmeasured**, because the corpus holds no window at any page but
  B = 4. **Answering either needs a window.**
- **B ≤ 3 IS A MODEL-CONDITIONED PROJECTION, NOT A NECESSARY CONDITION.**
  Corrected 2026-08-21 on the merged-PR audit of PR #8602, which found this page
  and its reader using the observed 884,280 B ceiling as an upper cap. **The
  largest observed success bounds a real maximum from BELOW**; 0 of 72 above it
  in a corpus sampled at one page size excludes nothing, and the 168,036 B
  directly above it is unsampled. The projection is kept, and its condition —
  *treat the corpus's largest observed success as a cap* — is now stated
  wherever it appears ([section 4](#4-the-same-question-in-live-terms)).
- **B ≤ 3 IS NOT A CERTIFYING PAGE either, and must not be read as one.** 36
  windows below the ceiling refuse, 20 with no collection at all. Reading
  `max B` as a page that certifies would repeat, one level down, exactly the
  defect `rf2-2k3vo` was filed to fix.
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
