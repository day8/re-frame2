#!/usr/bin/env node
/*
 * Pure unit tests for `lib/g8-latency-evidence.cjs` and the pure teeth of
 * `run-ui-g8.cjs` (rf2-fnkmi — completing the canonical G-8).
 *
 * PR #6182 shipped the G-8 real-browser correctness matrix but not the two
 * canonical arms: real attributable React commit counts and the comparative
 * event-to-commit latency. This test pins, without spawning shadow-cljs or
 * Playwright:
 *
 *   1. the comparative-latency evidence math — the stated nearest-rank quantile
 *      convention, sample-shape validation, the evidence-only p95 budget
 *      comparison, and the step-summary rendering;
 *   2. the runner's correctness mutation teeth for the NEW arms — an extra React
 *      commit, an IME duplicate (revision or commit), and a vacuous commit
 *      counter must all redden the gate (`=== 1`, not `>= 1`);
 *   3. the budget mutation teeth — the evidence-only comparison bites on
 *      synthetic over-budget data without gating real measured latency.
 *
 * Standalone node runner, matching the sibling `_*.test.cjs` convention.
 */

'use strict';

const assert = require('assert/strict');

const {
  WARMUP_SAMPLES,
  RECORDED_SAMPLES,
  PERCENTILE_CONVENTION,
  RATIO_BUDGET,
  NOISE_POLICY,
  latencyPercentile,
  validateLatencySamples,
  summarizeLatency,
  withinBudget,
  p95Ratio,
  engineLatencyEvidence,
  buildPerformanceSummary,
} = require('./lib/g8-latency-evidence.cjs');

