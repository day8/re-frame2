# `re-frame2-mcp-base` — shared primitives for the MCP pair

`day8/re-frame2-mcp-base` is the CLJC library that holds the
genuinely-cross-cutting primitives consumed by every MCP server in
the re-frame2 tool pair:

- `tools/re-frame2-pair-mcp/` (CLJS / Node — runs over nREPL to a browser app)
- `tools/story-mcp/` (JVM / Clojure — bridges to `tools/story/`)

This README indexes the per-namespace contracts; it is not the
normative source for an individual namespace's surface.

## Canonical home — external to `/spec`

This spec/ folder is the **canonical home** for the cross-MCP shared
primitives — a tool-shared contract that lives with the tool artefact
rather than in the project-level [`/spec`](../../../spec/), per
[`/spec/README.md` §Canonical homes outside `/spec`](../../../spec/README.md#canonical-homes-outside-spec).
The surface is indexed back to the framework via
a row in [`/spec/Ownership.md`](../../../spec/Ownership.md); the
framework's normative contract surface (the `:sensitive?`
substrate, the `:rf.size/*` markers, the wire-elision walker) lives
in [`/spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
and [`/spec/Conventions.md`](../../../spec/Conventions.md). This
folder pins the **cross-MCP shape** of the consumer-side primitives
that ride on those framework surfaces.

## Index

Thirteen namespaces under `re-frame.mcp-base.*`. Each ships its own
per-namespace contract doc; the table below indexes them:

| ns | Surface | Per-namespace spec |
|---|---|---|
| `vocab` | `:rf.mcp/*` + `:rf.size/*` marker keys + envelope slots + JSON-RPC error codes. | [`vocab.md`](vocab.md) |
| `sensitive` | spec/009 §Privacy fail-closed default-suppress filter (`sensitive-event?`, `strip-sensitive`, per-frame `scrub-snapshot`) + malformed-stamp counter. | [`sensitive.md`](sensitive.md) |
| `egress` | Cross-MCP `:rf.egress/*` profile vocabulary + pure-data `profile-size-opts` resolver — the framework-runtime-free mirror of the closed six-member egress enum and its `:rf.size/*` floor (EP-0015 §10). | [`egress.md`](egress.md) |
| `elision` | Wire-boundary `:rf.size/large-elided` marker counter (`count-elided-markers`). | [`elision.md`](elision.md) |
| `args` | Argument coercion helpers (`parse-boolean`, `parse-positive-int`, `fresh-keyword`, `safe-keyword`, `parse-mode`, …). | [`args.md`](args.md) |
| `diff-encode` | Path-keyed structural diff for epoch `:db-after` slots, projected into path-headed cluster sections, plus encoder/decoder Malli gates. | [`diff-encode.md`](diff-encode.md) |
| `section-grouping` | Patch-list → path-headed cluster sections (`group-patches-into-sections` / `sections->patches`); consumed by `diff-encode`. | [`section-grouping.md`](section-grouping.md) |
| `dedup` | The structural-dedup codec (`de-dupe-eq` / `expand`, vendored from `day8/de-dupe` under rf2-2ii52) plus the wire-boundary encode policy over it (`empty-payload?` / `no-substitutions?` / `dedup-value`). | [`dedup.md`](dedup.md) |
| `overflow` | Overflow-marker payload builder (`overflow-payload`) + `token-estimate` + fallback hint. | [`overflow.md`](overflow.md) |
| `cap` | Wire-boundary two-stage token-budget cap pipeline + `max-tokens` resolver + `ResultIO` protocol. | [`cap.md`](cap.md) |
| `cursor` | Shared cursor-pagination machinery — base64 codec, opaque encode/decode with `::malformed` recovery, `:limit` clamp, and `cursor-stale-result` envelope. | [`cursor.md`](cursor.md) |
| `envelope` | Indicator-field `with-indicators` splice (`:dropped-sensitive` / `:elided-large`, omit-when-zero MUST) + wire-bounded `:rf.mcp/*` marker detection. | [`envelope.md`](envelope.md) |
| `descriptor-manifest` | Shared MCP tool-descriptor manifest generator + drift-check — deterministic LF-pinned EDN serialiser (`render-edn`) + regenerate-vs-committed `check`. | [`descriptor-manifest.md`](descriptor-manifest.md) |

All `.cljc`, so consumers compile them under their own platform —
re-frame2-pair-mcp's shadow-cljs node build, story-mcp's JVM
classpath. The library's `deps.edn` carries `org.clojure/clojure` and
NOTHING ELSE at runtime: the one external dep it used to have
(`day8/de-dupe`, the persistent-data-structure walker behind `dedup`)
was vendored in under rf2-2ii52, because a `:git/url` coordinate is one
`clein pom` drops silently and it blocked this artefact's publish path
outright. Its pom is now complete by construction. No consumer-side
transport runtime deps either.

## Handler-arity divergence

The two shipped servers use **different registry-handler arities** —
pair-mcp is 3-arity `(fn [conn args extra])`, story-mcp is 1-arity
`(fn [args])`. The divergence is deliberate (pair-mcp needs `conn`
for nREPL and `extra` for streaming; story-mcp is single-process and
needs neither) and is documented in full at
[`handler-arity.md`](handler-arity.md).

## What deliberately does NOT live here

The base holds primitives that are shared across the pair's wire,
privacy, and size surfaces.
Two categories stay consumer-side:

1. **Wire transport.** story-mcp uses Cheshire for JSON-RPC over
   stdin/stdout; re-frame2-pair-mcp uses the npm `@modelcontextprotocol/sdk`'s
   stdio transport. The framing is different by language; there's
   nothing useful to share here.

2. **Tool registries.** Each MCP server's tool catalogue is domain-
   specific. The base provides building blocks; it does NOT
   prescribe how the registry is shaped.

The shared cursor codec and recovery helpers live in `cursor.cljc`,
parameterised by each consumer's cursor-payload shape. Cursor resource
controls remain pair-mcp-specific.

## Cross-MCP vocabulary as a versioned contract

Marker keys, envelope slots, and emitted JSON-RPC numeric values are
wire contracts. Changing a value requires updating its consumers and
conformance fixtures together.
Two layers of protection:

1. **The cross-MCP conformance gate** at
   `tools/mcp-conformance/wire-vocab/` pins the canonical Malli
   schema for every reserved `:rf.mcp/*` / `:rf.size/large-elided` /
   `:rf.elision/at` marker and asserts that fixtures + source text
   from every emitting server conform. Any rename or shape drift
   fails the JVM test corpus.
2. **The vars in `vocab.cljc`** are the shared reference point for
   executable consumers rather than consumer-local constant definitions.

## Adding to the base

Two rules:

1. **It must be implemented somewhere already.** This artefact is
   for factoring duplication, not for landing speculative shared
   surfaces. New primitives land in a consumer first; if a second
   consumer needs the same code, lift it then.

2. **It must be framework-runtime-free, with no consumer-side
   transport deps.** The base's `deps.edn` carries `org.clojure/clojure`
   and nothing else at runtime — and a new external coordinate has to
   clear the publish path as well as the design bar: this artefact is
   Clojars-published, so a dep `clein pom` cannot express (`:git/url`,
   `:local/root` outside the rewrite set) makes the jar unshippable. If
   your primitive needs cheshire / re-frame.trace / shadow-cljs /
   js-interop, it belongs in its consumer, not here. (re-frame2-pair-mcp
   keeps a thin local alias ns — `tools/sensitive.cljs` — that
   delegates to `re-frame.mcp-base.sensitive` for code-review locality;
   story-mcp requires `re-frame.mcp-base.sensitive` directly. The
   predicate itself lives here.)

**What this rules OUT** — concrete rejection cases so a contributor
sees the trap before falling in:

- **story-mcp's recorder bridge** — NO. Only one consumer; the bridge
  is recorder-specific machinery, not a cross-MCP primitive. Lifting
  it would invert the rule and pull recorder-shaped concerns into the
  base for every other server to ignore.
- **A re-frame2-pair-only nREPL bencode helper** — NO. Single
  consumer; nREPL transport is pair-mcp's domain. story-mcp does not
  speak nREPL; lifting would add a runtime concern the base does not
  need to know about.
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

- [`tools/re-frame2-pair-mcp/spec/Principles.md`](../../re-frame2-pair-mcp/spec/Principles.md)
  — re-frame2-pair-mcp's principles, downstream consumer of these primitives.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/)
  — the JVM-side cross-MCP conformance corpus that pins the marker
  SHAPE across every consumer.
- [`/spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §Size elision in traces — the framework primitive the `elision` ns
  counts the output of.
- [`/spec/Conventions.md`](../../../spec/Conventions.md) §Cross-MCP
  indicator-field vocabulary — the shared indicator and omit-when-zero rules.
- [`/spec/Ownership.md`](../../../spec/Ownership.md) — the row that
  indexes this spec folder under the canonical-homes-outside-`/spec`
  rule.
- [`/spec/README.md` §Canonical homes outside `/spec`](../../../spec/README.md#canonical-homes-outside-spec)
  — the rule that sanctions this folder as an external canonical home.
