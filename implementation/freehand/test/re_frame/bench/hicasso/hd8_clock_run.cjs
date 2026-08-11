#!/usr/bin/env node
// HD-008's DONOR ROWS ON THE CLOCK OF RECORD — driver (rf2-2rtt6.31).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/hd8_clock_run.cjs
//
// The mount-gate amendment (rf2-2rtt6.1, recorded 2026-08-02) ratifies raw
// `TaskDuration` — script AND frame — as the bar's adjudicating clock and
// restates the mount gate as ONE line: `<= 1.10x` direct UIx-on-subs,
// floor-normalised, same run, on that clock. The published HD-008 donor
// rows were taken on the in-page `performance.now()` window, which that
// amendment makes DIAGNOSTIC ONLY. This driver takes the six published
// arms' MOUNT rows through the frame-settlement door `clock_run.cjs`
// established, so the donor re-take can be adjudicated against the line
// it now answers to.
//
// ## The clock, and the door — stated per the stamp discipline
//
//   PUBLISHED   Performance.getMetrics raw TaskDuration, frame-settled
//               (rAF + setTimeout) — the arm's script AND the frame it
//               caused, main-thread only, no raster/composite. CDP does
//               not document TaskDuration's semantics; this is Chromium's
//               accounting read from source (rf2-8nqsl), and the clock is
//               never called by the bare adjective "frame-inclusive" —
//               that adjective is what both instrument defects hid behind.
//   DIAGNOSTIC  taskNet (TaskDuration less DevToolsCommandDuration) — a
//               FRAME-ONLY reading through this door, because every arm's
//               operation runs inside `page.evaluate` and Chromium bills
//               page script run inside a protocol command to the DevTools
//               term (rf2-yd52q, rf2-emvod).
//   DIAGNOSTIC  the in-page flushSync window (`lane/mount-arm!`'s `:ms`) —
//               the published rows' own clock, taken on the SAME samples
//               so the two instruments are compared on one operation.
//
//   THE DOOR    every arm, the plumb tare included:
//               `page.evaluate -> HD8CLOCK.sample`, settled to the next
//               frame in-page before the promise resolves. One door, so
//               its cost is common-mode; the tare measures it and every
//               published figure subtracts it.
//
// ## Controls, and what each can certify
//
//   * plumb tare — subtracted; reported.
//   * ctl-2x — the floor at twice the boundaries, predicted 2.00x,
//     adjudicated STRICT (every block inside +/-25%). rf2-jcm3p records
//     the mount-row undershoot (1.8173x over rf2-emvod's seven runs): an
//     additive per-sample constant the tare does not remove survives in
//     `(2W + c)/(W + c)`, and NO changed-set (three-point) control can
//     reach a mount row — a mount has no standing page. So this control
//     certifies that the instrument has page-proportional signal and
//     bounds the additive residual (printed as c, both rows); it cannot
//     certify exactness, and a clean pass at 2.00 is not expected.
//   * THE BAND — seam.cjs's `ctl-2x / floor` per-block statistic, ceiling
//     35% on raw TaskDuration (rf2-ymi6j's recalibration; the 25% figure
//     is superseded). A run whose band breaches the ceiling has NO
//     reportable magnitude; a gated ratio whose margin to the 1.10 line
//     sits inside the band is INSTRUMENT-LIMITED, not a pass.
//   * the arm-order guard, tolerance 0.35 (HD-008's own stated choice for
//     a browser mount clock), on the raw TaskDuration samples — the clock
//     the rows are stated on. Refusal is exit 2: repair the arm, never
//     the guard.
//
// ## What this driver does NOT measure
//
// Write rows. rf2-d2tzk fences the bulk row (its floor sits on the clock
// clamp; a resolved yield correction refuses it), and rf2-7iqb5 puts this
// box's bulk-class noise (28-48% within-block IQR) an order of magnitude
// above the ~3.5% a difference-statistic control needs. The mount rows
// are what the gate verdict turns on; the bulk magnitudes are REFUSED
// here by construction, with this paragraph as the reason.
//
// ## Exit codes
//
//   0  measured; gates passed
//   1  the run failed its own gates (build, page error, parity, quiet-box)
//   2  THE ARM-ORDER GUARD REFUSED — repair the arm, never the guard
//   3  a window's value never reached the page (unverified read-backs)
//   4  the run's reproducibility band breached seam.cjs's ceiling
//   5  the positive control did not see the change its arithmetic predicts
//
// 3, 4 and 5 are rf2-rr6do's repair: all three were computed, printed and
// written into the dataset, and none of them reached the exit. See the note
// above `verdict` below.
//
// ## Where the datasets land
//
// The canonical dataset directory holds THE PUBLISHED SHAPE and nothing
// else. A run that is narrowed (HD8CLOCK_ONLY), taken at an overridden
// depth, taken `--no-build`, or refused by the verdict above writes to a
// sibling `.unpublished` directory instead, named on stdout with the reason;
// an explicit HD8CLOCK_DATA_DIR is honoured as given. See the note above
// `destination` — that routing is rf2-2rtt6.31's half of the same fail-open
// rf2-rr6do repaired on the exit path, and the rule census_clock_run.cjs
// (rf2-2rtt6.56) already carries one file over.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');
const guard = require('../../freehand/bench/order_guard.cjs');
const seamlib = require('./seam.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const REPO = path.resolve(IMPL, '..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.HD8CLOCK_OUT_DIR || 'out/hd8-clock';
const INIT_FN = 're-frame.bench.hicasso.hd8-clock-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.HD8CLOCK_PORT || 8141);

// The published design: 6 rounds x 3 blocks x (4 warmup + 10 samples) per
// arm — 18 blocks for the band (the shape rf2-ymi6j's ceiling was
// calibrated on), and TEN warm samples per block cell, which is
// `clock_run.cjs`'s own per-block depth. The first cut of this driver ran
// 2 + 4 and its block p50s were too fragile to adjudicate anything: the
// control failed strict on single low-side block outliers (1.19x against
// 2.00x) and every gated range straddled the 1.10 boundary — an
// INSTRUMENT-LIMITED verdict manufactured by the design, not by the
// arms. That cut is recorded, not hidden; this is the repair. A run with
// any of these overridden prints the override in its provenance and is
// NOT the published shape.
const ROUNDS = Number(process.env.HD8CLOCK_ROUNDS || 6);
const BLOCKS = Number(process.env.HD8CLOCK_BLOCKS || 3);
const WARMUP = Number(process.env.HD8CLOCK_WARMUP || 4);
const SAMPLES = Number(process.env.HD8CLOCK_SAMPLES || 10);
const TOLERANCE = Number(process.env.HD8CLOCK_TOLERANCE || 0.35);
const CONTROL_SLACK = 0.25;
const GATE_LINE = 1.1; // the amendment's one line: donor <= 1.10x direct UIx
const NO_BUILD = process.argv.includes('--no-build');
const SKIP_QUIET = process.env.HD8CLOCK_SKIP_QUIET === '1';

// The published depth, in ONE place. The provenance stamp and the dataset
// write path must agree on what "the published shape" is, and two copies of
// that predicate is how they drift apart.
const PUBLISHED_DEPTH = { rounds: 6, blocks: 3, warmup: 4, samples: 10 };
const depthIsPublished = () =>
  ROUNDS === PUBLISHED_DEPTH.rounds &&
  BLOCKS === PUBLISHED_DEPTH.blocks &&
  WARMUP === PUBLISHED_DEPTH.warmup &&
  SAMPLES === PUBLISHED_DEPTH.samples;

// Where the run's compact datasets go. Named per run id below; the page's
// provenance points here, because a published study a reader cannot
// recompute from the landed tree is rf2-cvvb7's recorded fault.
const DATA_DIR_OVERRIDDEN = Boolean((process.env.HD8CLOCK_DATA_DIR || '').trim());
const DATA_DIR =
  process.env.HD8CLOCK_DATA_DIR || path.join(__dirname, 'data', 'hd8clock-2rtt6-31');

const ALL_RUNS = [
  { id: 'uix', query: '?adapter=uix', why: 'donor rungs against the frontier — the GATED pairs, within one process' },
  { id: 'reagent', query: '?adapter=reagent', why: 'plus stock Reagent, co-instrumented (NOT a second gate)' },
  { id: 'slim', query: '?adapter=slim', why: 'plus reagent-slim, co-instrumented (NOT a second gate)' },
];
const ONLY = (process.env.HD8CLOCK_ONLY || '').trim();
const RUNS = ONLY ? ALL_RUNS.filter((r) => ONLY.split(',').includes(r.id)) : ALL_RUNS;

const ROWS = ['mount-M', 'mount-U'];
const PLUMB = 'plumb';
const FLOOR = 'floor';
const CTL = 'ctl-2x';

// The gated pairs — numerator over denominator, both in the SAME run.
// `uix` is the anchor the amendment adopted (the direct-UIx anchor, not
// the parity-baseline phrasing); the Reagent pairs are co-instrumented
// and reported beside the gate, never as a second gate.
const GATED = [
  ['donor-r1', 'uix'],
  ['donor-r2', 'uix'],
];
const BESIDE = {
  uix: [['donor-r2', 'donor-r1']],
  reagent: [
    ['donor-r2', 'donor-r1'],
    ['donor-r1', 'reagent'],
    ['donor-r2', 'reagent'],
    ['uix', 'reagent'],
  ],
  slim: [
    ['donor-r2', 'donor-r1'],
    ['donor-r1', 'reagent-slim'],
    ['donor-r2', 'reagent-slim'],
    ['uix', 'reagent-slim'],
  ],
};

// ---------------------------------------------------------------------------
// Small statistics — ranges, never a bare mean
// ---------------------------------------------------------------------------

const r4 = (x) => Math.round(x * 10000) / 10000;
const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

function band(xs) {
  return {
    mean: r4(xs.reduce((a, b) => a + b, 0) / xs.length),
    min: r4(Math.min(...xs)),
    max: r4(Math.max(...xs)),
  };
}

function controlVerdict(predicted, per, slack) {
  const lo = predicted * (1 - slack);
  const hi = predicted * (1 + slack);
  const b = band(per);
  return {
    predicted: r4(predicted),
    band: [r4(lo), r4(hi)],
    measured: b,
    perBlock: per.map(r4),
    ok: per.every((x) => x >= lo && x <= hi),
    rule: 'strict — EVERY block inside the band',
  };
}

/** c recovered from the doubling ratio: floor = W + c, ctl2x = 2W + c => c = floorTared * (2 - R). */
const additiveConstant = (floorTared, ratio2x) => floorTared * (2 - ratio2x);

// ---------------------------------------------------------------------------
// Provenance — commit, blobs, and the run's own design
// ---------------------------------------------------------------------------

function sh(cmd, args) {
  return spawnSync(cmd, args, { cwd: REPO, encoding: 'utf8' });
}

function revision() {
  const r = sh('git', ['rev-parse', 'HEAD']);
  return r.status === 0 ? r.stdout.trim() : 'unknown';
}

const BLOB_FILES = [
  'implementation/freehand/test/re_frame/bench/hicasso/hd8_clock_app.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/hd8_clock_run.cjs',
  'implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/hd8_witnesses.cljs',
  'implementation/freehand/test/re_frame/bench/hicasso/lane.cljs',
  'implementation/core/src/re_frame/substrate/spine.cljs',
];

function blobs() {
  const out = {};
  for (const f of BLOB_FILES) {
    // `git hash-object` on the WORKING file, so an uncommitted instrument
    // edit stamps the run with the blob that actually ran rather than the
    // one HEAD would have run.
    const r = sh('git', ['hash-object', f]);
    out[f] = r.status === 0 ? r.stdout.trim() : 'unknown';
  }
  return out;
}

// ---------------------------------------------------------------------------
// The quiet-box gate — verified, not asserted
// ---------------------------------------------------------------------------

function cpuSamples(n) {
  // Get-Counter first (one call, n one-second samples); CIM fallback for
  // hosts whose counter names are localised.
  const gc = spawnSync(
    'powershell',
    [
      '-NoProfile',
      '-Command',
      `(Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 1 -MaxSamples ${n}).CounterSamples | ForEach-Object { [math]::Round($_.CookedValue,1) }`,
    ],
    { encoding: 'utf8', timeout: (n + 20) * 1000 }
  );
  if (gc.status === 0) {
    const xs = gc.stdout.split(/\r?\n/).map((s) => Number(s.trim())).filter(Number.isFinite);
    if (xs.length >= n) return xs.slice(0, n);
  }
  const xs = [];
  for (let i = 0; i < n; i++) {
    const r = spawnSync(
      'powershell',
      ['-NoProfile', '-Command', '(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average'],
      { encoding: 'utf8', timeout: 20000 }
    );
    const v = Number((r.stdout || '').trim());
    if (Number.isFinite(v)) xs.push(v);
  }
  return xs;
}

function quietGate(label) {
  if (SKIP_QUIET) {
    console.log(`;; quiet    ${label}: SKIPPED (HD8CLOCK_SKIP_QUIET=1) — NOT the published shape`);
    return { ok: true, skipped: true, samples: [] };
  }
  const THRESH = 30;
  const NEED = 8;
  for (let attempt = 1; attempt <= 5; attempt++) {
    const xs = cpuSamples(NEED);
    const ok = xs.length >= NEED && xs.every((x) => x < THRESH);
    console.log(
      `;; quiet    ${label}: attempt ${attempt} — CPU [${xs.map((x) => x.toFixed(0)).join(', ')}]% ` +
        `(${NEED} consecutive < ${THRESH}% required) ${ok ? 'QUIET' : 'NOT QUIET'}`
    );
    if (ok) return { ok: true, samples: xs, attempt };
    spawnSync('powershell', ['-NoProfile', '-Command', 'Start-Sleep -Seconds 15'], { timeout: 30000 });
  }
  return { ok: false, samples: [] };
}

// ---------------------------------------------------------------------------
// Build + serve
// ---------------------------------------------------------------------------

const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` + `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[hd8clock] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[hd8clock] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'hd8clock',
  });
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>HD-008 clock of record</title></head>' +
      '<body><div id="app"></div><script src="main.js"></script></body></html>'
  );
  return http
    .createServer((req, res) => {
      const rel = decodeURIComponent(req.url.split('?')[0]);
      const file = path.join(OUT, rel === '/' ? 'index.html' : rel);
      if (!file.startsWith(OUT) || !fs.existsSync(file)) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    })
    .listen(PORT);
}

