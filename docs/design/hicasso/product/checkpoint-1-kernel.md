# Checkpoint 1 — the kernel review record

**Verdict: the correctness half of the Phase 1 exit is CONFIRMED; the exit as a whole is NOT MET.**
Checkpoint 1 (`rf2-hic-019`) was re-dispatched to a reviewer who wrote none of the fixes and ran on
`main`@`d079143b91` on 2026-08-13. All eleven of its dependencies had landed. This record **supersedes**
the HELD record of 2026-08-11 (`main`@`27c5d12754`), which said so of itself.

Two findings, and they are separate:

1. **"Zero stale reads, cross-frame operations, tears, or residual ownership" is confirmed** on the
   evidence re-run here. All eight kernel rows of the
   [adversarial risk register](lanes/adversarial-risks.md#phase-1-kernel-risks) map to landed witnesses;
   seven of the eight now carry an **executing** sabotage control, and the eighth's — row 5's — was
   re-run by hand by this checkpoint and reddened. Nothing in the four suites indicts the kernel.
2. **The exit is a conjunction, and its second conjunct is open.**
   [Specification §12 Phase 1](specification.md#phase-1--make-the-reactive-kernel-trustworthy) exits
   "at zero stale reads, cross-frame operations, tears, or residual ownership **and only when the shell
   meets its frozen line or carries its separate prospective disposition**". The shell is a plain red
   and no disposition has been made; worse, nothing open owns the making of one (`rf2-zk87`).

**No row in [§2](#2-the-eight-kernel-rows-one-by-one) reads `pass` unqualified**, and Phase 1 may not be
declared exited on this record. What changed since 2026-08-11 is that the kernel's correctness can now
be spoken about at all: the previous checkpoint could not adjudicate it, and this one can.

## Where each fact lives

| Fact | Owner |
|---|---|
| The eight kernel risks, their required contracts, deciding witnesses and remedies | [`lanes/adversarial-risks.md`](lanes/adversarial-risks.md#phase-1-kernel-risks) |
| What Phase 1 must deliver, and the words its exit is written in | [`specification.md` §12](specification.md#12-action-programme), indexed by [`lanes/delivery-programme.md`](lanes/delivery-programme.md) |
| The review protocol this record discharges — §1 Completeness, §2 Correctness, §3 Quality | `rf2-hic-019` |
| What each miss is, its severity, and what it takes to close it | [`correction-ledger.md`](correction-ledger.md#the-ledger), and the bead each row names |
| Whether the kernel exit is met | this file |

One owner per fact. Bare `§1`, `§2` and `§3` throughout this page mean the review protocol's three
sections; specification sections are always written as `specification.md §N`. Findings are **not**
restated here: their text lives in the ledger row and their full detail in the bead.

## 1. The two halves of the exit, taken separately

### 1.1 The correctness half

Confirmed, and [§3](#3-what-was-re-run-and-what-it-measures) is the evidence. Four suites were re-run
from a clean checkout of `origin/main` and all four are green with raw captured exit codes; a sabotage
control was identified for every one of the eight risk families and each was either executed by the
suite or planted by hand and observed to redden.

The confirmation is bounded in one place, and the boundary is the operator's own: kernel row 5's native
IME half is **assumed, not witnessed** — see [§2.1](#21-row-5-is-assumed-not-witnessed).

### 1.2 The shell half

`substrate-decision.md:353-359` measures the R=0 package shell at **1,100 B** (Reagent segment) and
**1,095 B** (UIx segment) against the operator's frozen **1,024 B**, all twelve readings at or above
1,087 B, "a plain red" with no band-crossing verdict available. `rf2-hic-018` then **declines** to
re-register the line, on stated and good grounds, and records that the decider is the operator and only
he can move it (`substrate-decision.md:391-419`).

That is a correct refusal, and this checkpoint does not disturb it. What it leaves is an obligation with
nobody attached: `validation.md:794-795` names `rf2-hic-018` as the owner of the red row and that bead is
closed; the budget ledger's Authority column names it too, for five rows, and `rf2-hic-070` — also closed
— for the other two; and `correction-ledger.md`'s *Deferred items* table, whose stated job is that such
obligations "must not vanish into a green tick", does not carry it. Filed as `rf2-zk87` and `rf2-ltmd`.

**Until an operator disposition exists, Phase 1's exit clause is unsatisfiable as written**, whatever the
kernel does.

## 2. The eight kernel rows, one by one

Register order, as published in [`lanes/adversarial-risks.md`](lanes/adversarial-risks.md#phase-1-kernel-risks).
The verdict vocabulary:

- **WITNESSED** — the row's named scenarios each appear as a landed, executing witness; a sabotage
  control for the family exists and was seen to bite; the suites carrying it ran green here.
- **WITNESSED, record in flight** — as above, but a §1 artefact the row's bead owes is not yet on `main`.
- **ASSUMED IN PART** — a named scenario of the row has no measurement and is closed on an operator
  ruling rather than on evidence.

| # | Kernel risk | Witness set | §2 sabotage control | Row verdict |
|---|---|---|---|---|
| 1 | Frame reincarnation and cached operations | complete (`rf2-hic-013`) | executing — `reincarnation_routing`, `reincarnation_paint_dom` | WITNESSED |
| 2 | Process-global ownership | complete (`rf2-hic-012`, `rf2-hic-017`) | executing — `public_root_lifecycle_dom` W3 | WITNESSED, record in flight |
| 3 | Speculative render leakage | complete (`rf2-hic-010`, `rf2-hic-014`) | executing — `kernel_commit_owns_cljs` residue-census control | WITNESSED |
| 4 | Ambient-read extent | complete (`rf2-hic-011`) | executing — exact-refusal-map rows, both-ways witness | WITNESSED |
| 5 | Controlled-input portability | complete for the synthetic tier (`rf2-hic-016`) | hand-run, **re-run by this checkpoint** | **ASSUMED IN PART** |
| 6 | HMR identity | complete (`rf2-hic-015`) | executing — `lost-cleanup-sabotage`, `a-leaked-stale-registration-turns-the-cleanup-witness-red` | WITNESSED |
| 7 | Callback identity and retirement | complete (`rf2-hic-013`, `rf2-hic-029`) | executing — the unpinned-capture negative control | WITNESSED |
| 8 | Hydration isolation | complete (`rf2-hic-012`) | executing — `roots_frames_hydration_dom` H6 | WITNESSED |

Each row's named scenarios were checked against the witnesses **one for one**, by name, rather than by
counting files. Row 4's nine — nested helper, branch, loop, event, render prop, promise, timer, lazy
sequence, module — are nine `deftest` names at `read_extent_cljs_test.cljs:236-560`. Row 6's five are five
pinned section names in `hmr_spec.cjs:625-634`, enforced by name rather than by total. Row 2's four —
overlapping hydration, independent root release, concurrent request frames, root-unmount failure — are
four `deftest` names spread across three files, the last of them
`a-root-whose-teardown-throws-cannot-strand-its-siblings-state`.

### 2.1 Row 5 is assumed, not witnessed

`rf2-hic-016` was closed on 2026-08-13 by operator ruling, in its own words **"CLOSED AS ASSUMED, NOT
WITNESSED"**: the manual native-IME session was attempted and abandoned — a Japanese OS IME proved too
difficult to set up — and the operator ruled to close so the programme is not held on it.

Register row 5 names its deciding witness as "**WebKit/Firefox native composition and `beforeinput`**,
range/direction, autofill, reset, blur, unmount and upgrade matrix". The landed runner is honest about
the gap (`serve-and-run-hicasso-controlled-testbed.cjs:213-217`: real composition ranges are Chromium-only
and the abort signature "cannot be reproduced from page script in any engine, **so it is not claimed
here**"). Everything else on the row is measured, on three engines, and was re-measured here.

So the row is not a miss against the code and not a pass either. It is a scenario the programme has
decided to assume. That decision is the operator's to make and this record does not reopen it — but six
documents still describe the session as pending and owed, which is filed as `rf2-aubc`.

### 2.2 Row 2's record is in flight

`rf2-hic-017`'s deliverable is the mutable-global sweep's justification — the page that answers "root-scope
or justify every one". Its witnesses have landed and are green, but the ledger page itself
(`product/globals.md`, nineteen owners, zero migrations) is on PR **#8066**, which was open at
`d079143b91`. §1's "every kernel row maps to a landed witness" is therefore satisfied for row 2's *tests*
and not yet for its *record*. No bead is filed: the PR is live and owned. **The next reader of this page
re-checks that #8066 landed.**

## 3. What was re-run, and what it measures

From a fresh worktree of `origin/main`@`d079143b91`. Exit codes are the values captured to file, not the
harness's report.

| Suite | Result | Captured exit |
|---|---|---|
| `shadow-cljs compile node-test-hicasso` | — | **0** |
| `node out/node-test-hicasso.js` | **1127 tests, 4611 assertions, 0 failures, 0 errors** | **0** |
| `npm run test:browser` | **1473 tests, 9143 assertions, 0 failures, 0 errors** | **0** |
| `npm run test:hicasso-invariants` | freeze 1 row; motion/overlay/native/forms unreachable from the public door; **74 live complaints**, 6 reserved, 1 pending retirement, 1 retired, every live row emitted and rowed in Spec 009, every anchor resolving; budget ledger 38 rows | **0** |
| `npm run test:hicasso-controlled` | **97 checks across 13 sections on each of chromium, firefox and webkit** | **0** |
| `npm run test:hicasso-hmr` | **105 checks across 8 sections on each of chromium, firefox and webkit; 36 real shadow reloads** | **0** |

**The browser lane is the obligation the 2026-08-11 checkpoint left outstanding**, and it is now
discharged: kernel rows 2 and 8 live entirely in `:browser-test`, and both their witnesses and both their
sabotage controls executed in that green run.

For scale: the node lane read 553 tests on 2026-08-11 and reads 1127 here. The `ns-regexp`
(`implementation/shadow-cljs.edn:982`) is unchanged; the growth is two days of landed work.

### 3.1 The node lane still does not cover the DOM half

Unchanged from the previous record and repeated because it is a measurement fact, not a caveat:
`:node-test-hicasso`'s `ns-regexp` matches `-dom-cljs-test`, so DOM namespaces compile into the node lane
and their tests are counted in the 1127 — but in that lane every DOM claim degrades to a stated skip
(`roots_frames_support.cljs:85-91`, `impl/mount.cljs:447-451`). The skip is honest and it is not a
measurement. A green assertion whose reason is "there is no DOM here" answers no question about the DOM.

The remedy is that the browser lane ran. It is the 1473, not the 1127, that speaks for rows 2 and 8.

### 3.2 The HMR gate is a real reload, not a simulation

`test:hicasso-hmr` drove **36 real shadow-cljs reloads** across the three engines and asserted 105 checks
in eight sections named individually in `hmr_spec.cjs:625-634` and pinned by name in the runner's
`REQUIRED_SECTIONS`. A section deleted from the list fails the gate rather than shrinking a total, which
is the same structural-floor discipline the controlled gate uses. Row 6's register scenarios —
focused/uncontrolled input, child hook state, active host, frame routing, cleanup — are five of those
eight names, and the eighth is the family's executing sabotage.

## 4. The sabotage controls, one per risk family

§2 asks for one control per family, independently re-run. Seven of the eight are **executing controls**:
they are `deftest`s in the suites above, they ran in the green runs recorded in §3, and each asserts
*both* directions — the armed half reds if the mutation has quietly become a no-op, the disarmed half
reds if the defect returns. A control of that shape needs no hand-run to prove it bites; its armed half
failing **is** the proof, and it is taken on every run rather than on the day somebody remembered.

| Family | Control | How it was discharged here |
|---|---|---|
| 1 | `an-unpinned-bundle-does-reach-the-successor`; `restoring-the-macrotask-deferral-makes-the-paint-order-witness-fail` | executed, green |
| 2 | `a-page-wide-teardown-door-strands-the-sibling-root` (`public_root_lifecycle_dom:307`) | executed, green |
| 3 | `the-residue-census-can-answer-false` (`kernel_commit_owns_cljs:433`) | executed, green |
| 4 | the exact-refusal-map rows and `the-refusal-witness-answers-both-ways` | executed, green |
| 5 | disable the composition guard; the WebKit IME witness must redden | **planted by this checkpoint** — [§4.1](#41-the-one-control-that-had-to-be-planted) |
| 6 | `lost-cleanup-sabotage` (`hmr_spec.cjs`); `a-leaked-stale-registration-turns-the-cleanup-witness-red` | executed |
| 7 | `NEGATIVE-CONTROL-an-unpinned-capture-does-reach-the-successor` | executed, green |
| 8 | `a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition` (H6) | executed, green |

Families 2 and 8 are `rf2-1mmn`'s repair, filed by the previous checkpoint when neither had a control in
any form. Both were read in full here and both are the shape the finding asked for.

### 4.1 The one control that had to be planted

Row 5's sabotage is named by `rf2-hic-016`'s acceptance — *disabling the composition guard must turn the
WebKit IME witness red* — and it is not a `deftest`. It is a source mutation, and the runner's own
docstring (`serve-and-run-hicasso-controlled-testbed.cjs:228-246`) records it being run by hand on
2026-08-10 and again on 2026-08-11, with the failure it produced. **A hand-run mutation recorded in a
comment is not re-runnable by a reviewer**, which is the shape `rf2-1mmn` indicted for rows 2 and 8. This
one is better than what that finding described — it is dated and it quotes its failure text verbatim,
rather than pointing at an unnamed PR body — but it still had to be taken again to be believed.

So it was. `impl/controlled.cljs:380-381`'s `composing-input?` body was replaced with `false` — the whole
carve-out off, both halves, since the draft shadow is held from the same reading — and
`HICASSO_TESTBED_ENGINES=webkit npm run test:hicasso-controlled` was run against the plant:

```text
FAIL Hicasso controlled input (I15) — three engines (webkit):
  [webkit] the first composing update survives in the field: expected "123あ", got "123"
```

**Captured exit 1.** That is the same row and the same failure the 2026-08-10 session recorded, reproduced
by a reviewer who wrote none of it. The guard was then restored with `git checkout --`; `git diff` under
`implementation/` is empty and no source change is carried by this record's PR.

The green baseline it is measured against is §3's `test:hicasso-controlled` run — 97 checks on webkit,
captured exit 0 — taken on the same worktree immediately before the plant.

**What this does and does not establish.** It establishes that the composition carve-out is load-bearing
and that the WebKit witness reddens when it is removed: the family has a control and the control bites.
It establishes nothing about *native* IME composition on WebKit, which drives a synthetic composition
sequence rather than a real one — see [§2.1](#21-row-5-is-assumed-not-witnessed).

## 5. The misses

### 5.1 Filed by this run

| bd id | Protocol section | Severity | One line |
|---|---|---|---|
| `rf2-aubc` | §1 Completeness | correctness | Six records still promise a native-IME session the operator ruled will not happen |
| `rf2-zk87` | §1 Completeness | coverage | The byte-exact shell disposition is a Phase 1 exit conjunct with no owner and no ledger row |
| `rf2-ltmd` | §1 Completeness | coverage | All seven non-`MET` budget rows name a closed bead as the authority owning them "today" |

None of the three is a defect in the kernel. All three are the governance layer describing the kernel
inaccurately, which is the failure class this programme treats as load-bearing.

### 5.2 Closed by this run

The five misses the 2026-08-11 checkpoint filed have all merged and all had their producing protocol
section re-run here against `main`@`d079143b91`, by a reviewer who wrote none of them. The evidence is in
each [ledger row](correction-ledger.md#the-ledger); the closure rule is that page's, and this checkpoint
was the ledger keeper while it ran.

## 6. Checked, and found sound

A checkpoint's negative findings are worth as much as its positive ones. The 2026-08-11 record's list is
carried forward whole — it is that checkpoint's work and remains true — and this run adds:

- **Time is never the authority on ownership.** Both reapers in `impl/collector.cljs` are scheduled by
  `setTimeout` and decided by a count: `arm-cell-reaper!` (`:622-631`) disposes only
  `(when (and (zero? (alength (.-readers cell))) (not (.-disposed cell))))`, and `arm-entry-reaper!`
  (`:1321-1333`) drops only `(when (zero? (.-refs entry)))`. `entry-reap-horizon-ms`'s docstring
  (`:1284-1319`) is explicit that the 4 ms is "**A MARGIN, NOT A CONTRACT**", that no caller may rely on
  it, and that losing the race "costs a cache miss and a rebuilt subscription, **never a wrong value**".
  That is exactly what the register's row-3 remedy asks for — commit identity authoritative, time
  demoted to scheduling.
- **Both new sabotage controls arm in both directions.** `public_root_lifecycle_dom:307` runs the same
  two-root construction twice and its armed half fails loudly with "THE SABOTAGE DID NOT SABOTAGE" if the
  page-wide door stops being page-wide; `roots_frames_hydration_dom:702` asserts the armed half's
  page-wide window is *still open* on the line where the shipped tray reads its phase, so what the tray
  answers is the scoping doing work rather than the absence of any window. Neither needed an invented
  seam: one uses the shipped `impl.mount/release!`, the other restores the pre-`rf2-6tmu` page-global.
- **The residue census can be made non-zero.** `the-residue-census-can-answer-false` leaks exactly one
  abandoned-read registration by hand, asserts the summed counters *and* the per-key reader list see it,
  and then waits on `inventory/quiesced!` to assert that **the reapers do not launder it**. Every zero in
  that file is worth what that row is worth, and the row is real.
- **The four corrective fixes are on `main` and are what their beads said.** `boundary.cljs:186-227` has
  the closed `prop-roster` and the `:on-error` shape check; `codec.cljs:1925-1967` refuses a form after
  `opts` and `:1839` guards `opts` is a map; `native.cljc:357-404` validates the declaration map against
  both rosters; `codec.cljs:803` refuses a `::h/mounting` override out of a tray's reach.
- **The three-engine controlled matrix is unanimous and re-measured.** 97 checks × 13 sections × three
  engines, no divergence, `NARROWINGS` empty — re-run here rather than quoted.

## 7. Considered, and not filed

- **Rows 2, 7 and 8 publish no `declared-population` roster**, where rows 1, 3, 4, 5 and 6 do
  (`reincarnation_paint_dom`, `kernel_commit_owns_dom`/`activity_suspense`, `read_extent_dom`, and the two
  `REQUIRED_SECTIONS` floors in the testbed runners). Not filed, for the reason the 2026-08-11 record gave
  when it declined the same finding for `read_extent_cljs_test`: each of those rows' register scenarios
  already appears as a `deftest` name, one for one, and a roster `def` beside them would be process for
  its own sake. **The honest caveat**: row 2's four scenarios are spread across three files, so no single
  artefact would red if one were deleted. That is a general property of this repository's tests, not a
  kernel defect, and buying a guard for it here alone would be arbitrary.
- **The negative controls for families 1 and 7 are not literally the mutation their beads named.** Both
  beads say "remove the incarnation check / the retirement check and the case must go red"; what landed is
  an *unpinned capture*, which by construction never had a pin, driven through the public
  `rf/capture-frame` seam. It establishes the same A/B — pin present, write refused; pin absent, write
  lands — without redefining a substrate var, and both files say so in as many words. The difference is
  real and it is not worth a bead.
- `codec.cljs`'s `component` field on the host head and `portal.cljs:138-141`'s slightly over-reaching
  docstring sentence, both recorded by the 2026-08-11 checkpoint and both still true. Still not filed, for
  its reasons.

## 8. What this record is not

It is not a declaration that Phase 1 has exited: [§1.2](#12-the-shell-half) is why, and no amount of green
in [§3](#3-what-was-re-run-and-what-it-measures) reaches it. It is not evidence about native IME conduct on
Firefox or WebKit, which nothing has measured. It is not a `pass` on row 2's record, which is on an open
PR. It is the honest state of Checkpoint 1 on `main`@`d079143b91`: a kernel whose correctness the suites
now support, three governance misses filed, five earlier misses closed with evidence, and an exit clause
waiting on a decision only the operator can take.

## 9. Where this page's words came from

This revision was written by the reviewer who ran it, in the same session, from the suites and sources it
cites. Every figure in [§3](#3-what-was-re-run-and-what-it-measures) is a value captured to file by that
run rather than a quotation; every line number was read at `d079143b91`. [§6](#6-checked-and-found-sound)
carries the 2026-08-11 record's list forward by reference and adds its own; [§7](#7-considered-and-not-filed)
does the same and says which items are inherited.

The record it replaces was assembled at one remove — the checkpoint of 2026-08-11 ran under a read-only,
no-PR fence, so its verdict was published by a second worker from bead notes, and a provenance repair
(PR #7917) was needed afterwards to say so. That seam does not exist in this revision, which is the main
reason the re-dispatch was given a worktree and a PR.
