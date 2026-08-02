#!/usr/bin/env node
'use strict';

/*
 * rf2-u0cy4 (audit of merged PR #7343). serve-and-run-browser-tests.cjs
 * forwards RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE to its runner child ONLY
 * when the orchestrator's own `--duplicate-done-drift-unverifiable` CLI flag
 * is present — the declaration must come from THIS process's command line,
 * never from an ambient environment variable a parent shell happened to
 * export.
 *
 * `{ ...baseEnv, ...(cond ? {K: v} : {}) }` (the pre-fix shape) only ever
 * ADDS the key — it never REMOVES one `baseEnv` already carried. An ambient
 * RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE=1 therefore rode straight through to
 * the unflagged default `test:browser` lane's runner child, which took the
 * waiver branch in run-browser-tests.cjs and skipped the fail-closed drift
 * verdict entirely.
 *
 * This is a DYNAMIC (not merely static-source) test, unlike most of
 * `_impl-browser-runners-verdict-policy.test.cjs`'s sibling assertions,
 * because a source-regex check that this file still contains a `delete`
 * call cannot tell whether that delete actually fires on the code path that
 * matters — exactly the class of gap a prior audit found in the matcher-
 * drift check itself. computeRunnerEnv is a pure function extracted to its
 * own module (scripts/lib/browser-runner-drift-env.cjs) for exactly this:
 * both halves are pinned by actually calling it, not by reading its source.
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

test('no flag, no ambient value: the var is absent from the runner env', () => {
  const env = computeRunnerEnv(
    { PATH: '/usr/bin' },
    { driftUnverifiable: false, browserTestUrl: URL },
  );
  assert.ok(
    !Object.prototype.hasOwnProperty.call(env, DRIFT_UNVERIFIABLE_ENV_VAR),
    'the var must not be present when neither the flag nor an ambient value set it',
  );
  assert.equal(env.BROWSER_TEST_URL, URL);
});

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
      'which is exactly the PR #7343 regression',
  );
  // baseEnv itself must not be mutated — computeRunnerEnv returns a new object.
  assert.equal(baseEnv[DRIFT_UNVERIFIABLE_ENV_VAR], '1', 'the input object must be left untouched');
});

test('flag present, no ambient value: the var is forwarded as "1"', () => {
  const env = computeRunnerEnv(
    { PATH: '/usr/bin' },
    { driftUnverifiable: true, browserTestUrl: URL },
  );
  assert.equal(env[DRIFT_UNVERIFIABLE_ENV_VAR], '1');
});

test('flag present AND ambient value present: still forwarded as "1" (idempotent)', () => {
  const env = computeRunnerEnv(
    { PATH: '/usr/bin', [DRIFT_UNVERIFIABLE_ENV_VAR]: '1' },
    { driftUnverifiable: true, browserTestUrl: URL },
  );
  assert.equal(env[DRIFT_UNVERIFIABLE_ENV_VAR], '1');
});

test('flag present, ambient value is something OTHER than "1": still normalised to "1"', () => {
  // Defends against an ambient value like "0" or "true" being passed through
  // verbatim instead of the canonical "1" run-browser-tests.cjs compares against.
  const env = computeRunnerEnv(
    { PATH: '/usr/bin', [DRIFT_UNVERIFIABLE_ENV_VAR]: 'true' },
    { driftUnverifiable: true, browserTestUrl: URL },
  );
  assert.equal(env[DRIFT_UNVERIFIABLE_ENV_VAR], '1');
});

test('every other env var passes through unchanged', () => {
  const baseEnv = { PATH: '/usr/bin', HOME: '/home/x', RANDOM_VAR: 'y' };
  const env = computeRunnerEnv(baseEnv, { driftUnverifiable: false, browserTestUrl: URL });
  assert.equal(env.PATH, '/usr/bin');
  assert.equal(env.HOME, '/home/x');
  assert.equal(env.RANDOM_VAR, 'y');
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
