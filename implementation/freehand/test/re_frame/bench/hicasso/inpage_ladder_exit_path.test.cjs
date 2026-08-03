#!/usr/bin/env node
'use strict';
// THE IN-PAGE LADDER AGGREGATE'S EXIT PATH — a promised refusal must refuse.
// rf2-bml5u, the inverse of the rf2-rr6do defect class that #7450 repaired.
//
//     node freehand/test/re_frame/bench/hicasso/inpage_ladder_exit_path.test.cjs
//
// THE DEFECT THIS PINS. `inpage_ladder_aggregate.cjs`'s header has promised
// since it landed that it exits 1 on "a run that recorded a guard refusal or
// a failed control". Nothing in the file read either. That is not a refusal
// computed and left unread — it is a refusal never computed at all, and a
// gate that does not exist cannot be seen to fail, which is why the sweep
// for printed-but-unread refusals walked past it.
//
// A SECOND DEFECT SAT IN FRONT OF THE FIRST. `readMap`'s anchor was spelled
// LF-only, and the tracked datasets materialise CRLF on a normal Windows
// checkout, so the whole file died with `dataset has no :rounds` before
// comparing a single figure. Both halves are pinned below: a gate nobody can
// start is worth exactly as much as a gate nobody computed.
//
// WHY IT IS PINNED HERE. The aggregate reads four stored datasets and takes
// no measurement, so its decisions ARE testable directly — every case below
// is a real dataset with one field mutated, and the mutations are the ones
// the file promises to catch. `clock_exit_path.test.cjs` and
// `b8_exit_path.test.cjs` are the precedent for the shape.
//
// THE RETROACTIVITY FENCE IS ALSO PINNED. The control rule reconstructed
// here is `lane/control-verdict`'s OVERLAP rule, not the stricter
// every-round reading — the two disagree and the disagreement is a KNOWN
// DEFECT reserved to rf2-2rtt6.1 (rf2-egdaq). Run D contains a round at
// 1.2653 that sits below the band floor and passes anyway, and a test below
// pins exactly that, so a later worker who "tightens" the rule discovers
// they are front-running an open operator ruling rather than fixing a bug.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// Requiring the aggregate must NOT run it: the `require.main === module`
// guard is itself part of what is under test here.
const AGG = path.join(__dirname, 'inpage_ladder_aggregate.cjs');
const agg = require('./inpage_ladder_aggregate.cjs');
const SRC = fs.readFileSync(AGG, 'utf8');

const DIR = path.join(__dirname, 'data', 'inpage-ladder-409ab');
const RUNS = ['A', 'B', 'C', 'D'];
const read = (r) => fs.readFileSync(path.join(DIR, `run${r}.edn`), 'utf8');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the green case first, so the gate is not vacuously red ----------------

test('all four published datasets adjudicate CLEAN', () => {
  for (const r of RUNS) {
    const out = agg.checkRun(r, read(r));
    assert.deepStrictEqual(out.problems, [], `run${r} must reproduce with no problems`);
    assert.ok(out.control.ok, `run${r}: the published positive control must pass`);
    assert.ok(!out.order.refuse, `run${r}: the published arm order must be clean`);
  }
});

// --- the line ending ------------------------------------------------------

test('readMap finds its key with CRLF line endings, and with LF', () => {
  // The slice runs from the start of the form's own line, so it carries that
  // line's leading space — the parser's long-standing shape, unchanged here.
  const lf = '{\n :rounds\n [[0 :floor 1.5]]\n :arms\n {:floor {:n 1}}}\n';
  const crlf = lf.replace(/\n/g, '\r\n');
  assert.strictEqual(agg.readMap(lf, 'rounds'), ' [[0 :floor 1.5]]');
  assert.strictEqual(agg.readMap(crlf, 'rounds'), ' [[0 :floor 1.5]]');
});

test('THE CRLF CRASH: the tracked datasets really are CRLF, and still parse', () => {
  // Not a hypothetical. If this repository ever normalises the datasets to
  // LF the first assertion goes quiet and the second still holds, which is
  // the correct outcome — but while they are CRLF, this is the exact input
  // that used to kill the file before it compared anything.
  const raw = fs.readFileSync(path.join(DIR, 'runA.edn'), 'utf8');
  assert.ok(raw.includes('\r\n'), 'runA.edn is expected to carry CRLF on this checkout');
  assert.doesNotThrow(() => agg.readMap(raw, 'rounds'));
  assert.doesNotThrow(() => agg.readMap(raw, 'ratio-to-floor'));
});

