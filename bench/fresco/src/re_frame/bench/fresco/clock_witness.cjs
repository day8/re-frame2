'use strict';
// THE PER-KEYSTROKE WITNESS'S ADJUDICATOR — rf2-0qj9w.
//
// `clock_run.cjs` drives the keystroke row; this file decides what its Event
// Timing entries and its recompute census MEAN. It is a separate module for
// `seam.cjs`'s and `order_guard.cjs`'s reason: an adjudicator that can only be
// exercised by opening a browser is an adjudicator nobody has seen refuse.
// Everything here is pure, its refusals are fixtures ([[selfTest]]), and those
// fixtures run in the fast-PR spine via `clock_witness.test.cjs`.
//
// ## THE DEFECT THIS REPAIRS
//
// The runner sent 540 measured keys — 6 rounds x 3 segments x 10 samples x 3
// non-plumb arms — and reported `totalKeys` as 180, because the arithmetic it
// printed omitted the arm axis entirely. Worse, each substrate arm received 60
// measured keys and the published table called 109-115 records
// "interactions". The cause was the grouping key
//
//     `${seg}/${arm}#${round}#${sampleIndex}#${e.interactionId || 0}`
//
// inside an already-known physical sample. One keypress raises several events;
// Chrome gives the ones that belong to the interaction a shared NONZERO
// `interactionId` and leaves the others at 0. Folding `|| 0` into the key
// therefore minted a SECOND pseudo-interaction — the zero-id `beforeinput` /
// `input` entries — beside the real keyboard interaction, and roughly doubled
// the count. A row cannot report `n` it does not have.
//
// ## THE RULE, and it is web-vitals'
//
//   * An `event` entry with `interactionId === 0` IS NOT PART OF ANY
//     INTERACTION. web-vitals ignores those entries when it computes INP, and
//     so does this. They are COUNTED and REPORTED — they are evidence about
//     what the browser did — but they never form a record.
//   * Entries sharing a nonzero `interactionId` are ONE interaction, and its
//     latency is the LONGEST of them. The entry carrying that longest duration
//     is the record's representative, so its `processingStart`/`processingEnd`
//     are the ones published beside it.
//   * ONE RECORD PER PHYSICAL KEY. The driver knows exactly which keys it
//     pressed, so that is ground truth here rather than something inferred
//     from the entries: every record is attributed to one
//     `(segment, arm, round, sample)` and every entry to one record.
//
// ## CENSORING IS PUBLISHED, NOT DROPPED
//
// `PerformanceObserver`'s minimum `durationThreshold` for `event` is 16 ms, so
// an interaction faster than that produces NO entry at all. Those keys are not
// missing data and they are not zeroes: they are LEFT-CENSORED observations,
// known to be under 16 ms and not known more precisely. Reporting a p50 over
// the surviving subset alone would be a p50 CONDITIONAL on exceeding 16 ms,
// which is a different quantity from the row's, and larger. So every arm
// publishes `sent / observed / censored`, the observed statistics are labelled
// conditional, and [[format]] states the one population fact that survives:
// when more than half an arm's keys are censored, the arm's true median is
// BELOW 16 ms whatever the observed subset says.
//
// ## WHAT IT REFUSES
//
// Every fault below exits the driver non-zero and names itself. That is the
// point of the file: this lane has repeatedly found instruments that printed a
// remark about their own incoherence and returned success anyway.
//
//   collapsed-physical-keys  two keys the driver pressed share one identity,
//                            so their entries cannot be told apart. This is
//                            the exact shape of a broken grouping.
//   unattributed-entry       an entry belonging to no key the driver pressed.
//   multi-interaction-key    one physical key carrying two distinct nonzero
//                            interaction ids.
//   shared-interaction-id    one interaction id spanning two physical keys.
//   census-mismatch          the recompute census disagrees with the shape
//                            validation.md states for the witness.
//   key-accounting           records + censored does not equal keys sent.

