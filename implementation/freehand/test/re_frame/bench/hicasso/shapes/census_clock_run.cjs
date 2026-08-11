#!/usr/bin/env node
// THE CENSUS-REAL PAGES' CLOCK ROWS — driver (rf2-2rtt6.56).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs
//
// The tier-1 shape roster (rf2-2rtt6.51) authored four census-real pages on
// one shared state layer and published no timing row. This driver takes
// their MOUNT rows through the frame-settlement door `clock_run.cjs`
// established and `hd8_clock_run.cjs` re-used, and adjudicates the gated
// pair against the mount-gate amendment (rf2-2rtt6.1, recorded 2026-08-02):
// mount <= 1.10x direct UIx-on-subs, floor-normalised, same run, on the
// clock of record. THE CANONICAL WITNESS IS M1 AND STAYS M1 — these rows
// CORROBORATE the amendment's line on census-real screens; they do not and
// cannot redefine it.
//
// ## The clock, and the door — stated per the stamp discipline
//
//   PUBLISHED   Performance.getMetrics raw TaskDuration, frame-settled
//               (rAF + setTimeout) — the arm's script AND the frame it
//               caused, main-thread only, no raster/composite. CDP does
//               not document TaskDuration's semantics; this is Chromium's
//               accounting read from source (rf2-8nqsl), and the clock is
//               never called by the bare adjective "frame-inclusive".
//   DIAGNOSTIC  taskNet (TaskDuration less DevToolsCommandDuration) — a
//               FRAME-ONLY reading through this door, because every arm's
//               operation runs inside `page.evaluate` and Chromium bills
//               page script run inside a protocol command to the DevTools
//               term (rf2-yd52q, rf2-emvod).
//   DIAGNOSTIC  the in-page flushSync window (`lane/mount-arm!`'s `:ms`),
//               taken on the SAME samples.
//
//   THE DOOR    every arm, the plumb tare included:
//               `page.evaluate -> C56CLOCK.sample`, settled to the next
//               frame in-page before the promise resolves. One door, so
//               its cost is common-mode; the tare measures it and every
//               published figure subtracts it.
//
// ## The rows — mounts only, and the write refusal up front
//
//   large-template   69 article cards  1,202 elements  141 reads    1 boundary
//   feed            300 article cards  5,129 elements  603 reads  301 boundaries
//   ordinary          5 comment cards     51 elements   15 reads    7 boundaries
//
// `ordinary` is a DIFFERENT SCREEN (the article page's comment column),
// not the article list at a third size — its cards are not the other two
// rows' cards and its counts are not on their arithmetic.
//
// EVERY ROW IS A WITHIN-ROW COMPARISON, AND NOTHING HERE IS A CROSS-ROW
// ISOLATION (rf2-2rtt6.62, from the merged-PR audit of #7372/#7379). The
// large-template and feed rows were once described as one screen at two
// boundary decompositions, from which shell cost and interpreter cost
// separate. They are not: they seed 69 and 300 articles through the SAME
// element arithmetic, so cards (4.35x), elements (4.27x) and per-instance
// reads (4.28x) all move at once, against a 301x step in boundaries — the
// two middle terms lag only because the 29-element page chrome does not
// scale with the seed. Shared `card.cljs` and one-card
// canonical equality buy MARKUP PARITY, not matched workload. What this
// driver establishes is per row: within a row every arm mounts the
// identical page (canon-gated before any clock), so hicasso/uix on THAT
// page is adjudicable. Between rows it establishes ordering of measured
// numbers and nothing causal. Matching the two decompositions at one card
// count is a seed change in `census_clock_arms.cljs` plus a clock session
// on a quiet box; the cards column above is printed on every stamp so the
// confound cannot be re-derived silently.
//
// The roster's WRITE rows (shape 3's broad commit, shape 4's narrow
// commit) are REFUSED by construction on this box, with the recorded
// reasons: bulk-class rows cannot hold a difference-statistic control at
// the ~3.5% floor a magnitude needs (rf2-7iqb5, 28–48% within-block IQR),
// and the narrow class sits on the clock clamp (rf2-d2tzk fences it on
// the M1 instrument). This paragraph is the refusal.
//
// ## Controls, and what each can certify
//
//   * plumb tare — subtracted; reported.
//   * ctl-2x — the floor at TWICE the row's cards. The chrome does not
//     double, so the prediction is the row's own element arithmetic
//     (1.9759 / 1.9944 / 1.7255), not a flat 2.00 — the page computes it
//     and this driver prints predicted vs measured, adjudicated STRICT
//     (every block inside +/-25%). THAT STRICT RULE IS THE ONE rf2-8a746
//     RETIRED ON THE CLOCK, and rf2-y0pkh measured it here before deciding:
//     it costs 18.2% and 39.8% per-run false refusal on large-template and
//     feed against the clock's 90.5%, so it is RETAINED on those two rows,
//     unchanged — the arithmetic and the reasons are above `controlVerdict`.
//     rf2-jcm3p records the mount-row undershoot (1.8173x against 2.00 over
//     seven runs): an additive per-sample constant survives the tare in
//     `(PW + c)/(W + c)`, and no changed-set control can reach a mount. So
//     this control certifies page-proportional SIGNAL and bounds the
//     additive residual (printed as c); it cannot certify exactness.
//   * THE CHECK STANDARD, on the `ordinary` row only — `census_check_
//     standard.json`, v1, rf2-pzqy8. That row's element arithmetic is the
//     one prediction this instrument does NOT meet: it reads 1.2308x
//     against a predicted 1.7255x, because at 51 elements the per-sample
//     work that does not scale with the page is 68% of the reading, so
//     `(PW + c)/(W + c)` sits far below `P` with no defect anywhere. That
//     is a mis-specified CENTRE, the class rf2-8a746 diagnosed on ctl3, and
//     the repair is that ruling's part 3: a level-denominated, EMPIRICALLY
//     CALIBRATED, versioned standard whose location and dispersion limits
//     are frozen from a baseline it is not afterwards judged on. Where it
//     calibrates a row it is the adjudicator and the strict band above is
//     REPORTED; where it does not, it says so by name and the strict rule
//     decides. It is data on purpose: recalibrating edits the JSON and
//     bumps its version, never this file.
//   * THE BAND — seam.cjs's `ctl-2x / floor` per-block statistic, ceiling
//     35% on raw TaskDuration (rf2-ymi6j). A run whose band breaches has
//     NO reportable magnitude; a gated ratio whose margin to the 1.10
//     line sits inside the band is INSTRUMENT-LIMITED, not a pass.
//   * the arm-order guard, tolerance 0.35 on the raw TaskDuration samples.
//     Refusal is exit 2: repair the arm, never the guard.
//
// ## Two runs, each a self-contained browser session
//
// Spec 006 installs one adapter per process. `uix` carries the GATED pair
// (hicasso / uix); `reagent` adds Reagent-on-subs beside the gate, never
// as a second gate. Every published ratio is WITHIN one run — no figure
// composes across runs — so the runs may be invoked separately
// (C56CLOCK_ONLY) without breaking same-session pairing; the published
// shape is both runs present, each to completion.
//
// ## Exit codes
//
//   0  measured; gates passed
//   1  the run failed its own gates (build, page error, parity, quiet-box)
//   2  THE ARM-ORDER GUARD REFUSED — repair the arm, never the guard
//   3  a window's value never reached the page (unverified read-backs)
//   4  the run's reproducibility band breached seam.cjs's ceiling
//   5  the positive control did not see the change its arithmetic predicts
//
// 3, 4 and 5 are rf2-rr6do's repair. Until it, all three were computed,
// printed and written into the dataset, and none reached the exit — which
// also left prediction P4 below ("if its control or band cannot hold, the
// row publishes a REFUSAL with the reason, not a number") as a promise this
// file's own exit code did not keep. See the note above `verdict`.
//
// A NONZERO EXIT IS A RUN-LEVEL FACT AND STAYS ONE. 2, 3, 4 and 5 each mean
// "a row in this run refused" and never "every row did", so the exit refuses
// the RUN while the refusal itself belongs to the row that earned it — which
// is what the section below is about.
//
// ## Where the datasets land, and WHICH ROWS IN THEM MAY BE CITED
//
// The canonical dataset directory holds THE PUBLISHED SHAPE and nothing
// else. A run that is narrowed (C56CLOCK_ROWS / C56CLOCK_ONLY), taken at an
// overridden depth, taken `--no-build`, or taken with the quiet gate skipped
// (C56CLOCK_SKIP_QUIET) writes to a sibling `.unpublished` directory
// instead, named on stdout with the reason; an explicit C56CLOCK_DATA_DIR is
// honoured as given. See the note above `destination` — that routing is
// rf2-2rtt6.56's half of the same fail-open rf2-rr6do repaired on the exit
// path.
//
// EVERY ONE OF THOSE IS A FACT ABOUT THE RUN'S SHAPE. A gate refusal is not:
// it is a fact about ONE ROW, and P4 below says so in the instrument's own
// words — "the ROW publishes a REFUSAL with the reason, not a number". Until
// rf2-pzqy8 the refusal moved the whole run's file, so one refused row sent
// the rows that had passed every gate to `.unpublished` with it, and no
// full-shape census run could ever be canonical. Each row now carries its
// own `canonical` and `notCanonicalWhy` (see `rowPublication`), the file
// indexes them in `rowsRefused`, and a refused row travels beside the
// canonical ones with its reason attached. The run-level refusal is
// untouched: it is still the process exit, and `verdict` still names every
// offending row.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../../freehand/bench/navigate.cjs');
const { resetLaneBuildCache } = require('../../../freehand/bench/lane_cache.cjs');
const guard = require('../../../freehand/bench/order_guard.cjs');
const seamlib = require('../seam.cjs');

// THE CALIBRATED CHECK STANDARD (rf2-pzqy8) — data, never code. Recalibrating
// is editing that file and bumping its `version`; nothing here holds a limit.
const CHECK_STANDARD = require('./census_check_standard.json');

// THE CENSUS RIG'S EVIDENCE CARDINALITY (rf2-pzqy8) — how MANY control blocks
// this instrument's calibrated standard requires before it will adjudicate a
// row-run. Taken from that standard's own `evidence` design field and
// multiplied out in ONE place, so no `18` is spelled anywhere in this driver:
// re-deepening the design is editing that file, exactly like moving a limit.
//
// THIS IS NOT THE ALL-BLOCKS RULE, and the two must never be conflated. The
// strict per-block unanimity rule — `controlVerdict` below, "EVERY block
// inside the band" — is about WHERE each block falls, and its cost is the
// `p^n` arithmetic rf2-8a746 reasoned about on the hicasso clock. This is
// about HOW MANY blocks exist at all, and it is a precondition of the
// calibrated standard that REPLACED unanimity as the `ordinary` row's
// adjudicator. Retiring unanimity and requiring the whole evidence are
// orthogonal, and on this rig they ALREADY coexist — which is the proof that
// they are two rules and not one. A reader arriving from the clock-side
// instrument should read this `18` as the census design's 6 x 3, never as the
// `p^18` exponent in that rule's false-refusal arithmetic.
const EXPECTED_READINGS = CHECK_STANDARD.evidence.rounds * CHECK_STANDARD.evidence.blocks;

