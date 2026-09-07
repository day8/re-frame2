# MCP conformance

Client-side conformance for the `re-frame2-pair-mcp` and `story-mcp`
servers. The Node harnesses use the official
`@modelcontextprotocol/sdk` client, so the SDK validates the initialize,
`tools/list`, and `tools/call` envelopes that a real MCP host receives.

This artefact owns 4 cross-server concerns:

- SDK-driven end-to-end workflows in `test/end-to-end-*.cjs`
- live re-frame2-pair workflows in `test/live-re-frame2-pair-*.cjs`,
  with a hermetic shadow-cljs and Chromium orchestrator
- JVM vocabulary gates in [`wire-vocab/`](wire-vocab/)
- the shared naming and token-budget conventions in
  [`NAMING.md`](NAMING.md) and [`TOKEN-BUDGETS.md`](TOKEN-BUDGETS.md)

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
and the register, inspect, preview, run, explain, snapshot-identity and
unregister workflow. It also proves the retired `record-as-variant` tool is
absent and rejected; it does not exercise a live recorder bridge.

`test/end-to-end-project-stories.cjs` starts story-mcp through a
consumer project's own launch alias (`clojure -M:story-mcp` from the
fixture project in `test/fixtures/project-stories/`), whose
`:main-opts` require the project's pre-authored Hicasso-substrate
`.cljc` story namespace before the server takes the stdio loop. With no
write surface open, it proves the first connect discovers, reads, and
runs the project's story, and that a launch requiring a missing
namespace exits non-zero on the ordinary `require` failure instead of
booting over an empty registry.

`test/end-to-end-flag-gates.cjs` owns the cross-server launch-flag
contract that can be exercised without a browser runtime. In particular,
it checks story-mcp's default-closed `--allow-writes` surface, opt-in
behaviour, and removed-flag rejection.

These tests prove MCP and result-envelope behaviour. They do not prove
live runtime semantics that the pair server short-circuits when nREPL is
absent.

### Live pair conformance

The live tests exercise behaviour that requires a connected browser
runtime: overflow, turn-shaped observation (dispatch consequence,
`watch-epochs`, `watch-until`, the recorder read-back), sensitive-value
projection, the universal `isError`/`:ok?` relationship, recordable
coeffects, and event metadata.

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

### Start here: the two repository-root commands

One command runs MCP compatibility, in one of two explicit profiles. Both
run from the repository root and install what they need themselves.

```bash
# default profile — medium cost, no browser
npm --prefix implementation run test:mcp-conformance

# live profile — expensive; adds the hermetic live Pair suite
npm --prefix implementation run test:mcp-conformance -- --live
```

Each run ends on a verdict that names the profile it ran, so a green can
never be mistaken for one whose live layer was skipped. Adding `-- --help`
prints both profiles, their prerequisites, their relative cost and their
exit codes, and exits before installing, compiling, or booting anything.

| Layer | `default` | `--live` |
| --- | --- | --- |
| story-mcp JVM suite | included | included |
| story-mcp stdio roundtrip | included | included |
| re-frame2-pair-mcp `:server-test` | included | included |
| SDK end-to-end: degraded pair, story, flag gates | included | included |
| wire-vocabulary JVM suite | included | included |
| live pair rows (`test/live-re-frame2-pair-*.cjs`) | `SKIP` | run, sentinel-graded |
| hermetic shadow-cljs + Chromium orchestration | omitted | run, cleanup-graded |

Exit codes carry through from the hermetic runner: `1` is a conformance
failure, `2` is an orchestration or cleanup failure where the teardown could
not be proven clean.

`npm test` in this package is narrower than either profile: it is the Node
inventory alone — no JVM suite, no hermetic live suite — with the live rows
reporting `SKIP`.

### Focused diagnostic recipes

The commands below run one layer at a time, for diagnosing a failure the
profiles above have already found.

Install this artefact's Node dependencies first, lockfile-exact — the same
install the repository-level runner performs:

```bash
npm ci
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
command above is the gate that requires them to execute, and
`test:mcp-conformance -- --live` is the repository-level command that
chains it.

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
