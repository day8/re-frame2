#!/usr/bin/env node
'use strict';
// THE LADDER'S STRUCTURAL WITNESS — what the counts must be, and at R=0
// especially. rf2-xzg3b. AND THE ALLOCATION ROW'S MASKING WITNESS —
// rf2-n6w7o, at the foot of this file.
//
//     node core/test/re_frame/bench/p0_ladder_structural.test.cjs
//
// THE DEFECT THIS PINS. `ladderStructuralFailures` wanted `boundaries === B`
// on every candidate rung, R-independently. That was true of the PRE-FUSION
// runtime, where `:boundaries` counted `(:b->subs idx)` — an entry per
// MOUNTED boundary, whether or not it read anything. rf2-dabt3 fused the
// sub-index into the cell table and changed what the number means: the
// runtime now knows a boundary only through the reader lists of the cells it
// reads, so a boundary that read nothing retains no membership anywhere and
// is correctly absent. At R=0 the answer is 0. The driver still wanted 1200,
// and `--only ladder` exited 1 with twelve read-back failures on a run whose
// every other gate passed.
//
// THE REPAIR IS A STRONGER ASSERTION, NOT A RELAXED ONE. `R === 0 ? 0 : B`
// pins the edgeless-boundary property the fusion was taken for: with no
// per-boundary registry left to hold them, a non-zero reading at R=0 means
// something is retaining a boundary that reads nothing. So the R=0 rung now
// gates a real claim about the fused design instead of restating a
// bookkeeping artefact of the design it replaced. Both directions are
// exercised below, because a check that cannot fail has adjudicated nothing.
//
// WHY IT IS PINNED HERE. `--only ladder` needs a release build and a
// headless Chromium, and it is opt-in — in no gate at all. That is exactly
// how this expectation sat stale from rf2-dabt3 landing until rf2-zei9w next
// ran the driver: nobody ran it for weeks, so nothing said so. The witness
// itself is a pure function of the collected row, so it can be driven with
// contrived summaries and gated on every PR, which is what this file does —
// the same shape as `b8_exit_path.test.cjs` one tree over.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'p0_run.cjs');
// Requiring the driver must NOT drive it: it builds, serves and launches a
// browser. The `require.main === module` guard is part of what is under test.
const {
  ladderStructuralFailures,
  allocSteps,
  allocMaskableWindows,
  ALLOC_MASK_BUDGET_B,
} = require('./p0_run.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the row, as the driver assembles it -----------------------------------

const B = 1200; // ROOTS(4) x perRoot.grid(300), the ladder's fixed boundary count
const RUNGS = [0, 1, 3, 7, 20];
const SEGMENTS = ['reagent-subs', 'uix-subs'];
const DONOR = { reagent: 'reagent-subs', uix: 'uix-subs' };

const NO_RESIDUE = { cells: 0, cellRefs: 0, boundaries: 0, edges: 0, entries: 0 };

// What a HEALTHY candidate arm answers on rung R, post-fusion.
const candidateStamp = (R) => ({
  cells: B * R,
  cellRefs: B * R,
  boundaries: R === 0 ? 0 : B,
  edges: B * R,
  entries: R === 0 ? 1 : B,
});

// A donor arm runs no Hicasso runtime at all, so every field is zero.
const donorStamp = () => ({ cells: 0, cellRefs: 0, boundaries: 0, edges: 0, entries: 0 });

function armsOfRound(mutate) {
  const arms = {};
  for (const segment of SEGMENTS) {
    arms[`${segment}|grid/floor`] = {
      segment,
      arm: 'grid/floor',
      rung: 'floor',
      structural: { ...NO_RESIDUE },
    };
    for (const [donor, seg] of Object.entries(DONOR)) {
      if (seg !== segment) continue;
      for (const R of RUNGS) {
        arms[`${segment}|lad/${donor}#R${R}`] = {
          segment,
          arm: `lad/${donor}`,
          rung: `R${R}`,
          reads: R,
          verify: { hicasso: donorStamp() },
          structural: { ...NO_RESIDUE },
        };
      }
    }
    for (const R of RUNGS) {
      arms[`${segment}|lad/hicasso#R${R}`] = {
        segment,
        arm: 'lad/hicasso',
        rung: `R${R}`,
        reads: R,
        verify: { hicasso: candidateStamp(R) },
        structural: { ...NO_RESIDUE },
      };
    }
  }
  if (mutate) mutate(arms);
  return arms;
}

const rowWith = (mutate, rounds = 2) => ({
  plan: SEGMENTS.map((segment) => ({ segment, arms: [{ boundaries: B }] })),
  perRound: Array.from({ length: rounds }, (_, i) => ({
    round: i + 1,
    arms: armsOfRound(mutate),
  })),
});

// --- the green case first, so the gate is not vacuously red ----------------

test('a healthy fused run answers every expected count', () => {
  assert.deepStrictEqual(ladderStructuralFailures(rowWith(null)), []);
});

test('a row with no arms at all is not a failure', () => {
  assert.deepStrictEqual(ladderStructuralFailures({ plan: [{ arms: [{ boundaries: B }] }], perRound: [] }), []);
});

// --- direction 1: the stale PRE-FUSION reading must refuse -----------------

test('THE DEFECT — a boundary retained at R=0 fails, on both segments, every round', () => {
  // Exactly the pre-fusion stamp: `:boundaries` counted every MOUNTED
  // boundary, so R=0 read 1200. Post-fusion that means 1200 boundaries are
  // being retained by a runtime that should be holding none of them.
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      for (const segment of SEGMENTS) arms[`${segment}|lad/hicasso#R0`].verify.hicasso.boundaries = B;
    })
  );
  assert.strictEqual(fails.length, 4, '2 segments x 2 rounds');
  for (const f of fails) assert.match(f, /hicasso boundaries 1200, expected 0/);
  assert.ok(fails.some((f) => f.includes('reagent-subs|lad/hicasso#R0')));
  assert.ok(fails.some((f) => f.includes('uix-subs|lad/hicasso#R0')));
});

