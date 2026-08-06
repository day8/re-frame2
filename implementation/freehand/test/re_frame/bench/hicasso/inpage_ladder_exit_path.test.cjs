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

test('THE CRLF CRASH: a real dataset adjudicates identically as CRLF and as LF', () => {
  // The exact input that used to kill this file before it compared a single
  // figure — held HERE rather than read off the disk. Both variants are built
  // from the tracked dataset however git materialised it: a Windows checkout
  // (`core.autocrlf=true`) lands CRLF, a Linux one lands the stored LF, and
  // normalising then re-rendering gives both on either.
  //
  // THIS TEST USED TO ASSERT `runA.edn` CARRIES CRLF, which is a fact about
  // git's checkout settings, not about this code: true on Windows, false on
  // Linux CI, and it failed there while passing here. The behaviour worth
  // pinning was never "the file is CRLF" — it is "the ending is not part of
  // the data", and that is what is asserted now, on every platform.
  const lf = read('A').replace(/\r\n/g, '\n');
  const crlf = lf.replace(/\n/g, '\r\n');
  assert.ok(!lf.includes('\r'), 'the LF variant must carry no CR at all');
  assert.ok(crlf.includes('\r\n'), 'the CRLF variant must really carry CRLF');

  // The crash itself: `readMap`'s anchor was spelled LF-only, so on CRLF it
  // matched nothing and threw `dataset has no :rounds` for every key.
  for (const key of ['rounds', 'arms', 'decomposition', 'ratio-to-floor']) {
    assert.strictEqual(
      agg.readMap(crlf, key),
      agg.readMap(lf, key),
      `:${key} must read the same whichever way the file landed`
    );
  }
  // And the whole adjudication, not merely the key lookup: a CRLF dataset
  // must reach the same verdict, and that verdict must still be CLEAN.
  assert.deepStrictEqual(agg.checkRun('A', crlf), agg.checkRun('A', lf));
  assert.deepStrictEqual(agg.checkRun('A', crlf).problems, []);
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

// --- the raw shape, and the NaN that used to pass -------------------------
//
// rf2-409ab audit item 2. The command exited 0, printed "every published
// aggregate reproduces", and emitted `NaN` across five cells — because the
// arm roster was derived from the rows that survived, and because every
// comparison against a NaN is false.
//
// EVERY FIXTURE BELOW IS BUILT IN-TEST from the tracked dataset text, never
// asserted about the checkout. That is the CRLF tests' model above and it is
// deliberate: an earlier version of one of those asserted a fact about git's
// checkout settings, passed on Windows and failed on Linux CI.

/** Every `[round :arm ms]` row for one arm, gone; the stored block stays. */
const stripArm = (text, arm) =>
  text.replace(new RegExp(`\\[\\d+ :${arm} -?[0-9.]+\\] ?`, 'g'), '');
/** Every row of one round, gone. */
const stripRound = (text, r) =>
  text.replace(new RegExp(`\\[${r} :[A-Za-z0-9-]+ -?[0-9.]+\\] ?`, 'g'), '');

test('THE CONTRACT IS THE COMMITTED DATA\'S OWN SHAPE, on all four runs', () => {
  // The constants are asserted, not derived — so they must be checked against
  // the data once, here. If a future dataset legitimately changes shape this
  // goes red and the constants move WITH the measurement; what it forbids is
  // loosening them to accommodate data that lost rows.
  assert.strictEqual(agg.ARMS.length, 15);
  assert.strictEqual(agg.ROUNDS_PER_RUN, 6);
  assert.strictEqual(agg.SAMPLES_PER_CELL, 10);
  assert.strictEqual(agg.RAW_ROWS, 900);
  assert.strictEqual(new Set(agg.ARMS).size, 15, 'the roster carries no duplicate');
  for (const r of RUNS) {
    const raw = agg.rounds(agg.readMap(read(r), 'rounds'));
    assert.strictEqual(raw.length, 900, `run${r}: 900 raw rows`);
    assert.deepStrictEqual(
      [...new Set(raw.map((x) => x.arm))].sort(),
      [...agg.ARMS].sort(),
      `run${r}: the roster this file asserts must be the roster the run took`
    );
    assert.deepStrictEqual(
      [...new Set(raw.map((x) => x.round))].sort((a, b) => a - b),
      [0, 1, 2, 3, 4, 5],
      `run${r}: round ids`
    );
    const n = new Map();
    for (const x of raw) n.set(`${x.round}|${x.arm}`, (n.get(`${x.round}|${x.arm}`) || 0) + 1);
    assert.strictEqual(n.size, 90, `run${r}: 15 arms x 6 rounds = 90 cells`);
    assert.deepStrictEqual([...new Set(n.values())], [10], `run${r}: 10 samples in every cell`);
  }
});

test('THE FAIL-OPEN: an arm stripped from the raw rounds REFUSES', () => {
  // The audit's own mutation, verbatim: remove every run-A `:noreads` RAW
  // row and leave its stored summary block in place. This exited 0.
  const text = stripArm(read('A'), 'noreads');
  assert.ok(!/\[\d+ :noreads /.test(text), 'no raw :noreads row survives the mutation');
  assert.ok(/:noreads \{:n /.test(text), 'and its stored summary block is untouched');

  const out = agg.checkRun('A', text);
  assert.ok(
    out.problems.some((p) => /arm :noreads is ABSENT from the raw rounds/.test(p)),
    `an absent arm must be named as absent; got: ${out.problems.join(' | ')}`
  );
  // And the second half of the defect: the terms that went NaN must refuse on
  // their non-finiteness rather than compare equal to their stored values.
  for (const term of ['h-reads+commit', 'h-memo-fiber']) {
    assert.ok(
      out.problems.some((p) =>
        p.includes(`:${term} recomputes to NaN, which is not a finite number`)),
      `:${term} must refuse as non-finite; got: ${out.problems.join(' | ')}`
    );
  }
  assert.ok(
    out.problems.some((p) => /no arm mean recomputed for :noreads/.test(p)),
    'and the refusal must name what was absent, not merely that arithmetic failed'
  );
});

test('the NaN trap is real — an equality check on one reads as AGREEMENT', () => {
  // Why finiteness is tested BEFORE the comparison and not by it. This is the
  // exact expression `checkRun` runs against every stored figure.
  assert.ok(!(Math.abs(1.2345 - NaN) > agg.EPS), 'NaN comparisons are all false');
});

test('a whole round missing from the raw rounds REFUSES', () => {
  const out = agg.checkRun('A', stripRound(read('A'), 4));
  assert.ok(
    out.problems.some((p) => /raw rounds carry 750 samples, the contract is 900/.test(p)),
    `the row count must be checked; got: ${out.problems.slice(0, 3).join(' | ')}`
  );
  assert.ok(
    out.problems.some((p) => /runA\/noreads: no samples at all in round\(s\) 4/.test(p)),
    'and the empty round must be named per arm'
  );
});

test('a cell that is not exactly 10 samples REFUSES — short OR long', () => {
  const short = agg.checkRun('A', read('A').replace(/\[0 :bare -?[0-9.]+\] /, ''));
  assert.ok(
    short.problems.some((p) => /runA\/bare: round 0 carries 9 samples, the contract is 10/.test(p)),
    `a dropped sample must refuse; got: ${short.problems.slice(0, 3).join(' | ')}`
  );
  const long = agg.checkRun('A', read('A').replace(/(\[0 :bare -?[0-9.]+\] )/, '$1$1'));
  assert.ok(
    long.problems.some((p) => /runA\/bare: round 0 carries 11 samples, the contract is 10/.test(p)),
    `a duplicated sample must refuse too; got: ${long.problems.slice(0, 3).join(' | ')}`
  );
});

test('an arm outside the roster REFUSES rather than joining it', () => {
  const out = agg.checkRun('A', read('A').replace('[[0 :nohiccup', '[[0 :bogus 1.0] [0 :nohiccup'));
  assert.ok(
    out.problems.some((p) => /raw rounds carry :bogus, which is not one of the 15 ladder arms/.test(p)),
    `an unknown arm must refuse; got: ${out.problems.slice(0, 3).join(' | ')}`
  );
});

test('a control ratio that recomputes non-finite REFUSES', () => {
  // The per-round ratio is taken over the CONTRACT's six rounds, so a floor
  // absent from one round makes that round's ratio NaN instead of quietly
  // averaging the five that survived.
  const text = read('A').replace(/\[3 :floor -?[0-9.]+\] /g, '');
  const out = agg.checkRun('A', text);
  assert.ok(
    out.problems.some((p) =>
      /:ratio-to-floor :ctl-2x :mean recomputes to NaN, which is not a finite number/.test(p)),
    `a non-finite control ratio must refuse; got: ${out.problems.slice(0, 4).join(' | ')}`
  );
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
