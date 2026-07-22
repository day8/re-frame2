'use strict';

// G-8 comparative-latency evidence math + presentation, factored out of
// run-ui-g8.cjs (mirrors lib/g13-timing-evidence.cjs) so the quantile
// convention, sample validation, the relative-budget comparison, and the
// step-summary rendering are pure and unit-testable without spawning
// shadow-cljs or Playwright.
//
// Latency is INVESTIGATION EVIDENCE ONLY. The canonical G-8 names 10% of an
// equivalent hand-written React control's event-to-commit p95 as the
// comparative REFERENCE BUDGET for that evidence — not as a gate requirement;
// this module MEASURES that ratio, records the sample count + noise policy, and
// REPORTS `within-10pct` — but nothing here becomes a pass/fail wall-clock
// threshold. Since rf2-dpwel the evidence carries TWO channels per engine:
// `commit` (the true event-to-commit boundary — the arm's own Profiler
// onRender commitTime) and `settlement` (the former input-to-quiescence
// metric, retained for comparison); the ratio is reported for both. The gate follows G-13's
// evidence-only/no-threshold posture: a noisy CI host must never redden a
// correctness gate on a timing number. Deterministic correctness (one React
// commit per ordinary input, exactly one at the committed IME boundary) is the
// hard gate; it lives in the runner, separate from this comparative evidence.
//
// The budget comparison is still made NON-VACUOUS by mutation teeth (see
// `runBudgetTeeth` in the runner and `_g8-latency-evidence.test.cjs`): a
// synthetic over-budget sample set must flip `within-10pct` to false, and a
// tight one must keep it true. That proves the comparison bites on the data
// without ever gating the real measured latency.

// The gate records exactly this many warm event-to-commit samples per control
// per engine, after discarding this many warm-up samples.
const WARMUP_SAMPLES = 5;
const RECORDED_SAMPLES = 25;

// One stated quantile convention — identical to G-13's: nearest-rank empirical
// percentile on the sorted sample set, zero-based rank `ceil(p*n)-1`, clamped
// to `[0, n-1]`, no interpolation. Stated here (not left implicit) so the
// reported p95 can never be mistaken for an interpolated quantile.
const PERCENTILE_CONVENTION =
  'nearest-rank empirical percentile (zero-based rank ceil(p*n)-1, clamped); no interpolation';

// The relative budget the canonical G-8 names: the compiled reusable control's
// event-to-commit p95 within 10% of an equivalent hand-written React control.
// EVIDENCE-ONLY — `within-10pct` is reported, never gated.
const RATIO_BUDGET = 1.1;

// The noise policy recorded in the artifact so the stated relative budget is
// reproducible and meaningful. rf2-dpwel resolved the two measurement effects
// the rf2-5qz8z audit surfaced: the endpoint is now the arm's own Profiler
// commit (not flush settlement), and the pair order alternates (no fixed
// compiled-first sequence bias).
const NOISE_POLICY =
  'one compiled + one hand-written sample per pair, same warmed run; pair ' +
  'order ALTERNATES compiled-first / hand-written-first (no fixed sequence ' +
  `bias); first ${WARMUP_SAMPLES} warm-up pairs per engine discarded; ` +
  `${RECORDED_SAMPLES} recorded samples per control per channel per engine; ` +
  'each sample keys one fresh-value native input to its own React.Profiler ' +
  'onRender commitTime with exactly one attributable commit asserted per ' +
  `sample; ${PERCENTILE_CONVENTION}`;

// The two measurement channels every sample carries (rf2-dpwel):
//   commit     — TRUE event-to-commit: native input dispatch to the arm's own
//                React.Profiler onRender commitTime.
//   settlement — the FORMER metric, retained for comparison: the same input to
//                ui.test/flush! settlement (awaited React act + framework
//                draining to the fixed point — input-to-global-quiescence).
const CHANNELS = ['commit', 'settlement'];

function evidenceFail(message) {
  return new Error(`G-8 latency-evidence FAIL: ${message}`);
}