const IMPL = path.resolve(__dirname, '../../../../../..');
const REPO = path.resolve(IMPL, '..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.C56CLOCK_OUT_DIR || 'out/census-clock';
const INIT_FN = 're-frame.bench.hicasso.shapes.census-clock-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.C56CLOCK_PORT || 8143);

// The published design — `clock_run.cjs`'s own depth: 6 rounds x 3 blocks
// x (4 warmup + 10 samples) per arm, 18 blocks for the band (the shape
// rf2-ymi6j's ceiling was calibrated on). rf2-2rtt6.31's first cut proved
// a shallower design manufactures INSTRUMENT-LIMITED verdicts; this is
// the repaired shape. A run with any of these overridden prints the
// override in its provenance and is NOT the published shape.
const ROUNDS = Number(process.env.C56CLOCK_ROUNDS || 6);
const BLOCKS = Number(process.env.C56CLOCK_BLOCKS || 3);
const WARMUP = Number(process.env.C56CLOCK_WARMUP || 4);
const SAMPLES = Number(process.env.C56CLOCK_SAMPLES || 10);
const TOLERANCE = Number(process.env.C56CLOCK_TOLERANCE || 0.35);
const CONTROL_SLACK = 0.25;
const GATE_LINE = 1.1; // the amendment's one line: hicasso <= 1.10x direct UIx
const NO_BUILD = process.argv.includes('--no-build');
const SKIP_QUIET = process.env.C56CLOCK_SKIP_QUIET === '1';

// The published depth, in ONE place. The provenance stamp and the dataset
// write path must agree on what "the published shape" is, and two copies of
// that predicate is how they drift apart.
const PUBLISHED_DEPTH = { rounds: 6, blocks: 3, warmup: 4, samples: 10 };
const depthIsPublished = () =>
  ROUNDS === PUBLISHED_DEPTH.rounds &&
  BLOCKS === PUBLISHED_DEPTH.blocks &&
  WARMUP === PUBLISHED_DEPTH.warmup &&
  SAMPLES === PUBLISHED_DEPTH.samples;

const DATA_DIR_OVERRIDDEN = Boolean((process.env.C56CLOCK_DATA_DIR || '').trim());
const DATA_DIR =
  process.env.C56CLOCK_DATA_DIR || path.join(__dirname, '..', 'data', 'censusclock-2rtt6-56');

const ALL_RUNS = [
  { id: 'uix', query: '?adapter=uix', why: 'the GATED pair — hicasso against direct UIx-on-subs, within one process' },
  { id: 'reagent', query: '?adapter=reagent', why: 'plus stock Reagent-on-subs, co-instrumented (NOT a second gate)' },
];
const ONLY = (process.env.C56CLOCK_ONLY || '').trim();
const RUNS = ONLY ? ALL_RUNS.filter((r) => ONLY.split(',').includes(r.id)) : ALL_RUNS;

const ALL_ROWS = ['large-template', 'feed', 'ordinary'];
const ROWS_ONLY = (process.env.C56CLOCK_ROWS || '').trim();
const ROWS = ROWS_ONLY ? ALL_ROWS.filter((r) => ROWS_ONLY.split(',').includes(r)) : ALL_ROWS;
const PLUMB = 'plumb';
const FLOOR = 'floor';
const CTL = 'ctl-2x';

// The gated pair — numerator over denominator, both in the SAME run. The
// direct-UIx anchor is the amendment's; the Reagent pairs are
// co-instrumented and reported beside the gate, never as a second gate.
const GATED = [['hicasso', 'uix']];
const BESIDE = {
  uix: [],
  reagent: [
    ['hicasso', 'reagent'],
    ['uix', 'reagent'],
  ],
};

// What each arm reads on each row — printed on the stamp, because the
// substrates do NOT meet the roster's boundary variable equally and a row
// that hid that would be quoting a comparison it is not making.
//
// `cards` is on the stamp for the same reason (rf2-2rtt6.62): it is the
// term that makes large-template and feed incomparable to each other, and
// it was the one count the original stamp left off — which is precisely
// how "the same screen at two boundary decompositions" survived review.
const STAMP = {
  'large-template': {
    cards: '69 article cards',
    elements: 1202,
    boundaries: { hicasso: 1, uix: 1, reagent: 1 },
    reads: {
      hicasso: '141 per-instance (collector, inside a for inside a helper)',
      reagent: '141 per-instance (reactions deref in the same positions)',
      uix: '5 coarse (order/articles/tags/flag/pending at fixed hook sites — no one-boundary hook surface can spell 141 per-instance reads)',
    },
  },
  feed: {
    cards: '300 article cards',
    elements: 5129,
    boundaries: { hicasso: 301, uix: 301, reagent: 301 },
    reads: {
      hicasso: '603 per-instance (3 page + 2 per card)',
      reagent: '603 per-instance',
      uix: '603 per-instance (the census read pair, per-card component)',
    },
  },
  ordinary: {
    cards: '5 comment cards (a DIFFERENT screen — not the article list at a third size)',
    elements: 51,
    boundaries: { hicasso: 7, uix: 7, reagent: 7 },
    reads: {
      hicasso: '15 (delete-status read inside (when mine? …))',
      reagent: '15 (same conditional deref)',
      uix: '18 (a hook cannot sit in a branch; delete-status read on every card)',
    },
  },
};

// ---------------------------------------------------------------------------
// Small statistics — ranges, never a bare mean
// ---------------------------------------------------------------------------

const r4 = (x) => Math.round(x * 10000) / 10000;
const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

function band(xs) {
  return {
    mean: r4(xs.reduce((a, b) => a + b, 0) / xs.length),
    min: r4(Math.min(...xs)),
    max: r4(Math.max(...xs)),
  };
}

/** Linear-interpolated quantile, the definition `robustScale` is stated on. */
function quantile(xs, q) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  const h = (v.length - 1) * q;
  const lo = Math.floor(h);
  const hi = Math.ceil(h);
  return v[lo] + (v[hi] - v[lo]) * (h - lo);
}

/**
 * A ROW-RUN'S DISPERSION, ROBUSTLY. `IQR / 1.349` is the normal-consistent
 * scale estimator, so its number is comparable with a standard deviation
 * while not being movable by one wild block — which matters for the same
 * reason rf2-8bgqq moved a ratio's headline to its median: a run rule that a
 * single extreme block can trip is the all-blocks rule wearing a summary
 * statistic's clothes.
 */
const robustScale = (xs) => (quantile(xs, 0.75) - quantile(xs, 0.25)) / 1.349;

/**
 * The run-rejection rule: EVERY block inside the tolerance band.
 *
 * rf2-8a746 RETIRED this rule on the hicasso clock, and rf2-y0pkh asked
 * whether it should be retired here too. It should not, and the reason is a
 * measurement rather than a shape. `1 - p^n` on a per-block in-band rate `p`
 * is a property of the RULE and not of the clock, so what decides the answer
 * is `p` — and this rig's `p` is not that rig's.
 *
 * MEASURED over the 30 committed row-runs (540 blocks) in
 * `../data/censusclock-{2rtt6-56,6c237,cno31,jv36i,y1jkm}` — five sessions at
 * five commits — at this design's n = 18 blocks per row-run:
 *
 *     row              per-block in band    1 - p^18    runs passed
 *     large-template   178/180 = 98.9%        18.2%       8 of 10
 *     feed             175/180 = 97.2%        39.8%       7 of 10
 *     ordinary          56/180 = 31.1%       100.0%       0 of 10
 *
 * On the two rows that carry the gated pair the arithmetic and the empirical
 * rate agree (exact binomial two-sided p = 1.000 and 0.749), and 18–40% is a
 * rate a run survives — against the 90.5% empirical per-run false refusal
 * that retired the clock's. THE RULE IS RETAINED HERE ON MEASURED GROUNDS.
 *
 * The rig does not inherit the clock's ill-conditioning because this control
 * is ALREADY LEVEL-DENOMINATED, which is the class rf2-8a746's ruling
 * endorses: the tared floor reads 10.72 / 41.00 ms with a between-block SD of
 * 2.55 / 8.82, i.e. 4.20 / 4.65 sigma from zero, against the retired ctl3
 * denominator's 2.09. Block IQR is 7.7% of the centre on both rows.
 *
 * `ordinary` refuses 10 of 10 and THIS RULE IS NOT WHY. Its block median is
 * 1.2264x against a predicted 1.7255x — 71.1% of P, and 5.2% BELOW the band's
 * lower edge of 1.2941 — so the centre sits outside the band the rule
 * enforces, and relaxing the rule cannot reach it: allowing up to three
 * out-of-band blocks per run still passes 0 of 10. That is a mis-specified
 * CENTRE, the same defect class rf2-8a746 diagnosed on ctl3, and it is
 * rf2-pzqy8's to rule on — together with the fact that this row's failure
 * refuses the whole RUN (`verdict` below) where prediction P4 promised a
 * refusal of the ROW. Retiring a rule that is not the binding constraint
 * would loosen a control on the two rows where it is doing real work.
 *
 * Every figure above recomputes from the committed datasets and is pinned by
 * `../clock_exit_path.test.cjs`, which drives `controlBlocks` and this
 * function rather than a copy of their arithmetic.
 */
function controlVerdict(predicted, per, slack) {
  const lo = predicted * (1 - slack);
  const hi = predicted * (1 + slack);
  const b = band(per);
  return {
    predicted: r4(predicted),
    band: [r4(lo), r4(hi)],
    measured: b,
    perBlock: per.map(r4),
    ok: per.every((x) => x >= lo && x <= hi),
    rule: 'strict — EVERY block inside the band (rf2-y0pkh: measured and retained)',
  };
}

