// THE IN-PAGE LADDER'S AGGREGATOR — fail-closed (rf2-409ab).
//
// `inpage_ladder_app.cljs` publishes per-arm summaries and a named
// decomposition; this recomputes BOTH from each run's RAW per-sample rounds
// and refuses if they disagree. It exists because two merged-PR audits on
// this instrument family (rf2-yd52q, rf2-emvod) found published ensembles
// that a fresh checkout could not regenerate, and named the remedy: land the
// datasets and one fail-closed command that rebuilds every published figure
// from them.
//
//   node implementation/freehand/test/re_frame/bench/hicasso/inpage_ladder_aggregate.cjs
//
// Exit 0  every run's stored aggregates reproduce from its own raw rounds,
//         its positive control met its own arithmetic, its arm order was
//         clean, and the ensemble table below is the studio page's.
// Exit 1  a disagreement, a missing dataset, or a run that recorded a guard
//         refusal or a failed control.
//
// ## The last two clauses of that promise were never computed (rf2-bml5u)
//
// The header above has said "a guard refusal or a failed control" since the
// file landed, and until rf2-bml5u nothing in it read either. That is the
// INVERSE of rf2-rr6do's defect class: not a refusal computed and left
// unread, but a refusal the file promised and never computed at all — and a
// gate that does not exist cannot be seen to fail, which is why a sweep for
// printed-but-unread refusals walked straight past it.
//
// The datasets carry no `:guard-refused` or `:control-ok` field, so there is
// no stored flag to read. What they do carry is every sample the page took,
// in the order it took them, which is strictly more: the two verdicts are
// RECONSTRUCTED here from the raw rounds and adjudicated by the same two
// rules the page itself applied.
//
// **The rules are the page's, reproduced exactly, and deliberately not
// improved.** `lane/control-verdict`'s `:ok?` asks whether the measured
// range OVERLAPS the ±slack band; `hd8-rows/positive-control!` asks the
// stricter question, whether EVERY round sits inside it. `lane.cljs` records
// the disagreement between them as a KNOWN DEFECT reserved to an operator
// ruling (rf2-egdaq / rf2-2rtt6.1) because tightening it turns a published
// pass into a published failure. This file reconstructs a check that was
// never run; it is not the place to also change the rule it reconstructs, so
// it applies the OVERLAP rule the page applied and no other. A tighter
// prospective policy is that open ruling's to make.

const fs = require('fs');
const path = require('path');
const guard = require('../../freehand/bench/order_guard.cjs');

const DIR = path.join(__dirname, 'data', 'inpage-ladder-409ab');
const RUNS = ['A', 'B', 'C', 'D'];
const EPS = 5e-4; // the page rounds every recorded figure to 4 places

// The predicted `ctl-2x / floor` for the `:large-template` row. It is NOT
// 2.00: the control builds the page at twice the CARDS and the chrome does
// not double, so the prediction is the element arithmetic's — see
// `shapes/census_clock_arms.cljs`, whose `doubled`/`elements-at`/
// `ctl-predicted` trio states 1.9759 for `:large-template` (and 1.9944 for
// `:feed`, 1.7255 for `:ordinary`). `inpage_ladder_app.cljs` passes that
// arithmetic's answer for its own `row-key`, `:large-template`. Hardcoded
// because this file reads stored EDN and never boots the page.
const CTL_PREDICTED = 1.9759;
// The slack `inpage_ladder_app.cljs` passed to `lane/control-verdict`.
const CONTROL_SLACK = 0.25;
// `lane/guard!`'s default, which is the tolerance the page ran at.
const GUARD_TOLERANCE = 0.1;

// --- the two EDN shapes this file reads, and nothing more general ---------

function readMap(text, key) {
  // `:key\n <form>` inside the run's outer map; forms are flat enough that
  // brace/bracket counting is exact, and a mis-parse fails the comparison
  // rather than passing silently.
  //
  // THE LINE ENDING IS NOT PART OF THE DATA (rf2-bml5u). The anchor is a
  // newline, and the datasets are tracked text that materialises CRLF on a
  // normal Windows checkout — so an anchor spelled LF-only matched nothing
  // and every run of this file on such a checkout died right here with
  // `dataset has no :rounds`, before a single figure was compared. A gate
  // that cannot start is not a gate. Normalising on read is the robust fix
  // and travels with the parser; a `.gitattributes` eol rule would only fix
  // the checkouts it reached.
  const src = text.replace(/\r\n/g, '\n');
  const at = src.indexOf('\n :' + key + '\n');
  if (at < 0) throw new Error(`dataset has no :${key}`);
  const from = src.indexOf('\n', at + 1) + 1;
  let depth = 0;
  let i = from;
  for (; i < src.length; i++) {
    const c = src[i];
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') {
      depth--;
      if (depth === 0) return src.slice(from, i + 1);
    }
  }
  throw new Error(`unterminated form for :${key}`);
}

