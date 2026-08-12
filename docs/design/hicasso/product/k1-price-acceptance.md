# K1 price-acceptance amendment record

**Status: RATIFIED and operative — by operator ruling, 2026-08-13, not by a sitting.** This is the scoped amendment instrument prepared in Phase 0 and held for the sitting of **2026-08-27**, where **Mike Thompson, acting as re-frame2 product operator, is the decider**. He gave the ruling directly in chat on **2026-08-13 at 04:57 AUSEST**, ruling the P2 fork *graduate, as a success* and pre-empting that sitting; graduating as a success with the canonical K1 record of §2.1 on the table **is** the acceptance of the price this record prices, so the amendment is ratified by that ruling rather than by the meeting prepared to obtain it. The ruling is recorded on the epic `rf2-2rtt6`, as [`decisions.md` HD-029](../decisions.md#hd-029--the-p2-fork-hicasso-graduates-as-a-success) in the normative entry series, and at the [kill table's graduation record](../validation.md#the-kill-table-at-graduation--the-p2-ruling-of-2026-08-13); `rf2-2rtt6.144` wrote it into this tree. **Nothing else on this page moved with the status.** The accepted price, the jobs it buys, the escape route, the frozen comparison rule and both the reopen and revert conditions read exactly as drafted, and ratification is operator-overturnable like any recorded ruling. **The `1.25x` ceiling is now operative** and is read against a future row by [§5.1](#51-the-frozen-comparison-rule) and by nothing else; **the registered `1.10x` K1 gate is untouched, K1 remains recorded as MISSED, and no evidence row may cite this record to colour it green** ([§5](#5-the-proposed-ceiling), [§8](#8-what-ratification-would-not-carry)). The [primary performance contract](specification.md#6-performance-contract) owns the ceiling and the ratification rule; the [evidence baseline](lanes/evidence-baseline.md#k1-price-proposal) owns the pinned values; the [decision brief](decision-brief.md) owns the selection this record is the honest bill for. Those two siblings, and the other pages in this tree that describe the ceiling as pending a sitting, still carry their pre-ratification wording and are brought into line by their own bead (`rf2-2rtt6.145`) — **this page is the operative status**, and where a sibling still reads *proposed*, it is stale rather than contradicting.

Three things this record deliberately does not do. It does not recolour the measured result: the miss in §2 is quoted as published, and no threshold anywhere in this tree widens to accommodate it. It does not decide whether the K1 *kill criterion* has tripped — at drafting that conjunction had only one half adjudicated, and the operator has since ruled the other half MET (2026-08-10, `rf2-sza0w`), so this record carries the trip in §2.2 rather than deciding it; what the trip asks of the accepted price is §7.1's first reopen condition, which fired on that ruling and was **answered on 2026-08-13** — the price was reconsidered in the light of the trip and accepted, which is what ratifying this record by the graduation ruling means. And it does not waive the red read-free boundary-shell row, which the performance contract keeps as a separate prospective disposition that this ratification does not pre-authorize.

## 1. The frozen registered criterion

Frozen verbatim from the kill-criteria table in the [validation register](../validation.md) as it stands at drafting, so that ratification cannot later be read back onto a line that moved:

```text
| K1 | Mount above the amended mount gate (1.10× direct UIx on the clock of record) after two serious runtime iterations |
```

The denominator is **direct UIx-on-subs on the clock of record** — the mount-gate amendment of 2026-08-02 with its 2026-08-05 consistency edit (`rf2-hyd50`), which replaced the stale Reagent denominator. There is no second mount condition: the mount gate is one line, and Reagent-on-subs is co-instrumented and reported beside the mount row rather than gating it.

## 2. The measured position

The criterion has two conjuncts. They stand differently, and this record keeps them apart.

### 2.1 The gate half: missed, decisively

`rf2-diaud` (PR #7704) published the M1 mount row on K1's own floor-normalised estimand — point and interval drawn from one estimator through the same run-preserving bootstrap, 4,000 draws at the fixed seed 20,260,807 — and adjudicated it against K1's own threshold:

| Ensemble | Outer runs | `hicasso / uix-subs`, floor-normalised | Verdict against K1's `1.10×` |
|---|---:|---|---|
| `clock-emvod` | 8 | **1.1718×** [1.1263 – 1.2190] | whole interval above — **K1 MISSED** |
| `clock-w3yxd` | 6 | **1.1976×** [1.1504 – 1.2468] | whole interval above — **K1 MISSED** |

Both intervals lie entirely above the gate on the losing side, so the mount premium is a magnitude and not a direction: the candidate mounts at roughly **1.17–1.20x direct UIx-on-subs**, and the gate is missed on two independently launched ensembles. This is the canonical K1 record and it is never restated as anything softer.

The figures, the co-instrumented Reagent-on-subs pairs, the labelled unfloored diagnostic, and the residual-uncertainty caveats live at [§4.3 of the corrected-clock re-adjudication](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) and are quoted from nowhere else. Those caveats travel with the row into this record: eight and six outer runs are thin ensembles, the mount check standard was calibrated on the same fourteen runs it then judges, and both ensembles record the same Chromium build. The [evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence) owns the pinned values.

### 2.2 The trigger half: ruled MET on 2026-08-10

**Ruled MET, 2026-08-10 (`rf2-sza0w`).** The operator ruled that the candidate has had **two serious runtime iterations**, on the basis the [validation register](../validation.md#kill-criteria-any-tripping--stop-or-narrow-adapters-only-is-success) records: `rf2-nld4g` closed with no admissible attack clearing the bar, and the interpreter was exonerated at `0.9636x` stock Reagent, locating the deficit outside it. Both conjuncts therefore stand and **K1 is TRIPPED**. The criterion text is as registered, the `1.10x` gate is untouched, and the published miss in §2.1 is not recoloured by the ruling: what the trip supersedes is K1's *stop or narrow* consequence, which the selection of Hicasso together with this scoped record make a formalized narrow-and-price rather than a stop. The [clock page §8](../studio/the-candidates-clock.md#8-what-this-hands-the-programme) carries the same record.

**What the ruling asked of this record, and how it was answered.** What this record prices is the measurement in §2.1, and that fact never depended on the ruling. The record's *standing* did: the ruling is the first of [§7.1](#71-reopen-conditions)'s reopen conditions, and firing it returned this record to the decider. That question — whether the accepted price stands or changes, having been reconsidered in that light — was **open and pending from 2026-08-10 until 2026-08-13**, when the decider answered it: **the price stands.** The paragraph below records the forum that answer arrived in.

**Where that question is answered — added 2026-08-11, answered 2026-08-13.** It was **reserved to the sitting of 2026-08-27**, the same sitting this record was held for, with the caveat that reserving it did not defer it: the decider could answer earlier, and *did*. **The answer came on 2026-08-13 at 04:57 AUSEST, in chat, as part of the P2 graduation ruling: the accepted price STANDS.** Graduating *as a success* with §2.1's canonical miss on the table is the operator reconsidering the price in the light of the trip and accepting it, so this record is ratified by that ruling and the sitting it was reserved to is pre-empted. The [effective-revision field](#6-the-record) is filled from the ruling rather than from a sitting; the recording is logged as a back-fill under [§9](#9-amendments-to-this-record), which is the route this paragraph already named for an early answer. **The answer changes the record's status, not its content** — no figure, threshold, criterion or rule moved, and §2.1's miss stands as published.

Kept rather than erased — the reading this section carried at drafting, superseded on 2026-08-10:

> **2.2 The trigger half: undecided.** Whether the candidate has had **two serious runtime iterations** is a programme judgement rather than a measurement, and no document in this tree decides it. It is an open operator ruling, tracked as `rf2-sza0w`, which also owes what K1's "stop or narrow" consequence would mean in practice were the condition found met.
>
> This record neither decides that question nor depends on its answer. What it prices is the measured mount premium in §2.1 — a fact independent of whether the kill criterion is later ruled to have tripped.

## 3. What the premium buys

### 3.1 The purchased capability

About **70% of the mount premium is read-count capability**. Ambient `sub` in loops, helpers, and branches declares fine-grained reads that a hook-shaped twin cannot spell, so the comparator is not rendering the same page. Per read, Hicasso is the cheaper of the two; read-matched pages run near parity. The premium is therefore the price of a richer read topology, not of a slower implementation of the same topology — and the product is judged on the value of local reads plus the user-visible budgets, never on the fiction that both sides express the same page ([corpus insights](lanes/corpus-insights.md#the-performance-premium-must-buy-a-product-capability)).

### 3.2 The purchased jobs

These are the census-grounded jobs the capability buys — the revision-pinned requirements census recorded in the [evidence baseline](lanes/evidence-baseline.md#demonstrated-value) and [corpus insights](lanes/corpus-insights.md#requirements-are-mined-not-imagined), not a speculative roster:

| Purchased job | Census weight | What the capability buys it |
|---|---|---|
| Reads at point of use in synchronous helpers, branches, and variable-length loops | 231 reads across 85 files | The read is spelled where the value is wanted; no lifting to a hook-legal position, no extraction cliff |
| Event-data-first authoring | ~97% of 183 handler sites one value placeholder from data | Handler sites stay pure data because the values they need are read at their point of use |
| Controlled fields under the centralized law | 77 controlled fields | Same-turn echo, caret repair, IME, and revision reset are one framework law rather than per-field hand-rolled code |
| Ordinary lists, keyed collections, and conditional UI | Zero view-local reactive cells | Dynamic and conditional read sites, which a compile-indexed grammar cannot express, need no second state owner |

The remaining census spine — 106 route links, one ref site — rides the same authoring model but is not itself priced by this premium; a late-bound plain route link needs no reactive-boundary machinery. The census is a requirements mine, not a completeness proof: it weakly represents refs, portals, foreign observers, and complex React integrations, and rarity in one repository is not proof that a job does not matter ([evidence scope](lanes/use-cases.md#evidence-scope)). A full application witness and one serious vendor integration are the next useful evidence.

## 4. The escape route

The amendment is scoped because the premium has a named exit. A measured hot boundary is not condemned to pay it: the [performance ladder](lanes/hot-path-architecture.md#the-performance-ladder) is a visible gradient of authored choices — never a `:fast` flag, a compiler tier, or a second execution mode.

| Rung | Route | What the author accepts to take it |
|---|---|---|
| 1 | Ordinary Hicasso | Nothing. The default, where every feature starts |
| 2 | Tune topology without changing language | Boundary placement, key stability, and a chosen fine / coarse / chunked / windowed read shape |
| 3 | Direct React output from a Hicasso boundary | Frame, reads, memo, and component identity are kept; Hiccup lowering, intent lowering, controlled-field normalization, and structural inspection stop for that result |
| 4 | Named native React island | An explicit crossing contract — `n/defcomponent` with `n/use-frame` and `n/use-sub`, or UIx, under the same root and shared frame |
| 5 | Native screen | An intrinsically React-first surface authored natively under the single installed adapter; a separate root remains an isolation choice only |

The escape is an obligation, not a promise. The native tier is real only when **every** row of the [canonical native-tier acceptance checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) passes — a red row blocks publication of the namespace and no row is waived by success on another — and any escape kept in the codebase must clear the benefit threshold of ≥20%, ≥2 ms p95, or a converted user-visible budget. At drafting, rungs 3 to 5 are Phase 3 work and the Hicasso-native namespace is a product hypothesis rather than demonstrated value.

## 5. The proposed ceiling

**`1.25x` cold mount against equivalent direct UIx on the agreed representative witness**, as an initial ceiling, subject to the ratification named above — **which was given on 2026-08-13, so this ceiling is now operative.** The figure and the rule that reads it are exactly as drafted; ratification made them effective and changed neither. Read topology and capability differences are reported alongside the ratio; the ratio never travels alone.

The measured position sits between the two lines: above the registered `1.10x` gate on every corroborated reading, and below `1.25x` including the upper bound of the wider interval (1.2468). That interval is precisely what this record asks to buy, and it is why the instrument is a scoped, reversible acceptance rather than a re-registration of the gate.

Ratification records an accepted price. It does not convert the published miss into a pass, and **no evidence row may cite this record to colour K1 green**. That was true of the ratification when it was prospective and it is true of it now that it has been given.

### 5.1 The frozen comparison rule

A ceiling is only as good as the test that reads a measurement against it. This rule is frozen here, prospectively, before the sitting and before the next row exists; it is derived from how this tree already adjudicates thresholds and not from any measurement. [§7.2](#72-revert-condition) carries no independent test — it applies this one.

1. **What is compared.** Only a *canonical K1 row*: the mount ratio published on K1's own floor-normalised estimand against equivalent direct UIx on the clock of record, with point and interval drawn from one estimator through the same run-preserving bootstrap, as §2.1's row was. A diagnostic, unfloored, descendant, or co-instrumented figure is context and is never compared with the ceiling. The number compared is the published ratio itself: read-topology and capability differences travel beside it as §5 requires, and never adjust it.
2. **The whole interval decides, not the point estimate.** §2.1 adjudicated the gate by asking whether the whole interval lay above the line, and the ceiling is read the same way. The ceiling is *cleared* only when the entire interval lies at or below `1.25x`, and *exceeded* only when the entire interval lies above it.
3. **A straddling interval is inconclusive, and inconclusive is never a pass.** An interval containing `1.25x` neither clears the ceiling nor lapses the record on its own. It is a reopen under [§7.1](#71-reopen-conditions) and returns the record to the decider, who may lapse it; until that ruling no evidence row may cite this record as showing the ceiling holds. A missing decisive reading is recorded as absence of evidence, never as a flattering pass ([measurement posture](lanes/evidence-baseline.md#measurement-posture)).
4. **Compared as published; the ceiling is inclusive.** The published values are compared as published. Nothing is rounded, truncated, or re-expressed at coarser precision beforehand, and no value is ever rounded toward the ceiling to bring it inside. A value exactly equal to `1.25x` is within the ceiling; only a strictly greater value exceeds it, which is the sense §7.2 carries.
5. **One ensemble is not enough.** The gate miss was adjudicated on two independently launched ensembles that agreed, and the ceiling takes the same corroboration: at least two independently launched ensembles on the same estimand and clock of record, each satisfying clause 2 in the same direction, never pooled across programmes or instruments. A single ensemble, however clean, is inconclusive under clause 3, as are two ensembles that disagree.

Read against §2.1's published row, the paragraph above is clause 2 applied: the whole interval, upper bound included, lies below the ceiling. That is a statement about the rule's reading of a known row, not the reason the rule is written this way.

## 6. The record

The fields any governance change owes under the [measurement posture](lanes/evidence-baseline.md#measurement-posture), together with the additional fields the [performance contract](specification.md#6-performance-contract) requires of this one:

| Field | Value |
|---|---|
| Frozen registered criterion | The K1 row quoted verbatim in §1 — mount above `1.10x` direct UIx on the clock of record, after two serious runtime iterations |
| Adjudicated status of that criterion | Gate half: **MISSED, DECISIVELY** (`rf2-diaud` / PR #7704), §2.1. Trigger half: **ruled MET on 2026-08-10** (`rf2-sza0w`), §2.2 — this field read *undecided, open operator ruling* at drafting. Both conjuncts stand, so **K1 is TRIPPED**; the criterion text and its `1.10x` gate are untouched, and the published miss is not recoloured |
| Purchased use cases | The read-capability jobs of §3.2, at their census weights |
| Accepted ceiling | `1.25x` cold mount versus equivalent direct UIx on the agreed representative witness — **ratified and operative from 2026-08-13**; the figure is unchanged from the proposal. A future row is read against it only by the comparison rule frozen in §5.1 |
| Native escape | The five-rung gradient of §4, gated on the canonical native-tier acceptance checklist |
| Named witnesses | The two ambient-read witnesses and the native-tier checklist, with their exact owners and their unwitnessed status, tabulated in §7.2 |
| Decider | Mike Thompson, acting as re-frame2 product operator |
| Evidence owner | [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence) for the pinned values; [corrected-clock §4.3](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) for the published figures and their labels |
| Effective revision | **Ratified 2026-08-13, effective from that date** — by the operator's P2 graduation ruling given in chat at 04:57 AUSEST, not by the 2026-08-27 sitting, which it pre-empts. The ruling is recorded on the epic `rf2-2rtt6`, as [`decisions.md` HD-029](../decisions.md#hd-029--the-p2-fork-hicasso-graduates-as-a-success), and at the [kill table's graduation record](../validation.md#the-kill-table-at-graduation--the-p2-ruling-of-2026-08-13); `rf2-2rtt6.144` filled this field |
| Reopen conditions | §7.1, unchanged and live. Its first condition **fired on 2026-08-10** with the ruling above and was **answered on 2026-08-13**: the price was reconsidered in the light of the trip and **stands**, which is what ratifying this record by the graduation ruling means (§2.2). The remaining three conditions are undischarged and continue to bite |
| Revert condition | §7.2 |

## 7. Reopen and revert

### 7.1 Reopen conditions

Any of these returns this record to the decider, whether or not it has been ratified:

- `rf2-sza0w` rules on the two-serious-runtime-iterations conjunct. A ruling that K1 has tripped changes what "stop or narrow" asks of the programme, and the accepted price must be reconsidered in that light.
    - **FIRED — 2026-08-10.** `rf2-sza0w` ruled the conjunct MET; both conjuncts stand and K1 is TRIPPED (§2.2). This condition is therefore satisfied on its own terms, and the record is returned to the decider. **Whether the accepted price stands or changes, having been reconsidered in that light, is an open operator question and it is pending** — it is not answered here, in either direction. No figure moved when the ruling landed: §5's proposed `1.25x` reads exactly as drafted **because the reconsideration has not been made**, not because it was made and came back unchanged. Until the decider answers, nothing discharges this reopen, and no evidence row may cite the record as though something had.
    - **The forum, added 2026-08-11.** That answer was **reserved to the 2026-08-27 sitting**, the sitting this record was held for, *unless the decider gave it sooner*.
    - **ANSWERED — 2026-08-13.** The decider gave it sooner, which the clause above expressly allowed for. Ruling the P2 fork *graduate, as a success* with §2.1's canonical miss on the table is the reconsideration this condition called for, and **the accepted price stands**. The condition is therefore **discharged**, and it is discharged by an answer rather than by lapse of time. Nothing else moved with it: `1.25x` reads as drafted because the reconsideration came back unchanged, which is a different fact from the one recorded above it and is now the operative one. Evidence rows may cite this record as ratified from that date; they still may not cite it to colour K1 green.
- A new clean clock-of-record acceptance row is published on the mount estimand.
- A named lever plausibly worth the registered `45.7–49.4%` threshold of the measured deficit appears, or the compiler / render-phase / witness-set fences change explicitly. These are the baseline's own reopen conditions for the bounded irreducibility conclusion, and they reopen the price with it.
- The comparative budgets are ratified against named reference hardware and the representative witness changes as a result.

### 7.2 Revert condition

The record **lapses** — automatically, without amendment in place — on any of:

- The purchased ambient-read capability fails either of its named witnesses below.
- The native escape fails its named witness below; any red row on the canonical native-tier acceptance checklist is such a failure.
- The canonical K1 row exceeds the accepted ceiling, decided by the comparison rule frozen in [§5.1](#51-the-frozen-comparison-rule) and by nothing else.
- The witness or the estimator changes materially without re-ratification.

The witnesses the first two conditions name, so that a red result has an address rather than a description:

| What must hold | Named witness | Exact owner | Status at drafting |
|---|---|---|---|
| Ambient reads are legal exactly where the kernel says they are | The *Ambient-read extent* row of the [Phase 1 kernel risk register](lanes/adversarial-risks.md#phase-1-kernel-risks): `sub` works only during direct synchronous execution of the active body and every deferred crossing refuses with source and recovery, decided by the nested-helper, branch, loop, render-prop, event, promise, timer, lazy-sequence and module escape matrix. It runs inside the *Reactive kernel* suite of the [coverage proof suites](lanes/completeness-audit.md#canonical-suites), which is green only on zero stale reads, tears, cross-frame operations or residue | Bead `rf2-hic-011`, which owes the enforced ambient-read extent — the legality matrix and each refusal — to the recorded-freezes ledger of the [invariants record](invariants.md) | **Not yet recorded**, in the ledger's own words. Stating and enforcing the exact synchronous read extent is still an [open proof obligation](lanes/evidence-baseline.md#open-proof-obligations) |
| Ambient reads carry an ordinary application, not one screen | The *Ordinary application* suite of the [coverage proof suites](lanes/completeness-audit.md#canonical-suites): Todo and RealWorld-class flows that use only public surfaces and contain no artificial boundary introduced for the harness, with the fixtures [named there](lanes/completeness-audit.md#concrete-witness-fixtures). It is the required proof of the ordinary-pages row of [specification §7](specification.md#7-complete-use-case-coverage), whose default answer is ambient reads | Beads `rf2-hic-025` and `rf2-hic-074`, which own it jointly. `rf2-hic-025` owes the honest vertical slice — routing, keyed list, article edit, async mutation, controlled fields, error region and reset, on public namespaces only — as the [Phase 2](specification.md#phase-2--ship-one-lovable-vertical-slice) deliverable, and its Todo-class conduct is a strict subset of that RealWorld-class flow; `rf2-hic-074` owes pagination, runtime-selected content and a nested error region in that same application at [Phase 4](specification.md#phase-4--close-the-application-coverage-matrix). One application, published under the canonical suite in the [proof-suite lane](lanes/completeness-audit.md) — not a second app, and no boundary introduced for the proof. A red result from either is a lapse; neither being run yet is not | **Does not exist.** The dogfood screen is preference evidence for one list/form workload, and [evidence scope](lanes/use-cases.md#evidence-scope) names a full application and one serious vendor integration as the next useful witnesses |
| The escape route is real | Every row of the [canonical native-tier acceptance checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist), which is the only release checklist for the native tier; the *Host and native interop* suite applies it without restating it, and no variant gate list substitutes | The [hot-path lane](lanes/hot-path-architecture.md) owns the checklist and its acceptance evidence. A red row blocks publication of the native namespace, and no row is waived by success on another | **Unwitnessed.** Rungs 3 to 5 are Phase 3 work and the Hicasso-native namespace is a hypothesis |

Two of the three are unwitnessed at drafting, which is the honest shape of this instrument: the price is proposed ahead of its proof, and a red result later is what makes the acceptance reversible. Unwitnessed is not green, and no row of this table may be cited as evidence that the capability holds. Lapse fires on a red result, not on the continued absence of one; a witness that is never run leaves the record standing but never discharges it, which is what makes §7.1's reopen conditions the live path in the meantime.

On lapse the registered `1.10x` gate and its consequences resume immediately as the only adjudicated K1 line, with no grace band and no interim ceiling. A replacement acceptance requires a fresh prospective decision by the same decider. Rejection at the sitting has the same effect as a lapse from the outset — a branch that did not run: the record was ratified by ruling on 2026-08-13 and the sitting was pre-empted, so what governs from here is lapse, and lapse alone.

## 8. What ratification would not carry

- **No other threshold moves.** The read-free boundary shell stays red against the frozen byte-exact `1 KB` line; the K1 sitting does not pre-authorize its separate prospective disposition, and a relative regression allowance cannot recolour it.
- **The other kill criteria are untouched.** Bulk (K2) remains unresolved and instrument-limited with a standing release-gate obligation; per-boundary heap (K3) keeps its three non-substitutable scoreboards and owes its own disposition at the same sitting; the WebKit control matrix (K4) is open.
- **Thresholds never widen to turn a row green.** That is the kill rule this record is written under, and it binds this record itself.

## 9. Amendments to this record

**The amendment window closed on 2026-08-13.** This record was proposed while it was open — *"so it is not yet frozen: the commit that carries it into the sitting is what freezes it, and until then amendment is exactly the window a prospective instrument reserves"* — and the operator's ratifying ruling of that date froze it in place of the sitting. The rule below is what every row in the table was written under, and the sentence it already carried for this moment now governs. Amendments are prospective and never silent — each one is recorded below with its reason, in the manner the sibling [resource-demand criteria](resource-demand-criteria.md#amendment-rule) uses, and none may widen a threshold, recolour the published verdict, or settle a question this record deliberately holds open. After ratification the effective revision governs and `rf2-hic-085`, which owns that field, is the only route to a change. A **back-fill is not an amendment** — the rule `rf2-mcwm` established for that same sibling record: recording a fact decided elsewhere touches no threshold, verdict or rule, so it does not re-freeze this one. Back-fills are logged in the same table and marked as such, because the discipline this record keeps is that no change to it is silent, not that only amendments are written down.

| Date | Bead | Amendment | Thresholds and verdicts touched |
|---|---|---|---|
| 2026-08-09 | `rf2-hic-003` | Made the lapse terms executable, on a merged-PR audit of the drafting PR. §5.1 freezes the prospective rule for reading a future canonical K1 row against the ceiling; §7.2 names the two ambient-read witnesses and the native-tier checklist with their exact owners, and records that two of the three are unwitnessed. | None. The registered `1.10x` gate, the proposed `1.25x` ceiling, the published `rf2-diaud` miss and the undecided `rf2-sza0w` conjunct all stand exactly as drafted. |
| 2026-08-09 | `rf2-hic-003` | Named the ordinary-application owners, on a merged-PR audit of the amendment above. That row read "no bead owes it yet", which was already stale when it merged: `rf2-hic-025` and `rf2-hic-074` own this proof jointly and are now cited in §7.2. The witness itself is unchanged and still does not exist. | None. No witness was added, no status was promoted, and the sentence that lapse fires on a red result rather than on a missing one is unchanged. |
| 2026-08-10 | `rf2-hr9s` | **A back-fill, not an amendment.** The operator's K1 ruling of 2026-08-10 (`rf2-sza0w`) is recorded where this record was silent about it: §2.2 now reads *ruled MET* and keeps its drafting text verbatim beneath, the §6 adjudicated-status and reopen-condition rows follow it, §7.1's first condition is marked **fired**, and the preamble's "only one half is adjudicated" is dated to drafting. Nothing here decides what the fired condition asks. | None. The registered `1.10x` gate, the proposed `1.25x` ceiling, the frozen §5.1 comparison rule and the published `rf2-diaud` miss are untouched, and no criterion changed, so the record does not re-freeze. Whether the reconsidered price stands or changes is the decider's, is unanswered in either direction, and is now recorded as pending rather than left silent. |
| 2026-08-11 | `rf2-hg2z` | **A back-fill, not an amendment.** Names the **forum** for the reconsideration that §7.1's fired first condition calls for: it is reserved to the 2026-08-27 sitting this record is already held for, unless the decider answers sooner, and `rf2-hic-085` records what is decided there. §2.2, the §6 reopen-conditions row and §7.1's fired bullet each now say so. The record had said only that the answer was *the decider's, and pending*, leaving a reader of the sitting packet to infer where it would be given — and leaving an undischarged reopen looking like a defect rather than its expected state. | None. No threshold, verdict, criterion or rule moved: the registered `1.10x` gate, the proposed `1.25x` ceiling, the frozen §5.1 comparison rule and the published `rf2-diaud` miss are untouched. The reconsideration itself is still unanswered in both directions — only its venue is now stated — and the effective-revision field stays blank for `rf2-hic-085` to fill after the sitting. |
| 2026-08-13 | `rf2-2rtt6.144` | **A back-fill, not an amendment — and the one that closes this table.** Records that the operator RATIFIED this record on 2026-08-13 at 04:57 AUSEST, in chat, by ruling the P2 fork *graduate, as a success*, pre-empting the 2026-08-27 sitting. Graduating as a success with §2.1's canonical miss on the table is the acceptance of the price this record prices, so ratification came by ruling rather than by meeting. The preamble now reads RATIFIED and operative; §2.2 and §7.1's first bullet record the fired reconsideration as **answered — the price stands**; §5's ceiling is operative; §6's effective-revision field is filled from the ruling and its reopen row follows. | None. **Nothing this record prices moved.** The registered `1.10x` gate, the published `rf2-diaud` miss, the `1.25x` figure, the frozen §5.1 comparison rule, §3's purchased jobs, §4's escape route and §7's reopen and revert conditions are all exactly as drafted; K1 remains recorded MISSED, DECISIVELY, and no evidence row may cite this record to colour it green. What changed is status and forum: proposed → ratified, sitting → ruling. |

## Sources

- [`decision-brief.md`](decision-brief.md) — Part I finding 4 (the priced mount premium and the amendment's role), the scoreboard, and the sitting's agenda.
- [`specification.md` §6](specification.md#6-performance-contract) — the proposed ceiling, the sitting rule, the required record fields, and the lapse conditions.
- [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence) — the pinned cold-mount row, the K1 price-proposal status, and the measurement posture's governance-change shape.
- [`../studio/rows-re-adjudicated-on-the-corrected-clock.md` §4.3](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) — `rf2-diaud` / PR #7704, the published M1 row and its labels.
- [`../validation.md`](../validation.md) — the kill-criteria table and the `rf2-hyd50` denominator amendment.
- [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) — the ladder and the native-tier acceptance checklist.
- [`lanes/adversarial-risks.md`](lanes/adversarial-risks.md#phase-1-kernel-risks) — the ambient-read-extent kernel risk, its required contract and its deciding witness.
- [`lanes/completeness-audit.md`](lanes/completeness-audit.md#canonical-suites) — the reactive-kernel and ordinary-application proof suites that carry those witnesses, and their green conditions.
- [`lanes/corpus-insights.md`](lanes/corpus-insights.md#the-performance-premium-must-buy-a-product-capability) and [`lanes/use-cases.md`](lanes/use-cases.md#evidence-scope) — the census, its weight, and its declared limits.
