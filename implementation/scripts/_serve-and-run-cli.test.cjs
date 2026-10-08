#!/usr/bin/env node
'use strict';

/*
 * The strict CLI option contract of serve-and-run-browser-tests.cjs, which the
 * production browser gates call directly: a bad option fails fast at option parse
 * or path policy, before any server starts. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const RUNNER = path.join(__dirname, 'serve-and-run-browser-tests.cjs');
const IMPL_ROOT = path.resolve(__dirname, '..');

// A clean env WITHOUT the out-of-tree opt-in, so the path policy is live.
const CLEAN_ENV = { ...process.env };
delete CLEAN_ENV.RE_FRAME_ALLOW_OUT_OF_TREE_PATHS;
// Also strip the env-var interface so a stray value can't mask a CLI test.
delete CLEAN_ENV.BROWSER_TEST_ROOT;
delete CLEAN_ENV.BROWSER_TEST_PORT;

function runWith(args) {
  const res = spawnSync(process.execPath, [RUNNER, ...args], {
    cwd: IMPL_ROOT,
    env: CLEAN_ENV,
    encoding: 'utf8',
    timeout: 20000,
  });
  return {
    status: res.status,
    signal: res.signal,
    out: `${res.stdout || ''}${res.stderr || ''}`,
  };
}

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test('bad CLI options fail fast with a clear message, and --root cannot bypass the path policy (rf2-hmgwk2)', () => {
  const outOfTree = path.join(os.tmpdir(), `rf2-hmgwk2-out-of-tree-${process.pid}`);
  for (const [args, message] of [
    [['--frobnicate'], /Unknown option/],
    [['--port', 'abc'], /--port must be an integer in 1\.\.65535/],
    [['--port', '0'], /--port must be an integer in 1\.\.65535/],
    [['--port', '65536'], /--port must be an integer in 1\.\.65535/],
    [['--root'], /--root requires a value/],
    [['--root', outOfTree], /outside the approved roots/],
  ]) {
    const { status, out } = runWith(args);
    assert.notEqual(status, 0, `${args.join(' ')}: expected non-zero exit; got ${status}`);
    assert.match(out, message, `${args.join(' ')}: ${out}`);
  }
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
    console.log(`  PASS  ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`  FAIL  ${name}`);
    console.error(`        ${err && err.message ? err.message : err}`);
  }
}

if (failed > 0) {
  console.error(`serve-and-run-cli tests: ${failed} failed.`);
  process.exit(1);
}
console.log(`serve-and-run-cli tests: ${tests.length} passed.`);
