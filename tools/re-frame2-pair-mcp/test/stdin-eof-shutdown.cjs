// stdin-eof-shutdown.cjs — adversarial regression for the documented
// stdin-EOF lifecycle contract (rf2-j538f7.32).
//
// Contract (spec/001-Wire-Protocol.md:16-25, spec/API.md:245-257,
// server.cljs lifecycle step 6): when the MCP stdio client closes stdin,
// the Node process reaches EOF, closes its persistent nREPL socket, and
// exits 0 — WITHOUT relying on an out-of-band kill.
//
// Before the fix the server installed no `process.stdin` `end` listener,
// so a completed app-facing tool left an idle nREPL TCP socket as a live
// event-loop handle: closing stdin neither closed the socket nor exited
// the process. This harness reproduces that leak and grades the fix.
//
// It is fully self-contained — a minimal fake bencode nREPL stands in for
// shadow-cljs, so no live runtime is required. Run with:
//   node test/stdin-eof-shutdown.cjs   (after `npm run build`)
// Exits 0 on success, non-zero on any failure.
//
// CRITICAL (acceptance criteria 1-2): the SUCCESS path never calls
// `child.kill()`. Force-kill lives only in the failure/cleanup finally so
// the oracle cannot mask a lingering process by killing it.

'use strict';

const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

// The bound within which a healthy server must retire itself after EOF.
// Generous enough for a slow CI runner; short enough that the pre-fix
// hang (which never exits) is caught deterministically.
const EXIT_BOUND_MS = 4000;

// --------------------------------------------------------------------------
// Minimal bencode — self-contained so the harness has no decode-API
// coupling. `encode` builds nREPL response dicts; `parseOne` is an
// INCREMENTAL decoder that returns null on a partial (chunk-split) value
// so the fake server can reassemble streamed ops.
// --------------------------------------------------------------------------
function encode(v) {
  if (Buffer.isBuffer(v)) return Buffer.concat([Buffer.from(v.length + ':'), v]);
  if (typeof v === 'string') {
    const b = Buffer.from(v, 'utf8');
    return Buffer.concat([Buffer.from(b.length + ':'), b]);
  }
  if (typeof v === 'number') return Buffer.from('i' + Math.trunc(v) + 'e');
  if (Array.isArray(v)) {
    return Buffer.concat([Buffer.from('l'), ...v.map(encode), Buffer.from('e')]);
  }
  if (v && typeof v === 'object') {
    const parts = [Buffer.from('d')];
    for (const k of Object.keys(v).sort()) {
      parts.push(encode(k), encode(v[k]));
    }
    parts.push(Buffer.from('e'));
    return Buffer.concat(parts);
  }
  throw new Error('cannot bencode: ' + String(v));
}

// Returns { value, next } for the value at byte offset `i`, or null when
// the buffer holds only a partial value (needs more bytes).
function parseOne(buf, i) {
  if (i >= buf.length) return null;
  const c = buf[i];
  if (c === 0x69) {
    // i<int>e
    const e = buf.indexOf(0x65, i + 1);
    if (e < 0) return null;
    return { value: parseInt(buf.slice(i + 1, e).toString('ascii'), 10), next: e + 1 };
  }
  if (c === 0x6c || c === 0x64) {
    // l...e (list) / d...e (dict)
    let j = i + 1;
    const items = [];
    for (;;) {
      if (j >= buf.length) return null;
      if (buf[j] === 0x65) { j += 1; break; }
      const r = parseOne(buf, j);
      if (!r) return null;
      items.push(r.value);
      j = r.next;
    }
    if (c === 0x6c) return { value: items, next: j };
    const obj = {};
    for (let k = 0; k + 1 < items.length; k += 2) obj[String(items[k])] = items[k + 1];
    return { value: obj, next: j };
  }
  // <len>:<bytes> string
  const colon = buf.indexOf(0x3a, i);
  if (colon < 0) return null;
  const len = parseInt(buf.slice(i, colon).toString('ascii'), 10);
  if (Number.isNaN(len)) throw new Error('malformed bencode string length at ' + i);
  const start = colon + 1;
  const end = start + len;
  if (end > buf.length) return null;
  return { value: buf.slice(start, end).toString('utf8'), next: end };
}

