#!/usr/bin/env node
'use strict';
// THE HD8/CENSUS CLOCK DRIVERS' EXIT PATH — a printed refusal must refuse.
// rf2-rr6do, following rf2-tb345's repair of the same defect in b8_run.cjs.
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
