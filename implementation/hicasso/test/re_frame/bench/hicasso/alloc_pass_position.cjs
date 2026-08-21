'use strict';
// THE WITHIN-ROUND PASS-POSITION TERM, AND WHETHER IT IS THE PASS OR THE
// PARITY — rf2-fk6pj.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs <dataset.json>...
//     node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs --self-test
//
// ## WHAT THIS ADJUDICATES
//
// `rf2-0gjqi`'s paired window measured that, under `P0_ALLOC_WRITE=paired`,
// THE PASS THAT RAN SECOND READS LOWER — 10 of 12 round blocks, 6 of 6 in run
// 1 and 4 of 6 in run 2, median second-minus-first −0.59%, whichever write
// occupied it. Decomposed under an additive position model the pass-order
// half-difference read +0.68% and +0.21%, the same sign on both runs, while
// the order-free write half-sum read −0.33% and +0.24%, opposite signs.
//
// What that window could NOT establish is whether the carrier is the PASS
// POSITION or some other EVEN/ODD PROPERTY OF THE ROUND, because the rig tied
// them: the leg order was `round % 2`, so every `page`-first round was an even
// round and the two are one column in the design matrix.
//
// `P0_ALLOC_PASS_ORDER=seeded` (`p0_run.cjs`, rf2-fk6pj) draws a BALANCED leg
// schedule from a recorded seed instead. The two columns separate, and this
// reader is what reads them apart: it decomposes the same block statistic on
// BOTH groupings — by which pass ran first, and by round parity — and reports
// them side by side.
//
// ## THE ESTIMATOR, VERBATIM FROM THE RECORD IT MUST REPRODUCE
//
// For every round r, segment s and arm a the paired record carries four
// windows — the arm and that segment's floor, each under each write, all four
// measured in round r on the same page in the same process:
//
//     d_all (r,s,a) = (arm@all .legMedian − floor@all .legMedian) / B
//     d_page(r,s,a) = (arm@page.legMedian − floor@page.legMedian) / B
//     Δ     (r,s,a) = d_page − d_all
//
// A round contributes a cell only if ALL FOUR of its windows certified.
//
// **THE FLOOR MUST BE SUBTRACTED, AND THAT IS NOT A REFINEMENT.** The 10-of-12
// reproduces to the block on `d` as defined above; reading the same blocks off
// the raw `perBoundaryPerWrite` field instead gives 3 of 6 in run 1 and the
// wrong magnitude. The floor is the dominant shared term and the arms are read
// above it, so an estimator that skips the subtraction is measuring the floor.
//
// **THE BLOCK IS THE ROUND, NOT THE RUNG.** Within one round every arm of a
// segment differences against the same floor pair, and `rf2-77gz8`'s term is
// page-global, so a level term common to a round moves every cell in it
// together. The block statistic is the median of `Δ / d_all` over that round's
// certified mid-rung (R3, R7) cells.
//
// ## THE UNIT
//
// Every figure this reader prints as a percent is a RATIO of byte quantities,
// derived at the point of printing from a fraction held in the field. Nothing
// here is a byte threshold and nothing here is compared against one.
//
// ## WHICH PASS RAN FIRST IS READ OFF THE RECORD, NEVER RECOMPUTED
//
// `perRound[r].writeLegs[0]` is the leg that actually ran first in round r.
// Under `seeded` NOTHING recovers that from the round index — that is the
// whole point of the mode — so a reader that recomputed `round % 2` would
// mis-sign every block of a seeded run while reproducing every block of a
// parity one, which is the worst possible failure mode: silent on the corpus
// it is pinned against and wrong on the corpus it exists for.
//
// ## THE ARBITERS ARBITRATE, AND THAT IS A REPAIR (audit of PR #8601, #8615)
//
// This reader used to PRINT `controlVerdict`, `verification.unverified`,
// `passOrder`, `parityTied` and `scheduleDrove` and then feed every row to the
// blocks, the headline and both decompositions REGARDLESS. A copy of a real
// record with `controlVerdict.ok = false` still produced the headline. The
// audit of #8615 found the front-door version in `alloc_pass_design.cjs` had
// its own hole on top: `scheduleDrove` iterated only the rows PRESENT, so a
// record with `perRound` emptied or truncated to one row returned `true`
// VACUOUSLY and the boundary blessed missing evidence.
//
// `admissibleRun` and `admissibleCorpus` below are that boundary, moved IN.
// A refused run contributes no figure and `report` prints no figure at all —
// it prints the clause that refused it. Completeness is now its own clause:
// the realised round set must be the complete, unique `0..rounds-1`, so a
// truncated record is refused rather than blessed.
//
// ## THE BAND IS A RESTRICTED RANDOMISATION, AND IT IS ON THE AGGREGATE
//
// Phase 3 (PR #8615) refused the pass term against a band built at ten times
// its own null-arm p90. It recorded the defect rather than repairing it: that
// band compared an AGGREGATE — a contrast of medians over 316 cells — against
// a PER-OBSERVATION noise scale. `rf2-0eu1s` then found the reason the scale
// could not be repaired by re-cutting it either: the null arm is TWO DISJOINT
// POPULATIONS, so a pooled percentile of it is not a magnitude at all and the
// same rule returns 45 on one window and 610 on the next.
//
// So the band here is not a byte threshold and is not derived from one. The
// noise scale and the signal scale are THE SAME OBJECT because they are the
// same statistic on the same blocks: the reference distribution of a term is
// what that term reads when the PASS LABELS are re-drawn from the schedules
// the design would equally have admitted, with the block VALUES left exactly
// where they were measured.
//
// Restricted, and the restriction is the design's own. A re-labelling is
// admissible only if it is balanced, `q·parity = 0` and `q·linear = 0` — the
// criteria `alloc_pass_design.cjs` selected the real schedules under. At
// twelve rounds there are 48 such schedules and the set is CLOSED UNDER
// COMPLEMENT, so every reference distribution here is exactly symmetric about
// zero by construction rather than by assumption, and any parity structure or
// linear drift in the block values enters every re-labelling symmetrically.
//
// WHAT IT DOES NOT CONTROL, named rather than assumed away: residual functions
// of the round index that the two balanced columns do not span — `r mod 4`
// among them — are as free in the reference as they are in the observation.
// That is the same residual the design names and does not filter on.
//
// AND IT CARRIES ITS OWN NEGATIVE CONTROL. The identical machinery runs on the
// R = 0 NULL ARM, whose true term is zero by construction. A band that returns
// a significant pass term THERE is a band that cannot be believed on the mid
// rungs, and the pre-registration makes that outcome a refusal rather than a
// footnote.
//
// ## WHAT THIS IS NOT
//
// It is not a gate. No run passes or fails on it, no threshold here is a
// budget, and nothing here reads, moves or is calibrated against tau in either
// direction. It is a reader over records that already exist, exactly as
// `alloc_position_confound.cjs` and `alloc_level_witness.cjs` are. The band is
// a rank of one number among others computed the same way; it is not a byte
// threshold, it is not compared against one, and it cites no published floor.

const fs = require('node:fs');
const path = require('node:path');