function scalarMap(form) {
  const out = {};
  const re = /:([A-Za-z0-9\-+?*!]+)\s+(-?[0-9.]+)/g;
  let m;
  while ((m = re.exec(form))) out[m[1]] = Number(m[2]);
  return out;
}

function rounds(form) {
  const out = [];
  const re = /\[(\d+)\s+:([A-Za-z0-9\-]+)\s+(-?[0-9.]+)\]/g;
  let m;
  while ((m = re.exec(form))) out.push({ round: Number(m[1]), arm: m[2], ms: Number(m[3]) });
  return out;
}

/** One `{:mean _ :min _ :max _}` block out of a map of them, by arm id. */
function armBand(form, id) {
  const blk = new RegExp(`:${id}\\s*\\{([^}]*)\\}`).exec(form);
  return blk ? scalarMap('{' + blk[1] + '}') : null;
}

// --- the two statistics the page publishes, restated here ----------------

function trimmedMean(xs) {
  const v = [...xs].sort((a, b) => a - b);
  const k = Math.floor(0.1 * v.length);
  const w = v.slice(k, v.length - k);
  return w.reduce((a, b) => a + b, 0) / w.length;
}

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  const c = v.length;
  return c % 2 ? v[(c - 1) / 2] : (v[c / 2 - 1] + v[c / 2]) / 2;
}

const round4 = (x) => Math.round(x * 10000) / 10000;

// --- the decomposition, restated from the arm means ----------------------

function decompose(t) {
  return {
    ship: t.hicasso,
    uix: t.uix,
    coarse: t.coarse,
    bare: t.bare,
    floor: t.floor,
    deficit: t.hicasso - t.uix,
    'h-routing': t.local - t.nolink,
    'h-hiccup-build': t.nolink - t.nohiccup,
    'h-codec-walk': t.nohiccup - t.nowalk,
    'h-reads+commit': t.nowalk - t.noreads,
    'h-memo-fiber': t.noreads - t.nomemo,
    'h-shell': t.nomemo - t.bare,
    'h-total': t.hicasso - t.bare,
    'h-read-shape': t.local - t.coarse,
    'matched-shape-gap': t.coarse - t.uix,
    'u-routing': t.uixlocal - t.uixnolink,
    'u-markup': t.uixnolink - t.uixbare,
    'u-reads+hooks': t.uixbare - t.bare,
    'u-total': t.uix - t.bare,
    'fidelity-local-ship': t.local / t.hicasso,
    'fidelity-uixlocal-uix': t.uixlocal / t.uix,
  };
}

// --- the positive control, reconstructed ---------------------------------

/**
 * `inpage_ladder_app.cljs`'s `per-round-ratio` for one arm: EACH ROUND's own
 * `p50(id) / p50(floor)`, so a range is over rounds and never over raw
 * samples pooled across a drifting box. `:mean`, `:min` and `:max` are
 * rounded to 4 places exactly as the page rounded them, because those
 * rounded numbers are what the page then handed to `lane/control-verdict`.
 */
function perRoundRatio(raw, id, floorId, roundIds) {
  const p50In = (r, a) => p50(raw.filter((x) => x.round === r && x.arm === a).map((x) => x.ms));
  const vs = roundIds.map((r) => p50In(r, id) / p50In(r, floorId));
  return {
    mean: round4(vs.reduce((a, b) => a + b, 0) / vs.length),
    min: round4(Math.min(...vs)),
    max: round4(Math.max(...vs)),
  };
}

