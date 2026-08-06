#!/usr/bin/env node
// EP-0038 P0 — the driver. Build the `:advanced` bundle once, run both
// rows, print the table, exit on a refusal.
//
//   node implementation/core/test/re_frame/bench/p0_run.cjs
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only clock
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only heap
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only fanout
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
//   P0_ROUNDS=6 P0_SAMPLES=12 node .../p0_run.cjs
//
// `--only ladder` is the PER-READ row (rf2-2rtt6.34): B and the witness
// held fixed while READS walk HD-002's 1/3/7/20 at Q = E, on three
// substrates — the two donors and the Hicasso candidate — so the
// candidate is judged against donor rows taken on its own instrument
// (validation.md:180-189). Opt-in, runs nothing else, and takes
// `P0_LADDER_RUNGS` and `P0_LADDER_ROUNDS`.
//
// `--only fanout` is the CACHE-CARDINALITY row (rf2-5prok): the same page,
// readers, collector and guard as the heap row, with B and reads/boundary
// held fixed while the number of UNIQUE live query keys moves. It is
// opt-in, runs nothing else, and takes `P0_ROOTS` and `P0_FAN_ROUNDS`.
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
// build-id touches hot-zone sequenced. So this driver does what
// `b6_prod_run.cjs` and `b7_run.cjs` already do: it merges an output
// directory and an `:init-fn` into an EXISTING `:advanced` `:browser`
// build id, which contributes nothing but its compiler settings —
// `:target :browser`, `:optimizations :advanced`, `:infer-externs :auto`,
// `goog.DEBUG false`. The module's entry, and therefore everything that
// ends up in the bundle, is this arm's. The default id is rf2-2rtt6.2's
// measurement lane, `:hicasso-bench` — the id the lane landed for exactly
// this ride — and `P0_BUILD` overrides it. One id serves N programs, so
// the driven id's cache entry is cleared before every build
// (`lane_cache.cjs`, rf2-2rtt6.20): a sibling arm's stale `shadow-js/`
// index compiles clean and dies at runtime under `:advanced`, and the
// trap is the ride itself — a foreign `:init-fn` merged onto an existing
// id — not any one donor.
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

// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../../../freehand/test/re_frame/freehand/bench/lane_cache.cjs');
// The bench lane's one page-failure collector, reached by path across the
// test trees exactly as `lane_cache.cjs` is (rf2-sib23).
const { watchPage } = require('../../../../freehand/test/re_frame/freehand/bench/sentinel.cjs');

const IMPL = path.resolve(__dirname, '../../../..');

const BUILD = process.env.P0_BUILD || 'hicasso-bench';
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
// The fan-out sweep (rf2-5prok)
// ---------------------------------------------------------------------------
//
// The heap-regime ruling (rf2-2rtt6.16) made cache cardinality part of the
// witness: a retained-bytes-per-boundary figure is defined only relative to
// how many boundaries share a subscription. `--only fanout` is the row that
// walks that axis and nothing else — the same page, the same readers, the
// same collector, the same guard, and B and E/B held fixed while Q moves.
//
// The rungs, per substrate, at whatever `P0_ROOTS` sets B to:
//
//   R0      0 reads          — the boundary SHELL, Q = 0
//   R1Q1    1 read,  Q = B   — fan-out 1, the distinct-query worst case
//   R1Q2    1 read,  Q = B/2 — fan-out 2
//   R1Q4    1 read,  Q = B/4 — fan-out 4, which at ROOTS=4 is exactly the
//                              regime rf2-2rtt6.4's published grid rows were
//                              measured in
//   R1Q8    1 read,  Q = B/8 — fan-out 8
//   R2Q2B   2 reads, Q = 2B  — held out of the fit
//   R2QB2   2 reads, Q = B/2 — held out of the fit
//
// and the published `grid/<substrate>` arm rides along unchanged as the
// REPRODUCTION ANCHOR: at ROOTS=4 it is the same B, E and Q as R1Q4 through
// a different query id, so the two agreeing is a same-run check that the
// fan family is measuring the published family's quantity.
//
// Why two R=2 rungs. They are the only rungs the model is not fitted to,
// and they are what turns a curve fit into a test: `R2QB2 − R1Q2` is one
// extra edge per boundary at IDENTICAL Q, which is the per-edge term with
// nothing else moving, and the R=2 pair prices the per-key term a second
// time from samples the R=1 slope never saw.
const FAN_ROUNDS = Number(process.env.P0_FAN_ROUNDS || 6);

function fanRungs(boundaries) {
  const B = boundaries;
  return [
    { rung: 'R0', reads: 0, keys: 0 },
    { rung: 'R1Q1', reads: 1, keys: B },
    { rung: 'R1Q2', reads: 1, keys: Math.round(B / 2) },
    { rung: 'R1Q4', reads: 1, keys: Math.round(B / 4) },
    { rung: 'R1Q8', reads: 1, keys: Math.round(B / 8) },
    { rung: 'R2Q2B', reads: 2, keys: 2 * B },
    { rung: 'R2QB2', reads: 2, keys: Math.round(B / 2) },
  ];
}

const FAN_SUBSTRATE = { 'reagent-subs': 'reagent', 'uix-subs': 'uix' };

function fanPlan(perRoot, roots) {
  const B = roots * perRoot.grid;
  return Object.keys(FAN_SUBSTRATE).map((segment) => {
    const sub = FAN_SUBSTRATE[segment];
    const arms = [
      { arm: 'grid/floor', key: `${segment}|grid/floor`, boundaries: B, opts: null, rung: 'floor' },
    ];
    for (const r of fanRungs(B)) {
      arms.push({
        arm: `fan/${sub}`,
        key: `${segment}|fan/${sub}#${r.rung}`,
        boundaries: B,
        opts: { reads: r.reads, keys: r.keys },
        rung: r.rung,
        reads: r.reads,
        keys: r.keys,
      });
    }
    arms.push({
      arm: `grid/${sub}`,
      key: `${segment}|grid/${sub}`,
      boundaries: B,
      opts: null,
      rung: 'anchor',
      reads: 1,
      keys: perRoot.grid,
    });
    return { segment, arms };
  });
}

// ---------------------------------------------------------------------------
// The reads ladder (rf2-2rtt6.34)
// ---------------------------------------------------------------------------
//
// `--only ladder` is the PER-READ row: B held fixed, reads walked over
// HD-002's mandated 1/3/7/20, and Q pinned to E — the distinct-query
// worst case, which is the regime both published per-read gates are
// stated in (validation.md:136-140).
//
// It carries THREE substrates where every other row here carries two.
// validation.md:180-189 judges a candidate against the donor row taken
// on its OWN instrument, and calls a margin under 5% instrument-limited
// rather than cleared; this instrument has no 3/7/20 rung on any
// substrate, so a candidate measured here against the freehand ladder's
// donors would be quoting a ~5% cross-instrument offset as a result. The
// donors are therefore re-taken here, in the same rounds, under the same
// collector and the same guard.
//
// The candidate rides BOTH segments, and that is a measurement rather
// than a duplicate. It needs neither adapter's HOOKS, but its reads go
// through `re-frame.subs`, whose reaction implementation comes from the
// installed adapter's reactive substrate — so the two candidate columns
// are one view layer over two subscription substrates. A per-read claim
// has to be stated against that pair: a view layer cannot be cheaper
// than the reactions it holds, and which donor it is being compared to
// decides which substrate is under it.
const LADDER_RUNGS = (process.env.P0_LADDER_RUNGS || '0,1,3,7,20')
  .split(',')
  .map((s) => Number(s.trim()));
const LADDER_ROUNDS = Number(process.env.P0_LADDER_ROUNDS || 6);

const LADDER_SUBSTRATES = {
  'reagent-subs': ['reagent', 'hicasso'],
  'uix-subs': ['uix', 'hicasso'],
};

