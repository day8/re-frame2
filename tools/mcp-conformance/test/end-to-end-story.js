// End-to-end MCP-client conformance test for tools/story-mcp.
//
// Unlike tools/story-mcp/test/{stdio-roundtrip,live-server}.js (which
// hand-roll JSON-RPC on stdin/stdout), this harness drives the JVM
// story-mcp server through the official @modelcontextprotocol/sdk
// `Client`. The same library a real MCP-aware consumer (Claude Code,
// Continue, …) would use. Catches protocol-mismatch bugs the
// server-side tests cannot:
//
//   - response-envelope drift (CallToolResultSchema rejection)
//   - initialize-result drift (InitializeResultSchema rejection)
//   - tool-descriptor-shape drift (ListToolsResultSchema rejection)
//   - structuredContent shape drift (every assertion below routes
//     through the SDK's own parse step before user code sees it)
//
// Workflow (canonical write-loop with --allow-writes enabled):
//
//   1. connect (initialize + notifications/initialized via SDK)
//   2. tools/list — confirm 19 tools advertised (mirrors live-server.js)
//   3. register-variant — write a fixture variant
//   4. run-variant — exercise lifecycle, assert :passing?=true
//   5. read-failures — zero failures expected
//   6. unregister-variant — symmetric teardown
//   7. clean disconnect
//
// Run with: `node test/end-to-end-story.js` from this directory. Exits
// 0 on success. Source: rf2-cum40.

const path = require('node:path');
const { runWithWatchdog } = require('./_runner.js');
const { resolveTrustedExe } = require('../lib/exec-safety.cjs');

const STORY_MCP_CWD = path.resolve(__dirname, '..', '..', 'story-mcp');
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
// Resolve `clojure` to a trusted absolute path outside the workspace,
// or honour the explicit STORY_MCP_CMD override (trust the invoker —
// rf2-33vvc accident-gating only fires on the implicit-PATH path).
// Without the override, spawning bare `clojure` with `cwd =
// STORY_MCP_CWD` would let a workspace-local `clojure.cmd` hijack the
// invocation on Windows; the resolved-absolute-path form prevents it.
const CLOJURE = process.env.STORY_MCP_CMD
  ? process.env.STORY_MCP_CMD
  : resolveTrustedExe('clojure', { workspaceRoot: REPO_ROOT });

// Per-server tool catalogue. Same set as tools/story-mcp/test/stdio-roundtrip.js
// — pin exact so accidental renames / additions / deletions surface here.
const EXPECTED_TOOLS = [
  'get-docs-markdown',
  'get-story',
  'get-story-instructions',
  'get-variant',
  'list-assertions',
  'list-decorators',
  'list-modes',
  'list-stories',
  'list-substrates',
  'list-tags',
  'preview-variant',
  'read-failures',
  'record-as-variant',
  'register-variant',
  'run-a11y',
  'run-variant',
  'snapshot-identity',
  'unregister-variant',
  'variant->edn',
];

// Fixture variant — namespaced under `story.mcp-conformance` so it
// won't collide with anything the canonical-vocabulary install
// registers, and distinct from the live-server.js fixture so the two
// test scripts could in principle run against the same JVM.
const FIXTURE_VARIANT = 'story.mcp-conformance/probe.primary';

