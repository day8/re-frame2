'use strict';
// THE FLOOR ARM'S WITHIN-RUN LEVEL WITNESS — rf2-a233t.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs <dataset.json>...
//     node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs --corpus
//     node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs --self-test
//
// ## THE DEFECT THIS REFUSES
//
// The floor arm is MULTI-MODAL. Most runs settle at 19,100 / 19,540 B per
// write; some settle 2,532 B higher, and one level 3,792 B higher has been
// seen. `rf2-c4hhk`'s seventy-run window established that the levels are real
// — an armed run and an unarmed run trace the elevated ramp byte for byte —
// and `rf2-77gz8` established that the work count is identical across them, so
// neither the instrument nor different-work-per-write explains the step.
//
// BOTH MODES STILL CERTIFY. The leg witness in `p0_run.cjs` asks whether a
// window's legs are ALIKE, and in both modes they are: an elevated window is
// six legs of 21,632 B, as internally consistent as six legs of 19,100 B. It
// has nothing to say about WHICH of two levels the window sits at. So the
// runner passes an elevated window without remark, and any figure quoted from
// this arm out of a single run is a coin toss between two levels 13-21% apart.
//
// ## THE RULE
//
// A run that is at one level is at that level THROUGHOUT. The elevated mode is
// not a run that starts high — it is a run that starts at the low level, steps
// over rounds 4-5, and settles high. So the two populations are separated
// inside a single run's own data, and no second run and no new instrument are
// needed to tell them apart:
//
//   BEFORE(seg) = MINIMUM of `legMedian` over CERTIFIED windows at rounds 1-5
//   AFTER(seg)  = MEDIAN  of `legMedian` over CERTIFIED windows at round >= 6
//   STEP(seg)   = AFTER - BEFORE
//
//   REFUSE when STEP(seg) > BOUND x BEFORE(seg) for any segment.
//
// ## WHY THOSE TWO STATISTICS, WHICH IS NOT A SYMMETRY
//
// AFTER is not a new quantity: it is the PUBLISHED ESTIMATOR, verbatim — the
// median over certified windows at round index >= 6, per segment, declared in
// `rf2-77gz8`'s pre-registration and carried unchanged by `rf2-c4hhk`. The
// witness therefore adjudicates exactly the number the records quote, which is
// the only number a level gate has any business gating.
//
// BEFORE is a MINIMUM rather than a median, and the asymmetry is the point.
// The error available to an early round is ONE-SIGNED: those rounds carry
// warm-up allocation the settled rounds do not, so an early window can read
// ABOVE the run's floor and cannot read below it. A window that read below its
// cohort would already have been refused by the leg witness — "a leg BELOW its
// cohort is a leg something removed bytes from, and nothing in the work unit
// removes bytes; the collector does" — so a certified window is not available
// to be spuriously low. The minimum of the certified early windows is
// therefore the best estimate of the level the run started at, and a median
// over as few as one or two windows is not: four runs read a NEGATIVE step
// under the median form, the worst at -1,014 B, every one of them a normal run
// with a single inflated early round inside a two-window median.
//
// Round 0 is outside the window because it is the prime round and is
// uncertified in every run in the corpus.
//
// ## WHY ROUNDS 4-5 ARE IN THE WINDOW WHEN THEY ARE THE TRANSITION
//
// Because a minimum cannot be raised by them, and coverage is free. MEASURED
// over every committed admissible run: the BEFORE minimum came from rounds 1-3
// in 201 of 202 segment-halves — the ramp is never the lowest certified window
// when a pre-ramp one exists — so including rounds 4-5 changes no reading. The
// one half where it does is `alloc-c4hhk/armed-03`'s `reagent-subs`, where
// NONE of rounds 1-3 certified. Under a rounds-1-3 window that run has no
// BEFORE at all and the witness must refuse a run whose level is not elevated;
// under this window it is scored, at +0.494%, and certifies.
//
// THE COST, STATED AND MEASURED. When a segment's rounds 1-3 all fail to
// certify, BEFORE falls back to a ramp round, which sits BETWEEN the levels on
// an elevated run and so SHRINKS its step. That biases toward missing a mode
// run, never toward refusing a normal one. Forcing every elevated run in the
// corpus to that worst case, 38 of 40 still exceed the bound and two would
// slip — so the degradation is real, it is one-sided in the safe direction for
// false refusals, and it is DISCLOSED rather than hidden: `beforeFromRamp` is
// set on any segment scored that way, and no elevated run in the corpus is.
//
// ## THE TWO OTHER DEFINITIONS THE CORPUS HAS USED, AND WHY NEITHER IS PINNED
//
// The difference is entirely about whether a ramp round is allowed inside a
// LEVEL's window, and it is worth about 2.4 kB of separation. Scored over the
// same 100 admissible runs, taking each run's worst segment:
//
//   | definition                                | normal band  | mode band     |
//   |-------------------------------------------|--------------|---------------|
//   | min(cert r1-5) -> median(cert r>=6) PINNED |   96..194 B  | 2,616..3,984 B|
//   | median(r1-3) -> median(r>=6)               |   96..168 B  | 2,616..3,948 B|
//   | median(r4-6) - median(r1-3)                |   90..896 B  | 1,655..4,083 B|
//   | r4 - r3, one round against one round       |   80..2,312 B|   860..4,946 B|
//
// THE LAST ONE DOES NOT SEPARATE THE POPULATIONS AT ALL. It is the form
// `rf2-77gz8`'s re-analysis quoted as -43 to +158 B normal against 3,912-3,948
// B in the mode, and on the larger corpus its normal band reaches +2,312 B
// while its mode band descends to +860 B — they OVERLAP by 1,452 B. A bound
// set from that quoted band would have refused legitimate runs. It is also
// undefined on at least one segment in 95 of the 101 admissible runs, and on
// BOTH segments in 16 of them, because one round is not always certified —
// a definition that cannot be evaluated is not a gate. The median(r4-6) form
// separates, but leaves only 759 B of gap against this form's 2,422 B,
// because rounds 4-5 are in the AFTER half where they belong to neither level.
//
// ## WHY THE TEST IS ONE-SIDED
//
// A NEGATIVE step is not this defect and is not gated. The gate exists to stop
// an elevated PUBLISHED figure, and the published figure is AFTER: a run whose
// early rounds read above its settled level publishes the lower number, which
// is the correct one. Under the pinned estimator no admissible run in the
// corpus produces a negative step at all, so the rule costs nothing here; it
// is one-sided because of what the gate is FOR, not because of what the corpus
// happened to show.
//
// ## THE BOUND, AND WHERE IT COMES FROM
//
// BOUND = 5% of the run's own BEFORE level (~950 B at this arm's level).
//
// It is a fraction rather than a byte count so that it carries no constant
// tied to this plan's roots and cells, and so a plan that moves the level does
// not silently move the gate with it. Measured over every committed admissible
// floor run — 100 scored runs across four revisions and four corpora:
//
//   normal population   n=60    0.494% .. 1.015%   (96 .. 194 B)
//   elevated population n=40   13.766% .. 21.070%  (2,616 .. 3,984 B)
//
// 5% sits 4.9x above the largest step the normal population has ever produced
// and 2.75x below the smallest the mode has. Nothing was fitted: the number is
// one twentieth, and the corpus was consulted only to check that both
// populations sit far from it.
//
// THE BOUND DOES NOT DEPEND ON THE MODE'S RATE, which matters because that
// rate is not stable — `rf2-6kxub` measured 0%, 10% and 53% across three
// windows at ONE revision. Nothing here is sized against a rate: the witness
// scores each run against that run's own earlier rounds, so a window of one
// run and a window of seventy are adjudicated identically, and a window taken
// on a day when the mode never appears is scored by the same rule as one taken
// on a day when it appears in half the runs.
//
// ## WHAT IT REFUSES
//
//   * `level-step`   — a segment's settled level is more than BOUND above its
//                      own pre-transition level. The run is in the elevated
//                      mode and its figures must not be quoted.
//   * `level-window` — a segment has no certified window in one of the two
//                      halves. There is then no settled level to certify, and
//                      — since AFTER is the published estimator — no figure to
//                      quote either.
//   * `no-alloc`     — the record carries no `alloc` object. `rf2-c4hhk`'s
//                      `armed-25` is the corpus example: Chromium failed to
//                      launch, nothing was measured, and the driver still
//                      exited 1.
//   * `inadmissible` — the positive control failed or a read-back went
//                      unverified. Reported, and the level is not adjudicated.
//
// ## WHY THIS IS ANALYSIS-SIDE, AND NOT A CHANGE TO THE RUNNER
//
// `implementation/core/test/re_frame/bench/` has not changed since
// `408dfb0aa8`. Every dataset in `alloc-77gz8`, `alloc-c4hhk` and
// `workcount-n1b9h` was produced by that one instrument, and `rf2-6kxub` leans
// on exactly that when it compares rates across windows. A witness computable
// from data ALREADY RECORDED costs that constancy nothing; the same witness
// soldered into `p0_run.cjs` would end it, and would put a gate inside the rig
// whose invariance the published series rests on. So this file reads records
// and never writes one, and the bundle the arm compiles is untouched.

