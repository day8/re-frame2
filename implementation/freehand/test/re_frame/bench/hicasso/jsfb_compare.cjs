#!/usr/bin/env node
//
// THE CROSS-CHECK ITSELF — two instruments, one app, one table (rf2-rguy1).
//
//   node freehand/test/re_frame/bench/hicasso/jsfb_compare.cjs \
//     --theirs <clone>/webdriver-ts/results --ours <jsfb_ours_run.cjs JSON>
//
// Reads the benchmark driver's own result files and our instrument's JSON
// and prints the `hicasso / reagent` ratio each one measured, side by side.
//
// ## What "agree" means here, stated before the numbers are looked at
//
// The two instruments do NOT measure the same quantity, and a reader who
// expects the milliseconds to match will misread the table:
//
//   theirs  WALL CLOCK from the click's `EventDispatch` to the end of the
//           first paint `Commit` after it. Includes the idle wait for the
//           next frame. Median of N iterations, each on a FRESH PAGE.
//   ours    SUMMED MAIN-THREAD TASK TIME across the operation, the window
//           closed by rAF + setTimeout(0). Excludes idle. Median within a
//           round, mean across rounds, on a WARM page.
//
// So the magnitudes are not comparable and are not compared. The RATIO is,
// because a ratio divides out anything common to both arms — and the
// cross-check's question is whether the two instruments agree about how
// much slower one substrate is than the other.
//
// The agreement band below is 15%, and it is a judgement rather than a
// derivation: two instruments whose ratios sit within 15% of each other
// are telling the same story about a substrate, and two that differ by
// more are not. It is stated here, in the code, so it cannot be chosen
// after the numbers are seen.

const fs = require('node:fs');
const path = require('node:path');

const AGREEMENT_BAND = 0.15;

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const THEIRS = arg('--theirs', process.env.JSFB_RESULTS);
const OURS = arg('--ours', process.env.JSFB_OURS_JSON);

if (!THEIRS) {
  console.error('[compare] --theirs <results dir> is required');
  process.exit(2);
}

const BASE = 'rf2-reagent';
const OTHERS = ['rf2-hicasso', 'rf2-uix'];

// benchmark id -> the row id our instrument uses for the same operation
const PAIRS = [
  ['01_run1k', 'run1k', 'create 1,000 rows', 'mount — build N elements from nothing'],
  ['02_replace1k', 'replace1k', 'replace all rows', 'bulk'],
  ['03_update10th1k_x16', 'update10th', 'partial update (every 10th)', 'narrow'],
  ['05_swap1k', 'swaprows', 'swap rows', 'bulk'],
  ['09_clear1k_x8', 'clear1k', 'clear rows', 'teardown — no clock row of ours'],
  ['07_create10k', 'create10k', 'create 10,000 rows', 'the positive control'],
];

function readTheirs(dir) {
  const out = {};
  if (!fs.existsSync(dir)) return out;
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json')) continue;
    const j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    if (j.type !== 'cpu') continue;
    const arm = [BASE, ...OTHERS].find((a) => j.framework.startsWith(a));
    if (!arm) continue;
    out[j.benchmark] = out[j.benchmark] || {};
    out[j.benchmark][arm] = j.values;
  }
  return out;
}

const theirs = readTheirs(THEIRS);
const ours = OURS && fs.existsSync(OURS) ? JSON.parse(fs.readFileSync(OURS, 'utf8')) : null;

const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');

console.log('');
console.log(';; THE CROSS-CHECK — hicasso / reagent, measured two ways on ONE app');
console.log(';;');
console.log(';;   theirs = js-framework-benchmark driver, wall clock click -> paint Commit,');
console.log(';;            median of N iterations, fresh page per iteration');
console.log(';;   ours   = CDP TaskDuration less DevToolsCommandDuration, rAF-settled,');
console.log(';;            summed main-thread task time, warm page');
console.log(';;');
console.log(`;; agreement band: ${(AGREEMENT_BAND * 100).toFixed(0)}% (declared in this file, before the numbers)`);
console.log('');

