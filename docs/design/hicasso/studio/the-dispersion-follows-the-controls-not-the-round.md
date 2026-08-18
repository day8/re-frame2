# The leg dispersion follows the controls, not the round — a re-analysis on the work census

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-rs8q6`, read against `rf2-n1b9h`'s
work census once it landed. **No window was taken, no browser was launched, no
rig file was touched.** Every figure below is computed from datasets already
committed under `implementation/hicasso/test/re_frame/bench/hicasso/data/`,
at landed base `20ec8ba59d`.

The corpora read here, and nothing else:

| Corpus | Runs | Plan | B | Rounds | Census armed | Arm windows |
|---|---|---|---|---|---|---|
| `workcount-n1b9h` | 6 | `floor`, `write-all` | 24 | 18 | yes | 216 |
| `alloc-9jrhi` | 8 | `floor`, `write-all` | 24 | 18 (7 runs), 6 (1 run) | no | 264 |
| `alloc-0gjqi` | 2 | `full`, `write-paired` | 4 | 6 | no | 528 (not floor) |

`τ` was not moved, in either direction, and no gate, band or threshold on this
rig was widened, narrowed or touched.

## The answer, first

**`rf2-rs8q6`'s round index is REFUTED as stated and replaced by a sharper one:
the carrier is the window's POSITION WITHIN ITS ROUND.** Pooled over the 387
collection-free floor-arm windows in the 14 committed floor runs — four
revisions, two corpora, both substrates:

| Position in round | n | median deviation | ≤ 0.19% | ≥ 2.66% |
|---|---|---|---|---|
| 0 — first arm driven | 182 | **3.908%** | 25 (13%) | 153 (84%) |
| 1 — second arm driven | 205 | **0.184%** | 113 (55%) | 92 (44%) |

- **The work count never moves.** Re-derived independently here: across the 216
  arm windows and 1,512 writes of `workcount-n1b9h` the census takes ONE value,
  **7/0/0**, and the 324 control windows take **0/0/0**. It is 7/0/0 in wide
  windows and in tight ones, in both substrates, in all 18 rounds, and in the
  windows carrying the rider below as well as those carrying none.
- **The dispersion is ONE RIDER, not a spread.** Five of every six legs agree
  with their window's median to within ±36 B. What makes a window "wide" is a
  single leg carrying **+748 B** — 57 windows at exactly 748 B, and 748/744/736/
  742/754 B account for 84 of the 112 riders in the 600–900 B band.
- **The rider is in the ARM's path, and this is the sharpest exclusion here.**
  Over **4,284 control legs** — idle, `ctl1` and `ctl2`, which run the same
  window machinery, the same sampler, the same round schedule and the same
  position-in-round — the count of legs in the 700–800 B band is **ZERO**.
  In the arms it is 112 of 2,322 legs (4.82%).
- **The rider is position-locked and substrate-blind.** 101 of its 112
  occurrences are in position-0 windows; it splits 58/54 between the Reagent and
  UIx arms, which is the de-confounding the rig already builds in — the segment
  order alternates with round parity, so each substrate holds position 0 in half
  the rounds.
- **The rider lands LATE in the window.** By leg ordinal (0–5): 4/4/4/27/20/**53**.
- **It is not the prime's term recurring**, and this is a positive discriminator
  where `rf2-ojehu` had only a magnitude mismatch. The prime excess is
  **position-invariant** — median 6,838 B at position 0 against 6,852 B at
  position 1, p25 6,800 and p75 6,864 in both. The rider is position-locked. A
  term that recurs cannot be flat in the very index its recurrence tracks.

## What the census excludes, stated at its real width

`rf2-n1b9h`'s counters are event-handler invocations, subscription
recomputations and render calls. On the FLOOR arm two of those three are
**structural zeros**: the arm mounts no subscription, so nothing recomputes and
React's commit has no update to flush. The census's whole discriminating power
on this arm is therefore the ONE counter that moves, `events`, and it reads
7 — which is `windowWrites`.

So the exclusion is real but narrow, and three limits travel with it:

1. **It excludes a work-COUNT difference, not all work differences.** A
   different branch inside one handler body, or a different allocation inside
   one invocation, is invisible to a counter that ticks once per invocation.
2. **It is a WINDOW SUM over seven legs, not a per-leg reading.** The counters
   are read at the window's open and close. Seven events over seven write legs
   is consistent with one per leg, but a leg doing two while another does none
   sums identically. Nothing here measures a leg's own count.
3. **`subs = 0` and `renders = 0` are not observations of constancy.** They are
   properties of the floor arm's construction, and they generalise to no
   subscribing arm.

## Why the round framing failed

`rf2-rs8q6` read 72 windows pooled over six configurations —
`P0_ALLOC_CELLS ∈ {1,6,24}` (B = 4/24/96) crossed with
`P0_ALLOC_WRITE ∈ {page,all}`, one run each, six rounds. `workcount-n1b9h` is
six REPLICATES of exactly one of those cells (B = 24, `write-all`), extended to
18 rounds. In that cell the bead's two clean statements do not hold:

| Claim | On `rf2-rs8q6`'s corpus | On the 6 replicates of its B=24/all cell |
|---|---|---|
| round 2: NONE below 2.66% | 11 windows, 2.66 – 20.37% | one window at **0.189%** |
| round 3: ALL at or below 0.19% | 11 windows, 0.00 – 0.19% | 4 of 11 at **13.18 – 13.63%** |

But the bead's numbers are not wrong, and the reason is worth recording. The
committed 6-round pilot run — an independent launch at a different revision —
reproduces the bead's two BOUNDARY VALUES almost exactly:

| Round | Pilot run | `rf2-rs8q6`'s pooled band |
|---|---|---|
| 2 | **2.659%** | 2.66% (its floor) |
| 3 | 0.000 – **0.187%** | 0.19% (its ceiling) |
| 4 | 20.241% | includes 20.37% |

Those are not bands. They are two quanta seen head-on: 2.66% of a ~19,280 B leg
is **+512 B** and 0.19% is the sampler's own ±36 B jitter. The bead measured a
rider and a floor, one window deep, and read them as a round-indexed
distribution. `rf2-ojehu` already found the index finer than the round; this is
what the finer index is.

## The confound this cannot separate, named rather than glossed

Every position-0 window is simultaneously

- **the first arm window after the round's three control windows**, and
- **the same substrate as the immediately preceding arm window**, because the
  segment order flips on round parity, so an arm repeats across a round
  boundary and switches within a round.

Checked rather than assumed: over the 466 adjacent window pairs in the 14 floor
runs, "position is 0" and "substrate repeated from the previous window" agree
**466 times out of 466**. They are perfectly confounded by the schedule, and no
committed dataset separates them.

Three warm-up windows at the full measured size, plus a collection, sit
immediately before every measured window at both positions — so whatever the
position-0 window pays, it survives 21 warm writes and a GC.

## What would settle it, and what would not

**Another allocation window would not.** There are already 14 committed runs
across four revisions; a fifteenth replicates a finding replicated fourteen ways
and cannot break a confound built into the schedule.

Two readings would, in this order:

1. **Break the position confound with a rig change, not a window.** Either fix
   the segment order instead of flipping it on parity, or move the three
   controls after the arms. Under a fixed order both positions follow a
   substrate switch, so a rider that stays on position 0 is the controls' and
   one that moves is the switch's. **This edits the rig that the whole published
   series' constancy rests on, and is a deliberate separate act — not a
   measurement window's business.**
2. **Then confirm the term with V8 tracing.** `rf2-n1b9h` names this itself:
   tracing is "the CONFIRMING SECOND move, and only if the counts come back
   identical." **They came back identical.** A once-per-window, ~748 B,
   count-invariant term that appears only after a stretch of foreign work and
   lands three to five legs in is the signature `rf2-77gz8`'s candidate (b)
   predicts — a tier transition in the compiled write path. `--trace-opt` /
   `--trace-deopt` reads it directly.

## What this bead now stands on

The mechanism is **not identified** and this record does not claim it is. What
changed is that it is now bounded on every side: the work count is constant
within the stated limits, the site is `dispatch` (`rf2-ojehu`), the term is
absent from 4,284 control legs, it is one ~748 B rider per window rather than a
spread, it is position-locked and substrate-blind, and it is not the prime's
term recurring. The remaining question is a two-way confound with a named
rig-side discriminator and a named confirming instrument.

**The consequence for τ is better than `rf2-e9wr` left it, and is offered as an
observation, not a calibration.** If the wide mode is one rider in the
first-driven window, then the leg witness's premise — that a window's legs are W
repetitions of one work unit — holds on the second-driven window to ±36 B and
fails on the first by one leg. `rf2-e9wr`'s refusal to pin τ stands; nothing here
licenses moving it, and `rf2-rs8q6`'s standing fence against discharging this by
widening τ is untouched.
