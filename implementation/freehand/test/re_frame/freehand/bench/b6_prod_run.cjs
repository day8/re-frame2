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
  page.on('pageerror', (e) => console.error('[b6] page error:', e.message));
  await page.goto(`http://127.0.0.1:${PORT}/${QUERY}`, { waitUntil: 'load' });
  await page.waitForFunction('window.B6_DONE === true || window.B6_ERROR', null, {
    timeout: 15 * 60 * 1000,
  });
  const err = await page.evaluate('window.B6_ERROR || null');
  const results = await page.evaluate('window.B6_RESULTS || {}');
  await browser.close();
  return { err, results };
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
  for (const [k, v] of Object.entries(outcome.results)) {
    console.log(`;; ==== B6 PROD ${k} ====`);
    console.log(v);
  }
  if (outcome.err) {
    console.error(`[b6] FAILED: ${outcome.err}`);
    process.exit(1);
  }
  console.error('[b6] ok');
})();
