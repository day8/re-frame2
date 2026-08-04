#!/usr/bin/env node
'use strict';
// THE TWO HEAP DRIVERS' POSITIVE CONTROL — a control must be able to fail.
// rf2-bml5u, the inverse of the rf2-rr6do defect class that #7450 repaired.
//
//     node freehand/test/re_frame/freehand/bench/heap_control_exit_path.test.cjs
//
// THE DEFECT THIS PINS. `b7_run.cjs` and `reads_ladder_run.cjs` each take an
// in-situ positive control — a dense array of 587,500 doubles, predicted
// 4,700,000 B because slot width is a build fact — and each PRINTED it:
// predicted beside measured beside an error percentage, on every reader, in
// the report and in the raw artefact. Neither computed a pass or a fail. So
// a control that had stopped measuring anything at all could not stop the
// run, and the number beside it was published anyway. That is not a refusal
// left unread; it is a refusal never computed, which is why a sweep for
// printed-but-unread refusals walked past both.
//
// The control exists because the predecessor instrument reported those same
// ~4.7 MB as 0.00 MB. An instrument that cannot see the change its own
// arithmetic predicts has not measured the ones it does not predict either.
//
// WHY IT IS PINNED HERE. Both drivers need an `:advanced` release build and
// a headless Chromium, so their verdicts cannot be exercised end-to-end in a
// unit test. The repair therefore put the whole decision in pure functions
// over flat inputs, which this file exercises directly, plus wiring pins
// that the exit path consults those functions and not a second reading of
// the raw numbers. `b8_exit_path.test.cjs` and `clock_exit_path.test.cjs`
// are the precedent for the shape.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// Requiring a driver must NOT drive it: the `require.main === module` guard
// is itself part of what is under test here.
const DRIVERS = [
  { file: path.join(__dirname, 'b7_run.cjs'), mod: require('./b7_run.cjs') },
  { file: path.join(__dirname, 'reads_ladder_run.cjs'), mod: require('./reads_ladder_run.cjs') },
];

const PREDICTED = 587500 * 8; // 4,700,000 B — both drivers' own arithmetic
const SLACK = 0.25;

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/** Rounds whose two correlated readers both landed on the prediction. */
const rounds = (over = []) =>
  [0, 1, 2].map((round) => ({
    round,
    control: {
      measuredCdp: PREDICTED * (1 + 0.0001 * round),
      measuredPerf: PREDICTED * (1 - 0.0001 * round),
      ...(over[round] || {}),
    },
  }));

const snaps = (measured) => ({ control: { predictedBytes: PREDICTED, measured } });

