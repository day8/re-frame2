// Regression test for the hermetic orchestrator's ASYNC teardown contract.
//
// Uses Node's built-in `node:test` (same posture as
// `runner-watchdog.test.cjs` / `hermetic-setup-timeout.test.cjs` — no extra
// dev-dependency). Runs in-process: `makeCleanup` is a pure factory with no
// `process.exit`, so unlike the watchdog harness it does NOT need a child.
//
// ## The contract this pins
//
// `cleanup` in `scripts/run-re-frame2-pair-live-hermetic-suite.cjs` is
// ASYNC. `makeCleanup` returns an idempotent promise that AWAITS the
// browser close (bounded) and the shadow SIGTERM→exit, escalating to a
// SIGKILL it then ALSO awaits. Every caller — the `finally` path, the
// SIGINT/SIGTERM handlers, and the hard watchdog — `await`s it (the
// `finally` path) or races it against a hard cap (signal / watchdog paths)
// BEFORE `process.exit`. That ordering is the contract this guards:
//   - Playwright's promise-returning `browser.close()` is awaited, so it
//     settles before the process exits rather than being abandoned in
//     flight.
//   - The shadow SIGKILL fallback is awaited too, so a shadow-cljs JVM
//     that ignores SIGTERM is actually SIGKILL'd by us rather than left to
//     an unref'd timer that a synchronous `process.exit` would abandon.
//
// ## What this test drives
//
// It requires the orchestrator AS A MODULE (the auto-run is guarded behind
// `require.main === module`, so requiring it does NOT boot shadow-cljs /
// Chromium) and exercises the exported `makeCleanup` factory against fakes:
//
//   1. A fake browser whose `close()` is a promise that resolves only after
//      a delay — the test proves cleanup did not resolve until AFTER that
//      close settled (i.e. the close was awaited, not fire-and-forgotten).
//   2. A fake shadow child that ignores SIGTERM and only "exits" after a
//      delay following the SIGKILL — the test proves cleanup escalated to
//      SIGKILL and awaited the eventual exit.
//   3. A fake browser whose `close()` NEVER settles — the test proves
//      cleanup is hard-capped by `browserCloseMs` (it still completes
//      rather than hanging), exercising the "bounded, not abandoned" seam.
//   4. Idempotency: two concurrent `cleanup()` calls return the SAME
//      in-flight promise and SIGTERM is sent exactly once.

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

const { makeCleanup, settledWithin, waitForChildExit } = require(ORCH);

// A fake shadow-cljs child: an EventEmitter with a `kill(sig)` that records
// every signal. `exitAfterKill` lets the test model a JVM that ignores
// SIGTERM and only dies on SIGKILL (after a small delay).
function makeFakeShadow({ exitOnTermMs = null, exitOnKillMs = null } = {}) {
  const ee = new EventEmitter();
  ee.killSignals = [];
  ee.exited = false;
  ee.kill = (sig) => {
    ee.killSignals.push(sig);
    if (sig === 'SIGTERM' && exitOnTermMs !== null) {
      setTimeout(() => { ee.exited = true; ee.emit('exit', null, 'SIGTERM'); }, exitOnTermMs);
    }
    if (sig === 'SIGKILL' && exitOnKillMs !== null) {
      setTimeout(() => { ee.exited = true; ee.emit('exit', null, 'SIGKILL'); }, exitOnKillMs);
    }
    return true;
  };
  return ee;
}

test('makeCleanup AWAITS a slow promise-returning browser.close() (rf2-7ckmwx finding 1)', async () => {
  let closeStarted = false;
  let closeSettled = false;
  const browser = {
    close: () => {
      closeStarted = true;
      return new Promise((resolve) => {
        setTimeout(() => { closeSettled = true; resolve(); }, 150);
      });
    },
  };
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
    // Generous caps — we are testing the WAIT, not the timeout.
    browserCloseMs: 5000,
  });

  await cleanup();

  assert.ok(closeStarted, 'browser.close() was never called by cleanup');
  // THE load-bearing assertion: cleanup did not resolve until the
  // promise-returning close had SETTLED. A synchronous, un-awaited cleanup
  // would have returned before this flag flipped.
  assert.ok(
    closeSettled,
    'cleanup resolved BEFORE browser.close() settled — the promise-returning ' +
      'close was not awaited (the rf2-7ckmwx finding-1 fire-and-forget bug).',
  );
});