/**
 * THE CALIBRATED CHECK STANDARD, applied to one row-run (rf2-pzqy8) — or
 * `null` when `census_check_standard.json` does not calibrate that row.
 *
 * ## Why one row has a standard and two do not
 *
 * `ctl-2x` builds twice the row's cards and the page states the row's own
 * element arithmetic as the prediction. On `large-template` and `feed` the
 * instrument MEETS it — run-median centres 1.8433 and 2.0146 against 1.9759
 * and 1.9943 — and rf2-y0pkh measured the strict rule on those two rows at
 * 18.2% and 39.8% per-run false refusal and retained it. Nothing there needs
 * repairing, and seeding a standard for a row whose prediction already holds
 * would replace a working control with a copied one.
 *
 * `ordinary` is the row that does not. It reads 1.2308x against a predicted
 * 1.7255x — 71.3% of P, and BELOW the strict band's own lower edge of 1.2941
 * rather than inside it — so every one of its ten committed row-runs refused,
 * and rf2-y0pkh measured that no relaxation of the block count reaches it
 * (0, 1, 2 or 3 out-of-band blocks allowed: 0 of 10 either way, its pooled
 * block median 1.2264 sitting 5.2% under that edge). The arithmetic is not
 * wrong anywhere; the row is small. `R = (P*W + c)/(W + c)`, and at 51
 * elements the tared floor is 1.41 ms of which `c = 0.96 ms` — 68% — does
 * not scale with the page, so the reading sits far below `P` by
 * construction. That is a mis-specified CENTRE, the class rf2-8a746
 * diagnosed on ctl3 (whose true centre sat 2.6% ABOVE its refusal edge
 * where this row's sits below its own), and
 * its ruling's part 3 is the repair: a level-denominated, EMPIRICALLY
 * CALIBRATED, versioned standard rather than a theoretical value asserted
 * against a reading it does not describe.
 *
 * ## And why its run rule is location + dispersion
 *
 * Because re-centring alone does not reach it either. About the empirical
 * centre the per-block in-band fraction rises from 31.1% to 90.0% and the
 * all-blocks rule would still pass only 3 of 10, because `0.90^18 = 15%` —
 * rf2-8a746's `p^n` arithmetic, on this rig, at this n. So on a calibrated
 * row the tolerance band is REPORTED and the run rule is the standard's:
 * the row-run's block median inside frozen location limits AND its robust
 * scale at or under a frozen dispersion limit. THE STRICT RULE IS NOT
 * TOUCHED WHERE rf2-y0pkh MEASURED AND RETAINED IT — on the two rows above
 * it is still the adjudicator, with the same band and the same wording.
 *
 * ## And the evidence has to be all there
 *
 * The limits are statistics OF 18-block row-runs — the between-run SD the
 * location limits are three of, and the lognormal fit the dispersion limit
 * caps, are both taken over row-runs of exactly `evidence.rounds x
 * evidence.blocks`. So that count is a PRECONDITION and not a description.
 * Truncated capture evidence judged against these limits is a different
 * quantity judged against the wrong sampling distribution, and at `n = 1` it
 * is not a judgement at all: the robust scale of one reading is 0, so the
 * dispersion rule cannot fail, and a single block sitting at the centre
 * reported `ok: true` with `n: 1` — which `controlAdjudication` then made the
 * gate verdict, citing a row as in control on one block. MISSING OR EXTRA
 * BLOCKS REFUSE, with the observed and the expected count, BEFORE either
 * statistic is computed.
 *
 * FAIL CLOSED AT EVERY SEAT: a row-run with no blocks, one with fewer or more
 * than the declared evidence, a block that is not a finite reading, and a row
 * the standard declares `calibrated: false` are each a REFUSAL and never a
 * pass. A row that appears in NEITHER list is a fault and THROWS — the
 * standard is complete over the roster by construction, so a row it has never
 * heard of may not be adjudicated by it or waved past it.
 */
function checkStandardVerdict(rowId, per) {
  const spec = CHECK_STANDARD.rows[rowId];
  if (!spec) {
    if ((CHECK_STANDARD.notInThisStandard.rows || []).includes(rowId)) return null;
    throw new Error(
      `row \`${rowId}\` is in neither \`rows\` nor \`notInThisStandard\` of ${CHECK_STANDARD.id} ` +
        `v${CHECK_STANDARD.version} — a row this standard has never heard of can be neither adjudicated ` +
        `by it nor waved past it (${CHECK_STANDARD.bead})`
    );
  }

  const xs = Array.isArray(per) ? per : [];
  const finite = xs.filter(Number.isFinite);
  const out = {
    standard: { id: CHECK_STANDARD.id, version: CHECK_STANDARD.version, bead: CHECK_STANDARD.bead },
    rowId,
    calibrated: spec.calibrated === true,
    rule: CHECK_STANDARD.runRejection,
    measured: {
      n: xs.length,
      finite: finite.length,
      expected: EXPECTED_READINGS,
      p50: r4(p50(finite)),
      scale: r4(robustScale(finite)),
    },
    location: null,
    dispersion: null,
    tolerance: null,
    errorRates: spec.errorRates || null,
    ok: false,
    why: null,
  };

  if (!out.calibrated) {
    out.why = `the \`${rowId}\` row of the check standard is NOT CALIBRATED — ${spec.why || 'nothing has established what this instrument reads on it'}`;
    return out;
  }
  if (finite.length !== xs.length) {
    out.why = `${xs.length - finite.length} of ${xs.length} blocks are not finite readings of a level ratio`;
    return out;
  }
  // THE CARDINALITY PRECONDITION, before either statistic exists to be read.
  if (finite.length !== EXPECTED_READINGS) {
    const short = EXPECTED_READINGS - finite.length;
    out.why =
      `${finite.length} finite blocks where this standard's evidence is ${EXPECTED_READINGS} ` +
      `(${CHECK_STANDARD.evidence.rounds} rounds x ${CHECK_STANDARD.evidence.blocks} blocks) — ` +
      (finite.length === 0
        ? 'no blocks at all, and an empty block set is an absent reading, not a row-run in control'
        : short > 0
          ? `${short} MISSING. These limits are the location and dispersion OF ${EXPECTED_READINGS}-block ` +
            'row-runs, so a statistic over truncated evidence is a different quantity judged against the ' +
            'wrong sampling distribution'
          : `${-short} EXTRA. A row-run deeper than the declared design is not the run these limits were ` +
            'calibrated on either, and a standard that adjudicates any depth has an unmeasured rate');
    return out;
  }

  const [lo, hi] = spec.location.limits;
  const median = p50(finite);
  const scale = robustScale(finite);
  const [tlo, thi] = spec.tolerance.band;
  const inBand = finite.filter((x) => x >= tlo && x <= thi).length;

  out.location = {
    statistic: CHECK_STANDARD.statistics.location,
    centre: spec.centre,
    limits: [lo, hi],
    measured: r4(median),
    ok: median >= lo && median <= hi,
  };
  out.dispersion = {
    statistic: CHECK_STANDARD.statistics.dispersion,
    limit: spec.dispersion.limit,
    measured: r4(scale),
    ok: scale <= spec.dispersion.limit,
  };
  // REPORTED AND NOT A RULE. It is printed because a reader wants to see
  // where the blocks fell; nothing below reads `inBand` into `ok`.
  out.tolerance = {
    band: [tlo, thi],
    inBand,
    of: finite.length,
    fraction: r4(inBand / finite.length),
    gating: false,
  };
  out.ok = out.location.ok && out.dispersion.ok;
  out.why = out.ok
    ? null
    : [
        out.location.ok
          ? null
          : `the row-run's block median ${r4(median)}x is outside the frozen location limits ` +
            `[${lo} – ${hi}] about an EMPIRICAL centre of ${spec.centre}x`,
        out.dispersion.ok
          ? null
          : `the row-run's robust scale ${r4(scale)} exceeds the frozen dispersion limit ${spec.dispersion.limit} ` +
            `— the box could not reproduce identical work block to block`,
      ]
        .filter(Boolean)
        .join('; ');
  return out;
}

/**
 * THE ROW'S CONTROL VERDICT, and the one field a gate may read.
 *
 * Both adjudicators are computed and both are recorded, so a reader can see
 * the mis-specification rather than take its repair on trust: `strictOk` is
 * the all-blocks rule's own answer about the row's element arithmetic, and
 * `standard` is the calibrated standard's, or `null` where the row has none.
 * `ok` is whichever of the two ADJUDICATES the row, and `adjudicator` names
 * it. One field, one gate, one seat — `summarise` reads `ok` and nothing
 * else, exactly as it did before rf2-pzqy8.
 */
function controlAdjudication(rowId, predicted, per, slack) {
  const strict = controlVerdict(predicted, per, slack);
  const standard = checkStandardVerdict(rowId, per);
  return {
    ...strict,
    strictOk: strict.ok,
    standard,
    adjudicator: standard
      ? `the calibrated check standard \`${standard.standard.id}\` v${standard.standard.version} (${standard.standard.bead}); the band above is REPORTED`
      : 'the strict all-blocks rule above, about the row\'s own element arithmetic (rf2-y0pkh: measured and retained)',
    ok: standard ? standard.ok : strict.ok,
  };
}

/** The lines a report prints for a check-standard verdict; none when there is no standard. */
function formatCheckStandard(v) {
  if (!v) return [];
  const lines = [
    `;;   ${v.ok ? 'IN CONTROL' : 'REFUSED   '} check standard \`${v.standard.id}\` v${v.standard.version} ` +
      `[${v.rowId}${v.calibrated ? '' : ', UNCALIBRATED'}] (${v.standard.bead})`,
  ];
  if (v.location && v.dispersion) {
    lines.push(
      `;;     location   ${v.location.measured}x median of ${v.measured.finite} blocks against ` +
        `[${v.location.limits[0]} – ${v.location.limits[1]}] about an EMPIRICAL centre ${v.location.centre}x ` +
        `— ${v.location.ok ? 'inside' : 'OUTSIDE'}`
    );
    lines.push(
      `;;     dispersion ${v.dispersion.measured} robust scale (IQR/1.349) against a limit of ` +
        `${v.dispersion.limit} — ${v.dispersion.ok ? 'inside' : 'OVER'}`
    );
    lines.push(
      `;;     tolerance  ${v.tolerance.inBand} of ${v.tolerance.of} blocks inside [${v.tolerance.band[0]} – ` +
        `${v.tolerance.band[1]}] — REPORTED, and it decides nothing: about this centre the all-blocks rule ` +
        `would pass 3 of 10 row-runs, because 0.90^18 = 15%`
    );
  }
  if (v.why) lines.push(`;;     why        ${v.why}`);
  return lines;
}

/**
 * c recovered from the doubling ratio when the control builds P times the
 * work (P is the row's element arithmetic, not 2.00):
 * floor = W + c, ctl = PW + c  =>  R = P - (P - 1) c / floorTared
 * =>  c = floorTared (P - R) / (P - 1).
 */
const additiveConstant = (floorTared, ratio, predicted) =>
  (floorTared * (predicted - ratio)) / (predicted - 1);

// ---------------------------------------------------------------------------
// Provenance — commit, blobs, and the run's own design
// ---------------------------------------------------------------------------

function sh(cmd, args) {
  return spawnSync(cmd, args, { cwd: REPO, encoding: 'utf8' });
}

function revision() {
  const r = sh('git', ['rev-parse', 'HEAD']);
  return r.status === 0 ? r.stdout.trim() : 'unknown';
}

// The measured pages' own sources are pinned alongside the instrument's:
// a sibling branch is editing the shapes tree, and a row nobody can tie
// to the exact page it mounted is rf2-cvvb7's recorded fault.
const BLOB_FILES = [
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_arms.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_app.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/model.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/card.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/large_template.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/feed.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/shapes/ordinary.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/arm1/runtime.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/arm1/lang.clj',
  'implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/lane.cljs',
  'implementation/core/src/re_frame/substrate/spine.cljs',
];

function blobs() {
  const out = {};
  for (const f of BLOB_FILES) {
    // `git hash-object` on the WORKING file, so an uncommitted instrument
    // edit stamps the run with the blob that actually ran.
    const r = sh('git', ['hash-object', f]);
    out[f] = r.status === 0 ? r.stdout.trim() : 'unknown';
  }
  return out;
}

