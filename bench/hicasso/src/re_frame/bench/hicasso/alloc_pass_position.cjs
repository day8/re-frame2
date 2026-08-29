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
// ## THREE MORE THINGS IT READ PAST, AND WHAT THEY HAVE IN COMMON (rf2-flxxa)
//
// The audit of PR #8634 and phase 4's own reading found three further shapes
// the boundary blessed. All three are the same mistake in different clothes —
// A CHECK ON WHAT THE RECORD SAYS, STANDING IN FOR A CHECK ON WHAT THE RECORD
// SHOWS — and none of them moved a figure phase 4 published, which is what made
// them worth closing rather than noting.
//
//   1. THE BALANCE WAS READ OFF THE DRAW, NOT OFF THE BLOCKS. `q·parity` and
//      `q·linear` were computed on `passSchedule.flips`. A round whose mid-rung
//      windows all failed to certify still appears there and still contributes
//      no block, so the labelling the term is READ ON could be short and
//      unbalanced under a draw that is neither. See `realisedLabelling`.
//   2. A RECORD CARRYING NO MEASURED WINDOW AT ALL was admissible, because
//      every clause was a declared parameter or a control and none of them
//      needed data to exist. `admissibleCorpus` closes that; `admissibleRun`
//      deliberately does not, and says why.
//   3. THE DECLARED SESSION was carried through the boundary and never
//      compared against the record. Collapsing all eight of phase 4's
//      `box.session.sessionStartedAt` values to a single ID left the corpus
//      admitted and OUTCOME 1 computed — a one-session window read as the
//      two-session design it declared. See `sessionPartition`.
//
// None was repaired inside phase 4's window, and that was not an oversight: an
// estimator must not change between a pre-registration and the runs it will be
// read on. They were recorded on the window's page and owned by a bead instead.
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
// COMPLEMENT, so any parity structure or linear drift in the block values
// enters every re-labelling symmetrically.
//
// AND THE RESTRICTION IS ON THE WHOLE ASSIGNMENT, NOT ON EACH RUN SEPARATELY,
// which is the repair `rf2-t4vu1` filed. Phase 4 drew TWO schedules and the
// design forced the other two as their complements, then repeated the quadruple
// verbatim in the second session — so the eight-run assignment ranges over
// 48 × 46 = 2,208 configurations and not over 48⁸. See `assignmentRoles` for
// why each restriction is load-bearing and `termReference` for what follows
// from the support being closed under global complement.
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

// THE LABELLING THAT ACTUALLY CARRIED DATA, which is a different object from
// the one the record says it DREW, and is the one the design's identification
// is a property of.
//
// `passSchedule.flips` is a draw. A round contributes a block only if at least
// one of its mid-rung cells had all four windows certify, so a round that lost
// its windows drops out of the labelling entirely — and the block set left
// behind can be short, and its `q·parity` and `q·linear` non-zero, while the
// DRAWN schedule is balanced and orthogonal on both. `q·parity = 0` on the draw
// is then a true statement about a set of blocks that does not exist.
//
// The centring is the SCHEDULED round count, not the surviving one: `l(r) = r −
// (R−1)/2` is a column of the design, fixed when the schedule was drawn, and
// re-centring it on whatever survived would silently redefine the term the
// balance is a balance of.
function realisedLabelling(row, scheduledRounds = null) {
  const bs = blocks(row, null);
  const R = scheduledRounds === null ? bs.length : scheduledRounds;
  let parity = 0;
  let linear = 0;
  let unknownFirst = 0;
  for (const b of bs) {
    if (b.first !== 'page' && b.first !== 'all') { unknownFirst++; continue; }
    const q = b.first === 'page' ? 1 : -1;
    parity += q * (b.round % 2 === 0 ? 1 : -1);
    linear += q * (b.round - (R - 1) / 2);
  }
  const rounds = bs.map((b) => b.round);
  return { n: bs.length, rounds, distinct: new Set(rounds).size, parity, linear, unknownFirst };
}

// WHETHER A RECORD CARRIES ANY MEASURED WINDOW AT ALL. A record with every
// round's `arms` empty is a PARAMETERS-ONLY FIXTURE — the shape a design
// control builds to exercise the declared clauses before a figure exists — and
// it asserts nothing about a labelling because it carries nothing to label.
// The distinction is not a let-off: `admissibleCorpus` refuses such a record
// outright, because a corpus is the thing a figure is read OFF.
const carriesWindows = (row) =>
  (row.perRound || []).some((r) => Object.keys(r.arms || {}).length > 0);

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

  // AND THE REALISED LABELLING, WHICH IS NOT THE DRAWN ONE. Everything above
  // reads `passSchedule.flips` — the schedule the record says it drew — and the
  // `perRound` rows the drive produced. Neither is the block set that carried
  // data: a round whose mid-rung windows all failed to certify is still a row
  // in `perRound` and still matches its flip, and it still contributes no
  // block. So the labelling the term is actually read on can be short, with a
  // non-zero `q·parity` or `q·linear`, under a draw that is balanced and
  // orthogonal on both — and the design's identification is a property of the
  // realised labelling, never of the draw.
  //
  // Phase 4 realised 96 blocks of a possible 96 with `q·parity` 0 in all eight
  // runs, so this clause changes no figure that window published. That is
  // precisely why it has to be a clause: the reading was checked by hand and
  // written into a paragraph, and a paragraph does not run.
  if (carriesWindows(row)) {
    const scheduled = sched && Array.isArray(sched.flips) ? sched.flips.length : null;
    const real = realisedLabelling(row, scheduled);
    if (real.n === 0) {
      reasons.push('the realised block set is empty — no round carried a certified mid-rung cell');
    } else if (scheduled !== null && (real.n !== scheduled || real.distinct !== scheduled)) {
      reasons.push(
        `the realised block set covers ${real.n} round(s) (${real.distinct} distinct), not the scheduled ${scheduled}`
      );
    }
    if (real.unknownFirst) reasons.push(`${real.unknownFirst} realised block(s) carry no leg order`);
    if (real.parity !== 0) reasons.push(`realised q·parity ${real.parity}`);
    if (real.linear !== 0) reasons.push(`realised q·linear ${real.linear}`);
  }
  return { ok: reasons.length === 0, reasons };
}