for (const { file, mod } of DRIVERS) {
  const { controlVerdict, controlVerdicts, controlFailures } = mod;
  const name = path.basename(file);
  const SRC = fs.readFileSync(file, 'utf8');
  const t = (what, fn) => test(`${name}: ${what}`, fn);

  // --- the green case first, so the gate is not vacuously red --------------

  t('a control that met its prediction on every reader says nothing', () => {
    const v = controlVerdicts(rounds(), snaps(PREDICTED), PREDICTED, SLACK);
    assert.deepStrictEqual(Object.keys(v), ['A', 'B', 'C']);
    assert.ok(v.A.ok && v.B.ok && v.C.ok);
    assert.deepStrictEqual(controlFailures(v), []);
  });

  t('the published error margins pass — gating changes no recorded conclusion', () => {
    // B7 published -0.17% / -0.17% / +0.0005%; the reads ladder published
    // <= 0.06%. Both sit far inside +/-25%, which is why arming this gate
    // retro-fails nothing that was ever measured.
    for (const err of [-0.0017, 0.000005, 0.0006, -0.0006]) {
      const v = controlVerdict('reader A', PREDICTED, [PREDICTED * (1 + err)], SLACK);
      assert.ok(v.ok, `a control ${(err * 100).toFixed(4)}% off must still pass`);
    }
  });

  // --- the band ------------------------------------------------------------

  t('THE RULE IS OVERLAP against +/-slack of the prediction', () => {
    const at = (...xs) => controlVerdict('reader A', 100, xs, 0.25); // band [75 – 125]
    assert.ok(at(90, 110).ok, 'a range inside the band passes');
    assert.ok(at(60, 80).ok, 'a range that merely REACHES the floor passes — this is overlap');
    assert.ok(at(120, 140).ok, 'a range that merely reaches the ceiling passes');
    assert.ok(!at(50, 70).ok, 'a range entirely below the band fails');
    assert.ok(!at(130, 200).ok, 'a range entirely above the band fails');
  });

  t('the 0.00 MB reading this control exists for is REFUSED', () => {
    // The recorded fault, replayed: the predecessor sampler reported
    // ~4.7 MB of held doubles as zero.
    const v = controlVerdicts(rounds([{ measuredCdp: 0 }, { measuredCdp: 0 }, { measuredCdp: 0 }]),
      null, PREDICTED, SLACK);
    assert.ok(!v.A.ok, 'a control reading zero must not hold');
    assert.match(v.A.why, /DISJOINT/);
    assert.deepStrictEqual(controlFailures(v).length, 1, 'and only reader A is at fault');
  });

  t('EVERY reader is adjudicated — B alone failing still refuses', () => {
    // The recorded shape of this defect class: spine_ablation's first cut
    // adjudicated A and C only, and reader B's error was printed and never
    // gated anything.
    const v = controlVerdicts(
      rounds([{ measuredPerf: 0 }, { measuredPerf: 0 }, { measuredPerf: 0 }]),
      snaps(PREDICTED),
      PREDICTED,
      SLACK
    );
    assert.ok(v.A.ok && v.C.ok, 'A and C held');
    assert.ok(!v.B.ok, 'B did not');
    assert.strictEqual(controlFailures(v).length, 1);
    assert.match(controlFailures(v)[0], /reader B/);
  });

  t('reader C alone failing still refuses', () => {
    const v = controlVerdicts(rounds(), snaps(17), PREDICTED, SLACK);
    assert.ok(v.A.ok && v.B.ok);
    assert.ok(!v.C.ok);
    assert.strictEqual(controlFailures(v).length, 1);
  });

  // --- finiteness, tested BEFORE the band ----------------------------------

  t('A NaN CONTROL REFUSES — a band test alone is a bypass', () => {
    // spine_ablation_run.cjs's recorded lesson: `Math.abs(NaN) > band` is
    // false, so a control that came back NaN sailed through the only test
    // there was. A control that produced no finite number has controlled
    // nothing.
    for (const bad of [NaN, Infinity, -Infinity]) {
      const v = controlVerdict('reader A', PREDICTED, [PREDICTED, bad], SLACK);
      assert.ok(!v.ok, `${bad} must not pass the band test`);
      assert.match(v.why, /no finite reading/);
    }
  });

  t('a reader that took NO readings refuses rather than passing vacuously', () => {
    const v = controlVerdict('reader A', PREDICTED, [], SLACK);
    assert.ok(!v.ok);
    assert.match(v.why, /\(0 taken\)/);
  });

  t('an ABSENT verdict block is a failure, not a pass', () => {
    // The gate must not be defeated by the thing it reads going missing.
    for (const absent of [undefined, null, {}]) {
      const f = controlFailures(absent);
      assert.strictEqual(f.length, 1, `${JSON.stringify(absent)} must refuse`);
      assert.match(f[0], /no positive control was adjudicated at all/);
    }
  });

  t('reader C is absent, not failed, when the snapshot pass did not reach it', () => {
    // `LADDER_SNAPSHOT=0` / `B7_SNAPSHOT=0` is a legitimate mode, and a
    // reader that was never asked for must not be reported as a broken one.
    // (An INCOMPLETE reader C that WAS asked for is a separate, older gate.)
    for (const s of [null, undefined, { error: 'boom' }]) {
      const v = controlVerdicts(rounds(), s, PREDICTED, SLACK);
      assert.deepStrictEqual(Object.keys(v), ['A', 'B']);
      assert.deepStrictEqual(controlFailures(v), []);
    }
  });

  // --- the wiring ----------------------------------------------------------

  t('the slack is its OWN knob, never the arm-order guard\'s TOLERANCE', () => {
    // The two are equal by coincidence and mean different things: one is a
    // relative difference of medians between strata of one arm, the other is
    // how far a control may sit from a prediction.
    assert.match(SRC, /const CONTROL_SLACK = Number\(process\.env\.\w+_CONTROL_SLACK \|\| 0\.25\)/);
    assert.match(SRC, /const TOLERANCE = Number\(process\.env\.\w+_TOLERANCE \|\| 0\.25\)/);
    // The order verdict keeps TOLERANCE; the control keeps CONTROL_SLACK.
    assert.match(SRC, /guard\.verdict\(orderSamples, \{ tolerance: TOLERANCE \}\)/);
    assert.match(SRC, /controlVerdicts\(rounds, snapshots, CONTROL_PREDICTED, CONTROL_SLACK\)/);
    assert.doesNotMatch(
      SRC,
      /controlVerdicts?\([^)]*\bTOLERANCE\b/,
      'the control must never be adjudicated on the arm-order knob'
    );
  });

  t('the verdict is carried BESIDE the control in the artefact', () => {
    assert.match(SRC, /verdict: controlVerdicts\(|controlVerdict: controlVerdicts\(/);
  });

  t('the exit path consults `controlFailures` and nothing else', () => {
    const tail = SRC.slice(SRC.indexOf('async function drive('));
    assert.ok(tail.length > 0, 'the driver must expose its run as `drive`');
    assert.match(tail, /const ctlFailed = /);
    assert.match(tail, /controlFailures\(/);
    // The original defect was a report that printed a control and an exit
    // block that read something else. If the exit path ever recomputes a
    // margin of its own, the pure-function tests above stop covering it.
    assert.doesNotMatch(
      tail,
      /measuredCdp|measuredPerf/,
      'the exit path must consult the verdict, never re-read the raw control numbers'
    );
  });

  t('requiring the driver does not drive it', () => {
    assert.match(SRC, /module\.exports = \{ controlVerdict, controlVerdicts, controlFailures \};/);
    assert.match(SRC, /if \(require\.main === module\) \{/);
  });
}

// --- per-driver exit codes, which must not shift ---------------------------

test('b7_run.cjs: the report and the raw artefact precede the refusal', () => {
  const SRC = fs.readFileSync(path.join(__dirname, 'b7_run.cjs'), 'utf8');
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  const report = tail.indexOf('summariseHeap(out.heap)');
  const write = tail.indexOf('raw data ->');
  const exitAt = tail.indexOf('if (failed) {');
  assert.ok(report > 0 && write > report, 'the row is summarised, then written');
  assert.ok(exitAt > write, 'the evidence must survive the refusal');
});

test('reads_ladder_run.cjs: the report and both artefacts precede the refusal', () => {
  const SRC = fs.readFileSync(path.join(__dirname, 'reads_ladder_run.cjs'), 'utf8');
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  const json = tail.indexOf("'reads-ladder.json'");
  const txt = tail.indexOf("'reads-ladder.txt'");
  const exitAt = tail.indexOf('if (ctlFailed.length) {');
  assert.ok(json > 0 && txt > json, 'both artefacts are written');
  assert.ok(exitAt > txt, 'the evidence must survive the refusal');
});

test('b7_run.cjs: a control failure joins the `failed` -> exit 1 family', () => {
  const SRC = fs.readFileSync(path.join(__dirname, 'b7_run.cjs'), 'utf8');
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  assert.match(tail, /failed = \[failed, `heap: positive control/);
  // An order refusal still exits 2, and an unverified mount still exits 1 —
  // no previously-refusing run changes its code.
  assert.match(tail, /if \(failed\) \{[\s\S]{0,120}process\.exit\(1\)/);
  assert.match(tail, /if \(refused\) \{[\s\S]{0,200}process\.exit\(2\)/);
  // Both messages survive when both conditions fire.
  assert.match(tail, /\.filter\(Boolean\)\s*\n?\s*\.join\(' \| '\)/);
});

test('reads_ladder_run.cjs: the control gate is APPENDED as 5, shifting no existing code', () => {
  const SRC = fs.readFileSync(path.join(__dirname, 'reads_ladder_run.cjs'), 'utf8');
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  const codes = [...tail.matchAll(/process\.exit\((\d)\)/g)].map((m) => Number(m[1]));
  // THE ENUMERATED GATES, in source order: unverified 3, reader-C 4, order 2,
  // and the control 5 LAST. Those four numbers, and their order, are the
  // claim — a run that previously refused with 2, 3 or 4 must still do so.
  //
  // The 1s are FILTERED OUT rather than counted, and the reason is a fault
  // this assertion had until rf2-sib23 tripped it. It used to fingerprint
  // every `process.exit` in `drive` as the literal `[1, 3, 4, 2, 5, 1]`, so
  // ANY new refusal anywhere in the function turned it red — including one
  // that shifts nothing, which is exactly what its own title says it is
  // guarding against. Exit 1 is not an enumerated gate here: it is this
  // driver's "the instrument is not in a state to measure" family, which the
  // guard self-test opens with, the top-level catch closes with, and to which
  // the page-error refusal was added. Membership of that family is checked
  // below instead, which is a stronger statement than a count.
  const enumerated = codes.filter((c) => c !== 1);
  assert.deepStrictEqual(enumerated, [3, 4, 2, 5]);
  assert.ok(tail.indexOf('ctlFailed') > tail.indexOf('ORDER GUARD REFUSED'),
    'the new gate goes last, so a run that previously refused with 2/3/4 still does');
});

test('reads_ladder_run.cjs: the page-error refusal took no number of its own (rf2-sib23)', () => {
  const SRC = fs.readFileSync(path.join(__dirname, 'reads_ladder_run.cjs'), 'utf8');
  const tail = SRC.slice(SRC.indexOf('async function drive('));
  const at = tail.indexOf('const pageErrors = pageFailures();');
  assert.ok(at > 0, 'the page failures must be read in the exit path');
  // It refuses with 1 — the existing family — and it refuses AHEAD of the
  // enumerated gates, because those adjudicate readings while this one says
  // the readings are not of the thing they name.
  assert.match(
    tail.slice(at),
    /if \(pageErrors\.length\) \{[\s\S]{0,900}?process\.exit\(1\);/,
    'a page error must refuse, and with the existing code 1'
  );
  assert.ok(
    at < tail.indexOf('UNVERIFIED mounts'),
    'the page-error refusal precedes the enumerated gates'
  );
  // And the artefacts still survive it: both files are written above.
  assert.ok(
    tail.indexOf("'reads-ladder.txt'") < at,
    'the evidence must survive the refusal'
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
  console.error(`\nheap_control_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`heap_control_exit_path.test.cjs: ${tests.length} passed`);
