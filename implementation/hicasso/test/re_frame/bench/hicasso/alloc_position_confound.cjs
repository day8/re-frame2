'use strict';
// THE POSITION / SUBSTRATE CONFOUND, READ OFF A SCHEDULE THAT BREAKS IT —
// rf2-rs8q6.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_position_confound.cjs <dataset.json>...
//     node hicasso/test/re_frame/bench/hicasso/alloc_position_confound.cjs --corpus
//     node hicasso/test/re_frame/bench/hicasso/alloc_position_confound.cjs --self-test
//
// ## WHAT THIS ADJUDICATES
//
// `rf2-rs8q6` established a **+748 B rider** in the floor arm's own path: over
// 4,284 control legs the count in the 700-800 B band is ZERO, against 112 of
// 2,322 arm legs, and 101 of those 112 sit in the FIRST arm window of a round.
// What it could not establish is WHICH property of that window carries it,
// because the shipped `parity` schedule gives position-0 windows two at once:
//
//   - position 0 is the first arm window after the round's THREE CONTROLS, and
//   - position 0 REPEATS the substrate of the previous arm window, because the
//     segment order reverses on odd rounds.
//
// Those two agree in 466 of 466 adjacent pairs across the 14 committed floor
// runs. `P0_ALLOC_SEG_ORDER=fixed` (`p0_run.cjs`) drives the configured segment
// order every round, so BOTH positions follow a substrate switch while position
// 0 alone still follows the controls. This reader is what turns the resulting
// records into the three-way answer the mode was pre-registered against.
//
// ## AND THE SECOND CONFOUND, WHICH `fixed` LEFT STANDING
//
// `fixed` answered "position, not substrate" and its record named what it could
// not reach: position 0 is BOTH
//
//   (A) the first arm window after the round's THREE CONTROL WINDOWS, and
//   (B) the first arm window after the ROUND-LOOP BOUNDARY.
//
// `P0_ALLOC_CONTROL_SLOT` moves the three controls within the round, and only
// `mid` separates those two — under `last` the cyclic loop puts the previous
// round's controls immediately before position 0 again, so the confound is
// intact in every round but the first. So this reader indexes arm windows on
// the two predicates DIRECTLY, read off each round's recorded `windowOrder`,
// rather than on the position they happen to coincide with under one schedule.
// Position stays in the report beside them, because it is the index the earlier
// records are stated in.
//
// ## THE UNIT, STATED FIRST BECAUSE IT HAS ALREADY MISLED A READER
//
// `legWorstDeviation` is a **FRACTION**, not a percentage — `worst / legMedian`
// at `p0_run.cjs`. A window reported as "3.908%" carries `0.03908` in the
// field. Reading the field as a percent understates every figure 100x. This
// reader works in **absolute bytes** wherever it can for exactly that reason:
// the rider is a byte quantity (+748 B) and the sampler's own jitter is +/-36 B,
// and neither is a ratio. Where a fraction is reported it is named `Fraction`
// and the percent is derived at the point of printing, once.
//
// ## THE POPULATION, AND WHY IT IS THE FALLS GATE
//
// Collection-carrying windows are excluded, on `falls === 0` — the same
// restriction `rf2-rs8q6`'s own record used, and it is INDEPENDENT OF tau. A
// window a collector ran inside under-reads by an unknown amount, so a leg
// difference measured across it is not a difference in the work unit. Nothing
// here reads, moves or is calibrated against tau in either direction.
//
// ## THE PUBLISHED STATISTIC, AND WHY ITS CONVENTION IS WRITTEN DOWN
//
// The two records state a `z` beside each of their rate comparisons. Every one
// of them is a TWO-PROPORTION z-test on the POOLED proportion, with NO
// continuity correction and no conversion to a tail probability:
//
//     p1 = k1/n1 , p2 = k2/n2 , p = (k1 + k2)/(n1 + n2)
//     z  = (p1 - p2) / sqrt( p (1 - p) (1/n1 + 1/n2) )
//
// The convention is named rather than left implicit because THREE of them land
// within half a z of each other on these counts and only this one reproduces
// the records to the published digits: on `parity`'s 25/40 against 3/49 the
// pooled form gives 5.6975 (published 5.70), the unpooled form 6.7229 and the
// continuity-corrected form 5.4681. `selfTest` pins all ten published figures
// on their own input counts AND requires the other two forms to disagree with
// every one of them, so a change to the formula reds rather than drifting.
//
// SIGN. `z` is signed `(a - b)` with the two groups taken IN THE READER'S OWN
// CANONICAL ORDER — the order this report already lists them in, which is
// ascending by position and sorted by schedule name. The sign is therefore a
// property of the report and not of whichever sentence cites it.
//
// ## WHAT THIS IS NOT
//
// It is not a gate. No run passes or fails on it, no threshold here is a
// budget, and no published figure is computed from it. It is a reader over
// records that already exist, exactly as `alloc_level_witness.cjs` is.

const fs = require('node:fs');
const path = require('node:path');

// The rider's band, in BYTES, verbatim from `rf2-rs8q6`'s record: "the count in
// the 700-800 B band is ZERO [controls], against 112 of 2,322 arm legs". It is
// a DESCRIPTION of an already-measured population and not a threshold anything
// is adjudicated against — widening or narrowing it changes what this reader
// counts and refuses nothing either way.
const RIDER_LO_B = 700;
const RIDER_HI_B = 800;

// The sampler's own jitter, likewise measured rather than chosen: five of every
// six legs agree with their window's median to within +/-36 B. A leg inside this
// band is a leg that carries nothing.
const JITTER_B = 36;

// The SECONDARY cluster the position record filed rather than chased and
// `rf2-csca8` carries: "position 1 carries a cluster of 8 of 38 windows whose
// worst leg sits at 1,050-1,224 B". Like the rider band above this is a
// DESCRIPTION of an already-measured population, not a threshold anything is
// adjudicated against, and it is here only so the published 8 / 1 / 0 counts
// are re-derivable rather than taken on trust.
const CLUSTER_LO_B = 1050;
const CLUSTER_HI_B = 1224;

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

// The distinct values in `xs`, most frequent first and ties broken by value —
// the "modal values" column both records publish beside their rider legs.
const modal = (xs, top = 4) => {
  const counts = new Map();
  for (const x of xs) counts.set(x, (counts.get(x) || 0) + 1);
  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count || a.value - b.value)
    .slice(0, top);
};

// --- the published statistic ------------------------------------------------
//
// See the header. Pooled two-proportion z, no continuity correction, signed
// `(a - b)`. Returns `null` rather than a number wherever the statistic is not
// defined — an empty group, or a pooled proportion of 0 or 1, where the
// standard error is zero and every difference would divide to Infinity.
function twoProportionZ(k1, n1, k2, n2) {
  if (!n1 || !n2) return null;
  const p = (k1 + k2) / (n1 + n2);
  if (p <= 0 || p >= 1) return null;
  const se = Math.sqrt(p * (1 - p) * (1 / n1 + 1 / n2));
  if (!se) return null;
  return (k1 / n1 - k2 / n2) / se;
}

// --- the window sequence, IN THE ORDER THE PAGE DROVE IT --------------------
//
// Position within the round is the whole index this bead turns on, so it is
// read off the record rather than recomputed from a parity rule — and that is
// now load-bearing rather than tidy: under `fixed` the parity rule is WRONG,
// and a reader that recomputed it would mis-position every window of exactly
// the runs the mode was taken for.
//
// `arms` is keyed in drive order (the driver fills it in the loop), and rounds
// taken since `rf2-rs8q6` also carry `segments`. Where both are present they
// are CROSS-CHECKED rather than one being trusted: a disagreement means the
// record's own two statements about its order do not match, which is a fault in
// the record and not something to average over.
const isControlWindow = (id) => typeof id === 'string' && id.startsWith('control/');

