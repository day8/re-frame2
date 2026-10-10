// The hermetic orchestrator grades each inner test on the child's 'close'
// event, not 'exit': 'exit' can fire while stdout is still draining, and an
// inner test's success sentinel is its last write before `process.exit`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const path = require('node:path');

const { spawnAndGradeInnerTest, gradeInnerTestOutcome } = require(
  path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);

const SENTINEL = 'GREEN example-inner-test-sentinel';

// Grade a fake child that replays `events` one per tick: ['exit', code,
// signal], ['data', text] or ['close', code, signal].
function grade(events) {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  const replay = (i) => {
    if (i >= events.length) return;
    const [kind, ...args] = events[i];
    if (kind === 'data') child.stdout.emit('data', Buffer.from(args[0]));
    else child.emit(kind, ...args);
    setImmediate(() => replay(i + 1));
  };
  setImmediate(() => replay(0));
  return spawnAndGradeInnerTest({
    spawnFn: () => child,
    execPath: 'node',
    testPath: 'fake-inner-test.cjs',
    cwd: '.',
    env: {},
    testFile: 'fake-inner-test.cjs',
    onChunk: () => {},
    log: () => {},
  });
}

test('spawnAndGradeInnerTest resolves on close with the exit code and all stdout, including a chunk landing after exit (rf2-6girz0)', async () => {
  for (const [events, expected] of [
    [[['exit', 0, null], ['data', `${SENTINEL}\n`], ['close', 0, null]],
      { code: 0, stdoutText: `${SENTINEL}\n` }],
    [[['data', 'ran, but the gate itself failed\n'], ['exit', 1, null], ['close', 1, null]],
      { code: 1, stdoutText: 'ran, but the gate itself failed\n' }],
  ]) {
    assert.deepEqual(await grade(events), expected);
  }
});

test('gradeInnerTestOutcome: an exit-0 SKIP, a missing sentinel or a sentinel-less row fails with exit 2; a non-zero exit keeps its code; only the sentinel passes (rf2-3x7nj.36.1)', () => {
  const verdict = (code, stdoutText, sentinel = SENTINEL) => () =>
    gradeInnerTestOutcome({ code, stdoutText, sentinel, testFile: 'fake-inner-test.cjs' });
  const orchestration = (pattern) => (err) => err.exitCode === 2 && pattern.test(err.message);

  // A SKIP banner fails even when the sentinel is also present.
  assert.throws(verdict(0, `SKIP no $SHADOW_CLJS_NREPL_PORT\n${SENTINEL}\n`), orchestration(/SKIPped inside/));
  assert.throws(verdict(0, `booting\nSKIP no $SHADOW_CLJS_NREPL_PORT\n${SENTINEL}\n`), orchestration(/SKIPped inside/));
  assert.throws(verdict(0, 'exited clean but forgot to print the sentinel\n'), orchestration(/did NOT print its success sentinel/));
  assert.throws(verdict(0, 'anything\n', null), orchestration(/has no success sentinel/));
  assert.throws(verdict(1, `${SENTINEL}\n`), (err) => err.exitCode === 1 && /exited 1/.test(err.message));
  // "SKIP" inside a line is not a banner.
  assert.doesNotThrow(verdict(0, `ran; nothing to SKIP here\n${SENTINEL}\n`));
});

test('spawnAndGradeInnerTest rejects a signal-killed child (code === null)', async () => {
  await assert.rejects(
    grade([['exit', null, 'SIGKILL'], ['close', null, 'SIGKILL']]),
    /killed by SIGKILL/,
  );
});