// ---------------------------------------------------------------------------
// The instrument: Chrome's own renderer counters
// ---------------------------------------------------------------------------

const METRICS = [
  'TaskDuration',
  'ScriptDuration',
  'LayoutDuration',
  'RecalcStyleDuration',
  'DevToolsCommandDuration',
  'LayoutCount',
  'RecalcStyleCount',
];

async function readMetrics(cdp) {
  const { metrics } = await cdp.send('Performance.getMetrics');
  const out = {};
  for (const m of metrics) if (METRICS.includes(m.name)) out[m.name] = m.value;
  return out;
}

function deltaOf(a, b) {
  const d = {};
  for (const k of METRICS) d[k] = (b[k] || 0) - (a[k] || 0);
  const task = d.TaskDuration * 1000;
  const devtools = d.DevToolsCommandDuration * 1000;
  return {
    task, // PUBLISHED — raw TaskDuration, script AND frame
    taskNet: task - devtools, // DIAGNOSTIC — frame-only through this door
    devtools,
    script: d.ScriptDuration * 1000,
    layout: d.LayoutDuration * 1000,
    style: d.RecalcStyleDuration * 1000,
    layoutCount: d.LayoutCount,
    styleCount: d.RecalcStyleCount,
  };
}

// ---------------------------------------------------------------------------
// One row of one run
// ---------------------------------------------------------------------------

