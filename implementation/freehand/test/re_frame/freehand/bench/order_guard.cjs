'use strict';
// The ARM-ORDER guard — no reported figure may depend on WHERE IN THE PLAN
// it was measured.
//
//   node .../order_guard.cjs                     the deterministic self-test
//   node --expose-gc --min-semi-space-size=32 \
//        --max-semi-space-size=64 .../order_guard.cjs --live
//
// ## The fault this exists for
//
// `rf2-jr76s` measured one control two ways in a single process. A
// `.slice()` of a packed SMI array — predicted 8.000 B/slot, because slot
// width is a build fact on an uncompressed V8 — read:
//
//     FORWARD arm order    16.1052 B/slot   (2.01x its prediction)
//     REVERSED arm order    8.0027 B/slot   (+0.03%)
//     plain node, no harness ~8.5  B/slot
//
// It was caught only because the whole plan was run in both orders and the
// two controls disagreed with EACH OTHER rather than with a prediction.
// `rf2-88pie` filed it as *a large-object arm doubles the reading of its
// immediate successor*.
//
// ## What a live reproduction says, and why it changes the remedy
//
// [[liveReproduction]] rebuilds that control in plain node and separates
// the factors a reversal moves together. Measured on node 24.13.0 /
// V8 13.6, pointer compression OFF (`--expose-gc --min-semi-space-size=32
// --max-semi-space-size=64`):
//
//   POSITION IN THE RUN, nothing else varying — the same control, sixteen
//   consecutive windows:
//       42.32 | 10.26 10.26 10.26 10.33 10.28 | 8.12 8.12 8.12 ... 8.12
//   The first window reads 5.3x the prediction, the next five read +27%,
//   and from the seventh it sits at 8.122 for ever. Nothing changed but
//   how many times the site had run.
//
//   THE IMMEDIATE PREDECESSOR, position held fixed — a fresh process per
//   reading, the control measured first in both:
//       after an 87 KB-object arm   10.2900
//       after a 3-element arm       10.2588      0.3% apart
//
//   THE WINDOW SIZE — a knob with nothing to do with order. Measured COLD,
//   in a fresh process, 32 slices per window read 16.50 B/slot against 8
//   slices' 8.12: **2.03x**, the magnitude `rf2-jr76s` recorded. Measured
//   WARM it is 8.2515 against 8.1377, +1.4%. So that 2x is warm-up too.
//
// So the immediate predecessor is worth a third of a percent, and
// everything else on this control is one effect wearing three hats: a
// site that has not run enough times yet reads 1.26x to 5.3x its settled
// value, and both a plan reversal and a change of window size move where
// in that curve each arm is measured. **Reversing a plan moves position
// and adjacency together**, so forward-versus-reversed cannot say which of
// them it caught — and a plan re-ordered to keep large arms away from
// small ones fixes the factor that measured smallest.
//
// This does not restate what happened inside `rf2-jr76s`'s own harness;
// that harness is not reproduced here and its mechanism stays undiagnosed.
// It does say that the REMEDY the bead proposed — order the plan, or run
// it in both orders — is not sufficient on its own.
//
// ## The property, and why this one
//
// Three remedies were available.
//
// **Randomise the order.** Rejected. Randomising SPREADS a contaminant
// across every arm instead of concentrating it in one, converting a
// visible 2x on one arm into an invisible smear on all of them — wider
// ranges, no verdict, and a figure wrong by an amount nobody can name.
//
// **Order the plan so no arm follows a much larger one.** Rejected. It
// needs each arm's per-call allocation BEFORE measuring it, which is the
// thing being measured; it is unfalsifiable, since a plan ordered by that
// rule produces no evidence the rule worked; and the live reproduction
// says adjacency is the smallest of the three effects anyway.
//
// **Stratify and REFUSE.** Chosen, and widened past the bead's proposal:
//
//   1. [[schedule]] builds a run order in which every arm has at least two
//      DISTINCT immediate predecessors across rounds. Cyclic rotation —
//      which is what every interleaved harness in this repository was
//      doing — does not have that property; see below.
//   2. Every sample carries what ran immediately before it AND its
//      position in the run.
//   3. [[verdict]] partitions each arm's samples by EACH of those factors
//      and applies the house rule: OVERLAPPING RANGES MEAN
//      INDISTINGUISHABLE. A stratification is a finding only if the ranges
//      are disjoint AND the medians differ by more than a stated
//      tolerance.
//   4. An arm with one stratum on a factor is `unchecked`, and `unchecked`
//      refuses as loudly as `contaminated`: a single-order result has not
//      been checked and may not be reported.
//
// ## Cyclic rotation does not vary adjacency, and that is the trap
//
// `b8_run.cjs` and `b6_rows.cljs` both chose the arm for slot `j` of round
// `r` as `ARMS[(j + r) % n]`, and both published that as "arm order
// rotating with the round". A cyclic rotation changes which arm goes
// FIRST; it does not change which arm follows which. Arm `a` sits at slot
// `(a - r) mod n`, so its predecessor is `ARMS[(a - 1) mod n]` in every
// round. The only sample with a different predecessor is the one at the
// round seam — exactly one of the arm's `R`.
//
// [[schedule]] reflects the rotation on odd rounds, which replaces every
// `a -> a+1` adjacency with `a -> a-1`. `selfTest` proves both halves of
// that arithmetically.
//
// ## Two arms are the case where the reflection cancels (rf2-ouwh8)
//
// Reversing a pair IS rotating it by one. So at `n === 2` — and only there —
// composing the rotation with the reflection returns `[0, 1]` at every index,
// and a two-arm plan runs in a SINGLE ORDER for ever. That is not a weaker
// version of the property; it is the absence of it, and the guard says so:
// four two-arm rows came back `only 1 stratum — the question was never
// asked`. Two arms is the natural shape for *candidate versus comparator*, so
// the case is not exotic. [[schedule]] drops the reflection at `n === 2`,
// where the bare rotation already supplies both of the orders that exist.
//
// ## A LOST position is a refusal, not a smaller question
//
// Every instrument fault on this programme produced a plausible PRECISE
// WRONG NUMBER before it was caught, and one of them was aimed straight at
// this module: a shadowed binding in a harness made every `position` `NaN`
// from round 2 on. `stratifyArm` filters non-finite positions out of the
// phase contrast, so the phase question was quietly answered over the six
// samples that still had one while `format` printed the arm's full
// twenty-four beside it — and the run returned `[ok]`.
//
// A harness that records NO positions is a different and documented case:
// it is excused the phase contrast (and still owes the predecessor one). A
// harness that records positions and LOSES them is broken, and
// [[lostPositions]] separates the two so the second refuses. `selfTest`
// check 10 replays it with deliberately flat values, so nothing but the
// lost positions can be what refuses.
//
// The excuse belongs to the RUN, not to the sample, and reading it the other
// way is how the same fault came back: a first repair counted only `NaN`, so
// a sample carrying `position: null` — present, unusable, dropped by
// `stratifyArm` exactly as `NaN` is — was excused one at a time, and
// twenty-four samples with six positions and eighteen nulls adjudicated
// phase over four survivors and answered `[ok]` again. Inside a run that
// records positions at all, EVERY unusable position is a loss; only a run in
// which no sample offers one is excused. Check 11 prices both halves.
//
// ## What the `predecessor` factor can and cannot attribute (rf2-om73r)
//
// Under [[schedule]] an arm's predecessor is `(a - 1) mod n` on EVEN rounds
// and `(a + 1) mod n` on ODD ones, so with only two orders available the
// predecessor is a function of the round's PARITY. The two strata are
// therefore "even rounds" and "odd rounds" wearing predecessor labels, and
// a `predecessor` finding says *this figure moves with the plan* — which is
// the whole property and reason enough to refuse — but it does NOT
// establish that the immediate predecessor is the mechanism. Anything else
// that alternates with the round direction lands in the same stratum.
//
// That is not a defect to paper over with a randomised order (see above for
// why randomising is worse), and separating the two would need a third
// distinct order per arm. It is stated here so that no report quotes a
// `predecessor` stratum as evidence about adjacency specifically.

