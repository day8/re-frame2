#!/usr/bin/env node

const assert = require('assert/strict');
const {
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

test('summary parser extracts cljs.test counts from noisy text', () => {
  const parts = summaryPartsFromText([
    '[browser:log] booted',
    'Ran 12 tests containing 34 assertions.',
    '0 failures, 0 errors.',
  ].join('\n'));

  assert.deepEqual(parts, {
    ran: 'Ran 12 tests containing 34 assertions.',
    failErr: '0 failures, 0 errors.',
  });
});

// rf2-mwx08 (regression): a prelude app log shaped exactly like a
// zero-failure cljs.test summary (`0 failures, 0 errors.`) that PRECEDES
// the real `Ran ...` line must NOT be paired as the failure summary. The
// old first-match-of-each parser returned `{failErr: "0 failures, 0
// errors."}` here and false-greened a red run. The paired parser binds
// the failures/errors line that FOLLOWS the selected `Ran ...` line.
test('noisy zero-failure prelude before the real run is not mis-paired as green (rf2-mwx08)', () => {
  const blob = [
    '[browser:log] app boot: 0 failures, 0 errors.',
    'FAIL in (my-test) expected: 1 actual: 2',
    'Ran 8 tests containing 20 assertions.',
    '1 failures, 0 errors.',
  ].join('\n');
  const parts = summaryPartsFromText(blob);
  assert.equal(parts.ran, 'Ran 8 tests containing 20 assertions.');
  // MUST be the real red line, never the prelude zero line.
  assert.equal(parts.failErr, '1 failures, 0 errors.');
  const counts = parseFailureCounts(parts.failErr);
  assert.equal(counts.failures > 0 || counts.errors > 0, true,
    'red run must parse to a non-zero count, not green');
});

// rf2-mwx08: a bare `failures, errors` line with no preceding `Ran ...`
// is console noise, not a summary — it must be ignored, leaving failErr
// null (the "no verdict yet → run fails" signal in run-browser-tests).
test('a failures/errors line with no preceding Ran line is ignored (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    '[browser:log] some lib says: 0 failures, 0 errors.',
    '[browser:log] still booting',
  ].join('\n'));
  assert.deepEqual(parts, { ran: null, failErr: null });
});

// rf2-mwx08: when a page emits more than one cljs.test summary (e.g. a
// re-run), the LAST complete pair wins, and each failErr stays bound to
// its own Ran line.
test('last complete cljs.test summary pair wins on a re-run (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    'Ran 3 tests containing 9 assertions.',
    '0 failures, 0 errors.',
    '[browser:log] re-running suite',
    'Ran 4 tests containing 12 assertions.',
    '2 failures, 1 errors.',
  ].join('\n'));
  assert.equal(parts.ran, 'Ran 4 tests containing 12 assertions.');
  assert.equal(parts.failErr, '2 failures, 1 errors.');
});

// rf2-mwx08: an un-paired `Ran ...` line (failures/errors not yet
// streamed) surfaces the `ran` half for a meaningful timeout message,
// but failErr stays null so no green verdict can form.
test('a Ran line with no failures/errors line yet surfaces ran only (rf2-mwx08)', () => {
  const parts = summaryPartsFromText([
    '[browser:log] booted',
    'Ran 8 tests containing 20 assertions.',
  ].join('\n'));
  assert.equal(parts.ran, 'Ran 8 tests containing 20 assertions.');
  assert.equal(parts.failErr, null);
});