async function runRow(browser, runDef, rowId) {
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => {
    pageErrors.push(e.message);
    console.error('[hd8clock] PAGE ERROR:', e.message);
  });
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[hd8clock]')) console.log(t);
  });

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');

  await navigate(page, `http://127.0.0.1:${PORT}/${runDef.query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the wait for window.HD8CLOCK_READY',
  });
  await page.waitForFunction('window.HD8CLOCK_READY === true', null, { timeout: 180000 });

  // hd8's own fairness gate ran at boot, whole; refuse the run if it failed.
  const parity = await page.evaluate('window.HD8CLOCK_PARITY');
  if (!parity || !parity.ok) {
    await page.close();
    throw new Error(
      `parity gate failed at boot for ${runDef.id}: ${JSON.stringify(parity && parity.problems)}` +
        (parity && !parity.canFail ? ' (and/or the comparison could not answer false)' : '')
    );
  }

  const plan = await page.evaluate((r) => window.HD8CLOCK.plan(r), rowId);
  const armIds = plan.map((a) => a.id);
  const k = armIds.length;

  const canon = {};
  for (const a of armIds) {
    canon[a] = await page.evaluate(([r, arm]) => window.HD8CLOCK.canon(r, arm), [rowId, a]);
  }

  // blocks[round][block][arm] -> [warm ms...] on each clock
  const blocksTask = [];
  const blocksNet = [];
  const blocksInPage = [];
  const samplesTask = []; // arm-order guard, on the clock of record
  const samplesNet = []; // the same guard on the diagnostic clock
  const decomposition = {};
  const granularity = new Set();
  let position = 0;
  let previous = null;

  const bump = (arm, d, inPage) => {
    const acc = (decomposition[arm] ||= {
      n: 0, task: 0, taskNet: 0, devtools: 0, script: 0, style: 0, layout: 0, layoutCount: 0, inPage: 0,
    });
    acc.n += 1;
    acc.task += d.task;
    acc.taskNet += d.taskNet;
    acc.devtools += d.devtools;
    acc.script += d.script;
    acc.style += d.style;
    acc.layout += d.layout;
    acc.layoutCount += d.layoutCount;
    acc.inPage += Number.isFinite(inPage) ? inPage : 0;
  };

  for (let round = 0; round < ROUNDS; round++) {
    const roundTask = [];
    const roundNet = [];
    const roundInPage = [];
    for (let blk = 0; blk < BLOCKS; blk++) {
      const accT = {};
      const accN = {};
      const accI = {};
      for (const a of armIds) {
        accT[a] = [];
        accN[a] = [];
        accI[a] = [];
      }
      for (let s = 0; s < WARMUP + SAMPLES; s++) {
        for (const j of guard.schedule(k, s)) {
          const armId = armIds[j];
          const m0 = await readMetrics(cdp);
          const res = await page.evaluate(([r, arm]) => window.HD8CLOCK.sample(r, arm), [rowId, armId]);
          const m1 = await readMetrics(cdp);
          const d = deltaOf(m0, m1);
          if (d.task > 0) granularity.add(d.task);
          // AFTER the counters: verify + unmount, never billed to the mount.
          const reaped = await page.evaluate((r) => window.HD8CLOCK.reap(r), rowId);
          const ok = res.ok && reaped.ok;
          if (!ok) {
            // Adjudicated by the tally at the end of the row.
          }
          if (s >= WARMUP) {
            accT[armId].push(d.task);
            accN[armId].push(d.taskNet);
            accI[armId].push(res.inPageMs);
            bump(armId, d, res.inPageMs);
            samplesTask.push({ arm: armId, value: d.task, predecessor: previous, position });
            samplesNet.push({ arm: armId, value: d.taskNet, predecessor: previous, position });
            position += 1;
          }
          previous = armId;
        }
      }
      roundTask.push(accT);
      roundNet.push(accN);
      roundInPage.push(accI);
    }
    blocksTask.push(roundTask);
    blocksNet.push(roundNet);
    blocksInPage.push(roundInPage);
  }

  const tally = await page.evaluate('window.HD8CLOCK.tally()');
  const td = await page.evaluate('window.HD8CLOCK.teardownCheck()');
  const runtime = await page.evaluate('window.HD8CLOCK.runtime()');
  await page.close();
  if (td.length > 0) throw new Error(`teardown FAILED in ${runDef.id}/${rowId}: ${td.join(', ')}`);
  if (pageErrors.length > 0) throw new Error(`page errors in ${runDef.id}/${rowId}: ${pageErrors.join('; ')}`);

  return {
    runId: runDef.id, rowId, armIds, plan, canon,
    blocksTask, blocksNet, blocksInPage,
    samplesTask, samplesNet, decomposition, tally, runtime,
    granularity: [...granularity].sort((a, b) => a - b),
  };
}

// ---------------------------------------------------------------------------
// Adjudication
// ---------------------------------------------------------------------------

/** Tared p50 of one arm in one block: p50(arm) - p50(plumb of the SAME block). */
function taredCell(blocks, r, b, arm) {
  return p50(blocks[r][b][arm]) - p50(blocks[r][b][PLUMB]);
}

function perBlock(blocks, f) {
  const out = [];
  for (let r = 0; r < blocks.length; r++) {
    for (let b = 0; b < blocks[r].length; b++) out.push(f(r, b));
  }
  return out;
}

function report(out) {
  const { runId, rowId, armIds, canon, blocksTask, blocksNet, blocksInPage, samplesTask, samplesNet, decomposition, tally, runtime, granularity } = out;

  console.log(`\n;; ==== RUN ${runId} — ROW ${rowId} ====`);
  console.log(`;; runtime  ${runtime}`);
  console.log(`;; writes   ${tally.unverified} unverified of ${tally.writes} (mount + element-count read-backs)`);
  console.log(
    `;; clock    PUBLISHED: Performance.getMetrics raw TaskDuration, frame-settled (rAF + setTimeout) — ` +
      `script AND frame, main thread only, no raster/composite`
  );
  console.log(
    `;; door     every arm (plumb included): page.evaluate -> HD8CLOCK.sample. taskNet through this door ` +
      `is FRAME-ONLY (the subtraction removes the arm's own script) and is DIAGNOSTIC below`
  );
  console.log(
    `;; grain    smallest non-zero per-sample TaskDuration delta ${granularity.length ? granularity[0].toFixed(6) : 'n/a'} ms`
  );
  console.log(
    `;; stamp    B/E/Q = 300/300/300 — 300 boundaries, one subscription edge each, 300 distinct query ` +
      `vectors (${rowId === 'mount-M' ? '[:hd8/row i]' : '[:hd8/cell i]'}); M = 903 elements, U = 301; ctl-2x doubles them`
  );

  // parity record
  const nonControl = Object.entries(canon).filter(([, c]) => !c.control);
  const refHash = nonControl.length ? nonControl[0][1].hash : null;
  const disagree = nonControl.filter(([, c]) => c.hash !== refHash).map(([a]) => a);
  console.log(
    `;; parity   ${nonControl.length} non-control arms, canonical DOM ` +
      `${disagree.length === 0 ? 'IDENTICAL' : 'DISAGREES: ' + disagree.join(', ')} ` +
      `(${nonControl.length ? nonControl[0][1].bytes : 0} bytes; boot gate also held at stress AND small size, and can answer false)`
  );

  // the tare
  const plumbAll = blocksTask.flatMap((r) => r.flatMap((b) => b[PLUMB]));
  const pb = band(plumbAll);
  console.log(
    `;; tare     plumb p50 ${fmt(p50(plumbAll))} ms [${fmt(pb.min)} – ${fmt(pb.max)}] — SUBTRACTED from every figure below`
  );

  // per-arm vs floor + absolutes, on the clock of record
  console.log(`;; ---- per-arm, ratio to the floor in the SAME block (tared), raw TaskDuration ----`);
  const vsFloor = {};
  for (const arm of armIds) {
    if (arm === PLUMB) continue;
    const abs = p50(blocksTask.flatMap((r) => r.flatMap((b) => b[arm])));
    const absNet = p50(blocksNet.flatMap((r) => r.flatMap((b) => b[arm])));
    const absIn = p50(blocksInPage.flatMap((r) => r.flatMap((b) => b[arm])).filter(Number.isFinite));
    if (arm === FLOOR) {
      console.log(
        `;;   ${arm.padEnd(14)} ABSOLUTE p50 task ${fmt(abs, 3)} ms (tared ${fmt(abs - p50(plumbAll), 3)}) — ` +
          `taskNet ${fmt(absNet, 3)}, in-page ${fmt(absIn, 3)} — the denominator`
      );
      continue;
    }
    const per = perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, arm) / taredCell(blocksTask, r, b, FLOOR));
    const bd = band(per);
    vsFloor[arm] = bd;
    console.log(
      `;;   ${arm.padEnd(14)} ${fmt(bd.mean)}x floor [${fmt(bd.min)} – ${fmt(bd.max)}]  n=${per.length} blocks   ` +
        `ABS task ${fmt(abs, 3)} ms = taskNet ${fmt(absNet, 3)} + (in-page ${fmt(absIn, 3)})`
    );
  }

  // the control
  const ctlPer = perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, CTL) / taredCell(blocksTask, r, b, FLOOR));
  const ctl = controlVerdict(2.0, ctlPer, CONTROL_SLACK);
  const floorTared = p50(perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, FLOOR)));
  const c = additiveConstant(floorTared, ctl.measured.mean);
  console.log(`;; ---- POSITIVE CONTROL: ctl-2x builds exactly twice the page; prediction 2.00x ----`);
  console.log(
    `;;   ${ctl.ok ? 'PASS' : 'FAIL'}  measured ${fmt(ctl.measured.mean)}x [${fmt(ctl.measured.min)} – ${fmt(ctl.measured.max)}] ` +
      `against [${fmt(ctl.band[0], 2)} – ${fmt(ctl.band[1], 2)}] (${ctl.rule})`
  );
  console.log(
    `;;   rf2-jcm3p's recorded mount undershoot expected (~1.82x): additive residual c = ` +
      `${fmt(c, 3)} ms on a tared floor of ${fmt(floorTared, 3)} ms. This control certifies ` +
      `page-proportional SIGNAL, not exactness; no changed-set control can reach a mount row.`
  );

  // the band, on the clock the rows are stated on
  const floorBlocks = blocksTask.map((r) => r.map((b) => b[FLOOR]));
  const floorCells = blocksTask.map((_, ri) => blocksTask[ri].map((_, bi) => taredCell(blocksTask, ri, bi, FLOOR)));
  const fixedCells = blocksTask.map((_, ri) => blocksTask[ri].map((_, bi) => taredCell(blocksTask, ri, bi, CTL)));

  const pairs = GATED.concat(BESIDE[runId] || []).filter(([n, d]) => armIds.includes(n) && armIds.includes(d));
  const bars = {};
  const barBands = {};
  for (const [n, d] of pairs) {
    const per = perBlock(blocksTask, (r, b) => taredCell(blocksTask, r, b, n) / taredCell(blocksTask, r, b, d));
    const bd = band(per);
    bars[`${n} / ${d}`] = bd.mean;
    barBands[`${n} / ${d}`] = bd;
  }
  const assessed = seamlib.assess({ floorBlocks, floorCells, fixedCells, bars });
  const bw = assessed.bandStats.band;
  console.log(
    `;; ---- THE BAND (ctl-2x / floor per block, seam.cjs): ${Number.isFinite(bw) ? (bw * 100).toFixed(1) + '%' : 'n/a'} ` +
      `— ceiling ${(seamlib.BAND_CEILING * 100).toFixed(0)}% (rf2-ymi6j) ${assessed.verdict.ceilingBreached ? '— BREACHED, no magnitude reportable' : ''} ----`
  );
  console.log(
    `;;   block seam (floor by block-position): [${assessed.seam.bySeg.map((x) => fmt(x, 3)).join(', ')}] ` +
      `spread ${(assessed.seam.spread * 100).toFixed(1)}% (null q50 ${(assessed.null.q50 * 100).toFixed(1)}% q95 ${(assessed.null.q95 * 100).toFixed(1)}%, p ${assessed.null.p}) — ` +
      `the "segments" here are consecutive blocks of one plan, so this is the PHASE null, not an adapter seam`
  );

  // the head-to-head table, with the gate line
  console.log(`;; ---- HEAD-TO-HEAD (same run, same blocks; floor cancels within a block) ----`);
  const verdicts = {};
  for (const [n, d] of pairs) {
    const key = `${n} / ${d}`;
    const bd = barBands[key];
    const gated = d === 'uix' && (n === 'donor-r1' || n === 'donor-r2');
    const netPer = perBlock(blocksNet, (r, b) => taredCell(blocksNet, r, b, n) / taredCell(blocksNet, r, b, d));
    const inPer = perBlock(blocksInPage, (r, b) => p50(blocksInPage[r][b][n]) / p50(blocksInPage[r][b][d])).filter(Number.isFinite);
    console.log(
      `;;   ${key.padEnd(26)} ${fmt(bd.mean)}x [${fmt(bd.min)} – ${fmt(bd.max)}]` +
        `${bd.min <= 1 && bd.max >= 1 ? '  [STRADDLES 1.0]' : ''}` +
        `   (taskNet ${fmt(band(netPer).mean)}x · in-page ${inPer.length ? fmt(band(inPer).mean) : 'n/a'}x)` +
        (gated ? '   << GATED PAIR' : '   (beside the gate, not a second gate)')
    );
    if (gated) {
      const margin10 = Math.abs(bd.mean / GATE_LINE - 1);
      let verdict;
      let why;
      if (assessed.verdict.ceilingBreached) {
        verdict = 'REFUSED';
        why = `the run's band ${(bw * 100).toFixed(1)}% exceeds the ${(seamlib.BAND_CEILING * 100).toFixed(0)}% ceiling — no magnitude reportable`;
      } else if (bd.min > GATE_LINE && margin10 > bw) {
        verdict = 'FAILS THE LINE';
        why = `whole range above 1.10 and margin ${(margin10 * 100).toFixed(1)}% clears the band ${(bw * 100).toFixed(1)}%`;
      } else if (bd.max < GATE_LINE && margin10 > bw) {
        verdict = 'INSIDE THE LINE';
        why = `whole range below 1.10 and margin ${(margin10 * 100).toFixed(1)}% clears the band ${(bw * 100).toFixed(1)}%`;
      } else {
        verdict = 'INSTRUMENT-LIMITED';
        why =
          bd.min <= GATE_LINE && bd.max >= GATE_LINE
            ? `the range straddles 1.10 — the run cannot resolve the boundary, which is NOT a pass`
            : `margin to 1.10 ${(margin10 * 100).toFixed(1)}% sits inside the band ${(bw * 100).toFixed(1)}% — NOT a pass`;
      }
      verdicts[key] = { verdict, why, mean: bd.mean, min: bd.min, max: bd.max, band: bw, ctlOk: ctl.ok };
      console.log(`;;     GATE <= 1.10x direct UIx (same run, clock of record): ${verdict} — ${why}` + (ctl.ok ? '' : ' — AND the control failed, so the magnitude additionally carries the control\'s failure'));
    }
  }

  // decomposition
  console.log(`;; ---- decomposition, mean ms per sample ----`);
  for (const [arm, a] of Object.entries(decomposition)) {
    console.log(
      `;;   ${arm.padEnd(14)} task ${(a.task / a.n).toFixed(3)} = taskNet ${(a.taskNet / a.n).toFixed(3)} + devtools ${(a.devtools / a.n).toFixed(3)}` +
        `   script ${(a.script / a.n).toFixed(4)}  style ${(a.style / a.n).toFixed(3)}  layout ${(a.layout / a.n).toFixed(3)}  in-page ${(a.inPage / a.n).toFixed(3)}`
    );
  }

  // guard, on the clock of record (and the diagnostic clock beside it)
  const gv = guard.verdict(samplesTask, { tolerance: TOLERANCE });
  const gvNet = guard.verdict(samplesNet, { tolerance: TOLERANCE });
  for (const line of guard.format(gv, `${runId} / ${rowId} (raw TaskDuration)`)) console.log(line);
  console.log(`;;   (the same guard on taskNet, diagnostic: ${gvNet.refuse ? 'REFUSE' : 'ok'})`);

  return { vsFloor, bars: barBands, verdicts, ctl, cAdditive: c, assessed, guardRefuse: gv.refuse, plumb: p50(plumbAll), floorTared };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// A refusal that only PRINTS is not a refusal (rf2-rr6do; rf2-tb345 repaired