test('any non-zero reading at R=0 refuses, not just the pre-fusion 1200', () => {
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      arms['reagent-subs|lad/hicasso#R0'].verify.hicasso.boundaries = 1;
    })
  );
  assert.strictEqual(fails.length, 2, 'one per round');
  for (const f of fails) assert.match(f, /hicasso boundaries 1, expected 0/);
});

// --- direction 2: the check is still a check at R > 0 ----------------------

test('THE OTHER DIRECTION — a reading rung reporting 0 boundaries still fails', () => {
  // Without this the repair would read as "R=0 is exempt". It is not: at
  // R>=1 every boundary reads R distinct keys, so every registration holds
  // at least one slot and the count must be B.
  for (const R of RUNGS.filter((r) => r !== 0)) {
    const fails = ladderStructuralFailures(
      rowWith((arms) => {
        arms[`uix-subs|lad/hicasso#R${R}`].verify.hicasso.boundaries = 0;
      })
    );
    assert.strictEqual(fails.length, 2, `R=${R}: one per round`);
    for (const f of fails) assert.match(f, new RegExp(`#R${R}: hicasso boundaries 0, expected 1200`));
  }
});

test('a reading rung short by ONE boundary still fails', () => {
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      arms['uix-subs|lad/hicasso#R3'].verify.hicasso.boundaries = B - 1;
    })
  );
  assert.strictEqual(fails.length, 2);
  for (const f of fails) assert.match(f, /hicasso boundaries 1199, expected 1200/);
});

// --- the fields either side of it, so the R=0 carve-out stayed narrow ------

test('R=0 exempts NOTHING but `boundaries` — edges, cells and entries still gate', () => {
  for (const [field, wrong, expected] of [
    ['edges', 1, 0],
    ['cells', 1, 0],
    ['entries', 0, 1],
  ]) {
    const fails = ladderStructuralFailures(
      rowWith((arms) => {
        arms['reagent-subs|lad/hicasso#R0'].verify.hicasso[field] = wrong;
      })
    );
    assert.strictEqual(fails.length, 2, `${field} at R=0 must still be gated`);
    for (const f of fails) assert.match(f, new RegExp(`hicasso ${field} ${wrong}, expected ${expected}`));
  }
});

test('`entries` at R=0 is 1 and not 0 — the empty read-set is still an entry', () => {
  assert.deepStrictEqual(ladderStructuralFailures(rowWith(null)), []);
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      arms['reagent-subs|lad/hicasso#R0'].verify.hicasso.entries = B;
    })
  );
  assert.ok(fails.every((f) => /hicasso entries 1200, expected 1/.test(f)));
});

