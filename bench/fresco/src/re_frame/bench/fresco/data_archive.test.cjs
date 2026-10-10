'use strict';
// THE ARCHIVE BOUNDARY, HELD TO ITS TWO PROMISES.
//
// Run it:
//     node src/re_frame/bench/fresco/data_archive.test.cjs
//
// The corpus lives outside the working tree, at a commit older than the rename
// that gave this tree its name, and BOTH things it fixes about itself — the
// path it sits at, and the vocabulary its records are written in — are beyond
// the reach of any commit on main. Renaming the pointer at both while the
// target stays where it is ships two failures green:
//
//   1. The advertised restore command names a path that does not exist in the
//      archived commit and exits 1. Nothing runs it, so nothing says so.
//   2. Readers addressing the native arm by its current spelling match
//      nothing in an archived record — not a crash, a smaller population and a
//      different median.
//
// So there are two checks here and they are deliberately of different kinds.
// The FIRST validates the COMMAND and must run even with the corpus absent,
// because that is the state every clone is in — it is the check that catches
// (1) in a lane with no CI. The SECOND validates the READ and is
// corpus-backed, so it skips with the lane's one printed line when `data/` is
// not there. Its expectations are the population the reader saw BEFORE the
// rename, so a repair that quietly recalculates over half the cells fails here
// rather than publishing a plausible number.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const archive = require('./data_archive.cjs');
const pass = require('./alloc_pass_position.cjs');

const RECORD = path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
let corpusSkipped = 0;
const corpusTest = (name, fn) => test(name, () => {
  if (archive.present()) fn();
  else corpusSkipped += 1;
});

// --- 1. THE ADVERTISED RECOVERY, WITH OR WITHOUT A CORPUS --------------------

test('the archived corpus RESOLVES at the pinned commit, at the path it was archived under', () => {
  const entries = archive.archiveEntries();
  assert.strictEqual(
    entries.length,
    archive.ARCHIVE_ENTRIES,
    `${archive.ARCHIVE_SHA}:${archive.ARCHIVE_PATH} must carry the whole corpus`
  );
  assert.ok(
    entries.some((e) => e.rel === 'alloc-0gjqi/paired-run1.json'),
    'and the record `present()` tests on must be one of them'
  );
});

test('the restore is advertised in ONE spelling, and every place that quotes it agrees', () => {
  const readme = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..', 'README.md'), 'utf8');
  assert.ok(readme.includes(archive.RESTORE), 'bench/fresco/README.md must quote the restore command verbatim');
  const printed = [];
  const log = console.log;
  console.log = (s) => printed.push(s);
  try {
    archive.skipped('a check');
  } finally {
    console.log = log;
  }
  assert.ok(printed.length === 1 && printed[0].includes(archive.RESTORE), 'the skip line names the restore');
});

test('a `git restore` of the CURRENT path cannot be the advertised operation', () => {
  // The archived tree has no such path, so a `git restore` of it exits 1. If it
  // ever resolves, the restore can go back to being one git command.
  const r = cp.spawnSync(
    'git',
    ['-C', __dirname, 'ls-tree', '-r', '--name-only', archive.ARCHIVE_SHA, '--', 'data'],
    { encoding: 'utf8' }
  );
  assert.strictEqual(r.stdout.trim(), '', 'the corpus is NOT at its current path inside the archived commit');
});

// --- 2. THE ARCHIVED VOCABULARY, READ AS THE PUBLICATION READ IT -------------

test('a record written TODAY passes through the boundary unchanged', () => {
  const now = '{"build":"fresco-bench","arm":"lad/fresco","seg":"fresco"}';
  assert.strictEqual(archive.currentVocabulary(now), now);
});

corpusTest('the archived alloc-0gjqi record reads its FULL published population', () => {
  // A raw reader returns 27 of these 55 cells and drops the native arm
  // entirely, leaving Reagent and UIx — the two positive controls — untouched.
  const row = archive.readRecord(RECORD).alloc;
  const arms = {};
  for (const c of row.perRound.flatMap((r) => pass.roundCells(r, row.boundaries, ['R0', 'R3', 'R7']))) {
    arms[c.arm] = (arms[c.arm] || 0) + 1;
  }
  assert.deepStrictEqual(arms, { 'lad/fresco': 28, 'lad/reagent': 14, 'lad/uix': 13 });
});

corpusTest('and the REPORT ITSELF reads it — the boundary is on the path the CLI takes', () => {
  // The check above proves `readRecord` by calling `readRecord`, so it stays
  // green over a report that never touches the boundary. This one SPAWNS the
  // CLI as `bench/fresco/README.md` documents it and reads the population out
  // of what it printed: a raw read prints n=[1,4,4,4,2,4], PASS TERM +0.89% and
  // null arm n=8. Tables are parsed rather than matched verbatim, so a reworded
  // heading or a re-rounded median cannot fail a POPULATION check.
  const r = cp.spawnSync(process.execPath, [path.join(__dirname, 'alloc_pass_position.cjs'), RECORD], { encoding: 'utf8' });
  assert.strictEqual(r.status, 0, `the report must run over the archived record: ${r.stderr}`);

  // The `;;   a | b | ...` rows under a heading, less the column line that leads them.
  const lines = r.stdout.split(/\r?\n/);
  const isRow = (l) => /^;; {3}.+ \| /.test(l || '');
  const table = (heading) => {
    let i = lines.findIndex((l) => l.startsWith(heading)) + 1;
    while (i < lines.length && !isRow(lines[i])) i += 1;
    const rows = [];
    for (i += 1; isRow(lines[i]); i += 1) rows.push(lines[i].slice(5).split(' | '));
    return rows;
  };

  assert.deepStrictEqual(
    {
      roundN: table(';; THE ROUND BLOCKS').map((c) => Number(c[4])),
      nullN: Number(table(';; THE NULL ARM')[0][1]),
    },
    { roundN: [3, 8, 7, 8, 4, 7], nullN: 18 },
    'the per-round n the studio pages publish, and the null arm that licenses reading the rest'
  );
  assert.ok(r.stdout.includes('PASS TERM +0.68%'), 'the published term');
});

corpusTest('the archived corpus really is in the OLD vocabulary — so the translation is doing work', () => {
  // The control for the checks above: a corpus rewritten in the current
  // vocabulary would pass them while proving nothing about the boundary. It has
  // to spell the archived arm key as the archived bytes spell it, so the
  // assertion line is carried by `PRODUCT_EXEMPTIONS` in
  // `scripts/check_retired_spellings.py`.
  const raw = fs.readFileSync(RECORD, 'utf8');
  assert.ok(raw.includes('lad/hicasso'), 'the archived record names the arm as it was named when the run was taken');
  assert.ok(!raw.includes('lad/fresco'), 'and does not name it as it is named now');
});

// --- runner -----------------------------------------------------------------

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}\n     ${e.message}`);
  }
}
if (corpusSkipped > 0) archive.skipped(`data_archive.test.cjs: ${corpusSkipped} corpus-backed checks`);
console.log(`;; ${tests.length - corpusSkipped - failed}/${tests.length - corpusSkipped} checks pass`);
process.exit(failed ? 1 : 0);
