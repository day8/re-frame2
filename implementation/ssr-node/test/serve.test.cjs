'use strict';
// THE LAUNCHER, END TO END — a process, not a function.
//
//     node implementation/ssr-node/test/serve.test.cjs
//
// Every other suite here drives the service in-process. This one spawns
// `bin/serve.cjs` the way a supervisor or a JVM host would, and reads the
// same three things they read: the ready line on stdout, `/health`, and a
// render. It is a SMOKE test by design — one request, no `state`, the
// well-behaved fixture — because the five guarantees are witnessed
// elsewhere and this file's only claim is that the launcher wires them to
// a socket and gets out of the way. Sending no `state` at all is
// deliberate as well: it keeps the row independent of the request's
// partition vocabulary, which `protocol.test.cjs` owns.
//
// ## THE READY LINE IS THE CONTRACT UNDER TEST
//
// A JVM witness spawns this launcher on port 0 and parses the ready line to
// learn where to dial, so its shape is pinned here field by field rather
// than merely parsed: a launcher that printed a perfectly good JSON object
// under a different key would strand every reader written against the
// README.
//
// ## PORT 0, ALWAYS
//
// Same reason as the HTTP rows elsewhere: a fixed port is a fixed
// collision with a developer's server or a concurrent worker's.

const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { fixture, post } = require('./_support.cjs');
const manifest = require('../package.json');

const PACKAGE_DIR = path.resolve(__dirname, '..');
const LAUNCHER = path.join(PACKAGE_DIR, 'bin', 'serve.cjs');

/** Worker boot is the slow part, and a cold box is slower than this one. */
const BOOT_MS = 20000;
/** A graceful close is milliseconds; this is the bound the acceptance names. */
const STOP_MS = 5000;

const withTimeout = (p, ms, what) =>
  Promise.race([
    p,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${what} within ${ms} ms`)), ms).unref()),
  ]);

/**
 * Spawn the launcher. `ready` resolves with the parsed ready line — found
 * by scanning stdout for the discriminator key, which is how the README
 * tells a reader to find it — or rejects if the process exits first.
 */
function launch(args) {
  const child = spawn(process.execPath, [LAUNCHER, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
  const out = { stdout: '', stderr: '' };
  child.stdout.on('data', (d) => {
    out.stdout += d;
  });
  child.stderr.on('data', (d) => {
    out.stderr += d;
  });
  const exited = new Promise((resolve) => child.once('exit', (code, signal) => resolve({ code, signal })));
  const ready = new Promise((resolve, reject) => {
    child.stdout.on('data', () => {
      const line = out.stdout.split('\n').find((l) => l.includes('"rf.ssr-node"'));
      if (line === undefined) return;
      try {
        resolve(JSON.parse(line));
      } catch (err) {
        reject(new Error(`the ready line is not one JSON object: ${JSON.stringify(line)} (${err.message})`));
      }
    });
    exited.then(() => reject(new Error(`exited before a ready line\nstdout: ${out.stdout}\nstderr: ${out.stderr}`)));
  });
  return { child, out, ready: withTimeout(ready, BOOT_MS, 'no ready line'), exited };
}

test('the launcher boots on port 0, announces itself, answers /health and a render, and stops on SIGTERM', async () => {
  const run = launch(['--module', fixture('reference'), '--port', '0', '--isolates', '1']);
  try {
    const ready = await run.ready;

    // The shape, field by field — see the header.
    assert.deepStrictEqual(
      Object.keys(ready),
      ['rf.ssr-node', 'url', 'host', 'port', 'buildId', 'protocol'],
      'the ready line carries these keys, in this order, and no others',
    );
    assert.strictEqual(ready['rf.ssr-node'], 'ready');
    assert.strictEqual(ready.host, '127.0.0.1', 'the default host, as the OS reports the bound address');
    assert.ok(Number.isInteger(ready.port) && ready.port > 0, `port 0 must become a real port; got ${ready.port}`);
    assert.strictEqual(ready.url, `http://127.0.0.1:${ready.port}`);
    assert.strictEqual(ready.buildId, 'reference-build-1');
    assert.strictEqual(ready.protocol, 1);

    const health = await fetch(`${ready.url}/health`);
    assert.strictEqual(health.status, 200);
    const body = await health.json();
    assert.strictEqual(body.status, 'ok');
    assert.strictEqual(body.buildId, ready.buildId, '/health and the ready line name the same bundle');
    assert.strictEqual(body.isolates.total, 1, '--isolates reached the pool');

    // One render and no `state`: the fixture renders its entry id, which
    // is all this row needs to see to know a request crossed the socket.
    const r = await post(`${ready.url}/render`, { protocol: 1, entry: 'app/root' });
    assert.strictEqual(r.status, 200, r.text);
    assert.strictEqual(r.headers.get('x-rf-ssr-build'), 'reference-build-1');
    assert.match(r.text, /^<div data-entry="app\/root"/);

    const asked = Date.now();
    run.child.kill('SIGTERM');
    const { code, signal } = await withTimeout(run.exited, STOP_MS, 'the launcher did not exit');
    assert.ok(Date.now() - asked <= STOP_MS, 'and it went within the bound');
    if (process.platform === 'win32') {
      // Node on Windows has no graceful signal: `kill` terminates the
      // process outright, so the graceful arm below is witnessed on the
      // POSIX runners and only the bound is witnessed here.
      assert.strictEqual(signal, 'SIGTERM');
    } else {
      assert.strictEqual(code, 0, `exit ${code} (${signal})\nstderr: ${run.out.stderr}`);
      assert.match(run.out.stderr, /SIGTERM: closing/, 'the close was the graceful one, not a crash');
    }
    assert.strictEqual(run.out.stdout.trim().split('\n').length, 1, 'stdout is the ready line and nothing else');
  } finally {
    if (run.child.exitCode === null && run.child.signalCode === null) run.child.kill('SIGKILL');
  }
});