// The identity of a physical key. ONE list, used to build both the id of a key
// the driver pressed and the group of an entry the browser reported, so the
// two cannot drift apart — and so that breaking the grouping (dropping
// `sampleIndex`, say) is a one-token mutation whose refusal is
// `collapsed-physical-keys`.
const KEY_FIELDS = ['seg', 'arm', 'round', 'sampleIndex'];

const identify = (x) => KEY_FIELDS.map((f) => `${x[f]}`).join('#');

/** `seg/arm`, the name every other table in the driver uses. */
const armOf = (x) => `${x.seg}/${x.arm}`;

// The observer's floor, and the rounding on `duration`. Both are properties of
// PerformanceEventTiming rather than choices, and both are published on the
// row: `duration` is rounded to the nearest 8 ms and no `event` entry is
// reported below 16 ms.
const REPORTING_FLOOR_MS = 16;
const DURATION_QUANTUM_MS = 8;

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

function summarise(xs) {
  const v = [...xs].sort((a, b) => a - b);
  return { n: v.length, min: v[0], p50: p50(v), max: v[v.length - 1] };
}

/**
 * Adjudicate one keystroke row.
 *
 * @param sent     every WARM physical key the driver pressed, in press order:
 *                 `[{seg, arm, round, sampleIndex, field}]`. Ground truth —
 *                 the driver counts what it sent rather than deriving it.
 * @param entries  every Event Timing entry drained during the row, each
 *                 stamped with the sample it was drained after.
 * @param census   `{'seg/arm': {'p0/cell': n, 'p0/draft': m}}` — the recompute
 *                 census taken in a warm-up sample.
 * @param shape    `{cells, fields, substrate: [...], floors: [...]}` — the
 *                 witness's stated shape and which arm ids are which.
 */
