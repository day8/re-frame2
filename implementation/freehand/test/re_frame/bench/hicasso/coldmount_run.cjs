#!/usr/bin/env node
// THE COLD-MOUNT DOUBLE BUILD, PRICED AGAINST THE MOUNT RED-ZONE — driver
// (rf2-2rtt6.15; decision input for the rf2-2rtt6.14 ruling).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/coldmount_run.cjs
//   COLDMOUNT_ONLY=M1L1,M1L2 node .../coldmount_run.cjs      # a subset of rows
//
// Build once, serve once, load the page ONE ROW AT A TIME, and refuse a
// figure that moves with its position in the plan. The method is the
// converged arm's (`p0_converge_run.cjs`), on its witnesses, on its lane;
// what is new is the pair of transcription arms whose difference is the
// cold-mount double build, and the fraction their delta makes of the
// UIx-minus-Reagent mount excess measured in the SAME page loads.
//
// ## No new build id
//
// `implementation/shadow-cljs.edn` is not touched. This rides
// `:hicasso-bench` with an output directory and an `:init-fn` merged in at
// the CLI — HD-017's seam, the same one every driver in this directory
// uses. The lane's one cache rule runs first (`lane_cache.cjs`,
// rf2-2rtt6.20): one build id means one build cache, so it is cleared
// before the build and the bundle starts cold.
//
// ## Exit codes
//
//   0  measured; guard clean; controls, witness counts and fidelity all held
//   1  the run failed (build, page error, a fatal the page recorded, a
//      positive control that did not see what it predicted, a
//      SEGMENT-ORDER control whose two strata point opposite ways)
//   2  THE ARM-ORDER GUARD REFUSED. A figure whose value depends on where
//      in the plan it was measured is not a figure. Repair the ARM —
//      more warm-up, fewer arms per page, a longer window — never the
//      guard's tolerance.
//   4  A CLAIM THIS TABLE IS PUBLISHED ON DID NOT HOLD — the counting
//      witness falsified a construction-count prediction, or the
//      transcription failed the clock fidelity control against the
//      shipped hook. The records are on stdout before the refusal, so
//      the evidence survives it; the deltas are void and must not be
//      quoted (the rf2-2rtt6.12 audit's fail-closed discipline).
//
// A Chromium `pageerror` is FATAL: a benchmark that threw and kept going
// publishes a precise number for a page that is not the page under test.
//
// ## WHICH SCHEDULE THESE ROWS SPEAK FOR (rf2-2rtt6.25, audit of #7326)
//
// Every mount this driver provokes runs inside `react-dom/flushSync`, which
// forces React's passive `useSyncExternalStore` subscribe before control
// returns — the ordering the rf2-2rtt6.25 provisional hand-off needs to win
// its race with its own reaper. The public client mount path
// (`re-frame.substrate.adapter/render`, a bare `createRoot().render()`) does
// NOT force it, and there the reaper fires first and the cold mount rebuilds.
//
// So the `shipped` arm below is the forced-synchronous MECHANISM arm: it says
// what the hand-off costs and saves when it runs to completion, and it is NOT
// an acceptance witness for shipped mount performance. Every record this run
// emits carries that as a `:schedule` key. Nothing is unsound — the lost race
// costs a construction, never correctness (Spec 006 §Render-phase provisional
// acquisition and commit adoption). The reap horizon is an operator decision.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

// The directory's ONE navigation, with its ceiling named (rf2-p9fa3).
const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// The directory's ONE sentinel wait, raced against the page dying (rf2-f5roa).
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.COLDMOUNT_OUT_DIR || 'out/hicasso-coldmount';
const INIT_FN = 're-frame.bench.hicasso.coldmount-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.COLDMOUNT_PORT || 8143);

