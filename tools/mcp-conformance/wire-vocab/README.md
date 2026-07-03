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
   `wire_vocab/schemas.clj` (the cross-family ones) or co-located with
   their focused test namespace (the marker-family-local ones), derived from
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
4. **story-mcp uncontracted-marker tripwire.** story-mcp emits two
   cross-MCP markers today — `:rf.mcp/dedup-table` (rf2-90eft, mirror
   of pair-mcp's rf2-obpa9 wire-boundary structural dedup) and
   `:rf.mcp/overflow` (rf2-yxgcsz, its wire-boundary token-cap, sharing
   pair-mcp's `mcp-base.cap/apply-cap` builder) — and otherwise uses its
   own `:rf.story/*` / `:rf.assert/*` / `:rf.error/*` vocabularies. A
   test asserts that NO UNCONTRACTED cross-MCP marker leaks into
   story-mcp source, so the day it adopts another marker the test fails
   loud and forces the reviewer to register a fixture and a source file
   (rather than diverge silently).

## Files

- `deps.edn` — pure JVM Clojure, one dep: `metosin/malli`.
- `test/re_frame/mcp_conformance/wire_vocab/schemas.clj` — the canonical
  cross-MCP marker schemas + the `canonical-markers` catalogue (the
  contract data shared across test namespaces). Split out by rf2-7ckmwx.
- `test/re_frame/mcp_conformance/wire_vocab/source_pins.clj` — the shared
  source-text pin inventories (`emit-source-files` / `doc-source-files` /
  `all-source-files`) + the `marker-key->literal` / `near-miss-variants`
  helpers. Split out by rf2-7ckmwx.
- `test/re_frame/mcp_conformance/fixtures.clj` — classpath-walk + slurp
  ceremony + the filesystem-derived story-mcp source inventory + the
  `strip-comments-and-strings` documentation-vs-emission discriminator.
- `test/re_frame/mcp_conformance/wire_vocab_test.clj` — the CORE
  wrapper-marker contract: fixture-conformance over `canonical-markers`,
  the per-marker negative/live-emission gates, the marker-literal source
  pins, the JS cross-encoding pin, server-coverage, the story-mcp
  inventory tripwires, and the envelope indicator-field slots.
- Focused per-family test namespaces (the NON-wrapper markers, split out
  by rf2-7ckmwx): `cursor_stale_test.clj`, `result_envelope_test.clj`,
  `redacted_sentinel_test.clj`, `progress_notification_test.clj`,
  `event_bundle_test.clj`. Each requires the shared `schemas` +
  `source-pins` support nses; a marker-family-LOCAL schema
  (`ResultEnvelope`, `EventBundle`) stays co-located with its tests.
- `indicator_field_test.clj`, `slot_name_test.clj`, `verb_vocab_test.clj`
  — independent cross-MCP vocabulary surfaces (pre-existing).
- `reply_envelope_test.clj` — the EP-0011 reply-envelope TRACE-egress
  vocabulary gate (rf2-mrfvg2). See §EP-0011 coverage boundary below.

## EP-0011 reply-envelope coverage boundary (rf2-mrfvg2)

`reply_envelope_test.clj` pins the additive `:rf.reply/*` trace vocabulary
an MCP consumer reads off a `trace-window` / event-bundle `:trace-events`
reply-envelope row — the EP-0011 uniform reply envelope is named among
Tool-Pair's record-shaped off-box egress
([`spec/Tool-Pair.md`](../../re-frame2-pair-mcp/spec) §`project-egress`),
and Managed-Effects property 9 requires managed-async families to emit trace
rows FROM reply-envelope facts. Before this gate, `tools/mcp-conformance`
validated only the MCP wrapper / event-bundle envelope + a counter dispatch — no
managed-async reply-envelope trace CONTENT.

It pins, in the same schema + fixture + source-pin + near-miss shape as the
wrapper markers above:

- a canonical Malli schema (`ReplyEnvelopeTraceRow`) for the MCP-visible
  reply-envelope trace row: the additive `:rf.reply/status` /
  `:rf.reply/work-id` / `:rf.reply/work-status` keys, the canonical bare
  `:work/id` join key (which MUST equal `:rf.reply/work-id`), and the
  carried/current stale-suppression correlation gate;
- fixtures matching the REAL production emissions (HTTP
  `:rf.http/stale-suppressed`, resource `:rf.resource/stale-suppressed`,
  machine), so a tag-map drift trips the gate;
- a SOURCE-text pin that the additive `:rf.reply/*` keys appear as DATA in
  the production emit sites (HTTP transport, resources events, machine
  transition, routing nav-token) — so a substrate / family rename trips the
  gate even though the literals live in `implementation/`, not in
  re-frame2-pair-mcp;
- a near-miss anti-pin so a snake_case / pluralised / predicate spelling
  fails;
- schema fail-closed checks for a scalar (non-tuple) work-id, an
  off-vocabulary status / work-status, and a dropped required reply key.

**What it does NOT cover (complementarity, not duplication):** it does not
re-validate the whole managed-effect suite. Family-internal correctness +
the runtime trace emission live in the per-family `*-reply-lowering-*` suites
and the resources / machines runtime tests; the cross-family reply
VOCABULARY consistency lives in `implementation/reply-conformance`; the
EP-0015 reply egress projection lives there too; Xray's consumer-side reply
panel is `tools/xray`. This gate is narrowly the MCP-egress-visible contract
that those `:rf.reply/*` keys reach a `trace-window` / `subscribe` consumer
un-renamed. The live `subscribe` end-to-end path
(`test/live-re-frame2-pair-subscribe.cjs`) is the runtime counterpart; this
JVM gate is the cheap, hermetic vocabulary pin.

## Adding a new cross-MCP marker — which files to touch

For a **wrapper-shaped** marker (`{<key> <body>}` — the agent walks the
body):

1. Add the canonical Malli schema to `wire_vocab/schemas.clj`.
2. Add a `canonical-markers` entry there: `:key` + `:schema` +
   per-server `:fixtures` + `:servers`.
3. If the literal's source-of-truth home or doc-source differs from the
   existing markers, extend the inventories in `wire_vocab/source_pins.clj`.

The generic fixture-conformance + source-pin + near-miss sweeps in
`wire_vocab_test.clj` then cover it automatically.

For a **non-wrapper** marker (a `:reason` value like `:rf.mcp/cursor-stale`,
a bare scalar like `:rf/redacted`, a tagged-union like `:rf.mcp/result`,
or a streaming-notification shape):

1. Create a focused `<marker>_test.clj` namespace (use the existing
   `cursor_stale_test.clj` / `result_envelope_test.clj` /
   `redacted_sentinel_test.clj` / `progress_notification_test.clj` /
   `event_bundle_test.clj` as templates), requiring the shared
   `wire_vocab.schemas` + `wire_vocab.source-pins` support nses.
2. Keep the schema co-located in that test namespace when it is
   referenced only by that family; promote it to `schemas.clj` only if a
   second namespace needs it.
3. Cover all five conformance layers the family templates show: schema +
   fixture, live-emission gate (when a JVM-reachable builder exists),
   source-text emit/doc pin, JS cross-encoding pin (when a live `.cjs`
   harness re-encodes the shape), and the near-miss anti-pin.

New `*_test.clj` namespaces are auto-discovered by the cognitect
test-runner — no registration needed.

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
