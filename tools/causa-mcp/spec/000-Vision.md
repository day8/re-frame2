# 000-Vision: Causa MCP server

Causa-MCP is the **agent face of Causa**. Where
[`tools/causa/`](../../causa/) ships Causa-the-panel for human
debuggers, this artefact ships an MCP server so AI agents
(Claude Code, Cursor, Copilot, and any other host that speaks
Model Context Protocol) can drive the same surfaces — read the
trace bus, walk the epoch history, dispatch into a frame,
rewind to a named epoch, subscribe to live trace events — over
stdio JSON-RPC, without opening Causa's UI.

This spec folder is the per-tool normative contract for
`tools/causa-mcp/`. It is the **load-bearing scaffold** stood up
before implementation begins: the design decisions accumulated
inside [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
get their permanent home here. When implementation lands, this
folder gets fleshed out with the four `tools/pair2-mcp/spec/`-shape
files (`001-Wire-Protocol`, `002-nREPL-Transport`,
`003-Tool-Catalogue`) parameterised for Causa.

## Why a separate jar

Causa-the-panel is human-facing; Causa-MCP is agent-facing.
Humans and agents ship as distinct jars so the MCP server can be
loaded without dragging the entire Causa UI into the classpath;
the two surfaces split:

- **[`tools/causa/`](../../causa/)** — `day8/re-frame2-causa`. The
  DOM-injected panel. Pulled by shadow-cljs `:preloads`.
  Browser-side artefact.
- **`tools/causa-mcp/`** — `day8/re-frame2-causa-mcp`. The MCP
  server. Pulled by `npm install`. Node-side artefact, attached
  over nREPL.

Both consume the same re-frame2 instrumentation surface
([Spec 009 trace bus](../../../spec/009-Instrumentation.md),
[Tool-Pair epoch history](../../../spec/Tool-Pair.md), registrar
query API). Neither depends on the other.

The separation is the same shape `tools/story/` and
`tools/story-mcp/` use: a human-facing panel and an agent-facing
surface ship as distinct jars so the agent jar can be loaded
without the UI dependencies.

## What it is

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
launch it as a subprocess; one persistent nREPL socket is held
for the lifetime of the session; seventeen Causa-shaped tools are
exposed as MCP tools.

The architecture mirrors [`tools/pair2-mcp/`](../../pair2-mcp/)
exactly:

- Same `@modelcontextprotocol/sdk` `StdioServerTransport`.
- Same bencode-pinned nREPL bridge (per
  [`tools/pair2-mcp/spec/002-nREPL-Transport.md`](../../pair2-mcp/spec/002-nREPL-Transport.md)).
- Same port-discovery walk (`$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`).
- Same runtime-injection footing: a small sentinel namespace
  (`re-frame2-causa-mcp.runtime`) re-ships on browser reload.
- Same degraded-boot policy: tools return structured
  `{:ok? false :reason ...}` errors rather than refusing to start.

Different tool catalogue. Different `:origin` tag.

## What it isn't

- **Not** a new re-frame2 runtime extension. Tools route through
  the existing Causa instrumentation surfaces via `cljs-eval`;
  nothing new is registered against the framework.
- **Not** a Causa panel. The MCP server speaks JSON-RPC; the
  Causa UI is a separate artefact and need not be open for the
  MCP server to work.
- **Not** part of any production bundle. Per
  [`tools/README.md`](../../README.md), the dependency arrow flows
  tool → implementation; this artefact lives on a separate npm
  classpath and is invisible to consumer apps.
- **Not** a generalist agent surface. The tool catalogue is
  closed-set and shaped to Causa's panels; the deliberate escape
  valve is `eval-cljs`, which preserves the `:origin` tag.

## MCP catalogue summary

Seventeen tools across five bands (per the [canonical counts in
`README.md`](./README.md#canonical-counts)); the full enumeration
with signatures, return shapes, and example flows lives in
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
until implementation lands and this folder grows its own
`003-Tool-Catalogue.md` (the pair2-mcp-shape companion to
`Principles.md` and `DESIGN-RATIONALE.md`). The short list:

| Band | Tools |
|---|---|
| **Inspection** (read-only, 9 tools) | `get-trace-buffer`, `get-epoch-history`, `get-app-db`, `get-app-db-diff`, `get-machine-state`, `get-machine-list`, `get-issues`, `get-handlers`, `get-source-coord`. |
| **Mutation** (user-confirmed equivalents, 3 tools) | `restore-epoch`, `reset-frame-db`, `dispatch`. |
| **Streaming** (push-mode, 2 tools) | `subscribe`, `unsubscribe` — `notifications/progress` shaped, topic-mediated filters. |
| **Escape hatch** (1 tool) | `eval-cljs` — arbitrary CLJS form; any side-effects inherit `:origin :causa-mcp`. |
| **Meta** (session lifecycle, 2 tools) | `discover-app`, `tail-build`. |

Each tool maps to a Causa panel; together they let an agent
observe, dispatch, time-travel, stream, and inspect.

## Every MCP-driven mutation leaves a visible footprint

The defining property of Causa-MCP — and a first-class
differentiator against generalist agent surfaces like Chrome
DevTools MCP's `evaluate_script` — is that **every dispatch,
every `reset-frame-db`, every `restore-epoch` issued through this
server is tagged `:origin :causa-mcp` on the trace bus**. The
agent driving the server leaves an audit trail; its mutations
are filterable, separable from the user's, and distinguishable
from `:app` / `:pair` / `:story` / `:test` mutations in the same
session.

Chrome's `evaluate_script` is untagged: an agent that hand-rolls
`js/window.dispatch(...)` is **indistinguishable** in the trace
from a real user click. Causa-MCP's tag inversion
(`:origin :causa-mcp` by default; opt out at your peril) means a
post-incident audit can ask "what did the agent do?" and get a
complete, filterable answer via
`get-trace-buffer {:filter {:origin :causa-mcp}}`.

The origin tagging is **the convention**, not a suggestion —
captured as a load-bearing principle in
[`Principles.md`](./Principles.md) and locked in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Relationship to Causa

Causa-MCP **consumes** Causa's runtime surfaces via the shared
[Spec 009](../../../spec/009-Instrumentation.md) instrumentation
contract:

| Causa panel | Causa-MCP tool(s) |
|---|---|
| Trace timeline | `get-trace-buffer`, `subscribe :trace` |
| Time-travel scrubber | `get-epoch-history`, `restore-epoch`, `subscribe :epoch` |
| App-db inspector | `get-app-db`, `get-app-db-diff` |
| Machine inspector | `get-machine-state`, `get-machine-list` |
| Issues ribbon | `get-issues`, `subscribe :error` |
| Settings → registry browser | `get-handlers`, `get-source-coord` |
| Right-click → Re-dispatch | `dispatch` |
| "Try anyway" on schema mismatch | `reset-frame-db` |
| Bottom-rail Rewind button | `restore-epoch` |

Neither artefact depends on the other at the classpath. The
Causa panel works without the MCP server; the MCP server works
without the panel. The two coexist in the same session — agents
can drive Causa-MCP while a human inspects the Causa panel; both
see the same runtime, and the `:origin` tag is what lets a human
scrubbing the timeline see at-a-glance which mutations the agent
authored.

## Relationship to `tools/pair2-mcp/`

Causa-MCP is the **debugger-side** counterpart to pair2-mcp's
**editor-side** workflow:

| Axis | [`tools/pair2-mcp/`](../../pair2-mcp/) | `tools/causa-mcp/` |
|---|---|---|
| Audience | Editor-side AI workflows (build/edit/test). | Debugger-side AI workflows (inspect/time-travel). |
| Surface | 9 tools (dispatch, eval, hot-swap, trace, streaming). | 17 tools (inspection + mutation + streaming + escape hatch + session lifecycle). |
| `:origin` tag | `:pair` | `:causa-mcp` |
| Runtime ns | `re-frame-pair2.runtime` | `re-frame2-causa-mcp.runtime` |
| Implementation | shadow-cljs `:node-script`, npm-published. | Same. |
| MCP transport | stdio JSON-RPC 2.0. | Same. |

Both can coexist in the same session — the agent picks the right
tool for the workflow (pair2-mcp for "build/edit/test";
causa-mcp for "inspect/debug/time-travel"). They don't talk to
each other; the agent host multiplexes.

## Relationship to Chrome DevTools MCP

[Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp)
owns the **browser-substrate** (screenshots, DOM, network,
memory, performance, 46 tools across 8 categories as of v0.26.0,
May 2026). Causa-MCP owns the **app-domain** (re-frame2 events,
subs, machines, app-db, epochs). They are **complementary, not
competitive** — an agent debugging a real bug typically wants
both, and the agent host's `mcpServers` config loads them
side-by-side.

The defining differentiator on the rows that overlap (e.g.
`get-trace-buffer` vs Chrome's `list_console_messages`) is
**structured trace**. Chrome's console is a flat string log;
Causa-MCP's trace bus carries `:dispatch-id`,
`:parent-dispatch-id`, `:source-coord`, `:origin`, `:event-id`,
`:handler-id`, `:since-ms`, `:between`, `:pred` as filter axes.
Every domain question is a filter, not a regex.

Per-tool comparison and the co-install snippet are in
[`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
§Chrome DevTools MCP co-install. When implementation lands and
this folder grows `003-Tool-Catalogue.md`, the comparison moves
with the catalogue.

## Why ClojureScript + shadow-cljs → Node

Same answer as pair2-mcp (per
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
Lock #1). The team runs shadow-cljs daily; one more
`:node-script` target is trivial; the npm MCP SDK is reachable
via js-interop; end users install Node only. JVM Clojure was
rejected for startup cost; nbb / pure babashka for tooling
maturity; foreign-language stacks for ecosystem mismatch.

Captured as Lock #1 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Why a server, not an IDE plugin

MCP is the agent-host's contract for tool integration. By
implementing MCP over stdio, this artefact works with **every**
MCP-capable host (Claude Code, Cursor, Copilot, etc.) without
per-host plumbing.

Captured as Lock #2 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Status

**Spec scaffold.** No source yet. The implementation work begins
after Causa's panel ratifies; the four pair2-mcp-shape capability
files (`001-Wire-Protocol`, `002-nREPL-Transport`,
`003-Tool-Catalogue`, plus an `API.md`) land at that point. The
locked design decisions accumulated to date live in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md); the load-bearing
tie-breakers live in [`Principles.md`](./Principles.md).

The forward references that motivated this scaffold:

- [`tools/causa/spec/010-MCP-Server.md`](../../causa/spec/010-MCP-Server.md)
  describes the entire MCP contract from Causa's side. When this
  folder grows `003-Tool-Catalogue.md`, the catalogue prose
  migrates here and `010-MCP-Server.md` shrinks to a pointer.
- [`tools/causa/README.md`](../../causa/README.md) §MCP
  advertises `npm install -g @day8/re-frame2-causa-mcp` — the
  npm coord is locked even though the package doesn't exist
  yet. The scaffold prevents that coord becoming an orphan when
  implementation lands.