// The window sequence ONE ROUND drove, controls included, in drive order.
// Rounds taken since the control-slot mode record it as `windowOrder`; rounds
// taken before it were all driven `first` — three controls, then the arms in
// the order `arms` is keyed — so the fallback is the truth about them rather
// than a guess.
//
// AND IT IS CROSS-CHECKED against `arms` rather than trusted, for `segments`'
// reason: a `windowOrder` whose arm entries disagree with the keys the round
// actually filled means the record's two statements about its own sequence do
// not match, and this reader's whole index is built on that sequence.
function roundWindowOrder(round, keys, runId) {
  if (!round.windowOrder) return ['control/idle', 'control/d1', 'control/d2', ...keys];
  const armEntries = round.windowOrder.filter((w) => !isControlWindow(w));
  if (JSON.stringify(armEntries) !== JSON.stringify(keys)) {
    throw new Error(
      `${runId} round ${round.round}: the round's stated window order names arm windows ` +
        `${JSON.stringify(armEntries)} where its \`arms\` were filled as ` +
        `${JSON.stringify(keys)} — the record contradicts itself about the one sequence ` +
        'this reader is built on'
    );
  }
  return round.windowOrder;
}

function windowsOf(dataset, runId) {
  const row = dataset.alloc || dataset;
  // Records taken before this bead carry no `segOrder` field. They were all
  // taken on the flipping schedule, which is what `parity` names, so the
  // default is the truth about them rather than a guess.
  const segOrder = row.segOrder || 'parity';
  // Likewise the control slot: every record taken before the mode drove its
  // three controls before the round's arms, which is what `first` names.
  const controlSlot = row.controlSlot || 'first';
  const out = [];
  // THE RUN'S FLATTENED DRIVE STREAM, controls included and NOT reset at a
  // round boundary. That last part is the whole point: under `last` the window
  // immediately before a round's first arm is the PREVIOUS round's third
  // control, and a stream that restarted each round would report it as having
  // no predecessor and manufacture the separation the mode is being tested for.
  // Runs are not chained — a new browser process is a new stream.
  const stream = [];
  for (const r of row.perRound || []) {
    const keys = Object.keys(r.arms || {});
    const driven = keys.map((k) => r.arms[k].segment);
    if (r.segments && JSON.stringify(r.segments) !== JSON.stringify(driven)) {
      throw new Error(
        `${runId} round ${r.round}: the round's stated segment order ` +
          `${JSON.stringify(r.segments)} disagrees with the order its windows were ` +
          `filled in (${JSON.stringify(driven)}) — the record contradicts itself about ` +
          'the one index this reader is built on'
      );
    }
    const order = roundWindowOrder(r, keys, runId);
    let position = 0;
    for (const id of order) {
      if (isControlWindow(id)) {
        stream.push({ control: true });
        continue;
      }
      const a = r.arms[id];
      const w = {
        runId,
        segOrder,
        controlSlot,
        round: r.round,
        position,
        key: id,
        segment: a.segment,
        falls: a.falls,
        legs: a.legs || [],
        legMedian: a.legMedian,
        legWorstDeviationFraction: a.legWorstDeviation,
        primeExcess: a.primeExcess,
        certified: a.certified,
        // (B) — this window opens its round.
        roundFirst: position === 0,
        // (A) — filled below, once the stream has a predecessor to read.
        afterControls: stream.length > 0 && stream[stream.length - 1].control === true,
      };
      position++;
      stream.push({ control: false });
      out.push(w);
    }
  }
  return out;
}

// Every CONTROL leg in the record — the null arm, in situ. The controls run the
// same window machinery, the same sampler and the same round schedule as the
// arms and dispatch nothing, so a rider found in them would be the
// instrument's and not the arm's.
function controlLegsOf(dataset, runId) {
  const row = dataset.alloc || dataset;
  // The control windows carry the SAME group labels their run's arms do, for
  // the same defaults, because both records publish the control-leg count per
  // schedule ("960 under `first`, 972 under `last`, 972 under `mid`") and a
  // pooled total cannot be split back into those three after the fact.
  const segOrder = row.segOrder || 'parity';
  const controlSlot = row.controlSlot || 'first';
  const out = [];
  for (const r of row.perRound || []) {
    for (const [kind, c] of Object.entries(r.controls || {})) {
      if (!c || c.falls !== 0) continue;
      out.push({
        runId,
        segOrder,
        controlSlot,
        round: r.round,
        kind,
        legs: c.legs || [],
        legMedian: c.legMedian,
      });
    }
  }
  return out;
}

// A leg's excess over its own window's cohort median, in BYTES.
const excesses = (w) => (w.legs || []).map((x) => x - w.legMedian);

// The window's WORST leg, in bytes and SIGNED — the excess furthest from the
// cohort median in either direction. This is `legWorstDeviation` re-derived
// from the legs rather than read off the rig's own field, and the sign is
// load-bearing twice over: the pooling-trap table publishes a -6 B cell, and
// the secondary cluster reads 8 of 38 this way against 9 of 38 read as "the
// largest POSITIVE excess". The self-test pins both halves.
const worstExcessSignedB = (w) => {
  const e = excesses(w);
  if (!e.length) return null;
  return e.reduce((a, b) => (Math.abs(b) > Math.abs(a) ? b : a));
};

const inCluster = (w) => {
  const b = worstExcessSignedB(w);
  return b !== null && b >= CLUSTER_LO_B && b <= CLUSTER_HI_B;
};

// Riders, by the band above. Returns the leg ORDINALS as well as the values,
// because `rf2-rs8q6` found the rider lands late (ordinals 3/4/5 hold 100 of
// 112) and a reader that dropped the ordinal could not replicate that.
function ridersOf(w) {
  return excesses(w)
    .map((b, ordinal) => ({ ordinal, bytes: b }))
    .filter((x) => x.bytes >= RIDER_LO_B && x.bytes <= RIDER_HI_B);
}

// --- the adjacency relation the mode exists to break ------------------------
//
// For each window after the first IN A RUN, did its substrate repeat the
// previous window's? Under `parity` this is TRUE at position 0 and FALSE at
// position 1 — the confound. Under `fixed` it is FALSE at both.
//
// Runs are NOT chained: the previous window of a run's first window is in
// another browser process on another page, so it has no adjacency at all and is
// dropped rather than paired across the boundary.
function adjacency(windows) {
  const out = [];
  for (let i = 1; i < windows.length; i++) {
    const w = windows[i];
    const prev = windows[i - 1];
    if (w.runId !== prev.runId) continue;
    out.push({ ...w, repeatsSubstrate: w.segment === prev.segment });
  }
  return out;
}

// --- the report -------------------------------------------------------------

