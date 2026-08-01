#!/usr/bin/env node
// THE CANDIDATE'S CLOCK ROWS — driver (rf2-0qj9w).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs
//
// The programme has no wall-clock measurement of its own candidate. Hook
// count and per-read retained heap are measured; mount, bulk K=100/300,
// narrow and per-keystroke are not, and every clock figure it has
// published is about the DONORS. This driver takes those rows, with the
// donors in the same runs on the same instrument, because validation.md
// is explicit that a candidate is judged against the donor row taken on
// its own instrument and that a margin under 5% is instrument-limited
// rather than cleared.
//
// ## WHY THIS IS NOT `performance.now()` AROUND A `flushSync`
//
// Every other clock entry in this lane wraps an in-page span around the
// substrate's own call. That span ends when the JavaScript returns —
// BEFORE the style recalculation, layout, pre-paint and paint the
// mutation causes. The error would be tolerable if it were common-mode.
// It is not: how much work a substrate leaves for the browser after its
// stack unwinds is precisely what differs between these arms, and
// Hicasso's whole design concerns WHEN work happens, so an in-page window
// systematically flatters whichever arm defers most.
//
// So the clock here is CHROME'S OWN. `Performance.getMetrics` over the
// DevTools protocol reports the renderer's cumulative counters, and the
// delta across one operation — taken after the page has been made to
// produce the frame that follows it — is main-thread task time INCLUDING
// style, layout and paint recording.
//
// Two properties of that choice are worth stating because they are the
// reason for it:
//
//   * `TaskDuration` is a PROTOCOL value, not a web-exposed one, so it
//     does not carry the Spectre clamp. Chrome restricts
//     `performance.now()` to 100 µs from version 91 across platforms
//     (5 µs only under cross-origin isolation) — verified against
//     Chrome's own "Aligning timers with cross origin isolation
//     restrictions" and MDN's `Performance.now` security section, both
//     read on 2026-08-01. The page here is NOT cross-origin isolated, so
//     its in-page span carries the 100 µs quantum and this one does not.
//     The observed granularity of the counters is measured and reported
//     rather than assumed.
//   * It does NOT capture off-main-thread rasterisation or compositing.
//     Everything below is main-thread cost. That is stated on every row
//     rather than implied.
//
// The in-page span is taken anyway, on the same operation in the same
// sample, and published beside the frame-inclusive one. The gap between
// them measures the error the other instrument makes, per arm.
//
// ## PER-KEYSTROKE IS EVENT TIMING, AND THE KEY IS A REAL KEY
//
// `PerformanceEventTiming` decomposes real input latency into input
// delay, processing time and time to next paint — it CAPTURES THE PAINT,
// which is strictly better than asserting on the line after
// `dispatchEvent` returns. Two limits of it are load-bearing and are
// reported rather than papered over: `duration` is rounded to the nearest
// 8 ms, and the minimum `durationThreshold` an observer may ask for is
// 16 ms, so an interaction faster than that produces NO `event` entry at
// all. Both verified against MDN's `PerformanceEventTiming`, read
// 2026-08-01. A row whose interactions all land under the reporting floor
// is reported as exactly that.
//
// The driver sends the key through the protocol's input domain
// (Playwright's `keyboard.press`), because a JavaScript-dispatched event
// is not a user interaction and Event Timing reports user interactions.
//
// ## EVERY ROW SAYS WHICH REGIME PRODUCED IT (rf2-cvvb7)
//
// The first pass of this driver printed a cross-segment floor seam and hoped
// it cancelled, and `the-candidates-clock.md` §6 refused three rows partly
// because that seam once read 34% where a later run read 3.8%. A run whose
// seam swings like that cannot tell a reader which regime it was taken in,
// so `seam.cjs` now measures the regime and every row carries it:
//
//   * the seam, WITH THE NULL of its own statistic — segments relabelled
//     within each round — because a max-over-min of three noisy block
//     medians has a long right tail with nothing to attribute it to, and a
//     seam published bare invites a reader to treat 6% as a finding;
//   * where the floor's variation lives, decomposed orthogonally into
//     SEGMENT, ROUND and POSITION-IN-ROUND. A nineteen-run load ladder put
//     it on the round, not the segment;
//   * THE BAND — how much of a block's perturbation survives dividing by
//     that block's own floor, measured on `ctl-2x / floor`, two arms in one
//     block whose true ratio is a property of the page. A magnitude whose
//     margin is inside the band is INSTRUMENT-LIMITED and says so on the
//     row.
//
// `seam.cjs`'s header carries the ladder and the arithmetic. The short of it
// is that the perturbation a busy box applies is MULTIPLICATIVE and cancels
// exactly, the seam does not track load at all, and the band is the number
// that was actually wanted.
//
// ## EXIT CODES — the guard owns 2, and it is not the arm's to move
//
//   0  measured, guard clean, controls passed
//   1  the run failed (build, page error, a fatal the page recorded, a
//      positive control that did not see what its own arithmetic predicts,
//      an unverified write, a teardown that did not tear down)
//   2  THE ARM-ORDER GUARD REFUSED. A figure whose value depends on where
//      in the plan it was measured is not a figure. The repair is the ARM
//      — more warm-up, fewer arms per page, a longer window — never the
//      guard's tolerance.
//
// A Chromium `pageerror` is FATAL: a benchmark that threw and kept going
// publishes a precise number for a page that is not the page under test.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
const guard = require('../../freehand/bench/order_guard.cjs');
const seamlib = require('./seam.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.HCLOCK_OUT_DIR || 'out/hicasso-clock';
const INIT_FN = 're-frame.bench.hicasso.clock-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.HCLOCK_PORT || 8137);

const ROUNDS = Number(process.env.HCLOCK_ROUNDS || 6);
const WARMUP = Number(process.env.HCLOCK_WARMUP || 4);
const SAMPLES = Number(process.env.HCLOCK_SAMPLES || 10);
const NO_BUILD = process.argv.includes('--no-build');

// Where to write the run's RAW per-sample readings, if anywhere. The seam
// study (rf2-cvvb7) had to compare eleven runs against each other, and a
// console line is not a dataset: the segment decomposition below is
// recomputed from this file rather than scraped back out of the log.
const JSON_OUT = (process.env.HCLOCK_JSON || '').trim();

// The lane's slack, unchanged and for its reason: the claim a clock
// control certifies is THE INSTRUMENT HAS SIGNAL, not THE MODEL IS EXACT.
// A top-down React re-render is not perfectly linear in element count —
// the root, the commit and the diff walk do not double — so 2.00 ± 5%
// would fail an instrument that is working.
const CONTROL_SLACK = 0.25;

// The keystroke control burns this many milliseconds inside its handler
// (`clock-views/kb-floor`), and the prediction below is written against
// it before the run.
const CTL_BUSY_MS = 50;

// Subtract the tare arm's reading from every figure. On by default; the
// switch exists so a reader can reproduce the uncorrected run this
// instrument's first pass published (`HCLOCK_TARE=off`), not so a run can
// choose whichever answer it prefers — every table says which it is.
const TARE = (process.env.HCLOCK_TARE || 'on') !== 'off';

const ALL_ROWS = ['M1', 'bulk300', 'bulk100', 'narrow', 'keystroke'];
const ONLY = (process.env.HCLOCK_ONLY || '').trim();
const ROWS = ONLY ? ALL_ROWS.filter((r) => ONLY.split(',').includes(r)) : ALL_ROWS;
if (ROWS.length === 0) {
  console.error(`[clock] HCLOCK_ONLY=${ONLY} selects no row; known ids: ${ALL_ROWS.join(', ')}`);
  process.exit(1);
}

const SEGMENTS = ['reagent-subs', 'uix-subs', 'hicasso'];
const FLOOR = 'floor';
const PLUMB = 'plumb';

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` + `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[clock] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[clock] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(process.execPath, [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE], {
    cwd: IMPL,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (r.status !== 0) {
    console.error(`[clock] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso clock</title></head>' +
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
// Statistics — ranges, never a bare mean
// ---------------------------------------------------------------------------

const r4 = (x) => Math.round(x * 10000) / 10000;

function p50(xs) {
  const v = [...xs].sort((a, b) => a - b);
  if (v.length === 0) return NaN;
  return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
}

function summarise(xs) {
  const v = [...xs].sort((a, b) => a - b);
  return { n: v.length, min: v[0], p50: p50(v), max: v[v.length - 1] };
}

function band(xs) {
  return { mean: r4(xs.reduce((a, b) => a + b, 0) / xs.length), min: r4(Math.min(...xs)), max: r4(Math.max(...xs)) };
}

/**
 * A positive control is a STATED prediction against a measured range, and
 * the STRICT rule is used: every round must sit inside the band.
 * `lane/control-verdict`'s overlap rule is documented in its own docstring
 * as the lane's known defect (rf2-egdaq) — a control whose worst round is
 * wrong has caught something, and letting a good round vouch for a bad one
 * is how an instrument stops being one. Nothing here is already published
 * under the weaker rule, so there is nothing to re-adjudicate.
 */
function controlVerdict(predicted, perRound, slack) {
  const lo = predicted * (1 - slack);
  const hi = predicted * (1 + slack);
  const b = band(perRound);
  const ok = perRound.every((x) => x >= lo && x <= hi);
  return {
    predicted: r4(predicted),
    band: [r4(lo), r4(hi)],
    measured: b,
    perRound: perRound.map(r4),
    ok,
    rule: 'strict — EVERY round inside the band',
  };
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
  // The counters are seconds; milliseconds is what every other row in this
  // lane is stated in.
  const task = d.TaskDuration * 1000;
  const devtools = d.DevToolsCommandDuration * 1000;
  return {
    // PRIMARY. The driver's own protocol traffic is subtracted because it
    // is instrument cost rather than page cost, and Chromium's own
    // accounting treats it that way: `inspector_performance_agent.cc`
    // derives `TaskOtherDuration` by subtracting script, V8-compile, style,
    // layout AND `DevToolsCommandDuration` from `TaskDuration`, which is
    // only coherent if each is a subset of it. Both are reported.
    //
    // Stated from SOURCE deliberately (rf2-8nqsl): the CDP documents no
    // metric names at all — `Performance.Metric` is a bare `{name, value}`
    // and `getMetrics` is "Retrieve current values of run-time metrics" —
    // so nothing about `TaskDuration` or `DevToolsCommandDuration` is a
    // documented contract, and DevTools' own front-end
    // (`PerformanceMetricsModel.ts`) does NOT subtract the DevTools term.
    // The subtraction is a defensible inference from Chromium's accounting
    // model, not an established practice, and it is named as one.
    taskNet: task - devtools,
    task,
    devtools,
    script: d.ScriptDuration * 1000,
    layout: d.LayoutDuration * 1000,
    style: d.RecalcStyleDuration * 1000,
    layoutCount: d.LayoutCount,
    styleCount: d.RecalcStyleCount,
  };
}

// The Event Timing observer. Installed from the driver rather than from
// the page program: it is instrument code, it belongs to whoever is doing
// the measuring, and `addInitScript` puts it in before any page script
// runs so `buffered: true` has something to buffer.
const EVENT_TIMING_INIT = `
  window.__ET = [];
  window.__ETARM = null;
  window.__ETUNATTRIBUTED = 0;
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        if (!window.__ETARM) { window.__ETUNATTRIBUTED++; }
        window.__ET.push({
          arm: window.__ETARM,
          name: e.name,
          startTime: e.startTime,
          processingStart: e.processingStart,
          processingEnd: e.processingEnd,
          duration: e.duration,
          interactionId: e.interactionId,
        });
      }
    }).observe({ type: 'event', durationThreshold: 16, buffered: true });
  } catch (err) { window.__ETERROR = String(err); }
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        window.__ET.push({
          arm: window.__ETARM, name: 'first-input:' + e.name,
          startTime: e.startTime, processingStart: e.processingStart,
          processingEnd: e.processingEnd, duration: e.duration, interactionId: e.interactionId,
        });
      }
    }).observe({ type: 'first-input', buffered: true });
  } catch (err) { window.__ETERROR2 = String(err); }
