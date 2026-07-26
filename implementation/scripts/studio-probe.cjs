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
const QUERY   = arg('query', '');
const DIR     = path.join(ROOT, 'out', BUILD);

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
  const lines = [...self.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 30)
    .map(([k, n]) => `${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);

  // Bucket every sample by WHO the frame belongs to. This is the headroom
  // question: what share of a mount is substrate code at all, versus React,
  // the DOM, the engine and idle. Buckets are decided by the frame's own
  // name, so nothing is attributed by assumption.
  const bucket = new Map();
  for (const id of profile.samples) {
    const n = byId.get(id);
    const fn = n ? (n.callFrame.functionName || '(anonymous)') : '(unknown)';
    const url = n ? (n.callFrame.url || '') : '';
    let b;
    if (/^\(/.test(fn)) b = fn;                                  // (idle) (program) (gc)
    else if (/freehand|re_frame/.test(fn)) b = 'substrate: freehand/re-frame';
    else if (/^\$cljs\$core|^\$clojure\$/.test(fn)) b = 'substrate: cljs.core / clojure.string';
    else if (/^\$goog\$/.test(fn)) b = 'substrate: goog';
    else if (url === '') b = 'host: DOM/native';
    else b = 'react + engine (mangled)';
    bucket.set(b, (bucket.get(b) || 0) + 1);
  }
  const buckets = [...bucket.entries()].sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);

  // Caller attribution for a chosen frame. Self time says WHAT is hot; it
  // never says WHO made it hot, and a memoisation aimed at the wrong caller
  // is a change that costs and buys nothing.
  const callers = [];
  const want = arg('callers', null);
  if (want) {
    const parent = new Map();
    for (const n of profile.nodes) for (const c of n.children || []) parent.set(c, n.id);
    const hits = new Map();
    const selfCount = new Map();
    for (const id of profile.samples) {
      const n = byId.get(id);
      if (!n || !(n.callFrame.functionName || '').includes(want)) continue;
      selfCount.set(id, (selfCount.get(id) || 0) + 1);
      const chain = [];
      let p = parent.get(id);
      for (let d = 0; d < 4 && p !== undefined; d += 1) {
        const pn = byId.get(p);
        chain.push(pn ? (pn.callFrame.functionName || '(anon)') : '?');
        p = parent.get(p);
      }
      const key = chain.join('  <-  ');
      hits.set(key, (hits.get(key) || 0) + 1);
    }
    callers.push('', `--- callers of frames matching ${JSON.stringify(want)} ---`);
    for (const [k, n] of [...hits.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)) {
      callers.push(`${(100 * n / total).toFixed(2)}%  ${String(n).padStart(6)}  ${k}`);
    }
  }
  return [...lines, '', '--- by bucket ---', ...buckets, `total samples: ${total}`, ...callers];
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

  const url = `http://127.0.0.1:${port}/` + (QUERY ? `?${QUERY}` : '');
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction('window.__STUDIO_DONE__ === true', null, { timeout: 300000 });

  if (PROFILE) {
    const { profile } = await session.send('Profiler.stop');
    // rf2-xu6rx: keep the raw capture. Caller attribution is the instrument
    // that matters here and one question per browser run is both slow and
    // dishonest — every re-run is a different profile, so two frames compared
    // across two runs are not compared at all.
    const dest = path.join(ROOT, 'out', `${BUILD}-profile.json`);
    fs.writeFileSync(dest, JSON.stringify(profile));
    console.error(`\n--- CPU self time, ${BUILD} (sampling 100us, whole run) ---`);
    console.error(`raw profile: ${dest}`);
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
  if (QUERY) console.log(JSON.stringify(out));
  else summarise(out, BUILD);
  fs.writeFileSync(path.join(ROOT, 'out', `${BUILD}-raw.json`), JSON.stringify(out, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });

function stats(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.max(0, Math.ceil(p * s.length) - 1))];
  return { n: s.length, min: s[0], p50: q(0.5), p95: q(0.95), max: s[s.length - 1],
           mean: s.reduce((a, b) => a + b, 0) / s.length };
}
const f2 = (x) => (typeof x === 'number' ? x.toFixed(3) : String(x));

function table(title, group, keys) {
  console.log(`\n### ${title}`);
  console.log(['arm', ...keys.flatMap((k) => [`${k} p50`, `${k} min`, `${k} max`])].join('\t'));
  for (const [arm, rows] of Object.entries(group)) {
    const cells = keys.flatMap((k) => {
      const st = stats(rows.map((r) => r[k]));
      return [f2(st.p50), f2(st.min), f2(st.max)];
    });
    console.log([arm, ...cells].join('\t'));
  }
}

function summarise(out, build) {
  console.log(`# studio probe — ${build}`);
  console.log(`fixture: ${JSON.stringify(out.fixture)}`);
  console.log(`precise memory: ${out['precise-memory?']}`);
  console.log(`manifests: ${JSON.stringify(out.manifests)}`);
  console.log(`parity: ${JSON.stringify(out.parity)}`);
  table('MOUNT (ms)', out.mounts, ['react-ms', 'layout-ms', 'settle-ms', 'frame-ms']);
  table('MOUNT heap delta (bytes)', out.mounts, ['heap-bytes']);
  table('UPDATE (ms)', out.updates, ['react-ms', 'layout-ms', 'settle-ms']);
}
