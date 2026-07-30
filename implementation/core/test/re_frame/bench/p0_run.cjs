#!/usr/bin/env node
// EP-0038 P0 — the driver. Build the `:advanced` bundle once, run both
// rows, print the table, exit on a refusal.
//
//   node implementation/core/test/re_frame/bench/p0_run.cjs
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only clock
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only heap
//   P0_ROUNDS=6 P0_SAMPLES=12 node .../p0_run.cjs
//
// ## Why the numbers have to come from here
//
// The bar's numbers are BROWSER numbers (HD-012, validation.md §P0): a
// real browser, `:advanced`, `goog.DEBUG false`. Spec 009 instrumentation,
// schema validation and trace emission are all `goog.DEBUG`-gated and all
// sit on the subscription and render paths these rows measure, so a
// development build publishes a cost no user pays — and it does so in one
// direction only, because a floor arm has no counterpart to any of them.
// `:advanced` cannot compile shadow's `:browser-test` target
// (`cljs-test-display`'s `goog.define`s collide under Closure), so the
// reading rides a plain `:browser` module.
//
// ## No shadow-cljs.edn change, deliberately
//
// The epic's SEQUENCING LAW makes `implementation/shadow-cljs.edn`
// build-id touches hot-zone sequenced, and rf2-2rtt6.2 owns that file for
// this wave. So this driver does what `b6_prod_run.cjs` and `b7_run.cjs`
// already do: it merges an output directory and an `:init-fn` into an
// EXISTING `:advanced` `:browser` build id, which contributes nothing but
// its compiler settings — `:target :browser`, `:optimizations :advanced`,
// `:infer-externs :auto`, `goog.DEBUG false`. The module's entry, and
// therefore everything that ends up in the bundle, is this arm's.
// `P0_BUILD` overrides the donor id: when rf2-2rtt6.2's measurement-lane
// build id lands, point this at it and delete this paragraph.
//
// ## The heap row runs HERE and the clock row runs in the page
//
// A page cannot force a garbage collection, so it cannot decide when a
// retained-heap reading is taken; a page that tried would be reading
// whatever the collector happened to have done. The clock row has the
// opposite constraint — a `flushSync` window must not have a CDP
// round-trip in it — so it runs entirely in the page and parks its
// records on `window.P0_RESULTS`.
//
// ## The arm-order guard is expressed ONCE, in CLJS
//
// `re-frame.bench.order-guard` is the rule, and this driver reaches it
// through `window.P0H.verdict` rather than carrying a JavaScript copy —
// there is already a `.cjs` copy of the same rule serving the freehand
// bench, and a third would be a third place for it to drift. Its
// self-test runs before anything is measured, in both modes. **A refusal
// exits 2, and the repair is to the ARM, never to the guard.**
//
// ## Every figure this driver prints as a check, it EXITS on (rf2-95s5b)
//
// It did not. The clock row's `N unverified of M` and BOTH positive
// controls were printed and only the heap row's read-back count was
// adjudicated, so a run in which no write reached the page, or in which
// the instrument could not see a change its own arithmetic predicted,
// printed the count beside `VERDICT: reportable` and exited 0. A count
// that is displayed and not gated is decoration. The four exit-bearing
// checks are now, in the order they are taken:
//
//   1. the arm-order guard's self-test, in the page, before anything is
//      measured — exit 1 (clock) / the page refuses to install (heap);
//   2. `N unverified of M` — clock and heap, exit 1 on any nonzero count;
//   3. the positive control — clock and heap, adjudicated by
//      `lane/control-verdict` and exit 1 when it is not `ok`;
//   4. the arm-order verdict over the samples — exit 2, figures not
//      quotable.
//
// The positive controls are UNVERIFIABLE BY CONSTRUCTION — the clock's
// two control arms build different pages on purpose, and the heap's is a
// dense array with no DOM at all — so their windows are excluded from
// (2)'s denominator rather than counted as verified, and (3) is the gate
// they answer to instead. See `p0-harness/mount-sample!`.
//
// `lane/control-verdict` is the lane's shared rule and WHAT IT DECIDES IS
// NOT THIS DRIVER'S TO CHANGE: rf2-egdaq is the open ruling on whether it
// tightens from range-overlap to every-round-inside. Read its docstring
// before quoting `:ok?`. Both of this driver's controls pass under either
// reading — heap 4,700,317 B [4,699,074–4,700,974] against a predicted
// 4,700,000 B, clock 1.8381x [1.8182–1.8548] against a predicted 1.9975x
// with ±25% slack — so wiring them in changed no published figure.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const IMPL = path.resolve(__dirname, '../../../..');