// ---------------------------------------------------------------------------
// The quiet-box gate — verified, not asserted
// ---------------------------------------------------------------------------

function cpuSamples(n) {
  const gc = spawnSync(
    'powershell',
    [
      '-NoProfile',
      '-Command',
      `(Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 1 -MaxSamples ${n}).CounterSamples | ForEach-Object { [math]::Round($_.CookedValue,1) }`,
    ],
    { encoding: 'utf8', timeout: (n + 20) * 1000 }
  );
  if (gc.status === 0) {
    const xs = gc.stdout.split(/\r?\n/).map((s) => Number(s.trim())).filter(Number.isFinite);
    if (xs.length >= n) return xs.slice(0, n);
  }
  const xs = [];
  for (let i = 0; i < n; i++) {
    const r = spawnSync(
      'powershell',
      ['-NoProfile', '-Command', '(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average'],
      { encoding: 'utf8', timeout: 20000 }
    );
    const v = Number((r.stdout || '').trim());
    if (Number.isFinite(v)) xs.push(v);
  }
  return xs;
}

function quietGate(label) {
  if (SKIP_QUIET) {
    console.log(`;; quiet    ${label}: SKIPPED (C56CLOCK_SKIP_QUIET=1) — NOT the published shape`);
    return { ok: true, skipped: true, samples: [] };
  }
  const THRESH = 30;
  const NEED = 8;
  for (let attempt = 1; attempt <= 5; attempt++) {
    const xs = cpuSamples(NEED);
    const ok = xs.length >= NEED && xs.every((x) => x < THRESH);
    console.log(
      `;; quiet    ${label}: attempt ${attempt} — CPU [${xs.map((x) => x.toFixed(0)).join(', ')}]% ` +
        `(${NEED} consecutive < ${THRESH}% required) ${ok ? 'QUIET' : 'NOT QUIET'}`
    );
    if (ok) return { ok: true, samples: xs, attempt };
    spawnSync('powershell', ['-NoProfile', '-Command', 'Start-Sleep -Seconds 15'], { timeout: 30000 });
  }
  return { ok: false, samples: [] };
}

// ---------------------------------------------------------------------------
// Build + serve
// ---------------------------------------------------------------------------