// The mid-rung cells, verbatim from the record's section A: R3 and R7 on each
// of four arm families. A DESCRIPTION of the population the published window
// was read over and not a threshold anything is adjudicated against.
const MID_RUNGS = ['R3', 'R7'];
const FAMILIES = [
  { segment: 'reagent-subs', arm: 'lad/hicasso' },
  { segment: 'reagent-subs', arm: 'lad/reagent' },
  { segment: 'uix-subs', arm: 'lad/hicasso' },
  { segment: 'uix-subs', arm: 'lad/uix' },
];
const FLOOR_ARM = 'grid/floor';
// The null arm, whose true value is known in advance: boundaries that read
// nothing, so `arm − floor` must be zero under either write.
const NULL_RUNG = 'R0';
const NULL_RUNGS = [NULL_RUNG];

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

const pct = (x, d = 2) => (x === null ? 'n/a' : `${x >= 0 ? '+' : ''}${(x * 100).toFixed(d)}%`);

// --- the record, read rather than recomputed ---------------------------------

// A window, by the key the driver writes: `<segment>|<arm>@<writeSelector>`.
// Returns `null` where the window is absent OR uncertified, because those are
// the same thing to every estimator here — a round contributes a cell only if
// all four of its windows certified.
function certifiedWindow(round, segment, arm, leg) {
  const w = (round.arms || {})[`${segment}|${arm}@${leg}`];
  return w && w.certified ? w : null;
}

// `d` for one arm in one round under one write, in bytes per boundary, with
// the segment's floor subtracted. `null` where either window is missing or
// uncertified.
function armOverFloor(round, segment, arm, leg, boundaries) {
  const a = certifiedWindow(round, segment, arm, leg);
  const f = certifiedWindow(round, segment, FLOOR_ARM, leg);
  if (!a || !f) return null;
  return (a.legMedian - f.legMedian) / boundaries;
}

// Every matched certified cell of one round, at the rungs asked for.
//
// `ratio` IS NULL WHERE `d_all` IS ZERO, AND THE CELL IS STILL RETURNED. An
// earlier revision of this function DROPPED such a cell, which is correct for
// the block statistic — a ratio against a zero denominator is not a number —
// and wrong for the null arm, where `d_all = 0` is the value the arm is
// SUPPOSED to take. It cost 14 of the 38 cells `rf2-0gjqi` published in its
// section E, and it silently kept exactly the cells where the instrument had
// behaved perfectly. The self-test now pins that section's published figures
// for the same reason it pins the blocks: the defect was invisible in every
// figure the reader was pinned on.
function roundCells(round, boundaries, rungs) {
  const out = [];
  for (const { segment, arm } of FAMILIES) {
    for (const rung of rungs) {
      const key = `${arm}#${rung}`;
      const dAll = armOverFloor(round, segment, key, 'all', boundaries);
      const dPage = armOverFloor(round, segment, key, 'page', boundaries);
      if (dAll === null || dPage === null) continue;
      out.push({
        segment,
        arm,
        rung,
        dAll,
        dPage,
        delta: dPage - dAll,
        ratio: dAll === 0 ? null : (dPage - dAll) / dAll,
      });
    }
  }
  return out;
}

// --- the block statistic -----------------------------------------------------

// One block per round: the median of `Δ / d_all` over that round's certified
// mid-rung cells, beside WHICH LEG RAN FIRST — read off `writeLegs`, never
// recomputed from the round index. See the header.
//
// `rungs` AND `stat` ARE PARAMETERS SO THE NULL ARM CAN BE PUSHED THROUGH THE
// IDENTICAL PIPELINE, and that is the whole of what the band needs from this
// function. The defaults are the published estimator verbatim. The null arm
// takes `NULL_RUNGS` and the `delta` statistic instead of the `ratio` one for
// a reason that is arithmetic and not a preference: at R = 0 the denominator
// `d_all` is the quantity the arm is SUPPOSED to read as zero, so a ratio
// against it is not a number. The two readings are the same pipeline at the
// same aggregation level in different units, and NOTHING here ever compares a
// figure in one to a figure in the other — the null arm is a control on the
// BAND, not a noise scale a magnitude is measured against.
function blocks(row, label, rungs = MID_RUNGS, stat = 'ratio') {
  const B = row.boundaries;
  const out = [];
  for (const round of row.perRound || []) {
    // A cell with no ratio carries no block statistic — see `roundCells`. At
    // the mid rungs `d_all` is thousands of bytes per boundary and this drops
    // nothing, which the self-test's per-block `n` is what proves.
    const cells = roundCells(round, B, rungs).filter((c) => c[stat] !== null);
    if (!cells.length) continue;
    const first = (round.writeLegs || [])[0] || null;
    const m = median(cells.map((c) => c[stat]));
    out.push({
      run: label,
      round: round.round,
      first,
      n: cells.length,
      m,
      // Arithmetic on the column beside it, not a further estimator: `(page −
      // all)` already IS `second − first` when `all` ran first, and is its
      // negation when `page` did.
      secondMinusFirst: first === 'page' ? -m : m,
      evenRound: round.round % 2 === 0,
    });
  }
  return out;
}

// The additive decomposition, on whichever GROUPING is handed in. Under an
// additive model the half-sum of the two groups' medians is the term the
// grouping is blind to and the half-difference is the term it isolates.
//
// It is called TWICE and that is the whole of what rf2-fk6pj added: once on
// the pass grouping and once on round parity. Under `parity` those two are the
// same partition and the second call is inert; under `seeded` they are
// different partitions and the two half-differences are separately readable.
function decompose(bs, isA) {
  const a = bs.filter(isA).map((b) => b.m);
  const c = bs.filter((b) => !isA(b)).map((b) => b.m);
  const ma = median(a);
  const mc = median(c);
  if (ma === null || mc === null) return { nA: a.length, nB: c.length, a: ma, b: mc, half: null, free: null };
  return { nA: a.length, nB: c.length, a: ma, b: mc, half: (ma - mc) / 2, free: (ma + mc) / 2 };
}

// HOW FAR THE SCHEDULE ACTUALLY SEPARATED THE TWO COLUMNS, measured off the
// blocks rather than assumed from the mode name. `dot` is the inner product of
// the parity indicator (+1 even) and the pass indicator (+1 `page`-first) over
// the blocks: ±n is a schedule in which the two are the same column up to
// sign, and 0 is one in which they are orthogonal. A `parity` corpus reads
// exactly −n, which is the tie this bead exists to break.
function separation(bs) {
  let dot = 0;
  for (const b of bs) dot += (b.evenRound ? 1 : -1) * (b.first === 'page' ? 1 : -1);
  return { dot, n: bs.length, orthogonal: dot === 0 };
}

// --- the controls, which arbitrate ------------------------------------------
//
// A run whose positive control failed contributes no data, so the verdict is
// read off the record and reported per run, never pooled and never inferred
// from the figures. `alloc_cluster_carrier.cjs`'s own admissibility defect
// (rf2-csca8) was exactly this check being absent.
function controls(row) {
  const v = row.controlVerdict || {};
  const ver = row.verification || {};
  return {
    ok: v.ok === true,
    perDouble: v.perDouble ?? null,
    differential: v.differential ?? null,
    unverified: ver.unverified ?? null,
    passOrder: row.passOrder || 'parity',
    passSeed: row.passSeed ?? null,
    parityTied: row.passSchedule ? row.passSchedule.parityTied === true : null,
    // WHETHER THE DRAW AND THE DRIVE AGREE. `passSchedule.flips[r]` is what was
    // drawn and `perRound[r].writeLegs` is what ran; a record where they
    // disagree is a record whose own schedule is unknown, and no figure may be
    // read off it.
    scheduleDrove: scheduleDrove(row),
  };
}

