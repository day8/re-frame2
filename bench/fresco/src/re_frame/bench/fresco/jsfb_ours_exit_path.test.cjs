#!/usr/bin/env node
'use strict';
// THE JSFB PRODUCER'S EXIT PATH — five refusals, each reachable without a
// browser. The same shape `clock_exit_path.test.cjs` pins for the clock
// driver and `jsfb_compare_exit_path.test.cjs` for the comparator.
//
//     node src/re_frame/bench/fresco/jsfb_ours_exit_path.test.cjs   (from bench/fresco/)
//
// WHAT THIS PINS. `jsfb_ours_run.cjs` decides an exit code that is quoted
// as a quality gate, over five independent gates:
//
//   * DOM PARITY — two arms that build different DOM are not one experiment;
//   * THE POSITIVE CONTROL — `create10k` against `run1k`, adjudicated against a
//     band registered in the source before the run;
//   * UNVERIFIED WRITES — a sample whose page did not read back;
//   * THE PAGE-ERROR FUNNEL — per-arm handlers gathered into one array;
//   * THE RECORDING SITE — a published duration that is not strictly positive
//     is not a measurement.
//
// WHY IT IS PINNED HERE RATHER THAN END TO END. The evidence the driver decides
// over is produced by a headless run of a 636 KB `:advanced` bundle across
// three arms and six rounds, which no unit test can take. So each gate is a
// pure function over its own evidence, and this file drives them directly.
// The two things a pure call cannot observe — that `main` carries `verdict`'s
// code, and that the recording site counts rather than drops — are read off the
// source, because they are what keep the gate able to fail.
//
// EVERY FIXTURE IS BUILT IN THIS FILE. Nothing here reads a committed dataset:
// the runs this driver produces live outside the repository by design.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const RUN = path.join(__dirname, 'jsfb_ours_run.cjs');
const {
  verdict,
  parityOf,
  controlVerdict,
  pageErrorsOf,
  notMeasured,
  deltaOf,
  CONTROL,
  ALL_ROWS,
  ARMS,
  OTHERS,
} = require('./jsfb_ours_run.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- fixtures, built here and nowhere else -----------------------------------

const ROW_IDS = ALL_ROWS.map((r) => r.id);

/** A 1,000-row table as `canonicalise` serialises one, short enough to read. */
const TABLE = '<tbody><tr class="danger" id="1"><td class="col-md-1">1</td></tr></tbody>';

/** Canonical serialisations, one per arm. `differ` replaces named arms'. */
const htmlOf = (differ = {}) => Object.fromEntries(ARMS.map((a) => [a, differ[a] !== undefined ? differ[a] : TABLE]));

/**
 * The accumulator `main` builds, at the depth each gate reads it.
 * `spec` is `{rowId: {arm: ms}}` with `all` standing in for every arm, and
 * `errors` is `{rowId: {arm: [message]}}`. A round array of one element means
 * its mean IS that element, so a fixture states the figure the gate sees.
 */
function accOf(spec, errors = {}) {
  const acc = {};
  for (const [id, per] of Object.entries(spec)) {
    acc[id] = {};
    for (const arm of ARMS) {
      const ms = per[arm] !== undefined ? per[arm] : per.all;
      acc[id][arm] = {
        rounds: Array.isArray(ms) ? ms : [ms],
        errors: (errors[id] && errors[id][arm]) || [],
      };
    }
  }
  return acc;
}

/** A control accumulator reading exactly `x` times the base on every arm. */
const controlAt = (x) => accOf({ [CONTROL.against]: { all: 10 }, [CONTROL.row]: { all: 10 * x } });

/** The `summary` the run builds: one entry per row, nothing to refuse. */
function summaryOf(over = {}) {
  const s = {};
  for (const id of ROW_IDS) s[id] = { base: 10, unverified: 0, nonPositive: 0, arms: {} };
  for (const [id, patch] of Object.entries(over)) s[id] = { ...s[id], ...patch };
  return s;
}

/** The whole evidence bundle, with every gate cleared. */
const sound = (over = {}) => ({
  parity: parityOf(htmlOf(), {}),
  summary: summaryOf(),
  control: controlVerdict(controlAt(10), ''),
  pageErrors: [],
  ...over,
});

/** A CDP metrics reading, in the seconds `Performance.getMetrics` reports. */
const metrics = (over = {}) => ({
  TaskDuration: 0,
  ScriptDuration: 0,
  LayoutDuration: 0,
  RecalcStyleDuration: 0,
  DevToolsCommandDuration: 0,
  ...over,
});

// --- the green case first, so the gate is not vacuously red ------------------

test('a run that cleared every gate exits 0 and says nothing', () => {
  assert.deepStrictEqual(verdict(sound()), { code: 0, lines: [] });
});

// --- GATE 1: DOM PARITY ------------------------------------------------------

test('parity is judged against BASE across EVERY arm, not pairwise among the others', () => {
  // Both non-base arms agreeing with each other and not with the denominator is
  // the shape a pairwise check would call parity.
  const same = TABLE.replace('col-md-1', 'col-md-4');
  assert.strictEqual(parityOf(htmlOf({ [OTHERS[0]]: same, [OTHERS[1]]: same }), {}).identical, false);
});

test('a parity reading that was never taken is a refusal, not a pass', () => {
  assert.strictEqual(verdict(sound({ parity: {} })).code, 1);
});

// --- GATE 2: THE POSITIVE CONTROL -------------------------------------------

test('the positive control passes only when EVERY arm sits inside its inclusive band', () => {
  // A control is not an average over arms; the mean of no rounds is NaN, which
  // a comparison written the other way round would let through; and a narrowed
  // `JSFB_ONLY` run that dropped the control certifies no magnitude.
  const pass = (acc) => controlVerdict(acc, 'run1k').pass;
  assert.deepStrictEqual(
    {
      lowEdge: pass(controlAt(CONTROL.lo)),
      highEdge: pass(controlAt(CONTROL.hi)),
      underLow: pass(controlAt(CONTROL.lo - 0.5)),
      overHigh: pass(controlAt(CONTROL.hi + 0.5)),
      oneArmOut: pass(accOf({ [CONTROL.against]: { all: 10 }, [CONTROL.row]: { all: 100, [OTHERS[0]]: 300 } })),
      noRounds: pass(accOf({ [CONTROL.against]: { all: [] }, [CONTROL.row]: { all: [] } })),
      dropped: pass(accOf({ [CONTROL.against]: { all: 10 } })),
    },
    { lowEdge: true, highEdge: true, underLow: false, overHigh: false, oneArmOut: false, noRounds: false, dropped: false }
  );
});

// --- GATE 4: THE PAGE-ERROR FUNNEL ------------------------------------------

test('the funnel drops nothing: every arm of every row is gathered', () => {
  const errors = {};
  for (const r of ALL_ROWS) errors[r.id] = Object.fromEntries(ARMS.map((a) => [a, [`${r.id}/${a}`]]));
  const errs = pageErrorsOf(accOf(Object.fromEntries(ALL_ROWS.map((r) => [r.id, { all: 10 }])), errors), ALL_ROWS);
  assert.deepStrictEqual([...errs].sort(), ALL_ROWS.flatMap((r) => ARMS.map((a) => `${r.id}/${a}`)).sort());
});

// --- GATE 5: THE RECORDING SITE ---------------------------------------------

test('a sound sample is a measurement, and the arithmetic under it is the published one', () => {
  const d = deltaOf(metrics({ TaskDuration: 1, DevToolsCommandDuration: 0 }), metrics({ TaskDuration: 1.5, DevToolsCommandDuration: 0.25 }));
  assert.deepStrictEqual(
    { task: d.task, devtools: d.devtools, taskNet: d.taskNet, refused: notMeasured(d) },
    { task: 500, devtools: 250, taskNet: 250, refused: null }
  );
});

test('a published clock that is zero or non-finite is refused and NAMED; the decomposition is not asked', () => {
  // `task` is asked as well as `taskNet` because `taskNet` is derived: a task
  // of 0 under a negative devtools reads a perfectly ordinary 5 ms. The
  // decomposition is not asked because `clear1k` honestly recalculates no style.
  const refused = (before, after) => notMeasured(deltaOf(metrics(before), metrics(after)));
  assert.deepStrictEqual(
    [
      refused({}, {}),
      refused({ DevToolsCommandDuration: 0.01 }, { DevToolsCommandDuration: 0.005 }),
      refused({}, { TaskDuration: Infinity }),
      refused({}, { TaskDuration: 0.5 }),
    ],
    ['taskNet=0', 'task=0', 'taskNet=Infinity', null]
  );
});

// --- THE DISJUNCTION: five gates, and each one alone sinks the run -----------

test('EACH of the five gates alone refuses, and all five together are the only pass', () => {
  const broken = {
    'DOM parity': { parity: parityOf(htmlOf({ [OTHERS[0]]: '<div></div>' }), {}) },
    'the positive control': { control: controlVerdict(controlAt(1), '') },
    // The LAST row: every row counts toward the total, not only the first.
    'unverified writes': { summary: summaryOf({ [ROW_IDS[ROW_IDS.length - 1]]: { unverified: 1 } }) },
    'the page-error funnel': { pageErrors: ['Error: boom'] },
    'the recording site': { summary: summaryOf({ [ROW_IDS[0]]: { nonPositive: 1 } }) },
  };
  for (const [gate, over] of Object.entries(broken)) {
    assert.strictEqual(verdict(sound(over)).code, 1, `${gate} alone must refuse`);
  }
  assert.strictEqual(verdict(sound()).code, 0, 'and none of them broken is the pass');
});

// --- the wiring: what keeps the gate able to fail ----------------------------
//
// `main` and `measureArm` need a browser, so these two are read off the source.

{
  const SRC = fs.readFileSync(RUN, 'utf8');

  test('the exit code comes from `verdict` and is CARRIED, not re-derived', () => {
    const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('module.exports'));
    assert.match(
      MAIN,
      /const v = verdict\(\{ parity, summary, control, pageErrors \}\);\s+for \(const line of v\.lines\) console\.error\(line\);\s+process\.exit\(v\.code\);/
    );
  });

  test('the recording site COUNTS a non-measurement rather than dropping it', () => {
    // The distinction between a gate and a filter: a filter would quietly
    // publish a figure from a rig producing impossible numbers.
    const ARM = SRC.slice(SRC.indexOf('async function measureArm('), SRC.indexOf('async function main()'));
    assert.match(ARM, /const bad = notMeasured\(d\);/);
    assert.match(ARM, /nonPositive\+\+;/);
    assert.match(ARM, /return \{ samples: out, unverified, nonPositive, errors \};/, 'both counts and the errors must leave the arm');
  });
}

// --- run ---------------------------------------------------------------------

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name}\n      ${err.message}`);
  }
}

if (failed > 0) {
  console.error(`\njsfb_ours_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`jsfb_ours_exit_path.test.cjs: ${tests.length} passed`);
