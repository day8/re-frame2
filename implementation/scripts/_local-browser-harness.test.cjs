#!/usr/bin/env node
'use strict';

const assert = require('assert/strict');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  findFreePort,
  isValidExplicitPort,
  probeTargetFromBaseUrl,
  publishOwnershipToken,
  resolveServePort,
  spawnHarnessProcess,
  startLocalHttpServer,
  waitForHttpReady,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function listenOnLoopback(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function waitForExit(child, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.off('exit', onExit);
      reject(new Error(`child ${child.pid} did not exit within ${timeoutMs}ms`));
    }, timeoutMs);
    const onExit = (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    };
    child.once('exit', onExit);
  });
}

// The process 'exit' handler (cleanupSync) can fire while an async cleanup() is
// still awaiting one child's termination; Node never resumes those awaits, so
// any child cleanupSync skips is orphaned. The async terminator here never
// resolves, modelling exactly that abandonment.
test('cleanupSync sweeps every child when async cleanup() is mid-flight', async () => {
  const sweptSync = [];
  const cleanup = createHarnessCleanup({
    onError: () => {},
    terminateSync: (child) => { sweptSync.push(child.id); },
    terminateAsync: () => new Promise(() => {}),
  });
  cleanup.trackProcess({ id: 'A' });
  cleanup.trackProcess({ id: 'B' });

  cleanup.cleanup();
  cleanup.cleanupSync();

  assert.deepEqual([...sweptSync].sort(), ['A', 'B']);
});

test('cleanupSync terminates a real tracked child process', async () => {
  const cleanup = createHarnessCleanup({ onError: () => {} });
  const child = cleanup.trackProcess(
    spawnHarnessProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' }),
  );
  // Generous: on Windows the kill goes through `taskkill /T /F`.
  const exited = waitForExit(child, 30000);
  cleanup.cleanupSync();
  await exited;
});

test('cleanup runs each addCleanup fn at most once across sync + async paths', async () => {
  let calls = 0;
  const cleanup = createHarnessCleanup({ onError: () => {} });
  cleanup.addCleanup(() => { calls += 1; });
  await cleanup.cleanup();
  cleanup.cleanupSync();
  await cleanup.cleanup();
  assert.equal(calls, 1);
});

test('resolveServePort keeps a free preferred port and falls back, reported, from a busy or invalid one', async () => {
  const preferred = await findFreePort();
  assert.equal(await resolveServePort(preferred), preferred);

  const squatter = net.createServer();
  await new Promise((resolve, reject) => {
    squatter.once('error', reject);
    squatter.listen(preferred, '127.0.0.1', resolve);
  });
  try {
    // 0 would bind an ephemeral port, so it must never be advertised.
    for (const bad of [preferred, 0]) {
      let reported;
      const resolved = await resolveServePort(bad, { onFallback: (p) => { reported = p; } });
      assert.equal(reported, bad);
      assert.ok(
        isValidExplicitPort(resolved) && resolved !== bad,
        `the fallback for ${bad} must be a different usable port, got ${resolved}`,
      );
    }
  } finally {
    await new Promise((r) => squatter.close(r));
  }
});

test('waitForOwnedHttpReady refuses a foreign server (token mismatch)', async () => {
  const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end('a-foreign-token');
  });
  const port = await listenOnLoopback(server);
  try {
    assert.deepEqual(
      await waitForOwnedHttpReady(port, 'our-token', Date.now() + 1000, { pollMs: 10 }),
      { ok: false, reason: 'token-mismatch', got: 'a-foreign-token' },
    );
  } finally {
    server.close();
  }
});

test('waitForOwnedHttpReady aborts with child-exited when isAborted goes true', async () => {
  // Port 1 is unreachable: without the abort the same wait ends as a timeout.
  assert.deepEqual(
    await waitForOwnedHttpReady(1, 'our-token', Date.now() + 2000, { pollMs: 10 }),
    { ok: false, reason: 'timeout' },
  );
  assert.deepEqual(
    await waitForOwnedHttpReady(1, 'our-token', Date.now() + 2000, { pollMs: 10, isAborted: () => true }),
    { ok: false, reason: 'child-exited' },
  );
});

