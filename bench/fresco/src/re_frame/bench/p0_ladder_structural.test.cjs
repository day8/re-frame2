#!/usr/bin/env node
'use strict';
// THE P0 DRIVER'S PURE GATES, driven with contrived rows — the ladder's
// structural witness and the allocation row's window, preflight, schedule and
// provenance functions. `--only ladder` and the allocation row need a release
// build and a headless Chromium and run in no gate, so an expectation only the
// driver holds would sit stale; every function here is a pure function of the
// collected row or of the configuration, so it is pinned on every check.
//
//     node src/re_frame/bench/p0_ladder_structural.test.cjs   (from bench/fresco/)
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DRIVER = path.join(__dirname, 'p0_run.cjs');
// The P0 fixture lives in the package, where the per-PR `:node-test` build reads it.
const CORE_BENCH = path.resolve(__dirname, '../../../../../implementation/core/test/re_frame/bench');
const {
  ladderStructuralFailures,
  allocSteps,
  allocRefusedWindows,
  allocRefusedWindowCount,
  ladderPlan,
  allocArmSizing,
  ALLOC_MIN_WRITES,
  allocPrimeSplit,
  ALLOC_PRIME_WRITES,
  ALLOC_WINDOW_WRITES,
  ALLOC_WRITE_SPECS,
  allocWindowKey,
  allocWriteProvenance,
  ALLOC_PLAN_SHAPES,
  allocPlanArms,
  allocSegmentOrder,
  allocPassFlips,
  allocPassOrder,
  boxBusyFraction,
  boxSessionOpen,
  boxSessionClose,
  boxRecord,
  allocRoundWindowKinds,
  ALLOC_LEG_TOLERANCE,
  summariseAlloc,
  allocSiteSplit,
  allocSiteWitness,
  allocWindowVerdict,
  ALLOC_SITE_NAMES,
} = require('./p0_run.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

const pick = (o, ...keys) => Object.fromEntries(keys.map((k) => [k, o[k]]));
// The leading `leg K of N` of each refusal reason.
const legsNamed = (refusals) => refusals.map((r) => r.match(/^leg \d+ of \d+/)[0]);

const SRC = fs.readFileSync(DRIVER, 'utf8');
const has = (re, why) => assert.ok(re.test(SRC), `p0_run.cjs: expected ${re} — ${why}`);
const lacks = (re, why) => assert.ok(!re.test(SRC), `p0_run.cjs: must not match ${re} — ${why}`);
const countIn = (re) => (SRC.match(re) || []).length;

// ===========================================================================
// THE LADDER'S STRUCTURAL WITNESS
// ===========================================================================
//
// `ladderStructuralFailures` wants `boundaries === B` on every reading rung
// and 0 at R=0: the sub-index is fused into the cell table, so a boundary
// that reads nothing retains no membership anywhere. A non-zero reading at
// R=0 means something is retaining a boundary that reads nothing.

const B = 1200; // ROOTS(4) x perRoot.grid(300), the ladder's fixed boundary count
const RUNGS = [0, 1, 3, 7, 20];
const SEGMENTS = ['reagent-subs', 'uix-subs'];
const DONOR = { reagent: 'reagent-subs', uix: 'uix-subs' };
const ZEROS = () => ({ cells: 0, cellRefs: 0, boundaries: 0, edges: 0, entries: 0 });

// What a HEALTHY candidate arm answers on rung R.
const candidateStamp = (R) => ({
  cells: B * R,
  cellRefs: B * R,
  boundaries: R === 0 ? 0 : B,
  edges: B * R,
  entries: R === 0 ? 1 : B,
});

function armsOfRound(mutate) {
  const arms = {};
  for (const segment of SEGMENTS) {
    arms[`${segment}|grid/floor`] = { arm: 'grid/floor', structural: ZEROS() };
    for (const [donor, seg] of Object.entries(DONOR)) {
      if (seg !== segment) continue;
      for (const R of RUNGS) {
        arms[`${segment}|lad/${donor}#R${R}`] = {
          arm: `lad/${donor}`,
          reads: R,
          verify: { fresco: ZEROS() },
          structural: ZEROS(),
        };
      }
    }
    for (const R of RUNGS) {
      arms[`${segment}|lad/fresco#R${R}`] = {
        arm: 'lad/fresco',
        reads: R,
        verify: { fresco: candidateStamp(R) },
        structural: ZEROS(),
      };
    }
  }
  if (mutate) mutate(arms);
  return arms;
}

const rowWith = (mutate) => ({
  plan: SEGMENTS.map((segment) => ({ segment, arms: [{ boundaries: B }] })),
  perRound: [1, 2].map((round) => ({ round, arms: armsOfRound(mutate) })),
});

test('a healthy fused run answers every expected count', () => {
  assert.deepStrictEqual(ladderStructuralFailures(rowWith(null)), []);
});

test('each wrong count fails its arm on every round, naming the field, the reading and the expectation', () => {
  const bothRounds = (msg) => [1, 2].map((r) => `round ${r} ${msg}`);
  for (const [why, mutate, expected] of [
    [
      'a per-mount stamp at R=0, on both segments',
      (arms) => {
        for (const s of SEGMENTS) arms[`${s}|lad/fresco#R0`].verify.fresco.boundaries = B;
      },
      [1, 2].flatMap((r) =>
        SEGMENTS.map((s) => `round ${r} ${s}|lad/fresco#R0: fresco boundaries 1200, expected 0`)
      ),
    ],
    [
      'any non-zero reading at R=0',
      (arms) => (arms['reagent-subs|lad/fresco#R0'].verify.fresco.boundaries = 1),
      bothRounds('reagent-subs|lad/fresco#R0: fresco boundaries 1, expected 0'),
    ],
    [
      'a reading rung reporting none',
      (arms) => (arms['uix-subs|lad/fresco#R1'].verify.fresco.boundaries = 0),
      bothRounds('uix-subs|lad/fresco#R1: fresco boundaries 0, expected 1200'),
    ],
    [
      'a reading rung short by one',
      (arms) => (arms['uix-subs|lad/fresco#R3'].verify.fresco.boundaries = B - 1),
      bothRounds('uix-subs|lad/fresco#R3: fresco boundaries 1199, expected 1200'),
    ],
    [
      'R=0 exempts edges from nothing',
      (arms) => (arms['reagent-subs|lad/fresco#R0'].verify.fresco.edges = 1),
      bothRounds('reagent-subs|lad/fresco#R0: fresco edges 1, expected 0'),
    ],
    [
      'R=0 still wants its one entry',
      (arms) => (arms['reagent-subs|lad/fresco#R0'].verify.fresco.entries = 0),
      bothRounds('reagent-subs|lad/fresco#R0: fresco entries 0, expected 1'),
    ],
    [
      'a donor arm holding a Fresco boundary',
      (arms) => (arms['reagent-subs|lad/reagent#R7'].verify.fresco.boundaries = 1),
      bothRounds('reagent-subs|lad/reagent#R7: fresco boundaries 1, expected 0'),
    ],
    [
      'residue after teardown',
      (arms) => (arms['uix-subs|lad/fresco#R0'].structural.boundaries = 7),
      bothRounds('uix-subs|lad/fresco#R0: residue after teardown — fresco boundaries 7, expected 0'),
    ],
  ]) {
    assert.deepStrictEqual(ladderStructuralFailures(rowWith(mutate)), expected, why);
  }
});

// ===========================================================================
// WHICH SUBSTRATE THE CANDIDATE ARM IS
// ===========================================================================
//
// The heap ladder prices the PACKAGE, not `re-frame.bench.fresco.arm1.*`, the
// frozen prototype. `:fresco-bench` compiles both trees, so an arm re-pointed
// at the prototype builds green and reads plausibly; only the ns form says
// which one it compiled against.

// The file's `(ns …)` form with comments and strings removed: both files name
// the prototype in prose, which is not a require.
const nsForm = (file) => {
  const src = fs.readFileSync(file, 'utf8');
  let code = '';
  for (let i = 0, inString = false; i < src.length; i += 1) {
    const c = src[i];
    if (inString) {
      if (c === '\\') i += 1;
      else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === ';') while (i + 1 < src.length && src[i + 1] !== '\n') i += 1;
    else code += c;
  }
  const start = code.indexOf('(ns ');
  for (let i = start, depth = 0; start !== -1 && i < code.length; i += 1) {
    if (code[i] === '(') depth += 1;
    else if (code[i] === ')' && --depth === 0) return code.slice(start, i + 1);
  }
  throw new Error(`${path.basename(file)}: no balanced ns form`);
};

test('the candidate arm and the heap rig require the package, never the arm1 prototype', () => {
  for (const file of ['p0_fresco.cljs', 'p0_heap.cljs']) {
    const ns = nsForm(path.join(__dirname, file));
    assert.match(ns, /re-frame\.fresco[ .\]]/, `${file} requires the package`);
    assert.doesNotMatch(ns, /re-frame\.bench\.fresco\.arm1/, `${file} must not require the prototype`);
  }
});

