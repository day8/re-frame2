# Story-MCP — Write Surface Gating

> The `:rf.story-mcp/allow-writes?` config gate; what's gated; how the
> gate fails (errors clean, rather than no-op).

## The gate

A single atom: `re-frame.story-mcp.config/allow-writes?` (default
`false`). Three input paths flip the gate at boot:

| Path | Mechanism |
|---|---|
| CLI flag | `--allow-writes` |
| JVM sysprop | `-Drf.story-mcp.allow-writes=true` |
| Env var | `RF_STORY_MCP_ALLOW_WRITES=true` |

Any of the three opens the gate. Absent all three, the gate is
closed.

## What's gated

Two tools sit behind the gate (see
[`002-Tool-Registry.md`](002-Tool-Registry.md) §Write):

- `register-variant`
- `unregister-variant`

Read tools are never gated. The 14 Dev / Docs / Testing tools work
regardless of `allow-writes?`.

## How the gate fails

**Clean error, not no-op.** When the gate is closed and an agent
calls a write tool, the response is:

```json
{"content": [{"type": "text",
              "text": "Write surface is gated off; restart with --allow-writes"}],
 "structuredContent": {"gated": true, "reason": "..."},
 "isError": true}
```

Note `isError: true` — this is a **tool-execution error**, not a
JSON-RPC protocol error. The agent's conversation survives; the
agent sees the failure mode and can:

- Surface the error to the user ("the host has writes disabled").
- Continue with read-only operations.
- Abort the loop cleanly.

This is intentional: per
[`001-Wire-Protocol.md`](001-Wire-Protocol.md) §Error codes, returning
`-32601` `method-not-found` for a gated tool would make the agent
think the server is broken rather than configured. The
`isError: true` shape with a documented hint is the right shape.

## Why opt-in, not default

An agent that can register variants on demand activates the
self-healing loop (write story → run → read failures → fix). That
power must be opt-in, not default, for three reasons:

1. **CI safety.** A CI run that accidentally opens the write surface
   could mutate the agent host's registry and confuse subsequent
   test runs. CI runs should always leave the gate closed.
2. **Host trust.** Some dev hosts want a read-only agent surface for
   pair-coding (the agent reads the library; the human writes the
   code). Default-on would assume the wrong shape.
3. **Audit clarity.** When the gate is opt-in, opening it is a
   deliberate choice the host operator made. The presence of
   `--allow-writes` in the launch command is auditable.

## The agent loop with writes open

When writes are open, the four-step self-healing loop becomes:

1. Agent reads `get-story-instructions` + the existing library
   (Dev + Docs tools).
2. Agent generates a variant body in EDN.
3. Agent calls `register-variant {:variant-id ... :body ...}`.
4. Agent calls `run-variant` → `read-failures` → adjusts → GOTO 3.

When writes are closed, the loop is read-only — the agent can only
*describe* what it would change, not enact the change.

## Tests

The gate's tests cover:

- Open path: write tool succeeds when gate is open (via each of the
  three input paths).
- Closed path: write tool returns `isError: true` with the documented
  hint.
- Boot-time precedence: CLI flag > sysprop > env var.

## Cross-references

- [`002-Tool-Registry.md`](002-Tool-Registry.md) §Write — the two
  gated tools.
- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) §Error codes — why
  `isError: true` not `-32601`.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — why opt-in.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's write helpers (the `*`-suffix family) that Story-MCP routes
  through.
