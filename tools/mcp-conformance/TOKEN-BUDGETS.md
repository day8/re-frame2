# Cross-MCP token-budget posture

`re-frame2-pair-mcp` and `story-mcp` share one response-budget contract:

- a 5,000-token default cap;
- a `max-tokens` per-call argument on every tool;
- `max-tokens 0` as the explicit cap-disable sentinel; and
- `:rf.mcp/overflow` as the structured retry signal.

The shared implementation lives in `tools/mcp-base`. Server specs own
their domain-specific slicing, pagination, summary, and hint details.

## Measurement

The cap is enforced at each server's `tools/call` wire boundary through
`re-frame.mcp-base.cap/apply-cap`.

Each server's `ResultIO` implementation exposes every serialized,
payload-bearing string that will ride the wire. This includes the text
content and any duplicated `structuredContent` projection. The cap must
not measure only one encoding of a payload that is transmitted twice.

The primary estimate is `(quot (count text) 4)` for each payload string.
The pipeline also applies a `cap * 8` aggregate character ceiling. The
second bound covers many short strings whose per-string quotient rounds
to zero and gives a conservative backstop for data unlike English prose.
The objective is bounded egress, not tokenizer-precise billing.

## `max-tokens`

The common resolver has these results:

| Input | Result |
|---|---|
| absent or non-numeric | use the 5,000 default |
| `0` | disable the cap for this call |
| integer `>= 1` | use that cap |
| negative, fractional in `(0,1)`, non-finite, or out of range | return `:rf.mcp/invalid-arg` as an error result |

Every advertised descriptor includes the `max-tokens` property and
non-empty budget guidance. The descriptor and classification ratchets in
the Node conformance suite enforce that inventory-wide.

## Overflow

An over-budget result is replaced, never silently truncated:

```clojure
{:rf.mcp/overflow
 {:limit       :reached
  :token-count <integer>
  :cap-tokens  <integer>
  :tool        <string>
  :hint        <string>}}
```

Both servers build this body through
`re-frame.mcp-base.overflow/overflow-payload`. The per-tool hint is
server-authored; the key and body shape are cross-server vocabulary.
The JVM vocabulary suite and the live pair overflow test pin the shape.

Overflow is a successful budget signal, so `isError` remains false. A
client should narrow the request using paths, filters, limits, cursors,
or summary modes. Use `max-tokens 0` only when the full payload is
deliberately required.

## Pipeline invariants

Budget-reduction transforms run before the final cap. In particular,
structural dedup must be reflected in the payload that `apply-cap`
measures. The cap then either returns the result unchanged or replaces
the payload with the fixed-shape overflow marker.

The pair server additionally bounds streaming queues by event count and
bytes. That upstream resource control is distinct from the per-response
wire cap. Story-mcp has no streaming surface.

The detailed mechanism order belongs to the server specs:

- [`re-frame2-pair-mcp/spec/Principles.md`](../re-frame2-pair-mcp/spec/Principles.md)
- [`story-mcp/spec/Principles.md`](../story-mcp/spec/Principles.md)

## Multi-server sessions

The cap is per response, not per server process or host session. Servers
do not coordinate budgets. A host attached to both servers is
responsible for the sum of their responses and should:

1. start with default summaries or samples;
2. drill into a path or use a tighter filter;
3. paginate unbounded collections; and
4. disable the cap only for an intentional full read.

`max-tokens` applies independently to each call.

## Changing the contract

Changes to the default, argument name, disable sentinel, overflow key,
or overflow body must be made atomically across `mcp-base`, both server
adapters and descriptors, this document, and the conformance fixtures.
Marker-shape changes also require the JVM wire-vocabulary gate to change.

See also [`NAMING.md`](NAMING.md) and
[`wire-vocab/README.md`](wire-vocab/README.md).
