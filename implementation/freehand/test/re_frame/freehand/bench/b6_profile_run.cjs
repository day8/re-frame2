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

const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');
const { watchPage } = require('./sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
// The donor build id, hoisted out of the `spawnSync` argv it used to be a
// literal in, so the cache clear and the build cannot name different ids
// (rf2-t4j7c).
const BUILD = 'freehand-release';
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
  // The lane's cache rule, before anything reads the cache: this driver merges
  // its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry was written by a
  // different program — and this one also merges `:pseudo-names`, so its entry
  // is renamed differently again. `lane_cache.cjs` carries the measured fault
  // and the rejected alternatives (rf2-2rtt6.20).
  if (resetLaneBuildCache(IMPL, BUILD)) {
    console.error(`[b6p] cleared .shadow-cljs/builds/${BUILD} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error('[b6p] building :advanced + :pseudo-names bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE],
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
  // THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23) — see
  // the note in `b6_prod_run.cjs` and the finding in `sentinel.cjs`. A profile
  // is the LAST place a thrown-and-continued page should pass silently: the
  // sample stream would be of the recovery, and the flame graph would look
  // like an answer.
  const watch = watchPage(page, 'b6p');
  // `'commit'`, not `'load'` (rf2-p9fa3). `b6-profile-app/-main` is this
  // bundle's `:init-fn`: it mounts the profiled arm, mounts the reference arm
  // and canonicalises BOTH pages for the parity gate — all synchronously,
  // inside the `<script>`, before the seed promise flips `B6_READY`. `load`
  // is downstream of every one of those, so it cannot fire until the wait
  // below is already satisfiable. A `:pseudo-names` bundle is also the
  // largest artefact any driver here serves, which makes this the site most
  // likely to have quietly spent seconds of the anonymous 30s on parse alone.
  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 5-minute wait for `window.B6_READY`',
  });
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`. A profile
  // whose page died at load used to cost five minutes to say so. `race`
  // rejects only on a failure `watch` recorded, which `verdict` below already
  // refuses on, so no run that would have passed is shortened; the rejection
  // takes this driver's existing exit 1 through `drive`'s rejection handler.
  await watch.race('window.B6_READY === true || window.B6_ERROR', {
    timeoutMs: 5 * 60 * 1000,
    budget: 'the 5-minute wait for `window.B6_READY`',
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
  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  return { res, profile, pageErrors };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// ONE pure function over the run's outcome, exported so the refusal can be
// watched without a `:pseudo-names` build and a headless Chromium (rf2-y7mw7's
// shape). The unverified-writes gate is what it always was; the page-error
// gate is rf2-sib23's repair and takes the same code.
//
//   0  profiled
//   1  the run failed its own gates (writes that never reached the DOM, or
//      the page threw)
//
// The `.cpuprofile` is written before this is consulted: a refusal is about
// what may be QUOTED, not about throwing the artefact away.
function verdict({ unverified, of, pageErrors } = {}) {
  const errs = pageErrors || [];
  const lines = [];
  if (unverified > 0) {
    lines.push(`[b6p] FAILED: ${unverified} of ${of} writes did not reach the DOM`);
  }
  if (errs.length) {
    lines.push(
      '[b6p] FAILED: the page threw and kept going, so the profile above is of a page that is ' +
        'not the page under test (rf2-sib23):\n  ' +
        errs.join('\n  ')
    );
  }
  return { code: unverified > 0 || errs.length ? 1 : 0, lines };
}

async function drive() {
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
  const v = verdict({ unverified: out.res.unverified, of: WRITES, pageErrors: out.pageErrors });
  for (const line of v.lines) console.error(line);
  if (v.code === 0) console.error('[b6p] ok');
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
      console.error(`[b6p] FAILED: ${e && e.stack ? e.stack : e}`);
      process.exit(1);
    }
  );
}