// the same defect one tree over, in b8_run.cjs). This driver computed THREE
// refusals, printed each one loudly, wrote each into the dataset — and then
// took its exit off `failed` and the arm-order guard ALONE. So a quiet box
// with a clean guard could print
//
//   ;; writes   4 unverified of 36 (mount + element-count read-backs)
//   ;; ---- THE BAND ...: 41.2% — ceiling 35% — BREACHED, no magnitude reportable ----
//   ;;   FAIL  measured 1.21x [...] against [1.50 – 2.50]
//
// and still exit 0, on figures its own report had just refused.
//
// The correct shape already existed one file over: `clock_run.cjs` gates all
// three — unverified writes, the band ceiling, the positive control. This is
// that shape, with the decision moved into ONE pure function over a flat
// summary so the exit path is checkable without a release build and a
// headless Chromium — see `clock_exit_path.test.cjs`.
//
// The four conditions are INDEPENDENT: each refuses on its own, and when
// several fire every one of them is named. Precedence preserves every code
// this driver already had — a run that exited 1 still exits 1, a run the
// arm-order guard refused still exits 2 — so nothing that used to refuse now
// refuses differently.
//
// No refusal suppresses output, and none ever discards a completed
// measurement: the tables are printed and the datasets are written whatever
// this returns. A refusal is about what may be QUOTED, not about throwing
// the measurement away.
//
// What a refusal DOES decide is where the datasets land (rf2-2rtt6.31). The
// canonical directory is the PUBLISHED EVIDENCE SET, not "the last file this
// driver wrote", so a refused run is written to a `.unpublished` sibling and
// stamped `canonical: false` in the file rather than replacing the rows the
// studio page cites. Capture is not publication. See `destination` below —
// which is why this verdict is now computed BEFORE the write, not after it.