test('makeCleanup escalates SIGTERM→SIGKILL and AWAITS the eventual exit (rf2-7ckmwx finding 1)', async () => {
  // Shadow ignores SIGTERM (exitOnTermMs null) and only dies 80ms after
  // SIGKILL. A short SIGTERM grace forces the escalation quickly.
  const shadow = makeFakeShadow({ exitOnKillMs: 80 });
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    log: () => {},
    logErr: () => {},
    shadowTermGraceMs: 50,
    shadowKillGraceMs: 5000,
  });

  await cleanup();

  // SIGTERM sent, then escalated to SIGKILL (SIGTERM was ignored).
  assert.deepEqual(
    shadow.killSignals,
    ['SIGTERM', 'SIGKILL'],
    'cleanup did not escalate to SIGKILL after SIGTERM was ignored; ' +
      'signals seen: ' + JSON.stringify(shadow.killSignals),
  );
  // THE load-bearing assertion: cleanup awaited the post-SIGKILL exit.
  // An unref'd fire-and-forget SIGKILL timer would instead be abandoned by
  // the immediate process.exit.
  assert.ok(
    shadow.exited,
    'cleanup resolved before the shadow child exited after SIGKILL — the ' +
      'post-kill exit was not awaited (rf2-7ckmwx finding-1 abandon).',
  );
});

test('makeCleanup HARD-CAPS a never-settling browser.close() instead of hanging (rf2-7ckmwx finding 1)', async () => {
  // A close that NEVER settles. Cleanup must still complete, bounded by
  // browserCloseMs — proving the await is bounded, not an unbounded hang.
  const browser = { close: () => new Promise(() => {}) };
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
    browserCloseMs: 120,
  });

  const start = Date.now();
  await cleanup();
  const elapsed = Date.now() - start;

  assert.ok(
    elapsed >= 100,
    'cleanup returned in ' + elapsed + 'ms — it did not actually wait for ' +
      'the browser-close cap, so the bounded-wait seam is not exercised.',
  );
  assert.ok(
    elapsed < 5000,
    'cleanup took ' + elapsed + 'ms on a never-settling browser.close() — it ' +
      'is NOT hard-capped by browserCloseMs and would hang the teardown.',
  );
});

test('makeCleanup is idempotent: concurrent calls share one in-flight promise (rf2-7ckmwx finding 1)', async () => {
  const shadow = makeFakeShadow({ exitOnTermMs: 40 });
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    log: () => {},
    logErr: () => {},
    shadowTermGraceMs: 5000,
  });

  const p1 = cleanup();
  const p2 = cleanup();
  // Same in-flight promise — a signal arriving during the finally teardown
  // joins it rather than racing a second SIGTERM.
  assert.equal(p1, p2, 'concurrent cleanup() calls returned different promises');

  await Promise.all([p1, p2]);

  assert.deepEqual(
    shadow.killSignals,
    ['SIGTERM'],
    'idempotent cleanup sent SIGTERM more than once: ' +
      JSON.stringify(shadow.killSignals),
  );
});

test('settledWithin: true when the promise settles first, false when the cap wins (rf2-7ckmwx finding 1)', async () => {
  const fast = settledWithin(new Promise((r) => setTimeout(r, 10)), 5000);
  assert.equal(await fast, true, 'a fast-settling promise should report settled=true');

  const slow = settledWithin(new Promise(() => {}), 30);
  assert.equal(await slow, false, 'a never-settling promise should report settled=false at the cap');

  // A REJECTED promise still counts as "settled" — we waited for it, which
  // is the contract (teardown steps that reject are tried, not abandoned).
  const rejects = settledWithin(Promise.reject(new Error('x')), 5000);
  assert.equal(await rejects, true, 'a rejected promise should report settled=true (we waited for it)');
});

test('waitForChildExit resolves immediately when the child already exited (rf2-7ckmwx finding 1)', async () => {
  const shadow = makeFakeShadow();
  // Already-exited child: must resolve without needing an `exit` event.
  await waitForChildExit(shadow, () => true);
  // And it resolves on a real exit event when not already exited.
  const ee = makeFakeShadow();
  const p = waitForChildExit(ee, () => false);
  setTimeout(() => ee.emit('exit', 0, null), 10);
  await p;
});
