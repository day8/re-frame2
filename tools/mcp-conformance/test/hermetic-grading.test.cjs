// Entrypoint-level grading regression for the hermetic orchestrator
// (rf2-j538f7.19).
//
// The bug: the normal path emitted its GREEN/pass sentinel and exited 0
// BEFORE (and regardless of) the teardown outcome. A run whose six inner
// conformance tests all passed could still leak a Chromium or shadow-cljs JVM
// process and be certified green — "awaited and bounded" was only a TIMING
// property, never graded.
//
// The fix routes the settled cleanup report through `finalizeConformance`,
// which certifies GREEN (pass sentinel + exit 0) ONLY when the report proves
// every resource clean, and otherwise emits NO pass sentinel and returns
// orchestration exit 2. This test drives that REAL grading decision against
// clean vs dirty reports — no shadow-cljs / Chromium boot required, because
// `finalizeConformance` is a pure function of the report parameterised on its
// IO. `makeCleanup`'s own report GRADING (rejected close, never-exiting
// shadow, disconnection proof) is covered in `runner-cleanup.test.cjs`; this
// file owns the report → (sentinel, exit-code) mapping.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-re-frame2-pair-live-hermetic-suite.cjs',
);

const { finalizeConformance } = require(ORCH);

// Capturing IO so the test observes exactly what the entrypoint would emit,
// without any real console.log / process.exit / diagnostics flush.
function captureIo() {
  const io = {
    passLines: [],
    logLines: [],
    errLines: [],
    flushed: 0,
  };
  io.opts = {
    emitPass: (l) => io.passLines.push(l),
    log: (l) => io.logLines.push(l),
    logErr: (l) => io.errLines.push(l),
    flush: () => { io.flushed += 1; },
    count: 6,
  };
  return io;
}

test('CLEAN report ⇒ pass sentinel emitted exactly once and exit 0 (rf2-j538f7.19 AC7)', () => {
  const io = captureIo();
  const code = finalizeConformance(
    {
      clean: true,
      browser: { state: 'closed', clean: true },
      shadow: { state: 'exited', clean: true },
      issues: [],
    },
    io.opts,
  );

  assert.equal(code, 0, 'a proven-clean teardown is exit 0');
  assert.equal(io.passLines.length, 1, 'the pass sentinel is emitted EXACTLY once');
  assert.match(io.passLines[0], /live hermetic conformance passed/);
  assert.match(io.passLines[0], /6 inner tests/);
});

test('DIRTY report (browser dirty + shadow alive) ⇒ NO pass sentinel and exit 2 (rf2-j538f7.19 AC7)', () => {
  const io = captureIo();
  const code = finalizeConformance(
    {
      clean: false,
      browser: { state: 'dirty', clean: false, error: 'close rejected' },
      shadow: { state: 'alive', clean: false, signals: ['SIGTERM', 'SIGKILL'] },
      issues: ['browser: close rejected', 'shadow: no observed exit'],
    },
    io.opts,
  );

  assert.equal(code, 2, 'a teardown that could not be proven clean is orchestration exit 2, NOT 0');
  assert.equal(io.passLines.length, 0, 'NO pass/GREEN sentinel may be emitted for a dirty teardown');
  // The failure names the dirty resources so CI can see WHY it refused green.
  const err = io.errLines.join('\n');
  assert.match(err, /refusing to certify/);
  assert.match(err, /browser=dirty/);
  assert.match(err, /shadow=alive/);
  assert.equal(io.flushed, 1, 'diagnostics are flushed on the failure path');
});

test('DIRTY via browser only (shadow clean) still refuses green (rf2-j538f7.19 AC2)', () => {
  const io = captureIo();
  const code = finalizeConformance(
    {
      clean: false,
      browser: { state: 'dirty', clean: false },
      shadow: { state: 'exited', clean: true },
      issues: ['browser: still connected past cap'],
    },
    io.opts,
  );
  assert.equal(code, 2);
  assert.equal(io.passLines.length, 0);
});

test('missing/undefined report is treated as DIRTY: exit 2, no sentinel (rf2-j538f7.19)', () => {
  const io = captureIo();
  const code = finalizeConformance(undefined, io.opts);
  assert.equal(code, 2, 'no report at all cannot be certified green');
  assert.equal(io.passLines.length, 0);
});
