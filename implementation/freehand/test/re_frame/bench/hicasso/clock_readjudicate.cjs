#!/usr/bin/env node
// RE-ADJUDICATE AN ENSEMBLE OF CLOCK RUNS (rf2-emvod).
//
//   node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs out/run*.json
//
// `clock_run.cjs` adjudicates ONE run. Every conclusion this lane has
// reached about the candidate's clock rows rests on an ENSEMBLE — five runs
// for `the-candidates-clock.md`, eight for `bulk-broad-re-taken.md`, nineteen
// for the seam ladder — and each of those tables was assembled by hand from
// console logs. That is how `rf2-cvvb7`'s merged-PR audit came to record that
// a published study could not be recomputed from the landed tree: the runs
// were real, the arithmetic was reasonable, and nothing in the repository
// could reproduce either.
//
// So the ensemble arithmetic is a program, it reads the driver's own JSON
// datasets, and its output is the table that gets published. A reader with
// the datasets can rerun this and get the page back.
//
// ## WHAT IT REFUSES TO DO
//
// It does not select runs. Every dataset handed to it appears in the
// per-run table, including the ones whose control failed, and the ensemble
// figure is stated over all of them with the control-passing subset shown
// beside it rather than instead of it. Selecting a run after seeing its
// result is the fault this whole lane is built to avoid, and an analysis
// script is the easiest possible place to commit it silently.
//
// ## THE CONSUMER HALF OF THE TWO-TIER WRITE POLICY (`rf2-2rtt6.31`)
//
// The family's ruling of 2026-08-07 splits CAPTURE from PUBLICATION. Every
// completed measurement is preserved; only a gate-passing run of the full
// published shape is eligible published evidence, and a dataset says which it
// is IN THE FILE — `canonical` and `notCanonicalWhy`, exactly as
// `shapes/census_clock_run.cjs`'s `datasetFor` writes them. The ruling's
// consumer clause is one sentence: **a missing `canonical` field is not a
// pass.** A reader that inferred eligibility from the directory a file was
// found in would be trusting the one thing a file loses when it is copied.
//
// This program is that consumer, and `rf2-emvod`'s merged-PR audit (#7365)
// recorded it failing open in the other direction as well: the subset asked
// only for `ctlTask.ok` and the two ceilings, so it PRINTED `guardRefuse`,
// `guardRefuseTask`, the legacy-clock `ctlOk` and `tally.unverified` and then
// pooled the run anyway. The driver writes its dataset BEFORE its own fatal
// checks run, so a run that Chromium threw on, whose arms built different
// pages, or whose arm-order guard refused, is a well-formed file that reached
// the published mean. `GATES` below is that exit path, read back off the
// serialised record, fail-closed at every seat: **absent is not clean.**
//
// THREE OF THE DRIVER'S FATAL CHECKS WERE NOT SERIALISED WHEN THIS ROSTER WAS
// WRITTEN, and they were named here rather than skipped, because skipping one
// is how this file came to need repairing twice. `clock_run.cjs` computed
// `pageErrors`, `parityOk` (the canonical-DOM gate) and `etVerdict` (Event
// Timing) and exited 1 on each while storing none of them, so no dataset in
// the tree could satisfy this filter — the correct fail-closed reading of an
// INCOMPLETE record, and not a defect in it. Naming them at the driver's OWN
// internal field names is what made the producer half one line per verdict,
// and `rf2-e87sk` wrote those lines: `clock_run.cjs`'s `datasetFor` stores all
// three now, and its `publication` writes the two-tier `canonical` verdict
// beside them. Datasets taken before that repair still fail these gates, which
// is exactly what an incomplete record should do.
//
// ## THE ADDITIVE RESIDUAL, and why it is computed here
//
// `ctl-2x` builds exactly twice the floor's page and reads 1.68-1.86x rather
// than 2.00x, on every row, in every configuration, on both clocks. The
// standing diagnosis (`rf2-7iqb5`) is that a page-doubling control is
// mis-specified for an UPDATE row, whose work does not double with the page.
// That diagnosis predicts the mount row should be clean, and it is not — M1
// undershoots too.
//
// An additive per-sample cost `c` that the tare does not remove explains
// both at once, because a ratio of two arms one of which is twice the other
// reads `(2W + c) / (W + c)`, which is below 2 for any positive `c` and does
// not care whether the row is a mount or an update. Inverting the measured
// ratio `r` gives `c / W = (2 - r) / (r - 1)`, and `c` in milliseconds
// follows from the floor's own tared absolute. If `c` comes out similar
// across rows that differ wildly in what they do, the additive model is the
// better explanation and the repair is a control that DIFFERENCES the
// constant away rather than one that changes what is doubled.

// ## WHAT ADJUDICATES A ROW, AND WHAT PUBLISHES ONE (rf2-8a746)
//
// The 2026-08-07 ruling replaced both halves of this program's verdict and
// they are separate things.
//
//   THE GATE is a level-denominated, empirically calibrated, versioned CHECK
//   STANDARD — `clock_check_standard.json`, applied by `clock_check_
//   standard.cjs`, which this file and `clock_run.cjs` both require so there
//   is one seat and not two. It replaced the three-point difference-of-
//   differences control, which refused 42 of 42 bulk row-runs across two
//   independent quiet-box ensembles because its prediction is mis-derived on
//   a clock that is not affine in the dirty set AND its denominator sits ~2
//   sigma from zero. It also replaced the all-blocks strict rule, which was a
//   SEPARATE defect: `0.835^18 = 3.9%`, so a control fully meeting its premise
//   passed 4 of 42 runs.
//
//   THE PUBLICATION RULE is an EFFECT-SIZE CONFIDENCE INTERVAL on the quantity
//   the product claim actually asserts — the paired same-round Hicasso/donor
//   LEVEL ratio at fixed witness and fixed K — computed by a run-preserving
//   hierarchical bootstrap (Kalibera & Jones 2013: resample outer RUNS before
//   inner ROUNDS). A magnitude publishes only when the WHOLE interval lies on
//   the required side of 1.0, and the effect clears the same-run noise band.
//   Otherwise the row publishes INSTRUMENT-LIMITED and never a magnitude.
//
// Every pooled mean this program prints is therefore a DIAGNOSTIC beneath that
// verdict, and the 42 committed row-runs remain calibration evidence: they are
// not retroactively promoted to published magnitudes by anything here.
//
// ## AND THE MOUNT ROW IS ADJUDICATED TOO, FROM v2 (rf2-x7x10)
//
// The check standard shipped with its `mount` class UNCALIBRATED and failing
// closed, which was right at the hour — `rf2-8a746` seeded `bulk` and handed
// the mount to `rf2-t2flm` — and wrong by the next one. `rf2-t2flm`'s published
// `M1` row is conditioned on the every-block `ctl-2x` rule that `rf2-8a746`
// retired, and its ruling records THIS PROGRAM as reproducing every figure of
// that row exactly. With the class uncalibrated it printed `reportable subset:
// NONE` on all three `M1` pairs instead. No figure was wrong and nothing was
// invalidated — the posture was stricter, not looser — but a published claim
// stopped being recomputable from a fresh clone, which is the one property this
// file exists to hold.
//
// v2 calibrates the mount from the mount's own 14 committed row-runs and the
// row adjudicates again. WHAT IT THEN SAYS IS NOT THIS FILE'S TO ARRANGE: all
// 14 come back in control, so the reportable subset is the whole ensemble, and
// the publication rule above — reaching `M1` for the first time, because an
// interval needs a reportable run to be formed from — returns INSTRUMENT-LIMITED
// on every pair. The intervals sit wholly above 1.0 and clear neither the ship
// bar below nor the architecture-kill above. That is the rule doing its job on
// a row it had never been applied to, and the tension it creates with
// `rf2-t2flm`'s published magnitude is a RULING, recorded on the studio page
// and not resolved by any code here.

