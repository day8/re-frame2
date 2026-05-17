# Machines-Viz-MCP — Spec

Spec scaffold for [`tools/machines-viz-mcp/`](../). No
implementation yet; this folder is the **permanent home** for the
direction-setting calls (separate jar, MCP-over-stdio, share-URL
encoding parity, read-only posture, off-box egress contract)
pinned pre-implementation so consumers can plan against them.

The folder layout mirrors
[`tools/causa-mcp/spec/`](../../causa-mcp/spec/) — the sibling MCP
server that already split Vision / Principles / Wire-Pipeline /
Locks under the same template. Cross-MCP symmetry is deliberate:
an implementer reading this folder against causa-mcp's recognises
the same five docs in the same roles.

## Files

- **[000-Vision.md](000-Vision.md)** — Machines-Viz-MCP is the agent face of Machines-Viz; same shadow-cljs / Node / persistent-nREPL architecture as pair2-mcp + causa-mcp; relationship to Machines-Viz, Causa-MCP, Pair2-MCP, and Chrome DevTools MCP; sketch of the tool catalogue (list / fetch / encode-share-url / export / subscribe / meta); v1 vs v1.1.
- **[Principles.md](Principles.md)** — Load-bearing principles: downstream-consumer-of-the-framework rule, MCP-server-ns and injected-runtime-ns are distinct, share/export inherits the tightened upstream schema with no relaxation, read-only by default. Defers to [`004-Wire-Pipeline.md`](004-Wire-Pipeline.md) for the wire-pipeline cascade.
- **[004-Wire-Pipeline.md](004-Wire-Pipeline.md)** — The off-box egress contract: privacy default-drop on `:sensitive?` events at the MCP boundary, the `elide-wire-value` wire-boundary walker for every tree-shaped read, the four reserved indicator slots (`:include-sensitive?` / `:include-large?` opt-in flags; `:dropped-sensitive` / `:elided-large` response slots), the share-URL + export inheritance posture, and the conformance-test requirements baked in before implementation begins. Aligns with sibling MCP servers' wire-pipeline contracts so cross-server symmetry holds.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — Direction-setting decisions as locks: question, options, pick, why, date locked. Inheritance from upstream Machines-Viz locks (and the sibling causa-mcp / pair2-mcp locks the architecture template lifts) is cited rather than duplicated.

## Files that will land at implementation time

Following [`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/)'s and
[`tools/causa-mcp/spec/`](../../causa-mcp/spec/)'s shape, the
implementation phase will add:

- `001-Wire-Protocol.md` — Newline-delimited JSON-RPC 2.0 over stdio per the MCP 2025-06-18 transport spec.
- `002-nREPL-Transport.md` — Persistent TCP socket; bencode framing; port discovery; runtime injection.
- `003-Tool-Catalogue.md` — The shipped tool catalogue, with full signatures and return shapes. (Currently sketched in [`000-Vision.md` §What the catalogue covers (sketch)](000-Vision.md#what-the-catalogue-covers-sketch); fleshed out at implementation time.)
- `API.md` — Consolidated user-facing reference: install, configure, launch, call.
- `findings/` — Exploratory working substrate; audit lineage, not normative.

## How to use

Read [`000-Vision.md`](000-Vision.md) first to anchor *why*.
[`Principles.md`](Principles.md) is the tie-breaker doc when two
reasonable design choices conflict.
[`004-Wire-Pipeline.md`](004-Wire-Pipeline.md) carries the
off-box egress contract (privacy default-drop + elision walker +
indicator slots + share/export inheritance) — required reading
before any tool implementation lands.
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) captures the locks
— decisions that survived discussion and shouldn't be re-asked.

## Relationship to the upstream Machines-Viz spec

Machines-Viz-the-component lives at
[`tools/machines-viz/spec/`](../../machines-viz/spec/). This
folder is the agent-facing wrapper; the upstream spec is the
source-of-truth for the share-URL payload schema, the
`MachineChart` rendering contract, and the v1.0 component
commitment. Cross-references back to that folder are deliberate
— the same separation Causa / Causa-MCP uses.