function ladderPlan(perRoot, roots) {
  const B = roots * perRoot.grid;
  return Object.keys(LADDER_SUBSTRATES).map((segment) => {
    const arms = [
      { arm: 'grid/floor', key: `${segment}|grid/floor`, boundaries: B, opts: null, rung: 'floor' },
    ];
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const R of LADDER_RUNGS) {
        arms.push({
          arm: `lad/${sub}`,
          key: `${segment}|lad/${sub}#R${R}`,
          boundaries: B,
          opts: { reads: R, keys: B * R },
          rung: `R${R}`,
          reads: R,
          keys: B * R,
          substrate: sub,
        });
      }
    }
    return { segment, arms };
  });
}

function legacyPlan(perRoot, roots) {
  return HEAP_SEGMENTS.map(({ segment, arms }) => ({
    segment,
    arms: arms.map((arm) => ({
      arm,
      key: `${segment}|${arm}`,
      boundaries: roots * perRoot[arm.split('/')[0]],
      opts: null,
      rung: arm,
    })),
  }));
}

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and then reports `EOF while
// reading` from a fragment.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." :modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  // The lane's cache rule, before anything reads the cache: this driver
  // merges its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry was
  // written by a different program. `lane_cache.cjs` carries the measured
  // fault and the rejected alternatives (rf2-2rtt6.20).
  if (resetLaneBuildCache(IMPL, BUILD)) {
    console.error(`[p0] cleared .shadow-cljs/builds/${BUILD} — one build id, N arms (rf2-2rtt6.20)`);
  }
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
// One entry per page this run opened, and this driver opens MANY — one per
// clock round, plus one for each of the heap, fanout and ladder rows.
// Flattened once, at the exit.
const PAGE_WATCHES = [];
const pageFailures = () =>
  PAGE_WATCHES.flatMap((w) => w.failures).map((f) => `${f.kind}: ${f.detail}`);

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
  // AND THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23).
  // The console handler above already refuses to filter a React warning out
  // of the operator's view, for exactly the reason stated there — and one
  // line below it, an UNCAUGHT THROW was printed and recorded nowhere, so the
  // run exited 0 on top of it. `sentinel.cjs`'s header carries the finding,
  // including why no page-side `try`/`catch` can close it under React 19.2.
  // ONE PAGE PER CLOCK ROUND is the reason these are collected rather than
  // held in a local: a throw in any round has to reach the one exit. The
  // watch is also RETURNED, because every caller races its own sentinel
  // against it (rf2-qv761) — this driver's clock wait is the largest budget
  // in the fleet at thirty minutes, and a page that dies at load used to
  // spend all of it before saying so.
  const watch = watchPage(page, 'p0');
  PAGE_WATCHES.push(watch);
  await page.goto(`http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeout: 120000,
  });
  return { browser, page, watch };
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
    const { browser, page, watch } = await newPage(chromium, q);
    // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`. `race`
    // rejects only on a failure `watch` recorded, and the exit block already
    // folds exactly those failures into `failures`, so no run that would have
    // passed is shortened. The rejection lands in the driver's existing
    // `catch`, which pushes onto `failures` — this driver's exit 1. The
    // arm-order guard keeps its 2.
    await watch.race('window.P0_DONE === true || window.P0_ERROR', {
      timeoutMs: CLOCK_TIMEOUT_MS,
      budget: `the ${Math.round(CLOCK_TIMEOUT_MS / 60000)}-minute wait for window.P0_DONE (round ${r})`,
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
  const { browser, page, watch } = await newPage(chromium, '?mode=aggregate');
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`.
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the aggregate page)',
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

// ONE measurement engine, two plans. The heap row's plan is the published
// four-arm one; the fan-out row's walks cache cardinality. Everything
// between them — the warm-up pass, the collector, the in-situ control, the
// slot order, the read-back gates, the arm-order verdict — is shared, so
// the two rows cannot drift into being two instruments wearing one name.
// `analyse` runs with the page STILL OPEN, because the rules it reaches
// live in ClojureScript.
async function heapPass(
  chromium,
  { benchmark, bead, plan: planOf, roots, rounds: nRounds, preflight, analyse }
) {
  const { browser, page, watch } = await newPage(chromium, '?mode=heap');
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`.
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the heap page)',
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`heap page failed to initialise: ${err}`);
  }
  if (preflight) {
    try {
      await preflight(page);
    } catch (e) {
      await browser.close();
      throw e;
    }
  }
  const perRoot = await page.evaluate(() => window.P0H.boundariesPerRoot);
  const plan = planOf(perRoot, roots);
  const { gc, read } = await makeReaders(page);

  let unverified = 0;
  let mounts = 0;
  const unverifiedDetail = [];
  const orderSamples = [];
  let position = 0;
  let previous = null;

  const mountOne = async (entry) => {
    const v = await page.evaluate(
      ([a, k, o]) => window.P0H.mount(a, k, o),
      [entry.arm, roots, entry.opts]
    );
    mounts++;
    if (!v.ok) {
      unverified++;
      unverifiedDetail.push(
        `${entry.key}: elements ${v.elements}/${v.expected}, keys ${v.keys}/${v.keysExpected}`
      );
    }
    return v;
  };

  // A WARM-UP PASS, mounted and released once per arm and never read. The
  // first mount of any arm allocates things that are not the page and
  // never go away: compiled code for the paths it just took, inline
  // caches, interned keywords, one-time module state. Charged to round 1
  // they read as retention per boundary, and they are not.
  console.error('[p0] heap warm-up pass ...');
  for (const { segment, arms } of plan) {
    await page.evaluate((s) => window.P0H.prepare(s), segment);
    for (const entry of arms) {
      await mountOne(entry);
      await page.evaluate(() => window.P0H.release());
    }
  }
  await page.evaluate((n) => window.P0H.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.P0H.controlRelease());

  const rounds = [];
  for (let round = 0; round < nRounds; round++) {
    console.error(`[p0] heap round ${round + 1}/${nRounds}`);

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
    const segs = round % 2 === 0 ? plan : [...plan].slice().reverse();
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
        const entry = arms[j];
        await gc();
        const pre = await read();
        const verify = await mountOne(entry);
        await gc();
        const held = await read();
        await page.evaluate(() => window.P0H.release());
        await gc();
        const post = await read();
        // THE SURVIVAL METRIC'S STRUCTURAL HALF (rf2-2rtt6.34), read
        // here and not one line earlier: the Hicasso runtime reaps a
        // cell and a read-set entry whose last holder left on the NEXT
        // MACROTASK, so a residue read immediately after `release()`
        // would report a cache that is about to evict itself as a leak.
        // The collector above has just spent three passes with an 80 ms
        // beat between them, which is that macrotask several times over.
        const structural = await page.evaluate(() =>
          window.P0H.hicassoResidue ? window.P0H.hicassoResidue() : null
        );
        const boundaries = entry.boundaries;
        armsOut[entry.key] = {
          segment,
          arm: entry.arm,
          rung: entry.rung,
          reads: entry.reads,
          keys: entry.keys,
          verify,
          structural,
          boundaries,
          retainedCdp: held.cdp - pre.cdp,
          retainedPerf: held.perf - pre.perf,
          residueCdp: post.cdp - pre.cdp,
          bytesPerBoundaryCdp: (held.cdp - pre.cdp) / boundaries,
          bytesPerBoundaryPerf: (held.perf - pre.perf) / boundaries,
        };
        orderSamples.push({
          arm: entry.key,
          value: armsOut[entry.key].bytesPerBoundaryCdp,
          predecessor: previous,
          position: position++,
        });
        previous = entry.key;
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

  const extra = analyse ? await analyse(page, rounds, plan) : {};

  await browser.close();

  return Object.assign(
    {
      benchmark,
      bead,
      roots,
      perRoot,
      rounds: nRounds,
      plan: plan.map((p) => ({
        segment: p.segment,
        arms: p.arms.map((a) => ({
          key: a.key,
          arm: a.arm,
          rung: a.rung,
          boundaries: a.boundaries,
          reads: a.reads,
          keys: a.keys,
        })),
      })),
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
      verification: { mounts, unverified, detail: unverifiedDetail },
      perRound: rounds,
      orderVerdictEdn: verdictEdn,
      orderRefused: /:refuse\? true/.test(verdictEdn),
    },
    extra
  );
}

