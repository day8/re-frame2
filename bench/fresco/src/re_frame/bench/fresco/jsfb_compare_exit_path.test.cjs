#!/usr/bin/env node
'use strict';
// THE OUTSIDE-INSTRUMENT COMPARATOR'S EXIT PATH — absent evidence is not
// agreement. The same shape `clock_exit_path.test.cjs` pins for the bench
// DRIVERS: a program whose exit code is quoted as a quality gate must not take
// that exit from a reading that has nothing in it.
//
//     node src/re_frame/bench/fresco/jsfb_compare_exit_path.test.cjs   (from bench/fresco/)
//
// WHAT THIS PINS. A comparator that read the benchmark driver's results
// directory with `if (!fs.existsSync(dir)) return {}` and our run's JSON with
// `OURS && fs.existsSync(OURS) ? … : null`, then filtered the table down to
// the rows where both instruments produced a finite ratio, would keep nothing
// with neither file present, print
//
//     ;; VERDICT: 0 of 0 comparable rows agree within 15%
//
// and exit 0. "The comparator is green" and "the comparison never happened"
// would be the same observation — and the commonest reason a results directory
// is missing is a run that did not take place.
//
// WHY IT IS PINNED HERE RATHER THAN END TO END. The evidence the comparator
// reads is produced by a chromedriver run of an outside benchmark and a
// headless-Chromium run of ours, neither of which a unit test can take. So the
// decision is ONE pure function over the read evidence, which this file drives
// directly, plus spawned runs of the program for what only the process shows:
// its exit code and the sections `report` prints.
//
// EVERY FIXTURE IS BUILT IN THIS FILE, in a `mkdtemp` directory removed
// afterwards: the preserved run lives outside the repository by design.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const CMP = path.join(__dirname, 'jsfb_compare.cjs');
const { verdict, buildRows, readTheirs, readOurs, PAIRS, OTHERS, BASE } = require('./jsfb_compare.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- fixtures, built here and nowhere else -----------------------------------

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'jsfb-cmp-'));

// Arms differ so the ratios are finite and not all exactly 1.0.
const DEFAULT_MEDIAN = (arm) => (arm === BASE ? 100 : arm === OTHERS[0] ? 120 : 90);

/**
 * A results directory as the benchmark driver writes one: every benchmark it
 * runs, every arm. `skip` drops named `arm_benchmark` files; `median(arm, bench)`
 * supplies the duration.
 */
function theirsDir(name, { skip = [], median = DEFAULT_MEDIAN } = {}) {
  const dir = path.join(TMP, name);
  fs.mkdirSync(dir, { recursive: true });
  for (const p of PAIRS.filter((x) => x.theirs)) {
    for (const arm of [BASE, ...OTHERS]) {
      if (skip.includes(`${arm}_${p.bench}`)) continue;
      fs.writeFileSync(
        path.join(dir, `${arm}_${p.bench}.json`),
        JSON.stringify({ framework: `${arm}-v0.0.1.alpha-keyed`, benchmark: p.bench, type: 'cpu', values: { total: { median: median(arm, p.bench) } } })
      );
    }
  }
  return dir;
}

/** Our run's JSON, as `jsfb_ours_run.cjs` writes it; `edit` mutates it first. */
function oursJson(name, edit = () => {}) {
  const summary = {};
  for (const p of PAIRS) {
    summary[p.ours] = { base: 100, unverified: 0, arms: {} };
    for (const arm of OTHERS) summary[p.ours].arms[arm] = { ms: 120, ratio: arm === OTHERS[0] ? 1.2 : 0.95 };
  }
  const j = { summary };
  edit(j);
  const file = path.join(TMP, name);
  fs.writeFileSync(file, JSON.stringify(j));
  return file;
}

/** The evidence bundle the decision is taken over. */
function evidence(dir, oursFile) {
  const t = readTheirs(dir);
  const o = readOurs(oursFile);
  return { absent: [...t.absent, ...o.absent], rows: buildRows(t.table, o.ours) };
}

const run = (args) => cp.spawnSync(process.execPath, [CMP, ...args], { encoding: 'utf8' });

// --- the decision ------------------------------------------------------------

test('COMPLETE evidence exits 0 and says nothing', () => {
  const v = verdict(evidence(theirsDir('green'), oursJson('green.json')));
  assert.deepStrictEqual(v, { code: 0, lines: [] });
});

