#!/usr/bin/env node
'use strict';
// THE OUTSIDE-INSTRUMENT COMPARATOR'S EXIT PATH — absent evidence is not
// agreement. rf2-rguy1, filed by #7359's merged-PR audit, and the same defect
// `clock_exit_path.test.cjs` pins for the bench DRIVERS: a program whose exit
// code is quoted as a quality gate, taking that exit from a reading that had
// nothing in it.
//
//     node freehand/test/re_frame/bench/hicasso/jsfb_compare_exit_path.test.cjs
//
// THE DEFECT THIS PINS. `jsfb_compare.cjs` read the benchmark driver's results
// directory with `if (!fs.existsSync(dir)) return {}` and our run's JSON with
// `OURS && fs.existsSync(OURS) ? … : null`, then filtered the table down to the
// rows where both instruments produced a finite ratio. With neither file
// present that filter kept nothing, and the program printed
//
//     ;; VERDICT: 0 of 0 comparable rows agree within 15%
//
// and exited 0. Reproduced against the landed file before this repair, three
// ways: results directory absent, results directory renamed away with the real
// `ours3.json` present, and results present with `--ours` pointing nowhere.
// All three exited 0. So "the comparator is green" and "the comparison never
// happened" were the same observation, which is the failure direction that
// cannot be allowed: the commonest reason a results directory is missing is a
// run that did not take place.
//
// WHY IT IS PINNED HERE RATHER THAN END TO END. The evidence the comparator
// reads is produced by a chromedriver run of an outside benchmark and a
// headless-Chromium run of ours, neither of which a unit test can take. So the
// repair put the whole decision in ONE pure function over the read evidence,
// which this file drives directly, plus an end-to-end block that spawns the
// program and asserts the PROCESS exit code — because the process exit is the
// observable the bead is actually about, and a pure function returning 2 is no
// use if nothing carries it to the shell.
//
// EVERY FIXTURE IS BUILT IN THIS FILE, in a `mkdtemp` directory removed
// afterwards. Nothing here asserts that any path in the checkout exists: the
// preserved run lives outside the repository by design, so a test that read it
// would pass on the box that took it and fail everywhere else.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const CMP = path.join(__dirname, 'jsfb_compare.cjs');
const { verdict, buildRows, readTheirs, readOurs, EXPECTED_CELLS, PAIRS, OTHERS, BASE } = require('./jsfb_compare.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- fixtures, built here and nowhere else -----------------------------------

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'jsfb-cmp-'));
const scratch = (name) => {
  const p = path.join(TMP, name);
  fs.mkdirSync(p, { recursive: true });
  return p;
};

/** A result file shaped as `webdriver-ts` writes one. */
const resultFile = (framework, benchmark, median) => ({
  framework: `${framework}-v0.0.1.alpha-keyed`,
  benchmark,
  type: 'cpu',
  values: { total: { min: median, max: median, median, mean: median, stddev: 0, values: [median] } },
});

/**
 * A complete results directory: every benchmark the driver runs, every arm.
 * `skip` drops named `framework_benchmark` files so a SHORT run can be built.
 */
function theirsDir(name, { skip = [], corrupt = null } = {}) {
  const dir = scratch(name);
  for (const p of PAIRS.filter((x) => x.theirs)) {
    for (const arm of [BASE, ...OTHERS]) {
      const file = `${arm}_${p.bench}.json`;
      if (skip.includes(`${arm}_${p.bench}`)) continue;
      // Arms differ so the ratios are finite and not all exactly 1.0.
      const median = arm === BASE ? 100 : arm === OTHERS[0] ? 120 : 90;
      fs.writeFileSync(path.join(dir, file), JSON.stringify(resultFile(arm, p.bench, median)));
    }
  }
  if (corrupt) fs.writeFileSync(path.join(dir, corrupt), '{ this is not json');
  return dir;
}

