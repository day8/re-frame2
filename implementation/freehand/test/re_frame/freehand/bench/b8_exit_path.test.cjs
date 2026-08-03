#!/usr/bin/env node
'use strict';
// B8'S EXIT PATH — an unsettled warm-up cannot be green. rf2-tb345 (#7226).
//
//     node freehand/test/re_frame/freehand/bench/b8_exit_path.test.cjs
//
// THE DEFECT THIS PINS. `reportWarmth` returns every (arm, D) that hit
// B8_WARMUP_MAX while the guard still called its trajectory contaminated,
// `report` persisted that list as `summary.warmupUnsettled`, and the process
// exit then tested `summary.orderRefusals.length` AND NOTHING ELSE. So B8
// could print
//
//     ;;   NOT SETTLED inside the ceiling: narrow/reagent/D=32 — raise B8_WARMUP_MAX
//
// in the middle of its own report and still exit 0, on figures taken off a
// site that was still moving, whenever the measured-round guard was clean.
// Printing a refusal is not refusing.
//
// WHY IT IS PINNED HERE. B8's driver needs a release build and a headless
// Chromium, so its verdict cannot be exercised end-to-end in a unit test —
// the same reason `_impl-browser-runners-verdict-policy.test.cjs` exists one
// tree over. The repair therefore put the whole decision in ONE pure
// function over the summary, which this file exercises directly, plus a
// wiring pin that the exit code comes from that function and from no other
// reading of either refusal list. Those two together are what make "an
// unsettled warm-up cannot be green" a checked claim rather than an
// asserted one.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'b8_run.cjs');
// Requiring the driver must NOT drive it: the `require.main === module`
// guard is itself part of what is under test here.
const { verdict } = require('./b8_run.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the green case first, so the gate is not vacuously red ----------------

test('a clean run exits 0', () => {
  assert.deepStrictEqual(verdict({ warmupUnsettled: [], orderRefusals: [] }), {
    code: 0,
    lines: [],
  });
});

test('a summary that never ran (undefined) is not a refusal', () => {
  assert.strictEqual(verdict(undefined).code, 0);
});

// --- the defect: an unsettled warm-up, measured guard clean -----------------

test('an unsettled warm-up alone is a NONZERO exit — the case that used to be green', () => {
  const v = verdict({
    warmupUnsettled: ['narrow/reagent/D=32'],
    orderRefusals: [],
  });
  assert.notStrictEqual(v.code, 0, 'a site that never settled must not exit 0');
  assert.strictEqual(v.code, 3);
});

test('the unsettled refusal NAMES the sites and its bead', () => {
  const [line, ...rest] = verdict({
    warmupUnsettled: ['narrow/reagent/D=32', 'broad/instrument/D=8'],
    orderRefusals: [],
  }).lines;
  assert.deepStrictEqual(rest, [], 'only the warm-up refusal should be reported');
  assert.match(line, /narrow\/reagent\/D=32/);
  assert.match(line, /broad\/instrument\/D=8/);
  assert.match(line, /rf2-tb345/);
  assert.match(line, /B8_WARMUP_MAX/);
});

// --- and the measured order refusal, still independent ---------------------

test('an order refusal alone still exits 2 — the pre-existing contract is unchanged', () => {
  const v = verdict({ warmupUnsettled: [], orderRefusals: ['narrow/floor'] });
  assert.strictEqual(v.code, 2);
  assert.deepStrictEqual(v.lines.length, 1);
  assert.match(v.lines[0], /arm-order guard \(rf2-88pie\): narrow\/floor/);
});

test('both refusals: both are NAMED, and the order guard keeps its code', () => {
  const v = verdict({
    warmupUnsettled: ['narrow/reagent/D=32'],
    orderRefusals: ['narrow/floor'],
  });
  assert.strictEqual(v.code, 2, 'a run that exited 2 before must still exit 2');
  assert.strictEqual(v.lines.length, 2, 'neither refusal may mask the other');
  assert.match(v.lines.join('\n'), /rf2-tb345/);
  assert.match(v.lines.join('\n'), /rf2-88pie/);
});

// --- the wiring: `verdict` is load-bearing, not decorative ------------------

const SRC = fs.readFileSync(DRIVER, 'utf8');

test('the producer still fills BOTH fields `verdict` consumes', () => {
  assert.match(SRC, /summary\.warmupUnsettled = reportWarmth\(/);
  assert.match(SRC, /summary\.orderRefusals = refusals;/);
});

test('the process exit code comes from `verdict` and is returned, not re-derived', () => {
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  assert.ok(tail.length > 0, 'the driver must expose its run as `drive`');
  assert.match(tail, /const v = verdict\(out && out\.summary\);/);
  assert.match(tail, /return v\.code;/);
  assert.match(tail, /require\.main === module/);
  assert.match(tail, /drive\(\)\.then\(\(code\) => \{\s*if \(code !== 0\) process\.exit\(code\);/);
});

test('NOTHING downstream of `verdict` reads a refusal list on its own', () => {
  // This is the assertion that keeps the original defect from growing back.
  // The defect WAS an exit block reading `orderRefusals` directly; if either
  // field is named again anywhere after the decision function, some second
  // path is deciding the exit and the pure-function tests above stop
  // covering it.
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  assert.doesNotMatch(
    tail,
    /orderRefusals|warmupUnsettled/,
    'the exit path must consult `verdict` alone, never a refusal list directly'
  );
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
  console.error(`\nb8_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`b8_exit_path.test.cjs: ${tests.length} passed`);
