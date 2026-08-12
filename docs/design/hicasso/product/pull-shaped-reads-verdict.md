# Pull-shaped reads — the three-arm comparator and its verdict

**Verdict: DO NOT ADOPT. The recipe stands.** A pull is a coarse read wearing a declarative face: it lands on the coarse side of the fine/coarse trade by construction, and the only mechanism that would move it to the fine side is the per-leaf ledger the spike is forbidden to build.

The pre-registration below was written and committed before a single number was taken — `dd41c7ad6c` on this branch, off `origin/main` at `3deaf2890a`, with this line reading *PRE-REGISTERED, NOT YET MEASURED* and the verdict section empty. That ordering is a fact in the history rather than a claim in the prose.

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

## The measurement

The comparator is `implementation/hicasso/test/re_frame/hicasso/pull_reads_spike_cljs_test.cljs`, built for this question and for nothing else. It runs on the package's own Node lane — `shadow-cljs :node-test-hicasso`, and again under the always-on `:node-test` build, both of which already select `re-frame.hicasso.*-cljs-test`, so nothing was added to `shadow-cljs.edn` to make it run.

A fourth reading appears beside the three arms and is not a fourth arm. **`pull-compiled`** is the same query lowered once at registration into a closure rather than interpreted per read. It is there so that a verdict against the pull arm cannot be answered with *you measured an interpreter*; it is excluded from every three-arm row and reported in its own column.

### All three arms ran

Asserted before any figure is read, and the precondition for all of them: each arm produced a value, the coarse and pull values are `=`, the fine arm's five consumers agree with both leaf for leaf, and each arm's own instrument moved — a positive layer‑1 recompute count, a positive lookup count and a positive consumer count, per arm. The parity row is then driven the other way in `the-parity-row-answers-both-ways`, where a query missing one field must *not* agree, so the row is a discriminator rather than a helper that only knows one verb.

### The figures

`R` is the row count. Every cell is a captured reading, not a prediction.

| | fine | coarse | pull | pull-compiled |
|---|---|---|---|---|
| **G1** consumers re-run, correlated write | 1 | 1 | 1 | 1 |
| **G2** leaves re-delivered, `R=4` | **2** | **10** | **10** | **10** |
| **G2** leaves re-delivered, `R=16` | **2** | **34** | **34** | **34** |
| **G3** consumers re-run, independent write | 0 | 0 | 0 | — |
| **G4** layer‑1 recomputes per write, `R=4` / `R=16` | 10 / 34 | 1 / 1 | 1 / 1 | 1 / 1 |
| **G5** `app-db` lookups per write, `R=4` / `R=16` | 28 / 100 | 12 / 36 | 12 / 36 | 12 / 36 |
| **G5b** interpretation steps per write, `R=4` / `R=16` | 0 / 0 | 0 / 0 | **12 / 36** | **0 / 0** |
| **G6** live sub-cache entries, `R=4` / `R=16` | 15 / 51 | 2 / 2 | 2 / 2 | 2 / 2 |
| **G7** declared subscription ids | 6 | 2 | 2 | 2 |

`(G5 + G5b) / G5(coarse)` is **2.00** for the pull arm at both row counts, and **1.00** for the compiled one.

### Every figure's control

The control is the row count, and the comparator reads every figure at `R = 4` and again at `R = 16` — the same quadrupling `D1`–`D4` used to give the narrow-update row its meaning. It moves what it must and leaves flat what it must, and both halves are asserted:

- **Scales as predicted**: `G2` for coarse and pull (`10 → 34`), `G4` for fine (`10 → 34`), `G5` in every arm, `G5b` for pull (`12 → 36`), `G6` for fine (`15 → 51`).
- **Flat as predicted**: `G2` for fine (`2 → 2`), `G4` for coarse and pull (`1 → 1`), `G6` for coarse, pull and pull-compiled (`2 → 2`), `G7` in every arm.

The fine arm's `G6` is the positive control for the kill condition: a structure that holds something per leaf grows with `R`, and this one is shown growing, so the instrument that reports the pull arm flat is demonstrably able to report otherwise.

The whole correlated-churn reading is then taken again under the **UIx adapter** rather than `plain-atom`, and every figure is identical. That is the bead's *adapter-portable* clause measured rather than asserted.

### Reading against the pre-registered rule

**The kill condition was not triggered.** `G6` for the pull arm is `2` at both populations, the resolver is a pure function that retains nothing between calls, and the compiled variant retains one closure per registered query — a constant, not a slot per leaf. Nothing in the spike drifted toward a per-leaf dependency ledger, and the spike therefore ran to its verdict rather than stopping at one.

