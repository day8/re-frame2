#!/usr/bin/env node
'use strict';
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
//
// ## IT FAILS CLOSED, and it used to fail open (rf2-rguy1, #7359's audit)
//
// This program's exit code is quoted as a quality gate. It could not carry
// that weight, because ABSENT EVIDENCE looked exactly like agreement: with
// no results directory and no `--ours` JSON it printed a table of `n/a`,
// announced `VERDICT: 0 of 0 comparable rows agree within 15%`, and exited
// 0. A gate that is green when it was handed nothing is not a gate, and the
// commonest way to hand it nothing is a run that never happened.
//
// So the evidence is now REQUIRED rather than merely read. `EXPECTED_CELLS`
// is derived from `PAIRS` — every benchmark the driver actually runs,
// crossed with every arm that is not the denominator, which is 5 x 2 = 10 —
// and a cell that either instrument did not measure is named rather than
// silently dropped from the denominator.
//
//   0  every expected cell was measured by both instruments.
//   1  the comparison RAN and the evidence is SHORT: at least one expected
//      cell is missing, and every missing cell is named with the side that
//      lacks it.
//   2  the comparison could not run at all: an input was not named, is not
//      present, or will not parse. Named, with the path.
//
// And a cell counts as measured only when the DURATIONS UNDER IT are
// measurements: finite and STRICTLY POSITIVE on both sides — base, arm and
// ratio. A table of all-negative medians divides out to exactly the positive
// ratios a sound run produces, so finiteness alone let counter-inverted
// timing evidence through the gate above (rf2-110be, from #7643's audit).
//
// WHAT IT DOES NOT ADJUDICATE, deliberately. The run's own gates — DOM
// parity, the positive control, page errors, unverified writes — are
// REPORTED here and decided by `jsfb_ours_run.cjs`, which owns them and
// exits on them. Two programs deciding one verdict is the defect
// `clock_exit_path.test.cjs` exists to pin, and this file is not going to
// grow a second seat for it.

const fs = require('node:fs');
const path = require('node:path');

const AGREEMENT_BAND = 0.15;

const BASE = 'rf2-reagent';
const OTHERS = ['rf2-hicasso', 'rf2-uix'];

// benchmark id -> the row id our instrument uses for the same operation.
//
// `theirs` records whether the benchmark driver runs this row at all.
// `07_create10k` is OUR positive control and has no counterpart in the
// published `--benchmark 01_ 02_ 03_ 05_ 09_` selection, so it is measured
// on one instrument by design. That flag is what keeps the expected count
// honest: it is the difference between "the driver does not run this" and
// "the driver's run is missing", and before it existed the second was
// indistinguishable from the first.
const PAIRS = [
  { bench: '01_run1k', ours: 'run1k', label: 'create 1,000 rows', maps: 'mount — build N elements from nothing', theirs: true },
  { bench: '02_replace1k', ours: 'replace1k', label: 'replace all rows', maps: 'bulk', theirs: true },
  { bench: '03_update10th1k_x16', ours: 'update10th', label: 'partial update (every 10th)', maps: 'narrow', theirs: true },
  { bench: '05_swap1k', ours: 'swaprows', label: 'swap rows', maps: 'bulk', theirs: true },
  { bench: '09_clear1k_x8', ours: 'clear1k', label: 'clear rows', maps: 'teardown — no clock row of ours', theirs: true },
  { bench: '07_create10k', ours: 'create10k', label: 'create 10,000 rows', maps: 'the positive control', theirs: false },
];

const cellId = (bench, arm) => `${bench} / ${arm}`;

// EVERY cell both instruments must have measured, DERIVED rather than typed.
// The audit counted ten by hand; this counts them from the roster, so a row
// added to `PAIRS` raises the bar automatically instead of leaving a gate
// that quietly stopped covering the newest row.
const EXPECTED_CELLS = PAIRS.filter((p) => p.theirs).flatMap((p) => OTHERS.map((arm) => cellId(p.bench, arm)));

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

