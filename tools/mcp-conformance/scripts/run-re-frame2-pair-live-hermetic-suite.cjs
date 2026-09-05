#!/usr/bin/env node
/* Hermetic live-suite orchestrator.
 *
 * Boots the pair fixture, waits for the nREPL socket, compiled JS, browser
 * preload, and an addressable shadow runtime, then runs every entry in
 * live-test-inventory.cjs with SHADOW_CLJS_NREPL_PORT set. A child passes
 * only after close when it exits zero, prints its success sentinel, and
 * does not print SKIP.
 *
 * Exit codes: 0 conformance passed; 1 inner conformance failed; 2 setup,
 * grading, or watchdog failure. Setup commands and the whole run are
 * asynchronously bounded so watchdogs and signal cleanup remain active. */
'use strict';

const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const crossSpawn = require('cross-spawn');
const {
  resolveTrustedExe,
  safeUnlinkInside,
  safeReadFileInside,
} = require('../lib/exec-safety.cjs');
const { LIVE_TESTS } = require('./live-test-inventory.cjs');

// cross-spawn executes Windows .cmd shims without enabling shell parsing.
// Executables are still resolved to absolute, PATH-only locations first.

const HERE = __dirname;
const MCP_CONFORMANCE_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(MCP_CONFORMANCE_ROOT, '..', '..');
const FIXTURE_DIR = path.join(
  REPO_ROOT,
  'skills',
  're-frame2-pair',
  'tests',
  'fixture',
);
const RE_FRAME2_PAIR_MCP_DIR = path.join(REPO_ROOT, 'tools', 're-frame2-pair-mcp');
const {
  createDiagnosticBuffer,
  isVerboseTests,
} = require(path.join(
  REPO_ROOT,
  'implementation',
  'scripts',
  'lib',
  'browser-test-report.cjs',
));
const VERBOSE_TESTS = isVerboseTests();
const DIAGNOSTICS = createDiagnosticBuffer();

// Published as soon as children exist so watchdog and signal paths can reap
// the same resources as the normal finally path.
let activeCleanup = null;

// Keep this list aligned with pair-mcp's `port-file-candidates`.
const NREPL_PORT_FILE_CANDIDATES = [
  path.join(FIXTURE_DIR, '.shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, 'target', 'shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, '.nrepl-port'),
];

const FIXTURE_HTTP_PORT = 8030; // hard-coded in fixture's shadow-cljs.edn
const FIXTURE_URL = `http://127.0.0.1:${FIXTURE_HTTP_PORT}/`;
const FIXTURE_BUNDLE_PATH = path.join(FIXTURE_DIR, 'public', 'out', 'main.js');

// Cold shadow-cljs dependency resolution needs substantially more headroom
// than a warm fixture boot.
const SHADOW_BOOT_TIMEOUT_MS = 360_000;
const RUNTIME_PRELOAD_TIMEOUT_MS = 60_000;
const HERMETIC_TIMEOUT_MS = 540_000;
// Tests override these values to exercise bounded close and kill escalation.
const CLEANUP_BROWSER_CLOSE_TIMEOUT_MS =
  Number(process.env.HERMETIC_CLEANUP_BROWSER_MS) || 15_000;
const CLEANUP_SHADOW_SIGTERM_GRACE_MS =
  Number(process.env.HERMETIC_CLEANUP_SHADOW_GRACE_MS) || 5_000;
const CLEANUP_SHADOW_SIGKILL_GRACE_MS =
  Number(process.env.HERMETIC_CLEANUP_SHADOW_KILL_MS) || 5_000;
// How long the owned-descendant reap waits for a tree-kill to actually drain
// the process table. A JVM under `taskkill /F` dies promptly; this is the
// bound past which we report the survivors as DIRTY rather than wait forever.
const CLEANUP_SHADOW_TREE_GRACE_MS =
  Number(process.env.HERMETIC_CLEANUP_SHADOW_TREE_MS) || 10_000;
// Signal/watchdog paths race cleanup against this final bound.
const CLEANUP_HARD_CAP_MS =
  Number(process.env.HERMETIC_CLEANUP_HARD_CAP_MS) || 30_000;
// Sequential readiness probes latch once true; this is their resolution.
const POLL_MS = 100;

// Run the shared roster sequentially against one booted fixture.
const INNER_TESTS = LIVE_TESTS.map((t) => ({
  name: t.name,
  path: path.join(MCP_CONFORMANCE_ROOT, 'test', t.basename),
  sentinel: t.sentinel,
}));

function recordLine(line, stream = 'stdout') {
  DIAGNOSTICS.add(line, stream);
  if (VERBOSE_TESTS) {
    const write = stream === 'stderr' ? console.error : console.log;
    write(line);
  }
}

function recordChunk(prefix, chunk, stream = 'stdout') {
  const normalized = String(chunk || '').replace(/\r\n/g, '\n');
  for (const line of normalized.split('\n')) {
    if (line.length === 0) continue;
    recordLine(`${prefix}${line}`, stream);
  }
}

function flushDiagnostics() {
  if (VERBOSE_TESTS || DIAGNOSTICS.isEmpty()) return;
  console.error('--- re-frame2-pair hermetic diagnostics ---');
  DIAGNOSTICS.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('----------------------------------');
}

function log(msg) {
  recordLine(`[hermetic] ${msg}`);
}
function logErr(msg) {
  recordLine(`[hermetic] ${msg}`, 'stderr');
}

function exists(p) {
  try {
    fs.statSync(p);
    return true;
  } catch {
    return false;
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// Race `promise` against a `ms` cap. Resolves `true` if the promise
// settled first (success OR rejection — a rejected `browser.close()` still
// means we waited for it), `false` if the cap elapsed first. Never throws:
// the caller's contract is "did the awaited thing finish in time", and a
// teardown step that rejects is treated as "settled" (we tried, we move
// on), not as a reason to abandon the rest of cleanup. The timeout timer is
// unref'd so it can't itself keep the loop alive past a clean exit.
function settledWithin(promise, ms) {
  return new Promise((resolve) => {
    let done = false;
    const t = setTimeout(() => {
      if (done) return;
      done = true;
      resolve(false);
    }, ms);
    t.unref();
    Promise.resolve(promise).then(
      () => { if (!done) { done = true; clearTimeout(t); resolve(true); } },
      () => { if (!done) { done = true; clearTimeout(t); resolve(true); } },
    );
  });
}

// Resolve when `child` has emitted `exit` (or already has). Returns a
// promise that never rejects; pair it with `settledWithin` for a bounded
// wait. `hasExited` is the caller's already-tracked exit flag so a child
// that exited before we attach still resolves immediately.
function waitForChildExit(child, hasExited) {
  if (hasExited()) return Promise.resolve();
  return new Promise((resolve) => {
    child.once('exit', () => resolve());
  });
}

// ---------------------------------------------------------------------------
// Owned-process-tree teardown (rf2-kzbf)
//
// THE DEFECT THIS EXISTS TO CLOSE. On Windows the handle `crossSpawn` returns
// is NOT shadow-cljs. cross-spawn 7.0.6 rewrites the trusted absolute `npx`
// into `C:\Windows\system32\cmd.exe /d /s /c "...npx.CMD shadow-cljs watch
// app"`, so the child we hold — and whose `exit` event drives
// `hasShadowExited()` — is the COMMAND WRAPPER. The shadow-cljs Node process
// and the JVM it boots are its DESCENDANTS. Node's own child_process docs are
// explicit that killing a shell child does not terminate the processes it
// launched, and `child.kill()` on Windows terminates only the direct child.
//
// So `hasShadowExited() === true` proves the WRAPPER is gone and says nothing
// about the JVM still holding the fixture's port 8030. Grading teardown on
// that flag alone certifies a run GREEN while its residue survives — measured
// exactly so: wrapper `exit` code=0 observed, `report.clean === true`, pass
// sentinel emitted, grandchild still alive.
//
// The repair grades on the OWNED DESCENDANTS themselves. It is scoped to the
// subtree rooted at the exact PID this runner spawned — the same discipline
// as `scripts/test-core-jvm-windows.ps1`'s `Stop-OurSubtree` — so unrelated
// Node/JVM processes (peer workers, other MCP servers) are never targeted.
//
// AND THE SAME MISTAKE ONE LEVEL DOWN (the rf2-kzbf audit of PR #9213). "The
// subtree rooted at the exact PID we spawned" is only as good as the claim
// that the row wearing that number IS still ours. The first fix fenced the
// DESCENDANTS on creation time but let the ROOT in on the number alone
// (`if (byPid.has(rootPid)) owned.push(rootPid)`), then handed that number to
// `taskkill /T /F`. The wrapper above is short-lived by construction — it
// exits the moment it has launched the JVM — so its PID is exactly the kind
// Windows recycles soonest, and cleanup would then tree-kill a STRANGER and
// everything below it. That is the wrapper/resource confusion again, wearing
// a PID instead of a handle: an identity check that inspects a name.
//
// So the root row is now classified rather than assumed (`classifyRootRow`),
// on two pieces of evidence that catch the two ways the number can lie:
//   * we OBSERVED our own child's `exit` — the handle was reaped, the number
//     is free, and whatever wears it now is not ours (this is the case the
//     creation floor alone cannot see, because a PID recycled AFTER our spawn
//     is younger than our spawn floor); and
//   * the row's creation time predates our spawn instant — it cannot be a
//     process we started (this is the case the creation floor does see).
// A row we cannot date at all is FAIL-CLOSED: not killed, and not graded
// clean either. Orphan discovery is unaffected — Windows leaves the dead
// wrapper's number in its children's `ParentProcessId`, so the walk still
// starts at `rootPid` and still finds the JVM this whole mechanism exists
// for; only the authority to KILL that number is withdrawn.

// Is `pid` still alive? Signal 0 performs the permission/existence check
// without delivering a signal. EPERM means the pid EXISTS but is not ours to
// signal, which is still ALIVE; only ESRCH proves it is gone.
function pidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return Boolean(e) && e.code === 'EPERM';
  }
}

// Epoch-ms of the .NET/CIM `CreationDate` a process reports, used ONLY as an
// ownership guard: Windows recycles PIDs, so a process that predates our spawn
// cannot be our descendant even if it currently names our root as its parent.
const WINDOWS_PROCESS_TABLE_PS =
  'Get-CimInstance Win32_Process | ForEach-Object { ' +
  '$c = 0; ' +
  'if ($_.CreationDate) { $c = [int64](($_.CreationDate.ToUniversalTime() - ' +
  "[datetime]'1970-01-01').TotalMilliseconds) } " +
  "'{0},{1},{2}' -f $_.ProcessId, $_.ParentProcessId, $c }";

