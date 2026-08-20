# The rider follows the position, not the substrate — the confound broken on a rig change

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-rs8q6`, option (a) of the mayor's
2026-08-18 ruling: *break the position/substrate confound and read the result.*

This is a **rig change plus six new runs**. The schedule was altered so that
"first arm window of a round" and "same substrate as the previous window" stop
coinciding, and the ~748 B rider was read against the new schedule. Runs were
taken strictly one at a time, on one box, interleaved between the two arms, at
one revision.

**τ was not moved, in either direction.** No gate, band, threshold or budget
constant on this rig was widened, narrowed or touched.

## The answer, first

**The carrier is the POSITION — the first arm window after the round's three
control windows. Both substrate hypotheses are refuted.**

| Segment order | Collection-free arm windows | Rider windows at position 0 | at position 1 | z |
|---|---|---|---|---|
| `parity` (shipped) | 89 | **25 of 40** | 3 of 49 | 5.70 |
| `fixed` (this bead) | 81 | **21 of 43** | 1 of 38 | 4.67 |

Under `parity`, all 25 position-0 rider windows **repeated** the previous
window's substrate. Under `fixed`, all 21 of them **switched** it. The rider did
not care.

- **The "substrate repeat" carrier is refuted.** Under `fixed` no window repeats
  its predecessor — measured, 0 of 81 adjacent pairs — and the rider is still at
  position 0, in 21 of 43 windows.
- **The "substrate switch" carrier is refuted.** Under `fixed` *both* positions
  follow a switch, and position 1 carries the rider once in 38 windows against
  position 0's 21 in 43.
- **The mode changed nothing about the position effect.** Position-0 rider rate
  `fixed` 21/43 against `parity` 25/40 gives z = −1.25; position-1, 1/38 against
  3/49, z = −0.77. Neither is separated. What the schedule change removed is the
  confound, not the phenomenon.

## The rig change

`P0_ALLOC_SEG_ORDER` in `implementation/core/test/re_frame/bench/p0_run.cjs`,
off by default:

| Mode | Round 0 | Round 1 | Round 2 | Position 0 follows | Position 1 follows |
|---|---|---|---|---|---|
| `parity` | A B | B A | A B | controls **and** a substrate repeat | a substrate switch |
| `fixed` | A B | A B | A B | controls **and** a substrate switch | a substrate switch |

`parity` is the pre-bead expression verbatim, so every published row is taken on
the schedule it always was. A mistyped order is refused by name in the preflight
beside the plan and the write. The row records `segOrder` and each round records
the `segments` order it actually drove — the latter is now load-bearing rather
than tidy, because the parity rule is no longer the only rule a record can have
been taken under, and a reader that recomputed it would mis-position every
window of a `fixed` run.

**A `fixed` row is a diagnostic row, not a publishable one.** It gives up
exactly the property the parity flip was landed for: with one order the
substrate is confounded with the slot. That is why the `parity` arm below was
re-taken rather than borrowed from the committed corpus — see *What `fixed`
alone cannot do*.

## The corpus

| Corpus | Runs | Segment order | Arm windows | Collection-free |
|---|---|---|---|---|
| `segorder-rs8q6/fixed-{1,2,3}` | 3 | `fixed` | 108 | 81 |
| `segorder-rs8q6/parity-{1,2,3}` | 3 | `parity` | 108 | 89 |
| `workcount-n1b9h` + `alloc-9jrhi` (committed) | 14 | `parity` | 480 | 387 |

All six new runs are `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`,
`P0_ALLOC_CELLS=6` (B = 24), `P0_ALLOC_ROUNDS=18` — `workcount-n1b9h`'s
configuration without the work census, which this bead does not re-ask. Windows
carrying an observed collection are excluded on `falls === 0`, the same
restriction the previous record used and independent of τ.

Every run exited **1**, on the same two refusals every floor run in this corpus
carries: collections inside measured windows, and windows past the leg
tolerance. That is the phenomenon under study, not a fault in the run, and the
records are written before the refusal.

## It holds in every run separately

| Run | Position 0 windows | carrying a rider | Position 1 windows | carrying a rider |
|---|---|---|---|---|
| `fixed-1` | 14 | 6 | 13 | 0 |
| `fixed-2` | 15 | 8 | 13 | 1 |
| `fixed-3` | 14 | 7 | 12 | 0 |
| `parity-1` | 11 | 6 | 14 | 1 |
| `parity-2` | 16 | 9 | 18 | 2 |
| `parity-3` | 13 | 10 | 17 | 0 |

Six independent browser launches, and no single run carries the result.

## It is the same term

The rider legs under `fixed` are drawn from the same population as under
`parity` — same magnitudes, same leg ordinals:

| | Rider legs | Modal values | Ordinal 3 |
|---|---|---|---|
| `fixed`, position 0 | 22 | 748 B (×7), 736 B (×6), 754 B (×3) | 15 of 22 |
| `parity`, position 0 | 25 | 748 B (×8), 736 B (×8), 742 B (×2) | 16 of 25 |

And the controls remain the null arm: over **1,932 collection-free control legs**
in the six new runs, the count in the 700–800 B band is **zero**. The prime
excess stays position-invariant in both modes — 6,800 B against 6,864 B under
`fixed`, 6,806 B against 6,812 B under `parity` — which replicates the previous
record's positive discriminator against "the prime's term recurring."

## What `fixed` alone cannot do, and why the parity arm was re-taken

Under `fixed` the segment order never moves, so **position 0 is always
`reagent-subs` and position 1 is always `uix-subs`**. Read on its own, a `fixed`
run cannot tell "the first arm window of a round" from "the Reagent arm."

The `parity` arm is what closes that, and it was re-taken in this session rather
than cited from the committed corpus so that the two arms share a revision, a
box and an interleaved schedule:

| Segment order | Segment | Position | Windows | carrying a rider |
|---|---|---|---|---|
| `parity` | `reagent-subs` | 0 | 20 | 15 |
| `parity` | `uix-subs` | 0 | 20 | 10 |
| `parity` | `reagent-subs` | 1 | 26 | 1 |
| `parity` | `uix-subs` | 1 | 23 | 2 |

Both substrates carry the rider at position 0 and neither carries it at position
1. Substrate identity is excluded by the `parity` arm; the substrate *relation*
is excluded by the `fixed` arm. **Neither arm settles it alone, and that is the
whole reason both were taken.**

## What is left unseparated, stated rather than glossed

Position 0 is the first arm window after the round's three control windows, and
it is also the first arm window after the round-loop boundary. **Those are the
same event**: the loop body opens with `controlOf('idle', 0)` and there is
nothing else at a round boundary. Separating them is the mayor's option (b) —
move the three controls after the arms — which is not done here and is outside
this bead's scope fence.

**The term itself is still not named.** That was fenced out of this dispatch,
and it remains where the previous record left it: a once-per-window, count-
invariant ~748 B term in the `dispatch` site, absent from every control leg, now
known to follow the position rather than the substrate.

## One observation this run turned up and did not chase

Under `fixed`, position 1 (always `uix-subs`) carries a cluster of **8 of 38**
windows whose worst leg sits at 1,050–1,224 B, present in all three `fixed`
runs. The comparable `parity` cells carry 1 of 23 and 0 of 26. It is **not the
rider** — it is outside the band, at a different magnitude, and at a position the
rider does not occupy.

Naming it is outside this bead's fence and n is small. It is filed rather than
chased.

## Reproduction

From the **repository root**, one run at a time:

```bash
# the confound-broken arm
P0_PORT=8471 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 P0_ALLOC_SEG_ORDER=fixed \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/segorder-rs8q6/fixed-1.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# the paired control, same revision, same box, interleaved
P0_PORT=8471 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 P0_ALLOC_SEG_ORDER=parity \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/segorder-rs8q6/parity-1.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