test('waitForOwnedHttpReady polls through a tokenless window then succeeds when the token appears', async () => {
  const token = crypto.randomBytes(8).toString('hex');
  let tokenLive = false;
  const server = http.createServer((req, res) => {
    const isToken = req.url === `/${TOKEN_FILE_BASENAME}`;
    res.writeHead(isToken && !tokenLive ? 404 : 200);
    res.end(isToken && tokenLive ? token : 'ok');
  });
  const port = await listenOnLoopback(server);
  const flip = setTimeout(() => { tokenLive = true; }, 80);
  try {
    const result = await waitForOwnedHttpReady(port, token, Date.now() + 3000, { pollMs: 10 });
    assert.deepEqual(result, { ok: true });
  } finally {
    clearTimeout(flip);
    server.close();
  }
});

test('waitForOwnedHttpReady reports token-never-served for a tokenless responder', async () => {
  const server = http.createServer((_, res) => {
    res.writeHead(404);
    res.end('nope');
  });
  const port = await listenOnLoopback(server);
  try {
    assert.deepEqual(
      await waitForOwnedHttpReady(port, 'our-token', Date.now() + 400, { pollMs: 20 }),
      { ok: false, reason: 'token-never-served' },
    );
  } finally {
    server.close();
  }
});

test('isValidExplicitPort accepts only integers 1..65535', () => {
  assert.deepEqual(
    [1, 65535, 0, 65536, 8037.5, '8037'].map((p) => isValidExplicitPort(p)),
    [true, true, false, false, false, false],
  );
});

test('probeTargetFromBaseUrl derives host, port, path and protocol from the base URL (rf2-rcepku)', () => {
  assert.deepEqual(
    [
      'http://staging.internal:8080/app/base/',
      'http://example.com',
      'https://secure.example.com/xray/',
    ].map((url) => probeTargetFromBaseUrl(url)),
    [
      { host: 'staging.internal', port: 8080, path: '/app/base/', protocol: 'http:' },
      { host: 'example.com', port: 80, path: '/', protocol: 'http:' },
      { host: 'secure.example.com', port: 443, path: '/xray/', protocol: 'https:' },
    ],
  );
});

test('probeTargetFromBaseUrl throws on a malformed or non-http(s) URL rather than falling back', () => {
  assert.throws(() => probeTargetFromBaseUrl('not a url'));
  assert.throws(() => probeTargetFromBaseUrl('ftp://example.com/'));
});

test('waitForHttpReady probes the supplied host + path (rf2-rcepku, rf2-p8xl35)', async () => {
  // probeHttp counts ANY status as live, so only the requested path shows
  // whether the caller's path was forwarded rather than a hard-coded `/`.
  const basePath = '/app/base/';
  const requestedPaths = [];
  const server = http.createServer((req, res) => {
    requestedPaths.push(req.url);
    res.writeHead(404);
    res.end();
  });
  const port = await listenOnLoopback(server);
  try {
    const ready = await waitForHttpReady(port, Date.now() + 1000, {
      host: '127.0.0.1',
      path: basePath,
      pollMs: 10,
    });
    assert.deepEqual({ ready, requestedPaths }, { ready: true, requestedPaths: [basePath] });
  } finally {
    server.close();
  }
});

test('publishOwnershipToken returns null for a missing root (caller-controlled policy) (rf2-pgppmu)', () => {
  const missing = path.join(os.tmpdir(), `rf2-token-missing-${crypto.randomBytes(6).toString('hex')}`);
  assert.equal(publishOwnershipToken(missing), null);
});

