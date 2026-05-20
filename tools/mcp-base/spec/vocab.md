# `vocab` — wire-vocabulary constants

> **Type:** Reference (`tools/mcp-base/spec/`)
> The single source of truth for the marker keys an agent learns once and recognises across every MCP server in the re-frame2 pair — `re-frame2-pair-mcp` and `story-mcp`. A rename here is a wire-protocol break; the cross-MCP conformance gate under `tools/mcp-conformance/wire-vocab/` fails loud when that happens.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md).

## Scope

`vocab` owns:

- The `:rf.mcp/*` marker keyword catalogue.
- The `:rf.size/*` marker keyword catalogue (shared with the framework's `rf/elide-wire-value` walker).
- The unqualified envelope counter slots (`:dropped-sensitive`, `:elided-large`).
- The JSON-RPC 2.0 §5.1 error codes used by every server in the triplet.

`vocab` does NOT own:

- The walker implementation (`elide-wire-value` lives in `day8/re-frame2` core).
- The marker-emission policy (which tool emits which marker — that lives in each consumer's tool catalogue).
- Wire-transport framing (each server uses its own stdio JSON-RPC binding — see `tools/mcp-base/spec/README.md` §What deliberately does NOT live here).

## Two namespaces + envelope slots

`:rf.mcp/*` — per-tool wire-mechanism markers. Owned by the MCP servers; not part of the framework runtime vocabulary.

`:rf.size/*` — size-elision markers. Owned jointly with the framework's `rf/elide-wire-value` walker (per [`../../../spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned); [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md)).

Unqualified envelope slots — `:dropped-sensitive`, `:elided-large` — are per-call scalar counters summarising the walker's suppression count (per [`../../../spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md); [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md) — Indicator field on tool responses, MUST-level per rf2-2499j).

## Marker catalogue (`:rf.mcp/*`)

| Var | Key | Shape | Source bead |
|---|---|---|---|
| `overflow-key` | `:rf.mcp/overflow` | `{:limit :reached :token-count N :cap-tokens M :tool "…" :hint "…"}` | rf2-rvyzy |
| `dedup-table-key` | `:rf.mcp/dedup-table` | `{<cache-map>}` (de-dupe library) | rf2-obpa9 |
| `diff-from-key` | `:rf.mcp/diff-from` | Slot pointer keyword (`:db-before`) | rf2-1wdzp |
| `cursor-stale-reason` | `:rf.mcp/cursor-stale` | Error-result `:reason` value | rf2-kbqq3 |
| `cache-hit-key` | `:rf.mcp/cache-hit` | `{:tool … :digest … :hint …}` (content-free; agent host correlates by cache key) | rf2-3rt1f / rf2-36xod |
| `summary-key` | `:rf.mcp/summary` | `{<tree-summary>}` (lazy-summary projection) | rf2-tygdv / rf2-u2029 |

## Marker catalogue (`:rf.size/*`)

| Var | Key | Role | Source |
|---|---|---|---|
| `large-elided-key` | `:rf.size/large-elided` | Substituted for an over-threshold leaf (or declared-large slot). | Spec 009 §Size elision |
| `redacted-sentinel` | `:rf/redacted` | In-place **scalar sentinel** (bare keyword, no body) substituted for a `:sensitive?` leaf by `rf/elide-wire-value` / `with-redacted`. Unlike `:rf.size/large-elided`, there is no `:handle` / `:bytes` slot — the value is gone and MUST NOT be re-fetched. Reserved under the `:rf/*` single-root scheme (Conventions §Reserved namespaces). | Spec 009 §Privacy / `tools/mcp-base/src/re_frame/mcp_base/vocab.cljc` `redacted-sentinel` |
| `elision-handle-key` | First slot in the elision handle vector | Vector-shaped handle for follow-up `get-path` calls. | rf2-9fz64 |
| `include-large-opt` / `include-sensitive-opt` / `include-digests-opt` / `threshold-bytes-opt` | (framework-side opts) | Knobs `rf/elide-wire-value` honours when the consumer relays a wire request to the walker. | Spec 009 §Size elision |

## Envelope counter slots

| Var | Slot | Counts |
|---|---|---|
| `dropped-sensitive-key` | `:dropped-sensitive` | `:sensitive? true` leaves dropped this call. |
| `elided-large-key` | `:elided-large` | Leaves replaced with the `:rf.size/large-elided` marker. |

Both slots ride the response envelope alongside the tool's unqualified slots. **Indicator-field parity** is MUST-level: if one slot is emitted, the other must be too (the round-2 audit fix the conformance gate enforces).

## JSON-RPC error codes (per JSON-RPC 2.0 §5.1)

The same numeric codes apply across the triplet. Owned constants:

- `code-parse-error` (-32700)
- `code-invalid-request` (-32600)
- `code-method-not-found` (-32601)
- `code-invalid-params` (-32602)
- `code-internal-error` (-32603)

Story-mcp emits them via Cheshire; re-frame2-pair-mcp emits via the npm MCP SDK's `isError: true` tool-result shape, but the codes still pin the cross-consumer protocol surface.

## Conformance posture

The marker keys + envelope slots + JSON-RPC codes are a **wire-protocol contract**. A rename here breaks every connected agent. Two layers of protection:

1. **The cross-MCP conformance gate** at `tools/mcp-conformance/wire-vocab/` pins the canonical Malli schema for every reserved `:rf.mcp/*` / `:rf.size/large-elided` / `:rf.elision/at` marker and asserts that fixtures + source text from every emitting server conform. Any rename or shape drift fails the JVM test corpus.
2. **The marker-key vars in `vocab.cljc`** are the single reference point — every server reads them via `(:require ...)` rather than re-typing the keyword literal. A grep for `:rf.mcp/overflow` shows exactly one defining occurrence; everywhere else is a `vocab/overflow-key` reference.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md) — the framework primitive `:rf.size/*` markers ride on.
- [`../../../spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned) — the framework-owned namespace policy that gates these reserved keys.
- [`../../mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/) — the cross-MCP conformance corpus that pins this ns's marker SHAPE across every consumer.
