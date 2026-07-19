#!/usr/bin/env node
/*
 * Pure unit tests for `lib/g13-timing-evidence.cjs` (rf2-vxgfnd.213).
 *
 * G-13's timing evidence was mislabelled in three ways (see the bead):
 *   1. the reported span ran dispatch-through-audit, not dispatch-to-commit;
 *   2. the percentile used `floor(p*(n-1))`, an unstated convention that made
 *      p95 the SECOND-largest of nine samples (V=100 max 11.5ms but reported
 *      p95 11ms) instead of the nearest-rank maximum;
 *   3. the CI step summary emitted only cold/p50/p95, hiding the raw samples
 *      the artifact already carried.
 *
 * The span fix lives in the browser fixture (dev.cljs) and is exercised by the
 * G-13 browser gate. The quantile convention, sample validation, and summary
 * rendering are pure and pinned here so a regression can be caught without
 * spawning shadow-cljs or Playwright. Standalone node runner, matching the
 * sibling `_*.test.cjs` convention.
 *
 * rf2-muhsq — the source-text half of the containment proof used to live here
 * too: a Clojure string/comment blanker, private-defn delimiters, and exact
 * form/multiplicity regexes over `dev.cljs`, duplicated again in
 * `run-ui-g13.cjs`. Containment is now a property of the measurement seam
 * (`re-frame.ui.g13.measure/measure-dispatch-to-commit!`), whose call order is
 * asserted directly in `re-frame.ui.g13.measure-cljs-test`. What remains here
 * is `assertTimedIntervalDidWork` — the runtime half — which, now that the
 * seam owns both witness reads, also rejects a dispatch hoisted out of the
 * flushed thunk.
 */

'use strict';

const assert = require('assert/strict');

