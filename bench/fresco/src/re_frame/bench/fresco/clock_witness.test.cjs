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
// quietly — plus the censoring rate an arm publishes, which the fixtures do not
// check.

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

// --- censoring is published, not dropped ------------------------------------

test('a key that produced no entry is censored and the arm publishes the rate', () => {
  const key = (sampleIndex) => ({ seg: 's', arm: 'fresco', round: 0, sampleIndex, field: 0 });
  const entry = (name, interactionId, duration) =>
    ({ ...key(0), warm: true, name, interactionId, duration, startTime: 0, processingStart: 1, processingEnd: 2 });
  const v = witness.adjudicate({
    sent: [key(0), key(1)],
    entries: [entry('keydown', 11, 24), entry('keyup', 11, 32), entry('input', 0, 24)],
    census: { 's/fresco': { 'p0/cell': 100, 'p0/draft': 4 } },
    shape: { cells: 100, fields: 4, substrate: ['fresco'], floors: ['floor'] },
  });
  assert.strictEqual(v.ok, true, JSON.stringify(v.faults));
  assert.strictEqual(v.perArm['s/fresco'].censoredPct, 50);
  assert.match(witness.format(v).join('\n'), /CONDITIONAL on clearing 16 ms/);
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
