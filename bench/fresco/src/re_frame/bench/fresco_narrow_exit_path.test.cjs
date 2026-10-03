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
//     and printed twice — per arm as `still trending at the N-window
//     ceiling`, and again in the header's `warm-up` line with a `*` — then
//     stored as `warmupSettled`. A verdict that tested positionsLost,
//     report.refuse, leaked, badTotal and identityOk but not it would let
//     every arm hit the ceiling still trending, print `VERDICT: reportable.`
//     and exit 0, on figures taken off a site that was still moving.
//
//   * THE CLAMP. `clamped` is computed against the measured
//     `performance.now()` quantum and printed as `CLAMP-LIMITED, not
//     quotable as absolute` — beside a table whose entire purpose is to
//     quote absolutes — so a verdict that never read it would quote them.
//
// The driver's other gates share that shape (a stale write, a broken leg
// identity, a padded verification denominator), which is why the
// decision lives in ONE pure function with nothing downstream of it: a
// condition can only be read in one place, so no fail-open can grow in the
// gap between the report and the exit.
//
// WHY IT IS PINNED HERE. The driver needs an `:advanced` release build and a
// headless Chromium, so its verdict cannot be exercised end-to-end in a unit
// test. `verdict` is pure and exported; this file drives it directly, and
// then pins the wiring.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'fresco_narrow_run.cjs');
// Requiring the driver must NOT drive it: the `require.main === module`
// guard is itself part of what is under test here.
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

const joined = (v) => v.lines.join('\n');

// --- the green case first, so the gate is not vacuously red ----------------

test('a clean run exits 0 and says so', () => {
  const v = verdict(clean({}));
  assert.strictEqual(v.code, 0);
  assert.deepStrictEqual(v.lines, ['VERDICT: reportable.']);
});

// --- the defect: an unsettled warm-up, everything else clean ---------------

test('an UNSETTLED WARM-UP alone exits 3 and NAMES the arms, the ceiling and the knob — the case that used to be green', () => {
  const v = verdict(clean({ warmupUnsettled: ['reagent-ratom', 're-frame2'], warmupMax: 20 }));
  assert.notStrictEqual(v.code, 0, 'an arm measured on a site still trending must not exit 0');
  assert.strictEqual(v.code, 3);
  const text = joined(v);
  assert.doesNotMatch(text, /reportable\./, 'a refused run may not also call itself reportable');
  assert.match(text, /reagent-ratom, re-frame2/);
  assert.match(text, /20-window ceiling/);
  assert.match(text, /HN_WARMUP_MAX/);
});

// --- the second, narrower instance: a clamp-limited leg --------------------

test('a CLAMP-LIMITED LEG alone exits 4, names the legs and the repair, and refuses to loosen itself — the case that used to be green', () => {
  const v = verdict(clean({ clamped: ['re-frame2/force (4.1x quantum per sample)'] }));
  assert.notStrictEqual(v.code, 0, 'a leg sitting on the clock quantum must not exit 0');
  assert.strictEqual(v.code, 4, 'the clamp is scoped narrower than the warm-up, and gets its own code');
  const text = joined(v);
  assert.doesNotMatch(text, /reportable\./);
  assert.match(text, /re-frame2\/force \(4\.1x quantum per sample\)/);
  assert.match(text, /HN_WRITES/);
  assert.match(text, /do not loosen the multiple/);
});

// --- the other refusals: their exit codes and their wording ----------------

