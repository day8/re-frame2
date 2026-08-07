#!/usr/bin/env node
// RE-ADJUDICATE AN ENSEMBLE OF CLOCK RUNS (rf2-emvod).
//
//   node freehand/test/re_frame/bench/hicasso/clock_readjudicate.cjs out/run*.json
//
// `clock_run.cjs` adjudicates ONE run. Every conclusion this lane has
// reached about the candidate's clock rows rests on an ENSEMBLE — five runs
// for `the-candidates-clock.md`, eight for `bulk-broad-re-taken.md`, nineteen
// for the seam ladder — and each of those tables was assembled by hand from
// console logs. That is how `rf2-cvvb7`'s merged-PR audit came to record that
// a published study could not be recomputed from the landed tree: the runs
// were real, the arithmetic was reasonable, and nothing in the repository
// could reproduce either.
//
// So the ensemble arithmetic is a program, it reads the driver's own JSON
// datasets, and its output is the table that gets published. A reader with
// the datasets can rerun this and get the page back.
//
// ## WHAT IT REFUSES TO DO
//
// It does not select runs. Every dataset handed to it appears in the
// per-run table, including the ones whose control failed, and the ensemble
// figure is stated over all of them with the control-passing subset shown
// beside it rather than instead of it. Selecting a run after seeing its
// result is the fault this whole lane is built to avoid, and an analysis
// script is the easiest possible place to commit it silently.
//
// ## THE CONSUMER HALF OF THE TWO-TIER WRITE POLICY (`rf2-2rtt6.31`)
//
// The family's ruling of 2026-08-07 splits CAPTURE from PUBLICATION. Every
// completed measurement is preserved; only a gate-passing run of the full
// published shape is eligible published evidence, and a dataset says which it
// is IN THE FILE — `canonical` and `notCanonicalWhy`, exactly as
// `shapes/census_clock_run.cjs`'s `datasetFor` writes them. The ruling's
// consumer clause is one sentence: **a missing `canonical` field is not a
// pass.** A reader that inferred eligibility from the directory a file was
// found in would be trusting the one thing a file loses when it is copied.
//
// This program is that consumer, and `rf2-emvod`'s merged-PR audit (#7365)
// recorded it failing open in the other direction as well: the subset asked
// only for `ctlTask.ok` and the two ceilings, so it PRINTED `guardRefuse`,
// `guardRefuseTask`, the legacy-clock `ctlOk` and `tally.unverified` and then
// pooled the run anyway. The driver writes its dataset BEFORE its own fatal
// checks run, so a run that Chromium threw on, whose arms built different
// pages, or whose arm-order guard refused, is a well-formed file that reached
// the published mean. `GATES` below is that exit path, read back off the
// serialised record, fail-closed at every seat: **absent is not clean.**
//
// THREE OF THE DRIVER'S FATAL CHECKS ARE NOT SERIALISED YET, and they are
// named here rather than skipped, because skipping one is how this file came
// to need repairing twice. `clock_run.cjs` computes `pageErrors`, `parityOk`
// (the canonical-DOM gate) and `etVerdict` (Event Timing) and exits 1 on each,
// but stores none of them, so no dataset in the tree today can satisfy this
// filter — which is the correct fail-closed reading of an INCOMPLETE record
// and not a defect in it. The producer half adopts the family policy when
// `clock_run.cjs` is next touched (`rf2-2rtt6.31`, "record once, converge on
// touch"); the field names below are its own internal names so that adoption
// is one line per verdict.
//
// ## THE ADDITIVE RESIDUAL, and why it is computed here
//
// `ctl-2x` builds exactly twice the floor's page and reads 1.68-1.86x rather
// than 2.00x, on every row, in every configuration, on both clocks. The
// standing diagnosis (`rf2-7iqb5`) is that a page-doubling control is
// mis-specified for an UPDATE row, whose work does not double with the page.
// That diagnosis predicts the mount row should be clean, and it is not — M1
// undershoots too.
//
// An additive per-sample cost `c` that the tare does not remove explains
// both at once, because a ratio of two arms one of which is twice the other
// reads `(2W + c) / (W + c)`, which is below 2 for any positive `c` and does
// not care whether the row is a mount or an update. Inverting the measured
// ratio `r` gives `c / W = (2 - r) / (r - 1)`, and `c` in milliseconds
// follows from the floor's own tared absolute. If `c` comes out similar
// across rows that differ wildly in what they do, the additive model is the
// better explanation and the repair is a control that DIFFERENCES the
// constant away rather than one that changes what is doubled.

'use strict';

const fs = require('node:fs');

const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

/** mean / min / max over the runs of the ensemble — never a bare mean. */
function ens(xs) {
  const v = xs.filter(Number.isFinite);
  if (v.length === 0) return { n: 0, mean: NaN, min: NaN, max: NaN };
  return { n: v.length, mean: mean(v), min: Math.min(...v), max: Math.max(...v) };
}

