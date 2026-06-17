# `re-frame2-mcp-base` — shared primitives for the MCP pair

`day8/re-frame2-mcp-base` is the CLJC library that holds the
genuinely-cross-cutting primitives consumed by every MCP server in
the re-frame2 tool pair:

- `tools/re-frame2-pair-mcp/` (CLJS / Node — runs over nREPL to a browser app)
- `tools/story-mcp/` (JVM / Clojure — bridges to `tools/story/`)

(Historical: a third server `xray-mcp` was envisaged, making this an
MCP triplet; it was dropped per rf2-hvl1g — AI agent access to Xray
state flows via re-frame2-pair-mcp against the framework-published
Xray runtime API, so a dedicated xray-mcp is unnecessary.)

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

Thirteen namespaces under `re-frame.mcp-base.*`. Each ships its own
per-namespace contract doc; the table below indexes them:

| ns | Surface | Per-namespace spec |
|---|---|---|
| `vocab` | `:rf.mcp/*` + `:rf.size/*` marker keys + envelope slots + JSON-RPC error codes. | [`vocab.md`](vocab.md) |
| `sensitive` | spec/009 §Privacy fail-closed default-suppress filter (`sensitive-event?`, `strip-sensitive`, per-frame `scrub-snapshot`) + malformed-stamp counter. | [`sensitive.md`](sensitive.md) |
| `egress` | Cross-MCP `:rf.egress/*` profile vocabulary + pure-data `profile-size-opts` resolver — the framework-runtime-free mirror of the closed six-member egress enum and its `:rf.size/*` floor (EP-0015 §10). | [`egress.md`](egress.md) |
| `elision` | Wire-boundary `:rf.size/large-elided` walker (`count-elided-markers`, rf2-9fz64). | [`elision.md`](elision.md) |
| `args` | Argument coercion helpers (`parse-boolean`, `parse-positive-int`, `fresh-keyword`, `safe-keyword`, `parse-mode`, …). | [`args.md`](args.md) |
| `diff-encode` | Path-keyed structural diff for epoch `:db-after` slots projected into path-headed cluster sections (rf2-1wdzp / rf2-qeous) + encoder/decoder Malli gate. | [`diff-encode.md`](diff-encode.md) |
| `section-grouping` | Patch-list → path-headed cluster sections (`group-patches-into-sections` / `sections->patches`, rf2-qeous); consumed by `diff-encode`. | [`section-grouping.md`](section-grouping.md) |
| `dedup` | Structural-dedup encode step at the wire boundary (`empty-payload?` / `dedup-value`, over `day8/de-dupe`'s equality walk; rf2-ttspi7). Forward direction only — the test-only inverse stays consumer-side. | [`dedup.md`](dedup.md) |
| `overflow` | Overflow-marker payload SHAPE builder (`overflow-payload`) + `token-estimate` + fallback hint (rf2-rvyzy). | [`overflow.md`](overflow.md) |
| `cap` | Wire-boundary two-stage token-budget cap pipeline + `max-tokens` resolver + `ResultIO` protocol (rf2-eyelu / rf2-ih7g4). | [`cap.md`](cap.md) |
| `cursor` | Shared cursor-pagination machinery — base64 codec, opaque encode/decode with `::malformed` recovery, `:limit` clamp, `cursor-stale-result` envelope (rf2-ee38b.19). | [`cursor.md`](cursor.md) |
| `envelope` | Indicator-field `with-indicators` splice (`:dropped-sensitive` / `:elided-large`, omit-when-zero MUST) + wire-bounded `:rf.mcp/*` marker detection (rf2-ee38b.19). | [`envelope.md`](envelope.md) |
| `descriptor-manifest` | Shared MCP tool-descriptor manifest generator + drift-check — deterministic LF-pinned EDN serialiser (`render-edn`) + regenerate-vs-committed `check`, consumed by each server's registry-driven `tool-descriptors.edn` generator (rf2-sofwv). | [`descriptor-manifest.md`](descriptor-manifest.md) |

All `.cljc`, so consumers compile them under their own platform —
re-frame2-pair-mcp's shadow-cljs node build, story-mcp's JVM
classpath. The library's `deps.edn` carries `org.clojure/clojure` plus
the one framework-agnostic external dep `dedup` needs —
`day8/de-dupe`, a pure persistent-data-structure walker (rf2-ttspi7);
no consumer-side transport runtime deps.

## Handler-arity divergence

The two shipped servers use **different registry-handler arities** —
pair-mcp is 3-arity `(fn [conn args extra])`, story-mcp is 1-arity
`(fn [args])`. The divergence is deliberate (pair-mcp needs `conn`
for nREPL and `extra` for streaming; story-mcp is single-process and
needs neither) and is documented in full at
[`handler-arity.md`](handler-arity.md). A future unification awaits a
third server instance and lands as a separate bead.

## What deliberately does NOT live here

The bead's scope holds the line at primitives that are truly
identical across the pair's wire / privacy / size surfaces.
Two categories stay consumer-side:

1. **Wire transport.** story-mcp uses Cheshire for JSON-RPC over
   stdin/stdout; re-frame2-pair-mcp uses the npm `@modelcontextprotocol/sdk`'s
   stdio transport. The framing is different by language; there's
   nothing useful to share here.

2. **Tool registries.** Each MCP server's tool catalogue is domain-
   specific. The base provides building blocks; it does NOT
   prescribe how the registry is shaped.

> **Note (rf2-ee38b.19):** the cursor base64 codec USED to be listed
> here as consumer-side, on the premise that `js/Buffer` vs
> `java.util.Base64` forced a per-platform helper. story-mcp's own
> `.cljc` cursor codec refuted that — the codec lifts cleanly as a
> reader-conditional — so the shared machinery (base64 codec, opaque
> encode/decode + `::malformed` recovery, `:limit` clamp,
> `cursor-stale-result` envelope) now lives in `cursor.cljc`,
> parameterised by each consumer's cursor-payload SHAPE. The cursor
> *resource controls* (concurrent-stream cap, token-bucket rate-limit,
> abuse window) remain consumer-side — they live only in pair-mcp's
> `resource_controls.cljs` and are not yet a candidate to lift (single
> streaming consumer today).

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

2. **It must be framework-runtime-free, with no consumer-side
   transport deps.** The base's `deps.edn` carries `org.clojure/clojure`
   plus the framework-agnostic `day8/de-dupe` walker (rf2-ttspi7). If
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
- **A token-cap algorithm shared with a hypothetical xray-mcp before
  it ships** — NO. Speculative. The rule is "implemented somewhere
  already"; a primitive lifted ahead of a real second consumer earns
  the wrong shape for the consumer that eventually materialises (or
  never materialises). Lift when the second consumer exists.

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
  indicator-field vocabulary — the MUST-level parity between the
  `:dropped-sensitive` and `:elided-large` envelope slots.
- [`/spec/Ownership.md`](../../../spec/Ownership.md) — the row that
  indexes this spec folder under the canonical-homes-outside-`/spec`
  rule.
- [`/spec/README.md` §Canonical homes outside `/spec`](../../../spec/README.md#canonical-homes-outside-spec)
  — the rule that sanctions this folder as an external canonical home.