test('publishOwnershipToken remove() preserves a newer overlapping run\'s replacement token (rf2-pgppmu)', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-token-'));
  try {
    const runA = publishOwnershipToken(root);
    const runB = publishOwnershipToken(root);
    runA.remove();
    assert.equal(fs.readFileSync(path.join(root, TOKEN_FILE_BASENAME), 'utf8'), runB.token);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// Fake http-server bins: each parses the http-server-shaped argv, so the
// startLocalHttpServer composition runs without the http-server package.
//
// FAKE_HTTP_SERVER binds the requested host+port, serves the ownership token
// file startLocalHttpServer published under `root` (as the real http-server
// serves the dotfile), and records the argv it was given once listening.
const FAKE_HTTP_SERVER = `
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const argv = process.argv.slice(2);
const root = argv[0];
let host = '0.0.0.0';
let port = 0;
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '-a') host = argv[i + 1];
  else if (argv[i] === '-p') port = Number(argv[i + 1]);
}
const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (url === '/.rf-harness-token') {
    try {
      const body = fs.readFileSync(path.join(root, '.rf-harness-token'), 'utf8');
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end(body);
    } catch (_) {
      res.writeHead(404);
      res.end('no token');
    }
    return;
  }
  res.writeHead(200);
  res.end('ok');
});
server.listen(port, host, () => {
  fs.writeFileSync(path.join(root, '.fake-argv.json'), JSON.stringify(argv));
});
`;

// Exits 3 on EADDRINUSE, as the real http-server does when a stale or sibling
// listener squatted the port between resolveServePort() and this spawn.
const FAKE_HTTP_LOSES_BIND = `
'use strict';
const http = require('http');
const argv = process.argv.slice(2);
let host = '0.0.0.0';
let port = 0;
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '-a') host = argv[i + 1];
  else if (argv[i] === '-p') port = Number(argv[i + 1]);
}
const server = http.createServer((_, res) => { res.writeHead(200); res.end('ours'); });
server.once('error', () => process.exit(3));
server.listen(port, host);
`;

// Writes one line, then stays alive without ever listening: a readiness timeout.
const FAKE_HTTP_SILENT = `
'use strict';
const fs = require('fs');
fs.writeSync(2, 'fake http-server: staged output line\\n');
setInterval(() => {}, 1000);
`;

// Exits non-zero at once: the early-exit path the readiness abort must catch.
const FAKE_HTTP_CRASH = `
'use strict';
process.exit(3);
`;

function mkFakeBin(source, name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-slh-'));
  const binPath = path.join(dir, name);
  fs.writeFileSync(binPath, source);
  return { dir, binPath };
}

function rmTmp(dir) {
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
}

test('startLocalHttpServer composes the canonical loopback/static argv, reaches owned readiness, and its cleanup stops the server and removes its token', async () => {
  const { dir, binPath } = mkFakeBin(FAKE_HTTP_SERVER, 'fake-http-server.cjs');
  const port = await findFreePort();
  const cleanup = createHarnessCleanup({ onError: () => {} });
  try {
    const { server, ready } = await startLocalHttpServer({
      cleanup,
      httpServerBin: binPath,
      root: dir,
      port,
      cwd: dir,
      readyTimeoutMs: 5000,
      // Silences the "exited unexpectedly" line the teardown force-kill emits on Windows.
      log: () => {},
    });
    assert.equal(ready, true);
    assert.deepEqual(
      JSON.parse(fs.readFileSync(path.join(dir, '.fake-argv.json'), 'utf8')),
      [dir, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'],
    );
    const exited = waitForExit(server, 30000);
    await cleanup.cleanup();
    await exited;
    assert.equal(fs.existsSync(path.join(dir, TOKEN_FILE_BASENAME)), false);
  } finally {
    await cleanup.cleanup();
    rmTmp(dir);
  }
});

test('startLocalHttpServer appends the unresolved-request fallback ONLY when asked (rf2-fzbj.35)', async () => {
  // The test above pins the absent case. `--proxy` takes its value as the next token.
  const { dir, binPath } = mkFakeBin(FAKE_HTTP_SERVER, 'fake-http-server.cjs');
  const port = await findFreePort();
  const cleanup = createHarnessCleanup({ onError: () => {} });
  try {
    await startLocalHttpServer({
      cleanup,
      httpServerBin: binPath,
      root: dir,
      port,
      cwd: dir,
      readyTimeoutMs: 5000,
      log: () => {},
      unresolvedRequestUrl: 'http://127.0.0.1:65001',
    });
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, '.fake-argv.json'), 'utf8')), [
      dir, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1',
      '--proxy', 'http://127.0.0.1:65001',
    ]);
  } finally {
    await cleanup.cleanup();
    rmTmp(dir);
  }
});