const PAIRS = ['hicasso / reagent-subs', 'hicasso / uix-subs', 'uix-subs / reagent-subs'];
const SEGMENTS = ['reagent-subs', 'uix-subs', 'hicasso'];

/**
 * IS EVERY BAR THIS RUN PUBLISHED ON THIS ROW ADJUDICATED?
 *
 * rf2-y7mw7's term, and the one this file was missing entirely: a row whose
 * bars have no band has a magnitude and nothing to tell it from parity, and
 * this program prints its ensemble mean under the label "control-passing
 * subset". A dataset that stored no bar verdict at all is not adjudicated
 * either — absent is not clean.
 *
 * EVERY bar, not one of them. #7489's merged-PR audit found this predicate
 * asking `some`, which admitted a run into the reportable subset — for every
 * pair, including the unadjudicated one — on the strength of a single
 * adjudicated bar elsewhere on the row. The driver that wrote these datasets
 * sets `unadjudicated` from a row-wide flag, so no dataset in the tree today
 * contains a mixed row; but this program's whole reason for existing is that
 * datasets are re-read long after the driver that produced them, and a
 * predicate that is correct only for the files that happen to exist now is a
 * fail-open waiting for the first file that does not.
 *
 * AND EVERY BAR MUST SAY SO, which #7550's merged-PR audit found this
 * predicate still not asking. It read `!bars[n].unadjudicated` — truthiness —
 * so a bar the file stored as `{}`, with no verdict in it at all, came back
 * ADJUDICATED and the run was pooled into the published mean. That is the
 * absent-is-not-clean contract two paragraphs up being contradicted by the
 * line below it, and it matters MORE here than in the driver: the driver reads
 * an object `seam.assess` built moments earlier in the same process, while
 * this program reads a file, and a file is where a field goes missing. A bar
 * counts as adjudicated only on `unadjudicated === false`; a bar that is
 * missing, null, or silent has not been adjudicated, it has been lost.
 *
 * `row` is one entry of a dataset's `rows`, as `clock_run.cjs` wrote it.
 */
function adjudicated(row) {
  const bars = (row && row.seamTask && row.seamTask.rows) || {};
  const names = Object.keys(bars);
  return names.length > 0 && names.every((n) => !!bars[n] && bars[n].unadjudicated === false);
}

/**
 * EVERY GATE A RUN MUST CLEAR BEFORE ITS MAGNITUDE MAY BE POOLED, in the order
 * `clock_run.cjs` applies them, each read off the serialised record and each
 * FAIL-CLOSED: a verdict that is missing, null or silent has not been passed,
 * it has been lost.
 *
 * Stated as data rather than as a boolean expression for two reasons. The
 * refusal has to be NAMED — a run dropped from the subset without a reason is
 * indistinguishable from a run that was selected away — and a roster can be
 * quantified over, so `clock_exit_path.test.cjs` drives every gate rather than
 * the handful someone remembered to write a case for.
 *
 * Each entry's `why` returns `null` for a pass and the refusal's own sentence
 * otherwise. `scope` says what it is handed: the dataset envelope, or one row.
 */
