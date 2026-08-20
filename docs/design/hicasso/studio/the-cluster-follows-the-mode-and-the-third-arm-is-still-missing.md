# The cluster follows the mode, and the third arm is still missing

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-csca8`, which asks which of three
properties carries the ~1,050–1,224 B cluster that
[the rider follows the position, not the substrate](the-rider-follows-the-position-not-the-substrate.md)
filed rather than chased: the uix SUBSTRATE, the POSITION in the round, or the
segment-order MODE.

**No allocation window was taken for this page, and no rig file was edited.**
Every figure is re-derived from datasets already committed under
`implementation/hicasso/test/re_frame/bench/hicasso/data/`. The extraction it
rests on is `alloc_position_confound.cjs` at the identical blob
`cd1a80711ca1846cc6e4c5cfece0bbc6ad673164` — unchanged by this correction, which
touched only the admissibility filter and the claims, never the window
extraction.

> **CORRECTED 2026-08-21, in two places, and the second is not a variant of the
> first.**
>
> **(a) The corpus admitted two control-refused runs.** The reader filtered for
> `plan=floor`, `falls === 0` and `controlSlot=first` and never asked whether the
> run's own positive control had certified. `alloc-77gz8/run12-a4a1537cb71` and
> `alloc-9jrhi/bisect-5-a-4a1537cb71-replicate` both carry
> `alloc.controlVerdict.ok = false`, and the second is the exact run
> [the eight signs are one block](the-eight-signs-are-one-block.md) already
> excludes on this same corpus — so the tree contradicted itself about one
> dataset. The corpus is now **116 runs, 3,258 collection-free windows, 3,090 in
> the position tables**, against 118 / 3,308 / 3,140 before. **Every
> primary-band numerator is unchanged and every denominator moved**, which is
> the signature of this repair: neither refused run carried an in-band window,
> so nothing claimed positively here rested on them — but every rate they sat in
> was computed over too many windows.
>
> **(b) The inferential verdicts outran the design, and have been weakened.**
> Fisher was applied to individual windows as though they were independent
> trials. **They are not**: windows repeat within runs, and the whole `fixed`
> exposure is three runs in one session. `POSITION — REFUTED` was a
> non-significant `p` read as evidence of no difference, and `MODE — CONFIRMED`
> rested on a pooled comparison the page itself concedes is uncontrolled for
> session, date and revision. Both now read as what the design supports. **No
> test was swapped for a smaller `p`**; the arithmetic is unchanged and the
> claims came down to meet it.

**τ was not moved, in either direction.** No gate, band, threshold, budget or
tolerance on this rig was widened, narrowed or touched, and nothing here reads
one.

## The answer, first

**The MODE is the only arm still standing, and it is an ASSOCIATION rather than
a result. Position is unresolved, not refuted. Substrate is associated but not
necessary. The discriminating arm the bead asks for was OWED A WINDOW when this
page was written — and more plainly than the first version of this page implied,
because the one property that survives is the one the committed corpus cannot
separate and the one whose exposure is three runs.**

> **THAT WINDOW HAS SINCE BEEN TAKEN, so the last clause above records what was
> outstanding when this page was written and is not its current position.**
> [The reversed fixed arm: a pre-registered fifteen-run window](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
> recorded fifteen runs and moves two of the three verdicts in the table below:
> the SLOT reading is refuted, MODE gains the matched baseline this page says it
> lacks but narrows to the **forward** fixed order, and SUBSTRATE is still
> unsettled — for a reason this page did not anticipate. The reconciliation on
> this page is the blockquote closing
> [what the p-values are not](#what-the-p-values-are-not); the figures behind it
> belong to the window's own record and are quoted from there. **Every number
> here is read on the 116-run corpus of [the corpus](#the-corpus) below, and none
> is re-derived against the 131-run corpus that window produced.**

| arm of the question | verdict | the counts it rests on | Fisher, two-sided |
|---|---|---|---|
| POSITION | **UNRESOLVED** — no difference detected, equality not established | `parity` uix pos0 8 of 733 against pos1 3 of 712 | `p` = 0.2253 |
| SUBSTRATE | **ASSOCIATED, NOT NECESSARY** | `parity` uix 11 of 1,445 against reagent 2 of 1,564 | `p` = 0.0102 |
| MODE | **ASSOCIATED, NOT ESTABLISHED AT A MATCHED BASELINE** | `fixed` uix pos1 8 of 38 against `parity` uix pos1 3 of 712 | `p` = 3.02e-9 |

**Every `p` in that table counts WINDOWS as independent trials, and they are
not.** Read [what the p-values are not](#what-the-p-values-are-not) before
quoting any of them; the run-level counts are 3 runs against 107.

- **The position arm gained power and still found nothing — which is not the
  same as finding nothing is there.** It now rests on 1,445 windows instead of
  43, and the earlier read's verdict from the three paired `parity` runs alone
  (1 of 20 against 1 of 23, `p` = 1.0) reproduces exactly. But `p` = 0.2253 is a
  failure to reject, and the point estimate is an odds ratio of about **2.6** in
  favour of position 0. A position effect of that size is entirely inside what
  this evidence permits. **No equivalence bound is offered here and none is
  computable from these counts at a useful width.**
- **"Not one reagent window lands in the band" does not survive the widening.**
  On 1,564 `parity` reagent windows, two land in the bead's band and fifteen
  land in the wider one the bead states its comparison in. The substrate
  ASSOCIATION survives and is now properly powered; the NECESSITY claim does not.
  This one is a genuine refutation, because it rests on OBSERVING the thing
  claimed impossible rather than on failing to observe a difference.
- **The mode contrast is the largest thing on this page and the least matched.**
  Against the whole committed `parity` corpus it is `p` = 3.02e-9, pooling 107
  runs across several sessions, dates and revisions. Against the three `parity`
  runs taken in the same session, on the same box, on the same revision,
  interleaved with the `fixed` runs, it is **`p` = 0.1344** — and at run level,
  **3 of 3 `fixed` runs against 1 of 3 `parity` runs, `p` = 0.4**. The pooled
  figure is a large association worth the reversed-`fixed` arm. It is not a
  demonstration that the mode carries the term.

## The two bands are not one question

This is the methodological finding, and it has to come before the census because
every number after it depends on which band is meant.

`rf2-csca8` names **two** bands. It defines the cluster by its observed extremes,
**1,050–1,224 B**, and it states its `parity` comparison in a wider
**1,000–1,300 B**. On three runs those two choices agreed. On the full committed
corpus they do not, and the disagreement is not a rounding artefact — **the wide
band admits a second and different term.**

The evidence is the leg ordinal. Every floor window has six work legs, so an
in-band excess can be indexed by which leg carried it:

| mode \| segment \| position | in-band legs by ordinal, 0 → 5 | total |
|---|---|---|
| `fixed` \| `uix-subs` \| pos1 | 2, 1, 0, 2, 6, **0** | 11 |
| `parity` \| `uix-subs` \| pos0 | 0, 1, 2, 0, 2, **42** | 47 |
| `parity` \| `reagent-subs` \| pos0 | 11, 8, 0, 2, 0, **0** | 21 |
| `parity` \| `uix-subs` \| pos1 | 1, 2, 5, 16, 2, **1** | 27 |

**`parity | uix-subs | pos0`'s in-band legs are the LAST leg of the window, 42
times out of 47. The `fixed` cluster never lands there, not once in 11.** Two
populations that disagree that completely about which leg carries them are not
being counted by one criterion, and the split shows in the magnitudes too: of
the 44 `parity | uix-subs | pos0` windows the wide band catches, only **8** sit
inside the bead's own 1,050–1,224.

So a last-leg term of its own lives at `parity` position 0, and the wide band
sweeps it in. **This page therefore reports both bands everywhere and takes the
bead's own 1,050–1,224 as primary**, because that is the band the cluster was
defined by. Under the wide band the position comparison INVERTS — 44 of 733
against 3 of 712, `p` = 1.63e-10 — and that inversion is a fact about the
last-leg term, not about the cluster.

**The last-leg term is not named here and is not this bead's.** It is recorded
so the band choice is legible, and because a later reader pooling the `parity`
corpus for any purpose will meet it.

## The corpus

| corpus | admissible runs | segment order | control slot |
|---|---|---|---|
| `segorder-rs8q6/fixed-{1,2,3}` | 3 | `fixed` | `first` |
| `segorder-rs8q6/parity-{1,2,3}` | 3 | `parity` | `first` |
| `alloc-c4hhk` | 69 | `parity` | `first` |
| `alloc-77gz8` | 19 | `parity` | `first` |
| `alloc-9jrhi` | 7 | `parity` | `first` |
| `ctrlslot-rs8q6` | 9 | `parity` | `first`, `last`, `mid` |
| `workcount-n1b9h` | 6 | `parity` | `first` |

116 runs, 4,152 arm windows, **3,258 collection-free** on `falls === 0`. Every
run is `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, B = 24, six
writes, one instrument — checked rather than assumed, because a pooled census
over unlike units would be meaningless and the byte band is absolute.