// --------------------------------------------------------------------------
// Fake bencode nREPL — accepts TCP connections, answers every op with a
// `["done"]` status frame echoing the request id, and records socket
// lifecycle so the test can assert the server closes its connection on EOF.
// --------------------------------------------------------------------------
function startFakeNrepl() {
  let live = 0;
  let closed = 0;
  const server = net.createServer((sock) => {
    live += 1;
    let buf = Buffer.alloc(0);
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        let r;
        try { r = parseOne(buf, 0); } catch { buf = Buffer.alloc(0); break; }
        if (!r) break;
        buf = buf.slice(r.next);
        const msg = r.value || {};
        const id = msg.id;
        if (id == null) continue;
        // A `["done"]` frame is all send-op! needs to resolve. A `value`
        // keeps the eval-cljs probe ladder from hanging; its exact shape
        // is irrelevant — the socket stays persistently open regardless of
        // whether the probe concludes a runtime is present.
        sock.write(encode({ id: String(id), status: ['done'], value: '1', ns: 'user' }));
      }
    });
    sock.on('close', () => { live -= 1; closed += 1; });
    sock.on('error', () => { /* client teardown races are expected */ });
  });
  return {
    server,
    listen: () =>
      new Promise((resolve) => {
        server.listen(0, '127.0.0.1', () => resolve(server.address().port));
      }),
    liveConnections: () => live,
    closedConnections: () => closed,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  };
}

// --------------------------------------------------------------------------
// MCP stdio client — spawn the compiled server, drive line-delimited
// JSON-RPC, and expose EOF + exit primitives. Records EVERY stdout line so
// the harness can assert stdout purity (no non-JSON noise on teardown).
// --------------------------------------------------------------------------
function spawnServer(extraEnv, extraArgs) {
  const env = { ...process.env, ...extraEnv };
  const child = spawn(process.execPath, [SERVER, ...(extraArgs || [])], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env,
  });

  let stderrBuf = '';
  child.stderr.on('data', (d) => {
    const s = d.toString();
    stderrBuf += s;
    process.stderr.write('[server] ' + s);
  });

  const stdoutLines = [];
  const nonJsonStdout = [];
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
      stdoutLines.push(line);
      let frame;
      try {
        frame = JSON.parse(line);
      } catch {
        // stdout is reserved for MCP frames — anything else is a purity
        // violation the harness must fail on.
        nonJsonStdout.push(line);
        continue;
      }
      if (frame.id && pending.has(frame.id)) {
        pending.get(frame.id)(frame);
        pending.delete(frame.id);
      }
    }
  });

  const waitForStderr = (pattern, { timeoutMs = 8000, intervalMs = 25 } = {}) =>
    new Promise((res, rej) => {
      const deadline = Date.now() + timeoutMs;
      const tick = () => {
        if (pattern.test(stderrBuf)) return res();
        if (child.exitCode !== null) {
          return rej(new Error('server exited (code=' + child.exitCode + ') before matching ' + pattern));
        }
        if (Date.now() >= deadline) {
          return rej(new Error('timed out waiting for ' + pattern + ' on stderr; saw:\n' + stderrBuf));
        }
        setTimeout(tick, intervalMs);
      };
      tick();
    });

  const call = (m, p) => {
    const id = next++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('MCP call ' + m + ' (id ' + id + ') timed out'));
      }, 15000);
      pending.set(id, (frame) => { clearTimeout(timer); resolve(frame); });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method: m, params: p }) + '\n');
    });
  };
  const notify = (m, p) =>
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: m, params: p }) + '\n');

  // Resolves { code, signal } when the child exits; rejects if it stays
  // alive past `ms` (the pre-fix behaviour this regression is designed to
  // catch). Deliberately does NOT kill on timeout — the caller's finally
  // owns force-kill so a hung child surfaces as a red, not a masked pass.
  const waitForExit = (ms) =>
    new Promise((resolve, reject) => {
      if (child.exitCode !== null) return resolve({ code: child.exitCode, signal: null });
      const timer = setTimeout(() => {
        reject(new Error('child did not exit within ' + ms + 'ms of stdin EOF (still alive) — leak reproduced'));
      }, ms);
      child.once('exit', (code, signal) => { clearTimeout(timer); resolve({ code, signal }); });
    });

  return {
    child,
    call,
    notify,
    waitForStderr,
    waitForExit,
    endStdin: () => child.stdin.end(),
    nonJsonStdout: () => nonJsonStdout.slice(),
    forceKill: () => { try { child.kill('SIGKILL'); } catch { /* best effort */ } },
  };
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

async function handshake(client, name) {
  await client.waitForStderr(/\bready\b/);
  const init = await client.call('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name, version: '0' },
  });
  assert(init.result?.protocolVersion, 'initialize failed: ' + JSON.stringify(init));
  client.notify('notifications/initialized', {});
}

