'use strict';
// THE FLOOR ARM'S SECOND-MODE **RATE**, AGAINST ELAPSED TIME WITHIN A SESSION —
// rf2-6kxub, read off committed datasets and nothing else.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_mode_rate_session.cjs
//     node hicasso/test/re_frame/bench/hicasso/alloc_mode_rate_session.cjs --self-test
//
// ## THE QUESTION
//
// Three windows at ONE revision, on ONE instrument, with the SAME plan and the
// SAME estimator produced three incompatible rates for the elevated floor mode:
// 0 of 6, 2 of 20, and 37 of 69. `rf2-6kxub` filed that as a live risk to the
// METHOD rather than an oddity, because every window on this arm SIZES ITS RUN
// COUNT against a prior rate and a pre-declared count cannot be revised after
// the fact.
//
// The bead's own first-named candidate is a within-window ordering effect, and
// it is the one candidate that needs NOTHING new recorded: `generatedAt` is on
// every dataset already. This reader tests it.
//
// ## THE CLASSIFIER, WHICH IS NOT THIS READER'S TO CHOOSE
//
// A run is HIGH when either segment's estimator reads at or above 21,000
// B/write. That is `rf2-77gz8`'s criterion, carried unchanged through
// `does-arming-the-census-move-the-high-level.md`, and it is quoted here rather
// than invented: this reader must not be free to move the bar it counts
// against. The estimator is the MEDIAN of `legMedian` over the run's CERTIFIED
// floor windows, per segment.
//
// **THE BAR IS NOT A GATE.** Nothing passes or fails on it, no run is refused
// by it, and it is not tau and is not calibrated against tau in either
// direction. It is a label applied to an already-measured bimodal population.
//
// ## THE BOUNDARY SENSITIVITY, WHICH IS WHY THIS READER PRINTS A SWEEP
//
// The headline figure an earlier read quoted — a one-tail hypergeometric
// P(<= 5 high among the first 19 of 69) = 0.0054 — turns on where the first
// "quarter" is cut, and the cut is a choice rather than a measurement. Over
// k = 17, 18, 19, 20 the same statistic reads 0.0210, 0.0109, 0.0054, 0.0025:
// an eightfold move across a two-run choice.
//
// So this reader prints the sweep, and prints a BOUNDARY-FREE test beside it —
// the Mann-Whitney rank-sum of each run's position in its session against
// whether it read high. That statistic has no cut point to choose, and it is
// the one a record should quote.
//
// ## WHAT THIS IS NOT
//
// It is not a gate and it launches nothing. It is a reader over records that
// already exist, exactly as `alloc_heap_trajectory.cjs` is.

const fs = require('node:fs');
const path = require('node:path');

const DATA = path.join(__dirname, 'data');

// `rf2-77gz8`'s criterion, unchanged. See the header: quoted, not chosen.
const HIGH_MODE_B = 21000;

// The three sessions the bead names, in the order it names them.
const SESSIONS = ['workcount-n1b9h', 'alloc-77gz8', 'alloc-c4hhk'];

// The corpus that BOUNDS the result rather than supporting it.
const BOUNDING_SESSION = 'alloc-9jrhi';

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

// --- combinatorics ----------------------------------------------------------

const LOG_FACTORIAL = [0];
function logFactorial(n) {
  for (let i = LOG_FACTORIAL.length; i <= n; i++) LOG_FACTORIAL[i] = LOG_FACTORIAL[i - 1] + Math.log(i);
  return LOG_FACTORIAL[n];
}
const logChoose = (n, k) => (k < 0 || k > n ? -Infinity : logFactorial(n) - logFactorial(k) - logFactorial(n - k));

// P(exactly k successes) drawing n from N with K successes in it.
const hypergeometric = (k, K, n, N) => Math.exp(logChoose(K, k) + logChoose(N - K, n - k) - logChoose(N, n));

