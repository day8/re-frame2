# Principles

The load-bearing principles for Machines-Viz-MCP. When a design
call has two reasonable options, these are the tie-breakers.
Implementers and contributors should be able to read this doc and
reach the same answers the spec already reached.

These are downstream of the framework's
[Principles](../../../spec/Principles.md), of the upstream
Machines-Viz [Principles](../../machines-viz/spec/Principles.md)
(Machines-Viz-MCP is Machines-Viz's agent face — the upstream
principles apply transitively), and of the sibling
[Causa-MCP Principles](../../causa-mcp/spec/Principles.md) +
[Pair2-MCP Principles](../../pair2-mcp/spec/Principles.md) (the
two MCP servers whose architecture template this artefact
inherits). The principles below are what **Machines-Viz-MCP adds**
on top.

## Tool consumes the framework; doesn't extend it

Machines-Viz-MCP is a **downstream consumer** of re-frame2's
existing surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The tool catalogue routes through the existing instrumentation +
the existing `reg-machine` registry via the injected-runtime
namespace under `cljs-eval`. The MCP-server-side code is the
*renderer* of those eval forms; nothing new is registered against
the framework on the browser side, and the Node-side server is
invisible to the consumer app's runtime.

This is the
[downstream-EPs-consume-foundation rule](../../../spec/Principles.md)
applied to tools: tools observe and exercise what the framework
emits and registers; they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g. "I
want to subscribe to cross-frame `:rf.machine/transition` events
but the trace bus doesn't expose the right axis"), file a `bd`
bead against
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
or [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md).
Don't bolt a parallel surface onto the MCP server.

## MCP-server-ns and injected-runtime-ns are distinct

Machines-Viz-MCP's code lives on two sides of an stdio JSON-RPC
pipe; each side has its own namespace root, and the spec calls
them out separately so the implementation can too (same posture
as pair2-mcp's `re-frame-pair2-mcp.*` / `re-frame-pair2.runtime`
split and causa-mcp's
`day8.re-frame2-causa-mcp.*` / `day8.re-frame2-causa.runtime`
split).

The tie-breaker this principle hands implementers and reviewers:

- **MCP-server-side concern?** Code goes under the Node-only
  `day8.re-frame2-machines-viz-mcp.*` root, lives in
  `tools/machines-viz-mcp/src/`, ships only inside the Node-side
  `:node-script` artefact, is never `:require`-d from a browser
  bundle. Examples: stdio framing, bencode/nREPL bridge, port
  discovery, eval-form rendering, the `subscribe` notification
  pump, agent-host capability negotiation.
- **Injected-runtime-side concern?** Code goes under
  `day8.re-frame2-machines-viz.runtime` (parented by
  Machines-Viz-the-component's `day8.re-frame2-machines-viz.*`
  root, because the runtime rides the component's preload
  classpath), lives in the component's preload classpath, is the
  eval target of the MCP server's rendered forms. Examples:
  per-frame machine-registry accessors backing the list / fetch
  tools, snapshot readers at `[:rf/machines <id>]`, the
  share-URL encoder shim invoking the upstream codec.

A surface that *needs* to straddle both sides — e.g. a wire
schema, an MCP marker keyword like `:rf.mcp/overflow`, the verb
catalogue at
[`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md) —
is owned by the cross-cutting `tools/mcp-conformance/` spec
folder, not duplicated into either ns.

## Share / export inherits the upstream tightened schema with no relaxation

The share-URL encoder this server wraps is the canonical
[`tools/machines-viz/`](../../machines-viz/) encoder. The
upstream share-payload schema was deliberately tightened (PR
#1086 / rf2-li3o4) so that the only data crossing the share-URL
boundary is **machine topology + the active-state name** —
runtime `:data` is structurally absent; `:source-coords` are
dropped at encode time.

**Machines-Viz-MCP inherits this tightened schema with no
relaxation.** The MCP wrapper around `encode-share-url` MUST NOT
add a code path that re-introduces `:data` or `:source-coords`
into the share payload, and MUST NOT bypass the upstream encoder
by composing the URL fragment in the MCP server's host process —
the wrapper MUST delegate to the canonical
`(machines-viz/encode-share-url chart-state)` call so the
upstream schema's `{:closed true}` rejection (decode-side
`:invalid-chart-state`) remains the single normative gate.

SVG/PNG export tools sit under the same posture: the rendered
artefact MUST be derived from the same tightened `ChartState`
shape the share-URL encoder accepts. An export tool that embeds
runtime `:data` (e.g. by labelling a state node with the current
data value as a debugging convenience) is **prohibited by
default** — the artefact bytes cross the same trust boundary as
a share URL and the same exclusion applies.

If a future workflow genuinely needs the runtime `:data` value
off-box, the right surface is a *separate* MCP tool that returns
the value subject to the
[`004-Wire-Pipeline.md`](004-Wire-Pipeline.md) §Tree-shaped read
surface contract (`elide-wire-value` walker,
`:include-sensitive?` / `:include-large?` opt-in, indicator
slots) — **not** a relaxation of the share/export schema.

The principle and the full normative wire-contract enforcement
live in [`004-Wire-Pipeline.md`](004-Wire-Pipeline.md); this
section is the tie-breaker for "should this surface add an opt-in
flag that re-introduces session data?" — answer: no.

## Read-only by default

The catalogue's centre of gravity is **observation**: list,
fetch, encode-share-url, export, subscribe. There is no
`edit-machine` tool — machines are authored in code via
`reg-machine`; the MCP queries what's already registered. Per
[`tools/machines-viz/spec/000-Vision.md` §What it isn't](../../machines-viz/spec/000-Vision.md)
the upstream component takes the same posture, and Machines-Viz-MCP
inherits it.

The read-only stance is the tie-breaker when a future tool
proposal blurs the line: a tool that mutates machine
registrations, rewrites transition tables, or seeds snapshot
state belongs to the source code, not the MCP surface. The
escape valve sibling MCP servers ship (`eval-cljs` in causa-mcp)
is a deliberate exception, not a precedent — adding one here
requires a lock entry in
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md).

## Origin tagging is the convention

Every Machines-Viz-MCP-initiated effect that rides the trace bus
— a `subscribe` notification, a meta `discover-app` probe — MUST
carry `:origin :machines-viz-mcp` so its actions are visibly
distinguishable from in-panel actions in trace consumers (Causa,
Story, downstream forwarders). Same convention pair2-mcp uses
(`:origin :pair2-mcp`) and causa-mcp uses (`:origin
:causa-mcp`); the cross-MCP `:origin` filter axis lives in
[`spec/009-Instrumentation.md` §Filter vocabulary](../../../spec/009-Instrumentation.md#filter-vocabulary).

## See also

- [Causa-MCP Principles](../../causa-mcp/spec/Principles.md) — the closest sibling; the principles above mirror its shape.
- [Pair2-MCP Principles](../../pair2-mcp/spec/Principles.md) — the original MCP-server template.
- [Machines-Viz Principles](../../machines-viz/spec/Principles.md) — the upstream component's tie-breakers (bundle isolation, EDN-first wire, observation-only, embedding-host-agnostic, no session data in shares, read-only by default).
