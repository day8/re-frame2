// End-to-end MCP-client conformance test for tools/pair2-mcp.
//
// Unlike tools/pair2-mcp/test/stdio-roundtrip.js (which hand-rolls the
// JSON-RPC framing on stdin/stdout), this harness drives the server
// through the official @modelcontextprotocol/sdk `Client` — the same
// library a real MCP-aware consumer (Claude Code, Continue, …) would
// use. That catches a class of protocol-mismatch bugs the server-side
// tests can't see:
//
//   - response-envelope drift (the SDK's CallToolResultSchema rejects
//     payloads that fail the spec contract)
//   - capability negotiation drift (initialize result must round-trip
//     through ServerCapabilitiesSchema)
//   - tool-descriptor schema drift (the SDK's ListToolsResultSchema
//     enforces the descriptor shape)
//   - cap-marker handling (the {:rf.mcp/overflow ...} payload flowing
//     through a degraded error result still has to validate as a tool
//     result; this harness asserts the SDK accepts what the server
//     sends)
//
// Run with: `node test/end-to-end-pair2.js` from this directory after
// `cd ../pair2-mcp && shadow-cljs compile server`. Exits 0 on success.
//
// The test runs in degraded mode (no nREPL on $SHADOW_CLJS_NREPL_PORT)
// — same shape as the upstream stdio-roundtrip — so it stays
// self-contained and reproducible on CI without needing a live
// shadow-cljs runtime. Source: rf2-cum40.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog } = require('./_runner.js');

const SERVER = path.resolve(__dirname, '..', '..', 'pair2-mcp', 'out', 'server.js');

// Expected tools advertised by pair2-mcp's tools/list. Pinned exact so
// accidental renames / additions / deletions surface here. Mirrors the
// upstream stdio-roundtrip baseline (fourteen tools post-rf2-cibp8 +
// rf2-pctf8, which added `handler-meta` and `registry-list` as the
// registry-introspection pair on top of the twelve-tool post-rf2-fnpqg
// baseline; that earlier expansion added `get-pair2-instructions` as an
// agent-paste preamble tool on top of the eleven-tool post-rf2-zjz9q
// baseline).
const EXPECTED_TOOLS = [
  'discover-app',
  'dispatch',
  'eval-cljs',
  'get-pair2-instructions',
  'get-path',
  'handler-meta',
  'registry-list',
  'snapshot',
  'subscribe',
  'subscription-info',
  'tail-build',
  'trace-window',
  'unsubscribe',
  'watch-epochs',
];

// Force degraded mode: empty out $SHADOW_CLJS_NREPL_PORT and boot from
// a tmpdir so the port-file probe misses. Same setup as the pair2-mcp
// upstream stdio-roundtrip.
const env = { ...process.env };
delete env.SHADOW_CLJS_NREPL_PORT;

runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-pair2',
    transportSpec: {
      command: process.execPath,
      args: [SERVER],
      cwd: os.tmpdir(),
      env,
    },
  },
  async (client) => {
    const serverInfo = client.getServerVersion();
    if (!serverInfo) throw new Error('connect succeeded but getServerVersion() is empty');
    console.log('OK   connect ->', serverInfo);

    // 2. tools/list via SDK. The SDK validates the response against
    // ListToolsResultSchema, so any descriptor-shape drift surfaces
    // here.
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
    console.log('OK   tools/list -> ' + names.length + ' tools advertised:', names.join(', '));

    // 2b. Spot-check that each advertised descriptor carries an
    // inputSchema. A real MCP consumer reads this to know what
    // arguments to pass. The SDK's ListToolsResultSchema does loose
    // structural validation; we tighten it here.
    for (const t of listed.tools) {
      if (!t.inputSchema || typeof t.inputSchema !== 'object') {
        throw new Error('tool ' + t.name + ' has no inputSchema');
      }
    }
    console.log('OK   every tool descriptor carries an inputSchema');

    // 3. Canonical workflow (degraded, since no nREPL is available):
    //    a. dispatch — proves the write-shaped tool routes through the
    //       SDK; expect graceful degraded `isError: true`.
    //    b. watch-epochs — proves a pull-mode tool routes through the
    //       SDK; expect graceful degraded `isError: true` matching the
    //       documented pattern.
    //    c. snapshot — proves the mega-op tool routes; expect graceful
    //       degraded.
    //
    // Each call goes through the SDK's CallToolResultSchema, so a
    // malformed degraded response (missing `content` array, malformed
    // structuredContent) would surface here as a Zod parse error
    // before we even reach the `isError` assertion.
    const dispatchResp = await client.callTool({
      name: 'dispatch',
      arguments: { 'event-v': '[:rf-conformance/probe]' },
    });
    if (!dispatchResp.isError) {
      throw new Error(
        'dispatch in degraded mode should isError; got: ' + JSON.stringify(dispatchResp),
      );
    }
    const dispatchText = dispatchResp.content?.[0]?.text || '';
    if (!dispatchText.includes('nrepl-port-not-found')) {
      throw new Error(
        'dispatch degraded text should mention :nrepl-port-not-found; got: ' + dispatchText,
      );
    }
    console.log('OK   tools/call dispatch (degraded) -> isError + nrepl-port-not-found');

    const watchResp = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'max-ms': 50 },
    });
    if (!watchResp.isError) {
      throw new Error(
        'watch-epochs in degraded mode should isError; got: ' + JSON.stringify(watchResp),
      );
    }
    const watchText = watchResp.content?.[0]?.text || '';
    if (!watchText.includes('nrepl-port-not-found')) {
      throw new Error(
        'watch-epochs degraded text should mention :nrepl-port-not-found; got: ' + watchText,
      );
    }
    console.log('OK   tools/call watch-epochs (degraded) -> isError + nrepl-port-not-found');

    const snapResp = await client.callTool({
      name: 'snapshot',
      arguments: { frames: 'all' },
    });
    if (!snapResp.isError) {
      throw new Error(
        'snapshot in degraded mode should isError; got: ' + JSON.stringify(snapResp),
      );
    }
    const snapText = snapResp.content?.[0]?.text || '';
    if (!snapText.includes('nrepl-port-not-found')) {
      throw new Error(
        'snapshot degraded text should mention :nrepl-port-not-found; got: ' + snapText,
      );
    }
    console.log('OK   tools/call snapshot (degraded) -> isError + nrepl-port-not-found');

    // 3b. subscribe — same degraded shape. The streaming-shaped tool
    // still routes through `tools/call`; in connected mode it would
    // open a notification stream, but in degraded mode it just
    // returns the same nrepl-port-not-found error envelope.
    const subResp = await client.callTool({
      name: 'subscribe',
      arguments: { topic: 'trace' },
    });
    if (!subResp.isError) {
      throw new Error(
        'subscribe in degraded mode should isError; got: ' + JSON.stringify(subResp),
      );
    }
    const subText = subResp.content?.[0]?.text || '';
    if (!subText.includes('nrepl-port-not-found')) {
      throw new Error(
        'subscribe degraded text should mention :nrepl-port-not-found; got: ' + subText,
      );
    }
    console.log('OK   tools/call subscribe (degraded) -> isError + nrepl-port-not-found');

    // 3c. subscription-info — added by rf2-zjz9q as a dedicated MCP
    // wrapper around `re-frame-pair2.runtime/subscription-info` so AI
    // clients can list active streaming subscriptions without an
    // eval-cljs round-trip. Pure-read tool, no required arguments —
    // optional :topic / :sub-id filters narrow the result. In degraded
    // mode it returns the same nrepl-port-not-found envelope as every
    // other live-runtime tool; covering it here proves the dispatch
    // table is wired and the descriptor reaches the SDK.
    const subInfoResp = await client.callTool({
      name: 'subscription-info',
      arguments: {},
    });
    if (!subInfoResp.isError) {
      throw new Error(
        'subscription-info in degraded mode should isError; got: ' +
          JSON.stringify(subInfoResp),
      );
    }
    const subInfoText = subInfoResp.content?.[0]?.text || '';
    if (!subInfoText.includes('nrepl-port-not-found')) {
      throw new Error(
        'subscription-info degraded text should mention :nrepl-port-not-found; got: ' +
          subInfoText,
      );
    }
    console.log(
      'OK   tools/call subscription-info (degraded) -> isError + nrepl-port-not-found',
    );

    // 4. The runner tears down the transport via client.close() on
    // exit; the SDK closes the transport which kills the child
    // process. If the server hangs on shutdown the runner's watchdog
    // catches it.
    console.log('\nPAIR2-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
