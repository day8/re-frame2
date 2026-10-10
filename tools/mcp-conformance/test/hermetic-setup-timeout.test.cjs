// The hermetic orchestrator's SETUP-command timeout. `runTrusted` runs the
// fixture setup (`npm install`) through an ASYNC spawn with a per-command
// cap, so the event loop stays live for the child's whole lifetime and both
// that cap and the whole-run `HERMETIC_TIMEOUT_MS` watchdog stay armed. A
// synchronous spawn would let a hung install wedge the job until the outer
// CI timeout. On the timeout path the SIGTERM -> SIGKILL escalation is
// AWAITED before the promise rejects, so the reject cannot cancel the kill
// and leak a SIGTERM-ignoring child.
//
// The orchestrator is required as a module: its auto-run is guarded behind
// `require.main === module`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');

// The orchestrator reads both at module-load time, so they are set before
// the require.
process.env.HERMETIC_SETUP_TIMEOUT_MS = '750';
process.env.HERMETIC_SETUP_SIGKILL_GRACE_MS = '400';

const { runTrusted } = require(
  path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);

test('runTrusted kills a hung setup command within its timeout, loop stays live (rf2-wqi4n4 finding 2)', async () => {
  // This timer fires only if the loop keeps turning while runTrusted awaits.
  let loopStayedLive = false;
  const liveProbe = setTimeout(() => { loopStayedLive = true; }, 200);
  const start = Date.now();
  await assert.rejects(runTrusted('node', ['-e', 'setInterval(() => {}, 1000);'], os.tmpdir()), /timed out after/);
  const elapsed = Date.now() - start;
  clearTimeout(liveProbe);
  // Far below the 300s default cap, with room for a slow CI box.
  assert.ok(elapsed < 15000, `runTrusted took ${elapsed}ms to reject against a 750ms cap`);
  assert.ok(loopStayedLive, 'the event loop did not turn while runTrusted awaited');
});

// On Windows both signals map to the unconditional TerminateProcess, so the
// child dies on SIGTERM there; on the Linux CI runner the handler is honoured
// and the escalation is exercised.
test('runTrusted SIGKILLs a SIGTERM-ignoring setup child — the reject does not cancel the fallback (rf2-i4d5wr)', async () => {
  // runTrusted does not expose the child, so the child reports its own pid.
  const pidFile = path.join(os.tmpdir(), `rf2-i4d5wr-sigterm-ignore-${process.pid}-${Date.now()}.pid`);
  const IGNORE_SIGTERM = `
    const fs = require('fs');
    process.on('SIGTERM', () => {});
    process.on('SIGINT', () => {});
    fs.writeFileSync(${JSON.stringify(pidFile)}, String(process.pid));
    setInterval(() => {}, 1000);
  `;
  // `process.kill(pid, 0)` throws ESRCH once the process is gone; EPERM means
  // alive but not ours to signal.
  const pidAlive = (pid) => {
    try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
  };
  const pause = () => new Promise((r) => setTimeout(r, 25));

  const run = runTrusted('node', ['-e', IGNORE_SIGTERM], os.tmpdir());
  run.catch(() => {});

  let childPid = null;
  for (const until = Date.now() + 5000; !childPid && Date.now() < until; await pause()) {
    try { childPid = Number(fs.readFileSync(pidFile, 'utf8').trim()) || null; } catch { /* not written yet */ }
  }
  assert.ok(childPid, 'the SIGTERM-ignoring child never wrote its pid file');

  await assert.rejects(run, /timed out after/);
  // A short poll covers the OS reap latency after SIGKILL.
  for (const until = Date.now() + 5000; pidAlive(childPid) && Date.now() < until; await pause()) { /* poll */ }
  const gone = !pidAlive(childPid);
  if (!gone) { try { process.kill(childPid, 'SIGKILL'); } catch {} }
  try { fs.rmSync(pidFile, { force: true }); } catch {}
  assert.ok(gone, `the SIGTERM-ignoring child (pid ${childPid}) is still alive after runTrusted rejected`);
});
