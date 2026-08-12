# The K3 disposition — three scoreboards, no substitution

> **Status: OPERATIVE by default, operator-overturnable.** This is the explicit
> per-read K3 record [`budgets.md`](budgets.md) and the
> [decision brief](decision-brief.md) both name and neither takes. It discharges
> `rf2-hic-070`. The **decider is Mike Thompson, acting as re-frame2 product
> operator**; `rf2-hic-085` records what the sitting of 2026-08-27 rules and
> fills the effective-revision field in [§9](#9-the-record). It follows the rule
> `rf2-hic-003` set for the sibling records: the drafted disposition is operative
> now so that nothing depends on tracker-only state, and the sitting's ruling
> then **re-pins this record rather than re-framing it**.

K3 asks one question — *is per-boundary heap worse than Reagent with no paper
path to the floor?* — and the per-read half of it is answered by three different
instruments answering three different questions. The programme's standing rule,
carried in the [decision brief's finding 4](decision-brief.md), the
[evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence) and
[specification §6](specification.md#6-performance-contract), is that **none of
the three may stand in for another**. This page is where that rule stops being a
sentence and becomes a record: every figure below carries the tree it was taken
on, every scoreboard carries what it may not be used for, and where a figure
exists on one scoreboard and not another the absence is stated rather than
borrowed from a neighbour.

---

## 1. The frozen registered criterion

Quoted verbatim from the kill-criteria table in the
[validation register](../validation.md#kill-criteria-any-tripping--stop-or-narrow-adapters-only-is-success),
so that no later disposition can be read back onto a line that moved:

```text
| K3 | Per-boundary heap worse than Reagent with no paper path to the floor |
```

The heap-regime ruling (`rf2-2rtt6.16`) split that row in two — the R=0 boundary
shell on one axis, the marginal per-read slope on the other. **This record owns
the per-read axis only.** The shell axis is red against its frozen `1,024 B`
line and its disposition is
[the substrate decision](substrate-decision.md#6-what-this-page-does-not-decide)'s,
not this page's.

The per-read axis is judged against two regime-matched lines, both stated on the
mandatory distinct-query witness (Q = E) as marginal slopes, and both quoted from
[the validation register](../validation.md):

| Gate | Line | Verdict beyond it |
|---|---:|---|
| UIx material-cost red-zone | **2,935 B/read** [2,852–3,055] | RED — requires an explicit operator waiver naming the observed dogfood benefit |
| K3, Reagent-sourced | **943 B/read** [935–944] | K3 territory unless a paper path down is named |

Between the two lines a candidate is *"UIx-rule cleared, K3 open until a path
down is named"* — **never plain green**.

---

## 2. The figures this record disposes, and the tree each was taken on

**S1–S5 are package figures as of 2026-08-12**, re-pinned by `rf2-fe0l` (PRs
#7939 and #7941) in one solo quiet-window run of the P0 ladder repointed at
`implementation/hicasso`, six rounds, package candidate and both donors in the
same run set. They are quoted here from [`budgets.md` §4](budgets.md) and
[the ladder's own section](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l),
which carry the controls. Nothing on this page is re-derived from raw samples.

| Quantity | Package (2026-08-12) | Prototype anchor (superseded) | Moved? |
|---|---:|---:|---|
| Hicasso, ratom segment | **1,417** [1,416–1,417] | 1,278 [1,275–1,280] | **+139 B/read, +10.9%**; bands disjoint by 136 B |
| Hicasso, React-hook spine segment | **2,115** [2,109–2,118] | 2,115 [2,110–2,118] | no — reproduced to the byte |
| Reagent donor | **948** [947–948] | 947 | no |
| UIx donor | **2,980** [2,979–2,981] | 2,979 / 2,980 | no |

**The bead that dispatched this record quoted `1,278` and `947 B/read`, and that
premise no longer holds.** Those are the prototype anchors, taken on
`re-frame.bench.hicasso.arm1.*` in the benchmark tree; the package re-pin
landed after the bead was written and after the pointer note on it. The anchors
are kept above as lineage — the evidence the package figures reproduce or move
against — and they are never quoted as package measurements. The `~1.4x` the
decision brief's scoreboard row carries for K3 is therefore the *prototype*
contrast; on the package the same contrast is deeper, and [§3](#3-scoreboard-a--governed-viability-against-the-best-shipped-path)
records how much.

**Which column ships.** `rf2-hic-018` ruled that the collector stops inheriting
its substrate and that
[the substrate is the React-hook spine](substrate-decision.md#3-the-verdict-on-the-substrate),
carried at a measured premium of **+698 B/read** (`1.4926×`) over the ratom
family. The consequence for this page is stated by that record and not invented
here: *"the published per-read figure for Hicasso is the spine column. S3's
ratom-segment figure remains a measurement and a K3 scoreboard input … but it is
no longer the number a shipped Hicasso would produce"*
([`substrate-decision.md`](substrate-decision.md#what-this-freezes)).

**On the ratios.** Every ratio quoted from `budgets.md` or the ladder comes from
the run's own unrounded fits, so a reader dividing the rounded figures in the
table above lands a few ten-thousandths away — `1,417 / 948 = 1.4947` against the
cited `1.4953×`. That is rounding, not drift. Where this page computes a ratio
itself it says so on the spot.

**What is not on this page, and may not be brought onto it.** S6 (cold mount)
and S7 (warm allocation) are **bench-tree figures** — no package-resident clock
instrument exists and the allocation row has no publishable claim — and U1–U4
are unpinned on any tree. No clock reading, no allocation reading and no
bench-tree figure of any kind appears below, because none of them measures
retained bytes per read. A page that reached for one would be substituting an
instrument, not just a denominator.

---

## 3. Scoreboard (a) — governed viability against the best shipped path

**The question.** What does an application pay, per retained read, to ship
Hicasso instead of the best shipped path — Reagent? This is the governed K3 row,
and it is the one the kill criterion is written about.

Two contrasts exist in the package run, and **they are not the same estimand**:

| | Contrast | Figures | Ratio | What it prices |
|---|---|---:|---:|---|
| **a-i** | design cost, substrate held fixed | 1,417 vs 948 B/read | **1.4953×** | what the collector costs above Reagent's own reactions when both sit on the ratom family |
| **a-ii** | shipped configuration, substrate as it will ship | 2,115 vs 948 B/read | **≈2.23×** | what an application actually pays after the ABI freeze |

`a-i` is quoted from [`budgets.md` §4](budgets.md), which also records that the
re-pin deepened it from `1.3492×`. **`a-ii`'s ratio is computed here**, from the
two package figures in [§2](#2-the-figures-this-record-disposes-and-the-tree-each-was-taken-on)
and from nothing else: `2,115 / 948 = 2.231×` on the rounded figures. It is
written `≈2.23×` above rather than to four places because this page does not
hold the unrounded fits those two figures came out of, and the ratios it quotes
from `budgets.md` show that the last digits move when it does. The two
rows stand in an exact arithmetical relation rather than a modelled one —
`a-ii = a-i × the spine premium`, because `(1,417/948) × (2,115/1,417) =
2,115/948` — which is why the premium `rf2-hic-018` carried explicitly is
visible here as the whole of the difference between them.

**Reading the two segments against each other is licensed, and by a control
rather than by convenience.** The candidate's R=0 shell reads within **five
bytes** across the two segments (`1,100` and `1,095 B`) because the shell touches
no adapter, and that agreement is what the substrate decision names as its
licence for attributing the slope difference to the substrate. The same control
licenses `a-ii`. Each figure is still taken above its own segment's floor in its
own round; none is scaled onto another.

### The verdict on this scoreboard

**MISSED — on both readings, and more deeply than the record previously said.**
Against the registered `943 B/read` [935–944] line the shipped column sits at
`2,115` [2,109–2,118] and the design column at `1,417` [1,416–1,417]; both bands
are wholly above it, so the row is **K3 territory** on either reading. **No paper
path down to the line is named**: the design's own demonstrated floor is the
`1,417 B/read` ratom column — still `1.4953×` the donor — and the ladder's
bracket sentence is what says a view layer cannot cost less than the reactions it
holds. A Hicasso-owned derived-value container landing inside `1,417 – 2,115`
would move `a-ii` toward `a-i`; it would not reach `943`.

**Which row governs, and the alternative.** From the ABI freeze onward the
governed row is **`a-ii`**, because the estimand the kill criterion asks about is
what an application pays and `a-i` is not a configuration that ships. `a-i` is
retained, published and tracked as the design-cost decomposition and as the
reopen target. The operator may instead rule that the governed row stays `a-i`
— on the reading that K3 asks about the design rather than the shipped
configuration — in which case only which of the two numbers is quoted as *the*
K3 miss changes; **neither ruling turns the row green**, and both are recorded
above so that the choice is visible rather than embedded.

### What this scoreboard may not be used for

It may not be netted against [scoreboard (b)](#4-scoreboard-b--architecture-progress-against-the-uix-parent)
or [(c)](#5-scoreboard-c--author-preference), and no result on either recolours
it. It may not be netted against the R=0 shell row in either direction: a shell
byte is not a per-read byte, the shell is `rf2-hic-018`'s axis, and
[`budgets.md` §5](budgets.md) already forbids a relative allowance from
recolouring it.

---

## 4. Scoreboard (b) — architecture progress against the UIx parent

**The question.** Does the architecture cost less per retained read than the
parent it was built to improve on — direct UIx-on-subs?

| Figures | Ratio | Margin |
|---|---:|---:|
| 2,115 vs 2,980 B/read, both package figures from the same run | **0.7098×** | **29.0% lower** |

**WON, and unchanged by the package re-pin** — `budgets.md` records the move as
`0.7099× → 0.7098×`, unchanged to the fourth decimal, because both sides of this
row reproduced their prototype anchors.

Two facts strengthen the row and are worth stating because a reader would
otherwise have to reconstruct them. First, **this row is like-for-like after
`rf2-hic-018`**: UIx installs the React-hook spine through
`spine/make-react-adapter`, and the shipped Hicasso now carries the same spine,
so the two columns differ in view layer alone. Second, the shipped column clears
the registered **UIx material-cost red-zone** of `2,935 B/read` [2,852–3,055]
outright — `[2,109–2,118]` is wholly below the band's lower edge, so the verdict
is robust to the 45 B by which this instrument's own UIx donor (`2,980`
[2,979–2,981]) reads above the published line. That gap is not drift: the ladder
took the same quantity against the same gate and recorded `2,981 B` [2,979–2,986]
against `2,935` [2,852–3,055] as a **`+1.6%` reproduction, inside the band** —
the donors being where the record says they are, on the box the candidate was
measured on.

Combining the two registered lines gives the row's full registered verdict, in
the register's own words: **"UIx-rule cleared, K3 open until a path down is
named" — never plain green.**

### What this scoreboard may not be used for

**It is not a K3 pass, and a 29% win against the parent may never be reported as
one.** K3's denominator is Reagent; this row's denominator is UIx; a win here
answers a question the kill criterion did not ask. It is reported **beside**
scoreboard (a), never instead of it.

---

## 5. Scoreboard (c) — author preference

**The question.** For the everyday spine screen — a list, a controlled field, a
filter — which of the two files do the authors want to write, read and maintain?

The witness is
[the dogfood preference case](../studio/the-dogfood-preference-case.md), and it
is denominated in **lines, event positions and named losses — in no bytes at
all**:

| Measured | Hicasso | Raw UIx |
|---|---:|---:|
| Counted lines for the same screen | **47** | 72 |
| Event positions | 8 carrying data | 8 hand-written closures |
| Values threaded through props | 0 | 2 |

Both renderings are *proved to be the same screen* — same canonical DOM at
mount, the same fifteen intents through a 17-step script touching every handler
site, the same silences on composing keystrokes, DOM parity again afterwards —
which is what makes the authoring difference a comparison rather than an
anecdote. The witness also records
[four things raw UIx does better](../studio/the-dogfood-preference-case.md#4-what-raw-uix-does-better-here)
and a draw: compile-time shape checking, the event already in hand, subscription
identity stable by construction, and a smaller machinery bet.

**The verdict, and where its citation comes from.** The witness page
[deliberately records no verdict](../studio/the-dogfood-preference-case.md#5-the-open-question)
— the charter's gate is written as a preference held by people, and the call is
the operator's. The preference is recorded instead in the operator's own
[decision brief, Part I finding 4](decision-brief.md): *"The dogfood verdict
prefers the capability, and the selection is made."* **The verdict's citation is
therefore the brief, and never the witness page.** That distinction is the whole
of this scoreboard's discipline: the witness may not be quoted as though it
delivered a verdict, and the verdict may not be quoted as though it were a
measurement.

### What this scoreboard may not be used for

It may not offset a byte. A preference held in lines and named losses cannot pay
down `a-ii`'s `≈2.23×`, and equally the byte miss does not overturn the
preference — they are different currencies, and the record keeps them in
different columns. Its scope is also bounded: it is preference evidence for **one
list/form workload**, and the [use-cases lane](lanes/use-cases.md) names a full
application and one serious vendor integration as the next useful witnesses.

---

## 6. What substitution would look like here

Named so that a later reader can check this record against its own rule. Each
line is refused above rather than merely disapproved of:

- **Calling scoreboard (b)'s 29% win a K3 pass.** Different denominator,
  different question. Refused in [§4](#4-scoreboard-b--architecture-progress-against-the-uix-parent).
- **Inventing a paper path down to `943 B/read`.** No code and no measurement
  supports one; the design's demonstrated floor is `1,417`.
  [§3](#3-scoreboard-a--governed-viability-against-the-best-shipped-path).
- **Counting the R=0 shell against the per-read slope**, in either direction.
  Different axis, different owner.
- **Quoting `1,417 B/read` as what a shipped Hicasso costs.** It is the arm that
  does not ship; the shipped column is `2,115`.
  [§2](#2-the-figures-this-record-disposes-and-the-tree-each-was-taken-on).
- **Treating the registered `943 B/read` [935–944] line and this run's `948`
  [947–948] donor as one number.** They come from different instruments — the fan
  sweep and the P0 ladder — their bands are **disjoint**, and they do different
  jobs: the gate is judged against `943`, while the same-run ratio is taken
  against `948`. The ladder records their `+0.5%` relationship as a
  *reproduction* of the gate, which is a licence to trust the box, not a licence
  to swap the numbers.
- **Importing a bench-tree figure onto this page** — S6, S7, or any clock
  reading. [§2](#2-the-figures-this-record-disposes-and-the-tree-each-was-taken-on).
- **Letting the dogfood preference settle a byte question, or a byte result
  settle the preference.** [§5](#5-scoreboard-c--author-preference).

---

## 7. The disposition

**K3's per-read axis is ACCEPTED AS A RECORDED MISS for the selected product.**
The three scoreboards are recorded once each, separately labelled: the governed
Reagent row is **missed** and its miss deepened on the package; the UIx-parent
row is **won** and reported beside it; the author preference is **held by the
operator** on a witness that measures no bytes. Acceptance means the programme
ships knowing the bill, not that the row is green: the registered verdict stays
*"UIx-rule cleared, K3 open until a path down is named"*, and no threshold on
this page or any other widens to accommodate it.

**Nothing is recoloured by this acceptance.** The published figures stand as
measured, the registered `943 B/read` line is untouched, the red R=0 shell row
keeps its own disposition, and K1, K2 and K4 keep their own owners and gates.

---

## 8. The 10% same-witness per-read regression rule

The rule as registered, quoted from
[specification §6](specification.md#6-performance-contract):

> Per-read retained cost is governed by the three-scoreboard K3 disposition and
> also may not regress more than 10% on the same pinned witness.

This record supplies what "the same pinned witness" means, so that the rule is
executable by the bead that owns its enforcement:

| Field | Value |
|---|---|
| Pinned witness | The P0 ladder's candidate arm pointed at `implementation/hicasso` — rungs 0/1/3/7/20 at B = 1,200 boundaries (4 roots × 300 cells), Q = E, six rounds, slope fitted over rungs 1/3/7/20 and never from the R=0 intercept |
| Instrument | `p0_run.cjs --only ladder` with the package seams landed by PR #7939; the driver, donors, floor, harness, fixtures, fit rules and order guard unchanged from the run that set the baseline |
| Profile | **P-DEV-1 only.** `CI-RUNNER-A` may never source this row — [`budgets.md` §1](budgets.md) |
| Governed baseline | The **shipped column**: `2,115 B/read` [2,109–2,118] (S4's Hicasso figure) |
| Tracked, not gated | The ratom column, `1,417 B/read` [1,416–1,417] (S3's Hicasso figure). It is published and its movement is reported, but a regression on an arm that does not ship is not a product regression |
| Trip point | `+10%` of the governed baseline is **`2,326.5 B/read`** — computed here as `1.10 × 2,115`, and stated to the half-byte because rounding it up would hand back a byte of allowance the rule did not grant. A reading above it trips |
| Band reading | A confidence band that **crosses** the trip point is **UNRESOLVED, not a pass** — the same reading the operator froze for the `1,024 B` shell line on 2026-08-12, adopted here by analogy and overturnable with it |
| What a breach does | It is **not** a blocking PR gate: [`budgets.md` §7](budgets.md) keeps the distributional family out of flaky PR thresholds. It blocks on the pinned interleaved evidence run until the benchmark owner validates the instrument and the adapter owner fixes or reverts |
| Enforcement home | **`rf2-hic-071`**, with the early framework at `rf2-hic-089`. This record states the rule; it does not build the gate |

**A re-pin is not a regression, and the distinction is load-bearing.** The
package move on the ratom segment was `+10.9%` — larger than this rule's
threshold — and it is **not** a regression event, because the witness tree
changed underneath it: the prototype and the package are different software
measured on the same instrument, which is a re-pin. On the arm this rule
actually governs the same move was **0 B**: the spine segment reproduced
`2,115 → 2,115`. The rule runs forward from the package baseline above, and its
first same-witness comparison has not yet been taken.

**The 5% rule is a different rule.** [`budgets.md` §4](budgets.md)'s
*"pinned ordinary-Hicasso benchmark does not regress > 5% on same witness and
instrument"* governs a different witness on a different axis; neither threshold
may be quoted for the other, and a green result on one says nothing about the
other.

---

## 9. The record

The fields any governance change owes under the
[measurement posture](lanes/evidence-baseline.md#measurement-posture):

| Field | Value |
|---|---|
| Frozen registered criterion | The K3 row quoted verbatim in [§1](#1-the-frozen-registered-criterion), with the two regime-matched per-read lines it is judged on |
| Adjudicated status | **MISSED on the governed Reagent row, accepted as a recorded miss.** Registered verdict: *"UIx-rule cleared, K3 open until a path down is named"* — never plain green |
| Scoreboard (a) — governed viability | `2,115` vs `948 B/read`, `≈2.23×` as shipped; `1,417` vs `948`, `1.4953×` with the substrate held fixed. Package figures |
| Scoreboard (b) — architecture progress | `2,115` vs `2,980 B/read`, `0.7098×`, 29.0% lower than the UIx parent. Package figures. Reported beside, never as a pass |
| Scoreboard (c) — author preference | 47 counted lines against 72, eight data event positions against eight closures, on one proved-equal list/form screen. Denominated in no bytes |
| Named witnesses | The P0 ladder's package run for (a) and (b) — [the ladder's section](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l); [the dogfood preference case](../studio/the-dogfood-preference-case.md) for (c), whose verdict is cited from the [decision brief](decision-brief.md) and not from the witness |
| Instruments | `p0_run.cjs --only ladder` on the package seams (PR #7939), one solo quiet-window run, six rounds, P-DEV-1, for (a) and (b); counted authoring plus a canonical-DOM and 17-step intent-parity witness for (c) |
| Regression rule | The 10% same-witness per-read rule, made executable in [§8](#8-the-10-same-witness-per-read-regression-rule) |
| Decider | Mike Thompson, acting as re-frame2 product operator |
| Evidence owner | [`budgets.md`](budgets.md) for S1–S7, [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence) for the pinned values, [`substrate-decision.md`](substrate-decision.md) for which column ships |
| Effective revision | *(left blank — `rf2-hic-085` fills this after the sitting)* |
| Reopen conditions | [§10](#10-reopen-and-revert) |
| Revert condition | [§10](#10-reopen-and-revert) |

---

## 10. Reopen and revert

Any of these returns this record to the decider:

- **A new package baseline on the same instrument.** These are the first package
  figures for this row; a second run that moves either column re-pins every ratio
  above.
- **A substrate change.** `rf2-hic-018` is delegated and operator-overturnable,
  and its own reopen conditions — a Hicasso-owned derived-value container
  measured inside the `1,417 – 2,115 B/read` bracket with the kernel witnesses
  green on it, a core landing that moves the spine's `+698 B/read` premium, or a
  kernel witness that fails on the spine and passes on the ratom family — each
  change **which column ships**, and therefore which number scoreboard (a)
  reports.
- **A named paper path down to `943 B/read`.** None exists; one would change the
  criterion's own second conjunct.
- **A movement of either registered line** in the validation register.
- **A recorded author-preference verdict that differs from the one cited in
  [§5](#5-scoreboard-c--author-preference)**, or a second preference witness on a
  workload the dogfood screen does not cover.

**The record lapses**, automatically and without amendment in place, if any
scoreboard's figures are quoted in this tree as a substitute for another's — that
is the one thing it exists to prevent, and a record that tolerated it would be
worse than none. Re-issuing it requires the same decider.

---

## 11. What this record does not decide

- **[OPEN] The mechanism of the `+139 B/read` package move on the ratom
  segment.** `rf2-hic-018` left it open and said it is owed an attribution to
  this bead or to one of its own; **this record takes the second route and files
  it as `rf2-l50z`.** No verdict above needs it: the move sits entirely inside
  the arm that does not ship, and the shipped column reproduced to the byte. The
  ablation that would settle it is **named on `rf2-hic-018`, not guessed at
  here** — the A–B–A bisection on the ratom segment only, riding the spine
  segment as a negative control, with one suspect carrying a precedent *as a
  suspect and not as an answer*. This page repeats neither the suspect nor a
  cause; it cites [where both are written down](substrate-decision.md#6-what-this-page-does-not-decide).
- **The R=0 shell breach.** `rf2-hic-018`'s, carried red against the frozen
  `1,024 B` line, with the substrate arm refused on the evidence because the two
  shells are five bytes apart.
- **The K1 mount price.** [`k1-price-acceptance.md`](k1-price-acceptance.md)'s.
  Ratifying it carries nothing here, and this record carries nothing there.
- **Bulk (K2), the WebKit control matrix (K4), and warm allocation.** Each keeps
  its own owner, gate and open status.
- **Whether a Hicasso-owned derived-value container beats the spine.** No code,
  no measurement, a demonstrated target of `1,417 B/read`, and a bracket that
  says where it would have to land.

---

## 12. Sources

- [`budgets.md`](budgets.md) — S1–S7 with their trees and statuses, the
  comparative and regression rules, the reference profiles, and §7's enforcement
  homes.
- [`substrate-decision.md`](substrate-decision.md) — `rf2-hic-018`: which
  substrate ships, the `+698 B/read` premium, the shell disposition, and the open
  `+139 B/read` attribution with its named ablation.
- [`../studio/reads-per-boundary-heap-ladder.md`](../studio/reads-per-boundary-heap-ladder.md#the-package-itself-priced-on-this-rung-at-last-rf2-fe0l)
  — the package run: rows, fitted lines, the ten-quantity reproduction table, and
  the gate reproduction that anchors the donors.
- [`../validation.md`](../validation.md#kill-criteria-any-tripping--stop-or-narrow-adapters-only-is-success)
  — the K3 criterion as registered and the two regime-matched per-read lines.
- [`../studio/the-dogfood-preference-case.md`](../studio/the-dogfood-preference-case.md)
  — the authoring comparison, the intent-parity witness, the named losses, and
  its refusal to record a verdict.
- [`decision-brief.md`](decision-brief.md) — Part I finding 4, the scoreboard row
  this record supersedes on figures, and the author-preference verdict.
- [`specification.md` §6](specification.md#6-performance-contract) — the
  three-scoreboard governance and the 10% same-witness rule as registered.
- [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence)
  — the pinned retained-reads row and the measurement posture's governance-change
  fields.
