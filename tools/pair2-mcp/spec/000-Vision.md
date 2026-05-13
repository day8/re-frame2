# 000-Vision: re-frame-pair2 MCP server

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> pair2-mcp is the canonical consumer of `get-frame-db`,
> `epoch-history`, `register-trace-cb!`, `register-epoch-cb!`,
> `restore-epoch`, `reset-frame-db!`, `dispatch`, `dispatch-sync`,
> plus the destroyed-frame and operating-frame rules.

## Why it exists

The bash-shim chain in `skills/re-frame-pair2/scripts/` pays a heavy
per-op cost: bash startup (~50ms on Windows), babashka cold-start
(~50–100ms), fresh nREPL TCP connect (~200–500ms cold), bencode
round-trip, process teardown. First-op latency lands near 700ms; the
shape of the protocol means the cost is paid *every* call because
each invocation is a one-shot process.

This MCP server pays the cold-connect cost **once per session**.
Every subsequent op is just a bencode round-trip on the open socket,
landing at ~5–50ms. The break-even point is one op — and a typical
pair2 session fires dozens to hundreds.

## What it is

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
(Claude Code, Cursor, Copilot) launch it as a subprocess; one
persistent nREPL socket is held for the lifetime of the session;
the nine canonical pair2 ops are exposed as MCP tools.

## What it isn't

- **Not** a new pair2 contract. The op vocabulary is identical to the
  bash-shim catalogue minus `inject-runtime` (rf2-7dvg cut the
  inject step in favour of a shadow-cljs `:preloads` entry).
- **Not** a re-frame2 runtime extension. It calls into the
  `re-frame-pair2.runtime` namespace the consumer app preloads via
  shadow-cljs; nothing new is registered against the framework.
- **Not** part of any production bundle. Per `tools/README.md`, the
  dependency arrow flows tool → implementation; this artefact lives
  on a separate npm classpath and is invisible to consumer apps.

## Why ClojureScript + shadow-cljs → Node

Pick locked during the toolchain pilot (see PR description). The team
runs shadow-cljs daily, one more `:node-script` target is trivial,
the npm MCP SDK is reachable via js-interop, and end users install
Node only. JVM Clojure was rejected for startup; nbb / pure babashka
for tooling maturity; foreign-language stacks for ecosystem mismatch.

## Why a server, not an IDE plugin

MCP is the agent-host's contract for tool integration. By implementing
MCP over stdio, this artefact works with **every** MCP-capable host
(Claude Code, Cursor, Copilot, etc.) without per-host plumbing.
