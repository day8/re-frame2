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
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'p0_run.cjs');
// Requiring the driver must NOT drive it: it builds, serves and launches a
// browser. The `require.main === module` guard is part of what is under test.
const {
  ladderStructuralFailures,
  allocSteps,
  allocMaskableWindows,
  allocRefusedWindows,
  ALLOC_MASK_BUDGET_B,
  ALLOC_LEG_TOLERANCE,
  ladderPlan,
  allocArmSizing,
  allocMaxWrites,
  ALLOC_MIN_WRITES,
  ALLOC_ARM,
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
  has(/module\.exports = \{\s*ladderStructuralFailures,/, 'so this file can drive it');
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
    /module\.exports = \{\s*ladderStructuralFailures,\s*allocSteps,\s*allocMaskableWindows,\s*ALLOC_MASK_BUDGET_B,\s*ladderPlan,\s*allocArmSizing,\s*allocMaxWrites,\s*ALLOC_MIN_WRITES,\s*ALLOC_ARM,\s*\};/,
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

// ===========================================================================
// THE SMALL-WITNESS ARM — rf2-2rtt6.138
// ===========================================================================
//
// WHAT THIS PINS, AND WHY IT IS PINNED HERE. The allocation row's published
// witness is outside its own instrument's range: one warm write of 1,200
// boundaries allocates 1.2-1.7 MB against a ~600 KB fall threshold, and the
// masking bound above refuses a window that size rather than publishing what
// it reads. The repair is a smaller PAGE — the row's quantity is per
// BOUNDARY, so a few dozen of them put a many-write window under the
// threshold with the averaging a fit needs still in it.
//
// A SIZE IS A CLAIM AND HAS TO BE CHECKABLE. `allocArmSizing` is the
// arithmetic that chose the page, as a pure function of the bound, so the
// claim "this arm fits" is a thing this file can adjudicate on every PR
// rather than something the next opt-in run discovers. `--only alloc` needs
// a release build and a headless Chromium and is in no gate; an arm that
// drifted out of range would otherwise be found by a measurement, which is
// the expensive way to find it and the way rf2-2rtt6.76 already found it
// once.
//
// NOTHING HERE MEASURES ANYTHING, and the arm these tests describe HAS NEVER
// BEEN RUN. Every window below is a synthetic sample stream from `stream`
// above, built from the sizing's own prediction — which is exactly what
// makes the check hermetic and exactly what stops it being evidence about
// the substrate.

// The window a page of `boundaries` produces at `writes`, as `alloc-window!`
// would have sampled it if every leg allocated the predicted `B.c`. Built
// from the SIZING, so the two cannot disagree: if the prediction is wrong
// about the bound, `allocSteps` says so on the sizing's own numbers.
const predictedStream = (sizing) =>
  stream(Array.from({ length: sizing.writes }, () => sizing.predictedMaxStep));

test('THE BOUND IS THE SIZING — (W+1).B.c is what the arithmetic computes', () => {
  const s = allocArmSizing({ writes: 6, roots: 4, cells: 6, bytesPerBoundaryWrite: 1000 });
  assert.strictEqual(s.boundaries, 24, 'B is roots x cells');
  assert.strictEqual(s.predictedMaxStep, 24000, 'the largest single step is ONE write, B.c');
  assert.strictEqual(s.predictedRise, 144000, 'and rise is W of them');
  assert.strictEqual(s.predictedWindowB, 168000, '(W+1).B.c — the bound\'s left-hand side');
  assert.strictEqual(s.headroom, ALLOC_MASK_BUDGET_B - 168000);
  assert.ok(s.admissible);
});

test('THE SHIPPED ARM is a few dozen boundaries and is inside the bound', () => {
  // The numbers the driver ships with. A `P0_ALLOC_CELLS` / `P0_ALLOC_WRITES`
  // / `P0_ROOTS` in the environment moves them, and this failing under one is
  // a TRUE signal — the run's arm would not be the shipped arm.
  assert.strictEqual(ALLOC_ARM.cells, 6, 'cells per root');
  assert.strictEqual(ALLOC_ARM.roots, 4, 'P0_ROOTS');
  assert.strictEqual(ALLOC_ARM.boundaries, 24, 'a few dozen boundaries, as the bead asked');
  assert.strictEqual(ALLOC_ARM.writes, 6, 'and a MANY-write window, which is the averaging');
  assert.strictEqual(ALLOC_ARM.bytesPerBoundaryWrite, 1655, 'the top of the measured range');
  assert.strictEqual(ALLOC_ARM.predictedWindowB, 278040, '(6+1) x 24 x 1655');
  assert.strictEqual(ALLOC_ARM.headroom, 21960, 'under the 300000 B budget');
  assert.ok(ALLOC_ARM.admissible);
});

test('the shipped arm takes EVERY write the bound admits at its page', () => {
  // A window smaller than the bound allows is averaging thrown away, and the
  // whole reason the page shrank was to buy that averaging back.
  assert.strictEqual(ALLOC_ARM.writes, allocMaxWrites(ALLOC_ARM.boundaries));
});

test('THE PUBLISHED WITNESS is refused by the same arithmetic — the reason the arm exists', () => {
  const published = allocArmSizing({ writes: ALLOC_ARM.writes, roots: 4, cells: 300 });
  assert.strictEqual(published.boundaries, 1200);
  assert.strictEqual(published.predictedWindowB, 7 * 1200 * 1655);
  assert.ok(!published.admissible, '1,200 boundaries cannot be certified at any useful window');
  assert.ok(published.headroom < 0);
  assert.strictEqual(allocMaxWrites(1200), -1, 'not even a one-write window fits at that page');
});

test('the predicted window PASSES the real gate, and the published one does not', () => {
  // The sizing and `allocSteps` are two expressions of one bound, so they are
  // driven against each other rather than each asserted alone.
  const arm = allocSteps(predictedStream(ALLOC_ARM));
  assert.strictEqual(arm.rise, ALLOC_ARM.predictedRise);
  assert.strictEqual(arm.maxStep, ALLOC_ARM.predictedMaxStep);
  assert.strictEqual(arm.headroom, ALLOC_ARM.headroom);
  assert.strictEqual(arm.maskable, false, 'the arm the driver ships is inside the budget');
  assert.strictEqual(arm.falls, 0);

  const published = allocArmSizing({ writes: ALLOC_ARM.writes, roots: 4, cells: 300 });
  const big = allocSteps(predictedStream(published));
  assert.strictEqual(big.headroom, published.headroom);
  assert.ok(big.maskable, 'and the 1,200-boundary page is not');
});

test('a MIS-SIZED arm is refused — one boundary over the bound is over it', () => {
  // `maxBoundaries` is the largest page the window admits, so the page one
  // root wider than it must refuse. A sizing rule that named a limit it did
  // not enforce would have adjudicated nothing.
  const fits = allocArmSizing({ writes: 6, roots: 1, cells: ALLOC_ARM.maxBoundaries });
  assert.strictEqual(fits.boundaries, 25);
  assert.ok(fits.admissible, 'exactly at the limit is inside it');
  const over = allocArmSizing({ writes: 6, roots: 1, cells: ALLOC_ARM.maxBoundaries + 1 });
  assert.ok(!over.admissible, 'and one boundary more is not');
  assert.ok(allocSteps(predictedStream(over)).maskable, 'the real gate agrees');
});

test('`allocMaxWrites` inverts the BOUND exactly — one write more is over budget', () => {
  // Driven on `headroom`, which is the bound's own term, and not on
  // `admissible`, which is the whole preflight verdict: at 60 boundaries the
  // bound admits only 2 writes, and the averaging floor refuses that page
  // whatever the budget thinks of it (rf2-2rtt6.142, below).
  for (const boundaries of [4, 12, 24, 25, 60]) {
    const w = allocMaxWrites(boundaries);
    assert.ok(w >= 1, `${boundaries} boundaries must admit a window at all`);
    assert.ok(
      allocArmSizing({ writes: w, roots: 1, cells: boundaries }).headroom >= 0,
      `${boundaries} boundaries at ${w} writes must fit the budget`
    );
    assert.ok(
      allocArmSizing({ writes: w + 1, roots: 1, cells: boundaries }).headroom < 0,
      `${boundaries} boundaries at ${w + 1} writes must not`
    );
  }
});

test('a window with no work in it, and a page with no boundaries, are NOT admissible', () => {
  // Both sail under the budget on zero bytes, and neither has a per-boundary
  // quantity to publish. A bound that admitted them would be admitting
  // nothing measured at all.
  assert.ok(!allocArmSizing({ writes: 0, roots: 4, cells: 6 }).admissible);
  assert.ok(!allocArmSizing({ writes: 6, roots: 4, cells: 0 }).admissible);
  assert.ok(!allocArmSizing({ writes: 0, roots: 0, cells: 0 }).admissible);
});

test('the sizing cannot buy headroom by shrinking the WINDOW alone', () => {
  // The direction the bead ruled out: the 300-boundary one-write config whose
  // fall count was zero across 88 windows is still refused by the bound, so
  // it was never certified. Shrinking the unit is what works.
  const oneWrite = allocArmSizing({ writes: 1, roots: 1, cells: 300 });
  assert.ok(!oneWrite.admissible, 'B=300 at one write is still over budget');
  assert.ok(oneWrite.predictedWindowB > ALLOC_MASK_BUDGET_B);
  const smallUnit = allocArmSizing({ writes: 6, roots: 4, cells: 6 });
  assert.ok(smallUnit.admissible, 'and a smaller UNIT carries six writes of averaging');
});

// ===========================================================================
// THE AVERAGING FLOOR IS ENFORCED, NOT MERELY DERIVED FROM — rf2-2rtt6.142
// ===========================================================================
//
// THE DEFECT THIS PINS. `ALLOC_MIN_WRITES` sized the default page and then
// adjudicated nothing: the preflight's verdict was `writes >= 1 && boundaries
// >= 1 && headroom >= 0`, so every window from one write upwards was licensed
// however little averaging was left in it. Two routes reached that state
// through the shipped env surface, and the merged-PR audit of #7688 derived
// both without launching a browser:
//
//   P0_ROOTS=50          the DEFAULT derivation, which floors `cells` at 1 and
//                        lands on 50 boundaries — a page that admits 2 writes
//   P0_ALLOC_WRITES=1    the shipped 24-boundary page, window set by hand
//
// Both are far under the masking budget, which is precisely why the bound
// could not catch them: the budget is an upper limit on a window's SIZE and
// has nothing to say about how few writes are averaged inside it.
//
// WHY THE FLOOR EXISTS, so it is not weakened by accident. A one-write window
// has no averaging in it, and at one write per window all four ladder fits
// came back under the 0.98 r2 floor — 0.75 / 0.28 / 0.94 / 0.31. The row
// already assumes the averaging; this is the preflight being made to enforce
// what the row assumes.
//
// NOTHING HERE MEASURES ANYTHING. The two env routes are re-derived in a
// child `node` that requires the driver and prints `ALLOC_ARM` — the module
// constants are read at require time, so an env route can only be pinned from
// outside the process. No build, no browser, no page.

// The driver's own `ALLOC_ARM`, as a fresh process with `env` set derives it.
// This is the shipped derivation and not a re-statement of it, which is the
// whole point: the routes below are configuration, and configuration is read
// exactly once, at require.
function armUnderEnv(env) {
  const r = spawnSync(
    process.execPath,
    ['-e', 'process.stdout.write(JSON.stringify(require(process.argv[1]).ALLOC_ARM))', DRIVER],
    { env: { ...process.env, ...env }, encoding: 'utf8' }
  );
  assert.strictEqual(r.status, 0, `requiring the driver must not throw: ${r.stderr}`);
  return JSON.parse(r.stdout);
}

test('ROUTE 1 — the large-root DEFAULT that cannot fit six writes is refused', () => {
  // `P0_ROOTS=50` needs no other flag: the `ALLOC_CELLS` default floors at one
  // cell per root, so the page it derives is 50 boundaries and the window that
  // page admits is 2 — below the floor, and inside the budget, which is how it
  // reached a publication path at all.
  const arm = armUnderEnv({ P0_ROOTS: '50', P0_ALLOC_CELLS: '', P0_ALLOC_WRITES: '' });
  assert.strictEqual(arm.cells, 1, 'the default floors at one cell per root');
  assert.strictEqual(arm.boundaries, 50);
  assert.strictEqual(arm.writes, 2, 'and 50 boundaries admit only a two-write window');
  assert.ok(arm.headroom >= 0, 'the masking bound is content — it never saw this');
  assert.ok(!arm.admissible, 'the averaging floor is what refuses it');
  assert.ok(
    arm.refusals.some((r) => r.includes('averaging floor')),
    `the refusal must name the floor: ${JSON.stringify(arm.refusals)}`
  );
});

test('ROUTE 2 — an explicit one-write window on the shipped page is refused', () => {
  const arm = armUnderEnv({ P0_ALLOC_WRITES: '1' });
  assert.strictEqual(arm.boundaries, 24, 'the shipped page, unchanged');
  assert.strictEqual(arm.writes, 1);
  assert.ok(arm.headroom >= 0, 'and far under the budget, which is why it got through');
  assert.ok(!arm.admissible);
  const floor = arm.refusals.find((r) => r.includes('averaging floor'));
  assert.ok(floor, JSON.stringify(arm.refusals));
  // The page here is fine — it carries six writes — so the WINDOW is the knob
  // and the refusal must say so rather than send the operator at the page.
  assert.match(floor, /RAISE P0_ALLOC_WRITES to at least 6/);
  assert.doesNotMatch(floor, /SHRINK THE PAGE/);
});

test('THE CONTROL — the shipped six-write arm is still admitted, so this is not vacuous', () => {
  // A gate that refused everything would pass the two cases above and have
  // adjudicated nothing. The arm the driver actually ships must go through.
  const arm = armUnderEnv({});
  assert.strictEqual(arm.boundaries, 24);
  assert.strictEqual(arm.writes, ALLOC_MIN_WRITES, 'exactly at the floor');
  assert.deepStrictEqual(arm.refusals, []);
  assert.ok(arm.admissible);
});

test('the refusal names SHRINKING THE PAGE and never shrinking the window', () => {
  // The one direction that must not be advised. A below-floor arm whose page
  // admits fewer than six writes has no window repair at all: telling the
  // operator "this page admits at most 2 writes" is telling them to configure
  // the very shape being refused.
  const arm = armUnderEnv({ P0_ROOTS: '50', P0_ALLOC_CELLS: '', P0_ALLOC_WRITES: '' });
  const floor = arm.refusals.find((r) => r.includes('averaging floor'));
  assert.match(floor, /SHRINK THE PAGE/);
  assert.match(floor, /P0_ROOTS \/ P0_ALLOC_CELLS/);
  assert.match(floor, /at most 25 boundaries/, 'the largest page that carries the floor');
  assert.doesNotMatch(
    floor,
    /P0_ALLOC_WRITES/,
    'a page that cannot carry the floor must not have its window named at all'
  );
});

test('the floor follows ALLOC_MIN_WRITES rather than a number typed beside it', () => {
  // If a later ruling moves the averaging floor, the preflight moves with it.
  assert.ok(ALLOC_MIN_WRITES >= 1, 'a floor below one write would not be a floor');
  const page = { roots: 1, cells: 4 }; // 4 boundaries: admits 44 writes, budget-wise
  for (let w = 0; w < ALLOC_MIN_WRITES; w++) {
    const s = allocArmSizing({ writes: w, ...page });
    assert.ok(s.headroom >= 0, `w=${w} is inside the budget`);
    assert.ok(!s.admissible, `w=${w} is below the floor and must refuse`);
  }
  const atFloor = allocArmSizing({ writes: ALLOC_MIN_WRITES, ...page });
  assert.ok(atFloor.admissible, 'and exactly at the floor is admitted');
});

test('THE CHANGE ONLY EVER REFUSES MORE — admissible is a subset of the old bound', () => {
  // The old predicate, verbatim: `writes >= 1 && boundaries >= 1 && headroom
  // >= 0`. Enforcing the floor may only turn an admitted shape into a refused
  // one, never the reverse, and that is checked exhaustively over the grid
  // rather than argued.
  let admitted = 0;
  let newlyRefused = 0;
  for (let writes = 0; writes <= 40; writes++) {
    for (let cells = 0; cells <= 40; cells++) {
      for (const roots of [0, 1, 4, 50]) {
        const s = allocArmSizing({ writes, roots, cells });
        const wasAdmissible = writes >= 1 && s.boundaries >= 1 && s.headroom >= 0;
        if (s.admissible) {
          admitted++;
          assert.ok(wasAdmissible, `newly admissible at W=${writes} B=${s.boundaries}`);
        } else if (wasAdmissible) {
          newlyRefused++;
          assert.ok(writes < ALLOC_MIN_WRITES, 'and only ever for want of averaging');
        }
      }
    }
  }
  assert.ok(admitted > 0, 'the sweep must still admit something');
  assert.ok(newlyRefused > 0, 'and must have refused something, or it changed nothing');
});

test('a refused arm says WHY, one reason per thing wrong with it', () => {
  // Both wrong at once: 300 boundaries at one write is over the budget AND
  // under the floor, and an operator who fixed only the one they were told
  // about would come back to the other.
  const both = allocArmSizing({ writes: 1, roots: 1, cells: 300 });
  assert.strictEqual(both.refusals.length, 2, JSON.stringify(both.refusals));
  assert.ok(both.refusals.some((r) => r.includes('averaging floor')));
  assert.ok(both.refusals.some((r) => r.includes('masking budget')));
  // A page with nothing on it has no per-boundary quantity, and none of the
  // other terms mean anything: one reason, and it is that one.
  const empty = allocArmSizing({ writes: 6, roots: 4, cells: 0 });
  assert.strictEqual(empty.refusals.length, 1);
  assert.match(empty.refusals[0], /no per-boundary quantity/);
});

test('`floorBoundaries` is the largest page that carries the averaging floor', () => {
  const s = allocArmSizing({ writes: 6, roots: 4, cells: 6 });
  assert.strictEqual(s.floorBoundaries, 25, '300000 / ((6+1) x 1655)');
  assert.ok(
    allocMaxWrites(s.floorBoundaries) >= ALLOC_MIN_WRITES,
    'a page that size admits the floor'
  );
  assert.ok(
    allocMaxWrites(s.floorBoundaries + 1) < ALLOC_MIN_WRITES,
    'and one boundary more does not'
  );
  assert.ok(ALLOC_ARM.boundaries <= s.floorBoundaries, 'the shipped page is inside it');
});

// --- the plan the page is mounted from -------------------------------------

test('`ladderPlan` states the page on every arm, floor included', () => {
  const plan = ladderPlan({ list: 300, grid: 6 }, 4);
  assert.strictEqual(plan.length, 2, 'one per segment');
  for (const { arms } of plan) {
    assert.strictEqual(arms.length, 11, 'a floor plus 5 rungs x 2 substrates');
    for (const a of arms) {
      assert.strictEqual(a.opts.cells, 6, `${a.key} must state the page it mounts`);
      assert.strictEqual(a.boundaries, 24, `${a.key} must agree with roots x cells`);
    }
  }
  const floor = plan[0].arms[0];
  assert.strictEqual(floor.arm, 'grid/floor');
  assert.strictEqual(floor.opts.cells, 6, 'the calibrator is read on the SAME page as the arms');
});

test('the plan the small arm mounts is the ladder plan, at a smaller page', () => {
  // Same arms, same rungs, same keys rule — Q = E on every rung. A second
  // plan shape would make the small witness a second instrument, which is the
  // one thing this arm may not be.
  const small = ladderPlan({ list: 300, grid: 6 }, 4);
  const published = ladderPlan({ list: 300, grid: 300 }, 4);
  assert.deepStrictEqual(
    small.map((s) => s.arms.map((a) => a.key)),
    published.map((s) => s.arms.map((a) => a.key)),
    'the same arms under the same keys'
  );
  for (const [i, seg] of small.entries()) {
    for (const [j, a] of seg.arms.entries()) {
      const p = published[i].arms[j];
      assert.strictEqual(a.rung, p.rung);
      assert.strictEqual(a.reads, p.reads);
      if (a.rung === 'floor') {
        // The calibrator reads nothing and holds no key on either page.
        assert.strictEqual(a.keys, undefined);
        assert.strictEqual(p.keys, undefined);
        continue;
      }
      // Q = E at every rung on both pages: keys is B x R and nothing else.
      assert.strictEqual(a.keys, a.boundaries * a.reads);
      assert.strictEqual(p.keys, p.boundaries * p.reads);
    }
  }
});

test('the RETENTION ladder is not moved by any of this', () => {
  // It reads at the published 1,200 and its instrument has no such ceiling.
  const published = ladderPlan({ list: 300, grid: 300 }, 4);
  for (const { arms } of published) {
    for (const a of arms) {
      assert.strictEqual(a.boundaries, 1200);
      assert.strictEqual(a.opts.cells, 300);
    }
  }
  assert.strictEqual(published[0].arms.find((a) => a.rung === 'R20').keys, 24000);
});

// --- the wiring, so this pin cannot drift off the thing that refuses -------

test('the driver REFUSES a mis-sized arm before it launches a browser', () => {
  has(/if \(!ALLOC_ARM\.admissible\) \{/, 'the sizing refusal is an exit, not a warning');
  has(
    /the allocation arm is refused by its own preflight before anything is measured/,
    'and it says so before a byte is measured'
  );
  has(/SHRINK THE MEASURED UNIT, DO NOT WIDEN THE BUDGET/, 'naming the repair, not the dial');
  has(
    /ALLOC_ARM\.refusals/,
    'and the exit prints the reasons rather than one undifferentiated verdict'
  );
});

test('the averaging floor is ENFORCED in the sizing, not merely derived from', () => {
  // rf2-2rtt6.142. `ALLOC_MIN_WRITES` sized the default page and adjudicated
  // nothing; the verdict admitted any window from one write up.
  has(/if \(writes < ALLOC_MIN_WRITES\) \{/, 'the floor is a refusal in the sizing itself');
  has(/admissible: refusals\.length === 0,/, 'and the verdict is the reasons, not a conjunction');
  lacks(
    /admissible: writes >= 1 && boundaries >= 1 && headroom >= 0,/,
    'the old verdict licensed exactly the low-averaging shape the row rules out'
  );
});

test('the window is DERIVED from the bound and the arm from the measured cost', () => {
  has(
    /const ALLOC_WRITES = Number\(\s*process\.env\.P0_ALLOC_WRITES \|\| allocMaxWrites\(ROOTS \* ALLOC_CELLS\)\s*\);/,
    'the window must be what the page admits, not a number typed beside it'
  );
  has(/const ALLOC_B_PER_BOUNDARY_WRITE = 1655;/, 'the top of the MEASURED range sizes the arm');
  has(/const ALLOC_MIN_WRITES = 6;/, 'and a window is sized to hold averaging');
  // The bound itself is still the untouched one, with no dial of its own.
  has(/const ALLOC_MASK_BUDGET_B = ALLOC_FALL_THRESHOLD_B \/ 2;/, 'unchanged by this arm');
  has(/const ALLOC_FALL_THRESHOLD_B = 600000;/, 'and so is the measured threshold under it');
});

// ===========================================================================
// VALIDITY WITNESS V4 — THE PINNED PROBES (rf2-2rtt6.140, criterion 3)
// ===========================================================================
//
// The merged-PR audit of #7682 wrote two executable probes and the old bound
// ADMITTED both, at `headroom = 0`, with true allocations of 300 KB and
// 600 KB. That is what retired the bound: it was not too loose, its premises
// did not support it. `ALLOC_FALL_THRESHOLD_B` is an UPPER bound on where the
// first collection runs where safety needs a LOWER one, and a masked leg's
// true allocation is not bounded by the observed `maxStep`, which sees only
// NET positive deltas.
//
// THE REPLACEMENT IS AN OBSERVATION, NOT ANOTHER MODEL. The legs of a window
// are W repetitions of ONE work unit, so absent a collection they should be
// alike. A leg materially BELOW its cohort is a leg something removed bytes
// from, and nothing in the work unit removes bytes — the collector does. A
// leg ABOVE its cohort is not evidence of a collection but is evidence that
// the one-work-unit premise has failed in this window, so refusing is correct
// rather than merely conservative.
//
// HERMETIC, exactly as the pins above: every window here is a synthetic
// sample stream from `stream`, and NOTHING IN THIS FILE HAS EVER BEEN RUN
// AGAINST A REAL PAGE.

// The two audit probes, as the fixtures `stream` builds them. Each has its
// offending leg at EXACTLY ZERO against a strictly positive cohort median,
// which is what makes the refusal independent of the calibration.
const PROBE_A = () => stream([60000, 60000, 60000, 60000, 60000], [0, 0, 0, 0, 60000]);
const PROBE_B = () =>
  stream([50000, 50000, 50000, 50000, 50000, 350000], [0, 0, 0, 0, 0, 350000]);

test('V4 PROBE A — 300 KB of true allocation, admitted by the retired bound, is REFUSED', () => {
  const s = allocSteps(PROBE_A());
  // The old admission, reproduced AS FACT rather than described. These three
  // are what the retired bound saw, and it let the window through on them.
  assert.strictEqual(s.falls, 0, 'the sign test saw nothing — the fifth leg netted to zero');
  assert.strictEqual(s.rise, 240000, 'and `rise` under-reads the true 300000 by the whole leg');
  assert.strictEqual(s.maxStep, 60000);
  // The new verdict, which names the leg.
  assert.strictEqual(s.certified, false, 'the leg witness must refuse what the bound admitted');
  assert.strictEqual(s.legMedian, 60000, 'four legs at 60000 and one at 0');
  assert.deepStrictEqual(s.legs, [60000, 60000, 60000, 60000, 0]);
  assert.strictEqual(s.refusals.length, 1, JSON.stringify(s.refusals));
  assert.match(s.refusals[0], /leg 5 of 5/, 'the refusal must NAME the leg');
  assert.match(s.refusals[0], /0 B against a cohort median of 60000 B/);
});

test('V4 PROBE B — 600 KB of true allocation, admitted by the retired bound, is REFUSED', () => {
  const s = allocSteps(PROBE_B());
  assert.strictEqual(s.falls, 0);
  assert.strictEqual(s.rise, 250000, 'the sixth leg allocated 350000 and lost all of it');
  assert.strictEqual(s.maxStep, 50000);
  assert.strictEqual(s.certified, false);
  assert.strictEqual(s.legMedian, 50000);
  assert.deepStrictEqual(s.legs, [50000, 50000, 50000, 50000, 50000, 0]);
  assert.strictEqual(s.refusals.length, 1, JSON.stringify(s.refusals));
  assert.match(s.refusals[0], /leg 6 of 6/);
});

test('V4 — the net-growth masking case is refused on the LEG grounds', () => {
  // The fixture this file has carried since rf2-n6w7o, re-pointed. It used to
  // refuse for being over the budget; it now refuses because its third leg
  // reads zero against a cohort of 200 KB, which is the observation rather
  // than the arithmetic.
  const s = allocSteps(stream([200000, 200000, 200000, 200000], [0, 0, 200000, 0]));
  assert.strictEqual(s.falls, 0, 'the sign test is blind here — that IS the defect');
  assert.strictEqual(s.rise, 600000, 'rise under-reads the true 800000 by the reclaimed 200000');
  assert.strictEqual(s.certified, false);
  assert.deepStrictEqual(s.legs, [200000, 200000, 0, 200000]);
  assert.strictEqual(s.legMedian, 200000);
  assert.match(s.refusals[0], /leg 3 of 4/);
});

test('V4 τ-INDEPENDENCE — both probes are refused for EVERY tolerance below 1', () => {
  // The property that stops a later re-calibration silently re-admitting
  // them. In both probes the offending leg reads exactly zero against a
  // strictly positive median, so |0 − m| = m > τ·m for every τ < 1 — the
  // refusal is a fact about the fixtures, not about the constant.
  const taus = [0, 0.001, 0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999];
  for (const tau of taus) {
    for (const [name, probe] of [['A', PROBE_A], ['B', PROBE_B]]) {
      const s = allocSteps(probe(), tau);
      assert.strictEqual(s.certified, false, `probe ${name} must refuse at τ=${tau}`);
    }
    // And the sweep must not be vacuous: a clean cohort certifies at every
    // one of the same tolerances, so what refuses the probes is the leg that
    // deviates and not a gate that refuses everything.
    assert.strictEqual(
      allocSteps(stream([20000, 20000, 20000, 20000]), tau).certified,
      true,
      `a clean window must still certify at τ=${tau}`
    );
  }
});

test('V4 NOT VACUOUS — the clean small window and the idle window both certify', () => {
  const clean = allocSteps(stream([20000, 20000, 20000, 20000]));
  assert.strictEqual(clean.certified, true, 'four alike legs are one work unit repeated');
  assert.deepStrictEqual(clean.refusals, []);
  assert.strictEqual(clean.legMedian, 20000);
  assert.strictEqual(clean.legWorstDeviation, 0);

  // An idle window is homogeneous AT ZERO. `τ·0` is 0 and every leg deviates
  // from the median by 0, which is not MORE than 0 — so it certifies, and it
  // has to, because the idle control is one of the three the row takes.
  const idle = allocSteps(stream([0, 0, 0]));
  assert.strictEqual(idle.certified, true);
  assert.strictEqual(idle.legMedian, 0);
  assert.deepStrictEqual(idle.legs, [0, 0, 0]);
  // A relative deviation from a zero median is not a number, and the field
  // says so rather than reporting a fabricated 0 or an Infinity.
  assert.strictEqual(idle.legWorstDeviation, null);
});

test('THE MANDATORY PAGE — an unstated P0_ALLOC_CELLS is refused BY NAME', () => {
  // rf2-2rtt6.139 retired `ALLOC_B_PER_BOUNDARY_WRITE` as a sizing input and
  // forbade substituting a replacement constant. With no sizing model there
  // is no honest default page, so the page becomes MANDATORY — which also
  // makes an accidental publication run impossible while criterion 5's
  // measurement freeze is in force.
  const arm = armUnderEnv({ P0_ALLOC_CELLS: '', P0_ALLOC_WRITES: '' });
  assert.strictEqual(arm.admissible, false, 'an unstated page cannot be derived');
  const page = arm.refusals.find((r) => r.includes('P0_ALLOC_CELLS'));
  assert.ok(page, JSON.stringify(arm.refusals));
  assert.match(page, /rf2-2rtt6\.139/, 'and it names what would make a default honest');
  // A stated page derives an admissible arm, so the refusal is the missing
  // page and not a preflight that refuses everything.
  const stated = armUnderEnv({ P0_ALLOC_CELLS: '6', P0_ALLOC_WRITES: '' });
  assert.strictEqual(stated.cells, 6);
  assert.strictEqual(stated.boundaries, 24);
  assert.deepStrictEqual(stated.refusals, []);
  assert.ok(stated.admissible);
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