// ---------------------------------------------------------------------------
// Reading the evidence — every failure comes back as a NAMED absence
// ---------------------------------------------------------------------------

/**
 * The benchmark driver's own result files.
 * @returns {{table: object, absent: string[]}}
 */
function readTheirs(dir) {
  const absent = [];
  const table = {};

  if (!dir) {
    absent.push('--theirs <results dir> was not given, so the benchmark driver\'s side of the comparison is missing entirely');
    return { table, absent };
  }
  let stat = null;
  try {
    stat = fs.statSync(dir);
  } catch (e) {
    absent.push(`the benchmark driver's results directory does not exist: ${dir}`);
    return { table, absent };
  }
  if (!stat.isDirectory()) {
    absent.push(`--theirs is not a directory: ${dir}`);
    return { table, absent };
  }

  let seen = 0;
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json')) continue;
    let j;
    try {
      j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    } catch (e) {
      absent.push(`a result file in ${dir} will not parse: ${f} — ${e.message}`);
      continue;
    }
    if (!j || j.type !== 'cpu') continue;
    const arm = [BASE, ...OTHERS].find((a) => typeof j.framework === 'string' && j.framework.startsWith(a));
    if (!arm) continue;
    seen += 1;
    table[j.benchmark] = table[j.benchmark] || {};
    table[j.benchmark][arm] = j.values;
  }

  if (absent.length === 0 && seen === 0) {
    absent.push(
      `${dir} holds no \`type: cpu\` result for any of ${[BASE, ...OTHERS].join(', ')} — ` +
        'the directory is there but the run that fills it is not'
    );
  }
  return { table, absent };
}

/**
 * Our instrument's JSON, as `jsfb_ours_run.cjs` writes it.
 * @returns {{ours: object|null, absent: string[]}}
 */
function readOurs(file) {
  if (!file) {
    return { ours: null, absent: ['--ours <jsfb_ours_run.cjs JSON> was not given, so our side of the comparison is missing entirely'] };
  }
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    return { ours: null, absent: [`our run's JSON does not exist: ${file}`] };
  }
  let ours;
  try {
    ours = JSON.parse(raw);
  } catch (e) {
    return { ours: null, absent: [`our run's JSON will not parse: ${file} — ${e.message}`] };
  }
  if (!ours || typeof ours !== 'object' || !ours.summary || typeof ours.summary !== 'object') {
    return { ours: null, absent: [`our run's JSON carries no \`summary\` object: ${file}`] };
  }
  return { ours, absent: [] };
}

// ---------------------------------------------------------------------------
// The table
// ---------------------------------------------------------------------------

const ratioOf = (o, arm) => (o && o.arms && o.arms[arm] ? o.arms[arm].ratio : NaN);

// A MEASUREMENT IS FINITE AND STRICTLY POSITIVE (rf2-110be).
//
// The predicate below used to be "the derived difference is finite", and a
// difference is finite long before the durations under it are real. An
// elapsed time of zero is the absence of a measurement and a negative one is
// a broken measurement, so neither may enter the measured set — and neither
// may a ratio built out of them.
const positive = (x) => Number.isFinite(x) && x > 0;

/** The first named value on one side that is not a measurement, or `null`. */
const notMeasured = (named) => {
  const bad = named.find(([, v]) => !positive(v));
  return bad ? `${bad[0]}=${bad[1]}` : null;
};

