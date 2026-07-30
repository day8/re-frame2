#!/usr/bin/env node
// rf2-flqpd — the retention diagnostic's driver.
//
//   node implementation/freehand/test/re_frame/bench/hicasso/retention_run.cjs
//   RETENTION_SEGMENT=uix-subs RETENTION_CYCLES=8 node .../retention_run.cjs
//   node .../retention_run.cjs --no-build      (reuse the last bundle)
//
// ## What it drives, and why the driver holds the clock
//
// A page cannot force a garbage collection, so it cannot decide when a
// RETAINED figure is taken; a page that tried would be reading whatever
// the collector happened to have done. So every reading here is taken by
// this driver, over a CDP session, in the order: collect, read, act,
// collect, read.
//
// Two series, because rf2-flqpd's two candidate causes predict different
// shapes and one run can separate them:
//
//   SERIES A — SEGMENT ENTRIES, nothing mounted. `P0H.prepare(segment)`
//     destroys the adapter and the frame and stands both back up. If the
//     climb is here, the segment entry retains its predecessor and the
//     mounts are innocent.
//
//   SERIES B — MOUNT CYCLES inside ONE segment. Mount k roots, unmount
//     every one, drop every container. If the climb is here, an unmounted
//     root is still held, and the census says by how many watchers.
//
// ## No verdict
//
// This prints a table. The stop/continue rulings on this programme are
// operator-owned on rf2-2rtt6.1 and a diagnostic must never learn to
// issue one — exit 0 means the probe RAN, not that the heap is clean.
// Exit 1 is reserved for the probe failing: a page error, a mount that
// did not verify at the DOM, a census that could not be read.
//
// ## The build id
//
// `implementation/shadow-cljs.edn` is hot-zone. This rides rf2-2rtt6.2's
// `:hicasso-bench` with an output directory and an `:init-fn` merged in at
// the CLI, which is the seam that lane established for exactly this.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.RETENTION_OUT_DIR || 'out/hicasso-retention';
const OUT = path.join(IMPL, OUT_DIR);
const INIT_FN = 're-frame.bench.hicasso.retention-probe/-main';
const PORT = Number(process.env.RETENTION_PORT || 8137);

// The arm, the segment it needs, and how many roots a cycle mounts.
// `grid/uix` and `grid/reagent` are the two the observation was made on;
// `grid/floor` is the control that has no substrate, no subscription and
// no re-frame state at all, and the bead records it staying flat.
const SEGMENT = process.env.RETENTION_SEGMENT || 'uix-subs';
const ARM = process.env.RETENTION_ARM || 'grid/uix';
const CONTROL_ARM = process.env.RETENTION_CONTROL_ARM || 'grid/floor';
const CYCLES = Number(process.env.RETENTION_CYCLES || 6);
const ROOTS = Number(process.env.RETENTION_ROOTS || 4);
const ENTRIES = Number(process.env.RETENTION_ENTRIES || 6);

// SERIES C reproduces the bead's OWN shape: six adapter-segment entries,
// alternating substrate, ~200 mount/unmount cycles each. Six entries is
// what produced 34 -> 46 -> 55 -> 63 -> 75 -> 87 MB.
const REPRO_SEGMENTS = Number(process.env.RETENTION_REPRO_SEGMENTS || 6);
const REPRO_CYCLES = Number(process.env.RETENTION_REPRO_CYCLES || 50);
const REPRO_ROOTS = Number(process.env.RETENTION_REPRO_ROOTS || 4);

const NO_BUILD = process.argv.includes('--no-build');

// WHICH collector forces the reading, and it is a real question rather
// than a knob. `window.gc({type:'major',execution:'sync'})` is V8's
// `--expose-gc` door and is what rf2-flqpd used from inside the page;
// `HeapProfiler.collectGarbage` is the CDP door, which exists because a
// measurement needs a collection the page cannot ask for. If the two
// disagree, a mitigation built on the first is resting on the second.
//   both (default) | page | cdp | none
const COLLECT = process.env.RETENTION_COLLECT || 'both';
// `all` (default) | repro — the two cheap series are worth running once,
// not on every variant of the collector question.
const ONLY = process.env.RETENTION_ONLY || 'all';

