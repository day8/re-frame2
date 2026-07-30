#!/usr/bin/env node
// THE READS-PER-BOUNDARY HEAP LADDER — driver.
//
//   node implementation/freehand/test/re_frame/freehand/bench/reads_ladder_run.cjs
//   LADDER_ROUNDS=6 LADDER_SNAPSHOT=1 node .../reads_ladder_run.cjs
//   LADDER_SUBSTRATES=uix node .../reads_ladder_run.cjs        # one arm only
//
// Bead rf2-2rtt6.5, epic rf2-2rtt6 (EP-0038). Reports into rf2-2rtt6.1's
// P0 table and docs/design/hicasso/studio/.
//
// ## What this measures, and the fault it is built around
//
// RETENTION, never allocation. V8's CDP *sampling* heap profiler drops the
// samples of collected objects: pointed at a mount/unmount loop it reports
// the residue of a page that has already been discarded, not the cost of
// the page — the same 80,000 objects read 4.77 MB when a global held them
// and 0.00 MB when nothing did. That fault has already produced one wrong
// table on this surface, and the reasoning that rationalised it ("allocation
// is a counter; a collection inside the window cannot make it smaller") is
// simply false of a sampler. So a reading here is: MOUNT AN ARM AND KEEP
// IT, force a full collection, read the heap; release, collect, read again.
//
// ## Why this driver exists rather than `b7_run.cjs` with different env
//
// B7's driver is deliberately re-pointable (`B7_ARMS`, `B7_INIT_FN`) and
// this ladder started there. Two properties it cannot express:
//
//   1. **A per-arm boundary count.** B7 divides every arm by one global
//      `ROOTS * perRoot`. Curve A's whole point is that the boundary count
//      VARIES across arms, so the divisor has to travel with the arm.
//   2. **Two pages in one run.** `rf/init!` keeps the first adapter
//      installed in a JS context, so the Reagent-on-subs and UIx-on-subs
//      arms cannot share a page. Each substrate needs its own page load,
//      its own floors, and its own within-page subtraction.
//
// Everything else is B7's design, reused rather than reinvented: the
// three-collections-with-a-beat collector, the pre/held/post triple, the
// unread warm-up pass, the in-situ predicted control, the streaming
// snapshot reader, and `order_guard.cjs` itself, which is REQUIRED here
// rather than copied.
//
// ## Readers
//
//   A  CDP `Runtime.getHeapUsage().usedSize`, after 3x collectGarbage.
//   B  in-page `performance.memory.usedJSHeapSize`, same moment, under
//      `--enable-precise-memory-info`.
//   C  a full heap SNAPSHOT, every node's `self_size` summed by a
//      streaming scan, as its own pass.
//
// A and B are NOT independent — two doors onto one V8 counter; on 80,000
// held objects B7 got 3,868,954 from both. B is a cross-check that the page
// and the debugger see the same heap, nothing more. C walks the object
// graph and is the reader that makes the table falsifiable.
//
// ## The positive control
//
// A dense JS array of N doubles, which V8 stores as N unboxed 8-byte slots,
// so its retained size is 8N bytes PREDICTED BEFORE ANYTHING IS MEASURED.
// It rides EVERY round, in situ, read by the same instrument as the arms.
// A heap number without a control is not a measurement.
//
// ## Order
//
// `order_guard.schedule` rotates AND REFLECTS with the round, so every arm
// is measured after at least two distinct predecessors and both whole-plan
// orders run. Even rounds are FORWARD, odd rounds REVERSED, and the report
// carries the two separately — position dominates adjacency, so the pair is
// there to show the figure does not move, not to average. `verdict` refuses
// on a contaminated OR an unchecked arm and this driver EXITS 2 on refusal.
// Repair the arm, not the guard.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const guard = require('./order_guard.cjs');
const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
// FORWARD SLASHES, and not by accident. `:output-dir` is spliced into the
// `--config-merge` EDN string below, and on Windows `path.join('out',
// 'reads-ladder')` yields `out\reads-ladder` whose `\r` the EDN reader takes
// as a carriage return: shadow-cljs then writes `outeads-ladder\main.js` and
// dies on a filename the OS will not accept. Seen once, here.
const OUT_DIR = process.env.LADDER_OUT_DIR || 'out/reads-ladder';
const OUT = path.resolve(IMPL, OUT_DIR);
const PORT = Number(process.env.LADDER_PORT || 8137);

