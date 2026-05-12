# tools/pair2-mcp/

`@day8/re-frame-pair2-mcp` — the **MCP (Model Context Protocol) server**
that pair-programs with a live re-frame2 application over a persistent
nREPL connection.

This is the structural successor to the bash-shim → babashka → nREPL
chain under `skills/re-frame-pair2/scripts/`. The shims still ship for
back-compat, but new sessions should prefer the MCP server.

## What it is

A Node-based stdio JSON-RPC server (written in ClojureScript, compiled
via shadow-cljs to a single `.js` file) that exposes the seven pair2
ops as MCP tools. AI agents (Claude Code, Cursor, Copilot) launch it
as a subprocess; one persistent nREPL socket is held for the lifetime
of the session.

Per-op latency drops from ~700ms (bash startup + babashka startup +
fresh nREPL connect per call) to ~5–50ms (one bencode round-trip on
the open socket).

## Tool surface

| MCP tool       | Bash-shim equivalent      | What it does |
|----------------|---------------------------|--------------|
| `discover-app` | `discover-app.sh`         | Verify shadow-cljs nREPL is reachable, inject the pair2 runtime, return a health summary. Run first every session. |
| `eval-cljs`    | `eval-cljs.sh`            | Evaluate a CLJS form via shadow-cljs's `cljs-eval`. Returns the EDN value. |
| `inject-runtime` | `inject-runtime.sh`     | Force a re-ship of `runtime.cljs` to the connected runtime. |
| `dispatch`     | `dispatch.sh`             | Fire a re-frame2 event with `:origin :pair`. Modes: queued, sync, trace. Frame and fx-overrides supported. |
| `trace-window` | `trace-window.sh`         | Return the epochs that landed in the last N ms. |
| `watch-epochs` | `watch-epochs.sh`         | Pull-mode poll for matching epochs added after a given epoch-id. Predicate keys: `:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`. |
| `tail-build`   | `tail-build.sh`           | Wait for a hot-reload to land by polling a probe form until its value changes. |

## Quick start

### Install

```bash
npm install -g @day8/re-frame-pair2-mcp
```

(or use `npx @day8/re-frame-pair2-mcp` for one-off runs).

### Configure Claude Code

Add to your `~/.claude/settings.json` (or per-project `.claude/settings.json`):

```json
{
  "mcpServers": {
    "re-frame-pair2": {
      "command": "re-frame-pair2-mcp",
      "env": {
        "SHADOW_CLJS_BUILD_ID": "app"
      }
    }
  }
}
```

The server auto-discovers the nREPL port from (in order):

1. `$SHADOW_CLJS_NREPL_PORT` env var
2. `target/shadow-cljs/nrepl.port`
3. `.shadow-cljs/nrepl.port`
4. `.nrepl-port`

### First call

```text
Agent: tools/call eval-cljs {form: "@(rf/subscribe [:user/email])"}
Server: {:ok? true :value "alice@example.com"}
```

Or, with the pair2 skill loaded, just describe the task — the skill's
SKILL.md teaches the agent which tool to call. See
[`../../skills/re-frame-pair2/SKILL.md`](../../skills/re-frame-pair2/SKILL.md).

## How sentinel-based reconnect works

A full page reload in the browser destroys the CLJS runtime but
leaves the nREPL socket on the JVM side intact. The bash-shim chain
detected this by probing `re-frame-pair2.runtime/session-id` —
if the var was empty, the runtime was re-shipped.

The MCP server ports the same check. Every tool that needs the runtime
calls `ensure-runtime!` first; if the sentinel is missing, `runtime.cljs`
is re-injected from the skill before the actual op runs. No manual
reconnect step is needed — page reload → next tool call → automatic
re-inject → op proceeds.

## Spec

The contract lives in [`spec/`](./spec/):

| File | Covers |
|------|--------|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this server is, why it replaces the bash-shim chain. |
| [`spec/001-Wire-Protocol.md`](./spec/001-Wire-Protocol.md) | JSON-RPC 2.0 over stdio; lifecycle; tool dispatch. |
| [`spec/002-nREPL-Transport.md`](./spec/002-nREPL-Transport.md) | Persistent socket, bencode framing, sentinel-based reconnect. |
| [`spec/003-Tool-Catalogue.md`](./spec/003-Tool-Catalogue.md) | The seven tools, their argument schemas, EDN result shape. |

## Development

```bash
# Install deps
npm install

# Compile production build
npm run build      # → out/server.js

# Watch mode
npm run watch

# Unit tests (cljs)
npm test

# Stdio integration test (no nREPL needed — exercises the degraded path)
npm run stdio-roundtrip

# Live-nREPL integration test (requires an nREPL running on $NREPL_TEST_PORT)
NREPL_TEST_PORT=17777 node test/live-nrepl.js
```

## Implementation language

ClojureScript compiled via shadow-cljs to a `:node-script` target.
End users install Node only; the compiled output is plain JS. The
language pick is locked — see the bead notes on the implementing PR.

`pilot/` contains the original toolchain pilot — a minimal MCP server
with two tools (`ping` and `nrepl-ping`) used to verify the
shadow-cljs + npm MCP SDK + bencode round-trip worked before the full
port. Kept for reference and as a stripped-down smoke harness.

## File layout

```
tools/pair2-mcp/
├── README.md                                 ; this file
├── package.json                              ; npm package
├── shadow-cljs.edn                           ; build config
├── spec/                                     ; contract
├── pilot/                                    ; pre-port toolchain pilot
└── src/re_frame_pair2_mcp/
    ├── nrepl.cljs                            ; persistent socket + bencode
    ├── tools.cljs                            ; the 7 MCP tools
    └── server.cljs                           ; stdio JSON-RPC entry point
└── test/
    ├── re_frame_pair2_mcp/nrepl_test.cljs    ; bencode framing unit tests
    ├── stdio-roundtrip.js                    ; stdio integration test
    └── live-nrepl.js                         ; live-nREPL integration test
```

## Back-compat with the bash shims

The shims under `skills/re-frame-pair2/scripts/` still work and are
not slated for removal in this drop. Their headers carry a
deprecation notice pointing here. Migration is opt-in per session;
agents can mix shim calls and MCP tool calls in the same workflow
during the transition.