const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` + `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[c56clock] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[c56clock] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(process.execPath, [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE], {
    cwd: IMPL,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (r.status !== 0) {
    console.error(`[c56clock] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>census-real clock rows</title></head>' +
      '<body><div id="app"></div><script src="main.js"></script></body></html>'
  );
  return http
    .createServer((req, res) => {
      const rel = decodeURIComponent(req.url.split('?')[0]);
      const file = path.join(OUT, rel === '/' ? 'index.html' : rel);
      if (!file.startsWith(OUT) || !fs.existsSync(file)) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    })
    .listen(PORT);
}

// ---------------------------------------------------------------------------
// The instrument: Chrome's own renderer counters
// ---------------------------------------------------------------------------

const METRICS = [
  'TaskDuration',
  'ScriptDuration',
  'LayoutDuration',
  'RecalcStyleDuration',
  'DevToolsCommandDuration',
  'LayoutCount',
  'RecalcStyleCount',
];

async function readMetrics(cdp) {
  const { metrics } = await cdp.send('Performance.getMetrics');
  const out = {};
  for (const m of metrics) if (METRICS.includes(m.name)) out[m.name] = m.value;
  return out;
}

function deltaOf(a, b) {
  const d = {};
  for (const k of METRICS) d[k] = (b[k] || 0) - (a[k] || 0);
  const task = d.TaskDuration * 1000;
  const devtools = d.DevToolsCommandDuration * 1000;
  return {
    task, // PUBLISHED — raw TaskDuration, script AND frame
    taskNet: task - devtools, // DIAGNOSTIC — frame-only through this door
    devtools,
    script: d.ScriptDuration * 1000,
    layout: d.LayoutDuration * 1000,
    style: d.RecalcStyleDuration * 1000,
    layoutCount: d.LayoutCount,
    styleCount: d.RecalcStyleCount,
  };
}

/**
 * The nine quantities ONE block's decomposition accumulator carries, named
 * once. `bump` creates them at collection, `datasetFor` serialises them, and
 * `foldDecomposition` both sums them and requires exactly these — three
 * readers over one list, so a field cannot be collected and then silently not
 * be required, which is how the split came to be dropped in the first place.
 */
const DECOMP_FIELDS = ['n', 'task', 'taskNet', 'devtools', 'script', 'style', 'layout', 'layoutCount', 'inPage'];
const zeroDecomp = () => Object.fromEntries(DECOMP_FIELDS.map((k) => [k, 0]));

// ---------------------------------------------------------------------------
// One row of one run
// ---------------------------------------------------------------------------

async function runRow(browser, runDef, rowId) {
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => {
    pageErrors.push(e.message);
    console.error('[c56clock] PAGE ERROR:', e.message);
  });
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[c56clock]')) console.log(t);
  });

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');

  await navigate(page, `http://127.0.0.1:${PORT}/${runDef.query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the wait for window.C56CLOCK_READY',
  });
  await page.waitForFunction('window.C56CLOCK_READY === true', null, { timeout: 180000 });

  // The whole parity gate ran at boot; refuse the run if it failed.
  const parity = await page.evaluate('window.C56CLOCK_PARITY');
  if (!parity || !parity.ok) {
    await page.close();
    throw new Error(
      `parity gate failed at boot for ${runDef.id}: ${JSON.stringify(parity && parity.problems)}` +
        (parity && !parity.canFail ? ' (and/or the comparison could not answer false)' : '')
    );
  }

  const plan = await page.evaluate((r) => window.C56CLOCK.plan(r), rowId);
  const armIds = plan.map((a) => a.id);
  const k = armIds.length;
  const ctlPredicted = await page.evaluate((r) => window.C56CLOCK.ctlPredicted(r), rowId);

  const canon = {};
  for (const a of armIds) {
    canon[a] = await page.evaluate(([r, arm]) => window.C56CLOCK.canon(r, arm), [rowId, a]);
  }

  const blocksTask = [];
  const blocksNet = [];
  const blocksInPage = [];
  const blocksDecomp = []; // per block: the renderer's own Script/Layout/Style split
  const samplesTask = []; // arm-order guard, on the clock of record
  const samplesNet = []; // the same guard on the diagnostic clock
  const granularity = new Set();
  let position = 0;
  let previous = null;

  const bump = (into, arm, d, inPage) => {
    const acc = (into[arm] ||= zeroDecomp());
    acc.n += 1;
    acc.task += d.task;
    acc.taskNet += d.taskNet;
    acc.devtools += d.devtools;
    acc.script += d.script;
    acc.style += d.style;
    acc.layout += d.layout;
    acc.layoutCount += d.layoutCount;
    acc.inPage += Number.isFinite(inPage) ? inPage : 0;
  };

  for (let round = 0; round < ROUNDS; round++) {
    const roundTask = [];
    const roundNet = [];
    const roundInPage = [];
    const roundDecomp = [];
    for (let blk = 0; blk < BLOCKS; blk++) {
      const accT = {};
      const accN = {};
      const accI = {};
      const accD = {};
      for (const a of armIds) {
        accT[a] = [];
        accN[a] = [];
        accI[a] = [];
      }
      for (let s = 0; s < WARMUP + SAMPLES; s++) {
        for (const j of guard.schedule(k, s)) {
          const armId = armIds[j];
          const m0 = await readMetrics(cdp);
          const res = await page.evaluate(([r, arm]) => window.C56CLOCK.sample(r, arm), [rowId, armId]);
          const m1 = await readMetrics(cdp);
          const d = deltaOf(m0, m1);
          if (d.task > 0) granularity.add(d.task);
          // AFTER the counters: verify + unmount, never billed to the mount.
          const reaped = await page.evaluate((r) => window.C56CLOCK.reap(r), rowId);
          const ok = res.ok && reaped.ok;
          if (!ok) {
            // Adjudicated by the tally at the end of the row.
          }
          if (s >= WARMUP) {
            accT[armId].push(d.task);
            accN[armId].push(d.taskNet);
            accI[armId].push(res.inPageMs);
            bump(accD, armId, d, res.inPageMs);
            samplesTask.push({ arm: armId, value: d.task, predecessor: previous, position });
            samplesNet.push({ arm: armId, value: d.taskNet, predecessor: previous, position });
            position += 1;
          }
          previous = armId;
        }
      }
      roundTask.push(accT);
      roundNet.push(accN);
      roundInPage.push(accI);
      roundDecomp.push(accD);
    }
    blocksTask.push(roundTask);
    blocksNet.push(roundNet);
    blocksInPage.push(roundInPage);
    blocksDecomp.push(roundDecomp);
  }

  const tally = await page.evaluate('window.C56CLOCK.tally()');
  const td = await page.evaluate('window.C56CLOCK.teardownCheck()');
  const runtime = await page.evaluate('window.C56CLOCK.runtime()');
  await page.close();
  if (td.length > 0) throw new Error(`teardown FAILED in ${runDef.id}/${rowId}: ${td.join(', ')}`);
  if (pageErrors.length > 0) throw new Error(`page errors in ${runDef.id}/${rowId}: ${pageErrors.join('; ')}`);

  return {
    runId: runDef.id, rowId, armIds, plan, canon, ctlPredicted,
    blocksTask, blocksNet, blocksInPage, blocksDecomp,
    samplesTask, samplesNet, tally, runtime,
    granularity: [...granularity].sort((a, b) => a - b),
  };
}

/**
 * The row's decomposition, folded out of the per-block accumulators — one
 * `{n, task, taskNet, devtools, script, style, layout, layoutCount, inPage}`
 * per arm, summed over every block of every round.
 *
 * The blocks are what is COLLECTED and what is STORED; this is the only
 * derived form, so the mean the report prints and the ratio the studio page
 * quotes are both functions of the dataset rather than of a number that was
 * true once in a console. Addition is associative, so folding the stored
 * blocks reproduces the row exactly — `../clock_exit_path.test.cjs` pins that.
 *
 * It REFUSES a row that carries no blocks rather than folding to zeros, because
 * every census dataset written before rf2-jo60g is exactly that row, and a fold
 * that answered there would hand a reader a NaN — or worse, a plausible number
 * — for a split those files never recorded.
 *
 * AND IT REFUSES A PARTIAL ONE (merged-PR audit #7666). The first landing
 * checked only the OUTER array and then summed with `acc[k] += a[k] || 0`, so
 * `[[]]` and `[[{}]]` both answered `{}`, and a missing, null or NaN metric was
 * silently synthesised as zero. An arm that had lost half its fields therefore
 * folded to a plausible ratio instead of a refusal — the same fail-open one
 * level down, and the one this bead exists to close. `|| 0` is the defect, so
 * the repair is to REQUIRE the shape rather than to default it:
 *
 *   - every round carries at least one block, and every block at least one arm;
 *   - every arm carries every one of `DECOMP_FIELDS`, each a FINITE number —
 *     absent, `null`, `undefined` and `NaN` are refusals, not zeros;
 *   - `n` is positive (a block that took no samples is not evidence) and
 *     `layoutCount` is non-negative (it tallies a monotone renderer counter).
 *
 * Durations are only required to be finite: `plumb` legitimately lays nothing
 * out and reads 0, and `taskNet` subtracts the devtools window so it may read
 * below zero. The refusal names the round, the block, the arm and the field, so
 * a reader can go to the offending side of the ratio and see what is wrong.
 *
 * AND IT MEASURES COMPLETENESS AGAINST THE ROW'S DECLARED SHAPE, not against
 * the evidence itself (merged-PR audit #7681, rf2-e1tko). That landing required
 * "the same arm roster as the row's FIRST BLOCK" and counted no rounds or
 * blocks at all, so the evidence certified itself: a row with its final round
 * removed, a row with one block removed, and a row with an arm removed from
 * EVERY block were all accepted, each folding to internally consistent
 * survivors and a plausible aggregate. The third is the sharp one — a roster
 * read off block 1 cannot see an arm that block 1 has also lost, so uniform
 * loss walks straight past the check written to catch it.
 *
 * So `declared` is REQUIRED, and it is the row's own account of itself: the
 * `armIds` its plan named and the `rounds` x `blocks` its design ran. Both are
 * carried beside the split rather than derived from it — `report` reads them
 * from the row and the run's design, a reader of a written dataset from
 * `row.armIds` and `data.design`. Passing the stored blocks back in under a new
 * name would be this defect with extra steps, so a caller that offers no
 * declared shape gets a refusal rather than a fold.
 */
function foldDecomposition(blocksDecomp, declared) {
  if (!Array.isArray(blocksDecomp) || blocksDecomp.length === 0) {
    throw new Error(
      'this row carries no per-block decomposition: it predates rf2-jo60g, so the ' +
        'Script/Layout/RecalcStyle split is NOT recomputable from it and may not be quoted'
    );
  }
  const shape = declared || {};
  const expected = Array.isArray(shape.armIds) ? shape.armIds.slice().sort() : [];
  if (expected.length === 0 || !(shape.rounds > 0) || !(shape.blocks > 0)) {
    throw new Error(
      "foldDecomposition needs the row's DECLARED shape — {armIds, rounds, blocks} — to measure " +
        'completeness against. Anchoring to the stored blocks instead lets a truncated row certify ' +
        'itself: that is what accepted a removed round, a removed block, and an arm removed from ' +
        'every block (rf2-e1tko).'
    );
  }
  const describe = (v) =>
    v === undefined ? 'absent'
      : v === null ? 'null'
        : typeof v === 'number' ? (Number.isNaN(v) ? 'NaN' : String(v))
          : Array.isArray(v) ? 'an array'
            : `a ${typeof v}`;
  const refuse = (where, what) => {
    throw new Error(
      `this row's per-block decomposition is not valid evidence — ${where}: ${what}. ` +
        'A partial or corrupt split is refused rather than folded: defaulting it to zero ' +
        'would publish a plausible Script/Layout/RecalcStyle ratio for numbers the run ' +
        'never recorded (rf2-jo60g).'
    );
  };

  // "5 rounds are stored where the row declares 6 — 1 missing", in one voice for
  // both dimensions, because a reader wants the same sentence either way.
  const counted = (stored, want, unit) =>
    `carries ${stored} ${unit}${stored === 1 ? '' : 's'} where the row declares ${want} — ` +
    `${Math.abs(want - stored)} ${stored < want ? 'missing' : 'unexpected'}`;

  if (blocksDecomp.length !== shape.rounds) refuse("the row's shape", counted(blocksDecomp.length, shape.rounds, 'round'));

  const out = {};
  for (let r = 0; r < blocksDecomp.length; r++) {
    const round = blocksDecomp[r];
    if (!Array.isArray(round) || round.length === 0) {
      refuse(`round ${r}`, `carries no blocks (${describe(round)})`);
    }
    if (round.length !== shape.blocks) refuse(`round ${r}`, counted(round.length, shape.blocks, 'block'));
    for (let b = 0; b < round.length; b++) {
      const blk = round[b];
      const at = `round ${r}, block ${b}`;
      if (!blk || typeof blk !== 'object' || Array.isArray(blk)) refuse(at, `is not a block of arms (${describe(blk)})`);
      const here = Object.keys(blk).sort();
      if (here.length === 0) refuse(at, 'carries no arms — an empty block measured nothing');
      const missing = expected.filter((a) => !here.includes(a));
      const extra = here.filter((a) => !expected.includes(a));
      if (missing.length || extra.length) {
        refuse(
          at,
          `carries arms [${here.join(', ')}] where the row declares [${expected.join(', ')}]` +
            `${missing.length ? ` — missing ${missing.join(', ')}` : ''}` +
            `${extra.length ? ` — unexpected ${extra.join(', ')}` : ''}`
        );
      }
      for (const arm of expected) {
        const a = blk[arm];
        const side = `${at}, arm "${arm}"`;
        if (!a || typeof a !== 'object' || Array.isArray(a)) refuse(side, `carries no accumulator (${describe(a)})`);
        for (const k of DECOMP_FIELDS) {
          if (typeof a[k] !== 'number' || !Number.isFinite(a[k])) {
            refuse(side, `field "${k}" is ${describe(a[k])}, not a finite number`);
          }
        }
        if (!(a.n > 0)) refuse(side, `field "n" is ${a.n} — a block that took no samples is not evidence`);
        if (a.layoutCount < 0) refuse(side, `field "layoutCount" is ${a.layoutCount} — a count cannot be negative`);
        const acc = (out[arm] ||= zeroDecomp());
        for (const k of DECOMP_FIELDS) acc[k] += a[k];
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Adjudication
// ---------------------------------------------------------------------------

/** Tared p50 of one arm in one block: p50(arm) - p50(plumb of the SAME block). */
function taredCell(blocks, r, b, arm) {
  return p50(blocks[r][b][arm]) - p50(blocks[r][b][PLUMB]);
}

function perBlock(blocks, f) {
  const out = [];
  for (let r = 0; r < blocks.length; r++) {
    for (let b = 0; b < blocks[r].length; b++) out.push(f(r, b));
  }
  return out;
}

/**
 * The control's per-block statistic — tared ctl-2x over tared floor, one per
 * (round, block), which is what `controlVerdict` adjudicates and what a row's
 * `adjudication.ctl.perBlock` records.
 *
 * Exported so a witness can drive the REAL arithmetic over a committed
 * dataset rather than a copy of it (rf2-y0pkh). `report` used to spell this
 * inline, and a rate measured against a reimplementation would be a rate
 * about the reimplementation.
 */
const controlBlocks = (blocksTask) =>
  perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, CTL) / taredCell(blocksTask, r, b, FLOOR));

function report(out) {
  const { runId, rowId, armIds, canon, ctlPredicted, blocksTask, blocksNet, blocksInPage, blocksDecomp, samplesTask, samplesNet, tally, runtime, granularity } = out;
  const decomposition = foldDecomposition(blocksDecomp, { armIds, rounds: ROUNDS, blocks: BLOCKS });
  const stamp = STAMP[rowId];

  console.log(`\n;; ==== RUN ${runId} — ROW ${rowId} ====`);
  console.log(`;; runtime  ${runtime}`);
  console.log(`;; writes   ${tally.unverified} unverified of ${tally.writes} (mount + element-count read-backs)`);
  console.log(
    `;; clock    PUBLISHED: Performance.getMetrics raw TaskDuration, frame-settled (rAF + setTimeout) — ` +
      `script AND frame, main thread only, no raster/composite`
  );
  console.log(
    `;; door     every arm (plumb included): page.evaluate -> C56CLOCK.sample. taskNet through this door ` +
      `is FRAME-ONLY (the subtraction removes the arm's own script) and is DIAGNOSTIC below`
  );
  console.log(
    `;; grain    smallest non-zero per-sample TaskDuration delta ${granularity.length ? granularity[0].toFixed(6) : 'n/a'} ms`
  );
  console.log(`;; stamp    ${stamp.cards}; ${stamp.elements} elements; the census's own screen, mounted at the roster's seed`);
  for (const arm of ['hicasso', 'uix', 'reagent']) {
    if (!armIds.includes(arm)) continue;
    console.log(`;; stamp    ${arm.padEnd(8)} B=${stamp.boundaries[arm]} · reads: ${stamp.reads[arm]}`);
  }
  console.log(
    `;; scope    WITHIN-ROW ONLY — the arms above mount the identical page, so this row's ratios ` +
      `adjudicate. Rows differ in cards as well as boundaries (rf2-2rtt6.62), so no difference ` +
      `BETWEEN rows attributes to boundary decomposition.`
  );

  // parity record
  const nonControl = Object.entries(canon).filter(([, c]) => !c.control);
  const refHash = nonControl.length ? nonControl[0][1].hash : null;
  const disagree = nonControl.filter(([, c]) => c.hash !== refHash).map(([a]) => a);
  console.log(
    `;; parity   ${nonControl.length} non-control arms, canonical DOM ` +
      `${disagree.length === 0 ? 'IDENTICAL' : 'DISAGREES: ' + disagree.join(', ')} ` +
      `(${nonControl.length ? nonControl[0][1].bytes : 0} bytes; boot gate also held at stress AND small size, and can answer false)`
  );

  // the tare
  const plumbAll = blocksTask.flatMap((r) => r.flatMap((b) => b[PLUMB]));
  const pb = band(plumbAll);
  console.log(
    `;; tare     plumb p50 ${fmt(p50(plumbAll))} ms [${fmt(pb.min)} – ${fmt(pb.max)}] — SUBTRACTED from every figure below`
  );

  // per-arm vs floor + absolutes, on the clock of record
  console.log(`;; ---- per-arm, ratio to the floor in the SAME block (tared), raw TaskDuration ----`);
  const vsFloor = {};
  for (const arm of armIds) {
    if (arm === PLUMB) continue;
    const abs = p50(blocksTask.flatMap((r) => r.flatMap((b) => b[arm])));
    const absNet = p50(blocksNet.flatMap((r) => r.flatMap((b) => b[arm])));
    const absIn = p50(blocksInPage.flatMap((r) => r.flatMap((b) => b[arm])).filter(Number.isFinite));
    if (arm === FLOOR) {
      console.log(
        `;;   ${arm.padEnd(14)} ABSOLUTE p50 task ${fmt(abs, 3)} ms (tared ${fmt(abs - p50(plumbAll), 3)}) — ` +
          `taskNet ${fmt(absNet, 3)}, in-page ${fmt(absIn, 3)} — the denominator`
      );
      continue;
    }
    const per = perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, arm) / taredCell(blocksTask, r, b, FLOOR));
    const bd = band(per);
    vsFloor[arm] = bd;
    console.log(
      `;;   ${arm.padEnd(14)} ${fmt(bd.mean)}x floor [${fmt(bd.min)} – ${fmt(bd.max)}]  n=${per.length} blocks   ` +
        `ABS task ${fmt(abs, 3)} ms = taskNet ${fmt(absNet, 3)} + (in-page ${fmt(absIn, 3)})`
    );
  }

  // the control — predicted from the row's own element arithmetic
  const ctlPer = controlBlocks(blocksTask);
  const ctl = controlAdjudication(rowId, ctlPredicted, ctlPer, CONTROL_SLACK);
  const floorTared = p50(perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, FLOOR)));
  const c = additiveConstant(floorTared, ctl.measured.mean, ctlPredicted);
  console.log(`;; ---- POSITIVE CONTROL: ctl-2x doubles the row's cards; prediction ${fmt(ctlPredicted)}x (element arithmetic, chrome does not double) ----`);
  console.log(
    `;;   ${ctl.strictOk ? 'PASS' : 'FAIL'}  measured ${fmt(ctl.measured.mean)}x [${fmt(ctl.measured.min)} – ${fmt(ctl.measured.max)}] ` +
      `against [${fmt(ctl.band[0], 2)} – ${fmt(ctl.band[1], 2)}] (${ctl.rule})` +
      (ctl.standard ? '   [REPORTED — this row is adjudicated by the check standard below]' : '')
  );
  for (const line of formatCheckStandard(ctl.standard)) console.log(line);
  console.log(`;;   ADJUDICATED BY ${ctl.adjudicator} — ${ctl.ok ? 'the control HOLDS' : 'the control DOES NOT HOLD'}`);
  console.log(
    `;;   rf2-jcm3p's recorded mount undershoot expected: additive residual c = ` +
      `${fmt(c, 3)} ms on a tared floor of ${fmt(floorTared, 3)} ms. This control certifies ` +
      `page-proportional SIGNAL, not exactness; no changed-set control can reach a mount row.`
  );

  // the band, on the clock the rows are stated on
  const floorBlocks = blocksTask.map((r) => r.map((b) => b[FLOOR]));
  const floorCells = blocksTask.map((_, ri) => blocksTask[ri].map((_, bi) => taredCell(blocksTask, ri, bi, FLOOR)));
  const fixedCells = blocksTask.map((_, ri) => blocksTask[ri].map((_, bi) => taredCell(blocksTask, ri, bi, CTL)));

  const pairs = GATED.concat(BESIDE[runId] || []).filter(([n, d]) => armIds.includes(n) && armIds.includes(d));
  const bars = {};
  const barBands = {};
  for (const [n, d] of pairs) {
    const per = perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, n) / taredCell(blocksTask, r, b, d));
    const bd = band(per);
    bars[`${n} / ${d}`] = bd.mean;
    barBands[`${n} / ${d}`] = bd;
  }
  const assessed = seamlib.assess({ floorBlocks, floorCells, fixedCells, bars });
  const bw = assessed.bandStats.band;
  console.log(
    `;; ---- THE BAND (ctl-2x / floor per block, seam.cjs): ${Number.isFinite(bw) ? (bw * 100).toFixed(1) + '%' : 'n/a'} ` +
      `— ceiling ${(seamlib.BAND_CEILING * 100).toFixed(0)}% (rf2-ymi6j) ${assessed.verdict.ceilingBreached ? '— BREACHED, no magnitude reportable' : ''} ----`
  );
  console.log(
    `;;   block seam (floor by block-position): [${assessed.seam.bySeg.map((x) => fmt(x, 3)).join(', ')}] ` +
      `spread ${(assessed.seam.spread * 100).toFixed(1)}% (null q50 ${(assessed.null.q50 * 100).toFixed(1)}% q95 ${(assessed.null.q95 * 100).toFixed(1)}%, p ${assessed.null.p}) — ` +
      `the "segments" here are consecutive blocks of one plan, so this is the PHASE null, not an adapter seam`
  );

  // the head-to-head table, with the gate line
  console.log(`;; ---- HEAD-TO-HEAD (same run, same blocks; floor cancels within a block) ----`);
  const verdicts = {};
  for (const [n, d] of pairs) {
    const key = `${n} / ${d}`;
    const bd = barBands[key];
    const gated = n === 'hicasso' && d === 'uix';
    const netPer = perBlock(blocksNet, (r, b) => taredCell(blocksNet, r, b, n) / taredCell(blocksNet, r, b, d));
    const inPer = perBlock(blocksInPage, (r, b) => p50(blocksInPage[r][b][n]) / p50(blocksInPage[r][b][d])).filter(Number.isFinite);
    console.log(
      `;;   ${key.padEnd(26)} ${fmt(bd.mean)}x [${fmt(bd.min)} – ${fmt(bd.max)}]` +
        `${bd.min <= 1 && bd.max >= 1 ? '  [STRADDLES 1.0]' : ''}` +
        `   (taskNet ${fmt(band(netPer).mean)}x · in-page ${inPer.length ? fmt(band(inPer).mean) : 'n/a'}x)` +
        (gated ? '   << GATED PAIR' : '   (beside the gate, not a second gate)')
    );
    if (gated) {
      const margin10 = Math.abs(bd.mean / GATE_LINE - 1);
      let verdict;
      let why;
      if (assessed.verdict.ceilingBreached) {
        verdict = 'REFUSED';
        why = `the run's band ${(bw * 100).toFixed(1)}% exceeds the ${(seamlib.BAND_CEILING * 100).toFixed(0)}% ceiling — no magnitude reportable`;
      } else if (bd.min > GATE_LINE && margin10 > bw) {
        verdict = 'FAILS THE LINE';
        why = `whole range above 1.10 and margin ${(margin10 * 100).toFixed(1)}% clears the band ${(bw * 100).toFixed(1)}%`;
      } else if (bd.max < GATE_LINE && margin10 > bw) {
        verdict = 'INSIDE THE LINE';
        why = `whole range below 1.10 and margin ${(margin10 * 100).toFixed(1)}% clears the band ${(bw * 100).toFixed(1)}%`;
      } else {
        verdict = 'INSTRUMENT-LIMITED';
        why =
          bd.min <= GATE_LINE && bd.max >= GATE_LINE
            ? `the range straddles 1.10 — the run cannot resolve the boundary, which is NOT a pass`
            : `margin to 1.10 ${(margin10 * 100).toFixed(1)}% sits inside the band ${(bw * 100).toFixed(1)}% — NOT a pass`;
      }
      verdicts[key] = { verdict, why, mean: bd.mean, min: bd.min, max: bd.max, band: bw, ctlOk: ctl.ok };
      console.log(
        `;;     GATE <= 1.10x direct UIx (same run, clock of record; CORROBORATES M1, does not redefine it): ` +
          `${verdict} — ${why}` +
          (ctl.ok ? '' : " — AND the control failed, so the magnitude additionally carries the control's failure")
      );
    }
  }

  // decomposition
  console.log(`;; ---- decomposition, mean ms per sample ----`);
  for (const [arm, a] of Object.entries(decomposition)) {
    console.log(
      `;;   ${arm.padEnd(14)} task ${(a.task / a.n).toFixed(3)} = taskNet ${(a.taskNet / a.n).toFixed(3)} + devtools ${(a.devtools / a.n).toFixed(3)}` +
        `   script ${(a.script / a.n).toFixed(4)}  style ${(a.style / a.n).toFixed(3)}  layout ${(a.layout / a.n).toFixed(3)}  in-page ${(a.inPage / a.n).toFixed(3)}`
    );
  }

  // guard, on the clock of record (and the diagnostic clock beside it)
  const gv = guard.verdict(samplesTask, { tolerance: TOLERANCE });
  const gvNet = guard.verdict(samplesNet, { tolerance: TOLERANCE });
  for (const line of guard.format(gv, `${runId} / ${rowId} (raw TaskDuration)`)) console.log(line);
  console.log(`;;   (the same guard on taskNet, diagnostic: ${gvNet.refuse ? 'REFUSE' : 'ok'})`);

  return { vsFloor, bars: barBands, verdicts, ctl, cAdditive: c, assessed, guardRefuse: gv.refuse, plumb: p50(plumbAll), floorTared };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// A refusal that only PRINTS is not a refusal (rf2-rr6do; rf2-tb345 repaired