const ROUNDS = Number(process.env.LADDER_ROUNDS || 6);
const SUBSTRATES = (process.env.LADDER_SUBSTRATES || 'reagent,uix')
  .split(',').map((s) => s.trim()).filter(Boolean);
const WANT_SNAPSHOT = process.env.LADDER_SNAPSHOT !== '0';
// 587,500 unboxed doubles = 4,700,000 bytes — deliberately the same ~4.7 MB
// the predecessor's broken sampler reported as 0.00 MB.
const CONTROL_DOUBLES = Number(process.env.LADDER_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
// A relative difference of medians, matching B7's. A retention reading taken
// across a mount/collect/release cycle moves several percent between rounds,
// so this sits above that and far below the 2.01x the recorded fault made.
const TOLERANCE = Number(process.env.LADDER_TOLERANCE || 0.25);
const INIT_FN = 're-frame.freehand.bench.reads-ladder-app/-main';

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and then reports `EOF while
// reading` from a fragment.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  console.error('[ladder] building :advanced bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[ladder] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>reads ladder</title></head>' +
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

// ---------------------------------------------------------------------------
// The collector and the readers
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function newPage(chromium, query, budget) {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.error('[ladder] page error:', e.message));
  // `'commit'`, not `'load'` (rf2-p9fa3): the bundle's `:init-fn` runs
  // synchronously inside the `<script>`, so the load event is downstream of
  // work this driver budgets separately.
  await navigate(page, `http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget,
  });
  return { browser, page };
}

async function makeReaders(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('Runtime.enable');

  // Three collections with a beat between them. One is not enough: React
  // roots die in stages (fibers, then the host instances they point at),
  // and a single pass leaves the second stage standing.
  const gc = async () => {
    for (let i = 0; i < 3; i++) {
      await cdp.send('HeapProfiler.collectGarbage');
      await sleep(80);
    }
  };

  const read = async () => {
    const { usedSize } = await cdp.send('Runtime.getHeapUsage');
    const perf = await page.evaluate(() => window.LADDER.perfMem());
    return { cdp: usedSize, perf };
  };

  return { cdp, gc, read };
}

// Sum every node's `self_size` out of a streaming heap snapshot. The
// snapshot arrives as JSON text in chunks and can be hundreds of megabytes,
// so it is never assembled or `JSON.parse`d: `nodes` is a flat run of
// integers, `node_fields` names the stride, and this consumes complete
// integers per chunk and carries the partial one. It is the only reader here
// that walks the object graph, which is what makes it a check on the other
// two rather than a restatement of them.
function snapshotTotal(cdp) {
  return new Promise((resolve, reject) => {
    let phase = 0;
    let buf = '';
    let nFields = 0;
    let selfIdx = -1;
    let idx = 0;
    let total = 0;
    let nodeCount = 0;

    const onChunk = ({ chunk }) => {
      if (phase === 2) return;
      buf += chunk;
      if (phase === 0) {
        const at = buf.indexOf('"nodes":[');
        if (at === -1) return;
        const fm = /"node_fields":\s*\[([^\]]*)\]/.exec(buf.slice(0, at));
        if (!fm) { phase = 2; reject(new Error('node_fields absent from snapshot header')); return; }
        const fields = fm[1].split(',').map((s) => s.trim().replace(/^"|"$/g, ''));
        nFields = fields.length;
        selfIdx = fields.indexOf('self_size');
        if (selfIdx < 0) { phase = 2; reject(new Error('self_size absent from node_fields')); return; }
        buf = buf.slice(at + '"nodes":['.length);
        phase = 1;
      }
      let last = 0;
      let done = false;
      for (let i = 0; i < buf.length; i++) {
        const c = buf.charCodeAt(i);
        if (c === 44 || c === 93) {
          const s = buf.slice(last, i);
          if (s.length) {
            if (idx % nFields === selfIdx) total += Number(s);
            if (idx % nFields === 0) nodeCount++;
            idx++;
          }
          last = i + 1;
          if (c === 93) { done = true; break; }
        }
      }
      buf = done ? '' : buf.slice(last);
      if (done) phase = 2;
    };

    cdp.on('HeapProfiler.addHeapSnapshotChunk', onChunk);
    cdp.send('HeapProfiler.takeHeapSnapshot', { reportProgress: false })
      .then(() => { cdp.off('HeapProfiler.addHeapSnapshotChunk', onChunk); resolve({ totalSelfSize: total, nodeCount }); })
      .catch((e) => { cdp.off('HeapProfiler.addHeapSnapshotChunk', onChunk); reject(e); });
  });
}

// ---------------------------------------------------------------------------
// Arm naming — the plan is derived from the page, never assumed here
// ---------------------------------------------------------------------------

// `floor/b-r0-b1200`, `uix/b-r7-b1200`, `reagent/a-r3-b2400`. The driver
// parses rather than reconstructs, so a rename in the CLJS cannot silently
// desynchronise the two halves of the instrument.
function parseArm(id) {
  const m = /^([a-z]+)\/([ab])-r(\d+)-b(\d+)$/.exec(id);
  if (!m) throw new Error(`unparseable arm id: ${id}`);
  return { id, kind: m[1], curve: m[2], reads: Number(m[3]), boundaries: Number(m[4]) };
}

const floorFor = (arms, boundaries) =>
  arms.find((a) => a.kind === 'floor' && a.boundaries === boundaries);

// ---------------------------------------------------------------------------
// One substrate's page
// ---------------------------------------------------------------------------

async function runSubstrate(chromium, substrate) {
  const { browser, page } = await newPage(
    chromium, `?adapter=${substrate}`, `the 120s wait for window.LADDER_READY (${substrate})`
  );
  await page.waitForFunction(
    'window.LADDER_READY === true || window.LADDER_ERROR', null, { timeout: 120000 }
  );
  const err = await page.evaluate('window.LADDER_ERROR || null');
  if (err) { await browser.close(); throw new Error(`${substrate} page failed to initialise: ${err}`); }

  const armIds = await page.evaluate(() => Array.from(window.LADDER.arms));
  const plan = await page.evaluate(() => window.LADDER.plan);
  const arms = armIds.map(parseArm);
  const { cdp, gc, read } = await makeReaders(page);

  const rounds = [];
  const orderSamples = [];
  let position = 0;
  let previous = null;
  let unverified = 0;
  let mounts = 0;

  // A WARM-UP PASS, mounted and released once per arm and never read. The
  // first mount of any arm allocates things that are not the page and never
  // go away: compiled code for the paths it just took, inline caches,
  // interned keywords, one-time module state. Charged to round 1 they read
  // as retention per boundary, and they are not. Position dominates
  // adjacency, so this pass matters more than the interleaving does.
  console.error(`[ladder] ${substrate}: warm-up pass over ${arms.length} arms ...`);
  for (const a of arms) {
    const v = await page.evaluate((id) => window.LADDER.mount(id), a.id);
    mounts++;
    if (!v.ok) { unverified++; console.error(`[ladder] UNVERIFIED warm-up ${a.id}: ${v.elements}/${v.expected}`); }
    await page.evaluate(() => window.LADDER.release());
  }
  await page.evaluate((n) => window.LADDER.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.LADDER.controlRelease());

  for (let round = 0; round < ROUNDS; round++) {
    console.error(`[ladder] ${substrate}: round ${round + 1}/${ROUNDS} (${round % 2 ? 'REVERSED' : 'FORWARD'})`);

    // --- the positive control, IN SITU, before this round's arms ---------
    await gc();
    const ctlBefore = await read();
    const ctlLen = await page.evaluate((n) => window.LADDER.control(n), CONTROL_DOUBLES);
    await gc();
    const ctlHeld = await read();
    await page.evaluate(() => window.LADDER.controlRelease());
    await gc();
    const ctlAfter = await read();
    const control = {
      doubles: ctlLen,
      predictedBytes: CONTROL_PREDICTED,
      measuredCdp: ctlHeld.cdp - (ctlBefore.cdp + ctlAfter.cdp) / 2,
      measuredPerf: ctlHeld.perf - (ctlBefore.perf + ctlAfter.perf) / 2,
      baselineDriftCdp: ctlAfter.cdp - ctlBefore.cdp,
    };
    control.errorCdp = control.measuredCdp / CONTROL_PREDICTED - 1;
    control.errorPerf = control.measuredPerf / CONTROL_PREDICTED - 1;

    // --- the arms, rotating AND REFLECTING with the round ----------------
    const byArm = {};
    for (const j of guard.schedule(arms.length, round)) {
      const a = arms[j];
      await gc();
      const pre = await read();
      const verify = await page.evaluate((id) => window.LADDER.mount(id), a.id);
      mounts++;
      if (!verify.ok) { unverified++; console.error(`[ladder] UNVERIFIED ${a.id}: ${verify.elements}/${verify.expected}`); }
      await gc();
      const held = await read();
      await page.evaluate(() => window.LADDER.release());
      await gc();
      const post = await read();
      byArm[a.id] = {
        verify,
        boundaries: a.boundaries,
        retainedCdp: held.cdp - pre.cdp,
        retainedPerf: held.perf - pre.perf,
        residueCdp: post.cdp - pre.cdp,
        bytesPerBoundaryCdp: (held.cdp - pre.cdp) / a.boundaries,
        raw: { pre, held, post },
      };
      orderSamples.push({
        arm: a.id,
        value: byArm[a.id].bytesPerBoundaryCdp,
        predecessor: previous,
        position: position++,
      });
      previous = a.id;
    }
    rounds.push({ round, order: round % 2 ? 'reversed' : 'forward', control, arms: byArm });
  }

  // --- the independent reader, as its own pass --------------------------
  // Curve B plus its floor only. C is slow (a full graph walk over a heap
  // that reaches ~12 MB above baseline at the 20-read rung) and its job is
  // to falsify the HEADLINE curve, which is the one that prices a read.
  let snapshots = null;
  if (WANT_SNAPSHOT) {
    snapshots = {};
    const want = arms.filter((a) => a.curve === 'b' || a.boundaries === plan.curveB.boundaries);
    try {
      for (const a of want) {
        console.error(`[ladder] ${substrate}: snapshot pass ${a.id}`);
        await gc();
        const pre = await snapshotTotal(cdp);
        const verify = await page.evaluate((id) => window.LADDER.mount(id), a.id);
        mounts++;
        if (!verify.ok) unverified++;
        await gc();
        const held = await snapshotTotal(cdp);
        await page.evaluate(() => window.LADDER.release());
        snapshots[a.id] = {
          verify,
          boundaries: a.boundaries,
          retained: held.totalSelfSize - pre.totalSelfSize,
          bytesPerBoundary: (held.totalSelfSize - pre.totalSelfSize) / a.boundaries,
          nodeDelta: held.nodeCount - pre.nodeCount,
        };
      }
      await gc();
      const cpre = await snapshotTotal(cdp);
      await page.evaluate((n) => window.LADDER.control(n), CONTROL_DOUBLES);
      await gc();
      const cheld = await snapshotTotal(cdp);
      await page.evaluate(() => window.LADDER.controlRelease());
      snapshots.control = {
        predictedBytes: CONTROL_PREDICTED,
        measured: cheld.totalSelfSize - cpre.totalSelfSize,
        error: (cheld.totalSelfSize - cpre.totalSelfSize) / CONTROL_PREDICTED - 1,
      };
    } catch (e) {
      snapshots = { error: String(e && e.message ? e.message : e) };
    }
  }

  await browser.close();
  return {
    substrate,
    plan,
    arms: arms.map((a) => a.id),
    verification: { mounts, unverified },
    perRound: rounds,
    orderVerdict: guard.verdict(orderSamples, { tolerance: TOLERANCE }),
    snapshots,
    curves: curves(arms, rounds),
  };
}

// ---------------------------------------------------------------------------
// The two curves, computed PER ROUND and only then summarised
// ---------------------------------------------------------------------------

const median = guard.median;
const rangeOf = (xs) => ({ n: xs.length, min: Math.min(...xs), p50: median(xs), max: Math.max(...xs) });

// Ordinary least squares. Returned with the residual sum so a curve that is
// not a line cannot be reported as one by accident.
function fit(points) {
  const n = points.length;
  const sx = points.reduce((t, p) => t + p.x, 0);
  const sy = points.reduce((t, p) => t + p.y, 0);
  const sxx = points.reduce((t, p) => t + p.x * p.x, 0);
  const sxy = points.reduce((t, p) => t + p.x * p.y, 0);
  const slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
  const intercept = (sy - slope * sx) / n;
  const ss = points.reduce((t, p) => t + Math.pow(p.y - (slope * p.x + intercept), 2), 0);
  const my = sy / n;
  const tot = points.reduce((t, p) => t + Math.pow(p.y - my, 2), 0);
  return { slope, intercept, r2: tot === 0 ? 1 : 1 - ss / tot, points };
}

function curves(arms, rounds) {
  const subst = arms.find((a) => a.kind !== 'floor').kind;
  const curveB = [];
  const curveA = [];
  const perRung = {};   // arm id -> [excess-bytes-per-boundary per round]
  const orders = { forward: { b: [], a: [] }, reversed: { b: [], a: [] } };

  for (const r of rounds) {
    const excess = (a) => {
      const f = floorFor(arms, a.boundaries);
      return r.arms[a.id].retainedCdp - r.arms[f.id].retainedCdp;
    };
    // Curve B — fixed boundaries, growing reads. y is PER BOUNDARY.
    const bArms = arms.filter((a) => a.kind === subst && a.curve === 'b')
      .sort((p, q) => p.reads - q.reads);
    const bPts = bArms.map((a) => ({ x: a.reads, y: excess(a) / a.boundaries, arm: a.id }));
    // Curve A — fixed reads, growing boundaries. y is TOTAL excess.
    const aArms = arms.filter((a) => a.kind === subst && (a.curve === 'a' || a.reads === 3))
      .sort((p, q) => p.boundaries - q.boundaries);
    const aPts = aArms.map((a) => ({ x: a.boundaries, y: excess(a), arm: a.id }));

    for (const p of bPts) {
      (perRung[p.arm] = perRung[p.arm] || []).push(p.y);
    }
    const bFit = fit(bPts);
    const aFit = fit(aPts);
    curveB.push({ round: r.round, order: r.order, ...bFit });
    curveA.push({ round: r.round, order: r.order, ...aFit });
    orders[r.order].b.push(bFit);
    orders[r.order].a.push(aFit);
  }

  const sum = (fits, key) => rangeOf(fits.map((f) => f[key]));
  return {
    curveB: {
      what: 'fixed boundaries x growing reads — SLOPE is bytes per READ, INTERCEPT is bytes per BOUNDARY',
      perRound: curveB,
      bytesPerRead: sum(curveB, 'slope'),
      bytesPerBoundaryIntercept: sum(curveB, 'intercept'),
      r2: sum(curveB, 'r2'),
      forward: { bytesPerRead: sum(orders.forward.b, 'slope'), intercept: sum(orders.forward.b, 'intercept') },
      reversed: { bytesPerRead: sum(orders.reversed.b, 'slope'), intercept: sum(orders.reversed.b, 'intercept') },
      rungs: Object.fromEntries(Object.entries(perRung).map(([k, v]) => [k, rangeOf(v)])),
    },
    curveA: {
      what: 'fixed reads x growing boundaries — SLOPE is bytes per BOUNDARY at 3 reads, INTERCEPT is the page constant',
      perRound: curveA,
      bytesPerBoundary: sum(curveA, 'slope'),
      pageConstant: sum(curveA, 'intercept'),
      r2: sum(curveA, 'r2'),
      forward: { bytesPerBoundary: sum(orders.forward.a, 'slope'), pageConstant: sum(orders.forward.a, 'intercept') },
      reversed: { bytesPerBoundary: sum(orders.reversed.a, 'slope'), pageConstant: sum(orders.reversed.a, 'intercept') },
    },
  };
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

const b = (v) => (Number.isFinite(v) ? Math.round(v).toLocaleString('en-US') : String(v));
const pct = (v) => (Number.isFinite(v) ? `${(v * 100).toFixed(3)}%` : String(v));

function report(all) {
  const L = [];
  L.push(';; ================================================================');
  L.push(';; READS-PER-BOUNDARY HEAP LADDER — rf2-2rtt6.5 (EP-0038 Wave 0)');
  L.push(';; retention instrument, :advanced, headless Chromium');
  L.push(';; ================================================================');
  for (const s of all) {
    const ctl = s.perRound.map((r) => r.control);
    L.push('');
    L.push(`;; ---- ${s.substrate.toUpperCase()} on re-frame2 subs -----------------------------`);
    L.push(`;; verification: ${s.verification.unverified} unverified of ${s.verification.mounts}`);
    L.push(`;; IN-SITU POSITIVE CONTROL  predicted ${b(CONTROL_PREDICTED)} B`);
    L.push(`;;   reader A  measured ${b(median(ctl.map((c) => c.measuredCdp)))} B  ` +
           `[${b(Math.min(...ctl.map((c) => c.measuredCdp)))}-${b(Math.max(...ctl.map((c) => c.measuredCdp)))}]  ` +
           `error ${pct(median(ctl.map((c) => c.errorCdp)))}`);
    L.push(`;;   reader B  measured ${b(median(ctl.map((c) => c.measuredPerf)))} B  ` +
           `error ${pct(median(ctl.map((c) => c.errorPerf)))}`);
    if (s.snapshots && s.snapshots.control) {
      L.push(`;;   reader C  measured ${b(s.snapshots.control.measured)} B  error ${pct(s.snapshots.control.error)}`);
    }
    L.push('');
    L.push(';;   CURVE B — fixed boundaries x growing reads');
    for (const [arm, r] of Object.entries(s.curves.curveB.rungs)) {
      const reads = parseArm(arm).reads;
      L.push(`;;     R=${String(reads).padStart(2)}  ${b(r.p50).padStart(7)} B/boundary  [${b(r.min)}-${b(r.max)}]`);
    }
    const cb = s.curves.curveB;
    L.push(`;;     => BYTES PER READ      ${b(cb.bytesPerRead.p50)}  [${b(cb.bytesPerRead.min)}-${b(cb.bytesPerRead.max)}]`);
    L.push(`;;        fwd ${b(cb.forward.bytesPerRead.p50)} [${b(cb.forward.bytesPerRead.min)}-${b(cb.forward.bytesPerRead.max)}]  ` +
           `rev ${b(cb.reversed.bytesPerRead.p50)} [${b(cb.reversed.bytesPerRead.min)}-${b(cb.reversed.bytesPerRead.max)}]`);
    L.push(`;;     => PER-BOUNDARY INTERCEPT ${b(cb.bytesPerBoundaryIntercept.p50)}  ` +
           `[${b(cb.bytesPerBoundaryIntercept.min)}-${b(cb.bytesPerBoundaryIntercept.max)}]`);
    L.push(`;;        r2 ${cb.r2.p50.toFixed(5)} [${cb.r2.min.toFixed(5)}-${cb.r2.max.toFixed(5)}]`);
    L.push('');
    L.push(';;   CURVE A — fixed reads (3) x growing boundaries');
    for (const p of s.curves.curveA.perRound[0].points) {
      L.push(`;;     B=${String(p.x).padStart(5)}  ${b(p.y).padStart(10)} B excess (round 0)`);
    }
    const ca = s.curves.curveA;
    L.push(`;;     => BYTES PER BOUNDARY @3 ${b(ca.bytesPerBoundary.p50)}  [${b(ca.bytesPerBoundary.min)}-${b(ca.bytesPerBoundary.max)}]`);
    L.push(`;;        fwd ${b(ca.forward.bytesPerBoundary.p50)} [${b(ca.forward.bytesPerBoundary.min)}-${b(ca.forward.bytesPerBoundary.max)}]  ` +
           `rev ${b(ca.reversed.bytesPerBoundary.p50)} [${b(ca.reversed.bytesPerBoundary.min)}-${b(ca.reversed.bytesPerBoundary.max)}]`);
    L.push(`;;     => PAGE CONSTANT ${b(ca.pageConstant.p50)}  [${b(ca.pageConstant.min)}-${b(ca.pageConstant.max)}]`);
    L.push(`;;        r2 ${ca.r2.p50.toFixed(5)} [${ca.r2.min.toFixed(5)}-${ca.r2.max.toFixed(5)}]`);
    L.push(`;;   ORDER GUARD: ${s.orderVerdict.refuse ? 'REFUSED' : 'clean'}` +
           `${s.orderVerdict.contaminated ? ' (contaminated)' : ''}${s.orderVerdict.unchecked ? ' (unchecked)' : ''}`);
  }
  if (all.length === 2) {
    const [x, y] = all;
    L.push('');
    L.push(';; ---- cross-substrate ---------------------------------------------');
    L.push(`;;   bytes per READ      ${x.substrate} ${b(x.curves.curveB.bytesPerRead.p50)}  ` +
           `${y.substrate} ${b(y.curves.curveB.bytesPerRead.p50)}  ` +
           `ratio ${(y.curves.curveB.bytesPerRead.p50 / x.curves.curveB.bytesPerRead.p50).toFixed(3)}`);
    L.push(`;;   per-boundary shell  ${x.substrate} ${b(x.curves.curveB.bytesPerBoundaryIntercept.p50)}  ` +
           `${y.substrate} ${b(y.curves.curveB.bytesPerBoundaryIntercept.p50)}`);
  }
  return L.join('\n');
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

(async () => {
  // The guard's own self-test runs BEFORE the bundle is even built: an
  // instrument whose refusal machinery is broken must not produce a table.
  const st = guard.selfTest();
  if (!st.ok) {
    console.error('[ladder] order-guard self-test FAILED — refusing to measure');
    for (const c of st.checks) if (!c.ok) console.error(`  FAIL ${c.name} — ${c.detail || ''}`);
    process.exit(1);
  }
  console.error(`[ladder] order-guard self-test ok (${st.checks.length} checks)`);

  fs.mkdirSync(OUT, { recursive: true });
  build();
  const server = serve();
  const { chromium } = require('playwright');

  const all = [];
  try {
    for (const s of SUBSTRATES) all.push(await runSubstrate(chromium, s));
  } finally {
    server.close();
  }

  const result = {
    benchmark: 'hicasso:reads-per-boundary-heap-ladder',
    bead: 'rf2-2rtt6.5',
    epic: 'rf2-2rtt6',
    rounds: ROUNDS,
    tolerance: TOLERANCE,
    instruments: {
      A: 'CDP Runtime.getHeapUsage().usedSize after 3x HeapProfiler.collectGarbage',
      B: 'in-page performance.memory.usedJSHeapSize, same moment, --enable-precise-memory-info',
      C: 'full heap snapshot, every node self_size summed by a streaming scan (own pass, Curve B only)',
      note: 'A and B are two doors onto one V8 counter and are NOT independent. C walks the object graph.',
    },
    control: { shape: 'dense JS array of doubles', doubles: CONTROL_DOUBLES, predictedBytes: CONTROL_PREDICTED },
    substrates: all,
  };

  fs.writeFileSync(path.join(OUT, 'reads-ladder.json'), JSON.stringify(result, null, 2));
  const text = report(all);
  console.log(text);
  fs.writeFileSync(path.join(OUT, 'reads-ladder.txt'), text + '\n');
  console.error(`[ladder] wrote ${path.join(OUT, 'reads-ladder.json')}`);

  const refused = all.filter((s) => s.orderVerdict.refuse);
  const unverified = all.reduce((t, s) => t + s.verification.unverified, 0);
  if (unverified > 0) {
    console.error(`[ladder] ${unverified} UNVERIFIED mounts — the DOM read-back did not match`);
    process.exit(3);
  }
  if (refused.length) {
    for (const s of refused) {
      for (const line of guard.format(s.orderVerdict, `${s.substrate}: arm order`)) console.error(line);
    }
    console.error('[ladder] ORDER GUARD REFUSED — repair the arm, not the guard');
    process.exit(2);
  }
  console.error('[ladder] done');
})().catch((e) => { console.error(e); process.exit(1); });
