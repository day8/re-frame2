# The floor is two populations — the non-cancellation floor, re-derived over the whole committed null-arm corpus

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-0eu1s`.

> **THE CORPUS HAS GROWN SINCE THIS PAGE WAS WRITTEN, AND THREE FIGURES ON IT
> HAVE MOVED.** Every figure below is the record of a re-derivation over 242
> cells, 3 windows and 3 sessions, and is left exactly as it was published.
> `rf2-fk6pj`'s phase-4 window has since added 8 runs and 327 cells, taking the
> corpus to **569 cells over 4 windows and 5 sessions**. What moved: the empty
> span now holds **one** cell of 569; the over-bar fraction **stopped rising**,
> reading 17.4% against phase 3's 23.2%; and the session question is partly
> answered, because phase 4 is **one design held still across two sessions** and
> its over-bar fraction reads 17.7% against 17.2% — **the second population is
> not session-carried on that evidence**. What did not move: the pooled median,
> mode 1's absolute median of 1.5 B/boundary, and rounds 0 and 1 carrying no
> mode-2 cell at all — now over 85 cells rather than 43. **No verdict on this
> page is withdrawn and the 1.5 / 4.5 / 45 triple is untouched; the ruling this
> bead awaits is unaffected.** `alloc_null_floor.cjs` reads the larger corpus and
> its self-test pins the new figures. See
> [the band on the aggregate, and the second session](the-band-on-the-aggregate-and-the-second-session.md).

**No window was taken for this page.** It is a re-analysis over run records that
already existed, by a reader that launches no browser, builds no bundle and
writes nothing. Every figure below is printed by

```
node hicasso/test/re_frame/bench/hicasso/alloc_null_floor.cjs
node hicasso/test/re_frame/bench/hicasso/alloc_null_floor.cjs --tables
```

from `implementation/`, and **every table on this page is that second command's
output pasted unedited** — the tables are generated, not transcribed. The reader
is registered in `test:script-helpers`, where its `--self-test` pins each
published figure it reproduces.

## The question

The R = 0 arm reads nothing, so `arm − floor` must be zero under either write. It
is the only allocation population whose true value is known in advance, and
`rf2-2rtt6.140` published it as the instrument's **non-cancellation floor**: a
median of 1.5 B/boundary, a 90th percentile of 4.5, and a **refusal bar of 45
B/boundary** at ten times that p90.

`rf2-0eu1s` observed that the fraction of that zero-signal population sitting
above the bar had risen across three windows — 5.3%, then 10.0%, then 23.2% —
and asked for a re-derivation over the whole committed corpus rather than one
window's 38 cells: the quantile ladder per window and per session, and a
statement on whether the published triple should be re-cut or whether the tail is
session-carried.

## The answer, first

**There is no tail.** The null-arm Δ distribution is not one population with a
heavy upper end. It is **two disjoint populations separated by an empty gap**,
and the published p90 does not report a magnitude at all — it reports which of
the two the 90th-percentile index happened to land in.

That single fact reorganises everything the bead observed:

1. **`4.5` and `61` are not two points on a continuum that rose.** They are the
   two modes. The pooled p90 crossed from the first to the second when the
   over-bar fraction passed roughly one in ten, and there is nothing in between
   for it to have passed through on the way.
2. **The bar at 45 B/boundary is correct and must not move.** It was derived by
   an arithmetic that turned out to be unstable, but the number it landed on sits
   **half a byte above** a 23.5 B/boundary span that holds **zero of 242 cells**.
   That makes its value robust, which is not the same as separating the two
   populations exactly: it differs from the population partition on the single
   cell at 44.5, which it counts below the bar. Section A proves both halves.
3. **What cannot be repaired by re-cutting is the p90 itself**, because a pooled
   percentile is not a magnitude on a two-population mixture. That is a ruling
   and not this page's call — the triple is cited by other windows' bands.
4. **Whether the movement is session-carried is not decidable from this corpus.**
   Three windows are three sessions, one each, and the session is confounded with
   the design, the round count and the calendar date in all three.

## The corpus, and that it is the whole of it

The reader discovers the corpus rather than assuming it: it walks every committed
run record under `implementation/hicasso/test/re_frame/bench/hicasso/data/` and
keeps the ones that yield a null-arm cell. Eight runs do. **There are no others**,
and the self-test insists on that in both directions.

| window | design | run | rounds | session recorded | control | B/double | unverified | cells |
|---|---|---|---|---|---|---|---|---|
| rf2-0gjqi | parity, 2 x 6 rounds | run 1 | 6 | no (predates the field) | OK | 8.08 | 0 | 18 |
| rf2-0gjqi | parity, 2 x 6 rounds | run 2 | 6 | no (predates the field) | OK | 8.08 | 0 | 20 |
| phase 2 | seeded, 2 x 6 rounds | run 1 | 6 | yes | OK | 8.08 | 0 | 20 |
| phase 2 | seeded, 2 x 6 rounds | run 2 | 6 | yes | OK | 8.08 | 0 | 20 |
| phase 3 | seeded, 4 x 12 rounds | run 1 | 12 | yes | OK | 8.08 | 0 | 37 |
| phase 3 | seeded, 4 x 12 rounds | run 2 | 12 | yes | OK | 8.08 | 0 | 42 |
| phase 3 | seeded, 4 x 12 rounds | run 3 | 12 | yes | OK | 8.08 | 0 | 42 |
| phase 3 | seeded, 4 x 12 rounds | run 4 | 12 | yes | OK | 8.08 | 0 | 43 |

242 cells over 8 runs, 3 windows and 3 sessions. Every run's positive control
passed at 8.08 B/double with zero unverified read-backs, so no run is excluded
for behaving badly and the population below is the whole committed one.

**The exclusions are by construction, which the self-test checks rather than
assumes.** A null-arm cell needs the R = 0 ladder arms *and* two write legs,
because `Δ = d_page − d_all` is a difference. Every excluded record fails one of
those two and the reader names which: the floor-plan runs record the `grid/floor`
arm alone and have no R = 0 arm at all, and one older run — `alloc-2rtt6-138` —
*does* carry R = 0 arms but writes them under a single leg, from before the
paired write existed. **The R = 0 arm is therefore older than the Δ statistic
published over it**, and that run is not evidence about this floor either way.

That check is a positive control on the discovery, not a formality: without it, a
parse that silently returned nothing for every file would satisfy "discovery
finds exactly the pinned corpus" by returning nothing for the pinned files too,
and the empty result would read as a clean corpus rather than as a broken reader.

### Window and session are the same partition here

The bead asks for the ladder per window **and** per session. In this corpus each
window is exactly one session, so the two tables would be the same table. That is
not a convenience worth economising on — **it is the reason nothing below can
attribute the movement to the session** rather than to the design, the round count
or the date. It is also why the third of the bead's three permitted answers is
partly the one this page returns.

One detail belongs here rather than in a footnote: `rf2-0gjqi` predates the
`box.session` block entirely, so its session is *inferred* from the window rather
than read from the record. The reader marks it as inferred and does not invent an
identifier for it.

## A. The shape, which governs every ladder below

Sorted, the 242 committed cells run from 0 to 21, then **nothing at all**, then
44.5 to 135.5.

| population | span, B/boundary | cells |
|---|---|---|
| mode 1 | 0 to 21 | 197 |
| **the gap** | **(21, 44.5)** | **0** |
| mode 2 | 44.5 to 135.5 | 45 |

Both edges are observed rather than chosen: 21 is the largest cell below the gap
and 44.5 the smallest above it, over all 242.

**The published bar at 45 sits half a byte above that span.** Any bar in
(21, 44.5] classifies all 242 cells identically; the published one differs from
that partition on exactly one cell of 242 — the one at 44.5, which it counts
below the bar. So the bar's *value* is robust however its derivation behaved, and
so is the small difference between the two counts used below: 44 cells lie over
the 45 B bar, 45 cells lie in mode 2, and the difference is that single cell and
nothing else. **Every cross-window comparison on this page is stated over the
bar**, the basis the published figures use; mode-2 membership is used only for
the shape and for mode 1's own ladder.

## B. The ladder, per run

`step` is the lowest ladder rung whose value is already in mode 2 — a rank, not a
magnitude, and the statistic that makes the rest of this page legible.

| window | run | n | median Δ | abs median | p50 | p75 | p80 | p85 | p90 | p95 | max | step | over the 45 B bar |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rf2-0gjqi | run 1 | 18 | 0 | 1.5 | 1.5 | 3 | 3 | 3 | 3 | 96.5 | 96.5 | **p95** | 1/18 (5.6%) |
| rf2-0gjqi | run 2 | 20 | 0 | 1.5 | 1.5 | 3 | 4.5 | 4.5 | 5 | 56.5 | 56.5 | **p95** | 1/20 (5.0%) |
| phase 2 | run 1 | 20 | 0 | 2.5 | 3 | 3 | 3 | 4.5 | 59.5 | 62.5 | 62.5 | **p90** | 2/20 (10.0%) |
| phase 2 | run 2 | 20 | -1 | 3 | 3 | 6 | 7.5 | 9 | 56.5 | 62.5 | 62.5 | **p90** | 2/20 (10.0%) |
| phase 3 | run 1 | 37 | 0 | 3 | 3 | 9 | 10.5 | 59.5 | 59.5 | 62.5 | 62.5 | **p85** | 6/37 (16.2%) |
| phase 3 | run 2 | 42 | 0 | 8.25 | 9 | 56.5 | 59.5 | 62.5 | 64 | 68.5 | 76 | **p75** | 12/42 (28.6%) |
| phase 3 | run 3 | 42 | 0 | 2.25 | 3 | 10.5 | 44.5 | 49 | 53.5 | 59.5 | 61 | **p80** | 8/42 (19.0%) |
| phase 3 | run 4 | 43 | 0 | 3 | 3 | 58 | 59.5 | 61 | 62.5 | 64 | 135.5 | **p75** | 12/43 (27.9%) |

**`rf2-0gjqi`'s own runs already contained mode-2 cells** — a 96.5 in run 1 and a
56.5 in run 2 — so the second population was present in the corpus the floor was
published from. What was not present was enough of it to reach the p90 index.

## C. The ladder, per window, which is also per session

| window | n | median Δ | abs median | p50 | p75 | p80 | p85 | p90 | p95 | max | step | over the 45 B bar |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rf2-0gjqi | 38 | 0 | 1.5 | 1.5 | 3 | 3 | 3 | 4.5 | 56.5 | 96.5 | **p95** | 2/38 (5.3%) |
| phase 2 | 40 | 0 | 3 | 3 | 4.5 | 6 | 7.5 | 56.5 | 62.5 | 62.5 | **p90** | 4/40 (10.0%) |
| phase 3 | 164 | 0 | 3 | 3 | 19.5 | 54.5 | 59.5 | 61 | 64 | 135.5 | **p80** | 38/164 (23.2%) |
| **pooled** | 242 | 0 | 3 | 3 | 9 | 13.5 | 56.5 | 59.5 | 62.5 | 135.5 | **p85** | 44/242 (18.2%) |

Every published figure reproduces exactly: `rf2-0gjqi`'s 38 cells at median 0,
absolute median 1.5, p90 4.5 and max 96.5 — which *is* the published floor — and
phase 3's full ladder of p50 3, p75 19.5, p80 54.5, p85 59.5, p90 61, p95 64. The
self-test pins all of them, along with phase 3's per-run cell counts and p90s, so
the reader cannot drift off the record it is re-analysing.

**No window has an intermediate rung.** Every ladder in the table steps straight
from a mode-1 value to a mode-2 one, because there is no intermediate population
for a rung to sit in. The step moves p95 → p90 → p80 across the three windows,
and where it falls is `1 − the over-bar fraction` and nothing else.

This is what refutes both explanations offered so far, and it does not side with
either. Phase 2 wrote that its p90 jump from 4.5 to 56.5 was *an artefact of the
count*; phase 3 replied that at n = 164 a quarter of the population above the bar
is *a distribution, not an index*. **Both are describing the same step from
opposite sides.** The index really did move — that is what a step function does
— and the fraction really did rise. What neither noticed is that the quantity
they were arguing about cannot take an intermediate value, so its movement was
never going to be informative about magnitude at all.

## D. What is comparable across windows: the over-bar fraction

| window | over the 45 B bar | mode-1 n | mode-1 median | mode-1 p90 | mode-1 p95 | mode-1 max |
|---|---|---|---|---|---|---|
| rf2-0gjqi | 2/38 (5.3%) | 36 | 1.5 | 3 | 4.5 | 5 |
| phase 2 | 4/40 (10.0%) | 36 | 3 | 6 | 7.5 | 9 |
| phase 3 | 38/164 (23.2%) | 125 | 1.5 | 9 | 13.5 | 21 |
| **pooled** | 44/242 (18.2%) | 197 | 1.5 | 7.5 | 10.5 | 21 |

**The centre held and the occupancy moved.** Mode 1's median is 1.5 B/boundary in
the first window and 1.5 in the last, against a published 1.5. Its dispersion did
rise — p90 from 3 to 9, p95 from 4.5 to 13.5 — and that rise is real, but it is a
twentieth of the movement the pooled p90 reports, and mode 1's largest cell in the
whole corpus is 21 B/boundary against a bar of 45.

So the bead's "what still holds" survives this re-derivation intact, and is if
anything stronger than it claimed: the instrument still cancels in the centre, and
the mid-rung figures — thousands of bytes per boundary — are read far above either
mode.

## E. The round-index structure — the one internally controlled comparison here

Window, session, design and date are confounded. **Round index is not**, because
every run contributes both early and late rounds, so this comparison is made
within runs and is immune to all four.

| round | cells | in mode 2 | share |
|---|---|---|---|
| 0 | 12 | 0 | 0.0% |
| 1 | 31 | 0 | 0.0% |
| 2 | 30 | 2 | 6.7% |
| 3 | 29 | 7 | 24.1% |
| 4 | 29 | 9 | 31.0% |
| 5 | 27 | 4 | 14.8% |
| 6 | 15 | 4 | 26.7% |
| 7 | 13 | 4 | 30.8% |
| 8 | 16 | 7 | 43.8% |
| 9 | 10 | 0 | 0.0% |
| 10 | 14 | 4 | 28.6% |
| 11 | 16 | 4 | 25.0% |

Rounds 0–2 carry 2 mode-2 cells of 73; rounds 3 and later carry 43 of 169.
**Rounds 0 and 1 carry none at all**, over 43 cells.

That is not the certification gate doing the filtering. Rounds 1 and 2 are a
near-complete sample — 61 of a possible 64 cells survive — and carry 2 between
them. Round 0 *is* heavily decimated, 12 of a possible 32, and is reported on its
own line for exactly that reason rather than being folded in silently.

**No mechanism is proposed and none is excluded.** The clean prefix is three
rounds long and `warmups` is 3 in every run in the corpus. This page has not
tested whether those are the same three, and it does not assert that they are.

## F. The common-support control

Phase 3 ran twelve rounds where the earlier windows ran six, so section E is a
live candidate explanation for the window ordering: a longer run spends more of
itself in the region where mode-2 cells occur. Restricting every window to rounds
0–5, which all eight runs have, removes that.

| window | over the bar, all rounds | over the bar, rounds 0-5 only |
|---|---|---|
| rf2-0gjqi | 2/38 (5.3%) | 2/38 (5.3%) |
| phase 2 | 4/40 (10.0%) | 4/40 (10.0%) |
| phase 3 | 38/164 (23.2%) | 15/80 (18.8%) |

**The ordering survives.** The control has bite — it does move phase 3's figure,
from 23.2% to 18.8% — and 18.8% is still nearly four times `rf2-0gjqi`'s. So the
longer runs are part of the story and are not the whole of it, and what remains is
confounded four ways at once. This corpus cannot apportion it.

## G. What a band should rest on, which is the live consumer

`rf2-fk6pj`'s phase-3 window built its band at ten times its own null-arm p90, got
610 B/boundary, and refused a term whose implied delta was 37.8. Its successor is
owed a band on the aggregate.

**A band must not be built at ten times the pooled null-arm p90.** That rule is
bistable by construction: it returns 45 B/boundary on the window the published bar
came from, 610 on phase 3, and 595 over the whole corpus, with nothing in between
available for it to return, because the statistic it multiplies has nothing in
between to take. A band built that way records which mode its own null arm landed
in, and no more — a factor of thirteen between two windows of the same instrument
whose centres agree to within 1.5 B/boundary.

**The figure a band should rest on is mode 1's dispersion**, which is the
population that actually represents the instrument failing to cancel by a little.
Ten times mode 1's p90 is **75 B/boundary** over the whole corpus and 90 on phase
3's own null arm. Mode-2 occupancy is then declared separately, as a condition on
the run rather than as a term in the band's width.

**`rf2-fk6pj`'s refusal stands either way, and this page is not an argument for
reading past it.** 37.8 is below 75 and below 90 as well as below 610. The
correction changes the band's *scale*, by a factor of eight; it does not change
that window's verdict.

## The verdict, in the three parts the bead asks for

**The median, 1.5 — HOLDS.** Mode 1's median is 1.5 B/boundary in the first
window and 1.5 in the last. It is the most stable figure in the published triple
and nothing here disturbs it.

**The bar, 45 — DOES NOT MOVE.** It sits half a byte above a 23.5 B/boundary
span holding zero of 242 cells, which is what makes its value robust however its
derivation behaved. **Robust is not the same as exact**, and the difference is
stated here rather than smoothed over: the bar's partition differs from the
population partition on one cell of 242, the one at 44.5 that it counts below
the bar (A). Its derivation was unsound; its value is right. Those are different
claims and only the second is being made.

**The p90, 4.5 — CANNOT BE REPAIRED BY RE-CUTTING IT.** A pooled percentile is
not a magnitude on a two-population mixture, so no re-cut value would be stable
across the next window either. It should be retired as a published statistic and
replaced by two that are: mode 1's own dispersion (p90 7.5 over the corpus) and
the fraction over the bar. **This is a ruling and not this page's call** — the
triple is cited by other windows' bands, and one of them is being chosen now.

**Is the tail session-carried? NOT DECIDABLE HERE.** Three windows are three
sessions, and the session is confounded with the design, the round count and the
date in all three. Two things do narrow it: the round count is ruled out as the
whole explanation (F), and a within-run component is established (E).

**What is missing, stated so a dispatch can act on it: two sessions on ONE
design.** Every window in this corpus changed the design and the session together,
which is why 242 cells cannot separate them and more cells of the same kind would
not either. Re-running phase 3's twelve-round design unchanged in a fresh session
is the cheapest measurement that would.

## What this page establishes, and what it does not

**ESTABLISHED.**

1. The committed null-arm corpus is 242 cells over 8 runs, 3 windows and 3
   sessions, and there is no more of it. Every published figure derived from it
   reproduces exactly.
2. The distribution is two disjoint populations separated by an empty 23.5
   B/boundary span, not one population with a tail.
3. The pooled p90 is therefore a mode selector rather than a magnitude, and every
   window's quantile ladder is a step function with exactly one step.
4. The published bar's value is robust — every bar in (21, 44.5] classifies all
   242 cells alike, and 45 differs from the population partition on one cell
   only; the rule that produced it is not robust.
5. The over-bar fraction rises 5.3% → 10.0% → 23.2%, and survives restriction to
   the rounds every run has at 5.3% → 10.0% → 18.8%.
6. Mode-2 cells are absent from rounds 0 and 1 across the whole corpus and rare
   through round 2, on a near-complete sample. This is within-run and so is not
   confounded with window, session, design or date.

**NOT ESTABLISHED, so it is not read as more than it is.**

1. **NO MECHANISM IS PROPOSED AND NONE IS EXCLUDED.** Not for the second
   population, and not for the round structure in E.
2. **THE THREE WINDOWS ARE THREE SESSIONS**, not 242 independent observations,
   and this page's per-session table is its per-window table for that reason.
3. **THE PUBLISHED BAR IS NOT SHOWN TO BE WRONG.** It refuses nothing this page
   publishes. What is shown is that the distribution it was derived from is not
   the distribution the instrument produces now, and that the derivation would
   not reproduce it today.
4. **NOTHING HERE IS A GATE PROBLEM.** No tolerance is widened and none should
   be. All eight runs' positive controls passed with zero unverified read-backs.
5. **THE MODE-1 RISE IS SEEN, NOT EXPLAINED.** Mode 1's p90 treble from 3 to 9
   across the corpus. It is small beside the bar and it is not nothing.
