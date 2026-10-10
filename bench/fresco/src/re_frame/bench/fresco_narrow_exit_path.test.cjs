#!/usr/bin/env node
'use strict';
// THE NARROW-WRITE DRIVER'S EXIT PATH — a printed refusal must refuse.
//
//     node src/re_frame/bench/fresco_narrow_exit_path.test.cjs   (from bench/fresco/)
//
// WHAT THIS PINS. Two of this driver's own listed gates are computed, printed
// and written into `report.json`, and a verdict block that read neither would
// exit 0 on a run it had just refused in print.
//
//   * THE WARM-UP. `settled[arm]` is computed inside the `WARMUP_MAX` loop
//     and printed, then stored as `warmupSettled`. A verdict that did not read
//     it would let every arm hit the ceiling still trending, print
//     `VERDICT: reportable.` and exit 0, on figures taken off a site that was
//     still moving.
//
//   * THE CLAMP. `clamped` is computed against the measured
//     `performance.now()` quantum and printed as `CLAMP-LIMITED, not
//     quotable as absolute` — beside a table whose entire purpose is to
//     quote absolutes — so a verdict that never read it would quote them.
//
// The driver's other gates share that shape (a stale write, a broken leg
// identity, a padded verification denominator), which is why the
// decision lives in ONE pure function with nothing downstream of it.
//
// WHY IT IS PINNED HERE. The driver needs an `:advanced` release build and a
// headless Chromium, so its verdict cannot be exercised end-to-end in a unit
// test. `verdict` is pure and exported; this file drives it directly, and
// then pins the wiring that keeps it the exit.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'fresco_narrow_run.cjs');
const { verdict } = require('./fresco_narrow_run.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/** A run that passed every gate. Overridden one field at a time below. */
const clean = (over) => ({
  positionsLost: false,
  orderRefuse: false,
  leaked: false,
  badTotal: 0,
  writeTotal: 720,
  offenders: '',
  identityOk: true,
  warmupUnsettled: [],
  warmupMax: 20,
  clamped: [],
  ...over,
});

// --- the green case first, so the gate is not vacuously red ----------------

test('a clean run exits 0 and says so', () => {
  assert.deepStrictEqual(verdict(clean({})), { code: 0, lines: ['VERDICT: reportable.'] });
});

// --- each refusal, alone and beside the two late ones ----------------------

/** Every refusal: what, the fault, its exit code. */
const REFUSALS = [
  ['a lost position', { positionsLost: true }, 1],
  ['the arm-order guard', { orderRefuse: true }, 2],
  ['a control leak', { leaked: true }, 1],
  ['unverified writes', { badTotal: 5, offenders: 'reagent-ratom:5' }, 1],
  ['a broken leg identity', { identityOk: false }, 1],
  // The two that used to be green: the warm-up and the clamp each get their own code.
  ['an unsettled warm-up', { warmupUnsettled: ['reagent-ratom', 're-frame2'] }, 3],
  ['a clamp-limited leg', { clamped: ['re-frame2/force (4.1x quantum per sample)'] }, 4],
];

test('each refusal exits with its own code, and a warm-up or a clamp never downgrades one', () => {
  const codes = (extra) => Object.fromEntries(REFUSALS.map(([what, over]) => [what, verdict(clean({ ...over, ...extra })).code]));
  const own = Object.fromEntries(REFUSALS.map(([what, , code]) => [what, code]));
  assert.deepStrictEqual(codes({}), own);
  // Beside an unsettled warm-up AND a clamp, every code holds — except the
  // clamp's own, which the warm-up outranks.
  assert.deepStrictEqual(
    codes({ warmupUnsettled: ['reagent-ratom'], clamped: ['a/b (1x quantum per sample)'] }),
    { ...own, 'a clamp-limited leg': 3 }
  );
});

test('every fault at once: each is named, and a lost position takes the code', () => {
  const v = verdict({
    positionsLost: true,
    orderRefuse: true,
    leaked: true,
    badTotal: 2,
    writeTotal: 720,
    offenders: 'reagent-ratom:2',
    identityOk: false,
    warmupUnsettled: ['re-frame2'],
    warmupMax: 20,
    clamped: ['re-frame2/write (3.0x quantum per sample)'],
  });
  assert.strictEqual(v.code, 1, 'a lost position outranks every other fault');
  const text = v.lines.join('\n');
  for (const fault of [
    /no finite position/,
    /REFUSED by the arm-order guard/,
    /moved with the control size/,
    /never reached the DOM/,
    /does not equal the published total/,
    /warm-up never settled/,
    /sits on the clock quantum/,
  ]) {
    assert.match(text, fault);
  }
});

// --- the wiring: what keeps `verdict` the exit -----------------------------

const SRC = fs.readFileSync(DRIVER, 'utf8');
const MAIN = SRC.slice(SRC.indexOf('async function main('), SRC.indexOf('// The exit decision'));

test('NOTHING downstream of `verdict` reads a condition on its own', () => {
  // `main` must END at the decision — anything after it is a second exit path.
  const tail = MAIN.slice(MAIN.indexOf('for (const line of v.lines)'));
  assert.deepStrictEqual(
    tail
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && l !== '}' && !l.startsWith('//')),
    ['for (const line of v.lines) say(line);', 'if (v.code !== 0) process.exitCode = v.code;']
  );
});

test('the summary `main` builds fills every field `verdict` reads', () => {
  // A field left out reads as a cleared gate, which is how the warm-up was green.
  const call = MAIN.slice(MAIN.indexOf('const v = verdict({'));
  for (const field of [
    'positionsLost',
    'orderRefuse: report.refuse',
    'leaked',
    'badTotal',
    'writeTotal',
    'offenders:',
    'identityOk',
    'warmupUnsettled: arms.filter((a) => !settled[a])',
    'warmupMax: WARMUP_MAX',
    'clamped',
  ]) {
    assert.ok(call.includes(field), `the summary must carry \`${field}\``);
  }
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
  console.error(`\nfresco_narrow_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`fresco_narrow_exit_path.test.cjs: ${tests.length} passed`);
