// stdin-eof-drain.cjs — a client that writes its frames and closes stdin gets
// its answers.
//
// Contract (spec/001-Wire-Protocol.md §Lifecycle, server.cljs lifecycle
// step 6): on stdin EOF the server answers the `tools/call` requests already
// in flight, within its drain bound, before it closes the nREPL socket and
// exits 0.
//
// The frames are written to a FILE and that file is the child's stdin, so EOF
// lands right behind the last frame — the way a heredoc or `< frames.jsonl`
// arrives. A fake bencode nREPL answers every op late, so the `tools/call` is
// still waiting on the nREPL when EOF arrives. A server that exits on EOF
// without draining writes the `initialize` response and nothing more.
//
// Self-contained: no live shadow-cljs. Run with:
//   node test/stdin-eof-drain.cjs   (after `npm run build`)
// Exits 0 on success, non-zero on any failure.

'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

const SERVER = path.join(__dirname, '..', 'out', 'server.js');

// Each nREPL op is answered this late, so the call is in flight at EOF.
const REPLY_DELAY_MS = 300;
// The call's few delayed ops, with room for a slow runner — far inside the
// server's drain bound, so a red here is a missing drain, not the bound.
const EXIT_BOUND_MS = 20000;

// Minimal bencode: `encode` builds nREPL response dicts; `parseOne` is an
// incremental decoder that returns null on a partial value.
function encode(v) {
  if (typeof v === 'string') {
    const b = Buffer.from(v, 'utf8');
    return Buffer.concat([Buffer.from(b.length + ':'), b]);
  }
  if (typeof v === 'number') return Buffer.from('i' + Math.trunc(v) + 'e');
  if (Array.isArray(v)) return Buffer.concat([Buffer.from('l'), ...v.map(encode), Buffer.from('e')]);
  const parts = [Buffer.from('d')];
  for (const k of Object.keys(v).sort()) parts.push(encode(k), encode(v[k]));
  parts.push(Buffer.from('e'));
  return Buffer.concat(parts);
}

function parseOne(buf, i) {
  if (i >= buf.length) return null;
  const c = buf[i];
  if (c === 0x69) {
    const e = buf.indexOf(0x65, i + 1);
    if (e < 0) return null;
    return { value: parseInt(buf.slice(i + 1, e).toString('ascii'), 10), next: e + 1 };
  }
  if (c === 0x6c || c === 0x64) {
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
  const colon = buf.indexOf(0x3a, i);
  if (colon < 0) return null;
  const len = parseInt(buf.slice(i, colon).toString('ascii'), 10);
  if (Number.isNaN(len)) throw new Error('malformed bencode string length at ' + i);
  const end = colon + 1 + len;
  if (end > buf.length) return null;
  return { value: buf.slice(colon + 1, end).toString('utf8'), next: end };
}

// A fake nREPL that answers every op with a `["done"]` frame REPLY_DELAY_MS late.
function startSlowNrepl() {
  let ops = 0;
  const sockets = new Set();
  const server = net.createServer((sock) => {
    sockets.add(sock);
    let buf = Buffer.alloc(0);
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        let r;
        try { r = parseOne(buf, 0); } catch { buf = Buffer.alloc(0); break; }
        if (!r) break;
        buf = buf.slice(r.next);
        const id = (r.value || {}).id;
        if (id == null) continue;
        ops += 1;
        setTimeout(() => {
          if (!sock.destroyed) sock.write(encode({ id: String(id), status: ['done'], value: '3', ns: 'user' }));
        }, REPLY_DELAY_MS);
      }
    });
    sock.on('close', () => sockets.delete(sock));
    sock.on('error', () => { /* teardown races are expected */ });
  });
  return {
    listen: () => new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port))),
    ops: () => ops,
    close: () => new Promise((resolve) => {
      for (const s of sockets) s.destroy();
      server.close(() => resolve());
    }),
  };
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

async function main() {
  if (!fs.existsSync(SERVER)) {
    throw new Error('server bundle missing at ' + SERVER + ' — run `npm run build` first');
  }
  const fake = startSlowNrepl();
  const port = await fake.listen();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pair-eof-drain-'));
  let child;
  try {
    const framesPath = path.join(dir, 'frames.jsonl');
    const frames = [
      { jsonrpc: '2.0', id: 1, method: 'initialize',
        params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'stdin-eof-drain', version: '0' } } },
      { jsonrpc: '2.0', method: 'notifications/initialized' },
      { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'eval-cljs', arguments: { form: '(+ 1 2)' } } },
    ];
    fs.writeFileSync(framesPath, frames.map((f) => JSON.stringify(f)).join('\n') + '\n');

    const fd = fs.openSync(framesPath, 'r');
    child = spawn(process.execPath, [SERVER], {
      stdio: [fd, 'pipe', 'pipe'],
      env: { ...process.env, SHADOW_CLJS_NREPL_PORT: String(port) },
    });
    fs.closeSync(fd);

    let stderr = '';
    child.stderr.on('data', (d) => {
      stderr += d.toString();
      process.stderr.write('[server] ' + d.toString());
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d.toString('utf8'); });

    const started = Date.now();
    const { code, signal } = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(
        'server still running ' + EXIT_BOUND_MS + 'ms after stdin EOF')), EXIT_BOUND_MS);
      child.once('exit', (c, s) => { clearTimeout(timer); resolve({ code: c, signal: s }); });
    });
    const elapsed = Date.now() - started;

    const lines = out.split('\n').map((l) => l.trim()).filter(Boolean);
    const responses = new Map();
    for (const line of lines) {
      let frame;
      try { frame = JSON.parse(line); } catch { throw new Error('non-MCP line on stdout: ' + line); }
      if (frame.id != null) responses.set(frame.id, frame);
    }

    assert(code === 0, 'expected exit 0 after stdin EOF, got code=' + code + ' signal=' + signal);
    console.log('OK   server exited 0 after stdin EOF (' + elapsed + 'ms)');
    assert(responses.get(1) && responses.get(1).result, 'no initialize response: ' + JSON.stringify(lines));
    console.log('OK   initialize answered');
    const call = responses.get(2);
    assert(call && call.result,
      'the tools/call in flight at EOF was not answered before exit; stdout carried ids ' +
      JSON.stringify([...responses.keys()]));
    assert(fake.ops() >= 1, 'the tools/call never reached the nREPL, so it was not waiting on it at EOF');
    console.log('OK   tools/call in flight at EOF answered before exit (isError=' + Boolean(call.result.isError) +
      ', ' + fake.ops() + ' nREPL op(s), each answered ' + REPLY_DELAY_MS + 'ms late)');
    assert(/in flight/.test(stderr), 'stderr does not report the drain:\n' + stderr);
    console.log('OK   stderr reports the drain');

    console.log('\nSTDIN-EOF DRAIN GREEN');
  } finally {
    if (child && child.exitCode === null) {
      try { child.kill('SIGKILL'); } catch { /* best effort */ }
    }
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error('FAIL:', err && err.message ? err.message : err);
    process.exit(1);
  });
