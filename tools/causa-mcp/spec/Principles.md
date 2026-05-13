# Principles

The load-bearing principles for Causa-MCP. When a design call
has two reasonable options, these are the tie-breakers.
Implementers and contributors should be able to read this doc
and reach the same answers Causa-MCP already reached.

These are downstream of the framework's
[Principles](../../../spec/Principles.md) and of Causa's
[Principles](../../causa/spec/Principles.md) (Causa-MCP is
Causa's agent face — the panel's principles apply transitively
to anything Causa-MCP does). The principles below are what
*Causa-MCP adds* on top.

## Tool consumes the framework; doesn't extend it

Causa-MCP is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The eighteen tools route through the existing
`re-frame2-causa-mcp.runtime` namespace via `cljs-eval`. Nothing
new is registered against the framework; nothing new is
introduced into a consumer app's runtime.

This is the
[downstream-EPs-consume-foundation rule](../../../spec/Principles.md)
applied to tools: tools observe and exercise what the framework
emits and registers; they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g.
"I want to inspect cross-frame causality but the trace bus
doesn't expose `:parent-frame`"), file a `bd` bead against
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
or the relevant Causa spec. Don't bolt a parallel surface onto
the MCP server.

## Origin tagging is the convention, not a suggestion

Every Causa-MCP-driven side-effect on the trace bus carries
`:tags :origin :causa-mcp`. The tag is set at the entry point of
each mutating tool (`dispatch`, `reset-frame-db`,
`restore-epoch`) and at the boundary of `eval-cljs` (any
dispatch the eval'd form triggers inherits the tag — the
runtime install hook reads
`re-frame2-causa-mcp.runtime/current-origin` for the dynamic
extent of the eval call).

This is the **load-bearing differentiator** against generalist
agent surfaces (Chrome DevTools MCP's `evaluate_script` is
untagged — an agent hand-rolling `js/window.dispatch(...)` is
indistinguishable from a real user click). It costs nothing at
the call site and the audit-trail payoff is substantial:

| Question | Trace filter |
|---|---|
| What did the agent do this session? | `get-trace-buffer {:filter {:origin :causa-mcp}}` |
| Did the agent or the user fire `:cart/checkout`? | `get-trace-buffer {:filter {:event-id :cart/checkout}}` then read `:tags :origin` on each match |
| Stream just my own mutations as they happen | `subscribe {:topic :trace :filter {:origin :causa-mcp}}` |

The full origin vocabulary (`:app` / `:pair` / `:causa-mcp` /
`:story` / `:test`) is the
[Spec 009 §Origin axis](../../../spec/009-Instrumentation.md);
Causa-MCP's job is to honour it.

Captured as Lock #4 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## EDN canonical; JSON the wire

Causa-MCP's args and returns travel as MCP-shaped JSON but
**the canonical form is EDN-encoded strings inside the JSON
text payload**. The catalogue ships with EDN-encoded `filter`
maps, EDN-encoded `path` arguments, EDN-encoded return values.

Why: re-frame2's runtime speaks EDN. A `:filter` map of
`{:operation :event :origin :causa-mcp :since-ms 5000}` cannot
round-trip through JSON without losing keyword shapes. Encoding
the filter as an EDN string inside the JSON payload preserves
the runtime's vocabulary; the server parses with
`cljs.reader/read-string` at the runtime boundary.

The convention is the same shape pair2-mcp uses; see
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md)
for the wire-format details.

## MCP, not an IDE plugin

The agent-host integration contract is **Model Context Protocol
over stdio**, not a per-editor extension.

By implementing MCP, this artefact works with every MCP-capable
host (Claude Code, Cursor, Copilot, and whatever lands next)
without per-host plumbing. The cost of one stdio JSON-RPC server
is paid once; the alternative — N editor extensions, each
re-implementing the same tool surface — pays the cost N times
and ages worse.

A custom WebSocket protocol was considered and rejected for the
same reason in pair2-mcp; Causa-MCP inherits the lock.

Captured as Lock #2 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need
and held for the lifetime of the session. Subsequent ops reuse
the socket without reconnecting.

Same break-even calculus as pair2-mcp: per-op latency drops from
~700ms (bash startup + babashka startup + fresh nREPL connect
per call) to ~5–50ms (one bencode round-trip on the open socket).
A typical Causa-MCP session fires dozens to hundreds of ops.

