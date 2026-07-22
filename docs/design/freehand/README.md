# Freehand design dossiers (transitional tracked snapshot)

These are the Freehand design dossiers — the design record behind
[EP-0036](../../EP/EP-0036-the-freehand-view-substrate-programme.md), the
programme that delivers **Freehand** (`re-frame.freehand`, alias `v`): one
re-frame-native view substrate with an interpreted paved path and a compiled
tier, built by absorbing `re-frame.ui`'s useful machinery and deleting the
donor artifact at the conformance gate.

| Document | Role |
|---|---|
| [`fable-design.md`](fable-design.md) | the ARGUED DOSSIER — worked dream code, fitness scoring, semantic traces, wounds/pre-mortems, alternatives |
| [`codex-design.md`](codex-design.md) | the PRODUCT SPINE — the normative statement of what Freehand is and is not, its conformance surface, and its build order |
| [`studio/fitness-harness.md`](studio/fitness-harness.md) | the acceptance harness — the three hard cases, the examples-corpus census, and the re-com problem inventory |
| [`decisions/`](decisions/README.md) | the 21 ratified decisions D001–D021 (ratified and folded 2026-07-22; see the note below) |

Read them in the order the decision register prescribes: operator rulings,
then `codex-design.md` as the product spine, then `fable-design.md` as
evidence and argument, then the individual decision dossiers.

**Snapshot honesty.** These files are copied verbatim from the working tree
(`ai/findings/better-ui/`, 2026-07-22) and are not edited here. Two
consequences: the per-dossier `Status:` lines under `decisions/` predate the
ratification — the ratification of all twenty-one decisions is recorded in
`fable-design.md`'s header (2026-07-22) — and relative links were written for
source-tree browsing, so this directory is excluded from the mkdocs site
build and is meant to be read on GitHub or in a checkout.

**TRANSITIONAL TRACKED EXCEPTION.** Design working artefacts normally live
under the untracked `ai/` tree. This directory is deliberately tracked so
that beads and the EP can cite stable, reviewable paths while the normative
spec is authored. It is deleted when the `spec/0XX-Freehand` family
(EP-0036 §W1) supersedes it — the same retirement pattern EP-0030 applied to
its synthesis dossier. Until then, beads cite these paths; do not grow this
tree beyond the snapshot.