// ---------------------------------------------------------------------------
// The schedule
// ---------------------------------------------------------------------------

/**
 * Slot order for `n` arms in `round`: rotate forward, then REFLECT on odd
 * rounds — EXCEPT at `n === 2`, where the two operations are the SAME
 * permutation and composing them cancels.
 *
 * Reversing a pair is rotating it by one, so a schedule that always composes
 * returns `[0, 1]` at every index and a two-arm plan runs in ONE ORDER FOR
 * EVER — the single-order result this module exists to refuse (`rf2-ouwh8`;
 * the guard found it, four two-arm rows deep, as `only 1 stratum — the
 * question was never asked`). At `n === 2` the bare rotation already
 * alternates `[0, 1]` and `[1, 0]`, which is every order two arms have, so
 * the reflection is dropped rather than composed. `selfTest` checks 9 and 10
 * price both halves.
 */
function schedule(n, round) {
  const xs = [];
  for (let j = 0; j < n; j++) xs.push((j + round) % n);
  return round % 2 === 1 && n > 2 ? xs.reverse() : xs;
}

/** The plain cyclic rotation, kept so `selfTest` can price it. */
function rotationOnly(n, round) {
  const xs = [];
  for (let j = 0; j < n; j++) xs.push((j + round) % n);
  return xs;
}

/**
 * Flatten `rounds` rounds of `sched(n, round)` into the sequence that
 * actually runs, and answer, per arm, the predecessor strata it produces.
 * The round seam counts: the rounds run back to back in one process, so
 * the last arm of round `r` really does precede the first of round `r+1`.
 */