async function heapRow(chromium) {
  const row = await heapPass(chromium, {
    benchmark: 'P0:retained-heap-per-boundary',
    bead: 'rf2-2rtt6.4',
    plan: legacyPlan,
    roots: ROOTS,
    rounds: ROUNDS,
  });
  row.segments = HEAP_SEGMENTS;
  return row;
}

// ---------------------------------------------------------------------------
// The fan-out row
// ---------------------------------------------------------------------------

async function fanoutRow(chromium) {
  return heapPass(chromium, {
    benchmark: 'P0:retained-heap-fan-out-sweep',
    bead: 'rf2-5prok',
    plan: fanPlan,
    roots: ROOTS,
    rounds: FAN_ROUNDS,
    // The adjudicator's own self-test, BEFORE anything is measured, on the
    // same footing as the arm-order guard's: two synthetic pages built by
    // arithmetic, one exactly additive and one carrying a quadratic key
    // term. The first has to be priced back to its own three terms and the
    // second has to be REFUSED — and refused out of sample, since its r²
    // clears the linearity floor. A fit rule that cannot fail would make
    // every price below unfalsifiable.
    preflight: async (page) => {
      const st = await page.evaluate(() => window.P0H.fanSelfTest());
      for (const c of st.checks) {
        console.log(`;; P0 fan-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
      }
      if (!st.ok) {
        throw new Error(
          'the additive-model self-test FAILED — no fan-out figure may be priced'
        );
      }
    },
    // The fit and its refusal rule are ClojureScript
    // (`p0-heap/additive-fit`), reached through the page while it is still
    // open, for the same reason the arm-order verdict and the control
    // verdict are: a JavaScript restatement would be a second place for the
    // rule to drift, and this one decides whether a component price may be
    // quoted at all.
    analyse: async (page, rounds) => {
      const substrates = Object.keys(FAN_SUBSTRATE);
      const perRoundFits = {};
      const meanFits = {};
      for (const segment of substrates) {
        const sub = FAN_SUBSTRATE[segment];
        const rungsOf = (r) => {
          const floor = r.arms[`${segment}|grid/floor`];
          return fanRungs(floor.boundaries).map((g) => {
            const a = r.arms[`${segment}|fan/${sub}#${g.rung}`];
            return {
              rung: g.rung,
              reads: g.reads,
              keys: g.keys,
              boundaries: a.boundaries,
              y: a.bytesPerBoundaryCdp - floor.bytesPerBoundaryCdp,
            };
          });
        };
        perRoundFits[segment] = [];
        for (const r of rounds) {
          perRoundFits[segment].push(
            await page.evaluate((rs) => window.P0H.fanVerdict(rs), rungsOf(r))
          );
        }
        // The headline fit, over the per-rung MEANS. Reported beside the
        // per-round fits and never instead of them: a criterion applied
        // only to a mean is a criterion a single bad round can hide from.
        const all = rounds.map(rungsOf);
        const meanRungs = all[0].map((g, i) => ({
          ...g,
          y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
        }));
        meanFits[segment] = await page.evaluate((rs) => window.P0H.fanVerdict(rs), meanRungs);
        meanFits[segment].rungs = meanRungs;
      }
      return { fanFits: { perRound: perRoundFits, mean: meanFits } };
    },
  });
}

// ---------------------------------------------------------------------------
// The ladder row (rf2-2rtt6.34)
// ---------------------------------------------------------------------------

async function ladderRow(chromium) {
  return heapPass(chromium, {
    benchmark: 'P0:retained-heap-reads-ladder',
    bead: 'rf2-2rtt6.34',
    plan: ladderPlan,
    roots: ROOTS,
    rounds: LADDER_ROUNDS,
    // The fit rule's own control, BEFORE anything is measured, on the
    // same footing as the arm-order guard's and the additive model's:
    // an exact line has to be recovered to the byte, a QUADRATIC page
    // has to be refused by the r² floor at a value predicted in
    // advance, and a fit that used the forbidden R=0 rung has to be
    // caught. The third is the defect the audit of PR #7260 found in
    // the predecessor ladder, and it is a check of this instrument's
    // arithmetic — not corroboration of any measurement.
    preflight: async (page) => {
      const st = await page.evaluate(() => window.P0H.ladderSelfTest());
      for (const c of st.checks) {
        console.log(`;; P0 ladder-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
      }
      if (!st.ok) {
        throw new Error('the ladder-fit self-test FAILED — no per-read slope may be priced');
      }
    },
    analyse: async (page, rounds, plan) => {
      const fits = { perRound: {}, mean: {} };
      for (const { segment } of plan) {
        for (const sub of LADDER_SUBSTRATES[segment]) {
          const id = `${segment}|${sub}`;
          const rungsOf = (r) => {
            const floor = r.arms[`${segment}|grid/floor`];
            return LADDER_RUNGS.map((R) => {
              const a = r.arms[`${segment}|lad/${sub}#R${R}`];
              return {
                rung: `R${R}`,
                reads: R,
                y: a.bytesPerBoundaryCdp - floor.bytesPerBoundaryCdp,
              };
            });
          };
          fits.perRound[id] = [];
          for (const r of rounds) {
            fits.perRound[id].push(await page.evaluate((rs) => window.P0H.ladderFit(rs), rungsOf(r)));
          }
          // The headline fit is over the per-rung MEANS, reported beside
          // the per-round fits and never instead of them — a criterion
          // applied only to a mean is one a single bad round can hide from.
          const all = rounds.map(rungsOf);
          const meanRungs = all[0].map((g, i) => ({
            ...g,
            y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
          }));
          fits.mean[id] = await page.evaluate((rs) => window.P0H.ladderFit(rs), meanRungs);
          fits.mean[id].rungs = meanRungs;
        }
      }
      return { ladderFits: fits };
    },
  });
}

// ---------------------------------------------------------------------------
// The allocation row (rf2-2rtt6.76) — the survival metric's OTHER half
// ---------------------------------------------------------------------------
//
// `--only alloc` is the STEADY-STATE ALLOCATION SLOPE across warm 1/3/7/20
// reads. It is opt-in and runs nothing else, on the same terms as `ladder`
// and `fanout`.
//
// It is not a variant of the ladder and it does not re-measure it. The
// ladder prices what a boundary KEEPS per read; this prices what a warm
// re-render THROWS AWAY per read. `p0_heap.cljs`'s own header says
// "Nothing here counts allocations", and that is exactly why HD-002's
// survival metric has been half-witnessed: the zero-retained-per-
// occurrence clause is answered by the ladder's residue column and its
// structural stamp (rf2-2rtt6.9), and the allocation clause has never had
// an instrument on this rig at all.
//
// THE SHAPE OF A READING. Collect; then run N warm bulk writes with the
// used-heap counter sampled on both sides of every one of them; accumulate
// the RISING steps and the FALLING steps separately. A rising-step sum is
// an allocation total whether or not a collection intervened, because a
// collection is excluded from it rather than netted against it. Where one
// did land the sum is a slight UNDER-estimate, bounded by one leg per
// collection, and the collection count is published beside every figure.
//
// WHAT IS BEING WRITTEN. One `dispatch-sync` of `:p0/write-all` — the same
// event the bulk clock arms drive — through the event pipeline and the
// signal graph, followed by the substrate's own drain. Every boundary
// re-renders and every boundary's READ SET IS UNCHANGED, which is the
// steady state HD-002's cost law is stated over.
//
// WHY THE DONORS RIDE ALONG. A warm re-render at R reads allocates R query
// vectors in the arm's own body, R subscription recomputations and a React
// element tree, on EVERY substrate. HD-002's claim is about edge
// maintenance alone, so the quantity that answers it is the candidate's
// slope LESS the same-run donor's. A candidate slope quoted on its own
// would be mostly other people's allocation, which is why this row carries
// the donors in the same rounds under the same collector exactly as the
// ladder does.
//
// THE CONTROLS. Three, and the run exits on the first two:
//
//   idle       a window with no work in it at all — the sampler's own
//              footprint, which is otherwise an unexamined constant
//              sitting inside every figure in the table;
//   control    a dropped `.slice()` of D doubles per iteration, costing a
//              PREDICTED 8D bytes, read DIRECTLY;
//   control'   the same at a second D, so the per-double cost is also read
//              as a SLOPE between the two, which cancels every constant
//              including the sampler's footprint.
//
// A retention instrument reads both control figures as ZERO. That is the
// entire claim this row has to establish before any arm is quoted, and it
// is the check `b8-alloc` was built around after the sampling profiler
// produced a wrong table on this surface.
const ALLOC_WRITES = Number(process.env.P0_ALLOC_WRITES || 30);
const ALLOC_ROUNDS = Number(process.env.P0_ALLOC_ROUNDS || 6);
const ALLOC_WARMUPS = Number(process.env.P0_ALLOC_WARMUPS || 3);
// 8 B a double, so 100,000 doubles is a predicted 800,000 B of garbage per
// iteration — well clear of the per-write traffic the arms produce, and
// well inside the young generation at these window sizes.
const ALLOC_D = Number(process.env.P0_ALLOC_D || 100000);
const ALLOC_D2 = Number(process.env.P0_ALLOC_D2 || 40000);
// How far the two control readings may sit from 8 B/double and still count
// as THE INSTRUMENT CAN SEE GARBAGE. Generous on purpose: the claim being
// gated is that transient bytes are visible AT ALL, not that V8's
// bookkeeping is exactly 8 B wide. `b8-alloc` measured a stable 12.06
// B/double against the same 8 B arithmetic and could not close the gap, so
// a band that demanded 8 exactly would refuse a working instrument.
const ALLOC_CONTROL_SLACK = Number(process.env.P0_ALLOC_CONTROL_SLACK || 0.75);