// Read the Windows process table as [{ pid, ppid, createdMs }]. `wmic` is
// removed on current Windows 11 builds, so this goes through PowerShell CIM.
// Parameterised on its runner so the unit test can drive the parse without a
// real process table.
function readWindowsProcessTable({ runPs } = {}) {
  const run =
    runPs ||
    (() =>
      require('node:child_process').execFileSync(
        path.join(
          process.env.SystemRoot || 'C:\\Windows',
          'System32',
          'WindowsPowerShell',
          'v1.0',
          'powershell.exe',
        ),
        ['-NoProfile', '-NonInteractive', '-Command', WINDOWS_PROCESS_TABLE_PS],
        { encoding: 'utf8', windowsHide: true, maxBuffer: 32 * 1024 * 1024 },
      ));
  const rows = [];
  for (const raw of String(run() || '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const parts = line.split(',');
    if (parts.length < 3) continue;
    const pid = Number(parts[0]);
    const ppid = Number(parts[1]);
    const createdMs = Number(parts[2]);
    if (!Number.isInteger(pid) || pid <= 0) continue;
    rows.push({
      pid,
      ppid: Number.isInteger(ppid) ? ppid : 0,
      createdMs: Number.isFinite(createdMs) ? createdMs : 0,
    });
  }
  return rows;
}

// Classify the row (if any) currently wearing our root's PID. A PID is a
// NUMBER, and a number is exactly what recycling forges — so the row is ours
// only when independent evidence says so, and "we could not tell" is never
// read as "ours" (that would tree-kill a stranger, which is the one thing
// teardown must never do):
//
//   'absent'     — nothing wears that number now. Our wrapper is gone.
//   'stranger'   — a row wears it that CANNOT be the process we spawned:
//                  either we OBSERVED our own child's `exit` (the handle was
//                  reaped, so whatever holds the number now is somebody
//                  else's), or the row predates our spawn floor.
//   'unprovable' — a row wears it but the OS reported no usable creation
//                  time, so ownership cannot be established either way.
//                  FAIL CLOSED: not ours to kill, and not a clean teardown
//                  either (the caller grades it dirty).
//   'ours'       — a row wears it, we have not seen our child exit, and it
//                  was created at or after the instant we spawned.
function classifyRootRow(table, rootPid, notBeforeMs, opts = {}) {
  const { rootExited = false, rootExitedAtMs = 0 } = opts;
  const row = table.find((r) => r.pid === rootPid);
  if (!row) return 'absent';
  // An observed exit INSTANT is the same evidence as the boolean, carrying
  // more of it — accept either, so a caller cannot supply the instant and
  // still be classified as though the handle were live.
  if (rootExited || rootExitedAtMs > 0) return 'stranger';
  if (!Number.isFinite(row.createdMs) || row.createdMs <= 0) return 'unprovable';
  if (row.createdMs < notBeforeMs) return 'stranger';
  return 'ours';
}

// The evidence the KILL DECISION turns on — for the root row AND for the rows
// that merely NAME its number as their parent (rf2-kzbf audit of PR #9247).
//
// Fencing the root row answered "may we kill the row wearing our number?".
// It left the other half unanswered: the walk started at `rootPid` and swept
// up its ppid claimants regardless of what the root had just been classified
// as. Two holes of exactly the shape the first fix closed, one level down:
//
//   * an UNPROVABLE root still handed us its children, and the reaper
//     tree-killed them before returning its dirty error. If the undated row
//     is a stranger, that was the stranger's child; reporting dirty AFTER
//     the kill is not fail-closed.
//   * an ABSENT root left NO ceiling at all (`strangerCeilingMs` is Infinity
//     when no row wears the number), so a stranger that took the number,
//     forked, and exited before we enumerated left a child wearing our
//     number as PPID, above our spawn floor and below no ceiling.
//
// A boolean "our wrapper exited" cannot separate that child from our own
// orphan. The INSTANT it exited can: a DIRECT child of our wrapper had to be
// created before the wrapper died, so the observed exit time is a genuine
// upper bound on ownership. Where neither that instant nor a dated stranger
// row is available there is no positive evidence at all, and the answer is to
// kill nothing and grade dirty — never to guess.
//
// Returns `{ rootClass, killRoot, strangerCeilingMs, exitCeilingMs, error }`.
// A non-null `error` with `refuse` set means the walk must not run.
function rootOwnershipEvidence(table, rootPid, notBeforeMs, opts = {}) {
  const rootClass = classifyRootRow(table, rootPid, notBeforeMs, opts);
  const rootRow = table.find((r) => r.pid === rootPid) || null;
  const exitedAtMs = Number(opts.rootExitedAtMs);
  // Our own direct child predates our wrapper's death, so anything parented
  // by that number afterwards is somebody else's.
  const exitCeilingMs =
    Number.isFinite(exitedAtMs) && exitedAtMs > 0 ? exitedAtMs : Infinity;
  // A stranger that inherited the number was created after our wrapper died,
  // so its own creation instant bounds ownership too.
  const strangerCeilingMs =
    rootClass === 'stranger' && rootRow && rootRow.createdMs > 0
      ? rootRow.createdMs
      : Infinity;
  const base = { rootClass, killRoot: false, strangerCeilingMs, exitCeilingMs };

  if (rootClass === 'ours') {
    // The row IS our live wrapper, so its ppid link is trustworthy and no
    // ceiling applies: anything below it is ours by construction.
    return { ...base, killRoot: true, refuse: false, error: null };
  }
  if (rootClass === 'unprovable') {
    return {
      ...base,
      refuse: true,
      error:
        'the process table reported no usable creation time for the row ' +
        `wearing root pid ${rootPid} — refusing to tree-kill a PID that ` +
        'cannot be proven ours, or anything parented by it',
    };
  }
  // 'absent' / 'stranger': the wrapper is gone, so the ppid link is the only
  // claim a direct child has on us. It needs a ceiling to be worth anything.
  const claimants = table.filter((r) => r.ppid === rootPid && r.pid !== rootPid);
  if (
    claimants.length > 0 &&
    !Number.isFinite(exitCeilingMs) &&
    !Number.isFinite(strangerCeilingMs)
  ) {
    return {
      ...base,
      refuse: true,
      error:
        `${claimants.length} process(es) name the vacated root pid ${rootPid} ` +
        'as their parent, but neither an observed wrapper-exit instant nor a ' +
        'dated row wearing that number is available — ownership could not be ' +
        'bounded, so nothing was killed',
    };
  }
  // An undated claimant cannot be held against either ceiling. It is left
  // alone (it may be a stranger's) and named, so the run is not certified on
  // a tree we could not fully account for.
  const undated = claimants.filter((r) => !(r.createdMs > 0)).map((r) => r.pid);
  return {
    ...base,
    refuse: false,
    error:
      undated.length > 0
        ? `pid(s) ${undated.join(', ')} name the vacated root pid ${rootPid} ` +
          'as their parent but report no usable creation time — they cannot ' +
          'be proven ours, so they were left alone'
        : null,
  };
}

// Breadth-first descendant closure of `rootPid` over `table`, EXCLUDING any
// process created before `notBeforeMs` (a recycled PID wearing our root's
// number). The ROOT ROW IS SUBJECT TO THE SAME FENCE (rf2-kzbf audit): it is
// included only when `classifyRootRow` says 'ours'. Discovery of our own
// ORPHANS is retained regardless — Windows leaves the dead wrapper's number
// in its children's `ParentProcessId`, so the walk always starts from
// `rootPid` even when no row of ours wears it any more.
//
// The ceilings from `rootOwnershipEvidence` bound that discovery, and they
// apply to the ROOT'S DIRECT CHILDREN only: a grandchild our own JVM forked
// later reaches us through a parent we have already proven ours, so its ppid
// link is trustworthy and no ceiling applies to it.
function ownedDescendants(table, rootPid, notBeforeMs, opts = {}) {
  const ev = rootOwnershipEvidence(table, rootPid, notBeforeMs, opts);
  if (ev.refuse) return [];
  const childrenOf = new Map();
  for (const row of table) {
    if (!childrenOf.has(row.ppid)) childrenOf.set(row.ppid, []);
    childrenOf.get(row.ppid).push(row);
  }
  const owned = [];
  const seen = new Set([rootPid]);
  const queue = [rootPid];
  if (ev.killRoot) owned.push(rootPid);
  while (queue.length > 0) {
    const current = queue.shift();
    for (const child of childrenOf.get(current) || []) {
      if (seen.has(child.pid)) continue;
      // A descendant created before we spawned our root is not ours.
      if (child.createdMs > 0 && child.createdMs < notBeforeMs) continue;
      if (current === rootPid && ev.rootClass !== 'ours') {
        // A direct claimant on a VACATED number needs a date to be held
        // against the ceilings at all; without one it is not provably ours.
        if (!(child.createdMs > 0)) continue;
        // Nor is a direct child the STRANGER fathered after taking the number.
        if (child.createdMs >= ev.strangerCeilingMs) continue;
        // Nor one created after our own wrapper had already died.
        if (child.createdMs > ev.exitCeilingMs) continue;
      }
      seen.add(child.pid);
      owned.push(child.pid);
      queue.push(child.pid);
    }
  }
  return owned;
}

// Build the teardown seam `makeCleanup` grades on. Returns an async
// `reapShadowTree()` resolving to:
//   { supported, owned, survivors, error }
// `supported:false` (non-Windows) is the identity: no owned set, no
// survivors, no error — POSIX grading is left exactly as it was, since no
// equivalent defect was demonstrated there (a POSIX `npx` is exec'd directly,
// not behind a `cmd.exe` shim).
function makeShadowTreeReaper({
  rootPid,
  spawnedAtMs,
  // Has OUR child handle already emitted `exit`? A reaped handle means the
  // number is free, so any row still wearing it belongs to somebody else.
  // Read as a thunk because it flips while cleanup is running.
  rootExited = () => false,
  // WHEN it emitted `exit`, as epoch ms (0 until it has). The boolean above
  // withdraws the authority to kill the NUMBER; this is what bounds the
  // orphans discovered THROUGH it, since a direct child of our wrapper had
  // to be created before the wrapper died (rf2-kzbf audit of PR #9247).
  rootExitedAtMs = () => 0,
  platform = process.platform,
  readTable = readWindowsProcessTable,
  treeKill = defaultWindowsTreeKill,
  graceMs = CLEANUP_SHADOW_TREE_GRACE_MS,
  pollMs = 200,
  isAlive = pidAlive,
  log: logFn = log,
  logErr: logErrFn = logErr,
} = {}) {
  const inert = async () => ({
    supported: false,
    owned: [],
    survivors: [],
    error: null,
  });
  if (platform !== 'win32') return inert;
  if (!Number.isInteger(rootPid) || rootPid <= 0) {
    // No PID to own means no tree we can honestly claim to have reaped.
    return async () => ({
      supported: true,
      owned: [],
      survivors: [],
      error: 'no shadow root pid was recorded — owned-descendant teardown ' +
        'could not be attempted',
    });
  }
  return async function reapShadowTree() {
    let owned;
    let evidence;
    try {
      const table = readTable();
      const exitedAt = Number(rootExitedAtMs());
      const opts = {
        rootExited: Boolean(rootExited()),
        rootExitedAtMs: Number.isFinite(exitedAt) && exitedAt > 0 ? exitedAt : 0,
      };
      // ONE evidence read drives both the kill decision and the grade, so the
      // two can never disagree about what we were entitled to touch.
      evidence = rootOwnershipEvidence(table, rootPid, spawnedAtMs, opts);
      owned = ownedDescendants(table, rootPid, spawnedAtMs, opts);
    } catch (e) {
      return {
        supported: true,
        owned: [],
        survivors: [],
        error: `could not enumerate the process table (${e && e.message})`,
      };
    }
    // FAIL CLOSED wherever ownership could not be established: nothing of the
    // sort is ours to kill, and "we could not tell" is not a proven teardown
    // either (AC3).
    const rootClass = evidence.rootClass;
    const rootError = evidence.error;
    if (rootClass === 'stranger') {
      logFn(
        `the row wearing root pid ${rootPid} is NOT the process we spawned ` +
          '(recycled PID) — it will not be touched',
      );
    }
    if (owned.length === 0) {
      if (!rootError) {
        logFn(`owned shadow tree (root pid ${rootPid}) is already empty`);
      }
      return { supported: true, owned: [], survivors: [], error: rootError };
    }
    logFn(
      `owned shadow tree rooted at pid ${rootPid}: ${owned.join(', ')} — ` +
        'tree-killing OUR subtree only',
    );
    let killError = rootError;
    try {
      // Kill the root's subtree first — but ONLY when the row wearing that
      // number is provably the process we spawned. Then any orphan we
      // attributed to the root but whose own parent link died with the
      // wrapper.
      if (evidence.killRoot) treeKill(rootPid);
      for (const pid of owned) {
        if (pid !== rootPid && isAlive(pid)) treeKill(pid);
      }
    } catch (e) {
      killError = `tree-kill failed (${e && e.message})`;
    }
    const deadline = Date.now() + graceMs;
    let survivors = owned.filter((pid) => isAlive(pid));
    while (survivors.length > 0 && Date.now() < deadline) {
      await new Promise((r) => {
        const t = setTimeout(r, pollMs);
        if (typeof t.unref === 'function') t.unref();
      });
      survivors = survivors.filter((pid) => isAlive(pid));
    }
    if (survivors.length > 0) {
      logErrFn(
        `owned shadow descendants SURVIVED tree-kill after ${graceMs}ms: ` +
          survivors.join(', '),
      );
    }
    return { supported: true, owned, survivors, error: killError };
  };
}

// `taskkill /T` walks descendants, `/F` forces. Scoped to the pid we own —
// the pattern already proven in scripts/test-core-jvm-windows.ps1.
function defaultWindowsTreeKill(pid) {
  try {
    require('node:child_process').execFileSync(
      path.join(
        process.env.SystemRoot || 'C:\\Windows',
        'System32',
        'taskkill.exe',
      ),
      ['/pid', String(pid), '/T', '/F'],
      { stdio: 'ignore', windowsHide: true },
    );
  } catch {
    // taskkill exits non-zero when the pid is already gone — the survivor
    // poll below is what actually grades the outcome, so this is not fatal.
  }
}

// Race a `browser.close()` thunk against `ms`, PRESERVING the outcome —
// unlike `settledWithin`, which collapses resolve / reject / timeout into a
// single "did it finish in time" boolean (all `makeCleanup` needs for the
// shadow-exit wait, and all the signal/watchdog paths need to bound
// `cleanup()`). Browser GRADING is different: a close that RESOLVED is proof
// of shutdown, but a close that REJECTED or TIMED OUT is tolerable only if
// disconnection can be independently proven (rf2-j538f7.19) — so the caller
// must know WHICH of the three happened. Returns:
//   { kind: 'closed'  }          — close() settled successfully within the cap
//   { kind: 'rejected', error }  — close() rejected within the cap
//   { kind: 'timeout' }          — the cap elapsed before close() settled
// Never throws (a sync throw from the thunk is normalised to 'rejected'); the
// timer is unref'd so it can't keep the loop alive past a clean exit.
function closeOutcomeWithin(closeThunk, ms) {
  return new Promise((resolve) => {
    let done = false;
    const t = setTimeout(() => {
      if (done) return;
      done = true;
      resolve({ kind: 'timeout' });
    }, ms);
    t.unref();
    Promise.resolve().then(closeThunk).then(
      () => { if (!done) { done = true; clearTimeout(t); resolve({ kind: 'closed' }); } },
      (error) => { if (!done) { done = true; clearTimeout(t); resolve({ kind: 'rejected', error }); } },
    );
  });
}

// A browser is *provably* disconnected only if it exposes `isConnected()`
// AND that returns false. Playwright's `Browser` has this method. A close
// that rejected or exceeded its cap is acceptable ONLY when we can
// independently observe the browser is gone; a missing method, a thrown
// call, or a `true` result all mean "not proven" — the browser is graded
// dirty so the run is not falsely certified hermetic (rf2-j538f7.19).
function isBrowserProvablyDisconnected(browser) {
  if (browser && typeof browser.isConnected === 'function') {
    try { return browser.isConnected() === false; } catch { return false; }
  }
  return false;
}

// Spawn one INNER_TESTS child and resolve once its stdio is fully drained
// AND it has terminated. Lives at module scope, taking `spawnFn` as a
// dependency, so the regression harness (`inner-test-close-grading.test.cjs`)
// can drive the REAL grading logic against a fake child that reproduces the
// write-then-exit race without spawning a real process.
//
// Grades on 'close', NOT 'exit' (rf2-6girz0). Node fires 'exit' as soon as
// the child process itself terminates, which can race the stdio pipes
// still draining into the `stdout`/`stderr` 'data' handlers below —
// 'close' is the event Node guarantees fires only once stdout/stderr are
// fully read. Every INNER_TESTS entry hits the worst case: its success
// path is `console.log(sentinel)` immediately followed by
// `process.exit(exitCode)` (write-then-exit, see `_runner.cjs`'s
// `runWithWatchdog`), so a conformant, fully-passing child can have its
// final sentinel-bearing stdout chunk arrive AFTER 'exit' fires — grading
// on 'exit' would resolve with a truncated `stdoutText` and the caller's
// sentinel check would then fail a gate that actually passed. 'close'
// carries the same `(code, signal)` payload as 'exit', so this is a
// like-for-like swap with no loss of signal-death detection.
//
// Resolves `{ code, stdoutText }` on a code-terminated exit (code
// non-null); rejects on spawn error or signal-termination (code === null),
// matching the pre-fix contract — only the event grading changed.
function spawnAndGradeInnerTest({
  spawnFn,
  execPath,
  testPath,
  cwd,
  env,
  testFile,
  onChunk,
  log: logFn,
}) {
  return new Promise((resolve, reject) => {
    let stdoutText = '';
    const child = spawnFn(execPath, [testPath], {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
    });
    child.stdout.on('data', (d) => {
      stdoutText += String(d);
      onChunk(`[${testFile}:stdout] `, d);
    });
    child.stderr.on('data', (d) => onChunk(`[${testFile}:stderr] `, d, 'stderr'));
    child.on('error', reject);
    child.on('close', (code, signal) => {
      // signal-terminated children report null exit codes; treat the
      // signal as a non-zero status so the conformance gate fails loud
      // rather than silently passing.
      if (code === null) {
        logFn(`${testFile} killed by ${signal}`);
        reject(new Error(`${testFile} killed by ${signal}`));
        return;
      }
      logFn(`${testFile} exited ${code}`);
      resolve({ code, stdoutText });
    });
  });
}

// Build the idempotent async teardown. Lives at
// module scope so the teardown regression harness
// (`runner-cleanup.test.cjs`) can drive the REAL teardown logic against a
// fake promise-returning browser and a slow-exiting fake child — proving
// the awaited-close + SIGTERM→exit→SIGKILL contract holds without booting
// shadow-cljs + Playwright.
//
// `deps`:
//   getBrowser()      -> the Playwright Browser or null (promise-returning `close()`)
//   getShadow()       -> the shadow-cljs child or null (`kill(sig)` + `'exit'` event)
//   hasShadowExited() -> boolean: has the shadow child already emitted `exit`
//   reapShadowTree()  -> async: terminate the OWNED descendant subtree and
//                        report `{ supported, owned, survivors, error }`.
//                        On Windows `getShadow()` is the `cmd.exe`/npx WRAPPER,
//                        not shadow-cljs, so its `exit` says nothing about the
//                        JVM below it (rf2-kzbf). Defaults to an inert reaper.
//   log / logErr      -> structured loggers (default to the module ones)
//   timeouts          -> overridable caps (default to the module constants;
//                        the harness shrinks them so the test runs fast)
//
// Returns a `cleanup()` function whose promise:
//   1. awaits `browser.close()`, bounded by `browserCloseMs`, and GRADES it,
//   2. SIGTERMs shadow then awaits its `exit` (or `shadowTermGraceMs`),
//   3. SIGKILLs if still alive then awaits the final exit (or `shadowKillGraceMs`),
//      and GRADES the shadow on its OBSERVED exit — a successful `kill()` call
//      is NOT proof of exit.
// EVERY step is attempted regardless of an earlier step's failure; the result
// is a structured, gradeable report — never a throw — so the normal path can
// refuse to certify a run whose teardown could not prove the children were
// reaped (rf2-j538f7.19), while the signal/watchdog paths that race
// `cleanup()` against a hard cap never see an unhandled rejection.
//
// Resolves to `{ clean, browser, shadow, issues }`:
//   clean   — true ⇔ the browser is proven closed/disconnected (or absent)
//             AND shadow is proven exited (or absent/already-exited) AND no
//             process we spawned survived the owned-tree reap.
//   browser — `{ attempted, state, clean, ... }` where state is one of
//             'none' | 'closed' | 'disconnected' | 'dirty'.
//   shadow  — `{ attempted, state, clean, signals, tree }` where `state` is the
//             WRAPPER's state ('none' | 'exited' | 'alive') and `tree` is the
//             owned-descendant reap report. `clean` requires BOTH: a wrapper
//             that exited AND a subtree with no survivors (rf2-kzbf).
//   issues  — human-readable strings naming each dirty resource, the signals
//             attempted, the timeouts, and the final observed state.
// Idempotent: repeat/concurrent calls return the SAME in-flight promise and
// therefore the SAME final report — no caller can observe success while
// another observes failure.
function makeCleanup(deps) {
  const {
    getBrowser,
    getShadow,
    hasShadowExited,
    // Default is the INERT reaper: `makeCleanup` itself stays platform-neutral
    // and knows no PIDs. The real Windows reaper is built at the spawn site,
    // where the root pid exists, and injected. That keeps this factory (and
    // every test that drives it) identical on Windows, macOS and Linux.
    reapShadowTree = async () => ({
      supported: false,
      owned: [],
      survivors: [],
      error: null,
    }),
    log: logFn = log,
    logErr: logErrFn = logErr,
    browserCloseMs = CLEANUP_BROWSER_CLOSE_TIMEOUT_MS,
    shadowTermGraceMs = CLEANUP_SHADOW_SIGTERM_GRACE_MS,
    shadowKillGraceMs = CLEANUP_SHADOW_SIGKILL_GRACE_MS,
  } = deps;
  let cleanupPromise = null;
  return function cleanup() {
    if (cleanupPromise) return cleanupPromise;
    cleanupPromise = (async () => {
      logFn('cleanup requested');
      const issues = [];

      // (1) Browser: await the promise-returning close, bounded, then GRADE
      //     the outcome. A close that RESOLVES is proof of shutdown. A close
      //     that rejects OR exceeds its cap is tolerated ONLY if the browser
      //     independently reports `isConnected() === false`; otherwise the
      //     browser is left DIRTY and the run must not certify hermetic.
      const browser = getBrowser();
      let browserReport;
      if (!browser) {
        browserReport = { attempted: false, state: 'none', clean: true };
      } else {
        const outcome = await closeOutcomeWithin(() => browser.close(), browserCloseMs);
        if (outcome.kind === 'closed') {
          browserReport = { attempted: true, state: 'closed', clean: true };
        } else {
          const detail =
            outcome.kind === 'rejected'
              ? `browser.close() rejected (${outcome.error && outcome.error.message})`
              : `browser.close() did not settle within ${browserCloseMs}ms`;
          if (isBrowserProvablyDisconnected(browser)) {
            // Close failed but the browser is provably gone — clean, said why.
            logFn(`${detail}, but browser.isConnected() === false — treating as closed`);
            browserReport = { attempted: true, state: 'disconnected', clean: true, note: detail };
          } else {
            const msg =
              `${detail} and disconnection could NOT be proven ` +
              '(no isConnected() === false) — browser left DIRTY';
            logErrFn(msg);
            issues.push(`browser: ${msg}`);
            browserReport = { attempted: true, state: 'dirty', clean: false, error: detail };
          }
        }
      }

      // (2)+(3) Shadow: SIGTERM → await exit / grace → SIGKILL → await, then
      //     GRADE on the OBSERVED exit only. A successful kill() call is NOT
      //     proof of exit; the child is clean only if it actually emitted
      //     `exit` (or had already exited). We do NOT claim "the OS will reap
      //     it" — parent exit does not portably terminate a live child,
      //     especially on Windows.
      const shadow = getShadow();
      let shadowReport;
      let attempted = false;
      let signals = [];
      let wrapperState;
      let wrapperClean;
      if (!shadow || hasShadowExited()) {
        wrapperState = hasShadowExited() ? 'exited' : 'none';
        wrapperClean = true;
      } else {
        attempted = true;
        signals = [];
        try { shadow.kill('SIGTERM'); signals.push('SIGTERM'); } catch {}
        const exitedOnTerm = await settledWithin(
          waitForChildExit(shadow, hasShadowExited),
          shadowTermGraceMs,
        );
        if (!exitedOnTerm && !hasShadowExited()) {
          logErrFn(
            `shadow-cljs did not exit ${shadowTermGraceMs}ms after SIGTERM ` +
              '— escalating to SIGKILL',
          );
          try { shadow.kill('SIGKILL'); signals.push('SIGKILL'); } catch {}
          await settledWithin(
            waitForChildExit(shadow, hasShadowExited),
            shadowKillGraceMs,
          );
        }
        if (hasShadowExited()) {
          wrapperState = 'exited';
          wrapperClean = true;
        } else {
          const msg =
            `shadow-cljs still has NOT reported exit after ${signals.join('+')} ` +
            `+ ${shadowKillGraceMs}ms grace — child left ALIVE (no observed exit)`;
          logErrFn(msg);
          issues.push(`shadow: ${msg}`);
          wrapperState = 'alive';
          wrapperClean = false;
        }
      }

      // (3b) OWNED DESCENDANTS (rf2-kzbf). The wrapper's `exit` proves only
      //      that the `cmd.exe`/npx shim is gone. On Windows the shadow-cljs
      //      Node process and its JVM are GRANDCHILDREN that outlive it and
      //      keep holding the fixture's port. Reap and grade the subtree
      //      rooted at the pid we spawned; a survivor — or an inability to
      //      prove there is none — is DIRTY however clean the wrapper looked.
      const tree = await reapShadowTree();
      let treeClean = true;
      if (tree.error) {
        const msg =
          'owned-descendant teardown could NOT be proven ' +
          `(${tree.error}) — refusing to call the shadow tree reaped`;
        logErrFn(msg);
        issues.push(`shadow-tree: ${msg}`);
        treeClean = false;
      } else if (tree.survivors.length > 0) {
        const msg =
          `${tree.survivors.length} process(es) we spawned SURVIVED teardown ` +
          `(pids ${tree.survivors.join(', ')}) — the run is NOT hermetic even ` +
          `though the shadow wrapper reported '${wrapperState}'`;
        logErrFn(msg);
        issues.push(`shadow-tree: ${msg}`);
        treeClean = false;
      }

      shadowReport = {
        attempted,
        state: wrapperState,
        clean: wrapperClean && treeClean,
        signals,
        tree,
      };

      const clean = browserReport.clean && shadowReport.clean;
      logFn(clean ? 'cleanup complete (clean)' : 'cleanup complete (DIRTY)');
      return { clean, browser: browserReport, shadow: shadowReport, issues };
    })();
    return cleanupPromise;
  };
}

// Turn a settled cleanup report into the normal-path process outcome. The run
// is certified GREEN (pass sentinel emitted, exit 0) ONLY when the teardown
// proved every resource clean. A dirty/unknown teardown — a browser that
// could not be confirmed closed/disconnected, or a shadow-cljs child with no
// observed exit — is an ORCHESTRATION failure (exit 2) that emits NO pass
// sentinel: the inner contract passed, but the harness could not guarantee
// the hermetic isolation it exists to provide, so it must not falsely certify
// a possibly-contaminating run (rf2-j538f7.19). Parameterised on its IO so the
// grading test can assert the sentinel/exit-code mapping without booting
// shadow-cljs + Chromium.
function finalizeConformance(report, {
  emitPass = (line) => console.log(line),
  log: logFn = log,
  logErr: logErrFn = logErr,
  flush = flushDiagnostics,
  count = INNER_TESTS.length,
} = {}) {
  if (report && report.clean === true) {
    logFn(`RE-FRAME2-PAIR-MCP LIVE HERMETIC CONFORMANCE GREEN (${count} inner tests)`);
    emitPass(`RE-FRAME2-PAIR-MCP live hermetic conformance passed (${count} inner tests).`);
    return 0;
  }
  logErrFn(
    'FAIL: all inner conformance tests passed but the hermetic TEARDOWN could ' +
      'NOT be proven clean — refusing to certify a possibly non-hermetic run ' +
      `(rf2-j538f7.19): ${describeDirty(report)}`,
  );
  flush();
  return 2;
}

// One-line summary of a dirty/missing cleanup report for the failure log.
function describeDirty(report) {
  if (!report) return 'no cleanup report was produced';
  const parts = [
    `browser=${report.browser ? report.browser.state : 'unknown'}`,
    `shadow=${report.shadow ? report.shadow.state : 'unknown'}`,
  ];
  if (report.issues && report.issues.length) {
    parts.push('issues: ' + report.issues.join(' | '));
  }
  return parts.join(', ');
}

// Discriminate a containment-escape refusal (a candidate whose realpath
// resolves OUTSIDE FIXTURE_DIR) from a benign fs failure (EACCES, EBUSY
// — a Windows lock, a permission quirk). The exec-safety helpers tag
// every escape with the `symlink-escape accident-gating` marker; benign
// failures carry an errno `code` and no such marker. Only an
// escape is fatal — a transient lock must not abort the whole run.
function isContainmentEscape(e) {
  return !!(e && typeof e.message === 'string' &&
    e.message.includes('symlink-escape accident-gating'));
}

// Wipe one stale nREPL port-file candidate before boot. A symlink-ESCAPE
// refusal is FATAL (rf2-khav7l — never continue and later trust a file
// we refused to clean). A BENIGN unlink failure (EACCES / EBUSY — a
// Windows file lock from a not-yet-reaped prior shadow-cljs process, a
// permission quirk) is tolerated ONLY when the file is actually gone
// afterward. `readPortFile()` (the `waitUntil('nREPL port file', ...)`
// poll in `main`) has NO staleness/liveness gate: it trusts the first
// candidate that parses to a finite integer, so a stale file surviving a
// failed unlink would be trusted on the very first poll — before THIS
// run's shadow-cljs has any chance to rebind and rewrite it. Best case
// that wastes the whole boot timeout; worst case it connects to a
// stale/zombie runtime from a prior run (state bleed between runs).
// Re-stat after a benign failure: if the file is genuinely gone (the
// unlink raced a concurrent removal), proceed as before; if it is still
// there, fail LOUD now instead of silently deferring the risk to the
// read side (rf2-6i2yi4). `unlink`/`statExists`/`logFn` are injectable
// so the call-site regression test can drive this against a fake
// benign-failing unlink without needing a real Windows file lock.
function wipeStalePortFileCandidate(
  p,
  fixtureDir,
  { unlink = safeUnlinkInside, statExists = exists, logFn = log } = {},
) {
  try {
    const removed = unlink(p, fixtureDir);
    if (removed) logFn(`removed stale port file ${p}`);
  } catch (e) {
    if (isContainmentEscape(e)) {
      throw new Error(
        `stale nREPL port candidate ${p} escapes FIXTURE_DIR ` +
          `(${e.message}); aborting — the runner must not continue and ` +
          'later trust a port file it refused to clean (rf2-khav7l).',
      );
    }
    if (statExists(p)) {
      throw new Error(
        `could not remove stale nREPL port file ${p} (${e.message}), and ` +
          're-stat confirms it is STILL on disk — refusing to continue: ' +
          'the poll loop below has no staleness gate and would trust this ' +
          'file on its very first check, before shadow-cljs rebinds ' +
          '(rf2-6i2yi4 stale port-file trust). The file may be held by a ' +
          'lingering prior process — remove it manually and re-run.',
      );
    }
    logFn(`could not remove stale port file ${p} (${e.message}), but it is gone now; continuing`);
  }
}

// `candidates` + `fixtureDir` are parameterised (defaulting to the
// module constants) so the call-site regression test can drive
// this exact function against a temp fixture with a symlinked
// `.shadow-cljs` — proving the poller refuses an external port file
// without booting shadow-cljs + Chromium.
function readPortFile(
  candidates = NREPL_PORT_FILE_CANDIDATES,
  fixtureDir = FIXTURE_DIR,
) {
  // Walk every candidate path; return `{port, source}` for the first
  // file that parses to a finite integer. The `source` string is used
  // for diagnostics so a successful read tells you *which* path
  // satisfied the wait — useful when shadow-cljs's default cache-root
  // moves between versions.
  //
  // Route the read through `safeReadFileInside` — the
  // SAME containment check the cleanup loop's `safeUnlinkInside` uses.
  // This refuses any candidate (or candidate parent) symlinked OUTSIDE
  // FIXTURE_DIR: such a file is exactly what the cleanup step refuses to
  // delete, and trusting it as the live nREPL source would let a stale
  // external `nrepl.port` satisfy the port-file wait and steer the inner
  // conformance tests at an unrelated runtime (false-red / false-green).
  // `safeReadFileInside` THROWS on a containment escape; we let that
  // propagate so an escaped candidate is a FATAL orchestration error, not
  // a silently-trusted read. A candidate that simply doesn't exist yet
  // returns `null` — try the next.
  for (const p of candidates) {
    let txt;
    try {
      const contents = safeReadFileInside(p, fixtureDir);
      if (contents == null) continue; // not present yet — try next
      txt = contents.trim();
    } catch (e) {
      // A containment escape (symlinked candidate / parent that resolves
      // outside FIXTURE_DIR) is a fatal orchestration error — DO NOT fall
      // through and trust the file. Re-throw with context so the caller's
      // catch surfaces it as exit 2.
      throw new Error(
        `refusing to read nREPL port candidate ${p}: ${e.message} ` +
          '(rf2-khav7l: an escaped/refused port file must not be trusted ' +
          'as the live nREPL source).',
      );
    }
    const n = parseInt(txt, 10);
    if (Number.isFinite(n)) return { port: n, source: p };
  }
  return null;
}

function probeHttp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/', timeout: 1000 },
      (res) => {
        res.resume();
        resolve(res.statusCode != null);
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Probe `/out/main.js` and confirm it is the actual compiled bundle —
// not shadow-cljs's SPA-style fallback HTML that dev-http returns 200
// for unknown paths. Used to wait for the bundle to compile before
// Chromium navigates; without this gate the page loads while shadow is
// still on its first compile, the runtime preload never runs, and the
// sentinel-wait times out.
//
// We accept the response iff the Content-Type starts with
// `application/javascript` (shadow's dev-http sets this for .js files
// it actually serves) AND the body begins with a known shadow-cljs
// preamble byte. The preamble guard is belt-and-braces — a partial
// write during compile would also fail the content-type check.
function probeBundleReady(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/out/main.js', timeout: 2000 },
      (res) => {
        const ct = (res.headers['content-type'] || '').toLowerCase();
        if (res.statusCode !== 200 || !ct.startsWith('application/javascript')) {
          res.resume();
          resolve(false);
          return;
        }
        // Read up to ~256 bytes to confirm the body is actually JS.
        // shadow-cljs's first-line preamble starts with `var $CLJS` or
        // a `SHADOW_ENV.setLoaded`/`var shadow=...` — anything beginning
        // with `<` is the SPA fallback HTML.
        let prefix = '';
        res.on('data', (chunk) => {
          prefix += chunk.toString('utf8');
          if (prefix.length >= 64) {
            req.destroy();
            const head = prefix.slice(0, 64).trimStart();
            resolve(!head.startsWith('<'));
          }
        });
        res.on('end', () => {
          const head = prefix.slice(0, 64).trimStart();
          resolve(prefix.length > 0 && !head.startsWith('<'));
        });
        res.on('error', () => resolve(false));
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Probe shadow-cljs's `:app` CLJS runtime by attempting a trivial
// `cljs-eval` over nREPL. Returns true iff the eval round-trips
// successfully — which only happens once the browser-side runtime has
// registered with shadow via the devtools WebSocket.
//
// Why this is necessary: re-frame2-pair-mcp's `runtime-preloaded?` (in
// `tools/probe.cljs`) wraps `cljs-eval` in a `.catch` that swallows
// every error to `false`, including the transient "No application has
// connected to the REPL server" error that shadow throws between
// page-load and runtime-registration. Without this gate the live-test
// fires while the runtime isn't yet addressable and re-frame2-pair-mcp's first
// `eval-cljs` call surfaces as `:runtime-not-preloaded` — a false
// negative on the actual hermetic conformance.
//
// One bencode round-trip on a fresh socket; we don't try to share the
// connection with the live test because the live test fork-execs the
// re-frame2-pair-mcp server (which opens its own nREPL connection).
function probeShadowRuntimeReady(nreplPort, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port: nreplPort, timeout: 2000 });
    let buf = Buffer.alloc(0);
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      try { sock.end(); } catch {}
      resolve(ok);
    };
    sock.on('connect', () => {
      // Bencode-encoded nREPL eval op: route through
      // `shadow.cljs.devtools.api/cljs-eval` on build `:app`. Probe the
      // same runtime sentinel re-frame2-pair-mcp checks so this gate cannot pass
      // before the preload is visible through the nREPL eval path.
      const code =
        '(shadow.cljs.devtools.api/cljs-eval :app "(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))" {})';
      // Minimal bencode hand-encode (avoid adding a dep). Op fields:
      // {"op": "eval", "code": <code>, "id": "rt-probe"}
      const dict =
        'd' +
        '2:id' + '8:rt-probe' +
        '2:op' + '4:eval' +
        '4:code' + code.length + ':' + code +
        'e';
      sock.write(dict, 'utf8');
    });
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const txt = buf.toString('utf8');
      // Look for a `:results` frame with the sentinel expression's
      // boolean `true`. A successful trivial eval is not enough: the
      // runtime can be addressable before the preload marker is visible,
      // which makes re-frame2-pair-mcp's first `ensure-runtime!` fail with a
      // false `:runtime-not-preloaded`.
      if (txt.includes(':results') && txt.includes('true') && !txt.includes('No application')) {
        finish(true);
      } else if (txt.includes('"status"') && txt.includes('done')) {
        // Op completed but no `:results` — either an error or empty.
        finish(false);
      }
    });
    sock.on('error', () => finish(false));
    sock.on('timeout', () => finish(false));
    sock.on('close', () => finish(false));
  });
}

