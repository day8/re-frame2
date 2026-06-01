#!/usr/bin/env node
/*
 * Tests for `dev-testbed.cjs` group-alias resolution + URL printing
 * (rf2-jooy3).
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `_path-policy.test.cjs`. Each test logs PASS / FAIL; the process exits
 * 0 only when every test passes. Wired into `package.json` via
 * `test:script-helpers`.
 *
 * Requiring `dev-testbed.cjs` does NOT spawn shadow-cljs: the CLI body is
 * guarded by `require.main === module`, so importing it here only loads
 * the pure `resolveArgs` / `urlsForBuild` helpers + the GROUPS / DEV_HTTP
 * maps.
 */

'use strict';

const assert = require('assert');
const { GROUPS, DEV_HTTP, resolveArgs, urlsForBuild } = require('./dev-testbed.cjs');

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

console.log('dev-testbed group-alias tests (rf2-jooy3)');

it('a group name expands to its build-ids', () => {
  assert.deepStrictEqual(resolveArgs(['xray']), [
    ':examples/standard-epochs',
    ':examples/routes-epochs',
    ':examples/machine-epochs',
    ':examples/edn-inspector',
    ':examples/two-frame-isolation',
    ':testbeds/panel-gallery',
  ]);
  assert.deepStrictEqual(resolveArgs(['stories']), [
    ':examples/nine-states-with-stories',
    ':examples/login-with-stories',
    ':examples/counter-with-stories',
    ':examples/login-form',
  ]);
});

it('the epochs group is the standard/routes/machine trio only', () => {
  assert.deepStrictEqual(resolveArgs(['epochs']), [
    ':examples/standard-epochs',
    ':examples/routes-epochs',
    ':examples/machine-epochs',
  ]);
});

it('the all group is xray + stories, deduped', () => {
  const expected = [...GROUPS.xray, ...GROUPS.stories];
  assert.deepStrictEqual(resolveArgs(['all']), expected);
  // No duplicates survive.
  assert.strictEqual(new Set(resolveArgs(['all'])).size, resolveArgs(['all']).length);
});

it('an unknown arg passes through unchanged (back-compat)', () => {
  assert.deepStrictEqual(resolveArgs([':examples/standard-epochs']), [
    ':examples/standard-epochs',
  ]);
  // Extra shadow-cljs flags pass straight through.
  assert.deepStrictEqual(resolveArgs([':testbeds/panel-gallery', '--verbose']), [
    ':testbeds/panel-gallery',
    '--verbose',
  ]);
});

it('mixing a group with an explicit build-id works and dedups', () => {
  // login-form is already in the stories group; the explicit repeat is
  // dropped, first-seen order preserved.
  assert.deepStrictEqual(resolveArgs(['stories', ':examples/login-form']), [
    ':examples/nine-states-with-stories',
    ':examples/login-with-stories',
    ':examples/counter-with-stories',
    ':examples/login-form',
  ]);
  // A NOT-yet-present build-id appended after a group is kept, in order.
  assert.deepStrictEqual(resolveArgs(['stories', ':examples/standard-epochs']), [
    ':examples/nine-states-with-stories',
    ':examples/login-with-stories',
    ':examples/counter-with-stories',
    ':examples/login-form',
    ':examples/standard-epochs',
  ]);
});

it('preserves first-seen order across two groups (all dedups overlap)', () => {
  assert.deepStrictEqual(resolveArgs(['xray', 'stories']), resolveArgs(['all']));
  // Stories-first then xray keeps stories ahead of xray.
  assert.deepStrictEqual(resolveArgs(['stories', 'xray']), [
    ...GROUPS.stories,
    ...GROUPS.xray,
  ]);
});

it('duplicate explicit build-ids are deduped, order preserved', () => {
  assert.deepStrictEqual(
    resolveArgs([':examples/standard-epochs', ':examples/standard-epochs']),
    [':examples/standard-epochs'],
  );
});

it('non-build flags are NOT deduped (passed verbatim)', () => {
  assert.deepStrictEqual(resolveArgs(['xray', '--verbose', '--verbose']), [
    ...GROUPS.xray,
    '--verbose',
    '--verbose',
  ]);
});

it('every group build-id has a dev-http port entry', () => {
  const all = new Set(Object.values(GROUPS).flat());
  for (const build of all) {
    assert.ok(
      Object.prototype.hasOwnProperty.call(DEV_HTTP, build),
      `${build} should have a DEV_HTTP entry`,
    );
  }
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

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll dev-testbed tests passed.');
}