function adjacency(n, rounds, sched, opts = {}) {
  const seams = opts.seams !== false;
  const seq = [];
  const rounded = [];
  for (let r = 0; r < rounds; r++) {
    const slots = sched(n, r);
    for (let k = 0; k < slots.length; k++) {
      seq.push(slots[k]);
      rounded.push(k === 0);
    }
  }
  const preds = new Map();
  for (let i = 0; i < seq.length; i++) {
    const a = seq[i];
    const roundFirst = rounded[i];
    if (!seams && roundFirst) continue; // the seam is a different question
    if (!preds.has(a)) preds.set(a, new Map());
    const m = preds.get(a);
    const p = i === 0 || (!seams && roundFirst) ? null : seq[i - 1];
    m.set(p, (m.get(p) || 0) + 1);
  }
  const arms = {};
  for (const [a, m] of preds) {
    const counts = [...m.values()];
    const total = counts.reduce((x, y) => x + y, 0);
    arms[a] = {
      distinct: m.size,
      samples: total,
      modalShare: Math.max(...counts) / total,
    };
  }
  return { sequence: seq, arms };
}

// ---------------------------------------------------------------------------
// The verdict
// ---------------------------------------------------------------------------

function median(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function summarise(predecessor, values) {
  return {
    stratum: predecessor,
    n: values.length,
    min: Math.min(...values),
    max: Math.max(...values),
    p50: median(values),
  };
}

/**
 * The nuisance factors a plan varies whether it means to or not.
 *
 * `predecessor` is the bead's factor. `phase` is the one the live
 * reproduction says is larger: split an arm's own samples at the median of
 * their positions in the run, so EARLY and LATE are the same arm measured
 * at two points in the process's history. A warm-up effect, a pretenuring
 * transition or a growing heap all land here, and all of them move when a
 * plan is reversed.
 */
const FACTORS = {
  predecessor: (s) => (s.predecessor === null || s.predecessor === undefined
    ? '<none>'
    : String(s.predecessor)),
  phase: null, // computed per arm, below
};

function stratifyArm(samples, factor) {
  const m = new Map();
  if (factor === 'phase') {
    // THIRDS, not halves, and the middle is discarded. A warm-up step
    // lands somewhere inside the run; a median split can put the step in
    // the middle of the EARLY stratum, which then straddles it, and a
    // straddling stratum's range overlaps everything. First third against
    // last third is the beginning-versus-end contrast without that trap.
    const withPos = samples
      .filter((s) => Number.isFinite(s.position))
      .sort((a, b) => a.position - b.position);
    if (withPos.length < 2) return [];
    const t = Math.max(1, Math.floor(withPos.length / 3));
    m.set('FIRST-THIRD', withPos.slice(0, t).map((s) => s.value));
    m.set('LAST-THIRD', withPos.slice(withPos.length - t).map((s) => s.value));
  } else {
    const key = FACTORS[factor];
    for (const s of samples) {
      const k = key(s);
      if (!m.has(k)) m.set(k, []);
      m.get(k).push(s.value);
    }
  }
  return [...m.entries()]
    .map(([k, vs]) => summarise(k, vs))
    .sort((a, b) => a.p50 - b.p50);
}

/**
 * How many of `samples` have NO USABLE `position` — null, absent, or a
 * non-finite number — in a run that records positions AT ALL.
 *
 * Not the same question as "how many have no position". A harness that
 * offers no position on any sample is a documented case and is excused the
 * phase contrast; a harness that records positions and loses some of them
 * has LOST them, and the difference between the two is the difference
 * between a question not asked and a question answered over a silently
 * smaller set.
 *
 * So the excuse is a property of the RUN, not of the sample: only a run in
 * which NO sample offers a position is excused, and inside a run that does
 * record them every unusable position is a loss. `NaN` was the recorded
 * fault and null is its sibling — `stratifyArm` drops both from the phase
 * contrast while `format` prints the arm's full count beside it, so a guard
 * that counted only `NaN` would adjudicate 24 samples on four and answer
 * `[ok]` for the second reason having been repaired for the first.
 */
function lostPositions(samples) {
  if (!samples.some((s) => s.position !== undefined && s.position !== null)) return 0;
  let n = 0;
  for (const s of samples) {
    if (!Number.isFinite(s.position)) n += 1;
  }
  return n;
}

/**
 * Adjudicate one arm's strata on one factor.
 *
 * Strata of a single sample carry no range, so they are adjudicated on the
 * ratio alone — and only when there is no better-powered pair available,
 * because a powered comparison should decide the question when one exists.
 * `rf2-jr76s`'s recorded fault is exactly the unpowered case (one forward
 * reading, one reversed) and must still fire.
 */
function adjudicate(strata, tolerance) {
  if (strata.length < 2) {
    return {
      strata,
      status: 'unchecked',
      why: `only ${strata.length} stratum — the question was never asked`,
    };
  }
  const powered = strata.filter((s) => s.n >= 2);
  const use = powered.length >= 2 ? powered : strata;
  const lo = use[0];
  const hi = use[use.length - 1];
  // An arm that allocates NOTHING reads zero in every stratum, and `0/0` is
  // not an infinite separation — it is no separation at all. Left as
  // `Infinity` this manufactures contamination out of two strata that agree
  // exactly, and on two n=1 strata (which are adjudicated on the ratio
  // alone) it FIRES — the one thing the house rule exists to prevent.
  // `read-attribution`'s CACHEGET and NOOP arms are identically zero in
  // every round and were the reproduction (rf2-om73r).
  const ratio = hi.p50 === lo.p50 ? 1 : lo.p50 === 0 ? Infinity : hi.p50 / lo.p50;
  const disjoint = hi.min > lo.max;
  const degenerate = lo.n < 2 || hi.n < 2;
  const beyond = Math.abs(ratio - 1) > tolerance;
  const hit = beyond && (disjoint || degenerate);
  return {
    strata,
    worst: { low: lo.stratum, high: hi.stratum, ratio, disjoint, degenerate },
    underpowered: degenerate,
    status: hit ? 'contaminated' : 'clean',
    why: hit
      ? `${hi.stratum} reads ${ratio.toFixed(4)}x ${lo.stratum}` +
        (degenerate ? ' (n=1 strata: a ratio, not a range)' : ', ranges disjoint')
      : beyond
        ? `medians ${ratio.toFixed(4)}x apart but the ranges OVERLAP — indistinguishable`
        : `within ${(tolerance * 100).toFixed(0)}%`,
  };
}

/**
 * Does any arm's figure depend on where in the plan it was measured?
 *
 * `samples` — `[{arm, value, predecessor, position}]`. `tolerance` is a
 * relative difference of medians and the caller must choose it knowing its
 * own noise; the default 0.10 sits far below the 2.01x the recorded fault
 * produced and far above the 0.4% the same study's uncontaminated arms
 * reproduced to.
 *
 * Answers `{ tolerance, arms, contaminated, unchecked, refuse }`. `refuse`
 * is the one a driver acts on: a single-order result and a contaminated
 * one are both unreportable.
 */
function verdict(samples, opts = {}) {
  const tolerance = opts.tolerance === undefined ? 0.1 : opts.tolerance;
  const factors = opts.factors || ['predecessor', 'phase'];
  const byArm = new Map();
  for (const s of samples) {
    if (!Number.isFinite(s.value)) continue;
    if (!byArm.has(s.arm)) byArm.set(s.arm, []);
    byArm.get(s.arm).push(s);
  }
  const arms = {};
  let contaminated = false;
  let unchecked = false;
  for (const [arm, ss] of byArm) {
    const per = {};
    for (const f of factors) {
      const strata = stratifyArm(ss, f);
      const lost = f === 'phase' ? lostPositions(ss) : 0;
      if (lost > 0) {
        // A sample with no usable position in a run that records positions
        // is a HARNESS FAULT, and it is the recorded one: a shadowed
        // binding made every position `NaN` from round 2 on, `stratifyArm`
        // dropped them, and the report said "24 samples" over a phase
        // contrast adjudicated on six — and answered `[ok]`. null is the
        // same loss by another route and is counted the same way. Silently
        // narrowing the question is the one thing this module must not do.
        per[f] = {
          strata,
          status: 'unchecked',
          lostPositions: lost,
          why:
            `${lost} of ${ss.length} samples have NO USABLE position (null, absent or ` +
            'non-finite) in a run that records them — the harness lost them, so this ' +
            'contrast is over the rest and the sample count above is not it',
        };
        unchecked = true;
        continue;
      }
      // `phase` is only askable when positions were recorded at all; a
      // driver that does not record them is not thereby excused the
      // predecessor question, so an empty phase stratification is skipped
      // rather than failed.
      if (f === 'phase' && strata.length === 0) continue;
      per[f] = adjudicate(strata, tolerance);
      if (per[f].status === 'contaminated') contaminated = true;
      if (per[f].status === 'unchecked') unchecked = true;
    }
    arms[arm] = { n: ss.length, factors: per };
  }
  return { tolerance, arms, contaminated, unchecked, refuse: contaminated || unchecked };
}

const num = (v) =>
  !Number.isFinite(v) ? String(v) : Math.abs(v) >= 1000 ? Math.round(v).toLocaleString('en-US') : v.toFixed(4);

function format(v, title) {
  const out = [];
  out.push(`;; ==== ARM-ORDER GUARD${title ? ' — ' + title : ''} ====`);
  out.push(
    ';;   every arm partitioned by WHAT RAN BEFORE IT and by WHERE IN THE RUN it was ' +
      `measured; tolerance ${(v.tolerance * 100).toFixed(0)}%, overlapping ranges are ` +
      'indistinguishable'
  );
  for (const [arm, a] of Object.entries(v.arms)) {
    out.push(`;;   ${arm}  (${a.n} samples)`);
    for (const [f, r] of Object.entries(a.factors)) {
      const mark = r.status === 'clean' ? 'ok  ' : r.status === 'unchecked' ? 'UNCH' : 'FAIL';
      out.push(`;;     [${mark}] by ${f.padEnd(12)} ${r.why}`);
      for (const s of r.strata) {
        out.push(
          `;;              ${String(s.stratum).padEnd(24)} n=${String(s.n).padStart(2)}  ` +
            `p50 ${num(s.p50).padStart(12)}  [${num(s.min)}–${num(s.max)}]`
        );
      }
    }
  }
  out.push(
    v.refuse
      ? ';;   VERDICT: REFUSE — no figure above may be published as measured'
      : ';;   VERDICT: reportable — no arm reads differently for its position in the plan'
  );
  return out;
}

/** Throw unless every arm was checked on every factor and passed. */
function assertClean(samples, opts = {}) {
  const v = verdict(samples, opts);
  if (v.refuse) {
    throw new Error('arm-order guard REFUSED (rf2-88pie)\n' + format(v, opts.title).join('\n'));
  }
  return v;
}

// ---------------------------------------------------------------------------
// The self-test — deterministic, and it is the proof the guard catches the
// cases it was built for. Drivers run it before measuring.
// ---------------------------------------------------------------------------

function selfTest() {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: !!ok, detail });
  const one = (v, arm, factor) => v.arms[arm].factors[factor];

  // 1. THE RECORDED 2x, replayed from `rf2-jr76s`. A packed-SMI `.slice()`
  //    control, predicted 8.000 B/slot, read once in forward arm order and
  //    once in reversed. Both strata are n=1, which is the whole difficulty:
  //    a guard that insisted on ranges would have passed this.
  const smi = [
    { arm: 'CTL-SMI-slice', predecessor: 'DBL-10000', position: 9, value: 16.1052 },
    { arm: 'CTL-SMI-slice', predecessor: 'P-VALS', position: 2, value: 8.0027 },
  ];
  const vSmi = verdict(smi, { tolerance: 0.1 });
  check(
    'the recorded 2.01x is REFUSED',
    vSmi.refuse &&
      one(vSmi, 'CTL-SMI-slice', 'predecessor').status === 'contaminated' &&
      Math.abs(one(vSmi, 'CTL-SMI-slice', 'predecessor').worst.ratio - 2.0125) < 0.001,
    `ratio ${one(vSmi, 'CTL-SMI-slice', 'predecessor').worst.ratio.toFixed(4)}`
  );

  // 2. THE UNCONTAMINATED ARM FROM THE SAME STUDY must pass. The
  //    per-subscription slope reproduced 2,830.7 forward against 2,842.8
  //    reversed — 0.43% — and a guard that failed this would be useless.
  const slope = [
    { arm: 'RFWRITE-slope', predecessor: 'RFWRITE-150', position: 3, value: 2830.7 },
    { arm: 'RFWRITE-slope', predecessor: 'RFWRITE-300', position: 8, value: 2842.8 },
  ];
  check(
    "the same study's uncontaminated slope passes",
    !verdict(slope, { tolerance: 0.1 }).refuse,
    'forward 2830.7 / reversed 2842.8'
  );

  // 3. A SINGLE-ORDER RESULT IS REFUSED — the bead's first actionable: a
  //    study that ran one order has not checked this.
  const oneOrder = [
    { arm: 'A', predecessor: 'B', position: 0, value: 100 },
    { arm: 'A', predecessor: 'B', position: 1, value: 101 },
    { arm: 'A', predecessor: 'B', position: 2, value: 99 },
  ];
  const vOne = verdict(oneOrder, { tolerance: 0.1, factors: ['predecessor'] });
  check(
    'a single-order result is refused as UNCHECKED',
    vOne.refuse && vOne.unchecked && one(vOne, 'A', 'predecessor').status === 'unchecked',
    one(vOne, 'A', 'predecessor').why
  );

  // 4. THE HOUSE RULE. Medians 20% apart with overlapping ranges is
  //    indistinguishable, not a finding. A guard that fired here would
  //    manufacture contamination out of ordinary noise.
  const overlapping = [
    { arm: 'A', predecessor: 'X', position: 0, value: 100 },
    { arm: 'A', predecessor: 'X', position: 2, value: 140 },
    { arm: 'A', predecessor: 'X', position: 4, value: 120 },
    { arm: 'A', predecessor: 'Y', position: 1, value: 90 },
    { arm: 'A', predecessor: 'Y', position: 3, value: 135 },
    { arm: 'A', predecessor: 'Y', position: 5, value: 100 },
  ];
  const vOv = verdict(overlapping, { tolerance: 0.1 });
  check(
    'overlapping ranges are indistinguishable even 20% apart in median',
    !vOv.refuse,
    one(vOv, 'A', 'predecessor').why
  );

  // 5. THE WARM-UP STEP, from `liveReproduction`'s own measured sweep: the
  //    SAME control, nothing else running, sixteen consecutive windows.
  //    The predecessor is identical throughout, so ONLY the phase factor
  //    can catch it — and a guard built to the bead's literal proposal
  //    (reverse the plan, compare) would not have.
  const warmup = [
    10.3223, 10.2627, 10.2588, 10.2588, 10.334, 10.2832,
    8.1221, 8.1221, 8.1221, 8.1221, 8.1221, 8.1221,
  ].map((value, i) => ({ arm: 'CTL-SMI-slice', predecessor: 'CTL-SMI-slice', position: i, value }));
  const vWarm = verdict(warmup, { tolerance: 0.1 });
  check(
    'the measured warm-up step is caught by PHASE, which no reversal would show',
    vWarm.refuse &&
      one(vWarm, 'CTL-SMI-slice', 'phase').status === 'contaminated' &&
      one(vWarm, 'CTL-SMI-slice', 'predecessor').status === 'unchecked',
    `phase: ${one(vWarm, 'CTL-SMI-slice', 'phase').why}`
  );

  // 6. AN ARM THAT ALLOCATES NOTHING IS NOT INFINITELY CONTAMINATED.
  //    `read-attribution`'s CACHEGET reads exactly 0 B in every round, so
  //    both strata are zero; the pair is n=1 apiece here, which is the
  //    case adjudicated on the ratio alone and therefore the case that
  //    fires. It must not.
  const zeros = [
    { arm: 'CACHEGET', predecessor: 'PORT', position: 0, value: 0 },
    { arm: 'CACHEGET', predecessor: 'DEREF', position: 1, value: 0 },
  ];
  const vZero = verdict(zeros, { tolerance: 0.1, factors: ['predecessor'] });
  check(
    'an arm that reads exactly zero in both strata is indistinguishable, not infinite',
    one(vZero, 'CACHEGET', 'predecessor').status === 'clean',
    one(vZero, 'CACHEGET', 'predecessor').why
  );

  // 7. CYCLIC ROTATION DOES NOT VARY ADJACENCY. Every arm keeps one
  //    predecessor in every round but the seam, so the order question is
  //    asked with a single sample in R. The reflecting schedule splits it.
  const R = 6;
  const N = 4;
  const rot = adjacency(N, R, rotationOnly, { seams: false });
  const ref = adjacency(N, R, schedule, { seams: false });
  const rotDistinct = Object.values(rot.arms).map((a) => a.distinct);
  const refDistinct = Object.values(ref.arms).map((a) => a.distinct);
  const refShare = Math.max(...Object.values(ref.arms).map((a) => a.modalShare));
  check(
    'cyclic rotation gives every arm exactly ONE within-round predecessor',
    rotDistinct.every((d) => d === 1),
    `rotation over ${R} rounds: distinct predecessors per arm ${rotDistinct.join(',')} ` +
      '(only the round seam ever differs, and that is one sample in R)'
  );
  check(
    'the reflecting schedule gives every arm TWO, in balance',
    refDistinct.every((d) => d >= 2) && refShare <= 0.75,
    `reflected: ${refDistinct.join(',')} predecessors per arm, largest modal share ` +
      `${(refShare * 100).toFixed(0)}%`
  );

  // 8. TWO ARMS, where the reflection CANCELS the rotation. Priced beside 7
  //    because it is the same arithmetic asked at the smallest `n` a
  //    comparison can have, and two arms is the natural shape for
  //    "candidate versus comparator". `alwaysReflect` is what [[schedule]]
  //    used to be, kept HERE and nowhere else so the defect stays
  //    reproducible rather than merely described (rf2-ouwh8).
  //
  //    A pair has exactly one adjacency per round, so the WITHIN-round
  //    question is unaskable at `n = 2` under any schedule; the seams are
  //    the run, and seams-on is the honest setting here.
  const alwaysReflect = (n, round) => {
    const xs = [];
    for (let j = 0; j < n; j++) xs.push((j + round) % n);
    return round % 2 === 1 ? xs.reverse() : xs;
  };
  const orders = (sched) => new Set(Array.from({ length: R }, (_, s) => sched(2, s).join(','))).size;
  const degOrders = orders(alwaysReflect);
  const fixOrders = orders(schedule);
  const deg2 = adjacency(2, R, alwaysReflect);
  const fix2 = adjacency(2, R, schedule);
  const degDistinct = Object.values(deg2.arms).map((a) => a.distinct);
  const fixDistinct = Object.values(fix2.arms).map((a) => a.distinct);
  const fixShare = Math.max(...Object.values(fix2.arms).map((a) => a.modalShare));
  check(
    'at k=2 an ALWAYS-reflecting schedule runs one order for ever and is UNCHECKED',
    degOrders === 1 && degDistinct.some((d) => d === 1),
    `always-reflect over ${R} rounds of 2 arms: ${degOrders} distinct order(s), ` +
      `predecessors per arm ${degDistinct.join(',')} — rotating a pair by one IS ` +
      'reversing it, so the two cancel'
  );
  check(
    'at k=2 schedule alternates, and every arm gets TWO predecessors in balance',
    fixOrders === 2 && fixDistinct.every((d) => d >= 2) && fixShare <= 0.75,
    `schedule: ${fixOrders} distinct orders, predecessors per arm ${fixDistinct.join(',')}, ` +
      `largest modal share ${(fixShare * 100).toFixed(0)}%`
  );

  // 10. THE LOST POSITIONS, replayed. A shadowed binding made every position
  //     `NaN` from round 2 on; `stratifyArm` dropped them, the report printed
  //     the arm's full sample count beside a phase contrast taken over the
  //     survivors, and the run came back `[ok]`. Values are deliberately flat,
  //     so nothing but the lost positions can be what refuses this.
  const lostSamples = Array.from({ length: 24 }, (_, i) => ({
    arm: 'A',
    predecessor: 'B',
    position: i < 6 ? i : NaN,
    value: 1.0,
  }));
  const vLost = verdict(lostSamples, { tolerance: 0.1 });
  const lostR = one(vLost, 'A', 'phase');
  check(
    'samples whose position went NON-FINITE refuse instead of narrowing the question',
    vLost.refuse && lostR.status === 'unchecked' && lostR.lostPositions === 18,
    lostR.why
  );

  // 11. THE SAME LOSS BY THE OTHER ROUTE. `null` is dropped from the phase
  //     contrast exactly as `NaN` is, so a guard that counts only `NaN`
  //     adjudicates these 24 samples on FOUR survivors and answers `[ok]` —
  //     the fault of check 10, repaired for one spelling and live for the
  //     other. Only `phase` is asked, so nothing but the lost positions can
  //     be what refuses; and the second half pins the excuse it must NOT
  //     swallow, a run in which no sample offers a position at all.
  const nullSamples = Array.from({ length: 24 }, (_, i) => ({
    arm: 'A',
    predecessor: 'B',
    position: i < 6 ? i : null,
    value: 1.0,
  }));
  const vNull = verdict(nullSamples, { tolerance: 0.1, factors: ['phase'] });
  const nullR = one(vNull, 'A', 'phase');
  const noneSamples = Array.from({ length: 24 }, () => ({ arm: 'A', predecessor: 'B', value: 1.0 }));
  const vNone = verdict(noneSamples, { tolerance: 0.1, factors: ['phase'] });
  check(
    'a present-but-NULL position is the same loss, and recording NONE is still excused',
    vNull.refuse &&
      nullR.status === 'unchecked' &&
      nullR.lostPositions === 18 &&
      !vNone.refuse &&
      vNone.arms.A.factors.phase === undefined,
    `${nullR.why} — and a run offering no position at all is excused, not refused`
  );

  return { ok: checks.every((c) => c.ok), checks };
}

