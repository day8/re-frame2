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
> - **SA-4** (MUST — addressed to spec authors). *(originally C-000.39.)* Every item under a `## Open questions` heading in a per-Spec document MUST be classified per the four-term vocabulary below; resolved items MUST move out of `## Open questions` to `## Resolved decisions`; indefinite "we'll figure out X later" items are incompatible with [000 Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and MUST be resolved before the corpus ships.
>
>   **Classification vocabulary (rf2-p6xyh):**
>
>   | Term | Meaning | Where it lives | Required cross-link |
>   |---|---|---|---|
>   | **`:resolved`** | A landed decision; the design is settled and the load-bearing prose lives elsewhere in this Spec (or a sibling Spec). | `## Resolved decisions` — not `## Open questions`. | A pointer to the section that carries the load-bearing prose, OR (for cross-Spec decisions) a pointer to the sibling Spec section. The bead id that resolved the decision (`rf2-<id>`) when one exists. |
>   | **`:host-choice`** | The pattern allows multiple valid implementations; the CLJS reference's pick is explicitly named so other-language ports know what the reference does and can decide independently. | `## Resolved decisions` — the host choice IS the resolution. | An explicit "v1 CLJS reference: …" naming the chosen approach, plus a "other ports MAY …" framing for the alternatives. |
>   | **`:post-v1 tracked`** | Deferred design work that is in scope for re-frame2 but does not ship in v1; the work has a concrete tracking bead. | `## Open questions` (until the bead lands) OR `## Future` (when the spec uses a Future section). | A `rf2-<id>` bead reference and a one-line "deferred to …" framing. Items that read as "we might do this someday" without a bead are NOT `:post-v1 tracked` — they are `:still-blocking`. |
>   | **`:still-blocking`** | Genuinely unresolved design question that blocks the corpus shipping; needs a decision before v1. | `## Open questions` with a clear "blocking — needs decision" framing. | A bead filed to drive the decision (filing the bead converts to `:post-v1 tracked` once Mike confirms post-v1 scope; or to `:resolved` once a decision lands). |
>
>   **Migration rule.** When an item under `## Open questions` is labelled `(RESOLVED)` in its heading, that is a signal it has already met the `:resolved` bar but has not been moved. SA-4 says it MUST move to `## Resolved decisions`. The corpus audit rf2-p6xyh moved the three specs where the migration was overdue (002, 005, 013); rf2-ghl6w schedules the same sweep against the remaining 11 numbered specs.
>
> - **SA-8** (MUST — addressed to spec authors and AI-Audit harness authors). *(introduced 2026-05-16 alongside SA-4's classification vocabulary, rf2-p6xyh.)* The AI-Audit pass MUST produce a corpus-wide report enumerating every `## Open questions` heading across the per-Spec docs, with each item's SA-4 classification (one of `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking`) and its required cross-link (per SA-4 table). The report's purpose is to make SA-4 violations mechanically auditable rather than dependent on per-Spec narrative review — a `:resolved` item still sitting under `## Open questions` surfaces as an SA-4 violation in the report, the same way a `:post-v1 tracked` item without a bead id surfaces. The report cadence is per AI-Audit run; the report's persistence (a generated EDN / Markdown table at `spec/AI-Audit.md` §SA-4-report or its successor) lives in the AI-Audit doc, not here.
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
