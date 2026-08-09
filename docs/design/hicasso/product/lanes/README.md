# Hicasso product specification set

These documents define the current target for the native interpreted-Hiccup adapter and are organized by enduring product concern.

## Authority

1. [`../decision-brief.md`](../decision-brief.md) is the decision instrument. It owns the forensic scoreboard, selected-direction record, scoped-amendment adjudication, K3 disposition, sitting agenda, and kill rules.
2. [`../specification.md`](../specification.md) is the normative product target for that selected direction. It owns product verdicts, budgets, capability placement, the public-surface target, and phase order.
3. [`design-laws.md`](design-laws.md) states the non-negotiable design constraints; [`evidence-baseline.md`](evidence-baseline.md) records what is actually demonstrated and what still needs proof.
4. The focused specifications below own protocols, risk detail, and acceptance tests only. If one conflicts with either governing document, update it rather than preserving a second verdict or sequence.
5. The sibling lesson reports and `fable/` material are source corpus. They contribute evidence and ideas but are not normative product documents.

Quantitative claims stay attached to their witness, revision, runtime, substrate, and instrument. Results from different instruments are not presented as one continuous benchmark series.

## Ownership map

| Concern | Canonical owner |
|---|---|
| Current measurements and their admissibility | [`evidence-baseline.md`](evidence-baseline.md) |
| Product budgets, capability placement, portfolio status, and phase deliverables | [`../specification.md`](../specification.md) |
| Native-tier semantic laws | [`design-laws.md#native-boundary`](design-laws.md#native-boundary) |
| Native authoring grammar and examples | [`ergonomics-api.md#optional-native-surface`](ergonomics-api.md#optional-native-surface) |
| Native-tier acceptance checklist and performance protocols | [`hot-path-architecture.md#canonical-native-tier-acceptance-checklist`](hot-path-architecture.md#canonical-native-tier-acceptance-checklist) |
| Public-surface SSR/hydration policy and witness matrix | [`react-compatibility-notes.md#public-surface-ssrhydration-matrix`](react-compatibility-notes.md#public-surface-ssrhydration-matrix) |
| Concrete product proof suites | [`completeness-audit.md`](completeness-audit.md) |
| Phase dependencies and exit signals | [`delivery-programme.md`](delivery-programme.md) |
| Candidate-idea protocols and kill criteria | [`left-field-ideas.md`](left-field-ideas.md) |
| Corpus-derived rationale and cautions | [`corpus-insights.md`](corpus-insights.md) |
| Use-case coverage classification and per-surface server/hydration dispositions | [`../dispositions.md`](../dispositions.md) |
| Per-job corpus evidence: frequency, failure modes, and the witness each recurring job owes | [`../requirements-mine.md`](../requirements-mine.md) |
| Open naming questions and their consolidation | [`../naming-ledger.md`](../naming-ledger.md) |
| Pre-registered adopt/stop criteria for demand-driven resource ownership | [`../resource-demand-criteria.md`](../resource-demand-criteria.md) |
| Checkpoint findings and their closure into the release verdict | [`../correction-ledger.md`](../correction-ledger.md) |

## Product documents

- [`design-laws.md`](design-laws.md) — React, ownership, state, language, interop, scope, and evidence laws.
- [`evidence-baseline.md`](evidence-baseline.md) — implemented capabilities, demonstrated value, measured economics, and open proof obligations.
- [`corpus-insights.md`](corpus-insights.md) — durable rationale and deciding cautions distilled from the wider corpus; it does not own portfolio membership or status.
- [`ergonomics-api.md`](ergonomics-api.md) — small public language, the native-form grammar and examples, interop, failure ergonomics, and facade discipline.
- [`hot-path-architecture.md`](hot-path-architecture.md) — topology tuning, the canonical native-tier acceptance checklist, budgets, and deciding experiments.
- [`testing-xray.md`](testing-xray.md) — tiered testing, evidence schema, causal questions, privacy, and release witnesses.
- [`completeness-audit.md`](completeness-audit.md) — concrete proof suites and coverage witnesses, independent of roadmap prose.
- [`use-cases.md`](use-cases.md) — motivating jobs, adversarial cases, ownership, and required witnesses.
- [`adversarial-risks.md`](adversarial-risks.md) — kernel, native-surface, programme, and evidence risk/contract/witness/remedy registers.
- [`react-compatibility-notes.md`](react-compatibility-notes.md) — React obligations and the canonical public-surface SSR/hydration matrix.
- [`left-field-ideas.md`](left-field-ideas.md) — bounded innovation portfolio with validation and kill criteria.
- [`delivery-programme.md`](delivery-programme.md) — dependency and exit index keyed exactly to the primary Phases 0–6.

## Maintenance rules

- State current facts, decisions, requirements, unknowns, and next actions directly.
- Replace superseded claims in place; do not append dated review narratives or fold logs.
- Preserve provenance in the source corpus, not in this normative set.
- Keep proposed features conditional until their witness and kill criteria are explicit.
- Remove obsolete documents once their durable content is represented in the living specification.