function probeTcp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port, timeout: 1000 }, () => {
      sock.end();
      resolve(true);
    });
    sock.on('error', () => resolve(false));
    sock.on('timeout', () => {
      sock.destroy();
      resolve(false);
    });
  });
}

async function waitUntil(label, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await sleep(POLL_MS);
  }
  throw new Error(`timeout after ${timeoutMs}ms waiting for: ${label}`);
}

// Resolve `npm` / `npx` / etc. to a single trusted absolute path via
// PATH search, refusing any candidate that resolves under REPO_ROOT.
// Cached per-name so the PATH walk runs once. See `lib/exec-safety.cjs`
// for the rationale (Windows command-hijack accident-gating).
const TRUSTED_EXE_CACHE = new Map();
function trustedExe(name) {
  if (TRUSTED_EXE_CACHE.has(name)) return TRUSTED_EXE_CACHE.get(name);
  const resolved = resolveTrustedExe(name, { workspaceRoot: REPO_ROOT });
  TRUSTED_EXE_CACHE.set(name, resolved);
  return resolved;
}

// Hard cap on any trusted SETUP command (fixture `npm install`). Generous
// enough for a cold-cache install of the tiny fixture's deps on a slow CI
// runner, but bounded so a wedged package-manager child can't wedge the
// whole hermetic run. Distinct from
// `HERMETIC_TIMEOUT_MS` (the whole-run cap) — this is the per-setup-command
// cap that the whole-run watchdog physically CANNOT enforce while a
// synchronous child blocks the event loop.
//
// `$HERMETIC_SETUP_TIMEOUT_MS` overrides the cap for the
// setup-timeout regression harness ONLY (`hermetic-setup-timeout.test.cjs`
// drives `runTrusted` against a never-exiting child under a tiny cap to
// prove the timeout/kill path fires and the loop stays live). Production
// CI never sets it, so the 300s cap stands.
const SETUP_COMMAND_TIMEOUT_MS =
  Number(process.env.HERMETIC_SETUP_TIMEOUT_MS) || 300_000;

