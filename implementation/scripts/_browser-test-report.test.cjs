#!/usr/bin/env node

const assert = require('assert/strict');
const {
  createDiagnosticBuffer,
  formatCompactSummary,
  isVerboseTests,
  parseFailureCounts,
  summaryPartsFromText,
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
