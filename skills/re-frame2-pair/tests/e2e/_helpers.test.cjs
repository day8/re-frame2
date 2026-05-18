#!/usr/bin/env node
'use strict';

const assert = require('assert/strict');
const {
  createDiagnostics,
  flushDiagnostics,
  isVerboseTests,
} = require('./_helpers.cjs');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

test('RF2_VERBOSE_TESTS=1 enables re-frame2-pair e2e verbose diagnostics', () => {
  assert.equal(isVerboseTests({ RF2_VERBOSE_TESTS: '1' }), true);
  assert.equal(isVerboseTests({ RF2_VERBOSE_TESTS: 'true' }), false);
  assert.equal(isVerboseTests({}), false);
});

test('diagnostics buffer preserves stream routing until flush', () => {
  const diagnostics = createDiagnostics({ verbose: false });
  diagnostics.add('[re-frame2-pair-e2e] fixture reachable');
  diagnostics.add('page exploded\nstack line', 'stderr');

  const stdout = [];
  const stderr = [];
  diagnostics.flush({
    stdout: (line) => stdout.push(line),
    stderr: (line) => stderr.push(line),
  });

  assert.deepEqual(stdout, ['[re-frame2-pair-e2e] fixture reachable']);
  assert.deepEqual(stderr, ['page exploded', 'stack line']);
});

test('failure flush prints quiet diagnostics to stderr only', () => {
  const diagnostics = createDiagnostics({ verbose: false });
  diagnostics.add('[re-frame2-pair-e2e] running dispatch-trace.e2e.cjs');

  const originalError = console.error;
  const stderr = [];
  console.error = (line) => stderr.push(line);
  try {
    flushDiagnostics(diagnostics);
  } finally {
    console.error = originalError;
  }

  assert.deepEqual(stderr, [
    '--- re-frame2-pair e2e diagnostics ---',
    '[re-frame2-pair-e2e] running dispatch-trace.e2e.cjs',
    '-----------------------------',
  ]);
});

test('verbose diagnostics are live-only and not flushed again', () => {
  const originalLog = console.log;
  const originalError = console.error;
  const stdout = [];
  const stderr = [];
  console.log = (line) => stdout.push(line);
  console.error = (line) => stderr.push(line);
  try {
    const diagnostics = createDiagnostics({ verbose: true });
    diagnostics.add('[re-frame2-pair-e2e] fixture reachable');
    diagnostics.add('page exploded', 'stderr');
    flushDiagnostics(diagnostics);
  } finally {
    console.log = originalLog;
    console.error = originalError;
  }

  assert.deepEqual(stdout, ['[re-frame2-pair-e2e] fixture reachable']);
  assert.deepEqual(stderr, ['page exploded']);
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
  console.error(`re-frame2-pair e2e helper tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`re-frame2-pair e2e helper tests: ${tests.length} passed.`);
