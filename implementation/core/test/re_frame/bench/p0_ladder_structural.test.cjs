#!/usr/bin/env node
'use strict';
// THE LADDER'S STRUCTURAL WITNESS — what the counts must be, and at R=0
// especially. rf2-xzg3b. AND THE ALLOCATION ROW'S OBSERVED-COLLECTION
// WITNESS — rf2-2rtt6.140, at the foot of this file, which retired
// rf2-n6w7o's masking budget rather than widening it.
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
  allocRefusedWindows,
  allocRefusedWindowCount,
  ALLOC_LEG_TOLERANCE,
  ALLOC_FALL_THRESHOLD_B,
  ladderPlan,
  allocArmSizing,
  ALLOC_MIN_WRITES,
  allocPrimeSplit,
  ALLOC_PRIME_WRITES,
  ALLOC_WINDOW_WRITES,
  ALLOC_WRITE_SPECS,
  ALLOC_PLAN_SHAPES,
  allocPlanArms,
  summariseAlloc,
  allocSiteSplit,
  allocSiteWitness,
  allocSiteReport,
  ALLOC_SITES,
  ALLOC_SITE_NAMES,
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
// WHICH SUBSTRATE THE CANDIDATE ARM IS — rf2-fe0l
// ===========================================================================
//
// THE DEFECT THIS PINS. The heap ladder's candidate arm read
// `re-frame.bench.hicasso.arm1.*` — the frozen PROTOTYPE that
// `implementation/hicasso/src` was moved from, and whose own docstring says
// it lives "off every production source path". So every retained-heap figure
// the ladder ever produced priced a bench-tree copy, and rf2-hic-006 could
// not re-pin S1-S5 on the package because no heap instrument pointed at the
// package at all. The freeze header calls the two trees' divergence expected
// and permanent, which is exactly why a repoint cannot be left to drift back:
// nothing about a compiling arm says which of the two it compiled against.
//
// WHY IT IS PINNED HERE RATHER THAN LEFT TO THE COMPILER. `:hicasso-bench`
// compiles both trees, so an arm re-pointed at the prototype tomorrow builds
// green and reads plausibly — the number simply describes a different piece
// of software. The compile gate proves the requires RESOLVE; only a source
// assertion says WHICH ones they were.
//
// PARSED FROM THE ns FORM, NEVER GREPPED. Both files legitimately NAME the
// prototype in prose — the provenance is worth keeping — so a whole-file
// grep for `arm1` would fail on a docstring that is doing its job. This
// reads the `:require` / `:require-macros` forms and nothing else, the same
// rule `hicasso/scripts/check_optional_module_reachability.py` follows one
// tree over.

const HEAP = path.join(__dirname, 'p0_heap.cljs');
const CANDIDATE = path.join(__dirname, 'p0_hicasso.cljs');

// `;`-to-end-of-line comments removed, and `"strings"` kept. String-aware,
// because the heap arm's require form carries both — `"react-dom"` beside a
// comment block that names the prototype it no longer requires.
const stripComments = (text) => {
  let out = '';
  let inString = false;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    out += c;
    if (inString) {
      if (c === '\\') {
        out += text[i + 1] ?? '';
        i += 1;
      } else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === ';') {
      out = out.slice(0, -1);
      while (i < text.length && text[i] !== '\n') i += 1;
      out += '\n';
    }
  }
  return out;
};

// The `(:require …)` / `(:require-macros …)` forms of `file`, concatenated,
// with their commentary stripped. Paren-balanced over the stripped text, so
// a `)` inside a comment cannot end a form early.
const nsRequires = (file) => {
  const src = stripComments(fs.readFileSync(file, 'utf8'));
  let out = '';
  for (const head of ['(:require ', '(:require-macros ']) {
    let at = src.indexOf(head);
    while (at !== -1) {
      let depth = 0;
      let i = at;
      let inString = false;
      for (; i < src.length; i += 1) {
        const c = src[i];
        if (inString) {
          if (c === '\\') i += 1;
          else if (c === '"') inString = false;
          continue;
        }
        if (c === '"') inString = true;
        else if (c === '(') depth += 1;
        else if (c === ')') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
      assert.strictEqual(depth, 0, `${path.basename(file)}: unbalanced ${head}`);
      out += `${src.slice(at, i + 1)}\n`;
      at = src.indexOf(head, i + 1);
    }
  }
  assert.ok(out.length > 0, `${path.basename(file)}: no ns require form found`);
  return out;
};

// The file with its comments AND its string literals removed — closer to
// what the COMPILER acts on, so a call site named only in prose or in a
// docstring cannot satisfy a check.
const codeOf = (file) => {
  const src = stripComments(fs.readFileSync(file, 'utf8'));
  let out = '';
  let inString = false;
  for (let i = 0; i < src.length; i += 1) {
    const c = src[i];
    if (inString) {
      if (c === '\\') i += 1;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else out += c;
  }
  return out;
};

const countOf = (hay, needle) => hay.split(needle).length - 1;

test('THE CANDIDATE ARM READS THE PACKAGE — its two doors are the facade', () => {
  const req = nsRequires(CANDIDATE);
  assert.match(req, /\[re-frame\.hicasso :refer \[sub\]\]/, 'the ambient collector is the package facade');
  assert.match(
    req,
    /\(:require-macros \[re-frame\.hicasso :refer \[defview\]\]\)/,
    'and boundaries are minted by the package macro'
  );
  assert.ok(
    !/re-frame\.bench\.hicasso\.arm1/.test(req),
    'p0_hicasso.cljs must not REQUIRE the frozen prototype (naming it in prose is fine)'
  );
});

test('THE HEAP RIG READS THE PACKAGE — all three doors, none of them arm1', () => {
  const req = nsRequires(HEAP);
  assert.match(req, /\[re-frame\.hicasso\.impl\.mount :as hic-mount\]/, 'the mount door');
  assert.match(req, /\[re-frame\.hicasso\.impl\.collector :as hic-collector\]/, 'the runtime reset');
  assert.match(req, /\[re-frame\.hicasso\.impl\.inventory :as hic-inventory\]/, 'the structural census');
  assert.ok(
    !/re-frame\.bench\.hicasso\.arm1/.test(req),
    'p0_heap.cljs must not REQUIRE the frozen prototype (naming it in prose is fine)'
  );
});

test('THE FOUR SEAMS CALL THROUGH THOSE ALIASES, and no fifth one is hiding', () => {
  // Aliases alone prove nothing: a require can be repointed while a call
  // site keeps the old one. These are the four sites rf2-fe0l enumerated,
  // counted in the code and not in the commentary.
  const code = codeOf(HEAP);
  assert.strictEqual(countOf(code, 'hic-mount/root!'), 1, 'the mount door, once');
  assert.strictEqual(countOf(code, 'hic-collector/reset-runtime!'), 1, 'the runtime reset, once');
  assert.strictEqual(countOf(code, 'hic-inventory/residue'), 2, 'the live census and the post-unmount read');
  // The prototype's alias. Its absence is what says no seam was missed.
  assert.strictEqual(countOf(code, 'hic-rt/'), 0, 'no call site left on the old alias');
});

// ===========================================================================
// THE ALLOCATION ROW'S OBSERVED-COLLECTION WITNESS — rf2-2rtt6.140
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
// WHAT USED TO GUARD IT, AND WHY IT IS GONE. rf2-n6w7o charged
// `rise + maxStep <= ALLOC_FALL_THRESHOLD_B / 2` and argued that a window
// inside that budget could contain no collection at all. The merged-PR audit
// of #7682 refuted both premises — the threshold is an UPPER bound on where
// the first collection runs where safety needs a LOWER one, and `maxStep`
// bounds nothing about a masked leg because it sees only NET positive deltas
// — and wrote two executable probes the bound ADMITTED, at `headroom = 0`,
// with true allocations of 300 KB and 600 KB. rf2-2rtt6.141 accepted both
// objections and named this witness as the replacement; rf2-2rtt6.140's
// criterion 4 sanctions the retirement explicitly and states that replacing
// is not widening.
//
// WHAT REPLACED IT reads the window's own samples: the legs of a window are
// W repetitions of ONE work unit, so REFUSE the window if any leg deviates
// from the cohort MEDIAN by more than τ·m. The two probes are pinned at the
// foot of this file, refused, and refused INDEPENDENTLY OF τ.
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
  // The witness refuses it where the sign test cannot — and on the LEG, which
  // is an observation about this window rather than an argument about its
  // size. The third leg read 0 B against a cohort of 200 KB.
  assert.strictEqual(s.certified, false, 'the leg witness must refuse it');
  assert.deepStrictEqual(s.legs, [200000, 200000, 0, 200000]);
  assert.strictEqual(s.legMedian, 200000);
  assert.strictEqual(s.legWorstDeviation, -1, 'the masked leg is 100% below its cohort');
  assert.strictEqual(s.refusals.length, 1, JSON.stringify(s.refusals));
  assert.match(s.refusals[0], /leg 3 of 4/);
});

