# tools/mcp-conformance/wire-vocab

**Cross-MCP wire-vocabulary conformance test.** Source: rf2-j2z7o.

## What this is

The three MCP servers under `tools/` — `pair2-mcp`, `story-mcp`, and
`causa-mcp` (spec-only today) — share a reserved cross-server **wire
vocabulary**: namespaced map keys that an agent recognises identically
across every server it talks to.

```
:rf.mcp/overflow       — token-budget overflow marker
:rf.mcp/summary        — tree-summary lazy-mode marker
:rf.mcp/dedup-table    — structural-dedup wrapper
:rf.mcp/diff-from      — diff-encoded :db-after marker
:rf.size/large-elided  — size-elision wire marker
:rf.elision/at         — size-elision fetch-handle tag
```

Without conformance enforcement, two servers could each ship the
"overflow" concept with slightly different shapes (`:cap-tokens` vs
`:cap_tokens`; `:hint` as a string vs as a keyword vs absent) and an
agent host would have to special-case per server. The cross-server
value proposition collapses if every server invents its own dialect.

This test is the conformance gate. It asserts:

1. **One canonical Malli schema per marker.** The schemas live in
   `wire_vocab_test.clj`, derived from
   [`spec/Spec-Schemas.md` §`:rf/elision-marker`](../../../spec/Spec-Schemas.md),
   [`tools/causa-mcp/spec/Principles.md` §"5 mechanisms"](../../causa-mcp/spec/Principles.md),
   and [`tools/pair2-mcp/src/.../tools.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)
   (pair2-mcp's `overflow-payload`, `tree-summary`, `dedup-value`,
   `diff-encode-db-after`).
2. **Per-server fixtures all conform.** Each marker has a fixture
   representing each server's actual / spec'd emission shape. They
   MUST validate against the same schema.
3. **Source-text vocabulary pin.** A grep against each server's source
   (`pair2-mcp/src/`) and spec (`causa-mcp/spec/`) asserts the
   canonical literal appears, and asserts that no near-miss spelling
   (snake_case, pluralised, namespace-with-underscores) appears.
4. **story-mcp absence tripwire.** story-mcp does NOT currently emit
   any of the cross-MCP markers — it uses its own `:rf.story/*` /
   `:rf.assert/*` / `:rf.error/*` vocabularies. A test asserts that
   absence so that the day story-mcp adopts a marker, the test fails
   loud and forces the reviewer to register a fixture and a source
   file (rather than diverge silently).

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

The sibling `tools/mcp-conformance/test/end-to-end-*.js` files drive
each server through the official MCP SDK client (handshake +
`tools/list` + `tools/call` against a live process). That's *protocol*
conformance.

This test is *vocabulary* conformance — the shapes of EDN values a
server emits as response payloads. It does NOT need a live server: the
schemas are normative, the fixtures are authored from each server's
spec/source, and the grep step pins those authored fixtures to the
actual source/spec text. Two complementary gates; one wire.

## causa-mcp impl gap

causa-mcp's implementation has not landed yet (its `tools/causa-mcp/`
tree contains `README.md` + `spec/` but no `src/`). The fixtures and
the spec-grep step against `tools/causa-mcp/spec/Principles.md` cover
the vocabulary today. When the impl lands, a follow-up bead extends
this test to:

- Grep `tools/causa-mcp/src/` for the canonical literals.
- Add live-emission fixtures if their actual emitted shape diverges
  from the spec snippets.

## When this test fails

| Failing assertion | Likely cause | Fix |
|---|---|---|
| Fixture doesn't validate against schema | Server (or spec) changed the marker body shape | Update both the schema AND the relevant fixture; the divergence is the bug |
| Literal missing from server source | Marker was renamed in one server | Pick the canonical form, rename the other server, update the schema |
| Near-miss variant present | Vocabulary drift (snake_case spelling crept in) | Rename back to the canonical form |
| story-mcp absence tripwire fires | story-mcp now emits a cross-MCP marker | Add story-mcp to the marker's `:servers` set, add a fixture, extend `server-source-files` |