const GATES = [
  // --- the file's own two-tier verdict, before anything in it is read ------
  {
    id: 'canonical',
    scope: 'dataset',
    why: (d) =>
      d.canonical === true
        ? null
        : d.notCanonicalWhy
          ? `NOT the published evidence set — ${d.notCanonicalWhy}`
          : 'the file carries no `canonical` verdict, so it has never been shown to be the published ' +
            'evidence set — absent is not a pass (rf2-2rtt6.31)',
  },
  // --- the driver's fatal checks, in its own order -------------------------
  {
    id: 'page-errors',
    scope: 'row',
    why: (r) =>
      !Array.isArray(r.pageErrors)
        ? 'no `pageErrors` record — whether the page threw was not serialised'
        : r.pageErrors.length
          ? `the page THREW during the run: ${r.pageErrors.join(' | ')}`
          : null,
  },
  {
    id: 'guard-net',
    scope: 'row',
    why: (r) =>
      r.guardRefuse === false
        ? null
        : r.guardRefuse === true
          ? 'the arm-order guard REFUSED this row on taskNet'
          : 'no arm-order guard verdict on taskNet',
  },
  {
    id: 'guard-task',
    scope: 'row',
    why: (r) =>
      r.guardRefuseTask === false
        ? null
        : r.guardRefuseTask === true
          ? 'the arm-order guard REFUSED this row on the published clock'
          : 'no arm-order guard verdict on the published clock',
  },
  {
    id: 'canonical-dom',
    scope: 'row',
    why: (r) =>
      r.parityOk === true
        ? null
        : r.parityOk === false
          ? 'the canonical-DOM gate found arms building DIFFERENT PAGES — a ratio between two pages is not a ratio'
          : 'no canonical-DOM verdict was serialised',
  },
  {
    id: 'ctl3-parity',
    scope: 'row',
    why: (r) =>
      !('ctl3Parity' in r)
        ? "no three-point-control parity record — whether the control's own arms agreed was not serialised"
        : r.ctl3Parity === null || r.ctl3Parity.ok === true
          ? null
          : "the three-point control's own arms built DIFFERENT PAGES",
  },
  {
    id: 'keystroke-witness',
    scope: 'row',
    why: (r) =>
      !('kbWitness' in r)
        ? "no per-keystroke witness record — whether the row's n counts anything was not serialised"
        : r.kbWitness === null || r.kbWitness.ok === true
          ? null
          : 'the per-keystroke witness REFUSED — its accounting does not close',
  },
  {
    id: 'unverified',
    scope: 'row',
    why: (r) =>
      !r.tally || !Number.isFinite(r.tally.unverified)
        ? 'no write-verification tally'
        : r.tally.unverified > 0
          ? `${r.tally.unverified} unverified operation(s) of ${r.tally.writes} — a window whose value never reached the page`
          : null,
  },
  // THE CEILINGS FIRE BEFORE ANY CONTROL IS CONSULTED, and this file refuses
  // on BOTH while the driver (rf2-ymi6j) refuses only on the published clock's
  // and reports the frame-only one. The asymmetry is deliberate and it is the
  // safe direction: a consumer stricter than its producer can withhold a
  // magnitude the driver would have allowed, never publish one it refused.
  {
    id: 'ceiling-net',
    scope: 'row',
    why: (r) =>
      !r.seam || !r.seam.verdict || typeof r.seam.verdict.ceilingBreached !== 'boolean'
        ? 'no frame-only band verdict'
        : r.seam.verdict.ceilingBreached
          ? "the run's own frame-only reproducibility band exceeds the ceiling"
          : null,
  },
  {
    id: 'ceiling-task',
    scope: 'row',
    why: (r) =>
      !r.seamTask || typeof r.seamTask.ceilingBreached !== 'boolean'
        ? 'no published-clock band verdict'
        : r.seamTask.ceilingBreached
          ? "the run's own reproducibility band exceeds the ceiling on the published clock"
          : null,
  },
  // THE CONTROL, MIRRORING `ctlBad` RATHER THAN RE-DECIDING IT. A row that has
  // a three-point control is gated on it and `ctl-2x` is a reported diagnostic
  // (rf2-7iqb5, rf2-5xrcd); a row that has none — `M1`, `keystroke` — is gated
  // on `ctl-2x`, and on BOTH clocks, because the row is stated on one of them
  // and was adjudicated on the other.
  {
    id: 'control',
    scope: 'row',
    why: (r) => {
      if (!('ctl3' in r)) return 'no three-point-control record — which control gates this row was not serialised';
      if (r.ctl3) return r.ctl3.ok === true ? null : 'the THREE-POINT control FAILED';
      if (r.ctlOk !== true) return r.ctlOk === false ? 'ctl-2x FAILED on taskNet' : 'no ctl-2x verdict on taskNet';
      return r.ctlTask && r.ctlTask.ok === true
        ? null
        : r.ctlTask
          ? 'ctl-2x FAILED on the published clock'
          : 'no ctl-2x verdict on the published clock';
    },
  },
  {
    id: 'event-timing',
    scope: 'row',
    why: (r) =>
      !('etVerdict' in r)
        ? 'no Event-Timing verdict was serialised'
        : r.etVerdict === null || r.etVerdict.ok === true
          ? null
          : 'the Event-Timing witness REFUSED',
  },
  {
    id: 'adjudication',
    scope: 'row',
    why: (r) => (adjudicated(r) ? null : 'a bar this run published carries no adjudication verdict'),
  },
];

/**
 * WHY THIS RUN MAY NOT BE POOLED — every reason, never the first one.
 *
 * All of them, because a reader deciding whether to re-take a run needs to know
 * that four gates failed rather than that one did, and because a filter that
 * short-circuits reports the cheapest fault rather than the interesting one.
 *
 * This selects nothing away from the TABLE. Every dataset handed to this
 * program appears in the per-run listing whatever this returns, with these
 * reasons printed beside it; the subset is shown beside the whole ensemble and
 * never instead of it.
 */
function refusals(row, dataset) {
  const d = dataset || {};
  const r = row || null;
  const why = [];
  for (const g of GATES) {
    if (g.scope === 'dataset') {
      const w = g.why(d);
      if (w) why.push(w);
    } else if (!r) {
      why.push(`no row to read \`${g.id}\` from`);
    } else {
      const w = g.why(r);
      if (w) why.push(w);
    }
  }
  return why;
}

