#!/usr/bin/env node

'use strict';

/*
 * Policy + unit gate for the two Story CI-as-test orchestrators under
 * examples/scripts/ (rf2-wf5al). Lives in implementation/scripts/ (NOT
 * under examples/) so it respects the "examples are test-free / no
 * *.spec.cjs under examples/" lock while still pinning the runner
 * behaviour. Wired into package.json via `test:script-policy`.
 *
 * Covers all three rf2-wf5al findings:
 *
 *   1. The :play-script runner must FAIL on an uncaught browser
 *      `pageerror` even when every play row matched its expected status
 *      — otherwise a runtime regression false-greens the gate behind a
 *      clean play-status summary. Asserted via the pure
 *      `computeExitCode({failures, pageErrors})` helper.
 *
 *   2. Both Story http-server orchestrators must bind loopback
 *      explicitly (`-a 127.0.0.1`) rather than the http-server 0.0.0.0
 *      default. Asserted statically over the runner sources.
 *
 *   3. The runner's terminal-status predicate must accept `cannot-run`
 *      (the unified THIRD terminal status, spec/017) so an honest
 *      cannot-run is surfaced immediately instead of burning the full
 *      per-row terminal timeout. Asserted via `isTerminalStatus`, kept
 *      symmetric with the CLJS `ci-runner/terminal?`.
 *
 * Plus the rf2-54xbp non-vacuous discovery guard (rf2-ljyp9 follow-up):
 *
 *   4. The :play-script gate must FAIL CLOSED on an empty / under-floor /
 *      one-sided (no expected-fail) discovery rather than false-greening
 *      "nothing to assert". Asserted via the pure `checkRowsNonVacuous`
 *      / `MIN_PLAY_ROWS` exports, with the runtime call-site pinned.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
// Shared loopbackBindRe factory + framework-free test harness (rf2-j552l2).
const { loopbackBindRe, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = path.resolve(__dirname, '..', '..', 'examples', 'scripts');
const PLAY_SCRIPTS_RUNNER = path.join(
  SCRIPTS_DIR,
  'serve-and-run-story-play-scripts.cjs',
);
const FEATURE_LOAD_RUNNER = path.join(
  SCRIPTS_DIR,
  'serve-and-run-story-feature-load-tests.cjs',
);

// Safe to require: the runner guards its top-level main() behind
// `require.main === module`, so this pulls in only the pure exports.
const {
  computeExitCode,
  isTerminalStatus,
  checkRowsNonVacuous,
  MIN_PLAY_ROWS,
} = require(PLAY_SCRIPTS_RUNNER);

const { test, run } = createPolicyTestSuite('story-script-runners-policy');

// ---- Finding 1: pageerror is fatal, even with all play rows matched ----

test('computeExitCode: clean run (no failures, no pageerrors) → 0', () => {
  assert.equal(computeExitCode({ failures: [], pageErrors: [] }), 0);
});

test('computeExitCode: a pageerror fails the gate even when all play rows matched (rf2-wf5al.1)', () => {
  // The false-green case the bead describes: every play row reported its
  // expected pass/fail status (failures empty) but the shell threw an
  // uncaught exception. Must be RED.
  assert.equal(
    computeExitCode({ failures: [], pageErrors: ['[browser:pageerror] boom'] }),
    1,
  );
});

test('computeExitCode: a play-status mismatch fails the gate (regression guard)', () => {
  assert.equal(
    computeExitCode({ failures: [{ variantId: 'x' }], pageErrors: [] }),
    1,
  );
});

test('computeExitCode: both signals dirty → 1', () => {
  assert.equal(
    computeExitCode({
      failures: [{ variantId: 'x' }],
      pageErrors: ['[browser:pageerror] boom'],
    }),
    1,
  );
});

test('computeExitCode: missing/undefined arrays are treated as empty', () => {
  assert.equal(computeExitCode({}), 0);
  assert.equal(computeExitCode({ failures: undefined, pageErrors: undefined }), 0);
});

// The runner must actually FEED pageErrors into the verdict, not just
// define the helper. Pin the call-site so a refactor can't silently drop
// the pageerror signal back to a failures-only verdict.
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

// ---- Finding 2: both Story orchestrators bind loopback explicitly ----

// Match the http-server spawn argument array and assert it carries the
// `-a 127.0.0.1` loopback flag adjacent to the http-server bin token
// (the play-scripts runner resolves it lazily via httpServerBin(); the
// feature-load runner uses the HTTP_SERVER_BIN constant — accept both).
// NB `[\s\S]{0,80}?` (not `[^]]`): the latter is the JS-regex gotcha
// `[^]` (any char) followed by a literal `]`, which would NOT match here.
const LOOPBACK_BIND_RE = loopbackBindRe('(?:httpServerBin\\(\\)|HTTP_SERVER_BIN)');

for (const runner of [PLAY_SCRIPTS_RUNNER, FEATURE_LOAD_RUNNER]) {
  const base = path.basename(runner);
  test(`${base}: http-server is bound to 127.0.0.1 explicitly (rf2-wf5al.2)`, () => {
    const src = fs.readFileSync(runner, 'utf8');
    assert.match(
      src,
      LOOPBACK_BIND_RE,
      `${base} must spawn http-server with '-a', '127.0.0.1' (loopback only) — ` +
        `the http-server default is 0.0.0.0, and the runner only ever hits ` +
        `127.0.0.1. Match serve-and-run-adapter-smokes.cjs.`,
    );
  });
}

// ---- Finding 3: cannot-run is a terminal status ----

test('isTerminalStatus: pass / fail / cannot-run are terminal (rf2-wf5al.3)', () => {
  assert.equal(isTerminalStatus('pass'), true);
  assert.equal(isTerminalStatus('fail'), true);
  // The crux: an honest cannot-run must be terminal so the wait loop
  // returns immediately instead of burning the full timeout.
  assert.equal(isTerminalStatus('cannot-run'), true);
});

test('isTerminalStatus: non-terminal / unknown statuses are not terminal', () => {
  assert.equal(isTerminalStatus('running'), false);
  assert.equal(isTerminalStatus('queued'), false);
  assert.equal(isTerminalStatus(undefined), false);
  assert.equal(isTerminalStatus(null), false);
  assert.equal(isTerminalStatus(''), false);
});

// Pin both wait loops to the shared predicate so neither can regress to
// an inline pass/fail-only check that re-strands cannot-run.
test('both wait loops gate on isTerminalStatus (rf2-wf5al.3)', () => {
  const src = fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8');
  const matches = src.match(/isTerminalStatus\(last\.status\)/g) || [];
  assert.ok(
    matches.length >= 2,
    'waitForTerminalState and waitForPlayTerminalState must both gate on ' +
      'isTerminalStatus(last.status).',
  );
});

// ---- Finding 4 (rf2-54xbp / rf2-ljyp9): non-vacuous discovery guard ----
//
// rf2-54xbp added `checkRowsNonVacuous(rows)` + `MIN_PLAY_ROWS` to the
// :play-script runner: a pure (rows in → verdict out) guard that fails
// the gate closed on an empty / under-floor / one-sided discovery rather
// than false-greening "nothing to assert". The runtime call-site is
// pinned below; these cases lock the pure verdict (the canonical home the
// rf2-54xbp examples/scripts lane could not reach). Rows are classified
// expected-fail iff `variant-id` OR `play-key` carries `failing` /
// `expected-fail` — symmetric with the runner's `expectedStatusFor` — so
// the test rows here drive the same classification the runtime uses.

// The seeded inventory shape: eight discovered rows, five classified
// expected-pass and three expected-fail (the `failing` / `-expected-fail`
// markers below) — the 8 / 5-pass / 3-fail shape the bead pins as the
// healthy gate. Mirrors the counter-with-stories testbed's seeded
// fixtures and the runner's `expectedStatusFor` classification.
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
  assert.equal(v.ok, false);
  assert.equal(v.rowCount, 0);
  assert.match(v.diagnostic, /DRIFTED/);
});

test('checkRowsNonVacuous: a non-array (undefined) discovery is treated as empty → { ok:false }', () => {
  const v = checkRowsNonVacuous(undefined);
  assert.equal(v.ok, false);
  assert.equal(v.rowCount, 0);
  assert.match(v.diagnostic, /DRIFTED/);
});

test('checkRowsNonVacuous: under-floor (< MIN_PLAY_ROWS) → { ok:false } /below the non-vacuous floor/ (rf2-54xbp)', () => {
  // MIN_PLAY_ROWS rows minus one, with BOTH sides present so the failure
  // is attributable to the floor and not to one-sidedness.
  const underFloor = [
    { 'variant-id': 'story.counter/basic', 'play-key': null },
    { 'variant-id': 'story.counter/dom', 'play-key': null },
    { 'variant-id': 'story.counter/dom-expected-fail', 'play-key': null },
  ];
  assert.ok(underFloor.length < MIN_PLAY_ROWS, 'fixture must sit below the floor');
  const v = checkRowsNonVacuous(underFloor);
  assert.equal(v.ok, false);
  assert.equal(v.rowCount, underFloor.length);
  assert.match(v.diagnostic, /below the non-vacuous floor/);
});

test('checkRowsNonVacuous: one-sided (no expected-fail) → { ok:false } /one-sided|expected-fail=0/ (rf2-54xbp)', () => {
  // At/above the floor, but every row classifies expected-pass — the
  // runner's failure path is uncovered.
  const oneSided = [
    { 'variant-id': 'story.counter/basic', 'play-key': null },
    { 'variant-id': 'story.counter/keyword-play', 'play-key': null },
    { 'variant-id': 'story.counter/pred-fn-vector', 'play-key': null },
    { 'variant-id': 'story.counter/dom', 'play-key': null },
  ];
  assert.ok(oneSided.length >= MIN_PLAY_ROWS, 'fixture must clear the floor');
  const v = checkRowsNonVacuous(oneSided);
  assert.equal(v.ok, false);
  assert.equal(v.failRows, 0);
  assert.match(v.diagnostic, /one-sided|expected-fail=0/);
});

test('checkRowsNonVacuous: seeded inventory shape (8 rows, 5 pass / 3 fail) → { ok:true } (rf2-54xbp)', () => {
  const v = checkRowsNonVacuous(SEEDED_ROWS);
  assert.equal(v.ok, true);
  assert.equal(v.diagnostic, null);
  assert.equal(v.rowCount, 8);
  assert.equal(v.passRows, 5);
  assert.equal(v.failRows, 3);
});

// Pin the runtime call-site so a refactor can't drop the non-vacuous
// guard back to the old "empty rows → 0 / nothing to assert" false-green.
test('play-scripts runner gates discovery on checkRowsNonVacuous (rf2-54xbp / rf2-ljyp9)', () => {
  const src = fs.readFileSync(PLAY_SCRIPTS_RUNNER, 'utf8');
  assert.match(
    src,
    /checkRowsNonVacuous\(\s*rows\s*\)/,
    'runAllVariants must call checkRowsNonVacuous(rows) over the discovered rows.',
  );
  assert.match(
    src,
    /if\s*\(\s*!\s*vacuity\.ok\s*\)/,
    'the runner must fail closed (throw) when the non-vacuous verdict is not ok.',
  );
});

run();
