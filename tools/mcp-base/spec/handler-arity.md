# Handler-arity divergence across the MCP pair

The two shipped MCP servers in the re-frame2 pair use **different
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
  cap dispatcher (`re-frame.story-mcp.tools.wire-pipeline/invoke-tool`) calls
  `(handler arguments)` and returns the EDN result map.

## Ownership

Handler invocation stays consumer-side. mcp-base shares the pure wire
primitives used around invocation, but it does not define a common
handler protocol or adapt one server's registry to the other.

## Why the divergence stayed

Both shapes are correct for their respective servers. Forcing pair-mcp's
handlers to drop `conn` / `extra` would push the streaming
`subscribe` plumbing into a side-channel (or force `extra` through a
dynamic var); forcing story-mcp's handlers to accept dead slots adds
boilerplate without buying anything. The divergence follows the two
dispatchers' current requirements.

## See also

- [README.md](README.md) §"What deliberately does NOT live here"
  — the existing list of tool-shaped surfaces that stay consumer-side.
- [`tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs`](../../re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs)
  — pair-mcp's 3-arity registry; ns docstring §"Handler arity
  convention" cross-references this doc.
- [`tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc`](../../story-mcp/src/re_frame/story_mcp/tools/registry.cljc)
  — story-mcp's 1-arity registry; ns docstring cross-references this doc.
- [`spec/Ownership.md`](../../../spec/Ownership.md) — the row that
  indexes the `tools/mcp-base/spec/` folder under the canonical-homes-
  outside-`/spec` rule (handler-arity divergence falls under that
  row's cross-MCP shape contract).
