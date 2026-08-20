'use strict';
// WHAT CARRIES THE ~1,000-1,300 B CLUSTER — rf2-csca8, read off the committed
// floor corpus and nothing else.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs <dataset.json>...
//     node hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs --corpus
//     node hicasso/test/re_frame/bench/hicasso/alloc_cluster_carrier.cjs --self-test
//
// ## THE QUESTION
//
// `rf2-rs8q6` filed rather than chased a SECOND term: under
// `P0_ALLOC_SEG_ORDER=fixed` the second-driven arm window of a round — which
// under that mode is always `uix-subs` — carries a cluster of windows whose
// worst leg sits around 1,050-1,224 B over the window's own leg median.
// `rf2-csca8` asks which of THREE properties carries it: the uix SUBSTRATE, the
// POSITION in the round, or the segment-order MODE itself.
//
// Under `fixed` those three are confounded exactly as position and substrate
// were before that mode was landed: position 0 is always `reagent-subs` and
// position 1 is always `uix-subs`. So a `fixed` run alone cannot separate them.
//
// ## WHY THIS READER EXISTS RATHER THAN A ONE-OFF DERIVATION
//
// `alloc_position_confound.cjs` already extracts the window stream, the leg
// cohorts and the control arm, and already cross-checks each round's stated
// segment and window order against the keys the round actually filled. This
// reader IMPORTS that extraction rather than restating it, so the two records
// cannot drift about what a window is. What it adds is the three-way census and
// its exact test.
//
// ## THE STATISTIC, AND WHY THIS READER PRINTS TWO OF THEM
//
// The bead's population is "collection-free windows whose WORST LEG sits in the
// band" — one statistic per window, not a leg count. But "worst leg" has two
// readings and they do not agree on this corpus:
//
//   SIGNED-FURTHEST   the excess furthest from the cohort median in EITHER
//                     direction. This is `legWorstDeviation` re-derived, and it
//                     is the reading `alloc_position_confound.cjs` publishes.
//   LARGEST-POSITIVE  the largest excess ABOVE the cohort median.
//
// They differ on exactly one window in the `segorder-rs8q6` corpus, and that
// one window is the whole of the reported "8 of 38 versus 9 of 38": `fixed-2`
// round 4 `uix-subs` has excesses [0, -12, 0, -4324, +1056, 0]. Its furthest
// excess is -4,324 B, so signed-furthest puts it out of band; its largest
// positive excess is +1,056 B, so largest-positive puts it in. NEITHER COUNT IS
// AN ERROR. A record quoting one of them must say which, and this reader prints
// both side by side so the choice cannot be silent.
//
// The -4,324 B leg is itself worth the sentence: a leg BELOW its cohort is a
// leg something removed bytes from, and nothing in the work unit removes bytes
// — the collector does. That window passed the falls gate (`falls === 0`) all
// the same, which is a bound on the gate and not a defect in this reader.
//
// ## THE POPULATION, AND THE TWO RESTRICTIONS ON IT
//
// `plan=floor` allocation records, collection-free windows only (`falls === 0`)
// — the same restriction the previous records used, INDEPENDENT OF tau.
// Nothing here reads, moves or is calibrated against tau in either direction.
//
// And `controlSlot === 'first'` wherever the position index is read, because
// under `last` and `mid` the three control windows do not precede position 0
// and "position 0" stops naming the same event. The nine `ctrlslot-rs8q6` runs
// are therefore in the corpus and out of the position tables; the report says
// so rather than dropping them silently.
//
// ## WHAT THIS IS NOT
//
// It is not a gate. No run passes or fails on it, no threshold here is a
// budget, and it launches nothing — it is a reader over records that already
// exist, exactly as `alloc_position_confound.cjs` and `alloc_level_witness.cjs`
// are.

const fs = require('node:fs');
const path = require('node:path');

const {
  windowsOf,
  controlLegsOf,
  excesses,
  worstExcessSignedB,
  ridersOf,
} = require('./alloc_position_confound.cjs');