console.log(
  ';; operation                     arm            theirs ms   theirs ratio   ours ratio   |diff|   agree?'
);

const rows = [];
for (const [bench, ourId, label] of PAIRS) {
  const t = theirs[bench];
  const tBase = t && t[BASE] ? t[BASE].total.median : NaN;
  const o = ours && ours.summary && ours.summary[ourId];

  for (const arm of OTHERS) {
    const tArm = t && t[arm] ? t[arm].total.median : NaN;
    const tRatio = tArm / tBase;
    const oRatio = o && o.arms && o.arms[arm] ? o.arms[arm].ratio : NaN;

    let agree = '';
    let diff = NaN;
    if (Number.isFinite(tRatio) && Number.isFinite(oRatio)) {
      diff = Math.abs(tRatio - oRatio) / Math.min(tRatio, oRatio);
      agree = diff <= AGREEMENT_BAND ? 'YES' : 'NO';
    }
    rows.push({ bench, ourId, label, arm, tArm, tRatio, oRatio, diff, agree });
    console.log(
      `;; ${(arm === OTHERS[0] ? label : '').padEnd(28)} ${arm.padEnd(13)} ${fmt(tArm, 1).padStart(9)}   ${fmt(tRatio).padStart(12)}   ${fmt(oRatio).padStart(10)}   ${(Number.isFinite(diff) ? (diff * 100).toFixed(1) + '%' : 'n/a').padStart(6)}   ${agree}`
    );
  }
}

console.log('');
console.log(';; DIRECTION — does each instrument put the arm on the same side of parity?');
for (const r of rows) {
  if (!Number.isFinite(r.tRatio) || !Number.isFinite(r.oRatio)) continue;
  const tSide = r.tRatio > 1 ? 'slower' : 'faster';
  const oSide = r.oRatio > 1 ? 'slower' : 'faster';
  console.log(
    `;;   ${r.label.padEnd(28)} ${r.arm.padEnd(13)} theirs: ${tSide.padEnd(7)} ours: ${oSide.padEnd(7)} ${tSide === oSide ? 'SAME' : 'OPPOSITE'}`
  );
}

// The workload comparison, which is the other half of the 2x2 and the
// reason the ratio cannot simply be quoted against the published clock
// row. Our instrument on the benchmark's create-1,000 against our
// instrument on the M1 mount witness: same instrument, different page.
const PUBLISHED_M1_MOUNT = 1.2107;
console.log('');
console.log(';; WORKLOAD — one instrument, two pages (the other half of the 2x2)');
if (ours && ours.summary && ours.summary.run1k) {
  const r = ours.summary.run1k.arms['rf2-hicasso'].ratio;
  console.log(`;;   ours, benchmark create-1,000 rows   ${fmt(r)}`);
  console.log(`;;   ours, M1 mount (published, rf2-0qj9w) ${fmt(PUBLISHED_M1_MOUNT)}`);
  console.log(`;;   workload moves the ratio by ${(((r - PUBLISHED_M1_MOUNT) / PUBLISHED_M1_MOUNT) * 100).toFixed(1)}%`);
}

if (ours) {
  console.log('');
  console.log(';; OUR RUN\'S GATES');
  console.log(`;;   DOM parity        ${ours.parity && ours.parity.identical ? 'IDENTICAL' : 'DIFFERENT'}`);
  console.log(`;;   positive control  ${ours.control && ours.control.pass ? 'PASS' : 'FAIL'}`);
  console.log(`;;   page errors       ${ours.pageErrors}`);
  const unv = Object.values(ours.summary).reduce((a, s) => a + s.unverified, 0);
  console.log(`;;   unverified        ${unv}`);
}

console.log('');
const comparable = rows.filter((r) => Number.isFinite(r.diff));
const agreeing = comparable.filter((r) => r.agree === 'YES');
console.log(
  `;; VERDICT: ${agreeing.length} of ${comparable.length} comparable rows agree within ${(AGREEMENT_BAND * 100).toFixed(0)}%`
);
