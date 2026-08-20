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

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

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
  const out = [];
  for (const r of row.perRound || []) {
    for (const [kind, c] of Object.entries(r.controls || {})) {
      if (!c || c.falls !== 0) continue;
      out.push({ runId, round: r.round, kind, legs: c.legs || [], legMedian: c.legMedian });
    }
  }
  return out;
}

// A leg's excess over its own window's cohort median, in BYTES.
const excesses = (w) => (w.legs || []).map((x) => x - w.legMedian);

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
      adjacencyWindows: adj.length,
      crossTab,
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
        worstDeviationMedianAbsFraction: median(
          ws
            .map((w) => w.legWorstDeviationFraction)
            .filter((x) => typeof x === 'number')
            .map(Math.abs)
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

  const controlLegs = controls.flatMap((c) => c.legs.map((x) => x - c.legMedian));
  return {
    modes,
    byMode,
    slots,
    bySlot,
    controls: {
      windows: controls.length,
      legs: controlLegs.length,
      riderLegs: controlLegs.filter((b) => b >= RIDER_LO_B && b <= RIDER_HI_B).length,
    },
  };
}

// --- printing ---------------------------------------------------------------

const pct = (f) => (typeof f === 'number' ? `${(f * 100).toFixed(3)}%` : 'n/a');

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
    out.push(`  adjacency (${m.adjacencyWindows} paired windows): ${JSON.stringify(m.crossTab)}`);
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
    out.push('  after controls | opens round | windows | rider windows | rider legs | median |worst dev|');
    for (const c of s.cells) {
      out.push(
        `  ${(c.afterControls ? 'yes' : 'no').padEnd(14)} | ${(c.roundFirst ? 'yes' : 'no').padEnd(11)} | ` +
          `${String(c.windows).padStart(7)} | ${String(c.riderWindows).padStart(13)} | ` +
          `${String(c.riderLegs).padStart(10)} | ` +
          `${pct(c.worstDeviationMedianAbsFraction).padStart(16)}`
      );
    }
    out.push('');
  }
  out.push(
    `CONTROLS (the null arm): ${a.controls.riderLegs} of ${a.controls.legs} legs in the band, ` +
      `over ${a.controls.windows} collection-free control windows`
  );
  return out;
}

// --- the self-test ----------------------------------------------------------
//
// A reader that cannot fail is not a reader. Every claim below is driven over a
// SYNTHETIC record whose answer is known by construction, and each one includes
// its own negative: a rider planted where the reader must see it, and a record
// with none where it must report none.

function synthRound(round, segments, riderAt) {
  const arms = {};
  segments.forEach((segment, i) => {
    const legMedian = 19280;
    const legs = [0, 1, 2, 3, 4, 5].map((o) => legMedian + (riderAt === `${round}:${i}:${o}` ? 748 : 0));
    arms[`${segment}|grid/floor`] = {
      segment,
      falls: 0,
      legs,
      legMedian,
      legWorstDeviation: (Math.max(...legs) - legMedian) / legMedian,
      primeExcess: 6864,
      certified: true,
    };
  });
  return { round, arms, segments, controls: {} };
}

function synth(segOrder, rounds, riderAt) {
  const order = ['reagent-subs', 'uix-subs'];
  return {
    alloc: {
      segOrder,
      perRound: Array.from({ length: rounds }, (_, r) =>
        synthRound(r, segOrder === 'fixed' || r % 2 === 0 ? order : [...order].reverse(), riderAt)
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
  analyse,
  report,
  selfTest,
  RIDER_LO_B,
  RIDER_HI_B,
  JITTER_B,
};