Every measured figure on this page — every window and leg count, byte value,
percentage and `z` — is re-derived from the committed records by:

```bash
node implementation/hicasso/test/re_frame/bench/hicasso/alloc_position_confound.cjs \
  implementation/hicasso/test/re_frame/bench/hicasso/data/segorder-rs8q6/*.json
```

That reader drives the 14 committed `parity` runs to the previous record's
figures exactly — 387 collection-free windows, 182 / 205 by position, 101 + 11
rider legs, 4,284 control legs with zero in the band — which is the control on
the reader itself. Its own fixtures run under `--self-test` and in
`npm run test:script-helpers`.

### The `z` column, and the convention it is taken under

Every `z` on this page is a **two-proportion z-test on the pooled proportion**,
with no continuity correction and no conversion to a tail probability:

```text
p1 = k1/n1     p2 = k2/n2     p = (k1 + k2) / (n1 + n2)
z  = (p1 - p2) / sqrt( p (1 - p) (1/n1 + 1/n2) )
```

**The convention is written down because three of them land within half a z of
each other on these counts.** Worked on the first row's 25 of 40 against 3 of 49,
the formula above gives **5.6975**, which is the published 5.70 and is what the
reader prints; the unpooled form gives 6.7229 and the continuity-corrected form
5.4681. Anyone re-deriving these figures under a different convention will land
near them and not on them, which is why the reader prints every `z` at four
decimals as well as at the published two.

