#!/usr/bin/env node

'use strict';

/*
 * Policy gate for the VERDICT wiring of the implementation-side browser
 * runners — the places a runner can ship green while a fatal signal is
 * masked, or fail while naming the wrong cause. These classes are pinned
 * here:
 *
 *   1. an uncaught Chromium `pageerror` the suite happened not to assert on
 *      (rf2-mwx08);
 *   2. a lane that ran ZERO tests, whose `Ran 0 tests containing 0
 *      assertions. / 0 failures, 0 errors.` summary satisfies a
 *      failure-tally-only verdict (rf2-qqzmf);
 *   3. a cljs.test async row that called `done` twice, which `run-block`
 *      degrades to a `println` on the already-realized continuation and so
 *      can never reach the failure tally (rf2-u0cy4);
 *   4. a navigation that inherited Playwright's default 30s `load` ceiling —
 *      a SECOND budget BROWSER_TEST_TIMEOUT_MS cannot reach, whose CI log
 *      line reads like the summary timeout it is not (rf2-dczpv), and which
 *      turned out to be a CLASS rather than one site: run-ui-g8.cjs had the
 *      same defect one file over (rf2-bhjzn). Pinned as a SWEEP over every
 *      runner in this directory, so the next `page.goto` cannot reintroduce
 *      it.
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

test('run-browser-tests: a double-fired cljs.test `done` is fatal, not diagnostic noise (rf2-u0cy4)', () => {
  const src = read('run-browser-tests.cjs');
  // The literal cljs.test emits from run-block's realized? branch. If this
  // string drifts in a ClojureScript upgrade the guard silently stops
  // matching, so pin the literal itself as well as the wiring.
  assert.match(
    src,
    /Async test called done more than one time/,
    'must match the cljs.test duplicate-done warning literal',
  );
  assert.match(
    src,
    /const\s+fatalConsole\s*=\s*\[\]/,
    'must track fatal console lines in a dedicated array, not merely in diagnostics',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]console['"][\s\S]{0,320}?fatalConsole\.push\(/,
    'the console handler must push matching lines into the dedicated array',
  );
  assert.match(
    src,
    /if\s*\(\s*fatalConsole\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,900}?return\s+1\s*;/,
    'must fail the run (return 1) when a duplicate `done` was captured, even on a green summary',
  );
  // Like the pageerror arm, the check is worthless after the green return.
  const fatalIdx = src.search(/if\s*\(\s*fatalConsole\.length\s*>\s*0\s*\)/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(fatalIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    fatalIdx < greenIdx,
    'the duplicate-done fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: diagnostics are stamped at capture time, not flush time (rf2-76lhy)', () => {
  const src = read('run-browser-tests.cjs');
  // The buffer flushes in one burst, so a timeout trail without capture-time
  // offsets records WHICH namespaces a run reached and nothing about how long
  // any of them took. This is dormant in a healthy tree — only a timeout ever
  // flushes it — so it needs a static pin exactly like the test-count floor.
  assert.match(
    src,
    /process\.hrtime\.bigint\(\)/,
    'must take a monotonic clock reading, not Date.now()',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]console['"][\s\S]{0,320}?diagnostics\.add\(\s*`\$\{capturedAt\(\)\}/,
    'the console handler must stamp each captured line as it arrives',
  );
});

test('run-browser-tests: navigation has its own explicit, named ceiling (rf2-dczpv)', () => {
  const src = read('run-browser-tests.cjs');
  // A bare `page.goto(URL, { waitUntil: 'load' })` takes Playwright's default
  // 30s — a SECOND budget on this lane that BROWSER_TEST_TIMEOUT_MS cannot
  // reach, and one whose failure line reads in CI like the summary timeout it
  // is not. Dormant in a healthy tree (navigation normally commits in
  // milliseconds), so it needs a static pin like the test-count floor.
  assert.match(
    src,
    /page\.goto\(\s*URL\s*,\s*\{\s*waitUntil:[^}]*\btimeout:/,
    'page.goto must pass an EXPLICIT timeout, never inherit Playwright\'s 30s default',
  );
  assert.doesNotMatch(
    src,
    /NAV_WAIT_UNTIL\s*=\s*['"]load['"]/,
    "waitUntil 'load' cannot fire until the suite yields — the poll loop owns that wait",
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,600}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must name itself as the navigation ceiling, not the summary timeout',
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

// ---- the navigation-ceiling sweep (rf2-dczpv → rf2-bhjzn) ----
//
// rf2-dczpv fixed one `page.goto` that inherited Playwright's 30s default;
// rf2-bhjzn found the identical defect in run-ui-g8.cjs, which passed no
// options at all. Two instances is a class, so this is pinned by SWEEP rather
// than by naming files: every `page.goto` in this directory must carry its own
// explicit `timeout:`, tied to the lane's own budget.
//
// Why it needs a static pin: the ceiling is DORMANT in a healthy tree —
// navigation normally completes in milliseconds — so ordinary CI would never
// notice a new bare `goto` until a loaded runner turned it into a flake whose
// log line names the wrong budget.

test('every runner navigation carries an EXPLICIT timeout, never Playwright\'s 30s default (rf2-dczpv, rf2-bhjzn)', () => {
  const runners = fs
    .readdirSync(SCRIPTS_DIR)
    .filter((f) => f.endsWith('.cjs') && !f.startsWith('_'));
  assert.ok(runners.length > 0, 'the sweep must actually find runners to check');

  // Whole-line comments only. A `//` mid-line would truncate the very
  // `http://…` URLs these calls navigate to, taking the `timeout:` with it.
  const codeOnly = (src) => src
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n');

  const offenders = [];
  let navigations = 0;
  for (const file of runners) {
    const src = codeOnly(read(file));
    for (let i = src.indexOf('.goto('); i !== -1; i = src.indexOf('.goto(', i + 1)) {
      navigations += 1;
      // The call's arguments, bounded — long enough to span a multi-line
      // options object, short enough not to reach an unrelated `timeout:`.
      const call = src.slice(i, i + 300);
      if (!/\btimeout:/.test(call)) {
        offenders.push(`${file}: ${call.split('\n')[0].trim()}`);
      }
    }
  }

  assert.ok(navigations > 0, 'the sweep must actually find navigations to check');
  assert.deepEqual(
    offenders,
    [],
    'page.goto must pass an EXPLICIT timeout tied to the lane\'s own budget. ' +
      'Without one Playwright applies a 30s default that no lane budget can ' +
      'reach, and whose CI failure line reads like the lane timeout it is not',
  );
});

// ---- run-ui-g8.cjs (rf2-bhjzn) ----

test('run-ui-g8: navigation has its own named ceiling, and does not wait on `load` (rf2-bhjzn)', () => {
  const src = read('run-ui-g8.cjs');
  // `load` cannot fire until the un-optimized :ui-g8 bundle has arrived AND
  // run its synchronous portion — which is where the fixture's warm-up and
  // sample collection live. Waiting for it is waiting for the fixture, which
  // is the waitForFunction poll's job against TIMEOUT.
  assert.match(
    src,
    /NAV_WAIT_UNTIL\s*=\s*'commit'/,
    "the G-8 navigation must commit, not wait on `load` — `load` waits for the fixture",
  );
  assert.match(
    src,
    /NAV_TIMEOUT\s*=\s*TIMEOUT\b/,
    'the navigation budget must be the gate\'s own TIMEOUT, so the lane has ONE number',
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,400}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must name itself as the navigation ceiling, not the fixture-result budget',
  );
});

// ---- check-story-static.cjs navigation ceiling (rf2-bhjzn) ----

test('check-story-static: the navigation budget is named, not a bare literal (rf2-bhjzn)', () => {
  const src = read('check-story-static.cjs');
  // This smoke always passed an explicit timeout, so it never INHERITED the
  // default — but the literal 30000 sat beside a READY_TIMEOUT_MS of 30000,
  // two different budgets printing one number.
  assert.match(
    src,
    /const\s+NAV_TIMEOUT_MS\s*=/,
    'the navigation budget must be a named constant, distinct from READY_TIMEOUT_MS',
  );
  assert.doesNotMatch(
    src,
    /page\.goto\([^)]*timeout:\s*\d/,
    'the navigation timeout must not be an anonymous numeric literal',
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,300}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must say which of the two 30000ms budgets fired',
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
