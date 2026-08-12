# The collector's substrate — the choice, the two-hook freeze, and the shell disposition

> **Delegated verdict, operator-overturnable.** Discharges `rf2-hic-018`, which
> asks for three things before the runtime ABI freezes: an explicit choice of the
> subscription substrate underneath the collector, the two-hook boundary ceiling
> frozen with its measurement, and a disposition of the read-free shell against
> the byte-exact line `rf2-hic-006` froze. It is written on the pattern
> [`hd-002-adjudication.md`](../hd-002-adjudication.md) established — produced by
> a worker, recorded in the tree, overturnable by the operator — and it uses that
> page's markers, because a reader has to be able to tell what the record decided
> from what this page concluded.

| Marker | Means |
|---|---|
| **[RULED]** | The normative record or the operator says this. Citation follows. |
| **[DERIVED]** | Follows by direct reading of quoted normative text or of source in this repository. Overturn the reading and the conclusion goes. |
| **[INFERRED]** | This page's conclusion from evidence the record does not itself draw. Weakest class — check it before relying on it. |
| **[OPEN]** | Not adjudicable from the current evidence. What would settle it is named. |
| **[SETTLED]** | Was **[OPEN]**; a later bead ran what this page named and settled it. The bead, the ablation and the result are named — including when nothing this page decided changes. |

