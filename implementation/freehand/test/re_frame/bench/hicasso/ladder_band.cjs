#!/usr/bin/env node
'use strict';
// THE LADDER, RECOMPUTED FROM ITS OWN DATASETS — both clocks (rf2-ymi6j).
//
//   node .../ladder_band.cjs out/ladder-ymi6j/*.json        recompute and print
//   node .../ladder_band.cjs --emit data.json out/.../*.json   ... and write the
//                                                              compact dataset
//   node .../ladder_band.cjs --from data.json                recompute FROM it
//
// ## Why this file exists
//
// `rf2-cvvb7`'s nineteen-run load ladder calibrated the band, the 25% ceiling
// and the multiplicativity finding, and none of it could be restated: the
// driver of the day wrote only the `taskNet` per-sample readings, the term
// `roundsTask` did not appear in it, and the datasets went to a gitignored
// `out/` and were not kept. The merged-PR audit on `rf2-ymi6j` asked for the
// raw fields **and a durable recomputation path**. The driver now writes all
// three windows; this is the path.
//
// It computes nothing of its own. Every figure below comes out of `seam.cjs`'s
// exported adjudicators — the same functions the driver runs in-line — so a
// figure printed here and a figure printed by a run are the same figure by
// construction rather than by agreement.
//
// ## The compact dataset, and what it drops
//
// A run's raw dataset is ~220 KB, almost all of it per-sample readings;
// nineteen of them are ~4 MB, which is not a thing to put in a repository. So
// `--emit` writes the REDUCED quantities every statistic on this page is a
// function of: the eighteen per-block tared `floor` and `ctl-2x` cells on each
// clock, the pooled floor medians per segment, and the bar row's per-round
// legs. From that file the band, the seam, the orthogonal decomposition, the
// bootstrap and every correlation recompute to the FOUR DECIMAL PLACES the
// file stores. `--from` against the full-dataset pass is the check, and it
// reproduces every band, every `ctl-2x / floor` and every bar row digit for
// digit; a floor absolute and the odd seam move in the fourth significant
// figure, which is the rounding and is said rather than glossed as "exactly".
//
// What it does NOT carry is the per-sample distribution inside a block. A
// question about within-block shape needs the raw datasets, which are kept
// beside the run and named in the page's provenance.

const fs = require('node:fs');
const path = require('node:path');
const seamlib = require('./seam.cjs');

const SEGMENTS = ['reagent-subs', 'uix-subs', 'hicasso'];
const FLOOR = 'floor';
const PLUMB = 'plumb';
const FIXED = 'ctl-2x';
const BAR = ['hicasso', 'reagent-subs']; // numerator segment, denominator segment

const p50 = (xs) => {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
};
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const r4 = (x) => (Number.isFinite(x) ? Math.round(x * 10000) / 10000 : null);
const pc = (x) => (Number.isFinite(x) ? (x * 100).toFixed(1) + '%' : 'n/a');

function corr(xs, ys) {
  const n = xs.length;
  const mx = mean(xs);
  const my = mean(ys);
  let sxy = 0;
  let sxx = 0;
  let syy = 0;
  for (let i = 0; i < n; i++) {
    sxy += (xs[i] - mx) * (ys[i] - my);
    sxx += (xs[i] - mx) ** 2;
    syy += (ys[i] - my) ** 2;
  }
  return sxy / Math.sqrt(sxx * syy);
}

/** One arm's cell in one block, tared by that block's own `plumb`. */
const cell = (rounds, seg, arm, r) => p50(rounds[r][seg][arm]) - p50(rounds[r][seg][PLUMB]);

/**
 * Everything one clock of one run contributes, reduced to the quantities the
 * ladder's statistics are functions of.
 */