// the same defect in b8_run.cjs, and `hd8_clock_run.cjs` — this file's near
// twin — carried it identically). Three refusals were computed here, printed
// loudly, written into the dataset, and then the exit was taken off `failed`
// and the arm-order guard ALONE. So a quiet box with a clean guard could
// print
//
//   ;; writes   4 unverified of 36 (mount + element-count read-backs)
//   ;; ---- THE BAND ...: 41.2% — ceiling 35% — BREACHED, no magnitude reportable ----
//   ;;   FAIL  measured 1.21x [...] against [1.50 – 2.50]
//
// and still exit 0 — and prediction P4 above, registered before any clock,
// PROMISES that a row whose control or band cannot hold "publishes a REFUSAL
// with the reason, not a number". A promise the process exit does not keep
// is worse than no promise: it is a reader's reason not to check.
//
// The correct shape already existed in `clock_run.cjs`, which gates all
// three. This is that shape, with the decision moved into ONE pure function
// over a flat summary so the exit path is checkable without a release build
// and a headless Chromium — see `../clock_exit_path.test.cjs`.
//
// The four conditions are INDEPENDENT: each refuses on its own, and when
// several fire every one of them is named. Precedence preserves every code
// this driver already had — a run that exited 1 still exits 1, a run the
// arm-order guard refused still exits 2.
//
// No refusal suppresses output: the tables are printed and the datasets are
// written before this is consulted. A refusal is about what may be QUOTED,
// not about throwing the measurement away.

/**
 * ONE ROW, in the flat shape every decision below is taken on.
 *
 * Lifted out of `summarise` because the WRITE path needs the same four
 * refusal fields the EXIT path does, and reading them twice off two
 * different accessor paths is how a second adjudicator grows (rf2-pzqy8).
 * One mapper, so `verdict` and `rowPublication` can never disagree about
 * what refused a row.
 */
const summariseRow = (r) => ({
  id: `${r.runId}/${r.rowId}`,
  guardRefuse: r.adjudication.guardRefuse,
  unverified: r.tally.unverified,
  writes: r.tally.writes,
  ctlOk: r.adjudication.ctl.ok,
  ctlMeasured: r.adjudication.ctl.measured.mean,
  ceilingBreached: r.adjudication.assessed.verdict.ceilingBreached,
  band: r.adjudication.assessed.bandStats.band,
});

/** The flat record the exit is decided on: one entry per row actually taken. */
function summarise(failed, results) {
  return { failed: failed || null, rows: (results || []).map(summariseRow) };
}

