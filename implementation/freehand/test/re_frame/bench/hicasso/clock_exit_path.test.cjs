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
// AND THEN THE REPAIR'S OWN REMAINDER, which #7489's merged-PR audit found.
// The bar-level term reached the exit code but was derived one notch too
// loose, in BOTH places that carry it: `clock_run.cjs` asked
// `unadj.length < names.length` and `clock_readjudicate.cjs` asked
// `names.some(...)`, so ONE adjudicated bar carried a row whose other
// published bars had no band at all — the driver exiting 0 under a sentence
// reading "every published bar adjudicated", the readjudicator pooling that
// run into the published mean for every pair including the unadjudicated one.
//
// The reason it survived a landing is the lesson: both rules lived where no
// test could reach them — inline in the driver's `main`, and in a file that
// read `process.argv` at module scope so requiring it ran it — and the only
// thing holding the driver's was a regex over its own source, which matched
// the wrong rule as faithfully as it would have matched the right one. A rule
// a test cannot drive is not a checked rule however exactly it is quoted.
// Both are pure exported functions now (`rowAdjudication`, `adjudicated`),
// both require every published bar to carry a band, and both are driven below
// on the mixed-bar case that neither used to have.
//
// AND THEN THAT REPAIR'S REMAINDER, which #7550's merged-PR audit found: the
// strict rule was asking TRUTHINESS, so absence read as cleanliness one level
// down. A bar stored as `{}` — present, carrying no verdict at all — counted
// as adjudicated in both seats. `rowAdjudication` returned `adjudicable: true`
// beside an `unadjudicatedWhy` reading "the run adjudicated no bar on this row
// at all", contradicting itself inside one returned object; `adjudicated`
// returned true and the reader pooled the run into the published mean. A bar
// stored as `null` did not even fail open — it threw. Both now require an
// EXPLICIT `unadjudicated === false`, absence and null included, and the
// fixtures for it are in both blocks below.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
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
    // `summarise` and `verdict` FIRST and always — a driver may export more
    // (both clock drivers add `destination`, their write-path decision), but
    // the decision pair is what every test below reaches for.
    assert.match(SRC, /module\.exports = \{ summarise, verdict\s*[,}]/);
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

// --- census_clock_run.cjs: the WRITE path, not just the exit path ------------
//
// rf2-2rtt6.56 (merged-PR audit #7379). `verdict` decides what may be QUOTED;
// `destination` decides what may be WRITTEN. The driver used to write the
// canonical datasets before the refusal was consulted and under the canonical
// filenames whatever shape the run had, so a narrowed / --no-build / refused
// run silently replaced the published evidence. Same fail-open as the exit
// path, one file over, and checkable the same hermetic way.