The sign is `(first group − second group)`, the two taken in the reader's own
canonical order: the order its report already lists them in, ascending by
position and sorted by schedule name. So the sign belongs to the report and not
to whichever sentence cites it.

The reader prints each comparison by name beside the two cell counts it was
taken on, so no figure here can drift from the counts behind it. Its fixtures
pin **all ten** z-scores across this page and the control-slot record on those
same counts, as literals, **and require the unpooled and continuity-corrected
forms to miss every one of them** — so the pin discriminates rather than
restates, and a change to the formula reds `npm run test:script-helpers`.

The reader also prints the worst deviation **both ways**, magnitude and signed,
because the unit trap below turns on the pair and this page publishes both.

### What that command does NOT print

Stated so the claim above is exact rather than generous. Three kinds of number
on this page are not figures of the runs and the reader does not emit them:
the **environment of the reproduction block** (`P0_PORT`, `P0_ROOTS`,
`P0_ALLOC_CELLS`, `P0_ALLOC_ROUNDS`) — that is how the runs were taken, not
something read out of them; the **seat and bead identifiers**; and figures
explicitly **cited from another record's corpus**, which are named as such where
they appear. Everything else on the page comes out of the command.

## The unit trap, restated because it nearly published an error

`legWorstDeviation` is a **fraction**, not a percentage — `worst / legMedian` —
and it is **signed**, carrying the deviation furthest from the cohort median in
either direction. Both bite:

- Read as a percent it understates every figure 100×.
- A median over the signed field is not the published statistic. Over the same
  387 committed windows the signed median gives 3.867% / 0.030% by position and
  the **magnitude** gives 3.908% / 0.184%, which are the previous record's two
  figures to the digit.

This page works in absolute bytes wherever it can, for that reason: the rider is
a byte quantity and the sampler's jitter is ±36 B, and neither is a ratio.

## What this bead now stands on

The mechanism is **not identified** and this page does not claim it is. What the
rig change bought is the elimination of the last structural confound that could
be eliminated by scheduling: the +748 B rider is carried by a window's position
in its round — first arm after the controls — and not by any relation to the
substrate driven before it, in either direction, and not by substrate identity.

`rf2-e9wr`'s refusal to pin τ stands, and `rf2-rs8q6`'s fence against
discharging this by widening τ is restated rather than relaxed.