// IT FAILS CLOSED ON A CORPUS THAT IS NOT THERE, and that is the audit of
// #8615's finding repaired at its source. The earlier revision iterated only
// the rows PRESENT, so a record whose `perRound` was emptied or truncated to
// one row returned `true` — the check reported agreement between a draw of
// twelve rounds and a drive of one. "Every row present agreed" is not the
// claim this function's name makes, and a boundary built on it blessed missing
// evidence. It now requires the drive to cover EVERY round the draw scheduled,
// exactly once each, before it can return `true`.
function scheduleDrove(row) {
  const sched = row.passSchedule;
  const legs = row.writeLegs || [];
  if (!sched || !Array.isArray(sched.flips) || legs.length !== 2) return null;
  const seen = new Set();
  for (const round of row.perRound || []) {
    const flip = sched.flips[round.round];
    if (flip === undefined) return null;
    if (seen.has(round.round)) return false;
    seen.add(round.round);
    const want = flip ? [...legs].reverse() : legs;
    const got = round.writeLegs || [];
    if (got.length !== want.length || got.some((l, i) => l !== want[i])) return false;
  }
  return seen.size === sched.flips.length;
}

// --- the schedule as two design columns -------------------------------------
//
// `q(r) = +1` where `page` ran first — the base leg order is `[page, all]`, so
// `page` leads exactly where the flip is FALSE. `p(r) = +1` on an even round.
// `l(r) = r − (R−1)/2` is the centred round index. Two dot products, computed
// here rather than imported, because `alloc_pass_design.cjs` imports from THIS
// file and the dependency may not run the other way.
const passIndicator = (flips) => flips.map((f) => (f ? -1 : 1));
const parityDot = (flips) =>
  passIndicator(flips).reduce((s, q, r) => s + q * (r % 2 === 0 ? 1 : -1), 0);
const linearDot = (flips) =>
  passIndicator(flips).reduce((s, q, r) => s + q * (r - (flips.length - 1) / 2), 0);
const flipKey = (flips) => flips.map((f) => (f ? '1' : '0')).join('');

// --- the boundary, which fails closed ----------------------------------------
//
// Every clause is a DECLARED PARAMETER of a window or a control the record
// already carries. Nothing here is a tolerance, nothing is compared against a
// byte threshold, and no clause was chosen after seeing a figure — the
// declaration is a committed file that predates run 1.
//
// `plan`, `roots`, `boundaries` and `writes` are checked because the audit of
// #8615 found the front door admitting them changed: `boundaries` is the `B`
// every `d` on this page is divided by, so a run that moved it is not a run of
// the same estimator at all.
function admissibleRun(row, expect = {}) {
  const reasons = [];
  const v = row.controlVerdict || {};
  const ver = row.verification || {};
  const eq = (got, want, name) => {
    if (want !== undefined && got !== want) reasons.push(`${name} ${JSON.stringify(got)} is not the declared ${JSON.stringify(want)}`);
  };

  if (v.ok !== true) reasons.push('positive control did not pass');
  if (ver.unverified !== 0) reasons.push(`unverified read-backs ${ver.unverified}`);

  eq(row.passOrder, expect.passOrder, 'pass order');
  eq(row.passSeed, expect.passSeed, 'seed');
  eq(row.rounds, expect.rounds, 'rounds');
  eq(row.writePaired, expect.writePaired, 'writePaired');
  eq(row.segOrder, expect.segOrder, 'segment order');
  eq(row.controlSlot, expect.controlSlot, 'control slot');
  // `plan` is an OBJECT in the record (`{name, arms, rungs, fits}`), so the
  // declared parameter is its NAME. A declaration comparing the object would
  // never match and the clause would refuse every run — a boundary that
  // refuses everything is no more a boundary than one that refuses nothing.
  eq(row.plan && row.plan.name, expect.plan, 'plan');
  eq(row.roots, expect.roots, 'roots');
  eq(row.boundaries, expect.boundaries, 'boundaries');
  eq(row.writes, expect.writes, 'writes');
  if (expect.writeLegs && (row.writeLegs || []).join(',') !== expect.writeLegs.join(',')) {
    reasons.push(`write legs ${JSON.stringify(row.writeLegs)} are not the declared ${JSON.stringify(expect.writeLegs)}`);
  }

  const sched = row.passSchedule;
  if (!sched || !Array.isArray(sched.flips)) {
    reasons.push('no schedule in the record');
  } else {
    if (sched.parityTied !== false) reasons.push('the draw was parity-tied');
    if (expect.rounds !== undefined && sched.flips.length !== expect.rounds) {
      reasons.push(`the schedule is ${sched.flips.length} rounds, not the declared ${expect.rounds}`);
    }
    const pd = parityDot(sched.flips);
    const ld = linearDot(sched.flips);
    if (pd !== 0) reasons.push(`q·parity ${pd}`);
    if (ld !== 0) reasons.push(`q·linear ${ld}`);
    if (expect.flips && flipKey(sched.flips) !== flipKey(expect.flips)) {
      reasons.push('the drawn schedule is not the one this run declared');
    }
    // THE REALISED ROUND SET, which is where a truncated corpus is caught. The
    // design's identification is a property of the labelling that actually
    // carried data, not of the one the record says it drew.
    const rounds = (row.perRound || []).map((r) => r.round);
    const unique = new Set(rounds);
    if (rounds.length !== sched.flips.length || unique.size !== rounds.length) {
      reasons.push(`the drive covers ${rounds.length} round(s) (${unique.size} distinct), not the scheduled ${sched.flips.length}`);
    } else {
      for (let r = 0; r < sched.flips.length; r++) {
        if (!unique.has(r)) { reasons.push(`round ${r} is missing from the drive`); break; }
      }
    }
  }
  if (scheduleDrove(row) !== true) reasons.push('the drive does not match the draw');
  return { ok: reasons.length === 0, reasons };
}

// THE CORPUS, not one run of it. A window that declared eight runs and can show
// six has not taken the window it declared, and the audit of #8615's `--admit`
// exiting 0 on an EMPTY corpus is exactly that hole. There is no partial
// credit: the count is fixed before run 1 and a short corpus is refused.
function admissibleCorpus(rows, declared) {
  const reasons = [];
  const runs = declared.runs || [];
  if (rows.length !== runs.length) {
    reasons.push(`the corpus holds ${rows.length} run(s), not the declared ${runs.length}`);
  }
  const per = rows.map(({ label, row }, i) => {
    const want = runs[i] ? { ...declared.window, ...runs[i] } : declared.window;
    const got = admissibleRun(row, want);
    if (!got.ok) reasons.push(`run ${label}: ${got.reasons.join('; ')}`);
    return { label, ...got };
  });
  return { ok: reasons.length === 0, reasons, per };
}

// --- the band ----------------------------------------------------------------

