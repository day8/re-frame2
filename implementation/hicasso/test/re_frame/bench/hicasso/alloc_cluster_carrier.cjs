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
// ## ADMISSIBILITY, WHICH FAILS CLOSED
//
// **A FAILED POSITIVE CONTROL IS NOT AN OBSERVATION.** Every floor record
// carries `alloc.controlVerdict`, the verdict of its own positive control. A
// control that did not certify says the INSTRUMENT was not reading correctly
// during that run, so no window from it is evidence about anything.
//
// An earlier version of this reader filtered on `plan.name === 'floor'` alone
// and never asked, so two control-refused runs contributed 50 collection-free
// windows to every census here: `alloc-77gz8/run12-a4a1537cb71` and
// `alloc-9jrhi/bisect-5-a-4a1537cb71-replicate`. The second is the exact run
// that `the-eight-signs-are-one-block.md` already excludes on the same corpus,
// so the tree contradicted itself about one dataset. `admit()` below closes
// that, and `partition()` NAMES every refusal rather than dropping it.
//
// The corrected corpus is 116 runs / 3,258 collection-free / 3,090 positional,
// against 118 / 3,308 / 3,140 before. Every primary-band numerator is unchanged
// and every denominator moves.
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

// --- admissibility ----------------------------------------------------------

// Is this a plan=floor allocation record at all? This is a question about the
// SHAPE of the dataset, and it is deliberately separate from the one below.
const isFloorAlloc = (d) => {
  const row = d.data && d.data.alloc;
  return !!row && row.plan && row.plan.name === 'floor' && Array.isArray(row.perRound);
};

// Did the run's own positive control certify? A record with no verdict at all
// is refused on the same principle: an unasserted control is not a passed one.
function admit(d) {
  if (!isFloorAlloc(d)) return { ok: false, why: 'not a plan=floor allocation record' };
  const cv = d.data.alloc.controlVerdict;
  if (!cv || cv.ok !== true) return { ok: false, why: 'control refused' };
  return { ok: true };
}

// Split a loaded corpus into what may be read and what may not, NAMING every
// refusal. Callers pass `excluded` into `analyse` so the report can print it —
// a census that dropped runs silently is the defect this reader was corrected
// for.
function partition(datasets) {
  const admitted = [];
  const excluded = [];
  for (const d of datasets) {
    const v = admit(d);
    if (v.ok) admitted.push(d);
    else excluded.push({ id: d.id, why: v.why });
  }
  return { admitted, excluded };
}

