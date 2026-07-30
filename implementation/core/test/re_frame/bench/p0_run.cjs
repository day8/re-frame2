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
const WARMUPS = Number(process.env.P0_WARMUPS || 3);
const ROOTS = Number(process.env.P0_ROOTS || 4);
// 587,500 unboxed doubles = 4,700,000 bytes.
const CONTROL_DOUBLES = Number(process.env.P0_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
const HEAP_TOLERANCE = Number(process.env.P0_HEAP_TOLERANCE || 0.25);
const CLOCK_TIMEOUT_MS = Number(process.env.P0_CLOCK_TIMEOUT_MS || 30 * 60 * 1000);

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
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; P0')) console.log(t);
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

async function clockRow(chromium) {
  const q =
    `?mode=clock&rounds=${ROUNDS}&samples=${SAMPLES}&warmups=${WARMUPS}`;
  const { browser, page } = await newPage(chromium, q);
  await page.waitForFunction('window.P0_DONE === true || window.P0_ERROR', null, {
    timeout: CLOCK_TIMEOUT_MS,
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  const results = await page.evaluate('window.P0_RESULTS || {}');
  const refused = await page.evaluate('window.P0_REFUSED || []');
  await browser.close();
  return { err, results, refused };
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

function summariseHeap(row) {
  console.log('\n;; ==== P0 RETAINED HEAP — bytes per boundary ====');
  console.log(`;; ${row.roots} roots held per arm; list=${row.perRoot.list} rows, grid=${row.perRoot.grid} cells`);
  const ctlA = stat(row.perRound.map((r) => r.control.measuredCdp));
  console.log(
    `;; positive control: predicted ${CONTROL_PREDICTED} B  |  measured ${Math.round(ctlA.mean)} B ` +
      `[${Math.round(ctlA.min)}–${Math.round(ctlA.max)}]  (ratio ${(ctlA.mean / CONTROL_PREDICTED).toFixed(4)})`
  );
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
  let failed = null;
  let refused = false;
  try {
    if (ONLY !== 'heap') {
      console.error('[p0] clock row ...');
      const c = await clockRow(chromium);
      out.clock = c.results;
      for (const [k, v] of Object.entries(c.results)) {
        console.log(`;; ==== P0 clock / ${k} ====`);
        console.log(v);
      }
      if (c.err) failed = `clock: ${c.err}`;
      if (c.refused && c.refused.length) {
        refused = true;
        console.log(';;');
        console.log(';; ==== ARM ORDER: THESE CLOCK ROWS ARE NOT REPORTABLE ====');
        console.log(`;;   ${c.refused.join(', ')}`);
      }
    }
    if (ONLY !== 'clock') {
      console.error('[p0] heap row ...');
      out.heap = await heapRow(chromium);
      summariseHeap(out.heap);
      if (out.heap.verification.unverified > 0) {
        failed = `heap: ${out.heap.verification.unverified} unverified mounts`;
      }
      refused = refused || out.heap.orderRefused;
    }
  } catch (e) {
    failed = String(e && e.stack ? e.stack : e);
  } finally {
    server.close();
  }
  const raw = process.env.P0_RAW_OUT;
  if (raw) {
    fs.mkdirSync(path.dirname(raw), { recursive: true });
    fs.writeFileSync(raw, JSON.stringify(out, null, 2));
    console.error(`[p0] raw data -> ${raw}`);
  }
  if (failed) {
    console.error(`[p0] FAILED: ${failed}`);
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