test('the tolerance is CALIBRATED, pinned, and has no dial on it', () => {
  // The pin that replaces `the budget is the measured fall threshold HALVED`.
  // τ decides whether a measurement may be PUBLISHED, and a gate with a dial
  // on it is a gate that gets dialled — `ALLOC_MASK_BUDGET_B`'s reasoning,
  // unchanged and inherited.
  assert.strictEqual(typeof ALLOC_LEG_TOLERANCE, 'number');
  assert.ok(ALLOC_LEG_TOLERANCE > 0, 'a tolerance of zero would refuse every real window');
  assert.ok(
    ALLOC_LEG_TOLERANCE < 1,
    'at τ >= 1 a leg reading ZERO against a positive cohort is admitted, which is exactly ' +
      'the shape both audit probes have'
  );
  // It is UNCALIBRATED until V3 runs, and the source has to say so rather
  // than letting a placeholder pass for a measured constant.
  has(/const ALLOC_LEG_TOLERANCE = /, 'named, and typed once');
  has(/THIS VALUE IS AN UNCALIBRATED PLACEHOLDER/, 'and marked as not yet measured');
  has(/VALIDITY WITNESS V3/, 'naming what would calibrate it');
  lacks(
    /P0_ALLOC_TOLERANCE|P0_ALLOC_TAU|P0_ALLOC_LEG|P0_ALLOC_MASK|P0_ALLOC_HEADROOM|P0_ALLOC_BUDGET/,
    'a gate with an env dial on it is a gate that gets dialled off'
  );
  // And the driver reads the module constant at every measurement site: the
  // `tolerance` parameter exists for the τ sweep below and for nothing else.
  // Both sites COLLAPSE the by-site stream first (rf2-rs8q6) — which is the
  // identity off the diagnostic mode — then split the prime off (rf2-oiy1) and
  // adjudicate the MEASURED region. One adjudicator, one τ, two windows.
  has(/const site = allocSiteSplit\(w\.samples, w\.sites\);/, 'the control window collapses first');
  has(/const site = allocSiteSplit\(win\.samples, win\.sites\);/, 'and so does the arm window');
  assert.strictEqual(
    (SRC.match(/const \{ primeLegs, measured \} = allocPrimeSplit\(site\.collapsed\);/g) || [])
      .length,
    2,
    'and both then split the prime off the COLLAPSED stream, which is the stream the shipped ' +
      'stride would have filled'
  );
  has(/const s = allocSteps\(measured\);/, 'and what is adjudicated is the measured region');
  lacks(
    /allocSteps\(win\.samples\)|allocSteps\(w\.samples\)/,
    'nothing adjudicates the raw window any more — the prime leg is not a work leg'
  );
});

test('the retired masking budget is GONE, not widened', () => {
  // rf2-2rtt6.140 criterion 4, as a fact about the source rather than a
  // sentence in a brief. Retaining the bound belt-and-braces was considered
  // and rejected on arithmetic: at the composed operating point a window is
  // ~630 KB of rise+maxStep, so a retained bound would refuse every window
  // the witness certifies and the package would deliver nothing.
  lacks(/const ALLOC_MASK_BUDGET_B/, 'the budget constant is deleted');
  lacks(/const ALLOC_B_PER_BOUNDARY_WRITE/, 'and the sizing constant with it');
  lacks(/function allocMaxWrites/, 'and the inversion that only existed to serve it');
  // The measured threshold STAYS, at its measured value, and gates nothing.
  has(/const ALLOC_FALL_THRESHOLD_B = 600000;/, 'not loosened — recorded');
  assert.strictEqual(ALLOC_FALL_THRESHOLD_B, 600000);
  has(/RECORDED, gates nothing/, 'and the summary says which it is');
});

test('the gate is not vacuous — a small clean window passes it', () => {
  // Four writes of 20 KB. Four alike legs are four repetitions of one work
  // unit, which is what the witness is looking for.
  const s = allocSteps(stream([20000, 20000, 20000, 20000]));
  assert.strictEqual(s.falls, 0);
  assert.strictEqual(s.rise, 80000);
  assert.strictEqual(s.maxStep, 20000);
  assert.strictEqual(s.certified, true);
  assert.deepStrictEqual(s.refusals, []);
  assert.strictEqual(s.legMedian, 20000);
  assert.strictEqual(s.legWorstDeviation, 0);
});

test('the boundary is exact — at τ passes, one byte past refuses', () => {
  // The replacement for `at budget passes, one byte over refuses`. Five legs
  // whose median is 20000, with the first sitting exactly τ·m high: admitted.
  // One byte further: refused. Both directions, because the rule is two-sided
  // and a one-sided check would have adjudicated half of it.
  const m = 20000;
  const edge = Math.round(ALLOC_LEG_TOLERANCE * m);
  for (const sign of [1, -1]) {
    const at = allocSteps(stream([m + sign * edge, m, m, m, m]));
    assert.strictEqual(at.legMedian, m, `sign ${sign}: the median is the cohort, not the outlier`);
    assert.strictEqual(at.certified, true, `sign ${sign}: exactly at τ is inside it`);
    const past = allocSteps(stream([m + sign * (edge + 1), m, m, m, m]));
    assert.strictEqual(past.legMedian, m);
    assert.strictEqual(past.certified, false, `sign ${sign}: one byte past τ is not`);
    assert.strictEqual(past.refusals.length, 1);
    assert.match(past.refusals[0], /leg 1 of 5/);
  }
});

test('A MASKED LEG READS BELOW ITS COHORT, and that is what refuses', () => {
  // The replacement for `NET GROWTH CANNOT DEFEAT IT`, which argued about the
  // budget. The same four legs, once with the third leg's collection masked
  // and once without. Masking REMOVES bytes from `rise`, so the masked window
  // is the one that looks SMALLER — and the retired bound could only ever be
  // flattered by that. The leg witness reads the opposite way round: removing
  // bytes from one leg is precisely what makes it unlike its cohort.
  const legs = [200000, 200000, 200000, 200000];
  const masked = allocSteps(stream(legs, [0, 0, 200000, 0]));
  const clean = allocSteps(stream(legs));
  assert.ok(masked.rise < clean.rise, 'masking removes bytes from the rising sum');
  assert.strictEqual(clean.certified, true, 'the unmasked window is four alike legs');
  assert.strictEqual(masked.certified, false, 'and masking is what refuses the other');
  assert.ok(
    masked.legWorstDeviation < 0,
    'the offending leg is BELOW its cohort, never above — that is the signature'
  );
});

test('THE FALLS GATE IS UNTOUCHED — a visible collection still counts as one', () => {
  // The half that works. rf2-n6w7o was discharged by ADDING a refusal and
  // rf2-2rtt6.140 replaces only what rf2-n6w7o added: every window this gate
  // refuses today it refuses after.
  const s = allocSteps(stream([20000, 20000], [0, 40000]));
  assert.strictEqual(s.falls, 1, 'a net-negative leg is still a falling step');
  assert.strictEqual(s.fall, 20000, 'the second leg allocated 20000 and lost 40000');
  assert.strictEqual(s.rise, 20000, 'a fall is EXCLUDED from the rising sum, never netted');
  assert.strictEqual(s.endpoints, 0, 'and the endpoints alone would have said nothing happened');
});

test('`maxStep` is the largest single rising step, not the mean or the last', () => {
  // It survives as a reported DIAGNOSTIC. Nothing certifies on it any more —
  // it was the term the audit showed bounds nothing about a masked leg — but
  // it is still the right answer to the question it asks.
  const s = allocSteps(stream([1000, 7000, 3000]));
  assert.strictEqual(s.rise, 11000);
  assert.strictEqual(s.maxStep, 7000);
  assert.strictEqual(s.endpoints, 11000);
});

test('an idle window is homogeneous at zero, and certifies', () => {
  // The idle control prices the sampler's own footprint and is one of the
  // three windows every round takes, so a witness that refused it would refuse
  // the row's own instrument. `τ·0` is 0 and no leg deviates from 0 by more
  // than 0.
  const s = allocSteps(stream([0, 0, 0]));
  assert.strictEqual(s.rise, 0);
  assert.strictEqual(s.falls, 0);
  assert.strictEqual(s.maxStep, 0);
  assert.strictEqual(s.certified, true);
  assert.strictEqual(s.legMedian, 0);
  assert.deepStrictEqual(s.legs, [0, 0, 0]);
  // But it is not a free pass: a window whose legs disagree is refused at a
  // zero median too, which is the case a ratio test would have divided by
  // zero on.
  const lumpy = allocSteps(stream([0, 100, 0]));
  assert.strictEqual(lumpy.legMedian, 0);
  assert.strictEqual(lumpy.certified, false, 'one leg doing work and two doing none is not one unit');
});

test('THE CERTIFICATE IS TIGHT — an admitted window under-reads by at most 2τ', () => {
  // What an admitted window is certified to BE, checked rather than asserted.
  // UNDER THE COHORT PREMISE — that absent a collection each leg's true
  // allocation lies within τ of the true median — a leg can sit τ·m high on
  // its own merits and still be admitted after losing a further τ·m. So the
  // guarantee is `rise under-reads by at most 2τ`, and this is the exact
  // worst case: leg 1 is τ·m HIGH, loses 2τ·m, and reads τ·m LOW.
  const m = 1000;
  const hi = m * (1 + ALLOC_LEG_TOLERANCE);
  const lost = 2 * ALLOC_LEG_TOLERANCE * m;
  const at = allocSteps(stream([hi, m, m, m, m], [lost, 0, 0, 0, 0]));
  assert.strictEqual(at.falls, 0, 'a masked leg turns no step negative — that IS the fault');
  assert.strictEqual(at.legs[0], m * (1 - ALLOC_LEG_TOLERANCE), 'and reads exactly τ·m low');
  assert.strictEqual(at.legMedian, m);
  assert.strictEqual(at.certified, true, 'admitted, at the very edge of the tolerance');
  // The under-read that bought that admission is exactly 2τ of the median,
  // and no more: one byte further and the window refuses.
  assert.strictEqual(lost, 2 * ALLOC_LEG_TOLERANCE * at.legMedian);
  const past = allocSteps(stream([hi, m, m, m, m], [lost + 1, 0, 0, 0, 0]));
  assert.strictEqual(past.certified, false, 'so 2τ is a BOUND and not an approximation');
  // And the whole-window statement the summary prints: `rise` under-reads the
  // true allocation by at most 2τ. Here the true allocation is 5,250 B and
  // `rise` reads 4,750 B — a 9.5% under-read against a 50% ceiling.
  const trueAlloc = hi + 4 * m;
  assert.ok((trueAlloc - at.rise) / trueAlloc <= 2 * ALLOC_LEG_TOLERANCE);
});

test('LEGS AND GAPS are read apart, and only the legs are adjudicated', () => {
  // `[s0, pre0, post0, pre1, post1, ...]`: the legs are `post - pre` and the
  // gaps are `pre - post`, where nothing happens but a loop increment and two
  // array stores. `rise` walks BOTH, as it always did. The witness reads only
  // the legs — and nothing allocates in a gap, so a collection there cannot be
  // masked at all: it lands as a negative step and the falls gate takes it.
  const s = allocSteps(stream([1000, 2000, 3000]));
  assert.deepStrictEqual(s.legs, [1000, 2000, 3000], 'one leg per iteration');
  assert.deepStrictEqual(s.gaps, [0, 0, 0], 'and nothing happens between them');
  assert.strictEqual(s.rise, 6000, 'rise is unchanged by the split');
});

