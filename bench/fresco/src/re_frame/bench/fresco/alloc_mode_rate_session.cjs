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
// ## ADMISSIBILITY, WHICH IS A SEPARATE QUESTION AND FAILS CLOSED
//
// The bar above says which of two MODES an admissible reading sits in. It says
// nothing about whether there is a reading at all, and those two questions were
// once run together here to this reader's cost.
//
// **A FAILED POSITIVE CONTROL IS NOT A LOW-MODE OBSERVATION.** Every floor
// record carries `alloc.controlVerdict`, the verdict of the run's own positive
// control — a control that did not certify says the INSTRUMENT was not reading
// correctly during that run, so the arm figures beside it are not a measurement
// of anything. An earlier version of `runsOf` admitted every dataset carrying an
// `alloc.perRound` array and classified anything it could not read as LOW, so
// two control-refused runs were silently counted as low-mode observations:
// `alloc-77gz8/run12-a4a1537cb71` and
// `alloc-9jrhi/bisect-5-a-4a1537cb71-replicate`. Counting a refused control low
// biases every rate on this record DOWNWARD, and it did.
//
// So `admit()` below fails closed on three shapes, and `runsOf` NAMES each
// exclusion rather than dropping it:
//
//   no alloc block             — the dataset holds no allocation reading at all
//   control refused            — `alloc.controlVerdict.ok !== true`
//   no certified segment level — every window was refused, so no level exists
//
// The rule is the one `docs/design/hicasso/studio/the-eight-signs-are-one-block.md`
// already applies to the same corpus, and it is applied here identically so the
// two records cannot disagree about which runs count as readings.
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

const archive = require('./data_archive.cjs');

const DATA = archive.DATA;

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

// ADMISSIBILITY, as a pure function of one parsed record, so the self-test can
// exercise every refusal shape without a fixture directory on disk. It returns
// either a reading or a NAMED reason there is none — never a default, and never
// a `false` that a caller could read as "low".
function admit(raw) {
  const a = raw && raw.alloc;
  // A dataset with no `alloc` block carries no reading at all.
  if (!a || !Array.isArray(a.perRound)) return { ok: false, why: 'no alloc block' };
  // A FAILED POSITIVE CONTROL IS NOT A LOW-MODE OBSERVATION. See the header:
  // the control certifies that the instrument was reading correctly during the
  // run, so a refused control voids the arm figures beside it rather than
  // contributing a low one. A record with no verdict at all is refused on the
  // same principle — an unasserted control is not a passed control.
  if (!a.controlVerdict || a.controlVerdict.ok !== true) return { ok: false, why: 'control refused' };

  const perSegment = {};
  for (const round of a.perRound) {
    for (const w of Object.values(round.arms || {})) {
      if (!w.certified) continue;
      (perSegment[w.segment] = perSegment[w.segment] || []).push(w.legMedian);
    }
  }
  const levels = {};
  for (const [seg, xs] of Object.entries(perSegment)) levels[seg] = median(xs);
  const readable = Object.values(levels).filter((v) => v !== null && Number.isFinite(v));
  // Every window refused, so the run holds no level to classify. Falling through
  // here would have produced `high: false` from an EMPTY set — the exact shape
  // that made an unreadable run look like a low-mode one.
  if (!readable.length) return { ok: false, why: 'no certified segment level' };

  return { ok: true, levels, high: readable.some((v) => v >= HIGH_MODE_B) };
}

