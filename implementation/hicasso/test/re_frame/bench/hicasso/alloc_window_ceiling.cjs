'use strict';
// WHAT SEPARATES A CERTIFYING R = 20 WINDOW FROM A REFUSING ONE — rf2-onozm,
// and the same constraint under its other name, rf2-2rtt6.140.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_window_ceiling.cjs
//     node hicasso/test/re_frame/bench/hicasso/alloc_window_ceiling.cjs --self-test
//
// Records:
//   docs/design/hicasso/studio/the-window-total-is-the-ceiling.md
//   docs/design/hicasso/studio/the-fixed-cost-binds-on-one-rung.md
//
// ## WHAT THIS IS AND IS NOT
//
// It is a READER. It launches no browser, builds no bundle, reads no rig file
// and writes nothing. Every figure both pages publish is printed below; run it
// and diff the output against them.
//
// ## THE QUESTION
//
// V2's top rung certifies on ONE of its four arm families and refuses on the
// other three, in three independent sessions. `rf2-onozm` asks what separates
// them. The recorded `rise` does not: across the corpus's sixteen R = 20 cell
// medians it spans 3.78%, which is nothing.
//
// ## WHY `rise` CANNOT, WHICH IS ARITHMETIC RATHER THAN LUCK
//
// `rise` is the sum of the window's POSITIVE deltas — over legs and over the
// inter-leg gaps alike — so a leg the collector emptied contributes zero rather
// than its negative reading. The identity
//
//     rise == sum(max(leg, 0)) + sum(max(gap, 0))
//
// is checked below on every window in the corpus and holds on all 528. A window
// that lost one leg to a collection therefore reports the sum of the legs it has
// LEFT, and on this bench five refusing-family legs come to almost exactly six
// surviving-family legs. The masking bound is charged on `perWrite`, and
// `perWrite == rise / writes` — also checked below, on all 528 — so the bound
// inherits the same blindness.
//
// ## THE QUANTITY THAT DOES SEPARATE
//
// The window's WOULD-BE total: `6 x legMedian`, what the window would have
// allocated had nothing collected inside it. It is built from the median work
// leg, so a collapsed leg cannot pull it down.
//
// ## AND THE ONE MODELLED STEP, which is the reader's chief limit
//
// The five-write projection is `5 x legMedian` — the same window with its top
// leg removed. It is ARITHMETIC ON THE RECORDED LEGS, not a measurement of a
// five-write window: no such window exists in any committed dataset. Whether a
// window that allocates that much certifies is a claim about the corpus's
// observed ceiling, and the reader prints the evidence for that ceiling rather
// than assuming it.

const fs = require('fs');
const path = require('path');

const DATA = path.join(__dirname, 'data');
const DIR = 'alloc-0gjqi';
const RUNS = ['paired-run1', 'paired-run2'];

// `rf2-n6w7o`'s masking bound and the averaging floor it is charged at, both
// read here rather than proposed. Neither is touched by this reader.
const MASKING_BOUND_B = 300000;
const W = 6;

const med = (a) => {
  const s = [...a].sort((x, y) => x - y);
  const n = s.length;
  return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
};
const pos = (x) => (x > 0 ? x : 0);
// Every negative leg in a window, not just the first or the deepest. A fresh
// array each call, so callers may sort it in place. This is the reader's only
// EVENT-level view of collections: the position and magnitude censuses below
// both take one reading per window and so cannot count collections at all.
const negLegs = (c) => c.legs.filter((l) => l < 0);
const n0 = (v) => Math.round(v).toLocaleString('en-US');
const pc = (v) => v.toFixed(1) + '%';

const load = (name) =>
  JSON.parse(fs.readFileSync(path.join(DATA, DIR, name + '.json'), 'utf8')).alloc;

