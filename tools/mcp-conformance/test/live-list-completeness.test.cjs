// Both live runners derive their rows from the shared inventory
// (`scripts/live-test-inventory.cjs`), so a live gate missing from it never
// launches, and an entry with no file on disk fails the hermetic suite.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

const { LIVE_TESTS } = require('../scripts/live-test-inventory.cjs');

test('the shared live-test inventory lists exactly the live gates on disk', () => {
  const disk = fs
    .readdirSync(__dirname)
    .filter((name) => /^live-re-frame2-pair-.*\.cjs$/.test(name))
    .sort();
  assert.deepEqual(LIVE_TESTS.map((t) => t.basename).sort(), disk);
});
