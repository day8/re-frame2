// End-to-end MCP-client conformance test for tools/re-frame2-pair-mcp.
//
// Unlike tools/re-frame2-pair-mcp/test/stdio-roundtrip.js (which hand-rolls the
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
// Run with: `node test/end-to-end-re-frame2-pair.cjs` from this directory after
// `cd ../re-frame2-pair-mcp && shadow-cljs compile server`. Exits 0 on success.
//
// The test runs in degraded mode (no nREPL on $SHADOW_CLJS_NREPL_PORT)
// — same shape as the upstream stdio-roundtrip — so it stays
// self-contained and reproducible on CI without needing a live
// shadow-cljs runtime. Source: rf2-cum40.

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, assertJsonRpcErrorCodes } = require('./_runner.cjs');

const RE_FRAME2_PAIR_MCP_DIR = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp');
const SERVER = path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js');

// Canonical tool-name list — sourced from re-frame2-pair-mcp's own fixture
// (rf2-drke0, mirrors story-mcp's rf2-36upq TE7) so this conformance
// harness and the upstream `tools/re-frame2-pair-mcp/test/stdio-roundtrip.js`
// agree on the expected `tools/list` response without two hand-
// maintained lists drifting. A registry change updates one file.
const EXPECTED_TOOLS = JSON.parse(
  fs.readFileSync(path.join(RE_FRAME2_PAIR_MCP_DIR, 'test', 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// Force degraded mode: empty out $SHADOW_CLJS_NREPL_PORT and boot from
// a tmpdir so the port-file probe misses. Same setup as the re-frame2-pair-mcp
// upstream stdio-roundtrip.
const env = { ...process.env };
delete env.SHADOW_CLJS_NREPL_PORT;

runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair',
    transportSpec: {
      command: process.execPath,
      args: [SERVER],
      cwd: os.tmpdir(),
      env,
    },
  },
  async (client) => {
    // The SDK's `client.connect()` (invoked by the runner) already
    // validated the initialize envelope against `InitializeResultSchema`
    // — a missing / malformed `serverInfo` would have thrown there.
    // We surface the negotiated identity for diagnostic logging only.
    console.log('OK   connect ->', client.getServerVersion());

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

    // 2b. Tighten the inputSchema spot-check past "is an object"
    // (rf2-i3ffz F-GAP-2). The SDK's ListToolsResultSchema already
    // gates structural JSON-Schema-validity at the SDK layer; this
    // step pins two cross-MCP-convention invariants the SDK doesn't
    // model:
    //
    //   1. `type === 'object'` — every tool's input shape is a map
    //      (per NAMING.md §"The verb table"; the catalogue carries
    //      no scalar-input tools today).
    //   2. `properties['max-tokens']` is present on every descriptor
    //      — TOKEN-BUDGETS.md §"Per-call override slot" pins the
    //      MUST: "The slot surfaces in tools/list on every tool
    //      descriptor so clients discover it automatically." A
    //      regression that drops `max-tokens` from a descriptor's
    //      properties would silently break agent-host budget
    //      negotiation; that bug now trips here.
    for (const t of listed.tools) {
      if (!t.inputSchema || typeof t.inputSchema !== 'object') {
        throw new Error('tool ' + t.name + ' has no inputSchema');
      }
      if (t.inputSchema.type !== 'object') {
        throw new Error(
          'tool ' + t.name + " inputSchema.type MUST be 'object' (cross-MCP " +
            "convention; NAMING.md catalogue surface); got: " +
            JSON.stringify(t.inputSchema.type),
        );
      }
      const props = t.inputSchema.properties || {};
      if (!('max-tokens' in props)) {
        throw new Error(
          'tool ' + t.name + " inputSchema.properties MUST expose 'max-tokens' " +
            '(TOKEN-BUDGETS.md §"Per-call override slot": every tool surfaces ' +
            'the cap-override slot so clients discover it automatically).',
        );
      }
    }
    console.log(
      'OK   every tool descriptor carries inputSchema with type=object + max-tokens',
    );

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
      // The dispatch tool's MCP inputSchema names the slot `event` —
      // a single EDN-vector string parsed server-side (rf2-vflrg).
      // (Degraded-mode returns `:nrepl-port-not-found` regardless of
      // args, so the pre-rf2-zb5z6 `event-v` typo went unobserved
      // here; the live subscribe gate surfaced it.)
      arguments: { event: '[:rf-conformance/probe]' },
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
    // wrapper around `re-frame2-pair.runtime/subscription-info` so AI
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

    // 4. JSON-RPC error-code conformance (rf2-i3ffz F-GAP-3). Asserts
    // re-frame2-pair-mcp emits the canonical codes from `mcp-base/vocab.cljc`
    // for unknown-method + malformed-params. The runner-side helper
    // pins the shared contract; same call appears in end-to-end-story.
    await assertJsonRpcErrorCodes(client);
    console.log(
      'OK   JSON-RPC error codes -> MethodNotFound + (InvalidParams|InternalError)',
    );

    // 5. The runner tears down the transport via client.close() on
    // exit; the SDK closes the transport which kills the child
    // process. If the server hangs on shutdown the runner's watchdog
    // catches it.
    console.log('\nRE-FRAME2-PAIR-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