'use strict';

const fs = require('node:fs');

// ONE SEAT FOR THE GATE'S ARITHMETIC (rf2-8a746) — see `clock_run.cjs`, which
// requires the same module. Two copies would be two adjudicators.
const checkstd = require('./clock_check_standard.cjs');

const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

/** Linear-interpolated quantile — the percentile-interval definition below. */
function quantile(xs, q) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  const h = (v.length - 1) * q;
  const lo = Math.floor(h);
  const hi = Math.ceil(h);
  return v[lo] + (v[hi] - v[lo]) * (h - lo);
}

/** mean / min / max over the runs of the ensemble — never a bare mean. */
function ens(xs) {
  const v = xs.filter(Number.isFinite);
  if (v.length === 0) return { n: 0, mean: NaN, min: NaN, max: NaN };
  return { n: v.length, mean: mean(v), min: Math.min(...v), max: Math.max(...v) };
}

const PAIRS = ['hicasso / reagent-subs', 'hicasso / uix-subs', 'uix-subs / reagent-subs'];
const SEGMENTS = ['reagent-subs', 'uix-subs', 'hicasso'];

/**
 * THE ESTIMATOR THAT TOUCHES NEITHER FLOOR (rf2-w3yxd).
 *
 * Every `bar` this program prints is a DOUBLE ratio. Each arm is divided first
 * by the floor measured in its OWN segment of that round, so the quantity the
 * pages publish is
 *
 *     (U / F_U) ÷ (R / F_R) = (U / R) × (F_R / F_U)
 *
 * and the two floors are not one value. `the-clock-behind-the-published-rows.md`
 * §2 first said they divide out, corrected itself, and left the size of the
 * second term unmeasured on these clocks — because forming the counterpart
 * needs the per-sample readings, and no dataset from any published ensemble on
 * this instrument had survived.
 *
 * This is that counterpart: one arm's p50 over the other's, in the same round,
 * no floor and no tare. `p0-converged-witness-set.md`'s "Two estimators,
 * published together" has carried the pair for the in-page rows since
 * `rf2-zb3qg`; the two agreeing on a row's verdict is the evidence that the
 * normalisation is doing its job rather than injecting a term of its own.
 *
 * IT IS NOT `crossSegment`'s `raw` FLAG. That flag drops the TARE and keeps the
 * floor normalisation, so it is neither of the two quantities either page is
 * about — the bead says so in terms, and it is worth the sentence because the
 * word `raw` appears on both.
 *
 * ANALYSIS, NOT INSTRUMENT. Nothing here is measured; every number is a
 * function of `roundsTask` / `rounds` / `inPageRounds` as the driver wrote
 * them, which is what makes a retained dataset worth retaining. `rounds` is
 * `[round][segment][arm]`, and each leg's segment and arm carry the same name
 * by the construction the driver's own `crossSegment` uses.
 */
function rawCrossSegment(rounds, pair) {
  const [num, den] = pair.split(' / ');
  const per = [];
  for (const round of rounds || []) {
    const n = round && round[num] && round[num][num];
    const d = round && round[den] && round[den][den];
    if (!Array.isArray(n) || !Array.isArray(d) || n.length === 0 || d.length === 0) {
      return { n: 0, mean: NaN, min: NaN, max: NaN };
    }
    per.push(p50(n) / p50(d));
  }
  return ens(per);
}

/**
 * THE CHECK STANDARD, RE-APPLIED TO THE RUN'S OWN READINGS (rf2-8a746).
 *
 * RECOMPUTED, NOT READ BACK, and this is the one gate in the roster below that
 * is — so the reason has to be here rather than inferred. Every other gate
 * mirrors a verdict `clock_run.cjs` TOOK, and a taken verdict can be lost in a
 * file, which is why absent is not clean. A check standard is not that kind of
 * thing. It is versioned DATA that a READER applies, and applying today's
 * standard to a dataset taken before it existed is the entire point of
 * freezing limits as data instead of as a stored boolean: recalibrate, bump
 * the version, and every retained run can be re-adjudicated without re-running
 * the box. `clock_run.cjs` stores its own verdict beside the readings anyway,
 * carrying the standard's id and version, so a reader can see which standard a
 * run was taken under; nothing here reads it, because a run adjudicated under
 * v1 and re-read under v2 must come back with v2's answer.
 *
 * FAIL CLOSED, at each seat: no design record, no raw readings, a missing arm,
 * a block that is not a finite reading, a row whose class has no standard, and
 * a class nobody has calibrated are each a REFUSAL.
 */
function checkStandardFor(row, dataset) {
  const r = row || {};
  const d = dataset || {};
  // THE CLASS DECIDES BEFORE THE READINGS DO. A row whose class is
  // uncalibrated cannot be certified in control however clean its blocks are,
  // and asking for readings first would report the wrong refusal. Every class
  // the standard declares is calibrated as of v2 (rf2-x7x10), so this seat has
  // no live instance today; it stays because the next class added to `classes`
  // will arrive before its limits do, and that class must refuse rather than
  // be waved through by a branch nobody kept.
  const klass = checkstd.classOf(r.rowId);
  if (!klass || !checkstd.STANDARD.classes[klass].calibrated) return checkstd.checkStandard([], r.rowId);
  if (!d.design || typeof d.design.tare !== 'boolean') {
    return {
      ok: false,
      why: 'no design record — whether these readings are tared was not serialised, and a level ratio taken with the wrong tare is not that ratio',
    };
  }
  if (!Array.isArray(r.roundsTask) || r.roundsTask.length === 0) {
    return {
      ok: false,
      why: 'no raw per-sample TaskDuration readings — the check standard is applied to the readings themselves, so a record that did not store them cannot be shown to have been in control',
    };
  }
  const xs = [];
  for (const seg of SEGMENTS) {
    for (const round of r.roundsTask) {
      const s = round && round[seg];
      const c = s && s['ctl-2x'];
      const f = s && s.floor;
      const t = s && s.plumb;
      if (!Array.isArray(c) || !Array.isArray(f) || (d.design.tare && !Array.isArray(t))) {
        return {
          ok: false,
          why: `the check standard's arms are not all in the record — segment \`${seg}\` is missing ctl-2x, floor or the tare`,
        };
      }
      xs.push((p50(c) - (d.design.tare ? p50(t) : 0)) / (p50(f) - (d.design.tare ? p50(t) : 0)));
    }
  }
  return checkstd.checkStandard(xs, r.rowId);
}

/**
 * THE PUBLICATION RULE (rf2-8a746), and it is deliberately small.
 *
 * `bar` is the claim's threshold and `architectureKill` the other side of it.
 * `draws` and `seed` are here rather than inline because a published interval
 * a reader cannot reproduce is the fault this whole program exists to fix: the
 * bootstrap is seeded, so running this file twice over the same datasets
 * yields the same interval to the last place.
 *
 * `minRuns` is the lane's own standing sentence — two runs are not an ensemble
 * — as arithmetic. A run-preserving bootstrap resamples OUTER units, and with
 * one or two of them the outer distribution is essentially the observed runs
 * themselves, so the interval understates by construction. Below the floor the
 * row publishes INSTRUMENT-LIMITED rather than a narrow interval.
 */
const EFFECT = {
  bead: 'rf2-8a746',
  draws: 4000,
  seed: 20260807,
  alpha: 0.05,
  bar: 1.0,
  architectureKill: 1.5,
  minRuns: 3,
  method: 'paired log-ratios; hierarchical percentile bootstrap, outer RUNS resampled before inner ROUNDS (Kalibera & Jones 2013)',
};

