# Release scans — the native share, the allocation non-claim, and the aggregate rent

Three release-time obligations that [`specification.md`](specification.md) states and no other page owns.
This is `rf2-hic-072`'s record. Each one is a **census**: it asserts a number, and a number is worth nothing
without the command that produced it, so every section below carries the exact invocation beside its result.
`rf2-hic-087` re-runs all three against the final tree, and a figure it cannot re-derive is a figure it has to
take on trust.

**Merge base.** Every count on this page was taken at merge-base commit
`94136dbf7a3eb16f0a2b56a92c69b3c346bf11cf`, on 2026-08-14 22:18 AUSEST. Counts move when the tree moves; the
method does not.

## What this page is written against

[Checkpoint 4](checkpoint-4-coverage.md) returned **NOT MET** on the Phase 4 exit, and nothing here softens
that. Sixteen of twenty coverage rows point at running evidence, four do not, and one conjunct fails on its
own. These scans are release-time obligations rather than exit criteria, so they are answerable while the exit
is not — but two of the three come back with a refusal rather than a figure, and the refusals are the
deliverable rather than a placeholder for one.

**Three of this bead's dependencies are still open**, which bounds what the scans can be. `rf2-hic-060` (API
reference and cookbook), `rf2-hic-068` (troubleshooting and performance method) and `rf2-hic-061` (versioned
artifacts and upgrade policy) all add publication-surface pages, and the allocation non-claim scan is a scan
*of the publication surface*. So its green here is a green over the corpus that exists, and the scan — not this
page — is the thing that carries the obligation forward onto pages not yet written. That is the point of
shipping a scan rather than a snapshot.

## 1. The native-share census