// One row, one page, one fresh heap. M1 is the primary witness; M2 is
// DIAGNOSTIC per rf2-2rtt6.2's own grading and stays diagnostic here. The
// layer ladder is the split that attributes the double-build clock between
// the reaction allocation (layer 1, no `:<-` chain) and the per-hop input
// rebuild + re-deref (the L2−L1 / L3−L2 increments).
const ALL_ROWS = [
  { id: 'M1L1', why: 'mount, 901 el / 300 boundaries, layer-1 subs — PRIMARY' },
  { id: 'M1L2', why: 'the M1 mount through one :<- hop — PRIMARY (layer-2/3 coverage)' },
  { id: 'M1L3', why: 'the M1 mount through two :<- hops — PRIMARY (layer-2/3 coverage)' },
  { id: 'M2L1', why: 'mount, 51 el / 12 fields, layer-1 — DIAGNOSTIC, clamp-quantised' },
  { id: 'M2L2', why: 'the M2 form through one :<- hop — DIAGNOSTIC' },
];

// `COLDMOUNT_ONLY=M1L2` re-takes a subset over the SAME bundle and the same
// gates — `p0_converge_run.cjs`'s `HICASSO_ONLY`, for that driver's reasons.
const ONLY = (process.env.COLDMOUNT_ONLY || '').trim();
const ROWS = ONLY ? ALL_ROWS.filter((r) => ONLY.split(',').includes(r.id)) : ALL_ROWS;
if (ROWS.length === 0) {
  console.error(
    `[coldmount] COLDMOUNT_ONLY=${ONLY} selects no row; known ids: ${ALL_ROWS.map((r) => r.id).join(', ')}`
  );
  process.exit(1);
}

// A page's own budget. Four rounds of two segments with warm-up, plus the
// parity and counting-witness phases, is minutes on a loaded workstation.
const SENTINEL_TIMEOUT_MS = 20 * 60 * 1000;

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[coldmount] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[coldmount] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[coldmount] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso cold-mount double build</title></head>' +
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

async function runRow(browser, row) {
  // A FRESH PAGE per row: the fault this lane's drivers were rebuilt
  // around is a page that gets slower the longer it runs, and a reused
  // page carries whatever caused that across the row boundary.
  const page = await browser.newPage();
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[coldmount]')) console.log(t);
  });
  // Watching starts BEFORE the navigation (rf2-f5roa): a throw before the
  // sentinel must not cost the full budget before the driver looks.
  const watch = watchPage(page, `coldmount:${row.id}`);

  try {
    await navigate(page, `http://127.0.0.1:${PORT}/?row=${row.id}`, {
      waitUntil: 'commit',
      timeoutMs: NAV_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HICASSO_DONE`,
    });
    await watch.race('window.HICASSO_DONE === true || window.HICASSO_ERROR', {
      timeoutMs: SENTINEL_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HICASSO_DONE`,
    });

    const err = await page.evaluate('window.HICASSO_ERROR || null');
    const refused = await page.evaluate('window.HICASSO_GUARD_REFUSED === true');
    const controlFailed = await page.evaluate('window.HICASSO_CONTROL_FAILED === true');
    const orderRefused = await page.evaluate('window.HICASSO_ORDER_REFUSED === true');
    const claimFailed = await page.evaluate('window.HICASSO_CLAIM_FAILED === true');
    const results = await page.evaluate('window.HICASSO_RESULTS || {}');
    return {
      row, err, refused, controlFailed, orderRefused, claimFailed, results,
      pageErrors: watch.failures.map((f) => `${f.kind}: ${f.detail}`),
    };
  } finally {
    watch.dispose();
    await page.close();
  }
}

