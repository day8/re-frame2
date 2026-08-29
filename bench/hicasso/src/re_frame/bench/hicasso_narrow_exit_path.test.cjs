#!/usr/bin/env node
'use strict';
// THE NARROW-WRITE DRIVER'S EXIT PATH — a printed refusal must refuse.
// rf2-rr6do, following rf2-tb345's repair of the same defect in b8_run.cjs.
//
//     node adapters/reagent/test/re_frame/bench/hicasso_narrow_exit_path.test.cjs
//
// THE DEFECT THIS PINS. Two of this driver's own listed gates were computed,
// printed and written into `report.json`, and the verdict block read neither.
//
//   * THE WARM-UP. `settled[arm]` was computed inside the `WARMUP_MAX` loop
//     and printed twice — per arm as `still trending at the N-window
//     ceiling`, and again in the header's `warm-up` line with a `*` — then
//     stored as `warmupSettled`. The verdict tested positionsLost,
//     report.refuse, leaked, badTotal and identityOk, and never it. So every
//     arm could hit the ceiling still trending and the run would print
//     `VERDICT: reportable.` and exit 0, on figures taken off a site that
//     was still moving. This is the b8 defect exactly (rf2-tb345).
//
//   * THE CLAMP. `clamped` was computed against the measured
//     `performance.now()` quantum and printed as `CLAMP-LIMITED, not
//     quotable as absolute` — beside a table whose entire purpose is to
//     quote absolutes — and then never read.
//
// It is the FOURTH and FIFTH fail-open found in this one file: the audit of
// PR #7262 already caught three (a stale write, a broken leg identity, a
// padded verification denominator), each in the same shape. That history is
// why the decision now lives in ONE pure function with nothing downstream of
// it: a condition can only be read in one place, so a sixth cannot grow in
// the gap between the report and the exit.
//
// WHY IT IS PINNED HERE. The driver needs an `:advanced` release build and a
// headless Chromium, so its verdict cannot be exercised end-to-end in a unit
// test. `verdict` is pure and exported; this file drives it directly, and
// then pins the wiring.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'hicasso_narrow_run.cjs');
// Requiring the driver must NOT drive it: the `require.main === module`
// guard is itself part of what is under test here.
const { verdict } = require('./hicasso_narrow_run.cjs');

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

test('an UNSETTLED WARM-UP alone is a nonzero exit — the case that used to be green', () => {
  const v = verdict(clean({ warmupUnsettled: ['reagent-ratom'] }));
  assert.notStrictEqual(v.code, 0, 'an arm measured on a site still trending must not exit 0');
  assert.strictEqual(v.code, 3);
  assert.doesNotMatch(joined(v), /reportable\./, 'a refused run may not also call itself reportable');
});

test('the warm-up refusal NAMES the arms, the ceiling, the knob and its bead', () => {
  const v = verdict(clean({ warmupUnsettled: ['reagent-ratom', 're-frame2'], warmupMax: 20 }));
  const text = joined(v);
  assert.match(text, /reagent-ratom, re-frame2/);
  assert.match(text, /20-window ceiling/);
  assert.match(text, /HN_WARMUP_MAX/);
  assert.match(text, /rf2-rr6do/);
});

// --- the second, narrower instance: a clamp-limited leg --------------------

test('a CLAMP-LIMITED LEG alone is a nonzero exit — the case that used to be green', () => {
  const v = verdict(clean({ clamped: ['re-frame2/force (4.1x quantum per sample)'] }));
  assert.notStrictEqual(v.code, 0, 'a leg sitting on the clock quantum must not exit 0');
  assert.strictEqual(v.code, 4, 'the clamp is scoped narrower than the warm-up, and gets its own code');
  assert.doesNotMatch(joined(v), /reportable\./);
});

test('the clamp refusal names the legs and the repair, and refuses to loosen itself', () => {
  const v = verdict(clean({ clamped: ['re-frame2/force (4.1x quantum per sample)'] }));
  const text = joined(v);
  assert.match(text, /re-frame2\/force \(4\.1x quantum per sample\)/);
  assert.match(text, /HN_WRITES/);
  assert.match(text, /do not loosen the multiple/);
  assert.match(text, /rf2-rr6do/);
});

