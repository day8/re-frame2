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

  t('the published shape, all gates passed, is the ONLY thing that is canonical', () => {
    const d = destination(shape(), 0);
    assert.strictEqual(d.canonical, true);
    assert.strictEqual(d.dir, CANON);
    assert.strictEqual(d.why, null);
  });

  // Each condition alone must move the write off the canonical set. These are
  // the mutation proofs: flip one field, the destination must change.
  for (const [what, over, code, needle] of [
    ['a refused verdict', {}, 3, /verdict refused it \(exit 3\)/],
    ['a partial row set', { rowsOnly: 'feed' }, 0, /PARTIAL row set/],
    ['a partial run set', { runsOnly: 'uix' }, 0, /PARTIAL run set/],
    ['--no-build', { noBuild: true }, 0, /--no-build/],
    ['an overridden depth', { depthPublished: false }, 0, /OVERRIDDEN design depth/],
    // rf2-azopg. C56CLOCK_SKIP_QUIET=1 made `quietGate` return ok:true and
    // print "NOT the published shape", and then nothing carried that fact to
    // the write decision — so a run whose samples were never checked against a
    // quiet box could occupy the canonical directory, indistinguishable from
    // one taken in a granted window. This is the probe that found it: the
    // published shape in every other respect, exit 0, quiet gate skipped.
    ['a SKIPPED quiet gate', { skipQuiet: true }, 0, /SKIPPED quiet gate \(C56CLOCK_SKIP_QUIET=1\)/],
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

  t('every condition that fired is named, not just the first', () => {
    const d = destination(shape({ rowsOnly: 'feed', noBuild: true, depthPublished: false, skipQuiet: true }), 5);
    assert.strictEqual(d.canonical, false);
    for (const needle of [/verdict refused it \(exit 5\)/, /PARTIAL row set/, /--no-build/, /OVERRIDDEN design depth/, /SKIPPED quiet gate/]) {
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
  t('the verdict is computed BEFORE any dataset is written', () => {
    const v = SRC.indexOf('const v = verdict(summarise(failed, results));');
    const dst = SRC.indexOf('const dest = destination(runShape(), v.code);');
    const mk = SRC.indexOf('fs.mkdirSync(dest.dir');
    assert.ok(v > 0 && dst > 0 && mk > 0, 'the write path no longer has the shape this test pins');
    assert.ok(v < dst, 'the verdict must be computed before the destination is chosen');
    assert.ok(dst < mk, 'the destination must be chosen before the directory is created');
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
  const FIXTURE_DECOMP = [
    [
      { floor: DECOMP_BLOCK(10, 8, 9, 0.8), 'ctl-2x': DECOMP_BLOCK(10, 20, 18, 2.0) },
      { floor: DECOMP_BLOCK(10, 12, 11, 1.2), 'ctl-2x': DECOMP_BLOCK(10, 21, 19, 2.4) },
    ],
    [
      { floor: DECOMP_BLOCK(10, 9, 10, 1.0), 'ctl-2x': DECOMP_BLOCK(10, 20.4, 18.5, 2.4) },
      { floor: DECOMP_BLOCK(10, 11, 10, 1.0), 'ctl-2x': DECOMP_BLOCK(10, 21, 18.5, 2.4) },
    ],
  ];
  const CITED = { layout: 2.06, style: 1.85, script: 2.3 };

  const fixtureRow = (over = {}) => ({
    runId: 'reagent',
    rowId: 'feed',
    armIds: ['plumb', 'floor', 'ctl-2x'],
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
      ctl: { ok: true },
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
    const fold = foldDecomposition(written().blocksDecomp);
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
    assert.throws(() => foldDecomposition(legacy.blocksDecomp), /NOT recomputable/);
    assert.throws(() => foldDecomposition([]), /NOT recomputable/);
  });

  t('every committed census dataset either carries the split or refuses to answer', () => {
    // Capture backfills nothing: the datasets on disk gain the fields on the
    // next canonical run, not on this commit. Whatever is there, the rule is
    // the same — a row with the split folds, a row without it refuses. There
    // is no third answer, so this stays true across the re-run.
    const dir = path.join(__dirname, 'data');
    const files = fs
      .readdirSync(dir)
      .filter((d) => d.startsWith('censusclock-'))
      .flatMap((d) => fs.readdirSync(path.join(dir, d)).filter((f) => f.endsWith('.json')).map((f) => path.join(dir, d, f)));
    assert.ok(files.length >= 2, 'expected the committed census datasets');
    for (const f of files) {
      for (const row of JSON.parse(fs.readFileSync(f, 'utf8')).rows) {
        if (row.blocksDecomp) {
          const fold = foldDecomposition(row.blocksDecomp);
          assert.ok(Object.values(fold).every((a) => a.n > 0), `${f}: a stored split must fold to real counts`);
        } else {
          assert.throws(() => foldDecomposition(row.blocksDecomp), /NOT recomputable/, `${f}: must refuse, not answer`);
        }
      }
    }
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
  const { reportability, rowAdjudication, rowRegime, ROW_REGIME, reportabilitySelfTest } = require('./clock_run.cjs');
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
    assert.match(v.lines[1], /HCLOCK_CTL3_SABOTAGE=140 WAS SET/);
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
    assert.match(
      SRC,
      /module\.exports = \{ reportability, rowAdjudication, rowRegime, ROW_REGIME, reportabilitySelfTest \};/
    );
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
  const CANON = { canonical: true, notCanonicalWhy: null };
  // A dataset row as a two-tier `clock_run.cjs` writes it, reduced to the
  // fields this predicate reads — every whole-run verdict the driver exits on,
  // each at its passing value.
  const dsRow = (bars, over) => ({
    rowId: 'M1',
    pageErrors: [],
    guardRefuse: false,
    guardRefuseTask: false,
    parityOk: true,
    ctl3Parity: null,
    kbWitness: null,
    tally: { writes: 1008, unverified: 0 },
    ctl3: null,
    ctlOk: true,
    ctlTask: { ok: true },
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
    // A failed control still excludes a fully adjudicated run ...
    assert.strictEqual(reportable(dsRow(bars, { ctlTask: { ok: false } }), CANON), false);
    assert.strictEqual(reportable(dsRow(bars, { ctlTask: null }), CANON), false);
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
      id: 'control',
      row: { ctl3: { ok: false, measured: { mean: 1.2 } } },
      failed: /THREE-POINT control FAILED/,
      erase: { row: 'ctl3' },
      absent: /no three-point-control record/,
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
    const why = refusals(dsRow(BARS, { guardRefuse: true, parityOk: false, ctlTask: { ok: false } }), {
      canonical: false,
      notCanonicalWhy: 'a PARTIAL row set',
    });
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
    assert.match(RJSRC, /module\.exports = \{ GATES, adjudicated, refusals, reportable, responsivenessRegime \};/);
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