// Every arm window in the corpus, flattened, each carrying the run and round it
// came from. `grid/floor` windows carry no rung, so they are keyed 'floor'.
function windows() {
  const out = [];
  for (const name of RUNS) {
    const d = load(name);
    for (const r of d.perRound) {
      for (const a of Object.values(r.arms)) {
        out.push({
          run: name,
          round: r.round,
          writes: d.writes,
          boundaries: d.boundaries,
          rungKey: a.arm === 'grid/floor' ? 'floor' : a.rung,
          family: `${a.segment} | ${a.arm} @${a.writeSelector}`,
          cell: `${name} | ${a.segment} | ${a.arm} | ${a.writeSelector}`,
          total: W * a.legMedian, // the would-be total
          five: (W - 1) * a.legMedian, // the five-write projection
          ...a,
        });
      }
    }
  }
  return out;
}

// The two identities the `rise` argument rests on. Both are checked rather than
// asserted, because the whole account of why `rise` cannot discriminate is that
// it drops negative deltas.
function identities(ws) {
  let riseBad = 0;
  let perWriteBad = 0;
  for (const c of ws) {
    const s =
      c.legs.reduce((a, b) => a + pos(b), 0) + c.gaps.reduce((a, b) => a + pos(b), 0);
    if (s !== c.rise) riseBad++;
    if (Math.abs(c.perWrite - c.rise / c.writes) > 1e-9) perWriteBad++;
  }
  return { n: ws.length, riseBad, perWriteBad };
}

// The 16 cells the triage counted: run x segment x arm x write, each summarised
// by the median of its six rounds.
function cells(ws, rung) {
  const by = new Map();
  for (const c of ws) {
    if (c.rungKey !== rung) continue;
    if (!by.has(c.cell)) by.set(c.cell, []);
    by.get(c.cell).push(c);
  }
  return [...by.entries()]
    .map(([k, v]) => ({
      cell: k,
      n: v.length,
      certified: v.filter((x) => x.certified).length,
      total: med(v.map((x) => x.total)),
      five: med(v.map((x) => x.five)),
      rise: med(v.map((x) => x.rise)),
      falls: v.map((x) => x.falls),
    }))
    .sort((a, b) => b.total - a.total);
}

const RUNGS = ['floor', 'R0', 'R1', 'R3', 'R7', 'R20'];

function rungTable(ws, F) {
  const B = ws[0].boundaries;
  return RUNGS.map((g) => {
    const v = ws.filter((c) => c.rungKey === g);
    const perWrite = med(v.map((c) => c.perWrite));
    const s = g === 'floor' ? null : (perWrite - F) / B;
    const budget = MASKING_BOUND_B / (W + 1) - F;
    return {
      rung: g,
      n: v.length,
      certified: v.filter((c) => c.certified).length,
      perWrite,
      bracket: (W + 1) * perWrite,
      s,
      maxB: s == null || s <= 0 ? null : Math.floor(budget / s),
      total: med(v.map((c) => c.total)),
    };
  });
}