function analyse(datasets) {
  const all = datasets.flatMap((d) => windowsOf(d.data, d.id));
  const controls = datasets.flatMap((d) => controlLegsOf(d.data, d.id));
  const modes = [...new Set(all.map((w) => w.segOrder))].sort();

  const byMode = {};
  for (const mode of modes) {
    const mine = all.filter((w) => w.segOrder === mode);
    const clean = mine.filter((w) => w.falls === 0);
    // The adjacency is computed over the FULL sequence and only then filtered,
    // because a window's predecessor is its predecessor whether or not a
    // collector ran inside it. Filtering first would silently re-pair windows
    // across a dropped one and report an adjacency the page never drove.
    const adj = adjacency(mine).filter((w) => w.falls === 0);

    const positions = [...new Set(clean.map((w) => w.position))].sort();
    const perPosition = positions.map((p) => {
      const ws = clean.filter((w) => w.position === p);
      const riderWindows = ws.filter((w) => ridersOf(w).length > 0);
      const riderLegs = ws.flatMap(ridersOf);
      const legs = ws.flatMap(excesses);
      return {
        position: p,
        windows: ws.length,
        legs: legs.length,
        riderWindows: riderWindows.length,
        riderLegs: riderLegs.length,
        riderMedianB: median(riderLegs.map((x) => x.bytes)),
        riderOrdinals: riderLegs.reduce((acc, x) => {
          acc[x.ordinal] = (acc[x.ordinal] || 0) + 1;
          return acc;
        }, {}),
        // The "modal values" column of both records' "It is the same term"
        // table — the rider legs' distinct byte values with their counts.
        riderModalB: modal(riderLegs.map((x) => x.bytes)),
        // The pooling-trap table's cell, and the secondary cluster's count.
        worstExcessSignedMedianB: median(ws.map(worstExcessSignedB).filter((x) => x !== null)),
        clusterWindows: ws.filter(inCluster).length,
        // THE MAGNITUDE, and the `Math.abs` is not cosmetic. `legWorstDeviation`
        // is SIGNED — it is the deviation FURTHEST from the cohort median in
        // either direction — so a median over the signed field mixes a window
        // that read 748 B high with one that read 40 B low and answers a
        // different question. `rf2-rs8q6`'s published table is the magnitude:
        // over the same 387 committed windows the signed form gives 3.867% /
        // 0.030% and the magnitude gives 3.908% / 0.184%, which are that
        // record's two figures to the digit. The name carries both facts, since
        // this is the second unit trap on the same field.
        worstDeviationMedianAbsFraction: median(
          ws
            .map((w) => w.legWorstDeviationFraction)
            .filter((x) => typeof x === 'number')
            .map(Math.abs)
        ),
        // AND THE SIGNED FORM BESIDE IT, because the unit trap has two halves
        // and the position record publishes BOTH: over the 14 committed runs
        // the signed median reads 3.867% / 0.030% where the magnitude reads
        // 3.908% / 0.184%. A reader that emitted only one of them left the
        // other to be taken on trust, which is the whole of what that trap is.
        worstDeviationMedianSignedFraction: median(
          ws.map((w) => w.legWorstDeviationFraction).filter((x) => typeof x === 'number')
        ),
        legsWithinJitter: legs.filter((b) => Math.abs(b) <= JITTER_B).length,
        primeExcessMedianB: median(
          ws.map((w) => w.primeExcess).filter((x) => typeof x === 'number')
        ),
      };
    });

    // The cross-tab that says whether the confound is present in THIS mode's
    // records, measured rather than assumed from the mode's name.
    const crossTab = {};
    for (const w of adj) {
      const cell = `pos${w.position}/${w.repeatsSubstrate ? 'repeat' : 'switch'}`;
      crossTab[cell] = (crossTab[cell] || 0) + 1;
    }
    // The same cross-tab counting only the windows that CARRY a rider, which is
    // the position record's "all 25 position-0 rider windows repeated the
    // previous window's substrate / all 21 of them switched it". `crossTab`
    // itself is left as the window counts it has always been, because the two
    // fixtures below pin it.
    const crossTabRiders = {};
    for (const w of adj.filter((x) => ridersOf(x).length > 0)) {
      const cell = `pos${w.position}/${w.repeatsSubstrate ? 'repeat' : 'switch'}`;
      crossTabRiders[cell] = (crossTabRiders[cell] || 0) + 1;
    }
    // The re-taken `parity` arm's own table: rider windows by SUBSTRATE and
    // position, which is what excludes substrate IDENTITY where the adjacency
    // above excludes the substrate RELATION.
    const segments = [...new Set(clean.map((w) => w.segment))].sort();
    const perSegmentPosition = [];
    for (const segment of segments) {
      for (const p of positions) {
        const ws = clean.filter((w) => w.segment === segment && w.position === p);
        if (!ws.length) continue;
        perSegmentPosition.push({
          segment,
          position: p,
          windows: ws.length,
          riderWindows: ws.filter((w) => ridersOf(w).length > 0).length,
          clusterWindows: ws.filter(inCluster).length,
        });
      }
    }
    const byRepeat = [true, false].map((rep) => {
      const ws = adj.filter((w) => w.repeatsSubstrate === rep);
      return {
        repeatsSubstrate: rep,
        windows: ws.length,
        riderWindows: ws.filter((w) => ridersOf(w).length > 0).length,
      };
    });

    byMode[mode] = {
      runs: [...new Set(mine.map((w) => w.runId))].length,
      windows: mine.length,
      cleanWindows: clean.length,
      perPosition,
      perSegmentPosition,
      adjacencyWindows: adj.length,
      crossTab,
      crossTabRiders,
      byRepeat,
    };
  }

  // --- AND THE CONTROL-SLOT CROSS-TAB (rf2-rs8q6) --------------------------
  //
  // The 2x2 the whole second window exists to fill. Under `first` and `last`
  // two of its four cells are empty by construction, which is exactly the
  // statement that those schedules do not separate the two properties; under
  // `mid` all four are populated and the rider's cell is the answer.
  const slots = [...new Set(all.map((w) => w.controlSlot))].sort();
  const bySlot = {};
  for (const slot of slots) {
    const clean = all.filter((w) => w.controlSlot === slot && w.falls === 0);
    const cell = (afterControls, roundFirst) => {
      const ws = clean.filter(
        (w) => w.afterControls === afterControls && w.roundFirst === roundFirst
      );
      const riderLegs = ws.flatMap(ridersOf);
      return {
        afterControls,
        roundFirst,
        windows: ws.length,
        riderWindows: ws.filter((w) => ridersOf(w).length > 0).length,
        riderLegs: riderLegs.length,
        riderMedianB: median(riderLegs.map((x) => x.bytes)),
        riderModalB: modal(riderLegs.map((x) => x.bytes)),
        riderOrdinals: riderLegs.reduce((acc, x) => {
          acc[x.ordinal] = (acc[x.ordinal] || 0) + 1;
          return acc;
        }, {}),
        worstDeviationMedianAbsFraction: median(
          ws
            .map((w) => w.legWorstDeviationFraction)
            .filter((x) => typeof x === 'number')
            .map(Math.abs)
        ),
        worstDeviationMedianSignedFraction: median(
          ws.map((w) => w.legWorstDeviationFraction).filter((x) => typeof x === 'number')
        ),
        worstExcessSignedMedianB: median(ws.map(worstExcessSignedB).filter((x) => x !== null)),
        // The control-slot record states the prime excess BY CELL rather than
        // by position, and under `last` the two differ: cell (A) holds 43
        // windows where position 0 holds 44, and their medians read 6,884 B
        // and 6,882 B. Reading the published figure off the position is the
        // 2 B error that difference costs.
        primeExcessMedianB: median(
          ws.map((w) => w.primeExcess).filter((x) => typeof x === 'number')
        ),
      };
    };
    bySlot[slot] = {
      runs: [...new Set(all.filter((w) => w.controlSlot === slot).map((w) => w.runId))].length,
      cleanWindows: clean.length,
      // Whether this slot's records SEPARATE the two properties at all,
      // measured off the records rather than asserted from the slot's name: the
      // count of clean windows on which the two predicates disagree.
      separatedWindows: clean.filter((w) => w.afterControls !== w.roundFirst).length,
      cells: [
        cell(true, true),
        cell(true, false),
        cell(false, true),
        cell(false, false),
      ],
    };
  }

  // --- PER RUN (rf2-rs8q6) --------------------------------------------------
  //
  // Both records publish an "It holds in every run separately" table, and the
  // control-slot record publishes a second per-run table for the pooling trap
  // it laid — pooled by slot, position 1 looks like it carries a ~2,400 B term
  // under two slots and nothing under the third, and per RUN it splits 1/3,
  // 2/3, 3/3. A reader that only ever pooled could not show that.
  const cleanAll = all.filter((w) => w.falls === 0);
  const perRun = [];
  for (const runId of [...new Set(all.map((w) => w.runId))].sort()) {
    for (const p of [...new Set(cleanAll.filter((w) => w.runId === runId).map((w) => w.position))].sort()) {
      const ws = cleanAll.filter((w) => w.runId === runId && w.position === p);
      perRun.push({
        runId,
        segOrder: ws[0].segOrder,
        controlSlot: ws[0].controlSlot,
        position: p,
        windows: ws.length,
        riderWindows: ws.filter((w) => ridersOf(w).length > 0).length,
        worstExcessSignedMedianB: median(ws.map(worstExcessSignedB).filter((x) => x !== null)),
      });
    }
  }

  // --- THE NAMED COMPARISONS, AND THEIR z (rf2-rs8q6) -----------------------
  //
  // Every z either record publishes, built from the cells above rather than
  // stated, so the figure and the counts it was taken on cannot drift apart.
  // Each comparison names its two groups in the reader's canonical order and
  // the sign follows that order — see the header.
  const rate = (label, ws) => ({
    label,
    riderWindows: ws.filter((w) => ridersOf(w).length > 0).length,
    windows: ws.length,
  });
  const comparisons = [];
  const compare = (name, a, b) => {
    if (!a.windows || !b.windows) return;
    comparisons.push({ name, a, b, z: twoProportionZ(a.riderWindows, a.windows, b.riderWindows, b.windows) });
  };
  const allPositions = [...new Set(cleanAll.map((w) => w.position))].sort();

  // (1) within one segment order: position against position.
  for (const mode of modes) {
    const mine = cleanAll.filter((w) => w.segOrder === mode);
    for (let i = 0; i < allPositions.length; i++) {
      for (let j = i + 1; j < allPositions.length; j++) {
        compare(
          `segment order ${mode}: position ${allPositions[i]} against position ${allPositions[j]}`,
          rate(`position ${allPositions[i]}`, mine.filter((w) => w.position === allPositions[i])),
          rate(`position ${allPositions[j]}`, mine.filter((w) => w.position === allPositions[j]))
        );
      }
    }
  }
  // (2) one position, across two segment orders — "the mode changed nothing
  //     about the position effect".
  for (let i = 0; i < modes.length; i++) {
    for (let j = i + 1; j < modes.length; j++) {
      for (const p of allPositions) {
        compare(
          `position ${p}: ${modes[i]} against ${modes[j]}`,
          rate(modes[i], cleanAll.filter((w) => w.segOrder === modes[i] && w.position === p)),
          rate(modes[j], cleanAll.filter((w) => w.segOrder === modes[j] && w.position === p))
        );
      }
    }
  }
  // (3) within one control slot: the after-controls windows against the rest.
  //     This is the z column of the control-slot record's answer table, and one
  //     rule covers all three rows — under `mid` "the rest" IS the separated
  //     opens-round cell, which is what that row compares.
  for (const slot of slots) {
    const mine = cleanAll.filter((w) => w.controlSlot === slot);
    compare(
      `control slot ${slot}: after the controls against the rest`,
      rate('after controls', mine.filter((w) => w.afterControls)),
      rate('the rest', mine.filter((w) => !w.afterControls))
    );
  }
  // (4) and across slots. A slot SEPARATES the two properties when they
  //     disagree on every one of its clean windows, and is COUPLED otherwise —
  //     measured off the records, never assumed from the slot's name.
  const separates = (slot) => {
    const mine = cleanAll.filter((w) => w.controlSlot === slot);
    return mine.length > 0 && mine.every((w) => w.afterControls !== w.roundFirst);
  };
  const separating = slots.filter(separates);
  const coupled = slots.filter((s) => !separates(s));
  const coupledClean = cleanAll.filter((w) => coupled.includes(w.controlSlot));
  for (let i = 0; i < coupled.length; i++) {
    for (let j = i + 1; j < coupled.length; j++) {
      compare(
        `after the controls: ${coupled[i]} against ${coupled[j]}`,
        rate(coupled[i], coupledClean.filter((w) => w.controlSlot === coupled[i] && w.afterControls)),
        rate(coupled[j], coupledClean.filter((w) => w.controlSlot === coupled[j] && w.afterControls))
      );
    }
  }
  for (const slot of separating) {
    const mine = cleanAll.filter((w) => w.controlSlot === slot);
    compare(
      `opens the round ALONE: ${slot} against the coupled slots' not-after-controls windows`,
      rate(`${slot} opens round`, mine.filter((w) => w.roundFirst && !w.afterControls)),
      rate('coupled, not after controls', coupledClean.filter((w) => !w.afterControls))
    );
    compare(
      `after the controls ALONE: ${slot} against the coupled slots' coincident windows`,
      rate(`${slot} after controls`, mine.filter((w) => w.afterControls && !w.roundFirst)),
      rate('coupled, both properties', coupledClean.filter((w) => w.afterControls && w.roundFirst))
    );
  }

  const controlLegs = controls.flatMap((c) => c.legs.map((x) => x - c.legMedian));
  const controlGroup = (key, value) => {
    const mine = controls.filter((c) => c[key] === value);
    const legs = mine.flatMap((c) => c.legs.map((x) => x - c.legMedian));
    return {
      windows: mine.length,
      legs: legs.length,
      riderLegs: legs.filter((b) => b >= RIDER_LO_B && b <= RIDER_HI_B).length,
    };
  };
  return {
    modes,
    byMode,
    slots,
    bySlot,
    perRun,
    comparisons,
    controls: {
      windows: controls.length,
      legs: controlLegs.length,
      riderLegs: controlLegs.filter((b) => b >= RIDER_LO_B && b <= RIDER_HI_B).length,
      bySlot: Object.fromEntries(slots.map((s) => [s, controlGroup('controlSlot', s)])),
      byMode: Object.fromEntries(modes.map((m) => [m, controlGroup('segOrder', m)])),
    },
  };
}