/** Our run's JSON, as `jsfb_ours_run.cjs` writes it. `drop` removes cells. */
function oursJson(name, { drop = [], over = {} } = {}) {
  const summary = {};
  for (const p of PAIRS) {
    summary[p.ours] = { base: 100, unverified: 0, arms: {} };
    for (const arm of OTHERS) {
      if (drop.includes(`${arm}_${p.ours}`)) continue;
      summary[p.ours].arms[arm] = { ms: 120, ratio: arm === OTHERS[0] ? 1.2 : 0.95, perRound: [], lo: 1, hi: 1 };
    }
  }
  const file = path.join(TMP, name);
  fs.writeFileSync(
    file,
    JSON.stringify({ provenance: { rounds: 5 }, parity: { identical: true }, summary, control: { pass: true }, pageErrors: 0, ...over })
  );
  return file;
}

/** The evidence bundle the decision is taken over. */
function evidence(dir, oursFile) {
  const t = readTheirs(dir);
  const o = readOurs(oursFile);
  return { absent: [...t.absent, ...o.absent], rows: buildRows(t.table, o.ours) };
}

// --- the green case first, so the gate is not vacuously red ------------------

test('COMPLETE evidence exits 0 and says nothing', () => {
  const v = verdict(evidence(theirsDir('green'), oursJson('green.json')));
  assert.deepStrictEqual(v, { code: 0, lines: [] });
});

test('the complete fixture really does fill every expected cell', () => {
  // Without this the refusals below would all pass against a fixture that
  // simply never produces a comparable row.
  const { rows } = evidence(theirsDir('green2'), oursJson('green2.json'));
  const measured = rows.filter((r) => Number.isFinite(r.diff)).map((r) => r.cell);
  assert.deepStrictEqual([...measured].sort(), [...EXPECTED_CELLS].sort());
});

// --- THE DEFECT: absent evidence is a refusal, and it used to be a pass ------

test('THE VACUOUS PASS: no results directory and no --ours cannot exit 0', () => {
  const v = verdict(evidence(path.join(TMP, 'nope'), undefined));
  assert.notStrictEqual(v.code, 0, 'a comparison handed nothing must not report agreement');
  assert.strictEqual(v.code, 2);
  assert.match(v.lines.join('\n'), /results directory does not exist/);
  assert.match(v.lines.join('\n'), /--ours <jsfb_ours_run\.cjs JSON> was not given/);
  assert.match(v.lines.join('\n'), /0 of 0 comparable rows/, 'the refusal names the reading it replaces');
});

test('a results directory renamed away, with a REAL ours JSON beside it, still refuses', () => {
  // The audit's own scenario: the run happened, the directory moved.
  const v = verdict(evidence(path.join(TMP, 'results-RENAMED'), oursJson('renamed.json')));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /results directory does not exist: .*results-RENAMED/);
});

test('an --ours path pointing nowhere refuses even with a complete results directory', () => {
  const v = verdict(evidence(theirsDir('ok1'), path.join(TMP, 'no-such-ours.json')));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /our run's JSON does not exist/);
});

test('--theirs naming a FILE rather than a directory refuses', () => {
  const f = oursJson('not-a-dir.json');
  const v = verdict(evidence(f, f));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /--theirs is not a directory/);
});

test('an EMPTY results directory refuses — present is not the same as filled', () => {
  const v = verdict(evidence(scratch('empty'), oursJson('empty.json')));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /holds no `type: cpu` result for any of/);
  assert.match(v.lines[0], /the directory is there but the run that fills it is not/);
});

test('a results directory of files for OTHER frameworks refuses', () => {
  const dir = scratch('strangers');
  fs.writeFileSync(path.join(dir, 'vanillajs_01_run1k.json'), JSON.stringify(resultFile('vanillajs', '01_run1k', 40)));
  const v = verdict(evidence(dir, oursJson('strangers.json')));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /holds no `type: cpu` result/);
});

// --- MALFORMED evidence is absent evidence ----------------------------------

test('a results file that will not parse is NAMED rather than skipped', () => {
  const dir = theirsDir('corrupt', { corrupt: 'rf2-hicasso_01_run1k.json' });
  const v = verdict(evidence(dir, oursJson('corrupt.json')));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /will not parse: rf2-hicasso_01_run1k\.json/);
});