const fs = require('node:fs');
const path = require('node:path');

// The pinned windows and the bound. Exported so a caller can re-score the
// corpus under a different definition without editing this file — the record
// in docs/design/hicasso/studio/ does exactly that to build the table above.
const BEFORE_ROUNDS = [1, 5];
const BEFORE_PRE_RAMP_MAX = 3; // rounds above this are the transition; see the header
const AFTER_ROUND_MIN = 6;
const LEVEL_STEP_BOUND = 0.05;
const HIGH_MODE_FLOOR_B = 21000; // rf2-77gz8's classification criterion, for reporting only

function median(xs) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

// PER-SEGMENT, NOT PER-ROUND. A round holds one window per segment and the
// levels are page-global — rf2-c4hhk found the offset identical on both
// segments in 34 of 37 elevated runs — but the two segments sit at different
// absolute levels (19,100 against 19,540), so they are never pooled.
function segmentsOf(alloc) {
  const out = new Map();
  for (const round of alloc.perRound || []) {
    for (const arm of Object.values(round.arms || {})) {
      const seg = arm.segment;
      if (!out.has(seg)) out.set(seg, []);
      out.get(seg).push({ round: round.round, legMedian: arm.legMedian, certified: arm.certified === true });
    }
  }
  return out;
}

const inWindow = (rows, lo, hi) =>
  rows.filter((r) => r.certified && typeof r.legMedian === 'number' && r.round >= lo && r.round <= hi);