// --- printing ---------------------------------------------------------------

const pct = (f) => (typeof f === 'number' ? `${(f * 100).toFixed(3)}%` : 'n/a');
const bytes = (b) => (typeof b === 'number' ? `${b} B` : 'n/a');
const modalStr = (ms) => (ms && ms.length ? ms.map((m) => `${m.value} B x${m.count}`).join(', ') : 'none');
// TWO precisions, and both are wanted. The records publish two decimals, so
// that is what a cross-check scans for; four is what shows a near-miss
// convention landing beside the published figure rather than on it.
const signed = (z, dp) => (z >= 0 ? `+${z.toFixed(dp)}` : z.toFixed(dp));
const zStr = (z) => (typeof z === 'number' ? `${signed(z, 2)} (${signed(z, 4)})` : 'n/a');
const rateStr = (r) =>
  `${r.label} ${r.riderWindows} of ${r.windows} (${((r.riderWindows / r.windows) * 100).toFixed(1)}%)`;

function report(a) {
  const out = [];
  out.push('THE POSITION / SUBSTRATE CONFOUND (rf2-rs8q6)');
  out.push(`  rider band: ${RIDER_LO_B}-${RIDER_HI_B} B over the window's own leg median`);
  out.push('  legWorstDeviation is a FRACTION and is SIGNED; the column below is its MAGNITUDE, x 100');
  out.push('');
  for (const mode of a.modes) {
    const m = a.byMode[mode];
    out.push(
      `SEGMENT ORDER = ${mode}   ${m.runs} run(s), ${m.windows} arm windows, ` +
        `${m.cleanWindows} collection-free`
    );
    out.push('  position | windows | legs | rider legs | rider windows | median |worst dev| | prime excess');
    for (const p of m.perPosition) {
      out.push(
        `  ${String(p.position).padEnd(8)} | ${String(p.windows).padStart(7)} | ` +
          `${String(p.legs).padStart(4)} | ${String(p.riderLegs).padStart(10)} | ` +
          `${String(p.riderWindows).padStart(13)} | ${pct(p.worstDeviationMedianAbsFraction).padStart(16)} | ` +
          `${p.primeExcessMedianB === null ? 'n/a' : `${p.primeExcessMedianB} B`}`
      );
    }
    for (const p of m.perPosition) {
      out.push(
        `    position ${p.position} worst deviation median: MAGNITUDE ` +
          `${pct(p.worstDeviationMedianAbsFraction)}, SIGNED ` +
          `${pct(p.worstDeviationMedianSignedFraction)}`
      );
      out.push(
        `    position ${p.position} rider legs: modal ${modalStr(p.riderModalB)}; ` +
          `ordinals ${JSON.stringify(p.riderOrdinals)}`
      );
      out.push(
        `    position ${p.position} worst leg (SIGNED) median ${bytes(p.worstExcessSignedMedianB)}; ` +
          `${CLUSTER_LO_B}-${CLUSTER_HI_B} B cluster ${p.clusterWindows} of ${p.windows}`
      );
    }
    for (const s of m.perSegmentPosition) {
      out.push(
        `    ${s.segment} at position ${s.position}: ${s.riderWindows} of ${s.windows} carry a rider, ` +
          `${s.clusterWindows} in the ${CLUSTER_LO_B}-${CLUSTER_HI_B} B cluster`
      );
    }
    out.push(`  adjacency (${m.adjacencyWindows} paired windows): ${JSON.stringify(m.crossTab)}`);
    out.push(`    of which carry a rider: ${JSON.stringify(m.crossTabRiders)}`);
    for (const r of m.byRepeat) {
      out.push(
        `    substrate ${r.repeatsSubstrate ? 'REPEATS' : 'SWITCHES'}: ` +
          `${r.riderWindows} of ${r.windows} windows carry a rider`
      );
    }
    out.push('');
  }
  for (const slot of a.slots || []) {
    const s = a.bySlot[slot];
    out.push(
      `CONTROL SLOT = ${slot}   ${s.runs} run(s), ${s.cleanWindows} collection-free arm ` +
        `windows, ${s.separatedWindows} on which the two properties DISAGREE`
    );
    out.push('  after controls | opens round | windows | rider windows | rider legs | median |worst dev| | prime excess');
    for (const c of s.cells) {
      out.push(
        `  ${(c.afterControls ? 'yes' : 'no').padEnd(14)} | ${(c.roundFirst ? 'yes' : 'no').padEnd(11)} | ` +
          `${String(c.windows).padStart(7)} | ${String(c.riderWindows).padStart(13)} | ` +
          `${String(c.riderLegs).padStart(10)} | ` +
          `${pct(c.worstDeviationMedianAbsFraction).padStart(16)} | ${bytes(c.primeExcessMedianB)}`
      );
      if (c.windows) {
        out.push(
          `      rider legs: modal ${modalStr(c.riderModalB)}; ordinals ${JSON.stringify(c.riderOrdinals)}; ` +
            `worst leg (SIGNED) median ${bytes(c.worstExcessSignedMedianB)}; ` +
            `worst deviation SIGNED ${pct(c.worstDeviationMedianSignedFraction)}`
        );
      }
    }
    out.push('');
  }

  out.push('PER RUN — the pooled figures above split by browser launch');
  out.push('  run | segment order | control slot | position | windows | rider windows | worst leg (SIGNED) median');
  for (const r of a.perRun) {
    out.push(
      `  ${r.runId} | ${r.segOrder} | ${r.controlSlot} | ${r.position} | ` +
        `${String(r.windows).padStart(3)} | ${String(r.riderWindows).padStart(3)} | ` +
        `${bytes(r.worstExcessSignedMedianB)}`
    );
  }
  out.push('');

  out.push('COMPARISONS — two-proportion z on the POOLED proportion, no continuity correction');
  out.push('  the sign is (first group - second group), in the order each line names them');
  out.push('  z is printed at the published two decimals and again at four');
  for (const c of a.comparisons) {
    out.push(`  z = ${zStr(c.z).padStart(17)}   ${c.name}`);
    out.push(`             ${rateStr(c.a)}  against  ${rateStr(c.b)}`);
  }
  out.push('');

  out.push(
    `CONTROLS (the null arm): ${a.controls.riderLegs} of ${a.controls.legs} legs in the band, ` +
      `over ${a.controls.windows} collection-free control windows`
  );
  for (const [slot, g] of Object.entries(a.controls.bySlot || {})) {
    out.push(`    control slot ${slot}: ${g.riderLegs} of ${g.legs} legs in the band, over ${g.windows} windows`);
  }
  for (const [mode, g] of Object.entries(a.controls.byMode || {})) {
    out.push(`    segment order ${mode}: ${g.riderLegs} of ${g.legs} legs in the band, over ${g.windows} windows`);
  }
  return out;
}

