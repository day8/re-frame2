#!/usr/bin/env node
'use strict';

const assert = require('assert/strict');
const crypto = require('crypto');
const http = require('http');
const net = require('net');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  fetchToken,
  findFreePort,
  isPortFree,
  isValidExplicitPort,
  probeTargetFromBaseUrl,
  resolveServePort,
  spawnHarnessProcess,
  terminateProcessTree,
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

test('waitForHttpReady resolves true for a reachable local server', async () => {
  const server = http.createServer((_, res) => {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
  });
  const port = await listenOnLoopback(server);
  try {
    assert.equal(
      await waitForHttpReady(port, Date.now() + 1000, { pollMs: 10 }),
      true,
    );
  } finally {
    server.close();
  }
});

test('waitForHttpReady stops when aborted', async () => {
  assert.equal(
    await waitForHttpReady(1, Date.now() + 1000, {
      isAborted: () => true,
      pollMs: 10,
    }),
    false,
  );
});

test('cleanup manager runs cleanup callbacks once', async () => {
  let calls = 0;
  const cleanup = createHarnessCleanup({ onError: () => {} });
  cleanup.addCleanup(() => { calls += 1; });
  await cleanup.cleanup();
  await cleanup.cleanup();
  cleanup.cleanupSync();
  assert.equal(calls, 1);
});

test('terminateProcessTree stops a managed child process', async () => {
  const child = spawnHarnessProcess(process.execPath, [
    '-e',
    'setInterval(() => {}, 1000)',
  ], {
    stdio: ['ignore', 'ignore', 'ignore'],
  });

  const exitPromise = waitForExit(child);
  await terminateProcessTree(child, { timeoutMs: 2000 });
  const exit = await exitPromise;
  assert.notEqual(exit, null);
});

// rf2-ogpeq regression (the core race). The async cleanup() kills tracked
// children sequentially (last-tracked first), awaiting each. If
// process.exit() fires the 'exit' handler -> cleanupSync() while cleanup()
// is still mid-flight — it has reached child[last] and is awaiting its
// termination, but has NOT yet reached the earlier children — cleanupSync
// MUST still synchronously terminate those earlier children. Node cannot
// resume the async cleanup's pending awaits once the process is exiting,
// so anything cleanupSync skips is ORPHANED.
//
// The old code shared a single `cleaned` flag: the async cleanup set it at
// the start, so cleanupSync saw `cleaned===true` and returned immediately,
// skipping the un-reached children.
//
// We model this deterministically via the injectable terminator seam: the
// async terminator NEVER resolves (modelling a hard exit that abandons the
// in-flight cleanup), and the sync terminator records which children it
// swept. The invariant: after cleanupSync(), EVERY tracked child has been
// synchronously swept — regardless of the async cleanup being mid-flight.
test('cleanupSync sweeps every child when async cleanup() is mid-flight (rf2-ogpeq)', async () => {
  const childA = { id: 'A' };
  const childB = { id: 'B' };
  const sweptSync = [];

  const cleanup = createHarnessCleanup({
    onError: () => {},
    // Records sync sweeps. Idempotent in spirit: real terminateProcessTreeSync
    // no-ops on dead children; here we just record the attempt.
    terminateSync: (child) => { sweptSync.push(child.id); },
    // Models an in-flight async kill that never completes — i.e. the
    // process is exiting and these awaits will never resume.
    terminateAsync: () => new Promise(() => {}),
  });
  cleanup.trackProcess(childA);
  cleanup.trackProcess(childB);

  // Start the async cleanup. Its IIFE runs synchronously up to the first
  // `await terminateAsync(...)`, which never resolves — cleanup() is now
  // permanently mid-flight (modelling abandonment by a hard exit).
  cleanup.cleanup();

  // The 'exit' handler fires. With the bug this returns without sweeping;
  // with the fix it synchronously sweeps every tracked child.
  cleanup.cleanupSync();

  assert.deepEqual(
    [...sweptSync].sort(),
    ['A', 'B'],
    'cleanupSync must synchronously terminate every tracked child even when ' +
      'an async cleanup() is mid-flight (otherwise children are orphaned on hard exit)',
  );
});

