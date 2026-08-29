'use strict';
// THE DESIGN CONTROL FOR rf2-fk6pj's PASS-POSITION WINDOW — does the schedule
// this window is about to run let the estimator it is about to be read on
// SEPARATE the pass column from the parity column at all?
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --controls
//     node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --select [rounds] [runs]
//     node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --schedule <rounds> <seed>
//
// ## WHY THIS EXISTS, AND IT IS NOT A REFINEMENT
//
// `rf2-fk6pj`'s phase-2 window (PR #8601, record `the pass order drawn
// independently of parity`) argued its two seeds were admissible because the
// parity indicator and the pass indicator are ORTHOGONAL over the pooled
// twelve blocks. That is correct arithmetic on the indicator COLUMNS. It does
// not deliver what it was used for, because THE BLOCK STATISTIC IS A MEDIAN
// AND A MEDIAN IS NOT A LINEAR FUNCTIONAL: orthogonal design columns do not
// give independent median contrasts.
//
// The merged-PR audit of #8601 proved the gap by construction rather than by
// argument. On that window's run-2 schedule — `page, page, all, page, all,
// all` over six rounds, symmetric difference 4 from parity, the very schedule
// its successor was told to use for BOTH runs — feed the shipped `decompose` a
// corpus with a PURE PARITY effect and no pass effect at all, and it reports
// `PASS = -1`. Feed it a PURE PASS effect and it reports `PARITY = -1`. Two
// marginal median contrasts that merely DIFFER are not two separated terms.
//
// So this reader is the thing that has to run BEFORE a window is taken, and
// its verdict is what makes the window worth its wall clock. It DRIVES THE
// SHIPPED `decompose` — the same function `alloc_pass_position.cjs` reads the
// real corpus with, imported and not reimplemented — over synthetic block sets
// whose true terms are known by construction, and requires it to recover them.
//
// ## WHAT MAKES A SCHEDULE ADMISSIBLE, AND WHY THESE THREE
//
// Over `R` rounds write `q(r) = +1` where `page` ran first, `p(r) = +1` on an
// even round, and `l(r) = r − (R−1)/2` for the centred round index.
//
//   1. NOT PARITY-TIED. `p0_run.cjs`'s own draw enforces this and redraws; it
//      is restated here so a schedule can be judged without a run.
//   2. `q · p = 0` WITHIN THE RUN. Not pooled — pooled is what failed. At an
//      `R` divisible by 4 this is exactly the 2 x 2 balance: each pass group
//      holds `R/4` even rounds and `R/4` odd ones, so a pure parity effect
//      enters both pass groups symmetrically and the median of each group sits
//      at the level. That is what makes the median contrast identify, and it is
//      why the round count moved to twelve rather than the count alone.
//   3. `q · l = 0` WITHIN THE RUN, equivalently the `page`-first rounds and the
//      `all`-first rounds have equal index sums. Elapsed session time is a
//      MEASURED term on this instrument (`the mode rate tracks elapsed session
//      time`), so a linear within-run drift is a named nuisance rather than a
//      hypothetical, and a design that let it load onto the pass contrast would
//      be repeating this bead's own mistake one column over.
//
// ## WHAT IT DOES NOT BALANCE, NAMED RATHER THAN CONSTRAINED AWAY
//
// Two columns are balanced and every other function of the round index is
// residual. A schedule may still be a function of `r mod 4`, and one of the
// four selected below is: any nuisance of exactly that shape would be
// confounded with the pass column inside that run. It cancels only in the pool
// of that run with its complement. Adding a fourth criterion to filter it out
// would be choosing schedules against a nuisance nobody has measured, so it is
// reported instead — `--select` prints the residual for every run it picks.
//
// ## WHAT THIS IS NOT
//
// It is not a gate: no run passes or fails on it, and nothing here is a byte
// threshold, a budget, or read against tau in either direction. It is a
// property of a SCHEDULE and an ESTIMATOR, computable from a seed and a round
// count with no build, no server and no Chromium — which is the whole reason
// it can be run, and published, before the window costs anything.

const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert');
const { allocPassFlips } = require('../p0_run.cjs');
const {
  decompose,
  separation,
  admissibleRun: readerAdmissibleRun,
  assignmentRoles,
  assignmentSupport,
  admissibleSchedules,
  supportSize,
} = require('./alloc_pass_position.cjs');

