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
// Every witness above describes state BEFORE the timed dispatch, so none of them
// can separate a real measured drain from an EMPTY one. Delete the dispatch from
// `timing-cycle!` and the whole gate stays green: `timing-first` pre-hot is still
// the mount seed 0, `correctness-first` pre-hot still advances to queued-writes
// (the SEPARATE untimed correctness cycle advanced it), and that same correctness
// cycle still supplies every projection — so the runner labels a flush interval
// that did nothing "dispatch-to-commit" and reports it as evidence.
//
// The closing witness is the timed cycle's own POST-commit app-db `:hot`, read
// AFTER its end timestamp (no validation work enters the measured span) and
// returned with that same cycle. `post - pre` is exactly the number of writes
// that committed between the two reads. The reads bracket the two timestamps by
// construction — nothing but the `performance.now` call itself sits between the
// pre read and `started`, and nothing but the elapsed subtraction sits between
// the end timestamp and the post read — so a delta of `queuedWrites` is proof
// that this interval carried the drain it is named for. A removed, no-op, or
// hoisted-out dispatch yields 0 (or the wrong delta) and is rejected here.
//
// This is the RUNTIME half of the containment proof. The LEXICAL half — dispatch
// inside the flushed thunk, thunk between the two timestamps — is pinned by
// `assertTimedRegionShape` below, which reads the fixture source itself.
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

// ---------------------------------------------------------------------------
// rf2-a0i2y — the STRUCTURAL half of the containment proof.
//
// The runtime witness above proves the interval did `queuedWrites` of work
// between the two app-db reads. Those reads sit immediately outside the two
// timestamps, so the remaining question is purely lexical: is the dispatch
// actually inside the flushed thunk, and is that thunk actually between the
// start and end timestamps? That is a property of the fixture's SOURCE, and it
// is better proven by construction than by any number of green samples — so it
// is asserted here over `dev.cljs` itself, the same way the lease-free arm
// asserts its positive controls against `reactive.cljc`.
//
// It also closes the one mutant the runtime witness cannot see. Hoisting the
// dispatch above `started` leaves the post-pre delta at exactly `queuedWrites`
// (the pre read still precedes the hoisted dispatch), yet the measured span no
// longer contains the eight write epochs. Only the source order shows that, and
// the ordering assertion below rejects it.
// ---------------------------------------------------------------------------

const TIMED_REGION_OPEN = '(defn- timing-cycle!';
const TIMED_REGION_CLOSE = '(defn- correctness-cycle!';