/** The flat record the exit is decided on: one entry per row actually taken. */
function summarise(failed, results) {
  return {
    failed: failed || null,
    rows: (results || []).map((r) => ({
      id: `${r.runId}/${r.rowId}`,
      guardRefuse: r.adjudication.guardRefuse,
      unverified: r.tally.unverified,
      writes: r.tally.writes,
      ctlOk: r.adjudication.ctl.ok,
      ctlMeasured: r.adjudication.ctl.measured.mean,
      ceilingBreached: r.adjudication.assessed.verdict.ceilingBreached,
      band: r.adjudication.assessed.bandStats.band,
    })),
  };
}

function verdict(summary) {
  const failed = summary && summary.failed;
  const rows = (summary && summary.rows) || [];
  const pct = (x) => (Number.isFinite(x) ? `${(x * 100).toFixed(1)}%` : 'n/a');
  const lines = [];

  const refused = rows.filter((r) => r.guardRefuse);
  const unverified = rows.filter((r) => r.unverified > 0);
  const overCeiling = rows.filter((r) => r.ceilingBreached);
  const ctlFailed = rows.filter((r) => !r.ctlOk);

  if (failed) lines.push(`[hd8clock] FAILED: ${failed}`);
  if (refused.length) {
    lines.push(
      '[hd8clock] ARM-ORDER GUARD REFUSED — at least one figure above depends on where in the plan ' +
        'it was measured, and may not be reported as measured. Repair the arm, not the guard: ' +
        refused.map((r) => r.id).join(', ')
    );
  }
  if (unverified.length) {
    lines.push(
      '[hd8clock] REFUSED — unverified operations (rf2-rr6do): a window whose value never reached ' +
        'the page is not a measurement of that page: ' +
        unverified.map((r) => `${r.id}: ${r.unverified} of ${r.writes}`).join(', ')
    );
  }
  if (overCeiling.length) {
    lines.push(
      `[hd8clock] REFUSED — the run's own reproducibility band exceeds seam.cjs's ceiling ` +
        `(rf2-ymi6j, rf2-rr6do) on: ` +
        overCeiling.map((r) => `${r.id} (${pct(r.band)})`).join(', ') +
        '. ctl-2x and floor are two arms in the SAME block whose true ratio is a property of the ' +
        'page, so a band that wide means the box could not reproduce identical work — no magnitude ' +
        'from those rows is reportable, whatever its margin.'
    );
  }
  if (ctlFailed.length) {
    lines.push(
      '[hd8clock] REFUSED — the positive control did not see the change its own arithmetic ' +
        'predicts (rf2-rr6do) on: ' +
        ctlFailed.map((r) => `${r.id} (measured ${Number(r.ctlMeasured).toFixed(4)}x)`).join(', ') +
        '. No MAGNITUDE from those rows is reportable.'
    );
  }

  const code = failed
    ? 1
    : refused.length
      ? 2
      : unverified.length
        ? 3
        : overCeiling.length
          ? 4
          : ctlFailed.length
            ? 5
            : 0;
  return { code, lines };
}

