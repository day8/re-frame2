// `finalizeConformance` maps a settled cleanup report to the hermetic run's
// outcome: the pass sentinel and exit 0 only for a teardown proved clean,
// otherwise no sentinel and orchestration exit 2. How `makeCleanup` grades
// the report itself is pinned in `runner-cleanup.test.cjs`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { finalizeConformance } = require(
  path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);

function finalize(report) {
  let sentinels = 0;
  let flushed = 0;
  const code = finalizeConformance(report, {
    emitPass: () => { sentinels += 1; },
    log: () => {},
    logErr: () => {},
    flush: () => { flushed += 1; },
    count: 6,
  });
  return { code, sentinels, flushed };
}

test('CLEAN report ⇒ pass sentinel emitted exactly once and exit 0 (rf2-j538f7.19 AC7)', () => {
  assert.deepEqual(finalize({ clean: true }), { code: 0, sentinels: 1, flushed: 0 });
});

test('DIRTY report ⇒ NO pass sentinel, diagnostics flushed and exit 2 (rf2-j538f7.19 AC7)', () => {
  assert.deepEqual(
    finalize({ clean: false, browser: { state: 'dirty' }, shadow: { state: 'alive' }, issues: [] }),
    { code: 2, sentinels: 0, flushed: 1 },
  );
});
