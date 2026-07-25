#!/usr/bin/env node
/*
 * Pure unit tests for `lib/g8-latency-evidence.cjs` (rf2-fnkmi — completing the
 * canonical G-8).
 *
 * PR #6182 shipped the G-8 real-browser correctness matrix but not the two
 * canonical arms: real attributable React commit counts and the comparative
 * event-to-commit latency. This test pins the comparative-latency evidence
 * MATH — the stated nearest-rank quantile convention, sample-shape validation,
 * the evidence-only p95 budget comparison, the rf2-dpwel two-channel shape
 * (commit = true event-to-commit via the arm's Profiler onRender endpoint;
 * settlement = the former input-to-quiescence metric, retained for
 * comparison), and the step-summary rendering — without spawning shadow-cljs
 * or Playwright.
 *
 * PURE by construction, exactly like the sibling `_g13-timing-evidence.test.cjs`:
 * it requires ONLY `assert/strict` and the pure `lib/g8-latency-evidence.cjs`
 * (no node_modules), so it runs in the lightweight "JS harness self-tests"
 * CI job, which installs no dependencies. The runner's correctness + budget
 * MUTATION TEETH (extra React commit, IME duplicate, vacuous commit counter,
 * over-budget latency) live in `run-ui-g8.cjs` — which requires Playwright — and
 * are exercised with the SAME red-before/green-after discipline at the START of
 * the actual G-8 browser gate: `runMutationTeeth` + `runBudgetTeeth` run before
 * any browser and throw if a tooth fails to bite, and the report records the
 * labels that bit. They are therefore not re-run here.
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
  ZERO_BASELINE_REASON,
  CHANNELS,
  latencyPercentile,
  validateLatencySamples,
  summarizeLatency,
  withinBudget,
  p95Ratio,
  engineLatencyEvidence,
  engineLatencyChannels,
  buildPerformanceSummary,
} = require('./lib/g8-latency-evidence.cjs');

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

// ----- rf2-yumaq: a baseline that is TOO FAST must not redden the gate -------

test('engineLatencyEvidence SKIPS the comparison when the baseline p95 is 0 (too fast to compare)', () => {
  const e = engineLatencyEvidence('chromium', fill25(1.0), fill25(0));
  assert.equal(e.comparison, 'skipped');
  assert.equal(e['p95-ratio'], null);
  assert.equal(e['within-10pct'], null); // NOT true — a skip is never a verdict
  assert.equal(e['comparison-reason'], ZERO_BASELINE_REASON);
  assert.match(e['comparison-reason'], /too fast to compare/);
  // Both distributions are still recorded — the skip hides nothing.
  assert.equal(e.compiled['p95-ms'], 1.0);
  assert.equal(e.handwritten['p95-ms'], 0);
  assert.deepEqual(e.handwritten['raw-ms'], fill25(0));
});

test('engineLatencyEvidence marks a real comparison as compared, with no skip reason', () => {
  const e = engineLatencyEvidence('webkit', fill25(1.0), fill25(1.0));
  assert.equal(e.comparison, 'compared');
  assert.equal(e['comparison-reason'], null);
});

test('a zero COMPILED p95 still compares — only the denominator can be missing', () => {
  const e = engineLatencyEvidence('chromium', fill25(0), fill25(1.0));
  assert.equal(e.comparison, 'compared');
  assert.equal(e['p95-ratio'], 0);
  assert.equal(e['within-10pct'], true);
});

test('withinBudget / p95Ratio stay STRICT on a zero denominator (the runner tooth still bites)', () => {
  assert.throws(() => withinBudget(1.0, 0), /positive finite/);
  assert.throws(() => p95Ratio(1.0, 0), /positive finite/);
});

test('engineLatencyEvidence validates BOTH raw arrays (a malformed one fails loudly)', () => {
  assert.throws(() => engineLatencyEvidence('chromium', fill25(1).slice(0, 10), fill25(1)), /exactly 25/);
  assert.throws(() => engineLatencyEvidence('chromium', fill25(1), fill25(1).slice(0, 10)), /exactly 25/);
});

// ----- engineLatencyChannels: the rf2-dpwel two-channel evidence -------------

// A synthetic fixture latency payload with distinguishable channels: commit
// (true event-to-commit) well below settlement (input-to-quiescence), the way
// the real fixture reports them.
function fixtureLatency() {
  return {
    'compiled-commit-raw-ms': fill25(1.2),
    'handwritten-commit-raw-ms': fill25(1.1),
    'compiled-settle-raw-ms': fill25(5.0),
    'handwritten-settle-raw-ms': fill25(4.0),
    'order-policy': 'alternating pair order: even pairs compiled-first, odd pairs hand-written-first',
  };
}

test('CHANNELS names exactly the commit and settlement channels', () => {
  assert.deepEqual(CHANNELS, ['commit', 'settlement']);
});

test('engineLatencyChannels builds BOTH channels from the fixture payload and carries the order policy', () => {
  const e = engineLatencyChannels('chromium', fixtureLatency());
  assert.equal(e.engine, 'chromium');
  assert.match(e['order-policy'], /alternating pair order/);
  assert.equal(e.commit.compiled['p95-ms'], 1.2);
  assert.equal(e.commit.handwritten['p95-ms'], 1.1);
  assert.equal(e.settlement.compiled['p95-ms'], 5.0);
  assert.equal(e.settlement.handwritten['p95-ms'], 4.0);
  assert.equal(e.commit['within-10pct'], true);
  assert.equal(e.settlement['within-10pct'], false); // 1.25x — observed, never gated
});

test('engineLatencyChannels validates every raw array and names the engine/channel', () => {
  const missingCommit = fixtureLatency();
  delete missingCommit['compiled-commit-raw-ms'];
  assert.throws(() => engineLatencyChannels('webkit', missingCommit), /webkit\/commit/);
  const shortSettle = fixtureLatency();
  shortSettle['handwritten-settle-raw-ms'] = fill25(1).slice(0, 10);
  assert.throws(() => engineLatencyChannels('webkit', shortSettle), /exactly 25/);
  assert.throws(() => engineLatencyChannels('webkit', null), /not an object/);
});

// ----- buildPerformanceSummary: evidence-only posture + raw visible ----------

function twoEngineEvidence() {
  return [
    engineLatencyChannels('chromium', fixtureLatency()),
    engineLatencyChannels('webkit', {
      ...fixtureLatency(),
      'compiled-commit-raw-ms': fill25(1.4),
      'handwritten-commit-raw-ms': fill25(1.3),
    }),
  ];
}

test('buildPerformanceSummary states the evidence-only posture, the conventions, and both channels', () => {
  const md = buildPerformanceSummary(twoEngineEvidence());
  assert.match(md, /EVIDENCE ONLY/);
  assert.match(md, /no wall-clock threshold/);
  assert.match(md, /TRUE event-to-commit/);
  assert.match(md, /former metric, retained for comparison/);
  assert.equal(md.includes(PERCENTILE_CONVENTION), true);
  assert.equal(md.includes(NOISE_POLICY), true);
});

test('buildPerformanceSummary emits a per-engine, per-channel p95 + ratio row and exposes the raw samples', () => {
  const md = buildPerformanceSummary(twoEngineEvidence());
  assert.match(md, /\| chromium \| commit \| 1\.200 \| 1\.100 \| 1\.091 \| ✓ \|/);
  assert.match(md, /\| chromium \| settlement \| 5\.000 \| 4\.000 \| 1\.250 \| observed over \|/);
  assert.match(md, /\| webkit \| commit \| 1\.400 \| 1\.300 \| 1\.077 \| ✓ \|/);
  // Raw distributions must be present for both channels, not just percentiles.
  assert.match(md, /chromium commit compiled/);
  assert.match(md, /chromium settlement compiled/);
  assert.match(md, /webkit settlement hand-written/);
});

test('buildPerformanceSummary reports an over-budget commit channel as observed-over (evidence, not a gate)', () => {
  const over = engineLatencyChannels('chromium', {
    ...fixtureLatency(),
    'compiled-commit-raw-ms': fill25(2.0),
    'handwritten-commit-raw-ms': fill25(1.0),
  });
  const md = buildPerformanceSummary([over]);
  assert.match(md, /\| chromium \| commit \| 2\.000 \| 1\.000 \| 2\.000 \| observed over \|/);
});

test('buildPerformanceSummary renders a skipped channel as n/a + not-compared, never a ✓ (rf2-yumaq)', () => {
  const tooFast = engineLatencyChannels('chromium', {
    ...fixtureLatency(),
    'handwritten-commit-raw-ms': fill25(0), // baseline below the timer resolution
  });
  const md = buildPerformanceSummary([tooFast]);
  assert.match(md, /\| chromium \| commit \| 1\.200 \| 0\.000 \| n\/a \| not compared \|/);
  // The reason is STATED in the packet, not left as a bare blank cell.
  assert.equal(md.includes(ZERO_BASELINE_REASON), true);
  // The other channel is unaffected and still carries its real numbers.
  assert.match(md, /\| chromium \| settlement \| 5\.000 \| 4\.000 \| 1\.250 \| observed over \|/);
  // A skip must never be dressed up as a pass.
  assert.equal(/\| chromium \| commit \|[^\n]*✓/.test(md), false);
});

test('buildPerformanceSummary omits the skip footnote when every channel compared', () => {
  const md = buildPerformanceSummary(twoEngineEvidence());
  assert.equal(md.includes(ZERO_BASELINE_REASON), false);
  assert.equal(md.includes('not compared'), false);
});

test('buildPerformanceSummary rejects an empty per-engine set', () => {
  assert.throws(() => buildPerformanceSummary([]), /non-empty/);
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
