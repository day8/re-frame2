#!/usr/bin/env node
'use strict';
// THE PER-KEYSTROKE WITNESS'S FIXTURES, IN A GATE.
//
//     node src/re_frame/bench/fresco/clock_witness.test.cjs   (from bench/fresco/)
//
// `clock_witness.cjs` decides whether the keystroke row's `n` means anything.
// Grouping Event Timing entries by `${interactionId || 0}` inside an
// already-known physical sample would make the zero-id `beforeinput`/`input`
// entries a second pseudo-interaction and publish 60 keys as 109-115
// "interactions". Nothing would go red. Nothing could — the only way to run
// that arithmetic would be to open a browser and read a console line.
//
// So the adjudicator's own refusals run in the lane's `npm run check`, beside
// `lane_build.test.cjs`, and this file is what makes a mutation to the
// grouping go red in seconds rather than in a bench run nobody schedules.
//
// The self-test lives in the module rather than here, so `clock_run.cjs` runs
// the same fixtures on every invocation before it launches Chromium. This file
// is the GATE over them — their fixture count included, so a fixture cannot go
// quietly — plus the checks the fixtures do not make: the identity a physical
// key is grouped by, the censoring rate an arm publishes, the expected set a
// census refusal names, and the witness shape the formatted block states.

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
  assert.ok(checks.length >= 20, `expected the witness to carry its fixtures, saw ${checks.length}`);
});

// --- the shape of a physical key --------------------------------------------

test('a physical key is identified by segment, arm, round AND sample', () => {
  assert.deepStrictEqual(witness.KEY_FIELDS, ['seg', 'arm', 'round', 'sampleIndex']);
});

// --- the keys and entries the checks below adjudicate -----------------------

const SHAPE = { cells: 100, fields: 4, substrate: ['fresco'], floors: ['floor'] };
const CENSUS = { 's/fresco': { 'p0/cell': 100, 'p0/draft': 4 } };

const keyAt = (round, sampleIndex) => ({ seg: 's', arm: 'fresco', round, sampleIndex, field: 0 });

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
  assert.strictEqual(v.perArm['s/fresco'].censoredPct, 50);
  const lines = witness.format(v).join('\n');
  assert.match(lines, /censored/);
  assert.match(lines, /CONDITIONAL on clearing 16 ms/);
});

// --- sub-recompute localisation is a gate -----------------------------------

test('the recompute census refuses a substrate arm that did not recompute the stated set', () => {
  const t = twoKeys();
  const v = witness.adjudicate({ ...t, census: { 's/fresco': { 'p0/cell': 100 } } });
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
