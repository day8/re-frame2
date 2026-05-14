// Shared Node-side test runner for MCP-conformance harnesses.
//
// Every `tools/mcp-conformance/test/end-to-end-*.cjs` (and the
// sibling `live-pair2-overflow.cjs`) repeats the same ceremony:
//
//   - construct a `StdioClientTransport` with `stderr: 'pipe'`
//   - pipe the child's stderr to ours with a `[server]` prefix
//   - construct an SDK `Client` with a `mcp-conformance-*` identity
//   - install a watchdog `setTimeout` so a hung child can't wedge CI
//   - on success: tear down the transport, clear the watchdog,
//     `process.exit(0)`
//   - on error: best-effort `client.close()`, clear the watchdog,
//     print the failure, `process.exit(1)`
//   - on watchdog: print the timeout, `process.exit(2)`
//
// Source: rf2-sems4. The split was originally three near-identical
// ~40-LoC blocks of pure framing code; centralising the framing here
// shrinks each test body to the workflow steps that actually differ.
// Adding a fourth Node-side conformance test (causa-mcp impl, a third
// pair2 variant) becomes ~30 LoC instead of ~110.
//
// ## Contract
//
// `runWithWatchdog({ watchdogMs, transportSpec, clientName }, body)`
//
//   - `watchdogMs`     — ms before the hard-cap `process.exit(2)` fires.
//   - `transportSpec`  — `{ command, args, cwd, env }` forwarded to
//                        `StdioClientTransport` (with `stderr: 'pipe'`
//                        spliced in automatically).
//   - `clientName`     — the SDK `Client` `name` slot. Every conformance
//                        test uses a `mcp-conformance-<server>` identity.
//   - `body`           — `async (client, transport) => void`. Throws on
//                        any assertion failure; the runner reports the
//                        message + stack and exits 1.
//
// The SDK `Client.version` slot is pinned at `'0.1.0'` for every
// conformance harness — none of the callers override it and the
// server's `initialize` handler treats client version as informational.
// Promoting it to a parameter would be slot-soup (rf2-i3ffz F-GAP-6).
//
// `runWithWatchdog` performs the SDK connect handshake itself (every
// historical caller did it as the first line of the body) and passes
// the connected `client` + `transport` to `body`. Tests that want to
// skip (no-op exit 0) call `runWithWatchdog.skip(reason)` BEFORE
// invoking this function — that prints a uniform `SKIP <reason>`
// banner and exits 0 without spawning a child or installing a
// watchdog.
//
// The runner returns nothing; it always terminates the process via
// `process.exit`. Callers that pre-flight skip MUST exit themselves
// (via `runWithWatchdog.skip`).

const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');

const CLIENT_VERSION = '0.1.0';

async function runWithWatchdog({ watchdogMs, transportSpec, clientName }, body) {
  // Install the watchdog FIRST so even a hang inside transport
  // construction (rare but possible: bad cwd, env corruption) gets
  // killed. The active timer is cleared on every exit path below.
  const watchdog = setTimeout(() => {
    console.error('FAIL: watchdog timeout (' + watchdogMs + 'ms)');
    process.exit(2);
  }, watchdogMs);

  // `stderr: 'pipe'` is non-negotiable — every conformance test pipes
  // the server's stderr so CI logs surface the server-side debug
  // output when a step fails. Splice it in here rather than asking
  // the caller to remember it.
  const transport = new StdioClientTransport({ ...transportSpec, stderr: 'pipe' });

  const client = new Client({ name: clientName, version: CLIENT_VERSION }, { capabilities: {} });

  // Pipe the child's stderr to ours with a `[server]` prefix. Same
  // shape every historical caller used; the closure captures the
  // transport reference cleanly.
  transport.stderr?.on('data', (d) => process.stderr.write('[server] ' + d.toString()));

  try {
    // Initialize handshake. SDK validates the result against
    // InitializeResultSchema; a malformed envelope throws here.
    await client.connect(transport);
    await body(client, transport);
    // Success — tear down the transport explicitly. The SDK's
    // `client.close()` kills the child process; without it CI would
    // wait for the watchdog.
    await client.close();
    clearTimeout(watchdog);
    process.exit(0);
  } catch (err) {
    // Best-effort teardown. If `client.close()` itself throws (e.g.
    // because the transport already died), swallow that — the
    // original error is the load-bearing signal.
    try {
      await client.close();
    } catch (_e) {
      // best-effort
    }
    clearTimeout(watchdog);
    console.error('FAIL:', err.message);
    if (err.stack) console.error(err.stack);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------
// Pre-flight skip helper (rf2-i3ffz F-HYG-2).
//
// Conformance tests gate on environment / server-implementation status
// (`live-pair2-overflow.cjs` requires `$SHADOW_CLJS_NREPL_PORT`;
// `end-to-end-causa.cjs` is a placeholder until causa-mcp's
// implementation lands). Skipping cleanly means exiting 0 BEFORE
// spawning a child or installing the watchdog — same pattern across
// every caller. Centralising the SKIP banner + exit here makes the
// caller a one-liner and pins the marker shape (`SKIP <reason>`) so a
// CI step that greps for it doesn't drift per-test.
// ---------------------------------------------------------------------
runWithWatchdog.skip = function skip(reason) {
  console.log('SKIP ' + reason);
  process.exit(0);
};

module.exports = { runWithWatchdog };