// --- the self-test ----------------------------------------------------------
//
// A reader that cannot fail is not a reader. Every claim below is driven over a
// SYNTHETIC record whose answer is known by construction, and each one includes
// its own negative: a rider planted where the reader must see it, and a record
// with none where it must report none.

// `riderAt` is a `round:position:ordinal` key, which plants one +748 B rider —
// or a MAP of such keys to byte excesses, which plants whatever the fixture
// needs. The string form is the whole of what the pre-existing fixtures use and
// is unchanged by the map form.
const plantedAt = (riderAt, key) => {
  if (!riderAt) return 0;
  if (typeof riderAt === 'string') return riderAt === key ? 748 : 0;
  return riderAt[key] || 0;
};

function synthRound(round, segments, riderAt, controlLegs) {
  const arms = {};
  segments.forEach((segment, i) => {
    const legMedian = 19280;
    const legs = [0, 1, 2, 3, 4, 5].map((o) => legMedian + plantedAt(riderAt, `${round}:${i}:${o}`));
    arms[`${segment}|grid/floor`] = {
      segment,
      falls: 0,
      legs,
      legMedian,
      // The rig's own field, and the SIGNED furthest-from-median leg rather
      // than the largest one, because that is what `p0_run.cjs` writes and
      // what the records' "worst leg" figures are. On a record whose only
      // planted leg is a positive rider the two forms agree, so every fixture
      // written before the map form above reads exactly as it did.
      legWorstDeviation:
        legs.map((x) => x - legMedian).reduce((a, b) => (Math.abs(b) > Math.abs(a) ? b : a)) / legMedian,
      primeExcess: 6864,
      certified: true,
    };
  });
  const controls = {};
  for (const [kind, excess] of Object.entries(controlLegs || {})) {
    const legMedian = 12040;
    controls[kind] = { falls: 0, legMedian, legs: [legMedian, legMedian + excess, legMedian] };
  }
  return { round, arms, segments, controls };
}

