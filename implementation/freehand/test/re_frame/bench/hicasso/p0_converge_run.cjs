#!/usr/bin/env node
// THE CONVERGED P0 CLOCK TABLE — driver (rf2-a4x1o).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs
//
// Build once, serve once, load the page ONE ROW AT A TIME, and refuse a
// figure that moves with its position in the plan.
//
// ## No new build id
//
// `implementation/shadow-cljs.edn` is not touched. rf2-2rtt6.2 owns the
// measurement lane and HD-017 makes a build-id addition a hot-zone,
// sequenced edit; this rides `:hicasso-bench` with an output directory and
// an `:init-fn` merged in at the CLI, which is the seam that arm's own
// driver established for exactly this.
//
// ## ONE ROW PER PAGE, and why the first cut was not
//
// The first cut ran all four rows in one page and THE ARM-ORDER GUARD
// REFUSED IT, exit 2, on two independent faults. Both were the arm's and
// both are repaired here; the tolerance was not touched.
//
//   1. PHASE. `M1/uix-subs/floor` — an arm that hand-builds React
//      elements and cannot change — read LAST-THIRD 2.1739x FIRST-THIRD
//      with ranges DISJOINT (p50 1.15 ms -> 2.50 ms). Its Reagent-segment
//      twin climbed 2.09x and the two control arms climbed 1.96x-2.07x,
//      so the whole page was degrading, not one arm. That is the
//      accumulation rf2-2rtt6.4 recorded and could not explain (~12 MB per
//      segment entry, surviving a forced major collection, never reaching
//      the document); its repair was ONE ROUND PER PAGE. The repair here
//      is one ROW per page, which cuts a page's measured work from ~2,460
//      operations to ~600 and is the smaller change of the two — with
//      round-per-page held in reserve if the guard says it is not enough.
//
//   2. PREDECESSOR. `narrow/reagent-subs/ctl-2x` was refused on a stratum
//      of TWO samples labelled `M1/reagent-subs/reagent-subs` — an arm
//      that ran in a different ROW. Those tiny cross-row strata are an
//      artefact of the shared sample collector: `lane/collect!` advances
//      the `:previous` pointer only for RECORDED samples, so the first
//      recorded sample after a warm-up block is tagged with whatever ran
//      before the warm-up rather than with what actually ran before it.
//      rf2-2rtt6.4 hit the same class from the other side (`<none>`
//      strata reading 1.35x their siblings). Two repairs, both in the arm:
//      the warm-up now advances the predecessor pointer without banking a
//      sample, so every recorded sample carries its REAL predecessor; and
//      one row per page means no cross-row adjacency exists to mislabel.
//
// ## Exit codes — the guard owns 2, and it is not the arm's to move
//
//   0  measured, guard clean, controls passed
//   1  the run failed (build, page error, a fatal the page recorded, a
//      positive control that did not see what it predicted, a SEGMENT-ORDER
//      control whose two strata point opposite ways across 1.0)
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

// The directory's ONE navigation, with its ceiling named (rf2-p9fa3).
const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// The directory's ONE sentinel wait, raced against the page dying (rf2-f5roa).
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.HICASSO_OUT_DIR || 'out/hicasso-converge';
const INIT_FN = 're-frame.bench.hicasso.p0-converge-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.HICASSO_PORT || 8134);

// One row, one page, one fresh heap. The order is the order the studio
// page reports them in; each is self-contained, so it is not load-bearing.
const ALL_ROWS = [
  { id: 'M1', why: 'mount, 901 elements, 300 sub-reading boundaries — a bar row' },
  { id: 'M2', why: 'mount, 51 elements, 12 fields — DIAGNOSTIC, per rf2-2rtt6.2' },
  { id: 'broad', why: 'one commit all 300 boundaries read — a bar row' },
  { id: 'narrow', why: 'k commits each read by exactly one boundary — localisation' },
];

// `HICASSO_ONLY=narrow` drives one row instead of four, over the SAME bundle
// and the same gates. Not a shortcut, and it is `hd8_run.cjs`'s `HD8_ONLY`
// for the same reason that one has it: re-taking ONE row whose window moved
// must not re-take the three whose windows did not, because that would mint a
// competing set of figures for rows already published at another SHA and
// leave a reader unable to tell which table is current. rf2-zb3qg batched the
// narrow window and nothing else; the mount rows and the broad row are
// untouched by it — the broad row passes a batch of one, which is the
// pre-batch window exactly — so re-taking them would be replacing sound
// published numbers with different ones for no reason.
//
// Unset is all four, so the published shape is the default.
const ONLY = (process.env.HICASSO_ONLY || '').trim();
const ROWS = ONLY ? ALL_ROWS.filter((r) => ONLY.split(',').includes(r.id)) : ALL_ROWS;
if (ROWS.length === 0) {
  console.error(
    `[converge] HICASSO_ONLY=${ONLY} selects no row; known ids: ${ALL_ROWS.map((r) => r.id).join(', ')}`
  );
  process.exit(1);
}

