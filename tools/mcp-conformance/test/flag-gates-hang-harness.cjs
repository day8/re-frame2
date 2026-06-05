// Child harness for `runner-watchdog.test.cjs` (rf2-wqi4n4 finding 1).
//
// Pins the bare-`connectServer` connect-hang teardown contract in
// `end-to-end-flag-gates.cjs`. That module does NOT use `runWithWatchdog`
// (it needs three fresh in-process JVM boots, which the single-boot runner
// can't serve); it runs its OWN module-scope watchdog over an
// `activeFlagGateClient` handle. Pre-fix that handle was assigned only AFTER
// `await connectServer(...)` RESOLVED — so a story-mcp JVM that spawns and
// then HANGS mid-`initialize` (connect never resolving) left
// `activeFlagGateClient === null` when the watchdog fired, and the
// watchdog's `else` branch `process.exit(2)`'d WITHOUT tearing the hung JVM
// down. The fix publishes the handle via `connectServer`'s `onClient`
// callback BEFORE connect awaits.
//
// This harness is HERMETIC: it stubs the SDK so the first (and only)
// `connect()` NEVER resolves, points `$STORY_MCP_CMD` at the running node
// binary (so `resolveTrustedExe` is bypassed — no `clojure` on PATH
// needed), and shortens the module-scope watchdog to 200ms via
// `$FLAG_GATE_WATCHDOG_MS`. The stub `close()` prints a marker; its
// presence in the child output proves the watchdog had a teardown handle
// for the hung JVM (i.e. `activeFlagGateClient` was set BEFORE connect).

'use strict';

const Module = require('node:module');
const path = require('node:path');

const CLOSE_MARKER = 'HARNESS: flag-gate client.close() invoked';

class StubClient {
  // The hang: connect never settles — a story-mcp JVM that spawned but
  // stalled mid-`initialize`.
  connect() {
    return new Promise(() => {});
  }
  async close() {
    process.stdout.write(CLOSE_MARKER + '\n');
  }
}

class StubTransport {
  constructor() {
    this.stderr = null;
  }
}

const SDK_STUBS = {
  '@modelcontextprotocol/sdk/client/index.js': { Client: StubClient },
  '@modelcontextprotocol/sdk/client/stdio.js': { StdioClientTransport: StubTransport },
  '@modelcontextprotocol/sdk/types.js': {
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

// Bypass `resolveTrustedExe('clojure')` (no real toolchain in the hermetic
// child) and shorten the module-scope watchdog. Set BEFORE requiring the
// module under test — it reads both at module-load time.
process.env.STORY_MCP_CMD = process.execPath;
process.env.FLAG_GATE_WATCHDOG_MS = '200';

// Requiring the module runs its `main()` (the three gate probes). The first
// `withStoryServer` boot's connect hangs, so `main()` never progresses; the
// 200ms module-scope watchdog is the only exit path. With the fix it tears
// the (stubbed) hung JVM client down — printing CLOSE_MARKER — before
// `process.exit(2)`.
require(path.join(__dirname, 'end-to-end-flag-gates.cjs'));