**This is the corpus AS THIS PAGE READ IT, and every figure below is anchored to
it.** [The reversed fixed arm](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
has since added fifteen runs, taking the admissible corpus to **131 runs, 3,688
collection-free, 3,520 positional** against 116 / 3,258 / 3,090 here. **Nothing
on this page is re-derived against them**, so a figure quoted from here is a
figure about the 116, and the two censuses must not be read across.

**Two `plan=floor` runs are in the directory and out of this corpus**, and the
reader names both rather than dropping them:

| refused run | reason |
|---|---|
| `alloc-77gz8/run12-a4a1537cb71` | `alloc.controlVerdict.ok = false` |
| `alloc-9jrhi/bisect-5-a-4a1537cb71-replicate` | `alloc.controlVerdict.ok = false` |

**A failed positive control is not an observation.** The control certifies that
the instrument was reading correctly while the run was taken, so a refused
control voids the arm windows beside it — they are not evidence in either
direction. Between them the two runs contributed 50 collection-free windows to
the first version of every census on this page.

**168 windows are in the corpus and out of the position tables.** Under
`controlSlot` `last` and `mid` the three control windows do not precede position
0, so "position 0" stops naming the same event; the six non-`first`
`ctrlslot-rs8q6` runs are excluded from every position figure and the reader
prints the count it dropped.

## The census

Worst leg per window, over the window's own leg median, collection-free windows,
`controlSlot = first`:

| mode | segment | position | windows | in 1,000–1,300 | rate | in 1,050–1,224 | rate |
|---|---|---|---|---|---|---|---|
| `fixed` | `reagent-subs` | 0 | 43 | 0 | 0.0% | 0 | 0.0% |
| `fixed` | `uix-subs` | 1 | 38 | **8** | **21.1%** | **8** | **21.1%** |
| `parity` | `reagent-subs` | 0 | 666 | 15 | 2.3% | 2 | 0.3% |
| `parity` | `reagent-subs` | 1 | 898 | 0 | 0.0% | 0 | 0.0% |
| `parity` | `uix-subs` | 0 | 733 | 44 | 6.0% | 8 | 1.1% |
| `parity` | `uix-subs` | 1 | 712 | 3 | 0.4% | 3 | 0.4% |

**The `fixed` cell is twenty times the rate of any `parity` cell in the bead's
own band**, and it is the only cell above 1.1%. **Every numerator in the
1,050–1,224 column is what it was before the admissibility repair**; only the
denominators moved, because neither refused run carried an in-band window.

### The same census by RUN, which is the denominator the tests actually have

| mode \| segment \| position | runs | runs with a hit | in-band windows | most in any one run |
|---|---|---|---|---|
| `fixed` \| `reagent-subs` \| pos0 | 3 | 0 | 0 | 0 |
| `fixed` \| `uix-subs` \| pos1 | **3** | **3** | 8 | **4** |
| `parity` \| `reagent-subs` \| pos0 | 107 | 2 | 2 | 1 |
| `parity` \| `reagent-subs` \| pos1 | 107 | 0 | 0 | 0 |
| `parity` \| `uix-subs` \| pos0 | 107 | 8 | 8 | 1 |
| `parity` \| `uix-subs` \| pos1 | 107 | 3 | 3 | 1 |

Two things follow, and they pull in opposite directions.

**In the `fixed` arm's favour**: all three `fixed` runs carry the term, so it is
not one anomalous run. **Against it**: three runs is the entire exposure, one
run supplies half the in-band windows, and the window-level `n` of 38 overstates
the independent evidence by roughly the number of windows a run contributes.
Every `parity` cell, by contrast, is at most one in-band window per run at the
primary band — so the repeated-measures problem bites hardest on precisely the
arm the strongest claim rested on.

### The null arm

Over **37,044 collection-free control legs** across all 116 admissible runs, the
count in the 1,000–1,300 B band is **zero**. The controls run the same window
machinery, the same sampler and the same round schedule as the arms and dispatch
nothing, so a term found in them would be the instrument's rather than the arm's.
It is not there, in either band.

### And a control on the reader itself

Re-derived from the 14 committed `parity` runs the previous record published:
**387 collection-free windows, 182 / 205 by position, 101 + 11 rider legs.**
Those are that record's figures to the digit, which is what says this page's
window extraction has not drifted from the one the earlier counts were taken
with.

**That check is computed BEFORE admissibility, deliberately.** Its job is to
detect drift in the window extraction by reproducing a number an earlier record
published, and that record counted all fourteen runs — one of which
(`bisect-5`) is now inadmissible. Applying the control filter to it would
compare against a figure nobody ever published and turn a cross-check into a
restatement of this reader's own output. **On the admissible thirteen the same
control reads 359 windows, 169 / 190 by position, 92 + 10 rider legs**, and the
reader prints both so neither has to be inferred.

## The three-way question, arm by arm

### POSITION — unresolved, with more power than before and still not enough

Within `parity` the segment order reverses on odd rounds, so **uix occupies both
positions**: 733 windows at position 0 and 712 at position 1. That is the
position arm the bead believed unavailable, and it is already in the tree.

| comparison | band 1,050–1,224 | band 1,000–1,300 |
|---|---|---|
| `parity` uix, pos0 against pos1 | 8/733 vs 3/712, `p` = 0.2253 | 44/733 vs 3/712, `p` = 1.63e-10 |
| `parity` reagent, pos0 against pos1 | 2/666 vs 0/898, `p` = 0.1812 | 15/666 vs 0/898, `p` = 2.51e-6 |

**Under the bead's own band no position effect is DETECTED on 1,445 uix
windows.** That is the correct reading and it is weaker than the one this page
first gave, which said REFUTED.

**`p` = 0.2253 is a failure to reject, not a demonstration of equality.** The
point estimate is an odds ratio of about **2.6** in favour of position 0 — 8 in
733 against 3 in 712 — and on eleven in-band windows total the interval around
that estimate is wide enough to contain a substantial real effect in one
direction and a modest one in the other. **Turning that into "no position
effect" is reading a non-significant `p` as evidence of absence**, which is the
single most common way a null result is overstated.

What would settle it is an equivalence test against a pre-declared margin, and
**this corpus cannot supply one at a useful width**: at these rates, bounding
the position effect below (say) 2× would need an order of magnitude more in-band
windows than the eleven available. So the arm is recorded as UNRESOLVED and the
page does not lean on it.

Under the wide band there is a very large difference, and the ordinal table
above says what it is: the last-leg term, which sits at position 0 and is not
the cluster.

### SUBSTRATE — associated, not necessary

| comparison | band 1,050–1,224 | band 1,000–1,300 |
|---|---|---|
| `parity` pooled, uix against reagent | 11/1,445 vs 2/1,564, `p` = 0.0102 | 47/1,445 vs 15/1,564, `p` = 8.31e-6 |
| `parity` pos0 only, uix against reagent | 8/733 vs 2/666, `p` = 0.1122 | 44/733 vs 15/666, `p` = 4.65e-4 |

uix carries the term about eight times as often as reagent does. **But reagent
carries it.** Two windows in the bead's band and fifteen in the wider one, out
of 1,564. On the 89 reagent windows the earlier read had, the expected count in
the bead's band is 0.11 — so seeing none of them was the likeliest single
outcome and says nothing about necessity. **A zero in a small sample is not an
impossibility result**, and that is the whole of the correction here.

**This arm is the one refutation on the page that the design does support**, and
the reason is worth stating: it rests on OBSERVING the thing claimed impossible,
not on failing to observe a difference. Seventeen reagent windows in band refute
"no reagent window lands in the band" whatever the clustering does, because a
single genuine observation is enough to falsify a universal claim. The
ASSOCIATION beside it — the eightfold ratio — is a window-level `p` and carries
the same caveat as every other on this page.

### MODE — associated, and NOT established at a matched baseline

| baseline | counts | Fisher, two-sided |
|---|---|---|
| whole committed `parity` corpus, uix at pos1 | 8/38 vs 3/712 | `p` = 3.02e-9 |
| the three same-session `parity` runs, uix at pos1 | 8/38 vs 1/23 | `p` = 0.1344 |
| the same three runs, counted by RUN | 3 of 3 vs 1 of 3 | `p` = 0.4 |

The first is 712 windows across **107 runs**, many sessions, several dates and
several revisions. The second is 23 windows taken on one box in one session
interleaved with the `fixed` runs themselves. The third is the same matched
comparison with each run counted once, which is the unit the design actually
randomised over. **They are matched on different things and the pooled one does
not dominate**: it has the apparent power and carries session, date and revision
as uncontrolled terms; the same-session baseline controls all three and has
almost no power; the run-level version has none at all.

**So `p` = 3.02e-9 is a large association, not a result about modes.** The first
version of this page recorded MODE as CONFIRMED and led with that figure. It
should not have: the whole `fixed` exposure is three runs taken in one session,
and no arithmetic on the windows inside them can turn three runs into a
controlled comparison. **A replication of the `fixed` arm at higher n against
same-session `parity` is what would settle it**, and that is an allocation
window rather than an analysis. That the mode arm is the only one still standing
made the reversed-`fixed` window more clearly necessary, not less.

**SINCE SETTLED, and narrowed by the settling.**
[The reversed fixed arm](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
took exactly that replication — five `fixed` and five `parity` runs interleaved
in one session — and reads **4 of 5 `fixed` runs against 0 of 5 `parity` runs,
`p` = 0.0476** at run level. Taken with this page's 3 of 3 against 1 of 3 that is
**7 of 8 against 1 of 8, `p` = 0.0101**, which is the first run-level separation
the record has had that controls session, date and revision. **But the same
window put five `fixed-reversed` runs beside them and they do not carry the
term** — 1 of 68 windows at the primary band — and `fixed-reversed` is exactly as
non-alternating as `fixed`. So what separates from `parity` is the **forward**
fixed order specifically, not "a plan that does not alternate". Those counts are
that window's corpus, not this one's. The paragraph above is left as written: its
reasoning is what commissioned the window.

The negative control on the same contrast: `reagent` at position 0, `fixed`
against `parity`, reads **0 of 43 against 2 of 666, `p` = 1.0**. Reagent does not
move with the mode in these runs. Whatever the mode does, it does to uix — and
that too is three runs.

## What the p-values are not

This section is the one the summary table points at, and it bounds every figure
on this page rather than any one of them.

**Fisher's exact test counts each WINDOW as an independent trial.** The windows
are not independent. A run contributes six to thirteen of them, and anything
that varies by run — the box's thermal and allocator state that minute, the
Chromium build, the revision, the session — is shared across the whole block.
The effective number of independent observations is therefore nearer the RUN
count than the window count, and the two differ by an order of magnitude.

The run-level table in the census above is published for exactly this reason.
Read against it:

- **The `fixed` arm's 38 windows are 3 runs**, one of which supplies half the
  in-band windows. Every `fixed` figure on this page inherits that.
- **The `parity` cells are barely clustered at the primary band** — at most one
  in-band window per run — so the caveat bites hardest on the `fixed` arm, which
  is the arm the strongest claim rested on. That asymmetry is unfortunate and it
  is not correctable by re-analysis.
- **No cluster-robust or mixed-effects estimate is offered.** With three
  clusters in one arm, a random-effects variance component is not identifiable,
  and a cluster-robust standard error on three clusters is badly biased. The
  right response to three clusters is more clusters, not a different estimator —
  which is the reversed-`fixed` arm.

**No test on this page was chosen after seeing its `p`**, and none was swapped
for another when a verdict weakened. The arithmetic is exactly what the first
version computed, on a corrected corpus; what changed is what it is said to
show.

> **THE WINDOW HAS SINCE BEEN TAKEN, and this page's title is now half wrong.**
> [The reversed fixed arm: a pre-registered fifteen-run window](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
> recorded fifteen runs — five `fixed-reversed`, five `fixed`, five `parity`,
> interleaved in one session — so the third arm is no longer missing. Two things
> on this page move as a result, and everything else stands.
>
> **The SLOT reading is refuted.** With `reagent-subs` in the second-driven slot
> the cluster does not go with it: 1 of 82 windows, against the same session's
> 8 of 63 at `fixed | uix-subs | pos1`.
>
> **"MODE" survives but narrows.** The matched baseline this page said the mode
> claim lacked now exists — 7 of 8 `fixed` runs against 1 of 8 `parity` runs
> across two matched sessions, p = 0.0101 — but `fixed-reversed` is exactly as
> non-alternating as `fixed` and does **not** carry the term, so what separates
> from `parity` is the **forward** fixed order specifically and not "a plan that
> does not alternate".
>
> **The SUBSTRATE arm is still not settled**, and the new page names why: the
> statistic below is a per-window MAXIMUM, and at position 0 the already-recorded
> ~748 B rider competes with the cluster for it. So the reversed arm's `uix` cell
> is masked, and the window that was supposed to settle the substrate could not.
>
> **The title is left standing rather than corrected.** It names this record, and
> it is the name both the studio index and the new page link it by; a page
> renamed to its own correction stops being findable as the thing that was
> corrected. It is half wrong, and this is where it says so.

## Why the discriminating arm was owed a window

The bead names its discriminator: *a fixed run with the segment order REVERSED
(uix leading), which `p0_run.cjs` does not currently offer.* **That remains the
right instrument, the reason it is needed has changed rather than gone away —
and since this page was first written the arm itself has LANDED, and has since
been RUN.**

`P0_ALLOC_SEG_ORDER=fixed-reversed` is now one of three names in
`ALLOC_SEG_ORDERS`, and it drives the plan reversed every round: the mode held
constant, the second substrate at position 0. **So what was outstanding when this
page was written was no longer the rig change but the WINDOW** — a
`fixed-reversed` run at adequate n, read against the `fixed` and `parity` arms
already in the corpus. That was bench-slot-bound rather than quiet-box-bound.

> **THE WINDOW HAS SINCE BEEN TAKEN.**
> [The reversed fixed arm](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
> ran five `fixed-reversed` runs at that n, interleaved with five `fixed` and
> five `parity` in one session. **The bead does stay open, but not for the reason
> this section gives.** What is owed now is a **masking-free statistic,
> pre-registered as such, and the reversed arm taken again under it** — because
> the statistic this window pre-registered is a per-window MAXIMUM and it is
> masked in the one cell the whole discriminator rests on. The reasoning below is
> left as written: it is what commissioned the window.

The bead's stated reason was that no position arm existed. That reason is
superseded — `parity` supplies uix at both positions, 1,445 windows of it, and
this page uses exactly that. But it supplies it **under `parity`**, and the one
finding that survived is that something about `fixed` is what carries the term.
Inside `fixed` the confound is untouched:

- Under `fixed` the order never moves, so **uix is always position 1 and reagent
  is always position 0**. Substrate and position are perfectly confounded there,
  in all 81 `fixed` windows.
- So `fixed` uix pos1 at 21.1% is consistent with two readings this corpus
  cannot separate: *uix under `fixed`*, or *second-driven under `fixed`*.
- A `parity` comparison cannot decide between them, because it is the `fixed`
  mode that the effect is attached to.

**A `fixed` run with the order reversed puts uix at position 0 while holding the
mode constant, and it is the only arrangement that does.** If the cluster follows
uix to position 0, the carrier is the substrate under `fixed`; if it stays at
position 1 with reagent now in it, the carrier is the slot. **No committed run
answered that** when this page was written, because none had been taken in the
reversed order.

**Five have been taken since, and the disjunction above did not resolve cleanly.**
On the pre-registered statistic neither branch fires:
`fixed-reversed | uix-subs | pos0` reads **1 of 68** and
`fixed-reversed | reagent-subs | pos1` reads **1 of 82**, `p` = 1.0000 between
them, against the same session's **8 of 63** at `fixed | uix-subs | pos1`.
**The STAYS branch is refuted** — putting `reagent-subs` in the second-driven
slot does not move the cluster there, 8 of 63 against 1 of 82, `p` = 0.0105.
**The FOLLOWS branch is masked rather than
tested**, because position 0 is where the already-recorded ~748 B rider lives and
it competes for the per-window maximum the statistic reads: that cell's median
|worst leg| is **1,488 B**, above the top of the band, and only **20%** of its
in-band legs are the window's worst against `fixed`'s **80%**. Every figure in
this paragraph is that window's own corpus, not this page's; the record is
[the reversed fixed arm](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md).

### What it cost, now that it has been made

This page sized the change at **three lines** and that sizing held. It landed in
`p0_run.cjs` bundled with `rf2-fk6pj` and `rf2-24o2z`: one more name in
`ALLOC_SEG_ORDERS`, one branch in `allocSegmentOrder` returning the reversed
plan every round, and **nothing** in the preflight, which already interpolates
the roster and so refuses an unknown name for free.

The record already carries `segOrder` per run and `segments` per round, and both
readers group on that field rather than recomputing a parity rule, so the third
mode is read correctly by everything on this page without being taught about it.
**No reader, schema or record change was needed, and none was made.**

The outcomes are pre-registered in the source rather than chosen after the run:
the cluster FOLLOWS uix to position 0 and the carrier is the substrate under
`fixed`; it STAYS at position 1 and the carrier is the second-driven slot,
whichever substrate occupies it; BOTH or NEITHER and neither property is the
carrier as stated. **They have since been read, and the pre-registered statistic
returned NEITHER**; what that does and does not settle is recorded above.

## What the earlier read got wrong, and one thing it did not

**The "8 of 38 versus 9 of 38" is an ESTIMATOR difference and neither count is an
error.** "Worst leg" has two readings, and they part on exactly one window in the
corpus. `fixed-2` round 4 `uix-subs` has leg excesses **0, −12, 0, −4,324,
+1,056, 0**. Read as *the excess furthest from the cohort median in either
direction* — which is `legWorstDeviation` re-derived, and the reading the
previous record publishes — that window's worst leg is **−4,324 B** and it is out
of band. Read as *the largest excess above the median* it is **+1,056 B** and it
is in. Eight and nine are the same census under the two readings, and
`alloc_position_confound.cjs` has pinned both since it was written. **A record
quoting one of them owes the reader which**, and this reader prints both columns
so the choice cannot be silent.

That leg is worth its own sentence. **A leg 4,324 B BELOW its cohort is a leg
something removed bytes from, and nothing in the work unit removes bytes — the
collector does.** The window carries `falls === 0` all the same, so the falls
gate did not see it. That is a bound on the gate, recorded here and not acted on:
no gate was changed and none is proposed.

**And the bead's own title is wrong, as its filer suspected.** "Seen only under
the fixed segment order" does not hold: the term appears under `parity` three
times at uix position 1 and eight more at uix position 0 within the bead's band,
out of 1,445 windows. It is **rarer** under `parity` by a factor of about twenty,
not absent.

## What is not concluded

- **No mechanism is named.** Nothing here says what the term is, only what it
  travels with. The `fixed` mode drives the same two segments in the same order
  every round where `parity` alternates them; which consequence of that matters
  is untested.
- **The mode contrast is not established at a matched baseline.** `p` = 3.02e-9
  against 107 pooled runs, `p` = 0.1344 against the three that share its session,
  and `p` = 0.4 when those same three are counted by run. The second and third
  are the ones that control what the first does not, and neither reaches
  significance. **This page therefore records the mode as ASSOCIATED and not as
  confirmed**, which is a weaker claim than its first version made.
- **No `p` here is a run-level test.** Every one counts windows as independent
  trials and windows repeat within runs. See
  [what the p-values are not](#what-the-p-values-are-not).
- **POSITION is unresolved rather than refuted.** `p` = 0.2253 with a point
  estimate near a 2.6x odds ratio is a failure to reject, and no equivalence
  bound is available at a useful width on eleven in-band windows.
- **Three `fixed` runs is three.** 81 windows, one box, one session, one
  revision. Every `fixed` figure on this page rests on them.
- **The reversed-`fixed` arm has SINCE BEEN RUN, and nothing here is read against
  it.** `P0_ALLOC_SEG_ORDER=fixed-reversed` landed after this analysis was first
  written, and
  [a pre-registered fifteen-run window](the-reversed-fixed-arm-a-pre-registered-fifteen-run-window.md)
  has since recorded five runs through it. Every number on this page is still the
  116-run corpus it was computed on, so those runs bear on its verdicts without
  being counted in any of its figures. **The arm answering is not this page
  answering**: what that window settled, what it narrowed and what it could not
  reach are its record to state, not this one's.
- **The last-leg term at `parity` position 0 is described and not chased.** Its
  ordinal signature is published above because it decides the band question; its
  identity is nobody's yet.
- **The falls gate's blind spot is recorded, not remedied.** One window with a
  −4,324 B leg passed it. No gate, band or tolerance was touched, and widening or
  narrowing one is not proposed in either direction.
- **`rf2-e9wr`'s refusal to pin τ stands**, and `rf2-rs8q6`'s fence against
  discharging any of this by widening τ is restated rather than relaxed.

## Reproduction

Every measured figure on this page — every window and leg count, byte value,
rate, ordinal and `p` — is re-derived from the committed records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs --corpus
```

and the same-session subset, which is the only place this page's `p` = 0.1344
comes from, by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs \
  implementation/hicasso/test/re_frame/bench/hicasso/data/segorder-rs8q6/*.json
```

That reader imports its window extraction from `alloc_position_confound.cjs`
rather than restating it, so the two records cannot drift about what a window is,
and it re-derives that record's published 387 / 182 / 205 / 101 + 11 as a control
on itself. Its fixtures run under `--self-test`.

### The statistic, and the convention it is taken under

Every `p` on this page is **Fisher's exact test, two-sided**, by the conventional
definition: the sum of the probabilities of every table whose probability is no
greater than the observed table's.

**Fisher rather than the two-proportion `z` the neighbouring record publishes,
and the reason is the data.** Several cells here carry zero or one success, where
the normal approximation is not defined — `alloc_position_confound.cjs` returns
`null` for exactly that case rather than a number. No `z` is quoted anywhere on
this page, and the two conventions must not be read across.

The two-sided convention is named because doubling the smaller tail is the other
common one and it disagrees on asymmetric margins, which every margin here is.
The reader's fixtures pin the tea-tasting table at 0.4857, `[[0,5],[5,0]]` at
2/252 and `[[1,0],[0,1]]` at exactly 1, so a change to the convention reds rather
than drifting.

### What that command does NOT print

Three kinds of number here are not figures of the runs: the **corpus table's
configuration** (`P0_ALLOC_PLAN`, `P0_ROOTS`, B, the write count) — that is how
the runs were taken, not something read out of them; the **seat, bead and blob
identifiers**; and the **rig sizing table**, which is read off `p0_run.cjs` and
not off any dataset. Everything else on the page comes out of the command.