// --- the pre-existing contract, unchanged in code AND in wording -----------

test('a lost position still exits 1, in its own words', () => {
  const v = verdict(clean({ positionsLost: true }));
  assert.strictEqual(v.code, 1, 'a run that exited 1 before must still exit 1');
  assert.match(joined(v), /VERDICT: FAILED — some samples reached the guard with no finite position/);
});

test('the arm-order guard still exits 2, in its own words', () => {
  const v = verdict(clean({ orderRefuse: true }));
  assert.strictEqual(v.code, 2, 'a run that exited 2 before must still exit 2');
  assert.match(joined(v), /VERDICT: REFUSED by the arm-order guard/);
  assert.match(joined(v), /Not the tolerance\./);
});

test('a control leak still exits 1, in its own words', () => {
  const v = verdict(clean({ leaked: true }));
  assert.strictEqual(v.code, 1);
  assert.match(joined(v), /an arm's total moved with the control size/);
});

test('unverified writes still exit 1, and still name the offenders', () => {
  const v = verdict(clean({ badTotal: 5, writeTotal: 720, offenders: 'reagent-ratom:5' }));
  assert.strictEqual(v.code, 1);
  assert.match(joined(v), /5 of 720 measured writes never reached the DOM/);
  assert.match(joined(v), /\(reagent-ratom:5\)/);
});

test('a broken leg identity still exits 1, in its own words', () => {
  const v = verdict(clean({ identityOk: false }));
  assert.strictEqual(v.code, 1);
  assert.match(joined(v), /write \+ gap \+ force does not equal the published total/);
});

// --- combinations: nothing masks anything, precedence is preserved ---------

test('the two NEW refusals together: both named, warm-up takes the code', () => {
  const v = verdict(clean({ warmupUnsettled: ['reagent-ratom'], clamped: ['re-frame2/write (2.0x quantum per sample)'] }));
  assert.strictEqual(v.code, 3);
  assert.match(joined(v), /warm-up never settled/);
  assert.match(joined(v), /sits on the clock quantum/);
});

test('an unsettled warm-up NEVER downgrades an existing refusal', () => {
  for (const [over, code] of [
    [{ positionsLost: true }, 1],
    [{ orderRefuse: true }, 2],
    [{ leaked: true }, 1],
    [{ badTotal: 3, offenders: 'x:3' }, 1],
    [{ identityOk: false }, 1],
  ]) {
    const before = verdict(clean(over)).code;
    const after = verdict(clean({ ...over, warmupUnsettled: ['reagent-ratom'], clamped: ['a/b (1x quantum per sample)'] }));
    assert.strictEqual(before, code);
    assert.strictEqual(after.code, code, `${JSON.stringify(over)} must keep exit ${code}`);
    assert.match(joined(after), /warm-up never settled/, 'and the new refusal is still NAMED');
    assert.match(joined(after), /sits on the clock quantum/);
  }
});

test('the arm-order guard and everything else at once: every fault is named exactly once', () => {
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
  assert.strictEqual(v.code, 1, 'a lost position outranks every other fault, as before');
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

test('the exit code comes from `verdict`, and every one of its lines is SAID', () => {
  assert.match(MAIN, /const v = verdict\(\{/);
  assert.match(MAIN, /for \(const line of v\.lines\) say\(line\);/);
  assert.match(MAIN, /if \(v\.code !== 0\) process\.exitCode = v\.code;/);
});

test('`main` sets its exit code in exactly ONE place', () => {
  // The defect was a verdict block with six early returns, five of which
  // read a condition and one of which did not exist. One assignment means
  // one decision, and the decision is `verdict`'s.
  assert.strictEqual(
    (MAIN.match(/process\.exitCode/g) || []).length,
    1,
    '`main` must take its exit from `verdict` and nowhere else'
  );
});

test('NOTHING downstream of `verdict` reads a condition on its own', () => {
  // This is the assertion that keeps the defect from growing back a sixth
  // time. Every fail-open this file has had was a condition computed above
  // and consulted — or not consulted — somewhere below the report. Once the
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
  console.error(`\nhicasso_narrow_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`hicasso_narrow_exit_path.test.cjs: ${tests.length} passed`);
