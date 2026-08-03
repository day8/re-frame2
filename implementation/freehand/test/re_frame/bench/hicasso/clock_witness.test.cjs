#!/usr/bin/env node
'use strict';
// THE PER-KEYSTROKE WITNESS'S FIXTURES, IN A GATE — rf2-0qj9w.
//
//     node freehand/test/re_frame/bench/hicasso/clock_witness.test.cjs
//
// `clock_witness.cjs` decides whether the keystroke row's `n` means anything.
// Its predecessor did not: it grouped Event Timing entries by
// `${interactionId || 0}` inside an already-known physical sample, so the
// zero-id `beforeinput`/`input` entries formed a second pseudo-interaction and
// 60 keys were published as 109-115 "interactions". Nothing went red. Nothing
// could — the only way to run that arithmetic was to open a browser and read a
// console line.
//
// So the adjudicator's own refusals ride the fast-PR spine (`npm run
// test:script-helpers`, beside `lane_build.test.cjs`), and this file is what
// makes a mutation to the grouping go red in seconds rather than in a bench
// run nobody schedules.
//
// The self-test lives in the module rather than here, so `clock_run.cjs` runs
// the same fixtures on every invocation before it launches Chromium. This file
// is the GATE over them plus the checks that only make sense from outside: the
// MUTATION PROOF, which reaches past the module's public surface to break the
// grouping the way a careless edit would and asserts that the refusal fires and
// names itself.

const assert = require('node:assert');

