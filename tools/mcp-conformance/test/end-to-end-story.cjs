// End-to-end MCP-client conformance test for tools/story-mcp.
//
// Unlike tools/story-mcp/test/stdio-roundtrip.js (which hand-rolls
// JSON-RPC on stdin/stdout), this harness drives the JVM story-mcp
// server through the official @modelcontextprotocol/sdk `Client`. The
// same library a real MCP-aware consumer (Claude Code, Continue, …)
// would use. Catches protocol-mismatch bugs the server-side tests
// cannot:
//
//   - response-envelope drift (CallToolResultSchema rejection)
//   - initialize-result drift (InitializeResultSchema rejection)
//   - tool-descriptor-shape drift (ListToolsResultSchema rejection)
//   - structuredContent shape drift (every assertion below routes
//     through the SDK's own parse step before user code sees it)
//
// Workflow (canonical write-loop with --allow-writes enabled). This is
// the single agent-loop integration harness for story-mcp's write
// surface — it absorbed the four smokes that the now-deleted
// tools/story-mcp/test/live-server.js used to add on top of a
// hand-rolled copy of this same loop (rf2-2mx0q). Running one SDK-driven
// boot instead of two hand-rolled + SDK boots saves a full JVM start in
// CI for no loss of coverage:
//
//   1. connect (initialize + notifications/initialized via SDK)
//   2. tools/list — confirm 19 tools advertised
//   3. register-variant — write a fixture variant
//   4. get-variant — read it back; assert the body :doc round-trips
//      through the EDN text payload (ex-live-server smoke #1)
//   5. preview-variant — exercise the run-variant lifecycle via the
//      preview tool; assert a :lifecycle key surfaces (ex-live-server
//      smoke #2)
//   6. run-variant — exercise lifecycle via the testing-category tool,
//      assert :passing?=true
//   7. read-failures — zero failures expected
//   8. snapshot-identity — same args ⇒ stable content-hash twice in a
//      row (ex-live-server smoke #3)
//   9. record-as-variant — zero-duration capture; proves the recorder
//      bridge (rf2-luhdu) is wired into dispatch (ex-live-server
//      smoke #4)
//  10. unregister-variant — symmetric teardown + not-found verify
//  11. JSON-RPC error-code conformance
//  12. clean disconnect
//
// Run with: `node test/end-to-end-story.cjs` from this directory. Exits
// 0 on success. Source: rf2-cum40 (loop) + rf2-2mx0q (live-server
// absorption).

const fs = require('node:fs');
const path = require('node:path');
const {
  runWithWatchdog,
  assertJsonRpcErrorCodes,
  assertDescriptorShape,
} = require('./_runner.cjs');
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