/** A small deterministic PRNG, so a published interval is reproducible. */
function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * ONE RUN'S PAIRED LOG-RATIOS, one per round — the quantity the product claim
 * asserts, at fixed witness and fixed K.
 *
 * PAIRED AND SAME-ROUND, which is what makes it the right quantity: both arms
 * are read in the same round of the same run, so whatever that round's box was
 * doing is common to the pair. LEVEL over LEVEL, touching neither floor, for
 * the reason the check standard is level-denominated — the floors are two
 * different values and dividing by them injects a second term. And LOGS,
 * because a ratio's sampling distribution is skewed and its log's is not, so
 * the arithmetic is done where the mean is a summary.
 *
 * Returns `null` for a row that carries no usable readings, which is a
 * refusal to state an interval rather than an interval over nothing.
 */
function pairedLogRatios(row, pair) {
  const [num, den] = pair.split(' / ');
  const out = [];
  for (const round of (row && row.roundsTask) || []) {
    const n = round && round[num] && round[num][num];
    const d = round && round[den] && round[den][den];
    if (!Array.isArray(n) || !Array.isArray(d) || n.length === 0 || d.length === 0) return null;
    const q = p50(n) / p50(d);
    if (!Number.isFinite(q) || q <= 0) return null;
    out.push(Math.log(q));
  }
  return out.length ? out : null;
}

/**
 * THE EFFECT-SIZE INTERVAL — a hierarchical percentile bootstrap that
 * PRESERVES THE RUN (rf2-8a746, Kalibera & Jones 2013).
 *
 * Runs are the outer unit and rounds the inner one, and the order matters: a
 * bootstrap that pooled every round of every run and resampled that pool would
 * treat two rounds of one run as independent of each other, which they are
 * not — a run is a box state, a browser process and a bundle — and would
 * return an interval far too narrow. So each draw resamples RUNS with
 * replacement first and then, within each drawn run, its ROUNDS.
 *
 * `runs` is `[[logRatio per round]]`. The point estimate is the mean over runs
 * of each run's mean, which is the balanced-design estimator the bootstrap is
 * the distribution of.
 */
function effectInterval(runs) {
  const usable = (runs || []).filter((r) => Array.isArray(r) && r.length > 0);
  if (usable.length === 0) return null;
  const point = mean(usable.map(mean));
  const rnd = mulberry32(EFFECT.seed);
  const draws = [];
  for (let b = 0; b < EFFECT.draws; b++) {
    const outer = [];
    for (let i = 0; i < usable.length; i++) {
      const run = usable[Math.floor(rnd() * usable.length)];
      const inner = [];
      for (let j = 0; j < run.length; j++) inner.push(run[Math.floor(rnd() * run.length)]);
      outer.push(mean(inner));
    }
    draws.push(mean(outer));
  }
  return {
    runs: usable.length,
    rounds: usable.map((r) => r.length),
    point: Math.exp(point),
    lo: Math.exp(quantile(draws, EFFECT.alpha / 2)),
    hi: Math.exp(quantile(draws, 1 - EFFECT.alpha / 2)),
    draws: EFFECT.draws,
    seed: EFFECT.seed,
    method: EFFECT.method,
  };
}

/**
 * MAY THIS ROW PUBLISH A MAGNITUDE? (rf2-8a746)
 *
 * Both conditions, and neither on its own. THE WHOLE INTERVAL must lie on one
 * side of the threshold — an interval straddling it is a row that has not
 * measured a direction, and quoting its point estimate is quoting the middle
 * of a range that contains parity. AND THE EFFECT MUST CLEAR THE SAME-RUN
 * NOISE BAND, because a confidence interval narrows with runs while the band
 * says what a single run of this instrument can resolve at all: an effect
 * inside the band is a difference the instrument cannot see in one sitting,
 * however many sittings are pooled.
 *
 * `noiseBandPct` is the WIDEST band among the runs pooled — a claim must clear
 * the noisiest run it is drawn from, which is the fail-closed direction and
 * the same direction this program's other asymmetries take.
 *
 * A LOWER RATIO IS FASTER: these are times. So the ship claim is the whole
 * interval BELOW 1.0 and the architecture-kill is the whole interval ABOVE
 * 1.5.
 */
function effectVerdict(iv, noiseBandPct) {
  if (!iv) return { publishes: false, verdict: 'INSTRUMENT-LIMITED', why: 'no usable paired readings in any pooled run — no interval was formed' };
  if (iv.runs < EFFECT.minRuns) {
    return {
      publishes: false,
      verdict: 'INSTRUMENT-LIMITED',
      why:
        `${iv.runs} pooled run(s) is not an ensemble — a run-preserving interval resamples OUTER units, and ` +
        `below ${EFFECT.minRuns} the outer distribution is the observed runs themselves and the interval understates`,
    };
  }
  const effectPct = Math.abs(iv.point - 1) * 100;
  const clearsNoise = Number.isFinite(noiseBandPct) && effectPct > noiseBandPct;
  const below = iv.hi < EFFECT.bar;
  const above = iv.lo > EFFECT.architectureKill;
  if (!below && !above) {
    return {
      publishes: false,
      verdict: 'INSTRUMENT-LIMITED',
      why:
        `the interval [${fmt(iv.lo)} – ${fmt(iv.hi)}] does not lie wholly on one side of the ${EFFECT.bar} bar ` +
        `or the ${EFFECT.architectureKill} architecture-kill threshold`,
    };
  }
  if (!clearsNoise) {
    return {
      publishes: false,
      verdict: 'INSTRUMENT-LIMITED',
      why:
        `the effect is ${fmt(effectPct, 1)}% and the widest same-run noise band among the pooled runs is ` +
        `${Number.isFinite(noiseBandPct) ? fmt(noiseBandPct, 1) + '%' : 'not recorded'} — an effect inside the band ` +
        `is a difference this instrument cannot resolve in one sitting, however many sittings are pooled`,
    };
  }
  return {
    publishes: true,
    verdict: below ? 'MAGNITUDE PUBLISHABLE — the whole interval is below the 1.0 bar' : 'ARCHITECTURE-KILL — the whole interval is above 1.5',
    why: `the interval [${fmt(iv.lo)} – ${fmt(iv.hi)}] clears the threshold and the ${fmt(effectPct, 1)}% effect clears the ${fmt(noiseBandPct, 1)}% same-run band`,
  };
}

/**
 * IS EVERY BAR THIS RUN PUBLISHED ON THIS ROW ADJUDICATED?
 *
 * rf2-y7mw7's term, and the one this file was missing entirely: a row whose
 * bars have no band has a magnitude and nothing to tell it from parity, and
 * this program prints its ensemble mean under the label "control-passing
 * subset". A dataset that stored no bar verdict at all is not adjudicated
 * either — absent is not clean.
 *
 * EVERY bar, not one of them. #7489's merged-PR audit found this predicate
 * asking `some`, which admitted a run into the reportable subset — for every
 * pair, including the unadjudicated one — on the strength of a single
 * adjudicated bar elsewhere on the row. The driver that wrote these datasets
 * sets `unadjudicated` from a row-wide flag, so no dataset in the tree today
 * contains a mixed row; but this program's whole reason for existing is that
 * datasets are re-read long after the driver that produced them, and a
 * predicate that is correct only for the files that happen to exist now is a
 * fail-open waiting for the first file that does not.
 *
 * AND EVERY BAR MUST SAY SO, which #7550's merged-PR audit found this
 * predicate still not asking. It read `!bars[n].unadjudicated` — truthiness —
 * so a bar the file stored as `{}`, with no verdict in it at all, came back
 * ADJUDICATED and the run was pooled into the published mean. That is the
 * absent-is-not-clean contract two paragraphs up being contradicted by the
 * line below it, and it matters MORE here than in the driver: the driver reads
 * an object `seam.assess` built moments earlier in the same process, while
 * this program reads a file, and a file is where a field goes missing. A bar
 * counts as adjudicated only on `unadjudicated === false`; a bar that is
 * missing, null, or silent has not been adjudicated, it has been lost.
 *
 * `row` is one entry of a dataset's `rows`, as `clock_run.cjs` wrote it.
 */