// ---------------------------------------------------------------------------
// The live reproduction — best effort, never a gate
// ---------------------------------------------------------------------------
//
// The fixtures above prove the guard catches the RECORDED faults. This
// produces them again from scratch, in plain node, and prices the three
// factors a plan reversal moves together. It needs a nursery big enough
// that a window is not truncated by a scavenge — the same flags `b8_run`
// gives Chromium — and it says so rather than reporting a truncated
// window as data.

function liveReproduction(opts = {}) {
  const slots = opts.slots || 1024;
  const calls = opts.calls || 8;
  const windows = opts.windows || 16;
  if (typeof global.gc !== 'function') {
    return { ran: false, why: 'needs --expose-gc' };
  }
  const v8 = require('node:v8');
  const used = () => v8.getHeapStatistics().used_heap_size;

  // PACKED_SMI and PACKED_DOUBLE, built by push so neither is holey. A
  // holey array's `.slice()` does not take the packed fast path and the
  // prediction would be a fiction — `rf2-jr76s`'s own control ladder
  // records that mistake.
  const smi = [];
  for (let i = 0; i < slots; i++) smi.push(i);
  const dbl = [];
  for (let i = 0; i < 10000; i++) dbl.push(i + 0.5);
  const tiny = [1, 2, 3];
  let sink = 0;
  let keep = null;

  // One call site per arm. A single site serving arrays of three elements
  // kinds goes polymorphic, which is a harness artefact wearing a V8
  // finding's clothes.
  const runSmi = (n) => { for (let i = 0; i < n; i++) { const c = smi.slice(); sink += c[i % slots]; keep = c; } };
  const runDbl = (n) => { for (let i = 0; i < n; i++) { const c = dbl.slice(); sink += c[i % 10000]; keep = c; } };
  const runTiny = (n) => { for (let i = 0; i < n; i++) { const c = tiny.slice(); sink += c[i % 3]; keep = c; } };

  const measure = (n) => {
    global.gc();
    const a = used();
    runSmi(n);
    return (used() - a) / (n * slots);
  };

  // --- FACTOR 1: position in the run, nothing else varying ---------------
  const position = [];
  for (let k = 0; k < windows; k++) position.push(measure(calls));

  // --- FACTOR 2: the immediate predecessor, position held fixed ----------
  // Warm first, so both readings sit on the settled side of the step above
  // and the comparison is about adjacency and nothing else.
  const predecessor = [];
  for (let k = 0; k < 6; k++) {
    runDbl(20);
    predecessor.push({ arm: 'CTL-SMI-slice', predecessor: 'DBL-10000', position: 2 * k, value: measure(calls) });
    runTiny(20);
    predecessor.push({ arm: 'CTL-SMI-slice', predecessor: 'TINY-3', position: 2 * k + 1, value: measure(calls) });
  }

  // --- FACTOR 3: the window size ----------------------------------------
  const windowSize = {};
  for (const n of [calls, calls * 4]) windowSize[n] = measure(n);

  const pc = !!(
    process.config && process.config.variables && process.config.variables.v8_enable_pointer_compression
  );
  // PREDICTED: one tagged slot per element, plus a FixedArray header,
  // divided over `slots`. 8 bytes a slot on an uncompressed build, 4 on a
  // compressed one. Page-tail filler is NOT in the prediction and is why a
  // settled reading lands a little above it.
  const predicted = (pc ? 4 : 8) + 16 / slots;
  const settled = position[position.length - 1];
  const truncated = settled < predicted * 0.8;
  return {
    ran: true,
    sink,
    kept: keep && keep.length,
    runtime: `node ${process.version} / V8 ${process.versions.v8}, pointer compression ${pc ? 'ON' : 'OFF'}`,
    predictedBytesPerSlot: predicted,
    settledBytesPerSlot: settled,
    coldBytesPerSlot: position[0],
    truncated,
    why: truncated
      ? 'the settled control reads below its prediction — the window is being truncated by a ' +
        'scavenge; re-run with --min-semi-space-size=32 --max-semi-space-size=64'
      : null,
    position,
    predecessor,
    windowSize,
    predecessorVerdict: verdict(predecessor, { tolerance: 0.1, factors: ['predecessor'] }),
    positionVerdict: verdict(
      position.map((value, i) => ({ arm: 'CTL-SMI-slice', predecessor: 'CTL-SMI-slice', position: i, value })),
      { tolerance: 0.1 }
    ),
  };
}