// Every balanced schedule over `rounds` with `q·parity = 0` and `q·linear = 0`
// — the design's own admissibility, enumerated exhaustively rather than
// sampled. At twelve rounds it returns 48 of the 924 balanced schedules, and
// the set is closed under complement.
function admissibleSchedules(rounds) {
  const out = [];
  if (rounds > 24) throw new Error(`admissibleSchedules: ${rounds} rounds is beyond exhaustive enumeration`);
  for (let mask = 0; mask < 1 << rounds; mask++) {
    let n = 0;
    for (let r = 0; r < rounds; r++) if ((mask >> r) & 1) n++;
    if (n * 2 !== rounds) continue;
    const flips = Array.from({ length: rounds }, (_, r) => Boolean((mask >> r) & 1));
    if (parityDot(flips) === 0 && linearDot(flips) === 0) out.push(flips);
  }
  return out;
}

// The blocks re-labelled under a schedule. The VALUES are untouched — only
// which group each block falls into changes, which is the whole content of the
// null hypothesis being tested.
function relabel(bs, flips) {
  return bs.map((b) => ({ ...b, first: flips[b.round] ? 'all' : 'page' }));
}

const passTerm = (bs) => decompose(bs, (b) => b.first === 'page').half;

// The term this window reads: the MEAN of the per-run pass terms. Each run is a
// complete balanced design on its own, so a run-level offset — and phase 2
// measured one of 20% on a floor — cancels exactly out of every per-run
// contrast before the runs are combined. Pooling all blocks first does not have
// that property, and is reported beside it rather than instead of it.
function meanRunTerm(runs) {
  const ts = runs.map((r) => passTerm(r.blocks)).filter((t) => t !== null);
  return ts.length ? ts.reduce((a, b) => a + b, 0) / ts.length : null;
}