/**
 * `lane/control-verdict`'s rule, restated over stored numbers.
 *
 * OVERLAP, not every-round: `ok` asks whether the measured range meets the
 * ±`slack` band anywhere, which is the weaker of the two rules this
 * programme uses and the one this instrument published under. See the header
 * — the stricter reading is a KNOWN DEFECT reserved to rf2-2rtt6.1, and
 * applying it here would retroactively fail an already-published run.
 *
 * A non-finite measurement needs no separate test: `NaN <= hi` and
 * `NaN >= lo` are both false, so a control that did not produce numbers
 * cannot produce an `ok`.
 */
function controlVerdict(predicted, measured, slack) {
  const lo = predicted * (1 - slack);
  const hi = predicted * (1 + slack);
  const { min, max, mean } = measured;
  const ok = min <= hi && max >= lo;
  const f = (x) => (Number.isFinite(x) ? x.toFixed(3) : String(x));
  return {
    predicted,
    band: [round4(lo), round4(hi)],
    measured,
    slack,
    ok,
    why: ok
      ? `predicted ${f(predicted)}x, measured ${f(mean)}x [${f(min)}–${f(max)}] — the range ` +
        `meets the prediction within ±${(slack * 100).toFixed(0)}%`
      : `predicted ${f(predicted)}x, measured ${f(mean)}x [${f(min)}–${f(max)}] — DISJOINT ` +
        `from ±${(slack * 100).toFixed(0)}% of the prediction; the instrument did not see a ` +
        'change its own arithmetic says it must, so no figure in this run is reportable',
  };
}

// --- the arm-order guard, reconstructed ----------------------------------

/**
 * The sample stream `lane/guard!` adjudicated, rebuilt from the stored
 * rounds.
 *
 * `rounds-async!` conj'd `[round arm ms]` at the SAME site as its
 * `lane/collect!` call and under the same `(>= s warmup)` test, so the
 * stored vector IS the sequence the guard saw, in measured order and with
 * the warm-ups already dropped from both. `lane/collect!` tags each sample
 * with the arm that ran immediately before it and with its index in the
 * WHOLE run — not within a round, because the effect the guard is most
 * likely to catch is warm-up and warm-up does not restart at a round
 * boundary. The first sample has no predecessor.
 *
 * The stored `ms` is the page's own `round4` of what the guard saw. Chrome
 * clamps `performance.now` to 100 µs, so on this instrument that rounding
 * is lossless; where it is not, it moves a value by under 5e-5, which is
 * four orders below the guard's 10% tolerance.
 */
function guardSamples(raw) {
  return raw.map((x, i) => ({
    arm: x.arm,
    value: x.ms,
    predecessor: i === 0 ? null : raw[i - 1].arm,
    position: i,
  }));
}

// --- one run --------------------------------------------------------------

/**
 * Everything this file checks about one dataset. Answers
 * `{problems, decomposition, control, order}` — pure over the EDN text, so
 * the exit path can be exercised by mutating a fixture rather than by
 * measuring anything.
 */
