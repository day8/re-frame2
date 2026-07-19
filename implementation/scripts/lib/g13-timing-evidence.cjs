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

// rf2-52isf — the cold-order control. G-13's `cold` sample must be the FIRST
// post-mount dispatch/commit: its timed span cannot sit AFTER this sample's own
// correctness dispatch, O(V) audit, DOM read, or React/cache warm-up. The
// fixture seeds app-db `:hot=0` at mount and every drain adds `queued-writes`,
// so `:timing-pre-hot` — the app-db `:hot` value captured immediately before
// the cold timing dispatch — is exactly 0 iff no drain preceded the cold timer.
// A nonzero value means correctness (or a warmup) ran first and the reported
// "cold" span is actually a warmed second dispatch. Evidence plumbing only — no
// wall-clock threshold; this pins the cold IDENTITY, not its duration.
const COLD_FIRST_DRAIN_PRE_HOT = 0;

// rf2-a0i2y — DID THE MEASURED INTERVAL CONTAIN ITS OWN DISPATCH AND COMMIT?
//
// Every OTHER witness describes state BEFORE the timed dispatch, so none of them
// can separate a real measured drain from an EMPTY one. Delete the dispatch from
// `timing-cycle!` and the rest of the gate stays green: `timing-first` pre-hot is
// still the mount seed 0, `correctness-first` pre-hot still advances to
// queued-writes (the SEPARATE untimed correctness cycle advanced it), and that
// same correctness cycle still supplies every projection — so the runner would
// label a flush interval that did nothing "dispatch-to-commit".
//
// Both witnesses are read by the measurement seam itself
// (`re-frame.ui.g13.measure/measure-dispatch-to-commit!`): `pre-hot` before its
// start timestamp, `post-hot` after its end timestamp. `post - pre` is therefore
// exactly the number of writes that committed inside the measured interval, and
// a delta of `queuedWrites` is proof that this interval carried the drain it is
// named for.
//
// rf2-muhsq — because the seam owns BOTH reads rather than taking them from its
// caller, this check now also rejects the mutant that used to need a source-text
// proof: work HOISTED out of the flushed thunk. A caller that dispatches before
// calling the seam has advanced app-db by the time `pre-hot` is read, so pre and
// post agree and the delta is 0. Removed, no-op, hoisted-above, and deferred-past
// dispatches all collapse the delta and are rejected here.
//
// What this does NOT establish is the seam's own internal order — that the work
// runs inside the flush and the two witness reads sit outside the two
// timestamps. That is a call-order property of one small function and is
// asserted directly, with a fake clock and a fake flush, by
// `re-frame.ui.g13.measure-cljs-test`. No source text is read by either half.
function assertTimedIntervalDidWork(pre, post, queuedWrites, where = 'timed interval work witness') {
  if (typeof queuedWrites !== 'number' || !Number.isFinite(queuedWrites) || queuedWrites <= 0) {
    throw evidenceFail(
      `${where}: queued-writes must be a positive finite number; got ${JSON.stringify(queuedWrites)}`,
    );
  }
  for (const [label, value] of [['pre-hot', pre], ['post-hot', post]]) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw evidenceFail(
        `${where}: the timed cycle's ${label} witness is missing or not a finite number ` +
          `(got ${JSON.stringify(value)}); without both witnesses the measured interval ` +
          'cannot be shown to contain its own dispatch and commit',
      );
    }
  }
  const delta = post - pre;
  if (delta !== queuedWrites) {
    throw evidenceFail(
      `${where}: the measured interval did not contain its own dispatch and commit ` +
        `(pre-hot=${pre}, post-hot=${post}, delta=${delta}, expected ${queuedWrites}); ` +
        'the timed dispatch-to-commit span was empty, partial, or ran outside the timestamps ' +
        '— the untimed correctness cycle cannot supply this witness',
    );
  }
  return delta;
}

// Read one order-control path's witness pair and prove that path's own timed
// interval did the work. Returns the path's `pre-hot` for the divergence checks.
function orderWitness(order, path, queuedWrites, where) {
  const witness = order[path];
  if (!witness || typeof witness !== 'object') {
    throw evidenceFail(
      `${where}: the ${path} order path did not report a timed-cycle witness pair ` +
        `(got ${JSON.stringify(witness)})`,
    );
  }
  assertTimedIntervalDidWork(
    witness['pre-hot'], witness['post-hot'], queuedWrites, `${where} (${path})`,
  );
  return witness['pre-hot'];
}