// ADMISSIBILITY IS THE RECORD'S, NOT THE EXIT CODE'S. `--only alloc` exits
// non-zero as a matter of course — it exits on any refused window and on any
// collection inside a measured one, and both are routine at this page. All
// seventy of rf2-c4hhk's runs exited 1, including the one that measured
// nothing. The two run-level gates are the positive control and the read-back
// verification; the per-window certificate is inside the estimator above.
function admissibility(alloc) {
  const reasons = [];
  if (!alloc || typeof alloc !== 'object') {
    return { ok: false, reasons: ['the record carries no `alloc` object at all'] };
  }
  if (alloc.controlVerdict?.ok !== true) reasons.push('the positive control did not adjudicate ok (`controlVerdict.ok`)');
  if (alloc.verification?.unverified !== 0) reasons.push(`${alloc.verification?.unverified} read-back(s) went unverified`);
  return { ok: reasons.length === 0, reasons };
}

function adjudicate(record, { bound = LEVEL_STEP_BOUND } = {}) {
  const faults = [];
  const fault = (code, message) => faults.push({ code, message });
  const alloc = record && record.alloc;

  if (!alloc) {
    fault(
      'no-alloc',
      'the record carries no `alloc` object: nothing was measured. A driver that fails to open a page still writes a record and still ' +
        'exits 1, so this is invisible in the exit code and obvious here.'
    );
    return { ok: false, admissible: false, faults, segments: [], bound };
  }

  const adm = admissibility(alloc);
  if (!adm.ok) {
    for (const r of adm.reasons) fault('inadmissible', `${r} — the run is excluded and named, and its level is not adjudicated.`);
    return { ok: false, admissible: false, faults, segments: [], bound };
  }

  const segments = [];
  for (const [seg, rows] of segmentsOf(alloc)) {
    const beforeRows = inWindow(rows, BEFORE_ROUNDS[0], BEFORE_ROUNDS[1]);
    const afterRows = inWindow(rows, AFTER_ROUND_MIN, Infinity);
    const lowest = beforeRows.length ? beforeRows.reduce((a, b) => (b.legMedian < a.legMedian ? b : a)) : null;
    const before = lowest ? lowest.legMedian : null;
    const after = median(afterRows.map((r) => r.legMedian));
    const s = {
      segment: seg,
      before,
      after,
      beforeRound: lowest ? lowest.round : null,
      beforeFromRamp: lowest ? lowest.round > BEFORE_PRE_RAMP_MAX : false,
      nBefore: beforeRows.length,
      nAfter: afterRows.length,
      step: before === null || after === null ? null : after - before,
      fraction: before === null || after === null || before === 0 ? null : (after - before) / before,
      elevated: after !== null && after >= HIGH_MODE_FLOOR_B,
    };
    segments.push(s);

    if (before === null || after === null) {
      const half = before === null ? `rounds ${BEFORE_ROUNDS[0]}-${BEFORE_ROUNDS[1]}` : `rounds >= ${AFTER_ROUND_MIN}`;
      fault(
        'level-window',
        `${seg}: no certified window in ${half}, so this segment has no level to compare. AFTER is the published estimator, so a run ` +
          'missing it has no figure to quote either — refusing is the same statement as "nothing was certified here".'
      );
      continue;
    }
    if (s.fraction > bound) {
      fault(
        'level-step',
        `${seg}: settled at ${after} B/write against ${before} B/write before the transition — a step of +${s.step} B ` +
          `(${(s.fraction * 100).toFixed(3)}%), past the ${(bound * 100).toFixed(0)}% bound (+${Math.round(bound * before)} B). ` +
          "The run changed level mid-window, so its settled figure is one of two modes and must not be quoted as the arm's."
      );
    }
  }

  if (!segments.length) fault('level-window', 'the record carries no per-round arm windows at all.');

  return { ok: faults.length === 0, admissible: true, faults, segments, bound };
}

