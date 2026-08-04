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
// ITS ACCOUNTING IS `clock_witness.cjs`'s, AND IT CAN REFUSE (rf2-0qj9w).
// The first cut of this row grouped entries by `${interactionId || 0}`
// inside an already-known physical sample, so the zero-id `beforeinput` /
// `input` entries became a second pseudo-interaction beside the real
// keyboard one and the published table called 109-115 records
// "interactions" for 60 keys. It also printed a `totalKeys` that omitted
// the arm axis — 180 for the 540 keys it sent. The repair is that the
// driver COUNTS THE KEYS IT PRESSES, one record is formed per physical
// key under web-vitals' interaction-id rules, keys that produced no entry
// are published as CENSORED rather than dropped, and every one of those
// statements is a gate whose failure exits non-zero naming itself. The
// witness's own refusals are fixtures: `node clock_run.cjs --selftest`,
// and `clock_witness.test.cjs` in the fast-PR spine.
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
// is that the seam does not track load at all and the band is the number that
// was actually wanted — and that the band is WIDEST ON AN IDLE BOX, because a
// busy one is slower and steadier while an idle one parks its cores between
// samples. `rf2-ymi6j` re-took the ladder on the published clock, withdrew the
// claim that the perturbation is multiplicative and cancels exactly, and put
// the ceiling on the band's own sampling distribution.
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
const kbwitness = require('./clock_witness.cjs');

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
// study (rf2-cvvb7) had to compare twenty runs against each other, and a
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

// THE FALSIFICATION KNOB for the three-point control. Set it and the
// control's arms render this many cells while still DECLARING 1 / 100 / 200
// to the driver, so a run in which every other gate passes exits 1 naming the
// control. It exists because a control nobody has seen refuse is a control of
// unmeasured sensitivity, and this lane has found that defect nine times.
const CTL3_SABOTAGE = Number(process.env.HCLOCK_CTL3_SABOTAGE || 0) || null;

// Run every adjudicator's own self-test and stop, without building or opening
// a browser. The three-point control's refusals are fixtures rather than
// prose, and this is how a reader runs them in a second.
const SELFTEST_ONLY = process.argv.includes('--selftest');

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

// THE BAR ROWS, `[numerator, denominator]` by segment — and the third one is
// not the candidate's (rf2-yd52q).
//
// The first two are what this driver was built for: the candidate against each
// donor. The third, `uix-subs / reagent-subs`, is THE PUBLISHED DONOR ROW —
// `bulk broad 0.6291x` on the converged page, and `M1 mount 1.0150x` — formed
// by exactly the arithmetic the converged harness forms it by: each donor
// divided by the floor measured in its own segment of that round, then one
// quotient over the other.
//
// It was computable from this driver's data from the first run and was not
// computed. `rf2-8nqsl`'s audit had to derive it BY HAND from the same
// readings to reach `1.0509x` on `bulk300`, and a statistic a page recomputes
// off-instrument is a statistic no gate is watching: it carries no band, no
// regime and no control verdict. Adding it here costs one line of arithmetic
// and puts the programme's most-quoted clock row under the same adjudication
// as every other row this driver prints.
const BAR_PAIRS = [
  ['hicasso', 'reagent-subs'],
  ['hicasso', 'uix-subs'],
  ['uix-subs', 'reagent-subs'],
];

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
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'clock',
  });
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
// THE THREE-POINT CONTROL — a difference of differences (rf2-7iqb5, rf2-5xrcd)
// ---------------------------------------------------------------------------
//
// `ctl-2x` is the floor at twice the boundaries against the floor, predicted
// 2.00x. Over rf2-emvod's seven runs it read 1.8173x on the MOUNT row and
// 1.7334 / 1.7696 / 1.7796 on bulk300 / bulk100 / narrow — every row short by
// 9-13%, and `rf2-5xrcd`'s "mis-specified for an UPDATE row" does not reach
// the mount row at all.
//
// ONE ADDITIVE CONSTANT fits all four. `(2W + c)/(W + c)` is below 2 for any
// positive `c` and does not care what the row is. Inverting each measured
// ratio against its own floor — see `additiveConstant` below, which this run
// re-measures rather than importing — recovered c = 1.040 / 1.043 / 0.873 /
// 0.790 ms.
//
// `rf2-7iqb5`'s filed repair, doubling the CHANGED SET at fixed page size,
// leaves `c` untouched: `(2D + c)/(D + c)` has the same shape. So the repair
// here is not a second two-point control on a better axis. It is a THREE-POINT
// control on that axis, adjudicated as a difference of differences, in which
// the constant is not estimated, bounded or assumed — it CANCELS.
//
//     R3 = (T(d2) - T(d0)) / (T(d1) - T(d0))  ->  (d2 - d0) / (d1 - d0)
//
// Both perturbations this lane has measured die in it:
//
//   * an ADDITIVE per-sample constant cancels in each difference;
//   * a MULTIPLICATIVE block-level perturbation — which is what rf2-cvvb7's
//     nineteen-run load ladder found ambient load to be — cancels in the
//     quotient.
//
// `ctl-2x` survives the second only. Both are computed and printed, on the
// same samples, so the repair is visible rather than asserted.
//
// WHAT IT CANNOT DO, stated here rather than discovered later. It certifies
// that the composite of INSTRUMENT and WORKLOAD is linear in the dirty set.
// A refusal does not by itself say which of the two bent, and a pass does not
// certify a MOUNT row — a mount has no standing page and no changed-set axis,
// so `M1` keeps `ctl-2x` and keeps its known undershoot.

// Every arm of the control goes through `page.evaluate` -> `HCLOCK.sample`,
// the SAME door as the floor, `ctl-2x` and every substrate arm on a bulk row.
// That is load-bearing rather than incidental: rf2-emvod's third defect was
// that `DevToolsCommandDuration` bills page script only when the script runs
// inside a protocol command, so the SAME subtraction is frame-only through
// `page.evaluate` and script-and-frame through `page.click`. A control whose
// arms went through two different doors would be differencing two different
// quantities. These three go through one, so whatever the door costs is
// common-mode, additive, and cancels with everything else constant.
const CTL3_DOOR = 'page.evaluate -> HCLOCK.sample (all three arms, one door)';

/** Least-squares fit of `T = a*d + c` over `[{d, t}]`, with its residuals. */
function linearFit(points) {
  const n = points.length;
  if (n < 2) return null;
  const dbar = points.reduce((s, p) => s + p.d, 0) / n;
  const tbar = points.reduce((s, p) => s + p.t, 0) / n;
  const sdd = points.reduce((s, p) => s + (p.d - dbar) ** 2, 0);
  if (sdd === 0) return null;
  const a = points.reduce((s, p) => s + (p.d - dbar) * (p.t - tbar), 0) / sdd;
  const c = tbar - a * dbar;
  const ssTot = points.reduce((s, p) => s + (p.t - tbar) ** 2, 0);
  const ssRes = points.reduce((s, p) => s + (p.t - (a * p.d + c)) ** 2, 0);
  return {
    slope: a,
    intercept: c,
    r2: ssTot === 0 ? NaN : 1 - ssRes / ssTot,
    maxResidual: Math.max(...points.map((p) => Math.abs(p.t - (a * p.d + c)))),
  };
}

/**
 * `c` recovered from a DOUBLING control, which is rf2-emvod's arithmetic and
 * is reproduced here rather than quoted.
 *
 * If `floor = W + c` and `ctl2x = 2W + c` then their measured ratio `R`
 * satisfies `R(W + c) = 2W + c`, so `W = c(R-1)/(2-R)` and therefore
 *
 *     floor = c/(2 - R)     =>     c = floor * (2 - R)
 *
 * It is exact, it has no free parameter, and it is DEGENERATE as `R -> 2`:
 * a doubling control that passed cleanly would recover `c = 0` and say
 * nothing, which is the sense in which `ctl-2x` was never able to measure
 * the thing that was wrong with it.
 */
function additiveConstant(floorTared, ratio2x) {
  return floorTared * (2 - ratio2x);
}

/**
 * The three-point verdict, per block. `dirty` maps arm id -> DECLARED dirty
 * count, taken from the page's own plan rather than from a literal here, so
 * a page that renders something other than what it declares is a
 * disagreement the control can see (`HCLOCK_CTL3_SABOTAGE`).
 *
 * THE STATISTIC DOES NOT USE THE TARE, and does not need to: `plumb` enters
 * every arm of a block identically, so it cancels in `T(d2) - T(d0)` and
 * again in `T(d1) - T(d0)`. This control is independent of the tare arm
 * entirely, which is one fewer thing for a published row to depend on.
 *
 * THE FITTED INTERCEPT DOES use it, and must. `c(3pt)` is only comparable
 * with `c(2x)` — which is computed on tared readings — if the same tare has
 * been taken out of both, and comparing them is how the model's ordering
 * prediction is adjudicated. So the fit runs on tared points while the
 * quotient runs on raw ones, and the ratio is identical either way.
 */
function ctl3Verdict(rounds, plan, slack) {
  const dirty = {};
  for (const a of plan) if (a.ctl3) dirty[a.id] = a.dirty;
  const witness = plan.filter((a) => a.ctl3Witness);
  const arms = Object.keys(dirty).sort((a, b) => dirty[a] - dirty[b]);
  if (arms.length < 3) return null;
  const [a0, a1, a2] = arms;
  const [d0, d1, d2] = arms.map((a) => dirty[a]);
  // The witness is a fourth point BELOW the control's range and is never a
  // term in the statistic. It exists to re-measure, every run, the
  // saturating paint term that refuted the first build of this control —
  // see `clock-app/ctl3-witness-dirty`.
  const wArm = witness.length ? witness[0].id : null;
  const wD = witness.length ? witness[0].dirty : null;
  const predicted = (d2 - d0) / (d1 - d0);
  const per = [];
  const blocks = [];
  for (let r = 0; r < rounds.length; r++) {
    for (const seg of SEGMENTS) {
      const t0 = p50(rounds[r][seg][a0]);
      const t1 = p50(rounds[r][seg][a1]);
      const t2 = p50(rounds[r][seg][a2]);
      const tW = wArm && rounds[r][seg][wArm] ? p50(rounds[r][seg][wArm]) : NaN;
      const tare = TARE && rounds[r][seg][PLUMB] ? p50(rounds[r][seg][PLUMB]) : 0;
      per.push((t2 - t0) / (t1 - t0));
      const fit = linearFit([
        { d: d0, t: t0 - tare },
        { d: d1, t: t1 - tare },
        { d: d2, t: t2 - tare },
      ]);
      blocks.push({
        seg, round: r,
        t: { ...(Number.isFinite(tW) ? { [wD]: r4(tW) } : {}), [d0]: r4(t0), [d1]: r4(t1), [d2]: r4(t2) },
        num: r4(t2 - t0), den: r4(t1 - t0),
        ratio: r4((t2 - t0) / (t1 - t0)),
        // MARGINAL COST PER DIRTY CELL, per interval, in µs. The control's
        // two intervals must agree — that IS the statistic, restated — and
        // the witness interval below them is expected NOT to, which is the
        // regime finding published rather than assumed.
        marginalUs: {
          [`${wD}-${d0}`]: Number.isFinite(tW) ? r4(((t0 - tW) / (d0 - wD)) * 1000) : null,
          [`${d0}-${d1}`]: r4(((t1 - t0) / (d1 - d0)) * 1000),
          [`${d1}-${d2}`]: r4(((t2 - t1) / (d2 - d1)) * 1000),
        },
        fit: fit && { slopeUsPerCell: r4(fit.slope * 1000), intercept: r4(fit.intercept), r2: r4(fit.r2), maxResidual: r4(fit.maxResidual) },
      });
    }
  }
  const v = controlVerdict(predicted, per, slack);
  const marg = (k) => band(blocks.map((b) => b.marginalUs[k]).filter(Number.isFinite));
  return {
    ...v,
    arms: { eps: a0, d: a1, twoD: a2, witness: wArm },
    dirty: { [a0]: d0, [a1]: d1, [a2]: d2 },
    witnessDirty: wD,
    door: CTL3_DOOR,
    blocks,
    // THE REGIME TABLE. The two intervals inside the control must agree;
    // the witness interval below it is where the first build of this
    // control was refuted, and it is re-measured rather than inherited.
    marginal: {
      witness: wD !== null ? marg(`${wD}-${d0}`) : null,
      lower: marg(`${d0}-${d1}`),
      upper: marg(`${d1}-${d2}`),
    },
    // ABSOLUTES BESIDE THE RATIO, because this lane's three instrument
    // defects were all visible in the milliseconds on run 1 and invisible in
    // the ratio printed instead. A difference of differences is exactly the
    // statistic whose denominator can quietly shrink to noise, and the only
    // way to see that happening is to look at it in milliseconds.
    signal: {
      numMs: band(blocks.map((b) => b.num)),
      denMs: band(blocks.map((b) => b.den)),
      slopeUsPerCell: band(blocks.filter((b) => b.fit).map((b) => b.fit.slopeUsPerCell)),
      interceptMs: band(blocks.filter((b) => b.fit).map((b) => b.fit.intercept)),
      r2: band(blocks.filter((b) => b.fit).map((b) => b.fit.r2)),
    },
  };
}