// Assert the cold sample is the first post-mount drain. `where` labels the call
// site (e.g. "V=100 cold timing evidence"). Returns the sample on success.
function assertColdIsFirstDrain(cold, where = 'cold timing evidence') {
  if (!cold || typeof cold !== 'object') {
    throw evidenceFail(`${where}: cold sample is missing (got ${JSON.stringify(cold)})`);
  }
  const preHot = cold['timing-pre-hot'];
  if (preHot !== COLD_FIRST_DRAIN_PRE_HOT) {
    throw evidenceFail(
      `${where}: cold timer did not measure the first post-mount dispatch ` +
        `(timing-pre-hot=${JSON.stringify(preHot)}, expected ${COLD_FIRST_DRAIN_PRE_HOT}); ` +
        'correctness ran before the cold timer',
    );
  }
  return cold;
}

// rf2-6k4cm — the CAUSAL cold-first order control. `assertColdIsFirstDrain`
// above proves ONE reported witness is 0, but a witness read at `sample!` entry
// (before either cycle) stays 0 no matter which cycle the timer actually
// measures — so that assertion can pass while the "cold" span is a warmed second
// drain. This closes the gap: the fixture runs the two cycles in BOTH orders on
// fresh frames and reports each TIMED cycle's OWN pre-hot witness. With the
// witness sourced from the timed dispatch, the orders MUST diverge —
// `timing-first` (the real cold order) sees the mount seed 0; `correctness-first`
// (a correctness drain BEFORE the timer) sees `queuedWrites`. This asserts the
// divergence: timing-first passes the cold-first assertion, correctness-first
// fails it, and a non-causal witness that stays 0 in BOTH orders is rejected.
// `queuedWrites` is the fixture's per-drain :hot advance (8). Returns the order
// control on success.
function assertColdOrderControl(order, queuedWrites, where = 'cold-first order control') {
  if (!order || typeof order !== 'object') {
    throw evidenceFail(`${where}: order control is missing (got ${JSON.stringify(order)})`);
  }
  if (typeof queuedWrites !== 'number' || !Number.isFinite(queuedWrites) || queuedWrites <= 0) {
    throw evidenceFail(
      `${where}: queued-writes must be a positive finite number; got ${JSON.stringify(queuedWrites)}`,
    );
  }
  // rf2-a0i2y — each order path reports its timed cycle's witness PAIR, and each
  // path must independently prove its own measured interval did the work.
  const timingFirst = orderWitness(order, 'timing-first', queuedWrites, where);
  const correctnessFirst = orderWitness(order, 'correctness-first', queuedWrites, where);
  // The real cold order: the timed cycle ran before any drain, so its witness is
  // the mount seed and the cold-first assertion must ACCEPT it.
  if (timingFirst !== COLD_FIRST_DRAIN_PRE_HOT) {
    throw evidenceFail(
      `${where}: timing-first witness is not the mount seed ${COLD_FIRST_DRAIN_PRE_HOT} ` +
        `(got ${JSON.stringify(timingFirst)}); the timed cycle did not measure the first drain`,
    );
  }
  assertColdIsFirstDrain({ 'timing-pre-hot': timingFirst }, `${where} (timing-first)`);
  // The inverted order: a correctness drain committed `queuedWrites` before the
  // timed cycle, so a CAUSAL witness MUST advance to it. A witness still equal to
  // the mount seed here proves the value was captured before the timed dispatch —
  // exactly the vacuous, non-causal control this check exists to reject.
  if (correctnessFirst !== queuedWrites) {
    throw evidenceFail(
      `${where}: correctness-first witness did not advance to queued-writes ` +
        `(got ${JSON.stringify(correctnessFirst)}, expected ${queuedWrites}); the pre-hot witness ` +
        'is not sourced from the timed dispatch — a non-causal, vacuous cold-first control',
    );
  }
  // ...and the cold-first assertion MUST REJECT that inverted-order witness.
  let rejected = false;
  try {
    assertColdIsFirstDrain({ 'timing-pre-hot': correctnessFirst }, `${where} (correctness-first)`);
  } catch (_) {
    rejected = true;
  }
  if (!rejected) {
    throw evidenceFail(
      `${where}: the cold-first assertion accepted the correctness-first witness ` +
        `(${JSON.stringify(correctnessFirst)}) — the control does not depend on the timed dispatch order`,
    );
  }
  return order;
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
  COLD_FIRST_DRAIN_PRE_HOT,
  warmPercentile,
  validateWarmSamples,
  summarizeWarm,
  assertColdIsFirstDrain,
  assertColdOrderControl,
  assertTimedIntervalDidWork,
  buildSummary,
};
