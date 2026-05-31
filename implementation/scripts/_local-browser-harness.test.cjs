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