// ===========================================================================
// THE ALLOCATION WINDOW'S CERTIFICATE
// ===========================================================================
//
// `allocSteps` counts a collection by the SIGN of a step, which is blind where
// a leg allocates at least what a collection inside it reclaimed: the step is
// >= 0 and the reclaimed bytes vanish from `rise`, so the window reads LOW. The
// legs of a window are W repetitions of ONE work unit, so the window is
// REFUSED if any leg deviates from the cohort median by more than τ·m.
//
// Hermetic: `stream` builds the sample buffer `p0-heap/alloc-window!` fills,
// `[s0, pre0, post0, pre1, post1, ...]`, from each leg's TRUE allocation and
// what a collection reclaimed inside it.
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

const SMALL = stream([20000, 20000, 20000, 20000]);
const MASKED = stream([200000, 200000, 200000, 200000], [0, 0, 200000, 0]);

test('THE DEFECT — a collection fully masked by net growth is REFUSED', () => {
  const s = allocSteps(MASKED);
  assert.deepStrictEqual(pick(s, 'falls', 'fall', 'rise', 'legs', 'legMedian', 'legWorstDeviation'), {
    falls: 0, // the sign test is blind here — that IS the defect
    fall: 0,
    rise: 600000, // the true 800000 less the reclaimed 200000
    legs: [200000, 200000, 0, 200000],
    legMedian: 200000,
    legWorstDeviation: -1,
  });
  assert.deepStrictEqual(legsNamed(s.refusals), ['leg 3 of 4']);
});

test('the retired masking budget is GONE, not widened', () => {
  lacks(/const ALLOC_MASK_BUDGET_B/, 'there is no budget constant');
});

test('the gate is not vacuous — a small clean window passes it', () => {
  assert.deepStrictEqual(
    pick(allocSteps(SMALL), 'falls', 'rise', 'maxStep', 'certified', 'refusals', 'legMedian', 'legWorstDeviation'),
    { falls: 0, rise: 80000, maxStep: 20000, certified: true, refusals: [], legMedian: 20000, legWorstDeviation: 0 }
  );
});

test('the boundary is exact — at τ passes, one byte past refuses', () => {
  // τ is the declared 0.25 placeholder: ±5000 B around a 20000 B cohort, both sides.
  const refusedLegs = (first) => legsNamed(allocSteps(stream([first, 20000, 20000, 20000, 20000])).refusals);
  assert.deepStrictEqual([25000, 25001, 15000, 14999].map(refusedLegs), [
    [],
    ['leg 1 of 5'],
    [],
    ['leg 1 of 5'],
  ]);
});

test('THE FALLS GATE IS UNTOUCHED — a visible collection still counts as one', () => {
  // A fall is EXCLUDED from the rising sum, never netted.
  assert.deepStrictEqual(pick(allocSteps(stream([20000, 20000], [0, 40000])), 'falls', 'fall', 'rise', 'endpoints'), {
    falls: 1,
    fall: 20000,
    rise: 20000,
    endpoints: 0,
  });
});

test('`maxStep` is the largest single rising step, not the mean or the last', () => {
  assert.deepStrictEqual(pick(allocSteps(stream([1000, 7000, 3000])), 'rise', 'maxStep', 'endpoints'), {
    rise: 11000,
    maxStep: 7000,
    endpoints: 11000,
  });
});

test('an idle window is homogeneous at zero, and certifies', () => {
  // The idle control is one of the three windows every round takes. A relative
  // deviation from a zero median is not a number, so it reads null.
  assert.deepStrictEqual(pick(allocSteps(stream([0, 0, 0])), 'certified', 'legs', 'legMedian', 'legWorstDeviation'), {
    certified: true,
    legs: [0, 0, 0],
    legMedian: 0,
    legWorstDeviation: null,
  });
  // Not a free pass: legs that disagree are refused at a zero median too.
  assert.strictEqual(allocSteps(stream([0, 100, 0])).certified, false);
});

// --- the prime work unit ----------------------------------------------------
//
// Arm windows carry a ~7 KB first-leg excess the driver's own `gc()` creates,
// so the window drives one extra work unit — the PRIME — which is reported and
// excluded from every published figure and from the certificate.

test('THE SPLIT — the measured region is a well-formed stream in the same shape', () => {
  // The last prime leg's `post` stands as the measured region's `s0`: nothing
  // is fabricated, so `allocSteps` needs no knowledge of the prime.
  const raw = stream([9000, 1000, 1000, 1000]);
  assert.deepStrictEqual(allocPrimeSplit(raw, 1), { primeLegs: [9000], measured: raw.slice(2) });
});

// ===========================================================================
// THE BY-SITE INSTRUMENT
// ===========================================================================
//
// A leg is ONE step, so "which part of the work unit allocates" is not
// recoverable from it. The by-site mode samples once more at the unit's one
// seam, `[s0, pre0, mid0, post0, ...]`, splitting each leg into dispatch and
// drain without moving anything the row publishes.

// `[dispatch, drain]` pairs to the stride-3 buffer, on `stream`'s base heap.
function siteStream(pairs) {
  let h = 10000000;
  const out = [h];
  for (const [d, f] of pairs) {
    out.push(h);
    h += d;
    out.push(h);
    h += f;
    out.push(h);
  }
  return out;
}

