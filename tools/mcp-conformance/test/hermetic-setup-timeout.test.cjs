// Regression test for the hermetic orchestrator's SETUP-command timeout
// contract (rf2-wqi4n4 finding 2 + rf2-i4d5wr).
//
// Two arms:
//   - rf2-wqi4n4: a hung setup command is killed within the per-command cap
//     and the event loop stays live (proving the async-spawn shape).
//   - rf2-i4d5wr: a SIGTERM-IGNORING setup child is actually SIGKILLed (the
//     timeout reject must NOT cancel the SIGKILL fallback). See that test's
//     own header for the leaked-child bug it pins.
//
// Uses Node's built-in `node:test` (same posture as
// `runner-watchdog.test.cjs` / `exec-safety.test.cjs` — no extra
// dev-dependency).
//
// ## The bug this pins
//
// `scripts/run-live-re-frame2-pair-overflow-hermetic.cjs` installs a
// `HERMETIC_TIMEOUT_MS` `setTimeout` watchdog meant to bound the WHOLE run.
// But the fixture dependency setup (`npm install`) ran through
// `crossSpawn.sync` in `runTrusted` — a SYNCHRONOUS spawn that blocks the
// Node event loop for the child's entire lifetime. While blocked, Node
// delivers no signals and runs no timers, so neither the whole-run watchdog
// nor the SIGINT/SIGTERM handlers could fire. A hung `npm install` (stuck
// registry fetch, package-manager prompt, lock contention) would wedge the
// hermetic job until the OUTER CI job timeout, bypassing the script's own
// hard cap and diagnostics.
//
// The fix converts `runTrusted` to an ASYNC spawn with an explicit
// per-command timeout/kill (`SETUP_COMMAND_TIMEOUT_MS`). The async spawn
// keeps the event loop live, so both the per-command timeout AND the
// whole-run watchdog stay armed across setup.
//
// ## What this test drives
//
// It requires the orchestrator AS A MODULE (the auto-run is guarded behind
// `require.main === module`, so requiring it does NOT boot shadow-cljs /
// Chromium) and calls the exported `runTrusted` against a never-exiting
// child (`node -e "setInterval(...)"`), with `$HERMETIC_SETUP_TIMEOUT_MS`
// shrunk to a tiny cap. It asserts:
//
//   1. `runTrusted` REJECTS within a small wall-clock bound (the per-command
//      timeout fired) — NOT after the default 300s, and NOT never. This is
//      the load-bearing assertion: a `crossSpawn.sync` regression would
//      block the event loop and this `await` would never see a rejection
//      within the bound (the test's own node:test timeout would kill it).
//   2. the rejection message names the timeout (so attribution is clear).
//   3. the event loop stayed LIVE during the wait — proven by an
//      independent `setTimeout` that MUST have fired before the reject
//      settled (a synchronous-spawn regression would starve this timer).

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-live-re-frame2-pair-overflow-hermetic.cjs',
);

// Shrink the per-command cap AND the post-SIGTERM SIGKILL grace BEFORE
// requiring the orchestrator — it reads both env vars at module-load time.
// The grace shrink keeps the rf2-i4d5wr SIGTERM-ignoring arm fast (it must
// wait the grace, then SIGKILL) without slowing the rf2-wqi4n4 arm.
process.env.HERMETIC_SETUP_TIMEOUT_MS = '750';
process.env.HERMETIC_SETUP_SIGKILL_GRACE_MS = '400';

const { runTrusted, SETUP_COMMAND_TIMEOUT_MS } = require(ORCH);

test('hermetic runTrusted env override wired (rf2-wqi4n4 finding 2)', () => {
  assert.equal(
    SETUP_COMMAND_TIMEOUT_MS,
    750,
    'the orchestrator did not honour $HERMETIC_SETUP_TIMEOUT_MS — the ' +
      'regression harness cannot drive a short per-command cap.',
  );
});