// ---------------------------------------------------------------------------
// Where a run's datasets may be written
// ---------------------------------------------------------------------------

// WHERE A RUN'S DATASETS MAY BE WRITTEN (rf2-2rtt6.31, the write-before-refuse
// ruling; census_clock_run.cjs / rf2-2rtt6.56 carries the same rule).
//
// `verdict` decides what may be QUOTED. This decides what may be WRITTEN, and
// it is a separate question this driver got wrong in the same direction its
// sibling did. The datasets were written under the CANONICAL filenames before
// the refusal was consulted, whatever shape the run had — so a run narrowed to
// one adapter (HD8CLOCK_ONLY), taken with `--no-build` against whatever bundle
// happened to be on disk, taken at an overridden depth, or one the verdict then
// REFUSED, silently replaced the published evidence the studio page cites.
// Nothing announced it: the write had already landed, and the nonzero exit
// arrived afterwards. rf2-rr6do repaired the exit path; this is the write path,
// the other half of the same fail-open.
//
// THE RULE: the canonical directory holds the PUBLISHED SHAPE and nothing
// else. Any narrowing, any override, any refusal routes to a sibling
// `.unpublished` directory, named on stdout with the reason. No completed
// measurement is ever discarded — it simply does not get the published names.
//
// An explicit HD8CLOCK_DATA_DIR is the operator naming their own destination.
// It is honoured as given, and it is never the canonical set.
//
// This is census's `destination` with ONE condition absent: census names a
// PARTIAL ROW SET because C56CLOCK_ROWS can narrow its rows. This driver's
// ROWS is a fixed pair with no such knob, so there is no partial-row shape to
// name and a `rowsOnly` that could only ever be null would be a dead reason.
// Every other condition, and the routing, is the same rule verbatim.
//
// Pure over a flat shape record, for the same reason `verdict` is: the write
// path is then checkable without a release build and a headless Chromium.
function destination(shape, code) {
  const s = shape || {};
  if (s.dataDirOverridden) {
    return { dir: s.dataDir, canonical: false, why: 'HD8CLOCK_DATA_DIR named this destination' };
  }
  const why = [];
  if (code !== 0) why.push(`the run's own verdict refused it (exit ${code})`);
  if (s.runsOnly) why.push(`a PARTIAL run set (HD8CLOCK_ONLY=${s.runsOnly})`);
  if (s.noBuild) why.push("--no-build (the bundle on disk is not known to be this tree's)");
  if (!s.depthPublished) why.push('an OVERRIDDEN design depth');
  if (!why.length) return { dir: s.dataDir, canonical: true, why: null };
  return { dir: `${s.dataDir}.unpublished`, canonical: false, why: why.join('; ') };
}

