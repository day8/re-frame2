#!/usr/bin/env node
'use strict';
// THE PAGE-CHROME ROW, AND WHAT THE BAIL-OUT COSTS — driver
// (rf2-2rtt6.52's landing bar).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/chrome_run.cjs
//   CHROME_ROUNDS=8 node .../chrome_run.cjs
//   CHROME_ONLY=chrome,bulk node .../chrome_run.cjs
//
// HD-028 makes a value-equality bail-out the boundary DEFAULT, and the
// ruling made that conditional on this measurement: the default lands if
// it removes the 300-row cascade *without a material mount/bulk
// regression and without pushing retained heap meaningfully farther past
// the bar*. The comparator takes React's full MemoComponent path and adds
// an outer Fiber per boundary; the R=0 shell already reads ~1.14 KB
// against a 1 KB paper-fail line. If the Fiber fails, HD-006 stands and
// the same comparator ships opt-in.
//
// ## What this is, and what it is NOT
//
// It is a **self-comparison** of one substrate against itself with one
// line changed — `:memo` (mint-view! + the codec's stable wrapper) versus
// `:plain` (marked only, which is mint-view! exactly as it stood before
// the repair). Both arms render the same page, the same card markup and
// the same bodies over the same model.
//
// It is NOT the comparative bulk ladder against Reagent. That row is
// `clock_run.cjs`'s and is adjudicated by its own guards, its own
// three-point control and its own arm-order refusal. Nothing here is
// stated as a Reagent ratio, and no `HD-` number is minted from it.
//
// ## Why an in-page window is legitimate here and is avoided anyway
//
// `clock_run.cjs` refuses in-page spans because they close when the
// JavaScript returns — before style, layout and paint — and how much work
// a substrate leaves for the browser is exactly what differs BETWEEN
// substrates. That objection does not reach a self-comparison: both arms
// are the same substrate. It is avoided regardless — every window in
// `chrome_app` closes after a rAF+setTimeout, so it spans the frame that
// followed the mutation.
//
// ## The heap readings belong to the driver
//
// A page cannot force a collection, so it cannot decide when a RETAINED
// figure is taken. Every heap reading here is: collect three times, read;
// mount and hold; collect, read; release; collect, read. Per-boundary
// exclusive retained is (held - baseline) / boundaries, and the
// after-release reading is the leak check that makes the held one
// meaningful (`reads_ladder_run.cjs`'s discipline, and its reason: V8's
// sampling profiler drops collected objects, so only a HELD page can be
// priced).
//
// ## Exit codes
//
//   0  measured; every invariant held
//   1  the run failed (build, page error, a page-recorded fatal)
//   4  A CLAIM THIS TABLE IS PUBLISHED ON DID NOT HOLD — the body counts
//      the arms are supposed to agree on did not agree, or the arm under
//      test did not stop the cascade, or a mount left residue. The
//      records are on stdout before the refusal so the evidence survives
//      it; the deltas are void and must not be quoted.

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.CHROME_OUT_DIR || 'out/hicasso-chrome';
const INIT_FN = 're-frame.bench.hicasso.chrome-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.CHROME_PORT || 8147);
const ROUNDS = Number(process.env.CHROME_ROUNDS || 10);
const WARMUP = Number(process.env.CHROME_WARMUP || 3);
const HEAP_ROUNDS = Number(process.env.CHROME_HEAP_ROUNDS || 5);
const NO_BUILD = process.argv.includes('--no-build');

const ALL_OPS = [
  { id: 'chrome', why: 'the page-chrome write — the win; :memo must re-run 0 cards, :plain 300' },
  { id: 'bulk', why: 'every card subscription moves — external-store invalidation still fires' },
  { id: 'narrow', why: 'one card subscription moves — the other 299 stay asleep' },
  { id: 'props', why: 'every card PROPS move — a bail-out that never re-renders is worse' },
];
const ONLY = (process.env.CHROME_ONLY || '').trim();
const OPS = ONLY ? ALL_OPS.filter((o) => ONLY.split(',').includes(o.id)) : ALL_OPS;

const ARMS = ['plain', 'memo'];
const SENTINEL_TIMEOUT_MS = 10 * 60 * 1000;

const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[chrome] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N programs`);
  }
  console.error(`[chrome] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'chrome',
  });
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso page-chrome row</title></head>' +
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

