# EP-0006: Runtime Subsystem Contract

Status: proposal
Type: standards-track

> Formalizes bead `rf2-6nn8bi` (filed from the 2026-06-10 EP-0001 review). Ruling
> on this EP supersedes ruling on that bead.

## Abstract

Every child of the runtime-db partition — `:rf.runtime/machines`,
`:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr`, and EP-0003's
incoming `:rf.runtime/resources` and `:rf.runtime/work-ledger` — independently
re-implements the same five properties, but the shape is nowhere named. This EP
names it:

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

1. **The write-authority gap (rf2-3939ig, now fixed).** Spec 002 names
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
   classification table (the rf2-oosjmh ruling: durable-serialized /
   local-subscribable / host-transient, per key) is the right shape — and it is
   exactly clause 4 of this contract, invented ad hoc because no contract asked
   for it. Resources and the work-ledger need the same table; they should fill
   in a form, not rediscover the form.

That is the whole v1 surface. External library-owned runtime-db children may
eventually need a similar contract, but they are not required to solve the
current write-authority/projection/restore gap and should not be standardized
without a concrete consumer.

## Goals

- Name the five-clause contract once, normatively.
- Grade the existing four subsystems against it (the table is the deliverable —
  filling it is most of the audit value).
- Give EP-0003's two children (`resources`, `work-ledger`) a graduation
  checklist.
- Make write-authority minting (clause 2) an enumerable, conformance-testable
  fact per subsystem, implemented by the general registration-meta mechanism
  `rf2-3939ig` introduces.
- Give the AI-Audit a gradeable surface identical in kind to Managed-Effects.

## Non-Goals

- Not the rejected generic N-partition frame: this organizes runtime-db's *own
  children*; the two-partition frame (EP-0001) is untouched.
- Not a capability/security boundary: clause 2 documents and tests authority;
  enforcement posture remains EP-0001 ruling #4 (convention + diagnostics).
- Not a new runtime mechanism: the contract is spec + grading table +
  conformance tests over machinery that already exists.
- Not a third-party runtime-subsystem registration API. A future external
  library may justify one, but that design would need its own EP because it
  changes the runtime-db namespace/schema/tooling contract.

## Relationships

- **Builds on EP-0001** (the partition that created the children) and adopts
  its Appendix A item 5, which the fourteen rulings never addressed.
- **Gates EP-0003 acceptance** (by recommendation, not hard dependency):
  `:rf.runtime/resources` and `:rf.runtime/work-ledger` should graduate against
  this checklist (EP-0003 amendment bead `rf2-pbzds6` adds the table there).
- **Sequenced with rf2-3939ig, not coupled to it:** the authority *mechanism*
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
  counters, which must never rewind (the rf2-oosjmh anti-recycling principle).
- **One authoritative home per fact.** Where a subsystem mirrors another's data
  (indexes, denormalized owners), the mirror is declared a recomputable
  projection, never a second source of truth.

### The grading table

`spec/Runtime-Subsystems.md` carries one row per subsystem × clause, citing the
owning spec section for each cell. Initial instances: machines (005), routing
(012), elision (009, with 015 supplying the privacy-classification inputs —
Conventions names instrumentation as the reserved-key owner), ssr (011), then
resources + work-ledger (EP-0003) at graduation. An empty or contested cell is
a tracked gap, not prose.

### Conformance

- A drift test pins the grading table's subsystem list against the reserved-key
  table in Conventions (a new child without a contract row fails CI).
- The `rf2-o4dmp8` sweep shape extends per subsystem: the framework's own
  writers never trigger the ownership diagnostics.

### Deferred: external runtime subsystems

A third-party library that wants durable, frame-local, framework-grade runtime
state (for example a GraphQL client cache or sync engine) is a real future
design pressure, but it is deliberately outside this EP. Blessing library-owned
runtime-db children would change more than this checklist: it would need a
registration API, namespace rules, runtime-db schema shape, write-authority
minting, projection defaults, teardown hooks, and tool rows. That is a separate
public extension contract, not a free consequence of naming the framework's
existing subsystems.

The future EP should start from a concrete external consumer and decide whether
extension children live under a framework-mediated `:rf.runtime/<lib>` key or
under the library's own qualified namespace. Until then, runtime-db's named
subsystem children remain framework-owned `:rf.runtime/*` keys only.

## Backwards Compatibility

Documentation + tests only; no runtime behavior changes. Pre-alpha: the
contract constrains future subsystem shapes deliberately — six existing
instances show the shape is empirical, not speculative.

## Reference Implementation / Bead Plan

1. Spec bead: author `spec/Runtime-Subsystems.md` (contract + table), add the
   Ownership row, cross-reference from Conventions. *(Hot-zone: Conventions;
   sequential.)*
2. Grading bead: fill the four existing rows from the owning specs; file gaps
   found as beads.
3. Conformance bead: the drift test + the per-subsystem diagnostics sweep.
4. EP-0003 integration: `rf2-pbzds6` (already filed) adds the resources +
   work-ledger rows.

## Open Issues

1. Should the work-ledger's future multi-writer authority (timers, streams,
   actors as later writers) be a single ledger-owned minting point or
   per-writer grants? Recommendation: ledger-owned — writers go through the
   ledger's API, which holds the authority; revisit if a writer needs direct
   row access.

## Recommendation

Adopt. The contract is small (one doc, one table, two tests), empirically
grounded, and its absence is precisely why the routing authority gap shipped
unnoticed. It converts "five subsystems, five sets of prose" into "one
contract, six graded instances" — the same move Managed-Effects already proved.
