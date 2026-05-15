#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const net = require('net');
const path = require('path');

const {
  DEFAULT_BASE_PORT,
  DERIVED_PORT_SPAN,
  findAvailablePort,
  parseExplicitPort,
  preferredPort,
  resolveStoryFeatureLoadPort,
} = require('../../examples/scripts/story-feature-load-port.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function occupy(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

test('explicit STORY_FEATURE_LOAD_PORT parses strictly', async () => {
  assert.equal(parseExplicitPort('8123'), 8123);
  assert.equal(parseExplicitPort(undefined), null);
  assert.throws(() => parseExplicitPort('0'), /1\.\.65535/);
  assert.throws(() => parseExplicitPort('8031.5'), /1\.\.65535/);
});

test('preferred port is deterministic per worktree root', async () => {
  const a = preferredPort(path.join('repo', 'worktree-a'));
  const b = preferredPort(path.join('repo', 'worktree-a'));
  assert.equal(a, b);
  assert.ok(a >= DEFAULT_BASE_PORT);
  assert.ok(a < DEFAULT_BASE_PORT + DERIVED_PORT_SPAN);
});

test('automatic resolution skips an occupied preferred port', async () => {
  const preferred = 19031;
  const server = await occupy(preferred);
  try {
    const port = await findAvailablePort(preferred, { attempts: 5 });
    assert.notEqual(port, preferred);
    assert.ok(port > preferred);
  } finally {
    await close(server);
  }
});

test('explicit occupied port fails with actionable message', async () => {
  const port = 19041;
  const server = await occupy(port);
  try {
    await assert.rejects(
      () => resolveStoryFeatureLoadPort({ env: { STORY_FEATURE_LOAD_PORT: String(port) } }),
      /already in use.*unique port/,
    );
  } finally {
    await close(server);
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
    console.error(`story-feature-load-port tests: ${failed} failed.`);
    process.exit(1);
  }

  console.log(`story-feature-load-port tests: ${tests.length} passed.`);
})();
