# Story-MCP — Vision

> What `day8/re-frame2-story-mcp` is, why it ships as a separate jar
> from `day8/re-frame2-story`, and how it relates to Story's runtime
> and the Tool-Pair contract.

## What it is

A JVM-side stdio JSON-RPC server that exposes Story's read (and gated
write) surface as MCP tools. AI agents (Claude Code, Cursor, Copilot)
launch the server as a subprocess, perform the `initialize`
handshake, then call tools like `list-stories`, `run-variant`,
`snapshot-identity`, `get-story-instructions` to navigate / drive /
introspect the Story library.

The server bridges the agent's JSON-RPC dialect to Story's CLJS /
CLJC public API: `re-frame.story/registrations`,
`re-frame.story/run-variant`, `re-frame.story/snapshot-identity`,
`re-frame.story/variant->edn`, and friends. Story-MCP itself adds
zero new framework primitives — it is a thin adapter from JSON-RPC to
Story's surface.

## What it isn't

- **Not an IDE plugin.** Agent hosts (Claude Code etc.) bring the MCP
  client side; this artefact is the server.
- **Not part of Story's authoring runtime.** It reads from
  `re-frame.story`'s public query API and dispatches to its public
  runtime fns; nothing here registers new framework primitives.
- **Not reachable from production CLJS bundles.** Per
  [`tools/README.md`](../../README.md) the dependency arrow flows
  tool → implementation; this jar is on a separate classpath root.
- **Not Story-the-runtime.** Story renders, runs the four-phase
  lifecycle, owns the registrar. Story-MCP only *talks about* what
  Story has registered.

## Why a separate jar from Story

Per [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md)
and [`tools/story/spec/DESIGN-RATIONALE.md`](../../story/spec/DESIGN-RATIONALE.md)
§separate-mcp-jar, the MCP agent surface ships as a distinct artefact
because:

- The MCP server depends on transport machinery (stdio adapter,
  JSON-RPC framing, asynchronous-handler runtime) that the vast
  majority of Story consumers never load.
- Splitting at the jar boundary keeps the Story core lean.
- The MCP surface can evolve on its own cadence — Story's own
  `re-frame.story/stage` advances when Story's surface extends; the
  MCP jar's `re-frame.story-mcp.config/stage = :mcp` advances when
  the MCP surface extends. The two artefacts have independent stage
  progression.

The pattern mirrors `tools/machines-viz/` vs.
`tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md)).

## Relationship to Story

```
┌─────────────────────────────┐         ┌───────────────────────────┐
│ Agent (Claude / Cursor /    │ stdio + │ tools/story-mcp/          │
│ Copilot)                    │ JSON-RPC│ day8/re-frame2-story-mcp  │
└─────────────────────────────┘ <────► │ - tool definitions         │
                                       │ - schema validation        │
                                       │ - bridges to ↓             │
                                       └──────┬────────────────────┘
                                              │ in-process or pair-style
                                              ▼
                                       ┌───────────────────────────┐
                                       │ tools/story/ runtime      │
                                       │ - registry queries        │
                                       │ - run-variant             │
                                       │ - snapshot-identity       │
                                       │ - variant->edn            │
                                       └───────────────────────────┘
```

The MCP server connects to a running app's story runtime via the
existing Tool-Pair primitives (see
[`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md)): nREPL-attached
process, the agent reads the registry over the wire, runs variants,
reads results back. The MCP server itself runs in the agent's
process; the story runtime runs in the app.

## Relationship to spec/007-Stories.md

Story-MCP is one consumer of the Story runtime, which in turn
implements [`spec/007-Stories.md`](../../../spec/007-Stories.md).
This jar adds nothing to the framework's normative spec — it surfaces
existing Story APIs over the wire.

When [`spec/007-Stories.md`](../../../spec/007-Stories.md) extends
(e.g. a new canonical assertion id), Story-MCP picks it up
automatically: `list-assertions` enumerates whatever is registered.
When Story-MCP gains a new tool, that's a Story-MCP-internal change —
no spec edit required.

## The agent loop this enables

The "self-healing" loop that Storybook MCP popularised, translated to
re-frame2:

1. Agent reads `get-story-instructions` to learn the authoring
   conventions.
2. Agent reads `list-stories` / `get-story` / `get-variant` to
   discover the existing component surface.
3. Agent generates a new variant body (EDN, per
   [`tools/story/spec/001-Authoring.md`](../../story/spec/001-Authoring.md)
   §reg-variant).
4. Agent calls `register-variant` (when the write surface is open) to
   land the body in the registry.
5. Agent calls `run-variant` to execute the four-phase lifecycle.
6. Agent reads `read-failures` to learn what didn't pass.
7. Agent edits the body (or the underlying component); GOTO 4.

The loop converges when `:assertions` is all-passing — the agent has
written a story that demonstrates the component working. This is the
whole point of agent-substrate posture in 2026.

## Cross-references

- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) — JSON-RPC framing,
  stdio transport, `initialize` handshake.
- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 19 tools
  across Dev (3) / Docs (9) / Testing (4) / Write (3).
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  `allow-writes?` config and gate behaviour.
- [`API.md`](API.md) — consolidated tool surface.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — why Cheshire, why
  stage-marker is independent from Story, why protocol-version pinned.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the contract.
- [`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) — the runtime
  contract for pair-shaped AI tools.
