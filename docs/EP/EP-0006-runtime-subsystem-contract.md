# EP-0006: Runtime Subsystem Contract

Status: final
Type: standards-track

> Formalizes the runtime-subsystem contract raised in the 2026-06-10 EP-0001
> review; the ruling on this EP is the authoritative one.

> **`final` means the decisions are settled.** Mike ruled on 2026-06-10:
> adopt the contract as a standalone `spec/Runtime-Subsystems.md`, on the
> Managed-Effects precedent, and graduate this EP proposal → final as
> the decision-record EP. That ruling *is* the EP-0006 ruling, so "proposal" was a
> false status. The contract's normative output has shipped in full — the
> five-clause contract, the grading table, and (under this graduation) the **two
> derived rules** now live in [`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md);
> where this EP and the spec differ, the spec governs. The decision surface is
> closed. One implementation item — the conformance drift test — remains unbuilt
> and is tracked in [Implementation errata](#implementation-errata) (the EP-0005
> final-with-errata pattern: decisions-final does not assert build-complete). The
> single deferred *implementation* question (the work-ledger multi-writer
> authority of [Open Issues](#open-issues) — a future-EP question, not a live
> decision blocking the contract) is recorded honestly as one on which the
> shipped `spec/016-Resources.md` currently diverges from this EP's recommendation
> — it does not block the contract.

## Implementation errata

The EP decisions are final and the contract's normative output has shipped to
`spec/Runtime-Subsystems.md`. **One tracked erratum remains open** — the
conformance drift test the [Conformance](#conformance) section specifies was not
built with the spec doc. The errata ledger tracks build-completion separately from
the decision-freeze (the EP-0005 pattern), and none of it reopens any ruling.

### Open errata

- **Open — drift test unbuilt** — the [Conformance](#conformance)
  section specifies a drift test that pins the grading table's subsystem list
  against the reserved-key table in [`spec/Conventions.md`](../../spec/Conventions.md#reserved-runtime-db-keys),
  so a new `:rf.runtime/*` child landed *without* a contract grading row fails CI.
  The grading table now covers the runtime-subsystem rows, including the
  EP-0003 resource trio (`resources`, `work-ledger`, `mutations`), and the
  per-subsystem ownership-diagnostic sweep
  exists, but the **table-vs-reserved-key drift test itself is not yet wired into a
  gate**. Until it lands, a new subsystem child without a grading row is caught
  only by review, not CI. *(Decision-settled, build-incomplete: the test's shape
  is fully specified above; only the wiring is outstanding.)*

## Abstract

Every child of the runtime-db partition — `:rf.runtime/machines`,
`:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr`, and the
EP-0003 resource trio (`:rf.runtime/resources`, `:rf.runtime/work-ledger`,
`:rf.runtime/mutations`) — independently re-implements the same five
properties, but the shape was nowhere named. This EP names it:

> A **runtime subsystem** is a reserved runtime-db sub-tree with a declared
> write authority, a public read API, a projection policy, and a teardown
> contract. Every `:rf.runtime/*` child is an instance of this contract and is
> graded against it.

This is the durable-state analogue of `Managed-Effects.md`: name the recurring
shape once, grade instances against a checklist, and stop re-deriving the rules
in prose per subsystem. The contract lives in a new standalone
`spec/Runtime-Subsystems.md` (the Managed-Effects precedent), referenced from
`Ownership.md` and the existing reserved-key tables in `Conventions.md`.

## Motivation

Three concrete failures this contract would have prevented or will prevent:

1. **The write-authority gap (now fixed).** Spec 002 names
   machines, routing, elision, and SSR as legitimate runtime-db writers; the
   implementation minted framework authority from `:rf/machine?` only — so
   routing fired `:rf.warning/app-handler-runtime-effect` on every navigation
   in dev, verified empirically. The general minting mechanism has since
   merged, but the *class* remains: nothing makes the next subsystem declare
   its authority. A contract with an explicit per-subsystem *write-authority*
   clause makes "who may mint" an enumerable table row that a conformance
   sweep checks, instead of a fact each subsystem re-implements.
2. **EP-0003 graduates against prose.** The resources design already satisfies
   four of the five clauses implicitly and is silent on exactly the fifth
   (write authority). Without a named contract, that observation took a manual
   review to surface; with one, it is a failing checklist row.
3. **The projection-policy class keeps being re-decided.** The routing
   classification table (the routing ruling: durable-serialized /
   local-subscribable / host-transient, per key) is the right shape — and it is
   exactly clause 4 of this contract, invented ad hoc because no contract asked
   for it. Resources, the work-ledger, and mutations need the same table; they
   should fill in a form, not rediscover the form.

That is the whole shipped framework-owned surface. The normative spec now also
names the checklist a runtime extension must satisfy to become a graded
instance; this EP still does not define a fully general public
third-party-registration API.

## Goals

- Name the five-clause contract once, normatively.
- Grade the then-current runtime-subsystem set against it (the table is the
  deliverable — filling it is most of the audit value).
- Give EP-0003's runtime children (`resources`, `work-ledger`, `mutations`) a
  graduation checklist.
- Make write-authority minting (clause 2) an enumerable, conformance-testable
  fact per subsystem, implemented by the general registration-meta mechanism.
- Give the AI-Audit a gradeable surface identical in kind to Managed-Effects.

## Non-Goals

- Not the rejected generic N-partition frame: this organizes runtime-db's *own
  children*; the two-partition frame (EP-0001) is untouched.
- Not a capability/security boundary: clause 2 documents and tests authority;
  enforcement posture remains EP-0001 ruling #4 (convention + diagnostics).
- Not a new runtime mechanism: the contract is spec + grading table +
  conformance tests over machinery that already exists.
- Not a fully general third-party runtime-subsystem registration API. The
  normative spec defines the grading checklist a runtime-extension child must
  satisfy; a public library-facing registrar, namespace allocation rules, and
  default tooling hooks remain future design work.

## Relationships

- **Builds on EP-0001** (the partition that created the children) and adopts
  its Appendix A item 5, which the fourteen rulings never addressed.
- **Informed EP-0003 graduation** (by recommendation, not hard dependency):
  `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and
  `:rf.runtime/mutations` graduated against this checklist. EP-0003 is now
  **final** (graduated 2026-06-11) with those grading rows
  shipped; this relationship is settled history plus ongoing conformance
  hygiene (the drift test).
- **Sequenced with the framework-authority fix, not coupled to it:** the authority *mechanism*
  (general framework-authority registration meta) has shipped as a bug fix;
  this contract is the *policy* layer that cites it as clause 2's
  implementation. No rework either way.

## Specification

### The contract

A runtime subsystem MUST declare, in its owning spec:

| # | Clause | The question it answers |
|---|---|---|
| 1 | **Reserved sub-tree** | Which reserved runtime-db child key does it own? For this EP, that is a framework-owned `:rf.runtime/<name>` key registered in Conventions' reserved-key table. |
| 2 | **Write authority** | Which registration sites mint framework-authority handlers for its writes? Multi-writer subsystems enumerate every writer. |
| 3 | **Read API** | Which public subscriptions/accessors read it? Raw paths are never the public surface. |
| 4 | **Projection policy** | Per key: durable-serialized / local-subscribable / host-transient — consumed by SSR hydration, epoch egress, and off-box redaction. (The routing classification table is the canonical shape.) |
| 5 | **Teardown contract** | Which durable facts survive restore/hydration; which transient side-tables and host handles are torn down on frame destroy; what recomputes on install. |

Two derived rules:

- **The restore question is mandatory.** Clause 5 must answer "what does
  epoch restore do to every value in this sub-tree?" — including allocator
  counters, which must never rewind (the anti-recycling principle).
- **One authoritative home per fact.** Where a subsystem mirrors another's data
  (indexes, denormalized owners), the mirror is declared a recomputable
  projection, never a second source of truth.

> **Graduated.** Both derived rules now live as general normative rules in
> [`spec/Runtime-Subsystems.md` §Two derived rules](../../spec/Runtime-Subsystems.md#two-derived-rules)
> ([rule 1](../../spec/Runtime-Subsystems.md#derived-rule-1--the-restore-question-is-mandatory-allocators-never-rewind),
> [rule 2](../../spec/Runtime-Subsystems.md#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections)).
> The original spec-authoring work landed the five-clause contract + grading table
> blind to this EP, so the rules were ported under this graduation.
> The spec is the normative home; this list is the rationale record.

### The grading table

`spec/Runtime-Subsystems.md` carries one row per subsystem × clause, citing the
owning spec section for each cell. Initial instances: machines (005), routing
(012), elision (009, with 015 supplying the privacy-classification inputs —
Conventions names instrumentation as the reserved-key owner), ssr (011), then
the EP-0003 resource trio (`resources`, `work-ledger`, `mutations`) at
graduation. An empty or contested cell is a tracked gap, not prose.

### Conformance

- A drift test pins the grading table's subsystem list against the reserved-key
  table in Conventions (a new child without a contract row fails CI).
- The ownership-diagnostic sweep shape extends per subsystem: the framework's own
  writers never trigger the ownership diagnostics.

### Runtime-extension seam and deferred public API

A third-party or optional framework-adjacent library that wants durable,
frame-local, framework-grade runtime state (for example a GraphQL client cache
or sync engine) is a real design pressure. The graduated
[`spec/Runtime-Subsystems.md`](../../spec/Runtime-Subsystems.md) contract now
answers the checklist question for such a child: reserve a runtime-db subtree,
declare write authority, public reads, projection/elision, and teardown.

That checklist is not a complete public registration API. A future EP should
start from a concrete external consumer and settle the library-facing mechanics:
how a non-core producer obtains a `:rf.runtime/<name>` reservation under the
framework-owned namespace, which registrar publishes the authority stamp, which
default projection and teardown hooks exist, and which tool rows are required.
Until then, new runtime children are still admitted by Spec/Conventions change
and a grading row, not by an arbitrary user-space claim under `:rf.*`.

## Backwards Compatibility

Documentation + tests only; no runtime behavior changes. Pre-alpha: the
contract constrains future subsystem shapes deliberately — the shipped grading
table shows the shape is empirical, not speculative.

## Reference Implementation / Bead Plan

1. Spec bead: author `spec/Runtime-Subsystems.md` (contract + table), add the
   Ownership row, cross-reference from Conventions. *(Hot-zone: Conventions;
   sequential.)*
2. Grading bead: fill the initial runtime-subsystem rows from the owning specs;
   file gaps found as beads.
3. Conformance bead: the drift test + the per-subsystem diagnostics sweep.
4. EP-0003 integration: the integration added the initial resources +
   work-ledger rows; later resource-trio syncs added the mutation row as the
   mutation slice landed.

## Open Issues

1. Should the work-ledger's future multi-writer authority (timers, streams,
   actors as later writers) be a single ledger-owned minting point or
   per-writer grants? Recommendation: ledger-owned — writers go through the
   ledger's API, which holds the authority; revisit if a writer needs direct
   row access.

   **Resolved as deferred (2026-06-10), with a divergence to record
   honestly.** This is a deferred *implementation* question, not a live decision
   blocking the contract: the shipped Resources artefact writers stamp
   framework authority, so the unresolved question is the first writer outside
   that artefact (timers, streams, route loaders, spawned actors, or machine
   async work). At that point the general work-ledger EP settles it. The pointer
   is [`spec/016-Resources.md` §Work-ledger multi-writer
   authority](../../spec/016-Resources.md#work-ledger-multi-writer-authority--still-blocking-for-the-multi-writer-slice-post-v1-tracked-for-v1),
   which classifies it `:still-blocking` for the multi-writer slice and
   `:post-v1 tracked` for v1.

   **The shipped spec diverges from this EP's recommendation, and the spec
   governs.** This EP recommended *ledger-owned* minting (writers go through the
   ledger's API, which holds the authority). The shipped `spec/016-Resources.md`
   instead leans **per-writer** minting: the linked section states each
   additional writer "MUST be settled per writer at that point — machines imply
   authority via `:rf/machine? true`; non-machine writers will each need to
   stamp `:rf/framework-authority? true` at their own registration sites or
   write through the privileged helpers." That is the per-writer-grants
   alternative, not the ledger-owned recommendation. The recommendation did
   **not** win; it stands as one input the future work-ledger EP will weigh
   against the per-writer default the resources spec currently encodes. Where
   this EP and the spec differ, the spec governs (per
   [EP-0009](EP-0009-the-ep-process.md) and the README index note).

## Recommendation

Adopt. The contract is small (one doc, one table, two tests), empirically
grounded, and its absence is precisely why the routing authority gap shipped
unnoticed. It converts repeated per-subsystem prose into one contract and one
grading table — the same move Managed-Effects already proved.