function format(v, label) {
  const out = [];
  out.push(`;; ${label || 'run'} — within-run LEVEL witness (rf2-a233t)`);
  for (const s of v.segments) {
    const step = s.step === null ? '—' : `${s.step >= 0 ? '+' : ''}${s.step} B`;
    const pct = s.fraction === null ? '—' : `${(s.fraction * 100).toFixed(3)}%`;
    out.push(
      `;;   ${s.segment.padEnd(14)} before ${String(s.before ?? '—').padStart(9)} @r${String(s.beforeRound ?? '-').padStart(2)} (n=${s.nBefore})` +
        `  after ${String(s.after ?? '—').padStart(9)} (n=${s.nAfter})  step ${step.padStart(9)}  ${pct.padStart(8)}` +
        (s.beforeFromRamp ? '  [BEFORE fell back to a ramp round — the step is understated]' : '')
    );
  }
  if (v.ok) {
    out.push(`;;   CERTIFIED: every segment holds its level across the transition, within ${(v.bound * 100).toFixed(0)}%.`);
  } else {
    for (const f of v.faults) out.push(`;;   REFUSED [${f.code}] ${f.message}`);
  }
  return out.join('\n');
}

// --- the corpus control, as a function rather than as prose ------------------
//
// The claim the bound rests on is that it refuses EXACTLY the elevated runs and
// nothing else. That claim is checkable against every dataset this repository
// has committed, so it is checked rather than asserted, and
// `alloc_level_witness.test.cjs` runs it on every fast-PR spine. Classification
// is rf2-77gz8's criterion — either segment's published estimator at or above
// 21,000 B/write — which is INDEPENDENT of the step this witness measures.

const CORPORA = ['alloc-c4hhk', 'alloc-77gz8', 'alloc-9jrhi', 'workcount-n1b9h'];

