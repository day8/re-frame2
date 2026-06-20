// Regression test for the `runWithWatchdog` connect-hang teardown
// contract.
//
// Uses Node's built-in `node:test` so the harness picks up no extra
// dev-dependency (same posture as `exec-safety.test.cjs`). Runs quiet
// on success.
//
// ## The contract this pins
//
// `runWithWatchdog` (in `_runner.cjs`) installs a watchdog `setTimeout`
// whose timeout path tears the spawned MCP child down via
// `closeQuietly(activeClient)` so a hung server can't be orphaned past
// the runner's exit. The watchdog reads `activeClient` — a closure
// handle that is published the moment the client is constructed (via
// `connectServer`'s `onClient` callback, fired BEFORE the connect await).
// That ordering is the load-bearing property: a server that spawns and
// then HANGS during the `initialize` handshake (connect never resolving,
// never throwing) must still leave the watchdog a teardown handle, so the
// timeout path can call `client.close()` / tear the transport down rather
// than `process.exit(2)`'ing and orphaning exactly the child the watchdog
// is meant to reap.
//
// This test drives the real `runWithWatchdog` through a hermetic child
// harness whose stubbed `client.connect()` never resolves, and asserts:
//
//   1. the child exits with the watchdog's code 2 (the timeout fired —
//      connect genuinely hung, so this is the only exit path), AND
//   2. the stub `client.close()` ran BEFORE that exit (the harness
//      prints a marker from `close()`; its presence proves the watchdog
//      had a teardown handle and used it).
//
// If `activeClient` were undefined when the watchdog fired, (1) would
// still hold (the watchdog still exits 2) but (2) would fail — the marker
// never prints. So the marker assertion is the load-bearing one.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const HARNESS = path.join(__dirname, 'runner-watchdog-hang-harness.cjs');
const AUX_HARNESS = path.join(__dirname, 'runner-aux-client-hang-harness.cjs');
const FLAG_GATES_HARNESS = path.join(__dirname, 'flag-gates-hang-harness.cjs');
const CLOSE_MARKER = 'HARNESS: client.close() invoked';
const BODY_MARKER = 'HARNESS: body reached';
const PRIMARY_CLOSE_MARKER = 'HARNESS: primary client.close() invoked';
const AUX_CLOSE_MARKER = 'HARNESS: aux client.close() invoked';
const AUX_SPAWNED_MARKER = 'HARNESS: aux client constructed';
const FLAG_GATE_CLOSE_MARKER = 'HARNESS: flag-gate client.close() invoked';

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

test('watchdog tears the SECONDARY (body-booted) client down when its connect hangs (rf2-wqi4n4 finding 1)', () => {
  // The redaction live test boots ADDITIONAL in-process servers inside the
  // runWithWatchdog body (inner-projection + gate-ON arms). The outer
  // watchdog owns the PRIMARY client AND every secondary registered with
  // it, so a hang in a SECONDARY boot is still reaped on timeout. The
  // harness boots a primary (connect resolves) then a secondary (connect
  // hangs) via the real connectServer + registerAuxClient, and asserts the
  // watchdog reaps BOTH.
  const child = spawnSync(process.execPath, [AUX_HARNESS], {
    cwd: path.resolve(__dirname, '..'),
    encoding: 'utf8',
    timeout: 15000,
    env: process.env,
  });
  const out = (child.stdout || '') + (child.stderr || '');

  assert.equal(
    child.signal,
    null,
    'aux-hang harness was killed by the test timeout (signal ' + child.signal +
      ') — the process never exited, i.e. a hung secondary connect kept it ' +
      'alive instead of the watchdog reaping it.\n--- output ---\n' + out,
  );
  assert.equal(
    child.status,
    2,
    'secondary connect-hang MUST exit via the watchdog with code 2; got ' +
      child.status + '.\n--- output ---\n' + out,
  );
  // The secondary MUST actually have been constructed (otherwise the test
  // is vacuous — the hang must be in the secondary, not the primary).
  assert.ok(
    out.includes(AUX_SPAWNED_MARKER),
    'the secondary client was never constructed — the harness is not ' +
      'exercising the secondary-hang path.\n--- output ---\n' + out,
  );
  // 1. The PRIMARY was torn down (the baseline single-client guarantee).
  assert.ok(
    out.includes(PRIMARY_CLOSE_MARKER),
    'the watchdog did NOT close the PRIMARY client on the secondary hang.\n' +
      '--- output ---\n' + out,
  );
  // 2. THE load-bearing assertion: the SECONDARY (body-booted) client was
  // ALSO torn down. Absent the registerAuxClient fix the watchdog has no
  // handle to it and the secondary child is orphaned.
  assert.ok(
    out.includes(AUX_CLOSE_MARKER),
    'the watchdog timeout path did NOT tear the SECONDARY client down ' +
      '(the aux close marker is absent). This is the rf2-wqi4n4 finding-1 ' +
      'orphan: a body-booted secondary server was unreachable by the outer ' +
      'watchdog.\n--- output ---\n' + out,
  );
});

test('flag-gates module watchdog tears the hung JVM client down on a connect-hang (rf2-wqi4n4 finding 1)', () => {
  // end-to-end-flag-gates.cjs runs its OWN module-scope watchdog (not
  // runWithWatchdog) over `activeFlagGateClient`. That handle is published
  // via `connectServer`'s `onClient` callback BEFORE the connect await, so
  // a story-mcp JVM that hangs mid-initialize is still torn down when the
  // watchdog fires. The harness stubs a never-resolving connect, bypasses
  // resolveTrustedExe via $STORY_MCP_CMD, and shrinks the watchdog via
  // $FLAG_GATE_WATCHDOG_MS.
  const child = spawnSync(process.execPath, [FLAG_GATES_HARNESS], {
    cwd: path.resolve(__dirname, '..'),
    encoding: 'utf8',
    timeout: 15000,
    env: process.env,
  });
  const out = (child.stdout || '') + (child.stderr || '');

  assert.equal(
    child.signal,
    null,
    'flag-gates harness was killed by the test timeout (signal ' +
      child.signal + ') — the module-scope watchdog never exited on a hung ' +
      'connect.\n--- output ---\n' + out,
  );
  assert.equal(
    child.status,
    2,
    'flag-gates connect-hang MUST exit via the module watchdog with code 2; ' +
      'got ' + child.status + '.\n--- output ---\n' + out,
  );
  // THE load-bearing assertion: the hung JVM client was torn down. Absent
  // the onClient fix `activeFlagGateClient` is null when the watchdog fires
  // and the marker never prints.
  assert.ok(
    out.includes(FLAG_GATE_CLOSE_MARKER),
    'the flag-gates module watchdog did NOT tear the hung JVM client down ' +
      '(close marker absent). This is the rf2-wqi4n4 finding-1 orphan: ' +
      'activeFlagGateClient was null when the watchdog fired because the ' +
      'handle was published only after connectServer resolved.\n' +
      '--- output ---\n' + out,
  );
});
