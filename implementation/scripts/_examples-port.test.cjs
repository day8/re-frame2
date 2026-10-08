#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-port.cjs` — the adapter-smoke
 * orchestrator's port resolver. The shared parser and forward scan
 * (port-resolver.cjs) are covered by _story-feature-load-port.test.cjs; what is
 * examples-specific is the resolution wired to DEFAULT_PORT and EXAMPLES_PORT,
 * and the actionable port-clash error. Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert/strict');
const net = require('net');

const {
  DEFAULT_PORT,
  resolveExamplesPort,
} = require('../../examples/scripts/examples-port.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

test('EXAMPLES_PORT unset resolves DEFAULT_PORT (or the next free port)', async () => {
  const port = await resolveExamplesPort({ env: {} });
  assert.ok(Number.isInteger(port) && port >= DEFAULT_PORT, `got ${port}`);
});

test('explicit occupied EXAMPLES_PORT throws an actionable port-clash error', async () => {
  const port = 19052;
  const server = await new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(port, '127.0.0.1', () => resolve(s));
  });
  try {
    await assert.rejects(
      () => resolveExamplesPort({ env: { EXAMPLES_PORT: String(port) } }),
      (err) => err.actionable === true && /already in use/.test(err.message),
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
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
    console.error(`examples-port tests: ${failed} failed.`);
    process.exit(1);
  }

  console.log(`examples-port tests: ${tests.length} passed.`);
})();