Captured as Lock #3 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Stage-marker-independent

Causa-MCP must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable
  on every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`. Any of them satisfy the contract.
- Runtime presence is marker-detected (the runtime exposes
  `re-frame2-causa-mcp.runtime/session-id`); a missing sentinel
  triggers automatic re-injection on the next op rather than a
  failed boot.
- The runtime contract is the shape of the eighteen tools, not
  a specific framework version.

A project that adopts a non-default build id, a custom nREPL
port, or a slightly different shadow layout still gets a working
Causa-MCP without code changes.

## Degraded boot, not failed boot

If the nREPL port can't be resolved at startup (no port file, no
env var, shadow-cljs not running yet), the server still boots
and answers `tools/list`. Every `tools/call` returns a
structured `:ok? false :reason :nrepl-port-not-found` error so
the agent host can surface the problem and the user can start
shadow-cljs without restarting the server.

If the runtime hasn't been preloaded, the first mutating /
inspecting tool call returns
`:ok? false :reason :runtime-not-preloaded` with a setup hint
pointing at the consumer's `shadow-cljs.edn` `:preloads` block.

The agent-host workflow is *don't make the user restart
anything*. The server's job is to keep working through the gaps
and surface a useful error when the runtime isn't ready.

Same posture as pair2-mcp.

## Read-mostly catalogue; mutate via the in-panel-equivalent gates

The eighteen tools split 9 read / 3 mutate / 2 stream / 1 escape
hatch / 2 meta. The mutating tools (`restore-epoch`,
`reset-frame-db`, `dispatch`) mirror the in-panel right-click
affordances the human user already has — Causa-MCP doesn't
introduce a *new* mutation surface, it gives the agent the same
surface the human has.

This is the symmetry the privacy + consent model rests on: the
user enabling the MCP server is the user *delegating their
in-panel rights* to the agent. The audit trail (origin tagging,
above) makes the delegation visible.

The escape hatch (`eval-cljs`) is the deliberate exception — it
lets agents reach for surfaces the catalogue doesn't yet cover.
Any side-effect the eval'd form triggers inherits the
`:origin :causa-mcp` tag (the runtime install hook handles the
dynamic-extent rebind). If a particular `eval-cljs` call becomes
recurrent, that's the signal to promote it to its own catalogue
entry.

## Closed-set tool catalogue, deliberate escape valve

The catalogue is **closed-set on purpose**: eighteen named
surfaces map to eighteen Causa panels. Adding tools is a
deliberate act (a `bd` bead, a discussion, a Lock entry in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)). Drift is
prevented by the discipline that every new tool maps to an
existing Causa surface — the framework's instrumentation
contract is the rate-limit.

The `eval-cljs` escape valve absorbs the long tail (per the
section above). Refusing to ship an escape hatch would force
agents through Chrome MCP's `evaluate_script` (which loses the
`:origin` tag) — a worse outcome for the audit trail.

Captured as Lock #5 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Bash-shim back-compat is *not* a goal

Unlike pair2-mcp (which mirrors a pre-existing bash-shim
catalogue under `skills/re-frame-pair2/scripts/`), Causa-MCP
starts greenfield. There is no shim chain to deprecate, no
"side-by-side cadence" lock, no transition period. The MCP
surface *is* the agent-driven surface for Causa.

This is a clean break by accident, not by design: pair2
predated MCP and had to absorb the migration; Causa launches
with MCP-the-pattern already established by pair2-mcp. The
historical context is in
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
Lock #6.

## Backed by Causa's and the framework's principles

When in doubt, defer to the principles upstream:

- **Causa's [Principles](../../causa/spec/Principles.md)** —
  "Read-only by default, mutate by confirmation" applies to the
  MCP surface too; the mutation tools mirror the in-panel
  right-click affordances, and the user's consent model is the
  same (enabling the MCP server is enabling the mutations).
- **Framework's [Principles](../../../spec/Principles.md)** —
  regularity over cleverness, named things over anonymous
  things, public query surfaces, deterministic execution, no
  core.async (the async return-shape is JavaScript Promises;
  the Node host's native async substrate).

Causa-MCP is a downstream artefact of Causa's design and the
framework's AI-first discipline. The principles above are what
*Causa-MCP adds* on top of both baselines; everything below is
inherited.
