# 000-Vision: Machines-Viz MCP server

Machines-Viz-MCP is the **agent face of Machines-Viz**. Where
[`tools/machines-viz/`](../../machines-viz/) ships the
`MachineChart` component plus a read-only viewer page for human
debuggers and PR reviewers, this artefact ships an MCP server so
AI agents (Claude Code, Cursor, Copilot, and any other host that
speaks Model Context Protocol) can query the same registry —
list registered machines, fetch a machine's transition table,
encode a share-URL for a given machine + snapshot, render a
chart to SVG or PNG — over stdio JSON-RPC, without opening a
browser.

This spec folder is the per-tool normative contract for
`tools/machines-viz-mcp/`. It is a **load-bearing scaffold**
stood up before implementation begins: the direction-setting
calls (separate jar, MCP-over-stdio, share-URL encoding parity,
read-only posture) are pinned here so consumers can plan against
them. When implementation lands, this folder gets fleshed out
with the four `tools/pair2-mcp/spec/`-shape files
(`001-Wire-Protocol`, `002-nREPL-Transport`,
`003-Tool-Catalogue`) parameterised for Machines-Viz.

## Why a separate jar

Machines-Viz-the-component is human-facing (React-flavoured CLJS
embedded inside Causa, Story, or any host that wires it);
Machines-Viz-MCP is agent-facing. They ship as distinct jars so
the MCP server can be loaded without dragging the React /
charting runtime into the agent's classpath; the two surfaces
split:

- **[`tools/machines-viz/`](../../machines-viz/)** —
  `day8/re-frame2-machines-viz`. The `MachineChart` component +
  read-only viewer page + share-URL encoder + PNG / SVG
  exporters. Browser-side artefact.
- **`tools/machines-viz-mcp/`** —
  `day8/re-frame2-machines-viz-mcp`. The MCP server. Pulled by
  `npm install`. Node-side artefact, attached over nREPL.

Both consume the same re-frame2 instrumentation surface
([Spec 009 trace bus](../../../spec/009-Instrumentation.md), the
registrar query API for `:machine` definitions, machine snapshots
at `[:rf/machines <id>]`) and the same share-URL encoding rules
(per [`tools/machines-viz/spec/API.md`](../../machines-viz/spec/API.md)
§Share-URL encoding). Neither depends on the other.

The separation is the same shape `tools/causa/` /
`tools/causa-mcp/` and `tools/story/` / `tools/story-mcp/` use: a
human-facing rendering artefact and an agent-facing query surface
ship as distinct jars so the agent jar can be loaded without the
UI dependencies. Per the umbrella sketch in
[`tools/README.md`](../../README.md) — "mirroring the causa /
causa-mcp split."

## What it is

A Node-based stdio JSON-RPC server, written in ClojureScript,
compiled via shadow-cljs to a single `.js` artefact. AI agents
launch it as a subprocess; one persistent nREPL socket is held
for the lifetime of the session; a small catalogue of
machines-viz-shaped tools is exposed as MCP tools.

The architecture mirrors [`tools/pair2-mcp/`](../../pair2-mcp/)
and [`tools/causa-mcp/`](../../causa-mcp/) — the same baseline
the agent triplet shares:

- Same `@modelcontextprotocol/sdk` `StdioServerTransport`.
- Same bencode-pinned nREPL bridge (per
  [`tools/pair2-mcp/spec/002-nREPL-Transport.md`](../../pair2-mcp/spec/002-nREPL-Transport.md)).
- Same port-discovery walk (`$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`).
- Same degraded-boot policy: tools return structured
  `{:ok? false :reason ...}` errors rather than refusing to
  start.
