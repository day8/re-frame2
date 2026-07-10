#!/usr/bin/env node
'use strict';

/*
 * CLI-option contract for serve-and-run-browser-tests.cjs (rf2-hmgwk2).
 *
 * The two production browser gates (test:browser-prod-elision,
 * test:browser-schemas-boundary-prod) used to route through mirrored
 * ~81-line wrapper launchers whose only job was to set BROWSER_TEST_ROOT /
 * BROWSER_TEST_PORT before spawning an inner serve-and-run-browser-tests.cjs
 * — an extra Node process per gate. rf2-hmgwk2 collapsed them: the shared
 * runner now takes strict `--root` / `--port` options and the gates call it
 * directly.
 *
 * This suite pins the STRICT half of that contract — the error paths the two
 * gates' happy-path browser runs don't exercise. Each spawns the real runner
 * (shell-free, under this node binary) with a bad option set and asserts it
 * fails FAST and CLEARLY, before any http-server is launched:
 *
 *   - an unknown flag is rejected,
 *   - a `--port` that is not a 1..65535 integer is rejected,
 *   - a flag missing its value is rejected,
 *   - a CLI `--root` cannot bypass the rf2-o38lb path policy (an out-of-tree
 *     root is refused, just as $BROWSER_TEST_ROOT would be).
 *
 * These all fail at module load (option parse / enforcePolicy), so the suite
 * is cheap: no build, no server, no browser. Wired into `test:script-policy`.
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

test('an unknown option fails fast with a clear message (rf2-hmgwk2)', () => {
  const { status, out } = runWith(['--frobnicate']);
  assert.notEqual(status, 0, `expected non-zero exit; got ${status}`);
  assert.match(out, /Unknown option/, `stderr should name the unknown option: ${out}`);
});

test('a non-integer --port fails fast with a clear message (rf2-hmgwk2)', () => {
  const { status, out } = runWith(['--port', 'abc']);
  assert.notEqual(status, 0, `expected non-zero exit; got ${status}`);
  assert.match(out, /--port must be an integer in 1\.\.65535/, out);
});

test('an out-of-range --port fails fast (rf2-hmgwk2)', () => {
  for (const bad of ['0', '65536', '-1']) {
    const { status, out } = runWith(['--port', bad]);
    assert.notEqual(status, 0, `expected non-zero exit for --port ${bad}; got ${status}`);
    assert.match(out, /--port must be an integer in 1\.\.65535/, `--port ${bad}: ${out}`);
  }
});

test('a --root with no value fails fast (rf2-hmgwk2)', () => {
  const { status, out } = runWith(['--root']);
  assert.notEqual(status, 0, `expected non-zero exit; got ${status}`);
  assert.match(out, /--root requires a value/, out);
});

test('a CLI --root cannot bypass the path policy (rf2-hmgwk2 / rf2-o38lb)', () => {
  // An out-of-tree root must be refused by enforcePolicy exactly as a
  // BROWSER_TEST_ROOT env override would be — the CLI is not an escape hatch.
  const outOfTree = path.join(os.tmpdir(), 'rf2-hmgwk2-out-of-tree');
  const { status, out } = runWith(['--root', outOfTree]);
  assert.notEqual(status, 0, `expected non-zero exit; got ${status}`);
  assert.match(
    out,
    /outside the approved roots/,
    `an out-of-tree --root must be refused by the path policy: ${out}`,
  );
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