test('THE VACUOUS PASS: no results directory and no --ours cannot exit 0', () => {
  const v = verdict(evidence(path.join(TMP, 'nope'), undefined));
  assert.strictEqual(v.code, 2, 'a comparison handed nothing must not report agreement');
  assert.match(v.lines.join('\n'), /results directory does not exist/);
  assert.match(v.lines.join('\n'), /--ours <jsfb_ours_run\.cjs JSON> was not given/);
});

// A cell counts as measured only when the durations under it are finite and
// STRICTLY POSITIVE on both sides: a table of all-negative medians divides out
// to exactly the positive ratios a sound run produces. Each row names the side
// that lacks the cell and the value that is not a measurement; the zero rows
// are the boundary of `> 0`.
const THEIRS = "the benchmark driver's results carry no usable median for this cell";
const OURS = "our run's JSON carries no usable ratio for this cell";
const SWAP = [BASE, ...OTHERS].map((arm) => `${arm}_05_swap1k`);
const ARM0 = OTHERS[0];

test('SHORT or UNMEASURED evidence exits 1, naming every missing cell, its side and the value', () => {
  for (const [what, dir, ours, missing, named] of [
    ['a benchmark missing from theirs', theirsDir('short-theirs', { skip: SWAP }), oursJson('short-theirs.json'), 2,
      [`05_swap1k / rf2-fresco: ${THEIRS}`, `05_swap1k / rf2-uix: ${THEIRS}`]],
    ['an arm missing from ours',
      theirsDir('short-ours'), oursJson('short-ours.json', (j) => delete j.summary.replace1k.arms[ARM0]), 1,
      [`02_replace1k / rf2-fresco: ${OURS}`]],
    ['a cell missing from both',
      theirsDir('both-gone', { skip: [`${ARM0}_09_clear1k_x8`] }), oursJson('both-gone.json', (j) => delete j.summary.clear1k.arms[ARM0]), 1,
      ['09_clear1k_x8 / rf2-fresco: NEITHER instrument measured it']],
    ['an all-negative driver table', theirsDir('neg-all', { median: (arm) => -DEFAULT_MEDIAN(arm) }), oursJson('neg-all.json'), 10,
      [`01_run1k / ${ARM0}: ${THEIRS} (${BASE} median=-100)`]],
    ['a driver arm median of zero',
      theirsDir('arm-0', { median: (arm, bench) => (arm === ARM0 && bench === '01_run1k' ? 0 : DEFAULT_MEDIAN(arm)) }),
      oursJson('arm-0.json'), 1, [`01_run1k / ${ARM0}: ${THEIRS} (${ARM0} median=0)`]],
    ['an ours ratio of zero', theirsDir('ours-ratio-0'), oursJson('ours-ratio-0.json', (j) => { j.summary.run1k.arms[ARM0].ratio = 0; }), 1,
      [`01_run1k / ${ARM0}: ${OURS} (ratio=0)`]],
    ['an ours base of zero, behind a sound ratio', theirsDir('ours-base-0'), oursJson('ours-base-0.json', (j) => { j.summary.run1k.base = 0; }), 2,
      [`01_run1k / ${ARM0}: ${OURS} (base ms=0)`]],
    ['an ours arm duration of zero, behind a sound ratio', theirsDir('ours-ms-0'), oursJson('ours-ms-0.json', (j) => { j.summary.run1k.arms[ARM0].ms = 0; }), 1,
      [`01_run1k / ${ARM0}: ${OURS} (${ARM0} ms=0)`]],
  ]) {
    const v = verdict(evidence(dir, ours));
    const all = v.lines.join('\n');
    assert.strictEqual(v.code, 1, `${what}: evidence that arrived short is not evidence that never arrived`);
    assert.ok(all.includes(`${missing} of 10 expected cells were not measured by both instruments`), `${what}:\n${all}`);
    for (const n of named) assert.ok(all.includes(n), `${what}: must name ${n}\n${all}`);
  }
});

// --- the process -------------------------------------------------------------

test('THE PROCESS EXIT: no arguments at all is a refusal, not a green run', () => {
  const r = run([]);
  assert.strictEqual(r.status, 2);
  assert.match(r.stderr, /--theirs <results dir> was not given/);
  assert.doesNotMatch(r.stdout, /VERDICT: 0 of 0/, 'no table may be printed over evidence that is not there');
});