// Nearest-rank percentile over a non-empty array of finite numbers. Sorts a
// COPY (never mutates the caller's array) numerically ascending.
function latencyPercentile(samples, p) {
  if (!Array.isArray(samples) || samples.length === 0) {
    throw evidenceFail(`percentile requires a non-empty sample array; got ${JSON.stringify(samples)}`);
  }
  if (typeof p !== 'number' || !Number.isFinite(p) || p < 0 || p > 1) {
    throw evidenceFail(`percentile p must be a finite number in [0,1]; got ${p}`);
  }
  for (const x of samples) {
    if (typeof x !== 'number' || !Number.isFinite(x)) {
      throw evidenceFail(`percentile sample is not a finite number: ${x}`);
    }
  }
  const sorted = [...samples].sort((a, b) => a - b);
  const n = sorted.length;
  const rank = Math.ceil(p * n) - 1;
  const idx = Math.min(Math.max(rank, 0), n - 1);
  return sorted[idx];
}

// Reject empty, malformed, non-finite, negative, or wrong-count latency sample
// arrays with a clear message naming the exact failure. This is a STRUCTURAL
// validity check (sample shape), NOT a wall-clock threshold — a wrong-count or
// non-finite sample set is a broken measurement, not a slow one. `where` labels
// the call site. Returns the array on success.
function validateLatencySamples(raw, where = 'event-to-commit evidence') {
  if (!Array.isArray(raw)) {
    throw evidenceFail(`${where}: latency samples are not an array (got ${JSON.stringify(raw)})`);
  }
  if (raw.length !== RECORDED_SAMPLES) {
    throw evidenceFail(
      `${where}: expected exactly ${RECORDED_SAMPLES} recorded samples, got ${raw.length}`,
    );
  }
  raw.forEach((x, i) => {
    if (typeof x !== 'number' || !Number.isFinite(x)) {
      throw evidenceFail(`${where}: latency sample [${i}] is not a finite number: ${x}`);
    }
    if (x < 0) {
      throw evidenceFail(`${where}: latency sample [${i}] is negative: ${x}`);
    }
  });
  return raw;
}

// Compute the labelled summary for one control from its raw samples. The raw
// array is preserved alongside the derived p50/p95 so the artifact keeps the
// full distribution, not just the two percentiles.
function summarizeLatency(raw, where = 'event-to-commit evidence') {
  validateLatencySamples(raw, where);
  return {
    'raw-ms': raw,
    'p50-ms': latencyPercentile(raw, 0.5),
    'p95-ms': latencyPercentile(raw, 0.95),
  };
}

// The evidence-only relative-budget comparison: is the compiled control's p95
// within RATIO_BUDGET × the hand-written baseline's p95? Reported, never gated.
// The baseline p95 must be a positive finite number (a zero baseline makes the
// ratio meaningless — a broken measurement, reported as such by the caller).
function withinBudget(compiledP95, baselineP95) {
  if (typeof compiledP95 !== 'number' || !Number.isFinite(compiledP95) || compiledP95 < 0) {
    throw evidenceFail(`compiled p95 is not a non-negative finite number: ${compiledP95}`);
  }
  if (typeof baselineP95 !== 'number' || !Number.isFinite(baselineP95) || baselineP95 <= 0) {
    throw evidenceFail(`baseline p95 must be a positive finite number: ${baselineP95}`);
  }
  return compiledP95 <= RATIO_BUDGET * baselineP95;
}

function p95Ratio(compiledP95, baselineP95) {
  if (typeof baselineP95 !== 'number' || !Number.isFinite(baselineP95) || baselineP95 <= 0) {
    throw evidenceFail(`baseline p95 must be a positive finite number: ${baselineP95}`);
  }
  return compiledP95 / baselineP95;
}

// The comparative-latency evidence object for ONE channel, built from the two
// raw sample arrays measured in the SAME warmed run. Pure — the runner folds
// this into the artifact's `performance` section (per engine, per channel).
function engineLatencyEvidence(engine, compiledRaw, handwrittenRaw) {
  const compiled = summarizeLatency(compiledRaw, `[${engine}] compiled latency`);
  const handwritten = summarizeLatency(handwrittenRaw, `[${engine}] hand-written latency`);
  return {
    engine,
    'samples-recorded': RECORDED_SAMPLES,
    'warmups-discarded': WARMUP_SAMPLES,
    'ratio-budget': RATIO_BUDGET,
    compiled,
    handwritten,
    'p95-ratio': p95Ratio(compiled['p95-ms'], handwritten['p95-ms']),
    'within-10pct': withinBudget(compiled['p95-ms'], handwritten['p95-ms']),
  };
}

