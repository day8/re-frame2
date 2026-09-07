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

Protocol-version negotiation is delegated to the npm MCP SDK; the
server does not independently pin the negotiated version. Clients use
the `protocolVersion` returned by `initialize`. The stdio integration
test exercises a `2025-06-18` initialization request, not a promise that
every compatible client receives that exact version.

## JSON-RPC error codes

| Code   | Name              | When |
|--------|-------------------|------|
| -32700 | parse-error       | Malformed JSON on the wire. |
| -32600 | invalid-request   | Not a valid JSON-RPC request envelope. |
| -32601 | method-not-found  | Unknown JSON-RPC method. |
| -32602 | invalid-params    | Method recognised, params shape wrong. |
| -32603 | internal-error    | Server-side fault. |

Tool-name and tool-execution errors use the
`tools/call` result shape with `isError: true` per the MCP spec's
error-handling guidance — they are NOT protocol-level errors. An
unregistered tool name is rejected locally as `:reason :unknown-tool`
before endpoint discovery, so it remains diagnosable when no app is
running.

## Degraded boot

If the nREPL endpoint cannot be found on the first app-facing call, the
server remains available and continues to answer `tools/list`. Calls that
need the app return an `isError: true` tool result containing the structured
`:nrepl-port-not-found` reason, and discovery is retried on the next call.
Server-local tools (`get-re-frame2-pair-instructions`) still run. Unknown-tool and disabled-write
guards also run before discovery, so their local errors are not masked by
transport state.
