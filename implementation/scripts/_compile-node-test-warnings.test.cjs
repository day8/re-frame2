#!/usr/bin/env node

'use strict';

/*
 * Holds the reader of shadow-cljs's own build tally, which the `:node-test`-family
 * compile gate compares against zero. The fixtures are real shadow-cljs output.
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { buildTally } = require('./compile-node-test.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

const CLEAN =
  'shadow-cljs - config: implementation/shadow-cljs.edn\n' +
  '[:node-test-security] Compiling ...\n' +
  '[:node-test-security] Build completed. (216 files, 215 compiled, 0 warnings, 24.73s)\n';

// A planted warning that left every suite count unchanged.
const PLANTED =
  '[:node-test-security] Compiling ...\n' +
  'Use of undeclared Var re-frame.security.ssr-escaping-security-cljs-test/app\n' +
  '[:node-test-security] Build completed. (216 files, 2 compiled, 4 warnings, 17.90s)\n';

// No tally reads as null (unknown), never as clean: the caller refuses on null,
// so a reworded shadow-cljs line cannot silently disarm the gate.
test('buildTally reads the last tally in the output, or null when there is none', () => {
  const coloured =
    '[32m[:node-test][0m Build completed. (2395 files, 2394 compiled, ' +
    '[33m3[0m warnings, 159.35s)\n';
  const two =
    '[:some-dep] Build completed. (10 files, 10 compiled, 0 warnings, 1.00s)\n' +
    '[:node-test] Build completed. (2395 files, 2394 compiled, 7 warnings, 159.35s)\n';
  for (const [text, expected] of [
    [CLEAN, { files: 216, compiled: 215, warnings: 0 }],
    [PLANTED, { files: 216, compiled: 2, warnings: 4 }],
    [coloured, { files: 2395, compiled: 2394, warnings: 3 }],
    [two, { files: 2395, compiled: 2394, warnings: 7 }],
    ['Build completed. (1 file, 1 compiled, 1 warning, 0.10s)\n', { files: 1, compiled: 1, warnings: 1 }],
    ['', null],
    ['Build completed. (216 files, 215 compiled, some warnings, 24.73s)', null],
  ]) {
    assert.deepEqual(buildTally(text), expected, JSON.stringify(text));
  }
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err.message);
  }
}
if (failed) {
  console.error(`compile-node-test-warnings tests: ${failed} failed.`);
  process.exit(1);
}
console.log(`compile-node-test-warnings tests: ${tests.length} passed.`);
