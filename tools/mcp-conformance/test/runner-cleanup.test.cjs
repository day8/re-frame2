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

const {
  makeCleanup,
  settledWithin,
  waitForChildExit,
  finalizeConformance,
  makeShadowTreeReaper,
  ownedDescendants,
} = require(ORCH);

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

// ---------------------------------------------------------------------------
// rf2-j538f7.19: the teardown must be GRADED, not merely awaited. A bounded
// wait that cannot prove the children were reaped is a DIRTY teardown that the
// normal path must refuse to certify green — the pre-fix `cleanup()` resolved
// to `undefined` (no grading) so a leaked browser / shadow JVM was silently
// blessed by the final `process.exit(0)`.
// ---------------------------------------------------------------------------

test('makeCleanup GRADES a rejected browser.close() + never-exiting shadow as DIRTY, after attempting BOTH (rf2-j538f7.19 AC1/AC3/AC6)', async () => {
  // Browser close rejects and the browser has NO isConnected() — disconnection
  // cannot be proven. Shadow ignores every signal and never emits exit. The
  // OLD orchestrator resolved cleanup as successful (undefined) and certified
  // this run GREEN; the fixed factory must record BOTH failures.
  const closeCalls = [];
  const browser = {
    close: () => { closeCalls.push('close'); return Promise.reject(new Error('close failed')); },
    // no isConnected() ⇒ disconnection NOT provable
  };
  const shadow = makeFakeShadow(); // never exits on any signal
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    log: () => {},
    logErr: () => {},
    browserCloseMs: 40,
    shadowTermGraceMs: 30,
    shadowKillGraceMs: 30,
  });

  const report = await cleanup();

  // THE red-then-green teeth: pre-fix, `report` was `undefined`, so reading
  // `.clean` here throws — the OLD orchestrator had NO gradeable outcome.
  assert.equal(report.clean, false, 'a rejected close + never-exiting shadow must be graded DIRTY');
  // BOTH steps were attempted before the failure was surfaced (AC1: all other
  // resources are still cleaned before the failure is surfaced).
  assert.deepEqual(closeCalls, ['close'], 'browser.close() must still be attempted');
  assert.deepEqual(
    shadow.killSignals,
    ['SIGTERM', 'SIGKILL'],
    'shadow teardown must still escalate SIGTERM→SIGKILL despite the browser failure',
  );
  // The report names both dirty resources with structured issues.
  assert.equal(report.browser.state, 'dirty');
  assert.equal(report.shadow.state, 'alive');
  assert.equal(report.issues.length, 2, 'both failures recorded: ' + JSON.stringify(report.issues));
});

test('makeCleanup treats a rejected browser.close() as CLEAN when isConnected() proves disconnection (rf2-j538f7.19 AC1)', async () => {
  // A close rejection is acceptable ONLY if disconnection can be independently
  // proven. isConnected() === false is that proof.
  const browser = {
    close: () => Promise.reject(new Error('transport already closed')),
    isConnected: () => false,
  };
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
    browserCloseMs: 100,
  });
  const report = await cleanup();
  assert.equal(report.clean, true, 'a reject is tolerable when isConnected()===false proves the browser is gone');
  assert.equal(report.browser.state, 'disconnected');
});

test('makeCleanup grades a browser close that exceeds its cap + stays connected as DIRTY (rf2-j538f7.19 AC2)', async () => {
  // Never-settling close, and isConnected() still reports true — the browser
  // is provably STILL connected past the cap ⇒ dirty, not a green pass-through.
  const browser = {
    close: () => new Promise(() => {}),
    isConnected: () => true,
  };
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
    browserCloseMs: 60,
  });
  const report = await cleanup();
  assert.equal(report.clean, false, 'a close that exceeds its cap while still connected is DIRTY');
  assert.equal(report.browser.state, 'dirty');
});