// ONE-TAILED, lower: P(at most `k` high among the first `n` of `N`), under the
// null that the K high runs are arranged at random. This is the null the
// question needs — "were the early runs unusually LOW" — and it is one-tailed
// on purpose, which the report says beside every figure it prints.
function hypergeometricAtMost(k, K, n, N) {
  let p = 0;
  for (let x = 0; x <= k; x++) p += hypergeometric(x, K, n, N);
  return Math.min(1, p);
}

const binomial = (k, n, p) => Math.exp(logChoose(n, k) + k * Math.log(p) + (n - k) * Math.log(1 - p));
function binomialAtMost(k, n, p) {
  let s = 0;
  for (let x = 0; x <= k; x++) s += binomial(x, n, p);
  return Math.min(1, s);
}

// Abramowitz & Stegun 7.1.26. Accurate to ~1.5e-7, which is far finer than any
// figure quoted from it here.
function erf(x) {
  const sign = x < 0 ? -1 : 1;
  const z = Math.abs(x);
  const t = 1 / (1 + 0.3275911 * z);
  const y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
  return sign * y;
}
const normalUpperTail = (z) => 0.5 * (1 - erf(z / Math.SQRT2));

// MANN-WHITNEY U on the run's ordinal position in its session, high against
// low. No cut point, so no boundary to choose. The normal approximation is used
// for the tail because n is 37 against 32 — far past where it bites — and the
// exact U is printed beside it so the approximation can be checked.
function mannWhitney(highRanks, lowRanks) {
  let U = 0;
  for (const a of highRanks) for (const b of lowRanks) if (a > b) U += 1;
  const n1 = highRanks.length;
  const n2 = lowRanks.length;
  if (!n1 || !n2) return null;
  const mu = (n1 * n2) / 2;
  const sd = Math.sqrt((n1 * n2 * (n1 + n2 + 1)) / 12);
  const z = (U - mu) / sd;
  return { U, mu, sd, z, pOneTail: normalUpperTail(z) };
}

// --- the records ------------------------------------------------------------

function runsOf(dir) {
  const full = path.join(DATA, dir);
  const out = [];
  const skipped = [];
  for (const f of fs.readdirSync(full).filter((x) => x.endsWith('.json')).sort()) {
    const raw = JSON.parse(fs.readFileSync(path.join(full, f), 'utf8'));
    const a = raw.alloc;
    // A dataset with no `alloc` block carries no reading at all. It is counted
    // as INADMISSIBLE and named, never silently dropped and never counted low —
    // counting it low would move every rate on this page.
    if (!a || !Array.isArray(a.perRound)) {
      skipped.push({ file: f, why: 'no alloc block' });
      continue;
    }
    const perSegment = {};
    for (const round of a.perRound) {
      for (const w of Object.values(round.arms || {})) {
        if (!w.certified) continue;
        (perSegment[w.segment] = perSegment[w.segment] || []).push(w.legMedian);
      }
    }
    const levels = {};
    for (const [seg, xs] of Object.entries(perSegment)) levels[seg] = median(xs);
    out.push({
      file: f.replace(/\.json$/, ''),
      at: Date.parse(raw.generatedAt),
      levels,
      high: Object.values(levels).some((v) => v !== null && v >= HIGH_MODE_B),
    });
  }
  out.sort((x, y) => x.at - y.at);
  return { runs: out, skipped };
}

