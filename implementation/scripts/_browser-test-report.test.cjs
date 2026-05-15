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