/**
 * The compact dataset for one run — the reduced quantities every statistic on
 * the studio page is a function of, so the page can be recomputed from the
 * tree.
 *
 * Lifted out of `drive` deliberately. Serialising a row means naming its
 * refusal fields (`guardRefuse`, `ceilingBreached`, …), and `drive` is held to
 * an invariant that nothing downstream of `verdict` may name one — the check
 * that stops a second exit path growing back (`clock_exit_path.test.cjs`).
 * The write now happens after the verdict, so the serialiser has to live
 * outside it. Recording is not deciding, and this is where that shows.
 */
function datasetFor(rows, meta) {
  return {
    bead: 'rf2-2rtt6.31',
    commit: meta.sha,
    blobs: meta.blobs,
    when: new Date().toISOString(),
    // Whether this file is the published evidence, recorded IN the file — a
    // dataset that travels out of its directory must still say what it is.
    // A consumer that finds no `canonical` field has not found a pass.
    canonical: meta.dest.canonical,
    notCanonicalWhy: meta.dest.why,
    design: { rounds: ROUNDS, blocks: BLOCKS, warmup: WARMUP, samples: SAMPLES, tolerance: TOLERANCE, controlSlack: CONTROL_SLACK, gateLine: GATE_LINE },
    clock: 'Performance.getMetrics raw TaskDuration, frame-settled (rAF + setTimeout), plumb-tared',
    door: 'page.evaluate -> HD8CLOCK.sample (every arm, plumb included)',
    node: process.version,
    rows: rows.map((r) => ({
      rowId: r.rowId,
      armIds: r.armIds,
      canon: r.canon,
      blocksTask: r.blocksTask,
      blocksNet: r.blocksNet.map((rd) => rd.map((b) => Object.fromEntries(Object.entries(b).map(([a, xs]) => [a, r4(p50(xs))])))),
      blocksInPage: r.blocksInPage.map((rd) => rd.map((b) => Object.fromEntries(Object.entries(b).map(([a, xs]) => [a, r4(p50(xs.filter(Number.isFinite)))])))),
      // THE CLOCK'S OWN GRAIN, in the run it governs — the sorted distinct
      // non-zero per-sample `TaskDuration` deltas this row observed, the
      // smallest of which is the finest interval the published clock
      // resolved here.
      //
      // rf2-dzus. `runRow` has always measured it and `report` has always
      // printed it; this function dropped it. So a dataset carried durations
      // with no record of what could be told apart from what, and "was this
      // measurable?" had to be answered from outside the file — from a
      // remembered constant, which is the failure `rf2-d2tzk` closed on the
      // in-page clock and `clock_run.cjs` had already closed here. This
      // driver took that driver's contract and did not inherit this part of
      // it. Persisting it backfills nothing: the grain travels with the NEXT
      // canonical run, not with this line.
      granularity: r.granularity,
      tally: r.tally,
      runtime: r.runtime,
      quiet: r.quiet,
      windowStart: r.windowStart,
      adjudication: {
        ctl: r.adjudication.ctl,
        cAdditive: r4(r.adjudication.cAdditive),
        band: Number.isFinite(r.adjudication.assessed.bandStats.band) ? r4(r.adjudication.assessed.bandStats.band) : null,
        ceilingBreached: r.adjudication.assessed.verdict.ceilingBreached,
        bars: r.adjudication.bars,
        verdicts: r.adjudication.verdicts,
        guardRefuse: r.adjudication.guardRefuse,
        plumb: r4(r.adjudication.plumb),
        floorTared: r4(r.adjudication.floorTared),
      },
    })),
  };
}

