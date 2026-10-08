#!/usr/bin/env node

const assert = require('assert/strict');
const {
  classifyTrustedInputRequest,
  createDiagnosticBuffer,
  findFixtureAbort,
  formatCompactSummary,
  isVerboseTests,
  parseFailureCounts,
  parseRanCounts,
  summaryPartsFromText,
  testingNamespaces,
} = require('./lib/browser-test-report.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

// A prelude app log shaped like a zero-failure summary must not be paired as the
// failure line: the paired parser binds the line that FOLLOWS the selected Ran.
test('noisy zero-failure prelude before the real run is not mis-paired as green (rf2-mwx08)', () => {
  const blob = [
    '[browser:log] app boot: 0 failures, 0 errors.',
    'FAIL in (my-test) expected: 1 actual: 2',
    'Ran 8 tests containing 20 assertions.',
    '1 failures, 0 errors.',
  ].join('\n');
  assert.deepEqual(summaryPartsFromText(blob), {
    ran: 'Ran 8 tests containing 20 assertions.',
    failErr: '1 failures, 0 errors.',
  });
});

// A null failErr is the runner's "no verdict yet, so the run fails" signal.
test('a failures/errors line with no preceding Ran line is ignored (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    '[browser:log] some lib says: 0 failures, 0 errors.',
    '[browser:log] still booting',
  ].join('\n'));
  assert.deepEqual(parts, { ran: null, failErr: null });
});

test('last complete cljs.test summary pair wins on a re-run (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    'Ran 3 tests containing 9 assertions.',
    '0 failures, 0 errors.',
    '[browser:log] re-running suite',
    'Ran 4 tests containing 12 assertions.',
    '2 failures, 1 errors.',
  ].join('\n'));
  assert.deepEqual(parts, { ran: 'Ran 4 tests containing 12 assertions.', failErr: '2 failures, 1 errors.' });
});

test('a Ran line with no failures/errors line yet surfaces ran only (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    '[browser:log] booted',
    'Ran 8 tests containing 20 assertions.',
  ].join('\n'));
  assert.deepEqual(parts, { ran: 'Ran 8 tests containing 20 assertions.', failErr: null });
});

// The executed-test count must be read: a lane that ran nothing has a clean
// failure tally, so only this count tells it from green.
test('ran-count parser exposes the executed-test count (rf2-qqzmf)', () => {
  assert.deepEqual(
    [
      'Ran 12 tests containing 34 assertions.',
      'Ran 0 tests containing 0 assertions.',
      'Ran 1 test containing 1 assertion.',
    ].map(parseRanCounts),
    [{ tests: 12, assertions: 34 }, { tests: 0, assertions: 0 }, { tests: 1, assertions: 1 }],
  );
});

// null is not "zero tests": the runner treats an unparseable summary as its own failure.
test('ran-count parser returns null for unparseable / null input (rf2-qqzmf)', () => {
  assert.deepEqual([null, '0 failures, 0 errors.', 'Ran some tests'].map(parseRanCounts), [null, null, null]);
});

test('summary parser tolerates null input (returns null parts)', () => {
  assert.deepEqual(summaryPartsFromText(null), { ran: null, failErr: null });
});

// A null here is run-browser-tests.cjs's "could not parse; failing the run" guard.
test('failure count parser returns null for unparseable / null input', () => {
  assert.deepEqual([null, 'Ran 3 tests containing 5 assertions.'].map(parseFailureCounts), [null, null]);
});

test('failure count parser reads failures and errors independently', () => {
  assert.deepEqual(
    ['0 failures, 0 errors.', '1 failures, 0 errors.', '0 failures, 3 errors.'].map(parseFailureCounts),
    [{ failures: 0, errors: 0 }, { failures: 1, errors: 0 }, { failures: 0, errors: 3 }],
  );
});

test('green browser summary is one line', () => {
  const line = formatCompactSummary({
    ran: 'Ran 12 tests containing 34 assertions.',
    failErr: '0 failures, 0 errors.',
    source: 'browser console',
  });

  assert.equal(
    line,
    'Browser tests: Ran 12 tests containing 34 assertions. 0 failures, 0 errors. (source: browser console)'
  );
});

test('diagnostic buffer preserves output streams until flush', () => {
  const buffer = createDiagnosticBuffer();
  buffer.add('[browser:log] hello');
  buffer.add('page exploded\nstack line', 'stderr');

  const stdout = [];
  const stderr = [];
  buffer.flush({
    stdout: (line) => stdout.push(line),
    stderr: (line) => stderr.push(line),
  });

  assert.deepEqual({ stdout, stderr }, { stdout: ['[browser:log] hello'], stderr: ['page exploded', 'stack line'] });
});