// rf2-qqzmf: the `Ran N tests containing M assertions.` integers were
// captured by RAN_RE and never read, so every browser lane's verdict came
// from the failure tally alone — and a lane that ran NOTHING satisfies it.
// This is the auditor's own repro, now a pin: the zero-test blob must yield
// a readable count of 0 for the runner's floor to act on.
test('ran-count parser exposes the executed-test count (rf2-qqzmf)', () => {
  assert.deepEqual(
    parseRanCounts('Ran 12 tests containing 34 assertions.'),
    { tests: 12, assertions: 34 },
  );
  // The zero-test summary the audit fed the runner's own library. Its
  // failure tally is clean, so only this count distinguishes it from green.
  const parts = summaryPartsFromText(
    'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.\n',
  );
  assert.deepEqual(parseFailureCounts(parts.failErr), { failures: 0, errors: 0 });
  assert.deepEqual(parseRanCounts(parts.ran), { tests: 0, assertions: 0 });
  // Singular forms ("Ran 1 test containing 1 assertion.") are the same regex.
  assert.deepEqual(
    parseRanCounts('Ran 1 test containing 1 assertion.'),
    { tests: 1, assertions: 1 },
  );
});

test('ran-count parser returns null for unparseable / null input (rf2-qqzmf)', () => {
  // A null return is NOT "zero tests" — the runner must treat an
  // unparseable summary as its own failure, never as a count to compare.
  assert.equal(parseRanCounts(null), null);
  assert.equal(parseRanCounts(undefined), null);
  assert.equal(parseRanCounts(''), null);
  assert.equal(parseRanCounts('0 failures, 0 errors.'), null);
  assert.equal(parseRanCounts('Ran some tests'), null);
});

test('failure count parser returns numeric counts', () => {
  assert.deepEqual(parseFailureCounts('2 failures, 1 errors.'), {
    failures: 2,
    errors: 1,
  });
});

// run-browser-tests.cjs line 149: `if (!summary.ran || !summary.failErr)`
// → a null part means "no cljs.test summary was found" and the run FAILS
// (return 1). Pin that the parser yields nulls when the summary is absent,
// so a crashed / summary-less browser run can never be read as green.
test('summary parser returns null parts when no cljs.test summary is present', () => {
  const parts = summaryPartsFromText([
    '[browser:log] booting',
    '[browser:error] ReferenceError: app is not defined',
    'stack trace line',
  ].join('\n'));
  assert.deepEqual(parts, { ran: null, failErr: null });
});

test('summary parser tolerates null / empty input (returns null parts)', () => {
  assert.deepEqual(summaryPartsFromText(null), { ran: null, failErr: null });
  assert.deepEqual(summaryPartsFromText(''), { ran: null, failErr: null });
});

// run-browser-tests.cjs line 156-157: a null from parseFailureCounts is the
// "could not parse failures/errors; failing the run" guard. Pin it so a
// malformed summary line is a FAIL, never a silent pass.
test('failure count parser returns null for unparseable / null input', () => {
  assert.equal(parseFailureCounts(null), null);
  assert.equal(parseFailureCounts('Ran 3 tests containing 5 assertions.'), null);
  assert.equal(parseFailureCounts('all good!'), null);
});

// The green decision (line 164) keys off the parsed counts. Pin the
// red-path numerics: a clean run is 0/0; failures and errors are read
// independently and exactly.
test('failure count parser reads failures and errors independently', () => {
  assert.deepEqual(parseFailureCounts('0 failures, 0 errors.'), {
    failures: 0,
    errors: 0,
  });
  assert.deepEqual(parseFailureCounts('1 failures, 0 errors.'), {
    failures: 1,
    errors: 0,
  });
  assert.deepEqual(parseFailureCounts('0 failures, 3 errors.'), {
    failures: 0,
    errors: 3,
  });
});

// End-to-end of the read path: a noisy console blob that DOES contain a
// red summary must surface both parts AND parse to a non-zero count —
// the exact sequence run-browser-tests drives before returning 1.
test('a red cljs.test run is extracted and parsed as non-zero from noisy console output', () => {
  const blob = [
    '[browser:log] booted',
    'FAIL in (my-test) expected: 1 actual: 2',
    'Ran 8 tests containing 20 assertions.',
    '1 failures, 0 errors.',
  ].join('\n');
  const parts = summaryPartsFromText(blob);
  assert.equal(parts.ran, 'Ran 8 tests containing 20 assertions.');
  assert.equal(parts.failErr, '1 failures, 0 errors.');
  const counts = parseFailureCounts(parts.failErr);
  assert.equal(counts.failures > 0 || counts.errors > 0, true);
});

