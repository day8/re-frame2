# Handler-arity divergence across the MCP triplet

Source: rf2-63s4e (rf2-h1izl follow-on C7).

The two shipped MCP servers in the re-frame2 triplet use **different
registry-handler arities**:

| Server                    | Handler shape                | Why                                                        |
|---------------------------|------------------------------|------------------------------------------------------------|
| `tools/re-frame2-pair-mcp` | `(fn [conn args extra])`     | Needs `conn` (the persistent nREPL session into the browser runtime) AND `extra` (the MCP `signal` + `sendNotification` + `_meta.progressToken` payload — load-bearing for the streaming `subscribe` tool). |
| `tools/story-mcp`         | `(fn [args])`                | JVM-side single-process; no remote runtime to connect to, no streaming tool. Neither `conn` nor `extra` carries any meaning here, so the handler shape collapses to a pure-data fn. |

The two source-of-truth declarations:

- pair-mcp:
  [`tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs`](../../re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs)
  — `:handler` is uniformly 3-arity `(fn [conn args extra])`; per-tool
  fns that don't consult `extra` are wrapped via `ignoring-extra` so
  the dispatcher invokes every handler with the same calling
  convention.
- story-mcp:
  [`tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc`](../../story-mcp/src/re_frame/story_mcp/tools/registry.cljc)
  — `:handler` is uniformly 1-arity `(fn [args])`; the wire-boundary
  cap dispatcher (`re-frame.story-mcp.tools.cap/invoke-tool`) calls
  `(handler arguments)` and returns the EDN result map.

## Consequence

Cross-MCP-aware tooling under `tools/mcp-base/` cannot abstract the
handler invocation shape — each server's dispatcher is a distinct
implementation. A future fourth MCP-server author who consults both
servers as examples sees two patterns rather than one. This is the
**deliberate gap** acknowledged here pending a third instance
(`causa-mcp`, blocked behind rf2-hvl1g's scope discussion at time of
writing) crystallising the canonical shape.

## Why the divergence stayed

Both shapes are correct for their respective servers. Forcing pair-mcp's
handlers to drop `conn` / `extra` would push the streaming
`subscribe` plumbing into a side-channel (or force `extra` through a
dynamic var); forcing story-mcp's handlers to accept dead slots adds
boilerplate without buying anything. The honest answer is "two
correct shapes, not yet unified" — that's what this doc records.

## Phase-2 unification (deferred — separate bead)

The unification is **not** part of this bead. A separate follow-on
will factor an explicit `ExecutionContext` type (likely a record /
map with optional `:conn` / `:progress-callback` / `:request-meta`
slots) into `tools/mcp-base/` and refit both servers to a uniform
`(fn [ctx args])` handler shape. That bead will be filed once
`causa-mcp` lands and the three-server pattern is observable; until
then there's no reliable third instance to validate the shape
against.

When it lands, the unification should:

1. Add a `re-frame.mcp-base.execution-context` namespace pinning the
   ctx record/map shape + accessor fns.
2. Refit `re-frame2-pair-mcp.tools.registry` to wrap every per-tool fn
   into the new `(fn [ctx args])` shape (the `ignoring-extra`
   adapter becomes `ctx->conn-args-extra` or similar; subscribe's
   handler reads `:conn` / `:progress-callback` off the ctx
   instead).
3. Refit `re-frame.story-mcp.tools.cap` / category descriptors to
   call handlers with `(handler ctx args)`; the JVM-side
   `:conn` / `:progress-callback` slots are nil and ignored.
4. `causa-mcp` adopts the unified shape from day one when it lands.

Until that bead lands, the divergence is the documented state.

## See also

- [README.md](README.md) §"What deliberately does NOT live here"
  — the existing list of tool-shaped surfaces that stay consumer-side
  (wire transport, cursor base64 codec, tool registries). Handler-arity
  belongs on that list in spirit but is broken out into its own doc
  because the divergence is structural — a future fourth server
  WILL need to pick one shape, and this doc gives them the context.
- [`tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs`](../../re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs)
  — pair-mcp's 3-arity registry; ns docstring §"Handler arity
  convention" cross-references this doc.
- [`tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc`](../../story-mcp/src/re_frame/story_mcp/tools/registry.cljc)
  — story-mcp's 1-arity registry; ns docstring cross-references this doc.
- [`spec/Ownership.md`](../../../spec/Ownership.md) — the row that
  indexes the `tools/mcp-base/spec/` folder under the canonical-homes-
  outside-`/spec` rule (handler-arity divergence falls under that
  row's cross-MCP shape contract).
