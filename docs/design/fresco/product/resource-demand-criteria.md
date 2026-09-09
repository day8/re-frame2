# Resource-demand criteria (pre-registered)

Demand-driven resource ownership is the strategic differentiator of the Hicasso programme: a committed `sub` that reads a resource may also declare demand, and unmount or parameter change releases it. This document freezes the criteria that decide whether it graduates, **before** the typeahead witness (`rf2-hic-044`) produces any data. Criteria written after a report exists are not pre-registration; they are a rationalisation of whatever the report happens to say.

Nothing here was measured. Every line below is derived from the [specification](specification.md#7-complete-use-case-coverage), the [decision brief](decision-brief.md), the [design laws](lanes/design-laws.md#state-and-reactivity) and the [charter](../charter.md)'s named goal. No benchmark was run to write it, and no existing result was consulted to place a line.

## Pre-registration record

| Field | Value |
|---|---|
| Subject | Demand-driven resource ownership (committed-read resource demand), the spec §7 flagship experiment |
| Registered by | `rf2-hic-039`, on the specification and lanes alone |
| Frozen | 2026-08-09 AUSEST, before `rf2-hic-044` starts |
| Evidence source | The `rf2-hic-044` typeahead report, and nothing else |
| Applied by | `rf2-hic-050`, mechanically |
| Default verdict | STOP. ADOPT requires every criterion met on published, re-checkable evidence |
| Amendment | Prospective only — see [Amendment rule](#amendment-rule) |
| Original freeze | `c8b8de6d28`, registering all seven criteria |
| Pre-registration commit | `afbb58febc`, the effective revision after C2's amendment — this is the hash to cite |

**The effective revision is the pre-registration proof.** `rf2-hic-044` records its instrumentation against these criteria by citing `afbb58febc`, and `rf2-hic-050` cites it again when it applies them, because that is the revision carrying the criteria text they actually apply. The original freeze `c8b8de6d28` remains the provenance of C1 and C3–C7, which have not changed a byte since it; only C2 was amended, and only prospectively. A verdict that cannot cite a criteria commit predating the report has not been pre-registered, whatever the dates claim.

**Post-freeze edits, recorded here rather than made silently.** Two landed after the original freeze and before `rf2-hic-044` began, which is the window the [amendment rule](#amendment-rule) reserves, and both landed as `afbb58febc4e0a28e67a2a172bb604f0570472b5`: the commit row was back-filled, and C2's closing sentence was corrected. That sentence had let unregistered defect classes count toward C2's threshold, contradicting the same criterion's statement that its classes are fixed in advance so no flattering class can be invented after the fact. Unregistered classes are still reported; they no longer count unless registered prospectively. No threshold moved, and nothing else changed. C2's correction is a prospective amendment, so the file re-froze there, and the rows above now name that revision.

**A back-fill is not an amendment.** Those rows were themselves written afterwards, by `rf2-mcwm`, because no commit can contain its own hash — the same reason the original freeze could not carry `c8b8de6d28`. Recording provenance touches no criterion, so it does not re-freeze the file: the effective revision stays `afbb58febc`, whose criteria text is byte-for-byte the text below. Only a change to a criterion moves it.

## What is being decided

In scope: whether a committed read may become the causal owner of resource demand, so that acquisition and release follow read liveness rather than hand-written correlation.

Out of scope, and not evidence for or against: the four explicit policies the specification keeps explicit under demand as well (debounce, stale-reply suppression and supersession, refresh-with-data, cancellation); the async-resource recipes of `rf2-hic-054`; prefetch that no read wants; any performance budget, which the [performance contract](specification.md#6-performance-contract) owns separately and ratifies elsewhere.

## The criteria

### C1 — Ceremony removed, counted

The claim is that demand retires hand-written correlation between read liveness and resource liveness. A claim of that shape is settled by a census, not by an impression.

A **ceremony site** is one contiguous region of witness source whose only job is to keep resource liveness correlated with read liveness. Each site is counted once, cited by file and line range so the count is re-checkable from the report without re-running anything, and classified exactly once:

| Class | Definition | Claimable by demand |
|---|---|---|
| OWNERSHIP | Exists only because nothing owns the correlation between "this read is live" and "this resource is wanted" | Yes |
| POLICY | Debounce, stale-reply suppression, supersession, refresh-with-data, cancellation | No — these stay explicit under demand |
| DOMAIN | Application logic that would exist under any mechanism | No |

STOP unless all of the following hold. The census is published and complete. OWNERSHIP is non-empty and contains at least one acquire site and at least one release site — an empty or acquire-only census means there is nothing to buy, which is the no-paying-witness result. Every OWNERSHIP site is marked removed-by-demand or surviving, and none survives: a mechanism that takes over part of the correlation adds a concept without retiring one. No POLICY or DOMAIN site is counted as removed; a report that credits demand with policy removal voids its own count.

A site believed misclassified may be re-classified in the report, with its reason, under the definitions in the table above. `rf2-hic-050` checks that re-classification against this document; it never invents a fourth class.

### C2 — Defect classes killed, named

Centralisation is justified when the law is subtle, common and testable, and it earns its place by removing defects that hand-written code otherwise reproduces — the reasoning [the corpus already applies to the controlled-input laws](lanes/corpus-insights.md#centralized-laws-are-an-ergonomic-feature). So the classes must be named, and their reachability demonstrated on the status quo.

These are the pre-registered candidates, fixed here so that no flattering class can be invented after the fact:

| Class | Failure it produces | Kind |
|---|---|---|
| Late reply clobbers a newer edit | Wrong value shown to the user | Correctness |
| Demand outlives the read that wanted it | Retained work and bytes after teardown | Residue |
| Duplicate acquisition on remount, retry or StrictMode double-invoke | Repeated side effect, duplicate fetch | Correctness and waste |
| Acquisition for a render that never commits | Work and requests for nothing | Waste |
| Orphaned in-flight request after a parameter change | Waste, and a source of late replies | Waste |
| Missed release on a conditional-false read | Retained work and bytes after the read stops | Residue |

Each class the report claims carries three things: the mechanism, a reachability demonstration on the witness — a mutation that makes the hand-written answer actually exhibit the defect, so an accidentally unreachable class cannot pass — and a statement of whether demand makes the class unreachable *by construction* or merely easier to avoid.

STOP unless at least two classes are killed by construction, at least one of them producing a wrong user-visible result or residue that survives teardown. "Easier to avoid" is never killed: a class whose only remedy is developer discipline or a documented step is exactly what a recipe is for, and `rf2-hic-054` already owns that answer. An unregistered class may still be reported, and carries the same reachability demonstration or is inadmissible; but it does not count toward this threshold unless it was added to the table above prospectively, under the [amendment rule](#amendment-rule), before the `rf2-hic-044` report exists. Otherwise the threshold could be met with classes chosen after the data, which is exactly what fixing the table in advance exists to prevent.

### C3 — Zero acquisition on abandoned renders

Render is speculative and owns nothing durable; demand-driven resources cannot acquire during an abandoned render. This is a law, so it is a veto, not a score.

`rf2-hic-044` changes no runtime, so it cannot measure an implementation that does not exist. What it must establish is that the fence is *gateable*: that abandoned renders genuinely occur on the witness and the instrument can name them with a counted population — React abandonment and retry, and the StrictMode double-invoke — and that the planned acquisition point is stated as post-commit only, with render pure.

STOP if the abandonment population is empty, unstated, or unobservable, or if the design sites acquisition anywhere in render. An unexhibitable fence cannot be gated on the implementation, and an ungateable fence is not a fence.

On ADOPT this becomes a blocking acceptance test inherited by the implementation bead: force an abandoned render, assert zero acquisitions, with the forced abandonment as the positive control that proves the test can fail.

### C4 — Reuse of committed read membership

Demand rides the read membership the commit already established. It does not register anything of its own.

The report states, for every demand the typeahead needs, whether it is derivable from a committed read's identity and parameters alone, and lists any demand that is not — prefetch on hover, focus or touch, speculative warm-up, and any demand with no reader are the known cases.

STOP unless every in-scope demand is derivable from committed read membership with no additional registration and no side table keyed by anything commit does not already know. A demand the membership cannot express is recorded as out of scope and routed to the recipes; if the report instead proposes widening the mechanism to reach it, that is C5's second ledger under another name, and it stops here.

### C5 — No second per-read ledger

The predecessor's ViewCell and observation ledger carried a correct guarantee on a mechanism that had to be abandoned. The guarantee transfers; the object graph must not come back. The recogniser is pre-registered so it cannot be defined away later:

A **second per-read ledger** is any retained structure that holds one entry per read, or per read-and-boundary pair, that has a lifecycle of its own requiring maintenance in step with commit and disconnect, and that can therefore drift from the committed read membership.

The report states the exact retained per-read and per-boundary structures of the status quo and the planned design's delta against them.

STOP if the delta introduces any structure meeting the recogniser, however small and however fast. Demand state is keyed by resource; a read contributes membership, not a record.

### C6 — No boundary-shell change

The two-hook boundary shell is a ceiling, and capability pays rent only where it is used. A typeahead facility may cost nothing on a boundary that reads no resource. The registered read-free shell line is already red on the [pinned evidence](lanes/evidence-baseline.md#pinned-economic-evidence), so there is no headroom to spend even if spending it were otherwise acceptable.

The report states where demand state and its lifecycle would live, and shows that a boundary with no resource read touches none of it.

STOP if the design adds a hook to the boundary shell, a field to the read-free shell, or anything at all to the do-nothing path.

On ADOPT the implementation bead inherits a blocking gate: measured zero delta on the pinned read-free-shell and do-nothing controls, with any delta as a revert trigger.

### C7 — Ambiguous evidence = STOP

This is the rule that makes the other six binding, so its trigger is defined rather than left to judgement. Evidence is ambiguous when any of the following is true:

- a criterion's evidence is absent, incomplete, or not re-checkable from the report alone;
- a criterion turns on a judgement that cannot be reduced to the published census, the named classes, or the stated design;
- an instrument fails its own validity check, or publishes without the control that could have made it fail;
- a population is empty or unstated where a criterion requires one;
- criteria conflict, and the report does not resolve the conflict from its own evidence;
- deciding would require re-running the witness.

Ambiguity resolves to STOP. It is not a request for more evidence, an invitation to re-run, or a deferral: `rf2-hic-050` publishes STOP and records the ambiguity, and the reopen conditions below govern any later attempt.

## The criteria at a glance

| # | Criterion | STOP when |
|---|---|---|
| C1 | Ceremony removed, counted | Census absent or incomplete; OWNERSHIP empty or acquire-only; any OWNERSHIP site survives; POLICY or DOMAIN counted as removed |
| C2 | Defect classes killed, named | Fewer than two killed by construction; none producing wrong output or surviving residue; a class claimed without a reachability demonstration |
| C3 | Zero acquisition on abandoned renders | Abandonment unexhibited, uncounted or unobservable; acquisition sited in render |
| C4 | Reuse of committed read membership | A demand needs registration commit does not already carry; the mechanism is widened to reach a demand no read expresses |
| C5 | No second per-read ledger | Any retained per-read structure with its own lifecycle that can drift from committed membership |
| C6 | No boundary-shell change | Any added hook, field or cost on the universal shell or the do-nothing path |
| C7 | Ambiguous evidence | Any of the ambiguity triggers above |

## What is not a criterion

Deliberately excluded, so that a strong showing on any of them cannot substitute for a criterion above: clock or heap improvement on the typeahead, which the performance contract owns and ratifies separately; author preference or "it reads better" unsupported by the C1 census; novelty, differentiation, or the observation that no comparable system offers the feature, which are marketing facts rather than evidence; and any number produced after this file's commit that would move a line. Thresholds do not widen to turn a row green.

## Amendment rule

Amendments are prospective. Before `rf2-hic-044` opens its PR, the product operator may record an amendment in this file naming the reason and the effective revision; the file then re-freezes at a new commit, and that hash is the one `rf2-hic-044` and `rf2-hic-050` cite.

Once the `rf2-hic-044` report exists, this document is closed. A criterion believed wrong at that point is recorded as a dissent inside the verdict, and the verdict still follows the frozen rule. That is the whole value of freezing it.

## Verdict procedure

`rf2-hic-050` applies these criteria mechanically:

1. Cite this file's pre-registration commit.
2. For each criterion C1–C6, record MET, NOT MET, or AMBIGUOUS, with the location in the `rf2-hic-044` report that decides it.
3. Any NOT MET or any AMBIGUOUS gives STOP. All six MET gives ADOPT.
4. Take no other input.

The flow is ungated: the verdict publishes and the programme proceeds on it. The operator may veto asynchronously, and the verdict record says so.

## Consequences

**ADOPT.** File the implementation bead separately rather than growing the verdict — a committed `sub` declares demand; release follows unmount, parameter change and conditional-false; debounce, supersession, refresh-with-data and cancellation stay explicit policies. That bead carries the gates inherited from C3 and C6, and joins the dynamic-tail roster as a predecessor of the naming packet (`rf2-hic-065`), the docs bead (`rf2-hic-068`), the erasure re-proof (`rf2-hic-072`) and the final audit (`rf2-hic-064`). It also acquires an inventory id and a row in the [canonical SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix), which already anticipates a resource-demand boundary.

**STOP.** Record which criterion failed and on what evidence, and bless `rf2-hic-054`'s async-resource recipes as the standing answer. The witness and these criteria remain on file as the reopen basis.

## Reopen conditions and revert trigger

A STOP reopens only on new evidence of a kind this witness could not supply: a second application whose ownership census the recipes demonstrably cannot answer, a defect class arriving repeatedly from real consumer code, or a change to the committed-read membership contract that makes a previously inexpressible demand expressible. Preference, novelty, and a re-reading of the same report are not reopen conditions.

An ADOPT that ships reverts on any inherited gate going red: a non-zero acquisition on an abandoned render, a retained structure meeting the C5 recogniser, or any delta on the read-free-shell or do-nothing controls.

## Provenance

The flagship paragraph and its three fences are in [specification §7](specification.md#7-complete-use-case-coverage); the same fences, with the failed-ledger warning, are in the [decision brief](decision-brief.md). The governing law is [design law State 7](lanes/design-laws.md#state-and-reactivity), with the shell ceiling at State 6 and the rent rules under [economics and scope](lanes/design-laws.md#economics-and-scope). The evidence conduct these criteria assume — named populations, controls that can fail, prospective governance records — is [evidence and tools](lanes/design-laws.md#evidence-and-tools). The design-time shape of the goal, including post-commit ensure and `demand ≠ retention`, is the named goal in the [charter](../charter.md). The caution that lifecycle machinery can create a second dependency ledger is in [motivating use cases](lanes/use-cases.md). The instruction to split criteria registration ahead of the witness, and to default to STOP on ambiguity, is defaults taken, row 4, of the bead-set review — `codex/beads-review.md`, review-staging material in the operator-local set, deliberately not published in this tree.