// `HICASSO_START=reagent|uix` names the segment that leads round 0; the
// order alternates from there. Default reagent — the only schedule any run
// before rf2-6i0i2 had. A fixed start confounds segment order with temporal
// position (Reagent-first was always rounds 0/2/4), so the segment-order
// question is answered by COUNTERBALANCING the start across independently
// launched runs — separate invocations of this driver, half each way — and
// adjudicating at the run level. One invocation, whatever its start, is one
// run and is never more than one trial.
const START = (process.env.HICASSO_START || 'reagent').trim();
if (!['reagent', 'uix'].includes(START)) {
  console.error(`[converge] HICASSO_START=${START} is not a segment; known starts: reagent, uix`);
  process.exit(1);
}

// `HICASSO_RATOM=on` puts the SECOND AUTHOR'S `:reagent-ratom` arm in the
// Reagent segment of the `M1` and `broad` rows, so `reagent-subs / ratom` —
// rf2-2rtt6.2's headline 1, the price of the reactive system — can be formed
// a second way on a second page (rf2-2rtt6.21). Default OFF, and the default
// is load-bearing: with the arm in it the Reagent segment runs four arms
// against the UIx segment's three, which is a different interleave and a
// bigger mount budget than the schedule rf2-6i0i2's ten-run ensemble was
// measured under. An unflagged invocation is still that instrument; a flagged
// one is a different plan and says so on every record it writes.
//
// It composes with HICASSO_ONLY rather than implying it: `M2` and `narrow`
// carry no ratom arm in either mode, so the useful pairing is
// `HICASSO_RATOM=on HICASSO_ONLY=M1,broad`.
const RATOM = (process.env.HICASSO_RATOM || 'off').trim();
if (!['on', 'off'].includes(RATOM)) {
  console.error(`[converge] HICASSO_RATOM=${RATOM} is not on|off`);
  process.exit(1);
}

