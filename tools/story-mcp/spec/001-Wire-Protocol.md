# Story-MCP — Wire Protocol

> JSON-RPC 2.0 over stdio; the `initialize` handshake; `tools/list` +
> `tools/call`; the protocol-version pin. The on-the-wire contract.

## Transport

- **Stdio**, newline-delimited JSON-RPC 2.0 per the
  [MCP 2025-06-18 §Transports specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
- Each frame is one JSON object on one line; the server reads from
  stdin, writes to stdout. stderr is reserved for diagnostics
  (failure traces; never used for protocol traffic).
- The server's main loop terminates on stdin EOF; the `shutdown`
  method is also honoured.
- **Frame-length cap** (rf2-g9fje): each inbound frame is bounded at
  `re-frame.story-mcp.protocol/max-frame-bytes` (4 MB — well above
  the largest legitimate MCP payload). A frame exceeding the cap is
  drained to its next newline boundary and yields a parse-error
  response; the loop continues. The cap exists so a hostile or
  runaway producer can't OOM the server with an unterminated frame.

## No-intern argument ingress (rf2-3luf3)

JVM keywords are interned in a global table that **never shrinks**. The
stdio server is a long-running JVM process, so any code path that mints a
fresh keyword from an attacker-/AI-supplied string is a slow-burn DoS:
a hostile or careless agent that streams unique JSON object keys
permanently burns one keyword-table slot per unique key. This is the
same threat model the framework's `:rf.http/max-decoded-keys` cap
defends (see [`../../../spec/014-HTTPRequests.md` §Keyword-interning
cap](../../../spec/014-HTTPRequests.md)) and the cross-MCP rule in
[`../../mcp-base/spec/args.md` §Keyword-interning safety](../../mcp-base/spec/args.md).

The ingress invariant: **untrusted nested wire keys are NEVER keywordised
before the bounded allowlists run.** Concretely:

- `re-frame.story-mcp.protocol/parse-json` parses each frame with
  **string keys** (`json/parse-string s false`) — no recursive
  keywordisation. A nested key under `params.arguments`,
  `cell-overrides`, or a write `body` therefore never interns at parse
  time.
- `protocol/normalize-frame` (run by `read-frame` immediately after the
  parse) keywordises ONLY:
  - the finite JSON-RPC envelope keys (`protocol/envelope-keys`),
  - the finite `params` keys (`protocol/params-keys`), and
  - the bounded top-level **argument-key allowlist**
    (`protocol/arg-keys`) — the single source of truth for every
    top-level key a `tool-*` handler reads.

  Each is resolved through `find-keyword` (via
  `mcp-base.args/safe-keyword`), so a wire string outside the set never
  mints a fresh keyword. **Keys outside these sets are dropped** at their
  level — they neither intern nor reach a handler.
- Genuinely data-bearing nested maps keep string keys at ingress and are
  routed through each surface's own **bounded** keyword policy:
  - `:cell-overrides` KEYS are resolved through `safe-keyword` against
    the variant's declared arg-key set (`read-run-opts`) — a finite,
    registry-derived allowlist; an override key outside it is dropped.
  - the object-form `register-variant` `:body` is keywordised by
    `coerce-body` only **behind the `--allow-writes` operator gate** and
    only **after** the rf2-g9fje depth cap, so the intern is bounded by
    both an operator gate and a depth ceiling (the `fresh-keyword`
    policy). The EDN-string body path is unchanged (the hardened
    `clojure.edn` reader yields keyword data directly).

This closes the input-side vector. The output-side egress scrubbing
(rf2-12f2q) is a separate concern documented with the result envelope.

## Protocol version pin

The MCP protocol revision string is pinned at `2025-06-18` (see
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §protocol-version-pin).
The pin lives in `re-frame.story-mcp.config/protocol-version`;
the `initialize` response advertises this version. Bumping the pin
is a deliberate Story-MCP change — Story's own stage marker is
independent.

## Capabilities advertised

The server advertises a minimal capability set:

- `tools` with `listChanged: false`.

That's it. The server does not advertise prompts, resources,
sampling, or roots. Those surfaces may land in later revisions; today
the contract is "tools only."

## Methods handled

| JSON-RPC method | Behaviour |
|---|---|
| `initialize` | Performs handshake; returns advertised capabilities + protocol version + server info. |
| `tools/list` | Returns the full 20-tool registry (Dev / Docs / Testing / Write). |
| `tools/call` | Dispatches the named tool with `arguments`; returns content + structuredContent + optional `isError`. |
| `ping` | Returns `{}`. Health check. |
| `shutdown` | Cleanly stops the run-loop. |
| `notifications/initialized` | Silent accept (no response, per MCP spec). |

Unknown methods return JSON-RPC error code `-32601`
(`method-not-found`).

## Error codes

The server uses the standard JSON-RPC 2.0 error codes:

| Code | Meaning | When returned |
|---|---|---|
| `-32700` | Parse error | Malformed JSON. The reply carries `id: null`; the run-loop survives and continues reading. |
| `-32600` | Invalid request | The frame is JSON but not a valid JSON-RPC request shape. |
| `-32601` | Method not found | Unknown method name. |
| `-32602` | Invalid params | `tools/call` `name` is missing or not a string. Argument-shape failures do NOT surface here — each tool self-validates (`required-arg` / `coerce-body`) and returns an `isError: true` tool result, not a protocol error. |
| `-32603` | Internal error | An unexpected exception during dispatch. |

**Tool-execution errors** (a tool ran, but its semantic failed —
e.g. `run-variant` on an unknown variant id) return `isError: true`
in the tool result, not a JSON-RPC protocol error. This lets the
agent see the failure mode without aborting the conversation. See
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) for an
example: a write-surface call when writes are gated off returns
`isError: true` with a documented hint, not `-32601`.