const {
  RECORDED_SAMPLES,
  PERCENTILE_CONVENTION,
  COLD_FIRST_DRAIN_PRE_HOT,
  warmPercentile,
  validateWarmSamples,
  summarizeWarm,
  assertColdIsFirstDrain,
  assertColdOrderControl,
  assertTimedIntervalDidWork,
  buildSummary,
} = require('./lib/g13-timing-evidence.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

const NINE = [1, 2, 3, 4, 5, 6, 7, 8, 9];

// ----- warmPercentile: the stated nearest-rank convention --------------------

test('nearest-rank [1..9] yields p50=5 and p95=9 (the maximum, not the runner-up)', () => {
  // The bead's exact acceptance value. `floor(p*(n-1))` returned p95=8 here
  // (the second-largest); nearest-rank `ceil(p*n)-1` returns the maximum.
  assert.equal(warmPercentile(NINE, 0.5), 5);
  assert.equal(warmPercentile(NINE, 0.95), 9);
});

test('p95 of nine samples is the maximum regardless of input order', () => {
  const shuffled = [9, 1, 8, 2, 7, 3, 6, 4, 5];
  assert.equal(warmPercentile(shuffled, 0.95), 9);
  assert.equal(warmPercentile(shuffled, 0.5), 5);
});

test('p95 is the maximum, reproducing the CI artifact contradiction fix', () => {
  // Regression against the merged green artifact: V=100 maximum 11.5ms but a
  // reported p95 of 11ms. Nearest-rank must report the maximum as p95.
  const samples = [8, 9, 9.5, 10, 10.5, 11, 11.5, 8.5, 9.2];
  assert.equal(warmPercentile(samples, 0.95), 11.5);
});

test('warmPercentile does not mutate the caller array', () => {
  const input = [3, 1, 2];
  const copy = input.slice();
  warmPercentile(input, 0.95);
  assert.deepEqual(input, copy);
});

test('warmPercentile clamps the edges (p=0 -> min, p=1 -> max, n=1 -> the sole value)', () => {
  assert.equal(warmPercentile(NINE, 0), 1);
  assert.equal(warmPercentile(NINE, 1), 9);
  assert.equal(warmPercentile([42], 0.95), 42);
});

test('warmPercentile rejects an empty sample set', () => {
  assert.throws(() => warmPercentile([], 0.95), /non-empty/);
});

test('warmPercentile rejects a non-finite sample', () => {
  assert.throws(() => warmPercentile([1, NaN, 3], 0.5), /finite/);
  assert.throws(() => warmPercentile([1, Infinity, 3], 0.5), /finite/);
});

test('warmPercentile rejects an out-of-range p', () => {
  assert.throws(() => warmPercentile(NINE, 1.5), /\[0,1\]/);
  assert.throws(() => warmPercentile(NINE, -0.1), /\[0,1\]/);
});

// ----- validateWarmSamples: reject empty / malformed / wrong-count -----------

test('validateWarmSamples accepts exactly nine finite samples', () => {
  assert.equal(RECORDED_SAMPLES, 9);
  assert.deepEqual(validateWarmSamples(NINE.slice()), NINE);
});

test('validateWarmSamples rejects a non-array', () => {
  assert.throws(() => validateWarmSamples(null, 'V=100'), /not an array/);
  assert.throws(() => validateWarmSamples('nope', 'V=100'), /not an array/);
});

test('validateWarmSamples rejects the wrong sample count (too few and too many)', () => {
  assert.throws(() => validateWarmSamples([1, 2, 3, 4, 5, 6, 7, 8], 'V=100'), /exactly 9/);
  assert.throws(
    () => validateWarmSamples([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 'V=100'),
    /exactly 9/,
  );
});

test('validateWarmSamples rejects a non-finite / non-numeric member and names the index', () => {
  const withNaN = [1, 2, 3, 4, NaN, 6, 7, 8, 9];
  assert.throws(() => validateWarmSamples(withNaN, 'V=500'), /\[4\] is not a finite number/);
  const withString = [1, 2, 3, 4, 5, 6, 7, 8, '9'];
  assert.throws(() => validateWarmSamples(withString, 'V=500'), /\[8\] is not a finite number/);
});

test('validateWarmSamples messages carry the call-site label', () => {
  assert.throws(() => validateWarmSamples([], 'V=100 warm timing evidence'), /V=100 warm timing evidence/);
});

// ----- summarizeWarm ---------------------------------------------------------

test('summarizeWarm preserves the raw array alongside p50/p95', () => {
  const s = summarizeWarm(NINE.slice());
  assert.deepEqual(s['raw-ms'], NINE);
  assert.equal(s['p50-ms'], 5);
  assert.equal(s['p95-ms'], 9);
});

test('summarizeWarm rejects malformed input (delegates to the validator)', () => {
  assert.throws(() => summarizeWarm([1, 2, 3]), /exactly 9/);
});

// ----- buildSummary: raw samples + cold + p50 + p95 are all VISIBLE ----------

function twoSizeResults() {
  return [
    { v: 100, cold: { 'elapsed-ms': 6.25 }, warm: { 'raw-ms': [7, 8, 9, 10, 11, 8.5, 9.5, 10.5, 11.5] } },
    { v: 500, cold: { 'elapsed-ms': 7.1 }, warm: { 'raw-ms': [8, 9, 10, 11, 12, 9.5, 10.5, 11.5, 13] } },
  ];
}

test('buildSummary emits the p50/p95 table row for both sizes', () => {
  const md = buildSummary(twoSizeResults());
  // p95 = maximum (nearest-rank): 11.5 for V=100, 13 for V=500.
  assert.match(md, /\| 100 \| 6\.250 \| 9\.500 \| 11\.500 \| yes \|/);
  assert.match(md, /\| 500 \| 7\.100 \| 10\.500 \| 13\.000 \| yes \|/);
});

test('buildSummary exposes the RAW warm sample arrays for both sizes (the visibility fix)', () => {
  const md = buildSummary(twoSizeResults());
  // Every raw sample must be printed, not just the two percentiles.
  assert.match(md, /V=100.*warm \[7\.000, 8\.000, 9\.000, 10\.000, 11\.000, 8\.500, 9\.500, 10\.500, 11\.500\]/);
  assert.match(md, /V=500.*warm \[8\.000, 9\.000, 10\.000, 11\.000, 12\.000, 9\.500, 10\.500, 11\.500, 13\.000\]/);
});

test('buildSummary surfaces the cold sample for both sizes', () => {
  const md = buildSummary(twoSizeResults());
  assert.match(md, /\*\*V=100\*\* — cold 6\.250/);
  assert.match(md, /\*\*V=500\*\* — cold 7\.100/);
});

test('buildSummary states the quantile convention plainly and the evidence-only posture', () => {
  const md = buildSummary(twoSizeResults());
  assert.match(md, /nearest-rank/);
  assert.equal(md.includes(PERCENTILE_CONVENTION), true);
  assert.match(md, /EVIDENCE ONLY/);
  assert.match(md, /no wall-clock threshold/);
});

test('buildSummary validates each size (a malformed raw array fails loudly)', () => {
  const bad = twoSizeResults();
  bad[1].warm['raw-ms'] = [1, 2, 3];
  assert.throws(() => buildSummary(bad), /exactly 9/);
});

// ----- assertColdIsFirstDrain: the cold-order control (rf2-52isf) ------------

test('COLD_FIRST_DRAIN_PRE_HOT is the mount-seed :hot value (0)', () => {
  // The fixture seeds app-db :hot=0 at mount; the cold timer must fire before
  // any drain advances it, so the cold sample's timing-pre-hot must equal 0.
  assert.equal(COLD_FIRST_DRAIN_PRE_HOT, 0);
});

test('assertColdIsFirstDrain accepts a cold sample captured before any drain', () => {
  const cold = { label: 'cold', 'elapsed-ms': 6.25, 'timing-pre-hot': 0 };
  assert.equal(assertColdIsFirstDrain(cold), cold);
});

test('assertColdIsFirstDrain rejects correctness running before the cold timer', () => {
  // A single correctness drain would have advanced :hot from 0 to queued-writes
  // (8) before the cold timer — the reported "cold" span is then a warmed second
  // dispatch, and the control must turn red naming the ordering fault.
  const warmed = { label: 'cold', 'elapsed-ms': 6.25, 'timing-pre-hot': 8 };
  assert.throws(
    () => assertColdIsFirstDrain(warmed, 'V=100 cold timing evidence'),
    /correctness ran before the cold timer/,
  );
  assert.throws(
    () => assertColdIsFirstDrain(warmed, 'V=100 cold timing evidence'),
    /V=100 cold timing evidence/,
  );
});

test('assertColdIsFirstDrain rejects any nonzero pre-hot (a later warmed drain)', () => {
  assert.throws(
    () => assertColdIsFirstDrain({ 'timing-pre-hot': 16 }),
    /first post-mount dispatch/,
  );
});

test('assertColdIsFirstDrain rejects a missing pre-hot witness', () => {
  assert.throws(() => assertColdIsFirstDrain({ label: 'cold' }), /first post-mount dispatch/);
  assert.throws(() => assertColdIsFirstDrain(null, 'V=500'), /cold sample is missing/);
});

// ----- assertColdOrderControl: the CAUSAL cold-first control (rf2-6k4cm) ------
//
// The fixture runs both cycle orders on fresh frames and reports each TIMED
// cycle's own pre-hot witness. This control asserts the two orders DIVERGE — the
// only evidence that the witness is sourced from the timed dispatch, not from a
// point (sample! entry) that stays 0 regardless of which cycle the timer measures.

const QUEUED_WRITES = 8;

// rf2-a0i2y — each order path now reports its timed cycle's witness PAIR, so the
// path proves both which state preceded its dispatch AND that its own measured
// interval contained that dispatch and its commit.
function pair(preHot) {
  return { 'pre-hot': preHot, 'post-hot': preHot + QUEUED_WRITES };
}

test('assertColdOrderControl accepts the causal 0 / queued-writes divergence', () => {
  // Real inverted execution advances :hot by queued-writes before the timer, so a
  // causal witness reports 0 (timing-first) vs 8 (correctness-first).
  const order = { 'timing-first': pair(0), 'correctness-first': pair(QUEUED_WRITES) };
  assert.equal(assertColdOrderControl(order, QUEUED_WRITES), order);
});

test('assertColdOrderControl REJECTS a correctness-first witness that stayed 0 (the vacuous, non-causal control)', () => {
  // THE TEETH. A witness captured before the timed dispatch stays 0 in BOTH
  // orders — the exact false-green this bead repairs. It must be rejected even
  // though the per-sample cold-first assertion (0 === 0) would still pass.
  const collapsed = { 'timing-first': pair(0), 'correctness-first': pair(0) };
  assert.throws(
    () => assertColdOrderControl(collapsed, QUEUED_WRITES, 'V=100 cold-first order control'),
    /non-causal, vacuous cold-first control/,
  );
  assert.throws(
    () => assertColdOrderControl(collapsed, QUEUED_WRITES, 'V=100 cold-first order control'),
    /V=100 cold-first order control/,
  );
});

test('assertColdOrderControl rejects a correctness-first witness that advanced by the wrong amount', () => {
  assert.throws(
    () => assertColdOrderControl({ 'timing-first': pair(0), 'correctness-first': pair(16) }, QUEUED_WRITES),
    /did not advance to queued-writes/,
  );
});

test('assertColdOrderControl rejects a timing-first witness that left the mount seed', () => {
  // If the timed cycle did NOT run first, its witness is nonzero and the reported
  // "cold" span is a warmed drain — the control must reject it.
  assert.throws(
    () => assertColdOrderControl({ 'timing-first': pair(8), 'correctness-first': pair(QUEUED_WRITES) }, QUEUED_WRITES),
    /timing-first witness is not the mount seed/,
  );
});

test('assertColdOrderControl rejects a missing order control', () => {
  assert.throws(() => assertColdOrderControl(null, QUEUED_WRITES), /order control is missing/);
  assert.throws(() => assertColdOrderControl(undefined, QUEUED_WRITES, 'V=500'), /V=500/);
});

test('assertColdOrderControl rejects a non-positive queued-writes basis', () => {
  assert.throws(
    () => assertColdOrderControl({ 'timing-first': pair(0), 'correctness-first': pair(8) }, 0),
    /queued-writes must be a positive finite number/,
  );
});

test('assertColdOrderControl rejects an order path whose timed interval did no work', () => {
  // rf2-a0i2y — the divergence can be perfectly causal on a path that measured an
  // EMPTY interval; each path must carry its own containment proof.
  assert.throws(
    () => assertColdOrderControl(
      { 'timing-first': { 'pre-hot': 0, 'post-hot': 0 }, 'correctness-first': pair(QUEUED_WRITES) },
      QUEUED_WRITES,
    ),
    /\(timing-first\): the measured interval did not contain its own dispatch and commit/,
  );
  assert.throws(
    () => assertColdOrderControl(
      { 'timing-first': pair(0), 'correctness-first': { 'pre-hot': 8, 'post-hot': 8 } },
      QUEUED_WRITES,
    ),
    /\(correctness-first\): the measured interval did not contain/,
  );
});

test('assertColdOrderControl rejects a path degraded back to a witness-free scalar', () => {
  // The pre-rf2-a0i2y shape reported one number per path. A result still carrying
  // it has no closing witness at all and must not be accepted.
  assert.throws(
    () => assertColdOrderControl({ 'timing-first': 0, 'correctness-first': 8 }, QUEUED_WRITES),
    /did not report a timed-cycle witness pair/,
  );
});

// ----- assertTimedIntervalDidWork: the containment witness (rf2-a0i2y) -------
//
// The gap this closes: every other check reads state from BEFORE the timed
// dispatch, so removing that dispatch entirely leaves them all green — the
// separate untimed correctness cycle advances app-db and supplies every
// projection either way, and the runner labels an empty flush interval
// "dispatch-to-commit".

test('assertTimedIntervalDidWork accepts an interval that advanced :hot by queued-writes', () => {
  assert.equal(assertTimedIntervalDidWork(0, 8, QUEUED_WRITES), 8);
  assert.equal(assertTimedIntervalDidWork(24, 32, QUEUED_WRITES), 8);
});

test('assertTimedIntervalDidWork REJECTS an empty measured interval (the bead\'s false-green)', () => {
  // The exact defect: `timing-cycle!` dispatches nothing, so its own post-commit
  // witness never advances, while pre-hot still reports the mount seed.
  assert.throws(
    () => assertTimedIntervalDidWork(0, 0, QUEUED_WRITES, 'V=100 cold timing evidence'),
    /the measured interval did not contain its own dispatch and commit/,
  );
  assert.throws(
    () => assertTimedIntervalDidWork(0, 0, QUEUED_WRITES, 'V=100 cold timing evidence'),
    /V=100 cold timing evidence/,
  );
});

test('assertTimedIntervalDidWork rejects a partial drain and an over-long interval', () => {
  assert.throws(() => assertTimedIntervalDidWork(0, 7, QUEUED_WRITES), /delta=7, expected 8/);
  assert.throws(() => assertTimedIntervalDidWork(0, 16, QUEUED_WRITES), /delta=16, expected 8/);
});

test('assertTimedIntervalDidWork rejects a missing or non-finite witness', () => {
  assert.throws(
    () => assertTimedIntervalDidWork(0, undefined, QUEUED_WRITES),
    /post-hot witness is missing or not a finite number/,
  );
  assert.throws(
    () => assertTimedIntervalDidWork(undefined, 8, QUEUED_WRITES),
    /pre-hot witness is missing or not a finite number/,
  );
  assert.throws(() => assertTimedIntervalDidWork(0, NaN, QUEUED_WRITES), /not a finite number/);
});

test('assertTimedIntervalDidWork rejects a non-positive queued-writes basis', () => {
  assert.throws(
    () => assertTimedIntervalDidWork(0, 0, 0),
    /queued-writes must be a positive finite number/,
  );
});

// ----- runner ----------------------------------------------------------------

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`g13-timing-evidence tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`g13-timing-evidence tests: ${tests.length} passed.`);
