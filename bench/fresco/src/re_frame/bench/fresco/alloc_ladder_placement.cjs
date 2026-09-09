#!/usr/bin/env node
// Where does the 2026-08-08 F_old row sit on the floor arm's LEVEL LADDER?
//
// Bead rf2-nkeba.  Record:
//   docs/design/hicasso/studio/the-2026-08-08-row-is-the-arms-top-level.md
//
// THE QUESTION.  V1's allocation control has an across-time clause: F_old "must
// land on the 2026-08-08 figures at B = 24 (24,108 / 24,730 B per write)".  On
// 2026-08-17 that clause was read for the first time and FAILED by 16 - 20%.
// The clause was written when the floor arm was believed to have one level.
//
// WHAT MAKES IT ANSWERABLE NOW.  rf2-c4hhk's seventy-run window pinned
// implementation/core/src at 4a1537cb717dc6660aa449642f198a2cc970c93b -- the
// commit at which the 2026-08-08 row itself was measured -- and read the arm 70
// times with today's instrument.  So for the first time there is a DISTRIBUTION
// of F_old on the 2026-08-08 substrate to place the 2026-08-08 row inside.
//
// WHAT THIS SCRIPT DOES.  It re-derives, from committed datasets only, every
// figure the record page publishes.  It launches no browser, reads no rig, and
// writes nothing.  Run it and diff the output against the page.
//
//   node implementation/hicasso/test/re_frame/bench/hicasso/alloc_ladder_placement.cjs
//
// THE ONE MODELLED STEP, and it is the page's chief limit.  The 2026-08-08
// dataset preserves per-round `rise`, `maxStep`, `falls` and `endpoints` but NOT
// the per-leg samples, so its legs cannot be read directly.  Its window ran six
// writes with no prime split; today's runs seven (one prime, six measured) and
// reports `legMedian` over the six.  The conversion below removes the prime as
// the window's largest step and averages the remaining five legs.  It is a MODEL
// of that window's leg structure, not a measurement of it -- which is exactly
// the lesson rf2-erre5 was filed for.  Its one independent check is printed: the
// prime excess the model implies must sit in the band the same substrate's
// preserved `primeExcess` actually occupies.

const fs = require('fs');
const path = require('path');

const DATA = path.join(__dirname, 'data');
const SEGS = ['reagent-subs', 'uix-subs'];
const KEY = (seg) => `${seg}|grid/floor`;

const med = (a) => {
  const s = [...a].sort((x, y) => x - y);
  const n = s.length;
  return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
};
const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
const load = (p) => JSON.parse(fs.readFileSync(path.join(DATA, p), 'utf8')).alloc;
const n0 = (v) => Math.round(v).toLocaleString('en-US');
const n1 = (v) => v.toFixed(1);

// ---------------------------------------------------------------- 2026-08-08
const OLD = load('alloc-2rtt6-138/run1.json');
const OLD_W = OLD.writes; // 6, no prime split

// rise/W over all six writes -- the basis the published 24,108 / 24,730 are in.
const oldPerWrite = (seg) =>
  OLD.perRound.map((r) => r.arms[KEY(seg)].rise / OLD_W);

// The legMedian basis: drop the prime (the window's largest step) and average
// the five legs left.  `gapsOut` additionally removes the six inter-leg gaps of
// 32 B the reconstruction in PR #8442 established for this dataset.
const oldLegBasis = (seg, gapsOut) =>
  OLD.perRound.map((r) => {
    const a = r.arms[KEY(seg)];
    return (a.rise - (gapsOut ? 192 : 0) - a.maxStep) / (OLD_W - 1);
  });

const oldPrimeExcess = (seg) => {
  const base = oldLegBasis(seg, false);
  return OLD.perRound.map((r, i) => r.arms[KEY(seg)].maxStep - base[i]);
};