// The observed B = 4 floor window — six alike legs of 19,256 B behind a
// 26,044 B prime — with ONE measured leg carrying the median recurring excess
// (+2,640 B), each term placed at a stated site.
const siteWindow = ({ primeAt, excessAt, excess = 2640, primeExcess = 6788 }) => {
  const at = (site, extra) => [1200 + (site === 'dispatch' ? extra : 0), 18056 + (site === 'drain' ? extra : 0)];
  return siteStream([
    at(primeAt, primeExcess),
    at(null, 0),
    at(null, 0),
    at(excessAt, excess),
    at(null, 0),
    at(null, 0),
    at(null, 0),
  ]);
};
const witnessOf = (spec) => allocSiteWitness(allocSiteSplit(siteWindow(spec), 3).siteLegs, 1);

test('THE COLLAPSE IS THE IDENTITY AT THE SHIPPED STRIDE — every gate above stands unedited', () => {
  const s = stream([19256, 19256, 19256]);
  assert.deepStrictEqual(allocSiteSplit(s, 2), { sites: 2, collapsed: s, siteLegs: [] });
});

test('THE COLLAPSED STREAM IS THE ONE THE SHIPPED WINDOW WOULD HAVE FILLED', () => {
  // Dropping the mid samples yields, sample for sample, the stride-2 stream of
  // the same legs; and a leg is its two sites exactly.
  const leg = (dispatch, drain) => ({ dispatch, drain, leg: dispatch + drain });
  const plain = leg(1200, 18056);
  assert.deepStrictEqual(allocSiteSplit(siteWindow({ primeAt: 'dispatch', excessAt: 'drain' }), 3), {
    sites: 3,
    collapsed: stream([26044, 19256, 19256, 21896, 19256, 19256, 19256]),
    siteLegs: [leg(7988, 18056), plain, plain, leg(1200, 20696), plain, plain, plain],
  });
});

test('THE ATTRIBUTION NAMES THE SITE — in both directions, so a swap cannot pass', () => {
  for (const site of ALLOC_SITE_NAMES) {
    const other = ALLOC_SITE_NAMES.find((s) => s !== site);
    const w = witnessOf({ primeAt: 'dispatch', excessAt: site });
    assert.deepStrictEqual(
      { dominant: w.dominant, deviant: w.legs[2] },
      { dominant: site, deviant: { leg: 3, deviation: 2640, by: { [site]: 2640, [other]: 0 }, site } }
    );
  }
});

test('THE PRIME IS OUT OF THE BY-SITE COHORT TOO, and its excess is placed', () => {
  // A cohort including the prime would carry its term back inside every median.
  const w = witnessOf({ primeAt: 'dispatch', excessAt: 'drain' });
  assert.deepStrictEqual(pick(w, 'primeSites', 'medians', 'primeExcessBySite', 'primeSite'), {
    primeSites: [{ dispatch: 7988, drain: 18056, leg: 26044 }],
    medians: { leg: 19256, dispatch: 1200, drain: 18056 },
    primeExcessBySite: { dispatch: 6788, drain: 0 },
    primeSite: 'dispatch',
  });
  assert.strictEqual(w.legs.length, 6, 'the prime is not one of the six measured legs');
});

test('THE SAME-TERM HYPOTHESIS IS ANSWERABLE, and answers three ways', () => {
  // Sites disagreeing settles it (one term cannot be in two places); agreeing
  // narrows it; byte-identical legs have no excess to place, so no verdict.
  const flat = allocSiteWitness(
    allocSiteSplit(siteStream([[7988, 18056], ...Array.from({ length: 6 }, () => [1200, 18056])]), 3).siteLegs,
    1
  );
  assert.deepStrictEqual(
    [witnessOf({ primeAt: 'dispatch', excessAt: 'drain' }), witnessOf({ primeAt: 'drain', excessAt: 'drain' }), flat].map(
      (w) => pick(w, 'dominant', 'primeSite', 'sameSite')
    ),
    [
      { dominant: 'drain', primeSite: 'dispatch', sameSite: false },
      { dominant: 'drain', primeSite: 'drain', sameSite: true },
      { dominant: null, primeSite: 'dispatch', sameSite: null },
    ]
  );
});

// ===========================================================================
// THE INTRA-LEG RECLAMATION GATE
// ===========================================================================
//
// Of 72 by-site floor-arm windows, three carried a reclamation INSIDE one leg
// with `falls` = 0, and two of those CERTIFIED at τ: the falls gate walks the
// collapsed stream, where such a leg is one non-negative step, and the leg
// tolerance reads its NET. The replays below are those windows' site legs to
// the byte, PRIME FIRST, driven through the driver's own four calls.
const OJEHU = {
  c6page_r0_uix: [
    [25096, 80], [21824, 80], [199980, -178496], [18744, 80],
    [18232, 80], [18232, 80], [23560, 80],
  ],
  c6page_r2_uix: [
    [24876, 80], [18012, 80], [18012, 80], [305392, -285292],
    [17976, 80], [18012, 80], [18012, 80],
  ],
  // Already refused at τ on two OTHER legs.
  c24page_r0_reagent: [
    [25000, 96], [279024, -259996], [18232, 80], [18196, 152],
    [278416, 80], [18412, 80], [18948, 262148],
  ],
};

const replay = (pairs) => {
  const site = allocSiteSplit(siteStream(pairs), 3);
  const steps = allocSteps(allocPrimeSplit(site.collapsed, 1).measured);
  return { steps, verdict: allocWindowVerdict(steps, site.siteLegs, 1) };
};

test('THE REPLAY — the two windows that CERTIFIED are refused, and both old gates are shown blind', () => {
  for (const [name, reclaimed, legTotal] of [
    ['c6page_r0_uix', 178496, 21484],
    ['c6page_r2_uix', 285292, 20100],
  ]) {
    const { steps, verdict } = replay(OJEHU[name]);
    // The gap is real: no step fell and τ had nothing to say...
    assert.deepStrictEqual(pick(steps, 'falls', 'fall', 'refusals', 'certified'), {
      falls: 0,
      fall: 0,
      refusals: [],
      certified: true,
    }, name);
    // ...and the third gate refuses it, on its own reason alone.
    assert.deepStrictEqual(pick(verdict, 'certified', 'legRefusals'), { certified: false, legRefusals: [] }, name);
    assert.deepStrictEqual(verdict.refusals, verdict.intraLegRefusals, name);
    assert.strictEqual(verdict.intraLegRefusals.length, 1, name);
    assert.match(
      verdict.intraLegRefusals[0],
      new RegExp(`reclaimed ${reclaimed} B inside its drain site .*leg total \\+${legTotal} B`),
      name
    );
  }
});

test('THE THIRD WINDOW was already refused, and the new reason is ADDITIONAL', () => {
  const { steps, verdict } = replay(OJEHU.c24page_r0_reagent);
  assert.strictEqual(steps.falls, 0, 'the falls gate is blind here too');
  assert.strictEqual(steps.refusals.length, 2, 'τ refused it on other legs');
  assert.strictEqual(verdict.intraLegRefusals.length, 1);
  assert.match(verdict.intraLegRefusals[0], /reclaimed 259996 B inside its drain site/);
  assert.deepStrictEqual(verdict.legRefusals, steps.refusals);
  assert.deepStrictEqual(verdict.refusals, [...steps.refusals, ...verdict.intraLegRefusals]);
});

