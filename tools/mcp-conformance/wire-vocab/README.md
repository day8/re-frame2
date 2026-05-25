# tools/mcp-conformance/wire-vocab

**Cross-MCP wire-vocabulary conformance test.** Source: rf2-j2z7o.

## What this is

The MCP servers under `tools/` — `re-frame2-pair-mcp` and `story-mcp` —
share a reserved cross-server **wire vocabulary**: namespaced map keys
that an agent recognises identically across every server it talks to.

(Historical: a third server `xray-mcp` was envisaged in the vocabulary;
it was dropped per rf2-hvl1g — AI agent access to Xray state flows via
`re-frame2-pair-mcp` against the framework-published Xray runtime API.
A prior false-start drop was tracked under rf2-bu21t; rf2-hvl1g is the
final close-out.)

There are **six top-level wire markers** (the `canonical-markers` table
in `wire_vocab_test.clj` is the source of truth) plus one embedded
fetch-handle tag (`:rf.elision/at`, pinned inside the
`:rf.size/large-elided` body's `:handle` slot — not a standalone marker
an agent encounters at the top level of a payload):

```
:rf.mcp/overflow       — token-budget overflow marker
:rf.mcp/summary        — tree-summary lazy-mode marker
:rf.mcp/dedup-table    — structural-dedup wrapper
:rf.mcp/diff-from      — diff-encoded :db-after marker
:rf.size/large-elided  — size-elision wire marker
:rf.mcp/cache-hit      — per-session response-cache hit marker
:rf.elision/at         — embedded fetch-handle tag inside the
                         :rf.size/large-elided body's :handle slot
                         (pinned via ElisionMarkerBody, not a
                         standalone top-level marker)
```

Without conformance enforcement, two servers could each ship the
"overflow" concept with slightly different shapes (`:cap-tokens` vs
`:cap_tokens`; `:hint` as a string vs as a keyword vs absent) and an
agent host would have to special-case per server. The cross-server
value proposition collapses if every server invents its own dialect.

This test is the conformance gate. It asserts:

1. **One canonical Malli schema per marker.** The schemas live in
   `wire_vocab_test.clj`, derived from
   [`spec/Spec-Schemas.md` §`:rf/elision-marker`](../../../spec/Spec-Schemas.md)
   and [`tools/re-frame2-pair-mcp/src/.../tools.cljs`](../../re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools.cljs)
   (re-frame2-pair-mcp's `overflow-payload`, `tree-summary`, `dedup-value`,
   `diff-encode-db-after`).
2. **Per-server fixtures all conform.** Each marker has a fixture
   representing each server's actual / spec'd emission shape. They
   MUST validate against the same schema.
3. **Source-text vocabulary pin.** A grep against each server's source
   (`re-frame2-pair-mcp/src/`) asserts the canonical literal appears,
   and asserts that no near-miss spelling (snake_case, pluralised,
   namespace-with-underscores) appears.
4. **story-mcp uncontracted-marker tripwire.** story-mcp emits one
   cross-MCP marker today — `:rf.mcp/dedup-table` (rf2-90eft, mirror
   of pair-mcp's rf2-obpa9 wire-boundary structural dedup) — and
   otherwise uses its own `:rf.story/*` / `:rf.assert/*` /
   `:rf.error/*` vocabularies. A test asserts that NO UNCONTRACTED
   cross-MCP marker leaks into story-mcp source, so the day it adopts
   another marker the test fails loud and forces the reviewer to
   register a fixture and a source file (rather than diverge silently).

## Files

- `deps.edn` — pure JVM Clojure, one dep: `metosin/malli`.
- `test/re_frame/mcp_conformance/wire_vocab_test.clj` — the test.

## How to run

From this directory:

```bash
clojure -M:test
```

The test:

- Resolves the repo root from `*file*` so it works from any CWD.
- Reads each server's source/spec via `slurp`.
- Validates each fixture against its canonical schema via
  `malli.core/validate`.
- Greps for canonical and near-miss spellings.

Total run time on a cold JVM: ~3-5 seconds.

## Why JVM (not Node SDK)

The sibling `tools/mcp-conformance/test/end-to-end-*.cjs` files drive
each server through the official MCP SDK client (handshake +
`tools/list` + `tools/call` against a live process). That's *protocol*
conformance.

This test is *vocabulary* conformance — the shapes of EDN values a
server emits as response payloads. It does NOT need a live server: the
schemas are normative, the fixtures are authored from each server's
spec/source, and the grep step pins those authored fixtures to the
actual source/spec text. Two complementary gates; one wire.

## When this test fails

| Failing assertion | Likely cause | Fix |
|---|---|---|
| Fixture doesn't validate against schema | Server (or spec) changed the marker body shape | Update both the schema AND the relevant fixture; the divergence is the bug |
| Literal missing from server source | Marker was renamed in one server | Pick the canonical form, rename the other server, update the schema |
| Near-miss variant present | Vocabulary drift (snake_case spelling crept in) | Rename back to the canonical form |
| story-mcp uncontracted-marker tripwire fires | story-mcp now emits a NEW cross-MCP marker | Add story-mcp to the marker's `:servers` set, add a fixture, extend `server-source-files` |
