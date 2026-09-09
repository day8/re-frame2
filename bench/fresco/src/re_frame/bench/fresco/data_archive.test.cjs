'use strict';
// THE ARCHIVE BOUNDARY, HELD TO ITS TWO PROMISES (rf2-d1nr.2).
//
// Run it:
//     node src/re_frame/bench/fresco/data_archive.test.cjs
//
// The corpus lives outside the working tree, at a commit older than the rename
// that gave this tree its name (rf2-d1nr.2), and BOTH things it fixes about
// itself — the path it sits at, and the vocabulary its records are written in —
// are beyond the reach of any commit on main. PR #9568 renamed the pointer at
// both and left the target where it was, which is how two failures shipped green:
//
//   1. The advertised restore command named a path that does not exist in the
//      archived commit and exited 1. Nothing ran it, so nothing said so.
//   2. Readers addressing the native arm by its current spelling matched
//      nothing in an archived record — not a crash, a smaller population and a
//      different median.
//
// So there are two checks here and they are deliberately of different kinds.
// The FIRST validates the COMMAND and must run even with the corpus absent,
// because that is the state every clone is in — it is the check that would
// have caught (1) in a lane with no CI. The SECOND validates the READ and is
// corpus-backed, so it skips with the lane's one printed line when `data/` is
// not there. Its expectations are the population the reader saw BEFORE the
// rename, so a repair that quietly recalculates over half the cells fails here
// rather than publishing a plausible number.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const archive = require('./data_archive.cjs');

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
    entries.some((e) => e.rel.replace(/\\/g, '/') === 'alloc-0gjqi/paired-run1.json'),
    'and the record `present()` tests on must be one of them'
  );
});

