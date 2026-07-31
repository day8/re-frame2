# What does sharing a subscription do to a boundary's retained heap?

Seat: EVIDENCE SPIKE, EP-0038. Bead `rf2-5prok`, adopted as *Codex rec 4* by the
heap-regime ruling (authoritative text on `rf2-2rtt6.16`; transcription on
`rf2-2rtt6.1`).

**Standing.** This sweep is **non-gating** for that ruling, which stands
regardless of what is below. It is **gating** for freezing Part 3's component
budget rows — the shell / per-edge / per-unique-key numbers that may not enter
[validation.md](../validation.md) until a sweep verifies the additive model and
prices the terms.

---

## The prediction, on record, before the run

Written and committed before the published rungs were measured. The ruling put
one prediction on record and this page adds the rest, because a page that only
records the predictions it got right is not recording predictions.

**P1 — the fan-out-1 rung lands on the ladder's distinct-query family** (the
ruling's own falsifiable claim): Reagent ~**1,562 B**, UIx ~**3,807 B** at one
read, ratio 2.25–2.44×. *A material miss reopens Part 1 of the ruling.*

**P1′ — but the ladder's absolutes predate two spine landings, and this sweep
measures the tree after both.** `rf2-2rtt6.13` (PR #7304) removed a retained,
disposed, unreachable reaction worth **769 B / 23.0 objects per UIx read**;
`rf2-2rtt6.25` (PR #7305) landed the hook-scoped provisional hand-off, taking
the cold read from two reaction constructions to one. The dead reaction was
built once per *cache miss*, so it belongs to the per-unique-key term, not the
per-edge one. So the corrected prediction is:

| rung | ladder, as published | predicted here (post-.13/.25) |
|---|---:|---:|
| Reagent, fan-out 1, R=1 | 1,562 B | **~1,562 B** (unchanged — the fix is UIx-side) |
| UIx, fan-out 1, R=1 | 3,807 B | **~3,038 B** (3,807 − 769) |
| UIx, fan-out 4, R=1 | 2,134 B (the `.4` grid) | **~1,942 B** (2,134 − 769/4) |

**P2 — the terms.** Reagent shell ~428 B, per-unique-key ~866 B
(`rf2-2rtt6.12`'s ablation), per-edge ~270 B by difference. UIx shell ~208 B,
per-unique-key ~1,684 B (the 2,453 B ablation less the 769 B the fix removed),
per-edge ~1,146 B.

**P3 — the model may not be three terms.** The reads ladder's own Reagent curve
fits `397 + 943·R` at r² 0.9988 while its measured R=0 shell reads 428 B and its
R=1 rung reads 1,562 B — a first read that costs **224 B more than the line**,
invisible in an r² taken against a 19 KB range. If that step is real and is a
per-*subscribing-boundary* cost rather than a per-edge one, the three-term
budget the ruling named would misprice every page whose fan-out is not 1. The
sweep therefore adjudicates **two** models and holds a rung back to tell them
apart. Predicted: **M4 on Reagent, M3 on UIx.**

**P4 — the instrument's own controls**, predicted before the run:

- the dense-array positive control reads **4,700,000 B** (587,500 unboxed
  doubles × 8) on every round;
- the adjudicator's self-test passes 6 of 6, and its synthetic quadratic page
  clears the r² floor at **≈0.997** and is refused only out of sample;
- **Q, counted off the frame's sub-cache**, reads exactly 300 on the published
  `grid/*` arms at *every* ROOTS setting. That is the ruling's central
  mechanical claim — four roots rendering one frame's query vectors hold 300
  reactions, not 1,200 — and this sweep turns it from a reading of the source
  into a number the instrument refuses to proceed without.

---

*(Results follow once the published rungs are measured.)*