/**
 * The adjudicator's own self-test, run before the browser opens and fatal if
 * it fails — the pattern `order_guard.cjs` and `seam.cjs` already hold this
 * driver to. A control is only worth its verdict if its arithmetic has been
 * shown to REFUSE something, and these cases are the refusals stated as
 * fixtures rather than as prose.
 */
function ctl3SelfTest() {
  const D = [1, 100, 200];
  const ids = ['ctl-d1', 'ctl-d100', 'ctl-d200'];
  const plan = ids.map((id, i) => ({ id, dirty: D[i], ctl3: true, ctl3Witness: false, cells: 300 }));
  const predicted = (200 - 1) / (100 - 1); // 2.0101
  // A synthetic block set: `t(d)` per block, three segments x three rounds.
  const synth = (t) => {
    const rs = [];
    for (let r = 0; r < 3; r++) {
      const perSeg = {};
      for (let i = 0; i < SEGMENTS.length; i++) {
        perSeg[SEGMENTS[i]] = {
          'ctl-d1': [t(1, r, i)], 'ctl-d100': [t(100, r, i)], 'ctl-d200': [t(200, r, i)],
          [FLOOR]: [t(300, r, i)], [PLUMB]: [0.7],
        };
      }
      rs.push(perSeg);
    }
    return rs;
  };
  const A = 0.006; // 6 µs per dirty cell
  const C = 3.5; // a constant of the same order as the whole signal
  const checks = [];

  // 1. Linear work under a large additive constant: the constant is exactly
  //    what broke `ctl-2x`, and it must not touch this statistic at all.
  // `measured.mean` comes back through `band`, which rounds to four places,
  // so the tolerance here is the ROUNDING and not a fudge — 5e-5 is half a
  // unit in the last place it can carry.
  const exact = (x) => Math.abs(x - r4(predicted)) < 5e-5;
  const lin = ctl3Verdict(synth((d) => A * d + C), plan, CONTROL_SLACK);
  checks.push({ name: 'linear + large additive constant PASSES', ok: lin.ok && exact(lin.measured.mean) });

  // 2. THE SAME WORLD THROUGH A DOUBLING CONTROL. The claim is not that
  //    `ctl-2x` fails some band on one value — with realistic numbers it
  //    lands inside the band and fails only on per-block scatter. The claim
  //    is that it is BIASED: it reads `(2W + c)/(W + c)`, systematically
  //    below 2, while the three-point statistic on the same world reads
  //    exactly 2 with no bias at all. Modelled on the floor's own page,
  //    where `W` is what doubles and `c` is rf2-emvod's ~1 ms.
  const Wf = A * 300;
  const cf = 1.0;
  const ctl2xOnSameWorld = (2 * Wf + cf) / (Wf + cf);
  checks.push({
    name: 'the doubling control is BIASED LOW on a world the three-point one reads exactly',
    ok:
      ctl2xOnSameWorld < 1.95 &&
      exact(lin.measured.mean) &&
      Math.abs(additiveConstant(Wf + cf, ctl2xOnSameWorld) - cf) < 1e-9,
  });

  // 3. A MULTIPLICATIVE block perturbation — ambient load, which rf2-cvvb7
  //    measured to be exactly this shape — must also cancel.
  const mult = ctl3Verdict(synth((d, r, i) => (1 + 0.35 * r + 0.2 * i) * (A * d + C)), plan, CONTROL_SLACK);
  checks.push({ name: 'multiplicative block perturbation PASSES', ok: mult.ok && exact(mult.measured.mean) });

  // 4. SUPERLINEAR work must REFUSE. If the page's cost per dirty cell grows
  //    with the dirty set, the row's own premise is wrong and the control is
  //    the thing that says so.
  const sup = ctl3Verdict(synth((d) => (A * Math.pow(d, 2)) / 300 + C), plan, CONTROL_SLACK);
  checks.push({ name: 'superlinear work (d^2) REFUSES', ok: !sup.ok });

  // 4b. THE CONTROL'S SENSITIVITY, DERIVED AND ASSERTED RATHER THAN HOPED
  //     FOR. With equally spaced points the statistic is exactly
  //     `1 + Δ₂/Δ₁`, where `Δ₁` and `Δ₂` are the marginal costs of the two
  //     intervals. So the +/-25% band means, precisely:
  //
  //         the control refuses iff the upper interval's marginal cost
  //         differs from the lower interval's by more than 50%.
  //
  //     That is what it catches. What it CANNOT catch is the important
  //     half, and it is stated here because the first build of this control
  //     was refuted by exactly this shape of workload:
  checks.push({
    name: 'the statistic is exactly 1 + upper/lower marginal, so the band is |Δ₂/Δ₁ - 1| <= 50%',
    ok:
      controlVerdict(2, [1 + 0.5], CONTROL_SLACK).ok &&
      controlVerdict(2, [1 + 1.5], CONTROL_SLACK).ok &&
      !controlVerdict(2, [1 + 0.49], CONTROL_SLACK).ok &&
      !controlVerdict(2, [1 + 1.51], CONTROL_SLACK).ok,
  });

  //     A PURE POWER LAW `d^k` is the sharp way to state that. At
  //     these points that is `(200^k - 1)/(100^k - 1)`, which tends to 1 as
  //     `k -> 0` and rises through 2.0101 at `k = 1`. Unlike an equally
  //     spaced 1 : 2 : 3 design — whose reading never falls below
  //     `ln3/ln2 = 1.585` and so can NEVER refuse a sublinear workload —
  //     this placement refuses below about `k = 0.55` and above about
  //     `k = 1.33`. That asymmetric span is the one real advantage the
  //     ruled point placement has over the wider-spaced alternative, and it
  //     is why the sublinear refusal this control actually returned is a
  //     finding rather than a shrug.
  const kOf = (k) => (Math.pow(200, k) - 1) / (Math.pow(100, k) - 1);
  checks.push({
    name: 'sensitivity, asserted: refuses a power law below k~0.55 and above k~1.33 (1:2:3 spacing could do neither below)',
    ok:
      Math.abs(kOf(1) - predicted) < 1e-9 &&
      controlVerdict(predicted, [kOf(0.65)], CONTROL_SLACK).ok &&
      !controlVerdict(predicted, [kOf(0.45)], CONTROL_SLACK).ok &&
      controlVerdict(predicted, [kOf(1.25)], CONTROL_SLACK).ok &&
      !controlVerdict(predicted, [kOf(1.4)], CONTROL_SLACK).ok &&
      // and the equally-spaced alternative genuinely cannot: its floor is
      // 1.585, which is inside the band for every sublinear exponent.
      [0.05, 0.3, 0.6, 0.9].every((k) =>
        controlVerdict(2, [(Math.pow(3, k) - 1) / (Math.pow(2, k) - 1)], CONTROL_SLACK).ok
      ),
  });

  // 5. AN ARM THAT DOES NOT DO WHAT IT DECLARES must REFUSE. This is the
  //    fixture form of `HCLOCK_CTL3_SABOTAGE`: the page renders 140 while
  //    still declaring 200, every other gate passes, and the control is the
  //    only one that can see it.
  const sab = ctl3Verdict(synth((d) => A * (d === 200 ? 140 : d) + C), plan, CONTROL_SLACK);
  checks.push({ name: 'an arm dirtying 140 while declaring 200 REFUSES', ok: !sab.ok });

  // 6. A DEAD DENOMINATOR must REFUSE rather than read as a pass. This
  //    statistic's characteristic failure is not a wrong number, it is a
  //    denominator that has shrunk into the noise — and a quotient of two
  //    quantities that are both zero must never be treated as agreement. An
  //    instrument that saw NO dirty-set signal at all would produce exactly
  //    this, and it has to come out as a refusal.
  const dead = ctl3Verdict(synth(() => C), plan, CONTROL_SLACK);
  checks.push({
    name: 'a workload with NO dirty-set signal REFUSES (degenerate denominator is not a pass)',
    ok: !dead.ok && !Number.isFinite(dead.measured.mean),
  });
  // 6b. THE STRICT RULE IS PER BLOCK. Eight clean blocks must not vouch for
  //     a ninth that is wrong — that is `lane/control-verdict`'s recorded
  //     overlap defect (rf2-egdaq), and it is the reason this control is
  //     adjudicated block by block rather than on a pooled mean. Note that a
  //     block-wide SCALING would not do as a fixture here: it cancels in the
  //     quotient by design, so the one bad block has to be bad in SHAPE.
  const oneBad = ctl3Verdict(
    synth((d, r, i) => (r === 1 && i === 2 ? (A * Math.pow(d, 2)) / 300 : A * d) + C),
    plan, CONTROL_SLACK
  );
  checks.push({
    name: 'ONE nonlinear block out of nine REFUSES (eight good blocks do not vouch for it)',
    ok: !oneBad.ok && oneBad.perRound.length === 9 &&
      oneBad.perRound.filter((x) => Math.abs(x - predicted) < 0.01).length === 8,
  });

  // 7. THE PREDICTION IS DERIVED, not carried as a literal. `2.0101` and not
  //    `2.00` is the whole reason the epsilon arm may dirty one cell rather
  //    than none: with `d0 = 0` the prediction WOULD be exactly 2, and a
  //    literal `2.00` would then be silently wrong by 0.5% for every other
  //    choice of counts. Re-deriving it under a different epsilon proves the
  //    number tracks the page's declaration.
  const widerPlan = plan.map((a) => (a.id === 'ctl-d1' ? { ...a, dirty: 20 } : a));
  const widerV = ctl3Verdict(synth((d) => A * (d === 1 ? 20 : d) + C), widerPlan, CONTROL_SLACK);
  checks.push({
    name: 'the prediction is (d2-d0)/(d1-d0), derived from the page plan',
    ok:
      Math.abs(lin.predicted - r4(199 / 99)) < 1e-9 &&
      Math.abs(widerV.predicted - r4(180 / 80)) < 1e-9 &&
      Math.abs(widerV.measured.mean - r4(180 / 80)) < 5e-5 &&
      widerV.ok,
  });

  // 8. `additiveConstant` inverts a doubling control exactly, and reproduces
  //    rf2-emvod's M1 figure from its published inputs.
  checks.push({
    name: 'additiveConstant recovers c from a doubling ratio (rf2-emvod M1: 5.695 ms, 1.8173x -> 1.040 ms)',
    ok: Math.abs(additiveConstant(5.695, 1.8173) - 1.0405) < 0.001 &&
      Math.abs(additiveConstant(Wf + cf, ctl2xOnSameWorld) - cf) < 1e-9,
  });

  return { checks };
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
//
// `__ETKEY` — the WHOLE physical key in flight, not just its arm — is
// stamped onto each entry AT OBSERVATION TIME (rf2-0qj9w). It used to be
// the arm alone, with the round and the sample index added by the driver
// at drain time; an entry that arrived one drain late therefore inherited
// the NEXT sample's index and was silently attributed to a keypress that
// did not raise it. Stamping in the page removes the possibility rather
// than making it unlikely. An entry observed with no key in flight keeps
// `key: null` and reaches the adjudicator as an `unattributed-entry`
// refusal.
const EVENT_TIMING_INIT = `
  window.__ET = [];
  window.__ETKEY = null;
  const __etpush = (e, name) => {
    window.__ET.push({
      key: window.__ETKEY,
      name: name,
      startTime: e.startTime,
      processingStart: e.processingStart,
      processingEnd: e.processingEnd,
      duration: e.duration,
      interactionId: e.interactionId,
    });
  };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) __etpush(e, e.name);
    }).observe({ type: 'event', durationThreshold: 16, buffered: true });
  } catch (err) { window.__ETERROR = String(err); }
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) __etpush(e, 'first-input:' + e.name);
    }).observe({ type: 'first-input', buffered: true });
  } catch (err) { window.__ETERROR2 = String(err); }
`;

// ---------------------------------------------------------------------------
// One row
// ---------------------------------------------------------------------------

// `trace` is the caller's `{step}` box, and it exists because THE SEGMENT
// ORDER ROTATES WITH THE ROUND: a bare `M1: <error>` does not say which
// segment was on the page when it threw, so it does not say whether the
// candidate failed or a donor did (rf2-029ed). Written at each point the
// row moves, read only if the row dies.
async function runRow(browser, rowId, trace) {
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

  // THE FALSIFICATION KNOB, installed before the first sample and echoed
  // back. It makes the three-point control's arms render a dirty set other
  // than the one they DECLARE, so the driver goes on predicting from the
  // declaration while the page does something else. Every other gate still
  // passes; the control is the only one that can see it. A run with this set
  // is a demonstration and not a measurement, and the banner below says so.
  let sabotage = null;
  if (CTL3_SABOTAGE) {
    sabotage = await page.evaluate((d) => window.HCLOCK.sabotage(d), CTL3_SABOTAGE);
    console.log(
      `;; SABOTAGE the three-point control's arms render ${sabotage} cells while still declaring ` +
        `1 / 100 / 200 — THIS RUN IS A FALSIFICATION, NOT A MEASUREMENT`
    );
  }

  const isKeystroke = rowId === 'keystroke';
  // The witness's stated shape, READ FROM THE PAGE rather than repeated
  // here: validation.md names `a 4-field form and a 100-cell grid`, the page
  // is what implements it, and a driver carrying its own copy of those two
  // numbers is a driver that can grade a witness it is not looking at.
  const kbShape = isKeystroke
    ? await page.evaluate(() => ({ cells: window.HCLOCK.kbCellsN, fields: window.HCLOCK.kbFieldsN }))
    : { cells: 0, fields: 0 };
  const kbFields = kbShape.fields;
  // The census rides the LAST warm-up sample. With no warm-up there is no
  // unmeasured sample to ride, so none is taken and the adjudicator refuses
  // the row for want of one — which is the right answer, not a gap.
  const censusAt = WARMUP > 0 ? WARMUP - 1 : -1;
  let armPlan = []; // the page's own plan, carrying each arm's DECLARED dirty count
  const samples = []; // for the arm-order guard, on taskNet
  // The SAME samples for the SAME guard on the corrected clock. A figure
  // whose value depends on where in the plan it was measured is not a
  // figure, and that claim is about whichever clock the figure is stated
  // on — so the published clock gets its own guard rather than inheriting
  // a verdict taken on the one it replaced (rf2-emvod).
  const samplesTask = [];
  const rounds = []; // [{seg: {arm: [ms...]}}] — taskNet, the superseded diagnostic
  const roundsTask = []; // the same samples on UNSUBTRACTED TaskDuration (rf2-yd52q)
  // PER-BLOCK LayoutDuration. The decomposition below already reports layout
  // as a per-arm MEAN, which cannot be adjudicated: a control is a per-block
  // statistic and a pooled mean has no blocks. Collected here so the SAME
  // three-point statistic can be run on the layout counter alone, which is
  // what separates a workload finding from an instrument one (rf2-7iqb5).
  const roundsLayout = [];
  const inPageRounds = [];
  const decomposition = {}; // "seg/arm" -> accumulated style/layout/counts
  const canon = {}; // "seg/arm" -> {hash, bytes, control}
  const eventTiming = []; // raw PerformanceEventTiming records
  // GROUND TRUTH for the keystroke row: every warm key the driver pressed,
  // counted at the press. The published `n` is this array's length rather
  // than arithmetic over the design — the arithmetic is what got the arm
  // axis wrong and printed 180 for 540.
  const sentKeys = [];
  const census = {}; // "seg/arm" -> {query -> recomputes}, taken in a warm-up
  let position = 0;
  let previous = null;
  const granularity = new Set();

  const bump = (key, d) => {
    const acc = (decomposition[key] ||= {
      n: 0, taskNet: 0, task: 0, devtools: 0, script: 0, style: 0, layout: 0,
      layoutCount: 0, styleCount: 0, inPage: 0,
    });
    acc.n += 1;
    acc.taskNet += d.taskNet;
    acc.task += d.task;
    acc.devtools += d.devtools;
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
    const perSegTask = {};
    const perSegInPage = {};
    const perSegLayout = {};

    for (const seg of segOrder) {
      trace.step = `round ${round}, segment ${seg}`;
      await page.evaluate((s) => window.HCLOCK.enterSegment(s), seg);
      const plan = await page.evaluate(([r, s]) => window.HCLOCK.plan(r, s), [rowId, seg]);
      const armIds = plan.map((a) => a.id);
      armPlan = plan;

      if (round === 0) {
        for (const a of armIds) {
          const c = await page.evaluate(([r, arm]) => window.HCLOCK.canon(r, arm), [rowId, a]);
          canon[`${seg}/${a}`] = c;
        }
      }

      for (const a of armIds) await page.evaluate(([r, arm]) => window.HCLOCK.prepare(r, arm), [rowId, a]);

      // Per-arm accumulated field values, keystroke row only — ONE STRING
      // PER FIELD. The witness is validation.md's 4-field form, a sample
      // types into one field, and every sample reads all four back, so the
      // expectation has to carry all four.
      const typed = {};
      for (const a of armIds) typed[a] = Array(kbFields).fill('');

      const acc = {};
      const accTask = {};
      const accInPage = {};
      const accLayout = {};
      for (const a of armIds) {
        acc[a] = [];
        accTask[a] = [];
        accInPage[a] = [];
        accLayout[a] = [];
      }

      for (let s = 0; s < WARMUP + SAMPLES; s++) {
        for (const j of guard.schedule(armIds.length, s)) {
          const armId = armIds[j];
          trace.step = `round ${round}, segment ${seg}, sample ${s}, arm ${armId}`;
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
            // The field ROTATES with the sample, so all four are exercised
            // and the one-value-moves claim is checked at four different
            // indices rather than at one.
            const field = s % kbFields;
            const focused = await page.evaluate(
              ([arm, k]) => {
                window.__ETKEY = k;
                return window.HCLOCK.focusDraft(arm, k.field);
              },
              [armId, { seg, arm: armId, round, sampleIndex: s, field, warm: s >= WARMUP }]
            );
            if (!focused) {
              await page.close();
              throw new Error(
                `${seg}/${armId} round ${round} sample ${s}: field ${field} is not on the page, so the ` +
                  `key would have gone nowhere and the window would have measured the settle`
              );
            }
            // THE RECOMPUTE CENSUS rides the LAST WARM-UP sample: a real
            // keypress on the path the row publishes, and not one measured
            // sample carries its cost.
            if (s === censusAt) await page.evaluate(() => window.HCLOCK.censusStart());
            typed[armId][field] += 'a';
            await page.keyboard.press('a');
            if (s >= WARMUP) sentKeys.push({ seg, arm: armId, round, sampleIndex: s, field });
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
            // Not the tare: it presses no key, so it armed no census and an
            // empty one recorded against it would read as a measured zero.
            if (s === censusAt && armId !== PLUMB) {
              census[`${seg}/${armId}`] = await page.evaluate(() => window.HCLOCK.censusTake());
            }
            const drained = await page.evaluate(() => {
              const es = window.__ET;
              window.__ET = [];
              window.__ETKEY = null;
              return es;
            });
            for (const e of drained) {
              // The key was stamped IN THE PAGE when the entry was observed.
              // An entry with none is kept, marked warm, and refused by the
              // adjudicator — never quietly filtered into nonexistence.
              const k = e.key;
              eventTiming.push({
                name: e.name,
                startTime: e.startTime,
                processingStart: e.processingStart,
                processingEnd: e.processingEnd,
                duration: e.duration,
                interactionId: e.interactionId,
                seg: k ? k.seg : null,
                arm: k ? k.arm : null,
                round: k ? k.round : null,
                sampleIndex: k ? k.sampleIndex : null,
                warm: k ? k.warm : true,
              });
            }
          }

          if (s >= WARMUP) {
            const key = `${seg}/${armId}`;
            acc[armId].push(d.taskNet);
            accTask[armId].push(d.task);
            accLayout[armId].push(d.layout);
            if (Number.isFinite(inPageMs)) accInPage[armId].push(inPageMs);
            bump(key, d);
            samples.push({ arm: key, value: d.taskNet, predecessor: previous, position });
            samplesTask.push({ arm: key, value: d.task, predecessor: previous, position });
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
      perSegTask[seg] = accTask;
      perSegInPage[seg] = accInPage;
      perSegLayout[seg] = accLayout;
    }
    rounds.push(perSeg);
    roundsTask.push(perSegTask);
    inPageRounds.push(perSegInPage);
    roundsLayout.push(perSegLayout);
  }

  const tally = await page.evaluate(() => window.HCLOCK.tally());
  const residue = await page.evaluate(() => window.HCLOCK.residue());
  const runtime = await page.evaluate(() => window.HCLOCK.runtime());
  const etError = await page.evaluate(() => window.__ETERROR || null);
  await page.close();

  return {
    rowId, samples, samplesTask, rounds, roundsTask, roundsLayout, inPageRounds, decomposition, canon, tally, residue, runtime,
    eventTiming, sentKeys, census, kbShape, etError, pageErrors, armPlan, sabotage,
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
    rowId, samples, samplesTask, rounds, roundsTask, roundsLayout, inPageRounds, decomposition, canon, tally, residue, runtime,
    eventTiming, sentKeys, census, kbShape, etError, granularity, armPlan, sabotage,
  } = out;

  console.log(`;; ==== ROW ${rowId} ====`);
  console.log(`;; runtime  ${runtime}`);
  console.log(`;; residue  ${residue}`);
  console.log(`;; writes   ${tally.unverified} unverified of ${tally.writes}`);
  console.log(
    `;; clock    PUBLISHED: Performance.getMetrics raw TaskDuration, frame-settled (rAF + setTimeout) ` +
      `— the arm's script AND the frame it caused, main thread only, no raster/composite`
  );
  console.log(
    `;; clock    SUPERSEDED: taskNet = TaskDuration less DevToolsCommandDuration. Reported below as a ` +
      `frame-ONLY diagnostic — the subtraction removes the operation's own script (rf2-yd52q)`
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
  const inPageBar = {};
  for (const [num, den] of BAR_PAIRS) {
    const v = crossSegment(rounds, num, num, den, den, false);
    const rv = crossSegment(rounds, num, num, den, den, true);
    bar[`${num} / ${den}`] = { tared: v, untared: rv };
    barMeans[`${num} / ${den}`] = v.mean;
  }

  // --- the floor seam, its null, and the band a magnitude must clear ---------
  //
  // rf2-cvvb7 measured what this seam is and what it does. A nineteen-run
  // load ladder — 0, 2, 4, 8, 12 and 20 competing busy cores on a 24-core
  // box — moved the absolute floor by 80% and left the seam unmoved
  // (0.1–16.4%, no trend), and showed the seam is not attributable to the
  // segment under an exact within-round relabelling null. Its third finding,
  // that the perturbation is MULTIPLICATIVE and so cancels exactly, was
  // WITHDRAWN by `rf2-ymi6j`'s re-take: pure multiplicativity predicts
  // `ctl-2x / floor` = 2.00 with no variance, and nineteen fresh runs read
  // 1.71 [1.62 – 1.84]. What a bar row must clear is not the seam; it is the
  // part of a block's perturbation that survives dividing by that block's own
  // floor, and `seam.cjs` measures that on `ctl-2x / floor`.
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
  console.log(
    `;; ---- THE BAR: candidate against each donor, and the DONOR ROW itself, all floor-normalised ----`
  );
  for (const [num, den] of BAR_PAIRS) {
    const key = `${num} / ${den}`;
    const { tared: v, untared: rv } = bar[key];
    const adj = assessed.verdict.rows[key];
    console.log(
      `;;   ${key.padEnd(27)} ${v.mean.toFixed(4)}x [${v.min.toFixed(4)} – ${v.max.toFixed(4)}]` +
        `   (untared ${rv.mean.toFixed(4)}x [${rv.min.toFixed(4)} – ${rv.max.toFixed(4)}])` +
        (v.straddles1 ? '   — RANGE STRADDLES 1.0, indistinguishable at this n' : '')
    );
    console.log(`;;     ${adj.unadjudicated ? 'UNADJ  ' : adj.clear ? 'CLEARS ' : 'LIMITED'} ${adj.why}`);
    console.log(`;;     per-round ${v.perRound.join(', ')}`);
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
        // The two windows in MILLISECONDS as well as in ratios. A ratio says
        // the two clocks disagree; only the absolutes say WHAT they disagree
        // about — how much of each arm's frame lands inside `flushSync` — and
        // that is the whole mechanism behind a published row moving.
        const ipAbs = p50(inPageRounds.flatMap((r) => r[seg][arm]));
        const fiAbs = p50(rounds.flatMap((r) => r[seg][arm]));
        console.log(
          `;;   ${(seg + '/' + arm).padEnd(28)} in-page ${b.mean.toFixed(4)}x  vs  ` +
            `frame-inclusive ${fi.mean.toFixed(4)}x   (in-page reads ` +
            `${(((b.mean - fi.mean) / fi.mean) * 100).toFixed(1)}% differently)` +
            `   [abs ${ipAbs.toFixed(3)} of ${fiAbs.toFixed(3)} ms = ` +
            `${((ipAbs / fiAbs) * 100).toFixed(0)}% of the frame]`
        );
      }
      // The floor's own two windows, because every ratio above is taken
      // against it and its in-page share is the smaller half of why the two
      // clocks rank these arms differently.
      const ipF = p50(inPageRounds.flatMap((r) => r[seg][FLOOR]));
      const fiF = p50(rounds.flatMap((r) => r[seg][FLOOR]));
      console.log(
        `;;   ${(seg + '/floor').padEnd(28)} in-page 1.0000x  vs  frame-inclusive 1.0000x   (the denominator)` +
          `   [abs ${ipF.toFixed(3)} of ${fiF.toFixed(3)} ms = ${((ipF / fiF) * 100).toFixed(0)}% of the frame]`
      );
    }
    // THE BAR ROWS ON BOTH CLOCKS. Per-arm gaps are not the comparison a
    // published row is quoted at: the row IS a bar, and the in-page window's
    // error only matters to the extent it fails to cancel between the two
    // legs. Printing the bar both ways answers that directly, and it is what
    // `rf2-8nqsl` had to compute by hand.
    const ipRatio = (seg, arm) => inPageRounds.map((r) => p50(r[seg][arm]) / p50(r[seg][FLOOR]));
    for (const [num, den] of BAR_PAIRS) {
      const n = ipRatio(num, num);
      const d = ipRatio(den, den);
      if (!n.every(Number.isFinite) || !d.every(Number.isFinite)) continue;
      const b = band(n.map((x, i) => x / d[i]));
      const fi = bar[`${num} / ${den}`].tared;
      console.log(
        `;;   BAR ${(num + ' / ' + den).padEnd(24)} in-page ${b.mean.toFixed(4)}x ` +
          `[${b.min.toFixed(4)} – ${b.max.toFixed(4)}]  vs  frame-inclusive ${fi.mean.toFixed(4)}x ` +
          `[${fi.min.toFixed(4)} – ${fi.max.toFixed(4)}]   (in-page reads ` +
          `${(((b.mean - fi.mean) / fi.mean) * 100).toFixed(1)}% differently)`
      );
      inPageBar[`${num} / ${den}`] = b;
    }
  }

  // --- THE SAME SAMPLES ON UNSUBTRACTED TaskDuration (rf2-yd52q) -------------
  //
  // `taskNet` subtracts `DevToolsCommandDuration`, and this run measured what
  // that subtraction actually removes. Chromium bills a `Runtime.callFunctionOn`
  // to `DevToolsCommandDuration` INCLUDING the page script the command invokes
  // — and this driver invokes every arm's operation through exactly that door.
  // So the excess of an arm's `devtools` term over the tare's baseline tracks
  // that arm's own in-page window: on one bulk300 run, floor 0.62 ms against an
  // in-page 0.40, `reagent-subs` 2.76 against 2.30, `uix-subs` 2.01 against
  // 1.60, `hicasso` 3.26 against 2.80.
  //
  // The consequence is not small. `taskNet` is style + layout + paint with the
  // OPERATION'S OWN SCRIPT REMOVED — a frame-ONLY clock, not a frame-inclusive
  // one — and it is not a superset of the in-page window but very nearly its
  // complement. That is visible without any of this arithmetic: on a substrate
  // arm the in-page absolute exceeds the `taskNet` absolute, which no superset
  // can do.
  //
  // Raw `TaskDuration` is the quantity that was wanted: the arm's script AND
  // the frame it caused, in one number, with the protocol's own round trip
  // carried by the tare exactly as before. It is reported here beside the
  // banked reading rather than replacing it, because every other row this
  // driver has published is stated on `taskNet` and a silent swap would
  // re-state them without saying so.
  const barTask = {};
  const barTaskMeans = {};
  for (const [num, den] of BAR_PAIRS) {
    barTask[`${num} / ${den}`] = crossSegment(roundsTask, num, num, den, den, false);
    barTaskMeans[`${num} / ${den}`] = barTask[`${num} / ${den}`].mean;
  }
  const assessedTask = seamlib.assess({
    floorBlocks: roundsTask.map((r) => SEGMENTS.map((s) => r[s][FLOOR])),
    floorCells: roundsTask.map((_, i) => SEGMENTS.map((s) => tared(roundsTask, s, FLOOR, i))),
    fixedCells: hasProportionalControl
      ? roundsTask.map((_, i) => SEGMENTS.map((s) => tared(roundsTask, s, 'ctl-2x', i)))
      : null,
    bars: barTaskMeans,
    noFixedPairWhy: "this row's control burns a fixed 50 ms rather than doubling the page",
  });
  console.log(
    `;; ---- the SAME samples on UNSUBTRACTED TaskDuration — script AND frame (rf2-yd52q) ----`
  );
  console.log(
    `;;   DevToolsCommandDuration carries the arm's own script, because the driver runs every ` +
      `operation inside a protocol command. Subtracting it takes the script out.`
  );
  // ABSOLUTES BESIDE EVERY RATIO, and the ordering is the lesson rather than
  // a preference. BOTH of this instrument's defects — the in-page window that
  // could not see the frame, and the subtraction that removed the script —
  // were plainly visible in the milliseconds from run 1 and invisible in the
  // ratio that was printed instead. A ratio cannot be sanity-checked against
  // anything; `2.938 > 2.466` refutes a superset claim on sight.
  const plumbTaskAll = p50(roundsTask.flatMap((r) => SEGMENTS.flatMap((s) => r[s][PLUMB])));
  for (const seg of SEGMENTS) {
    for (const arm of armsOf(seg)) {
      if (arm === PLUMB) continue;
      const b = band(ratioToFloor(roundsTask, seg, arm));
      const nb = band(ratioToFloor(rounds, seg, arm));
      const absTask = p50(roundsTask.flatMap((r) => r[seg][arm]));
      const absNet = p50(rounds.flatMap((r) => r[seg][arm]));
      const absIn = inPageRounds.length ? p50(inPageRounds.flatMap((r) => r[seg][arm] || [])) : NaN;
      console.log(
        `;;   ${(seg + '/' + arm).padEnd(28)} ${b.mean.toFixed(4)}x floor [${b.min.toFixed(4)} – ${b.max.toFixed(4)}]` +
          `   (on taskNet ${nb.mean.toFixed(4)}x)   ABS task ${absTask.toFixed(3)} ms` +
          ` (tared ${(absTask - plumbTaskAll).toFixed(3)}) = taskNet ${absNet.toFixed(3)}` +
          ` + in-page ${Number.isFinite(absIn) ? absIn.toFixed(3) : 'n/a'}`
      );
    }
  }
  console.log(
    `;;   ${'(tare) plumb'.padEnd(28)} ABS task ${plumbTaskAll.toFixed(4)} ms — subtracted from every ratio above`
  );
  const bandTask = assessedTask.bandStats.band;
  console.log(
    `;;   band ${Number.isFinite(bandTask) ? (bandTask * 100).toFixed(1) + '%' : 'n/a'} on this clock ` +
      `(ctl-2x/floor p50 ${assessedTask.bandStats.p50 ? assessedTask.bandStats.p50.toFixed(4) : 'n/a'})`
  );
  for (const [num, den] of BAR_PAIRS) {
    const key = `${num} / ${den}`;
    const v = barTask[key];
    const adj = assessedTask.verdict.rows[key];
    console.log(
      `;;   BAR ${key.padEnd(24)} ${v.mean.toFixed(4)}x [${v.min.toFixed(4)} – ${v.max.toFixed(4)}]` +
        `   (on taskNet ${bar[key].tared.mean.toFixed(4)}x)`
    );
    console.log(`;;     ${adj.unadjudicated ? 'UNADJ  ' : adj.clear ? 'CLEARS ' : 'LIMITED'} ${adj.why}`);
  }
  // GUARDED, and the guard is the row's rather than the arm-name's: a row
  // without a proportional control has no `ctl-2x` arm at all — `keystroke`
  // has `ctl-50ms` — so reaching for one outside this branch reads an
  // undefined arm and dies mid-row. Which is what it did, and what the
  // keystroke smoke was run to find.
  const ctlTask = hasProportionalControl
    ? controlVerdict(2.0, SEGMENTS.flatMap((seg) => ratioToFloor(roundsTask, seg, 'ctl-2x')), CONTROL_SLACK)
    : null;
  if (ctlTask) {
    console.log(
      `;;   CONTROL on this clock: ${ctlTask.ok ? 'PASS' : 'FAIL'} ${ctlTask.measured.mean}x ` +
        `[${ctlTask.measured.min} – ${ctlTask.measured.max}] against 2.00x +/-${CONTROL_SLACK * 100}%, ` +
        `${ctlTask.rule}`
    );
  }

  // --- where the time goes --------------------------------------------------
  console.log(`;; ---- decomposition, mean ms per sample ----`);
  for (const [k, a] of Object.entries(decomposition)) {
    console.log(
      `;;   ${k.padEnd(28)} taskNet ${(a.taskNet / a.n).toFixed(4)}  = task ${(a.task / a.n).toFixed(4)} ` +
        `less devtools ${(a.devtools / a.n).toFixed(4)}   script ${(a.script / a.n).toFixed(4)}  ` +
        `style ${(a.style / a.n).toFixed(4)}  layout ${(a.layout / a.n).toFixed(4)}  ` +
        `layouts/sample ${(a.layoutCount / a.n).toFixed(2)}`
    );
  }

  // --- event timing ---------------------------------------------------------
  let etVerdict = null;
  let kbVerdict = null;
  if (rowId === 'keystroke') {
    if (etError) console.log(`;;   observer error: ${etError}`);
    // ONE RECORD PER PHYSICAL KEY, and the driver's own press count is the
    // denominator. `clock_witness.cjs` owns every rule here — which entries
    // form an interaction, what a censored key is, what the recompute census
    // has to say — because an adjudicator that only runs behind a browser is
    // an adjudicator nobody has watched refuse.
    kbVerdict = kbwitness.adjudicate({
      sent: sentKeys,
      entries: eventTiming,
      census,
      shape: {
        cells: kbShape.cells,
        fields: kbShape.fields,
        substrate: SEGMENTS,
        floors: [FLOOR, 'ctl-50ms'],
      },
    });
    for (const line of kbwitness.format(kbVerdict)) console.log(line);
    const names = {};
    for (const e of eventTiming.filter((x) => x.warm)) names[e.name] = (names[e.name] || 0) + 1;
    console.log(`;;   event names seen: ${Object.entries(names).map(([n, c]) => `${n}x${c}`).join(', ')}`);

    // THE PREDICTED CONTROL for this instrument, on the repaired records.
    const ctl = kbVerdict.records.filter((r) => r.arm === 'ctl-50ms');
    const sawIt = ctl.length > 0 && p50(ctl.map((e) => e.duration)) >= CTL_BUSY_MS - 2;
    etVerdict = {
      predicted: `ctl-50ms produces one Event Timing interaction per physical key whose duration p50 is >= ${CTL_BUSY_MS - 2} ms`,
      measured: ctl.length ? `n=${ctl.length}, p50 ${p50(ctl.map((e) => e.duration)).toFixed(1)} ms` : 'no interactions',
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

  // --- THE ADDITIVE CONSTANT, measured two independent ways ------------------
  //
  // rf2-emvod inferred `c` by inverting the doubling control. That inference
  // is reproduced here from THIS run's own readings rather than quoted, and
  // it is then checked against a second estimate that shares none of its
  // arithmetic: the intercept of a line fitted through the three-point
  // control's arms at fixed page size. The two are not the same quantity and
  // the table says which is which —
  //
  //   c(2x)  the part of a floor sample that does NOT scale when the PAGE
  //          doubles: harness, protocol round trip, settle, document-level
  //          pre-paint. React's reconciliation walk is NOT in it, because
  //          the walk doubles with the page.
  //   c(3pt) the part that does not scale when the DIRTY SET grows at fixed
  //          page size — all of the above PLUS the whole-tree walk, which is
  //          why it is the larger of the two and must be.
  //
  // `c(3pt) > c(2x)` is therefore a PREDICTION of the model, and an ordering
  // the other way would refute it. It is printed as a verdict rather than
  // left for a reader to check.
  let constants = null;
  if (hasProportionalControl) {
    const rows2x = [];
    for (let i = 0; i < roundsTask.length; i++) {
      for (const seg of SEGMENTS) {
        const fl = tared(roundsTask, seg, FLOOR, i);
        const R = tared(roundsTask, seg, 'ctl-2x', i) / fl;
        rows2x.push({ seg, round: i, floorTared: fl, ratio: R, c: additiveConstant(fl, R) });
      }
    }
    constants = { c2x: band(rows2x.map((x) => x.c)), ratio2x: band(rows2x.map((x) => x.ratio)), floorTared: band(rows2x.map((x) => x.floorTared)) };
    console.log(`;; ---- THE ADDITIVE CONSTANT, re-measured on this run (rf2-emvod's arithmetic, not its numbers) ----`);
    console.log(
      `;;   c(2x) = floor_tared x (2 - ctl2x/floor) = ${constants.c2x.mean.toFixed(4)} ms ` +
        `[${constants.c2x.min.toFixed(4)} – ${constants.c2x.max.toFixed(4)}] over ${rows2x.length} blocks`
    );
    console.log(
      `;;         from floor_tared ${constants.floorTared.mean.toFixed(4)} ms ` +
        `[${constants.floorTared.min.toFixed(4)} – ${constants.floorTared.max.toFixed(4)}] and ` +
        `ctl-2x/floor ${constants.ratio2x.mean.toFixed(4)}x ` +
        `[${constants.ratio2x.min.toFixed(4)} – ${constants.ratio2x.max.toFixed(4)}]`
    );
    console.log(
      `;;         c/W = ${(constants.c2x.mean / (constants.floorTared.mean - constants.c2x.mean)).toFixed(4)}, ` +
        `i.e. ${((constants.c2x.mean / constants.floorTared.mean) * 100).toFixed(1)}% of a floor sample does not ` +
        `scale with the page — which is the whole of why a doubling control cannot read 2.00`
    );
  }

  // --- THE THREE-POINT CONTROL ----------------------------------------------
  const ctl3 = ctl3Verdict(roundsTask, armPlan, CONTROL_SLACK);
  const ctl3Net = ctl3 ? ctl3Verdict(rounds, armPlan, CONTROL_SLACK) : null;
  const ctl3Layout = ctl3 ? ctl3Verdict(roundsLayout, armPlan, CONTROL_SLACK) : null;
  // THE CONTROL'S ARMS MUST BUILD ONE PAGE AS EACH OTHER. They are exempt
  // from the cross-arm canonical-DOM gate because their page is not the
  // floor's — that is the price of the page size the signal needed — so the
  // guarantee is re-established where it still means something: four arms
  // compared only with one another have to be four renderings of the same
  // page, and a row whose control arms disagree is refused.
  let ctl3Parity = null;
  if (ctl3) {
    const ids = new Set([...Object.keys(ctl3.dirty), ctl3.arms.witness].filter(Boolean));
    const hs = Object.entries(canon).filter(([k]) => ids.has(k.split('/')[1]));
    const uniq = [...new Set(hs.map(([, c]) => c.hash))];
    ctl3Parity = { arms: hs.length, hashes: uniq.length, ok: uniq.length === 1, bytes: hs.length ? hs[0][1].bytes : 0 };
  }
  if (ctl3) {
    const d = ctl3.dirty;
    const cells = (armPlan.find((a) => a.ctl3) || {}).cells;
    console.log(
      `;; ---- THREE-POINT CONTROL: dirty ${Object.values(d).join(' / ')} of ${cells} boundaries, FIXED ` +
        `page size — the floor's own page, so the canonical-DOM gate CHECKS these arms ----`
    );
    console.log(
      `;;   parity   ${ctl3Parity.arms} control arms across ${SEGMENTS.length} segments, canonical DOM ` +
        `${ctl3Parity.ok ? 'IDENTICAL' : 'DISAGREES — ' + ctl3Parity.hashes + ' distinct pages'} ` +
        `(${ctl3Parity.bytes} bytes) — and they are ALSO inside the cross-arm gate above, because choosing ` +
        `the dirty set as the axis is what let the control keep the floor's page.`
    );
    console.log(`;;   door     ${ctl3.door}`);
    console.log(
      `;;   statistic (T(${Object.values(d)[2]}) - T(${Object.values(d)[0]})) / ` +
        `(T(${Object.values(d)[1]}) - T(${Object.values(d)[0]})) — a DIFFERENCE OF DIFFERENCES, so an additive ` +
        `constant cancels in each half and a multiplicative block perturbation cancels in the quotient`
    );
    console.log(
      `;;   predicted ${ctl3.predicted}x = (${Object.values(d)[2]} - ${Object.values(d)[0]}) / ` +
        `(${Object.values(d)[1]} - ${Object.values(d)[0]}), DERIVED from the page's own declared counts` +
        (sabotage ? `   [SABOTAGE: the page actually renders ${sabotage}]` : '')
    );
    // ABSOLUTES FIRST. A difference of differences fails silently when its
    // denominator shrinks into the noise, and the ratio cannot show that.
    console.log(
      `;;   signal   numerator ${ctl3.signal.numMs.mean.toFixed(4)} ms ` +
        `[${ctl3.signal.numMs.min.toFixed(4)} – ${ctl3.signal.numMs.max.toFixed(4)}], ` +
        `denominator ${ctl3.signal.denMs.mean.toFixed(4)} ms ` +
        `[${ctl3.signal.denMs.min.toFixed(4)} – ${ctl3.signal.denMs.max.toFixed(4)}] — ` +
        `the differencing throws away everything else, so this is what is left to measure with`
    );
    console.log(
      `;;   fit      ${ctl3.signal.slopeUsPerCell.mean.toFixed(3)} µs per dirty cell ` +
        `[${ctl3.signal.slopeUsPerCell.min.toFixed(3)} – ${ctl3.signal.slopeUsPerCell.max.toFixed(3)}], ` +
        `intercept c(3pt) ${ctl3.signal.interceptMs.mean.toFixed(4)} ms ` +
        `[${ctl3.signal.interceptMs.min.toFixed(4)} – ${ctl3.signal.interceptMs.max.toFixed(4)}] ` +
        `over the control's own three points`
    );
    // THE REGIME TABLE — the reason the epsilon point is not one cell.
    // The control's two intervals must agree with each other (that is the
    // statistic restated); the witness interval below them is expected NOT
    // to, and an agreement there would refute the paint-saturation account
    // that put the control where it is.
    const m = ctl3.marginal;
    // NB not `disagree` — that name is the canonical-DOM gate's, above.
    const margGap = (x) => (Math.abs(x.upper.mean - x.lower.mean) / ((x.upper.mean + x.lower.mean) / 2)) * 100;
    console.log(
      `;;   REGIME   marginal µs per dirty cell — [${Object.values(d)[0]}–${Object.values(d)[1]}] ` +
        `${m.lower.mean.toFixed(3)}, [${Object.values(d)[1]}–${Object.values(d)[2]}] ${m.upper.mean.toFixed(3)} ` +
        `— they disagree by ${margGap(m).toFixed(1)}%, and the statistic above IS that disagreement`
    );
    if (constants) {
      // THE INVERSION IS DEGENERATE WHEN THE DOUBLING CONTROL IS NOISE.
      // `c = floor x (2 - R)` runs backwards through a ratio, so a block
      // whose `R` overshot 2.0 returns a NEGATIVE constant — which is not a
      // small constant, it is a statement that the model does not apply to
      // that block. Adjudicating the ordering against a `c(2x)` whose range
      // contains a negative would be reading a refutation out of arithmetic
      // that has already broken down, so the verdict is withheld and says
      // so. This is the guard, not a get-out: a run with a clean positive
      // `c(2x)` gets the ordering adjudicated and can refute the model.
      const usable = constants.c2x.min > 0;
      const ordered = ctl3.signal.interceptMs.mean > constants.c2x.mean;
      console.log(
        `;;   c ORDER  c(3pt) ${ctl3.signal.interceptMs.mean.toFixed(4)} ms vs c(2x) ` +
          `${constants.c2x.mean.toFixed(4)} ms, both tared — and they are constants of DIFFERENT PAGES, so ` +
          `this is the weak form of the check and is labelled as such: c(3pt) carries React's whole-tree ` +
          `walk over 3,000 elements and c(2x) carries only what survives doubling a 300-element page, so ` +
          `c(3pt) > c(2x) is expected and its failure would be a real signal while its success proves little. ` +
          (usable
            ? `${ordered ? 'AS PREDICTED.' : 'NOT AS PREDICTED — worth chasing.'}`
            : `WITHHELD: c(2x) ranges to ${constants.c2x.min.toFixed(4)} ms, and a negative recovered constant ` +
              `means the doubling control overshot 2.0 in some block and its inversion has broken down there. ` +
              `Nothing is adjudicated against a degenerate estimate.`)
      );
      constants.c3pt = ctl3.signal.interceptMs;
      constants.orderAsPredicted = usable ? ordered : null;
      constants.orderUsable = usable;
    }
    // THE SAME STATISTIC ON THE LAYOUT COUNTER ALONE. This is the line that
    // decides whether a refusal is about the INSTRUMENT or about the
    // WORKLOAD, and it is the question a three-point control cannot answer
    // by itself. `LayoutDuration` is the part of a commit that must scale
    // with the dirty set — d dirty rows, d relayouts — while paint does not
    // once the damage region covers the viewport. If the control refuses on
    // `task` and PASSES on `layout`, the arithmetic is sound and the page's
    // cost simply is not affine in the dirty set; if it refuses on both,
    // the fault is upstream of the workload.
    if (ctl3Layout) {
      console.log(
        `;;   MECHANISM the same statistic on LayoutDuration alone: ${ctl3Layout.ok ? 'PASS' : 'FAIL'} ` +
          `${ctl3Layout.measured.mean.toFixed(4)}x [${ctl3Layout.measured.min.toFixed(4)} – ` +
          `${ctl3Layout.measured.max.toFixed(4)}], marginal ${ctl3Layout.marginal.lower.mean.toFixed(3)} then ` +
          `${ctl3Layout.marginal.upper.mean.toFixed(3)} µs per dirty cell — a ` +
          `${margGap(ctl3Layout.marginal).toFixed(1)}% disagreement against ${margGap(m).toFixed(1)}% on task`
      );
      console.log(
        `;;            layout is the half of a commit that MUST scale with the dirty set; paint is the half ` +
          `that stops scaling once the damage region covers the viewport. A control that holds on layout and ` +
          `refuses on task is reporting the PAGE, not the clock.`
      );
    }
    console.log(
      `;;   ${ctl3.ok ? 'PASS' : 'FAIL'}     measured ${ctl3.measured.mean.toFixed(4)}x ` +
        `[${ctl3.measured.min.toFixed(4)} – ${ctl3.measured.max.toFixed(4)}] against band ` +
        `[${ctl3.band[0]} – ${ctl3.band[1]}] over ${ctl3.perRound.length} blocks (${ctl3.rule})`
    );
    console.log(`;;   per-block ${ctl3.perRound.join(', ')}`);
    if (ctl3Net) {
      console.log(
        `;;   the same statistic on the superseded taskNet clock: ${ctl3Net.ok ? 'PASS' : 'FAIL'} ` +
          `${ctl3Net.measured.mean.toFixed(4)}x — reported as a diagnostic, never as the verdict`
      );
    }
    // SIDE BY SIDE with the control it replaces, on the SAME samples. The
    // claim is not that the new control is kinder; it is that the old one was
    // reading a quantity it could not correct for.
    if (ctlTask) {
      console.log(
        `;;   vs ctl-2x on the SAME samples: ${ctlTask.ok ? 'PASS' : 'FAIL'} ${ctlTask.measured.mean}x ` +
          `against 2.00x — the gap is c/(W + c), and it is the same samples, the same blocks, one clock`
      );
    }
  }

  // --- the arm-order guard, on BOTH clocks ----------------------------------
  //
  // The guard was only ever run on `taskNet`. That was defensible while
  // `taskNet` was the published figure and is not now: the claim a guard
  // certifies — this arm does not read differently for WHERE in the plan it
  // was measured — is a claim about a particular quantity, and the quantity
  // now published is raw `TaskDuration`. Both verdicts are printed and either
  // refusal refuses the row (rf2-emvod).
  const v = guard.verdict(samples, { tolerance: 0.1 });
  for (const line of guard.format(v, `${rowId} — taskNet (superseded)`)) console.log(line);
  const vTask = guard.verdict(samplesTask, { tolerance: 0.1 });
  for (const line of guard.format(vTask, `${rowId} — raw TaskDuration (PUBLISHED)`)) console.log(line);

  return {
    bar, inPageBar, barTask, ctlTask, bandTask, ctlVerdict, etVerdict, kbVerdict, guardVerdict: v,
    guardVerdictTask: vTask, ctl3, ctl3Net, ctl3Layout, ctl3Parity, constants, sabotage,
    seamTask: {
      band: Number.isFinite(assessedTask.bandStats.band) ? r4(assessedTask.bandStats.band) : null,
      ceilingBreached: assessedTask.verdict.ceilingBreached,
      rows: assessedTask.verdict.rows,
    },
    parityOk: disagree.length === 0, tally, seam,
  };
}

// ---------------------------------------------------------------------------

/**
 * THE RUN'S FINAL DECISION, as a pure function of a flat per-row summary.
 *
 * Everything above this point is a WHOLE-RUN gate — a page that threw, a
 * guard refusal, two arms building different pages, an unverified write, a
 * band over the ceiling — and each takes its own exit where it is found.
 * What is left is the pair of judgements that are about a ROW, and they are
 * taken here, together, because the defect this function exists to prevent
 * is one of them being computed and never reaching the exit code.
 *
 * `rows` is one entry per measured row:
 *
 *   rowId             the row's id, as printed
 *   ctlOk             its positive control saw the change its arithmetic predicts
 *   ctlNote           the parenthetical a control refusal prints, if any
 *   adjudicable       at least one PUBLISHED bar carries a band
 *   unadjudicatedWhy  why it does not, in the adjudicator's own words
 *
 * EXIT 1 HERE IS PER-ROW, AND THE ROWS THAT PASSED ARE STILL ROWS. Reaching
 * this point means every whole-run gate cleared on every row; these two are
 * the only ones this driver scopes to the row that failed them, because they
 * are the only ones whose claim is about a row.
 */
function reportability(rows, opts) {
  const list = Array.isArray(rows) ? rows : [];
  const sabotage = (opts && opts.sabotage) || null;
  const ctlFailed = list.filter((r) => !r.ctlOk);
  const unadjudicated = list.filter((r) => !r.adjudicable);
  const passed = list.filter((r) => r.ctlOk && r.adjudicable).map((r) => r.rowId);
  const lines = [];
  if (ctlFailed.length > 0) {
    lines.push(
      `[clock] FAILED: the positive control did not see the change its own arithmetic predicts on: ` +
        `${ctlFailed.map((r) => `${r.rowId}${r.ctlNote || ''}`).join(', ')}. ` +
        `No MAGNITUDE from those rows is reportable.`
    );
    if (sabotage) {
      lines.push(
        `[clock] HCLOCK_CTL3_SABOTAGE=${sabotage} WAS SET: the control's arms rendered ${sabotage} cells ` +
          `while declaring 200. This refusal is the DEMONSTRATION that the control can fail, and this run is not ` +
          `a measurement of anything.`
      );
    }
  }
  // AND A ROW MAY HAVE NO ADJUDICABLE BAR AT ALL (rf2-y7mw7). This is a
  // different claim from the control's and it used to reach nothing: the
  // driver labelled every bar on such a row UNADJUDICATED four hundred lines
  // above, then exited 0 because the control had passed. A control that
  // passes certifies the instrument had SIGNAL; it does not supply the band a
  // magnitude is adjudicated AGAINST, and a driver that cannot adjudicate a
  // figure may not announce it. Refused after the control and never instead
  // of it — a row can fail both, and both are said.
  if (unadjudicated.length > 0) {
    lines.push(
      `[clock] FAILED: no published bar can be ADJUDICATED on: ` +
        `${unadjudicated.map((r) => r.rowId).join(', ')}. The row has a magnitude and nothing to tell it ` +
        `from parity, so no figure from it is reportable — a passing control is not a band:`
    );
    for (const r of unadjudicated) {
      lines.push(`[clock]   ${r.rowId}: ${r.unadjudicatedWhy || 'no proportional control on this row'}`);
    }
  }
  if (ctlFailed.length === 0 && unadjudicated.length === 0) return { code: 0, lines };
  lines.push(
    passed.length > 0
      ? `[clock] REPORTABLE: ${passed.join(', ')} — control passed, guard clean, canonical DOM identical, ` +
          `0 unverified, and every published bar adjudicated against this run's own band. ` +
          `Publish those and mark the rest.`
      : `[clock] REPORTABLE: none.`
  );
  return { code: 1, lines };
}

/**
 * THE DECISION'S OWN FIXTURES, in the idiom of every other adjudicator here:
 * the refusals stated as cases rather than as prose, run by `--selftest`
 * before a browser opens and by `clock_exit_path.test.cjs` in CI.
 *
 * The case that matters is the FIRST one. It is the run this driver actually
 * produces under `HCLOCK_ONLY=keystroke` — both controls pass, every whole-run
 * gate clears, and every bar comes back UNADJUDICATED — and until rf2-y7mw7 it
 * printed `[clock] ok` and exited 0.
 */
function reportabilitySelfTest() {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: !!ok, detail: detail || '' });
  const KEYSTROKE_WHY = "UNADJUDICATED — this row's control burns a fixed 50 ms rather than doubling the page";
  const row = (over) => ({ rowId: 'M1', ctlOk: true, ctlNote: '', adjudicable: true, ...over });
  const keystroke = () => row({ rowId: 'keystroke', adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY });

  const green = reportability([row({}), row({ rowId: 'bulk300' })]);
  check('a run whose every row is adjudicated exits 0 and says nothing', green.code === 0 && green.lines.length === 0);

  const unadj = reportability([keystroke()]);
  check(
    'a row whose every bar is UNADJUDICATED cannot exit 0 — the case that used to be green',
    unadj.code !== 0,
    `code ${unadj.code}`
  );
  check(
    'and the refusal NAMES the row and the adjudicator\'s own reason',
    /no published bar can be ADJUDICATED on: keystroke/.test(unadj.lines[0] || '') &&
      unadj.lines.some((l) => l.includes(KEYSTROKE_WHY)),
    unadj.lines.join(' | ')
  );
  check(
    'a row with nothing to publish is not announced as REPORTABLE',
    unadj.lines[unadj.lines.length - 1] === '[clock] REPORTABLE: none.',
    unadj.lines[unadj.lines.length - 1]
  );

  const mixed = reportability([row({}), keystroke()]);
  check(
    'an unadjudicated row refuses the run while the adjudicated rows stay reportable',
    mixed.code !== 0 && /REPORTABLE: M1 —/.test(mixed.lines[mixed.lines.length - 1]),
    mixed.lines[mixed.lines.length - 1]
  );
  check(
    'and REPORTABLE no longer says "publish" without saying "adjudicated"',
    /every published bar adjudicated against this run's own band/.test(mixed.lines[mixed.lines.length - 1]),
    mixed.lines[mixed.lines.length - 1]
  );

  // THE CONTROL GATE IS UNCHANGED, which is the other half of the repair: the
  // bar-level verdict was ADDED to the exit code, not substituted for the
  // row-level one.
  const ctl = reportability([row({ ctlOk: false, ctlNote: ' (three-point 1.2134x vs 2.0101x)' })]);
  check(
    'a failed positive control still refuses, alone, exactly as before',
    ctl.code === 1 && /the positive control did not see the change/.test(ctl.lines[0] || ''),
    ctl.lines.join(' | ')
  );
  const sab = reportability([row({ ctlOk: false })], { sabotage: 140 });
  check(
    'the falsification knob still says the run was a falsification',
    sab.code === 1 && sab.lines.some((l) => l.includes('HCLOCK_CTL3_SABOTAGE=140')),
    sab.lines.join(' | ')
  );

  const both = reportability([row({ rowId: 'keystroke', ctlOk: false, adjudicable: false, unadjudicatedWhy: KEYSTROKE_WHY })]);
  check(
    'a row that fails BOTH is refused for both — neither verdict masks the other',
    both.code === 1 &&
      both.lines.some((l) => l.includes('the positive control did not see the change')) &&
      both.lines.some((l) => l.includes('no published bar can be ADJUDICATED')),
    both.lines.join(' | ')
  );

  const empty = reportability([]);
  check('a run that took no rows is not a refusal', empty.code === 0 && empty.lines.length === 0);
  check('and neither is one that never ran', reportability(undefined).code === 0);

  return { checks };
}

// ---------------------------------------------------------------------------

async function main() {
  // THE ADJUDICATORS' SELF-TESTS, and they run before anything is built or
  // launched. `ctl3SelfTest` is the one that matters for this driver's new
  // control: its cases are the REFUSALS — superlinear work, an arm that does
  // not do what it declares, a degenerate denominator, one bad block out of
  // nine — stated as fixtures, plus the case that shows the doubling control
  // failing on a world the three-point one passes.
  const c3st = ctl3SelfTest();
  const badCtl3 = c3st.checks.filter((c) => !c.ok);
  for (const c of c3st.checks) console.error(`[clock] ctl3 self-test  ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}`);
  if (badCtl3.length > 0) {
    console.error(`[clock] the three-point control's own self-test FAILED: ${badCtl3.map((c) => c.name).join(', ')}`);
    process.exit(1);
  }
  console.error(`[clock] three-point control self-test: ${c3st.checks.length} checks, all ok`);

  // The keystroke witness's fixtures run on EVERY invocation, not only under
  // `--selftest`, for the three-point control's reason: they are cheap, they
  // are the only thing that has ever seen this adjudicator refuse, and a run
  // whose adjudicator is broken should never reach a browser.
  const kbst = kbwitness.selfTest();
  const badKb = kbst.checks.filter((c) => !c.ok);
  for (const c of kbst.checks) console.error(`[clock] kb self-test    ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}`);
  if (badKb.length > 0) {
    console.error(`[clock] the keystroke witness's own self-test FAILED: ${badKb.map((c) => c.name).join(', ')}`);
    process.exit(1);
  }
  console.error(`[clock] keystroke witness self-test: ${kbst.checks.length} checks, all ok`);

  if (SELFTEST_ONLY) {
    const g = guard.selfTest();
    const s = seamlib.selfTest();
    // AND THE DECISION ITSELF (rf2-y7mw7). Every other self-test here asks
    // whether an adjudicator can refuse; this one asks whether its refusal
    // reaches the exit code, which is the fault the other twenty-three could
    // not have caught.
    const x = reportabilitySelfTest();
    for (const c of g.checks) console.error(`[clock] guard self-test ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}`);
    for (const c of s.checks) console.error(`[clock] seam self-test  ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}`);
    for (const c of x.checks) console.error(`[clock] exit self-test  ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.ok ? '' : ` — ${c.detail}`}`);
    const bad = [...g.checks, ...s.checks, ...x.checks].filter((c) => !c.ok);
    console.error(`[clock] --selftest: ${bad.length === 0 ? 'ALL ADJUDICATORS OK' : 'FAILURES: ' + bad.length}`);
    process.exit(bad.length === 0 ? 0 : 1);
  }

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
  for (const line of [
    `ctl-3pt    = THE REPAIR (rf2-7iqb5, rf2-5xrcd). Three arms on the FLOOR'S OWN PAGE — 300 boundaries,`,
    `             901 elements, canonical-DOM identical and NOT exempted from the fairness gate — dirtying`,
    `             1, 100 and 200 of them per commit. Adjudicated as (T(200)-T(1))/(T(100)-T(1)) -> 2.0101x,`,
    `             +/-${CONTROL_SLACK * 100}%, EVERY block. The constant is not estimated or bounded: it CANCELS.`,
    `WHAT IT CATCHES, exactly: with near-equal spacing the statistic is 1 + the ratio of the two intervals'`,
    `             marginal costs, so the band is a 50% tolerance on the marginal cost moving between them.`,
    `             At these points a power law d^k reads (200^k-1)/(100^k-1), refused below k~0.55 and above`,
    `             k~1.33. An equally spaced 1:2:3 design could refuse NEITHER sublinear case — its reading`,
    `             never falls below ln3/ln2 = 1.585 — which is why the ruled placement is kept.`,
    `WHAT IT CANNOT DO: it certifies the composite of INSTRUMENT and WORKLOAD. A refusal does not by itself`,
    `             say which of the two bent, so the SAME statistic is run on LayoutDuration alone and`,
    `             printed beside it. Layout must scale with the dirty set; paint stops scaling once the`,
    `             damage region covers the viewport. Holding on layout while refusing on task is a finding`,
    `             about the PAGE. And it certifies no MOUNT row: a mount has no changed-set axis, so M1`,
    `             keeps ctl-2x and keeps its known undershoot.`,
    `ctl-3pt CAN FAIL, and here is how to make it: HCLOCK_CTL3_SABOTAGE=140 makes the 2D arm render 140`,
    `             cells while still declaring 200. Canonical DOM identical, read-back verified, arm-order`,
    `             guard clean, band unmoved — and the control refuses. Its offline fixtures are 'node`,
    `             clock_run.cjs --selftest': eleven cases including a superlinear refusal, a`,
    `             declaring-200-rendering-140 refusal, a degenerate denominator, one bad block in nine,`,
    `             and the sensitivity span above asserted rather than described.`,
    `kb witness = ONE RECORD PER PHYSICAL KEY (rf2-0qj9w). The driver counts the keys it presses;`,
    `             web-vitals' rules form at most one interaction per key; keys that raised no entry are`,
    `             published as CENSORED under the 16 ms floor rather than dropped; and the recompute`,
    `             census must read 100 cells + 4 fields on a substrate arm and NOTHING on a floor arm.`,
    `             Each of those is a refusal that exits 1 naming itself. Its fixtures — including the`,
    `             collapse a broken grouping produces — are 'node clock_witness.test.cjs', in the`,
    `             fast-PR spine, and they also run on every invocation of this driver.`,
  ]) console.log(`;;   ${line}`);

  const outcomes = [];
  let died = null;
  try {
    for (const rowId of ROWS) {
      console.error(`[clock] row ${rowId}`);
      const trace = { step: null };
      try {
        const out = await runRow(browser, rowId, trace);
        outcomes.push({ out, verdict: report(out) });
      } catch (e) {
        died = `${rowId}${trace.step ? ` at ${trace.step}` : ''}: ${e.message}`;
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
            // THE PUBLISHED CLOCK'S OWN RAW READINGS, and the in-page
            // window's, on the same samples. Only `rounds` (taskNet) was
            // ever written, so a dataset from this driver could not
            // recompute the figure the page actually quotes — which is the
            // durable-evidence gap `rf2-cvvb7`'s merged-PR audit recorded
            // against the seam study. All three windows are here now, so
            // every table in `the-candidates-clock.md` is reproducible from
            // the file without re-running the box.
            roundsTask: o.out.roundsTask,
            inPageRounds: o.out.inPageRounds,
            decomposition: o.out.decomposition,
            granularity: o.out.granularity,
            seam: o.verdict.seam,
            seamTask: o.verdict.seamTask,
            tally: o.verdict.tally,
            ctlOk: o.verdict.ctlVerdict ? o.verdict.ctlVerdict.ok : null,
            // THE THREE-POINT CONTROL, whole — its per-block readings, the
            // absolutes each difference was taken from, the fitted line and
            // the constant it recovers. `armPlan` is the page's DECLARED
            // plan and `sabotage` is what the 2D arm actually rendered, so a
            // dataset carries the evidence that a falsification run was a
            // falsification run rather than a measurement.
            ctl3: o.verdict.ctl3,
            ctl3Net: o.verdict.ctl3Net,
            ctl3Layout: o.verdict.ctl3Layout,
            roundsLayout: o.out.roundsLayout,
            ctl3Parity: o.verdict.ctl3Parity,
            constants: o.verdict.constants,
            armPlan: o.out.armPlan,
            sabotage: o.out.sabotage,
            guardRefuse: o.verdict.guardVerdict.refuse,
            guardRefuseTask: o.verdict.guardVerdictTask.refuse,
            bar: o.verdict.bar,
            inPageBar: o.verdict.inPageBar,
            ctl: o.verdict.ctlVerdict,
            barTask: o.verdict.barTask,
            ctlTask: o.verdict.ctlTask,
            bandTask: o.verdict.bandTask,
            // THE KEYSTROKE ROW'S RAW ACCOUNTING. `sentKeys` is what the
            // driver pressed, `eventTiming` is what the browser reported and
            // `census` is what recomputed — so the published records, the
            // censored count and the localisation can all be recomputed from
            // the file without re-running the box, which is what the seam
            // study's merged-PR audit asked of every row here.
            sentKeys: o.out.sentKeys,
            eventTiming: o.out.eventTiming,
            census: o.out.census,
            kbShape: o.out.kbShape,
            kbWitness: o.verdict.kbVerdict
              ? {
                  ok: o.verdict.kbVerdict.ok,
                  faults: o.verdict.kbVerdict.faults,
                  totals: o.verdict.kbVerdict.totals,
                  perArm: o.verdict.kbVerdict.perArm,
                  records: o.verdict.kbVerdict.records,
                  censored: o.verdict.kbVerdict.censored,
                }
              : null,
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
  const refused = outcomes.filter((o) => o.verdict.guardVerdict.refuse || o.verdict.guardVerdictTask.refuse);
  if (refused.length > 0) {
    console.error(
      `[clock] ARM-ORDER GUARD REFUSED (exit 2) on: ` +
        refused
          .map(
            (o) =>
              `${o.out.rowId} [${[
                o.verdict.guardVerdict.refuse ? 'taskNet' : null,
                o.verdict.guardVerdictTask.refuse ? 'TaskDuration' : null,
              ]
                .filter(Boolean)
                .join(' + ')}]`
          )
          .join(', ') +
        `. ` +
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
  const badCtl3Parity = outcomes.filter((o) => o.verdict.ctl3Parity && !o.verdict.ctl3Parity.ok);
  if (badCtl3Parity.length > 0) {
    console.error(
      `[clock] FAILED: the three-point control's own arms built DIFFERENT PAGES on: ` +
        `${badCtl3Parity.map((o) => o.out.rowId).join(', ')}. These arms are exempt from the cross-arm ` +
        `canonical-DOM gate because their page is not the floor's, so this is the only thing checking ` +
        `them, and a difference of differences between two different pages is not a difference.`
    );
    process.exit(1);
  }
  // THE PER-KEYSTROKE WITNESS REFUSES BY NAME (rf2-0qj9w). Its faults are
  // statements about whether the row's `n` MEANS anything — whether every key
  // the driver pressed is accounted for exactly once, whether an entry the
  // browser reported belongs to a key that was pressed, and whether the page
  // recomputed the subscriptions validation.md's witness states it must. None
  // of that is adjudicable from a magnitude, so it is refused ahead of one.
  const kbRefused = outcomes.filter((o) => o.verdict.kbVerdict && !o.verdict.kbVerdict.ok);
  if (kbRefused.length > 0) {
    console.error(
      `[clock] FAILED: the per-keystroke witness REFUSED — its accounting does not close, so the row's ` +
        `n is not a count of anything:`
    );
    for (const o of kbRefused) {
      for (const f of o.verdict.kbVerdict.faults) {
        console.error(`[clock]   ${o.out.rowId} [${f.code}] ${f.why}`);
      }
    }
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
  // THE BAND CEILING is a tripwire, and `rf2-ymi6j` re-took the ladder that
  // sets it because the previous one did not behave like a tripwire: 25%,
  // calibrated as "above the widest of nineteen draws", fired three times in
  // two days. The re-take put the figure on the band's own bootstrap sampling
  // distribution instead — 35%, `P(fire) = 0.2%` per run — and measured the
  // predecessor at 2.6–9.0% per run, which is a lottery rather than a
  // tripwire. The gate that actually bites is still the per-row one printed
  // in the seam block: a margin inside the band is instrument-limited.
  //
  // AND IT ADJUDICATES THE CLOCK THE ROWS ARE STATED ON. It used to refuse if
  // EITHER clock breached, which sounds conservative and is not: `taskNet` is
  // a difference of two counters and a smaller number, so the same samples
  // give it a wider band by construction — 28.5% per-sample dispersion against
  // 23.2%, and a wider band on 14 of the re-take's 19 runs. Refusing a run for
  // the noise of a subtraction whose result nothing publishes is refusing on a
  // criterion that does not match what it is judging. The frame-only band is
  // still computed, printed and stored on every run; it is no longer a ground
  // of refusal, and a run where it alone breaches says so out loud.
  const overCeiling = outcomes.filter((o) => o.verdict.seamTask.ceilingBreached);
  const frameOnlyOver = outcomes.filter(
    (o) => o.verdict.seam.verdict.ceilingBreached && !o.verdict.seamTask.ceilingBreached
  );
  for (const o of frameOnlyOver) {
    console.error(
      `[clock] ${o.out.rowId}: the SUPERSEDED frame-only band is ` +
        `${(o.verdict.seam.band * 100).toFixed(1)}%, over the ` +
        `${(seamlib.BAND_CEILING * 100).toFixed(0)}% ceiling, while the PUBLISHED clock reads ` +
        `${o.verdict.seamTask.band === null ? 'n/a' : (o.verdict.seamTask.band * 100).toFixed(1) + '%'} ` +
        `and does not. Reported, not refused (rf2-ymi6j).`
    );
  }
  if (overCeiling.length > 0) {
    console.error(
      `[clock] FAILED: the run's own reproducibility band exceeds the ` +
        `${(seamlib.BAND_CEILING * 100).toFixed(0)}% ceiling on: ` +
        overCeiling
          .map(
            (o) =>
              `${o.out.rowId} (TaskDuration ${(o.verdict.seamTask.band * 100).toFixed(1)}%, ` +
              `taskNet ${o.verdict.seam.band === null ? 'n/a' : (o.verdict.seam.band * 100).toFixed(1) + '%'})`
          )
          .join(', ') +
        `. ctl-2x and floor are two arms in the SAME block whose true ratio is a property of the ` +
        `page, so a band that wide means the box could not reproduce identical work — no magnitude ` +
        `from those rows is reportable, whatever its margin.`
    );
    process.exit(1);
  }

  // THE CONTROL IS ADJUDICATED ON THE PUBLISHED CLOCK TOO. `ctlVerdict` is
  // the `taskNet` verdict and was the only one this gate consulted; the row
  // is now stated on raw `TaskDuration`, so its control has to hold there.
  // In practice the two agree to within 2% — `rf2-yd52q` measured that — so
  // this is a gate that should almost never change an answer, and one that
  // would be indefensible to leave out for exactly that reason.
  // THE THREE-POINT CONTROL IS THE GATE ON A BULK ROW, and `ctl-2x` is
  // demoted to a reported diagnostic on those rows rather than deleted
  // (rf2-7iqb5, rf2-5xrcd).
  //
  // Demotion and not removal, because the two disagree for a REASON that is
  // itself measured here — `ctl-2x` reads `(2W + c)/(W + c)` and the run
  // prints the `c` that explains the gap — and a control removed the moment
  // it started failing is indistinguishable from a control tuned until it
  // passed. `ctl-2x` also still supplies the band, which needs a pair whose
  // true ratio is a property of the page and does NOT need that ratio to be
  // 2.00; nothing about the band moves in this change, deliberately, because
  // moving the band in the same change that repairs the control would make
  // the repair unfalsifiable.
  //
  // A row that HAS no three-point control — `M1`, whose operation is the
  // mount, and `keystroke` — is gated exactly as before.
  const ctlBad = (o) =>
    o.verdict.ctl3
      ? !o.verdict.ctl3.ok
      : !o.verdict.ctlVerdict.ok || (o.verdict.ctlTask && !o.verdict.ctlTask.ok);
  const demoted = outcomes.filter(
    (o) => o.verdict.ctl3 && o.verdict.ctl3.ok && (!o.verdict.ctlVerdict.ok || (o.verdict.ctlTask && !o.verdict.ctlTask.ok))
  );
  for (const o of demoted) {
    console.error(
      `[clock] ${o.out.rowId}: ctl-2x FAILED at ${o.verdict.ctlTask ? o.verdict.ctlTask.measured.mean : '?'}x and the ` +
        `THREE-POINT control PASSED at ${o.verdict.ctl3.measured.mean.toFixed(4)}x on the same samples. ` +
        `The row is gated on the three-point control. c(2x) = ` +
        `${o.verdict.constants ? o.verdict.constants.c2x.mean.toFixed(4) : '?'} ms is the reported reason they differ.`
    );
  }
  // THE DECISION HAS ONE SEAT (`reportability`, above). Nothing below reads a
  // refusal on its own: the summary is built, the function decides, its lines
  // are printed and its code is the exit code.
  const decision = reportability(
    outcomes.map((o) => {
      // THE ADJUDICATION OF THE PUBLISHED CLOCK, read off the same object the
      // report printed and the dataset stored — not recomputed here, because a
      // second computation is a second decision.
      const bars = (o.verdict.seamTask && o.verdict.seamTask.rows) || {};
      const names = Object.keys(bars);
      const unadj = names.filter((n) => bars[n].unadjudicated);
      return {
        rowId: o.out.rowId,
        ctlOk: !(ctlBad(o) || (o.verdict.etVerdict && !o.verdict.etVerdict.ok)),
        ctlNote: o.verdict.ctl3
          ? ` (three-point ${o.verdict.ctl3.measured.mean.toFixed(4)}x vs ${o.verdict.ctl3.predicted}x)`
          : '',
        // Fail closed on a row that adjudicated no bar at all: an empty
        // verdict is an absent one, not a clean one.
        adjudicable: names.length > 0 && unadj.length < names.length,
        unadjudicatedWhy: unadj.length > 0 ? bars[unadj[0]].why : 'the run adjudicated no bar on this row at all',
      };
    }),
    { sabotage: CTL3_SABOTAGE }
  );
  for (const line of decision.lines) console.error(line);
  if (decision.code !== 0) process.exit(decision.code);
  console.error('[clock] ok');
}

module.exports = { reportability, reportabilitySelfTest };

if (require.main === module) {
  main();
}
