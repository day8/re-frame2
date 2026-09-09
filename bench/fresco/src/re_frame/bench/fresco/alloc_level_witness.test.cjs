#!/usr/bin/env node
'use strict';
// THE LEVEL WITNESS'S FIXTURES AND ITS CORPUS CONTROL, IN A GATE — rf2-a233t.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.test.cjs
//
// `alloc_level_witness.cjs` decides whether a floor run held ONE level across
// its own transition. Two things are checked here and they answer different
// questions.
//
// THE FIXTURES answer "does the rule do what its header says": they are
// synthetic, they cross the bound in both directions, and they live in the
// module so any caller runs them.
//
// THE CORPUS CONTROL answers the only question that licences ARMING the thing:
// does this bound refuse EXACTLY the elevated runs and nothing else? That is
// not a claim to be written in a record and left there — every dataset it
// rests on is committed, so it is re-derived here on every run of this gate.
// The counts below are therefore PINNED: a committed dataset that changes, a
// bound that drifts, or an estimator that is quietly redefined all turn this
// red rather than turning a published table wrong.
//
// WHY THE EXACT COUNTS AND NOT JUST "no false positives". A witness that
// stopped scoring runs — an estimator typo that emptied every window, say —
// would report zero false positives with perfect honesty. So the population
// sizes are asserted beside the verdict, which is the same reason
// `clock_witness.test.cjs` asserts its own fixture count has not shrunk.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const witness = require('./alloc_level_witness.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// The corpus control below re-derives its populations from the committed run
// corpus, which is archived in git history (`data_archive.cjs`). A test declared
// through `corpusTest` runs when `data/` is present and is counted as skipped —
// one printed line at the exit — when it is not; `scoreCorpus` tolerates a
// missing directory, so without the guard these would pass on nothing.
const archive = require('./data_archive.cjs');
let corpusSkipped = 0;
const corpusTest = (name, fn) => test(name, () => {
  if (archive.present()) fn();
  else corpusSkipped += 1;
});

// --- the module's own fixtures ----------------------------------------------

test('every fixture in alloc_level_witness.cjs passes', () => {
  const { checks } = witness.selfTest();
  const bad = checks.filter((c) => !c.ok).map((c) => c.name);
  assert.deepStrictEqual(bad, [], `failing fixtures: ${bad.join(', ')}`);
  assert.ok(checks.length >= 35, `the fixture set shrank to ${checks.length}; it had 36`);
});

// --- the corpus control -----------------------------------------------------

let corpus = null;
const scored = () => (corpus ??= witness.scoreCorpus());

corpusTest('the bound refuses every elevated run in the committed corpus', () => {
  const r = scored();
  assert.deepStrictEqual(
    r.misses.map((m) => `${m.corpus}/${m.run}`),
    [],
    'an elevated run was not refused — the bound is too loose'
  );
});

corpusTest('and it refuses no run that is not elevated', () => {
  const r = scored();
  assert.deepStrictEqual(
    r.falsePositives.map((m) => `${m.corpus}/${m.run}`),
    [],
    'a normal run was refused — a false refusal on this arm is expensive'
  );
});

corpusTest('the populations are the ones the bound was set against', () => {
  const r = scored();
  // 101 admissible records; 100 carry both halves on at least one segment.
  assert.strictEqual(r.scored, 101, 'the admissible population changed');
  assert.strictEqual(r.normal.n + r.mode.n, 100, 'the scored population changed');
  assert.strictEqual(r.mode.n, 40, 'the elevated population changed');
  assert.strictEqual(r.normal.n, 60, 'the normal population changed');
  assert.strictEqual(r.refusedForStep, 40, 'the refusal count no longer equals the elevated count');
});

corpusTest('the two populations are still separated by the margin the bound rests on', () => {
  const r = scored();
  assert.strictEqual(r.normal.minB, 96);
  assert.strictEqual(r.normal.maxB, 194);
  assert.strictEqual(r.mode.minB, 2616);
  assert.strictEqual(r.mode.maxB, 3984);
  // The bound sits between them with room on both sides. These are the two
  // numbers the bound was chosen against; if either moves, re-derive it.
  assert.ok(r.normal.maxPct < r.bound, `the worst normal step ${r.normal.maxPct} reached the bound ${r.bound}`);
  assert.ok(r.mode.minPct > r.bound, `the least elevated step ${r.mode.minPct} fell under the bound ${r.bound}`);
  assert.ok(r.bound / r.normal.maxPct > 4, 'less than 4x of margin above the normal population');
  assert.ok(r.mode.minPct / r.bound > 2.5, 'less than 2.5x of margin below the elevated population');
});

corpusTest('the runs the corpus itself excludes are excluded here, and named', () => {
  const r = scored();
  assert.deepStrictEqual(
    r.inadmissible.map((x) => `${x.corpus}/${x.run}`).sort(),
    [
      // Chromium failed to launch; the record has no `alloc` object at all and
      // the driver still exited 1. rf2-c4hhk committed it as its own evidence.
      'alloc-c4hhk/armed-25-a4a1537cb71',
      'alloc-77gz8/run12-a4a1537cb71',
      'alloc-9jrhi/bisect-5-a-4a1537cb71-replicate',
    ].sort()
  );
  assert.deepStrictEqual(
    r.notComputable.map((x) => `${x.corpus}/${x.run}`),
    // A 6-round pilot: there is no round >= 6, so the PUBLISHED estimator does
    // not exist for it either. Refusing it and quoting nothing from it are the
    // same statement.
    ['alloc-9jrhi/pilot-rounds6-head-88411ed803']
  );
});

