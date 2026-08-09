# K1 price-acceptance amendment record

**Status: PROPOSED. Not operative.** This is the scoped amendment instrument prepared in Phase 0 and held for the sitting of **2026-08-27**, where **Mike Thompson, acting as re-frame2 product operator, is the decider**. Nothing on this page is in force until he ratifies it. Until then the registered `1.10x` K1 gate remains the only adjudicated mount line, the proposed `1.25x` ceiling may not mark any result green, and rejection at the sitting leaves the registered gate and its consequences in force. The [primary performance contract](specification.md#6-performance-contract) owns the proposed ceiling and the sitting rule; the [evidence baseline](lanes/evidence-baseline.md#k1-price-proposal) records the same pending status; the [decision brief](decision-brief.md) owns the selection this record is the honest bill for.

Three things this record deliberately does not do. It does not recolour the measured result: the miss in §2 is quoted as published, and no threshold anywhere in this tree widens to accommodate it. It does not decide whether the K1 *kill criterion* has tripped — that criterion is a conjunction and only one half is adjudicated. And it does not waive the red read-free boundary-shell row, which the performance contract keeps as a separate prospective disposition that this sitting does not pre-authorize.

## 1. The frozen registered criterion

Frozen verbatim from the kill-criteria table in the [validation register](../validation.md) as it stands at drafting, so that ratification cannot later be read back onto a line that moved:

```text
| K1 | Mount above the amended mount gate (1.10× direct UIx on the clock of record) after two serious runtime iterations |
```

The denominator is **direct UIx-on-subs on the clock of record** — the mount-gate amendment of 2026-08-02 with its 2026-08-05 consistency edit (`rf2-hyd50`), which replaced the stale Reagent denominator. There is no second mount condition: the mount gate is one line, and Reagent-on-subs is co-instrumented and reported beside the mount row rather than gating it.

## 2. The measured position

The criterion has two conjuncts. They stand differently, and this record keeps them apart.

### 2.1 The gate half: missed, decisively

`rf2-diaud` (PR #7704) published the M1 mount row on K1's own floor-normalised estimand — point and interval drawn from one estimator through the same run-preserving bootstrap, 4,000 draws at the fixed seed 20260807 — and adjudicated it against K1's own threshold:

| Ensemble | Outer runs | `hicasso / uix-subs`, floor-normalised | Verdict against K1's `1.10×` |
|---|---:|---|---|
| `clock-emvod` | 8 | **1.1718×** [1.1263 – 1.2190] | whole interval above — **K1 MISSED** |
| `clock-w3yxd` | 6 | **1.1976×** [1.1504 – 1.2468] | whole interval above — **K1 MISSED** |

Both intervals lie entirely above the gate on the losing side, so the mount premium is a magnitude and not a direction: the candidate mounts at roughly **1.17–1.20x direct UIx-on-subs**, and the gate is missed on two independently launched ensembles. This is the canonical K1 record and it is never restated as anything softer.

The figures, the co-instrumented Reagent-on-subs pairs, the labelled unfloored diagnostic, and the residual-uncertainty caveats live at [§4.3 of the corrected-clock re-adjudication](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) and are quoted from nowhere else. Those caveats travel with the row into this record: eight and six outer runs are thin ensembles, the mount check standard was calibrated on the same fourteen runs it then judges, and both ensembles record the same Chromium build. The [evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence) owns the pinned values.

### 2.2 The trigger half: undecided

Whether the candidate has had **two serious runtime iterations** is a programme judgement rather than a measurement, and no document in this tree decides it. It is an open operator ruling, tracked as `rf2-sza0w`, which also owes what K1's "stop or narrow" consequence would mean in practice were the condition found met.

This record neither decides that question nor depends on its answer. What it prices is the measured mount premium in §2.1 — a fact independent of whether the kill criterion is later ruled to have tripped.

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

**`1.25x` cold mount against equivalent direct UIx on the agreed representative witness**, as an initial ceiling, subject to the ratification named above. Read topology and capability differences are reported alongside the ratio; the ratio never travels alone.

The measured position sits between the two lines: above the registered `1.10x` gate on every corroborated reading, and below `1.25x` including the upper bound of the wider interval (1.2468). That interval is precisely what this record asks to buy, and it is why the instrument is a scoped, reversible acceptance rather than a re-registration of the gate.

Ratification would record an accepted price. It would not convert the published miss into a pass, and **no evidence row may cite this record to colour K1 green**.

## 6. The record

The fields any governance change owes under the [measurement posture](lanes/evidence-baseline.md#measurement-posture), together with the additional fields the [performance contract](specification.md#6-performance-contract) requires of this one:

| Field | Value |
|---|---|
| Frozen registered criterion | The K1 row quoted verbatim in §1 — mount above `1.10x` direct UIx on the clock of record, after two serious runtime iterations |
| Adjudicated status of that criterion | Gate half: **MISSED, DECISIVELY** (`rf2-diaud` / PR #7704), §2.1. Trigger half: **undecided**, open operator ruling `rf2-sza0w`, §2.2 |
| Purchased use cases | The read-capability jobs of §3.2, at their census weights |
| Accepted ceiling | `1.25x` cold mount versus equivalent direct UIx on the agreed representative witness — **proposed**, inoperative until ratified |
| Native escape | The five-rung gradient of §4, gated on the canonical native-tier acceptance checklist |
| Decider | Mike Thompson, acting as re-frame2 product operator |
| Evidence owner | [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence) for the pinned values; [corrected-clock §4.3](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) for the published figures and their labels |
| Effective revision | *(left blank — the sitting fills this on ratification)* |
| Reopen conditions | §7.1 |
| Revert condition | §7.2 |

## 7. Reopen and revert

### 7.1 Reopen conditions

Any of these returns this record to the decider, whether or not it has been ratified:

- `rf2-sza0w` rules on the two-serious-runtime-iterations conjunct. A ruling that K1 has tripped changes what "stop or narrow" asks of the programme, and the accepted price must be reconsidered in that light.
- A new clean clock-of-record acceptance row is published on the mount estimand.
- A named lever plausibly worth the registered `45.7–49.4%` threshold of the measured deficit appears, or the compiler / render-phase / witness-set fences change explicitly. These are the baseline's own reopen conditions for the bounded irreducibility conclusion, and they reopen the price with it.
- The comparative budgets are ratified against named reference hardware and the representative witness changes as a result.

### 7.2 Revert condition

The record **lapses** — automatically, without amendment in place — on any of:

- The purchased ambient-read capability fails its named witness.
- The native escape fails its named witness; any red row on the canonical native-tier acceptance checklist is such a failure.
- The canonical K1 row exceeds the accepted ceiling.
- The witness or the estimator changes materially without re-ratification.

On lapse the registered `1.10x` gate and its consequences resume immediately as the only adjudicated K1 line, with no grace band and no interim ceiling. A replacement acceptance requires a fresh prospective decision by the same decider. Rejection at the sitting has the same effect as a lapse from the outset.

## 8. What ratification would not carry

- **No other threshold moves.** The read-free boundary shell stays red against the frozen byte-exact `1 KB` line; the K1 sitting does not pre-authorize its separate prospective disposition, and a relative regression allowance cannot recolour it.
- **The other kill criteria are untouched.** Bulk (K2) remains unresolved and instrument-limited with a standing release-gate obligation; per-boundary heap (K3) keeps its three non-substitutable scoreboards and owes its own disposition at the same sitting; the WebKit control matrix (K4) is open.
- **Thresholds never widen to turn a row green.** That is the kill rule this record is written under, and it binds this record itself.

## Sources

- [`decision-brief.md`](decision-brief.md) — Part I finding 4 (the priced mount premium and the amendment's role), the scoreboard, and the sitting's agenda.
- [`specification.md` §6](specification.md#6-performance-contract) — the proposed ceiling, the sitting rule, the required record fields, and the lapse conditions.
- [`lanes/evidence-baseline.md`](lanes/evidence-baseline.md#pinned-economic-evidence) — the pinned cold-mount row, the K1 price-proposal status, and the measurement posture's governance-change shape.
- [`../studio/rows-re-adjudicated-on-the-corrected-clock.md` §4.3](../studio/rows-re-adjudicated-on-the-corrected-clock.md#43-the-published-m1-row) — `rf2-diaud` / PR #7704, the published M1 row and its labels.
- [`../validation.md`](../validation.md) — the kill-criteria table and the `rf2-hyd50` denominator amendment.
- [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) — the ladder and the native-tier acceptance checklist.
- [`lanes/corpus-insights.md`](lanes/corpus-insights.md#the-performance-premium-must-buy-a-product-capability) and [`lanes/use-cases.md`](lanes/use-cases.md#evidence-scope) — the census, its weight, and its declared limits.