// The per-engine TWO-CHANNEL comparative-latency evidence built from the
// fixture's latency payload: the `commit` channel is the true event-to-commit
// evidence; the `settlement` channel is the former input-to-quiescence metric,
// retained so the two boundaries stay comparable in the same artifact. Pure.
function engineLatencyChannels(engine, lat) {
  if (!lat || typeof lat !== 'object') {
    throw evidenceFail(`[${engine}] latency payload is not an object: ${JSON.stringify(lat)}`);
  }
  return {
    engine,
    'order-policy': lat['order-policy'],
    endpoints: lat.endpoints,
    commit: engineLatencyEvidence(
      `${engine}/commit`, lat['compiled-commit-raw-ms'], lat['handwritten-commit-raw-ms']),
    settlement: engineLatencyEvidence(
      `${engine}/settlement`, lat['compiled-settle-raw-ms'], lat['handwritten-settle-raw-ms']),
  };
}

function fmt(x) {
  return Number(x).toFixed(3);
}

// Render the GitHub Actions step summary for the comparative-latency evidence.
// Emits, per engine and per channel, the compiled + hand-written p95, the
// ratio, and the within-10% OBSERVATION — the raw distributions collapsed
// behind a <details> so the packet stays compact but nothing is hidden.
// Returns the markdown string (the fs write lives in the runner so this stays
// pure). Takes the per-engine TWO-CHANNEL shape from `engineLatencyChannels`.
function buildPerformanceSummary(perEngine) {
  if (!Array.isArray(perEngine) || perEngine.length === 0) {
    throw evidenceFail(`summary requires a non-empty per-engine array; got ${JSON.stringify(perEngine)}`);
  }
  const lines = [
    '### re-frame.ui G-8 comparative latency (commit + settlement channels)',
    '',
    'Compiled reusable control vs an equivalent hand-written React control — ' +
      'EVIDENCE ONLY; G-8 has no wall-clock threshold (the correctness matrix is ' +
      'the hard gate).',
    'Channels: `commit` = TRUE event-to-commit (the arm\'s own React.Profiler ' +
      'onRender commitTime, one attributable commit asserted per sample); ' +
      '`settlement` = input to flush settlement (awaited React act + framework ' +
      'draining — the former metric, retained for comparison).',
    `Percentiles: ${PERCENTILE_CONVENTION}.`,
    `Noise policy: ${NOISE_POLICY}.`,
    '',
    '| engine | channel | compiled p95 ms | hand-written p95 ms | p95 ratio | within 10% (evidence) |',
    '|---|---|---:|---:|---:|:---:|',
  ];
  for (const e of perEngine) {
    for (const ch of CHANNELS) {
      const ev = e[ch];
      lines.push(
        `| ${e.engine} | ${ch} | ${fmt(ev.compiled['p95-ms'])} | ${fmt(ev.handwritten['p95-ms'])} | ` +
          `${fmt(ev['p95-ratio'])} | ${ev['within-10pct'] ? '✓' : 'observed over'} |`,
      );
    }
  }
  lines.push('');
  lines.push('<details><summary>Raw latency samples (ms)</summary>');
  lines.push('');
  for (const e of perEngine) {
    for (const ch of CHANNELS) {
      const ev = e[ch];
      lines.push(
        `- **${e.engine} ${ch} compiled** — [${ev.compiled['raw-ms'].map(fmt).join(', ')}]`,
      );
      lines.push(
        `- **${e.engine} ${ch} hand-written** — [${ev.handwritten['raw-ms'].map(fmt).join(', ')}]`,
      );
    }
  }
  lines.push('');
  lines.push('</details>');
  lines.push('');
  return `${lines.join('\n')}\n`;
}

module.exports = {
  WARMUP_SAMPLES,
  RECORDED_SAMPLES,
  PERCENTILE_CONVENTION,
  RATIO_BUDGET,
  NOISE_POLICY,
  CHANNELS,
  latencyPercentile,
  validateLatencySamples,
  summarizeLatency,
  withinBudget,
  p95Ratio,
  engineLatencyEvidence,
  engineLatencyChannels,
  buildPerformanceSummary,
};
