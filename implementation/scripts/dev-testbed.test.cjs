#!/usr/bin/env node
/*
 * Tests for `dev-testbed.cjs` arg resolution + URL printing.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `_path-policy.test.cjs`. Each test logs PASS / FAIL; the process exits
 * 0 only when every test passes. Wired into `package.json` via
 * `test:script-helpers`.
 *
 * Requiring `dev-testbed.cjs` does NOT spawn shadow-cljs: the CLI body is
 * guarded by `require.main === module`, so importing it here only loads
 * the pure `resolveArgs` / `urlsForBuild` helpers + the DEV_HTTP map.
 *
 * Group aliases (xray / stories / epochs / all) were removed (rf2-trlj7):
 * `npm run dev` takes EXPLICIT build-ids only. `resolveArgs` now just
 * passes tokens through, de-duplicating build-ids in first-seen order.
 */

'use strict';

const assert = require('assert');
const { DEV_HTTP, resolveArgs, urlsForBuild } = require('./dev-testbed.cjs');

let failed = 0;

function it(label, f) {
  try {
    f();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${err.message || err}`);
  }
}

console.log('dev-testbed arg-resolution tests (rf2-trlj7)');

it('a single explicit build-id passes through unchanged', () => {
  assert.deepStrictEqual(resolveArgs([':examples/standard-epochs']), [
    ':examples/standard-epochs',
  ]);
});

it('several explicit build-ids pass through in order', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/standard-epochs', ':examples/routes-epochs']),
    [':examples/standard-epochs', ':examples/routes-epochs'],
  );
});

it('extra shadow-cljs flags pass straight through', () => {
  assert.deepStrictEqual(resolveArgs([':testbeds/panel-gallery', '--verbose']), [
    ':testbeds/panel-gallery',
    '--verbose',
  ]);
});

it('a removed group name is NOT expanded — it passes through as a literal', () => {
  // Post-removal: `xray` is just a token. It reaches shadow-cljs as an
  // unknown build-id (which shadow-cljs reports), never a 6-build expansion.
  assert.deepStrictEqual(resolveArgs(['xray']), ['xray']);
  assert.deepStrictEqual(resolveArgs(['stories']), ['stories']);
  assert.deepStrictEqual(resolveArgs(['all']), ['all']);
  assert.deepStrictEqual(resolveArgs(['epochs']), ['epochs']);
});

it('duplicate explicit build-ids are deduped, order preserved', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/standard-epochs', ':examples/standard-epochs']),
    [':examples/standard-epochs'],
  );
  assert.deepStrictEqual(
    resolveArgs([
      ':examples/login-form',
      ':examples/standard-epochs',
      ':examples/login-form',
    ]),
    [':examples/login-form', ':examples/standard-epochs'],
  );
});

it('non-build flags are NOT deduped (passed verbatim)', () => {
  assert.deepStrictEqual(
    resolveArgs([':testbeds/panel-gallery', '--verbose', '--verbose']),
    [':testbeds/panel-gallery', '--verbose', '--verbose'],
  );
});

it('urlsForBuild prints the live URL for a plain build', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/standard-epochs'), [
    'http://localhost:8031/',
  ]);
  assert.deepStrictEqual(urlsForBuild(':testbeds/panel-gallery'), [
    'http://localhost:8765/',
  ]);
});

it('urlsForBuild prints the live + /#/stories URLs for a Story build', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/login-form'), [
    'http://localhost:8043/',
    'http://localhost:8043/#/stories',
  ]);
  assert.deepStrictEqual(urlsForBuild(':examples/nine-states-with-stories'), [
    'http://localhost:8040/',
    'http://localhost:8040/#/stories',
  ]);
});

it('urlsForBuild returns [] for a build with no dev-http port', () => {
  assert.deepStrictEqual(urlsForBuild(':examples/counter'), []);
  assert.deepStrictEqual(urlsForBuild('--verbose'), []);
});

it('every DEV_HTTP build-id is a colon-prefixed build coord', () => {
  for (const build of Object.keys(DEV_HTTP)) {
    assert.ok(build.startsWith(':'), `${build} should be a :build coord`);
  }
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll dev-testbed tests passed.');
}