function scoreCorpus({ dataDir = path.join(__dirname, 'data'), corpora = CORPORA, bound = LEVEL_STEP_BOUND } = {}) {
  const rows = [];
  for (const corpus of corpora) {
    const dir = path.join(dataDir, corpus);
    if (!fs.existsSync(dir)) continue;
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
      const record = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
      const v = adjudicate(record, { bound });
      rows.push({
        corpus,
        run: file.replace(/\.json$/, ''),
        admissible: v.admissible,
        elevated: v.segments.some((s) => s.elevated),
        refused: !v.ok,
        codes: [...new Set(v.faults.map((f) => f.code))],
        verdict: v,
      });
    }
  }
  const scored = rows.filter((r) => r.admissible);
  const notComputable = scored.filter((r) => r.codes.includes('level-window'));
  const stepped = scored.filter((r) => !r.codes.includes('level-window'));
  const falsePositives = stepped.filter((r) => !r.elevated && r.codes.includes('level-step'));
  const misses = stepped.filter((r) => r.elevated && !r.codes.includes('level-step'));
  const band = (elev) => {
    const xs = stepped.filter((r) => r.elevated === elev).map((r) => ({
      pct: Math.max(...r.verdict.segments.map((s) => s.fraction)),
      b: Math.max(...r.verdict.segments.map((s) => s.step)),
    }));
    if (!xs.length) return { n: 0 };
    return {
      n: xs.length,
      minPct: Math.min(...xs.map((x) => x.pct)), maxPct: Math.max(...xs.map((x) => x.pct)),
      minB: Math.min(...xs.map((x) => x.b)), maxB: Math.max(...xs.map((x) => x.b)),
    };
  };
  return {
    rows,
    scored: scored.length,
    inadmissible: rows.filter((r) => !r.admissible),
    elevated: scored.filter((r) => r.elevated).length,
    refusedForStep: scored.filter((r) => r.codes.includes('level-step')).length,
    degraded: stepped.filter((r) => r.verdict.segments.some((s) => s.beforeFromRamp)),
    falsePositives, misses, notComputable,
    normal: band(false), mode: band(true), bound,
  };
}

// --- fixtures ---------------------------------------------------------------
//
// Synthetic, so the refusals can be seen without opening a browser and without
// reading 20 MB of committed JSON. The corpus control above is the other half.

function synth({
  before = 19004, after = 19100, rounds = 18,
  certifyBefore = [1, 2, 3], certifyRamp = false, certifyAfter = true,
  segments = ['reagent-subs', 'uix-subs'],
} = {}) {
  const perRound = [];
  for (let round = 0; round < rounds; round++) {
    const arms = {};
    for (const segment of segments) {
      const isRamp = round === 4 || round === 5;
      const preRamp = round >= 1 && round <= 3;
      const legMedian = preRamp ? before : isRamp ? Math.round((before + after) / 2) : after;
      arms[`${segment}|grid/floor`] = {
        segment,
        legMedian,
        certified: preRamp ? certifyBefore.includes(round) : isRamp ? certifyRamp : round >= AFTER_ROUND_MIN && certifyAfter,
      };
    }
    perRound.push({ round, arms });
  }
  return { alloc: { controlVerdict: { ok: true }, verification: { unverified: 0 }, rounds, perRound } };
}