// A 32-bit PRNG seeded from a string, so a reference distribution is a function
// of the declaration and nothing else. Two readers on two machines get the same
// draws from the same committed seed.
function rng(seed) {
  let h = 2166136261 >>> 0;
  for (const ch of String(seed)) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return () => {
    h += 0x6d2b79f5;
    let t = h;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// THE REFERENCE DISTRIBUTION, and the two-sided rank of the observation in it.
//
// Each run's own reference set is EXACT — all 48 admissible re-labellings, so
// the per-run p-value is a rank among 48 and involves no sampling at all. Only
// the COMBINATION across runs is sampled, because the product is 48^8 and that
// is not enumerable. The sample is a fixed count from a committed seed.
//
// The p-value counts the observation itself, which is the standard
// conservative convention: with `draws` re-labellings the smallest attainable
// two-sided p is `1/(draws+1)`.
function termReference(runs, { draws, seed }) {
  const sets = runs.map((r) => {
    const flips = admissibleSchedules(r.rounds);
    return { blocks: r.blocks, flips, terms: flips.map((f) => passTerm(relabel(r.blocks, f))) };
  });
  const observed = meanRunTerm(runs);
  const next = rng(seed);
  const sample = [];
  for (let d = 0; d < draws; d++) {
    let s = 0;
    let n = 0;
    for (const set of sets) {
      const t = set.terms[Math.floor(next() * set.terms.length)];
      if (t !== null) { s += t; n++; }
    }
    sample.push(n ? s / n : 0);
  }
  const atLeast = sample.filter((t) => Math.abs(t) >= Math.abs(observed)).length;
  const sorted = sample.map(Math.abs).sort((a, b) => a - b);
  return {
    observed,
    draws,
    seed,
    // Per run, the exact rank among that run's own 48 re-labellings.
    perRun: sets.map((set, i) => {
      const t = passTerm(runs[i].blocks);
      const ge = set.terms.filter((x) => Math.abs(x) >= Math.abs(t)).length;
      return { label: runs[i].label, term: t, of: set.terms.length, p: ge / set.terms.length };
    }),
    p: (atLeast + 1) / (draws + 1),
    p95: sorted[Math.floor(sorted.length * 0.95)],
    p975: sorted[Math.floor(sorted.length * 0.975)],
  };
}

// The null arm — R = 0, all four windows certified. It is the only population
// here whose true value is known in advance, and it is what licenses reading
// the mid-rung numbers at all.
//
// It takes a LIST of rows because the figure it is read against is pooled over
// a whole window's runs, and a per-run reading of the same population is a
// different statistic on a third of the observations.
function nullArm(rows) {
  const deltas = [];
  for (const row of [].concat(rows)) {
    for (const round of row.perRound || []) {
      for (const c of roundCells(round, row.boundaries, [NULL_RUNG])) deltas.push(c.delta);
    }
  }
  const abs = deltas.map(Math.abs).sort((x, y) => x - y);
  return {
    n: deltas.length,
    median: median(deltas),
    absMedian: median(abs),
    p90: abs.length ? abs[Math.min(abs.length - 1, Math.floor(abs.length * 0.9))] : null,
    max: abs.length ? abs[abs.length - 1] : null,
  };
}

// --- the report --------------------------------------------------------------

// `declared` IS THE PRE-REGISTRATION, READ FROM A COMMITTED FILE, and passing
// it is what licenses a figure. A `seeded` corpus is by construction part of a
// declared design — the seed only means anything against the schedule it was
// selected to draw — so reading one WITHOUT its declaration is refused rather
// than annotated. A `parity` corpus predates the design and is read as before.
function report(rows, declared = null) {
  const out = [];
  const all = [];

  out.push(';; THE PASS-POSITION TERM, AND WHETHER IT IS THE PASS OR THE PARITY (rf2-fk6pj)');
  out.push(';;');

  const seeded = rows.filter(({ row }) => row.passOrder === 'seeded');
  if (seeded.length && !declared) {
    out.push(';; REFUSED — NO FIGURE IS PRINTED.');
    out.push(`;;   ${seeded.length} of ${rows.length} run(s) are \`seeded\`, and a seeded corpus is part of a`);
    out.push(';;   declared design: its seed means nothing except against the schedule it was');
    out.push(';;   selected to draw. Pass the window\'s committed pre-registration:');
    out.push(';;     alloc_pass_position.cjs --declared <pre-registration.json> <run.json>...');
    return { lines: out, refused: true };
  }
  if (declared) {
    const adm = admissibleCorpus(rows, declared);
    out.push(`;; THE DECLARATION — ${declared.window.label || 'unnamed'}, committed before run 1.`);
    for (const p of adm.per) {
      out.push(`;;   run ${p.label} | ${p.ok ? 'ADMITTED' : 'REFUSED — ' + p.reasons.join('; ')}`);
    }
    if (!adm.ok) {
      out.push(';;');
      out.push(';; THE CORPUS IS REFUSED AND NO FIGURE IS PRINTED. The run count and every');
      out.push(';; parameter above were fixed before run 1; a corpus that does not match the');
      out.push(';; declaration is not the window that was declared, and reading it anyway is');
      out.push(';; the defect this boundary exists to close.');
      for (const r of adm.reasons) out.push(`;;   ${r}`);
      return { lines: out, refused: true };
    }
    out.push(';;   ALL DECLARED RUNS ADMITTED.');
    out.push(';;');
  }
  out.push(';; CONTROLS — a run whose positive control failed contributes no data.');
  out.push(
    ';;   run | pass order | seed | parity-tied | schedule drove | control | B/double | differential | unverified'
  );
  for (const { label, row } of rows) {
    const c = controls(row);
    out.push(
      `;;   ${label} | ${c.passOrder} | ${c.passSeed} | ${c.parityTied} | ${c.scheduleDrove} | ` +
        `${c.ok ? 'OK' : 'FAILED'} | ${c.perDouble === null ? 'n/a' : c.perDouble.toFixed(2)} | ` +
        `${c.differential === null ? 'n/a' : c.differential.toFixed(2)} | ${c.unverified}`
    );
  }

  out.push(';;');
  out.push(';; THE ROUND BLOCKS — median (page − all)/all over the round\'s certified mid-rung cells.');
  out.push(';;   run | round | first pass | parity | n | median (page−all)/all | second−first');
  for (const { label, row } of rows) {
    const bs = blocks(row, label);
    all.push(...bs);
    for (const b of bs) {
      out.push(
        `;;   ${b.run} | ${b.round} | ${b.first} | ${b.evenRound ? 'even' : 'odd'} | ${b.n} | ` +
          `${pct(b.m)} | ${pct(b.secondMinusFirst)}`
      );
    }
  }

  const lower = all.filter((b) => b.secondMinusFirst < 0).length;
  out.push(';;');
  out.push(
    `;;   THE PASS THAT RAN SECOND READ LOWER IN ${lower} OF ${all.length} BLOCKS, ` +
      `median second−first ${pct(median(all.map((b) => b.secondMinusFirst)))}.`
  );

  const sep = separation(all);
  out.push(';;');
  out.push(
    `;;   SEPARATION — parity·pass over the ${sep.n} pooled blocks reads ${sep.dot}. ` +
      `${sep.orthogonal ? 'ORTHOGONAL: the two columns are separately readable.' : sep.dot === -sep.n || sep.dot === sep.n ? 'TIED: the two are one column and nothing below separates them.' : 'PARTIAL: the two are correlated but not collinear.'}`
  );

  out.push(';;');
  out.push(';; THE DECOMPOSITION, on BOTH groupings. Half-difference isolates the grouping\'s');
  out.push(';; own term; half-sum is the term that grouping is blind to.');
  out.push(';;   block set | grouping | group A | group B | half-difference (the TERM) | half-sum');
  const sets = [
    ...rows.map(({ label }) => ({ name: label, bs: all.filter((b) => b.run === label) })),
    { name: 'pooled', bs: all },
  ];
  for (const { name, bs } of sets) {
    const byPass = decompose(bs, (b) => b.first === 'page');
    const byParity = decompose(bs, (b) => b.evenRound);
    out.push(
      `;;   ${name} | PASS (page-first vs all-first) | ${pct(byPass.a)} (n=${byPass.nA}) | ` +
        `${pct(byPass.b)} (n=${byPass.nB}) | PASS TERM ${pct(byPass.half)} | write ${pct(byPass.free)}`
    );
    out.push(
      `;;   ${name} | PARITY (even vs odd round) | ${pct(byParity.a)} (n=${byParity.nA}) | ` +
        `${pct(byParity.b)} (n=${byParity.nB}) | PARITY TERM ${pct(byParity.half)} | write ${pct(byParity.free)}`
    );
  }

  out.push(';;');
  out.push(';; THE NULL ARM (R = 0), which is what licenses reading the rest. Bytes per boundary.');
  out.push(';;   run | n | median Δ | absolute median | 90th percentile | max');
  for (const { label, row } of rows) {
    const na = nullArm(row);
    out.push(
      `;;   ${label} | ${na.n} | ${na.median} | ${na.absMedian} | ${na.p90} | ${na.max}`
    );
  }
  const pooledNull = nullArm(rows.map((r) => r.row));
  out.push(
    `;;   pooled | ${pooledNull.n} | ${pooledNull.median} | ${pooledNull.absMedian} | ` +
      `${pooledNull.p90} | ${pooledNull.max}`
  );

  if (declared && declared.band) {
    const { draws, seed, alpha } = declared.band;
    const runsOf = (rungs, stat) =>
      rows.map(({ label, row }) => ({ label, rounds: row.rounds, blocks: blocks(row, label, rungs, stat) }));
    const signal = termReference(runsOf(MID_RUNGS, 'ratio'), { draws, seed });
    const control = termReference(runsOf(NULL_RUNGS, 'delta'), { draws, seed });

    out.push(';;');
    out.push(';; THE BAND — a restricted randomisation of the PASS LABELS over the schedules');
    out.push(`;; the design would equally have admitted (${admissibleSchedules(declared.window.rounds).length} at ${declared.window.rounds} rounds, closed under`);
    out.push(`;; complement). Block VALUES are untouched. ${draws} draws from the committed seed`);
    out.push(`;; ${JSON.stringify(seed)}; alpha ${alpha}. Nothing here is a byte threshold.`);
    out.push(';;   arm | term | two-sided p | reference p95 | reference p97.5');
    for (const [name, ref, unit] of [['MID-RUNG (signal)', signal, '%'], ['R=0 NULL (control)', control, 'B/boundary']]) {
      const shown = unit === '%' ? pct(ref.observed) : `${ref.observed === null ? 'n/a' : ref.observed.toFixed(2)} B/bnd`;
      const p95 = unit === '%' ? pct(ref.p95) : `${ref.p95.toFixed(2)} B/bnd`;
      const p975 = unit === '%' ? pct(ref.p975) : `${ref.p975.toFixed(2)} B/bnd`;
      out.push(`;;   ${name} | ${shown} | ${ref.p.toFixed(4)} | ${p95} | ${p975}`);
    }
    out.push(';;');
    out.push(';;   PER RUN, exact — the rank of the run\'s own term among all its re-labellings.');
    out.push(';;     run | mid-rung term | exact p (of 48) | null term | exact p');
    signal.perRun.forEach((s, i) => {
      const c = control.perRun[i];
      out.push(
        `;;     ${s.label} | ${pct(s.term)} | ${s.p.toFixed(4)} (${s.of}) | ` +
          `${c.term === null ? 'n/a' : c.term.toFixed(2) + ' B/bnd'} | ${c.p.toFixed(4)}`
      );
    });

    const sigOk = signal.p <= alpha;
    const ctlOk = control.p > alpha;
    out.push(';;');
    if (!ctlOk) {
      out.push(`;;   OUTCOME 3 — NO VERDICT. The R = 0 null arm, whose true term is ZERO by`);
      out.push(`;;   construction, itself returns p = ${control.p.toFixed(4)} <= ${alpha} through the identical`);
      out.push(';;   pipeline. A band that fires on a known-zero population cannot adjudicate the');
      out.push(';;   mid rungs, and the pre-registration makes that a refusal rather than a footnote.');
    } else if (sigOk) {
      out.push(`;;   OUTCOME 1 — THE PASS TERM IS ESTABLISHED as a within-window term at alpha ${alpha}:`);
      out.push(`;;   p = ${signal.p.toFixed(4)}, with the null-arm control clear at p = ${control.p.toFixed(4)}.`);
    } else {
      out.push(`;;   OUTCOME 2 — THE PASS TERM IS NOT ESTABLISHED. p = ${signal.p.toFixed(4)} > ${alpha}; the term`);
      out.push(';;   sits inside the spread the same design returns on re-labelled data.');
    }

    // THE SESSION, which is the block that has never been replicated on this
    // estimand and is the reason this window took more than one.
    // The session rider lives at the record's TOP level (`box.session`,
    // rf2-24o2z), beside `alloc` rather than inside it, so it arrives here as
    // its own field. A run that carries none is its own session rather than
    // silently joining another's.
    const sessions = new Map();
    for (const { label, row, box } of rows) {
      const k = (box && box.session && box.session.sessionStartedAt) || `unrecorded-${label}`;
      if (!sessions.has(k)) sessions.set(k, []);
      sessions.get(k).push({ label, rounds: row.rounds, blocks: blocks(row, label) });
    }
    out.push(';;');
    out.push(`;;   BY SESSION — ${sessions.size} session(s), and the session is the independent unit.`);
    out.push(';;     session | runs | mid-rung term');
    for (const [k, rs] of sessions) {
      out.push(`;;     ${k} | ${rs.map((r) => r.label).join(',')} | ${pct(meanRunTerm(rs))}`);
    }
    const signs = [...sessions.values()].map((rs) => Math.sign(meanRunTerm(rs) || 0));
    const agree = sessions.size > 1 && signs.every((s) => s === signs[0] && s !== 0);
    out.push(
      `;;     ${agree ? 'THE SESSIONS AGREE IN SIGN' : 'THE SESSIONS DO NOT AGREE IN SIGN'}` +
        `, so a claim of this term is ${agree ? `carried by all ${sessions.size}` : 'capped at a single session'}.` +
        ` With ${sessions.size} sessions that is a sign agreement on ${sessions.size} blocks and nothing stronger.`
    );
  }

  return { lines: out, refused: false };
}

// --- the self-test, which is this reader's own positive control ---------------
//
// It drives the SHIPPED functions over the COMMITTED `alloc-0gjqi` corpus and
// requires them to reproduce every figure `the sign follows the pass, not the
// write` published — all twelve blocks with their `n`, and both runs' and the
// pooled decomposition. That corpus was taken under `parity`, so it also pins
// the one property a seeded reader must not lose: the parity grouping and the
// pass grouping are THE SAME PARTITION there, and the two decompositions must
// agree to the digit.
//
// AND IT REQUIRES THE FLOOR-FREE ESTIMATOR TO DISAGREE. Reading the same blocks
// off the raw `perBoundaryPerWrite` field instead of `arm − floor` gives a
// different count, so a change that dropped the subtraction reds here rather
// than drifting.
function selfTest() {
  const assert = require('node:assert');
  const dir = path.join(__dirname, 'data', 'alloc-0gjqi');
  const rows = [
    { label: '1', row: JSON.parse(fs.readFileSync(path.join(dir, 'paired-run1.json'), 'utf8')).alloc },
    { label: '2', row: JSON.parse(fs.readFileSync(path.join(dir, 'paired-run2.json'), 'utf8')).alloc },
  ];

  // Both runs' controls passed at 8.00 B/double with 0 unverified read-backs.
  for (const { label, row } of rows) {
    const c = controls(row);
    assert.strictEqual(c.ok, true, `run ${label}: control verdict`);
    assert.strictEqual(c.unverified, 0, `run ${label}: unverified read-backs`);
    assert.strictEqual(c.differential.toFixed(2), '8.00', `run ${label}: differential`);
    assert.strictEqual(c.passOrder, 'parity', `run ${label}: the corpus is a parity corpus`);
  }

  // The twelve published blocks, `n` and median, to the published digits.
  const published = [
    ['1', 0, 'page', 3, '+0.35%'],
    ['1', 1, 'all', 8, '-1.00%'],
    ['1', 2, 'page', 7, '+0.35%'],
    ['1', 3, 'all', 8, '-1.03%'],
    ['1', 4, 'page', 4, '+0.74%'],
    ['1', 5, 'all', 7, '-0.78%'],
    ['2', 0, 'page', 4, '+1.51%'],
    ['2', 1, 'all', 8, '-0.93%'],
    ['2', 2, 'page', 7, '+0.45%'],
    ['2', 3, 'all', 5, '+0.03%'],
    ['2', 4, 'page', 6, '+0.28%'],
    ['2', 5, 'all', 6, '+0.36%'],
  ];
  const all = rows.flatMap(({ label, row }) => blocks(row, label));
  assert.strictEqual(all.length, published.length, 'block count');
  published.forEach((p, i) => {
    const b = all[i];
    assert.strictEqual(`${b.run}/${b.round}/${b.first}/${b.n}`, `${p[0]}/${p[1]}/${p[2]}/${p[3]}`,
      `block ${i}: run/round/first/n`);
    assert.strictEqual(pct(b.m), p[4], `block ${i}: median`);
  });

  // The published count, and the published median of the second-minus-first
  // column. That column's published median is -0.59%, which is the median of
  // the ROUNDED column; the unrounded one is -0.60%. Both are pinned so a
  // reader of either figure can see which is which.
  assert.strictEqual(all.filter((b) => b.secondMinusFirst < 0).length, 10, 'second-lower count');
  assert.strictEqual(pct(median(all.map((b) => b.secondMinusFirst))), '-0.60%', 'unrounded median');
  assert.strictEqual(
    median(published.map((p, i) => Number((all[i].secondMinusFirst * 100).toFixed(2)))).toFixed(2),
    '-0.59',
    'the published median, taken over the rounded column'
  );

  // Both runs' and the pooled decomposition on the PASS grouping.
  const dec = [
    ['1', '+0.35%', '-1.00%', '+0.68%', '-0.33%'],
    ['2', '+0.45%', '+0.03%', '+0.21%', '+0.24%'],
  ];
  for (const [label, a, b, half, free] of dec) {
    const d = decompose(all.filter((x) => x.run === label), (x) => x.first === 'page');
    assert.strictEqual(pct(d.a), a, `run ${label}: page-first median`);
    assert.strictEqual(pct(d.b), b, `run ${label}: all-first median`);
    assert.strictEqual(pct(d.half), half, `run ${label}: pass term`);
    assert.strictEqual(pct(d.free), free, `run ${label}: write term`);
  }
  const pooled = decompose(all, (x) => x.first === 'page');
  assert.strictEqual(pct(pooled.a), '+0.40%', 'pooled page-first median');
  assert.strictEqual(pct(pooled.b), '-0.86%', 'pooled all-first median');
  assert.strictEqual(pct(pooled.half), '+0.63%', 'pooled pass term');
  assert.strictEqual(pct(pooled.free), '-0.23%', 'pooled write term');

  // ON A PARITY CORPUS THE TWO GROUPINGS ARE ONE PARTITION, and the reader must
  // say so rather than pretend to have separated them.
  const byParity = decompose(all, (x) => x.evenRound);
  assert.strictEqual(pct(byParity.half), pct(pooled.half), 'parity and pass agree on a parity corpus');
  const sep = separation(all);
  // `+n` and not `-n`: under `parity` the EVEN rounds are the `page`-first
  // ones, so the two indicators agree in every block. The sign is a property
  // of which way round the rig's own ternary falls, not of the tie.
  assert.strictEqual(sep.dot, sep.n, 'a parity corpus is perfectly tied');
  assert.strictEqual(sep.orthogonal, false, 'and is therefore not orthogonal');

  // THE NULL ARM, pinned on the published section E — and it is the pin that
  // caught this reader's one real defect. A `d_all` of exactly zero is what the
  // R = 0 arm is SUPPOSED to read, and an earlier `roundCells` dropped every
  // such cell as a division hazard: 24 of 38 survived, the absolute median read
  // 3 instead of 1.5 and the 90th percentile 56.5 instead of 4.5. Nothing else
  // here would have seen it — every mid-rung figure above was unaffected.
  const na = nullArm(rows.map((r) => r.row));
  assert.strictEqual(na.n, 38, 'null arm: published n');
  assert.strictEqual(na.median, 0, 'null arm: published median');
  assert.strictEqual(na.absMedian, 1.5, 'null arm: published absolute median');
  assert.strictEqual(na.p90, 4.5, 'null arm: published 90th percentile');
  assert.strictEqual(na.max, 96.5, 'null arm: published max');

  // THE FLOOR-FREE ESTIMATOR MUST DISAGREE. Same blocks, `perBoundaryPerWrite`
  // instead of `arm − floor`, and the count falls to 3 of 6 in run 1.
  const floorFree = (row, label) => {
    const out = [];
    for (const round of row.perRound || []) {
      const rs = [];
      for (const { segment, arm } of FAMILIES) {
        for (const rung of MID_RUNGS) {
          const a = certifiedWindow(round, segment, `${arm}#${rung}`, 'all');
          const p = certifiedWindow(round, segment, `${arm}#${rung}`, 'page');
          const fa = certifiedWindow(round, segment, FLOOR_ARM, 'all');
          const fp = certifiedWindow(round, segment, FLOOR_ARM, 'page');
          if (!a || !p || !fa || !fp || !a.perBoundaryPerWrite) continue;
          rs.push((p.perBoundaryPerWrite - a.perBoundaryPerWrite) / a.perBoundaryPerWrite);
        }
      }
      if (rs.length) out.push({ run: label, m: median(rs), first: (round.writeLegs || [])[0] });
    }
    return out;
  };
  const ff = rows.flatMap(({ label, row }) => floorFree(row, label)).filter((b) => b.run === '1');
  const ffLower = ff.filter((b) => (b.first === 'page' ? -b.m : b.m) < 0).length;
  assert.strictEqual(ff.length, 6, 'floor-free run 1 block count');
  assert.notStrictEqual(ffLower, 6, 'the floor-free estimator must NOT reproduce run 1\'s 6 of 6');
  assert.strictEqual(ffLower, 3, 'and reads 3 of 6, which is the measured wrong answer');

  // --- THE BOUNDARY, PROVED IN BOTH DIRECTIONS -------------------------------
  //
  // A control that cannot refuse is not a control, and one that refuses
  // everything is not one either. So: one corpus that must be admitted, then
  // one clause at a time broken on it, then the shapes the audit of #8615
  // found the front door blessing — an EMPTY corpus, a TRUNCATED one, and one
  // with duplicate or out-of-range rounds.
  // THE DECLARATION IS A COMMITTED FILE AND ITS ABSENCE IS A FAILURE, not a
  // skip. A self-test that quietly passes when its fixture is missing is the
  // same class of defect as a boundary that admits an empty corpus.
  const declPath = path.join(__dirname, 'data', 'alloc-legorder', 'pre-registration.json');
  assert.ok(fs.existsSync(declPath), `the pre-registration must be committed at ${declPath}`);
  {
    const declared = JSON.parse(fs.readFileSync(declPath, 'utf8'));
    // The smallest record the boundary will admit, SHAPED AS THE DRIVER WRITES
    // ONE rather than as the declaration states it — `plan` is an object in a
    // record and a name in a declaration, and a fixture that spread the
    // declaration verbatim would be testing the boundary against a shape no
    // run has. It carries no arm windows at all: admissibility is decided on
    // declared parameters and controls, before a figure is read.
    const w = declared.window;
    const mk = (i) => {
      const legs = w.writeLegs;
      const flips = declared.runs[i].flips;
      return {
        passOrder: w.passOrder,
        passSeed: declared.runs[i].passSeed,
        rounds: w.rounds,
        writePaired: w.writePaired,
        writeLegs: legs.slice(),
        segOrder: w.segOrder,
        controlSlot: w.controlSlot,
        plan: { name: w.plan, arms: true, rungs: true, fits: true },
        roots: w.roots,
        boundaries: w.boundaries,
        writes: w.writes,
        passSchedule: { flips: flips.slice(), attempts: 1, parityTied: false },
        controlVerdict: { ok: true, perDouble: 8.08, differential: 8 },
        verification: { unverified: 0 },
        perRound: flips.map((f, r) => ({ round: r, writeLegs: f ? [...legs].reverse() : legs.slice(), arms: {} })),
      };
    };
    const good = declared.runs.map((_, i) => ({ label: String(i + 1), row: mk(i) }));
    const base = admissibleCorpus(good, declared);
    assert.strictEqual(base.ok, true, `the declared corpus is admitted: ${base.reasons.join('; ')}`);

    const breaks = [
      ['positive control', (r) => { r.controlVerdict.ok = false; }],
      ['unverified read-backs', (r) => { r.verification.unverified = 1; }],
      ['pass order', (r) => { r.passOrder = 'parity'; }],
      ['the seed', (r) => { r.passSeed = 'not-the-declared-seed'; }],
      ['the round count', (r) => { r.rounds = 6; }],
      ['the segment order', (r) => { r.segOrder = 'fixed-reversed'; }],
      ['the control slot', (r) => { r.controlSlot = 'last'; }],
      ['the boundary count', (r) => { r.boundaries = 8; }],
      ['the write count', (r) => { r.writes = 5; }],
      ['the plan', (r) => { r.plan = 'narrow'; }],
      ['the write legs', (r) => { r.writeLegs = ['all', 'page']; }],
      ['the paired write', (r) => { r.writePaired = false; }],
      ['a parity-tied draw', (r) => { r.passSchedule.parityTied = true; }],
      ['the declared schedule', (r) => { r.passSchedule.flips = declared.runs[1].flips.slice(); }],
      ['the drive against the draw', (r) => { r.perRound[0].writeLegs = ['all', 'page']; }],
      // THE THREE THE FRONT DOOR BLESSED.
      ['an EMPTY drive', (r) => { r.perRound = []; }],
      ['a TRUNCATED drive', (r) => { r.perRound = r.perRound.slice(0, 1); }],
      ['a DUPLICATED round', (r) => { r.perRound[1] = { ...r.perRound[0] }; }],
      ['an OUT-OF-RANGE round', (r) => { r.perRound[0] = { ...r.perRound[0], round: 99 }; }],
    ];
    for (const [name, breakIt] of breaks) {
      const rows = declared.runs.map((_, i) => ({ label: String(i + 1), row: mk(i) }));
      breakIt(rows[0].row);
      const got = admissibleCorpus(rows, declared);
      assert.strictEqual(got.ok, false, `breaking ${name} must refuse the corpus`);
    }
    // AND A SHORT CORPUS IS REFUSED ON ITS COUNT, which is the `--admit`
    // exiting 0 on an empty corpus that the audit found.
    assert.strictEqual(admissibleCorpus([], declared).ok, false, 'an empty corpus is refused');
    assert.strictEqual(admissibleCorpus(good.slice(0, 2), declared).ok, false, 'a short corpus is refused');

    // AND A SEEDED CORPUS READ WITHOUT ITS DECLARATION PRINTS NO FIGURE.
    const undeclared = report([{ label: '1', row: mk(0) }], null);
    assert.strictEqual(undeclared.refused, true, 'a seeded corpus with no declaration is refused');
    assert.ok(!undeclared.lines.some((l) => l.includes('THE ROUND BLOCKS')), 'and prints no blocks');
  }

  // --- THE BAND'S OWN CONTROLS ------------------------------------------------
  //
  // Driven on synthetic blocks whose true term is known by construction, so
  // what the reference must return is arithmetic rather than opinion.
  const sched12 = admissibleSchedules(12);
  assert.strictEqual(sched12.length, 48, 'the admissible set at 12 rounds');
  assert.ok(
    sched12.every((f) => sched12.some((g) => flipKey(g) === flipKey(f.map((x) => !x)))),
    'the admissible set is closed under complement, which is what makes the reference symmetric'
  );
  for (const f of sched12) {
    assert.strictEqual(parityDot(f), 0, 'every admissible schedule has q·parity 0');
    assert.strictEqual(linearDot(f), 0, 'and q·linear 0');
    assert.strictEqual(f.filter(Boolean).length, 6, 'and is balanced');
  }

  // The fixtures are driven over THE DECLARED EIGHT-RUN DESIGN, not over one
  // schedule, because the band is a property of the corpus and not of a run —
  // see the underpowered single run pinned at the end of this block. The
  // `0.0001 * r` ramp is a tie-breaker and nothing more: without it a pure
  // effect makes every block value ±`effect` and the median of six of them is
  // degenerate, which is a property of the FIXTURE and not of any real corpus.
  const declaredRuns = JSON.parse(fs.readFileSync(declPath, 'utf8')).runs;
  const synth = (flips, label, { pass = 0, parity = 0 }) =>
    flips.map((f, r) => ({
      run: label, round: r, first: f ? 'all' : 'page', n: 1,
      m: pass * (f ? -1 : 1) + parity * (r % 2 === 0 ? 1 : -1) + 0.0001 * r,
      secondMinusFirst: 0, evenRound: r % 2 === 0,
    }));
  const corpus = (model) =>
    declaredRuns.map((d, i) => ({ label: String(i + 1), rounds: 12, blocks: synth(d.flips, String(i + 1), model) }));
  const band = (model) => termReference(corpus(model), { draws: 20000, seed: 'self-test' });

  // A PURE PASS EFFECT AT THE SIZE THIS WINDOW IS LOOKING FOR — 0.6%, the
  // median of the four terms published across phases 2 and 3 — must clear the
  // band. A band that could not see the term it was built for would refuse
  // every window by construction, which is not a refusal but a broken
  // instrument.
  const hit = band({ pass: 0.006 });
  assert.ok(Math.abs(hit.observed - 0.006) < 1e-9, `a pure pass effect of 0.6% reads as 0.6%, got ${hit.observed}`);
  assert.ok(hit.p <= 0.05, `a 0.6% pass effect must clear the band, got p=${hit.p}`);

  // NO EFFECT MUST NOT CLEAR IT.
  const flat = band({});
  assert.ok(Math.abs(flat.observed) < 1e-9, 'no effect reads as 0');
  assert.ok(flat.p > 0.05, `no effect must NOT clear the band, got p=${flat.p}`);

  // A PURE PARITY EFFECT MUST NOT CLEAR IT, AND MUST READ AS EXACTLY ZERO.
  // This is the failure mode the whole bead exists to prevent, tested here on
  // the BAND rather than on the schedule: a parity term of 1 — 166 times the
  // pass term the window is looking for — moves the pass reading not at all.
  const par = band({ parity: 1 });
  assert.ok(Math.abs(par.observed) < 1e-9, `a pure parity effect reads as a pass term of 0, got ${par.observed}`);
  assert.ok(par.p > 0.05, 'and does not clear the band');

  // AND THE TWO TOGETHER RECOVER THE PASS TERM, not a blend of the two.
  const both = band({ pass: 0.006, parity: 1 });
  assert.ok(Math.abs(both.observed - 0.006) < 1e-9, `parity 1 + pass 0.6% still reads 0.6%, got ${both.observed}`);
  assert.ok(both.p <= 0.05, 'and still clears the band');

  // THE PER-RUN REFERENCE IS COARSE, AND THE CORPUS SIZE IS WHAT BUYS
  // RESOLUTION. One run's exact reference has exactly 48 points — every
  // admissible re-labelling of its own twelve blocks — so the smallest
  // p-value a single run can attain is 1/48, and no single run can say
  // anything finer however large its term. That is a structural property of
  // the design, pinned here rather than an effect size that would move with
  // the fixture.
  assert.strictEqual(hit.perRun.length, declaredRuns.length, 'every run gets its own exact reference');
  for (const r of hit.perRun) {
    assert.strictEqual(r.of, 48, 'each run is ranked among all 48 re-labellings');
    assert.ok(r.p >= 1 / 48 - 1e-12, `and cannot beat 1/48, got ${r.p}`);
  }

  // THE REFERENCE IS A FUNCTION OF THE SEED AND NOTHING ELSE.
  const twice = () => JSON.stringify(termReference(corpus({ pass: 0.004 }), { draws: 500, seed: 'k' }));
  assert.strictEqual(twice(), twice(), 'the same seed returns the same reference');
  assert.notStrictEqual(
    JSON.stringify(termReference(corpus({ pass: 0.004 }), { draws: 500, seed: 'k' }).p95),
    JSON.stringify(termReference(corpus({ pass: 0.004 }), { draws: 500, seed: 'other' }).p95),
    'and a different seed returns a different one, so the seed is doing work'
  );

  console.log(`[alloc-pass-position] self-test OK — 12 published blocks, both decompositions, `
    + `the parity tie, the null arm's 38 cells, the floor-free estimator's 3 of 6, `
    + `${sched12.length} admissible schedules, the band's three synthetic controls, and the `
    + `fail-closed boundary in both directions all reproduce`);
}

if (require.main === module) {
  const args = process.argv.slice(2);
  if (args[0] === '--self-test') {
    selfTest();
    process.exit(0);
  }
  let declared = null;
  let files = args;
  if (args[0] === '--declared') {
    if (args.length < 3) {
      console.error('usage: alloc_pass_position.cjs --declared <pre-registration.json> <dataset.json>...');
      process.exit(2);
    }
    declared = JSON.parse(fs.readFileSync(args[1], 'utf8'));
    files = args.slice(2);
  }
  if (!files.length) {
    console.error('usage: alloc_pass_position.cjs [--declared <pre-registration.json>] <dataset.json>... | --self-test');
    process.exit(2);
  }
  const rows = files.map((f, i) => {
    const j = JSON.parse(fs.readFileSync(f, 'utf8'));
    return { label: String(i + 1), row: j.alloc, box: j.box };
  });
  const { lines, refused } = report(rows, declared);
  for (const line of lines) console.log(line);
  // A REFUSAL EXITS NON-ZERO. The whole point of moving the boundary in is that
  // a caller cannot mistake a refused corpus for a read one.
  process.exit(refused ? 1 : 0);
}

module.exports = {
  MID_RUNGS,
  FAMILIES,
  FLOOR_ARM,
  NULL_RUNG,
  NULL_RUNGS,
  passIndicator,
  parityDot,
  linearDot,
  flipKey,
  admissibleRun,
  admissibleCorpus,
  admissibleSchedules,
  relabel,
  passTerm,
  meanRunTerm,
  rng,
  termReference,
  median,
  certifiedWindow,
  armOverFloor,
  roundCells,
  blocks,
  decompose,
  separation,
  controls,
  scheduleDrove,
  nullArm,
  report,
  selfTest,
};
