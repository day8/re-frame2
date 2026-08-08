#!/usr/bin/env node
// THE CHECK STANDARD FOR THE HICASSO CLOCK (rf2-8a746).
//
//   node freehand/test/re_frame/bench/hicasso/clock_check_standard.cjs --selftest
//
// ## What this replaces, and why the replacement is a LEVEL
//
// rf2-8a746 retired the three-point difference-of-differences control as a
// gate on this instrument — not re-sited, retired — on two independently
// fatal grounds, both arithmetic:
//
//   * THE PREDICTION WAS MIS-DERIVED. `(d2-d0)/(d1-d0) = 2.0101` is the
//     expectation only if `T` is affine in the dirty set. On the published
//     TaskDuration clock it is not: marginal cost falls 12.6 -> 6.8-7.2 µs
//     per cell across `[1,100]` / `[100,200]`, so the statistic's true centre
//     is 1.546-1.578 and sits 2.6% ABOVE the 1.5076 edge at which it refuses.
//   * THE ESTIMATOR WAS ILL-CONDITIONED. `den = T(100) - T(1)` is 1.23-1.25 ms
//     carrying 0.60-0.67 ms of dispersion — 1.85-2.09 sigma from zero, with
//     `corr(T(1), T(100)) ~ 0.4`. That is the classic Fieller ratio problem
//     (Franz, arXiv:0710.2024): differencing away the additive constant also
//     throws away the pedestal that made each arm's own estimator error look
//     small, and those errors are independent between arms so they ADD.
//
// Re-siting at 100/200/300 fixes the centre (1.9975/2.0777) and halves the
// denominator: in-band blocks FALL from 47% to 33%. Both available sitings
// fail and the conditioning arithmetic says any siting must. So the axis
// changes rather than the points: this standard's denominator is the floor's
// WHOLE TARED READING, ~4.5 ms, which carries the same ~0.4 ms of estimator
// error — 9% relative rather than 48%.
//
// ## And why its centre is measured rather than predicted
//
// `ctl-2x` builds exactly twice the floor's page, so its arithmetic prediction
// is 2.00x. It does not read 2.00x, on any row, in any configuration, on
// either clock, and the reason is the same non-affinity: page-doubling has a
// component that does not scale. Asserting a theoretical value against a
// non-affine clock is precisely the mistake being retired, so `clock_check_
// standard.json` freezes what this instrument WAS MEASURED TO READ — centre,
// location limits and dispersion limit — with its provenance, its error rates
// and the conditions that force a recalibration beside it (NIST e-Handbook
// 2.3.5's check-standard doctrine).
//
// ## The all-blocks rule went with it, and it was a SEPARATE defect
//
// The retired run-rejection rule was "every one of the 18 blocks inside the
// tolerance band". Its arithmetic is `p^18`: the premise-MEETING layout
// statistic has 83.5% of blocks in band and still passes 4 of 42 runs, because
// `0.835^18 = 3.9%`. Swapping controls while keeping "every block" would not
// have unblocked anything. So the tolerance band and the run-rejection rule
// are now two different things with two different, stated error rates — see
// `errorRates` in the JSON, and `checkStandard` below, which reports the band
// and rejects on neither block count nor block extremes.
//
// ## Why it is its own module
//
// `clock_run.cjs` applies it to a run it just measured and `clock_
// readjudicate.cjs` applies it to a dataset off disk. Two copies of the
// arithmetic would be two adjudicators, which is the fault this lane's exit
// path was rebuilt to remove, so there is one function and both require it.
// The JSON beside it is data on purpose: recalibrating is editing that file
// and bumping its version, never editing this one.

'use strict';

const STANDARD = require('./clock_check_standard.json');

const r4 = (x) => (Number.isFinite(x) ? Math.round(x * 10000) / 10000 : NaN);

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
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
 * THE RUN'S DISPERSION, ROBUSTLY. `IQR / 1.349` is the normal-consistent scale
 * estimator, so its number is comparable with a standard deviation while not
 * being movable by one wild block — which matters here for the same reason
 * rf2-8bgqq moved the headline to the median: a rule that a single extreme
 * block can trip is the all-blocks rule wearing a summary statistic's clothes.
 */
function robustScale(xs) {
  return (quantile(xs, 0.75) - quantile(xs, 0.25)) / 1.349;
}

/** Which class of the standard adjudicates a row — declared, never inferred. */
function classOf(rowId) {
  for (const [name, klass] of Object.entries(STANDARD.classes)) {
    if ((klass.rows || []).includes(rowId)) return name;
  }
  return null;
}

