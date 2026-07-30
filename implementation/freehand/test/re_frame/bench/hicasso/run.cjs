#!/usr/bin/env node
// THE HICASSO P0 LANE DRIVER — build, serve, run, print, judge.
// EP-0038 / HD-017; built by rf2-2rtt6.2 for rf2-2rtt6.2/.3/.4/.5.
//
// ONE driver for the whole P0 lane. Every arm rides it by naming its own
// entry, so a sibling arm needs no new build id, no new driver, and NO
// EDIT TO `implementation/shadow-cljs.edn` — which is the point, because
// HD-017 makes a build-id touch a hot-zone, sequenced dispatch and four
// arms would otherwise be four of them.
//
//   node implementation/freehand/test/re_frame/bench/hicasso/run.cjs
//
//   HICASSO_INIT_FN=re-frame.bench.hicasso.my-arm-app/-main \
//   HICASSO_OUT_DIR=out/hicasso-my-arm \
//   HICASSO_PORT=8131 \
//     node implementation/freehand/test/re_frame/bench/hicasso/run.cjs
//
// With every variable unset this file is byte-for-byte the published
// rf2-2rtt6.2 instrument.
//
// EXIT CODES — the arm-order guard owns 2, and it is not the arm's to
// move:
//
//   0  measured, guard clean, control passed
//   1  the run failed (build, page error, a fatal the page recorded, a
//      positive control that did not see what it predicted)
//   2  THE ARM-ORDER GUARD REFUSED. A figure whose value depends on where
//      in the plan it was measured is not a figure. The repair is the
//      ARM — more warm-up, fewer arms per round, a longer window — never
//      the guard's tolerance. Four workers have hit this on the
//      predecessor harnesses and every one of them repaired the arm.
//
// A Chromium `pageerror` is FATAL here for the same reason it is fatal in
// every adjacent runner: a benchmark that threw and kept going publishes
// a precise number for a page that is not the page under test.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

// The directory's ONE navigation, with its ceiling named (rf2-p9fa3).
// `page.goto` with no `timeout:` takes Playwright's 30s default — a second
// budget this driver's own 20-minute sentinel cannot reach, whose failure
// line reads exactly like the bench timeout it is not.
const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.HICASSO_OUT_DIR || 'out/hicasso-bench';
const INIT_FN = process.env.HICASSO_INIT_FN || 're-frame.bench.hicasso.p0-reagent-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.HICASSO_PORT || 8131);

// The page's own budget. Six rounds of four arms with warm-up is minutes,
// not seconds, and a loaded workstation is the normal case.
const SENTINEL_TIMEOUT_MS = 20 * 60 * 1000;

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and then reports `EOF while
// reading` from a fragment.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  console.error(`[hicasso] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, a shell concatenates argv, and a
  // concatenated argv is the other way the config-merge EDN gets torn in
  // half. It is also the command-hijack posture every launcher here uses.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[hicasso] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso P0</title></head>' +
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

async function run() {
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const page = await browser.newPage();

  // Tracked SEPARATELY from the console buffer, and fatal on its own. A
  // green summary that ignored an uncaught pageerror is the recorded
  // rf2-mwx08 class: the suite measured a page that had already thrown.
  const pageErrors = [];
  page.on('pageerror', (e) => {
    pageErrors.push(e.message);
    console.error('[hicasso] PAGE ERROR:', e.message);
  });
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith('[hicasso]')) console.log(t);
  });

  // `'commit'`, not `'load'`. The bundle's `:init-fn` runs inside the
  // `<script>`, so the measurement happens while the document is still
  // loading and `load` cannot fire until it is over — waiting for it is
  // waiting for the benchmark, against the one budget never meant to
  // bound it.
  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HICASSO_DONE`,
  });
  await page.waitForFunction('window.HICASSO_DONE === true || window.HICASSO_ERROR', null, {
    timeout: SENTINEL_TIMEOUT_MS,
  });

  const err = await page.evaluate('window.HICASSO_ERROR || null');
  const refused = await page.evaluate('window.HICASSO_GUARD_REFUSED === true');
  const controlFailed = await page.evaluate('window.HICASSO_CONTROL_FAILED === true');
  const results = await page.evaluate('window.HICASSO_RESULTS || {}');
  const version = browser.version();
  await browser.close();
  return { err, refused, controlFailed, results, pageErrors, version };
}

(async () => {
  build();
  const server = serve();
  let outcome;
  try {
    outcome = await run();
  } finally {
    server.close();
  }

  console.log(`;; ==== HICASSO RUNTIME ====`);
  console.log(`;; chromium ${outcome.version} (playwright), :advanced, goog.DEBUG false`);
  for (const [k, v] of Object.entries(outcome.results)) {
    console.log(`;; ==== HICASSO ${k} ====`);
    console.log(v);
  }

  if (outcome.pageErrors.length > 0) {
    console.error(
      `[hicasso] FAILED: ${outcome.pageErrors.length} uncaught page error(s) — ` +
        `every figure above was taken on a page that had already thrown:\n  ` +
        outcome.pageErrors.join('\n  ')
    );
    process.exit(1);
  }
  if (outcome.refused) {
    console.error(
      '[hicasso] ARM-ORDER GUARD REFUSED (exit 2). At least one arm reads ' +
        'differently for WHERE IN THE PLAN it was measured, so no figure above ' +
        'is reportable. Repair the ARM — more warm-up, fewer arms per round, a ' +
        'longer measured window. The guard tolerance is not yours to move.'
    );
    process.exit(2);
  }
  if (outcome.controlFailed) {
    console.error(
      '[hicasso] FAILED: the positive control did not see the change its own ' +
        'arithmetic predicts. An instrument that cannot see a predicted change ' +
        'cannot be trusted with an unpredicted one.'
    );
    process.exit(1);
  }
  if (outcome.err) {
    console.error(`[hicasso] FAILED: ${outcome.err}`);
    process.exit(1);
  }
  console.error('[hicasso] ok');
})();