{
  const CENSUS = DRIVERS[1].file;
  const { destination } = DRIVERS[1].mod;
  const SRC = fs.readFileSync(CENSUS, 'utf8');
  const CANON = '/data/censusclock-2rtt6-56';
  const shape = (over = {}) => ({
    dataDir: CANON,
    dataDirOverridden: false,
    rowsOnly: null,
    runsOnly: null,
    noBuild: false,
    depthPublished: true,
    skipQuiet: false,
    ...over,
  });
  const t = (what, fn) => test(`census_clock_run.cjs write path: ${what}`, fn);

  t('the published shape is the ONLY thing that is canonical', () => {
    const d = destination(shape());
    assert.strictEqual(d.canonical, true);
    assert.strictEqual(d.dir, CANON);
    assert.strictEqual(d.why, null);
  });

  // Each condition alone must move the write off the canonical set. These are
  // the mutation proofs: flip one field, the destination must change.
  //
  // EVERY ONE OF THEM IS A FACT ABOUT THE RUN'S SHAPE, and after rf2-pzqy8
  // that is the whole of this list — a refused VERDICT used to be on it and
  // is not, because a gate refusal belongs to one row and is recorded there
  // (`rowPublication`, below). `clock_run.cjs`'s `publication(shape)` has
  // read shape and nothing else all along; this is now that same rule.
  for (const [what, over, needle] of [
    ['a partial row set', { rowsOnly: 'feed' }, /PARTIAL row set/],
    ['a partial run set', { runsOnly: 'uix' }, /PARTIAL run set/],
    ['--no-build', { noBuild: true }, /--no-build/],
    ['an overridden depth', { depthPublished: false }, /OVERRIDDEN design depth/],
    // rf2-azopg. C56CLOCK_SKIP_QUIET=1 made `quietGate` return ok:true and
    // print "NOT the published shape", and then nothing carried that fact to
    // the write decision — so a run whose samples were never checked against a
    // quiet box could occupy the canonical directory, indistinguishable from
    // one taken in a granted window. This is the probe that found it: the
    // published shape in every other respect, exit 0, quiet gate skipped.
    ['a SKIPPED quiet gate', { skipQuiet: true }, /SKIPPED quiet gate \(C56CLOCK_SKIP_QUIET=1\)/],
  ]) {
    t(`${what} is NOT canonical, and says why`, () => {
      const d = destination(shape(over));
      assert.strictEqual(d.canonical, false, `${what} must not be canonical`);
      assert.notStrictEqual(d.dir, CANON, `${what} must not write the published filenames`);
      assert.strictEqual(d.dir, `${CANON}.unpublished`);
      assert.match(d.why, needle);
      // ... and the same shape WITHOUT that condition is canonical again, so
      // the test cannot pass by refusing everything.
      assert.strictEqual(destination(shape()).canonical, true);
    });
  }

  t('every condition that fired is named, not just the first', () => {
    const d = destination(shape({ rowsOnly: 'feed', noBuild: true, depthPublished: false, skipQuiet: true }));
    assert.strictEqual(d.canonical, false);
    for (const needle of [/PARTIAL row set/, /--no-build/, /OVERRIDDEN design depth/, /SKIPPED quiet gate/]) {
      assert.match(d.why, needle);
    }
  });

  // rf2-azopg, the other half. `destination` is pure over a shape RECORD; the
  // one caller that builds the real shape is `runShape`, and it is not
  // exported. So a `destination` that reads `skipQuiet` correctly still fails
  // open if `runShape` never puts it there — which is precisely how the defect
  // survived: the flag existed, printed "NOT the published shape", and never
  // reached the write decision. Pin the wiring in the source, the same way the
  // ordering pins below do, because no test of the export can see it.
  t('runShape carries the skip-quiet fact into the write decision', () => {
    assert.match(SRC, /const SKIP_QUIET = process\.env\.C56CLOCK_SKIP_QUIET === '1';/);
    const from = SRC.indexOf('const runShape = () => ({');
    assert.ok(from > 0, 'runShape no longer has the shape this test pins');
    const body = SRC.slice(from, SRC.indexOf('});', from));
    assert.match(
      body,
      /skipQuiet: SKIP_QUIET,/,
      'runShape must carry skipQuiet, or a skipped quiet gate can still write the canonical set'
    );
  });

  t('an explicit C56CLOCK_DATA_DIR is honoured as given, and is never canonical', () => {
    const mine = '/data/censusclock-somebead';
    const d = destination(shape({ dataDir: mine, dataDirOverridden: true }));
    assert.strictEqual(d.dir, mine, 'the operator named the destination; do not rewrite it');
    assert.strictEqual(d.canonical, false, 'an operator-named directory is not the published set');
    // It still lands where the operator said whatever else the run was — the
    // refusal is carried by the exit code and by `canonical`, never by moving
    // the file out from under the name the operator gave it.
    assert.strictEqual(destination(shape({ dataDir: mine, dataDirOverridden: true, skipQuiet: true })).dir, mine);
  });

  // The defect was an ORDERING one: the write happened, and the refusal was
  // computed afterwards. Pin the order in the source, because that is what
  // regressed and a behavioural test of a browser driver cannot see it.
  t('the verdict is computed BEFORE any dataset is written', () => {
    const v = SRC.indexOf('const v = verdict(summarise(failed, results));');
    const dst = SRC.indexOf('const dest = destination(runShape());');
    const mk = SRC.indexOf('fs.mkdirSync(dest.dir');
    assert.ok(v > 0 && dst > 0 && mk > 0, 'the write path no longer has the shape this test pins');
    assert.ok(v < dst, 'the verdict must be computed before the destination is chosen');
    assert.ok(dst < mk, 'the destination must be chosen before the directory is created');
  });

  t('no dataset is written to the raw DATA_DIR downstream of `destination`', () => {
    // This is how the defect grew: the write named the canonical directory
    // directly. Every write site must go through the chosen destination.
    const after = SRC.slice(SRC.indexOf('const dest = destination(runShape());'));
    assert.ok(!/fs\.mkdirSync\(DATA_DIR/.test(after), 'mkdirSync must use the chosen destination');
    assert.ok(!/path\.join\(DATA_DIR/.test(after), 'the dataset path must use the chosen destination');
    assert.match(after, /path\.join\(dest\.dir/);
  });

  t('a dataset records whether it is the published evidence', () => {
    // A file that travels out of its directory must still say what it is.
    assert.match(SRC, /canonical: meta\.dest\.canonical/);
    assert.match(SRC, /notCanonicalWhy: meta\.dest\.why/);
  });

  t('the dataset is built OUTSIDE `drive`, so recording never looks like deciding', () => {
    // `datasetFor` names guardRefuse/ceilingBreached to SERIALISE them. Inside
    // `drive` and downstream of `verdict` that would trip the invariant above
    // — correctly, since a reader cannot tell a record from a second decision.
    assert.match(SRC, /^function datasetFor\(rows, meta\) \{/m);
    const drive = SRC.slice(SRC.indexOf('async function drive('));
    assert.ok(drive.indexOf('function datasetFor(') === -1, '`datasetFor` must sit above `drive`');
    assert.match(drive, /const data = datasetFor\(rows, \{ sha, blobs: bl, dest \}\);/);
  });

  // rf2-2rtt6.62: the counts the retraction turned on must be IN the evidence,
  // not recoverable only by reading the instrument at the producing commit.
  t("each row's workload counts are persisted with its numbers", () => {
    assert.match(SRC, /stamp: STAMP\[r\.rowId\]/);
    for (const needle of [/cards: '69 article cards'/, /cards: '300 article cards'/, /cards: '5 comment cards/]) {
      assert.match(SRC, needle);
    }
  });

  t('the published depth has ONE definition, shared by the stamp and the write path', () => {
    assert.match(SRC, /const PUBLISHED_DEPTH = \{ rounds: 6, blocks: 3, warmup: 4, samples: 10 \}/);
    // The old inline copy must be gone, or the two can drift apart again.
    assert.ok(
      !/ROUNDS === 6 && BLOCKS === 3 && WARMUP === 4 && SAMPLES === 10/.test(SRC),
      'the inline depth predicate is duplicated; use depthIsPublished()'
    );
    assert.ok(SRC.split('depthIsPublished()').length - 1 >= 2, 'the one predicate must serve both readers');
  });

  // --- rf2-jo60g: the split the driver collects must reach the file ----------
  //
  // `deltaOf` has always read ScriptDuration / LayoutDuration /
  // RecalcStyleDuration per sample, the report has always printed the row's
  // means, and `datasetFor` dropped all three. So census-real-clock-rows.md's
  // P1 scoring — "layout 2.06x, style 1.85x, script 2.3x" on the feed row —
  // was a figure the tree could not reproduce: real when taken, unreachable
  // afterwards. These tests drive the WRITE, hermetically, on a fixture row.
  // NO measurement is taken here and none is needed: what regressed is what
  // the serialiser keeps.

  const { datasetFor, foldDecomposition } = DRIVERS[1].mod;
  const round4 = (x) => Math.round(x * 10000) / 10000;

  // Four blocks over two rounds, ten samples each. Per-block sums differ so a
  // fold that read one block, or averaged the blocks' means, cannot pass:
  // only summing all four gives floor 1.0 / 1.0 / 0.1 ms per sample against
  // ctl-2x 2.06 / 1.85 / 0.23 — the page's cited ratios, exactly.
  const DECOMP_BLOCK = (n, layout, style, script) => ({
    n, task: layout + style + script, taskNet: 0, devtools: 0, script, style, layout, layoutCount: n, inPage: 0,
  });
  // The plan names plumb too and the collector bumps every arm in it, so a
  // faithful block carries plumb — laying nothing out and reading zeros, as it
  // does in the committed data. rf2-e1tko made this load-bearing: completeness
  // is measured against the row's DECLARED roster, so a fixture that omitted
  // plumb from every block would itself be the truncation under test.
  const DECOMP_PLUMB = () => ({
    n: 10, task: 0.5, taskNet: 0, devtools: 0, script: 0.5, style: 0, layout: 0, layoutCount: 0, inPage: 0,
  });
  const FIXTURE_DECOMP = [
    [
      { plumb: DECOMP_PLUMB(), floor: DECOMP_BLOCK(10, 8, 9, 0.8), 'ctl-2x': DECOMP_BLOCK(10, 20, 18, 2.0) },
      { plumb: DECOMP_PLUMB(), floor: DECOMP_BLOCK(10, 12, 11, 1.2), 'ctl-2x': DECOMP_BLOCK(10, 21, 19, 2.4) },
    ],
    [
      { plumb: DECOMP_PLUMB(), floor: DECOMP_BLOCK(10, 9, 10, 1.0), 'ctl-2x': DECOMP_BLOCK(10, 20.4, 18.5, 2.4) },
      { plumb: DECOMP_PLUMB(), floor: DECOMP_BLOCK(10, 11, 10, 1.0), 'ctl-2x': DECOMP_BLOCK(10, 21, 18.5, 2.4) },
    ],
  ];
  const CITED = { layout: 2.06, style: 1.85, script: 2.3 };

  // What the row DECLARES itself to be: the arms its plan named and the
  // dimensions its design ran. Both travel beside the split rather than out of
  // it — `report` reads them from the row and the run's design, a reader of a
  // written dataset from `row.armIds` and `data.design` — which is the whole
  // point: evidence cannot be its own completeness check (rf2-e1tko).
  const DECLARED = { armIds: ['plumb', 'floor', 'ctl-2x'], rounds: 2, blocks: 2 };

  const fixtureRow = (over = {}) => ({
    runId: 'reagent',
    rowId: 'feed',
    armIds: DECLARED.armIds,
    canon: { floor: true },
    ctlPredicted: 1.9943,
    blocksTask: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksNet: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksInPage: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksDecomp: FIXTURE_DECOMP,
    tally: { writes: 36, unverified: 0 },
    runtime: 'fixture',
    quiet: { ok: true },
    windowStart: '2026-08-07T00:00:00.000Z',
    adjudication: {
      // The whole `ctl` record a real row carries, `measured` included:
      // `datasetFor` now decides each row's own publication through the same
      // `summariseRow` mapper `summarise` uses, so a fixture missing a field
      // the mapper reads would be testing a row no run can produce
      // (rf2-pzqy8).
      ctl: { ok: true, measured: { mean: 1.9943 } },
      cAdditive: 0.9,
      assessed: { bandStats: { band: 0.12 }, verdict: { ceilingBreached: false } },
      bars: {},
      verdicts: {},
      guardRefuse: false,
      plumb: 0.5,
      floorTared: 1.14,
    },
    ...over,
  });
  const META = { sha: 'deadbeef', blobs: {}, dest: { canonical: true, why: null } };
  const written = () => JSON.parse(JSON.stringify(datasetFor([fixtureRow()], META))).rows[0];

  t('the Script / Layout / RecalcStyle split reaches the written dataset', () => {
    const row = written();
    assert.ok(row.blocksDecomp, 'the split was collected and then dropped at write time — that was the defect');
    // At the same block grain as the arrays beside it, not a row-level lump.
    assert.strictEqual(row.blocksDecomp.length, FIXTURE_DECOMP.length);
    assert.strictEqual(row.blocksDecomp[0].length, FIXTURE_DECOMP[0].length);
    for (const arm of ['floor', 'ctl-2x']) {
      for (const k of ['n', 'script', 'style', 'layout']) {
        assert.ok(Number.isFinite(row.blocksDecomp[0][0][arm][k]), `${arm}.${k} must be a number in the file`);
      }
    }
  });

  t("the studio page's cited decomposition is recomputable from the file alone", () => {
    // The whole point of the bead: read the JSON, fold it, divide, and the
    // page's three numbers come back out. Nothing here reads the browser.
    const fold = foldDecomposition(written().blocksDecomp, DECLARED);
    const mean = (arm, k) => fold[arm][k] / fold[arm].n;
    for (const [k, cited] of Object.entries(CITED)) {
      assert.strictEqual(round4(mean('ctl-2x', k) / mean('floor', k)), cited, `${k} must recompute to ${cited}x`);
    }
    // ... and the fold is the sum of the stored blocks, so the row the report
    // prints and the file are the same quantity.
    assert.strictEqual(fold.floor.n, 40);
    assert.strictEqual(round4(fold['ctl-2x'].layout), 82.4);
  });

  t('a row written BEFORE this change fails closed rather than folding to zeros', () => {
    // This is the state of every census dataset on main when rf2-jo60g was
    // filed. A fold that answered on it would hand the reader a number for a
    // split the file never recorded, which is the defect wearing a fix's face.
    const legacy = written();
    delete legacy.blocksDecomp;
    assert.throws(() => foldDecomposition(legacy.blocksDecomp, DECLARED), /NOT recomputable/);
    assert.throws(() => foldDecomposition([], DECLARED), /NOT recomputable/);
  });

  // Every committed census row, paired with the declared shape its own file
  // carries. `row.armIds` and `data.design` are serialised independently of
  // `blocksDecomp`, so reading them back measures the split against something
  // other than itself — which is the whole of rf2-e1tko in one line.
  const committedRows = () => {
    const dir = path.join(__dirname, 'data');
    const files = fs
      .readdirSync(dir)
      .filter((d) => d.startsWith('censusclock-'))
      .flatMap((d) => fs.readdirSync(path.join(dir, d)).filter((f) => f.endsWith('.json')).map((f) => path.join(dir, d, f)));
    assert.ok(files.length >= 2, 'expected the committed census datasets');
    return files.flatMap((f) => {
      const data = JSON.parse(fs.readFileSync(f, 'utf8'));
      return data.rows.map((row) => ({
        f,
        row,
        declared: { armIds: row.armIds, rounds: data.design.rounds, blocks: data.design.blocks },
      }));
    });
  };

  t('every committed census dataset either carries the split or refuses to answer', () => {
    // Capture backfills nothing: the datasets on disk gain the fields on the
    // next canonical run, not on this commit. Whatever is there, the rule is
    // the same — a row with the split folds, a row without it refuses. There
    // is no third answer, so this stays true across the re-run.
    for (const { f, row, declared } of committedRows()) {
      if (row.blocksDecomp) {
        const fold = foldDecomposition(row.blocksDecomp, declared);
        assert.ok(Object.values(fold).every((a) => a.n > 0), `${f}: a stored split must fold to real counts`);
      } else {
        assert.throws(() => foldDecomposition(row.blocksDecomp, declared), /NOT recomputable/, `${f}: must refuse, not answer`);
      }
    }
  });

  // --- rf2-jo60g, merged-PR audit #7666: PARTIAL evidence must refuse too ----
  //
  // The landing above closed the ABSENT case and left the partial one open. It
  // checked the outer array and nothing inside it, then summed with
  // `acc[k] += a[k] || 0` — so `foldDecomposition([[]])` and
  // `foldDecomposition([[{}]])` each answered `{}`, and an arm that had lost
  // `task`/`script`/`layoutCount`, or carried an explicit `null`, folded with
  // every missing field synthesised as zero. A half-written or truncated
  // dataset therefore became a plausible Script/Layout/RecalcStyle ratio
  // instead of the refusal this bead promises, which is the fail-open the
  // whole bead exists to close, one level down from where it was closed.
  //
  // Every fixture below is built HERE. The committed datasets cannot witness
  // these states — they either carry a whole split or none — and a negative
  // case that needs a file to be wrong on disk is not a test anyone can run.

  const { summarise, verdict } = DRIVERS[1].mod;

  // `drive`'s own composition, on the real exported functions: `report` folds
  // BEFORE anything else it does, inside the try; a throw sets `failed`, so the
  // row never reaches `results`; `verdict` reads `failed`; and the dataset write
  // is gated on `results.length && !failed`. A refusal that throws where nothing
  // catches is not the same as a driver that exits non-zero — this drives the
  // second. The source pins below hold the reconstruction to the shape it
  // stands for, so it cannot drift away from the driver while still passing.
  const driveOn = (blocksDecomp, declared = DECLARED) => {
    const results = [];
    let failed = null;
    try {
      foldDecomposition(blocksDecomp, declared); // `report`'s first act on the row
      results.push(fixtureRow()); // reached only if the fold ANSWERED
    } catch (e) {
      failed = e.message;
    }
    const v = verdict(summarise(failed, results));
    const wrote = Boolean(results.length && !failed);
    return {
      code: v.code,
      wrote,
      // WHAT THIS RUN PUBLISHED, which is the question every witness below
      // asks. `destination` reads the run's SHAPE and nothing else after
      // rf2-pzqy8, so what keeps a refused fold out of the published set is
      // not a routing decision — it is that `report` threw, `failed` was set,
      // and `drive`'s `if (results.length && !failed)` never reached a write
      // at all. Nothing is published because nothing was written.
      canonical: wrote && destination(shape()).canonical,
      why: failed || '',
    };
  };

  t("the reconstruction above IS `drive`'s composition, and stays that way", () => {
    // Each pin is one link of the chain the witnesses below assume. Newlines
    // are normalised because this tree is checked out with CRLF on Windows.
    const src = SRC.replace(/\r\n/g, '\n');
    // The second argument is pinned with the first: rf2-e1tko turns on WHERE
    // the declared shape comes from, and `{ armIds, rounds: ROUNDS, blocks:
    // BLOCKS }` is the row's plan and the run's design — never the blocks.
    assert.match(
      src,
      /function report\(out\) \{\n {2}const \{[^}]*armIds[^}]*blocksDecomp[^}]*\} = out;\n {2}const decomposition = foldDecomposition\(blocksDecomp, \{ armIds, rounds: ROUNDS, blocks: BLOCKS \}\);/
    );
    const drive = src.slice(src.indexOf('async function drive('));
    assert.match(drive, /const out = await runRow\(browser, runDef, rowId\);\n\s*const adj = report\(out\);\n\s*results\.push\(/);
    assert.match(drive, /\} catch \(e\) \{\n\s*failed = e\.message;/);
    assert.match(drive, /if \(results\.length && !failed\) \{/);
  });

  // The green case first, so nothing below is vacuously red: the valid fixture
  // folds, the row is kept, and the run writes to the canonical set.
  t('the fold ANSWERS on whole evidence — the gate is not vacuous', () => {
    const d = driveOn(FIXTURE_DECOMP);
    assert.strictEqual(d.code, 0, 'a complete decomposition must fold, not refuse');
    assert.strictEqual(d.wrote, true);
    assert.strictEqual(d.canonical, true);
  });

  t('the fold sums the stored fields and DEFAULTS nothing', () => {
    // `acc[k] += a[k] || 0` is the defect itself. Pinned in the source because
    // a rewrite of the loop could reintroduce it while every witness below
    // still described a state the new loop happened to reject some other way.
    const fold = SRC.slice(SRC.indexOf('function foldDecomposition('), SRC.indexOf('function taredCell('));
    assert.ok(fold.length > 0, 'the fold no longer has the shape this test pins');
    assert.ok(!/\|\| 0/.test(fold), 'a missing or null metric must REFUSE, not become zero');
    assert.match(fold, /for \(const k of DECOMP_FIELDS\) acc\[k\] \+= a\[k\];/);
  });

  t('the fold measures completeness against the DECLARED shape, never against block 1', () => {
    // rf2-e1tko, and the reason it is a source pin as well as a witness: every
    // state below is one the pre-repair fold ACCEPTED, and a rewrite that
    // re-derived the roster from the evidence could go on rejecting them for
    // some other reason while the anchor quietly returned. `roster`, the old
    // block-1 variable, and the message that named it are both gone.
    const fold = SRC.slice(SRC.indexOf('function foldDecomposition('), SRC.indexOf('function taredCell('));
    assert.match(fold, /function foldDecomposition\(blocksDecomp, declared\)/);
    assert.match(fold, /const expected = Array\.isArray\(shape\.armIds\) \? shape\.armIds\.slice\(\)\.sort\(\) : \[\];/);
    assert.match(fold, /blocksDecomp\.length !== shape\.rounds/, 'the row must carry the rounds it declares');
    assert.match(fold, /round\.length !== shape\.blocks/, 'each round must carry the blocks the row declares');
    assert.match(fold, /for \(const arm of expected\)/, 'the per-field checks must run over the declared roster');
    assert.ok(!/round 0, block 0 carries/.test(fold), 'block 1 is not the authority on what a whole row carries');
  });

  t('the collector, the serialiser and the fold agree on ONE field list', () => {
    // Three readers over one constant; the split was dropped in the first place
    // because a field could be collected and then not be required anywhere.
    assert.match(SRC, /^const DECOMP_FIELDS = \['n', 'task', 'taskNet', 'devtools', 'script', 'style', 'layout', 'layoutCount', 'inPage'\];$/m);
    assert.match(SRC, /const acc = \(into\[arm\] \|\|= zeroDecomp\(\)\);/, 'the collector must build the one shape');
    assert.match(SRC, /const acc = \(out\[arm\] \|\|= zeroDecomp\(\)\);/, 'the fold must build the one shape');
  });

  // JSON.stringify cannot carry NaN or undefined, so the mutations are applied
  // AFTER the clone — the states below are what a truncated write, a partial
  // in-memory row, or a corrupt read actually looks like.
  const mutate = (fn) => {
    const c = JSON.parse(JSON.stringify(FIXTURE_DECOMP));
    fn(c);
    return c;
  };

  // [what it is, the evidence, the phrase the refusal must carry, the side it
  //  must name, and the shape the row DECLARES itself to be]
  //
  // The declared shape defaults to the fixture's own. The four degenerate
  // arrays below are single-round, single-block by construction, so each is
  // measured against a shape that matches its dimensions — otherwise the row
  // count would refuse them first and the structural fault they stand for would
  // never be reached.
  const ONE = { armIds: DECLARED.armIds, rounds: 1, blocks: 1 };
  const PARTIAL = [
    ['an outer array whose round has no blocks', [[]], 'carries no blocks', 'round 0', ONE],
    ['a round whose block has no arms', [[{}]], 'carries no arms', 'round 0, block 0', ONE],
    ['a round that is not an array at all', [null], 'carries no blocks', 'round 0', ONE],
    ['a block that is not a block of arms', [[[]]], 'is not a block of arms', 'round 0, block 0', ONE],
    ['a block that LOST an arm the other blocks carry', mutate((c) => delete c[1][0]['ctl-2x']), 'missing ctl-2x', 'round 1, block 0'],
    ['a block that grew an arm the row never planned', mutate((c) => (c[1][1].bogus = DECOMP_BLOCK(10, 1, 1, 1))), 'unexpected bogus', 'round 1, block 1'],
    ['an arm with no accumulator at all', mutate((c) => (c[0][0].floor = null)), 'carries no accumulator (null)', 'arm "floor"'],
    ['an arm MISSING a metric', mutate((c) => delete c[0][1]['ctl-2x'].script), 'field "script" is absent', 'round 0, block 1, arm "ctl-2x"'],
    ['an arm whose metric is explicitly null', mutate((c) => (c[0][0].floor.layout = null)), 'field "layout" is null', 'round 0, block 0, arm "floor"'],
    ['an arm whose metric is NaN', mutate((c) => (c[1][1]['ctl-2x'].style = NaN)), 'field "style" is NaN', 'round 1, block 1, arm "ctl-2x"'],
    ['an arm whose metric is a string', mutate((c) => (c[0][0]['ctl-2x'].task = '20')), 'field "task" is a string', 'arm "ctl-2x"'],
    ['a block that took no samples', mutate((c) => (c[1][0].floor.n = 0)), 'field "n" is 0', 'round 1, block 0, arm "floor"'],
    ['a negative renderer count', mutate((c) => (c[0][1].floor.layoutCount = -1)), 'field "layoutCount" is -1', 'round 0, block 1, arm "floor"'],
  ];

  // --- rf2-e1tko, merged-PR audit #7681: TRUNCATED evidence must refuse too ---
  //
  // The three shapes the audit drove through the landed fold and watched it
  // ACCEPT — each returning internally consistent survivors and a perfectly
  // plausible aggregate, which is the whole danger. They passed because
  // completeness was measured against the first surviving block, and block 1
  // cannot report a round dropped after it, a sibling block that was never
  // stored, or an arm it has lost itself. The third is the sharp one: uniform
  // loss walks straight past a block-to-block roster check, so the check written
  // to catch a lost arm was blind to an arm lost everywhere. The row's declared
  // shape sees all three, which is why it is now the anchor.
  const TRUNCATED = [
    ['a row whose FINAL ROUND was dropped', mutate((c) => c.pop()), 'carries 1 round where the row declares 2', "the row's shape"],
    ['a round that LOST a block', mutate((c) => c[1].splice(0, 1)), 'carries 1 block where the row declares 2', 'round 1'],
    [
      'an arm removed from EVERY block, which block-to-block agreement cannot see',
      mutate((c) => {
        for (const rd of c) for (const b of rd) delete b['ctl-2x'];
      }),
      'missing ctl-2x',
      'round 0, block 0',
    ],
  ];

  for (const [what, blocks, needle, side, declared = DECLARED] of PARTIAL.concat(TRUNCATED)) {
    t(`${what} is refused, and the refusal names it`, () => {
      assert.throws(() => foldDecomposition(blocks, declared), /not valid evidence/, 'this state must not fold');
      const d = driveOn(blocks, declared);
      assert.notStrictEqual(d.code, 0, 'a refused row must reach a NON-ZERO driver outcome');
      assert.strictEqual(d.wrote, false, 'a refused row must not be written at all');
      assert.strictEqual(d.canonical, false, 'and the destination must not be the published set');
      // Naming the offending side is the diagnostic that earns its place: it
      // fires only on evidence that is already invalid, and it tells the reader
      // which side of the ratio to go and look at.
      assert.ok(d.why.includes(needle), `the refusal must say "${needle}" — it said: ${d.why}`);
      assert.ok(d.why.includes(side), `the refusal must name ${side} — it said: ${d.why}`);
    });
  }

  t('a partial arm can no longer be synthesised into a plausible ratio', () => {
    // The audit's own probe, verbatim: an arm lacking task/taskNet/devtools/
    // script/layoutCount/inPage used to fold with all six read as zero, and an
    // explicit `null` script used to fold as `script: 0`. Both would have made
    // the studio page's cited split reproducible from evidence that never held
    // it — a wrong number with a fold's authority behind it.
    const stripped = mutate((c) => {
      for (const k of ['task', 'taskNet', 'devtools', 'script', 'layoutCount', 'inPage']) delete c[0][0]['ctl-2x'][k];
    });
    assert.throws(() => foldDecomposition(stripped, DECLARED), /not valid evidence/);
    const nulled = mutate((c) => (c[0][0]['ctl-2x'].script = null));
    assert.throws(() => foldDecomposition(nulled, DECLARED), /field "script" is null/);
    assert.strictEqual(driveOn(nulled).code, 1, 'the run must exit non-zero, not publish a script ratio of 0');
  });

  t('a fold offered no declared shape refuses rather than anchoring to the evidence', () => {
    // The fence around rf2-e1tko. A shape argument that could be omitted, or
    // quietly filled in from block 1, would be the defect with an extra
    // parameter — so the whole evidence, folded with nothing to measure it
    // against, must refuse exactly as a truncated row does.
    for (const bad of [undefined, {}, { armIds: [], rounds: 2, blocks: 2 }, { armIds: DECLARED.armIds, rounds: 0, blocks: 2 }]) {
      assert.throws(() => foldDecomposition(FIXTURE_DECOMP, bad), /DECLARED shape/, `${JSON.stringify(bad)} must not fold`);
    }
    // `null` rather than `undefined`, which `driveOn`'s own default would fill.
    const d = driveOn(FIXTURE_DECOMP, null);
    assert.notStrictEqual(d.code, 0);
    assert.strictEqual(d.wrote, false);
    assert.strictEqual(d.canonical, false);
  });

  t("a stored split must match the row's own declared shape, dimensions included", () => {
    // The counterweight to the tightening above: a rule that refuses a dropped
    // round, a lost block and a uniformly-missing arm must still accept the real
    // thing unchanged, or it is over-tight rather than fail-closed. `armIds` and
    // `design` are serialised independently of `blocksDecomp`, so this measures
    // the file against its own header rather than restating it.
    let checked = 0;
    for (const { f, row, declared } of committedRows()) {
      if (!row.blocksDecomp) continue;
      const where = `${f} / ${row.rowId}`;
      assert.strictEqual(row.blocksDecomp.length, declared.rounds, `${where}: stored rounds must be the declared depth`);
      for (const rd of row.blocksDecomp) assert.strictEqual(rd.length, declared.blocks, `${where}: stored blocks must be the declared width`);
      assert.deepStrictEqual(
        Object.keys(foldDecomposition(row.blocksDecomp, declared)).sort(),
        declared.armIds.slice().sort(),
        `${where}: the stored split covers a different arm set than the row claims`
      );
      checked += 1;
    }
    assert.ok(checked >= 3, 'expected the committed rows that carry the split');
  });
}

// --- rf2-y0pkh: the census run-rejection rule's false-refusal rate, MEASURED --
//
// rf2-8a746 retired the all-blocks strict rule on the HICASSO CLOCK, and the
// `^18` grep that ruling mandated also landed here — on a different
// instrument, with a different driver, its own datasets, and a control whose
// prediction is the row's own element arithmetic rather than a page doubling.
// The ruling rejects retiring a control merely because it shares a shape with
// one that failed, so the rule was MEASURED here before being decided on.
// `1 - p^n` is a property of the rule and not of the clock, so only `p`
// decides it — and this rig's `p` is not that rig's. The rule is RETAINED.
//
// Everything below recomputes from the committed datasets through the
// driver's OWN `controlBlocks` and `controlVerdict`. That `controlVerdict` is
// exported here where the clock's deliberately is not, and the asymmetry is
// the point: on the clock it was retired and now decides nothing, so a test
// able to reach it would be a reader able to reach it; here its `ok` is still
// the decision, reaching `summarise` -> `verdict` -> exit 5. A rule a test
// cannot drive is not a checked rule however exactly it is quoted.

{
  const { controlBlocks, controlVerdict } = DRIVERS[1].mod;
  const CSRC = fs.readFileSync(DRIVERS[1].file, 'utf8');
  const t = (what, fn) => test(`census run-rejection rate: ${what}`, fn);

  // The corpus the rates are stated over, and the rates themselves. Stated as
  // literals so that a new dataset, or an arithmetic change, reds this file
  // rather than silently ageing the driver's comment into a false claim.
  const CORPUS = { datasets: 5, rowRuns: 30, blocks: 540, n: 18, slack: 0.25 };
  const RATES = {
    'large-template': { rowRuns: 10, blocks: 180, inBand: 178, passed: 8, falseRefusalPct: '18.2' },
    feed: { rowRuns: 10, blocks: 180, inBand: 175, passed: 7, falseRefusalPct: '39.8' },
    ordinary: { rowRuns: 10, blocks: 180, inBand: 56, passed: 0, falseRefusalPct: '100.0' },
  };

  const med = (xs) => {
    const v = [...xs].sort((a, b) => a - b);
    return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
  };
  const choose = (n, k) => {
    let x = 1;
    for (let i = 0; i < k; i++) x = (x * (n - i)) / (i + 1);
    return x;
  };
  const binom = (n, k, p) => choose(n, k) * p ** k * (1 - p) ** (n - k);
  /** Exact two-sided p-value for k of n at probability p — no normal approximation at n=10. */
  const exactTwoSided = (n, k, p) => {
    const ceiling = binom(n, k, p) * (1 + 1e-7);
    let s = 0;
    for (let i = 0; i <= n; i++) if (binom(n, i, p) <= ceiling) s += binom(n, i, p);
    return s;
  };

  /** Every committed census row-run, its statistic recomputed by the driver's own arithmetic. */
  const corpus = () => {
    const dir = path.join(__dirname, 'data');
    return fs
      .readdirSync(dir)
      .filter((d) => d.startsWith('censusclock-'))
      .sort()
      .flatMap((d) =>
        fs
          .readdirSync(path.join(dir, d))
          .filter((f) => f.endsWith('.json'))
          .sort()
          .flatMap((f) => {
            const data = JSON.parse(fs.readFileSync(path.join(dir, d, f), 'utf8'));
            return data.rows.map((r) => {
              const per = controlBlocks(r.blocksTask);
              return {
                where: `${d}/${f} ${r.rowId}`,
                rowId: r.rowId,
                slack: data.design.controlSlack,
                stored: r.adjudication.ctl,
                // The band the run ACTUALLY adjudicated on, read back rather
                // than re-derived: the run held the page's raw `ctlPredicted`
                // and the dataset stores `r4` of it, so a re-derived edge can
                // differ by one unit in the last stored place. Counting on the
                // stored band measures the rate the runs experienced.
                band: r.adjudication.ctl.band,
                per,
                verdict: controlVerdict(r.ctlPredicted, per, data.design.controlSlack),
              };
            });
          })
      );
  };

  t('the corpus the rates are stated over is the corpus on disk', () => {
    const all = corpus();
    const datasets = new Set(all.map((r) => r.where.split('/')[0]));
    assert.strictEqual(datasets.size, CORPUS.datasets, 'a new censusclock-* dataset means the stated rates need recounting');
    assert.strictEqual(all.length, CORPUS.rowRuns);
    for (const r of all) {
      assert.strictEqual(r.per.length, CORPUS.n, `${r.where}: n is the design's 6 rounds x 3 blocks`);
      assert.strictEqual(r.slack, CORPUS.slack, `${r.where}: the tolerance band must be the one the rates were measured at`);
    }
    assert.strictEqual(
      all.reduce((a, r) => a + r.per.length, 0),
      CORPUS.blocks
    );
    // A per-row rate pools blocks across row-runs, which only means something
    // if every row-run of that row was judged against the same band.
    for (const rowId of Object.keys(RATES)) {
      const bands = new Set(all.filter((r) => r.rowId === rowId).map((r) => r.band.join(':')));
      assert.strictEqual(bands.size, 1, `${rowId}: its row-runs must share one band — ${[...bands].join(' / ')}`);
    }
  });

  t('the live arithmetic and the stored verdicts are the same quantity', () => {
    // Without this the rate would be a rate about the datasets rather than
    // about the rule: a statistic recomputed differently from the one the
    // driver adjudicated on would measure the recomputation.
    for (const r of corpus()) {
      assert.deepStrictEqual(r.verdict.perBlock, r.stored.perBlock, `${r.where}: recomputed blocks must match the stored ones`);
      assert.strictEqual(r.verdict.ok, r.stored.ok, `${r.where}: same strict verdict`);
      // The one place the two can legitimately differ, and by exactly one unit
      // in the last stored place: `datasetFor` writes `r4(ctlPredicted)` while
      // the run adjudicated on the page's raw value. Bounded rather than
      // deep-equalled, because a wider drift would mean the band moved.
      for (const i of [0, 1]) {
        assert.ok(
          Math.abs(r.verdict.band[i] - r.stored.band[i]) <= 1e-4 + Number.EPSILON,
          `${r.where}: band edge ${i} drifted beyond the stored grain (${r.verdict.band[i]} vs ${r.stored.band[i]})`
        );
      }
      assert.strictEqual(
        r.per.every((x) => x >= r.band[0] && x <= r.band[1]),
        r.stored.ok,
        `${r.where}: the stored band and the stored verdict must agree`
      );
    }
  });

  t('the measured rate, per row — and it is survivable where the control meets its premise', () => {
    const all = corpus();
    for (const [rowId, want] of Object.entries(RATES)) {
      const rows = all.filter((r) => r.rowId === rowId);
      const blocks = rows.flatMap((r) => r.per);
      const [lo, hi] = rows[0].band;
      const inBand = blocks.filter((x) => x >= lo && x <= hi).length;
      const passed = rows.filter((r) => r.verdict.ok).length;
      assert.strictEqual(rows.length, want.rowRuns, `${rowId}: row-runs`);
      assert.strictEqual(blocks.length, want.blocks, `${rowId}: blocks`);
      assert.strictEqual(inBand, want.inBand, `${rowId}: per-block in band`);
      assert.strictEqual(passed, want.passed, `${rowId}: runs the strict rule passed`);
      const p = inBand / blocks.length;
      assert.strictEqual(((1 - p ** CORPUS.n) * 100).toFixed(1), want.falseRefusalPct, `${rowId}: 1 - p^${CORPUS.n}`);
    }
    // The claim that decides the bead: on the two rows carrying the gated pair
    // the rule costs 18-40% of runs, against the 90.5% empirical per-run false
    // refusal that retired the clock's. A rate a run survives is not a defect.
    assert.ok(Number(RATES['large-template'].falseRefusalPct) < 90.5);
    assert.ok(Number(RATES.feed.falseRefusalPct) < 90.5);
  });

  t('the arithmetic and the empirical rate AGREE, so p^n is the right model here', () => {
    // The bead asks whether `1 - p^n` predicts what the corpus actually did.
    // Per row it does; POOLED it does not, and that disagreement is a fact
    // about pooling a mis-centred row with two well-centred ones, not about
    // the rule. Both halves are pinned so neither can be quoted alone.
    const all = corpus();
    for (const rowId of Object.keys(RATES)) {
      const rows = all.filter((r) => r.rowId === rowId);
      const blocks = rows.flatMap((r) => r.per);
      const [lo, hi] = rows[0].band;
      const p = blocks.filter((x) => x >= lo && x <= hi).length / blocks.length;
      const pv = exactTwoSided(rows.length, rows.filter((r) => r.verdict.ok).length, p ** CORPUS.n);
      assert.ok(pv > 0.05, `${rowId}: observed pass count is not what p^${CORPUS.n} predicts (exact two-sided p = ${pv.toFixed(3)})`);
    }
    const pooled = all.flatMap((r) => r.per.map((x) => ({ x, band: r.band })));
    const p = pooled.filter((o) => o.x >= o.band[0] && o.x <= o.band[1]).length / pooled.length;
    assert.strictEqual((p * 100).toFixed(1), '75.7', 'the pooled in-band fraction');
    assert.strictEqual(((1 - p ** CORPUS.n) * 100).toFixed(1), '99.3', 'which predicts near-total refusal');
    assert.strictEqual(all.filter((r) => r.verdict.ok).length, 15, 'against 15 of 30 observed — pooling is the wrong statistic, not the rule');
  });

  t('the ordinary row refuses on its CENTRE, and no relaxation of this rule reaches it', () => {
    // The load-bearing assertion. `ordinary` is 0 of 10, and a reader who saw
    // only that number would blame the rule the way rf2-8a746's clock evidence
    // invites. It is the centre: the block median sits BELOW the band's own
    // lower edge, so every run would refuse under any per-block count.
    const rows = corpus().filter((r) => r.rowId === 'ordinary');
    const [lo, hi] = rows[0].band;
    const centre = med(rows.flatMap((r) => r.per));
    assert.ok(centre < lo, `the ordinary centre ${centre.toFixed(4)}x must sit below the band's lower edge ${lo}`);
    for (const k of [0, 1, 2, 3]) {
      const passed = rows.filter((r) => r.per.filter((x) => x < lo || x > hi).length <= k).length;
      assert.strictEqual(passed, 0, `allowing ${k} out-of-band blocks must still pass 0 of 10 — the rule is not the binding constraint`);
    }
    // And the two rows whose centre IS inside the band recover immediately, so
    // the rule is doing real work exactly where it is not the constraint.
    for (const rowId of ['large-template', 'feed']) {
      const rs = corpus().filter((r) => r.rowId === rowId);
      const [l, h] = rs[0].band;
      assert.ok(med(rs.flatMap((r) => r.per)) > l, `${rowId}: its centre is inside the band`);
      assert.strictEqual(rs.filter((r) => r.per.filter((x) => x < l || x > h).length <= 2).length, 10, `${rowId}: recovers within two blocks`);
    }
  });

  t('the retained rule states its measured rates where the grep lands', () => {
    // rf2-8a746's criterion 4 in the form this instrument needs it: the label
    // a `^18`-semantics grep hits must say the rule was measured and kept, or
    // the next grep re-files rf2-y0pkh. That is how this bead came to exist.
    assert.match(CSRC, /rule: 'strict — EVERY block inside the band \(rf2-y0pkh: measured and retained\)'/);
    for (const [rowId, want] of Object.entries(RATES)) {
      assert.ok(CSRC.includes(`${want.inBand}/${want.blocks}`), `the comment states ${rowId}'s per-block count`);
    }
    assert.match(CSRC, /90\.5%/, "and the clock's retired rate it is measured against");
    assert.match(CSRC, /rf2-pzqy8/, "the ordinary row's centre is handed on by bead id, not left as a shape");
    // The rates are rates AT A SLACK. Widening the band would change every one
    // of them, so the constant they were measured at is pinned beside them —
    // otherwise the limits could move and the stated rates would quietly lie.
    assert.match(CSRC, /const CONTROL_SLACK = 0\.25;/, 'the tolerance the rates were measured at');
  });
}

// --- rf2-pzqy8, half one: the ordinary row's CENTRE, re-specified empirically -
//
// rf2-y0pkh measured the census rig's run-rejection rule and RETAINED it, and
// found the one thing the rule is not: `ordinary` refused 10 of 10 on its
// CENTRE. Its block median is 1.2308x against an element-arithmetic prediction
// of 1.7255x — 71.3% of P, and 5.2% below the strict band's own lower edge —
// so no relaxation of the block count reaches it. That is the class rf2-8a746
// diagnosed on ctl3, whose true centre sat 2.6% ABOVE its refusal edge where
// this sits below, and its ruling's part 3 is the repair: a level-denominated,
// EMPIRICALLY CALIBRATED, versioned check standard.
//
// EVERYTHING BELOW RECOMPUTES FROM THE COMMITTED DATASETS through the driver's
// own `controlBlocks`, `checkStandardVerdict` and `controlAdjudication`. The
// frozen limits are read out of `shapes/census_check_standard.json` and never
// written here, so a recalibration moves the file and these witnesses follow
// it; what they pin is that the numbers in the file are the numbers its own
// `derivation` strings claim, taken over the corpus the file names.
//
// NO MEASUREMENT IS TAKEN. No census driver, no window.

{
  const CENSUS = DRIVERS[1].mod;
  const { controlBlocks, controlAdjudication, checkStandardVerdict, CHECK_STANDARD: STD, robustScale } = CENSUS;
  const CSRC = fs.readFileSync(DRIVERS[1].file, 'utf8');
  const t = (what, fn) => test(`census check standard: ${what}`, fn);

  const ORD = STD.rows.ordinary;
  const p50 = (xs) => {
    const v = [...xs].sort((a, b) => a - b);
    return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
  };
  const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
  const sd = (xs) => Math.sqrt(xs.reduce((a, b) => a + (b - mean(xs)) ** 2, 0) / (xs.length - 1));
  const r4 = (x) => Math.round(x * 10000) / 10000;

  /** Every committed census row-run, adjudicated by the driver's real functions. */
  const corpus = () => {
    const dir = path.join(__dirname, 'data');
    return fs
      .readdirSync(dir)
      .filter((d) => d.startsWith('censusclock-'))
      .sort()
      .flatMap((d) =>
        fs
          .readdirSync(path.join(dir, d))
          .filter((f) => f.endsWith('.json'))
          .sort()
          .flatMap((f) => {
            const data = JSON.parse(fs.readFileSync(path.join(dir, d, f), 'utf8'));
            return data.rows.map((r) => {
              const per = controlBlocks(r.blocksTask);
              return {
                set: d,
                where: `${d}/${f}`,
                rowId: r.rowId,
                per,
                adj: controlAdjudication(r.rowId, r.ctlPredicted, per, data.design.controlSlack),
                stored: r.adjudication.ctl,
              };
            });
          })
      );
  };
  const ordinaries = () => corpus().filter((r) => r.rowId === 'ordinary');
  const setsOf = (which) => (ORD.provenance[which].datasets || []).map((d) => path.basename(d));

  // --- the standard is complete over the roster, and says which rows it holds -

  t('every row this driver takes is either calibrated or declared OUT, by name', () => {
    const roster = CSRC.match(/const ALL_ROWS = \[([^\]]*)\]/)[1].split(',').map((s) => s.trim().replace(/'/g, ''));
    assert.deepStrictEqual(roster, ['large-template', 'feed', 'ordinary'], "the driver's roster is not what this test assumes");
    for (const rowId of roster) {
      const held = Object.keys(STD.rows).includes(rowId);
      const out = STD.notInThisStandard.rows.includes(rowId);
      assert.ok(held !== out, `${rowId} must be in exactly one of \`rows\` and \`notInThisStandard\``);
    }
  });

  t('a row the standard has never heard of THROWS rather than being waved past', () => {
    // Fail closed at the seat that decides WHETHER a row has a standard. A row
    // silently returning `null` here would be adjudicated by the strict rule
    // on nobody's decision, which is how a roster and a standard drift apart.
    assert.throws(
      () => checkStandardVerdict('a-row-nobody-calibrated', [1.2, 1.25]),
      /is in neither `rows` nor `notInThisStandard`/
    );
    assert.throws(() => checkStandardVerdict('a-row-nobody-calibrated', [1.2]), /rf2-pzqy8/);
  });

  t('the two sibling rows are declared out and get NO standard, by name', () => {
    for (const rowId of ['large-template', 'feed']) {
      assert.strictEqual(checkStandardVerdict(rowId, [1.9, 2.0]), null, `${rowId} must have no standard`);
    }
    assert.match(STD.notInThisStandard.why, /MEETS its own element-arithmetic prediction/);
    assert.match(STD.notInThisStandard.adjudicatedBy, /controlVerdict/);
  });

  // --- the frozen numbers are the numbers their own derivations claim --------

  t('the CENTRE recomputes from the baseline the file names, and is not a literal in the code', () => {
    // The load-bearing check on half one. The file says the centre is the
    // median of its baseline row-runs' block medians; this recomputes exactly
    // that, from the datasets the file names, through the driver's own
    // `controlBlocks`. A centre typed into the JSON by hand would red here.
    const base = ordinaries().filter((r) => setsOf('baseline').includes(r.set));
    assert.strictEqual(base.length, ORD.provenance.baseline.rowRuns, 'the baseline is the row-runs the file declares');
    assert.strictEqual(
      base.reduce((a, r) => a + r.per.length, 0),
      ORD.provenance.baseline.blocks
    );
    const meds = base.map((r) => p50(r.per));
    assert.strictEqual(r4(p50(meds)), ORD.centre, 'the frozen centre is the baseline run medians\' median');
    assert.strictEqual(r4(p50(meds)), ORD.provenance.baseline.observed.runMedianCentre);
    assert.strictEqual(r4(mean(meds)), ORD.provenance.baseline.observed.runMedianMean);
    assert.deepStrictEqual([r4(Math.min(...meds)), r4(Math.max(...meds))], ORD.provenance.baseline.observed.runMedianRange);
    assert.strictEqual(r4(sd(meds)), ORD.provenance.baseline.observed.betweenRunSD);
  });

  t('NO LIMIT IS SPELLED IN THE DRIVER — recalibrating edits the JSON, never the code', () => {
    // The prose above `checkStandardVerdict` quotes the centre, because a
    // reader landing on the control needs the finding; the CODE must not,
    // because a limit in two places is a limit that can be moved in one.
    const CODE = CSRC.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
    assert.ok(CODE.includes('const spec = CHECK_STANDARD.rows[rowId];'), 'the comment stripper removed the code as well');
    for (const [what, n] of [
      ['the centre', ORD.centre],
      ['a location limit', ORD.location.limits[0]],
      ['a location limit', ORD.location.limits[1]],
      ['the dispersion limit', ORD.dispersion.limit],
    ]) {
      assert.ok(!CODE.includes(String(n)), `${what} (${n}) is a literal in the driver's code`);
    }
    for (const read of ['spec.location.limits', 'spec.dispersion.limit', 'spec.centre', 'spec.tolerance.band']) {
      assert.ok(CODE.includes(read), `the driver must read ${read} from the file`);
    }
  });

  t('the LOCATION limits are centre +/- 3 x the between-run SD the file states', () => {
    // Derived from the PUBLISHED figures rather than from full precision, in
    // clock_check_standard.json's idiom — its own 1.7207 +/- 3 x 0.0566
    // reproduces [1.5509, 1.8905] exactly — so a reader with the file alone
    // can check the arithmetic without re-running the corpus.
    const s = ORD.provenance.baseline.observed.betweenRunSD;
    assert.match(ORD.location.derivation, /centre \+\/- 3 x between-run SD 0\.0632/);
    assert.deepStrictEqual(ORD.location.limits, [r4(ORD.centre - 3 * s), r4(ORD.centre + 3 * s)]);
    assert.ok(ORD.location.limits[0] < ORD.centre && ORD.centre < ORD.location.limits[1], 'the limits must bracket the centre');
  });

  t('the DISPERSION limit is the lognormal upper 3 sigma of the baseline robust scales', () => {
    const base = ordinaries().filter((r) => setsOf('baseline').includes(r.set));
    const scales = base.map((r) => robustScale(r.per));
    const logs = scales.map(Math.log);
    const mu = mean(logs);
    const sigma = sd(logs);
    assert.strictEqual(Number(mu.toFixed(4)), -2.104, 'the stated mu');
    assert.strictEqual(Number(sigma.toFixed(4)), 0.3439, 'the stated sigma');
    assert.strictEqual(r4(Math.exp(mu + 3 * sigma)), ORD.dispersion.limit);
    assert.strictEqual(r4(p50(scales)), ORD.provenance.baseline.observed.robustScaleP50);
    assert.strictEqual(r4(Math.max(...scales)), ORD.provenance.baseline.observed.robustScaleMax);
    // ABOVE the widest observed draw rather than at it — a limit sitting on
    // its own worst baseline draw refuses the next one by construction.
    assert.ok(ORD.dispersion.limit > ORD.provenance.baseline.observed.robustScaleMax);
    assert.match(ORD.dispersion.derivation, /44% above the widest of the 8 observed draws/);
    assert.strictEqual(Math.round((ORD.dispersion.limit / ORD.provenance.baseline.observed.robustScaleMax - 1) * 100), 44);
  });

  // --- INDEPENDENCE, which is what makes it a check standard ----------------

  t('the baseline and the HOLD-OUT are disjoint, and together they are the corpus', () => {
    // rf2-8a746's v1 said in its own `independence` field that its limits were
    // seeded from the 42 runs it was quoted against, so 0-of-42 was a
    // consistency check and not a false-refusal measurement. This corpus is
    // five sessions at five commits and therefore SPLITS, which is the one
    // thing v1 could not do — NIST e-Handbook 2.3.5's doctrine kept rather
    // than merely cited.
    const base = setsOf('baseline');
    const hold = setsOf('holdOut');
    assert.strictEqual(base.length, 4);
    assert.strictEqual(hold.length, 1);
    for (const d of hold) assert.ok(!base.includes(d), `${d} may not be in both`);
    const all = [...new Set(ordinaries().map((r) => r.set))].sort();
    assert.deepStrictEqual(all, [...base, ...hold].sort(), 'a new censusclock-* dataset means the standard needs recalibrating');
  });

  t('the HOLD-OUT row-runs are IN CONTROL against limits derived WITHOUT them', () => {
    // The false-refusal measurement, small but real: two row-runs taken five
    // days after the last baseline session, at a different commit, judged by
    // limits that never saw them.
    const hold = ordinaries().filter((r) => setsOf('holdOut').includes(r.set));
    assert.strictEqual(hold.length, ORD.provenance.holdOut.rowRuns);
    assert.deepStrictEqual(hold.map((r) => r4(p50(r.per))), ORD.provenance.holdOut.observed.runMedians);
    assert.deepStrictEqual(hold.map((r) => r4(robustScale(r.per))), ORD.provenance.holdOut.observed.robustScales);
    for (const r of hold) {
      assert.strictEqual(r.adj.standard.ok, true, `${r.where}: the hold-out must be in control`);
      assert.strictEqual(r.adj.standard.location.ok, true);
      assert.strictEqual(r.adj.standard.dispersion.ok, true);
    }
    assert.match(ORD.provenance.independence, /INDEPENDENT OF THE RUNS THEY ARE FIRST JUDGED ON/);
    assert.match(ORD.errorRates.runRejectionHoldOut, /0 of 2 HOLD-OUT row-runs refused/);
  });

  // --- what the repair actually changed, and what it deliberately did not ----

  t('the CENTRE was the defect: the same blocks refuse against 1.7255 and hold against 1.2308', () => {
    const rows = ordinaries();
    assert.strictEqual(rows.length, 10);
    // The strict rule's OWN answer about the element arithmetic is untouched —
    // still 0 of 10 — so nothing here widened a band to make a row pass.
    assert.strictEqual(rows.filter((r) => r.adj.strictOk).length, 0, 'the strict rule about 1.7255 still refuses every row-run');
    for (const r of rows) assert.strictEqual(r.adj.strictOk, r.stored.ok, `${r.where}: the strict answer must be what the run recorded`);
    // And the calibrated standard holds every one of them.
    assert.strictEqual(rows.filter((r) => r.adj.ok).length, 10, 'the empirical centre holds all ten committed row-runs');
    assert.strictEqual(ORD.prediction, 1.7255);
    assert.ok(ORD.centre < ORD.prediction, 'the empirical centre is BELOW the prediction — that is the whole finding');
    assert.match(ORD.errorRates.retiredCentreForComparison, /56 of 180 blocks \(31\.1%\) inside/);
    assert.match(ORD.errorRates.retiredCentreForComparison, /passed 0 of 10 row-runs/);
  });

  t('and the ADJUDICATOR is named on the row, so nobody has to infer which rule decided', () => {
    for (const r of corpus()) {
      if (r.rowId === 'ordinary') {
        assert.ok(r.adj.standard, 'ordinary must carry a standard verdict');
        assert.strictEqual(r.adj.ok, r.adj.standard.ok, 'a calibrated row is adjudicated by its standard');
        assert.match(r.adj.adjudicator, /calibrated check standard `census-clock\/ctl-2x-level` v1 \(rf2-pzqy8\)/);
      } else {
        assert.strictEqual(r.adj.standard, null, `${r.rowId} must carry no standard`);
        assert.strictEqual(r.adj.ok, r.adj.strictOk, 'a row with no standard is adjudicated by the strict rule');
        assert.match(r.adj.adjudicator, /strict all-blocks rule/);
      }
    }
  });

  t('THE SIBLING ROWS ARE UNTOUCHED — rf2-y0pkh measured and retained the rule where it works', () => {
    // The fence, asserted rather than promised. Their pass counts are the ones
    // rf2-y0pkh measured three hours before this bead, and the rule that
    // produced them is the same function with the same wording.
    for (const [rowId, passed] of [['large-template', 8], ['feed', 7]]) {
      const rows = corpus().filter((r) => r.rowId === rowId);
      assert.strictEqual(rows.length, 10);
      assert.strictEqual(rows.filter((r) => r.adj.ok).length, passed, `${rowId}: still ${passed} of 10`);
      assert.strictEqual(rows.filter((r) => r.adj.strictOk).length, passed, `${rowId}: and the strict rule is what decided it`);
      for (const r of rows) assert.strictEqual(r.adj.ok, r.stored.ok, `${r.where}: unchanged from what the run recorded`);
    }
    assert.match(CSRC, /rule: 'strict — EVERY block inside the band \(rf2-y0pkh: measured and retained\)'/);
  });

  t('the tolerance band is REPORTED on a calibrated row, and the all-blocks rule is why', () => {
    // The other half of the diagnosis, and the reason re-centring ALONE is not
    // the repair: about the empirical centre 90.0% of blocks are in band and
    // `0.90^18 = 15%`, so the all-blocks rule would still pass 3 of 10. That is
    // rf2-8a746's `p^n` arithmetic on this rig, at this n.
    const rows = ordinaries();
    const blocks = rows.flatMap((r) => r.per);
    const [lo, hi] = ORD.tolerance.band;
    assert.deepStrictEqual(ORD.tolerance.band, [r4(ORD.centre * (1 - ORD.tolerance.slack)), r4(ORD.centre * (1 + ORD.tolerance.slack))]);
    const inBand = blocks.filter((x) => x >= lo && x <= hi).length;
    assert.strictEqual(blocks.length, 180);
    assert.strictEqual(inBand, 162, '90.0% of blocks in band about the empirical centre');
    assert.strictEqual(((inBand / blocks.length) * 100).toFixed(1), '90.0');
    assert.strictEqual(rows.filter((r) => r.per.every((x) => x >= lo && x <= hi)).length, 3, 'the all-blocks rule would pass 3 of 10');
    assert.strictEqual((((inBand / blocks.length) ** 18) * 100).toFixed(0), '15', '0.90^18');
    // ... and nothing reads it into the verdict.
    for (const r of rows) {
      assert.strictEqual(r.adj.standard.tolerance.gating, false);
      assert.strictEqual(r.adj.standard.tolerance.of, 18);
    }
    assert.match(STD.whyNotTheAllBlocksRule, /0\.90\^18 = 15%/);
    assert.match(STD.runRejection, /Nothing per-block rejects it/);
  });

  // --- what this standard can and cannot catch, stated as arithmetic ---------

  t('the SENSITIVITY the file states is the sensitivity the frozen limits have', () => {
    // A standard nobody has seen refuse is a standard of unmeasured
    // sensitivity. This one refuses an arm that does not double at all and an
    // arm that triples — and ADMITS the 140-of-300 sabotage rf2-8a746 proved
    // its own standard against, because only 31.8% of this row's reading is
    // page-proportional. That is prediction P4 restated as a number, and the
    // file says so rather than leaving it to be discovered.
    const k = 0.3181; // the page-proportional share, from the model in the file
    const c = 0.6819;
    assert.match(ORD.sensitivity.model, new RegExp(`R = ${k} \\* P_eff \\+ ${c}`));
    assert.strictEqual(r4(k + c), 1, 'the two shares are one reading');
    const reads = (pEff) => r4(k * pEff + c);
    const admits = (R) => R >= ORD.location.limits[0] && R <= ORD.location.limits[1];
    const pEffFor = (R) => r4((R - c) / k);
    assert.match(
      ORD.sensitivity.admits,
      new RegExp(`P_eff in \\[${pEffFor(ORD.location.limits[0])}, ${pEffFor(ORD.location.limits[1])}\\]`)
    );
    // the declared doubling reads the frozen centre — the model and the
    // corpus are the same instrument, which is what makes the rest of this
    // arithmetic a statement about the row rather than about an analogy
    assert.strictEqual(reads(ORD.prediction), ORD.centre, 'a declared doubling must read the frozen centre');
    // CATCHES
    assert.strictEqual(reads(1), 1, 'an arm that does not double reads 1.0000x');
    assert.ok(!admits(reads(1)), 'and is REFUSED');
    assert.strictEqual(reads(1 + 2 * (ORD.prediction - 1)), 1.4616);
    assert.ok(!admits(reads(1 + 2 * (ORD.prediction - 1))), 'a TRIPLING is REFUSED');
    assert.match(ORD.sensitivity.catches, /reads 1\.0000x and is REFUSED/);
    assert.match(ORD.sensitivity.catches, /reads 1\.4616x and is REFUSED/);
    // MISSES, and says so
    const sabotage = reads(1 + (ORD.prediction - 1) * (140 / 300));
    assert.strictEqual(sabotage, 1.1077);
    assert.ok(admits(sabotage), 'the 140-of-300 sabotage is ADMITTED on this row');
    assert.match(ORD.sensitivity.misses, /would read 1\.1077x on THIS row and be ADMITTED/);
    assert.match(ORD.sensitivity.misses, /prediction P4 restated as a number/);
  });

  // --- fail closed at every seat --------------------------------------------

  t('an empty, a partial, or a non-finite block set REFUSES rather than passing', () => {
    assert.strictEqual(checkStandardVerdict('ordinary', []).ok, false);
    assert.match(checkStandardVerdict('ordinary', []).why, /empty block set/);
    assert.strictEqual(checkStandardVerdict('ordinary', undefined).ok, false);
    assert.strictEqual(checkStandardVerdict('ordinary', null).ok, false);
    const withNaN = [...Array.from({ length: 17 }, () => ORD.centre), NaN];
    assert.strictEqual(checkStandardVerdict('ordinary', withNaN).ok, false);
    assert.match(checkStandardVerdict('ordinary', withNaN).why, /not finite readings of a level ratio/);
    // ... while the healthy world passes, so none of the above is vacuous.
    assert.strictEqual(checkStandardVerdict('ordinary', Array.from({ length: 18 }, () => ORD.centre)).ok, true);
  });

  t('a row-run OUTSIDE either frozen limit refuses, and the refusal says which', () => {
    const flat = (x) => Array.from({ length: 18 }, () => x);
    const low = checkStandardVerdict('ordinary', flat(ORD.location.limits[0] - 0.01));
    assert.strictEqual(low.ok, false);
    assert.strictEqual(low.location.ok, false);
    assert.strictEqual(low.dispersion.ok, true, 'a flat world has no dispersion — this must be the LOCATION rule');
    assert.match(low.why, /outside the frozen location limits/);
    const high = checkStandardVerdict('ordinary', flat(ORD.location.limits[1] + 0.01));
    assert.strictEqual(high.ok, false, 'the limits are two-sided — an arm that OVERBUILDS is equally a fault');
    // a world whose median sits dead on the centre and whose blocks scatter
    const noisy = Array.from({ length: 18 }, (_, i) => ORD.centre + (i % 2 ? 1 : -1) * 0.9);
    const n = checkStandardVerdict('ordinary', noisy);
    assert.strictEqual(n.location.ok, true, 'the median is still the centre');
    assert.strictEqual(n.dispersion.ok, false);
    assert.strictEqual(n.ok, false);
    assert.match(n.why, /exceeds the frozen dispersion limit/);
  });

  t('every verdict carries the standard it was taken against, by id and version', () => {
    const v = checkStandardVerdict('ordinary', Array.from({ length: 18 }, () => ORD.centre));
    assert.strictEqual(v.standard.id, STD.id);
    assert.strictEqual(v.standard.version, STD.version);
    assert.strictEqual(v.standard.bead, 'rf2-pzqy8');
    assert.ok(Number.isInteger(STD.version), 'a version that is not an integer cannot be bumped');
    assert.deepStrictEqual(v.location.limits, ORD.location.limits, 'the frozen limits are the JSON\'s, never a literal');
    assert.strictEqual(v.location.centre, ORD.centre);
    assert.strictEqual(v.dispersion.limit, ORD.dispersion.limit);
    assert.ok(STD.recalibrateOn.length >= 4, 'a standard with no recalibration conditions is a constant');
    assert.ok(STD.recalibrateOn.some((s) => /FRESH baseline/.test(s)));
  });

  t('the driver cites the bead and the standard where a reader of the control lands', () => {
    assert.match(CSRC, /require\('\.\/census_check_standard\.json'\)/);
    assert.match(CSRC, /rf2-pzqy8/);
    // The finding itself, above `checkStandardVerdict`, where a reader of the
    // control lands — the measured centre and the prediction it is not.
    const note = CSRC.slice(CSRC.indexOf('THE CALIBRATED CHECK STANDARD, applied to one row-run'), CSRC.indexOf('function checkStandardVerdict('));
    assert.ok(note.length > 0, 'the standard no longer carries the note this test pins');
    assert.match(note, /1\.2308x/);
    assert.match(note, /1\.7255x/);
    assert.match(note, /mis-specified CENTRE/);
    assert.match(note, /rf2-8a746/, 'and the ruling whose idiom this is');
    assert.match(note, /rf2-y0pkh/, 'and the measurement it stands on');
  });
}

// --- rf2-pzqy8, half two: a REFUSED ROW no longer refuses the RUN -----------
//
// The higher-leverage half, and it is a SCOPE defect rather than an arithmetic
// one. `census_clock_run.cjs`'s prediction P4, registered before any clock,
// says: "the ORDINARY row (51 elements) sits near this door's own floor; if
// its control or band cannot hold, the ROW publishes a REFUSAL with the
// reason, not a number." The implementation refused the RUN — `verdict`'s
// `ctlFailed` exits 5 and `destination` then read that exit code and sent the
// whole run's datasets to `.unpublished`, including the `large-template` and
// `feed` rows that had passed every gate they have.
//
// Over the five committed sessions the ordinary row failed on both adapters
// every time, so NO FULL-SHAPE CENSUS RUN COULD EVER BE CANONICAL — which is
// why the mayor's 2026-08-07 ruling on rf2-jo60g specified work nobody could
// have done, and why this half is the precondition for any future
// recomputable census claim.
//
// THE REFUSAL IS NOT WEAKENED, IT IS PUT AT ITS OWN SCOPE. The exit code still
// refuses the run and still names every offending row; each row now carries
// its own `canonical` and `notCanonicalWhy`; the file indexes them; and the
// directory is chosen by the run's SHAPE alone, which is what
// `clock_run.cjs`'s `publication(shape)` has read all along.

{
  const CENSUS = DRIVERS[1].mod;
  const { summarise, summariseRow, verdict, destination, datasetFor, rowPublication } = CENSUS;
  const CSRC = fs.readFileSync(DRIVERS[1].file, 'utf8');
  const t = (what, fn) => test(`census refusal scope: ${what}`, fn);

  const CANON = '/data/censusclock-2rtt6-56';
  const shape = (over = {}) => ({
    dataDir: CANON,
    dataDirOverridden: false,
    rowsOnly: null,
    runsOnly: null,
    noBuild: false,
    depthPublished: true,
    skipQuiet: false,
    ...over,
  });
  const PUBLISHED = () => destination(shape());

  const DECOMP = () => ({ n: 10, task: 3, taskNet: 2, devtools: 1, script: 0.5, style: 0.4, layout: 0.6, layoutCount: 10, inPage: 1 });

  /** One row as `drive` collects it, with its four gates settable one at a time. */
  const resultRow = (rowId, gates = {}) => ({
    runId: 'uix',
    rowId,
    armIds: ['plumb', 'floor', 'ctl-2x'],
    canon: { floor: { hash: 'a', bytes: 10, control: true } },
    ctlPredicted: 1.7255,
    blocksTask: [[{ plumb: [0.7], floor: [2, 2.1], 'ctl-2x': [3, 3.1] }]],
    blocksNet: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksInPage: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksDecomp: [[{ plumb: DECOMP(), floor: DECOMP(), 'ctl-2x': DECOMP() }]],
    tally: { writes: 36, unverified: gates.unverified || 0 },
    runtime: 'fixture',
    quiet: { ok: true },
    windowStart: '2026-08-08T00:00:00.000Z',
    adjudication: {
      ctl: { ok: gates.ctlOk !== false, measured: { mean: gates.ctlMeasured || 1.9943 } },
      cAdditive: 0.96,
      assessed: {
        bandStats: { band: gates.band === undefined ? 0.12 : gates.band },
        verdict: { ceilingBreached: gates.ceilingBreached === true },
      },
      bars: {},
      verdicts: {},
      guardRefuse: gates.guardRefuse === true,
      plumb: 0.7683,
      floorTared: 1.4076,
    },
  });

  /** The full published shape: the three rows, in the order the driver takes them. */
  const fullShape = (gatesByRow = {}) =>
    ['large-template', 'feed', 'ordinary'].map((id) => resultRow(id, gatesByRow[id] || {}));
  const META = (dest) => ({ sha: 'deadbeef', blobs: {}, dest });
  const written = (rows, dest = PUBLISHED()) => JSON.parse(JSON.stringify(datasetFor(rows, META(dest))));

  // --- the green case first, so nothing below is vacuously red ---------------

  t('a full-shape run with every gate held is canonical, and every row is citable', () => {
    const data = written(fullShape());
    assert.strictEqual(data.canonical, true);
    assert.strictEqual(data.notCanonicalWhy, null);
    assert.deepStrictEqual(data.rowsRefused, []);
    for (const row of data.rows) {
      assert.strictEqual(row.canonical, true, `${row.rowId} must be citable`);
      assert.strictEqual(row.notCanonicalWhy, null);
    }
    assert.strictEqual(verdict(summarise(null, fullShape())).code, 0);
  });

  // --- THE ONE THIS BEAD EXISTS FOR -----------------------------------------

  t('ORDINARY REFUSED, THE OTHER TWO CLEAN: the two stay canonical and the run still refuses', () => {
    // The state every one of the five committed sessions was in, on both
    // adapters. Before rf2-pzqy8 this produced exit 5, dir
    // `censusclock-2rtt6-56.unpublished`, and `canonical: false` over the
    // whole file — the two rows that passed every gate discarded with the one
    // that did not.
    const rows = fullShape({ ordinary: { ctlOk: false, ctlMeasured: 1.2264 } });
    const v = verdict(summarise(null, rows));

    // 1. THE RUN STILL REFUSES. A run in which a row refused is not a clean
    //    run; it is a run with a refused row, and the exit says so.
    assert.strictEqual(v.code, 5, 'the run-level refusal is NOT weakened into nothing');
    assert.strictEqual(v.lines.length, 1);
    assert.match(v.lines[0], /uix\/ordinary \(measured 1\.2264x\)/);
    assert.doesNotMatch(v.lines[0], /large-template|feed/, 'a clean row must not be blamed');
    assert.match(v.lines[0], /the scope is the ROW \(rf2-pzqy8\)/);

    // 2. THE FILE STAYS IN THE PUBLISHED SET, because the run's SHAPE is the
    //    published one and a gate refusal is not a fact about the shape.
    const dest = PUBLISHED();
    assert.strictEqual(dest.dir, CANON, 'a refused ROW must not move the RUN off the canonical set');
    assert.strictEqual(dest.canonical, true);

    // 3. AND THE ROWS CARRY THE REFUSAL AT ITS OWN SCOPE.
    const data = written(rows, dest);
    assert.deepStrictEqual(data.rowsRefused, ['ordinary'], 'the file indexes exactly the row that refused');
    const byId = Object.fromEntries(data.rows.map((r) => [r.rowId, r]));
    for (const id of ['large-template', 'feed']) {
      assert.strictEqual(byId[id].canonical, true, `${id} passed every gate and must remain canonical`);
      assert.strictEqual(byId[id].notCanonicalWhy, null);
    }
    assert.strictEqual(byId.ordinary.canonical, false);
    assert.match(byId.ordinary.notCanonicalWhy, /POSITIVE CONTROL did not hold \(measured 1\.2264x\)/);
    assert.match(byId.ordinary.notCanonicalWhy, /prediction P4 promises the row publishes/);
    // ... and the row's numbers are still in the file. A refusal is evidence;
    // throwing the measurement away was never the refusal.
    assert.ok(byId.ordinary.blocksTask, 'the refused row is recorded, not deleted');
  });

  t('the whole point, stated as a counterfactual: nothing citable used to survive this run', () => {
    // If the write decision ever folds a gate refusal back in, this is the
    // assertion that reds. `destination` takes ONE argument now — the shape —
    // and a second one would have to come from somewhere.
    assert.strictEqual(destination.length, 1, '`destination` must read the run SHAPE and nothing else');
    const rows = fullShape({ ordinary: { ctlOk: false } });
    const citable = written(rows).rows.filter((r) => r.canonical).map((r) => r.rowId);
    assert.deepStrictEqual(citable, ['large-template', 'feed'], 'a full-shape run must be able to publish SOME row');
  });

  // --- each gate alone, at the row's scope ----------------------------------

  for (const [what, gates, needle] of [
    ['the ARM-ORDER GUARD', { guardRefuse: true }, /ARM-ORDER GUARD refused it/],
    ['UNVERIFIED read-backs', { unverified: 4 }, /4 of 36 operations are UNVERIFIED/],
    ['a BREACHED band ceiling', { ceilingBreached: true, band: 0.412 }, /band 41\.2% exceeds seam\.cjs's 35% ceiling/],
    ['a FAILED positive control', { ctlOk: false, ctlMeasured: 1.2264 }, /POSITIVE CONTROL did not hold/],
  ]) {
    t(`${what} refuses THAT row and no other`, () => {
      const data = written(fullShape({ feed: gates }));
      assert.deepStrictEqual(data.rowsRefused, ['feed']);
      const byId = Object.fromEntries(data.rows.map((r) => [r.rowId, r]));
      assert.strictEqual(byId.feed.canonical, false);
      assert.match(byId.feed.notCanonicalWhy, needle);
      for (const id of ['large-template', 'ordinary']) {
        assert.strictEqual(byId[id].canonical, true, `${id} must be untouched by ${what}`);
      }
      // ... and the run still refuses, so this cannot pass by going quiet.
      assert.notStrictEqual(verdict(summarise(null, fullShape({ feed: gates }))).code, 0);
      // ... and the same run WITHOUT that gate publishes every row, so it
      // cannot pass by refusing everything either.
      assert.deepStrictEqual(written(fullShape()).rowsRefused, []);
    });
  }

  t('several gates on one row name every one of them', () => {
    const data = written(fullShape({ ordinary: { ctlOk: false, ceilingBreached: true, band: 0.5, unverified: 2, guardRefuse: true } }));
    const why = data.rows.find((r) => r.rowId === 'ordinary').notCanonicalWhy;
    for (const needle of [/ARM-ORDER GUARD/, /UNVERIFIED/, /band 50\.0% exceeds/, /POSITIVE CONTROL/]) {
      assert.match(why, needle);
    }
  });

  // --- a row inherits the run's shape ---------------------------------------

  t('NO row of a run that is not the published shape is citable, whatever its own gates did', () => {
    // The composition that keeps the two scopes from drifting apart: the row's
    // own gates are necessary and the run's shape is necessary, and neither is
    // sufficient. A `--no-build` run whose every gate held publishes nothing.
    const dest = destination(shape({ noBuild: true }));
    assert.strictEqual(dest.canonical, false);
    const data = written(fullShape(), dest);
    assert.strictEqual(data.canonical, false);
    assert.match(data.notCanonicalWhy, /--no-build/);
    assert.deepStrictEqual(data.rowsRefused, ['large-template', 'feed', 'ordinary']);
    for (const row of data.rows) {
      assert.strictEqual(row.canonical, false);
      assert.match(row.notCanonicalWhy, /the run itself is not the published evidence: --no-build/);
    }
  });

  t('and the run-shape reason comes FIRST, so a reader sees the disqualifying fact first', () => {
    const dest = destination(shape({ skipQuiet: true }));
    const p = rowPublication(summariseRow(resultRow('ordinary', { ctlOk: false })), dest);
    assert.strictEqual(p.canonical, false);
    assert.match(p.why, /^the run itself is not the published evidence: a SKIPPED quiet gate/);
    assert.match(p.why, /POSITIVE CONTROL did not hold/, 'and the row-level reason is still named');
  });

  // --- fail closed ----------------------------------------------------------

  t('an absent row and an absent destination are each a REFUSAL, never a pass', () => {
    assert.strictEqual(rowPublication(undefined, PUBLISHED()).canonical, false);
    assert.match(rowPublication(undefined, PUBLISHED()).why, /an absent row is not a citable one/);
    assert.strictEqual(rowPublication(null, PUBLISHED()).canonical, false);
    const clean = summariseRow(resultRow('feed'));
    assert.strictEqual(rowPublication(clean, undefined).canonical, false);
    assert.match(rowPublication(clean, undefined).why, /no destination was decided for this run/);
    // ... and the same row with a decided, published destination IS citable.
    assert.strictEqual(rowPublication(clean, PUBLISHED()).canonical, true);
    assert.strictEqual(rowPublication(clean, PUBLISHED()).why, null);
  });

  // --- the wiring: ONE mapper, so the two scopes cannot disagree -------------

  t('the write path and the exit path read the SAME four refusal fields', () => {
    // The fault this replaces was two readings of one fact. `summarise` and
    // `rowPublication` now share `summariseRow`, so a row the exit refuses and
    // a row the file marks refused are the same row by construction — and a
    // renamed accessor breaks both at once instead of one silently.
    // Newlines are normalised because this tree is checked out with CRLF.
    const src = CSRC.replace(/\r\n/g, '\n');
    assert.match(src, /^const summariseRow = \(r\) => \(\{/m);
    assert.match(src, /function summarise\(failed, results\) \{\n\s*return \{ failed: failed \|\| null, rows: \(results \|\| \[\]\)\.map\(summariseRow\) \};/);
    assert.match(src, /const publication = \(r\) => rowPublication\(summariseRow\(r\), meta\.dest\);/);
    // and the summary the exit takes is built by that mapper
    const s = summarise(null, [resultRow('ordinary', { ctlOk: false, ctlMeasured: 1.2264 })]);
    assert.deepStrictEqual(s.rows[0], {
      id: 'uix/ordinary',
      guardRefuse: false,
      unverified: 0,
      writes: 36,
      ctlOk: false,
      ctlMeasured: 1.2264,
      ceilingBreached: false,
      band: 0.12,
    });
  });

  t('the header says the scope, in the instrument\'s own words', () => {
    const header = CSRC.slice(0, CSRC.indexOf("'use strict'"));
    assert.match(header, /the ROW publishes a REFUSAL with the reason, not a number/);
    assert.match(header, /A NONZERO EXIT IS A RUN-LEVEL FACT AND STAYS ONE/);
    assert.match(header, /full-shape census run could ever be canonical/i);
    assert.match(header, /rf2-pzqy8/);
    // The write-path note must name both scopes and the idiom it follows.
    const note = CSRC.slice(CSRC.indexOf('// WHERE A RUN\'S DATASETS MAY BE WRITTEN'), CSRC.indexOf('function destination('));
    assert.match(note, /`clock_run\.cjs`'s `publication\(shape\)` has read shape and nothing else/);
    assert.match(note, /A refusal is evidence/);
  });
}

// --- hd8_clock_run.cjs: the WRITE path, not just the exit path ---------------
//
// rf2-2rtt6.31, the write-before-refuse ruling. The same fail-open as the block
// above, on the sibling driver that never received the repair: the dataset
// writes preceded `verdict`, so a control-refused re-run silently replaced
// data/hd8clock-2rtt6-31/{uix,reagent,slim}.json — the very files the studio
// page recomputes from, and whose committed rows fail the control on five of
// six. The ruling is TWO-TIER: no completed measurement is ever discarded, but
// only a gate-passing full-shape run gets the published names. Capture is not
// publication.
//
// hd8's `destination` is census's rule with ONE condition absent — census can
// narrow its rows (C56CLOCK_ROWS), hd8 cannot — and the last check below pins
// that deviation so a row knob cannot be added without the routing following.

{
  const HD8 = DRIVERS[0].file;
  const { destination } = DRIVERS[0].mod;
  const SRC = fs.readFileSync(HD8, 'utf8');
  const CANON = '/data/hd8clock-2rtt6-31';
  const shape = (over = {}) => ({
    dataDir: CANON,
    dataDirOverridden: false,
    runsOnly: null,
    noBuild: false,
    depthPublished: true,
    ...over,
  });
  const t = (what, fn) => test(`hd8_clock_run.cjs write path: ${what}`, fn);

  t('the published shape, all gates passed, is the ONLY thing that is canonical', () => {
    const d = destination(shape(), 0);
    assert.strictEqual(d.canonical, true);
    assert.strictEqual(d.dir, CANON);
    assert.strictEqual(d.why, null);
  });

  // Each condition alone must move the write off the canonical set. These are
  // the mutation proofs: flip one field, the destination must change.
  for (const [what, over, code, needle] of [
    ['a refused verdict', {}, 5, /verdict refused it \(exit 5\)/],
    ['a partial run set', { runsOnly: 'uix' }, 0, /PARTIAL run set \(HD8CLOCK_ONLY=uix\)/],
    ['--no-build', { noBuild: true }, 0, /--no-build/],
    ['an overridden depth', { depthPublished: false }, 0, /OVERRIDDEN design depth/],
  ]) {
    t(`${what} is NOT canonical, and says why`, () => {
      const d = destination(shape(over), code);
      assert.strictEqual(d.canonical, false, `${what} must not be canonical`);
      assert.notStrictEqual(d.dir, CANON, `${what} must not write the published filenames`);
      assert.strictEqual(d.dir, `${CANON}.unpublished`);
      assert.match(d.why, needle);
      // ... and the same shape WITHOUT that condition is canonical again, so
      // the test cannot pass by refusing everything.
      assert.strictEqual(destination(shape(), 0).canonical, true);
    });
  }

  // The failure that actually bit: five of the six committed rows failed the
  // positive control (exit 5). Under the old driver that re-run overwrote the
  // cited evidence in place; it must now land beside it, and still land.
  t('EVERY refusal code this driver can return routes off the canonical set', () => {
    for (const code of [1, 2, 3, 4, 5]) {
      const d = destination(shape(), code);
      assert.strictEqual(d.canonical, false, `exit ${code} must not write the published evidence`);
      assert.strictEqual(d.dir, `${CANON}.unpublished`);
      assert.match(d.why, new RegExp(`verdict refused it \\(exit ${code}\\)`));
    }
  });

  // The preservation half of the ruling: rr6do's "no refusal suppresses
  // output" stands. A refusal moves the write, it never cancels it — the
  // refused rows were the DIAGNOSTIC evidence that exposed the control
  // failure, and discarding them would lose exactly the interesting data.
  t('a refused run still HAS a destination — the measurement is never discarded', () => {
    const d = destination(shape(), 5);
    assert.ok(d.dir && d.dir.length > 0, 'a refused run must still be written somewhere');
    assert.notStrictEqual(d.dir, null);
    assert.match(SRC, /No refusal suppresses output, and none ever discards a completed/);
    assert.match(SRC, /Capture is not publication\./);
  });

  t('every condition that fired is named, not just the first', () => {
    const d = destination(shape({ runsOnly: 'uix', noBuild: true, depthPublished: false }), 5);
    assert.strictEqual(d.canonical, false);
    for (const needle of [/verdict refused it \(exit 5\)/, /PARTIAL run set/, /--no-build/, /OVERRIDDEN design depth/]) {
      assert.match(d.why, needle);
    }
  });

  t('an explicit HD8CLOCK_DATA_DIR is honoured as given, and is never canonical', () => {
    const mine = '/data/hd8clock-somebead';
    const d = destination(shape({ dataDir: mine, dataDirOverridden: true }), 0);
    assert.strictEqual(d.dir, mine, 'the operator named the destination; do not rewrite it');
    assert.strictEqual(d.canonical, false, 'an operator-named directory is not the published set');
    // Even refused, it still lands where the operator said — the refusal is
    // carried by the exit code and by `canonical`, not by moving the file.
    assert.strictEqual(destination(shape({ dataDir: mine, dataDirOverridden: true }), 4).dir, mine);
  });

  // The defect was an ORDERING one: the write happened, and the refusal was
  // computed afterwards. Pin the order in the source, because that is what
  // regressed and a behavioural test of a browser driver cannot see it.
  const ORDER = (src) => {
    const v = src.indexOf('const v = verdict(summarise(failed, results));');
    const dst = src.indexOf('const dest = destination(runShape(), v.code);');
    const mk = src.indexOf('fs.mkdirSync(dest.dir');
    return { v, dst, mk, ok: v > 0 && dst > 0 && mk > 0 && v < dst && dst < mk };
  };

  t('the verdict is computed BEFORE any dataset is written', () => {
    const o = ORDER(SRC);
    assert.ok(o.v > 0 && o.dst > 0 && o.mk > 0, 'the write path no longer has the shape this test pins');
    assert.ok(o.v < o.dst, 'the verdict must be computed before the destination is chosen');
    assert.ok(o.dst < o.mk, 'the destination must be chosen before the directory is created');
    assert.ok(o.ok);
  });

  // ... and the same check must REFUSE the defect. A guard that only ever
  // reports "ok" on the one source it is pointed at proves nothing; this
  // hoists `destination` above `verdict` in a copy of the real text and
  // requires the guard to catch it. This is the regression, reconstructed.
  t('that ordering check REFUSES a destination consulted before the verdict', () => {
    const V = 'const v = verdict(summarise(failed, results));';
    const D = 'const dest = destination(runShape(), v.code);';
    // Swap the two statements in place, whatever the line endings are.
    const HOLE = '<<swap>>';
    const mutant = SRC.replace(V, HOLE).replace(D, V).replace(HOLE, D);
    assert.notStrictEqual(mutant, SRC, 'the mutation must actually change the source');
    assert.strictEqual(ORDER(mutant).ok, false, 'the guard must refuse a write decided before the verdict');
    // and the pre-repair shape — no `destination` at all — is refused too.
    assert.strictEqual(ORDER(SRC.replace(D, '')).ok, false, 'a driver with no destination step must not pass');
  });

  t('no dataset is written to the raw DATA_DIR downstream of `destination`', () => {
    // This is how the defect grew: the write named the canonical directory
    // directly. Every write site must go through the chosen destination.
    const after = SRC.slice(SRC.indexOf('const dest = destination(runShape(), v.code);'));
    assert.ok(!/fs\.mkdirSync\(DATA_DIR/.test(after), 'mkdirSync must use the chosen destination');
    assert.ok(!/path\.join\(DATA_DIR/.test(after), 'the dataset path must use the chosen destination');
    assert.match(after, /path\.join\(dest\.dir/);
  });

  t('a dataset records whether it is the published evidence', () => {
    // A file that travels out of its directory must still say what it is, and
    // a consumer that finds no `canonical` field has not found a pass.
    assert.match(SRC, /canonical: meta\.dest\.canonical/);
    assert.match(SRC, /notCanonicalWhy: meta\.dest\.why/);
  });

  t('the dataset is built OUTSIDE `drive`, so recording never looks like deciding', () => {
    // `datasetFor` names guardRefuse/ceilingBreached to SERIALISE them. Inside
    // `drive` and downstream of `verdict` that would trip the invariant above
    // — correctly, since a reader cannot tell a record from a second decision.
    assert.match(SRC, /^function datasetFor\(rows, meta\) \{/m);
    const drive = SRC.slice(SRC.indexOf('async function drive('));
    assert.ok(drive.indexOf('function datasetFor(') === -1, '`datasetFor` must sit above `drive`');
    assert.match(drive, /const data = datasetFor\(rows, \{ sha, blobs: bl, dest \}\);/);
  });

  t('the published depth has ONE definition, shared by the stamp and the write path', () => {
    assert.match(SRC, /const PUBLISHED_DEPTH = \{ rounds: 6, blocks: 3, warmup: 4, samples: 10 \}/);
    // The old inline copy must be gone, or the two can drift apart again.
    assert.ok(
      !/ROUNDS === 6 && BLOCKS === 3 && WARMUP === 4 && SAMPLES === 10/.test(SRC),
      'the inline depth predicate is duplicated; use depthIsPublished()'
    );
    assert.ok(SRC.split('depthIsPublished()').length - 1 >= 2, 'the one predicate must serve both readers');
  });

  t("the header states the two-tier rule, so the file's own record is not the old one", () => {
    const header = SRC.slice(0, SRC.indexOf("'use strict'"));
    assert.match(header, /## Where the datasets land/);
    assert.match(header, /`\.unpublished`/);
    assert.match(header, /HD8CLOCK_DATA_DIR is honoured as given/);
    // The canonical-filename half of the pre-ruling intent must be gone.
    assert.ok(
      !/the datasets are\r?\n\/\/ written before this is consulted/.test(SRC),
      'the pre-ruling "written before this is consulted" intent must not survive'
    );
  });

  // hd8 deviates from census by ONE condition, and only because it has no row
  // knob. If a row knob is ever added, this fails and the routing must follow
  // — the deviation is pinned to its reason, not left as an omission.
  t('the absent PARTIAL-ROW condition is pinned to its reason: hd8 has no row knob', () => {
    assert.ok(!/HD8CLOCK_ROWS/.test(SRC), 'a row knob exists; `destination` must name a PARTIAL row set');
    assert.match(SRC, /^const ROWS = \['mount-M', 'mount-U'\];\r?$/m);
    assert.match(SRC, /no partial-row shape to\r?\n\/\/ name/);
    // Census, which DOES have the knob, still names it — the two files carry
    // one rule, not two.
    const census = fs.readFileSync(DRIVERS[1].file, 'utf8');
    assert.match(census, /a PARTIAL row set \(C56CLOCK_ROWS=/);
  });
}

// --- clock_run.cjs: the bar-level adjudication must reach the exit code -------
//
// rf2-y7mw7. `clock_run.cjs` is the candidate clock and needs an `:advanced`
// build and a headless Chromium, so — exactly as above — its decision is a
// pure function over a flat per-row summary and is exercised here directly.

{
  const CLOCK = path.join(__dirname, 'clock_run.cjs');
  const {
    reportability, rowAdjudication, rowRegime, ROW_REGIME, reportabilitySelfTest,
    ctl3Verdict, ctl3SelfTest,
  } = require('./clock_run.cjs');
  const SRC = fs.readFileSync(CLOCK, 'utf8');
  const t = (what, fn) => test(`clock_run.cjs: ${what}`, fn);
  const KEYSTROKE_WHY = "UNADJUDICATED — this row's control burns a fixed 50 ms rather than doubling the page";
  const clockRow = (over) => ({ rowId: 'M1', ctlOk: true, ctlNote: '', adjudicable: true, ...over });
  // A bar as `seam.assess` writes it into `seamTask.rows`.
  const ADJ = { unadjudicated: false, why: 'margin 34.8% clears the band 21.4%' };
  const UNADJ = { unadjudicated: true, why: KEYSTROKE_WHY };

  // --- the driver's own fixtures, which `--selftest` also runs --------------

  t("the decision's own self-test passes, every case", () => {
    const { checks } = reportabilitySelfTest();
    assert.ok(checks.length >= 10, `expected the decision's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad, [], bad.map((c) => `${c.name}: ${c.detail}`).join('\n'));
  });

  // --- the three-point control's own fixtures, likewise --------------------
  //
  // The driver runs these before the browser opens and dies if one fails, but
  // that path needs an `:advanced` build to reach. Driving them here puts the
  // control's refusals in the fast spine, where a change to how a run
  // DESCRIBES itself gets checked against what it DECIDES.

  t("the three-point control's own self-test passes, every case", () => {
    const { checks } = ctl3SelfTest();
    assert.ok(checks.length >= 10, `expected the control's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad.map((c) => c.name), [], 'the control must still refuse everything it refused');
  });

  // --- rf2-8bgqq: the run figure is a summary, never a verdict --------------
  //
  // The headline moved from the block MEAN to the block MEDIAN, because a mean
  // over a quotient whose denominator sits ~2 sigma from zero summarises
  // whichever block came nearest to it: rf2-8a746's two ensembles were read as
  // DISAGREEING at 1.6045x and 86.05x when their block medians were 1.569 and
  // 1.575 and they agreed to within 2% on every structural quantity.
  //
  // A robuster headline is worth nothing if it is also a softer gate, and the
  // median is precisely the statistic that shrugs off the one wild block. So
  // the load-bearing assertion is not that the number improved — it is that a
  // run which refused before refuses after, ON A RUN BUILT SO THE NEW HEADLINE
  // LOOKS PERFECT: eight clean blocks and one whose denominator has collapsed,
  // median dead on the prediction and inside the band, verdict still FAIL.
  {
    const SEG = ['reagent-subs', 'uix-subs', 'hicasso'];
    const D = [1, 100, 200];
    const plan = ['ctl-d1', 'ctl-d100', 'ctl-d200'].map((id, i) => ({
      id, dirty: D[i], ctl3: true, ctl3Witness: false, cells: 300,
    }));
    const A = 0.006;
    const C = 3.5;
    const synth = (f) => {
      const rs = [];
      for (let r = 0; r < 3; r++) {
        const per = {};
        for (let i = 0; i < SEG.length; i++) {
          per[SEG[i]] = {
            'ctl-d1': [f(1, r, i)], 'ctl-d100': [f(100, r, i)], 'ctl-d200': [f(200, r, i)],
            floor: [f(300, r, i)], plumb: [0.7],
          };
        }
        rs.push(per);
      }
      return rs;
    };
    // One block of nine with a denominator collapsed to 0.02 ms; the rest
    // linear and exact. Both differences stay positive, so the sign gate is
    // clean and the refusal is the band's, exactly as on the real runs.
    const heavy = ctl3Verdict(
      synth((d, r, i) => (r === 0 && i === 0 ? { 1: 5.0, 100: 5.02, 200: 7.0 }[d] : A * d + C)),
      plan,
      0.25
    );

    t('rf2-8bgqq: a refusal STAYS a refusal though the new headline lands dead on the prediction', () => {
      assert.strictEqual(heavy.measured.p50, 2.0101, 'the median is the prediction, to four places');
      assert.ok(
        heavy.measured.p50 >= heavy.band[0] && heavy.measured.p50 <= heavy.band[1],
        `the new headline must be INSIDE the band [${heavy.band}] for this test to mean anything`
      );
      // ...and the run refuses anyway. A verdict that read the summary would
      // pass here, and that is the whole failure this test exists to catch.
      assert.strictEqual(heavy.premiseMet, false, 'one out-of-band block must still break the premise');
      assert.strictEqual(heavy.sign.ok, true, 'refused by the BAND, not swept up by the sign gate');
      assert.strictEqual(heavy.perRound.filter((x) => x > 10).length, 1, 'exactly one wild block');
    });

    t('rf2-8bgqq: the mean it replaced was off by a factor of six, from that one block', () => {
      assert.strictEqual(heavy.measured.mean, 12.8979);
      assert.ok(heavy.measured.mean > heavy.band[1], 'the OLD headline was outside the band it is quoted against');
    });

    t('rf2-8bgqq: the denominator is surfaced in ms, which is where the collapse is visible', () => {
      assert.strictEqual(heavy.signal.denMs.p50, 0.594, 'the healthy denominator');
      assert.strictEqual(heavy.signal.denMs.min, 0.02, 'and the collapsed one, which the ratio alone cannot show');
    });

    t('rf2-8bgqq: on a healthy run the median IS the mean, so no untailed run moved', () => {
      const clean = ctl3Verdict(synth((d) => A * d + C), plan, 0.25);
      assert.strictEqual(clean.premiseMet, true);
      assert.strictEqual(clean.measured.p50, clean.measured.mean);
      assert.strictEqual(clean.measured.p50, 2.0101);
    });

    t('rf2-8bgqq: every refusing world in the fixture set still refuses', () => {
      // The summary changed; the refusals may not. Each of these is a world
      // the control is built to reject, driven through the same entry point.
      const at = (m) => (d) => (d in m ? m[d] : 0);
      const refusing = {
        'decreasing (more dirty work reads FASTER)': synth((d) => 10 - A * d),
        'superlinear': synth((d) => (A * Math.pow(d, 2)) / 300 + C),
        'a dead page': synth(() => C),
        'negative denominator': synth(at({ 1: 5.0, 100: 4.7, 200: 4.4 })),
        'zero denominator': synth(at({ 1: 5.0, 100: 5.0, 200: 6.2 })),
        'an unreadable arm': synth((d) => (d === 100 ? NaN : A * d + C)),
      };
      for (const [name, rounds] of Object.entries(refusing)) {
        assert.strictEqual(ctl3Verdict(rounds, plan, 0.25).premiseMet, false, `${name} must still break the premise`);
      }
      assert.strictEqual(ctl3Verdict([], plan, 0.25).premiseMet, false, 'and an empty block set is not a premise met');
    });
  }

  // --- the defect itself, driven here as well as in the driver -------------

  t('THE FAIL-OPEN: a row whose every bar is UNADJUDICATED cannot exit 0', () => {
    const v = reportability([
      clockRow({ rowId: 'keystroke', adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY }),
    ]);
    assert.notStrictEqual(v.code, 0, 'a run with no adjudicable bar must not exit 0');
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /not every published bar can be ADJUDICATED on: keystroke/);
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
    assert.match(v.lines[0], /HCLOCK_CTL3_SABOTAGE=140 WAS SET/);
  });

  t('neither verdict masks the other — a row failing both is refused for both', () => {
    const v = reportability([
      clockRow({ rowId: 'keystroke', ctlOk: false, adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY }),
    ]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /positive control/);
    assert.match(v.lines[1], /not every published bar can be ADJUDICATED/);
    assert.strictEqual(v.lines[3], '[clock] REPORTABLE: none.');
  });

  // --- THE REMAINDER: a row whose bars DISAGREE (#7489's merged-PR audit) ---
  //
  // The first repair put the bar-level verdict into the exit code but derived
  // it with `unadj.length < names.length` — "at least one bar carries a band"
  // — while the REPORTABLE line it printed claimed "every published bar
  // adjudicated". A row publishing three bars of which one had no band
  // therefore satisfied the code and contradicted the sentence, and the run
  // exited 0. Nothing caught it because the rule lived inline in `main`, which
  // needs an `:advanced` build and a headless Chromium to reach; the only
  // check on it was a regex over the source, and the regex matched the wrong
  // rule faithfully. It is a pure function now, and these drive it.

  t('THE REMAINDER: ONE unadjudicated bar makes the row unadjudicable', () => {
    const a = rowAdjudication({
      'hicasso / reagent-subs': ADJ,
      'hicasso / uix-subs': UNADJ,
      'uix-subs / reagent-subs': ADJ,
    });
    assert.strictEqual(a.adjudicable, false, 'two adjudicated bars may not carry a third that has no band');
    assert.strictEqual(a.barCount, 3);
    assert.deepStrictEqual(a.unadjudicatedBars, ['hicasso / uix-subs']);
    assert.strictEqual(a.unadjudicatedWhy, KEYSTROKE_WHY);
  });

  t('and that row cannot exit 0 — the refusal reaches the code, naming the bar', () => {
    const mixed = rowAdjudication({ 'h / r': ADJ, 'h / u': UNADJ, 'u / r': ADJ });
    const v = reportability([clockRow({}), clockRow({ rowId: 'keystroke', ...mixed })]);
    assert.notStrictEqual(v.code, 0, 'a row with a bar it cannot adjudicate must not exit 0');
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /not every published bar can be ADJUDICATED on: keystroke/);
    assert.match(v.lines[1], /1 of 3 published bars carry no band \(h \/ u\)/);
    assert.match(v.lines[1], new RegExp(KEYSTROKE_WHY.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  });

  t('the clean rows beside it are still reportable — the refusal stays per-row', () => {
    const mixed = rowAdjudication({ 'h / r': ADJ, 'h / u': UNADJ });
    const v = reportability([clockRow({}), clockRow({ rowId: 'keystroke', ...mixed })]);
    const last = v.lines[v.lines.length - 1];
    assert.match(last, /^\[clock\] REPORTABLE: M1 —/);
    assert.doesNotMatch(last, /keystroke/, 'a row with an unadjudicated bar must never appear in REPORTABLE');
  });

  t('a row whose every bar carries a band is adjudicable — the gate is not vacuous', () => {
    // Without this, every assertion above would still pass if `rowAdjudication`
    // simply always returned false.
    const a = rowAdjudication({ 'h / r': ADJ, 'h / u': ADJ, 'u / r': ADJ });
    assert.strictEqual(a.adjudicable, true);
    assert.strictEqual(a.barCount, 3);
    assert.deepStrictEqual(a.unadjudicatedBars, []);
    assert.strictEqual(reportability([clockRow({ ...a })]).code, 0);
  });

  t('the case #7489 DID catch is still caught — the rule was tightened, not swapped', () => {
    const a = rowAdjudication({ 'h / r': UNADJ, 'h / u': UNADJ });
    assert.strictEqual(a.adjudicable, false);
    assert.deepStrictEqual(a.unadjudicatedBars, ['h / r', 'h / u']);
    assert.strictEqual(reportability([clockRow({ rowId: 'keystroke', ...a })]).code, 1);
  });

  t('an empty or missing bar set fails CLOSED — absent is not clean', () => {
    for (const bars of [{}, undefined, null]) {
      const a = rowAdjudication(bars);
      assert.strictEqual(a.adjudicable, false, `bars=${JSON.stringify(bars)} must not be adjudicable`);
      assert.strictEqual(a.barCount, 0);
      assert.strictEqual(a.unadjudicatedWhy, 'the run adjudicated no bar on this row at all');
    }
    // And with no bar names to print, the refusal falls back to the row's own
    // reason rather than printing "0 of 0 published bars".
    const v = reportability([clockRow({ ...rowAdjudication({}) })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[1], /M1: the run adjudicated no bar on this row at all/);
    assert.doesNotMatch(v.lines[1], /0 of 0/);
  });

  // --- AND THE FIELD, not merely the bar set (#7550's merged-PR audit) ------
  //
  // The strict rule above still asked TRUTHINESS — `src[n].unadjudicated` —
  // so absence read as cleanliness one level down: a bar the dataset stored
  // as `{}` counted as adjudicated, and `rowAdjudication` returned
  // `adjudicable: true` beside `unadjudicatedWhy: "the run adjudicated no bar
  // on this row at all"`. A function contradicting itself inside one returned
  // object is what a fail-open looks like from the inside. Reproduced against
  // 57e0e68 before the repair, and driven here.

  t('THE FIELD: a bar with NO `unadjudicated` field is unadjudicated — absent is not clean', () => {
    const a = rowAdjudication({ 'h / r': ADJ, 'h / u': {} });
    assert.strictEqual(a.adjudicable, false, 'a bar carrying no verdict has not been adjudicated');
    assert.strictEqual(a.barCount, 2);
    assert.deepStrictEqual(a.unadjudicatedBars, ['h / u']);
    assert.strictEqual(a.unadjudicatedWhy, 'the bar carries no adjudication verdict at all');
  });

  t('a row of nothing but fieldless bars cannot exit 0, and it used to exit 0', () => {
    // The exact shape the audit named: every bar `{}`, which before the repair
    // produced `{adjudicable: true, unadjudicatedWhy: "the run adjudicated no
    // bar on this row at all"}` and a green run.
    const a = rowAdjudication({ 'h / r': {}, 'h / u': {} });
    assert.strictEqual(a.adjudicable, false);
    const v = reportability([clockRow({ rowId: 'keystroke', ...a })]);
    assert.strictEqual(v.code, 1, 'a run that adjudicated nothing must not announce success');
    assert.match(v.lines[0], /not every published bar can be ADJUDICATED on: keystroke/);
    assert.match(v.lines[1], /2 of 2 published bars carry no band \(h \/ r, h \/ u\)/);
    assert.match(v.lines[1], /the bar carries no adjudication verdict at all/);
  });

  t('a bar stored as null or undefined is unadjudicated rather than a crash', () => {
    // Truthiness dereferenced the record before testing it, so a null bar did
    // not fail open — it threw `Cannot read properties of null`, which is a
    // driver that dies mid-report rather than one that refuses.
    for (const missing of [null, undefined]) {
      const a = rowAdjudication({ 'h / r': ADJ, 'h / u': missing });
      assert.strictEqual(a.adjudicable, false, `bar=${String(missing)} must not be adjudicable`);
      assert.deepStrictEqual(a.unadjudicatedBars, ['h / u']);
      assert.strictEqual(a.unadjudicatedWhy, 'the bar carries no adjudication verdict at all');
    }
  });

  t('an EXPLICIT clean verdict is what adjudicates — the tightening is not vacuous', () => {
    // Without this, everything above would pass if the rule returned false for
    // every bar. `unadjudicated: false` is the one value that means adjudicated.
    const a = rowAdjudication({ 'h / r': { unadjudicated: false }, 'h / u': { unadjudicated: false, why: 'x' } });
    assert.strictEqual(a.adjudicable, true);
    assert.deepStrictEqual(a.unadjudicatedBars, []);
    assert.strictEqual(reportability([clockRow({ ...a })]).code, 0);
  });

  // --- and the sentence that invited the publication -----------------------

  t('REPORTABLE no longer says "Publish those" without saying "adjudicated"', () => {
    const v = reportability([clockRow({}), clockRow({ rowId: 'keystroke', adjudicable: false })]);
    const line = v.lines[v.lines.length - 1];
    assert.match(line, /every published bar adjudicated against this run's own band/);
    assert.doesNotMatch(line, /keystroke/, 'an unadjudicated row must never appear in REPORTABLE');
  });

  // --- THE REGIMES: what a row publishes, declared (rf2-jcm3p, rf2-swwud) ---
  //
  // Two rows had spent months refusing for reasons no amount of measuring
  // could move — `M1`'s positive control undershoots 2.00x by an additive
  // constant and no changed-set control can reach a mount; `keystroke`'s
  // control burns a fixed 50 ms and therefore supplies no band. Both rulings
  // NARROW THE CLAIM rather than build a better instrument: the rows publish
  // regimes, not magnitudes.
  //
  // THE THING THESE CASES EXIST TO PIN is that the narrowing did not soften
  // anything. rf2-y7mw7 had just made `HCLOCK_ONLY=keystroke` exit 1, and a
  // "relabelling" that let it exit 0 again would be that repair undone under
  // a nicer name. Every case below asserts the code as well as the sentence.

  const mountRow = (over) => clockRow({ rowId: 'M1', regime: 'mount-regime', ctlOk: false, ...over });
  const respRow = (over) =>
    clockRow({
      rowId: 'keystroke',
      regime: 'responsiveness-regime',
      adjudicable: false,
      unadjudicatedWhy: KEYSTROKE_WHY,
      ...over,
    });

  t('THE ROSTER: M1 is a mount regime, keystroke a responsiveness regime', () => {
    assert.strictEqual(rowRegime('M1'), 'mount-regime');
    assert.strictEqual(rowRegime('keystroke'), 'responsiveness-regime');
    assert.deepStrictEqual(ROW_REGIME, {
      M1: 'mount-regime',
      bulk300: 'magnitude',
      bulk100: 'magnitude',
      narrow: 'magnitude',
      keystroke: 'responsiveness-regime',
    });
  });

  t('a regime is granted by ruling and never by default — an unknown row is a magnitude row', () => {
    assert.strictEqual(rowRegime('bulk300'), 'magnitude');
    assert.strictEqual(rowRegime('some-row-nobody-ruled-on'), 'magnitude');
    assert.strictEqual(rowRegime(undefined), 'magnitude');
  });

  t('THE EXIT DID NOT MOVE: a keystroke-only run exits 1 exactly as rf2-y7mw7 made it', () => {
    const v = reportability([respRow()]);
    assert.strictEqual(v.code, 1, 'a regime row publishes no magnitude, so it cannot exit 0');
    assert.strictEqual(v.lines[v.lines.length - 1], '[clock] REPORTABLE: none.');
  });

  t('and an M1-only run exits 1 exactly as its failing control made it', () => {
    assert.strictEqual(reportability([mountRow()]).code, 1);
  });

  t('the keystroke refusal now states a REGIME rather than an unadjudicated magnitude', () => {
    const v = reportability([respRow()]);
    const all = v.lines.join('\n');
    assert.match(all, /REGIME: these rows publish a regime and never a magnitude/);
    assert.match(all, /keystroke \[responsiveness-regime, rf2-swwud\] STATED/);
    assert.match(all, /DIAGNOSTIC, never magnitudes/);
    assert.doesNotMatch(
      all,
      /not every published bar can be ADJUDICATED/,
      "a bandless bar on a regime row is a diagnostic, not a magnitude the run failed to adjudicate"
    );
  });

  t('the M1 refusal now states a REGIME rather than a control that went wrong', () => {
    const v = reportability([mountRow({ ctlNote: ' (ctl-2x 1.8173x vs 2.00x)' })]);
    const all = v.lines.join('\n');
    assert.match(all, /M1 \[mount-regime, rf2-jcm3p\] STATED/);
    assert.match(all, /DIRECTION ONLY/);
    assert.match(all, /positive control: FAIL \(ctl-2x 1\.8173x vs 2\.00x\) — expected, and the reason no magnitude/);
    assert.doesNotMatch(all, /the positive control did not see the change/);
  });

  // --- MUTATION, BOTH DIRECTIONS -------------------------------------------
  //
  // A label that cannot be got wrong is a label nothing depends on. These two
  // revert each row's regime and assert the run reads differently — the first
  // is the relabelling's forward proof, the second its reverse.

  t('MUTATION: relabel M1 back to a magnitude row and it reads as a fault again', () => {
    const v = reportability([mountRow({ regime: 'magnitude', ctlNote: ' (ctl-2x 1.8173x vs 2.00x)' })]);
    assert.strictEqual(v.code, 1, 'the exit is the same either way — only the sentence differs');
    assert.match(v.lines[0], /the positive control did not see the change its own arithmetic predicts on: M1/);
    assert.doesNotMatch(v.lines.join('\n'), /mount-regime/);
  });

  t('MUTATION: relabel keystroke back to a magnitude row and its bars read as unadjudicated', () => {
    const v = reportability([respRow({ regime: 'magnitude' })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines[0], /not every published bar can be ADJUDICATED on: keystroke/);
    assert.doesNotMatch(v.lines.join('\n'), /responsiveness-regime/);
  });

  // --- the one condition rf2-swwud puts on the responsiveness regime --------

  t('a responsiveness regime is WITHHELD when its fixed-work controls did not pass', () => {
    const v = reportability([respRow({ ctlOk: false })]);
    assert.strictEqual(v.code, 1);
    assert.match(v.lines.join('\n'), /keystroke \[responsiveness-regime, rf2-swwud\] WITHHELD/);
    assert.match(v.lines.join('\n'), /prove the instrument moves when the work moves/);
  });

  t('a mount regime is NOT withheld by a failing control — the two regimes differ deliberately', () => {
    // rf2-jcm3p's premise IS that ctl-2x fails, so a mount regime that waited
    // on it could never be stated at all.
    const v = reportability([mountRow()]);
    assert.match(v.lines.join('\n'), /M1 \[mount-regime, rf2-jcm3p\] STATED/);
    assert.doesNotMatch(v.lines.join('\n'), /M1 .* WITHHELD/);
  });

  // --- the magnitude rows are untouched, which is the other half ------------

  t('a magnitude row beside the regimes is still reportable, and no regime joins it', () => {
    const v = reportability([clockRow({ rowId: 'bulk300' }), mountRow(), respRow()]);
    assert.strictEqual(v.code, 1);
    const last = v.lines[v.lines.length - 1];
    assert.match(last, /^\[clock\] REPORTABLE: bulk300 —/);
    assert.doesNotMatch(last, /M1|keystroke/, 'a regime row may never be announced as reportable');
  });

  t("a magnitude row's own gates still refuse it, beside the regimes", () => {
    const v = reportability([clockRow({ rowId: 'bulk300', ctlOk: false }), mountRow()]);
    assert.strictEqual(v.code, 1);
    const all = v.lines.join('\n');
    assert.match(all, /the positive control did not see the change.*bulk300/);
    assert.match(all, /REGIME:/);
    assert.doesNotMatch(
      all,
      /predicts on: bulk300, M1|predicts on: M1/,
      'a regime row must not be listed among the control failures'
    );
  });

  t('and a clean all-magnitude run still exits 0 — the regimes did not make the gate vacuous', () => {
    assert.deepStrictEqual(reportability([clockRow({ rowId: 'bulk300' }), clockRow({ rowId: 'narrow' })]), {
      code: 0,
      lines: [],
    });
  });

  t('the driver hands the DECLARED regime to the decision, not one it inferred from its numbers', () => {
    const M = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));
    assert.match(M, /regime: rowRegime\(o\.out\.rowId\)/);
  });

  // --- the wiring: `reportability` is load-bearing, not decorative ----------

  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));

  t('the driver exposes its decision and does not drive itself on require', () => {
    assert.ok(MAIN.length > 0, 'the driver must expose its run as `main`');
    // The decision's four seats, plus the write path's two (rf2-e87sk) — a
    // serialiser nothing can require is a serialiser nothing can drive.
    for (const name of [
      'reportability',
      'rowAdjudication',
      'rowRegime',
      'ROW_REGIME',
      'reportabilitySelfTest',
      'publication',
      'datasetFor',
      'PUBLISHED_DEPTH',
    ]) {
      assert.match(SRC, new RegExp(`module\\.exports = \\{[^}]*\\b${name}\\b`), `\`${name}\` must be exported`);
    }
    assert.match(SRC, /if \(require\.main === module\) \{\s*main\(\);/);
  });

  t('the summary reads the adjudication the report printed, rather than recomputing it', () => {
    // A second computation is a second decision, and a second decision is
    // this whole file's subject.
    assert.match(MAIN, /rowAdjudication\(o\.verdict\.seamTask && o\.verdict\.seamTask\.rows\)/);
    // And the rule itself is NOT written out here. It used to be, and the only
    // thing checking it was a regex on this line — which held the loose rule
    // happily for as long as the regex agreed with it. The behavioural cases
    // are below; this asserts the inline copy has not grown back.
    assert.ok(
      !/unadj\.length|Object\.keys\(bars\)/.test(MAIN),
      'the bar-level rule must live in `rowAdjudication`, not inline in `main` where no test can drive it'
    );
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

// --- A RUN'S RAW READINGS, AS A FIXTURE (rf2-8a746) --------------------------
//
// The check standard is applied to the READINGS rather than read back off a
// stored boolean — a check standard is versioned data a reader applies, and
// re-applying a recalibrated standard to a retained dataset is the whole point
// of freezing it as data. So every fixture that must clear the gate below has
// to carry readings, and this builds them in the shape `clock_run.cjs` writes:
// `roundsTask[round][segment][arm]`, ten samples an arm.
//
// The numbers are the instrument's own model — a floor sample is `W + c` where
// `c` is the part that does not scale with the page, and `ctl-2x` builds twice
// the page so it reads `2W + c`. `W = 3.0` and `c = 1.1628` put the block ratio
// dead on the frozen empirical centre, 1.7207x. The jitter is deterministic and
// small, so the run has a real dispersion without being anywhere near the
// frozen limit: a fixture that passed only because it had none would not be
// exercising the dispersion term at all.
const FIXTURE_SEGMENTS = ['reagent-subs', 'uix-subs', 'hicasso'];
function fixtureRoundsTask(over) {
  const o = over || {};
  const W = o.W === undefined ? 3.0 : o.W;
  const C = 1.1628;
  const TARE = 0.7;
  const scale = o.ctlScale === undefined ? 2 : o.ctlScale; // what ctl-2x actually builds
  const ten = (x) => Array.from({ length: 10 }, (_, k) => x + (k % 3) * 0.01);
  const rounds = [];
  for (let r = 0; r < 6; r++) {
    const per = {};
    for (let i = 0; i < FIXTURE_SEGMENTS.length; i++) {
      const seg = FIXTURE_SEGMENTS[i];
      const j = 0.02 * ((r + i) % 4) - 0.03;
      per[seg] = {
        plumb: ten(TARE),
        floor: ten(W + C + TARE + j),
        'ctl-2x': ten(scale * W + C + TARE + j),
        // the segment's own substrate arm, which is what the paired level
        // ratio is formed from
        [seg]: ten(5.0 + i * 0.1 + j),
      };
    }
    rounds.push(per);
  }
  return rounds;
}

// --- clock_readjudicate.cjs: the SAME term, on the persisted datasets --------
//
// rf2-y7mw7's second place. The driver adjudicates one run; the readjudicator
// pools an ENSEMBLE of the driver's stored datasets and prints the figure that
// gets published, so the same fail-open has two homes and the second one
// outlives the first — a dataset is re-read long after the `seam.assess` that
// wrote it.
//
// #7489 added the adjudication term here as `names.some((n) => !unadjudicated)`
// and its audit found that wrong for the reason the driver's was: ONE
// adjudicated bar admitted the whole run into the "control-passing subset",
// FOR EVERY PAIR — including the pair whose own bar had no band. The predicate
// was also unreachable, because the file read `process.argv` at module scope
// and exited, so requiring it ran it. Both are repaired below.

{
  const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
  const { GATES, adjudicated, refusals, reportable, responsivenessRegime } = require('./clock_readjudicate.cjs');
  const RJSRC = fs.readFileSync(RJ, 'utf8');
  const t = (what, fn) => test(`clock_readjudicate.cjs: ${what}`, fn);
  const ADJ = { unadjudicated: false, band: 0.21, why: 'margin 34.8% clears the band 21.4%' };
  const UNADJ = { unadjudicated: true, band: null, why: 'UNADJUDICATED — no proportional control on this row' };
  // THE FILE'S OWN TWO-TIER VERDICT (rf2-2rtt6.31), as `datasetFor` writes it.
  // Every predicate here is handed one, because a row cannot vouch for the
  // file it came from and this filter's first gate is that it does not try.
  // `design` is here because a ROW gate now receives the envelope too
  // (rf2-8a746): whether the readings are tared decides what a level ratio
  // taken from them means, and the check standard refuses a record that does
  // not say.
  const CANON = { canonical: true, notCanonicalWhy: null, design: { rounds: 6, warmup: 4, samples: 10, tare: true } };
  // A dataset row as a two-tier `clock_run.cjs` writes it, reduced to the
  // fields this predicate reads — every whole-run verdict the driver exits on,
  // each at its passing value.
  //
  // `bulk300` rather than `M1`, and the reason has changed while the choice has
  // not. Under rf2-8a746 the mount class was deliberately UNCALIBRATED, so an
  // `M1` row could never be reportable and a roster fixture built on one would
  // have been asserting against a gate closed for an unrelated reason.
  // rf2-x7x10 calibrated it, so that hazard is gone — but the fixture stays on
  // `bulk300` because its readings are built at the BULK centre, and a roster
  // that swapped the row id without swapping the world would be certifying a
  // mount against limits its blocks were never placed inside.
  const dsRow = (bars, over) => ({
    rowId: 'bulk300',
    pageErrors: [],
    guardRefuse: false,
    guardRefuseTask: false,
    parityOk: true,
    ctl3Parity: null,
    kbWitness: null,
    tally: { writes: 1008, unverified: 0 },
    ctl3: null,
    ctlOk: null,
    ctlTask: { measured: { mean: 1.9 }, inBand: 18, of: 18 },
    roundsTask: fixtureRoundsTask(),
    etVerdict: null,
    seam: { verdict: { ceilingBreached: false } },
    seamTask: { ceilingBreached: false, rows: bars },
    ...over,
  });

  t('THE REMAINDER: one unadjudicated bar keeps the whole run OUT of the subset', () => {
    const row = dsRow({ 'h / r': ADJ, 'h / u': UNADJ, 'u / r': ADJ });
    assert.strictEqual(adjudicated(row), false, 'two adjudicated bars may not carry a third with no band');
    assert.strictEqual(reportable(row, CANON), false, 'and the run may not be pooled into the published mean');
  });

  t('a run whose every bar carries a band IS pooled — the predicate is not vacuous', () => {
    const row = dsRow({ 'h / r': ADJ, 'h / u': ADJ, 'u / r': ADJ });
    assert.strictEqual(adjudicated(row), true);
    assert.strictEqual(reportable(row, CANON), true, 'a clean run must still reach the reportable subset');
  });

  t('a run whose every bar is UNADJUDICATED is still out — the term was tightened, not swapped', () => {
    assert.strictEqual(adjudicated(dsRow({ 'h / r': UNADJ, 'h / u': UNADJ })), false);
  });

  t('a dataset that stored no bar verdict at all fails closed', () => {
    assert.strictEqual(adjudicated(dsRow({})), false, 'an empty bar set is absent, not clean');
    assert.strictEqual(adjudicated({ rowId: 'M1' }), false, 'a row with no seamTask at all is absent, not clean');
    assert.strictEqual(adjudicated(undefined), false);
    assert.strictEqual(reportable(undefined, CANON), false);
  });

  // --- AND THE FIELD, which #7550's merged-PR audit found still open here ---
  //
  // The predicate asked `!bars[n].unadjudicated`, so a bar the FILE stored as
  // `{}` — present, and carrying no verdict — read as adjudicated and the run
  // was pooled into the published mean for every pair. This is the seat where
  // it matters most: the driver reads an object built moments earlier in its
  // own process, this program reads a file, and a file is where a field goes
  // missing. Reproduced against 57e0e68 before the repair.

  t('THE FIELD: a bar with no `unadjudicated` field keeps the run OUT of the subset', () => {
    const row = dsRow({ 'h / r': ADJ, 'h / u': {} });
    assert.strictEqual(adjudicated(row), false, 'a bar carrying no verdict has not been adjudicated');
    assert.strictEqual(reportable(row, CANON), false, 'and a lost verdict may not reach the published mean');
  });

  t('a dataset whose every bar is fieldless is out, and it used to be IN', () => {
    // The exact shape the audit named: `adjudicated` returned true, and with a
    // passing control and no ceiling breach `reportable` returned true too.
    const row = dsRow({ 'h / r': {}, 'h / u': {} });
    assert.strictEqual(adjudicated(row), false);
    assert.strictEqual(reportable(row, CANON), false);
  });

  t('a bar stored as null or undefined is out rather than a crash', () => {
    // Truthiness dereferenced the record before testing it, so these threw
    // `Cannot read properties of null` — a reader that dies over a dataset
    // rather than one that declines to publish from it.
    for (const missing of [null, undefined]) {
      assert.strictEqual(adjudicated(dsRow({ 'h / r': ADJ, 'h / u': missing })), false, `bar=${String(missing)}`);
      assert.strictEqual(reportable(dsRow({ 'h / r': ADJ, 'h / u': missing }), CANON), false, `bar=${String(missing)}`);
    }
  });

  t('an EXPLICIT clean verdict is what pools a run — the tightening is not vacuous', () => {
    // Without this, everything above would pass if the predicate always said
    // false and no run would ever be published again.
    const row = dsRow({ 'h / r': { unadjudicated: false }, 'h / u': { unadjudicated: false, band: 0.2 } });
    assert.strictEqual(adjudicated(row), true);
    assert.strictEqual(reportable(row, CANON), true);
  });

  t('the OTHER two terms are unchanged — adjudication was ADDED, never substituted', () => {
    const bars = { 'h / r': ADJ, 'h / u': ADJ };
    // A failed control still excludes a fully adjudicated run — and it is the
    // CHECK STANDARD's refusal now (rf2-8a746), read off the run's own
    // readings rather than off a stored `ctlTask.ok`.
    assert.strictEqual(
      reportable(dsRow(bars, { roundsTask: fixtureRoundsTask({ ctlScale: 140 / 300 }) }), CANON),
      false
    );
    assert.strictEqual(reportable(dsRow(bars, { roundsTask: [] }), CANON), false);
    // ... and so does either ceiling, which fires before any control.
    assert.strictEqual(
      reportable(dsRow(bars, { seam: { verdict: { ceilingBreached: true } } }), CANON),
      false,
      'a breached net-band ceiling still excludes the run'
    );
    assert.strictEqual(
      reportable(dsRow(bars, { seamTask: { ceilingBreached: true, rows: bars } }), CANON),
      false,
      'a breached task-band ceiling still excludes the run'
    );
  });

  // --- EVERY GATE THE DRIVER EXITS ON, ENFORCED HERE TOO (rf2-emvod) --------
  //
  // #7365's merged-PR audit: the subset asked for `ctlTask.ok` and the two
  // ceilings and nothing else, while the very same program PRINTED
  // `guardRefuse`, `guardRefuseTask`, the legacy-clock `ctlOk` and
  // `tally.unverified` in the gate table two lines above the pooled mean. And
  // `clock_run.cjs` writes its dataset BEFORE its fatal checks run, so a run
  // Chromium threw on, or whose arms built different pages, is a well-formed
  // file that reached the published mean. The filter is now the driver's own
  // exit path read back off the record, plus rf2-2rtt6.31's two-tier clause:
  // a file that does not say it is the published evidence set is not.
  //
  // DRIVEN OVER THE ROSTER, not over a hand-written list, so a gate added to
  // `GATES` without a case here fails the first assertion rather than shipping
  // as a gate nothing has ever seen refuse.

  const del = (o, k) => {
    const c = { ...o };
    delete c[k];
    return c;
  };
  const BARS = { 'h / r': ADJ, 'h / u': ADJ };
  // One deliberate corruption per gate, and one deliberate ERASURE per gate:
  // `failed` and `absent` are different faults and both must refuse.
  const CASES = [
    {
      id: 'canonical',
      data: { canonical: false, notCanonicalWhy: "the run's own verdict refused it (exit 5)" },
      failed: /NOT the published evidence set — the run's own verdict refused it \(exit 5\)/,
      erase: { data: 'canonical' },
      absent: /carries no `canonical` verdict/,
    },
    {
      id: 'page-errors',
      row: { pageErrors: ['TypeError: undefined is not a function'] },
      failed: /the page THREW during the run: TypeError/,
      erase: { row: 'pageErrors' },
      absent: /no `pageErrors` record/,
    },
    {
      id: 'guard-net',
      row: { guardRefuse: true },
      failed: /arm-order guard REFUSED this row on taskNet/,
      erase: { row: 'guardRefuse' },
      absent: /no arm-order guard verdict on taskNet/,
    },
    {
      id: 'guard-task',
      row: { guardRefuseTask: true },
      failed: /arm-order guard REFUSED this row on the published clock/,
      erase: { row: 'guardRefuseTask' },
      absent: /no arm-order guard verdict on the published clock/,
    },
    {
      id: 'canonical-dom',
      row: { parityOk: false },
      failed: /canonical-DOM gate found arms building DIFFERENT PAGES/,
      erase: { row: 'parityOk' },
      absent: /no canonical-DOM verdict was serialised/,
    },
    {
      id: 'ctl3-parity',
      row: { ctl3Parity: { ok: false } },
      failed: /three-point control's own arms built DIFFERENT PAGES/,
      erase: { row: 'ctl3Parity' },
      absent: /no three-point-control parity record/,
    },
    {
      id: 'keystroke-witness',
      row: { kbWitness: { ok: false, faults: [{ code: 'ORPHAN', why: 'an entry belongs to no key pressed' }] } },
      failed: /per-keystroke witness REFUSED/,
      erase: { row: 'kbWitness' },
      absent: /no per-keystroke witness record/,
    },
    {
      id: 'unverified',
      row: { tally: { writes: 1008, unverified: 4 } },
      failed: /4 unverified operation\(s\) of 1008/,
      erase: { row: 'tally' },
      absent: /no write-verification tally/,
    },
    {
      id: 'ceiling-net',
      row: { seam: { verdict: { ceilingBreached: true } } },
      failed: /frame-only reproducibility band exceeds the ceiling/,
      erase: { row: 'seam' },
      absent: /no frame-only band verdict/,
    },
    {
      id: 'ceiling-task',
      row: { seamTask: { ceilingBreached: true, rows: BARS } },
      failed: /band exceeds the ceiling on the published clock/,
      erase: { row: 'seamTask' },
      absent: /no published-clock band verdict/,
    },
    {
      // THE SABOTAGE, ON THE CONSUMER SIDE (rf2-8a746). `ctlScale: 140/300` is
      // the fixture form of an arm that declares the floor's page doubled and
      // builds 140 of its 300 boundaries — the run's block median collapses to
      // 0.6156x and the frozen location limits refuse it. The ERASURE case is
      // the readings themselves: a record that did not store them cannot be
      // shown to have been in control.
      id: 'check-standard',
      row: { roundsTask: fixtureRoundsTask({ ctlScale: 140 / 300 }) },
      failed: /outside the frozen location limits/,
      erase: { row: 'roundsTask' },
      absent: /no raw per-sample TaskDuration readings/,
    },
    {
      id: 'event-timing',
      row: { etVerdict: { ok: false } },
      failed: /Event-Timing witness REFUSED/,
      erase: { row: 'etVerdict' },
      absent: /no Event-Timing verdict was serialised/,
    },
    {
      id: 'adjudication',
      bars: { 'h / r': ADJ, 'h / u': UNADJ },
      failed: /carries no adjudication verdict/,
      erase: { bars: true },
      absent: /carries no adjudication verdict/,
    },
  ];

  t('every gate in the roster has a case — an unexercised gate is not a gate', () => {
    assert.deepStrictEqual(
      GATES.map((g) => g.id),
      CASES.map((c) => c.id),
      'GATES and the corruption cases must agree, in order'
    );
  });

  t('a fully compliant run IS reportable — the roster is not vacuous', () => {
    // Every assertion below would pass against a predicate that always said
    // false, so this one comes first in weight if not in order.
    assert.strictEqual(reportable(dsRow(BARS), CANON), true);
    assert.deepStrictEqual(refusals(dsRow(BARS), CANON), []);
  });

  for (const c of CASES) {
    t(`gate \`${c.id}\`: a FAILED verdict removes the run from the subset, and names itself`, () => {
      const row = dsRow(c.bars || BARS, c.row || {});
      const data = { ...CANON, ...(c.data || {}) };
      const why = refusals(row, data);
      assert.ok(
        why.some((w) => c.failed.test(w)),
        `no refusal matched ${c.failed} — got ${JSON.stringify(why)}`
      );
      assert.strictEqual(reportable(row, data), false);
    });

    t(`gate \`${c.id}\`: an ABSENT verdict removes it too — absent is not clean`, () => {
      let row = dsRow(c.erase.bars ? {} : c.bars || BARS, c.row ? {} : {});
      if (c.erase.row) row = del(row, c.erase.row);
      let data = { ...CANON };
      if (c.erase.data) data = del(data, c.erase.data);
      const why = refusals(row, data);
      assert.ok(
        why.some((w) => c.absent.test(w)),
        `no refusal matched ${c.absent} — got ${JSON.stringify(why)}`
      );
      assert.strictEqual(reportable(row, data), false);
    });
  }

  t('a row cannot vouch for the file it came from — no envelope is a refusal', () => {
    // The two-tier contract's consumer clause. A dataset that travelled out of
    // its directory, or a caller that forgot to pass the envelope, must not be
    // able to publish on the strength of the row alone.
    assert.strictEqual(reportable(dsRow(BARS)), false);
    assert.ok(refusals(dsRow(BARS)).some((w) => /carries no `canonical` verdict/.test(w)));
  });

  t('EVERY reason is reported, not the first — a run that failed four gates says four', () => {
    const why = refusals(
      dsRow(BARS, { guardRefuse: true, parityOk: false, roundsTask: fixtureRoundsTask({ ctlScale: 140 / 300 }) }),
      { canonical: false, notCanonicalWhy: 'a PARTIAL row set', design: { tare: true } }
    );
    assert.strictEqual(why.length, 4, JSON.stringify(why));
  });

  // --- AND THE WHOLE PROGRAM, END TO END, ON A FILE -------------------------
  //
  // The predicates above are pure and the program is what a reader actually
  // runs, so the refusal is demonstrated where it is claimed: over a dataset on
  // disk, by exit code. Both directions, because a checker nobody has seen say
  // yes is as useless as one nobody has seen say no.

  const fixture = (over) => ({
    label: 'fixture',
    chromium: '147.0.0.0',
    node: process.version,
    when: '2026-08-07T00:00:00.000Z',
    design: { rounds: 6, warmup: 4, samples: 10, tare: true },
    canonical: true,
    notCanonicalWhy: null,
    ...over,
    rows: [
      {
        ...dsRow({ 'hicasso / reagent-subs': ADJ }),
        granularity: [0.146],
        inPageRounds: [],
        decomposition: {
          'reagent-subs/plumb': { n: 60, task: 36, taskNet: 12, devtools: 24, script: 0.06, layout: 5 },
          'reagent-subs/floor': { n: 60, task: 360, taskNet: 280, devtools: 80, script: 0.06, layout: 40 },
          'reagent-subs/reagent-subs': { n: 60, task: 600, taskNet: 280, devtools: 320, script: 0.06, layout: 60 },
          'hicasso/hicasso': { n: 60, task: 780, taskNet: 320, devtools: 460, script: 0.06, layout: 70 },
        },
        ctlTask: { ok: true, measured: { mean: 1.9 } },
        seam: { band: 0.06, verdict: { ceilingBreached: false } },
        seamTask: { ceilingBreached: false, band: 0.05, rows: { 'hicasso / reagent-subs': ADJ } },
        bandTask: 0.05,
        bar: { 'hicasso / reagent-subs': { tared: { mean: 1.1 } } },
        inPageBar: { 'hicasso / reagent-subs': { mean: 1.2 } },
        barTask: { 'hicasso / reagent-subs': { mean: 1.3, min: 1.2, max: 1.4 } },
      },
    ],
  });

  const runProgram = (data) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-emvod-'));
    const f = path.join(dir, 'run1.json');
    fs.writeFileSync(f, JSON.stringify(data));
    const r = cp.spawnSync(process.execPath, [RJ, f], { encoding: 'utf8' });
    fs.rmSync(dir, { recursive: true, force: true });
    return { code: r.status, out: `${r.stdout}${r.stderr}` };
  };

  t('THE COMMAND: a compliant dataset regenerates its aggregate and exits 0', () => {
    const { code, out } = runProgram(fixture());
    assert.strictEqual(code, 0, out);
    assert.match(out, /reportable subset 1\.3000x n=1/);
    assert.match(out, /— reportable: every gate this dataset serialises is clean/);
  });

  t('THE COMMAND: a non-canonical dataset exits 3 and is still printed in full', () => {
    const { code, out } = runProgram(fixture({ canonical: false, notCanonicalWhy: '--no-build' }));
    assert.strictEqual(code, 3, out);
    assert.match(out, /NOT ELIGIBLE PUBLISHED EVIDENCE/);
    assert.match(out, /reportable subset: NONE/);
    // RETAINED, not erased: the run is still in the per-run table with its own
    // magnitude beside it. A refusal is about what may be QUOTED.
    assert.match(out, /;;\s+run1\s+1\.2000\s+1\.1000\s+1\.3000/);
    assert.match(out, /EXIT 3 — 1 of 1 dataset\(s\) are not eligible published evidence/);
  });

  t('THE COMMAND: a gate failure inside a canonical dataset empties the subset, not the table', () => {
    const bad = fixture();
    bad.rows[0].tally = { writes: 1008, unverified: 4 };
    const { code, out } = runProgram(bad);
    assert.strictEqual(code, 0, 'the FILE is still eligible evidence — it is the RUN that is refused');
    assert.match(out, /reportable subset: NONE/);
    assert.match(out, /4 unverified operation\(s\) of 1008/);
    assert.match(out, /;;\s+run1\s+1\.2000\s+1\.1000\s+1\.3000/);
  });

  t('requiring the readjudicator does not RUN it, which is what made this reachable', () => {
    assert.match(RJSRC, /^  GATES, adjudicated, refusals, reportable, responsivenessRegime,$/m);
    assert.match(RJSRC, /if \(require\.main === module\) \{/);
    assert.match(RJSRC, /main\(process\.argv\.slice\(2\)\)/);
  });

  t('the subset is chosen by `reportable` alone, not by a second predicate inline', () => {
    const body = RJSRC.slice(RJSRC.indexOf('function main(argv)'));
    assert.match(body, /runs\.map\(\(\{ row, data \}, i\) => \(reportable\(row, data\) \? i : -1\)\)/);
    assert.ok(
      !/\.some\(\(n\) => !bars\[n\]\.unadjudicated\)/.test(RJSRC),
      'the loose `some` rule must not survive anywhere in this file'
    );
    // `main` may still READ a bar verdict to print it — the per-run table is
    // a description, not a decision. What it may not do is quantify over the
    // bars, because that is the predicate above being written a second time.
    assert.ok(
      !/(names|Object\.keys)[^\n]*\.(some|every)\(/.test(body),
      'quantifying over a row\'s bars inside `main` is the subset predicate written twice'
    );
  });

  t('the printed verdict column reads the SAME per-bar record the subset does', () => {
    // The column used to be derived from row-wide `bandTask` while the subset
    // was derived per bar, so a row whose bars disagreed would print "clears
    // its band" for a bar the subset had just refused. That is this bead's own
    // complaint — a column and a decision disagreeing about the same run —
    // pointing the other way.
    const body = RJSRC.slice(RJSRC.indexOf('function main(argv)'));
    assert.match(body, /const barRec = \(row\.seamTask && row\.seamTask\.rows && row\.seamTask\.rows\[pair\]\)/);
    // ... and with `adjudicated`'s own token. A column reading `!!` beside a
    // subset reading `=== false` would print "clears its band" for a silent
    // bar the subset had just refused — the disagreement one field further in.
    assert.match(body, /const barUnadjudicated = barRec \? barRec\.unadjudicated !== false/);
    // and the column's UNADJUDICATED branch is taken from THAT, not from the
    // row-wide band.
    assert.match(body, /: barUnadjudicated\s*\r?\n\s*\? 'UNADJUDICATED/);
  });

  // --- THE RESPONSIVENESS REGIME, off the retained datasets (rf2-swwud) -----
  //
  // The ruling re-adjudicates the per-keystroke row from the two runs already
  // on disk — no new window, and none needed. These drive the predicate on
  // synthetic rows and then on the real ones, because "re-adjudicated from
  // disk" is a claim about files that must be checkable against those files.

  const etArm = (durations, over) => ({
    sent: 60,
    observed: durations.length,
    censored: 60 - durations.length,
    durations,
    ...over,
  });
  const frames = (n) => Array(n).fill(16);
  const kbRow = (over) => ({
    rowId: 'keystroke',
    granularity: [0.146, 0.2, 0.3],
    barTask: {
      'hicasso / reagent-subs': { mean: 0.9491, min: 0.7328, max: 1.1596 },
      'hicasso / uix-subs': { mean: 0.9968, min: 0.8059, max: 1.2172 },
      'uix-subs / reagent-subs': { mean: 0.9548, min: 0.771, max: 1.0546 },
    },
    kbWitness: {
      totals: { sent: 180, observed: 140, censored: 40 },
      perArm: {
        'hicasso/hicasso': etArm(frames(49)),
        'reagent-subs/reagent-subs': etArm(frames(46)),
        'uix-subs/uix-subs': etArm(frames(45)),
        'hicasso/ctl-50ms': etArm(Array(60).fill(48)),
      },
    },
    ...over,
  });

  t('every other row is not a responsiveness regime, and says so by returning nothing', () => {
    assert.strictEqual(responsivenessRegime({ rowId: 'M1' }), null);
    assert.strictEqual(responsivenessRegime(undefined), null);
    assert.strictEqual(responsivenessRegime({ rowId: 'M1', kbWitness: {} }), null);
  });

  t('one bucket across every observed arm IS the verdict, and the control must have moved', () => {
    const r = responsivenessRegime(kbRow());
    assert.strictEqual(r.indistinguishable, true);
    assert.strictEqual(r.frame, 16);
    assert.strictEqual(r.controlP50, 48);
    assert.strictEqual(r.controlMoved, true, 'a control that did not move is an instrument nobody saw respond');
  });

  t('THE GATE IS NOT VACUOUS: an arm in a second bucket refuses the frame statement', () => {
    // If it always said "indistinguishable", it would be an assertion rather
    // than a reading. Move one arm a bucket and the verdict must withdraw.
    const r = responsivenessRegime(
      kbRow({
        kbWitness: {
          totals: { sent: 180, observed: 140, censored: 40 },
          perArm: {
            'hicasso/hicasso': etArm(Array(49).fill(24)),
            'reagent-subs/reagent-subs': etArm(frames(46)),
            'hicasso/ctl-50ms': etArm(Array(60).fill(48)),
          },
        },
      })
    );
    assert.strictEqual(r.indistinguishable, false, 'arms in two buckets are not indistinguishable');
  });

  t('a control that did not clear the arms is FAIL — the sensitivity claim is checked, not assumed', () => {
    const r = responsivenessRegime(
      kbRow({
        kbWitness: {
          totals: null,
          perArm: { 'hicasso/hicasso': etArm(frames(49)), 'hicasso/ctl-50ms': etArm(frames(60)) },
        },
      })
    );
    assert.strictEqual(r.controlMoved, false);
  });

  t('THE RIDER is carried, not optional: the grain and the straddling bars come back with it', () => {
    const r = responsivenessRegime(kbRow());
    assert.strictEqual(r.grain, 0.146, "the run's own finest per-sample step, as the driver stored it");
    assert.strictEqual(r.diagnosticBars.length, 3);
    assert.ok(
      r.diagnosticBars.every((b) => b.straddles1),
      'every diagnostic bar straddles 1.0, which is why no magnitude is published'
    );
  });

  t('it reads the WITNESS the driver stored rather than regrouping raw entries', () => {
    // The witness owns what forms an interaction and what a censored key is.
    // A second grouping here would be a second adjudicator, which is this
    // whole file's subject.
    const body = RJSRC.slice(RJSRC.indexOf('function responsivenessRegime('));
    assert.match(body, /row && row\.kbWitness/);
    assert.ok(
      !/interactionId/.test(body.slice(0, body.indexOf('function main('))),
      'grouping entries by interactionId here would be the witness written a second time'
    );
  });

  // --- and against the retained runs themselves ----------------------------

  t('THE RULING RE-ADJUDICATED FROM DISK, and the datasets still say what it said', () => {
    const dir = path.join(__dirname, 'data', 'clock-0qj9w');
    if (!fs.existsSync(dir)) return; // datasets are retained, not required to build
    const expected = { 'run1.json': { observed: 466, censored: 74, ctl: 48 }, 'run2.json': { observed: 449, censored: 91, ctl: 56 } };
    for (const [file, want] of Object.entries(expected)) {
      const data = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
      const row = data.rows.find((r) => r.rowId === 'keystroke');
      assert.ok(row, `${file} must retain its keystroke row`);
      const r = responsivenessRegime(row);
      assert.strictEqual(r.indistinguishable, true, `${file}: every observed interaction must be one frame`);
      assert.strictEqual(r.frame, 16, `${file}: and that frame is 16 ms`);
      assert.strictEqual(r.controlP50, want.ctl, `${file}: ctl-50ms median`);
      assert.strictEqual(r.totals.observed, want.observed, `${file}: observed keys`);
      assert.strictEqual(r.totals.censored, want.censored, `${file}: censored keys`);
      assert.strictEqual(r.totals.sent, 540, `${file}: keys sent`);
      assert.ok(
        r.diagnosticBars.every((b) => b.straddles1),
        `${file}: every diagnostic bar must straddle 1.0 — no magnitude may be published from this row`
      );
    }
  });

  t('it still SELECTS no run away from the table — only from the subset', () => {
    // The file's own stated refusal, and the reason the predicate above may
    // never be used to drop a row from the per-run listing.
    assert.match(RJSRC, /It does not select runs\./);
    const body = RJSRC.slice(RJSRC.indexOf('function main(argv)'));
    assert.match(body, /runs\.forEach\(\(\{ file, row \}, i\) => \{/);
  });
}

// --- THE PRODUCER HALF: clock_run.cjs must WRITE every verdict it is gated on
//
// rf2-e87sk, the other half of rf2-emvod. The roster above refuses on ABSENT
// as well as on failed, which is right — a gate that passes when its evidence
// is missing is the fail-open this lane keeps finding — and four of its
// thirteen gates named verdicts `clock_run.cjs` COMPUTED, PRINTED and EXITED
// ON and then did not store: `canonical`, `pageErrors`, `parityOk` and
// `etVerdict`. So no dataset in the tree could be reportable on those axes,
// and a measurement window would have produced correctly-unreportable
// evidence.
//
// THE TWO CLAIMS THIS BLOCK MAKES, and they pull against each other, which is
// the point:
//
//   1. A dataset the CURRENT serialiser writes satisfies every one of the
//      thirteen gates. Without this the repair could be "the field is there
//      somewhere" rather than "the consumer accepts it".
//   2. Erase any one of those fields from that same freshly written record and
//      its gate refuses again. Without this the repair could have been made by
//      loosening the gate, which is the fail-open wearing the fix's clothes.
//
// Both are driven over `GATES` rather than over a hand-written list, so a gate
// added to the roster with no producer field fails the first assertion instead
// of shipping as a gate no dataset can satisfy — the exact fault this bead is.

{
  const { datasetFor, publication, PUBLISHED_DEPTH } = require('./clock_run.cjs');
  const { GATES, refusals, reportable } = require('./clock_readjudicate.cjs');
  const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
  const t = (what, fn) => test(`clock_run.cjs -> clock_readjudicate.cjs: ${what}`, fn);

  const ADJ = { unadjudicated: false, band: 0.21, why: 'margin 34.8% clears the band 21.4%' };
  const BARS = { 'hicasso / reagent-subs': ADJ };

  /**
   * ONE OUTCOME AS `runRow` AND `report` HAND IT TO THE WRITE PATH, at every
   * gate's passing value. Hermetic on purpose: reaching the real thing needs
   * an `:advanced` build and a headless Chromium, and no measurement window is
   * spent on a serialisation test.
   */
  const outcome = (outOver, verdictOver) => ({
    out: {
      rowId: 'bulk300',
      rounds: [],
      // THE READINGS THE CHECK STANDARD IS APPLIED TO (rf2-8a746). This used
      // to be `[]`, which was fine while every gate read a stored boolean; the
      // standard is applied to the readings, so a serialiser that dropped them
      // would produce a record no reader could certify.
      roundsTask: fixtureRoundsTask(),
      roundsLayout: [],
      inPageRounds: [],
      granularity: [0.146],
      decomposition: {
        'reagent-subs/plumb': { n: 60, task: 36, taskNet: 12, devtools: 24, script: 0.06, layout: 5 },
        'reagent-subs/floor': { n: 60, task: 360, taskNet: 280, devtools: 80, script: 0.06, layout: 40 },
        'reagent-subs/reagent-subs': { n: 60, task: 600, taskNet: 280, devtools: 320, script: 0.06, layout: 60 },
        'hicasso/hicasso': { n: 60, task: 780, taskNet: 320, devtools: 460, script: 0.06, layout: 70 },
      },
      // THE FOUR, at the producer's own internal names.
      pageErrors: [],
      armPlan: { 'ctl-3pt-2d': 200 },
      sabotage: null,
      sentKeys: null,
      eventTiming: null,
      census: null,
      kbShape: null,
      ...outOver,
    },
    verdict: {
      seam: { band: 0.06, verdict: { ceilingBreached: false } },
      seamTask: { band: 0.05, ceilingBreached: false, rows: BARS },
      tally: { writes: 1008, unverified: 0 },
      ctlVerdict: { measured: { mean: 1.9 }, inBand: 18, of: 18, allInBand: true, gating: false },
      ctl3: null,
      ctl3Net: null,
      ctl3Layout: null,
      ctl3Parity: null,
      checkStandard: { ok: true, standard: { id: 'hicasso-clock/ctl-2x-level', version: 1 }, why: null },
      constants: null,
      guardVerdict: { refuse: false },
      guardVerdictTask: { refuse: false },
      parityOk: true,
      bar: { 'hicasso / reagent-subs': { tared: { mean: 1.1 } } },
      inPageBar: { 'hicasso / reagent-subs': { mean: 1.2 } },
      barTask: { 'hicasso / reagent-subs': { mean: 1.3, min: 1.2, max: 1.4 } },
      ctlTask: { ok: true, measured: { mean: 1.9 } },
      bandTask: 0.05,
      etVerdict: null,
      kbVerdict: null,
      ...verdictOver,
    },
  });

  /** What the driver would write for a full-shape run of that outcome. */
  const produce = (outOver, verdictOver) =>
    datasetFor([outcome(outOver, verdictOver)], {
      chromium: '147.0.0.0',
      publication: publication({ depthPublished: true, tare: true }),
    });

  // --- 1. THE FOUR, BY NAME ------------------------------------------------

  t('rf2-e87sk: the four verdicts no dataset could carry are in the record', () => {
    const rec = produce();
    assert.strictEqual(rec.canonical, true, 'the file must state whether it is the published evidence set');
    assert.ok('notCanonicalWhy' in rec, 'and carry the reason seat even when there is no reason');
    assert.deepStrictEqual(rec.rows[0].pageErrors, [], 'whether the page threw must be IN the file');
    assert.strictEqual(rec.rows[0].parityOk, true, 'and whether the arms built the same page');
    assert.ok('etVerdict' in rec.rows[0], 'and the Event-Timing verdict, `null` included — null is a verdict');
  });

  t('each of the four is COPIED off the verdict, never defaulted to a passing value', () => {
    // A serialiser that wrote `pageErrors: []` unconditionally would satisfy
    // the gate on a run that threw, which is the fail-open one layer down.
    const rec = produce(
      { pageErrors: ['TypeError: undefined is not a function'] },
      { parityOk: false, etVerdict: { ok: false, predicted: 1, measured: 3 } }
    );
    assert.deepStrictEqual(rec.rows[0].pageErrors, ['TypeError: undefined is not a function']);
    assert.strictEqual(rec.rows[0].parityOk, false);
    assert.deepStrictEqual(rec.rows[0].etVerdict, { ok: false, predicted: 1, measured: 3 });
    assert.strictEqual(reportable(rec.rows[0], rec), false, 'and a record carrying them is refused');
    for (const want of [
      /the page THREW during the run/,
      /canonical-DOM gate found arms building DIFFERENT PAGES/,
      /Event-Timing witness REFUSED/,
    ]) {
      assert.ok(refusals(rec.rows[0], rec).some((w) => want.test(w)), `no refusal matched ${want}`);
    }
  });

  // --- 2. EVERY GATE, SATISFIED THEN ERASED --------------------------------
  //
  // The eraser is per gate rather than per field name because two gates read
  // one object (`seamTask` carries both the task ceiling and the bar set), and
  // a table keyed by field could not say which of them a deletion tests.

  const ERASE = {
    canonical: (rec) => delete rec.canonical,
    'page-errors': (rec) => delete rec.rows[0].pageErrors,
    'guard-net': (rec) => delete rec.rows[0].guardRefuse,
    'guard-task': (rec) => delete rec.rows[0].guardRefuseTask,
    'canonical-dom': (rec) => delete rec.rows[0].parityOk,
    'ctl3-parity': (rec) => delete rec.rows[0].ctl3Parity,
    'keystroke-witness': (rec) => delete rec.rows[0].kbWitness,
    unverified: (rec) => delete rec.rows[0].tally,
    'ceiling-net': (rec) => delete rec.rows[0].seam,
    'ceiling-task': (rec) => delete rec.rows[0].seamTask.ceilingBreached,
    // THE PRODUCER FIELD FOR THE CHECK STANDARD IS THE READINGS (rf2-8a746),
    // not the driver's stored verdict — the standard is versioned data a
    // reader applies, so what the serialiser must not lose is what it is
    // applied to.
    'check-standard': (rec) => delete rec.rows[0].roundsTask,
    'event-timing': (rec) => delete rec.rows[0].etVerdict,
    adjudication: (rec) => delete rec.rows[0].seamTask.rows,
  };

  t('every gate in the roster has a producer field — a gate no dataset can satisfy is this bead', () => {
    assert.deepStrictEqual(GATES.map((g) => g.id), Object.keys(ERASE), 'GATES and the erasures must agree, in order');
  });

  t('THE WHOLE ROSTER IS SATISFIABLE by a freshly written dataset — all thirteen', () => {
    const rec = produce();
    assert.deepStrictEqual(refusals(rec.rows[0], rec), [], 'a full-shape clean run must clear every gate');
    assert.strictEqual(reportable(rec.rows[0], rec), true);
  });

  for (const g of GATES) {
    t(`gate \`${g.id}\`: the record SATISFIES it, and erasing the field REFUSES again`, () => {
      // A ROW GATE RECEIVES THE ENVELOPE TOO (rf2-8a746) — the design it was
      // taken under decides what its readings mean.
      const ask = (rec) => (g.scope === 'dataset' ? g.why(rec) : g.why(rec.rows[0], rec));
      const green = produce();
      assert.strictEqual(ask(green), null, `the serialiser must write what \`${g.id}\` reads`);

      const red = produce();
      ERASE[g.id](red);
      const why = ask(red);
      assert.ok(
        typeof why === 'string' && why.length > 0,
        `erasing \`${g.id}\`'s field must refuse — absent is not clean, and a fix that made it clean ` +
          `would be the fail-open this gate exists to prevent`
      );
      assert.strictEqual(reportable(red.rows[0], red), false, 'and the run may not be pooled into the published mean');
    });
  }

  // --- 3. AND THE PROGRAM A READER ACTUALLY RUNS ---------------------------

  t('THE COMMAND accepts a freshly written dataset and pools it — end to end, on a file', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-e87sk-'));
    const f = path.join(dir, 'run1.json');
    fs.writeFileSync(f, JSON.stringify(produce()));
    const r = cp.spawnSync(process.execPath, [RJ, f], { encoding: 'utf8' });
    fs.rmSync(dir, { recursive: true, force: true });
    const out = `${r.stdout}${r.stderr}`;
    assert.strictEqual(r.status, 0, out);
    assert.match(out, /reportable subset 1\.3000x n=1/);
    assert.match(out, /— reportable: every gate this dataset serialises is clean/);
  });

  // --- 4. THE SHAPE VERDICT ------------------------------------------------
  //
  // `publication` answers "is this file the published evidence set", and the
  // one thing it must never do is answer yes by default. Every narrowing gets
  // a case and each names itself, because a refusal without a reason is
  // indistinguishable from a run that was selected away.

  const full = { rowsOnly: null, noBuild: false, depthPublished: true, tare: true, sabotage: null };

  t('a full-shape run IS canonical — the verdict is not vacuous', () => {
    assert.deepStrictEqual(publication(full), { canonical: true, why: null });
  });

  t('the published depth is the design every table on this lane was taken at', () => {
    assert.deepStrictEqual(PUBLISHED_DEPTH, { rounds: 6, warmup: 4, samples: 10 });
  });

  for (const [what, over, why] of [
    ['a PARTIAL row set', { rowsOnly: 'keystroke' }, /a PARTIAL row set \(HCLOCK_ONLY=keystroke\)/],
    ['--no-build', { noBuild: true }, /--no-build/],
    ['an overridden depth', { depthPublished: false }, /an OVERRIDDEN design depth/],
    ['the tare off', { tare: false }, /the tare DISABLED \(HCLOCK_TARE=off\)/],
    ['a falsification run', { sabotage: 140 }, /a FALSIFICATION run \(HCLOCK_CTL3_SABOTAGE=140\)/],
  ]) {
    t(`${what} is NOT the published evidence set, and the file says why`, () => {
      const p = publication({ ...full, ...over });
      assert.strictEqual(p.canonical, false);
      assert.match(p.why, why);
      // and the consumer refuses it by that same sentence.
      const rec = { ...produce(), canonical: p.canonical, notCanonicalWhy: p.why };
      assert.strictEqual(reportable(rec.rows[0], rec), false);
      assert.ok(refusals(rec.rows[0], rec).some((w) => /NOT the published evidence set/.test(w)));
    });
  }

  t('an absent shape record is NOT canonical either — the producer fails closed too', () => {
    assert.strictEqual(publication(undefined).canonical, false);
    assert.match(publication({}).why, /an OVERRIDDEN design depth/);
  });

  t('every narrowing is named at once, not the first — the same rule the refusals follow', () => {
    const p = publication({ rowsOnly: 'M1', noBuild: true, depthPublished: false, tare: false, sabotage: 140 });
    assert.strictEqual(p.why.split('; ').length, 5, p.why);
  });

  // --- 5. THE WIRING, so none of the above is decorative -------------------

  t('the driver writes what `datasetFor` returns, and derives the shape from its own knobs', () => {
    const SRC = fs.readFileSync(path.join(__dirname, 'clock_run.cjs'), 'utf8');
    const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));
    assert.match(MAIN, /const pub = publication\(runShape\(\)\);/);
    assert.match(MAIN, /datasetFor\(outcomes, \{ chromium: version, publication: pub \}\)/);
    // The serialiser lives OUTSIDE `main` — it names refusal fields, and
    // inside `main` those names are both invariant-breaking and undrivable.
    assert.ok(!/pageErrors: o\.out\.pageErrors/.test(MAIN), 'the serialiser must not have grown back inside `main`');
    assert.match(SRC.slice(0, SRC.indexOf('async function main()')), /function datasetFor\(outcomes, meta\) \{/);
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

  // --- rf2-d2tzk: a LIMIT is not a FAULT, and the exit code says which -----
  //
  // The bulk row's floor is one write under one clock and reads 1.0 to 2.0
  // ticks of a measured 0.1 ms grain, so `mask-below-grain` (hd8_rows.cljs)
  // withdraws every figure normalised by it — the same marker SHAPE the DOM
  // read-back uses and a different meaning. Nothing failed; a magnitude was
  // never available. Exiting non-zero on it would exit non-zero on every run.
  const belowGrain = () =>
    run({
      summary: {
        'write-bulk': {
          vsFloor: {
            floor: { unpublished: 'below-clock-grain', arms: ['floor'], tick: 0.1, quanta: 1 },
            'reagent-slim': { unpublished: 'below-clock-grain', arms: ['floor'], tick: 0.1, quanta: 1 },
          },
          headToHead: { 'donor-r1 vs uix': { min: 1.185, max: 1.313 } },
        },
      },
      correction: { 'write-bulk': { verdict: 'moot', reason: 'no-published-figure-bears-it' } },
    });

  t('a window beneath the clock grain exits 0 — it is a limit, not a fault', () => {
    const v = verdict(summarise({ runs: [belowGrain()] }));
    assert.strictEqual(v.code, 0, 'nothing failed; the instrument could not resolve a magnitude');
    assert.match(v.lines.join('\n'), /NO REPORTABLE MAGNITUDE/);
    assert.match(v.lines.join('\n'), /floor at 1 tick\(s\) of 0\.1 ms/);
    assert.ok(!/REFUSED/.test(v.lines.join('\n')), 'and it must not read as a refusal');
  });

  t('a `moot` yield correction is not a refusal', () => {
    assert.strictEqual(verdict(summarise({ runs: [run({ correction: { r: { verdict: 'moot' } } })] })).code, 0);
  });

  t('the grain limit never swallows a real refusal standing beside it', () => {
    const v = verdict(summarise({ runs: [belowGrain(), readBack()] }));
    assert.strictEqual(v.code, 3, 'the read-back still decides the exit');
    const all = v.lines.join('\n');
    assert.match(all, /NO REPORTABLE MAGNITUDE/);
    assert.match(all, /never reached the DOM/);
  });

  t('the two masks are told apart by their reason, not by their shape', () => {
    // Both arrive as `{unpublished: …}` on the same channel. If the driver
    // ever partitions them by anything other than the reason code, a fault
    // starts exiting 0.
    assert.match(SRC, /v\.unpublished === 'below-clock-grain'/);
    assert.match(SRC, /instrumentLimited`? is DELIBERATELY absent/);
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
    // The pin is on `pageErrors` REACHING the returned record, not on the
    // shape of the record around it: this line grew a `clock` field for
    // rf2-d2tzk and a pin that spelled every neighbour out went red for a
    // change that could not touch what it is about.
    assert.match(SRC, /lines, pageErrors,\s*\};/);
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

// --- THE ADJECTIVE, which is what both instrument errors hid behind ---------
//
// rf2-yd52q's merged-PR audit (#7363) named the mislabel the root cause of
// BOTH instrument errors this programme has suffered. `taskNet` is
// `TaskDuration` less `DevToolsCommandDuration`, and because every arm's
// operation runs inside `page.evaluate` — a protocol command Chromium bills
// whole, page script included — the subtraction removes the operation's own
// script. So `taskNet` is FRAME-ONLY and nearer the in-page window's
// COMPLEMENT than its superset, and calling it "frame-inclusive" is not loose
// wording but a false statement about which quantity is on the row. It
// survived a full audit because the driver printed the two clocks' RATIO and
// never their absolutes, so the one tell that needed no arithmetic — a
// substrate arm's in-page absolute EXCEEDING its `taskNet` absolute — was
// never on screen.
//
// A one-time sweep does not hold that. This does: no line of a
// `Performance.getMetrics` driver that is not WHOLLY a comment may carry the
// adjective. Prose may — and must — discuss the mislabel; a printed LABEL,
// an identifier or a serialised key may not carry it, because those are what
// a reader takes the row's quantity from.
//
// THE ROSTER IS THE THREE CDP DRIVERS AND DELIBERATELY NOT THE LANE.
// `chrome_run.cjs` reports an in-page `performance.now()` span that closes
// after a `requestAnimationFrame` + `setTimeout`, which genuinely does span
// the frame. Its label is accurate and banning the word there would trade a
// true statement for a rule.
{
  const CDP_DRIVERS = [
    path.join(__dirname, 'clock_run.cjs'),
    path.join(__dirname, 'hd8_clock_run.cjs'),
    path.join(__dirname, 'shapes', 'census_clock_run.cjs'),
  ];
  // Whole-line comments only. A trailing `//` is not stripped, so a label
  // followed by a comment cannot smuggle the adjective past this, and neither
  // can a `//` inside a string literal truncate a line out of the search.
  const code = (src) =>
    src
      .split('\n')
      .map((line, i) => [i + 1, line])
      .filter(([, line]) => !/^\s*\/\//.test(line));

  for (const file of CDP_DRIVERS) {
    const name = path.relative(__dirname, file).replace(/\\/g, '/');
    test(`${name}: the banked clock is never LABELLED "frame-inclusive"`, () => {
      const hits = code(fs.readFileSync(file, 'utf8')).filter(([, line]) => /frame-inclusive/i.test(line));
      assert.deepStrictEqual(
        hits.map(([n, line]) => `${n}: ${line.trim()}`),
        [],
        `${name} labels a reading "frame-inclusive" outside prose. ` +
          `\`taskNet\` is frame-ONLY — name the window by what it measures.`
      );
    });
  }

  test('the adjective guard is not vacuous — it refuses a label and passes prose', () => {
    // Both halves, because a guard that cannot be shown to refuse proves
    // nothing, and one that refuses the correcting sentence would be deleted
    // by the first person who had to write it.
    const label = "console.log(`;; clock  frame-inclusive ${x}x`);";
    const prose = '// a frame-ONLY clock, not a frame-inclusive one — see rf2-yd52q';
    assert.strictEqual(code(label).length, 1, 'a printed label is code, and must be searched');
    assert.match(code(label)[0][1], /frame-inclusive/);
    assert.deepStrictEqual(code(prose), [], 'a whole-line comment is prose, and must not be');
  });
}

// --- rf2-8a746: THE CHECK STANDARD, AND WHAT MAY PUBLISH A MAGNITUDE ---------
//
// The 2026-08-07 ruling retired two rules at once and they are separate
// defects, so they get separate pins.
//
//   THE THREE-POINT CONTROL, retired as a GATE — not re-sited. Its prediction
//   is mis-derived on a clock that is not affine in the dirty set and its
//   denominator sits ~2 sigma from zero, which is the Fieller ratio problem;
//   it refused 42 of 42 bulk row-runs across two independent quiet-box
//   ensembles. It still prints, labelled non-gating.
//
//   THE ALL-BLOCKS STRICT RULE, retired WITH it and for its own reason: the
//   arithmetic is `p^18`, so a control fully MEETING its premise passes 4 of
//   42 runs at an 83.5% per-block rate. Swapping controls while keeping "every
//   block" would not have unblocked anything.
//
// What replaced them: a level-denominated, empirically calibrated, VERSIONED
// check standard as the gate, and a run-preserving effect-size interval as the
// publication rule. Both are pinned here, and the interval is pinned as a
// PROCEDURE — the corpus case below asserts that a verdict is well formed and
// that the 42 committed row-runs publish no magnitude, which is the ruling's
// own fence, not a preferred answer.
{
  const {
    STANDARD, checkStandard, checkStandardSelfTest, classOf,
  } = require('./clock_check_standard.cjs');
  const {
    checkStandardFor, pairedLogRatios, effectInterval, effectVerdict, EFFECT, reportable, refusals,
  } = require('./clock_readjudicate.cjs');
  const CLOCKSRC = fs.readFileSync(path.join(__dirname, 'clock_run.cjs'), 'utf8');
  const RJSRC2 = fs.readFileSync(path.join(__dirname, 'clock_readjudicate.cjs'), 'utf8');
  const t = (what, fn) => test(`rf2-8a746: ${what}`, fn);
  const BULK = 'bulk300';

  // The same synthetic world the standard's own fixtures use: a floor sample
  // is `W + c`, `ctl-2x` builds `P` times the page, so a block reads
  // `(P*W + c)/(W + c)`.
  const W = 3.0;
  const C = 1.1628;
  const blocksAt = (P, jitter) =>
    Array.from({ length: 18 }, (_, i) => (P * W + C + (jitter || 0) * (i % 2 ? -1 : 1) * (1 + (i % 3) / 3)) / (W + C));

  // --- 1. THE STANDARD IS DATA, AND IT SAYS WHICH DATA ----------------------

  t("the standard's own fixtures pass, every case — a standard nobody has seen refuse is not one", () => {
    const { checks } = checkStandardSelfTest();
    assert.ok(checks.length >= 10, `expected the standard's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad.map((c) => `${c.name}: ${c.detail}`), []);
  });

  t('the check standard lands as DATA — versioned, with a frozen centre and frozen limits', () => {
    // Criterion 2's first half. The point of freezing it as data is that
    // recalibrating is editing a file and bumping a version, so the version
    // has to be a real number and the limits have to live in the JSON rather
    // than in the code that reads it.
    assert.ok(Number.isInteger(STANDARD.version) && STANDARD.version >= 1, JSON.stringify(STANDARD.version));
    assert.strictEqual(STANDARD.ruling, 'rf2-8a746');
    const bulk = STANDARD.classes.bulk;
    assert.strictEqual(bulk.calibrated, true);
    assert.strictEqual(typeof bulk.centre, 'number');
    assert.strictEqual(bulk.location.limits.length, 2);
    assert.ok(bulk.location.limits[0] < bulk.centre && bulk.centre < bulk.location.limits[1], 'the limits must bracket the centre');
    assert.strictEqual(typeof bulk.dispersion.limit, 'number');
    assert.ok(bulk.dispersion.limit > 0);
    // and the file, not the reader, is where they live
    const v = checkStandard(blocksAt(2, 0.05), BULK);
    assert.deepStrictEqual(v.location.limits, bulk.location.limits);
    assert.strictEqual(v.dispersion.limit, bulk.dispersion.limit);
    assert.strictEqual(v.location.centre, bulk.centre);
    // the JSON carries the source code no literal here duplicates
    assert.ok(
      !new RegExp(String(bulk.centre)).test(require('node:fs').readFileSync(path.join(__dirname, 'clock_check_standard.cjs'), 'utf8')),
      'the frozen centre must not also be a literal in the module that reads it'
    );
  });

  t('the centre is EMPIRICAL and the standard says so — 2.00x is arithmetic, not a reading', () => {
    // The ruling's own reason for not reusing ctl-2x's literal prediction:
    // asserting a theoretical value against a non-affine clock is the mistake
    // being retired.
    assert.ok(Math.abs(STANDARD.classes.bulk.centre - 2.0) > 0.2, 'the frozen centre is nowhere near 2.00x');
    assert.match(STANDARD.notAPrediction, /EMPIRICAL/);
    assert.ok(Array.isArray(STANDARD.recalibrateOn) && STANDARD.recalibrateOn.length >= 3, 'a check standard states when it must be recalibrated');
    const prov = STANDARD.classes.bulk.provenance;
    assert.strictEqual(prov.rowRuns, 42);
    assert.deepStrictEqual(prov.datasets, ['data/clock-emvod/run1-8.json', 'data/clock-w3yxd/run1-6.json']);
    // v1 said it was seeded from the runs it was quoted against; v3 replaced
    // that admission with the measurement it was waiting for (rf2-c1974).
    assert.match(prov.independence, /SESSION LEVEL/i, 'the independence field states the level actually achieved');
    assert.ok(prov.holdOut, 'and carries the hold-out it was measured by');
  });

  // --- 2. THE SABOTAGE FIXTURE (criterion 2) --------------------------------

  t('THE SABOTAGE: an arm building 140 of the 300 boundaries it declares doubled is REFUSED', () => {
    const healthy = checkStandard(blocksAt(2, 0.05), BULK);
    assert.strictEqual(healthy.ok, true, 'the healthy world must pass, or every refusal below proves nothing');
    const sabotaged = checkStandard(blocksAt(140 / 300, 0.05), BULK);
    assert.strictEqual(sabotaged.ok, false);
    assert.strictEqual(sabotaged.location.ok, false, 'refused on LOCATION');
    assert.strictEqual(sabotaged.dispersion.ok, true, 'and not swept up by the dispersion term');
    assert.match(sabotaged.why, /outside the frozen location limits/);
  });

  t('and the sabotage reaches the consumer — a dataset carrying it may not be pooled', () => {
    // The fixture is only worth its name if it refuses where a run is
    // actually adjudicated, so it is driven through `reportable` on a record
    // shaped as `clock_run.cjs` writes one.
    const CANON = { canonical: true, notCanonicalWhy: null, design: { tare: true } };
    const ADJ = { unadjudicated: false, why: 'clears' };
    const row = (rt) => ({
      rowId: 'bulk300', pageErrors: [], guardRefuse: false, guardRefuseTask: false, parityOk: true,
      ctl3Parity: null, kbWitness: null, tally: { writes: 10, unverified: 0 }, etVerdict: null,
      roundsTask: rt, seam: { verdict: { ceilingBreached: false } },
      seamTask: { ceilingBreached: false, band: 0.2, rows: { 'hicasso / reagent-subs': ADJ } },
    });
    assert.strictEqual(reportable(row(fixtureRoundsTask()), CANON), true, 'the clean record must still pool');
    const bad = row(fixtureRoundsTask({ ctlScale: 140 / 300 }));
    assert.strictEqual(reportable(bad, CANON), false);
    assert.ok(refusals(bad, CANON).some((w) => /outside the frozen location limits/.test(w)), JSON.stringify(refusals(bad, CANON)));
  });

  t('the DISPERSION term refuses on its own — a box that could not reproduce its own work', () => {
    const noisy = checkStandard(blocksAt(2, 1.4), BULK);
    assert.strictEqual(noisy.ok, false);
    assert.strictEqual(noisy.location.ok, true, 'the centre is untouched — this is the other term');
    assert.strictEqual(noisy.dispersion.ok, false);
    assert.match(noisy.why, /robust scale .* exceeds the frozen dispersion limit/);
  });

  // --- 3. THE ALL-BLOCKS RULE IS GONE, EVERYWHERE ON THIS INSTRUMENT --------

  t('criterion 4: no verdict on this instrument is "every block inside the band" any more', () => {
    const clockMod = require('./clock_run.cjs');
    const { ctl3Verdict } = clockMod;
    // `controlVerdict` is not exported, and that is the point: it describes a
    // band, it takes no decision, and a test able to reach it would be a
    // reader able to reach it.
    assert.ok(!('controlVerdict' in clockMod), 'a description is not part of the decision surface');
    // The three-point statistic no longer offers an `ok` to read ...
    const D = [1, 100, 200];
    const plan = ['ctl-d1', 'ctl-d100', 'ctl-d200'].map((id, i) => ({ id, dirty: D[i], ctl3: true, ctl3Witness: false, cells: 300 }));
    const rs = [];
    for (let r = 0; r < 3; r++) {
      const per = {};
      for (const seg of FIXTURE_SEGMENTS) {
        per[seg] = { 'ctl-d1': [3.506], 'ctl-d100': [4.1], 'ctl-d200': [4.694], floor: [5.3], plumb: [0.7] };
      }
      rs.push(per);
    }
    const v = ctl3Verdict(rs, plan, 0.25);
    assert.ok(!('ok' in v), 'a retired gate may not keep a field called `ok` — that is how one grows back');
    assert.strictEqual(v.gating, false);
    assert.strictEqual(typeof v.premiseMet, 'boolean');
    assert.strictEqual(typeof v.allInBand, 'boolean', 'the retired rule survives as a DESCRIPTION, named for what it is');
    assert.match(v.rule, /DIAGNOSTIC \/ NON-GATING \(rf2-8a746\)/);
  });

  t('criterion 4: the ^18 semantics are gone from both programs, and the survivor says why', () => {
    // Grepped rather than reasoned about, because the ruling asks for the
    // SEMANTICS to be gone rather than one call site. The one `strict — EVERY`
    // left in the driver is `keystroke`'s fixed-work sensitivity floor, which
    // is a one-sided threshold with a 10 ms margin on a 50 ms burn and carries
    // no `p^n` at all; it is annotated in place, and rf2-swwud's regime
    // depends on it.
    const survivors = CLOCKSRC.split('\n')
      .map((line, i) => [i + 1, line])
      .filter(([, line]) => /strict — EVERY/.test(line));
    assert.deepStrictEqual(
      survivors.map(([, l]) => l.trim()),
      ["rule: 'strict — EVERY segment-round (a one-sided sensitivity floor, not a tolerance band)',"],
      'the only surviving every-block rule is the 50 ms sensitivity floor'
    );
    assert.match(CLOCKSRC, /THIS ONE KEEPS ITS EVERY-BLOCK RULE, and the reason is worth stating/);
    assert.ok(!/strict — EVERY/.test(RJSRC2), 'and the readjudicator carries none at all');
  });

  t('criterion 4: the replacement states its error rates in the code comment', () => {
    // Both of them, distinctly — the ruling's own term is that the tolerance
    // band and the run-rejection rule get DIFFERENT calibrated rates.
    const rates = STANDARD.classes.bulk.errorRates;
    assert.match(rates.runRejectionNominal, /0\.4% per run/);
    assert.match(rates.runRejectionEmpirical, /0 of 42/);
    assert.match(rates.toleranceBandPerBlock, /8\.2% of blocks/);
    assert.match(rates.retiredRuleForComparison, /4 of 42/);
    assert.match(rates.retiredRuleForComparison, /0\.918\^18/);
    assert.match(CLOCKSRC, /`0\.835\^18 = 3\.9%`/, 'the driver states the retired rule\'s arithmetic where it was removed');
    assert.match(RJSRC2, /0\.835\^18 = 3\.9%/);
  });

  t('criterion 4: a run with out-of-band blocks now PASSES if its location and dispersion hold', () => {
    // The inversion, which is the whole unblocking: the retired rule refused
    // this run and the calibrated one does not, while the band still REPORTS
    // the blocks it refused for.
    const xs = blocksAt(2, 0.05);
    xs[0] = 1.28;
    xs[1] = 2.19;
    const v = checkStandard(xs, BULK);
    assert.strictEqual(v.ok, true);
    assert.ok(v.tolerance.inBand < v.tolerance.of, 'blocks the retired rule would have refused for');
    assert.strictEqual(v.tolerance.gating, false);
  });

  // --- 4. NO VERDICT PATH CONSULTS THE THREE-POINT STATISTIC (criterion 1) --

  t('criterion 1: neither program reads the three-point statistic into a decision', () => {
    // Source-level, because the strongest form of "it does not gate" is that
    // the name a gate would read does not exist. `ctl3Parity` stays — it is a
    // canonical-DOM refusal about whether the control's own arms built one
    // page, which rf2-8a746 leaves in the UNCHANGED list.
    assert.ok(!/ctl3\.ok|ctl3Layout\.ok|ctl3Net\.ok/.test(CLOCKSRC.replace(/^\s*\/\/.*$/gm, '')), 'the driver reads no ctl3 verdict');
    assert.ok(!/r\.ctl3\b(?!Parity)/.test(RJSRC2.replace(/^\s*\/\/.*$/gm, '')), 'the readjudicator reads no ctl3 field but parity');
    assert.match(CLOCKSRC, /const ctlBad = \(o\) => !\(o\.verdict\.checkStandard && o\.verdict\.checkStandard\.ok\);/);
  });

  t('criterion 1: and behaviourally — flipping the three-point record changes no verdict', () => {
    const CANON = { canonical: true, notCanonicalWhy: null, design: { tare: true } };
    const ADJ = { unadjudicated: false, why: 'clears' };
    const base = {
      rowId: 'bulk300', pageErrors: [], guardRefuse: false, guardRefuseTask: false, parityOk: true,
      ctl3Parity: null, kbWitness: null, tally: { writes: 10, unverified: 0 }, etVerdict: null,
      roundsTask: fixtureRoundsTask(), seam: { verdict: { ceilingBreached: false } },
      seamTask: { ceilingBreached: false, band: 0.2, rows: { 'hicasso / reagent-subs': ADJ } },
    };
    const failing = { premiseMet: false, measured: { p50: 1.2 }, ok: false };
    const passing = { premiseMet: true, measured: { p50: 2.01 }, ok: true };
    assert.strictEqual(reportable({ ...base, ctl3: failing }, CANON), true, 'a failing three-point record refuses nothing');
    assert.strictEqual(reportable({ ...base, ctl3: passing }, CANON), true);
    assert.strictEqual(reportable({ ...base, ctl3: null }, CANON), true);
    // and it cannot rescue a run the standard refused, either
    const sab = { ...base, roundsTask: fixtureRoundsTask({ ctlScale: 140 / 300 }) };
    assert.strictEqual(reportable({ ...sab, ctl3: passing }, CANON), false, 'nor may it vouch for one');
  });

  t('criterion 1: the printout is relabelled DIAGNOSTIC / NON-GATING and no re-siting code landed', () => {
    assert.match(CLOCKSRC, /THREE-POINT STATISTIC \[DIAGNOSTIC, NON-GATING — rf2-8a746\]/);
    assert.match(CLOCKSRC, /RETIRED {2}this statistic refuses nothing/);
    // No re-siting: the ruling says the points are not to be moved, so the
    // page's declared dirty counts are still the only source of the
    // prediction and no alternative siting is computed anywhere.
    assert.ok(!/100\s*\/\s*200\s*\/\s*300|resite|reSite/i.test(CLOCKSRC.replace(/^\s*\/\/.*$/gm, '')), 'no re-siting code');
  });

  t('the falsification knob survived the retirement — it refuses on its own now', () => {
    // The coupling that retiring a gate would have silently broken: the knob
    // used to refuse THROUGH the three-point control.
    const { reportability } = require('./clock_run.cjs');
    const clean = [{ rowId: 'bulk300', ctlOk: true, ctlNote: '', adjudicable: true }];
    assert.strictEqual(reportability(clean).code, 0, 'the same rows without the knob exit 0');
    const v = reportability(clean, { sabotage: 140 });
    assert.strictEqual(v.code, 1, 'a falsification run may not exit 0 however clean its gates');
    assert.match(v.lines[0], /HCLOCK_CTL3_SABOTAGE=140 WAS SET/);
    assert.strictEqual(v.lines[v.lines.length - 1], '[clock] REPORTABLE: none.');
  });

  // --- 5. THE PUBLICATION RULE (criterion 3) --------------------------------

  t('criterion 3: the interval is run-preserving — outer RUNS resampled before inner ROUNDS', () => {
    // The load-bearing property, asserted rather than described. Five runs
    // whose ROUNDS are identical within a run and whose runs differ: all the
    // variance is BETWEEN runs, so a bootstrap that pooled the 30 rounds and
    // ignored the run structure would return an interval roughly sqrt(30/5)
    // times too narrow. The hierarchical one must see the whole of it.
    const consts = [0.9, 0.95, 1.0, 1.05, 1.1].map(Math.log);
    const runs = consts.map((c) => Array(6).fill(c));
    const iv = effectInterval(runs);
    assert.strictEqual(iv.runs, 5);
    const width = iv.hi - iv.lo;
    assert.ok(width > 0.05, `a between-run spread of 20% must reach the interval, got width ${width}`);
    // ... and the inner resample is real too: with rounds that differ inside a
    // run and runs that do not, the interval is still non-degenerate.
    const inner = Array.from({ length: 5 }, () => [0.9, 0.95, 1.0, 1.05, 1.1, 1.0].map(Math.log));
    assert.ok(effectInterval(inner).hi - effectInterval(inner).lo > 0, 'inner resampling must contribute');
    // and identical runs of identical rounds collapse to a point
    const flat = Array.from({ length: 5 }, () => Array(6).fill(Math.log(1.2)));
    const fv = effectInterval(flat);
    assert.ok(Math.abs(fv.hi - fv.lo) < 1e-9 && Math.abs(fv.point - 1.2) < 1e-9, 'no variance, no interval width');
  });

  t('criterion 3: the interval is REPRODUCIBLE — a seeded bootstrap, stated in the output', () => {
    const runs = [0.9, 0.95, 1.0, 1.05, 1.1].map((c) => Array(6).fill(Math.log(c)));
    assert.deepStrictEqual(effectInterval(runs), effectInterval(runs), 'two runs of the same program agree to the last place');
    assert.strictEqual(EFFECT.seed, 20260807);
    assert.match(EFFECT.method, /Kalibera & Jones 2013/);
    assert.match(EFFECT.method, /outer RUNS resampled before inner ROUNDS/);
  });

  t('criterion 3: the WHOLE interval must clear the threshold, and the effect must clear the band', () => {
    // Both conditions and neither alone, driven at the boundary in each
    // direction. A lower ratio is faster: these are times.
    const below = { runs: 8, rounds: [6], point: 0.6, lo: 0.55, hi: 0.65, draws: 1, seed: 1 };
    assert.strictEqual(effectVerdict(below, 10).publishes, true, 'wholly below 1.0 and a 40% effect over a 10% band');
    assert.match(effectVerdict(below, 10).verdict, /MAGNITUDE PUBLISHABLE/);
    assert.strictEqual(effectVerdict(below, 50).publishes, false, 'the same interval inside a 50% band publishes nothing');
    assert.match(effectVerdict(below, 50).why, /an effect inside the band is a difference this instrument cannot resolve/);
    const straddles = { runs: 8, rounds: [6], point: 0.99, lo: 0.9, hi: 1.05, draws: 1, seed: 1 };
    assert.strictEqual(effectVerdict(straddles, 1).publishes, false, 'an interval containing parity publishes nothing');
    assert.match(effectVerdict(straddles, 1).verdict, /INSTRUMENT-LIMITED/);
    const kill = { runs: 8, rounds: [6], point: 1.9, lo: 1.7, hi: 2.1, draws: 1, seed: 1 };
    assert.strictEqual(effectVerdict(kill, 10).publishes, true);
    assert.match(effectVerdict(kill, 10).verdict, /ARCHITECTURE-KILL/);
    const nearKill = { runs: 8, rounds: [6], point: 1.5, lo: 1.4, hi: 1.6, draws: 1, seed: 1 };
    assert.strictEqual(effectVerdict(nearKill, 10).publishes, false, 'an interval straddling 1.5 is not a kill');
    // an unrecorded band is not a clear band
    assert.strictEqual(effectVerdict(below, NaN).publishes, false, 'no band recorded is not a band cleared');
    // and two runs are not an ensemble
    assert.strictEqual(effectVerdict({ ...below, runs: 2 }, 1).publishes, false);
    assert.match(effectVerdict({ ...below, runs: 2 }, 1).why, /is not an ensemble/);
    assert.strictEqual(effectVerdict(null, 1).publishes, false, 'no interval is not a pass');
  });

  // --- 6. AND OVER THE COMMITTED 42-RUN CORPUS ------------------------------
  //
  // THE TEST PINS THE PROCEDURE, NOT THE ANSWER. What is asserted is that
  // every pair of every bulk row comes back with a WELL-FORMED verdict
  // carrying its own reason, and that none of them publishes a magnitude —
  // which is rf2-8a746's own fence rather than a preferred result: the 42
  // committed row-runs are calibration and diagnostic evidence and are NOT
  // retroactively promoted. If the procedure ever published from them this
  // test fails, and that failure is the finding.

  const CORPORA = [
    { dir: 'clock-emvod', runs: 8 },
    { dir: 'clock-w3yxd', runs: 6 },
  ];
  const BULK_ROWS = ['bulk300', 'bulk100', 'narrow'];
  const PAIRS_8a746 = ['hicasso / reagent-subs', 'hicasso / uix-subs', 'uix-subs / reagent-subs'];

  t('the committed corpus is 42 bulk row-runs, and every one is IN CONTROL under the standard', () => {
    let n = 0;
    let inControl = 0;
    for (const { dir } of CORPORA) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return; // datasets are retained, not required to build
      for (const f of fs.readdirSync(d)) {
        const data = JSON.parse(fs.readFileSync(path.join(d, f), 'utf8'));
        for (const row of data.rows.filter((r) => BULK_ROWS.includes(r.rowId))) {
          n += 1;
          if (checkStandardFor(row, data).ok) inControl += 1;
        }
      }
    }
    assert.strictEqual(n, 42, 'the corpus rf2-8a746 calibrated from');
    // The consistency check the standard's own provenance calls NOT
    // INDEPENDENT: v1 was seeded from these medians, so 42 of 42 is what it
    // must say, and a drift here means the limits and the corpus have parted.
    assert.strictEqual(inControl, 42, `the frozen limits must still admit their own baseline — got ${inControl}`);
  });

  t('criterion 3: every pair of every bulk row gets a stated verdict over the committed corpus', () => {
    for (const { dir } of CORPORA) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return;
      const datasets = fs.readdirSync(d).map((f) => JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')));
      for (const rowId of BULK_ROWS) {
        const rows = datasets.map((data) => ({ data, row: data.rows.find((r) => r.rowId === rowId) })).filter((x) => x.row);
        const pooled = rows.filter(({ row, data }) => reportable(row, data));
        for (const pair of PAIRS_8a746) {
          const iv = effectInterval(pooled.map(({ row }) => pairedLogRatios(row, pair)).filter(Boolean));
          const bands = pooled.map(({ row }) => row.seamTask.band).filter(Number.isFinite).map((b) => b * 100);
          const ev = effectVerdict(iv, bands.length ? Math.max(...bands) : NaN);
          assert.ok(typeof ev.why === 'string' && ev.why.length > 0, `${dir}/${rowId}/${pair}: a verdict must carry its reason`);
          assert.ok(
            /^(INSTRUMENT-LIMITED|MAGNITUDE PUBLISHABLE|ARCHITECTURE-KILL)/.test(ev.verdict),
            `${dir}/${rowId}/${pair}: unrecognised verdict ${ev.verdict}`
          );
          assert.ok(iv, `${dir}/${rowId}/${pair}: the corpus must yield an interval to adjudicate`);
          assert.ok(iv.lo <= iv.point && iv.point <= iv.hi, `${dir}/${rowId}/${pair}: the point must lie inside its own interval`);
          assert.strictEqual(
            ev.publishes,
            false,
            `${dir}/${rowId}/${pair} PUBLISHED a magnitude from the committed corpus — rf2-8a746 rules those 42 ` +
              `row-runs calibration and diagnostic evidence, never retroactively promoted. Verdict: ${ev.verdict} — ${ev.why}`
          );
        }
      }
    }
  });

  t('and the program itself exits 0 over both committed ensembles, publishing no magnitude', () => {
    const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
    for (const { dir } of CORPORA) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return;
      const files = fs.readdirSync(d).map((f) => path.join(d, f));
      const r = cp.spawnSync(process.execPath, [RJ, ...files], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const out = `${r.stdout}${r.stderr}`;
      assert.strictEqual(r.status, 0, `${dir}: ${out.slice(-2000)}`);
      assert.ok(/EFFECT-SIZE INTERVAL \(rf2-8a746\)/.test(out), `${dir}: the interval must be printed`);
      assert.ok(!/VERDICT MAGNITUDE PUBLISHABLE|VERDICT ARCHITECTURE-KILL/.test(out), `${dir}: no magnitude may be published from the corpus`);
      assert.ok(/pooled means below are DIAGNOSTICS/.test(out), `${dir}: the pooled means must be labelled`);
    }
  });

  // --- 7. CRITERION 5: EVERYTHING CITES THE RULING --------------------------

  t('criterion 5: the ruling is cited by bead id in every surface it changed', () => {
    for (const [name, src] of [
      ['clock_run.cjs', CLOCKSRC],
      ['clock_readjudicate.cjs', RJSRC2],
      ['clock_check_standard.cjs', fs.readFileSync(path.join(__dirname, 'clock_check_standard.cjs'), 'utf8')],
      ['clock_check_standard.json', fs.readFileSync(path.join(__dirname, 'clock_check_standard.json'), 'utf8')],
      ['clock_app.cljs', fs.readFileSync(path.join(__dirname, 'clock_app.cljs'), 'utf8')],
    ]) {
      assert.ok(/rf2-8a746/.test(src), `${name} must cite the ruling that changed it`);
    }
    assert.strictEqual(classOf('bulk300'), 'bulk');
    assert.strictEqual(classOf('M1'), 'mount');
    assert.strictEqual(classOf('keystroke'), null);
  });

  t('the residual uncertainty is carried: no surface states paint causation', () => {
    // rf2-8a746 carries it explicitly — "non-layout and saturating below
    // d=100" is established; that PAINT causes the concavity is not, because
    // the datasets have no paint counter.
    for (const [name, src] of [
      ['clock_run.cjs', CLOCKSRC],
      ['clock_readjudicate.cjs', RJSRC2],
      ['clock_app.cljs', fs.readFileSync(path.join(__dirname, 'clock_app.cljs'), 'utf8')],
    ]) {
      const claims = src
        .split('\n')
        .map((line, i) => [i + 1, line])
        .filter(([, l]) => /mechanism is PAINT|paint is what|caused by paint|because paint/i.test(l));
      assert.deepStrictEqual(claims.map(([n, l]) => `${name}:${n}: ${l.trim()}`), [], 'paint causation is an inference, not a finding');
    }
  });
}

// --- rf2-x7x10: THE MOUNT CLASS, AND THE ROW IT PUTS BACK IN REACH ----------
//
// rf2-8a746 calibrated the BULK class and left the MOUNT class uncalibrated,
// holding rf2-t2flm's seat. The consequence fell between the two rulings and is
// what this block pins.
//
//   rf2-t2flm's published `M1` row is conditioned on the strict every-block
//   `ctl-2x` rule. rf2-8a746 retired that rule EVERYWHERE ON THIS INSTRUMENT.
//   So the row's label named a rule nothing implements, and
//   `clock_readjudicate.cjs` — which rf2-t2flm's ruling records as reproducing
//   every published figure exactly — returned `reportable subset: NONE` on all
//   three `M1` pairs instead. No figure was wrong and the posture was stricter,
//   not looser; what was lost was the REPRODUCTION PATH of a published claim.
//
// v2 calibrates the mount from the mount's own 14 committed row-runs. THE
// FENCE IS THE POINT OF THE BLOCK: the mount is calibrated on its own data and
// NOT on bulk's limits, because the two classes read 4.4% apart on the
// identical statistic and importing bulk's would have asserted against the
// mount a value never measured on it — the mis-specification rf2-8a746 retired,
// one row class over. The corpus makes that concrete rather than rhetorical:
// bulk's ceiling would have refused a mount run the mount's own limits admit.
{
  const { STANDARD, checkStandard, classOf } = require('./clock_check_standard.cjs');
  const { checkStandardFor, pairedLogRatios, effectInterval, effectVerdict, reportable, refusals } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-x7x10: ${what}`, fn);
  const bulk = STANDARD.classes.bulk;
  const mount = STANDARD.classes.mount;
  const CORPORA = [
    { dir: 'clock-emvod', runs: 8 },
    { dir: 'clock-w3yxd', runs: 6 },
  ];
  const M1_PAIRS = ['hicasso / reagent-subs', 'hicasso / uix-subs', 'uix-subs / reagent-subs'];

  /** Every committed dataset of an ensemble, or `null` when the corpus is absent. */
  const corpus = (dir) => {
    const d = path.join(__dirname, 'data', dir);
    if (!fs.existsSync(d)) return null;
    return fs.readdirSync(d).map((f) => ({ file: path.join(d, f), data: JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')) }));
  };

  // --- 1. THE CLASS IS DATA, LIKE THE OTHER ONE -----------------------------

  t('the mount class lands as DATA — calibrated, with its own frozen centre and limits', () => {
    // Calibrating a class is a version bump, or a stored verdict cannot be
    // re-read. The version has moved on since (v3, rf2-c1974), so the pin is on
    // the amendment that records THIS change rather than on the head number.
    assert.ok(STANDARD.version >= 2, `expected at least v2, got ${STANDARD.version}`);
    assert.strictEqual((STANDARD.amendments.find((a) => a.ruling === 'rf2-x7x10') || {}).version, 2);
    assert.strictEqual(mount.calibrated, true);
    assert.deepStrictEqual(mount.rows, ['M1']);
    assert.strictEqual(classOf('M1'), 'mount');
    assert.strictEqual(typeof mount.centre, 'number');
    assert.strictEqual(mount.location.limits.length, 2);
    assert.ok(mount.location.limits[0] < mount.centre && mount.centre < mount.location.limits[1], 'the limits must bracket the centre');
    assert.ok(mount.dispersion.limit > 0);
    // and the file, not the reader, is where they live
    assert.ok(
      !new RegExp(String(mount.centre)).test(fs.readFileSync(path.join(__dirname, 'clock_check_standard.cjs'), 'utf8')),
      'the mount centre must not also be a literal in the module that reads it'
    );
  });

  t('the mount centre is EMPIRICAL, with its provenance and the independence it has stated', () => {
    assert.ok(Math.abs(mount.centre - 2.0) > 0.15, 'the frozen centre is not the arithmetic 2.00x');
    const prov = mount.provenance;
    assert.strictEqual(prov.rowRuns, 14);
    assert.strictEqual(prov.calibratedBy, 'rf2-x7x10');
    assert.deepStrictEqual(prov.datasets, ['data/clock-emvod/run1-8.json', 'data/clock-w3yxd/run1-6.json']);
    // v2 said it was seeded from the runs it was quoted against; v3 replaced
    // that admission with the measurement it was waiting for (rf2-c1974), and
    // on this class the measurement came back split — see the rf2-c1974 block.
    assert.match(prov.independence, /SESSION LEVEL/i, 'the independence field states the level actually achieved');
    assert.ok(prov.holdOut, 'and carries the hold-out it was measured by');
    // the derivation is the bulk class's, restated on this class's numbers
    assert.match(mount.location.derivation, /3 x between-run SD/);
    assert.match(mount.dispersion.derivation, /exp\(mu \+ 3 sigma\)/);
    assert.match(mount.errorRates.runRejectionEmpirical, /0 of 14/);
    assert.match(mount.errorRates.retiredRuleForComparison, /7 of 14/, "the published row's own yield is where the label's arithmetic now lives");
  });

  // --- 2. THE FENCE ---------------------------------------------------------

  t('THE FENCE: the mount is calibrated on its OWN data, not on the bulk class\'s limits', () => {
    assert.notStrictEqual(mount.centre, bulk.centre);
    assert.notDeepStrictEqual(mount.location.limits, bulk.location.limits);
    assert.notStrictEqual(mount.dispersion.limit, bulk.dispersion.limit);
    // and calibrating a class did not loosen one: the mount's location term is
    // TIGHTER than bulk's, because its between-run scatter is smaller.
    assert.ok(
      mount.location.limits[1] - mount.location.limits[0] < bulk.location.limits[1] - bulk.location.limits[0],
      `mount width ${mount.location.limits[1] - mount.location.limits[0]} against bulk ${bulk.location.limits[1] - bulk.location.limits[0]}`
    );
  });

  t('THE BULK CLASS IS UNTOUCHED — rf2-8a746\'s frozen numbers, to the last place', () => {
    // The fence's other half. A PR that calibrates one class is the easiest
    // possible place to nudge another, so #7698's seed is asserted literally
    // here rather than left to a reviewer's eye.
    assert.strictEqual(bulk.calibrated, true);
    assert.strictEqual(bulk.centre, 1.7207);
    assert.deepStrictEqual(bulk.location.limits, [1.5509, 1.8905]);
    assert.strictEqual(bulk.dispersion.limit, 0.568);
    assert.deepStrictEqual(bulk.tolerance.band, [1.2905, 2.1509]);
    assert.strictEqual(bulk.provenance.rowRuns, 42);
    assert.deepStrictEqual(bulk.rows, ['bulk300', 'bulk100', 'narrow']);
  });

  t('and the corpus proves the fence rather than asserting it — bulk\'s ceiling would refuse a real mount run', () => {
    const c = corpus('clock-emvod');
    if (!c) return; // datasets are retained, not required to build
    const medians = [];
    for (const { data } of c) {
      const row = data.rows.find((r) => r.rowId === 'M1');
      if (row) medians.push(checkStandardFor(row, data).location.measured);
    }
    const widest = Math.max(...medians);
    assert.ok(
      widest > bulk.location.limits[1],
      `a real mount run must sit above bulk's ceiling for the fence to be load-bearing — widest ${widest}, ceiling ${bulk.location.limits[1]}`
    );
    assert.ok(widest < mount.location.limits[1], `and inside the mount's own — widest ${widest}, ceiling ${mount.location.limits[1]}`);
  });

  // --- 3. THE 14 COMMITTED MOUNT ROW-RUNS -----------------------------------

  t('the committed corpus is 14 mount row-runs, and every one is IN CONTROL under the standard', () => {
    let n = 0;
    let inControl = 0;
    for (const { dir } of CORPORA) {
      const c = corpus(dir);
      if (!c) return;
      for (const { data } of c) {
        for (const row of data.rows.filter((r) => r.rowId === 'M1')) {
          n += 1;
          if (checkStandardFor(row, data).ok) inControl += 1;
        }
      }
    }
    assert.strictEqual(n, 14, 'the corpus rf2-x7x10 calibrated from');
    // The same consistency check the bulk class carries, and the same caveat:
    // v2 was seeded from these medians, so 14 of 14 is what it must say, and a
    // drift here means the limits and the corpus have parted.
    assert.strictEqual(inControl, 14, `the frozen limits must still admit their own baseline — got ${inControl}`);
  });

  // --- 4. THE REPRODUCTION PATH, WHICH IS THE DEFECT THIS BEAD REPAIRS ------

  t('THE REPAIR: the M1 row has a reportable subset again, on both ensembles', () => {
    // The regression this bead exists to prevent recurring. With the mount
    // class uncalibrated every M1 run was refused by the gate, so
    // `clock_readjudicate.cjs` printed `reportable subset: NONE` for a row the
    // programme publishes — a claim a fresh clone could not recompute.
    for (const { dir, runs } of CORPORA) {
      const c = corpus(dir);
      if (!c) return;
      const rows = c.map(({ data }) => ({ data, row: data.rows.find((r) => r.rowId === 'M1') })).filter((x) => x.row);
      const pooled = rows.filter(({ row, data }) => reportable(row, data));
      assert.strictEqual(
        pooled.length,
        runs,
        `${dir}: every M1 run cleared every other gate before this bead, so all ${runs} must pool — ` +
          `refusals on the first: ${JSON.stringify(refusals(rows[0].row, rows[0].data))}`
      );
    }
  });

  t('and the publication rule reaches M1 for the first time, with a stated verdict on every pair', () => {
    // rf2-8a746's rule could not speak to this row while no run was
    // reportable, because an interval needs a pooled run to be formed from.
    for (const { dir } of CORPORA) {
      const c = corpus(dir);
      if (!c) return;
      const rows = c.map(({ data }) => ({ data, row: data.rows.find((r) => r.rowId === 'M1') })).filter((x) => x.row);
      const pooled = rows.filter(({ row, data }) => reportable(row, data));
      for (const pair of M1_PAIRS) {
        const iv = effectInterval(pooled.map(({ row }) => pairedLogRatios(row, pair)).filter(Boolean));
        const bands = pooled.map(({ row }) => row.seamTask.band).filter(Number.isFinite).map((b) => b * 100);
        const ev = effectVerdict(iv, bands.length ? Math.max(...bands) : NaN);
        assert.ok(iv, `${dir}/M1/${pair}: the corpus must now yield an interval to adjudicate`);
        assert.ok(iv.lo <= iv.point && iv.point <= iv.hi, `${dir}/M1/${pair}: the point must lie inside its own interval`);
        assert.ok(typeof ev.why === 'string' && ev.why.length > 0, `${dir}/M1/${pair}: a verdict must carry its reason`);
        assert.ok(
          /^(INSTRUMENT-LIMITED|MAGNITUDE PUBLISHABLE|ARCHITECTURE-KILL)/.test(ev.verdict),
          `${dir}/M1/${pair}: unrecognised verdict ${ev.verdict}`
        );
        // PINNED AS THE RULING'S FENCE, NOT AS A PREFERRED ANSWER, exactly as
        // the bulk corpus case above is. These 14 row-runs are calibration
        // evidence; promoting them to a published magnitude is a RULING —
        // rf2-t2flm's magnitude and rf2-8a746's whole-interval rule currently
        // disagree on this row — and no code here may do it silently. If this
        // ever flips, the failure IS the finding.
        assert.strictEqual(
          ev.publishes,
          false,
          `${dir}/M1/${pair} PUBLISHED a magnitude from the committed corpus. That is a ruling to be made, not a ` +
            `test to be updated: rf2-t2flm publishes a magnitude on this row and rf2-8a746 part 4 publishes one only ` +
            `on a whole interval clearing the 1.0 bar or the 1.5 kill threshold. Verdict: ${ev.verdict} — ${ev.why}`
        );
      }
    }
  });

  t('and the program itself exits 0 over both ensembles, printing an M1 subset rather than NONE', () => {
    const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
    for (const { dir, runs } of CORPORA) {
      const c = corpus(dir);
      if (!c) return;
      const r = cp.spawnSync(process.execPath, [RJ, ...c.map((x) => x.file)], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const out = `${r.stdout}${r.stderr}`;
      assert.strictEqual(r.status, 0, `${dir}: ${out.slice(-2000)}`);
      const m1 = out.slice(out.indexOf(';; ======== ROW M1'), out.indexOf(';; ======== ROW bulk300'));
      assert.ok(m1.length > 0, `${dir}: the M1 block must be printed`);
      assert.ok(!/reportable subset: NONE/.test(m1), `${dir}: the M1 row must have a reportable subset — that is the defect rf2-x7x10 repairs`);
      assert.ok(new RegExp(`reportable subset [0-9.]+x n=${runs}`).test(m1), `${dir}: the subset must pool all ${runs} runs`);
      assert.ok(!/the `mount` class of the check standard is NOT CALIBRATED/.test(m1), `${dir}: the mount class is calibrated now`);
      assert.ok(/EFFECT-SIZE INTERVAL \(rf2-8a746\)/.test(m1), `${dir}: the interval must be printed on M1 too`);
    }
  });

  // --- 5. THE SUPERSEDED RATIONALE IS CORRECTED, NOT DELETED ---------------

  t('v1\'s rationale cited a SUPERSEDED ruling, and the standard records that rather than erasing it', () => {
    // The failure mode worth leaving on the record: a rationale can be sound,
    // current at the hour it was written, and still rest on a ruling overturned
    // the day before. v1 justified failing closed with "M1 publishes DIRECTION
    // and never a magnitude by rf2-jcm3p, so no magnitude is being withheld";
    // rf2-t2flm had superseded rf2-jcm3p's regime-only statement the previous
    // day, so a magnitude WAS being withheld.
    const a = mount.amendedFrom;
    assert.ok(a, 'the mount class must carry what it was amended from');
    assert.strictEqual(a.version, 1);
    assert.match(a.citedASupersededRuling, /rf2-jcm3p/);
    assert.match(a.citedASupersededRuling, /rf2-t2flm/);
    assert.match(a.citedASupersededRuling, /ALREADY FALSE/);
    assert.match(a.whatItActuallyCost, /REPRODUCTION PATH/);
    assert.match(a.keptRatherThanDeleted, /[Aa]nnotate-never-erase/);
    // and the superseded premise is no longer asserted anywhere as live
    // rationale: it survives only inside the amendment that corrects it.
    const json = fs.readFileSync(path.join(__dirname, 'clock_check_standard.json'), 'utf8');
    const outsideAmendment = json.split('"amendedFrom"')[0];
    assert.ok(!/rf2-jcm3p/.test(outsideAmendment), 'the superseded ruling may only appear inside the amendment that retires it');
    // the standard also records the amendment at the top level, so a reader
    // meeting v2 knows what changed between the versions without diffing.
    assert.ok(Array.isArray(STANDARD.amendments) && STANDARD.amendments.length >= 1);
    assert.strictEqual(STANDARD.amendments[0].version, 2);
    assert.strictEqual(STANDARD.amendments[0].ruling, 'rf2-x7x10');
  });

  t('the ruling is cited by bead id in every surface it changed', () => {
    for (const [name, file] of [
      ['clock_check_standard.json', 'clock_check_standard.json'],
      ['clock_check_standard.cjs', 'clock_check_standard.cjs'],
      ['clock_readjudicate.cjs', 'clock_readjudicate.cjs'],
      ['clock_run.cjs', 'clock_run.cjs'],
    ]) {
      assert.ok(/rf2-x7x10/.test(fs.readFileSync(path.join(__dirname, file), 'utf8')), `${name} must cite the ruling that changed it`);
    }
  });

  t('the UNCALIBRATED seat survives its last live instance — a class with no limits still refuses', () => {
    // Every declared class is calibrated as of v2, so the branch has no
    // production instance. It is not dead code: it is what refuses the next
    // class added to `classes` before its limits exist. Proved by making an
    // instance and putting it back, which is also the shape of this PR's own
    // mutation proof.
    const before = JSON.stringify(STANDARD.classes.mount);
    let v;
    try {
      STANDARD.classes.mount.calibrated = false;
      v = checkStandard([1.79, 1.8, 1.81], 'M1');
    } finally {
      STANDARD.classes.mount.calibrated = true;
    }
    assert.strictEqual(v.ok, false, 'an uncalibrated class refuses rather than abstains');
    assert.strictEqual(v.calibrated, false);
    assert.strictEqual(v.location, null, 'and it refuses on the CLASS, before any reading is judged');
    assert.match(v.why, /NOT CALIBRATED/);
    assert.strictEqual(JSON.stringify(STANDARD.classes.mount), before, 'the fixture must leave the standard as it found it');
    assert.strictEqual(checkStandard([1.79, 1.8, 1.81], 'M1').ok, true, 'and the class certifies again afterwards');
  });
}

// ============================================================================
// rf2-c1974 — THE LIMITS, VALIDATED OUT OF SAMPLE
// ============================================================================
//
// rf2-8a746 part 3 asks for limits frozen from an INDEPENDENT baseline. v1 and
// v2 both said, in their own `provenance.independence` fields, that they did not
// have one — seeded from the runs they were quoted against, so `0 of 42` and
// `14 of 14` were CONSISTENCY CHECKS and not false-refusal measurements. To
// their credit they said it rather than leaving it to be found; but the moment
// a NEW run is judged in control the verdict rests on limits nobody has tested
// out of sample.
//
// WHAT THIS CORPUS SUPPORTS, AND THE CLAIM IS DELIBERATELY THE WEAKER ONE.
// rf2-pzqy8's census standard held out a later COMMIT: its corpus spans five
// days and a code change. This one cannot — every run in both clock ensembles
// carries a `when` of 2026-08-07 and the ensembles start 87 minutes apart, on
// the evidence one tree — so the strongest hold-out here is by SITTING. That is
// real, and it is the dominant practical failure mode for a check standard, but
// it crosses a coffee break rather than a commit. The first case below checks
// that premise against the datasets rather than taking it on trust.
//
// BOTH WAYS, because a one-directional answer depends on which ensemble
// happened to be picked. BULK agrees, 42 of 42 held-out row-runs in control.
// MOUNT DISAGREES — and the disagreement is the finding, so it is pinned here
// as a measurement rather than smoothed into an average.
//
// AND NOTHING WAS WIDENED. The fence rf2-c1974 was raised under is that a
// hold-out failure is an answer, not a tuning signal; the last cases assert
// that every shipped limit is still v2's, to the last place.
{
  const { STANDARD } = require('./clock_check_standard.cjs');
  const { checkStandardFor } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-c1974: ${what}`, fn);
  const bulk = STANDARD.classes.bulk;
  const mount = STANDARD.classes.mount;
  const ENSEMBLES = ['clock-emvod', 'clock-w3yxd'];

  const r4 = (x) => Math.round(x * 10000) / 10000;
  const p50 = (xs) => {
    const v = [...xs].sort((a, b) => a - b);
    return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
  };
  const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
  const sd = (xs) => {
    const m = mean(xs);
    return Math.sqrt(xs.reduce((a, b) => a + (b - m) * (b - m), 0) / (xs.length - 1));
  };

  /**
   * Every committed row-run of one class, per ensemble, ADJUDICATED BY THE LIVE
   * ADJUDICATOR rather than by a copy of its arithmetic: `checkStandardFor` is
   * what the readjudicator applies to a dataset off disk, so a witness built on
   * it cannot pass while the thing it describes has changed. Returns `null`
   * when the corpus is absent — the datasets are retained, not required to
   * build.
   */
  const readClass = (klassName) => {
    const rows = STANDARD.classes[klassName].rows;
    const out = {};
    for (const dir of ENSEMBLES) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return null;
      out[dir] = [];
      for (const f of fs.readdirSync(d).sort()) {
        const data = JSON.parse(fs.readFileSync(path.join(d, f), 'utf8'));
        for (const row of data.rows.filter((r) => rows.includes(r.rowId))) {
          const v = checkStandardFor(row, data);
          out[dir].push({ run: `${dir}/${f.replace(/\.json$/, '')}`, rowId: row.rowId, median: v.location.measured, scale: v.dispersion.measured });
        }
      }
    }
    return out;
  };

  /** The class's own frozen recipe, applied to ONE ensemble alone. */
  const deriveFrom = (runs) => {
    const meds = runs.map((r) => r.median);
    const logs = runs.map((r) => Math.log(r.scale));
    const centre = p50(meds);
    const s = sd(meds);
    return { n: runs.length, centre, sd: s, limits: [centre - 3 * s, centre + 3 * s], dispersionLimit: Math.exp(mean(logs) + 3 * sd(logs)) };
  };

  // --- 1. THE PREMISE OF THE WEAKER CLAIM, CHECKED AGAINST THE DATA ---------

  t('the corpus does NOT split by commit — one day, ~90 minutes apart, so the hold-out is by SITTING', () => {
    // The reason the claim is session-level rather than commit-level. It is
    // asserted from the datasets because a standard that overstated its own
    // independence would be the exact defect rf2-c1974 was raised on, one
    // level up.
    const firsts = {};
    for (const dir of ENSEMBLES) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return;
      const whens = fs.readdirSync(d).sort().map((f) => JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')).when);
      assert.ok(whens.every((w) => /^2026-08-07T/.test(w)), `${dir}: every run must be the same day for the claim to be what it says — ${JSON.stringify(whens)}`);
      firsts[dir] = Math.min(...whens.map((w) => Date.parse(w)));
    }
    const gapMinutes = Math.abs(firsts['clock-w3yxd'] - firsts['clock-emvod']) / 60000;
    assert.ok(gapMinutes > 30 && gapMinutes < 180, `the two ensembles must be separate sittings of the same day — ${r4(gapMinutes)} minutes apart`);
    for (const k of [bulk, mount]) {
      assert.match(k.provenance.holdOut.level, /SESSION-LEVEL/, 'and the file must claim exactly that');
      assert.match(k.provenance.holdOut.whyNotCommitLevel, /does not split by commit/);
    }
  });

  // --- 2. THE HOLD-OUT IS RECOMPUTABLE FROM THE COMMITTED DATASETS ---------

  t('every hold-out number in the standard is re-derived from the corpus — both classes, both directions', () => {
    for (const [name, klass] of [['bulk', bulk], ['mount', mount]]) {
      const data = readClass(name);
      if (!data) return;
      assert.strictEqual(
        Object.values(data).reduce((a, v) => a + v.length, 0),
        klass.provenance.rowRuns,
        `${name}: the corpus must be the ${klass.provenance.rowRuns} row-runs the class was calibrated from`
      );
      for (const dir of klass.provenance.holdOut.directions) {
        const d = deriveFrom(data[dir.baseline]);
        const held = data[dir.heldOut];
        assert.strictEqual(d.n, dir.baselineRowRuns, `${name}/${dir.baseline}: baseline size`);
        assert.strictEqual(held.length, dir.heldOutRowRuns, `${name}/${dir.heldOut}: held-out size`);
        assert.strictEqual(r4(d.centre), dir.centre, `${name}/${dir.baseline}: derived centre`);
        assert.strictEqual(r4(d.sd), dir.betweenRunSD, `${name}/${dir.baseline}: derived between-run SD`);
        // THE LIMITS AND THE DISPERSION TERM ARE PINNED TO WITHIN HALF A PLACE
        // rather than to the last one, and the reason is arithmetic and not
        // slack: the adjudicator reports a run's median and scale ROUNDED to
        // four places, so a statistic taken over the reported numbers can
        // differ from one taken over the raw ones in the fourth. Any refit
        // moves these by orders of magnitude more than 1e-3.
        assert.ok(Math.abs(d.limits[0] - dir.limits[0]) < 1e-3 && Math.abs(d.limits[1] - dir.limits[1]) < 1e-3,
          `${name}/${dir.baseline}: derived limits [${r4(d.limits[0])}, ${r4(d.limits[1])}] against recorded ${JSON.stringify(dir.limits)}`);
        assert.ok(Math.abs(d.dispersionLimit - dir.dispersionLimit) < 1e-3,
          `${name}/${dir.baseline}: derived dispersion ${r4(d.dispersionLimit)} against recorded ${dir.dispersionLimit}`);
        // and the verdict itself: every held-out run judged by limits fitted
        // without it, against the limits the file records.
        const verdicts = held.map((r) => ({ ...r, ok: r.median >= dir.limits[0] && r.median <= dir.limits[1] && r.scale <= dir.dispersionLimit }));
        assert.strictEqual(verdicts.filter((v) => v.ok).length, dir.inControl, `${name}/${dir.baseline} -> ${dir.heldOut}: in-control count`);
        assert.deepStrictEqual(
          verdicts.filter((v) => !v.ok).map((v) => v.run).sort(),
          (dir.refused || []).map((r) => r.run).sort(),
          `${name}/${dir.baseline} -> ${dir.heldOut}: the refused runs must be the ones the file names`
        );
        for (const r of dir.refused || []) {
          const v = verdicts.find((x) => x.run === r.run);
          assert.strictEqual(v.median, r.median, `${r.run}: recorded median`);
          assert.strictEqual(r.term, v.scale <= dir.dispersionLimit ? 'location' : 'dispersion', `${r.run}: recorded term`);
        }
      }
    }
  });

  // --- 3. BULK: BOTH DIRECTIONS AGREE ---------------------------------------

  t('BULK: limits fitted on one sitting admit every row-run of the other, BOTH WAYS — 42 of 42', () => {
    const h = bulk.provenance.holdOut;
    assert.strictEqual(h.agree, true);
    assert.strictEqual(h.heldOutInControl, '42 of 42');
    assert.deepStrictEqual(h.directions.map((d) => `${d.baseline}->${d.heldOut} ${d.inControl}/${d.heldOutRowRuns}`), [
      'clock-emvod->clock-w3yxd 18/18',
      'clock-w3yxd->clock-emvod 24/24',
    ]);
    // and the file does not oversell it: the closer direction passes by 0.0073
    // on a limits width of 0.2949, which the note says out loud.
    const tight = Math.min(...h.directions.map((d) => d.tightestMargin));
    assert.ok(tight > 0 && tight < 0.02, `the tightest held-out margin is ${tight}`);
    assert.match(h.directions.find((d) => d.tightestMargin === tight).note, /does not pass comfortably/);
  });

  // --- 4. MOUNT: THE DIRECTIONS DISAGREE, AND THAT IS THE FINDING -----------

  t('MOUNT: one direction admits all 6, the other REFUSES 4 of 8 — the disagreement is recorded, not averaged', () => {
    const h = mount.provenance.holdOut;
    assert.strictEqual(h.agree, false, 'a class whose directions disagree may not report as if they agreed');
    assert.strictEqual(h.heldOutInControl, '10 of 14');
    const emvodBased = h.directions.find((d) => d.baseline === 'clock-emvod');
    const w3yxdBased = h.directions.find((d) => d.baseline === 'clock-w3yxd');
    assert.strictEqual(emvodBased.inControl, emvodBased.heldOutRowRuns, 'the 8-run baseline admits the whole other sitting');
    assert.strictEqual(w3yxdBased.inControl, 4);
    assert.strictEqual(w3yxdBased.refused.length, 4);
    // EVERY refusal is LOCATION. That is what makes this a statement about
    // where the two sittings sit rather than about how noisy either was, and
    // it is the difference between a finding and a flaky instrument.
    assert.ok(w3yxdBased.refused.every((r) => r.term === 'location'), JSON.stringify(w3yxdBased.refused));
    // and the cause is stated: not a large shift, but a baseline too tight to
    // contain a small one.
    assert.ok(
      Math.abs(emvodBased.centre - w3yxdBased.centre) < Math.abs(bulk.provenance.holdOut.directions[0].centre - bulk.provenance.holdOut.directions[1].centre),
      'the mount sittings are CLOSER than the bulk sittings, which is why the cause cannot be the offset alone'
    );
    assert.ok(w3yxdBased.betweenRunSD * 4 < emvodBased.betweenRunSD, 'the failing baseline is the tighter one, by more than 4x');
    assert.match(h.disagreement, /REPRODUCIBILITY/);
    assert.match(h.disagreement, /single sitting/i);
  });

  // --- 5. THE FENCE: NOTHING WAS WIDENED TO MAKE A HOLD-OUT PASS ------------

  t('THE FENCE: not one shipped limit moved — v2\'s frozen numbers, to the last place', () => {
    // The bulk half of this is already pinned by the rf2-x7x10 block; the mount
    // half is pinned here, because the mount is the class whose hold-out
    // failed and therefore the one a tuning hand would have reached for.
    assert.strictEqual(mount.centre, 1.7956);
    assert.deepStrictEqual(mount.location.limits, [1.6765, 1.9147]);
    assert.strictEqual(mount.dispersion.limit, 0.577);
    assert.deepStrictEqual(mount.tolerance.band, [1.3467, 2.2445]);
    assert.strictEqual(bulk.centre, 1.7207);
    assert.deepStrictEqual(bulk.location.limits, [1.5509, 1.8905]);
    assert.strictEqual(bulk.dispersion.limit, 0.568);
    // and the shipped limits are the POOLED fit rather than either single-
    // sitting one — narrower than the widest available, which is what a
    // widening would have produced.
    for (const k of [bulk, mount]) {
      const width = k.location.limits[1] - k.location.limits[0];
      const widths = k.provenance.holdOut.directions.map((d) => d.limits[1] - d.limits[0]);
      assert.ok(width < Math.max(...widths), `${JSON.stringify(k.rows)}: pooled width ${r4(width)} against ${widths.map(r4)}`);
    }
    // the standard still admits its own baseline, both classes, unchanged
    for (const [name, klass] of [['bulk', bulk], ['mount', mount]]) {
      const data = readClass(name);
      if (!data) return;
      const all = ENSEMBLES.flatMap((d) => data[d]);
      const inControl = all.filter((r) => r.median >= klass.location.limits[0] && r.median <= klass.location.limits[1] && r.scale <= klass.dispersion.limit);
      assert.strictEqual(inControl.length, klass.provenance.rowRuns, `${name}: the shipped limits must still admit all ${klass.provenance.rowRuns}`);
    }
  });

  // --- 6. THE VERSION, THE AMENDMENT AND THE RESIDUAL -----------------------

  t('v3 is an amendment with its own entry, and the earlier admissions are replaced by measurements', () => {
    assert.strictEqual(STANDARD.version, 3, 'replacing an independence claim is a version bump — a stored verdict must stay re-readable');
    const a = STANDARD.amendments.find((x) => x.ruling === 'rf2-c1974');
    assert.ok(a, 'the amendment log must carry this change');
    assert.strictEqual(a.version, 3);
    assert.match(a.what, /OUT OF SAMPLE/);
    assert.match(a.touches, /byte-identical to v2/);
    // the honest admission is GONE as a live claim, on both classes, because it
    // has been answered — not because it was inconvenient.
    for (const k of [bulk, mount]) {
      assert.ok(!/NOT INDEPENDENT OF ITS OWN BASELINE/.test(k.provenance.independence), 'the admission is replaced by the measurement that answers it');
      assert.match(k.provenance.independence, /rf2-c1974/);
      assert.match(k.provenance.independence, /SESSION LEVEL/i);
    }
  });

  t('THE RESIDUAL: commit-level independence is named as the recalibration trigger, not implied to be done', () => {
    // The half of rf2-8a746 part 3 this work does NOT discharge. Left unstated,
    // a reader meeting `provenance.independence` would take "validated out of
    // sample" for the whole criterion.
    const triggers = STANDARD.recalibrateOn;
    const residual = triggers.find((r) => /COMMIT-LEVEL INDEPENDENCE HAS NEVER BEEN MEASURED/.test(r));
    assert.ok(residual, `the standard must name what it still lacks — ${JSON.stringify(triggers)}`);
    assert.match(residual, /different trees/);
    assert.match(residual, /rf2-8a746 part 3/);
    // and the mount's own residual, which is narrower and sharper: its next
    // baseline may not be a single sitting.
    assert.ok(triggers.some((r) => /MOUNT class specifically/.test(r) && /more than 14 runs/.test(r)), JSON.stringify(triggers));
  });

  t('the ruling is cited by bead id in every surface it changed', () => {
    for (const file of ['clock_check_standard.json', 'clock_check_standard.cjs']) {
      assert.ok(/rf2-c1974/.test(fs.readFileSync(path.join(__dirname, file), 'utf8')), `${file} must cite the ruling that changed it`);
    }
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