function checkRun(runId, text) {
  const problems = [];
  const raw = rounds(readMap(text, 'rounds'));
  const storedArms = readMap(text, 'arms');
  const storedDec = scalarMap(readMap(text, 'decomposition'));
  const storedRatio = readMap(text, 'ratio-to-floor');

  const arms = [...new Set(raw.map((x) => x.arm))];
  const tmean = {};
  for (const a of arms) {
    const xs = raw.filter((x) => x.arm === a).map((x) => x.ms);
    tmean[a] = trimmedMean(xs);
    // The stored per-arm block must reproduce from the same raw samples.
    // `:min` and `:max` are checked too, and they are not decoration: the
    // trimmed mean deliberately discards the extremes, so a corrupted
    // outlier sample moves NEITHER the tmean nor the p50. Without these two
    // the gate would pass a dataset whose raw rounds had been edited.
    const blk = new RegExp(
      `:${a}\\s*\\{[^}]*:n\\s+(\\d+)[^}]*:min\\s+(-?[0-9.]+)[^}]*:max\\s+(-?[0-9.]+)` +
        `[^}]*:p50\\s+(-?[0-9.]+)[^}]*:tmean\\s+(-?[0-9.]+)`
    ).exec(storedArms);
    if (!blk) {
      problems.push(`run${runId}: no stored summary for arm ${a}`);
      continue;
    }
    if (Number(blk[1]) !== xs.length)
      problems.push(`run${runId}/${a}: stored n=${blk[1]}, raw rounds carry ${xs.length}`);
    if (Math.abs(Number(blk[2]) - Math.min(...xs)) > EPS)
      problems.push(`run${runId}/${a}: stored min ${blk[2]} != recomputed ${round4(Math.min(...xs))}`);
    if (Math.abs(Number(blk[3]) - Math.max(...xs)) > EPS)
      problems.push(`run${runId}/${a}: stored max ${blk[3]} != recomputed ${round4(Math.max(...xs))}`);
    if (Math.abs(Number(blk[4]) - p50(xs)) > EPS)
      problems.push(`run${runId}/${a}: stored p50 ${blk[4]} != recomputed ${round4(p50(xs))}`);
    if (Math.abs(Number(blk[5]) - tmean[a]) > EPS)
      problems.push(`run${runId}/${a}: stored tmean ${blk[5]} != recomputed ${round4(tmean[a])}`);
  }

  const dec = decompose(tmean);
  for (const [k, v] of Object.entries(dec)) {
    if (!(k in storedDec)) {
      problems.push(`run${runId}: decomposition has no :${k}`);
      continue;
    }
    if (Math.abs(storedDec[k] - v) > EPS)
      problems.push(`run${runId}: :${k} stored ${storedDec[k]} != recomputed ${round4(v)}`);
  }

  // --- THE POSITIVE CONTROL. Recomputed from the raw rounds, cross-checked
  // against what the page stored, and only then adjudicated: a control
  // adjudicated on a number this file could not regenerate would be gating
  // the dataset's own assertion rather than the measurement.
  const roundIds = [...new Set(raw.map((x) => x.round))].sort((a, b) => a - b);
  const ratio = perRoundRatio(raw, 'ctl-2x', 'floor', roundIds);
  const storedCtl = armBand(storedRatio, 'ctl-2x');
  if (!storedCtl) {
    problems.push(`run${runId}: :ratio-to-floor has no :ctl-2x`);
  } else {
    for (const k of ['mean', 'min', 'max']) {
      if (!(k in storedCtl)) problems.push(`run${runId}: :ratio-to-floor :ctl-2x has no :${k}`);
      else if (Math.abs(storedCtl[k] - ratio[k]) > EPS)
        problems.push(
          `run${runId}: :ratio-to-floor :ctl-2x :${k} stored ${storedCtl[k]} != recomputed ${ratio[k]}`
        );
    }
  }
  const control = controlVerdict(CTL_PREDICTED, ratio, CONTROL_SLACK);
  if (!control.ok) problems.push(`run${runId}: positive control FAILED — ${control.why}`);

  // --- THE ARM-ORDER GUARD, over the page's own sample stream and by the
  // page's own adjudicator. `refuse` is the authority; the per-factor loop
  // exists to say WHICH arm, and the fallback exists so a refusal can never
  // reach the exit as an empty list.
  const order = guard.verdict(guardSamples(raw), { tolerance: GUARD_TOLERANCE });
  if (order.refuse) {
    const before = problems.length;
    for (const [arm, a] of Object.entries(order.arms)) {
      for (const [f, res] of Object.entries(a.factors)) {
        if (res.status !== 'clean')
          problems.push(
            `run${runId}: arm-order guard REFUSED (rf2-88pie) — ${arm} by ${f}: ${res.why}`
          );
      }
    }
    if (problems.length === before)
      problems.push(
        `run${runId}: arm-order guard REFUSED (rf2-88pie) — contaminated=${order.contaminated}, ` +
          `unchecked=${order.unchecked}`
      );
  }

  return { problems, decomposition: dec, control, order };
}

// --- run ------------------------------------------------------------------

const TERMS = [
  'ship', 'uix', 'coarse', 'bare', 'floor', 'deficit',
  'h-routing', 'h-hiccup-build', 'h-codec-walk', 'h-reads+commit',
  'h-memo-fiber', 'h-shell', 'h-total', 'h-read-shape', 'matched-shape-gap',
  'u-routing', 'u-markup', 'u-reads+hooks', 'u-total',
  'fidelity-local-ship', 'fidelity-uixlocal-uix',
];

