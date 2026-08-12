# Hicasso budgets: named reference profiles and pinned baselines

This page prepares [specification §6](specification.md#6-performance-contract)
for ratification. It does not ratify anything. **Ratification authority is the
re-frame2 product operator's (Mike Thompson)**; this document records the
profiles a budget is stated *on*, the estimand/instrument/control standard each
row must meet, and the state of every baseline the later gates will anchor to.
Enforcement lives in [`rf2-hic-089`](#7-where-each-row-is-enforced) (the early
framework) and `rf2-hic-071` (the full gates), never here. What this page does
carry, since 2026-08-12, is [§9's reconciliation ledger](#9-the-budget-line-reconciliation-ledger)
— one row per registered line, each stating its own verdict — and the gate that
keeps that ledger honest. **That gate enforces the record, not the budgets**:
it can tell you a breach is unrecorded, and it cannot tell you a budget is met.

Three things are deliberately *not* decided on this page, because each is
another bead's to decide:

| Question | Owner |
|---|---|
| The R=0 shell's breach of its paper-fail line — remediate, or disposition it | `rf2-hic-018` |
| The per-read K3 record and its three non-substitutable scoreboards | `rf2-hic-070` — decided in [`k3-disposition.md`](k3-disposition.md) |
| Turning any row below into a blocking gate | `rf2-hic-089`, `rf2-hic-071` |

---

## 1. The named reference profiles

A budget without a named machine is not a budget. Two profile classes are
registered, and they are **not interchangeable**: one may source a
distributional product budget, the other may never.

### P-DEV-1 — the pinned physical profile

The operator's development machine. This is the only profile from which a
**distributional product budget** (clock, heap) may be stated.

| Field | Value |
|---|---|
| Machine | Acer Predator PHN16S-71 |
| CPU | Intel Core Ultra 9 275HX |
| Cores / threads | 24 / 24 |
| Base clock | 2,700 MHz |
| RAM | 63.4 GB |
| OS | Windows 11 Home, build 26200 |
| node | 24.13.0 |
| React | 19.2.0 |
| Playwright | 1.59.1 |

Quiet-state verification for this page's run, read from real counters rather
than from `Win32_Processor.LoadPercentage` (which has misreported 93% against a
true 11%):

```
Get-Counter '\Processor(_Total)\% Processor Time'   16.8 / 32.2 / 15.8 %
Get-Counter '\System\Processor Queue Length'        0 / 0 / 0
```

**Processor queue length is the decisive number**, because it says whether
anything was waiting for a core. It read 0 on every sample.

### CI-RUNNER-A — the hosted runner class

GitHub-hosted runners. **Correctness gates and same-run relative drift only.**
A hosted runner may never source a distributional product budget: its hardware
identity varies between runs and it is shared silicon. Where a drift comparison
is made on it, both arms must come from the *same run* and the observed
hardware/runtime identity must be recorded with the result — a floor is never
imported from another run.

### The single-profile limitation, accepted explicitly

Bead `rf2-hic-006` asks for either two physical distributional profiles (a
low-tier and a mid-tier machine) **or** an explicit record that a
single-profile limitation is accepted and why. **This document takes the
second branch, and states the cost.**

There is one physical machine in the programme. No low-tier machine exists to
measure on, and none is funded. The consequence is precise and must not be
softened: **every distributional figure in this corpus is a P-DEV-1 figure, and
carries no cross-hardware generalisation whatever.** A budget met on P-DEV-1 is
not thereby met on a low-tier machine, and this programme currently has no way
to find out. The user-visible budgets in §4 below — 50 ms p95 to next paint and
the rest — are the rows where that gap bites hardest, because they are claims
about *users*, and users are not on P-DEV-1.

Lifting it needs one thing only: a second physical machine of a named lower
tier, with the same instruments run on it. Until then the limitation is
accepted, recorded here, and inherited by every page that quotes a
distributional number.

---

## 2. The two disposition families, which §6 already separates

§6's rows are not one kind of thing, and treating them as one is how a
measurement programme gets both halves wrong. The specification draws the line
itself:

> Deterministic correctness, residue, scaling-shape, and production-erasure
> gates block ordinary changes. Noisy clock/heap distributions are adjudicated
> in pinned, interleaved evidence runs rather than converted into flaky PR
> thresholds.

That sentence sorts every row below into one of two families, and the families
have opposite operational rules:

| | **Deterministic** | **Distributional** |
|---|---|---|
| Value | integers — body counts, residue counters, shape | bytes, milliseconds |
| Machine sensitivity | **none** — a counter reads the same on a loaded box | high — needs a quiet window |
| Where it belongs | ordinary blocking PR gates | pinned interleaved evidence runs |
| Profile needed | any | P-DEV-1 only |
| Anchored on `implementation/hicasso` today? | **yes** | **the heap rows S1–S5, yes** — since 2026-08-12, §6; the clock rows, no |

The practical consequence, and the reason it is worth naming: **the
deterministic family did not need this measurement window at all.** A body
count is a delta on a monotone integer counter; contention cannot move it. Only
the distributional family needs the quiet box — which is exactly why this page
could not re-pin that family when it was written, and why the re-pin it refused
had to be taken later, alone, in a window of its own.

---

## 3. Deterministic rows, pinned on the moved package

These are pinned **on `implementation/hicasso`** — the moved package itself,
not a donor — at commit `0c0aa22898`, through the supported test-kit facade
`re-frame.hicasso.test.mounted`. No `impl/` reach-through is involved.

The instrument for body counts is [`hm/bodies-run`][kit], which is page-wide,
handle-free, and takes its reading as a delta on the runtime's monotone
counter. Its docstring names specification §6's narrow-update row as the budget
it exists to make assertable.

[kit]: ../../../../implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs

| # | Estimand | Figure | Witness | Control that moves it |
|---|---|---:|---|---|
| D1 | Boundary bodies run per keystroke, 5×5 grid | **2** | `grid/scaling_dom_cljs_test` | D2 — same measurement at 10×10 |
| D2 | Boundary bodies run per keystroke, 10×10 grid | **2** | `grid/scaling_dom_cljs_test` | D3/D4 — coarse shape moves it to 26/101 |
| D3 | Same, coarse shape with closure props, 5×5 | **26** | `grid/scaling_dom_cljs_test` | D1 — the fine shape reads 2 |
| D4 | Same, coarse shape with closure props, 10×10 | **101** | `grid/scaling_dom_cljs_test` | D3 — quartering the grid moves it to 26 |
| D5 | Bodies run for a *refused* keystroke | **0** | `grid/scaling_dom_cljs_test` | D1 — an accepted keystroke reads 2 |
| D6 | Bodies run for `clear-row` on a 10-wide row | **11** | `grid/scaling_dom_cljs_test` | D5 — a refused edit reads 0 |
| D7 | Bodies run, first keystroke of an editor session | **2** | `editor/flow_dom_cljs_test` | D8 — the second keystroke reads 1 |
| D8 | Bodies run, every keystroke after the first | **1** | `editor/flow_dom_cljs_test` | D7 — the session's first reads 2 |
| D9 | Teardown residue in counters and frame ids | **zero** | `hm/assert-clean!`, every DOM witness | its own pre-mount baseline delta |
| D10 | Hiccup-walk entries a direct return drops (`codec/vec->element`) | **3** | `direct_return_cljs_test` | write the same body as hiccup — reads 4 against a floor of 1 |
| D11 | Prop-pipeline entries a direct return drops (`codec/convert-props`) | **3** | as D10 | as D10 — reads 3 against a floor of 0 |
| D12 | Event-lowering entries a direct return drops (`intent/lower-prop`) | **2** | as D10 | as D10 — reads 2 against a floor of 0 |
| D13 | Controlled-repair entries a direct return drops (`controlled/install!`) | **3** | as D10 | as D10 — reads 3 against a floor of 0 |
| D14 | Wrappers between the element type React reconciles and the author's own function, declared island | **0** | `three_way_parity_cljs_test` | the `:client-only` default answers a gate instead; UIx's route answers a generated component |
| D15 | Slots on the props object React carries, and they are the author's own | **1** | as D14 | UIx's route also reads 1 — but the slot is `argv`, which the author never wrote |
| D16 | Unwrapping hops per render, per component, native and handwritten React | **0** | as D14 | the UIx route reads 1 — the `argv` carrier being opened |

**These figures are cited, not re-derived.** D1–D8 were established by the two
witness apps that landed on `main` ahead of this page; re-deriving them here
would create a second source for one number and therefore a second thing to
drift. What this page adds is the same-instrument confirmation that they hold
at `0c0aa22898` (§8), and the control column above.

**On D1/D2 — the acceptance row.** Quadrupling the mounted cells changes
nothing, which is §6's *narrow-update body work scales with changed rows rather
than all mounted rows*, measured rather than asserted. D3/D4 are the positive
control that gives the row its meaning: the same app in a coarse shape scales
26 → 101 with the grid, so the instrument demonstrably *can* see amplification
and D1/D2's flatness is a property of the topology, not of a blind instrument.

**On D10–D13 — the direct-return delta, deterministic half (`rf2-hic-033`).**
Specification §5's Rung 3 lets a `defview` body answer an already-constructed
React element and skip hiccup lowering for that result. D10–D13 price what that
skips, on one page written twice — same props, same two ambient reads, same
data, and DOM that is **byte-equal** under React's own server renderer — by
counting entries into the four shipping functions React would otherwise have
reached. Every count is read against the crossing's own **floor**, not against
zero: a boundary is reached through a hiccup vector whatever its body answers,
so the crossing is still walked and only the body's own lowering is gone.

Two departures from this section's stated method, both deliberate:

- **These rows reach through `impl/`.** There is no test-kit facade for *how
  many times did the codec walk*, and inventing one would be a public export
  bought to make a measurement quotable. The counts are taken by wrapping the
  shipping var and restoring in a `finally`, which is the convention
  `walk_profile_baseline_cljs_test` already established for exactly this
  question.
- **They are counts, not clocks**, so they are deterministic and carry no
  hardware profile. That is what lets them sit in this section rather than §4,
  and it is also their limit: **they say what work is skipped, never how long
  that work took.**

**The clock half of the direct-return delta is an OPEN obligation, not a
figure.** Specification §6 lists it in the bounded experiment set, and the bead
asks for a pinned interleaved run on both named reference profiles published
with witness, instrument and confidence. No such run has been taken: the
instrument would be the P0 lane driver riding `:hicasso-bench`, and the machine
available to `rf2-hic-033` was carrying three concurrent compiles, which is not
a window a duration can be attributed in. Nothing in D10–D13 licenses a claim
about how much faster a direct return is, and a reader wanting the remedy's
price must wait for that row rather than read one off these. It is tracked as
`rf2-5yn9`.

**On D14–D16 — the island band's structural half (`rf2-hic-034`).** These read the
construction cost of a native island as a structural fact rather than as a clock,
over one subject written three times: a cell that paints a label, as a declared
native island, as a handwritten React component, and as a UIx component. D14 is
the element type each route hands React — the native tier's is `identical?` to the
author's own function, and so is the handwritten arm's, so nothing sits between
React and the body. D15 reads the props object React then carries: one slot on
every route, and on those two arms it is the author's own `label`. D16 is what the
first two make of the third. UIx's element type is a generated component and its
one slot is `argv`, a carrier `uix/$` builds around the map the author wrote, so
that route pays one unwrapping hop per render per component where the other two
pay none.

**This is a stronger reading than a timing, not a weaker one.** A declared island
and a handwritten React component are the same element type carrying the same
props object, so there is no interposed work for a stopwatch to find — a thing a
counter can decide and a clock could only fail to detect. It is also why §6
requires the native tier to be co-instrumented against handwritten React and not
against UIx alone: a floor set by UIx would have D16's hop inside it.

Three qualifications, in the order a reader meets them. **The band is stated over
the DECLARED arm** — since `rf2-hic-046` the `:client-only` default answers a gate
rather than the author's function, costing one fiber and one hook the declared arm
does not, and that is the ruled price of a conservative default rather than a
figure about `n/defcomponent`. **They are counts and identities, not clocks**, so
like D10–D13 they carry no hardware profile, which is what lets them sit in this
section rather than §4. And **they are the deterministic half of C7 only**: they
say there is no interposed work, never how long a render takes, so C7 stays
`UNPINNED` and its clock half remains `rf2-hic-071`'s, along with the ladder re-pin
and the package-resident clock instrument that half needs.

**On D9.** This is teardown residue in **counters and objects**, which is a
deterministic reading the package can make. It is *not* S5, the teardown row
stated in **retained bytes** — a distributional figure, taken in its own quiet
window and re-pinned on the package only on 2026-08-12. The two must not be
conflated: a mount can leave zero residue counters and still retain bytes, and
the two halves are not interchangeable evidence even now that both are package
figures. D9's counters are **exactly** zero; S5's bytes are *indistinguishable
from* zero, which is the strongest form a distributional reading takes.

---

## 4. Distributional rows — S1–S5 re-pinned on the package, S6–S7 carried

**S1–S5 are package figures as of 2026-08-12.** They were re-measured on
`implementation/hicasso` in one solo quiet-window run, on the same P0 ladder
`rf2-hic-006` had to refuse — repointed at the package by PR #7939 and read
through by `rf2-fe0l`. **S6 and S7 are still carried unchanged** from
[the evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence)
and are **not** package figures: no package-resident clock instrument exists,
and the allocation row has no publishable claim to re-pin.

| # | Estimand | Pinned figure | Instrument | Status |
|---|---|---|---|---|
| S1 | R=0 boundary shell, Reagent segment | `1,100 B` [1,091–1,107] | P0 ladder, **package** candidate arm | **package figure** — over the frozen `1,024 B` line in every round |
| S2 | R=0 boundary shell, UIx segment | `1,095 B` [1,087–1,101] | as S1 | **package figure** — over `1,024 B` in every round |
| S3 | Per-read retained, Hicasso vs Reagent | `1,417` vs `948 B/read` | as S1 | **package figure**; K3 scoreboard (a) — dispositioned by [`rf2-hic-070`](k3-disposition.md#3-scoreboard-a--governed-viability-against-the-best-shipped-path) |
| S4 | Per-read retained, Hicasso vs UIx | `2,115` vs `2,980 B/read` | as S1 | **package figure**; K3 scoreboard (b) — dispositioned by [`rf2-hic-070`](k3-disposition.md#4-scoreboard-b--architecture-progress-against-the-uix-parent) |
| S5 | Teardown, retained **bytes** | indistinguishable from zero — all ten candidate rungs' bands straddle 0 | as S1 | **package figure** |
| S6 | Cold mount vs direct UIx-on-subs | `1.1718x` [1.1263–1.2190] n=8; `1.1976x` [1.1504–1.2468] n=6 | final K1 estimator | **bench-tree figure** — registered `1.10x` gate missed; `1.25x` is a *proposal* only |
| S7 | Warm allocation | no publishable claim | allocation instrument | **bench-tree** — no fitted series clears the quality floor |

The run's evidence, its controls and its full provenance are
[on the ladder's studio page](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l);
what follows is only what the re-pin does to this page's rows.

**S3 is the one figure that moved, and the instrument says it is the
candidate's.** Nine of the ten quantities the run took — both donors, both
floors, both shells and the UIx-segment slope — came back on the prototype's
published anchors. S3's candidate slope did not: `1,278 → 1,417 B/read`,
`+139 B` and `+10.9%`, with the two bands disjoint. The governed contrast
against Reagent therefore deepens from `1.3492×` to `1.4953×`, while S4's win
against UIx is unchanged to the fourth decimal (`0.7099× → 0.7098×`).
**Attribution was deliberately not attempted in that window** — its terms are a
single invocation, and the ablations that would name a cause are exactly what a
measurement window may not run. It was taken afterwards, by `rf2-l50z`, and the
answer is **one line**: `interop/activate-derived-value!` in the collector's
`wire-cell!`, a ratom-only correctness repair that landed between the two
sessions. **The figure does not move** — S3 stays `1,417 B/read` — because the
attribution names the cost rather than removing it. The bisection is
[on the ladder](../studio/reads-per-boundary-heap-ladder.md#the-139-bread-attributed-to-one-line-and-the-premise-it-had-to-correct-first-rf2-l50z);
the disposition is [substrate-decision §6](substrate-decision.md#6-what-this-page-does-not-decide).

**S1 and S2 carry the substantive news for the shell.** The breach is not an
artefact of the prototype — it survives the move to the package essentially
unchanged, at `1,100` and `1,095 B` against `1,103` and `1,097 B`.

**S6 carries a standing prohibition.** The `1.25x` cold-mount ceiling is a
proposal pending the operator's sitting. Until ratification the registered
`1.10x` line is the only adjudicated one, and **no evidence row may use the
proposal to mark K1 green.**

### The §6 user-visible budgets

Transcribed with their estimands. All are P-DEV-1-only and all inherit §1's
single-profile limitation.

| # | Budget | Estimand | Family |
|---|---|---|---|
| U1 | Controlled updates correct same-turn, echoed within one 60 Hz frame at p95 | latency to visible echo | distributional |
| U2 | Ordinary discrete interactions reach next paint within 50 ms p95 / 100 ms p99 | latency to next paint | distributional |
| U3 | Broad application operations within 100 ms p95 unless classified background | operation latency | distributional |
| U4 | Dragging/animation stay inside frame budget | per-frame latency | distributional |
| U5 | Narrow-update body work scales with changed rows, not all mounted rows | boundary bodies run | **deterministic** — pinned as D1–D4 |
| U6 | Teardown residue is zero after quiescence | residue counters / retained bytes | **split** — D9 deterministic, S5 distributional |

U5 and the deterministic half of U6 are pinned on the package today. U1–U4 are
not, and cannot be until a package-resident clock instrument exists.

### The comparative and regression rules

The eight rules carry ids so that §9's ledger can reconcile each one by name.
The ids are this table's, not a second register: a rule is registered *here*
and its verdict is recorded there.

| # | Rule | Disposition |
|---|---|---|
| C1 | Pinned ordinary-Hicasso benchmark does not regress > 5% on same witness and instrument | same-instrument regression **blocks** until the benchmark owner validates the instrument and the adapter owner fixes or reverts |
| C2 | Cold mount ≤ 1.25x direct UIx (subject to ratification) | proposal only; `1.10x` registered line stands |
| C3 | Broad updates ≤ 1.25x best relevant adapter after topology tuning | 1.25–1.5x is a **warning band**: attribute cause, one bounded topology pass, test a local island |
| C4 | Sustained > 1.5x | cannot graduate as ordinary Hicasso until fixed or deliberately classified a native-host use case |
| C5 | R=0 shell meets the frozen byte-exact `1,024 B` line | **not** governed by baseline-plus-10%; see §5 |
| C6 | Per-read retained ≤ 10% regression on same pinned witness | governed by the K3 disposition (`rf2-hic-070`) |
| C7 | Native island within 5% or 1 ms of the same component mounted directly | co-instrumented against both handwritten React and UIx |
| C8 | An escape recovers ≥ 20%, saves ≥ 2 ms p95, or converts a failed budget to a pass | an island missing its threshold is simplified or removed — **thresholds do not widen to keep it** |

---

## 5. The read-free boundary shell: the byte-exact line, now FROZEN at 1,024 B

> **RULED, 2026-08-12 (operator-directed, operator-overturnable).** The paper-fail
> line is frozen at the literal **`1,024 B`**. Spell it that way everywhere; the
> ambiguous `1 KB` spelling retires. Adopted with it: **a confidence band that
> crosses 1,024 B is UNRESOLVED, not a pass**, and no substrate is selected on a
> point-estimate difference inside that band. The ruling text is on `rf2-fe0l`.
>
> The rest of this section is the record of *why* the freeze was needed and
> what it does and does not decide. It is kept as written — the recommendation
> below is the reasoning the ruling adopted, not a live question — because a
> page edited to look as though it had always known the answer stops being a
> record of how the answer was reached.

### What the package reading does to this row

The shell has now been measured on `implementation/hicasso` itself
([the run](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l)),
and the freeze is **not load-bearing for the present verdict** on either tree:

| R=0 shell | Reagent segment | UIx segment | worst round | vs `1,024 B` |
|---|---:|---:|---:|---|
| pinned baseline row (bench tree) | 1,103 B | 1,097 B | — | over |
| ladder re-take on the shipping bench tree | 1,099.5 B | 1,097 B | — | over |
| **the package (S1 / S2)** | **1,100 B** | **1,095 B** | 1,091 / 1,087 B | **1.074× / 1.069× — over, in every round** |
| frame-prop variant (bench tree) | 1,054 B | 1,051 B | 1,047 B | over |

Every one of the twelve package readings sits at or above **1,087 B**, so the
row is red under the frozen reading, red under the retired 1,000 B one, and
**not a band-crossing case at all**. What the freeze forestalls is still ahead:
the no-wrapper arm's `994` / `992 B` sits exactly in the window the two readings
disagreed about, so a remediation landing there would have been decided by a
coin-flip. It no longer can be.

**The breach is a property of the design, not of the prototype.** That is the
substantive finding the package re-pin adds, and it is `rf2-hic-018`'s to
disposition.

### The record: why the freeze was needed

`rf2-hic-006` asks this page to resolve, **from the registered instrument's
source of record**, whether the paper-fail line is 1,000 B or 1,024 B.

**The source of record is [`validation.md`](../validation.md), and it does not
resolve the question.** Its row reads, in full:

> Exclusive retained per boundary (**the R=0 boundary shell**) — target
> ~0.4–0.5 KB; > 1 KB fails on paper.

That is the registered text. It says `1 KB` and defines no byte expansion, and
no other registered document supplies one. The heap ladder — the instrument's
own studio page — does not assume one either; it deliberately reports **both**
readings side by side wherever the answer could turn on it. **So the honest
finding is that the byte-exact interpretation was never frozen, and this page
cannot discover a freeze that does not exist.** Freezing it is an operator
ruling, and it is put here rather than invented.

### The freeze is not load-bearing for today's disposition

It matters that this is recorded without overstating its urgency. **The current
row is red under either reading**, so the freeze changes nothing about the
present breach:

| R=0 shell, shipping (wrapper) arm | Reagent segment | UIx segment | vs 1,000 B | vs 1,024 B |
|---|---:|---:|---:|---:|
| pinned baseline row | 1,103 B | 1,097 B | over | over |
| ladder re-take on the shipping tree | 1,099.5 B | 1,097 B | 1.10x | 1.07x |
| frame-prop variant | 1,054 B | 1,051 B | 1.05x | 1.03x |

Where the freeze **will** become load-bearing is remediation. The ladder's
no-wrapper arm reads **994 B / 992 B** — 6 B and 8 B under 1,000, inside a
per-round band that straddles the line, and well inside the ~75 B
component-shape sensitivity `validation.md` states for it. A remediation
landing anywhere between 1,000 B and 1,024 B would be *green under one reading
and red under the other*, decided by a coin-flip nobody has called. That is
precisely the case the freeze exists to forestall, and it is now reachable.

**Recommendation to the operator, not a ruling: freeze it at 1,024 B**, on the
grounds that `KB` in a memory context conventionally expands to 2^10 and the
instrument reports raw bytes either way. Recorded as a recommendation; the
decision is the operator's.

> **This recommendation was adopted on 2026-08-12, on these grounds.** See the
> ruling at the head of this section. The sentence above is left standing as
> the reasoning that was put to the operator, not as a live question.

### The breach is carried, never normalised

The current breach stands at **R=0 = 1,100 B / 1,095 B on the package**, against
the frozen `1,024 B` line. It is a **live pressure owned by `rf2-hic-018`**, not
a disposition this page may make — that disposition has since been taken, and it
is [the substrate decision record](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition)'s:
the substrate arm is refused on the evidence, the breach is carried red, and the
line is not re-registered. The row here is never silently normalised into a
percentage allowance — §6 says in terms that a relative regression allowance
cannot recolour the red shell row.

One drift is recorded rather than smoothed. The baseline's pinned Reagent
figure was `1,103 B`; the ladder's later paired re-takes on the shipping bench
tree read `1,101 B` and `1,099.5 B`, and the UIx segment reads `1,097 B` in all
of them. The arms overlap in band and the ladder page itself notes the
difference. Those rows are left exactly as measured, because a measurement
record edited to match a later run stops being a record. **What has changed is
which of them this page pins**: the package re-pin (§6) has now happened, so
S1/S2 are `1,100` and `1,095 B` and the earlier figures are the bench-tree
lineage behind them rather than the live rows. That the package reading landed
within 3 B and 2 B of the bench-tree one is a result, not a formality — it is
the reason the whole prior lineage remains readable as evidence about the same
design.

---

## 6. The re-pin on the moved package: REFUSED, then DISCHARGED

> **DISCHARGED 2026-08-12 by `rf2-fe0l`.** The refusal below stands as the
> record of why this page could not deliver the re-pin, and every word of its
> diagnosis held: no heap instrument pointed at the package, and the registered
> one had drifted from its own pin. Both were fixed in the order this section
> asked for. PR #7939 repointed the existing P0 ladder's four candidate seams at
> `re-frame.hicasso` — **reusing the driver, donors, floor, harness, fixtures,
> fit rules and order guard unchanged, so the estimator did not move** — and it
> merged, fixing the rig's blobs, *before* any sample existed. One solo
> quiet-window run then took the package arm and **both** donor arms in the same
> run set, as this section required. S1–S5 in §4 are package figures as a
> result.
>
> Two of this section's stated conditions were met differently from the way it
> guessed, and the difference is worth recording. **No new build id was needed
> and no hot-zone file was touched**: the driver already rides `:hicasso-bench`
> through `P0_BUILD` / `P0_INIT_FN`, and `hicasso/src` is already on that build's
> source paths, so `:hicasso-heap-bench` was deferred rather than built (to
> `rf2-hic-071`, reconsidered only if this becomes a standing gate). And the
> `~5% common-mode offset` caution below is about the **two harnesses** — P0
> against the freehand ladder — not about two runs of this one; the
> cross-session comparison in the studio section is licensed instead by the
> donors reproducing their published anchors within 1 B, which is this corpus's
> own standing rule.
>
> **The instrument-drift half is closed by supersession, not by repair.** The
> eleven pinned blobs tabulated below have not been made to match; they never
> can be, and `front/sub_index.cljs` no longer exists. What has changed is that
> the drift is no longer *un-attributable*: the run below took the package and
> both donors on one instrument in one session, so the delta between it and the
> prototype is read against same-run donors rather than against a stale pin.

`rf2-hic-006` asks for the shell, per-read and teardown baselines
**re-measured on `implementation/hicasso`** so later gates have a
same-instrument anchor. **This page refuses that deliverable and reports why.
The refusal is the finding.**

### The instrument does not measure the package

The pinned heap figures come from `p0_run.cjs --only ladder`, whose candidate
arm is `re-frame.bench.hicasso.arm1.*` in the benchmark tree. That runtime's
own docstring states its residence:

> Residence: the bench/test tree, off every production source path (HD-017).
> **Nothing under `implementation/*/src` requires this.**

`implementation/shadow-cljs.edn` states the same separation from the other
side — the bench tree "stays exactly where it is and keeps building under
`:hicasso-bench`; nothing in the package imports it, and
`hicasso/scripts/check_freeze.py` is what says so."

So `implementation/hicasso/src` is a **copy** of that runtime, deliberately
frozen apart from it, and **no heap instrument is pointed at the package at
all.** The merged-PR audit of #7759 reached the same conclusion from the
`npm run bench:hicasso` side and is recorded on the bead.

### The registered instrument has also drifted from its own pin

Independently of the above, the instrument is no longer the one that produced
the pinned rows. The heap ladder identifies itself **by content hash, not by
commit SHA**, and says plainly that "if a SHA does not resolve, the blobs are
what to trust". At `0c0aa22898`, **not one of the eleven pinned blobs matches**,
and one file no longer exists:

| file | pinned blob | at `0c0aa22898` |
|---|---|---|
| `reads_ladder_run.cjs` | `eabd226bcb6fe3877d056145cae496eccd5ab62c` | `7c10142110543bf67e2f9b8df57b14fdea46fddb` |
| `p0_run.cjs` | `4718aaead7035ae9a6cf74a89ef13141803742cc` | `30e168756279b37edc47f7a900d009f861903966` |
| `p0_heap.cljs` | `34c9210dfe39d3c7ee153c724fa63cf8e65dd1e1` | `0d3ef77db05bcec08d9f37f861bb4af30f88901d` |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` | `a24fcebc7a8a0ac11dafb215ffbe462b6e9ccf4d` |
| `arm1/runtime.cljs` | `69bfc6fc23af3035af88a2f69c4f4623a869fd83` | `202f7612ae356602b14db038bfd9249c16930acb` |
| `front/sub_index.cljs` | `394927d6f6493ea651daac84b9f140cd54f8f6c1` | **absent** |

Four other blobs in the same two tables have moved likewise. The point is not
that drift is wrong — the tree has legitimately advanced — but that **a number
taken today could not be compared with the pinned row**, because the delta would
be un-attributable between instrument drift and the package move. Two changes,
one reading, no way to tell them apart.

### Why it was not fixed inside this window

Building a package-pointed heap instrument is a **new instrument**, and it needs
a new build id in `implementation/shadow-cljs.edn` — a hot-zone file. Both are
out of bounds here:

- The window's own terms forbid improving the rig mid-measurement: *"no new
  instrument, no extra rungs, no third estimator, however obvious. File the
  improvement and run it as its own window."* Half the numbers coming from one
  instrument and half from another, with nothing in the record saying which, is
  the failure mode that rule exists to prevent.
- The hot-zone edit is the operator's to sequence, and was not requested in
  advance.
- `rf2-hic-018` already owns `implementation/hicasso/test/.../substrate_*` as
  **new benches** on its surface line. The package-resident heap instrument is
  most naturally that bead's, or a new one sequenced with it — not this one's.

**What it would take**, stated so the next window can be dispatched without
rediscovering it: a `:hicasso-heap-bench` build id whose candidate arm requires
`re-frame.hicasso` instead of `re-frame.bench.hicasso.arm1`, the existing
`p0_*` driver and fixtures reused unchanged so the estimator does not move, and
one quiet-machine run taking the package arm and both donor arms **in the same
run** — because the two harnesses differ by a measured ~5% common-mode offset
and no figure may be scaled from one onto the other.

---

## 7. Where each row is enforced

| Family | Enforcement home |
|---|---|
| Deterministic rows D1–D16 | ordinary blocking PR gates; framework in `rf2-hic-089`, full gates in `rf2-hic-071` |
| Distributional rows S1–S7, U1–U4 | pinned interleaved evidence runs on P-DEV-1; never converted into flaky PR thresholds |
| Shell breach disposition | `rf2-hic-018` |
| K3 per-read record | `rf2-hic-070` — [`k3-disposition.md`](k3-disposition.md), whose §8 makes the 10% same-witness per-read rule executable for `rf2-hic-071` |

Row by row, with each verdict beside its line, that table is
[§9's ledger](budgets.md#9-the-budget-line-reconciliation-ledger). The routing
above is what the ledger's own gate enforces: a deterministic row must name a
witness a pull request runs, and a distributional row must not.

---

## 8. This page's own run, and what would falsify it

The deterministic rows were confirmed at `0c0aa22898` on P-DEV-1 by the browser
lane, run alone on a quiet box:

| Run | Command | Captured exit | Result |
|---|---|---:|---|
| 1 | `npm run test:browser` | `0` | 1,284 tests, 7,971 assertions, 0 failures, 0 errors |
| 2 | same, with D1/D2's acceptance inverted to `3` | `1` | captured red naming the line — see below |
| 3 | same, assertion restored | `0` | restored byte-exact; file hash matches pre-sabotage |

**Run 2 is the sabotage control, and it is why run 1 means anything.** The
witness rows are `browser?`-guarded and degrade to stated skips off the browser
lane, so a green aggregate alone would not distinguish *ran and passed* from
*skipped*. Inverting the acceptance produced a captured failure naming the
assertion:

```
FAIL in (a-keystroke-costs-the-same-at-25-cells-and-at-100)
  (re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs:198:11)
expected: (= 3 at-25 at-100)
  actual: (not (= 3 2 2))
```

That report does two jobs at once, and the second is the more useful. It proves
the row **executes** rather than skipping — a skipped row cannot fail. And
because `cljs.test` prints the bound values, `(not (= 3 2 2))` **independently
witnesses D1 = 2 and D2 = 2** from the failure path, without the passing
assertion being the thing that reports them. The figures and the proof they
were measured are therefore not the same observation.

Run 3 restored the assertion and re-ran: the file's SHA-256 matches its
pre-sabotage value byte-for-byte, and the lane returned to 1,284 tests and
7,971 assertions with zero failures — the same totals as run 1, on bytes proven
identical to run 1's.

**What would falsify this page.** Any of: a second physical profile showing the
user-visible budgets behave differently in tier (§1's accepted limitation is
the standing invitation); a package-resident heap instrument producing shell or
per-read figures materially apart from S1–S4 (§6 says how to build it); the
operator freezing the byte line somewhere other than the §5 recommendation; or
any deterministic row D1–D16 moving without a topology change to explain it.

**On the scope of the run above.** The table records the confirmation this page
took at `0c0aa22898`, which is the browser lane and therefore D1–D9. D10–D13 and
D14–D16 landed later and run in the node lane, each with its own witness named in
§9's ledger and run by every pull request; the falsification clause is stated over
all sixteen because the claim it makes — a deterministic row does not move without
a topology change — is the same claim whichever lane decides it.

---

## 9. The budget-line reconciliation ledger

Every line this page registers has a verdict somewhere above, and above is
eight sections long. A page whose verdicts are spread that far can carry a
breach nobody has to look at — not by hiding it, but by never putting it next
to the rows that are fine. This section is the one place each registered line
states its own verdict, and
[`check_budget_ledger.py`][gate] is what stops the ledger drifting from the
sections it summarises.

[gate]: ../../../../implementation/hicasso/scripts/check_budget_ledger.py

**The gate does not enforce the budgets, and cannot.** Two thirds of the rows
below are distributional, and §1 has already said a hosted runner may never
source a distributional figure. What the gate enforces is that the *record*
tells the truth: every registered line has a row; a row that is not green names
who owns it and where it was dispositioned; a confidence band that crosses its
line reads `UNRESOLVED` rather than green; and no distributional row is quietly
routed into a pull-request threshold. It can tell you a breach has gone
unrecorded. It cannot tell you a budget is met.

### 9.1 How to read a row

**Status is four-valued, and exactly one value is a pass.** The fourth column
is the point of the whole table, so it uses a closed vocabulary rather than
prose:

| Status | What the evidence did | A pass? |
|---|---|---|
| `MET` | decided the row on the meeting side of its registered line | **yes** |
| `BREACH` | decided it on the failing side | no — carried, red, with a named disposition |
| `UNRESOLVED` | did **not** decide it: a confidence band that crosses the line, or a comparison whose two arms are not the same witness | **no, and never silently folded into a pass** |
| `UNPINNED` | never reached the row — no instrument for it exists on the governed population | no; there is nothing yet to decide |

`UNRESOLVED` is the status the operator's 2026-08-12 ruling exists to make
sayable. §5 froze the shell line at `1,024 B` *and* adopted the adjudication
rule that a band crossing it is unresolved rather than a pass; a ledger with
only pass and fail would have had to round that ruling to one or the other, and
whichever it chose would have been a fabrication. Where a row carries a
machine-readable byte ceiling and a machine-readable band, the gate recomputes
the verdict from those two numbers and refuses a status that disagrees — so
`MET` cannot be written over a crossing band by hand.

**A `BREACH` row does not redden the gate; an unrecorded one does.** The
distinction is the whole design. §5 says the shell breach is carried and never
normalised, and [the substrate decision](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition)
carried it red deliberately after refusing substrate remediation on the
evidence. A gate that failed the build on that would be demanding a fix the
programme has already ruled against; a gate that quietly greened it would be
the normalisation §5 forbids. So the gate asks the only question it is entitled
to ask — *is this breach on the record, with an owner and a disposition that
resolves?* — and reds when the answer is no.

**The other cells.** *Population* is `package`, `bench-tree` or `—`, and it is
pinned per row in the gate: `rf2-fe0l` made S1–S5 package figures and left S6,
S7 and U1–U4 where they were, so a later edit cannot quietly promote a
bench-tree figure by rewriting a cell. *Instrument* names the thing that took
the reading and, in parentheses, the lane it runs in — `PR gate`,
`P-DEV-1 evidence run`, or `none`. A deterministic row must name a witness file
that exists and runs in the first lane; a distributional row may never name the
first lane at all. *Authority* is the bead that owns the row's disposition
today. *Disposition* is a link that must resolve to a section naming the row.

<!-- rf2-hic-089: ledger -->

| # | Registered line | Current value | Population | Status | Instrument (lane) | Authority | Disposition |
|---|---|---|---|---|---|---|---|
| D1 | 2 bodies per keystroke at 5×5, and equal to D2 | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D2 | 2 bodies per keystroke at 10×10, and equal to D1 | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D3 | 26 bodies, coarse shape at 5×5 | 26 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D4 | 101 bodies, coarse shape at 10×10 | 101 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D5 | 0 bodies for a refused keystroke | 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D6 | 11 bodies for `clear-row` on a 10-wide row | 11 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D7 | 2 bodies, first keystroke of an editor session | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/editor/flow_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D8 | 1 body, every keystroke after the first | 1 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/editor/flow_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| D9 | zero teardown residue in counters and frame ids | zero | package | `MET` | `implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs` (PR gate) | `rf2-hic-089` | — |
| D10 | 3 hiccup-walk entries dropped by a direct return (`codec/vec->element`) | 3 — the hiccup arm reads 4 against the crossing's floor of 1 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D11 | 3 prop-pipeline entries dropped by a direct return (`codec/convert-props`) | 3 — the hiccup arm reads 3 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D12 | 2 event-lowering entries dropped by a direct return (`intent/lower-prop`) | 2 — the hiccup arm reads 2 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D13 | 3 controlled-repair entries dropped by a direct return (`controlled/install!`) | 3 — the hiccup arm reads 3 against a floor of 0 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs` (PR gate) | `rf2-hic-033` | — |
| D14 | 0 wrappers between the element type React reconciles and the author's own function, declared island | 0 — the type is `identical?` to the function, on the native and handwritten-React routes alike; UIx's is a generated component | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| D15 | 1 slot on the props object React carries, and it is the author's own | 1 — `label`, the name the call site wrote; UIx also reads 1, and it is the `argv` carrier | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| D16 | 0 unwrapping hops per render, per component, native and handwritten React | 0 — the UIx route reads 1, opening `argv` | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` (PR gate) | `rf2-hic-034` | — |
| S1 | 1,024 B, R=0 shell, Reagent segment | 1,100 B [1,091–1,107] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-018` | [substrate-decision §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition) |
| S2 | 1,024 B, R=0 shell, UIx segment | 1,095 B [1,087–1,101] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-018` | [substrate-decision §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition) |
| S3 | ≤ 10% regression on the same pinned witness | 1,417 vs Reagent 948 per read | package | `UNRESOLVED` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-070` | [substrate-decision §6](substrate-decision.md#6-what-this-page-does-not-decide) |
| S4 | ≤ 10% regression on the same pinned witness | 2,115 vs UIx 2,980 per read | package | `MET` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-070` | — |
| S5 | teardown retained indistinguishable from 0 | indistinguishable from 0; all ten rungs' bands straddle it | package | `MET` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-089` | — |
| S6 | 1.10x cold mount against direct UIx-on-subs | 1.1718x [1.1263–1.2190] n=8; 1.1976x [1.1504–1.2468] n=6 | bench-tree | `BREACH` | final K1 estimator (P-DEV-1 evidence run) | `rf2-hic-018` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| S7 | warm allocation, a fitted series clearing the quality floor | no publishable claim | bench-tree | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U1 | echo within one 60 Hz frame at p95 | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U2 | ≤ 50 ms p95 and ≤ 100 ms p99 to next paint | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U3 | ≤ 100 ms p95 for broad operations | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U4 | dragging and animation inside the frame budget | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| U5 | body work scales with changed rows, not mounted rows | 2 at 25 cells and at 100 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/examples/grid/scaling_dom_cljs_test.cljs` (PR gate) | `rf2-hic-089` | — |
| U6 | teardown residue zero after quiescence | zero counters (D9); bytes indistinguishable from 0 (S5) | package | `MET` | `implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs` (PR gate) | `rf2-hic-089` | — |
| C1 | ≤ 5% regression on the same witness and instrument | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C2 | 1.10x cold mount, the registered line | see S6 | bench-tree | `BREACH` | final K1 estimator (P-DEV-1 evidence run) | `rf2-hic-018` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C3 | ≤ 1.25x the best relevant adapter on broad updates | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C4 | no sustained 1.5x as ordinary Hicasso | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C5 | 1,024 B byte-exact, not governed by baseline-plus-10% | 1,100 B / 1,095 B [1,087–1,107] | package | `BREACH` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-018` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C6 | ≤ 10% per-read regression on the same pinned witness | see S3 / S4 | package | `UNRESOLVED` | P0 heap ladder, package candidate arm (P-DEV-1 evidence run) | `rf2-hic-070` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C7 | a native island within 5% or 1 ms of the same component mounted directly | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| C8 | an escape recovers ≥ 20%, saves ≥ 2 ms p95, or flips a failed budget | — | — | `UNPINNED` | — (none) | `rf2-hic-071` | [§9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) |
| I9 | ≤ 2 React hooks per boundary shell, invariant in read count | 2 | package | `MET` | `implementation/hicasso/test/re_frame/hicasso/hook_budget_cljs_test.cljs` (PR gate) | `rf2-hic-018` | — |

<!-- rf2-hic-089: end-ledger -->

Thirty-eight rows: the sixteen deterministic figures of §3, the seven distributional
rows and six user-visible budgets of §4, the eight comparative rules §4 now
gives ids to, and one row registered off this page — **I9**, the two-hook
ceiling frozen by
[the substrate decision](substrate-decision.md#4-the-two-hook-ceiling-frozen-with-its-measurement).
I9 is here because it is a budget with a package-resident witness and no other
ledger; its provenance is held in the gate rather than assumed.

**U6 is the split row, and the ledger shows only one half of it.** Its
deterministic half is D9's counters and its distributional half is S5's bytes,
and §3 has already said the two are not interchangeable evidence. The
instrument cell names the deterministic witness because that is the half a pull
request runs; the other half is S5's own row, one line above.

### 9.2 What each not-green row is waiting on

Three rows have a disposition record of their own and point at it: `S1` and
`S2` at [the substrate decision's §5.2](substrate-decision.md#52-the-read-free-boundary-shell--the-disposition),
where the shell breach was carried red on the evidence, and `S3` at
[its §6](substrate-decision.md#6-what-this-page-does-not-decide), where the
`+139 B/read` package move is now attributed to one ratom-only correctness line
(`rf2-l50z`) — an attribution, not a change to the figure, so `S3` is still
`UNRESOLVED` against its own rule. Every other row that is not `MET`
points here, and here is what each is waiting on.

- **No package-resident clock instrument exists.** `U1`, `U2`, `U3` and `U4`
  are latency budgets, and §4 says in terms that they cannot be pinned until
  such an instrument exists. `C3` and `C4` are the comparative rules stated on
  the same missing readings. None of the six can be pinned by measuring harder
  on the rig that exists; each needs a clock instrument pointed at
  `implementation/hicasso`, on P-DEV-1, in its own window.
- **The 5% rule has no same-instrument anchor.** `C1` compares a reading
  against the pinned ordinary-Hicasso benchmark, and §6 records that the
  registered instrument's eleven pinned blobs are superseded rather than
  repaired — one of its files no longer exists. Until the ladder is re-pinned,
  "the same instrument" names nothing, and a comparison against it would be
  un-attributable in exactly the way §6 describes. The re-pin is `rf2-hic-071`'s
  along with the gate it enables.
- **The cold-mount line is missed and its replacement is unratified.** `S6` and
  `C2` are red against the registered `1.10x`. The `1.25x` figure is a proposal
  pending the operator's sitting, so it is not written in either row's line
  cell, and the gate refuses to let it be: §4's standing prohibition — no
  evidence row may use the proposal to mark K1 green — is held as a pin on
  those two rows rather than as a sentence nobody re-reads.
- **`S7` has no publishable claim to pin.** No fitted allocation series clears
  the quality floor. This is a property of the readings rather than of the
  rig, so it is `UNPINNED` rather than `UNRESOLVED`: nothing crossed a line,
  because nothing reached one.
- **`C8` has no population yet; `C7` now has one.** The native-island rule
  and the escape-benefit rule are both stated over landed escapes and islands,
  and the apps that would carry them are `rf2-hic-034`, `rf2-hic-047` and
  `rf2-hic-045`. `rf2-hic-034` has landed, bringing `C7` its population and the
  deterministic half of its band — D14–D16 above, the structural question a
  hosted runner is allowed to decide. `C7` stays `UNPINNED` for the other half
  only: no package-resident clock instrument exists to take the reading, the 5%
  rule has no same-instrument anchor until the ladder is re-pinned, and §7
  forbids converting a distributional row into a pull-request threshold in any
  case. All three are `rf2-hic-071`'s, which names these beads as the ones it
  extends over — the same statement from the other side.
- **`C5` and `C6` are rules whose readings have been dispositioned, and the
  rules have not.** `C5` is the shell rule; its evidence is `S1` and `S2`,
  carried red by the substrate decision's §5.2. `C6` is the per-read rule; its
  evidence is `S3` and `S4`, and that page's §6 leaves the `S3` move open to
  `rf2-hic-070`. Neither section adjudicates the *rule*, only the readings
  under it, so the two rows point here rather than borrowing a disposition
  that was not made about them. `C5` is settled the day a shell arm lands
  under `1,024 B` on the package; `C6` the day the K3 record is taken.

### 9.3 Where this ledger stops and rf2-hic-071 begins

This bead is the *early* framework: the half that can exist before the
population does. The boundary is not a matter of taste, and it is worth stating
as a rule rather than as a list.

**What can be gated early is what a hosted runner is allowed to decide.** §1
permits CI-RUNNER-A correctness gates and same-run relative drift, and forbids
it any distributional product budget; §2 sorts every row into the two families;
§7 routes them. So the early framework is *the deterministic family plus the
record*: the D rows and I9 already run as ordinary blocking witnesses in a pull
request, and this section adds the ledger and the gate that keeps it honest.
Everything the ledger says about a distributional row is a transcription of an
evidence run, checked for internal honesty and nothing more.

**Two of this bead's stated deliverables cannot be built early, and the reason
is on this page.** They are recorded here rather than quietly dropped:

- *The 5% same-instrument regression gate, running in CI per relevant PR.* The
  pinned ordinary-Hicasso benchmark is a heap figure — distributional, P-DEV-1
  only, and §7 says such a row is never converted into a flaky PR threshold.
  Wiring it to a hosted runner would breach §1 on the first green. It is `C1`
  in the ledger, `UNPINNED`, and it additionally has no anchor to compare
  against until §6's instrument drift is re-pinned. The gate below enforces
  that no one wires it there by mistake: a distributional row may not name the
  `PR gate` lane.
- *Slice-app user-visible gates at 50 ms p95 / 100 ms p99 and one-frame
  keystroke echo.* These are `U1`–`U4`, and §4 records that no package-resident
  clock instrument exists to pin them on. The deterministic half that *is*
  reachable — a controlled update landing same-turn, and the per-keystroke body
  count behind the echo claim — is already gated, as `U5`, `D7` and `D8`.
  Building a second instrument to say more would be building a second thing to
  drift, which is the rule §3 states and `rf2-fe0l` was dispatched to honour.

**`rf2-hic-071` therefore inherits three things, not one**: the clock
instrument and the U-row gates it makes possible, the ladder re-pin and the 5%
comparison it makes meaningful, and the escape-benefit rule over the escapes
its own dependencies land. This ledger is what it will report into — the bead
owns the status columns from the moment it takes them.