`;

// ---------------------------------------------------------------------------
// One row
// ---------------------------------------------------------------------------

async function runRow(browser, rowId) {
  // A FRESH PAGE per row, not a fresh navigation in the same one: this
  // lane's recorded fault is a page that gets slower the longer it runs,
  // and a reused page carries whatever caused that across the row boundary.
  const page = await browser.newPage();
  await page.addInitScript(EVENT_TIMING_INIT);
  const pageErrors = [];
  page.on('pageerror', (e) => {
    pageErrors.push(e.message);
    console.error('[clock] PAGE ERROR:', e.message);
  });
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[clock]')) console.log(t);
  });

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');

  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the wait for window.HCLOCK_READY',
  });
  await page.waitForFunction('window.HCLOCK_READY === true', null, { timeout: 120000 });

  const isKeystroke = rowId === 'keystroke';
  const samples = []; // for the arm-order guard
  const rounds = []; // [{seg: {arm: [ms...]}}]
  const inPageRounds = [];
  const decomposition = {}; // "seg/arm" -> accumulated style/layout/counts
  const canon = {}; // "seg/arm" -> {hash, bytes, control}
  const eventTiming = []; // raw PerformanceEventTiming records
  let unattributedET = 0;
  let position = 0;
  let previous = null;
  const granularity = new Set();

  const bump = (key, d) => {
    const acc = (decomposition[key] ||= {
      n: 0, taskNet: 0, script: 0, style: 0, layout: 0, layoutCount: 0, styleCount: 0, inPage: 0,
    });
    acc.n += 1;
    acc.taskNet += d.taskNet;
    acc.script += d.script;
    acc.style += d.style;
    acc.layout += d.layout;
    acc.layoutCount += d.layoutCount;
    acc.styleCount += d.styleCount;
  };

  for (let round = 0; round < ROUNDS; round++) {
    // The segment order ROTATES with the round, so no segment is
    // permanently first and a segment effect cannot hide inside a temporal
    // one. Three segments give three orders; six rounds visit each twice.
    const segOrder = SEGMENTS.map((_, i) => SEGMENTS[(i + round) % SEGMENTS.length]);
    const perSeg = {};
    const perSegInPage = {};

    for (const seg of segOrder) {
      await page.evaluate((s) => window.HCLOCK.enterSegment(s), seg);
      const plan = await page.evaluate(([r, s]) => window.HCLOCK.plan(r, s), [rowId, seg]);
      const armIds = plan.map((a) => a.id);

      if (round === 0) {
        for (const a of armIds) {
          const c = await page.evaluate(([r, arm]) => window.HCLOCK.canon(r, arm), [rowId, a]);
          canon[`${seg}/${a}`] = c;
        }
      }

      for (const a of armIds) await page.evaluate(([r, arm]) => window.HCLOCK.prepare(r, arm), [rowId, a]);

      const typed = {}; // per-arm accumulated field value, keystroke row only
      for (const a of armIds) typed[a] = '';

      const acc = {};
      const accInPage = {};
      for (const a of armIds) {
        acc[a] = [];
        accInPage[a] = [];
      }

      for (let s = 0; s < WARMUP + SAMPLES; s++) {
        for (const j of guard.schedule(armIds.length, s)) {
          const armId = armIds[j];
          let inPageMs = NaN;
          let ok = true;

          // Only the arm under test is on the page while it is measured.
          // Outside the window, and followed by a settle, so the layout of
          // the arm just shown is complete before the clock starts.
          if (rowId !== 'M1') await page.evaluate(([r, arm]) => window.HCLOCK.solo(r, arm), [rowId, armId]);

          const m0 = await readMetrics(cdp);
          if (armId === PLUMB) {
            // The tare's operation is the settle and nothing else. It is
            // driven through the SAME two evaluates a real sample costs on
            // this row, because what it is measuring is exactly those.
            if (isKeystroke) {
              await page.evaluate(() => window.HCLOCK.settle());
              await page.evaluate(() => window.HCLOCK.settle());
            } else {
              await page.evaluate(([r, arm]) => window.HCLOCK.sample(r, arm), [rowId, armId]);
            }
          } else if (isKeystroke) {
            await page.evaluate(
              ([arm]) => {
                window.__ETARM = arm;
                return window.HCLOCK.focusDraft(arm);
              },
              [armId]
            );
            typed[armId] += 'a';
            await page.keyboard.press('a');
            const res = await page.evaluate(
              ([arm, exp]) => window.HCLOCK.settleVerify(arm, exp),
              [armId, typed[armId]]
            );
            ok = res.ok;
          } else {
            const res = await page.evaluate(([r, arm]) => window.HCLOCK.sample(r, arm), [rowId, armId]);
            inPageMs = res.inPageMs;
            ok = res.ok;
          }
          const m1 = await readMetrics(cdp);
          const d = deltaOf(m0, m1);
          if (d.taskNet > 0) granularity.add(d.taskNet);

          // AFTER the counters. A mount row's arm is left standing by
          // `sample`, and unmounting 300 or 600 boundaries is real work
          // that a mount row must not be charged for.
          if (rowId === 'M1') {
            const reaped = await page.evaluate((r) => window.HCLOCK.reap(r), rowId);
            ok = ok && reaped.ok;
          }

          if (isKeystroke) {
            // A second settle before draining: Event Timing entries reach
            // the observer in a task AFTER the frame that painted them.
            await page.evaluate(() => window.HCLOCK.settle());
            const drained = await page.evaluate(() => {
              const es = window.__ET;
              window.__ET = [];
              const u = window.__ETUNATTRIBUTED;
              window.__ETUNATTRIBUTED = 0;
              window.__ETARM = null;
              return { es, u };
            });
            unattributedET += drained.u;
            for (const e of drained.es) {
              eventTiming.push({ ...e, seg, round, sampleIndex: s, warm: s >= WARMUP });
            }
          }

          if (s >= WARMUP) {
            const key = `${seg}/${armId}`;
            acc[armId].push(d.taskNet);
            if (Number.isFinite(inPageMs)) accInPage[armId].push(inPageMs);
            bump(key, d);
            samples.push({ arm: key, value: d.taskNet, predecessor: previous, position });
            position += 1;
          }
          previous = `${seg}/${armId}`;
          if (!ok) {
            // Not fatal here — the tally is adjudicated at the end of the
            // row, where the count is what makes it reportable or not.
          }
        }
      }

      for (const a of armIds) await page.evaluate(([r, arm]) => window.HCLOCK.finish(r, arm), [rowId, a]);
      const td = await page.evaluate(() => window.HCLOCK.teardownCheck());
      if (td.length > 0) {
        await page.close();
        throw new Error(`teardown FAILED in segment ${seg} round ${round}: ${td.join(', ')}`);
      }

      perSeg[seg] = acc;
      perSegInPage[seg] = accInPage;
    }
    rounds.push(perSeg);
    inPageRounds.push(perSegInPage);
  }

  const tally = await page.evaluate(() => window.HCLOCK.tally());
  const residue = await page.evaluate(() => window.HCLOCK.residue());
  const runtime = await page.evaluate(() => window.HCLOCK.runtime());
  const etError = await page.evaluate(() => window.__ETERROR || null);
  await page.close();

  return {
    rowId, samples, rounds, inPageRounds, decomposition, canon, tally, residue, runtime,
    eventTiming, unattributedET, etError, pageErrors,
    granularity: [...granularity].sort((a, b) => a - b),
  };
}

// ---------------------------------------------------------------------------
// Adjudication
// ---------------------------------------------------------------------------

/**
 * One arm's page cost in one round of one segment, TARED.
 *
 * The tare is `plumb`'s p50 in the SAME round of the SAME segment, so it
 * is never carried across a seam. Correction is subtraction and has no
 * free parameter: the prediction that it restores the doubling control to
 * 2.00x is registered before the run and is falsifiable — an overshoot
 * would say the model is wrong, and would be reported as saying it.
 */
function tared(rounds, seg, arm, round) {
  const t = TARE ? p50(rounds[round][seg][PLUMB]) : 0;
  return p50(rounds[round][seg][arm]) - t;
}

/** Per-round ratio of `arm` to the floor measured in THAT round of THAT segment. */
function ratioToFloor(rounds, seg, arm) {
  return rounds.map((_, i) => tared(rounds, seg, arm, i) / tared(rounds, seg, FLOOR, i));
}

function rawRatioToFloor(rounds, seg, arm) {
  return rounds.map((r) => p50(r[seg][arm]) / p50(r[seg][FLOOR]));
}

/** The bar arithmetic: two floor-normalised ratios, one against the other. */
function crossSegment(rounds, numSeg, numArm, denSeg, denArm, raw) {
  const f = raw ? rawRatioToFloor : ratioToFloor;
  const num = f(rounds, numSeg, numArm);
  const den = f(rounds, denSeg, denArm);
  const per = num.map((x, i) => x / den[i]);
  const b = band(per);
  return { ...b, perRound: per.map(r4), straddles1: b.min <= 1.0 && b.max >= 1.0 };
}

function report(out) {
  const {
    rowId, samples, rounds, inPageRounds, decomposition, canon, tally, residue, runtime,
    eventTiming, unattributedET, etError, granularity,
  } = out;

  console.log(`;; ==== ROW ${rowId} ====`);
  console.log(`;; runtime  ${runtime}`);
  console.log(`;; residue  ${residue}`);
  console.log(`;; writes   ${tally.unverified} unverified of ${tally.writes}`);
  console.log(
    `;; clock    Performance.getMetrics TaskDuration less DevToolsCommandDuration, ` +
      `frame-settled (rAF + setTimeout) — main thread only, no raster/composite`
  );
  console.log(
    `;; grain    smallest non-zero per-sample delta ${granularity.length ? granularity[0].toFixed(6) : 'n/a'} ms ` +
      `over ${granularity.length} distinct values ` +
      `(the page is NOT cross-origin isolated; performance.now() here carries a 100 µs quantum)`
  );

  // --- the fairness gate ----------------------------------------------------
  const nonControl = Object.entries(canon).filter(([, c]) => !c.control);
  const refHash = nonControl.length ? nonControl[0][1].hash : null;
  const disagree = nonControl.filter(([, c]) => c.hash !== refHash).map(([k]) => k);
  console.log(
    `;; parity   ${nonControl.length} non-control arms across ${SEGMENTS.length} segments, ` +
      `canonical DOM ${disagree.length === 0 ? 'IDENTICAL' : 'DISAGREES: ' + disagree.join(', ')} ` +
      `(${nonControl.length ? nonControl[0][1].bytes : 0} bytes)`
  );

  // --- the tare -------------------------------------------------------------
  const armsOf = (seg) => Object.keys(rounds[0][seg]);
  const plumbAll = summarise(rounds.flatMap((r) => SEGMENTS.flatMap((s) => r[s][PLUMB])));
  const plumbBySeg = SEGMENTS.map((s) => `${s} ${p50(rounds.flatMap((r) => r[s][PLUMB])).toFixed(3)}`);
  console.log(
    `;; tare     plumb p50 ${plumbAll.p50.toFixed(4)} ms [${plumbAll.min.toFixed(4)} – ${plumbAll.max.toFixed(4)}] ` +
      `(${plumbBySeg.join(', ')}) — ${TARE ? 'SUBTRACTED from every figure below' : 'NOT subtracted (HCLOCK_TARE=off)'}`
  );

  // --- the bar row ----------------------------------------------------------
  // Computed HERE and printed below, because the seam block adjudicates it:
  // a magnitude is reportable only against the band the same run measured,
  // and the band has to be in hand before the seam block can say so.
  const bar = {};
  const barMeans = {};
  for (const den of ['reagent-subs', 'uix-subs']) {
    const v = crossSegment(rounds, 'hicasso', 'hicasso', den, den, false);
    const rv = crossSegment(rounds, 'hicasso', 'hicasso', den, den, true);
    bar[den] = { tared: v, untared: rv };
    barMeans[`hicasso / ${den}`] = v.mean;
  }

  // --- the floor seam, its null, and the band a magnitude must clear ---------
  //
  // rf2-cvvb7 measured what this seam is and what it does. A nineteen-run
  // load ladder — 0, 2, 4, 8, 12 and 20 competing busy cores on a 24-core
  // box — moved the absolute floor by 80% and left the seam unmoved
  // (0.1–16.4%, no trend), showed the seam is not attributable to the
  // segment under an exact within-round relabelling null, and showed the
  // perturbation is MULTIPLICATIVE, so floor-normalisation cancels it
  // exactly. What a bar row must clear is not the seam; it is the part of a
  // block's perturbation that survives dividing by that block's own floor,
  // and `seam.cjs` measures that on `ctl-2x / floor`.
  const floorBlocks = rounds.map((r) => SEGMENTS.map((s) => r[s][FLOOR]));
  const floorCells = rounds.map((_, i) => SEGMENTS.map((s) => tared(rounds, s, FLOOR, i)));
  const hasProportionalControl = rowId !== 'keystroke';
  const fixedCells = hasProportionalControl
    ? rounds.map((_, i) => SEGMENTS.map((s) => tared(rounds, s, 'ctl-2x', i)))
    : null;
  const assessed = seamlib.assess({
    floorBlocks,
    floorCells,
    fixedCells,
    bars: barMeans,
    noFixedPairWhy:
      "this row's control burns a fixed 50 ms rather than doubling the page, so control/floor " +
      'reads (F+50)/F and moves with F — not a pair whose true ratio is a property of the page',
  });
  for (const line of seamlib.format(assessed, SEGMENTS)) console.log(line);
  const seam = {
    floorBySeg: assessed.seam.bySeg.map(r4),
    pooledSpread: r4(assessed.seam.spread),
    null: { q50: r4(assessed.null.q50), q95: r4(assessed.null.q95), q99: r4(assessed.null.q99), p: assessed.null.p },
    effects: {
      segment: r4(assessed.effects.segment),
      round: r4(assessed.effects.round),
      position: r4(assessed.effects.position),
      balanced: assessed.effects.balanced,
    },
    band: Number.isFinite(assessed.bandStats.band) ? r4(assessed.bandStats.band) : null,
    verdict: assessed.verdict,
  };

  // --- the rows -------------------------------------------------------------
  console.log(`;; ---- per-arm, ratio to the floor measured in that round of that segment ----`);
  for (const seg of SEGMENTS) {
    for (const arm of armsOf(seg)) {
      if (arm === FLOOR || arm === PLUMB) continue;
      const per = ratioToFloor(rounds, seg, arm);
      const b = band(per);
      const rb = band(rawRatioToFloor(rounds, seg, arm));
      console.log(
        `;;   ${(seg + '/' + arm).padEnd(28)} ${b.mean.toFixed(4)}x floor ` +
          `[${b.min.toFixed(4)} – ${b.max.toFixed(4)}]  n=${per.length} rounds   (untared ${rb.mean.toFixed(4)}x)`
      );
    }
    const fl = summarise(rounds.flatMap((r) => r[seg][FLOOR]));
    console.log(
      `;;   ${(seg + '/floor').padEnd(28)} ABSOLUTE p50 ${fl.p50.toFixed(4)} ms ` +
        `[${fl.min.toFixed(4)} – ${fl.max.toFixed(4)}], tared ${(fl.p50 - plumbAll.p50).toFixed(4)} ms`
    );
  }

  // --- the bar row ----------------------------------------------------------
  console.log(`;; ---- THE BAR: candidate against each donor, both floor-normalised ----`);
  for (const den of ['reagent-subs', 'uix-subs']) {
    const { tared: v, untared: rv } = bar[den];
    const adj = assessed.verdict.rows[`hicasso / ${den}`];
    console.log(
      `;;   hicasso / ${den.padEnd(14)} ${v.mean.toFixed(4)}x [${v.min.toFixed(4)} – ${v.max.toFixed(4)}]` +
        `   (untared ${rv.mean.toFixed(4)}x [${rv.min.toFixed(4)} – ${rv.max.toFixed(4)}])` +
        (v.straddles1 ? '   — RANGE STRADDLES 1.0, indistinguishable at this n' : '')
    );
    console.log(`;;     ${adj.unadjudicated ? 'UNADJ  ' : adj.clear ? 'CLEARS ' : 'LIMITED'} ${adj.why}`);
  }

  // --- the two instruments, side by side ------------------------------------
  if (inPageRounds[0] && Object.keys(inPageRounds[0][SEGMENTS[0]][FLOOR] || {}).length !== 0) {
    console.log(`;; ---- the SAME samples, read on the in-page performance.now() window ----`);
    for (const seg of SEGMENTS) {
      for (const arm of armsOf(seg)) {
        if (arm === FLOOR || arm === PLUMB) continue;
        const per = inPageRounds.map((r) => p50(r[seg][arm]) / p50(r[seg][FLOOR]));
        if (!per.every(Number.isFinite)) continue;
        const b = band(per);
        const fi = band(ratioToFloor(rounds, seg, arm));
        console.log(
          `;;   ${(seg + '/' + arm).padEnd(28)} in-page ${b.mean.toFixed(4)}x  vs  ` +
            `frame-inclusive ${fi.mean.toFixed(4)}x   (in-page reads ` +
            `${(((b.mean - fi.mean) / fi.mean) * 100).toFixed(1)}% differently)`
        );
      }
    }
  }

  // --- where the time goes --------------------------------------------------
  console.log(`;; ---- decomposition, mean ms per sample ----`);
  for (const [k, a] of Object.entries(decomposition)) {
    console.log(
      `;;   ${k.padEnd(28)} task ${(a.taskNet / a.n).toFixed(4)}  script ${(a.script / a.n).toFixed(4)}  ` +
        `style ${(a.style / a.n).toFixed(4)}  layout ${(a.layout / a.n).toFixed(4)}  ` +
        `layouts/sample ${(a.layoutCount / a.n).toFixed(2)}`
    );
  }

  // --- event timing ---------------------------------------------------------
  let etVerdict = null;
  if (rowId === 'keystroke') {
    console.log(`;; ---- Event Timing (paint-inclusive; duration rounded to 8 ms, floor 16 ms) ----`);
    if (etError) console.log(`;;   observer error: ${etError}`);
    const warm = eventTiming.filter((e) => e.warm && !e.name.startsWith('first-input:'));
    const totalKeys = ROUNDS * SEGMENTS.length * SAMPLES;
    console.log(
      `;;   ${warm.length} reported ENTRIES from ${totalKeys} measured keystrokes ` +
        `(one keypress raises several: keydown, beforeinput, input, keyup, …); ` +
        `${unattributedET} arrived with no arm in flight`
    );
    // ONE INTERACTION, not one event. INP's own definition: the events a
    // keypress raises share an `interactionId`, and the latency of the
    // interaction is the LONGEST of them — reporting the entries
    // individually multiplies the sample count by the event count and says
    // nothing extra, because every entry of one keypress ends at the same
    // paint.
    const byArm = {};
    const inter = new Map();
    for (const e of warm) {
      const key = e.arm ? `${e.seg}/${e.arm}` : `${e.seg}/<unattributed>`;
      const id = `${key}#${e.round}#${e.sampleIndex}#${e.interactionId || 0}`;
      const cur = inter.get(id);
      if (!cur || e.duration > cur.duration) inter.set(id, { key, ...e });
    }
    for (const v of inter.values()) (byArm[v.key] ||= []).push(v);
    const names = {};
    for (const e of warm) names[e.name] = (names[e.name] || 0) + 1;
    console.log(`;;   event names seen: ${Object.entries(names).map(([n, c]) => `${n}x${c}`).join(', ')}`);
    for (const [k, es] of Object.entries(byArm)) {
      const dur = summarise(es.map((e) => e.duration));
      const proc = summarise(es.map((e) => e.processingEnd - e.processingStart));
      const delay = summarise(es.map((e) => e.processingStart - e.startTime));
      console.log(
        `;;   ${k.padEnd(28)} interactions=${String(dur.n).padStart(3)}  duration p50 ${dur.p50.toFixed(1)} ms ` +
          `[${dur.min.toFixed(1)} – ${dur.max.toFixed(1)}]  processing p50 ${proc.p50.toFixed(3)} ms  ` +
          `input-delay p50 ${delay.p50.toFixed(3)} ms`
      );
    }
    for (const seg of SEGMENTS) {
      for (const arm of armsOf(seg)) {
        if (byArm[`${seg}/${arm}`] || arm === PLUMB) continue;
        console.log(
          `;;   ${(seg + '/' + arm).padEnd(28)} NO ENTRY — every interaction landed under the ` +
            `16 ms reporting floor, which is a result and not a gap`
        );
      }
    }
    // THE PREDICTED CONTROL for this instrument.
    const ctl = Object.entries(byArm)
      .filter(([k]) => k.endsWith('/ctl-50ms'))
      .flatMap(([, es]) => es);
    const sawIt = ctl.length > 0 && p50(ctl.map((e) => e.duration)) >= CTL_BUSY_MS - 2;
    etVerdict = {
      predicted: `ctl-50ms produces Event Timing interactions whose duration p50 is >= ${CTL_BUSY_MS - 2} ms`,
      measured: ctl.length ? `n=${ctl.length}, p50 ${p50(ctl.map((e) => e.duration)).toFixed(1)} ms` : 'no entries',
      ok: sawIt,
    };
    console.log(
      `;;   CONTROL  ${etVerdict.ok ? 'PASS' : 'FAIL'} — predicted ${etVerdict.predicted}; measured ${etVerdict.measured}`
    );
  }

  // --- the positive control -------------------------------------------------
  let ctlVerdict = null;
  if (rowId !== 'keystroke') {
    const per = SEGMENTS.flatMap((seg) => ratioToFloor(rounds, seg, 'ctl-2x'));
    ctlVerdict = controlVerdict(2.0, per, CONTROL_SLACK);
    console.log(
      `;; ---- POSITIVE CONTROL: ctl-2x builds exactly twice the page, so the prediction is 2.00x ----`
    );
    console.log(
      `;;   ${ctlVerdict.ok ? 'PASS' : 'FAIL'}  predicted ${ctlVerdict.predicted}x, band ` +
        `[${ctlVerdict.band[0]} – ${ctlVerdict.band[1]}], measured ${ctlVerdict.measured.mean}x ` +
        `[${ctlVerdict.measured.min} – ${ctlVerdict.measured.max}] over ${per.length} segment-rounds ` +
        `(${ctlVerdict.rule})`
    );
  } else {
    // A DIFFERENCE, so the tare cancels in it whether or not it is
    // subtracted — which is why this control is stated in milliseconds
    // rather than as a ratio.
    const ctlTask = SEGMENTS.flatMap((seg) =>
      rounds.map((r) => p50(r[seg]['ctl-50ms']) - p50(r[seg][FLOOR]))
    );
    const b = band(ctlTask);
    ctlVerdict = {
      predicted: `>= ${CTL_BUSY_MS - 10} ms of extra main-thread task time`,
      measured: b,
      ok: ctlTask.every((x) => x >= CTL_BUSY_MS - 10),
      rule: 'strict — EVERY segment-round',
    };
    console.log(`;; ---- POSITIVE CONTROL: ctl-50ms burns 50 ms inside its own handler ----`);
    console.log(
      `;;   ${ctlVerdict.ok ? 'PASS' : 'FAIL'}  predicted ${ctlVerdict.predicted}; measured ` +
        `${b.mean.toFixed(2)} ms [${b.min.toFixed(2)} – ${b.max.toFixed(2)}] over ${ctlTask.length} segment-rounds`
    );
  }

  // --- the arm-order guard --------------------------------------------------
  const v = guard.verdict(samples, { tolerance: 0.1 });
  for (const line of guard.format(v, `${rowId} — frame-inclusive task time`)) console.log(line);

  return { bar, ctlVerdict, etVerdict, guardVerdict: v, parityOk: disagree.length === 0, tally, seam };
}