// End-to-end companion: with REAL child processes, both terminate on the
// cleanupSync path. Generous timeout because on Windows the kill goes
// through `taskkill /T /F` (spawnSync), which can take several seconds per
// child on a loaded machine — we only assert THAT they die, not how fast.
test('cleanupSync terminates real tracked child processes', async () => {
  const spawnLongLived = () => spawnHarnessProcess(process.execPath, [
    '-e',
    'setInterval(() => {}, 1000)',
  ], { stdio: ['ignore', 'ignore', 'ignore'] });

  const cleanup = createHarnessCleanup({ onError: () => {} });
  const first = cleanup.trackProcess(spawnLongLived());
  const second = cleanup.trackProcess(spawnLongLived());

  const firstExit = waitForExit(first, 30000);
  const secondExit = waitForExit(second, 30000);

  cleanup.cleanupSync();

  await Promise.all([firstExit, secondExit]);
  assert.ok(true);
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

// rf2-84gzw regression — free-port resolution.
test('resolveServePort returns the preferred port when it is free', async () => {
  const free = await findFreePort();
  const resolved = await resolveServePort(free);
  assert.equal(resolved, free);
});

test('resolveServePort falls back to a different free port when preferred is busy', async () => {
  // Occupy a port, then ask resolveServePort to prefer it.
  const occupied = await findFreePort();
  const squatter = net.createServer();
  await new Promise((resolve, reject) => {
    squatter.once('error', reject);
    squatter.listen(occupied, '127.0.0.1', resolve);
  });
  try {
    let fellBack = false;
    const resolved = await resolveServePort(occupied, {
      onFallback: () => { fellBack = true; },
    });
    assert.equal(fellBack, true);
    assert.notEqual(resolved, occupied);
    assert.equal(await isPortFree(resolved), true);
  } finally {
    await new Promise((r) => squatter.close(r));
  }
});

// rf2-84gzw regression — ownership-token verification.
test('waitForOwnedHttpReady resolves ok when the served token matches', async () => {
  const token = crypto.randomBytes(8).toString('hex');
  const server = http.createServer((req, res) => {
    if (req.url === `/${TOKEN_FILE_BASENAME}`) {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end(token);
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
  });
  const port = await listenOnLoopback(server);
  try {
    const result = await waitForOwnedHttpReady(port, token, Date.now() + 2000, { pollMs: 10 });
    assert.deepEqual(result, { ok: true });
    assert.equal(await fetchToken(port), token);
  } finally {
    server.close();
  }
});

test('waitForOwnedHttpReady refuses a foreign server (token mismatch)', async () => {
  // A reachable server that serves a DIFFERENT token — i.e. a port
  // squatter / stale server from another run. The gate must refuse to
  // proceed against it.
  const server = http.createServer((req, res) => {
    if (req.url === `/${TOKEN_FILE_BASENAME}`) {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('a-foreign-token');
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
  });
  const port = await listenOnLoopback(server);
  try {
    const result = await waitForOwnedHttpReady(port, 'our-token', Date.now() + 1000, { pollMs: 10 });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'token-mismatch');
    assert.equal(result.got, 'a-foreign-token');
  } finally {
    server.close();
  }
});

// rf2-vtp2er — child-exited abort path. Both migrated callers
// (serve-and-run-browser-tests.cjs, check-story-static.cjs) and the Xray
// gate pass `isAborted: () => <server died>` so the owned-readiness wait
// fails fast when http-server exits before becoming reachable, instead of
// burning the full timeout. Model the server never coming up + isAborted
// flipping true: the wait must resolve { ok:false, reason:'child-exited' }.
test('waitForOwnedHttpReady aborts with child-exited when isAborted goes true', async () => {
  let aborted = false;
  const result = await waitForOwnedHttpReady(1, 'our-token', Date.now() + 2000, {
    pollMs: 10,
    isAborted: () => aborted,
  });
  // Flip after construction is irrelevant here — :1 is unreachable, so the
  // first isAborted() check inside the loop decides. Assert the immediate
  // pre-flighted abort path.
  aborted = true;
  const result2 = await waitForOwnedHttpReady(1, 'our-token', Date.now() + 2000, {
    pollMs: 10,
    isAborted: () => aborted,
  });
  // result1 used aborted=false the whole time against an unreachable port →
  // it must time out (never reachable), not falsely report child-exited.
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'timeout');
  // result2 had isAborted()===true from the first check → child-exited.
  assert.equal(result2.ok, false);
  assert.equal(result2.reason, 'child-exited');
});

// rf2-vtp2er — the exact owned-readiness happy path the migrated browser-
// test / story-static launchers now drive: a server that starts tokenless
// (http-server is up but hasn't published /.rf-harness-token yet) and then
// begins serving the matching token. The wait must keep polling through the
// tokenless window and resolve ok once the token appears — proving the
// shared primitive covers the "server racing to publish the sentinel" shape
// the bespoke per-caller waitForReady copies handled.
test('waitForOwnedHttpReady polls through a tokenless window then succeeds when the token appears', async () => {
  const token = crypto.randomBytes(8).toString('hex');
  let tokenLive = false;
  const server = http.createServer((req, res) => {
    if (req.url === `/${TOKEN_FILE_BASENAME}`) {
      if (!tokenLive) {
        res.writeHead(404);
        res.end('not yet');
        return;
      }
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end(token);
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
  });
  const port = await listenOnLoopback(server);
  // Publish the token shortly after the wait begins.
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
  // Reachable, but never serves /.rf-harness-token (404). Must not be
  // mistaken for "ours".
  const server = http.createServer((_, res) => {
    res.writeHead(404);
    res.end('nope');
  });
  const port = await listenOnLoopback(server);
  try {
    const result = await waitForOwnedHttpReady(port, 'our-token', Date.now() + 400, { pollMs: 20 });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'token-never-served');
  } finally {
    server.close();
  }
});

// rf2-0u8kz regression — explicit-port validation. An explicit preferred
// port is usable only as an integer in 1..65535; 0 / negative / overflow /
// non-integer values must NOT bind net.listen (0 would bind an unusable
// ephemeral port — a false "free"; the others throw ERR_SOCKET_BAD_PORT).
test('isValidExplicitPort accepts only integers 1..65535', () => {
  assert.equal(isValidExplicitPort(1), true);
  assert.equal(isValidExplicitPort(8037), true);
  assert.equal(isValidExplicitPort(65535), true);
  // Invalid: 0, negative, overflow, non-integer, NaN, non-number.
  assert.equal(isValidExplicitPort(0), false);
  assert.equal(isValidExplicitPort(-1), false);
  assert.equal(isValidExplicitPort(65536), false);
  assert.equal(isValidExplicitPort(99999), false);
  assert.equal(isValidExplicitPort(8037.5), false);
  assert.equal(isValidExplicitPort(NaN), false);
  assert.equal(isValidExplicitPort('8037'), false);
  assert.equal(isValidExplicitPort(undefined), false);
  assert.equal(isValidExplicitPort(null), false);
});

test('isPortFree reports false for 0 / out-of-range / non-integer without throwing', async () => {
  // Port 0 binds an ephemeral port at the OS layer, but is not a usable
  // ADVERTISED port — isPortFree must short-circuit to false rather than
  // bind-and-release it (the bug: returning true here let callers advertise
  // :0). Negative / overflow / non-integer must not reach net.listen
  // (which throws ERR_SOCKET_BAD_PORT) — the guard reports false cleanly.
  assert.equal(await isPortFree(0), false);
  assert.equal(await isPortFree(-1), false);
  assert.equal(await isPortFree(65536), false);
  assert.equal(await isPortFree(99999), false);
  assert.equal(await isPortFree(8037.5), false);
  assert.equal(await isPortFree(NaN), false);
});

test('resolveServePort never returns 0 and falls back (logged) for invalid preferred ports', async () => {
  for (const bad of [0, -1, 65536, 99999, 8037.5, NaN, undefined, '8037']) {
    let fellBack = false;
    let reported;
    const resolved = await resolveServePort(bad, {
      onFallback: (preferred) => { fellBack = true; reported = preferred; },
    });
    assert.equal(fellBack, true, `expected fallback for preferred=${String(bad)}`);
    assert.equal(reported, bad, 'onFallback receives the original invalid preferred value');
    assert.ok(isValidExplicitPort(resolved), `fallback ${resolved} must be a usable port`);
    assert.notEqual(resolved, 0);
    assert.equal(await isPortFree(resolved), true);
  }
});

// rf2-rcepku — XRAY_FEATURE_GATE_BASE_URL probe-target parsing. The Xray
// feature gate's external-server escape hatch previously extracted only a
// port and probed loopback `/`, so a base URL with a non-127.0.0.1 host,
// an https scheme, or a meaningful base path was probed against the wrong
// endpoint shape. probeTargetFromBaseUrl now derives {host, port, path,
// protocol} from the parsed URL so the readiness probe hits the endpoint
// the caller actually pointed at.

test('probeTargetFromBaseUrl honours a non-127.0.0.1 host + base path (rf2-rcepku)', () => {
  const target = probeTargetFromBaseUrl('http://staging.internal:8080/app/base/');
  assert.deepEqual(target, {
    host: 'staging.internal',
    port: 8080,
    path: '/app/base/',
    protocol: 'http:',
  });
});

test('probeTargetFromBaseUrl defaults the http port to 80 and path to / (rf2-rcepku)', () => {
  const target = probeTargetFromBaseUrl('http://example.com');
  assert.equal(target.host, 'example.com');
  assert.equal(target.port, 80);
  assert.equal(target.path, '/');
  assert.equal(target.protocol, 'http:');
});

test('probeTargetFromBaseUrl honours the https scheme + default 443 (rf2-rcepku)', () => {
  const target = probeTargetFromBaseUrl('https://secure.example.com/xray/');
  assert.equal(target.host, 'secure.example.com');
  assert.equal(target.port, 443);
  assert.equal(target.path, '/xray/');
  assert.equal(target.protocol, 'https:');
});

test('probeTargetFromBaseUrl keeps an explicit port over the scheme default (rf2-rcepku)', () => {
  const target = probeTargetFromBaseUrl('https://secure.example.com:8443/');
  assert.equal(target.port, 8443);
  assert.equal(target.protocol, 'https:');
});

test('probeTargetFromBaseUrl throws on a malformed URL rather than falling back (rf2-rcepku)', () => {
  // A silent loopback fallback is how an invalid escape-hatch value could
  // probe an unrelated local listener and then send the browser at a
  // broken external URL — so a bad value must fail loud.
  assert.throws(
    () => probeTargetFromBaseUrl('not a url', { envName: 'XRAY_FEATURE_GATE_BASE_URL' }),
    /XRAY_FEATURE_GATE_BASE_URL is not a valid URL/,
  );
});

test('probeTargetFromBaseUrl rejects a non-http(s) scheme (rf2-rcepku)', () => {
  assert.throws(
    () => probeTargetFromBaseUrl('ftp://example.com/', { envName: 'XRAY_FEATURE_GATE_BASE_URL' }),
    /must use http: or https:/,
  );
});

test('waitForHttpReady probes the supplied host + path (rf2-rcepku, rf2-p8xl35)', async () => {
  // rf2-p8xl35 — bind the PATH probe directly. probeHttp resolves ready on
  // ANY HTTP status (liveness, not 2xx), so a regression that hard-coded
  // `/` instead of forwarding opts.path would still receive a response
  // (a 404) and still report ready — leaving the old assertion (`ready ===
  // true` against a server that 404s every other path) green under the
  // bug. Observe the requested URL path directly instead: record every
  // req.url and assert the advertised basePath was the path probed (and a
  // bare `/` was NOT). This makes the path-forwarding contract load-bearing.
  const basePath = '/app/base/';
  const requestedPaths = [];
  const server = http.createServer((req, res) => {
    requestedPaths.push(req.url);
    if (req.url === basePath) {
      res.writeHead(200);
      res.end('ok');
      return;
    }
    res.writeHead(404);
    res.end('nope');
  });
  const port = await listenOnLoopback(server);
  try {
    const ready = await waitForHttpReady(port, Date.now() + 1000, {
      host: '127.0.0.1',
      path: basePath,
      pollMs: 10,
    });
    assert.equal(ready, true, 'the advertised base-path server must be seen as ready');
    // The probe must have requested the caller-supplied path verbatim — a
    // regression that hard-codes `/` would record `/` here and trip this.
    assert.ok(
      requestedPaths.includes(basePath),
      `waitForHttpReady must probe the supplied path ${JSON.stringify(basePath)}; ` +
        `observed requests: ${JSON.stringify(requestedPaths)}`,
    );
    assert.ok(
      !requestedPaths.includes('/'),
      `waitForHttpReady must NOT fall back to the hard-coded root path "/"; ` +
        `observed requests: ${JSON.stringify(requestedPaths)}`,
    );
  } finally {
    server.close();
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