// --- the donor arms, and the residue half of the witness -------------------

test('a donor arm holding ANY Hicasso boundary fails — R=0 included', () => {
  for (const R of RUNGS) {
    const fails = ladderStructuralFailures(
      rowWith((arms) => {
        arms[`reagent-subs|lad/reagent#R${R}`].verify.hicasso.boundaries = 1;
      })
    );
    assert.strictEqual(fails.length, 2, `donor at R=${R} must be all-zero`);
    for (const f of fails) assert.match(f, /lad\/reagent#R\d+: hicasso boundaries 1, expected 0/);
  }
});

test('residue after teardown is gated on every field, independently of the rung', () => {
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      arms['uix-subs|lad/hicasso#R0'].structural.boundaries = 7;
    })
  );
  assert.strictEqual(fails.length, 2);
  for (const f of fails) {
    assert.match(f, /residue after teardown — hicasso boundaries 7, expected 0/);
  }
});

test('an arm with no stamp at all is skipped, not silently passed as zero', () => {
  // `verify.hicasso` absent means the mount was not read back; the
  // verification gate upstream owns that case and this one must not
  // manufacture a pass for it.
  const fails = ladderStructuralFailures(
    rowWith((arms) => {
      delete arms['reagent-subs|lad/hicasso#R7'].verify;
    })
  );
  assert.deepStrictEqual(fails, []);
});

// --- the wiring, so the pin cannot drift off the thing that exits ----------

const SRC = fs.readFileSync(DRIVER, 'utf8');

// `assert.match` against a 1400-line driver prints the whole file on a
// failure and buries the reason. These say what was expected instead.
const has = (re, why) => assert.ok(re.test(SRC), `p0_run.cjs: expected ${re} — ${why}`);
const lacks = (re, why) => assert.ok(!re.test(SRC), `p0_run.cjs: must no longer match ${re} — ${why}`);

