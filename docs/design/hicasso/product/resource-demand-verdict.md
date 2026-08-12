# The committed-read resource-demand verdict

> **PRE-REGISTERED, NOT YET APPLIED.** This commit fixes the procedure, the address in the evidence that decides each criterion, and the reading that each possible answer produces. No label is recorded and the verdict section below is deliberately empty. Both are filled by later commits on this branch, so that the ordering in the history is itself the proof that the reading rules were not chosen to fit the answer.

`rf2-hic-050` decides the flagship experiment of the Hicasso programme: whether a committed `sub` that reads a resource may also declare demand for it, so that acquisition and release follow read liveness instead of hand-written correlation.

The decision is not this page's to invent. It was frozen in [`resource-demand-criteria.md`](resource-demand-criteria.md) before any data existed, and it is applied here to [`resource-demand-witness.md`](resource-demand-witness.md) and to nothing else. This page's whole job is to carry out four steps someone else wrote down.

Under the operator ruling `rf2-xpq9` of 2026-08-12 17:36 AUSEST, a decision-shaped Phase 5 item is complete when its spike has run and its pre-registered verdict has been made — *"a ruled STOP still completes the item; completion is the verdict, not forced adoption."* The verdict below is that completion, whichever way it reads.

## The pre-registration, and how to check it