function selfTest() {
  const checks = [];
  const check = (name, ok) => checks.push({ name, ok });
  const has = (v, code) => v.faults.some((f) => f.code === code);

  // 1. THE NORMAL POPULATION. The corpus's own low level and the largest
  //    settling movement it has ever produced both certify.
  {
    check('a flat run certifies', adjudicate(synth({ before: 19004, after: 19004 })).ok);
    check('the corpus low level (19,004 -> 19,100, +96 B) certifies', adjudicate(synth({ before: 19004, after: 19100 })).ok);
    check('the largest settling step ever measured (+194 B) certifies', adjudicate(synth({ before: 19184, after: 19378 })).ok);
    check('the largest settling FRACTION ever measured (1.015%) certifies', adjudicate(synth({ before: 18908, after: 19100 })).ok);
  }

  // 2. THE DEFECT. Both elevated levels in the corpus, and the least-marginal
  //    elevated run there is.
  {
    const nu = adjudicate(synth({ before: 19004, after: 21632 })); // +2,628 B, the `new` level
    check('the +2,532 level REFUSES', !nu.ok && has(nu, 'level-step'));
    const hi = adjudicate(synth({ before: 18944, after: 22892 })); // +3,948 B, the `high` level
    check('the +3,792 level REFUSES', !hi.ok && has(hi, 'level-step'));
    const marginal = adjudicate(synth({ before: 19016, after: 21632 })); // +2,616 B, the smallest in the corpus
    check('the smallest elevated step in the corpus REFUSES', !marginal.ok && has(marginal, 'level-step'));
  }

  // 3. THE BOUND IS WHERE IT SAYS IT IS. Exactly at 5% certifies; a byte past
  //    refuses. A gate nobody has watched cross its own threshold is a gate
  //    nobody has watched.
  {
    check('a step of exactly the bound certifies', adjudicate(synth({ before: 20000, after: 21000 })).ok);
    const over = adjudicate(synth({ before: 20000, after: 21001 })); // 5.005%
    check('one byte past the bound REFUSES', !over.ok && has(over, 'level-step'));
  }

  // 4. ONE-SIDED. A run whose early rounds read high publishes the LOWER
  //    number, so it is not this defect and is not gated.
  {
    check('a large NEGATIVE step certifies — the published figure is the lower one', adjudicate(synth({ before: 20554, after: 19540 })).ok);
  }

  // 5. EITHER SEGMENT REFUSES THE RUN. The levels are page-global, but a
  //    witness that needed both to agree would pass the 3-in-37 that do not.
  {
    const one = synth({ before: 19004, after: 19100 });
    for (const round of one.alloc.perRound) {
      if (round.round >= AFTER_ROUND_MIN) round.arms['uix-subs|grid/floor'].legMedian = 22072;
    }
    const v = adjudicate(one);
    check('one elevated segment refuses the whole run', !v.ok && has(v, 'level-step'));
    check('and the certified segment is still reported', v.segments.find((s) => s.segment === 'reagent-subs').step === 96);
  }

  // 6. A HALF WITH NO CERTIFIED WINDOW IS A REFUSAL, NOT A PASS. The silent
  //    failure this replaces is a witness that computes a step from an empty
  //    window and reports NaN as "within bound".
  {
    const noBefore = adjudicate(synth({ certifyBefore: [], certifyRamp: false }));
    check('an empty BEFORE window REFUSES', !noBefore.ok && has(noBefore, 'level-window'));
    const noAfter = adjudicate(synth({ rounds: 6 })); // the 6-round pilot's shape
    check('a run with no round >= 6 REFUSES', !noAfter.ok && has(noAfter, 'level-window'));
    const nb = adjudicate(synth({ certifyBefore: [], certifyRamp: false })).segments[0];
    check('and it reports the empty half rather than a NaN step', nb.step === null && nb.nBefore === 0);
  }

  // 7. BEFORE IS A MINIMUM, so the transition cannot raise it. This is the
  //    whole of what lets rounds 4-5 sit in the window, and a mutation that
  //    turns it into a mean or a median must be visible.
  {
    const ramp = adjudicate(synth({ before: 19004, after: 19100, certifyRamp: true }));
    check('certified ramp rounds do not raise BEFORE', ramp.ok && ramp.segments[0].before === 19004 && ramp.segments[0].beforeRound <= 3);
    const r = synth({ before: 19004, after: 19100, certifyBefore: [1, 2, 3] });
    r.alloc.perRound[2].arms['reagent-subs|grid/floor'].legMedian = 21688; // one inflated early round
    const v = adjudicate(r);
    check('one inflated early round does not raise BEFORE', v.ok && v.segments.find((s) => s.segment === 'reagent-subs').before === 19004);
    const r0 = synth({ before: 19004, after: 19100 });
    r0.alloc.perRound[0].arms['reagent-subs|grid/floor'] = { segment: 'reagent-subs', legMedian: 1, certified: true };
    check('round 0 is outside the window even when certified and lowest', adjudicate(r0).segments.find((s) => s.segment === 'reagent-subs').before === 19004);
  }

  // 8. THE RAMP FALLBACK IS DISCLOSED. When rounds 1-3 give nothing the run is
  //    still scored, and the reading is marked as understating its step.
  {
    const v = adjudicate(synth({ before: 19004, after: 19100, certifyBefore: [], certifyRamp: true }));
    check('a segment with only ramp rounds is scored rather than refused', v.ok);
    check('and it is marked beforeFromRamp', v.segments.every((s) => s.beforeFromRamp === true));
    check('an ordinary reading is NOT marked beforeFromRamp', adjudicate(synth()).segments.every((s) => s.beforeFromRamp === false));
  }

  // 9. ADMISSIBILITY IS READ OFF THE RECORD. The exit code is not a criterion
  //    and neither is the absence of one.
  {
    const noAlloc = adjudicate({ generatedAt: 'x', build: 'hicasso-bench' });
    check('a record with no `alloc` object REFUSES', !noAlloc.ok && has(noAlloc, 'no-alloc'));
    const badControl = synth();
    badControl.alloc.controlVerdict.ok = false;
    const bc = adjudicate(badControl);
    check('a failed positive control is INADMISSIBLE, not certified', !bc.ok && has(bc, 'inadmissible'));
    const unverified = synth({ before: 19004, after: 21632 });
    unverified.alloc.verification.unverified = 2;
    const uv = adjudicate(unverified);
    check('an unverified read-back is INADMISSIBLE and the level is not adjudicated', !uv.ok && has(uv, 'inadmissible') && !has(uv, 'level-step'));
  }

  // 10. THE BOUND IS A FRACTION OF THE RUN'S OWN LEVEL, so a plan sitting at a
  //     different level is gated at the same relative strictness.
  {
    check('a tenfold level with the same relative step certifies', adjudicate(synth({ before: 190040, after: 191000 })).ok);
    check("a tenfold level with the mode's relative step REFUSES", !adjudicate(synth({ before: 190040, after: 216320 })).ok);
  }

  return { checks };
}

