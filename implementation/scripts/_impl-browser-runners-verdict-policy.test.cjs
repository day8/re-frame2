#!/usr/bin/env node

'use strict';

/*
 * Policy gate for the VERDICT wiring of the implementation-side browser
 * runners — the places a runner can ship green while a fatal signal is
 * masked. Two such classes are pinned here:
 *
 *   1. an uncaught Chromium `pageerror` the suite happened not to assert on
 *      (rf2-mwx08);
 *   2. a lane that ran ZERO tests, whose `Ran 0 tests containing 0
 *      assertions. / 0 failures, 0 errors.` summary satisfies a
 *      failure-tally-only verdict (rf2-qqzmf).
 *
 * Both are pinned statically for the same reason: these runners drive a
 * headless Chromium end-to-end, so their verdict paths are not cleanly
 * unit-testable without launching a browser. The test-count floor also
 * needs a static pin because it is DORMANT in a healthy tree — every lane
 * runs well above its floor, so ordinary CI would never notice the check
 * being refactored away.
 *
 * The pageerror class (1) was the rf2-wf5al correctness class fixed for the
 * examples/scripts Story play runner, here applied to:
 *
 *   - run-browser-tests.cjs           (a green cljs.test summary ignored
 *                                      pageerrors)
 *   - serve-and-run-xray-feature-gate.cjs
 *                                     (scenario marked passed even when a
 *                                      pageerror was captured)
 *   - check-story-static.cjs          (static smoke passed on visible
 *                                      assertions, ignoring pageerrors)
 *   - serve-and-run-tenant-switcher-testbed.cjs (rf2-h5e3v7 — joined the
 *                                      pinned set when CI-wired)
 *
 * Following the rf2-wf5al precedent (_story-script-runners-policy.test.cjs),
 * we pin the verdict wiring STATICALLY: each runner must (a) record
 * pageerrors into a separately-tracked array — NOT merely into the
 * diagnostic buffer — and (b) flip its verdict to fail when that array is
 * non-empty. A refactor that drops the pageerror signal back to an
 * assertions-only / summary-only verdict trips this gate.
 *
 * Console noise stays diagnostic-only by design; only `pageerror` is
 * fatal — matching every adjacent runner's policy.
 *
 * Wired into package.json via `test:script-policy`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const SCRIPTS_DIR = path.resolve(__dirname);

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function read(name) {
  return fs.readFileSync(path.join(SCRIPTS_DIR, name), 'utf8');
}

// ---- run-browser-tests.cjs ----

test('run-browser-tests: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-mwx08)', () => {
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('run-browser-tests: a green cljs.test summary still fails when pageErrors were captured (rf2-mwx08)', () => {
  const src = read('run-browser-tests.cjs');
  // After the failures/errors-count guard, there must be a pageErrors
  // length check that returns the failure exit code (1) before the
  // green/compact-summary return.
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,400}?return\s+1\s*;/,
    'must fail the run (return 1) when pageErrors.length > 0, even on a green summary',
  );
  // And the fatal check must sit BEFORE the green compact-summary print.
  const fatalIdx = src.search(/if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(fatalIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    fatalIdx < greenIdx,
    'the pageerror fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: the executed-test count is part of the verdict, not just the failure tally (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  // The runner has always PARSED `Ran N tests containing M assertions.`
  // (RAN_RE captures both integers) and never read N. Pin that it now does,
  // through the shared library rather than a second local regex.
  assert.match(
    src,
    /parseRanCounts/,
    'must read the `Ran N` count via the shared browser-test-report helper',
  );
  assert.match(
    src,
    /ranCounts\.tests\s*<\s*minTests[\s\S]{0,700}?return\s+1\s*;/,
    'must fail the run (return 1) when the executed-test count is below the floor',
  );
  // And the floor check must sit BEFORE the green compact-summary return —
  // otherwise a zero-test lane still prints a green verdict and exits 0.
  const floorIdx = src.search(/ranCounts\.tests\s*<\s*minTests/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(floorIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    floorIdx < greenIdx,
    'the test-count floor must precede the green compact-summary return',
  );
});

test('run-browser-tests: a malformed floor is refused, never a silent default (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  // A floor that silently falls back to the default on `RF2_MIN_TESTS=1O`
  // would disable the gate that catches silent non-execution.
  assert.match(
    src,
    /Number\.isInteger\(\s*n\s*\)/,
    'must validate the floor as an integer',
  );
  assert.match(
    src,
    /if\s*\(\s*minTests\s*===\s*null\s*\)\s*return\s+2\s*;/,
    'a malformed floor must exit 2 (configuration error), distinct from 1 (red)',
  );
});

// ---- serve-and-run-xray-feature-gate.cjs ----

test('xray-feature-gate: a scenario with a captured pageerror is not marked passed (rf2-mwx08)', () => {
  const src = read('serve-and-run-xray-feature-gate.cjs');
  // The handler already records into browserState.pageErrors; pin that
  // the run path THROWS (→ passed stays false / anyFailed flips) when
  // that array is non-empty, BEFORE `passed = true`.
  assert.match(
    src,
    /if\s*\(\s*browserState\.pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when browserState.pageErrors is non-empty so the scenario fails',
  );
  const throwIdx = src.search(/browserState\.pageErrors\.length\s*>\s*0/);
  const passedIdx = src.search(/\bpassed\s*=\s*true\s*;/);
  assert.ok(throwIdx > -1 && passedIdx > -1, 'both call-sites must exist');
  assert.ok(
    throwIdx < passedIdx,
    'the pageerror fatal check must precede `passed = true`',
  );
});

// ---- check-story-static.cjs ----

test('check-story-static: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-mwx08)', () => {
  const src = read('check-story-static.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('check-story-static: the smoke throws on a captured pageerror even when assertions passed (rf2-mwx08)', () => {
  const src = read('check-story-static.cjs');
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the smoke fails (exit 1)',
  );
});

// ---- serve-and-run-tenant-switcher-testbed.cjs (rf2-h5e3v7) ----
// The tenant-switcher testbed smoke is now CI-wired
// (tenant-switcher-testbed-smoke job), so its verdict path joins the
// pinned set: pageerror handling is specific
// to this runner as a maskable failure. Same discipline as the siblings —
// pageerrors go into a dedicated array and flip the verdict to fail before
// `passed = true`.

test('tenant-switcher-testbed: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-h5e3v7)', () => {
  const src = read('serve-and-run-tenant-switcher-testbed.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('tenant-switcher-testbed: a captured pageerror fails the spec even when assertions passed (rf2-h5e3v7)', () => {
  const src = read('serve-and-run-tenant-switcher-testbed.cjs');
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the spec fails',
  );
  const throwIdx = src.search(/pageErrors\.length\s*>\s*0/);
  const passedIdx = src.search(/\bpassed\s*=\s*true\s*;/);
  assert.ok(throwIdx > -1 && passedIdx > -1, 'both call-sites must exist');
  assert.ok(
    throwIdx < passedIdx,
    'the pageerror fatal check must precede `passed = true`',
  );
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
  console.error(`impl-browser-runners-verdict-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`impl-browser-runners-verdict-policy tests: ${tests.length} passed.`);