/** This process's shape, as `destination` reads it. */
const runShape = () => ({
  dataDir: DATA_DIR,
  dataDirOverridden: DATA_DIR_OVERRIDDEN,
  runsOnly: ONLY || null,
  noBuild: NO_BUILD,
  depthPublished: depthIsPublished(),
});

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function drive() {
  const gst = guard.selfTest();
  console.log(';; ==== ARM-ORDER GUARD SELF-TEST ====');
  for (const cse of gst.checks) console.log(`;;   ${cse.ok ? 'ok  ' : 'FAIL'}  ${cse.name}`);
  if (!gst.ok) {
    console.error('[hd8clock] the arm-order guard failed its own self-test; nothing was measured');
    return 1;
  }
  const sst = seamlib.selfTest();
  console.log(';; ==== SEAM/BAND SELF-TEST ====');
  for (const cse of sst.checks) console.log(`;;   ${cse.ok ? 'ok  ' : 'FAIL'}  ${cse.name}`);
  if (!sst.ok) {
    console.error('[hd8clock] seam.cjs failed its own self-test; nothing was measured');
    return 1;
  }

  const sha = revision();
  const bl = blobs();
  console.log(';; ==== HD8 CLOCK-OF-RECORD PROVENANCE ====');
  console.log(';;   bead        rf2-2rtt6.31 (HD-008 donor re-take; EP-0038)');
  console.log(`;;   commit      ${sha}`);
  for (const [f, h] of Object.entries(bl)) console.log(`;;   blob        ${h}  ${f}`);
  console.log(`;;   reproduce   node implementation/freehand/test/re_frame/bench/hicasso/hd8_clock_run.cjs`);
  console.log(`;;   build       shadow-cljs release ${BUILD_ID} (:advanced, goog.DEBUG false)`);
  console.log(`;;   node        ${process.version}`);
  console.log(
    `;;   design      ${ROUNDS} rounds x ${BLOCKS} blocks x (${WARMUP} warmup + ${SAMPLES} samples) per arm` +
      `${depthIsPublished() ? '' : '  *** OVERRIDDEN — NOT THE PUBLISHED SHAPE ***'}`
  );
  console.log(`;;   runs        ${RUNS.map((r) => r.id).join(', ')}${ONLY ? `  (HD8CLOCK_ONLY=${ONLY} — PARTIAL, not the published shape)` : ''}`);
  console.log(`;;   guard tol   ${TOLERANCE} on raw TaskDuration (HD-008's stated mount choice)`);
  console.log(`;;   band ceil   ${(seamlib.BAND_CEILING * 100).toFixed(0)}% on raw TaskDuration (rf2-ymi6j)`);
  console.log(';; ==== PREDICTIONS, REGISTERED BEFORE ANY CLOCK ====');
  console.log(';;   P1  ctl-2x reads BELOW 2.00x on every mount row — toward rf2-jcm3p\'s 1.8173x —');
  console.log(';;       and inside the strict +/-25% band unless block scatter is wide.');
  console.log(';;   P2  DIRECTION ONLY: the donor and uix columns sit BELOW the published in-page');
  console.log(';;       rows\' implied position — .13/.25 removed numerator work. No magnitude predicted.');
  console.log(';;   P3  uix / reagent (reagent run) reads NEARER PARITY than the pre-landing rows');
  console.log(';;       implied (~1.12–1.17 on the in-page clock).');

  if (!NO_BUILD) build();
  const server = serve();
  const results = [];
  let failed = null;
  try {
    const { chromium } = require('playwright');
    for (const runDef of RUNS) {
      console.log(`\n;; ======== RUN ${runDef.id} — ${runDef.why} ========`);
      const windowStart = new Date().toISOString();
      console.log(`;; measurement window OPEN  ${windowStart}`);
      const browser = await chromium.launch();
      try {
        for (const rowId of ROWS) {
          const q = quietGate(`${runDef.id}/${rowId}`);
          if (!q.ok) {
            failed = `the box would not go quiet before ${runDef.id}/${rowId} — the row was NOT taken (a run on a loud box is discarded, not reported)`;
            break;
          }
          const out = await runRow(browser, runDef, rowId);
          const adj = report(out);
          results.push({ ...out, adjudication: adj, quiet: q, windowStart });
        }
      } finally {
        await browser.close();
        console.log(`;; measurement window CLOSE ${new Date().toISOString()}  (${runDef.id})`);
      }
      if (failed) break;
    }
  } catch (e) {
    failed = e.message;
  } finally {
    server.close();
  }

  // The verdict is computed BEFORE the datasets are written, because where
  // they may be written depends on it (see `destination`).
  const v = verdict(summarise(failed, results));
  const dest = destination(runShape(), v.code);

  // compact datasets — the reduced quantities every statistic above is a
  // function of, per run, so the page can be recomputed from the tree.
  if (results.length && !failed) {
    if (dest.canonical) {
      console.log(';; datasets CANONICAL — the published shape, all gates passed');
    } else {
      console.log(`;; datasets NOT CANONICAL — ${dest.why}`);
      console.log(';;          These are working datasets. They are not the published evidence and');
      console.log(';;          may not be cited as it.');
    }
    fs.mkdirSync(dest.dir, { recursive: true });
    for (const runDef of RUNS) {
      const rows = results.filter((r) => r.runId === runDef.id);
      if (!rows.length) continue;
      const data = datasetFor(rows, { sha, blobs: bl, dest });
      const f = path.join(dest.dir, `${runDef.id}.json`);
      fs.writeFileSync(f, JSON.stringify(data));
      console.log(`;; dataset  ${f}${dest.canonical ? '' : '   (NOT the published evidence)'}`);
    }
  }

  console.log('\n;; ==== THE RULING IS NOT THIS INSTRUMENT\'S TO ISSUE ====');
  console.log(';;   The verdict against the amended mount gate is the bead\'s to state and the');
  console.log(';;   operator\'s to overturn (rf2-2rtt6.1). This driver prints measurements and');
  console.log(';;   per-pair adjudications against the recorded line; nothing here amends the bar.');

  for (const line of v.lines) console.error(line);
  if (v.code === 0) {
    console.error('[hd8clock] ok — measured, and no arm reads differently for its position in the plan');
  }
  return v.code;
}

module.exports = { summarise, verdict, destination };

if (require.main === module) {
  drive().then((code) => {
    if (code !== 0) process.exit(code);
  });
}