// Rising and falling steps, accumulated separately, from one window's raw
// samples. The samples are `[s0, pre0, post0, pre1, post1, ...]`, so the
// work legs are the (pre -> post) steps and the gaps between iterations
// are the (post -> pre) steps; both are walked, because a collection can
// fall in either.
function allocSteps(samples) {
  let rise = 0;
  let fall = 0;
  let falls = 0;
  for (let i = 1; i < samples.length; i++) {
    const d = samples[i] - samples[i - 1];
    if (d > 0) rise += d;
    else if (d < 0) {
      fall += -d;
      falls++;
    }
  }
  return {
    rise,
    fall,
    falls,
    endpoints: samples[samples.length - 1] - samples[0],
  };
}

async function allocRow(chromium) {
  const { browser, page, watch } = await newPage(chromium, '?mode=heap');
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the alloc page)',
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`alloc page failed to initialise: ${err}`);
  }

  // The fit rule's own self-test, BEFORE anything is measured. This row
  // fits with `p0-heap/ladder-fit` — the SAME rule the reads ladder uses,
  // with the same r² floor and the same R=0 exclusion — so it inherits
  // that rule's control unchanged rather than growing a second one.
  const st = await page.evaluate(() => window.P0H.ladderSelfTest());
  for (const c of st.checks) {
    console.log(`;; P0 alloc-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
  }
  if (!st.ok) {
    await browser.close();
    throw new Error('the ladder-fit self-test FAILED — no allocation slope may be priced');
  }

  const perRoot = await page.evaluate(() => window.P0H.boundariesPerRoot);
  const B = ROOTS * perRoot.grid;
  const plan = ladderPlan(perRoot, ROOTS);
  const { gc, read } = await makeReaders(page);

  // --- the precise-memory flag, PROVED rather than trusted --------------
  // Without `--enable-precise-memory-info` Chrome quantises the in-page
  // counter to 100 KB buckets and every figure here would be noise. A
  // quantised counter is not a small error; it is a different instrument.
  await page.evaluate(([d, n]) => window.P0H.allocPrepare(d, n), [ALLOC_D, ALLOC_WRITES]);
  const probe = await page.evaluate(
    (n) => window.P0H.allocWindow(n, 'control', 'react'),
    ALLOC_WRITES
  );
  const rounded = probe.samples.filter((x) => x % 100000 === 0).length;
  const precise = rounded < probe.samples.length;
  if (!precise) {
    await browser.close();
    throw new Error(
      `--enable-precise-memory-info did not take: all ${probe.samples.length} readings are ` +
        'multiples of 100,000, so the counter is quantised and nothing here is measurable'
    );
  }

  let unverified = 0;
  const unverifiedDetail = [];
  const rounds = [];

  for (let round = 0; round < ALLOC_ROUNDS; round++) {
    console.error(`[p0] alloc round ${round + 1}/${ALLOC_ROUNDS}`);
    const armsOut = {};

    // --- the three controls, in situ, before this round's arms ----------
    const controlOf = async (kind, d) => {
      await page.evaluate(([dd, n]) => window.P0H.allocPrepare(dd, n), [d, ALLOC_WRITES]);
      await gc();
      const pre = await read();
      const w = await page.evaluate(
        ([n, k]) => window.P0H.allocWindow(n, k, 'react'),
        [ALLOC_WRITES, kind]
      );
      const post = await read();
      const s = allocSteps(w.samples);
      return { kind, d, ...s, perIter: s.rise / ALLOC_WRITES, cdpBracket: post.cdp - pre.cdp };
    };
    const idle = await controlOf('idle', 0);
    const ctl1 = await controlOf('control', ALLOC_D);
    const ctl2 = await controlOf('control', ALLOC_D2);

    // --- the arms, in the order this round's parity dictates ------------
    const segs = round % 2 === 0 ? plan : [...plan].slice().reverse();
    for (const { segment, arms } of segs) {
      await page.evaluate((s) => window.P0H.prepare(s), segment);
      const drain = segment === 'reagent-subs' ? 'reagent' : 'react';
      const order = await page.evaluate(
        ([n, r]) => window.P0H.slotOrder(n, r),
        [arms.length, round]
      );
      for (const j of order) {
        const entry = arms[j];
        // Mount, and KEEP it. Nothing is released inside this row — the
        // whole quantity is what a STANDING page allocates when it is
        // written to, and an arm torn down between windows would be
        // measuring a mount.
        const v = await page.evaluate(
          ([a, k, o]) => window.P0H.mount(a, k, o),
          [entry.arm, ROOTS, entry.opts]
        );
        if (!v.ok) {
          unverified++;
          unverifiedDetail.push(
            `${entry.key}: elements ${v.elements}/${v.expected}, keys ${v.keys}/${v.keysExpected}`
          );
        }
        // A WARM-UP PASS at the REAL window size, and not a token one. A
        // measurement site reads well above its settled value until it has
        // run several full-size windows: `b8-alloc`'s driver watched a
        // first window read 5.3x its settled value with nothing else
        // varying, and its own instrument pseudo-arm read 9.9x. Warming
        // with anything smaller than the window would leave that inside
        // the first round of every arm.
        await page.evaluate(([dd, n]) => window.P0H.allocPrepare(dd, n), [0, ALLOC_WRITES]);
        for (let w = 0; w < ALLOC_WARMUPS; w++) {
          await page.evaluate(
            ([n, d]) => window.P0H.allocWindow(n, 'write', d),
            [ALLOC_WRITES, drain]
          );
        }
        await gc();
        const pre = await read();
        const win = await page.evaluate(
          ([n, d]) => window.P0H.allocWindow(n, 'write', d),
          [ALLOC_WRITES, drain]
        );
        const post = await read();
        await page.evaluate(() => window.P0H.release());
        const s = allocSteps(win.samples);
        // THE WRITE READ-BACK, and the row exits on it. At R reads of a
        // page whose cells were all written to `v`, a ladder boundary's
        // text is `R·v`; the floor has no subscription and cannot move.
        // A row whose writes never reached the page is the cheapest row in
        // any table, and this is the same class of gate as the mount
        // read-back the retention rows already carry.
        const R = entry.reads || 0;
        const want = String(R * ALLOC_WRITES);
        if (win.text !== want) {
          unverified++;
          unverifiedDetail.push(
            `${entry.key}: warm write read-back "${win.text}", expected "${want}"`
          );
        }
        armsOut[entry.key] = {
          segment,
          arm: entry.arm,
          rung: entry.rung,
          reads: R,
          boundaries: B,
          text: win.text,
          ...s,
          perWrite: s.rise / ALLOC_WRITES,
          perBoundaryPerWrite: s.rise / ALLOC_WRITES / B,
          cdpBracket: post.cdp - pre.cdp,
        };
      }
    }
    rounds.push({ round, controls: { idle, ctl1, ctl2 }, arms: armsOut });
  }

  // --- the fits, through the LADDER's rule, with the page still open ----
  const fits = { perRound: {}, mean: {} };
  for (const { segment } of plan) {
    for (const sub of LADDER_SUBSTRATES[segment]) {
      const id = `${segment}|${sub}`;
      const rungsOf = (r) =>
        LADDER_RUNGS.map((R) => ({
          rung: `R${R}`,
          reads: R,
          y: r.arms[`${segment}|lad/${sub}#R${R}`].perBoundaryPerWrite,
        }));
      fits.perRound[id] = [];
      for (const r of rounds) {
        fits.perRound[id].push(await page.evaluate((rs) => window.P0H.ladderFit(rs), rungsOf(r)));
      }
      const all = rounds.map(rungsOf);
      const meanRungs = all[0].map((g, i) => ({
        ...g,
        y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
      }));
      fits.mean[id] = await page.evaluate((rs) => window.P0H.ladderFit(rs), meanRungs);
      fits.mean[id].rungs = meanRungs;
    }
  }

  await browser.close();

  return {
    benchmark: 'P0:steady-state-allocation-slope',
    bead: 'rf2-2rtt6.76',
    roots: ROOTS,
    perRoot,
    boundaries: B,
    writes: ALLOC_WRITES,
    warmups: ALLOC_WARMUPS,
    rounds: ALLOC_ROUNDS,
    preciseMemory: precise,
    controlDoubles: { d1: ALLOC_D, d2: ALLOC_D2 },
    controlSlack: ALLOC_CONTROL_SLACK,
    instrument:
      'in-page performance.memory.usedJSHeapSize sampled at every leg boundary, ' +
      'rising steps accumulated separately from falling ones; --enable-precise-memory-info',
    verification: { unverified, detail: unverifiedDetail },
    perRound: rounds,
    allocFits: fits,
  };
}

