#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-lnecd — deleted before the PR.
 *
 * Serves an :advanced studio-probe bundle over loopback, drives it in a real
 * Chromium, and prints the probe's readings as JSON on stdout.
 *
 *   node scripts/studio-probe.cjs --build studio-probe [--profile]
 *
 * --profile additionally takes a CDP CPU profile of the whole run and writes
 * a self-time-by-function table to stderr. Use it with `studio-probe-named`,
 * whose :pseudo-names keep production function names readable.
 */

'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');

function arg(name, dflt) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? dflt : process.argv[i + 1];
}
const BUILD   = arg('build', 'studio-probe');
const PROFILE = process.argv.includes('--profile');
const DIR     = path.join(ROOT, 'out', BUILD);

const PAGE = `<!doctype html><meta charset="utf-8"><title>studio probe</title>
<div id="app"></div><script src="main.js"></script>`;

function serve(dir) {
  const server = http.createServer((req, res) => {
    const name = req.url === '/' ? 'index.html' : req.url.replace(/^\//, '').split('?')[0];
    const file = path.join(dir, name);
    if (!file.startsWith(dir) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, {
      'Content-Type': name.endsWith('.js') ? 'text/javascript' : 'text/html',
      'Cache-Control': 'no-store',
    });
    res.end(fs.readFileSync(file));
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

function selfTimeTable(profile) {
  // Chrome's sampling profiler: attribute each sample to the node it landed
  // in. Self time only — an inclusive tree would just re-report the root.
  const byId = new Map(profile.nodes.map((n) => [n.id, n]));
  const self = new Map();
  const total = profile.samples.length;
  for (const id of profile.samples) {
    const n = byId.get(id);
    if (!n) continue;
    const f = n.callFrame;
    const key = `${f.functionName || '(anonymous)'}  ${(f.url || '').split('/').pop()}:${f.lineNumber}`;
    self.set(key, (self.get(key) || 0) + 1);
  }
  return [...self.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 45)
    .map(([k, n]) => `${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);
}

(async () => {
  if (!fs.existsSync(path.join(DIR, 'main.js'))) {
    console.error(`No bundle at ${DIR}/main.js — build it first.`);
    process.exit(1);
  }
  fs.writeFileSync(path.join(DIR, 'index.html'), PAGE);

  const server = await serve(DIR);
  const port = server.address().port;
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(`console: ${m.text()}`); });

  let session = null;
  if (PROFILE) {
    session = await page.context().newCDPSession(page);
    await session.send('Profiler.enable');
    await session.send('Profiler.setSamplingInterval', { interval: 100 }); // 100us
    await session.send('Profiler.start');
  }

  await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load' });
  await page.waitForFunction('window.__STUDIO_DONE__ === true', null, { timeout: 300000 });

  if (PROFILE) {
    const { profile } = await session.send('Profiler.stop');
    console.error(`\n--- CPU self time, ${BUILD} (sampling 100us, whole run) ---`);
    for (const line of selfTimeTable(profile)) console.error(line);
  }

  const err = await page.evaluate('window.__STUDIO_ERROR__ || null');
  const out = await page.evaluate('window.__STUDIO__ || null');
  await browser.close();
  server.close();

  if (err) { console.error(`PROBE ERROR: ${err}`); process.exit(1); }
  if (pageErrors.length) {
    console.error(`PAGE ERRORS (${pageErrors.length}):`);
    for (const e of pageErrors.slice(0, 10)) console.error(`  ${e}`);
    process.exit(1);
  }
  console.log(JSON.stringify({ build: BUILD, ...out }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });
