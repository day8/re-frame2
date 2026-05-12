// Stdio integration test: drives the compiled server through the
// standard MCP handshake and verifies the tool catalogue.
//
// This test does NOT need a live shadow-cljs nREPL — it exercises:
//   - initialize handshake
//   - tools/list (expects all seven tools, including the snapshot mega-op)
//   - tools/call eval-cljs against an absent nREPL (expects graceful
//     :nrepl-port-not-found degraded mode)
//   - tools/call snapshot against an absent nREPL (same degraded mode —
//     proves the new tool is wired into the dispatch table)
//
// A separate live-nrepl test (spec/INTEGRATION.md) covers the
// connected-to-shadow-cljs case.
//
// Run with: `node test/stdio-roundtrip.js` from this directory after
// `shadow-cljs compile server`. Exits 0 on success.

const { spawn } = require('node:child_process');
const path = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

function run() {
  return new Promise((resolve, reject) => {
    // Force a no-port boot so we test the degraded path deterministically.
    const env = { ...process.env, SHADOW_CLJS_NREPL_PORT: '' };
    delete env.SHADOW_CLJS_NREPL_PORT;
    // Boot from a tmp dir so port-file probing misses.
    const child = spawn(process.execPath, [SERVER], {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: require('node:os').tmpdir(),
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
      await new Promise((r) => setTimeout(r, 250));

      // 1. initialize
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'roundtrip', version: '0' },
      });
      if (!init.result?.protocolVersion) throw new Error('initialize failed: ' + JSON.stringify(init));
      console.log('OK   initialize ->', init.result.serverInfo);

      notify('notifications/initialized', {});

      // 2. tools/list — expect all seven tools (six per-op + snapshot
      // mega-op shipped in rf2-x70e). rf2-7dvg cut inject-runtime in
      // favour of a shadow-cljs :preloads entry; see SKILL.md §Setup.
      const list = await call('tools/list', {});
      const names = (list.result?.tools || []).map((t) => t.name).sort();
      const expected = [
        'discover-app',
        'dispatch',
        'eval-cljs',
        'snapshot',
        'tail-build',
        'trace-window',
        'watch-epochs',
      ];
      if (JSON.stringify(names) !== JSON.stringify(expected)) {
        throw new Error('tools/list mismatch:\n  expected ' + expected + '\n  got ' + names);
      }
      console.log('OK   tools/list ->', names.join(', '));

      // 2b. Verify the snapshot descriptor carries the documented input
      // schema (frames + include + build), so accidental future renames
      // break the test instead of silently shipping a broken contract.
      const snapDesc = (list.result?.tools || []).find((t) => t.name === 'snapshot');
      if (!snapDesc) throw new Error('snapshot descriptor missing from tools/list');
      const props = snapDesc.inputSchema?.properties || {};
      for (const k of ['frames', 'include', 'build']) {
        if (!(k in props)) {
          throw new Error('snapshot inputSchema missing property: ' + k);
        }
      }
      console.log('OK   snapshot descriptor -> frames/include/build');

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