function summariseAlloc(row) {
  const B = row.boundaries;
  console.log('\n;; ==== P0 STEADY-STATE ALLOCATION — WARM 1/3/7/20 READS (rf2-2rtt6.76) ====');
  console.log(
    `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
      'held FIXED across every rung'
  );
  console.log(
    `;; ${row.rounds} rounds x ${row.writes} warm bulk writes, after ${row.warmups} full-size ` +
      'warm-up windows. Q = E on every rung.'
  );
  console.log(';; The arm stays MOUNTED across the window: this is what a standing page');
  console.log(';; allocates when it is written to, not what a mount costs.');

  // --- the controls, adjudicated ----------------------------------------
  console.log(';;');
  console.log(';; ==== THE CONTROLS ====');
  const cstat = (f) => stat(row.perRound.map(f));
  const idle = cstat((r) => r.controls.idle.perIter);
  const c1 = cstat((r) => r.controls.ctl1.perIter);
  const c2 = cstat((r) => r.controls.ctl2.perIter);
  const d1 = row.controlDoubles.d1;
  const d2 = row.controlDoubles.d2;
  console.log(
    `;;   idle window (the sampler's own footprint): ${n0(idle.mean)} B/iteration ` +
      `[${n0(idle.min)}–${n0(idle.max)}]`
  );
  console.log(
    `;;   control D=${d1}: predicted ${8 * d1} B  |  measured ${n0(c1.mean)} B ` +
      `[${n0(c1.min)}–${n0(c1.max)}]  = ${(c1.mean / d1).toFixed(2)} B/double`
  );
  console.log(
    `;;   control D=${d2}: predicted ${8 * d2} B  |  measured ${n0(c2.mean)} B ` +
      `[${n0(c2.min)}–${n0(c2.max)}]  = ${(c2.mean / d2).toFixed(2)} B/double`
  );
  // The DIFFERENTIAL reading, which cancels every constant including the
  // sampler's own footprint. It is the one of the two that a residual
  // per-window overhead cannot flatter.
  const slopePerDouble = (c1.mean - c2.mean) / (d1 - d2);
  console.log(
    `;;   DIFFERENTIAL (D=${d1} less D=${d2}): ${slopePerDouble.toFixed(2)} B/double ` +
      '— cancels the sampler footprint and every other constant'
  );
  const within = (x) => Math.abs(x - 8) / 8 <= row.controlSlack;
  const controlOk = within(c1.mean / d1) && within(slopePerDouble);
  console.log(
    `;;   VERDICT (slack ±${(row.controlSlack * 100).toFixed(0)}% around 8 B/double): ` +
      `${controlOk ? 'OK — transient garbage IS visible to this counter' : 'FAILED'}`
  );
  console.log(
    ';;   A RETENTION instrument reads both control figures as ZERO. That is what the'
  );
  console.log(
    ';;   CDP sampling profiler does on this surface, and why it is not used here.'
  );
  row.controlVerdict = { ok: controlOk, perDouble: c1.mean / d1, differential: slopePerDouble };

  const falls = row.perRound.reduce(
    (a, r) => a + Object.values(r.arms).reduce((b, x) => b + x.falls, 0),
    0
  );
  const wins = row.perRound.reduce((a, r) => a + Object.keys(r.arms).length, 0);
  console.log(';;');
  console.log(
    `;;   collections seen inside arm windows: ${falls} falling steps across ${wins} windows ` +
      '(a fall is EXCLUDED from the rising sum, never netted against it)'
  );
  console.log(
    `;;   verification: ${row.verification.unverified} unverified ` +
      '(mount read-backs AND the warm-write read-back)'
  );
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  // --- the rows ----------------------------------------------------------
  for (const segment of Object.keys(LADDER_SUBSTRATES)) {
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    console.log(
      ';; arm            reads        B/boundary/write [min–max]        B/write        falls'
    );
    const floorKey = `${segment}|grid/floor`;
    const fl = stat(row.perRound.map((r) => r.arms[floorKey].perBoundaryPerWrite));
    const flw = stat(row.perRound.map((r) => r.arms[floorKey].perWrite));
    console.log(
      `;; floor              — ${(n0(fl.mean) + ' [' + n0(fl.min) + '–' + n0(fl.max) + ']').padStart(30)}` +
        `${n0(flw.mean).padStart(15)}   (no subscription: the WRITE's own cost)`
    );
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const R of LADDER_RUNGS) {
        const key = `${segment}|lad/${sub}#R${R}`;
        const s = stat(row.perRound.map((r) => r.arms[key].perBoundaryPerWrite));
        const w = stat(row.perRound.map((r) => r.arms[key].perWrite));
        const f = row.perRound.reduce((a, r) => a + r.arms[key].falls, 0);
        console.log(
          `;; ${sub.padEnd(11)}${String(R).padStart(6)} ` +
            `${(n0(s.mean) + ' [' + n0(s.min) + '–' + n0(s.max) + ']').padStart(30)}` +
            `${n0(w.mean).padStart(15)}${String(f).padStart(9)}` +
            (R === 0 ? '   (anchor — regressed nowhere; cannot re-render)' : '')
        );
      }
    }
  }

  // --- the fitted lines ---------------------------------------------------
  console.log(';;');
  console.log(';; ==== THE FITTED LINES —  y = intercept + slope·R,  over 1/3/7/20 ONLY ====');
  console.log(';;   y is bytes ALLOCATED per boundary per warm write. The slope is what one');
  console.log(';;   more read costs a boundary that already reads, on a re-render whose READ');
  console.log(';;   SET DID NOT CHANGE. R=0 is the anchor and is regressed nowhere.');
  const fits = row.allocFits;
  for (const id of Object.keys(fits.mean)) {
    const m = fits.mean[id];
    const per = fits.perRound[id];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${id.padEnd(22)} slope ${n0(m.slope).padStart(6)} B/read ${rng((f) => f.slope)}` +
        `   intercept ${n0(m.intercept)} B ${rng((f) => f.intercept)}`
    );
    console.log(
      `;;     shell (R=0, measured) ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   ·   first read ${n0(m.firstRead)} B ${rng((f) => f.firstRead)}` +
        `   ·   r² ${m.r2.toFixed(5)}`
    );
    const nonlinear = per.filter((f) => !f.linear).length;
    console.log(
      `;;     ${m.linear ? 'LINE' : 'NOT A LINE'} — ${m.why}` +
        `  (per-round: ${per.length - nonlinear} of ${per.length} rounds linear)`
    );
  }

  // --- HD-002's own question ---------------------------------------------
  console.log(';;');
  console.log(';; ==== WHAT HD-002 PREDICTED, AND WHAT THE ROW SAYS ====');
  console.log(';;   HD-002 (hd-002-adjudication.md): "allocation is proportional to the CHANGE,');
  console.log(';;   not to the read count... the allocation slope across warm 1/3/7/20 reads is');
  console.log(';;   FLAT AT ZERO", falsified by a non-flat slope. That is a claim about EDGE');
  console.log(';;   MAINTENANCE, and every substrate here also allocates R query vectors, R sub');
  console.log(';;   recomputations and a React element tree. So the quantity that answers it is');
  console.log(';;   the candidate slope LESS the same-run donor slope, in the SAME segment.');
  const slopeOf = (id) => fits.mean[id] && fits.mean[id].slope;
  for (const seg of Object.keys(LADDER_SUBSTRATES)) {
    const donor = LADDER_SUBSTRATES[seg][0];
    const hc = slopeOf(`${seg}|hicasso`);
    const dn = slopeOf(`${seg}|${donor}`);
    if (typeof hc !== 'number' || typeof dn !== 'number') continue;
    console.log(
      `;;   ${seg.padEnd(13)} candidate ${n0(hc)} − ${donor} ${n0(dn)} = ` +
        `${n0(hc - dn)} B/read of EXCESS steady-state allocation`
    );
  }
  console.log(';;');
  console.log(';; ==== ARM-ORDER NOTE (alloc) ====');
  console.log(';;   This row mounts each arm and keeps it for the whole window, so it produces');
  console.log(";;   no mount/release sample stream for `order-guard`'s phase test. Segment order");
  console.log(';;   still alternates by round parity and slot order is still the guard\'s, so the');
  console.log(';;   ranges below are across BOTH orders — but the guard itself does not');
  console.log(';;   adjudicate this row and no figure here claims its verdict.');
}

// The candidate's structural claim, as numbers the run exits on. The
// arm IS `arm1.runtime`, so its own index and cell tables can be
// counted, and "one subscription/epoch hook per boundary plus N edges
// in a shared index" stops being a sentence in a docstring:
//
//   boundaries === B     one registration per boundary THAT READS —
//                        0 at R=0 (see below)
//   edges      === B·R   the reads live as index edges, not as hooks
//   cells      === Q     one cell per unique (frame, query) — Q = E here
//   entries    === B     one read-set entry per boundary AT Q = E, because
//                        no two boundaries read the same SET; the entry
//                        cache's sharing buys nothing on this witness and
//                        the row says so rather than claiming it does
//
// `boundaries` IS 0 AT R=0, AND THE ROW ASSERTS IT RATHER THAN EXCUSING
// IT. Since rf2-dabt3 fused the sub-index into the cell table there is no
// per-boundary registry to count: the runtime knows a boundary only
// through the reader lists of the cells it reads, so an edgeless boundary
// retains no membership anywhere and is correctly absent. That is the
// property the fusion was taken FOR — one reader list on the cell that
// already existed, in place of a map entry per mounted boundary whether
// or not it read — so the ladder pins it as a positive claim: at R=0 the
// count must be 0, and any non-zero reading means a mounted boundary is
// being retained by something. (`entries` says the same thing from the
// other side: 1 at R=0, the empty read-set, not B.)
//
// and on a DONOR arm every one of them must be 0 — the check that the
// candidate's runtime is not standing behind the rows it is compared to.
function ladderStructuralFailures(row) {
  const out = [];
  const B = row.plan[0].arms[0].boundaries;
  for (const r of row.perRound) {
    for (const [key, a] of Object.entries(r.arms)) {
      const h = a.verify && a.verify.hicasso;
      if (!h) continue;
      const hicasso = a.arm === 'lad/hicasso';
      const R = a.reads || 0;
      const want = hicasso
        ? {
            boundaries: R === 0 ? 0 : B,
            edges: B * R,
            cells: B * R,
            entries: R === 0 ? 1 : B,
          }
        : { boundaries: 0, edges: 0, cells: 0, entries: 0 };
      for (const f of Object.keys(want)) {
        if (h[f] !== want[f]) {
          out.push(`round ${r.round} ${key}: hicasso ${f} ${h[f]}, expected ${want[f]}`);
        }
      }
      const res = a.structural;
      if (res) {
        for (const f of ['cells', 'cellRefs', 'boundaries', 'edges', 'entries']) {
          if (res[f] !== 0) {
            out.push(
              `round ${r.round} ${key}: residue after teardown — hicasso ${f} ${res[f]}, expected 0`
            );
          }
        }
      }
    }
  }
  return out;
}

function summariseLadder(row, structuralFailures) {
  const B = row.plan[0].arms[0].boundaries;
  console.log('\n;; ==== P0 RETAINED HEAP — THE READS LADDER (rf2-2rtt6.34) ====');
  console.log(
    `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
      `held FIXED across every rung`
  );
  console.log(`;; ${row.rounds} rounds. Reads walk ${LADDER_RUNGS.join('/')}; Q = E on every rung`);
  console.log(';; (distinct-query, the mandatory worst-case witness — every read its own key).');
  console.log(';; Q is COUNTED off the frame\'s own sub-cache on every mount, not asserted.');
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
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  for (const { segment } of row.plan) {
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    const floorKey = `${segment}|grid/floor`;
    const floorStat = stat(row.perRound.map((r) => r.arms[floorKey].bytesPerBoundaryCdp));
    console.log(
      ';; arm            reads      B        E        Q     exclusive B/boundary [min–max]   residue B/bdy'
    );
    const floorRes = stat(row.perRound.map((r) => r.arms[floorKey].residueCdp / B));
    console.log(
      `;; floor              0 ${String(B).padStart(7)} ${String(0).padStart(8)} ${String(0).padStart(8)}   ` +
        `${n0(floorStat.mean).padStart(8)} [${n0(floorStat.min)}–${n0(floorStat.max)}]`.padEnd(26) +
        `${n0(floorRes.mean).padStart(6)} [${n0(floorRes.min)}–${n0(floorRes.max)}]` +
        '  (absolute, the calibrator)'
    );
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const R of LADDER_RUNGS) {
        const key = `${segment}|lad/${sub}#R${R}`;
        const excl = stat(
          row.perRound.map(
            (r) => r.arms[key].bytesPerBoundaryCdp - r.arms[floorKey].bytesPerBoundaryCdp
          )
        );
        // Residue PER BOUNDARY, so it is on the same axis as the
        // exclusive column beside it and comparable with the published
        // ±11 B/boundary the predecessor ladder reported. As a total it
        // reads in the tens of thousands on a 71 MB arm and looks like a
        // leak; divided by B it is the width of this instrument's zero.
        const res = stat(row.perRound.map((r) => r.arms[key].residueCdp / B));
        console.log(
          `;; ${sub.padEnd(11)}${String(R).padStart(6)} ${String(B).padStart(7)} ` +
            `${String(B * R).padStart(8)} ${String(B * R).padStart(8)}   ` +
            `${n0(excl.mean).padStart(8)} [${n0(excl.min)}–${n0(excl.max)}]`.padEnd(26) +
            `${n0(res.mean).padStart(6)} [${n0(res.min)}–${n0(res.max)}]` +
            (R === 0 ? '   (anchor — regressed nowhere)' : '')
        );
      }
    }
  }

  console.log(';;');
  console.log(';; ==== THE FITTED LINES —  y = intercept + slope·R,  over 1/3/7/20 ONLY ====');
  console.log(';;   The slope is MARGINAL: what the next read costs once a boundary already');
  console.log(';;   reads. The first read is a separate quantity and is printed beside it.');
  console.log(';;   `shell` is the DIRECTLY MEASURED R=0 rung, never the fitted intercept.');
  const fits = row.ladderFits;
  for (const id of Object.keys(fits.mean)) {
    const m = fits.mean[id];
    const per = fits.perRound[id];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${id.padEnd(22)} slope ${n0(m.slope).padStart(6)} B/read ${rng((f) => f.slope)}` +
        `   intercept ${n0(m.intercept)} B ${rng((f) => f.intercept)}`
    );
    console.log(
      `;;     shell (R=0, measured) ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   ·   first read ${n0(m.firstRead)} B ${rng((f) => f.firstRead)}` +
        `   ·   r² ${m.r2.toFixed(5)}`
    );
    const nonlinear = per.filter((f) => !f.linear).length;
    console.log(
      `;;     ${m.linear ? 'LINE' : 'NOT A LINE'} — ${m.why}` +
        `  (per-round: ${per.length - nonlinear} of ${per.length} rounds linear)`
    );
  }

  console.log(';;');
  console.log(';; ==== THE CANDIDATE, AGAINST THE DONORS TAKEN IN THE SAME RUN ====');
  console.log(';;   Same instrument, same rounds, same collector, same guard — which is what');
  console.log(';;   validation.md:180-189 requires, and what makes the margin quotable at all.');
  console.log(';;   A margin under 5% is INSTRUMENT-LIMITED and is not a pass.');
  const slopeOf = (id) => fits.mean[id] && fits.mean[id].slope;
  const rg = slopeOf('reagent-subs|reagent');
  const ux = slopeOf('uix-subs|uix');
  for (const seg of Object.keys(LADDER_SUBSTRATES)) {
    const hc = slopeOf(`${seg}|hicasso`);
    if (typeof hc !== 'number') continue;
    const line = (name, donor) =>
      typeof donor === 'number'
        ? `${name} ${(hc / donor).toFixed(4)}x (${n0(hc)} vs ${n0(donor)} B/read, ` +
          `margin ${(100 * (donor - hc) / donor).toFixed(1)}%)`
        : `${name} —`;
    console.log(`;;   hicasso in ${seg.padEnd(13)} ${line('vs Reagent', rg)}   ${line('vs UIx', ux)}`);
  }
  if (typeof slopeOf('reagent-subs|hicasso') === 'number' &&
      typeof slopeOf('uix-subs|hicasso') === 'number') {
    const a = slopeOf('reagent-subs|hicasso');
    const b = slopeOf('uix-subs|hicasso');
    console.log(
      `;;   the candidate's two segments: ${n0(a)} and ${n0(b)} B/read — ` +
        `${(100 * Math.abs(a - b) / ((a + b) / 2)).toFixed(2)}% apart. NOT a seam figure: the`
    );
    console.log(
      ';;   arm needs neither adapter\'s hooks, but `subs/subscribe` builds a reaction whose'
    );
    console.log(
      ';;   implementation is the INSTALLED adapter\'s, so these are one view layer over two'
    );
    console.log(
      ';;   subscription substrates. Compare each against the donor measured beside it.'
    );
  }

  console.log(';;');
  console.log(';; ==== THE STRUCTURAL WITNESS (counted, and exited on) ====');
  console.log(';;   boundaries = B (0 at R=0) · edges = B·R · cells = Q · entries = B (1 at R=0)');
  console.log(';;   on the candidate; all four ZERO on every donor arm; and every field zero');
  console.log(';;   again after teardown — HD-002 clause (d) in objects rather than in bytes.');
  console.log(';;   The R=0 zero is the fused design\'s own claim (rf2-dabt3): with the');
  console.log(';;   sub-index living on the cell table, an edgeless boundary retains no');
  console.log(';;   membership, so a NON-zero reading there is a retention bug.');
  if (structuralFailures.length === 0) {
    console.log(';;   VERDICT: every arm of every round answered its expected counts.');
  } else {
    for (const f of structuralFailures.slice(0, 40)) console.log(`;;   FAILED ${f}`);
    if (structuralFailures.length > 40) {
      console.log(`;;   … and ${structuralFailures.length - 40} more`);
    }
  }

  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (ladder) ====');
  console.log(
    `;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`
  );
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
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
  console.log(';;   (a mount is verified on TWO read-backs: the boundary elements it produced,');
  console.log(";;    and the unique query keys the frame's sub-cache is holding — B and Q)");
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);
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