function synth(segOrder, rounds, riderAt, controlLegs) {
  const order = ['reagent-subs', 'uix-subs'];
  return {
    alloc: {
      segOrder,
      perRound: Array.from({ length: rounds }, (_, r) =>
        synthRound(
          r,
          segOrder === 'fixed' || r % 2 === 0 ? order : [...order].reverse(),
          riderAt,
          controlLegs
        )
      ),
    },
  };
}

// AND THE SAME RECORD UNDER A CONTROL SLOT (rf2-rs8q6). `windowOrder` is built
// here the way `p0_run.cjs` builds it — the three controls at the slot's index
// among the round's arm passes — so the reader is driven over the shape the rig
// actually emits rather than over a hand-written idea of it.
//
// `riderAt` is `round:position:ordinal`, unchanged, so a rider can be planted
// at a POSITION and the reader asked which PROPERTY it lands on.
function synthSlot(controlSlot, rounds, riderAt) {
  const d = synth('fixed', rounds, riderAt);
  d.alloc.controlSlot = controlSlot;
  for (const r of d.alloc.perRound) {
    const keys = Object.keys(r.arms);
    const ctl = ['control/idle', 'control/d1', 'control/d2'];
    const at = controlSlot === 'last' ? keys.length : controlSlot === 'mid' ? 1 : 0;
    const orderOut = [];
    keys.forEach((k, i) => {
      if (i === at) orderOut.push(...ctl);
      orderOut.push(k);
    });
    if (at >= keys.length) orderOut.push(...ctl);
    r.windowOrder = orderOut;
    r.controlIndex = at;
  }
  return d;
}

// EVERY z EITHER RECORD PUBLISHES, with the counts it was taken on, verbatim
// from the two pages — `[what it is, k1, n1, k2, n2, the published z]`. The
// pair is in the reader's canonical order, which is the order `comparisons`
// emits them in and the order the sign follows; where a page's prose names the
// two groups the other way round the magnitude is unchanged and only the sign's
// reading is, which is why the pages now state the convention beside the table.
const PUBLISHED_Z = [
  // docs/design/hicasso/studio/the-rider-follows-the-position-not-the-substrate.md
  ['position record: `parity`, position 0 against position 1', 25, 40, 3, 49, 5.7],
  ['position record: `fixed`, position 0 against position 1', 21, 43, 1, 38, 4.67],
  ['position record: position 0, `fixed` against `parity`', 21, 43, 25, 40, -1.25],
  ['position record: position 1, `fixed` against `parity`', 1, 38, 3, 49, -0.77],
  // docs/design/hicasso/studio/the-rider-follows-the-controls-not-the-round-boundary.md
  ['control-slot record: `first`, after the controls against the rest', 18, 39, 2, 46, 4.53],
  ['control-slot record: `last`, after the controls against the rest', 26, 43, 3, 44, 5.31],
  ['control-slot record: `mid`, after the controls against the rest', 9, 37, 3, 44, 2.21],
  ['control-slot record: opens the round alone, `mid` against the coupled slots', 3, 44, 5, 90, 0.29],
  ['control-slot record: after the controls alone, `mid` against the coincident cells', 9, 37, 44, 82, -2.98],
  ['control-slot record: after the controls, `first` against `last`', 18, 39, 26, 43, -1.3],
];

const slotCell = (a, slot, afterControls, roundFirst) =>
  a.bySlot[slot].cells.find(
    (c) => c.afterControls === afterControls && c.roundFirst === roundFirst
  );

