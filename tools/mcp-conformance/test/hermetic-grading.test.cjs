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

test('only a CLEAN report emits the pass sentinel and exits 0; a DIRTY one flushes diagnostics and exits 2 (rf2-j538f7.19 AC7)', () => {
  for (const [report, expected] of [
    [{ clean: true }, { code: 0, sentinels: 1, flushed: 0 }],
    [{ clean: false, browser: { state: 'dirty' }, shadow: { state: 'alive' }, issues: [] },
      { code: 2, sentinels: 0, flushed: 1 }],
  ]) {
    assert.deepEqual(finalize(report), expected);
  }
});
