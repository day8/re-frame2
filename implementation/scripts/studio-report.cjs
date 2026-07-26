#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-lnecd — deleted before the PR.
 *
 * Reduces the raw probe output to the tables the report quotes. Kept separate
 * from the driver so the reduction can be re-run without re-measuring, and so
 * the arithmetic behind every published figure is one readable file.
 */

'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const ARMS = ['free-floor', 'free-cc', 'free-ic', 'free-ik', 'free-i',
              'read-floor', 'read-cc', 'read-ic', 'read-i'];

const med = (a) => { const s = [...a].sort((x, y) => x - y); const m = s.length >> 1;
                     return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
const rng = (a) => `[${Math.min(...a).toFixed(3)}-${Math.max(...a).toFixed(3)}]`;

/* ---- clock ---------------------------------------------------------------- */
const rounds = [];
for (let i = 1; i <= 99; i += 1) {
  const f = path.join(ROOT, 'out', `clock-r${i}.json`);
  if (!fs.existsSync(f)) continue;
  const d = JSON.parse(fs.readFileSync(f, 'utf8'));
  for (const [w, p] of Object.entries(d.parity)) {
    if (!p['agree?']) { console.error(`PARITY FAIL round ${i} witness ${w}`); process.exit(1); }
  }
  const r = { _i: i };
  for (const a of ARMS) r[a] = med(d.mounts[a].map((s) => s['settle-ms']));
  rounds.push(r);
}
console.log(`=== WALL CLOCK — ${rounds.length} rounds, each a fresh process, arms interleaved within a round`);
console.log('settle-ms p50, per round (absolute figures are NOT comparable across rounds)');
console.log('arm'.padEnd(11) + rounds.map((r) => `r${r._i}`.padStart(7)).join(''));
for (const a of ARMS) console.log(a.padEnd(11) + rounds.map((r) => r[a].toFixed(2).padStart(7)).join(''));

const floorDrift = (k) => {
  const v = rounds.map((r) => r[k]);
  return `${Math.min(...v).toFixed(2)}-${Math.max(...v).toFixed(2)} ms ` +
         `(${(100 * (Math.max(...v) / Math.min(...v) - 1)).toFixed(0)}% drift)`;
};
console.log(`\nin-run floor drift across rounds: free-floor ${floorDrift('free-floor')}, ` +
            `read-floor ${floorDrift('read-floor')}`);

console.log('\nratio to the IN-RUN React floor (mean of rounds, [min-max])');
for (const a of ARMS) {
  const f = a.startsWith('free') ? 'free-floor' : 'read-floor';
  const v = rounds.map((r) => r[a] / r[f]);
  console.log(`  ${a.padEnd(11)} ${mean(v).toFixed(3)} ${rng(v)}`);
}

const pair = (label, num, den) => {
  const v = rounds.map((r) => r[num] / r[den]);
  const crosses = Math.min(...v) <= 1 && Math.max(...v) >= 1;
  console.log(`  ${label.padEnd(52)} ${mean(v).toFixed(3)} ${rng(v)}${crosses ? '  <- straddles 1.0' : ''}`);
  return mean(v);
};
console.log('\nthe clock ablation');
const kept    = pair('free-ik / free-ic   ViewCell KEPT vs ELIDED', 'free-ik', 'free-ic');
const compKpt = pair('free-i  / free-ik   interpreted vs compiled, BOTH keep', 'free-i', 'free-ik');
const total   = pair('free-i  / free-ic   interpreted vs compiled, elided', 'free-i', 'free-ic');
pair('free-i  / free-cc   interpreted vs FULLY compiled', 'free-i', 'free-cc');
pair('read-i  / read-ic   reactive leaf, neither may elide', 'read-i', 'read-ic');
console.log(`\n  decomposition check: ${kept.toFixed(3)} x ${compKpt.toFixed(3)} = ` +
            `${(kept * compKpt).toFixed(3)} vs measured total ${total.toFixed(3)}`);
console.log(`  elision's share of the log-effect: ` +
            `${(100 * Math.log(kept) / Math.log(total)).toFixed(0)}%`);

/* ---- retained heap -------------------------------------------------------- */
const rf = path.join(ROOT, 'out', 'retain-1.json');
if (fs.existsSync(rf)) {
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  console.log(`\n=== RETAINED HEAP — ${d.rounds} rounds, ${d.roots} roots x ` +
              `${d['boundaries-per-root']} boundaries held live`);
  console.log(`positive control (expect ~4.7 MB): sampler ` +
              `${d['positive-control']['sampler-mb'].toFixed(2)} MB, ` +
              `occupancy ${d['positive-control']['occupancy-mb'].toFixed(2)} MB`);
  const S = d['sampled-bytes-per-boundary'], O = d['occupancy-bytes-per-boundary'];
  const r = {};
  console.log('\narm          sampler B/boundary   occupancy B/boundary');
  for (const a of ARMS) {
    r[a] = { s: med(S[a]), o: med(O[a]) };
    console.log(`  ${a.padEnd(11)} ${med(S[a]).toFixed(0).padStart(6)} ` +
      `[${Math.min(...S[a]).toFixed(0)}-${Math.max(...S[a]).toFixed(0)}]`.padEnd(14) +
      `${med(O[a]).toFixed(0).padStart(8)} ` +
      `[${Math.min(...O[a]).toFixed(0)}-${Math.max(...O[a]).toFixed(0)}]`);
  }
  const p2 = (label, n, dd) =>
    console.log(`  ${label.padEnd(52)} ${(r[n].s / r[dd].s).toFixed(3)} sampler / ` +
                `${(r[n].o / r[dd].o).toFixed(3)} occupancy`);
  console.log('\nthe retained-heap ablation');
  console.log(`  one kept ViewCell costs ${(r['free-ik'].s - r['free-ic'].s).toFixed(0)} B sampler / ` +
              `${(r['free-ik'].o - r['free-ic'].o).toFixed(0)} B occupancy, per boundary`);
  p2('free-ik / free-ic   ViewCell KEPT vs ELIDED', 'free-ik', 'free-ic');
  p2('free-i  / free-ik   interpreted vs compiled, BOTH keep', 'free-i', 'free-ik');
  p2('free-i  / free-ic   interpreted vs compiled, elided', 'free-i', 'free-ic');
  p2('read-i  / read-ic   reactive leaf, neither may elide', 'read-i', 'read-ic');
  console.log('\n  over the React floor (sampler):');
  for (const a of ARMS) {
    const f = a.startsWith('free') ? 'free-floor' : 'read-floor';
    console.log(`    ${a.padEnd(11)} ${(r[a].s / r[f].s).toFixed(2)}x`);
  }
}