// ===========================================================================
// THE PRIME WORK UNIT — rf2-oiy1
// ===========================================================================
//
// THE DEFECT THIS PINS. rf2-2rtt6.140's V1/V2/V3 window measured 336 arm
// windows across eight browser runs and every one carried a POSITIVE first-leg
// excess over its own cohort median — median 6,966 B, constant in absolute
// bytes across a 24x page range, identical under both writes. At τ = 0.25 a
// fixed ~7 KB excess refuses every window whose leg median is under ~27,900 B,
// which is every floor window at every page size; and `arm − floor` is the
// quantity every witness on this row is stated over, so the ladder went with
// it.
//
// WHICH CAUSE IT IS, FROM THAT WINDOW'S OWN NUMBERS. The tail legs of a clean
// window are byte-identical, so steady state arrives after ONE work unit
// inside the window; eighteen work units already run immediately before it, in
// three full-size warm-up windows, and the excess survives all of them; and
// the only thing between the last of those and leg 1 is the driver's own
// `gc()`. One after clears it, eighteen before do not — so the collection
// creates it, and a fourth warm-up (the bead's branch (a)) cannot reach it.
//
// THE REPAIR IS NOT A WIDENING AND NOT A DISCARD. The window drives one extra
// work unit; the first is a PRIME, sampled and reported and excluded from
// every published quantity and from the certificate. τ is untouched.
// `allocSteps` is untouched — every pin above it stands unedited — because the
// split hands it a shorter stream in the same shape.

test('THE SPLIT — the measured region is a well-formed stream in the same shape', () => {
  // `[s0, pre0, post0, pre1, post1, ...]`. Dropping `2·prime` leading samples
  // leaves the last prime leg's `post` standing as the measured region's `s0`,
  // so nothing is fabricated and nothing is re-based. That is the whole reason
  // `allocSteps` needs no knowledge of any of this.
  const raw = stream([9000, 1000, 1000, 1000]);
  const { primeLegs, measured } = allocPrimeSplit(raw, 1);
  assert.deepStrictEqual(primeLegs, [9000], 'the prime leg is READ, not thrown away');
  assert.deepStrictEqual(measured, raw.slice(2));
  const s = allocSteps(measured);
  assert.deepStrictEqual(s.legs, [1000, 1000, 1000], 'and the measured legs are the rest');
  assert.deepStrictEqual(s.gaps, [0, 0, 0], 'including the true gap out of the prime');
  assert.strictEqual(s.rise, 3000, 'the prime`s 9000 B is in no rising sum');
  assert.strictEqual(s.falls, 0);
});

test('THE BEAD`S OWN WINDOW — refused before the prime, certified after', () => {
  // The B = 4 floor window quoted verbatim on rf2-oiy1:
  // [26044, 19256, 19256, 19256, 19256, 19256]. Six alike legs with the first
  // 6,788 B high — 35% of a 19,256 B cohort, against a 25% tolerance.
  const OBSERVED = [26044, 19256, 19256, 19256, 19256, 19256];
  // WRONG BEFORE. Adjudicated whole, exactly as the driver did until this
  // bead, it refuses — and on the first leg, which is the only deviant one.
  const before = allocSteps(stream(OBSERVED));
  assert.strictEqual(before.certified, false, 'this is the window the row could not certify');
  assert.strictEqual(before.refusals.length, 1, JSON.stringify(before.refusals));
  assert.match(before.refusals[0], /leg 1 of 6/);
  assert.strictEqual(before.legMedian, 19256);

  // RIGHT AFTER. The same six work units with a prime in front of them: the
  // prime absorbs the excess, the six measured legs are byte-identical, and
  // the window certifies. The tail is byte-identical in the MEASURED data —
  // that is the observation the whole repair rests on, and it is why one
  // priming unit is enough.
  const primed = allocPrimeSplit(stream([26044, ...OBSERVED.slice(1), 19256]), 1);
  const after = allocSteps(primed.measured);
  assert.deepStrictEqual(primed.primeLegs, [26044]);
  assert.deepStrictEqual(after.legs, [19256, 19256, 19256, 19256, 19256, 19256]);
  assert.strictEqual(after.certified, true, 'six repetitions of one work unit certify');
  assert.strictEqual(after.legWorstDeviation, 0);
  assert.strictEqual(after.rise, 6 * 19256, 'and the prime is in no published byte');
  // The excess is still MEASURED. This is what makes the repair a report
  // rather than a discard, and it is the figure rf2-e9wr's calibration wants.
  assert.strictEqual(primed.primeLegs[0] - after.legMedian, 6788);
});

test('THE PRIME IS NOT AN AMNESTY — a deviant MEASURED leg still refuses', () => {
  // A rule that cannot fail has adjudicated nothing. The prime takes exactly
  // one work unit out of the cohort; everything the leg witness refused before
  // it, it refuses after.
  const masked = allocPrimeSplit(stream([26044, 200000, 200000, 0, 200000], [0, 0, 0, 200000, 0]));
  const s = allocSteps(masked.measured);
  assert.strictEqual(s.certified, false, 'a masked leg in the measured region still refuses');
  assert.ok(s.legWorstDeviation < 0, 'and it is BELOW its cohort, which is the signature');
  // And a SECOND high leg — one the prime does not cover — refuses too.
  const twoHigh = allocPrimeSplit(stream([26044, 26044, 19256, 19256, 19256, 19256, 19256]));
  assert.strictEqual(
    allocSteps(twoHigh.measured).certified,
    false,
    'the prime covers ONE work unit, not "the high ones"'
  );
});

test('the prime is CONFIGURED IN ONE PLACE and the divisor is not it', () => {
  assert.strictEqual(typeof ALLOC_PRIME_WRITES, 'number');
  assert.ok(ALLOC_PRIME_WRITES >= 1, 'a prime of zero is the shape the bead refutes');
  assert.strictEqual(
    ALLOC_WINDOW_WRITES - ALLOC_MIN_WRITES,
    ALLOC_PRIME_WRITES,
    'the window drives the averaging floor PLUS the prime'
  );
  // THE AVERAGING FLOOR IS UNMOVED (rf2-2rtt6.142). Excluding a leg from a
  // six-write window would have left five averaged writes, under the floor.
  // Averaging six means driving seven, and this is the arithmetic that says so.
  assert.strictEqual(ALLOC_MIN_WRITES, 6);
  // The published quantity is per MEASURED write. A divisor of
  // `ALLOC_WINDOW_WRITES` would have folded the prime back into every figure.
  has(/perWrite: s\.rise \/ ALLOC_WRITES,/, 'the arm figure divides by the measured writes');
  has(/perIter: s\.rise \/ ALLOC_WRITES,/, 'and so does the control figure');
  // And the window is driven at the full count at every site, warm-ups
  // included — a warm-up smaller than the window is the defect the warm-up
  // pass exists to avoid.
  // THE CALL SHAPE MOVED, SO THIS PIN MOVED WITH IT (rf2-rs8q6). `allocPrepare`
  // gained a stride argument, and the old literal `allocPrepare(dd, n)` would
  // have matched nothing from that day on — a `lacks` that matches nothing
  // passes, silently, for ever. Anchored on the argument ARRAY instead, which
  // is where the count it is about actually lives.
  lacks(
    /allocPrepare\([^)]*\),\s*\[[^\]]*\bALLOC_WRITES\b/,
    'no sample buffer is sized for the measured writes alone'
  );
  // And the positive half, counted, so it bites in both directions: EVERY
  // `allocPrepare` in the driver sizes for the window count AND states the
  // stride, because a buffer sized for one stride and indexed at another
  // writes past the end of a `Float64Array` — which does not throw, it drops
  // the store, and the leg reads zero.
  const prepares = SRC.match(/window\.P0H\.allocPrepare\([^)]*\),\s*\[[^\]]*\]/g) || [];
  assert.strictEqual(
    prepares.length,
    3,
    `every allocPrepare call site is accounted for: ${JSON.stringify(prepares)}`
  );
  for (const p of prepares) {
    assert.match(p, /ALLOC_WINDOW_WRITES/, `sized for the window it drives: ${p}`);
    assert.match(p, /ALLOC_SITES/, `and the stride is stated at the same call: ${p}`);
  }
  has(/\[ALLOC_WINDOW_WRITES, drain, ALLOC_WRITE_SPEC\.kind\]/, 'the window drives the full count');
  // NO DIAL. The prime decides what a window MEANS, so it is a constant for
  // `ALLOC_LEG_TOLERANCE`'s reason: a knob on it is a knob on the certificate.
  lacks(/P0_ALLOC_PRIME|P0_ALLOC_WINDOW_WRITES/, 'the prime has no env dial on it');
});

test('THE PRIME IS LIVE THROUGH THE SHIPPED CONFIGURATION ROUTE, not just declared', () => {
  // DRIVEN, in a fresh process, through the same env surface an operator
  // configures: configuration is read exactly once at require, so this is the
  // shipped derivation rather than a restatement of it. `constUnderEnv` is
  // hoisted from further down this file — the same helper the write and plan
  // switches are pinned with.
  assert.strictEqual(constUnderEnv('ALLOC_PRIME_WRITES', {}), ALLOC_PRIME_WRITES);
  assert.strictEqual(
    constUnderEnv('ALLOC_WINDOW_WRITES', {}),
    ALLOC_MIN_WRITES + ALLOC_PRIME_WRITES,
    'the default window drives the averaging floor plus the prime'
  );
  // And it tracks a stated window rather than sitting at a literal: a run that
  // asked for more averaging gets more averaging AND its prime, not one of the
  // two.
  assert.strictEqual(
    constUnderEnv('ALLOC_WINDOW_WRITES', { P0_ALLOC_WRITES: '10' }),
    10 + ALLOC_PRIME_WRITES
  );
  assert.strictEqual(constUnderEnv('ALLOC_WRITES', { P0_ALLOC_WRITES: '10' }), 10);
});

