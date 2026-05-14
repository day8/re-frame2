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

## Privacy + Wire Contract

Machines-Viz-MCP is an **off-box egress surface**: machine
enumeration, transition-table reads, direct snapshot reads at
`[:rf/machines <id>]`, share-URL encoding, SVG/PNG export, and
future `:rf.machine/*` trace streaming all cross the stdio JSON-RPC
trust boundary into the agent host (and from there potentially to
an LLM provider). The framework already pins a cross-MCP privacy
posture — `tools/pair2-mcp/`, `tools/causa-mcp/`, and
`tools/story-mcp/` honour it today. **Machines-Viz-MCP inherits the
same contract**; the rules below are normative on the
implementation pass, and were pinned here pre-implementation
(rf2-epiao) to prevent a future cut from shipping raw machine
snapshots or raw `:rf.machine/*` events across the boundary by
accident.

The trust boundary is the MCP stdio channel. Every rule below
gates **what crosses that channel by default**, and every opt-in
escape hatch is **off by default** — a tool that omits the opts
gets the safe posture. Cross-server slot names match
[`tools/causa-mcp/spec/004-Wire-Pipeline.md`](../../causa-mcp/spec/004-Wire-Pipeline.md)
and
[`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
so an agent that learned the vocabulary on a sibling server
recognises it here verbatim.

### Trace-stream surface — default-drop `:sensitive?` events (MUST)

Per [spec/009 §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(rf2-a32kd):

> Framework-published listener integrations (Sentry/Honeybadger
> forwarders, pair2 server, Causa-MCP server) MUST default-suppress
> `:sensitive? true`.

Machines-Viz-MCP is such a listener integration. The streaming
`subscribe` tool for `:rf.machine/*` events (and any future tool
whose return surfaces `:rf/trace-event`-shaped items)
**MUST** apply the default-suppress filter at the MCP boundary
before any data crosses into the agent surface. Pull the shared
helper from
[`tools/mcp-base/src/re_frame/mcp_base/sensitive.cljc`](../../mcp-base/src/re_frame/mcp_base/sensitive.cljc)
(`strip-sensitive`, `scrub-snapshot`) — per the cross-MCP symmetry
posture, do not reimplement the predicate.

The contract:

- **Default**: events with `:sensitive? true` at the top level are
  dropped. Dropped count surfaces as `:dropped-sensitive` on the
  result (or on each `notifications/progress` payload for streaming
  tools) when non-zero. Slot name is cross-MCP reserved per
  [`spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters).
- **Opt-in**: per-call `:include-sensitive? true` MCP arg disables
  the gate. The arg name is cross-server identical (pair2-mcp,
  causa-mcp, story-mcp use the same slot) per the cross-server
  symmetry posture.
- **Fail-closed**: any truthy non-boolean `:sensitive?` stamp drops
  (and logs to stderr). Spec/009 declares the stamp as a boolean;
  contract drift surfaces rather than silently leaks. Per
  rf2-ih7g4.

### Tree-shaped read surface — `elide-wire-value` on every direct read (MUST)

The list/fetch tools (machine enumeration return shapes,
`fetch-transition-table`, `fetch-machine-snapshot` at
`[:rf/machines <id>]`) and the export tools (SVG/PNG byte returns
that embed snapshot data) are **direct reads of live runtime
state** — they bypass the trace bus entirely. The `:sensitive?`
trace stamp protects only the *trace* surface; a direct read
returns the live value untransformed unless the wire-egress
boundary scrubs it.

Per [`spec/Tool-Pair.md` §Direct-read privacy posture for `sub-cache` and `get-path`](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path)
(rf2-vflrg), every Machines-Viz-MCP tool whose return value is a
tree-typed payload **MUST** route the returned value through
`re-frame.core/elide-wire-value` before the value crosses the MCP
stdio egress. Off-box defaults apply: both
`:rf.size/include-sensitive?` and `:rf.size/include-large?`
**MUST** default `false`. The walker is the **single normative
emission site** for `:rf/redacted` and `:rf.size/large-elided`
markers; per-tool reimplementation is prohibited.

The walker reads the live `[:rf/elision]` registry from the named
frame's `app-db` — it MUST therefore run **app-side** (inside the
nREPL-evaluated form), not in the Node process. The wiring shape
matches
[`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs)
and
[`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/elision.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/elision.cljs):
the eval form wraps the snapshot value with
`(re-frame.core/elide-wire-value v opts)` server-side and returns
`{:value <walked> :elided-count N}` so the MCP server can read the
count from a piggyback slot rather than re-walking.

Composition rule — **sensitive wins** when both predicates match:
a value that is both `:sensitive? true` and over the size
threshold is dropped/redacted without emitting a size marker (the
marker's `:path` / `:bytes` / `:digest` slots would leak signal an
audit must not see). Per
[`spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md#size-elision-in-traces).

### Caller opt-in flags + indicator slots (MUST)

The four cross-MCP wire slots are **reserved and load-bearing**.
Every Machines-Viz-MCP tool whose return walks a tree-typed payload
or surfaces trace-shaped items MUST honour them:

| Slot | Direction | Default | Effect |
|---|---|---|---|
| `:include-sensitive?` | call arg | `false` | When `true`, the walker passes `:sensitive?` slots verbatim AND the trace-stream filter passes `:sensitive? true` events. Per-call escape hatch only — never set at server level. |
| `:include-large?` | call arg | `false` | When `true`, the walker bypasses size elision entirely. For workflows that explicitly need the full payload (e.g. inspecting a declared-large slot, large-context-model sessions). |
| `:dropped-sensitive` | response | omitted when 0 | Integer count of trace-shaped items the filter dropped. Present on every tool whose return surfaces trace-stream data. |
| `:elided-large` | response | omitted when 0 | Integer count of leaves the walker replaced with the `:rf.size/large-elided` marker. Present on every tool whose return walks a tree-typed payload. |

Streaming `subscribe` notifications carry **both** indicator slots
on each `notifications/progress` payload and on the final summary
— per [`spec/Conventions.md` §Cross-MCP indicator-field vocabulary §Streaming payloads](../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters).

### Share-URL + export — inherit the upstream tightened schema

The share-URL encoder this server wraps is the canonical
[`tools/machines-viz/`](../../machines-viz/) encoder. The upstream
share-payload schema was deliberately tightened (PR #1086 /
rf2-li3o4) so that the only data crossing the share-URL boundary
is **machine topology + the active-state name**:

- **Runtime `:data` is structurally absent** from the share
  payload. The `:snapshot` map is `{:closed true}` and carries
  `:state` only; the encoder neither reads nor serialises `:data`.
  Per
  [`tools/machines-viz/spec/API.md` §Share-URL payload schema](../../machines-viz/spec/API.md#share-url-payload-schema)
  and
  [`tools/machines-viz/spec/Principles.md` §No session data in shares](../../machines-viz/spec/Principles.md).
- **`:source-coords` are dropped at encode time.** Absolute file
  paths would reveal usernames, workstation layout, and internal
  repo structure; the viewer page has no editor handler wired and
  cannot use them.

**Machines-Viz-MCP inherits this tightened schema with no
relaxation.** The MCP wrapper around `encode-share-url`
**MUST NOT**:

- Add a code path that re-introduces `:data` into the share
  payload (no `:include-snapshot-data?` opt-in flag, no
  `:full-snapshot? true` mode, no out-of-band `:data` slot on the
  result envelope alongside the URL).
- Add a code path that re-introduces `:source-coords` into the
  share payload or into the URL fragment.
- Bypass the upstream encoder by composing the URL fragment in the
  MCP server's host process — the wrapper **MUST** delegate to the
  canonical `(machines-viz/encode-share-url chart-state)` call so
  the upstream schema's `{:closed true}` rejection (decode-side
  `:invalid-chart-state`) remains the single normative gate.

SVG/PNG export tools sit under the same posture: the rendered
artefact MUST be derived from the same tightened `ChartState`
shape the share-URL encoder accepts. An export tool that embeds
runtime `:data` (e.g. by labelling a state node with the current
data value as a debugging convenience) is **prohibited by default**
— the artefact bytes cross the same trust boundary as a share URL
and the same exclusion applies.

If a future workflow genuinely needs the runtime `:data` value
off-box, the right surface is a *separate* MCP tool that returns
the value subject to the §Tree-shaped read surface contract above
(`elide-wire-value` walker, `:include-sensitive?` /
`:include-large?` opt-in, indicator slots) — **not** a relaxation
of the share/export schema.

### Conformance test requirements

When implementation lands, the conformance suite for
`tools/machines-viz-mcp/` **MUST** carry the following regression
tests. The shape mirrors the conformance set already shipped for
pair2-mcp and causa-mcp:

1. **Sensitive-event suppression.** A `subscribe` topic emitting
   `:rf.machine/*` events with `:sensitive? true` MUST drop the
   sensitive events by default and surface `:dropped-sensitive`
   on the progress payload. With `:include-sensitive? true`, the
   same call MUST forward the events verbatim.
2. **Large-value elision.** A direct read of `[:rf/machines <id>]`
   whose snapshot carries an over-threshold leaf (declared-large
   via `:rf/elision` or runtime-size flagged) MUST return the
   `:rf.size/large-elided` marker at the elided slot and surface
   `:elided-large` on the response envelope. With
   `:include-large? true`, the same read MUST return the full
   value.
3. **Sensitive-wins composition.** A leaf that is both
   `:sensitive? true` and over the size threshold MUST drop /
   redact **without** emitting an `:rf.size/large-elided` marker
   (the marker's `:path` / `:bytes` / `:digest` slots are an
   information leak).
4. **Share-URL exfil regression.** Encoding a share URL for a
   machine whose snapshot carries `:data` MUST produce a URL
   fragment whose decoded `:snapshot` map carries `:state` only —
   `:data` MUST be structurally absent. The test feeds the
   decoder output through the upstream `ChartState` Malli schema
   and asserts the `{:closed true}` validation passes; an
   implementation that smuggles `:data` through fails the schema.
5. **Source-coord exfil regression.** Encoding a share URL for a
   machine whose definition carries `:source-coords` meta MUST
   produce a URL fragment whose decoded payload contains no
   absolute file paths. The test scans the decoded EDN for the
   `:rf/source-coord-meta` shape and asserts absence.
6. **Export artefact parity.** An SVG export for a machine whose
   snapshot carries `:data` MUST produce bytes that do not
   contain the `:data` value (string-match assertion against the
   rendered SVG / a structural parse against the embedded
   metadata, depending on which export shape lands).

The first three tests cover the cross-MCP wire contract (the
default-drop filter, the elision walker, the composition cascade);
the last three cover the share/export-specific exfil paths that
distinguish this server from its read-only siblings. Both classes
are MUST-level on the implementation pass.

### Cross-references

- [`spec/Tool-Pair.md` §Direct-read privacy posture for `sub-cache` and `get-path`](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — the framework contract for tree-shaped MCP reads.
- [`spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) — the trace-stream filter contract.
- [`spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md#size-elision-in-traces) — the walker contract + sensitive-wins composition.
- [`spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters) — `:dropped-sensitive` / `:elided-large` slot reservation.
- [`tools/machines-viz/spec/API.md` §Share-URL payload schema](../../machines-viz/spec/API.md#share-url-payload-schema) — the tightened upstream schema this server inherits.
- [`tools/machines-viz/spec/Principles.md` §No session data in shares](../../machines-viz/spec/Principles.md) — the upstream exclusion policy.
- [`tools/causa-mcp/spec/004-Wire-Pipeline.md`](../../causa-mcp/spec/004-Wire-Pipeline.md) — sibling MCP server's wire pipeline; the wording above aligns deliberately so cross-server symmetry holds.
- [`tools/mcp-base/src/re_frame/mcp_base/sensitive.cljc`](../../mcp-base/src/re_frame/mcp_base/sensitive.cljc) — the shared default-drop helper (`strip-sensitive`, `scrub-snapshot`).
- [`tools/mcp-base/src/re_frame/mcp_base/elision.cljc`](../../mcp-base/src/re_frame/mcp_base/elision.cljc) — the shared indicator-count helper (`count-elided-markers`).
- [`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs) — reference implementation for the eval-form composition.

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
