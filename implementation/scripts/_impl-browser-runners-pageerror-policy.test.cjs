#!/usr/bin/env node

'use strict';

/*
 * Policy gate for the implementation-side browser runners that could
 * previously ship green while Chromium observed an uncaught `pageerror`
 * (rf2-mwx08). Same correctness class rf2-wf5al fixed for the
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
 * These runners drive a headless Chromium end-to-end, so their verdict
 * paths are not cleanly unit-testable without launching a browser.
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
  console.error(`impl-browser-runners-pageerror-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`impl-browser-runners-pageerror-policy tests: ${tests.length} passed.`);
