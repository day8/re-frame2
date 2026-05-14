// Live story-mcp server integration test — drives a representative
// agent-loop scenario through a single running JVM process with the
// write surface enabled.
//
// Story-MCP is JVM-side (a Clojure subprocess speaking JSON-RPC on
// stdio). Unlike pair2-mcp's live-nrepl.js (which connects out to a
// separate shadow-cljs runtime over bencode), this artefact is
// self-contained — the same process that terminates stdin/stdout also
// holds the Story registrar, so a single boot suffices for the entire
// register → run → read → refine loop.
//
// Scenario (per IMPL-SPEC §7.2 + the self-healing-loop motif from rf2-h8z5l):
//
//   1. initialize handshake
//   2. tools/list — confirm 19 tools advertised
//   3. register-variant — write a fixture variant (gate open via
//                          --allow-writes)
//   4. get-variant — read it back; assert the body round-trips
//   5. preview-variant — exercise the run-variant lifecycle (loaders →
//                        events → render → play); expect a passing run
//                        (no assertions ⇒ vacuously passing)
//   6. run-variant — same lifecycle via the testing-category tool; assert
//                    the result map shape (:frame :assertions :passing?)
//   7. read-failures — zero accumulated failures expected
//   8. snapshot-identity — stable content-hash twice in a row
//   9. record-as-variant — zero-duration capture (empty :play snippet);
//                          proves the recorder bridge is wired (rf2-luhdu)
//  10. unregister-variant — tear the fixture variant back down; assert
//                            it's gone from the read surface
//
// Run with: `node test/live-server.js` from tools/story-mcp/. Exits 0 on
// success.

const { spawn } = require('node:child_process');
const path = require('node:path');

const CWD = path.join(__dirname, '..');
const CLOJURE = process.env.STORY_MCP_CMD || 'clojure';
const ARGS = ['-M', '-m', 're-frame.story-mcp.server', '--allow-writes'];

// A fixture variant id we can safely create + tear down; namespaced under
// `story.live-server` so it won't collide with anything the
// canonical-vocabulary install registers.
const FIXTURE_VARIANT = 'story.live-server/probe.primary';