function adjudicated(row) {
  const bars = (row && row.seamTask && row.seamTask.rows) || {};
  const names = Object.keys(bars);
  return names.length > 0 && names.every((n) => !!bars[n] && bars[n].unadjudicated === false);
}

/**
 * EVERY GATE A RUN MUST CLEAR BEFORE ITS MAGNITUDE MAY BE POOLED, in the order
 * `clock_run.cjs` applies them, each read off the serialised record and each
 * FAIL-CLOSED: a verdict that is missing, null or silent has not been passed,
 * it has been lost.
 *
 * Stated as data rather than as a boolean expression for two reasons. The
 * refusal has to be NAMED — a run dropped from the subset without a reason is
 * indistinguishable from a run that was selected away — and a roster can be
 * quantified over, so `clock_exit_path.test.cjs` drives every gate rather than
 * the handful someone remembered to write a case for.
 *
 * Each entry's `why` returns `null` for a pass and the refusal's own sentence
 * otherwise. `scope` says what it is PRIMARILY handed — the dataset envelope,
 * or one row — and a row gate receives the envelope as a second argument,
 * because the envelope carries the design a reading has to be interpreted
 * under (`rf2-8a746`: whether the readings are tared decides what a level
 * ratio taken from them means). Most row gates ignore it.
 */
const GATES = [
  // --- the file's own two-tier verdict, before anything in it is read ------
  {
    id: 'canonical',
    scope: 'dataset',
    why: (d) =>
      d.canonical === true
        ? null
        : d.notCanonicalWhy
          ? `NOT the published evidence set — ${d.notCanonicalWhy}`
          : 'the file carries no `canonical` verdict, so it has never been shown to be the published ' +
            'evidence set — absent is not a pass (rf2-2rtt6.31)',
  },
  // --- the driver's fatal checks, in its own order -------------------------
  {
    id: 'page-errors',
    scope: 'row',
    why: (r) =>
      !Array.isArray(r.pageErrors)
        ? 'no `pageErrors` record — whether the page threw was not serialised'
        : r.pageErrors.length
          ? `the page THREW during the run: ${r.pageErrors.join(' | ')}`
          : null,
  },
  {
    id: 'guard-net',
    scope: 'row',
    why: (r) =>
      r.guardRefuse === false
        ? null
        : r.guardRefuse === true
          ? 'the arm-order guard REFUSED this row on taskNet'
          : 'no arm-order guard verdict on taskNet',
  },
  {
    id: 'guard-task',
    scope: 'row',
    why: (r) =>
      r.guardRefuseTask === false
        ? null
        : r.guardRefuseTask === true
          ? 'the arm-order guard REFUSED this row on the published clock'
          : 'no arm-order guard verdict on the published clock',
  },
  {
    id: 'canonical-dom',
    scope: 'row',
    why: (r) =>
      r.parityOk === true
        ? null
        : r.parityOk === false
          ? 'the canonical-DOM gate found arms building DIFFERENT PAGES — a ratio between two pages is not a ratio'
          : 'no canonical-DOM verdict was serialised',
  },
  {
    id: 'ctl3-parity',
    scope: 'row',
    why: (r) =>
      !('ctl3Parity' in r)
        ? "no three-point-control parity record — whether the control's own arms agreed was not serialised"
        : r.ctl3Parity === null || r.ctl3Parity.ok === true
          ? null
          : "the three-point control's own arms built DIFFERENT PAGES",
  },
  {
    id: 'keystroke-witness',
    scope: 'row',
    why: (r) =>
      !('kbWitness' in r)
        ? "no per-keystroke witness record — whether the row's n counts anything was not serialised"
        : r.kbWitness === null || r.kbWitness.ok === true
          ? null
          : 'the per-keystroke witness REFUSED — its accounting does not close',
  },
  {
    id: 'unverified',
    scope: 'row',
    why: (r) =>
      !r.tally || !Number.isFinite(r.tally.unverified)
        ? 'no write-verification tally'
        : r.tally.unverified > 0
          ? `${r.tally.unverified} unverified operation(s) of ${r.tally.writes} — a window whose value never reached the page`
          : null,
  },
  // THE CEILINGS FIRE BEFORE ANY CONTROL IS CONSULTED, and this file refuses
  // on BOTH while the driver (rf2-ymi6j) refuses only on the published clock's
  // and reports the frame-only one. The asymmetry is deliberate and it is the
  // safe direction: a consumer stricter than its producer can withhold a
  // magnitude the driver would have allowed, never publish one it refused.
  {
    id: 'ceiling-net',
    scope: 'row',
    why: (r) =>
      !r.seam || !r.seam.verdict || typeof r.seam.verdict.ceilingBreached !== 'boolean'
        ? 'no frame-only band verdict'
        : r.seam.verdict.ceilingBreached
          ? "the run's own frame-only reproducibility band exceeds the ceiling"
          : null,
  },
  {
    id: 'ceiling-task',
    scope: 'row',
    why: (r) =>
      !r.seamTask || typeof r.seamTask.ceilingBreached !== 'boolean'
        ? 'no published-clock band verdict'
        : r.seamTask.ceilingBreached
          ? "the run's own reproducibility band exceeds the ceiling on the published clock"
          : null,
  },
  // THE CHECK STANDARD (rf2-8a746). It replaced two rules at once and the old
  // gate's shape is worth recording, because a reader of an old dataset will
  // meet it: this seat used to ask `ctl3.ok` on a bulk row and `ctlOk` /
  // `ctlTask.ok` — the +/-25% band about a THEORETICAL 2.00x, every block —
  // everywhere else. The three-point control read 0 of 42 bulk row-runs in
  // band, on a mis-derived prediction and a denominator ~2 sigma from zero;
  // and the all-blocks rule was a separate defect that would have refused just
  // as hard behind any control, `0.835^18 = 3.9%`.
  //
  // The replacement is level over level, against an EMPIRICAL centre frozen
  // with its provenance, with a run-rejection rule made of the run's own
  // location and dispersion at stated error rates. The refusal is the
  // standard's own sentence, so a reader is told which of the two terms went.
  {
    id: 'check-standard',
    scope: 'row',
    why: (r, d) => checkStandardFor(r, d).why || null,
  },
  {
    id: 'event-timing',
    scope: 'row',
    why: (r) =>
      !('etVerdict' in r)
        ? 'no Event-Timing verdict was serialised'
        : r.etVerdict === null || r.etVerdict.ok === true
          ? null
          : 'the Event-Timing witness REFUSED',
  },
  {
    id: 'adjudication',
    scope: 'row',
    why: (r) => (adjudicated(r) ? null : 'a bar this run published carries no adjudication verdict'),
  },
];

/**
 * THE FILE-LEVEL GATE, BY NAME. `main` announces a dataset's own verdict ahead
 * of every table and carries it into the exit code, and reaching it by array
 * position would make that announcement depend on the roster's order.
 */
const CANONICAL_GATE = GATES.find((g) => g.id === 'canonical');