**(a) cost approaches the hand-coarse arm — FAILS as measured, and the failure is narrow.** `G4(P) = G4(C) = 1` and `G6(P) = G6(C) = 2`, so the two arms are the same recompute class and the same retention class. `G5(P) = G5(C)` exactly: a query naming the same leaves walks the same paths, which is the strongest thing this spike found in the idea's favour. But `(G5 + G5b)(P)` is `2.00 × G5(C)` at both populations, against the pre-registered `1.25×` ceiling. Compiling the query removes that overhead entirely and reaches `1.00`.

One honesty note on the ratio, because the verdict must not rest on it: `2.00` treats an interpretation step and a lookup as one unit each, and nothing here prices them against each other — that is what a clock would do, and no clock was taken. Under the most generous possible assumption, that a step is free, the pull arm merely **ties** the hand-written answer; it never beats it. The verdict does not turn on the unit-equivalence assumption, because (c) fails independently and the discriminator below is untouched by it.

**(b) no independent-churn regression — PASSES.** A write to `:noise` re-runs no consumer in any arm: `G3` is `0`, `0`, `0`. Every arm still pays to discover that — the fine arm re-runs ten layer‑1 handlers and walks 28 lookups to conclude nothing changed, where the other two run one handler and walk 12 — which is worth recording, because the cost of independent churn is not zero anywhere and the fine arm carries the most of it.

**(c) ergonomics approach the fine arm — FAILS.** `G7(P)` is `2` and does not grow with `R`, which the fine arm's `6` does not match: a fine arm needs one registered id per distinct leaf kind and the other two need two apiece however many fields the screen carries. But the second clause asked for *strictly fewer source sites than either C or F* when one field is added to the screen, and the pull arm **ties** the hand-written one: adding `:locale` is one keyword in the query for P and one line in the view-model for C, against two forms for F. `pull-compiled` ties in exactly the same way, so compiling does not rescue this clause either.

### The discriminator, which is the answer to the bead's actual question

`G2(pull)` equals `G2(coarse)` exactly, at both populations — `10` and `34` — while `G2(fine)` is flat at `2`. One row's text changes, and the fine arm re-delivers that row; the pull arm re-delivers the whole screen. The compiled variant re-delivers the whole screen too, so this is a property of **one invalidation unit**, not of interpretation.

That settles the bead's goal sentence. **A pull does not dissolve the fine/coarse trade; it relocates the trade into the ergonomics column.** And the reason is structural rather than incidental: a pull is one invalidation unit by definition, so its invalidation granularity *is* the coarse arm's, and the only mechanism that could give it the fine arm's granularity is a per-leaf dependency ledger — which is precisely the thing this spike is forbidden to build. The deciding rule and the kill condition are therefore in tension by construction, and no amount of implementation effort resolves it.

## What this record does not claim

- **No clock.** `G1`–`G7` say what work each arm does and never how long it takes. No wall-clock figure is taken, and none is owed: a duration is spent pricing something one intends to adopt, and nothing here is adopted.
- **No retained bytes.** `G6` counts live subscription-cache entries. The `D9`/`S5` distinction governs: a count of zero residue and a reading of zero retained bytes are not interchangeable evidence, and only the count was taken.
- **No commit half.** The package's lane is Node, where `react-dom/server` runs bodies and never commits, so no arm mounts a boundary and *how many React bodies re-ran* has no witness here — **for all three arms equally**. It is a declared exclusion. It does not weaken the comparison, because the invalidation unit a boundary re-runs on is the subscription consumer that `G1` and `G2` count, one level below React.

## What would change the verdict

One thing, and it is not a number this comparator could have taken. The pull arm's genuine advantage over the hand-written one is **locality** — the query sits beside the reader, where the view-model is a separate registration somewhere else — and locality is a quality rather than a quantity. The pre-registration deliberately chose a countable test for ergonomics, and on that test the two arms tie. A verdict for adoption would need an authoring result: a measured ergonomics witness on a real screen, showing that colocating the query changes what an author does rather than only how it reads. That is a different instrument and a different bead, and nothing here forecloses it.

What would *not* change the verdict is more implementation. Compiling the query was measured, reaches the cost ceiling, and moves neither the ergonomics tie nor the discriminator.

## Provenance

Written 2026-08-12 for `rf2-hic-058`, under the operator ruling `rf2-xpq9` of the same day.

**Figures, and their tree.** `G1`–`G7` are new readings taken by the comparator named above and pinned by it; they are **bench readings of this spike**, not package budget rows, and no row is added to [budgets.md](budgets.md). The `1.25×` ceiling in the pre-registration is borrowed from `budgets.md` §4's `S6` cold-mount *proposal*, which is a proposal there and is borrowed as one here — nothing in this record ratifies it.

**Pre-registration.** Commit `dd41c7ad6c` on `worker/pull-hic058`, branched from `origin/main` at `3deaf2890a`.
