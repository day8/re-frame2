#!/usr/bin/env node
'use strict';
// THE FRESCO BENCH DRIVERS' EXIT PATH — a printed refusal must refuse.
//
//     node src/re_frame/bench/fresco/clock_exit_path.test.cjs   (from bench/fresco/)
//
// Each driver here computes refusals per row — unverified read-backs, a band
// over `seam.cjs`'s ceiling, a positive control that missed, a bar with no
// band — and prints them. A refusal that is printed but never reaches the exit
// code, or a refused run that still writes the published datasets, is the
// defect this file guards. The drivers need an `:advanced` build and a headless
// Chromium, so each decision lives in one pure exported function driven here
// directly, plus a source pin that the exit code comes from that function and
// that nothing downstream of it reads a refusal on its own.
//
// The census and clock blocks also re-derive the check standards' frozen
// numbers and the published M1 figures from the committed run corpus.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Requiring a driver must NOT drive it.
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

// The run corpus is archived in git history (`data_archive.cjs`). A test
// declared through `corpusTest` runs when `data/` is present and is counted as
// skipped — one printed line at the exit — when it is not.
const archive = require('./data_archive.cjs');
let corpusSkipped = 0;
const corpusTest = (name, fn) => test(name, () => {
  if (archive.present()) fn();
  else corpusSkipped += 1;
});

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

  t('a clean run exits 0 and says nothing', () => {
    assert.deepStrictEqual(verdict({ failed: null, rows: [row({}), row({ id: 'reagent/mount-M' })] }), {
      code: 0,
      lines: [],
    });
  });

  t('each refusal ALONE takes its own exit code and names the row', () => {
    for (const [over, failed, code, named] of [
      [{}, 'the box would not go quiet before uix/mount-M', 1, /FAILED: the box would not go quiet/],
      [{ guardRefuse: true }, null, 2, /Repair the arm, not the guard: uix\/mount-M/],
      [{ unverified: 4 }, null, 3, /uix\/mount-M: 4 of 36/],
      [{ ceilingBreached: true, band: 0.412 }, null, 4, /uix\/mount-M \(41\.2%\)/],
      [{ ctlOk: false, ctlMeasured: 1.2134 }, null, 5, /uix\/mount-M \(measured 1\.2134x\)/],
    ]) {
      const v = verdict({ failed, rows: [row(over)] });
      assert.deepStrictEqual([v.code, v.lines.length], [code, 1], `${failed || JSON.stringify(over)}: ${v.lines}`);
      assert.match(v.lines[0], named);
    }
  });

  t('all three new refusals together: all THREE are named, band precedes control', () => {
    const v = verdict({
      failed: null,
      rows: [row({ unverified: 2, ceilingBreached: true, band: 0.5, ctlOk: false })],
    });
    assert.strictEqual(v.code, 3, 'the first-declared of the three refusals takes the code');
    assert.strictEqual(v.lines.length, 3, 'no refusal may mask another');
    assert.match(v.lines[0], /unverified operations/);
    assert.match(v.lines[1], /reproducibility band/);
    assert.match(v.lines[2], /positive control/);
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
    // And the summary it built refuses, naming every condition on the row.
    assert.deepStrictEqual([verdict(s).code, verdict(s).lines.length], [2, 4]);
  });

  t('summarise survives a run that took no rows', () => {
    assert.deepStrictEqual(summarise('build failed', []), { failed: 'build failed', rows: [] });
  });

  // --- the wiring: `verdict` is load-bearing, not decorative ----------------

  const SRC = fs.readFileSync(file, 'utf8');
  const DRIVE = SRC.slice(SRC.indexOf('async function drive('), SRC.indexOf('\nmodule.exports'));

  t('the exit code comes from `verdict` and is RETURNED, not re-derived', () => {
    assert.match(SRC, /module\.exports = \{ summarise, verdict\s*[,}]/);
    assert.match(DRIVE, /const v = verdict\(summarise\(failed, results\)\);/);
    assert.match(DRIVE, /return v\.code;/);
    assert.match(SRC, /drive\(\)\.then\(\(code\) => \{\s*if \(code !== 0\) process\.exit\(code\);/);
  });

  t('NOTHING downstream of `verdict` reads a refusal on its own', () => {
    // An exit block reading `guardRefuse` directly while its siblings sit
    // unread beside it is the defect; a `process.exit` inside `drive` is a
    // second decision by construction.
    assert.ok(!/process\.exit/.test(DRIVE), '`drive` must return its code, never exit');
    const tail = DRIVE.slice(DRIVE.indexOf('const v = verdict('));
    assert.ok(
      !/guardRefuse|ceilingBreached|ctl\.ok|tally\.unverified/.test(tail),
      'the exit path must consult `verdict` alone, never a refusal directly'
    );
  });

  t('every refusal line names the driver, so a piped log says which run refused', () => {
    const v = verdict({
      failed: 'x',
      rows: [row({ guardRefuse: true, unverified: 1, ceilingBreached: true, ctlOk: false })],
    });
    assert.deepStrictEqual([v.code, v.lines.length], [1, 5], 'a failed run keeps exit 1 and masks none of the others');
    for (const line of v.lines) assert.ok(line.startsWith(`${tag} `), `not tagged ${tag}: ${line}`);
  });
}

// --- census_clock_run.cjs: the WRITE path ------------------------------------
//
// `verdict` decides what may be QUOTED; `destination` decides what may be
// WRITTEN. A narrowed, --no-build or unchecked run must not replace the
// published datasets.

{
  const { destination } = DRIVERS[1].mod;
  const SRC = fs.readFileSync(DRIVERS[1].file, 'utf8');
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
  const tc = (what, fn) => corpusTest(`census_clock_run.cjs write path: ${what}`, fn);

  t('the published shape is the ONLY thing that is canonical', () => {
    assert.deepStrictEqual(destination(shape()), { dir: CANON, canonical: true, why: null });
  });

  // Each is a fact about the run's SHAPE. A gate refusal is not on this list:
  // it belongs to one row and is recorded there (`rowPublication`, below).
  t('a non-published shape is NOT canonical, and says why', () => {
    for (const [over, needle] of [
      [{ rowsOnly: 'feed' }, /PARTIAL row set/],
      [{ runsOnly: 'uix' }, /PARTIAL run set/],
      [{ noBuild: true }, /--no-build/],
      [{ depthPublished: false }, /OVERRIDDEN design depth/],
      [{ skipQuiet: true }, /SKIPPED quiet gate \(C56CLOCK_SKIP_QUIET=1\)/],
    ]) {
      const d = destination(shape(over));
      assert.deepStrictEqual([d.canonical, d.dir], [false, `${CANON}.unpublished`], JSON.stringify(over));
      assert.match(d.why, needle);
    }
  });

  // `destination` is pure over a shape RECORD; `runShape` builds the real one
  // and is not exported, so the skip-quiet flag reaching it is pinned here.
  t('runShape carries the skip-quiet fact into the write decision', () => {
    assert.match(SRC, /const SKIP_QUIET = process\.env\.C56CLOCK_SKIP_QUIET === '1';/);
    const from = SRC.indexOf('const runShape = () => ({');
    assert.match(
      SRC.slice(from, SRC.indexOf('});', from)),
      /skipQuiet: SKIP_QUIET,/,
      'runShape must carry skipQuiet, or a skipped quiet gate can still write the canonical set'
    );
  });

  t('an explicit C56CLOCK_DATA_DIR is honoured as given, and is never canonical', () => {
    const mine = '/data/censusclock-somebead';
    const d = destination(shape({ dataDir: mine, dataDirOverridden: true }));
    assert.deepStrictEqual([d.dir, d.canonical], [mine, false]);
    assert.strictEqual(destination(shape({ dataDir: mine, dataDirOverridden: true, skipQuiet: true })).dir, mine);
  });

  t('no dataset is written to the raw DATA_DIR downstream of `destination`', () => {
    const after = SRC.slice(SRC.indexOf('const dest = destination(runShape());'));
    assert.ok(!/fs\.mkdirSync\(DATA_DIR/.test(after), 'mkdirSync must use the chosen destination');
    assert.ok(!/path\.join\(DATA_DIR/.test(after), 'the dataset path must use the chosen destination');
    assert.match(after, /path\.join\(dest\.dir/);
  });

  // --- the split the driver collects must reach the file --------------------
  //
  // census-real-clock-rows.md's P1 scoring cites the feed row's layout /
  // style / script ratios; `datasetFor` must keep the per-block split they
  // recompute from.

  const { datasetFor, foldDecomposition } = DRIVERS[1].mod;
  const round4 = (x) => Math.round(x * 10000) / 10000;

  // Per-block sums differ, so only summing all four blocks gives floor
  // 1.0 / 1.0 / 0.1 ms per sample against ctl-2x 2.06 / 1.85 / 0.23.
  const DECOMP_BLOCK = (n, layout, style, script) => ({
    n, task: layout + style + script, taskNet: 0, devtools: 0, script, style, layout, layoutCount: n, inPage: 0,
  });
  // Completeness is measured against the row's DECLARED roster, so every block
  // carries plumb as the committed data does.
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
  const FIXTURE_GRAIN = [0.094, 0.107, 0.123, 0.152];
  const DECLARED = { armIds: ['plumb', 'floor', 'ctl-2x'], rounds: 2, blocks: 2 };

  const fixtureRow = () => ({
    runId: 'reagent',
    rowId: 'feed',
    armIds: DECLARED.armIds,
    canon: { floor: true },
    ctlPredicted: 1.9943,
    blocksTask: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksNet: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksInPage: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
    blocksDecomp: FIXTURE_DECOMP,
    granularity: FIXTURE_GRAIN,
    tally: { writes: 36, unverified: 0 },
    runtime: 'fixture',
    quiet: { ok: true },
    windowStart: '2026-08-07T00:00:00.000Z',
    adjudication: {
      ctl: { ok: true, measured: { mean: 1.9943 } },
      cAdditive: 0.9,
      assessed: { bandStats: { band: 0.12 }, verdict: { ceilingBreached: false } },
      bars: {},
      verdicts: {},
      guardRefuse: false,
      plumb: 0.5,
      floorTared: 1.14,
    },
  });
  const META = { sha: 'deadbeef', blobs: {}, dest: { canonical: true, why: null } };
  const written = () => JSON.parse(JSON.stringify(datasetFor([fixtureRow()], META))).rows[0];

  t("the studio page's cited decomposition is recomputable from the file alone", () => {
    const fold = foldDecomposition(written().blocksDecomp, DECLARED);
    const mean = (arm, k) => fold[arm][k] / fold[arm].n;
    for (const [k, cited] of Object.entries(CITED)) {
      assert.strictEqual(round4(mean('ctl-2x', k) / mean('floor', k)), cited, `${k} must recompute to ${cited}x`);
    }
    // ... and the fold is the sum of the stored blocks.
    assert.strictEqual(fold.floor.n, 40);
    assert.strictEqual(round4(fold['ctl-2x'].layout), 82.4);
  });

  t('a row written BEFORE this change fails closed rather than folding to zeros', () => {
    assert.throws(() => foldDecomposition(undefined, DECLARED), /NOT recomputable/);
    assert.throws(() => foldDecomposition([], DECLARED), /NOT recomputable/);
  });

  t('the grain the row measured reaches the written dataset', () => {
    assert.deepStrictEqual(written().granularity, FIXTURE_GRAIN);
  });

  // --- PARTIAL and TRUNCATED evidence must refuse ---------------------------
  //
  // A fold that synthesised a missing field as zero, or anchored completeness
  // on the first surviving block, would turn a half-written dataset into a
  // plausible ratio. One row per refusal branch; the declared shape is the
  // anchor, which is what sees an arm lost from EVERY block.

  // JSON.stringify cannot carry NaN or undefined, so mutations apply AFTER the clone.
  const mutate = (fn) => {
    const c = JSON.parse(JSON.stringify(FIXTURE_DECOMP));
    fn(c);
    return c;
  };
  const ONE = { armIds: DECLARED.armIds, rounds: 1, blocks: 1 };

  t('partial or truncated evidence is refused, and the refusal names where', () => {
    for (const [what, blocks, needle, side, declared = DECLARED] of [
      ['a round with no blocks', [[]], 'carries no blocks', 'round 0', ONE],
      ['a block with no arms', [[{}]], 'carries no arms', 'round 0, block 0', ONE],
      ['a block that is not a block of arms', [[[]]], 'is not a block of arms', 'round 0, block 0', ONE],
      ['an arm with no accumulator', mutate((c) => (c[0][0].floor = null)), 'carries no accumulator (null)', 'arm "floor"'],
      ['an arm MISSING a metric', mutate((c) => delete c[0][1]['ctl-2x'].script), 'field "script" is absent', 'round 0, block 1, arm "ctl-2x"'],
      ['a NaN metric', mutate((c) => (c[1][1]['ctl-2x'].style = NaN)), 'field "style" is NaN', 'round 1, block 1, arm "ctl-2x"'],
      ['a block that took no samples', mutate((c) => (c[1][0].floor.n = 0)), 'field "n" is 0', 'round 1, block 0, arm "floor"'],
      ['a negative renderer count', mutate((c) => (c[0][1].floor.layoutCount = -1)), 'field "layoutCount" is -1', 'round 0, block 1, arm "floor"'],
      ['a dropped FINAL ROUND', mutate((c) => c.pop()), 'carries 1 round where the row declares 2', "the row's shape"],
      ['a round that LOST a block', mutate((c) => c[1].splice(0, 1)), 'carries 1 block where the row declares 2', 'round 1'],
      [
        'an arm removed from EVERY block',
        mutate((c) => {
          for (const rd of c) for (const b of rd) delete b['ctl-2x'];
        }),
        'missing ctl-2x',
        'round 0, block 0',
      ],
    ]) {
      assert.throws(
        () => foldDecomposition(blocks, declared),
        (e) => /not valid evidence/.test(e.message) && e.message.includes(needle) && e.message.includes(side),
        `${what} must refuse, naming "${needle}" at ${side}`
      );
    }
  });

  t('a fold offered no declared shape refuses rather than anchoring to the evidence', () => {
    for (const bad of [undefined, { armIds: DECLARED.armIds, rounds: 0, blocks: 2 }]) {
      assert.throws(() => foldDecomposition(FIXTURE_DECOMP, bad), /DECLARED shape/, `${JSON.stringify(bad)} must not fold`);
    }
  });

  // The counterweight: a rule that refuses all of the above must still accept
  // the committed splits, measured against each file's own `armIds` and `design`.
  tc("a stored split must match the row's own declared shape, dimensions included", () => {
    const dir = path.join(__dirname, 'data');
    let checked = 0;
    for (const d of fs.readdirSync(dir).filter((x) => x.startsWith('censusclock-'))) {
      for (const f of fs.readdirSync(path.join(dir, d)).filter((x) => x.endsWith('.json'))) {
        const data = archive.readRecord(path.join(dir, d, f));
        for (const r of data.rows.filter((x) => x.blocksDecomp)) {
          foldDecomposition(r.blocksDecomp, { armIds: r.armIds, rounds: data.design.rounds, blocks: data.design.blocks });
          checked += 1;
        }
      }
    }
    assert.ok(checked >= 3, 'expected the committed rows that carry the split');
  });
}

// --- the census run-rejection rule, on the committed datasets ----------------
//
// The strict all-blocks rule is RETAINED on this rig, and its `ok` is the
// decision (`summarise` -> `verdict` -> exit 5). Recomputed here through the
// driver's own `controlBlocks` and `controlVerdict`.

{
  const { controlBlocks, controlVerdict } = DRIVERS[1].mod;
  const tc = (what, fn) => corpusTest(`census run-rejection rate: ${what}`, fn);

  /** Every committed census row-run, its statistic recomputed by the driver's own arithmetic. */
  const corpus = () => {
    const dir = path.join(__dirname, 'data');
    return fs
      .readdirSync(dir)
      .filter((d) => d.startsWith('censusclock-'))
      .flatMap((d) =>
        fs
          .readdirSync(path.join(dir, d))
          .filter((f) => f.endsWith('.json'))
          .flatMap((f) => {
            const data = archive.readRecord(path.join(dir, d, f));
            return data.rows.map((r) => {
              const per = controlBlocks(r.blocksTask);
              return {
                where: `${d}/${f} ${r.rowId}`,
                stored: r.adjudication.ctl,
                band: r.adjudication.ctl.band,
                per,
                verdict: controlVerdict(r.ctlPredicted, per, data.design.controlSlack),
              };
            });
          })
      );
  };

  tc('the live arithmetic and the stored verdicts are the same quantity', () => {
    for (const r of corpus()) {
      assert.deepStrictEqual(r.verdict.perBlock, r.stored.perBlock, `${r.where}: recomputed blocks must match the stored ones`);
      assert.strictEqual(r.verdict.ok, r.stored.ok, `${r.where}: same strict verdict`);
      // `datasetFor` writes `r4(ctlPredicted)` while the run adjudicated on the
      // raw value, so a band edge may differ by one unit in the last place.
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
}

// --- the ordinary row's CENTRE, specified empirically -------------------------
//
// `ordinary` refuses on its CENTRE, not on the strict rule: its block median
// sits below the element-arithmetic band. The calibrated check standard
// (`shapes/census_check_standard.json`) adjudicates it instead. The frozen
// numbers are read from the JSON and recomputed here from the datasets its
// own `provenance` names.

{
  const CENSUS = DRIVERS[1].mod;
  const { controlBlocks, controlAdjudication, checkStandardVerdict, CHECK_STANDARD: STD, robustScale } = CENSUS;
  const t = (what, fn) => test(`census check standard: ${what}`, fn);
  const tc = (what, fn) => corpusTest(`census check standard: ${what}`, fn);

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
            const data = archive.readRecord(path.join(dir, d, f));
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

  t('a row the standard has never heard of THROWS rather than being waved past', () => {
    assert.throws(
      () => checkStandardVerdict('a-row-nobody-calibrated', [1.2, 1.25]),
      /is in neither `rows` nor `notInThisStandard`/
    );
  });

  tc('the CENTRE recomputes from the baseline the file names, and is not a literal in the code', () => {
    const base = ordinaries().filter((r) => setsOf('baseline').includes(r.set));
    const meds = base.map((r) => p50(r.per));
    const b = ORD.provenance.baseline;
    assert.deepStrictEqual(
      {
        rowRuns: base.length,
        blocks: base.reduce((a, r) => a + r.per.length, 0),
        centre: r4(p50(meds)),
        runMedianCentre: r4(p50(meds)),
        runMedianMean: r4(mean(meds)),
        runMedianRange: [r4(Math.min(...meds)), r4(Math.max(...meds))],
        betweenRunSD: r4(sd(meds)),
      },
      {
        rowRuns: b.rowRuns,
        blocks: b.blocks,
        centre: ORD.centre,
        runMedianCentre: b.observed.runMedianCentre,
        runMedianMean: b.observed.runMedianMean,
        runMedianRange: b.observed.runMedianRange,
        betweenRunSD: b.observed.betweenRunSD,
      }
    );
  });

  t('the LOCATION limits are centre +/- 3 x the between-run SD the file states', () => {
    const s = ORD.provenance.baseline.observed.betweenRunSD;
    assert.deepStrictEqual(ORD.location.limits, [r4(ORD.centre - 3 * s), r4(ORD.centre + 3 * s)]);
  });

  tc('the DISPERSION limit is the lognormal upper 3 sigma of the baseline robust scales', () => {
    const scales = ordinaries()
      .filter((r) => setsOf('baseline').includes(r.set))
      .map((r) => robustScale(r.per));
    const logs = scales.map(Math.log);
    const o = ORD.provenance.baseline.observed;
    assert.deepStrictEqual(
      [r4(Math.exp(mean(logs) + 3 * sd(logs))), r4(p50(scales)), r4(Math.max(...scales))],
      [ORD.dispersion.limit, o.robustScaleP50, o.robustScaleMax]
    );
  });

  tc('the HOLD-OUT row-runs are IN CONTROL against limits derived WITHOUT them', () => {
    const hold = ordinaries().filter((r) => setsOf('holdOut').includes(r.set));
    const h = ORD.provenance.holdOut;
    assert.deepStrictEqual(
      [hold.length, hold.map((r) => r4(p50(r.per))), hold.map((r) => r4(robustScale(r.per))), hold.map((r) => r.adj.standard.ok)],
      [h.rowRuns, h.observed.runMedians, h.observed.robustScales, hold.map(() => true)]
    );
  });

  tc('the CENTRE was the defect: the same blocks refuse against 1.7255 and hold against 1.2308', () => {
    // [the strict rule's answer, what the run recorded, the calibrated standard's answer]
    assert.deepStrictEqual(
      ordinaries().map((r) => [r.adj.strictOk, r.stored.ok, r.adj.ok]),
      Array.from({ length: 10 }, () => [false, false, true])
    );
  });

  tc('and the ADJUDICATOR is named on the row, so nobody has to infer which rule decided', () => {
    for (const r of corpus()) {
      if (r.rowId === 'ordinary') {
        assert.ok(r.adj.standard, 'ordinary must carry a standard verdict');
        assert.strictEqual(r.adj.ok, r.adj.standard.ok, 'a calibrated row is adjudicated by its standard');
        assert.match(r.adj.adjudicator, /calibrated check standard `census-clock\/ctl-2x-level` v1;/);
      } else {
        assert.strictEqual(r.adj.standard, null, `${r.rowId} must carry no standard`);
        assert.strictEqual(r.adj.ok, r.adj.strictOk, 'a row with no standard is adjudicated by the strict rule');
        assert.match(r.adj.adjudicator, /strict all-blocks rule/);
      }
    }
  });

  t('an empty, a partial, or a non-finite block set REFUSES rather than passing', () => {
    const withNaN = [...Array.from({ length: 17 }, () => ORD.centre), NaN];
    assert.deepStrictEqual([[], null, withNaN].map((b) => checkStandardVerdict('ordinary', b).ok), [false, false, false]);
    assert.match(checkStandardVerdict('ordinary', []).why, /empty block set/);
    assert.match(checkStandardVerdict('ordinary', withNaN).why, /not finite readings of a level ratio/);
    assert.strictEqual(checkStandardVerdict('ordinary', Array.from({ length: 18 }, () => ORD.centre)).ok, true);
  });

  t('a row-run OUTSIDE either frozen limit refuses, and the refusal says which', () => {
    const flat = (x) => Array.from({ length: 18 }, () => x);
    const low = checkStandardVerdict('ordinary', flat(ORD.location.limits[0] - 0.01));
    assert.deepStrictEqual([low.ok, low.location.ok, low.dispersion.ok], [false, false, true]);
    assert.match(low.why, /outside the frozen location limits/);
    assert.strictEqual(checkStandardVerdict('ordinary', flat(ORD.location.limits[1] + 0.01)).ok, false, 'the limits are two-sided');
    // a median dead on the centre whose blocks scatter
    const n = checkStandardVerdict('ordinary', Array.from({ length: 18 }, (_, i) => ORD.centre + (i % 2 ? 1 : -1) * 0.9));
    assert.deepStrictEqual([n.ok, n.location.ok, n.dispersion.ok], [false, true, false]);
    assert.match(n.why, /exceeds the frozen dispersion limit/);
  });

  t('every verdict carries the standard it was taken against, by id and version', () => {
    const v = checkStandardVerdict('ordinary', Array.from({ length: 18 }, () => ORD.centre));
    assert.deepStrictEqual(
      [v.standard.id, v.standard.version, v.location.limits, v.location.centre, v.dispersion.limit],
      [STD.id, STD.version, ORD.location.limits, ORD.centre, ORD.dispersion.limit]
    );
  });
}

// --- a REFUSED ROW refuses itself, not the RUN's publication ----------------
//
// Prediction P4: if the ordinary row's control or band cannot hold, the ROW
// publishes a refusal with the reason. Refusing the RUN instead would discard
// the `large-template` and `feed` rows that passed every gate — and since the
// ordinary row fails on every committed session, no full-shape run could ever
// be canonical. So the exit code refuses the run, each row carries its own
// `canonical`, and the directory is chosen by the run's SHAPE alone.

{
  const CENSUS = DRIVERS[1].mod;
  const {
    summarise, summariseRow, verdict, destination, datasetFor, rowPublication,
    controlBlocks, controlAdjudication, checkStandardVerdict, CHECK_STANDARD: STD,
  } = CENSUS;
  const t = (what, fn) => test(`census refusal scope: ${what}`, fn);

  const ORD = STD.rows.ordinary;
  const EXPECTED = STD.evidence.rounds * STD.evidence.blocks;

  /**
   * A capture of exactly `n` control blocks, every block reading the frozen
   * CENTRE, so anything that refuses it can only be refusing the COUNT. Laid
   * out in rounds of `evidence.blocks`, as a truncated capture arrives.
   */
  const centreBlocks = (n) => {
    const rounds = [];
    for (let i = 0; i < n; i += STD.evidence.blocks) {
      const width = Math.min(STD.evidence.blocks, n - i);
      rounds.push(Array.from({ length: width }, () => ({ plumb: [0], floor: [1], 'ctl-2x': [ORD.centre] })));
    }
    return rounds;
  };

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

  /**
   * One row as `drive` collects it, with its gates settable one at a time. A
   * `blocks` gate hands the row a real capture of that many blocks,
   * adjudicated through the driver's own `controlAdjudication`.
   */
  const resultRow = (rowId, gates = {}) => {
    const ctlPredicted = 1.7255;
    const blocksTask =
      gates.blocks === undefined
        ? [[{ plumb: [0.7], floor: [2, 2.1], 'ctl-2x': [3, 3.1] }]]
        : centreBlocks(gates.blocks);
    const ctl =
      gates.blocks === undefined
        ? { ok: gates.ctlOk !== false, measured: { mean: gates.ctlMeasured || 1.9943 } }
        : controlAdjudication(rowId, ctlPredicted, controlBlocks(blocksTask), ORD.tolerance.slack);
    return {
      runId: 'uix',
      rowId,
      armIds: ['plumb', 'floor', 'ctl-2x'],
      canon: { floor: { hash: 'a', bytes: 10, control: true } },
      ctlPredicted,
      blocksTask,
      blocksNet: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
      blocksInPage: [[{ floor: [1, 2], 'ctl-2x': [2, 4] }]],
      blocksDecomp: [[{ plumb: DECOMP(), floor: DECOMP(), 'ctl-2x': DECOMP() }]],
      tally: { writes: 36, unverified: gates.unverified || 0 },
      runtime: 'fixture',
      quiet: { ok: true },
      windowStart: '2026-08-08T00:00:00.000Z',
      adjudication: {
        ctl,
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
    };
  };

  /** The full published shape: the three rows, in the order the driver takes them. */
  const fullShape = (gatesByRow = {}) =>
    ['large-template', 'feed', 'ordinary'].map((id) => resultRow(id, gatesByRow[id] || {}));
  const META = (dest) => ({ sha: 'deadbeef', blobs: {}, dest });
  const written = (rows, dest = PUBLISHED()) => JSON.parse(JSON.stringify(datasetFor(rows, META(dest))));

  t('a full-shape run with every gate held is canonical, and every row is citable', () => {
    const data = written(fullShape());
    assert.deepStrictEqual(
      [data.canonical, data.notCanonicalWhy, data.rowsRefused, data.rows.map((r) => [r.canonical, r.notCanonicalWhy])],
      [true, null, [], [[true, null], [true, null], [true, null]]]
    );
    assert.strictEqual(verdict(summarise(null, fullShape())).code, 0);
  });

  t('ORDINARY REFUSED, THE OTHER TWO CLEAN: the two stay canonical and the run still refuses', () => {
    const rows = fullShape({ ordinary: { ctlOk: false, ctlMeasured: 1.2264 } });
    const v = verdict(summarise(null, rows));
    assert.deepStrictEqual([v.code, v.lines.length], [5, 1], 'the run-level refusal is NOT weakened into nothing');
    assert.match(v.lines[0], /uix\/ordinary \(measured 1\.2264x\)/);
    assert.doesNotMatch(v.lines[0], /large-template|feed/, 'a clean row must not be blamed');
    // `destination` takes the run SHAPE and nothing else, so a gate refusal has
    // no way to move the run off the canonical set.
    assert.strictEqual(destination.length, 1);
    const data = written(rows);
    assert.deepStrictEqual(data.rowsRefused, ['ordinary'], 'the file indexes exactly the row that refused');
    assert.deepStrictEqual(data.rows.map((r) => r.canonical), [true, true, false]);
    assert.match(data.rows[2].notCanonicalWhy, /POSITIVE CONTROL did not hold \(measured 1\.2264x\)/);
    assert.ok(data.rows[2].blocksTask, 'the refused row is recorded, not deleted');
  });

  t('each gate alone refuses THAT row and no other', () => {
    for (const [gates, needle] of [
      [{ guardRefuse: true }, /ARM-ORDER GUARD refused it/],
      [{ unverified: 4 }, /4 of 36 operations are UNVERIFIED/],
      [{ ceilingBreached: true, band: 0.412 }, /band 41\.2% exceeds seam\.cjs's 35% ceiling/],
      [{ ctlOk: false, ctlMeasured: 1.2264 }, /POSITIVE CONTROL did not hold/],
    ]) {
      const data = written(fullShape({ feed: gates }));
      assert.deepStrictEqual(data.rows.map((r) => r.canonical), [true, false, true], JSON.stringify(gates));
      assert.match(data.rows[1].notCanonicalWhy, needle);
    }
  });

  t('NO row of a run that is not the published shape is citable, whatever its own gates did', () => {
    const data = written(fullShape(), destination(shape({ noBuild: true })));
    assert.deepStrictEqual([data.canonical, data.rowsRefused], [false, ['large-template', 'feed', 'ordinary']]);
    assert.match(data.notCanonicalWhy, /--no-build/);
    for (const r of data.rows) assert.match(r.notCanonicalWhy, /the run itself is not the published evidence: --no-build/);
  });

  t('an absent row and an absent destination are each a REFUSAL, never a pass', () => {
    const clean = summariseRow(resultRow('feed'));
    assert.deepStrictEqual(
      [rowPublication(undefined, PUBLISHED()).canonical, rowPublication(null, PUBLISHED()).canonical, rowPublication(clean, undefined).canonical],
      [false, false, false]
    );
    assert.match(rowPublication(undefined, PUBLISHED()).why, /an absent row is not a citable one/);
    assert.match(rowPublication(clean, undefined).why, /no destination was decided for this run/);
    assert.deepStrictEqual([rowPublication(clean, PUBLISHED()).canonical, rowPublication(clean, PUBLISHED()).why], [true, null]);
  });

  // --- the standard requires its DECLARED EVIDENCE CARDINALITY --------------
  //
  // The calibrated limits are statistics OF 18-block row-runs, and the
  // standard is THE adjudicator on a calibrated row, so one block at the
  // centre certified as in control would publish truncated evidence. The
  // precondition is exactly the declared evidence: one short, one over.

  t('ONE and SEVENTEEN blocks at the frozen centre REFUSE the row, where eighteen hold', () => {
    const whole = resultRow('ordinary', { blocks: EXPECTED });
    assert.deepStrictEqual([whole.adjudication.ctl.standard.ok, whole.adjudication.ctl.ok], [true, true], 'the twin must PASS');
    assert.strictEqual(verdict(summarise(null, [whole])).code, 0);
    for (const n of [1, 17]) {
      const r = resultRow('ordinary', { blocks: n });
      const { ok, measured, location, dispersion, why } = r.adjudication.ctl.standard;
      assert.deepStrictEqual(
        { ok, n: measured.n, expected: measured.expected, finite: measured.finite, location, dispersion },
        { ok: false, n, expected: EXPECTED, finite: n, location: null, dispersion: null },
        `${n} blocks must refuse on the COUNT, before any statistic`
      );
      assert.match(why, new RegExp(`^${n} finite blocks where this standard's evidence is ${EXPECTED} `));
      assert.match(why, new RegExp(`${EXPECTED - n} MISSING`));
      assert.strictEqual(r.adjudication.ctl.ok, false, 'the standard adjudicates a calibrated row, so the row does not hold');
      assert.strictEqual(verdict(summarise(null, [r])).code, 5);
    }
  });

  t('EXTRA blocks refuse too, and the refusal counts them', () => {
    const v = checkStandardVerdict('ordinary', controlBlocks(centreBlocks(EXPECTED + 1)));
    assert.deepStrictEqual([v.ok, v.measured.n, v.measured.expected, v.location], [false, EXPECTED + 1, EXPECTED, null]);
    assert.match(v.why, /1 EXTRA/);
  });
}

// --- hd8_clock_run.cjs: the WRITE path ---------------------------------------
//
// Two-tier: no completed measurement is ever discarded, but only a
// gate-passing full-shape run gets the published names.

{
  const { destination } = DRIVERS[0].mod;
  const SRC = fs.readFileSync(DRIVERS[0].file, 'utf8');
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
    assert.deepStrictEqual(destination(shape(), 0), { dir: CANON, canonical: true, why: null });
  });

  t('a non-published shape is NOT canonical, and says why', () => {
    for (const [over, needle] of [
      [{ runsOnly: 'uix' }, /PARTIAL run set \(HD8CLOCK_ONLY=uix\)/],
      [{ noBuild: true }, /--no-build/],
      [{ depthPublished: false }, /OVERRIDDEN design depth/],
    ]) {
      const d = destination(shape(over), 0);
      assert.deepStrictEqual([d.canonical, d.dir], [false, `${CANON}.unpublished`], JSON.stringify(over));
      assert.match(d.why, needle);
    }
  });

  // A control-refused re-run must land beside the cited evidence, not over it.
  t('EVERY refusal code this driver can return routes off the canonical set', () => {
    for (const code of [1, 5]) {
      const d = destination(shape(), code);
      assert.deepStrictEqual([d.canonical, d.dir], [false, `${CANON}.unpublished`], `exit ${code}`);
      assert.match(d.why, new RegExp(`verdict refused it \\(exit ${code}\\)`));
    }
  });

  t('an explicit HD8CLOCK_DATA_DIR is honoured as given, and is never canonical', () => {
    const mine = '/data/hd8clock-somebead';
    const d = destination(shape({ dataDir: mine, dataDirOverridden: true }), 0);
    assert.deepStrictEqual([d.dir, d.canonical], [mine, false]);
    assert.strictEqual(destination(shape({ dataDir: mine, dataDirOverridden: true }), 4).dir, mine, 'even refused, it lands where the operator said');
  });

  t('no dataset is written to the raw DATA_DIR downstream of `destination`', () => {
    const after = SRC.slice(SRC.indexOf('const dest = destination(runShape(), v.code);'));
    assert.ok(!/fs\.mkdirSync\(DATA_DIR/.test(after), 'mkdirSync must use the chosen destination');
    assert.ok(!/path\.join\(DATA_DIR/.test(after), 'the dataset path must use the chosen destination');
    assert.match(after, /path\.join\(dest\.dir/);
  });
}

// --- clock_run.cjs: the bar-level adjudication must reach the exit code -------
//
// A row whose every bar is UNADJUDICATED, or whose bars disagree, must not exit
// 0. The rule is `rowAdjudication` (strict, `=== false`) and the decision is
// `reportability`; both are driven by the driver's own `reportabilitySelfTest`
// fixtures — the mixed-bar remainder, absent and null bars, the control gate
// and the regimes — which `--self-test` also runs.

{
  const { reportabilitySelfTest, ctl3SelfTest } = require('./clock_run.cjs');
  const SRC = fs.readFileSync(path.join(__dirname, 'clock_run.cjs'), 'utf8');
  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));
  const t = (what, fn) => test(`clock_run.cjs: ${what}`, fn);

  t("the decision's own self-test passes, every case", () => {
    const { checks } = reportabilitySelfTest();
    assert.ok(checks.length >= 33, `expected the decision's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad, [], bad.map((c) => `${c.name}: ${c.detail}`).join('\n'));
  });

  // The driver dies before the browser opens if one of these fails; driving
  // them here puts them in the fast spine.
  t("the three-point control's own self-test passes, every case", () => {
    const { checks } = ctl3SelfTest();
    assert.ok(checks.length >= 15, `expected the control's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad.map((c) => c.name), [], 'the control must refuse every world its fixtures refuse');
  });

  t('the summary reads the adjudication the report printed, rather than recomputing it', () => {
    assert.match(MAIN, /rowAdjudication\(o\.verdict\.seamTask && o\.verdict\.seamTask\.rows\)/);
    // An inline copy of the bar rule could be checked only by a regex on its
    // line, which would hold a loose rule as happily as the strict one.
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
}

// --- A RUN'S RAW READINGS, AS A FIXTURE --------------------------------------
//
// The check standard is applied to the READINGS, so every fixture that must
// clear it carries them, in the shape `clock_run.cjs` writes:
// `roundsTask[round][segment][arm]`, ten samples an arm. A floor sample is
// `W + c`, `ctl-2x` builds twice the page (`2W + c`), and `W = 3.0`,
// `c = 1.1628` put the block ratio on the frozen bulk centre, 1.7207x, with a
// small deterministic jitter so the dispersion term is exercised.
const FIXTURE_SEGMENTS = ['reagent-subs', 'uix-subs', 'fresco'];
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
        // the segment's own substrate arm, which the paired level ratio is formed from
        [seg]: ten(5.0 + i * 0.1 + j),
      };
    }
    rounds.push(per);
  }
  return rounds;
}

// --- clock_readjudicate.cjs: the SAME term, on the persisted datasets --------
//
// The readjudicator pools an ENSEMBLE of stored datasets into the published
// figure, so the fail-open has a second home here: one adjudicated bar must
// not admit a run into the subset for every pair, a bar stored as `{}` or
// `null` is not adjudicated, and every gate the driver exits on is enforced
// again off the record.

{
  const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
  const { GATES, adjudicated, refusals, reportable, responsivenessRegime } = require('./clock_readjudicate.cjs');
  const RJSRC = fs.readFileSync(RJ, 'utf8');
  const t = (what, fn) => test(`clock_readjudicate.cjs: ${what}`, fn);
  const ADJ = { unadjudicated: false, band: 0.21, why: 'margin 34.8% clears the band 21.4%' };
  const UNADJ = { unadjudicated: true, band: null, why: 'UNADJUDICATED — no proportional control on this row' };
  // The file's own two-tier verdict, handed to every predicate: a row cannot
  // vouch for the file it came from. `design` decides what its readings mean.
  const CANON = { canonical: true, notCanonicalWhy: null, design: { rounds: 6, warmup: 4, samples: 10, tare: true } };
  // A dataset row as `clock_run.cjs` writes it, every whole-run verdict at its
  // passing value. `bulk300` because its readings are built at the BULK centre.
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
    const r = dsRow({ 'h / r': ADJ, 'h / u': UNADJ, 'u / r': ADJ });
    assert.strictEqual(adjudicated(r), false, 'two adjudicated bars may not carry a third with no band');
    assert.strictEqual(reportable(r, CANON), false, 'and the run may not be pooled into the published mean');
  });

  t('a dataset that stored no bar verdict at all fails closed', () => {
    assert.strictEqual(adjudicated(dsRow({})), false, 'an empty bar set is absent, not clean');
    assert.strictEqual(adjudicated({ rowId: 'M1' }), false, 'a row with no seamTask at all is absent, not clean');
  });

  t('a dataset whose every bar is fieldless is out, and it used to be IN', () => {
    const r = dsRow({ 'h / r': {}, 'h / u': {} });
    assert.strictEqual(adjudicated(r), false);
    assert.strictEqual(reportable(r, CANON), false);
  });

  t('a bar stored as null or undefined is out rather than a crash', () => {
    for (const missing of [null, undefined]) {
      assert.strictEqual(adjudicated(dsRow({ 'h / r': ADJ, 'h / u': missing })), false, `bar=${String(missing)}`);
      assert.strictEqual(reportable(dsRow({ 'h / r': ADJ, 'h / u': missing }), CANON), false, `bar=${String(missing)}`);
    }
  });

  t('an EXPLICIT clean verdict is what pools a run — the tightening is not vacuous', () => {
    const r = dsRow({ 'h / r': { unadjudicated: false }, 'h / u': { unadjudicated: false, band: 0.2 } });
    assert.strictEqual(adjudicated(r), true);
    assert.strictEqual(reportable(r, CANON), true);
  });

  // --- EVERY GATE THE DRIVER EXITS ON, ENFORCED HERE TOO --------------------
  //
  // `clock_run.cjs` writes its dataset BEFORE its fatal checks run, so a run
  // Chromium threw on is still a well-formed file. Driven over the `GATES`
  // roster, with one corruption and one ERASURE per gate: `failed` and
  // `absent` are different faults and both must refuse.

  const del = (o, k) => {
    const c = { ...o };
    delete c[k];
    return c;
  };
  const BARS = { 'h / r': ADJ, 'h / u': ADJ };
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
      // `ctlScale: 140/300` is an arm that declares the page doubled and builds
      // 140 of its 300 boundaries; the frozen location limits refuse it.
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
    assert.deepStrictEqual(refusals(dsRow(BARS), CANON), []);
  });

  t('every gate: a FAILED verdict removes the run from the subset, and names itself', () => {
    for (const c of CASES) {
      const why = refusals(dsRow(c.bars || BARS, c.row), { ...CANON, ...c.data });
      assert.ok(why.some((w) => c.failed.test(w)), `${c.id}: no refusal matched ${c.failed} — got ${JSON.stringify(why)}`);
    }
  });

  t('every gate: an ABSENT verdict removes it too — absent is not clean', () => {
    for (const c of CASES) {
      let r = dsRow(c.erase.bars ? {} : c.bars || BARS);
      if (c.erase.row) r = del(r, c.erase.row);
      const why = refusals(r, c.erase.data ? del(CANON, c.erase.data) : CANON);
      assert.ok(why.some((w) => c.absent.test(w)), `${c.id}: no refusal matched ${c.absent} — got ${JSON.stringify(why)}`);
    }
  });

  // --- AND THE WHOLE PROGRAM, END TO END, ON A FILE -------------------------

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
        ...dsRow({ 'fresco / reagent-subs': ADJ }),
        granularity: [0.146],
        inPageRounds: [],
        decomposition: {
          'reagent-subs/plumb': { n: 60, task: 36, taskNet: 12, devtools: 24, script: 0.06, layout: 5 },
          'reagent-subs/floor': { n: 60, task: 360, taskNet: 280, devtools: 80, script: 0.06, layout: 40 },
          'reagent-subs/reagent-subs': { n: 60, task: 600, taskNet: 280, devtools: 320, script: 0.06, layout: 60 },
          'fresco/fresco': { n: 60, task: 780, taskNet: 320, devtools: 460, script: 0.06, layout: 70 },
        },
        ctlTask: { ok: true, measured: { mean: 1.9 } },
        seam: { band: 0.06, verdict: { ceilingBreached: false } },
        seamTask: { ceilingBreached: false, band: 0.05, rows: { 'fresco / reagent-subs': ADJ } },
        bandTask: 0.05,
        bar: { 'fresco / reagent-subs': { tared: { mean: 1.1 } } },
        inPageBar: { 'fresco / reagent-subs': { mean: 1.2 } },
        barTask: { 'fresco / reagent-subs': { mean: 1.3, min: 1.2, max: 1.4 } },
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

  // A refusal is about what may be QUOTED: the run stays in the per-run table.
  t('THE COMMAND: a non-canonical dataset exits 3 and is still printed in full', () => {
    const { code, out } = runProgram(fixture({ canonical: false, notCanonicalWhy: '--no-build' }));
    assert.strictEqual(code, 3, out);
    assert.match(out, /reportable subset: NONE/);
    assert.match(out, /;;\s+run1\s+1\.2000\s+1\.1000\s+1\.3000/);
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
  });

  t('the subset is chosen by `reportable` alone, not by a second predicate inline', () => {
    const body = RJSRC.slice(RJSRC.indexOf('function main(argv)'));
    assert.match(body, /runs\.map\(\(\{ row, data \}, i\) => \(reportable\(row, data\) \? i : -1\)\)/);
    // `main` may READ a bar verdict to print it; quantifying over the bars is
    // the subset predicate written a second time.
    assert.ok(
      !/(names|Object\.keys)[^\n]*\.(some|every)\(/.test(body),
      'quantifying over a row\'s bars inside `main` is the subset predicate written twice'
    );
  });

  // --- THE RESPONSIVENESS REGIME, off the retained datasets -----------------

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
      'fresco / reagent-subs': { mean: 0.9491, min: 0.7328, max: 1.1596 },
      'fresco / uix-subs': { mean: 0.9968, min: 0.8059, max: 1.2172 },
      'uix-subs / reagent-subs': { mean: 0.9548, min: 0.771, max: 1.0546 },
    },
    kbWitness: {
      totals: { sent: 180, observed: 140, censored: 40 },
      perArm: {
        'fresco/fresco': etArm(frames(49)),
        'reagent-subs/reagent-subs': etArm(frames(46)),
        'uix-subs/uix-subs': etArm(frames(45)),
        'fresco/ctl-50ms': etArm(Array(60).fill(48)),
      },
    },
    ...over,
  });

  t('every other row is not a responsiveness regime, and says so by returning nothing', () => {
    assert.strictEqual(responsivenessRegime({ rowId: 'M1' }), null);
  });

  t('one bucket across every observed arm IS the verdict, and the control must have moved', () => {
    const r = responsivenessRegime(kbRow());
    assert.deepStrictEqual([r.indistinguishable, r.frame, r.controlP50, r.controlMoved], [true, 16, 48, true]);
  });

  t('THE GATE IS NOT VACUOUS: an arm in a second bucket refuses the frame statement', () => {
    const r = responsivenessRegime(
      kbRow({
        kbWitness: {
          totals: { sent: 180, observed: 140, censored: 40 },
          perArm: {
            'fresco/fresco': etArm(Array(49).fill(24)),
            'reagent-subs/reagent-subs': etArm(frames(46)),
            'fresco/ctl-50ms': etArm(Array(60).fill(48)),
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
          perArm: { 'fresco/fresco': etArm(frames(49)), 'fresco/ctl-50ms': etArm(frames(60)) },
        },
      })
    );
    assert.strictEqual(r.controlMoved, false);
  });

  t('THE RIDER is carried, not optional: the grain and the straddling bars come back with it', () => {
    const r = responsivenessRegime(kbRow());
    assert.strictEqual(r.grain, 0.146, "the run's own finest per-sample step, as the driver stored it");
    assert.deepStrictEqual(r.diagnosticBars.map((b) => b.straddles1), [true, true, true]);
  });

  t('THE RULING RE-ADJUDICATED FROM DISK, and the datasets still say what it said', () => {
    const dir = path.join(__dirname, 'data', 'clock-0qj9w');
    if (!fs.existsSync(dir)) return; // datasets are retained, not required to build
    const expected = { 'run1.json': { observed: 466, censored: 74, ctl: 48 }, 'run2.json': { observed: 449, censored: 91, ctl: 56 } };
    for (const [file, want] of Object.entries(expected)) {
      const r = responsivenessRegime(archive.readRecord(path.join(dir, file)).rows.find((x) => x.rowId === 'keystroke'));
      assert.deepStrictEqual(
        [r.indistinguishable, r.frame, r.controlP50, r.totals.observed, r.totals.censored, r.totals.sent, r.diagnosticBars.every((b) => b.straddles1)],
        [true, 16, want.ctl, want.observed, want.censored, 540, true],
        file
      );
    }
  });
}

// --- THE PRODUCER HALF: clock_run.cjs must WRITE every verdict it is gated on
//
// The roster above refuses on ABSENT as well as on failed, so a dataset the
// current serialiser writes must satisfy every gate, and erasing any gate's
// field from that same record must refuse again. Driven over `GATES`, so a
// gate added with no producer field fails here.

{
  const { datasetFor, publication } = require('./clock_run.cjs');
  const { GATES, refusals, reportable } = require('./clock_readjudicate.cjs');
  const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
  const t = (what, fn) => test(`clock_run.cjs -> clock_readjudicate.cjs: ${what}`, fn);

  const ADJ = { unadjudicated: false, band: 0.21, why: 'margin 34.8% clears the band 21.4%' };
  const BARS = { 'fresco / reagent-subs': ADJ };

  /** One outcome as `runRow` and `report` hand it to the write path, at every gate's passing value. */
  const outcome = (outOver, verdictOver) => ({
    out: {
      rowId: 'bulk300',
      rounds: [],
      roundsTask: fixtureRoundsTask(),
      roundsLayout: [],
      inPageRounds: [],
      granularity: [0.146],
      decomposition: {
        'reagent-subs/plumb': { n: 60, task: 36, taskNet: 12, devtools: 24, script: 0.06, layout: 5 },
        'reagent-subs/floor': { n: 60, task: 360, taskNet: 280, devtools: 80, script: 0.06, layout: 40 },
        'reagent-subs/reagent-subs': { n: 60, task: 600, taskNet: 280, devtools: 320, script: 0.06, layout: 60 },
        'fresco/fresco': { n: 60, task: 780, taskNet: 320, devtools: 460, script: 0.06, layout: 70 },
      },
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
      checkStandard: { ok: true, standard: { id: 'fresco-clock/ctl-2x-level', version: 1 }, why: null },
      constants: null,
      guardVerdict: { refuse: false },
      guardVerdictTask: { refuse: false },
      parityOk: true,
      bar: { 'fresco / reagent-subs': { tared: { mean: 1.1 } } },
      inPageBar: { 'fresco / reagent-subs': { mean: 1.2 } },
      barTask: { 'fresco / reagent-subs': { mean: 1.3, min: 1.2, max: 1.4 } },
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

  t('each of the four is COPIED off the verdict, never defaulted to a passing value', () => {
    const rec = produce(
      { pageErrors: ['TypeError: undefined is not a function'] },
      { parityOk: false, etVerdict: { ok: false, predicted: 1, measured: 3 } }
    );
    const r = rec.rows[0];
    assert.deepStrictEqual(
      [r.pageErrors, r.parityOk, r.etVerdict],
      [['TypeError: undefined is not a function'], false, { ok: false, predicted: 1, measured: 3 }]
    );
    assert.strictEqual(reportable(r, rec), false, 'and a record carrying them is refused');
  });

  // Per gate rather than per field, because two gates read one object
  // (`seamTask` carries both the task ceiling and the bar set). The
  // check-standard gate's producer field is the READINGS it is applied to.
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
    'check-standard': (rec) => delete rec.rows[0].roundsTask,
    'event-timing': (rec) => delete rec.rows[0].etVerdict,
    adjudication: (rec) => delete rec.rows[0].seamTask.rows,
  };

  t('THE WHOLE ROSTER IS SATISFIABLE by a freshly written dataset — all thirteen', () => {
    const rec = produce();
    assert.deepStrictEqual(refusals(rec.rows[0], rec), [], 'a full-shape clean run must clear every gate');
  });

  t('every gate: erasing its field from a freshly written record REFUSES again', () => {
    for (const g of GATES) {
      const red = produce();
      ERASE[g.id](red);
      const why = g.scope === 'dataset' ? g.why(red) : g.why(red.rows[0], red);
      assert.ok(typeof why === 'string' && why.length > 0, `erasing \`${g.id}\`'s field must refuse — absent is not clean`);
    }
  });

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

  // --- THE SHAPE VERDICT: never canonical by default -------------------------

  const full = { rowsOnly: null, noBuild: false, depthPublished: true, tare: true, sabotage: null };

  t('a full-shape run IS canonical — the verdict is not vacuous', () => {
    assert.deepStrictEqual(publication(full), { canonical: true, why: null });
  });

  t('a narrowed run is NOT the published evidence set, and the file says why', () => {
    for (const [over, why] of [
      [{ rowsOnly: 'keystroke' }, /a PARTIAL row set \(HCLOCK_ONLY=keystroke\)/],
      [{ noBuild: true }, /--no-build/],
      [{ depthPublished: false }, /an OVERRIDDEN design depth/],
      [{ tare: false }, /the tare DISABLED \(HCLOCK_TARE=off\)/],
      [{ sabotage: 140 }, /a FALSIFICATION run \(HCLOCK_CTL3_SABOTAGE=140\)/],
    ]) {
      const p = publication({ ...full, ...over });
      assert.strictEqual(p.canonical, false, JSON.stringify(over));
      assert.match(p.why, why);
    }
  });

  t('the driver writes what `datasetFor` returns, and derives the shape from its own knobs', () => {
    const SRC = fs.readFileSync(path.join(__dirname, 'clock_run.cjs'), 'utf8');
    const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));
    assert.match(MAIN, /const pub = publication\(runShape\(\)\);/);
    assert.match(MAIN, /datasetFor\(outcomes, \{ chromium: version, publication: pub \}\)/);
  });
}

// --- hd8_run.cjs: the read-back and the refused correction must exit -------
//
// The HD-008 donor driver is not a clock driver but carries the same copied
// exit block. Its decision fixtures — a failed DOM read-back, a refused yield
// correction, a page error, the grain limit that is NOT a fault, and masking —
// are `verdictSelfTest`, which `--self-test` also runs.

{
  const { verdictSelfTest } = require('./hd8_run.cjs');
  const SRC = fs.readFileSync(path.join(__dirname, 'hd8_run.cjs'), 'utf8');
  const MAIN = SRC.slice(SRC.indexOf('async function main()'), SRC.indexOf('\nmodule.exports'));
  const t = (what, fn) => test(`hd8_run.cjs: ${what}`, fn);

  t("the decision's own self-test passes, every case", () => {
    const { checks } = verdictSelfTest();
    assert.ok(checks.length >= 24, `expected the decision's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad, [], bad.map((c) => `${c.name}: ${c.detail}`).join('\n'));
  });

  t('the sentinel\'s failures are READ, which they were not at all', () => {
    assert.match(SRC, /const pageErrors = watch\.failures\.map/);
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
    // Matched on the condition rather than the bare word: the `ok` line
    // legitimately says "no yield correction was refused".
    assert.ok(
      !/if \(\s*(hardFail|contractFailed|refused|orderRefused)\s*\)/.test(tail),
      'nothing downstream of the decision may read a refusal on its own'
    );
  });
}

// --- THE SELF-TEST FLAG, AND THE ONE THAT IS RETIRED ------------------------
//
// Both drivers take `--self-test` and no alias. A retirement is only safe if
// the driver validates its arguments: otherwise the old spelling is silently
// ignored and falls through into an `:advanced` release build and a headless
// Chromium. So the argv definition, the closed vocabulary, and the refusal
// wired ahead of everything the driver would otherwise spend.
{
  for (const d of [
    { name: 'clock_run.cjs', mod: require('./clock_run.cjs'), flags: ['--no-build', '--self-test'] },
    { name: 'hd8_run.cjs', mod: require('./hd8_run.cjs'), flags: ['--self-test'] },
  ]) {
    const SRC = fs.readFileSync(path.join(__dirname, d.name), 'utf8');
    const t = (what, fn) => test(`${d.name}: ${what}`, fn);

    t('the self-test flag is spelt `--self-test` where argv is read', () => {
      assert.match(SRC, /const SELFTEST_ONLY = process\.argv\.includes\('--self-test'\);/);
    });

    t('an argument outside the vocabulary is refused, the retired spelling included', () => {
      // No positional argument either: these drivers take their knobs from the environment.
      assert.deepStrictEqual(
        d.mod.unknownFlags([...d.flags, '--selftest', '--nope', 'self-test']),
        ['--selftest', '--nope', 'self-test']
      );
    });

    t('the refusal is wired ahead of every self-test, build and browser', () => {
      const head = SRC.slice(SRC.indexOf('async function main() {'), SRC.indexOf('  if (SELFTEST_ONLY) {'));
      assert.match(head, /const unknown = unknownFlags\(process\.argv\.slice\(2\)\);/);
      assert.match(head, /if \(unknown\.length > 0\) \{/);
      assert.match(head, /process\.exit\(2\);/);
      assert.ok(
        head.indexOf('unknownFlags(') < head.indexOf('SelfTest()') || !head.includes('SelfTest()'),
        'the argument check must come before the adjudicators run, not after'
      );
      assert.ok(
        !/build\(\)|chromium/.test(head),
        'nothing may be built or launched above the argument check — that is the whole point of it'
      );
    });
  }
}

// --- AND THE THIRD DRIVER, WHOSE VOCABULARY MUST HOLD PAST A PREFIX --------
//
// A parser that ran the self-test and returned from inside its argv loop would
// never read a token after `--self-test`, refusing the retired spelling in one
// order and swallowing it in the other. `ladder_band.cjs` takes dataset
// filenames, and `--emit` / `--from` consume the token after them, so its rule
// is `parseArgv`: pure, total, reads the whole vector before returning a plan.
{
  const LB = path.join(__dirname, 'ladder_band.cjs');
  const lb = require('./ladder_band.cjs');
  const t = (what, fn) => test(`ladder_band.cjs: ${what}`, fn);

  /** The driver as an operator meets it: a real process, a real exit code. */
  const run = (...argv) => cp.spawnSync(process.execPath, [LB, ...argv], { encoding: 'utf8' });

  t('an unknown flag is refused AFTER `--self-test` — the position that used to exit 0', () => {
    assert.deepStrictEqual(lb.parseArgv(['--self-test', '--selftest']), { error: 'unknown flag --selftest' });
    // and after a VALUED flag, the other way a parser stops reading early
    assert.deepStrictEqual(lb.parseArgv(['--from', 'x.json', '--selftest']), { error: 'unknown flag --selftest' });
  });

  t('the refusal is the process exit, in both positions and not just the plan', () => {
    assert.strictEqual(run('--self-test', '--selftest').status, 2, 'retired spelling AFTER the self-test flag');
    assert.strictEqual(run('--selftest', '--self-test').status, 2, 'retired spelling BEFORE it');
    const ok = run('--self-test');
    assert.strictEqual(ok.status, 0, 'and the flag itself still works');
    assert.match(ok.stdout, /ladder_band self-test ok/);
  });

  t('a valued flag consumes its value, so a filename is never read as a flag', () => {
    assert.deepStrictEqual(lb.parseArgv(['--emit', '--from.json', 'a.json']), {
      plan: { selfTest: false, emit: '--from.json', from: null, files: ['a.json'] },
    });
    assert.deepStrictEqual(lb.parseArgv(['--emit']), { error: '--emit needs a filename' });
    assert.deepStrictEqual(lb.parseArgv(['--from']), { error: '--from needs a filename' });
  });
}

// --- THE ADJECTIVE, which is where an instrument error hides ---------------
//
// `taskNet` is `TaskDuration` less `DevToolsCommandDuration`, and every arm's
// operation runs inside a protocol command Chromium bills whole, so the
// subtraction removes the operation's own script: `taskNet` is FRAME-ONLY.
// No line of a `Performance.getMetrics` driver that is not wholly a comment
// may LABEL a reading "frame-inclusive"; prose may discuss the mislabel.
// `chrome_run.cjs` is deliberately not on the roster: its in-page span closes
// after a `requestAnimationFrame` + `setTimeout` and genuinely spans the frame.
{
  const CDP_DRIVERS = [
    path.join(__dirname, 'clock_run.cjs'),
    path.join(__dirname, 'hd8_clock_run.cjs'),
    path.join(__dirname, 'shapes', 'census_clock_run.cjs'),
  ];
  // Whole-line comments only, so a label followed by a trailing comment is still searched.
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
}

// --- THE CHECK STANDARD, AND WHAT MAY PUBLISH A MAGNITUDE --------------------
//
// The three-point control is retired as a GATE (it prints, labelled
// non-gating), and so is the all-blocks strict rule on this instrument. In
// their place: a versioned, empirically calibrated check standard as the gate
// (its own fixtures, `checkStandardSelfTest`), and a run-preserving
// effect-size interval as the publication rule.
{
  const { checkStandardSelfTest } = require('./clock_check_standard.cjs');
  const { effectInterval, effectVerdict, reportable } = require('./clock_readjudicate.cjs');
  const CLOCKSRC = fs.readFileSync(path.join(__dirname, 'clock_run.cjs'), 'utf8');
  const RJSRC2 = fs.readFileSync(path.join(__dirname, 'clock_readjudicate.cjs'), 'utf8');
  const t = (what, fn) => test(`rf2-8a746: ${what}`, fn);
  const BULK = 'bulk300';

  t("the standard's own fixtures pass, every case — a standard nobody has seen refuse is not one", () => {
    const { checks } = checkStandardSelfTest();
    assert.ok(checks.length >= 41, `expected the standard's fixtures, got ${checks.length}`);
    const bad = checks.filter((c) => !c.ok);
    assert.deepStrictEqual(bad.map((c) => `${c.name}: ${c.detail}`), []);
  });

  t('criterion 1: neither program reads the three-point statistic into a decision', () => {
    // `ctl3Parity` stays: it is a canonical-DOM refusal, not the statistic.
    assert.ok(!/ctl3\.ok|ctl3Layout\.ok|ctl3Net\.ok/.test(CLOCKSRC.replace(/^\s*\/\/.*$/gm, '')), 'the driver reads no ctl3 verdict');
    assert.ok(!/r\.ctl3\b(?!Parity)/.test(RJSRC2.replace(/^\s*\/\/.*$/gm, '')), 'the readjudicator reads no ctl3 field but parity');
    assert.match(CLOCKSRC, /const ctlBad = \(o\) => !\(o\.verdict\.checkStandard && o\.verdict\.checkStandard\.ok\);/);
  });

  t('criterion 1: and behaviourally — flipping the three-point record changes no verdict', () => {
    const CANON = { canonical: true, notCanonicalWhy: null, design: { rounds: 6, tare: true } };
    const ADJ = { unadjudicated: false, why: 'clears' };
    const base = {
      rowId: 'bulk300', pageErrors: [], guardRefuse: false, guardRefuseTask: false, parityOk: true,
      ctl3Parity: null, kbWitness: null, tally: { writes: 10, unverified: 0 }, etVerdict: null,
      roundsTask: fixtureRoundsTask(), seam: { verdict: { ceilingBreached: false } },
      seamTask: { ceilingBreached: false, band: 0.2, rows: { 'fresco / reagent-subs': ADJ } },
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

  t('criterion 3: the interval is run-preserving — outer RUNS resampled before inner ROUNDS', () => {
    // All the variance BETWEEN runs: a bootstrap that pooled the 30 rounds
    // would return an interval roughly sqrt(30/5) times too narrow.
    const runs = [0.9, 0.95, 1.0, 1.05, 1.1].map((c) => Array(6).fill(Math.log(c)));
    const iv = effectInterval(runs);
    assert.strictEqual(iv.runs, 5);
    assert.ok(iv.hi - iv.lo > 0.05, `a between-run spread of 20% must reach the interval, got width ${iv.hi - iv.lo}`);
    // rounds that differ inside identical runs still widen it
    const inner = Array.from({ length: 5 }, () => [0.9, 0.95, 1.0, 1.05, 1.1, 1.0].map(Math.log));
    assert.ok(effectInterval(inner).hi - effectInterval(inner).lo > 0, 'inner resampling must contribute');
    // and identical runs of identical rounds collapse to a point
    const fv = effectInterval(Array.from({ length: 5 }, () => Array(6).fill(Math.log(1.2))));
    assert.ok(Math.abs(fv.hi - fv.lo) < 1e-9 && Math.abs(fv.point - 1.2) < 1e-9, 'no variance, no interval width');
  });

  t('criterion 3: the interval is REPRODUCIBLE — a seeded bootstrap, stated in the output', () => {
    const runs = [0.9, 0.95, 1.0, 1.05, 1.1].map((c) => Array(6).fill(Math.log(c)));
    assert.deepStrictEqual(effectInterval(runs), effectInterval(runs), 'two runs of the same program agree to the last place');
  });

  t('criterion 3: the WHOLE interval must clear the threshold', () => {
    // Times, so lower is faster. Bulk's bar is 1.0 and its kill line 1.5, on
    // the pair validation.md's bulk bar names.
    const at = (point, lo, hi, runs = 8) => ({ runs, rounds: [6], point, lo, hi, draws: 1, seed: 1 });
    const ev = (iv) => effectVerdict(iv, BULK, 'fresco / reagent-subs', { widestSameRunBandPct: 10 });
    const below = at(0.6, 0.55, 0.65);
    const kill = at(1.9, 1.7, 2.1);
    assert.match(ev(below).verdict, /MAGNITUDE PUBLISHABLE/);
    assert.match(ev(kill).verdict, /ARCHITECTURE-KILL/);
    // [wholly below, wholly above the kill, straddling 1.0, straddling 1.5, two runs, no interval]
    assert.deepStrictEqual(
      [below, kill, at(0.99, 0.9, 1.05), at(1.5, 1.4, 1.6), at(0.6, 0.55, 0.65, 2), null].map((iv) => ev(iv).publishes),
      [true, true, false, false, false, false]
    );
  });
}

// --- THE MOUNT CLASS: the published M1 row is reproducible -------------------
//
// The mount class is calibrated on the mount's own 14 committed row-runs, so
// `clock_readjudicate.cjs` pools M1 and adjudicates its gated pair against
// K1's 1.10x mount gate. Only that pair — fresco against direct UIx-on-subs,
// per validation.md — publishes, and it publishes a MISS.
{
  const { pairedLogRatios, effectInterval, effectVerdict, reportable } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-x7x10: ${what}`, fn);
  const M1_PAIRS = ['fresco / reagent-subs', 'fresco / uix-subs', 'uix-subs / reagent-subs'];

  t('and the publication rule reaches M1 for the first time, with a stated verdict on every pair', () => {
    for (const dir of ['clock-emvod', 'clock-w3yxd']) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return; // datasets are retained, not required to build
      const pooled = fs
        .readdirSync(d)
        .map((f) => archive.readRecord(path.join(d, f)))
        .map((data) => ({ data, row: data.rows.find((r) => r.rowId === 'M1') }))
        .filter(({ row: r, data }) => r && reportable(r, data));
      for (const pair of M1_PAIRS) {
        const iv = effectInterval(pooled.map(({ row: r, data }) => pairedLogRatios(r, pair, data)).filter(Boolean));
        assert.strictEqual(effectVerdict(iv, 'M1', pair).publishes, pair === 'fresco / uix-subs', `${dir}/M1/${pair}`);
      }
    }
  });
}

// --- THE LIMITS, VALIDATED OUT OF SAMPLE -------------------------------------
//
// Both clock ensembles were taken on one day, so the hold-out is by SITTING:
// limits fitted on one ensemble, applied to the other, both ways. Every
// hold-out number the standard records is re-derived here from the committed
// datasets through the live adjudicator, and the shipped limits are pinned so
// a failed hold-out is answered by measurement, never by widening.
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

  /** Every committed row-run of one class, per ensemble, as the live adjudicator measures it; `null` when the corpus is absent. */
  const readClass = (klassName) => {
    const rows = STANDARD.classes[klassName].rows;
    const out = {};
    for (const dir of ENSEMBLES) {
      const d = path.join(__dirname, 'data', dir);
      if (!fs.existsSync(d)) return null;
      out[dir] = [];
      for (const f of fs.readdirSync(d).sort()) {
        const data = archive.readRecord(path.join(d, f));
        for (const r of data.rows.filter((x) => rows.includes(x.rowId))) {
          const v = checkStandardFor(r, data);
          out[dir].push({ run: `${dir}/${f.replace(/\.json$/, '')}`, rowId: r.rowId, median: v.location.measured, scale: v.dispersion.measured });
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
        const at = `${name}/${dir.baseline}`;
        assert.deepStrictEqual(
          [d.n, held.length, r4(d.centre), r4(d.sd)],
          [dir.baselineRowRuns, dir.heldOutRowRuns, dir.centre, dir.betweenRunSD],
          `${at}: baseline size, held-out size, centre, between-run SD`
        );
        // Within half a place rather than to the last one, because the
        // adjudicator reports a run's median and scale rounded to four places.
        assert.ok(Math.abs(d.limits[0] - dir.limits[0]) < 1e-3 && Math.abs(d.limits[1] - dir.limits[1]) < 1e-3,
          `${at}: derived limits [${r4(d.limits[0])}, ${r4(d.limits[1])}] against recorded ${JSON.stringify(dir.limits)}`);
        assert.ok(Math.abs(d.dispersionLimit - dir.dispersionLimit) < 1e-3,
          `${at}: derived dispersion ${r4(d.dispersionLimit)} against recorded ${dir.dispersionLimit}`);
        // every held-out run judged by limits fitted without it, against the limits the file records
        const verdicts = held.map((r) => ({ ...r, ok: r.median >= dir.limits[0] && r.median <= dir.limits[1] && r.scale <= dir.dispersionLimit }));
        assert.strictEqual(verdicts.filter((v) => v.ok).length, dir.inControl, `${at} -> ${dir.heldOut}: in-control count`);
        assert.deepStrictEqual(
          verdicts.filter((v) => !v.ok).map((v) => v.run).sort(),
          (dir.refused || []).map((r) => r.run).sort(),
          `${at} -> ${dir.heldOut}: the refused runs must be the ones the file names`
        );
        for (const r of dir.refused || []) {
          const v = verdicts.find((x) => x.run === r.run);
          assert.deepStrictEqual([v.median, v.scale <= dir.dispersionLimit ? 'location' : 'dispersion'], [r.median, r.term], `${r.run}: recorded median and term`);
        }
      }
    }
  });

  t('THE FENCE: not one shipped limit moved — v2\'s frozen numbers, to the last place', () => {
    const frozen = (k) => [k.centre, k.location.limits, k.dispersion.limit];
    assert.deepStrictEqual(frozen(mount), [1.7956, [1.6765, 1.9147], 0.577]);
    assert.deepStrictEqual(frozen(bulk), [1.7207, [1.5509, 1.8905], 0.568]);
    // and the shipped limits still admit every row-run they were fitted on
    for (const [name, klass] of [['bulk', bulk], ['mount', mount]]) {
      const data = readClass(name);
      if (!data) return;
      const all = ENSEMBLES.flatMap((d) => data[d]);
      const inControl = all.filter((r) => r.median >= klass.location.limits[0] && r.median <= klass.location.limits[1] && r.scale <= klass.dispersion.limit);
      assert.strictEqual(inControl.length, klass.provenance.rowRuns, `${name}: the shipped limits must still admit all ${klass.provenance.rowRuns}`);
    }
  });
}

// --- A POINT AND AN INTERVAL MUST DESCRIBE THE SAME ESTIMAND ----------------
//
// validation.md defines canonical `M1` as FLOOR-NORMALISED on the clock of
// record. The tool must compute that estimand, adjudicate it against the
// mount's own 1.10x gate, and print the point and both bounds from that one
// estimator — splicing a floor-normalised point onto an unfloored interval
// makes two correct figures one incorrect claim.
{
  const { pairedLogRatios, effectVerdict } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-diaud: ${what}`, fn);
  const GATED = 'fresco / uix-subs';
  const M1_PAIRS = ['fresco / reagent-subs', GATED, 'uix-subs / reagent-subs'];

  /** The expected outcome, to 4 places — the tool's figures, as the studio pages publish them. */
  const EXPECTED = {
    'clock-emvod': { runs: 8, point: '1.1718', lo: '1.1263', hi: '1.2190' },
    'clock-w3yxd': { runs: 6, point: '1.1976', lo: '1.1504', hi: '1.2468' },
  };

  const corpus = (dir) => {
    const d = path.join(__dirname, 'data', dir);
    if (!fs.existsSync(d)) return null;
    return fs.readdirSync(d).map((f) => ({ file: path.join(d, f), data: archive.readRecord(path.join(d, f)) }));
  };

  t("criterion (a): the mount estimand IS validation.md's canonical M1, and the driver's own bar proves it", () => {
    // Computed from the raw `roundsTask`; the driver stored the same quantity
    // at capture time in `barTask[pair].perRound`.
    let checked = 0;
    for (const dir of Object.keys(EXPECTED)) {
      const c = corpus(dir);
      if (!c) return;
      for (const { data } of c) {
        const m1 = data.rows.find((r) => r.rowId === 'M1');
        for (const pair of M1_PAIRS) {
          const mine = pairedLogRatios(m1, pair, data).map((x) => Math.exp(x).toFixed(4));
          const driver = m1.barTask[pair].perRound.map((x) => x.toFixed(4));
          assert.deepStrictEqual(mine, driver, `${dir}/M1/${pair}: the estimand and the driver's own bar must agree`);
          checked += 1;
        }
      }
    }
    assert.strictEqual(checked, 42, 'three pairs over fourteen committed mount row-runs');
  });

  t('criterion (a): the estimand refuses a record that did not serialise its tare', () => {
    const c = corpus('clock-emvod');
    if (!c) return;
    const { data } = c[0];
    const m1 = data.rows.find((r) => r.rowId === 'M1');
    assert.ok(pairedLogRatios(m1, GATED, data), 'the design record is present and the estimand forms');
    assert.strictEqual(pairedLogRatios(m1, GATED, { ...data, design: undefined }), null, 'no design record, no estimand');
    assert.strictEqual(
      pairedLogRatios(m1, GATED, { ...data, design: { ...data.design, tare: 'yes' } }),
      null,
      'a tare that is not a boolean has not been serialised'
    );
  });

  t("criterion (b): the mount rule's boundary, driven in both directions at 1.10x", () => {
    const v = (lo, hi) => effectVerdict({ runs: 8, rounds: [6], point: (lo + hi) / 2, lo, hi, draws: 1, seed: 1 }, 'M1', GATED);
    // ships when the WHOLE interval is at or below the gate; trips when it is strictly above
    assert.deepStrictEqual(
      [[1.0, 1.1], [1.0, 1.1001], [1.1001, 1.3], [1.1, 1.3]].map(([lo, hi]) => v(lo, hi).publishes),
      [true, false, true, false]
    );
    assert.match(v(1.0, 1.1).verdict, /MOUNT SHIP BAR MET/);
    assert.match(v(1.1001, 1.3).verdict, /K1 MISSED, DECISIVELY/);
  });

  t('criterion (e): the figures come out of clock_readjudicate.cjs itself, on both ensembles', () => {
    // The point and BOTH bounds are read off one printed line, which is the
    // only way a splice cannot survive.
    const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
    for (const [dir, want] of Object.entries(EXPECTED)) {
      const c = corpus(dir);
      if (!c) return;
      const r = cp.spawnSync(process.execPath, [RJ, ...c.map((x) => x.file)], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const out = `${r.stdout}${r.stderr}`;
      assert.strictEqual(r.status, 0, `${dir}: ${out.slice(-2000)}`);
      const m1 = out.slice(out.indexOf(';; ======== ROW M1'), out.indexOf(';; ======== ROW bulk300'));
      const block = m1.slice(m1.indexOf(`;;   PAIR ${GATED}`), m1.indexOf(';;   PAIR uix-subs / reagent-subs'));
      const line = block.split('\n').find((l) => /^;;\s+point /.test(l));
      assert.ok(
        line && new RegExp(`point ${want.point}x\\s+95% CI \\[${want.lo} – ${want.hi}\\]\\s+over ${want.runs} reportable run\\(s\\)`).test(line),
        `${dir}: expected ${want.point}x [${want.lo} – ${want.hi}] n=${want.runs}, got: ${line && line.trim()}`
      );
      assert.ok(/VERDICT K1 MISSED, DECISIVELY/.test(block), `${dir}: the gated pair trips K1`);
      assert.ok(/VERDICT CO-INSTRUMENTED/.test(m1.slice(m1.indexOf(';;   PAIR uix-subs / reagent-subs'))), `${dir}: donor-vs-donor gates nothing`);
    }
  });
}

// --- BULK GATES THE PAIR ITS BAR NAMES ---------------------------------------
//
// validation.md:17 states the bulk ship bar as one comparison — fresco against
// Reagent-on-subs — so the other two pairs of a bulk row are reported and not
// adjudicated. The cross-run max-band veto is retired on every class; it is
// safe on bulk because the gated pair straddles 1.0 on all six row-ensemble
// combinations and is refused by the whole-interval rule, ahead of the veto.
// That is a fact about this corpus, driven here in both directions.
{
  const {
    pairedLogRatios, effectInterval, effectVerdict, reportable, PUBLICATION,
  } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-vp0j7/rf2-vh0e3: ${what}`, fn);
  const GATED = 'fresco / reagent-subs';
  const UNGATED = ['fresco / uix-subs', 'uix-subs / reagent-subs'];
  const BULK_ROWS = ['bulk300', 'bulk100', 'narrow'];
  const CORPORA = ['clock-emvod', 'clock-w3yxd'];

  const corpus = (dir) => {
    const d = path.join(__dirname, 'data', dir);
    if (!fs.existsSync(d)) return null;
    return fs.readdirSync(d).map((f) => ({ file: path.join(d, f), data: archive.readRecord(path.join(d, f)) }));
  };

  /** One bulk row of one ensemble, as the publication path sees it: interval + widest band. */
  const bulkPooled = (dir, rowId, pair) => {
    const c = corpus(dir);
    if (!c) return null;
    const pooled = c
      .map(({ data }) => ({ data, row: data.rows.find((r) => r.rowId === rowId) }))
      .filter((x) => x.row && reportable(x.row, x.data));
    const bands = pooled.map(({ row: r }) => r.seamTask && r.seamTask.band).filter(Number.isFinite).map((b) => b * 100);
    return {
      iv: effectInterval(pooled.map(({ row: r, data }) => pairedLogRatios(r, pair, data)).filter(Boolean)),
      band: bands.length ? Math.max(...bands) : NaN,
    };
  };

  t('a non-gated bulk pair keeps its interval and loses only the VERDICT', () => {
    let seen = 0;
    for (const dir of CORPORA) {
      for (const rowId of BULK_ROWS) {
        for (const pair of UNGATED) {
          const p = bulkPooled(dir, rowId, pair);
          if (!p) return;
          assert.ok(p.iv, `${dir}/${rowId}/${pair}: the interval must still be formed`);
          const ev = effectVerdict(p.iv, rowId, pair, { widestSameRunBandPct: p.band });
          assert.deepStrictEqual([/^CO-INSTRUMENTED/.test(ev.verdict), ev.publishes], [true, false], `${dir}/${rowId}/${pair}: ${ev.verdict}`);
          seen += 1;
        }
      }
    }
    assert.strictEqual(seen, 12, 'two ungated pairs of three bulk rows over two ensembles');
  });

  t("and it says so in the tool's own output, beside its own interval", () => {
    const RJ = path.join(__dirname, 'clock_readjudicate.cjs');
    for (const dir of CORPORA) {
      const c = corpus(dir);
      if (!c) return;
      const r = cp.spawnSync(process.execPath, [RJ, ...c.map((x) => x.file)], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const out = `${r.stdout}${r.stderr}`;
      assert.strictEqual(r.status, 0, `${dir}: ${out.slice(-2000)}`);
      for (const rowId of BULK_ROWS) {
        const at = out.indexOf(`;; ======== ROW ${rowId} `);
        const next = out.indexOf(';; ======== ROW ', at + 1);
        const rowBlock = out.slice(at, next < 0 ? undefined : next);
        for (const pair of UNGATED) {
          const from = rowBlock.indexOf(`;;   PAIR ${pair}`);
          assert.ok(from >= 0, `${dir}/${rowId}: the ${pair} block must still be printed`);
          const ends = [`;;   PAIR `, `;; ======== `].map((m) => rowBlock.indexOf(m, from + 1)).filter((i) => i > 0);
          const block = rowBlock.slice(from, ends.length ? Math.min(...ends) : undefined);
          assert.ok(/point .*95% CI \[/.test(block), `${dir}/${rowId}/${pair}: the interval must still print`);
          assert.ok(/VERDICT CO-INSTRUMENTED/.test(block), `${dir}/${rowId}/${pair}: and carry no ship/kill verdict`);
        }
      }
    }
  });

  t('the gated bulk pair still adjudicates, and is still INSTRUMENT-LIMITED on this corpus', () => {
    // Refused by the INTERVAL straddling the bar, not by the band.
    let seen = 0;
    for (const dir of CORPORA) {
      for (const rowId of BULK_ROWS) {
        const p = bulkPooled(dir, rowId, GATED);
        if (!p) return;
        const ev = effectVerdict(p.iv, rowId, GATED, { widestSameRunBandPct: p.band });
        assert.deepStrictEqual(
          [ev.publishes, /^INSTRUMENT-LIMITED/.test(ev.verdict), /does not lie wholly on one side of the 1 bar/.test(ev.why)],
          [false, true, true],
          `${dir}/${rowId}: ${ev.verdict} — ${ev.why}`
        );
        seen += 1;
      }
    }
    assert.strictEqual(seen, 6, 'three bulk rows over two ensembles');
  });

  t('MUTATION: the band veto is retired on bulk, and the gated pair does not move either way', () => {
    for (const dir of CORPORA) {
      for (const rowId of BULK_ROWS) {
        const p = bulkPooled(dir, rowId, GATED);
        if (!p) return;
        const shipped = effectVerdict(p.iv, rowId, GATED, { widestSameRunBandPct: p.band });
        const before = PUBLICATION.bulk.crossRunBandVeto;
        let mutated;
        try {
          PUBLICATION.bulk.crossRunBandVeto = true;
          mutated = effectVerdict(p.iv, rowId, GATED, { widestSameRunBandPct: p.band });
        } finally {
          PUBLICATION.bulk.crossRunBandVeto = before;
        }
        assert.deepStrictEqual(mutated, shipped, `${dir}/${rowId}: the veto must not reach the gated pair in either position`);
      }
    }
  });

  t('MUTATION: and the retired veto still REFUSES when it is switched back on', () => {
    // The corpus cannot show this direction, so a synthetic interval: the
    // refusal branch stays live so the retirement is one line to overturn.
    const iv = { runs: 8, rounds: [6], point: 0.9, lo: 0.85, hi: 0.95, draws: 1, seed: 1 };
    const ev = () => effectVerdict(iv, 'bulk300', GATED, { widestSameRunBandPct: 40 });
    assert.strictEqual(ev().publishes, true, 'retired: a 10% effect inside a 40% band publishes on the interval alone');
    const before = PUBLICATION.bulk.crossRunBandVeto;
    let mutated;
    try {
      PUBLICATION.bulk.crossRunBandVeto = true;
      mutated = ev();
    } finally {
      PUBLICATION.bulk.crossRunBandVeto = before;
    }
    assert.strictEqual(mutated.publishes, false, 'reinstated: the same interval is refused, or the mutation proves nothing');
    assert.match(mutated.why, /the widest same-run noise band among the pooled runs/);
  });
}

// ============================================================================
// EVIDENCE COMPLETENESS AT THE CONSUMER BOUNDARY
// ============================================================================
//
// Eligibility is decided on the serialised verdicts while the raw evidence
// behind them can be missing. A record whose declared design is itself short
// must still refuse, and the interval consumer must refuse an unusable member
// rather than silently pooling fewer runs than cleared the gates.
{
  const { checkStandardFor, pairedLogRatios, effectInterval, reportable } = require('./clock_readjudicate.cjs');
  const t = (what, fn) => test(`rf2-8a746 audits #7698/#7700: ${what}`, fn);
  const RJ = path.join(__dirname, 'clock_readjudicate.cjs');

  const corpus = (dir) => {
    const d = path.join(__dirname, 'data', dir);
    if (!fs.existsSync(d)) return null;
    return fs.readdirSync(d).sort().map((f) => ({
      name: f,
      data: archive.readRecord(path.join(d, f)),
    }));
  };

  t('a record whose OWN declared design is short cannot smuggle a smaller count past the boundary', () => {
    const c = corpus('clock-emvod');
    if (!c) return;
    const { data } = c[0];
    const bulk300 = data.rows.find((r) => r.rowId === 'bulk300');
    // one round declared, one round present: internally consistent, still three blocks of eighteen
    const v = checkStandardFor({ ...bulk300, roundsTask: bulk300.roundsTask.slice(0, 1) }, { ...data, design: { ...data.design, rounds: 1 } });
    assert.strictEqual(v.ok, false);
    assert.match(v.why, /3 block\(s\) observed/);
    // and a design that declares NO round count is absent, not clean
    const noRounds = checkStandardFor(bulk300, { ...data, design: { tare: data.design.tare } });
    assert.strictEqual(noRounds.ok, false);
    assert.match(noRounds.why, /declares no round count/);
  });

  t('effectInterval REJECTS an invalid member rather than filtering it', () => {
    const good = Array(6).fill(Math.log(1.2));
    assert.throws(() => effectInterval([good, null]), /member\(s\) 1 of 2 carry no usable paired log-ratios/, 'a null member is refused, not dropped');
    assert.throws(() => effectInterval([good, [Math.log(1.2), NaN]]), /completeness is the caller's to prove before pooling/, 'and a member with a non-finite entry');
    assert.strictEqual(effectInterval([good, good, good]).runs, 3, 'a complete pool still forms its interval');
    assert.strictEqual(effectInterval([]), null, 'an EMPTY pool is no interval, not a refusal — nothing was pooled and nothing was lost');
  });

  t('WITNESS (mutation, committed corpus): blanking one raw reading is caught, named, and exits 4', () => {
    // Blank ONLY run1's `roundsTask[0].fresco.fresco` on M1: every serialised
    // gate verdict is untouched, so eligibility is unchanged — exactly what
    // would make a filtering consumer silent.
    const c = corpus('clock-emvod');
    if (!c) return;
    const mutated = JSON.parse(JSON.stringify(c[0].data));
    const m1 = mutated.rows.find((r) => r.rowId === 'M1');
    m1.roundsTask[0].fresco.fresco = [];
    assert.strictEqual(reportable(m1, mutated), true, 'the run still clears every gate — evidence loss is NOT an eligibility question');
    assert.strictEqual(pairedLogRatios(m1, 'fresco / uix-subs', mutated), null, 'and the estimand refuses to form');
    assert.ok(Array.isArray(pairedLogRatios(m1, 'uix-subs / reagent-subs', mutated)), 'the unblanked pair still forms');

    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hic-8a746-'));
    try {
      const files = c.map(({ name, data }, i) => {
        const p = path.join(tmp, name);
        fs.writeFileSync(p, JSON.stringify(i === 0 ? mutated : data));
        return p;
      });
      const r = cp.spawnSync(process.execPath, [RJ, ...files], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const out = `${r.stdout}${r.stderr}`;
      const tail = out.slice(-2000);
      assert.strictEqual(r.status, 4, `the program must exit 4 on raw evidence lost after eligibility — got ${r.status}: ${tail}`);
      assert.match(out, /run1\.json: row M1, pair `fresco \/ uix-subs`/, `the exit roster names the file and the pair — ${tail}`);
      assert.match(out, /run1\.json: row M1, pair `fresco \/ reagent-subs`/, `both fresco pairs of the blanked segment — ${tail}`);
      assert.ok(!/run1\.json: row M1, pair `uix-subs \/ reagent-subs`/.test(out), 'the intact pair is not named as lost');
      assert.match(out, /EXIT 4 — 2 reportable run\/pair\(s\) LOST RAW EVIDENCE/, 'and the exit block counts the losses');
      // No M1 interval may claim 7 runs where 8 cleared the gates; the intact
      // pair keeps its full pool. (`narrow` pools 7 of 8 legitimately — one
      // run refused BY A GATE, with its reason printed.)
      const m1Block = out.slice(out.indexOf(';; ======== ROW M1'), out.indexOf(';; ======== ROW bulk300'));
      assert.ok(!/over 7 reportable run\(s\)/.test(m1Block), 'no silent subset');
      assert.match(m1Block, /INCOMPLETE — 1 of 8 reportable run\(s\) yield no raw quotient/, 'the diagnostic states its loss instead of shrinking');
      assert.match(m1Block, /over 8 reportable run\(s\)/, 'the intact M1 pair keeps its full pool');
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
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
if (corpusSkipped > 0) archive.skipped(`clock_exit_path.test.cjs: ${corpusSkipped} corpus-backed tests`);
console.log(`clock_exit_path.test.cjs: ${tests.length - corpusSkipped} passed`);