function selfTest() {
  const assert = require('node:assert');
  const checks = [];
  const ok = (name, fn) => {
    try {
      fn();
      checks.push([true, name]);
    } catch (e) {
      checks.push([false, `${name} — ${e.message}`]);
    }
  };

  ok('the confound is PRESENT under `parity` — position 0 is exactly the repeats', () => {
    const a = analyse([{ id: 'p', data: synth('parity', 6, null) }]);
    assert.deepStrictEqual(a.byMode.parity.crossTab, { 'pos0/repeat': 5, 'pos1/switch': 6 });
  });

  ok('and BROKEN under `fixed` — both positions switch', () => {
    const a = analyse([{ id: 'f', data: synth('fixed', 6, null) }]);
    assert.deepStrictEqual(a.byMode.fixed.crossTab, { 'pos0/switch': 5, 'pos1/switch': 6 });
  });

  ok('a planted rider is COUNTED, at its position and its ordinal', () => {
    const a = analyse([{ id: 'f', data: synth('fixed', 6, '2:0:4') }]);
    const p0 = a.byMode.fixed.perPosition.find((p) => p.position === 0);
    assert.strictEqual(p0.riderLegs, 1);
    assert.strictEqual(p0.riderWindows, 1);
    assert.strictEqual(p0.riderMedianB, 748);
    assert.deepStrictEqual(p0.riderOrdinals, { 4: 1 });
  });

  ok('and a record with NONE reports none — the reader is not manufacturing them', () => {
    const a = analyse([{ id: 'f', data: synth('fixed', 6, null) }]);
    for (const p of a.byMode.fixed.perPosition) assert.strictEqual(p.riderLegs, 0);
  });

  ok('a collection-carrying window is EXCLUDED by the falls gate', () => {
    const d = synth('fixed', 6, '0:0:3');
    d.alloc.perRound[0].arms['reagent-subs|grid/floor'].falls = 2;
    const a = analyse([{ id: 'f', data: d }]);
    assert.strictEqual(a.byMode.fixed.cleanWindows, 11);
    assert.strictEqual(a.byMode.fixed.perPosition.find((p) => p.position === 0).riderLegs, 0);
  });

  ok('runs are NOT chained — the first window of a run has no predecessor', () => {
    const a = analyse([
      { id: 'a', data: synth('fixed', 3, null) },
      { id: 'b', data: synth('fixed', 3, null) },
    ]);
    assert.strictEqual(a.byMode.fixed.windows, 12);
    assert.strictEqual(a.byMode.fixed.adjacencyWindows, 10, 'two firsts dropped, not paired across');
  });

  ok('a record contradicting itself about its own order REFUSES', () => {
    const d = synth('fixed', 2, null);
    d.alloc.perRound[1].segments = ['uix-subs', 'reagent-subs'];
    assert.throws(() => analyse([{ id: 'x', data: d }]), /disagrees with the order/);
  });

  ok('a pre-bead record with no `segOrder` reads as `parity`', () => {
    const d = synth('parity', 4, null);
    delete d.alloc.segOrder;
    for (const r of d.alloc.perRound) delete r.segments;
    const a = analyse([{ id: 'o', data: d }]);
    assert.deepStrictEqual(a.modes, ['parity']);
  });

  // --- THE CONTROL SLOT (rf2-rs8q6) ----------------------------------------
  //
  // The reader's new index, and the claim that decides which slot was worth
  // the machine: under `first` and `last` the two properties are the same
  // predicate on all but one window, and only `mid` separates them at full n.

  ok('a pre-bead record with no `controlSlot` reads as `first`', () => {
    const d = synth('parity', 4, null);
    const a = analyse([{ id: 'o', data: d }]);
    assert.deepStrictEqual(a.slots, ['first']);
    // and with no `windowOrder` either, the fallback puts the three controls
    // before the round's arms — so position 0 is "after controls" every round.
    assert.strictEqual(slotCell(a, 'first', true, true).windows, 4);
    assert.strictEqual(slotCell(a, 'first', false, false).windows, 4);
    assert.strictEqual(a.bySlot.first.separatedWindows, 0);
  });

  ok('`first` CONFOUNDS the two properties — they never disagree', () => {
    const a = analyse([{ id: 'f', data: synthSlot('first', 6, null) }]);
    assert.strictEqual(a.bySlot.first.separatedWindows, 0);
    assert.strictEqual(slotCell(a, 'first', true, false).windows, 0);
    assert.strictEqual(slotCell(a, 'first', false, true).windows, 0);
  });

  ok('`last` separates them in exactly ONE window per run — round 0, position 0', () => {
    const a = analyse([{ id: 'l', data: synthSlot('last', 6, null) }]);
    assert.strictEqual(a.bySlot.last.separatedWindows, 1);
    // the separated window OPENS a round and does NOT follow the controls
    assert.strictEqual(slotCell(a, 'last', false, true).windows, 1);
    assert.strictEqual(slotCell(a, 'last', true, false).windows, 0);
  });

  ok('and the stream is NOT reset at a round boundary — `last` would read 6 if it were', () => {
    // The failure this guards: a reader that restarted its stream each round
    // would find every round's first arm with no predecessor and report all six
    // as separated, manufacturing exactly the result the window tests for.
    const a = analyse([{ id: 'l', data: synthSlot('last', 6, null) }]);
    assert.notStrictEqual(a.bySlot.last.separatedWindows, 6);
    assert.strictEqual(slotCell(a, 'last', true, true).windows, 5);
  });

  ok('`mid` separates them at FULL n — every window disagrees', () => {
    const a = analyse([{ id: 'm', data: synthSlot('mid', 6, null) }]);
    assert.strictEqual(a.bySlot.mid.cleanWindows, 12);
    assert.strictEqual(a.bySlot.mid.separatedWindows, 12);
    // "opens the round" is position 0 and never follows the controls;
    // "follows the controls" is position 1 and never opens a round.
    assert.strictEqual(slotCell(a, 'mid', false, true).windows, 6);
    assert.strictEqual(slotCell(a, 'mid', true, false).windows, 6);
    assert.strictEqual(slotCell(a, 'mid', true, true).windows, 0);
    assert.strictEqual(slotCell(a, 'mid', false, false).windows, 0);
  });

  ok('and a rider planted at position 0 under `mid` lands in the OPENS-ROUND cell', () => {
    const a = analyse([{ id: 'm', data: synthSlot('mid', 6, '2:0:3') }]);
    assert.strictEqual(slotCell(a, 'mid', false, true).riderWindows, 1);
    assert.strictEqual(slotCell(a, 'mid', false, true).riderMedianB, 748);
    assert.strictEqual(slotCell(a, 'mid', true, false).riderWindows, 0);
  });

  ok('and one planted at position 1 under `mid` lands in the AFTER-CONTROLS cell', () => {
    const a = analyse([{ id: 'm', data: synthSlot('mid', 6, '2:1:3') }]);
    assert.strictEqual(slotCell(a, 'mid', true, false).riderWindows, 1);
    assert.strictEqual(slotCell(a, 'mid', false, true).riderWindows, 0);
  });

  ok('a `windowOrder` disagreeing with the round’s own `arms` REFUSES', () => {
    const d = synthSlot('mid', 2, null);
    d.alloc.perRound[1].windowOrder = [...d.alloc.perRound[1].windowOrder].reverse();
    assert.throws(() => analyse([{ id: 'x', data: d }]), /contradicts itself about the one sequence/);
  });

  // --- THE PUBLISHED STATISTIC (rf2-rs8q6) ---------------------------------
  //
  // The gap the merged-PR audits of #8545 and #8555 named twice: both records
  // say every figure on them is re-derived by this reader, and the reader
  // computed no z at all. These fixtures are the control that closes it. The
  // input counts are LITERALS taken from the two records, so the pin is
  // hermetic — it needs no corpus and cannot go stale against one — and the
  // negative below is what makes it a control rather than a restatement.

  ok('the ten published z-scores are reproduced to the digit by the pooled two-proportion z', () => {
    for (const [name, k1, n1, k2, n2, z] of PUBLISHED_Z) {
      assert.strictEqual(Number(twoProportionZ(k1, n1, k2, n2).toFixed(2)), z, name);
    }
  });

  ok('and NOT by the unpooled or continuity-corrected forms — the pin DISCRIMINATES', () => {
    // Without this, "the formula reproduces the records" would be a claim about
    // a formula nobody could vary. Three conventions are in play on a 2x2 of
    // counts and they land within half a z of each other; this requires the
    // other two to miss EVERY published figure at the published precision.
    const unpooled = (k1, n1, k2, n2) => {
      const p1 = k1 / n1;
      const p2 = k2 / n2;
      return (p1 - p2) / Math.sqrt((p1 * (1 - p1)) / n1 + (p2 * (1 - p2)) / n2);
    };
    const corrected = (k1, n1, k2, n2) => {
      const p1 = k1 / n1;
      const p2 = k2 / n2;
      const p = (k1 + k2) / (n1 + n2);
      const se = Math.sqrt(p * (1 - p) * (1 / n1 + 1 / n2));
      return (Math.sign(p1 - p2) * (Math.abs(p1 - p2) - 0.5 * (1 / n1 + 1 / n2))) / se;
    };
    for (const [name, k1, n1, k2, n2, z] of PUBLISHED_Z) {
      assert.notStrictEqual(Number(unpooled(k1, n1, k2, n2).toFixed(2)), z, `unpooled reads ${name}`);
      assert.notStrictEqual(Number(corrected(k1, n1, k2, n2).toFixed(2)), z, `corrected reads ${name}`);
    }
  });

  ok('the statistic is ANTISYMMETRIC — the sign is the order of the two groups', () => {
    for (const [, k1, n1, k2, n2] of PUBLISHED_Z) {
      const f = twoProportionZ(k1, n1, k2, n2);
      const r = twoProportionZ(k2, n2, k1, n1);
      assert.ok(Math.abs(f + r) < 1e-12, `${f} vs ${r}`);
    }
  });

  ok('and it REFUSES rather than returning a number where it is undefined', () => {
    assert.strictEqual(twoProportionZ(0, 0, 3, 44), null, 'an empty group');
    assert.strictEqual(twoProportionZ(3, 44, 0, 0), null, 'an empty group, the other side');
    assert.strictEqual(twoProportionZ(0, 10, 0, 12), null, 'pooled proportion 0');
    assert.strictEqual(twoProportionZ(10, 10, 12, 12), null, 'pooled proportion 1');
  });

  ok('the named comparisons are built from the record’s own cells, not stated', () => {
    const a = analyse([{ id: 'm', data: synthSlot('mid', 6, '2:1:3') }]);
    const c = a.comparisons.find((x) => x.name === 'control slot mid: after the controls against the rest');
    assert.deepStrictEqual(
      [c.a.riderWindows, c.a.windows, c.b.riderWindows, c.b.windows],
      [1, 6, 0, 6],
      'the after-controls cell carries the planted rider'
    );
    assert.strictEqual(c.z, twoProportionZ(1, 6, 0, 6));
  });

  ok('and they MOVE with the record — the same rider at the other cell flips the sign', () => {
    const a = analyse([{ id: 'm', data: synthSlot('mid', 6, '2:0:3') }]);
    const c = a.comparisons.find((x) => x.name === 'control slot mid: after the controls against the rest');
    assert.deepStrictEqual([c.a.riderWindows, c.b.riderWindows], [0, 1]);
    assert.ok(c.z < 0, `expected a negative z, read ${c.z}`);
  });

  // --- THE OTHER PAGE-ONLY SUMMARIES (rf2-rs8q6) ---------------------------
  //
  // The second audit named these beside the z-scores: the per-run tables, the
  // modal rider terms, and the secondary cluster.

  ok('the per-run table separates the runs rather than pooling them', () => {
    const a = analyse([
      { id: 'r1', data: synth('fixed', 3, '0:0:3') },
      { id: 'r2', data: synth('fixed', 3, null) },
    ]);
    assert.deepStrictEqual(
      a.perRun.filter((r) => r.position === 0).map((r) => [r.runId, r.riderWindows, r.windows]),
      [
        ['r1', 1, 3],
        ['r2', 0, 3],
      ]
    );
  });

  ok('the modal rider values are counted and ranked, most frequent first', () => {
    const a = analyse([
      { id: 'f', data: synth('fixed', 3, { '0:0:3': 748, '1:0:3': 748, '2:0:4': 736 }) },
    ]);
    const p0 = a.byMode.fixed.perPosition.find((p) => p.position === 0);
    assert.strictEqual(p0.riderLegs, 3);
    assert.deepStrictEqual(p0.riderModalB, [
      { value: 748, count: 2 },
      { value: 736, count: 1 },
    ]);
    assert.deepStrictEqual(p0.riderOrdinals, { 3: 2, 4: 1 });
  });

  ok('the worst leg is the SIGNED furthest-from-median one, and agrees with the record’s own field', () => {
    const a = analyse([{ id: 'w', data: synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -1400 }) }]);
    const p1 = a.byMode.fixed.perPosition.find((p) => p.position === 1);
    assert.strictEqual(p1.worstExcessSignedMedianB, -1400, 'the -1,400 B leg is further out than the +1,100 B one');
    // and the same window read off `legWorstDeviation`, which is what the rig
    // wrote — the two must not disagree, because the records cite both.
    const w = windowsOf(synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -1400 }), 'w')[1];
    assert.strictEqual(Math.round(w.legWorstDeviationFraction * w.legMedian), -1400);
  });

  ok('so the secondary cluster EXCLUDES a window whose furthest leg is out of band', () => {
    // The published "8 of 38" reads 9 of 38 if the cluster is taken on the
    // largest POSITIVE excess instead: one `fixed` position-1 window has a
    // +1,056 B leg and a larger negative one. This is that window, in
    // miniature, and it is the fixture that holds the published figure.
    const a = analyse([{ id: 'w', data: synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -1400 }) }]);
    assert.strictEqual(a.byMode.fixed.perPosition.find((p) => p.position === 1).clusterWindows, 0);
    assert.strictEqual(Math.max(...excesses(windowsOf(synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -1400 }), 'w')[1])), 1100);
  });

  ok('and COUNTS one whose furthest leg is the in-band one', () => {
    const a = analyse([{ id: 'w', data: synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -300 }) }]);
    const p1 = a.byMode.fixed.perPosition.find((p) => p.position === 1);
    assert.strictEqual(p1.clusterWindows, 1);
    assert.strictEqual(p1.worstExcessSignedMedianB, 1100);
  });

  ok('control legs split by the schedule they were driven under, and an in-band one is COUNTED', () => {
    // "0 of 2,904 legs in the band, in every slot" is a MEASUREMENT, and this
    // is what makes it one: plant a control leg in the band and the count moves.
    const withControls = (slot, excess) => {
      const d = synth('fixed', 2, null, { 'control/idle': 0, 'control/d1': excess, 'control/d2': 0 });
      d.alloc.controlSlot = slot;
      return d;
    };
    const a = analyse([
      { id: 'c1', data: withControls('first', 0) },
      { id: 'c2', data: withControls('mid', 748) },
    ]);
    assert.strictEqual(a.controls.legs, 36);
    assert.strictEqual(a.controls.riderLegs, 2);
    assert.deepStrictEqual(a.controls.bySlot.first, { windows: 6, legs: 18, riderLegs: 0 });
    assert.deepStrictEqual(a.controls.bySlot.mid, { windows: 6, legs: 18, riderLegs: 2 });
  });

  ok('the worst DEVIATION is reported both ways, because the record publishes both', () => {
    // The unit trap's second half: over the 14 committed runs the signed median
    // reads 3.867% / 0.030% and the magnitude 3.908% / 0.184%, and the position
    // record states both. A reader emitting one left the other on trust.
    const a = analyse([{ id: 'u', data: synth('fixed', 1, { '0:1:0': 1100, '0:1:1': -1400 }) }]);
    const p1 = a.byMode.fixed.perPosition.find((p) => p.position === 1);
    assert.strictEqual(Number((p1.worstDeviationMedianSignedFraction * 100).toFixed(3)), -7.261);
    assert.strictEqual(Number((p1.worstDeviationMedianAbsFraction * 100).toFixed(3)), 7.261);
    assert.notStrictEqual(p1.worstDeviationMedianSignedFraction, p1.worstDeviationMedianAbsFraction);
  });

  ok('rider windows are cross-tabbed by substrate relation as well as counted', () => {
    const a = analyse([{ id: 'p', data: synth('parity', 6, '2:0:4') }]);
    assert.deepStrictEqual(a.byMode.parity.crossTabRiders, { 'pos0/repeat': 1 });
    assert.deepStrictEqual(a.byMode.parity.crossTab, { 'pos0/repeat': 5, 'pos1/switch': 6 });
  });

  ok('and broken out by SUBSTRATE and position, which is what excludes substrate identity', () => {
    const a = analyse([{ id: 'p', data: synth('parity', 6, '2:0:4') }]);
    assert.deepStrictEqual(
      a.byMode.parity.perSegmentPosition.map((s) => [s.segment, s.position, s.riderWindows, s.windows]),
      [
        ['reagent-subs', 0, 1, 3],
        ['reagent-subs', 1, 0, 3],
        ['uix-subs', 0, 0, 3],
        ['uix-subs', 1, 0, 3],
      ]
    );
  });

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
  return { id: path.relative(DATA, p).replace(/\\/g, '/'), data };
}

// The floor corpus, and nothing else: this reader's whole population is
// `plan=floor` allocation records, and a `full` or clock record has neither the
// two-window round nor the leg cohort it reads.
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
    console.log(`alloc_position_confound self-test: ${checks.length - bad}/${checks.length} passed`);
    process.exit(bad ? 1 : 0);
  }
  const files = argv.includes('--corpus') ? corpus() : argv;
  if (!files.length) {
    console.error('usage: alloc_position_confound.cjs <dataset.json>... | --corpus | --self-test');
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
  windowsOf,
  roundWindowOrder,
  controlLegsOf,
  adjacency,
  ridersOf,
  excesses,
  worstExcessSignedB,
  twoProportionZ,
  analyse,
  report,
  selfTest,
  PUBLISHED_Z,
  RIDER_LO_B,
  RIDER_HI_B,
  JITTER_B,
  CLUSTER_LO_B,
  CLUSTER_HI_B,
};