/**
 * IS THIS RUN IN CONTROL? — the whole verdict, over one row-run's per-block
 * `ctl-2x / floor` level ratios.
 *
 * FAIL CLOSED AT EVERY SEAT, in this lane's standing sense: a row with no
 * blocks, a block that is not a finite reading, a row whose class has no
 * standard, and a class that has not been calibrated are each a REFUSAL and
 * never a pass. An uncalibrated class is the one worth spelling out — it does
 * not mean "no opinion", it means nothing has ever established what this
 * instrument reads on that class, and a certificate nobody calibrated is not
 * a certificate.
 *
 * `why` is `null` for a pass and the refusal's own sentence otherwise, in the
 * idiom `clock_readjudicate.cjs`'s `GATES` are written in, so a caller prints
 * a reason rather than a boolean.
 */
function checkStandard(perBlockRatios, rowId) {
  const klassName = classOf(rowId);
  const klass = klassName ? STANDARD.classes[klassName] : null;
  const xs = Array.isArray(perBlockRatios) ? perBlockRatios : [];
  const finite = xs.filter(Number.isFinite);
  const base = {
    standard: { id: STANDARD.id, version: STANDARD.version, ruling: STANDARD.ruling },
    rowId: rowId || null,
    rowClass: klassName,
    calibrated: !!(klass && klass.calibrated),
    rule: STANDARD.runRejection,
    measured: {
      n: xs.length,
      finite: finite.length,
      p50: r4(p50(finite)),
      mean: finite.length ? r4(finite.reduce((a, b) => a + b, 0) / finite.length) : NaN,
      min: finite.length ? r4(Math.min(...finite)) : NaN,
      max: finite.length ? r4(Math.max(...finite)) : NaN,
      scale: r4(robustScale(finite)),
    },
    location: null,
    dispersion: null,
    tolerance: null,
    errorRates: klass ? klass.errorRates || null : null,
    ok: false,
    why: null,
  };

  if (!klassName) {
    base.why =
      `row \`${rowId}\` has no class in the check standard — ` +
      (STANDARD.noStandard.rows.includes(rowId) ? STANDARD.noStandard.why : 'no proportional control arm adjudicates it');
    return base;
  }
  if (!klass.calibrated) {
    base.why = `the \`${klassName}\` class of the check standard is NOT CALIBRATED — ${klass.why}`;
    return base;
  }
  if (finite.length === 0 || finite.length !== xs.length) {
    base.why =
      xs.length === 0
        ? 'no blocks — an empty block set is an absent reading, not a run in control'
        : `${xs.length - finite.length} of ${xs.length} blocks are not finite readings of a level ratio`;
    return base;
  }

  const [lo, hi] = klass.location.limits;
  const centre = klass.centre;
  const scale = robustScale(finite);
  const median = p50(finite);
  const [tlo, thi] = klass.tolerance.band;
  const inBand = finite.filter((x) => x >= tlo && x <= thi).length;

  base.location = {
    statistic: STANDARD.statistics.location,
    centre,
    limits: [lo, hi],
    measured: r4(median),
    ok: median >= lo && median <= hi,
  };
  base.dispersion = {
    statistic: STANDARD.statistics.dispersion,
    limit: klass.dispersion.limit,
    measured: r4(scale),
    ok: scale <= klass.dispersion.limit,
  };
  // REPORTED AND NOT A RULE (rf2-8a746). It is printed because a reader wants
  // to see where the blocks fell; nothing below reads `inBand` into `ok`.
  base.tolerance = {
    band: [tlo, thi],
    inBand,
    of: finite.length,
    fraction: r4(inBand / finite.length),
    gating: false,
  };
  base.ok = base.location.ok && base.dispersion.ok;
  base.why = base.ok
    ? null
    : [
        base.location.ok
          ? null
          : `the run's block median ${r4(median)}x is outside the frozen location limits ` +
            `[${lo} – ${hi}] about an empirical centre of ${centre}x`,
        base.dispersion.ok
          ? null
          : `the run's robust scale ${r4(scale)} exceeds the frozen dispersion limit ${klass.dispersion.limit} ` +
            `— the box could not reproduce identical work block to block`,
      ]
        .filter(Boolean)
        .join('; ');
  return base;
}

