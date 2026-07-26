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

  For the **top-level `tools/call` argument** level specifically, a
  dropped key is no longer SILENTLY discarded (rf2-ovmc5e). Its RAW
  STRING form is recorded as Clojure **metadata** on the normalised
  arguments map under `protocol/unknown-arg-keys-meta` — never as a map
  entry (so it cannot reach a handler) and never keywordised (so the
  no-intern invariant is preserved verbatim). The dispatcher
  (`wire-pipeline/invoke-tool`) reads that metadata BEFORE handler
  dispatch and, when non-empty, returns a tool-level `isError: true`
  diagnostic naming the unknown keys, the tool, and **that tool's**
  allowed argument-key set (the descriptor's input-schema property keys
  — the same names `tools/list` advertises). So a non-schema-validating
  host or hand-rolled agent that typos a control knob (`:timeuot-ms`,
  `:include-sensitve`) gets agent-recoverable feedback rather than a
  successful-looking call that silently defaulted. The server is the
  authoritative backstop for the advertised `additionalProperties false`
  contract; it no longer depends on the client validating. The
  `:rf.error` id on the structured slot is
  `:rf.story-mcp/unknown-arguments`.

  **Two-level enforcement (rf2-an95jj).** The global allowlist
  (`protocol/arg-keys`) is the UNION of every tool's argument keys, so it
  only rejects keys no tool reads. A key valid for ANOTHER tool — `:body`
  (register-variant), `:write-back` (record-as-variant) — survives
  normalisation as a keyword entry and would be silently ignored by the
  selected handler. The dispatcher therefore runs a SECOND, per-tool
  check (`wire-pipeline/tool-invalid-arg-keys`) AFTER global
  normalisation: a globally-known key the SELECTED tool does not advertise
  in its `inputSchema` properties is diagnosed with the same
  `:rf.story-mcp/unknown-arguments` envelope. This is the descriptor-level
  `additionalProperties false` backstop at PER-TOOL granularity. Two
  cross-cutting knobs handled at the wire boundary rather than by a
  handler are tolerated on every tool regardless of advertisement
  (`wire-pipeline/wire-managed-arg-keys`): `:max-tokens` (injected on
  every descriptor anyway) and `:dedup` (advertised only on dedup-eligible
  tools but documented as silently ignored elsewhere — the dispatcher
  gates dedup on `:dedup-eligible?`, not on the arg's presence). No-intern
  holds: a per-tool-invalid key is already an interned keyword (it passed
  the global allowlist), so reporting it mints nothing.

  **Bounded error envelopes (rf2-p0eiq3).** Both the global and per-tool
  unknown-argument diagnostics — and the invalid-`:max-tokens` rejection —
  ride the SAME wire-boundary token cap (`mcp-base.cap/apply-cap`) that
  bounds every normal tool response (see
  [`Principles.md`](Principles.md) §Tight token budget). A caller can
  otherwise pack many long unknown keys inside the 4 MB frame cap and
  receive an UNCAPPED diagnostic echoing them all back, violating the
  response-cap contract. The invalid-`:max-tokens` envelope falls back to
  the convention default cap (the caller's own cap was malformed, so it
  can't be honoured), but is still bounded.
- Genuinely data-bearing nested maps keep string keys at ingress and are
  routed through each surface's own **bounded** keyword policy:
  - `:cell-overrides` KEYS are resolved through `safe-keyword` against
    the variant's **effective** arg-key set under the request's
    `:active-modes` (`read-run-opts`) — a finite, registry-derived
    allowlist; an override key outside it is dropped. The allowlist is
    the effective set (variant args ∪ the active modes' contributed
    keys) rather than the bare variant's declared keys because Story's
    args precedence merges mode args *before* cell-local overrides, so
    an arg introduced only by an active mode is a legitimate override
    target (rf2-to3q7). The active modes are coerced first so the
    allowlist is derived from the same effective args the render uses.
  - the object-form `register-variant` `:body` is keywordised by
    `coerce-body` only **behind the `--allow-writes` operator gate** and
    only **after** the rf2-g9fje depth cap AND the rf2-tag30h **width
    cap** (`max-body-string-keys`, total string-key count across the
    tree), so the intern is bounded by an operator gate, a depth ceiling,
    AND a width ceiling. The depth cap alone bounds nesting but not the
    number of distinct unknown string keys a shallow object can carry —
    a wide body would intern a fresh keyword per key before the registrar
    rejected it. The EDN-string body path is unchanged (the hardened
    `clojure.edn` reader yields keyword data directly).
  - the fresh **id** a write path mints — `register-variant`'s
    `:variant-id` and `record-as-variant`'s write-back `:new-variant-id`
    — is validated against the canonical `:story.<path>/<name>` grammar
    on the STRING shape (`mcp-base.args/fresh-keyword-checked` +
    `re-frame.story.schemas/variant-id-shape?`, single-sourced with the
    keyword-level `variant-id?`) BEFORE interning (rf2-tag30h). The
    earlier `fresh-keyword` interned first and let the registrar's
    downstream `assert-id!` reject on grammar — but that reject ran AFTER
    the intern, so an invalid id (which correctly returned an MCP error)
    still permanently grew the keyword table. The pre-intern shape check
    fails closed with no intern: a rejected id leaves the keyword table
    unchanged.

This closes the input-side vector. The output-side egress scrubbing
(rf2-12f2q, pre-frame hardening rf2-tag30h) is a separate concern
documented with the result envelope.

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
| `-32600` | Invalid request | The frame is JSON but not a valid JSON-RPC request shape, **or** a request other than `initialize` / `ping` arrives before the `initialize` handshake completes (the pre-initialize lifecycle gate — see [§Lifecycle state enforcement](#lifecycle-state-enforcement)). |
| `-32601` | Method not found | Unknown method name. |
| `-32602` | Invalid params | `tools/call` `name` is missing or not a string, **or** `tools/call` `arguments` is present but not an object (a scalar / array / string — a params-CONTAINER shape failure; rf2-2zym5e). Per-ARGUMENT validation does NOT surface here — within a well-formed arguments map, each tool self-validates a wrong-typed / missing field (`required-arg` / `coerce-body`) and returns an `isError: true` tool result, not a protocol error. An unknown top-level argument KEY (rf2-ovmc5e) likewise returns an `isError: true` tool result (`:rf.error :rf.story-mcp/unknown-arguments`) before dispatch, not a protocol error. |
| `-32603` | Internal error | An unexpected exception during dispatch. |

**Tool-execution errors** (a tool ran, but its semantic failed —
e.g. `run-variant` on an unknown variant id) return `isError: true`
in the tool result, not a JSON-RPC protocol error. This lets the
agent see the failure mode without aborting the conversation. See
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) for an
example: a write-surface call when writes are gated off returns
`isError: true` with a documented hint, not `-32601`.

**Unknown top-level argument keys** (rf2-ovmc5e) are diagnosed, not
silently dropped. A caller-supplied `tools/call` argument key outside
the bounded `protocol/arg-keys` allowlist is kept (as a raw string,
never interned) only as metadata for the dispatcher; before the handler
runs, `wire-pipeline/invoke-tool` returns an `isError: true` result
naming the unknown key(s), the tool, and that tool's allowed argument
key set (the descriptor's advertised property names). This makes the
server the authoritative backstop for each descriptor's
`additionalProperties false` contract — a non-schema-validating host
that typos a control knob gets recoverable feedback instead of a
successful-looking call that defaulted. Nested data-bearing key
policies (`:cell-overrides`, object-form write `:body`) keep their own
bounded per-surface drop semantics — only the TOP-LEVEL MCP argument
level diagnoses.

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

## Lifecycle state enforcement

The dispatcher is **stateful**: each session (one run-loop invocation)
tracks whether the `initialize` handshake has completed. Before a
successful `initialize`, the **only** requests accepted are:

- `initialize` — the handshake itself.
- `ping` — a stateless liveness probe (MCP §Utilities); answering it
  before initialize lets a host health-check a freshly-spawned server.

Any **other** request before initialization — `tools/list`,
`tools/call`, `shutdown`, an unknown method — returns
`-32600 invalid-request` with a message naming the violation. The tool
registry never enumerates and no tool handler ever runs before the
handshake, closing a protocol-compliance and state-leak gap (a malformed
or hostile client cannot probe or invoke the surface pre-handshake).

**Notifications** (a request with no `id`, e.g.
`notifications/initialized`) are accepted as silent no-ops in **every**
lifecycle posture — the gate runs only on requests.

### `notifications/initialized` is not required (deliberate relaxation)

The MCP lifecycle has the client send `notifications/initialized` after
the `initialize` response to confirm the handshake. The server does
**not** gate the tool surface on receiving it: the session flips to
*initialized* the moment the `initialize` **response** is built. This
matches the reference SDK posture — the MCP Python SDK was aligned to the
TypeScript SDK to mark the server initialized on the `initialize`
response rather than waiting for the notification, so a client that
pipelines `initialize` + `tools/list` does not race a refusal. The
notification is still accepted as a no-op, so a well-behaved client sees
no error. (rf2-e6knrq.)

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

The protocol and server tests cover each of these paths.

**A tool HANDLER's exception never reaches that `-32603` arm.** `invoke-tool`
catches it and answers an `isError: true` result, which is what MCP §Error
Handling asks for: the agent shows the failure to the LLM instead of aborting
the conversation. That containment has to hold for an arbitrary throw, so the
relayed `ex-data` is projected by `tools.result/wire-safe-ex-data` — total by
SHAPE, not by a roster of known-bad slots. Any value outside the EDN value
space, at any depth and in key position, becomes
`{:rf.story-mcp/unencodable "<class name>"}`: bounded loss, and never an
object address. Without the projection an un-encodable slot throws inside
`protocol/write-frame!`, *past* the handler's own error result, and the client
gets a protocol fault on what was a tool-domain failure (rf2-2z9u3,
rf2-ia904). The `-32603` arm remains the outer net for a fault in dispatch
itself.

## Cross-references

- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 20 tools.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  how the gate fails (clean error, not no-op).
- [`API.md`](API.md) — per-tool input/output shapes.
- [MCP 2025-06-18 spec](https://modelcontextprotocol.io/specification/2025-06-18/)
  — the upstream protocol.
