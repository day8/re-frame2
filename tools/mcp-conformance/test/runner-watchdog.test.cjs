// The connect-hang teardown contract of the runners' watchdogs. A watchdog
// reads a client handle that is published the moment the client is
// constructed (`connectServer`'s `onClient` callback fires BEFORE the connect
// await), so a server that spawns and then hangs mid-`initialize` still
// leaves the watchdog something to close, instead of `process.exit(2)`
// orphaning exactly the child it exists to reap.
//
// Each harness stubs the SDK with a connect that never settles and prints a
// marker from its stub `client.close()`. Exit code 2 says the watchdog fired;
// the close markers say it tore the hung clients down first. The watchdog
// `process.exit`s, so each harness runs in its own child.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('every watchdog exits 2 and closes each hung client first (rf2-2js41 finding 2, rf2-wqi4n4 finding 1)', () => {
  for (const [harness, markers] of [
    // runWithWatchdog over the primary client.
    ['runner-watchdog-hang-harness.cjs', ['HARNESS: client.close() invoked']],
    // A secondary client booted inside the body and registered with the
    // watchdog through registerAuxClient; the primary connected cleanly.
    ['runner-aux-client-hang-harness.cjs',
      ['HARNESS: primary client.close() invoked', 'HARNESS: aux client.close() invoked']],
    // end-to-end-flag-gates.cjs's own module-scope watchdog.
    ['flag-gates-hang-harness.cjs', ['HARNESS: flag-gate client.close() invoked']],
  ]) {
    const child = spawnSync(process.execPath, [path.join(__dirname, harness)], {
      cwd: path.resolve(__dirname, '..'),
      encoding: 'utf8',
      // A process kept alive by a wedged child trips this cap rather than
      // hanging CI, and reads as status null.
      timeout: 15000,
      env: process.env,
    });
    const out = (child.stdout || '') + (child.stderr || '');
    assert.equal(child.status, 2, `${harness} did not exit through its watchdog\n--- output ---\n${out}`);
    for (const marker of markers) {
      assert.ok(out.includes(marker), `${harness}: "${marker}" is absent, so the hung client was orphaned\n--- output ---\n${out}`);
    }
  }
});
