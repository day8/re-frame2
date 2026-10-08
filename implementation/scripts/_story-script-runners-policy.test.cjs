#!/usr/bin/env node

'use strict';

/*
 * Policy and unit tests for the two Story CI-as-test orchestrators under
 * examples/scripts/ (examples/ carries no tests of its own): an uncaught page
 * error fails the play-scripts gate even when every play row matched, `cannot-run`
 * is terminal, the play-scripts discovery fails closed when vacuous, and both
 * runners start http-server through the shared loopback-binding owner.
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = path.resolve(__dirname, '..', '..', 'examples', 'scripts');
const PLAY_SCRIPTS_RUNNER = path.join(
  SCRIPTS_DIR,
  'serve-and-run-story-play-scripts.cjs',
);
const FEATURE_LOAD_RUNNER = path.join(
  SCRIPTS_DIR,
  'serve-and-run-story-feature-load-tests.cjs',
);

// The runner guards main() behind `require.main === module`.
const {
  computeExitCode,
  isTerminalStatus,
  checkRowsNonVacuous,
  MIN_PLAY_ROWS,
  TESTBEDS,
} = require(PLAY_SCRIPTS_RUNNER);

const { test, run } = createPolicyTestSuite('story-script-runners-policy');

test('computeExitCode: a pageerror or a play-status mismatch fails the gate, alone or together (rf2-wf5al.1)', () => {
  const pageError = '[browser:pageerror] boom';
  assert.deepEqual(
    [
      { failures: [], pageErrors: [] },
      { failures: [], pageErrors: [pageError] },
      { failures: [{ variantId: 'x' }], pageErrors: [] },
      { failures: [{ variantId: 'x' }], pageErrors: [pageError] },
      {},
    ].map(computeExitCode),
    [0, 1, 1, 1, 0],
  );
});

// The verdict must be fed pageErrors, or a refactor drops back to failures-only.
test('play-scripts runner verdict is computed from BOTH failures and pageErrors (rf2-wf5al.1)', () => {
  const src = fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8');
  assert.match(
    src,
    /return\s+computeExitCode\(\{\s*failures,\s*pageErrors\s*\}\)/,
    'runAllVariants must return computeExitCode({ failures, pageErrors }) — ' +
      'the pageerror signal must reach the verdict.',
  );
  assert.match(
    src,
    /pageErrors\.push\(/,
    'the pageerror handler must record into the separately-tracked pageErrors array.',
  );
});

// startLocalHttpServer composes the loopback `-a 127.0.0.1` bind once, verified
// functionally in _local-browser-harness.test.cjs.
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;

for (const runner of [PLAY_SCRIPTS_RUNNER, FEATURE_LOAD_RUNNER]) {
  const base = path.basename(runner);
  test(`${base}: delegates http-server startup to the shared startLocalHttpServer owner (rf2-wf5al.2 / rf2-slapfs)`, () => {
    const code = stripComments(fs.readFileSync(runner, 'utf8'));
    assert.match(
      code,
      START_LOCAL_HTTP_SERVER_RE,
      `${base} must start its http-server via the shared startLocalHttpServer(...) owner`,
    );
  });
}

// An honest cannot-run must be terminal, or the wait loop burns its full timeout.
test('isTerminalStatus: pass / fail / cannot-run are terminal, anything else is not (rf2-wf5al.3)', () => {
  assert.deepEqual(
    ['pass', 'fail', 'cannot-run', 'running', undefined].map(isTerminalStatus),
    [true, true, true, false, false],
  );
});

test('both wait loops gate on isTerminalStatus (rf2-wf5al.3)', () => {
  const src = fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8');
  const matches = src.match(/isTerminalStatus\(last\.status\)/g) || [];
  assert.ok(
    matches.length >= 2,
    'waitForTerminalState and waitForPlayTerminalState must both gate on ' +
      'isTerminalStatus(last.status).',
  );
});

// ---- non-vacuous discovery ----
//
// A row is expected-fail iff its `variant-id` or `play-key` carries `failing` /
// `expected-fail`, as in the runner's `expectedStatusFor`. The healthy seeded
// inventory is eight rows, five expected-pass and three expected-fail.
const SEEDED_ROWS = [
  // 5 expected-pass:
  { 'variant-id': 'story.counter/basic', 'play-key': null },
  { 'variant-id': 'story.counter/keyword-play', 'play-key': null },
  { 'variant-id': 'story.counter/pred-fn-vector', 'play-key': null },
  { 'variant-id': 'story.counter/dom', 'play-key': null },
  { 'variant-id': 'story.counter/multi', 'play-key': 'passing' },
  // 3 expected-fail (carry a `failing` / `expected-fail` marker):
  { 'variant-id': 'story.counter/dom-expected-fail', 'play-key': null },
  { 'variant-id': 'story.counter/pred-fn-failing', 'play-key': null },
  { 'variant-id': 'story.counter/multi', 'play-key': 'expected-fail' },
];

test('checkRowsNonVacuous: empty discovery → { ok:false } with a DRIFTED diagnostic (rf2-54xbp)', () => {
  const v = checkRowsNonVacuous([]);
  assert.ok(!v.ok && /DRIFTED/.test(v.diagnostic), JSON.stringify(v));
});

test('checkRowsNonVacuous: a non-array (undefined) discovery is treated as empty → { ok:false }', () => {
  const v = checkRowsNonVacuous(undefined);
  assert.ok(!v.ok && /DRIFTED/.test(v.diagnostic), JSON.stringify(v));
});

test('checkRowsNonVacuous: under-floor (< MIN_PLAY_ROWS) → { ok:false } /below the non-vacuous floor/ (rf2-54xbp)', () => {
  // Both sides present, so the failure is the floor's and not one-sidedness.
  const v = checkRowsNonVacuous([
    { 'variant-id': 'story.counter/basic', 'play-key': null },
    { 'variant-id': 'story.counter/dom', 'play-key': null },
    { 'variant-id': 'story.counter/dom-expected-fail', 'play-key': null },
  ]);
  assert.ok(!v.ok && /below the non-vacuous floor/.test(v.diagnostic), JSON.stringify(v));
});

const ONE_SIDED_ROWS = [
  { 'variant-id': 'story.counter/basic', 'play-key': null },
  { 'variant-id': 'story.counter/keyword-play', 'play-key': null },
  { 'variant-id': 'story.counter/pred-fn-vector', 'play-key': null },
  { 'variant-id': 'story.counter/dom', 'play-key': null },
];

test('checkRowsNonVacuous: one-sided (no expected-fail) → { ok:false } /one-sided|expected-fail=0/ (rf2-54xbp)', () => {
  const v = checkRowsNonVacuous(ONE_SIDED_ROWS);
  assert.ok(!v.ok && /one-sided|expected-fail=0/.test(v.diagnostic), JSON.stringify(v));
});

test('checkRowsNonVacuous: seeded inventory shape (8 rows, 5 pass / 3 fail) → { ok:true } (rf2-54xbp)', () => {
  const { ok, diagnostic, rowCount, passRows, failRows } = checkRowsNonVacuous(SEEDED_ROWS);
  assert.deepEqual(
    { ok, diagnostic, rowCount, passRows, failRows },
    { ok: true, diagnostic: null, rowCount: 8, passRows: 5, failRows: 3 },
  );
});

// The trailing `[,)]` admits the per-testbed opts argument.
test('play-scripts runner gates discovery on checkRowsNonVacuous (rf2-54xbp / rf2-ljyp9)', () => {
  const src = fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8');
  assert.match(
    src,
    /checkRowsNonVacuous\(\s*rows\s*[,)]/,
    'runTestbed must call checkRowsNonVacuous(rows, …) over the discovered rows.',
  );
  assert.match(
    src,
    /if\s*\(\s*!\s*vacuity\.ok\s*\)/,
    'the runner must fail closed (throw) when the non-vacuous verdict is not ok.',
  );
});

// ---- the roster, and the per-testbed floors ----
//
// A deck dropped from the roster stops running without going red; the fresco
// entry's opt-out waives the expected-fail requirement and nothing else.

test('the play-scripts roster names both Story testbeds (rf2-kttom)', () => {
  const labels = TESTBEDS.map((t) => t.label);
  assert.ok(
    ['counter-with-stories', 'fresco-counter'].every((label) => labels.includes(label)),
    `both Story testbeds must stay on the roster, got: ${labels.join(', ')}`,
  );
});

test("the counter entry's floor is UNCHANGED — four rows, both sides (rf2-kttom)", () => {
  const { minRows, requireBothSides } = TESTBEDS.find((t) => t.label === 'counter-with-stories').vacuity;
  assert.deepEqual({ minRows, bothSides: requireBothSides !== false }, { minRows: MIN_PLAY_ROWS, bothSides: true });
});

test('checkRowsNonVacuous: requireBothSides defaults TRUE when opts omit it (rf2-54xbp)', () => {
  assert.equal(checkRowsNonVacuous(ONE_SIDED_ROWS, { minRows: 4 }).ok, false);
});

test('checkRowsNonVacuous: an opted-out entry passes on ONE successful play (rf2-kttom)', () => {
  const fresco = TESTBEDS.find((t) => t.label === 'fresco-counter');
  const v = checkRowsNonVacuous(
    [{ 'variant-id': 'story.fresco-counter/tally', 'play-key': null }],
    fresco.vacuity,
  );
  assert.equal(v.ok, true);
});

test('checkRowsNonVacuous: an opted-out entry still fails CLOSED on zero rows (rf2-kttom)', () => {
  const fresco = TESTBEDS.find((t) => t.label === 'fresco-counter');
  const v = checkRowsNonVacuous([], fresco.vacuity);
  assert.ok(!v.ok && /DRIFTED/.test(v.diagnostic), JSON.stringify(v));
});

// For an entry whose job is to prove one play succeeds, all-expected-fail is
// vacuous in the direction the opt-out does not cover.
test('checkRowsNonVacuous: the opt-out waives expected-fail ONLY — no successful play is still RED (rf2-kttom)', () => {
  const v = checkRowsNonVacuous(
    [{ 'variant-id': 'story.fresco-counter/failing-tally', 'play-key': null }],
    { minRows: 1, requireBothSides: false },
  );
  assert.ok(!v.ok && /no successful play/.test(v.diagnostic), JSON.stringify(v));
});

// Or a refactor collapses the roster to one hardcoded testbed unseen.
test('play-scripts runner drives every roster entry (rf2-kttom)', () => {
  const src = stripComments(fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8'));
  assert.match(
    src,
    /for\s*\(\s*const\s+testbed\s+of\s+TESTBEDS\s*\)/,
    'runAllVariants must iterate the whole TESTBEDS roster.',
  );
  assert.match(
    src,
    /runTestbed\(\s*browser\s*,\s*baseUrl\s*,\s*testbed\s*\)/,
    'each roster entry must be driven through runTestbed(browser, baseUrl, testbed).',
  );
});

run();