function sessionOf(dir) {
  const { runs, skipped } = runsOf(dir);
  const N = runs.length;
  const K = runs.filter((r) => r.high).length;
  const t0 = runs.length ? runs[0].at : null;
  const t1 = runs.length ? runs[runs.length - 1].at : null;
  const ranked = runs.map((r, i) => ({ ...r, rank: i + 1 }));
  return {
    dir,
    N,
    K,
    rate: N ? K / N : null,
    skipped,
    openedAt: t0,
    minutes: N > 1 ? (t1 - t0) / 60000 : 0,
    runs: ranked,
    // THE SWEEP. See the header: the published figure moves eightfold across a
    // two-run choice of where the first quarter ends, so the choice is printed
    // rather than made.
    prefixSweep: [17, 18, 19, 20, Math.floor(N / 2)]
      .filter((k) => k > 0 && k < N)
      .sort((a, b) => a - b)
      .map((k) => {
        const h = ranked.slice(0, k).filter((r) => r.high).length;
        return { k, high: h, pAtMost: hypergeometricAtMost(h, K, k, N) };
      }),
    rankTest: mannWhitney(ranked.filter((r) => r.high).map((r) => r.rank), ranked.filter((r) => !r.high).map((r) => r.rank)),
  };
}

function analyse() {
  const sessions = SESSIONS.map(sessionOf);
  const bounding = sessionOf(BOUNDING_SESSION);
  const long = sessions[sessions.length - 1];

  // The other two sessions read against two reference rates: the long session's
  // OWN EARLY rate, and its pooled rate. The contrast between those two columns
  // is the whole of the "the three rates stop being incompatible" claim.
  const early = long.prefixSweep.find((s) => s.k === 19) || long.prefixSweep[0];
  const earlyRate = early.high / early.k;
  const references = [
    { label: `early prefix ${early.high}/${early.k}`, p: earlyRate },
    { label: `pooled ${long.K}/${long.N}`, p: long.rate },
  ];
  const conditioned = references.map((ref) => ({
    ...ref,
    others: sessions.slice(0, -1).map((s) => ({
      dir: s.dir,
      observed: `${s.K}/${s.N}`,
      pAtMost: binomialAtMost(s.K, s.N, ref.p),
    })),
  }));

  return { sessions, bounding, conditioned };
}

// --- printing ---------------------------------------------------------------

const pStr = (p) => (p >= 0.001 ? p.toFixed(4) : p.toExponential(2));

function report(a) {
  const L = [];
  L.push("THE FLOOR ARM'S SECOND-MODE RATE AGAINST SESSION ELAPSED TIME (rf2-6kxub)");
  L.push(`high-mode criterion: either segment's median certified legMedian at or above ${HIGH_MODE_B} B/write`);
  L.push('');
  L.push(`  ${'session'.padEnd(20)}${'runs'.padStart(6)}${'high'.padStart(6)}${'rate'.padStart(8)}${'minutes'.padStart(10)}   inadmissible`);
  for (const s of [...a.sessions, a.bounding]) {
    L.push(`  ${s.dir.padEnd(20)}${String(s.N).padStart(6)}${String(s.K).padStart(6)}${`${(100 * s.rate).toFixed(1)}%`.padStart(8)}${s.minutes.toFixed(1).padStart(10)}   ${s.skipped.map((x) => x.file).join(', ') || '—'}`);
  }
  L.push('');

  const long = a.sessions[a.sessions.length - 1];
  L.push(`WITHIN ${long.dir} — the one comparison that holds revision, box and date fixed:`);
  L.push('');
  L.push('  PREFIX SWEEP (one-tailed hypergeometric, P(at most this many high in the first k)):');
  for (const s of long.prefixSweep) {
    L.push(`    first ${String(s.k).padStart(2)} runs: ${String(s.high).padStart(2)} high   p = ${pStr(s.pAtMost)}`);
  }
  L.push('    ^ the figure MOVES with the cut. The cut is a choice; the test below is not.');
  L.push('');
  const rt = long.rankTest;
  L.push(`  MANN-WHITNEY on position-in-session, high against low — NO cut point:`);
  L.push(`    U = ${rt.U} against a null mean of ${rt.mu}, z = ${rt.z.toFixed(3)}, one-tail p = ${pStr(rt.pOneTail)}`);
  L.push('');

  L.push('THE OTHER TWO SESSIONS, read against two reference rates (one-tailed binomial):');
  for (const ref of a.conditioned) {
    L.push(`  against the ${ref.label} = ${(100 * ref.p).toFixed(1)}%:`);
    for (const o of ref.others) L.push(`    ${o.dir.padEnd(20)} observed ${o.observed.padEnd(7)} P(at most that many) = ${pStr(o.pAtMost)}`);
  }
  L.push('');

  L.push(`THE BOUND — ${a.bounding.dir}, ordered by generatedAt:`);
  const b0 = a.bounding.runs[0].at;
  for (const r of a.bounding.runs) {
    const lv = Object.entries(r.levels).map(([k, v]) => `${k} ${v}`).join('  ');
    L.push(`  +${((r.at - b0) / 60000).toFixed(1).padStart(5)} min  ${r.file.padEnd(34)}${r.high ? 'HIGH' : 'low '}   ${lv}`);
  }
  const hi = a.bounding.runs.findIndex((r) => r.high);
  L.push(`  session ${a.bounding.minutes.toFixed(1)} min; its single high run is number ${hi + 1} of ${a.bounding.runs.length}, at ` +
    `+${((a.bounding.runs[hi].at - b0) / 60000).toFixed(1)} min — EARLY, which is where the gradient says a high run is LEAST likely.`);
  L.push('  So the elapsed-time account does NOT explain this run, and does not discharge rf2-9jrhi.');
  return L;
}

