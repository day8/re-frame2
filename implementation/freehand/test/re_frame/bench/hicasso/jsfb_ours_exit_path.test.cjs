#!/usr/bin/env node
'use strict';
// THE JSFB PRODUCER'S EXIT PATH — five refusals that nothing could reach.
// rf2-2ze1h, filed by rf2-iuudn's worker, and the same defect
// `clock_exit_path.test.cjs` pins for the clock driver and
// `jsfb_compare_exit_path.test.cjs` for the comparator.
//
//     node freehand/test/re_frame/bench/hicasso/jsfb_ours_exit_path.test.cjs
//
// THE DEFECT THIS PINS. `jsfb_ours_run.cjs` decides an exit code that is quoted
// as a quality gate, over five independent gates:
//
//   * DOM PARITY — two arms that build different DOM are not one experiment;
//   * THE POSITIVE CONTROL — `create10k` against `run1k`, adjudicated against a
//     band registered in the source before the run;
//   * UNVERIFIED WRITES — a sample whose page did not read back;
//   * THE PAGE-ERROR FUNNEL — per-arm handlers gathered into one array;
//   * THE RECORDING SITE — a published duration that is not strictly positive
//     is not a measurement (rf2-iuudn).
//
// Until this file existed NONE of them could be reached: the driver required
// `playwright` at module scope and called `main()` unguarded, so the only way
// to ask what it refuses was to launch a headless Chromium and hope the run
// produced the shape in question. Five gates in that condition are five gates
// nobody has ever seen go red, which is the state this whole lane treats as a
// defect rather than a gap.
//
// WHY IT IS PINNED HERE RATHER THAN END TO END. The evidence the driver decides
// over is produced by a headless run of a 636 KB `:advanced` bundle across
// three arms and six rounds, which no unit test can take — and the bead is
// explicit that the benchmark must NOT be run for this, because the rig has to
// stay stable across the series being compared. So the repair put each gate in
// a pure function over its own evidence, and this file drives them directly.
// The two things a pure call cannot observe — that requiring the driver neither
// launches a browser nor exits, and that `main` carries `verdict`'s code rather
// than re-deriving it — are taken from a spawned process and from the source.
//
// EVERY FIXTURE IS BUILT IN THIS FILE. Nothing here reads a committed dataset:
// the runs this driver produces live outside the repository by design, so a
// negative case that depended on one would pass on the box that took it and
// fail everywhere else.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const RUN = path.join(__dirname, 'jsfb_ours_run.cjs');
const {
  verdict,
  parityOf,
  controlVerdict,
  pageErrorsOf,
  notMeasured,
  positive,
  deltaOf,
  PUBLISHED,
  CONTROL,
  ALL_ROWS,
  ARMS,
  BASE,
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

/** Raw `innerHTML` lengths, which the gate reports and does not decide on. */
const rawLensOf = () => Object.fromEntries(ARMS.map((a) => [a, TABLE.length + 7]));

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
  parity: parityOf(htmlOf(), rawLensOf()),
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

test('the sound fixture really does clear all five gates, one at a time', () => {
  // Without this the refusals below would all pass against a fixture that was
  // already refused for some other reason.
  const e = sound();
  assert.strictEqual(e.parity.identical, true, 'parity');
  assert.strictEqual(e.control.pass, true, 'the control');
  assert.strictEqual(Object.values(e.summary).reduce((a, s) => a + s.unverified, 0), 0, 'unverified');
  assert.strictEqual(Object.values(e.summary).reduce((a, s) => a + s.nonPositive, 0), 0, 'non-measurements');
  assert.strictEqual(e.pageErrors.length, 0, 'page errors');
});

// --- GATE 1: DOM PARITY ------------------------------------------------------

test('arms that serialise identically are parity, and both lengths are reported', () => {
  const p = parityOf(htmlOf(), rawLensOf());
  assert.strictEqual(p.identical, true);
  assert.strictEqual(p.firstDiff, null, 'nothing diverges, so nothing is named');
  assert.deepStrictEqual(Object.keys(p.lens).sort(), [...ARMS].sort());
  assert.deepStrictEqual(p.rawLens, rawLensOf(), 'the raw lengths pass through untouched');
});

test('THE GATE: an arm that builds different DOM refuses, with nothing else wrong', () => {
  const p = parityOf(htmlOf({ [OTHERS[0]]: TABLE.replace('danger', 'warning') }), rawLensOf());
  assert.strictEqual(p.identical, false);
  assert.strictEqual(verdict(sound({ parity: p })).code, 1, 'different DOM is not one experiment');
});

test('the divergence is NAMED — which arm, and at which character', () => {
  const other = TABLE.replace('danger', 'DANGER');
  const p = parityOf(htmlOf({ [OTHERS[1]]: other }), rawLensOf());
  assert.strictEqual(p.firstDiff.arm, OTHERS[1]);
  assert.strictEqual(p.firstDiff.at, TABLE.indexOf('danger'), 'the offset is the first differing byte');
  assert.ok(p.firstDiff.base.includes('danger'), p.firstDiff.base);
  assert.ok(p.firstDiff.other.includes('DANGER'), p.firstDiff.other);
});

test('parity is judged against BASE across EVERY arm, not pairwise among the others', () => {
  // Both non-base arms agreeing with each other and not with the denominator is
  // the shape a pairwise check would call parity. It is not parity: the
  // denominator every ratio is taken against is the odd one out.
  const same = TABLE.replace('col-md-1', 'col-md-4');
  const p = parityOf(htmlOf({ [OTHERS[0]]: same, [OTHERS[1]]: same }), rawLensOf());
  assert.strictEqual(p.identical, false);
  assert.strictEqual(p.firstDiff.arm, OTHERS[0], 'the first arm that differs from the base is named');
});

test('a parity reading that was never taken is a refusal, not a pass', () => {
  assert.strictEqual(verdict(sound({ parity: {} })).code, 1);
  assert.strictEqual(verdict(sound({ parity: undefined })).code, 1);
});

// --- GATE 2: THE POSITIVE CONTROL -------------------------------------------

test('the control names two rows that the roster actually has', () => {
  // A renamed row would leave the control permanently `NOT RUN`, which refuses
  // every run forever — fail-closed, and still a bug.
  assert.ok(ROW_IDS.includes(CONTROL.row), `${CONTROL.row} must be a real row`);
  assert.ok(ROW_IDS.includes(CONTROL.against), `${CONTROL.against} must be a real row`);
  assert.ok(CONTROL.lo < CONTROL.hi, 'the band must have width');
});

test('a control inside the band passes, and says PASS on every arm', () => {
  const c = controlVerdict(controlAt(10), '');
  assert.strictEqual(c.pass, true);
  for (const arm of ARMS) assert.ok(c.lines.some((l) => l.includes(arm) && l.includes('PASS')), c.lines.join('\n'));
  assert.match(c.lines[0], /POSITIVE CONTROL — create10k against run1k, predicted \[8 – 13\]x/);
});

test('a control BELOW the band refuses — the instrument saturated', () => {
  const c = controlVerdict(controlAt(4), '');
  assert.strictEqual(c.pass, false, 'ten times the rows read as four times the work');
  assert.ok(c.lines.some((l) => l.includes('FAIL')), c.lines.join('\n'));
  assert.strictEqual(verdict(sound({ control: c })).code, 1);
});

test('a control ABOVE the band refuses — something superlinear dominates', () => {
  const c = controlVerdict(controlAt(20), '');
  assert.strictEqual(c.pass, false);
  assert.strictEqual(verdict(sound({ control: c })).code, 1);
});

test('both band edges are INCLUSIVE, and a hair outside either is not', () => {
  assert.strictEqual(controlVerdict(controlAt(CONTROL.lo), '').pass, true, 'the low edge is in');
  assert.strictEqual(controlVerdict(controlAt(CONTROL.hi), '').pass, true, 'the high edge is in');
  assert.strictEqual(controlVerdict(controlAt(CONTROL.lo - 0.5), '').pass, false);
  assert.strictEqual(controlVerdict(controlAt(CONTROL.hi + 0.5), '').pass, false);
});

test('ONE arm outside the band sinks the control, and only that arm reads FAIL', () => {
  const acc = accOf({
    [CONTROL.against]: { all: 10 },
    [CONTROL.row]: { all: 100, [OTHERS[0]]: 300 },
  });
  const c = controlVerdict(acc, '');
  assert.strictEqual(c.pass, false, 'a control is not an average over arms');
  const failing = c.lines.filter((l) => l.includes('FAIL'));
  assert.strictEqual(failing.length, 1, c.lines.join('\n'));
  assert.ok(failing[0].includes(OTHERS[0]), failing[0]);
});

test('A NARROWED RUN THAT DROPPED THE CONTROL DOES NOT PASS BY ABSENCE', () => {
  // `JSFB_ONLY` exists so one row can be asked about cheaply. A probe that
  // dropped the control has nothing saying the instrument saw the work at all,
  // so it must not certify a magnitude — in either direction of dropping.
  for (const [spec, dropped] of [
    [{ [CONTROL.against]: { all: 10 } }, CONTROL.row],
    [{ [CONTROL.row]: { all: 100 } }, CONTROL.against],
  ]) {
    const c = controlVerdict(accOf(spec), 'run1k');
    assert.strictEqual(c.pass, false, `dropping ${dropped} must refuse`);
    const all = c.lines.join('\n');
    assert.match(all, /NOT RUN — JSFB_ONLY=run1k dropped /);
    assert.ok(all.includes(`dropped ${dropped}`), all);
    assert.match(all, /THIS RUN IS A PROBE AND CERTIFIES NO MAGNITUDE/);
    assert.match(all, /never to publish a ratio/);
    assert.strictEqual(verdict(sound({ control: c })).code, 1);
  }
});

test('a control arm carrying NO rounds is a FAIL, not a pass', () => {
  // The mean of nothing is NaN, and NaN is inside no band. Stated because a
  // comparison written the other way round would have let it through.
  const c = controlVerdict(accOf({ [CONTROL.against]: { all: [] }, [CONTROL.row]: { all: [] } }), '');
  assert.strictEqual(c.pass, false);
  assert.ok(c.lines.some((l) => l.includes('n/a') && l.includes('FAIL')), c.lines.join('\n'));
});

// --- GATE 3: UNVERIFIED WRITES ----------------------------------------------

test('one unverified write on one row refuses the whole run', () => {
  const v = verdict(sound({ summary: summaryOf({ [ROW_IDS[0]]: { unverified: 1 } }) }));
  assert.strictEqual(v.code, 1, 'a sample whose page did not read back is not a sample');
  assert.deepStrictEqual(v.lines, ['[ours] a gate did not clear — see the report above']);
});

test('unverified writes are summed across rows, not read off one', () => {
  const over = {};
  for (const id of ROW_IDS) over[id] = { unverified: 0 };
  over[ROW_IDS[ROW_IDS.length - 1]] = { unverified: 2 };
  assert.strictEqual(verdict(sound({ summary: summaryOf(over) })).code, 1, 'the LAST row must count too');
});

// --- GATE 4: THE PAGE-ERROR FUNNEL ------------------------------------------

test('an error on ONE arm of ONE row reaches the refusal — the three hops, followed', () => {
  // `pageerror_exit_path.test.cjs` stops at the sink because following it here
  // needed real dataflow: per-arm array -> per-row accumulator -> one aggregate
  // -> the exit. This walks it with a fixture instead.
  const rows = ALL_ROWS.slice(0, 2);
  const acc = accOf(
    Object.fromEntries(rows.map((r) => [r.id, { all: 10 }])),
    { [rows[1].id]: { [OTHERS[1]]: ['TypeError: undefined is not a function'] } }
  );
  const errs = pageErrorsOf(acc, rows);
  assert.deepStrictEqual(errs, ['TypeError: undefined is not a function']);
  assert.strictEqual(verdict(sound({ pageErrors: errs })).code, 1, 'a page that threw is not the page under test');
});

test('the funnel drops nothing: every arm of every row is gathered', () => {
  const rows = ALL_ROWS;
  const errors = {};
  for (const r of rows) errors[r.id] = Object.fromEntries(ARMS.map((a) => [a, [`${r.id}/${a}`]]));
  const errs = pageErrorsOf(accOf(Object.fromEntries(rows.map((r) => [r.id, { all: 10 }])), errors), rows);
  assert.strictEqual(errs.length, rows.length * ARMS.length);
  assert.deepStrictEqual([...new Set(errs)].length, errs.length, 'no message may be gathered twice');
  for (const r of rows) for (const a of ARMS) assert.ok(errs.includes(`${r.id}/${a}`), `${r.id}/${a} was dropped`);
});

test('a page error refuses even when every number in the report is sound', () => {
  assert.strictEqual(verdict(sound({ pageErrors: ['console: boom'] })).code, 1);
});

// --- GATE 5: THE RECORDING SITE (rf2-iuudn) ---------------------------------

test('PUBLISHED is exactly the two clocks the report publishes', () => {
  assert.deepStrictEqual(PUBLISHED, ['taskNet', 'task']);
});

test('a duration is a measurement only when finite AND strictly positive', () => {
  assert.strictEqual(positive(1), true);
  for (const bad of [0, -0, -1, NaN, Infinity, -Infinity]) {
    assert.strictEqual(positive(bad), false, `${bad} is not an elapsed time`);
  }
});

test('a sound sample is a measurement, and the arithmetic under it is the published one', () => {
  const d = deltaOf(metrics({ TaskDuration: 1, DevToolsCommandDuration: 0 }), metrics({ TaskDuration: 1.5, DevToolsCommandDuration: 0.25 }));
  assert.deepStrictEqual({ task: d.task, devtools: d.devtools, taskNet: d.taskNet }, { task: 500, devtools: 250, taskNet: 250 });
  assert.strictEqual(notMeasured(d), null, 'a positive taskNet and a positive task is a sample');
});

test('a sample that read ZERO on both clocks is refused and NAMED', () => {
  // Two identical readings: the operation is over and no time passed, which is
  // the absence of a measurement rather than a fast one.
  const d = deltaOf(metrics(), metrics());
  assert.strictEqual(notMeasured(d), 'taskNet=0');
});

test('a counter that went BACKWARDS across the delta is refused', () => {
  const d = deltaOf(metrics({ TaskDuration: 2 }), metrics({ TaskDuration: 1.5 }));
  assert.strictEqual(notMeasured(d), 'taskNet=-500');
});

test('THE WRONG-SIDE-BLAMED FAILURE: a task of 0 under a NEGATIVE devtools is still refused', () => {
  // rf2-110be's finding, which is why `task` is asked as well as `taskNet`:
  // `taskNet` is derived, and a derived number is sound-looking long before its
  // inputs are. Here the published `taskNet` is a perfectly ordinary 5 ms.
  const d = deltaOf(metrics({ DevToolsCommandDuration: 0.01 }), metrics({ DevToolsCommandDuration: 0.005 }));
  assert.strictEqual(d.taskNet, 5, 'the derived clock reads positive');
  assert.strictEqual(d.task, 0, 'and its input did not');
  assert.strictEqual(notMeasured(d), 'task=0', 'the offending clock is named, not the one that looked fine');
});

test('the DECOMPOSITION durations are deliberately NOT asked', () => {
  // `clear1k` recalculates no style and honestly reads 0 there. Requiring the
  // decomposition positive would refuse sound runs, which is the opposite
  // fault to the one this gate exists for.
  const d = deltaOf(metrics(), metrics({ TaskDuration: 0.5 }));
  assert.deepStrictEqual([d.script, d.layout, d.style, d.devtools], [0, 0, 0, 0]);
  assert.strictEqual(notMeasured(d), null, 'a zero decomposition is honest; a zero task is not');
});

test('a non-finite published duration is not a measurement either', () => {
  for (const bad of [NaN, Infinity, -Infinity]) {
    assert.strictEqual(notMeasured({ taskNet: bad, task: 1 }), `taskNet=${bad}`);
    assert.strictEqual(notMeasured({ taskNet: 1, task: bad }), `task=${bad}`);
  }
});

test('a COUNTED non-measurement refuses the run — the count is a gate, not a filter', () => {
  const v = verdict(sound({ summary: summaryOf({ [CONTROL.row]: { nonPositive: 1 } }) }));
  assert.strictEqual(v.code, 1, 'a filter would quietly publish a figure from a rig producing impossible numbers');
  assert.deepStrictEqual(v.lines, ['[ours] a gate did not clear — see the report above']);
});

// --- THE DISJUNCTION: five gates, and each one alone sinks the run -----------

test('EACH of the five gates alone refuses, and all five together are the only pass', () => {
  const broken = {
    'DOM parity': { parity: parityOf(htmlOf({ [OTHERS[0]]: '<div></div>' }), rawLensOf()) },
    'the positive control': { control: controlVerdict(controlAt(1), '') },
    'unverified writes': { summary: summaryOf({ [ROW_IDS[0]]: { unverified: 1 } }) },
    'the page-error funnel': { pageErrors: ['Error: boom'] },
    'the recording site': { summary: summaryOf({ [ROW_IDS[0]]: { nonPositive: 1 } }) },
  };
  for (const [gate, over] of Object.entries(broken)) {
    assert.strictEqual(verdict(sound(over)).code, 1, `${gate} alone must refuse`);
  }
  assert.strictEqual(verdict(sound()).code, 0, 'and none of them broken is the pass');
});

test('a verdict handed nothing at all refuses — absent evidence is not a clear run', () => {
  assert.strictEqual(verdict().code, 1);
  assert.strictEqual(verdict({}).code, 1);
});

// --- THE DEFECT ITSELF: requiring the driver must not RUN it -----------------

const node = (args, env = {}) =>
  cp.spawnSync(process.execPath, args, { encoding: 'utf8', env: { ...process.env, ...env }, timeout: 120000 });

test('THE DEFECT: requiring the driver neither opens a browser nor exits', () => {
  // Everything above depends on this. Before rf2-2ze1h the driver required
  // Playwright at module scope and called `main()` unguarded, so `require` was
  // a benchmark run — and on a box with no `implementation/node_modules` it was
  // not even that, it was a module that would not load.
  const r = node(['-e', `require(${JSON.stringify(RUN)}); console.log('required');`]);
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /^required\s*$/, 'a driver that reported anything on require is a driver that ran');
  assert.strictEqual(r.stderr, '');
});

