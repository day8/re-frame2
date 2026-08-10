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
// shadow-cljs runtime.

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {
  runWithWatchdog,
  assertJsonRpcErrorCodes,
  assertDescriptorShape,
  assertClassificationRatchet,
  assertCallCoverageRatchet,
  track,
  assertIsErrorMatchesOk,
} = require('./_runner.cjs');

const RE_FRAME2_PAIR_MCP_DIR = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp');
const SERVER = path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js');

// Per-tool annotation-classification ratchet. Pins each descriptor's
// EXACT readOnly/destructive posture + budget-hint prose so a tool
// silently re-classified (a destructive write re-labelled readOnly — a
// trust-boundary regression an agent host would auto-approve) turns this
// gate RED for an unchanged tool-set. Sourced from this slice's own
// fixture (mirrors tools/re-frame2-pair-mcp/tool-descriptors.edn).
const EXPECTED_CLASSIFICATIONS = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 're-frame2-pair-classifications.json'), 'utf8'),
);

// Canonical tool-name list — sourced from re-frame2-pair-mcp's own fixture
// (mirrors story-mcp's fixture pattern) so this conformance harness and
// the upstream `tools/re-frame2-pair-mcp/test/stdio-roundtrip.js` agree on
// the expected `tools/list` response without two hand-maintained lists
// drifting. A registry change updates one file.
const EXPECTED_TOOLS = JSON.parse(
  fs.readFileSync(path.join(RE_FRAME2_PAIR_MCP_DIR, 'test', 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// Force degraded mode: empty out $SHADOW_CLJS_NREPL_PORT and boot from
// a tmpdir so the port-file probe misses. The server also runs a shadow
// HTTP probe (default port 9630) that would otherwise resolve a port
// whenever shadow happens to be running on the CI agent's loopback — so
// we pin --http-port to a port we know is closed (port 1, IANA-reserved
// + never bound) which makes the probe's ECONNREFUSED short-circuit
// deterministic on every host.
const env = { ...process.env };
delete env.SHADOW_CLJS_NREPL_PORT;

// SDK callTool() coverage tracking via the shared `track` helper
// (_runner.cjs): every tool driven through `Client.callTool()` records
// its name into the returned `called` Set; the coverage ratchet at the
// end of the body fails if any ADVERTISED tool is neither recorded nor in
// the reviewed exclusion table.

// Advertised pair-mcp tools intentionally NOT SDK-call-covered by THIS
// degraded harness, each with a rationale naming WHERE its coverage
// lives. The pair-mcp conformance surface is split: this end-to-end runs
// DEGRADED (no nREPL), where every tool returns the same
// `:nrepl-port-not-found` envelope (except the two gated writes, refused
// pre-connection) — so a degraded probe proves the descriptor reaches the
// SDK, the dispatch-table entry is wired, the arg shape is accepted, and
// the SDK's CallToolResultSchema parses the envelope. The genuinely
// LIVE-only behaviours (streaming progress frames, overflow markers,
// redaction, the eval/write gates' SUCCESS/refusal under a real runtime)
// are pinned by the gated live harnesses. This table is EMPTY: the
// degraded walk below drives a cheap probe for every advertised tool, so
// every one of them is SDK-called here (the live count is printed at
// runtime and pinned by the classification ratchet — no hardcoded total
// to restale). The table is wired through
// `assertCallCoverageRatchet` so a FUTURE tool that is genuinely
// unreachable degraded has a reviewed home (e.g. `{ 'some-live-only-tool':
// 'live-only; covered by live-re-frame2-pair-<x>.cjs under a real nREPL'
// }`) instead of silently dropping out of coverage. A
// blank/stale/contradictory row trips the ratchet.
const PAIR_CALL_EXCLUSIONS = {};

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
  async (rawClient) => {
    // Wrap the SDK client in the coverage tracker: every
    // `client.callTool({name,…})` below records `name` for the callTool
    // coverage ratchet at the end of this body. All other client members
    // delegate transparently.
    const { client, called } = track(rawClient);
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
    // max-tokens (TOKEN-BUDGETS.md), :outputSchema, and an :annotations
    // classification hint. The shared `assertDescriptorShape` helper pins
    // all three; pair-mcp permits `openWorldHint` as a classifier (its
    // eval-cljs / dispatch tools touch an open world), so
    // `allowOpenWorld: true`. See _runner.cjs for the per-invariant
    // rationale.
    assertDescriptorShape(listed.tools, { allowOpenWorld: true });
    console.log(
      'OK   every tool descriptor: inputSchema(type=object,max-tokens) + outputSchema + annotations hint',
    );

    // 2e. Per-tool classification CONTENT ratchet. The shape check above
    // pins that SOME classifier is set; this pins WHICH — the exact
    // readOnly/destructive posture per tool + the budget-hint prose. A
    // destructive write tool (dispatch, eval-cljs, restore-epoch,
    // replace-app-db) silently re-classified readOnly would ship green
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
    //                        string parsed server-side. (Degraded-mode
    //                        returns :nrepl-port-not-found regardless of
    //                        args, so arg-shape regressions surface in the
    //                        live subscribe gate, not here.)
    //   - watch-epochs       pull-mode tool.
    //   - snapshot           mega-op tool.
    //   - subscribe          streaming-shaped tool. In connected mode it
    //                        would open a notification stream; degraded it
    //                        returns the same error envelope.
    //   - list-subscriptions lists the LIVE reactive sub-cache for a frame
    //                        — the same source as `snapshot :sub-cache`,
    //                        via the runtime's `sub-cache-info` fn.
    //                        Pure-read, optional :frame / :include-values.
    //   - list-streams       the streaming-tap diagnostic list. Wraps
    //                        `re-frame2-pair.runtime/subscription-info` so
    //                        AI clients can list active streaming
    //                        subscriptions without an eval-cljs round-trip.
    //                        Pure-read, optional :topic / :sub-id filters.
    const DEGRADED_TOOLS = [
      { name: 'dispatch', arguments: { event: '[:rf-conformance/probe]' } },
      { name: 'watch-epochs', arguments: { 'max-ms': 50 } },
      { name: 'snapshot', arguments: { frames: 'all' } },
      { name: 'subscribe', arguments: { topic: 'trace' } },
      { name: 'list-subscriptions', arguments: {} },
      { name: 'list-streams', arguments: {} },
      // read-dom (and its read-ui sibling + orient) MUST produce an
      // SDK-valid result envelope, never a null structuredContent that the
      // SDK's outputSchema validation rejects at the transport layer
      // (`expected record at structuredContent, received null`). A LIVE
      // read-dom whose browser eval comes back blank hits this path;
      // degraded mode exercises the same envelope-assembly path (the eval
      // short-circuits at :nrepl-port-not-found through wire/err-text).
      // Including them here pins the read-family envelope shape at the
      // authoritative SDK layer, not just a unit proxy.
      { name: 'read-dom', arguments: { selector: 'body', limit: 1 } },
      { name: 'read-ui', arguments: { selector: 'body' } },
      { name: 'orient', arguments: {} },
      // The three re-frame.hicasso.tool reads — the adapter-neutral evidence
      // door. Degraded (no nREPL) each routes through ensure-runtime! to the
      // shared :nrepl-port-not-found envelope, pinning the callTool envelope +
      // dispatch wiring. All three are NULLARY — the runtime mints no boundary
      // identity, so there is no id to narrow by and no missing-arg
      // short-circuit to route around; the LIVE read runs in a real Hicasso app
      // tab (the door lives in day8/re-frame2-hicasso and nothing in
      // re-frame.hicasso requires it).
      { name: 'read-mounted-boundaries', arguments: {} },
      { name: 'read-read-attribution', arguments: {} },
      { name: 'explain-render', arguments: {} },
      // Every advertised pair-mcp tool below is SDK-CALLED so a regression
      // in its callTool envelope / outputSchema / structuredContent shape
      // / argument handling turns RED rather than shipping green under a
      // descriptor-only check. In degraded mode (no nREPL) each routes
      // through `ensure-connection!` first and returns the SAME
      // `:nrepl-port-not-found` envelope — which still proves, per tool:
      // (a) the descriptor reaches the SDK, (b) the dispatch-table entry
      // is wired (an unwired name surfaces a different error), (c) the arg
      // shape is accepted into the envelope, (d) the SDK's
      // CallToolResultSchema parses the result. The two gated WRITE tools
      // (restore-epoch / replace-app-db) are NOT here — they are refused
      // PRE-connection with `:writes-disabled`, not `:nrepl-port-not-found`
      // (writes/refuse-pre-connection fires before ensure-connection!), so
      // they get their own assertion block below. The two CLOSED-WORLD
      // tools (get-re-frame2-pair-instructions / get-stream-controls) are
      // ALSO not here (rf2-6amhbt): they read only server-local state (no
      // nREPL), so the server dispatches them at the pre-connection
      // boundary — they SUCCEED degraded and are covered by the
      // closed-world success block below, not this degraded walk.
      { name: 'discover-app', arguments: {} },
      // eval-cljs is default-ON (no pre-connection gate unless --no-eval),
      // so degraded it routes through ensure-connection! and returns the
      // shared :nrepl-port-not-found envelope. Its LIVE success / overflow
      // behaviour is covered by live-re-frame2-pair-overflow.cjs and its
      // --no-eval refusal by live-re-frame2-pair-subscribe.cjs; this
      // degraded probe pins the callTool envelope + dispatch wiring.
      { name: 'eval-cljs', arguments: { form: '(+ 1 2)' } },
      { name: 'dispatch-dry-run', arguments: { event: '[:rf-conformance/probe]' } },
      { name: 'get-operating-frame', arguments: {} },
      { name: 'get-path', arguments: { path: '[:does :not :matter]' } },
      { name: 'handler-meta', arguments: { kind: 'event', id: ':rf-conformance/probe' } },
      { name: 'list-handlers', arguments: { kind: 'event' } },
      // EP-0017 cofx introspection over the SDK wire. The `cofx` kind is
      // one of the closed-v1 registrar kinds `handler-meta` /
      // `list-handlers` advertise (tool-descriptors.edn). Degraded mode
      // routes both through `ensure-connection!` to the shared
      // :nrepl-port-not-found envelope — which still proves the `cofx` kind
      // is ACCEPTED into the envelope (an unsupported kind would return
      // :invalid-kind instead, NOT :nrepl-port-not-found) and reaches the
      // SDK. The LIVE positive (the `:rf/time-ms` recordable-cofx
      // registration metadata is actually surfaced) is pinned by the
      // hermetic live-re-frame2-pair-cofx.cjs gate.
      { name: 'handler-meta', arguments: { kind: 'cofx', id: ':rf/time-ms' } },
      { name: 'list-handlers', arguments: { kind: 'cofx' } },
      // describe-image reads a frame's running image generation over the
      // public rf/frame-generation facade read. Degraded mode routes it
      // through ensure-connection! to the shared :nrepl-port-not-found
      // envelope (proving the callTool wiring); the LIVE generation
      // projection is the runtime preload's concern, pinned by the bb
      // structural test
      // skills/re-frame2-pair/tests/runtime/frame_registrar_test.clj.
      { name: 'describe-image', arguments: { frame: ':rf/default' } },
      { name: 'read-recording', arguments: { 'recording-id': 'rf2-conformance-no-such' } },
      { name: 'read-sub', arguments: { sub: '[:rf-conformance/probe]' } },
      { name: 'record', arguments: { signals: '[[:rf-conformance/probe]]' } },
      { name: 'reset-operating-frame', arguments: {} },
      { name: 'set-operating-frame', arguments: { frame: ':rf/default' } },
      { name: 'tail-build', arguments: {} },
      { name: 'trace-window', arguments: { 'max-ms': 50 } },
      { name: 'unsubscribe', arguments: { 'sub-id': 'rf2-conformance-no-such' } },
      {
        name: 'watch-until',
        arguments: { signals: '[[:rf-conformance/probe]]', pred: '(constantly true)' },
      },
    ];

    // Universal isError <-> :ok? cross-check (spec/003-Tool-Catalogue.md
    // §381): EVERY `:ok? false` structuredContent MUST carry
    // `isError === true`, and every `:ok? true` MUST carry a falsy
    // isError. `assertIsErrorMatchesOk` lives in `_runner.cjs` (routed
    // through the shared `structured()` dedup-decoder, not the raw wire
    // `resp.structuredContent` — see its docstring there for the
    // dedup-envelope rationale, rf2-6i2yi4 finding 1).

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

    // 3b-closed-world. Closed-world success path (rf2-6amhbt). Unlike
    // every tool in the degraded walk above, `get-re-frame2-pair-instructions`
    // and `get-stream-controls` read ONLY server-local state — inline
    // onboarding text / the in-process resource-control atoms — with NO
    // nREPL round-trip. The server dispatches them at the pre-connection
    // boundary (bypassing `ensure-connection!`), so even in THIS degraded
    // harness (no nREPL) they MUST SUCCEED (isError:false) rather than
    // return the shared `:nrepl-port-not-found` envelope. This pins the
    // spec/003 "answers even when the runtime is down" contract at the real
    // MCP boundary — the exact behaviour the earlier harness mis-encoded as
    // a degraded failure. Each still routes through the SDK's
    // CallToolResultSchema + the declared outputSchema parse, so a
    // structuredContent regression turns RED. Mirrors the analogous Story
    // closed-world block in end-to-end-story.cjs. These two calls also keep
    // both tools SDK-covered for the callTool coverage ratchet below.
    for (const readTool of [
      'get-re-frame2-pair-instructions',
      'get-stream-controls',
    ]) {
      const r = await client.callTool({ name: readTool, arguments: {} });
      if (r.isError) {
        throw new Error(
          'closed-world tool ' + readTool + ' MUST succeed (isError=false) ' +
            'with no nREPL — it reads server-local state and is dispatched ' +
            'pre-connection (rf2-6amhbt); got: ' + JSON.stringify(r),
        );
      }
      const text = r.content?.[0]?.text || '';
      if (text.includes('nrepl-port-not-found')) {
        throw new Error(
          readTool + ' MUST NOT return the degraded :nrepl-port-not-found ' +
            'envelope — it answers without a runtime; got: ' + text.slice(0, 200),
        );
      }
      if (r.structuredContent === undefined || r.structuredContent === null ||
          typeof r.structuredContent !== 'object' ||
          Array.isArray(r.structuredContent)) {
        throw new Error(
          readTool + ' success envelope MUST carry a JSON-object ' +
            ':structuredContent slot; got: ' + JSON.stringify(r),
        );
      }
      // isError:false MUST agree with :ok? true (universal cross-check).
      assertIsErrorMatchesOk('tools/call ' + readTool + ' (closed-world)', r);
    }
    console.log(
      'OK   closed-world tools (get-re-frame2-pair-instructions/' +
        'get-stream-controls) -> success envelopes with no nREPL (rf2-6amhbt)',
    );

    // 3c. Gated WRITE tools — pre-connection refusal. The two
    // state-mutating tools `restore-epoch` and `replace-app-db` are gated
    // behind `--allow-writes` (default OFF). This server booted WITHOUT
    // the flag, so `writes/refuse-pre-connection` short-circuits BOTH to
    // the `:rf.error/writes-disabled` envelope BEFORE `ensure-connection!`
    // runs — so unlike every other tool they do NOT return
    // `:nrepl-port-not-found` in degraded mode; they return the write-gate
    // refusal. Probing them here pins the default-safe write posture at
    // the real MCP boundary (the env that ships) — a regression that
    // flipped the default ON, renamed the gate flag, or dropped the
    // pre-connection guard surfaces RED. The args are well-formed but
    // inert: the guard fires before the arg is read or any nREPL touched.
    // (The LIVE, non-degraded refusal — gate-OFF with a runtime attached —
    // is additionally pinned by live-re-frame2-pair-subscribe.cjs, which
    // also asserts the `:tool` slot; here we pin the degraded/pre-connection
    // path the live test cannot reach.)
    for (const probe of [
      { name: 'restore-epoch', arguments: { 'epoch-id': '0' } },
      { name: 'replace-app-db', arguments: { db: '{}' } },
    ]) {
      const resp = await client.callTool(probe);
      degradedResp[probe.name] = resp;
      if (!resp.isError) {
        throw new Error(
          probe.name + ' MUST isError when booted WITHOUT --allow-writes ' +
            '(default-OFF write gate, rf2-ee38b.18); the state-mutating ' +
            'surface is reachable unauthorised. got: ' + JSON.stringify(resp),
        );
      }
      const text = resp.content?.[0]?.text || '';
      if (!text.includes(':rf.error/writes-disabled')) {
        throw new Error(
          probe.name + ' default-OFF write-gate envelope MUST carry ' +
            ':rf.error/writes-disabled (writes/disabled-result, NAMING.md ' +
            'cross-server flag-vocabulary contract); got: ' + text.slice(0, 200),
        );
      }
      // The pre-connection refusal must NOT leak a misleading
      // :nrepl-port-not-found — the guard runs before discovery.
      if (text.includes('nrepl-port-not-found')) {
        throw new Error(
          probe.name + ' write-gate refusal MUST NOT mention ' +
            ':nrepl-port-not-found — the gate is refused PRE-connection, not ' +
            'after a failed discovery (rf2-wz66k7); got: ' + text.slice(0, 200),
        );
      }
      console.log(
        'OK   tools/call ' + probe.name + ' (no --allow-writes, degraded) -> ' +
          'isError + :rf.error/writes-disabled (pre-connection refusal)',
      );
    }

    // 3c-quater. Unknown tool name — pre-connection refusal. Symmetric
    // with the gated-write pre-connection guard above: a name absent from
    // the registry (a typo or a removed alias) is rejected by
    // `tools/refuse-unknown-tool` in `server.cljs/handle-call` BEFORE
    // `ensure-connection!` runs discovery. So in degraded mode (no nREPL)
    // an unknown tool returns the recovery-shaped `:unknown-tool` envelope,
    // NOT `:nrepl-port-not-found`. The guard surfaces the recovery
    // affordances (`tools/list` hint + `:available-tools` catalogue) for
    // the fresh/misconfigured-session case they serve, even though this
    // conformance harness drives the compiled server WITHOUT a live nREPL.
    // A regression that drops the guard or re-orders it after discovery —
    // which would mask the unknown name behind a transport error — surfaces
    // RED here.
    {
      const resp = await client.callTool({
        name: 'no-such-tool-xyz',
        arguments: {},
      });
      degradedResp['no-such-tool-xyz'] = resp;
      if (!resp.isError) {
        throw new Error(
          'unknown tool MUST isError; got: ' + JSON.stringify(resp),
        );
      }
      const text = resp.content?.[0]?.text || '';
      if (!text.includes('unknown-tool')) {
        throw new Error(
          'unknown tool MUST diagnose as :unknown-tool even in degraded ' +
            'mode (rf2-4mc6q1 — refused PRE-connection); got: ' + text.slice(0, 200),
        );
      }
      if (text.includes('nrepl-port-not-found')) {
        throw new Error(
          'unknown tool refusal MUST NOT mention :nrepl-port-not-found — ' +
            'registry membership is a pure function of the static catalogue, ' +
            'refused before discovery (rf2-4mc6q1); got: ' + text.slice(0, 200),
        );
      }
      if (!text.includes('tools/list')) {
        throw new Error(
          'unknown-tool envelope MUST carry the tools/list recovery hint ' +
            '(rf2-tkmik); got: ' + text.slice(0, 200),
        );
      }
      console.log(
        'OK   tools/call no-such-tool-xyz (degraded) -> isError + ' +
          ':unknown-tool (pre-connection refusal, recovery hint intact)',
      );
    }

    // 3c-ter. EP-0017 reproducible-dispatch `cofx` argument — degraded
    // accept. The `dispatch` tool advertises a `cofx` input-key (an EDN
    // map of scripted recordable coeffects, e.g.
    // `"{:rf/time-ms 1781078400123}"`) for deterministic replay. In
    // degraded mode the server's `degraded-handler` short-circuits the
    // tool to the shared `:nrepl-port-not-found` envelope BEFORE the tool
    // body's cofx shape-check runs (same posture as every other
    // live-runtime tool — UNLIKE the write gate, which refuses
    // pre-connection). So a degraded probe cannot reach the cofx
    // shape-check; what it DOES prove is that a `cofx` arg is ACCEPTED into
    // the request envelope and the SDK's CallToolResultSchema parses the
    // result (a regression that rejected the `cofx` arg shape at the SDK /
    // descriptor boundary would surface here). Recorded in `degradedResp`
    // so the universal :ok?/isError cross-check below sweeps it.
    //
    // The behaviours the cofx shape-check governs — the MALFORMED-cofx
    // `:invalid-cofx` / `:invalid-cofx-time-ms` refusals AND the positive
    // that a supplied `:rf/time-ms` reaches the resulting app-db state —
    // are reachable ONLY with a live runtime (the degraded-handler hides
    // the tool body), so they are pinned by the hermetic
    // live-re-frame2-pair-cofx.cjs gate.
    {
      const resp = await assertDegraded({
        name: 'dispatch',
        arguments: {
          event: '[:rf-conformance/probe]',
          cofx: '{:rf/time-ms 1781078400123}',
        },
      });
      degradedResp['dispatch+cofx'] = resp;
    }

    // 3c-bis. Universal isError <-> :ok? cross-check across the whole
    // degraded walk. Every degraded / write-gate envelope collected above
    // carries `:ok? false` and MUST be isError:true; this sweeps them ALL
    // through the contract assertion (not just the tool-specific
    // :nrepl-port-not-found / :writes-disabled checks). A server that
    // decoupled isError from `:ok?` on ANY tool — the class the sibling
    // read-dom/read-ui envelope belongs to — trips RED here.
    for (const [label, resp] of Object.entries(degradedResp)) {
      assertIsErrorMatchesOk('tools/call ' + label + ' (degraded)', resp);
    }
    console.log(
      'OK   every :ok? false envelope is isError:true (universal cross-check, rf2-87h71e)',
    );

    // 3d. structuredContent dual-slot conformance. Every
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
      // the read-family envelopes must carry a non-null object
      // structuredContent through the SDK like every other tool.
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
      // that emits a vector there is caught.
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

    // 4. JSON-RPC error-code conformance. Asserts re-frame2-pair-mcp emits
    // the canonical codes from `mcp-base/vocab.cljc` for unknown-method +
    // malformed-params. The runner-side helper pins the shared contract;
    // same call appears in end-to-end-story.
    await assertJsonRpcErrorCodes(client);
    console.log(
      'OK   JSON-RPC error codes -> MethodNotFound + (InvalidParams|InternalError)',
    );

    // 4b. callTool() coverage ratchet. Every advertised pair-mcp tool MUST
    // have been driven through `Client.callTool()` above (recorded in
    // `called` by the `track` proxy) or carry a reviewed exclusion in
    // `PAIR_CALL_EXCLUSIONS`. A NEW advertised tool the workflow forgets
    // to probe trips RED here — closing the descriptor-only false-green.
    assertCallCoverageRatchet({
      advertised: names,
      called,
      exclusions: PAIR_CALL_EXCLUSIONS,
    });
    console.log(
      'OK   callTool coverage ratchet -> every advertised tool SDK-called ' +
        '(' + called.size + '/' + names.length + ') or reviewed-excluded',
    );

    // 5. The runner tears down the transport via client.close() on
    // exit; the SDK closes the transport which kills the child
    // process. If the server hangs on shutdown the runner's watchdog
    // catches it.
    console.log('\nRE-FRAME2-PAIR-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
