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

That report prints, for each of the three readings and both bands, the cell
census, the run-level census, the unpaired Fisher comparisons **and** the
`EXACT PAIRED (McNemar / sign)` table this page's primary contrast is tested
with. The paired table and its `p` are pinned in the reader's `--self-test`.

This window alone is read by naming its records:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs \
  implementation/hicasso/test/re_frame/bench/hicasso/data/revorder-csca8/*.json
```

## The answer, first — and the retraction that followed it

> **RETRACTED 2026-08-21, by the merged-PR audit of #8619 (`rf2-csca8`).** This
> section originally opened *"The SUBSTRATE is a carrier. The pre-registered
> primary contrast separates."* **It does not separate under a test that respects
> its own pairing.** Every count below is unchanged and every one of them is
> still what the records say; what is withdrawn is the inference drawn from them.
> **This window shows the DIRECTION the first declared outcome named. It does not
> resolve the substrate arm standing alone.**

**The direction.** Inside `fixed-reversed`, with the mode held constant and both
cells drawn from the same ten runs, `uix-subs` at position 0 reads **8 of 136**
and `reagent-subs` at position 1 reads **0 of 161** at the 1,050 – 1,224 B band.
At **run level**, the denominator this page treats as the honest one, that is
**5 of 10 runs against 0 of 10**.

**The substrate moved position and the cluster went with it.** What the shared
run holds constant is every **per-run** term: the mode, the session, the
revision, the box that minute, and the level the run settled at.

**POSITION IS NOT ONE OF THEM, and this page said it was.** The contrast compares
`uix-subs` at position **0** with `reagent-subs` at position **1** — that is how
a round is driven, and moving `uix-subs` to position 0 is the entire purpose of
the reversed arm. So position is **deliberately changed** across the two cells,
not held fixed. The broader crossover evidence may still distinguish position —
the POSITION rows and the `parity` alternation are where it is tested — but this
primary contrast alone cannot be described as holding it constant.

### The primary contrast is PAIRED, and it was first tested as though it were not

The two cells are the two arms of **the same ten runs**. That is exactly what
makes this the primary contrast rather than a matched one — and it means the
run-level census is **ten pairs**, not two independent samples of ten. The
originally published run-level `p = 0.0325` came from `fisherExactTwoSided(5, 5,
0, 10)`, an **independent-sample** test, which discards the pairing at the one
step the whole design exists to supply.

Read as pairs, at the 1,050 – 1,224 B band, over this window's own thirty
records:

| Reading | pairs | both | `uix` only | `reagent` only | neither | discordant | exact two-sided paired `p` |
|---|---|---|---|---|---|---|---|
| **`any-leg`** — this window's pre-registered reading | 10 | 0 | **5** | 0 | 5 | 5 | **0.0625** |
| `signed-furthest` — the earlier pre-registered maximum | 10 | 0 | 3 | 0 | 7 | 3 | 0.25 |

Five discordant pairs, all falling the same way. The exact two-sided result is
**2 / 2⁵ = 0.0625** — the McNemar exact test, equivalently the sign test, and
arithmetic a reader can check without running anything. **Concordant pairs carry
no information about which cell carries the term and are discarded**, which is
what makes this a statement about ten paired runs rather than about 297 windows.

**The window-level `p = 0.0017` cannot stand in for it.** That figure counts each
window as an independent trial, and this page says in three separate places that
they are not; borrowing it to rescue a run-level claim would be using the bound
this page publishes as though it were not there.

**The pooled figure is withdrawn too, and for a second and simpler reason.** This
section originally read *"Pooled with the fifteen-run window's five reversed runs
it is 6 of 15 against 0 of 15, p = 0.0169."* **That count does not reproduce
under any reading and either band.** The fifteen-run window carries a
`reagent-subs` run-hit of its own — the run-level census reads its reversed
`reagent` cell as 1 of 5 under all three readings — so the pooled `reagent` arm
is **1 of 15** and never 0. Read as pairs the pooled fifteen are 1 both, 8 `uix`
only, 0 `reagent` only, 6 neither: eight discordant pairs, paired `p` = 0.0078.
**And it would not be confirmatory even so.** Those five older records **informed
the selection of `any-leg`**, which this page's own pre-registration labels
POST-HOC over every record predating this window. A post-hoc population cannot
convert a pre-registered window into confirmatory evidence, and pooling it in was
the wrong move whatever the arithmetic came to.

### What that leaves standing, stated exactly

- **The direction is as the first declared outcome named**, and the `reagent`
  cell is empty in all 161 of its windows and all ten of its runs.
- **The pre-registered primary does not reach a conventional two-sided level
  under the exact paired test**: `p` = 0.0625.
- **No decision threshold was pre-registered**, so "does not reach 0.05" is
  itself a convention applied after the fact, and is stated here as such rather
  than dressed as the window's own rule.
- **The substrate arm is therefore NOT resolved by this window standing alone**,
  and the page's earlier claim that it was is withdrawn above.

**No one-sided test is offered here, and offering one would be the same mistake
in a new place.** The pre-registration named the within-run contrast but no
paired test, no sidedness and no decision threshold; the only sidedness fixed in
advance is the one the reader committed alongside it computes, and every
comparison in that reader is two-sided. Declaring a direction **after** seeing
which way the five discordant pairs fell would halve the `p` by choosing the test
once the answer was known — the exact thing pre-registration exists to prevent,
and the exact mistake the masking episode above already cost this record once. If
a one-sided test is defensible it is a decision for a **future** window's
pre-registration, argued on the record before its runner is invoked, and never a
retrofit onto this one.

### And the statistic is what had been blocking it — this window shows that on its own records

The fifteen-run window said the substrate arm was blocked by a **defect in the
statistic rather than a shortage of runs**. That is now measured rather than
argued, on one set of thirty records read two ways:

| Reading, at the 1,050 – 1,224 B band | CARRIER contrast, windows | unpaired `p` | CARRIER contrast, pairs | paired `p` |
|---|---|---|---|---|
| `signed-furthest` — the earlier pre-registered worst-leg maximum | 3 of 136 vs 0 of 161 | 0.0949 | 3 discordant of 10 | 0.25 |
| `any-leg` — this window's pre-registered masking-free reading | 8 of 136 vs 0 of 161 | **0.0017** | 5 discordant of 10 | **0.0625** |

**Both readings are kept, and neither is discarded for the other.** The
masking-free reading finds more than twice the windows and nearly twice the
discordant pairs on the same records, which is what says the masking was real and
that the reading did have to change before the runs.

> **RETRACTED 2026-08-21.** This paragraph originally read *"At double the
> reversed-arm n the old statistic still cannot resolve the question, and the new
> one resolves it on the same data. More runs would not have fixed this."* **The
> second sentence is withdrawn.** `3 of 136` against `0 of 161` at n = 10
> establishes that **this** window did not resolve the question, not that a
> persistent nonzero rate never could at a larger n.

**This window's own records show why.** With `b` discordant pairs all falling one
way, the smallest attainable two-sided paired `p` is `2 / 2^b`: five discordant
pairs cannot go below 0.0625 however clean they are, six reach 0.03125, nine
reach 0.0039. That is a fact about the design, not about the data — and the same
ten runs' **`fixed` arm**, tested with the same exact paired test at the same
band, reaches **`p` = 0.0039 on nine discordant pairs**. A paired exact test
resolves perfectly well at ten pairs when the discordance rate is high enough.
What the reversed cell bought was five discordant pairs. **So what the reading
fixed was the masking; what n bought was the number of discordant pairs, and n is
not shown to be irrelevant to it.**

The masking is visible as a rate. Of the windows carrying an in-band leg, the
share where that leg **is** the window's worst runs at **79%** in
`fixed | uix-subs | pos1` and **38%** in `fixed-reversed | uix-subs | pos0` —
because position 0 is where the ~748 B rider wins the maximum. The fifteen-run
window measured the same asymmetry as 80% against 20%.

### The other two arms

- **POSITION stays refuted, now at higher n and under the masking-free reading.**
  With `reagent-subs` in the second-driven slot, that slot carries nothing:
  **28 of 143 against 0 of 161**, p = 1.43e-10, and **10 of 10 runs against 0 of
  10** at run level. *(Corrected 2026-08-21: this line originally read "1 of 10".
  No cell of this window reads 1 of 10 in that contrast under either band — the
  `fixed-reversed` `reagent-subs` pos1 cell is 0 of 10 runs at 1,050 – 1,224 B
  and 4 of 10 at 1,000 – 1,300 B. The count is now pinned in the reader.)* This
  is a **between-mode** contrast and carries the repeated-measures bound in full;
  it is not paired and no paired figure is offered for it.
- **MODE is a SECOND term, on top of the substrate rather than instead of it.**
  Reversing the order does not abolish the cluster at `uix-subs`, it cuts it:
  **28 of 143 under `fixed` against 8 of 136 under `fixed-reversed`**,
  p = 6.21e-4, both at `uix-subs`. And the forward order stands well above parity
  at the matched cell — 28 of 143 against 3 of 66, p = 0.0033.

**So the bead's three-way question points at two terms and not one:** the
direction of the substrate arm is as declared but is **not resolved by this
window standing alone** (see the retraction above), the forward fixed order
multiplies the rate at which the cluster appears, and the within-round position
carries nothing.

## What this window does NOT settle

- **It does not separate `fixed-reversed` from `parity` at `uix-subs`.** That
  contrast is **8 of 136 against 5 of 140, p = 0.4069** — a failure to reject, on
  counts too small to bound anything. What is established is the **within-mode**
  substrate contrast, not a reversed-versus-parity difference.
- **It names no mechanism**, and nothing here proposes one.
- **It does not resolve the substrate arm.** The pre-registered primary reads
  `p` = 0.0625 under the exact paired test its own design licenses. The direction
  is the declared one; the resolution claimed in the original text of this page
  is retracted above.
- **Every window-level p carries the repeated-measures bound in full.** Fisher
  treats each window as an independent trial and they are not. The run-level
  census is published beside every one of them — and for the primary contrast the
  run level is **ten pairs**, tested as pairs. *(Corrected 2026-08-21: this
  bullet originally ended "and the primary contrast is the one that survives at
  run level". It does not survive at run level once the pairing is respected.)*
  **No cluster-robust or mixed-effects estimate is offered and none should be** —
  at ten clusters per arm a variance component is not identifiable and a
  cluster-robust standard error is badly biased.
- **τ did not move, the band did not move, and `p0_run.cjs` was not edited.**

## A gap in the pre-registration, recorded rather than repaired

**The pre-registration above names the within-run contrast and names no test for
it.** It fixes the statistic (`any-leg`), the band, the run count, the stopping
rule, the interleaving and the admissibility rule — and then declares CARRIER as
the primary without naming a **paired test**, a **sidedness** or a **decision
threshold**. What landed retrofitted an independent-sample two-sided Fisher onto
a contrast whose whole standing is that it is paired.

**The pre-registration text is left standing as written**, on the same principle
the band limitation below is left standing: rewriting a pre-registration once its
outcome is known is the thing pre-registration exists to prevent, and a page that
silently repaired its own declaration would be worth less than one that flags it.

Two things partly fill the gap and neither closes it. **Sidedness** was fixed in
advance, but only indirectly: the reader committed with this pre-registration
computes every comparison two-sided, which is why the paired figure above is
two-sided and why a switch to one-sided now would be a retrofit. **A threshold**
was fixed nowhere at all, so the 0.05 the retraction above measures against is a
convention borrowed after the fact and is labelled as one.

**What is not offered here is a continuation.** Designing the window that would
settle the substrate arm is a fresh pre-registration, argued before its runner is
invoked, and nothing on this page is that.

## A limitation of the band, found after this window closed

**Recorded, not repaired. The band did not move and must not: it was pre-registered
and it is fenced.**

While this window was running, `rf2-0eu1s`'s re-derivation landed on
`worker/floorderv-cluster` (PR #8620), record
`docs/design/hicasso/studio/the-floor-is-two-populations.md` — **cited by path
rather than linked, because at this commit it is on that branch and not on
`main`, and a link to it would be a broken target here.** **Verified first-hand on that
branch rather than taken from the report:** over 242 committed null-arm cells the
sorted values run 0 to 21, then **nothing at all**, then 44.5 to 135.5 — mode 1
holds 197 cells, mode 2 holds 45, and the span between them holds **zero of 242**.
Those are two disjoint populations, not one distribution with a heavy end. A
pooled p90 over them is therefore **not a magnitude**; it reports only which
population the 90th-percentile index happened to fall in, which is why a band cut
at ten times it can swing by a factor of thirteen between windows whose centres
agree to 1.5 B/boundary.

**This does not reach this window's band, and the reason is structural rather
than lucky: this band was never derived from a quantile at all.** It is the
bead's own observed cluster range, and what licenses reading it is a **direct,
unit-matched in-band count on the null arm** — 0 of 41,862 control legs on the
committed corpus and 0 of 9,642 on this window's own controls, **0 of 51,504
together**. That count is indifferent to how the null arm partitions.

*(Removed 2026-08-21, per the downstream-consumer audit filed on `rf2-0eu1s`: a
sentence here compared mode 2's maximum of 135.5 **B/boundary** against this
band's floor of 1,050 **B**. That is the very unit crossing the paragraph below
condemns, made two paragraphs above condemning it. The direct in-band count is
unit-matched and licenses the band on its own, so nothing is lost by deleting the
comparison rather than converting it — a converted figure would re-open the same
question.)*

**One sentence in the pre-registration above is wrong, and it is fairer to say so
than to quietly edit it.** It compares the band floor to `rf2-0eu1s`'s pooled p90
of 61 and calls it "roughly seventeen times that measured p90". That comparison
is defective **twice**: it leans on exactly the pooled p90 the finding above shows
is a mode-selector rather than a scale, and it crosses units — the null-floor
figures are **B/boundary**, while this band and the cluster it describes are leg
excesses in **B** over a window's own leg median. **It was decorative and never
load-bearing** — the justification the band actually rests on is the direct
in-band count, which is unit-matched by construction, since
`alloc_cluster_carrier.cjs` computes control-leg excesses over their own leg
median exactly as it computes the arms'. **The pre-registration text is left
standing as written**, because rewriting a pre-registration after its outcome is
known is the thing pre-registration exists to prevent, and a page that silently
repaired its own declaration would be worth less than one that flags it.

**Nothing here widens anything.** τ is untouched, the band is untouched, and no
term is admitted that was not admitted before.

## Controls, published before the comparatives were read

| Control | Reading |
|---|---|
| Admissibility | **all 30 runs control-certified**; the report carries no INADMISSIBLE entry for this window |
| Null arm | **9,642 control legs, 0 in the 1,000 – 1,300 B band** |
| Same-session positive control | `uix-subs` under `fixed` at pos1 reads **28 of 143 (19.6%)** here, against 20.8% (21 of 101) on the corpus as it stood before this window — the instrument was not asleep |
| Reader control | on the pooled corpus the published 14-run parity figures still reproduce exactly — **387 collection-free windows, 182 / 205 by position, 101 + 11 rider legs** — so the window extraction has not drifted |
| Arm drives correctly | every `fixed-reversed` round drove `uix-subs` then `reagent-subs`; every `fixed` round the reverse; `parity` alternated |

### The level read

**7 of 10 `fixed`, 9 of 10 `fixed-reversed` and 8 of 10 `parity` runs settled in
the high mode** — a much higher share than the fifteen-run window's one per arm.
The box moved into the high mode partway through this session and largely stayed
there.

**It is balanced across the three arms, and the primary contrast cannot be
touched by it at all**, because both of that contrast's cells come from the same
runs: whatever level a run settled at, it settled there for `uix-subs` and
`reagent-subs` alike. This is reported because an unexplained excursion reported
plainly is worth more than a clean-looking number taken over it.

## Deviations from the pre-registration, declared

**Two, both recorded rather than smoothed over.**

1. **One aborted attempt, which produced no record.** `parity-3`'s first attempt
   was killed by the harness runtime cap at round 14 of 18. The runner writes its
   record only at the end, so **no file was written and no partial record exists**;
   it was then re-taken in its pre-registered slot in the cycle. **This is not a
   re-roll** — no completed record was inspected, discarded or replaced, and the
   distinction is the one that matters for a pre-registered series.
2. **The row's exit verdict changed partway through.** The first eleven runs
   exited 1 on the falls gate and the leg-tolerance gate — this row's normal
   verdict, and what all fifteen runs of the previous window did. Runs 12 – 30
   exited 0. The box changed state mid-session, and the level read above shows the
   same shift. **Strict interleaving is exactly what bounds this**: a drift over
   wall-clock is spread evenly across the three arms by construction, and the
   primary contrast is within-run, so it is immune to it rather than merely
   protected from it.

Otherwise the pre-registration held to the letter: **30 runs taken, none dropped,
none re-rolled, the stopping rule not extended on a null or cut short on a
positive**, the band and τ untouched in either direction, and the rig frozen at
the blob the pre-registration pins.

## Where this leaves `rf2-csca8`

*(Rewritten 2026-08-21. This section originally read: "All three arms now have an
answer: substrate YES, position NO, mode YES as a second and separate term. The
one arm that had resisted — the substrate — is settled by a pre-registered,
within-run contrast." The substrate half of that is retracted.)*

The bead asked which of three properties carries the cluster.

- **POSITION: refuted**, at higher n and under the masking-free reading, and the
  reversed arm is what refuted it. That arm is closed.
- **MODE: a second and separate term.** Reversing the order cuts the rate at
  `uix-subs` without abolishing it, and the forward order stands above parity at
  the matched cell. Between-mode and carrying the repeated-measures bound, but
  the direction is consistent across two matched sessions.
- **SUBSTRATE: the direction is as declared, and the arm is NOT settled by this
  window standing alone.** The pre-registered within-run contrast reads
  `p` = 0.0625 under the exact paired test its own design licenses. The `reagent`
  cell is empty in all 161 windows and all ten runs, which is a real asymmetry
  and is why the direction is stated at all — but five discordant pairs cannot
  reach a conventional two-sided level, and no threshold was pre-registered
  either way.

**The masking finding stands and is the durable part of this window.** The reason
the substrate arm had resisted was a property of the statistic and not of the
corpus, and that is measured here on one set of records read two ways rather than
argued. What does not follow from it is that the arm is now closed.

**τ did not move, the band did not move, and `p0_run.cjs` was not edited.**
