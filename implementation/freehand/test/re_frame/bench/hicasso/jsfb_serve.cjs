#!/usr/bin/env node
// SERVE THE THREE BENCHMARK ARMS, so `jsfb_ours_run.cjs` can be run without
// the upstream clone (rf2-emvod).
//
//   node freehand/test/re_frame/bench/hicasso/jsfb_build.cjs --dest out/jsfb-dest
//   node freehand/test/re_frame/bench/hicasso/jsfb_serve.cjs --root out/jsfb-dest &
//   JSFB_ONLY=run1k node freehand/test/re_frame/bench/hicasso/jsfb_ours_run.cjs
//
// ## WHY THIS EXISTS, AND WHAT IT DELIBERATELY DOES NOT DO
//
// `jsfb_ours_run.cjs` is OUR instrument pointed at the benchmark's app. It
// needs nothing from `krausest/js-framework-benchmark` except the URL layout
// `/frameworks/keyed/<arm>/`, because the arms are ours and are built by
// `jsfb_build.cjs`. Until now the only documented way to reach that layout
// was to clone the benchmark, `npm install` its server and run it — three
// network-dependent steps to serve six static files, and a reproduction
// nobody can perform offline.
//
// It does NOT replace the benchmark's server for THEIR driver. Their
// `benchmarkRunner` reads `/ls`, applies its own throttling and writes its
// own results; running it still requires the clone, and the cross-check page
// says so. What this restores cheaply is the half of that page which is our
// instrument on their app — which is exactly the half that has now had to be
// re-measured twice, once for `rf2-yd52q`'s correction and once for this
// bead's reconciliation.
//
// The server is deliberately dumb: static files, no directory listing, no
// caching headers, and a path check that refuses anything resolving outside
// the root. A benchmark harness that serves from disk under load is
// measuring the server as much as the page, so it also logs nothing per
// request.

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const arg = (name, dflt) => {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
};

const ROOT = path.resolve(arg('--root', process.env.JSFB_ROOT || 'out/jsfb-dest'));
const PORT = Number(arg('--port', process.env.JSFB_PORT || 8080));

if (!fs.existsSync(path.join(ROOT, 'frameworks'))) {
  console.error(
    `[jsfb-serve] ${ROOT} has no frameworks/ directory — run jsfb_build.cjs --dest ${ROOT} first`
  );
  process.exit(1);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
};

const server = http.createServer((req, res) => {
  let rel;
  try {
    rel = decodeURIComponent(req.url.split('?')[0]);
  } catch {
    res.writeHead(400).end('bad request');
    return;
  }
  let file = path.join(ROOT, rel);
  // A directory means its index, which is how the benchmark's own URLs are
  // written: `/frameworks/keyed/rf2-uix/`.
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  // Resolved AFTER the index join, so `..` in a path cannot escape by way of
  // a directory that does exist.
  const resolved = path.resolve(file);
  if (resolved !== ROOT && !resolved.startsWith(ROOT + path.sep)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    res.writeHead(404).end('not found');
    return;
  }
  res.writeHead(200, { 'content-type': MIME[path.extname(resolved)] || 'application/octet-stream' });
  fs.createReadStream(resolved).pipe(res);
});

server.listen(PORT, () => {
  const arms = fs.existsSync(path.join(ROOT, 'frameworks', 'keyed'))
    ? fs.readdirSync(path.join(ROOT, 'frameworks', 'keyed'))
    : [];
  console.error(`[jsfb-serve] ${ROOT} on http://localhost:${PORT}/ — arms: ${arms.join(', ') || '(none)'}`);
  // THE STYLESHEET IS THE BENCHMARK'S AND IS NOT OURS TO VENDOR. Every arm's
  // `index.html` links `/css/currentStyle.css`, which lives in the upstream
  // clone; nothing from that repository is committed here, by ruling. Served
  // from a bare `--dest` the link 404s and the table renders UNSTYLED — which
  // changes style recalculation and layout, and therefore changes both the
  // absolute milliseconds and the arm-to-arm ratio.
  //
  // Announced at startup rather than left to a reader who notices 404s in a
  // console dump, because the failure is silent in exactly the place it must
  // not be: the page still works, the row still verifies, and the number is
  // simply of a different page. A run taken this way may compare two CLOCKS
  // on identical samples — that question does not involve the stylesheet —
  // and may NOT be put beside a published ratio taken against the clone.
  if (!fs.existsSync(path.join(ROOT, 'css', 'currentStyle.css'))) {
    console.error(
      `[jsfb-serve] WARNING: ${path.join(ROOT, 'css', 'currentStyle.css')} is ABSENT, so every arm ` +
        `renders UNSTYLED and its layout/style cost is not the published page's. Copy the clone's ` +
        `css/ directory into --root for a run whose ratio is comparable to the cross-check page.`
    );
  }
});

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
