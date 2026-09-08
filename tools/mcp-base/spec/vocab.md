# `vocab` — wire-vocabulary constants

> **Type:** Reference (`tools/mcp-base/spec/`)
> The single source of truth for the marker keys an agent learns once and recognises across every MCP server in the re-frame2 pair — `re-frame2-pair-mcp` and `story-mcp`. A rename here is a wire-protocol break; the cross-MCP conformance gate under `tools/mcp-conformance/wire-vocab/` fails loud when that happens.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`vocab` owns:

- The `:rf.mcp/*` marker keyword catalogue.
- The `:rf.size/*` marker keyword catalogue (shared with the framework's `rf/project-egress` walker).
- The `:rf.egress/*` framework egress-opts keywords a server relays INWARD to that walker (rf2-kuky.93 moved these out of `:rf.size/*`).
- The unqualified envelope counter slots (`:dropped-sensitive`, `:elided-large`).
- The JSON-RPC 2.0 §5.1 error-code constants used by direct JSON-RPC consumers.

`vocab` does NOT own:

- The walker implementation (`project-egress` lives in `day8/re-frame2` core).
- The marker-emission policy (which tool emits which marker — that lives in each consumer's tool catalogue).
- Wire-transport framing (each server uses its own stdio JSON-RPC binding — see `tools/mcp-base/spec/README.md` §What deliberately does NOT live here).

## Three namespaces + envelope slots

`:rf.mcp/*` — per-tool wire-mechanism markers. Owned by the MCP servers; not part of the framework runtime vocabulary.

`:rf.size/*` — the size-elision MARKER `:rf.size/large-elided`, and since rf2-kuky.93 nothing else. Owned jointly with the framework's `rf/project-egress` walker (per [`../../../spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned); [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md)).

`:rf.egress/*` — the framework's egress-opts vocabulary: the named boundary `:rf.egress/profile` and the per-call policy keys beneath it. These travel INWARD to `rf/project-egress`; they are never wire keys (a wire arg drops both the namespace and the `?` — see [`egress.md`](egress.md) and [`sensitive.md`](sensitive.md)).

Unqualified envelope slots — `:dropped-sensitive`, `:elided-large` — are per-call scalar counters summarising suppression and elision (per [`../../../spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md) and [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md)).

## Marker catalogue (`:rf.mcp/*`)

| Var | Key | Shape |
|---|---|---|
| `overflow-key` | `:rf.mcp/overflow` | `{:limit :reached :token-count N :cap-tokens M :tool "…" :hint "…"}` |
| `dedup-table-key` | `:rf.mcp/dedup-table` | `{<cache-map>}` (`re-frame.mcp-base.dedup`) |
| `diff-from-key` | `:rf.mcp/diff-from` | Slot pointer keyword (`:db-before`) |
| `cursor-stale-reason` | `:rf.mcp/cursor-stale` | Error-result `:reason` value for an invalid continuation position |
| `cache-hit-key` | `:rf.mcp/cache-hit` | `{:tool … :digest … :hint …}` (content-free; agent host correlates by cache key) |
| `summary-key` | `:rf.mcp/summary` | `{<tree-summary>}` (lazy-summary projection) |
| `invalid-arg-key` | `:rf.mcp/invalid-arg` | `{:arg <kw> :value <supplied> :hint <str>}` — payload of an `isError: true` result rejecting a malformed per-call arg. See [`cap.md` §Out-of-domain `:max-tokens` is rejected](cap.md#out-of-domain-max-tokens-is-rejected). |
| `result-key` | `:rf.mcp/result` | `{:rf.mcp/result <tag> …}` — typed evaluation outcome (`:value`, `:nil`, `:eval-error`, or `:unserializable`). See [Tool-Pair §Wire fidelity](../../../spec/Tool-Pair.md). |

## Marker catalogue (`:rf.size/*`)

| Var | Key | Role | Source |
|---|---|---|---|
| `large-elided-key` | `:rf.size/large-elided` | Substituted for an over-threshold leaf (or declared-large slot). | Spec 009 §Size elision |
| `redacted-sentinel` | `:rf/redacted` | In-place **scalar sentinel** substituted by the egress projection/walker for a sensitive value. Unlike `:rf.size/large-elided`, it has no handle: the value must not be re-fetched. | Spec 009 §Privacy |
| `elision-handle-key` | First slot in the elision handle vector | Vector-shaped handle for follow-up `get-path` calls. | Spec 009 §Size elision |

## Framework egress-opt catalogue (`:rf.egress/*`)

| Var | Key | Role | Source |
|---|---|---|---|
| `include-large-opt` / `include-sensitive-opt` / `include-digests-opt` / `threshold-bytes-opt` | `:rf.egress/include-large?` / `:rf.egress/include-sensitive?` / `:rf.egress/include-digests?` / `:rf.egress/threshold-bytes` | Knobs `rf/project-egress` honours when the consumer relays a wire request to the walker. Framework-side, never wire keys. | Spec 009 §Size elision |

## Envelope counter slots

| Var | Slot | Counts |
|---|---|---|
| `dropped-sensitive-key` | `:dropped-sensitive` | Sensitive records dropped this call, including fail-closed malformed stamps. |
| `elided-large-key` | `:elided-large` | Leaves replaced with the `:rf.size/large-elided` marker. |

Tree-payload emitters compute both indicators under the same convention. Each zero count is omitted independently; a positive count emits its own slot.

## JSON-RPC error codes (per JSON-RPC 2.0 §5.1)

The constants follow JSON-RPC 2.0:

- `code-parse-error` (-32700)
- `code-invalid-request` (-32600)
- `code-method-not-found` (-32601)
- `code-invalid-params` (-32602)
- `code-internal-error` (-32603)

Story-mcp uses these constants in its direct JSON-RPC envelopes. re-frame2-pair-mcp delegates protocol-level errors to the npm MCP SDK; `isError: true` is a tool-result failure shape, not a JSON-RPC error code.

## Conformance posture

The marker keys + envelope slots + JSON-RPC codes are a **wire-protocol contract**. A rename here breaks every connected agent. Two layers of protection:

1. **The cross-MCP conformance gate** at `tools/mcp-conformance/wire-vocab/` pins the canonical Malli schema for every reserved `:rf.mcp/*` / `:rf.size/large-elided` / `:rf.elision/at` marker and asserts that fixtures + source text from every emitting server conform. Any rename or shape drift fails the JVM test corpus.
2. **The marker-key vars in `vocab.cljc`** are the shared reference point for executable consumers rather than consumer-local constant definitions.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md) — the framework primitive `:rf.size/*` markers ride on.
- [`../../../spec/Conventions.md` §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned) — the framework-owned namespace policy that gates these reserved keys.
- [`../../mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/) — the cross-MCP conformance corpus that pins this ns's marker SHAPE across every consumer.