- Same MCP verb-naming conformance (per
  [`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md)).

Different tool catalogue. Different `:origin` tag
(`:machines-viz-mcp`).

## What it isn't

- **Not** the component. The `MachineChart` React component lives
  in [`tools/machines-viz/`](../../machines-viz/). The MCP
  surface exposes the **data** the component would render plus
  the **share-URL** an agent can paste into a PR description; it
  does not render to the agent's session. Agents that want a
  chart receive a share-URL (the user opens it) or an SVG / PNG
  (a tool call returns the bytes).
- **Not** an editor. Machines are authored in code via
  `reg-machine`; this MCP queries what's already registered.
  There is no `edit-machine` tool. Same posture
  [`tools/machines-viz/spec/000-Vision.md`](../../machines-viz/spec/000-Vision.md)
  §What it isn't takes for the component.
- **Not** a session recorder. The share-URL encoding serialises
  machine topology + a single current-state snapshot, **not** a
  trace stream / epoch buffer / app-db slice. Per Causa
  Lock #4 (session export forbidden), lifted into Machines-Viz
  and inherited here.
- **Not** a parallel registrar / dispatch / effect surface.
  Machines-Viz-MCP is a downstream consumer of the framework's
  instrumentation. If a query needs data the framework doesn't
  emit, file a `bd` bead against
  [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) —
  do not bolt a parallel surface onto this artefact.
- **Not** part of any production bundle. The bundle-isolation
  contract in [`tools/README.md`](../../README.md) holds:
  nothing under `implementation/` may `:require` from
  `tools/machines-viz-mcp/`.

## Where it lives in the stack

```
              ┌────────────────────────────────┐
              │  AI agent host                 │
              │   (Claude Code, Cursor, …)     │
              └────────────────┬───────────────┘
                               │ stdio JSON-RPC
                               ▼
              ┌────────────────────────────────┐
              │  tools/machines-viz-mcp/       │
              │   • MCP tool dispatcher        │
              │   • nREPL client (Node side)   │
              │   • share-URL encoder shim     │
              └────────────────┬───────────────┘
                               │ nREPL
                               ▼
              ┌────────────────────────────────┐
              │  Browser-side runtime          │
              │   • reads registry             │
              │   • reads [:rf/machines <id>]  │
              │   • encodes share-URL (shared  │
              │     codec from machines-viz)   │
              └────────────────┬───────────────┘
                               │
                               ▼
              ┌────────────────────────────────┐
              │  implementation/machines + core│
              │   • reg-machine + tables       │
              │   • Spec 009 trace bus events  │
              └────────────────────────────────┘
```

The arrow does not invert. Machines-Viz-MCP does not know about
Causa-MCP or Story-MCP — it only knows the registrar query API
and the machines-viz codec.

## What the catalogue covers (sketch)

The full catalogue lands in `003-Tool-Catalogue.md` when the
implementation spec is fleshed out. The sketch — load-bearing for
the separation decision and consumer planning, not the final
shape:

- **List / fetch.** Enumerate registered machines (across
  frames); fetch one machine's transition table; fetch one
  machine's current snapshot at `[:rf/machines <id>]`.
- **Encode share-URL.** Wrap the canonical
  `(machines-viz/encode-share-url chart-state)` (per
  [`tools/machines-viz/spec/API.md`](../../machines-viz/spec/API.md)
  §Share-URL encoding) so an agent can return a pasteable URL
  to its caller.
- **Export.** Return SVG or PNG bytes for a machine + snapshot
  (calls the same exporters Machines-Viz ships; structured
  content + `:size` band per
  [`tools/mcp-base/`](../../mcp-base/) wire conventions).
- **Subscribe (streaming).** Tail Spec 009
  `:rf.machine/*` trace events for a given machine-id; mirrors
  the streaming-band pattern from
  [`tools/causa-mcp/spec/000-Vision.md`](../../causa-mcp/spec/000-Vision.md).
- **Meta.** `discover-app` and `tail-build` parity with the
  other servers (shared via `tools/mcp-base/`).

## v1 vs. v1.1

The v1 commitment matches the
[`tools/machines-viz/spec/000-Vision.md`](../../machines-viz/spec/000-Vision.md)
§v1.0 commitment: list + fetch + share-URL + export are the
must-ship surfaces. Streaming subscribe and richer query shapes
(transition-history bisection, parallel-region projection
queries) are v1.1 candidates.

## See also

- [`tools/machines-viz/spec/000-Vision.md`](../../machines-viz/spec/000-Vision.md) — the component this MCP queries.
- [`tools/causa-mcp/spec/000-Vision.md`](../../causa-mcp/spec/000-Vision.md) — sibling MCP surface; this artefact follows the same architecture template.
- [`tools/pair2-mcp/spec/`](../../pair2-mcp/spec/) — the reference for the four-file `001 / 002 / 003 / API` shape this folder grows into when implementation lands.
- [`tools/mcp-base/`](../../mcp-base/) — shared wire primitives.
- [`tools/mcp-conformance/NAMING.md`](../../mcp-conformance/NAMING.md) — verb-naming conformance the catalogue must respect.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry this MCP queries.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the streaming-subscribe tools tap.
- [`tools/README.md`](../../README.md) — the umbrella that flagged this jar's existence pending the first cut.