// The band, verbatim from `rf2-csca8`'s own comparison: "1 of 23 in the same
// 1,000-1,300 B band". It is a DESCRIPTION of an already-measured population,
// not a threshold anything is adjudicated against — widening or narrowing it
// changes what this reader counts and refuses nothing either way.
const BAND_LO_B = 1000;
const BAND_HI_B = 1300;

// The bead's own narrower quotation — the observed extremes of the cluster it
// found, "1,050-1,224 B". Reported beside the band above so that the published
// `8 of 38` is re-derivable under the exact bounds it was published with.
const OBSERVED_LO_B = 1050;
const OBSERVED_HI_B = 1224;

// --- the two readings of "worst leg" ---------------------------------------

const largestPositiveB = (w) => {
  const e = excesses(w);
  if (!e.length) return null;
  return Math.max(...e);
};

const STATISTICS = {
  'signed-furthest': worstExcessSignedB,
  'largest-positive': largestPositiveB,
};

const inBand = (b, lo, hi) => b !== null && b >= lo && b <= hi;

// --- Fisher's exact test ----------------------------------------------------
//
// TWO-SIDED, by the conventional definition: the sum of the probabilities of
// every table at least as extreme as the observed one, where "at least as
// extreme" means "of probability no greater than the observed table's". That
// convention is named because the other common one — doubling the smaller tail
// — disagrees on asymmetric margins, and every margin here is asymmetric.
//
// Fisher rather than a two-proportion z, and the reason is the data: several
// cells here are 0 or 1 successes, where the normal approximation the z rests
// on is not defined (`alloc_position_confound.cjs` returns `null` for exactly
// that case). The z scores that reader publishes are NOT reproduced here and
// nothing on this page is stated in them.

const LOG_FACTORIAL = [0];
function logFactorial(n) {
  for (let i = LOG_FACTORIAL.length; i <= n; i++) {
    LOG_FACTORIAL[i] = LOG_FACTORIAL[i - 1] + Math.log(i);
  }
  return LOG_FACTORIAL[n];
}

function tableProbability(a, b, c, d) {
  return Math.exp(
    logFactorial(a + b) +
      logFactorial(c + d) +
      logFactorial(a + c) +
      logFactorial(b + d) -
      logFactorial(a + b + c + d) -
      logFactorial(a) -
      logFactorial(b) -
      logFactorial(c) -
      logFactorial(d)
  );
}

function fisherExactTwoSided(a, b, c, d) {
  if (a + b === 0 || c + d === 0 || a + c === 0 || b + d === 0) return 1;
  const n = a + b + c + d;
  const row1 = a + b;
  const col1 = a + c;
  const observed = tableProbability(a, b, c, d);
  // A strict `<=` on floating point drops tables that are equiprobable with the
  // observed one by symmetry, which is how a two-sided p silently becomes
  // one-sided on a balanced margin. The tolerance is what keeps them in.
  const ceiling = observed * (1 + 1e-9);
  let p = 0;
  const lo = Math.max(0, col1 - (n - row1));
  const hi = Math.min(row1, col1);
  for (let x = lo; x <= hi; x++) {
    const q = tableProbability(x, row1 - x, col1 - x, n - row1 - col1 + x);
    if (q <= ceiling) p += q;
  }
  return Math.min(1, p);
}

// --- the census -------------------------------------------------------------

const CELL_KEY = (w) => `${w.segOrder}|${w.segment}|pos${w.position}`;