test('makeCleanup grades a happy teardown (resolved close + already-exited shadow) as CLEAN (rf2-j538f7.19 AC4)', async () => {
  const browser = { close: () => Promise.resolve() };
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
  });
  const report = await cleanup();
  assert.equal(report.clean, true);
  assert.equal(report.browser.state, 'closed');
  assert.equal(report.shadow.state, 'exited');
});

test('makeCleanup grades a SIGTERM-exit shadow as CLEAN with the observed exit (rf2-j538f7.19 AC4)', async () => {
  const shadow = makeFakeShadow({ exitOnTermMs: 20 });
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    log: () => {},
    logErr: () => {},
    shadowTermGraceMs: 500,
  });
  const report = await cleanup();
  assert.equal(report.clean, true);
  assert.equal(report.shadow.state, 'exited');
  assert.deepEqual(report.shadow.signals, ['SIGTERM'], 'a child that exits on SIGTERM is never escalated to SIGKILL');
});

test('concurrent cleanup() callers observe the SAME graded report — no split success/failure (rf2-j538f7.19 AC5)', async () => {
  const browser = { close: () => Promise.reject(new Error('x')) }; // dirty (no isConnected)
  const shadow = makeFakeShadow(); // never exits ⇒ dirty
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    log: () => {},
    logErr: () => {},
    browserCloseMs: 30,
    shadowTermGraceMs: 20,
    shadowKillGraceMs: 20,
  });
  const [r1, r2] = await Promise.all([cleanup(), cleanup()]);
  assert.equal(r1, r2, 'concurrent callers must receive the identical report object');
  assert.equal(r1.clean, false, 'and both observe the same DIRTY verdict');
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

// ---------------------------------------------------------------------------
// rf2-kzbf: the teardown must grade the PROCESS TREE WE SPAWNED, not the npx
// wrapper that fronts it.
//
// On Windows cross-spawn 7.0.6 rewrites the trusted absolute `npx` into
// `cmd.exe /d /s /c "...npx.CMD shadow-cljs watch app"`, so the handle the
// runner holds — and whose `exit` event sets `shadowExited` — is the COMMAND
// WRAPPER. shadow-cljs and its JVM are its DESCENDANTS and outlive it.
//
// Every test above models wrapper and JVM as ONE EventEmitter
// (`makeFakeShadow().kill()` emits that same object's `exit`), so they pin
// direct-child timing and escalation but CANNOT see a wrapper that exits while
// a grandchild survives. Measured against the real code before this fix: a
// cross-spawn'd `.cmd` wrapper emitted `exit` code=0 with its grandchild still
// alive, `report.clean` came back `true`, `report.issues` was `[]`, and
// `finalizeConformance` emitted the pass sentinel and returned 0 — a certified
// GREEN hermetic run holding a live process. These pin that shut.
//
// The seam is platform-neutral by construction: `makeCleanup` knows no PIDs and
// takes `reapShadowTree` as a dependency, so the grading tests below run
// identically on Windows, macOS and Linux. Only the DEFAULT reaper built at the
// spawn site is platform-conditional, and only the last test in this file — the
// one that launches a real wrapper — is Windows-gated.
// ---------------------------------------------------------------------------

// A reap report whose survivors/error the test dictates.
function fakeReap({ supported = true, owned = [], survivors = [], error = null } = {}) {
  return async () => ({ supported, owned, survivors, error });
}

