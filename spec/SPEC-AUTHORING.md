# Spec Authoring — meta-spec obligations

> **Type:** Meta-spec (addressed to spec authors and conformance-harness authors, not implementors).
> A small set of obligations on the people writing re-frame2's specifications and the people building its conformance harness — distinct from the implementor-facing contracts in the per-Spec docs.

## Scope

The per-Spec documents (000–014, the Pattern docs, MIGRATION, Spec-Schemas, etc.) bind **implementors**: a TS port, a Python port, the CLJS reference. They use RFC 2119 keywords (MUST, SHOULD, MAY) to mark obligations the implementation has to satisfy.

This document binds two different audiences:

- **Spec authors** — the people writing the per-Spec docs themselves. Their obligations are about what the spec corpus *as a corpus* must contain or avoid.
- **Conformance-harness authors** — the people building [conformance/](conformance/) and the runners that grade implementations against capability-declared fixture sets. Their obligations are about what the harness must and must not do when grading.

The clauses below are addressed to those two roles; an implementor never acts on them directly. They live in their own document because mixing them into 000-Vision's implementor-facing Contract block dilutes both audiences (per the rf2-ehml review §6.5).

The clauses are id'd `SA-NN` (spec-authoring) to keep them visually distinct from per-Spec `C-NNN.NN` clauses. The original draft assigned these clauses C-000.20, .37, .38, .39, .40, .43, .44 inside 000-Vision's Contract block; that history is preserved in each clause's cross-reference for traceability.

## Contract — spec authoring and conformance harness obligations

> The clauses below are normative for spec authors and conformance-harness authors. Implementors are not the addressees. RFC 2119 keywords are interpreted per RFC 8174 (capitalised forms only carry the formal meaning).

> - **SA-1** (MUST NOT — addressed to spec authors and harness authors). *(originally C-000.20 in the rf2-ehml draft.)* A non-CLJS port MUST NOT be deemed non-conformant for failing to ship any of the items in [000 §What the pattern does NOT over-commit to](000-Vision.md#what-the-pattern-does-not-over-commit-to) (macros, Vars and `def`-as-registration, Reagent-specific component return types, hiccup, CLJS-only runtime assumptions, React context as the frame-routing mechanism, `goog-define`, Malli). These are CLJS-reference choices, not pattern requirements.
>
> - **SA-2** (MUST — addressed to spec authors). *(originally C-000.37.)* Every spec document MUST be readable without consulting re-frame v1 source. Where re-frame v1 behaviour is the contract, the spec MUST capture it explicitly (with examples); "see re-frame v1 for the existing behaviour" is not a sufficient specification.
>
> - **SA-3** (MUST — addressed to spec authors). *(originally C-000.38.)* Every shape that flows on the wire or appears in a spec example — event vector, dispatch envelope, registration metadata, effect map, snapshot, hydration payload, trace event, fixture file — MUST have a schema in [Spec-Schemas.md](Spec-Schemas.md).
>
> - **SA-4** (MUST — addressed to spec authors). *(originally C-000.39.)* Every Open Question in a per-Spec document MUST eventually resolve to either (a) a landed decision, or (b) an explicit "host-choice" framing naming the v1 CLJS reference's pick. Indefinite "we'll figure out X later" Open Questions are incompatible with [000 Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and MUST be resolved before the corpus ships.
>
> - **SA-5** (MUST — addressed to spec authors and harness authors). *(originally C-000.40.)* A conformance fixture that fails because the spec is ambiguous MUST be classified as a spec defect, not an implementation defect. The remediation is to add the missing prose, schema, fixture, or host-profile-matrix entry.
>
> - **SA-6** (MUST NOT — addressed to harness authors). *(originally C-000.43.)* A conformance harness MUST NOT mark a fixture as failing against an implementation when the fixture exercises a capability the implementation has not claimed (per [000 §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)). Capability-graded conformance only works if the harness honours the claim.
>
> - **SA-7** (MUST NOT — addressed to spec authors and harness authors). *(originally C-000.44.)* Parallel regions and history states MUST NOT be treated as pattern-level capability omissions. They are explicitly out of pattern scope; substitutes (separate machines per region; snapshot-as-value capture) are documented in [005 §Substitutes for skipped features](005-StateMachines.md#substitutes-for-skipped-features). Treating them as gaps in the substrate contradicts the deliberate scope decision.

## Cross-references

- [000-Vision.md §Contract — pattern obligations](000-Vision.md) — the implementor-facing summary block that pairs with this document. The clauses there bind implementations of the pattern; the clauses here bind the spec corpus and the harness that grades implementations.
- [conformance/README.md](conformance/README.md) — the operational contract for the harness; SA-5 and SA-6 govern how it grades.
- [Spec-Schemas.md](Spec-Schemas.md) — the catalogue SA-3 commits the spec corpus to keep complete.

## Decision history

The clauses above were originally drafted inside 000-Vision's Contract block (rf2-uuoj / rf2-ehml). Independent review (`findings/rf2-ehml-review.md` §5, §6.5) flagged them as addressee-flipped — they bind spec authors or harness authors, not implementors — and recommended extracting them so 000's implementor-facing contract isn't diluted. This document is the resolution (rf2-04sm).
