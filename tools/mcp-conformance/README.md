# MCP conformance

Client-side conformance for the `re-frame2-pair-mcp` and `story-mcp`
servers. The Node harnesses use the official
`@modelcontextprotocol/sdk` client, so the SDK validates the initialize,
`tools/list`, and `tools/call` envelopes that a real MCP host receives.

This artefact owns four cross-server concerns:

1. SDK-driven end-to-end workflows in `test/end-to-end-*.cjs`.
2. Live re-frame2-pair workflows in `test/live-re-frame2-pair-*.cjs`,
   with a hermetic shadow-cljs and Chromium orchestrator.
3. JVM vocabulary gates in [`wire-vocab/`](wire-vocab/).
4. The shared naming and token-budget conventions in
   [`NAMING.md`](NAMING.md) and [`TOKEN-BUDGETS.md`](TOKEN-BUDGETS.md).

Server unit tests still own tool implementation details. This artefact
owns the client-observable protocol, shared vocabulary, and the seams
between the servers.

## Conformance layers

### Degraded end to end

`test/end-to-end-re-frame2-pair.cjs` starts the pair server without a
live nREPL. It checks the SDK handshake, the canonical tool catalogue,
descriptor metadata, every advertised tool's `callTool()` envelope, the
pre-connection write refusals, and JSON-RPC error codes.

`test/end-to-end-story.cjs` starts story-mcp with writes enabled. It
checks the catalogue and descriptors, then drives the closed-world reads
and the complete register, inspect, preview, run, record, and unregister
workflow.

`test/end-to-end-flag-gates.cjs` owns the cross-server launch-flag
contract that can be exercised without a browser runtime. In particular,
it checks story-mcp's default-closed `--allow-writes` surface, opt-in
behaviour, and removed-flag rejection.

These tests prove MCP and result-envelope behaviour. They do not prove
live runtime semantics that the pair server short-circuits when nREPL is
absent.

### Live pair conformance

The live tests exercise behaviour that requires a connected browser
runtime: overflow, progress notifications, sensitive-value projection,
the universal `isError`/`:ok?` relationship, recordable coeffects, and
event metadata.

[`scripts/live-test-inventory.cjs`](scripts/live-test-inventory.cjs) is
the single owner of the live-test roster. Both `npm test` and the
hermetic runner derive their rows from it. The disk-completeness test
fails if a `live-re-frame2-pair-*.cjs` file is not registered.

Each live test skips cleanly when `SHADOW_CLJS_NREPL_PORT` is absent.
[`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`](scripts/run-re-frame2-pair-live-hermetic-suite.cjs)
boots the fixture, supplies the port, launches Chromium, and runs the
entire roster. It grades a test only after the child closes and requires
both a zero exit code and that test's success sentinel. A skipped inner
test therefore cannot pass the hermetic suite.

The runner owns cleanup for Chromium and shadow-cljs. Cleanup is
idempotent, bounded, and awaited on normal completion; watchdog and
signal paths allow a short cleanup window before forcing exit.

### Wire vocabulary

[`wire-vocab/README.md`](wire-vocab/README.md) describes the JVM suite.
It pins canonical schemas, representative fixtures, live builders where
they are available on the JVM, source literals, and rejected near-miss
spellings. It also owns the executable tool-verb linter against each
server's canonical `tool-names.json` fixture.

## Shared ratchets

The end-to-end workflows use helpers from `test/_runner.cjs`:

- `assertDescriptorShape` checks the common descriptor envelope.
- `assertClassificationRatchet` compares every live descriptor with the
  server classification fixture. The fixture key set must exactly match
  the advertised catalogue.
- `trackCallCoverage` and `assertCallCoverageRatchet` require every
  advertised tool to be SDK-called or named in a reviewed exclusion map
  with a non-empty alternative-coverage rationale. Stale, contradictory,
  and blank exclusions fail.
- `assertIsErrorMatchesOk` requires structured `:ok? false` results to
  set `isError`, and structured `:ok? true` results not to set it.
- `structured` expands `:rf.mcp/dedup-table` before semantic assertions,
  matching the payload a decoding client consumes.

The canonical tool-name fixtures remain server-owned:

- `tools/re-frame2-pair-mcp/test/fixtures/tool-names.json`
- `tools/story-mcp/test/fixtures/tool-names.json`

Do not copy those inventories into this artefact. The workflows load the
fixtures directly, and the coverage ratchet compares calls against the
loaded names.

## Running the gates

Install this artefact's Node dependencies first:

```bash
npm install
```

The focused, server-free Node gate is:

```bash
npm run test:unit
```

The pair end-to-end test needs the server bundle:

```bash
cd ../re-frame2-pair-mcp
npx shadow-cljs compile server
cd ../mcp-conformance
npm run test:re-frame2-pair
```

Story gates need `clojure` on `PATH`:

```bash
npm run test:story
npm run test:flag-gates
```

The hermetic live suite also needs the pair bundle and Playwright. It
installs the fixture's own Node dependencies when its `node_modules`
directory is absent:

```bash
npm run test:re-frame2-pair-live-hermetic-suite
```

Run the JVM vocabulary suite separately:

```bash
cd wire-vocab
clojure -M:test
```

`npm test` runs the authoritative Node inventory sequentially and fails
fast. Live rows report `SKIP` unless a port is supplied; the hermetic
command above is the gate that requires them to execute.

## Inventory ownership

- `scripts/test-all.cjs` owns the overall Node inventory.
- `scripts/test-unit.cjs` derives all `node --test` rows from that
  inventory and runs them in one process.
- `scripts/live-test-inventory.cjs` owns the live pair inventory.
- Each server's `tool-names.json` owns its advertised tool names.
- `test/fixtures/*-classifications.json` owns the expected descriptor
  classification partition.
- `wire-vocab/test/re_frame/mcp_conformance/wire_vocab/schemas.clj`
  owns shared wrapper-marker schemas.

When adding a test, update its owning inventory rather than another
runner. When adding a tool, update the server's canonical name fixture;
the conformance and vocabulary gates consume it directly.

## Contract ownership

This artefact deliberately has no local `spec/` directory. Per-tool
input, output, and error contracts live in each server's `spec/`
directory. Cross-server contracts live in `NAMING.md`,
`TOKEN-BUDGETS.md`, and `wire-vocab/README.md`; executable constraints
live in this test corpus. A local spec would duplicate those owners.