// The window this file was written for. Both are re-derivable from the record
// and neither is a threshold: the round count is what buys criterion 2 an exact
// 2 x 2 balance, and the run count is what the phase-2 window said it owed.
const ROUNDS = 12;
const RUNS = 4;
// Seeds are enumerated in integer order under this prefix, so the selection is
// a rule rather than a choice. See `selectSeeds`.
const SEED_PREFIX = 'fk6pj-';
const SEED_SEARCH_LIMIT = 6000;

// --- the schedule, as a design matrix ---------------------------------------

// `flips[r]` is TRUE where the round drove its legs REVERSED, and the base
// order is `[page, all]` — so `page` ran first exactly where the flip is false.
// Read from `p0_run.cjs`'s `allocPassOrder`, never assumed: a reader that had
// the polarity backwards would report every sign inverted and nothing else.
const passIndicator = (flips) => flips.map((f) => (f ? -1 : 1));
const parityIndicator = (rounds) => Array.from({ length: rounds }, (_, r) => (r % 2 === 0 ? 1 : -1));
const linearIndicator = (rounds) =>
  Array.from({ length: rounds }, (_, r) => r - (rounds - 1) / 2);
// The residual this design does not balance: `+1` on `r mod 4` in {0, 3}. Named
// in the header; reported, not filtered on.
const mod4Indicator = (rounds) =>
  Array.from({ length: rounds }, (_, r) => (r % 4 === 0 || r % 4 === 3 ? 1 : -1));

const dot = (a, b) => a.reduce((s, x, i) => s + x * b[i], 0);

function scheduleProps(flips) {
  const rounds = flips.length;
  const q = passIndicator(flips);
  const pageFirst = [];
  for (let r = 0; r < rounds; r++) if (!flips[r]) pageFirst.push(r);
  const evenPageFirst = pageFirst.filter((r) => r % 2 === 0).length;
  return {
    rounds,
    key: flips.map((f) => (f ? '1' : '0')).join(''),
    pageFirst,
    pageFirstCount: pageFirst.length,
    evenPageFirst,
    oddPageFirst: pageFirst.length - evenPageFirst,
    parityDot: dot(q, parityIndicator(rounds)),
    linearDot: dot(q, linearIndicator(rounds)),
    mod4Dot: dot(q, mod4Indicator(rounds)),
  };
}

// The three criteria, as one predicate. `parityTied` comes from the draw
// itself, so a schedule that was never drawn is judged on the two it can be.
function admissible(schedule) {
  if (schedule.parityTied === true) return false;
  const p = scheduleProps(schedule.flips);
  return p.parityDot === 0 && p.linearDot === 0;
}

// --- the selection rule, which is a rule and not a choice --------------------
//
// Enumerate `<prefix>1`, `<prefix>2`, ... in integer order and keep the
// admissible draws. Run 1 takes the FIRST. Run 2 takes the first subsequent
// admissible seed drawing neither run 1's schedule nor its complement. Runs 3
// and 4 take the LOWEST-INDEXED seeds drawing the exact complements of runs 1
// and 2.
//
// THE COMPLEMENTS ARE THE POINT OF THE LAST TWO. Complementing a schedule
// negates `q`, so it preserves every criterion above while inverting the pass
// order at EVERY ROUND INDEX. Pooled over a complementary pair the pass
// contrast is therefore balanced against the round index itself rather than
// merely against its parity and its trend — any per-index term, and any
// residual the design did not name, cancels out of the pooled pass contrast.
function selectSeeds(rounds = ROUNDS, runs = RUNS, prefix = SEED_PREFIX, limit = SEED_SEARCH_LIMIT) {
  const found = [];
  const byKey = new Map();
  for (let i = 1; i <= limit; i++) {
    const seed = `${prefix}${i}`;
    const drawn = allocPassFlips(rounds, seed);
    if (!admissible(drawn)) continue;
    const props = scheduleProps(drawn.flips);
    const entry = { seed, flips: drawn.flips, attempts: drawn.attempts, ...props };
    found.push(entry);
    if (!byKey.has(props.key)) byKey.set(props.key, entry);
  }
  if (!found.length) return [];
  const complement = (key) => key.split('').map((c) => (c === '1' ? '0' : '1')).join('');
  const picked = [found[0]];
  const second = found.find((e) => e.key !== picked[0].key && e.key !== complement(picked[0].key));
  if (second) picked.push(second);
  for (const base of picked.slice(0, 2)) {
    const c = byKey.get(complement(base.key));
    if (c) picked.push(c);
  }
  // Runs 1 and 2 are the two independent schedules; 3 and 4 are their
  // complements, in that order.
  const ordered = [picked[0], picked[1], picked[2], picked[3]].filter(Boolean);
  return ordered.slice(0, runs);
}