// --- CLI --------------------------------------------------------------------

function main(argv) {
  const args = argv.slice(2);
  if (args.includes('--self-test')) {
    const { checks } = selfTest();
    const bad = checks.filter((c) => !c.ok);
    for (const c of checks) console.log(`${c.ok ? 'ok  ' : 'FAIL'} ${c.name}`);
    console.log(`;; ${checks.length - bad.length}/${checks.length} fixtures pass`);
    return bad.length ? 1 : 0;
  }
  if (args.includes('--corpus')) {
    const r = scoreCorpus();
    console.log(`;; corpus control — bound ${(r.bound * 100).toFixed(0)}% of each run's own BEFORE level`);
    console.log(`;;   admissible ${r.scored}   scored ${r.normal.n + r.mode.n}   elevated ${r.elevated}   refused-for-step ${r.refusedForStep}`);
    console.log(`;;   FALSE POSITIVES ${r.falsePositives.length}   MISSES ${r.misses.length}   not-computable ${r.notComputable.length}   ramp-fallback readings ${r.degraded.length}`);
    console.log(`;;   normal n=${r.normal.n}  ${r.normal.minB}..${r.normal.maxB} B  ${(r.normal.minPct * 100).toFixed(3)}%..${(r.normal.maxPct * 100).toFixed(3)}%`);
    console.log(`;;   mode   n=${r.mode.n}  ${r.mode.minB}..${r.mode.maxB} B  ${(r.mode.minPct * 100).toFixed(3)}%..${(r.mode.maxPct * 100).toFixed(3)}%`);
    for (const row of r.inadmissible) console.log(`;;   EXCLUDED ${row.corpus}/${row.run} — ${row.codes.join(', ')}`);
    for (const row of r.notComputable) console.log(`;;   NOT COMPUTABLE ${row.corpus}/${row.run}`);
    for (const row of r.degraded) console.log(`;;   RAMP FALLBACK ${row.corpus}/${row.run} (elevated=${row.elevated})`);
    return r.falsePositives.length || r.misses.length ? 1 : 0;
  }
  const files = args.filter((a) => !a.startsWith('--'));
  if (!files.length) {
    console.error('usage: alloc_level_witness.cjs [--self-test] [--corpus] <dataset.json>...');
    return 2;
  }
  let bad = 0;
  for (const f of files) {
    const v = adjudicate(JSON.parse(fs.readFileSync(f, 'utf8')));
    console.log(format(v, path.basename(f)));
    if (!v.ok) bad++;
  }
  return bad ? 1 : 0;
}

if (require.main === module) process.exit(main(process.argv));

module.exports = {
  adjudicate, format, selfTest, scoreCorpus, admissibility, segmentsOf, median,
  BEFORE_ROUNDS, BEFORE_PRE_RAMP_MAX, AFTER_ROUND_MIN, LEVEL_STEP_BOUND, HIGH_MODE_FLOOR_B, CORPORA,
};
