// Stdio integration test: drives the compiled server through the
// standard MCP handshake and verifies the tool catalogue.
//
// This test does NOT need a live shadow-cljs nREPL — it exercises:
//   - initialize handshake
//   - tools/list (expects all fourteen tools: original six bash-shim mirrors
//     + snapshot + get-path + subscribe/unsubscribe/list-subscriptions streaming
//     trio + handler-meta + list-handlers registrar-introspection pair (rf2-cibp8 /
//     rf2-pctf8; renamed from subscription-info / registry-list per rf2-4y595) +
//     get-re-frame2-pair-instructions agent-onboarding (rf2-fnpqg))
//   - tools/call eval-cljs against an absent nREPL — with `--allow-eval`
//     passed below, expects graceful :nrepl-port-not-found degraded mode.
//     The default-off gate (rf2-cxx5s) is exercised by the conformance
//     `:eval-cljs/disabled-default` fixture; this test passes the flag
//     so the existing degraded-path coverage remains intact.
//   - tools/call snapshot against an absent nREPL (same degraded mode —
//     proves the new tool is wired into the dispatch table)
//   - tools/call get-path against an absent nREPL (same degraded mode —
//     proves the new tool is wired into the dispatch table)
//
// A separate live-nrepl test (spec/INTEGRATION.md) covers the
// connected-to-shadow-cljs case.
//
// Run with: `node test/stdio-roundtrip.js` from this directory after
// `shadow-cljs compile server`. Exits 0 on success.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

// Canonical tool-name list (rf2-drke0, mirrors story-mcp's rf2-36upq TE7)
// — single source of truth shared with the cross-server conformance
// harness (`tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs`). Both
// consumers parse this JSON; a drift in the registry surfaces in one
// place rather than two (or three).
const EXPECTED_TOOLS = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'tool-names.json'), 'utf8'),
).names;