// --- the synthetic corpus ----------------------------------------------------

// Block objects shaped EXACTLY as `alloc_pass_position.cjs`'s `blocks()` emits
// them, because they are handed to that file's own `decompose` and `separation`
// unchanged. `m` is built from a known additive model, so what the estimator
// must return is arithmetic rather than opinion.
function syntheticBlocks(flips, { pass = 0, parity = 0, level = 0 } = {}, label = 'synthetic') {
  return flips.map((flip, r) => {
    const first = flip ? 'all' : 'page';
    const evenRound = r % 2 === 0;
    const m = level + pass * (first === 'page' ? 1 : -1) + parity * (evenRound ? 1 : -1);
    return {
      run: label,
      round: r,
      first,
      n: 1,
      m,
      secondMinusFirst: first === 'page' ? -m : m,
      evenRound,
    };
  });
}

// What the SHIPPED decomposition returns on one synthetic corpus.
function readTerms(flips, model) {
  const bs = syntheticBlocks(flips, model);
  return {
    pass: decompose(bs, (b) => b.first === 'page').half,
    parity: decompose(bs, (b) => b.evenRound).half,
    dot: separation(bs).dot,
  };
}

// The three fixtures every admissible schedule must pass, and they are the
// audit's own: a pure parity effect must read as parity alone, a pure pass
// effect as pass alone, and a mixture of the two must return both coefficients
// rather than a blend. The mixed one uses UNEQUAL coefficients on purpose — a
// swap of the two columns would be invisible at equal ones.
const FIXTURES = [
  { name: 'pure PARITY', model: { parity: 1 }, want: { pass: 0, parity: 1 } },
  { name: 'pure PASS', model: { pass: 1 }, want: { pass: 1, parity: 0 } },
  { name: 'PASS 1 + PARITY 3', model: { pass: 1, parity: 3, level: 0.5 }, want: { pass: 1, parity: 3 } },
];

function identifies(flips) {
  return FIXTURES.every((f) => {
    const got = readTerms(flips, f.model);
    return got.pass === f.want.pass && got.parity === f.want.parity;
  });
}

// --- run admissibility, and it FAILS CLOSED ----------------------------------
//
// The merged-PR audit of #8601's second finding: `alloc_pass_position.cjs`'s
// `report()` PRINTS `controlVerdict`, `verification.unverified`, `passOrder`,
// `parityTied` and `scheduleDrove` and then feeds every row to the blocks, the
// headline and both decompositions REGARDLESS. A copy of a real record with
// `controlVerdict.ok = false` still produces the headline. The arbiters do not
// arbitrate.
//
// This is the boundary that does, and it is declared BEFORE the window rather
// than repaired inside it. The shipped reader is left exactly as it is: an
// estimator must not change between a pre-registration and the runs it will be
// read on, and the reader's own defect belongs to the bead rather than to this
// window. What runs here is a gate in FRONT of it — a run that fails any clause
// contributes no figure, and the clause it failed is published beside it.
//
// Every clause is a DECLARED PARAMETER of this window or a control the record
// already carries. Nothing here is a tolerance and nothing is compared against
// a byte threshold.
// THE ESTIMATOR PARAMETERS ARE DECLARED HERE TOO, and the audit of #8615 is
// why. It found this boundary admitting a record with `plan`, `roots`,
// `boundaries` or `writes` changed, although all four are declared fixed — and
// `boundaries` is the `B` every `d` is divided by, so a run that moved it is
// not a run of the same estimator at all. The clause list lives in
// `alloc_pass_position.cjs`, but a clause is only checked against a parameter
// something DECLARES, so omitting them here left the checks inert. All four are
// phase 3's committed values, so its four runs are admitted exactly as before.
const WINDOW = {
  rounds: ROUNDS,
  passOrder: 'seeded',
  writeLegs: ['page', 'all'],
  writePaired: true,
  segOrder: 'parity',
  controlSlot: 'first',
  plan: 'full',
  roots: 4,
  boundaries: 4,
  writes: 6,
};