function adjudicate({ sent, entries, census = {}, shape }) {
  const faults = [];
  const fault = (code, why) => faults.push({ code, why });

  // --- one identity per physical key ---------------------------------------
  const byKey = new Map();
  for (const k of sent) {
    const id = identify(k);
    if (byKey.has(id)) {
      fault(
        'collapsed-physical-keys',
        `two keys the driver pressed share the identity ${id} — ` +
          `${JSON.stringify(byKey.get(id).key)} and ${JSON.stringify(k)}. Their entries cannot be ` +
          `told apart, so neither is one record per physical key.`
      );
      continue;
    }
    byKey.set(id, { key: k, entries: [] });
  }

  // --- every entry to exactly one key ---------------------------------------
  const warm = entries.filter((e) => e.warm && !String(e.name).startsWith('first-input:'));
  for (const e of warm) {
    const slot = byKey.get(identify(e));
    if (!slot) {
      fault(
        'unattributed-entry',
        `an Event Timing entry (${e.name}, arm ${e.arm === null ? '<none in flight>' : armOf(e)}, ` +
          `round ${e.round}, sample ${e.sampleIndex}) belongs to no key the driver pressed. ` +
          `An entry the instrument cannot place is an entry it cannot count.`
      );
      continue;
    }
    slot.entries.push(e);
  }

  // --- one record per key, web-vitals' rules --------------------------------
  const records = [];
  const censored = [];
  const idOwner = new Map(); // interactionId -> the physical key that owns it

  for (const [id, slot] of byKey) {
    const withId = slot.entries.filter((e) => Number(e.interactionId) > 0);
    const zeroId = slot.entries.length - withId.length;
    const ids = [...new Set(withId.map((e) => Number(e.interactionId)))];

    if (ids.length > 1) {
      fault(
        'multi-interaction-key',
        `physical key ${id} carries ${ids.length} distinct nonzero interaction ids ` +
          `(${ids.join(', ')}). One keypress is one interaction; two mean the sample caught a ` +
          `keypress that was not this one.`
      );
    }
    for (const iid of ids) {
      const prior = idOwner.get(iid);
      if (prior !== undefined && prior !== id) {
        fault(
          'shared-interaction-id',
          `interaction id ${iid} appears under two physical keys (${prior} and ${id}). ` +
            `An interaction id identifies one interaction, so this is two keys reported as one.`
        );
      }
      idOwner.set(iid, id);
    }

    if (ids.length === 0) {
      // LEFT-CENSORED, and it is a reading rather than a gap: the key was
      // pressed, the page answered, and no interaction reached 16 ms.
      censored.push({ ...slot.key, zeroIdEntries: zeroId });
      continue;
    }
    // The interaction's latency is the LONGEST of its entries; that entry is
    // the record's representative, so the decomposition published beside the
    // duration is the decomposition of the entry the duration came from.
    const own = withId.filter((e) => Number(e.interactionId) === ids[0]);
    const rep = own.reduce((a, b) => (b.duration > a.duration ? b : a));
    records.push({
      ...slot.key,
      interactionId: ids[0],
      duration: rep.duration,
      processing: rep.processingEnd - rep.processingStart,
      inputDelay: rep.processingStart - rep.startTime,
      entries: slot.entries.length,
      zeroIdEntries: zeroId,
    });
  }

  // --- per arm --------------------------------------------------------------
  const perArm = {};
  const armSlot = (k) => (perArm[k] ||= { sent: 0, observed: 0, censored: 0, zeroIdEntries: 0, durations: [], processing: [], inputDelay: [] });
  for (const k of sent) armSlot(armOf(k)).sent += 1;
  for (const r of records) {
    const a = armSlot(armOf(r));
    a.observed += 1;
    a.zeroIdEntries += r.zeroIdEntries;
    a.durations.push(r.duration);
    a.processing.push(r.processing);
    a.inputDelay.push(r.inputDelay);
  }
  for (const c of censored) {
    const a = armSlot(armOf(c));
    a.censored += 1;
    a.zeroIdEntries += c.zeroIdEntries;
  }
  for (const [k, a] of Object.entries(perArm)) {
    if (a.observed + a.censored !== a.sent) {
      fault(
        'key-accounting',
        `${k}: ${a.observed} observed + ${a.censored} censored is not the ${a.sent} keys the ` +
          `driver pressed. Every key is one or the other.`
      );
    }
    a.censoredPct = a.sent ? (a.censored / a.sent) * 100 : 0;
    a.duration = summarise(a.durations);
    a.proc = summarise(a.processing);
    a.delay = summarise(a.inputDelay);
    // The one population statement censoring still permits. Everything else
    // about the observed subset is CONDITIONAL on clearing 16 ms.
    a.populationMedianUnderFloor = a.sent > 0 && a.censored * 2 > a.sent;
  }

  // --- the recompute census -------------------------------------------------
  //
  // validation.md's per-keystroke budget requires sub-recompute localisation —
  // WHICH SUBS RECOMPUTE, not merely which boundaries re-run — so this is a
  // gate and not a diagnostic. A substrate arm installs a whole app-db per
  // keystroke, so every layer-1 subscription on the page recomputes: 100 grid
  // cells plus 4 fields. A floor arm holds its drafts in React and touches the
  // substrate not at all, so its census must be EMPTY.
  const wantSubstrate = { 'p0/cell': shape.cells, 'p0/draft': shape.fields };
  const censusOf = (k) => census[k] || null;
  const describe = (o) =>
    Object.keys(o).length === 0 ? '{}' : Object.entries(o).map(([q, n]) => `${q}=${n}`).join(' ');
  for (const k of Object.keys(perArm)) {
    const arm = k.split('/')[1];
    const got = censusOf(k);
    const isSubstrate = shape.substrate.includes(arm);
    const isFloor = shape.floors.includes(arm);
    if (!isSubstrate && !isFloor) continue;
    if (got === null) {
      fault('census-mismatch', `${k}: no recompute census was taken, so nothing localises the row's subs.`);
      continue;
    }
    const want = isSubstrate ? wantSubstrate : {};
    const same =
      Object.keys(want).length === Object.keys(got).length &&
      Object.entries(want).every(([q, n]) => got[q] === n);
    if (!same) {
      fault(
        'census-mismatch',
        `${k}: recomputed ${describe(got)} where the witness states ${describe(want)}. ` +
          (isSubstrate
            ? `A keystroke installs a whole app-db, so every layer-1 sub on the page must recompute ` +
              `while exactly one field's value moves.`
            : `The floor holds its drafts in React and must recompute no subscriptions at all.`)
      );
    }
  }

  const totals = {
    sent: sent.length,
    observed: records.length,
    censored: censored.length,
    entries: warm.length,
    zeroIdEntries: warm.filter((e) => !(Number(e.interactionId) > 0)).length,
  };

  return { ok: faults.length === 0, faults, records, censored, perArm, census, totals, shape };
}

