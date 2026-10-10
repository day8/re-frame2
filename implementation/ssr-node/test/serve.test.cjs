'use strict';
// The launcher end to end: spawned the way a supervisor or a JVM host spawns
// it, read through the ready line, `/health` and one render. The ready line
// is parsed by those hosts, so its shape is pinned key by key.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { fixture, post } = require('./_support.cjs');
const manifest = require('../package.json');

const LAUNCHER = path.join(__dirname, '..', 'bin', 'serve.cjs');

/** Worker boot is the slow part, and a cold box is slower than this one. */
const BOOT_MS = 20000;
/** The bound a graceful close is held to. */
const STOP_MS = 5000;

const withTimeout = (p, ms, what) =>
  Promise.race([
    p,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${what} within ${ms} ms`)), ms).unref()),
  ]);

/** Spawn the launcher; `ready` resolves with the parsed ready line, found by its discriminator key. */
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
    assert.deepStrictEqual(
      Object.keys(ready),
      ['rf.ssr-node', 'url', 'host', 'port', 'buildId', 'protocol'],
      'the ready line carries these keys, in this order, and no others',
    );
    assert.ok(Number.isInteger(ready.port) && ready.port > 0, `port 0 must become a real port; got ${ready.port}`);
    assert.deepStrictEqual(ready, {
      'rf.ssr-node': 'ready',
      url: `http://127.0.0.1:${ready.port}`,
      host: '127.0.0.1',
      port: ready.port,
      buildId: 'reference-build-1',
      protocol: 1,
    });

    const health = await fetch(`${ready.url}/health`);
    assert.strictEqual(health.status, 200);
    assert.deepStrictEqual(await health.json(), {
      status: 'ok',
      protocol: 1,
      buildId: 'reference-build-1',
      entries: ['app/root', 'app/other'],
      isolates: { total: 1, ready: 1, busy: 0, waiting: 0, replacements: 0 },
    });

    const r = await post(`${ready.url}/render`, { protocol: 1, entry: 'app/root' });
    assert.strictEqual(r.status, 200, r.text);
    assert.strictEqual(r.headers.get('x-rf-ssr-build'), 'reference-build-1');
    assert.match(r.text, /^<div data-entry="app\/root"/);

    run.child.kill('SIGTERM');
    const { code, signal } = await withTimeout(run.exited, STOP_MS, 'the launcher did not exit');
    if (process.platform === 'win32') {
      // Windows has no graceful signal: `kill` terminates outright, so only
      // the bound is witnessed here and the graceful arm on POSIX runners.
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

test('a replacement that cannot boot ends the launcher with exit 1, so its supervisor restarts it', async () => {
  // The bundle changes on disk under the running service, so the replacement
  // loads a different build and is refused as a build-identity mismatch.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-ssr-node-'));
  const bundle = path.join(dir, 'bundle.cjs');
  fs.copyFileSync(fixture('flaky-boot'), bundle);
  const run = launch(['--module', bundle, '--port', '0', '--isolates', '1', '--timeout-ms', '300']);
  try {
    const ready = await run.ready;
    fs.writeFileSync(bundle, fs.readFileSync(bundle, 'utf8').replace('flaky-boot-build-1', 'flaky-boot-build-2'));
    const hung = await post(`${ready.url}/render`, { protocol: 1, entry: 'app/hang' });
    assert.strictEqual(hung.status, 504, hung.text);
    const { code } = await withTimeout(run.exited, BOOT_MS, 'the launcher did not exit');
    assert.strictEqual(code, 1, `exit ${code}\nstderr: ${run.out.stderr}`);
    assert.match(run.out.stderr, /the bundle changed on disk/, 'the operator is told why');
  } finally {
    if (run.child.exitCode === null && run.child.signalCode === null) run.child.kill('SIGKILL');
    fs.rmSync(dir, { recursive: true, force: true });
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
