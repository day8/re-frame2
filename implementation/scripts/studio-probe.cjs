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
const RETAIN  = process.argv.includes('--retain');
const ROOTS   = Number(arg('roots', '10'));
const ITERS   = Number(arg('n', '40'));
const ROUNDS  = Number(arg('rounds', '3'));
const QUERY   = arg('query', '');
const OUT     = arg('out', null);
const INTERVAL= Number(arg('sample-interval', '512'));
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

/* How many sampled allocation sites the profile contains — the sampler's own
 * confidence. A bytes figure built from a handful of nodes is noise. */
function countNodes(node) {
  let n = (node.selfSize || 0) > 0 ? 1 : 0;
  for (const c of node.children || []) n += countNodes(c);
  return n;
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

  // The allocation session is opened BEFORE navigation, and that is not
  // incidental. Opened AFTER `page.goto`, `HeapProfiler.startSampling`
  // silently reports ~0.01 MB no matter what the page allocates — a positive
  // control that burned 1 MB, 9 MB and 36 MB of plain objects got 0.03, 0.01
  // and 0.01 MB back, and the first alloc run of this ablation was reading
  // exactly that nothing. Moved ahead of the navigation, the same control
  // returns 4.75 MB against a 4.71 MB occupancy delta. See
  // scripts/studio-alloc-control.cjs.
  const allocCdp = (ALLOC || RETAIN) ? await page.context().newCDPSession(page) : null;
  if (allocCdp) await allocCdp.send('HeapProfiler.enable');

  let session = null;
  if (PROFILE) {
    session = await page.context().newCDPSession(page);
    await session.send('Profiler.enable');
    await session.send('Profiler.setSamplingInterval', { interval: 100 });
    await session.send('Profiler.start');
  }

  const q = (ALLOC || RETAIN) ? 'alloc=1' : QUERY;
  await page.goto(`http://127.0.0.1:${port}/` + (q ? `?${q}` : ''), { waitUntil: 'load' });
  await page.waitForFunction('window.__STUDIO_DONE__ === true', null, { timeout: 600000 });

  let out = await page.evaluate('window.__STUDIO__ || null');

  if (ALLOC) {
    const cdp = allocCdp;

    // The POSITIVE CONTROL, in situ. Burns a known ~4.7 MB of plain objects
    // inside the very same start/stop window the arms use, in the very same
    // page. Without it, an arm reporting 10 KB is unfalsifiable: it could be a
    // cheap arm or a sampler that is not running.
    await cdp.send('HeapProfiler.collectGarbage');
    await cdp.send('HeapProfiler.startSampling', { samplingInterval: INTERVAL });
    await page.evaluate(() => {
      const s = [];
      for (let i = 0; i < 80000; i += 1) s.push({ a: i, b: 'x', c: null, d: [i] });
      window.__control__ = s.length;
    });
    const ctl = await cdp.send('HeapProfiler.stopSampling');
    const ctlMb = totalBytes(ctl.profile.head) / 1048576;
    process.stderr.write(`alloc positive control: expected ~4.7 MB, sampler saw ${ctlMb.toFixed(2)} MB\n`);

    const bytes = {};
    const samples = {};
    for (const a of ARMS) bytes[a] = [];
    for (let r = 0; r < ROUNDS; r += 1) {
      for (const armId of ARMS) {
        await cdp.send('HeapProfiler.collectGarbage');
        // The sampling interval is a FLAG, not a constant, because the first
        // run had no way to tell "this arm allocates little" from "the sampler
        // saw little". Halving the interval must roughly double the sample
        // count and leave bytes/mount alone; if it moves bytes/mount, the
        // reading is undersampled and not evidence. `--sample-interval`.
        await cdp.send('HeapProfiler.startSampling', { samplingInterval: INTERVAL });
        await page.evaluate(([id, n]) => window.__studioRun__(id, n), [armId, ITERS]);
        const { profile } = await cdp.send('HeapProfiler.stopSampling');
        bytes[armId].push(totalBytes(profile.head) / ITERS);
        (samples[armId] = samples[armId] || []).push(countNodes(profile.head));
      }
      process.stderr.write(`alloc round ${r + 1}/${ROUNDS} done\n`);
    }
    out = {
      mode: 'alloc',
      iterations: ITERS,
      rounds: ROUNDS,
      'sample-interval': INTERVAL,
      'positive-control-mb': ctlMb,
      'bytes-per-mount': bytes,
      'sampled-sites': samples,
      verdicts: await page.evaluate('window.__STUDIO_VERDICTS__'),
    };
  }

  if (RETAIN) {
    const cdp = allocCdp;
    const N_BOUNDARIES = 300;            // the fixture's leaf count per root
    const gc = async () => { await cdp.send('HeapProfiler.collectGarbage');
                             await cdp.send('HeapProfiler.collectGarbage'); };
    const heap = () => page.evaluate('performance.memory.usedJSHeapSize');

    // POSITIVE CONTROL for the retained-heap reading, in situ: ~4.7 MB of
    // plain objects held by a global. Both instruments must see it.
    await gc();
    const c0 = await heap();
    await cdp.send('HeapProfiler.startSampling', { samplingInterval: INTERVAL });
    await page.evaluate(() => { const s = [];
      for (let i = 0; i < 80000; i += 1) s.push({ a: i, b: 'x', c: null, d: [i] });
      window.__control__ = s; });
    const ctl = await cdp.send('HeapProfiler.stopSampling');
    await gc();
    const c1 = await heap();
    const control = { 'sampler-mb': totalBytes(ctl.profile.head) / 1048576,
                      'occupancy-mb': (c1 - c0) / 1048576 };
    await page.evaluate('window.__control__ = null');
    await gc();
    process.stderr.write(`retain positive control (expect ~4.7 MB): sampler ` +
      `${control['sampler-mb'].toFixed(2)} MB, occupancy ${control['occupancy-mb'].toFixed(2)} MB\n`);

    const sampled = {}, occupancy = {}, recovered = {};
    for (const a of ARMS) { sampled[a] = []; occupancy[a] = []; recovered[a] = []; }
    for (let r = 0; r < ROUNDS; r += 1) {
      for (const armId of ARMS) {
        await page.evaluate('window.__studioReleaseAll__()');
        await gc();
        const h0 = await heap();
        await cdp.send('HeapProfiler.startSampling', { samplingInterval: INTERVAL });
        await page.evaluate(([id, k]) => window.__studioRetain__(id, k), [armId, ROOTS]);
        const { profile } = await cdp.send('HeapProfiler.stopSampling');
        await gc();
        const h1 = await heap();
        await page.evaluate('window.__studioReleaseAll__()');
        await gc();
        const h2 = await heap();
        const per = ROOTS * N_BOUNDARIES;
        sampled[armId].push(totalBytes(profile.head) / per);
        occupancy[armId].push((h1 - h0) / per);
        recovered[armId].push((h1 - h2) / per);
      }
      process.stderr.write(`retain round ${r + 1}/${ROUNDS} done\n`);
    }
    out = {
      mode: 'retain',
      roots: ROOTS,
      'boundaries-per-root': N_BOUNDARIES,
      rounds: ROUNDS,
      'sample-interval': INTERVAL,
      'positive-control': control,
      'sampled-bytes-per-boundary': sampled,
      'occupancy-bytes-per-boundary': occupancy,
      'recovered-bytes-per-boundary': recovered,
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