// IT DELEGATES, AND THAT IS THE REPAIR THE AUDIT OF #8615 ASKED FOR. This file
// used to carry its own copy of the clause list while `alloc_pass_position.cjs`
// carried none, so the boundary sat in FRONT of the reader instead of inside
// it and the two could drift. The clauses now live in the reader — including
// the completeness check that closes this file's own `scheduleDrove` hole,
// where a `perRound` truncated to one row returned `true` vacuously — and this
// front door adds the one clause that cannot live there: `identifies`, which
// drives the fixtures through `decompose` and would make the import circular.
function admissibleRun(row, expect = {}) {
  const want = { ...WINDOW, ...expect };
  const { reasons } = readerAdmissibleRun(row, want);
  const sched = row.passSchedule;
  if (sched && Array.isArray(sched.flips) && !identifies(sched.flips)) {
    reasons.push('the schedule does not recover the fixtures');
  }
  return { ok: reasons.length === 0, reasons };
}

// The smallest record `admissibleRun` will admit, built from a selected run.
// It carries no arm windows at all, which is the point: admissibility is a
// property of the RUN's declared parameters and its controls, and it is decided
// before a single figure is read.
function syntheticRow(pick) {
  const legs = WINDOW.writeLegs;
  return {
    rounds: pick.rounds,
    passOrder: WINDOW.passOrder,
    passSeed: pick.seed,
    passSchedule: { flips: pick.flips.slice(), attempts: 1, parityTied: false },
    writeLegs: legs.slice(),
    writePaired: true,
    segOrder: WINDOW.segOrder,
    controlSlot: WINDOW.controlSlot,
    // Shaped as the DRIVER writes one: `plan` is an object in a record and a
    // name in a declaration.
    plan: { name: WINDOW.plan, arms: true, rungs: true, fits: true },
    roots: WINDOW.roots,
    boundaries: WINDOW.boundaries,
    writes: WINDOW.writes,
    controlVerdict: { ok: true, perDouble: 8.08, differential: 8 },
    verification: { unverified: 0 },
    perRound: pick.flips.map((flip, r) => ({
      round: r,
      writeLegs: flip ? [...legs].reverse() : legs.slice(),
      arms: {},
    })),
  };
}

// --- the report --------------------------------------------------------------

function reportSelection(rounds = ROUNDS, runs = RUNS) {
  const picks = selectSeeds(rounds, runs);
  const out = [];
  out.push(`;; THE SELECTED SCHEDULES — ${rounds} rounds, ${runs} runs (rf2-fk6pj)`);
  out.push(';;   run | seed | flips | page-first rounds | q·parity | q·linear | q·(r mod 4) | identifies');
  picks.forEach((p, i) => {
    out.push(
      `;;   ${i + 1} | ${p.seed} | ${p.key} | {${p.pageFirst.join(', ')}} | ${p.parityDot} | ` +
        `${p.linearDot} | ${p.mod4Dot} | ${identifies(p.flips) ? 'YES' : 'NO'}`
    );
  });
  return out;
}

function reportSchedule(rounds, seed) {
  const drawn = allocPassFlips(rounds, seed);
  const p = scheduleProps(drawn.flips);
  const out = [];
  out.push(`;; ${seed} over ${rounds} rounds`);
  out.push(`;;   flips           ${p.key}`);
  out.push(`;;   parity-tied     ${drawn.parityTied} (draw attempts ${drawn.attempts})`);
  out.push(`;;   page-first      {${p.pageFirst.join(', ')}} — ${p.evenPageFirst} even, ${p.oddPageFirst} odd`);
  out.push(`;;   q·parity        ${p.parityDot}`);
  out.push(`;;   q·linear        ${p.linearDot}`);
  out.push(`;;   q·(r mod 4)     ${p.mod4Dot}  (residual, reported not filtered)`);
  out.push(`;;   admissible      ${admissible(drawn)}`);
  out.push(`;;   identifies      ${identifies(drawn.flips)}`);
  for (const f of FIXTURES) {
    const g = readTerms(drawn.flips, f.model);
    out.push(`;;     ${f.name}: PASS ${g.pass}, PARITY ${g.parity} (want PASS ${f.want.pass}, PARITY ${f.want.parity})`);
  }
  return out;
}

