# 000-Vision: re-frame2-pair MCP server

> Implements the [Tool-Pair contract](../../../spec/Tool-Pair.md) —
> re-frame2-pair-mcp is the canonical consumer of `app-db-value`,
> `epoch-history`, `register-listener!`, `register-epoch-listener!`,
> `restore-epoch`, `replace-frame-state!`, `dispatch`, `dispatch-sync`,
> plus the destroyed-frame and operating-frame rules.

## Why it exists

The bash-shim chain in `skills/re-frame2-pair/scripts/` pays a heavy
per-op cost: bash startup (~50ms on Windows), babashka cold-start
(~50–100ms), fresh nREPL TCP connect (~200–500ms cold), bencode
round-trip, process teardown. First-op latency lands near 700ms; the
shape of the protocol means the cost is paid *every* call because
each invocation is a one-shot process.

This MCP server pays the cold-connect cost **once per session**.
Every subsequent op is just a bencode round-trip on the open socket,
landing at ~5–50ms. The break-even point is one op — and a typical
re-frame2-pair session fires dozens to hundreds.

## What it is

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
(Claude Code, Cursor, Copilot) launch it as a subprocess; one
persistent nREPL socket is held for the lifetime of the session;
the canonical re-frame2-pair ops are exposed as MCP tools — see
[`003-Tool-Catalogue.md`](003-Tool-Catalogue.md) for the live count.

## Architecture at a glance

The artefact is ~14,700 LOC across ~60 source files — intrinsically large
(live nREPL bridge + streaming + the full tool catalogue + roots-discovery
+ resource controls; see [`003-Tool-Catalogue.md`](003-Tool-Catalogue.md)
for the live tool count). The layering is straightforward but only surfaces from
reading multiple ns docstrings; this diagram sits at the entrance so a
new reader (human or AI pair) is oriented in 60 seconds.

```
                          MCP client (Claude Code / Cursor / Copilot)
                              │
                              ▼  tools/call request (stdio, JSON-RPC)
                       ┌─────────────────────────────────────────────┐
                       │  server.cljs                                │
                       │    lifecycle, port-discovery cascade,       │
                       │    SDK transport wiring                     │
                       └─────────────────────────────────────────────┘
                              │
                              ▼  invoke
                       ┌─────────────────────────────────────────────┐
                       │  tools.cljs — wire-boundary pipeline        │
                       │    precheck → dispatch → apply-cache        │
                       │             → apply-cap                     │
                       └─────────────────────────────────────────────┘
                              │
                              ▼  per-tool body
                       ┌─────────────────────────────────────────────┐
                       │  tools/<tool>.cljs                          │
                       │    snapshot.cljs, dispatch.cljs,            │
                       │    eval_cljs.cljs, subscribe.cljs, …        │
                       │    (one file per tool — see 003-Tool-       │
                       │     Catalogue.md for the live count)        │
                       └─────────────────────────────────────────────┘
                              │
                              ▼  bencode round-trip
                       ┌─────────────────────────────────────────────┐
                       │  nrepl.cljs                                 │
                       │    persistent socket, message correlation   │
                       └─────────────────────────────────────────────┘
                              │
                              ▼  cljs-eval
                       ┌─────────────────────────────────────────────┐
                       │  shadow-cljs JVM (the user's app)           │
                       └─────────────────────────────────────────────┘
                              │
                              ▼  preload runtime (re-frame2-pair.runtime)
                       ┌─────────────────────────────────────────────┐
                       │  app-db · sub-cache · machines · epochs ·   │
                       │  traces                                     │
                       └─────────────────────────────────────────────┘
```

### Concern namespaces

Concern namespaces sit alongside the per-tool bodies. Each owns
one cross-cutting concern; new tools `:require` from these rather than
reinventing the lens. The principal lenses (the wire-pipeline core; the
src tree carries further concern ns — `resource_controls`, `source_uri`,
`freshness`, `reserved_frame_guard`, `await_promise`, `result_envelope`,
… — each owning a narrower slice):

| Lens                          | Owned by                          |
|---|---|
| Wire-bounded markers          | `tools/wire.cljs`, `tools/wire_pipeline.cljs` |
| Boundary step protocol        | `tools/boundary_step.cljs`        |
| Precheck (early-exit gates)   | `tools/precheck.cljs`             |
| Cache (per-session response)  | `cache.cljs`                      |
| Cap (token-budget pipeline)   | `tools/cap.cljs`                  |
| Dedup (structural)            | `tools/dedup.cljs`                |
| Elision (size-bounded leaves) | `tools/elision.cljs`              |
| Sensitive (privacy filter)    | `tools/sensitive.cljs`            |
| Cursor (pagination)           | `tools/cursor.cljs`               |
| Args (coercion + parsing)     | `tools/args.cljs`                 |
| Summary (lazy tree-summary)   | `tools/summary.cljs`              |
| Snapshot pipeline             | `tools/snapshot_pipeline.cljs`    |

The cross-MCP-shared half of these primitives lives in
[`tools/mcp-base/`](../../mcp-base/spec/README.md) (`vocab`,
`sensitive`, `elision`, `args`, `cursor`, `envelope`, `cap`,
`overflow`, `diff-encode`, `section-grouping`); the
re-frame2-pair-mcp-side wrappers above adapt the shared primitives to
this server's wire-shape and per-tool registrations.

## What it isn't

- **Not** a new re-frame2-pair contract. The op vocabulary is identical to the
  bash-shim catalogue minus `inject-runtime` (rf2-7dvg cut the
  inject step in favour of a shadow-cljs `:preloads` entry).
- **Not** a re-frame2 runtime extension. It calls into the
  `re-frame2-pair.runtime` namespace the consumer app preloads via
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