/** The one line a report prints for a check-standard verdict. */
function formatCheckStandard(v) {
  if (!v) return [];
  const head =
    `;;   ${v.ok ? 'IN CONTROL' : 'REFUSED   '} check standard \`${v.standard.id}\` v${v.standard.version} ` +
    `[${v.rowClass || 'no class'}${v.calibrated ? '' : ', UNCALIBRATED'}] (${v.standard.ruling})`;
  const lines = [head];
  if (v.location && v.dispersion) {
    lines.push(
      `;;     location  ${v.location.measured}x median of ${v.measured.finite} blocks against ` +
        `[${v.location.limits[0]} – ${v.location.limits[1]}] about an EMPIRICAL centre ${v.location.centre}x ` +
        `— ${v.location.ok ? 'inside' : 'OUTSIDE'}`
    );
    lines.push(
      `;;     dispersion ${v.dispersion.measured} robust scale (IQR/1.349) against a limit of ` +
        `${v.dispersion.limit} — ${v.dispersion.ok ? 'inside' : 'OVER'}`
    );
    lines.push(
      `;;     tolerance ${v.tolerance.inBand} of ${v.tolerance.of} blocks inside [${v.tolerance.band[0]} – ` +
        `${v.tolerance.band[1]}] — REPORTED, and it decides nothing: the retired rule required all of them, ` +
        `and 0.835^18 = 3.9% is why a control meeting its own premise passed 4 of 42 runs`
    );
  }
  if (v.why) lines.push(`;;     why      ${v.why}`);
  return lines;
}

/**
 * THE STANDARD'S OWN FIXTURES, in the idiom every other adjudicator on this
 * lane is held to: the refusals stated as cases rather than as prose, run
 * before a browser opens and driven by `clock_exit_path.test.cjs` in CI.
 *
 * THE SABOTAGE CASE IS THE ONE THAT MATTERS (rf2-8a746 acceptance criterion
 * 2). `HCLOCK_CTL3_SABOTAGE` makes the THREE-POINT control's top arm render
 * fewer cells than it declares, and it is the fixture form of that knob —
 * an arm that does not do what it declares — pointed at the standard's own
 * arm instead: `ctl-2x` declares the floor's page doubled and builds 140 of
 * its 300 boundaries. A standard nobody has seen refuse is a standard of
 * unmeasured sensitivity, and this lane has found that defect nine times.
 *
 * Every world here is synthetic and pure. No measurement is taken, no window
 * is opened, and the numbers are chosen to sit on the frozen limits rather
 * than the limits chosen to admit the numbers.
 */
