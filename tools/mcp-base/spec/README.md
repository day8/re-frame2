# `re-frame2-mcp-base` — shared primitives for the MCP triplet

`day8/re-frame2-mcp-base` is the CLJC library that holds the
genuinely-cross-cutting primitives consumed by every MCP server in
the re-frame2 tool triplet:

- `tools/pair2-mcp/` (CLJS / Node — runs over nREPL to a browser app)
- `tools/story-mcp/` (JVM / Clojure — bridges to `tools/story/`)
- `tools/causa-mcp/` (CLJS / Node — runs over nREPL to a browser app; eighteen-tool catalogue at `tools/causa-mcp/spec/004-Tools-Catalogue.md`)

The factoring landed under [rf2-vw4sq][bead]. The per-namespace
contract expansion (rf2-643ia / rf2-0hs5t.5) splits each shipped
namespace into its own one-shot-able spec doc; this README is the
**index over those per-namespace contracts**, not the normative
source for any namespace's surface.

[bead]: https://github.com/day8/re-frame2/issues/rf2-vw4sq

## Canonical home — external to `/spec`

This spec/ folder is the **canonical home** for the cross-MCP shared
primitives — a tool-shared contract that lives with the tool artefact
rather than in the project-level [`/spec`](../../../spec/), per
[`/spec/README.md` §Canonical homes outside `/spec`](../../../spec/README.md#canonical-homes-outside-spec)
(rf2-0hs5t.3 (a)). The surface is indexed back to the framework via
a row in [`/spec/Ownership.md`](../../../spec/Ownership.md); the
framework's normative contract surface (the `:sensitive?`
substrate, the `:rf.size/*` markers, the wire-elision walker) lives
in [`/spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
and [`/spec/Conventions.md`](../../../spec/Conventions.md). This
folder pins the **cross-MCP shape** of the consumer-side primitives
that ride on those framework surfaces.

## Index

Seven namespaces under `re-frame.mcp-base.*`. Each ships its own
per-namespace contract doc; the table below indexes them:

| ns | Lines (src) | Surface | Per-namespace spec |
|---|---|---|---|
| `vocab` | 228 | `:rf.mcp/*` + `:rf.size/*` marker keys + envelope slots + JSON-RPC error codes. | [`vocab.md`](vocab.md) |
| `sensitive` | 212 | spec/009 §Privacy default-suppress filter (`sensitive-event?`, `strip-sensitive`, `scrub-snapshot`) + fail-closed suppressed-event counter. | [`sensitive.md`](sensitive.md) |
| `elision` | 60 | Wire-boundary `:rf.size/large-elided` walker (`count-elided-markers`, rf2-9fz64). | [`elision.md`](elision.md) |
| `args` | 253 | Argument coercion helpers (`parse-boolean`, `parse-positive-int`, `fresh-keyword`, `safe-keyword`, `parse-mode`, …). | [`args.md`](args.md) |
| `diff-encode` | 318 | Path-keyed structural diff for epoch `:db-after` slots (rf2-1wdzp) + decoder gate. | [`diff-encode.md`](diff-encode.md) |
| `overflow` | 60 | Overflow-marker payload SHAPE builder + per-tool hint table (rf2-rvyzy). | [`overflow.md`](overflow.md) |
| `cap` | 242 | Wire-boundary token-budget cap pipeline + `ResultIO` protocol (rf2-eyelu) + resource controls + token splitter. | [`cap.md`](cap.md) |

All `.cljc`, so consumers compile them under their own platform —
pair2-mcp's shadow-cljs node build, story-mcp / causa-mcp's JVM
classpath. The library's `deps.edn` carries only
`org.clojure/clojure`; no consumer-side runtime deps.

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
   protocol-shaped helper per platform; pair2-mcp and causa-mcp
   both run on Node and share `js/Buffer`, so a single codec helper
   here is not yet warranted.

3. **Tool registries.** Each MCP server's tool catalogue is domain-
   specific. The base provides building blocks; it does NOT
   prescribe how the registry is shaped.

## Cross-MCP vocabulary as a versioned contract

The marker keys + envelope slots + JSON-RPC codes are a **wire-
protocol contract**. A rename here breaks every connected agent.
Two layers of protection:

1. **The cross-MCP conformance gate** at
   `tools/mcp-conformance/wire-vocab/` pins the canonical Malli
   schema for every reserved `:rf.mcp/*` / `:rf.size/large-elided` /
   `:rf.elision/at` marker and asserts that fixtures + source text
   from every emitting server conform. Any rename or shape drift
   fails the JVM test corpus.
2. **The marker-key vars in `vocab.cljc`** are the single
   reference point — every server reads them via `(:require ...)`
   rather than re-typing the keyword literal. A grep for
   `:rf.mcp/overflow` shows exactly one defining occurrence;
   everywhere else is a `vocab/overflow-key` reference.

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
   ns keeps a thin local alias over `re-frame.privacy/sensitive?`
   for code-review locality — the predicate itself lives here.)

A new shared primitive ships with:

- A per-namespace spec doc in this folder (`<ns>.md`), at the
  one-shot bar — the doc should describe the surface fully enough
  that a future contributor can rebuild the ns from it without
  consulting source.
- An entry in the index table above.
- An update to [`/spec/Ownership.md`](../../../spec/Ownership.md)
  if the surface is genuinely framework-level cross-cutting (most
  cross-MCP primitives are not — they live under the existing
  "Cross-MCP shared primitives" row).

## See also

- [`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
  — pair2-mcp's principles, downstream consumer of these primitives.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/)
  — the JVM-side cross-MCP conformance corpus that pins the marker
  SHAPE across every consumer.
- [`/spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §Size elision in traces — the framework primitive the `elision` ns
  counts the output of.
- [`/spec/Conventions.md`](../../../spec/Conventions.md) §Cross-MCP
  indicator-field vocabulary — the MUST-level parity between the
  `:dropped-sensitive` and `:elided-large` envelope slots.
- [`/spec/Ownership.md`](../../../spec/Ownership.md) — the row that
  indexes this spec folder under the canonical-homes-outside-`/spec`
  rule.
- [`/spec/README.md` §Canonical homes outside `/spec`](../../../spec/README.md#canonical-homes-outside-spec)
  — the rule that sanctions this folder as an external canonical home.