function reduceClock(rounds) {
  const R = rounds.length;
  const floorBlocks = rounds.map((r) => SEGMENTS.map((s) => r[s][FLOOR]));
  const floorCells = [];
  const fixedCells = [];
  for (let r = 0; r < R; r++) {
    floorCells.push(SEGMENTS.map((s) => cell(rounds, s, FLOOR, r)));
    fixedCells.push(SEGMENTS.map((s) => cell(rounds, s, FIXED, r)));
  }
  const ratioToFloor = (seg, arm) =>
    rounds.map((_, r) => cell(rounds, seg, arm, r) / cell(rounds, seg, FLOOR, r));
  const barLegs = {
    num: ratioToFloor(BAR[0], BAR[0]),
    den: ratioToFloor(BAR[1], BAR[1]),
  };
  return {
    seamSpread: seamlib.seam(floorBlocks).spread,
    floorBySeg: SEGMENTS.map((s) => p50(rounds.flatMap((r) => r[s][FLOOR]))),
    floorPooled: p50(rounds.flatMap((r) => SEGMENTS.flatMap((s) => r[s][FLOOR]))),
    plumbPooled: p50(rounds.flatMap((r) => SEGMENTS.flatMap((s) => r[s][PLUMB]))),
    floorCells,
    fixedCells,
    barLegs,
    // The floor's per-block dispersion, which is the mechanism candidate for a
    // band that differs between two clocks measuring the same samples.
    floorSampleCv: SEGMENTS.flatMap((s) =>
      rounds.map((r) => {
        const xs = r[s][FLOOR];
        const m = mean(xs);
        return Math.sqrt(mean(xs.map((x) => (x - m) ** 2))) / m;
      })
    ),
  };
}

/**
 * The statistics, from a reduced clock. `seam.cjs` owns every one of them —
 * `effects` and `band` are called here exactly as `assess` calls them, and the
 * seam spread is `seamlib.seam`'s, taken while the raw blocks were still in
 * hand. `assess` itself is not used because its `seamNull` needs the
 * per-sample arrays, which are the one thing the compact dataset drops; the
 * null is a statement about the seam and is printed by the run, not here.
 */
function statsOf(red) {
  const eff = seamlib.effects(red.floorCells);
  const blocks = [];
  for (let r = 0; r < red.floorCells.length; r++) {
    for (let j = 0; j < red.floorCells[r].length; j++) {
      blocks.push({ fixed: red.fixedCells[r][j], floor: red.floorCells[r][j] });
    }
  }
  const b = seamlib.band(blocks);
  const bar = red.barLegs.num.map((x, i) => x / red.barLegs.den[i]);
  return {
    band: b.band,
    bandP50: b.p50,
    bandP10: b.p10,
    bandP90: b.p90,
    ratios: blocks.map((p) => p.fixed / p.floor),
    seam: red.seamSpread,
    effects: eff,
    floorPooled: red.floorPooled,
    floorTared: mean(red.floorCells.flat()),
    plumbPooled: red.plumbPooled,
    ctl2xMean: mean(blocks.map((p) => p.fixed / p.floor)),
    bar: mean(bar),
    barMin: Math.min(...bar),
    barMax: Math.max(...bar),
    floorSampleCv: red.floorSampleCv ? mean(red.floorSampleCv) : null,
    // THE ADDITIVE CONSTANT THE TARE DOES NOT REMOVE, per block, inverted from
    // the doubling control exactly as `rf2-emvod` inverted it:
    // `ctl-2x/floor = (2W + c)/(W + c)` gives `c = W·(2 − ratio)`. Reported
    // because the multiplicativity argument turns on whether what fails to
    // cancel is a constant of the harness or a perturbation of the box.
    cImplied: blocks.map((p) => p.floor * (2 - p.fixed / p.floor)),
  };
}

function readRun(file) {
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const row = j.rows.find((r) => r.rowId === 'bulk300') || j.rows[0];
  // `seamSpread` is computed here, while the raw per-sample arrays are still in
  // hand, and carried in the compact file — those arrays are exactly what the
  // compact file drops.
  const net = reduceClock(row.rounds);
  const task = reduceClock(row.roundsTask);
  return {
    label: j.label,
    load: j.load,
    when: j.when,
    chromium: j.chromium,
    rowId: row.rowId,
    guardRefuse: row.guardRefuse,
    guardRefuseTask: row.guardRefuseTask,
    net,
    task,
  };
}