test('a wrong command line is exit 2 with the usage on stderr, and nothing on stdout', () => {
  for (const [why, args] of [
    ['no --module', []],
    ['an unknown flag', ['--module', fixture('reference'), '--bogus', '1']],
    ['a non-integer port', ['--module', fixture('reference'), '--port', 'abc']],
    ['zero isolates', ['--module', fixture('reference'), '--isolates', '0']],
  ]) {
    const r = spawnSync(process.execPath, [LAUNCHER, ...args], { encoding: 'utf8', timeout: BOOT_MS });
    assert.strictEqual(r.status, 2, `${why}: exit ${r.status}\n${r.stderr}`);
    assert.match(r.stderr, /^usage: re-frame2-ssr-node --module <path>/, why);
    assert.strictEqual(r.stdout, '', `${why}: stdout is reserved for the ready line`);
  }
  const help = spawnSync(process.execPath, [LAUNCHER, '--help'], { encoding: 'utf8', timeout: BOOT_MS });
  assert.strictEqual(help.status, 0);
  assert.match(help.stdout, /^usage: re-frame2-ssr-node/, '--help is the one thing besides the ready line stdout carries');
});

test('a module the service refuses at boot is exit 1, the refusal code on stderr, and no ready line', () => {
  const r = spawnSync(process.execPath, [LAUNCHER, '--module', fixture('bad-protocol'), '--port', '0'], {
    encoding: 'utf8',
    timeout: BOOT_MS,
  });
  assert.strictEqual(r.status, 1, r.stderr);
  assert.match(r.stderr, /:rf\.ssr-node\/malformed-render-module/);
  assert.strictEqual(r.stdout, '');
});

test('the manifest points its bin at this launcher, exports the two entry points, and pins the Node CI runs', () => {
  assert.deepStrictEqual(Object.values(manifest.bin), ['./bin/serve.cjs']);
  assert.deepStrictEqual(manifest.exports, { './service': './src/service.cjs', './http': './src/http.cjs' });
  assert.strictEqual(manifest.engines.node, '>=24');
});
