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
//
// ## SECTION 7 — THE LIVE RESTATEMENT (rf2-2k3vo)
//
// Sections 5 and 6 answer `rf2-2rtt6.140` in the RETIRED bound's units, which is
// what the bead is written in. Section 7 asks the same question of the quantity
// the instrument actually exhibits — the observed would-be-total ceiling from
// section 3 — and gets a DIFFERENT answer. The bead's `R20 forces B = 0` is an
// artefact of the deleted constant's tightness: a 300,000 B window bracket
// against a 884,280 B observed ceiling. Restated live, the full ladder admits
// B <= 3.
//
// THAT IS A NECESSARY CONDITION AND NOT A SUFFICIENT ONE, and section 7 prints
// its own limits rather than leaving them to the page: the ceiling admits 36
// windows the certificate refuses, the model is fitted at the single page size
// the corpus holds, and at that page size it reduces ALGEBRAICALLY to the
// ceiling test, so the agreement it reports there is not independent evidence
// for the extrapolation.
//
// ## ADMISSIBILITY — A FAILED POSITIVE CONTROL IS NOT AN OBSERVATION
//
// `load()` below refuses any dataset whose `alloc.controlVerdict.ok` is not
// exactly `true`, and names it. Two committed datasets elsewhere in the tree
// carry `ok: false` (`alloc-77gz8/run12-a4a1537cb71`,
// `alloc-9jrhi/bisect-5-a-4a1537cb71-replicate`); NEITHER is in this corpus, and
// both `alloc-0gjqi` runs pass at 8.00 B/double. The check is therefore inert
// here and is present so that it CANNOT become fail-open if the corpus grows.

const fs = require('fs');
const path = require('path');

const DATA = path.join(__dirname, 'data');
const DIR = 'alloc-0gjqi';
const RUNS = ['paired-run1', 'paired-run2'];

// `rf2-n6w7o`'s masking bound and the averaging floor it is charged at, both
// read here rather than proposed. Neither is touched by this reader.
//
// THE BOUND IS RETIRED, AND THIS READER DOES NOT TREAT IT AS LIVE. `rf2-2rtt6.141`
// accepted the #7682 audit's two soundness objections, replaced the bound with
// the observed leg witness, and DELETED `ALLOC_MASK_BUDGET_B` and
// `allocMaxWrites` -- an ancestral, gated fact rather than a proposal:
// `p0_ladder_structural.test.cjs` pins `lacks(/const ALLOC_MASK_BUDGET_B/)`, and
// `docs/design/hicasso/allocation-instrument-rework.md` carries the reasoning
// under "Constraint semantics -- what is retired and what stands".
//
// It survives here as a HISTORICAL YARDSTICK and nothing more: `rf2-2rtt6.140`'s
// uncertifiability argument is written in its terms, so reconstructing that
// argument at today's F requires the number the bead used. Section 6 reconstructs
// it and says so. No live conclusion rests on it.
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

// FAIL CLOSED on the positive control. The field is nested under `alloc`, not at
// the top level of the file -- reading `raw.controlVerdict` returns `undefined`
// on every dataset in the tree and so admits everything while looking like a
// check. A dataset with NO `controlVerdict` at all is refused for the same
// reason: absence is not a pass.
const load = (name) => {
  const a = JSON.parse(fs.readFileSync(path.join(DATA, DIR, name + '.json'), 'utf8')).alloc;
  const v = a.controlVerdict;
  if (!v || v.ok !== true) {
    throw new Error(
      `INADMISSIBLE: ${DIR}/${name}.json has controlVerdict.ok = ${v ? String(v.ok) : 'ABSENT'}. ` +
        `A failed positive control is not an observation; the dataset is excluded rather than read.`,
    );
  }
  return a;
};

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

// The observed ceiling: the highest would-be total any window in the corpus
// certified at. GUARDED, because `Math.max()` of an empty array is `-Infinity`,
// which would silently place EVERY window "above the ceiling" and read as a
// unanimous finding rather than as no data. An empty certified set is the same
// class of latent fail-open as an unchecked positive control.
function ceiling(ws) {
  const certified = ws.filter((c) => c.certified);
  if (!certified.length) {
    throw new Error(
      'NO CERTIFIED WINDOW IN THE CORPUS: the observed ceiling is undefined, not infinite. ' +
        'Refusing to report a ceiling test rather than returning -Infinity.',
    );
  }
  return Math.max(...certified.map((c) => c.total));
}

