// Regression test for the hermetic live-suite's INNER_TESTS grading event
// (rf2-6girz0).
//
// Uses Node's built-in `node:test` (same posture as
// `runner-cleanup.test.cjs` / `runner-watchdog.test.cjs` — no extra
// dev-dependency). Runs in-process: it requires the orchestrator AS A
// MODULE (the auto-run is guarded behind `require.main === module`, so
// requiring it does NOT boot shadow-cljs / Chromium) and drives the
// exported `spawnAndGradeInnerTest` factory against a FAKE `spawnFn` — no
// real child process, no real stdio pipe.
//
// ## The bug this pins
//
// The hermetic orchestrator's INNER_TESTS loop used to grade each spawned
// inner test on the child's 'exit' event, then read the stdout it had
// captured so far to check for the test's GREEN sentinel. Node's own docs
// note that 'exit' fires as soon as the child process itself terminates,
// which can race the stdio pipes still draining into the parent's 'data'
// handlers — 'close' is the event Node guarantees fires only once
// stdout/stderr are fully read. Every INNER_TESTS entry hits the worst
// case: `_runner.cjs`'s success path is `console.log(sentinel)`
// immediately followed by `process.exit(exitCode)` — the sentinel is the
// very last write before the process tears down, a write-then-exit shape
// that is worse on Windows (where `process.exit()` can truncate
// not-yet-flushed pipe writes). Grading on 'exit' meant a genuinely
// conformant, fully-passing inner gate could have its final
// sentinel-bearing stdout chunk arrive AFTER grading already ran against a
// truncated `stdoutText` — scoring a PASSING gate as FAILED and blocking
// merge for a server that actually passed.
//
// FIX: grade on 'close' instead of 'exit' (same `(code, signal)` payload,
// fires only after stdio is fully drained).
//
// ## What this test drives
//
// It requires the orchestrator as a module and exercises the exported
// `spawnAndGradeInnerTest` factory against fakes:
//
//   1. A fake child that emits its sentinel-bearing stdout chunk AFTER
//      'exit' but BEFORE 'close' — the exact race rf2-6girz0 describes.
//      Proves the graded `stdoutText` contains the late-arriving sentinel
//      (would have been truncated under the old 'exit'-based grading).
//   2. A fake child that emits 'exit' alone (no 'close' yet) — proves the
//      grading promise does NOT resolve on 'exit' alone; it only resolves
//      once 'close' fires.
//   3. A fake child that exits non-zero — proves a genuinely failing
//      child's exit code is still surfaced faithfully after the fix.
//   4. A fake child that exits 0 but never prints the sentinel (a
//      "silently non-conformant" child, as opposed to a merely
//      late-flushing one) — graded, then handed to the REAL verdict
//      `main()` applies (`gradeInnerTestOutcome`), which must fail it as an
//      orchestration failure (exit 2). The close-grading fix reads MORE of a
//      child's stdout, but does not turn the sentinel gate into a rubber
//      stamp.
//   5. A fake child killed by a signal (code === null) — proves the
//      reject-on-signal contract is unchanged by the close/exit swap.
//   6. The verdict itself, RED and GREEN (rf2-3x7nj.36.1): a SKIP banner at
//      the start of stdout and after a newline, an exit 0 without the
//      sentinel and a row with no sentinel all fail with exit 2; a non-zero
//      exit keeps the inner code; only an exit 0 with the sentinel passes.
//      Case 4 used to assert only that its own fake child had not printed
//      the sentinel, so deleting the guards left this file green.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-re-frame2-pair-live-hermetic-suite.cjs',
);

const { spawnAndGradeInnerTest, gradeInnerTestOutcome } = require(ORCH);

const SENTINEL = 'GREEN example-inner-test-sentinel';

// A fake child_process.ChildProcess: an EventEmitter standing in for the
// process itself, with `.stdout` / `.stderr` EventEmitters standing in for
// the piped streams `spawnAndGradeInnerTest` attaches 'data' handlers to.
function makeFakeChild() {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  return child;
}

function grade(spawnFn, onChunk) {
  return spawnAndGradeInnerTest({
    spawnFn,
    execPath: 'node',
    testPath: 'fake-inner-test.cjs',
    cwd: '.',
    env: {},
    testFile: 'fake-inner-test.cjs',
    onChunk: onChunk || (() => {}),
    log: () => {},
  });
}

test('spawnAndGradeInnerTest reads a late-arriving sentinel chunk that lands AFTER exit but BEFORE close (rf2-6girz0)', async () => {
  const child = makeFakeChild();
  let exitFiredAt = null;
  let dataFiredAt = null;

  const spawnFn = () => {
    // Model `_runner.cjs`'s write-then-exit success path under the
    // worst-case pipe-drain race: 'exit' fires, THEN the sentinel-bearing
    // stdout chunk arrives, THEN 'close' fires once stdio is fully read.
    setImmediate(() => {
      exitFiredAt = Date.now();
      child.emit('exit', 0, null);
      setImmediate(() => {
        dataFiredAt = Date.now();
        child.stdout.emit('data', Buffer.from(`${SENTINEL}\n`));
        setImmediate(() => child.emit('close', 0, null));
      });
    });
    return child;
  };

  const result = await grade(spawnFn);

  assert.ok(
    dataFiredAt >= exitFiredAt,
    'test fixture invariant broken: the sentinel chunk must arrive at/after ' +
      "exit to model the race — otherwise this test doesn't exercise the bug",
  );
  assert.equal(result.code, 0);
  assert.ok(
    result.stdoutText.includes(SENTINEL),
    'grading on close should have captured the late-arriving sentinel chunk; ' +
      'stdoutText was: ' + JSON.stringify(result.stdoutText),
  );
});

