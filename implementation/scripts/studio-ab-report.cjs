#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-xu6rx — deleted before the PR.
 *
 * Reads out/ab/raw-<round>-<arm>.json and prints, per round, every arm's
 * mount cost as a RATIO to the no-substrate React floor measured in that same
 * run. Absolute milliseconds are not comparable between rounds on this box —
 * lnecd measured the floor arm alone drifting 37% — so the ratio is the only
 * quantity carried across rounds.
 *
 *   node scripts/studio-ab-report.cjs [metric]      default metric: react-ms
 */

'use strict';
const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', 'out', 'ab');
const METRIC = process.argv[2] || 'react-ms';

const p50 = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.max(0, Math.ceil(0.5 * s.length) - 1)];
};
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const f3 = (x) => (Number.isFinite(x) ? x.toFixed(3) : '—');

const files = fs.readdirSync(DIR).filter((f) => /^raw-\d+-(base|tuned)\.json$/.test(f));
const rows = [];
for (const f of files) {
  const [, round, arm] = f.match(/^raw-(\d+)-(base|tuned)\.json$/);
  const j = JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
  const at = (id) => p50(j.mounts[id].map((r) => r[METRIC]));
  const floors = { w1: at('w1-floor'), w2: at('w2-floor') };
  rows.push({
    round: Number(round),
    arm,
    'w1-int/floor': at('w1-interpreted') / floors.w1,
    'w1-cmp/floor': at('w1-compiled') / floors.w1,
    'w1-int-overhead': (at('w1-interpreted') - floors.w1) / floors.w1,
    'w2-int/floor': at('w2-interpreted') / floors.w2,
    'w2-cmp/floor': at('w2-compiled') / floors.w2,
    'w2r-int/cmp': at('w2r-interpreted') / at('w2r-compiled'),
    'w3-int/cmp': at('w3-interpreted') / at('w3-compiled'),
    'w1-floor-abs-ms': floors.w1,
    'w1-int-abs-ms': at('w1-interpreted'),
    parity: Object.values(j.parity).every((p) => p['agree?']),
  });
}
rows.sort((a, b) => a.round - b.round || a.arm.localeCompare(b.arm));

const cols = Object.keys(rows[0]).filter((k) => k !== 'round' && k !== 'arm');
console.log(`metric: ${METRIC}  (p50 per arm, per run)`);
console.log(['round', 'arm', ...cols].join('\t'));
for (const r of rows) {
  console.log([r.round, r.arm, ...cols.map((c) => (typeof r[c] === 'number' ? f3(r[c]) : String(r[c])))].join('\t'));
}

console.log('\n--- across rounds: mean [min-max] ---');
console.log(['quantity', 'base', 'tuned', 'delta %'].join('\t'));
for (const c of cols) {
  if (typeof rows[0][c] !== 'number') continue;
  const g = (arm) => rows.filter((r) => r.arm === arm).map((r) => r[c]);
  const [b, t] = [g('base'), g('tuned')];
  const cell = (xs) => `${f3(mean(xs))} [${f3(Math.min(...xs))}-${f3(Math.max(...xs))}]`;
  const d = 100 * (mean(t) - mean(b)) / mean(b);
  const overlap = Math.min(...b) <= Math.max(...t) && Math.min(...t) <= Math.max(...b);
  console.log([c, cell(b), cell(t), `${d.toFixed(1)}${overlap ? ' (ranges OVERLAP)' : ' (ranges disjoint)'}`].join('\t'));
}
