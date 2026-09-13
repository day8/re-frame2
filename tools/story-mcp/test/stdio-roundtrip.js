// Stdio integration test for tools/story-mcp.
//
// Spawns the JVM story-mcp server via
// `clojure -M -i test/fixtures/stdio_prelude.clj -m re-frame.story-mcp.server`
// (the README's preload launch shape — the prelude installs plain-atom and
// registers a small fixture story) and walks the MCP handshake against it:
//
//   - initialize                  (negotiate protocolVersion 2025-06-18)
//   - notifications/initialized   (notification, no response)
//   - tools/list                  (expect the canonical fixture from spec/002)
//   - tools/call list-tags        (read-side smoke; seven inclusion tags present in canonical set)
//   - tools/call list-substrates  (expect capability-unavailable error — the
//                                  substrate registry is CLJS-only and
//                                  unreachable from this JVM host, and a
//                                  false-empty `[]` success is the regression
//                                  this pins against)
//   - tools/call get-story-instructions (text content)
//   - tools/call preview-variant on an unregistered variant
//                                 (expect tool-execution error — proves the
//                                  tool is wired into the dispatch table)
//   - tools/call get-variant on an unregistered variant
//                                 (same shape — wires to dispatch table)
//   - tools/call register-variant without --allow-writes
//                                 (expect gated tool-execution error — proves
//                                  the gating contract per spec/003)
//   - tools/call no-such-tool     (expect method-not-found protocol error)
//   - tools/call run-variant on the prelude's REGISTERED variants, whose
//                                 handlers println after the server is up
//                                 (expect every stdout line to parse as JSON,
//                                  each marker on stderr only, the run
//                                  verdicts intact — rf2-gwye.57)
//   - ping                        (empty result, liveness probe)
//   - close stdin                 (expect the server to exit 0 ON ITS OWN
//                                  within a short bound — no kill on the
//                                  success path, rf2-gwye.59)
//
// The representative agent-loop workflow against a running server with
// --allow-writes enabled lives in the SDK-driven conformance harness
// tools/mcp-conformance/test/end-to-end-story.cjs (rf2-2mx0q absorbed
// the former live-server.js smokes there to drop a redundant JVM boot).
//
// Run with: `node test/stdio-roundtrip.js` from tools/story-mcp/. Exits 0 on
// success, 1 with a FAIL marker on the failing assertion.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CWD = path.join(__dirname, '..');

// Canonical tool-name list (rf2-36upq TE7) — single source of truth shared
// with the JVM test corpus (tools_test.clj `tool-names-fixture`). Both
// consumers parse this JSON; a drift in the registry surfaces in one
// place rather than two.
const TOOL_NAMES = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'tool-names.json'), 'utf8'),
).names;

// The agent-host canonical launch (per tools/story-mcp/README.md). On
// Linux CI the `clojure` binary lands via DeLaGuardo/setup-clojure; on
// Windows it's an .exe on PATH (the GitHub-hosted runner provides it).
// We spawn the binary directly — no shell wrap — so Node 24+'s
// shell-with-args deprecation (DEP0190) stays quiet.
const CLOJURE = process.env.STORY_MCP_CMD || 'clojure';
const ARGS = ['-M', '-i', 'test/fixtures/stdio_prelude.clj', '-m', 're-frame.story-mcp.server'];

// Application output markers the prelude's handlers print under the server's
// own dispatch (rf2-gwye.57). Each must reach stderr and never stdout.
const APP_OUTPUT_MARKERS = [
  'STDIO-FIXTURE-SETUP-PRINT',
  'STDIO-FIXTURE-SCRIPT-PRINT',
  'STDIO-FIXTURE-THROW-PRINT',
];

// After the last reply the harness CLOSES stdin and waits for the server to
// exit on its own (rf2-gwye.59). A JVM that has released its executors exits
// in well under a second; before that fix a session that ran a variant idled
// out Clojure's 60 s executor keep-alive, so this bound separates the two
// with room to spare on a slow runner.
const EXIT_AFTER_EOF_BOUND_MS = 10000;

