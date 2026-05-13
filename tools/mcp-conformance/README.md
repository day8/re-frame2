# tools/mcp-conformance

End-to-end **MCP-client** conformance harness for the re-frame2 MCP
servers — `pair2-mcp`, `story-mcp`, and (when its implementation lands)
`causa-mcp`. Source: rf2-cum40.

## What this is

Every existing test surface for these servers is **server-side**:

- per-tool unit tests (under each server artefact's `test/`)
- `stdio-roundtrip.js` — a hand-rolled JSON-RPC wire-format probe
- `live-{nrepl,server}.js` — workflow tests against a running runtime

None of these drive a server from the **client** side of MCP — they all
either hand-roll JSON-RPC framing or skip the protocol layer entirely.
That leaves a gap: when a real MCP-aware consumer (Claude Code,
Continue, etc.) talks to one of these servers, it goes through the
official `@modelcontextprotocol/sdk` `Client`, which validates **every**
response against the spec's Zod schemas (`InitializeResultSchema`,
`ListToolsResultSchema`, `CallToolResultSchema`, …). A server response
that's "close enough" to the spec to fool a hand-rolled probe can still
fail the SDK's parse step, and that bug ships unobserved until a real
consumer attaches.

This harness closes that gap. It spawns each server through the SDK's
`StdioClientTransport`, completes the full `Client.connect()`
handshake, walks one canonical workflow per server using `listTools()`
and `callTool()`, and tears the transport down cleanly. Any spec drift
on the server side surfaces as an SDK parse-error.

## Files

- `package.json` — depends on `@modelcontextprotocol/sdk` only
- `test/end-to-end-pair2.js` — pair2-mcp conformance (degraded mode,
  no nREPL needed — same shape as pair2-mcp's stdio-roundtrip)
- `test/end-to-end-story.js` — story-mcp conformance (full write-loop
  with `--allow-writes` enabled)
- `test/end-to-end-causa.js` — placeholder; exits 0 with a `SKIP`
  marker until causa-mcp's server implementation lands

## How to run

From this directory:

```bash
npm install

# pair2-mcp: requires the server bundle on disk at
# ../pair2-mcp/out/server.js. Build it first with:
#   cd ../pair2-mcp && npx shadow-cljs compile server
npm run test:pair2

# story-mcp: requires `clojure` on PATH (override via
# $STORY_MCP_CMD if non-default). No pre-build step — the server is
# launched via `clojure -M -m re-frame.story-mcp.server`.
npm run test:story

# causa-mcp: currently a SKIPPED no-op (see comment in the file).
npm run test:causa

# All three (pair2 must be pre-built):
npm test
```

## What each test covers

### `end-to-end-pair2.js`

1. Connect — full SDK handshake against the freshly spawned bundle
2. `tools/list` — confirm the ten advertised tools match the pinned
   catalogue
3. Spot-check every descriptor carries an `inputSchema`
4. Walk degraded-mode `dispatch` / `watch-epochs` / `snapshot` /
   `subscribe` — each call routes through the SDK's
   `CallToolResultSchema` parse step
5. Clean `Client.close()`

Runs without an nREPL on `$SHADOW_CLJS_NREPL_PORT`, so it's
self-contained and reproducible.

### `end-to-end-story.js`

1. Connect — `clojure -M -m re-frame.story-mcp.server --allow-writes`
2. `tools/list` — confirm the 17 advertised tools
3. Spot-check every descriptor carries an `inputSchema`
4. `register-variant` → `run-variant` (vacuous pass) →
   `read-failures` (total=0) → `unregister-variant` + verify
5. Clean `Client.close()`

Watchdog: 90s (cold JVM boot is ~10–30s on a CI runner).

### `end-to-end-causa.js`

Placeholder — exits 0 with a `SKIP` marker. Will be filled in when
the causa-mcp server implementation lands; the file's body comment
documents the expected shape.

## CI

`.github/workflows/test.yml` runs each of the three scripts in its own
`mcp-conformance-{pair2,story,causa}` job, parallel to the existing
`node-test-tools-{pair2,story}-mcp` jobs. Same Node 24 + JDK 21 setup
as those jobs.

## Why a separate artefact?

The harness only depends on `@modelcontextprotocol/sdk` and Node's
stdlib. Putting it under each server's `test/` would duplicate the
dependency and tie the client-side fixture to each server's
build/dependency graph. A standalone artefact keeps the conformance
contract in one place and makes "add an MCP server, add a conformance
script" a one-file change.

The artefact is bundle-isolated from production builds by construction
(it's pure Node-side test fixtures; no CLJS sources).
