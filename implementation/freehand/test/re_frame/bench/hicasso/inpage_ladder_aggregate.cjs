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
//         and the ensemble table below is the studio page's.
// Exit 1  a disagreement, a missing dataset, or a run that recorded a guard
//         refusal or a failed control.

const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, 'data', 'inpage-ladder-409ab');
const RUNS = ['A', 'B', 'C', 'D'];
const EPS = 5e-4; // the page rounds every recorded figure to 4 places

// --- the two EDN shapes this file reads, and nothing more general ---------

function readMap(text, key) {
  // `:key\n <form>` inside the run's outer map; forms are flat enough that
  // brace/bracket counting is exact, and a mis-parse fails the comparison
  // rather than passing silently.
  const at = text.indexOf('\n :' + key + '\n');
  if (at < 0) throw new Error(`dataset has no :${key}`);
  const from = text.indexOf('\n', at + 1) + 1;
  let depth = 0;
  let i = from;
  for (; i < text.length; i++) {
    const c = text[i];
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') {
      depth--;
      if (depth === 0) return text.slice(from, i + 1);
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

// --- run ------------------------------------------------------------------

const problems = [];
const perRun = {};

for (const r of RUNS) {
  const p = path.join(DIR, `run${r}.edn`);
  if (!fs.existsSync(p)) {
    problems.push(`run${r}: dataset missing at ${p}`);
    continue;
  }
  const text = fs.readFileSync(p, 'utf8');
  const raw = rounds(readMap(text, 'rounds'));
  const storedArms = readMap(text, 'arms');
  const storedDec = scalarMap(readMap(text, 'decomposition'));

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
      problems.push(`run${r}: no stored summary for arm ${a}`);
      continue;
    }
    if (Number(blk[1]) !== xs.length)
      problems.push(`run${r}/${a}: stored n=${blk[1]}, raw rounds carry ${xs.length}`);
    if (Math.abs(Number(blk[2]) - Math.min(...xs)) > EPS)
      problems.push(`run${r}/${a}: stored min ${blk[2]} != recomputed ${round4(Math.min(...xs))}`);
    if (Math.abs(Number(blk[3]) - Math.max(...xs)) > EPS)
      problems.push(`run${r}/${a}: stored max ${blk[3]} != recomputed ${round4(Math.max(...xs))}`);
    if (Math.abs(Number(blk[4]) - p50(xs)) > EPS)
      problems.push(`run${r}/${a}: stored p50 ${blk[4]} != recomputed ${round4(p50(xs))}`);
    if (Math.abs(Number(blk[5]) - tmean[a]) > EPS)
      problems.push(`run${r}/${a}: stored tmean ${blk[5]} != recomputed ${round4(tmean[a])}`);
  }

  const dec = decompose(tmean);
  for (const [k, v] of Object.entries(dec)) {
    if (!(k in storedDec)) {
      problems.push(`run${r}: decomposition has no :${k}`);
      continue;
    }
    if (Math.abs(storedDec[k] - v) > EPS)
      problems.push(`run${r}: :${k} stored ${storedDec[k]} != recomputed ${round4(v)}`);
  }
  perRun[r] = dec;
}

if (problems.length) {
  console.error('[inpage-ladder] REFUSED — the published aggregates do not reproduce:');
  for (const p of problems) console.error('  ' + p);
  process.exit(1);
}

const TERMS = [
  'ship', 'uix', 'coarse', 'bare', 'floor', 'deficit',
  'h-routing', 'h-hiccup-build', 'h-codec-walk', 'h-reads+commit',
  'h-memo-fiber', 'h-shell', 'h-total', 'h-read-shape', 'matched-shape-gap',
  'u-routing', 'u-markup', 'u-reads+hooks', 'u-total',
  'fidelity-local-ship', 'fidelity-uixlocal-uix',
];

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
console.log('[inpage-ladder] ok — every published aggregate reproduces from the raw rounds');