test('a wrapper that EXITED cannot certify clean while owned descendants survive (rf2-kzbf AC1)', async () => {
  // The exact false-green shape: `hasShadowExited()` is TRUE — the npx/cmd
  // wrapper is genuinely gone — but the shadow-cljs JVM it launched is still
  // running and still holding the fixture's port.
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => null,
    hasShadowExited: () => true,
    reapShadowTree: fakeReap({ owned: [4242, 4243], survivors: [4243] }),
    log: () => {},
    logErr: () => {},
  });

  const report = await cleanup();

  // The wrapper still grades 'exited' — that observation was never wrong, it
  // was just never sufficient.
  assert.equal(report.shadow.state, 'exited', 'the wrapper did exit and should still be reported so');
  // THE load-bearing assertion. Pre-fix this was `true`: `makeCleanup` graded
  // shadow solely from `hasShadowExited()` and ignored the descendants entirely.
  assert.equal(
    report.shadow.clean,
    false,
    'a surviving owned descendant must make the shadow teardown DIRTY even ' +
      'though the npx/cmd wrapper reported exit (the rf2-kzbf false green)',
  );
  assert.equal(report.clean, false, 'the overall teardown must be DIRTY');
  assert.deepEqual(report.shadow.tree.survivors, [4243]);
  // The issue names the surviving pid so an operator can act on it.
  assert.equal(report.issues.length, 1, 'issues: ' + JSON.stringify(report.issues));
  assert.match(report.issues[0], /4243/, 'the issue must name the surviving pid');

  // AC1 + AC3 end-to-end: no pass sentinel, orchestration exit 2.
  let sentinel = null;
  const code = finalizeConformance(report, {
    emitPass: (line) => { sentinel = line; },
    log: () => {},
    logErr: () => {},
    flush: () => {},
    count: 0,
  });
  assert.equal(code, 2, 'a leaked owned descendant must be an orchestration failure');
  assert.equal(sentinel, null, 'a run holding a live spawned process must emit NO pass sentinel');
});

test('an owned-tree reap that cannot be PROVEN is DIRTY, not optimistically clean (rf2-kzbf AC3)', async () => {
  // Enumeration failed, so we cannot say whether anything survived. "We could
  // not check" must never grade the same as "we checked and it was empty".
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => null,
    hasShadowExited: () => true,
    reapShadowTree: fakeReap({ error: 'could not enumerate the process table (EPERM)' }),
    log: () => {},
    logErr: () => {},
  });
  const report = await cleanup();
  assert.equal(report.clean, false, 'an unprovable teardown must be graded DIRTY');
  assert.equal(report.shadow.clean, false);
  assert.match(report.issues[0], /could NOT be proven/);
});

test('the owned-tree reap runs even when the wrapper never exited, and both failures are reported (rf2-kzbf AC2)', async () => {
  // A wrapper that ignores every signal AND a surviving descendant: the
  // teardown must attempt and report BOTH rather than short-circuiting.
  const shadow = makeFakeShadow(); // never exits
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => shadow,
    hasShadowExited: () => shadow.exited,
    reapShadowTree: fakeReap({ owned: [7001], survivors: [7001] }),
    log: () => {},
    logErr: () => {},
    shadowTermGraceMs: 20,
    shadowKillGraceMs: 20,
  });
  const report = await cleanup();
  assert.equal(report.shadow.state, 'alive');
  assert.deepEqual(shadow.killSignals, ['SIGTERM', 'SIGKILL'], 'signal escalation still runs');
  assert.equal(report.clean, false);
  assert.equal(report.issues.length, 2, 'both the wrapper and the tree are reported: ' + JSON.stringify(report.issues));
});

test('a reaped tree with no survivors still grades CLEAN (rf2-kzbf AC4 — no false RED)', async () => {
  // The repair must not invert into refusing every run: an owned tree that was
  // actually reaped is clean, and that is the normal path.
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => null,
    hasShadowExited: () => true,
    reapShadowTree: fakeReap({ owned: [900, 901], survivors: [] }),
    log: () => {},
    logErr: () => {},
  });
  const report = await cleanup();
  assert.equal(report.clean, true, 'a tree with no survivors is clean');
  assert.deepEqual(report.issues, []);
  assert.deepEqual(report.shadow.tree.owned, [900, 901]);
});

// ---- the descendant walk itself ------------------------------------------

