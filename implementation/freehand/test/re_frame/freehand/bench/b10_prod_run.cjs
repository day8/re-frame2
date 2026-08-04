#!/usr/bin/env node
// B10's production driver — build, serve, run, print.
//
// The rates B10 publishes should describe the artefact a consumer ships
// (`:advanced`, `goog.DEBUG false`), and shadow's `:browser-test` target
// cannot be compiled that way — `cljs-test-display`'s `goog.define`s
// collide under the Closure compiler. So the production reading rides a
// plain `:browser` module built by merging an output directory and an
// `:init-fn` into the existing `:freehand-release` build id, exactly as
// B6's `b6_prod_run.cjs` does.
//
// **Nothing in `implementation/shadow-cljs.edn` changes.** The bead that
// asked for this run assumed a new build target was needed and named the
// hot-zone file as the reason it had not been done; B6 had already shown
// the target is unnecessary. `--config-merge` supplies the two keys that
// differ, and `:freehand-release` supplies `:optimizations :advanced`
// and `goog.DEBUG false` — the only two properties this reading is
// about. The run reports the build it actually got, off `goog.DEBUG`, so
// the claim is checked rather than assumed.
//
//   node implementation/freehand/test/re_frame/freehand/bench/b10_prod_run.cjs
//
// Prints every published record as EDN on stdout and exits non-zero if
// the run did not reach its own controls.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');
const { watchPage } = require('./sentinel.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const OUT_DIR = process.env.B10_OUT_DIR || 'out/b10-prod';
const INIT_FN = process.env.B10_INIT_FN || 're-frame.freehand.bench.b10-prod-app/-main';

const PORT = Number(process.env.B10_PORT || 8128);

// A browser has no `process.env`, so provenance reaches the page only as a
// COMPILE-TIME define — the same route `:browser-test-freehand-bench` uses.
// Exporting the variables and stopping there would tell this runner and not
// the bundle, and the record would publish `:d021 :not-release-evidence` with
// a nil revision. `--config-merge` deep-merges, so `goog.DEBUG false` from
// `:freehand-release` survives alongside these; the run reports the build it
// actually got, so a merge that clobbered it would be visible rather than
// silent.
const REVISION = process.env.RF2_REVISION || '';
const HARDWARE = process.env.RF2_HARDWARE_CLASS || '';

// `B10_DEBUG=true` keeps `:advanced` and flips `goog.DEBUG` back ON — the
// ABLATION arm. The published development figures were taken from a
// different build id, a different page and a different runner, so reading
// the dev-to-production gap off them alone attributes it to `:advanced` when
// part of it could be any of those. This arm changes exactly one define and
// nothing else: same entry, same page, same driver, same optimiser. What it
// isolates is the Spec 009 instrumentation seam's own cost, which is the
// term the bead asks to publish. The run reports the `goog.DEBUG` it
// actually got, so the arm cannot silently be the wrong one.
const DEBUG = process.env.B10_DEBUG === 'true';
// A separate output directory per arm, so the two bundles cannot overwrite
// each other and a stale one cannot be served as the other.
const OUT_DIR_D = DEBUG ? `${OUT_DIR}-debug` : OUT_DIR;
const OUT = path.join(IMPL, OUT_DIR_D);

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline, and reports
// `EOF while reading` from a fragment. Keep it on one line.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR_D}" :asset-path "." ` +
  `:compiler-options {:closure-defines ` +
  `{goog.DEBUG ${DEBUG} ` +
  `re-frame.freehand.bench.provenance/build-revision "${REVISION}" ` +
  `re-frame.freehand.bench.provenance/build-hardware-class "${HARDWARE}"}} ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  console.error('[b10] building :advanced bundle ...');
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, and a shell concatenates argv, which is
  // the other way the config-merge EDN gets torn in half.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[b10] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>B10</title></head>' +
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
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; B10') || t.startsWith(';; B6')) console.log(t);
  });
  // THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23), and
  // THIS DRIVER IS THE WORST OF THE NINE — which is why the finding names it
  // by hand. `b10_two_clock.cljs` (~670/694/709) drives the run from two
  // `setInterval`s and a live `MutationObserver`. A throw inside any of those
  // is a DETACHED TASK: it escapes `-main`'s `try` and the promise chain's
  // `.catch` alike, `setInterval` keeps firing after a throwing callback, and
  // so the run completes, sets `B10_DONE`, and publishes a mangled M3 record.
  // That path needs no React at all — it is the second, independent mechanism
  // `sentinel.cjs` records, and nothing inside the app can see it.
  const watch = watchPage(page, 'b10');
  // `waitUntil: 'commit'` for rf2-dczpv's reason: the entry starts running
  // during page load, so the `load` event cannot fire until the whole run
  // yields — waiting for it would be waiting for the benchmark, against a
  // budget separate from the one below.
  //
  // This call was RIGHT and its four siblings were wrong, hand-patched here
  // and nowhere else — which is how rf2-p9fa3 got filed. It now routes
  // through the directory's shared navigation, at the same event and the same
  // 60s (`NAV_TIMEOUT_MS` is this call's number, kept), so there is one
  // navigation to get right instead of five to keep in step. It gains the one
  // thing it was still missing: a failure that says it is the navigation
  // ceiling and not the twenty-minute wait below.
  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 20-minute wait for `window.B10_DONE`',
  });
  // RACED AGAINST THE PAGE DYING (rf2-qv761), and this driver had the largest
  // budget of the nine to burn: a page that died at load cost TWENTY MINUTES
  // to report a `ReferenceError` from the first second. `race` rejects only
  // on a failure `watch` recorded, and `verdict` below already refuses on
  // exactly that array, so no run that would have passed is shortened. The
  // rejection takes this driver's existing exit 1 via `drive`'s handler.
  await watch.race('window.B10_DONE === true || window.B10_ERROR', {
    timeoutMs: 20 * 60 * 1000,
    budget: 'the 20-minute wait for `window.B10_DONE`',
  });
  const err = await page.evaluate('window.B10_ERROR || null');
  const results = await page.evaluate('window.B10_RESULTS || {}');
  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  return { err, results, pageErrors };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// ONE pure function over the run's outcome, exported so the refusal can be
// watched without an `:advanced` build and a headless Chromium (rf2-y7mw7's
// shape).
//
//   0  measured
//   1  the run failed its own gates (`B10_ERROR`, or the page threw)
//
// `B10_ERROR` and a `pageerror` are NOT the same signal here, which is the
// whole of rf2-sib23 for this file: `B10_ERROR` is what `-main`'s own catch
// records, and the interval/observer tasks that drive M3 never pass through
// it. A run could set neither `B10_ERROR` nor a failure of any kind, print a
// complete M3 record, and be a record of a page that had already thrown.
//
// The records are printed before this is consulted: a refusal is about what
// may be QUOTED, not about throwing the measurement away.
function verdict(outcome) {
  const o = outcome || {};
  const pageErrors = o.pageErrors || [];
  const lines = [];
  if (o.err) lines.push(`[b10] FAILED: ${o.err}`);
  if (pageErrors.length) {
    lines.push(
      '[b10] FAILED: the page threw and kept going — the interval and MutationObserver tasks ' +
        'that drive this run survive a throw, so the record above may be of a page that is not ' +
        'the page under test (rf2-sib23):\n  ' +
        pageErrors.join('\n  ')
    );
  }
  return { code: o.err || pageErrors.length ? 1 : 0, lines };
}

async function drive() {
  build();
  const server = serve();
  let outcome;
  try {
    outcome = await run();
  } finally {
    server.close();
  }
  for (const [k, v] of Object.entries(outcome.results)) {
    console.log(`;; ==== B10 PROD ${k} ====`);
    console.log(v);
  }
  const v = verdict(outcome);
  for (const line of v.lines) console.error(line);
  if (v.code === 0) console.error('[b10] ok');
  return v.code;
}

module.exports = { verdict };

// Requiring this file must NOT drive it — `pageerror_exit_path.test.cjs` loads
// `verdict` out of it.
if (require.main === module) {
  drive().then(
    (code) => {
      if (code !== 0) process.exit(code);
    },
    (e) => {
      console.error(`[b10] FAILED: ${e && e.stack ? e.stack : e}`);
      process.exit(1);
    }
  );
}
