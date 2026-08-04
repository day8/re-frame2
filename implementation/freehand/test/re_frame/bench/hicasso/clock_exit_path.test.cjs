#!/usr/bin/env node
'use strict';
// THE HICASSO BENCH DRIVERS' EXIT PATH — a printed refusal must refuse.
// rf2-rr6do, following rf2-tb345's repair of the same defect in b8_run.cjs,
// and rf2-y7mw7, which found the twenty-fourth instance of it — in
// `clock_run.cjs`, the driver this file's header once held up as the correct
// shape.
//
// It began as the two CLOCK drivers' pin and now holds four, because the
// defect is not a property of clocks: it is one copied exit block, and the
// pin belongs wherever the block went. `hd8_run.cjs` (rf2-x6g04) is the
// fourth and is not a clock driver at all.
//
//     node freehand/test/re_frame/bench/hicasso/clock_exit_path.test.cjs
//
// THE DEFECT THIS PINS. Both drivers computed THREE refusals per row —
// unverified read-backs, a reproducibility band over `seam.cjs`'s ceiling,
// and a positive control that missed its own arithmetic — printed every one
// of them in the report, and wrote every one of them into the run's dataset.
// The exit block then read `failed` and the arm-order guard AND NOTHING
// ELSE. So a quiet box with a clean guard could print
//
//     ;; writes   4 unverified of 36 (mount + element-count read-backs)
//     ;; ---- THE BAND ...: 41.2% — ceiling 35% — BREACHED, no magnitude reportable ----
//     ;;   FAIL  measured 1.21x [...] against [1.50 – 2.50]
//
// and exit 0 on figures its own report had just refused. Printing a refusal
// is not refusing. `census_clock_run.cjs` made it sharper still: its
// prediction P4, registered before any clock, promises that a row whose
// control or band cannot hold "publishes a REFUSAL with the reason, not a
// number" — a promise the process exit did not keep.
//
// WHY IT IS PINNED HERE. Both drivers need an `:advanced` release build and
// a headless Chromium, so their verdicts cannot be exercised end-to-end in a
// unit test. The repair therefore put the whole decision in ONE pure
// function over a flat summary, which this file exercises directly, plus the
// wiring pins that the exit code comes from that function and from no second
// reading of a refusal. Those together are what make "a refused row cannot
// be green" a checked claim rather than an asserted one.
//
// THE CORRECT SHAPE ALREADY EXISTED: `clock_run.cjs` gates all three. This
// is that shape, made checkable.
//
// AND THEN `clock_run.cjs` ITSELF (rf2-y7mw7). It gated all three and still
// exited 0 on a row it had adjudicated NOTHING on: it computes a row-level
// control verdict and a bar-level adjudication independently, and only the
// first reached the exit code. `HCLOCK_ONLY=keystroke` alone printed
// `[clock] ok` for a run whose every bar it had just labelled UNADJUDICATED.
// Its decision now has one seat too, and the last section of this file is
// that seat's fixtures plus the wiring that makes them load-bearing.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// Requiring a driver must NOT drive it: the `require.main === module` guard
// is itself part of what is under test here.
const DRIVERS = [
  {
    tag: '[hd8clock]',
    file: path.join(__dirname, 'hd8_clock_run.cjs'),
    mod: require('./hd8_clock_run.cjs'),
  },
  {
    tag: '[c56clock]',
    file: path.join(__dirname, 'shapes', 'census_clock_run.cjs'),
    mod: require('./shapes/census_clock_run.cjs'),
  },
];

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/** A row that passed every gate. Overridden one field at a time below. */
const row = (over) => ({
  id: 'uix/mount-M',
  guardRefuse: false,
  unverified: 0,
  writes: 36,
  ctlOk: true,
  ctlMeasured: 1.8173,
  ceilingBreached: false,
  band: 0.121,
  ...over,
});

