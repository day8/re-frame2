// Regression test for the hermetic live-suite's stale nREPL port-file
// cleanup (rf2-6i2yi4).
//
// Uses Node's built-in `node:test` (same posture as the sibling
// `*.test.cjs` files in this directory — no extra dev-dependency). Runs
// in-process: it requires the orchestrator AS A MODULE (the auto-run is
// guarded behind `require.main === module`, so requiring it does NOT
// boot shadow-cljs / Chromium) and drives the exported
// `wipeStalePortFileCandidate` against FAKE `unlink`/`statExists`/`logFn`
// — no real file, no real Windows file lock.
//
// ## The bug this pins
//
// Before this fix, `main()`'s stale-port-file wipe loop caught a BENIGN
// unlink failure (EACCES / EBUSY — a Windows file lock from a
// not-yet-reaped prior shadow-cljs process) and only logged it, then
// continued. `readPortFile()` (driving the `waitUntil('nREPL port
// file', ...)` poll right after) has NO staleness/liveness gate — it
// trusts the first candidate that parses to a finite integer. A stale
// port file surviving the failed unlink would therefore satisfy that
// poll on its very FIRST check, before shadow-cljs has any chance to
// rebind and rewrite it: best case that wastes the whole boot timeout
// polling a dead port, worst case it connects to a stale/zombie runtime
// from a prior run (state bleed between runs).
//
// FIX: after a benign unlink failure, re-stat the candidate. If it is
// genuinely gone (the failure raced a concurrent removal), proceed as
// before. If it is STILL on disk, fail loud immediately rather than
// deferring the risk to the staleness-blind read side.
//
// ## What this test drives
//
// It requires the orchestrator as a module and exercises the exported
// `wipeStalePortFileCandidate` against fakes:
//
//   1. RED-then-GREEN proof of the exact bug: a fake `unlink` that
//      throws a benign EBUSY-shaped error while a fake `statExists`
//      reports the file is STILL present. Asserts the call throws
//      (fail-loud) and the thrown message names the candidate + the
//      rf2-6i2yi4 marker. Reverting to the pre-fix behaviour (swallow +
//      log) would make this assertion fail — the proof is red against
//      the old code, green against the new.
//   2. A benign failure where the file is confirmed GONE afterward
//      (`statExists` returns false) does NOT throw — the transient-lock
//      happy path is unbroken.
//   3. A successful unlink (no throw) logs and does not throw,
//      regardless of `statExists` — the common case is unaffected.
//   4. A containment-escape error (message carries the
//      `symlink-escape accident-gating` marker) is FATAL even when
//      `statExists` would report the file gone — escape detection is
//      not weakened by this fix.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-re-frame2-pair-live-hermetic-suite.cjs',
);

// Requiring is side-effect-free: the orchestrator guards its auto-run
// behind `require.main === module`, so no shadow-cljs / Chromium spawns.
const { wipeStalePortFileCandidate } = require(ORCH);

const FIXTURE_DIR = '/fake/fixture';
const CANDIDATE = '/fake/fixture/.shadow-cljs/nrepl.port';

function benignError() {
  // Shaped like a real Node fs errno failure: has a `.code`, and its
  // `.message` carries NEITHER of the containment-escape marker strings.
  const e = new Error('EBUSY: resource busy or locked, unlink ' + CANDIDATE);
  e.code = 'EBUSY';
  return e;
}

function escapeError() {
  return new Error(
    `refusing to unlink ${CANDIDATE}: symlink-escape accident-gating — ` +
      'realpath resolves outside the allowed root',
  );
}

test('wipeStalePortFileCandidate: benign failure + file STILL present fails loud (rf2-6i2yi4 red-then-green)', () => {
  const logs = [];
  assert.throws(
    () =>
      wipeStalePortFileCandidate(CANDIDATE, FIXTURE_DIR, {
        unlink: () => {
          throw benignError();
        },
        statExists: () => true, // re-stat: still on disk
        logFn: (m) => logs.push(m),
      }),
    (err) => {
      assert.match(err.message, /STILL on disk/);
      assert.match(err.message, /rf2-6i2yi4/);
      assert.ok(err.message.includes(CANDIDATE), 'names the stale candidate');
      return true;
    },
  );
});

test('wipeStalePortFileCandidate: benign failure + file confirmed GONE does not throw (transient-lock happy path)', () => {
  const logs = [];
  assert.doesNotThrow(() =>
    wipeStalePortFileCandidate(CANDIDATE, FIXTURE_DIR, {
      unlink: () => {
        throw benignError();
      },
      statExists: () => false, // re-stat: gone now
      logFn: (m) => logs.push(m),
    }),
  );
  assert.ok(
    logs.some((m) => m.includes('gone now')),
    'logs the benign-but-resolved outcome',
  );
});

test('wipeStalePortFileCandidate: successful unlink does not throw and logs removal', () => {
  const logs = [];
  let statExistsCalled = false;
  assert.doesNotThrow(() =>
    wipeStalePortFileCandidate(CANDIDATE, FIXTURE_DIR, {
      unlink: () => true,
      statExists: () => {
        statExistsCalled = true;
        return true;
      },
      logFn: (m) => logs.push(m),
    }),
  );
  assert.equal(statExistsCalled, false, 're-stat is only consulted on a failed unlink');
  assert.ok(logs.some((m) => m.includes('removed stale port file')));
});

test('wipeStalePortFileCandidate: containment-escape is fatal even if statExists would say gone', () => {
  assert.throws(
    () =>
      wipeStalePortFileCandidate(CANDIDATE, FIXTURE_DIR, {
        unlink: () => {
          throw escapeError();
        },
        statExists: () => false,
        logFn: () => {},
      }),
    (err) => {
      assert.match(err.message, /khav7l/);
      return true;
    },
  );
});