/** One row per (benchmark, arm), whether or not either instrument measured it. */
function buildRows(theirs, ours) {
  const rows = [];
  for (const p of PAIRS) {
    const t = theirs[p.bench];
    const tBase = t && t[BASE] && t[BASE].total ? t[BASE].total.median : NaN;
    const o = ours && ours.summary ? ours.summary[p.ours] : null;
    const oBase = o ? o.base : NaN;

    for (const arm of OTHERS) {
      const tArm = t && t[arm] && t[arm].total ? t[arm].total.median : NaN;
      const tRatio = tArm / tBase;
      const oArm = o && o.arms && o.arms[arm] ? o.arms[arm] : null;
      const oMs = oArm ? oArm.ms : NaN;
      const oRatio = ratioOf(o, arm);

      // Both sides must have MEASURED, and the durations say whether they did:
      // -100 ms over -120 ms is a tidy 1.2 that no reading produced.
      const tBad = notMeasured([[`${BASE} median`, tBase], [`${arm} median`, tArm], ['ratio', tRatio]]);
      const oBad = notMeasured([['base ms', oBase], [`${arm} ms`, oMs], ['ratio', oRatio]]);

      let agree = '';
      let diff = NaN;
      if (!tBad && !oBad) {
        diff = Math.abs(tRatio - oRatio) / Math.min(tRatio, oRatio);
        agree = diff <= AGREEMENT_BAND ? 'YES' : 'NO';
      }
      rows.push({ cell: cellId(p.bench, arm), bench: p.bench, ourId: p.ours, label: p.label, expected: p.theirs, arm, tArm, tRatio, oRatio, diff, agree, tBad, oBad });
    }
  }
  return rows;
}

// ---------------------------------------------------------------------------
// THE DECISION, and it has ONE seat
// ---------------------------------------------------------------------------

/**
 * @param {{absent: string[], rows: object[]}} evidence
 * @returns {{code: number, lines: string[]}}
 */
function verdict({ absent = [], rows = [] } = {}) {
  const lines = [];

  if (absent.length > 0) {
    for (const a of absent) lines.push(`[compare] REFUSED — ${a}`);
    lines.push(
      '[compare] No comparison was made, so this run\'s exit code certifies nothing. ' +
        'It used to exit 0 here, printing `0 of 0 comparable rows` (rf2-rguy1).'
    );
    return { code: 2, lines };
  }

  const measured = new Set(rows.filter((r) => Number.isFinite(r.diff)).map((r) => r.cell));
  const missing = EXPECTED_CELLS.filter((c) => !measured.has(c));

  if (missing.length > 0) {
    lines.push(
      `[compare] REFUSED — ${missing.length} of ${EXPECTED_CELLS.length} expected cells were not measured by both instruments:`
    );
    for (const cell of missing) {
      const r = rows.find((x) => x.cell === cell);
      let why;
      // The offending value is NAMED. Absence prints `NaN`; the positivity
      // cases print the number that is not a duration.
      if (!r) why = 'no row was built for it at all';
      else if (r.tBad && r.oBad) why = `NEITHER instrument measured it (theirs ${r.tBad}; ours ${r.oBad})`;
      else if (r.tBad) why = `the benchmark driver's results carry no usable median for this cell (${r.tBad})`;
      else why = `our run's JSON carries no usable ratio for this cell (${r.oBad})`;
      lines.push(`[compare]   ${cell}: ${why}`);
    }
    lines.push(
      '[compare] A verdict over a subset of the table is not the verdict this program advertises. ' +
        'Re-run the missing side rather than reading the rows that survived.'
    );
    return { code: 1, lines };
  }

  return { code: 0, lines: [] };
}

const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');

// The workload comparison, which is the other half of the 2x2 and the
// reason the ratio cannot simply be quoted against the published clock
// row. Our instrument on the benchmark's create-1,000 against our
// instrument on the M1 mount witness: same instrument, different page.
const PUBLISHED_M1_MOUNT = 1.2107;