/** The driver's other refusals: what, the fault, its exit code, its words. */
const OTHER_REFUSALS = [
  ['a lost position', { positionsLost: true }, 1,
    [/VERDICT: FAILED — some samples reached the guard with no finite position/]],
  ['the arm-order guard', { orderRefuse: true }, 2,
    [/VERDICT: REFUSED by the arm-order guard/, /Not the tolerance\./]],
  ['a control leak', { leaked: true }, 1, [/an arm's total moved with the control size/]],
  ['unverified writes', { badTotal: 5, writeTotal: 720, offenders: 'reagent-ratom:5' }, 1,
    [/5 of 720 measured writes never reached the DOM/, /\(reagent-ratom:5\)/]],
  ['a broken leg identity', { identityOk: false }, 1,
    [/write \+ gap \+ force does not equal the published total/]],
];

test('every other refusal exits with its own code, in its own words', () => {
  for (const [what, over, code, words] of OTHER_REFUSALS) {
    const v = verdict(clean(over));
    assert.strictEqual(v.code, code, `${what} must exit ${code}`);
    for (const w of words) assert.match(joined(v), w, what);
  }
});

// --- combinations: nothing masks anything, precedence holds ----------------

test('the warm-up and the clamp together: both named, warm-up takes the code', () => {
  const v = verdict(clean({ warmupUnsettled: ['reagent-ratom'], clamped: ['re-frame2/write (2.0x quantum per sample)'] }));
  assert.strictEqual(v.code, 3);
  assert.match(joined(v), /warm-up never settled/);
  assert.match(joined(v), /sits on the clock quantum/);
});

test('an unsettled warm-up NEVER downgrades an existing refusal', () => {
  for (const [what, over, code] of OTHER_REFUSALS) {
    const after = verdict(clean({ ...over, warmupUnsettled: ['reagent-ratom'], clamped: ['a/b (1x quantum per sample)'] }));
    assert.strictEqual(after.code, code, `${what} must keep exit ${code}`);
    assert.match(joined(after), /warm-up never settled/, 'and the warm-up refusal is NAMED too');
    assert.match(joined(after), /sits on the clock quantum/);
  }
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
  const text = joined(v);
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
  assert.doesNotMatch(text, /reportable\./);
});

// --- the wiring: `verdict` is load-bearing, not decorative -----------------

const SRC = fs.readFileSync(DRIVER, 'utf8');
const MAIN = SRC.slice(SRC.indexOf('async function main('), SRC.indexOf('// The exit decision'));

test('the driver exposes its decision and does not drive itself on require', () => {
  assert.match(SRC, /module\.exports = \{ verdict \};/);
  assert.match(SRC, /if \(require\.main === module\) \{\s*main\(\)\.catch\(/);
});

test('`main` sets its exit code in exactly ONE place', () => {
  // A verdict block of early returns can read some conditions and miss
  // others. One assignment means one decision, and the decision is
  // `verdict`'s.
  assert.strictEqual(
    (MAIN.match(/process\.exitCode/g) || []).length,
    1,
    '`main` must take its exit from `verdict` and nowhere else'
  );
});

test('NOTHING downstream of `verdict` reads a condition on its own', () => {
  // This is the assertion that keeps a fail-open from growing in the gap. A
  // fail-open here is a condition computed above and consulted — or not
  // consulted — somewhere below the report. Once the
  // summary is handed over, `main` has three lines left and none of them
  // may look at a condition again.
  const tail = MAIN.slice(MAIN.indexOf('for (const line of v.lines)'));
  assert.ok(tail.length > 0, 'the say-loop must follow the decision');
  assert.ok(
    !/positionsLost|report\.refuse|leaked|badTotal|identityOk|settled\[|clamped/.test(tail),
    'the exit path must consult `verdict` alone, never a condition directly'
  );
  assert.deepStrictEqual(
    tail
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && l !== '}' && !l.startsWith('//')),
    ['for (const line of v.lines) say(line);', 'if (v.code !== 0) process.exitCode = v.code;'],
    '`main` must END at the decision — anything after it is a second exit path'
  );
});

test('the summary `main` builds fills every field `verdict` reads', () => {
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

test('the header documents every code the decision can return', () => {
  const header = SRC.slice(0, SRC.indexOf("'use strict'"));
  for (const code of ['0', '1', '2', '3', '4']) {
    assert.match(header, new RegExp(`^//   ${code}  \\S`, 'm'), `exit code ${code} must be documented`);
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