test('green browser summary is one line', () => {
  const line = formatCompactSummary({
    ran: 'Ran 12 tests containing 34 assertions.',
    failErr: '0 failures, 0 errors.',
    source: 'browser console',
  });

  assert.equal(line.split(/\r?\n/).length, 1);
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

  assert.deepEqual(stdout, ['[browser:log] hello']);
  assert.deepEqual(stderr, ['page exploded', 'stack line']);
});

test('diagnostic buffer reports emptiness and ignores null adds', () => {
  const buffer = createDiagnosticBuffer();
  assert.equal(buffer.isEmpty(), true);
  buffer.add(null);
  buffer.add(undefined);
  assert.equal(buffer.isEmpty(), true, 'null/undefined adds are no-ops');
  buffer.add('first line');
  assert.equal(buffer.isEmpty(), false);
});

test('diagnostic buffer entries() returns a defensive copy', () => {
  const buffer = createDiagnosticBuffer();
  buffer.add('a');
  const snapshot = buffer.entries();
  snapshot.push({ stream: 'stdout', text: 'mutation' });
  // Mutating the returned array must not leak back into the buffer.
  assert.equal(buffer.entries().length, 1);
  assert.deepEqual(buffer.entries(), [{ stream: 'stdout', text: 'a' }]);
});

// rf2-u0j8 — the fixture abort. The literal below is the one cljs.test 1.12.x
// rethrows from `test-var-block*`'s `::async-disabled` branch, captured from a
// real aborted `npm run test:browser` run.
const REAL_ABORT_TEXT =
  'Async tests require fixtures to be specified as maps.  Testing aborted.';

test('the cljs.test fixture abort is recognised in the captured page errors (rf2-u0j8)', () => {
  assert.equal(findFixtureAbort([REAL_ABORT_TEXT]), REAL_ABORT_TEXT);
  // It is found wherever it sits in the list — the abort is rarely the only
  // thing a busy page throws.
  assert.equal(
    findFixtureAbort(['TypeError: x is not a function', REAL_ABORT_TEXT]),
    REAL_ABORT_TEXT,
  );
});

test('an ordinary uncaught exception is NOT classified as the fixture abort (rf2-u0j8)', () => {
  // This is the arm that must stay narrow: an ordinary mid-suite throw is not
  // terminal, and treating it as one would truncate a run that was about to
  // report its summary.
  assert.equal(findFixtureAbort([]), null);
  assert.equal(findFixtureAbort(null), null);
  assert.equal(
    findFixtureAbort(['Error: Cannot read properties of null (reading "foo")']),
    null,
  );
  assert.equal(findFixtureAbort(['Async test called done more than one time']), null);
});

test('either half of the abort sentence is enough to match (rf2-u0j8)', () => {
  // Two independent substrings, so an upstream reword has to take out BOTH
  // before immediacy is lost — and even then the matcher-free
  // no-summary-with-pageerror arm still reports the abort.
  assert.ok(findFixtureAbort(['… Testing aborted.']));
  assert.ok(findFixtureAbort(['Async tests require fixtures to be specified as maps.']));
});

test('the announced namespaces are recovered from the console trail, in order (rf2-u0j8)', () => {
  // cljs-test-display prints `\nTesting <ns>` at every :begin-test-ns, so a
  // single console message carries a leading blank line.
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
  assert.deepEqual(testingNamespaces(['When Testing this, wrap in act(...)']), []);
  assert.deepEqual(testingNamespaces(['Testing']), []);
  assert.deepEqual(testingNamespaces([]), []);
  assert.deepEqual(testingNamespaces(null), []);
});

test('RF2_VERBOSE_TESTS=1 enables verbose mode', () => {
  assert.equal(isVerboseTests({ RF2_VERBOSE_TESTS: '1' }), true);
  assert.equal(isVerboseTests({ RF2_VERBOSE_TESTS: 'true' }), false);
  assert.equal(isVerboseTests({}), false);
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
