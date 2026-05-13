# tools/causa-mcp/

`day8/re-frame2-causa-mcp` — the **MCP (Model Context Protocol)
agent surface** for [Causa](../causa/). Spec only; no
implementation yet.

## Status

**Spec scaffold.** This folder contains the per-tool spec/
contract ([`spec/`](./spec/)) and nothing else. No `deps.edn`,
no `src/`, no `test/`. Implementation work begins after Causa's
panel ratifies; the four pair2-mcp-shape capability files
(`001-Wire-Protocol`, `002-nREPL-Transport`,
`003-Tool-Catalogue`, plus an `API.md`) land at that point.

The scaffold exists so that direction-setting decisions
accumulated inside
[`tools/causa/spec/010-MCP-Server.md`](../causa/spec/010-MCP-Server.md)
have a permanent home. Per the per-tool spec convention in
[`tools/README.md`](../README.md), every shipped tool has a
local `spec/` folder; this one is the spec folder ahead of the
tool.

## What it will be

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
(Claude Code, Cursor, Copilot) launch it as a subprocess; one
persistent nREPL socket is held for the lifetime of the session;
eighteen Causa-shaped MCP tools are exposed.

The architecture mirrors [`tools/pair2-mcp/`](../pair2-mcp/)
exactly — same transport, same nREPL bridge, same bencode pin,
same runtime-injection footing. Different tool catalogue,
different `:origin` tag.

Full design contract:
[`spec/`](./spec/).

## Where the depth lives

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this jar is, why it's separate from Causa, how the eighteen-tool catalogue maps to Causa's panels, the relationship to Chrome DevTools MCP. |
| [`spec/Principles.md`](./spec/Principles.md) | Load-bearing principles: origin tagging is the convention, EDN canonical, closed-set catalogue with deliberate escape valve, same single-persistent-nREPL footing as pair2-mcp. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | The eight direction-setting decisions: question, options, pick, why, date locked. |

The full tool catalogue (signatures, return shapes, example
flows) lives in
[`tools/causa/spec/010-MCP-Server.md`](../causa/spec/010-MCP-Server.md)
until implementation lands and this folder grows
`003-Tool-Catalogue.md`.

## See also

- [`spec/`](./spec/) — this jar's contract.
- [`tools/causa/`](../causa/) — Causa-the-panel; the human face
  of the same instrumentation surface.
- [`tools/causa/spec/010-MCP-Server.md`](../causa/spec/010-MCP-Server.md) —
  the full MCP catalogue and Chrome MCP per-tool comparison
  (will migrate into this folder when implementation lands).
- [`tools/pair2-mcp/`](../pair2-mcp/) — the editor-side
  counterpart; same architecture, different catalogue.
- [`tools/README.md`](../README.md) — per-tool jar convention +
  bundle isolation.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) —
  the trace-bus contract Causa-MCP consumes.