function verdict(summary) {
  const failed = summary && summary.failed;
  const rows = (summary && summary.rows) || [];
  const pct = (x) => (Number.isFinite(x) ? `${(x * 100).toFixed(1)}%` : 'n/a');
  const lines = [];

  const refused = rows.filter((r) => r.guardRefuse);
  const unverified = rows.filter((r) => r.unverified > 0);
  const overCeiling = rows.filter((r) => r.ceilingBreached);
  const ctlFailed = rows.filter((r) => !r.ctlOk);

  if (failed) lines.push(`[c56clock] FAILED: ${failed}`);
  if (refused.length) {
    lines.push(
      '[c56clock] ARM-ORDER GUARD REFUSED — at least one figure above depends on where in the plan ' +
        'it was measured, and may not be reported as measured. Repair the arm, not the guard: ' +
        refused.map((r) => r.id).join(', ')
    );
  }
  if (unverified.length) {
    lines.push(
      '[c56clock] REFUSED — unverified operations (rf2-rr6do): a window whose value never reached ' +
        'the page is not a measurement of that page: ' +
        unverified.map((r) => `${r.id}: ${r.unverified} of ${r.writes}`).join(', ')
    );
  }
  if (overCeiling.length) {
    lines.push(
      `[c56clock] REFUSED — the run's own reproducibility band exceeds seam.cjs's ceiling ` +
        `(rf2-ymi6j, rf2-rr6do; this is prediction P4 kept) on: ` +
        overCeiling.map((r) => `${r.id} (${pct(r.band)})`).join(', ') +
        '. ctl-2x and floor are two arms in the SAME block whose true ratio is a property of the ' +
        'page, so a band that wide means the box could not reproduce identical work — no magnitude ' +
        'from those rows is reportable, whatever its margin.'
    );
  }
  if (ctlFailed.length) {
    lines.push(
      '[c56clock] REFUSED — the positive control did not see the change its own arithmetic ' +
        'predicts (rf2-rr6do; this is prediction P4 kept) on: ' +
        ctlFailed.map((r) => `${r.id} (measured ${Number(r.ctlMeasured).toFixed(4)}x)`).join(', ') +
        '. No MAGNITUDE from those rows is reportable — and the scope is the ROW (rf2-pzqy8): every ' +
        'row above that passed every gate stays canonical in the same file, which is P4 kept at the ' +
        'scope P4 states it.'
    );
  }

  const code = failed
    ? 1
    : refused.length
      ? 2
      : unverified.length
        ? 3
        : overCeiling.length
          ? 4
          : ctlFailed.length
            ? 5
            : 0;
  return { code, lines };
}

// WHERE A RUN'S DATASETS MAY BE WRITTEN (rf2-2rtt6.56, merged-PR audit #7379).
//
// `verdict` decides what may be QUOTED. This decides what may be WRITTEN, and
// it is a separate question the driver got wrong in the same direction. The
// datasets were written under the CANONICAL filenames before the refusal was
// consulted, whatever shape the run had — so a run narrowed to one row
// (C56CLOCK_ROWS) or one adapter (C56CLOCK_ONLY), taken with `--no-build`
// against whatever bundle happened to be on disk, taken at an overridden
// depth, or one the verdict then REFUSED, silently replaced the published
// evidence the studio page cites. Nothing announced it: the write had already
// landed, and the nonzero exit arrived afterwards. rf2-rr6do repaired the exit
// path; this is the write path, the other half of the same fail-open.
//
// THE RULE: the canonical directory holds the PUBLISHED SHAPE and nothing
// else. Any narrowing, any override, any skipped gate routes to a sibling
// `.unpublished` directory, named on stdout with the reason.
//
// AND EVERY ONE OF THOSE IS A PROPERTY OF THE RUN, which is the correction
// rf2-pzqy8 makes. A gate refusal is a property of ONE ROW, and this function
// used to read the whole verdict's exit code and move the file for it — so a
// run whose `ordinary` row missed its control sent `large-template` and
// `feed` to `.unpublished` too, having passed every gate they have. Over the
// five committed sessions that was every session, on both adapters, so NO
// FULL-SHAPE CENSUS RUN COULD EVER BE CANONICAL and the studio page's
// recomputable claims had no canonical set to be recomputed from.
//
// The refusal is not weakened, it is put at its own scope. The exit code
// still refuses the run and `verdict` still names every offending row; each
// row now carries its own `canonical` and `notCanonicalWhy` (`rowPublication`
// below) and the file indexes them in `rowsRefused`. A canonical file may
// therefore hold a REFUSED row beside citable ones — which is not a hole but
// prediction P4 in the driver's own words: "the ROW publishes a REFUSAL with
// the reason, not a number". A refusal is evidence; discarding the rows
// beside it was the fault.
//
// `clock_run.cjs`'s `publication(shape)` has read shape and nothing else all
// along. This is that function's rule, on this driver.
//
// rf2-azopg added the skipped gate to that list, and it is the sharpest case:
// C56CLOCK_SKIP_QUIET=1 already PRINTED "NOT the published shape", but the
// fact never reached here, so a run taken on a contended box could occupy the
// canonical set and read back as if it had been taken in a granted window.
// That is the one distinction the whole quiet-box discipline exists to keep.
//
// An explicit C56CLOCK_DATA_DIR is the operator naming their own destination
// — which is how the sibling `censusclock-*` datasets beside the canonical
// one were taken. It is honoured as given, and it is never the canonical set.
//
// Pure over a flat shape record, for the same reason `verdict` is: the write
// path is then checkable without a release build and a headless Chromium.
function destination(shape) {
  const s = shape || {};
  if (s.dataDirOverridden) {
    return { dir: s.dataDir, canonical: false, why: 'C56CLOCK_DATA_DIR named this destination' };
  }
  const why = [];
  if (s.rowsOnly) why.push(`a PARTIAL row set (C56CLOCK_ROWS=${s.rowsOnly})`);
  if (s.runsOnly) why.push(`a PARTIAL run set (C56CLOCK_ONLY=${s.runsOnly})`);
  if (s.noBuild) why.push("--no-build (the bundle on disk is not known to be this tree's)");
  if (!s.depthPublished) why.push('an OVERRIDDEN design depth');
  if (s.skipQuiet) why.push('a SKIPPED quiet gate (C56CLOCK_SKIP_QUIET=1)');
  if (!why.length) return { dir: s.dataDir, canonical: true, why: null };
  return { dir: `${s.dataDir}.unpublished`, canonical: false, why: why.join('; ') };
}

/**
 * WHETHER ONE ROW MAY BE CITED (rf2-pzqy8) — `destination`'s decision at the
 * scope prediction P4 states it at, over the same flat row `verdict` refuses
 * on, so the two can never disagree about what refused it.
 *
 * TWO GROUNDS, and they compose. A row INHERITS the run's shape: nothing
 * taken at an overridden depth or on an unchecked box is citable whatever its
 * own gates did. On top of that it carries its OWN four gates — the arm-order
 * guard, unverified read-backs, a reproducibility band over seam.cjs's
 * ceiling, and a positive control that did not hold. Every one of those is
 * already per-row in `verdict`'s own lines, which name the rows they refuse;
 * the write path is where that scope used to be thrown away.
 *
 * FAIL CLOSED: an absent row record is refused rather than waved through, and
 * an absent destination is treated as an unpublished one — a row whose run's
 * shape nobody established is not a citable row.
 */
function rowPublication(row, dest) {
  const r = row || {};
  const d = dest || { canonical: false, why: 'no destination was decided for this run' };
  const pct = (x) => (Number.isFinite(x) ? `${(x * 100).toFixed(1)}%` : 'n/a');
  const why = [];
  if (!row) why.push('no row record — an absent row is not a citable one');
  if (!d.canonical) why.push(`the run itself is not the published evidence: ${d.why}`);
  if (r.guardRefuse) {
    why.push(
      'the ARM-ORDER GUARD refused it — a figure that depends on where in the plan it was measured ' +
        'may not be reported as measured'
    );
  }
  if (r.unverified > 0) {
    why.push(
      `${r.unverified} of ${r.writes} operations are UNVERIFIED (rf2-rr6do) — a window whose value ` +
        'never reached the page is not a measurement of that page'
    );
  }
  if (r.ceilingBreached) {
    why.push(
      `its reproducibility band ${pct(r.band)} exceeds seam.cjs's ${(seamlib.BAND_CEILING * 100).toFixed(0)}% ` +
        'ceiling (rf2-ymi6j) — the box could not reproduce identical work, so no magnitude is reportable'
    );
  }
  if (row && !r.ctlOk) {
    why.push(
      `its POSITIVE CONTROL did not hold (measured ${Number(r.ctlMeasured).toFixed(4)}x) — this is the ` +
        'REFUSAL prediction P4 promises the row publishes, with the reason, in place of a number'
    );
  }
  return why.length ? { canonical: false, why: why.join('; ') } : { canonical: true, why: null };
}

/**
 * The compact dataset for one run — the reduced quantities every statistic on
 * the studio page is a function of, so the page can be recomputed from the
 * tree.
 *
 * Lifted out of `drive` deliberately. Serialising a row means naming its
 * refusal fields (`guardRefuse`, `ceilingBreached`, …), and `drive` is held to
 * an invariant that nothing downstream of `verdict` may name one — the check
 * that stops a second exit path growing back (`../clock_exit_path.test.cjs`).
 * The write now happens after the verdict, so the serialiser has to live
 * outside it. Recording is not deciding, and this is where that shows.
 */