test('requiring it with a JSFB_ONLY that names no row does not kill the requiring process', () => {
  // The refusal below used to sit at module scope, so `require`-ing this driver
  // with a stray environment variable set took the test runner down with it.
  const r = node(['-e', `require(${JSON.stringify(RUN)}); console.log('required');`], { JSFB_ONLY: 'no-such-row' });
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /^required\s*$/);
});

test('THE PROCESS EXIT: a JSFB_ONLY naming no row exits 1 from the shell', () => {
  // The one refusal that is taken before anything is opened, so it is the one
  // process exit this file can observe without a browser on the box.
  const r = node([RUN], { JSFB_ONLY: 'no-such-row' });
  assert.strictEqual(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /\[ours\] JSFB_ONLY=no-such-row selects no row; known ids: /);
  for (const id of ROW_IDS) assert.ok(r.stderr.includes(id), `the refusal must name ${id}`);
});

// --- the wiring: `verdict` is load-bearing, not decorative -------------------

{
  const SRC = fs.readFileSync(RUN, 'utf8');
  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('module.exports'));

  test('the driver exports its arithmetic under a require.main guard', () => {
    assert.match(SRC, /\nmodule\.exports = \{/);
    for (const name of ['verdict', 'parityOf', 'controlVerdict', 'pageErrorsOf', 'notMeasured', 'deltaOf']) {
      assert.match(SRC, new RegExp(`\\n\\s+${name},`), `${name} must be exported`);
    }
    assert.match(SRC, /\nif \(require\.main === module\) \{/);
  });

  test('Playwright is required INSIDE `main`, never at module scope', () => {
    // A module-scope import is what made the driver unloadable without a
    // browser toolchain, and it is the half of the defect a `require.main`
    // guard on its own does not repair.
    const at = SRC.indexOf("require('playwright')");
    assert.ok(at > 0, 'the driver must still drive a browser');
    assert.ok(at > SRC.indexOf('async function main()'), 'the import must sit inside `main`');
  });

  test('the exit code comes from `verdict` and is CARRIED, not re-derived', () => {
    assert.ok(MAIN.length > 0, 'the driver must expose its run as `main`');
    assert.match(MAIN, /const v = verdict\(\{ parity, summary, control, pageErrors \}\);/);
    assert.match(MAIN, /for \(const line of v\.lines\) console\.error\(line\);/);
    assert.match(MAIN, /process\.exit\(v\.code\);/);
  });

  test('NOTHING downstream of `verdict` reads a gate on its own', () => {
    const tail = MAIN.slice(MAIN.indexOf('const v = verdict('));
    assert.ok(tail.length > 0);
    assert.ok(
      !/parity\.identical|unverified > 0|nonPositive > 0|control\.pass|pageErrors\.length > 0/.test(tail),
      'a second reading of any gate is a second seat, invisible to every test above'
    );
  });

  test('the old inline decisions are gone from `main`', () => {
    // The lines that were the defect: five gates decided in an `if` inside the
    // async run, and the control adjudicated in a mutable local beside its own
    // `console.log`s. Both were unreachable without a browser. The disjunction
    // still exists — in `verdict`, which is the point — so this asks about
    // `main`, where a surviving copy would be the second seat.
    assert.ok(!/totalUnverified|totalNonPositive/.test(MAIN), 'the inline exit must not survive in `main`');
    assert.ok(!/let controlPass = true;/.test(SRC), 'the control must be decided in one seat');
    // All five terms, in their original order: what moved is where the
    // disjunction lives, not what it says.
    assert.ok(
      SRC.includes(
        'if (!parity.identical || totalUnverified > 0 || pageErrors.length > 0 || !control.pass || totalNonPositive > 0) {'
      ),
      'the five terms must survive verbatim, in `verdict`'
    );
  });

  test('the recording site COUNTS a non-measurement rather than dropping it', () => {
    // The distinction between a gate and a filter, and the only part of the
    // recording site a pure call cannot reach: the loop that puts a sample to
    // `notMeasured` lives inside the per-arm page work.
    const ARM = SRC.slice(SRC.indexOf('async function measureArm('), SRC.indexOf('async function main()'));
    assert.match(ARM, /const bad = notMeasured\(d\);/);
    assert.match(ARM, /nonPositive\+\+;/);
    assert.match(ARM, /return \{ samples: out, unverified, nonPositive, errors \};/, 'both counts and the errors must leave the arm');
  });

  test('the header documents both codes the decision can return', () => {
    const header = SRC.slice(0, SRC.indexOf("const fs = require"));
    for (const code of ['0', '1']) {
      assert.match(header, new RegExp(`^//   ${code}  \\S`, 'm'), `exit code ${code} must be documented`);
    }
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