// Grace between the timeout SIGTERM and the SIGKILL escalation for a hung
// setup child. A well-behaved child reaps on SIGTERM within
// this window; a SIGTERM-ignoring child gets SIGKILLed after it. This grace
// is AWAITED (not a fire-and-forget `setTimeout` that the reject could
// cancel), so a setup child that ignores SIGTERM is GUARANTEED to receive
// SIGKILL before `runTrusted` rejects. `$HERMETIC_SETUP_SIGKILL_GRACE_MS`
// shrinks it for the regression harness (`hermetic-setup-timeout.test.cjs`)
// so the SIGTERM-ignoring arm runs fast; production CI never sets it.
const SETUP_SIGKILL_GRACE_MS =
  Number(process.env.HERMETIC_SETUP_SIGKILL_GRACE_MS) || 5_000;

// Run a trusted setup command (resolved to an absolute path outside the
// workspace via `trustedExe`) under an ASYNC spawn with an explicit
// child-level timeout/kill.
//
// The spawn is ASYNC (not `crossSpawn.sync`): a synchronous spawn would
// SYNCHRONOUSLY block the Node event loop for the child's entire lifetime.
// While blocked, Node delivers no signals and runs no timers — so the
// `HERMETIC_TIMEOUT_MS` `setTimeout` watchdog (and the SIGINT/SIGTERM
// handlers) could NOT fire, and a hung `npm install` (stuck registry
// fetch, a package-manager prompt, a lock contention) would wedge the
// whole hermetic job until the OUTER CI job timeout, bypassing this
// script's own hard time-cap and diagnostics. The inner-test spawn uses
// the same async shape for the same reason.
//
// The async spawn keeps the event loop live, so both the per-command
// timeout below AND the whole-run watchdog stay armed. On timeout we
// SIGTERM the child, AWAIT its exit (bounded by `SETUP_SIGKILL_GRACE_MS`),
// SIGKILL it if it ignored SIGTERM, then reject with an orchestration error
// — surfacing as exit 2 (orchestration failure) shortly after
// `SETUP_COMMAND_TIMEOUT_MS` rather than the multi-minute CI job timeout.
//
// The SIGTERM→SIGKILL escalation is an AWAITED sequence (the same shape
// the teardown `makeCleanup` uses): the child is reaped (or has
// demonstrably received SIGKILL) BEFORE the timeout promise rejects, and
// the reject does not cancel the kill. A setup child that ignores SIGTERM
// is therefore guaranteed to receive SIGKILL — the orchestrator never
// reports "setup command hung — killed" while leaking a still-alive
// npm/node child that holds locks/pipes. The happy path still settles
// promptly the moment the child exits normally.
function runTrusted(name, args, cwd) {
  const bin = trustedExe(name);
  // cross-spawn (async) handles the `.cmd` -> cmd.exe dispatch on Windows
  // without re-introducing PATH/cwd lookup ambiguity — see the module
  // comment at the top of this file for the contract. Passing the
  // absolute path is what keeps cross-spawn's `which` from doing its own
  // cwd-relative walk.
  log(`running ${name} ${args.join(' ')} in ${cwd} (bin=${bin})`);
  return new Promise((resolve, reject) => {
    const child = crossSpawn(bin, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });
    let settled = false;
    let childExited = false;
    let timedOut = false;

    const settle = (fn, arg) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      fn(arg);
    };

    // Per-command hard cap, enforced by the live event loop (the whole
    // point of the async spawn). On elapse: SIGTERM, AWAIT the child's exit
    // bounded by a grace, SIGKILL if it ignored SIGTERM, then reject as an
    // orchestration failure.
    //
    // The escalation is an AWAITED sequence, NOT a fire-and-
    // forget `setTimeout` that `settle(reject)` could cancel. A child that
    // ignores SIGTERM is therefore GUARANTEED to receive SIGKILL before the
    // promise rejects; we never report "killed" while the child is still
    // alive and un-SIGKILLed.
    const timer = setTimeout(() => {
      logErr(
        `${name} ${args.join(' ')} exceeded SETUP_COMMAND_TIMEOUT_MS ` +
          `(${SETUP_COMMAND_TIMEOUT_MS}ms) — killing the setup child`,
      );
      // The timeout owns the rejection attribution from here: the `exit`
      // handler observes `timedOut` and defers, so the message is "timed
      // out", not "killed by <signal>" from our own SIGKILL.
      timedOut = true;
      // `void`: the IIFE owns the kill/await; it ends by `settle(reject)`ing.
      void (async () => {
        try { child.kill('SIGTERM'); } catch {}
        const exitedOnTerm = await settledWithin(
          waitForChildExit(child, () => childExited),
          SETUP_SIGKILL_GRACE_MS,
        );
        if (!exitedOnTerm && !childExited) {
          logErr(
            `${name} ${args.join(' ')} did not exit ${SETUP_SIGKILL_GRACE_MS}ms ` +
              'after SIGTERM — escalating to SIGKILL',
          );
          try { child.kill('SIGKILL'); } catch {}
          // Best-effort observe the post-SIGKILL exit so we don't reject
          // while the OS reap is still in flight. Bounded so a truly
          // unkillable child (PID-namespace edge) can't wedge the run.
          await settledWithin(
            waitForChildExit(child, () => childExited),
            SETUP_SIGKILL_GRACE_MS,
          );
        }
        // Reject with the timeout attribution. `settle` is first-wins, so if
        // the `exit` handler already rejected with "killed by SIGKILL" this
        // is a no-op; either way the child has been SIGTERM'd→SIGKILL'd, not
        // left alive.
        settle(
          reject,
          new Error(
            `${name} ${args.join(' ')} in ${cwd} timed out after ` +
              `${SETUP_COMMAND_TIMEOUT_MS}ms (setup command hung — killed)`,
          ),
        );
      })();
    }, SETUP_COMMAND_TIMEOUT_MS);
    timer.unref();

    // `recordChunk` is the only consumer — the setup child's output is
    // streamed to the run log, never re-read as a whole. The two `stdout` /
    // `stderr` string accumulators that used to sit alongside these calls were
    // appended to on every chunk and read by nothing, so a chatty setup
    // command grew them for the life of the promise to no purpose.
    child.stdout.on('data', (d) => recordChunk(`[${name}:stdout] `, d));
    child.stderr.on('data', (d) => recordChunk(`[${name}:stderr] `, d, 'stderr'));
    child.on('error', (err) => settle(reject, err));
    child.on('exit', (code, signal) => {
      // Record the exit so the timeout path's awaited grace
      // (`waitForChildExit(child, () => childExited)`) sees a child that
      // exited before/while we waited and stops escalating.
      childExited = true;
      if (timedOut) {
        // Our own timeout SIGTERM/SIGKILL reaped it. Defer the rejection to
        // the timeout IIFE so the attribution is the timeout message, and so
        // the awaited escalation observes this exit (it `await`s
        // `waitForChildExit`, which resolves on this event).
        return;
      }
      if (signal) {
        // Signal-terminated by something OTHER than our timeout path (an
        // external SIGTERM/SIGKILL, an OOM kill). Treat as a failure so a
        // killed setup child never reads as success.
        settle(
          reject,
          new Error(
            `${name} ${args.join(' ')} in ${cwd} killed by ${signal}`,
          ),
        );
        return;
      }
      if (code !== 0) {
        settle(
          reject,
          new Error(`${name} ${args.join(' ')} in ${cwd} exited ${code}`),
        );
        return;
      }
      log(`${name} exited ${code}`);
      settle(resolve, undefined);
    });
  });
}

