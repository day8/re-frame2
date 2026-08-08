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
//
// ## v2 — THE MOUNT CLASS IS CALIBRATED (rf2-x7x10, 2026-08-08)
//
// v1 froze the BULK class and left the MOUNT class uncalibrated and failing
// closed, which was the correct restraint at the time: `rf2-8a746`'s ruling
// seeded bulk only and the mount half was `rf2-t2flm`'s concurrent seat. The
// consequence fell between the two rulings. `rf2-t2flm`'s published `M1` row is
// conditioned on the every-block `ctl-2x` rule — the rule `rf2-8a746` retired
// EVERYWHERE ON THIS INSTRUMENT — so after v1 the row carried a label naming a
// rule nothing implements, and `clock_readjudicate.cjs`, which that ruling's
// close reason records as reproducing every published figure exactly, returned
// `reportable subset: NONE` on every `M1` pair instead.
//
// So v2 calibrates the mount from the mount's own 14 committed row-runs, in
// v1's derivation with nothing changed but the data it is applied to. THE TWO
// CLASSES SIT 4.4% APART on the identical statistic through the identical door
// — 1.9 of the mount's own between-run SDs — and that gap is why there is a
// second class rather than a second row list on the first. Importing bulk's
// limits would have asserted against the mount a value never measured on it,
// which is the mis-specification `rf2-8a746` retired, one row class over;
// `rf2-8a746` saw it coming and handed the finding forward by id.
//
// WHAT THIS DOES NOT DO. It moves no published figure and it loosens nothing:
// the mount's location limits are 30% NARROWER than bulk's, because the mount's
// between-run scatter is smaller. What it restores is the reproduction path — a
// reader with the datasets can run the readjudicator and get the row back — and
// what the row then says is the readjudicator's to state, not this file's.
//
// ## v3 — THE LIMITS, VALIDATED OUT OF SAMPLE (rf2-c1974, 2026-08-08)
//
// `rf2-8a746` part 3 asks for limits frozen from an INDEPENDENT baseline, and
// v1 and v2 both said in their own `provenance.independence` fields that they
// did not have one: seeded from the runs they were quoted against, so `0 of 42`
// and `14 of 14` were consistency checks and not false-refusal measurements.
// That was honest and it was also a gap, because the moment a NEW run is judged
// in control the verdict rests on limits nobody has tested out of sample.
//
// WHAT THIS CORPUS CAN SUPPORT, AND WHAT IT CANNOT. `rf2-pzqy8`'s census
// standard held out a genuinely later COMMIT — its corpus spans five days and a
// code change. This one does not: every run in both clock ensembles carries a
// `when` of 2026-08-07 and the two ensembles start 87 minutes apart, on the
// evidence one tree. So the strongest hold-out available here is by SITTING —
// derive each class's limits from ONE ensemble, judge the OTHER against them —
// and the JSON claims exactly that and no more. It crosses a coffee break, not
// a commit, and the residual is written into `recalibrateOn` as the next
// ensemble's job rather than left to read as if the criterion were discharged.
//
// BOTH WAYS, because one direction's answer depends on which ensemble happened
// to be picked. On BULK they agree and every held-out row-run is in control, 42
// of 42. ON MOUNT THEY DISAGREE, and that is the finding: `clock-emvod`'s 8 runs
// give limits that admit all 6 of `clock-w3yxd`, while `clock-w3yxd`'s 6 give
// limits that refuse 4 of `clock-emvod`'s 8 — every refusal on LOCATION, none on
// dispersion. The cause is not a large between-session shift (the centres are
// 1.81% apart, less than bulk's 2.44%) but the w3yxd sitting's own tightness: a
// between-run SD under a quarter of emvod's, so a +/-3 sigma budget too narrow
// to contain the offset. NOTHING WAS WIDENED TO REPAIR IT — the fence this bead
// was raised under — and nothing needed to be: the shipped limits pool both
// sittings, so they already carry the between-session component and admit all
// 14. What the failure impeaches is SINGLE-SITTING CALIBRATION on that class,
// which is why `recalibrateOn` now names it.
//
// Every number above is in the JSON, once. Neither centre is written here, and
// the fixtures below derive their worlds from the classes' own frozen values
// for the same reason: a fixture carrying a copy of a limit is a second
// calibration waiting to drift from the first. The hold-out is the same rule
// applied to a hold-out record: the arithmetic that produced it is driven over
// the committed corpus by `clock_exit_path.test.cjs`, which has the datasets;
// the fixtures here stay synthetic and check the record's SHAPE and its claims.

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
    base.why =
      `the \`${klassName}\` class of the check standard is NOT CALIBRATED — ` +
      (klass.why ||
        'no location or dispersion limits have ever been established for it, so nothing has established what ' +
          'this instrument reads on that class and no run of one can be certified in control');
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
  const MOUNT = 'M1';
  const bulk = STANDARD.classes.bulk;
  const mount = STANDARD.classes.mount;

  // A block world in the shape the instrument actually has: a floor sample is
  // `W + c`, where `W` is the page-proportional work and `c` is the part that
  // does not scale with the page. `ctl-2x` builds `P` times the page, so it
  // reads `P*W + c` and the block ratio is `(P*W + c)/(W + c)`.
  //
  // `c` IS SOLVED FOR THE CLASS'S OWN FROZEN CENTRE rather than written down —
  // `(2W + c)/(W + c) = centre` gives `c = W(2 - centre)/(centre - 1)` — so a
  // world sits on the limits it is meant to sit on by construction. A literal
  // here would be a second copy of a calibrated number, and two copies of a
  // calibration drift (rf2-x7x10, which exists because a justification drifted
  // from the ruling it cited).
  const W = 3.0;
  const cFor = (centre) => (W * (2 - centre)) / (centre - 1);
  const blocksAtIn = (klass, P, n, jitter) => {
    const C = cFor(klass.centre);
    return Array.from({ length: n || 18 }, (_, i) => {
      const j = jitter ? jitter * (i % 2 === 0 ? 1 : -1) * (1 + (i % 3) / 3) : 0;
      return (P * W + C + j) / (W + C);
    });
  };
  const blocksAt = (P, n, jitter) => blocksAtIn(bulk, P, n, jitter);

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

  // 5. THE MOUNT CLASS (rf2-x7x10), and the case that matters is that it is
  //    NOT THE BULK CLASS. The two centres are 4.4% apart, which is small
  //    enough that copying bulk's limits across would have looked harmless and
  //    large enough that it would have been the retired mis-specification
  //    again — asserting against a row class a value never measured on it.
  const mountHealthy = checkStandard(blocksAtIn(mount, 2, 18, 0.05), MOUNT);
  check(
    'a doubling arm on the MOUNT class\'s own frozen centre is IN CONTROL',
    mountHealthy.ok && mountHealthy.why === null && mountHealthy.rowClass === 'mount' && mountHealthy.calibrated === true,
    JSON.stringify(mountHealthy.location)
  );
  check(
    'THE FENCE: the mount is judged by its OWN limits, and they are not bulk\'s',
    mountHealthy.location.centre !== bulk.centre &&
      mountHealthy.location.limits[0] !== bulk.location.limits[0] &&
      mountHealthy.location.limits[1] !== bulk.location.limits[1] &&
      mountHealthy.location.centre === mount.centre,
    `mount ${mount.centre}x [${mount.location.limits}] against bulk [${bulk.location.limits}]`
  );
  check(
    'and the mount\'s location limits are TIGHTER than bulk\'s — calibrating a class did not loosen one',
    mount.location.limits[1] - mount.location.limits[0] < bulk.location.limits[1] - bulk.location.limits[0],
    `mount width ${r4(mount.location.limits[1] - mount.location.limits[0])} against bulk ${r4(bulk.location.limits[1] - bulk.location.limits[0])}`
  );
  // WHERE THE TWO CLASSES ACTUALLY DECIDE DIFFERENTLY, stated as cases rather
  // than asserted. They are NOT disjoint: the classes' location limits overlap
  // heavily, and each class's centre sits comfortably inside the other's
  // limits, so a run near either centre passes under either. The calibration
  // earns its keep at the EDGES, one at each end, and a fixture that claimed
  // more than that would be overselling it.
  const reading = (r, jitter) => blocksAtIn({ centre: r }, 2, 18, jitter);
  const overBulk = (bulk.location.limits[1] + mount.location.limits[1]) / 2; // above bulk's ceiling, under the mount's
  const underMount = (bulk.location.limits[0] + mount.location.limits[0]) / 2; // above bulk's floor, under the mount's
  check(
    `a run reading ${r4(overBulk)}x is IN CONTROL on the mount and REFUSED on bulk — the mount's ceiling is higher`,
    checkStandard(reading(overBulk, 0.02), MOUNT).ok === true && checkStandard(reading(overBulk, 0.02), BULK).ok === false,
    `mount ceiling ${mount.location.limits[1]}, bulk ceiling ${bulk.location.limits[1]}`
  );
  check(
    `a run reading ${r4(underMount)}x is IN CONTROL on bulk and REFUSED on the mount — the mount's floor is higher too`,
    checkStandard(reading(underMount, 0.02), BULK).ok === true && checkStandard(reading(underMount, 0.02), MOUNT).ok === false,
    `mount floor ${mount.location.limits[0]}, bulk floor ${bulk.location.limits[0]}`
  );
  // AND THE FENCE, AS A COUNT ON THE REAL CORPUS. The widest of the 14 mount
  // row-runs reads above bulk's ceiling: had bulk's limits been imported here,
  // that run would have been refused by a limit derived from a different row
  // class on a different page. That is the mis-specification rf2-8a746 retired,
  // and it is why this class was calibrated rather than borrowed.
  check(
    'the corpus proves the fence: the widest observed mount run sits ABOVE bulk\'s ceiling and inside the mount\'s',
    mount.provenance.observed.runMedianRange[1] > bulk.location.limits[1] &&
      mount.provenance.observed.runMedianRange[1] < mount.location.limits[1],
    `widest mount run ${mount.provenance.observed.runMedianRange[1]}x, bulk ceiling ${bulk.location.limits[1]}, mount ceiling ${mount.location.limits[1]}`
  );
  // The sabotage, aimed at the mount's own arm: the standard has to be seen
  // refusing on EVERY class it certifies, not on the first one written.
  const mountSabotaged = checkStandard(blocksAtIn(mount, 140 / 300, 18, 0.05), MOUNT);
  check(
    'THE SABOTAGE ON THE MOUNT: an arm rendering 140 of the 300 boundaries it declares doubled REFUSES there too',
    !mountSabotaged.ok && !mountSabotaged.location.ok && /outside the frozen location limits/.test(mountSabotaged.why || ''),
    `${mountSabotaged.location && mountSabotaged.location.measured}x — ${mountSabotaged.why}`
  );

  // 6. FAIL CLOSED, at each seat.
  //
  // THE UNCALIBRATED SEAT HAS NO PRODUCTION INSTANCE any more — v2 calibrated
  // the last class that had none — so the fixture makes one and puts it back,
  // rather than leaving a fake row class in shipped data to keep a test alive.
  // The seat is not dead code: it is what refuses the next class somebody adds
  // to `classes` before its limits exist, and a branch nobody has seen refuse
  // is a branch of unmeasured sensitivity.
  let uncal;
  try {
    mount.calibrated = false;
    uncal = checkStandard(blocksAtIn(mount, 2), MOUNT);
  } finally {
    mount.calibrated = true;
  }
  check(
    'an UNCALIBRATED class refuses rather than abstains, and names the class it could not certify',
    !uncal.ok && uncal.calibrated === false && uncal.location === null && /NOT CALIBRATED/.test(uncal.why || ''),
    uncal.why
  );
  check(
    'and the flip was restored — a fixture that mutated the standard and left it mutated is a calibration change',
    STANDARD.classes.mount.calibrated === true && checkStandard(blocksAtIn(mount, 2, 18, 0.05), MOUNT).ok === true
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

  // 7. THE STANDARD IS DATA AND SAYS WHICH DATA IT IS. A verdict that did not
  //    carry its version could not be re-read after a recalibration — and v2
  //    is one, so this is now load-bearing rather than anticipatory.
  check(
    'every verdict carries the standard it was taken against, by id and version',
    healthy.standard.id === STANDARD.id &&
      healthy.standard.version === STANDARD.version &&
      healthy.standard.ruling === 'rf2-8a746' &&
      Number.isInteger(STANDARD.version)
  );
  check(
    'the frozen limits are the JSON\'s, never a literal here — for EVERY calibrated class',
    healthy.location.limits[0] === bulk.location.limits[0] &&
      healthy.location.limits[1] === bulk.location.limits[1] &&
      healthy.dispersion.limit === bulk.dispersion.limit &&
      healthy.location.centre === bulk.centre &&
      mountHealthy.location.limits[0] === mount.location.limits[0] &&
      mountHealthy.location.limits[1] === mount.location.limits[1] &&
      mountHealthy.dispersion.limit === mount.dispersion.limit &&
      mountHealthy.location.centre === mount.centre
  );
  check(
    'and the limits bracket the centre rather than sitting to one side of it, in every class',
    Object.values(STANDARD.classes)
      .filter((k) => k.calibrated)
      .every((k) => k.location.limits[0] < k.centre && k.centre < k.location.limits[1])
  );
  check(
    'every calibrated class states its provenance and its error rates — a limit with no baseline is an assertion',
    Object.entries(STANDARD.classes)
      .filter(([, k]) => k.calibrated)
      .every(([, k]) => k.provenance && k.provenance.rowRuns > 0 && k.provenance.independence && k.errorRates)
  );

  // 8. THE INDEPENDENCE CLAIM IS A MEASUREMENT (rf2-c1974), and the fixtures
  //    that hold it to that are here rather than only in the corpus test,
  //    because the corpus is retained and not required to build. What is
  //    checked here is the RECORD — its shape, its arithmetic against itself,
  //    and that it claims no more than a session-level hold-out. The numbers
  //    themselves are re-derived from the committed datasets by
  //    `clock_exit_path.test.cjs`, which is where the data lives.
  const calibrated = Object.entries(STANDARD.classes).filter(([, k]) => k.calibrated);
  // A MISSING RECORD MUST FAIL THE CHECK, NOT THROW PAST IT. Every reader below
  // goes through `HO`, so a class with no hold-out — the pre-v3 shape — reports
  // as a red fixture with its name and its detail, which is what the mutation
  // proof reads. A fixture that crashes on the mutation it exists to catch
  // tells you only that something broke.
  const HO = (k) => (k.provenance && k.provenance.holdOut) || {};
  const DIRS = (k) => (Array.isArray(HO(k).directions) ? HO(k).directions : []);
  check(
    'every calibrated class carries an out-of-sample hold-out, taken BOTH WAYS',
    calibrated.every(([, k]) => {
      const dirs = DIRS(k);
      if (dirs.length !== 2) return false;
      const bases = dirs.map((d) => d.baseline).sort();
      const held = dirs.map((d) => d.heldOut).sort();
      // each ensemble serves once as baseline and once as the held-out set —
      // a "both ways" that reused one baseline would be one direction twice
      return bases[0] !== bases[1] && JSON.stringify(bases) === JSON.stringify(held);
    }),
    calibrated.map(([n, k]) => `${n}:${DIRS(k).length}`).join(' ')
  );
  check(
    'the two baselines PARTITION the class\'s corpus — a hold-out that judged a run it was fitted on is not one',
    calibrated.every(([, k]) => {
      const dirs = DIRS(k);
      if (!dirs.length) return false;
      const sum = dirs.reduce((a, d) => a + d.baselineRowRuns, 0);
      return sum === k.provenance.rowRuns && dirs.every((d) => d.baselineRowRuns + d.heldOutRowRuns === k.provenance.rowRuns);
    }),
    calibrated.map(([n, k]) => `${n}: ${DIRS(k).map((d) => d.baselineRowRuns).join('+') || 'none'} of ${k.provenance.rowRuns}`).join('; ')
  );
  check(
    'the claim is SESSION-LEVEL and says why it is not commit-level — the weaker claim, made in the file rather than in a PR body',
    calibrated.every(
      ([, k]) =>
        /SESSION[- ]LEVEL/i.test(HO(k).level || '') &&
        /does not split by commit/.test(HO(k).whyNotCommitLevel || '') &&
        /SESSION LEVEL/i.test(k.provenance.independence || '')
    ),
    calibrated.map(([n, k]) => `${n}: ${(HO(k).level || 'NO HOLD-OUT').slice(0, 40)}`).join(' | ')
  );
  check(
    'and the residual is the STANDARD\'s to state: commit-level independence is named as what the next ensemble is for',
    STANDARD.recalibrateOn.some((r) => /COMMIT-LEVEL INDEPENDENCE HAS NEVER BEEN MEASURED/.test(r)),
    STANDARD.recalibrateOn.length + ' recalibration triggers'
  );
  check(
    '`agree` is the arithmetic and not an opinion — true exactly when every direction admitted every held-out run',
    calibrated.every(([, k]) => {
      const dirs = DIRS(k);
      if (!dirs.length) return false;
      const allIn = dirs.every((d) => d.inControl === d.heldOutRowRuns);
      const totIn = dirs.reduce((a, d) => a + d.inControl, 0);
      const totOf = dirs.reduce((a, d) => a + d.heldOutRowRuns, 0);
      return HO(k).agree === allIn && HO(k).heldOutInControl === `${totIn} of ${totOf}`;
    }),
    calibrated.map(([n, k]) => `${n}: agree=${HO(k).agree} ${HO(k).heldOutInControl}`).join('; ')
  );
  check(
    'a DISAGREEING class states the disagreement and names every refused run and its term — not averaged into a summary',
    calibrated
      .filter(([, k]) => HO(k).agree === false)
      .every(([, k]) => {
        const refused = DIRS(k).flatMap((d) => d.refused || []);
        return (
          typeof HO(k).disagreement === 'string' &&
          HO(k).disagreement.length > 0 &&
          refused.length > 0 &&
          refused.every((r) => r.run && Number.isFinite(r.median) && r.term) &&
          DIRS(k).every((d) => (d.refused || []).length === d.heldOutRowRuns - d.inControl)
        );
      }),
    calibrated.filter(([, k]) => HO(k).agree === false).map(([n]) => n).join(',') || 'no class disagreed'
  );
  check(
    'THE FENCE: no hold-out fit became a shipped limit — the frozen limits are still the POOLED ones',
    calibrated.every(([, k]) =>
      DIRS(k).every(
        (d) =>
          d.centre !== k.centre &&
          d.limits[0] !== k.location.limits[0] &&
          d.limits[1] !== k.location.limits[1] &&
          d.dispersionLimit !== k.dispersion.limit
      )
    ),
    'a shipped limit equal to a single-ensemble fit would mean the standard had been refitted to make a hold-out pass'
  );
  check(
    'and the pooled limits are not simply the widest available — pooling carried the between-session term, it did not widen',
    calibrated.every(([, k]) => {
      const widths = DIRS(k).map((d) => d.limits[1] - d.limits[0]);
      if (!widths.length) return false;
      const width = k.location.limits[1] - k.location.limits[0];
      return width < Math.max(...widths) && width > Math.min(...widths);
    }),
    calibrated
      .map(([n, k]) => `${n}: pooled ${r4(k.location.limits[1] - k.location.limits[0])} against [${DIRS(k).map((d) => r4(d.limits[1] - d.limits[0])).join(', ') || 'none'}]`)
      .join('; ')
  );
  check(
    'every hold-out direction\'s limits bracket its own derived centre, exactly as the shipped ones do',
    calibrated.every(([, k]) => DIRS(k).length > 0 && DIRS(k).every((d) => d.limits[0] < d.centre && d.centre < d.limits[1] && d.betweenRunSD > 0 && d.dispersionLimit > 0))
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