function runsOf(dir) {
  const full = path.join(DATA, dir);
  const out = [];
  const skipped = [];
  for (const f of fs.readdirSync(full).filter((x) => x.endsWith('.json')).sort()) {
    const raw = JSON.parse(fs.readFileSync(path.join(full, f), 'utf8'));
    const verdict = admit(raw);
    // EVERY exclusion is named and carries its reason. None is silently
    // dropped and none is counted low — a dataset with no reading is not a
    // reading of zero, and a dataset whose control failed is not a reading.
    if (!verdict.ok) {
      skipped.push({ file: f, why: verdict.why });
      continue;
    }
    out.push({
      file: f.replace(/\.json$/, ''),
      at: Date.parse(raw.generatedAt),
      levels: verdict.levels,
      high: verdict.high,
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
    // Every exclusion is printed WITH ITS REASON. A count of admissible runs
    // that did not say what it left out, and why, would be the defect this
    // reader was corrected for.
    const excl = s.skipped.map((x) => `${x.file.replace(/\.json$/, '')} (${x.why})`).join(', ') || '—';
    L.push(`  ${s.dir.padEnd(20)}${String(s.N).padStart(6)}${String(s.K).padStart(6)}${`${(100 * s.rate).toFixed(1)}%`.padStart(8)}${s.minutes.toFixed(1).padStart(10)}   ${excl}`);
  }
  L.push('');
  L.push('  INADMISSIBLE means NO READING, never a low reading. A failed positive control voids');
  L.push('  the arm figures beside it; counting such a run low biases every rate here downward.');
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

  // --- ADMISSIBILITY, every refusal shape, on synthetic records -----------
  // These are pure-function fixtures rather than corpus pins, so they hold even
  // if the corpus one day carries none of these shapes. The corpus pins below
  // then check that the rule is actually REACHING the committed data.
  const window = (segment, legMedian) => ({ segment, legMedian, certified: true });
  const synth = (over) => ({
    generatedAt: '2026-08-01T00:00:00.000Z',
    alloc: {
      controlVerdict: { ok: true },
      perRound: [{ arms: { a: window('reagent-subs', 19000), b: window('uix-subs', 19500) } }],
      ...over,
    },
  });

  ok('admit: a clean record is admitted and reads low', (() => {
    const v = admit(synth({}));
    return v.ok === true && v.high === false && v.levels['uix-subs'] === 19500;
  })());
  ok('admit: a clean record above the bar reads high', (() => {
    const v = admit({ generatedAt: 'x', alloc: { controlVerdict: { ok: true }, perRound: [{ arms: { a: window('uix-subs', 22000) } }] } });
    return v.ok === true && v.high === true;
  })());
  // SHAPE 1 — no alloc block. `alloc-c4hhk/armed-25` is this shape.
  ok('admit: SHAPE 1 — no alloc block is refused and named', (() => {
    const v = admit({ generatedAt: 'x' });
    return v.ok === false && v.why === 'no alloc block';
  })());
  // SHAPE 2 — the control refused. `alloc-77gz8/run12` and
  // `alloc-9jrhi/bisect-5` are this shape, and the reason this reader was
  // corrected: BOTH were previously counted LOW.
  ok('admit: SHAPE 2 — a refused control is refused, NOT counted low', (() => {
    const v = admit(synth({ controlVerdict: { ok: false, perDouble: 11.8, differential: 14.2 } }));
    return v.ok === false && v.why === 'control refused' && v.high === undefined;
  })());
  ok('admit: SHAPE 2 — a MISSING control verdict is refused too', (() => {
    const r = synth({});
    delete r.alloc.controlVerdict;
    return admit(r).why === 'control refused';
  })());
  // AND IT DISCRIMINATES: the refused record's levels are perfectly readable
  // and BELOW the bar, so a reader that only checked readability would call it
  // low. Refusal has to come from the control, not from the numbers.
  ok('admit: SHAPE 2 — the refused record would otherwise have read LOW', (() => {
    const clean = admit(synth({}));
    return clean.ok === true && clean.high === false;
  })());
  // SHAPE 3 — no certified segment level. `alloc-2rtt6-138/run1` is this shape
  // in the committed corpus, though not in the four sessions read here.
  ok('admit: SHAPE 3 — every window refused is refused, not read as low', (() => {
    const v = admit(synth({ perRound: [{ arms: { a: { segment: 'uix-subs', legMedian: 19500, certified: false } } }] }));
    return v.ok === false && v.why === 'no certified segment level';
  })());
  ok('admit: SHAPE 3 — an empty perRound is refused', (() => admit(synth({ perRound: [] })).why === 'no certified segment level')());
  // The ordering of the refusals matters: a record that is BOTH control-refused
  // and unreadable is reported against the control, which is the stronger fact.
  ok('admit: a doubly-bad record is named for the control', (() => {
    const v = admit(synth({ controlVerdict: { ok: false }, perRound: [] }));
    return v.why === 'control refused';
  })());

  // --- the committed corpus, pinned ---------------------------------------
  if (!archive.present()) {
    archive.skipped('alloc_mode_rate_session: the corpus-backed checks');
    return checks;
  }
  let a = null;
  try {
    a = analyse();
  } catch (e) {
    ok(`corpus: readable (${e.message})`, false);
    return checks;
  }
  const byDir = Object.fromEntries([...a.sessions, a.bounding].map((s) => [s.dir, s]));
  ok('corpus: workcount-n1b9h reads 0 of 6', byDir['workcount-n1b9h'].K === 0 && byDir['workcount-n1b9h'].N === 6);
  // THE CORRECTED DENOMINATOR. 2 of 20 was the figure before the admissibility
  // repair; run12's control was refused, so the readings are 2 of 19.
  ok('corpus: alloc-77gz8 reads 2 of 19 — NOT 2 of 20', byDir['alloc-77gz8'].K === 2 && byDir['alloc-77gz8'].N === 19);
  ok('corpus: alloc-c4hhk reads 37 of 69', byDir['alloc-c4hhk'].K === 37 && byDir['alloc-c4hhk'].N === 69);
  ok('corpus: alloc-9jrhi reads 1 of 7 — NOT 1 of 8', byDir['alloc-9jrhi'].K === 1 && byDir['alloc-9jrhi'].N === 7);
  // AND EVERY EXCLUSION IS NAMED WITH ITS REASON, on the committed corpus, so
  // the rule is shown to REACH the data rather than only the synthetic pins.
  ok('corpus: alloc-c4hhk names armed-25, refused for having no alloc block',
    byDir['alloc-c4hhk'].skipped.length === 1 &&
    /armed-25/.test(byDir['alloc-c4hhk'].skipped[0].file) &&
    byDir['alloc-c4hhk'].skipped[0].why === 'no alloc block');
  ok('corpus: alloc-77gz8 names run12, refused for its CONTROL',
    byDir['alloc-77gz8'].skipped.length === 1 &&
    /run12-a4a1537cb71/.test(byDir['alloc-77gz8'].skipped[0].file) &&
    byDir['alloc-77gz8'].skipped[0].why === 'control refused');
  ok('corpus: alloc-9jrhi names bisect-5, refused for its CONTROL',
    byDir['alloc-9jrhi'].skipped.length === 1 &&
    /bisect-5-a-4a1537cb71-replicate/.test(byDir['alloc-9jrhi'].skipped[0].file) &&
    byDir['alloc-9jrhi'].skipped[0].why === 'control refused');
  ok('corpus: workcount-n1b9h excludes nothing', byDir['workcount-n1b9h'].skipped.length === 0);
  // THE TWO REFUSED RUNS READ BELOW THE BAR, which is exactly why the old
  // reader counted them low and why nothing but the control catches them.
  ok('corpus: both refused runs would have read LOW, so only the control excludes them', (() => {
    const raw = (d, f) => JSON.parse(fs.readFileSync(path.join(DATA, d, f), 'utf8'));
    const check = (d, f) => {
      const r = raw(d, f);
      const v = admit(r);
      if (v.ok !== false || v.why !== 'control refused') return false;
      // Re-admit the same record with a passing control and confirm it reads low.
      const forced = { ...r, alloc: { ...r.alloc, controlVerdict: { ok: true } } };
      const w = admit(forced);
      return w.ok === true && w.high === false;
    };
    return check('alloc-77gz8', 'run12-a4a1537cb71.json') &&
      check('alloc-9jrhi', 'bisect-5-a-4a1537cb71-replicate.json');
  })());
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
  // RECOMPUTED ON THE CORRECTED DENOMINATOR. 77gz8's 2 of 19 reads 0.0894 and
  // 1.15e-4; on the stale 2 of 20 the same two figures were 0.0721 and 5.89e-5.
  ok('corpus: against the early rate, n1b9h reads 0.160 and 77gz8 reads 0.089',
    Math.abs(early.others[0].pAtMost - 0.160) < 5e-3 && Math.abs(early.others[1].pAtMost - 0.0894) < 5e-3);
  ok('corpus: against the pooled rate, 77gz8 reads 1.15e-04 — not the stale 5.89e-05',
    Math.abs(pooled.others[1].pAtMost - 1.149e-4) < 1e-6 && pooled.others[1].pAtMost > 6e-5);
  ok('corpus: against the pooled rate, n1b9h reads 0.0099', Math.abs(pooled.others[0].pAtMost - 0.00995) < 5e-4);
  // AND THE DIRECTION OF THE REPAIR IS PINNED: dropping a refused run that had
  // been counted LOW can only make the short session look LESS extreme, so both
  // corrected figures must sit ABOVE their stale counterparts. A future change
  // that silently re-admitted a refused control would push them back down.
  ok('corpus: the repair moved both 77gz8 figures UPWARD, as dropping a false low must',
    early.others[1].pAtMost > 0.0721 && pooled.others[1].pAtMost > 5.89e-5);

  // --- THE BOUND, pinned hardest of all -----------------------------------
  // This is the half a write-up is most likely to lose, so it is the half the
  // fixtures hold most tightly.
  const b = byDir['alloc-9jrhi'];
  ok('bound: the bisect carries exactly one high run among SEVEN admissible', b.K === 1 && b.N === 7);
  const idx = b.runs.findIndex((r) => r.high);
  ok('bound: its high run is SECOND, not third', idx === 1);
  ok('bound: its high run sits at +3.6 min in a 14.9 min session',
    Math.abs((b.runs[idx].at - b.runs[0].at) / 60000 - 3.6) < 0.05 && Math.abs(b.minutes - 14.9) < 0.05);
  ok('bound: it is in the EARLY half, where the gradient predicts fewest highs', idx < b.N / 2);
  // THE BOUND SURVIVES THE ADMISSIBILITY REPAIR, and that is the point of
  // pinning it here: the run excluded from the bisect sits at +11.3 min, LATE,
  // so removing it can only make the surviving high run look earlier still.
  ok('bound: the excluded bisect run is NOT the high one', !/bisect-5/.test(b.runs[idx].file));
  ok('bound: excluding it left the session span unchanged at 14.9 min', Math.abs(b.minutes - 14.9) < 0.05);

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
  admit,
  runsOf,
  sessionOf,
  hypergeometric,
  hypergeometricAtMost,
  binomialAtMost,
  mannWhitney,
  HIGH_MODE_B,
};