test('ownedDescendants walks the whole subtree, not just direct children (rf2-kzbf)', () => {
  // The real shape: runner -> cmd.exe(100) -> npx node(200) -> shadow node(300)
  // -> java(400). Grading the wrapper alone sees none of 200/300/400.
  const table = [
    { pid: 1, ppid: 0, createdMs: 1000 },
    { pid: 100, ppid: 1, createdMs: 5000 },
    { pid: 200, ppid: 100, createdMs: 5100 },
    { pid: 300, ppid: 200, createdMs: 5200 },
    { pid: 400, ppid: 300, createdMs: 5300 },
    { pid: 999, ppid: 1, createdMs: 5100 }, // an unrelated peer process
  ];
  const owned = ownedDescendants(table, 100, 4000);
  assert.deepEqual(owned.sort((a, b) => a - b), [100, 200, 300, 400]);
  assert.ok(!owned.includes(999), 'an unrelated sibling process must never be attributed to us');
});

test('ownedDescendants refuses a RECYCLED pid that predates our spawn (rf2-kzbf AC2)', () => {
  // Windows recycles PIDs. A process that already existed when we spawned
  // cannot be our descendant, however its parent link now reads — and killing
  // it would be exactly the "unrelated JVM" the fence forbids.
  const table = [
    { pid: 100, ppid: 1, createdMs: 5000 },
    { pid: 500, ppid: 100, createdMs: 4000 }, // created BEFORE we spawned
    { pid: 600, ppid: 100, createdMs: 5500 }, // genuinely ours
  ];
  const owned = ownedDescendants(table, 100, 4500);
  assert.ok(owned.includes(600), 'a descendant created after our spawn is ours');
  assert.ok(!owned.includes(500), 'a process predating our spawn must NOT be attributed to us');
});

test('ownedDescendants reports an empty set when the root is already gone and left nothing (rf2-kzbf)', () => {
  const table = [{ pid: 1, ppid: 0, createdMs: 1000 }];
  assert.deepEqual(ownedDescendants(table, 100, 4000), []);
});

// ---- the reaper's own outcome grading -------------------------------------

test('makeShadowTreeReaper reports SURVIVORS when the kill removes nothing (rf2-kzbf)', async () => {
  // A tree-kill that returns success while removing nothing is precisely the
  // failure mode this bead is about. The reaper must grade the EFFECT — is the
  // pid still alive — never the fact that the kill call returned.
  const killed = [];
  const reap = makeShadowTreeReaper({
    rootPid: 100,
    spawnedAtMs: 0,
    platform: 'win32',
    readTable: () => [
      { pid: 100, ppid: 1, createdMs: 10 },
      { pid: 200, ppid: 100, createdMs: 20 },
    ],
    treeKill: (pid) => { killed.push(pid); /* silently removes nothing */ },
    isAlive: () => true, // still there afterwards
    graceMs: 60,
    pollMs: 10,
    log: () => {},
    logErr: () => {},
  });
  const out = await reap();
  assert.ok(killed.includes(100), 'the owned root must be tree-killed');
  assert.deepEqual(out.survivors.sort((a, b) => a - b), [100, 200], 'survivors must be reported, not assumed dead');
  assert.equal(out.error, null);
});

test('makeShadowTreeReaper reports NO survivors once the pids actually die (rf2-kzbf)', async () => {
  const dead = new Set();
  const reap = makeShadowTreeReaper({
    rootPid: 100,
    spawnedAtMs: 0,
    platform: 'win32',
    readTable: () => [
      { pid: 100, ppid: 1, createdMs: 10 },
      { pid: 200, ppid: 100, createdMs: 20 },
    ],
    treeKill: (pid) => { dead.add(pid); dead.add(200); },
    isAlive: (pid) => !dead.has(pid),
    graceMs: 500,
    pollMs: 10,
    log: () => {},
    logErr: () => {},
  });
  const out = await reap();
  assert.deepEqual(out.survivors, []);
  assert.deepEqual(out.owned.sort((a, b) => a - b), [100, 200]);
});

