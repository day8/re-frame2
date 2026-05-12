# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers
pair2-mcp already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md);
they are *pair2-mcp-specific*. Where they overlap the framework's
principles, this doc cites instead of repeating.

## Tool consumes the framework; doesn't extend it

pair2-mcp is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The seven ops route through the existing `re-frame-pair2.runtime`
namespace via `cljs-eval`. Nothing new is registered against the
framework; nothing new is introduced into a consumer app's runtime.

This is the downstream-EPs-consume-foundation rule applied to tools:
tools observe and exercise what the framework emits and registers;
they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g., "I
want to inspect the epoch ring but the runtime doesn't expose a
read accessor"), file a `bd` bead against `re-frame-pair2.runtime`
or the relevant framework spec. Don't bolt a parallel surface onto
the MCP server.

## MCP, not an IDE plugin

The agent-host integration contract is **Model Context Protocol over
stdio**, not a per-editor extension.

By implementing MCP, this artefact works with every MCP-capable host
(Claude Code, Cursor, Copilot, and whatever lands next) without
per-host plumbing. The cost of one stdio JSON-RPC server is paid
once; the alternative — N editor extensions, each re-implementing
the same tool surface — pays the cost N times and ages worse.

A custom WebSocket protocol was considered and rejected for the same
reason: it would require every agent host to learn pair2's wire
shape. MCP already exists; pair2-mcp speaks it.

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need and
held for the lifetime of the session. Subsequent ops reuse the
socket without reconnecting.

The break-even versus a reconnect-per-op design is one op — and a
typical pair2 session fires dozens to hundreds. Per-op latency drops
from ~700ms (bash startup + babashka startup + fresh nREPL connect
per call) to ~5–50ms (one bencode round-trip on the open socket).

The persistent-socket choice is what makes the MCP server feel
*interactive* rather than *batch*. It is the load-bearing latency
decision.

Ops carry a UUID `id`; the connection multiplexes incoming bencode
frames against a `{id → resolve-fn}` pending map. Concurrent ops
are correct in principle even though the MCP server currently
invokes tools sequentially.

## Stage-marker-independent

pair2-mcp must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable on
  every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard shadow
  paths → `.nrepl-port`. Any of them satisfy the contract.
- Runtime presence is sentinel-detected (`re-frame-pair2.runtime/session-id`),
  not version-pinned. A reload re-injects from
  `skills/re-frame-pair2/scripts/runtime.cljs` (or
  `$PAIR2_RUNTIME_PATH`).
- The runtime contract is the shape of the seven ops, not a
  specific framework version.

A project that adopts a non-default build id, a custom nREPL port,
or a slightly different shadow layout still gets a working
pair2-mcp without code changes.

## Degraded boot, not failed boot

If the nREPL port can't be resolved at startup (no port file, no
env var, shadow-cljs not running yet), the server still boots and
answers `tools/list`. Every `tools/call` returns a structured
`:ok? false :reason :nrepl-port-not-found` error so the agent host
can surface the problem and the user can start shadow-cljs without
restarting the server.

The agent-host workflow is *don't make the user restart anything*.
The server's job is to keep working through the gaps and surface a
useful error when the runtime isn't ready.

## Bash-shim back-compat preserved

The bash shims under `skills/re-frame-pair2/scripts/` continue to
work and are not slated for removal. Their headers carry a
deprecation notice pointing here; migration is opt-in per session.

The migration discipline is *additive, not destructive*. Agents can
mix shim calls and MCP tool calls in the same workflow during the
transition. Existing skill docs and runbooks that reference the
shims keep working; nothing breaks because the MCP server shipped.

The op vocabulary (the seven canonical pair2 ops) is identical
between the two surfaces — only the transport changes. This is what
makes the back-compat tractable: the contract is the same; the
plumbing underneath is different.

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — one obvious way to do a thing.
  The seven op names and shapes are stable.
- **Named things over anonymous things** — every op has a stable
  name; every reason keyword in an `:ok? false` response is stable.
- **Public query surfaces** — pair2-mcp reads only what the
  framework's public registrar, trace bus, and epoch-history
  surfaces expose, through the `re-frame-pair2.runtime` namespace.
- **Deterministic execution** — a `dispatch` op with the same args
  produces the same effect; no hidden state in the server beyond
  the connection and the pending-map.
- **No core.async** — per [`feedback_no_core_async`](../../../AGENTS.md),
  pair2-mcp does not pull core.async as a dependency or use it as a
  building block. The async return-shape is JavaScript Promises
  (the Node host's native async substrate).

pair2-mcp is a downstream artefact of the framework's AI-first
discipline. The principles above are what *pair2-mcp adds* over the
framework's baseline; everything below is inherited.
