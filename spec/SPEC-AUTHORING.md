# Spec Authoring — meta-spec obligations

> **Type:** Meta-spec (addressed to spec authors and conformance-harness authors, not implementors).
> A small set of obligations on the people writing re-frame2's specifications and the people building its conformance harness — distinct from the implementor-facing contracts in the per-Spec docs.

## Scope

The per-Spec documents (000–016, the Pattern docs, MIGRATION, Spec-Schemas, etc.) bind **implementors**: a TS port, a Python port, the CLJS reference. They use RFC 2119 keywords (MUST, SHOULD, MAY) to mark obligations the implementation has to satisfy.

This document binds two different audiences:

- **Spec authors** — the people writing the per-Spec docs themselves. Their obligations are about what the spec corpus *as a corpus* must contain or avoid.
- **Conformance-harness authors** — the people building [conformance/](conformance) and the runners that grade implementations against capability-declared fixture sets. Their obligations are about what the harness must and must not do when grading.

The clauses below are addressed to those two roles; an implementor never acts on them directly. They live in their own document, separate from 000-Vision's implementor-facing Contract block.

The clauses are id'd `SA-NN` (spec-authoring) to keep them visually distinct from per-Spec `C-NNN.NN` clauses.

## Contract — spec authoring and conformance harness obligations

> The clauses below are normative for spec authors and conformance-harness authors. Implementors are not the addressees. RFC 2119 keywords are interpreted per RFC 8174 (capitalised forms only carry the formal meaning).

