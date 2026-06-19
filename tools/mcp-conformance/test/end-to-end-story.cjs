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
//   2. tools/list — confirm the advertised catalogue matches story-mcp's
//      own `tool-names.json` fixture (count-free per NAMING.md
//      §"Single source of truth for tool counts")
//   3. register-variant — write a fixture variant
//   4. get-variant — read it back; assert the body :doc round-trips
//      through the EDN text payload (ex-live-server smoke #1)
//   5. preview-variant — exercise the run-variant lifecycle via the
//      preview tool; assert a :lifecycle key surfaces (ex-live-server
//      smoke #2)
//   6. run-variant — exercise lifecycle via the testing-category tool,
//      assert the UNIFIED run-result shape (:status === "pass" for the
//      vacuous run; the retired :passing? boolean is gone — rf2-ba86n.17)
//   7. read-failures — zero failures, run :status surfaces (unified shape)
//   7b. explain-variant — the human Explain panel's data mirror; assert
//      the source-chain / merge / runner-requirement slots round-trip
//      (rf2-ba86n.17)
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
  assertClassificationRatchet,
  assertCallCoverageRatchet,
  track,
  structured,
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

// Per-tool annotation-classification ratchet (rf2-yi451). Pins each
// descriptor's EXACT readOnly/destructive posture + budget-hint prose so
// a tool silently re-classified turns this gate RED for an unchanged
// tool-set. Sourced from this slice's own fixture (mirrors
// tools/story-mcp/tool-descriptors.edn).
const EXPECTED_CLASSIFICATIONS = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'story-classifications.json'), 'utf8'),
);

// Fixture variant — namespaced under `story.mcp-conformance` so it
// won't collide with anything the canonical-vocabulary install
// registers, and distinct from the `stdio-roundtrip.js` probe ids so
// the two test scripts could in principle run against the same JVM.
const FIXTURE_VARIANT = 'story.mcp-conformance/probe.primary';

// Wire → semantic adapter (`structured`, _runner.cjs). Every assertion
// below reaches for slots the SEMANTIC contract guarantees (e.g.
// `:lifecycle` on `preview-variant`'s structuredContent). Three of
// story-mcp's tools (`preview-variant`, `run-variant`,
// `record-as-variant`) ship their payloads wrapped in
// `{:rf.mcp/dedup-table <cache>}` at the wire boundary (rf2-90eft) — a
// real MCP client decodes via `de-dupe.core/expand` before user code sees
// it. Routing every structuredContent read through `structured` mirrors
// that (a no-op on payloads without the marker), so a future tool gaining
// or losing dedup-eligibility doesn't break this suite.
//
// SDK callTool() coverage tracking (rf2-ke5n56) via the shared `track`
// helper (_runner.cjs): every tool the workflow drives through
// `Client.callTool()` records its name into the returned `called` Set; the
// coverage ratchet at the end of the body fails if any ADVERTISED tool is
// neither recorded nor in the reviewed exclusion table.