test('makeShadowTreeReaper surfaces an enumeration failure instead of reporting clean (rf2-kzbf AC3)', async () => {
  const reap = makeShadowTreeReaper({
    rootPid: 100,
    spawnedAtMs: 0,
    platform: 'win32',
    readTable: () => { throw new Error('powershell unavailable'); },
    treeKill: () => {},
    log: () => {},
    logErr: () => {},
  });
  const out = await reap();
  assert.match(out.error, /could not enumerate the process table/);
  assert.match(out.error, /powershell unavailable/);
});

test('makeShadowTreeReaper is INERT on POSIX — current behaviour is unchanged there (rf2-kzbf)', async () => {
  // POSIX `npx` is exec'd directly rather than behind a cmd.exe shim, so no
  // equivalent defect was demonstrated and the repair must not change grading
  // for the maintainers running macOS/Linux.
  for (const platform of ['linux', 'darwin']) {
    const reap = makeShadowTreeReaper({
      rootPid: 100,
      spawnedAtMs: 0,
      platform,
      readTable: () => { throw new Error('must never be consulted off Windows'); },
      treeKill: () => { throw new Error('must never kill off Windows'); },
    });
    const out = await reap();
    assert.equal(out.supported, false, platform + ': the reaper must be inert');
    assert.deepEqual(out.survivors, [], platform + ': no survivors are claimed');
    assert.equal(out.error, null, platform + ': and no failure is invented');
  }
  // And an inert reap leaves the grading exactly as it was before this fix.
  const cleanup = makeCleanup({
    getBrowser: () => null,
    getShadow: () => null,
    hasShadowExited: () => true,
    reapShadowTree: makeShadowTreeReaper({ rootPid: 1, spawnedAtMs: 0, platform: 'linux' }),
    log: () => {},
    logErr: () => {},
  });
  const report = await cleanup();
  assert.equal(report.clean, true);
  assert.equal(report.shadow.state, 'exited');
});

test('makeShadowTreeReaper refuses to claim a reap when no root pid was recorded (rf2-kzbf AC3)', async () => {
  const reap = makeShadowTreeReaper({ rootPid: undefined, spawnedAtMs: 0, platform: 'win32' });
  const out = await reap();
  assert.equal(out.supported, true);
  assert.match(out.error, /no shadow root pid/);
});

// ---- the real thing, on Windows -------------------------------------------

