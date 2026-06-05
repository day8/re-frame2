// Regression test for the `runWithWatchdog` connect-hang teardown
// contract (rf2-2js41 finding 2).
//
// Uses Node's built-in `node:test` so the harness picks up no extra
// dev-dependency (same posture as `exec-safety.test.cjs`). Runs quiet
// on success.
//
// ## The bug this pins
//
// `runWithWatchdog` (in `_runner.cjs`) installs a watchdog `setTimeout`
// whose timeout path tears the spawned MCP child down via
// `closeQuietly(activeClient)` so a hung server can't be orphaned past
// the runner's exit. The watchdog reads `activeClient` — a closure
// handle.
//
// Pre-fix, `activeClient` was assigned only AFTER `connectServer`
// RESOLVED, and `connectServer` resolves only after
// `await client.connect(transport)` completes. So a server that spawns
// and then HANGS during the `initialize` handshake (connect never
// resolving, never throwing) left `activeClient` undefined when the
// watchdog fired: the timeout path took its `else` branch and
// `process.exit(2)`'d immediately WITHOUT calling `client.close()` /
// tearing the transport down — orphaning exactly the child the watchdog
// is meant to reap.
//
// The fix gives the watchdog a teardown handle the moment the client is
// constructed (via `connectServer`'s `onClient` callback, fired BEFORE
// the connect await). This test drives the real `runWithWatchdog`
// through a hermetic child harness whose stubbed `client.connect()`
// never resolves, and asserts:
//
//   1. the child exits with the watchdog's code 2 (the timeout fired —
//      connect genuinely hung, so this is the only exit path), AND
//   2. the stub `client.close()` ran BEFORE that exit (the harness
//      prints a marker from `close()`; its presence proves the watchdog
//      had a teardown handle and used it).
//
// Without the fix, (1) still holds (the watchdog still exits 2) but (2)
// fails — the marker never prints because `activeClient` was undefined.
// So the marker assertion is the load-bearing one.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const HARNESS = path.join(__dirname, 'runner-watchdog-hang-harness.cjs');
const CLOSE_MARKER = 'HARNESS: client.close() invoked';
const BODY_MARKER = 'HARNESS: body reached';

test('watchdog tears the child down when connect hangs mid-initialize (rf2-2js41 finding 2)', () => {
  // Spawn the hermetic harness in its own process — `runWithWatchdog`
  // `process.exit`s, so it MUST run in a child. The harness stubs the
  // SDK with a never-resolving `connect`, so the watchdog (200ms) is the
  // only exit path.
  const child = spawnSync(process.execPath, [HARNESS], {
    cwd: path.resolve(__dirname, '..'),
    encoding: 'utf8',
    // Generous cap so a genuinely-orphaning regression (process kept
    // alive by a wedged child / unresolved close) trips the timeout
    // here rather than hanging CI.
    timeout: 15000,
    env: process.env,
  });

  const out = (child.stdout || '') + (child.stderr || '');

  // The harness must not have been killed by our own spawn timeout — if
  // it was, `signal` is set and `status` is null. That itself is a
  // regression signal (the process never exited), so surface it.
  assert.equal(
    child.signal,
    null,
    'harness was killed by the test timeout (signal ' + child.signal +
      ') — runWithWatchdog never exited, i.e. a hung connect kept the ' +
      'process alive instead of the watchdog reaping it.\n--- output ---\n' + out,
  );

  // 1. The watchdog fired and exited with its canonical code 2.
  assert.equal(
    child.status,
    2,
    'connect-hang MUST exit via the watchdog with code 2; got ' +
      child.status + '.\n--- output ---\n' + out,
  );

  // Sanity: the body must NOT have run (connect hung before it).
  assert.ok(
    !out.includes(BODY_MARKER),
    'the runner body ran — connect did not hang, so this test is not ' +
      'exercising the watchdog teardown path.\n--- output ---\n' + out,
  );

  // 2. THE load-bearing assertion: teardown ran before exit. The marker
  // is printed only from the stub `client.close()`, which the watchdog
  // path reaches only if it had a teardown handle (`activeClient` set
  // before the connect await). This is the assertion that fails when the
  // fix is reverted.
  assert.ok(
    out.includes(CLOSE_MARKER),
    'the watchdog timeout path did NOT tear the spawned child down on a ' +
      'connect-hang (the stub client.close() marker is absent). This is ' +
      'the rf2-2js41 finding-2 orphan: activeClient was undefined when ' +
      'the watchdog fired, so the child was abandoned.\n--- output ---\n' + out,
  );
});