| Field | Value |
|---|---|
| Criteria | [`resource-demand-criteria.md`](resource-demand-criteria.md), registered by `rf2-hic-039` |
| Original freeze | Producing commit `c8b8de6d28`, registering all seven criteria |
| Effective revision | Producing commit `afbb58febc`, after C2's prospective amendment — the revision this verdict applies and the hash the criteria file itself says to cite |
| Evidence | The `rf2-hic-044` report at [`resource-demand-witness.md`](resource-demand-witness.md), and nothing else |
| Default verdict | STOP. ADOPT requires every criterion met on published, re-checkable evidence |
| Ambiguity rule | Ambiguous evidence resolves to STOP — [C7](resource-demand-criteria.md#c7--ambiguous-evidence--stop) |
| Flow | Ungated. The verdict publishes and the programme proceeds on it; the operator may veto asynchronously |

**The ordering is structural, not a claim about dates.** `afbb58febc` is an ancestor of every `rf2-hic-044` commit — of the report at `53a747ae6a` and of the witness application at `bade358ae5` alike, both checked with `git merge-base --is-ancestor`. A rebase rewrites a hash; it cannot make a descendant precede its own ancestor. So the criteria demonstrably predate the data whatever the timestamps say, which is the property pre-registration actually needs.

**The criteria text has not moved since that revision.** `git diff afbb58febc HEAD -- docs/design/hicasso/product/resource-demand-criteria.md` returns two hunks: the pre-registration record rows with the prose immediately around them, and the Provenance paragraph, where `rf2-hic-091` replaced a link to an unpublished lane with prose naming it. C1 through C7, the glance table, *What is not a criterion*, the amendment rule, the verdict procedure, the consequences and the reopen conditions are byte-identical. The file's own [amendment rule](resource-demand-criteria.md#amendment-rule) says a provenance back-fill is not an amendment, and the diff is the check rather than the assurance. The file's blob today is `b7dc0f3e3448e49f56fdcfcc360ef04d8f281bda`.

## What this branch's own ordering proves, and what it does not

Honesty about the pre-registration is worth more than a strong claim about it, so both halves are stated.

**What it does not prove.** The `rf2-hic-044` report landed on `main` before this branch existed. Nothing in this branch's history can show that its author had not read the numbers — they were public, and reading them is the assignment. This is not the situation of a spike that registers a comparator and then runs it.

**What it does prove, and what actually carries the weight.** The load-bearing pre-registration is `rf2-hic-039`'s, and it is genuine in the strong sense: the criteria commit is an ancestor of the witness commits, so the rule was fixed before the evidence existed. What *this* branch's ordering adds is narrower and still worth having — the reading rules below, the address in the report that decides each criterion, and the consequences of both outcomes are committed here, in the first commit, with every label reading `NOT YET APPLIED`. A later commit fills the labels. Anyone can read the diff of this commit and see that no row's answer was available when its rule was written.

## The procedure, transcribed

From [`resource-demand-criteria.md`](resource-demand-criteria.md#verdict-procedure), verbatim:

1. Cite this file's pre-registration commit.
2. For each criterion C1–C6, record MET, NOT MET, or AMBIGUOUS, with the location in the `rf2-hic-044` report that decides it.
3. Any NOT MET or any AMBIGUOUS gives STOP. All six MET gives ADOPT.
4. Take no other input.

Step 4 is the one with teeth, and it is what this page's fences are for. No figure below is measured here. No suite is re-run. No second source is consulted for a number the report already carries, because a second source for one number is a second thing that can drift.

## The reading rules, fixed before any label is recorded

Each row states the frozen test, the address in the witness report that decides it, and what each possible reading produces. The label column is filled by a later commit.

### C1 — Ceremony removed, counted

**The frozen test.** The census is published and complete; OWNERSHIP is non-empty and contains at least one acquire site and at least one release site; every OWNERSHIP site is marked removed-by-demand or surviving, and none survives; no POLICY or DOMAIN site is counted as removed.

**Decided by** [the census](resource-demand-witness.md#c1--ceremony-removed-counted) and the sections under it.

- **MET** when all four hold.
- **NOT MET** when OWNERSHIP is empty or acquire-only, when any site is marked surviving, or when a POLICY or DOMAIN site is credited to demand.
- **AMBIGUOUS** when an element the criterion names is missing from the report, including the per-site removed-or-surviving marking, since C7's first trigger covers evidence that is absent or incomplete.

| Label |
|---|
| NOT YET APPLIED |

### C2 — Defect classes killed, named

**The frozen test.** At least two of the six pre-registered classes are killed *by construction*, at least one of them producing a wrong user-visible result or residue that survives teardown; each claimed class carries its mechanism, a reachability demonstration on the witness, and a statement of whether demand kills it by construction or merely makes it easier to avoid. "Easier to avoid" is never killed. An unregistered class may be reported but counts toward nothing.

**Decided by** [the class table](resource-demand-witness.md#c2--defect-classes-killed-named).

- **MET** when the threshold is reached on registered classes with demonstrations attached.
- **NOT MET** when fewer than two are killed by construction, when none of those produces wrong output or surviving residue, or when a claimed class arrives without its reachability demonstration.
- **AMBIGUOUS** when a class is claimed on evidence the report does not publish.

| Label |
|---|
| NOT YET APPLIED |

### C3 — Zero acquisition on abandoned renders

**The frozen test.** This is a veto, not a score. The report must establish that the fence is *gateable*: that abandoned renders genuinely occur on the witness and that the instrument can name them with a counted population — the criterion names two mechanisms, React abandonment and retry, and the StrictMode double-invoke — and that the planned acquisition point is stated as post-commit only, with render pure.

**Decided by** [the abandonment population](resource-demand-witness.md#c3--zero-acquisition-on-abandoned-renders).

- **MET** when a counted population is published for the mechanisms the criterion names, it is non-empty and observable on the witness, and acquisition is sited post-commit.
- **NOT MET** when the population is empty or unobservable, or when the design sites acquisition anywhere in render.
- **AMBIGUOUS** when a population the criterion requires is unstated in the report, which is C7's fourth trigger, or when the criterion turns on a judgement the report hands to the verdict and that judgement reduces to none of the published census, the named classes or the stated design, which is C7's second.

| Label |
|---|
| NOT YET APPLIED |

### C4 — Reuse of committed read membership

**The frozen test.** Every in-scope demand is derivable from committed read membership with no additional registration and no side table keyed by anything commit does not already know. A demand the membership cannot express is recorded out of scope and routed to the recipes; proposing to widen the mechanism to reach it stops here.

**Decided by** [the derivability table](resource-demand-witness.md#c4--reuse-of-committed-read-membership).

- **MET** when every in-scope demand is derivable and every other demand is recorded out of scope without widening.
- **NOT MET** when a demand needs registration commit does not already carry, or when the report proposes widening the mechanism to reach a demand no read expresses.
- **AMBIGUOUS** when a demand's derivability is asserted without the read that implies it being named.

| Label |
|---|
| NOT YET APPLIED |

### C5 — No second per-read ledger

**The frozen test.** The report states the exact retained per-read and per-boundary structures of the status quo and the planned design's delta against them, and the delta introduces no structure holding one entry per read, or per read-and-boundary pair, with a lifecycle of its own that could drift from committed membership.

**Decided by** [the retained-structure census](resource-demand-witness.md#c5--no-second-per-read-ledger).

- **MET** when both the status quo and the delta are stated and nothing in the delta meets the recogniser.
- **NOT MET** when the delta introduces any structure meeting it, however small and however fast.
- **AMBIGUOUS** when the status-quo structures are not enumerated, or the delta is not stated against them.

| Label |
|---|
| NOT YET APPLIED |

### C6 — No boundary-shell change

**The frozen test.** The report states where demand state and its lifecycle would live, and shows that a boundary with no resource read touches none of it. STOP if the design adds a hook to the boundary shell, a field to the read-free shell, or anything at all to the do-nothing path.

**Decided by** [the shell section](resource-demand-witness.md#c6--no-boundary-shell-change).

- **MET** when both statements are made and the design adds no hook, no field and no cost to the do-nothing path.
- **NOT MET** when the design adds any of the three.
- **AMBIGUOUS** when the report does not say where demand state would live, or does not address the read-free boundary at all.

**A distinction fixed here, before it can be convenient.** C6 asks for a statement and a showing about a design; the *measurement* it also names — zero delta on the pinned read-free-shell and do-nothing controls — is explicitly an inherited gate that the implementation bead carries **on ADOPT**. A report that measures no bytes is therefore not, on that ground alone, incomplete for C6. If it were, C6 would be unmeetable by any witness that changes no runtime, and `rf2-hic-044` was commissioned as exactly such a witness.

| Label |
|---|
| NOT YET APPLIED |

## The ambiguity triggers, transcribed

C7 exists so that the other six bind, and its triggers are enumerated rather than left to taste. Verbatim, evidence is ambiguous when any of the following is true:

1. a criterion's evidence is absent, incomplete, or not re-checkable from the report alone;
2. a criterion turns on a judgement that cannot be reduced to the published census, the named classes, or the stated design;
3. an instrument fails its own validity check, or publishes without the control that could have made it fail;
4. a population is empty or unstated where a criterion requires one;
5. criteria conflict, and the report does not resolve the conflict from its own evidence;
6. deciding would require re-running the witness.

Two consequences of C7 are fixed here so they cannot be softened later. **Ambiguity is not a request for more evidence.** C7 says so in terms: it is "not a request for more evidence, an invitation to re-run, or a deferral". So a STOP taken on an ambiguity trigger does not produce a bead to close the gap and try again — [the reopen conditions](resource-demand-criteria.md#reopen-conditions-and-revert-trigger) govern instead, and they exclude a re-reading of the same report. **And the report's own flags do not decide.** `rf2-hic-044` nominates several questions for the verdict; each is tested against the six triggers above, and a nomination that fires none of them is recorded MET.

## What would make this ADOPT

Written before the labels, so that the procedure cannot be said to have been aimed. ADOPT requires all six of these together, and each names the specific thing the report would have to contain:

- **C1** — the census as published, with each of its OWNERSHIP rows carrying an explicit removed-by-demand or surviving marking, and none reading surviving.
- **C2** — two or more registered classes killed by construction, each with its reachability demonstration, at least one producing a wrong user-visible result or residue that survives teardown.
- **C3** — a counted, non-empty abandonment population on the witness for the mechanisms the criterion names, with acquisition sited post-commit and render pure.
- **C4** — every in-scope demand derivable from a committed read's identity and parameters, with the out-of-scope ones routed to the recipes rather than reached by widening.
- **C5** — the status quo's retained structures enumerated, the design's delta stated against them, and nothing in the delta meeting the recogniser.
- **C6** — a stated home and lifecycle for demand state, and a read-free boundary shown to touch none of it.

## The readings

*Transcribed from the `rf2-hic-044` report by a later commit on this branch. Nothing is measured here.*

## The verdict

*Recorded by a later commit on this branch, after the readings above and by the reading rules above.*

## Consequences, both branches, fixed in advance

**On ADOPT.** File the implementation bead separately rather than growing this record — a committed `sub` declares demand; release follows unmount, parameter change and conditional-false; debounce, supersession, refresh-with-data and cancellation stay explicit policies. That bead inherits the blocking gates from C3 and C6, joins the dynamic-tail roster as a predecessor of `rf2-hic-065`, `rf2-hic-068`, `rf2-hic-072` and `rf2-hic-064`, and acquires an inventory id and a row in the canonical SSR/hydration matrix.

**On STOP.** Record which criterion failed and on what evidence, and bless `rf2-hic-054`'s async-resource recipes as the standing answer. File no implementation bead, add no dynamic-tail edge, mint no inventory id. The witness and the criteria remain on file as the reopen basis, and a reopen needs new evidence of a kind this witness could not supply — a second application whose ownership census the recipes demonstrably cannot answer, a defect class arriving repeatedly from real consumer code, or a change to the committed-read membership contract that makes a previously inexpressible demand expressible.

## What this page will not do

- **It takes no measurement.** Every figure it carries is `rf2-hic-044`'s, cited with the control `rf2-hic-044` published for it. A verdict that re-derives its own evidence has quietly become a second witness with no pre-registration of its own.
- **It ships no API.** No namespace, no export, no runtime file is touched by this bead on either outcome. A verdict that ships a surface is not a verdict.
- **It does not amend the criteria.** [The amendment rule](resource-demand-criteria.md#amendment-rule) closed this document when the report came into existence. A criterion believed wrong now is recorded as a dissent inside the verdict, and the verdict still follows the frozen rule. That is the whole value of freezing it.
- **It does not widen the evidence.** Step 4 says take no other input, so a strong showing elsewhere in the programme — a landed suite, another witness, an author's conviction — cannot rescue a row the report leaves open.

## Provenance

The criteria are [`resource-demand-criteria.md`](resource-demand-criteria.md) at `afbb58febc`; the evidence is [`resource-demand-witness.md`](resource-demand-witness.md) and nothing else. The flagship paragraph and its three fences are [specification §7](specification.md#7-complete-use-case-coverage), and the optional-module floor those fences sit under is at [§12's Phase 5](specification.md#12-action-programme), with its companion in [§2.1](specification.md#21-use-case-compass). The governing law is [design law State 7](lanes/design-laws.md#state-and-reactivity), with the shell ceiling at State 6. The scope ruling that makes this verdict v0 work is `rf2-xpq9`.