test('the prime is CLAMPED, so a short stream cannot read off the end', () => {
  // Defensive, and cheap: a window with fewer legs than the prime yields no
  // measured legs rather than an undefined subtraction.
  const short = allocPrimeSplit(stream([1000]), 1);
  assert.deepStrictEqual(short.primeLegs, [1000]);
  assert.deepStrictEqual(allocSteps(short.measured).legs, []);
  const none = allocPrimeSplit(stream([]), 1);
  assert.deepStrictEqual(none.primeLegs, [], 'nothing to prime is not a negative index');
  // A prime of zero is the pre-bead shape, and the split is the identity on it
  // — which is what lets every `allocSteps` pin above stand unedited.
  const raw = stream([1000, 1000]);
  assert.deepStrictEqual(allocPrimeSplit(raw, 0), { primeLegs: [], measured: raw });
});

// ===========================================================================
// THE BY-SITE INSTRUMENT — rf2-rs8q6
// ===========================================================================
//
// WHAT IT IS FOR. rf2-e9wr measured 72 floor-arm windows and found the leg
// dispersion to be a function of the ROUND INDEX — round 2 (n=11) at
// 2.66%–20.37% with none below 2.66%, round 3 (n=11) at 0.00%–0.19% with all
// at or below 0.19% — holding in each of six independent browser launches
// separately. The excesses are positive legs against a byte-identical cohort
// with zero falling steps, so they are allocation by the page's own heap.
//
// WHY THE SHIPPED STRIDE CANNOT IDENTIFY IT, as a matter of shape. A leg is
// ONE step: read before the unit, read after, everything between is a single
// number. "Which part of the work unit allocates" is not recoverable from a
// scalar per leg, however the scalars are analysed. The mode opens the unit at
// its one seam and the pins below are what say it did so without moving
// anything the row publishes.
//
// HERMETIC, exactly as the prime's pins are. `siteStream` builds the stride-3
// buffer `p0-heap/alloc-window!` would have filled from stated per-site
// allocations, so every case here is a fixture rather than something that has
// to be provoked out of a browser.

// `[s0, pre0, mid0, post0, pre1, mid1, post1, ...]` — the stride-3 shape, from
// `[dispatch, drain]` pairs. Same base heap as `stream`, so a collapsed
// stride-3 stream is directly comparable with the stride-2 one built from the
// same leg totals.
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

// One window in the shape rf2-e9wr actually measured: the B = 4 floor window
// quoted on rf2-oiy1 — six alike legs of 19,256 B with a 26,044 B prime — with
// ONE leg carrying the median recurring excess the bead reports (+2,640 B), and
// with each of the two terms placed at a stated site.
const siteWindow = ({ primeAt, excessAt, excess = 2640, primeExcess = 6788 }) => {
  const base = { dispatch: 1200, drain: 18056 };
  const at = (site, extra) => [
    base.dispatch + (site === 'dispatch' ? extra : 0),
    base.drain + (site === 'drain' ? extra : 0),
  ];
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

test('THE COLLAPSE IS THE IDENTITY AT THE SHIPPED STRIDE — every gate above stands unedited', () => {
  // This is the property that lets the whole mode be additive. Off it, the
  // driver runs the pre-bead path through a function that returns what it was
  // given, and `collapsed` is the SAME ARRAY rather than a copy of it: nothing
  // is rebuilt, so nothing can be rebuilt differently.
  const s = stream([19256, 19256, 19256]);
  const split = allocSiteSplit(s, 2);
  assert.deepStrictEqual(split, { sites: 2, collapsed: s, siteLegs: [] });
  assert.strictEqual(split.collapsed, s, 'the shipped stream is passed through, not reconstructed');
  // And anything that is not the by-site stride is the shipped one. A mode
  // armed with a typo must fall back to the instrument that publishes, never
  // to a third shape nothing decodes.
  for (const k of [0, 1, 4, undefined, null, '3']) {
    assert.strictEqual(allocSiteSplit(s, k).sites, 2, `stride ${JSON.stringify(k)} is not the seam`);
  }
});

test('THE COLLAPSED STREAM IS THE ONE THE SHIPPED WINDOW WOULD HAVE FILLED', () => {
  // The claim the whole mode rests on, driven rather than argued: decoding a
  // stride-3 window and dropping its mid samples yields, sample for sample,
  // the stride-2 stream the same work would have produced — so `allocSteps`
  // computes the certificate, the falls gate and every published figure over
  // the same shape by the same code.
  const raw = siteWindow({ primeAt: 'dispatch', excessAt: 'drain' });
  const { sites, collapsed, siteLegs } = allocSiteSplit(raw, 3);
  assert.strictEqual(sites, 3);
  assert.deepStrictEqual(
    collapsed,
    stream([26044, 19256, 19256, 21896, 19256, 19256, 19256]),
    'the collapsed stream is the stride-2 stream of the same legs'
  );
  // Every reading in it is a reading actually taken — nothing is averaged,
  // folded or synthesised.
  for (const x of collapsed) assert.ok(raw.includes(x), `${x} is a sample the window really took`);
  // And the gate reads what it would have read.
  const { primeLegs, measured } = allocPrimeSplit(collapsed, 1);
  assert.deepStrictEqual(primeLegs, [26044]);
  const s = allocSteps(measured);
  assert.deepStrictEqual(s.legs, [19256, 19256, 21896, 19256, 19256, 19256]);
  assert.strictEqual(s.legMedian, 19256);
  assert.strictEqual(s.falls, 0);
  // A LEG IS ITS SITES, EXACTLY. Not approximately and not up to a rounding:
  // the outer pair is the same pair, so the decomposition is a subtraction
  // identity rather than a model.
  assert.strictEqual(siteLegs.length, 7);
  for (const l of siteLegs) {
    assert.strictEqual(l.dispatch + l.drain, l.leg, JSON.stringify(l));
  }
  assert.deepStrictEqual(siteLegs[3], { dispatch: 1200, drain: 20696, leg: 21896 });
});

test('THE ATTRIBUTION NAMES THE SITE — in both directions, so a swap cannot pass', () => {
  // The mechanism question, and the one thing this instrument exists to
  // answer. A fixture whose recurring excess is entirely in the DRAIN must be
  // attributed to the drain, and the mirror fixture to the dispatch. Testing
  // one direction alone would admit an attribution that names the other site
  // every time.
  for (const site of ALLOC_SITE_NAMES) {
    const other = ALLOC_SITE_NAMES.find((s) => s !== site);
    const { siteLegs } = allocSiteSplit(
      siteWindow({ primeAt: 'dispatch', excessAt: site }),
      3
    );
    const w = allocSiteWitness(siteLegs, 1);
    assert.strictEqual(w.dominant, site, `the window's dispersion is in the ${site}`);
    assert.strictEqual(w.legs[2].site, site, 'and so is the deviant leg`s own');
    assert.strictEqual(w.legs[2].by[site], 2640, 'by the whole of the excess');
    assert.strictEqual(w.legs[2].by[other], 0, `and none of it in the ${other}`);
    assert.strictEqual(w.legs[2].deviation, 2640, 'which is the leg deviation the gate sees');
    // The cohort's own medians are the alike legs', not the deviant one's.
    assert.strictEqual(w.medians.dispatch, 1200);
    assert.strictEqual(w.medians.drain, 18056);
    assert.strictEqual(w.medians.leg, 19256);
  }
});

test('THE PRIME IS OUT OF THE BY-SITE COHORT TOO, and its excess is placed', () => {
  // rf2-oiy1 removed the first-leg term from the measured region; a by-site
  // cohort that included the prime would have that term sitting back inside
  // its own medians and every site figure would be wrong by a share of it.
  const { siteLegs } = allocSiteSplit(siteWindow({ primeAt: 'dispatch', excessAt: 'drain' }), 3);
  const w = allocSiteWitness(siteLegs, 1);
  assert.deepStrictEqual(w.primeSites, [{ dispatch: 7988, drain: 18056, leg: 26044 }]);
  assert.strictEqual(w.legs.length, 6, 'the prime is not one of the six measured legs');
  assert.deepStrictEqual(w.primeExcessBySite, { dispatch: 6788, drain: 0 });
  assert.strictEqual(w.primeSite, 'dispatch');
  // Clamped like `allocPrimeSplit`, and for the same reason.
  const short = allocSiteWitness(siteLegs.slice(0, 1), 1);
  assert.deepStrictEqual(short.legs, []);
  assert.strictEqual(short.primeSite, null, 'nothing to compare against is not a site');
  assert.strictEqual(short.sameSite, null);
});

test('THE SAME-TERM HYPOTHESIS IS ANSWERABLE, and answers three ways', () => {
  // The bead's "exclude this first": the recurring excesses (+476 to +7,456 B,
  // median 2,640) overlap the prime excess's own scale (median 6,864), which
  // would suggest THE SAME TERM RECURRING rather than a distinct one.
  //
  // SITES DISAGREEING SETTLES IT — one term cannot be in two places.
  const apart = allocSiteWitness(
    allocSiteSplit(siteWindow({ primeAt: 'dispatch', excessAt: 'drain' }), 3).siteLegs,
    1
  );
  assert.strictEqual(apart.primeSite, 'dispatch');
  assert.strictEqual(apart.dominant, 'drain');
  assert.strictEqual(apart.sameSite, false, 'two sites is two terms');

  // SITES AGREEING NARROWS IT and does not settle it — a site is two
  // statements wide and two terms can live in one. The magnitudes are what is
  // read next, which is why they are reported beside the verdict.
  const together = allocSiteWitness(
    allocSiteSplit(siteWindow({ primeAt: 'drain', excessAt: 'drain' }), 3).siteLegs,
    1
  );
  assert.strictEqual(together.sameSite, true);
  assert.deepStrictEqual(together.primeExcessBySite, { dispatch: 0, drain: 6788 });
  assert.strictEqual(together.legs[2].by.drain, 2640, 'and the two magnitudes are 2.6x apart');

  // NO DISPERSION IS `null`, NOT `false`. A round-3 window whose legs are
  // byte-identical has no excess to place, and reporting "the sites disagree"
  // about a window with nothing in it would be the instrument inventing a
  // finding. This is the case eleven of rf2-e9wr's round-3 windows are in.
  const flat = allocSiteWitness(
    allocSiteSplit(
      siteStream([[7988, 18056], ...Array.from({ length: 6 }, () => [1200, 18056])]),
      3
    ).siteLegs,
    1
  );
  assert.strictEqual(flat.dominant, null, 'byte-identical legs have no site to name');
  assert.strictEqual(flat.sameSite, null, 'and no verdict to give');
  assert.deepStrictEqual(flat.totals, { dispatch: 0, drain: 0 });
  assert.strictEqual(flat.primeSite, 'dispatch', 'while the prime excess is still placed');
});

test('THE MODE IS OFF BY DEFAULT, AND τ IS UNTOUCHED BY IT', () => {
  // Driven in a fresh process through the same env surface an operator
  // configures, for `constUnderEnv`'s reason: configuration is read once, at
  // require.
  assert.strictEqual(constUnderEnv('ALLOC_BY_SITE', { P0_ALLOC_BY_SITE: '' }), false);
  assert.strictEqual(constUnderEnv('ALLOC_SITES', { P0_ALLOC_BY_SITE: '' }), 2);
  assert.strictEqual(ALLOC_SITES, 2, 'and this process is running the shipped stride');
  assert.strictEqual(constUnderEnv('ALLOC_SITES', { P0_ALLOC_BY_SITE: '1' }), 3);
  // Anything that is not the switch is off — a diagnostic that turned itself
  // on for `P0_ALLOC_BY_SITE=0` would take every published row with it.
  for (const v of ['0', 'yes', 'true', '3', ' 1']) {
    assert.strictEqual(constUnderEnv('ALLOC_SITES', { P0_ALLOC_BY_SITE: v }), 2, `P0_ALLOC_BY_SITE=${v}`);
  }
  // AND IT IS NOT A DIAL ON THE GATE. rf2-e9wr established that no honest τ
  // calibration exists on the arms' data in either direction; an instrument
  // that answered the round-index question by moving τ would be answering a
  // different question. Every constant the certificate is read off is
  // identical with the mode armed.
  for (const c of ['ALLOC_LEG_TOLERANCE', 'ALLOC_FALL_THRESHOLD_B', 'ALLOC_MIN_WRITES',
                   'ALLOC_PRIME_WRITES', 'ALLOC_WINDOW_WRITES']) {
    assert.strictEqual(
      constUnderEnv(c, { P0_ALLOC_BY_SITE: '1' }),
      constUnderEnv(c, { P0_ALLOC_BY_SITE: '' }),
      `${c} must not move when the diagnostic is armed`
    );
  }
});

test('THE CERTIFICATE NEVER SEES A BY-SITE STREAM, and the stride is the PAGE`S', () => {
  // The collapse happens FIRST, at both window sites, and what is adjudicated
  // is the collapsed stream. A mid-leg sample reaching `allocSteps` would
  // split one step into two and change `rise`, `falls` and `maxStep` — all
  // published — for a diagnostic's convenience.
  has(/const site = allocSiteSplit\(w\.samples, w\.sites\);/, 'the control window collapses first');
  has(/const site = allocSiteSplit\(win\.samples, win\.sites\);/, 'and so does the arm window');
  assert.strictEqual(
    (SRC.match(/allocPrimeSplit\(site\.collapsed\)/g) || []).length,
    2,
    'both window sites hand the collapsed stream to the prime split'
  );
  lacks(
    /allocPrimeSplit\((?:w|win)\.samples\)/,
    'neither site adjudicates the raw stream any more'
  );
  // THE STRIDE IS READ OFF THE DATA, not off the switch the driver believes it
  // set. A page serving an older build would fill a stride-2 buffer and hand
  // back a well-formed stream; decoding it against the driver's own constant
  // would read site figures out of readings that are not at a seam.
  lacks(
    /allocSiteSplit\((?:w|win)\.samples, ALLOC_SITES\)/,
    'the driver decodes by the stride the page reported'
  );
  has(/if \(probe\.sites !== ALLOC_SITES\)/, 'and the two are made to agree before anything is measured');
});

test('THE REPORT RUNS — the mode is not merely defined', () => {
  // "The mode is defined" and "the mode runs" are different claims, and this
  // file already makes that point about `summariseAlloc`. Drive a collected
  // row through the reporter and read what an operator would have seen.
  const witnessOf = (spec) => {
    const { siteLegs } = allocSiteSplit(siteWindow(spec), 3);
    return { sites: 3, siteLegs, siteWitness: allocSiteWitness(siteLegs, 1) };
  };
  const row = {
    bySite: true,
    sites: 3,
    siteNames: ALLOC_SITE_NAMES,
    perRound: [
      {
        round: 1,
        controls: { idle: witnessOf({ primeAt: 'dispatch', excessAt: null, excess: 0 }) },
        arms: { 'hicasso|floor': { ...witnessOf({ primeAt: 'dispatch', excessAt: 'drain' }), tick0: 22, tick: 29 } },
      },
    ],
  };
  const lines = allocSiteReport(row);
  assert.ok(lines.length > 0, 'the mode armed must print something');
  const text = lines.join('\n');
  assert.match(text, /dispatch/, 'the site names appear');
  assert.match(text, /hicasso\|floor/, 'and the window is named');
  assert.match(text, /22–29/, 'with the tick range that places it in the page`s work-unit sequence');
  assert.match(text, /AGREE in 0 of 1/, 'and the hypothesis is answered on the data');
  // OFF THE MODE IT IS SILENT, so a published run's summary is unchanged to
  // the byte.
  assert.deepStrictEqual(allocSiteReport({ ...row, bySite: false }), []);
  assert.deepStrictEqual(allocSiteReport({ bySite: false, perRound: [] }), []);
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
    allocRefusedWindows(allocRowWith({ 'reagent-subs|lad/hicasso#R7': SMALL })),
    []
  );
});

