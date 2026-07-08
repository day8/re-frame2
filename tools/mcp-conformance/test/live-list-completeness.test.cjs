// Completeness / anti-drift guard for the hermetic live-suite's
// INNER_TESTS inventory and test-all.cjs's live rows (rf2-79qmsw).
//
// Uses Node's built-in `node:test` (same posture as
// `inner-test-close-grading.test.cjs` — no extra dev-dependency). Runs in
// the pure-Node unit cluster: `scripts/test-unit.cjs` DERIVES its set from
// every `--test` row in `test-all.cjs`, so registering this file there (as a
// `--test` row) makes PR CI pick it up automatically.
//
// ## The drift this pins
//
// The live gates live in `test/live-re-frame2-pair-*.cjs`. They are named in
// TWO separate, hand-maintained lists:
//
//   1. `scripts/run-re-frame2-pair-live-hermetic-suite.cjs` `INNER_TESTS` —
//      the ONLY place the live path actually FIRES (the hermetic suite boots
//      shadow-cljs, sets `$SHADOW_CLJS_NREPL_PORT`, and spawns each entry).
//   2. `scripts/test-all.cjs` `TESTS` live rows — spawned by `npm test`,
//      where they SKIP-by-default (no `$SHADOW_CLJS_NREPL_PORT`) and ride
//      green.
//
// Nothing derives either list from the files on disk, and nothing
// cross-checks the two against each other. So a future
// `live-re-frame2-pair-<new>.cjs` added to ONE list but forgotten in the
// other:
//   - forgotten in `test-all.cjs` → never spawned by `npm test` at all;
//   - forgotten in `INNER_TESTS`  → never LAUNCHED on CI (the hermetic job is
//     the only place the live path runs) — it rides perpetually green as an
//     un-exercised gate.
//
// The `inner-test-close-grading` sentinel guard (rf2-6girz0) cannot catch
// this: it only inspects tests already IN `INNER_TESTS`. This is the
// silent-SKIP false-green class one meta-level up. The `test-unit.cjs`
// derive-don't-re-list discipline is the fix, applied here as an assertion:
// the DISK glob is the source of truth; both lists must cover it exactly.
//
// It requires both scripts AS MODULES (the orchestrator's auto-run is guarded
// behind `require.main === module`, and `test-all.cjs` only runs `main()`
// under the same guard, so requiring them boots nothing).

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const TEST_DIR = __dirname;
const SCRIPTS_DIR = path.join(TEST_DIR, '..', 'scripts');

const { INNER_TESTS } = require(
  path.join(SCRIPTS_DIR, 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);
const { TESTS } = require(path.join(SCRIPTS_DIR, 'test-all.cjs'));

// The on-disk source of truth: every live gate file.
const LIVE_FILE_RE = /^live-re-frame2-pair-.*\.cjs$/;

function liveFilesOnDisk() {
  return fs
    .readdirSync(TEST_DIR)
    .filter((name) => LIVE_FILE_RE.test(name))
    .sort();
}

// The live basenames each list references. `INNER_TESTS` entries carry an
// absolute `.path`; `test-all.cjs` live rows are `{ argv: ['test/<file>'] }`
// (no `--test`) — take the last argv element and keep only the live gates.
function innerTestBasenames() {
  return INNER_TESTS.map((t) => path.basename(t.path))
    .filter((name) => LIVE_FILE_RE.test(name))
    .sort();
}

function testAllLiveBasenames() {
  return TESTS.map((t) => path.basename(t.argv[t.argv.length - 1]))
    .filter((name) => LIVE_FILE_RE.test(name))
    .sort();
}

test('there is at least one live gate on disk (refuse to report green on an empty glob)', () => {
  const disk = liveFilesOnDisk();
  assert.ok(
    disk.length > 0,
    'no test/live-re-frame2-pair-*.cjs files found — either the glob broke or ' +
      'the live gates were removed; refusing to certify the lists as complete ' +
      'against an empty disk set',
  );
});

test('every live gate on disk appears in the hermetic INNER_TESTS inventory (rf2-79qmsw)', () => {
  const disk = new Set(liveFilesOnDisk());
  const inner = new Set(innerTestBasenames());
  const missing = [...disk].filter((f) => !inner.has(f));
  assert.deepEqual(
    missing,
    [],
    'live gate file(s) present on disk but MISSING from `INNER_TESTS` in ' +
      'run-re-frame2-pair-live-hermetic-suite.cjs — the hermetic CI job is the ' +
      'only place the live path fires, so a forgotten entry never launches and ' +
      'rides perpetually green:\n  ' +
      missing.join('\n  '),
  );
});

test('every live gate on disk appears in the test-all.cjs live rows (rf2-79qmsw)', () => {
  const disk = new Set(liveFilesOnDisk());
  const testAll = new Set(testAllLiveBasenames());
  const missing = [...disk].filter((f) => !testAll.has(f));
  assert.deepEqual(
    missing,
    [],
    'live gate file(s) present on disk but MISSING from the `TESTS` live rows ' +
      'in test-all.cjs — a forgotten entry is never spawned by `npm test` at ' +
      'all:\n  ' +
      missing.join('\n  '),
  );
});

test('INNER_TESTS references no live gate that is absent from disk (dangling entry after a rename/delete)', () => {
  const disk = new Set(liveFilesOnDisk());
  const dangling = innerTestBasenames().filter((f) => !disk.has(f));
  assert.deepEqual(
    dangling,
    [],
    '`INNER_TESTS` names live gate file(s) that no longer exist on disk — a ' +
      'rename/delete left a dangling entry the hermetic suite would fail to ' +
      'spawn:\n  ' +
      dangling.join('\n  '),
  );
});

test('test-all.cjs live rows reference no live gate that is absent from disk (dangling row after a rename/delete)', () => {
  const disk = new Set(liveFilesOnDisk());
  const dangling = testAllLiveBasenames().filter((f) => !disk.has(f));
  assert.deepEqual(
    dangling,
    [],
    'test-all.cjs names live gate file(s) that no longer exist on disk — a ' +
      'rename/delete left a dangling row:\n  ' +
      dangling.join('\n  '),
  );
});