// --- statistics, deliberately plain -----------------------------------------

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const n = s.length;
  if (n === 0) return NaN;
  return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
};
const r3 = (x) => Math.round(x * 1000) / 1000;
const r4 = (x) => Math.round(x * 10000) / 10000;

// --- heap, driver-owned -----------------------------------------------------

// Three passes with a beat between them, `reads_ladder_run.cjs`'s
// discipline and its reason: React roots die in stages — fibers, then the
// host instances they point at — and a single pass leaves the second
// stage standing.
async function collectAndRead(cdp) {
  for (let i = 0; i < 3; i += 1) {
    await cdp.send('HeapProfiler.collectGarbage');
    await new Promise((r) => setTimeout(r, 80));
  }
  const { usedSize } = await cdp.send('Runtime.getHeapUsage');
  return usedSize;
}

async function heapOnce(page, cdp, arm) {
  await page.evaluate(() => window.HCHROME.resetRuntime());
  await new Promise((r) => setTimeout(r, 50));
  const baseline = await collectAndRead(cdp);
  const mounted = await page.evaluate((a) => window.HCHROME.mountArm(a), arm);
  const held = await collectAndRead(cdp);
  await page.evaluate((a) => window.HCHROME.releaseArm(a), arm);
  await page.evaluate(() => window.HCHROME.resetRuntime());
  await new Promise((r) => setTimeout(r, 50));
  const after = await collectAndRead(cdp);
  return {
    boundaries: mounted.boundaries,
    baseline,
    held,
    exclusive: held - baseline,
    perBoundary: (held - baseline) / mounted.boundaries,
    residual: after - baseline,
  };
}

// A first mount warms one-time structures — sub caches, codec caches,
// React internals — that are not per-boundary and never come back. Read
// on a cold page they land entirely in the first arm measured, which is
// how a wrapper came out CHEAPER than no wrapper on the first run of
// this file. So: a discarded warm-up per arm, then rounds, alternating
// which arm goes first, and the median across them.
async function heapForArms(page, cdp, arms, rounds) {
  for (const arm of arms) await heapOnce(page, cdp, arm); // warm-up, discarded
  const acc = Object.fromEntries(arms.map((a) => [a, []]));
  for (let r = 0; r < rounds; r += 1) {
    const order = r % 2 === 0 ? arms : [...arms].reverse();
    for (const arm of order) acc[arm].push(await heapOnce(page, cdp, arm));
  }
  return acc;
}

// --- the run ----------------------------------------------------------------

