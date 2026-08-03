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
// ## No verdict — but every VALIDITY gate is a gate
//
// This prints a table. The stop/continue rulings on this programme are
// operator-owned on rf2-2rtt6.1 and a diagnostic must never learn to
// issue one — exit 0 means the probe RAN AND ITS READINGS ARE VALID, not
// that the heap is clean.
//
// That distinction is not a licence to fail open, and this driver was
// doing exactly that. The header already reserved exit 1 for "a census
// that could not be read" and nothing implemented it: a failed census
// positive control printed `[FAIL] the census cannot see a live
// subscription` and then `[ret] ok`; an absent heap column printed `n/a`;
// a census answering `:no-frame` printed the token; and in `repro` mode
// an early `return` skipped the page-error and unverified-cycle gates
// altogether. A count that is DISPLAYED but not GATED is decoration.
//
// So the gates below are VALIDITY gates — could the instrument see what
// it claims to have seen — and every one of them exits:
//
//   1. no uncaught page error;
//   2. every cycle of every series verified at the DOM — ALL of them, not
//      the last one (see SERIES C's fold);
//   3. the sub-cache census positive control passed, when SERIES B ran at
//      all; when it did not, the run says the census claim is out of
//      scope rather than quietly passing a vacuous `0 of 0`;
//   4. every reading is a reading — a numeric sub-cache count, a heap
//      figure, and `window.gc` present whenever `RETENTION_COLLECT` asks
//      the PAGE to collect — and `RETENTION_COLLECT` itself names a real
//      collector, refused up front on a typo, because a misspelt one
//      forces no collection and certifies uncollected heap as collected;
//   5. the probe completed at all — `P0H.prepare` now RAISES with the
//      failing teardown phase named, instead of swallowing it.
//
// The reaction-WATCHERS column is deliberately NOT a gate: it is blind
// under `:advanced` and carries no information in either direction, so it
// decides nothing in either direction.
//
// EVERY failed gate is named, never the last one — `failures` is a list,
// which is `p0_run.cjs`'s shape and the same rule.
//
// ## The build id
//
// `implementation/shadow-cljs.edn` is hot-zone. This rides rf2-2rtt6.2's
// `:hicasso-bench` with an output directory and an `:init-fn` merged in at
// the CLI, which is the seam that lane established for exactly this.

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

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

