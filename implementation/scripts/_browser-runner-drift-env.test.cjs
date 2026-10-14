#!/usr/bin/env node
'use strict';

/*
 * serve-and-run-browser-tests.cjs forwards RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE
 * to its runner child only when its own --duplicate-done-drift-unverifiable flag
 * is present: an ambient value a parent shell exported must be stripped, or the
 * default lane takes the waiver branch and skips the fail-closed drift verdict.
 */

const assert = require('assert/strict');
const {
  computeRunnerEnv,
  DRIFT_UNVERIFIABLE_ENV_VAR,
} = require('./lib/browser-runner-drift-env.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

const URL = 'http://127.0.0.1:8021';

// A naive `{ ...baseEnv, ...(cond ? {K: v} : {}) }` leaves the ambient value in
// place; the strict equality rejects a leftover key, even one set to undefined.
test('no flag, AMBIENT value present: the var is STRIPPED, not forwarded (the bug this closes)', () => {
  const baseEnv = { PATH: '/usr/bin', HOME: '/home/x', RANDOM_VAR: 'y' };
  const env = computeRunnerEnv(
    { ...baseEnv, [DRIFT_UNVERIFIABLE_ENV_VAR]: '1' },
    { driftUnverifiable: false, browserTestUrl: URL },
  );
  assert.deepEqual(env, { ...baseEnv, BROWSER_TEST_URL: URL });
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`browser-runner-drift-env tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`browser-runner-drift-env tests: ${tests.length} passed.`);