test('spawnAndGradeInnerTest does NOT resolve on exit alone — it waits for close', async () => {
  const child = makeFakeChild();
  let resolved = false;

  const spawnFn = () => {
    setImmediate(() => child.emit('exit', 0, null));
    return child;
  };

  const p = grade(spawnFn).then((result) => {
    resolved = true;
    return result;
  });

  // Give 'exit' plenty of room to fire and (if grading were still keyed to
  // 'exit') resolve the promise.
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(
    resolved,
    false,
    "grading resolved on 'exit' alone, before 'close' fired — this is the " +
      'exact pre-fix behaviour rf2-6girz0 reports',
  );

  child.emit('close', 0, null);
  const result = await p;
  assert.equal(resolved, true);
  assert.equal(result.code, 0);
});

test('spawnAndGradeInnerTest still fails a genuinely non-conformant child that exits non-zero', async () => {
  const child = makeFakeChild();

  const spawnFn = () => {
    setImmediate(() => {
      child.stdout.emit('data', Buffer.from('ran, but the gate itself failed\n'));
      child.emit('exit', 1, null);
      setImmediate(() => child.emit('close', 1, null));
    });
    return child;
  };

  const result = await grade(spawnFn);

  assert.equal(
    result.code,
    1,
    'a genuinely failing inner test must still surface its non-zero exit ' +
      'code after the close-grading fix',
  );
});

test('spawnAndGradeInnerTest still surfaces a sentinel-less stdout for a silently non-conformant child (exit 0, sentinel never printed)', async () => {
  const child = makeFakeChild();

  const spawnFn = () => {
    setImmediate(() => {
      child.stdout.emit('data', Buffer.from('exited clean but forgot to print the sentinel\n'));
      child.emit('exit', 0, null);
      setImmediate(() => child.emit('close', 0, null));
    });
    return child;
  };

  const result = await grade(spawnFn);

  assert.equal(result.code, 0);
  // Hand the graded outcome to the verdict `main()` applies: the
  // close-grading fix reads MORE of a child's stdout than the old
  // exit-based grading, but it must not turn the sentinel check into a
  // rubber stamp — a child that never actually prints its sentinel, even
  // once its stdio is fully drained, still fails.
  assert.throws(
    () => gradeInnerTestOutcome({ ...result, sentinel: SENTINEL, testFile: 'fake-inner-test.cjs' }),
    (err) => err.exitCode === 2 && /did NOT print its success sentinel/.test(err.message),
  );
});

test('gradeInnerTestOutcome: an exit-0 SKIP, a missing sentinel or a sentinel-less row fails with exit 2; a non-zero exit keeps its code; only the sentinel passes (rf2-3x7nj.36.1)', () => {
  const verdict = (code, stdoutText, sentinel = SENTINEL) => () =>
    gradeInnerTestOutcome({ code, stdoutText, sentinel, testFile: 'fake-inner-test.cjs' });
  const orchestration = (pattern) => (err) => err.exitCode === 2 && pattern.test(err.message);

  // RED: a SKIP banner fails even when the sentinel is also present.
  assert.throws(verdict(0, `SKIP no $SHADOW_CLJS_NREPL_PORT\n${SENTINEL}\n`), orchestration(/SKIPped inside/));
  assert.throws(verdict(0, `booting\nSKIP no $SHADOW_CLJS_NREPL_PORT\n${SENTINEL}\n`), orchestration(/SKIPped inside/));
  // RED: exit 0 without the sentinel.
  assert.throws(verdict(0, 'exited clean but forgot to print the sentinel\n'), orchestration(/did NOT print its success sentinel/));
  // RED: an inventory row with no sentinel cannot pass on exit 0 alone.
  assert.throws(verdict(0, 'anything\n', null), orchestration(/has no success sentinel/));
  // RED: a non-zero exit keeps the inner test's own code.
  assert.throws(verdict(1, `${SENTINEL}\n`), (err) => err.exitCode === 1 && /exited 1/.test(err.message));
  // GREEN: exit 0 with the sentinel and no SKIP banner. "SKIP" inside a line
  // is not a banner.
  assert.doesNotThrow(verdict(0, `ran; nothing to SKIP here\n${SENTINEL}\n`));
});

test('spawnAndGradeInnerTest still rejects a signal-killed child (code === null) after the close-grading fix', async () => {
  const child = makeFakeChild();

  const spawnFn = () => {
    setImmediate(() => {
      child.emit('exit', null, 'SIGKILL');
      setImmediate(() => child.emit('close', null, 'SIGKILL'));
    });
    return child;
  };

  await assert.rejects(() => grade(spawnFn), /killed by SIGKILL/);
});