// A REAL cross-spawn'd `.cmd` wrapper that exits immediately after launching a
// long-lived grandchild — the npx/shadow-cljs shape, minus the 6-minute boot.
// Windows-only: the defect is a cmd.exe-shim artefact and there is no POSIX
// counterpart to model.
test('a REAL cmd wrapper that exits with a live grandchild is graded DIRTY, then reaped (rf2-kzbf AC1/AC2)', { skip: process.platform !== 'win32' ? 'Windows-only: models the cmd.exe shim cross-spawn interposes' : false }, async () => {
  const crossSpawn = require('cross-spawn');
  const fs = require('node:fs');
  const os = require('node:os');
  const { execFileSync } = require('node:child_process');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-kzbf-'));
  const beat = path.join(dir, 'beat.txt');
  fs.writeFileSync(
    path.join(dir, 'grandchild.cjs'),
    'const fs=require("node:fs");const o=process.argv[2];fs.writeFileSync(o,String(process.pid));' +
      'setInterval(()=>fs.writeFileSync(o,String(process.pid)),200);',
  );
  // `start "" /b` detaches the worker and lets the wrapper exit at once —
  // the wrapper/grandchild lifetime split, compressed.
  fs.writeFileSync(
    path.join(dir, 'wrapper.cmd'),
    '@echo off\r\nstart "" /b node "%~dp0grandchild.cjs" "%~1"\r\nexit /b 0\r\n',
  );

  const spawnedAtMs = Date.now();
  const shadow = crossSpawn(path.join(dir, 'wrapper.cmd'), [beat], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const rootPid = shadow.pid;
  let shadowExited = false;
  shadow.on('exit', () => { shadowExited = true; });

  // Wait for the wrapper to exit AND the grandchild to announce itself.
  const deadline = Date.now() + 20_000;
  while ((!shadowExited || !fs.existsSync(beat)) && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 100));
  }
  const grandPid = Number(fs.readFileSync(beat, 'utf8').trim());

  try {
    assert.ok(shadowExited, 'the cmd wrapper should have exited on its own');
    assert.ok(Number.isInteger(grandPid) && grandPid > 0, 'the grandchild should have announced its pid');

    // (a) THE PRE-FIX GRADE, reproduced: with the tree reap disabled, the
    //     wrapper's exit alone certifies this leaking run clean and GREEN.
    const beforeReport = await makeCleanup({
      getBrowser: () => null,
      getShadow: () => shadow,
      hasShadowExited: () => shadowExited,
      log: () => {},
      logErr: () => {},
    })();
    assert.equal(
      beforeReport.clean,
      true,
      'sanity: without an owned-tree reap the wrapper exit alone still reads clean — ' +
        'this is the defect being fixed, reproduced against a real process',
    );
    let sentinel = null;
    finalizeConformance(beforeReport, {
      emitPass: (l) => { sentinel = l; }, log: () => {}, logErr: () => {}, flush: () => {}, count: 0,
    });
    assert.ok(sentinel !== null, 'sanity: and it emitted the GREEN pass sentinel');
    // ...while the grandchild is demonstrably STILL RUNNING.
    const m1 = fs.statSync(beat).mtimeMs;
    await new Promise((r) => setTimeout(r, 500));
    assert.ok(
      fs.statSync(beat).mtimeMs > m1,
      'the grandchild must still be beating — otherwise this test proves nothing',
    );

    // (b) A reap whose kill is a NO-OP must grade DIRTY. This is the guard
    //     against a cleanup that reports success and removes nothing.
    const inertKillReport = await makeCleanup({
      getBrowser: () => null,
      getShadow: () => shadow,
      hasShadowExited: () => shadowExited,
      reapShadowTree: makeShadowTreeReaper({
        rootPid, spawnedAtMs, treeKill: () => {}, graceMs: 300, pollMs: 50,
        log: () => {}, logErr: () => {},
      }),
      log: () => {}, logErr: () => {},
    })();
    assert.equal(
      inertKillReport.clean,
      false,
      'a real surviving grandchild must grade DIRTY however cleanly the wrapper exited',
    );
    assert.ok(
      inertKillReport.shadow.tree.survivors.includes(grandPid),
      'the surviving grandchild pid must be named: ' + JSON.stringify(inertKillReport.shadow.tree),
    );

    // (c) The real reaper terminates the tree and grades it clean.
    const afterReport = await makeCleanup({
      getBrowser: () => null,
      getShadow: () => shadow,
      hasShadowExited: () => shadowExited,
      reapShadowTree: makeShadowTreeReaper({ rootPid, spawnedAtMs, log: () => {}, logErr: () => {} }),
      log: () => {}, logErr: () => {},
    })();
    assert.equal(afterReport.clean, true, 'the reaped tree grades clean: ' + JSON.stringify(afterReport.issues));
    assert.deepEqual(afterReport.shadow.tree.survivors, []);
    let stillAlive = true;
    try { process.kill(grandPid, 0); } catch (e) { stillAlive = e.code === 'EPERM'; }
    assert.equal(stillAlive, false, 'the grandchild must actually be GONE, not merely reported gone');
  } finally {
    // Never leave this test's own process behind, whatever failed above.
    try { execFileSync('taskkill.exe', ['/pid', String(grandPid), '/T', '/F'], { stdio: 'ignore' }); } catch {}
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch {}
  }
});