async function main() {
  if (!NO_BUILD) build();
  const server = serve();
  const { chromium } = require('playwright');
  const browser = await chromium.launch({ args: ['--enable-precise-memory-info'] });
  const version = browser.version();

  const page = await browser.newPage();
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[chrome]')) console.log(t);
  });
  const watch = watchPage(page, 'chrome');
  const problems = [];
  const timing = {}; // op -> arm -> [ms]
  const counts = {}; // op -> arm -> {cards, pages}
  let mountMs = {}; // arm -> [ms]
  let heap = {};
  let label = null;
  let CARDS_N = null;

  try {
    await navigate(page, `http://127.0.0.1:${PORT}/`, {
      waitUntil: 'commit',
      timeoutMs: NAV_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HCHROME_READY`,
    });
    await watch.race('window.HCHROME_READY === true || window.HICASSO_ERROR', {
      timeoutMs: SENTINEL_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HCHROME_READY`,
    });
    const err = await page.evaluate('window.HICASSO_ERROR || null');
    if (err) throw new Error(`the page recorded a fatal before it was ready: ${err}`);
    label = await page.evaluate(() => window.HCHROME.runtimeLabel());
    CARDS_N = await page.evaluate(() => window.HCHROME.cardsN());

    const cdp = await page.context().newCDPSession(page);
    await cdp.send('HeapProfiler.enable');
    await cdp.send('Runtime.enable');

    // ---- the heap half, first, on a quiet page ----------------------------
    heap = await heapForArms(page, cdp, ARMS, HEAP_ROUNDS);

    // ---- the clock half ---------------------------------------------------
    for (const op of OPS) {
      timing[op.id] = { plain: [], memo: [] };
      counts[op.id] = {};
    }
    for (const arm of ARMS) mountMs[arm] = [];

    for (let round = 0; round < ROUNDS; round += 1) {
      // Arms alternate their order round to round, so a figure cannot be a
      // function of where in the plan it sat.
      const order = round % 2 === 0 ? ARMS : [...ARMS].reverse();
      for (const arm of order) {
        await page.evaluate(() => window.HCHROME.resetRuntime());
        const mounted = await page.evaluate((a) => window.HCHROME.mountArm(a), arm);
        if (round >= WARMUP) mountMs[arm].push(mounted.ms);
        if (mounted.cards !== CARDS_N) {
          problems.push(`${arm}: mounted ${mounted.cards} cards, expected ${CARDS_N}`);
        }
        for (const op of OPS) {
          const res = await page.evaluate(
            ([a, o]) => window.HCHROME.runOp(a, o),
            [arm, op.id]
          );
          if (round >= WARMUP) timing[op.id][arm].push(res.ms);
          const prev = counts[op.id][arm];
          const now = { cards: res.cards, pages: res.pages };
          if (prev && (prev.cards !== now.cards || prev.pages !== now.pages)) {
            problems.push(
              `${arm}/${op.id}: body counts moved between rounds — ${JSON.stringify(prev)} then ${JSON.stringify(now)}`
            );
          }
          counts[op.id][arm] = now;
        }
        await page.evaluate((a) => window.HCHROME.releaseArm(a), arm);
        const residue = await page.evaluate(() => window.HCHROME.residue());
        if (residue.boundaries !== 0 || residue.edges !== 0) {
          problems.push(`${arm}: residue after release — ${JSON.stringify(residue)}`);
        }
      }
    }
  } catch (e) {
    // An `:advanced` build renames everything, so a bare `e.message` here
    // is a minified symbol and names nothing. Ask the page what IT saw
    // before tearing the browser down — that is where a readable cause is.
    let pageSaid = null;
    try {
      pageSaid = await page.evaluate('window.HICASSO_ERROR || null');
    } catch (_) {
      /* the page may already be gone */
    }
    console.error(`[chrome] the run died: ${e.message}`);
    if (pageSaid) console.error(`[chrome] the page recorded: ${pageSaid}`);
    for (const f of watch.failures) console.error(`[chrome] page ${f.kind}: ${f.detail}`);
    watch.dispose();
    await browser.close();
    server.close();
    process.exit(1);
  }

  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  server.close();

  if (pageErrors.length) {
    console.error(`[chrome] the page threw — a benchmark that threw and kept going publishes a`);
    console.error(`[chrome] precise number for a page that is not the page under test:`);
    for (const p of pageErrors) console.error(`[chrome]   ${p}`);
    process.exit(1);
  }

  // ---- the claims this table is published on -------------------------------
  const claims = [];
  const claimsHeap = [];
  const chromeCounts = counts.chrome;
  if (chromeCounts) {
    if (!(chromeCounts.memo && chromeCounts.memo.cards === 0)) {
      claims.push(`:memo did not stop the cascade — ${JSON.stringify(chromeCounts.memo)}`);
    }
    if (!(chromeCounts.memo && chromeCounts.memo.pages === 1)) {
      claims.push(`:memo did not re-run the page exactly once — ${JSON.stringify(chromeCounts.memo)}`);
    }
    if (!(chromeCounts.plain && chromeCounts.plain.cards === CARDS_N)) {
      claims.push(
        `:plain did not reproduce the cascade — ${JSON.stringify(chromeCounts.plain)}; ` +
          `without it there is no before to compare an after against`
      );
    }
  }
  for (const op of OPS) {
    if (op.id === 'chrome') continue;
    const c = counts[op.id];
    if (c.plain && c.memo && (c.plain.cards !== c.memo.cards || c.plain.pages !== c.memo.pages)) {
      claims.push(
        `${op.id}: the arms must agree and did not — plain ${JSON.stringify(c.plain)} vs memo ${JSON.stringify(c.memo)}`
      );
    }
  }

  // ---- report --------------------------------------------------------------
  console.log('');
  console.log(`;; chrome    the page-chrome row, and what the bail-out costs (rf2-2rtt6.52)`);
  console.log(`;; runtime   ${version} · ${label ? label.optimizations : '?'} · goog.DEBUG=${label ? label['goog-debug'] : '?'}`);
  console.log(`;; cores     ${label ? label['hardware-concurrency'] : '?'}`);
  console.log(`;; rounds    ${ROUNDS} (${WARMUP} warm-up, ${ROUNDS - WARMUP} measured), arms alternate order`);
  console.log(`;; window    frame-inclusive (rAF + setTimeout after the flush)`);
  console.log('');

  console.log('### Body counts — what each write actually re-ran');
  console.log('');
  console.log('| op | arm | page bodies | card bodies |');
  console.log('|---|---|---:|---:|');
  for (const op of OPS) {
    for (const arm of ARMS) {
      const c = counts[op.id][arm] || {};
      console.log(`| ${op.id} | ${arm} | ${c.pages} | ${c.cards} |`);
    }
  }
  console.log('');

  console.log('### The clock, frame-inclusive (ms, median [min–max])');
  console.log('');
  console.log('| row | plain | memo | memo ÷ plain |');
  console.log('|---|---:|---:|---:|');
  const fmt = (xs) => `${r3(median(xs))} [${r3(Math.min(...xs))}–${r3(Math.max(...xs))}]`;
  console.log(
    `| mount (301 boundaries) | ${fmt(mountMs.plain)} | ${fmt(mountMs.memo)} | ${r4(median(mountMs.memo) / median(mountMs.plain))}× |`
  );
  for (const op of OPS) {
    const p = timing[op.id].plain;
    const m = timing[op.id].memo;
    console.log(`| ${op.id} | ${fmt(p)} | ${fmt(m)} | ${r4(median(m) / median(p))}× |`);
  }
  console.log('');

  console.log('### Per-boundary retained heap (bytes, one mount held, median of rounds)');
  console.log('');
  console.log('| arm | boundaries | exclusive retained | per boundary | residual after release |');
  console.log('|---|---:|---:|---:|---:|');
  const heapStat = {};
  for (const arm of ARMS) {
    const rows = heap[arm] || [];
    if (!rows.length) continue;
    const per = median(rows.map((h) => h.perBoundary));
    const exc = median(rows.map((h) => h.exclusive));
    const res = median(rows.map((h) => h.residual));
    heapStat[arm] = { per, exc, res, n: rows.length, boundaries: rows[0].boundaries };
    console.log(
      `| ${arm} | ${rows[0].boundaries} | ${Math.round(exc)} B | **${Math.round(per)} B** ` +
        `[${Math.round(Math.min(...rows.map((h) => h.perBoundary)))}–${Math.round(Math.max(...rows.map((h) => h.perBoundary)))}] ` +
        `| ${Math.round(res)} B |`
    );
  }
  const hp = heapStat.plain;
  const hm = heapStat.memo;
  if (hp && hm) {
    console.log('');
    console.log(
      `;; per-boundary heap delta  ${Math.round(hm.per - hp.per)} B  ` +
        `(${r4(hm.per / hp.per)}×) — the outer Fiber, priced`
    );
    // A residual that is a large fraction of what was held means the page
    // did not come back, so the NEXT baseline is not the last one and the
    // difference is not exclusive retention. Refuse the table rather than
    // publish a number differenced against a drifting floor.
    for (const arm of ARMS) {
      const h = heapStat[arm];
      if (!h) continue;
      const frac = Math.abs(h.res) / Math.abs(h.exc);
      if (frac > 0.25) {
        claimsHeap.push(
          `${arm}: residual after release is ${Math.round(h.res)} B against ${Math.round(h.exc)} B held ` +
            `(${r4(frac)}) — the page did not return to its baseline, so the per-boundary figure is ` +
            `differenced against a drifting floor and is not exclusive retention`
        );
      }
    }
  }
  console.log('');

  if (problems.length) {
    console.error('[chrome] INVARIANTS BROKE:');
    for (const p of problems) console.error(`[chrome]   ${p}`);
  }
  if (claims.length) {
    console.error('[chrome] A CLAIM THIS TABLE IS PUBLISHED ON DID NOT HOLD:');
    for (const c of claims) console.error(`[chrome]   ${c}`);
  }
  if (claimsHeap.length) {
    console.error('[chrome] THE HEAP TABLE IS NOT TRUSTWORTHY:');
    for (const c of claimsHeap) console.error(`[chrome]   ${c}`);
  }
  if (problems.length || claims.length || claimsHeap.length) process.exit(4);
  console.error('[chrome] ok — every invariant and every claim held');
}

main().catch((e) => {
  console.error(`[chrome] ${e.stack || e.message}`);
  process.exit(1);
});