// SECTION 7's model, and it is a MODEL. The would-be total is taken as
// `total(R, B) ~= T0 + B * t(R)`, where T0 is the floor arm's own would-be total
// -- the six writes with no subscription under them -- and `t(R)` is fitted from
// the single page size the corpus holds, B = 4. The largest page a rung admits
// under the OBSERVED ceiling is then `floor((CEIL - T0) / t(R))`.
//
// Fitted PER FAMILY rather than per rung, because at R = 20 the pooled rung
// median is not representative of any family: six families sit at 1.03 - 1.05 MB
// and two at 0.85 - 0.86 MB, and the pooled median falls in a gap no family
// occupies. Each family's T0 is its OWN segment-and-selector floor.
function liveLadder(ws) {
  const B = ws[0].boundaries;
  const CEIL = ceiling(ws);
  const floorKey = (c) => `${c.segment} | ${c.writeSelector}`;
  const T0 = new Map();
  for (const [k, v] of groupBy(ws.filter((c) => c.rungKey === 'floor'), floorKey)) {
    T0.set(k, med(v.map((c) => c.total)));
  }
  const fams = [];
  for (const [f, v] of groupBy(ws.filter((c) => c.rungKey !== 'floor'), (c) => c.family)) {
    const base = T0.get(floorKey(v[0]));
    const rungs = new Map();
    for (const g of RUNGS) {
      const vv = v.filter((c) => c.rungKey === g);
      if (!vv.length) continue;
      const total = med(vv.map((c) => c.total));
      const t = (total - base) / B;
      rungs.set(g, {
        rung: g,
        total,
        t,
        // R = 0 mounts boundaries that read nothing, so its `t` is the
        // non-cancellation floor and dividing by it yields an artefact, exactly
        // as its `s` does in section 6. No page size is quoted for it.
        maxB: g === 'R0' || t <= 0 ? null : Math.floor((CEIL - base) / t),
        certified: vv.filter((c) => c.certified).length,
        n: vv.length,
      });
    }
    fams.push({ family: f, base, rungs });
  }
  return { B, CEIL, fams };
}

// The largest page every rung in `set` admits, across every family -- the
// binding rung of the binding family, since one ladder holds ONE B.
const ladderMaxB = (fams, set) => {
  const all = fams.flatMap((f) => set.map((g) => f.rungs.get(g)).filter(Boolean).map((r) => r.maxB));
  return all.includes(null) ? null : Math.min(...all);
};