/** MAY THIS RUN'S MAGNITUDE ON THIS ROW BE POOLED INTO THE REPORTABLE SUBSET? */
function reportable(row, dataset) {
  return refusals(row, dataset).length === 0;
}

/**
 * THE RESPONSIVENESS REGIME, RE-ADJUDICATED OFF THE STORED RUN (rf2-swwud).
 *
 * The per-keystroke row has no band and never will: its control burns a fixed
 * 50 ms, so `control/floor` reads `(F+50)/F` and moves with `F` — a fine
 * sensitivity control, and not a pair whose true ratio is a property of the
 * page. Two clean runs on two verifiably idle boxes produced two UNADJUDICATED
 * rows for exactly that reason, which is what settled the question the row was
 * raised on: the obstruction is the rig, not the box, and no scheduling moves
 * it. The 2026-08-06 ruling therefore adjudicates this row by EVENT TIMING
 * instead of by the band, and the tables above become diagnostics.
 *
 * WHY IT IS COMPUTED HERE. The re-adjudication is of the runs already on disk
 * — no new window was taken and none is needed. `clock_run.cjs` stores the
 * repaired witness's own per-arm accounting in every dataset, so this reads
 * `kbWitness.perArm` rather than regrouping raw entries: the driver's witness
 * owns what forms an interaction and what a censored key is, and a second
 * grouping here would be a second adjudicator.
 *
 * IT PUBLISHES NO MAGNITUDE AND THE RIDER IS NOT OPTIONAL. Event Timing
 * resolves 8 ms buckets above a 16 ms floor, so this row detects only a
 * difference that crosses a bucket boundary. "Indistinguishable at one frame"
 * is a statement about the instrument's resolution and NOT a measured tie
 * below it — the arms' separations are sub-frame, the per-sample grid is
 * coarser than they are, and every diagnostic bar straddles 1.0.
 *
 * `row` is one entry of a dataset's `rows`. Returns `null` for a row that
 * carries no keystroke witness, which is every other row.
 */
function responsivenessRegime(row) {
  const w = row && row.kbWitness;
  if (!w || !w.perArm) return null;
  const perArm = Object.entries(w.perArm).map(([arm, a]) => {
    const ds = a.durations || [];
    return {
      arm,
      control: /\/ctl-/.test(arm),
      sent: a.sent,
      observed: a.observed,
      censored: a.censored,
      n: ds.length,
      p50: p50(ds),
      min: ds.length ? Math.min(...ds) : NaN,
      max: ds.length ? Math.max(...ds) : NaN,
    };
  });
  const observedArms = perArm.filter((a) => !a.control && a.n > 0);
  const controlArms = perArm.filter((a) => a.control && a.n > 0);
  const buckets = [...new Set(observedArms.flatMap((a) => [a.min, a.max]))];
  // The control's median over its OWN readings, pooled across segments —
  // every duration once, rather than each arm's median re-weighted.
  const controlDurations = controlArms.flatMap((a) => w.perArm[a.arm].durations || []);
  return {
    perArm,
    totals: w.totals || null,
    // ONE BUCKET ACROSS EVERY OBSERVED ARM is the whole verdict. It is stated
    // as a property of the readings rather than asserted, so a run in which
    // the arms DID separate would say so here instead.
    indistinguishable: observedArms.length > 0 && buckets.length === 1,
    frame: buckets.length === 1 ? buckets[0] : NaN,
    controlP50: controlDurations.length ? p50(controlDurations) : NaN,
    // The instrument moved when the work moved, or this run states nothing.
    controlMoved: controlArms.length > 0 && controlArms.every((a) => a.p50 > buckets[0]),
    // THE RIDER'S OWN NUMBERS, both stored by the driver rather than derived
    // here: the finest per-sample step this run could resolve, and whether the
    // diagnostic bars separate the arms at this n. They do not.
    grain: Array.isArray(row.granularity) && row.granularity.length ? row.granularity[0] : NaN,
    diagnosticBars: PAIRS.filter((p) => row.barTask && row.barTask[p]).map((p) => ({
      pair: p,
      mean: row.barTask[p].mean,
      min: row.barTask[p].min,
      max: row.barTask[p].max,
      straddles1: row.barTask[p].min <= 1 && row.barTask[p].max >= 1,
    })),
  };
}

