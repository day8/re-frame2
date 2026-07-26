#!/usr/bin/env node
// B6's PROFILING driver — build one `:advanced` bundle with readable
// names, run ONE update arm in a tight loop, and save a CDP CPU profile.
//
//   node implementation/freehand/test/re_frame/freehand/bench/b6_profile_run.cjs \
//        --arm freehand-interpreted --writes 400 [--control-ms 1] [--no-build]
//
// WHY A SECOND BUILD. The published numbers come from `:advanced` with
// `goog.DEBUG false`, where every symbol is renamed to `Xa`. A profile of
// that bundle names nothing. `:pseudo-names true` keeps the same
// `:advanced` optimisation pipeline — same inlining, same DCE, same
// collapsed properties — and only changes the renaming policy, so the
// profiled twin is the shipped artefact with its labels left on.
//
// WHY ONE ARM. `b6_prod_run.cjs` interleaves four arms at the sample
// level, which is right for the clock and useless for attribution: the
// samples split four ways and most of the capture is scheduler idle.
//
// Exits non-zero if any write failed its DOM read-back.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const IMPL = path.resolve(__dirname, '../../../../..');
const OUT = path.join(IMPL, 'out', 'b6-profile');
const PORT = Number(process.env.B6_PORT || 8129);

function arg(name, dflt) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? dflt : process.argv[i + 1];
}
const ARM = arg('arm', 'freehand-interpreted');
const WRITES = Number(arg('writes', 400));
const WARMUP = Number(arg('warmup', 60));
const CONTROL_MS = Number(arg('control-ms', 0));
const NO_BUILD = process.argv.includes('--no-build');
const TAG = arg('tag', ARM + (CONTROL_MS ? `-control${CONTROL_MS}` : ''));

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline. Same trap as b6_prod_run.
const CONFIG_MERGE =
  '{:output-dir "out/b6-profile" :asset-path "." ' +
  ':compiler-options {:pseudo-names true} ' +
  ':modules {:main {:init-fn re-frame.freehand.bench.b6-profile-app/-main}}}';

function build() {
  console.error('[b6p] building :advanced + :pseudo-names bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[b6p] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>B6 profile</title></head>' +
      `<body><div id="app"></div><script>window.B6_ARM=${JSON.stringify(ARM)};` +
      `window.B6_CONTROL_MS=${CONTROL_MS};</script>` +
      '<script src="main.js"></script></body></html>'
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
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; B6')) console.error(t);
  });
  page.on('pageerror', (e) => console.error('[b6p] page error:', e.message));
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'load' });
  await page.waitForFunction('window.B6_READY === true || window.B6_ERROR', null, {
    timeout: 5 * 60 * 1000,
  });
  const err = await page.evaluate('window.B6_ERROR || null');
  if (err) throw new Error(err);

  // Warm up OUTSIDE the capture: first-call compilation, IC warm-up and
  // the initial allocation ramp are not what this profile is asking about.
  console.error(`[b6p] warming ${WARMUP} writes ...`);
  const warm = await page.evaluate((n) => window.B6_RUN(n), WARMUP);
  if (warm.unverified > 0) throw new Error(`warm-up had ${warm.unverified} unverified writes`);

  const client = await page.context().newCDPSession(page);
  await client.send('Profiler.enable');
  await client.send('Profiler.setSamplingInterval', { interval: 100 });
  await client.send('Profiler.start');
  console.error(`[b6p] profiling ${WRITES} writes of arm ${ARM} ...`);
  const res = await page.evaluate((n) => window.B6_RUN(n), WRITES);
  const { profile } = await client.send('Profiler.stop');
  await browser.close();
  return { res, profile };
}

(async () => {
  if (!NO_BUILD) build();
  fs.mkdirSync(OUT, { recursive: true });
  const server = serve();
  let out;
  try {
    out = await run();
  } finally {
    server.close();
  }
  const dir = path.join(IMPL, 'out', 'b6-profiles');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${TAG}.cpuprofile`);
  fs.writeFileSync(file, JSON.stringify(out.profile));
  console.log(out.res.edn);
  console.log(`;; profile -> ${file}`);
  if (out.res.unverified > 0) {
    console.error(`[b6p] FAILED: ${out.res.unverified} of ${WRITES} writes did not reach the DOM`);
    process.exit(1);
  }
  console.error('[b6p] ok');
})();