function run() {
  return new Promise((resolve, reject) => {
    const env = { ...process.env };
    const child = spawn(CLOJURE, ARGS, {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: CWD,
      env,
    });
    child.stderr.on('data', (d) => process.stderr.write('[server] ' + d.toString()));

    let next = 1;
    const pending = new Map();
    let buf = '';
    child.stdout.on('data', (chunk) => {
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

    // Watchdog — generous because we cover multiple `run-variant`
    // executions and Clojure boot is slow on a cold CI worker.
    const watchdog = setTimeout(() => {
      console.error('FAIL: watchdog timeout (90s) — workflow stalled');
      child.kill();
      reject(new Error('watchdog timeout'));
    }, 90000);

    (async () => {
      // 1. initialize
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'live-server', version: '0' },
      });
      if (!init.result?.protocolVersion) {
        throw new Error('initialize failed: ' + JSON.stringify(init));
      }
      console.log('OK   initialize ->', init.result.serverInfo);
      notify('notifications/initialized', {});

      // 2. tools/list — 19 tools advertised (post-rf2-mqp1u list-decorators
      // and rf2-i0kyy get-docs-markdown additions).
      const list = await call('tools/list', {});
      const tools = list.result?.tools || [];
      if (tools.length !== 19) {
        throw new Error('expected 19 tools; got ' + tools.length);
      }
      console.log('OK   tools/list -> ' + tools.length + ' tools advertised');

      // 3. register-variant — write the fixture variant. The MCP write
      // surface exposes ONLY `register-variant`; there is no parallel
      // `register-story` tool. Story's registrar accepts orphan variants
      // in dev mode (per tools/story tests `register-variant-happy-when-allowed`)
      // so we go straight to the variant registration.
      //
      // The `:body` parameter accepts either a structured object or an
      // EDN string per tools.cljc's `tool-register-variant` — we use the
      // EDN string so JSON's lack of keyword support doesn't dilute the
      // assertion (Cheshire would coerce {"doc": "..."} into a
      // string-keyed map, which Story's registrar would reject).
      const variantBodyEdn =
        '{:doc "Primary live-server probe variant."' +
        ' :args {:label "OK"}' +
        ' :tags #{:dev}}';
      const regVResp = await call('tools/call', {
        name: 'register-variant',
        arguments: {
          'variant-id': FIXTURE_VARIANT,
          body: variantBodyEdn,
        },
      });
      if (regVResp.result?.isError) {
        throw new Error('register-variant for fixture failed: ' + JSON.stringify(regVResp));
      }
      const regVStruct = regVResp.result?.structuredContent || {};
      if (regVStruct.registered !== true && regVStruct['registered?'] !== true) {
        throw new Error('register-variant did not report :registered? true: ' + JSON.stringify(regVResp));
      }
      console.log('OK   register-variant -> ' + FIXTURE_VARIANT + ' registered');

      // 4. get-variant — round-trip the body. Cheshire coerces keys to
      // strings on the wire, so the comparison reads the :doc back from
      // the text payload (EDN-encoded, keyword keys preserved).
      const getResp = await call('tools/call', {
        name: 'get-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (getResp.result?.isError) {
        throw new Error('get-variant after register failed: ' + JSON.stringify(getResp));
      }
      const getText = getResp.result?.content?.[0]?.text || '';
      if (!/Primary live-server probe variant\./.test(getText)) {
        throw new Error('get-variant text payload missing :doc; got: ' + getText.slice(0, 200));
      }
      console.log('OK   get-variant -> body :doc round-trips through EDN text');

      // 5. preview-variant — exercise the full lifecycle. With no :play
      // events the run is vacuously passing.
      const prevResp = await call('tools/call', {
        name: 'preview-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (prevResp.result?.isError) {
        throw new Error('preview-variant on fixture failed: ' + JSON.stringify(prevResp));
      }
      const prevStruct = prevResp.result?.structuredContent || {};
      if (!('lifecycle' in prevStruct)) {
        throw new Error('preview-variant structuredContent missing :lifecycle: ' + JSON.stringify(prevResp));
      }
      console.log('OK   preview-variant -> lifecycle=' + prevStruct.lifecycle);

      // 6. run-variant — same lifecycle via the testing-category tool.
      // Assert the result-map shape (:assertions vector, :passing? true).
      const runResp = await call('tools/call', {
        name: 'run-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (runResp.result?.isError) {
        throw new Error('run-variant on fixture failed: ' + JSON.stringify(runResp));
      }
      const runStruct = runResp.result?.structuredContent || {};
      if (!Array.isArray(runStruct.assertions)) {
        throw new Error('run-variant :assertions not an array: ' + JSON.stringify(runResp));
      }
      // `passing?` arrives as a top-level key whose JSON name preserves the
      // question-mark suffix.
      if (runStruct['passing?'] !== true) {
        throw new Error('run-variant :passing? expected true (no assertions ⇒ vacuous); got: ' + JSON.stringify(runResp));
      }
      console.log('OK   run-variant -> :passing?=true, assertions=[] (vacuous pass)');

      // 7. read-failures — no assertions ran, so zero failures.
      const failResp = await call('tools/call', {
        name: 'read-failures',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (failResp.result?.isError) {
        throw new Error('read-failures on fixture failed: ' + JSON.stringify(failResp));
      }
      const failStruct = failResp.result?.structuredContent || {};
      if (failStruct.total !== 0) {
        throw new Error('read-failures :total expected 0; got: ' + JSON.stringify(failResp));
      }
      if (!Array.isArray(failStruct.failures) || failStruct.failures.length !== 0) {
        throw new Error('read-failures :failures expected empty vector; got: ' + JSON.stringify(failResp));
      }
      console.log('OK   read-failures -> total=0, failures=[]');

      // 8. snapshot-identity — same args ⇒ same content-hash.
      const snap1 = await call('tools/call', {
        name: 'snapshot-identity',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      const snap2 = await call('tools/call', {
        name: 'snapshot-identity',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (snap1.result?.isError || snap2.result?.isError) {
        throw new Error('snapshot-identity errored: ' + JSON.stringify(snap1) + ' / ' + JSON.stringify(snap2));
      }
      const h1 = snap1.result?.structuredContent?.['content-hash'];
      const h2 = snap2.result?.structuredContent?.['content-hash'];
      if (!h1 || h1 !== h2) {
        throw new Error('snapshot-identity content-hash not stable: ' + h1 + ' vs ' + h2);
      }
      console.log('OK   snapshot-identity -> stable hash: ' + h1);

      // 9. record-as-variant — zero-duration capture; empty :play snippet.
      // Proves the recorder bridge (rf2-luhdu) is wired into dispatch.
      const recResp = await call('tools/call', {
        name: 'record-as-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (recResp.result?.isError) {
        throw new Error('record-as-variant zero-duration failed: ' + JSON.stringify(recResp));
      }
      const recStruct = recResp.result?.structuredContent || {};
      if (recStruct['recorded-event-count'] !== 0) {
        throw new Error('record-as-variant :recorded-event-count expected 0; got: ' + JSON.stringify(recResp));
      }
      if (typeof recStruct['play-snippet'] !== 'string') {
        throw new Error('record-as-variant :play-snippet missing/non-string: ' + JSON.stringify(recResp));
      }
      console.log('OK   record-as-variant -> recorded-event-count=0, play-snippet emitted');

      // 10. unregister-variant — symmetric tear-down. After this the
      // variant must no longer be reachable through the read surface.
      const unregResp = await call('tools/call', {
        name: 'unregister-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (unregResp.result?.isError) {
        throw new Error('unregister-variant failed: ' + JSON.stringify(unregResp));
      }
      const unregStruct = unregResp.result?.structuredContent || {};
      if (unregStruct['unregistered?'] !== true) {
        throw new Error('unregister-variant :unregistered? expected true: ' + JSON.stringify(unregResp));
      }
      // Read-back must now fail.
      const verify = await call('tools/call', {
        name: 'get-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
      if (!verify.result?.isError || !/not found/i.test(verify.result?.content?.[0]?.text || '')) {
        throw new Error('after unregister, get-variant should yield not-found; got: ' + JSON.stringify(verify));
      }
      console.log('OK   unregister-variant -> :unregistered?=true; subsequent get-variant -> not-found');

      clearTimeout(watchdog);
      child.kill();
      console.log('\nSTORY-MCP LIVE-SERVER WORKFLOW GREEN');
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