test('a genuinely absent key still throws — the fix must not paper over one', () => {
  assert.throws(() => agg.readMap('{\r\n :arms\r\n {}}\r\n', 'rounds'), /dataset has no :rounds/);
});

// --- the positive control -------------------------------------------------

test('the control rule is OVERLAP, exactly as `lane/control-verdict` spells it', () => {
  const at = (min, max) => agg.controlVerdict(2, { min, max, mean: (min + max) / 2 }, 0.25);
  // band is [1.5 – 2.5]
  assert.ok(at(1.9, 2.1).ok, 'a range inside the band passes');
  assert.ok(at(1.2, 1.6).ok, 'a range that merely REACHES the floor passes — this is overlap');
  assert.ok(at(2.4, 3.0).ok, 'a range that merely reaches the ceiling passes');
  assert.ok(!at(1.0, 1.4).ok, 'a range entirely below the band fails');
  assert.ok(!at(2.6, 3.0).ok, 'a range entirely above the band fails');
});

test('THE RETROACTIVITY FENCE: run D holds a round below the band and still passes', () => {
  // rf2-egdaq / rf2-2rtt6.1. `lane.cljs` records overlap-vs-strict as a KNOWN
  // DEFECT reserved to an operator ruling, because tightening it turns a
  // PUBLISHED pass into a published failure. Run D is that case on this
  // instrument: its worst round reads 1.2653 against a band floor of 1.4819.
  // If this test ever goes red because someone made the rule stricter, the
  // change is front-running an open ruling — take it to rf2-2rtt6.1, do not
  // relax this test.
  const out = agg.checkRun('D', read('D'));
  assert.ok(out.control.ok, 'run D passes under the overlap rule the page published under');
  assert.ok(
    out.control.measured.min < out.control.band[0],
    `run D's worst round (${out.control.measured.min}) is expected to sit BELOW the band floor ` +
      `(${out.control.band[0]}) — that is the whole point of this pin`
  );
  const strict = out.control.measured.min >= out.control.band[0] &&
    out.control.measured.max <= out.control.band[1];
  assert.ok(!strict, 'and the strict every-round rule would refuse it');
});

test('a control that misses its own arithmetic REFUSES', () => {
  // The published `:ctl-2x` ratios are ~1.8–2.1 against a prediction of
  // 1.9759. Halve every `ctl-2x` sample and the control can no longer see
  // the doubling its own element arithmetic predicts.
  const text = read('A').replace(/:ctl-2x (-?[0-9.]+)\]/g, (_, ms) => `:ctl-2x ${Number(ms) / 4}]`);
  const out = agg.checkRun('A', text);
  assert.ok(!out.control.ok, 'the control must not hold when the control arm is quartered');
  assert.ok(
    out.problems.some((p) => /positive control FAILED/.test(p)),
    `a control failure must reach the problems array; got: ${out.problems.join(' | ')}`
  );
  assert.ok(
    out.problems.some((p) => /DISJOINT/.test(p)),
    'and it must say why'
  );
});

test('a control whose stored ratio does not reproduce REFUSES', () => {
  // The cross-check, independent of the verdict: the page's stored
  // `:ratio-to-floor :ctl-2x` must be regenerable from the raw rounds.
  const text = read('A').replace(':ctl-2x {:mean 2.0923', ':ctl-2x {:mean 2.5923');
  const out = agg.checkRun('A', text);
  assert.ok(
    out.problems.some((p) => /:ratio-to-floor :ctl-2x :mean stored 2\.5923/.test(p)),
    `the stored ratio must be cross-checked; got: ${out.problems.join(' | ')}`
  );
});

test('a missing `:ctl-2x` ratio block REFUSES rather than being skipped', () => {
  const text = read('A').replace(/:ctl-2x \{:mean [^}]*\}, /, '');
  const out = agg.checkRun('A', text);
  assert.ok(
    out.problems.some((p) => /:ratio-to-floor has no :ctl-2x/.test(p)),
    `an absent control must fail, never pass; got: ${out.problems.join(' | ')}`
  );
});

// --- the arm-order guard --------------------------------------------------

test('the rebuilt guard stream carries predecessor and position as the page did', () => {
  const raw = [
    { round: 0, arm: 'floor', ms: 1.5 },
    { round: 0, arm: 'uix', ms: 3.1 },
    { round: 0, arm: 'floor', ms: 1.6 },
  ];
  assert.deepStrictEqual(agg.guardSamples(raw), [
    { arm: 'floor', value: 1.5, predecessor: null, position: 0 },
    { arm: 'uix', value: 3.1, predecessor: 'floor', position: 1 },
    { arm: 'floor', value: 1.6, predecessor: 'uix', position: 2 },
  ]);
});

