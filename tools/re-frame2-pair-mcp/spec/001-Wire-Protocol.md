# 001-Wire-Protocol

## Transport

Newline-delimited JSON-RPC 2.0 over stdio per the
[MCP stdio transport spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

- One message per line on stdin/stdout.
- UTF-8.
- No embedded newlines in the JSON.
- stdout is reserved for valid MCP messages; stderr is free-form.

The npm `@modelcontextprotocol/sdk`'s `StdioServerTransport` provides
the framing; we don't roll our own.

## Lifecycle

1. Agent host launches the server as a subprocess.
2. First message: `initialize`. Server responds with
   `protocolVersion`, `capabilities`, `serverInfo`.
3. Client sends `notifications/initialized` (no response per JSON-RPC
   notification semantics).
4. Client sends `tools/list`, `tools/call`, etc. Server dispatches via
   `setRequestHandler` keyed by the SDK's request schemas.
5. Shutdown: client closes stdin → Node EOF → process exits.

## Protocol version

Server reports `2025-06-18` (the version supported by the npm SDK at
v1.29.0 used in this build). Clients on older versions are accepted
when the SDK negotiates a fallback; clients on newer versions receive
our version and may disconnect per the spec's negotiation rule.

## JSON-RPC error codes

| Code   | Name              | When |
|--------|-------------------|------|
| -32700 | parse-error       | Malformed JSON on the wire. |
| -32600 | invalid-request   | Not a valid JSON-RPC request envelope. |
| -32601 | method-not-found  | Unknown method (or unknown tool name). |
| -32602 | invalid-params    | Method recognised, params shape wrong. |
| -32603 | internal-error    | Server-side fault. |

Tool-execution errors (a known tool returning a failure) use the
`tools/call` result shape with `isError: true` per the MCP spec's
error-handling guidance — they are NOT protocol-level errors.

## Degraded boot

If the nREPL port can't be found at startup (no port file, no env
var, shadow-cljs not running yet), the server still boots and answers
`tools/list`. Every `tools/call` returns
`{:isError true :content [{:text "{:ok? false :reason :nrepl-port-not-found ...}"}]}`
so the agent host can surface the structured error and the user can
start shadow-cljs without restarting the server.