// ---------------------------------------------------------------------------

(async () => {
  if (!NO_BUILD) build();
  if (!fs.existsSync(OUT)) {
    console.error(`[clock] ${OUT} does not exist — run without --no-build first`);
    process.exit(1);
  }
  const server = serve();
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const version = browser.version();

  const st = guard.selfTest();
  const badSelfTest = st.checks.filter((c) => !c.ok);
  if (badSelfTest.length > 0) {
    console.error(`[clock] the arm-order guard's own self-test FAILED: ${badSelfTest.map((c) => c.name).join(', ')}`);
    await browser.close();
    server.close();
    process.exit(1);
  }
  console.error(`[clock] arm-order guard self-test: ${st.checks.length} checks, all ok`);

  const sst = seamlib.selfTest();
  const badSeam = sst.checks.filter((c) => !c.ok);
  if (badSeam.length > 0) {
    console.error(`[clock] the seam adjudicator's own self-test FAILED: ${badSeam.map((c) => c.name).join(', ')}`);
    await browser.close();
    server.close();
    process.exit(1);
  }
  console.error(`[clock] seam adjudicator self-test: ${sst.checks.length} checks, all ok`);

  console.log(`;; ==== HICASSO CANDIDATE CLOCK ====`);
  console.log(`;; chromium ${version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; rows      ${ROWS.join(', ')}`);
  console.log(`;; segments  ${SEGMENTS.join(', ')}  (order rotates with the round)`);
  console.log(`;; design    ${ROUNDS} rounds x (${WARMUP} warm-up + ${SAMPLES} samples) per arm per segment`);
  console.log(
    `;; reproduce ${ONLY ? `HCLOCK_ONLY=${ONLY} ` : ''}node ` +
      `implementation/freehand/test/re_frame/bench/hicasso/clock_run.cjs`
  );
  console.log(`;; tare      ${TARE ? 'ON' : 'OFF'} — plumb, an arm that mounts nothing and settles the same frame`);
  console.log(`;; PREDICTIONS, written before the run:`);
  console.log(`;;   ctl-2x     = the floor at twice the boundaries -> 2.00x the floor, +/-${CONTROL_SLACK * 100}%, EVERY round`);
  console.log(`;;   ctl-50ms   = 50 ms burned inside the handler -> >= ${CTL_BUSY_MS - 10} ms extra task time AND an Event Timing interaction >= ${CTL_BUSY_MS - 2} ms`);
  console.log(`;;   in-page    = the performance.now() window reads DIFFERENTLY from the frame-inclusive one, by an arm-dependent amount`);
  console.log(
    `;;   THE TARE   = run 1 of this instrument (no plumb arm) read ctl-2x at 1.5909x [1.1635 - 2.1509] and FAILED,`
  );
  console.log(
    `;;                while its decomposition showed layout doubling exactly (1.56 -> 3.08 ms) and 3.6 ms/sample`
  );
  console.log(
    `;;                not moving at all. The additive-constant model therefore PREDICTS that subtracting a measured`
  );
  console.log(
    `;;                per-sample constant restores ctl-2x to 2.00x. That is what the plumb arm tests, and an`
  );
  console.log(
    `;;                overshoot past 2.5x would refute the model rather than the instrument.`
  );
  console.log(
    `;;   SOLO       = run 2 tared cleanly and its MOUNT control passed at 1.9103x every round, while the three`
  );
  console.log(
    `;;                BULK controls failed at 1.4304-1.5214x. The difference between those rows is that a mount`
  );
  console.log(
    `;;                row has ONE arm standing and a bulk row has four (901+901+1801 elements), and a dirty frame`
  );
  console.log(
    `;;                pays pre-paint and paint over the whole document. So this run hides every arm but the one`
  );
  console.log(
    `;;                under test, outside the window. PREDICTION: the bulk controls move up toward 2.00x. If they`
  );
  console.log(
    `;;                do not, the co-mounted document was not the cause and the 2.00x prediction is what is wrong.`
  );

  const outcomes = [];
  let died = null;
  try {
    for (const rowId of ROWS) {
      console.error(`[clock] row ${rowId}`);
      try {
        const out = await runRow(browser, rowId);
        outcomes.push({ out, verdict: report(out) });
      } catch (e) {
        died = `${rowId}: ${e.message}`;
        break;
      }
    }
  } finally {
    await browser.close();
    server.close();
  }

  if (died) {
    // A RUN THAT DIED IS NOT A RUN. Nothing is written, including the
    // rows that completed before it: a partial dataset on disk is the
    // shape of `rf2-6t03c`'s recorded fault, where a stale artefact was
    // silently measured after the thing that produced it had aborted.
    console.error(`[clock] FAILED: ${died}`);
    process.exit(1);
  }

  if (JSON_OUT) {
    fs.mkdirSync(path.dirname(path.resolve(JSON_OUT)), { recursive: true });
    fs.writeFileSync(
      path.resolve(JSON_OUT),
      JSON.stringify(
        {
          label: process.env.HCLOCK_LABEL || null,
          load: process.env.HCLOCK_LOAD === undefined ? null : Number(process.env.HCLOCK_LOAD),
          chromium: version,
          node: process.version,
          when: new Date().toISOString(),
          design: { rounds: ROUNDS, warmup: WARMUP, samples: SAMPLES, tare: TARE, segments: SEGMENTS },
          rows: outcomes.map((o) => ({
            rowId: o.out.rowId,
            // Raw per-sample task-time readings, [round][segment][arm].
            // Everything the seam decomposition needs is derived from
            // these; the segment's POSITION in a round is
            // `(SEGMENTS.indexOf(seg) - round) mod 3` by construction.
            rounds: o.out.rounds,
            decomposition: o.out.decomposition,
            granularity: o.out.granularity,
            seam: o.verdict.seam,
            tally: o.verdict.tally,
            ctlOk: o.verdict.ctlVerdict ? o.verdict.ctlVerdict.ok : null,
            guardRefuse: o.verdict.guardVerdict.refuse,
            bar: o.verdict.bar,
          })),
        },
        null,
        1
      )
    );
    console.error(`[clock] raw readings -> ${path.resolve(JSON_OUT)}`);
  }

  const errored = outcomes.filter((o) => o.out.pageErrors.length > 0);
  if (errored.length > 0) {
    console.error(
      `[clock] FAILED: uncaught page error(s) — every figure above was taken on a page that had ` +
        `already thrown:\n  ` +
        errored.map((o) => `${o.out.rowId}: ${o.out.pageErrors.join(' | ')}`).join('\n  ')
    );
    process.exit(1);
  }
  const refused = outcomes.filter((o) => o.verdict.guardVerdict.refuse);
  if (refused.length > 0) {
    console.error(
      `[clock] ARM-ORDER GUARD REFUSED (exit 2) on: ${refused.map((o) => o.out.rowId).join(', ')}. ` +
        `At least one arm reads differently for WHERE IN THE PLAN it was measured, so no figure in ` +
        `that row is reportable. Repair the ARM — more warm-up, fewer arms per page, a longer ` +
        `measured window. The guard tolerance is not yours to move.`
    );
    process.exit(2);
  }
  const badParity = outcomes.filter((o) => !o.verdict.parityOk);
  if (badParity.length > 0) {
    console.error(
      `[clock] FAILED: the canonical-DOM gate found arms building DIFFERENT PAGES on: ` +
        `${badParity.map((o) => o.out.rowId).join(', ')}. A ratio between two different pages is not a ratio.`
    );
    process.exit(1);
  }
  const unverified = outcomes.filter((o) => o.verdict.tally.unverified > 0);
  if (unverified.length > 0) {
    console.error(
      `[clock] FAILED: unverified operations — a window whose value never reached the page is not a ` +
        `measurement of that page: ` +
        unverified.map((o) => `${o.out.rowId}: ${o.verdict.tally.unverified} of ${o.verdict.tally.writes}`).join(', ')
    );
    process.exit(1);
  }
  // THE BAND CEILING is a tripwire and is honest about being one. The
  // nineteen-run ladder that calibrated it produced bands of 4.4–18.5%
  // INCLUDING the rung with twenty of twenty-four cores saturated, and the
  // ceiling sits at 25% above all of them — so this gate did not fire
  // anywhere on its own calibration. It catches a box outside that whole
  // range, in which the instrument's own reproducibility has gone and no
  // magnitude means anything. The gate that actually bites is the per-row
  // one printed in the seam block: a margin inside the band is
  // instrument-limited.
  const overCeiling = outcomes.filter((o) => o.verdict.seam.verdict.ceilingBreached);
  if (overCeiling.length > 0) {
    console.error(
      `[clock] FAILED: the run's own reproducibility band exceeds the ` +
        `${(seamlib.BAND_CEILING * 100).toFixed(0)}% ceiling on: ` +
        overCeiling.map((o) => `${o.out.rowId} (${(o.verdict.seam.band * 100).toFixed(1)}%)`).join(', ') +
        `. ctl-2x and floor are two arms in the SAME block whose true ratio is a property of the ` +
        `page, so a band that wide means the box could not reproduce identical work — no magnitude ` +
        `from those rows is reportable, whatever its margin.`
    );
    process.exit(1);
  }

  const ctlFailed = outcomes.filter((o) => !o.verdict.ctlVerdict.ok || (o.verdict.etVerdict && !o.verdict.etVerdict.ok));
  if (ctlFailed.length > 0) {
    const passed = outcomes.filter((o) => !ctlFailed.includes(o)).map((o) => o.out.rowId);
    console.error(
      `[clock] FAILED: the positive control did not see the change its own arithmetic predicts on: ` +
        `${ctlFailed.map((o) => o.out.rowId).join(', ')}. No MAGNITUDE from those rows is reportable.`
    );
    // EXIT 1 HERE IS PER-ROW, AND THE ROWS THAT PASSED ARE STILL ROWS.
    // Everything before this line is a whole-run gate — a page that threw,
    // a guard refusal, two arms building different pages, an unverified
    // write — and reaching here means every one of them cleared on every
    // row. A control is the only gate this driver scopes to the row that
    // failed it, because that is the only one whose claim is about a row.
    console.error(
      passed.length > 0
        ? `[clock] REPORTABLE: ${passed.join(', ')} — control passed, guard clean, canonical DOM identical, ` +
            `0 unverified. Publish those and mark the rest.`
        : `[clock] REPORTABLE: none.`
    );
    process.exit(1);
  }
  console.error('[clock] ok');
})();