for (const { tag, file, mod } of DRIVERS) {
  const { summarise, verdict } = mod;
  const name = path.basename(file);
  const t = (what, fn) => test(`${name}: ${what}`, fn);

  // --- the green case first, so the gate is not vacuously red --------------

  t('a clean run exits 0 and says nothing', () => {
    assert.deepStrictEqual(verdict({ failed: null, rows: [row({}), row({ id: 'reagent/mount-M' })] }), {
      code: 0,
      lines: [],
    });
  });

  t('a summary that never ran (undefined) is not a refusal', () => {
    assert.strictEqual(verdict(undefined).code, 0);
    assert.strictEqual(verdict({ failed: null, rows: [] }).code, 0);
  });

  // --- the three defects, each ALONE, on an otherwise clean run ------------

  t('UNVERIFIED WRITES alone are a nonzero exit — the case that used to be green', () => {
    const v = verdict({ failed: null, rows: [row({ unverified: 4 })] });
    assert.notStrictEqual(v.code, 0, 'a window that never reached the page must not exit 0');
    assert.strictEqual(v.code, 3);
    assert.strictEqual(v.lines.length, 1, 'only the unverified refusal should be reported');
    assert.match(v.lines[0], /^\[\w+\] REFUSED — unverified operations/);
    assert.match(v.lines[0], /uix\/mount-M: 4 of 36/);
    assert.match(v.lines[0], /rf2-rr6do/);
  });

  t('A BREACHED BAND CEILING alone is a nonzero exit — the case that used to be green', () => {
    const v = verdict({ failed: null, rows: [row({ ceilingBreached: true, band: 0.412 })] });
    assert.notStrictEqual(v.code, 0, 'a band over the ceiling must not exit 0');
    assert.strictEqual(v.code, 4);
    assert.strictEqual(v.lines.length, 1);
    assert.match(v.lines[0], /REFUSED — the run's own reproducibility band exceeds/);
    assert.match(v.lines[0], /uix\/mount-M \(41\.2%\)/);
    assert.match(v.lines[0], /rf2-ymi6j/);
  });

  t('A FAILED POSITIVE CONTROL alone is a nonzero exit — the case that used to be green', () => {
    const v = verdict({ failed: null, rows: [row({ ctlOk: false, ctlMeasured: 1.2134 })] });
    assert.notStrictEqual(v.code, 0, 'a control that missed its prediction must not exit 0');
    assert.strictEqual(v.code, 5);
    assert.strictEqual(v.lines.length, 1);
    assert.match(v.lines[0], /REFUSED — the positive control did not see the change/);
    assert.match(v.lines[0], /uix\/mount-M \(measured 1\.2134x\)/);
    assert.match(v.lines[0], /No MAGNITUDE from those rows is reportable/);
  });

  t('a band with no finite figure still names itself rather than printing NaN', () => {
    const v = verdict({ failed: null, rows: [row({ ceilingBreached: true, band: NaN })] });
    assert.strictEqual(v.code, 4);
    assert.match(v.lines[0], /uix\/mount-M \(n\/a\)/);
  });

  // --- the pre-existing contract, unchanged --------------------------------

  t('the arm-order guard alone still exits 2', () => {
    const v = verdict({ failed: null, rows: [row({ guardRefuse: true })] });
    assert.strictEqual(v.code, 2, 'a run that exited 2 before must still exit 2');
    assert.strictEqual(v.lines.length, 1);
    assert.match(v.lines[0], /ARM-ORDER GUARD REFUSED/);
    assert.match(v.lines[0], /Repair the arm, not the guard: uix\/mount-M/);
  });

  t('a failed run still exits 1', () => {
    const v = verdict({ failed: 'the box would not go quiet before uix/mount-M', rows: [] });
    assert.strictEqual(v.code, 1, 'a run that exited 1 before must still exit 1');
    assert.match(v.lines[0], /^\[\w+\] FAILED: the box would not go quiet/);
  });

  // --- combinations: nothing masks anything, precedence is preserved -------

  t('all three new refusals together: all THREE are named, band precedes control', () => {
    const v = verdict({
      failed: null,
      rows: [row({ unverified: 2, ceilingBreached: true, band: 0.5, ctlOk: false })],
    });
    assert.strictEqual(v.code, 3, 'the first-declared new refusal takes the code');
    assert.strictEqual(v.lines.length, 3, 'no refusal may mask another');
    assert.match(v.lines[0], /unverified operations/);
    assert.match(v.lines[1], /reproducibility band/);
    assert.match(v.lines[2], /positive control/);
  });

  t('guard refusal WITH a new refusal keeps the guard code and names both', () => {
    const v = verdict({ failed: null, rows: [row({ guardRefuse: true, ceilingBreached: true, band: 0.4 })] });
    assert.strictEqual(v.code, 2, 'a run that exited 2 before must still exit 2');
    assert.strictEqual(v.lines.length, 2);
    assert.match(v.lines.join('\n'), /ARM-ORDER GUARD REFUSED/);
    assert.match(v.lines.join('\n'), /reproducibility band/);
  });

  t('a failed run WITH new refusals keeps exit 1 and still names them', () => {
    const v = verdict({
      failed: 'page errors in uix/mount-M',
      rows: [row({ unverified: 1, guardRefuse: true })],
    });
    assert.strictEqual(v.code, 1, 'a run that exited 1 before must still exit 1');
    assert.strictEqual(v.lines.length, 3);
    assert.match(v.lines[0], /FAILED: page errors/);
  });

  t('a refusal on ANY row refuses the run, and every offending row is named', () => {
    const v = verdict({
      failed: null,
      rows: [row({ id: 'uix/mount-M' }), row({ id: 'reagent/mount-M', unverified: 7, writes: 36 })],
    });
    assert.strictEqual(v.code, 3);
    assert.match(v.lines[0], /reagent\/mount-M: 7 of 36/);
    assert.doesNotMatch(v.lines[0], /uix\/mount-M/, 'a clean row must not be blamed');
  });

  // --- summarise: the accessor paths the defect hid behind ------------------

  t('summarise reads the real adjudication paths, so a rename cannot re-hide a refusal', () => {
    const s = summarise(null, [
      {
        runId: 'uix',
        rowId: 'mount-M',
        tally: { unverified: 3, writes: 36 },
        adjudication: {
          guardRefuse: true,
          ctl: { ok: false, measured: { mean: 1.2134 } },
          assessed: { verdict: { ceilingBreached: true }, bandStats: { band: 0.412 } },
        },
      },
    ]);
    assert.deepStrictEqual(s, {
      failed: null,
      rows: [
        {
          id: 'uix/mount-M',
          guardRefuse: true,
          unverified: 3,
          writes: 36,
          ctlOk: false,
          ctlMeasured: 1.2134,
          ceilingBreached: true,
          band: 0.412,
        },
      ],
    });
    // And the summary it built refuses, rather than merely describing.
    assert.strictEqual(verdict(s).code, 2);
    assert.strictEqual(verdict(s).lines.length, 4, 'every condition on the row is named');
  });

  t('summarise survives a run that took no rows', () => {
    assert.deepStrictEqual(summarise('build failed', []), { failed: 'build failed', rows: [] });
    assert.deepStrictEqual(summarise(null, undefined), { failed: null, rows: [] });
  });

  // --- the wiring: `verdict` is load-bearing, not decorative ----------------

  const SRC = fs.readFileSync(file, 'utf8');
  // `drive` alone — the module tail below it is the ONLY place an exit may
  // be taken, and it is asserted separately.
  const DRIVE = SRC.slice(SRC.indexOf('async function drive('), SRC.indexOf('\nmodule.exports'));

  t('the driver exposes its run as `drive` and its decision as `verdict`', () => {
    assert.ok(DRIVE.length > 0, 'the driver must expose its run as `drive`');
    assert.match(SRC, /module\.exports = \{ summarise, verdict \};/);
    assert.match(SRC, /require\.main === module/);
    assert.match(SRC, /drive\(\)\.then\(\(code\) => \{\s*if \(code !== 0\) process\.exit\(code\);/);
  });

  t('the exit code comes from `verdict` and is RETURNED, not re-derived', () => {
    assert.match(DRIVE, /const v = verdict\(summarise\(failed, results\)\);/);
    assert.match(DRIVE, /for \(const line of v\.lines\) console\.error\(line\);/);
    assert.match(DRIVE, /return v\.code;/);
  });

  t('`drive` never calls process.exit itself — the decision has ONE seat', () => {
    // The defect was a second exit path reading a subset of the conditions.
    // A `process.exit` inside `drive` is that second path by construction,
    // and it would also be invisible to every test above.
    assert.ok(
      DRIVE.length > 0 && !/process\.exit/.test(DRIVE),
      '`drive` must return its code, never exit — a process.exit inside it is a second decision'
    );
  });

  t('NOTHING downstream of `verdict` reads a refusal on its own', () => {
    // This is the assertion that keeps the original defect from growing
    // back. The defect WAS an exit block reading `guardRefuse` directly
    // while three siblings sat unread beside it; if any of the four is
    // named again after the decision is taken, some second path is deciding
    // the exit and the pure-function tests above stop covering it.
    const tail = DRIVE.slice(DRIVE.indexOf('const v = verdict('));
    assert.ok(
      tail.length > 0 && !/guardRefuse|ceilingBreached|ctl\.ok|tally\.unverified/.test(tail),
      'the exit path must consult `verdict` alone, never a refusal directly'
    );
  });

  t('the header still documents every code the decision can return', () => {
    const header = SRC.slice(0, SRC.indexOf("'use strict'"));
    for (const code of ['0', '1', '2', '3', '4', '5']) {
      assert.match(header, new RegExp(`^//   ${code}  \\S`, 'm'), `exit code ${code} must be documented`);
    }
  });

  t('every refusal line names the driver, so a piped log says which run refused', () => {
    const v = verdict({
      failed: 'x',
      rows: [row({ guardRefuse: true, unverified: 1, ceilingBreached: true, ctlOk: false })],
    });
    assert.strictEqual(v.lines.length, 5);
    for (const line of v.lines) assert.ok(line.startsWith(`${tag} `), `not tagged ${tag}: ${line}`);
  });
}

// --- and the promise census_clock_run.cjs published --------------------------

test('census P4 is now KEPT: its own prediction of a refusal reaches the exit', () => {
  const { verdict } = DRIVERS[1].mod;
  const SRC = fs.readFileSync(DRIVERS[1].file, 'utf8');
  // The prediction, registered before any clock, in the driver's own words.
  assert.match(SRC, /the row publishes a REFUSAL with the/);
  assert.match(SRC, /reason, not a number/);
  // Its control cannot hold ...
  const ctl = verdict({ failed: null, rows: [row({ ctlOk: false })] });
  assert.notStrictEqual(ctl.code, 0, 'P4 promises a refusal when the control cannot hold');
  // ... nor its band.
  const band = verdict({ failed: null, rows: [row({ ceilingBreached: true, band: 0.4 })] });
  assert.notStrictEqual(band.code, 0, 'P4 promises a refusal when the band cannot hold');
});

// --- clock_run.cjs: the bar-level adjudication must reach the exit code -------
//
// rf2-y7mw7. `clock_run.cjs` is the candidate clock and needs an `:advanced`
// build and a headless Chromium, so — exactly as above — its decision is a
// pure function over a flat per-row summary and is exercised here directly.

{
  const CLOCK = path.join(__dirname, 'clock_run.cjs');
  const { reportability, reportabilitySelfTest } = require('./clock_run.cjs');
  const SRC = fs.readFileSync(CLOCK, 'utf8');
  const t = (what, fn) => test(`clock_run.cjs: ${what}`, fn);
  const KEYSTROKE_WHY = "UNADJUDICATED — this row's control burns a fixed 50 ms rather than doubling the page";
  const clockRow = (over) => ({ rowId: 'M1', ctlOk: true, ctlNote: '', adjudicable: true, ...over });

  // --- the driver's own fixtures, which `--selftest` also runs --------------

  t("the decision's own self-test passes, every case", () => {
    const { checks } = reportabilitySelfTest();
    assert.ok(checks.length >= 10, `expected the decision's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad, [], bad.map((c) => `${c.name}: ${c.detail}`).join('\n'));
  });

  // --- the defect itself, driven here as well as in the driver -------------

  t('THE FAIL-OPEN: a row whose every bar is UNADJUDICATED cannot exit 0', () => {
    const v = reportability([
      clockRow({ rowId: 'keystroke', adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY }),
    ]);
    assert.notStrictEqual(v.code, 0, 'a run with no adjudicable bar must not exit 0');
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /no published bar can be ADJUDICATED on: keystroke/);
    assert.match(v.lines[1], /keystroke: UNADJUDICATED/);
    assert.strictEqual(v.lines[2], '[clock] REPORTABLE: none.');
  });

  t('a row that adjudicated NO bar at all fails closed rather than open', () => {
    // The summary the driver builds sets `adjudicable` false when the verdict
    // carries no bars, so a verdict that went missing refuses instead of
    // passing quietly — the same reason `seam.cjs` refuses a NaN band.
    const v = reportability([clockRow({ adjudicable: false, unadjudicatedWhy: undefined })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[1], /M1: no proportional control on this row/);
  });

  t('a genuinely reportable run still exits 0 and says nothing', () => {
    const v = reportability([clockRow({}), clockRow({ rowId: 'bulk300' })]);
    assert.deepStrictEqual(v, { code: 0, lines: [] });
  });

  t('a bar INSIDE the band is adjudicated, so an instrument-limited row still exits 0', () => {
    // `LIMITED` is a verdict. Only `UNADJUDICATED` is the absence of one, and
    // conflating them would turn this gate into a magnitude gate.
    assert.strictEqual(reportability([clockRow({ adjudicable: true })]).code, 0);
  });

  // --- the control gate is UNCHANGED, which is half the repair --------------

  t('a failed positive control alone still exits 1, exactly as before', () => {
    const v = reportability([clockRow({ ctlOk: false, ctlNote: ' (three-point 1.2134x vs 2.0101x)' })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /the positive control did not see the change its own arithmetic predicts on: /);
    assert.match(v.lines[0], /M1 \(three-point 1\.2134x vs 2\.0101x\)/);
    assert.match(v.lines[0], /No MAGNITUDE from those rows is reportable/);
  });

  t('the control refusal is still per-row: the rows that passed are still rows', () => {
    const v = reportability([clockRow({}), clockRow({ rowId: 'narrow', ctlOk: false })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[v.lines.length - 1], /^\[clock\] REPORTABLE: M1 —/);
  });

  t('HCLOCK_CTL3_SABOTAGE still declares the run a falsification', () => {
    const v = reportability([clockRow({ ctlOk: false })], { sabotage: 140 });
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[1], /HCLOCK_CTL3_SABOTAGE=140 WAS SET/);
  });

  t('neither verdict masks the other — a row failing both is refused for both', () => {
    const v = reportability([
      clockRow({ rowId: 'keystroke', ctlOk: false, adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY }),
    ]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /positive control/);
    assert.match(v.lines[1], /no published bar can be ADJUDICATED/);
    assert.strictEqual(v.lines[3], '[clock] REPORTABLE: none.');
  });

  // --- and the sentence that invited the publication -----------------------

  t('REPORTABLE no longer says "Publish those" without saying "adjudicated"', () => {
    const v = reportability([clockRow({}), clockRow({ rowId: 'keystroke', adjudicable: false })]);
    const line = v.lines[v.lines.length - 1];
    assert.match(line, /every published bar adjudicated against this run's own band/);
    assert.doesNotMatch(line, /keystroke/, 'an unadjudicated row must never appear in REPORTABLE');
  });

  // --- the wiring: `reportability` is load-bearing, not decorative ----------

  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));

  t('the driver exposes its decision and does not drive itself on require', () => {
    assert.ok(MAIN.length > 0, 'the driver must expose its run as `main`');
    assert.match(SRC, /module\.exports = \{ reportability, reportabilitySelfTest \};/);
    assert.match(SRC, /if \(require\.main === module\) \{\s*main\(\);/);
  });

  t('the summary reads the adjudication the report printed, rather than recomputing it', () => {
    // A second computation is a second decision, and a second decision is
    // this whole file's subject.
    assert.match(MAIN, /o\.verdict\.seamTask && o\.verdict\.seamTask\.rows/);
    assert.match(MAIN, /adjudicable: names\.length > 0 && unadj\.length < names\.length,/);
  });

  t('the exit code comes from `reportability` and from nothing else', () => {
    assert.match(MAIN, /for \(const line of decision\.lines\) console\.error\(line\);/);
    assert.match(MAIN, /if \(decision\.code !== 0\) process\.exit\(decision\.code\);/);
    const tail = MAIN.slice(MAIN.indexOf('for (const line of decision.lines)'));
    assert.strictEqual(
      (tail.match(/process\.exit/g) || []).length,
      1,
      'the decision must have ONE seat — a second exit below it is a second decision'
    );
    assert.ok(
      !/ctlBad\(|seamTask|unadjudicated|ctlFailed/.test(tail),
      'nothing downstream of the decision may read a refusal on its own'
    );
  });

  t('`--selftest` runs the decision, so an operator sees it before the browser opens', () => {
    const block = SRC.slice(SRC.indexOf('if (SELFTEST_ONLY) {'), SRC.indexOf('if (!NO_BUILD)'));
    assert.match(block, /reportabilitySelfTest\(\)/);
    assert.match(block, /\[\.\.\.g\.checks, \.\.\.s\.checks, \.\.\.x\.checks\]\.filter/);
  });
}

// --- hd8_run.cjs: the read-back and the refused correction must exit -------
//
// rf2-x6g04. The HD-008 donor driver is not a clock driver, but it carried
// the same copied exit block: it read `hardFail`, `contractFailed` and the
// arm-order `refused`, and printed `[hd8] ok`. Three refusals it computes and
// PRINTS reached nothing. Like its neighbours it needs an `:advanced` build
// and a headless Chromium, so its decision is a pure function over a flat
// record and is exercised here directly.

{
  const HD8 = path.join(__dirname, 'hd8_run.cjs');
  const { summarise, verdict, verdictSelfTest } = require('./hd8_run.cjs');
  const SRC = fs.readFileSync(HD8, 'utf8');
  const t = (what, fn) => test(`hd8_run.cjs: ${what}`, fn);
  const run = (over) => ({ id: 'slim', summary: {}, correction: {}, pageErrors: [], ...over });
  // The reachable read-back shape, exactly as `mask-failed-read-backs`
  // (hd8_rows.cljs ~913-944) writes it into the row's `:summary`.
  const readBack = () =>
    run({
      summary: {
        'write-narrow': {
          vsFloor: { floor: { min: 1, max: 1 }, 'reagent-slim': { unpublished: 'failed-dom-read-back', unverified: 1, of: 78 } },
          headToHead: {},
        },
      },
    });
  // And the reachable refusal shape (hd8_rows.cljs ~1307/1315/1330/1365).
  const refusedCorrection = () =>
    run({ correction: { 'write-narrow': { verdict: 'refused', reason: 'correction-changes-the-verdict', why: 'reverses the row' } } });

  // --- the driver's own fixtures, which `--selftest` also runs -------------

  t("the decision's own self-test passes, every case", () => {
    const { checks } = verdictSelfTest();
    assert.ok(checks.length >= 15, `expected the decision's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad, [], bad.map((c) => `${c.name}: ${c.detail}`).join('\n'));
  });

  // --- THE FAIL-OPENS, driven here as well as in the driver ---------------

  t('THE FAIL-OPEN (a): a failed DOM read-back cannot exit 0', () => {
    const v = verdict(summarise({ runs: [readBack()] }));
    assert.notStrictEqual(v.code, 0, 'a run whose arm never reached the DOM must not exit 0');
    assert.strictEqual(v.code, 3, 'and it exits 3, as hd8_clock_run.cjs numbers the same refusal');
    assert.match(v.lines.join('\n'), /never reached the DOM/);
    assert.match(v.lines.join('\n'), /slim \/ write-narrow \/ reagent-slim vs floor: failed-dom-read-back \(1 of 78 unverified\)/);
  });

  t('THE FAIL-OPEN (b): a REFUSED yield correction cannot exit 0', () => {
    const v = verdict(summarise({ runs: [refusedCorrection()] }));
    assert.notStrictEqual(v.code, 0);
    assert.strictEqual(v.code, 4);
    assert.match(v.lines.join('\n'), /yield correction could not be discharged/);
    assert.match(v.lines.join('\n'), /write-narrow: correction-changes-the-verdict/);
  });

  t('THE FAIL-OPEN (c): a pageerror recorded beside the sentinel cannot exit 0', () => {
    const v = verdict(summarise({ runs: [run({ pageErrors: ['pageerror: boom'] })] }));
    assert.strictEqual(v.code, 1, 'a page error is the exit 1 this driver already documented');
    assert.match(v.lines.join('\n'), /already thrown/);
  });

  t('a genuinely reportable run still exits 0 and says nothing', () => {
    const v = verdict(
      summarise({
        runs: [
          run({
            summary: { 'write-narrow': { vsFloor: { floor: { min: 1, max: 1 }, 'donor-r1': { min: 2, max: 2 } }, headToHead: { 'a vs b': { min: 1, max: 1 } } } },
            correction: { 'write-narrow': { verdict: 'not-owed' } },
          }),
        ],
      })
    );
    assert.deepStrictEqual(v, { code: 0, lines: [] });
  });

  t('a `corrected` correction is not a refusal — only `refused` is', () => {
    assert.strictEqual(verdict(summarise({ runs: [run({ correction: { r: { verdict: 'corrected' } } })] })).code, 0);
  });

  // --- the gates that already existed are UNCHANGED, which is half the repair

  t('a hard failure still exits 1, exactly as before', () => {
    const v = verdict(summarise({ hardFail: 'slim: boom' }));
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /^\[hd8\] FAILED: slim: boom/);
  });

  t('a contract self-test failure still exits 1, with its own sentence', () => {
    const v = verdict(summarise({ contractFailed: 'slim: fixtures' }));
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /does not agree with its recorded fixtures/);
  });

  t('the arm-order guard still exits 2, and still says repair the arm', () => {
    const v = verdict(summarise({ orderRefused: true }));
    assert.strictEqual(v.code, 2);
    assert.match(v.lines[0], /ARM-ORDER GUARD REFUSED/);
    assert.match(v.lines[0], /Repair the arm, not the guard/);
  });

  t('no verdict masks another — a run failing several is refused for every one', () => {
    const v = verdict(summarise({ orderRefused: true, runs: [readBack(), refusedCorrection()] }));
    assert.strictEqual(v.code, 2, 'the hardest code wins the exit');
    const all = v.lines.join('\n');
    assert.match(all, /ARM-ORDER GUARD REFUSED/);
    assert.match(all, /never reached the DOM/);
    assert.match(all, /yield correction could not be discharged/);
  });

  // --- the wiring: `verdict` is load-bearing, not decorative ---------------

  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));

  t('the driver exposes its decision and does not drive itself on require', () => {
    assert.ok(MAIN.length > 0, 'the driver must expose its run as `main`');
    assert.match(SRC, /module\.exports = \{ summarise, verdict, verdictSelfTest \};/);
    assert.match(SRC, /if \(require\.main === module\) \{\s*main\(\);/);
  });

  t('the summary reads the markers the table printed, rather than recomputing them', () => {
    // A second computation is a second decision, and a second decision is
    // this whole file's subject.
    assert.match(SRC, /if \(v && v\.unpublished\)/);
    assert.match(SRC, /c\.verdict === 'refused'/);
  });

  t('the sentinel\'s failures are READ, which they were not at all', () => {
    // In `runOne`, above `main` — the driver installed `watchPage` and then
    // read nobody's failures, alone among the six callers of it.
    assert.match(SRC, /const pageErrors = watch\.failures\.map/);
    assert.match(SRC, /correction, contractSelfTest, userAgent, lines, pageErrors,/);
  });

  t('the exit code comes from `verdict` and from nothing else', () => {
    assert.match(MAIN, /for \(const line of decision\.lines\) console\.error\(line\);/);
    assert.match(MAIN, /if \(decision\.code !== 0\) process\.exit\(decision\.code\);/);
    const tail = MAIN.slice(MAIN.indexOf('for (const line of decision.lines)'));
    assert.strictEqual(
      (tail.match(/process\.exit/g) || []).length,
      1,
      'the decision must have ONE seat — a second exit below it is a second decision'
    );
    // No SECOND `if (someRefusal)` below the seat. Matched on the condition
    // rather than the bare word, because the `ok` line legitimately says
    // "no yield correction was refused" — prose is not a decision.
    assert.ok(
      !/if \(\s*(hardFail|contractFailed|refused|orderRefused)\s*\)/.test(tail),
      'nothing downstream of the decision may read a refusal on its own'
    );
  });

  t('`--selftest` runs the decision, so an operator sees it before the browser opens', () => {
    const block = SRC.slice(SRC.indexOf('if (SELFTEST_ONLY) {'), SRC.indexOf('const sha = revision()'));
    assert.match(block, /\[\.\.\.st\.checks, \.\.\.ts\.checks, \.\.\.vs\.checks\]\.filter/);
    assert.match(SRC, /const vs = verdictSelfTest\(\);/);
  });

  t('the `ok` line no longer claims only what the old gates checked', () => {
    const ok = SRC.slice(SRC.indexOf("'[hd8] ok"));
    assert.match(ok, /survived its DOM read-back/);
    assert.match(ok, /no yield correction was refused/);
  });
}

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
  console.error(`\nclock_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`clock_exit_path.test.cjs: ${tests.length} passed`);
