# Checkpoint 1 — the kernel review record

**Verdict: HELD. Not a pass, and not a fail.** Checkpoint 1 (`rf2-hic-019`) ran on `main`@`27c5d12754` at
2026-08-11 17:28 AUSEST and **could not adjudicate the Phase 1 kernel exit**. Four of its own dependencies were
open when it ran, so three of the eight kernel rows of the
[adversarial risk register](lanes/adversarial-risks.md#phase-1-kernel-risks) have no completed witness set, and the
substrate decision, the byte-exact shell disposition and the two-hook freeze that the protocol's §1 requires have
not been made.

**The exit state is NOT CONFIRMED.** [Specification §12 Phase 1](specification.md#phase-1--make-the-reactive-kernel-trustworthy)
exits "at zero stale reads, cross-frame operations, tears, or residual ownership and only when the shell meets its
frozen line or carries its separate prospective disposition". This record does not establish that, in whole or in
part, and nothing on this page may be quoted as though it did. **No row in [§2](#2-the-eight-kernel-rows-one-by-one)
reads `pass`.**

HELD is a third verdict and it is deliberate. A `fail` would say the kernel was measured and found wrong. A `pass`
would say it was measured and found right. Neither happened: most of it was not measured. Recording that as either
of the other two would be the fail-open shape this programme exists to delete — written into the governance layer,
where it would be believed longest.

## Where each fact lives

| Fact | Owner |
|---|---|
| The eight kernel risks, their required contracts, deciding witnesses and remedies | [`lanes/adversarial-risks.md`](lanes/adversarial-risks.md#phase-1-kernel-risks) |
| What Phase 1 must deliver, and the words its exit is written in | [`specification.md` §12](specification.md#12-action-programme), indexed by [`lanes/delivery-programme.md`](lanes/delivery-programme.md) |
| The review protocol this record discharges — §1 Completeness, §2 Correctness, §3 Quality | `rf2-hic-019` |
| What each miss is, its severity, and what it takes to close it | [`correction-ledger.md`](correction-ledger.md#the-ledger), and the bead each row names |
| Whether the kernel exit is met | this file — and today the answer is *not established* |

One owner per fact. Bare `§1`, `§2` and `§3` throughout this page mean the review protocol's three sections;
specification sections are always written as `specification.md §N`. The five findings are **not** restated here:
their text lives in the ledger row and their full detail in the bead. A third copy is a third thing to drift.

## 1. Why the verdict is HELD, and what would discharge it

### 1.1 The four dependencies that were open

`rf2-hic-019`'s dependency set is `rf2-hic-010` … `rf2-hic-018`, `rf2-hic-029`, and the ledger bead `rf2-hic-073`
— eleven, of which seven had landed. These four had not:

| Open dependency | What it owes | What its absence costs this checkpoint |
|---|---|---|
| `rf2-hic-016` | Kernel: controlled input across Firefox and WebKit | Kernel row 5 (controlled-input portability) has no completed witness set |
| `rf2-hic-017` | Kernel: the mutable-global sweep — root-scope or justify every one | Kernel row 2 (process-global ownership) has no completed witness set |
| `rf2-hic-018` | The substrate adjudication: choose the under-collector, freeze the two-hook ceiling, disposition the shell breach | §1 Completeness cannot check three governance artefacts that do not exist |
| `rf2-hic-029` | Retaining-host callback identity: the experiment and the verdict | Kernel row 7 (callback identity and retirement) has no completed witness set; `rf2-hic-013` carries only its other half |

### 1.2 The three §1 artefacts that have not been made

The protocol's §1 Completeness requires that "the substrate decision + byte-exact shell disposition + two-hook
freeze exist and follow the governance shape". None of the three exists, because `rf2-hic-018` — the bead that
makes all three — was open. §1 is therefore not a partial pass with three items outstanding; it is an unanswered
question, and the specification's Phase 1 exit names the shell disposition as a conjunct of the exit itself.

### 1.3 What discharges the hold

`rf2-hic-019` **stays open**. It is not closed on a partial pass, and this record is not its closure. The hold
discharges when all four beads above land and the checkpoint is **re-dispatched** — to a reviewer who did not write
the fixes — to run all three protocol sections again, including the obligations §2 left outstanding
([§3.2](#32-the-node-lane-does-not-cover-the-dom-half)). The re-run supersedes this record; it does not append to
it. Until then the two checkpoints downstream of it — `rf2-hic-026` and `rf2-hic-064` — read Phase 1's exit as
unestablished.

## 2. The eight kernel rows, one by one

Register order, as published in [`lanes/adversarial-risks.md`](lanes/adversarial-risks.md#phase-1-kernel-risks).
Three verdicts are used and `pass` is not among them:

- **MISS** — a defect was found and is a row in the [correction ledger](correction-ledger.md#the-ledger).
- **HELD — witness set incomplete** — the bead that builds this row's witness is still open. There was nothing to review.
- **HELD — §2 re-run outstanding** — the witness landed and nothing indicts it, but the protocol's §2 obligation
  (re-run the suite from a clean checkout and independently re-run one sabotage control for the family) was not
  discharged. An undischarged obligation is not a pass.

| # | Kernel risk | Witness set | §2 sabotage control | Row verdict |
|---|---|---|---|---|
| 1 | Frame reincarnation and cached operations | complete (`rf2-hic-013`) | not indicted; not re-run here | HELD — §2 re-run outstanding |
| 2 | Process-global ownership | **incomplete** (`rf2-hic-017` open) | **absent in any form** (`rf2-1mmn`) | **MISS** |
| 3 | Speculative render leakage | complete (`rf2-hic-010`, `rf2-hic-014`) | not indicted; not re-run here | HELD — §2 re-run outstanding |
| 4 | Ambient-read extent | complete (`rf2-hic-011`) | not indicted; not re-run here | HELD — §2 re-run outstanding |
| 5 | Controlled-input portability | **incomplete** (`rf2-hic-016` open) | pending its witness | HELD — witness set incomplete |
| 6 | HMR identity | complete (`rf2-hic-015`) | not indicted; not re-run here | HELD — §2 re-run outstanding |
| 7 | Callback identity and retirement | **incomplete** (`rf2-hic-029` open) | pending its witness | HELD — witness set incomplete |
| 8 | Hydration isolation | complete (`rf2-hic-012`) | **absent in any form** (`rf2-1mmn`) | **MISS** |

Two readings of that table would be wrong, and both are easy.

**"Not indicted" is not "verified".** In the sabotage column it means only that `rf2-1mmn`'s audit of the controls
did not name the row. This checkpoint did not inventory those controls and did not execute one deliberately, so the
column records the absence of an accusation, never the presence of evidence.

**"Complete" is a statement about beads, not about coverage.** It says the witness bead for that row has landed. What
that witness actually measures — and, for rows 2 and 8, what no lane measured at all — is [§3](#3-what-was-re-run-and-what-it-measures).

## 3. What was re-run, and what it measures

### 3.1 The two suites that ran

Two suites were re-run against `main`@`27c5d12754`, both green:

- `npx shadow-cljs compile node-test-hicasso && node out/node-test-hicasso.js` — **553 tests, 2508 assertions,
  0 failures, 0 errors**.
- `npm run test:hicasso-invariants` — freeze (1 frozen row); optional-module reachability (motion unreachable from
  the public door); complaint catalogue (67 live, 6 reserved, 1 pending retirement, 1 retired; every live row
  emitted and rowed in Spec 009, every anchor resolving).

Neither is the clean-checkout run of the full kernel suite that §2 Correctness asks for, and neither executes a
nominated sabotage control per risk family. §2 is discharged in neither respect.

### 3.2 The node lane does not cover the DOM half

**This is a measurement fact, not a caveat, and a reader of "553 tests green" must not mistake it for kernel
coverage.**

`:node-test-hicasso`'s `ns-regexp` (`implementation/shadow-cljs.edn:975`) matches `-dom-cljs-test` as well as
`-cljs-test`, so the DOM namespaces are compiled into the node lane and their tests are counted in the 553. But in
that lane **every DOM claim degrades to a stated skip** — `implementation/hicasso/test/re_frame/hicasso/roots_frames_support.cljs:85-91`
and `implementation/hicasso/src/re_frame/hicasso/impl/mount.cljs:447-451`.

The skip is honest. It is a passing `is` carrying its reason rather than a silent absence, which is the right shape
for a lane that cannot do the work. **It is still not a measurement.** A green assertion whose reason is "there is
no DOM here" answers no question about the DOM, and it inflates the test count while doing so.

Kernel rows 2 (process-global ownership) and 8 (hydration isolation) live **entirely** in `:browser-test`. A
clean-checkout `:browser-test` run was **not performed** by this checkpoint and remains §2's outstanding
obligation — which is also why those two rows' missing sabotage controls (`rf2-1mmn`) could not be supplied by
running something else instead.

## 4. The five misses

Filed as real beads by the checkpoint and rowed in the [correction ledger](correction-ledger.md#the-ledger), where
each carries its [severity](correction-ledger.md#severity) and the [closure rule](correction-ledger.md#the-closure-rule)
that governs it. They are listed here by id only; the ledger row and the bead own their text.

| bd id | Protocol section | Severity | Note |
|---|---|---|---|
| `rf2-czlb` | §3 Quality | correctness | — |
| `rf2-u9lk` | §3 Quality | correctness | Native-surface risks are Phase-3-deferred ([register](lanes/adversarial-risks.md#phase-3-native-surface-risks)), so this does not block the kernel exit |
| `rf2-3f11` | §3 Quality | correctness | — |
| `rf2-1mmn` | §2 Correctness | coverage | The row-2/row-8 control gap of [§3.2](#32-the-node-lane-does-not-cover-the-dom-half) |
| `rf2-34a7` | §3 Quality | quality | Severity qualified in the ledger row; see the bead |

Three correctness rows and one coverage row block `rf2-hic-064` until each is closed — and under the ledger's own
rule a row closes only when the protocol section that produced it is re-run against the landed fix, never when the
fix merges. **These five are the misses found in the part of the kernel that could be reviewed.** They are not the
kernel's defect list; §1 and §2 above are why no such list can be written yet.

## 5. Checked, and found sound

A checkpoint's negative findings are worth as much as its positive ones, and nothing else in this tree records
them. Each of these was examined against the failure shape the protocol sends a reviewer hunting, and each held:

- **The microtask-checkpoint instrument** (`checkpoint_support.cljs`). `at-the-checkpoint` (`:102-131`) takes its
  vacuity check *synchronously, before* the checkpoint and fails loudly if the condition already held — the "a row
  that watched nothing" shape, already closed. Its docstring records that the turn count was mistakenly used as the
  vacuity check first, and reddened four rows. `with-macrotask-deferral` (`:137-155`) is a real sabotage: the
  collector is unmodified and unaware, and the docstring argues from react-dom's own by-value `scheduleMicrotask`
  binding that it has no collateral.
- **The hook-budget witness** (`hook_budget_cljs_test.cljs`). Its first row drives a three-hook control and asserts
  the probe answers three *by name and in order*, so every "exactly two" below it is a measurement rather than a
  limit of the instrument. `armed!` (`:102-109`) asserts `probe/install!` rather than branching on it, and says
  why: the budget would be "UNWITNESSED, not satisfied". This is the model `rf2-1mmn` asks rows 2 and 8 to meet.
- **Residue before reset.** `roots_frames_support.cljs:179-196`'s `teardown-census!` states the rule exactly —
  `mount/release!` resets the runtime, so a post-release census reads zeros whether teardown worked or not, "the
  shape of gate that cannot go red". `kernel_commit_owns_dom` (`:74-79`) and `read_extent_dom` (`:298-309`) carry
  the same discipline. Read from the fixture code rather than from the claims, as §2 requires.
- **Exercised-population rosters** exist and are *asserted* where the risk row needs one: `kernel_commit_owns_dom:119`
  (5), `read_extent_dom:170` (4), `reincarnation_paint_dom:100` (3), `testbed/spec.cjs:927` (13, pinned by
  `serve-and-run-hicasso-controlled-testbed.cjs:354`), `testbed/hmr_spec.cjs:625` (8, cross-checked against
  `serve-and-run-hicasso-hmr-testbed.cjs:161`), `native-ime-witness.cjs:519` (8, with missing/extra reporting).
- **Row 4's scenario coverage** is one-for-one with the register's named scenarios, as `deftest` names — nested
  helper, branch, loop, event, render prop, promise, timer, lazy sequence, module
  (`read_extent_cljs_test.cljs:236-560`). It has no declared-population `def`, and that was **deliberately not
  filed**: the `deftest` names already enumerate the register's list, and a roster `def` beside them would be
  process for its own sake.
- **The "declared a gate that runs nowhere" trap is already caught mechanically.** `test.yml:1446` records that
  `test:hicasso-hmr` once ran nowhere; `scripts/check_gate_scheduling.py` now guards it and is always-on
  (`test.yml:662-669`). `test:hicasso-controlled` (`test.yml:1440`) and `test:hicasso-hmr` (`test.yml:1544`) are
  both wired.
- **`impl/frames.cljs`'s incarnation row** — one row per public id carrying incarnation, bundle and dispatch
  closure, so the three cannot describe different incarnations. Correctness is lazy replacement rather than a
  destruction hook, and `forget-frame-ops!` is stated as reset-and-hygiene only. The coupling is structural, not a
  rule somebody has to keep.
- **`impl/roots.cljs`** — the adoption window is per-root, born open, nil-is-closed, with the page-global
  reference-count alternative explicitly refused and its reason recorded.
- **The virtual clock's handover** (`test_kit/mounted.cljs:301-363`). An interval's time left is its next tick
  re-armed as a one-shot that then arms the platform's repeat; the cadence is armed in a `finally` so a throwing
  tick cannot retire the timer, and the throw still escapes. Negative virtual ids (`:225-235`) keep the two
  scheduler id domains provably disjoint. `fire-due!` advances an interval's due *before* the call, so the virtual
  clock and the handed-over real one behave the same way under a throw.

## 6. Considered, and not filed

Recorded so that a later reviewer meeting them knows they were seen and judged, rather than missed:

- `codec.cljs:1695` writes a `component` field onto the host head that nothing in `implementation/hicasso/` reads
  (`host-element` reads gate/callbacks/slots/displayName at `:2781-2787`; under `:ssr :render` the gate *is* the
  component). Dead weight, one line, no author-visible effect.
- `portal.cljs:138-141` claims "a misspelled option is therefore an absent one, and lands on `portal-target`'s
  refusal". True for `:target`, not for `:fallback` — a misspelled `:fall-back` crosses as ordinary data and the
  server render silently emits nothing. Two options is genuinely not a roster; the docstring sentence overreaches
  slightly.

## 7. What this record is not

It is not a partial pass, and it may not be cited as one. It is not the closure of `rf2-hic-019`, which stays open.
It is not evidence about the browser lane, which did not run. It is the honest state of Checkpoint 1 on
`main`@`27c5d12754`: five misses in what could be reviewed, and a kernel exit that has not been adjudicated.

Provenance: `rf2-hic-019` ran under a read-only, no-PR fence and could write neither this page nor the ledger rows
itself, so it carried both verbatim in `rf2-0bu1`, which published them. Every finding, count and verdict above is
the checkpoint's; nothing was re-derived, and the two suite results are quoted rather than re-measured.