test('THE GATE IS NOT VACUOUS — a clean window passes it, however large', () => {
  // The SIGN is the test, not the magnitude, so there is no threshold to drift.
  for (const pairs of [
    [[7988, 18056], [1200, 18056], [1200, 18056], [1200, 20696], [1200, 18056], [1200, 18056], [1200, 18056]],
    Array.from({ length: 7 }, () => [900000, 900000]),
  ]) {
    assert.deepStrictEqual(pick(replay(pairs).verdict, 'certified', 'refusals'), { certified: true, refusals: [] });
  }
});

// --- the row-level witness the driver exits on ------------------------------

const allocRowWith = (windows) => ({
  perRound: [1, 2].map((round) => ({
    round,
    arms: Object.fromEntries(Object.entries(windows).map(([key, s]) => [key, allocSteps(s)])),
  })),
});

test('a row with no rounds at all is not a failure', () => {
  assert.deepStrictEqual(allocRefusedWindows({ perRound: [] }), []);
});

test('every REFUSED window is named, on every round, with its reason', () => {
  const fails = allocRefusedWindows(
    allocRowWith({ 'reagent-subs|lad/fresco#R7': MASKED, 'reagent-subs|lad/reagent#R7': SMALL })
  );
  assert.deepStrictEqual(
    fails.map((f) => f.match(/^round \d \S+: leg \d of \d read \d+ B against a cohort median of \d+ B/)[0]),
    [1, 2].map(
      (r) => `round ${r} reagent-subs|lad/fresco#R7: leg 3 of 4 read 0 B against a cohort median of 200000 B`
    )
  );
});

test('THE EXIT NAMES WHICH GATE FIRED — the two lists are read apart', () => {
  // A leg past τ and an intra-leg reclamation send the operator to different
  // places, and only one of them is about the tolerance.
  const window = replay(OJEHU.c6page_r2_uix).verdict;
  const row = { perRound: [{ round: 1, arms: { 'uix-subs|grid/floor': window } }] };
  const intra = allocRefusedWindows(row, 'intraLegRefusals');
  assert.deepStrictEqual(allocRefusedWindows(row, 'legRefusals'), []);
  assert.strictEqual(intra.length, 1);
  assert.match(intra[0], /^round 1 uix-subs\|grid\/floor: leg 3 of 6 reclaimed 285292 B/);
  assert.deepStrictEqual(allocRefusedWindows(row), intra, 'the default is the union');
});

test('THE RATIO IS POSSIBLE — refusal REASONS are not counted against WINDOWS', () => {
  // One window with three legs outside tolerance yields three reasons, so a
  // reason count could print `windows refused: 17 of 12`.
  const THREE_BAD = stream([20000, 20000, 0, 60000, 0]);
  const row = allocRowWith({ 'reagent-subs|lad/fresco#R7': THREE_BAD, 'reagent-subs|lad/reagent#R7': SMALL });
  assert.deepStrictEqual([allocRefusedWindows(row).length, allocRefusedWindowCount(row)], [6, 2]);

  // Two segments x two rounds = four floor windows, each with the same three
  // deviant legs: the summary names both counts and prints every reason.
  const refusedFloor = Object.fromEntries(
    SEGMENTS.map((seg) => [`${seg}|grid/floor`, { ...floorWindow(seg, 'page'), ...allocSteps(THREE_BAD) }])
  );
  const { row: summarised, out } = allocSummaryFor('floor', refusedFloor);
  assert.deepStrictEqual(pick(summarised, 'refusedWindows', 'refusalReasons'), { refusedWindows: 4, refusalReasons: 12 });
  assert.match(out, /windows refused: 4 of 4, 12 refusal reasons in all/);
  assert.strictEqual((out.match(/^;;   REFUSED /gm) || []).length, 12);
});

// ===========================================================================
// THE PREFLIGHT — THE PAGE IS STATED, AND THE AVERAGING FLOOR IS ENFORCED
// ===========================================================================
//
// There is no per-boundary sizing constant, so the preflight refuses only on
// grounds it can defend without one: an unstated page (no honest default
// exists), a page or window with nothing in it, and a window under the
// averaging floor — at one write per window all four ladder fits came back
// under the 0.98 r² floor.

test('the floor follows ALLOC_MIN_WRITES rather than a number typed beside it', () => {
  const admits = (writes) => allocArmSizing({ writes, roots: 1, cells: 4 }).admissible;
  assert.deepStrictEqual([admits(ALLOC_MIN_WRITES - 1), admits(ALLOC_MIN_WRITES)], [false, true]);
});

test('the averaging floor is ENFORCED in the sizing, not merely derived from', () => {
  const below = allocArmSizing({ writes: ALLOC_MIN_WRITES - 1, roots: 4, cells: 6 });
  assert.deepStrictEqual(below.refusals.map((r) => r.includes('averaging floor')), [true]);
});

test('a refused arm says WHY, one reason per thing wrong with it', () => {
  // No page stated AND one write: the two checks are independent, so an
  // operator fixing one is told about the other.
  const both = allocArmSizing({ writes: 1, roots: 4, cells: null });
  assert.deepStrictEqual(
    both.refusals.map((r) => [r.includes('STATE P0_ALLOC_CELLS'), r.includes('averaging floor')]),
    [
      [true, false],
      [false, true],
    ]
  );
  // A page STATED as zero is a page with nothing on it, not a missing configuration.
  const empty = allocArmSizing({ writes: 6, roots: 4, cells: 0 });
  assert.strictEqual(empty.refusals.length, 1);
  assert.match(empty.refusals[0], /no per-boundary quantity/);
  assert.doesNotMatch(empty.refusals[0], /STATE P0_ALLOC_CELLS/);
});

test('a window with no work in it, and a page with no boundaries, are NOT admissible', () => {
  assert.ok(!allocArmSizing({ writes: 0, roots: 4, cells: 6 }).admissible);
  assert.ok(!allocArmSizing({ writes: 6, roots: 4, cells: 0 }).admissible);
});

// --- the plan the page is mounted from -------------------------------------

test('`ladderPlan` states the page on every arm, floor included', () => {
  const plan = ladderPlan({ list: 300, grid: 6 }, 4);
  const armsFor = (donor) => [
    'grid/floor floor',
    ...[donor, 'fresco'].flatMap((sub) => RUNGS.map((R) => `lad/${sub} R${R}`)),
  ];
  assert.deepStrictEqual(
    plan.map((s) => [s.segment, s.arms.map((a) => `${a.arm} ${a.rung}`)]),
    [
      ['reagent-subs', armsFor('reagent')],
      ['uix-subs', armsFor('uix')],
    ]
  );
  assert.deepStrictEqual([...new Set(plan.flatMap((s) => s.arms.map((a) => `${a.opts.cells}/${a.boundaries}`)))], [
    '6/24',
  ]);
});

test('the plan the small arm mounts is the ladder plan, at a smaller page', () => {
  // Same arms, same rungs, same keys rule — Q = E on every rung — or the small
  // witness would be a second instrument.
  const shape = (grid) =>
    ladderPlan({ list: 300, grid }, 4).map((s) =>
      s.arms.map((a) => [a.key, a.rung, a.reads, a.keys === undefined ? 'no keys' : a.keys === a.boundaries * a.reads])
    );
  const small = shape(6);
  assert.deepStrictEqual(small, shape(300));
  assert.ok(small.flat().every(([, rung, , q]) => q === (rung === 'floor' ? 'no keys' : true)));
});

