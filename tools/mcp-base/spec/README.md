# `re-frame2-mcp-base` — shared primitives for the MCP triplet

`day8/re-frame2-mcp-base` is the CLJC library that holds the
genuinely-cross-cutting primitives consumed by every MCP server in
the re-frame2 tool triplet:

- `tools/pair2-mcp/` (CLJS / Node — runs over nREPL to a browser app)
- `tools/story-mcp/` (JVM / Clojure — bridges to `tools/story/`)
- `tools/causa-mcp/` (planned; spec lives at `tools/causa-mcp/spec/`)

The factoring landed under [rf2-vw4sq][bead].

[bead]: https://github.com/day8/re-frame2/issues/rf2-vw4sq

## What lives here

Five small namespaces under `re-frame.mcp-base.*`:

| ns           | Surface                                                        |
|--------------|----------------------------------------------------------------|
| `vocab`      | `:rf.mcp/*` / `:rf.size/*` marker keys + JSON-RPC error codes.  |
| `sensitive`  | spec/009 §Privacy default-suppress filter (`sensitive-event?`, `strip-sensitive`, `scrub-snapshot`). |
| `elision`    | Wire-boundary `:rf.size/large-elided` walker (`count-elided-markers`, rf2-9fz64). |
| `args`       | Argument coercion helpers (`parse-boolean`, `parse-positive-int`, `parse-keyword`, `parse-mode`, …). |
| `diff-encode` | Path-keyed structural diff for epoch records (rf2-1wdzp).      |
| `overflow`   | Overflow-marker payload builder (rf2-rvyzy).                   |
| `cap`        | Wire-boundary token-budget cap pipeline + `ResultIO` protocol (rf2-eyelu). |

All `.cljc`, so consumers compile them under their own platform —
pair2-mcp's shadow-cljs node build, story-mcp / causa-mcp's JVM
classpath.

## What deliberately does NOT live here

The bead's scope holds the line at primitives that are truly
identical across the triplet's wire / privacy / size surfaces.
Three categories stay consumer-side:

1. **Wire transport.** story-mcp uses Cheshire for JSON-RPC over
   stdin/stdout; pair2-mcp uses the npm `@modelcontextprotocol/sdk`'s
   stdio transport. The framing is different by language; there's
   nothing useful to share here.

2. **Cursor base64 codec.** The cursor *shape* (`{:v 1 :after-id ...
   :ms ... :until-ms ... :frame ...}`) is conventional — but
   pair2-mcp uses `js/Buffer.from … "base64"` and a JVM consumer
   would use `java.util.Base64`. Factoring the codec means a
   protocol-shaped helper per platform; not worth the indirection
   until causa-mcp lands and demonstrates the third call site.

3. **Tool registries.** Each MCP server's tool catalogue is domain-
   specific. The base provides building blocks; it does NOT
   prescribe how the registry is shaped.

## Cross-MCP vocabulary

The `vocab.cljc` ns is the single source of truth for the marker
keys an agent learns once and recognises across every MCP it talks
to. A rename here is a wire-protocol break; `vocab_test.clj` fails
loud when that happens.

The marker family is documented in the consumer-side principles
files:

- [`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
- [`tools/causa-mcp/spec/Principles.md`](../../causa-mcp/spec/Principles.md) (defers to [`004-Wire-Pipeline.md`](../../causa-mcp/spec/004-Wire-Pipeline.md))

## Adding to the base

Two rules:

1. **It must be implemented somewhere already.** This artefact is
   for factoring duplication, not for landing speculative shared
   surfaces. New primitives land in a consumer first; if a second
   consumer needs the same code, lift it then.

2. **It must be pure CLJC with no consumer-side deps.** The base's
   `deps.edn` carries only `org.clojure/clojure`. If your primitive
   needs cheshire / re-frame.trace / shadow-cljs / js-interop, it
   belongs in its consumer, not here. (story-mcp's `sensitive.cljc`
   ns keeps a thin local alias over `re-frame.privacy/sensitive?` for
   code-review locality — the predicate itself lives here.)