test('diagnostic buffer reports emptiness and ignores null adds', () => {
  const buffer = createDiagnosticBuffer();
  buffer.add(null);
  buffer.add(undefined);
  assert.equal(buffer.isEmpty(), true, 'null/undefined adds are no-ops');
  buffer.add('first line');
  assert.equal(buffer.isEmpty(), false);
});

// The literal cljs.test 1.12.x rethrows from `test-var-block*`'s
// `::async-disabled` branch, captured from a real aborted browser run.
const REAL_ABORT_TEXT =
  'Async tests require fixtures to be specified as maps.  Testing aborted.';

test('the cljs.test fixture abort is recognised in the captured page errors (rf2-u0j8)', () => {
  assert.equal(
    findFixtureAbort(['TypeError: x is not a function', REAL_ABORT_TEXT]),
    REAL_ABORT_TEXT,
  );
});

// Narrow on purpose: an ordinary mid-suite throw is not terminal, and treating
// it as one would truncate a run that was about to report its summary.
test('an ordinary uncaught exception is NOT classified as the fixture abort (rf2-u0j8)', () => {
  assert.deepEqual([null, ['Async test called done more than one time']].map(findFixtureAbort), [null, null]);
});

// Two independent substrings, so an upstream reword has to take out both.
test('either half of the abort sentence is enough to match (rf2-u0j8)', () => {
  assert.ok(
    findFixtureAbort(['… Testing aborted.']) &&
      findFixtureAbort(['Async tests require fixtures to be specified as maps.']),
  );
});

// cljs-test-display prints `\nTesting <ns>` at every :begin-test-ns.
test('the announced namespaces are recovered from the console trail, in order (rf2-u0j8)', () => {
  const lines = [
    '\nTesting day8.re-frame2-xray.theme.a11y-dom-cljs-test',
    'some app log',
    '\nTesting re-frame.aaa-abort-probe-dom-cljs-test',
  ];
  assert.deepEqual(testingNamespaces(lines), [
    'day8.re-frame2-xray.theme.a11y-dom-cljs-test',
    're-frame.aaa-abort-probe-dom-cljs-test',
  ]);
});

test('a "Testing" mention inside a log line is not mistaken for a namespace (rf2-u0j8)', () => {
  assert.deepEqual(
    [['When Testing this, wrap in act(...)'], ['Testing'], null].map(testingNamespaces),
    [[], [], []],
  );
});

// The suites that drive the trusted-input bridge witness that it works; what they
// cannot witness is a request the runner cannot acknowledge, which suspends the
// publishing row and takes the whole lane down.
test('nothing published on the bridge is idle, not a fault (rf2-il7b)', () => {
  assert.deepEqual([null, { present: false }].map(classifyTrustedInputRequest), [{ kind: 'idle' }, { kind: 'idle' }]);
});

test('a well-formed request is servable, and its keys are strings (rf2-il7b)', () => {
  assert.deepEqual(
    classifyTrustedInputRequest({
      present: true,
      token: 'rf2-trusted-input-3',
      tokenType: 'string',
      keys: ['Tab', 'Escape'],
      keysType: 'array',
      ackType: 'function',
    }),
    { kind: 'ready', token: 'rf2-trusted-input-3', keys: ['Tab', 'Escape'] },
  );
});

// No token, no keys or no ack each guarantees a stall: nothing to match the ack
// against, nothing to press, nowhere for the answer to go.
test('a request the runner cannot acknowledge is terminal, and says which part (rf2-il7b)', () => {
  const ok = { present: true, token: 't', tokenType: 'string', keys: ['Tab'], keysType: 'array', ackType: 'function' };
  const verdicts = [
    [{ ...ok, token: null, tokenType: 'undefined' }, 'token'],
    [{ ...ok, keys: null, keysType: 'string' }, 'keys'],
    [{ ...ok, ackType: 'undefined' }, 'ack'],
  ].map(([request, part]) => {
    const { kind, reason } = classifyTrustedInputRequest(request);
    return [kind, reason.includes(part)];
  });
  assert.deepEqual(verdicts, [['malformed', true], ['malformed', true], ['malformed', true]]);
});

// Zero presses is a page-side bug, not a stall: the runner acks `pressed: 0` and
// the row reds on its own assertion.
test('an empty key list is served, not refused (rf2-il7b)', () => {
  assert.deepEqual(
    classifyTrustedInputRequest({
      present: true, token: 't', tokenType: 'string',
      keys: [], keysType: 'array', ackType: 'function',
    }),
    { kind: 'ready', token: 't', keys: [] },
  );
});

test('RF2_VERBOSE_TESTS=1 enables verbose mode', () => {
  assert.deepEqual(
    [{ RF2_VERBOSE_TESTS: '1' }, { RF2_VERBOSE_TESTS: 'true' }, {}].map((env) => isVerboseTests(env)),
    [true, false, false],
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
  console.error(`browser-test-report tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`browser-test-report tests: ${tests.length} passed.`);