// THE DECLARED SESSION PARTITION, CHECKED RATHER THAN PRINTED (audit of PR
// #8634).
//
// `declared.runs[i].session` is a LABEL — phase 4 declares four runs into `A`
// and four into `B` — and it was the one declared parameter the boundary read
// past. Every other field of a declared run is compared against the record;
// this one was carried into `admissibleRun` and never looked at, so replacing
// all eight `box.session.sessionStartedAt` values with a single ID left the
// corpus ADMITTED and OUTCOME 1 computed, and a window that took ONE session
// was read as the two-session design it declared. The session is the
// independent unit here — it is the whole reason phase 4 cost eight runs
// instead of four — so admitting a collapsed partition is not a cosmetic miss.
//
// It is checked at CORPUS level because the evidence is: no run can tell you
// whether it shared a session with another. Two directions, and both are
// needed — one alone passes the shape it is blind to:
//
//   - runs declared into the SAME session must carry the SAME actual session,
//     or the declared group is really two and its runs are not the replicates
//     the design says they are;
//   - runs declared into DIFFERENT sessions must carry DIFFERENT actual ones,
//     which is the collapse above.
//
// The record's own session identity is `box.session.sessionStartedAt`, written
// by the rig at the record's TOP level beside `alloc` (rf2-24o2z). A run that
// carries none cannot be shown to have taken the session it was declared into,
// so it is refused rather than given one of its own — `report`'s
// `unrecorded-<label>` fallback is a display convenience and would, used here,
// make a missing rider look like a distinct session and PASS the collapse test.
function sessionPartition(rows, declared) {
  const reasons = [];
  const runs = declared.runs || [];
  if (!runs.some((r) => r && r.session !== undefined)) return reasons;

  // declared label -> actual session id -> the run labels that carried it.
  const byLabel = new Map();
  rows.forEach(({ label, box }, i) => {
    const want = runs[i] ? runs[i].session : undefined;
    if (want === undefined) {
      reasons.push(`run ${label}: the declaration partitions this window by session but names none for this run`);
      return;
    }
    const got = box && box.session && box.session.sessionStartedAt;
    if (!got) {
      reasons.push(`run ${label}: declared session ${JSON.stringify(want)}, but the record carries no session rider`);
      return;
    }
    if (!byLabel.has(want)) byLabel.set(want, new Map());
    const actual = byLabel.get(want);
    if (!actual.has(got)) actual.set(got, []);
    actual.get(got).push(label);
  });

  for (const [want, actual] of byLabel) {
    if (actual.size > 1) {
      const split = [...actual].map(([id, ls]) => `${id} (run ${ls.join(',')})`).join(' and ');
      reasons.push(`declared session ${JSON.stringify(want)} spans ${actual.size} actual sessions: ${split}`);
    }
  }
  const owner = new Map();
  for (const [want, actual] of byLabel) {
    for (const [id, ls] of actual) {
      if (owner.has(id) && owner.get(id) !== want) {
        reasons.push(
          `declared sessions ${JSON.stringify(owner.get(id))} and ${JSON.stringify(want)} are the SAME actual ` +
            `session ${id} (run ${ls.join(',')}), so the declared partition did not happen`
        );
      } else {
        owner.set(id, want);
      }
    }
  }
  return reasons;
}

// THE CORPUS, not one run of it. A window that declared eight runs and can show
// six has not taken the window it declared, and the audit of #8615's `--admit`
// exiting 0 on an EMPTY corpus is exactly that hole. There is no partial
// credit: the count is fixed before run 1 and a short corpus is refused.
//
// TWO CLAUSES LIVE HERE AND NOT IN `admissibleRun`, because neither is a
// property a single run can carry: the session partition is a relation BETWEEN
// runs, and the parameters-only escape below is a distinction between a fixture
// and a corpus.
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
  // AND THERE IS NO PARAMETERS-ONLY ESCAPE AT CORPUS LEVEL. `admissibleRun`
  // holds its realised-labelling clause off a record with no arm window at all,
  // because such a record is a fixture asserting nothing about a labelling. A
  // CORPUS is the thing a figure is read off, so here the escape is closed:
  // a corpus whose windows all failed to certify would otherwise satisfy every
  // declared parameter and every control and be read as zero blocks.
  for (const { label, row } of rows) {
    if (!carriesWindows(row)) {
      reasons.push(`run ${label}: the record carries no arm window, so no figure can be read off it`);
    }
  }
  reasons.push(...sessionPartition(rows, declared));
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

// The index of each schedule's complement inside the same enumeration. The
// admissible set is closed under complement and no balanced schedule is its own
// complement, so this is a fixed-point-free involution on `0..n−1`.
function complementIndex(schedules) {
  const at = new Map(schedules.map((f, i) => [flipKey(f), i]));
  return schedules.map((f) => at.get(flipKey(f.map((x) => !x))));
}

// --- THE DESIGN'S OWN ASSIGNMENT SUPPORT -------------------------------------
//
// A RUN'S SCHEDULE IS NOT DRAWN INDEPENDENTLY OF ITS SIBLINGS, and an earlier
// revision of this reference behaved as though it were: it drew each of the
// eight runs from that run's own 48 admissible schedules, a support of 48⁸.
// Phase 4 never drew eight schedules. `alloc_pass_design.cjs`'s rule draws
// TWO — the first admissible seed, then the first subsequent one drawing
// neither that schedule nor its complement — and the design then FORCES the
// other two as their exact complements, after which the pre-registration
// REPEATS the quadruple verbatim in session B. The whole eight-run labelling is
// a function of two free schedules, and 48⁸ admits assignments this design
// could not have produced: quadruples that are not complementary pairs, and
// second sessions that do not repeat the first.
//
// Both restrictions are load-bearing rather than incidental. THE COMPLEMENT
// PAIRING is what cancels every per-index nuisance out of the pooled pass
// contrast — the whole reason the design carries it — so a reference that
// breaks it ranks the observation against assignments in which a nuisance is
// free that in the observation was cancelled. THE CROSS-SESSION REPEAT is what
// makes the session the only thing differing between the two halves, which is
// the contrast this window took a second session to obtain.
//
// `assignmentRoles` READS THAT STRUCTURE OFF THE DECLARATION rather than
// assuming it: for each declared run, which free schedule it takes and whether
// it takes that schedule complemented. A declaration of eight unrelated
// schedules yields eight generators, and the old independent support falls out
// as that special case rather than being the general one.
function assignmentRoles(declaredRuns) {
  const generatorKeys = [];
  const roles = declaredRuns.map((d) => {
    const key = flipKey(d.flips);
    const anti = flipKey(d.flips.map((x) => !x));
    const own = generatorKeys.indexOf(key);
    if (own >= 0) return { generator: own, complement: false };
    const inverted = generatorKeys.indexOf(anti);
    if (inverted >= 0) return { generator: inverted, complement: true };
    generatorKeys.push(key);
    return { generator: generatorKeys.length - 1, complement: false };
  });
  return { roles, generatorKeys };
}