// A SECOND, INDEPENDENT CONVERSION, so the placement does not rest on one model.
// If the window's six legs are one repeated work unit L and the first carries a
// prime excess E, then rise = 6L + E + gaps, so L = (rise - E - gaps) / 6.  This
// takes E from the SAME substrate's own preserved `primeExcess` instead of
// inferring it from `maxStep`, and so shares no term with the conversion above
// beyond `rise` itself.  Where the two agree, the placement is not an artefact
// of either.
const oldLegBasisFromE = (seg, E, gapsOut) =>
  OLD.perRound.map((r) => (r.arms[KEY(seg)].rise - E - (gapsOut ? 192 : 0)) / OLD_W);

// ------------------------------------------------------- alloc-c4hhk, n = 70
const C4_ALL = fs
  .readdirSync(path.join(DATA, 'alloc-c4hhk'))
  .filter((f) => f.endsWith('.json'))
  .sort()
  .map((f) => ({ f, name: f.split('-a4')[0], d: load(`alloc-c4hhk/${f}`) }));
// armed-25 produced NO reading (Chromium failed to launch) yet exited 1 exactly
// like the 69 good runs.  rf2-c4hhk committed it as evidence of its own
// exclusion rather than replacing it, so it must be counted OUT loudly here and
// not silently dropped by the filter below.
const C4_NOREAD = C4_ALL.filter((r) => !r.d || !r.d.perRound);
const C4 = C4_ALL.filter((r) => r.d && r.d.perRound);

// rf2-c4hhk's pre-registered admissibility, unchanged: the positive controls
// pass and no read-back went unverified.  The runner's exit code is NOT a
// criterion -- armed-25 exited 1 exactly like the 69 good runs.
const ADM = C4.filter(
  (r) =>
    r.d.controlVerdict &&
    r.d.controlVerdict.ok === true &&
    r.d.verification &&
    r.d.verification.unverified === 0,
);

// rf2-77gz8's estimator, carried unchanged through rf2-c4hhk: the median, over
// CERTIFIED windows at round index >= 6, of that window's legMedian.
const estimator = (d, seg) => {
  const v = d.perRound
    .filter((r) => r.round >= 6 && r.arms[KEY(seg)] && r.arms[KEY(seg)].certified)
    .map((r) => r.arms[KEY(seg)].legMedian);
  return v.length ? med(v) : null;
};
const certRounds = (d, seg) =>
  d.perRound.filter((r) => r.arms[KEY(seg)] && r.arms[KEY(seg)].certified).map((r) => r.arms[KEY(seg)]);

const HIGH_CRITERION = 21000; // rf2-77gz8's, unchanged

// ------------------------------------------------------------------- report
const out = [];
const say = (s = '') => out.push(s);
const PRESERVED_E = {}; // filled in section 3, consumed in section 6

say('=== 1. THE 2026-08-08 ROW, RE-DERIVED FROM ITS OWN DATASET ===');
say(`    alloc-2rtt6-138/run1.json | rounds ${OLD.perRound.map((r) => r.round).join(',')} | writes ${OLD_W} | boundaries ${OLD.boundaries}`);
for (const seg of SEGS) {
  const pw = oldPerWrite(seg);
  say('');
  say(`  ${seg}`);
  say(`    rise/W per round : ${pw.map(n1).join(' / ')}`);
  say(`    mean   ${n1(mean(pw))}   <- what the runner of the day printed`);
  say(`    MEDIAN ${n1(med(pw))}   <- what was published`);
}

say('');
say('=== 2. THE SAME ROW ON TODAY\'S legMedian BASIS ===');
say("    (rise - maxStep) / 5, prime removed.  'gaps out' also removes 6 x 32 B.");
const conv = {};
for (const seg of SEGS) {
  const A = oldLegBasis(seg, false);
  const B = oldLegBasis(seg, true);
  conv[seg] = { A, B };
  say('');
  say(`  ${seg}`);
  say(`    per round        : ${A.map(n0).join(' / ')}`);
  say(`    gaps out         : ${B.map(n0).join(' / ')}`);
  say(`    median, rounds 1-5 : ${n1(med(A.slice(1)))}   (gaps out ${n1(med(B.slice(1)))})`);
  say(`    median, all rounds : ${n1(med(A))}   (gaps out ${n1(med(B))})`);
  const pe = oldPrimeExcess(seg);
  say(`    implied primeExcess: ${pe.map(n0).join(' / ')}  (mean ${n0(mean(pe))})`);
}