// --- the control, which arbitrates -------------------------------------------
//
// Positive: every schedule this window will run recovers all three fixtures
// exactly. Negative: the schedule the phase-2 window ran, and the one its own
// successor plan named, does NOT — which is what proves the fixture bites
// rather than passing everything handed to it. A control that cannot fail is
// not a control.
function designControl() {
  const picks = selectSeeds();
  assert.strictEqual(picks.length, RUNS, `the selection rule returns ${RUNS} runs`);

  // No two runs share a schedule, and 3 and 4 are 1's and 2's complements.
  const keys = picks.map((p) => p.key);
  assert.strictEqual(new Set(keys).size, RUNS, 'the four schedules are distinct');
  const complement = (k) => k.split('').map((c) => (c === '1' ? '0' : '1')).join('');
  assert.strictEqual(keys[2], complement(keys[0]), 'run 3 is run 1 complemented');
  assert.strictEqual(keys[3], complement(keys[1]), 'run 4 is run 2 complemented');

  for (const p of picks) {
    assert.strictEqual(p.rounds, ROUNDS, `${p.seed}: round count`);
    assert.strictEqual(p.pageFirstCount, ROUNDS / 2, `${p.seed}: balanced legs`);
    assert.strictEqual(p.evenPageFirst, ROUNDS / 4, `${p.seed}: even page-first count`);
    assert.strictEqual(p.oddPageFirst, ROUNDS / 4, `${p.seed}: odd page-first count`);
    assert.strictEqual(p.parityDot, 0, `${p.seed}: q·parity`);
    assert.strictEqual(p.linearDot, 0, `${p.seed}: q·linear`);
    for (const f of FIXTURES) {
      const got = readTerms(p.flips, f.model);
      assert.strictEqual(got.pass, f.want.pass, `${p.seed}: ${f.name} — PASS term`);
      assert.strictEqual(got.parity, f.want.parity, `${p.seed}: ${f.name} — PARITY term`);
      assert.strictEqual(got.dot, 0, `${p.seed}: ${f.name} — parity·pass over the blocks`);
    }
  }

  // AND THE RULE'S OUTPUT IS A POINT OF THE BAND'S OWN SUPPORT, which is where
  // `rf2-t4vu1` found the two disagreeing. This file draws TWO free schedules
  // and forces the other two as their exact complements; the band in
  // `alloc_pass_position.cjs` was re-drawing each run's schedule independently
  // from the 48, a support of 48⁸ that admits quadruples this rule cannot
  // produce. The rule is what MAKES the support what it is, so the two
  // statements of one design are pinned against each other here rather than
  // only over there.
  const { roles, generatorKeys } = assignmentRoles(picks.map((p) => ({ flips: p.flips })));
  assert.strictEqual(generatorKeys.length, 2, 'the rule draws exactly TWO free schedules');
  assert.deepStrictEqual(
    roles.map((r) => `${r.generator}${r.complement ? '~' : ''}`),
    ['0', '1', '0~', '1~'],
    'and the other two are their exact complements, in that order'
  );
  const admissibleSet = admissibleSchedules(ROUNDS);
  assert.strictEqual(admissibleSet.length, 48, 'the reader and this file agree on the admissible set');
  const support = assignmentSupport(generatorKeys.length, admissibleSet);
  assert.strictEqual(support.length, supportSize(2, 48), 'the support is 48 × 46');
  assert.strictEqual(support.length, 2208, 'which is 2,208 ordered assignments');
  const keyed = new Set(support.map((t) => t.map((i) => admissibleSet[i].map((f) => (f ? '1' : '0')).join('')).join('|')));
  assert.ok(keyed.has(generatorKeys.join('|')), 'and the rule\'s own draw is one of them');

  // THE NEGATIVE CONTROL. Phase 2's run-2 schedule over six rounds:
  // `page, page, all, page, all, all`, symmetric difference 4 from parity —
  // the schedule its successor plan named for BOTH runs. Its columns are not
  // orthogonal (q·parity = -2), it is not admissible here, and the shipped
  // decomposition mis-attributes both pure fixtures on it.
  const phase2Run2 = [false, false, true, false, true, true];
  const p2 = scheduleProps(phase2Run2);
  assert.strictEqual(p2.parityDot, -2, 'phase-2 run 2: q·parity is -2, not 0');
  assert.strictEqual(admissible({ flips: phase2Run2, parityTied: false }), false,
    'phase-2 run 2 is NOT admissible under this design');
  const pureParity = readTerms(phase2Run2, { parity: 1 });
  assert.strictEqual(pureParity.pass, -1,
    'phase-2 run 2 reports PASS -1 on a corpus with NO pass effect');
  assert.strictEqual(pureParity.parity, 1, 'phase-2 run 2 does recover the parity term');
  const purePass = readTerms(phase2Run2, { pass: 1 });
  assert.strictEqual(purePass.parity, -1,
    'phase-2 run 2 reports PARITY -1 on a corpus with NO parity effect');
  assert.strictEqual(purePass.pass, 1, 'phase-2 run 2 does recover the pass term');
  assert.strictEqual(identifies(phase2Run2), false, 'and therefore does not identify');

  // AND SO DOES THE PARITY SCHEDULE, which is the tie this bead was filed
  // about — both groupings are one partition and the two terms are one number.
  const parity6 = [false, true, false, true, false, true];
  const tied = readTerms(parity6, { parity: 1 });
  assert.strictEqual(tied.pass, tied.parity, 'a parity schedule returns one number twice');
  assert.strictEqual(Math.abs(tied.dot), 6, 'and its columns are collinear');
  assert.strictEqual(identifies(parity6), false, 'a parity schedule does not identify');

  // THE ROUND COUNT IS LOAD-BEARING, not the criteria alone. At six rounds no
  // balanced schedule satisfies criterion 2 at all — `q·parity` is `4a − 6` for
  // an integer `a`, which is never zero — so the twelve is what makes the
  // design possible rather than merely more precise.
  let anySix = false;
  for (let mask = 0; mask < 64; mask++) {
    const flips = Array.from({ length: 6 }, (_, r) => Boolean((mask >> r) & 1));
    if (flips.filter(Boolean).length !== 3) continue;
    if (scheduleProps(flips).parityDot === 0) anySix = true;
  }
  assert.strictEqual(anySix, false, 'no balanced six-round schedule has q·parity = 0');

  // THE ADMISSIBILITY BOUNDARY, PROVED IN BOTH DIRECTIONS. A control that
  // cannot refuse is not a control, and a control that refuses everything is
  // not one either — so one row that must be admitted, and one clause at a time
  // broken on that same row.
  const admitted = syntheticRow(picks[0]);
  const base = admissibleRun(admitted, { passSeed: picks[0].seed, flips: picks[0].flips });
  assert.strictEqual(base.ok, true, `a well-formed run is admitted: ${base.reasons.join('; ')}`);
  const breaks = [
    ['positive control', (r) => { r.controlVerdict.ok = false; }],
    ['unverified read-backs', (r) => { r.verification.unverified = 1; }],
    ['pass order', (r) => { r.passOrder = 'parity'; }],
    ['round count', (r) => { r.rounds = 6; }],
    ['segment order', (r) => { r.segOrder = 'fixed'; }],
    ['control slot', (r) => { r.controlSlot = 'last'; }],
    ['drive against draw', (r) => { r.perRound[0].writeLegs = ['all', 'page']; }],
    ['the declared schedule', (r) => { r.passSchedule.flips = picks[1].flips.slice(); }],
    // THE FOUR THE AUDIT OF #8615 FOUND ADMITTED. `boundaries` is the divisor
    // of every `d`, so a run that moved it is a different estimator.
    ['the plan', (r) => { r.plan = { ...r.plan, name: 'narrow' }; }],
    ['the root count', (r) => { r.roots = 2; }],
    ['the boundary count', (r) => { r.boundaries = 8; }],
    ['the write count', (r) => { r.writes = 5; }],
    // AND THE THREE CORPUS SHAPES `scheduleDrove` USED TO BLESS VACUOUSLY.
    ['an empty drive', (r) => { r.perRound = []; }],
    ['a truncated drive', (r) => { r.perRound = r.perRound.slice(0, 1); }],
    ['a duplicated round', (r) => { r.perRound[1] = { ...r.perRound[0] }; }],
  ];
  for (const [name, breakIt] of breaks) {
    const bad = syntheticRow(picks[0]);
    breakIt(bad);
    const got = admissibleRun(bad, { passSeed: picks[0].seed, flips: picks[0].flips });
    assert.strictEqual(got.ok, false, `breaking ${name} must refuse the run`);
  }

  // AND THE PHASE-2 CORPUS IS REFUSED BY THIS WINDOW'S OWN RULE. Its seeded
  // six-round records are real, well-controlled runs of a DIFFERENT design, so
  // the boundary must reject them on the design clauses rather than admit them
  // on the control ones.
  const prior = path.join(__dirname, 'data', 'alloc-fk6pj', 'seeded-run1.json');
  if (fs.existsSync(prior)) {
    const row = JSON.parse(fs.readFileSync(prior, 'utf8')).alloc;
    const got = admissibleRun(row, { passSeed: picks[0].seed, flips: picks[0].flips });
    assert.strictEqual(got.ok, false, 'phase 2\'s six-round record is not admissible here');
    assert.ok(got.reasons.some((r) => r.startsWith('rounds ')), 'and the round count is one reason');
    assert.ok(got.reasons.some((r) => r.startsWith('q·parity ')), 'and its columns are not orthogonal');
  }

  console.log(
    `[alloc-pass-design] design control OK — ${RUNS} schedules at ${ROUNDS} rounds recover all ` +
      `${FIXTURES.length} fixtures exactly; phase-2's run-2 schedule and the parity schedule both fail them; ` +
      'no balanced six-round schedule is admissible at all'
  );
}

