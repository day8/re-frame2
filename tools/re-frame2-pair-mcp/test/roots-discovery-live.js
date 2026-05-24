// Live verification harness for rf2-3grub.
//
// Spawns the compiled MCP server as a child process and acts as a
// minimal MCP client that:
//
//   1. Declares the `roots` capability in `initialize`.
//   2. Responds to the server's `roots/list` request with a synthetic
//      workspace listing.
//   3. Triggers a tool call and observes the server's discovery cascade
//      land on step 3 (roots-based) — proven by the server logging the
//      port it discovered.
//
// This is NOT testing against the real Claude Code MCP client — that
// path is exercised by the mayor having the freshly-built MCP server
// reload in their Claude Code session. This harness PINS the contract
// that the server correctly issues `roots/list` and walks the workspace
// when the client exposes the capability, independent of the live host.
//
// Run with: `node test/roots-discovery-live.js` from this directory
// after `shadow-cljs compile server`. Exits 0 on success.

const { spawn } = require('node:child_process');
const fs        = require('node:fs');
const os        = require('node:os');
const path      = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

// Build a synthetic workspace with one shadow project — a fake
// `shadow-cljs.edn` + a port file at `.shadow-cljs/nrepl.port` with
// a port number the test asserts the server picked up.
const TMP_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-3grub-'));
const PROJ     = path.join(TMP_ROOT, 'proj');
fs.mkdirSync(PROJ, { recursive: true });
fs.writeFileSync(path.join(PROJ, 'shadow-cljs.edn'), '{}\n');
const SHADOW_DIR = path.join(PROJ, '.shadow-cljs');
fs.mkdirSync(SHADOW_DIR);
const PORT_FILE = path.join(SHADOW_DIR, 'nrepl.port');
const FAKE_PORT = 65432;
fs.writeFileSync(PORT_FILE, String(FAKE_PORT));

function run() {
  return new Promise((resolve, reject) => {
    const env = { ...process.env };
    delete env.SHADOW_CLJS_NREPL_PORT;
    // --http-port 1 so the rf2-umoz2 HTTP probe ALWAYS fails — the only way
    // to land on the discovered port is via the rf2-3grub roots/list path.
    const child = spawn(process.execPath, [SERVER, '--http-port', '1'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: os.tmpdir(),
      env,
    });
    let stderrBuf = '';
    child.stderr.on('data', (d) => {
      const s = d.toString();
      stderrBuf += s;
      process.stderr.write('[server] ' + s);
    });

    const waitForStderr = (pattern, { timeoutMs = 8000, intervalMs = 25 } = {}) =>
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

    // Track the server's roots/list request — when it arrives we
    // answer with our synthetic workspace.
    let rootsListAnswered = false;

    child.stdout.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        let f;
        try { f = JSON.parse(line); } catch (e) {
          console.error('FAIL malformed JSON:', line);
          child.kill();
          reject(e);
          return;
        }
        if (f.method === 'roots/list') {
          // Build a file:// URI for the synthetic project root.
          // Use the platform-appropriate URL encoding so the server's
          // node:url/fileURLToPath decodes it correctly.
          const uri = new URL('file:///' + TMP_ROOT.replace(/\\/g, '/').replace(/^\//, '')).href;
          const resp = {
            jsonrpc: '2.0',
            id: f.id,
            result: { roots: [{ uri, name: 'synthetic-workspace' }] },
          };
          child.stdin.write(JSON.stringify(resp) + '\n');
          rootsListAnswered = true;
          console.log('CLIENT received roots/list request — answered with ' + uri);
          continue;
        }
        if (f.id && pending.has(f.id)) {
          pending.get(f.id)(f);
          pending.delete(f.id);
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
      await waitForStderr(/\bready\b/);

      // 1. initialize — declare the `roots` client capability.
      const init = await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: { roots: {} },
        clientInfo: { name: 'roots-live-test', version: '0' },
      });
      if (!init.result?.protocolVersion) throw new Error('initialize failed');
      console.log('OK   initialize ->', init.result.serverInfo);

      notify('notifications/initialized', {});

      // 2. tools/call — should trigger the roots-based discovery cascade.
      // The server will issue roots/list, walk the synthetic workspace,
      // find the port file at the fake port, then try to connect to that
      // port. The connection will FAIL (no real nREPL listening at the
      // fake port) but the failure mode tells us the cascade landed on
      // step 3 and read OUR port file. The structured error contains the
      // port-number trace.
      const resp = await call('tools/call', {
        name: 'discover-app',
        arguments: {},
      });
      if (!rootsListAnswered) {
        throw new Error('Server did not issue roots/list — discovery cascade skipped step 3');
      }

      // Check stderr for the discovered port — the server logs
      // `nREPL port = <port>` from `new-conn-for-port`. If our fake port
      // appears there, the roots discovery walked our workspace, found
      // the port file, and used it.
      if (!stderrBuf.includes('nREPL port = ' + FAKE_PORT)) {
        throw new Error(
          'Server did not pick up the rf2-3grub port file at ' + FAKE_PORT +
          '. stderr:\n' + stderrBuf,
        );
      }
      console.log('OK   roots/list → walk → port-file → port=' + FAKE_PORT +
                  ' (rf2-3grub primary path live-verified)');

      // The actual tool call will surface an isError (no live nREPL at
      // the fake port). We only care that discovery worked.
      console.log('OK   discover-app result envelope:', resp.result?.isError ? 'isError (expected — fake port)' : 'unexpectedly ok');

      child.kill();
      console.log('\nrf2-3grub ROOTS-DISCOVERY LIVE VERIFY GREEN');
      resolve();
    })().catch((e) => {
      child.kill();
      reject(e);
    });
  });
}

run().then(() => {
  try { fs.rmSync(TMP_ROOT, { recursive: true, force: true }); } catch (_) {}
  process.exit(0);
}).catch((e) => {
  console.error('FAIL:', e.message);
  try { fs.rmSync(TMP_ROOT, { recursive: true, force: true }); } catch (_) {}
  process.exit(1);
});
