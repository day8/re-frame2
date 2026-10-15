#!/usr/bin/env node
'use strict';
// Adjudicate real stored runs and keep every refusal connected to the process exit.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const AGG = path.join(__dirname, 'inpage_ladder_aggregate.cjs');
const agg = require('./inpage_ladder_aggregate.cjs');

const DIR = path.join(__dirname, 'fixtures', 'inpage-ladder-409ab');
const RUNS = ['A', 'B', 'C', 'D'];
const read = (r) => fs.readFileSync(path.join(DIR, `run${r}.edn`), 'utf8');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the green case first, so the gate is not vacuously red ----------------

test('all four published datasets adjudicate CLEAN', () => {
  assert.deepStrictEqual(RUNS.map((r) => agg.checkRun(r, read(r)).problems), [[], [], [], []]);
});

// --- the line ending ------------------------------------------------------

test('THE CRLF CRASH: a real dataset adjudicates identically as CRLF and as LF', () => {
  // Both variants are built from the tracked dataset however git materialised
  // it, because whether `runA.edn` CARRIES CRLF is a fact about git's checkout
  // settings, not about this code. The behaviour pinned is "the ending is not
  // part of the data", on every platform.
  const lf = read('A').replace(/\r\n/g, '\n');
  assert.deepStrictEqual(agg.checkRun('A', lf.replace(/\n/g, '\r\n')), agg.checkRun('A', lf));
});

// --- the positive control -------------------------------------------------

test('the control rule is OVERLAP, exactly as `lane/control-verdict` spells it', () => {
  // band is [1.5 – 2.5]: a range that merely REACHES either edge passes, and a
  // range entirely outside fails.
  const ok = (min, max) => agg.controlVerdict(2, { min, max, mean: (min + max) / 2 }, 0.25).ok;
  assert.deepStrictEqual([ok(1.2, 1.6), ok(2.4, 3.0), ok(1.0, 1.4), ok(2.6, 3.0)], [true, true, false, false]);
});

// --- every mutation the file promises to catch ------------------------------
//
// An arm roster derived from the rows that survived would exit 0, print
// "every published aggregate reproduces", and emit `NaN` across five cells.
// Every fixture below is built in-test from the tracked dataset text, never
// asserted about the checkout.

test('every mutation the aggregate promises to catch reaches the problems array, named', () => {
  for (const [what, mutate, expected] of [
    // The control can no longer see the doubling its own arithmetic predicts.
    ['the control arm quartered',
      (t) => t.replace(/:ctl-2x (-?[0-9.]+)\]/g, (_, ms) => `:ctl-2x ${Number(ms) / 4}]`),
      [/runA: positive control FAILED/]],
    ['a stored control ratio that does not reproduce',
      (t) => t.replace(':ctl-2x {:mean 2.0923', ':ctl-2x {:mean 2.5923'),
      [/:ratio-to-floor :ctl-2x :mean stored 2\.5923/]],
    ['the stored control ratio block missing',
      (t) => t.replace(/:ctl-2x \{:mean [^}]*\}, /, ''),
      [/:ratio-to-floor has no :ctl-2x/]],
    // A warm-up step: the arm reads differently late than early, which no plan
    // reversal would show.
    ['an arm made to depend on where it ran',
      (t) => t.replace(/\[([45]) :floor (-?[0-9.]+)\]/g, (_, r, ms) => `[${r} :floor ${Number(ms) / 3}]`),
      [/arm-order guard REFUSED — floor by phase/]],
    // THE FAIL-OPEN: every raw row of one arm gone, its stored summary block left.
    ['an arm stripped from the raw rounds',
      (t) => t.replace(/\[\d+ :noreads -?[0-9.]+\] ?/g, ''),
      [/arm :noreads is ABSENT from the raw rounds/]],
    ['a whole round missing from the raw rounds',
      (t) => t.replace(/\[4 :[A-Za-z0-9-]+ -?[0-9.]+\] ?/g, ''),
      [/raw rounds carry 750 samples, the contract is 900/, /runA\/noreads: no samples at all in round\(s\) 4/]],
    ['a cell one sample short',
      (t) => t.replace(/\[0 :bare -?[0-9.]+\] /, ''),
      [/runA\/bare: round 0 carries 9 samples, the contract is 10/]],
  ]) {
    const problems = agg.checkRun('A', mutate(read('A'))).problems;
    for (const re of expected) {
      assert.ok(problems.some((p) => re.test(p)), `${what}: expected ${re}; got: ${problems.slice(0, 4).join(' | ')}`);
    }
  }
});

// --- the wiring -----------------------------------------------------------
//
// `main` reads the four committed datasets, so its exit cannot be driven with a
// mutated one; what keeps the gate able to fail is read off the source.

test('`main` gates on the SAME problems array the checks feed', () => {
  const SRC = fs.readFileSync(AGG, 'utf8');
  const tail = SRC.slice(SRC.indexOf('function main('));
  assert.match(tail, /problems\.push\(\.\.\.out\.problems\)/);
  assert.match(tail, /if \(problems\.length\) \{/);
  assert.match(tail, /return 1;/);
  assert.match(SRC, /if \(require\.main === module\) \{\s*const code = main\(\);\s*if \(code !== 0\) process\.exit\(code\);/);
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
  console.error(`\ninpage_ladder_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`inpage_ladder_exit_path.test.cjs: ${tests.length} passed`);