/** Strip the raw per-sample arrays; keep everything a statistic needs. */
function compact(run) {
  const c = (k) => ({
    floorBySeg: run[k].floorBySeg.map(r4),
    floorPooled: r4(run[k].floorPooled),
    plumbPooled: r4(run[k].plumbPooled),
    seamSpread: r4(run[k].seamSpread),
    floorCells: run[k].floorCells.map((r) => r.map(r4)),
    fixedCells: run[k].fixedCells.map((r) => r.map(r4)),
    barLegs: { num: run[k].barLegs.num.map(r4), den: run[k].barLegs.den.map(r4) },
    floorSampleCv: r4(mean(run[k].floorSampleCv)),
  });
  return {
    label: run.label, load: run.load, when: run.when, chromium: run.chromium, rowId: run.rowId,
    guardRefuse: run.guardRefuse, guardRefuseTask: run.guardRefuseTask,
    net: c('net'), task: c('task'),
  };
}

function inflate(cr) {
  const c = (k) => ({
    floorBySeg: cr[k].floorBySeg,
    floorPooled: cr[k].floorPooled,
    plumbPooled: cr[k].plumbPooled,
    seamSpread: cr[k].seamSpread,
    floorCells: cr[k].floorCells,
    fixedCells: cr[k].fixedCells,
    barLegs: cr[k].barLegs,
    floorSampleCv: [cr[k].floorSampleCv],
  });
  return { ...cr, net: c('net'), task: c('task') };
}

// ---------------------------------------------------------------------------
// The bootstrap — the run-level sampling distribution of the band
// ---------------------------------------------------------------------------
//
// The band is a p10–p90 half-width over EIGHTEEN blocks, and eighteen is not
// many. Whether a 25% ceiling is a tripwire or a coin toss is a question about
// that statistic's own tail, and nineteen calibration runs cannot answer it —
// nineteen draws bound a 1-in-20 event at exactly one observation. Resampling
// the blocks does: draw eighteen blocks with replacement from the pooled set,
// recompute the band, and read off how often it clears the ceiling.
function bootstrapBand(ratios, draws, seed) {
  let s = seed >>> 0 || 0x9e3779b9;
  const next = () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5; s >>>= 0;
    return s / 0x100000000;
  };
  const out = [];
  for (let it = 0; it < draws; it++) {
    const pick = [];
    for (let i = 0; i < 18; i++) pick.push(ratios[Math.floor(next() * ratios.length)]);
    const v = [...pick].sort((a, b) => a - b);
    const q = (p) => v[Math.min(v.length - 1, Math.max(0, Math.floor(p * v.length)))];
    out.push((q(0.9) - q(0.1)) / (2 * p50(pick)));
  }
  out.sort((a, b) => a - b);
  return out;
}
const quant = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.max(0, Math.floor(p * sorted.length)))];

// ---------------------------------------------------------------------------