test('the rebuilt stream is the whole run — 900 samples, in stored order', () => {
  const raw = agg.rounds(agg.readMap(read('A'), 'rounds'));
  const s = agg.guardSamples(raw);
  assert.strictEqual(s.length, 900, '6 rounds x 15 arms x 10 samples');
  assert.strictEqual(s[899].position, 899, 'position indexes the WHOLE run, not the round');
  assert.strictEqual(s[500].predecessor, raw[499].arm);
});

test('an arm made to depend on WHERE IT RAN is REFUSED', () => {
  // A warm-up step is what the phase factor exists to catch, and it is what
  // no plan reversal would show. Halve every `floor` reading in the last two
  // rounds and the arm reads differently late than early.
  const text = read('A').replace(
    /\[([45]) :floor (-?[0-9.]+)\]/g,
    (_, r, ms) => `[${r} :floor ${Number(ms) / 3}]`
  );
  const out = agg.checkRun('A', text);
  assert.ok(out.order.refuse, 'the arm-order guard must refuse a phase-dependent arm');
  assert.ok(
    out.problems.some((p) => /arm-order guard REFUSED \(rf2-88pie\)/.test(p)),
    `the refusal must reach the problems array; got: ${out.problems.slice(0, 4).join(' | ')}`
  );
  assert.ok(
    out.problems.some((p) => /floor by phase/.test(p)),
    'and it must name the arm and the factor'
  );
});

test('a REFUSAL can never reach the exit as an empty problem list', () => {
  // The fallback. If the guard refuses but the per-factor loop names nothing
  // — a shape change in `order_guard.cjs`, say — the run must still refuse.
  // Proven against the real adjudicator by asserting the invariant on a
  // refusing dataset rather than by faking one.
  const text = read('A').replace(
    /\[([45]) :floor (-?[0-9.]+)\]/g,
    (_, r, ms) => `[${r} :floor ${Number(ms) / 3}]`
  );
  const out = agg.checkRun('A', text);
  assert.ok(out.order.refuse);
  assert.ok(out.problems.length > 0, 'a refusing guard must contribute at least one problem');
});

// --- the wiring -----------------------------------------------------------

test('the aggregate is requirable without running, and exits from `main`', () => {
  assert.match(SRC, /module\.exports = \{/);
  assert.match(SRC, /if \(require\.main === module\) \{\s*const code = main\(\);/);
  assert.match(SRC, /if \(code !== 0\) process\.exit\(code\);/);
});

test('`main` gates on the SAME problems array the checks feed', () => {
  const tail = SRC.slice(SRC.indexOf('function main('));
  assert.match(tail, /problems\.push\(\.\.\.out\.problems\)/);
  assert.match(tail, /if \(problems\.length\) \{/);
  assert.match(tail, /return 1;/);
});

test('the control and the guard are PRINTED before the gate, passing or not', () => {
  const tail = SRC.slice(SRC.indexOf('function main('));
  const printAt = tail.indexOf('POSITIVE CONTROL AND ARM ORDER, PER RUN');
  const gateAt = tail.indexOf('if (problems.length) {');
  assert.ok(printAt > 0, 'the per-run verdicts must be printed');
  assert.ok(printAt < gateAt, 'report first, gate after — a control quoted only when it passes is not a control');
});

test('the header no longer promises anything the file does not compute', () => {
  const header = SRC.slice(0, SRC.indexOf('const fs = require'));
  assert.match(header, /a guard\s*\n?\/\/\s*refusal or a failed control/);
  // The two things that make the promise true.
  assert.match(SRC, /guard\.verdict\(guardSamples\(raw\), \{ tolerance: GUARD_TOLERANCE \}\)/);
  assert.match(SRC, /controlVerdict\(CTL_PREDICTED, ratio, CONTROL_SLACK\)/);
});

test('the reconstruction constants are the page\'s own', () => {
  assert.strictEqual(agg.CTL_PREDICTED, 1.9759, 'the large-template element arithmetic');
  assert.strictEqual(agg.CONTROL_SLACK, 0.25, 'what inpage_ladder_app.cljs passed');
  assert.strictEqual(agg.GUARD_TOLERANCE, 0.1, "lane/guard!'s default");
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