test('the RETENTION ladder is not moved by any of this', () => {
  // It reads at the published 1,200 boundaries under `:p0/write-all`, which
  // rebuilds at the fixture's literal width; only the allocation window drives
  // `write-page!`, and an unstated width is the published page.
  const published = ladderPlan({ list: 300, grid: 300 }, 4);
  assert.deepStrictEqual(
    [...new Set(published.flatMap((s) => s.arms.map((a) => `${a.opts.cells}/${a.boundaries}`)))],
    ['300/1200']
  );
  const HEAP = fs.readFileSync(path.join(__dirname, 'p0_heap.cljs'), 'utf8');
  assert.match(
    HEAP,
    /\(if all\?\s+\(rf\.bench\.p0-arms\/write-all! @alloc-tick\)\s+\(rf\.bench\.p0-arms\/write-page! @alloc-tick\)\)/
  );
  assert.match(HEAP, /all\?\s+\(= kind "write-all"\)/, 'the bulk write only under its NAMED kind');
  assert.match(HEAP, /\(\[segment-id\] \(prepare! segment-id per-root\)\)/);
  const ARMS = fs.readFileSync(path.join(__dirname, 'p0_arms.cljs'), 'utf8');
  assert.match(ARMS, /\(dispatch-sync! \[:p0\/write-all v\]\)/);
  // The handler to the byte, census call included; the census alias is
  // vocabulary, so it is read off the fixture's own `:require`.
  const FIXTURE = fs.readFileSync(path.join(CORE_BENCH, 'p0_fixture.cljc'), 'utf8');
  const census = (FIXTURE.match(/\[re-frame\.bench\.p0-workcount :as ([^\s\]]+)\]/) || [])[1];
  assert.ok(census, 'the fixture requires the work census');
  const reEsc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  assert.match(
    FIXTURE,
    new RegExp(
      `${reEsc('(rf/reg-event :p0/write-all')}\\s+` +
        reEsc(`(fn [{:keys [db]} [_ v]] (${census}/event!) {:db (assoc db :cells (vec (repeat cells-n v)))}))`)
    )
  );
});

test('the narrow plans SUBTRACT arms — they add none, move none and reorder none', () => {
  const full = ladderPlan({ list: 300, grid: 6 }, 4);
  assert.strictEqual(allocPlanArms(full, ALLOC_PLAN_SHAPES.full), full);
  // The floor read under `floor` is the very arm read under `full`.
  assert.deepStrictEqual(
    allocPlanArms(full, ALLOC_PLAN_SHAPES.floor).map((s, i) => [s.segment, s.arms.length, s.arms[0] === full[i].arms[0]]),
    [
      ['reagent-subs', 1, true],
      ['uix-subs', 1, true],
    ]
  );
  assert.deepStrictEqual(allocPlanArms(full, ALLOC_PLAN_SHAPES.controls), []);
});

// ===========================================================================
// THE CONFIGURATION ROUTES
// ===========================================================================
//
// Configuration is read once, at require, so an env route is pinned from a
// fresh process that requires the driver with `env` set.

function envConsts(env, ...names) {
  const r = spawnSync(
    process.execPath,
    [
      '-e',
      'const m = require(process.argv[1]);' +
        'process.stdout.write(JSON.stringify(process.argv.slice(2).map((n) => m[n] ?? null)))',
      DRIVER,
      ...names,
    ],
    { env: { ...process.env, ...env }, encoding: 'utf8' }
  );
  assert.strictEqual(r.status, 0, `requiring the driver must not throw: ${r.stderr}`);
  return JSON.parse(r.stdout);
}

const UNSET = Object.fromEntries(
  [
    'P0_ROOTS',
    'P0_ALLOC_CELLS',
    'P0_ALLOC_WRITES',
    'P0_ALLOC_BY_SITE',
    'P0_ALLOC_WRITE',
    'P0_ALLOC_PLAN',
    'P0_ALLOC_SEG_ORDER',
    'P0_ALLOC_PASS_ORDER',
    'P0_ALLOC_CONTROL_SLOT',
  ].map((k) => [k, ''])
);
// The resolved write legs as `[selector, kind the page is sent, event]`.
const legsOf = (legs) => legs && legs.map((l) => [l.selector, l.spec.kind, l.spec.event]);
const PAGE_LEG = ['page', 'write', ':p0/write-page'];
const ALL_LEG = ['all', 'write-all', ':p0/write-all'];

test('every switch unset is the published configuration, and the page is mandatory', () => {
  const [arm, windowWrites, sites, legs, paired, plan, segOrder, passOrder, controlSlot] = envConsts(
    UNSET,
    'ALLOC_ARM',
    'ALLOC_WINDOW_WRITES',
    'ALLOC_SITES',
    'ALLOC_WRITE_LEGS',
    'ALLOC_WRITE_PAIRED',
    'ALLOC_PLAN_SHAPE',
    'ALLOC_SEG_ORDER',
    'ALLOC_PASS_ORDER',
    'ALLOC_CONTROL_SLOT'
  );
  assert.deepStrictEqual(
    {
      arm: { ...arm, refusals: arm.refusals.map((r) => r.includes('STATE P0_ALLOC_CELLS')) },
      windowWrites,
      sites,
      legs: legsOf(legs),
      paired,
      plan,
      segOrder,
      passOrder,
      controlSlot,
    },
    {
      // No page is derived from anything: unstated, the run refuses by name.
      arm: { cells: null, roots: 4, boundaries: null, writes: 6, refusals: [true], admissible: false },
      windowWrites: 7, // the six-write averaging floor plus the one-unit prime
      sites: 2,
      legs: [PAGE_LEG],
      paired: false,
      plan: { arms: true, rungs: true, fits: true },
      segOrder: 'parity',
      passOrder: 'parity',
      controlSlot: 'first',
    }
  );
});

test('each switch reaches its named settings, and a mistyped one resolves to nothing', () => {
  for (const [env, names, expected] of [
    [
      { P0_ALLOC_CELLS: '6' },
      ['ALLOC_ARM'],
      [{ cells: 6, roots: 4, boundaries: 24, writes: 6, refusals: [], admissible: true }],
    ],
    [{ P0_ALLOC_WRITES: '10' }, ['ALLOC_WRITES', 'ALLOC_WINDOW_WRITES'], [10, 10 + ALLOC_PRIME_WRITES]],
    [{ P0_ALLOC_BY_SITE: '1' }, ['ALLOC_SITES'], [3]],
    // Anything but the switch is off — `=0` turning the mode on would take
    // every published row with it.
    [{ P0_ALLOC_BY_SITE: '0' }, ['ALLOC_SITES'], [2]],
    [{ P0_ALLOC_WRITE: 'all' }, ['ALLOC_WRITE_LEGS', 'ALLOC_WRITE_PAIRED'], [[ALL_LEG], false]],
    [{ P0_ALLOC_WRITE: 'paired' }, ['ALLOC_WRITE_LEGS', 'ALLOC_WRITE_PAIRED'], [[PAGE_LEG, ALL_LEG], true]],
    // A typo resolves to nothing rather than silently falling back to the
    // default; the preflight then refuses it by name.
    [{ P0_ALLOC_WRITE: 'pge' }, ['ALLOC_WRITE_LEGS', 'ALLOC_WRITE_PAIRED'], [null, false]],
    [{ P0_ALLOC_PLAN: 'floor' }, ['ALLOC_PLAN_SHAPE'], [{ arms: true, rungs: false, fits: false }]],
    [{ P0_ALLOC_PLAN: 'controls' }, ['ALLOC_PLAN_SHAPE'], [{ arms: false, rungs: false, fits: false }]],
    [{ P0_ALLOC_PLAN: 'contols' }, ['ALLOC_PLAN_SHAPE'], [null]],
  ]) {
    const got = envConsts({ ...UNSET, ...env }, ...names);
    if (names[0] === 'ALLOC_WRITE_LEGS') got[0] = legsOf(got[0]);
    assert.deepStrictEqual(got, expected, JSON.stringify(env));
  }
});