Governing documents: [specification §3.4](specification.md#34-capability-pays-rent)
and [§6](specification.md#6-performance-contract), the
[boundary-substrate paragraph](lanes/hot-path-architecture.md#boundary-substrate-and-hook-budget),
[`budgets.md`](budgets.md) for every figure, and
[`invariants.md`](invariants.md) for the I9 row this page narrows.

---

## The verdicts, first

1. **The collector stops inheriting its substrate.** Today it rides whichever
   adapter the application installed, and the same collector measures **1,417**
   or **2,115 B per read** on the package depending only on which one that was —
   a 49% spread on the product's own per-read row, set by a line of somebody
   else's `rf/init!`. Hicasso installs its own adapter, and the substrate under
   the collector is a Hicasso fact from the ABI freeze onward.
2. **That substrate is the React-hook spine**, `re-frame.substrate.spine` —
   the body UIx already installs and the one every Hicasso witness has ever run
   on. It is chosen against a measured per-read premium of **+698 B/read** over
   the ratom family, and that premium is carried explicitly rather than
   discovered later.
3. **The shell breach is carried, red, and this page does not re-register the
   line.** The substrate arm is exhausted and it refuses: R=0 reads **1,100** and
   **1,095 B** on the two substrates, five bytes apart, and no substrate choice
   can move it. The line stays at the ruled `1,024 B`, the row stays red, and the
   three routes that could close it are priced below for the operator.

What this page does **not** decide is in [§6](#6-what-this-page-does-not-decide).
The `+139 B/read` move the package re-pin found is no longer on that list: the
ablation §6 named has since been run (`rf2-l50z`), and it attributed the whole
move to a single ratom-only correctness line in `wire-cell!`. **No figure above
moves** — S3 is still `1,417 B/read` — because the attribution names what that
cost buys rather than removing it. What §6 still leaves open is the ratom
family's missing clock and migration contrasts, and whether a Hicasso-owned
derived-value container could beat the spine.

---

## 1. What was actually being chosen

The bead inherits its question from the
[hot-path lane](lanes/hot-path-architecture.md#boundary-substrate-and-hook-budget):
*the underlying collector can currently ride materially different subscription
substrates; select that substrate before the ABI freezes.* Neither that paragraph
nor the specification enumerates the candidates, so the first job is to read them
off the source rather than to assume them.

**[DERIVED] The collector reaches the substrate through exactly one door.**
`re-frame.hicasso.impl.collector/wire-cell!` subscribes through
`re-frame.subs/subscribe`, activates the result through
`re-frame.interop/activate-derived-value!`, watches it, and arms
`interop/add-on-dispose!`; cold reads go through `subs/compute-sub-with-memo` on
the observation port's probe discipline. Every one of those is
substrate-routed, and what they route to is the installed adapter's
`make-derived-value`.

**[DERIVED] There are two implementations of that function in the reference, and
they are materially different.**

| Family | `make-derived-value` | Installed by | Ships in |
|---|---|---|---|
| ratom | `reagent.ratom/make-reaction` | `re-frame.adapter.reagent`, `re-frame.adapter.reagent-slim` | the Reagent adapter artefacts |
| React-hook spine | `re-frame.substrate.spine`'s derived-value container — one watch per source at construction, coalesced through a shared epoch scheduler | `re-frame.adapter.uix` via `spine/make-react-adapter` | **`day8/re-frame2` core** |

A third, `re-frame.substrate.plain-atom`, is **excluded at source and not by this
page**: the package's own `deps.edn` records that plain-atom has no reactivity
layer, so a subscription under it never notifies and every commit assertion would
pass vacuously by never firing. It cannot carry a collector.

**[DERIVED] Hicasso installs neither today.** `implementation/hicasso/deps.edn`
declares core alone and states in terms that shipping source names no adapter;
`impl/mount.cljs` reads the shared `re-frame.adapter.context/frame-context` and
installs nothing. The UIx adapter appears only as a test dependency. So the
substrate under the collector is, literally, whatever the application chose.

**[DERIVED] The product already assumes otherwise.** The specification's native
tier is written around *"the single installed Hicasso adapter, root, and shared
frame contract"* ([§5](specification.md#5-native-react-hot-path)), and the ladder
that measured this rung says the same from the other side: *a shipped Hicasso
would sit on neither donor's substrate — it is an adapter for React and would
install its own*
([the ladder](../studio/reads-per-boundary-heap-ladder.md#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index)).
**The two measured columns are therefore a bracket around a choice, not a menu**,
and the choice is which `make-derived-value` the Hicasso adapter carries.

---

## 2. The evidence, axis by axis — including the axes that are one-sided

The bead names five axes: correctness, retained heap, clock, teardown, migration.
Two of them have a reading for both candidates. Three do not, and **the missing
readings are all missing for the same candidate**, which is what makes the
asymmetry decidable rather than merely awkward.

| Axis | ratom family | React-hook spine | Comparable? |
|---|---|---|---|
| Correctness — kernel witnesses | **none** | every one | **no** |
| Retained heap — R=0 shell | 1,100 B [1,091–1,107] | 1,095 B [1,087–1,101] | yes |
| Retained heap — per read | **1,417 B/read** | 2,115 B/read | yes |
| Clock | **none, on any tree** | measured (bench tree) | **no** |
| Teardown | indistinguishable from zero | indistinguishable from zero | yes |
| Migration cost | zero today | zero today | yes — and that is the point |

### The heap axis, which is the two-sided one

**[RULED]** S1–S4 are package figures as of 2026-08-12, re-pinned by `rf2-fe0l`
in one solo quiet-window run of the P0 ladder repointed at
`implementation/hicasso` ([`budgets.md` §4](budgets.md), and
[the run](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l)).
Both donors, both floors and both shells returned on their published anchors; the
candidate's slope bands are 1–9 B wide over six rounds.

**[DERIVED] The two candidate columns are one view layer over two subscription
substrates, and they are level.** The ladder establishes it and the package run
reproduces the control: the candidate's shell reads within **five bytes** across
the two segments, because the shell touches no adapter, while its per-read slope
reads 1,417 and 2,115. Same arm, same instrument, same six rounds, each figure
taken above its own segment's floor in its own round. The shell agreeing is what
licenses reading the slope difference as the substrate's.

```
per read, on the package, same run
  ratom family        1,417 B/read
  React-hook spine    2,115 B/read
  the spine's premium   +698 B/read   (1.4926×)
```

**[DERIVED] The premium is a core fact, not a Hicasso one.** The same run
measures the donors at 948 B/read (Reagent) against 2,980 B/read (UIx) on the
identical rungs, so the ratom family is cheaper per retained read for *both* view
layers. What the collector does is absorb most of the gap — 2,032 B between the
donors becomes 698 B between the candidate columns — which is the one-hook design
working, seen from an angle the verdict rows do not show.

### The correctness axis, which is one-sided and total

**[DERIVED]** Of the files in `implementation/hicasso`, **73 reference
`re-frame.adapter.uix` and none references `re-frame.adapter.reagent`** — none in
`src`, which names no adapter at all, and none in `test`, `test_kit` or
`testbed`. Every kernel witness this bead depends on — `rf2-hic-010`'s
render-probes/commit-owns suites, `rf2-hic-011`'s read-extent matrix,
`rf2-hic-013`'s reincarnation suites, `rf2-hic-014`'s Activity and Suspense
conduct, `rf2-hic-015`'s HMR rows, and the hook-budget witness below — installs
the UIx adapter and therefore runs on the spine. **The ratom family has never
carried a green Hicasso witness in this package.**

**[DERIVED] That is not a formality, because the collector carries a
ratom-only line.** `wire-cell!` calls `interop/activate-derived-value!` before it
watches or derefs, and `re-frame.interop`'s own docstring says what that op is
for: the React-hook spine wires one watch per source at construction and needs
nothing, while the ratom family is demand-driven and a `Reaction` deref'd outside
`*ratom-context*` leaves `watching` nil — watchable, watched, and notifying
nobody. The repair exists because the arm was measured painting once and going
deaf thereafter (`rf2-2kshh`). **The one line in the runtime that exists solely
for the ratom family is exercised by no test in this package**, because every
test installs the substrate on which it is a routed no-op.

The heap ladder does mount the package under Reagent and answers its structural
read-backs, so the family is not un-run. But a retained-heap reading is taken at
mount: an arm that mounted correctly and then never notified again would produce
the same bytes. **Heap evidence on a substrate is not correctness evidence on it.**

### The clock axis, which does not exist for either candidate as a contrast

**[DERIVED]** [The candidate's clock](../studio/the-candidates-clock.md) is built
as three segments with one substrate arm each, because `install-adapter!` is once
per process and a bulk write re-renders every arm mounted against the frame. The
candidate appears in the `hicasso` segment only, on UIx. **There is no
candidate-on-ratom clock reading on any tree**, and S6/S7 remain bench-tree
figures — no package-resident clock instrument exists
([`budgets.md` §4](budgets.md)).

The clock axis therefore cannot discriminate. What it can do is say which arm has
a reading at all, and that arm is the spine.

### Teardown, and migration

**[RULED]** S5: teardown bytes indistinguishable from zero, all ten candidate
rungs' bands straddling zero, with the structural witness exactly zero after
teardown on every arm of every round — on both segments. The axis does not
discriminate.

**[DERIVED]** Migration cost is zero today for either choice, and that is the
whole reason the bead is timed where it is. `implementation/hicasso/deps.edn`
records the artefact as pre-publication: no Maven coordinate, absent from the
lockstep array and the release deploy matrix. There are no consumers to migrate.
After publication the same change is an adapter swap in every application's
`init!` plus whatever the substrate difference does to their retained cost — which
is why *"decide it explicitly before the runtime ABI freezes"* is the deliverable
rather than *"decide it"*.

---

## 3. The verdict on the substrate

**[RULED — delegated, operator-overturnable] Hicasso installs its own adapter,
and that adapter carries the React-hook spine's derived-value container.**

The reasons, in the order they carry weight:

1. **It is the only candidate with a correctness arm.** Three of the bead's five
   axes are blank for the ratom family, and choosing the spine is the choice
   those blanks *support*: the arm being selected is the arm every witness is
   green on, so the missing evidence is evidence about the road not taken. The
   opposite choice would seat the runtime on a substrate that has never carried
   one of its own witnesses, including the witness for the one runtime line that
   exists for it.
2. **It costs no new dependency and no new substrate code.** The spine ships in
   `day8/re-frame2` core, which the package already declares as its only
   dependency, and `spine/make-react-adapter` is the same one call the UIx
   adapter makes. The spine's own docstring anticipates precisely this consumer:
   *"React-shaped adapters that lack a native reactive-atom primitive (UIx and
   any future minimal-React-wrapper substrate)."* Taking the ratom family instead
   means Hicasso depends on Reagent — in a package whose reason for existing is
   to be a lean React substrate, and in every application that installs it.
3. **The heap premium is real, priced, and the smaller quantity.** +698 B/read is
   not dismissed and is not normalised away; it is [§5](#5-the-carried-costs-and-their-reopen-conditions)'s
   first carried cost, with its reopen condition. What it does not outweigh is a
   correctness arm that exists on one side only and a dependency the product
   spends nowhere else.

**[INFERRED] The bracket's lower end is a demonstrated target, not a rejected
option.** 1,417 B/read is what the collector costs when the reactions underneath
it are cheap, measured on this package on this instrument. A Hicasso-owned
derived-value container that beat the spine would land somewhere in
`1,417 – 2,115`, and the ladder's bracket sentence is what says a view layer
cannot cost less than the reactions it holds. That is a third option this page
does not take, because it does not exist in code and has no measurement — but it
is the shape of the attack, and it is filed as this verdict's reopen condition.

### What this freezes

- The runtime ABI freezes with a **Hicasso-owned** substrate. No shipped
  configuration of Hicasso reads its per-read cost off the application's
  `rf/init!` line.
- The published per-read figure for Hicasso is the **spine** column. S3's
  ratom-segment figure remains a measurement and a K3 scoreboard input
  (`rf2-hic-070` owns that disposition), but it is no longer the number a shipped
  Hicasso would produce.

### Reopen conditions

Any one of these reopens the choice, and none of them is speculative:

- a Hicasso-owned derived-value container measured on this ladder inside the
  `1,417 – 2,115 B/read` bracket, with the kernel witnesses green on it;
- a same-instrument reading that moves the spine's premium materially — the
  premium is a `re-frame.substrate.spine` fact, so a core landing can move it in
  either direction without touching this package;
- a kernel witness that fails on the spine and passes on the ratom family, which
  would invert reason 1 outright.

---

## 4. The two-hook ceiling, frozen with its measurement

**[RULED — delegated] I9 is frozen: an ordinary boundary's shell calls exactly
two React hooks, and the count does not move with the read count.** The two are

1. `useContext(frame-context)` — the frame hook, and
2. `useSyncExternalStore(subscribe, getSnapshot)` — the subscription/epoch hook,

with **no per-instance render-phase state at all**: no `useRef`, no `useState`,
no third cell. That is what makes the budget reachable rather than merely
declared — React gives a function component no per-instance storage except a hook
cell, so a shell wanting one has spent a third hook before it starts, and the
runtime instead makes both closures pure functions of a value that live on a
shared read-set entry.

**The measurement, and why it counts as one.** The witness is
`implementation/hicasso/test/re_frame/hicasso/hook_budget_cljs_test.cljs`
(`rf2-wjag`), which counts the calls **React's own dispatcher received**, through
a probe that wraps the dispatcher slot. The runtime's `shell-hook-ledger` is a
declaration, and a budget a runtime reports about itself is not evidence; the
ledger is what the counted calls are checked *against*. Four properties are
asserted:

| Claim | What is counted |
|---|---|
| the instrument can say three | a control component calling `useRef`, `useState` and `useMemo` is answered with all three, by name and in call order |
| the shell calls exactly its two | and neither is `useRef` or `useState` |
| the budget is per boundary | a nested boundary costs its own two and shares nothing that would make a page's total sublinear |
| **the count is invariant in reads** | one read, seven, twenty — all cost two |

The third row is the one that matters, and it is the whole argument for the
ambient collector: *N* reads = *N* hooks satisfies this budget on no rung. A
`defhost` crossing costs the door one hook under a gated server policy and none
under the render policy, and the shell's ledger does not move either way. *(The
spelling of those policy keywords is in flight under `rf2-mo4o`, so they are
named by role here rather than by keyword.)*

The witness runs a real server render in the node lane, which is in the fast-PR
spine — a budget breach is a fact about the shell's source rather than about the
host, so it is counted where a pull request actually runs it.

**What the ceiling does not forbid.** It is a ceiling, not a target.
`collector/frame-prop-shell` declares **one** hook and carries its own ledger; it
is a hypothesis under measurement rather than the default, `h/defview` mints the
context-fed shell, and I9's own wording already says what taking it up would cost
— *freeing or replacing one requires its own correctness and whole-shell
measurement*. Nothing in this freeze pre-empts that.

**What the ceiling does forbid**, and this is the operative half: no optional
capability may add a hook to every boundary. The
[React-compatibility lane](lanes/react-compatibility-notes.md) already refuses one
concrete facility on exactly this ground — correcting the concurrent path before
its paint would need a third universal hook plus per-fiber force-update machinery
on every boundary of every application — and that refusal is now backed by a
counted line rather than a declared one.

---

## 5. The carried costs, and their reopen conditions

Two costs are carried out of this adjudication. Neither is normalised, both are
red where they are red, and each has a named condition under which it is revisited.

### 5.1 The spine's per-read premium

`+698 B/read` against the ratom family, measured on the package. Reopened by any
of [§3](#reopen-conditions)'s three conditions. It is a
`re-frame.substrate.spine` cost, so the natural place to attack it is core, and
the natural instrument is this same ladder.

### 5.2 The read-free boundary shell — the disposition

**[RULED]** The line is the literal **`1,024 B`**, frozen by the operator on
2026-08-12, with the adjudication rule that a confidence band crossing it is
UNRESOLVED rather than a pass ([`budgets.md` §5](budgets.md)).

**[DERIVED] The verdict is a plain red, and the UNRESOLVED rule does not fire.**

| R=0 shell, package | Reagent segment | UIx segment | worst round | against `1,024 B` |
|---|---:|---:|---:|---|
| S1 / S2 | **1,100 B** | **1,095 B** | 1,091 / 1,087 B | **1.074× / 1.069× — over, in every round** |

All twelve readings sit at or above **1,087 B**. The bands do not reach the line
from either side, so this is not a band-crossing case and no band-crossing
verdict arises.

**[DERIVED] Remediation through the substrate is REFUSED, and that is this
bead's own finding.** The specification directs that *Phase 1 first attempts
remediation through the collector-substrate adjudication*
([§6](specification.md#6-performance-contract)). The attempt is made here and it
fails, structurally rather than narrowly: the two substrates' shells are **five
bytes apart**, inside each other's bands, because the shell touches no adapter at
all. There is no substrate — including one Hicasso writes itself — that moves an
R=0 boundary, because an R=0 boundary holds no reaction. **The whole overage — 71 B on the chosen substrate, 76 B on the other — would
have to come out of the shell.**

**The three routes that are left, priced.** Two are measured and one is not:

| Route | Measured | Where it lands | What it costs |
|---|---|---|---|
| remove HD-028's memo wrapper | bench tree, paired A/B/A | **994 / 992 B** — under `1,024 B` in every round | overturning [HD-028](../decisions.md#hd-028--value-equality-is-the-boundary-default), whose own rationale measured the cascade it prevents on the most ordinary page shape there is |
| adopt the one-hook `frame-prop-shell` | bench tree | **1,054 / 1,051 B**, worst round 1,047 | still over the line; plus I9's own whole-shell measurement |
| attack the shell's own bytes | not measured | unknown | nobody has decomposed the R=0 rung object by object |

The wrapper row is the arresting one and it needs reading carefully. The paired
A/B/A run puts the wrapper at **+105.5 / +105.0 B** on the two segments with
disjoint bands, so *the design's own shell arrives at the line and the wrapper is
what puts it past*. But the wrapper is a **ruled default** — HD-028 is itself the
evidence-driven overturn of HD-006, taken because a page-chrome write re-rendered
300 value-equal card boundaries beneath it — so removing it trades a measured heap
row for a measured re-render cascade. That is an operator's trade, not a worker's,
and it is not a substrate question at all. **Both figures are bench-tree**: the
package re-pin did not re-take the no-wrapper arm, and the shell figures it did
take land within 1–2 B of the bench-tree wrapper arm, which is why the bench-tree
A/B is quoted as a guide rather than promoted.

**[RULED — delegated] The disposition: the breach is carried, red, and the line
is not re-registered.** The bead offers re-registration as the alternative when a
substrate cannot bring the row under, and this page declines to take it, for
reasons it would rather state than bury:

- the line was ruled **earlier the same day** as this adjudication, and
  re-registering it on the first measurement that fails it is the
  silent-normalisation path wearing a name-tag;
- the line is **reached** by a measured arm — 994 / 992 B — so it is not an
  unreachable line, only an unreached one, and the correct disposition of a
  reachable line is to keep it red and name the route;
- and no number this page could propose would be anything but reverse-engineered
  from the measurement it has to accommodate.

What is handed up instead is the decision itself, in the form the bead requires —
nothing silent, and every field filled:

- **Reason**, if the operator does re-register: the registered estimand prices the
  design at the one rung it is deliberately worst at. The per-boundary
  subscription hook is paid by a boundary that reads nothing, and it is the same
  structural choice that wins **−692 B on every read** against the spine donor;
  the crossing against that donor is bracketed by direct measurement between R=1
  and R=3. A read-free line therefore adjudicates the trade from one end only.
- **Decider**: the operator. The `1,024 B` reading is his ruling and only he can
  move it.
- **Revert**: any re-registration is prospective and lapses the moment a shell
  arm lands under `1,024 B` on the package — at which point the registered line
  is the one that stands, and the re-registration is deleted rather than kept as
  a floor.

**Nothing is recoloured in the meantime.** [`budgets.md` §7](budgets.md) keeps the
distributional family out of blocking PR thresholds, so the red row costs no gate
and buys no allowance; §6 already forbids a relative regression allowance from
recolouring it. The row is published beside its line, in the red, with this
verdict beside it.

---

## 6. What this page does not decide

**[SETTLED 2026-08-13, `rf2-l50z`] The mechanism of the `+139 B/read` package
move.** It is **one line**, and it is a correctness repair rather than a package
regression: `interop/activate-derived-value!` in `wire-cell!`, landed by
`9d01cd171e` (`rf2-2kshh`) on 2026-08-07. The A–B–A bisection this section
called for was run on the ratom segment with the spine segment as the negative
control, and the whole of the move came back on that line — `1,417 → 1,278 →
1,417 B/read`, the two hook-armed readings agreeing to the byte around the
hook-less one, while the spine read `2,115` in all three arms.
[The bisection, its controls and its provenance](../studio/reads-per-boundary-heap-ladder.md#the-139-bread-attributed-to-one-line-and-the-premise-it-had-to-correct-first-rf2-l50z)
are on the ladder. **No figure on this page changes** — S3 is still
`1,417 B/read` and the premium is still `+698` — because the attribution names a
cost rather than moving one.

The mechanism is the ratom-only line [§2 above](#the-correctness-axis-which-is-one-sided-and-total)
already identifies. Activation is `deref-capture`, so the `Reaction` afterwards
holds a populated `watching` array and is enrolled in each captured source's
watcher map; both are retained per cell, cells are B·R on this rung, and the
whole price therefore lands in the marginal slope. On the React-hook spine the
hook is published by nobody and the call allocates nothing, which is why the
spine reproduced exactly.

**The suspect this section named was not the answer, and the premise had to be
corrected before the ablation could be aimed.** The two columns of the package
run's comparison table are two runs eight days apart rather than two source
trees, and the `wire-cell!` bodies of the package and the prototype are
identical today — so what the table prices is the whole interval between the
sessions, not the package/prototype difference its columns are named after. That
is why the move showed in one segment and not both: nothing in the window added
per-cell state to the spine.

**What that costs the collector's own bill, stated rather than glossed.** The
`139 B/read` is what a Reagent-hosted Hicasso boundary pays to answer writes at
all; without it the arm paints once at mount and goes deaf. It is a real cost on
a real axis and it is carried, not normalised — the same posture
[the disposal hook](../studio/reads-per-boundary-heap-ladder.md#the-slope-went-stale-before-this-section-merged-and-the-landing-that-moved-it-rf2-2rtt660)
was recorded under.

**[OPEN] The clock and migration contrasts for the ratom family.** Neither
exists, on any tree. Settling them needs a candidate-on-ratom segment added to
the clock page's design — which its own §3 explains is not free, because
`install-adapter!` is once per process and two substrate arms in one segment pay
for each other's writes. This page does not ask for it: the arm it would inform
is the one not selected.

**[OPEN] Whether a Hicasso-owned derived-value container beats the spine.** No
code, no measurement, a demonstrated target of 1,417 B/read, and a bracket that
says where it would have to land.

**Not touched.** `spec/**` is not reached by any verdict here — the freeze lands
in [`invariants.md`](invariants.md#7-recorded-freezes), which is this tree's
ledger, and the specification rows this page cites are cited rather than changed.

---

## 7. Provenance

Written 2026-08-12 for `rf2-hic-018`.

**Figures, and their trees.** S1–S5 are package figures, re-pinned by `rf2-fe0l`
(PRs #7939 and #7941) in one solo quiet-window run of `p0_run.cjs --only ladder`
against `implementation/hicasso`, six rounds, package candidate and both donors
in the same run set; they are quoted from
[`budgets.md` §4](budgets.md) and
[the ladder's own section](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l),
which carry the controls. **Bench-tree figures are labelled bench-tree
everywhere they appear** — the no-wrapper and `frame-prop` shell arms in
[§5.2](#52-the-read-free-boundary-shell--the-disposition), the S6 cold-mount and
S7 allocation rows, and every clock reading. Nothing measured elsewhere is
promoted here.

**Source facts** — the adapter reference counts, the `make-derived-value`
implementations, the package's declared dependencies, the ratom-only activation
line and the hook witness's assertions — were read in this repository at the
branch this page merges from, and each is named in place so it can be re-read
rather than trusted.

**This page ran no measurement.** It adjudicates evidence taken by
`rf2-hic-006`, `rf2-fe0l` and the studio pages they cite, and by the kernel
witnesses `rf2-hic-010`, `rf2-hic-011`, `rf2-hic-013`, `rf2-hic-014` and
`rf2-hic-015`. Where it needed a figure that does not exist, it says so and names
the ablation rather than estimating one.