function checkStandardSelfTest() {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: !!ok, detail: detail || '' });
  const BULK = 'bulk300';
  const bulk = STANDARD.classes.bulk;

  // A block world in the shape the instrument actually has: a floor sample is
  // `W + c`, where `W` is the page-proportional work and `c` is the part that
  // does not scale with the page. `ctl-2x` builds `P` times the page, so it
  // reads `P*W + c` and the block ratio is `(P*W + c)/(W + c)`.
  const W = 3.0;
  const C = 1.1628; // c/W = 0.3876, which puts the doubling arm on the frozen centre
  const blocksAt = (P, n, jitter) =>
    Array.from({ length: n || 18 }, (_, i) => {
      const j = jitter ? jitter * (i % 2 === 0 ? 1 : -1) * (1 + (i % 3) / 3) : 0;
      return (P * W + C + j) / (W + C);
    });

  // 1. THE HEALTHY WORLD PASSES, and it comes first in weight: every refusal
  //    below is worth nothing against a standard that refuses everything.
  const healthy = checkStandard(blocksAt(2, 18, 0.05), BULK);
  check(
    'a doubling arm on the frozen centre is IN CONTROL',
    healthy.ok && healthy.why === null && Math.abs(healthy.location.measured - bulk.centre) < 0.01,
    JSON.stringify(healthy.location)
  );

  // 2. THE SABOTAGE, which is the fixture form of `HCLOCK_CTL3_SABOTAGE`
  //    aimed at this standard's own arm: `ctl-2x` declares twice the floor's
  //    300 boundaries and renders 140 of them. Every other gate on the run
  //    passes and this is the only one that can see it.
  const sabotaged = checkStandard(blocksAt(140 / 300, 18, 0.05), BULK);
  check(
    'THE SABOTAGE: an arm rendering 140 of the 300 boundaries it declares doubled REFUSES',
    !sabotaged.ok && !sabotaged.location.ok && /outside the frozen location limits/.test(sabotaged.why || ''),
    `${sabotaged.location && sabotaged.location.measured}x — ${sabotaged.why}`
  );
  check(
    'and the sabotage refusal is the LOCATION rule, not a dispersion accident',
    sabotaged.dispersion.ok === true,
    JSON.stringify(sabotaged.dispersion)
  );
  // The knob's other direction: an arm that OVERBUILDS is equally a sabotage,
  // and a one-sided standard would wave it through.
  const over = checkStandard(blocksAt(3, 18, 0.05), BULK);
  check('an arm building THREE times the page it declares doubled REFUSES too', !over.ok && !over.location.ok);

  // 3. THE DISPERSION HALF, which is the run-rejection rule's other term. A
  //    box that could not reproduce identical work block to block is refused
  //    on the same centre — this world's median sits dead on it.
  const noisy = checkStandard(blocksAt(2, 18, 1.4), BULK);
  check(
    'a run whose blocks scatter past the frozen dispersion limit REFUSES on the same centre',
    !noisy.ok && noisy.location.ok && !noisy.dispersion.ok,
    `median ${noisy.location.measured}x, scale ${noisy.dispersion.measured}`
  );

  // 4. THE DEFECT THIS STANDARD REPAIRS, asserted rather than described. The
  //    retired rule refused a run for ONE out-of-band block. A world with a
  //    healthy median, a scale inside the limit and two blocks outside the
  //    tolerance band is exactly that run, and it must now PASS while the
  //    band still REPORTS the two.
  const twoOut = blocksAt(2, 18, 0.05);
  twoOut[0] = 1.28; // just under the reported band's lower edge
  twoOut[1] = 2.19; // just over its upper edge
  const survives = checkStandard(twoOut, BULK);
  check(
    'THE RETIRED RULE, INVERTED: two out-of-band blocks no longer refuse a run whose median and scale hold',
    survives.ok && survives.tolerance.inBand === 16 && survives.tolerance.of === 18 && survives.tolerance.gating === false,
    JSON.stringify(survives.tolerance)
  );
  check(
    'and the tolerance band is REPORTED rather than read into the verdict',
    survives.tolerance.fraction < 1 && survives.ok === true,
    JSON.stringify(survives.tolerance)
  );

  // 5. FAIL CLOSED, at each seat.
  const uncal = checkStandard(blocksAt(2), 'M1');
  check(
    'an UNCALIBRATED class refuses rather than abstains, and hands the mount to rf2-t2flm by id',
    !uncal.ok && uncal.calibrated === false && /rf2-t2flm/.test(uncal.why || ''),
    uncal.why
  );
  const noClass = checkStandard(blocksAt(2), 'keystroke');
  check(
    'a row with no proportional control arm cannot be certified in control',
    !noClass.ok && noClass.rowClass === null && /fixed 50 ms/.test(noClass.why || ''),
    noClass.why
  );
  check(
    'an unknown row is refused too — a class is granted by calibration, never by default',
    !checkStandard(blocksAt(2), 'a-row-nobody-has-calibrated').ok
  );
  check('an EMPTY block set is not a pass', !checkStandard([], BULK).ok && /empty block set/.test(checkStandard([], BULK).why));
  check('and a block that is not a finite reading refuses the run', !checkStandard([...blocksAt(2, 17, 0.05), NaN], BULK).ok);
  check('a missing block set is absent, not clean', !checkStandard(undefined, BULK).ok && !checkStandard(null, BULK).ok);

  // 6. THE STANDARD IS DATA AND SAYS WHICH DATA IT IS. A verdict that did not
  //    carry its version could not be re-read after a recalibration.
  check(
    'every verdict carries the standard it was taken against, by id and version',
    healthy.standard.id === STANDARD.id &&
      healthy.standard.version === STANDARD.version &&
      healthy.standard.ruling === 'rf2-8a746' &&
      Number.isInteger(STANDARD.version)
  );
  check(
    'the frozen limits are the JSON\'s, never a literal here',
    healthy.location.limits[0] === bulk.location.limits[0] &&
      healthy.location.limits[1] === bulk.location.limits[1] &&
      healthy.dispersion.limit === bulk.dispersion.limit &&
      healthy.location.centre === bulk.centre
  );
  check(
    'and the limits bracket the centre rather than sitting to one side of it',
    bulk.location.limits[0] < bulk.centre && bulk.centre < bulk.location.limits[1]
  );

  return { checks };
}

module.exports = { STANDARD, checkStandard, checkStandardSelfTest, formatCheckStandard, classOf, robustScale, quantile };

if (require.main === module) {
  const { checks } = checkStandardSelfTest();
  for (const c of checks) console.error(`[check-standard] ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.detail ? `  — ${c.detail}` : ''}`);
  const bad = checks.filter((c) => !c.ok);
  console.error(`[check-standard] ${checks.length} checks, ${bad.length} failed`);
  if (bad.length) process.exit(1);
}
