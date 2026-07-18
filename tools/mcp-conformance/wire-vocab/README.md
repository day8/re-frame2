# Cross-MCP wire vocabulary

This JVM suite checks the shared vocabulary consumed across the
re-frame2 MCP servers and their framework-facing projections. It is
complementary to the Node SDK tests: Node owns protocol envelopes and
workflows; this suite owns EDN value shapes, canonical names, and source
alignment.

Run it from this directory:

```bash
clojure -M:test
```

## What is pinned

`wire_vocab/schemas.clj` owns the generic wrapper-marker catalogue:

```text
:rf.mcp/overflow
:rf.mcp/summary
:rf.mcp/dedup-table
:rf.mcp/diff-from
:rf.size/large-elided
:rf.mcp/cache-hit
```

Focused namespaces own shapes that are not generic one-key wrappers,
including:

- `:rf.mcp/cursor-stale` result reasons;
- `:rf.mcp/result` tagged results;
- `:rf.mcp/source-uri` editor-jump decorations — the pre-built
  jump-to-definition URI re-frame2-pair-mcp splices beside a
  `:source-coord`; a decoration, not a single-key wrapper (no
  `canonical-markers` schema/fixture), asserted as the legitimate
  additive slot the operating-frame open-map schema must accept;
- `:rf/redacted`;
- progress notifications and event bundles;
- reply-envelope trace rows;
- suppression indicator fields and shared input slots;
- operating-frame addresses and egress profiles;
- tool-name verbs, infinite-trace operations, and trace-catalogue
  duplicate-key linting.

The focused schema stays beside its tests unless more than one namespace
needs it. Shared wrapper schemas and their fixture catalogue stay in
`wire_vocab/schemas.clj`.

## Evidence layers

Not every vocabulary family has a reachable builder on the JVM, so the
suite composes several independent checks:

1. **Schema and fixtures.** Representative emissions must validate
   against one canonical Malli shape.
2. **Live builder calls.** When the canonical encoder or builder is on
   the JVM classpath, the test drives it and validates the emitted value.
3. **Emission-source pins.** Canonical literals must occur as data at the
   contracted emit sites.
4. **Documentation pins.** Where a public server contract owns a literal,
   its source must describe the canonical spelling.
5. **Near-miss rejection.** Snake-case, pluralized, or otherwise
   confusing spellings are rejected in the relevant source inventory.
6. **Cross-encoding pins.** When a Node live test re-encodes a body, the
   JVM suite checks that its assertion names every required field.

Fixtures are evidence, not production owners. A source pin alone proves
only that a literal exists, so builder tests are preferred whenever the
real emitter can be invoked hermetically.

## Inventory ownership

- `wire_vocab/schemas.clj`: shared wrapper schemas and
  `canonical-markers`.
- `wire_vocab/source_pins.clj`: contracted emit/doc source inventories
  and near-miss helpers.
- `fixtures.clj`: repository path resolution, filesystem inventories,
  and comment/string stripping for source scans.
- `wire_vocab_test.clj`: generic wrapper-marker checks and story-mcp's
  uncontracted-marker tripwire.
- focused `*_test.clj` namespaces: non-wrapper vocabulary families.
- each server's `test/fixtures/tool-names.json`: advertised tool names;
  `verb_vocab_test.clj` consumes those files directly.

Do not add a second hand-maintained tool or source inventory to a runner.
Extend the owner above and derive from it.

## Cross-server marker adoption

story-mcp currently emits the shared dedup and overflow wrappers. The
uncontracted-marker tripwire scans its tool sources for other `:rf.mcp/*`
or `:rf.size/*` literals. When story-mcp adopts another shared marker,
the change must register that server in the canonical marker entry and
provide a conforming fixture and source pin. The failure is an adoption
prompt, not evidence that the new marker spelling is acceptable.

## Reply-envelope boundary

`reply_envelope_test.clj` checks the MCP-visible reply facts carried in
trace rows: `:rf.reply/status`, `:rf.reply/work-id`,
`:rf.reply/work-status`, the canonical `:work/id` tuple, and stale
suppression correlation. It validates cross-family fixtures and pins the
production emit sites.

Managed-effect behaviour remains owned by each implementation family and
`implementation/reply-conformance`. This suite checks only that the
shared reply vocabulary reaches MCP trace consumers without renaming or
shape drift.

## Adding vocabulary

For a generic wrapper marker:

1. add its schema and `canonical-markers` entry to
   `wire_vocab/schemas.clj`;
2. include a fixture for every contracted server;
3. extend the emit/doc source inventories when the existing sets do not
   cover its owners; and
4. add a live-builder assertion when the canonical builder is available.

The generic fixture, source, server-coverage, and near-miss sweeps then
apply automatically.

For a non-wrapper family:

1. add a focused `<family>_test.clj` namespace;
2. keep a single-family schema local;
3. cover the applicable evidence layers above; and
4. rely on test-runner discovery rather than registering the namespace
   in another list.

When a gate fails, reconcile the production owner and the canonical
schema together. Updating only a fixture hides the divergence instead of
resolving it.