test('the driver exits on THIS function and does not re-derive the counts', () => {
  has(/const structural = ladderStructuralFailures\(out\.ladder\);/, 'the ladder gate calls it');
  has(/if \(structural\.length > 0\) \{/, 'and its result is what pushes a failure');
  has(/module\.exports = \{ ladderStructuralFailures \};/, 'so this file can drive it');
  has(/if \(require\.main === module\)/, 'and requiring the driver must not drive it');
});

test('the R=0 boundary expectation is written as a claim, not hardcoded to B', () => {
  // The line that was stale. A `candidateStamp` built the same wrong way
  // would keep the green case above green, so the source is pinned too.
  has(/boundaries: R === 0 \? 0 : B,/, 'the fused edgeless-boundary property');
  lacks(/\{ boundaries: B, edges: B \* R/, 'the pre-fusion R-independent expectation');
});

test('the printed legend states the R=0 zero rather than the old flat `boundaries = B`', () => {
  has(/boundaries = B \(0 at R=0\)/, 'the printed legend must qualify it');
  lacks(/boundaries = B ·/, 'the unqualified printed legend');
  lacks(/one registration per boundary, R-independent/, 'the stale source legend');
});

// ===========================================================================
// THE ALLOCATION ROW'S MASKING WITNESS — rf2-n6w7o
// ===========================================================================
//
// THE DEFECT THIS PINS. `allocSteps` detects a collection by the SIGN of an
// adjacent step in `usedJSHeapSize`. A sign test is blind in exactly one
// direction: where V8 collects inside a leg that also allocates at least as
// much as the collection reclaimed, the observed step is >= 0, `falls` stays
// 0, and the reclaimed bytes are simply missing from `rise`. The row then
// prints a window that looks clean and reads LOW — and under-reading
// allocation is the direction that manufactures HD-002's predicted
// flat-at-zero, so it is the one direction this row may not fail in.
//
// Until rf2-n6w7o there was no allocation coverage in this file at all, and a
// sample stream with a fully masked collection in it passed `allocSteps`
// silently with nothing anywhere to notice. The pin below is that stream.
//
// HERMETIC BY CONSTRUCTION. Nothing here measures anything. `stream` builds
// the sample buffer `p0-heap/alloc-window!` would have filled, from stated
// per-leg allocations and stated reclaims, so the masked case is a fixture
// rather than something that has to be provoked out of a real collector.

// `[s0, pre0, post0, pre1, post1, ...]` — the shape `alloc-window!` fills:
// one sample before the window, then a pair around every iteration. `legs[i]`
// is the TRUE allocation of work leg i; `reclaim[i]` is what a collection
// took back inside that same leg, which is what the sign test cannot see.
// The gaps between iterations do nothing, as they do in the real window.
function stream(legs, reclaim = []) {
  let h = 10000000;
  const out = [h];
  legs.forEach((a, i) => {
    out.push(h);
    h += a - (reclaim[i] || 0);
    out.push(h);
  });
  return out;
}

test('THE DEFECT — a collection fully masked by net growth is REFUSED', () => {
  // Four warm writes of 200 KB each. A collection runs inside the third and
  // reclaims exactly what that leg allocated, so the step is 0 and the sign
  // test sees nothing at all. 200 KB of real allocation has vanished from
  // `rise`, and the window would have been published as clean.
  const s = allocSteps(stream([200000, 200000, 200000, 200000], [0, 0, 200000, 0]));
  assert.strictEqual(s.falls, 0, 'the sign test is blind here — that IS the defect');
  assert.strictEqual(s.fall, 0, 'and nothing was netted, so `fall` is silent too');
  assert.strictEqual(s.rise, 600000, 'rise under-reads the true 800000 by the reclaimed 200000');
  assert.ok(s.maskable, 'the masking witness must refuse it where the sign test cannot');
  assert.ok(s.headroom < 0, `headroom must be negative, was ${s.headroom}`);
});

test('the budget is the measured fall threshold HALVED, and pinned at its value', () => {
  // Pinned as a NUMBER on purpose. Widening this constant is the one repair
  // that would retro-admit every window it was introduced to refuse, so a
  // change to it has to come through this line and say so.
  assert.strictEqual(ALLOC_MASK_BUDGET_B, 300000, 'ALLOC_FALL_THRESHOLD_B (600000) / 2');
});

test('the gate is not vacuous — a small clean window passes it', () => {
  // Four writes of 20 KB. 100 KB of rise-plus-largest-step against a 300 KB
  // budget: nowhere near the threshold at which a collection can fire, which
  // is the shape rf2-2rtt6.138's small-witness arm has to have.
  const s = allocSteps(stream([20000, 20000, 20000, 20000]));
  assert.strictEqual(s.falls, 0);
  assert.strictEqual(s.rise, 80000);
  assert.strictEqual(s.maxStep, 20000);
  assert.strictEqual(s.headroom, ALLOC_MASK_BUDGET_B - 100000);
  assert.strictEqual(s.maskable, false);
});

test('the boundary is exact — at budget passes, one byte over refuses', () => {
  const at = allocSteps(stream([50000, 50000, 50000, 50000, 50000]));
  assert.strictEqual(at.rise + at.maxStep, ALLOC_MASK_BUDGET_B);
  assert.strictEqual(at.headroom, 0);
  assert.strictEqual(at.maskable, false, 'exactly at budget is still inside it');
  const over = allocSteps(stream([50000, 50000, 50000, 50000, 50000, 1]));
  assert.strictEqual(over.rise + over.maxStep, ALLOC_MASK_BUDGET_B + 1);
  assert.strictEqual(over.headroom, -1);
  assert.ok(over.maskable);
});

test('NET GROWTH CANNOT DEFEAT IT — masking only moves a window toward refusal', () => {
  // The same four legs, once with the third leg's collection masked and once
  // without it. Masking REMOVES bytes from `rise`, so the masked window is
  // the one with MORE headroom — and it is still refused, because the
  // allocation that had to happen before the collector could fire is counted
  // in full, in the legs before it, where no masking is possible.
  const legs = [200000, 200000, 200000, 200000];
  const masked = allocSteps(stream(legs, [0, 0, 200000, 0]));
  const clean = allocSteps(stream(legs));
  assert.ok(masked.rise < clean.rise, 'masking removes bytes from the rising sum');
  assert.ok(masked.headroom > clean.headroom, 'so it can only flatter the headroom');
  assert.ok(masked.maskable && clean.maskable, 'and both are over budget regardless');
});

test('THE FALLS GATE IS UNTOUCHED — a visible collection still counts as one', () => {
  // The half that works. rf2-n6w7o is discharged by ADDING a refusal, never
  // by widening this one: every window it admits today it must still admit.
  const s = allocSteps(stream([20000, 20000], [0, 40000]));
  assert.strictEqual(s.falls, 1, 'a net-negative leg is still a falling step');
  assert.strictEqual(s.fall, 20000, 'the second leg allocated 20000 and lost 40000');
  assert.strictEqual(s.rise, 20000, 'a fall is EXCLUDED from the rising sum, never netted');
  assert.strictEqual(s.endpoints, 0, 'and the endpoints alone would have said nothing happened');
});

test('`maxStep` is the largest single rising step, not the mean or the last', () => {
  const s = allocSteps(stream([1000, 7000, 3000]));
  assert.strictEqual(s.rise, 11000);
  assert.strictEqual(s.maxStep, 7000);
  assert.strictEqual(s.endpoints, 11000);
});

test('an idle window is neither maskable nor a fall', () => {
  const s = allocSteps(stream([0, 0, 0]));
  assert.strictEqual(s.rise, 0);
  assert.strictEqual(s.falls, 0);
  assert.strictEqual(s.maxStep, 0);
  assert.strictEqual(s.maskable, false);
});

// --- the row-level witness the driver actually exits on --------------------

const allocRowWith = (windows, rounds = 2) => ({
  perRound: Array.from({ length: rounds }, (_, i) => ({
    round: i + 1,
    arms: Object.fromEntries(
      Object.entries(windows).map(([key, legs]) => [
        key,
        { segment: 'reagent-subs', arm: 'lad/hicasso', reads: 7, ...allocSteps(legs) },
      ])
    ),
  })),
});

const SMALL = stream([20000, 20000, 20000, 20000]);
const MASKED = stream([200000, 200000, 200000, 200000], [0, 0, 200000, 0]);

test('a row of small clean windows is not a failure', () => {
  assert.deepStrictEqual(
    allocMaskableWindows(allocRowWith({ 'reagent-subs|lad/hicasso#R7': SMALL })),
    []
  );
});

test('a row with no rounds at all is not a failure', () => {
  assert.deepStrictEqual(allocMaskableWindows({ perRound: [] }), []);
});

test('every over-budget window is named, on every round, with its overshoot', () => {
  const fails = allocMaskableWindows(
    allocRowWith({
      'reagent-subs|lad/hicasso#R7': MASKED,
      'reagent-subs|lad/reagent#R7': SMALL,
    })
  );
  assert.strictEqual(fails.length, 2, 'one per round, and only the over-budget arm');
  for (const f of fails) {
    assert.match(f, /lad\/hicasso#R7/);
    assert.match(f, /rise 600000 B \+ largest step 200000 B/);
    assert.match(f, /500000 B over the 300000 B masking budget/);
  }
  assert.ok(fails.some((f) => f.startsWith('round 1 ')));
  assert.ok(fails.some((f) => f.startsWith('round 2 ')));
});

// --- the wiring, so this pin cannot drift off the thing that exits ---------

test('the driver exits on THIS function and does not re-derive the budget', () => {
  has(/const maskable = allocMaskableWindows\(out\.alloc\);/, 'the alloc gate calls it');
  has(/if \(maskable\.length > 0\) \{/, 'and its result is what pushes a failure');
  has(
    /module\.exports = \{\s*ladderStructuralFailures,\s*allocSteps,\s*allocMaskableWindows,\s*ALLOC_MASK_BUDGET_B,\s*\};/,
    'so this file can drive it'
  );
});

test('the budget is DERIVED from the measured threshold and has no dial on it', () => {
  has(
    /const ALLOC_MASK_BUDGET_B = ALLOC_FALL_THRESHOLD_B \/ 2;/,
    'the budget must follow the measured threshold, not be typed beside it'
  );
  lacks(
    /P0_ALLOC_MASK|P0_ALLOC_HEADROOM|P0_ALLOC_BUDGET/,
    'a gate with an env dial on it is a gate that gets dialled off'
  );
  has(/if \(out\.alloc\.fallsInMeasuredWindows > 0\) \{/, 'and the falling-step gate still exits');
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
  console.error(`\np0_ladder_structural.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`p0_ladder_structural.test.cjs: ${tests.length} passed`);