/** The published block, one line per arm plus the censoring statement. */
function format(v) {
  const out = [];
  out.push(
    `;; ---- Event Timing, ONE RECORD PER PHYSICAL KEY (web-vitals interaction-id rules) ----`
  );
  out.push(
    `;;   ${v.totals.sent} keys pressed (counted at the press, per arm) raised ${v.totals.entries} ` +
      `entries, of which ${v.totals.zeroIdEntries} carried interactionId 0 and are therefore part of ` +
      `NO interaction — counted here, never a record`
  );
  out.push(
    `;;   ${v.totals.observed} interactions observed, ${v.totals.censored} keys CENSORED — no entry, ` +
      `so under the ${REPORTING_FLOOR_MS} ms observer floor. A censored key is a reading, not a gap: ` +
      `duration is rounded to ${DURATION_QUANTUM_MS} ms and nothing below ${REPORTING_FLOOR_MS} ms is reported at all`
  );
  for (const [k, a] of Object.entries(v.perArm)) {
    if (a.observed === 0) {
      out.push(
        `;;   ${k.padEnd(28)} sent ${String(a.sent).padStart(3)}  observed   0  censored ` +
          `${String(a.censored).padStart(3)} (100.0%) — EVERY interaction landed under the ` +
          `${REPORTING_FLOOR_MS} ms floor, which is a result and not a gap`
      );
      continue;
    }
    out.push(
      `;;   ${k.padEnd(28)} sent ${String(a.sent).padStart(3)}  observed ${String(a.observed).padStart(3)}  ` +
        `censored ${String(a.censored).padStart(3)} (${a.censoredPct.toFixed(1)}%)  ` +
        `duration p50 ${a.duration.p50.toFixed(1)} ms [${a.duration.min.toFixed(1)} – ${a.duration.max.toFixed(1)}]  ` +
        `processing p50 ${a.proc.p50.toFixed(3)} ms  input-delay p50 ${a.delay.p50.toFixed(3)} ms`
    );
    out.push(
      `;;     ${''.padEnd(26)} those statistics are CONDITIONAL on clearing ${REPORTING_FLOOR_MS} ms` +
        (a.populationMedianUnderFloor
          ? ` — and with ${a.censoredPct.toFixed(1)}% censored the arm's TRUE median is below ` +
            `${REPORTING_FLOOR_MS} ms, whatever the observed subset reads`
          : ``)
    );
  }
  out.push(`;;   ---- sub-recompute localisation (validation.md's per-keystroke budget) ----`);
  for (const [k, c] of Object.entries(v.census)) {
    const body = Object.keys(c).length === 0 ? 'no subscriptions at all' : Object.entries(c).map(([q, n]) => `${q} x${n}`).join(', ');
    out.push(`;;   ${k.padEnd(28)} ${body}`);
  }
  out.push(
    `;;   the witness states ${v.shape.cells} grid cells + ${v.shape.fields} fields = ` +
      `${v.shape.cells + v.shape.fields} layer-1 recomputes per keystroke on a substrate arm, and NONE ` +
      `on a floor arm. Exactly one field's value moves; all four are read back.`
  );
  for (const f of v.faults) out.push(`;;   REFUSED [${f.code}] ${f.why}`);
  return out;
}

// ---------------------------------------------------------------------------
// The fixtures — every refusal, plus the defect this file repairs
// ---------------------------------------------------------------------------

const SHAPE = { cells: 100, fields: 4, substrate: ['hicasso'], floors: ['floor', 'ctl-50ms'] };

