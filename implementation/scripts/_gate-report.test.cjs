#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const { createGateReporter } = require('./lib/gate-report.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

test('green details are buffered while PASS stays one line, and a failure flush emits them to stderr', () => {
  const stdout = [];
  const stderr = [];
  const report = createGateReporter({
    env: {},
    stdout: (line) => stdout.push(line),
    stderr: (line) => stderr.push(line),
  });

  report.detail('sentinel table line 1\nsentinel table line 2');
  report.pass('demo-gate', '2 sentinels checked; bundle=out/demo');

  assert.deepEqual({ stdout, stderr }, {
    stdout: ['PASS demo-gate: 2 sentinels checked; bundle=out/demo'],
    stderr: [],
  });
  report.flushDetails();
  assert.deepEqual(stderr, ['sentinel table line 1', 'sentinel table line 2']);
});

test('RF2_VERBOSE_TESTS=1 streams details immediately', () => {
  const stdout = [];
  const stderr = [];
  const report = createGateReporter({
    env: { RF2_VERBOSE_TESTS: '1' },
    stdout: (line) => stdout.push(line),
    stderr: (line) => stderr.push(line),
  });

  report.detail('verbose table line');
  report.flushDetails();
  report.pass('demo-gate', 'ok');

  assert.deepEqual({ stdout, stderr }, { stdout: ['verbose table line', 'PASS demo-gate: ok'], stderr: [] });
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
  console.error(`gate-report tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`gate-report tests: ${tests.length} passed.`);
