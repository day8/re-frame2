#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-port.cjs` — the adapter-smoke
 * orchestrator's port resolver.
 *
 * The shared bind-probe / forward-scan mechanism (port-resolver.cjs) is
 * covered transitively by _story-feature-load-port.test.cjs; examples-port is
 * its near-identical policy sibling, so what is genuinely unexercised is the
 * examples-SPECIFIC policy: the DEFAULT_PORT (8050, the examples-owned 805x
 * band), the strict env parser bound to EXAMPLES_PORT, and the actionable
 * port-clash message. This mirrors the story-feature-load-port test against
 * those examples-specific surfaces.
 *
 * The port-clash assertions below pin the properties that make the message
 * useful, and require that it names no port at all beyond the one under
 * test — a pin that cannot go stale, because it has nothing to keep in step
 * with. Pinning the shadow-cljs.edn bands the message transcribed would match
 * the message rather than the map, so as the map grew the suite would stay
 * green BECAUSE the message had gone stale.
 *
 * Standalone node-runnable suite (no test framework), matching
 * _story-feature-load-port.test.cjs. Discovered by `npm run test:scripts`.
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

test('explicit occupied EXAMPLES_PORT throws an actionable message that points at the authorities', async () => {
  const port = 19052;
  const server = await occupy(port);
  try {
    await assert.rejects(
      () => resolveExamplesPort({ env: { EXAMPLES_PORT: String(port) } }),
      (err) => {
        assert.match(err.message, /already in use/);
        assert.equal(err.actionable, true, 'the port-clash error must be tagged actionable');

        // What makes this message worth raising: the likely CAUSE, both
        // AUTHORITIES a reader can look the live answer up in, and the two
        // REMEDIES. These are properties of the diagnostic, not of the port
        // map, so they stay true however the map grows.
        assert.match(err.message, /shadow-cljs watch/, 'must name the likely cause');
        assert.match(err.message, /:dev-http/, 'must name the claiming map');
        assert.match(
          err.message,
          /implementation\/shadow-cljs\.edn/,
          'must name the file holding the live port list',
        );
        assert.match(
          err.message,
          /OWNED-RANGE PORT MAP/,
          'must name the band-ownership authority',
        );
        assert.match(
          err.message,
          /implementation\/scripts\/dev-testbed\.cjs/,
          'must name the file holding the OWNED-RANGE PORT MAP',
        );
        assert.match(err.message, /Stop the watch/, 'must give the first remedy');
        assert.match(
          err.message,
          /set EXAMPLES_PORT to a free port/,
          'must give the second remedy',
        );

        // And it must transcribe NO port from that map. Pinning a transcribed
        // port list against the message would go on certifying stale wording
        // as the map grows. Pinning the ABSENCE of an enumeration has nothing
        // to keep in step with, so it cannot fail that way: the only
        // multi-digit run the message may carry is the port under test.
        const numbers = [...new Set(err.message.match(/\d{4,}/g) || [])];
        assert.deepEqual(
          numbers,
          [String(port)],
          `the message must name no port but the one under test — a ` +
            `transcribed :dev-http list goes stale in silence. ` +
            `Point at implementation/shadow-cljs.edn instead. Got: ${numbers}`,
        );
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