function run() {
  return new Promise((resolve, reject) => {
    const env = { ...process.env };
    const child = spawn(CLOJURE, ARGS, {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: CWD,
      env,
    });
    let stderrText = '';
    child.stderr.on('data', (d) => {
      stderrText += d.toString();
      process.stderr.write('[server] ' + d.toString());
    });
    // Registered up front so the exit is observed however early it happens.
    const exited = new Promise((r) =>
      child.on('close', (code, signal) => r({ code, signal, at: Date.now() })),
    );

    let next = 1;
    const pending = new Map();
    let buf = '';
    let rawStdout = '';
    child.stdout.on('data', (chunk) => {
      rawStdout += chunk.toString('utf8');
      buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        try {
          const f = JSON.parse(line);
          if (f.id != null && pending.has(f.id)) {
            pending.get(f.id)(f);
            pending.delete(f.id);
          }
        } catch (e) {
          console.error('FAIL: malformed JSON on stdout:', line);
          child.kill();
          reject(e);
        }
      }
    });
    const call = (m, p) => {
      const id = next++;
      return new Promise((r) => {
        pending.set(id, r);
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method: m, params: p }) + '\n');
      });
    };
    const notify = (m, p) =>
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: m, params: p }) + '\n');

    // Watchdog: kill the child if assertions take longer than 60s. Clojure
    // boot on a cold CI runner can take 10-20s; 60s is comfortably above
    // that without leaving a hung process.
    const watchdog = setTimeout(() => {
      console.error('FAIL: watchdog timeout (60s) — server did not respond');
      child.kill();
      reject(new Error('watchdog timeout'));
    }, 60000);

    (async () => {
      // 1. initialize handshake. The JVM boot needs a moment; the first
      // call's response will land whenever the server hits its read loop.
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'roundtrip', version: '0' },
      });
      if (!init.result?.protocolVersion) {
        throw new Error('initialize failed: ' + JSON.stringify(init));
      }
      console.log('OK   initialize ->', init.result.serverInfo);

      notify('notifications/initialized', {});

      // 2. tools/list — expect the full registry per spec/002-Tool-Registry.md
      // (rf2-mqp1u list-decorators,
      //  rf2-i0kyy get-docs-markdown). The canonical name list is shared
      // with the JVM test corpus via test/fixtures/tool-names.json
      // (rf2-36upq TE7) — a registry change updates one file, not two.
      const list = await call('tools/list', {});
      const names = (list.result?.tools || []).map((t) => t.name).sort();
      if (JSON.stringify(names) !== JSON.stringify(TOOL_NAMES)) {
        throw new Error(
          'tools/list mismatch:\n  expected ' +
            JSON.stringify(TOOL_NAMES) +
            '\n  got      ' +
            JSON.stringify(names),
        );
      }
      console.log('OK   tools/list -> ' + TOOL_NAMES.length + ' tools:', names.join(', '));

      // 2b. Verify the preview-variant descriptor: required `variant-id`,
      // optional `substrate`/`active-modes`/`cell-overrides`/`base-url`.
      const previewDesc = (list.result?.tools || []).find((t) => t.name === 'preview-variant');
      if (!previewDesc) throw new Error('preview-variant descriptor missing from tools/list');
      const previewProps = previewDesc.inputSchema?.properties || {};
      for (const k of ['variant-id', 'substrate', 'active-modes', 'cell-overrides', 'base-url']) {
        if (!(k in previewProps)) {
          throw new Error('preview-variant inputSchema missing property: ' + k);
        }
      }
      if (!previewDesc.inputSchema?.required?.includes('variant-id')) {
        throw new Error('preview-variant.inputSchema missing required: variant-id');
      }
      console.log('OK   preview-variant descriptor -> variant-id (required) + substrate/active-modes/cell-overrides/base-url');

      // 2c. Verify the register-variant descriptor: required
      // `variant-id` + `body`. This is the write-gated tool per spec/003;
      // its descriptor is advertised whether the gate is open or not.
      const regDesc = (list.result?.tools || []).find((t) => t.name === 'register-variant');
      if (!regDesc) throw new Error('register-variant descriptor missing from tools/list');
      const regReq = regDesc.inputSchema?.required || [];
      for (const k of ['variant-id', 'body']) {
        if (!regReq.includes(k)) {
          throw new Error('register-variant.inputSchema missing required: ' + k);
        }
      }
      console.log('OK   register-variant descriptor -> variant-id + body required');

      // 2d. record-as-variant is RETIRED (rf2-5saz7): the blocking recorder
      // bridge advertised a capture window no stdio client could drive (the
      // single dispatch loop slept through it). The fixture-equality check
      // above already excludes it; this explicit probe keeps the absence
      // loud if the fixture and registry ever drift back in lockstep.
      const recDesc = (list.result?.tools || []).find((t) => t.name === 'record-as-variant');
      if (recDesc) throw new Error('record-as-variant was retired (rf2-5saz7) but is advertised in tools/list');
      console.log('OK   record-as-variant -> retired, absent from tools/list (rf2-5saz7)');

      // 3. tools/call list-tags — read-side smoke; the seven inclusion
      // tags must be present among the 12-entry canonical set (7 inclusion
      // + 5 :state/* magnitude tags), loaded by
      // `install-canonical-vocabulary!` in server `boot!`.
      const tagsResp = await call('tools/call', { name: 'list-tags', arguments: {} });
      if (tagsResp.result?.isError) {
        throw new Error('list-tags returned isError: ' + JSON.stringify(tagsResp));
      }
      const tagsStructured = tagsResp.result?.structuredContent;
      const canonical = (tagsStructured?.canonical || []).map(String);
      // Cheshire's `generate-string` writes keywords as bare names without
      // the leading colon — `:dev` arrives on the wire as `"dev"`.
      for (const tag of ['dev', 'docs', 'test', 'screenshot', 'experimental', 'internal', 'agent']) {
        if (!canonical.includes(tag)) {
          throw new Error('list-tags missing canonical tag ' + tag + '; got ' + JSON.stringify(canonical));
        }
      }
      console.log('OK   tools/call list-tags -> seven inclusion tags present in canonical set:', canonical.join(', '));

      // 3b. tools/call list-substrates — the substrate registry is CLJS-only
      // and UNREACHABLE from this JVM stdio host (no browser bridge), so the
      // truthful result is a machine-readable capability-unavailable ERROR,
      // NOT a false-empty `{:substrates []}` success (rf2-3fc89f.21). Pin
      // the isError verdict + the stable error id so a regression to the old
      // false-empty behaviour turns this round-trip RED.
      const subsResp = await call('tools/call', { name: 'list-substrates', arguments: {} });
      if (!subsResp.result?.isError) {
        throw new Error(
          'list-substrates on a JVM host MUST return isError (the substrate ' +
            'registry is unreachable — not a false-empty success); got: ' +
            JSON.stringify(subsResp),
        );
      }
      const subsErr = subsResp.result?.structuredContent?.['rf.error'];
      if (subsErr !== 'rf.error/story-mcp-capability-unavailable') {
        throw new Error(
          'list-substrates error MUST carry the stable capability-unavailable ' +
            'id; got: ' + JSON.stringify(subsResp),
        );
      }
      if ('substrates' in (subsResp.result?.structuredContent || {})) {
        throw new Error(
          'list-substrates capability error MUST NOT carry a false-empty ' +
            ':substrates slot; got: ' + JSON.stringify(subsResp),
        );
      }
      console.log('OK   tools/call list-substrates -> capability-unavailable error (no false-empty)');

      // 3c. tools/call get-story-instructions — returns the agent-onboarding
      // text. Smoke-check key authoring vocab is present AND that the
      // result carries structuredContent (rf2-vyacl): the descriptor
      // declares an :outputSchema, so an SDK-driven consumer rejects a
      // text-only result with -32600. Pinning the structured slot here
      // catches a regression to the text-only shape at the wire.
      const instrResp = await call('tools/call', { name: 'get-story-instructions', arguments: {} });
      const instrText = instrResp.result?.content?.[0]?.text || '';
      if (instrResp.result?.isError || !instrText.includes('reg-story')) {
        throw new Error('get-story-instructions did not contain `reg-story`: ' + JSON.stringify(instrResp).slice(0, 300));
      }
      const instrStruct = instrResp.result?.structuredContent;
      if (!instrStruct || typeof instrStruct !== 'object' || typeof instrStruct.instructions !== 'string') {
        throw new Error('get-story-instructions missing structuredContent.instructions (SDK -32600 risk): ' + JSON.stringify(instrResp).slice(0, 300));
      }
      console.log('OK   tools/call get-story-instructions -> text + structuredContent.instructions');

      // 4. tools/call preview-variant on an unknown variant — expect
      // tool-execution error (`isError: true`) with a "not found" message.
      // This proves the tool is wired into the dispatch table; in degraded
      // states the response would carry a different shape.
      const prevResp = await call('tools/call', {
        name: 'preview-variant',
        arguments: { 'variant-id': 'story.no-such-fixture/missing' },
      });
      const prevText = prevResp.result?.content?.[0]?.text || '';
      if (!prevResp.result?.isError || !/not found/i.test(prevText)) {
        throw new Error('preview-variant on missing variant did not yield isError + not-found; got: ' + JSON.stringify(prevResp));
      }
      console.log('OK   tools/call preview-variant (missing) -> isError + not-found');

      // 4b. tools/call get-variant on an unknown variant — same shape.
      const getVResp = await call('tools/call', {
        name: 'get-variant',
        arguments: { 'variant-id': 'story.no-such-fixture/missing' },
      });
      const getVText = getVResp.result?.content?.[0]?.text || '';
      if (!getVResp.result?.isError || !/not found/i.test(getVText)) {
        throw new Error('get-variant on missing variant did not yield isError + not-found; got: ' + JSON.stringify(getVResp));
      }
      console.log('OK   tools/call get-variant (missing) -> isError + not-found');

      // 5. tools/call register-variant without --allow-writes — the
      // server boots with the gate closed by default; the write tool must
      // surface a gated tool-execution error.
      const regResp = await call('tools/call', {
        name: 'register-variant',
        arguments: {
          'variant-id': 'story.gated/probe',
          body: { doc: 'probe' },
        },
      });
      const regText = regResp.result?.content?.[0]?.text || '';
      if (!regResp.result?.isError || !/Write surface disabled/.test(regText)) {
        throw new Error('register-variant gated path expected; got: ' + JSON.stringify(regResp));
      }
      const regStruct = regResp.result?.structuredContent;
      if (!regStruct || regStruct.gated !== true) {
        throw new Error('register-variant gated result missing structuredContent.gated=true: ' + JSON.stringify(regResp));
      }
      console.log('OK   tools/call register-variant (gated) -> isError + structuredContent.gated=true');

      // 6. tools/call no-such-tool — unknown tool name; the dispatcher
      // surfaces a protocol-level method-not-found (-32601) per
      // server.cljc's `handle-tools-call`.
      const unkResp = await call('tools/call', {
        name: 'no-such-tool',
        arguments: {},
      });
      if (unkResp.error?.code !== -32601) {
        throw new Error('no-such-tool should yield method-not-found (-32601); got: ' + JSON.stringify(unkResp));
      }
      console.log('OK   tools/call no-such-tool -> -32601 method-not-found');

      // 6b. tools/call run-variant on REGISTERED variants whose handlers
      // println (rf2-gwye.57). stdout is the protocol stream, so application
      // output emitted under the server's dispatch must go to stderr. The line
      // parser above fails the run on any non-JSON stdout line; these calls are
      // what give it something to catch. `quiet` is the control, and the
      // print-then-throw handler proves a failing run is contained too.
      const runVariant = (variantId) =>
        call('tools/call', {
          name: 'run-variant',
          arguments: { 'variant-id': variantId, dedup: false, 'max-tokens': 0 },
        });
      for (const [variantId, ran] of [
        ['story.stdio-fixture/quiet', 'quiet'],
        ['story.stdio-fixture/setup-print', 'setup'],
        ['story.stdio-fixture/script-print', 'script'],
      ]) {
        const r = await runVariant(variantId);
        const s = r.result?.structuredContent;
        if (r.error || r.result?.isError || s?.status !== 'pass' || s?.['app-db']?.ran !== ran) {
          throw new Error(
            'run-variant ' + variantId + ' should pass with app-db.ran=' + ran + '; got: ' +
              JSON.stringify(r).slice(0, 600),
          );
        }
      }
      const thrownResp = await runVariant('story.stdio-fixture/throw-print');
      const thrownStatus = thrownResp.result?.structuredContent?.status;
      if (thrownResp.error || thrownResp.result?.isError || thrownStatus === 'pass' || !thrownStatus) {
        throw new Error(
          'run-variant on a throwing handler should return a non-pass run verdict; got: ' +
            JSON.stringify(thrownResp).slice(0, 600),
        );
      }
      console.log('OK   tools/call run-variant (registered, printing handlers) -> verdicts intact (throwing handler -> ' + thrownStatus + ')');

      // 7. ping — empty result, MCP §Utilities/ping liveness probe.
      const pingResp = await call('ping', {});
      if (pingResp.result === undefined || Object.keys(pingResp.result || {}).length !== 0) {
        throw new Error('ping should return empty {}; got: ' + JSON.stringify(pingResp));
      }
      console.log('OK   ping -> empty result {}');

      for (const marker of APP_OUTPUT_MARKERS) {
        if (rawStdout.includes(marker)) {
          throw new Error('application output ' + marker + ' reached stdout (the protocol stream)');
        }
        if (!stderrText.includes(marker)) {
          throw new Error('application output ' + marker + ' never reached stderr');
        }
      }
      console.log('OK   application output -> stderr only; every stdout line parsed as JSON');

      // 8. Close stdin and let the server exit ON ITS OWN — no kill on this
      // path (rf2-gwye.59). The session above ran variants on Clojure's
      // future executor; the CLI must release it at EOF rather than idle out
      // its keep-alive.
      clearTimeout(watchdog);
      const eofAt = Date.now();
      child.stdin.end();
      const exit = await Promise.race([
        exited,
        new Promise((r) => setTimeout(() => r(null), EXIT_AFTER_EOF_BOUND_MS)),
      ]);
      if (exit === null) {
        throw new Error(
          'server still running ' + EXIT_AFTER_EOF_BOUND_MS + ' ms after stdin EOF ' +
            '(a session that ran variants must release its executors and exit)',
        );
      }
      if (exit.code !== 0) {
        throw new Error('server exited ' + exit.code + ' (signal ' + exit.signal + ') after stdin EOF; expected 0');
      }
      console.log('OK   stdin EOF -> server exited 0 on its own in ' + (exit.at - eofAt) + ' ms');

      console.log('\nSTORY-MCP STDIO ROUND-TRIP GREEN');
      resolve();
    })().catch((e) => {
      clearTimeout(watchdog);
      child.kill();
      reject(e);
    });
  });
}

run().then(() => process.exit(0)).catch((e) => {
  console.error('FAIL:', e.message);
  process.exit(1);
});
