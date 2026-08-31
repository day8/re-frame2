#!/usr/bin/env node

'use strict';

/*
 * rf2-4a6ei — the `:node-test`-family compile gate reads shadow-cljs's own
 * tally, so the reader is what has to be held.
 *
 * WHY THE READER AND NOT THE GATE. The gate itself is three lines — a number
 * compared against zero — and it is exercised end to end by every lane on
 * every run. The part that can go wrong quietly is the extraction: a tally line
 * that stops matching reads as `null`, and a `null` that fell through to a pass
 * would restore exactly the fail-open the bead is about. It does not fall
 * through (an unreadable tally REFUSES), and that direction is asserted below
 * too, because "unknown" and "clean" must never converge.
 *
 * THE FIXTURES ARE REAL OUTPUT, not invented shapes. The clean and warning
 * lines are copied from `npm run test:security` runs on this tree — the same
 * pair of runs the header of compile-node-test.cjs cites, one over a clean
 * checkout and one with a bare double-quote planted in a deftest docstring.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { buildTally } = require('./compile-node-test.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// Verbatim from the clean run: 216 files, 0 warnings, exit 0.
const CLEAN =
  'shadow-cljs - config: implementation/shadow-cljs.edn\n' +
  '[:node-test-security] Compiling ...\n' +
  '[:node-test-security] Build completed. (216 files, 215 compiled, 0 warnings, 24.73s)\n';

// Verbatim from the planted run. Everything a suite reports is unchanged —
// `Ran 93 tests containing 705 assertions. / 0 failures, 0 errors.` — and the
// warning count is the only number that moved.
const PLANTED =
  '[:node-test-security] Compiling ...\n' +
  'Use of undeclared Var re-frame.security.ssr-escaping-security-cljs-test/app\n' +
  '[:node-test-security] Build completed. (216 files, 2 compiled, 4 warnings, 17.90s)\n';

test('a clean tally reads zero warnings', () => {
  assert.deepEqual(buildTally(CLEAN), { files: 216, compiled: 215, warnings: 0 });
});

test('the planted tally reads the warnings that the test counts did not show', () => {
  assert.deepEqual(buildTally(PLANTED), { files: 216, compiled: 2, warnings: 4 });
});

test('ANSI colouring does not hide the tally', () => {
  const coloured =
    '[32m[:node-test][0m Build completed. (2395 files, 2394 compiled, ' +
    '[33m3[0m warnings, 159.35s)\n';
  assert.deepEqual(buildTally(coloured), { files: 2395, compiled: 2394, warnings: 3 });
});

test('the LAST tally wins, so a dependency build cannot answer for this one', () => {
  const two =
    '[:some-dep] Build completed. (10 files, 10 compiled, 0 warnings, 1.00s)\n' +
    '[:node-test] Build completed. (2395 files, 2394 compiled, 7 warnings, 159.35s)\n';
  assert.equal(buildTally(two).warnings, 7);
});

test('a singular tally still reads', () => {
  const one = 'Build completed. (1 file, 1 compiled, 1 warning, 0.10s)\n';
  assert.deepEqual(buildTally(one), { files: 1, compiled: 1, warnings: 1 });
});

// THE DIRECTION THAT MATTERS. An output with no tally must read as UNKNOWN and
// not as clean, because the caller turns `null` into a refusal. Were this to
// return a zero-warning object instead, a shadow-cljs release that reworded the
// line would silently disarm the gate and nothing would say so — which is the
// defect this whole change is about, one level up.
test('an output with no tally reads as unknown, never as clean', () => {
  assert.equal(buildTally(''), null);
  assert.equal(buildTally('[:node-test] Compiling ...\nBuild completed.\n'), null);
  assert.equal(
    buildTally('Build completed. (216 files, 215 compiled, some warnings, 24.73s)'),
    null,
  );
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