> - **SA-1** (MUST NOT — addressed to spec authors and harness authors). A non-CLJS port MUST NOT be deemed non-conformant for failing to ship any of the items in [000 §What the pattern does NOT over-commit to](000-Vision.md#what-the-pattern-does-not-over-commit-to) (macros, Vars and `def`-as-registration, Reagent-specific component return types, hiccup, CLJS-only runtime assumptions, React context as the frame-routing mechanism, `goog-define`, Malli). These are CLJS-reference choices, not pattern requirements.
>
> - **SA-2** (MUST — addressed to spec authors). Every spec document MUST be readable without consulting re-frame v1 source. Where re-frame v1 behaviour is the contract, the spec MUST capture it explicitly (with examples); "see re-frame v1 for the existing behaviour" is not a sufficient specification.
>
> - **SA-3** (MUST — addressed to spec authors). Every shape that flows on the wire or appears in a spec example — event vector, dispatch envelope, registration metadata, effect map, snapshot, hydration payload, trace event, fixture file — MUST have a schema in [Spec-Schemas.md](Spec-Schemas.md).
>
> - **SA-4** (MUST — addressed to spec authors). Every item under a `## Open questions` heading in a per-Spec document MUST be classified per the four-term vocabulary below; resolved items MUST move out of `## Open questions` to `## Resolved decisions`; indefinite "we'll figure out X later" items are incompatible with [000 Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and MUST be resolved before the corpus ships.
>
>   **Classification vocabulary:**
>
>   | Term | Meaning | Where it lives | Required cross-link |
>   |---|---|---|---|
>   | **`:resolved`** | A landed decision; the design is settled and the load-bearing prose lives elsewhere in this Spec (or a sibling Spec). | `## Resolved decisions` — not `## Open questions`. | A pointer to the section that carries the load-bearing prose, OR (for cross-Spec decisions) a pointer to the sibling Spec section. The bead id that resolved the decision (`rf2-<id>`) when one exists. |
>   | **`:host-choice`** | The pattern allows multiple valid implementations; the CLJS reference's pick is explicitly named so other-language ports know what the reference does and can decide independently. | `## Resolved decisions` — the host choice IS the resolution. | An explicit "v1 CLJS reference: …" naming the chosen approach, plus a "other ports MAY …" framing for the alternatives. |
>   | **`:post-v1 tracked`** | Deferred design work that is in scope for re-frame2 but does not ship in v1; the work has a concrete tracking bead. | `## Open questions` (until the bead lands) OR `## Future` (when the spec uses a Future section). | A `rf2-<id>` bead reference and a one-line "deferred to …" framing. Items that read as "we might do this someday" without a bead are NOT `:post-v1 tracked` — they are `:still-blocking`. |
>   | **`:still-blocking`** | Genuinely unresolved design question that blocks the corpus shipping; needs a decision before v1. | `## Open questions` with a clear "blocking — needs decision" framing. | A bead filed to drive the decision (filing the bead converts to `:post-v1 tracked` once Mike confirms post-v1 scope; or to `:resolved` once a decision lands). |
>
>   **Migration rule.** When an item under `## Open questions` is labelled `(RESOLVED)` in its heading, that is a signal it has already met the `:resolved` bar but has not been moved. SA-4 says it MUST move to `## Resolved decisions`.
>
> - **SA-8** (MUST — addressed to spec authors and AI-Audit harness authors). The AI-Audit pass MUST produce a corpus-wide report enumerating every `## Open questions` heading across the per-Spec docs, with each item's SA-4 classification (one of `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking`) and its required cross-link (per SA-4 table). The report's purpose is to make SA-4 violations mechanically auditable rather than dependent on per-Spec narrative review — a `:resolved` item still sitting under `## Open questions` surfaces as an SA-4 violation in the report, the same way a `:post-v1 tracked` item without a bead id surfaces. The report cadence is per AI-Audit run; the report's persistence (a generated EDN / Markdown table at `spec/AI-Audit.md` §SA-4-report or its successor) lives in the AI-Audit doc, not here.
>
> - **SA-5** (MUST — addressed to spec authors and harness authors). A conformance fixture that fails because the spec is ambiguous MUST be classified as a spec defect, not an implementation defect. The remediation is to add the missing prose, schema, fixture, or host-profile-matrix entry.
>
> - **SA-6** (MUST NOT — addressed to harness authors). A conformance harness MUST NOT mark a fixture as failing against an implementation when the fixture exercises a capability the implementation has not claimed (per [000 §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)). Capability-graded conformance only works if the harness honours the claim.
>
> - **SA-7** (MUST — addressed to spec authors and harness authors). Capability status is recorded by the [005 §Capability matrix](005-StateMachines.md#capability-matrix), not by ad-hoc "out of scope" prose. Parallel regions and history states are **first-class capabilities claimed by the v1 reference** (parallel per Nine States Stage 2; history per `:fsm/history`). There is no snapshot-as-value history substitute; the N-machines-per-region pattern is the right tool for *conceptually-independent* features, not a substitute for an unclaimed capability. A port that does not claim a capability remains conformant for its claimed subset and rejects the unclaimed key at registration (`:rf.error/machine-grammar-not-in-v1`, per [005 §How conformance is graded](005-StateMachines.md#how-conformance-is-graded)); spec authors MUST treat the capability matrix as the single source of truth for what is and is not claimed.
>
> - **SA-9** (MUST — addressed to spec authors). **The audit co-edit question — "does this change falsify an audit row?"** Every PR that edits a per-Spec doc MUST answer this against [AI-Audit.md](AI-Audit.md): if the change alters a shape, key, path, capability, or resolution that an AI-Audit scoring row or cross-cutting entry asserts (e.g. a `✓`/`◐`/`✗` grade, a "lives at `<path>`" claim, a "resolved by `<EP>`" note, a G-item's RESOLVED/open state), the **same PR** MUST correct the affected AI-Audit row and advance that table's **_As-of_** watermark to the change's date. This extends the [Conventions §Error-id and warning-id grammar](Conventions.md#error-id-and-warning-id-grammar) same-PR co-edit invariant (a new `:rf.<area>/<category>` event lands its 009 catalogue row in the same PR) from the error catalogue to the per-Spec audit tables: a spec change that silently falsifies an audit row is a contract bug, not a deferred follow-up, because AI-Audit is Track-2 reading and a stale row makes an AI generate against a shape the corpus no longer has. The SA-3 schema-coverage report and the SA-8 open-questions report regenerate **per AI-Audit run** (their standing cadence); SA-9 tightens the per-Spec scoring tables to the **per-PR** cadence for any row a given change actually touches — the audit-pass sweep re-verifies everything, but SA-9 stops a known-falsifying edit from shipping the staleness in the first place. A PR that leaves an audit row it falsified uncorrected is an SA-9 violation surfaced by the next AI-Audit pass (the row and its stale watermark are the evidence).

## Cross-references

- [000-Vision.md §Contract — pattern obligations](000-Vision.md) — the implementor-facing summary block that pairs with this document. The clauses there bind implementations of the pattern; the clauses here bind the spec corpus and the harness that grades implementations.
- [conformance/README.md](conformance/README.md) — the operational contract for the harness; SA-5 and SA-6 govern how it grades.
- [Spec-Schemas.md](Spec-Schemas.md) — the catalogue SA-3 commits the spec corpus to keep complete.
- [AI-Audit.md](AI-Audit.md) — the audit whose scoring tables SA-8 regenerates per run and SA-9 keeps per-PR-fresh; its per-table _As-of_ watermarks are the freshness evidence.

## Decision history

These clauses bind spec authors or harness authors, not implementors; they live here so 000-Vision's implementor-facing contract is not diluted.