test('runTrusted kills a hung setup command within its timeout, loop stays live (rf2-wqi4n4 finding 2)', async () => {
  // A never-exiting child: `node -e` with an unref'd interval would let
  // node exit, so use a ref'd interval that keeps the child alive until our
  // timeout SIGTERM/SIGKILL reaps it.
  const NEVER_EXIT = 'setInterval(() => {}, 1000);';

  // Independent liveness probe: this timer can ONLY fire if the event loop
  // keeps turning while `runTrusted` awaits. A `crossSpawn.sync` regression
  // would block the loop and this stays false until the (much later) sync
  // child returns — by which point the test's node:test timeout has killed
  // the run.
  let loopStayedLive = false;
  const liveProbe = setTimeout(() => { loopStayedLive = true; }, 200);

  const start = Date.now();
  let rejected = false;
  let message = '';
  try {
    await runTrusted('node', ['-e', NEVER_EXIT], os.tmpdir());
  } catch (err) {
    rejected = true;
    message = (err && err.message) || String(err);
  }
  const elapsed = Date.now() - start;
  clearTimeout(liveProbe);

  // 1. The hung child was reaped by the per-command timeout (rejection),
  // not left to run forever.
  assert.ok(
    rejected,
    'runTrusted did NOT reject on a never-exiting child — the per-command ' +
      'timeout never fired. A synchronous-spawn regression would block here ' +
      'until the child exits (it never does), so this path must REJECT.',
  );

  // 2. The rejection is the timeout path, named clearly for attribution.
  assert.ok(
    /timed out after/.test(message),
    'runTrusted rejected but NOT via the timeout path; got: ' + message,
  );

  // 3. It rejected within a small bound of the configured cap — proving the
  // timer actually fired on a live loop, not the default 300s. Generous
  // upper bound (cap + spawn + SIGTERM grace) so a slow CI box doesn't
  // flake, but FAR below the 300s default a regression would exhibit.
  assert.ok(
    elapsed < 15000,
    'runTrusted took ' + elapsed + 'ms to reject — far past the 750ms cap. ' +
      'The event loop was likely blocked (synchronous spawn regression) so ' +
      'the per-command timer could not fire promptly.',
  );

  // 4. The event loop kept turning during the await (the load-bearing
  // distinction from `crossSpawn.sync`).
  assert.ok(
    loopStayedLive,
    'the event loop did NOT turn while runTrusted awaited — the independent ' +
      'liveness timer never fired. This is the synchronous-spawn signature ' +
      'finding 2 pins: a blocked loop starves both this timer AND the ' +
      'whole-run HERMETIC_TIMEOUT_MS watchdog.',
  );
});

