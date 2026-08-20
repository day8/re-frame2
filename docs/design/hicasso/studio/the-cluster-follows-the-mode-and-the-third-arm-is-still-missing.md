# The cluster follows the mode, and the third arm is still missing

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-csca8`, which asks which of three
properties carries the ~1,050–1,224 B cluster that
[the rider follows the position, not the substrate](the-rider-follows-the-position-not-the-substrate.md)
filed rather than chased: the uix SUBSTRATE, the POSITION in the round, or the
segment-order MODE.

**No allocation window was taken for this page, and no rig file was edited.**
Every figure is re-derived from datasets already committed under
`implementation/hicasso/test/re_frame/bench/hicasso/data/`. The analysis was run
against the tree at commit `a51138e93b`, and the extraction it rests on is
`alloc_position_confound.cjs` at the identical blob
`cd1a80711ca1846cc6e4c5cfece0bbc6ad673164`.

**τ was not moved, in either direction.** No gate, band, threshold, budget or
tolerance on this rig was widened, narrowed or touched, and nothing here reads
one.

## The answer, first

**The MODE carries it. Position is refuted, substrate is associated but not
necessary — and the rig change the bead asks for is STILL REQUIRED, because the
one property that survives is the one the committed corpus cannot separate.**

| arm of the question | verdict | the counts it rests on | Fisher, two-sided |
|---|---|---|---|
| POSITION | **REFUTED** | `parity` uix pos0 8 of 747 against pos1 3 of 724 | `p` = 0.2254 |
| SUBSTRATE | **ASSOCIATED, NOT NECESSARY** | `parity` uix 11 of 1,471 against reagent 2 of 1,588 | `p` = 0.0102 |
| MODE | **CONFIRMED** | `fixed` uix pos1 8 of 38 against `parity` uix pos1 3 of 724 | `p` = 2.67e-9 |

- **The position refutation now rests on 1,471 windows instead of 43**, which is
  the whole of what this page adds to it. The earlier read reached the same
  verdict from the three paired `parity` runs alone — 1 of 20 against 1 of 23,
  `p` = 1.0 — and that reproduces here exactly. It was right, and it was right
  on 2.9% of the evidence available.
- **"Not one reagent window lands in the band" does not survive the widening.**
  On 1,588 `parity` reagent windows, two land in the bead's band and fifteen
  land in the wider one the bead states its comparison in. The substrate
  ASSOCIATION survives and is now properly powered; the NECESSITY claim does not.
- **The mode contrast is the strongest thing on this page and the least
  matched.** Against the whole committed `parity` corpus it is `p` = 2.67e-9;
  against the three `parity` runs taken in the same session, on the same box, on
  the same revision, interleaved with the `fixed` runs, it is **`p` = 0.1344**.
  Both are stated below and neither is dropped.

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
| `parity` \| `uix-subs` \| pos0 | 0, 1, 2, 0, 2, **43** | 48 |
| `parity` \| `reagent-subs` \| pos0 | 11, 9, 0, 2, 0, **0** | 22 |
| `parity` \| `uix-subs` \| pos1 | 1, 2, 5, 17, 2, **1** | 28 |

**`parity | uix-subs | pos0`'s in-band legs are the LAST leg of the window, 43
times out of 48. The `fixed` cluster never lands there, not once in 11.** Two
populations that disagree that completely about which leg carries them are not
being counted by one criterion, and the split shows in the magnitudes too: of
the 45 `parity | uix-subs | pos0` windows the wide band catches, only **8** sit
inside the bead's own 1,050–1,224.

So a last-leg term of its own lives at `parity` position 0, and the wide band
sweeps it in. **This page therefore reports both bands everywhere and takes the
bead's own 1,050–1,224 as primary**, because that is the band the cluster was
defined by. Under the wide band the position verdict INVERTS — 45 of 747 against
3 of 724, `p` = 8.82e-11 — and that inversion is a fact about the last-leg term,
not about the cluster.

**The last-leg term is not named here and is not this bead's.** It is recorded
so the band choice is legible, and because a later reader pooling the `parity`
corpus for any purpose will meet it.

## The corpus

| corpus | runs | segment order | control slot |
|---|---|---|---|
| `segorder-rs8q6/fixed-{1,2,3}` | 3 | `fixed` | `first` |
| `segorder-rs8q6/parity-{1,2,3}` | 3 | `parity` | `first` |
| `alloc-c4hhk` | 69 | `parity` | `first` |
| `alloc-77gz8` | 20 | `parity` | `first` |
| `alloc-9jrhi` | 8 | `parity` | `first` |
| `ctrlslot-rs8q6` | 9 | `parity` | `first`, `last`, `mid` |
| `workcount-n1b9h` | 6 | `parity` | `first` |

118 runs, 4,224 arm windows, **3,308 collection-free** on `falls === 0`. Every
run is `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, B = 24, six
writes, one instrument — checked rather than assumed, because a pooled census
over unlike units would be meaningless and the byte band is absolute.

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
| `parity` | `reagent-subs` | 0 | 675 | 15 | 2.2% | 2 | 0.3% |
| `parity` | `reagent-subs` | 1 | 913 | 0 | 0.0% | 0 | 0.0% |
| `parity` | `uix-subs` | 0 | 747 | 45 | 6.0% | 8 | 1.1% |
| `parity` | `uix-subs` | 1 | 724 | 3 | 0.4% | 3 | 0.4% |

**The `fixed` cell is twenty times the rate of any `parity` cell in the bead's
own band**, and it is the only cell above 1.1%.

### The null arm