// THE ENUMS REFUSE A TYPO, before anything is built or measured. `collect()`
// matches these strings and silently does NOTHING on any other, so an
// unvalidated `RETENTION_COLLECT=bogus` performed no forced collection at
// all while the header still printed it as the collector forcing every
// COLLECTED reading — and the run exited 0, certifying UNCOLLECTED heap as
// collected. Proven at the landed head with the one-cycle `repro` shape
// (rf2-flqpd, merged-PR audit #7281). `none` stays a legal answer: asking
// for no collector is a question this probe exists to compare, and is
// nothing like failing to name one. `RETENTION_ONLY` is the same trap one
// door over — a typo there silently narrows the run to `repro` — so it is
// held to the same rule.
for (const [name, value, legal] of [
  ['RETENTION_COLLECT', COLLECT, ['both', 'page', 'cdp', 'none']],
  ['RETENTION_ONLY', ONLY, ['all', 'repro']],
]) {
  if (!legal.includes(value)) {
    console.error(
      `[ret] FAILED: ${name}=${JSON.stringify(value)} is not one of ` +
        `${legal.join(' | ')} — refusing before anything is measured. Without ` +
        `this gate an unrecognised value does not stop the run: a misspelt ` +
        `collector forces NO collection and certifies uncollected heap as ` +
        `COLLECTED; a misspelt scope silently narrows the run to repro.`
    );
    process.exit(1);
  }
}

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
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'ret',
  });
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

  // HAS A FRAME BEEN STOOD UP YET. Before the first `prepare` there is no
  // frame, so the sub-cache census truthfully answers `:no-frame` — the
  // pre-anything baseline row of SERIES A, and of SERIES C too when the
  // cheap series are skipped. That is a legitimate reading and not a
  // blind one, and the validity gate below has to be able to tell the
  // two apart: `:no-frame` AFTER a segment entry means the census is
  // pointed at nothing and no row is evidence about subscriptions.
  let prepared = false;
  const prepare = async (seg) => {
    await page.evaluate(`window.P0H.prepare(${JSON.stringify(seg)})`);
    prepared = true;
  };

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
    if (i > 0) await prepare(SEGMENT);
    entries.push({ i, ...(await census()), framed: prepared });
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
    await prepare(SEGMENT);
    const rows = [];
    rows.push({ i: 0, verify: null, mounted: null, ...(await census()), framed: prepared });
    for (let i = 1; i <= CYCLES; i++) {
      // The same verdict SHAPE as SERIES C's fold, so one gate reads both
      // series and neither can grow a private notion of "verified".
      const verify = await page.evaluate(
        `(() => { const v = window.P0H.mount(${JSON.stringify(arm)}, ${ROOTS});` +
          ' return {cycles: 1, bad: v.ok ? 0 : 1, ok: v.ok,' +
          '         first: v.ok ? null : {cycle: 0, elements: v.elements, expected: v.expected},' +
          '         elements: v.elements, expected: v.expected}; })()'
      );
      const mounted = await census();
      await page.evaluate('window.P0H.release()');
      rows.push({ i, verify, mounted, ...(await census()), framed: prepared });
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
  repro.push({
    i: 0,
    segment: 'baseline',
    verify: null,
    ...(await bothCensus()),
    framed: prepared,
  });
  for (let s = 0; s < plan.length; s += 1) {
    const [seg, arm] = plan[s];
    await prepare(seg);
    // EVERY cycle's verdict, not the last one. This loop used to overwrite
    // `last` and return only the final cycle's reading, so 49 of 50 cycles
    // could render nothing at all and the row would still print `ok` —
    // while SERIES C's own claim is that every one of them was DOM-verified
    // at 1,200 elements. A later cycle's silence must not overwrite an
    // earlier one's refusal, so the fold counts the failures and keeps the
    // FIRST one, which is the one that says when the page went wrong.
    const verify = await page.evaluate(
      `(async () => {
         let cycles = 0, bad = 0, first = null, last = null;
         for (let i = 0; i < ${REPRO_CYCLES}; i++) {
           const v = window.P0H.mount(${JSON.stringify(arm)}, ${REPRO_ROOTS});
           cycles += 1;
           last = v;
           if (!v.ok && first === null) {
             first = {cycle: i, elements: v.elements, expected: v.expected};
           }
           if (!v.ok) bad += 1;
           window.P0H.release();
         }
         return {cycles: cycles, bad: bad, ok: bad === 0, first: first,
                 elements: last === null ? -1 : last.elements,
                 expected: last === null ? -1 : last.expected};
       })()`
    );
    repro.push({
      i: s + 1,
      segment: seg,
      verify,
      ...(await bothCensus()),
      framed: prepared,
    });
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
        `${
          r.verify
            ? r.verify.ok
              ? `ok ${r.verify.elements} (${r.verify.cycles})`
              : `UNVERIFIED ${r.verify.bad}/${r.verify.cycles} cycles, first ` +
                `#${r.verify.first.cycle} ${r.verify.first.elements}/${r.verify.first.expected}`
            : '—'
        }`
    );
  }
}

(async () => {
  if (!NO_BUILD) build();
  const server = serve();
  let out;
  try {
    out = await run();
  } catch (e) {
    // NAMED, never a bare unhandled rejection. `P0H.prepare` now raises
    // with the failing teardown phase in its message (`p0-arms/teardown!`),
    // and that message is the whole point of the repair — losing it to
    // Node's default rejection handler would put the instrument back where
    // it started.
    console.error(`[ret] FAILED: the probe did not complete — ${e && e.stack ? e.stack : e}`);
    process.exit(1);
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

  // EVERY failed gate, named, in one list. This tail used to be a chain of
  // prints with an early `return` in the middle of it: in `repro` mode the
  // driver returned before it had looked at the page errors or the
  // unverified cycles at all, and a failing census positive control printed
  // `[FAIL] the census cannot see a live subscription` and then exited 0
  // anyway. A count that is displayed but not gated is decoration.
  //
  // WHAT THESE GATES ARE, AND WHAT THEY ARE NOT. They are VALIDITY gates —
  // they decide whether the instrument could see what it claims to have
  // seen. They are NOT a verdict on the heap: this diagnostic still prints
  // no ruling on whether the numbers are acceptable, because rows on this
  // programme are operator-owned (rf2-2rtt6.1). Exit 0 means THE PROBE RAN
  // AND ITS READINGS ARE VALID, which is a stronger claim than before and
  // still not an operator's.
  const failures = [];

  // THE CENSUS'S OWN POSITIVE CONTROL. `MOUNTED` is read with 1,200
  // boundaries standing; if it is the same as `released` on every row, the
  // census cannot see a live subscription and no conclusion may be drawn
  // from it about a dead one.
  const mountedRows = out.armRows.filter((r) => r.mounted);
  if (mountedRows.length === 0) {
    // NOT A PASS. `sawEntries.length === mountedRows.length` is `0 === 0`
    // on an empty set, so running the comparison here would print `[ok  ]
    // the census SEES a live subscription` for a control that never ran —
    // a vacuous truth reported as evidence, which is the same fault as the
    // vacuous bundle-composition check the sibling rig guards against.
    console.log(';; ==== CENSUS POSITIVE CONTROL — NOT RUN IN THIS MODE ====');
    console.log(
      ';;   SERIES B did not run, so the sub-cache census has no positive control in\n' +
        ';;   this run and NO claim about live or disposed subscriptions may be drawn\n' +
        ';;   from it. That is a NARROWED SCOPE, not a pass. The heap series above is\n' +
        ';;   what this mode measures.'
    );
  } else {
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
    const entriesOk = sawEntries.length === mountedRows.length;
    console.log(
      entriesOk
        ? ';;     [ok  ] the census SEES a live subscription, so a zero after release is a real zero'
        : ';;     [FAIL] the census cannot see a live subscription, so its zero after release is\n' +
          ';;            not evidence of anything'
    );
    // AND IT EXITS ON IT. This printed `[FAIL]` and then `[ret] ok` — an
    // exact landed one-cycle run, deliberately blinded, did precisely that.
    // The ENTRIES column is the one with a working control and it is the
    // column the H1-is-dead conclusion rests on; a run whose control failed
    // has not earned that conclusion.
    if (!entriesOk) {
      failures.push(
        `the sub-cache census POSITIVE CONTROL failed: only ${sawEntries.length} of ` +
          `${mountedRows.length} cycles read more entries with the roots standing than ` +
          `after release. The census cannot see a live subscription, so its zero after ` +
          `release is not evidence that anything was disposed.`
      );
    }
    console.log(
      `;;   reaction WATCHERS:  ${sawWatchers.length} of ${mountedRows.length} cycles read MORE ` +
        'while mounted than after release'
    );
    // NOT A GATE, deliberately. This column is BLIND under `:advanced` and
    // the probe says so on every run; gating it would refuse every valid
    // run, and reading its zero as "nothing is watching" is the misreading
    // the note exists to prevent. It carries no information in either
    // direction, so it decides nothing in either direction.
    console.log(
      sawWatchers.length === 0
        ? ';;     [UNCH] BLIND. `:advanced` renames a deftype field, so `watches` is not reachable\n' +
          ';;            by name from outside the type. This column carries NO information in\n' +
          ';;            EITHER direction and must not be read as "nothing is watching".'
        : ';;     [ok  ] the watcher census can see a live watcher'
    );
  }

  // --- the readings themselves have to be READINGS ------------------------
  //
  // The header has always said exit 1 is reserved for "a census that could
  // not be read", and nothing implemented it: a census that answered
  // `:no-frame`, `:no-sub-cache` or an error string printed the token in
  // the table and passed, and a heap column of `n/a` printed `n/a` and
  // passed. Every row of every series is checked, because a diagnostic
  // whose readings are absent has measured nothing whatever its table
  // looks like.
  const allRows = [
    ['SERIES A', out.entries],
    [`SERIES B ${ARM}`, out.armRows],
    [`SERIES B ${CONTROL_ARM}`, out.controlRows],
    ['SERIES C', out.repro],
  ];
  const readings = (r) => (r.mounted ? [r, r.mounted] : [r]);
  for (const [series, rows] of allRows) {
    for (const r of rows) {
      for (const c of readings(r)) {
        const sc = c.subCache || {};
        if (sc.error !== undefined) {
          failures.push(`${series} row ${r.i}: the sub-cache census threw — ${sc.error}`);
        } else if (typeof sc.entries !== 'number' && r.framed) {
          // GATED ON `framed`, not on the row index. The pre-anything
          // baseline row has no frame yet and `:no-frame` is the truthful
          // answer there; the same token AFTER a segment entry means the
          // census is pointed at nothing. Refusing both would refuse every
          // valid run, and refusing neither is how `:no-frame` used to
          // print into the table and pass.
          failures.push(
            `${series} row ${r.i}: the sub-cache census read ${JSON.stringify(sc.entries)} ` +
              `rather than a count, with a segment already entered — the census is ` +
              `pointed at nothing, so no row of this table is evidence about subscriptions`
          );
        }
        if (!(c.usedJSHeap >= 0)) {
          failures.push(
            `${series} row ${r.i}: no heap reading (performance.memory absent — ` +
              `--enable-precise-memory-info). The heap series IS this diagnostic.`
          );
        }
        // The page-side collector only has to exist when it is the one
        // being asked to collect. `RETENTION_COLLECT=cdp` and `=none`
        // never call it, so its absence there is not a fault.
        if ((COLLECT === 'page' || COLLECT === 'both') && !c.gcAvailable) {
          failures.push(
            `${series} row ${r.i}: RETENTION_COLLECT=${COLLECT} asks the PAGE to collect ` +
              `and window.gc is absent, so the call skipped silently and this reading ` +
              `did not follow the collection it claims to`
          );
        }
      }
      if (r.uncollectedHeap !== undefined && !(r.uncollectedHeap >= 0)) {
        failures.push(
          `${series} row ${r.i}: the UNCOLLECTED reading is missing, and it is the ` +
            `column the garbage-versus-retention conclusion is drawn from`
        );
      }
    }
  }

  const unverified = [...out.armRows, ...out.controlRows, ...out.repro].filter(
    (r) => r.verify && !r.verify.ok
  );
  if (out.pageErrors.length > 0) {
    failures.push(
      `${out.pageErrors.length} uncaught page error(s) — every census above ` +
        `was taken on a page that had already thrown:\n  ${out.pageErrors.join('\n  ')}`
    );
  }
  if (unverified.length > 0) {
    const detail = unverified
      .map((r) => `row ${r.i}: ${r.verify.bad} of ${r.verify.cycles} cycles`)
      .join('; ');
    failures.push(
      `${unverified.length} row(s) did not verify at the DOM (${detail}). An arm that ` +
        'silently rendered nothing would read as the substrate that retains least.'
    );
  }

  if (failures.length) {
    for (const f of failures) console.error(`[ret] FAILED: ${f}`);
    process.exit(1);
  }
  console.error('[ret] ok — the probe ran; the table above is the finding');
})();
