#!/usr/bin/env node
'use strict';
// THE SEGMENT SEAM, ITS NULL, AND THE BAND A MAGNITUDE MUST CLEAR (rf2-cvvb7).
//
//   node .../seam.cjs            the deterministic self-test
//
// ## The finding this module exists to carry
//
// `the-candidates-clock.md` §6 refused three rows, and one of its three
// grounds was the cross-segment floor seam: the SAME floor arm — identical
// work, no substrate, untouched by which adapter is installed — read
// 3.147 / 2.437 / 2.349 ms across the three segments on one run's
// `bulk300`, a 34% spread, while a later run on a quieter box read 3.8% on
// the same row. The ground reasoned that floor-normalisation cancels a
// MULTIPLICATIVE seam and not an ADDITIVE one, so a bar row formed across
// segments that disagree by 34% is quoting the seam.
//
// A load ladder settled it. Nineteen runs of `bulk300` at a stated number
// of competing busy cores — 0, 2, 4, 8, 12 and 20 of this box's 24 —
// produced:
//
//   * THE SEAM DOES NOT TRACK LOAD. Pooled seam 0.1% – 16.4%, mean 4.8%,
//     with no trend: 4.3% at idle and 4.6% at twenty competing cores. Over
//     the same ladder the absolute floor rose 3.06 -> 5.50 ms, an 80% span,
//     so the ladder unquestionably moved the box. "How busy the machine
//     was" therefore does not explain the published 34%, and neither does
//     row position within the process (a five-row run read 4.9 / 2.4 / 1.1
//     / 9.4 / 3.3% across its five rows).
//   * IT IS NOT SEGMENT-ATTRIBUTABLE. [[seamNull]] relabels the segments
//     WITHIN each round — keeping every round's three blocks together and
//     destroying only which segment owned which — and recomputes the same
//     statistic. Pooled over the nineteen runs the null's median is 5.5%,
//     its q95 14.1% and its q99 17.6%, because a max-over-min of three
//     noisy block medians has a long right tail even when segment identity
//     means nothing. NOT ONE of the nineteen runs reached p < 0.2, and the
//     observed seams sit at or below that null's median. The published 22%
//     is a 1-in-400 draw from it; the published 34% is beyond all 38,000
//     permutations and beyond every rung of the ladder, and remains
//     unexplained by anything this study could reproduce.
//   * WHERE THE FLOOR'S VARIATION ACTUALLY LIVES is the round, not the
//     segment: by rung, ROUND 8–41% against SEGMENT 4.0–6.1% and
//     POSITION-in-round 3.2–9.9%. The three are orthogonal by construction
//     (see [[effects]]).
//   * THE PERTURBATION IS MULTIPLICATIVE, so floor-normalisation cancels it
//     and the arithmetic is exact: `(k·H)/(k·F) = H/F`. Across the ladder's
//     80% floor span, `ctl-2x / floor` — two arms in the SAME block whose
//     true ratio is a property of the page — moved 6.5% and moved the WRONG
//     WAY for additivity (correlation with the floor +0.41; an additive `c`
//     reads `(2W+c)/(W+c)`, which FALLS as `c` grows). The published bar
//     row did not move with the floor at all (correlation -0.10), nor with
//     the seam (-0.18).
//
// **So the seam is the wrong statistic to gate on**, and gating on it would
// refuse good runs on a tail draw while passing a genuinely additive seam
// half its size. What bounds a bar row is not how far apart two segments'
// floors are; it is how much of a block's perturbation FAILS to cancel when
// the block's own floor is divided out.
//
// ## The band, which is that quantity measured
//
// `ctl-2x` builds exactly twice the floor's page in the same block. Its
// ratio to the floor is therefore a property of the witness and not of the
// box, and every departure from a constant is the part of the ambient
// perturbation that survived normalisation. [[band]] is that departure,
// over the run's segment-blocks, in the units a bar row is quoted in.
//
// It is deliberately CONSERVATIVE. `ctl-2x` and `floor` differ in size
// (1,801 against 901 elements), so a perturbation that is multiplicative
// but size-dependent shows up in this ratio and would NOT show up between a
// substrate arm and its own same-size floor. The band therefore over-states
// the noise a real bar row carries, which is the right direction for a gate
// to err in and is stated rather than left to be discovered.
//
// ## What it gates
//
//   * A MAGNITUDE whose distance from 1.0 is inside the band is
//     INSTRUMENT-LIMITED and is not reportable as a magnitude. This
//     generalises `validation.md`'s standing rule — *a margin under 5% is
//     instrument-limited rather than cleared* — from an assumed 5% to the
//     figure the run itself measured. **This is the gate that bites**, and
//     it is calibrated: over the ladder the band averaged 8.9% while the
//     published bar row's own run-to-run spread was 0.9643 – 1.1041, about
//     ±7% around 1.016. The band predicts the noise a bar row actually
//     carries, slightly conservatively, which is the direction a gate
//     should err in.
//
//     THE CALIBRATION IS ON THE FRAME-ONLY CLOCK (rf2-ymi6j). Every figure
//     in this header comes from `rf2-cvvb7`'s ladder, which spawns
//     `clock_run.cjs` on `bulk300` — a `page.evaluate`-driven row — so it
//     was computed on `taskNet`, the frame with the arm's own script
//     subtracted out (rf2-yd52q). It cannot be recomputed: at the ladder's
//     driver blob `roundsTask` did not exist and only `taskNet` readings
//     were written, and no dataset survives.
//
//     THE BAND STATISTIC ITSELF LARGELY SURVIVES THAT, and the reason is
//     structural. It is `ctl-2x / floor` — two PURE-REACT arms, whose
//     script is small and roughly proportional to their frame, which is
//     exactly the class of arm both clocks rank alike. Measured on later
//     ensembles: the band reads 10.3–23.6% on `taskNet` against 8.8–19.7%
//     on raw `TaskDuration` over the same sixteen row-runs, and `ctl-2x`
//     reads 1.69–1.83x floor on BOTH clocks, agreeing to within 2%. It
//     moves slightly TIGHTER on the corrected clock, not wider, so the gate
//     does not become more permissive under the correction.
//
//     WHAT IS NOT ESTABLISHED is the multiplicativity argument above, which
//     is the one that reasons about how a perturbation ENTERS: a
//     perturbation multiplicative in frame time need not be multiplicative
//     in script time, and the +0.41 correlation that evidences it has no
//     counterpart on the corrected clock. Re-running the ladder at the
//     current blob settles it in one pass — the driver now writes
//     `roundsTask` and `inPageRounds`, and [[assess]] is clock-agnostic and
//     is already run over both.
//   * A RUN whose band exceeds [[BAND_CEILING]] has no reportable magnitude
//     at all, whatever its margin. The ladder produced bands of 4.4% –
//     18.5% across all nineteen runs, so the ceiling is **25%** — above
//     everything the calibration produced, including the rung with twenty
//     of twenty-four cores saturated. It therefore did NOT fire anywhere on
//     its own calibration, and that is stated rather than left for a reader
//     to discover: it is a tripwire for a box outside the whole range this
//     ladder could reach, not a gate on a busy one. A ceiling tight enough
//     to fire on the ladder would have refused an IDLE run (the widest band
//     of the nineteen, 18.5%, was taken at zero load), which is how a
//     threshold on a noisy statistic turns into a coin toss.
//
//     IT HAS SINCE FIRED THREE TIMES, which is worth a reader's attention
//     because it was calibrated never to. 26.2% on an earlier `bulk300`
//     ensemble (rf2-yd52q), then 26.2% on `bulk300` and 28.4% on `narrow`
//     in one run of rf2-emvod's. Either those boxes genuinely left the
//     calibrated regime — rf2-h8o80 is the bead for that, and its evidence
//     is a floor ABOVE the whole ladder — or the threshold sits on the
//     wrong clock. The ladder re-take distinguishes them. Note that the
//     gate in force is not frame-only whatever its threshold's provenance:
//     `clock_run.cjs` computes the band on both clocks and refuses if
//     EITHER breaches.