// ===========================================================================
// THE SCHEDULE — segment order, leg order and control slot
// ===========================================================================
//
// Each mode exists to break a confound in the published `parity` schedule, and
// each property is a claim about a SEQUENCE of rounds, so the pins drive the
// sequence rather than match the source.

test('THE SEGMENT ORDER — `parity` alternates, `fixed` holds, `fixed-reversed` holds the other way', () => {
  const plan = [{ segment: 'reagent-subs' }, { segment: 'uix-subs' }];
  const seq = (mode) => [0, 1, 2, 3].map((r) => allocSegmentOrder(plan, r, mode).map((s) => s.segment[0]).join(''));
  assert.deepStrictEqual(
    { parity: seq('parity'), fixed: seq('fixed'), reversed: seq('fixed-reversed') },
    {
      parity: ['ru', 'ur', 'ru', 'ur'],
      fixed: ['ru', 'ru', 'ru', 'ru'],
      reversed: ['ur', 'ur', 'ur', 'ur'],
    }
  );
  // Reversing in place would hand every later round a plan already reversed.
  assert.deepStrictEqual(plan.map((s) => s.segment), ['reagent-subs', 'uix-subs']);
});

test('THE LEG ORDER — `parity` alternates; a `seeded` schedule is balanced, parity-free and reproducible', () => {
  // `parity` ties the pass position to every even/odd property of a round;
  // `seeded` draws the order instead, and must stay balanced exactly as
  // parity is or it trades that tie for a write/position confound.
  const LEGS = [{ selector: 'page' }, { selector: 'all' }];
  const legSeq = (mode, rounds, schedule) =>
    Array.from({ length: rounds }, (_, r) =>
      allocPassOrder(LEGS, r, mode, schedule)
        .map((l) => l.selector)
        .join('>')
    );
  assert.deepStrictEqual(legSeq('parity', 4, null), ['page>all', 'all>page', 'page>all', 'all>page']);

  const isParity = (flips) =>
    flips.every((f, r) => f === (r % 2 === 1)) || flips.every((f, r) => f === (r % 2 === 0));
  for (const rounds of [3, 4, 6]) {
    for (let i = 0; i < 20; i++) {
      const s = allocPassFlips(rounds, `probe-${rounds}-${i}`);
      assert.deepStrictEqual(
        [s.parityTied, s.flips.filter(Boolean).length, isParity(s.flips), legSeq('seeded', rounds, s)],
        [false, Math.floor(rounds / 2), false, s.flips.map((f) => (f ? 'all>page' : 'page>all'))],
        `rounds=${rounds} probe ${i}`
      );
    }
  }
  // At two rounds or fewer every balanced schedule IS a parity schedule, and
  // the draw says so rather than returning one under a name that denies it.
  assert.deepStrictEqual([0, 1, 2].map((n) => allocPassFlips(n, 'any').parityTied), [true, true, true]);
  // The draw varies with the seed, and the recorded seed replays it.
  const drawn = new Set(Array.from({ length: 40 }, (_, i) => allocPassFlips(6, `vary-${i}`).flips.join('')));
  assert.ok(drawn.size > 3, `the draw must vary with the seed — saw ${drawn.size} distinct`);
  assert.deepStrictEqual(allocPassFlips(6, 'rf2-fk6pj'), allocPassFlips(6, 'rf2-fk6pj'));
});

test('THE CONTROL SLOT — only `mid` separates the two predicates at full n', () => {
  // `mid` sits after the round's FIRST pass whatever the pass count, and a
  // one-pass round clamps it to the end rather than dropping the controls.
  const C = 'control';
  assert.deepStrictEqual(
    [
      allocRoundWindowKinds(2, 'first'),
      allocRoundWindowKinds(2, 'mid'),
      allocRoundWindowKinds(2, 'last'),
      allocRoundWindowKinds(4, 'mid'),
      allocRoundWindowKinds(1, 'mid'),
    ],
    [
      [C, C, C, 'arm', 'arm'],
      ['arm', C, C, C, 'arm'],
      ['arm', 'arm', C, C, C],
      ['arm', C, C, C, 'arm', 'arm', 'arm'],
      ['arm', C, C, C],
    ]
  );

  // The two properties the slot separates: (A) the arm follows the round's
  // controls, (B) the arm opens its round. Read off the flattened sequence of
  // six two-pass rounds — `last` alone does not separate them, because the
  // round loop is cyclic and round r's controls sit right before round r+1.
  const separated = (slot) => {
    const flat = Array.from({ length: 6 }, () => allocRoundWindowKinds(2, slot)).flat();
    const out = [];
    let n = 0;
    flat.forEach((kind, i) => {
      if (kind !== 'arm') return;
      const afterControls = i > 0 && flat[i - 1] === C;
      if (afterControls !== (n % 2 === 0)) out.push([Math.floor(n / 2), n % 2]);
      n += 1;
    });
    return out;
  };
  assert.deepStrictEqual(
    { first: separated('first'), last: separated('last'), mid: separated('mid').length },
    { first: [], last: [[0, 0]], mid: 12 }
  );
});

// ===========================================================================
// THE BOX RIDERS — what the box was doing, stated in the record
// ===========================================================================