// Case A — the core regression: a completed app-facing tool leaves an idle
// nREPL socket; stdin EOF must close it AND exit 0.
async function caseWithNrepl() {
  const fake = startFakeNrepl();
  const port = await fake.listen();
  const client = spawnServer({ SHADOW_CLJS_NREPL_PORT: String(port) });
  try {
    await handshake(client, 'stdin-eof-with-nrepl');

    // Complete one app-facing tool so the persistent TCP socket opens and
    // goes idle. eval-cljs drives the nREPL round-trip unconditionally; its
    // result envelope is irrelevant here — we only need the socket open.
    const resp = await client.call('tools/call', { name: 'eval-cljs', arguments: { form: '(+ 1 2)' } });
    assert(resp.result, 'eval-cljs returned no result: ' + JSON.stringify(resp));
    assert(
      fake.liveConnections() >= 1,
      'expected an open nREPL socket after the tool completed, saw ' + fake.liveConnections(),
    );
    console.log('OK   eval-cljs opened an idle nREPL socket (' + fake.liveConnections() + ' live)');

    // The documented shutdown signal: close stdin, nothing else.
    client.endStdin();
    const { code, signal } = await client.waitForExit(EXIT_BOUND_MS);
    assert(code === 0, 'expected clean exit 0 on stdin EOF, got code=' + code + ' signal=' + signal);
    console.log('OK   stdin EOF -> process exited 0 within ' + EXIT_BOUND_MS + 'ms (no kill)');

    // The fake nREPL peer must observe its socket close — the leak's other half.
    const deadline = Date.now() + 1000;
    while (fake.liveConnections() > 0 && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 20));
    }
    assert(
      fake.liveConnections() === 0 && fake.closedConnections() >= 1,
      'nREPL socket was not closed on EOF: live=' + fake.liveConnections() + ' closed=' + fake.closedConnections(),
    );
    console.log('OK   nREPL peer observed socket close on EOF');

    assert(
      client.nonJsonStdout().length === 0,
      'stdout purity violated — non-MCP lines on stdout: ' + JSON.stringify(client.nonJsonStdout()),
    );
    console.log('OK   stdout stayed pure (no non-MCP output on teardown)');
  } finally {
    client.forceKill();
    await fake.close();
  }
}

// Case B — no-nREPL counterpart (acceptance criterion 3): a closed-world
// session (discovery never resolves a port) must still exit promptly on EOF.
async function caseNoNrepl() {
  // Empty SHADOW_CLJS_NREPL_PORT + a closed --http-port forces the degraded
  // (no-nREPL) boot deterministically.
  const env = { ...process.env };
  delete env.SHADOW_CLJS_NREPL_PORT;
  const client = spawnServer({ ...env, SHADOW_CLJS_NREPL_PORT: '' }, ['--http-port', '1']);
  try {
    await handshake(client, 'stdin-eof-no-nrepl');
    const list = await client.call('tools/list', {});
    assert(Array.isArray(list.result?.tools), 'tools/list failed: ' + JSON.stringify(list));

    client.endStdin();
    const { code, signal } = await client.waitForExit(EXIT_BOUND_MS);
    assert(code === 0, 'no-nREPL: expected clean exit 0 on stdin EOF, got code=' + code + ' signal=' + signal);
    assert(
      client.nonJsonStdout().length === 0,
      'no-nREPL: stdout purity violated: ' + JSON.stringify(client.nonJsonStdout()),
    );
    console.log('OK   no-nREPL session exited 0 on stdin EOF within ' + EXIT_BOUND_MS + 'ms');
  } finally {
    client.forceKill();
  }
}

// Case C — EOF before the first tool call (no socket ever opened) must be
// harmless, and a duplicate terminal event (end after already-ended stdin)
// must not change the exit status (idempotency, acceptance criteria 4-5).
async function caseEofBeforeFirstTool() {
  const fake = startFakeNrepl();
  const port = await fake.listen();
  const client = spawnServer({ SHADOW_CLJS_NREPL_PORT: String(port) });
  try {
    await handshake(client, 'stdin-eof-early');
    // No tool call — discovery never ran, no nREPL socket exists.
    assert(fake.liveConnections() === 0, 'unexpected early nREPL socket');
    client.endStdin();
    // A duplicate terminal nudge must be a no-op, never a double-close crash.
    try { client.child.stdin.end(); } catch { /* already ended */ }
    const { code, signal } = await client.waitForExit(EXIT_BOUND_MS);
    assert(code === 0, 'early-EOF: expected clean exit 0, got code=' + code + ' signal=' + signal);
    assert(
      client.nonJsonStdout().length === 0,
      'early-EOF: stdout purity violated: ' + JSON.stringify(client.nonJsonStdout()),
    );
    console.log('OK   EOF before first tool exited 0 (idempotent, no socket to leak)');
  } finally {
    client.forceKill();
    await fake.close();
  }
}

async function main() {
  const fs = require('node:fs');
  if (!fs.existsSync(SERVER)) {
    console.error('FAIL: server bundle missing at ' + SERVER + ' — run `npm run build` first');
    process.exit(1);
  }
  await caseWithNrepl();
  await caseNoNrepl();
  await caseEofBeforeFirstTool();
  console.log('\nSTDIN-EOF SHUTDOWN GREEN');
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error('FAIL:', err && err.message ? err.message : err);
    if (err && err.stack) console.error(err.stack);
    process.exit(1);
  });