// THE WORKLOAD section states a second finding — how far our create-1,000
// ratio sits from the published M1 mount — and it sits behind the row's own
// refusal: conditioned only on `summary.run1k` existing, an ours ratio of -1.2
// would move the published 1.2107 by a perfectly finite -199.1%. `report`
// prints rather than returns, so these are PROCESS assertions, and both
// directions are asserted.
const WORKLOAD_CONCLUSION = /workload moves the ratio by/;

test('THE WORKLOAD CONCLUSION: a refused ours ratio or duration prints UNMEASURED, not a movement', () => {
  // The duration rows sit under an ordinary 1.2 ratio, so a guard that looked
  // only at the derived number would still print the movement.
  for (const [field, bad, named] of [['ratio', -1.2, 'ratio=-1.2'], ['base', 0, 'base ms=0'], ['ms', 0, `${ARM0} ms=0`]]) {
    const f = oursJson(`wl-${field}.json`, (j) => {
      if (field === 'base') j.summary.run1k.base = bad;
      else j.summary.run1k.arms[ARM0][field] = bad;
    });
    const r = run(['--theirs', theirsDir(`wl-${field}`), '--ours', f]);
    assert.doesNotMatch(r.stdout, WORKLOAD_CONCLUSION, `ours ${field}=${bad} may not yield a movement`);
    assert.ok(r.stdout.includes(`UNMEASURED — ${named}`), r.stdout);
  }
});

test('THE WORKLOAD CONCLUSION: a MEASURED run exits 0, states the movement and SUMS the unverified counts', () => {
  const f = oursJson('wl-ok.json', (j) => {
    j.summary.run1k.unverified = 2;
    j.summary.clear1k.unverified = 3;
  });
  const r = run(['--theirs', theirsDir('wl-ok'), '--ours', f]);
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /workload moves the ratio by -?\d+\.\d%/);
  assert.match(r.stdout, /;;\s+unverified\s+5\b/);
});

test('THE WORKLOAD CONCLUSION is OURS-only: a short DRIVER table does not suppress it', () => {
  // This section is one instrument across two pages, so the benchmark driver's
  // evidence is not among its premises. It is also the process-exit row for
  // short evidence: exit 1, the missing cell named.
  const r = run(['--theirs', theirsDir('wl-short', { skip: [`${ARM0}_01_run1k`] }), '--ours', oursJson('wl-short.json')]);
  assert.strictEqual(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /1 of 10 expected cells were not measured/);
  assert.match(r.stderr, /01_run1k \/ rf2-fresco/);
  assert.match(r.stdout, WORKLOAD_CONCLUSION, 'ours measured this page; the driver is not its premise');
});

// A COUNT THAT IS NOT THERE IS NOT A ZERO. A fold of `s.unverified ? … : 0`
// would print a clean `unverified 0` over entries that never recorded the
// field. The count is REPORTED here and decided by `jsfb_ours_run.cjs`, so a
// refusal in it must leave the comparator's own exit where it was.
test('THE UNVERIFIED COUNT: an absent or non-numeric count is UNSTATED, every one named, and the exit left alone', () => {
  for (const [counts, named] of [[{ replace1k: undefined, swaprows: undefined }, 'replace1k, swaprows'], [{ run1k: 'n/a' }, 'run1k']]) {
    const f = oursJson(`unv-${named.replace(/\W+/g, '-')}.json`, (j) => {
      for (const [ourId, n] of Object.entries(counts)) {
        if (n === undefined) delete j.summary[ourId].unverified;
        else j.summary[ourId].unverified = n;
      }
    });
    const r = run(['--theirs', theirsDir(`unv-${named.replace(/\W+/g, '-')}`), '--ours', f]);
    assert.strictEqual(r.status, 0, r.stderr);
    assert.ok(r.stdout.includes(`UNSTATED — no \`unverified\` count under ${named}, so no total is derived`), r.stdout);
  }
});

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

fs.rmSync(TMP, { recursive: true, force: true });

if (failed > 0) {
  console.error(`\njsfb_compare_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`jsfb_compare_exit_path.test.cjs: ${tests.length} passed`);
