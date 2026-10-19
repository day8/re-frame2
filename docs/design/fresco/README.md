# Fresco — design record

Fresco (`re-frame.fresco`, alias `h`) is re-frame2's native view layer:
interpreted Hiccup on a UIx-class React function-component host, optimised for
re-frame2. This directory is the durable design record for the programme chartered
by [EP-0038](../../EP/EP-0038-the-fresco-view-layer-programme.md).

| Document | Contents |
|---|---|
| [charter.md](charter.md) | Product identity, evidence base, goals, constraints, use-case roster, known losses |
| [decisions.md](decisions.md) | HD-001…HD-029 — every design decision, resolved, with rationale and reopen conditions; HD-029 is the P2 fork ruling of 2026-08-13, *Fresco graduates, as a success* |
| [hd-002-adjudication.md](hd-002-adjudication.md) | HD-002's correctness/cost gates, adjudicated and still binding — the tripwire, the boundary, ownership, and the hypotheses under test. Written before the operator's 2026-07-31 ergonomics ruling ([decisions.md](decisions.md) HD-002) and stands as written per that ruling; read it alongside HD-002, not in place of it |
| [production-server-arm.md](production-server-arm.md) | Ruling prep for the P2 fork (`rf2-2rtt6.88`) — written for the 2026-08-27 sitting that the 2026-08-13 graduation ruling pre-empted, and the page says so: the JVM structural walk and the Node sidecar priced against each other from the X1–X5 spike corpus, with what is measured and what is not. **No verdict** — the arm is the operator's to choose |
| [allocation-instrument-rework.md](allocation-instrument-rework.md) | Design brief for `rf2-2rtt6.140`: the boundary-proportional write and the in-window collection witness, with their validity witnesses, the disposition of every test the change supersedes, and the option held in reserve. **No code** — written before the implementation |
| [reactive-substrate-adapter-costing.md](reactive-substrate-adapter-costing.md) | Sizing for `rf2-hvr5h`: what a Fresco-shaped adapter would have to implement, read against the six required contract fns, and how much of it core's React spine already supplies. **No adapter built, no ruling taken** — whether to ship one is the operator's |
| [architecture.md](architecture.md) | The architecture space, the one live arm (and the withdrawn second), the shared front half, inside-React feasibility, the sub-read mechanism ladder |
| [validation.md](validation.md) | The bar, the budgets, the P0→P1→P2 plan, witnesses, tournament and measurement discipline, kill criteria |
| [authoring.md](authoring.md) | The authoring surface: views, subs, intents, interop door, theming, ephemeral state, testing doors |

**Authority.** This directory is the design record — the reasoning, the
alternatives and the measurements behind Fresco — and it does not govern the
shipped package. The contract is
[`implementation/fresco/spec/authoring-contract.md`](../../../implementation/fresco/spec/authoring-contract.md),
with its sibling tables in
[`implementation/fresco/spec/`](../../../implementation/fresco/spec/README.md);
where any page here differs from them, the contract is right and the page is
history. Within the record, decisions.md is the canonical statement of each
ruling, and a page here that calls it normative means that and no more. EP-0038
sequences the programme; the operator-owned standard bead carries the live
bar/kill numbers.

**Provenance and retention.** This record graduates — and fully absorbs — the
exploration corpus that lived in the untracked local `ai/findings/new-view/`
tree (three per-model charters, two independent multi-lens review cycles, a
paper budget, and the `spike-01` index model); nothing load-bearing remains only
there. The requirements harness was tracked at
`docs/design/freehand/studio/fitness-harness.md`, removed from the tree with the rest of
the Freehand record and read from git history at commit `9cee47cf38`; measured claims cite the
repo's studio/bench record and beads. The bench lane those measurements were taken on lives at `bench/fresco/` since 2026-08-29 (rf2-6c12m.1; its README maps the older `implementation/fresco/test/re_frame/bench/fresco/` paths cited across this record). Programme measurements land in
`docs/design/fresco/studio/` (minted by the first P0 worker).

This tree is excluded from the published mkdocs site and is read in the source
tree.