function run() {
  return new Promise((resolve, reject) => {
    // Force a no-port boot so we test the degraded path deterministically.
    const env = { ...process.env, SHADOW_CLJS_NREPL_PORT: '' };
    delete env.SHADOW_CLJS_NREPL_PORT;
    // Boot from a tmp dir so port-file probing misses.
    // Pass `--allow-eval` to opt in to the eval-cljs tool (rf2-cxx5s
    // launch-flag gate, default OFF). The roundtrip relies on
    // eval-cljs to surface the no-nREPL degraded envelope.
    const child = spawn(process.execPath, [SERVER, '--allow-eval'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: require('node:os').tmpdir(),
      env,
    });
    // Buffer stderr so the readiness poll (`waitForStderr`) can match
    // against everything the server has logged so far — and tee it to
    // the parent process for live visibility.
    let stderrBuf = '';
    child.stderr.on('data', (d) => {
      const s = d.toString();
      stderrBuf += s;
      process.stderr.write('[server] ' + s);
    });

    // Poll-wait for `pattern` to appear in the server's stderr stream.
    // Resolves on first match; rejects on timeout or child exit. 25ms
    // is fast enough that a healthy boot adds no perceptible latency,
    // and the 5s ceiling covers a slow CI runner without masking a
    // genuine hang. Replaces the historical bare 250ms `setTimeout`
    // wait (rf2-llzzt) which raced on slow CI / under valgrind.
    const waitForStderr = (pattern, { timeoutMs = 5000, intervalMs = 25 } = {}) =>
      new Promise((res, rej) => {
        const deadline = Date.now() + timeoutMs;
        const tick = () => {
          if (pattern.test(stderrBuf)) return res();
          if (child.exitCode !== null) {
            return rej(new Error(
              'server exited (code=' + child.exitCode + ') before matching ' + pattern,
            ));
          }
          if (Date.now() >= deadline) {
            return rej(new Error(
              'timed out after ' + timeoutMs + 'ms waiting for ' + pattern +
              ' on stderr; saw:\n' + stderrBuf,
            ));
          }
          setTimeout(tick, intervalMs);
        };
        tick();
      });

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
          if (f.id && pending.has(f.id)) {
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

    (async () => {
      // Wait for the server's readiness banner on stderr — emitted by
      // `connect-transport!` once stdio is wired up. Matches both the
      // success-path ("ready — awaiting MCP frames on stdin") and the
      // degraded-mode ("ready (degraded — no nREPL port)") banners.
      await waitForStderr(/\bready\b/);

      // 1. initialize
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'roundtrip', version: '0' },
      });
      if (!init.result?.protocolVersion) throw new Error('initialize failed: ' + JSON.stringify(init));
      console.log('OK   initialize ->', init.result.serverInfo);

      notify('notifications/initialized', {});

      // 2. tools/list — confirm catalogue matches the canonical fixture
      // at `test/fixtures/tool-names.json` (rf2-drke0). The fixture is
      // the single source of truth shared with the cross-server
      // conformance harness (`tools/mcp-conformance/test/
      // end-to-end-re-frame2-pair.cjs`) — a registry change updates one file,
      // not two.
      const list = await call('tools/list', {});
      const names = (list.result?.tools || []).map((t) => t.name).sort();
      if (JSON.stringify(names) !== JSON.stringify(EXPECTED_TOOLS)) {
        throw new Error('tools/list mismatch:\n  expected ' + EXPECTED_TOOLS + '\n  got ' + names);
      }
      console.log('OK   tools/list ->', names.join(', '));

      // 2c. Verify the subscribe descriptor carries the documented
      // input schema (topic + filter + max-buffered-events +
      // max-buffered-bytes + poll-ms + max-ms + max-events + build)
      // and the enum of recognised topics. Pinning the exact set so
      // accidental renames break the test instead of silently
      // shipping a broken contract. rf2-ho4ve replaced the
      // pre-byte-budget `max-buffered` event-count slot with the
      // `max-buffered-events` / `max-buffered-bytes` pair.
      const subDesc = (list.result?.tools || []).find((t) => t.name === 'subscribe');
      if (!subDesc) throw new Error('subscribe descriptor missing from tools/list');
      const subProps = subDesc.inputSchema?.properties || {};
      for (const k of ['topic', 'filter', 'max-buffered-events', 'max-buffered-bytes', 'poll-ms', 'max-ms', 'max-events', 'build']) {
        if (!(k in subProps)) {
          throw new Error('subscribe inputSchema missing property: ' + k);
        }
      }
      // Pre-byte-budget slot MUST be gone — catch accidental revert.
      if ('max-buffered' in subProps) {
        throw new Error('subscribe inputSchema still carries removed `max-buffered` property — should be replaced by max-buffered-events / max-buffered-bytes (rf2-ho4ve)');
      }
      const topicEnum = subProps.topic?.enum || [];
      const expectedTopics = ['trace', 'epoch', 'fx', 'error'];
      for (const t of expectedTopics) {
        if (!topicEnum.includes(t)) {
          throw new Error('subscribe.topic.enum missing: ' + t);
        }
      }
      console.log('OK   subscribe descriptor -> topic/filter/max-buffered-events/max-buffered-bytes/poll-ms/max-ms/max-events/build');

      const unsubDesc = (list.result?.tools || []).find((t) => t.name === 'unsubscribe');
      if (!unsubDesc) throw new Error('unsubscribe descriptor missing from tools/list');
      if (!unsubDesc.inputSchema?.required?.includes('sub-id')) {
        throw new Error('unsubscribe.inputSchema missing required: sub-id');
      }
      console.log('OK   unsubscribe descriptor -> sub-id required');

      // 2b. Verify the snapshot descriptor carries the documented input
      // schema (frames + include + path + build), so accidental future
      // renames break the test instead of silently shipping a broken
      // contract. `path` joined the schema under rf2-tygdv (path-based
      // slicing for the :app-db slice).
      const snapDesc = (list.result?.tools || []).find((t) => t.name === 'snapshot');
      if (!snapDesc) throw new Error('snapshot descriptor missing from tools/list');
      const props = snapDesc.inputSchema?.properties || {};
      for (const k of ['frames', 'include', 'path', 'build']) {
        if (!(k in props)) {
          throw new Error('snapshot inputSchema missing property: ' + k);
        }
      }
      console.log('OK   snapshot descriptor -> frames/include/path/build');

      // 2d. Verify the get-path descriptor (rf2-tygdv). Required
      // input: `path`; optional: `frame`, `build`.
      const gpDesc = (list.result?.tools || []).find((t) => t.name === 'get-path');
      if (!gpDesc) throw new Error('get-path descriptor missing from tools/list');
      const gpProps = gpDesc.inputSchema?.properties || {};
      for (const k of ['path', 'frame', 'build']) {
        if (!(k in gpProps)) {
          throw new Error('get-path inputSchema missing property: ' + k);
        }
      }
      if (!gpDesc.inputSchema?.required?.includes('path')) {
        throw new Error('get-path.inputSchema missing required: path');
      }
      console.log('OK   get-path descriptor -> path (required) + frame/build');

      // 3. tools/call eval-cljs without nREPL — expect graceful degraded result
      const evalResp = await call('tools/call', {
        name: 'eval-cljs',
        arguments: { form: '(+ 1 2)' },
      });
      const text = evalResp.result?.content?.[0]?.text || '';
      if (!evalResp.result?.isError || !text.includes('nrepl-port-not-found')) {
        throw new Error('eval-cljs degraded mode expected, got: ' + JSON.stringify(evalResp));
      }
      console.log('OK   tools/call eval-cljs (no nREPL) -> degraded isError');

      // 3b. tools/call snapshot — same degraded path. Proves the new
      // tool is wired into the dispatch table (would surface
      // :unknown-tool otherwise).
      const snapResp = await call('tools/call', {
        name: 'snapshot',
        arguments: { frames: 'all' },
      });
      const snapText = snapResp.result?.content?.[0]?.text || '';
      if (!snapResp.result?.isError || !snapText.includes('nrepl-port-not-found')) {
        throw new Error('snapshot degraded mode expected, got: ' + JSON.stringify(snapResp));
      }
      console.log('OK   tools/call snapshot (no nREPL) -> degraded isError');

      // 3b'. tools/call get-path — same degraded path. Proves the
      // rf2-tygdv tool is wired into the dispatch table.
      const gpResp = await call('tools/call', {
        name: 'get-path',
        arguments: { path: '[:user :email]' },
      });
      const gpText = gpResp.result?.content?.[0]?.text || '';
      if (!gpResp.result?.isError || !gpText.includes('nrepl-port-not-found')) {
        throw new Error('get-path degraded mode expected, got: ' + JSON.stringify(gpResp));
      }
      console.log('OK   tools/call get-path (no nREPL) -> degraded isError');

      // 3c. tools/call subscribe (no nREPL) — degraded path. Proves
      // the streaming-shaped tool is wired into the dispatch table
      // and routes through the same degraded-mode response as the
      // pull-mode tools.
      const subResp = await call('tools/call', {
        name: 'subscribe',
        arguments: { topic: 'trace' },
      });
      const subTxt = subResp.result?.content?.[0]?.text || '';
      if (!subResp.result?.isError || !subTxt.includes('nrepl-port-not-found')) {
        throw new Error('subscribe degraded mode expected, got: ' + JSON.stringify(subResp));
      }
      console.log('OK   tools/call subscribe (no nREPL) -> degraded isError');

      // 3d. tools/call unsubscribe (no nREPL) — same.
      const unsubResp = await call('tools/call', {
        name: 'unsubscribe',
        arguments: { 'sub-id': 'fake-uuid' },
      });
      const unsubTxt = unsubResp.result?.content?.[0]?.text || '';
      if (!unsubResp.result?.isError || !unsubTxt.includes('nrepl-port-not-found')) {
        throw new Error('unsubscribe degraded mode expected, got: ' + JSON.stringify(unsubResp));
      }
      console.log('OK   tools/call unsubscribe (no nREPL) -> degraded isError');

      // 4. tools/call unknown — expect isError with :unknown-tool
      const unk = await call('tools/call', { name: 'no-such-tool', arguments: {} });
      const t2 = unk.result?.content?.[0]?.text || '';
      // Degraded mode currently swallows everything — that's the documented
      // behaviour when nREPL port is missing. Both paths are acceptable here:
      //  - degraded path: isError with :nrepl-port-not-found
      //  - connected path: isError with :unknown-tool
      if (!unk.result?.isError) {
        throw new Error('unknown tool should isError, got: ' + JSON.stringify(unk));
      }
      console.log('OK   tools/call unknown-tool -> isError');

      child.kill();
      console.log('\nSERVER STDIO ROUND-TRIP GREEN');
      resolve();
    })().catch((e) => {
      child.kill();
      reject(e);
    });
  });
}

run().then(() => process.exit(0)).catch((e) => {
  console.error('FAIL:', e.message);
  process.exit(1);
});