Over **37,680 collection-free control legs** across all 118 runs, the count in
the 1,000–1,300 B band is **zero**. The controls run the same window machinery,
the same sampler and the same round schedule as the arms and dispatch nothing,
so a term found in them would be the instrument's rather than the arm's. It is
not there, in either band.

### And a control on the reader itself

Re-derived from the 14 committed `parity` runs the previous record published:
**387 collection-free windows, 182 / 205 by position, 101 + 11 rider legs.**
Those are that record's figures to the digit, which is what says this page's
window extraction has not drifted from the one the earlier counts were taken
with.

## The three-way question, arm by arm

### POSITION — refuted, and now with power behind it

Within `parity` the segment order reverses on odd rounds, so **uix occupies both
positions**: 747 windows at position 0 and 724 at position 1. That is the
position arm the bead believed unavailable, and it is already in the tree.

| comparison | band 1,050–1,224 | band 1,000–1,300 |
|---|---|---|
| `parity` uix, pos0 against pos1 | 8/747 vs 3/724, `p` = 0.2254 | 45/747 vs 3/724, `p` = 8.82e-11 |
| `parity` reagent, pos0 against pos1 | 2/675 vs 0/913, `p` = 0.1805 | 15/675 vs 0/913, `p` = 2.44e-6 |

**Under the bead's own band there is no position effect on 1,471 uix windows.**
Under the wide band there is a very large one, and the ordinal table above says
what it is: the last-leg term, which sits at position 0 and is not the cluster.

### SUBSTRATE — associated, not necessary

| comparison | band 1,050–1,224 | band 1,000–1,300 |
|---|---|---|
| `parity` pooled, uix against reagent | 11/1,471 vs 2/1,588, `p` = 0.0102 | 48/1,471 vs 15/1,588, `p` = 5.31e-6 |
| `parity` pos0 only, uix against reagent | 8/747 vs 2/675, `p` = 0.1126 | 45/747 vs 15/675, `p` = 3.21e-4 |

uix carries the term about eight times as often as reagent does. **But reagent
carries it.** Two windows in the bead's band and fifteen in the wider one, out
of 1,588. On the 89 reagent windows the earlier read had, the expected count in
the bead's band is 0.11 — so seeing none of them was the likeliest single
outcome and says nothing about necessity. **A zero in a small sample is not an
impossibility result**, and that is the whole of the correction here.

### MODE — confirmed, and stated at two strengths

| baseline | counts | Fisher, two-sided |
|---|---|---|
| whole committed `parity` corpus, uix at pos1 | 8/38 vs 3/724 | **`p` = 2.67e-9** |
| the three same-session `parity` runs, uix at pos1 | 8/38 vs 1/23 | **`p` = 0.1344** |

The first is 724 windows across 112 runs, many sessions, several dates and
several revisions. The second is 23 windows taken on one box in one session
interleaved with the `fixed` runs themselves. **They are matched on different
things and neither dominates**: the wide baseline has the power and carries
session, date and revision as uncontrolled terms; the same-session baseline
controls all three and has almost no power.

The honest reading is that the mode contrast is large, is the only arm that
survives, and is **not yet established at a matched baseline**. A replication of
the `fixed` arm at higher n against same-session `parity` is what would settle
its size, and that is an allocation window rather than an analysis.

The negative control on the same contrast: `reagent` at position 0, `fixed`
against `parity`, reads **0 of 43 against 2 of 675, `p` = 1.0**. Reagent does not
move with the mode. Whatever the mode does, it does to uix.

## Why the rig change is still needed

The bead names its discriminator: *a fixed run with the segment order REVERSED
(uix leading), which `p0_run.cjs` does not currently offer.* **That remains the
right instrument, and the reason it is needed has changed rather than gone away.**

The bead's stated reason was that no position arm existed. That reason is
superseded — `parity` supplies uix at both positions, 1,471 windows of it, and
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
position 1 with reagent now in it, the carrier is the slot. Nothing already
committed answers that, and nothing can, because no committed run drives `fixed`
in the other order.

### What it costs, since this page sizes it rather than making it

Measured against `p0_run.cjs` at blob
`1be8e793d070b9b4797503d40e5798b2fb7b325e`. **Three lines**, and no reader,
schema or record change:

| what | where | change |
|---|---|---|
| the mode roster | `p0_run.cjs:1717` | one more name in `ALLOC_SEG_ORDERS` |
| the order itself | `p0_run.cjs:1821`–`:1824` | one branch in `allocSegmentOrder`, returning the reversed plan every round |
| the preflight refusal | `p0_run.cjs:2582`–`:2585` | **nothing** — it already interpolates `ALLOC_SEG_ORDERS.join(' \| ')`, so a new name is refused by name for free |

The record already carries `segOrder` per run and `segments` per round, and both
readers group on the field rather than recomputing a parity rule, so a third
mode is read correctly by everything that exists without being taught about.

**This page does not make that change.** `p0_run.cjs` is shared with `rf2-fk6pj`
and `rf2-onozm`, which want their own arms in the same file; it is one-toucher,
and bundling is the mayor's call rather than this bead's.

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
out of 1,471 windows. It is **rarer** under `parity` by a factor of about twenty,
not absent.

## What is not concluded

- **No mechanism is named.** Nothing here says what the term is, only what it
  travels with. The `fixed` mode drives the same two segments in the same order
  every round where `parity` alternates them; which consequence of that matters
  is untested.
- **The mode contrast is not established at a matched baseline.** `p` = 2.67e-9
  against 112 pooled runs, `p` = 0.1344 against the three that share its session.
  Nothing here chooses between those, and the second is the one that controls
  what the first does not.
- **Three `fixed` runs is three.** 81 windows, one box, one session, one
  revision. Every `fixed` figure on this page rests on them.
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