// Canonical tool-name list — sourced from story-mcp's own fixture
// (rf2-36upq TE7) so this conformance harness and the upstream
// `tools/story-mcp/test/stdio-roundtrip.js` / JVM `tools_test.clj`
// agree on the expected `tools/list` response without three hand-
// maintained lists drifting. A registry change updates one file.
const EXPECTED_TOOLS = JSON.parse(
  fs.readFileSync(path.join(STORY_MCP_CWD, 'test', 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// Fixture variant — namespaced under `story.mcp-conformance` so it
// won't collide with anything the canonical-vocabulary install
// registers, and distinct from the `stdio-roundtrip.js` probe ids so
// the two test scripts could in principle run against the same JVM.
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
    // The SDK's `client.connect()` (invoked by the runner) already
    // validated the initialize envelope against `InitializeResultSchema`
    // — a missing / malformed `serverInfo` would have thrown there.
    // We surface the negotiated identity for diagnostic logging only.
    console.log('OK   connect ->', client.getServerVersion());

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

    // Descriptor-shape conformance — inputSchema type=object +
    // max-tokens (rf2-i3ffz F-GAP-2 / TOKEN-BUDGETS.md), :outputSchema
    // (rf2-3l3be), and an :annotations classification hint (rf2-94p8q).
    // Shared `assertDescriptorShape` helper (rf2-80y2h dedup). story-mcp
    // is closed-world (reads + fixture writes), so openWorldHint does
    // NOT count as a classifier — `allowOpenWorld` omitted (defaults
    // false).
    assertDescriptorShape(listed.tools, { allowOpenWorld: false });
    console.log(
      'OK   every tool descriptor: inputSchema(type=object,max-tokens) + outputSchema + annotations hint',
    );

    // 3. register-variant — body as an EDN string so JSON's lack of
    // keyword support doesn't dilute the assertion (Cheshire would
    // coerce {"doc": "..."} into a string-keyed map, which Story's
    // registrar rejects).
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

    // 4. get-variant — round-trip the body. Cheshire coerces keys to
    // strings on the wire, so the comparison reads the :doc back from
    // the text payload (EDN-encoded, keyword keys preserved). Absorbed
    // from live-server.js (rf2-2mx0q) — proves the register→read
    // round-trip the bare run/read loop did not.
    const getResp = await client.callTool({
      name: 'get-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (getResp.isError) {
      throw new Error('get-variant after register failed: ' + JSON.stringify(getResp));
    }
    const getText = getResp.content?.[0]?.text || '';
    if (!/MCP-conformance harness probe variant\./.test(getText)) {
      throw new Error('get-variant text payload missing :doc; got: ' + getText.slice(0, 200));
    }
    console.log('OK   get-variant -> body :doc round-trips through EDN text');

    // 5. preview-variant — exercise the full lifecycle via the preview
    // tool. With no :play events the run is vacuously passing; the
    // contract is that a :lifecycle key surfaces (loaders → events →
    // render → play wiring is live). Absorbed from live-server.js
    // (rf2-2mx0q).
    const prevResp = await client.callTool({
      name: 'preview-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (prevResp.isError) {
      throw new Error('preview-variant on fixture failed: ' + JSON.stringify(prevResp));
    }
    const prevStruct = prevResp.structuredContent || {};
    if (!('lifecycle' in prevStruct)) {
      throw new Error(
        'preview-variant structuredContent missing :lifecycle: ' + JSON.stringify(prevResp),
      );
    }
    console.log('OK   preview-variant -> lifecycle=' + prevStruct.lifecycle);

    // 6. run-variant — exercise lifecycle; vacuous pass since :play
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

    // 7. read-failures — zero failures expected after a vacuous-pass
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

    // 8. snapshot-identity — same args ⇒ same content-hash. Absorbed
    // from live-server.js (rf2-2mx0q): proves the stable-hash identity
    // contract the bare loop did not exercise.
    const snap1 = await client.callTool({
      name: 'snapshot-identity',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    const snap2 = await client.callTool({
      name: 'snapshot-identity',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (snap1.isError || snap2.isError) {
      throw new Error(
        'snapshot-identity errored: ' + JSON.stringify(snap1) + ' / ' + JSON.stringify(snap2),
      );
    }
    const h1 = snap1.structuredContent?.['content-hash'];
    const h2 = snap2.structuredContent?.['content-hash'];
    if (!h1 || h1 !== h2) {
      throw new Error('snapshot-identity content-hash not stable: ' + h1 + ' vs ' + h2);
    }
    console.log('OK   snapshot-identity -> stable hash: ' + h1);

    // 9. record-as-variant — zero-duration capture; empty :play
    // snippet. Proves the recorder bridge (rf2-luhdu) is wired into
    // dispatch. Absorbed from live-server.js (rf2-2mx0q).
    const recResp = await client.callTool({
      name: 'record-as-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (recResp.isError) {
      throw new Error('record-as-variant zero-duration failed: ' + JSON.stringify(recResp));
    }
    const recStruct = recResp.structuredContent || {};
    if (recStruct['recorded-event-count'] !== 0) {
      throw new Error(
        'record-as-variant :recorded-event-count expected 0; got: ' + JSON.stringify(recResp),
      );
    }
    if (typeof recStruct['play-snippet'] !== 'string') {
      throw new Error(
        'record-as-variant :play-snippet missing/non-string: ' + JSON.stringify(recResp),
      );
    }
    console.log('OK   record-as-variant -> recorded-event-count=0, play-snippet emitted');

    // 10. unregister-variant — symmetric teardown.
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

    // 11. JSON-RPC error-code conformance (rf2-i3ffz F-GAP-3). Mirrors
    // the assertion in end-to-end-re-frame2-pair.cjs — story-mcp MUST emit the
    // same canonical codes from `mcp-base/vocab.cljc` for unknown-method
    // + malformed-params (its JVM transport reuses the same SDK
    // server-side framing).
    await assertJsonRpcErrorCodes(client);
    console.log(
      'OK   JSON-RPC error codes -> MethodNotFound + (InvalidParams|InternalError)',
    );

    // 12. Clean disconnect — runner handles client.close() on success.
    console.log('\nSTORY-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