// A page's own budget. Six rounds of two segments with warm-up is
// minutes, not seconds, and a loaded workstation is the normal case.
const SENTINEL_TIMEOUT_MS = 20 * 60 * 1000;

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  // The lane's cache rule, before anything reads the cache. `lane_cache.cjs`
  // carries the measurement and the rejected alternatives.
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[converge] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[converge] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'converge',
  });
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso P0 converged</title></head>' +
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
  // A FRESH PAGE per row, not a fresh navigation in the same one: the
  // fault this driver was rebuilt around is a page that gets slower the
  // longer it runs, and a reused page carries whatever caused that across
  // the row boundary.
  const page = await browser.newPage();
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[converge]')) console.log(t);
  });
  // Watching starts BEFORE the navigation. `pageerror` used to be pushed onto
  // an array that nothing read until after the sentinel wait, so a throw
  // before HICASSO_DONE — which is every throw, since a page that threw never
  // reaches its own `done!` — cost the FULL twenty-minute budget before the
  // driver looked at the answer it had held all along (rf2-f5roa, from the PR
  // #7268 audit).
  const watch = watchPage(page, `converge:${row.id}`);

  try {
    // `'commit'`, not `'load'`. The bundle's `:init-fn` runs inside the
    // `<script>`, so the measurement happens while the document is still
    // loading and `load` cannot fire until it is over.
    await navigate(page, `http://127.0.0.1:${PORT}/?row=${row.id}&start=${START}&ratom=${RATOM}`, {
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
    const results = await page.evaluate('window.HICASSO_RESULTS || {}');
    // Kept as a BACKSTOP for an error arriving in the same tick as the
    // sentinel, which the race cannot order.
    return { row, err, refused, controlFailed, orderRefused, results, pageErrors: watch.failures.map((f) => `${f.kind}: ${f.detail}`) };
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
      console.error(`[converge] row ${row.id} — ${row.why}`);
      try {
        outcomes.push(await runRow(browser, row));
      } catch (e) {
        // The sweep STOPS. The remaining rows would each spend their own
        // budget re-discovering the same broken bundle, and nothing this row
        // recorded is a measurement.
        died = `${row.id}: ${e.message}`;
        break;
      }
    }
  } finally {
    await browser.close();
    server.close();
  }

  if (died) {
    console.error(`[converge] FAILED: ${died}`);
    process.exit(1);
  }

  console.log(`;; ==== HICASSO RUNTIME ====`);
  console.log(`;; chromium ${version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; one row per page; ${ROWS.length} pages`);
  // The repro line carries the environment it was actually run with, not the
  // bare command: a published figure whose repro command does not reproduce
  // it is a figure nobody can check.
  console.log(
    `;; reproduce  ${ONLY ? `HICASSO_ONLY=${ONLY} ` : ''}${START !== 'reagent' ? `HICASSO_START=${START} ` : ''}` +
      `${RATOM !== 'off' ? `HICASSO_RATOM=${RATOM} ` : ''}node ` +
      `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs`
  );
  console.log(`;; rows       ${ROWS.map((r) => r.id).join(', ')}${ONLY ? `  (HICASSO_ONLY=${ONLY})` : ''}`);
  console.log(`;; start      round 0 led by ${START} (HICASSO_START), alternating`);
  console.log(
    `;; ratom      ${RATOM} (HICASSO_RATOM)` +
      (RATOM === 'on'
        ? ' — the second author\'s :reagent-ratom arm is in the Reagent segment of M1 and broad;' +
          ' this run\'s cross-segment red-zone is NOT the published threshold'
        : '')
  );
  for (const o of outcomes) {
    for (const [k, v] of Object.entries(o.results)) {
      console.log(`;; ==== HICASSO ${o.row.id} / ${k} ====`);
      console.log(v);
    }
  }

  const errored = outcomes.filter((o) => o.pageErrors.length > 0);
  if (errored.length > 0) {
    console.error(
      `[converge] FAILED: uncaught page error(s) — every figure above was taken on a ` +
        `page that had already thrown:\n  ` +
        errored.map((o) => `${o.row.id}: ${o.pageErrors.join(' | ')}`).join('\n  ')
    );
    process.exit(1);
  }
  const refused = outcomes.filter((o) => o.refused);
  if (refused.length > 0) {
    console.error(
      `[converge] ARM-ORDER GUARD REFUSED (exit 2) on: ${refused
        .map((o) => o.row.id)
        .join(', ')}. At least one arm reads differently for WHERE IN THE PLAN it was ` +
        `measured, so no figure in that row is reportable. Repair the ARM — more ` +
        `warm-up, fewer arms per page, a longer measured window. The guard tolerance ` +
        `is not yours to move.`
    );
    process.exit(2);
  }
  const ctlFailed = outcomes.filter((o) => o.controlFailed);
  if (ctlFailed.length > 0) {
    console.error(
      `[converge] FAILED: the positive control did not see the change its own ` +
        `arithmetic predicts on: ${ctlFailed.map((o) => o.row.id).join(', ')}. An ` +
        `instrument that cannot see a predicted change cannot be trusted with an ` +
        `unpredicted one.`
    );
    process.exit(1);
  }
  // EXIT 1 HERE MEANS "THIS RUN MAY NOT PUBLISH ALONE", NOT "DISCARD THIS RUN".
  // The two are different questions and the row records above already answer
  // both — `:publishable` governs the run quoted by itself, `:in-an-ensemble`
  // governs whether it is one observation of many, and it says it always is.
  // rf2-b0tz5's re-take dropped a whole run on this exit code, which moved the
  // published estimate away from parity by nine tenths of a point and narrowed
  // its interval: refusing on the way the strata split IS a selection on the
  // result. So the message says so, because a bare exit 1 does not.
  const orderRefused = outcomes.filter((o) => o.orderRefused);
  if (orderRefused.length > 0) {
    console.error(
      `[converge] FAILED: the SEGMENT-ORDER control refused on: ${orderRefused
        .map((o) => o.row.id)
        .join(', ')}. The row's two order strata — rounds where Reagent's segment ran ` +
        `first, rounds where UIx's did — point OPPOSITE WAYS across 1.0, so the row has ` +
        `no direction to publish and the figure is a measurement of the schedule. The ` +
        `repair is the ARM: more rounds, a balanced (even) round count, a longer window.\n` +
        `[converge] BANK THIS LOG. Exit 1 here bars THIS RUN from publishing a figure ` +
        `ALONE; it does NOT drop the run from an ensemble. Every row record above ` +
        `carries :in-an-ensemble, and it reads ONE OBSERVATION whatever :publishable ` +
        `says — dropping a run for the way its strata happened to split selects on the ` +
        `result, and it moves the estimate it would be used to compute (rf2-6i0i2, ` +
        `rf2-b0tz5). Everything before this line passed: the arm-order guard owns exit 2 ` +
        `and the positive control prints a different exit 1, so reaching here means both ` +
        `cleared on every row.`
    );
    process.exit(1);
  }
  const failed = outcomes.filter((o) => o.err);
  if (failed.length > 0) {
    console.error(
      `[converge] FAILED:\n  ` + failed.map((o) => `${o.row.id}: ${o.err}`).join('\n  ')
    );
    process.exit(1);
  }
  console.error('[converge] ok');
})();
