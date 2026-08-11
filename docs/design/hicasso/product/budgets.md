# Hicasso budgets: named reference profiles and pinned baselines

This page prepares [specification §6](specification.md#6-performance-contract)
for ratification. It does not ratify anything. **Ratification authority is the
re-frame2 product operator's (Mike Thompson)**; this document records the
profiles a budget is stated *on*, the estimand/instrument/control standard each
row must meet, and the state of every baseline the later gates will anchor to.
Enforcement lives in [`rf2-hic-089`](#7-where-each-row-is-enforced) (the early
framework) and `rf2-hic-071` (the full gates), never here.

Three things are deliberately *not* decided on this page, because each is
another bead's to decide:

| Question | Owner |
|---|---|
| The R=0 shell's breach of its paper-fail line — remediate, or disposition it | `rf2-hic-018` |
| The per-read K3 record and its three non-substitutable scoreboards | `rf2-hic-070` |
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
| Anchored on `implementation/hicasso` today? | **yes** | **no** — see §6 |

The practical consequence, and the reason it is worth naming: **the
deterministic family did not need this measurement window at all.** A body
count is a delta on a monotone integer counter; contention cannot move it. Only
the distributional family needs the quiet box — and that is exactly the family
this page could not re-pin.

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

**On D9.** This is teardown residue in **counters and objects**, which is a
deterministic reading the package can make. It is *not* the evidence
baseline's teardown row, which is stated in **retained bytes** on the pinned
heap arms — a distributional figure, and one of the three this page could not
re-pin. The two must not be conflated: a mount can leave zero residue counters
and still retain bytes.

---

## 4. Distributional rows, carried forward and NOT re-pinned

Every figure here is carried unchanged from
[the evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence).
None was re-measured on `implementation/hicasso`, for the reason in §6.

| # | Estimand | Pinned figure | Instrument | Status |
|---|---|---|---|---|
| S1 | R=0 boundary shell, Reagent segment | `1,103 B` | P0 bench ladder, bench-tree candidate arm | not re-pinned on the package |
| S2 | R=0 boundary shell, UIx segment | `1,097 B` | as S1 | not re-pinned on the package |
| S3 | Per-read retained, Hicasso vs Reagent | `1,278` vs `947 B/read` | as S1 | K3 scoreboard (a) — owned by `rf2-hic-070` |
| S4 | Per-read retained, Hicasso vs UIx | `2,115` vs `2,980 B/read` | as S1 | K3 scoreboard (b) — owned by `rf2-hic-070` |
| S5 | Teardown, retained **bytes** | zero on the pinned heap arms | as S1 | not re-pinned on the package |
| S6 | Cold mount vs direct UIx-on-subs | `1.1718x` [1.1263–1.2190] n=8; `1.1976x` [1.1504–1.2468] n=6 | final K1 estimator | registered `1.10x` gate missed; `1.25x` is a *proposal* only |
| S7 | Warm allocation | no publishable claim | allocation instrument | no fitted series clears the quality floor |

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

| Rule | Disposition |
|---|---|
| Pinned ordinary-Hicasso benchmark does not regress > 5% on same witness and instrument | same-instrument regression **blocks** until the benchmark owner validates the instrument and the adapter owner fixes or reverts |
| Cold mount ≤ 1.25x direct UIx (subject to ratification) | proposal only; `1.10x` registered line stands |
| Broad updates ≤ 1.25x best relevant adapter after topology tuning | 1.25–1.5x is a **warning band**: attribute cause, one bounded topology pass, test a local island |
| Sustained > 1.5x | cannot graduate as ordinary Hicasso until fixed or deliberately classified a native-host use case |
| R=0 shell meets the frozen byte-exact `1 KB` line | **not** governed by baseline-plus-10%; see §5 |
| Per-read retained ≤ 10% regression on same pinned witness | governed by the K3 disposition (`rf2-hic-070`) |
| Native island within 5% or 1 ms of the same component mounted directly | co-instrumented against both handwritten React and UIx |
| An escape recovers ≥ 20%, saves ≥ 2 ms p95, or converts a failed budget to a pass | an island missing its threshold is simplified or removed — **thresholds do not widen to keep it** |

---

## 5. The read-free boundary shell: the byte-exact line

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

### The breach is carried, never normalised

The current breach stands at **R=0 = 1,103 B / 1,097 B** against the registered
`1 KB` line. It is a **live pressure owned by `rf2-hic-018`**, not a
disposition this page may make, and it is never silently normalised into a
percentage allowance — §6 says in terms that a relative regression allowance
cannot recolour the red shell row.

One drift is recorded rather than smoothed. The baseline's pinned Reagent
figure is `1,103 B`; the ladder's later paired re-takes on the shipping tree
read `1,101 B` and `1,099.5 B`, and the UIx segment reads `1,097 B` in all
of them. The arms overlap in band and the page itself notes the difference. The
baseline row is left as the pinned figure because a measurement record edited to
match a later run stops being a record — but a re-pin on the package (§6) would
supersede all of them, and until it happens **`1,103 B` is a bench-tree figure,
not a package figure.**

---

## 6. The re-pin on the moved package: REFUSED

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
| Deterministic rows D1–D9 | ordinary blocking PR gates; framework in `rf2-hic-089`, full gates in `rf2-hic-071` |
| Distributional rows S1–S7, U1–U4 | pinned interleaved evidence runs on P-DEV-1; never converted into flaky PR thresholds |
| Shell breach disposition | `rf2-hic-018` |
| K3 per-read record | `rf2-hic-070` |

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
any deterministic row D1–D9 moving without a topology change to explain it.
