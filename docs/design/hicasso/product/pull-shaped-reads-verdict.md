# Pull-shaped reads — the three-arm comparator and its verdict

**Status at the time of this commit: PRE-REGISTERED, NOT YET MEASURED.** Everything below the horizontal rule was written before a single number was taken, and this commit exists so that ordering is a fact in the history rather than a claim in the prose. The verdict section is deliberately empty; it is filled by a later commit on the same branch.

Owned by `rf2-hic-058`. The rule this record discharges is [specification §11](specification.md#11-innovation-portfolio)'s portfolio row:

> | Pull-shaped reads | Spike | Must beat hand-coarse ergonomics/cost without a per-leaf ledger or independent-churn regression |

and its detailed form in [the left-field lane](lanes/left-field-ideas.md#pull-shaped-reads):

> Compare one declarative pull subscription with fine reads and a hand-written coarse view-model on the same form/list witness under correlated and independent churn. A pull is one invalidation unit; it cannot build a per-leaf dependency ledger. Graduate only if it approaches the coarse arm's clock/heap while materially preserving the local declaration ergonomics, with no independent-churn regression that makes the hand-coarse answer plainly better.

The spike runs under the operator ruling of 2026-08-12 17:36 AUSEST (`rf2-xpq9`), which places every Phase-5 decision-shaped item inside v0 scope on these terms: *the spike RUNS and its pre-registered verdict is MADE within v0; adoption follows its own criteria. A ruled STOP still completes the item; completion is the verdict, not forced adoption.* A verdict of *do not adopt* therefore completes this bead.

---

## Pre-registration — written 2026-08-12 20:05 AUSEST, before measurement

### What is being compared

Three arms read the same screen — a small form over one user record, and a list of `R` rows — from the same `app-db`, and must deliver the same value. The screen's **read extent** is the two user fields and the two fields of each row; `:user/locale` and `:noise` sit in `app-db` and outside the extent, and exist so that *independent churn* has somewhere to land.

| Arm | Shape |
|---|---|
| **F — fine reads** | one layer‑1 subscription per leaf, and one layer‑2 consumer per view unit (the form, plus one per row) |
| **C — hand-coarse view-model** | one hand-written layer‑1 subscription assembling the whole screen's value, and one layer‑2 consumer |
| **P — pull** | one layer‑1 subscription whose body interprets a declarative query, and one layer‑2 consumer |

Arms C and P must produce values that are `=`, and arm F's leaves must agree with the corresponding pieces of both. A parity assertion covering **all three** arms runs before any figure is read: two arms agreeing while the third is structurally absent is the failure mode this comparator is most likely to have, and it is asserted against rather than assumed away.

### What is measured, and what is not

Every figure below is a **count**, taken on the CLJS Node lane. Counts are deterministic, carry no hardware profile, and can sit beside the deterministic rows of [budgets.md §3](budgets.md) rather than the distributional rows of §4. That is also their limit, and it is stated here rather than discovered later: **they say what work each arm does, never how long that work takes.**

No wall clock is taken. The bead's acceptance names *clock + heap on pinned runs*, and a duration cannot be attributed on a machine that may be carrying another worker's compile — the standard `rf2-hic-033` set and `rf2-5yn9` inherited. Retention is likewise read as **live subscription-cache entries**, a count, and not as retained bytes: the `D9`/`S5` distinction in [budgets.md §3](budgets.md) governs, and a comparator that conflated them would be claiming a heap result it did not take.

Every figure carries a **control that moves it**. The control is the row count: the witness is built at `R = 4` and again at `R = 16`, the same quadrupling `D1`–`D4` used to give the narrow-update row its meaning. A figure that does not move when it is predicted to move, or moves when it is predicted to stay flat, fails the comparator rather than the arm.

| Figure | What it counts | Predicted to |
|---|---|---|
| `G1` | consumer recomputations after a **correlated** write (one row's text) | stay at 1 for every arm; it is `G2` that separates them |
| `G2` | leaf values re-delivered to the notified consumer after that write | stay flat for F, scale with `R` for C and P |
| `G3` | consumer recomputations after an **independent** write (`:noise`) | be 0 for every arm |
| `G4` | layer‑1 handler recomputations per write | scale with `R` for F, stay flat for C and P |
| `G5` | `app-db` path lookups per write, counted through one shared helper so the unit is identical in all three arms | scale with `R` in every arm |
| `G5b` | query-interpretation steps per write — the work P does that C does not | be zero for F and C, non-zero for P |
| `G6` | live subscription-cache entries retained, read from `re-frame.subs.tooling/sub-cache-snapshot` | scale with `R` for F, stay flat for C and P |
| `G7` | declared subscription ids, read from `re-frame.subs.tooling/sub-topology` | stay flat in `R` for every arm; F's grows with the number of distinct leaf *kinds* |

`G5` and `G5b` are separated deliberately. A single blended "cost" number would let P's interpretation overhead hide inside the lookups it shares with C; split, P's overhead over the hand-written answer is exactly `G5b`.

### The kill condition, and how it is witnessed

The bead states it as a hard stop: *any drift toward a per-leaf dependency ledger stops the spike immediately.* It is witnessed rather than attested:

**`G6` for arm P must be identical at `R = 4` and `R = 16`, and the resolver must retain nothing between invocations.** A per-leaf ledger cannot be built without retaining something per leaf, and anything retained per leaf shows up as retention that grows with `R`. If closing the gap between `G2(P)` and `G2(F)` were to require per-leaf dependency state, the spike stops there and the stop is the result.

### The deciding rule, as it will be applied

**GRADUATE to a feature bead** if and only if all three hold:

- **(a) cost approaches the hand-coarse arm.** `G4(P) = G4(C)`, `G6(P) = G6(C)`, and `(G5 + G5b)(P) ≤ 1.25 × G5(C)` at **both** row counts. The `1.25×` ceiling is not invented here: it is the factor [budgets.md §4](budgets.md) records as the programme's `S6` cold-mount *proposal*, reused as the largest overhead this programme has ever entertained in exchange for an ergonomic win. It is a **proposal only** there and is borrowed as one here — nothing in this record ratifies it.
- **(b) no independent-churn regression.** `G3(P) = G3(C) = G3(F) = 0`.
- **(c) ergonomics approach the fine arm.** `G7(P)` does not grow with `R`, and adding one field to the screen touches strictly fewer source sites in P than in either C or F.

**RECIPE STANDS — do not adopt** if any of (a), (b) or (c) fails, or on the kill condition. Under the ruling above this still completes the bead.

### The discriminator, recorded whichever way the verdict goes

`G2` places the pull arm on one side or the other of the trade this spike exists to test. If `G2(P) = G2(C)` and both are far above `G2(F)`, then a pull is a coarse read wearing a declarative face: the fine/coarse trade has been **relocated into the ergonomics column, not dissolved**. That reading is recorded here regardless of whether (a), (b) and (c) permit graduation, because it is the answer to the question the bead asks — *does it dissolve the fine/coarse ergonomics trade* — and that question has an answer even when the graduation rule has a different one.

---

## The verdict

*To be filled by measurement. Empty at the time of pre-registration, deliberately.*