function main() {
  const problems = [];
  const perRun = {};
  const verdicts = [];

  for (const r of RUNS) {
    const p = path.join(DIR, `run${r}.edn`);
    if (!fs.existsSync(p)) {
      problems.push(`run${r}: dataset missing at ${p}`);
      continue;
    }
    const out = checkRun(r, fs.readFileSync(p, 'utf8'));
    problems.push(...out.problems);
    perRun[r] = out.decomposition;
    verdicts.push(
      `;;   run ${r}  control  ${out.control.ok ? 'ok  ' : 'FAIL'} band [` +
        `${out.control.band[0]}–${out.control.band[1]}]  ${out.control.why}`
    );
    verdicts.push(
      `;;   run ${r}  arm order ${out.order.refuse ? 'REFUSED' : 'clean'} ` +
        `at tolerance ${(out.order.tolerance * 100).toFixed(0)}%`
    );
  }

  // Published on every run, passing or not: a control quoted only when it
  // passes is not a control.
  console.log(';; ==== POSITIVE CONTROL AND ARM ORDER, PER RUN (reconstructed) ====');
  console.log(`;;   predicted ctl-2x/floor ${CTL_PREDICTED} — the large-template element arithmetic`);
  for (const line of verdicts) console.log(line);

  if (problems.length) {
    console.error('[inpage-ladder] REFUSED — the published aggregates do not reproduce:');
    for (const p of problems) console.error('  ' + p);
    return 1;
  }

  const f = (x) => x.toFixed(4).padStart(9);
  console.log(';; ==== IN-PAGE LADDER — ENSEMBLE OVER 4 RUNS (ms, in-page flushSync window) ====');
  console.log(
    ';;   ' + 'term'.padEnd(22) + RUNS.map((r) => ('run ' + r).padStart(9)).join('') +
      '  |' + 'mean'.padStart(9) + 'min'.padStart(9) + 'max'.padStart(9)
  );
  const mean = {};
  for (const t of TERMS) {
    const vs = RUNS.map((r) => perRun[r][t]);
    mean[t] = vs.reduce((a, b) => a + b, 0) / vs.length;
    console.log(
      ';;   ' + t.padEnd(22) + vs.map(f).join('') +
        '  |' + f(mean[t]) + f(Math.min(...vs)) + f(Math.max(...vs))
    );
  }

  const gapReads = mean['h-reads+commit'] - mean['u-reads+hooks'];
  const gapMarkup = mean['h-hiccup-build'] + mean['h-codec-walk'] - mean['u-markup'];
  const gapRouting = mean['h-routing'] - mean['u-routing'];
  const gapShell = mean['h-memo-fiber'] + mean['h-shell'];
  const named = gapReads + gapMarkup + gapRouting + gapShell;
  const pct = (x) => ((100 * x) / mean.deficit).toFixed(0) + '%';

  console.log(';; ==== THE DEFICIT, BY TERM ====');
  console.log(`;;   ship / uix (in-page)  ${(mean.ship / mean.uix).toFixed(4)}   published 6.100/3.900 = ${(6.1 / 3.9).toFixed(4)}`);
  console.log(`;;   deficit               ${f(mean.deficit)}`);
  console.log(`;;     reads              ${f(gapReads)}  ${pct(gapReads)}`);
  console.log(`;;     markup             ${f(gapMarkup)}  ${pct(gapMarkup)}`);
  console.log(`;;     routing            ${f(gapRouting)}  ${pct(gapRouting)}`);
  console.log(`;;     shell + memo       ${f(gapShell)}  ${pct(gapShell)}`);
  console.log(`;;     residual           ${f(mean.deficit - named)}  ${pct(mean.deficit - named)}`);
  console.log(`;;   read shape on our own arm (local - coarse) ${f(mean['h-read-shape'])}  ${pct(mean['h-read-shape'])} of the deficit`);
  console.log(`;;   at MATCHED read shape (coarse - uix)       ${f(mean['matched-shape-gap'])}`);
  console.log('[inpage-ladder] ok — every published aggregate reproduces from the raw rounds, ' +
    'its control met its own arithmetic, and its arm order was clean');
  return 0;
}

module.exports = {
  readMap,
  scalarMap,
  rounds,
  armBand,
  perRoundRatio,
  controlVerdict,
  guardSamples,
  checkRun,
  main,
  CTL_PREDICTED,
  CONTROL_SLACK,
  GUARD_TOLERANCE,
  EPS,
};

if (require.main === module) {
  const code = main();
  if (code !== 0) process.exit(code);
}