/**
 * WHY THIS RUN MAY NOT BE POOLED — every reason, never the first one.
 *
 * All of them, because a reader deciding whether to re-take a run needs to know
 * that four gates failed rather than that one did, and because a filter that
 * short-circuits reports the cheapest fault rather than the interesting one.
 *
 * This selects nothing away from the TABLE. Every dataset handed to this
 * program appears in the per-run listing whatever this returns, with these
 * reasons printed beside it; the subset is shown beside the whole ensemble and
 * never instead of it.
 */
function refusals(row, dataset) {
  const d = dataset || {};
  const r = row || null;
  const why = [];
  for (const g of GATES) {
    if (g.scope === 'dataset') {
      const w = g.why(d);
      if (w) why.push(w);
    } else if (!r) {
      why.push(`no row to read \`${g.id}\` from`);
    } else {
      const w = g.why(r, d);
      if (w) why.push(w);
    }
  }
  return why;
}

/** MAY THIS RUN'S MAGNITUDE ON THIS ROW BE POOLED INTO THE REPORTABLE SUBSET? */
function reportable(row, dataset) {
  return refusals(row, dataset).length === 0;
}

/**
 * THE RESPONSIVENESS REGIME, RE-ADJUDICATED OFF THE STORED RUN (rf2-swwud).
 *
 * The per-keystroke row has no band and never will: its control burns a fixed
 * 50 ms, so `control/floor` reads `(F+50)/F` and moves with `F` — a fine
 * sensitivity control, and not a pair whose true ratio is a property of the
 * page. Two clean runs on two verifiably idle boxes produced two UNADJUDICATED
 * rows for exactly that reason, which is what settled the question the row was
 * raised on: the obstruction is the rig, not the box, and no scheduling moves
 * it. The 2026-08-06 ruling therefore adjudicates this row by EVENT TIMING
 * instead of by the band, and the tables above become diagnostics.
 *
 * WHY IT IS COMPUTED HERE. The re-adjudication is of the runs already on disk
 * — no new window was taken and none is needed. `clock_run.cjs` stores the
 * repaired witness's own per-arm accounting in every dataset, so this reads
 * `kbWitness.perArm` rather than regrouping raw entries: the driver's witness
 * owns what forms an interaction and what a censored key is, and a second
 * grouping here would be a second adjudicator.
 *
 * IT PUBLISHES NO MAGNITUDE AND THE RIDER IS NOT OPTIONAL. Event Timing
 * resolves 8 ms buckets above a 16 ms floor, so this row detects only a
 * difference that crosses a bucket boundary. "Indistinguishable at one frame"
 * is a statement about the instrument's resolution and NOT a measured tie
 * below it — the arms' separations are sub-frame, the per-sample grid is
 * coarser than they are, and every diagnostic bar straddles 1.0.
 *
 * `row` is one entry of a dataset's `rows`. Returns `null` for a row that
 * carries no keystroke witness, which is every other row.
 */
function responsivenessRegime(row) {
  const w = row && row.kbWitness;
  if (!w || !w.perArm) return null;
  const perArm = Object.entries(w.perArm).map(([arm, a]) => {
    const ds = a.durations || [];
    return {
      arm,
      control: /\/ctl-/.test(arm),
      sent: a.sent,
      observed: a.observed,
      censored: a.censored,
      n: ds.length,
      p50: p50(ds),
      min: ds.length ? Math.min(...ds) : NaN,
      max: ds.length ? Math.max(...ds) : NaN,
    };
  });
  const observedArms = perArm.filter((a) => !a.control && a.n > 0);
  const controlArms = perArm.filter((a) => a.control && a.n > 0);
  const buckets = [...new Set(observedArms.flatMap((a) => [a.min, a.max]))];
  // The control's median over its OWN readings, pooled across segments —
  // every duration once, rather than each arm's median re-weighted.
  const controlDurations = controlArms.flatMap((a) => w.perArm[a.arm].durations || []);
  return {
    perArm,
    totals: w.totals || null,
    // ONE BUCKET ACROSS EVERY OBSERVED ARM is the whole verdict. It is stated
    // as a property of the readings rather than asserted, so a run in which
    // the arms DID separate would say so here instead.
    indistinguishable: observedArms.length > 0 && buckets.length === 1,
    frame: buckets.length === 1 ? buckets[0] : NaN,
    controlP50: controlDurations.length ? p50(controlDurations) : NaN,
    // The instrument moved when the work moved, or this run states nothing.
    controlMoved: controlArms.length > 0 && controlArms.every((a) => a.p50 > buckets[0]),
    // THE RIDER'S OWN NUMBERS, both stored by the driver rather than derived
    // here: the finest per-sample step this run could resolve, and whether the
    // diagnostic bars separate the arms at this n. They do not.
    grain: Array.isArray(row.granularity) && row.granularity.length ? row.granularity[0] : NaN,
    diagnosticBars: PAIRS.filter((p) => row.barTask && row.barTask[p]).map((p) => ({
      pair: p,
      mean: row.barTask[p].mean,
      min: row.barTask[p].min,
      max: row.barTask[p].max,
      straddles1: row.barTask[p].min <= 1 && row.barTask[p].max >= 1,
    })),
  };
}

