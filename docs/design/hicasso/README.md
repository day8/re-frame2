# Hicasso — design record

**Hicasso** (`re-frame.hicasso`, alias `h`) is re-frame2's native view layer:
interpreted Hiccup on a UIx-class React function-component host, optimised for
re-frame2. This directory is the durable design record for the programme chartered
by [EP-0038](../../EP/EP-0038-the-hicasso-view-layer-programme.md).

| Document | Contents |
|---|---|
| [charter.md](charter.md) | Product identity, evidence base, goals, constraints, use-case roster, known losses |
| [decisions.md](decisions.md) | HD-001…HD-026 — every design decision, resolved, with rationale and reopen conditions |
| [hd-002-adjudication.md](hd-002-adjudication.md) | HD-002 adjudicated — the tripwire, the boundary, ownership, and the hypotheses under test |
| [architecture.md](architecture.md) | The architecture space, the one live arm (and the withdrawn second), the shared front half, inside-React feasibility, the sub-read mechanism ladder |
| [validation.md](validation.md) | The bar, the budgets, the P0→P1→P2 plan, witnesses, tournament and measurement discipline, kill criteria |
| [authoring.md](authoring.md) | The authoring surface: views, subs, intents, interop door, theming, ephemeral state, testing doors |

**Authority.** decisions.md is normative; where pages differ, decisions.md
governs, then validation.md. EP-0038 sequences the programme; the operator-owned
standard bead carries the live bar/kill numbers.

**Provenance and retention.** This record graduates — and fully absorbs — the
exploration corpus that lived in the untracked local `ai/findings/new-view/`
tree (three per-model charters, two independent multi-lens review cycles, a
paper budget, and the `spike-01` index model); nothing load-bearing remains only
there. The requirements harness is tracked at
`docs/design/freehand/studio/fitness-harness.md`; measured claims cite the
repo's studio/bench record and beads. Programme measurements land in
`docs/design/hicasso/studio/` (minted by the first P0 worker).

Like `design/freehand/`, this tree is excluded from the published mkdocs site and
is read in the source tree.