// --- the self-test ----------------------------------------------------------

function selfTest() {
  const checks = [];
  const ok = (name, pass) => checks.push([pass, name]);

  // --- the combinatorics, against values computable by hand ---------------
  ok('hypergeometric: P(0 of 1 drawn from 2 with 1 high) = 1/2', Math.abs(hypergeometric(0, 1, 1, 2) - 0.5) < 1e-12);
  ok('hypergeometric: the full draw is certain', Math.abs(hypergeometricAtMost(3, 3, 5, 5) - 1) < 1e-12);
  ok('binomial: P(0 of 6 | p = 1/2) = 1/64', Math.abs(binomial(0, 6, 0.5) - 1 / 64) < 1e-12);
  ok('binomial: the whole distribution sums to 1', Math.abs(binomialAtMost(20, 20, 0.3) - 1) < 1e-9);
  ok('erf: the normal upper tail at 0 is 1/2', Math.abs(normalUpperTail(0) - 0.5) < 1e-9);
  ok('erf: the normal upper tail at 1.96 is 0.025', Math.abs(normalUpperTail(1.96) - 0.025) < 1e-3);
  // Mann-Whitney against a case whose U is countable by eye: highs at ranks
  // 3 and 4, lows at 1 and 2, so every one of the four pairs favours high.
  const mw = mannWhitney([3, 4], [1, 2]);
  ok('mann-whitney: a perfectly separated pair gives U = n1*n2', mw.U === 4);

  // --- the classifier ------------------------------------------------------
  // Pinned as literals so a change to the criterion reds here rather than
  // moving every rate on the record silently.
  ok('classifier: the bar is rf2-77gz8 21,000 B/write, unchanged', HIGH_MODE_B === 21000);

  // --- the committed corpus, pinned ---------------------------------------
  let a = null;
  try {
    a = analyse();
  } catch (e) {
    ok(`corpus: readable (${e.message})`, false);
    return checks;
  }
  const byDir = Object.fromEntries([...a.sessions, a.bounding].map((s) => [s.dir, s]));
  ok('corpus: workcount-n1b9h reads 0 of 6', byDir['workcount-n1b9h'].K === 0 && byDir['workcount-n1b9h'].N === 6);
  ok('corpus: alloc-77gz8 reads 2 of 20', byDir['alloc-77gz8'].K === 2 && byDir['alloc-77gz8'].N === 20);
  ok('corpus: alloc-c4hhk reads 37 of 69', byDir['alloc-c4hhk'].K === 37 && byDir['alloc-c4hhk'].N === 69);
  ok('corpus: alloc-c4hhk names armed-25 as the one inadmissible run',
    byDir['alloc-c4hhk'].skipped.length === 1 && /armed-25/.test(byDir['alloc-c4hhk'].skipped[0].file));
  ok('corpus: the three session durations are 8.2 / 19.7 / 88.3 min',
    Math.abs(byDir['workcount-n1b9h'].minutes - 8.2) < 0.05 &&
    Math.abs(byDir['alloc-77gz8'].minutes - 19.7) < 0.05 &&
    Math.abs(byDir['alloc-c4hhk'].minutes - 88.3) < 0.05);

  const sweep = Object.fromEntries(byDir['alloc-c4hhk'].prefixSweep.map((s) => [s.k, s]));
  ok('corpus: the first 19 runs carry 5 high, p = 0.0054', sweep[19].high === 5 && Math.abs(sweep[19].pAtMost - 0.0054) < 5e-5);
  // AND THE SWEEP DISCRIMINATES: this is the whole reason it is printed.
  ok('corpus: the same statistic reads 0.0210 at k = 17 and 0.0025 at k = 20',
    Math.abs(sweep[17].pAtMost - 0.0210) < 5e-4 && Math.abs(sweep[20].pAtMost - 0.0025) < 5e-4);
  ok('corpus: the cut choice moves the figure at least fourfold', sweep[17].pAtMost / sweep[20].pAtMost > 4);
  ok('corpus: the boundary-free rank test is weaker than the best cut',
    byDir['alloc-c4hhk'].rankTest.pOneTail > sweep[19].pAtMost);
  ok('corpus: the boundary-free rank test reads one-tail p = 0.020',
    Math.abs(byDir['alloc-c4hhk'].rankTest.pOneTail - 0.0198) < 1e-3);

  const early = a.conditioned[0];
  const pooled = a.conditioned[1];
  ok('corpus: against the early rate, n1b9h reads 0.16 and 77gz8 reads 0.072',
    Math.abs(early.others[0].pAtMost - 0.160) < 5e-3 && Math.abs(early.others[1].pAtMost - 0.072) < 5e-3);
  ok('corpus: against the pooled rate, 77gz8 reads 5.9e-05',
    Math.abs(pooled.others[1].pAtMost - 5.89e-5) < 1e-6);

  // --- THE BOUND, pinned hardest of all -----------------------------------
  // This is the half a write-up is most likely to lose, so it is the half the
  // fixtures hold most tightly.
  const b = byDir['alloc-9jrhi'];
  ok('bound: the bisect carries exactly one high run', b.K === 1 && b.N === 8);
  const idx = b.runs.findIndex((r) => r.high);
  ok('bound: its high run is SECOND of eight, not third', idx === 1);
  ok('bound: its high run sits at +3.6 min in a 14.9 min session',
    Math.abs((b.runs[idx].at - b.runs[0].at) / 60000 - 3.6) < 0.05 && Math.abs(b.minutes - 14.9) < 0.05);
  ok('bound: it is in the EARLY half, where the gradient predicts fewest highs', idx < b.N / 2);

  return checks;
}

// --- entry ------------------------------------------------------------------

if (require.main === module) {
  const argv = process.argv.slice(2);
  if (argv.includes('--self-test')) {
    const checks = selfTest();
    for (const [pass, name] of checks) console.log(`${pass ? 'ok  ' : 'FAIL'} ${name}`);
    const bad = checks.filter(([p]) => !p).length;
    console.log(`alloc_mode_rate_session self-test: ${checks.length - bad}/${checks.length} passed`);
    process.exit(bad ? 1 : 0);
  }
  for (const line of report(analyse())) console.log(line);
}

module.exports = {
  analyse,
  report,
  selfTest,
  runsOf,
  sessionOf,
  hypergeometric,
  hypergeometricAtMost,
  binomialAtMost,
  mannWhitney,
  HIGH_MODE_B,
};
