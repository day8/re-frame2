# 010-MCP-Server

Causa ships an MCP server as a separate jar at `tools/10x-mcp/`. The
server exposes Causa's surfaces as MCP tools so AI agents (Claude
Code, Cursor, Copilot) can read and write a live re-frame2 runtime
without opening Causa's UI.

The architecture mirrors `tools/pair2-mcp/` (per
[`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/)): a Node-based stdio
JSON-RPC server, written in ClojureScript, compiled via shadow-cljs
to a single `.js` file. One persistent nREPL socket per session.
Different tool catalogue.

## Why a separate jar

Causa-the-panel is human-facing; Causa-MCP is agent-facing. Per the
rf2-m6tu §6.1 separation lesson (humans and agents ship as distinct
jars so the MCP server can be loaded without dragging the entire
Causa UI into the classpath), the two surfaces split:

- **`tools/10x/`** — `day8/re-frame2-causa`. The DOM-injected panel.
  Pulled by `:preloads`. Browser-side artefact.
- **`tools/10x-mcp/`** — `day8/re-frame2-causa-mcp`. The MCP server.
  Pulled by `npm install`. Node-side artefact, attached over nREPL.

Both consume the same re-frame2 instrumentation surface (Spec 009
trace bus, Tool-Pair epoch history, registrar query API). Neither
depends on the other.

## Transport

Same as `tools/pair2-mcp/spec/001-Wire-Protocol.md`:

- Newline-delimited JSON-RPC 2.0 over stdio per the
  [MCP stdio transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
- One message per line on stdin/stdout. UTF-8. No embedded newlines.
- npm `@modelcontextprotocol/sdk`'s `StdioServerTransport` provides
  the framing.
- stdout reserved for MCP messages; stderr free-form.

Causa-MCP follows the same lifecycle:

1. Agent host launches the server as a subprocess.
2. Server responds to `initialize` with `protocolVersion`,
   `capabilities`, `serverInfo`.
3. `tools/list`, `tools/call` dispatch via the SDK's
   `setRequestHandler`.
4. Shutdown: client closes stdin → Node EOF → process exits.

## nREPL bridge

Same as `tools/pair2-mcp/spec/002-nREPL-Transport.md`:

- One TCP socket to `127.0.0.1:<nrepl-port>` held for the session.
- bencode framing via `bencode@2.0.x` (pinned for CommonJS
  compatibility).
- Multiplexing by UUID id over the socket's `pending` map.
- Port discovery via `$SHADOW_CLJS_NREPL_PORT` → `target/shadow-cljs/nrepl.port`
  → `.shadow-cljs/nrepl.port` → `.nrepl-port`.
- Degraded boot if no port is reachable; tools return
  `{:ok? false :reason :nrepl-port-not-found}`.

Causa-MCP injects a small runtime sentinel (`re-frame2-causa-mcp.runtime/session-id`)
that tools probe via `cljs-eval`. If the var is empty (e.g., after a
browser reload), the MCP server re-ships the runtime namespace before
the actual op runs. Mirrors the bash-shim chain's
`runtime-already-injected?` check (and the pair2-mcp implementation).

## Tool catalogue

Twelve MCP tools. Each maps to a Causa surface; together they let an
agent observe, dispatch, time-travel, and inspect.

### Inspection (read-only)

| Tool | Mirrors Causa panel | Returns |
|---|---|---|
| `get-trace-buffer` | Trace timeline | A slice of the trace stream by filter (`:op-type`, `:operation`, `:frame`, `:dispatch-id`, `:origin`, time range). |
| `get-epoch-history` | Time-travel scrubber | Per-frame epoch history (vector of `:rf/epoch-record`). |
| `get-app-db` | App-db inspector | Current value at a frame, optionally at a path. |
| `get-app-db-diff` | App-db inspector (slice view) | The slice diff for a named epoch (`{:added [...] :modified [...] :removed [...]}`). |
| `get-machine-state` | Machine inspector | Current snapshot for a named machine. |
| `get-machine-list` | Machine inspector | List of registered machines per frame, with current state. |
| `get-issues` | Issues ribbon | Recent errors / warnings / schema violations / hydration mismatches. |
| `get-handlers` | (Settings → registry browser) | Registered handlers' metadata (`:doc`, source coords). |
| `get-source-coord` | Click-to-source | Source coord for a given id (handler, view, machine state, sub). |

### Mutation (user-confirmed equivalents in the UI)

| Tool | Mirrors Causa surface | Behaviour |
|---|---|---|
| `restore-epoch` | Bottom-rail Rewind button | Rewinds a frame's `app-db` to the named epoch's `:db-after`. Returns the six failure modes structurally. |
| `reset-frame-db` | "Try anyway" affordance on schema mismatch | Injects state, bypassing cascade; schema-validates against current schemas. |
| `dispatch` | Right-click → Re-dispatch | Fires an event tagged `:origin :causa-mcp` (so a separate trace tag distinguishes MCP-issued from in-panel-issued mutations). |

### Why `dispatch` is in the catalogue

Causa-MCP is **the agent face of Causa**. An agent driving Causa-MCP
is the user's delegate; the user has given consent (by enabling the
MCP server and giving the agent a key). Dispatch from the agent is
no more dangerous than dispatch from the agent driving
`tools/pair2-mcp/` — and we already ship that.

The `:origin :causa-mcp` tag means every dispatch from the MCP
surface is **visibly distinguishable** in the trace stream — Causa's
event log shows them in a distinct colour (per
[`007-UX-IA.md`](./007-UX-IA.md) §Colour system), and post-session
filters surface them as a group.

## Args and return shapes

Each tool's args / returns follow the same EDN-encoded JSON pattern
as `tools/pair2-mcp/`:

```jsonc
// Example: get-app-db
{ "method": "tools/call",
  "params": {
    "name": "get-app-db",
    "arguments": {
      "frame": ":app/main",
      "path": "[:cart :items]"
    } } }

// Response:
{ "content": [{ "type": "text",
                "text": "{:ok? true :value [{:id 7 :qty 1} {:id 22 :qty 1}]}" }],
  "isError": false }
```

Tool-execution errors use `isError: true` with a structured EDN
error map in `content[0].text` — never a protocol-level error.

The full schema of each tool's args is bundled in
`tools/10x-mcp/src/day8/re_frame2_causa_mcp/tools.cljs` (matching
the layout in `tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`)
and surfaced via `tools/list` in MCP-standard JSONSchema form.

## Lifecycle and reconnect

Same as pair2-mcp. A full page reload destroys the CLJS runtime in
the browser but leaves the JVM-side nREPL socket intact. Every tool
that needs the runtime calls `ensure-runtime!`:

1. `cljs-eval` `re-frame2-causa-mcp.runtime/session-id`.
2. If a non-blank string comes back, the runtime is live; proceed.
3. Otherwise, slurp the canonical `runtime.cljs` (shipped with the
   MCP jar) and ship it through `cljs-eval`. Then proceed.

No manual reconnect step is needed — page reload → next tool call →
automatic re-inject → op proceeds.

## Compared to pair2-mcp

| Axis | `tools/pair2-mcp/` | `tools/10x-mcp/` |
|---|---|---|
| **Audience** | Editor-side AI workflows (pair-programming). | Debugger-side AI workflows (inspection, time-travel). |
| **Surface** | 7 tools focused on dispatch / eval / hot-swap / trace. | 12 tools focused on inspection (graph, app-db, machine, issues) + restore / reset / dispatch. |
| **`:origin` tag** | `:pair` | `:causa-mcp` |
| **Runtime injection** | `re-frame-pair2.runtime` | `re-frame2-causa-mcp.runtime` |
| **Implementation** | shadow-cljs `:node-script`, npm-published. | Same. |
| **MCP transport** | stdio JSON-RPC 2.0. | Same. |

Both can coexist in the same session. The agent picks the right tool
for the workflow: pair2 for "build/edit/test"; causa-mcp for
"inspect/debug/time-travel."

## Install + configure

```bash
npm install -g @day8/re-frame2-causa-mcp
```

```jsonc
// ~/.claude/settings.json
{
  "mcpServers": {
    "causa": {
      "command": "re-frame2-causa-mcp",
      "env": { "SHADOW_CLJS_BUILD_ID": "app" }
    }
  }
}
```

First-call sanity check:

```text
Agent: tools/call get-trace-buffer {limit: 10}
Server: {:ok? true :events [...10 events...]}
```

## What this doesn't do

- **No GUI rendering.** The MCP server has no UI; it speaks JSON-RPC
  to its agent host. The Causa panel is unaffected (and need not be
  open) for the MCP server to work.
- **No telemetry.** The server transmits nothing to Day8.
- **No automatic re-dispatch.** Dispatches go through the same gates
  as pair2-mcp; the agent is the user's delegate.
- **No conversation history.** The MCP server is stateless across
  tool calls; conversation state belongs to the agent host.

## Spec scope

This doc is the **contract**, not the implementation. The
implementation details (shadow-cljs build, the bencode pin, the
specific tools.cljs shape) follow `tools/pair2-mcp/spec/`'s structure
and prose; replicating them here would be redundant. When
`tools/10x-mcp/` lands, it will carry its own `spec/` folder with
the four pair2-mcp-shape files (000-Vision, 001-Wire-Protocol,
002-nREPL-Transport, 003-Tool-Catalogue) parameterised for Causa.
