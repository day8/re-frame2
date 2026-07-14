'use strict';

// G-13 timing-evidence math + presentation, factored out of the browser
// orchestration in `run-ui-g13.cjs` (rf2-vxgfnd.213) so the quantile
// convention, sample validation, and step-summary rendering are pure and
// unit-testable without spawning shadow-cljs or Playwright.
//
// Timing is INVESTIGATION EVIDENCE ONLY: nothing here becomes a pass/fail
// threshold. Its sole job is to state one quantile convention plainly, reject
// malformed sample sets, and surface the raw dispatch-to-commit samples (not
// just the summary) so a human reading the CI packet can see the full
// distribution rather than a single opaque number.

// The gate records exactly nine warm dispatch-to-commit samples per size.
const RECORDED_SAMPLES = 9;

// One stated quantile convention: nearest-rank empirical percentile on the
// sorted sample set, zero-based rank `ceil(p * n) - 1`, clamped to
// `[0, n-1]`. No interpolation. For the nine-sample warm set this makes p95
// the maximum observation and p50 the fifth-smallest — e.g. for [1..9],
// p50 = 5 and p95 = 9. This is stated here (not left implicit) so the
// reported percentile can never be mistaken for an interpolated quantile.
const PERCENTILE_CONVENTION =
  'nearest-rank empirical percentile (zero-based rank ceil(p*n)-1, clamped); no interpolation';

function evidenceFail(message) {
  return new Error(`G-13 timing-evidence FAIL: ${message}`);
}

// Nearest-rank percentile over a non-empty array of finite numbers. Sorts a
// COPY (never mutates the caller's array) numerically ascending.
function warmPercentile(samples, p) {
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

// Reject empty, malformed, non-finite, or wrong-count warm sample arrays with
// a clear message naming the exact failure. `where` labels the call site
// (e.g. "V=100 warm timing evidence"). Returns the array on success.
function validateWarmSamples(raw, where = 'warm timing evidence') {
  if (!Array.isArray(raw)) {
    throw evidenceFail(`${where}: warm samples are not an array (got ${JSON.stringify(raw)})`);
  }
  if (raw.length !== RECORDED_SAMPLES) {
    throw evidenceFail(
      `${where}: expected exactly ${RECORDED_SAMPLES} warm samples, got ${raw.length}`,
    );
  }
  raw.forEach((x, i) => {
    if (typeof x !== 'number' || !Number.isFinite(x)) {
      throw evidenceFail(`${where}: warm sample [${i}] is not a finite number: ${x}`);
    }
  });
  return raw;
}

// Compute the labelled summary for one size from its raw warm samples. The
// raw array is preserved alongside the derived p50/p95 so the artifact keeps
// the full distribution, not just the two percentiles.
function summarizeWarm(raw, where = 'warm timing evidence') {
  validateWarmSamples(raw, where);
  return {
    'raw-ms': raw,
    'p50-ms': warmPercentile(raw, 0.5),
    'p95-ms': warmPercentile(raw, 0.95),
  };
}

function fmt(x) {
  return Number(x).toFixed(3);
}

// Render the GitHub Actions step summary for the development results. Emits,
// for EACH size, the raw warm sample array, the cold sample, and the labelled
// p50/p95 — the raw distribution collapsed behind a <details> so the packet
// stays compact but nothing is hidden. Returns the markdown string (the fs
// write lives in the runner so this stays pure).
function buildSummary(results) {
  if (!Array.isArray(results) || results.length === 0) {
    throw evidenceFail(`summary requires a non-empty results array; got ${JSON.stringify(results)}`);
  }
  const lines = [
    '### re-frame.ui G-13 push economics',
    '',
    'Dispatch-to-commit timing — EVIDENCE ONLY; G-13 has no wall-clock threshold.',
    `Percentiles: ${PERCENTILE_CONVENTION}. With n=${RECORDED_SAMPLES} warm samples p95 is the maximum.`,
    '',
    '| V | cold ms | warm p50 ms | warm p95 ms | exact projection |',
    '|---:|---:|---:|---:|:---:|',
  ];
  for (const row of results) {
    const warm = summarizeWarm(row.warm['raw-ms'], `V=${row.v} warm timing evidence`);
    lines.push(
      `| ${row.v} | ${fmt(row.cold['elapsed-ms'])} | ` +
        `${fmt(warm['p50-ms'])} | ${fmt(warm['p95-ms'])} | yes |`,
    );
  }
  lines.push('');
  lines.push('<details><summary>Raw dispatch-to-commit samples (ms)</summary>');
  lines.push('');
  for (const row of results) {
    const raw = row.warm['raw-ms'].map(fmt).join(', ');
    lines.push(`- **V=${row.v}** — cold ${fmt(row.cold['elapsed-ms'])}; warm [${raw}]`);
  }
  lines.push('');
  lines.push('</details>');
  lines.push('');
  return `${lines.join('\n')}\n`;
}

module.exports = {
  RECORDED_SAMPLES,
  PERCENTILE_CONVENTION,
  warmPercentile,
  validateWarmSamples,
  summarizeWarm,
  buildSummary,
};
