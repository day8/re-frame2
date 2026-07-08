#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-port.cjs` — the adapter-smoke
 * orchestrator's port resolver (rf2-0u6ce / rf2-ot0lv).
 *
 * The shared bind-probe / forward-scan mechanism (port-resolver.cjs) is
 * covered transitively by _story-feature-load-port.test.cjs; examples-port is
 * its near-identical policy sibling, so what is genuinely unexercised is the
 * examples-SPECIFIC policy: the DEFAULT_PORT (8050, the examples-owned 805x
 * band), the strict env parser bound to EXAMPLES_PORT, and the actionable
 * port-clash message whose wording hard-codes the shadow-cljs.edn 8765 /
 * 8030-8034 / 8040-8043 reserved bands (which could drift). This mirrors the
 * story-feature-load-port test against those examples-specific surfaces
 * (rf2-ewnznu).
 *
 * Standalone node-runnable suite (no test framework), matching
 * _story-feature-load-port.test.cjs. Wired into package.json test:script-policy.
 */

'use strict';

const assert = require('assert/strict');
const net = require('net');

const {
  DEFAULT_PORT,
  findAvailablePort,
  parseExplicitPort,
  resolveExamplesPort,
} = require('../../examples/scripts/examples-port.cjs');

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

test('DEFAULT_PORT is 8050, in the examples-owned 805x band', async () => {
  assert.equal(DEFAULT_PORT, 8050);
});

test('explicit EXAMPLES_PORT parses strictly', async () => {
  assert.equal(parseExplicitPort('8123'), 8123);
  assert.equal(parseExplicitPort(undefined), null);
  assert.equal(parseExplicitPort(''), null);
  assert.throws(() => parseExplicitPort('0'), /1\.\.65535/);
  assert.throws(() => parseExplicitPort('8050.5'), /1\.\.65535/);
  assert.throws(() => parseExplicitPort('nope'), /1\.\.65535/);
});

test('automatic resolution skips an occupied preferred port', async () => {
  const preferred = 19051;
  const server = await occupy(preferred);
  try {
    const port = await findAvailablePort(preferred, { attempts: 5 });
    assert.notEqual(port, preferred);
    assert.ok(port > preferred);
  } finally {
    await close(server);
  }
});

test('EXAMPLES_PORT unset resolves DEFAULT_PORT (or the next free port)', async () => {
  // No explicit override => DEFAULT_PORT if free, else forward-scan. The result
  // must be a valid port at or above the 8050 default.
  const port = await resolveExamplesPort({ env: {} });
  assert.ok(Number.isInteger(port) && port >= DEFAULT_PORT, `got ${port}`);
});

test('explicit occupied EXAMPLES_PORT throws an actionable, band-citing message', async () => {
  const port = 19052;
  const server = await occupy(port);
  try {
    await assert.rejects(
      () => resolveExamplesPort({ env: { EXAMPLES_PORT: String(port) } }),
      (err) => {
        assert.match(err.message, /already in use/);
        assert.equal(err.actionable, true, 'the port-clash error must be tagged actionable');
        // The wording hard-codes the shadow-cljs.edn reserved bands — pin them
        // so a drift in the message (or the port map) is caught (rf2-ewnznu).
        assert.match(err.message, /8765/);
        assert.match(err.message, /8030-8034/);
        assert.match(err.message, /8040-8043/);
        return true;
      },
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
    console.error(`examples-port tests: ${failed} failed.`);
    process.exit(1);
  }

  console.log(`examples-port tests: ${tests.length} passed.`);
})();
