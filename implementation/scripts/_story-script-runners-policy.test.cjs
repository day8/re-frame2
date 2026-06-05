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
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

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
} = require(PLAY_SCRIPTS_RUNNER);

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

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
const LOOPBACK_BIND_RE =
  /(?:httpServerBin\(\)|HTTP_SERVER_BIN),[\s\S]{0,80}?['"]-a['"]\s*,\s*['"]127\.0\.0\.1['"]/;

for (const runner of [PLAY_SCRIPTS_RUNNER, FEATURE_LOAD_RUNNER]) {
  const base = path.basename(runner);
  test(`${base}: http-server is bound to 127.0.0.1 explicitly (rf2-wf5al.2)`, () => {
    const src = fs.readFileSync(runner, 'utf8');
    assert.match(
      src,
      LOOPBACK_BIND_RE,
      `${base} must spawn http-server with '-a', '127.0.0.1' (loopback only) — ` +
        `the http-server default is 0.0.0.0, and the runner only ever hits ` +
        `127.0.0.1. Match serve-and-run-examples-tests.cjs.`,
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
  console.error(`story-script-runners-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`story-script-runners-policy tests: ${tests.length} passed.`);