(async () => {
  build();
  const server = serve();
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const version = browser.version();
  const outcomes = [];
  let died = null;
  try {
    for (const row of ROWS) {
      console.error(`[coldmount] row ${row.id} — ${row.why}`);
      try {
        outcomes.push(await runRow(browser, row));
      } catch (e) {
        died = `${row.id}: ${e.message}`;
        break;
      }
    }
  } finally {
    await browser.close();
    server.close();
  }

  if (died) {
    console.error(`[coldmount] FAILED: ${died}`);
    process.exit(1);
  }

  console.log(`;; ==== HICASSO RUNTIME ====`);
  console.log(`;; chromium ${version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; one row per page; ${ROWS.length} pages; 4 rounds (balanced 2:2 segment order)`);
  console.log(
    `;; reproduce  ${ONLY ? `COLDMOUNT_ONLY=${ONLY} ` : ''}node ` +
      `implementation/freehand/test/re_frame/bench/hicasso/coldmount_run.cjs`
  );
  console.log(`;; rows       ${ROWS.map((r) => r.id).join(', ')}${ONLY ? `  (COLDMOUNT_ONLY=${ONLY})` : ''}`);
  for (const o of outcomes) {
    for (const [k, v] of Object.entries(o.results)) {
      console.log(`;; ==== HICASSO ${o.row.id} / ${k} ====`);
      console.log(v);
    }
  }

  const errored = outcomes.filter((o) => o.pageErrors.length > 0);
  if (errored.length > 0) {
    console.error(
      `[coldmount] FAILED: uncaught page error(s) — every figure above was taken on a ` +
        `page that had already thrown:\n  ` +
        errored.map((o) => `${o.row.id}: ${o.pageErrors.join(' | ')}`).join('\n  ')
    );
    process.exit(1);
  }
  const refused = outcomes.filter((o) => o.refused);
  if (refused.length > 0) {
    console.error(
      `[coldmount] ARM-ORDER GUARD REFUSED (exit 2) on: ${refused
        .map((o) => o.row.id)
        .join(', ')}. At least one arm reads differently for WHERE IN THE PLAN it was ` +
        `measured, so no figure in that row is reportable. Repair the ARM; the guard ` +
        `tolerance is not yours to move.`
    );
    process.exit(2);
  }
  const ctlFailed = outcomes.filter((o) => o.controlFailed);
  if (ctlFailed.length > 0) {
    console.error(
      `[coldmount] FAILED: the positive control did not see the change its own ` +
        `arithmetic predicts on: ${ctlFailed.map((o) => o.row.id).join(', ')}. An ` +
        `instrument that cannot see a predicted change cannot be trusted with an ` +
        `unpredicted one.`
    );
    process.exit(1);
  }
  const orderRefused = outcomes.filter((o) => o.orderRefused);
  if (orderRefused.length > 0) {
    console.error(
      `[coldmount] FAILED: the SEGMENT-ORDER control refused on: ${orderRefused
        .map((o) => o.row.id)
        .join(', ')}. The row's two order strata point OPPOSITE WAYS across 1.0, so the ` +
        `row has no direction to publish and the figure is a measurement of the schedule.`
    );
    process.exit(1);
  }
  const failed = outcomes.filter((o) => o.err);
  if (failed.length > 0) {
    console.error(
      `[coldmount] FAILED:\n  ` + failed.map((o) => `${o.row.id}: ${o.err}`).join('\n  ')
    );
    process.exit(1);
  }
  // LAST among the gates, so every more specific failure names itself
  // first. Exit 4 is the fail-closed refusal the rf2-2rtt6.12 audit
  // established: the table was written, and it may not be published.
  const claimFailed = outcomes.filter((o) => o.claimFailed);
  if (claimFailed.length > 0) {
    console.error(
      `[coldmount] THIS TABLE MAY NOT BE PUBLISHED — a claim it rests on did not hold ` +
        `on: ${claimFailed.map((o) => o.row.id).join(', ')}. Either the counting witness ` +
        `falsified a construction-count prediction (see the witness-counts record) or ` +
        `the transcription failed the clock fidelity control against the shipped hook ` +
        `(see the fidelity record). Every delta in those rows is void.`
    );
    process.exit(4);
  }
  console.error('[coldmount] ok');
})();