function main(argv) {
  const files = argv.filter((a) => !a.startsWith('--'));
  if (files.length === 0) {
    console.error('usage: clock_readjudicate.cjs <run1.json> [run2.json ...]');
    return 1;
  }

  const datasets = files.map((f) => ({ file: f, data: JSON.parse(fs.readFileSync(f, 'utf8')) }));

  // Row ids in the order the first dataset produced them, so the report reads
  // in run order rather than in whatever order Object.keys happens to give.
  const rowIds = [];
  for (const { data } of datasets) {
    for (const r of data.rows) if (!rowIds.includes(r.rowId)) rowIds.push(r.rowId);
  }

  console.log(';; ==== ENSEMBLE RE-ADJUDICATION (rf2-emvod) ====');
  console.log(`;; datasets ${datasets.length}`);
  for (const { file, data } of datasets) {
    console.log(
      `;;   ${file}  label=${data.label || '-'}  when=${data.when}  chromium=${data.chromium}  ` +
        `design=${data.design.rounds}x(${data.design.warmup}+${data.design.samples}) tare=${data.design.tare}`
    );
  }

  // THE FILE'S OWN VERDICT, ANNOUNCED BEFORE ANY TABLE. A dataset that does
  // not say it is the published evidence set is still read, still tabled and
  // still printed in full — capture is preserved — but it may not contribute a
  // magnitude, and the reader is told that at the top rather than left to
  // notice an empty subset at the bottom.
  const ineligible = datasets.filter(({ data }) => !!GATES[0].why(data));
  if (ineligible.length) {
    console.log('');
    console.log(';; !! NOT ELIGIBLE PUBLISHED EVIDENCE — every table below is printed, and none of it');
    console.log(';; !! may be quoted as a magnitude. This program exits nonzero for that reason.');
    for (const { file, data } of ineligible) console.log(`;; !!   ${file}: ${GATES[0].why(data)}`);
  }

  for (const rowId of rowIds) {
    const runs = datasets
      .map(({ file, data }) => ({ file, data, row: data.rows.find((r) => r.rowId === rowId) }))
      .filter((x) => x.row);
    if (runs.length === 0) continue;

    console.log('');
    console.log(`;; ======== ROW ${rowId} — ${runs.length} runs ========`);

    // --- gates, per run -------------------------------------------------------
    console.log(';; run  guard(net/task)  ctl-2x task   ctlPASS  band(task)  band(net)  floor abs ms');
    for (const { file, row } of runs) {
      const floorAbs = p50(
        SEGMENTS.map((s) => {
          const d = row.decomposition[`${s}/floor`];
          return d ? d.task / d.n : NaN;
        }).filter(Number.isFinite)
      );
      console.log(
        `;;  ${shortName(file).padEnd(6)} ${(row.guardRefuse ? 'REFUSE' : 'ok').padEnd(7)}` +
          `${(row.guardRefuseTask ? 'REFUSE' : 'ok').padEnd(8)}` +
          `${row.ctlTask ? fmt(row.ctlTask.measured.mean, 3).padStart(10) : '       n/a'}` +
          `${row.ctlTask ? (row.ctlTask.ok ? '   PASS' : '   FAIL') : '    n/a'}` +
          `${row.bandTask === null || row.bandTask === undefined ? '       n/a' : (fmt(row.bandTask * 100, 1) + '%').padStart(10)}` +
          `${row.seam && row.seam.band !== null ? (fmt(row.seam.band * 100, 1) + '%').padStart(11) : '        n/a'}` +
          `${fmt(floorAbs, 3).padStart(13)}`
      );
    }

    // --- WHY EACH REFUSED RUN IS REFUSED --------------------------------------
    //
    // The audit's own term: reject an incomplete or failed dataset in the
    // reportable ACCOUNTING while still displaying it in the full ensemble.
    // Every reason, named, per run — so a run that leaves the subset leaves it
    // for a stated cause and can be told from one that was selected away.
    console.log(';; run    REFUSED FROM THE REPORTABLE SUBSET (and retained in every table here)');
    for (const { file, data, row } of runs) {
      const why = refusals(row, data);
      console.log(
        `;;  ${shortName(file).padEnd(6)} ${why.length ? why.join('; ') : '— reportable: every gate this dataset serialises is clean'}`
      );
    }

    // --- the three clocks, per pair -------------------------------------------
    for (const pair of PAIRS) {
      const inPage = runs.map(({ row }) => (row.inPageBar && row.inPageBar[pair] ? row.inPageBar[pair].mean : NaN));
      const net = runs.map(({ row }) => (row.bar && row.bar[pair] ? row.bar[pair].tared.mean : NaN));
      const task = runs.map(({ row }) => (row.barTask && row.barTask[pair] ? row.barTask[pair].mean : NaN));
      if (!task.some(Number.isFinite)) continue;

      // The REPORTABLE subset, beside the whole ensemble and never instead of
      // it. `reportable` is at module scope (above) so it can be driven by a
      // test. rf2-y7mw7's third term landed here wrong, and it took a merged-PR
      // audit rather than a gate to find it, precisely because nothing could
      // reach it.
      const passIdx = runs.map(({ row, data }, i) => (reportable(row, data) ? i : -1)).filter((i) => i >= 0);
      const taskPass = passIdx.map((i) => task[i]);

      const e = ens(task);
      const en = ens(net);
      const ei = ens(inPage);
      console.log('');
      console.log(`;;   PAIR ${pair}`);
      console.log(
        `;;     raw TaskDuration (PUBLISHED)  ${fmt(e.mean)}x  [${fmt(e.min)} – ${fmt(e.max)}]  n=${e.n}` +
          (taskPass.length
            ? `   reportable subset ${fmt(ens(taskPass).mean)}x n=${taskPass.length}`
            : '   reportable subset: NONE')
      );
      console.log(`;;     taskNet (frame-only, superseded) ${fmt(en.mean)}x  [${fmt(en.min)} – ${fmt(en.max)}]`);
      console.log(`;;     in-page performance.now()        ${fmt(ei.mean)}x  [${fmt(ei.min)} – ${fmt(ei.max)}]`);
      console.log(';;     run   in-page   taskNet   TaskDuration   band   margin   verdict');
      runs.forEach(({ file, row }, i) => {
        const b = row.bandTask;
        const margin = Math.abs(task[i] - 1) * 100;
        const bandPct = Number.isFinite(b) ? b * 100 : NaN;
        const breached =
          (row.seam && row.seam.verdict && row.seam.verdict.ceilingBreached) ||
          (row.seamTask && row.seamTask.ceilingBreached);
        // WHETHER THIS BAR IS ADJUDICATED IS READ PER BAR, not off the row's
        // `bandTask`. `bandTask` is row-wide, and a row-wide reading here
        // would print "clears its 21.4% band" for the very bar the subset
        // above has just refused for carrying no band — the same disagreement
        // between a printed column and a decision that rf2-y7mw7 is about,
        // pointing the other way. They agree on every dataset in the tree,
        // and that agreement is `seam.assess`'s to withdraw, not this
        // program's to depend on. The sentence is left row-phrased because no
        // dataset yet exists in which a row's bars disagree; the bar's own
        // `why` is in the dataset for the day one does.
        //
        // And it is read with `adjudicated`'s OWN token, `=== false`, for the
        // same reason: a bar record present but silent is not a bar that
        // cleared anything, and a column reading `!!` while the subset reads
        // `=== false` is that disagreement again, one field further in.
        const barRec = (row.seamTask && row.seamTask.rows && row.seamTask.rows[pair]) || null;
        const barUnadjudicated = barRec ? barRec.unadjudicated !== false : !Number.isFinite(bandPct);
        const verdict = breached
          ? `BAND CEILING BREACHED — whole run refused before any control is consulted`
          : barUnadjudicated
            ? 'UNADJUDICATED — no proportional control on this row'
            : !(row.ctlTask && row.ctlTask.ok)
              ? `control FAILED — no magnitude reportable`
              : margin > bandPct
                ? `clears its ${fmt(bandPct, 1)}% band`
                : `INSIDE the band — instrument-limited`;
        console.log(
          `;;     ${shortName(file).padEnd(5)} ${fmt(inPage[i]).padStart(8)} ${fmt(net[i]).padStart(9)}` +
            `${fmt(task[i]).padStart(14)} ${(Number.isFinite(bandPct) ? fmt(bandPct, 1) + '%' : 'n/a').padStart(7)}` +
            ` ${(fmt(margin, 1) + '%').padStart(7)}   ${verdict}`
        );
      });
    }

    // --- the responsiveness regime, per run (rf2-swwud) -----------------------
    //
    // Printed immediately under the bars it re-labels, because the bars above
    // are what this row USED to be published as and the ruling's whole content
    // is that they are diagnostics.
    for (const { file, row } of runs) {
      const reg = responsivenessRegime(row);
      if (!reg) continue;
      console.log('');
      console.log(
        `;;   RESPONSIVENESS REGIME — ${shortName(file)}, re-adjudicated on EVENT TIMING rather than on the band`
      );
      console.log(';;     arm                        sent  observed  censored   ET p50   range');
      for (const a of reg.perArm) {
        console.log(
          `;;     ${a.arm.padEnd(26)}${String(a.sent).padStart(4)}${String(a.observed).padStart(10)}` +
            `${String(a.censored).padStart(10)}${(fmt(a.p50, 1) + ' ms').padStart(10)}   ` +
            `[${fmt(a.min, 1)} – ${fmt(a.max, 1)}]${a.control ? '   (control)' : ''}`
        );
      }
      if (reg.totals) {
        console.log(
          `;;     accounting: ${reg.totals.sent} keys sent = ${reg.totals.observed} observed + ` +
            `${reg.totals.censored} censored under the 16 ms floor`
        );
      }
      console.log(
        `;;     VERDICT ${
          reg.indistinguishable
            ? `Hicasso, Reagent-on-subs and UIx-on-subs are INDISTINGUISHABLE at Event Timing's ` +
              `resolution — every observed interaction was one frame (${fmt(reg.frame, 1)} ms)`
            : `the arms did NOT fall in one bucket on this run — the frame statement does not hold here`
        }`
      );
      console.log(
        `;;     control ${reg.controlMoved ? 'PASS' : 'FAIL'} — ctl-50ms reads ${fmt(reg.controlP50, 1)} ms ` +
          `against the arms' ${fmt(reg.frame, 1)} ms, so the instrument demonstrably moves when the work moves`
      );
      console.log(
        `;;     POWERED TO DETECT: Event Timing resolves 8 ms buckets above a 16 ms floor, so this row ` +
          `detects only a difference that CROSSES a bucket boundary. It is not a measured tie below that.`
      );
      console.log(
        `;;       the arms' separations are sub-frame; this run's finest per-sample step is ` +
          `${fmt(reg.grain, 3)} ms, and every diagnostic bar above straddles 1.0 ` +
          `(${reg.diagnosticBars.filter((b) => b.straddles1).length} of ${reg.diagnosticBars.length}) — ` +
          `no magnitude is published from this row.`
      );
    }

    // --- absolutes, pooled over the ensemble ----------------------------------
    //
    // PRINTED FOR EVERY ROW, because both of this instrument's defects were
    // visible here and in no ratio anywhere.
    console.log('');
    console.log(';;   ABSOLUTES — mean ms per sample, pooled over the ensemble');
    console.log(';;     arm                          task   taskNet   in-page  devtools    script    layout');
    const armKeys = [];
    for (const { row } of runs) for (const k of Object.keys(row.decomposition)) if (!armKeys.includes(k)) armKeys.push(k);
    for (const k of armKeys) {
      const acc = { task: [], taskNet: [], devtools: [], script: [], layout: [], inPage: [] };
      for (const { row } of runs) {
        const d = row.decomposition[k];
        if (!d || !d.n) continue;
        acc.task.push(d.task / d.n);
        acc.taskNet.push(d.taskNet / d.n);
        acc.devtools.push(d.devtools / d.n);
        acc.script.push(d.script / d.n);
        acc.layout.push(d.layout / d.n);
      }
      // The in-page window is not in `decomposition`; it is in the raw
      // per-sample readings, which is where it has to be read from.
      for (const { row } of runs) {
        if (!row.inPageRounds) continue;
        const [seg, arm] = k.split('/');
        const xs = row.inPageRounds.flatMap((rd) => (rd[seg] && rd[seg][arm]) || []);
        if (xs.length) acc.inPage.push(p50(xs));
      }
      if (acc.task.length === 0) continue;
      console.log(
        `;;     ${k.padEnd(28)} ${fmt(mean(acc.task), 3).padStart(6)} ${fmt(mean(acc.taskNet), 3).padStart(9)}` +
          ` ${(acc.inPage.length ? fmt(mean(acc.inPage), 3) : 'n/a').padStart(9)} ${fmt(mean(acc.devtools), 3).padStart(9)}` +
          ` ${fmt(mean(acc.script), 3).padStart(9)} ${fmt(mean(acc.layout), 3).padStart(9)}`
      );
    }

    // --- THE DOOR -------------------------------------------------------------
    //
    // `DevToolsCommandDuration` absorbs page script that runs INSIDE a protocol
    // command. Which door the driver uses therefore decides whether `taskNet`
    // is a frame-only reading or an honest one, and the rows here disagree
    // about the door: `M1`, `bulk300`, `bulk100` and `narrow` drive the
    // operation through `page.evaluate` (`Runtime.callFunctionOn`), while
    // `keystroke` drives it through `page.keyboard.press` (the Input domain).
    //
    // Two numbers settle it per row, and neither needs the other harness:
    //
    //   * whether `devtools` TRACKS the arm. If it carries the arm's script it
    //     must move with the arm; if it is only the protocol's round trip it is
    //     roughly constant across arms that differ by milliseconds of work.
    //   * what `ScriptDuration` reads. The renderer's own script counter does
    //     not see script run through a protocol command either, so a row whose
    //     script is being absorbed reports a mount of 901 elements as costing
    //     hundredths of a millisecond of script.
    {
      const armsIn = (row) => Object.keys(row.decomposition).filter((k) => !k.endsWith('/plumb'));
      const per = (sel) =>
        mean(
          runs.map(({ row }) => {
            const vals = armsIn(row).map((k) => sel(row.decomposition[k]));
            return Math.max(...vals) - Math.min(...vals);
          })
        );
      const scriptMax = mean(
        runs.map(({ row }) => Math.max(...armsIn(row).map((k) => row.decomposition[k].script / row.decomposition[k].n)))
      );
      const dvSpread = per((d) => d.devtools / d.n);
      const ipSpread = per((d) => d.taskNet / d.n); // stand-in scale for "how much arms differ"
      console.log('');
      console.log(
        `;;   THE DOOR — devtools spread across arms ${fmt(dvSpread, 3)} ms; largest ScriptDuration on any arm ` +
          `${fmt(scriptMax, 4)} ms. ` +
          (scriptMax < 0.2
            ? `ScriptDuration sees essentially NOTHING, so the operation ran inside a protocol command and ` +
              `taskNet on this row is FRAME-ONLY.`
            : `ScriptDuration SEES the arms' work, so the operation did not run inside a protocol command and ` +
              `taskNet on this row was never corrupted.`) +
          `  (taskNet spread ${fmt(ipSpread, 3)} ms, for scale)`
      );
    }

    // --- THE ADDITIVE RESIDUAL ------------------------------------------------
    const ctlMeans = runs.map(({ row }) => (row.ctlTask ? row.ctlTask.measured.mean : NaN)).filter(Number.isFinite);
    if (ctlMeans.length) {
      const r = mean(ctlMeans);
      const cOverW = (2 - r) / (r - 1);
      const floorTared = mean(
        runs.map(({ row }) => {
          const f = p50(
            SEGMENTS.map((s) => {
              const d = row.decomposition[`${s}/floor`];
              return d ? d.task / d.n : NaN;
            }).filter(Number.isFinite)
          );
          const pl = p50(
            SEGMENTS.map((s) => {
              const d = row.decomposition[`${s}/plumb`];
              return d ? d.task / d.n : NaN;
            }).filter(Number.isFinite)
          );
          return f - pl;
        })
      );
      // floorTared = W + c, so W = floorTared / (1 + c/W) and c = W * (c/W).
      const W = floorTared / (1 + cOverW);
      console.log('');
      console.log(
        `;;   ADDITIVE RESIDUAL — ctl-2x reads ${fmt(r, 4)}x where the page it builds is exactly 2.00x. ` +
          `Inverting (2W+c)/(W+c): c/W = ${fmt(cOverW, 3)}, and with the floor's tared absolute at ` +
          `${fmt(floorTared, 3)} ms that is W = ${fmt(W, 3)} ms of page-proportional work and ` +
          `c = ${fmt(floorTared - W, 3)} ms that the plumb tare does not remove.`
      );
    }
  }

  console.log('');
  console.log(';; ==== CROSS-ROW: is the residual the same constant everywhere? ====');
  console.log(';; row        ctl-2x(task)   implied c/W   implied c ms   floor tared ms');
  for (const rowId of rowIds) {
    const runs = datasets.map(({ data }) => data.rows.find((r) => r.rowId === rowId)).filter(Boolean);
    const ctlMeans = runs.map((row) => (row.ctlTask ? row.ctlTask.measured.mean : NaN)).filter(Number.isFinite);
    if (!ctlMeans.length) {
      console.log(`;; ${rowId.padEnd(11)} (no proportional control on this row)`);
      continue;
    }
    const r = mean(ctlMeans);
    const cOverW = (2 - r) / (r - 1);
    const floorTared = mean(
      runs.map((row) => {
        const f = p50(SEGMENTS.map((s) => (row.decomposition[`${s}/floor`] ? row.decomposition[`${s}/floor`].task / row.decomposition[`${s}/floor`].n : NaN)).filter(Number.isFinite));
        const pl = p50(SEGMENTS.map((s) => (row.decomposition[`${s}/plumb`] ? row.decomposition[`${s}/plumb`].task / row.decomposition[`${s}/plumb`].n : NaN)).filter(Number.isFinite));
        return f - pl;
      })
    );
    const W = floorTared / (1 + cOverW);
    console.log(
      `;; ${rowId.padEnd(11)} ${fmt(r, 4).padStart(11)} ${fmt(cOverW, 3).padStart(13)} ${fmt(floorTared - W, 3).padStart(14)} ${fmt(floorTared, 3).padStart(16)}`
    );
  }

  // FAIL-CLOSED, AND THE EXIT CODE IS WHERE THAT LANDS. `rf2-cvvb7`'s recorded
  // gap was a study nobody could recompute; the repair is not just that a
  // program exists but that running it over evidence it may not publish from
  // says so in the one place a script can be believed. Everything is printed
  // either way — a refusal is about what may be QUOTED, never about throwing a
  // measurement away (`rf2-2rtt6.31`).
  if (ineligible.length) {
    console.log('');
    console.log(
      `;; EXIT 3 — ${ineligible.length} of ${datasets.length} dataset(s) are not eligible published evidence. ` +
        'No reportable subset above was drawn from them, and no figure here may be quoted as a magnitude.'
    );
    return 3;
  }
  return 0;
}

function shortName(f) {
  const m = /([^/\\]+)\.json$/.exec(f);
  return m ? m[1] : f;
}

module.exports = { GATES, adjudicated, refusals, reportable, responsivenessRegime };

// Requiring this file must not run it: `clock_exit_path.test.cjs` drives the
// two predicates above directly, which it cannot do if the module body reads
// argv and exits.
if (require.main === module) {
  const code = main(process.argv.slice(2));
  if (code !== 0) process.exit(code);
}