if (require.main === module) {
  const args = process.argv.slice(2);
  if (args[0] === '--controls') {
    designControl();
    process.exit(0);
  }
  if (args[0] === '--select') {
    for (const line of reportSelection(Number(args[1] || ROUNDS), Number(args[2] || RUNS))) console.log(line);
    process.exit(0);
  }
  if (args[0] === '--admit') {
    const picks = selectSeeds();
    let worst = 0;
    // AN EMPTY OR SHORT CORPUS IS A REFUSAL, NOT A PASS. The audit of #8615
    // found this CLI exiting 0 for no files at all and for one of the declared
    // four: the loop below only ever reports on files it was handed, so
    // "nothing was refused" read as "everything was admitted". The run count is
    // declared, so a corpus that does not carry it never reaches the loop.
    const files = args.slice(1);
    if (files.length !== RUNS) {
      console.log(`;;   REFUSED — the corpus holds ${files.length} run(s), not the declared ${RUNS}.`);
      process.exit(1);
    }
    files.forEach((f, i) => {
      const row = JSON.parse(fs.readFileSync(f, 'utf8')).alloc;
      const pick = picks[i];
      const got = admissibleRun(row, pick ? { passSeed: pick.seed, flips: pick.flips } : {});
      if (!got.ok) worst = 1;
      console.log(
        `;;   run ${i + 1} | ${path.basename(f)} | ${got.ok ? 'ADMITTED' : 'REFUSED'}` +
          `${got.ok ? '' : ' — ' + got.reasons.join('; ')}`
      );
    });
    process.exit(worst);
  }
  if (args[0] === '--schedule') {
    if (args.length < 3) {
      console.error('usage: alloc_pass_design.cjs --schedule <rounds> <seed>');
      process.exit(2);
    }
    for (const line of reportSchedule(Number(args[1]), args[2])) console.log(line);
    process.exit(0);
  }
  console.error('usage: alloc_pass_design.cjs --controls | --select [rounds] [runs] | --schedule <rounds> <seed>');
  process.exit(2);
}

module.exports = {
  ROUNDS,
  RUNS,
  SEED_PREFIX,
  FIXTURES,
  passIndicator,
  parityIndicator,
  linearIndicator,
  mod4Indicator,
  scheduleProps,
  admissible,
  selectSeeds,
  syntheticBlocks,
  readTerms,
  identifies,
  reportSelection,
  reportSchedule,
  WINDOW,
  admissibleRun,
  syntheticRow,
  designControl,
};