// `opts.extractionSet` is the population the READER CONTROL is computed over,
// and it defaults to the admitted set. The corpus entry point passes the
// PRE-ADMISSIBILITY floor set instead, deliberately: that control exists to
// detect drift in the window extraction by reproducing a figure an earlier
// record published, and the earlier record counted all fourteen runs. Applying
// admissibility to it would compare against a number nobody ever published and
// turn a cross-check into a restatement of this reader's own output. Both
// counts are reported.
function analyse(datasets, opts = {}) {
  const excluded = opts.excluded || [];
  const extractionSet = opts.extractionSet || datasets;
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
    excluded,
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
        // --- THE REVERSED ARM (rf2-csca8) ---------------------------------
        //
        // These four rows were added to this reader BEFORE the first
        // `fixed-reversed` record existed, so what they compute was fixed in
        // advance of what they would find. Under `fixed-reversed` the plan is
        // driven reversed every round: the mode is held constant and `uix-subs`
        // moves to position 0, which is the one arrangement no committed run
        // supplied.
        //
        // CARRIER is the PRIMARY row and the only WITHIN-RUN one on this list.
        // Both of its cells come from the same runs, the same session and the
        // same mode, so every per-run term — the box that minute, the revision,
        // the level the floor settled at — is held constant by construction
        // rather than by matching. The three rows under it are between-mode and
        // carry the repeated-measures bound every other row here carries.
        compare('CARRIER   | fixed-reversed, uix pos0 vs reagent pos1', pick('fixed-reversed', 'uix-subs', 0), pick('fixed-reversed', 'reagent-subs', 1)),
        compare('FOLLOWS   | uix, fixed pos1 vs fixed-reversed pos0', pick('fixed', 'uix-subs', 1), pick('fixed-reversed', 'uix-subs', 0)),
        compare('STAYS     | pos1, fixed uix vs fixed-reversed reagent', pick('fixed', 'uix-subs', 1), pick('fixed-reversed', 'reagent-subs', 1)),
        compare('MODE      | uix pooled, fixed-reversed vs parity', pick('fixed-reversed', 'uix-subs', null), pick('parity', 'uix-subs', null)),
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

  // --- THE LEVEL EACH RUN SETTLED AT (rf2-csca8) --------------------------
  //
  // The floor arm at this configuration is MULTI-MODAL — see
  // `the-second-mode-a-pre-registered-twenty-run-window.md`, which pre-registered
  // this estimator and this criterion, and both are reused here verbatim rather
  // than re-invented: the MEDIAN, over CERTIFIED windows at ROUND INDEX >= 6, of
  // that window's `legMedian`, per segment; HIGH MODE is either segment at or
  // above 21,000 B/write.
  //
  // IT IS A READ AND NOT A FILTER. Nothing here excludes a run, and no census
  // above consults it. Its job is to make a level excursion VISIBLE before a
  // comparative is quoted over it, because a window whose arms separate on level
  // rather than on the property under test has found something about the box and
  // not about the property — and an unexplained excursion reported plainly is
  // worth more than a clean-looking number taken over it.
  const LEVEL_FROM_ROUND = 6;
  const HIGH_MODE_B = 21000;
  const median = (xs) => {
    const s = xs.slice().sort((x, y) => x - y);
    return s.length ? s[s.length >> 1] : null;
  };
  const levels = {};
  for (const w of all) {
    if (!w.certified || w.round < LEVEL_FROM_ROUND) continue;
    const r = (levels[w.runId] = levels[w.runId] || { runId: w.runId, segOrder: w.segOrder, segments: {} });
    (r.segments[w.segment] = r.segments[w.segment] || []).push(w.legMedian);
  }
  out.levels = Object.values(levels)
    .map((r) => {
      const per = {};
      for (const [seg, xs] of Object.entries(r.segments)) per[seg] = { n: xs.length, median: median(xs) };
      const highest = Math.max(...Object.values(per).map((x) => x.median));
      return { runId: r.runId, segOrder: r.segOrder, per, highMode: highest >= HIGH_MODE_B };
    })
    .sort((p, q) => p.runId.localeCompare(q.runId));

  // AND THE READER'S OWN CONTROL: the published 14-run parity figures. If this
  // reader's window extraction has drifted, these move. Computed over
  // `extractionSet` — see the note on `analyse` — because the figure it checks
  // against was published over all fourteen runs, one of which is now
  // inadmissible. The admissible count is reported beside it so neither number
  // has to be inferred.
  const extractionWindows = extractionSet
    .flatMap((d) => windowsOf(d.data, d.id))
    .filter((w) => w.falls === 0);
  const pub = (ws) => ({
    windows: ws.length,
    pos0: ws.filter((w) => w.position === 0).length,
    pos1: ws.filter((w) => w.position === 1).length,
    riderLegsPos0: ws.filter((w) => w.position === 0).flatMap(ridersOf).length,
    riderLegsPos1: ws.filter((w) => w.position === 1).flatMap(ridersOf).length,
  });
  const isPublished = (w) => /^(workcount-n1b9h|alloc-9jrhi)\//.test(w.runId);
  out.readerControl = pub(extractionWindows.filter(isPublished));
  out.readerControlAdmitted = pub(clean.filter(isPublished));

  // --- RUN-LEVEL COUNTS, which is what bounds every Fisher p below ---------
  //
  // Fisher treats each window as an independent trial. They are not: a run
  // contributes many windows, and any per-run term — the box's state that
  // minute, the revision, the session — is shared by all of them. So the
  // run-level census is published beside every window-level one, and it is the
  // honest denominator for a claim about the MODE, whose whole `fixed`
  // exposure is three runs in a single session.
  const runLevel = {};
  for (const [name, statistic] of Object.entries(STATISTICS)) {
    runLevel[name] = {};
    for (const band of [{ n: `${BAND_LO_B}-${BAND_HI_B}`, lo: BAND_LO_B, hi: BAND_HI_B },
      { n: `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, lo: OBSERVED_LO_B, hi: OBSERVED_HI_B }]) {
      const cells = {};
      for (const w of positional) {
        const k = CELL_KEY(w);
        cells[k] = cells[k] || { key: k, runs: {}, };
        const r = (cells[k].runs[w.runId] = cells[k].runs[w.runId] || { n: 0, k: 0 });
        r.n++;
        if (inBand(statistic(w), band.lo, band.hi)) r.k++;
      }
      runLevel[name][band.n] = Object.values(cells).map((c) => {
        const rs = Object.values(c.runs);
        return {
          key: c.key,
          runs: rs.length,
          runsWithHit: rs.filter((r) => r.k > 0).length,
          windows: rs.reduce((s, r) => s + r.n, 0),
          band: rs.reduce((s, r) => s + r.k, 0),
          maxPerRun: rs.reduce((m, r) => Math.max(m, r.k), 0),
        };
      }).sort((p, q) => p.key.localeCompare(q.key));
    }
  }
  out.runLevel = runLevel;

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
  if (a.excluded.length) {
    L.push('INADMISSIBLE, and named rather than dropped — a failed positive control is not an observation:');
    for (const e of a.excluded) L.push(`  ${e.id}  (${e.why})`);
    L.push('');
  }
  L.push(`READER CONTROL — the published 14-run parity figures: ${a.readerControl.windows} collection-free ` +
    `windows, ${a.readerControl.pos0} / ${a.readerControl.pos1} by position, ` +
    `${a.readerControl.riderLegsPos0} + ${a.readerControl.riderLegsPos1} rider legs.`);
  L.push('  (published: 387 windows, 182 / 205, 101 + 11)');
  L.push('  ^ computed BEFORE admissibility, deliberately: it checks this reader against a figure');
  L.push('    an earlier record published over all 14 runs. On the admissible 13 it reads ' +
    `${a.readerControlAdmitted.windows} windows, ${a.readerControlAdmitted.pos0} / ${a.readerControlAdmitted.pos1}, ` +
    `${a.readerControlAdmitted.riderLegsPos0} + ${a.readerControlAdmitted.riderLegsPos1} rider legs.`);
  L.push('');
  L.push(`NULL ARM — control legs ${a.control.legs}, of which in the ${BAND_LO_B}-${BAND_HI_B} B band: ${a.control.band}`);
  L.push('');

  // THE LEVEL READ, printed BEFORE any comparative below it, deliberately.
  if (a.levels && a.levels.length) {
    const byMode = {};
    for (const r of a.levels) {
      byMode[r.segOrder] = byMode[r.segOrder] || { runs: 0, high: 0 };
      byMode[r.segOrder].runs++;
      if (r.highMode) byMode[r.segOrder].high++;
    }
    L.push('LEVEL — median legMedian over certified windows at round >= 6, per segment.');
    L.push('  High mode is either segment at or above 21,000 B/write, the criterion');
    L.push('  `the-second-mode-a-pre-registered-twenty-run-window.md` pre-registered. A READ, NOT A FILTER:');
    L.push('  no census here consults it and no run is excluded on it.');
    for (const [mode, s] of Object.entries(byMode).sort()) {
      L.push(`    ${mode.padEnd(16)}${String(s.runs).padStart(5)} runs, ${String(s.high).padStart(4)} high mode`);
    }
    // The per-run rows, for the segment-order windows only: a directory that
    // carries a non-`parity` mode is a MATCHED window, and its arms' levels are
    // exactly what a comparative between them can be confounded by.
    const dirsWithModes = new Set(
      a.levels.filter((r) => r.segOrder !== 'parity').map((r) => r.runId.split('/')[0])
    );
    const rows = a.levels.filter((r) => dirsWithModes.has(r.runId.split('/')[0]));
    if (rows.length) {
      L.push('');
      L.push('  PER RUN, for the matched segment-order windows:');
      const segs = [...new Set(rows.flatMap((r) => Object.keys(r.per)))].sort();
      L.push(`    ${'run'.padEnd(30)}${'mode'.padEnd(16)}${segs.map((s) => s.padStart(14)).join('')}   level`);
      for (const r of rows) {
        const cells = segs.map((s) => String((r.per[s] || {}).median ?? '-').padStart(14)).join('');
        L.push(`    ${r.runId.padEnd(30)}${r.segOrder.padEnd(16)}${cells}   ${r.highMode ? 'HIGH' : 'low'}`);
      }
    }
    L.push('');
  }

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
    L.push('  RUN-LEVEL COUNTS — the denominator every p below actually has:');
    L.push(`    ${'mode | segment | position'.padEnd(34)}${'runs'.padStart(6)}${'w/ a hit'.padStart(10)}${'in band'.padStart(9)}${'max/run'.padStart(9)}`);
    for (const r of a.runLevel[name][`${OBSERVED_LO_B}-${OBSERVED_HI_B}`]) {
      L.push(`    ${r.key.replace(/\|/g, ' | ').padEnd(34)}${String(r.runs).padStart(6)}${String(r.runsWithHit).padStart(10)}${String(r.band).padStart(9)}${String(r.maxPerRun).padStart(9)}`);
    }
    L.push('');
    for (const [band, cmps] of Object.entries(s.comparisons)) {
      L.push(`  FISHER EXACT, TWO-SIDED — band ${band} B:`);
      for (const cmp of cmps) {
        L.push(`    ${cmp.label.padEnd(52)}${`${cmp.a.k}/${cmp.a.n}`.padStart(9)} vs ${`${cmp.b.k}/${cmp.b.n}`.padEnd(9)} p = ${pStr(cmp.p)}`);
      }
      L.push('');
    }
    L.push('  WHAT THESE p-VALUES ARE NOT. Fisher counts each WINDOW as an independent trial.');
    L.push('  Windows repeat within runs, so any per-run term — the box that minute, the');
    L.push('  revision, the session — is shared across a whole row of them. Read the run-level');
    L.push('  table above beside every p: the entire `fixed` exposure is THREE runs in ONE');
    L.push('  session. A window-level p is an association at the window level and NOT a test of');
    L.push('  a hypothesis about modes, substrates or positions as properties of a run.');
    L.push('');
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

function synthDataset(segOrder, rounds, over = {}) {
  return {
    alloc: {
      plan: { name: 'floor', arms: true, rungs: false, fits: false },
      // A synthetic record is admissible by default; the admissibility fixtures
      // below override this to exercise the refusals.
      controlVerdict: { ok: true },
      segOrder,
      controlSlot: 'first',
      boundaries: 24,
      ...over,
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

  // --- ADMISSIBILITY, both shapes, on synthetic records -------------------
  // Pure-function fixtures, so they hold even if the corpus one day carries
  // neither shape; the corpus pins below then check the rule reaches the data.
  const clean1 = { id: 'synth-clean', data: synthDataset('fixed', [{ 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 1000, 1000]) }]) };
  const refused1 = { id: 'synth-refused', data: synthDataset('fixed', [{ 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 2100, 1000]) }], { controlVerdict: { ok: false, perDouble: 11.8, differential: 14.2 } }) };
  const noVerdict = { id: 'synth-no-verdict', data: synthDataset('fixed', [{ 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 1000, 1000]) }], { controlVerdict: undefined }) };
  const notFloor = { id: 'synth-not-floor', data: { alloc: { plan: { name: 'ladder' }, controlVerdict: { ok: true }, perRound: [] } } };

  ok('admit: a record with a passing control is admitted', admit(clean1).ok === true);
  ok('admit: a REFUSED control is refused and named', admit(refused1).ok === false && admit(refused1).why === 'control refused');
  ok('admit: a MISSING control verdict is refused too', admit(noVerdict).why === 'control refused');
  ok('admit: a non-floor plan is refused with its own reason', admit(notFloor).why === 'not a plan=floor allocation record');
  ok('partition: names every refusal and admits the rest', (() => {
    const { admitted, excluded } = partition([clean1, refused1, noVerdict, notFloor]);
    return admitted.length === 1 && admitted[0].id === 'synth-clean' && excluded.length === 3 &&
      excluded.filter((e) => e.why === 'control refused').length === 2;
  })());
  // AND IT DISCRIMINATES: the refused record carries an in-band window, so a
  // reader that skipped the control check would COUNT it. The exclusion has to
  // change the census, or it is not doing anything.
  ok('partition: the refused record would have contributed an in-band window', (() => {
    const withIt = analyse([clean1, refused1]);
    const withoutIt = analyse([clean1]);
    const cell = (x) => (x.byStatistic['largest-positive'].cells.find((c) => c.key === 'fixed|uix-subs|pos0') || { band: 0, windows: 0 });
    return cell(withIt).band === 1 && cell(withoutIt).band === 0 && cell(withIt).windows === 2 && cell(withoutIt).windows === 1;
  })());
  ok('analyse: excluded ids are carried through to the report', (() => {
    const { admitted, excluded } = partition([clean1, refused1]);
    const x = analyse(admitted, { excluded });
    return x.excluded.length === 1 && x.excluded[0].id === 'synth-refused' &&
      report(x).some((l) => l.includes('synth-refused') && l.includes('control refused'));
  })());

  // --- the run-level census, on a dataset whose clustering is known -------
  // Two in-band windows in ONE run and none in the other: window-level counting
  // says 2 of 4, run-level says 1 of 2. That gap is the whole caveat.
  const clustered = { id: 'planted-clustered', data: synthDataset('fixed', [
    { 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 2100, 1000]) },
    { 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 2100, 1000]) },
  ]) };
  const unclustered = { id: 'planted-unclustered', data: synthDataset('fixed', [
    { 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 1000, 1000]) },
    { 'uix-subs|grid/floor': synthWindow('uix-subs', [1000, 1000, 1000, 1000, 1000, 1000]) },
  ]) };
  ok('run-level: two hits in one run count as ONE run with a hit', (() => {
    const x = analyse([clustered, unclustered]);
    const r = x.runLevel['largest-positive'][`${OBSERVED_LO_B}-${OBSERVED_HI_B}`].find((c) => c.key === 'fixed|uix-subs|pos0');
    return r && r.band === 2 && r.runs === 2 && r.runsWithHit === 1 && r.maxPerRun === 2;
  })());

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
    const { admitted, excluded, floor } = admissibleCorpus(corpusFiles);
    const c = analyse(admitted, { excluded, extractionSet: floor });

    // --- ADMISSIBILITY REACHES THE COMMITTED DATA ------------------------
    ok('corpus: exactly TWO floor runs are refused, and both for their control',
      excluded.length === 2 && excluded.every((e) => e.why === 'control refused'));
    ok('corpus: the refused runs are run12 and bisect-5, by name', (() => {
      const ids = excluded.map((e) => e.id).sort();
      return ids[0] === 'alloc-77gz8/run12-a4a1537cb71' &&
        ids[1] === 'alloc-9jrhi/bisect-5-a-4a1537cb71-replicate';
    })());
    // AND THE TREE NO LONGER CONTRADICTS ITSELF: bisect-5 is the run
    // `the-eight-signs-are-one-block.md` already excludes on this same corpus.
    ok('corpus: bisect-5 is refused here as it already was on the eight-signs record',
      excluded.some((e) => e.id === 'alloc-9jrhi/bisect-5-a-4a1537cb71-replicate'));
    ok('corpus: 116 admissible floor runs, of 118', admitted.length === 116 && floor.length === 118);
    ok('corpus: 3,258 collection-free and 3,090 positional windows — NOT 3,308 / 3,140',
      c.collectionFree === 3258 && c.positional === 3090);
    ok('corpus: the runs count is the admissible 116', c.runs === 116);

    // The extraction check runs BEFORE admissibility, deliberately, so it still
    // reproduces the figure the earlier record published over all 14 runs.
    ok('corpus: the reader control reproduces the published 387 / 182 / 205', c.readerControl.windows === 387 && c.readerControl.pos0 === 182 && c.readerControl.pos1 === 205);
    ok('corpus: the reader control reproduces the published 101 + 11 rider legs', c.readerControl.riderLegsPos0 === 101 && c.readerControl.riderLegsPos1 === 11);
    ok('corpus: on the ADMISSIBLE 13 the same control reads 359 / 169 / 190, 92 + 10',
      c.readerControlAdmitted.windows === 359 && c.readerControlAdmitted.pos0 === 169 &&
      c.readerControlAdmitted.pos1 === 190 && c.readerControlAdmitted.riderLegsPos0 === 92 &&
      c.readerControlAdmitted.riderLegsPos1 === 10);
    ok('corpus: the two controls DIFFER, which is what says admissibility bit', c.readerControl.windows !== c.readerControlAdmitted.windows);
    ok('corpus: the null arm carries nothing in the band', c.control.band === 0);
    const find = (stat, key) => c.byStatistic[stat].cells.find((x) => x.key === key);
    // NUMERATORS UNCHANGED, DENOMINATORS MOVED. That is the signature of this
    // repair: neither refused run carried an in-band window in the primary
    // band, so nothing the record claims positively rests on them — but every
    // rate they sat in was computed over too many windows.
    ok('corpus: fixed|uix|pos1 reads 8 of 38 signed-furthest', (find('signed-furthest', 'fixed|uix-subs|pos1') || {}).band === 8);
    ok('corpus: fixed|uix|pos1 reads 9 of 38 largest-positive', (find('largest-positive', 'fixed|uix-subs|pos1') || {}).band === 9);
    ok('corpus: fixed|reagent|pos0 reads 0 of 43', (find('signed-furthest', 'fixed|reagent-subs|pos0') || {}).band === 0);
    ok('corpus: parity|uix|pos0 reads 44 of 733 — NOT 45 of 747', (find('signed-furthest', 'parity|uix-subs|pos0') || {}).band === 44 && find('signed-furthest', 'parity|uix-subs|pos0').windows === 733);
    ok('corpus: parity|uix|pos1 reads 3 of 712 — NOT 3 of 724', (find('signed-furthest', 'parity|uix-subs|pos1') || {}).band === 3 && find('signed-furthest', 'parity|uix-subs|pos1').windows === 712);
    ok('corpus: parity|reagent|pos0 reads 15 of 666 — the substrate NECESSITY claim fails here', (find('signed-furthest', 'parity|reagent-subs|pos0') || {}).band === 15 && find('signed-furthest', 'parity|reagent-subs|pos0').windows === 666);
    ok('corpus: parity|reagent|pos1 reads 0 of 898 — NOT 0 of 913', (find('signed-furthest', 'parity|reagent-subs|pos1') || {}).band === 0 && find('signed-furthest', 'parity|reagent-subs|pos1').windows === 898);
    ok('corpus: the primary-band numerators are unchanged at 8 / 3 / 2 / 0', (() => {
      const n = (k) => (find('signed-furthest', k) || {}).observed;
      return n('fixed|uix-subs|pos1') === 8 && n('parity|uix-subs|pos1') === 3 &&
        n('parity|reagent-subs|pos0') === 2 && n('parity|reagent-subs|pos1') === 0;
    })());
    // The ordinal structure, which is what bounds the pooled parity result.
    const ord = c.byStatistic['signed-furthest'].ordinals;
    ok('corpus: parity|uix|pos0 in-band legs sit at ordinal 5 (42 of 47)', ord['parity|uix-subs|pos0'][5] === 42);
    ok('corpus: fixed|uix|pos1 carries NO in-band leg at ordinal 5', ord['fixed|uix-subs|pos1'][5] === 0);

    // --- THE RUN-LEVEL BOUND, which is why no verdict here says CONFIRMED --
    const rl = (key, band = `${OBSERVED_LO_B}-${OBSERVED_HI_B}`) =>
      c.runLevel['signed-furthest'][band].find((x) => x.key === key);
    ok('corpus: the whole `fixed` exposure is THREE runs', rl('fixed|uix-subs|pos1').runs === 3);
    ok('corpus: all three `fixed` runs carry the term, and none carries it more than four times',
      rl('fixed|uix-subs|pos1').runsWithHit === 3 && rl('fixed|uix-subs|pos1').maxPerRun === 4);
    // The parity cells are barely clustered at the primary band — at most one
    // window per run — so the repeated-measures caveat bites hardest on the
    // `fixed` arm, which is exactly the arm the strongest claim rested on.
    ok('corpus: at the primary band no parity run carries more than ONE in-band window',
      rl('parity|uix-subs|pos0').maxPerRun === 1 && rl('parity|uix-subs|pos1').maxPerRun === 1);
    ok('corpus: the parity baseline spans 107 runs against the fixed arm\'s 3',
      rl('parity|uix-subs|pos1').runs === 107);

    // THE PUBLISHED p-VALUES, and the pin DISCRIMINATES between the two bands
    // rather than restating one of them: the position verdict FLIPS across
    // them, which is the whole methodological finding of the record.
    const cmp = (stat, band, label) =>
      c.byStatistic[stat].comparisons[band].find((x) => x.label.startsWith(label));
    const wideP = cmp('signed-furthest', `${BAND_LO_B}-${BAND_HI_B}`, 'POSITION  | parity, uix').p;
    const narrowP = cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'POSITION  | parity, uix').p;
    ok('corpus: POSITION under the wide band is p < 1e-9', wideP < 1e-9);
    ok('corpus: POSITION under the bead band is p = 0.225', Math.abs(narrowP - 0.2253) < 5e-3);
    ok('corpus: the two bands DISAGREE about position', wideP < 0.001 && narrowP > 0.05);
    ok('corpus: MODE under the bead band is p < 1e-8',
      cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'MODE      | uix at pos1').p < 1e-8);
    ok('corpus: SUBSTRATE is not NECESSARY — parity reagent carries 2 in the bead band',
      cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'SUBSTRATE | parity pooled').b.k === 2);
    // THE POSITION ARM IS NOT AN EQUIVALENCE RESULT, and this pin says so in
    // the only way a number can: the point estimate is a ~2.6x odds ratio, so
    // failing to reject at p = 0.225 leaves a real position effect of that size
    // comfortably inside the interval. A record calling this REFUTED would be
    // reading a non-significant p as evidence of no difference.
    ok('corpus: the POSITION odds ratio is about 2.6, so equality is NOT established', (() => {
      const x = cmp('signed-furthest', `${OBSERVED_LO_B}-${OBSERVED_HI_B}`, 'POSITION  | parity, uix');
      const or = (x.a.k * (x.b.n - x.b.k)) / ((x.a.n - x.a.k) * x.b.k);
      return or > 2 && or < 3.5;
    })());
    // AND THE MODE ARM'S STRENGTH IS AN ARTEFACT OF POOLING. The matched
    // same-session contrast is the one that controls session, date and
    // revision, and it does not reach significance at any conventional level.
    ok('corpus: the matched same-session MODE contrast is 8/38 vs 1/23, p = 0.134', (() => {
      const seg = admitted.filter((d) => d.id.startsWith('segorder-rs8q6/'));
      const s = analyse(seg);
      const m = s.byStatistic['signed-furthest'].comparisons[`${OBSERVED_LO_B}-${OBSERVED_HI_B}`]
        .find((x) => x.label.startsWith('MODE      | uix at pos1'));
      return m.a.k === 8 && m.a.n === 38 && m.b.k === 1 && m.b.n === 23 && Math.abs(m.p - 0.1344) < 5e-4;
    })());
    // The run-level version of that same matched contrast has almost no power
    // at all: three runs against three. Pinned so a record cannot quote the
    // window-level p as though it were a statement about runs.
    ok('corpus: at RUN level the matched MODE contrast is 3 of 3 against 1 of 3, p = 0.4', (() => {
      const seg = admitted.filter((d) => d.id.startsWith('segorder-rs8q6/'));
      const s = analyse(seg);
      const at = (mode) => s.runLevel['signed-furthest'][`${OBSERVED_LO_B}-${OBSERVED_HI_B}`]
        .find((x) => x.key === `${mode}|uix-subs|pos${mode === 'fixed' ? 1 : 1}`);
      const f = at('fixed');
      const p = at('parity');
      return f.runs === 3 && f.runsWithHit === 3 && p.runs === 3 && p.runsWithHit === 1 &&
        Math.abs(fisherExactTwoSided(3, 0, 1, 2) - 0.4) < 1e-9;
    })());
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

// The whole admissible corpus, and what it refused, from one call — so no
// caller has to remember to apply the control check itself.
function admissibleCorpus(files) {
  const floor = files.map(load).filter(isFloorAlloc);
  const { admitted, excluded } = partition(floor);
  return { admitted, excluded, floor };
}

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
  const { admitted, excluded, floor } = admissibleCorpus(files);
  if (!floor.length) {
    console.error('no plan=floor allocation record among the files given');
    process.exit(2);
  }
  if (!admitted.length) {
    console.error(`every plan=floor record given was refused: ${excluded.map((e) => `${e.id} (${e.why})`).join(', ')}`);
    process.exit(2);
  }
  for (const line of report(analyse(admitted, { excluded, extractionSet: floor }))) console.log(line);
}

module.exports = {
  analyse,
  report,
  selfTest,
  admit,
  partition,
  admissibleCorpus,
  isFloorAlloc,
  fisherExactTwoSided,
  largestPositiveB,
  BAND_LO_B,
  BAND_HI_B,
  OBSERVED_LO_B,
  OBSERVED_HI_B,
};
