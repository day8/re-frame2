# Story-MCP — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — What `day8/re-frame2-story-mcp` is, why it ships as a separate jar from `day8/re-frame2-story`, and how it relates to Story's runtime and the Tool-Pair contract.
- **[001-Wire-Protocol.md](001-Wire-Protocol.md)** — JSON-RPC 2.0 over stdio; the `initialize` handshake; `tools/list` + `tools/call`; the protocol-version pin.
- **[002-Tool-Registry.md](002-Tool-Registry.md)** — The 19 tools across four categories — Dev (3: `get-story-instructions`, `preview-variant`, `list-substrates`), Docs (9: `list-stories`, `get-story`, `get-variant`, `list-tags`, `list-modes`, `list-decorators`, `list-assertions`, `variant->edn`, `get-docs-markdown`), Testing (4: `run-variant`, `snapshot-identity`, `run-a11y`, `read-failures`), Write (3, gated: `register-variant`, `unregister-variant`, `record-as-variant`). One paragraph orientation per tool.
- **[003-Write-Surface-Gating.md](003-Write-Surface-Gating.md)** — The `:rf.story-mcp/allow-writes?` config gate: what's gated, how it errors clean rather than no-ops.
- **[API.md](API.md)** — Consolidated tool surface: per-tool input schema, output shape, error mode; cross-links to the category doc.
- **[Principles.md](Principles.md)** — story-mcp-specific load-bearing principles, downstream of framework + Story principles.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — WHY each major design call was made: Cheshire over data.json, independent stage marker, pinned protocol version, opt-in write gate.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor the separate-jar choice and the Story-core boundary; the capability docs (001–003) are normative — they own the wire protocol, the tool registry, and the write-surface gating. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated tool-surface reference; per-area specs win on drift. The companion Story surface contract lives at [`../../story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md).
