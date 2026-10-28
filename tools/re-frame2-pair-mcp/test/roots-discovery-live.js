// Live verification harness for roots-based nREPL port discovery.
//
// Spawns the compiled MCP server as a child process and acts as a
// minimal MCP client that:
//
//   1. Declares the `roots` capability in `initialize`.
//   2. Responds to the server's `roots/list` request with a synthetic
//      workspace listing two projects.
//   3. Triggers a tool call and observes the server's discovery cascade
//      land on step 3 (roots-based) — proven by the server logging the
//      port it discovered.
//   4. Observes the server skip the second project, whose port file names a
//      port nothing listens on (the file a force-killed shadow-cljs JVM
//      leaves behind), and name that file on stderr.
//
// This is NOT testing against the real Claude Code MCP client — that
// path is exercised by reloading the freshly-built MCP server in a
// Claude Code session. This harness PINS the contract
// that the server correctly issues `roots/list` and walks the workspace
// when the client exposes the capability, independent of the live host.
//
// Run with: `node test/roots-discovery-live.js` from this directory
// after `shadow-cljs compile server`. Exits 0 on success.

const { spawn } = require('node:child_process');
const fs        = require('node:fs');
const net       = require('node:net');
const os        = require('node:os');
const path      = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

// A synthetic workspace: each project is a fake `shadow-cljs.edn` beside a
// port file at `.shadow-cljs/nrepl.port`.
const TMP_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'pair-roots-discovery-'));

function writeProject(name, port) {
  const proj = path.join(TMP_ROOT, name);
  fs.mkdirSync(path.join(proj, '.shadow-cljs'), { recursive: true });
  fs.writeFileSync(path.join(proj, 'shadow-cljs.edn'), '{}\n');
  const portFile = path.join(proj, '.shadow-cljs', 'nrepl.port');
  fs.writeFileSync(portFile, String(port));
  return portFile;
}

// A listener on an ephemeral port. It drops every connection it accepts:
// discovery needs only the connect, and nothing here answers nREPL.
function listen() {
  return new Promise((resolve, reject) => {
    const server = net.createServer((sock) => sock.destroy());
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

function run({ livePort, deadPort, deadPortFile }) {
  return new Promise((resolve, reject) => {
    const env = { ...process.env };
    delete env.SHADOW_CLJS_NREPL_PORT;
    // --http-port 1 so the shadow HTTP probe ALWAYS fails — the only way
    // to land on the discovered port is via the roots/list path.
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

      // 2. tools/call — triggers the roots-based discovery cascade. The
      // server issues roots/list, walks the synthetic workspace, probes both
      // port files, and connects to the live one. Nothing there answers
      // nREPL, so the call itself is not awaited: the stderr lines are the
      // evidence of which file discovery chose and which it skipped.
      call('tools/call', { name: 'discover-app', arguments: {} });

      // The server logs `nREPL port = <port>` from `new-conn-for-port`.
      await waitForStderr(new RegExp('nREPL port = ' + livePort + '\\b'));
      if (!rootsListAnswered) {
        throw new Error('Server did not issue roots/list — discovery cascade skipped step 3');
      }
      console.log('OK   roots/list → walk → port-file → port=' + livePort +
                  ' (roots-discovery primary path live-verified)');

      await waitForStderr(/skipped port file\(s\) whose port refused a connect:/);
      if (!stderrBuf.includes(deadPortFile)) {
        throw new Error('Server did not name the refused port file ' + deadPortFile +
                        '. stderr:\n' + stderrBuf);
      }
      if (stderrBuf.includes('nREPL port = ' + deadPort)) {
        throw new Error('Server attached to the refused port ' + deadPort);
      }
      console.log('OK   refused port file skipped and named: ' + deadPortFile);

      child.kill();
      console.log('\nROOTS-DISCOVERY LIVE VERIFY GREEN');
      resolve();
    })().catch((e) => {
      child.kill();
      reject(e);
    });
  });
}

async function main() {
  const live = await listen();
  // A port that was bound and then released, so nothing listens on it.
  const released = await listen();
  const deadPort = released.address().port;
  await new Promise((r) => released.close(r));
  const livePort = live.address().port;
  writeProject('live', livePort);
  const deadPortFile = writeProject('dead', deadPort);
  try {
    await run({ livePort, deadPort, deadPortFile });
  } finally {
    await new Promise((r) => live.close(r));
  }
}

main().then(() => {
  try { fs.rmSync(TMP_ROOT, { recursive: true, force: true }); } catch (_) {}
  process.exit(0);
}).catch((e) => {
  console.error('FAIL:', e.message);
  try { fs.rmSync(TMP_ROOT, { recursive: true, force: true }); } catch (_) {}
  process.exit(1);
});
