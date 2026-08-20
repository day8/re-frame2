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
// ## WHAT THIS IS NOT
//
// It is not a gate. No run passes or fails on it, no threshold here is a
// budget, and nothing here reads, moves or is calibrated against tau in either
// direction. It is a reader over records that already exist, exactly as
// `alloc_position_confound.cjs` and `alloc_level_witness.cjs` are.

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
function blocks(row, label) {
  const B = row.boundaries;
  const out = [];
  for (const round of row.perRound || []) {
    // A cell with no ratio carries no block statistic — see `roundCells`. At
    // the mid rungs `d_all` is thousands of bytes per boundary and this drops
    // nothing, which the self-test's per-block `n` is what proves.
    const cells = roundCells(round, B, MID_RUNGS).filter((c) => c.ratio !== null);
    if (!cells.length) continue;
    const first = (round.writeLegs || [])[0] || null;
    const m = median(cells.map((c) => c.ratio));
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

function scheduleDrove(row) {
  const sched = row.passSchedule;
  const legs = row.writeLegs || [];
  if (!sched || !Array.isArray(sched.flips) || legs.length !== 2) return null;
  for (const round of row.perRound || []) {
    const flip = sched.flips[round.round];
    if (flip === undefined) return null;
    const want = flip ? [...legs].reverse() : legs;
    const got = round.writeLegs || [];
    if (got.length !== want.length || got.some((l, i) => l !== want[i])) return false;
  }
  return true;
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

function report(rows) {
  const out = [];
  const all = [];

  out.push(';; THE PASS-POSITION TERM, AND WHETHER IT IS THE PASS OR THE PARITY (rf2-fk6pj)');
  out.push(';;');
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

  return out;
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

  console.log(`[alloc-pass-position] self-test OK — 12 published blocks, both decompositions, `
    + `the parity tie, the null arm's 38 cells, and the floor-free estimator's 3 of 6 all reproduce`);
}

if (require.main === module) {
  const args = process.argv.slice(2);
  if (args[0] === '--self-test') {
    selfTest();
    process.exit(0);
  }
  if (!args.length) {
    console.error('usage: alloc_pass_position.cjs <dataset.json>... | --self-test');
    process.exit(2);
  }
  const rows = args.map((f, i) => ({
    label: String(i + 1),
    row: JSON.parse(fs.readFileSync(f, 'utf8')).alloc,
  }));
  for (const line of report(rows)) console.log(line);
}

module.exports = {
  MID_RUNGS,
  FAMILIES,
  FLOOR_ARM,
  NULL_RUNG,
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