const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." :modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  // The lane's cache rule, before anything reads the cache. `lane_cache.cjs`
  // carries the measurement and the rejected alternatives.
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[ret] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[ret] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, and a shell concatenates argv, which is
  // how the config-merge EDN gets torn in half.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(process.execPath, [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE], {
    cwd: IMPL,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (r.status !== 0) {
    console.error(`[ret] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>retention</title></head>' +
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

const MB = (b) => (b < 0 ? '   n/a' : (b / 1048576).toFixed(2).padStart(7));

async function run() {
  const { chromium } = require('playwright');
  const browser = await chromium.launch({
    // Precise memory info, because the 100 KB bucketing Chrome applies
    // without it is coarse enough to hide a per-cycle step even though it
    // could not hide the 12 MB one.
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => {
    pageErrors.push(e.message);
    console.error('[ret] PAGE ERROR:', e.message);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' || m.type() === 'warning') {
      console.error(`[ret] page ${m.type()}: ${m.text().slice(0, 300)}`);
    }
  });

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  // A MAJOR collection, synchronously, before every reading. The bead
  // records that this did not move the climb — which is what makes the
  // figure RETENTION rather than garbage, and it is repeated here so this
  // probe's numbers carry the same property.
  const collect = async () => {
    if (COLLECT === 'cdp' || COLLECT === 'both') await cdp.send('HeapProfiler.collectGarbage');
    if (COLLECT === 'page' || COLLECT === 'both') {
      await page.evaluate('window.gc && window.gc({type:"major",execution:"sync"})');
    }
  };

  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the wait for window.RETENTION_READY',
  });
  await page.waitForFunction('window.RETENTION_READY === true', null, { timeout: 120000 });

  const raw = () => page.evaluate('window.RETENTION.census()');
  const census = async () => {
    await collect();
    return raw();
  };
  // A reading BEFORE the collection and one AFTER it, from the same point
  // in the run. The gap between them is how much of an apparent climb is
  // GARBAGE the page had not been asked to drop — which is the one
  // possibility rf2-flqpd ruled out by calling `gc()` from inside the
  // page, and it is only ruled out if that `gc` existed.
  const bothCensus = async () => {
    const before = await raw();
    const after = await census();
    return { ...after, uncollectedHeap: before.usedJSHeap };
  };

  // --- SERIES A: bare segment entries, nothing mounted -------------------
  const entries = [];
  for (let i = 0; ONLY === 'all' && i <= ENTRIES; i++) {
    if (i > 0) await page.evaluate(`window.P0H.prepare(${JSON.stringify(SEGMENT)})`);
    entries.push({ i, ...(await census()) });
  }

  // --- SERIES B: mount cycles inside ONE segment -------------------------
  //
  // Each cycle takes TWO censuses: one while the roots are STANDING and
  // one after they are released. The mounted reading is this probe's
  // POSITIVE CONTROL — a census that reads zero subscriptions with 1,200
  // boundaries live is blind, and a blind census reporting "nothing is
  // retained" is worse than no census at all. It is printed on every row,
  // passing or not, because a control quoted only when it passes is not a
  // control.
  const series = async (arm) => {
    await page.evaluate(`window.P0H.prepare(${JSON.stringify(SEGMENT)})`);
    const rows = [];
    rows.push({ i: 0, verify: null, mounted: null, ...(await census()) });
    for (let i = 1; i <= CYCLES; i++) {
      const verify = await page.evaluate(
        `(() => { const v = window.P0H.mount(${JSON.stringify(arm)}, ${ROOTS});` +
          ' return {elements: v.elements, expected: v.expected, ok: v.ok}; })()'
      );
      const mounted = await census();
      await page.evaluate('window.P0H.release()');
      rows.push({ i, verify, mounted, ...(await census()) });
    }
    return rows;
  };

  const armRows = ONLY === 'all' ? await series(ARM) : [];
  const controlRows = ONLY === 'all' ? await series(CONTROL_ARM) : [];

  // --- SERIES C: THE REPRODUCTION ---------------------------------------
  //
  // The bead's own shape and not a smaller one: SIX adapter-segment
  // entries, alternating substrate, each followed by ~200 mount/unmount
  // cycles against the live frame. That is the run whose heap read
  // 34 -> 46 -> 55 -> 63 -> 75 -> 87 MB. Censuses are taken at the segment
  // seams only, which is where the observation was made and which keeps
  // the collector out of the cycle loop.
  const repro = [];
  const plan = [];
  for (let s = 0; s < REPRO_SEGMENTS; s += 1) {
    plan.push(s % 2 === 0 ? ['uix-subs', 'grid/uix'] : ['reagent-subs', 'grid/reagent']);
  }
  repro.push({ i: 0, segment: 'baseline', verify: null, ...(await bothCensus()) });
  for (let s = 0; s < plan.length; s += 1) {
    const [seg, arm] = plan[s];
    await page.evaluate(`window.P0H.prepare(${JSON.stringify(seg)})`);
    const verify = await page.evaluate(
      `(async () => {
         let last = null;
         for (let i = 0; i < ${REPRO_CYCLES}; i++) {
           last = window.P0H.mount(${JSON.stringify(arm)}, ${REPRO_ROOTS});
           window.P0H.release();
         }
         return {elements: last.elements, expected: last.expected, ok: last.ok};
       })()`
    );
    repro.push({ i: s + 1, segment: seg, verify, ...(await bothCensus()) });
  }

  const version = browser.version();
  await browser.close();
  return { entries, armRows, controlRows, repro, pageErrors, version };
}

const cell = (c) => `${String(c.entries).padStart(5)}/${String(c['watchers-total']).padStart(6)}`;

function table(title, rows, label) {
  console.log(`;; ==== ${title} ====`);
  console.log(
    `;;   ${label.padEnd(7)} COLLECTED  delta  UNCOLLECTED garbage  released  MOUNTED  gc?   DOM  verified`
  );
  console.log(
    `;;   ${''.padEnd(7)}     MB        MB        MB        MB    ent/watch ent/watch     elems`
  );
  let prev = null;
  for (const r of rows) {
    const heap = r.usedJSHeap;
    const delta = prev === null || heap < 0 || prev < 0 ? null : heap - prev;
    prev = heap;
    const sc = r.subCache || {};
    const m = r.mounted ? cell(r.mounted.subCache || {}) : '       —';
    const un = r.uncollectedHeap === undefined ? null : r.uncollectedHeap;
    const garbage = un === null || un < 0 || heap < 0 ? null : un - heap;
    console.log(
      `;;   ${String(r.i).padEnd(7)} ${MB(heap)} ${delta === null ? '     —' : MB(delta)} ` +
        `${un === null ? '     —' : MB(un)} ${garbage === null ? '     —' : MB(garbage)} ` +
        `${cell(sc)} ${m} ${r.gcAvailable ? ' yes' : '  NO'} ` +
        `${String(r.domElements).padStart(5)}  ` +
        `${r.verify ? (r.verify.ok ? `ok ${r.verify.elements}` : `UNVERIFIED ${r.verify.elements}/${r.verify.expected}`) : '—'}`
    );
  }
}

(async () => {
  if (!NO_BUILD) build();
  const server = serve();
  let out;
  try {
    out = await run();
  } finally {
    server.close();
  }

  console.log(';; ==== RETENTION DIAGNOSTIC (rf2-flqpd) ====');
  console.log(`;; chromium ${out.version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; segment ${SEGMENT}; arm ${ARM}; control ${CONTROL_ARM};`);
  console.log(`;; ${ROOTS} roots per cycle, ${CYCLES} cycles, ${ENTRIES} bare segment entries`);
  console.log(`;; collector forcing every COLLECTED reading: ${COLLECT}`);
  console.log(';; every reading follows a forced MAJOR collection, so every figure is RETAINED');
  console.log(';; THIS PRINTS NO VERDICT. Rows are operator-owned on rf2-2rtt6.1.');

  table('SERIES A — bare segment entries, nothing mounted', out.entries, 'entry');
  table(`SERIES B — mount/unmount cycles, arm ${ARM}`, out.armRows, 'cycle');
  table(`SERIES B — mount/unmount cycles, CONTROL ${CONTROL_ARM}`, out.controlRows, 'cycle');
  table(
    `SERIES C — the bead's own shape: ${REPRO_SEGMENTS} segment entries x ` +
      `${REPRO_CYCLES} cycles x ${REPRO_ROOTS} roots`,
    out.repro,
    'segment'
  );

  // THE CENSUS'S OWN POSITIVE CONTROL. `MOUNTED` is read with 1,200
  // boundaries standing; if it is the same as `released` on every row, the
  // census cannot see a live subscription and no conclusion may be drawn
  // from it about a dead one.
  const mountedRows = out.armRows.filter((r) => r.mounted);
  if (mountedRows.length === 0) {
    console.log(';; ==== CENSUS POSITIVE CONTROL — not run in this mode ====');
    console.error('[ret] ok — the probe ran; the table above is the finding');
    return;
  }
  const sawEntries = mountedRows.filter(
    (r) => ((r.mounted.subCache || {}).entries || 0) > ((r.subCache || {}).entries || 0)
  );
  const sawWatchers = mountedRows.filter(
    (r) =>
      ((r.mounted.subCache || {})['watchers-total'] || 0) >
      ((r.subCache || {})['watchers-total'] || 0)
  );
  console.log(';; ==== CENSUS POSITIVE CONTROL — published passing or not ====');
  console.log(
    `;;   sub-cache ENTRIES:  ${sawEntries.length} of ${mountedRows.length} cycles read MORE ` +
      'while mounted than after release'
  );
  console.log(
    sawEntries.length === mountedRows.length
      ? ';;     [ok  ] the census SEES a live subscription, so a zero after release is a real zero'
      : ';;     [FAIL] the census cannot see a live subscription, so its zero after release is\n' +
        ';;            not evidence of anything'
  );
  console.log(
    `;;   reaction WATCHERS:  ${sawWatchers.length} of ${mountedRows.length} cycles read MORE ` +
      'while mounted than after release'
  );
  console.log(
    sawWatchers.length === 0
      ? ';;     [UNCH] BLIND. `:advanced` renames a deftype field, so `watches` is not reachable\n' +
        ';;            by name from outside the type. This column carries NO information in\n' +
        ';;            EITHER direction and must not be read as "nothing is watching".'
      : ';;     [ok  ] the watcher census can see a live watcher'
  );

  const unverified = [...out.armRows, ...out.controlRows, ...out.repro].filter(
    (r) => r.verify && !r.verify.ok
  );
  if (out.pageErrors.length > 0) {
    console.error(
      `[ret] FAILED: ${out.pageErrors.length} uncaught page error(s) — every census above ` +
        `was taken on a page that had already thrown:\n  ${out.pageErrors.join('\n  ')}`
    );
    process.exit(1);
  }
  if (unverified.length > 0) {
    console.error(
      `[ret] FAILED: ${unverified.length} cycle(s) did not verify at the DOM. An arm that ` +
        'silently rendered nothing would read as the substrate that retains least.'
    );
    process.exit(1);
  }
  console.error('[ret] ok — the probe ran; the table above is the finding');
})();