corpusTest('every committed record carries the whole declared segment roster', () => {
  const r = scored();
  assert.deepStrictEqual(
    r.missingSegment.map((m) => `${m.corpus}/${m.run}`),
    [],
    'a committed record is missing a declared segment — either the record is short or the roster is wrong'
  );
  assert.deepStrictEqual(r.expected, ['reagent-subs', 'uix-subs']);
});

corpusTest('exactly one reading falls back to a ramp round, and it is not an elevated one', () => {
  const r = scored();
  assert.strictEqual(r.degraded.length, 1);
  assert.strictEqual(r.degraded[0].run, 'armed-03-a4a1537cb71');
  assert.strictEqual(r.degraded[0].elevated, false);
});

// --- the mutation proof -----------------------------------------------------
//
// The checks above all run the SHIPPED bound. This one reaches past it: a gate
// that cannot be shown to bite is a gate nobody has watched. Loosening the
// bound past the elevated population must let elevated runs through, and
// tightening it under the normal population must start refusing normal ones.
// If either fails, the separation being claimed is not there.

corpusTest('a bound loosened past the mode stops refusing elevated runs', () => {
  const loose = witness.scoreCorpus({ bound: 0.25 });
  assert.strictEqual(loose.refusedForStep, 0, 'a 25% bound still refused something');
  assert.strictEqual(loose.misses.length, 40, 'the elevated runs did not become misses');
});

corpusTest('a bound tightened under the normal population starts refusing normal runs', () => {
  const tight = witness.scoreCorpus({ bound: 0.004 });
  assert.strictEqual(tight.falsePositives.length, 60, 'a 0.4% bound did not refuse the whole normal population');
});

// THE FAIL-OPEN, ON A REAL RECORD RATHER THAN A FIXTURE — rf2-a233t.
//
// The fixtures above are synthetic and the corpus control only ever sees
// COMPLETE records, so neither of them watches the defect this gate was
// reopened for: a record that lost one whole measured segment. `segmentsOf`
// used to instantiate only the segments that OCCURRED and the sole guard fired
// only when ZERO occurred, so the survivor was adjudicated alone and the
// verdict read CERTIFIED. Measured on this very record before the fix: half
// the arm's measurement deleted, and the witness certified it without remark.
//
// So the mutation is applied to a COMMITTED dataset, in memory, and both
// directions are asserted — the intact record certifies, the mutilated one
// refuses. A gate that can only be shown to pass is not a gate.

const RECORD = path.join(__dirname, 'fixtures', 'alloc-77gz8', 'run01-a4a1537cb71.json');
const readRecord = () => JSON.parse(fs.readFileSync(RECORD, 'utf8'));

const withoutSegment = (record, segment) => {
  const copy = JSON.parse(JSON.stringify(record));
  let removed = 0;
  for (const round of copy.alloc.perRound) {
    for (const key of Object.keys(round.arms)) {
      if (round.arms[key].segment === segment) { delete round.arms[key]; removed++; }
    }
  }
  assert.ok(removed > 0, `the mutation removed nothing — ${segment} is not in this record and the proof below is vacuous`);
  return copy;
};

test('the committed record this proof mutates certifies while it is intact', () => {
  const v = witness.adjudicate(readRecord());
  assert.strictEqual(v.ok, true, 'the control record no longer certifies; the refusal below would prove nothing');
  assert.deepStrictEqual(v.segments.map((s) => s.segment), ['reagent-subs', 'uix-subs']);
});

test('and REFUSES once one whole measured segment is removed from it', () => {
  const v = witness.adjudicate(withoutSegment(readRecord(), 'uix-subs'));
  assert.strictEqual(v.ok, false, 'a record missing half its measurement still certified — the fail-open is back');
  assert.deepStrictEqual([...new Set(v.faults.map((f) => f.code))], ['level-segment']);
  const absent = v.segments.find((s) => s.segment === 'uix-subs');
  assert.strictEqual(absent.absent, true, 'the absent segment was dropped rather than reported');
  assert.strictEqual(absent.nBefore + absent.nAfter, 0);
  // The surviving segment is still read, so the refusal is not a bail-out.
  assert.strictEqual(v.segments.find((s) => s.segment === 'reagent-subs').step, 168);
  const text = witness.format(v, 'mutilated');
  assert.ok(!text.includes('CERTIFIED'), 'the verdict still formats CERTIFIED');
  assert.ok(text.includes('ABSENT'), 'the verdict does not name the absent segment');
});

test('the same holds for the other segment, so the roster is not half-checked', () => {
  const v = witness.adjudicate(withoutSegment(readRecord(), 'reagent-subs'));
  assert.strictEqual(v.ok, false);
  assert.ok(v.faults.some((f) => f.code === 'level-segment' && f.message.startsWith('reagent-subs:')));
});

corpusTest('and the corpus control fails rather than passes when a record loses a segment', () => {
  // The corpus control is what the spine actually runs. Score the same corpus
  // with a roster the records cannot satisfy: every scored run must now be
  // held out of the bands and named, not quietly certified.
  const r = witness.scoreCorpus({ expected: ['reagent-subs', 'uix-subs', 'a-segment-no-record-carries'] });
  assert.strictEqual(r.missingSegment.length, 101, 'a roster no record satisfies left runs unrefused');
  assert.strictEqual(r.normal.n + r.mode.n, 0, 'runs missing a declared segment still reached the bands');
});

// --- runner -----------------------------------------------------------------

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}\n     ${e.message}`);
  }
}
if (corpusSkipped > 0) archive.skipped(`alloc_level_witness.test.cjs: ${corpusSkipped} corpus-backed checks`);
console.log(`;; ${tests.length - corpusSkipped - failed}/${tests.length - corpusSkipped} checks pass`);
process.exit(failed ? 1 : 0);
