// The hermetic orchestrator's teardown contract. `makeCleanup` returns an
// idempotent async cleanup that awaits a bounded browser close and the
// shadow-cljs SIGTERM -> SIGKILL escalation, then GRADES the outcome: the run
// is certified green only when every process it spawned is proven gone.
//
// On Windows cross-spawn runs `npx` behind a `cmd.exe` wrapper, so the handle
// the runner holds is the wrapper and shadow-cljs and its JVM are descendants
// that outlive it. `makeShadowTreeReaper` reaps and grades that owned subtree;
// `ownedDescendants` decides which rows are provably ours, fencing recycled
// PIDs, strangers wearing our root's number and rows it cannot date.
//
// The orchestrator is required as a module: its auto-run is guarded behind
// `require.main === module`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const path = require('node:path');

const {
  makeCleanup,
  makeShadowTreeReaper,
  ownedDescendants,
} = require(path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'));

// A fake shadow-cljs child whose `kill(sig)` records each signal and exits
// only after the delay given for that signal (null = ignores it).
function makeFakeShadow({ exitOnTermMs = null, exitOnKillMs = null } = {}) {
  const ee = new EventEmitter();
  ee.killSignals = [];
  ee.exited = false;
  ee.kill = (sig) => {
    ee.killSignals.push(sig);
    const delay = sig === 'SIGTERM' ? exitOnTermMs : sig === 'SIGKILL' ? exitOnKillMs : null;
    if (delay !== null) {
      setTimeout(() => { ee.exited = true; ee.emit('exit', null, sig); }, delay);
    }
    return true;
  };
  return ee;
}

function cleanupWith(deps) {
  return makeCleanup({
    getBrowser: () => null,
    getShadow: () => null,
    hasShadowExited: () => true,
    log: () => {},
    logErr: () => {},
    ...deps,
  });
}

function shadowDeps(shadow) {
  return { getShadow: () => shadow, hasShadowExited: () => shadow.exited };
}

// A reap report whose survivors/error the test dictates.
function fakeReap({ supported = true, owned = [], survivors = [], error = null } = {}) {
  return async () => ({ supported, owned, survivors, error });
}

const quiet = { log: () => {}, logErr: () => {} };

function reaper(opts) {
  return makeShadowTreeReaper({
    rootPid: 100,
    spawnedAtMs: 5000,
    platform: 'win32',
    graceMs: 20,
    pollMs: 5,
    ...quiet,
    ...opts,
  });
}

// ---- awaiting and grading the browser and the shadow wrapper ---------------

test('makeCleanup AWAITS a slow promise-returning browser.close() and grades it CLEAN', async () => {
  let closeSettled = false;
  const browser = {
    close: () => new Promise((resolve) => setTimeout(() => { closeSettled = true; resolve(); }, 150)),
  };
  const report = await cleanupWith({ getBrowser: () => browser, browserCloseMs: 5000 })();
  assert.ok(closeSettled, 'cleanup resolved before browser.close() settled');
  assert.deepEqual([report.clean, report.browser.state, report.shadow.state], [true, 'closed', 'exited']);
});

test('makeCleanup escalates SIGTERM→SIGKILL and AWAITS the eventual exit (rf2-7ckmwx finding 1)', async () => {
  const shadow = makeFakeShadow({ exitOnKillMs: 80 });
  await cleanupWith({ ...shadowDeps(shadow), shadowTermGraceMs: 50, shadowKillGraceMs: 5000 })();
  assert.deepEqual(shadow.killSignals, ['SIGTERM', 'SIGKILL']);
  assert.ok(shadow.exited, 'cleanup resolved before the post-SIGKILL exit');
});

test('makeCleanup HARD-CAPS a never-settling browser.close() instead of hanging (rf2-7ckmwx finding 1)', async () => {
  const start = Date.now();
  await cleanupWith({ getBrowser: () => ({ close: () => new Promise(() => {}) }), browserCloseMs: 120 })();
  const elapsed = Date.now() - start;
  assert.ok(elapsed >= 100, `cleanup returned in ${elapsed}ms without waiting for the close cap`);
  assert.ok(elapsed < 5000, `cleanup took ${elapsed}ms: the close is not capped by browserCloseMs`);
});

test('makeCleanup GRADES a rejected browser.close() + never-exiting shadow as DIRTY, after attempting BOTH (rf2-j538f7.19 AC1/AC3/AC6)', async () => {
  let closes = 0;
  // No isConnected(), so disconnection cannot be proven.
  const browser = { close: () => { closes += 1; return Promise.reject(new Error('close failed')); } };
  const shadow = makeFakeShadow();
  const report = await cleanupWith({
    getBrowser: () => browser,
    ...shadowDeps(shadow),
    browserCloseMs: 40,
    shadowTermGraceMs: 30,
    shadowKillGraceMs: 30,
  })();
  assert.deepEqual(
    { clean: report.clean, browser: report.browser.state, shadow: report.shadow.state,
      closes, signals: shadow.killSignals, issues: report.issues.length },
    { clean: false, browser: 'dirty', shadow: 'alive',
      closes: 1, signals: ['SIGTERM', 'SIGKILL'], issues: 2 },
  );
});

test('makeCleanup treats a rejected browser.close() as CLEAN when isConnected() proves disconnection (rf2-j538f7.19 AC1)', async () => {
  const browser = { close: () => Promise.reject(new Error('transport already closed')), isConnected: () => false };
  const report = await cleanupWith({ getBrowser: () => browser, browserCloseMs: 100 })();
  assert.deepEqual([report.clean, report.browser.state], [true, 'disconnected']);
});

test('makeCleanup grades a browser close that exceeds its cap + stays connected as DIRTY (rf2-j538f7.19 AC2)', async () => {
  const browser = { close: () => new Promise(() => {}), isConnected: () => true };
  const report = await cleanupWith({ getBrowser: () => browser, browserCloseMs: 60 })();
  assert.deepEqual([report.clean, report.browser.state], [false, 'dirty']);
});

// A signal arriving during the `finally` teardown joins the in-flight cleanup
// rather than racing a second SIGTERM.
test('makeCleanup is idempotent and grades a shadow that exits on SIGTERM CLEAN without escalating', async () => {
  const shadow = makeFakeShadow({ exitOnTermMs: 40 });
  const cleanup = cleanupWith({ ...shadowDeps(shadow), shadowTermGraceMs: 5000 });
  const inFlight = cleanup();
  assert.equal(cleanup(), inFlight, 'concurrent cleanup() calls returned different promises');
  const report = await inFlight;
  assert.deepEqual(
    [report.clean, report.shadow.state, report.shadow.signals, shadow.killSignals],
    [true, 'exited', ['SIGTERM'], ['SIGTERM']],
  );
});

// ---- grading the owned process tree -----------------------------------------

test('a wrapper that EXITED cannot certify clean while owned descendants survive (rf2-kzbf AC1)', async () => {
  const report = await cleanupWith({ reapShadowTree: fakeReap({ owned: [4242, 4243], survivors: [4243] }) })();
  assert.deepEqual(
    { clean: report.clean, wrapper: report.shadow.state, shadowClean: report.shadow.clean,
      survivors: report.shadow.tree.survivors, issues: report.issues.length },
    { clean: false, wrapper: 'exited', shadowClean: false, survivors: [4243], issues: 1 },
  );
  assert.match(report.issues[0], /4243/, 'the issue must name the surviving pid');
});

test('an owned-tree reap that cannot be PROVEN is DIRTY, not optimistically clean (rf2-kzbf AC3)', async () => {
  const report = await cleanupWith({
    reapShadowTree: fakeReap({ error: 'could not enumerate the process table (EPERM)' }),
  })();
  assert.deepEqual([report.clean, report.shadow.clean], [false, false]);
  assert.match(report.issues[0], /could NOT be proven/);
});

test('the owned-tree reap runs even when the wrapper never exited, and both failures are reported (rf2-kzbf AC2)', async () => {
  const shadow = makeFakeShadow();
  const report = await cleanupWith({
    ...shadowDeps(shadow),
    reapShadowTree: fakeReap({ owned: [7001], survivors: [7001] }),
    shadowTermGraceMs: 20,
    shadowKillGraceMs: 20,
  })();
  assert.deepEqual(
    { wrapper: report.shadow.state, signals: shadow.killSignals, clean: report.clean, issues: report.issues.length },
    { wrapper: 'alive', signals: ['SIGTERM', 'SIGKILL'], clean: false, issues: 2 },
  );
});

test('a reaped tree with no survivors still grades CLEAN (rf2-kzbf AC4 — no false RED)', async () => {
  const report = await cleanupWith({ reapShadowTree: fakeReap({ owned: [900, 901], survivors: [] }) })();
  assert.deepEqual(
    { clean: report.clean, issues: report.issues, owned: report.shadow.tree.owned },
    { clean: true, issues: [], owned: [900, 901] },
  );
});

// ---- which rows are ours ----------------------------------------------------

test('ownedDescendants claims our subtree and fences every row it cannot prove ours (rf2-kzbf)', () => {
  for (const [label, table, notBeforeMs, opts, expected] of [
    ['the whole subtree, never an unrelated peer',
      [{ pid: 1, ppid: 0, createdMs: 1000 }, { pid: 100, ppid: 1, createdMs: 5000 },
        { pid: 200, ppid: 100, createdMs: 5100 }, { pid: 300, ppid: 200, createdMs: 5200 },
        { pid: 400, ppid: 300, createdMs: 5300 }, { pid: 999, ppid: 1, createdMs: 5100 }],
      4000, {}, [100, 200, 300, 400]],
    // Windows recycles PIDs: a process older than our spawn is not ours.
    ['not a recycled pid that predates our spawn',
      [{ pid: 100, ppid: 1, createdMs: 5000 }, { pid: 500, ppid: 100, createdMs: 4000 },
        { pid: 600, ppid: 100, createdMs: 5500 }],
      4500, {}, [100, 600]],
    // Our handle was reaped, so the number is free: a stranger wears it now,
    // while our orphaned JVM is still reached through the dead parent link.
    ['not the stranger wearing a reaped root pid, nor its child',
      [{ pid: 100, ppid: 1, createdMs: 7000 }, { pid: 200, ppid: 100, createdMs: 5500 },
        { pid: 300, ppid: 100, createdMs: 7500 }, { pid: 400, ppid: 200, createdMs: 5600 }],
      5000, { rootExited: true }, [200, 400]],
    // A direct child of our wrapper existed before the wrapper died; the bound
    // applies to the root's direct children only.
    ['not a child parented by our number after our wrapper exited',
      [{ pid: 200, ppid: 100, createdMs: 5500 }, { pid: 300, ppid: 100, createdMs: 7000 },
        { pid: 400, ppid: 200, createdMs: 7500 }],
      5000, { rootExited: true, rootExitedAtMs: 6000 }, [200, 400]],
    ['not an undated direct child of a dead root',
      [{ pid: 200, ppid: 100, createdMs: 0 }, { pid: 201, ppid: 100, createdMs: 5500 }],
      5000, { rootExited: true, rootExitedAtMs: 6000 }, [201]],
  ]) {
    assert.deepEqual(ownedDescendants(table, 100, notBeforeMs, opts).sort((a, b) => a - b), expected, label);
  }
});

// ---- the reaper's kill decisions and outcome grading -------------------------

test('makeShadowTreeReaper never tree-kills a RECYCLED root pid (rf2-kzbf audit, AC2)', async () => {
  const killed = [];
  const out = await reaper({
    readTable: () => [{ pid: 100, ppid: 1, createdMs: 1000 }, { pid: 200, ppid: 100, createdMs: 6000 }],
    treeKill: (pid) => killed.push(pid),
    isAlive: () => false,
  })();
  // A stranger wearing our number is not itself a teardown failure.
  assert.deepEqual({ killed, owned: out.owned, error: out.error }, { killed: [], owned: [], error: null });
});

test('makeShadowTreeReaper still reaps OUR orphan after the wrapper exits (rf2-kzbf audit, AC1)', async () => {
  const killed = [];
  const out = await reaper({
    rootExited: () => true,
    readTable: () => [{ pid: 100, ppid: 1, createdMs: 7000 }, { pid: 200, ppid: 100, createdMs: 5500 }],
    treeKill: (pid) => killed.push(pid),
    isAlive: (pid) => !killed.includes(pid),
    graceMs: 200,
  })();
  assert.deepEqual(
    { killed, owned: out.owned, survivors: out.survivors },
    { killed: [200], owned: [200], survivors: [] },
  );
});

// Reporting dirty only after the kill is not fail-closed.
test('makeShadowTreeReaper kills NOTHING through an unprovable root (rf2-kzbf audit of PR #9247, AC2/AC3)', async () => {
  const killed = [];
  const out = await reaper({
    readTable: () => [{ pid: 100, ppid: 1, createdMs: 0 }, { pid: 200, ppid: 100, createdMs: 6000 }],
    treeKill: (pid) => killed.push(pid),
    isAlive: () => true,
  })();
  assert.deepEqual({ killed, owned: out.owned }, { killed: [], owned: [] });
  assert.match(out.error, /cannot be proven ours/);
});

test('no dated stranger and no observed exit instant means orphan discovery is UNBOUNDED — refuse (rf2-kzbf audit of PR #9247, AC2/AC3)', async () => {
  const killed = [];
  const out = await reaper({
    rootExited: () => true,
    readTable: () => [{ pid: 200, ppid: 100, createdMs: 6000 }],
    treeKill: (pid) => killed.push(pid),
    isAlive: () => true,
  })();
  assert.deepEqual({ killed, owned: out.owned }, { killed: [], owned: [] });
  assert.match(out.error, /could not be bounded/);
});

test('an already-empty tree stays CLEAN (rf2-kzbf audit of PR #9247 — no false RED)', async () => {
  const out = await reaper({
    readTable: () => [{ pid: 999, ppid: 1, createdMs: 7000 }],
    treeKill: () => { throw new Error('nothing to kill'); },
    isAlive: () => false,
  })();
  assert.deepEqual({ owned: out.owned, error: out.error }, { owned: [], error: null });
});

// A tree-kill that returns success while removing nothing must grade the
// effect, never the call.
test('makeShadowTreeReaper reports SURVIVORS when the kill removes nothing (rf2-kzbf)', async () => {
  const killed = [];
  const out = await reaper({
    spawnedAtMs: 0,
    readTable: () => [{ pid: 100, ppid: 1, createdMs: 10 }, { pid: 200, ppid: 100, createdMs: 20 }],
    treeKill: (pid) => killed.push(pid),
    isAlive: () => true,
    graceMs: 60,
    pollMs: 10,
  })();
  assert.ok(killed.includes(100), 'the owned root must be tree-killed');
  assert.deepEqual(
    { survivors: out.survivors.sort((a, b) => a - b), error: out.error },
    { survivors: [100, 200], error: null },
  );
});

test('makeShadowTreeReaper surfaces an enumeration failure instead of reporting clean (rf2-kzbf AC3)', async () => {
  const out = await reaper({ readTable: () => { throw new Error('powershell unavailable'); }, treeKill: () => {} })();
  assert.match(out.error, /could not enumerate the process table \(powershell unavailable\)/);
});

// POSIX `npx` is exec'd directly, with no wrapper/descendant split to reap.
test('makeShadowTreeReaper is INERT on POSIX — current behaviour is unchanged there (rf2-kzbf)', async () => {
  const out = await makeShadowTreeReaper({
    rootPid: 100,
    spawnedAtMs: 0,
    platform: 'linux',
    readTable: () => { throw new Error('must never be consulted off Windows'); },
    treeKill: () => { throw new Error('must never kill off Windows'); },
  })();
  assert.deepEqual(out, { supported: false, owned: [], survivors: [], error: null });
});

test('makeShadowTreeReaper refuses to claim a reap when no root pid was recorded (rf2-kzbf AC3)', async () => {
  const out = await makeShadowTreeReaper({ rootPid: undefined, spawnedAtMs: 0, platform: 'win32' })();
  assert.equal(out.supported, true);
  assert.match(out.error, /no shadow root pid/);
});

// ---- the real thing, on Windows ---------------------------------------------

// A real cross-spawn'd `.cmd` wrapper that exits at once after launching a
// long-lived grandchild: the npx/shadow-cljs shape. The wrapper/grandchild
// split is a cmd.exe-shim artefact, so this runs on Windows only.
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
  // `start "" /b` detaches the worker and lets the wrapper exit at once.
  fs.writeFileSync(
    path.join(dir, 'wrapper.cmd'),
    '@echo off\r\nstart "" /b node "%~dp0grandchild.cjs" "%~1"\r\nexit /b 0\r\n',
  );

  const spawnedAtMs = Date.now();
  const shadow = crossSpawn(path.join(dir, 'wrapper.cmd'), [beat], { stdio: ['ignore', 'pipe', 'pipe'] });
  const rootPid = shadow.pid;
  let shadowExited = false;
  let shadowExitedAtMs = 0;
  shadow.on('exit', () => { shadowExited = true; shadowExitedAtMs = Date.now(); });

  const deadline = Date.now() + 20_000;
  while ((!shadowExited || !fs.existsSync(beat)) && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 100));
  }
  const grandPid = Number(fs.readFileSync(beat, 'utf8').trim());
  const wired = (extra) => cleanupWith({
    getShadow: () => shadow,
    hasShadowExited: () => shadowExited,
    // Wired as `main()` wires it: the wrapper HAS exited, so the reaper may not
    // kill through its number and must reach the grandchild through the dead
    // parent link.
    reapShadowTree: makeShadowTreeReaper({
      rootPid, spawnedAtMs, rootExited: () => shadowExited, rootExitedAtMs: () => shadowExitedAtMs,
      ...quiet, ...extra,
    }),
  })();

  try {
    assert.ok(shadowExited, 'the cmd wrapper should have exited on its own');
    assert.ok(Number.isInteger(grandPid) && grandPid > 0, 'the grandchild should have announced its pid');

    // A reap whose kill is a no-op must grade DIRTY and name the grandchild.
    const inertKill = await wired({ treeKill: () => {}, graceMs: 300, pollMs: 50 });
    assert.deepEqual(
      { clean: inertKill.clean, named: inertKill.shadow.tree.survivors.includes(grandPid) },
      { clean: false, named: true },
    );

    // The real reaper discovers the orphan, terminates it and grades clean.
    const after = await wired({});
    assert.deepEqual(
      { clean: after.clean, discovered: after.shadow.tree.owned.includes(grandPid), survivors: after.shadow.tree.survivors },
      { clean: true, discovered: true, survivors: [] },
    );
    let stillAlive = true;
    try { process.kill(grandPid, 0); } catch (e) { stillAlive = e.code === 'EPERM'; }
    assert.equal(stillAlive, false, 'the grandchild must actually be GONE, not merely reported gone');
  } finally {
    try { execFileSync('taskkill.exe', ['/pid', String(grandPid), '/T', '/F'], { stdio: 'ignore' }); } catch {}
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch {}
  }
});
