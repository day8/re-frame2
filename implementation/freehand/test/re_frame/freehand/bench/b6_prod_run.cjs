#!/usr/bin/env node
// B6's production driver — build, serve, run, print.
//
// The numbers B6 publishes have to come from the artefact a consumer
// ships (`:advanced`, `goog.DEBUG false`), and shadow's `:browser-test`
// target cannot be compiled that way — `cljs-test-display`'s
// `goog.define`s collide under the Closure compiler. So the production
// reading rides a plain `:browser` module built by merging an output
// directory and an `:init-fn` into the existing `:freehand-release`
// build id. Nothing in `implementation/shadow-cljs.edn` changes.
//
//   node implementation/freehand/test/re_frame/freehand/bench/b6_prod_run.cjs
//
// Prints every published record as EDN on stdout and exits non-zero if
// the run did not reach its own parity gate.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');
const { watchPage } = require('./sentinel.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
// `B6_INIT_FN` / `B6_OUT_DIR` point this driver at a different entry —
// the seam an ABLATION LADDER rides so that it reuses this window rather
// than growing a second one. With both unset the file is byte-for-byte
// the published instrument.
const OUT_DIR = process.env.B6_OUT_DIR || 'out/b6-prod';
const INIT_FN = process.env.B6_INIT_FN || 're-frame.freehand.bench.b6-prod-app/-main';

const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.B6_PORT || 8127);
const QUERY = process.env.B6_QUERY || '';

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline, and reports
// `EOF while reading` from a fragment. Keep it on one line.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  console.error('[b6] building :advanced bundle ...');
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
    console.error(`[b6] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>B6</title></head>' +
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
    if (t.startsWith(';; B6')) console.log(t);
  });
  // THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23). This
  // was a bare `page.on('pageerror', ...)` that logged and recorded nothing,
  // so an uncaught throw was announced on stderr and the run exited 0 beneath
  // it. `sentinel.cjs`'s header carries the whole finding — including why no
  // page-side `try`/`catch` can close it under React 19.2 — and this is the
  // remedy its other callers already have: collect, then refuse. The sentinel
  // wait below is deliberately NOT converted to `watch.race` here; that is
  // rf2-f5roa's separate "report it promptly" remedy, and the fault this file
  // has is the one where the page throws AND STILL reaches `B6_DONE`, which
  // the wait returns from normally.
  const watch = watchPage(page, 'b6');
  // `'commit'`, not `'load'` (rf2-p9fa3). `b6-prod-app/-main` is this
  // bundle's `:init-fn`, so it runs inside the `<script>` — the parity pass
  // and every mount row are measured while the document is still loading,
  // and only then does the promise chain start the update rows. `load`
  // therefore cannot fire until the published run has yielded, which is
  // exactly what `B6_DONE` waits for below, against a budget thirty times
  // larger. Waiting for `load` was waiting for the benchmark against a
  // ceiling nothing here could see or move.
  await navigate(page, `http://127.0.0.1:${PORT}/${QUERY}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 15-minute wait for `window.B6_DONE`',
  });
  await page.waitForFunction('window.B6_DONE === true || window.B6_ERROR', null, {
    timeout: 15 * 60 * 1000,
  });
  const err = await page.evaluate('window.B6_ERROR || null');
  const results = await page.evaluate('window.B6_RESULTS || {}');
  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  return { err, results, pageErrors };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// ONE pure function over the run's outcome, exported so the refusal can be
// watched without a release build and a headless Chromium — the shape
// rf2-y7mw7 and rf2-x6g04 established for this fleet, and the reason this
// file's exit block is no longer an inline `if` nobody could reach.
//
// The two conditions are INDEPENDENT and both are named when both fire.
// `err` is what it always was; `pageErrors` is rf2-sib23's repair and takes
// the SAME code, because a page that threw is a page whose figures are not
// figures — `sentinel.cjs` has said so in words since rf2-f5roa, and this
// driver was exiting 0 underneath the sentence.
//
//   0  measured
//   1  the run failed its own gates (`B6_ERROR`, or the page threw)
//
// No refusal suppresses output: every published record is printed before
// this is consulted. A refusal is about what may be QUOTED.
function verdict(outcome) {
  const o = outcome || {};
  const pageErrors = o.pageErrors || [];
  const lines = [];
  if (o.err) lines.push(`[b6] FAILED: ${o.err}`);
  if (pageErrors.length) {
    lines.push(
      '[b6] FAILED: the page threw and kept going, so every record above was taken on a page ' +
        'that is not the page under test (rf2-sib23):\n  ' +
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
    console.log(`;; ==== B6 PROD ${k} ====`);
    console.log(v);
  }
  const v = verdict(outcome);
  for (const line of v.lines) console.error(line);
  if (v.code === 0) console.error('[b6] ok');
  return v.code;
}

module.exports = { verdict };

// Requiring this file must NOT drive it — `pageerror_exit_path.test.cjs` loads
// `verdict` out of it, and a driver that builds an `:advanced` bundle on
// `require` could not be tested at all.
if (require.main === module) {
  drive().then(
    (code) => {
      if (code !== 0) process.exit(code);
    },
    (e) => {
      console.error(`[b6] FAILED: ${e && e.stack ? e.stack : e}`);
      process.exit(1);
    }
  );
}