## `initialize` handshake

The agent sends:

```json
{"jsonrpc": "2.0",
 "id": 1,
 "method": "initialize",
 "params": {"protocolVersion": "2025-06-18",
            "capabilities": {...},
            "clientInfo": {...}}}
```

The server replies:

```json
{"jsonrpc": "2.0",
 "id": 1,
 "result": {"protocolVersion": "2025-06-18",
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": "re-frame2-story-mcp",
                           "version": "<from VERSION file>"}}}
```

The agent then sends a `notifications/initialized` notification
(silent accept); the handshake is complete.

## `tools/list`

Returns the tool registry verbatim — name, description, JSON-Schema
input schema, optional output schema. Order is stable but not
contractually relevant; agents iterate by name.

The 20 tools are enumerated in
[`002-Tool-Registry.md`](002-Tool-Registry.md).

## `tools/call`

Standard MCP shape:

```json
{"jsonrpc": "2.0",
 "id": 42,
 "method": "tools/call",
 "params": {"name": "run-variant",
            "arguments": {"variant-id": ":story.auth.login-form/happy-path",
                          "substrate": ":reagent"}}}
```

Each tool's result envelope:

```json
{"jsonrpc": "2.0",
 "id": 42,
 "result": {"content": [{"type": "text", "text": "..."}],
            "structuredContent": {...},
            "isError": false}}
```

- `content` is the agent-facing render (typically text).
- `structuredContent` is the JSON projection for programmatic
  consumption.
- `isError: true` signals tool-execution failure (vs. JSON-RPC
  protocol failure); the agent can read the content + error
  metadata and decide whether to retry / abort.

## Run-loop survivability

The run-loop is designed to survive every recoverable error:

- Malformed JSON → emit `-32700`, continue reading.
- Unknown method → emit `-32601`, continue reading.
- Tool dispatch exception → emit `-32603` with the exception message,
  continue reading.
- stdin EOF → graceful shutdown.

Stage 7's tests cover each of these paths.

## Cross-references

- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 20 tools.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  how the gate fails (clean error, not no-op).
- [`API.md`](API.md) — per-tool input/output shapes.
- [MCP 2025-06-18 spec](https://modelcontextprotocol.io/specification/2025-06-18/)
  — the upstream protocol.