const {
  goldenResult,
  assertEngineResult,
  runMutationTeeth,
  runBudgetTeeth,
} = require('./run-ui-g8.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// A sorted 1..25 sample set — the canonical recorded-sample cardinality.
const TWENTY_FIVE = Array.from({ length: RECORDED_SAMPLES }, (_, i) => i + 1);
const fill25 = (v) => Array(RECORDED_SAMPLES).fill(v);

// ----- latencyPercentile: the stated nearest-rank convention -----------------

test('nearest-rank [1..25] yields p50=13 and p95=24 (rank ceil(p*n)-1, clamped)', () => {
  assert.equal(latencyPercentile(TWENTY_FIVE, 0.5), 13);
  assert.equal(latencyPercentile(TWENTY_FIVE, 0.95), 24);
});

test('p95 is order-independent (sorts a copy)', () => {
  const shuffled = [...TWENTY_FIVE].reverse();
  const copy = shuffled.slice();
  assert.equal(latencyPercentile(shuffled, 0.95), 24);
  assert.deepEqual(shuffled, copy, 'must not mutate the caller array');
});

test('latencyPercentile clamps the edges (p=0 -> min, p=1 -> max, n=1 -> sole value)', () => {
  assert.equal(latencyPercentile(TWENTY_FIVE, 0), 1);
  assert.equal(latencyPercentile(TWENTY_FIVE, 1), 25);
  assert.equal(latencyPercentile([4.2], 0.95), 4.2);
});

test('latencyPercentile rejects empty / non-finite / out-of-range p', () => {
  assert.throws(() => latencyPercentile([], 0.95), /non-empty/);
  assert.throws(() => latencyPercentile([1, NaN, 3], 0.5), /finite/);
  assert.throws(() => latencyPercentile(TWENTY_FIVE, 1.5), /\[0,1\]/);
});

// ----- validateLatencySamples: sample SHAPE, not a wall-clock threshold ------

test('validateLatencySamples accepts exactly RECORDED_SAMPLES finite non-negative samples', () => {
  assert.equal(RECORDED_SAMPLES, 25);
  assert.equal(WARMUP_SAMPLES, 5);
  assert.deepEqual(validateLatencySamples(TWENTY_FIVE.slice()), TWENTY_FIVE);
  assert.deepEqual(validateLatencySamples(fill25(0)), fill25(0)); // zero ms is valid
});

test('validateLatencySamples rejects the wrong sample count (too few and too many)', () => {
  assert.throws(() => validateLatencySamples(fill25(1).slice(0, 24), 'chromium'), /exactly 25/);
  assert.throws(() => validateLatencySamples([...fill25(1), 1], 'chromium'), /exactly 25/);
});

test('validateLatencySamples rejects a non-finite or negative member and names the index', () => {
  const withNaN = fill25(1);
  withNaN[7] = NaN;
  assert.throws(() => validateLatencySamples(withNaN, 'webkit'), /\[7\] is not a finite number/);
  const negative = fill25(1);
  negative[3] = -0.5;
  assert.throws(() => validateLatencySamples(negative, 'webkit'), /\[3\] is negative/);
});

test('validateLatencySamples rejects a non-array and carries the call-site label', () => {
  assert.throws(() => validateLatencySamples(null, 'chromium compiled'), /not an array/);
  assert.throws(() => validateLatencySamples(null, 'chromium compiled'), /chromium compiled/);
});

// ----- summarizeLatency ------------------------------------------------------

test('summarizeLatency preserves the raw array alongside p50/p95', () => {
  const s = summarizeLatency(TWENTY_FIVE.slice());
  assert.deepEqual(s['raw-ms'], TWENTY_FIVE);
  assert.equal(s['p50-ms'], 13);
  assert.equal(s['p95-ms'], 24);
});

// ----- withinBudget / p95Ratio: EVIDENCE-ONLY comparison ---------------------

test('withinBudget: compiled p95 within RATIO_BUDGET x baseline is true at the boundary', () => {
  assert.equal(RATIO_BUDGET, 1.1);
  assert.equal(withinBudget(1.1, 1.0), true); // exactly 1.10x — inclusive
  assert.equal(withinBudget(1.0, 1.0), true);
  assert.equal(withinBudget(0.5, 1.0), true);
});

test('withinBudget: compiled p95 above the budget is false (the comparison bites)', () => {
  assert.equal(withinBudget(1.11, 1.0), false);
  assert.equal(withinBudget(2.0, 1.0), false);
});

test('withinBudget rejects a zero / non-positive baseline as a broken measurement', () => {
  assert.throws(() => withinBudget(1.0, 0), /positive finite/);
  assert.throws(() => withinBudget(1.0, -1), /positive finite/);
  assert.throws(() => withinBudget(NaN, 1.0), /non-negative finite/);
});

test('p95Ratio is compiled/baseline and rejects a zero baseline', () => {
  assert.equal(p95Ratio(1.5, 1.0), 1.5);
  assert.throws(() => p95Ratio(1.0, 0), /positive finite/);
});

// ----- engineLatencyEvidence -------------------------------------------------

test('engineLatencyEvidence reports p95s, ratio, and the within-10% observation', () => {
  const e = engineLatencyEvidence('chromium', fill25(1.0), fill25(1.0));
  assert.equal(e.engine, 'chromium');
  assert.equal(e['samples-recorded'], 25);
  assert.equal(e['warmups-discarded'], 5);
  assert.equal(e['ratio-budget'], 1.1);
  assert.equal(e.compiled['p95-ms'], 1.0);
  assert.equal(e.handwritten['p95-ms'], 1.0);
  assert.equal(e['p95-ratio'], 1.0);
  assert.equal(e['within-10pct'], true);
});

test('engineLatencyEvidence flips within-10% to false when compiled p95 crosses the budget', () => {
  const e = engineLatencyEvidence('webkit', fill25(2.0), fill25(1.0));
  assert.equal(e['p95-ratio'], 2.0);
  assert.equal(e['within-10pct'], false);
});

test('engineLatencyEvidence validates BOTH raw arrays (a malformed one fails loudly)', () => {
  assert.throws(() => engineLatencyEvidence('chromium', fill25(1).slice(0, 10), fill25(1)), /exactly 25/);
  assert.throws(() => engineLatencyEvidence('chromium', fill25(1), fill25(1).slice(0, 10)), /exactly 25/);
});

// ----- buildPerformanceSummary: evidence-only posture + raw visible ----------

function twoEngineEvidence() {
  return [
    engineLatencyEvidence('chromium', fill25(1.2), fill25(1.1)),
    engineLatencyEvidence('webkit', fill25(1.4), fill25(1.3)),
  ];
}

test('buildPerformanceSummary states the evidence-only posture and the conventions', () => {
  const md = buildPerformanceSummary(twoEngineEvidence());
  assert.match(md, /EVIDENCE ONLY/);
  assert.match(md, /no wall-clock threshold/);
  assert.equal(md.includes(PERCENTILE_CONVENTION), true);
  assert.equal(md.includes(NOISE_POLICY), true);
});

test('buildPerformanceSummary emits a per-engine p95 + ratio row and exposes the raw samples', () => {
  const md = buildPerformanceSummary(twoEngineEvidence());
  assert.match(md, /\| chromium \| 1\.200 \| 1\.100 \| 1\.091 \| ✓ \|/);
  assert.match(md, /\| webkit \| 1\.400 \| 1\.300 \| 1\.077 \| ✓ \|/);
  // Raw distributions must be present, not just the two percentiles.
  assert.match(md, /chromium compiled/);
  assert.match(md, /webkit hand-written/);
});

test('buildPerformanceSummary rejects an empty per-engine set', () => {
  assert.throws(() => buildPerformanceSummary([]), /non-empty/);
});

// ----- runner correctness teeth: the NEW commit + IME arms bite --------------

test('the golden result passes assertEngineResult (green-after baseline)', () => {
  assert.doesNotThrow(() => assertEngineResult('golden', goldenResult()));
});

test('runMutationTeeth returns a label for every tooth (all bit — none stayed green)', () => {
  const teeth = runMutationTeeth();
  assert.ok(Array.isArray(teeth) && teeth.length >= 13, `expected the full teeth set, got ${teeth.length}`);
  // The NEW commit-count + IME-exact teeth must be present and have bit.
  for (const needle of [
    /extra React commit/,
    /commit counter vacuous/,
    /duplicate revision/,
    /duplicate React commit/,
    /IME commit counter vacuous/,
  ]) {
    assert.ok(teeth.some((t) => needle.test(t)), `missing a tooth matching ${needle}`);
  }
});

test('assertEngineResult rejects an extra React commit for one ordinary input', () => {
  const r = goldenResult();
  r.sync['ordinary-commit-delta'] = 2;
  assert.throws(() => assertEngineResult('mutant', r), /not exactly one/);
});

test('assertEngineResult rejects a vacuous (0) ordinary commit counter', () => {
  const r = goldenResult();
  r.sync['ordinary-commit-delta'] = 0;
  assert.throws(() => assertEngineResult('mutant', r), /vacuous counter reports 0/);
});

test('assertEngineResult rejects an IME duplicate revision (>= 1 would have accepted it)', () => {
  const r = goldenResult();
  r.sync['ime-revision-delta'] = 2;
  assert.throws(() => assertEngineResult('mutant', r), /not exactly once/);
});

test('assertEngineResult rejects an IME duplicate React commit', () => {
  const r = goldenResult();
  r.sync['ime-commit-delta'] = 2;
  assert.throws(() => assertEngineResult('mutant', r), /not exactly one/);
});

// ----- runner budget teeth: non-vacuous, evidence-only -----------------------

test('runBudgetTeeth proves the comparison bites without gating real timing', () => {
  const teeth = runBudgetTeeth();
  assert.ok(Array.isArray(teeth) && teeth.length === 3, `expected 3 budget teeth, got ${teeth.length}`);
  assert.ok(teeth.some((t) => /within 10%/.test(t)));
  assert.ok(teeth.some((t) => /over-budget compiled p95 \(2x baseline\) flagged outside 10%/.test(t)));
  assert.ok(teeth.some((t) => /zero baseline p95 is rejected/.test(t)));
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
  console.error(`g8-latency-evidence tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`g8-latency-evidence tests: ${tests.length} passed.`);
