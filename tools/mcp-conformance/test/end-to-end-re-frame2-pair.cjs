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
const {
  runWithWatchdog,
  assertJsonRpcErrorCodes,
  assertDescriptorShape,
  assertClassificationRatchet,
} = require('./_runner.cjs');

const RE_FRAME2_PAIR_MCP_DIR = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp');
const SERVER = path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js');

// Per-tool annotation-classification ratchet (rf2-yi451). Pins each
// descriptor's EXACT readOnly/destructive posture + budget-hint prose so
// a tool silently re-classified (a destructive write re-labelled
// readOnly — a trust-boundary regression an agent host would auto-approve)
// turns this gate RED for an unchanged tool-set. Sourced from this
// slice's own fixture (mirrors tools/re-frame2-pair-mcp/tool-descriptors.edn).
const EXPECTED_CLASSIFICATIONS = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 're-frame2-pair-classifications.json'), 'utf8'),
);

// Canonical tool-name list — sourced from re-frame2-pair-mcp's own fixture
// (rf2-drke0, mirrors story-mcp's rf2-36upq TE7) so this conformance
// harness and the upstream `tools/re-frame2-pair-mcp/test/stdio-roundtrip.js`
// agree on the expected `tools/list` response without two hand-
// maintained lists drifting. A registry change updates one file.
const EXPECTED_TOOLS = JSON.parse(
  fs.readFileSync(path.join(RE_FRAME2_PAIR_MCP_DIR, 'test', 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// Force degraded mode: empty out $SHADOW_CLJS_NREPL_PORT and boot from
// a tmpdir so the port-file probe misses. Pre-rf2-umoz2 that was
// sufficient; the cwd-relative file scan was the last discovery step.
// rf2-umoz2 added a shadow HTTP probe (default port 9630) that would
// otherwise resolve a port whenever shadow happens to be running on the
// CI agent's loopback — so we pin --http-port to a port we know is
// closed (port 1, IANA-reserved + never bound) which makes the probe's
// ECONNREFUSED short-circuit deterministic on every host.
const env = { ...process.env };
delete env.SHADOW_CLJS_NREPL_PORT;

runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair',
    transportSpec: {
      command: process.execPath,
      args: [SERVER, '--http-port', '1'],
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

    // 2b-2d. Descriptor-shape conformance — inputSchema type=object +
    // max-tokens (rf2-i3ffz F-GAP-2 / TOKEN-BUDGETS.md), :outputSchema
    // (rf2-3l3be), and an :annotations classification hint (rf2-94p8q).
    // The shared `assertDescriptorShape` helper pins all three; pair-mcp
    // permits `openWorldHint` as a classifier (its eval-cljs / dispatch
    // tools touch an open world), so `allowOpenWorld: true`. See
    // _runner.cjs for the per-invariant rationale (rf2-80y2h dedup).
    assertDescriptorShape(listed.tools, { allowOpenWorld: true });
    console.log(
      'OK   every tool descriptor: inputSchema(type=object,max-tokens) + outputSchema + annotations hint',
    );

    // 2e. Per-tool classification CONTENT ratchet (rf2-yi451). The shape
    // check above pins that SOME classifier is set; this pins WHICH —
    // the exact readOnly/destructive posture per tool + the budget-hint
    // prose. A destructive write tool (dispatch, eval-cljs, restore-epoch,
    // reset-frame-db) silently re-classified readOnly would ship green
    // under the shape check (readOnlyHint alone satisfies "≥1 set") but
    // turns RED here. See _runner.cjs for the per-posture rationale.
    assertClassificationRatchet(listed.tools, EXPECTED_CLASSIFICATIONS);
    console.log(
      'OK   per-tool classification ratchet: readOnly/destructive posture + ' +
        'budget-hint prose pinned (rf2-yi451)',
    );

    // 3. Canonical workflow (degraded, since no nREPL is available).
    // Every live-runtime tool returns the SAME degraded envelope —
    // `isError: true` + a `:nrepl-port-not-found` text — so the per-tool
    // assertion is identical; only the tool name, its argument shape, and
    // the reason it earns coverage differ. The `DEGRADED_TOOLS` table
    // below pins one row per tool (with that rationale inline) and
    // `assertDegraded` runs the shared assertion. Each call still goes
    // through the SDK's CallToolResultSchema, so a malformed degraded
    // response (missing `content` array, malformed structuredContent)
    // surfaces here as a Zod parse error before the `isError` assertion.
    //
    // The covered tools span every category, which is the point — the
    // degraded walk proves each descriptor reaches the SDK and each
    // dispatch-table entry is wired:
    //
    //   - dispatch          write-shaped tool. The MCP inputSchema names
    //                        the slot `event` — a single EDN-vector
    //                        string parsed server-side (rf2-vflrg).
    //                        (Degraded-mode returns :nrepl-port-not-found
    //                        regardless of args, so the pre-rf2-zb5z6
    //                        `event-v` typo went unobserved here; the
    //                        live subscribe gate surfaced it.)
    //   - watch-epochs       pull-mode tool.
    //   - snapshot           mega-op tool.
    //   - subscribe          streaming-shaped tool. In connected mode it
    //                        would open a notification stream; degraded it
    //                        returns the same error envelope.
    //   - list-subscriptions lists the LIVE reactive sub-cache for a frame
    //                        (rf2-qicji repointed it here from the
    //                        streaming-tap registry; it now reads the same
    //                        source as `snapshot :sub-cache` via the
    //                        runtime's `sub-cache-info` fn). Pure-read,
    //                        optional :frame / :include-values.
    //   - list-streams       the streaming-tap diagnostic list-subscriptions
    //                        formerly carried (rf2-qicji split). Wraps
    //                        `re-frame2-pair.runtime/subscription-info`
    //                        (runtime fn keeps the historical name) so AI
    //                        clients can list active streaming subscriptions
    //                        without an eval-cljs round-trip. Pure-read,
    //                        optional :topic / :sub-id filters.
    const DEGRADED_TOOLS = [
      { name: 'dispatch', arguments: { event: '[:rf-conformance/probe]' } },
      { name: 'watch-epochs', arguments: { 'max-ms': 50 } },
      { name: 'snapshot', arguments: { frames: 'all' } },
      { name: 'subscribe', arguments: { topic: 'trace' } },
      { name: 'list-subscriptions', arguments: {} },
      { name: 'list-streams', arguments: {} },
      // rf2-r5erl — read-dom (and its read-ui sibling + orient) MUST
      // produce an SDK-valid result envelope, never a null
      // structuredContent that the SDK's outputSchema validation rejects
      // at the transport layer (`expected record at structuredContent,
      // received null`). The bug surfaced on a LIVE read-dom whose
      // browser eval came back blank; degraded mode exercises the same
      // envelope-assembly path (the eval short-circuits at
      // :nrepl-port-not-found through wire/err-text). Including them here
      // pins the read-family envelope shape at the authoritative SDK
      // layer, not just a unit proxy.
      { name: 'read-dom', arguments: { selector: 'body', limit: 1 } },
      { name: 'read-ui', arguments: { selector: 'body' } },
      { name: 'orient', arguments: {} },
    ];

    // Call one tool and assert the shared degraded envelope. Returns the
    // SDK response so the structuredContent dual-slot check below can
    // spot-check the assembled spool.
    async function assertDegraded({ name, arguments: args }) {
      const resp = await client.callTool({ name, arguments: args });
      if (!resp.isError) {
        throw new Error(
          name + ' in degraded mode should isError; got: ' + JSON.stringify(resp),
        );
      }
      const text = resp.content?.[0]?.text || '';
      if (!text.includes('nrepl-port-not-found')) {
        throw new Error(
          name + ' degraded text should mention :nrepl-port-not-found; got: ' + text,
        );
      }
      console.log('OK   tools/call ' + name + ' (degraded) -> isError + nrepl-port-not-found');
      return resp;
    }

    const degradedResp = {};
    for (const tool of DEGRADED_TOOLS) {
      degradedResp[tool.name] = await assertDegraded(tool);
    }

    // 3d. structuredContent dual-slot conformance (rf2-hj3pi). Every
    // pair-mcp result envelope MUST carry BOTH the wire-canonical
    // `:content [{:type "text"}]` slot AND a `:structuredContent`
    // slot — the canonical mcp-builder pattern. The SDK already
    // surfaces the structured slot through `result.structuredContent`
    // when present; assert it for one tool per category: read
    // (snapshot, list-subscriptions), action (dispatch), streaming-
    // shaped (subscribe). All four degraded responses above route
    // through wire/err-text which now emits both slots; we spot-
    // check the assembled spool here. The responses come from the
    // `degradedResp` map keyed by tool name (filled by the loop above).
    for (const label of [
      'dispatch',
      'watch-epochs',
      'snapshot',
      'subscribe',
      'list-subscriptions',
      // rf2-r5erl — the read-family envelopes must carry a non-null
      // object structuredContent through the SDK like every other tool.
      'read-dom',
      'read-ui',
      'orient',
    ]) {
      const resp = degradedResp[label];
      if (resp.structuredContent === undefined || resp.structuredContent === null) {
        throw new Error(
          'tool ' + label + " result MUST carry :structuredContent slot " +
            "(rf2-hj3pi dual-slot conformance); got: " + JSON.stringify(resp),
        );
      }
      // Sanity: structured slot must be a JSON object (map) — NOT an
      // array. `typeof [] === 'object'`, so the bare typeof check admits
      // a vector `structuredContent`; the MCP `structuredContent` slot is
      // contractually a JSON object. Reject arrays explicitly so a server
      // that regressed to emitting a vector there is caught (rf2-ee38b.20).
      if (
        typeof resp.structuredContent !== 'object' ||
        Array.isArray(resp.structuredContent)
      ) {
        throw new Error(
          'tool ' + label + " :structuredContent should be a JSON object " +
            '(not an array); got: ' +
            (Array.isArray(resp.structuredContent)
              ? 'array'
              : typeof resp.structuredContent),
        );
      }
    }
    console.log(
      'OK   every tool envelope carries :structuredContent (rf2-hj3pi)',
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