// The design a reference is taken over, read off the committed declaration.
const bandDesign = (declared) => ({
  ...assignmentRoles(declared.runs),
  rounds: declared.window.rounds,
});

// `S · (S−2) · … · (S−2(k−1))` — see `assignmentSupport`.
function supportSize(generators, n) {
  let size = 1;
  for (let j = 0; j < generators; j++) size *= n - 2 * j;
  return size;
}

// Every ordered tuple of admissible schedules the design could have drawn its
// free schedules as: distinct, and no two complementary. Each choice removes
// the schedule taken AND its complement, because a design that drew one
// schedule twice — or a schedule beside its own complement — is not the design
// that was declared, and its quadruple would not be four distinct runs.
//
// ORDERED, because the runs are not interchangeable: run 1 and run 2 carry
// different blocks, so handing them the two free schedules the other way round
// is a different assignment. At phase 4's `k = 2` over `S = 48` that is
// 48 × 46 = 2,208 — small enough to enumerate, so the reference is EXACT.
function assignmentSupport(generators, schedules) {
  const comp = complementIndex(schedules);
  const out = [];
  const walk = (tuple) => {
    if (tuple.length === generators) {
      out.push(tuple.slice());
      return;
    }
    const taken = new Set();
    for (const t of tuple) {
      taken.add(t);
      taken.add(comp[t]);
    }
    for (let i = 0; i < schedules.length; i++) {
      if (taken.has(i)) continue;
      tuple.push(i);
      walk(tuple);
      tuple.pop();
    }
  };
  walk([]);
  return out;
}

// One assignment drawn uniformly from that same support, for a design whose
// support is too large to enumerate. Each generator is drawn from the schedules
// not already taken and not the complement of one — the support's own
// definition — so the draw is uniform over it.
function drawAssignment(next, generators, schedules, comp) {
  const tuple = [];
  const taken = new Set();
  while (tuple.length < generators) {
    const pool = [];
    for (let i = 0; i < schedules.length; i++) if (!taken.has(i)) pool.push(i);
    const i = pool[Math.floor(next() * pool.length)];
    tuple.push(i);
    taken.add(i);
    taken.add(comp[i]);
  }
  return tuple;
}