function main(argv) {
  const files = argv.filter((a) => !a.startsWith('--'));
  if (files.length === 0) {
    console.error('usage: clock_readjudicate.cjs <run1.json> [run2.json ...]');
    return 1;
  }

  const datasets = files.map((f) => ({ file: f, data: JSON.parse(fs.readFileSync(f, 'utf8')) }));

  // Row ids in the order the first dataset produced them, so the report reads
  // in run order rather than in whatever order Object.keys happens to give.
  const rowIds = [];
  for (const { data } of datasets) {
    for (const r of data.rows) if (!rowIds.includes(r.rowId)) rowIds.push(r.rowId);
  }

  console.log(';; ==== ENSEMBLE RE-ADJUDICATION (rf2-emvod) ====');
  console.log(`;; datasets ${datasets.length}`);
  for (const { file, data } of datasets) {
    console.log(
      `;;   ${file}  label=${data.label || '-'}  when=${data.when}  chromium=${data.chromium}  ` +
        `design=${data.design.rounds}x(${data.design.warmup}+${data.design.samples}) tare=${data.design.tare}`
    );
  }

  // THE FILE'S OWN VERDICT, ANNOUNCED BEFORE ANY TABLE. A dataset that does
  // not say it is the published evidence set is still read, still tabled and
  // still printed in full — capture is preserved — but it may not contribute a
  // magnitude, and the reader is told that at the top rather than left to
  // notice an empty subset at the bottom.
  const ineligible = datasets.filter(({ data }) => !!CANONICAL_GATE.why(data));
  if (ineligible.length) {
    console.log('');
    console.log(';; !! NOT ELIGIBLE PUBLISHED EVIDENCE — every table below is printed, and none of it');
    console.log(';; !! may be quoted as a magnitude. This program exits nonzero for that reason.');
    for (const { file, data } of ineligible) console.log(`;; !!   ${file}: ${CANONICAL_GATE.why(data)}`);
  }

  for (const rowId of rowIds) {
    const runs = datasets
      .map(({ file, data }) => ({ file, data, row: data.rows.find((r) => r.rowId === rowId) }))
      .filter((x) => x.row);
    if (runs.length === 0) continue;

    console.log('');
    console.log(`;; ======== ROW ${rowId} — ${runs.length} runs ========`);

    // --- gates, per run -------------------------------------------------------
    // THE COLUMN THAT DECIDES IS THE CHECK STANDARD'S (rf2-8a746), and the
    // `ctl-2x` mean beside it is the raw reading it is taken from rather than
    // a second verdict. The old `ctlPASS` column read `ctlTask.ok` — the
    // all-blocks band about a theoretical 2.00x — and that rule no longer
    // exists to be printed.
    console.log(';; run  guard(net/task)  ctl-2x task  ctl2x/floor  STANDARD  band(task)  band(net)  floor abs ms');
    for (const { file, data, row } of runs) {
      const floorAbs = p50(
        SEGMENTS.map((s) => {
          const d = row.decomposition[`${s}/floor`];
          return d ? d.task / d.n : NaN;
        }).filter(Number.isFinite)
      );
      const cs = checkStandardFor(row, data);
      console.log(
        `;;  ${shortName(file).padEnd(6)} ${(row.guardRefuse ? 'REFUSE' : 'ok').padEnd(7)}` +
          `${(row.guardRefuseTask ? 'REFUSE' : 'ok').padEnd(8)}` +
          `${row.ctlTask ? fmt(row.ctlTask.measured.mean, 3).padStart(9) : '      n/a'}` +
          `${cs.location ? fmt(cs.location.measured, 3).padStart(13) : '          n/a'}` +
          `${(cs.ok ? 'IN CTRL' : 'REFUSED').padStart(10)}` +
          `${row.bandTask === null || row.bandTask === undefined ? '       n/a' : (fmt(row.bandTask * 100, 1) + '%').padStart(10)}` +
          `${row.seam && row.seam.band !== null ? (fmt(row.seam.band * 100, 1) + '%').padStart(11) : '        n/a'}` +
          `${fmt(floorAbs, 3).padStart(13)}`
      );
    }

    // --- WHY EACH REFUSED RUN IS REFUSED --------------------------------------
    //
    // The audit's own term: reject an incomplete or failed dataset in the
    // reportable ACCOUNTING while still displaying it in the full ensemble.
    // Every reason, named, per run — so a run that leaves the subset leaves it
    // for a stated cause and can be told from one that was selected away.
    console.log(';; run    REFUSED FROM THE REPORTABLE SUBSET (and retained in every table here)');
    for (const { file, data, row } of runs) {
      const why = refusals(row, data);
      console.log(
        `;;  ${shortName(file).padEnd(6)} ${why.length ? why.join('; ') : '— reportable: every gate this dataset serialises is clean'}`
      );
    }

    // --- the three clocks, per pair -------------------------------------------
    for (const pair of PAIRS) {
      const inPage = runs.map(({ row }) => (row.inPageBar && row.inPageBar[pair] ? row.inPageBar[pair].mean : NaN));
      const net = runs.map(({ row }) => (row.bar && row.bar[pair] ? row.bar[pair].tared.mean : NaN));
      const task = runs.map(({ row }) => (row.barTask && row.barTask[pair] ? row.barTask[pair].mean : NaN));
      if (!task.some(Number.isFinite)) continue;

      // The REPORTABLE subset, beside the whole ensemble and never instead of
      // it. `reportable` is at module scope (above) so it can be driven by a
      // test. rf2-y7mw7's third term landed here wrong, and it took a merged-PR
      // audit rather than a gate to find it, precisely because nothing could
      // reach it.
      const passIdx = runs.map(({ row, data }, i) => (reportable(row, data) ? i : -1)).filter((i) => i >= 0);
      const taskPass = passIdx.map((i) => task[i]);

      const e = ens(task);
      const en = ens(net);
      const ei = ens(inPage);
      console.log('');
      console.log(`;;   PAIR ${pair}`);
      // EVERY POOLED MEAN BELOW IS A DIAGNOSTIC (rf2-8a746). "reportable
      // subset" means the runs that cleared every gate, which is a statement
      // about eligibility and never about publication: what publishes a
      // magnitude is the EFFECT-SIZE INTERVAL at the foot of this block, and a
      // subset mean quoted above it would be exactly the un-adjudicated
      // magnitude the ruling forbids.
      console.log(
        `;;     [pooled means below are DIAGNOSTICS — the interval at the foot of this block is what publishes]`
      );
      console.log(
        `;;     raw TaskDuration (PUBLISHED)  ${fmt(e.mean)}x  [${fmt(e.min)} – ${fmt(e.max)}]  n=${e.n}` +
          (taskPass.length
            ? `   reportable subset ${fmt(ens(taskPass).mean)}x n=${taskPass.length}`
            : '   reportable subset: NONE')
      );
      console.log(`;;     taskNet (frame-only, superseded) ${fmt(en.mean)}x  [${fmt(en.min)} – ${fmt(en.max)}]`);
      console.log(`;;     in-page performance.now()        ${fmt(ei.mean)}x  [${fmt(ei.min)} – ${fmt(ei.max)}]`);

      // BOTH ESTIMATORS, SIDE BY SIDE (rf2-w3yxd). The three lines above are
      // the floor-normalised bar on each window; these are the same three
      // windows read with the raw quotient, which touches neither floor. They
      // are printed together and never one instead of the other: the pair
      // agreeing on a row's verdict is what bounds what the normalisation is
      // doing to the mean, and a disagreement is a finding rather than a
      // choice between them.
      const rawTask = runs.map(({ row }) => rawCrossSegment(row.roundsTask, pair).mean);
      const rawNet = runs.map(({ row }) => rawCrossSegment(row.rounds, pair).mean);
      const rawInPage = runs.map(({ row }) => rawCrossSegment(row.inPageRounds, pair).mean);
      const erT = ens(rawTask);
      const erN = ens(rawNet);
      const erI = ens(rawInPage);
      if (erT.n > 0) {
        const rawPass = passIdx.map((i) => rawTask[i]).filter(Number.isFinite);
        console.log(
          `;;     RAW quotient (neither floor) raw TaskDuration ${fmt(erT.mean)}x  [${fmt(erT.min)} – ${fmt(erT.max)}]  n=${erT.n}` +
            (rawPass.length
              ? `   reportable subset ${fmt(ens(rawPass).mean)}x n=${rawPass.length}`
              : '   reportable subset: NONE')
        );
        console.log(
          `;;       the same quotient on taskNet ${fmt(erN.mean)}x  [${fmt(erN.min)} – ${fmt(erN.max)}]` +
            `, on the in-page window ${fmt(erI.mean)}x  [${fmt(erI.min)} – ${fmt(erI.max)}]` +
            `  — floor-normalised minus raw, published clock: ${fmt((e.mean - erT.mean) * 100, 1)}pp`
        );
      }
      console.log(';;     run   in-page   taskNet   TaskDuration   band   margin   verdict');
      runs.forEach(({ file, row }, i) => {
        const b = row.bandTask;
        const margin = Math.abs(task[i] - 1) * 100;
        const bandPct = Number.isFinite(b) ? b * 100 : NaN;
        const breached =
          (row.seam && row.seam.verdict && row.seam.verdict.ceilingBreached) ||
          (row.seamTask && row.seamTask.ceilingBreached);
        // WHETHER THIS BAR IS ADJUDICATED IS READ PER BAR, not off the row's
        // `bandTask`. `bandTask` is row-wide, and a row-wide reading here
        // would print "clears its 21.4% band" for the very bar the subset
        // above has just refused for carrying no band — the same disagreement
        // between a printed column and a decision that rf2-y7mw7 is about,
        // pointing the other way. They agree on every dataset in the tree,
        // and that agreement is `seam.assess`'s to withdraw, not this
        // program's to depend on. The sentence is left row-phrased because no
        // dataset yet exists in which a row's bars disagree; the bar's own
        // `why` is in the dataset for the day one does.
        //
        // And it is read with `adjudicated`'s OWN token, `=== false`, for the
        // same reason: a bar record present but silent is not a bar that
        // cleared anything, and a column reading `!!` while the subset reads
        // `=== false` is that disagreement again, one field further in.
        const barRec = (row.seamTask && row.seamTask.rows && row.seamTask.rows[pair]) || null;
        const barUnadjudicated = barRec ? barRec.unadjudicated !== false : !Number.isFinite(bandPct);
        const verdict = breached
          ? `BAND CEILING BREACHED — whole run refused before any control is consulted`
          : barUnadjudicated
            ? 'UNADJUDICATED — no proportional control on this row'
            : !checkStandardFor(row, runs[i].data).ok
              ? `check standard REFUSED — the run was not shown to be in control`
              : margin > bandPct
                ? `clears its ${fmt(bandPct, 1)}% band`
                : `INSIDE the band — instrument-limited`;
        console.log(
          `;;     ${shortName(file).padEnd(5)} ${fmt(inPage[i]).padStart(8)} ${fmt(net[i]).padStart(9)}` +
            `${fmt(task[i]).padStart(14)} ${(Number.isFinite(bandPct) ? fmt(bandPct, 1) + '%' : 'n/a').padStart(7)}` +
            ` ${(fmt(margin, 1) + '%').padStart(7)}   ${verdict}`
        );
      });

      // --- THE EFFECT-SIZE INTERVAL, and the only thing here that publishes --
      //
      // rf2-8a746's fourth part. The rows above describe; this decides. It is
      // computed over the REPORTABLE runs only — a run refused by any gate is
      // not evidence about the page — and it is stated whichever way it falls,
      // because a rule that only prints when it likes the answer is not one.
      const ivRuns = passIdx.map((i) => pairedLogRatios(runs[i].row, pair)).filter(Boolean);
      const iv = effectInterval(ivRuns);
      const bands = passIdx
        .map((i) => runs[i].row.seamTask && runs[i].row.seamTask.band)
        .filter((b) => Number.isFinite(b))
        .map((b) => b * 100);
      const noise = bands.length ? Math.max(...bands) : NaN;
      const ev = effectVerdict(iv, noise);
      console.log(
        `;;     EFFECT-SIZE INTERVAL (${EFFECT.bead}) — paired same-round LEVEL ratio at fixed witness and ` +
          `fixed K, neither floor; ${EFFECT.method}`
      );
      if (iv) {
        console.log(
          `;;       point ${fmt(iv.point)}x   ${fmt((1 - EFFECT.alpha) * 100, 0)}% CI [${fmt(iv.lo)} – ${fmt(iv.hi)}]   ` +
            `over ${iv.runs} reportable run(s) x ${iv.rounds[0]} rounds, ${iv.draws} draws, seed ${iv.seed}`
        );
        console.log(
          `;;       effect |1 - point| ${fmt(Math.abs(iv.point - 1) * 100, 1)}%   widest same-run band among the ` +
            `pooled runs ${Number.isFinite(noise) ? fmt(noise, 1) + '%' : 'n/a'}   ` +
            `thresholds: bar ${EFFECT.bar} (whole interval below), architecture-kill ${EFFECT.architectureKill} (whole interval above)`
        );
      } else {
        console.log(`;;       no interval — ${passIdx.length} run(s) cleared every gate and none carried usable paired readings`);
      }
      console.log(`;;       VERDICT ${ev.verdict} — ${ev.why}`);
      if (!ev.publishes) {
        console.log(
          `;;       so this row publishes INSTRUMENT-LIMITED on this pair and NEVER a magnitude, and no mean ` +
            `printed above may be quoted as one (${EFFECT.bead}).`
        );
      }
    }

    // --- the responsiveness regime, per run (rf2-swwud) -----------------------
    //
    // Printed immediately under the bars it re-labels, because the bars above
    // are what this row USED to be published as and the ruling's whole content
    // is that they are diagnostics.
    for (const { file, row } of runs) {
      const reg = responsivenessRegime(row);
      if (!reg) continue;
      console.log('');
      console.log(
        `;;   RESPONSIVENESS REGIME — ${shortName(file)}, re-adjudicated on EVENT TIMING rather than on the band`
      );
      console.log(';;     arm                        sent  observed  censored   ET p50   range');
      for (const a of reg.perArm) {
        console.log(
          `;;     ${a.arm.padEnd(26)}${String(a.sent).padStart(4)}${String(a.observed).padStart(10)}` +
            `${String(a.censored).padStart(10)}${(fmt(a.p50, 1) + ' ms').padStart(10)}   ` +
            `[${fmt(a.min, 1)} – ${fmt(a.max, 1)}]${a.control ? '   (control)' : ''}`
        );
      }
      if (reg.totals) {
        console.log(
          `;;     accounting: ${reg.totals.sent} keys sent = ${reg.totals.observed} observed + ` +
            `${reg.totals.censored} censored under the 16 ms floor`
        );
      }
      console.log(
        `;;     VERDICT ${
          reg.indistinguishable
            ? `Hicasso, Reagent-on-subs and UIx-on-subs are INDISTINGUISHABLE at Event Timing's ` +
              `resolution — every observed interaction was one frame (${fmt(reg.frame, 1)} ms)`
            : `the arms did NOT fall in one bucket on this run — the frame statement does not hold here`
        }`
      );
      console.log(
        `;;     control ${reg.controlMoved ? 'PASS' : 'FAIL'} — ctl-50ms reads ${fmt(reg.controlP50, 1)} ms ` +
          `against the arms' ${fmt(reg.frame, 1)} ms, so the instrument demonstrably moves when the work moves`
      );
      console.log(
        `;;     POWERED TO DETECT: Event Timing resolves 8 ms buckets above a 16 ms floor, so this row ` +
          `detects only a difference that CROSSES a bucket boundary. It is not a measured tie below that.`
      );
      console.log(
        `;;       the arms' separations are sub-frame; this run's finest per-sample step is ` +
          `${fmt(reg.grain, 3)} ms, and every diagnostic bar above straddles 1.0 ` +
          `(${reg.diagnosticBars.filter((b) => b.straddles1).length} of ${reg.diagnosticBars.length}) — ` +
          `no magnitude is published from this row.`
      );
    }

    // --- absolutes, pooled over the ensemble ----------------------------------
    //
    // PRINTED FOR EVERY ROW, because both of this instrument's defects were
    // visible here and in no ratio anywhere.
    console.log('');
    console.log(';;   ABSOLUTES — mean ms per sample, pooled over the ensemble');
    console.log(';;     arm                          task   taskNet   in-page  devtools    script    layout');
    const armKeys = [];
    for (const { row } of runs) for (const k of Object.keys(row.decomposition)) if (!armKeys.includes(k)) armKeys.push(k);
    for (const k of armKeys) {
      const acc = { task: [], taskNet: [], devtools: [], script: [], layout: [], inPage: [] };
      for (const { row } of runs) {
        const d = row.decomposition[k];
        if (!d || !d.n) continue;
        acc.task.push(d.task / d.n);
        acc.taskNet.push(d.taskNet / d.n);
        acc.devtools.push(d.devtools / d.n);
        acc.script.push(d.script / d.n);
        acc.layout.push(d.layout / d.n);
      }
      // The in-page window is not in `decomposition`; it is in the raw
      // per-sample readings, which is where it has to be read from.
      for (const { row } of runs) {
        if (!row.inPageRounds) continue;
        const [seg, arm] = k.split('/');
        const xs = row.inPageRounds.flatMap((rd) => (rd[seg] && rd[seg][arm]) || []);
        if (xs.length) acc.inPage.push(p50(xs));
      }
      if (acc.task.length === 0) continue;
      console.log(
        `;;     ${k.padEnd(28)} ${fmt(mean(acc.task), 3).padStart(6)} ${fmt(mean(acc.taskNet), 3).padStart(9)}` +
          ` ${(acc.inPage.length ? fmt(mean(acc.inPage), 3) : 'n/a').padStart(9)} ${fmt(mean(acc.devtools), 3).padStart(9)}` +
          ` ${fmt(mean(acc.script), 3).padStart(9)} ${fmt(mean(acc.layout), 3).padStart(9)}`
      );
    }

    // --- THE DOOR -------------------------------------------------------------
    //
    // `DevToolsCommandDuration` absorbs page script that runs INSIDE a protocol
    // command. Which door the driver uses therefore decides whether `taskNet`
    // is a frame-only reading or an honest one, and the rows here disagree
    // about the door: `M1`, `bulk300`, `bulk100` and `narrow` drive the
    // operation through `page.evaluate` (`Runtime.callFunctionOn`), while
    // `keystroke` drives it through `page.keyboard.press` (the Input domain).
    //
    // Two numbers settle it per row, and neither needs the other harness:
    //
    //   * whether `devtools` TRACKS the arm. If it carries the arm's script it
    //     must move with the arm; if it is only the protocol's round trip it is
    //     roughly constant across arms that differ by milliseconds of work.
    //   * what `ScriptDuration` reads. The renderer's own script counter does
    //     not see script run through a protocol command either, so a row whose
    //     script is being absorbed reports a mount of 901 elements as costing
    //     hundredths of a millisecond of script.
    {
      const armsIn = (row) => Object.keys(row.decomposition).filter((k) => !k.endsWith('/plumb'));
      const per = (sel) =>
        mean(
          runs.map(({ row }) => {
            const vals = armsIn(row).map((k) => sel(row.decomposition[k]));
            return Math.max(...vals) - Math.min(...vals);
          })
        );
      const scriptMax = mean(
        runs.map(({ row }) => Math.max(...armsIn(row).map((k) => row.decomposition[k].script / row.decomposition[k].n)))
      );
      const dvSpread = per((d) => d.devtools / d.n);
      const ipSpread = per((d) => d.taskNet / d.n); // stand-in scale for "how much arms differ"
      console.log('');
      console.log(
        `;;   THE DOOR — devtools spread across arms ${fmt(dvSpread, 3)} ms; largest ScriptDuration on any arm ` +
          `${fmt(scriptMax, 4)} ms. ` +
          (scriptMax < 0.2
            ? `ScriptDuration sees essentially NOTHING, so the operation ran inside a protocol command and ` +
              `taskNet on this row is FRAME-ONLY.`
            : `ScriptDuration SEES the arms' work, so the operation did not run inside a protocol command and ` +
              `taskNet on this row was never corrupted.`) +
          `  (taskNet spread ${fmt(ipSpread, 3)} ms, for scale)`
      );
    }

    // --- THE ADDITIVE RESIDUAL ------------------------------------------------
    const ctlMeans = runs.map(({ row }) => (row.ctlTask ? row.ctlTask.measured.mean : NaN)).filter(Number.isFinite);
    if (ctlMeans.length) {
      const r = mean(ctlMeans);
      const cOverW = (2 - r) / (r - 1);
      const floorTared = mean(
        runs.map(({ row }) => {
          const f = p50(
            SEGMENTS.map((s) => {
              const d = row.decomposition[`${s}/floor`];
              return d ? d.task / d.n : NaN;
            }).filter(Number.isFinite)
          );
          const pl = p50(
            SEGMENTS.map((s) => {
              const d = row.decomposition[`${s}/plumb`];
              return d ? d.task / d.n : NaN;
            }).filter(Number.isFinite)
          );
          return f - pl;
        })
      );
      // floorTared = W + c, so W = floorTared / (1 + c/W) and c = W * (c/W).
      const W = floorTared / (1 + cOverW);
      console.log('');
      console.log(
        `;;   ADDITIVE RESIDUAL — ctl-2x reads ${fmt(r, 4)}x where the page it builds is exactly 2.00x. ` +
          `Inverting (2W+c)/(W+c): c/W = ${fmt(cOverW, 3)}, and with the floor's tared absolute at ` +
          `${fmt(floorTared, 3)} ms that is W = ${fmt(W, 3)} ms of page-proportional work and ` +
          `c = ${fmt(floorTared - W, 3)} ms that the plumb tare does not remove.`
      );
    }
  }

  console.log('');
  console.log(';; ==== CROSS-ROW: is the residual the same constant everywhere? ====');
  console.log(';; row        ctl-2x(task)   implied c/W   implied c ms   floor tared ms');
  for (const rowId of rowIds) {
    const runs = datasets.map(({ data }) => data.rows.find((r) => r.rowId === rowId)).filter(Boolean);
    const ctlMeans = runs.map((row) => (row.ctlTask ? row.ctlTask.measured.mean : NaN)).filter(Number.isFinite);
    if (!ctlMeans.length) {
      console.log(`;; ${rowId.padEnd(11)} (no proportional control on this row)`);
      continue;
    }
    const r = mean(ctlMeans);
    const cOverW = (2 - r) / (r - 1);
    const floorTared = mean(
      runs.map((row) => {
        const f = p50(SEGMENTS.map((s) => (row.decomposition[`${s}/floor`] ? row.decomposition[`${s}/floor`].task / row.decomposition[`${s}/floor`].n : NaN)).filter(Number.isFinite));
        const pl = p50(SEGMENTS.map((s) => (row.decomposition[`${s}/plumb`] ? row.decomposition[`${s}/plumb`].task / row.decomposition[`${s}/plumb`].n : NaN)).filter(Number.isFinite));
        return f - pl;
      })
    );
    const W = floorTared / (1 + cOverW);
    console.log(
      `;; ${rowId.padEnd(11)} ${fmt(r, 4).padStart(11)} ${fmt(cOverW, 3).padStart(13)} ${fmt(floorTared - W, 3).padStart(14)} ${fmt(floorTared, 3).padStart(16)}`
    );
  }

  // FAIL-CLOSED, AND THE EXIT CODE IS WHERE THAT LANDS. `rf2-cvvb7`'s recorded
  // gap was a study nobody could recompute; the repair is not just that a
  // program exists but that running it over evidence it may not publish from
  // says so in the one place a script can be believed. Everything is printed
  // either way — a refusal is about what may be QUOTED, never about throwing a
  // measurement away (`rf2-2rtt6.31`).
  if (ineligible.length) {
    console.log('');
    console.log(
      `;; EXIT 3 — ${ineligible.length} of ${datasets.length} dataset(s) are not eligible published evidence. ` +
        'No reportable subset above was drawn from them, and no figure here may be quoted as a magnitude.'
    );
    return 3;
  }
  return 0;
}

function shortName(f) {
  const m = /([^/\\]+)\.json$/.exec(f);
  return m ? m[1] : f;
}

// `rawCrossSegment` is deliberately NOT here. This list is pinned by
// `clock_exit_path.test.cjs`, and the pin is right: the exported names are the
// DECISIONS a test must be able to drive. The raw estimator decides nothing —
// it is a second description of a row printed beside the first — so exporting
// it would widen a guard to accommodate a reporting helper.
//
// rf2-8a746 adds four names and every one of them is a decision: the gate a
// row now turns on, the quantity the product claim asserts, the interval over
// it, and the rule that says whether that interval may publish. `EFFECT` is
// exported with them because a test that could not read the seed could not
// pin the procedure.
module.exports = {
  GATES, adjudicated, refusals, reportable, responsivenessRegime,
  checkStandardFor, pairedLogRatios, effectInterval, effectVerdict, EFFECT,
};

// Requiring this file must not run it: `clock_exit_path.test.cjs` drives the
// two predicates above directly, which it cannot do if the module body reads
// argv and exits.
if (require.main === module) {
  const code = main(process.argv.slice(2));
  if (code !== 0) process.exit(code);
}