const SEGMENT_PERMUTATIONS = [
  [0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0],
];

/** A run whose band exceeds this has no reportable magnitude. See the header. */
const BAND_CEILING = 0.25;

/** Permutations drawn for the seam null. Fixed, because a null that moves between two reports of the same run is not a null. */
const NULL_DRAWS = 4000;

// A seeded xorshift, so the null is REPRODUCIBLE. A report whose p-value
// changes when it is recomputed on the same data cannot be checked by the
// reader, and this module's whole claim is that a number should be checkable.
function rng(seed) {
  let s = seed >>> 0 || 0x9e3779b9;
  return () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5; s >>>= 0;
    return s / 0x100000000;
  };
}

function median(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const spread = (xs) => Math.max(...xs) / Math.min(...xs) - 1;
function quantile(xs, p) {
  const v = [...xs].sort((a, b) => a - b);
  return v[Math.min(v.length - 1, Math.max(0, Math.floor(p * v.length)))];
}

/**
 * The published seam: the same floor arm's pooled median in each segment,
 * and the max-over-min of those.
 *
 * `blocks[round][segment]` is the array of that block's floor samples.
 */
function seam(blocks) {
  const nSeg = blocks[0].length;
  const bySeg = [];
  for (let j = 0; j < nSeg; j++) bySeg.push(median(blocks.flatMap((b) => b[j])));
  return { bySeg, spread: spread(bySeg) };
}

/**
 * The null distribution of THAT STATISTIC when segment identity is
 * irrelevant.
 *
 * The relabelling is WITHIN a round, so each round keeps its own three
 * blocks and only the question "which segment owned which" is destroyed.
 * That is the exact null the ground needs: not "is there any structure",
 * but "is the structure attributable to the segment".
 *
 * It is reported as quantiles rather than as a bare p-value because the
 * point of it is the SHAPE — this statistic's null median is around 5% and
 * its q99 around 18%, so a seam of 6% is the middle of the noise and
 * reporting it without the null invites a reader to treat it as a finding.
 */
function seamNull(blocks, opts = {}) {
  const draws = opts.draws || NULL_DRAWS;
  const next = rng(opts.seed || 0x5eed5eed);
  const nSeg = blocks[0].length;
  const observed = seam(blocks).spread;
  const nulls = [];
  for (let it = 0; it < draws; it++) {
    const perm = blocks.map(() => SEGMENT_PERMUTATIONS[Math.floor(next() * SEGMENT_PERMUTATIONS.length) % SEGMENT_PERMUTATIONS.length]);
    const bySeg = [];
    for (let j = 0; j < nSeg; j++) bySeg.push(median(blocks.flatMap((b, r) => b[perm[r][j]])));
    nulls.push(spread(bySeg));
  }
  const atLeast = nulls.filter((x) => x >= observed).length;
  return {
    observed,
    draws,
    q50: quantile(nulls, 0.5),
    q95: quantile(nulls, 0.95),
    q99: quantile(nulls, 0.99),
    p: atLeast / draws,
  };
}

/**
 * Where a floor's variation lives: with the SEGMENT, with the ROUND, or
 * with the POSITION the segment occupied in its round.
 *
 * `cells[round][segment]` is one number per block — the tared floor. The
 * three factors are mutually orthogonal by construction when the number of
 * rounds is a multiple of the number of segments, because the driver
 * rotates the segment order with the round: segment `j` sits at position
 * `(j - round) mod nSeg`, so each segment visits each position equally
 * often and each round contains every position exactly once.
 *
 * That balance is CHECKED rather than assumed. An unbalanced design makes
 * the three marginal spreads confounded with each other, and a confounded
 * decomposition reported as though it were orthogonal is how "it is the
 * adapter" gets said about an order effect.
 */
function effects(cells) {
  const R = cells.length;
  const nSeg = cells[0].length;
  const posOf = (j, r) => (((j - r) % nSeg) + nSeg) % nSeg;
  const balanced = R % nSeg === 0;

  const bySeg = [];
  for (let j = 0; j < nSeg; j++) bySeg.push(mean(cells.map((c) => c[j])));
  const byRound = cells.map((c) => mean(c));
  const byPos = [];
  for (let p = 0; p < nSeg; p++) {
    const vs = [];
    for (let r = 0; r < R; r++) for (let j = 0; j < nSeg; j++) if (posOf(j, r) === p) vs.push(cells[r][j]);
    byPos.push(mean(vs));
  }
  return {
    balanced,
    bySeg, byRound, byPos,
    segment: spread(bySeg),
    round: spread(byRound),
    position: spread(byPos),
  };
}

/**
 * THE BAND — how much of a block's perturbation survives dividing by that
 * block's own floor.
 *
 * `pairs` is one `{fixed, floor}` per block: two arms measured in the same
 * block whose true ratio is a property of the page. The band is the
 * half-width of the p10–p90 interval of `fixed / floor`, relative to its
 * median — a range and not a standard deviation, because this lane quotes
 * ranges, and an interior quantile and not a max/min, because a max/min
 * over eighteen blocks is the very tail-heavy statistic this module was
 * written to stop trusting.
 */
function band(pairs) {
  const ratios = pairs.map((p) => p.fixed / p.floor).filter(Number.isFinite);
  if (ratios.length < 4) return { n: ratios.length, band: NaN, why: 'fewer than four blocks' };
  const p50 = median(ratios);
  const lo = quantile(ratios, 0.1);
  const hi = quantile(ratios, 0.9);
  return {
    n: ratios.length,
    p50,
    p10: lo,
    p90: hi,
    min: Math.min(...ratios),
    max: Math.max(...ratios),
    band: (hi - lo) / (2 * p50),
  };
}

/**
 * Is a measured magnitude far enough from 1.0 to be one?
 *
 * `bars` is `{name: mean}`. A row inside the band is INSTRUMENT-LIMITED:
 * the instrument cannot tell it from parity, which is a result about the
 * instrument and not about the candidate, and publishing it as a magnitude
 * would be quoting noise.
 */
function adjudicate(bars, bandWidth, unavailableWhy) {
  // A row that STRUCTURALLY has no proportional control is UNADJUDICATED,
  // which is a different thing from a run whose band came out too wide.
  // Collapsing the two would let a row with no gate at all report as though
  // it had failed one, and would let a broken band report as though it were
  // merely absent. `keystroke` is the real case: its control burns a fixed
  // 50 ms rather than doubling the page, so `control/floor` is `(F+50)/F`
  // and moves with `F` — not a pair whose true ratio is a page property.
  const unavailable = !!unavailableWhy;
  const ceilingBreached = !unavailable && !(bandWidth <= BAND_CEILING);
  const rows = {};
  for (const [name, value] of Object.entries(bars)) {
    const margin = Math.abs(value - 1);
    rows[name] = {
      value,
      margin,
      band: unavailable ? null : bandWidth,
      clear: !unavailable && !ceilingBreached && margin > bandWidth,
      unadjudicated: unavailable,
      why: unavailable
        ? `UNADJUDICATED — ${unavailableWhy}`
        : ceilingBreached
          ? `the run's band ${(bandWidth * 100).toFixed(1)}% exceeds the ${(BAND_CEILING * 100).toFixed(0)}% ceiling — ` +
            'no magnitude from this run is reportable'
          : margin > bandWidth
            ? `margin ${(margin * 100).toFixed(1)}% clears the band ${(bandWidth * 100).toFixed(1)}%`
            : `margin ${(margin * 100).toFixed(1)}% is INSIDE the band ${(bandWidth * 100).toFixed(1)}% — instrument-limited`,
    };
  }
  return { band: unavailable ? null : bandWidth, ceiling: BAND_CEILING, unavailable, ceilingBreached, rows };
}

/** Everything above, for one row. `fixedCells` null when the row has no proportional control. */
function assess({ floorBlocks, floorCells, fixedCells, bars, noFixedPairWhy }) {
  const s = seam(floorBlocks);
  const nul = seamNull(floorBlocks);
  const eff = effects(floorCells);
  let b = { n: 0, band: NaN, why: noFixedPairWhy || 'no proportional control on this row' };
  if (fixedCells) {
    const pairs = [];
    for (let r = 0; r < floorCells.length; r++) {
      for (let j = 0; j < floorCells[r].length; j++) pairs.push({ fixed: fixedCells[r][j], floor: floorCells[r][j] });
    }
    b = band(pairs);
  }
  return {
    seam: s, null: nul, effects: eff, bandStats: b,
    verdict: adjudicate(bars || {}, b.band, fixedCells ? null : b.why),
  };
}

function format(a, segmentNames) {
  const pc = (x) => (x * 100).toFixed(1) + '%';
  const out = [];
  out.push(
    `;; seam     the SAME floor read ${segmentNames.map((s, i) => `${s} ${a.seam.bySeg[i].toFixed(3)}`).join(', ')} ms ` +
      `— spread ${pc(a.seam.spread)}`
  );
  out.push(
    `;;          NULL for that statistic with segment identity relabelled WITHIN each round: ` +
      `median ${pc(a.null.q50)}, q95 ${pc(a.null.q95)}, q99 ${pc(a.null.q99)} over ${a.null.draws} draws — ` +
      `p=${a.null.p.toFixed(3)}. A max-over-min of three noisy block medians has a long right tail with ` +
      `nothing to attribute it to, so the seam is published WITH its null or not at all`
  );
  out.push(
    `;;          where the floor's variation lives: SEGMENT ${pc(a.effects.segment)}, ` +
      `ROUND ${pc(a.effects.round)}, POSITION-in-round ${pc(a.effects.position)}` +
      (a.effects.balanced
        ? ' (orthogonal — rounds are a multiple of segments, so each segment visits each position equally often)'
        : ' (NOT ORTHOGONAL — rounds are not a multiple of segments, so these three are confounded)')
  );
  out.push(
    Number.isFinite(a.bandStats.band)
      ? `;; band     ctl-2x/floor, two arms in the SAME block whose true ratio is a property of the page: ` +
        `p50 ${a.bandStats.p50.toFixed(4)} [p10 ${a.bandStats.p10.toFixed(4)} – p90 ${a.bandStats.p90.toFixed(4)}] ` +
        `over ${a.bandStats.n} blocks — BAND ${pc(a.bandStats.band)}. This is the part of the ambient ` +
        `perturbation that floor-normalisation does NOT cancel, and it is what a magnitude must clear`
      : `;; band     NOT AVAILABLE on this row — ${a.bandStats.why}`
  );
  for (const [name, r] of Object.entries(a.verdict.rows)) {
    const mark = r.unadjudicated ? 'UNADJ  ' : r.clear ? 'CLEARS ' : 'LIMITED';
    out.push(`;;   ${mark} ${name.padEnd(24)} ${r.value.toFixed(4)}x — ${r.why}`);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The self-test — deterministic, and it prices the claims the header makes.
// ---------------------------------------------------------------------------

function selfTest() {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: !!ok, detail });

  // Six rounds of three segments, a flat floor, one block per round of
  // samples. Deterministic values so nothing here depends on the PRNG
  // except the null, which is seeded.
  const flat = () => {
    const bs = [];
    for (let r = 0; r < 6; r++) bs.push([[10, 10, 10], [10, 10, 10], [10, 10, 10]]);
    return bs;
  };

  check('a flat floor has no seam', Math.abs(seam(flat()).spread) < 1e-12, `${seam(flat()).spread}`);

  // 1. A PURELY MULTIPLICATIVE PERTURBATION CANCELS EXACTLY. This is the
  //    header's central claim and it is arithmetic, not a measurement: scale
  //    every reading in a block by k and the block's floor-normalised ratio
  //    is unchanged to the last bit.
  {
    const H = 13.7, F = 10.0;
    const ks = [1, 1.34, 0.8, 2.5, 1.05];
    const rs = ks.map((k) => (H * k) / (F * k));
    check(
      'a block-wide multiplicative perturbation cancels EXACTLY in H/F',
      rs.every((x) => Math.abs(x - H / F) < 1e-12),
      `k in {${ks.join(', ')}} all give ${(H / F).toFixed(6)}`
    );
  }

  // 2. AN ADDITIVE ONE DOES NOT, and the band SEES it. `ctl-2x/floor` under
  //    an additive c reads (2W+c)/(W+c) and falls as c grows — which is the
  //    signature the ladder looked for and did not find.
  {
    const W = 5;
    const rat = (c) => (2 * W + c) / (W + c);
    const falls = rat(0) > rat(1) && rat(1) > rat(3);
    check(
      'an ADDITIVE perturbation makes ctl-2x/floor fall, which is how the band detects it',
      falls && Math.abs(rat(0) - 2) < 1e-12,
      `c=0 -> ${rat(0).toFixed(4)}, c=1 -> ${rat(1).toFixed(4)}, c=3 -> ${rat(3).toFixed(4)}`
    );
  }

  // 3. THE NULL HAS A LONG RIGHT TAIL, which is the reason the seam may not
  //    be reported bare. Three segments of IDENTICAL distribution, differing
  //    only by noise, must still produce a null q95 well above zero.
  {
    const next = rng(12345);
    const bs = [];
    for (let r = 0; r < 6; r++) {
      const blk = [];
      for (let j = 0; j < 3; j++) {
        const lvl = 10 * (1 + 0.25 * (next() - 0.5)); // a per-BLOCK ambient level, nothing to do with the segment
        blk.push(Array.from({ length: 10 }, () => lvl * (1 + 0.1 * (next() - 0.5))));
      }
      bs.push(blk);
    }
    const n = seamNull(bs);
    check(
      'the null of the seam statistic has a long right tail with NOTHING to attribute it to',
      n.q50 > 0.01 && n.q95 > n.q50 * 1.5,
      `median ${(n.q50 * 100).toFixed(1)}%, q95 ${(n.q95 * 100).toFixed(1)}%, q99 ${(n.q99 * 100).toFixed(1)}%`
    );
    check(
      'the null is REPRODUCIBLE — the same data twice gives the same p',
      seamNull(bs).p === n.p,
      `p=${n.p.toFixed(4)} both times`
    );
  }

  // 4. THE DECOMPOSITION SEPARATES A ROUND EFFECT FROM A SEGMENT ONE. Build
  //    a floor whose ONLY structure is a per-round ambient level; the
  //    segment and position marginals must come back flat, because the
  //    rotation balances them.
  {
    const lvls = [10, 14, 9, 12, 11, 13];
    const cells = lvls.map((L) => [L, L, L]);
    const e = effects(cells);
    check(
      'a pure ROUND effect lands on round and NOT on segment or position',
      e.balanced && e.round > 0.5 && e.segment < 1e-12 && e.position < 1e-12,
      `round ${(e.round * 100).toFixed(1)}%, segment ${(e.segment * 100).toFixed(1)}%, position ${(e.position * 100).toFixed(1)}%`
    );
  }
  {
    // and a pure POSITION effect must land on position, not on segment —
    // this is the contrast that says "order effect" rather than "adapter".
    const byPos = [12, 10, 9];
    const cells = [];
    for (let r = 0; r < 6; r++) cells.push([0, 1, 2].map((j) => byPos[(((j - r) % 3) + 3) % 3]));
    const e = effects(cells);
    check(
      'a pure POSITION-in-round effect lands on position and NOT on segment',
      e.position > 0.3 && e.segment < 1e-12,
      `position ${(e.position * 100).toFixed(1)}%, segment ${(e.segment * 100).toFixed(1)}%, round ${(e.round * 100).toFixed(1)}%`
    );
  }
  {
    // and a pure SEGMENT effect must land on segment. Without this the two
    // checks above would pass for a decomposition that always answered zero.
    const bySeg = [12, 10, 9];
    const cells = [];
    for (let r = 0; r < 6; r++) cells.push([0, 1, 2].map((j) => bySeg[j]));
    const e = effects(cells);
    check(
      'a pure SEGMENT effect lands on segment and NOT on position',
      e.segment > 0.3 && e.position < 1e-12 && e.round < 1e-12,
      `segment ${(e.segment * 100).toFixed(1)}%, position ${(e.position * 100).toFixed(1)}%, round ${(e.round * 100).toFixed(1)}%`
    );
  }
  {
    // An UNBALANCED design cannot be decomposed, and must say so rather than
    // answering. Five rounds of three segments: each segment does not visit
    // each position equally often, so the marginals are confounded.
    const cells = [];
    for (let r = 0; r < 5; r++) cells.push([10, 10, 10]);
    check(
      'a design whose rounds are not a multiple of its segments is flagged NOT ORTHOGONAL',
      effects(cells).balanced === false,
      '5 rounds, 3 segments'
    );
  }

  // 5. THE GATE. A margin inside the band is instrument-limited; one outside
  //    it clears; and a band above the ceiling takes every magnitude with it.
  {
    const pairs = Array.from({ length: 18 }, (_, i) => ({ fixed: 2 * (10 + (i % 3) * 0.2), floor: 10 + (i % 3) * 0.2 }));
    const b = band(pairs);
    check('a perfectly proportional pair has a ZERO band', Math.abs(b.band) < 1e-12, `band ${b.band}`);
    const v = adjudicate({ tight: 1.02, wide: 1.35 }, 0.06);
    check(
      'a margin inside the band is INSTRUMENT-LIMITED and one outside it CLEARS',
      v.rows.tight.clear === false && v.rows.wide.clear === true,
      `${v.rows.tight.why} | ${v.rows.wide.why}`
    );
    // Stated against BAND_CEILING rather than a literal, so moving the
    // ceiling cannot leave this check silently testing nothing — which is
    // exactly what a literal 0.2 did when the ceiling moved from 15% to 25%.
    const v2 = adjudicate({ wide: 1.35 }, BAND_CEILING + 0.05);
    check(
      'a band above the ceiling takes EVERY magnitude with it, however wide the margin',
      v2.ceilingBreached && v2.rows.wide.clear === false,
      v2.rows.wide.why
    );
    const v2ok = adjudicate({ wide: 1.35 }, BAND_CEILING - 0.05);
    check(
      'a band just BELOW the ceiling does not breach, so the ceiling check is not vacuous',
      v2ok.ceilingBreached === false && v2ok.rows.wide.clear === true,
      v2ok.rows.wide.why
    );
    // A NaN band must refuse rather than pass: `margin > NaN` is false, so
    // `clear` is already false, but the ceiling test has to agree — written
    // as `!(band <= ceiling)` for exactly this reason.
    const v3 = adjudicate({ wide: 1.35 }, NaN);
    check(
      'a band that could not be computed REFUSES rather than passing quietly',
      v3.ceilingBreached && v3.rows.wide.clear === false,
      v3.rows.wide.why
    );
    // A row with NO proportional control is UNADJUDICATED, not breached —
    // and the two must not read alike, because one says "this instrument
    // could not answer" and the other says "this box was too unstable".
    const v4 = adjudicate({ wide: 1.35 }, NaN, 'its control burns a fixed 50 ms rather than doubling the page');
    check(
      'a row with no proportional control is UNADJUDICATED, which is not the same as breached',
      v4.unavailable && v4.rows.wide.unadjudicated && v4.rows.wide.clear === false && v4.ceilingBreached === false,
      v4.rows.wide.why
    );
    const a5 = assess({
      floorBlocks: Array.from({ length: 6 }, () => [[10], [10], [10]]),
      floorCells: Array.from({ length: 6 }, () => [10, 10, 10]),
      fixedCells: null,
      bars: { 'h / r': 1.35 },
      noFixedPairWhy: 'no doubled-page control on this row',
    });
    check(
      'assess with no fixed pair reports the band as unavailable and adjudicates nothing',
      !Number.isFinite(a5.bandStats.band) && a5.verdict.unavailable && a5.verdict.rows['h / r'].unadjudicated,
      a5.verdict.rows['h / r'].why
    );
  }

  // 6. THE LADDER'S OWN HEADLINE, replayed as a fixture: the published run's
  //    three floors. The seam is 34% and the decomposition is the point —
  //    with one round of data there is nothing to attribute it to, which is
  //    why the instrument now takes the null alongside.
  {
    const published = [3.147, 2.437, 2.349];
    check(
      "the published run's 34% seam is reproduced by this module's own arithmetic",
      Math.abs(spread(published) - 0.3397) < 0.001,
      `${(spread(published) * 100).toFixed(1)}%`
    );
  }

  return { ok: checks.every((c) => c.ok), checks };
}

module.exports = {
  BAND_CEILING, NULL_DRAWS,
  median, quantile, spread,
  seam, seamNull, effects, band, adjudicate, assess, format, selfTest,
};

if (require.main === module) {
  const st = selfTest();
  for (const c of st.checks) console.log(`${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.detail ? '  — ' + c.detail : ''}`);
  if (!st.ok) {
    console.error('\nseam self-test FAILED');
    process.exit(1);
  }
  console.log('\nseam self-test ok');
}