test('an ours JSON that will not parse is NAMED rather than treated as absent-and-fine', () => {
  const f = path.join(TMP, 'broken-ours.json');
  fs.writeFileSync(f, '{ "summary": ');
  const v = verdict(evidence(theirsDir('ok2'), f));
  assert.strictEqual(v.code, 2);
  assert.match(v.lines[0], /our run's JSON will not parse/);
});

test('an ours JSON carrying no `summary` refuses — a shape check, not a truthiness one', () => {
  for (const body of ['{}', '{"summary": null}', '{"summary": 3}', 'null']) {
    const f = path.join(TMP, `noshape-${Buffer.from(body).toString('hex')}.json`);
    fs.writeFileSync(f, body);
    const v = verdict(evidence(theirsDir('ok3'), f));
    assert.strictEqual(v.code, 2, `body ${body} must refuse`);
    assert.match(v.lines[0], /carries no `summary` object|will not parse/);
  }
});

// --- SHORT evidence: the comparison ran, and the table has holes -------------

test('one benchmark missing from THEIRS refuses, naming both its cells and the side', () => {
  const dir = theirsDir('short-theirs', {
    skip: [`${BASE}_05_swap1k`, `${OTHERS[0]}_05_swap1k`, `${OTHERS[1]}_05_swap1k`],
  });
  const v = verdict(evidence(dir, oursJson('short-theirs.json')));
  assert.notStrictEqual(v.code, 0);
  assert.strictEqual(v.code, 1, 'evidence that arrived short is a different failure from evidence that never arrived');
  const all = v.lines.join('\n');
  assert.match(all, /2 of 10 expected cells were not measured by both instruments/);
  assert.match(all, /05_swap1k \/ rf2-hicasso: the benchmark driver's results carry no usable median/);
  assert.match(all, /05_swap1k \/ rf2-uix: the benchmark driver's results carry no usable median/);
  assert.doesNotMatch(all, /01_run1k/, 'a cell that arrived must not be blamed');
});

test('one ARM missing from OURS refuses, naming our side', () => {
  const v = verdict(evidence(theirsDir('short-ours'), oursJson('short-ours.json', { drop: [`${OTHERS[0]}_replace1k`] })));
  assert.strictEqual(v.code, 1);
  const all = v.lines.join('\n');
  assert.match(all, /1 of 10 expected cells/);
  assert.match(all, /02_replace1k \/ rf2-hicasso: our run's JSON carries no usable ratio/);
});

test('the DENOMINATOR going missing takes every cell of that benchmark with it', () => {
  // Losing `rf2-reagent` loses the ratio for both other arms, and a gate that
  // only looked for the named arm would not notice.
  const dir = theirsDir('no-base', { skip: [`${BASE}_02_replace1k`] });
  const v = verdict(evidence(dir, oursJson('no-base.json')));
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /2 of 10 expected cells/);
});

test('a cell absent from BOTH instruments says so rather than blaming one', () => {
  const dir = theirsDir('both-gone', {
    skip: [`${BASE}_09_clear1k_x8`, `${OTHERS[0]}_09_clear1k_x8`, `${OTHERS[1]}_09_clear1k_x8`],
  });
  const v = verdict(evidence(dir, oursJson('both-gone.json', { drop: [`${OTHERS[0]}_clear1k`, `${OTHERS[1]}_clear1k`] })));
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /09_clear1k_x8 \/ rf2-hicasso: NEITHER instrument measured it/);
});

test('a ratio present but NOT FINITE is missing evidence, not a measured cell', () => {
  // `null`, and a JSON round-trip of NaN, both land here. A filter asking
  // truthiness would have taken 0 as absent and NaN as present.
  [null, 'n/a', undefined].forEach((bad, i) => {
    const dir = theirsDir('nan');
    const f = path.join(TMP, `nan-${i}.json`);
    const j = JSON.parse(fs.readFileSync(oursJson('nan-src.json'), 'utf8'));
    j.summary.run1k.arms[OTHERS[0]].ratio = bad;
    fs.writeFileSync(f, JSON.stringify(j));
    const v = verdict(evidence(dir, f));
    assert.strictEqual(v.code, 1, `ratio=${String(bad)} must refuse`);
    assert.match(v.lines.join('\n'), /01_run1k \/ rf2-hicasso: our run's JSON carries no usable ratio/);
  });
});

// --- THE EXPECTED COUNT, derived rather than typed --------------------------

test('EXPECTED_CELLS is the ten the audit counted, and it is DERIVED from PAIRS', () => {
  assert.strictEqual(EXPECTED_CELLS.length, 10, 'five benchmarks the driver runs, crossed with two non-base arms');
  assert.deepStrictEqual(EXPECTED_CELLS, [
    '01_run1k / rf2-hicasso', '01_run1k / rf2-uix',
    '02_replace1k / rf2-hicasso', '02_replace1k / rf2-uix',
    '03_update10th1k_x16 / rf2-hicasso', '03_update10th1k_x16 / rf2-uix',
    '05_swap1k / rf2-hicasso', '05_swap1k / rf2-uix',
    '09_clear1k_x8 / rf2-hicasso', '09_clear1k_x8 / rf2-uix',
  ]);
  assert.strictEqual(
    EXPECTED_CELLS.length,
    PAIRS.filter((p) => p.theirs).length * OTHERS.length,
    'the count must fall out of the roster, so a new row raises the bar by itself'
  );
});

test('the positive control is measured on ONE instrument by design and is not expected of theirs', () => {
  // `07_create10k` is not in the published `--benchmark 01_ 02_ 03_ 05_ 09_`
  // selection. If it were expected, a correct run would refuse forever; if the
  // flag did not exist, a driver run that lost five files would look normal.
  const ctl = PAIRS.find((p) => p.ours === 'create10k');
  assert.strictEqual(ctl.theirs, false);
  assert.ok(!EXPECTED_CELLS.some((c) => c.startsWith('07_create10k')), 'no create10k cell may be expected of theirs');
  // And a complete run — which has no `theirs` create10k — still exits 0.
  assert.strictEqual(verdict(evidence(theirsDir('ctl'), oursJson('ctl.json'))).code, 0);
});

test('a run that measured ONLY the control refuses — ten cells short, all named', () => {
  const dir = scratch('ctl-only');
  const v = verdict(evidence(dir, oursJson('ctl-only.json')));
  assert.notStrictEqual(v.code, 0);
  // An empty directory is absent evidence, so this lands at 2 rather than 1;
  // the point is that it lands somewhere non-zero, which it did not before.
  assert.strictEqual(v.code, 2);
});

// --- the run's OWN gates are reported here and decided elsewhere -------------

test('a FAILING parity gate does not change this program\'s exit — one seat per decision', () => {
  // `jsfb_ours_run.cjs` exits on parity, the control, page errors and
  // unverified writes. This program reporting them is a description; deciding
  // them here would be the second seat `clock_exit_path.test.cjs` exists to
  // forbid. The published run's `ours3.json` carries `parity.identical: false`
  // and a failed control, and that disposition is rf2-rguy1 item 1's to
  // settle — not this gate's to pre-empt.
  const f = oursJson('failing-gates.json', { over: { parity: { identical: false }, control: { pass: false }, pageErrors: 3 } });
  assert.strictEqual(verdict(evidence(theirsDir('gates'), f)).code, 0);
});

// --- END TO END: the process exit, which is the observable the bead names ----

const run = (args) => cp.spawnSync(process.execPath, [CMP, ...args], { encoding: 'utf8' });

test('THE PROCESS EXIT: absent evidence exits non-zero from the shell', () => {
  const r = run(['--theirs', path.join(TMP, 'no-such-dir')]);
  assert.notStrictEqual(r.status, 0, 'the shell must see the refusal, not just the function');
  assert.strictEqual(r.status, 2);
  assert.match(r.stderr, /\[compare\] REFUSED — the benchmark driver's results directory does not exist/);
  assert.doesNotMatch(r.stdout, /VERDICT: 0 of 0/, 'no table may be printed over evidence that is not there');
});

test('THE PROCESS EXIT: complete evidence exits 0 and prints the table', () => {
  const r = run(['--theirs', theirsDir('e2e'), '--ours', oursJson('e2e.json')]);
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /THE CROSS-CHECK — hicasso \/ reagent/);
  assert.match(r.stdout, /VERDICT: \d+ of 10 comparable rows agree within 15% \(10 expected\)/);
  assert.strictEqual(r.stderr, '');
});

test('THE PROCESS EXIT: short evidence exits 1 and names the missing cells', () => {
  const dir = theirsDir('e2e-short', { skip: [`${OTHERS[1]}_03_update10th1k_x16`] });
  const r = run(['--theirs', dir, '--ours', oursJson('e2e-short.json')]);
  assert.strictEqual(r.status, 1, r.stderr);
  assert.match(r.stderr, /1 of 10 expected cells were not measured/);
  assert.match(r.stderr, /03_update10th1k_x16 \/ rf2-uix/);
});

test('THE PROCESS EXIT: no arguments at all is a refusal, not a green run', () => {
  const r = run([]);
  assert.notStrictEqual(r.status, 0);
  assert.strictEqual(r.status, 2);
  assert.match(r.stderr, /--theirs <results dir> was not given/);
});

test('requiring the comparator does not RUN it', () => {
  // Everything above depends on this: a module that compared on require would
  // have taken its exit before the first assertion.
  const SRC = fs.readFileSync(CMP, 'utf8');
  assert.match(SRC, /module\.exports = \{ verdict, buildRows, readTheirs, readOurs, EXPECTED_CELLS, PAIRS, OTHERS, BASE, AGREEMENT_BAND \};/);
  assert.match(SRC, /if \(require\.main === module\) \{/);
});

// --- the wiring: `verdict` is load-bearing, not decorative -------------------

{
  const SRC = fs.readFileSync(CMP, 'utf8');
  const MAIN = SRC.slice(SRC.indexOf('function main()'), SRC.indexOf('module.exports'));

  test('the exit code comes from `verdict` and is RETURNED, not re-derived', () => {
    assert.ok(MAIN.length > 0, 'the comparator must expose its run as `main`');
    assert.match(MAIN, /const v = verdict\(\{ absent, rows \}\);/);
    assert.match(MAIN, /for \(const line of v\.lines\) console\.error\(line\);/);
    assert.match(MAIN, /return v\.code;/);
  });

  test('`main` never calls process.exit itself — the decision has ONE seat', () => {
    assert.ok(!/process\.exit/.test(MAIN), 'a process.exit inside `main` is a second decision, invisible to every test above');
  });

  test('NOTHING downstream of `verdict` reads an absence on its own', () => {
    const tail = MAIN.slice(MAIN.indexOf('const v = verdict('));
    assert.ok(
      tail.length > 0 && !/absent\.length|existsSync|missing/.test(tail),
      'the exit path must consult `verdict` alone, never an absence directly'
    );
  });

  test('the old fail-open readers are gone from the file entirely', () => {
    // The two lines that were the defect. Named exactly, because a repair that
    // leaves either behind has left the second path in place.
    assert.ok(!/if \(!fs\.existsSync\(dir\)\) return \{\};/.test(SRC), 'the silent empty-table return must not survive');
    assert.ok(!/OURS && fs\.existsSync\(OURS\) \?/.test(SRC), 'the silent null-ours ternary must not survive');
  });

  test('the header documents every code the decision can return', () => {
    const header = SRC.slice(0, SRC.indexOf("const fs = require"));
    for (const code of ['0', '1', '2']) {
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

fs.rmSync(TMP, { recursive: true, force: true });

if (failed > 0) {
  console.error(`\njsfb_compare_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`jsfb_compare_exit_path.test.cjs: ${tests.length} passed`);