say('');
say('=== 3. THE LEVEL LADDER AT THE SAME SUBSTRATE REVISION ===');
say(`    alloc-c4hhk, implementation/core/src pinned at 4a1537cb71`);
say(`    ${C4_ALL.length} datasets committed | ${C4_NOREAD.length} produced no reading (${C4_NOREAD.map((r) => r.name).join(', ') || 'none'})` +
    ` | ${C4.length} scored | ${ADM.length} admissible` +
    ` (control/read-back exclusions: ${C4.filter((r) => !ADM.includes(r)).map((r) => r.name).join(', ') || 'none'})`);
for (const seg of SEGS) {
  const vals = ADM.map((r) => estimator(r.d, seg)).filter((v) => v != null);
  const tally = {};
  vals.forEach((v) => (tally[v] = (tally[v] || 0) + 1));
  const keys = Object.keys(tally).map(Number).sort((a, b) => a - b);
  const lo = keys[0];
  const hi = keys[keys.length - 1];
  say('');
  say(`  ${seg}  (n = ${vals.length})`);
  keys.forEach((k) => say(`    ${n0(k).padStart(7)}  x ${tally[k]}`));
  say(`    SPAN ${n0(hi - lo)} B = ${(((hi - lo) / lo) * 100).toFixed(2)}% of the low level`);
  // the preserved primeExcess, for the check on section 2's model
  // The preserved primeExcess is heavy-tailed -- a prime leg that catches a
  // page-global allocation reads in the hundreds of kilobytes while its six
  // MEASURED legs still certify -- so quote the quartiles, never the extremes.
  const pe = ADM.flatMap((r) => certRounds(r.d, seg).map((a) => a.primeExcess)).sort((a, b) => a - b);
  const q = (p) => pe[Math.floor(p * (pe.length - 1))];
  say(`    preserved primeExcess over ${pe.length} certified rounds: median ${n0(med(pe))}, p05-p95 ${n0(q(0.05))} - ${n0(q(0.95))}`);
  PRESERVED_E[seg] = med(pe);
}
const high = ADM.filter((r) => SEGS.some((s) => estimator(r.d, s) >= HIGH_CRITERION));
say('');
say(`  elevated (either segment >= ${n0(HIGH_CRITERION)}): ${high.length} of ${ADM.length}` +
    `  |  armed ${high.filter((r) => /^armed/.test(r.name)).length} of ${ADM.filter((r) => /^armed/.test(r.name)).length}` +
    `  |  unarmed ${high.filter((r) => /^unarmed/.test(r.name)).length} of ${ADM.filter((r) => /^unarmed/.test(r.name)).length}`);

say('');
say('=== 4. WHERE THE STEP LANDS, AND WHY SIX ROUNDS CANNOT SEE IT ===');
const stepAt = {};
high.forEach((r) => {
  const s = r.d.perRound.map((x) => (x.arms[KEY('reagent-subs')] ? x.arms[KEY('reagent-subs')].legMedian : null));
  const i = s.findIndex((v) => v != null && v >= HIGH_CRITERION);
  stepAt[i] = (stepAt[i] || 0) + 1;
});
Object.keys(stepAt).sort((a, b) => a - b).forEach((k) =>
  say(`    first round index at or above ${n0(HIGH_CRITERION)}: round ${k}  x ${stepAt[k]}`));
say(`    the 2026-08-08 run holds rounds ${OLD.perRound[0].round} - ${OLD.perRound[OLD.perRound.length - 1].round}.`);
say(`    the level witness's AFTER window opens at round 6, so it cannot adjudicate that run at all.`);