function groupBy(xs, key) {
  const by = new Map();
  for (const x of xs) {
    const k = key(x);
    if (!by.has(k)) by.set(k, []);
    by.get(k).push(x);
  }
  return by;
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
  const CEIL = ceiling(ws);
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
  say(`    THE ${n0(MASKING_BOUND_B)} B MASKING BOUND IS RETIRED AND DELETED (rf2-2rtt6.141, 2026-08-08).`);
  say(`    It is reconstructed below ONLY because rf2-2rtt6.140's uncertifiability argument`);
  say(`    is written in its terms. Nothing live rests on it; the certificate is the leg`);
  say(`    witness and the falls gate.`);
  say('');
  say(`    retired bound (W+1) x perWrite <= ${n0(MASKING_BOUND_B)} B  =>  perWrite <= ${n0(allow)} B at W = ${W}`);
  say(`    F (floor arm, median perWrite) = ${n0(F)} B = ${pc((100 * F) / allow)} of the allowance`);
  say(`    boundary budget = ${n0(allow)} - ${n0(F)} = ${n0(budget)} B per write`);
  say('');
  say('    rung    perWrite   s = (pW-F)/B    max B (RETIRED)  (W+1) x pW    certified    6 x legMedian');
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
  say(`    RECONSTRUCTING THE BEAD'S OWN ARGUMENT in the retired bound's terms. A ladder`);
  say(`    holds ONE B across all rungs, so on that argument the binding rung decides:`);
  const LADDER = T.filter((r) => r.maxB != null && r.rung !== 'R0');
  say(`      ${LADDER.map((r) => `${r.rung} B<=${r.maxB}`).join(', ')}`);
  const stopAt = LADDER.filter((r) => r.maxB >= 1).map((r) => r.rung);
  say(`      -> R20 alone forces B = 0. Stop at ${stopAt[stopAt.length - 1]} and B = 1 certifies.`);
  say(`    THAT ARGUMENT NO LONGER HAS A LIVE WARRANT: every max-B above is computed from a`);
  say(`    DELETED constant. What survives it is the empirical certified column, which is`);
  say(`    measured rather than modelled and reaches the SAME rung -- R20 at ` +
      `${T.find((r) => r.rung === 'R20').certified}/${T.find((r) => r.rung === 'R20').n} against`);
  say(`    ${LADDER.filter((r) => r.rung !== 'R20').map((r) => `${r.rung} ${r.certified}/${r.n}`).join(', ')}` +
      ` -- and rf2-onozm's observed ceiling, which is a`);
  say(`    quantity rather than a constant. The conclusion stands on those two, not on the bound.`);
  say('');
  say(`    AND THE RETIRED BOUND DISAGREES WITH THE CERTIFICATE IN BOTH DIRECTIONS, which`);
  say(`    is an independent re-derivation of why it was retired rather than a case for it.`);
  say(`    (i) it REFUSES what the certificate ADMITS -- rungs over the ${n0(MASKING_BOUND_B)} B bound:`);
  for (const r of overBound) {
    say(`      ${r.rung.padEnd(4)} (W+1) x perWrite = ${n0(r.bracket)} B, yet certifies ` +
        `${r.certified}/${r.n} = ${pc((100 * r.certified) / r.n)}`);
  }
  const underBound = T.filter((r) => r.bracket <= MASKING_BOUND_B && r.n > r.certified);
  say(`    (ii) it ADMITS what the certificate REFUSES -- rungs under the bound that still refuse:`);
  for (const r of underBound) {
    say(`      ${r.rung.padEnd(4)} (W+1) x perWrite = ${n0(r.bracket)} B, under the bound, yet ` +
        `${r.n - r.certified} of ${r.n} windows refuse`);
  }
  say(`    So the two criteria are INDEPENDENT, not nested either way. Any claim that the`);
  say(`    bound "refuses nothing the certificate admits and admits nothing it refuses" is`);
  say(`    false in both directions on this corpus.`);
  say(`    The bound's status is NOT an open question: it was retired and deleted on`);
  say(`    2026-08-08. This reader moves no reading, threshold, band or budget status.`);

  // ------------------------------------------------------------- 7. rf2-2k3vo
  say('');
  say('=== 7. THE SAME QUESTION IN LIVE TERMS (rf2-2k3vo) ===');
  const L = liveLadder(ws);
  say(`    Section 6 divides by a DELETED constant. This section asks the bead's question of`);
  say(`    the quantity the instrument exhibits: the observed ceiling from section 3.`);
  say('');
  say(`    total(R,B) ~= T0 + B x t(R), t fitted at the corpus's only page B = ${L.B};`);
  say(`    T0 = the family's own floor-arm would-be total; ceiling = ${n0(L.CEIL)} B (observed).`);
  say(`    max B = floor((${n0(L.CEIL)} - T0) / t(R)).`);
  say('');
  say('    family                            T0        R1    R3    R7   R20   <- max B under the ceiling');
  for (const f of L.fams) {
    const cells = ['R1', 'R3', 'R7', 'R20'].map((g) => {
      const r = f.rungs.get(g);
      return (r && r.maxB != null ? String(r.maxB) : '-').padStart(4);
    });
    say(`    ${f.family.padEnd(32)} ${n0(f.base).padStart(9)}  ${cells.join('  ')}`);
  }
  const FULL = ['R1', 'R3', 'R7', 'R20'];
  const SHORT = ['R1', 'R3', 'R7'];
  const bFull = ladderMaxB(L.fams, FULL);
  const bShort = ladderMaxB(L.fams, SHORT);
  say('');
  say(`    ONE B ACROSS ALL RUNGS AND ALL FAMILIES, so the binding cell decides:`);
  say(`      full 1/3/7/20 ladder : B <= ${bFull}      (binding rung R20)`);
  say(`      reduced 1/3/7 ladder : B <= ${bShort}      (binding rung R7)`);
  say('');
  say(`    THE BEAD'S CONCLUSION DOES NOT SURVIVE THE RESTATEMENT. "At six writes there is no`);
  say(`    page of one boundary or more that certifies the 1/3/7/20 ladder" is carried by the`);
  say(`    RETIRED bound's tightness -- a ${n0(MASKING_BOUND_B)} B window bracket against an observed`);
  say(`    ${n0(L.CEIL)} B ceiling, ${(L.CEIL / MASKING_BOUND_B).toFixed(2)}x looser. On the live ceiling the full ladder admits`);
  say(`    B <= ${bFull}, which is one boundary UNDER the page this corpus runs, not zero.`);
  say('');
  say(`    WHAT THE EMPIRICAL COLUMN CAN AND CANNOT CHECK. At B = ${L.B} the prediction is exact:`);
  const admits = L.fams.filter((f) => f.rungs.get('R20').maxB >= L.B);
  const excl = L.fams.filter((f) => f.rungs.get('R20').maxB < L.B);
  const sum = (fs) => fs.reduce((a, f) => a + f.rungs.get('R20').certified, 0);
  const cnt = (fs) => fs.reduce((a, f) => a + f.rungs.get('R20').n, 0);
  say(`      R20 families the ceiling ADMITS at B = ${L.B} (max B >= ${L.B}): ${admits.length}, certifying ` +
      `${sum(admits)}/${cnt(admits)}`);
  say(`      R20 families it EXCLUDES  at B = ${L.B} (max B <  ${L.B}): ${excl.length}, certifying ` +
      `${sum(excl)}/${cnt(excl)}`);
  say(`    -- ${L.fams.length} of ${L.fams.length} families on the right side. BUT THAT AGREEMENT IS NOT INDEPENDENT`);
  say(`    EVIDENCE: t is fitted at B = ${L.B}, so at B = ${L.B} "max B >= ${L.B}" reduces ALGEBRAICALLY to`);
  say(`    "total <= ceiling", which is section 3's test restated per family. It confirms the`);
  say(`    ceiling, not the linear extrapolation to any other page.`);
  say('');
  say(`    AND THE CEILING IS NECESSARY, NOT SUFFICIENT -- section 3's own result. ` +
      `${ws.filter((c) => c.total <= L.CEIL && !c.certified).length} windows`);
  say(`    sit at or below it and refuse anyway, ` +
      `${ws.filter((c) => c.total <= L.CEIL && !c.certified && !c.legs.some((l) => l < 0)).length} of them carrying no collection at all. So`);
  say(`      B <= ${bFull} is a NECESSARY condition the full ladder can meet, NOT a certifying page.`);
  say(`      No committed dataset holds a window at any page size but B = ${L.B}, so whether B = ${bFull}`);
  say(`      certifies is UNMEASURED. Answering it needs a window, and none was taken here.`);
  say('');
  say(`    WHAT SURVIVES, STATED AT THE STRENGTH THE EVIDENCE CARRIES: R = 20 is where the`);
  say(`    ladder binds, on the live ceiling as on the retired bound; at B = ${L.B} it binds hard,`);
  say(`    ${sum(excl)}/${cnt(excl)} in ${excl.length} of ${L.fams.length} families; and the largest full-ladder page the ceiling`);
  say(`    admits is B = ${bFull}. That is a one-rung constraint. It is NOT an impossibility result.`);

  return out.join('\n');
}