const witness = require('./clock_witness.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the module's own fixtures ----------------------------------------------

test('every fixture in clock_witness.cjs passes', () => {
  const { checks } = witness.selfTest();
  const bad = checks.filter((c) => !c.ok).map((c) => c.name);
  assert.deepStrictEqual(bad, [], `failing fixtures: ${bad.join(', ')}`);
  // A self-test that silently stopped having cases is a self-test that passes
  // for the wrong reason.
  assert.ok(checks.length >= 15, `expected the witness to carry its fixtures, saw ${checks.length}`);
});

// --- the shape of a physical key --------------------------------------------

test('a physical key is identified by segment, arm, round AND sample', () => {
  assert.deepStrictEqual(witness.KEY_FIELDS, ['seg', 'arm', 'round', 'sampleIndex']);
});

// --- THE MUTATION PROOF ------------------------------------------------------
//
// Break the grouping so two physical keys collapse into one record, and the
// witness must exit refusing AND name the fault. This is done here rather than
// by hand-editing the module because a mutation proof somebody performed once
// and described afterwards is not a proof anybody can repeat.

const SHAPE = { cells: 100, fields: 4, substrate: ['hicasso'], floors: ['floor'] };
const CENSUS = { 's/hicasso': { 'p0/cell': 100, 'p0/draft': 4 } };

const keyAt = (round, sampleIndex) => ({ seg: 's', arm: 'hicasso', round, sampleIndex, field: 0 });

const entriesFor = (k, interactionId, duration) => [
  { seg: k.seg, arm: k.arm, round: k.round, sampleIndex: k.sampleIndex, warm: true, name: 'keydown', interactionId, duration: duration - 8, startTime: 0, processingStart: 1, processingEnd: 2 },
  { seg: k.seg, arm: k.arm, round: k.round, sampleIndex: k.sampleIndex, warm: true, name: 'keyup', interactionId, duration, startTime: 0, processingStart: 1, processingEnd: 3 },
  { seg: k.seg, arm: k.arm, round: k.round, sampleIndex: k.sampleIndex, warm: true, name: 'input', interactionId: 0, duration: 24, startTime: 0, processingStart: 1, processingEnd: 2 },
];

/** Two physical keys, two interactions, and every accounting rule satisfied. */
const twoKeys = () => {
  const a = keyAt(0, 0);
  const b = keyAt(0, 1);
  return {
    sent: [a, b],
    entries: [...entriesFor(a, 11, 32), ...entriesFor(b, 12, 40)],
    census: CENSUS,
    shape: SHAPE,
  };
};

test('UNMUTATED: two physical keys form two records and the run is clean', () => {
  const v = witness.adjudicate(twoKeys());
  assert.strictEqual(v.ok, true, `unexpected faults: ${JSON.stringify(v.faults)}`);
  assert.strictEqual(v.records.length, 2);
  assert.strictEqual(v.censored.length, 0);
  assert.strictEqual(v.totals.sent, 2);
  // The zero-id entries are counted and are NOT records — the whole defect.
  assert.strictEqual(v.totals.zeroIdEntries, 2);
  assert.strictEqual(v.perArm['s/hicasso'].observed, 2);
});

test('MUTATED (sampleIndex dropped from the key): the collapse is REFUSED by name', () => {
  // The mutation a careless edit makes: the grouping stops distinguishing
  // samples, so both of round 0's keys become the same physical key. Applied
  // to the data rather than to the module — identical effect, and it leaves no
  // edited file behind to forget to revert.
  const { sent, entries, census, shape } = twoKeys();
  const collapse = (x) => ({ ...x, sampleIndex: 0 });
  const v = witness.adjudicate({
    sent: sent.map(collapse),
    entries: entries.map(collapse),
    census,
    shape,
  });
  assert.strictEqual(v.ok, false, 'the witness returned ok on collapsed keys');
  const codes = v.faults.map((f) => f.code);
  assert.ok(codes.includes('collapsed-physical-keys'), `faults were ${codes.join(', ')}`);
  const named = v.faults.find((f) => f.code === 'collapsed-physical-keys');
  assert.match(named.why, /share the identity/);
  assert.match(named.why, /one record per physical key/);
});

test('MUTATED: two keys reported under one interaction id is REFUSED', () => {
  const a = keyAt(0, 0);
  const b = keyAt(0, 1);
  const v = witness.adjudicate({
    sent: [a, b],
    entries: [...entriesFor(a, 11, 32), ...entriesFor(b, 11, 40)],
    census: CENSUS,
    shape: SHAPE,
  });
  assert.strictEqual(v.ok, false);
  assert.ok(v.faults.map((f) => f.code).includes('shared-interaction-id'));
});

// --- censoring is published, not dropped ------------------------------------

test('a key that produced no entry is censored and the arm publishes the rate', () => {
  const a = keyAt(0, 0);
  const b = keyAt(0, 1);
  const v = witness.adjudicate({
    sent: [a, b],
    entries: entriesFor(a, 11, 32),
    census: CENSUS,
    shape: SHAPE,
  });
  assert.strictEqual(v.ok, true, JSON.stringify(v.faults));
  assert.strictEqual(v.censored.length, 1);
  assert.strictEqual(v.perArm['s/hicasso'].censoredPct, 50);
  const lines = witness.format(v).join('\n');
  assert.match(lines, /censored/);
  assert.match(lines, /CONDITIONAL on clearing 16 ms/);
});

test('the published block never claims more interactions than keys pressed', () => {
  const v = witness.adjudicate(twoKeys());
  assert.ok(v.totals.observed <= v.totals.sent);
  assert.strictEqual(v.totals.observed + v.totals.censored, v.totals.sent);
});

// --- sub-recompute localisation is a gate -----------------------------------

test('the recompute census refuses a substrate arm that did not recompute the stated set', () => {
  const t = twoKeys();
  const v = witness.adjudicate({ ...t, census: { 's/hicasso': { 'p0/cell': 100 } } });
  assert.strictEqual(v.ok, false);
  const f = v.faults.find((x) => x.code === 'census-mismatch');
  assert.ok(f);
  assert.match(f.why, /p0\/cell=100 p0\/draft=4/);
});

test('the formatted block states the witness shape validation.md names', () => {
  const lines = witness.format(witness.adjudicate(twoKeys())).join('\n');
  assert.match(lines, /100 grid cells \+ 4 fields = 104 layer-1 recomputes/);
});

// ---------------------------------------------------------------------------

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failed += 1;
    console.error(`FAIL ${name}\n     ${e.message}`);
  }
}
console.log(`${tests.length - failed}/${tests.length} clock_witness checks passed`);
process.exit(failed === 0 ? 0 : 1);