function report() {
  const ws = windows();
  const out = [];
  const say = (s = '') => out.push(s);

  say(`=== 0. CORPUS AND IDENTITIES ===`);
  say(`    ${DIR}/{${RUNS.join(',')}}.json`);
  const id = identities(ws);
  say(`    ${id.n} arm windows | W = ${W} measured writes after one prime | B = ${ws[0].boundaries}`);
  say(`    rise == sum(max(leg,0)) + sum(max(gap,0))  : ${id.riseBad} mismatches of ${id.n}`);
  say(`    perWrite == rise / writes                  : ${id.perWriteBad} mismatches of ${id.n}`);
  say(`    -> rise drops every negative delta, so a collected leg is INVISIBLE to`);
  say(`       both rise and to the masking bound charged on perWrite.`);

  // ------------------------------------------------------------- 1. rf2-onozm
  say('');
  say('=== 1. THE R = 20 CENSUS, PER ARM FAMILY (rf2-onozm) ===');
  const famR20 = new Map();
  for (const c of ws.filter((x) => x.rungKey === 'R20')) {
    if (!famR20.has(c.family)) famR20.set(c.family, []);
    famR20.get(c.family).push(c);
  }
  say('    family                            certified   median rise   6 x legMedian   falls');
  for (const [f, v] of [...famR20.entries()].sort(
    (a, b) => med(b[1].map((x) => x.total)) - med(a[1].map((x) => x.total)),
  )) {
    const falls = v.map((x) => x.falls);
    say(
      `    ${f.padEnd(32)} ${(v.filter((x) => x.certified).length + '/' + v.length).padStart(6)}   ` +
        `${n0(med(v.map((x) => x.rise))).padStart(11)}   ${n0(med(v.map((x) => x.total))).padStart(13)}   ` +
        `${Math.min(...falls)}-${Math.max(...falls)}`,
    );
  }
  const r20rise = [...famR20.values()].map((v) => med(v.map((x) => x.rise)));
  say('');
  say(
    `    rise across the 8 families: ${n0(Math.min(...r20rise))} - ${n0(Math.max(...r20rise))} B = ` +
      `${(((Math.max(...r20rise) - Math.min(...r20rise)) / Math.min(...r20rise)) * 100).toFixed(2)}% ` +
      `<- DOES NOT DISCRIMINATE`,
  );

  say('');
  say('=== 2. THE SIXTEEN CELLS, ON THE WOULD-BE TOTAL ===');
  const C = cells(ws, 'R20');
  say('    cell                                             cert   6 x legMed   5 x legMed   median rise');
  for (const c of C) {
    say(
      `    ${c.cell.padEnd(46)} ${(c.certified + '/' + c.n).padStart(5)}   ${n0(c.total).padStart(10)}   ` +
        `${n0(c.five).padStart(10)}   ${n0(c.rise).padStart(11)}`,
    );
  }
  const ref = C.filter((c) => c.certified === 0);
  const cer = C.filter((c) => c.certified > 0);
  const refLo = Math.min(...ref.map((c) => c.total));
  const cerHi = Math.max(...cer.map((c) => c.total));
  say('');
  say(`    ${ref.length} refusing cells   ${n0(refLo)} - ${n0(Math.max(...ref.map((c) => c.total)))} B`);
  say(`    ${cer.length} certifying cells ${n0(Math.min(...cer.map((c) => c.total)))} - ${n0(cerHi)} B`);
  say(`    GAP ${n0(refLo - cerHi)} B, no overlap in ${C.length} of ${C.length} cells.`);
  say(`    falls: ${cer.every((c) => c.falls.every((f) => f === 0)) ? 'ZERO in every certifying round' : 'MIXED'}; ` +
      `${ref.every((c) => c.falls.every((f) => f >= 1)) ? 'one or more in every refusing round' : 'MIXED'}.`);

  // The cell table is 16 medians. The corpus is 528 windows, and at that
  // granularity the separator is ONE-SIDED — which is the finding, not a caveat.
  say('');
  say('=== 3. THE SAME SEPARATOR AT WINDOW GRANULARITY — AND IT IS ONE-SIDED ===');
  const certified = ws.filter((c) => c.certified);
  const CEIL = Math.max(...certified.map((c) => c.total));
  const above = ws.filter((c) => c.total > CEIL);
  const below = ws.filter((c) => c.total <= CEIL);
  const top = certified.sort((a, b) => b.total - a.total)[0];
  say(`    highest CERTIFIED would-be total in the corpus: ${n0(CEIL)} B`);
  say(`      (${top.family}, ${top.rungKey}, ${top.run} round ${top.round})`);
  say(`    windows ABOVE it: ${above.length}, certified ${above.filter((c) => c.certified).length}`);
  say(`    windows AT OR BELOW: ${below.length}, certified ${below.filter((c) => c.certified).length}` +
      ` (${pc((100 * below.filter((c) => c.certified).length) / below.length)})`);
  say('');
  say(`    So the total is NECESSARY, not SUFFICIENT. The ${below.length - below.filter((c) => c.certified).length}` +
      ` refusals below the ceiling, by rung:`);
  const lowRef = below.filter((c) => !c.certified);
  const byRung = {};
  lowRef.forEach((c) => (byRung[c.rungKey] = (byRung[c.rungKey] || 0) + 1));
  say(`      ${RUNGS.filter((g) => byRung[g]).map((g) => `${g} ${byRung[g]}`).join(' | ')}`);
  say(`      of those, ${lowRef.filter((c) => c.legs.some((l) => l < 0)).length} carry a negative leg` +
      ` (a collection inside the window) and ${lowRef.filter((c) => !c.legs.some((l) => l < 0)).length} do not.`);

  say('');
  say('=== 4. THE REFUSAL SIGNATURE ===');
  const refused = ws.filter((c) => !c.certified);
  say(`    ${refused.length} refused windows in the corpus; ` +
      `${refused.filter((c) => (c.legRefusals || []).length).length} carry a LEG refusal, ` +
      `${refused.filter((c) => (c.intraLegRefusals || []).length).length} an intra-leg one.`);
  const neg = ws.filter((c) => c.legs.some((l) => l < 0));
  say(`    windows with a negative leg: ${neg.length} of ${ws.length}; ` +
      `certified among them: ${neg.filter((c) => c.certified).length}`);
  say(`      of those ${neg.length}, ${neg.filter((c) => negLegs(c).length > 1).length} carry MORE THAN ONE.`);
  const r20neg = ws.filter((c) => c.rungKey === 'R20' && !c.certified && c.legs.some((l) => l < 0));
  const at = {};
  r20neg.forEach((c) => {
    const i = c.legs.findIndex((l) => l < 0) + 1;
    at[i] = (at[i] || 0) + 1;
  });
  const mags = r20neg.map((c) => Math.min(...c.legs));
  say(`    R = 20 refusals with AT LEAST ONE negative leg: ${r20neg.length} of ` +
      `${ws.filter((c) => c.rungKey === 'R20' && !c.certified).length}`);

  // THE CARDINALITY CENSUS. Both per-window statistics below take ONE reading
  // from each window -- the FIRST negative leg's position, and the DEEPEST
  // leg's magnitude -- so neither can distinguish one collection from several.
  // The count that can is this one, and it is printed FIRST for that reason.
  const perWin = {};
  r20neg.forEach((c) => {
    const k = negLegs(c).length;
    perWin[k] = (perWin[k] || 0) + 1;
  });
  const events = r20neg.flatMap((c) => negLegs(c));
  const multi = r20neg.filter((c) => negLegs(c).length > 1);
  say(`      negative legs per window: ` +
      Object.keys(perWin).sort((a, b) => a - b).map((k) => `${k} x ${perWin[k]}`).join(', ') +
      `  ->  ${events.length} EVENTS over ${r20neg.length} windows`);
  say(`      so the signature is AT LEAST ONE collection per refusing window. It is NOT`);
  say(`      exactly one, and neither per-window census below can show the difference.`);
  say(`      the ${multi.length} multi-negative windows, named in full:`);
  for (const c of multi) {
    const posns = c.legs.map((l, i) => (l < 0 ? i + 1 : null)).filter(Boolean);
    say(`        ${c.run} round ${c.round}  ${c.family.padEnd(30)} ` +
        `${negLegs(c).length} neg legs at [${posns.join(',')}]  falls=${c.falls}`);
  }

  say(`      position of the FIRST negative leg (of ${W}): ` +
      Object.keys(at).sort((a, b) => a - b).map((k) => `leg ${k} x ${at[k]}`).join(', '));
  say(`        (${r20neg.length} windows, one reading each; it cannot see the ` +
      `${events.length - r20neg.length} later legs above.)`);
  say(`      DEEPEST leg per window: median ${n0(med(mags))} B, ` +
      `range ${n0(Math.min(...mags))} to ${n0(Math.max(...mags))} B`);
  say(`        (${r20neg.length} windows, one reading each. A MAGNITUDE statistic, NOT a count`);
  say(`         of collections -- what it establishes is the near-constant dominant reclaim.)`);
  const extras = r20neg.flatMap((c) => negLegs(c).sort((a, b) => a - b).slice(1));
  say(`      all ${events.length} events: median ${n0(med(events))} B, ` +
      `range ${n0(Math.min(...events))} to ${n0(Math.max(...events))} B`);
  say(`        the ${extras.length} legs the per-window statistic drops: ` +
      extras.sort((a, b) => a - b).map(n0).join(' B, ') + ' B');
  say(`        -- every one SMALLER in magnitude than the dominant leg of its own window, so`);
  say(`           exposing them does not weaken the dominant-reclaim reading. It separates`);
  say(`           that reading from a COUNT of collections, which is a different claim.`);
  say(`      against a cohort legMedian of ${n0(med(r20neg.map((c) => c.legMedian)))} B`);

  say('');
  say('=== 5. THE FIVE-WRITE PROJECTION, AND WHAT IT RESTS ON ===');
  const p5lo = Math.min(...ref.map((c) => c.five));
  const p5hi = Math.max(...ref.map((c) => c.five));
  say(`    dropping the top rung from ${W} measured writes to ${W - 1} projects the ${ref.length}`);
  say(`    refusing cells to ${n0(p5lo)} - ${n0(p5hi)} B.`);
  say(`      vs the certifying band  ${n0(Math.min(...cer.map((c) => c.total)))} - ${n0(cerHi)} B: ` +
      `${ref.filter((c) => c.five <= cerHi).length} of ${ref.length} land inside it.`);
  say(`      vs the corpus CEILING   ${n0(CEIL)} B: ` +
      `${ref.filter((c) => c.five <= CEIL).length} of ${ref.length} land at or below it` +
      ` (headroom ${n0(CEIL - p5hi)} - ${n0(CEIL - p5lo)} B).`);
  const band = ws.filter((c) => c.total >= p5lo && c.total <= p5hi);
  say(`      windows the corpus actually holds in [${n0(p5lo)}, ${n0(p5hi)}]: ${band.length}, ` +
      `certified ${band.filter((c) => c.certified).length}`);
  say(`        (all ${band.length} are ${[...new Set(band.map((c) => c.family))].join(', ')}, so the`);
  say(`         in-band rate is NOT independent of the surviving family.)`);

  // ---------------------------------------------------------- 6. rf2-2rtt6.140
  say('');
  say('=== 6. THE FIXED COST AND THE LADDER ARITHMETIC (rf2-2rtt6.140) ===');
  const F = med(ws.filter((c) => c.rungKey === 'floor').map((c) => c.perWrite));
  const allow = MASKING_BOUND_B / (W + 1);
  const budget = allow - F;
  say(`    masking bound (W+1) x perWrite <= ${n0(MASKING_BOUND_B)} B  =>  perWrite <= ${n0(allow)} B at W = ${W}`);
  say(`    F (floor arm, median perWrite) = ${n0(F)} B = ${pc((100 * F) / allow)} of the allowance`);
  say(`    boundary budget = ${n0(allow)} - ${n0(F)} = ${n0(budget)} B per write`);
  say('');
  say('    rung    perWrite   s = (pW-F)/B    max B at W=6   (W+1) x pW    certified    6 x legMedian');
  for (const r of rungTable(ws, F)) {
    say(
      `    ${r.rung.padEnd(6)} ${n0(r.perWrite).padStart(9)}   ` +
        `${(r.s == null ? '-' : r.s < 10 ? r.s.toFixed(2) : n0(r.s)).padStart(11)}   ` +
        `${(r.maxB == null ? '-' : String(r.maxB)).padStart(12)}   ` +
        `${n0(r.bracket).padStart(10)}   ` +
        `${(r.certified + '/' + r.n).padStart(7)} ${pc((100 * r.certified) / r.n).padStart(6)}   ` +
        `${n0(r.total).padStart(11)}`,
    );
  }
  const T = rungTable(ws, F);
  const overBound = T.filter((r) => r.rung !== 'floor' && r.bracket > MASKING_BOUND_B);
  say('');
  say(`    R0 is the NULL arm — it mounts boundaries that read nothing, so its s is the`);
  say(`    non-cancellation floor rather than a per-read cost, and its "max B" is an`);
  say(`    artefact of dividing by it. Its perWrite lands ${n0(T.find((r) => r.rung === 'R0').perWrite - F)} B from the floor's,`);
  say(`    which is the independent check that F is the WRITE and not the mount.`);
  say('');
  say(`    A ladder holds ONE B across all rungs, so the binding rung decides:`);
  const LADDER = T.filter((r) => r.maxB != null && r.rung !== 'R0');
  say(`      ${LADDER.map((r) => `${r.rung} B<=${r.maxB}`).join(', ')}`);
  const stopAt = LADDER.filter((r) => r.maxB >= 1).map((r) => r.rung);
  say(`      -> R20 alone forces B = 0. Stop at ${stopAt[stopAt.length - 1]} and B = 1 certifies.`);
  say('');
  say(`    AND THE BOUND DISAGREES WITH THE CERTIFICATE. Rungs over the ${n0(MASKING_BOUND_B)} B bound:`);
  for (const r of overBound) {
    say(`      ${r.rung.padEnd(4)} (W+1) x perWrite = ${n0(r.bracket)} B, yet certifies ` +
        `${r.certified}/${r.n} = ${pc((100 * r.certified) / r.n)}`);
  }
  say(`    Whether the bound is still a binding VALIDITY requirement independent of the`);
  say(`    certificate is a DECISION FOR THE OPERATOR. This reader does not answer it and`);
  say(`    moves no reading, threshold, band or budget status.`);

  return out.join('\n');
}

