# Causa-MCP — Spec

Spec scaffold for [`tools/causa-mcp/`](../). No implementation
yet; the folder is the **permanent home** for design decisions
accumulated inside
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
before this folder existed.

## Files

- **[000-Vision.md](000-Vision.md)** — Causa-MCP is the agent face of Causa; seventeen MCP tools across five bands; same shadow-cljs / Node / persistent-nREPL architecture as pair2-mcp; relationship to Causa, pair2-mcp, and Chrome DevTools MCP.
- **[Principles.md](Principles.md)** — Load-bearing principles: origin tagging is the convention (not a suggestion), EDN canonical, closed-set catalogue with `eval-cljs` as the deliberate escape valve, single persistent nREPL socket, stage-marker-independent, and the **six-mechanism wire-protocol budget posture** (token cap + path slicing + cursor pagination + lazy summary + structural dedup + size elision via `:rf.size/large-elided`) — baked into the spec before implementation begins so the impl is born compliant.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — Ten direction-setting decisions: question, options, pick, why, date locked. Inheritance from pair2-mcp's locks is cited rather than duplicated.

## Files that will land at implementation time

Following [`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/)'s
shape, the implementation phase will add:

- `001-Wire-Protocol.md` — Newline-delimited JSON-RPC 2.0 over stdio per the MCP 2025-06-18 transport spec.
- `002-nREPL-Transport.md` — Persistent TCP socket; bencode framing; port discovery; runtime injection.
- `003-Tool-Catalogue.md` — The seventeen tools, with full signatures and return shapes. (Currently the catalogue lives in [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md) §Tool catalogue; the prose will migrate here.)
- `API.md` — Consolidated user-facing reference: install, configure, launch, call.
- `findings/` — Exploratory working substrate; audit lineage, not normative.

## Canonical counts

The numbers asserted in prose throughout this folder. **Cite this
subsection from sibling docs rather than restating the counts** —
that's how drift is prevented when a count next moves.

| Count | Value | Source-of-truth |
|---|---|---|
| **Tools** | **17** | [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md) §Tool catalogue — the catalogue prose enumerates each. Migrates to `003-Tool-Catalogue.md` when impl lands. |
| **Bands** | **5** | Inspection (9) + Mutation (3) + Streaming (2) + Escape hatch (1) + Meta (2) = 17. Enumerated in [`000-Vision.md`](000-Vision.md) §MCP catalogue summary. |
| **Mechanisms** | **6** | [`Principles.md`](Principles.md) §"Tight token budget per response" — token cap, path slicing, cursor pagination, lazy summary, structural dedup, size elision. Mechanism #6 promoted by Lock #10. |
| **Locks** | **10** | [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §Summary table. |

## How to use

Read [`000-Vision.md`](000-Vision.md) first to anchor *why*.
[`Principles.md`](Principles.md) is the tie-breaker doc when
two reasonable design choices conflict.
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) captures the locks
— decisions that survived discussion and shouldn't be re-asked.

Until the implementation-phase files land, the **catalogue
canonical** is
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Tool catalogue. Cross-references from this folder back into
Causa's spec are deliberate; the orphan-lock lift will happen
when `003-Tool-Catalogue.md` is authored.