// Blank out Clojure string literals, `;` comments and `\x` character literals,
// PRESERVING byte offsets and newlines, so the ordering below is computed over
// CODE only. This region's docstring legitimately says "dispatch", "pre-hot" and
// "performance"; a raw indexOf over the text would count prose as structure —
// precisely the byte-slice mistake the deps-boundary arm already refuses to make
// (rf2-5e3ic/rf2-han0r). Prose can no longer satisfy — or break — this proof.
function codeOnly(source) {
  let out = '';
  let i = 0;
  const n = source.length;
  while (i < n) {
    const ch = source[i];
    if (ch === '\\') { // character literal: \( \; \" ...
      out += ' ';
      i += 1;
      if (i < n) { out += source[i] === '\n' ? '\n' : ' '; i += 1; }
      continue;
    }
    if (ch === '"') {
      out += ' ';
      i += 1;
      while (i < n && source[i] !== '"') {
        if (source[i] === '\\') {
          out += ' ';
          i += 1;
          if (i < n) { out += source[i] === '\n' ? '\n' : ' '; i += 1; }
          continue;
        }
        out += source[i] === '\n' ? '\n' : ' ';
        i += 1;
      }
      if (i < n) { out += ' '; i += 1; }
      continue;
    }
    if (ch === ';') {
      while (i < n && source[i] !== '\n') { out += ' '; i += 1; }
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

function occurrences(haystack, re) {
  const global = new RegExp(re.source, `${re.flags.replace('g', '')}g`);
  const found = [];
  let m = global.exec(haystack);
  while (m !== null) {
    found.push(m.index);
    if (m.index === global.lastIndex) global.lastIndex += 1;
    m = global.exec(haystack);
  }
  return found;
}

// The measured region's required lexical shape, in required source order. Each
// row names WHY it is where it is, because the failure message is the only thing
// a future editor will read.
const TIMED_REGION_ANCHORS = [
  {
    name: 'the pre-dispatch app-db witness read',
    re: /rf\/app-db-value/,
    nth: 1,
    why: 'the opening witness must be read BEFORE the interval starts',
  },
  {
    name: 'the interval START timestamp',
    re: /js\/performance\.now/,
    nth: 1,
    why: 'the measured span opens here',
  },
  {
    name: 'the flush! that owns the measured interval',
    re: /\(uit\/flush!/,
    nth: 1,
    why: 'the flush whose Promise resolution IS the commit must open inside the span',
  },
  {
    name: 'the timed dispatch',
    re: /\(uit\/dispatch!\s+frame\s+\[::fixture\/step\s+fixture\/queued-writes\]\)/,
    nth: 1,
    why: 'the dispatch under measurement must sit INSIDE the flushed thunk, after the start timestamp',
  },
  {
    name: 'the interval END timestamp',
    re: /js\/performance\.now/,
    nth: 2,
    why: 'the measured span closes here, once the commit has resolved',
  },
  {
    name: 'the post-commit app-db witness read',
    re: /rf\/app-db-value/,
    nth: 2,
    why: 'the closing witness must be read AFTER the end timestamp, so it adds no work to the span',
  },
];

// Exact multiplicities. Without these an extra `performance.now` or a second
// dispatch could satisfy the ordering while changing what is measured.
const TIMED_REGION_COUNTS = [
  { name: 'performance.now readings', re: /js\/performance\.now/, exactly: 2 },
  { name: 'app-db witness reads', re: /rf\/app-db-value/, exactly: 2 },
  { name: 'flush! calls', re: /\(uit\/flush!/, exactly: 1 },
  { name: 'dispatch! calls', re: /\(uit\/dispatch!/, exactly: 1 },
];

// Prove, from `devSource`, that the timed region contains its dispatch and its
// commit BY CONSTRUCTION. Returns the pinned order on success.
function assertTimedRegionShape(devSource, where = 'G-13 timed region containment') {
  if (typeof devSource !== 'string' || devSource.length === 0) {
    throw evidenceFail(`${where}: the fixture source was not readable`);
  }
  const open = devSource.indexOf(TIMED_REGION_OPEN);
  if (open < 0) {
    throw evidenceFail(
      `${where}: could not locate \`${TIMED_REGION_OPEN}\` — the measured region this proof ` +
        'is about no longer exists under that name; re-establish containment before renaming it',
    );
  }
  const close = devSource.indexOf(TIMED_REGION_CLOSE, open);
  if (close < 0) {
    throw evidenceFail(
      `${where}: could not locate \`${TIMED_REGION_CLOSE}\` after the timed region — the proof ` +
        'delimits the timed cycle by the untimed cycle that must follow it',
    );
  }
  const region = codeOnly(devSource.slice(open, close));
  for (const { name, re, exactly } of TIMED_REGION_COUNTS) {
    const n = occurrences(region, re).length;
    if (n !== exactly) {
      throw evidenceFail(
        `${where}: expected exactly ${exactly} ${name} in the timed region, found ${n}. ` +
          'The measured dispatch-to-commit span is defined by exactly these forms; ' +
          'adding or removing one changes what the reported number means.',
      );
    }
  }
  const positions = TIMED_REGION_ANCHORS.map((anchor) => {
    const at = occurrences(region, anchor.re)[anchor.nth - 1];
    if (at === undefined) {
      throw evidenceFail(
        `${where}: ${anchor.name} is absent from the timed region (${anchor.why}). ` +
          'A measured interval missing this form does not contain the dispatch and commit it is named for.',
      );
    }
    return { ...anchor, at };
  });
  for (let i = 1; i < positions.length; i += 1) {
    const previous = positions[i - 1];
    const current = positions[i];
    if (!(previous.at < current.at)) {
      throw evidenceFail(
        `${where}: ${current.name} does not follow ${previous.name} in the timed region ` +
          `(offsets ${previous.at} then ${current.at}). ${current.why}. The dispatch and commit ` +
          'are no longer inside the measured span by construction — the reported ' +
          'dispatch-to-commit number would describe a different region than its label claims.',
      );
    }
  }
  return positions.map((p) => p.name);
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
  TIMED_REGION_OPEN,
  TIMED_REGION_CLOSE,
  warmPercentile,
  validateWarmSamples,
  summarizeWarm,
  assertColdIsFirstDrain,
  assertColdOrderControl,
  assertTimedIntervalDidWork,
  assertTimedRegionShape,
  codeOnly,
  buildSummary,
};
