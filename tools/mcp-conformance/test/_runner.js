// Shared Node-side test runner for MCP-conformance harnesses.
//
// Every `tools/mcp-conformance/test/end-to-end-*.js` (and the
// sibling `live-pair2-overflow.js`) repeats the same ceremony:
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
// `runWithWatchdog({ watchdogMs, transportSpec, clientName, clientVersion }, body)`
//
//   - `watchdogMs`     — ms before the hard-cap `process.exit(2)` fires.
//   - `transportSpec`  — `{ command, args, cwd, env }` forwarded to
//                        `StdioClientTransport` (with `stderr: 'pipe'`
//                        spliced in automatically).
//   - `clientName`     — the SDK `Client` `name` slot.
//   - `clientVersion`  — the SDK `Client` `version` slot (default
//                        `'0.1.0'` — matches the historical value the
//                        three concrete tests used).
//   - `body`           — `async (client, transport) => void`. Throws on
//                        any assertion failure; the runner reports the
//                        message + stack and exits 1.
//
// `runWithWatchdog` performs the SDK connect handshake itself (every
// historical caller did it as the first line of the body) and passes
// the connected `client` + `transport` to `body`. Tests that want to
// skip (no-op exit 0) call `process.exit(0)` directly BEFORE invoking
// this function — see `live-pair2-overflow.js`'s `isSkip()` branch.
//
// The runner returns nothing; it always terminates the process via
// `process.exit`. Callers that pre-flight skip MUST exit themselves.

const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');

async function runWithWatchdog(
  { watchdogMs, transportSpec, clientName, clientVersion = '0.1.0' },
  body,
) {
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

  const client = new Client({ name: clientName, version: clientVersion }, { capabilities: {} });

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

module.exports = { runWithWatchdog };