say('');
say('=== 5. PLACEMENT INSIDE THE ENVELOPE ===');
for (const seg of SEGS) {
  const vals = ADM.flatMap((r) => certRounds(r.d, seg).map((a) => a.legMedian)).sort((a, b) => a - b);
  say('');
  say(`  ${seg}  envelope of ${vals.length} certified round-readings: ${n0(vals[0])} - ${n0(vals[vals.length - 1])}`);
  conv[seg].A.forEach((v, i) => {
    const below = vals.filter((x) => x < v).length;
    const where =
      v > vals[vals.length - 1] ? `ABOVE by ${n0(v - vals[vals.length - 1])} B`
      : v < vals[0] ? 'BELOW' : 'inside';
    say(`    round ${i}  ${n0(v).padStart(7)}  ->  ${((100 * below) / vals.length).toFixed(1)}th percentile, ${where}`);
  });
}

say('');
say('=== 6. THE 2026-08-08 ROW AGAINST THE LADDER\'S TOP LEVEL ===');
const top = {};
for (const seg of SEGS) {
  let best = null;
  for (const r of ADM) {
    const e = estimator(r.d, seg);
    if (e != null && (best === null || e > best.e)) best = { name: r.name, e };
  }
  top[seg] = best;
}
say(`    top level carried by: ${SEGS.map((s) => `${s} ${top[s].name} ${n0(top[s].e)}`).join('  |  ')}`);
say('');
say('    seg           conversion                     2026-08-08   top level    delta    delta %');
for (const seg of SEGS) {
  const t = top[seg].e;
  const E = PRESERVED_E[seg];
  const C = oldLegBasisFromE(seg, E, false);
  const D = oldLegBasisFromE(seg, E, true);
  const rows = [
    ['maxStep, rounds 1-5', med(conv[seg].A.slice(1))],
    ['maxStep, rounds 1-5, gaps out', med(conv[seg].B.slice(1))],
    ['maxStep, all rounds', med(conv[seg].A)],
    ['maxStep, all rounds, gaps out', med(conv[seg].B)],
    [`E=${n0(E)}, rounds 1-5`, med(C.slice(1))],
    [`E=${n0(E)}, rounds 1-5, gaps out`, med(D.slice(1))],
    [`E=${n0(E)}, all rounds`, med(C)],
    [`E=${n0(E)}, all rounds, gaps out`, med(D)],
  ];
  rows.forEach(([label, v]) =>
    say(`    ${seg.padEnd(13)} ${label.padEnd(30)} ${n1(v).padStart(9)} ${n0(t).padStart(10)} ${n1(v - t).padStart(9)} ${(((v - t) / t) * 100).toFixed(2).padStart(8)}%`));
}

say('');
say('=== 7. THE PUBLISHED SHORTFALL, FOR SCALE ===');
const PUB = { 'reagent-subs': 24108, 'uix-subs': 24730 };
const TODAY = { 'reagent-subs': [19349, 19650, 19816], 'uix-subs': [19712, 20696] };
for (const seg of SEGS) {
  const d = TODAY[seg].map((v) => PUB[seg] - v);
  say(`    ${seg.padEnd(13)} target ${n0(PUB[seg])}  certified ${TODAY[seg].map(n0).join(' / ')}` +
      `  ->  short by ${d.map(n0).join(' / ')} B` +
      `  = ${TODAY[seg].map((v) => (((v - PUB[seg]) / PUB[seg]) * 100).toFixed(2) + '%').join(' / ')}`);
}
say('');
say('    The ladder spans 3,784 B on BOTH segments.  The shortfall it is asked to');
say('    adjudicate is 4,034 - 5,018 B.  A control cannot decide an effect the');
say('    quantity it reads varies by, at one revision, on one instrument, in one');
say('    afternoon.');

process.stdout.write(out.join('\n') + '\n');
