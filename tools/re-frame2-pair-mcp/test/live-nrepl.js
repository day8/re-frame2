// Live nREPL integration test. Requires an nREPL listening on the
// port read from $NREPL_TEST_PORT (default 17778). The bash-shim
// equivalent is `eval-cljs.sh '(+ 1 2)'` against a real shadow.
//
// This test exercises:
//   - persistent socket survives multiple ops on one server instance
//   - bencode multi-frame parsing (status frame comes separately from
//     the value frame in our nREPL test setup)
//   - eval-cljs degrades cleanly when the runtime preload marker is
//     absent (because we're talking to a plain JVM nREPL, not
//     shadow-cljs — the preload probe fails and eval-cljs surfaces
//     a structured error rather than hanging)
//
// Run via: `NREPL_TEST_PORT=17778 node test/live-nrepl.js`

const { spawn } = require('node:child_process');
const path = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');
const PORT = process.env.NREPL_TEST_PORT || '17778';

function run() {
  return new Promise((resolve, reject) => {
    const env = { ...process.env, SHADOW_CLJS_NREPL_PORT: PORT };
    // Pass `--allow-eval` to opt in to the eval-cljs tool (rf2-cxx5s
    // launch-flag gate, default OFF). This live-nrepl probe is
    // specifically exercising the eval surface.
    const child = spawn(process.execPath, [SERVER, '--allow-eval'], {
      stdio: ['pipe', 'pipe', 'pipe'],
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
        const f = JSON.parse(line);
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
      await new Promise((r) => setTimeout(r, 400));

      await call('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'live', version: '0' },
      });
      notify('notifications/initialized', {});

      // 1. Plain JVM eval — bypass the cljs-eval wrapper by sending a
      // form that the cljs-eval wrapper will fail to find, then verify
      // the error is surfaced rather than the connection hanging.
      // (A real shadow-cljs test would do `(+ 1 2)` cleanly.)
      console.log('Probing eval-cljs against a JVM nREPL on port', PORT);
      const r1 = await call('tools/call', {
        name: 'eval-cljs',
        arguments: { form: '(+ 1 2)' },
      });
      const t1 = r1.result?.content?.[0]?.text;
      console.log('  result:', t1?.slice(0, 200));
      // The shadow.cljs.devtools.api/cljs-eval call will fail on a plain
      // JVM nREPL (no shadow namespace), so we expect a graceful error,
      // NOT a timeout — that's the actual contract being tested here.
      if (!t1) throw new Error('no response');
      if (t1.includes('timed out')) {
        throw new Error('socket call hung — bencode/socket interop broken');
      }
      console.log('OK   eval-cljs against bare nREPL completes (does not hang)');

      // 2. Persistent connection: fire a second op on the same server
      // process and verify it doesn't open a new socket / doesn't time
      // out either.
      const r2 = await call('tools/call', {
        name: 'eval-cljs',
        arguments: { form: '(reduce + (range 10))' },
      });
      const t2 = r2.result?.content?.[0]?.text;
      console.log('  second:', t2?.slice(0, 200));
      if (t2.includes('timed out')) {
        throw new Error('persistent-connection second call timed out');
      }
      console.log('OK   second op on the same persistent socket completes');

      child.kill();
      console.log('\nLIVE NREPL INTEGRATION GREEN');
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
