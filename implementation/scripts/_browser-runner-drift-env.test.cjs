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

test('no flag, AMBIENT value present: the var is STRIPPED, not forwarded (the bug this closes)', () => {
  const baseEnv = { PATH: '/usr/bin', [DRIFT_UNVERIFIABLE_ENV_VAR]: '1' };
  const env = computeRunnerEnv(baseEnv, {
    driftUnverifiable: false,
    browserTestUrl: URL,
  });
  assert.ok(
    !Object.prototype.hasOwnProperty.call(env, DRIFT_UNVERIFIABLE_ENV_VAR),
    'an ambient value must be stripped when the orchestrator was not passed the flag — ' +
      'a naive `{ ...baseEnv, ...(cond ? {K: v} : {}) }` construction leaves it in place, ' +
      'which is exactly the leak this pins',
  );
});

test('flag present, ambient value is something OTHER than "1": still normalised to "1"', () => {
  const env = computeRunnerEnv(
    { PATH: '/usr/bin', [DRIFT_UNVERIFIABLE_ENV_VAR]: 'true' },
    { driftUnverifiable: true, browserTestUrl: URL },
  );
  assert.equal(env[DRIFT_UNVERIFIABLE_ENV_VAR], '1');
});

test('every other env var passes through unchanged, and BROWSER_TEST_URL is set', () => {
  const baseEnv = { PATH: '/usr/bin', HOME: '/home/x', RANDOM_VAR: 'y' };
  const env = computeRunnerEnv(baseEnv, { driftUnverifiable: false, browserTestUrl: URL });
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