// ── rf2-i4d5wr ───────────────────────────────────────────────────────────
//
// Pins the SIGKILL-fallback bug: on the timeout path `runTrusted` armed the
// SIGKILL as a fire-and-forget `setTimeout` (`killTimer`) and then
// immediately `settle(reject, ...)`d. `settle` cleared `killTimer`, so the
// SIGKILL fallback was CANCELLED on the very path that scheduled it. A setup
// child that IGNORES SIGTERM survived after the orchestrator reported "setup
// command hung — killed", leaking npm/node children that hold locks/pipes.
//
// The fix makes the SIGTERM→SIGKILL escalation an AWAITED sequence: the
// child is reaped (SIGTERM grace, then SIGKILL, then a bounded final wait)
// BEFORE the promise rejects, and the reject no longer cancels the kill.
//
// This arm spawns a child that installs a SIGTERM handler (and SIGINT, for
// good measure) which logs but does NOT exit, holding itself alive with a
// ref'd interval. Under POSIX signal semantics that child cannot be reaped
// by SIGTERM — only SIGKILL ends it. We then assert the child PID is GONE
// after `runTrusted` rejects, which can ONLY be true if the SIGKILL
// escalation actually fired (the pre-fix code would leave it alive).
//
// Cross-platform note: Node on Windows maps BOTH `child.kill('SIGTERM')` and
// `child.kill('SIGKILL')` to `TerminateProcess`, which is unconditional —
// a Windows process cannot "ignore SIGTERM", so the SIGTERM handler is inert
// there and the child dies on the first signal. The assertion ("child is
// gone after reject") still holds on Windows; it just exercises the SIGTERM
// leg rather than the SIGTERM→SIGKILL escalation. On Linux CI
// (`ubuntu-latest`, the gate's runner) the SIGTERM handler is honoured, so
// the escalation IS exercised — which is where the original bug bit.
test('runTrusted SIGKILLs a SIGTERM-ignoring setup child — the reject does not cancel the fallback (rf2-i4d5wr)', async () => {
  // PID handshake: the child writes its own PID to a temp file on startup so
  // the parent can poll for the child's liveness directly (independent of
  // the child handle, which `runTrusted` does not expose).
  const pidFile = path.join(
    os.tmpdir(),
    `rf2-i4d5wr-sigterm-ignore-${process.pid}-${Date.now()}.pid`,
  );
  try { fs.rmSync(pidFile, { force: true }); } catch {}

  // The child:
  //   - installs SIGTERM/SIGINT handlers that LOG but never exit,
  //   - writes its PID so the parent can watch it,
  //   - stays alive with a ref'd interval (so it does not self-exit).
  // Under POSIX this child outlives a SIGTERM; only SIGKILL ends it.
  const IGNORE_SIGTERM = `
    const fs = require('fs');
    process.on('SIGTERM', () => { /* deliberately ignore */ });
    process.on('SIGINT', () => { /* deliberately ignore */ });
    fs.writeFileSync(${JSON.stringify(pidFile)}, String(process.pid));
    setInterval(() => {}, 1000);
  `;

  // Read the child PID once it has written it (it does so synchronously at
  // startup; poll briefly to cover spawn latency).
  async function readChildPid(deadlineMs) {
    const until = Date.now() + deadlineMs;
    while (Date.now() < until) {
      try {
        const raw = fs.readFileSync(pidFile, 'utf8').trim();
        const pid = Number(raw);
        if (Number.isInteger(pid) && pid > 0) return pid;
      } catch { /* not written yet */ }
      await new Promise((r) => setTimeout(r, 25));
    }
    return null;
  }

  // `process.kill(pid, 0)` sends no signal; it throws ESRCH when the process
  // is gone, succeeds (or throws EPERM) while it is alive. The
  // platform-portable "is this PID still alive" probe.
  function pidAlive(pid) {
    try {
      process.kill(pid, 0);
      return true;
    } catch (e) {
      // ESRCH = gone. EPERM = alive but not ours to signal (treat as alive).
      return e && e.code === 'EPERM';
    }
  }

  let rejected = false;
  let message = '';
  let childPid = null;

  // Start the child via runTrusted; grab its PID while runTrusted is awaiting
  // its own timeout. We do NOT await runTrusted yet — we need the PID first.
  const runPromise = runTrusted('node', ['-e', IGNORE_SIGTERM], os.tmpdir());
  // Swallow the eventual rejection on the floating promise reference so an
  // unhandled-rejection does not crash the test runner if our await races.
  runPromise.catch(() => {});

  childPid = await readChildPid(5000);
  assert.ok(
    childPid,
    'the SIGTERM-ignoring child never wrote its PID file — could not spawn ' +
      'the regression child; cannot prove the SIGKILL escalation.',
  );

  try {
    await runPromise;
  } catch (err) {
    rejected = true;
    message = (err && err.message) || String(err);
  }

  // 1. The timeout path rejected, attributed to the timeout (not "killed by
  // <signal>" — the fix defers the exit-handler signal message to the
  // timeout IIFE so attribution stays clear).
  assert.ok(rejected, 'runTrusted did NOT reject on the SIGTERM-ignoring child.');
  assert.ok(
    /timed out after/.test(message),
    'runTrusted rejected but NOT via the timeout path; got: ' + message,
  );

  // 2. THE LOAD-BEARING ASSERTION: the child is actually GONE after the
  // reject. Pre-fix, `settle(reject)` cancelled the SIGKILL `killTimer`, so a
  // SIGTERM-ignoring child stayed alive forever — this poll would never see
  // ESRCH within the bound. Post-fix, the AWAITED SIGTERM→SIGKILL escalation
  // reaps it before (or as) the promise rejects, so the PID is gone. Poll
  // with a small bound to cover the OS reap latency after SIGKILL.
  async function waitGone(pid, deadlineMs) {
    const until = Date.now() + deadlineMs;
    while (Date.now() < until) {
      if (!pidAlive(pid)) return true;
      await new Promise((r) => setTimeout(r, 25));
    }
    return !pidAlive(pid);
  }
  const gone = await waitGone(childPid, 5000);

  // Defensive: if it somehow survived, kill it so the test does not leak the
  // very child it is meant to prove was reaped.
  if (!gone) {
    try { process.kill(childPid, 'SIGKILL'); } catch {}
  }
  try { fs.rmSync(pidFile, { force: true }); } catch {}

  assert.ok(
    gone,
    'the SIGTERM-ignoring child (pid ' + childPid + ') is STILL ALIVE after ' +
      'runTrusted rejected — the SIGKILL fallback was cancelled by the ' +
      'reject (the rf2-i4d5wr bug). The timeout path must AWAIT the ' +
      'SIGTERM→SIGKILL escalation so a hung setup child is actually reaped, ' +
      'not leaked.',
  );
});
