// Regression test for the hermetic orchestrator's SETUP-command timeout
// contract (rf2-wqi4n4 finding 2).
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
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-live-re-frame2-pair-overflow-hermetic.cjs',
);

// Shrink the per-command cap BEFORE requiring the orchestrator — it reads
// `$HERMETIC_SETUP_TIMEOUT_MS` at module-load time.
process.env.HERMETIC_SETUP_TIMEOUT_MS = '750';

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