test('startLocalHttpServer times out with the unreachable diagnostic + captured failure tail (rf2-slapfs)', async () => {
  const { dir, binPath } = mkFakeBin(FAKE_HTTP_SILENT, 'fake-http-silent.cjs');
  const port = await findFreePort();
  const cleanup = createHarnessCleanup({ onError: () => {} });
  const logged = [];
  try {
    await startLocalHttpServer({
      cleanup,
      httpServerBin: binPath,
      root: dir,
      port,
      cwd: dir,
      readyTimeoutMs: 400,
      captureOutput: true,
      log: (m) => logged.push(m),
    });
    assert.ok(
      logged.some((l) => /did not become reachable on :/.test(l)),
      `the timeout diagnostic must be logged; got ${JSON.stringify(logged)}`,
    );
    assert.ok(
      logged.some((l) => /staged output line/.test(l)),
      'the captured failure tail must be printed via the log sink',
    );
  } finally {
    await cleanup.cleanup();
    rmTmp(dir);
  }
});

test('startLocalHttpServer aborts fast on an early server exit rather than burning the timeout (rf2-slapfs)', async () => {
  const { dir, binPath } = mkFakeBin(FAKE_HTTP_CRASH, 'fake-http-crash.cjs');
  const port = await findFreePort();
  const cleanup = createHarnessCleanup({ onError: () => {} });
  let exitCode = null;
  const started = Date.now();
  try {
    const { ready, isDown } = await startLocalHttpServer({
      cleanup,
      httpServerBin: binPath,
      root: dir,
      port,
      cwd: dir,
      readyTimeoutMs: 15000,
      log: () => {},
      onExit: (code) => { exitCode = code; },
    });
    assert.deepEqual({ ready, down: isDown(), exitCode }, { ready: false, down: true, exitCode: 3 });
    assert.ok(
      Date.now() - started < 10000,
      'the early-exit abort must not wait out the 15s readiness budget',
    );
  } finally {
    cleanup.cleanupSync();
    rmTmp(dir);
  }
});

// Owned readiness is the default: an unowned liveness wait would accept a
// foreign server that won the non-atomic resolveServePort()->spawn handoff.
test('startLocalHttpServer refuses a foreign asset tree holding the port (rf2-3fc89f.14)', async () => {
  const { dir, binPath } = mkFakeBin(FAKE_HTTP_LOSES_BIND, 'fake-http-loses-bind.cjs');
  // Answers every path, the ownership-token path included.
  const foreign = http.createServer((_, res) => {
    res.writeHead(200);
    res.end('FOREIGN-ASSET-TREE');
  });
  const port = await listenOnLoopback(foreign);
  const cleanup = createHarnessCleanup({ onError: () => {} });
  try {
    const { ready } = await startLocalHttpServer({
      cleanup,
      httpServerBin: binPath,
      root: dir,
      port,
      cwd: dir,
      readyTimeoutMs: 4000,
      log: () => {},
    });
    assert.equal(ready, false);
  } finally {
    await cleanup.cleanup();
    await new Promise((r) => foreign.close(r));
    rmTmp(dir);
  }
});

test('startLocalHttpServer throws loudly when the staging root is missing or is a file', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-notdir-'));
  const filePath = path.join(dir, 'a-file');
  fs.writeFileSync(filePath, 'x');
  const cleanup = createHarnessCleanup({ onError: () => {} });
  try {
    for (const root of [path.join(dir, 'missing'), filePath]) {
      await assert.rejects(() =>
        startLocalHttpServer({ cleanup, httpServerBin: 'unused', root, port: 1, readyTimeoutMs: 500 }),
      );
    }
  } finally {
    rmTmp(dir);
  }
});

(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    try {
      await fn();
    } catch (err) {
      failed += 1;
      console.error(`FAIL ${name}`);
      console.error(err && err.stack ? err.stack : err);
    }
  }

  if (failed > 0) {
    console.error(`local-browser-harness tests: ${failed} failed.`);
    process.exit(1);
  }

  console.log(`local-browser-harness tests: ${tests.length} passed.`);
})().catch((err) => {
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