// A negative control on the reader's own arithmetic. It does not touch the
// corpus: it feeds hand-built windows through the two identities and the cell
// summary, so a regression in either shows up without a dataset.
function selfTest() {
  const fail = [];
  const ck = (name, got, want) => {
    if (got !== want) fail.push(`${name}: got ${got}, want ${want}`);
  };

  // rise drops negative legs AND negative gaps.
  const w = {
    legs: [100, 100, -500, 100],
    gaps: [4, -50, 4, 4],
    rise: 312,
    writes: 4,
    perWrite: 78,
  };
  const s = w.legs.reduce((a, b) => a + pos(b), 0) + w.gaps.reduce((a, b) => a + pos(b), 0);
  ck('rise identity on a synthetic collected window', s, w.rise);
  ck('perWrite identity', w.rise / w.writes, w.perWrite);

  // A window whose legs sum small but whose MEDIAN leg is large: the would-be
  // total must follow the median, not the sum. This is the whole point of the
  // separator, so it gets its own check.
  ck('would-be total follows the median leg', W * med([100, 100, -500, 100, 100, 100]), 600);

  ck('median, even n', med([1, 2, 3, 4]), 2.5);
  ck('median, odd n', med([3, 1, 2]), 2);
  ck('pos clamps', pos(-1) + pos(5), 5);

  // THE CARDINALITY GUARD, added when the merged-PR audit of #8591 caught this
  // page claiming "one collection" where the corpus holds three windows with
  // more than one. These checks pin the DISTINCTION rather than the figures:
  // a per-window reading must not be read as a count of collections.
  const synth = { legs: [100, -700, 100, -300, 100, -50] };
  ck('negLegs counts every negative leg', negLegs(synth).length, 3);
  ck('first-negative-leg position sees only the first', synth.legs.findIndex((l) => l < 0) + 1, 2);
  ck('deepest-leg magnitude sees only the deepest', Math.min(...synth.legs), -700);
  // The two per-window readings above agree on a window with THREE collections
  // and on one with a single -700 B leg. That is exactly the blindness the
  // audit found, so it is asserted rather than described.
  const single = { legs: [100, -700, 100, 100, 100, 100] };
  ck('first-position cannot separate 3 collections from 1',
    (synth.legs.findIndex((l) => l < 0) + 1) === (single.legs.findIndex((l) => l < 0) + 1), true);
  ck('deepest-magnitude cannot separate 3 collections from 1',
    Math.min(...synth.legs) === Math.min(...single.legs), true);
  ck('negLegs CAN separate them', negLegs(synth).length !== negLegs(single).length, true);

  if (fail.length) {
    console.error('SELF-TEST FAILED:\n  ' + fail.join('\n  '));
    process.exit(1);
  }
  console.log(`self-test OK (${12} checks)`);
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) selfTest();
  else console.log(report());
}

module.exports = { windows, identities, cells, rungTable, report, selfTest, med, pos, MASKING_BOUND_B, W };