function main() {
  const argv = process.argv.slice(2);
  let emit = null;
  let from = null;
  const files = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--emit') emit = argv[++i];
    else if (argv[i] === '--from') from = argv[++i];
    else files.push(argv[i]);
  }

  let runs;
  if (from) {
    const j = JSON.parse(fs.readFileSync(from, 'utf8'));
    runs = j.runs.map(inflate);
  } else {
    if (files.length === 0) {
      console.error('usage: ladder_band.cjs [--emit out.json] <dataset.json ...>   |   --from compact.json');
      process.exit(2);
    }
    runs = files.map(readRun);
  }
  runs.sort((a, b) => a.load - b.load || String(a.label).localeCompare(String(b.label)));

  const rows = runs.map((r) => ({ run: r, net: statsOf(r.net), task: statsOf(r.task) }));

  console.log(`# THE LADDER RE-TAKEN, BOTH CLOCKS — ${rows.length} runs of bulk300`);
  console.log(`# chromium ${runs[0].chromium}, ${runs[0].when} .. ${runs[runs.length - 1].when}`);
  console.log('');
  console.log('## Per run. ABSOLUTES BESIDE EVERY RATIO.');
  console.log('');
  console.log(
    '| run | load | floor ms taskNet | floor ms task | seam net | seam task | BAND net | BAND task | ctl-2x/floor net | ctl-2x/floor task | bar net | bar task |'
  );
  console.log('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|');
  for (const { run, net, task } of rows) {
    console.log(
      `| ${run.label} | ${run.load} | ${net.floorPooled.toFixed(3)} | ${task.floorPooled.toFixed(3)} | ` +
        `${pc(net.seam)} | ${pc(task.seam)} | ${pc(net.band)}${net.band > seamlib.BAND_CEILING ? ' **BREACH**' : ''} | ` +
        `${pc(task.band)}${task.band > seamlib.BAND_CEILING ? ' **BREACH**' : ''} | ` +
        `${net.ctl2xMean.toFixed(4)} | ${task.ctl2xMean.toFixed(4)} | ${net.bar.toFixed(4)} | ${task.bar.toFixed(4)} |`
    );
  }

  // --- by rung --------------------------------------------------------------
  const rungs = [...new Set(rows.map((r) => r.run.load))].sort((a, b) => a - b);
  console.log('');
  console.log('## By rung');
  console.log('');
  for (const clock of ['net', 'task']) {
    console.log(`### ${clock === 'net' ? '`taskNet` — frame-only (superseded)' : 'raw `TaskDuration` — the published clock'}`);
    console.log('');
    console.log('| competing cores | runs | floor ms | per-sample CV | seam | SEGMENT | ROUND | POSITION | band |');
    console.log('|---:|---:|---:|---:|---:|---:|---:|---:|---:|');
    for (const L of rungs) {
      const rs = rows.filter((r) => r.run.load === L).map((r) => r[clock]);
      console.log(
        `| ${L} | ${rs.length} | ${mean(rs.map((r) => r.floorPooled)).toFixed(3)} | ` +
          `${pc(mean(rs.map((r) => r.floorSampleCv)))} | ` +
          `${pc(mean(rs.map((r) => r.seam)))} | ${pc(mean(rs.map((r) => r.effects.segment)))} | ` +
          `${pc(mean(rs.map((r) => r.effects.round)))} | ${pc(mean(rs.map((r) => r.effects.position)))} | ` +
          `${pc(mean(rs.map((r) => r.band)))} |`
      );
    }
    console.log('');
  }

  // --- the headline figures -------------------------------------------------
  for (const clock of ['net', 'task']) {
    const rs = rows.map((r) => r[clock]);
    const bands = rs.map((r) => r.band).sort((a, b) => a - b);
    const floors = rs.map((r) => r.floorPooled);
    const breaches = bands.filter((b) => b > seamlib.BAND_CEILING).length;
    console.log(`## ${clock === 'net' ? 'taskNet (frame-only)' : 'raw TaskDuration (published)'} — headline`);
    console.log('');
    console.log(`- floor absolute: ${Math.min(...floors).toFixed(3)} – ${Math.max(...floors).toFixed(3)} ms, mean ${mean(floors).toFixed(3)}`);
    console.log(`- floor TARED, per-block mean: ${Math.min(...rs.map((r) => r.floorTared)).toFixed(3)} – ${Math.max(...rs.map((r) => r.floorTared)).toFixed(3)} ms`);
    console.log(`- plumb (tare) pooled: ${mean(rs.map((r) => r.plumbPooled)).toFixed(3)} ms`);
    console.log(`- per-sample CV of the floor inside a block, mean over blocks: ${pc(mean(rs.map((r) => r.floorSampleCv)))}`);
    console.log(`- band: ${pc(Math.min(...bands))} – ${pc(Math.max(...bands))}, mean ${pc(mean(bands))}, median ${pc(p50(bands))}`);
    console.log(`- runs breaching the ${pc(seamlib.BAND_CEILING)} ceiling: **${breaches} of ${bands.length}**`);
    console.log(`- seam: ${pc(Math.min(...rs.map((r) => r.seam)))} – ${pc(Math.max(...rs.map((r) => r.seam)))}, mean ${pc(mean(rs.map((r) => r.seam)))}`);
    console.log(`- ctl-2x/floor: ${mean(rs.map((r) => r.ctl2xMean)).toFixed(4)} [${Math.min(...rs.map((r) => r.ctl2xMean)).toFixed(4)} – ${Math.max(...rs.map((r) => r.ctl2xMean)).toFixed(4)}]`);
    console.log(`- bar hicasso/reagent-subs: ${mean(rs.map((r) => r.bar)).toFixed(4)} [${Math.min(...rs.map((r) => r.barMin)).toFixed(4)} – ${Math.max(...rs.map((r) => r.barMax)).toFixed(4)}]`);
    console.log('');
    console.log('**Correlations across the ladder** — the multiplicativity evidence:');
    console.log('');
    console.log(`- corr(ctl-2x/floor, floor) = **${corr(rs.map((r) => r.ctl2xMean), floors).toFixed(2)}** — an additive \`c\` reads \`(2W+c)/(W+c)\`, which FALLS as \`c\` grows, so a NEGATIVE sign is the additive signature`);
    console.log(`- corr(bar, floor)          = ${corr(rs.map((r) => r.bar), floors).toFixed(2)}`);
    console.log(`- corr(bar, seam)           = ${corr(rs.map((r) => r.bar), rs.map((r) => r.seam)).toFixed(2)}`);
    console.log(`- corr(band, floor)         = ${corr(rs.map((r) => r.band), floors).toFixed(2)} — does the BAND track load?`);
    console.log('');

    // THE ADDITIVE CONSTANT, by rung. Pure multiplicativity predicts
    // `ctl-2x/floor = 2.00` exactly at every rung, because `(2kF)/(kF) = 2`
    // for any `k`. A fixed additive `c` predicts a ratio BELOW 2 that RISES
    // toward it as load inflates `W`. Which of those the data does is the
    // whole question, and it is a table rather than an argument.
    console.log('**The additive constant the tare does not remove**, by rung — `c = W·(2 − ctl-2x/floor)`:');
    console.log('');
    console.log('| competing cores | floor tared ms | ctl-2x/floor | implied c ms | c/W |');
    console.log('|---:|---:|---:|---:|---:|');
    for (const L of rungs) {
      const g = rows.filter((r) => r.run.load === L).map((r) => r[clock]);
      const W = mean(g.map((r) => r.floorTared));
      const ratio = mean(g.map((r) => r.ctl2xMean));
      const c = mean(g.flatMap((r) => r.cImplied));
      console.log(`| ${L} | ${W.toFixed(3)} | ${ratio.toFixed(4)} | ${c.toFixed(3)} | ${(c / W).toFixed(3)} |`);
    }
    console.log('');

    // The bootstrap.
    const pooled = rs.flatMap((r) => r.ratios);
    const bs = bootstrapBand(pooled, 20000, 0x5eed5eed);
    // AND a within-run bootstrap: resample each run's OWN eighteen blocks, so
    // the question is "would this run breach again if re-taken under identical
    // conditions" rather than "would a run drawn from the whole ladder". The
    // pooled one carries between-run variation and is the wider — a future run
    // is a future run, not a repeat of a past one, so the pooled one is what a
    // ceiling should be set against and the within-run one is the floor of the
    // estimate.
    const withinRun = mean(
      rs.map((r) => {
        const b = bootstrapBand(r.ratios, 2000, 0x5eed5eed);
        return b.filter((x) => x > seamlib.BAND_CEILING).length / b.length;
      })
    );
    console.log(
      `**The band's own run-level sampling distribution**, 20,000 resamples of 18 blocks from this ` +
        `ladder's ${pooled.length} pooled blocks: median ${pc(quant(bs, 0.5))}, q90 ${pc(quant(bs, 0.9))}, ` +
        `q95 ${pc(quant(bs, 0.95))}, q99 ${pc(quant(bs, 0.99))}.`
    );
    console.log('');
    console.log(`| candidate ceiling | P(fire) pooled | P(fire) within-run | runs of these 19 that breach |`);
    console.log('|---:|---:|---:|---:|');
    for (const c of [0.2, 0.25, 0.3, 0.35, 0.4]) {
      const pooledP = bs.filter((b) => b > c).length / bs.length;
      const wr = mean(
        rs.map((r) => {
          const b = bootstrapBand(r.ratios, 2000, 0x5eed5eed);
          return b.filter((x) => x > c).length / b.length;
        })
      );
      console.log(
        `| ${pc(c)} | ${(pooledP * 100).toFixed(1)}% | ${(wr * 100).toFixed(1)}% | ` +
          `${rs.filter((r) => r.band > c).length} of ${rs.length} |`
      );
    }
    console.log('');
    console.log(
      `At the ceiling in force (${pc(seamlib.BAND_CEILING)}) the within-run estimate is ` +
        `${(withinRun * 100).toFixed(1)}% and ${rs.filter((r) => r.band > seamlib.BAND_CEILING).length} of ` +
        `${rs.length} of this ladder's runs breach.`
    );
    console.log('');
  }

  // --- the two clocks against each other ------------------------------------
  console.log('## The two clocks, on the same samples');
  console.log('');
  const dn = rows.map((r) => r.net.band);
  const dt = rows.map((r) => r.task.band);
  const wider = dn.filter((b, i) => b > dt[i]).length;
  console.log(
    `- the frame-only band is wider than the corrected-clock band on **${wider} of ${dn.length}** runs; ` +
      `mean ${pc(mean(dn))} against ${pc(mean(dt))}, a ratio of **${(mean(dn) / mean(dt)).toFixed(2)}×**`
  );
  console.log(
    `- floor absolute: taskNet ${mean(rows.map((r) => r.net.floorPooled)).toFixed(3)} ms against ` +
      `raw TaskDuration ${mean(rows.map((r) => r.task.floorPooled)).toFixed(3)} ms — a gap of ` +
      `**${(mean(rows.map((r) => r.task.floorPooled)) - mean(rows.map((r) => r.net.floorPooled))).toFixed(3)} ms**, ` +
      `which is what a floor quoted on one clock costs when it is read against a range calibrated on the other`
  );
  console.log(
    `- per-sample CV of the floor inside a block: taskNet ${pc(mean(rows.map((r) => r.net.floorSampleCv)))} ` +
      `against raw TaskDuration ${pc(mean(rows.map((r) => r.task.floorSampleCv)))}`
  );
  console.log('');

  if (emit) {
    fs.mkdirSync(path.dirname(path.resolve(emit)), { recursive: true });
    fs.writeFileSync(
      path.resolve(emit),
      JSON.stringify(
        {
          what: 'rf2-ymi6j — the band ladder re-taken on the corrected clock, reduced',
          bandCeiling: seamlib.BAND_CEILING,
          segments: SEGMENTS, fixed: FIXED, bar: BAR,
          note:
            'Per-block TARED cells on both clocks. floorCells[round][segment] and fixedCells[round][segment] ' +
            'are p50(arm) - p50(plumb) in that block. Everything the ladder publishes is a function of these; ' +
            'recompute with `ladder_band.cjs --from <this file>`.',
          runs: runs.map(compact),
        },
        null,
        1
      )
    );
    console.error(`[ladder_band] compact dataset -> ${path.resolve(emit)}`);
  }
}

main();