function report(theirs, ours, rows) {
  console.log('');
  console.log(';; THE CROSS-CHECK — hicasso / reagent, measured two ways on ONE app');
  console.log(';;');
  console.log(';;   theirs = js-framework-benchmark driver, wall clock click -> paint Commit,');
  console.log(';;            median of N iterations, fresh page per iteration');
  console.log(';;   ours   = CDP TaskDuration less DevToolsCommandDuration, rAF-settled,');
  console.log(';;            summed main-thread task time, warm page');
  console.log(';;');
  console.log(`;; agreement band: ${(AGREEMENT_BAND * 100).toFixed(0)}% (declared in this file, before the numbers)`);
  console.log(`;; expected cells: ${EXPECTED_CELLS.length} — every benchmark the driver runs, crossed with ${OTHERS.join(' and ')}`);
  console.log('');

  console.log(
    ';; operation                     arm            theirs ms   theirs ratio   ours ratio   |diff|   agree?'
  );

  for (const r of rows) {
    console.log(
      `;; ${(r.arm === OTHERS[0] ? r.label : '').padEnd(28)} ${r.arm.padEnd(13)} ${fmt(r.tArm, 1).padStart(9)}   ${fmt(r.tRatio).padStart(12)}   ${fmt(r.oRatio).padStart(10)}   ${(Number.isFinite(r.diff) ? (r.diff * 100).toFixed(1) + '%' : 'n/a').padStart(6)}   ${r.agree}`
    );
  }

  console.log('');
  console.log(';; DIRECTION — does each instrument put the arm on the same side of parity?');
  for (const r of rows) {
    if (!Number.isFinite(r.diff)) continue; // an unmeasured cell has no direction
    const tSide = r.tRatio > 1 ? 'slower' : 'faster';
    const oSide = r.oRatio > 1 ? 'slower' : 'faster';
    console.log(
      `;;   ${r.label.padEnd(28)} ${r.arm.padEnd(13)} theirs: ${tSide.padEnd(7)} ours: ${oSide.padEnd(7)} ${tSide === oSide ? 'SAME' : 'OPPOSITE'}`
    );
  }

  console.log('');
  console.log(';; WORKLOAD — one instrument, two pages (the other half of the 2x2)');
  if (ours && ours.summary && ours.summary.run1k) {
    const r = ratioOf(ours.summary.run1k, 'rf2-hicasso');
    console.log(`;;   ours, benchmark create-1,000 rows   ${fmt(r)}`);
    console.log(`;;   ours, M1 mount (published, rf2-0qj9w) ${fmt(PUBLISHED_M1_MOUNT)}`);
    console.log(`;;   workload moves the ratio by ${(((r - PUBLISHED_M1_MOUNT) / PUBLISHED_M1_MOUNT) * 100).toFixed(1)}%`);
  }

  // The run's own gates, REPORTED. `jsfb_ours_run.cjs` decides them.
  if (ours) {
    console.log('');
    console.log(";; OUR RUN'S GATES (decided by jsfb_ours_run.cjs; reported here)");
    console.log(`;;   DOM parity        ${ours.parity && ours.parity.identical ? 'IDENTICAL' : 'DIFFERENT'}`);
    console.log(`;;   positive control  ${ours.control && ours.control.pass ? 'PASS' : 'FAIL'}`);
    console.log(`;;   page errors       ${ours.pageErrors}`);
    const unv = Object.values(ours.summary).reduce((a, s) => a + (s && s.unverified ? s.unverified : 0), 0);
    console.log(`;;   unverified        ${unv}`);
  }

  console.log('');
  const comparable = rows.filter((r) => Number.isFinite(r.diff));
  const agreeing = comparable.filter((r) => r.agree === 'YES');
  console.log(
    `;; VERDICT: ${agreeing.length} of ${comparable.length} comparable rows agree within ${(AGREEMENT_BAND * 100).toFixed(0)}%` +
      ` (${EXPECTED_CELLS.length} expected)`
  );
}

function main() {
  const t = readTheirs(arg('--theirs', process.env.JSFB_RESULTS));
  const o = readOurs(arg('--ours', process.env.JSFB_OURS_JSON));
  const absent = [...t.absent, ...o.absent];
  const rows = buildRows(t.table, o.ours);

  // The table is printed whenever there is one to print, because a reader
  // debugging a short run needs to see which cells arrived.
  if (absent.length === 0) report(t.table, o.ours, rows);

  const v = verdict({ absent, rows });
  for (const line of v.lines) console.error(line);
  return v.code;
}

module.exports = { verdict, buildRows, readTheirs, readOurs, EXPECTED_CELLS, PAIRS, OTHERS, BASE, AGREEMENT_BAND };

if (require.main === module) {
  const code = main();
  if (code !== 0) process.exit(code);
}
