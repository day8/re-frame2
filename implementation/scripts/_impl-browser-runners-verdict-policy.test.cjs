#!/usr/bin/env node

'use strict';

/*
 * Policy gate for the VERDICT wiring of the implementation-side browser
 * runners — the places a runner can ship green while a fatal signal is masked:
 *
 *   1. an uncaught Chromium `pageerror` the suite happened not to assert on;
 *   2. a lane that ran ZERO tests, whose `Ran 0 tests` summary satisfies a
 *      failure-tally-only verdict;
 *   3. a cljs.test async row that called `done` twice, which `run-block`
 *      degrades to a `println` that never reaches the failure tally;
 *   4. a run the browser can no longer advance (a terminal abort, an
 *      unanswerable trusted-input request), waited out as a timeout.
 *
 * They are pinned STATICALLY because these runners drive a headless Chromium
 * end-to-end, so their verdict paths are not unit-testable without a browser,
 * and most are DORMANT in a healthy tree, where ordinary CI would never notice
 * the check being refactored away. Each runner records pageerrors into a
 * dedicated array and flips its verdict on it; console noise stays diagnostic.
 * The repository-wide `page.goto` ceiling sweep lives in
 * `_navigation-ceiling-policy.test.cjs`.
 *
 * Discovered by `npm run test:scripts`.
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

// `first` occurs, and before `then` — a fatal check after the green return is
// worthless.
function assertPrecedes(src, first, then, message) {
  const a = src.search(first);
  const b = src.search(then);
  assert.ok(a > -1 && b > -1 && a < b, message);
}

// ---- run-browser-tests.cjs ----

test('run-browser-tests: a captured pageerror fails the run even on a green cljs.test summary (rf2-mwx08)', () => {
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,400}?return\s+1\s*;/,
    'must fail the run (return 1) when pageErrors.length > 0, even on a green summary',
  );
  assertPrecedes(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)/,
    /formatCompactSummary\(/,
    'the pageerror fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: the executed-test count is part of the verdict, not just the failure tally (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /ranCounts\.tests\s*<\s*minTests[\s\S]{0,700}?return\s+1\s*;/,
    'must fail the run (return 1) when the executed-test count is below the floor',
  );
  assertPrecedes(
    src,
    /ranCounts\.tests\s*<\s*minTests/,
    /formatCompactSummary\(/,
    'the test-count floor must precede the green compact-summary return',
  );
});

test('run-browser-tests: a malformed floor is refused, never a silent default (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  // A floor that silently fell back to the default on `RF2_MIN_TESTS=1O` would
  // disable the gate that catches silent non-execution.
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
  assertPrecedes(
    src,
    /if\s*\(\s*fatalConsole\.length\s*>\s*0\s*\)/,
    /formatCompactSummary\(/,
    'the duplicate-done fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: the duplicate-`done` matcher is checked against the INSTALLED cljs.test (rf2-u0cy4 audit)', () => {
  // The literal belongs to ClojureScript, so a reworded upstream changes
  // neither this runner nor a test that reads it. The runner compares its
  // matcher against what the loaded ClojureScript actually emits, and an
  // unreachable `run_block` is REPORTED, since a drift check that passes when
  // it cannot look is the fail-open shape it exists to prevent.
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /FATAL_CONSOLE_RE\.test\(\s*source\s*\)/,
    'must test the matcher against the live run-block source, not against its own text',
  );
  assert.match(
    src,
    /if\s*\(\s*source\s*==\s*null\s*\)\s*\{[\s\S]{0,1400}?It cannot be skipped/,
    'an unreachable `run_block` must be REPORTED, never silently skipped',
  );
  assertPrecedes(
    src,
    /const\s+matcherDrift\s*=\s*await\s+duplicateDoneMatcherDrift\(\s*page\s*\)/,
    /const\s+counts\s*=\s*parseFailureCounts/,
    'the verdict path must run the drift check before the failure-tally verdict, so a red run cannot mask a guard that stopped guarding',
  );
});

test('run-browser-tests: an `:advanced` lane may only skip the drift check by DECLARING it (rf2-u0cy4)', () => {
  // Closure renames `cljs.test.run_block`, so on an `:advanced` bundle the
  // drift check is BLIND rather than failing. That is a per-lane declaration,
  // never an inference that would spread to a lane that merely broke, and only
  // the unreachable branch honours it: a stale declaration cannot disable a
  // working check.
  const src = read('run-browser-tests.cjs');
  const nullBranch = src.slice(src.search(/if\s*\(\s*source\s*==\s*null\s*\)/));
  const driftAt = nullBranch.search(/if\s*\(\s*!FATAL_CONSOLE_RE\.test/);
  assert.match(
    nullBranch.slice(0, driftAt),
    /DRIFT_UNVERIFIABLE_ENV_VAR\]\s*===\s*'1'/,
    'the declaration may only be honoured where the symbol is unreachable',
  );
  assert.doesNotMatch(
    nullBranch.slice(driftAt),
    /DRIFT_UNVERIFIABLE_ENV_VAR/,
    'a REAL drift (matcher present but not matching) must fail even on a lane that '
      + 'declares itself unverifiable — the declaration is about blindness, not about drift',
  );
});

test('the default `test:browser` lane CARRIES the drift check; only `:advanced` lanes waive it (rf2-u0cy4)', () => {
  // The waiver is only safe because at least one lane still verifies.
  const pkg = JSON.parse(
    fs.readFileSync(path.join(path.resolve(SCRIPTS_DIR, '..'), 'package.json'), 'utf8'),
  );
  const FLAG = '--duplicate-done-drift-unverifiable';
  assert.ok(
    !pkg.scripts['test:browser'].includes(FLAG),
    'the default `test:browser` lane must NEVER declare itself unverifiable — it is '
      + 'the lane that carries the duplicate-`done` drift verification',
  );
  for (const [lane, cmd] of Object.entries(pkg.scripts).filter(([, c]) => c.includes(FLAG))) {
    assert.match(
      cmd,
      /shadow-cljs release/,
      `${lane} waives the drift check, so it must actually be a release (\`:advanced\`) `
        + 'build — the waiver exists only because Closure renames `cljs.test.run_block`',
    );
  }
});

test('run-browser-tests: navigation does not wait for the load event (rf2-dczpv)', () => {
  const src = read('run-browser-tests.cjs');
  assert.doesNotMatch(
    src,
    /NAV_WAIT_UNTIL\s*=\s*['"]load['"]/,
    "waitUntil 'load' cannot fire until the suite yields — the poll loop owns that wait",
  );
});

test('run-browser-tests: an aborted run is named as an abort, not waited out as a timeout (rf2-u0j8)', () => {
  const src = read('run-browser-tests.cjs');
  // cljs.test refuses an `async` row under a POSITIONAL fixture by throwing out
  // of `test-var-block*`, and shadow.test runs the whole lane in ONE `run-block`,
  // so every remaining namespace and the summary are lost. Waiting for that
  // summary would burn the full BROWSER_TEST_TIMEOUT_MS.
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,400}?fixtureAborts\.push\(/,
    'the pageerror handler must classify and record the terminal abort as it arrives',
  );
  assert.match(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,700}?if\s*\(\s*fixtureAborts\.length\s*>\s*0\s*\)\s*break\s*;/,
    'the poll loop must stop the moment a terminal abort is seen',
  );
  // Only the terminal class short-circuits: an ordinary mid-suite pageerror
  // usually lets the run finish and fail on the summary.
  assert.doesNotMatch(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,700}?if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*break\s*;/,
    'an ordinary pageerror must NOT short-circuit the poll loop',
  );
});

test('run-browser-tests: an unanswerable trusted-input request stops the run instead of hanging it (rf2-il7b)', () => {
  const src = read('run-browser-tests.cjs');
  // The bridge itself is exercised on every `npm run test:browser`; what is
  // pinned is the arm those suites cannot reach. The page SUSPENDS until a
  // request is acknowledged, so one the runner cannot answer would present as
  // a six-minute hang.
  assert.match(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,1600}?serviceTrustedInputRequest\([\s\S]{0,200}?if\s*\(\s*trustedInput\.faults\.length\s*>\s*0\s*\)\s*break\s*;/,
    'the poll loop must stop the moment a request it cannot answer is seen',
  );
  // A press that THREW is acknowledged with its error so the row resumes and
  // reds on the spot; breaking on it would truncate the lane.
  assert.doesNotMatch(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,1600}?if\s*\(\s*trustedInput\.presses\s*[=<>!]/,
    'a failed press must not short-circuit the poll loop',
  );
});

// ---- serve-and-run-xray-feature-gate.cjs ----

test('xray-feature-gate: a scenario with a captured pageerror is not marked passed (rf2-mwx08)', () => {
  const src = read('serve-and-run-xray-feature-gate.cjs');
  assert.match(
    src,
    /if\s*\(\s*browserState\.pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when browserState.pageErrors is non-empty so the scenario fails',
  );
  assertPrecedes(
    src,
    /browserState\.pageErrors\.length\s*>\s*0/,
    /\bpassed\s*=\s*true\s*;/,
    'the pageerror fatal check must precede `passed = true`',
  );
});

// ---- check-story-static.cjs ----

test('check-story-static: a pageerror lands in a dedicated array and throws even when assertions passed (rf2-mwx08)', () => {
  const src = read('check-story-static.cjs');
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the smoke fails (exit 1)',
  );
});

// ---- serve-and-run-tenant-switcher-testbed.cjs (CI-wired) ----

test('tenant-switcher-testbed: a pageerror lands in a dedicated array and fails the spec even when assertions passed (rf2-h5e3v7)', () => {
  const src = read('serve-and-run-tenant-switcher-testbed.cjs');
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the spec fails',
  );
  assertPrecedes(
    src,
    /pageErrors\.length\s*>\s*0/,
    /\bpassed\s*=\s*true\s*;/,
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