[§6](specification.md#6-performance-contract) states the obligation in one sentence: *"The native-code
percentage is reported as a source/component census after implementation; it is never enforced."* The decision
brief says the same thing more sharply — *"Native-code percentage is **an observed census, never a quota**."*
So what follows is an observation. There is no line to pass and none is drawn.

**What counts as native.** [§5](specification.md#5-native-react-hot-path) grades the escape as five rungs.
Rungs 1 and 2 are ordinary Hicasso — hiccup, ambient reads, boundary and read topology — and are not native.
Rungs 3, 4 and 5 are, and each leaves a mark in the source that a scan can find: a `re-frame.hicasso.native`
require, a `["react" …]` or `[uix.…]` require, an `h/defhost` declaration, or an `h/as-element` /
`h/as-component` crossing. Those six tokens are the census, and they were chosen because each is a
*declaration* rather than a style — you cannot reach React from a Hicasso view without writing one of them.

### Method

```sh
cd implementation/hicasso/test/re_frame/hicasso/examples
SRC=$(ls */*.cljs | grep -v "_test\.cljs$")            # source namespaces; test namespaces excluded

# namespaces carrying a native escape
grep -nE '\[re-frame\.hicasso\.native|\["react"|\["react-dom|\[uix\.' $SRC
grep -nE '\(h/defhost |\(h/as-element|\(h/as-component|\(n/' $SRC

# the component denominator
grep -hcE '^\(h/defview' $SRC | awk '{s+=$1} END{print s}'   # 47
grep -hcE '^\(h/defhost' $SRC | awk '{s+=$1} END{print s}'   # 1
```

**Every hit was read in context before it was counted, and that step is not optional.** The unanchored form of
the same grep returns nine hits; five are prose inside a namespace docstring and two are the string `"react"`
in a `:tags` vector of example data. A symbol-shaped grep would have published `9` here. The anchored greps
above return four, of which one — `ledger/views.cljs:14` — is still docstring, quoting the screen's own
crossing inside the namespace comment. Three are code.

### The census

| §7 cohort | App | Source ns | ns with a native escape | Components | Native components |
|---|---|---|---|---|---|
| slice | `examples/slice` | 7 | 0 | 13 | 0 |
| editor/grid | `examples/editor` | 4 | 0 | 6 | 0 |
| editor/grid | `examples/grid` | 4 | 0 | 4 | 0 |
| vendor | `examples/ledger` | 5 | **2** | 5 | **2** |
| — | `examples/forms` | 4 | 0 | 7 | 0 |
| — | `examples/navigation` | 4 | 0 | 4 | 0 |
| — | `examples/todo` | 6 | 0 | 5 | 0 |
| — | `examples/typeahead` | 6 | 0 | 5 | 0 |
| **total** | eight applications | **40** | **2** | **49** | **2** |

**The observed native share is 2 of 40 source namespaces (5.0%) and 2 of 49 components (4.1%), and all of it
is in one application.** The three cohorts the bead names read: slice **0%**, editor/grid **0%**, vendor
**40% of namespaces and 40% of components**.

The two native namespaces are both `examples/ledger`, which is the serious-vendor screen `rf2-hic-047` shipped
and whose own namespace docstring says so. They are `ledger/vendor.cljs`, which requires `["react" :as react]`
and defines one React function component (`virtual-rows`, using `useRef`, `useState`, `useEffect` and
`createElement`), and `ledger/views.cljs`, which declares the crossing with `h/defhost` and hands a view back
across it with `h/as-element`. The two native components are that raw React component and the `h/defhost` door
onto it.

**Zero of the forty source namespaces require `re-frame.hicasso.native`.** The tier's own namespace docstring
predicts this — *"Most applications never require this namespace"* — and the shipped corpus does not contradict
it. The tier is exercised by its own witness suites, the benches and the testbed, which are not applications
and are not counted here.

**One independent corroboration, found rather than sought.**
[`budgets.md`](budgets.md#9-the-budget-line-reconciliation-ledger) states, for a different purpose
entirely, that *"The one `h/as-element` call in the shipped example applications is exactly that one —
`examples/ledger/views.cljs`"*. That is the same population arrived at by a different route, and it agrees.

**Read this as a census and not as a result.** A native share near zero is not evidence that the escape is
unnecessary; it is evidence about what eight example applications happened to need, and the corpus weakly
represents refs, portals, foreign observers and complex React integrations by its own admission
([evidence scope](lanes/use-cases.md#evidence-scope)). Rarity in one repository is not proof that a job does
not matter. The number is reported because §6 asks for it, and it is enforced against nothing.

## 2. The warm-allocation non-claim

[§6](specification.md#6-performance-contract) again, and this time the sentence is a prohibition: *"Warm-allocation
evidence is not a release gate: no allocation claim publishes until a fitted series clears the registered
quality floor."* [§13](specification.md#13-definition-of-done) repeats it as a definition-of-done clause —
*"Warm allocation is not a release blocker and carries no product claim unless its instrument qualifies."*

**No fitted series clears the floor, and this page publishes no allocation figure as a product claim.** That is
the whole of the deliverable. It is not a gap, it is not pending measurement, and it is not a number withheld
until a better rig is free. It is a measured result with three independent legs, each recorded on its own bead:

- **`rf2-2rtt6.140`** measured, for the first time on this rig, a **fixed** per-write cost of about **24.4 KB**
  (24,108 B on `reagent-subs`, 24,730 B on `uix-subs`) which does not shrink as the page shrinks. Against the
  masking bound's 42,857 B per write at the six-write averaging floor, that fixed term alone is 57% of the
  budget before a single boundary has been measured. Its conclusion is stated in terms: at six writes there is
  no page of one boundary or more that certifies the 1/3/7/20 ladder.
- **`rf2-2rtt6.139`** retired the sizing constant `ALLOC_B_PER_BOUNDARY_WRITE = 1655`, because it was derived
  from a window the instrument itself refused. It is therefore a lower bound taken from an invalid
  measurement, and it is the single most available wrong answer on this page's subject.
- **`rf2-e9wr`** found that tau cannot be pinned at all, because the controls are not a valid calibration
  population for the arms: the controls' work unit has no first-write re-allocation and the arms' has one, in
  **336 of 336** measured windows.

The tree already records the non-claim in four places and they agree —
[`budgets.md` §9](budgets.md#9-the-budget-line-reconciliation-ledger)'s `S7` row (*no publishable claim*,
`UNPINNED` rather than `UNRESOLVED`, because nothing crossed a line since nothing reached one),
[`evidence-baseline.md`](lanes/evidence-baseline.md)'s Warm allocation row (*Publish no allocation claim
yet*), [`decision-brief.md`](decision-brief.md), and §6 itself. What was missing was anything that would
*notice* a future page contradicting them. That is the scan.

### The scan

`scripts/check_allocation_non_claim.py`, new with this bead.

```sh
python scripts/check_allocation_non_claim.py --self-test   # 5 fixtures
python scripts/check_allocation_non_claim.py
```

It is built on three decisions, each of which could have gone the other way:

**A claim is a FIGURE, not a word.** The scan fires on a number bound to a per-write byte unit —
`2,031 B/boundary/write`, `24.4 KB per write`, `1655 bytes per write`. That denominator *is* the
warm-allocation estimand and nothing else in this repository carries it: retained heap is measured per read or
per boundary and never per write. Scanning for the word *allocation* was rejected, because the word is
unavoidable in prose about an instrument that measures allocation, and a gate firing on it is a gate authors
route around by rewording within a week. The figure is the part that cannot be reworded.

**The corpus is the publication surface, and the design record is the positive control.** A figure in
`README.md`, `CHANGELOG.md` or any `docs/**/*.md` outside `docs/design/` is what a consumer reads, so it is a
claim. A figure in `docs/design/` is the instrument's own record of what it measured and refused — it is the
evidence *for* the non-claim, and a scan that silenced it would delete the evidence in the name of enforcing
the conclusion. So the design record is not a scanned row; it is the control. The same pattern that must match
**nothing** on the publication surface must match **something** there, or the pattern has rotted and a green
absence means nothing. It currently fires in 5 design-record files.

**The rule is *no claim without its qualification*, not *no figure*.** A paragraph that states the number and
then says the instrument does not certify it has published the refusal, which is the truthful thing to
publish; the scan passes it. Today no such paragraph exists on the publication surface and the row is empty,
which is a stronger state than the one being enforced. The seeded fixture proving the qualified form passes is
deliberately in the self-test beside the one proving the unqualified form fails, because a gate that banned
the digits would push authors into vagueness rather than into honesty.

**The scan is designed to be retired by its own premise check.** Before it scans anything it reads the two
rows that record the floor's state — `budgets.md`'s `S7` and `evidence-baseline.md`'s Warm allocation row. If
either stops saying the floor is unmet, the scan **fails** and says so, because the rule it enforces has lost
its justification and a guard that outlives its reason is worse than no guard. When a fitted series finally
clears the floor, this scan is deleted rather than repaired.

### Result

| Row | Reading |
|---|---|
| Premise — `budgets.md` `S7` reads *no publishable claim* | holds |
| Premise — `evidence-baseline.md` reads *No fitted series clears the registered quality floor* | holds |
| Positive control — the claim pattern fires in the design record | 5 files |
| **Publication surface — unqualified allocation claims** | **0**, over 235 files |
| Seeded violation demonstrated red | yes — see below |

Exit **0**. The seeded violation is demonstrated twice: by the self-test's own fixture, and against the real
tree by planting `1,655 B/boundary/write` — deliberately the retired constant `rf2-2rtt6.139` refused — into a
publication-surface page, which the scan reds by file, line and figure. The plant was removed and the working
tree verified clean.

## 3. The aggregate rent check

The obligation has two halves and they are answered differently, so they are separated here rather than scored
together.

### 3a. Zero rent for the optional surfaces

The [native-boundary law](lanes/design-laws.md#native-boundary)'s clause 6 requires that *an interpreted-only
production dependency graph and bundle contain neither native-tier runtime nor UIx code*, and
[`invariants.md`](invariants.md) states the parallel clause for optional libraries: *zero reachable production
code when absent*. Two instruments answer it, and — as `check_bundle_isolation.cjs`'s own docstring says —
neither can answer the other's question. The dependency graph is a property of the source; the bundle is a
property of the compiler and the linker.

**This is the census, and it is a coverage matrix rather than a pass.**

| Optional surface | Source-side gate (`check_optional_module_reachability.py`) | Bundle-side gate (`check_bundle_isolation.cjs`) |
|---|---|---|
| native tier | door | 2 sentinels — the tier marker and the refusal-id family |
| forms (`rf2-sh56`) | door | 2 sentinels — the view name and the app-db concern |
| motion | door + 2 engine namespaces | **none** |
| overlay (`rf2-hic-052`) | door + 1 engine namespace | **none** |
| UIx | forbidden-import rule | carried as a positive control (present), by design |

**Four of four landed optional surfaces are covered source-side; two of four are covered bundle-side.** Motion
and overlay have no sentinel row. This is not an inference from silence: `re-frame.hicasso.motion/presence`
appears in `check_bundle_isolation.cjs` exactly once, at line 453, as a *near-miss control string* in the
self-test — a string the gate is asserted **not** to fire on. So the file knows the module exists and has
chosen not to scan for it. The consequence is narrow and worth stating precisely: motion and overlay are proved
unreachable in the source graph, which is the stronger of the two instruments, but nothing checks that Closure
really leaves nothing behind for them at `:advanced`, which is the confidence the bundle gate exists to add.
Filed as **`rf2-ot28g`**.

### Method

```sh
cd implementation
python hicasso/scripts/check_optional_module_reachability.py --self-test   # exit 0
python hicasso/scripts/check_optional_module_reachability.py              # exit 0
python hicasso/scripts/check_budget_ledger.py --self-test                 # exit 0
python hicasso/scripts/check_budget_ledger.py                             # exit 0

# the two rosters, read at source rather than inferred
grep -n '"name":' hicasso/scripts/check_optional_module_reachability.py   # 5 entries
grep -n 'surface:' hicasso/scripts/check_bundle_isolation.cjs             # 4 entries, 2 surfaces
```

The source-side gate is green at this merge base: *motion, overlay, native, forms unreachable from the public
door; UIx required by no `src/` namespace and named by no production coordinate*.

**The bundle-side gate was NOT re-run here, and the reason is a precondition rather than a cost.** The bead
asks for this check *"with all Phase 5 optional products landed"*, and they are not all landed —
`re-frame.hicasso.server` is in flight and reaches neither roster above, so a bundle run taken now would not
be the aggregate the obligation names. The run costs a full `:advanced` release build (`npm run
build:hicasso-release`), and the honest reading is that it belongs after the server module lands and gains its
rows, not before. Recorded here rather than quietly skipped; `rf2-hic-087` inherits it.

### 3b. The boundary shell against the frozen line

[§6](specification.md#6-performance-contract) and [§13](specification.md#13-definition-of-done) both require
that the read-free boundary shell *"meets the operative byte-exact `1 KB` line or has a separately ratified,
prospective operator disposition; a relative regression budget cannot substitute for it."* The line is frozen
at **1,024 B**.

`rf2-hic-018` adjudicated it and **refused remediation on the evidence**: the two shells are five bytes apart
(1,100 and 1,095 B) because the shell touches no adapter, so no substrate choice moves an `R=0` boundary. The
breach was carried red and the line was not re-registered.

**That disposition still holds at this merge base, and it has not been normalized.** Re-read from
[`budgets.md` §9](budgets.md#9-the-budget-line-reconciliation-ledger):

| Row | Line | Reading | Status |
|---|---|---|---|
| `S1` — `R=0` shell, Reagent segment | 1,024 B | 1,100 B [1,091–1,107] | `BREACH` |
| `S2` — `R=0` shell, UIx segment | 1,024 B | 1,095 B [1,087–1,101] | `BREACH` |
| `C5` — byte-exact, not governed by baseline-plus-10% | 1,024 B | 1,100 B / 1,095 B [1,087–1,107] | `BREACH` |

A scoped acceptance dated 2026-08-13 *prices* the breach (accepted to 1,107 B and 1,101 B respectively). It
does not pass it: the ceiling is unchanged at 1,024 B and all three rows stay `BREACH`, which budgets.md states
in its own words — *"A priced breach is not a normalised one"*. The ledger gate re-run at this merge base
returns exit **0** over **49 rows — 31 MET, 5 BREACH, 3 UNRESOLVED, 10 UNPINNED**, the same distribution
[Checkpoint 4](checkpoint-4-coverage.md) recorded, and three of those five breaches are these rows. So the
answer to *"still holds or escalated"* is **still holds**, and there is nothing to escalate that is not already
red and named.

**No fresh measurement was taken, and none was permissible.** The shell figures are retained-heap readings from
the pinned P0 ladder run, which is the measurement lane. That lane's quiet-box window is conditional on an
empty dispatchable queue and an uncontended machine, and neither condition held. The figures above are cited as
the record reads them, not re-measured. Converting a counter into a reading, or publishing a heap figure taken
on a loud machine, would be the same substitution this programme has now refused five times.

## 4. What these scans do not say

- **They are not a Phase 4 or Phase 5 exit.** [Checkpoint 4](checkpoint-4-coverage.md) is NOT MET and
  `rf2-hic-064` owns the §13 audit. Two green scans and a documented refusal are three release obligations
  discharged, which is a smaller sentence than it may look.
- **The native share is not a quota and not a verdict on the escape.** §6 forbids enforcing it and this page
  enforces nothing.
- **The allocation non-claim is not a promise of a future number.** Nobody has measured how much of the fixed
  per-write cost is the vector rebuild and how much is the event pipeline, and until that is measured the
  ladder has no page it can be read on. Whether it ever gets one is not this page's question.
- **The rent check is one instrument short of aggregate**, by its own precondition, and section 3a says which
  one and why.