module.exports = {
  schedule,
  rotationOnly,
  adjacency,
  median,
  verdict,
  format,
  assertClean,
  selfTest,
  liveReproduction,
};

if (require.main === module) {
  const st = selfTest();
  for (const c of st.checks) {
    console.log(`${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.detail ? '  — ' + c.detail : ''}`);
  }
  if (process.argv.includes('--live')) {
    const live = liveReproduction();
    console.log('\n;; ==== LIVE REPRODUCTION — plain node, no browser ====');
    if (!live.ran) {
      console.log(`;;   skipped: ${live.why}`);
    } else {
      console.log(`;;   runtime: ${live.runtime}`);
      console.log(
        `;;   POSITIVE CONTROL packed-SMI .slice(): predicted ` +
          `${live.predictedBytesPerSlot.toFixed(3)} B/slot, settled ` +
          `${live.settledBytesPerSlot.toFixed(4)}, cold ${live.coldBytesPerSlot.toFixed(4)}`
      );
      if (live.truncated) console.log(`;;   WARNING: ${live.why}`);
      console.log(`;;   FACTOR position:  ${live.position.map((x) => x.toFixed(2)).join(' ')}`);
      console.log(
        `;;   FACTOR window size: ${Object.entries(live.windowSize)
          .map(([n, v]) => `${n} slices -> ${v.toFixed(4)}`)
          .join(' | ')}`
      );
      for (const line of format(live.predecessorVerdict, 'the immediate predecessor, position held fixed')) {
        console.log(line);
      }
      for (const line of format(live.positionVerdict, 'position in the run, predecessor held fixed')) {
        console.log(line);
      }
    }
  }
  if (!st.ok) {
    console.error('\norder guard self-test FAILED');
    process.exit(1);
  }
  console.log('\norder guard self-test ok');
}