const cleanCensus = (arm) => ({ [`s/${arm}`]: arm === 'hicasso' ? { 'p0/cell': 100, 'p0/draft': 4 } : {} });

/** One physical key's worth of entries: the interaction, plus zero-id noise. */
function keyEntries(k, { interactionId, duration, zeroIds = 2 }) {
  const base = { seg: k.seg, arm: k.arm, round: k.round, sampleIndex: k.sampleIndex, warm: true };
  const es = [];
  if (interactionId > 0) {
    es.push({ ...base, name: 'keydown', interactionId, duration: duration - DURATION_QUANTUM_MS, startTime: 0, processingStart: 1, processingEnd: 2 });
    es.push({ ...base, name: 'keyup', interactionId, duration, startTime: 0, processingStart: 1, processingEnd: 3 });
  }
  for (let i = 0; i < zeroIds; i++) {
    es.push({ ...base, name: i === 0 ? 'beforeinput' : 'input', interactionId: 0, duration: 24, startTime: 0, processingStart: 1, processingEnd: 2 });
  }
  return es;
}

function selfTest() {
  const checks = [];
  const check = (name, ok) => checks.push({ name, ok });

  const key = (round, sampleIndex, arm = 'hicasso') => ({ seg: 's', arm, round, sampleIndex, field: sampleIndex % 4 });

  // 1. THE DEFECT ITSELF. One physical key, one nonzero interaction, two
  //    zero-id entries beside it. The old grouping minted TWO records here.
  {
    const k = key(0, 0);
    const v = adjudicate({
      sent: [k],
      entries: keyEntries(k, { interactionId: 7, duration: 32 }),
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('one physical key with zero-id noise forms exactly ONE record', v.ok && v.records.length === 1);
    check('the zero-id entries are counted, not silently dropped', v.totals.zeroIdEntries === 2 && v.records[0].zeroIdEntries === 2);
    check("the record's latency is the LONGEST entry of the interaction", v.records[0].duration === 32);
  }

  // 2. CENSORING. A key with no entries at all is a reading.
  {
    const ks = [key(0, 0), key(0, 1), key(0, 2)];
    const v = adjudicate({
      sent: ks,
      entries: keyEntries(ks[0], { interactionId: 7, duration: 40 }),
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('a key with no entry is CENSORED, not dropped', v.ok && v.censored.length === 2 && v.records.length === 1);
    check('records + censored = keys sent', v.perArm['s/hicasso'].observed + v.perArm['s/hicasso'].censored === 3);
    check(
      'a majority-censored arm reports its true median as under the floor',
      v.perArm['s/hicasso'].populationMedianUnderFloor === true
    );
    check('the censoring is published in the formatted block', format(v).some((l) => l.includes('censored')));
  }

  // 3. EVERY KEY CENSORED is a result and says so.
  {
    const ks = [key(0, 0), key(0, 1)];
    const v = adjudicate({ sent: ks, entries: [], census: cleanCensus('hicasso'), shape: SHAPE });
    check('an arm with no entries at all still passes', v.ok && v.records.length === 0);
    check('and its line says the floor is the result', format(v).some((l) => l.includes('a result and not a gap')));
  }

  // 4. THE MUTATION. Two physical keys sharing one identity — what a grouping
  //    that dropped `sampleIndex` produces — is refused by name.
  {
    const k = key(0, 0);
    const v = adjudicate({ sent: [k, { ...k }], entries: [], census: cleanCensus('hicasso'), shape: SHAPE });
    check('two keys collapsed into one identity REFUSES', !v.ok && v.faults.some((f) => f.code === 'collapsed-physical-keys'));
  }

  // 5. An entry belonging to no key the driver pressed.
  {
    const k = key(0, 0);
    const v = adjudicate({
      sent: [k],
      entries: keyEntries(key(3, 9), { interactionId: 7, duration: 32 }),
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('an unattributable entry REFUSES', !v.ok && v.faults.some((f) => f.code === 'unattributed-entry'));
  }

  // 6. Two distinct interactions inside one physical key.
  {
    const k = key(0, 0);
    const v = adjudicate({
      sent: [k],
      entries: [
        ...keyEntries(k, { interactionId: 7, duration: 32, zeroIds: 0 }),
        ...keyEntries(k, { interactionId: 9, duration: 24, zeroIds: 0 }),
      ],
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('two interaction ids in one physical key REFUSES', !v.ok && v.faults.some((f) => f.code === 'multi-interaction-key'));
  }

  // 7. One interaction id spanning two physical keys.
  {
    const a = key(0, 0);
    const b = key(0, 1);
    const v = adjudicate({
      sent: [a, b],
      entries: [
        ...keyEntries(a, { interactionId: 7, duration: 32, zeroIds: 0 }),
        ...keyEntries(b, { interactionId: 7, duration: 24, zeroIds: 0 }),
      ],
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('one interaction id across two keys REFUSES', !v.ok && v.faults.some((f) => f.code === 'shared-interaction-id'));
  }

  // 8. The recompute census is a GATE.
  {
    const k = key(0, 0);
    const base = { sent: [k], entries: keyEntries(k, { interactionId: 7, duration: 32 }), shape: SHAPE };
    const short = adjudicate({ ...base, census: { 's/hicasso': { 'p0/cell': 1, 'p0/draft': 1 } } });
    check(
      'a substrate arm that localised its recomputes REFUSES (the witness states all 104)',
      !short.ok && short.faults.some((f) => f.code === 'census-mismatch')
    );
    const none = adjudicate({ ...base, census: {} });
    check('a missing census REFUSES', !none.ok && none.faults.some((f) => f.code === 'census-mismatch'));
    const kf = key(0, 0, 'floor');
    const dirty = adjudicate({
      sent: [kf],
      entries: keyEntries(kf, { interactionId: 7, duration: 32 }),
      census: { 's/floor': { 'p0/cell': 100 } },
      shape: SHAPE,
    });
    check(
      'a FLOOR arm that recomputed any subscription REFUSES',
      !dirty.ok && dirty.faults.some((f) => f.code === 'census-mismatch')
    );
    const cleanFloor = adjudicate({
      sent: [kf],
      entries: keyEntries(kf, { interactionId: 7, duration: 32 }),
      census: { 's/floor': {} },
      shape: SHAPE,
    });
    check('a floor arm with an empty census passes', cleanFloor.ok);
  }

  // 9. WARM-UP ENTRIES ARE NOT DATA. The driver drains them; they must not
  //    become records, and they must not become unattributed faults either.
  {
    const k = key(0, 0);
    const cold = keyEntries(key(0, 99), { interactionId: 11, duration: 40 }).map((e) => ({ ...e, warm: false }));
    const v = adjudicate({
      sent: [k],
      entries: [...cold, ...keyEntries(k, { interactionId: 7, duration: 32 })],
      census: cleanCensus('hicasso'),
      shape: SHAPE,
    });
    check('warm-up entries are excluded rather than refused', v.ok && v.records.length === 1);
  }

  // 10. THE FULL SHAPE the row publishes: 60 keys per arm per segment.
  {
    const sent = [];
    const entries = [];
    let iid = 1;
    for (let round = 0; round < 6; round++) {
      for (let s = 0; s < 10; s++) {
        const k = key(round, s);
        sent.push(k);
        // Half over the floor, half under it, so both branches are exercised.
        if (s % 2 === 0) entries.push(...keyEntries(k, { interactionId: iid++, duration: 24 }));
        else entries.push(...keyEntries(k, { interactionId: 0, duration: 0 }));
      }
    }
    const v = adjudicate({ sent, entries, census: cleanCensus('hicasso'), shape: SHAPE });
    check('60 keys per arm reconcile exactly', v.ok && v.totals.sent === 60 && v.totals.observed === 30 && v.totals.censored === 30);
    check('and the count is never inflated past the keys pressed', v.totals.observed <= v.totals.sent);
  }

  return { checks };
}

module.exports = { adjudicate, format, selfTest, KEY_FIELDS, REPORTING_FLOOR_MS, DURATION_QUANTUM_MS };
