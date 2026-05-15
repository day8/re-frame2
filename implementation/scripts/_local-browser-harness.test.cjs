#!/usr/bin/env node
'use strict';

const assert = require('assert/strict');
const http = require('http');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  terminateProcessTree,
  waitForHttpReady,
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