// JVM boot is slow on a cold CI worker (~10-30s); the whole register →
// run → read → unregister loop adds a few more seconds. 90s is
// comfortably above that without leaving a hung process if something
// stalls.
runWithWatchdog(
  {
    watchdogMs: 90000,
    clientName: 'mcp-conformance-story',
    transportSpec: {
      command: CLOJURE,
      args: ['-M', '-m', 're-frame.story-mcp.server', '--allow-writes'],
      cwd: STORY_MCP_CWD,
      env: { ...process.env },
    },
  },
  async (client) => {
    const serverInfo = client.getServerVersion();
    if (!serverInfo) throw new Error('connect succeeded but getServerVersion() is empty');
    console.log('OK   connect ->', serverInfo);

    // 2. tools/list — confirm catalogue.
    const listed = await client.listTools();
    const names = listed.tools.map((t) => t.name).sort();
    if (JSON.stringify(names) !== JSON.stringify(EXPECTED_TOOLS)) {
      throw new Error(
        'tools/list catalogue mismatch:\n  expected ' +
          JSON.stringify(EXPECTED_TOOLS) +
          '\n  got      ' +
          JSON.stringify(names),
      );
    }
    console.log('OK   tools/list -> ' + names.length + ' tools advertised');

    for (const t of listed.tools) {
      if (!t.inputSchema || typeof t.inputSchema !== 'object') {
        throw new Error('tool ' + t.name + ' has no inputSchema');
      }
    }
    console.log('OK   every tool descriptor carries an inputSchema');

    // 3. register-variant — body as an EDN string so JSON's lack of
    // keyword support doesn't dilute the assertion (same approach as
    // live-server.js).
    const variantBodyEdn =
      '{:doc "MCP-conformance harness probe variant."' +
      ' :args {:label "OK"}' +
      ' :tags #{:dev}}';
    const regResp = await client.callTool({
      name: 'register-variant',
      arguments: {
        'variant-id': FIXTURE_VARIANT,
        body: variantBodyEdn,
      },
    });
    if (regResp.isError) {
      throw new Error('register-variant failed: ' + JSON.stringify(regResp));
    }
    const regStruct = regResp.structuredContent || {};
    if (regStruct.registered !== true && regStruct['registered?'] !== true) {
      throw new Error(
        'register-variant did not report registered=true: ' + JSON.stringify(regResp),
      );
    }
    console.log('OK   register-variant -> ' + FIXTURE_VARIANT + ' registered');

    // 4. run-variant — exercise lifecycle; vacuous pass since :play
    // is empty.
    const runResp = await client.callTool({
      name: 'run-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (runResp.isError) {
      throw new Error('run-variant failed: ' + JSON.stringify(runResp));
    }
    const runStruct = runResp.structuredContent || {};
    if (!Array.isArray(runStruct.assertions)) {
      throw new Error('run-variant :assertions not an array: ' + JSON.stringify(runResp));
    }
    if (runStruct['passing?'] !== true) {
      throw new Error(
        'run-variant :passing? expected true (vacuous pass); got: ' + JSON.stringify(runResp),
      );
    }
    console.log('OK   run-variant -> :passing?=true, assertions=[] (vacuous pass)');

    // 5. read-failures — zero failures expected after a vacuous-pass
    // run.
    const failResp = await client.callTool({
      name: 'read-failures',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (failResp.isError) {
      throw new Error('read-failures failed: ' + JSON.stringify(failResp));
    }
    const failStruct = failResp.structuredContent || {};
    if (failStruct.total !== 0) {
      throw new Error('read-failures :total expected 0; got: ' + JSON.stringify(failResp));
    }
    if (!Array.isArray(failStruct.failures) || failStruct.failures.length !== 0) {
      throw new Error(
        'read-failures :failures expected empty vector; got: ' + JSON.stringify(failResp),
      );
    }
    console.log('OK   read-failures -> total=0, failures=[]');

    // 6. unregister-variant — symmetric teardown.
    const unregResp = await client.callTool({
      name: 'unregister-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (unregResp.isError) {
      throw new Error('unregister-variant failed: ' + JSON.stringify(unregResp));
    }
    const unregStruct = unregResp.structuredContent || {};
    if (unregStruct['unregistered?'] !== true) {
      throw new Error(
        'unregister-variant :unregistered? expected true: ' + JSON.stringify(unregResp),
      );
    }
    // Read-back must now fail.
    const verify = await client.callTool({
      name: 'get-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    const verifyText = verify.content?.[0]?.text || '';
    if (!verify.isError || !/not found/i.test(verifyText)) {
      throw new Error(
        'after unregister, get-variant should yield not-found; got: ' + JSON.stringify(verify),
      );
    }
    console.log('OK   unregister-variant -> teardown verified by subsequent get-variant -> not-found');

    // 7. Clean disconnect — runner handles client.close() on success.
    console.log('\nSTORY-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
