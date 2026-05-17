# 004-Wire-Pipeline

The off-box egress contract for Machines-Viz-MCP. The MCP-server-side
code is the renderer; this doc is the **normative pipeline every
tool response passes through** before bytes cross the stdio JSON-RPC
trust boundary into the agent host (and from there potentially to an
LLM provider).

Machines-Viz-MCP is an **off-box egress surface**: machine
enumeration, transition-table reads, direct snapshot reads at
`[:rf/machines <id>]`, share-URL encoding, SVG/PNG export, and
future `:rf.machine/*` trace streaming all cross the stdio JSON-RPC
trust boundary. The framework already pins a cross-MCP privacy
posture — `tools/pair2-mcp/`, `tools/causa-mcp/`, and
`tools/story-mcp/` honour it today. **Machines-Viz-MCP inherits the
same contract**; the rules below are normative on the implementation
pass, and were pinned here pre-implementation (rf2-epiao) to
prevent a future cut from shipping raw machine snapshots or raw
`:rf.machine/*` events across the boundary by accident.

The trust boundary is the MCP stdio channel. Every rule below gates
**what crosses that channel by default**, and every opt-in escape
hatch is **off by default** — a tool that omits the opts gets the
safe posture. Cross-server slot names match
[`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
so an agent that learned the vocabulary on a sibling server
recognises it here verbatim.

## Trace-stream surface — default-drop `:sensitive?` events (MUST)

Per [spec/009 §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(rf2-a32kd):

> Framework-published listener integrations (Sentry/Honeybadger
> forwarders, pair2 server, Causa-MCP server) MUST default-suppress
> `:sensitive? true`.

Machines-Viz-MCP is such a listener integration. The streaming
`subscribe` tool for `:rf.machine/*` events (and any future tool
whose return surfaces `:rf/trace-event`-shaped items) **MUST**
apply the default-suppress filter at the MCP boundary before any
data crosses into the agent surface. Pull the shared helper from
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

## Tree-shaped read surface — `elide-wire-value` on every direct read (MUST)

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

## Caller opt-in flags + indicator slots (MUST)

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

## Share-URL + export — inherit the upstream tightened schema

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

## Conformance test requirements

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

## Cross-references

- [`spec/Tool-Pair.md` §Direct-read privacy posture for `sub-cache` and `get-path`](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — the framework contract for tree-shaped MCP reads.
- [`spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) — the trace-stream filter contract.
- [`spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md#size-elision-in-traces) — the walker contract + sensitive-wins composition.
- [`spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters) — `:dropped-sensitive` / `:elided-large` slot reservation.
- [`tools/machines-viz/spec/API.md` §Share-URL payload schema](../../machines-viz/spec/API.md#share-url-payload-schema) — the tightened upstream schema this server inherits.
- [`tools/machines-viz/spec/Principles.md` §No session data in shares](../../machines-viz/spec/Principles.md) — the upstream exclusion policy.
- [`tools/mcp-base/src/re_frame/mcp_base/sensitive.cljc`](../../mcp-base/src/re_frame/mcp_base/sensitive.cljc) — the shared default-drop helper (`strip-sensitive`, `scrub-snapshot`).
- [`tools/mcp-base/src/re_frame/mcp_base/elision.cljc`](../../mcp-base/src/re_frame/mcp_base/elision.cljc) — the shared indicator-count helper (`count-elided-markers`).
- [`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs) — reference implementation for the eval-form composition.