function resolvePlaywright() {
  // Resolve playwright either from local mcp-conformance deps or from
  // the implementation/ tree (which already lists it as a devDep). The
  // CI job installs both; locally Mike's machine likely has at least
  // one path. require.resolve throws if neither has it.
  const candidates = [MCP_CONFORMANCE_ROOT, path.join(REPO_ROOT, 'implementation')];
  for (const root of candidates) {
    try {
      const pwPath = require.resolve('playwright', { paths: [root] });
      return require(pwPath);
    } catch {
      // try next
    }
  }
  throw new Error(
    'playwright is not resolvable from tools/mcp-conformance or implementation/. ' +
      'Run `npm install` in one of those directories first.',
  );
}

async function main() {
  // ---- Sanity: required artefacts on disk -------------------------------
  if (!exists(path.join(FIXTURE_DIR, 'shadow-cljs.edn'))) {
    throw new Error(`fixture missing: ${FIXTURE_DIR}`);
  }
  for (const test of INNER_TESTS) {
    if (!exists(test.path)) {
      throw new Error(`live test missing: ${test.path}`);
    }
  }
  if (!exists(path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js'))) {
    throw new Error(
      `re-frame2-pair-mcp server bundle missing: ${path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js')}. ` +
        'Compile with `npx shadow-cljs compile server` in tools/re-frame2-pair-mcp first.',
    );
  }

  // ---- Wipe any stale port files ---------------------------------------
  // A leftover port file from a previous run could otherwise satisfy
  // the poll-loop before shadow-cljs has actually re-bound to the port,
  // and the subsequent nREPL connect would race. The shadow-cljs watch
  // child will rewrite the appropriate file as part of its boot. Wipe
  // ALL candidate paths so a stale entry at one location can't shadow
  // the fresh file at another.
  //
  // Route every unlink through `safeUnlinkInside` so a
  // symlinked candidate (or symlinked parent directory) that escapes
  // FIXTURE_DIR can't be coerced into deleting a file outside the
  // fixture tree.
  //
  // A symlink-ESCAPE refusal on a load-bearing stale port candidate is
  // FATAL (rf2-khav7l). A BENIGN unlink failure (EACCES / EBUSY) is
  // tolerated only when the file is confirmed gone afterward — see
  // `wipeStalePortFileCandidate`'s docstring for why a surviving stale
  // file must fail loud here rather than being silently trusted by the
  // staleness-blind `readPortFile()` poll below (rf2-6i2yi4).
  for (const p of NREPL_PORT_FILE_CANDIDATES) {
    wipeStalePortFileCandidate(p, FIXTURE_DIR);
  }
  try {
    const removed = safeUnlinkInside(FIXTURE_BUNDLE_PATH, FIXTURE_DIR);
    if (removed) log(`removed stale fixture bundle ${FIXTURE_BUNDLE_PATH}`);
  } catch (e) {
    if (isContainmentEscape(e)) {
      throw new Error(
        `stale fixture bundle ${FIXTURE_BUNDLE_PATH} escapes FIXTURE_DIR ` +
          `(${e.message}); aborting (rf2-khav7l).`,
      );
    }
    log(`could not remove stale fixture bundle ${FIXTURE_BUNDLE_PATH} (${e.message}); continuing`);
  }

  // ---- Install fixture deps --------------------------------------------
  // `await` the async spawn: the setup command runs under a live event
  // loop with its own hard timeout, so a hung `npm install` is bounded by
  // `SETUP_COMMAND_TIMEOUT_MS` instead of wedging the whole run past the
  // outer CI job cap.
  if (!exists(path.join(FIXTURE_DIR, 'node_modules'))) {
    log(`installing fixture deps in ${FIXTURE_DIR}`);
    await runTrusted('npm', ['install', '--no-audit', '--no-fund'], FIXTURE_DIR);
  }

  // ---- Boot shadow-cljs watch ------------------------------------------
  // Resolve `npx` to a trusted absolute path (rejected if it lives
  // inside REPO_ROOT) and route the spawn through cross-spawn so the
  // `.cmd`-on-Windows shape works without re-introducing the shell.
  // Passing the trusted absolute path (rather than a bare `npx.cmd` with
  // `shell: true` + `cwd = FIXTURE_DIR`) means a fixture-local `npx.cmd`
  // that ever landed in the checkout can never be executed.
  const npxBin = trustedExe('npx');
  log(`spawning shadow-cljs watch app in ${FIXTURE_DIR} (npx=${npxBin})`);
  // Record the spawn instant BEFORE the spawn: it is the ownership floor for
  // the descendant walk, so it must not be later than our own root's creation.
  const shadowSpawnedAtMs = Date.now();
  const shadow = crossSpawn(npxBin, ['shadow-cljs', 'watch', 'app'], {
    cwd: FIXTURE_DIR,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, FORCE_COLOR: '0' },
  });
  // On Windows `shadow.pid` is the `cmd.exe` wrapper cross-spawn interposed;
  // shadow-cljs and its JVM hang below it. This is the root of the subtree we
  // own, and the ONLY subtree teardown is allowed to touch (rf2-kzbf).
  const shadowRootPid = shadow.pid;
  let shadowExited = false;
  // WHEN the wrapper died, not merely THAT it did: our own direct children
  // all predate this instant, so it is the upper bound that keeps a
  // stranger's later child out of the kill set (rf2-kzbf audit of PR #9247).
  let shadowExitedAtMs = 0;
  shadow.on('exit', (code, sig) => {
    shadowExited = true;
    shadowExitedAtMs = Date.now();
    log(`shadow-cljs exited code=${code} sig=${sig}`);
  });
  shadow.stdout.on('data', (d) => {
    recordChunk('[shadow:stdout] ', d);
  });
  shadow.stderr.on('data', (d) => {
    recordChunk('[shadow:stderr] ', d, 'stderr');
  });

  let browser = null;

  // Idempotent async teardown. `makeCleanup` (module scope, unit-tested by
  // `runner-cleanup.test.cjs`) returns an idempotent promise that AWAITS
  // the browser close (bounded) and SIGTERM→exit→SIGKILL of shadow, so
  // every caller (`finally`, signal handlers, the hard watchdog) waits for
  // the browser-close promise to settle and for shadow-cljs to actually
  // exit before `process.exit` — the children the teardown exists to reap
  // are reaped, not abandoned. `getBrowser`/`getShadow` read the live
  // closure vars so the teardown sees `browser` even though it's assigned
  // later in `main()`.
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => shadow,
    hasShadowExited: () => shadowExited,
    // The wrapper's `exit` is not the JVM's. Grade the subtree we spawned.
    // `rootExited` is what keeps a RECYCLED root pid out of the kill set:
    // once our own handle has been reaped, the number is free and whatever
    // wears it now is somebody else's process (rf2-kzbf audit).
    reapShadowTree: makeShadowTreeReaper({
      rootPid: shadowRootPid,
      spawnedAtMs: shadowSpawnedAtMs,
      rootExited: () => shadowExited,
      rootExitedAtMs: () => shadowExitedAtMs,
    }),
  });
  // Expose the teardown to the module-scope hard watchdog
  // so a watchdog-elapse kills shadow-cljs + Chromium rather than
  // orphaning them.
  activeCleanup = cleanup;
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => {
      logErr(`caught ${sig} — tearing down`);
      // Race the async cleanup against a hard cap: an interrupted run still
      // exits promptly (CLEANUP_HARD_CAP_MS) even if a child refuses to die,
      // while still giving the teardown a real chance to settle before the
      // process exits.
      settledWithin(cleanup(), CLEANUP_HARD_CAP_MS).then((settled) => {
        if (!settled) {
          logErr(
            `cleanup did not complete within ${CLEANUP_HARD_CAP_MS}ms of ` +
              `${sig} — exiting anyway`,
          );
        }
        flushDiagnostics();
        process.exit(130);
      });
    });
  }

  // The graded teardown report the `finally` produces; `main()` returns it so
  // the entrypoint can refuse to certify GREEN on a dirty/unknown teardown
  // (rf2-j538f7.19).
  let cleanupReport = null;
  try {
    // ---- Wait for nREPL port file ---------------------------------------
    log(
      `waiting for shadow-cljs nREPL port file; candidates: ${
        NREPL_PORT_FILE_CANDIDATES.join(', ')
      }`,
    );
    let port = null;
    await waitUntil(
      'nREPL port file',
      async () => {
        if (shadowExited) {
          throw new Error('shadow-cljs exited before binding nREPL port');
        }
        const hit = readPortFile();
        if (hit) {
          port = hit.port;
          log(`nREPL port file appeared at ${hit.source}`);
          return true;
        }
        return false;
      },
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL bound to port ${port}`);

    // ---- Wait for nREPL TCP listener actually accepting -----------------
    await waitUntil(
      `nREPL TCP listener on :${port}`,
      () => probeTcp(port),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL TCP accepting on :${port}`);

    // ---- Wait for http server -------------------------------------------
    await waitUntil(
      `fixture http on :${FIXTURE_HTTP_PORT}`,
      () => probeHttp(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`fixture http reachable at ${FIXTURE_URL}`);

    // ---- Wait for the :app bundle to actually compile -------------------
    // shadow-cljs `watch` writes the nREPL port file and starts the
    // dev-http server BEFORE the first compile completes. The fixture's
    // public/index.html references `/out/main.js`; if Chromium navigates
    // before that file exists the bundle 404s, no CLJS runs, and the
    // preload sentinel never lands. We poll the asset URL until it
    // returns 200, then navigate — so navigation always happens against a
    // compiled bundle, and the sentinel wait can succeed. The first cold
    // compile on CI runs 10–20s after the watch is up; rebuilds on a warm
    // cache are <1s.
    await waitUntil(
      `fixture bundle at ${FIXTURE_URL}out/main.js`,
      () => probeBundleReady(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log('fixture bundle compiled and served');

    // ---- Launch headless Chromium + load page ---------------------------
    const playwright = resolvePlaywright();
    log('launching headless Chromium');
    browser = await playwright.chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    log(`browser URL ${FIXTURE_URL}`);
    page.on('console', (msg) => {
      recordLine(`[browser:${msg.type()}] ${msg.text()}`);
    });
    page.on('pageerror', (err) => {
      recordLine(`[browser:pageerror] ${err.message}`, 'stderr');
      if (err.stack) recordLine(err.stack, 'stderr');
    });
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        recordLine(`[browser:navigation] ${frame.url()}`);
      }
    });
    // rf2-taj9b — this navigation carried no timeout, so it took Playwright's
    // 30s default: a ceiling nothing in this file could see or move, sitting
    // BELOW the 60s RUNTIME_PRELOAD_TIMEOUT_MS that owns the very wait it was
    // duplicating. `'load'` cannot fire until the shadow-cljs `:app` bundle
    // has arrived AND run its synchronous portion — which is where the
    // preload installs `__re_frame2_pair_runtime`, i.e. precisely what the
    // `waitForFunction` below already polls for, with a budget twice as
    // large. So the navigation commits, and the sentinel wait does its job.
    // The bundle is already proven served by the probeBundleReady poll above,
    // so a genuine server fault still fails here, naming itself.
    try {
      await page.goto(FIXTURE_URL, {
        waitUntil: 'commit', timeout: RUNTIME_PRELOAD_TIMEOUT_MS,
      });
    } catch (err) {
      recordLine(
        `NAVIGATION FAILED — this is the page.goto ceiling (waitUntil: ` +
          `'commit', timeout: ${RUNTIME_PRELOAD_TIMEOUT_MS}ms), NOT the ` +
          'runtime-preload wait that carries the same number and had not yet ' +
          `started. The fixture at ${FIXTURE_URL} never responded, so no ` +
          'browser runtime exists for the suite to address (rf2-taj9b).',
        'stderr');
      throw err;
    }
    log('page navigation committed');

    // ---- Wait for the runtime sentinel ----------------------------------
    // The preload mirrors itself onto js/globalThis.__re_frame2_pair_runtime
    // at load time. This is exactly what re-frame2-pair-mcp probes via
    // ensure-runtime!; if it's not present, eval-cljs returns
    // :reason :runtime-not-preloaded and the overflow path never trips.
    await page.waitForFunction(
      () => typeof window.__re_frame2_pair_runtime !== 'undefined',
      undefined,
      { timeout: RUNTIME_PRELOAD_TIMEOUT_MS },
    );
    const sentinel = await page.evaluate(() => window.__re_frame2_pair_runtime);
    log(`runtime preload sentinel = ${JSON.stringify(sentinel)}`);

    // ---- Wait for shadow to register the browser runtime ----------------
    // re-frame2-pair-mcp routes its preload-probe through `shadow.cljs.devtools.api/
    // cljs-eval :app ...` over the nREPL. Shadow dispatches that to
    // whichever CLJS runtime is currently connected for the build. The
    // browser's runtime registers via the shadow devtools WebSocket on
    // page load, but there's a brief window between page-load and the
    // websocket handshake during which `cljs-eval` returns
    // "No application has connected to the REPL server. Make sure your
    // JS environment has loaded your compiled ClojureScript code." —
    // which `runtime-preloaded?` catches and surfaces as
    // `:runtime-not-preloaded` (the .catch in `tools/probe.cljs`
    // swallows the underlying nREPL error). Poll the same probe re-frame2-pair-mcp
    // uses until it returns true, so we hand off to the live test only
    // after the runtime is actually addressable.
    log('waiting for shadow :app runtime to register');
    await waitUntil(
      'shadow :app runtime addressable via cljs-eval',
      () => probeShadowRuntimeReady(port),
      RUNTIME_PRELOAD_TIMEOUT_MS,
    );
    log('shadow :app runtime addressable');

    // ---- Run each inner test sequentially --------------------------------
    // Each test inherits the spawned `SHADOW_CLJS_NREPL_PORT` so its
    // SKIP gate flips off and the live path fires against the same
    // booted runtime. Sequential execution keeps cold-boot cost
    // amortised; tests are short relative to shadow-cljs boot.
    //
    // Spawn ASYNC, not via `crossSpawn.sync`.
    // The sync form synchronously blocks the event loop for the inner
    // test's entire watchdog window — during which the
    // `SIGINT`/`SIGTERM`/`SIGHUP` handlers wired above CANNOT fire (Node
    // delivers signals only between event-loop iterations) and the
    // outer `HERMETIC_TIMEOUT_MS` watchdog `setTimeout` CANNOT trip. A
    // hang inside an inner test would wedge the orchestrator
    // unresponsive for ~30-60s before any outer cleanup gets control.
    // The async-spawn shape preserves signal responsiveness end-to-end.
    //
    // `process.execPath` is always an absolute path to the currently-
    // running node binary; it's outside the workspace by construction.
    // No PATH walk is performed by cross-spawn for absolute paths
    // (see `which`'s separator short-circuit), so this stays
    // accident-safe.
    const testEnv = {
      ...process.env,
      SHADOW_CLJS_NREPL_PORT: String(port),
    };
    for (const test of INNER_TESTS) {
      const testFile = path.basename(test.path);
      log(`running ${testFile} - ${test.name}`);
      // Capture the inner test's stdout so we can assert it actually RAN
      // (printed its GREEN sentinel) — not merely exited 0 (a SKIP also
      // exits 0). Grading happens on 'close' — see
      // `spawnAndGradeInnerTest` (rf2-6girz0).
      const { code: testStatus, stdoutText } = await spawnAndGradeInnerTest({
        spawnFn: crossSpawn,
        execPath: process.execPath,
        testPath: test.path,
        cwd: MCP_CONFORMANCE_ROOT,
        env: testEnv,
        testFile,
        onChunk: recordChunk,
        log,
      });
      if (testStatus !== 0) {
        // Surface the inner test's exit code verbatim so CI sees a
        // conformance failure as exit 1 (the test's own code) rather
        // than 2 (which we reserve for orchestration failures —
        // shadow-cljs didn't boot, runtime didn't preload, etc.).
        const err = new Error(
          `${path.basename(test.path)} exited ${testStatus}`,
        );
        err.exitCode = testStatus;
        throw err;
      }
      // ---- Observable-SKIP guard ------------------------------------------
      // The inner test exited 0 — but a SKIP also exits 0. The hermetic
      // env sets $SHADOW_CLJS_NREPL_PORT, so the inner test's SKIP gate
      // MUST NOT have fired. Assert it printed its success sentinel AND
      // did NOT print a SKIP banner. A broken setup path that left the
      // test SKIPping (port-file probe / bundle-compile / runtime-sentinel
      // wait silently regressed in a way that didn't propagate the env)
      // would otherwise leave this load-bearing gate UN-EXERCISED while
      // the hermetic job stayed green on the other inner tests. This is
      // an ORCHESTRATION failure (exit 2): the contract didn't fail, the
      // setup did.
      if (stdoutText.includes('\nSKIP ') || stdoutText.startsWith('SKIP ')) {
        throw new Error(
          `${testFile} SKIPped inside the hermetic orchestrator (exit 0) — ` +
            'but the hermetic env guarantees $SHADOW_CLJS_NREPL_PORT is set, ' +
            'so a SKIP here means the setup path regressed and left this ' +
            'load-bearing live gate UN-EXERCISED (a silent SKIP would ship ' +
            'green via the other inner tests). rf2-ybiz0. Inner stdout tail: ' +
            stdoutText.slice(-400),
        );
      }
      if (test.sentinel && !stdoutText.includes(test.sentinel)) {
        throw new Error(
          `${testFile} exited 0 but did NOT print its success sentinel ` +
            `("${test.sentinel}"). The live gate did not actually run to ` +
            'completion (a SKIP, an early return, or a truncated run). ' +
            'rf2-ybiz0 requires each inner test to PROVE it ran, not just ' +
            'exit 0. Inner stdout tail: ' + stdoutText.slice(-400),
        );
      }
      log(`${testFile} ran to completion (sentinel observed)`);
    }
    // NB: no GREEN sentinel is emitted here. The run is only certified GREEN
    // once cleanup has PROVEN hermetic teardown — grading happens in
    // `finalizeConformance` against the report the `finally` returns below
    // (rf2-j538f7.19). Emitting GREEN before teardown could falsely certify a
    // run that then leaks a browser / shadow-cljs child.
  } finally {
    // Await the async teardown before `main()` resolves/rejects: the
    // normal-exit path must not report success and `process.exit(0)` while
    // the browser-close promise is still settling or shadow-cljs has not
    // yet exited. Capture the graded report so the entrypoint can refuse to
    // certify a dirty/unknown teardown.
    cleanupReport = await cleanup();
  }
  return cleanupReport;
}

