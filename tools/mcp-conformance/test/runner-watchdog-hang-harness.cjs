// Child harness for `runner-watchdog.test.cjs`.
//
// Simulates the connect-hang failure mode: an MCP server that SPAWNS and
// then HANGS during the `initialize` handshake — i.e.
// `client.connect(transport)` neither resolves nor rejects. The watchdog
// in `runWithWatchdog` is the only escape from that hang. `activeClient`
// (the watchdog's teardown handle) is published BEFORE the connect await,
// so a connect that hangs still leaves the watchdog a client to tear down
// rather than orphaning the spawned child on the timeout path.
//
// This harness is HERMETIC: it does NOT require the real
// `@modelcontextprotocol/sdk` package. A `Module._load` override
// intercepts the three SDK specifiers `_runner.cjs` requires and
// supplies in-process stubs:
//
//   - `Client.connect()`  returns a never-resolving promise (the hang).
//   - `Client.close()`    resolves, and records that it was called by
//                         printing the marker line below — the assertion
//                         the parent test greps for.
//   - `StdioClientTransport` is a no-op constructor (no real child is
//                         spawned; `stderr` is null so the optional-chain
//                         pipe in `connectServer` is a no-op).
//
// The harness installs the override, requires the real `_runner.cjs`
// (so the production teardown path under test is exercised verbatim),
// and calls `runWithWatchdog` with a short `watchdogMs`. Expected
// behaviour: the watchdog fires, sees `activeClient` (set via the
// `onClient` callback BEFORE connect awaited), calls
// `closeQuietly(activeClient)` which invokes our stub `close()` (printing
// the marker), then `process.exit(2)`. The parent asserts exit code 2
// AND the marker line — i.e. teardown ran before exit.
//
// Were `activeClient` undefined when the watchdog fires, the marker would
// NOT print and the child would be orphaned — which is the regression
// this harness guards against.

'use strict';

const Module = require('node:module');
const path = require('node:path');

// The marker the parent test greps for. Printed from the stub
// `client.close()` — its presence proves teardown ran before exit.
const CLOSE_MARKER = 'HARNESS: client.close() invoked';

// ---------------------------------------------------------------------
// SDK stubs — never-resolving connect, recording close.
// ---------------------------------------------------------------------

class StubClient {
  constructor() {
    this._connected = false;
  }
  // The hang: connect returns a promise that NEVER settles, modelling a
  // server that spawned but stalled mid-`initialize`.
  connect() {
    return new Promise(() => {});
  }
  // Records teardown — the load-bearing observation. Resolves cleanly so
  // `closeQuietly` doesn't take its catch branch.
  async close() {
    process.stdout.write(CLOSE_MARKER + '\n');
  }
}

class StubTransport {
  constructor() {
    // `connectServer` reads `transport.stderr?.on(...)`; null short-
    // circuits the optional chain so no real pipe is wired.
    this.stderr = null;
  }
}

// ---------------------------------------------------------------------
// Module._load override — intercept the three SDK specifiers.
// ---------------------------------------------------------------------

const SDK_STUBS = {
  '@modelcontextprotocol/sdk/client/index.js': { Client: StubClient },
  '@modelcontextprotocol/sdk/client/stdio.js': { StdioClientTransport: StubTransport },
  '@modelcontextprotocol/sdk/types.js': {
    // `_runner.cjs` imports ErrorCode / McpError at module load; the
    // watchdog-hang path never touches them, but they must exist so the
    // require doesn't throw. Minimal stand-ins.
    ErrorCode: { MethodNotFound: -32601, InvalidParams: -32602, InternalError: -32603 },
    McpError: class McpError extends Error {},
  },
};

const originalLoad = Module._load;
Module._load = function patchedLoad(request, parent, isMain) {
  if (Object.prototype.hasOwnProperty.call(SDK_STUBS, request)) {
    return SDK_STUBS[request];
  }
  return originalLoad.call(this, request, parent, isMain);
};

// ---------------------------------------------------------------------
// Drive the real runner with the hang stub.
// ---------------------------------------------------------------------

const { runWithWatchdog } = require(path.join(__dirname, '_runner.cjs'));

runWithWatchdog(
  {
    // Short watchdog so the test completes fast. The hang means connect
    // never resolves, so this fire is the only exit path.
    watchdogMs: 200,
    transportSpec: { command: 'node', args: ['-e', '0'] },
    clientName: 'mcp-conformance-watchdog-hang-harness',
  },
  // `body` is never reached — connect hangs before it runs. If it WERE
  // reached the test would be meaningless, so mark that loudly.
  async () => {
    process.stdout.write('HARNESS: body reached (UNEXPECTED — connect should hang)\n');
  },
);