const BUILD = process.env.P0_BUILD || 'freehand-release';
const OUT_DIR = process.env.P0_OUT_DIR || 'out/p0-hicasso';
const OUT = path.join(IMPL, OUT_DIR);
const INIT_FN = process.env.P0_INIT_FN || 're-frame.bench.p0-app/-main';
const PORT = Number(process.env.P0_PORT || 8149);

const ROUNDS = Number(process.env.P0_ROUNDS || 6);
const SAMPLES = Number(process.env.P0_SAMPLES || 12);
const WARMUPS = Number(process.env.P0_WARMUPS || 4);
const ROOTS = Number(process.env.P0_ROOTS || 4);
// 587,500 unboxed doubles = 4,700,000 bytes.
const CONTROL_DOUBLES = Number(process.env.P0_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
const HEAP_TOLERANCE = Number(process.env.P0_HEAP_TOLERANCE || 0.25);
const CLOCK_TIMEOUT_MS = Number(process.env.P0_CLOCK_TIMEOUT_MS || 30 * 60 * 1000);
// How far a positive control's measured range may sit from the prediction
// its own arithmetic made and still count as THE INSTRUMENT HAS SIGNAL.
// Generous on purpose — the claim being gated is not that the model is
// exact. `lane/control-verdict` applies it; this driver only carries it.
const CONTROL_SLACK = Number(process.env.P0_CONTROL_SLACK || 0.25);

const ONLY = (() => {
  const i = process.argv.indexOf('--only');
  return i === -1 ? null : process.argv[i + 1];
})();

// The heap families, and which segment each arm needs. `null` is the
// floor, which needs whichever adapter the segment it is being read in
// has installed — it holds no re-frame state, so it is the same work
// either side of the seam and is the calibrator that makes the
// cross-segment ratio legitimate.
const HEAP_SEGMENTS = [
  { segment: 'reagent-subs', arms: ['list/floor', 'list/reagent', 'grid/floor', 'grid/reagent'] },
  { segment: 'uix-subs', arms: ['list/floor', 'list/uix', 'grid/floor', 'grid/uix'] },
];

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and then reports `EOF while
// reading` from a fragment.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." :modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  console.error(`[p0] building :advanced bundle (donor build id: ${BUILD}) ...`);
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, and a shell concatenates argv, which is
  // the other way the config-merge EDN gets torn in half.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[p0] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>P0</title></head>' +
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

// `'commit'`, never `'load'`. `p0-app/-main` is this bundle's `:init-fn`,
// so the whole clock run happens INSIDE the `<script>` and the `load`
// event is downstream of it. Waiting for `load` would be waiting for the
// benchmark against a thirty-second ceiling nothing here could see.
async function newPage(chromium, query) {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  // Every `;; P0` record, and EVERY warning or error the page emits. The
  // second half is not debug scaffolding: a React warning about a root
  // that failed to unmount, or a re-frame error recovered on a render
  // path, is the difference between a slow arm and a broken instrument,
  // and a driver that filtered them out would publish the number anyway.
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; P0')) console.log(t);
    else if (m.type() === 'error' || m.type() === 'warning') {
      console.error(`[p0] page ${m.type()}: ${t.slice(0, 400)}`);
    }
  });
  page.on('pageerror', (e) => console.error('[p0] page error:', e.message));
  await page.goto(`http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeout: 120000,
  });
  return { browser, page };
}

// ---------------------------------------------------------------------------
// The clock row
// ---------------------------------------------------------------------------

// ONE ROUND PER PAGE. Run as a single page, this instrument's own probe
// measured `usedJSHeapSize` climbing 34 -> 87 MB across six segment
// entries with `body-children` pinned at 2, and the FLOOR arm — which
// cannot change — drifting 3.4 -> 7.0 ms on that heap; the arm-order
// guard refused on phase, correctly. A fresh document cannot inherit the
// previous round's heap, so a browser restart per round removes the
// factor by construction rather than by argument. The accumulation is
// itself a finding and is filed separately, not swept up here.
async function clockRow(chromium) {
  const roundEdns = [];
  let err = null;
  for (let r = 0; r < ROUNDS && !err; r++) {
    console.error(`[p0] clock round ${r + 1}/${ROUNDS} (fresh page) ...`);
    const q = `?round=${r}&samples=${SAMPLES}&warmup=${WARMUPS}`;
    const { browser, page } = await newPage(chromium, q);
    await page.waitForFunction('window.P0_DONE === true || window.P0_ERROR', null, {
      timeout: CLOCK_TIMEOUT_MS,
    });
    err = await page.evaluate('window.P0_ERROR || null');
    const edn = await page.evaluate('window.P0_ROUND || null');
    const selfTest = await page.evaluate('window.P0_GUARD_SELF_TEST || null');
    if (r === 0 && selfTest && !/:ok\? true/.test(selfTest)) {
      err = 'the arm-order guard self-test failed — nothing may be measured';
    }
    await browser.close();
    if (edn) roundEdns.push(edn);
  }
  if (err) return { err, results: null };
  if (!roundEdns.length) return { err: 'no round produced a record', results: null };

  // The fold runs in a page too, so the ranges, the red-zone ratios, the
  // arm-order verdict, the summed read-back tally and the positive
  // control's verdict are computed by `re-frame.bench.order-guard`,
  // `p0-harness` and `hicasso.lane` — the same code the rounds ran under —
  // rather than by a second, drifting expression of the same arithmetic in
  // JavaScript. `adjudicate` is the ONLY door onto the fold: a driver that
  // could take the record without the verdicts is the hole this closed.
  console.error('[p0] aggregating ...');
  const { browser, page } = await newPage(chromium, '?mode=aggregate');
  await page.waitForFunction('window.P0_READY === true || window.P0_ERROR', null, {
    timeout: 180000,
  });
  const adj = await page.evaluate(
    ([e, s]) => window.P0A.adjudicate(e, s),
    [roundEdns, CONTROL_SLACK]
  );
  await browser.close();
  return {
    err: null,
    results: adj.edn,
    verification: { unverified: adj.unverified, of: adj.of, perRow: adj.perRow },
    control: { ok: adj.controlOk, why: adj.controlWhy, slack: CONTROL_SLACK },
  };
}

// ---------------------------------------------------------------------------
// The collector and the readers
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function makeReaders(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('Runtime.enable');
  // Three collections with a beat between them. One is not enough: React
  // roots die in stages — fibers, then the host instances they point at —
  // and a single pass leaves the second stage standing.
  const gc = async () => {
    for (let i = 0; i < 3; i++) {
      await cdp.send('HeapProfiler.collectGarbage');
      await sleep(80);
    }
  };
  const read = async () => {
    const { usedSize } = await cdp.send('Runtime.getHeapUsage');
    const perf = await page.evaluate(() => window.P0H.perfMem());
    return { cdp: usedSize, perf };
  };
  return { cdp, gc, read };
}

// ---------------------------------------------------------------------------
// The heap row
// ---------------------------------------------------------------------------

async function heapRow(chromium) {
  const { browser, page } = await newPage(chromium, '?mode=heap');
  await page.waitForFunction('window.P0_READY === true || window.P0_ERROR', null, {
    timeout: 180000,
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`heap page failed to initialise: ${err}`);
  }
  const perRoot = await page.evaluate(() => window.P0H.boundariesPerRoot);
  const { gc, read } = await makeReaders(page);

  const boundariesOf = (arm) => ROOTS * perRoot[arm.split('/')[0]];

  let unverified = 0;
  let mounts = 0;
  const orderSamples = [];
  let position = 0;
  let previous = null;

  // A WARM-UP PASS, mounted and released once per arm and never read. The
  // first mount of any arm allocates things that are not the page and
  // never go away: compiled code for the paths it just took, inline
  // caches, interned keywords, one-time module state. Charged to round 1
  // they read as retention per boundary, and they are not.
  console.error('[p0] heap warm-up pass ...');
  for (const { segment, arms } of HEAP_SEGMENTS) {
    await page.evaluate((s) => window.P0H.prepare(s), segment);
    for (const arm of arms) {
      const v = await page.evaluate(([a, k]) => window.P0H.mount(a, k), [arm, ROOTS]);
      mounts++;
      if (!v.ok) unverified++;
      await page.evaluate(() => window.P0H.release());
    }
  }
  await page.evaluate((n) => window.P0H.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.P0H.controlRelease());

  const rounds = [];
  for (let round = 0; round < ROUNDS; round++) {
    console.error(`[p0] heap round ${round + 1}/${ROUNDS}`);

    // --- the positive control, in situ, before this round's arms --------
    await gc();
    const ctlBefore = await read();
    const ctlLen = await page.evaluate((n) => window.P0H.control(n), CONTROL_DOUBLES);
    await gc();
    const ctlHeld = await read();
    await page.evaluate(() => window.P0H.controlRelease());
    await gc();
    const ctlAfter = await read();
    const control = {
      doubles: ctlLen,
      predictedBytes: CONTROL_PREDICTED,
      measuredCdp: ctlHeld.cdp - (ctlBefore.cdp + ctlAfter.cdp) / 2,
      measuredPerf: ctlHeld.perf - (ctlBefore.perf + ctlAfter.perf) / 2,
      baselineDriftCdp: ctlAfter.cdp - ctlBefore.cdp,
    };

    // --- the two segments, in the order this round's parity dictates ----
    // Segment order alternates with the round for the same reason the
    // clock row's does: two segments admit two orders, and a single-order
    // result has not been checked.
    const segs = round % 2 === 0 ? HEAP_SEGMENTS : [...HEAP_SEGMENTS].slice().reverse();
    const armsOut = {};
    for (const { segment, arms } of segs) {
      // `prepare` BEFORE the baseline read, so the adapter swap's own
      // residue lands in the baseline and not in the arm's delta.
      await page.evaluate((s) => window.P0H.prepare(s), segment);
      const order = await page.evaluate(
        ([n, r]) => window.P0H.slotOrder(n, r),
        [arms.length, round]
      );
      for (const j of order) {
        const arm = arms[j];
        await gc();
        const pre = await read();
        const verify = await page.evaluate(([a, k]) => window.P0H.mount(a, k), [arm, ROOTS]);
        mounts++;
        if (!verify.ok) unverified++;
        await gc();
        const held = await read();
        await page.evaluate(() => window.P0H.release());
        await gc();
        const post = await read();
        const boundaries = boundariesOf(arm);
        const key = `${segment}|${arm}`;
        armsOut[key] = {
          segment,
          arm,
          verify,
          boundaries,
          retainedCdp: held.cdp - pre.cdp,
          retainedPerf: held.perf - pre.perf,
          residueCdp: post.cdp - pre.cdp,
          bytesPerBoundaryCdp: (held.cdp - pre.cdp) / boundaries,
          bytesPerBoundaryPerf: (held.perf - pre.perf) / boundaries,
        };
        orderSamples.push({
          arm: key,
          value: armsOut[key].bytesPerBoundaryCdp,
          predecessor: previous,
          position: position++,
        });
        previous = key;
      }
    }
    rounds.push({ round, control, arms: armsOut });
  }

  const verdictEdn = await page.evaluate(
    ([s, t]) => window.P0H.verdict(s, t),
    [orderSamples, HEAP_TOLERANCE]
  );

  // THE CONTROL IS ADJUDICATED, not printed. `predicted` is 8 bytes a
  // double, fixed before the run; the measured range is this row's own
  // per-round readings. The rule is `lane/control-verdict`'s, the same one
  // the freehand P0 arms publish under — this driver states the pair and
  // reads the answer, and it does so HERE because the page has to still be
  // open for the rule to be the lane's rather than a JavaScript copy of it.
  const ctlStat = stat(rounds.map((r) => r.control.measuredCdp));
  const controlVerdict = await page.evaluate(
    ([p, m, s]) => window.P0H.controlVerdict(p, m, s),
    [CONTROL_PREDICTED, { min: ctlStat.min, max: ctlStat.max, mean: ctlStat.mean }, CONTROL_SLACK]
  );

  await browser.close();

  return {
    benchmark: 'P0:retained-heap-per-boundary',
    bead: 'rf2-2rtt6.4',
    roots: ROOTS,
    perRoot,
    rounds: ROUNDS,
    segments: HEAP_SEGMENTS,
    instruments: {
      A: 'CDP Runtime.getHeapUsage().usedSize after 3x HeapProfiler.collectGarbage',
      B: 'in-page performance.memory.usedJSHeapSize, same moment, --enable-precise-memory-info',
      note:
        'A and B are two doors onto one V8 counter and are NOT independent — on the ' +
        'predecessor instrument, pointed at 80,000 held objects, they returned 3868954 both.',
    },
    control: {
      shape: 'dense JS array of doubles',
      doubles: CONTROL_DOUBLES,
      predictedBytes: CONTROL_PREDICTED,
      measured: ctlStat,
      slack: CONTROL_SLACK,
      verdict: controlVerdict,
    },
    verification: { mounts, unverified },
    perRound: rounds,
    orderVerdictEdn: verdictEdn,
    orderRefused: /:refuse\? true/.test(verdictEdn),
  };
}

// ---------------------------------------------------------------------------
// The summary
// ---------------------------------------------------------------------------

const stat = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  return { mean, min: s[0], max: s[s.length - 1] };
};

// The clock row's two gates, stated where a reader will look for them
// rather than buried inside the record's EDN. Both were printed and
// neither was adjudicated until rf2-95s5b; the exit logic below reads
// exactly these figures.
function summariseClock(c) {
  console.log(';;');
  console.log(';; ==== P0 CLOCK — VERIFICATION AND POSITIVE CONTROL ====');
  console.log(`;;   verification: ${c.verification.unverified} unverified of ${c.verification.of} windows`);
  console.log(`;;                 ${c.verification.perRow}`);
  console.log(';;   The positive control is NOT in that denominator and cannot be: its two');
  console.log(';;   arms build DIFFERENT pages on purpose, so no window of it has anything to');
  console.log(';;   read back. It is reported and adjudicated separately, here:');
  console.log(
    `;;   positive control (slack ±${(c.control.slack * 100).toFixed(0)}%): ` +
      `${c.control.ok ? 'OK' : 'FAILED'} — ${c.control.why}`
  );
}

function summariseHeap(row) {
  console.log('\n;; ==== P0 RETAINED HEAP — bytes per boundary ====');
  console.log(`;; ${row.roots} roots held per arm; list=${row.perRoot.list} rows, grid=${row.perRoot.grid} cells`);
  const ctlA = row.control.measured;
  console.log(
    `;; positive control: predicted ${CONTROL_PREDICTED} B  |  measured ${Math.round(ctlA.mean)} B ` +
      `[${Math.round(ctlA.min)}–${Math.round(ctlA.max)}]  (ratio ${(ctlA.mean / CONTROL_PREDICTED).toFixed(4)})`
  );
  console.log(
    `;;   VERDICT (lane/control-verdict, slack ±${(row.control.slack * 100).toFixed(0)}%): ` +
      `${row.control.verdict.ok ? 'OK' : 'FAILED'}`
  );
  console.log(`;;     ${row.control.verdict.why}`);
  console.log(';;     (the shared rule words its figures with an "x"; this control\'s unit is BYTES)');
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  console.log(';;');
  console.log(';; arm                              B/boundary (mean) [min-max]     residue B');
  const keys = Object.keys(row.perRound[0].arms);
  const byKey = {};
  for (const k of keys) {
    const a = stat(row.perRound.map((r) => r.arms[k].bytesPerBoundaryCdp));
    const res = stat(row.perRound.map((r) => r.arms[k].residueCdp));
    byKey[k] = a;
    console.log(
      `;; ${k.padEnd(32)} ${String(Math.round(a.mean)).padStart(8)} ` +
        `[${Math.round(a.min)}–${Math.round(a.max)}]`.padEnd(20) +
        `${String(Math.round(res.mean)).padStart(10)}`
    );
  }

  // --- the red-zone figure, per family -----------------------------------
  console.log(';;');
  console.log(';; ==== P0 RED-ZONE (retained heap) — UIx over Reagent, per witness family ====');
  console.log(';;   EXCLUSIVE = arm - floor, the substrate\'s OWN standing cost, measured');
  console.log(';;   in the same segment. That is the axis validation.md states the budget on.');
  const redZone = {};
  for (const family of ['list', 'grid']) {
    const perRound = row.perRound.map((r) => {
      const rf = r.arms[`reagent-subs|${family}/floor`].bytesPerBoundaryCdp;
      const rs = r.arms[`reagent-subs|${family}/reagent`].bytesPerBoundaryCdp;
      const uf = r.arms[`uix-subs|${family}/floor`].bytesPerBoundaryCdp;
      const us = r.arms[`uix-subs|${family}/uix`].bytesPerBoundaryCdp;
      return { exclusive: (us - uf) / (rs - rf), absolute: us / rs };
    });
    const ex = stat(perRound.map((p) => p.exclusive));
    const ab = stat(perRound.map((p) => p.absolute));
    redZone[family] = { exclusive: ex, absolute: ab, perRound };
    const straddles = ex.min <= 1.0 && ex.max >= 1.0;
    console.log(
      `;;   ${family.padEnd(6)} EXCLUSIVE ${ex.mean.toFixed(4)}x [${ex.min.toFixed(4)}–${ex.max.toFixed(4)}]` +
        `   absolute ${ab.mean.toFixed(4)}x [${ab.min.toFixed(4)}–${ab.max.toFixed(4)}]` +
        (straddles ? '   RANGE STRADDLES 1.0 — INDISTINGUISHABLE' : '')
    );
  }
  row.redZone = redZone;
  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (heap) ====');
  console.log(`;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`);
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
}

// ---------------------------------------------------------------------------

(async () => {
  build();
  const server = serve();
  const { chromium } = require('playwright');
  const out = { generatedAt: new Date().toISOString(), build: BUILD, initFn: INIT_FN };
  // EVERY failed gate, not the last one. A single `failed` slot let a
  // later gate's silence overwrite an earlier gate's refusal, and a run
  // that failed two things would name one of them.
  const failures = [];
  let refused = false;
  try {
    if (ONLY !== 'heap') {
      console.error('[p0] clock row ...');
      const c = await clockRow(chromium);
      out.clock = c.results;
      if (c.err) {
        failures.push(`clock: ${c.err}`);
      } else {
        out.clockGates = { verification: c.verification, control: c.control };
        console.log(';; ==== P0 CLOCK ====');
        console.log(c.results);
        summariseClock(c);
        // A row whose writes never reached the page is the cheapest row in
        // any table. The count was printed inside the record from the
        // first run; nothing exited on it until rf2-95s5b.
        if (c.verification.unverified > 0) {
          failures.push(
            `clock: ${c.verification.unverified} unverified of ${c.verification.of} windows ` +
              `— ${c.verification.perRow}`
          );
        }
        if (!c.control.ok) failures.push(`clock: positive control — ${c.control.why}`);
        const m = /:refused \[([^\]]*)\]/.exec(c.results);
        if (m && m[1].trim()) {
          refused = true;
          console.log(';;');
          console.log(';; ==== ARM ORDER: THESE CLOCK ROWS ARE NOT REPORTABLE ====');
          console.log(`;;   ${m[1].trim()}`);
        }
      }
    }
    if (ONLY !== 'clock') {
      console.error('[p0] heap row ...');
      out.heap = await heapRow(chromium);
      summariseHeap(out.heap);
      if (out.heap.verification.unverified > 0) {
        failures.push(`heap: ${out.heap.verification.unverified} unverified mounts`);
      }
      if (!out.heap.control.verdict.ok) {
        failures.push(`heap: positive control — ${out.heap.control.verdict.why}`);
      }
      refused = refused || out.heap.orderRefused;
    }
  } catch (e) {
    failures.push(String(e && e.stack ? e.stack : e));
  } finally {
    server.close();
  }
  const raw = process.env.P0_RAW_OUT;
  if (raw) {
    fs.mkdirSync(path.dirname(raw), { recursive: true });
    fs.writeFileSync(raw, JSON.stringify(out, null, 2));
    console.error(`[p0] raw data -> ${raw}`);
  }
  if (failures.length) {
    for (const f of failures) console.error(`[p0] FAILED: ${f}`);
    process.exit(1);
  }
  // A run whose figures the arm-order guard refused is not a green run.
  // The data is printed and the raw file written — the refusal is about
  // what may be QUOTED, not about throwing the measurement away. Exit 2,
  // the same code the sibling harnesses use for the same verdict. REPAIR
  // THE ARM, NOT THE GUARD.
  if (refused) {
    console.error('[p0] arm-order guard REFUSED — figures are not reportable');
    process.exit(2);
  }
  console.error('[p0] done');
})();