// A negative control on the reader's own arithmetic, in TWO halves.
//
// The first feeds hand-built windows through the identities and the cell summary,
// so a regression in either shows up without a dataset. The second reads the real
// corpus, because the claims it guards are about the corpus and a synthetic
// stand-in cannot fail on them -- the merged-PR audit of #8591 found exactly that
// shape here and it is not repeated.
function selfTest() {
  const fail = [];
  let checks = 0;
  const ck = (name, got, want) => {
    checks++;
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

  // THE SET-RELATIONSHIP GUARD, added when the merged-PR audit of #8591 caught
  // this page asserting that the retired bound "refuses nothing the certificate
  // admits and admits nothing the certificate refuses". Both halves are false on
  // this corpus, so the two criteria are INDEPENDENT rather than nested either
  // way.
  //
  // DERIVED FROM THE LOADED CORPUS, not from a literal. An earlier version built
  // three synthetic rungs carrying the answer, filtered those literals, and
  // asserted the filter had found them -- which cannot fail and could not notice
  // the reader reversing the relationship, the one regression it names. The
  // second merged-PR audit of #8591 caught that; this version reads the real 528
  // windows through `rungTable`, so it fails if the reader's own arithmetic
  // moves, and pins the 3 / 5 / 10 under-bound refusal counts the page publishes.
  const cws = windows();
  const cF = med(cws.filter((c) => c.rungKey === 'floor').map((c) => c.perWrite));
  const T = rungTable(cws, cF);
  const overAndAdmitted = T.filter(
    (r) => r.rung !== 'floor' && r.bracket > MASKING_BOUND_B && r.certified > 0,
  );
  const underAndRefused = T.filter((r) => r.bracket <= MASKING_BOUND_B && r.n > r.certified);
  ck('the bound REFUSES what the certificate ADMITS', overAndAdmitted.length > 0, true);
  ck('the bound ADMITS what the certificate REFUSES', underAndRefused.length > 0, true);
  ck('so neither criterion contains the other',
    overAndAdmitted.length > 0 && underAndRefused.length > 0, true);
  ck('the over-bound-yet-certifying rungs are R3, R7, R20',
    overAndAdmitted.map((r) => r.rung).join(','), 'R3,R7,R20');
  ck('the under-bound-yet-refusing rungs refuse 3 / 5 / 10 windows',
    underAndRefused.map((r) => `${r.rung}:${r.n - r.certified}`).join(','), 'floor:3,R0:5,R1:10');

  // SECTION 7's ANSWER, pinned against the corpus for the same reason. The bead's
  // conclusion is that no page of one boundary or more certifies the full ladder;
  // the live ceiling admits B <= 3, so the guard that matters is the one that
  // fires if that ever silently returns 0 -- the reconstruction's answer arriving
  // in the live section would be exactly the defect rf2-2k3vo exists to fix.
  const L = liveLadder(cws);
  ck('the observed ceiling is the corpus maximum certified would-be total', L.CEIL, 884280);
  ck('the full 1/3/7/20 ladder admits a page of one boundary or more',
    ladderMaxB(L.fams, ['R1', 'R3', 'R7', 'R20']) >= 1, true);
  ck('and that page is B <= 3', ladderMaxB(L.fams, ['R1', 'R3', 'R7', 'R20']), 3);
  ck('the reduced 1/3/7 ladder admits B <= 8', ladderMaxB(L.fams, ['R1', 'R3', 'R7']), 8);
  // The ceiling is NECESSARY and not SUFFICIENT, so a guard that only checked the
  // admitting side would license reading max B as a certifying page.
  ck('the ceiling still admits windows the certificate refuses',
    cws.filter((c) => c.total <= L.CEIL && !c.certified).length, 36);

  // An empty certified set must be NO CEILING rather than -Infinity, which would
  // place every window above the ceiling and read as a unanimous finding.
  let threw = false;
  try {
    ceiling(cws.map((c) => ({ ...c, certified: false })));
  } catch {
    threw = true;
  }
  ck('an empty certified set refuses to yield a ceiling', threw, true);

  if (fail.length) {
    console.error('SELF-TEST FAILED:\n  ' + fail.join('\n  '));
    process.exit(1);
  }
  console.log(`self-test OK (${checks} checks)`);
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) selfTest();
  else console.log(report());
}

module.exports = {
  windows, identities, cells, rungTable, ceiling, liveLadder, ladderMaxB,
  report, selfTest, med, pos, MASKING_BOUND_B, W,
};