function analyse(datasets) {
  const all = datasets.flatMap((d) => windowsOf(d.data, d.id));
  const clean = all.filter((w) => w.falls === 0);
  // The position index only names the same event under `first`. See the header.
  const positional = clean.filter((w) => w.controlSlot === 'first');
  const otherSlots = clean.length - positional.length;

  const out = {
    windows: all.length,
    collectionFree: clean.length,
    positional: positional.length,
    excludedOtherSlot: otherSlots,
    runs: [...new Set(all.map((w) => w.runId))].length,
    modes: [...new Set(all.map((w) => w.segOrder))].sort(),
    byStatistic: {},
  };

  for (const [name, statistic] of Object.entries(STATISTICS)) {
    const hit = (w, lo = BAND_LO_B, hi = BAND_HI_B) =>
      inBand(statistic(w), lo, hi);

    const cells = {};
    for (const w of positional) {
      const k = CELL_KEY(w);
      cells[k] = cells[k] || { key: k, segOrder: w.segOrder, segment: w.segment, position: w.position, windows: 0, band: 0, observed: 0, values: [] };
      cells[k].windows++;
      if (hit(w)) {
        cells[k].band++;
        cells[k].values.push({ run: w.runId, round: w.round, bytes: statistic(w) });
      }
      if (hit(w, OBSERVED_LO_B, OBSERVED_HI_B)) cells[k].observed++;
    }

    // Which LEG carries the in-band excess. This is what shows the pooled
    // parity population is not one term: see the report's own note.
    const ordinals = {};
    for (const w of positional) {
      const k = CELL_KEY(w);
      ordinals[k] = ordinals[k] || [0, 0, 0, 0, 0, 0];
      excesses(w).forEach((b, ordinal) => {
        if (!inBand(b, BAND_LO_B, BAND_HI_B)) return;
        while (ordinals[k].length <= ordinal) ordinals[k].push(0);
        ordinals[k][ordinal]++;
      });
    }

    const pick = (mode, segment, position) =>
      positional.filter(
        (w) =>
          (mode === null || w.segOrder === mode) &&
          (segment === null || w.segment === segment) &&
          (position === null || w.position === position)
      );
    // THE COMPARISONS ARE RUN UNDER BOTH BANDS, and that is load-bearing
    // rather than generous. The wide 1,000-1,300 B band is the one the bead
    // states its parity comparison in; the narrow 1,050-1,224 B band is the
    // one it defines the CLUSTER by. On this corpus they do not answer the
    // same question, because the wide band admits a second and different term
    // — see the ordinal table, where `parity | uix-subs | pos0`'s in-band legs
    // sit at the LAST leg ordinal and the `fixed` cluster's never do. A record
    // that quoted one band without the other would read as having settled
    // something it had not.
    const bands = [
      { name: `${BAND_LO_B}-${BAND_HI_B}`, lo: BAND_LO_B, hi: BAND_HI_B },
      { name: `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, lo: OBSERVED_LO_B, hi: OBSERVED_HI_B },
    ];
    const comparisons = {};
    for (const band of bands) {
      const inThis = (w) => inBand(statistic(w), band.lo, band.hi);
      const count = (ws) => ({ k: ws.filter(inThis).length, n: ws.length });
      const compare = (label, a, b) => {
        const x = count(a);
        const y = count(b);
        return { label, a: x, b: y, p: fisherExactTwoSided(x.k, x.n - x.k, y.k, y.n - y.k) };
      };
      comparisons[band.name] = [
        compare('POSITION  | parity, uix-subs, pos0 vs pos1', pick('parity', 'uix-subs', 0), pick('parity', 'uix-subs', 1)),
        compare('POSITION  | parity, reagent-subs, pos0 vs pos1', pick('parity', 'reagent-subs', 0), pick('parity', 'reagent-subs', 1)),
        compare('POSITION  | fixed, pos0 vs pos1 (substrate-confounded)', pick('fixed', null, 0), pick('fixed', null, 1)),
        compare('SUBSTRATE | parity pos0, uix vs reagent', pick('parity', 'uix-subs', 0), pick('parity', 'reagent-subs', 0)),
        compare('SUBSTRATE | parity pooled, uix vs reagent', pick('parity', 'uix-subs', null), pick('parity', 'reagent-subs', null)),
        compare('MODE      | uix at pos1, fixed vs parity', pick('fixed', 'uix-subs', 1), pick('parity', 'uix-subs', 1)),
        compare('MODE      | reagent at pos0, fixed vs parity', pick('fixed', 'reagent-subs', 0), pick('parity', 'reagent-subs', 0)),
      ];
    }

    out.byStatistic[name] = {
      cells: Object.values(cells).sort((p, q) => p.key.localeCompare(q.key)),
      ordinals,
      comparisons,
    };
  }

  // THE NULL ARM. The control windows run the same machinery, the same sampler
  // and the same round schedule as the arms and dispatch nothing, so a term
  // found in them would be the instrument's and not the arm's.
  const controlLegs = datasets
    .flatMap((d) => controlLegsOf(d.data, d.id))
    .flatMap((c) => (c.legs || []).map((x) => x - c.legMedian));
  out.control = {
    legs: controlLegs.length,
    band: controlLegs.filter((b) => inBand(b, BAND_LO_B, BAND_HI_B)).length,
  };

  // AND THE READER'S OWN CONTROL: the published 14-run parity figures. If this
  // reader's window extraction has drifted, these move.
  const published = clean.filter((w) => /^(workcount-n1b9h|alloc-9jrhi)\//.test(w.runId));
  out.readerControl = {
    windows: published.length,
    pos0: published.filter((w) => w.position === 0).length,
    pos1: published.filter((w) => w.position === 1).length,
    riderLegsPos0: published.filter((w) => w.position === 0).flatMap(ridersOf).length,
    riderLegsPos1: published.filter((w) => w.position === 1).flatMap(ridersOf).length,
  };

  return out;
}

// --- printing ---------------------------------------------------------------

const rate = (k, n) => (n ? `${((100 * k) / n).toFixed(1)}%` : 'n/a');
const pStr = (p) => (p >= 0.001 ? p.toFixed(4) : p.toExponential(2));

function report(a) {
  const L = [];
  L.push('THE ~1,000-1,300 B CLUSTER, AND WHAT CARRIES IT (rf2-csca8)');
  L.push('');
  L.push(`runs ${a.runs} | arm windows ${a.windows} | collection-free ${a.collectionFree} | ` +
    `in the position tables ${a.positional} (excluded, controlSlot != first: ${a.excludedOtherSlot})`);
  L.push(`segment-order modes present: ${a.modes.join(', ')}`);
  L.push('');
  L.push(`READER CONTROL — the published 14-run parity figures: ${a.readerControl.windows} collection-free ` +
    `windows, ${a.readerControl.pos0} / ${a.readerControl.pos1} by position, ` +
    `${a.readerControl.riderLegsPos0} + ${a.readerControl.riderLegsPos1} rider legs.`);
  L.push('  (published: 387 windows, 182 / 205, 101 + 11)');
  L.push('');
  L.push(`NULL ARM — control legs ${a.control.legs}, of which in the ${BAND_LO_B}-${BAND_HI_B} B band: ${a.control.band}`);
  L.push('');

  for (const [name, s] of Object.entries(a.byStatistic)) {
    L.push(`=== worst leg read as ${name.toUpperCase()} ===`);
    L.push('');
    L.push(`  ${'mode | segment | position'.padEnd(34)}${'windows'.padStart(9)}${`in ${BAND_LO_B}-${BAND_HI_B}`.padStart(14)}${'rate'.padStart(8)}${`in ${OBSERVED_LO_B}-${OBSERVED_HI_B}`.padStart(14)}`);
    for (const c of s.cells) {
      L.push(`  ${c.key.replace(/\|/g, ' | ').padEnd(34)}${String(c.windows).padStart(9)}${String(c.band).padStart(14)}${rate(c.band, c.windows).padStart(8)}${String(c.observed).padStart(14)}`);
    }
    L.push('');
    L.push('  IN-BAND LEGS BY ORDINAL (a leg count, not a window count):');
    for (const c of s.cells) {
      const o = s.ordinals[c.key] || [];
      L.push(`    ${c.key.replace(/\|/g, ' | ').padEnd(34)}[${o.join(', ')}]`);
    }
    L.push('');
    for (const [band, cmps] of Object.entries(s.comparisons)) {
      L.push(`  FISHER EXACT, TWO-SIDED — band ${band} B:`);
      for (const cmp of cmps) {
        L.push(`    ${cmp.label.padEnd(52)}${`${cmp.a.k}/${cmp.a.n}`.padStart(9)} vs ${`${cmp.b.k}/${cmp.b.n}`.padEnd(9)} p = ${pStr(cmp.p)}`);
      }
      L.push('');
    }
  }
  return L;
}

// --- the self-test ----------------------------------------------------------
//
// Synthetic fixtures for the machinery, and a pin on the committed corpus for
// the figures a record quotes. The synthetic half is what makes the pins
// DISCRIMINATING rather than merely restating: the two worst-leg readings are
// required to DISAGREE on the window that separates them, and Fisher is
// required to miss the values the two-proportion z would give.

const synthWindow = (segment, legs, extra = {}) => ({
  segment,
  arm: 'grid/floor',
  rung: 'floor',
  falls: 0,
  legs,
  legMedian: legs.slice().sort((x, y) => x - y)[legs.length >> 1],
  certified: true,
  ...extra,
});

function synthDataset(segOrder, rounds) {
  return {
    alloc: {
      plan: { name: 'floor', arms: true, rungs: false, fits: false },
      segOrder,
      controlSlot: 'first',
      boundaries: 24,
      perRound: rounds.map((r, i) => ({
        round: i,
        controls: { idle: { falls: 0, legs: [100, 100, 100], legMedian: 100 } },
        arms: r,
      })),
    },
  };
}

function selfTest() {
  const checks = [];
  const ok = (name, pass) => checks.push([pass, name]);

  // --- Fisher, against values computable by hand --------------------------
  // The 2x2 [[1,0],[0,1]] is the smallest table with both margins fixed at 1;
  // every table is as extreme as every other, so p = 1 exactly.
  ok('fisher: [[1,0],[0,1]] is p = 1', Math.abs(fisherExactTwoSided(1, 0, 0, 1) - 1) < 1e-12);
  // Tea-tasting, the canonical worked example: 3/4 against 1/4 gives 0.4857.
  ok('fisher: the tea-tasting table gives 0.4857', Math.abs(fisherExactTwoSided(3, 1, 1, 3) - 0.485714285714) < 1e-9);
  // 0 of 5 against 5 of 5 is the extreme table on balanced margins: p = 1/126.
  ok('fisher: [[0,5],[5,0]] is 2/252', Math.abs(fisherExactTwoSided(0, 5, 5, 0) - 2 / 252) < 1e-12);
  ok('fisher: an empty margin returns 1 rather than NaN', fisherExactTwoSided(0, 0, 3, 4) === 1);
  // The 1/20-vs-1/23 cell the earlier triage read as "no position effect".
  ok('fisher: 1/20 against 1/23 is p = 1', Math.abs(fisherExactTwoSided(1, 19, 1, 22) - 1) < 1e-9);
  // AND IT DISCRIMINATES: the same counts under a pooled two-proportion z give
  // z = -0.0699, which is not a p-value at all and must not be mistaken for one.
  ok('fisher: 9/38 vs 2/43 reproduces 0.0204 and not the z', Math.abs(fisherExactTwoSided(9, 29, 2, 41) - 0.0204) < 5e-4);

  // --- the two worst-leg readings, and the window that separates them ------
  // `fixed-2` round 4's excess vector, verbatim.
  const separator = synthWindow('uix-subs', [19872, 19860, 19872, 15548, 20928, 19872]);
  ok('worst leg: signed-furthest takes the -4,324 B leg', worstExcessSignedB(separator) === -4324);
  ok('worst leg: largest-positive takes the +1,056 B leg', largestPositiveB(separator) === 1056);
  ok('worst leg: the two readings disagree about the band', inBand(largestPositiveB(separator), BAND_LO_B, BAND_HI_B) && !inBand(worstExcessSignedB(separator), BAND_LO_B, BAND_HI_B));

  // --- the census, on a dataset whose answer is known by construction ------
  const planted = synthDataset('fixed', [
    { 'reagent-subs|grid/floor': synthWindow('reagent-subs', [1000, 1000, 1000, 1000, 1000, 1000]), 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 2280, 1000]) },
    { 'reagent-subs|grid/floor': synthWindow('reagent-subs', [1000, 1000, 1000, 1000, 1000, 1000]), 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 1000, 1000]) },
  ]);
  const a = analyse([{ id: 'planted-1', data: planted }]);
  const cells = a.byStatistic['largest-positive'].cells;
  const uix = cells.find((c) => c.key === 'fixed|uix-subs|pos1');
  const reagent = cells.find((c) => c.key === 'fixed|reagent-subs|pos0');
  ok('census: the planted uix window is counted at position 1', uix && uix.band === 1 && uix.windows === 2);
  ok('census: the unplanted reagent windows are not counted', reagent && reagent.band === 0 && reagent.windows === 2);
  // AND THE TWO BANDS ARE DISCRIMINATED: +1,280 B is inside 1,000-1,300 and
  // outside the bead's quoted 1,050-1,224, so a reader that collapsed the two
  // bands would report `observed` as 1 here.
  ok('census: the +1,280 B plant is in the band and outside the observed range', uix && uix.observed === 0);

  // --- the falls gate is respected ----------------------------------------
  const collected = synthDataset('fixed', [
    { 'reagent-subs|grid/floor': synthWindow('reagent-subs', [1000, 1000, 1000, 1000, 1000, 1000]), 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 2100, 1000], { falls: 1 }) },
  ]);
  const b = analyse([{ id: 'planted-2', data: collected }]);
  const bUix = b.byStatistic['largest-positive'].cells.find((c) => c.key === 'fixed|uix-subs|pos1');
  ok('falls gate: a collection-carrying window is out of the population', !bUix);

  // --- the committed corpus, pinned -------------------------------------
  // These are the figures a record quotes. They are pinned as literals so that
  // a change to the extraction reds here rather than drifting into prose.
  let corpusFiles = [];
  try {
    corpusFiles = corpus();
  } catch (e) {
    corpusFiles = [];
  }
  if (corpusFiles.length) {
    const c = analyse(corpusFiles.map(load).filter(isFloorAlloc));
    ok('corpus: the reader control reproduces the published 387 / 182 / 205', c.readerControl.windows === 387 && c.readerControl.pos0 === 182 && c.readerControl.pos1 === 205);
    ok('corpus: the reader control reproduces the published 101 + 11 rider legs', c.readerControl.riderLegsPos0 === 101 && c.readerControl.riderLegsPos1 === 11);
    ok('corpus: the null arm carries nothing in the band', c.control.band === 0);
    const find = (stat, key) => c.byStatistic[stat].cells.find((x) => x.key === key);
    ok('corpus: fixed|uix|pos1 reads 8 of 38 signed-furthest', (find('signed-furthest', 'fixed|uix-subs|pos1') || {}).band === 8);
    ok('corpus: fixed|uix|pos1 reads 9 of 38 largest-positive', (find('largest-positive', 'fixed|uix-subs|pos1') || {}).band === 9);
    ok('corpus: fixed|reagent|pos0 reads 0 of 43', (find('signed-furthest', 'fixed|reagent-subs|pos0') || {}).band === 0);
    ok('corpus: parity|uix|pos0 reads 45 of 747', (find('signed-furthest', 'parity|uix-subs|pos0') || {}).band === 45 && find('signed-furthest', 'parity|uix-subs|pos0').windows === 747);
    ok('corpus: parity|uix|pos1 reads 3 of 724', (find('signed-furthest', 'parity|uix-subs|pos1') || {}).band === 3 && find('signed-furthest', 'parity|uix-subs|pos1').windows === 724);
    ok('corpus: parity|reagent|pos0 reads 15 of 675 — the substrate NECESSITY claim fails here', (find('signed-furthest', 'parity|reagent-subs|pos0') || {}).band === 15 && find('signed-furthest', 'parity|reagent-subs|pos0').windows === 675);
    ok('corpus: parity|reagent|pos1 reads 0 of 913', (find('signed-furthest', 'parity|reagent-subs|pos1') || {}).band === 0);
    // The ordinal structure, which is what bounds the pooled parity result.
    const ord = c.byStatistic['signed-furthest'].ordinals;
    ok('corpus: parity|uix|pos0 in-band legs sit at ordinal 5 (43 of 48)', ord['parity|uix-subs|pos0'][5] === 43);
    ok('corpus: fixed|uix|pos1 carries NO in-band leg at ordinal 5', ord['fixed|uix-subs|pos1'][5] === 0);

    // THE PUBLISHED p-VALUES, and the pin DISCRIMINATES between the two bands
    // rather than restating one of them: the position verdict FLIPS across
    // them, which is the whole methodological finding of the record.
    const cmp = (stat, band, label) =>
      c.byStatistic[stat].comparisons[band].find((x) => x.label.startsWith(label));
    const wideP = cmp('signed-furthest', `${BAND_LO_B}-${BAND_HI_B}`, 'POSITION  | parity, uix').p;
    const narrowP = cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'POSITION  | parity, uix').p;
    ok('corpus: POSITION under the wide band is p < 1e-9', wideP < 1e-9);
    ok('corpus: POSITION under the bead band is p = 0.225', Math.abs(narrowP - 0.225) < 5e-3);
    ok('corpus: the two bands DISAGREE about position', wideP < 0.001 && narrowP > 0.05);
    ok('corpus: MODE under the bead band is p < 1e-8',
      cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'MODE      | uix at pos1').p < 1e-8);
    ok('corpus: SUBSTRATE is not NECESSARY — parity reagent carries 2 in the bead band',
      cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'SUBSTRATE | parity pooled').b.k === 2);
  } else {
    ok('corpus: data directory present', false);
  }

  return checks;
}

// --- entry ------------------------------------------------------------------

const DATA = path.join(__dirname, 'data');

function corpus() {
  const out = [];
  for (const dir of fs.readdirSync(DATA)) {
    const full = path.join(DATA, dir);
    if (!fs.statSync(full).isDirectory()) continue;
    for (const f of fs.readdirSync(full)) {
      if (f.endsWith('.json')) out.push(path.join(full, f));
    }
  }
  return out;
}

function load(p) {
  const data = JSON.parse(fs.readFileSync(p, 'utf8'));
  return { id: path.relative(DATA, p).replace(/\\/g, '/').replace(/\.json$/, ''), data };
}

const isFloorAlloc = (d) => {
  const row = d.data.alloc;
  return !!row && row.plan && row.plan.name === 'floor' && Array.isArray(row.perRound);
};

if (require.main === module) {
  const argv = process.argv.slice(2);
  if (argv.includes('--self-test')) {
    const checks = selfTest();
    for (const [pass, name] of checks) console.log(`${pass ? 'ok  ' : 'FAIL'} ${name}`);
    const bad = checks.filter(([p]) => !p).length;
    console.log(`alloc_cluster_carrier self-test: ${checks.length - bad}/${checks.length} passed`);
    process.exit(bad ? 1 : 0);
  }
  const files = argv.includes('--corpus') ? corpus() : argv;
  if (!files.length) {
    console.error('usage: alloc_cluster_carrier.cjs <dataset.json>... | --corpus | --self-test');
    process.exit(2);
  }
  const datasets = files.map(load).filter(isFloorAlloc);
  if (!datasets.length) {
    console.error('no plan=floor allocation record among the files given');
    process.exit(2);
  }
  for (const line of report(analyse(datasets))) console.log(line);
}

module.exports = {
  analyse,
  report,
  selfTest,
  fisherExactTwoSided,
  largestPositiveB,
  BAND_LO_B,
  BAND_HI_B,
  OBSERVED_LO_B,
  OBSERVED_HI_B,
};