// Where the support fits under this it is enumerated and the reference is
// exact; above it, the declared `draws` and `seed` sample from the SAME
// support. Phase 4's 2,208 is two orders below. A design drawing four free
// schedules would be 48 × 46 × 44 × 42 = 4,080,384 and would not be.
const EXACT_SUPPORT_LIMIT = 250000;

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
// The statistic is re-read over every assignment THE DESIGN COULD HAVE DRAWN,
// which is NOT every combination of per-run schedules — see `assignmentRoles`
// for what that distinction cost. At phase 4's two free schedules over 48
// admissible ones the support holds 48 × 46 = 2,208 assignments, so the
// reference is EXACT: `p` is a rank among all of them, nothing is sampled, and
// the declared `draws` and `seed` do the sampling they were declared for only
// where the support is too large to enumerate.
//
// IT IS EXACTLY SYMMETRIC ABOUT ZERO, by construction rather than by
// assumption. The support is closed under complementing every free schedule at
// once, that map complements every run's labelling, and a complemented
// labelling negates that run's term exactly — so each assignment's statistic is
// paired with its own negation. Two consequences a page reading this has to
// carry: a two-sided count is always EVEN, and the smallest attainable p is
// `2/|support|` rather than `1/|support|`.
//
// The sampled branch keeps the conservative convention it always had — the
// observation is counted, so the smallest attainable p there is `1/(draws+1)`.
//
// AND A REFERENCE THAT DOES NOT CONTAIN THE OBSERVATION IS NOT A REFERENCE FOR
// IT. An executed assignment whose free schedules are not themselves admissible
// is refused rather than ranked, and so is one the record does not reproduce.
function termReference(runs, { draws, seed, design }) {
  const schedules = admissibleSchedules(design.rounds);
  const comp = complementIndex(schedules);
  const at = new Map(schedules.map((f, i) => [flipKey(f), i]));
  const observedTuple = design.generatorKeys.map((k) => at.get(k));
  if (observedTuple.some((i) => i === undefined)) {
    return { refused: 'the executed assignment draws a schedule the design does not admit' };
  }

  // Each run's term under EVERY admissible re-labelling, computed once and read
  // by index from here on: the support is enumerated over schedule INDICES, so
  // no assignment re-derives a median.
  const terms = runs.map((r) => schedules.map((f) => passTerm(relabel(r.blocks, f))));
  const statistic = (tuple) => {
    let s = 0;
    let n = 0;
    for (let i = 0; i < design.roles.length; i++) {
      const g = tuple[design.roles[i].generator];
      const t = terms[i][design.roles[i].complement ? comp[g] : g];
      if (t !== null) { s += t; n++; }
    }
    return n ? s / n : 0;
  };

  const observed = meanRunTerm(runs);
  // AN ARM THAT CARRIES NO BLOCK HAS NO TERM, AND THAT IS A REFUSAL RATHER THAN
  // A PASS. The earlier revision let `observed` be `null`, compared every draw
  // against `Math.abs(null) = 0`, and returned p = 1 — so a corpus carrying no
  // R = 0 arm at all cleared the null-arm control vacuously and outcome 1 was
  // read with no negative control under it. Same class as the `--admit` that
  // exited 0 on an empty corpus.
  if (observed === null) {
    return { refused: 'the arm carries no block at all, so there is no term to rank' };
  }
  // AND THE OBSERVATION MUST BE A POINT OF ITS OWN REFERENCE. Re-labelling the
  // blocks under the schedules the declaration says were drawn has to reproduce
  // the measured term exactly; where it does not, the record and the
  // declaration disagree about what ran and no rank off either is meaningful.
  if (statistic(observedTuple) !== observed) {
    return { refused: 'the declared assignment does not reproduce the measured term' };
  }

  const generators = design.generatorKeys.length;
  const support = supportSize(generators, schedules.length);
  const exact = support > 0 && support <= EXACT_SUPPORT_LIMIT;
  let sample;
  if (exact) {
    sample = assignmentSupport(generators, schedules).map(statistic);
  } else {
    const next = rng(seed);
    sample = [];
    for (let d = 0; d < draws; d++) sample.push(statistic(drawAssignment(next, generators, schedules, comp)));
  }

  const atLeast = sample.filter((t) => Math.abs(t) >= Math.abs(observed)).length;
  const sorted = sample.map(Math.abs).sort((a, b) => a - b);
  return {
    observed,
    exact,
    support,
    generators,
    draws: exact ? sample.length : draws,
    seed: exact ? null : seed,
    // Per run, the exact rank among that run's own 48 re-labellings. A MARGINAL
    // statement and now labelled as one: it is what a single run's schedule
    // could have been holding its siblings nowhere, and the joint support above
    // is what the window is adjudicated on.
    perRun: runs.map((r, i) => {
      const t = passTerm(r.blocks);
      const ge = terms[i].filter((x) => Math.abs(x) >= Math.abs(t)).length;
      return { label: r.label, term: t, of: terms[i].length, p: ge / terms[i].length };
    }),
    p: exact ? atLeast / sample.length : (atLeast + 1) / (draws + 1),
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
    const design = bandDesign(declared);
    const runsOf = (rungs, stat) =>
      rows.map(({ label, row }) => ({ label, rounds: row.rounds, blocks: blocks(row, label, rungs, stat) }));
    const signal = termReference(runsOf(MID_RUNGS, 'ratio'), { draws, seed, design });
    const control = termReference(runsOf(NULL_RUNGS, 'delta'), { draws, seed, design });

    if (signal.refused || control.refused) {
      out.push(';;');
      out.push(';; THE BAND IS REFUSED AND NO p IS PRINTED. A rank is only a rank against a');
      out.push(';; reference the observation is a member of.');
      for (const r of [signal.refused, control.refused]) if (r) out.push(`;;   ${r}`);
      return { lines: out, refused: true };
    }

    out.push(';;');
    out.push(';; THE BAND — a restricted randomisation of the PASS LABELS over the assignments');
    out.push(';; THE DESIGN ITSELF could have drawn, which is not every combination of per-run');
    out.push(`;; schedules. ${signal.generators} free schedule(s) out of the ${admissibleSchedules(design.rounds).length} admissible at ${design.rounds} rounds (closed`);
    out.push(`;; under complement); the rest of the ${design.roles.length} runs are FORCED by the declaration's`);
    out.push(`;; complement pairing and its session repeat. ${signal.support} assignments in all, and the`);
    out.push(';; block VALUES are untouched.');
    if (signal.exact) {
      out.push(`;;   EXACT — every one of the ${signal.support} is enumerated, so nothing is sampled and the`);
      out.push(`;;   declared ${draws} draws from ${JSON.stringify(seed)} are not used. The support is closed under`);
      out.push(';;   global complement, so the reference is exactly symmetric about zero, every');
      out.push(`;;   two-sided count is even, and the smallest attainable p is 2/${signal.support}.`);
    } else {
      out.push(`;;   SAMPLED — the support is too large to enumerate, so ${draws} draws from the committed`);
      out.push(`;;   seed ${JSON.stringify(seed)}. The observation is counted, so the smallest attainable p is`);
      out.push(`;;   1/${draws + 1}.`);
    }
    out.push(`;;   alpha ${alpha}. Nothing here is a byte threshold.`);
    out.push(';;   arm | term | two-sided p | reference p95 | reference p97.5');
    for (const [name, ref, unit] of [['MID-RUNG (signal)', signal, '%'], ['R=0 NULL (control)', control, 'B/boundary']]) {
      const shown = unit === '%' ? pct(ref.observed) : `${ref.observed === null ? 'n/a' : ref.observed.toFixed(2)} B/bnd`;
      const p95 = unit === '%' ? pct(ref.p95) : `${ref.p95.toFixed(2)} B/bnd`;
      const p975 = unit === '%' ? pct(ref.p975) : `${ref.p975.toFixed(2)} B/bnd`;
      out.push(`;;   ${name} | ${shown} | ${ref.p.toFixed(6)} | ${p95} | ${p975}`);
    }
    out.push(';;');
    out.push(';;   PER RUN, MARGINAL — the rank of the run\'s own term among all its own');
    out.push(';;   re-labellings, holding its siblings nowhere. The verdict above is the JOINT');
    out.push(';;   rank over the design\'s assignments, and it is the one the outcome reads.');
    out.push(`;;     run | mid-rung term | marginal p (of ${signal.perRun[0] ? signal.perRun[0].of : 0}) | null term | marginal p`);
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
      out.push(`;;   construction, itself returns p = ${control.p.toFixed(6)} <= ${alpha} through the identical`);
      out.push(';;   pipeline. A band that fires on a known-zero population cannot adjudicate the');
      out.push(';;   mid rungs, and the pre-registration makes that a refusal rather than a footnote.');
    } else if (sigOk) {
      out.push(`;;   OUTCOME 1 — THE PASS TERM IS ESTABLISHED as a within-window term at alpha ${alpha}:`);
      out.push(`;;   p = ${signal.p.toFixed(6)}, with the null-arm control clear at p = ${control.p.toFixed(6)}.`);
    } else {
      out.push(`;;   OUTCOME 2 — THE PASS TERM IS NOT ESTABLISHED. p = ${signal.p.toFixed(6)} > ${alpha}; the term`);
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
    // ONE SESSION IS NOT A DISAGREEMENT. The earlier wording had no clause for
    // `sessions.size === 1`: it fell into the `agree === false` branch and
    // printed "THE SESSIONS DO NOT AGREE IN SIGN … With 1 sessions that is a
    // sign agreement on 1 blocks", which asserts the outcome of a comparison
    // that was never made. Re-reading phase 3's four runs took that branch. No
    // figure moves either way — what moves is whether the reader describes its
    // own evidence truthfully, and a reader that does not is worth less than
    // one that prints nothing.
    if (sessions.size === 1) {
      out.push(
        ';;     ONE SESSION — NO CROSS-SESSION COMPARISON IS POSSIBLE. A claim of this term' +
          ' is capped at a single session because there is no second session to agree or' +
          ' disagree with it, not because two were compared and differed.'
      );
    } else {
      out.push(
        `;;     ${agree ? 'THE SESSIONS AGREE IN SIGN' : 'THE SESSIONS DO NOT AGREE IN SIGN'}` +
          `, so a claim of this term is ${agree ? `carried by all ${sessions.size}` : 'capped at a single session'}.` +
          ` With ${sessions.size} sessions that is a sign agreement on ${sessions.size} blocks and nothing stronger.`
      );
    }
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
    // A record SHAPED AS THE DRIVER WRITES ONE rather than as the declaration
    // states it — `plan` is an object in a record and a name in a declaration,
    // and a fixture that spread the declaration verbatim would be testing the
    // boundary against a shape no run has.
    //
    // IT CARRIES CERTIFIED WINDOWS, AND THAT IS A CHANGE (rf2-flxxa). The
    // earlier fixture set every round's `arms` to `{}` on the principle that
    // admissibility is decided before a figure is read. That principle is
    // still true of the DECLARED clauses, and `alloc_pass_design.cjs` still
    // builds such a row for them — but it made the fixture structurally unable
    // to exercise the realised-labelling clause, because a record with no
    // window has no labelling to be short or unbalanced. A control that cannot
    // reach the fault it exists to catch is not a control.
    //
    // The values are chosen to be inert and are not a figure: every cell reads
    // `d_all = 1000 B/boundary`, and `page` reads 6 higher where `page` ran
    // first and 6 lower where it ran second. Every round's block is therefore
    // exactly ±0.60%, the sign follows the pass, and the pass term of every run
    // is +0.60% — which is what makes the two-session sign-agreement branch
    // below reachable at all.
    const w = declared.window;
    const armWindows = (flip) => {
      const a = {};
      const pageDelta = flip ? -24 : 24;
      for (const { segment, arm } of FAMILIES) {
        for (const rung of MID_RUNGS) {
          a[`${segment}|${arm}#${rung}@all`] = { certified: true, legMedian: 5000 };
          a[`${segment}|${arm}#${rung}@page`] = { certified: true, legMedian: 5000 + pageDelta };
        }
        // AND AN R = 0 ARM, reading the floor exactly under both legs so its
        // true term is zero the way the real null arm's is. It is here because
        // the band now REFUSES an arm carrying no block rather than returning
        // p = 1 for it: a fixture with no null arm would exercise the refusal
        // instead of the two-session verdict it is built for.
        for (const rung of NULL_RUNGS) {
          a[`${segment}|${arm}#${rung}@all`] = { certified: true, legMedian: 1000 };
          a[`${segment}|${arm}#${rung}@page`] = { certified: true, legMedian: 1000 };
        }
        a[`${segment}|${FLOOR_ARM}@all`] = { certified: true, legMedian: 1000 };
        a[`${segment}|${FLOOR_ARM}@page`] = { certified: true, legMedian: 1000 };
      }
      return a;
    };
    const mk = (i, decl = declared) => {
      const legs = decl.window.writeLegs;
      const flips = decl.runs[i].flips;
      const dw = decl.window;
      return {
        passOrder: dw.passOrder,
        passSeed: decl.runs[i].passSeed,
        rounds: dw.rounds,
        writePaired: dw.writePaired,
        writeLegs: legs.slice(),
        segOrder: dw.segOrder,
        controlSlot: dw.controlSlot,
        plan: { name: dw.plan, arms: true, rungs: true, fits: true },
        roots: dw.roots,
        boundaries: dw.boundaries,
        writes: dw.writes,
        passSchedule: { flips: flips.slice(), attempts: 1, parityTied: false },
        controlVerdict: { ok: true, perDouble: 8.08, differential: 8 },
        verification: { unverified: 0 },
        perRound: flips.map((f, r) => ({
          round: r,
          writeLegs: f ? [...legs].reverse() : legs.slice(),
          arms: armWindows(f),
        })),
      };
    };
    // THE SESSION RIDER IS PART OF THE FIXTURE NOW, because the partition is a
    // declared parameter like any other. `session-A`/`session-B` stand in for
    // the real `box.session.sessionStartedAt` timestamps.
    const corpus = (decl = declared, ids = null) =>
      decl.runs.map((d, i) => ({
        label: String(i + 1),
        row: mk(i, decl),
        box: { session: { sessionStartedAt: ids ? ids[i] : `session-${d.session}` } },
      }));
    const good = corpus();
    const base = admissibleCorpus(good, declared);
    assert.strictEqual(base.ok, true, `the declared corpus is admitted: ${base.reasons.join('; ')}`);
    // AND THE FIXTURE REALLY DOES CARRY THE LABELLING IT CLAIMS TO. Without
    // this the realised clauses below would be refusing a record that was
    // already short, and every one of them would pass for the wrong reason.
    const realBase = realisedLabelling(good[0].row, w.rounds);
    assert.strictEqual(realBase.n, w.rounds, 'the fixture realises every scheduled round');
    assert.strictEqual(realBase.parity, 0, 'and its realised q·parity is 0');
    assert.strictEqual(realBase.linear, 0, 'and its realised q·linear is 0');

    // Each entry may name the reason it must be refused ON. `ok === false` is
    // not enough for a clause added to a boundary that already had nineteen:
    // any of them could be doing the refusing, and a control satisfied by
    // somebody else's clause proves nothing about its own.
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
      // AND THE REALISED LABELLING, WHICH THE DRAW CANNOT SPEAK FOR (rf2-flxxa).
      // Every declared parameter is untouched in all three, the drive still
      // covers every scheduled round exactly once, and the drawn schedule is
      // still balanced with `q·parity = 0` and `q·linear = 0`. What moved is
      // only which rounds CARRIED DATA — which is why nothing but the realised
      // clause can refuse them, and why the reason is pinned.
      [
        'one round\'s mid-rung cells stripped of certification',
        (r) => {
          const round = r.perRound[0];
          for (const k of Object.keys(round.arms)) round.arms[k] = { ...round.arms[k], certified: false };
        },
        /the realised block set covers 11 round\(s\) \(11 distinct\), not the scheduled 12/,
      ],
      [
        'the floor windows of one round stripped of certification',
        (r) => {
          const round = r.perRound[1];
          for (const k of Object.keys(round.arms)) {
            if (k.includes(FLOOR_ARM)) round.arms[k] = { ...round.arms[k], certified: false };
          }
        },
        /the realised block set covers 11 round\(s\)/,
      ],
      [
        'EVERY window in the run stripped of certification',
        (r) => {
          for (const round of r.perRound) {
            for (const k of Object.keys(round.arms)) round.arms[k] = { ...round.arms[k], certified: false };
          }
        },
        /the realised block set is empty/,
      ],
    ];
    for (const [name, breakIt, wantReason] of breaks) {
      const rows = corpus();
      breakIt(rows[0].row);
      const got = admissibleCorpus(rows, declared);
      assert.strictEqual(got.ok, false, `breaking ${name} must refuse the corpus`);
      if (wantReason) {
        assert.ok(
          got.reasons.some((r) => wantReason.test(r)),
          `breaking ${name} must be refused ON its own clause, got: ${got.reasons.join('; ')}`
        );
      }
    }

    // THE REALISED BALANCE IS ITS OWN CLAUSE, and it is not the completeness
    // one wearing a different name. Drop the SAME NUMBER of rounds from both
    // parity classes and the block set is short but still balanced; drop them
    // all from one class and it is short AND unbalanced. Only the second can
    // read `q·parity` non-zero, and pinning it is what proves the balance is
    // being computed on the realised labelling rather than copied off the draw.
    {
      const uncertify = (r, roundIdx) => {
        const round = r.perRound[roundIdx];
        for (const k of Object.keys(round.arms)) round.arms[k] = { ...round.arms[k], certified: false };
      };
      const rows = corpus();
      // Rounds 0 and 3 of run 1's declared schedule: `page`-first on an even
      // round and `all`-first on an odd one, so removing both leaves q·parity
      // where it was and only the count moves.
      uncertify(rows[0].row, 0);
      uncertify(rows[0].row, 3);
      const balanced = realisedLabelling(rows[0].row, w.rounds);
      assert.strictEqual(balanced.n, 10, 'two rounds dropped leaves ten blocks');
      assert.strictEqual(balanced.parity, 0, 'and this pair leaves the realised q·parity at 0');
      const one = corpus();
      uncertify(one[0].row, 0);
      const skewed = realisedLabelling(one[0].row, w.rounds);
      assert.strictEqual(skewed.n, 11, 'one round dropped leaves eleven blocks');
      assert.notStrictEqual(skewed.parity, 0, 'and moves the realised q·parity off 0');
      const got = admissibleCorpus(one, declared);
      assert.ok(
        got.reasons.some((r) => /realised q·parity/.test(r)),
        `an unbalanced realised labelling is refused on its balance, got: ${got.reasons.join('; ')}`
      );
    }

    // A CORPUS OF PARAMETERS-ONLY RECORDS IS REFUSED, and this is the shape
    // `admissibleRun` deliberately lets through. It satisfies every declared
    // clause and every control, realises nothing, and would be read as zero
    // blocks. The two levels disagreeing here is the design, not an oversight.
    {
      const bare = corpus();
      for (const { row } of bare) for (const round of row.perRound) round.arms = {};
      assert.strictEqual(
        admissibleRun(bare[0].row, { ...w, ...declared.runs[0] }).ok, true,
        'a parameters-only record is admissible as a RUN — the design control builds exactly this'
      );
      const got = admissibleCorpus(bare, declared);
      assert.strictEqual(got.ok, false, 'but a CORPUS of them is refused');
      assert.ok(
        got.reasons.some((r) => /carries no arm window/.test(r)),
        `and refused on that, got: ${got.reasons.join('; ')}`
      );
    }

    // THE DECLARED SESSION PARTITION, IN BOTH DIRECTIONS (audit of PR #8634).
    // The declaration puts runs 1–4 in session A and 5–8 in session B. Nothing
    // below touches a single other field: only which actual session each record
    // says it was taken in moves, so nothing but `sessionPartition` can refuse
    // any of them, and each reason is pinned.
    {
      const ids = declared.runs.map((d) => `session-${d.session}`);
      assert.strictEqual(admissibleCorpus(corpus(declared, ids), declared).ok, true,
        'the declared partition, honoured, is admitted');

      // COLLAPSED — the reproduction on the bead: every run in one session.
      const collapsed = admissibleCorpus(corpus(declared, ids.map(() => 'one-session')), declared);
      assert.strictEqual(collapsed.ok, false, 'a COLLAPSED session partition is refused');
      assert.ok(collapsed.reasons.some((r) => /are the SAME actual session/.test(r)),
        `and refused on the collapse, got: ${collapsed.reasons.join('; ')}`);

      // MISPARTITIONED — run 4 was declared into A and taken in B.
      const crossed = ids.slice();
      crossed[3] = ids[4];
      const mis = admissibleCorpus(corpus(declared, crossed), declared);
      assert.strictEqual(mis.ok, false, 'a MISPARTITIONED corpus is refused');
      assert.ok(mis.reasons.some((r) => /spans 2 actual sessions/.test(r)),
        `and refused on the split group, got: ${mis.reasons.join('; ')}`);

      // AND A RECORD THAT NEVER SAID. `report` would give it a session of its
      // own and the collapse test would pass over it.
      const silent = corpus(declared, ids);
      silent[0].box = {};
      const none = admissibleCorpus(silent, declared);
      assert.strictEqual(none.ok, false, 'a run with no session rider is refused');
      assert.ok(none.reasons.some((r) => /carries no session rider/.test(r)),
        `and refused on that, got: ${none.reasons.join('; ')}`);

      // AND THE CLAUSE DOES NOT SIMPLY REFUSE EVERY PARTITION. Phase 3's
      // re-adjudication declares all four runs into ONE session, which is a
      // legitimate declaration and must be admitted when the records agree.
      const p3Path = path.join(__dirname, 'data', 'alloc-legorder', 'phase3-re-adjudication.json');
      assert.ok(fs.existsSync(p3Path), `the phase-3 declaration must be committed at ${p3Path}`);
      const p3 = JSON.parse(fs.readFileSync(p3Path, 'utf8'));
      assert.strictEqual(admissibleCorpus(corpus(p3), p3).ok, true,
        'a single-session declaration whose records agree is admitted');
      const p3Split = admissibleCorpus(corpus(p3, ['s1', 's1', 's1', 's2']), p3);
      assert.strictEqual(p3Split.ok, false, 'and one whose records do not is refused');
      assert.ok(p3Split.reasons.some((r) => /spans 2 actual sessions/.test(r)),
        `on the split group, got: ${p3Split.reasons.join('; ')}`);
    }

    // AND A SHORT CORPUS IS REFUSED ON ITS COUNT, which is the `--admit`
    // exiting 0 on an empty corpus that the audit found.
    assert.strictEqual(admissibleCorpus([], declared).ok, false, 'an empty corpus is refused');
    assert.strictEqual(admissibleCorpus(good.slice(0, 2), declared).ok, false, 'a short corpus is refused');

    // AND A SEEDED CORPUS READ WITHOUT ITS DECLARATION PRINTS NO FIGURE.
    const undeclared = report([{ label: '1', row: mk(0) }], null);
    assert.strictEqual(undeclared.refused, true, 'a seeded corpus with no declaration is refused');
    assert.ok(!undeclared.lines.some((l) => l.includes('THE ROUND BLOCKS')), 'and prints no blocks');

    // --- WHAT THE READER SAYS ABOUT ITS OWN SESSIONS, BOTH BRANCHES ----------
    //
    // The session verdict is the LAST line the band block prints, and it had no
    // clause for a one-session corpus: it fell into `agree === false` and
    // asserted that sessions which were never compared did not agree. Both
    // branches are pinned here, the two-session one VERBATIM as published, so
    // repairing the single-session wording cannot quietly move the other.
    //
    // `draws` is cut to 200 because the branch under test is a string and the
    // reference distribution is not what is being pinned. Nothing else in the
    // declaration is touched.
    {
      const cheap = (d) => ({ ...d, band: { ...d.band, draws: 200 } });
      const verdict = (lines) => lines[lines.length - 1];

      const two = report(corpus(), cheap(declared));
      assert.strictEqual(two.refused, false, 'the two-session fixture is read');
      assert.ok(two.lines.some((l) => l.includes('BY SESSION — 2 session(s)')), 'and reads two sessions');
      assert.strictEqual(
        verdict(two.lines),
        ';;     THE SESSIONS AGREE IN SIGN, so a claim of this term is carried by all 2.'
          + ' With 2 sessions that is a sign agreement on 2 blocks and nothing stronger.',
        'the two-session wording is unchanged, to the byte'
      );

      const p3 = JSON.parse(fs.readFileSync(path.join(__dirname, 'data', 'alloc-legorder', 'phase3-re-adjudication.json'), 'utf8'));
      const one = report(corpus(p3), cheap(p3));
      assert.strictEqual(one.refused, false, 'the one-session fixture is read');
      assert.ok(one.lines.some((l) => l.includes('BY SESSION — 1 session(s)')), 'and reads one session');
      assert.strictEqual(
        verdict(one.lines),
        ';;     ONE SESSION — NO CROSS-SESSION COMPARISON IS POSSIBLE. A claim of this term'
          + ' is capped at a single session because there is no second session to agree or'
          + ' disagree with it, not because two were compared and differed.',
        'and a one-session corpus says so instead of claiming a disagreement'
      );
      assert.ok(!/AGREE IN SIGN/.test(verdict(one.lines)), 'it claims no agreement');
      assert.ok(!/DO NOT AGREE/.test(verdict(one.lines)), 'and asserts no disagreement');
    }
  }

  // --- THE REALISED CLAUSE ON A REAL RECORD -----------------------------------
  //
  // The fixtures above are synthetic, and a clause that only ever meets its own
  // fixture has never been shown to survive contact with a record the rig
  // wrote. This drives the same clause over phase 4's committed run 1: intact
  // it realises all twelve rounds and is admitted; with round 0's windows
  // stripped of certification it realises eleven, `q·parity` moves off zero,
  // and it is refused. Nothing else about the record moves.
  {
    const dir4 = path.join(__dirname, 'data', 'alloc-legorder');
    const declared = JSON.parse(fs.readFileSync(path.join(dir4, 'pre-registration.json'), 'utf8'));
    const runPath = path.join(dir4, 'run1.json');
    assert.ok(fs.existsSync(runPath), `phase 4's run 1 must be committed at ${runPath}`);
    const want = { ...declared.window, ...declared.runs[0] };
    const load = () => JSON.parse(fs.readFileSync(runPath, 'utf8')).alloc;

    const intact = load();
    const realIntact = realisedLabelling(intact, declared.window.rounds);
    assert.strictEqual(realIntact.n, 12, 'phase 4 run 1 realised all twelve rounds');
    assert.strictEqual(realIntact.parity, 0, 'with realised q·parity 0');
    assert.strictEqual(realIntact.linear, 0, 'and realised q·linear 0');
    assert.strictEqual(admissibleRun(intact, want).ok, true, 'and is admitted');

    const stripped = load();
    const round0 = stripped.perRound.find((r) => r.round === 0);
    for (const k of Object.keys(round0.arms)) {
      if (MID_RUNGS.some((rung) => k.includes(`#${rung}@`)) || k.includes(FLOOR_ARM)) {
        round0.arms[k] = { ...round0.arms[k], certified: false };
      }
    }
    const realStripped = realisedLabelling(stripped, declared.window.rounds);
    assert.strictEqual(realStripped.n, 11, 'stripping round 0 leaves eleven realised blocks');
    assert.notStrictEqual(realStripped.parity, 0, 'and an unbalanced realised labelling');
    // The DRAW is untouched, which is the whole point: the old boundary read it
    // and admitted this record.
    assert.strictEqual(parityDot(stripped.passSchedule.flips), 0, 'the DRAWN q·parity is still 0');
    assert.strictEqual(scheduleDrove(stripped), true, 'and the drive still matches the draw');
    const got = admissibleRun(stripped, want);
    assert.strictEqual(got.ok, false, 'yet the run is refused');
    assert.ok(
      got.reasons.every((r) => /^the realised block set|^realised q·/.test(r)),
      `and ONLY on the realised clauses, got: ${got.reasons.join('; ')}`
    );
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

  // --- THE JOINT ASSIGNMENT SUPPORT, WHICH IS THE DESIGN'S AND NOT EACH RUN'S -
  //
  // `rf2-t4vu1`'s repair, pinned on the SUPPORT rather than only on each run's
  // 48 marginal schedules — which is exactly what the earlier pins reached and
  // is why an eight-fold independent draw sat under them unnoticed.
  const design = bandDesign(JSON.parse(fs.readFileSync(declPath, 'utf8')));
  assert.strictEqual(design.generatorKeys.length, 2, 'phase 4 draws exactly TWO free schedules');
  assert.deepStrictEqual(
    design.roles.map((r) => `${r.generator}${r.complement ? '~' : ''}`),
    ['0', '1', '0~', '1~', '0', '1', '0~', '1~'],
    'and the declared eight runs are [A, B, ~A, ~B] repeated verbatim in session B'
  );

  // THE SIZE IS ARITHMETIC AND IS DERIVABLE WITHOUT RUNNING ANYTHING: 48 ways to
  // draw A; B must be admissible, not A and not ~A, and the set is closed under
  // complement with no schedule its own complement, so exactly two are removed
  // and 46 remain. 48 × 46 = 2,208, ORDERED because run 1 and run 2 carry
  // different blocks.
  const support = assignmentSupport(2, sched12);
  assert.strictEqual(supportSize(2, 48), 2208, 'the closed form is 48 × 46');
  assert.strictEqual(support.length, 2208, 'and the enumeration returns exactly that many');
  assert.strictEqual(new Set(support.map((t) => t.join(','))).size, 2208, 'with no repeats');

  // AND IT REFUSES IN BOTH DIRECTIONS, which is what makes the count evidence
  // rather than a number that happened to come out right. Dropping the
  // exclusion would give 48 × 48 = 2,304 — 96 more — so the two degenerate
  // shapes are named and each is checked for absence.
  {
    const compIdx = complementIndex(sched12);
    const inSupport = new Set(support.map((t) => t.join(',')));
    assert.ok(inSupport.has(`0,${1 === compIdx[0] ? 2 : 1}`), 'a distinct non-complementary pair IS in the support');
    assert.ok(!inSupport.has('0,0'), 'a design that drew one schedule twice is not this design');
    assert.ok(!inSupport.has(`0,${compIdx[0]}`), 'nor is one that drew a schedule beside its own complement');
    assert.strictEqual(2304 - support.length, 96, 'and the exclusion removes exactly the 96 such pairs');
  }

  const synth = (flips, label, { pass = 0, parity = 0 }) =>
    flips.map((f, r) => ({
      run: label, round: r, first: f ? 'all' : 'page', n: 1,
      m: pass * (f ? -1 : 1) + parity * (r % 2 === 0 ? 1 : -1) + 0.0001 * r,
      secondMinusFirst: 0, evenRound: r % 2 === 0,
    }));
  const corpus = (model) =>
    declaredRuns.map((d, i) => ({ label: String(i + 1), rounds: 12, blocks: synth(d.flips, String(i + 1), model) }));
  const band = (model) => termReference(corpus(model), { draws: 20000, seed: 'self-test', design });
  assert.strictEqual(band({}).exact, true, 'the reference over this design is enumerated, not sampled');
  assert.strictEqual(band({}).support, 2208, 'over all 2,208 assignments');

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

  // THE REFERENCE IS EXACT, SO THE DECLARED SEED IS INERT — and that is the
  // property to pin now, where before it was the opposite one. An exhaustive
  // enumeration of 2,208 assignments is a function of the corpus and the design
  // alone; a reader that still varied with the seed would be sampling
  // something. The declaration's `draws` and `seed` are kept and reported
  // unused rather than deleted: they are what the sampled branch needs, and a
  // pre-registration is not amended after its runs.
  const ref = (s, d) => termReference(corpus({ pass: 0.004 }), { draws: d, seed: s, design });
  assert.strictEqual(JSON.stringify(ref('k', 500)), JSON.stringify(ref('k', 500)), 'the reference is deterministic');
  assert.strictEqual(
    JSON.stringify(ref('k', 500)),
    JSON.stringify(ref('other', 7)),
    'and neither the seed nor the draw count moves it, because nothing is sampled'
  );

  // --- THE JOINT SUPPORT IS WHAT IS BEING RANKED, AND HERE IS THE PROOF -------
  //
  // EIGHT COPIES OF ONE RUN READ EXACTLY ZERO AT EVERY ONE OF THE 2,208
  // ASSIGNMENTS, and that is arithmetic rather than a measurement. Complementing
  // a labelling negates that run's term, and the design hands each free schedule
  // to two runs as itself and to two more complemented; with identical runs the
  // four terms cancel in pairs and the mean is exactly 0 whatever A and B are.
  //
  // IT IS THE CONTROL THAT DISCRIMINATES THE TWO SUPPORTS. Under eight
  // independent per-run draws the same corpus has a real spread — the pairing is
  // what collapses it — so a reference that had gone back to drawing each run
  // separately would fail here and nowhere else in this block.
  {
    const compIdx = complementIndex(sched12);
    const one = corpus({ pass: 0.006 })[0].blocks;
    const t = sched12.map((f) => passTerm(relabel(one, f)));
    let worst = 0;
    for (const tuple of support) {
      let s = 0;
      for (const role of design.roles) {
        const g = tuple[role.generator];
        s += t[role.complement ? compIdx[g] : g];
      }
      worst = Math.max(worst, Math.abs(s / design.roles.length));
    }
    assert.ok(worst < 1e-15,
      `eight identical runs read exactly 0 at every one of the ${support.length} assignments, got ${worst}`);
    // AND THE DEGENERACY IS THE PAIRING'S, NOT FLAT DATA'S. The same run's own
    // 48 marginal terms have a real spread — an assignment handing every run
    // one schedule, which is what an eight-fold independent draw permits and
    // this design does not, reads that schedule's own term.
    assert.ok(Math.max(...t.map(Math.abs)) > 1e-6,
      'the run itself carries a real term, so the cancellation is the design and not the data');
  }

  // THE SUPPORT IS CLOSED UNDER GLOBAL COMPLEMENT, so the reference is exactly
  // symmetric about zero and a two-sided count is always EVEN. That is why the
  // smallest attainable p is 2/2208 and not 1/2208 — a floor a page quoting this
  // band has to quote correctly, and one no amount of sampling would have shown.
  {
    const compIdx = complementIndex(sched12);
    for (const [a, b] of support.slice(0, 64)) {
      assert.ok(
        support.some(([x, y]) => x === compIdx[a] && y === compIdx[b]),
        'every assignment (A, B) is in the support beside (~A, ~B)'
      );
    }
    const counted = (r) => Math.round(r.p * r.support);
    for (const r of [band({ pass: 0.006 }), band({}), band({ parity: 1 }), band({ pass: 0.006, parity: 1 })]) {
      assert.strictEqual(counted(r) % 2, 0, `a two-sided count over a symmetric support is even, got ${counted(r)}`);
    }
  }

  // AND A REFERENCE THE OBSERVATION IS NOT A MEMBER OF IS REFUSED, NOT RANKED.
  {
    const bogus = { ...design, generatorKeys: [flipKey(Array.from({ length: 12 }, () => false)), design.generatorKeys[1]] };
    const got = termReference(corpus({ pass: 0.006 }), { draws: 500, seed: 'k', design: bogus });
    assert.ok(/does not admit/.test(got.refused || ''), `an inadmissible free schedule is refused, got: ${JSON.stringify(got.refused)}`);
    const crossed = { ...design, generatorKeys: [design.generatorKeys[1], design.generatorKeys[0]] };
    const swap = termReference(corpus({ pass: 0.006 }), { draws: 500, seed: 'k', design: crossed });
    assert.ok(/does not reproduce/.test(swap.refused || ''),
      `an assignment the record does not reproduce is refused, got: ${JSON.stringify(swap.refused)}`);
  }

  console.log(`[alloc-pass-position] self-test OK — 12 published blocks, both decompositions, `
    + `the parity tie, the null arm's 38 cells, the floor-free estimator's 3 of 6, `
    + `${sched12.length} admissible schedules, the design's own ${support.length}-assignment support in both `
    + `directions, the identical-run degeneracy that only the paired support produces, `
    + `the band's four synthetic controls, the `
    + `fail-closed boundary in both directions, the realised labelling on both a fixture and `
    + `phase 4's own run 1, the declared session partition collapsed and mispartitioned, and `
    + `both branches of the session verdict all reproduce`);
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
  realisedLabelling,
  carriesWindows,
  sessionPartition,
  relabel,
  passTerm,
  meanRunTerm,
  complementIndex,
  assignmentRoles,
  assignmentSupport,
  supportSize,
  bandDesign,
  EXACT_SUPPORT_LIMIT,
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
