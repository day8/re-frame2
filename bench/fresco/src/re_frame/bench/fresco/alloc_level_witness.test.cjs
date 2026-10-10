#!/usr/bin/env node
'use strict';
// THE LEVEL WITNESS'S FIXTURES AND ITS CORPUS CONTROL, IN A GATE.
//
//     node src/re_frame/bench/fresco/alloc_level_witness.test.cjs
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

// The corpus control re-derives its populations from the committed run corpus,
// which is archived in git history (`data_archive.cjs`). A test declared through
// `corpusTest` runs when `data/` is present and is counted as skipped — one
// printed line at the exit — when it is not; `scoreCorpus` tolerates a missing
// directory, so without the guard these would pass on nothing.
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

const names = (rows) => rows.map((x) => `${x.corpus}/${x.run}`);

corpusTest('the bound refuses exactly the elevated runs of the committed corpus, over the populations it was set against', () => {
  const r = witness.scoreCorpus();
  assert.deepStrictEqual(
    {
      misses: names(r.misses),
      falsePositives: names(r.falsePositives),
      // 101 admissible records; 100 carry both halves on at least one segment.
      scored: r.scored,
      normal: r.normal.n,
      elevated: r.mode.n,
      refusedForStep: r.refusedForStep,
      inadmissible: names(r.inadmissible).sort(),
      notComputable: names(r.notComputable),
      missingSegment: names(r.missingSegment),
      rampFallback: r.degraded.map((x) => [names([x])[0], x.elevated]),
      // The two bands the bound was chosen against; if either moves, re-derive it.
      normalBand: [r.normal.minB, r.normal.maxB],
      elevatedBand: [r.mode.minB, r.mode.maxB],
    },
    {
      misses: [],
      falsePositives: [],
      scored: 101,
      normal: 60,
      elevated: 40,
      refusedForStep: 40,
      // Chromium failed to launch on `armed-25`; the other two fail their
      // positive control. Each record is committed as its own evidence.
      inadmissible: [
        'alloc-77gz8/run12-a4a1537cb71',
        'alloc-9jrhi/bisect-5-a-4a1537cb71-replicate',
        'alloc-c4hhk/armed-25-a4a1537cb71',
      ],
      // A 6-round pilot: there is no round >= 6, so the PUBLISHED estimator does
      // not exist for it either.
      notComputable: ['alloc-9jrhi/pilot-rounds6-head-88411ed803'],
      missingSegment: [],
      rampFallback: [['alloc-c4hhk/armed-03-a4a1537cb71', false]],
      normalBand: [96, 194],
      elevatedBand: [2616, 3984],
    }
  );
  assert.ok(r.bound / r.normal.maxPct > 4, `less than 4x of margin above the normal population (${r.normal.maxPct})`);
  assert.ok(r.mode.minPct / r.bound > 2.5, `less than 2.5x of margin below the elevated population (${r.mode.minPct})`);
});

// --- the mutation proof -----------------------------------------------------
//
// The check above runs the SHIPPED bound. This one reaches past it: a gate
// that cannot be shown to bite is a gate nobody has watched. Loosening the
// bound past the elevated population must let every elevated run through, and
// tightening it under the normal population must refuse every normal one.

corpusTest('the bound bites both ways: loosened past the mode it refuses nothing, tightened under the normal population it refuses all of it', () => {
  assert.deepStrictEqual(
    [witness.scoreCorpus({ bound: 0.25 }).refusedForStep, witness.scoreCorpus({ bound: 0.004 }).falsePositives.length],
    [0, 60]
  );
});

// THE FAIL-OPEN, ON A REAL RECORD RATHER THAN A FIXTURE.
//
// The fixtures are synthetic and the corpus control only ever sees COMPLETE
// records, so neither watches the defect this proof exists for: a record that
// lost one whole measured segment. A `segmentsOf` that instantiated only the
// segments that OCCUR would adjudicate the survivor alone and read CERTIFIED.
// So the mutation is applied to a COMMITTED dataset, in memory, for each
// declared segment: the only fault is the missing one, and the survivor is
// still read.

const RECORD = path.join(__dirname, 'fixtures', 'alloc-77gz8', 'run01-a4a1537cb71.json');

test('a committed record REFUSES once either declared segment is removed, and still reads the survivor', () => {
  for (const [gone, kept, step] of [['uix-subs', 'reagent-subs', 168], ['reagent-subs', 'uix-subs', 96]]) {
    const record = JSON.parse(fs.readFileSync(RECORD, 'utf8'));
    for (const round of record.alloc.perRound) {
      for (const key of Object.keys(round.arms)) if (round.arms[key].segment === gone) delete round.arms[key];
    }
    const v = witness.adjudicate(record);
    assert.deepStrictEqual(
      { faults: v.faults.map((f) => `${f.code} ${f.message.split(':')[0]}`), step: v.segments.find((s) => s.segment === kept).step },
      { faults: [`level-segment ${gone}`], step },
      `without ${gone}`
    );
  }
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