test('a row with no rounds at all is not a failure', () => {
  assert.deepStrictEqual(allocRefusedWindows({ perRound: [] }), []);
});

test('every REFUSED window is named, on every round, with its reason', () => {
  const fails = allocRefusedWindows(
    allocRowWith({
      'reagent-subs|lad/hicasso#R7': MASKED,
      'reagent-subs|lad/reagent#R7': SMALL,
    })
  );
  assert.strictEqual(fails.length, 2, 'one per round, and only the refused arm');
  for (const f of fails) {
    assert.match(f, /lad\/hicasso#R7/);
    assert.match(f, /leg 3 of 4 read 0 B against a cohort median of 200000 B/);
    assert.match(f, /leg BELOW its cohort/, 'and it says what a low leg means');
  }
  assert.ok(fails.some((f) => f.startsWith('round 1 ')));
  assert.ok(fails.some((f) => f.startsWith('round 2 ')));
});

test('THE RATIO IS POSSIBLE — refusal REASONS are not counted against WINDOWS', () => {
  // rf2-xxeq. Every V1 run printed `windows refused: 17 of 12` — and 14, 13,
  // 14, 16, 13 — because the numerator was `allocRefusedWindows(...).length`,
  // one entry per refusal REASON, while the denominator counted WINDOWS. A
  // window with two deviant legs contributed two.
  //
  // CONSTRUCTED, not asserted: this window has THREE legs outside tolerance
  // against a cohort of five, so it yields three reasons and is one window.
  const THREE_BAD = stream([20000, 20000, 0, 60000, 0]);
  const three = allocSteps(THREE_BAD);
  assert.strictEqual(three.certified, false);
  assert.strictEqual(three.refusals.length, 3, 'one window, three deviant legs');

  const row = allocRowWith(
    { 'reagent-subs|lad/hicasso#R7': THREE_BAD, 'reagent-subs|lad/reagent#R7': SMALL },
    2
  );
  const reasons = allocRefusedWindows(row);
  const windows = allocRefusedWindowCount(row);
  const measured = row.perRound.reduce((a, r) => a + Object.keys(r.arms).length, 0);

  assert.strictEqual(reasons.length, 6, 'three reasons x two rounds — the old numerator');
  assert.strictEqual(windows, 2, 'and TWO windows were refused, one per round');
  assert.strictEqual(measured, 4);
  // THE DEFECT, as arithmetic rather than as an anecdote: the old numerator
  // overran its own denominator on exactly this row.
  assert.ok(reasons.length > measured, 'reasons against windows is what printed "17 of 12"');
  assert.ok(windows <= measured, 'and a window count never can');

  // The two are told apart on the row itself, and the summary prints both.
  // Two segments x two rounds = four floor windows, every one carrying the
  // same three deviant legs: four windows, twelve reasons.
  const refusedFloor = Object.fromEntries(
    ['reagent-subs', 'uix-subs'].map((seg) => [
      `${seg}|grid/floor`,
      {
        segment: seg,
        arm: 'grid/floor',
        rung: 'floor',
        reads: 0,
        boundaries: 24,
        ...three,
        primeLegs: [26044],
        primeExcess: 6788,
        perWrite: 5000,
        perBoundaryPerWrite: 208,
      },
    ])
  );
  const { row: summarised, out } = allocSummaryFor('floor', refusedFloor);
  assert.strictEqual(summarised.refusedWindows, 4, 'the record carries WINDOWS under that name');
  assert.strictEqual(summarised.refusalReasons, 12, 'and reasons under theirs');
  assert.match(out, /windows refused: 4 of 4, 12 refusal reasons in all/);
  assert.ok(
    summarised.refusedWindows <= 4,
    'the numerator can no longer overrun the denominator it is printed against'
  );
  // And the reason list itself is untouched — naming the counts apart drops
  // neither.
  assert.strictEqual((out.match(/^;;   REFUSED /gm) || []).length, 12);
  // A row with nothing wrong reads 0 of N, and singular/plural is not a bug.
  assert.strictEqual(allocRefusedWindowCount({ perRound: [] }), 0);
  assert.strictEqual(allocRefusedWindowCount(allocRowWith({ ok: SMALL })), 0);
});

// --- the wiring, so this pin cannot drift off the thing that exits ---------

test('the driver exits on THIS function and does not re-derive the verdict', () => {
  has(/const refusedWindows = allocRefusedWindows\(out\.alloc\);/, 'the alloc gate calls it');
  has(/if \(refusedWindows\.length > 0\) \{/, 'and its result is what pushes a failure');
  has(/DO NOT WIDEN THE TOLERANCE/, 'naming the repair, not the dial');
  // The nine this file drives, in order. The trailing `};` came off in
  // rf2-gxrr: it made the pin a CLOSED list, which was never its content —
  // "so this file can drive it" is a claim about what is exported, not about
  // nothing else ever being. The measurement surface's own exports are pinned
  // in their own test below.
  has(
    /module\.exports = \{\s*ladderStructuralFailures,\s*allocSteps,\s*allocRefusedWindows,(\s*\/\/[^\n]*\n)*\s*allocRefusedWindowCount,\s*ALLOC_LEG_TOLERANCE,\s*ALLOC_FALL_THRESHOLD_B,\s*ladderPlan,\s*allocArmSizing,\s*ALLOC_MIN_WRITES,\s*ALLOC_ARM,/,
    'so this file can drive it'
  );
  has(/if \(out\.alloc\.fallsInMeasuredWindows > 0\) \{/, 'and the falling-step gate still exits');
});

// ===========================================================================
// THE PAGE IS STATED, AND THE AVERAGING FLOOR IS ENFORCED — rf2-2rtt6.139/.142
// ===========================================================================
//
// WHAT THE PREFLIGHT IS FOR, now that it predicts nothing. rf2-2rtt6.139
// retired `ALLOC_B_PER_BOUNDARY_WRITE = 1655` as a sizing input — it was read
// off the 2026-08-07 run, which itself refused at 36 falling steps across 44
// windows, and that run's own refusal text declares every figure from such a
// window an under-estimate — and ruled that NO REPLACEMENT CONSTANT MAY BE
// SUBSTITUTED. Its interim posture: the preflight refuses only on grounds it
// can defend without a sizing model. Two survive.
//
//   THE PAGE IS MANDATORY. With no sizing model there is no honest default,
//   so an unstated `P0_ALLOC_CELLS` is refused by name. It has the additional
//   virtue of making an accidental publication run impossible while
//   rf2-2rtt6.140 criterion 5's measurement freeze is in force.
//
//   THE AVERAGING FLOOR STAYS (rf2-2rtt6.142). It is not a budget question:
//   a one-write window has no averaging in it whatever certifies the window,
//   and at one write per window all four ladder fits came back under the 0.98
//   r² floor — 0.75 / 0.28 / 0.94 / 0.31.
//
// NOTHING HERE MEASURES ANYTHING. The env routes are re-derived in a child
// `node` that requires the driver and prints `ALLOC_ARM` — the module
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

// ANY module constant, as a fresh process with `env` set derives it — the
// generalisation of `armUnderEnv`, and for the same reason: configuration is
// read exactly once, at require, so an env route can only be pinned from
// outside the process. `undefined` comes back as `null`, which is what an
// unknown switch resolves to and how a mistyped one is told apart from a
// defaulted one.
function constUnderEnv(name, env) {
  const r = spawnSync(
    process.execPath,
    [
      '-e',
      'process.stdout.write(JSON.stringify(require(process.argv[1])[process.argv[2]] ?? null))',
      DRIVER,
      name,
    ],
    { env: { ...process.env, ...env }, encoding: 'utf8' }
  );
  assert.strictEqual(r.status, 0, `requiring the driver must not throw: ${r.stderr}`);
  return JSON.parse(r.stdout);
}

test('ROUTE 1 — the large-root DEFAULT is gone, and an unstated page refuses', () => {
  // `P0_ROOTS=50` used to derive a 50-boundary page carrying a two-write
  // window — below the floor, inside the budget, and therefore on a
  // publication path. There is no derivation left to do it: the page is
  // stated or the run refuses.
  const arm = armUnderEnv({ P0_ROOTS: '50', P0_ALLOC_CELLS: '', P0_ALLOC_WRITES: '' });
  assert.strictEqual(arm.cells, null, 'no default is derived from the retired constant');
  assert.strictEqual(arm.boundaries, null, 'so there is no page to report');
  assert.ok(!arm.admissible);
  assert.ok(
    arm.refusals.some((r) => r.includes('STATE P0_ALLOC_CELLS')),
    `the refusal must name the page: ${JSON.stringify(arm.refusals)}`
  );
  // And the window is the floor, not something inverted out of a bound that
  // no longer exists.
  assert.strictEqual(arm.writes, ALLOC_MIN_WRITES);
});

test('ROUTE 2 — an explicit one-write window on a stated page is refused', () => {
  // rf2-2rtt6.142's pin, which must stay green. The floor is enforced against
  // `ALLOC_MIN_WRITES` and never against the literal 6, so a ruling that moves
  // the floor moves this with it.
  const arm = armUnderEnv({ P0_ALLOC_CELLS: '6', P0_ALLOC_WRITES: '1' });
  assert.strictEqual(arm.boundaries, 24, 'a stated page, unchanged');
  assert.strictEqual(arm.writes, 1);
  assert.ok(!arm.admissible);
  const floor = arm.refusals.find((r) => r.includes('averaging floor'));
  assert.ok(floor, JSON.stringify(arm.refusals));
  assert.match(floor, /RAISE P0_ALLOC_WRITES to at least 6/);
});

test('THE CONTROL — a stated page at the floor is admitted, so this is not vacuous', () => {
  // A gate that refused everything would pass both cases above and have
  // adjudicated nothing.
  const arm = armUnderEnv({ P0_ALLOC_CELLS: '6', P0_ALLOC_WRITES: '' });
  assert.strictEqual(arm.cells, 6);
  assert.strictEqual(arm.boundaries, 24);
  assert.strictEqual(arm.writes, ALLOC_MIN_WRITES, 'the window defaults to the floor');
  assert.deepStrictEqual(arm.refusals, []);
  assert.ok(arm.admissible);
});

test('the refusal names THE WINDOW, and never a page it cannot size', () => {
  // AMENDED from `the refusal names SHRINKING THE PAGE and never shrinking the
  // window`. That message BRANCHED on whether the page could carry six writes,
  // and the branch was computed from the budget; with the budget retired its
  // trigger no longer exists and the refusal collapses to one arm.
  //
  // NOT A REGRESSION, and the property rf2-2rtt6.142's mayor adjudication
  // endorsed is what has to survive: never advise the operator to configure
  // the very shape being refused. It survives because the collapsed message
  // names NO PAGE AT ALL — with no page-size model there is nothing to name.
  const arm = armUnderEnv({ P0_ALLOC_CELLS: '6', P0_ALLOC_WRITES: '1' });
  const floor = arm.refusals.find((r) => r.includes('averaging floor'));
  assert.match(floor, /RAISE P0_ALLOC_WRITES/, 'the window is the knob it names');
  assert.doesNotMatch(floor, /SHRINK THE PAGE/, 'and it may not name a page it cannot size');
  assert.doesNotMatch(floor, /boundaries/, 'nor any boundary count');
  assert.doesNotMatch(floor, /masking budget/, 'the budget is retired and may not be cited');
});

test('the floor follows ALLOC_MIN_WRITES rather than a number typed beside it', () => {
  // If a later ruling moves the averaging floor, the preflight moves with it.
  assert.ok(ALLOC_MIN_WRITES >= 1, 'a floor below one write would not be a floor');
  const page = { roots: 1, cells: 4 };
  for (let w = 0; w < ALLOC_MIN_WRITES; w++) {
    const s = allocArmSizing({ writes: w, ...page });
    assert.ok(!s.admissible, `w=${w} is below the floor and must refuse`);
    assert.ok(s.refusals.some((r) => r.includes('averaging floor')));
  }
  const atFloor = allocArmSizing({ writes: ALLOC_MIN_WRITES, ...page });
  assert.ok(atFloor.admissible, 'and exactly at the floor is admitted');
});

test('a refused arm says WHY, one reason per thing wrong with it', () => {
  // Both wrong at once: no page stated AND one write. An operator who fixed
  // only the one they were told about would come back to the other, so the two
  // checks are independent rather than nested.
  const both = allocArmSizing({ writes: 1, roots: 4, cells: null });
  assert.strictEqual(both.refusals.length, 2, JSON.stringify(both.refusals));
  assert.ok(both.refusals.some((r) => r.includes('STATE P0_ALLOC_CELLS')));
  assert.ok(both.refusals.some((r) => r.includes('averaging floor')));
  // A page STATED as zero is a different fault from a page not stated, and
  // they say so differently: one is a page with nothing on it, the other is a
  // missing configuration.
  const empty = allocArmSizing({ writes: 6, roots: 4, cells: 0 });
  assert.strictEqual(empty.refusals.length, 1);
  assert.match(empty.refusals[0], /no per-boundary quantity/);
  assert.doesNotMatch(empty.refusals[0], /STATE P0_ALLOC_CELLS/);
});

test('a window with no work in it, and a page with no boundaries, are NOT admissible', () => {
  // Neither has a per-boundary quantity to publish, and a preflight that
  // admitted them would be admitting nothing measured at all.
  assert.ok(!allocArmSizing({ writes: 0, roots: 4, cells: 6 }).admissible);
  assert.ok(!allocArmSizing({ writes: 6, roots: 4, cells: 0 }).admissible);
  assert.ok(!allocArmSizing({ writes: 0, roots: 0, cells: 0 }).admissible);
});

test('THE CHANGE ONLY EVER REFUSES MORE than rf2-2rtt6.142 shipped', () => {
  // The property this package had to preserve, checked exhaustively over the
  // grid rather than argued. rf2-2rtt6.142's shipped predicate was
  // `boundaries >= 1 && writes >= ALLOC_MIN_WRITES && headroom >= 0`; the
  // budget term is retired, so the surviving predicate is the first two. An
  // admitted arm must therefore have satisfied BOTH surviving terms — which
  // is the statement that nothing became newly admissible for any reason
  // other than the budget's removal, and the budget's removal is criterion 4.
  let admitted = 0;
  let newlyRefused = 0;
  for (let writes = 0; writes <= 40; writes++) {
    for (let cells = 0; cells <= 40; cells++) {
      for (const roots of [0, 1, 4, 50]) {
        const s = allocArmSizing({ writes, roots, cells });
        const survivingTerms = s.boundaries >= 1 && writes >= ALLOC_MIN_WRITES;
        if (s.admissible) {
          admitted++;
          assert.ok(survivingTerms, `newly admissible at W=${writes} B=${s.boundaries}`);
        } else if (survivingTerms) {
          newlyRefused++;
        }
      }
    }
  }
  assert.ok(admitted > 0, 'the sweep must still admit something');
  assert.strictEqual(
    newlyRefused,
    0,
    'and a stated page at or above the floor is admitted, so the preflight adds no new refusal ' +
      'beyond the mandatory page'
  );
  // The one genuinely NEW refusal is the unstated page, and it refuses a
  // configuration that used to be admitted — the direction criterion 4 allows.
  assert.ok(!allocArmSizing({ writes: 6, roots: 4, cells: null }).admissible);
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

  // STRENGTHENED (rf2-2rtt6.140). The retention rows publish RETAINED bytes,
  // and the write half must not move them: they still drive `:p0/write-all`
  // at the published width, `prepare!` still defaults to `per-root`, and only
  // the allocation window drives `write-page!`. Read off the sources, because
  // a driver that silently swapped the write would leave every published
  // retention figure describing a page it was not taken on.
  const HEAP = fs.readFileSync(path.join(__dirname, 'p0_heap.cljs'), 'utf8');
  const ARMS = fs.readFileSync(path.join(__dirname, 'p0_arms.cljs'), 'utf8');
  const FIXTURE = fs.readFileSync(path.join(__dirname, 'p0_fixture.cljc'), 'utf8');
  // AMENDED, NOT DELETED (rf2-gxrr). The absolute `doesNotMatch` on
  // `write-all!` was this pin's SHAPE; criterion 6's row separation is its
  // CONTENT — "a reader of any row can tell from the row which write
  // produced it" — and V1 re-measures `F_old` as its control, which a window
  // with no route to `write-all!` at all cannot do. So the pin moves from
  // ABSENCE to REACHABILITY, which is strictly more than absence asked:
  // `write-page!` is the DEFAULT arm, `write-all!` is the else-arm of a test
  // on one NAMED kind, there is exactly one such call in the namespace, and
  // both kinds run the SAME window so the control differs in the event
  // alone. The clock/bulk pin below is untouched, and the record now names
  // the write it drove — which absence could not ask for, because there was
  // only ever one write to name.
  assert.match(
    HEAP,
    /\(if all\?\s+\(arms\/write-all! @alloc-tick\)\s+\(arms\/write-page! @alloc-tick\)\)/,
    "write-page! is the alloc window's DEFAULT arm and write-all! the else-arm"
  );
  assert.strictEqual(
    (HEAP.match(/\(arms\/write-all! /g) || []).length,
    1,
    'and write-all! is reachable from exactly ONE place in this namespace'
  );
  assert.match(
    HEAP,
    /all\?\s+\(= kind "write-all"\)/,
    'gated on a NAMED measurement kind — nothing ambient, nothing derived'
  );
  assert.match(
    HEAP,
    /write\?\s+\(or \(= kind "write"\) all\?\)/,
    'and both kinds are ONE window: the control differs in the event and nothing else'
  );
  assert.match(
    HEAP,
    /\(\[segment-id\] \(prepare! segment-id per-root\)\)/,
    'and an unstated width is the published page'
  );
  assert.match(ARMS, /\(dispatch-sync! \[:p0\/write-all v\]\)/, 'the public door is untouched');
  assert.match(ARMS, /\(dispatch-sync! \[:p0\/write-page v\]\)/, 'and the new one sits beside it');
  assert.match(
    FIXTURE,
    /\(rf\/reg-event :p0\/write-all\s+\(fn \[\{:keys \[db\]\} \[_ v\]\] \{:db \(assoc db :cells \(vec \(repeat cells-n v\)\)\)\}\)\)/,
    '`:p0/write-all` is byte-identical, literal `cells-n` and all'
  );
  assert.match(
    FIXTURE,
    /\(vec \(repeat \(count \(:cells db\)\) v\)\)/,
    'and `:p0/write-page` rebuilds at the db\'s own width'
  );
  assert.match(
    FIXTURE,
    /\(mod i \(count \(:cells db\)\)\)/,
    "`:p0/fan`'s modulus is read off the db, so the width cannot live in two places"
  );
});

test('the clock and bulk rows are not moved either', () => {
  // The other half of the same property. `p0_app.cljs` drives the clock rows
  // and must still call `enter-segment!` with no width — the arity that seeds
  // the published grid to the byte.
  const APP = fs.readFileSync(path.join(__dirname, 'p0_app.cljs'), 'utf8');
  assert.match(APP, /\(arms\/enter-segment! segment\)/, 'the clock rows pass no width');
  assert.doesNotMatch(APP, /:p0\/write-page/, 'and no clock row drives the new write');
  const ARMS = fs.readFileSync(path.join(__dirname, 'p0_arms.cljs'), 'utf8');
  assert.match(
    ARMS,
    /\(\[segment\] \(enter-segment! segment fx\/cells-n\)\)/,
    'because the default arity IS the published width'
  );
});

// --- the wiring, so this pin cannot drift off the thing that refuses -------

test('the driver REFUSES a mis-configured arm before it launches a browser', () => {
  has(/if \(!ALLOC_ARM\.admissible\) \{/, 'the preflight refusal is an exit, not a warning');
  has(
    /the allocation arm is refused by its own preflight before anything is measured/,
    'and it says so before a byte is measured'
  );
  has(
    /ALLOC_ARM\.refusals/,
    'and the exit prints the reasons rather than one undifferentiated verdict'
  );
});

test('the averaging floor is ENFORCED in the sizing, not merely derived from', () => {
  // rf2-2rtt6.142, unchanged by this package. `ALLOC_MIN_WRITES` sized the
  // default page and adjudicated nothing; the verdict admitted any window from
  // one write up.
  has(/if \(writes < ALLOC_MIN_WRITES\) \{/, 'the floor is a refusal in the sizing itself');
  has(/admissible: refusals\.length === 0,/, 'and the verdict is the reasons, not a conjunction');
  lacks(
    /admissible: writes >= 1 && boundaries >= 1 && headroom >= 0,/,
    'the old verdict licensed exactly the low-averaging shape the row rules out'
  );
});

test('the window is the FLOOR and the page is STATED — neither is derived', () => {
  // Replaces `the window is DERIVED from the bound and the arm from the
  // measured cost`. Both derivations were the retired constant's arithmetic.
  has(
    /const ALLOC_WRITES = Number\(process\.env\.P0_ALLOC_WRITES \|\| ALLOC_MIN_WRITES\);/,
    'the window follows the averaging floor, not an inverted bound'
  );
  has(/const ALLOC_MIN_WRITES = 6;/, 'and a window is sized to hold averaging');
  has(
    /process\.env\.P0_ALLOC_CELLS === undefined \|\| process\.env\.P0_ALLOC_CELLS === ''/,
    'the page is stated or it is not derivable'
  );
  lacks(/const ALLOC_CELLS = Number\(\s*process\.env\.P0_ALLOC_CELLS \|\|/, 'never defaulted');
});

// ===========================================================================
// THE MEASUREMENT SURFACE (rf2-gxrr) — the two switches V1 and V3 are
// configured through
// ===========================================================================
//
// PR #7702 landed the artefacts V1–V4 judge; what it did not land is the
// surface that lets V1 and V3 be RUN, and the granted measurement window for
// rf2-2rtt6.140 refused before a browser was launched because neither shape
// could be configured. These pins are over the SURFACE, not over any figure:
// the tables and the plan filter are exported so this file DRIVES the shipped
// code rather than reading its source and hoping, and the env routes are
// derived in a fresh process because configuration is read once, at require.
//
// NOTHING HERE MEASURES ANYTHING, and nothing here moves a gate. The pins
// that hold `ALLOC_MIN_WRITES` at 6, the page mandatory and the fall
// threshold where the probes read it are above, untouched.

test('THE WRITE SELECTOR — `page` is the DEFAULT and `all` is a NAMED switch', () => {
  assert.deepStrictEqual(Object.keys(ALLOC_WRITE_SPECS), ['page', 'all'], 'two writes, no more');
  assert.strictEqual(ALLOC_WRITE_SPECS.page.kind, 'write', "the default is the page's own width");
  assert.strictEqual(ALLOC_WRITE_SPECS.page.event, ':p0/write-page');
  assert.strictEqual(ALLOC_WRITE_SPECS.all.kind, 'write-all', "and V1's control is the bulk write");
  assert.strictEqual(ALLOC_WRITE_SPECS.all.event, ':p0/write-all');

  // THE ENV ROUTE, as the shipped derivation performs it.
  assert.strictEqual(constUnderEnv('ALLOC_WRITE', { P0_ALLOC_WRITE: '' }), 'page');
  assert.deepStrictEqual(
    constUnderEnv('ALLOC_WRITE_SPEC', { P0_ALLOC_WRITE: '' }),
    ALLOC_WRITE_SPECS.page,
    'an unset switch takes the page write — the one every published row is under'
  );
  assert.deepStrictEqual(
    constUnderEnv('ALLOC_WRITE_SPEC', { P0_ALLOC_WRITE: 'all' }),
    ALLOC_WRITE_SPECS.all,
    "and V1's control is reachable by naming it"
  );
  // A MISTYPED SWITCH RESOLVES TO NOTHING rather than silently falling back
  // to the default. Silence there would publish a row under a configuration
  // nobody asked for, and the record would name a write the operator did not
  // select — the one failure a measurement instrument may not have.
  assert.strictEqual(constUnderEnv('ALLOC_WRITE_SPEC', { P0_ALLOC_WRITE: 'pge' }), null);
  has(/unknown P0_ALLOC_WRITE/, 'and the preflight refuses it BY NAME, before a browser');
});

test('THE PLAN SELECTOR — `full` is the DEFAULT; the others SUBTRACT arms', () => {
  assert.deepStrictEqual(Object.keys(ALLOC_PLAN_SHAPES), ['full', 'floor', 'controls']);
  assert.deepStrictEqual(ALLOC_PLAN_SHAPES.full, { arms: true, rungs: true, fits: true });
  assert.deepStrictEqual(ALLOC_PLAN_SHAPES.floor, { arms: true, rungs: false, fits: false });
  assert.deepStrictEqual(ALLOC_PLAN_SHAPES.controls, { arms: false, rungs: false, fits: false });

  assert.strictEqual(constUnderEnv('ALLOC_PLAN', { P0_ALLOC_PLAN: '' }), 'full');
  assert.deepStrictEqual(
    constUnderEnv('ALLOC_PLAN_SHAPE', { P0_ALLOC_PLAN: '' }),
    ALLOC_PLAN_SHAPES.full,
    'an unset switch is the published run, unchanged in every particular'
  );
  assert.deepStrictEqual(
    constUnderEnv('ALLOC_PLAN_SHAPE', { P0_ALLOC_PLAN: 'controls' }),
    ALLOC_PLAN_SHAPES.controls,
    "V3's controls-only mode is reachable by naming it"
  );
  assert.deepStrictEqual(
    constUnderEnv('ALLOC_PLAN_SHAPE', { P0_ALLOC_PLAN: 'floor' }),
    ALLOC_PLAN_SHAPES.floor,
    "and V1's floor-only mode is the same switch's middle setting, not a second mechanism"
  );
  assert.strictEqual(constUnderEnv('ALLOC_PLAN_SHAPE', { P0_ALLOC_PLAN: 'contols' }), null);
  has(/unknown P0_ALLOC_PLAN/, 'and the preflight refuses a mistyped plan BY NAME');
});

test('the narrow plans SUBTRACT arms — they add none, move none and reorder none', () => {
  const full = ladderPlan({ list: 300, grid: 6 }, 4);

  // NOT VACUOUS: the full plan is the floor plus every rung on both
  // substrates, on both segments — 1 + 2 x 5.
  assert.strictEqual(full.length, 2);
  for (const seg of full) {
    assert.deepStrictEqual(
      [...new Set(seg.arms.map((a) => a.rung))],
      ['floor', 'R0', 'R1', 'R3', 'R7', 'R20'],
      'the full plan carries the floor and every rung'
    );
    assert.strictEqual(seg.arms.length, 11, 'floor + 2 substrates x 5 rungs');
  }
  // `full` is the shipped plan ITSELF, not a copy that happens to look alike.
  assert.strictEqual(allocPlanArms(full, ALLOC_PLAN_SHAPES.full), full);

  const floor = allocPlanArms(full, ALLOC_PLAN_SHAPES.floor);
  assert.deepStrictEqual(
    floor.map((s) => s.segment),
    full.map((s) => s.segment),
    'the same segments in the same order — round parity still alternates them'
  );
  assert.deepStrictEqual(
    floor.map((s) => s.arms.map((a) => a.rung)),
    [['floor'], ['floor']],
    'and the floor alone: no rung, no candidate'
  );
  // The floor arms are the SAME OBJECTS the full plan carries — same page,
  // same opts, same key. A floor read under `floor` is the floor read under
  // `full`, or the narrow plan would be a second instrument rather than a
  // narrower run of the one instrument.
  for (const [i, s] of floor.entries()) {
    assert.strictEqual(s.arms[0], full[i].arms.find((a) => a.rung === 'floor'));
  }

  assert.deepStrictEqual(
    allocPlanArms(full, ALLOC_PLAN_SHAPES.controls),
    [],
    'and controls-only mounts nothing at all — V3 asks for no arms'
  );
});

// --- and the SUMMARY SURVIVES A NARROWED PLAN ------------------------------
//
// A mode that collects V3's controls and then throws in the row's own table
// reader has not delivered V3. `summariseAlloc` is driven here on a synthetic
// row per plan shape, with `console.log` captured — hermetic, no browser, no
// build, exactly as every other pin in this file.

// The fields `summariseAlloc` reads, and no more. `arms` is what the plan
// shape decides, so it is the parameter.
function allocSummaryFor(planName, arms = {}) {
  // The control windows carry a prime too and it costs them nothing — the
  // studio page records their worst leg deviation at <= 1% against the arms'
  // 26-46%, which is the contrast that located the term in the first place.
  const ctl = (perIter) => ({
    perIter,
    primeLegs: [20000],
    primeExcess: 0,
    ...allocSteps(stream([20000, 20000, 20000, 20000])),
  });
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
  };
  const lines = [];
  const real = console.log;
  console.log = (s) => lines.push(String(s));
  try {
    // The row's OWN refusals, derived by the shipped function rather than
    // handed in empty (rf2-xxeq): the summary's numerator and the list under
    // it are two different counts of the same row, and a fixture that passed
    // `[]` could not tell them apart.
    summariseAlloc(row, allocRefusedWindows(row));
  } finally {
    console.log = real;
  }
  return { row, out: lines.join('\n') };
}

const FLOOR_ARMS = () =>
  Object.fromEntries(
    ['reagent-subs', 'uix-subs'].map((seg) => [
      `${seg}|grid/floor`,
      {
        segment: seg,
        arm: 'grid/floor',
        rung: 'floor',
        reads: 0,
        boundaries: 24,
        ...allocSteps(stream([5000, 5000, 5000, 5000])),
        // The bead's own excess, 6,788 B, riding on a synthetic floor window.
        primeLegs: [11788],
        primeExcess: 6788,
        perWrite: 5000,
        perBoundaryPerWrite: 208,
      },
    ])
  );

test("V3's CONTROLS-ONLY summary RUNS, and states absence rather than zero", () => {
  const { row, out } = allocSummaryFor('controls');
  assert.match(out, /NO ARM WAS MOUNTED/, 'absence is stated, not left to be read as zero');
  assert.match(out, /NO FITTED LINE/, 'and no slope is reported');
  assert.doesNotMatch(out, /THE FITTED LINES/);
  assert.doesNotMatch(out, /---- reagent-subs ----/, 'no arm table at all');
  assert.match(out, /THE PLAN IS `controls`/, 'and the row names the plan it ran');
  // The controls THEMSELVES are still adjudicated — V3 is a statement about
  // exactly those windows, so a controls-only run that skipped its own
  // verdict would have collected nothing worth having.
  assert.strictEqual(row.controlVerdict.ok, true, 'the positive control still decides');
  assert.strictEqual(row.fallsInMeasuredWindows, 0);
  assert.match(out, /control D=1000: predicted 8000 B/);
  // With no arm mounted the prime is reported off the CONTROL windows, which
  // is the population a controls-only run has. Their prime costs nothing, and
  // a run that printed no prime line at all would be hiding the one term this
  // instrument now has a name for.
  assert.match(out, /prime excess over the measured cohort median: 0 B mean/);
});

test("V1's FLOOR-ONLY summary RUNS, prints the floor, and prints no rung", () => {
  const { out } = allocSummaryFor('floor', FLOOR_ARMS());
  // NOT VACUOUS: this plan DOES mount an arm, so the two modes are told apart
  // by the summary and not merely by the shape table.
  assert.doesNotMatch(out, /NO ARM WAS MOUNTED/);
  assert.match(out, /---- reagent-subs ----/, 'the arm table is printed');
  assert.match(out, /;; floor /, 'and the floor is in it');
  assert.doesNotMatch(out, /hicasso\s+\d/, 'but no rung row is');
  assert.match(out, /NO FITTED LINE/, 'and nothing is regressed through one arm');
  assert.match(out, /THE PLAN IS `floor`/);
  // THE PRIME LEG IS PRINTED, not merely excluded (rf2-oiy1). This is what
  // separates the repair from "discard leg 1": the term that made every floor
  // window uncertifiable is still measured, on every window, and is readable
  // beside the cohort it used to contaminate — which is the figure rf2-e9wr's
  // τ calibration needs.
  assert.match(out, /THE PRIME LEG \(excluded from every figure\)/);
  assert.match(
    out,
    /prime excess over the measured cohort median: 6788 B mean \[6788–6788\] across 4 windows/,
    'the excess is REPORTED, per window, in bytes'
  );
  assert.match(
    out,
    new RegExp(`The window drives ${ALLOC_WINDOW_WRITES} — the first ${ALLOC_PRIME_WRITES} is a PRIME`),
    'and the header says how many work units ran against how many are published'
  );
});

test('the DRIVER honours both switches — the write, the plan, and the record', () => {
  // The wiring, so the pins above cannot drift off the thing that runs.
  // BOTH allocWindow call sites, counted rather than merely matched. The
  // warm-up and the measured window carry identical text, so a `has` would
  // stay green with either one pinned back to a literal — and the warm-up is
  // the more dangerous of the two to lose, because a site warmed under one
  // write and measured under the other reads its settled value for neither.
  // (`b8-alloc`'s driver watched a first window read 5.3x its settled value.)
  // `ALLOC_WINDOW_WRITES` at both, since rf2-oiy1: the window drives the
  // measured writes PLUS the prime, and a warm-up shorter than the window is
  // the very defect the warm-up pass exists to avoid.
  assert.strictEqual(
    (SRC.match(
      /window\.P0H\.allocWindow\(n, k, d\),\s*\[ALLOC_WINDOW_WRITES, drain, ALLOC_WRITE_SPEC\.kind\]/g
    ) || []).length,
    2,
    'the warm-up AND the measured window both drive the SELECTED write'
  );
  lacks(/window\.P0H\.allocWindow\(n, 'write', d\)/, 'and neither is pinned to a literal kind');
  has(
    /const plan = allocPlanArms\(ladderPlan\(perRoot, ROOTS\), ALLOC_PLAN_SHAPE\);/,
    'and the plan is the shipped ladder plan narrowed by the shape'
  );
  has(
    /for \(const \{ segment \} of ALLOC_PLAN_SHAPE\.fits \? plan : \[\]\)/,
    'a plan with no rungs fits nothing'
  );
  // THE ROW NAMES ITS OWN WRITE — criterion 6's separation, which a
  // hard-coded string satisfied only because there was one write to name and
  // would have gone on naming it after the selector landed.
  has(/writeSelector: ALLOC_WRITE,/, 'the record carries the switch');
  has(
    /write: `\$\{ALLOC_WRITE_SPEC\.event\} — \$\{ALLOC_WRITE_SPEC\.note\}`,/,
    'and the write it actually drove'
  );
  has(/plan: \{ name: ALLOC_PLAN, \.\.\.ALLOC_PLAN_SHAPE \},/, 'and the plan it ran');
  lacks(
    /write: ':p0\/write-page — the grid is rebuilt at the width the mounted page reads',/,
    'the record may no longer hard-code one write for every run'
  );
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