// Hard watchdog: if the orchestrator hangs past this, kill the process
// so CI gets a deterministic failure instead of waiting on the job
// timeout. Length set to cover cold Maven cache + cold chromium boot.
//
// On elapse it tears down shadow-cljs + Chromium BEFORE exiting, rather
// than calling `process.exit(2)` directly: a direct exit would orphan the
// spawned JVM (and any launched Chromium), and orphans that inherited the
// step's stdio can keep the CI step's log pipes open past the node exit,
// making the gate appear to hang well past the orchestrator's own cap.
//
// `activeCleanup` is async (awaited browser.close + SIGTERM→exit→SIGKILL
// of shadow). The watchdog RACES the async cleanup against
// `CLEANUP_HARD_CAP_MS`: the teardown gets a real chance to reap the
// children, but the process still exits within the cap if a child refuses
// to die. `activeCleanup` is null only if the watchdog fires before
// `main()` has spawned the children (nothing to reap), so we exit straight
// away in that case.
const watchdog = setTimeout(() => {
  logErr(`watchdog timeout (${HERMETIC_TIMEOUT_MS}ms) — bailing`);
  const bail = () => {
    flushDiagnostics();
    process.exit(2);
  };
  if (activeCleanup) {
    settledWithin(
      (async () => { try { await activeCleanup(); } catch {} })(),
      CLEANUP_HARD_CAP_MS,
    ).then((settled) => {
      if (!settled) {
        logErr(
          `cleanup did not complete within ${CLEANUP_HARD_CAP_MS}ms of the ` +
            'watchdog elapse — exiting anyway',
        );
      }
      bail();
    });
  } else {
    bail();
  }
}, HERMETIC_TIMEOUT_MS);
watchdog.unref();