function datasetFor(rows, meta) {
  const publication = (r) => rowPublication(summariseRow(r), meta.dest);
  return {
    bead: 'rf2-2rtt6.56',
    commit: meta.sha,
    blobs: meta.blobs,
    when: new Date().toISOString(),
    // Whether this file is THE PUBLISHED SHAPE, recorded IN the file — a
    // dataset that travels out of its directory must still say what it is.
    // It is a fact about the RUN: its depth, its row set, its build, its
    // quiet gate. Whether a given ROW in it may be cited is on the row.
    canonical: meta.dest.canonical,
    notCanonicalWhy: meta.dest.why,
    // ... and the rows that may NOT be, indexed at the top of the file so a
    // reader who checks only the header cannot cite one by missing it
    // (rf2-pzqy8). Empty on a run every row of which passed every gate.
    rowsRefused: rows.filter((r) => !publication(r).canonical).map((r) => r.rowId),
    design: { rounds: ROUNDS, blocks: BLOCKS, warmup: WARMUP, samples: SAMPLES, tolerance: TOLERANCE, controlSlack: CONTROL_SLACK, gateLine: GATE_LINE },
    clock: 'Performance.getMetrics raw TaskDuration, frame-settled (rAF + setTimeout), plumb-tared',
    door: 'page.evaluate -> C56CLOCK.sample (every arm, plumb included)',
    node: process.version,
    rows: rows.map((r) => ({
      rowId: r.rowId,
      // WHETHER THIS ROW MAY BE CITED, and why not when it may not — the
      // refusal at prediction P4's own scope (rf2-pzqy8). A row that refused
      // travels here beside the rows that did not, rather than taking them
      // to `.unpublished` with it.
      canonical: publication(r).canonical,
      notCanonicalWhy: publication(r).why,
      armIds: r.armIds,
      // The row's workload — cards, elements, per-instance reads, boundaries.
      // Persisted because rf2-2rtt6.62 turned on exactly these counts, and
      // they were recoverable only by reading the instrument's own source at
      // the producing commit.
      stamp: STAMP[r.rowId],
      canon: r.canon,
      ctlPredicted: r4(r.ctlPredicted),
      blocksTask: r.blocksTask,
      blocksNet: r.blocksNet.map((rd) => rd.map((b) => Object.fromEntries(Object.entries(b).map(([a, xs]) => [a, r4(p50(xs))])))),
      blocksInPage: r.blocksInPage.map((rd) => rd.map((b) => Object.fromEntries(Object.entries(b).map(([a, xs]) => [a, r4(p50(xs.filter(Number.isFinite)))])))),
      // THE DECOMPOSITION — the renderer's own Script / RecalcStyle / Layout
      // split, per block per arm: the block's sums with the `n` that produced
      // them beside them.
      //
      // rf2-jo60g. `deltaOf` has always COLLECTED these three and this function
      // has always dropped them, so the studio page's cited split on the feed
      // row — "layout 2.06x, style 1.85x, script 2.3x" — was a figure no
      // committed dataset could reproduce. Sums and counts at the same block
      // grain as `blocksTask` are the reduced quantity: `foldDecomposition`
      // gives the row, dividing by `n` gives the per-sample mean the report
      // prints, and one arm's mean over another's gives the published ratio.
      // Persisting it does not backfill the datasets already on main — the
      // split becomes recomputable from the NEXT canonical run, not this line.
      blocksDecomp: r.blocksDecomp.map((rd) =>
        rd.map((b) =>
          Object.fromEntries(
            Object.entries(b).map(([a, acc]) => [a, Object.fromEntries(Object.entries(acc).map(([k, v]) => [k, r4(v)]))])
          )
        )
      ),
      // THE CLOCK'S OWN GRAIN, in the run it governs — the sorted distinct
      // non-zero per-sample `TaskDuration` deltas this row observed, the
      // smallest of which is the finest interval the published clock
      // resolved here. `report` prints that smallest value; until now it
      // printed it and nothing kept it.
      //
      // rf2-dzus, and the same species as `blocksDecomp` above: collected in
      // `runRow`, dropped at write time. A file of durations with no record
      // of its own resolution cannot answer "was this measurable?" from
      // itself — the answer has to come from a constant somebody remembers,
      // which is exactly what `rf2-d2tzk` stopped the in-page clock doing.
      // It backfills nothing; the grain travels with the NEXT canonical run.
      granularity: r.granularity,
      tally: r.tally,
      runtime: r.runtime,
      quiet: r.quiet,
      windowStart: r.windowStart,
      adjudication: {
        ctl: r.adjudication.ctl,
        cAdditive: r4(r.adjudication.cAdditive),
        band: Number.isFinite(r.adjudication.assessed.bandStats.band) ? r4(r.adjudication.assessed.bandStats.band) : null,
        ceilingBreached: r.adjudication.assessed.verdict.ceilingBreached,
        bars: r.adjudication.bars,
        verdicts: r.adjudication.verdicts,
        guardRefuse: r.adjudication.guardRefuse,
        plumb: r4(r.adjudication.plumb),
        floorTared: r4(r.adjudication.floorTared),
      },
    })),
  };
}

/** This process's shape, as `destination` reads it. */
const runShape = () => ({
  dataDir: DATA_DIR,
  dataDirOverridden: DATA_DIR_OVERRIDDEN,
  rowsOnly: ROWS_ONLY || null,
  runsOnly: ONLY || null,
  noBuild: NO_BUILD,
  depthPublished: depthIsPublished(),
  // The quiet gate PRINTS that a skipped run is not the published shape
  // (`quietGate`); until rf2-azopg nothing carried that fact here, so the
  // write decision never saw it. Printing a caveat is not enforcing one.
  skipQuiet: SKIP_QUIET,
});

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function drive() {
  const gst = guard.selfTest();
  console.log(';; ==== ARM-ORDER GUARD SELF-TEST ====');
  for (const cse of gst.checks) console.log(`;;   ${cse.ok ? 'ok  ' : 'FAIL'}  ${cse.name}`);
  if (!gst.ok) {
    console.error('[c56clock] the arm-order guard failed its own self-test; nothing was measured');
    return 1;
  }
  const sst = seamlib.selfTest();
  console.log(';; ==== SEAM/BAND SELF-TEST ====');
  for (const cse of sst.checks) console.log(`;;   ${cse.ok ? 'ok  ' : 'FAIL'}  ${cse.name}`);
  if (!sst.ok) {
    console.error('[c56clock] seam.cjs failed its own self-test; nothing was measured');
    return 1;
  }

  const sha = revision();
  const bl = blobs();
  console.log(';; ==== CENSUS-REAL CLOCK-ROW PROVENANCE ====');
  console.log(';;   bead        rf2-2rtt6.56 (census-page clock rows; EP-0038)');
  console.log(`;;   commit      ${sha}`);
  for (const [f, h] of Object.entries(bl)) console.log(`;;   blob        ${h}  ${f}`);
  console.log(`;;   reproduce   node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs`);
  console.log(`;;   build       shadow-cljs release ${BUILD_ID} (:advanced, goog.DEBUG false)`);
  console.log(`;;   node        ${process.version}`);
  console.log(
    `;;   design      ${ROUNDS} rounds x ${BLOCKS} blocks x (${WARMUP} warmup + ${SAMPLES} samples) per arm` +
      `${depthIsPublished() ? '' : '  *** OVERRIDDEN — NOT THE PUBLISHED SHAPE ***'}`
  );
  console.log(
    `;;   runs        ${RUNS.map((r) => r.id).join(', ')}` +
      `${ONLY ? `  (C56CLOCK_ONLY=${ONLY} — a self-contained browser session; every published ratio is within-run, so nothing here composes across invocations. The published shape is both runs present.)` : ''}`
  );
  console.log(
    `;;   rows        ${ROWS.join(', ')}` + `${ROWS_ONLY ? `  (C56CLOCK_ROWS=${ROWS_ONLY} — PARTIAL, not the published shape)` : ''}`
  );
  console.log(`;;   guard tol   ${TOLERANCE} on raw TaskDuration (HD-008's stated mount choice)`);
  console.log(`;;   band ceil   ${(seamlib.BAND_CEILING * 100).toFixed(0)}% on raw TaskDuration (rf2-ymi6j)`);
  console.log(';;   write rows  REFUSED by construction on this box (rf2-7iqb5, rf2-d2tzk) — see the header');
  console.log(';; ==== PREDICTIONS, REGISTERED BEFORE ANY CLOCK ====');
  console.log(';;   P1  ctl-2x reads BELOW its arithmetic prediction on every row (rf2-jcm3p\'s');
  console.log(';;       recorded mount undershoot), inside the strict +/-25% band unless block');
  console.log(';;       scatter is wide.');
  console.log(';;   P2  DIRECTION ONLY: hicasso / uix on the FEED row sits wholly ABOVE 1.10 —');
  console.log(';;       the census card is ~17 elements per boundary against M1\'s 3, so the');
  console.log(';;       interpreter term the candidate\'s recorded 1.5001x deficit lives in');
  console.log(';;       GROWS on census-real cards. No magnitude predicted.');
  console.log(';;   P3  hicasso / uix on the LARGE-TEMPLATE row is the largest of the three —');
  console.log(';;       1,202 interpreted elements against a compile-time page with the shell');
  console.log(';;       held at one boundary.');
  console.log(';;   P4  the ORDINARY row (51 elements) sits near this door\'s own floor; if its');
  console.log(';;       control or band cannot hold, the row publishes a REFUSAL with the');
  console.log(';;       reason, not a number.');

  if (!NO_BUILD) build();
  const server = serve();
  const results = [];
  let failed = null;
  try {
    const { chromium } = require('playwright');
    for (const runDef of RUNS) {
      console.log(`\n;; ======== RUN ${runDef.id} — ${runDef.why} ========`);
      const windowStart = new Date().toISOString();
      console.log(`;; measurement window OPEN  ${windowStart}`);
      const browser = await chromium.launch();
      try {
        for (const rowId of ROWS) {
          const q = quietGate(`${runDef.id}/${rowId}`);
          if (!q.ok) {
            failed = `the box would not go quiet before ${runDef.id}/${rowId} — the row was NOT taken (a run on a loud box is discarded, not reported)`;
            break;
          }
          const out = await runRow(browser, runDef, rowId);
          const adj = report(out);
          results.push({ ...out, adjudication: adj, quiet: q, windowStart });
        }
      } finally {
        await browser.close();
        console.log(`;; measurement window CLOSE ${new Date().toISOString()}  (${runDef.id})`);
      }
      if (failed) break;
    }
  } catch (e) {
    failed = e.message;
  } finally {
    server.close();
  }

  // The verdict is computed BEFORE the datasets are written, because where
  // they may be written depends on it (see `destination`).
  const v = verdict(summarise(failed, results));
  const dest = destination(runShape());

  // compact datasets — the reduced quantities every statistic above is a
  // function of, per run, so the page can be recomputed from the tree.
  if (results.length && !failed) {
    if (dest.canonical) {
      console.log(';; datasets CANONICAL — the published shape; each row states whether it may be cited');
    } else {
      console.log(`;; datasets NOT CANONICAL — ${dest.why}`);
      console.log(';;          These are working datasets. They are not the published evidence and');
      console.log(';;          may not be cited as it.');
    }
    fs.mkdirSync(dest.dir, { recursive: true });
    for (const runDef of RUNS) {
      const rows = results.filter((r) => r.runId === runDef.id);
      if (!rows.length) continue;
      const data = datasetFor(rows, { sha, blobs: bl, dest });
      const f = path.join(dest.dir, `${runDef.id}.json`);
      fs.writeFileSync(f, JSON.stringify(data));
      console.log(
        `;; dataset  ${f}${dest.canonical ? '' : '   (NOT the published evidence)'}` +
          (data.rowsRefused.length
            ? `   — rows that may NOT be cited: ${data.rowsRefused.join(', ')} (each carries its own reason)`
            : '   — every row citable')
      );
    }
  }

  console.log("\n;; ==== THE RULING IS NOT THIS INSTRUMENT'S TO ISSUE ====");
  console.log(";;   The verdict against the amended mount gate is the bead's to state and the");
  console.log(";;   operator's to overturn (rf2-2rtt6.1). This driver prints measurements and");
  console.log(';;   per-pair adjudications against the recorded line; nothing here amends the');
  console.log(';;   bar, and nothing here re-baselines the canonical M1 witness.');

  for (const line of v.lines) console.error(line);
  if (v.code === 0) {
    console.error('[c56clock] ok — measured, and no arm reads differently for its position in the plan');
  }
  return v.code;
}

// rf2-pzqy8 adds the last six: the empirical centre, and the refusal at the
// row's own scope. `summarise` and `verdict` stay FIRST, which the shared
// wiring pin in `../clock_exit_path.test.cjs` reads.
module.exports = { summarise, verdict, destination, datasetFor, foldDecomposition, controlBlocks, controlVerdict, CHECK_STANDARD, checkStandardVerdict, controlAdjudication, robustScale, summariseRow, rowPublication };

if (require.main === module) {
  drive().then((code) => {
    if (code !== 0) process.exit(code);
  });
}
