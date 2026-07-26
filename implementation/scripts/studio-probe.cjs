#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-lnecd's ELISION ABLATION — deleted before the PR.
 *
 * Serves the :advanced studio-probe bundle over loopback, drives it in a real
 * Chromium, and writes the probe's readings as JSON.
 *
 *   node scripts/studio-probe.cjs --out out/clock-1.json
 *   node scripts/studio-probe.cjs --alloc --n 40 --out out/alloc-1.json
 *   node scripts/studio-probe.cjs --profile --query 'profile=free-ik&n=150'
 *
 * --alloc is the PRIMARY instrument. Wall clock on this box drifts far more
 * than most of the effects being measured (the earlier pass watched a floor
 * arm that cannot change move 37% between rounds), so the headline reading is
 * BYTES ALLOCATED, taken from the CDP heap sampler around a window containing
 * one arm's mount/unmount loop and nothing else. Allocation is a counter, not
 * an occupancy sample: a collection inside the window cannot make it smaller.
 * Arms are still interleaved round-robin, because a measurement that only
 * looks deterministic has not been shown to be.
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
const ALLOC   = process.argv.includes('--alloc');
const ITERS   = Number(arg('n', '40'));
const ROUNDS  = Number(arg('rounds', '3'));
const QUERY   = arg('query', '');
const OUT     = arg('out', null);
const DIR     = path.join(ROOT, 'out', BUILD);

const ARMS = ['free-floor', 'free-cc', 'free-ic', 'free-ik', 'free-i',
              'read-floor', 'read-cc', 'read-ic', 'read-i'];

const PAGE = `<!doctype html><meta charset="utf-8"><title>studio probe</title>
<div id="app"></div><script src="main.js"></script>`;

function serve(dir) {
  const server = http.createServer((req, res) => {
    const bare = req.url.split('?')[0];
    const name = (bare === '/' || bare === '') ? 'index.html' : bare.replace(/^\//, '');
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
  const lines = [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)
    .map(([k, n]) => `${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);
  const bucket = new Map();
  for (const id of profile.samples) {
    const n = byId.get(id);
    const fn = n ? (n.callFrame.functionName || '(anonymous)') : '(unknown)';
    const url = n ? (n.callFrame.url || '') : '';
    let b;
    if (/^\(/.test(fn)) b = fn;
    else if (/freehand|re_frame/.test(fn)) b = 'substrate: freehand/re-frame';
    else if (/^\$cljs\$core|^\$clojure\$/.test(fn)) b = 'substrate: cljs.core / clojure.string';
    else if (/^\$goog\$/.test(fn)) b = 'substrate: goog';
    else if (url === '') b = 'host: DOM/native';
    else b = 'react + engine (mangled)';
    bucket.set(b, (bucket.get(b) || 0) + 1);
  }
  const buckets = [...bucket.entries()].sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);
  return [...lines, '', '--- by bucket ---', ...buckets, `total samples: ${total}`];
}

/* Sum every node's selfSize in a CDP SamplingHeapProfile. */
function totalBytes(node) {
  let sum = node.selfSize || 0;
  for (const c of node.children || []) sum += totalBytes(c);
  return sum;
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
    await session.send('Profiler.setSamplingInterval', { interval: 100 });
    await session.send('Profiler.start');
  }

  const q = ALLOC ? 'alloc=1' : QUERY;
  await page.goto(`http://127.0.0.1:${port}/` + (q ? `?${q}` : ''), { waitUntil: 'load' });
  await page.waitForFunction('window.__STUDIO_DONE__ === true', null, { timeout: 600000 });

  let out = await page.evaluate('window.__STUDIO__ || null');

  if (ALLOC) {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('HeapProfiler.enable');
    const bytes = {};
    for (const a of ARMS) bytes[a] = [];
    for (let r = 0; r < ROUNDS; r += 1) {
      for (const armId of ARMS) {
        await cdp.send('HeapProfiler.collectGarbage');
        // 512-byte sampling interval: fine enough that a 300-boundary mount
        // contributes thousands of samples, coarse enough not to perturb.
        await cdp.send('HeapProfiler.startSampling', { samplingInterval: 512 });
        await page.evaluate(([id, n]) => window.__studioRun__(id, n), [armId, ITERS]);
        const { profile } = await cdp.send('HeapProfiler.stopSampling');
        bytes[armId].push(totalBytes(profile.head) / ITERS);
      }
      process.stderr.write(`alloc round ${r + 1}/${ROUNDS} done\n`);
    }
    out = {
      mode: 'alloc',
      iterations: ITERS,
      rounds: ROUNDS,
      'bytes-per-mount': bytes,
      verdicts: await page.evaluate('window.__STUDIO_VERDICTS__'),
    };
  }

  if (PROFILE) {
    const { profile } = await session.send('Profiler.stop');
    console.error(`\n--- CPU self time, ${BUILD} ---`);
    for (const line of selfTimeTable(profile)) console.error(line);
  }

  const err = await page.evaluate('window.__STUDIO_ERROR__ || null');
  await browser.close();
  server.close();

  if (err) { console.error(`PROBE ERROR: ${err}`); process.exit(1); }
  if (pageErrors.length) {
    console.error(`PAGE ERRORS (${pageErrors.length}):`);
    for (const e of pageErrors.slice(0, 10)) console.error(`  ${e}`);
    process.exit(1);
  }
  const dest = OUT || path.join(ROOT, 'out', `${BUILD}-raw.json`);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, JSON.stringify(out, null, 2));
  console.error(`wrote ${dest}`);
})().catch((e) => { console.error(e); process.exit(1); });
