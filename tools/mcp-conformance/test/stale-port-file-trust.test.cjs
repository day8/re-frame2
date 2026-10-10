// The hermetic live-suite's stale nREPL port-file wipe. `readPortFile()` has
// no staleness gate: it trusts the first candidate that parses, so a stale
// port file surviving a failed unlink would satisfy the boot poll on its
// first check and connect to a dead or prior-run runtime. A benign unlink
// failure (EACCES / EBUSY, a Windows lock from a not-yet-reaped shadow-cljs)
// is therefore re-statted: gone means proceed, still on disk means fail loud.
// A containment escape is fatal whatever the re-stat says.
//
// Driven in-process against fake `unlink`/`statExists`/`logFn`; the
// orchestrator's auto-run is guarded behind `require.main === module`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { wipeStalePortFileCandidate } = require(
  path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);

const FIXTURE_DIR = '/fake/fixture';
const CANDIDATE = '/fake/fixture/.shadow-cljs/nrepl.port';

// A real fs errno failure carries neither containment-escape marker.
function benignError() {
  const e = new Error('EBUSY: resource busy or locked, unlink ' + CANDIDATE);
  e.code = 'EBUSY';
  return e;
}

function wipe(unlinkError, statExists, logFn = () => {}) {
  return () => wipeStalePortFileCandidate(CANDIDATE, FIXTURE_DIR, {
    unlink: () => { throw unlinkError; },
    statExists: () => statExists,
    logFn,
  });
}

test('wipeStalePortFileCandidate: benign failure + file STILL present fails loud (rf2-6i2yi4)', () => {
  assert.throws(wipe(benignError(), true), /STILL on disk/);
});

test('wipeStalePortFileCandidate: benign failure + file confirmed GONE does not throw (transient-lock happy path)', () => {
  const logs = [];
  assert.doesNotThrow(wipe(benignError(), false, (m) => logs.push(m)));
  assert.ok(logs.some((m) => m.includes('gone now')), 'logs the benign-but-resolved outcome');
});

test('wipeStalePortFileCandidate: containment-escape is fatal even if statExists would say gone', () => {
  const escape = new Error(
    `refusing to unlink ${CANDIDATE}: symlink-escape accident-gating — realpath resolves outside the allowed root`,
  );
  assert.throws(wipe(escape, false), /refused to clean/);
});