const n0 = (x) => (typeof x === 'number' && isFinite(x) ? String(Math.round(x)) : '—');

function summariseFanout(row) {
  const B = row.plan[0].arms[0].boundaries;
  console.log('\n;; ==== P0 RETAINED HEAP — THE FAN-OUT SWEEP (rf2-5prok) ====');
  console.log(
    `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
      `held FIXED across every rung`
  );
  console.log(`;; ${row.rounds} rounds. Q is COUNTED off the frame's own sub-cache on every mount,`);
  console.log(';; not asserted by the plan — an unstamped or mis-stamped rung is an unverified mount.');
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
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  for (const segment of Object.keys(FAN_SUBSTRATE)) {
    const sub = FAN_SUBSTRATE[segment];
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    console.log(
      ';; rung    reads    B      E      Q     E/B    E/Q    exclusive B/boundary [min–max]   residue'
    );
    const floorKey = `${segment}|grid/floor`;
    const line = (label, key, reads, keys) => {
      const excl = stat(
        row.perRound.map((r) => r.arms[key].bytesPerBoundaryCdp - r.arms[floorKey].bytesPerBoundaryCdp)
      );
      const res = stat(row.perRound.map((r) => r.arms[key].residueCdp));
      const E = B * reads;
      console.log(
        `;; ${label.padEnd(8)}${String(reads).padStart(3)}  ${String(B).padStart(6)} ` +
          `${String(E).padStart(6)} ${String(keys).padStart(6)} ` +
          `${reads.toFixed(2).padStart(5)}  ${(keys ? (E / keys).toFixed(2) : '—').padStart(5)}   ` +
          `${n0(excl.mean).padStart(8)} [${n0(excl.min)}–${n0(excl.max)}]`.padEnd(24) +
          `${n0(res.mean).padStart(9)}`
      );
    };
    const floorStat = stat(row.perRound.map((r) => r.arms[floorKey].bytesPerBoundaryCdp));
    console.log(
      `;; floor     0  ${String(B).padStart(6)} ${String(0).padStart(6)} ${String(0).padStart(6)} ` +
        ` 0.00      —   ${n0(floorStat.mean).padStart(8)} [${n0(floorStat.min)}–${n0(floorStat.max)}]` +
        '   (absolute, the calibrator)'
    );
    for (const g of fanRungs(B)) line(g.rung, `${segment}|fan/${sub}#${g.rung}`, g.reads, g.keys);
    line('anchor', `${segment}|grid/${sub}`, 1, row.perRoot.grid);
    console.log(
      `;;   'anchor' is the PUBLISHED rf2-2rtt6.4 ${sub} grid arm, unchanged — same B/E/Q as ` +
        `R1Q${Math.round(B / row.perRoot.grid)} at these roots, through :p0/cell instead of :p0/fan.`
    );
  }

  // --- the additive model -------------------------------------------------
  console.log(';;');
  console.log(';; ==== THE ADDITIVE MODEL ====');
  console.log(';;   M3   y = shell + (E/B)·edge + (Q/B)·key            (the ruling\'s shape)');
  console.log(';;   M4   y = shell + [E>0]·step + (E/B)·edge + (Q/B)·key');
  console.log(';;   Each term from one contrast: shell is the R=0 rung; key is the R=1 slope in');
  console.log(';;   Q/B; edge is R2QB2 − R1Q2 (same Q, one more read); step is what is left of');
  console.log(';;   the R=1 intercept. R2Q2B is HELD OUT of all of that and predicted by both.');
  console.log(";;   The rule is p0-heap/additive-fit's — this driver states the rungs and reads");
  console.log(';;   the answer. It is a verdict about what may be PRICED, not an instrument gate.');
  const fits = row.fanFits;
  for (const segment of Object.keys(FAN_SUBSTRATE)) {
    const m = fits.mean[segment];
    const per = fits.perRound[segment];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${segment}:  shell ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   step ${n0(m.step)} B ${rng((f) => f.step)}` +
        `   edge ${n0(m.edgeContrast)} B ${rng((f) => f.edgeContrast)}` +
        `   key ${n0(m.key)} B ${rng((f) => f.key)}`
    );
    console.log(
      `;;     r² ${m.r2.toFixed(5)}  ·  edge from the intercept ${n0(m.edgeIntercept)} B` +
        `  ·  key from the R=2 pair ${n0(m.keyAlt)} B`
    );
    console.log(
      `;;     held out ${m.heldOut.rung} = ${n0(m.heldOut.measured)} B  ·  M3 says ${n0(m.heldOut.m3)} B ` +
        `(${(100 * m.heldOut.m3Error).toFixed(2)}%)  ·  M4 says ${n0(m.heldOut.m4)} B ` +
        `(${(100 * m.heldOut.m4Error).toFixed(2)}%)`
    );
    for (const c of m.checks) {
      console.log(`;;     [${c.ok ? 'ok  ' : 'FAIL'}] ${c.name}`);
      console.log(`;;              ${c.detail}`);
    }
    const models = per.map((f) => f.model);
    const agreed = models.filter((x) => x === m.model).length;
    console.log(
      `;;     per-round: ${agreed} of ${per.length} rounds reach the same verdict ` +
        `(${models.map((x) => x || 'refused').join(', ')})`
    );
    console.log(`;;     VERDICT: ${m.why}`);
    row.fanFits.mean[segment].roundsAgreeing = agreed;
    row.fanFits.mean[segment].roundModels = models;
  }

  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (fan-out) ====');
  console.log(
    `;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`
  );
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
}

// ---------------------------------------------------------------------------

// The structural witness is the one gate here that needs neither a release
// build nor a Chromium to adjudicate — it is a pure function of the row —
// so it is exported and pinned directly by `p0_ladder_structural.test.cjs`
// (`test:script-helpers`). `--only ladder` is opt-in and in no gate, which
// is how the R=0 expectation sat stale from rf2-dabt3 until rf2-zei9w ran
// the driver; the unit pin is what stops the next such drift being found
// by the next measurement instead of by CI.
module.exports = { ladderStructuralFailures };

if (require.main === module) (async () => {
  build();
  const server = serve();
  const { chromium } = require('playwright');
  const out = { generatedAt: new Date().toISOString(), build: BUILD, initFn: INIT_FN };
  // EVERY failed gate, not the last one. A single `failed` slot let a
  // later gate's silence overwrite an earlier gate's refusal, and a run
  // that failed two things would name one of them.
  const failures = [];
  let refused = false;
  // `--only fanout` is opt-in and runs NOTHING ELSE. It is 5x the arms of
  // the published heap row and it answers a different question, so folding
  // it into a default run would both cost every run five times over and
  // change the sample stream the heap row's arm-order guard adjudicates.
  const wantClock = ONLY === null || ONLY === 'clock';
  const wantHeap = ONLY === null || ONLY === 'heap';
  const wantFanout = ONLY === 'fanout';
  // `--only ladder` is opt-in on the same terms as `--only fanout`, and
  // for the same two reasons: it is 5x the arms of the published heap
  // row, and folding it into a default run would change the sample
  // stream that row's arm-order guard adjudicates.
  const wantLadder = ONLY === 'ladder';
  // `--only alloc` is opt-in on the same terms, and for a third reason as
  // well as the two the ladder gives: it is the only row here that keeps
  // an arm mounted across a measured window, so its sample stream is not
  // the mount/release one the arm-order guard adjudicates.
  const wantAlloc = ONLY === 'alloc';
  if (ONLY !== null && !wantClock && !wantHeap && !wantFanout && !wantLadder && !wantAlloc) {
    console.error(`[p0] unknown --only ${ONLY} (clock | heap | fanout | ladder | alloc)`);
    process.exit(1);
  }
  try {
    if (wantClock) {
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
    if (wantHeap) {
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
    if (wantFanout) {
      console.error('[p0] fan-out sweep ...');
      out.fanout = await fanoutRow(chromium);
      summariseFanout(out.fanout);
      if (out.fanout.verification.unverified > 0) {
        failures.push(
          `fanout: ${out.fanout.verification.unverified} unverified mounts — ` +
            out.fanout.verification.detail.join(' | ')
        );
      }
      if (!out.fanout.control.verdict.ok) {
        failures.push(`fanout: positive control — ${out.fanout.control.verdict.why}`);
      }
      refused = refused || out.fanout.orderRefused;
      // The additive verdict is NOT an exit code. A model that does not
      // hold is a finding about the substrate, not a fault in the
      // instrument that measured it — the rows stay quotable and only the
      // component PRICES do not. It is adjudicated, printed as a verdict
      // and carried in the raw record; what it gates is what may be
      // written into validation.md.
    }
    if (wantLadder) {
      console.error('[p0] reads ladder ...');
      out.ladder = await ladderRow(chromium);
      const structural = ladderStructuralFailures(out.ladder);
      out.ladder.structuralFailures = structural;
      summariseLadder(out.ladder, structural);
      if (out.ladder.verification.unverified > 0) {
        failures.push(
          `ladder: ${out.ladder.verification.unverified} unverified mounts — ` +
            out.ladder.verification.detail.join(' | ')
        );
      }
      if (!out.ladder.control.verdict.ok) {
        failures.push(`ladder: positive control — ${out.ladder.control.verdict.why}`);
      }
      // The structural witness IS an exit code, unlike the additive
      // verdict, and the difference is what each one decides. The
      // additive model failing is a finding about a substrate. The
      // structural counts failing means the arm on the page is not the
      // arm the row claims to have measured — "one hook plus N edges in
      // a shared index" would be a description of something else — or
      // that a released arm is still holding objects, which is HD-002
      // clause (d)'s own failure. Neither is quotable.
      if (structural.length > 0) {
        failures.push(
          `ladder: ${structural.length} structural read-back failures — ${structural[0]}`
        );
      }
      refused = refused || out.ladder.orderRefused;
    }
    if (wantAlloc) {
      console.error('[p0] steady-state allocation row ...');
      out.alloc = await allocRow(chromium);
      summariseAlloc(out.alloc);
      if (out.alloc.verification.unverified > 0) {
        failures.push(
          `alloc: ${out.alloc.verification.unverified} unverified — ` +
            out.alloc.verification.detail.join(' | ')
        );
      }
      // The controls ARE an exit code. Everything this row prints rests on
      // one claim — that transient garbage is visible to the counter at
      // all — and a run whose control read zero would be a retention
      // instrument publishing an allocation table, which is the exact
      // fault that produced a wrong table on this surface before.
      if (!out.alloc.controlVerdict.ok) {
        failures.push(
          `alloc: positive control — ${out.alloc.controlVerdict.perDouble.toFixed(2)} B/double ` +
            `direct, ${out.alloc.controlVerdict.differential.toFixed(2)} B/double differential, ` +
            'against a predicted 8'
        );
      }
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
  // THE PAGES' OWN FAILURES, JOINING THE LIST THE EXIT ALREADY READS
  // (rf2-sib23). It joins `failures` rather than taking a code of its own
  // because it is the same class as every other entry there — the run did not
  // measure what it says it measured — and because that list is already the
  // one place this driver decides on. Every raw artefact is written above, so
  // the evidence survives the refusal.
  for (const e of pageFailures()) {
    failures.push(
      `the page threw and kept going — ${e}. Every figure above was taken after an uncaught ` +
        'error, and no window.P0_ERROR is set for one: React does not rethrow an uncaught ' +
        'render error to the caller of flushSync (see sentinel.cjs).'
    );
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
