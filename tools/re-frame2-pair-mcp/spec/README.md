# re-frame2-pair MCP server — Spec

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> the framework-side contract for pair-shaped AI tools. re-frame2-pair-mcp is
> the canonical consumer of its primitives.

## Files

- **[000-Vision.md](000-Vision.md)** — Why an MCP server beats the bash-shim chain: pay cold-connect cost once per session, not per op (~700ms → ~5–50ms).
- **[001-Wire-Protocol.md](001-Wire-Protocol.md)** — Newline-delimited JSON-RPC 2.0 over stdio per the MCP 2025-06-18 transport spec; framed by `@modelcontextprotocol/sdk`.
- **[002-nREPL-Transport.md](002-nREPL-Transport.md)** — Persistent TCP socket to `127.0.0.1:<nrepl-port>`; multiplexed by UUID `id`; held for session lifetime.
- **[003-Tool-Catalogue.md](003-Tool-Catalogue.md)** — The twenty-eight MCP tools (canonical count and full catalogue here): the bash-shim mirrors `discover-app` / `eval-cljs` / `dispatch` / `trace-window` / `watch-epochs` / `tail-build`; the mega-op reads `snapshot` / `get-path`; the streaming triad `subscribe` / `unsubscribe` / `list-subscriptions` (rf2-hq49, rf2-zjz9q; `list-subscriptions` renamed from `subscription-info` per rf2-4y595) plus `list-streams`; the registrar-introspection pair `handler-meta` / `list-handlers` (rf2-cibp8, rf2-pctf8; `list-handlers` renamed from `registry-list` per rf2-4y595); the write pair `restore-epoch` / `reset-frame-db` and `dispatch-dry-run`; the read-orientation pair `orient` / `read-sub` (rf2-3bu3d.7/.8); the view-plane reads `read-dom` / `read-ui`; the signal recorder `record` / `read-recording` / `watch-until` (rf2-zo4b9); the operating-frame trio `set-operating-frame` / `reset-operating-frame` / `get-operating-frame` (rf2-zomfq); and `get-re-frame2-pair-instructions` agent-onboarding text (rf2-fnpqg).
- **[API.md](API.md)** — Consolidated user-facing reference for installing, configuring, launching, and calling re-frame2-pair-mcp.
- **[Principles.md](Principles.md)** — re-frame2-pair-mcp-specific load-bearing principles, downstream of framework `Principles.md`.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — Direction-setting decisions: question, options, pick, why, date locked.
- **[findings/](findings/)** — Exploratory working substrate; audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor *why*; the capability docs (001–003) are normative — they own the wire protocol, nREPL transport, and tool catalogue respectively. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated reference where the per-area specs win on drift. `findings/` preserves the audit lineage and is never normative.