test('THE BOX RIDERS — a busy fraction and a session gap', () => {
  // A busy fraction is a DIFFERENCE of two snapshots, and null — never a
  // fabricated zero — where it cannot be taken.
  assert.deepStrictEqual(
    [
      boxBusyFraction(null, { cpuBusyMs: 1, cpuIdleMs: 1 }),
      boxBusyFraction({ cpuBusyMs: 1, cpuIdleMs: 1 }, { cpuBusyMs: 1, cpuIdleMs: 1 }),
      boxBusyFraction({ cpuBusyMs: 1000, cpuIdleMs: 9000 }, { cpuBusyMs: 1300, cpuIdleMs: 9700 }),
    ],
    [null, null, 0.3]
  );

  // The session, driven over a marker of the pin's own.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'p0-box-pin-'));
  const marker = path.join(dir, 'session.json');
  const at = (hhmm) => `2026-08-21T${hhmm}:00.000Z`;
  const gap = 60 * 60 * 1000;
  try {
    const first = boxSessionOpen(Date.parse(at('00:00')), marker);
    assert.deepStrictEqual(first, {
      startedAt: at('00:00'),
      sessionStartedAt: at('00:00'),
      runsInSession: 1,
      sessionGapMs: gap,
      previousRun: null,
    });
    assert.strictEqual(boxSessionClose(first, at('00:30'), marker), null);

    // Ten idle minutes later the session CONTINUES; the gap since the previous
    // run's END is the one the boundary is drawn on.
    const second = boxSessionOpen(Date.parse(at('00:40')), marker);
    assert.deepStrictEqual(second, {
      startedAt: at('00:40'),
      sessionStartedAt: at('00:00'),
      runsInSession: 2,
      sessionGapMs: gap,
      previousRun: {
        startedAt: at('00:00'),
        endedAt: at('00:30'),
        sinceStartMs: 40 * 60 * 1000,
        sinceEndMs: 10 * 60 * 1000,
        sameSession: true,
      },
    });
    boxSessionClose(second, at('01:00'), marker);

    // Four idle hours open a NEW session that still states the gap.
    const third = boxSessionOpen(Date.parse(at('05:00')), marker);
    assert.deepStrictEqual(pick(third, 'sessionStartedAt', 'runsInSession', 'previousRun'), {
      sessionStartedAt: at('05:00'),
      runsInSession: 1,
      previousRun: {
        startedAt: at('00:40'),
        endedAt: at('01:00'),
        sinceStartMs: (4 * 60 + 20) * 60 * 1000,
        sinceEndMs: 4 * 60 * 60 * 1000,
        sameSession: false,
      },
    });

    // A provenance rider may not refuse a window: a malformed marker reads as
    // a missing one, and an unwritable path comes back as a message.
    fs.writeFileSync(marker, 'not json at all');
    assert.strictEqual(boxSessionOpen(Date.parse(at('05:00')), marker).previousRun, null);
    assert.strictEqual(typeof boxSessionClose(first, at('00:30'), path.join(marker, 'nope', 'x.json')), 'string');

    // The marker path stays on this machine; the gaps travel, the path does not.
    const record = boxRecord({ session: second, chromium: null, open: null, close: null });
    assert.ok(!JSON.stringify(record).includes(os.tmpdir()), 'no machine path rides into a dataset');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ===========================================================================
// THE SUMMARY AND THE RECORD'S WRITE PROVENANCE
// ===========================================================================

// `summariseAlloc` over a synthetic row with `console.log` captured. `arms` is
// what the plan shape decides; `extra` overlays what a mode or a selection sets.
function allocSummaryFor(planName, arms = {}, extra = {}) {
  const ctl = (perIter) => ({ perIter, primeLegs: [20000], primeExcess: 0, ...allocSteps(SMALL) });
  const row = {
    roots: 4,
    boundaries: 24,
    perRoot: { grid: 6 },
    publishedPerRoot: { grid: 300 },
    arm: { cells: 6, roots: 4, boundaries: 24, writes: 6 },
    writes: 6,
    primeWrites: ALLOC_PRIME_WRITES,
    windowWrites: ALLOC_WINDOW_WRITES,
    warmups: 3,
    rounds: 2,
    writeSelector: 'page',
    writeLegs: ['page'],
    writePaired: false,
    write: ':p0/write-page — a synthetic row, measured against nothing',
    plan: { name: planName, ...ALLOC_PLAN_SHAPES[planName] },
    controlDoubles: { d1: 1000, d2: 400 },
    controlSlack: 0.75,
    fallThresholdB: 600000,
    legTolerance: ALLOC_LEG_TOLERANCE,
    verification: { unverified: 0, detail: [] },
    perRound: [1, 2].map((round) => ({
      round,
      controls: { idle: ctl(32), ctl1: ctl(8000), ctl2: ctl(3200) },
      arms,
    })),
    allocFits: { perRound: {}, mean: {} },
    ...extra,
  };
  const lines = [];
  const real = console.log;
  console.log = (s) => lines.push(String(s));
  try {
    summariseAlloc(row, allocRefusedWindows(row));
  } finally {
    console.log = real;
  }
  return { row, out: lines.join('\n') };
}

// One recorded window of arm `a` under write `selector`, carrying the write
// provenance every window records and the observed 6,788 B prime excess.
const armWindow = (segment, a, selector) => ({
  segment,
  arm: a.arm,
  rung: a.rung,
  reads: a.reads || 0,
  boundaries: 24,
  writeSelector: selector,
  write: `${ALLOC_WRITE_SPECS[selector].event} — a synthetic window, measured against nothing`,
  pairKey: a.key,
  ...allocSteps(stream([5000, 5000, 5000, 5000])),
  primeLegs: [11788],
  primeExcess: 6788,
  perWrite: 5000,
  perBoundaryPerWrite: 208,
});
const floorWindow = (segment, selector) =>
  armWindow(segment, { arm: 'grid/floor', rung: 'floor', key: `${segment}|grid/floor` }, selector);

// Every arm the given plan mounts, keyed exactly as the driver keys them, built
// FROM `ladderPlan` so a rung added to the ladder arrives here too.
const armsFor = (rungs, selectors = ['page']) =>
  Object.fromEntries(
    ladderPlan({ list: 300, grid: 6 }, 4).flatMap(({ segment, arms }) =>
      arms
        .filter((a) => rungs || a.rung === 'floor')
        .flatMap((a) =>
          selectors.map((sel) => [allocWindowKey(a.key, sel, selectors.length > 1), armWindow(segment, a, sel)])
        )
    )
  );

// The fits a full run carries, one per (segment, substrate) and per write.
const fitsFor = (selectors = ['page']) => {
  const mean = {};
  const perRound = {};
  for (const { segment, arms } of ladderPlan({ list: 300, grid: 6 }, 4)) {
    for (const sub of new Set(arms.map((a) => a.substrate).filter(Boolean))) {
      for (const sel of selectors) {
        const id = allocWindowKey(`${segment}|${sub}`, sel, selectors.length > 1);
        const fit = { slope: 12, intercept: 100, shell: 90, firstRead: 110, linear: true };
        mean[id] = { ...fit, r2: 0.999, why: 'a synthetic fit, measured against nothing' };
        perRound[id] = [fit, fit];
      }
    }
  }
  return { perRound, mean };
};

const PAIRED_ROW = {
  writeSelector: 'paired',
  writeLegs: ['page', 'all'],
  writePaired: true,
  write:
    ':p0/write-page — a synthetic row, measured against nothing  AND  ' +
    ':p0/write-all — a synthetic row, measured against nothing',
};

test('each plan and selection prints exactly its own claims, and states absence rather than zero', () => {
  // Under a narrowed plan the full plan's sentences are FALSE of the run, and
  // these modes exist to be the provenance of a validity witness. Every claim
  // below is asserted present under some plan, so none can pass by vanishing.
  const CLAIMS = {
    rungs: /WARM 1\/3\/7\/20 READS/,
    fixedB: /held FIXED across every rung/,
    qe: /Q = E on every rung/,
    mounted: /The arm stays MOUNTED/,
    write: /THE WRITE IS `/,
    armTable: /---- reagent-subs ----/,
    floorRow: /;; floor /,
    rungRow: /fresco\s+\d/,
    fits: /THE FITTED LINES/,
    noArm: /NO ARM WAS MOUNTED/,
    noWrite: /NO WRITE EVENT WAS DRIVEN/,
    noRung: /NO RUNG WAS MOUNTED/,
    noFit: /NO FITTED LINE/,
    paired: /MATCHED PAIR/,
    provenance: /PROVENANCE: /,
  };
  const FULL = ['rungs', 'fixedB', 'qe', 'mounted', 'write', 'armTable', 'floorRow', 'rungRow', 'fits'];
  for (const [name, plan, arms, extra, claims, writeDriven] of [
    ['full', 'full', armsFor(true), { allocFits: fitsFor() }, FULL, true],
    ['floor', 'floor', armsFor(false), {}, ['mounted', 'write', 'armTable', 'floorRow', 'noRung', 'noFit'], true],
    ['controls', 'controls', {}, {}, ['noArm', 'noWrite', 'noFit'], false],
    [
      'paired',
      'full',
      armsFor(true, ['page', 'all']),
      { ...PAIRED_ROW, allocFits: fitsFor(['page', 'all']) },
      [...FULL.filter((c) => c !== 'write'), 'paired', 'provenance'],
      true,
    ],
  ]) {
    const { row, out } = allocSummaryFor(plan, arms, extra);
    assert.deepStrictEqual(
      {
        claims: Object.keys(CLAIMS).filter((c) => CLAIMS[c].test(out)),
        // measured off the rounds, not read off the switch
        writeDriven: row.writeDriven,
        controlOk: row.controlVerdict.ok,
      },
      { claims, writeDriven, controlOk: true },
      name
    );
    // The prime leg is excluded from every figure but still REPORTED, in bytes.
    if (name === 'floor') {
      assert.match(out, /prime excess over the measured cohort median: 6788 B mean \[6788–6788\] across 4 windows/);
    }
  }
});

test("a PAIRED record names every window's write and every pair is WHOLE", () => {
  // A paired row has both writes, so the claim "a reader can tell which write
  // produced it" lives on each window, and a pair is matched by its `pairKey`.
  assert.deepStrictEqual(
    [allocWindowKey('k', 'page', false), allocWindowKey('k', 'all', true)],
    ['k', 'k@all']
  );
  const paired = () => armsFor(true, ['page', 'all']);
  const recordOf = (arms, selection = PAIRED_ROW) => ({
    ...selection,
    perRound: [1, 2].map((round) => ({ round, arms })),
  });
  const without = (arms, fields) =>
    Object.fromEntries(
      Object.entries(arms).map(([key, a]) => [key, Object.fromEntries(Object.entries(a).filter(([f]) => !fields.includes(f)))])
    );
  const unpaired = { writeLegs: ['page'] };
  const halfPair = paired();
  delete halfPair['reagent-subs|lad/fresco#R7@all'];
  const foreign = armsFor(true);
  foreign['reagent-subs|grid/floor'] = { ...foreign['reagent-subs|grid/floor'], writeSelector: 'all', write: ':p0/write-all — from some other run' };
  const crossed = armsFor(true);
  crossed['uix-subs|grid/floor'] = { ...crossed['uix-subs|grid/floor'], write: ':p0/write-all — but the selector says page' };

  for (const [why, record, expected] of [
    ['a whole paired record', recordOf(paired()), [true, 88, 44, 0, 0, null]],
    ['a whole unpaired record', recordOf(armsFor(true), unpaired), [true, 44, 44, 0, 0, null]],
    [
      'windows that name nothing',
      recordOf(without(paired(), ['writeSelector', 'write', 'pairKey'])),
      [false, 88, 0, 88, 0, 'round 1 reagent-subs|grid/floor@page: names no write'],
    ],
    [
      // Every window names its write correctly and none belongs to a pair.
      'windows with no pairKey',
      recordOf(without(paired(), ['pairKey'])),
      [false, 88, 0, 88, 0, 'round 1 reagent-subs|grid/floor@page: carries no pairKey, so it belongs to no pair'],
    ],
    [
      'a half pair, well-formed window by window',
      recordOf(halfPair),
      [false, 86, 44, 0, 2, 'round 1 reagent-subs|lad/fresco#R7: page — this run drives page + all'],
    ],
    [
      'a write this run did not drive',
      recordOf(foreign, unpaired),
      [false, 44, 42, 2, 0, 'round 1 reagent-subs|grid/floor: names `all`, which is not a write this run drove'],
    ],
    [
      'a selector and event that disagree',
      recordOf(crossed, unpaired),
      [
        false,
        44,
        42,
        2,
        0,
        'round 1 uix-subs|grid/floor: names `page` but records ' +
          '`:p0/write-all — but the selector says page`, not `:p0/write-page`',
      ],
    ],
  ]) {
    const p = allocWriteProvenance(record);
    assert.deepStrictEqual(
      [p.ok, p.windows, p.pairs, p.unnamed.length, p.incomplete.length, p.unnamed[0] ?? p.incomplete[0] ?? null],
      expected,
      why
    );
  }
});

// ===========================================================================
// THE DRIVER'S WIRING
// ===========================================================================
//
// The driver needs a browser, so these read its source: each gate's verdict
// reaches an exit, both window sites adjudicate the collapsed MEASURED region
// through the third gate, both the warm-up and the measured window drive the
// pass's own write at the full window count, and every published figure is
// divided by the measured writes — never by the window count, prime included.

test('the driver exits on every gate, over the measured region, and divides by the measured writes', () => {
  for (const re of [
    /const structural = ladderStructuralFailures\(out\.ladder\);/,
    /if \(structural\.length > 0\) \{/,
    /const legRefused = allocRefusedWindows\(out\.alloc, 'legRefusals'\);/,
    /if \(legRefused\.length > 0\) \{/,
    /const intraRefused = allocRefusedWindows\(out\.alloc, 'intraLegRefusals'\);/,
    /if \(intraRefused\.length > 0\) \{/,
    /if \(out\.alloc\.fallsInMeasuredWindows > 0\) \{/,
    /if \(!ALLOC_ARM\.admissible\) \{/,
    /perWrite: s\.rise \/ ALLOC_WRITES,/,
    /perIter: s\.rise \/ ALLOC_WRITES,/,
  ]) {
    has(re, 'wired');
  }
  assert.deepStrictEqual(
    [
      /const \{ primeLegs, measured \} = allocPrimeSplit\(site\.collapsed\);/g,
      /const verdict = allocWindowVerdict\(s, site\.siteLegs\);/g,
      /window\.P0H\.allocWindow\(n, k, d\),\s*\[ALLOC_WINDOW_WRITES, drain, leg\.spec\.kind\]/g,
    ].map(countIn),
    [2, 2, 2]
  );
});

// ===========================================================================
// VALIDITY WITNESS V4 — THE PINNED PROBES
// ===========================================================================
//
// Two windows a masking bound ADMITS at `headroom = 0`, with true allocations
// of 300 KB and 600 KB. Each has its offending leg at EXACTLY ZERO against a
// strictly positive median, so |0 − m| = m > τ·m for every τ < 1: the refusal
// is a fact about the fixtures, and no later re-calibration can re-admit them.
const PROBE_A = () => stream([60000, 60000, 60000, 60000, 60000], [0, 0, 0, 0, 60000]);
const PROBE_B = () => stream([50000, 50000, 50000, 50000, 50000, 350000], [0, 0, 0, 0, 0, 350000]);

test('V4 τ-INDEPENDENCE — both probes are refused for EVERY tolerance below 1', () => {
  for (const tau of [0, 0.25, 0.999]) {
    assert.deepStrictEqual(
      [allocSteps(PROBE_A(), tau).certified, allocSteps(PROBE_B(), tau).certified, allocSteps(SMALL, tau).certified],
      [false, false, true],
      `τ=${tau}: both probes refuse, and a clean window still certifies`
    );
  }
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