test('the restore is advertised in ONE spelling, and every place that quotes it agrees', () => {
  // Three surfaces used to carry three copies of a `git restore` line; they
  // drifted the moment the directory moved. They now quote `RESTORE`, and this
  // is what holds them to it.
  const self = fs.readFileSync(path.join(__dirname, 'data_archive.cjs'), 'utf8');
  const readme = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..', 'README.md'), 'utf8');
  for (const [what, text] of [['data_archive.cjs', self], ['bench/fresco/README.md', readme]]) {
    assert.ok(text.includes(archive.RESTORE), `${what} must quote the restore command verbatim`);
  }
  // And the skip line readers actually see carries it too.
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
  // The regression in one line: the archived tree has no such path, so the
  // command PR #9568 left behind exits 1. Asserted rather than remembered,
  // because if it ever starts resolving, the restore can go back to being one
  // git command and this whole file can shrink.
  const cp = require('node:child_process');
  const root = cp.execFileSync('git', ['-C', __dirname, 'rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
  const r = cp.spawnSync(
    'git',
    ['-C', root, 'ls-tree', '-r', '--name-only', archive.ARCHIVE_SHA, '--', 'bench/fresco/src/re_frame/bench/fresco/data'],
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
  // The audit's own numbers over `alloc-0gjqi/paired-run1.json` at rungs
  // R0/R3/R7. The landed reader returned 27 of these 55 cells and dropped the
  // native arm entirely, leaving Reagent and UIx — the two positive controls
  // below — untouched. That is the shape of the defect: an answer, quieter.
  const pass = require('./alloc_pass_position.cjs');
  const row = archive.readRecord(path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json')).alloc;
  const cells = row.perRound.flatMap((r) => pass.roundCells(r, row.boundaries, ['R0', 'R3', 'R7']));
  const arms = {};
  for (const c of cells) arms[c.arm] = (arms[c.arm] || 0) + 1;
  assert.strictEqual(cells.length, 55, 'the published population');
  assert.deepStrictEqual(
    arms,
    { 'lad/fresco': 28, 'lad/reagent': 14, 'lad/uix': 13 },
    'the native arm is 28 of it; Reagent and UIx are the controls that never moved'
  );
  // And the block statistic the studio pages publish is the one that population
  // produces — a repair that recalculates over a subset fails HERE, not later.
  assert.deepStrictEqual(
    pass.blocks(row, 'archive').map((b) => b.n),
    [3, 8, 7, 8, 4, 7],
    'per-round n, which halved when the arm went missing'
  );
});

corpusTest('and the REPORT ITSELF reads it — the boundary is on the path the CLI takes', () => {
  // THE CHECK ABOVE WAS HOLLOW AND THIS IS WHY THIS ONE EXISTS (the merged-PR
  // audit of #9571). It proves `readRecord` by CALLING `readRecord`, so it went
  // green over a `main` that never touched the boundary: the published report
  // parsed its dataset arguments raw and printed n=[1,4,4,4,2,4], PASS TERM
  // +0.89% and null arm n=8 over 27 of the 55 cells, exit 0, no remark. A test
  // that exercises a path the CLI does not take says nothing about the CLI. So
  // this one SPAWNS the CLI exactly as `bench/fresco/README.md` documents it and
  // reads the population out of what it printed — a future raw read anywhere on
  // that path goes red HERE, whatever shape it is written in.
  const cp = require('node:child_process');
  const record = path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json');
  const r = cp.spawnSync(
    process.execPath,
    [path.join(__dirname, 'alloc_pass_position.cjs'), record],
    { encoding: 'utf8' }
  );
  assert.strictEqual(r.status, 0, `the report must run over the archived record: ${r.stderr}`);

  // Its tables, read as tables: the `;;   a | b | ...` rows under a heading,
  // less the column line that leads them. Scanned forward to the first such row
  // rather than taken at a fixed offset, because a heading is prose and runs to
  // as many lines as it needs. Parsed rather than matched verbatim, so that a
  // reworded heading or a re-rounded median cannot fail a POPULATION check.
  const printed = r.stdout.split(/\r?\n/);
  const isRow = (l) => /^;; {3}.+ \| /.test(l);
  const table = (heading) => {
    const at = printed.findIndex((l) => l.startsWith(heading));
    assert.ok(at >= 0, `the report must print "${heading}" — got:\n${r.stdout}`);
    let from = at + 1;
    while (from < printed.length && !isRow(printed[from])) from += 1;
    const rows = [];
    for (const l of printed.slice(from)) {
      if (!isRow(l)) break;
      rows.push(l.slice(5).split(' | '));
    }
    assert.ok(rows.length > 1, `"${heading}" printed no rows under its column line`);
    return rows.slice(1);
  };

  assert.deepStrictEqual(
    table(';; THE ROUND BLOCKS').map((c) => Number(c[4])),
    [3, 8, 7, 8, 4, 7],
    'the per-round n the studio pages publish; [1,4,4,4,2,4] is the raw-read population'
  );
  assert.strictEqual(
    Number(table(';; THE NULL ARM')[0][1]),
    18,
    'the null arm licenses reading the rest, and a raw read halves it to 8'
  );
  assert.ok(r.stdout.includes('PASS TERM +0.68%'), 'the published term');
  assert.ok(!r.stdout.includes('PASS TERM +0.89%'), 'and not the one the smaller population produces');

  // AND THE CONTROL IN THE OTHER DIRECTION, without which this passes over
  // bytes that never needed a boundary: the same record parsed RAW must still
  // read the SMALLER population. That is what makes the figures above evidence
  // that the translation ran, rather than evidence that the corpus was rewritten.
  const pass = require('./alloc_pass_position.cjs');
  assert.deepStrictEqual(
    pass.blocks(JSON.parse(fs.readFileSync(record, 'utf8')).alloc, 'raw').map((b) => b.n),
    [1, 4, 4, 4, 2, 4],
    'a raw parse of the archived record must still lose the native arm'
  );
});

corpusTest('the archived corpus really is in the OLD vocabulary — so the translation is doing work', () => {
  // The control for the check above: without this, a corpus that had somehow
  // been rewritten in the current vocabulary would pass it while proving
  // nothing about the boundary. It has to spell the archived arm key as the
  // archived bytes spell it, so the assertion line is carried by
  // `PRODUCT_EXEMPTIONS` in `scripts/check_retired_spellings.py`.
  const raw = fs.readFileSync(path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json'), 'utf8');
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
