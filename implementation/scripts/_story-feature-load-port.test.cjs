#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const net = require('net');

const {
  DEFAULT_BASE_PORT,
  DERIVED_PORT_SPAN,
  MAX_PORT_ATTEMPTS,
  findAvailablePort,
  parseExplicitPort,
  resolveStoryFeatureLoadPort,
} = require('../../examples/scripts/story-feature-load-port.cjs');
const { DEV_HTTP } = require('./dev-testbed.cjs');
const { DEFAULT_PORT: EXAMPLES_PORT } = require('../../examples/scripts/examples-port.cjs');

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

// A fixed port inside the hashed band, or inside the forward scan beyond it,
// is one a `watch` can take while a gate is serving there.
test('the derived band and its forward scan miss every fixed dev/test port', async () => {
  const lo = DEFAULT_BASE_PORT;
  const hi = DEFAULT_BASE_PORT + DERIVED_PORT_SPAN + MAX_PORT_ATTEMPTS;
  const fixed = [
    ...Object.entries(DEV_HTTP).map(([build, { port }]) => [build, port]),
    ['examples orchestrator', EXAMPLES_PORT],
    ['shadow-cljs UI', 9630],
  ];
  assert.deepEqual(
    fixed.filter(([, port]) => port >= lo && port < hi),
    [],
    `fixed ports inside ${lo}..${hi - 1}`,
  );
});

test('automatic resolution skips an occupied preferred port', async () => {
  const preferred = 19031;
  const server = await occupy(preferred);
  try {
    const port = await findAvailablePort(preferred, { attempts: 5 });
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
