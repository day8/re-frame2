# Freehand design record

This directory is the durable supporting record behind
[EP-0036](../../EP/EP-0036-the-freehand-view-substrate-programme.md) and the
implementation of **Freehand** (`re-frame.freehand`, alias `v`): one
re-frame-native view substrate with an interpreted paved path and a compiled tier
built by absorbing the useful `re-frame.ui` machinery.

| Document | Role |
|---|---|
| [`codex-design.md`](codex-design.md) | product spine: the ratified target, boundaries, conformance contract, and technical dependency order |
| [`fable-design.md`](fable-design.md) | argued dossier: worked code, corpus fitness, semantic traces, alternatives, wounds, and pre-mortems |
| [`product-completion-setpoint.md`](product-completion-setpoint.md) | accepted 2026-07-26 problem statements, design completions, execution rulings, and evidence gates |
| [`studio/fitness-harness.md`](studio/fitness-harness.md) | evidence and acceptance pressure from applications, re-com, and hard browser cases |
| [`studio/er-01-architecture-comparison.md`](studio/er-01-architecture-comparison.md) | the ER-01 evidence spike: interpreted, compiled Hiccup and `v/$` measured over one virtual-table fixture, with the keep/change recommendation |
| [`decisions/`](decisions/README.md) | D001–D022, each with its explicit ratified ruling and rationale |
| the draft guide | PROMOTED and gone from this tree. It was the operator's working draft, and it became the shipped guide at [`docs/core/freehand/`](../../core/freehand/index.md) (rf2-fby7o), refined there against what actually landed. Its history is in git; keeping a second, staler copy beside the real one would only invite readers to the wrong page. |

## Authority

This directory is not a parallel specification tree. Operator rulings and
EP-0036 control the programme. The product-completion setpoint records the later
accepted delta. For target details not yet migrated into `spec/`, the product
spine plus that setpoint carry the ratified design. As each implementation slice
lands, the owning specification becomes the canonical contract for that surface.
The argued dossier and fitness harness explain and test the design; they do not
enlarge the API.

Current donor-era specs continue to describe current shipped behavior until their
Freehand migration lands. That temporary difference between “shipped now” and
“ratified target” is explicit; it is not a choice offered to implementers.

## Retention and maintenance

Keep this record after specification graduation. It preserves evidence, rejected
alternatives, and the reasoning that the tighter specifications intentionally omit.
There is no automatic deletion gate for this directory. A later explicit archival
decision may move it, but implementation completion alone does not erase it.

Keep the record lean and stable: correct factual errors, broken links, stale status,
or contradictions with a ruling, but do not grow it into a second API manual or
implementation tracker. Implementation work should cite stable spec/conformance
ids as they become available; before then it may cite the EP, product-spine section,
fitness requirement, or decision id.

The directory is excluded from the published mkdocs site because many evidence
links are intentionally source-tree-relative. It remains reviewable on GitHub and
in a checkout.