// Only auto-run the orchestrator when invoked as the entry-point. Required
// as a module (by the `runTrusted` regression test),
// it exports the unit under test WITHOUT kicking off the whole hermetic run
// (which would spawn shadow-cljs + Chromium). Guarding the run here keeps
// the watchdog timer from arming on `require` too.
if (require.main === module) {
  main()
    .then((report) => {
      clearTimeout(watchdog);
      // GREEN + exit 0 ONLY if the teardown proved hermetic; a dirty/unknown
      // cleanup report is an orchestration failure (exit 2) with NO pass
      // sentinel (rf2-j538f7.19).
      process.exit(finalizeConformance(report));
    })
    .catch((err) => {
      clearTimeout(watchdog);
      logErr('FAIL: ' + (err && err.message ? err.message : err));
      if (err && err.stack) logErr(err.stack);
      flushDiagnostics();
      // err.exitCode is set when the inner live-re-frame2-pair-overflow.cjs itself
      // exited non-zero — surface it so CI distinguishes conformance
      // failure (1) from orchestration failure (2).
      process.exit(err && typeof err.exitCode === 'number' ? err.exitCode : 2);
    });
} else {
  // Required as a module — don't arm the whole-run watchdog.
  clearTimeout(watchdog);
}

// Exported for the setup-command regression harness.
// `runTrusted` is the async, timeout-bounded setup-command spawn; the test
// drives it against (a) a never-exiting child to prove a hung setup command
// is killed within `SETUP_COMMAND_TIMEOUT_MS` instead of wedging the event
// loop, and (b) a SIGTERM-IGNORING child to prove the timeout path AWAITS
// the SIGTERM grace then SIGKILLs it (the reject does not cancel the
// fallback) so the child is actually reaped, not leaked.
//
// `readPortFile` + `isContainmentEscape` are exported for the
// call-site regression harness (`port-file-escape.test.cjs`): it drives
// `readPortFile` against a temp fixture whose `.shadow-cljs` is symlinked
// outside, proving the poller REFUSES an external port file (throws)
// rather than raw-reading it — the read-side guarantee that lets the
// cleanup loop's escape-refusal be safe.
//
// `makeCleanup` + `settledWithin` + `waitForChildExit` are exported for the
// teardown regression harness (`runner-cleanup.test.cjs`): it
// drives the REAL teardown against a fake promise-returning browser and a
// slow-exiting fake child, proving the awaited browser-close + the
// SIGTERM→exit→SIGKILL escalation are WAITED for (or hard-capped), never
// fire-and-forgotten.
//
// `spawnAndGradeInnerTest` is exported for the close-vs-exit grading
// regression harness (`inner-test-close-grading.test.cjs`, rf2-6girz0): it
// drives the REAL grading logic against a fake `spawnFn` whose child emits
// its sentinel-bearing stdout `data` AFTER `exit` but BEFORE `close`,
// proving the harness reads the fully-drained stdout (via `close`) rather
// than scoring a conformant, late-flushing child as failed.
//
// `wipeStalePortFileCandidate` is exported for the stale-port-file-trust
// regression harness (`stale-port-file-trust.test.cjs`, rf2-6i2yi4): it
// drives the REAL wipe logic with an injected `unlink` that fails
// benignly (mimicking a Windows EBUSY lock) while an injected `statExists`
// still reports the file present, proving the runner now fails LOUD
// instead of logging-and-continuing into a poll loop that would have
// trusted the surviving stale file.
//
// `finalizeConformance` is exported for the teardown-grading regression
// harness (`hermetic-grading.test.cjs`, rf2-j538f7.19): it drives the REAL
// grading decision against clean vs dirty cleanup reports, proving a dirty
// teardown emits NO pass sentinel and returns orchestration exit 2 while a
// clean teardown emits the sentinel exactly once and returns 0.
//
// `closeOutcomeWithin`, `isBrowserProvablyDisconnected` and `describeDirty`
// are deliberately NOT exported: each stays module-local and is reached from
// a test only through the composed behaviour above — the browser-close
// grading through `makeCleanup`, the dirty-report prose through
// `finalizeConformance`. Exporting them would advertise a test seam that
// invites coupling to the helper instead of the decision it serves.
// `makeShadowTreeReaper` + `ownedDescendants` + `classifyRootRow` + `pidAlive`
// are exported for the owned-tree regression harness
// (`runner-cleanup.test.cjs`, rf2-kzbf). The factory is driven with injected
// `readTable`/`treeKill`/`isAlive` fakes so the ownership walk, the
// recycled-PID guard (on the ROOT row as well as on descendants) and the
// survivor grading are pinned on EVERY platform, and additionally against a
// REAL cross-spawn'd `cmd.exe` wrapper whose grandchild outlives it on
// Windows — the exact shape that previously certified a leaked JVM as a
// clean, GREEN, exit-0 run.
module.exports = {
  runTrusted,
  SETUP_COMMAND_TIMEOUT_MS,
  readPortFile,
  isContainmentEscape,
  makeCleanup,
  settledWithin,
  finalizeConformance,
  waitForChildExit,
  spawnAndGradeInnerTest,
  wipeStalePortFileCandidate,
  makeShadowTreeReaper,
  ownedDescendants,
  classifyRootRow,
  rootOwnershipEvidence,
  pidAlive,
};
