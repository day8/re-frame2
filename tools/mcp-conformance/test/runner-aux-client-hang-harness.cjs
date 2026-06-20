// Child harness for `runner-watchdog.test.cjs`.
//
// Simulates the SECONDARY-client orphan the redaction live test could hit:
// `runWithWatchdog` boots its primary client fine, but the body then boots
// an ADDITIONAL in-process server (the inner-projection / gate-ON arms in
// `live-re-frame2-pair-redaction.cjs`) whose connect HANGS during the
// `initialize` handshake. Each secondary client registers with the outer
// watchdog (via `registerAuxClient`, fired from a bare `connectServer`'s
// `onClient` BEFORE connect awaits), so the watchdog drains the primary AND
// every secondary on a timeout — a hang in (or after) a secondary boot is
// reachable by the timeout path rather than orphaning the secondary child
// despite the harness reporting a watchdog timeout.
//
// This harness is HERMETIC: it stubs the three SDK specifiers `_runner.cjs`
// requires. The PRIMARY connect resolves cleanly (so the body runs and gets
// to boot the secondary); the SECONDARY connect NEVER resolves (the hang).
// Each stub `close()` prints a distinct marker so the parent test can prove
// BOTH clients were torn down before exit.

'use strict';

const Module = require('node:module');
const path = require('node:path');

const PRIMARY_CLOSE_MARKER = 'HARNESS: primary client.close() invoked';
const AUX_CLOSE_MARKER = 'HARNESS: aux client.close() invoked';
const BODY_MARKER = 'HARNESS: body reached';
const AUX_SPAWNED_MARKER = 'HARNESS: aux client constructed';

// ---------------------------------------------------------------------
// SDK stubs. The FIRST client constructed is the primary (connect
// resolves); the SECOND is the secondary (connect hangs). A module-scope
// counter distinguishes them so a single stub class serves both.
// ---------------------------------------------------------------------

let clientSeq = 0;

class StubClient {
  constructor() {
    this._seq = clientSeq++;
    this._isPrimary = this._seq === 0;
  }
  connect() {
    if (this._isPrimary) {
      // Primary connects cleanly so the body runs and boots the secondary.
      return Promise.resolve();
    }
    // Secondary: connect NEVER settles — the orphan-prone hang.
    process.stdout.write(AUX_SPAWNED_MARKER + '\n');
    return new Promise(() => {});
  }
  async close() {
    process.stdout.write(
      (this._isPrimary ? PRIMARY_CLOSE_MARKER : AUX_CLOSE_MARKER) + '\n',
    );
  }
  // The body issues no tool calls before booting the secondary; a stub
  // is enough for the primary's lifecycle.
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

// ---------------------------------------------------------------------
// Drive the real runner. The body boots a SECONDARY server via the real
// `connectServer` + registers it with the outer watchdog via the real
// `registerAuxClient` — exactly the redaction arms' shape. The secondary
// connect hangs, so the body's `await connectServer(...)` never resolves
// and the only exit path is the outer watchdog.
// ---------------------------------------------------------------------

const {
  runWithWatchdog,
  connectServer,
  registerAuxClient,
} = require(path.join(__dirname, '_runner.cjs'));

runWithWatchdog(
  {
    watchdogMs: 200,
    transportSpec: { command: 'node', args: ['-e', '0'] },
    clientName: 'mcp-conformance-aux-hang-harness-primary',
  },
  async () => {
    process.stdout.write(BODY_MARKER + '\n');
    // Boot a secondary server the SAME way the redaction arms do: bare
    // `connectServer` with `onClient: registerAuxClient`. The secondary
    // connect hangs, so this await never resolves; the outer watchdog must
    // reap BOTH the primary and this secondary.
    await connectServer({
      clientName: 'mcp-conformance-aux-hang-harness-secondary',
      transportSpec: { command: 'node', args: ['-e', '0'] },
      onClient: registerAuxClient,
    });
    process.stdout.write('HARNESS: secondary connect resolved (UNEXPECTED)\n');
  },
);