// Tools intentionally NOT SDK-call-covered by THIS harness, each with the
// rationale + where its alternative coverage lives (rf2-ke5n56). Today the
// Story workflow below drives a probe for every advertised tool, so this
// table is empty — but it is wired through `assertCallCoverageRatchet` so
// a FUTURE runtime-heavy/live-only Story tool has a reviewed home instead
// of silently dropping out of coverage. A blank rationale, a stale row, or
// an excluded-yet-called row trips the ratchet.
const STORY_CALL_EXCLUSIONS = {};

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
  async (rawClient) => {
    // Wrap the SDK client in the coverage tracker (rf2-ke5n56): every
    // `client.callTool({name,…})` below records `name` for the callTool
    // coverage ratchet at the end of this body. All other client members
    // delegate transparently.
    const { client, called } = track(rawClient);
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
    // is MOSTLY closed-world (reads + fixture writes), but `run-variant`
    // and `preview-variant` run the variant author's lifecycle events/fx
    // and so carry `openWorldHint:true` (rf2-e6knrq finding 2). Passing
    // `allowOpenWorld: true` lets that hint count as a classifier; the
    // PER-TOOL open-world VALUES are pinned exactly by the classification
    // ratchet's `closed-world` list below + the JVM matrix, so this only
    // relaxes the "at-least-one-classifier" floor — it does not let a
    // closed-world tool silently flip open.
    assertDescriptorShape(listed.tools, { allowOpenWorld: true });
    console.log(
      'OK   every tool descriptor: inputSchema(type=object,max-tokens) + outputSchema + annotations hint',
    );

    // Per-tool classification CONTENT ratchet (rf2-yi451). Pins WHICH
    // classifier per tool (the exact readOnly/destructive posture) +
    // the budget-hint prose — not just that SOME classifier is set. A
    // gated write tool (register-variant, unregister-variant,
    // record-as-variant) silently re-classified readOnly turns RED here.
    assertClassificationRatchet(listed.tools, EXPECTED_CLASSIFICATIONS);
    console.log(
      'OK   per-tool classification ratchet: readOnly/destructive posture + ' +
        'budget-hint prose pinned (rf2-yi451)',
    );

    // Open-world VALUE conformance (rf2-e6knrq finding 2). The ratchet's
    // `closed-world` list pins the closed-world tools to openWorldHint:false;
    // this positively pins the OTHER direction over the SDK wire — the two
    // lifecycle-run tools MUST advertise openWorldHint:true so an agent host
    // does not treat a call that can reach HTTP / analytics / websocket /
    // storage / navigation (any un-stubbed author fx) as contained on-box.
    // The annotation VALUE (not just the KEY presence the generated
    // descriptor manifest tracks) is what an MCP client actually reads.
    const byName = new Map(listed.tools.map((t) => [t.name, t]));
    for (const openWorldTool of ['run-variant', 'preview-variant']) {
      const a = (byName.get(openWorldTool) || {}).annotations || {};
      if (a.openWorldHint !== true) {
        throw new Error(
          openWorldTool +
            ' MUST advertise openWorldHint:true over the wire (rf2-e6knrq ' +
            'finding 2): it runs the variant author\'s lifecycle events/fx ' +
            'which can reach external systems unless explicitly stubbed. Got ' +
            'annotations: ' + JSON.stringify(a),
        );
      }
      if (a.destructiveHint !== true) {
        throw new Error(
          openWorldTool +
            ' MUST also keep destructiveHint:true (lifecycle run is a write); got: ' +
            JSON.stringify(a),
        );
      }
    }
    console.log(
      'OK   open-world VALUE: run-variant + preview-variant advertise ' +
        'openWorldHint:true + destructiveHint:true (rf2-e6knrq)',
    );

    // 2e. Closed-world read-path SUCCESS-envelope conformance
    // (rf2-ee38b.20). The completeness lens noted the harness walked
    // only the write-loop tools and asserted error-only shapes elsewhere
    // — no story-mcp `list-*` read was ever invoked, so a success-path
    // `CallToolResultSchema` parse / structuredContent drift on the read
    // surface (the exact bug class this harness exists to catch) could
    // ship unobserved. story-mcp's `list-*` reads are closed-world (no
    // runtime needed) and return a non-error envelope regardless of the
    // write gate, so they cover the success path here with zero extra
    // setup. Each routes through the SDK's `CallToolResultSchema` parse.
    //
    // `get-story-instructions` is walked here too (rf2-vyacl). It
    // declares an `:outputSchema`, so the SDK's high-level `callTool`
    // REJECTS a result with no structuredContent with `-32600` ("has an
    // output schema but did not return structured content"). The handler
    // now emits a matching `{:instructions <text>}` structuredContent
    // (mirroring re-frame2-pair-mcp's sibling `get-re-frame2-pair-
    // instructions`), so the SDK parse passes — this assertion pins that
    // the latent defect stays fixed across the wire.
    for (const readTool of [
      'get-story-instructions',
      'list-substrates',
      'list-modes',
      'list-tags',
    ]) {
      const r = await client.callTool({ name: readTool, arguments: {} });
      if (r.isError) {
        throw new Error(
          'closed-world read ' + readTool + ' MUST succeed (isError=false) ' +
            'with no runtime; got: ' + JSON.stringify(r),
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
    }
    console.log(
      'OK   closed-world reads (get-story-instructions/list-substrates/' +
        'list-modes/list-tags) -> success envelopes',
    );

    // 2f. Remaining closed-world LIST reads (rf2-ke5n56). The read loop
    // above covered four of story-mcp's reads; `list-stories`,
    // `list-assertions`, and `list-decorators` were advertised but never
    // SDK-CALLED — descriptor-only in conformance, so a callTool-envelope
    // / outputSchema / structuredContent regression on any of them shipped
    // green. These three need no runtime and no fixture (they enumerate
    // the canonical-vocabulary registry the server installs at boot), so
    // each is a zero-arg success probe routed through the SDK's
    // CallToolResultSchema + the declared outputSchema parse — the same
    // success-path shape pin the four reads above carry.
    for (const readTool of ['list-stories', 'list-assertions', 'list-decorators']) {
      const r = await client.callTool({ name: readTool, arguments: {} });
      if (r.isError) {
        throw new Error(
          'closed-world read ' + readTool + ' MUST succeed (isError=false) ' +
            'with no runtime/fixture; got: ' + JSON.stringify(r),
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
    }
    console.log(
      'OK   closed-world reads (list-stories/list-assertions/list-decorators)' +
        ' -> success envelopes',
    );

    // 2g. Refusal-path coverage for the two story-id read tools
    // (rf2-ke5n56). `get-story` and `get-docs-markdown` were advertised
    // but never SDK-called. Both REQUIRE a `:story-id` and resolve it via
    // `safe-keyword` against the registered-stories set — an unregistered
    // id short-circuits to a documented `Story not found` tool-execution
    // error (isError:true, NOT a JSON-RPC protocol error). A default-off
    // refusal probe is the cheap, fixture-free way to drive the callTool
    // path: it pins the not-found envelope shape AND proves the tool is
    // wired into dispatch (an unwired name would surface a different
    // error). A success-path hit would need a registered STORY, but the
    // conformance fixture is a VARIANT (the canonical vocabulary installs
    // no stable story id to hit), so the refusal probe is the coverage.
    const ABSENT_STORY = ':story.mcp-conformance/no-such-story';
    for (const storyTool of ['get-story', 'get-docs-markdown']) {
      const r = await client.callTool({
        name: storyTool,
        arguments: { 'story-id': ABSENT_STORY },
      });
      if (!r.isError) {
        throw new Error(
          storyTool + ' on an unregistered story-id MUST isError (documented ' +
            '`Story not found` refusal); got: ' + JSON.stringify(r),
        );
      }
      const text = r.content?.[0]?.text || '';
      if (!/not found/i.test(text)) {
        throw new Error(
          storyTool + ' not-found envelope MUST mention "not found"; got: ' +
            text.slice(0, 200),
        );
      }
    }
    console.log(
      'OK   story-id refusal probes (get-story/get-docs-markdown on absent ' +
        'id) -> isError + "not found" (tool wired, envelope shape pinned)',
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
    const regStruct = structured(regResp);
    // Pin the SINGLE canonical key story-mcp emits: `:registered?`
    // (Cheshire keeps the `?` on the keyword → JSON key `registered?`;
    // pinned JVM-side by tools_test.clj `(-> reg :structuredContent
    // :registered?)`). The earlier `registered || registered?`
    // alternation tolerated two spellings — masking exactly the
    // envelope-key drift the rest of this harness pins single-spelling
    // (`unregistered?`, `passing?`, `content-hash`). rf2-ee38b.20.
    if (regStruct['registered?'] !== true) {
      throw new Error(
        "register-variant did not report :registered? true: " + JSON.stringify(regResp),
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

    // 4b. variant->edn (rf2-ke5n56). Advertised but never SDK-called —
    // and it is the EXACT latent-defect twin of get-story-instructions:
    // it declares an :outputSchema, so the SDK's high-level callTool
    // REJECTS a result with no structuredContent (-32600); rf2-vyacl
    // notes variant->edn was the only OTHER tool still text-only before
    // the fix. Driving it against the live fixture pins that the fix
    // stays fixed across the wire. The text slot is the byte-stable
    // pr-str EDN; assert the fixture :doc round-trips through it AND that
    // a JSON-object structuredContent rides alongside (the slot the SDK
    // outputSchema parse demands).
    const ednResp = await client.callTool({
      name: 'variant->edn',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (ednResp.isError) {
      throw new Error('variant->edn on fixture failed: ' + JSON.stringify(ednResp));
    }
    const ednText = ednResp.content?.[0]?.text || '';
    if (!/MCP-conformance harness probe variant\./.test(ednText)) {
      throw new Error(
        'variant->edn text payload missing :doc; got: ' + ednText.slice(0, 200),
      );
    }
    if (ednResp.structuredContent === undefined || ednResp.structuredContent === null ||
        typeof ednResp.structuredContent !== 'object' ||
        Array.isArray(ednResp.structuredContent)) {
      throw new Error(
        'variant->edn MUST carry a JSON-object :structuredContent slot ' +
          '(the SDK outputSchema parse demands it — rf2-vyacl latent-defect ' +
          'class); got: ' + JSON.stringify(ednResp),
      );
    }
    console.log('OK   variant->edn -> :doc round-trips through EDN text + structuredContent present');

    // 4c. run-a11y (rf2-ke5n56). Advertised but never SDK-called. The
    // axe-core run is CLJS-in-browser only; from this JVM-standalone boot
    // the tool READS the (empty) violations atom and returns a success
    // envelope with `:violations []` + a JVM-standalone `:note` — so it
    // is a cheap, fixture-backed, runtime-free success probe (NOT a live
    // browser dependency). Pins the callTool envelope + structuredContent
    // shape for the testing-category read against the registered fixture.
    const a11yResp = await client.callTool({
      name: 'run-a11y',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (a11yResp.isError) {
      throw new Error('run-a11y on fixture failed: ' + JSON.stringify(a11yResp));
    }
    const a11yStruct = structured(a11yResp);
    if (!Array.isArray(a11yStruct.violations)) {
      throw new Error(
        'run-a11y :violations MUST be an array (empty on a JVM-standalone ' +
          'boot); got: ' + JSON.stringify(a11yResp),
      );
    }
    if (a11yStruct.violations.length !== 0) {
      throw new Error(
        'run-a11y :violations expected empty on a JVM-standalone boot (no ' +
          'in-browser axe-core run); got: ' + JSON.stringify(a11yResp),
      );
    }
    console.log('OK   run-a11y -> :violations=[] (JVM-standalone read, envelope pinned)');

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
    const prevStruct = structured(prevResp);
    if (!('lifecycle' in prevStruct)) {
      throw new Error(
        'preview-variant structuredContent missing :lifecycle: ' + JSON.stringify(prevResp),
      );
    }
    console.log('OK   preview-variant -> lifecycle=' + prevStruct.lifecycle);

    // 6. run-variant — exercise lifecycle; vacuous pass since :script
    // is empty. The UNIFIED run-result (rf2-ba86n.17 clean break) returns
    // the top-level :status verdict + unified :assertions records (each
    // with a :status) + :checks — NOT the retired :passing? boolean.
    const runResp = await client.callTool({
      name: 'run-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (runResp.isError) {
      throw new Error('run-variant failed: ' + JSON.stringify(runResp));
    }
    const runStruct = structured(runResp);
    if (!Array.isArray(runStruct.assertions)) {
      throw new Error('run-variant :assertions not an array: ' + JSON.stringify(runResp));
    }
    if (!Array.isArray(runStruct.checks)) {
      throw new Error('run-variant :checks not an array: ' + JSON.stringify(runResp));
    }
    if (runStruct.status !== 'pass') {
      throw new Error(
        'run-variant :status expected "pass" (vacuous pass); got: ' + JSON.stringify(runResp),
      );
    }
    if ('passing?' in runStruct) {
      throw new Error(
        'run-variant must NOT carry the retired :passing? boolean (clean break); got: ' +
          JSON.stringify(runResp),
      );
    }
    console.log('OK   run-variant -> :status="pass", assertions=[], checks=[] (vacuous pass)');

    // 7. read-failures — zero failures expected after a vacuous-pass
    // run; the run :status surfaces on the unified shape (not :passing?).
    const failResp = await client.callTool({
      name: 'read-failures',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (failResp.isError) {
      throw new Error('read-failures failed: ' + JSON.stringify(failResp));
    }
    const failStruct = structured(failResp);
    if (failStruct.total !== 0) {
      throw new Error('read-failures :total expected 0; got: ' + JSON.stringify(failResp));
    }
    if (!Array.isArray(failStruct.failures) || failStruct.failures.length !== 0) {
      throw new Error(
        'read-failures :failures expected empty vector; got: ' + JSON.stringify(failResp),
      );
    }
    if (failStruct.status !== 'pass') {
      throw new Error(
        'read-failures :status expected "pass" (vacuous); got: ' + JSON.stringify(failResp),
      );
    }
    if ('passing?' in failStruct) {
      throw new Error(
        'read-failures must NOT carry the retired :passing? boolean (clean break); got: ' +
          JSON.stringify(failResp),
      );
    }
    // rf2-koq5m — egress indicator omit-when-zero MUST: a clean read
    // (no sensitive records dropped, nothing elided) carries NEITHER
    // `:dropped-sensitive` nor `:elided-large`. The positive-count side
    // is pinned by the JVM `tools_test.clj` indicator deftests; the live
    // SDK driver pins the omit-when-zero contract end-to-end.
    if ('dropped-sensitive' in failStruct || 'elided-large' in failStruct) {
      throw new Error(
        'read-failures clean read must OMIT indicator slots when zero ' +
          '(Conventions §Cross-MCP indicator-field vocabulary, rf2-koq5m); got: ' +
          JSON.stringify(failResp),
      );
    }
    console.log(
      'OK   read-failures -> total=0, failures=[], :status="pass", indicators omitted (omit-when-zero)',
    );

    // 7b. explain-variant — the agent mirror of the human Explain panel
    // (rf2-ba86n.17). Assert the source-chain / merge / runner-requirement
    // slots round-trip — a structuredContent shape pin, exactly like the
    // existing reads. New net-additive Docs tool over story/explain.
    const explainResp = await client.callTool({
      name: 'explain-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (explainResp.isError) {
      throw new Error('explain-variant failed: ' + JSON.stringify(explainResp));
    }
    const explainStruct = structured(explainResp);
    const explain = explainStruct.explain;
    if (!explain || typeof explain !== 'object') {
      throw new Error(
        'explain-variant :explain not an object: ' + JSON.stringify(explainResp),
      );
    }
    if (!Array.isArray(explain['source-chain']) || explain['source-chain'].length === 0) {
      throw new Error(
        'explain-variant :explain missing :source-chain: ' + JSON.stringify(explainResp),
      );
    }
    if (typeof explain.merge !== 'object' || !('required-runner' in explain)) {
      throw new Error(
        'explain-variant :explain missing :merge / :required-runner slots: ' +
          JSON.stringify(explainResp),
      );
    }
    console.log('OK   explain-variant -> source-chain + merge + required-runner round-trip');

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
    const h1 = structured(snap1)['content-hash'];
    const h2 = structured(snap2)['content-hash'];
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
    const recStruct = structured(recResp);
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
    const unregStruct = structured(unregResp);
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

    // 12. callTool() coverage ratchet (rf2-ke5n56). Every advertised
    // story-mcp tool MUST have been driven through `Client.callTool()`
    // above (recorded in `called` by the `track` proxy) or carry a
    // reviewed exclusion in `STORY_CALL_EXCLUSIONS`. A NEW advertised
    // tool that the workflow forgets to probe trips RED here — closing
    // the descriptor-only false-green the senior review flagged.
    assertCallCoverageRatchet({
      advertised: names,
      called,
      exclusions: STORY_CALL_EXCLUSIONS,
    });
    console.log(
      'OK   callTool coverage ratchet -> every advertised tool SDK-called ' +
        '(' + called.size + '/' + names.length + ') or reviewed-excluded',
    );

    // 13. Clean disconnect — runner handles client.close() on success.
    console.log('\nSTORY-MCP MCP-CLIENT CONFORMANCE GREEN');
  },
);
