// Completeness / anti-drift guard for the shared live-test inventory
// (`scripts/live-test-inventory.cjs`) against the live gate files on disk
// (rf2-79qmsw, rf2-phveub).
//
// Uses Node's built-in `node:test` (same posture as
// `inner-test-close-grading.test.cjs` — no extra dev-dependency). Runs in
// the pure-Node unit cluster: `scripts/test-unit.cjs` DERIVES its set from
// every `--test` row in `test-all.cjs`, so registering this file there (as a
// `--test` row) makes PR CI pick it up automatically.
//
// ## The drift this pins
//
// The live gates live in `test/live-re-frame2-pair-*.cjs`. They used to be
// named in TWO separate, hand-maintained lists (the hermetic runner's
// `INNER_TESTS` and `test-all.cjs`'s live rows), and nothing cross-checked
// the two against each other or against disk — so a forgotten entry could
// ride perpetually green (never launched on CI) or never be spawned at all.
//
// rf2-phveub collapsed those two lists onto ONE owner: the shared inventory
// (`scripts/live-test-inventory.cjs`). BOTH runners now DERIVE their rows from
// that array (the hermetic runner resolves each `basename` to an absolute
// path; `test-all.cjs` derives a SKIP-by-default row), so they can no longer
// disagree with each other. The only remaining drift is inventory-versus-disk:
//   - a new `live-re-frame2-pair-<new>.cjs` added to disk but forgotten in the
//     inventory → never spawned by `npm test`, never LAUNCHED on CI (the
//     hermetic job is the only place the live path fires) — rides perpetually
//     green as an un-exercised gate;
//   - a rename/delete that left a dangling inventory entry → the hermetic
//     suite would fail to spawn a file that no longer exists.
//
// The DISK glob is the source of truth; the inventory must cover it exactly.
// It requires only the inventory (a side-effect-free pure-data module — no
// runner, no shadow-cljs, no Chromium boots).

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const TEST_DIR = __dirname;
const SCRIPTS_DIR = path.join(TEST_DIR, '..', 'scripts');

const { LIVE_TESTS } = require(
  path.join(SCRIPTS_DIR, 'live-test-inventory.cjs'),
);

// The on-disk source of truth: every live gate file.
const LIVE_FILE_RE = /^live-re-frame2-pair-.*\.cjs$/;

function liveFilesOnDisk() {
  return fs
    .readdirSync(TEST_DIR)
    .filter((name) => LIVE_FILE_RE.test(name))
    .sort();
}

// The live basenames the inventory declares.
function inventoryBasenames() {
  return LIVE_TESTS.map((t) => t.basename)
    .filter((name) => LIVE_FILE_RE.test(name))
    .sort();
}

test('there is at least one live gate on disk (refuse to report green on an empty glob)', () => {
  const disk = liveFilesOnDisk();
  assert.ok(
    disk.length > 0,
    'no test/live-re-frame2-pair-*.cjs files found — either the glob broke or ' +
      'the live gates were removed; refusing to certify the inventory as ' +
      'complete against an empty disk set',
  );
});

test('every live gate on disk appears in the shared inventory (rf2-phveub / rf2-79qmsw)', () => {
  const disk = new Set(liveFilesOnDisk());
  const inventory = new Set(inventoryBasenames());
  const missing = [...disk].filter((f) => !inventory.has(f));
  assert.deepEqual(
    missing,
    [],
    'live gate file(s) present on disk but MISSING from the inventory in ' +
      'scripts/live-test-inventory.cjs — both runners derive their roster from ' +
      'the inventory, so a forgotten entry is never spawned by `npm test` and ' +
      'never LAUNCHED by the hermetic CI job (the only place the live path ' +
      'fires), riding perpetually green:\n  ' +
      missing.join('\n  '),
  );
});

test('the inventory references no live gate that is absent from disk (dangling entry after a rename/delete)', () => {
  const disk = new Set(liveFilesOnDisk());
  const dangling = inventoryBasenames().filter((f) => !disk.has(f));
  assert.deepEqual(
    dangling,
    [],
    'the inventory in scripts/live-test-inventory.cjs names live gate file(s) ' +
      'that no longer exist on disk — a rename/delete left a dangling entry ' +
      'both runners would derive a row for and the hermetic suite would fail ' +
      'to spawn:\n  ' +
      dangling.join('\n  '),
  );
});
