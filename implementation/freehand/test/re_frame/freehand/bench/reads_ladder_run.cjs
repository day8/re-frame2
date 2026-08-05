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
//
// ## What the audit of PR #7260 corrected
//
//   1. THE FIT USED THE ANCHOR. `R=0` is a boundary that reads nothing; the
//      bead, this header and the studio page all called it "an anchor, not
//      evidence" and promised no reactive figure was derived from it — and
//      `curves()` then fitted `[0, 1, 3, 7, 20]`. The fit is now over the
//      mandated 1/3/7/20 alone. R=0 is reported directly beside it and never
//      enters a regression, and the FITTED MARGINAL SLOPE is distinguished
//      from the FIRST-READ INCREMENT `y(R=1) − y(R=0)`, which is the figure
//      a reader means by "what the first read costs".
//   2. READER C FAILED OPEN. A snapshot failure was caught into
//      `{error: ...}` and the run still exited 0, so a reproduction could
//      degrade to the two CORRELATED readers and print a table anyway.
//
// EXIT CODES
//   0  reportable
//   1  a gate failed, or the run could not complete
//   2  THE ARM-ORDER GUARD REFUSED. Repair the arm, never the tolerance.
//   3  a mount did not verify at the DOM
//   4  reader C was requested and is incomplete — see (2) above

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const guard = require('./order_guard.cjs');
const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');
const { watchPage } = require('./sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
// The donor build id, hoisted out of the `spawnSync` argv it used to be a
// literal in, so the cache clear and the build cannot name different ids
// (rf2-t4j7c).
const BUILD = 'freehand-release';
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
// How far the in-situ positive control's measured range may sit from the
// prediction its own arithmetic made and still count as THE INSTRUMENT HAS
// SIGNAL.
//
// A SEPARATE KNOB FROM `TOLERANCE` ABOVE, which it happens to equal. That is
// a coincidence and not a relationship: `TOLERANCE` is a relative difference
// of MEDIANS between strata of one arm, adjudicated by the arm-order guard;
// this is how far a control may sit from a PREDICTION. `p0_run.cjs` carries
// the same pair under these same two names for the same reason. Generous on
// purpose — the claim being gated is not that the model is exact, it is that
// the instrument can see a change it predicts; the predecessor this control
// exists to answer reported ~4.7 MB as 0.00 MB, which is orders of magnitude
// and not percent. `spine_ablation_run.cjs`'s 1% band is NOT the precedent:
// it is justified there by that ladder's own 0.13% worst reading.
const CONTROL_SLACK = Number(process.env.LADDER_CONTROL_SLACK || 0.25);
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
  // The lane's cache rule, before anything reads the cache: this driver merges
  // its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry was written by a
  // different program. `lane_cache.cjs` carries the measured fault and the
  // rejected alternatives (rf2-2rtt6.20). MEASURED HERE (rf2-t4j7c): a b7
  // build then this one over its cache compiled 11 of 160 files and exited 0,
  // against 105 of 160 from cold, and the bundle died on load with `Cannot
  // read properties of undefined (reading 'd')`.
  if (resetLaneBuildCache(IMPL, BUILD)) {
    console.error(`[ladder] cleared .shadow-cljs/builds/${BUILD} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error('[ladder] building :advanced bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE],
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

// One entry per page this run opened — one per substrate. Flattened once, at
// the exit.
const PAGE_WATCHES = [];
const pageFailures = () =>
  PAGE_WATCHES.flatMap((w) => w.failures).map((f) => `${f.kind}: ${f.detail}`);

async function newPage(chromium, query, budget) {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  // EVERY PAGE THIS RUN OPENS IS WATCHED, AND EVERY WATCH IS READ AT THE EXIT
  // (rf2-sib23). This was a bare `page.on('pageerror', ...)` that printed and
  // recorded nothing, so a throw was announced on stderr and `drive` below
  // exited 0 underneath it. One page is opened per substrate, which is why
  // the watches are collected rather than held in a local: a `pageerror` on
  // ANY substrate has to reach the one exit. `sentinel.cjs`'s header carries
  // the finding, including why no page-side `try`/`catch` can close it under
  // React 19.2. The watch is also RETURNED, because the caller races its own
  // sentinel against it (rf2-qv761).
  const watch = watchPage(page, 'ladder');
  PAGE_WATCHES.push(watch);
  // `'commit'`, not `'load'` (rf2-p9fa3): the bundle's `:init-fn` runs
  // synchronously inside the `<script>`, so the load event is downstream of
  // work this driver budgets separately.
  await navigate(page, `http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget,
  });
  return { browser, page, watch };
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
  const { browser, page, watch } = await newPage(
    chromium, `?adapter=${substrate}`, `the 120s wait for window.LADDER_READY (${substrate})`
  );
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`. `race`
  // rejects only on a failure `watch` recorded, and `drive`'s exit already
  // refuses on exactly those failures, so no run that would have passed is
  // shortened. The rejection propagates out of `drive` into its existing
  // rejection handler, which is this driver's exit 1 — the "not in a state to
  // measure" family. The enumerated 2/3/4/5 are untouched.
  await watch.race('window.LADDER_READY === true || window.LADDER_ERROR', {
    timeoutMs: 120000,
    budget: `the 120s wait for window.LADDER_READY (${substrate})`,
  });
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
  let snapshotFailure = null;
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

    // FAIL CLOSED WHEN READER C WAS ASKED FOR AND DID NOT ARRIVE.
    //
    // A and B are two doors onto one V8 counter. C is the reader that walks
    // the object graph, and it is the whole reason this table is falsifiable
    // rather than self-reported — the object-count conclusion (128.5 UIx
    // objects per read against 36.2 Reagent) exists nowhere else. The first
    // cut caught any snapshot failure into `{error: ...}` and the run went on
    // to exit 0, so a reproduction could silently degrade to the two
    // CORRELATED readers and still print a table.
    //
    // The error is recorded rather than thrown, so the A/B evidence and the
    // control still reach the artefact — and then the run refuses.
    const missing = [];
    if (snapshots.error) {
      missing.push(`snapshot pass failed: ${snapshots.error}`);
    } else {
      if (!snapshots.control) missing.push('reader-C positive control absent');
      for (const a of want) if (!snapshots[a.id]) missing.push(`reader-C rung ${a.id} absent`);
    }
    if (missing.length) {
      snapshotFailure = missing;
      for (const m of missing) console.error(`[ladder] ${substrate}: READER C — ${m}`);
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
    // The verdict is carried BESIDE this substrate's own control readings,
    // so the artefact says what was decided and not only what was measured.
    controlSlack: CONTROL_SLACK,
    controlVerdict: controlVerdicts(rounds, snapshots, CONTROL_PREDICTED, CONTROL_SLACK),
    snapshots,
    snapshotFailure,
    curves: curves(arms, rounds),
  };
}

/**
 * `lane/control-verdict`'s rule over ONE reader's control readings: does the
 * measured range meet ±`slack` of the prediction anywhere?
 *
 * FINITENESS IS TESTED FIRST, and separately. `spine_ablation_run.cjs`
 * records why: a band test on its own is a bypass for a control that came
 * back `NaN`, because every comparison against `NaN` is false. A reader that
 * produced no finite number has not controlled anything, and a reader that
 * took no readings at all has not either — both must fail rather than sail
 * through an untaken comparison.
 */
function controlVerdict(reader, predicted, xs, slack) {
  const lo = predicted * (1 - slack);
  const hi = predicted * (1 + slack);
  const finite = xs.length > 0 && xs.every((x) => Number.isFinite(x));
  const measured = finite
    ? {
        mean: xs.reduce((a, x) => a + x, 0) / xs.length,
        min: Math.min(...xs),
        max: Math.max(...xs),
      }
    : { mean: NaN, min: NaN, max: NaN };
  const ok = finite && measured.min <= hi && measured.max >= lo;
  const n = (x) => (Number.isFinite(x) ? Math.round(x).toLocaleString('en-US') : String(x));
  return {
    reader,
    predicted,
    band: [lo, hi],
    measured,
    slack,
    ok,
    why: !finite
      ? `${reader}: the control produced no finite reading (${xs.length} taken) — it has ` +
        'controlled nothing'
      : ok
        ? `${reader}: predicted ${n(predicted)} B, measured ${n(measured.mean)} B ` +
          `[${n(measured.min)}–${n(measured.max)}] — meets the prediction within ` +
          `±${(slack * 100).toFixed(0)}%`
        : `${reader}: predicted ${n(predicted)} B, measured ${n(measured.mean)} B ` +
          `[${n(measured.min)}–${n(measured.max)}] — DISJOINT from ±${(slack * 100).toFixed(0)}% ` +
          'of the prediction; the instrument did not see a change its own arithmetic says it ' +
          'must, so no figure from this substrate is reportable',
  };
}

/** One verdict per reader that actually took a control reading. */
function controlVerdicts(rounds, snapshots, predicted, slack) {
  const v = {
    A: controlVerdict('reader A', predicted, rounds.map((r) => r.control.measuredCdp), slack),
    B: controlVerdict('reader B', predicted, rounds.map((r) => r.control.measuredPerf), slack),
  };
  // Reader C exists only when the snapshot pass ran and reached its control.
  if (snapshots && snapshots.control) {
    v.C = controlVerdict('reader C', predicted, [snapshots.control.measured], slack);
  }
  return v;
}

/**
 * The readers whose control did not hold. Empty means every one held — and
 * an ABSENT verdict block is itself a failure, not a pass: this gate must
 * not be defeated by the thing it reads going missing.
 */
function controlFailures(verdicts) {
  const vs = Object.values(verdicts || {});
  if (!vs.length) return ['no positive control was adjudicated at all'];
  return vs.filter((v) => !v.ok).map((v) => v.why);
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

// THE REACTIVE RUNGS, and the one that is not one.
//
// `R=0` is a boundary that reads NOTHING. The bead, this instrument's
// docstring and the studio page all say the same thing about it — "anchor,
// not evidence", "no reactive figure is derived from it" — and the first cut
// then fitted `[0, 1, 3, 7, 20]`, which derived every reactive figure from
// it. The audit of PR #7260 caught it arithmetically: refitting the
// published medians over 1/3/7/20 alone gives 943 B/read + 397 B on Reagent
// and 3,552 B/read + 118 B on UIx, against the shipped 942/406 and
// 3,550/150. The differences are small and that is not the point — the
// figures were reached by a route the page promised it had not taken.
//
// So the fit is over the MANDATED rungs only. R=0 is carried, reported and
// compared, and never enters a regression.
const REACTIVE = (p) => p.x > 0;

function curves(arms, rounds) {
  const subst = arms.find((a) => a.kind !== 'floor').kind;
  const curveB = [];
  const curveA = [];
  const perRung = {};   // arm id -> [excess-bytes-per-boundary per round]
  const anchorPerRound = [];      // R=0, measured directly, never fitted
  const firstReadPerRound = [];   // y(R=1) - y(R=0), an INCREMENT, not a slope
  const orders = { forward: { b: [], a: [] }, reversed: { b: [], a: [] } };

  for (const r of rounds) {
    const excess = (a) => {
      const f = floorFor(arms, a.boundaries);
      return r.arms[a.id].retainedCdp - r.arms[f.id].retainedCdp;
    };
    // Curve B — fixed boundaries, growing reads. y is PER BOUNDARY.
    const bArms = arms.filter((a) => a.kind === subst && a.curve === 'b')
      .sort((p, q) => p.reads - q.reads);
    const bPtsAll = bArms.map((a) => ({ x: a.reads, y: excess(a) / a.boundaries, arm: a.id }));
    const bPts = bPtsAll.filter(REACTIVE);
    const anchorPt = bPtsAll.find((p) => p.x === 0) || null;
    const firstPt = bPtsAll.find((p) => p.x === 1) || null;
    // Curve A — fixed reads, growing boundaries. y is TOTAL excess.
    const aArms = arms.filter((a) => a.kind === subst && (a.curve === 'a' || a.reads === 3))
      .sort((p, q) => p.boundaries - q.boundaries);
    const aPts = aArms.map((a) => ({ x: a.boundaries, y: excess(a), arm: a.id }));

    // Every rung is REPORTED, including the anchor — it is the comparison
    // with the published sub-free rows that lets a reader decide whether to
    // believe this instrument at all. Only the fit excludes it.
    for (const p of bPtsAll) {
      (perRung[p.arm] = perRung[p.arm] || []).push(p.y);
    }
    if (anchorPt) anchorPerRound.push(anchorPt.y);
    // THE FIRST-READ INCREMENT is not the fitted marginal slope, and calling
    // the slope "the first read" was the other half of the same confusion.
    // The first read buys the subscription AND whatever the shell has to
    // grow to hold one; the marginal slope prices the second read onward.
    if (anchorPt && firstPt) firstReadPerRound.push(firstPt.y - anchorPt.y);
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
      what: 'fixed boundaries x growing READS over 1/3/7/20 ONLY — SLOPE is bytes per READ, ' +
            'INTERCEPT is the FITTED per-boundary shell. R=0 is an anchor and is excluded from the fit.',
      fittedRungs: (curveB[0] ? curveB[0].points.map((p) => p.x) : []),
      perRound: curveB,
      bytesPerRead: sum(curveB, 'slope'),
      bytesPerBoundaryIntercept: sum(curveB, 'intercept'),
      r2: sum(curveB, 'r2'),
      // Measured directly at R=0. NOT part of any regression above.
      anchorMeasured: anchorPerRound.length ? rangeOf(anchorPerRound) : null,
      // y(R=1) - y(R=0). An increment between two measured rungs, and the
      // figure a reader means by "what does the first read cost".
      firstReadIncrement: firstReadPerRound.length ? rangeOf(firstReadPerRound) : null,
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
    // Published on every run, passing or not: a control quoted only when it
    // passes is not a control.
    for (const v of Object.values(s.controlVerdict || {})) {
      L.push(`;;   VERDICT ${v.ok ? 'ok  ' : 'FAIL'} ${v.why}`);
    }
    L.push('');
    const cb = s.curves.curveB;
    L.push(`;;   CURVE B — fixed boundaries x growing reads; FITTED OVER R=${cb.fittedRungs.join('/')} ONLY`);
    for (const [arm, r] of Object.entries(s.curves.curveB.rungs)) {
      const reads = parseArm(arm).reads;
      const tag = reads === 0 ? '   <- ANCHOR, excluded from the fit' : '';
      L.push(`;;     R=${String(reads).padStart(2)}  ${b(r.p50).padStart(7)} B/boundary  [${b(r.min)}-${b(r.max)}]${tag}`);
    }
    L.push(`;;     => BYTES PER READ      ${b(cb.bytesPerRead.p50)}  [${b(cb.bytesPerRead.min)}-${b(cb.bytesPerRead.max)}]`);
    L.push(`;;        the fitted MARGINAL slope over ${cb.fittedRungs.join('/')} — what one more read costs.`);
    L.push(`;;        fwd ${b(cb.forward.bytesPerRead.p50)} [${b(cb.forward.bytesPerRead.min)}-${b(cb.forward.bytesPerRead.max)}]  ` +
           `rev ${b(cb.reversed.bytesPerRead.p50)} [${b(cb.reversed.bytesPerRead.min)}-${b(cb.reversed.bytesPerRead.max)}]`);
    if (cb.firstReadIncrement) {
      L.push(`;;     => FIRST-READ INCREMENT ${b(cb.firstReadIncrement.p50)}  ` +
             `[${b(cb.firstReadIncrement.min)}-${b(cb.firstReadIncrement.max)}]`);
      L.push(`;;        y(R=1) - y(R=0), two MEASURED rungs. This is NOT the slope above, and`);
      L.push(`;;        calling the slope "the first read" conflates them.`);
    }
    L.push(`;;     => PER-BOUNDARY INTERCEPT ${b(cb.bytesPerBoundaryIntercept.p50)}  ` +
           `[${b(cb.bytesPerBoundaryIntercept.min)}-${b(cb.bytesPerBoundaryIntercept.max)}]  (FITTED, extrapolated to R=0)`);
    if (cb.anchorMeasured) {
      L.push(`;;     => R=0 MEASURED DIRECTLY  ${b(cb.anchorMeasured.p50)}  ` +
             `[${b(cb.anchorMeasured.min)}-${b(cb.anchorMeasured.max)}]  (the anchor; nothing is derived from it)`);
    }
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

async function drive() {
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

  // THE PAGES' OWN FAILURES, READ FIRST (rf2-sib23). A benchmark that threw
  // and kept going publishes a precise number for a page that is not the page
  // under test, so this outranks every band and count below it: those gates
  // adjudicate readings, and this one says the readings are not of the thing.
  // Exit 1 — the code this driver already uses for "the instrument is not in
  // a state to measure", and deliberately NOT a new number among the
  // enumerated refusals, since a page that threw is not a refusal about what
  // may be quoted. The artefacts are written above, so the partial evidence
  // survives.
  const pageErrors = pageFailures();
  if (pageErrors.length) {
    for (const e of pageErrors) console.error(`[ladder] PAGE ERROR — ${e}`);
    console.error(
      '[ladder] THE PAGE THREW AND KEPT GOING — every figure in the table above was taken ' +
        'after an uncaught error, so none of it is a reading of the substrate it names. This ' +
        'is not closeable inside the app: React does not rethrow an uncaught render error to ' +
        'the caller of flushSync (see sentinel.cjs). Re-run on a clean page.'
    );
    process.exit(1);
  }

  const refused = all.filter((s) => s.orderVerdict.refuse);
  const unverified = all.reduce((t, s) => t + s.verification.unverified, 0);
  if (unverified > 0) {
    console.error(`[ladder] ${unverified} UNVERIFIED mounts — the DOM read-back did not match`);
    process.exit(3);
  }
  // Reader C was requested and did not arrive. A and B are two doors onto one
  // V8 counter; without C the object-count conclusion has no source and the
  // table is self-reported. The artefact above is written first, so the
  // partial evidence survives the refusal.
  const cFailed = all.filter((s) => s.snapshotFailure);
  if (cFailed.length) {
    for (const s of cFailed) {
      for (const m of s.snapshotFailure) console.error(`[ladder] ${s.substrate}: READER C — ${m}`);
    }
    console.error(
      '[ladder] READER C WAS REQUESTED AND IS INCOMPLETE — this table may not be published on the ' +
        'two correlated readers alone. Re-run, or set LADDER_SNAPSHOT=0 and publish nothing that ' +
        'depends on the object counts.'
    );
    process.exit(4);
  }
  if (refused.length) {
    for (const s of refused) {
      for (const line of guard.format(s.orderVerdict, `${s.substrate}: arm order`)) console.error(line);
    }
    console.error('[ladder] ORDER GUARD REFUSED — repair the arm, not the guard');
    process.exit(2);
  }
  // THE CONTROL IS ADJUDICATED, not merely printed. For this instrument's
  // whole life it was printed and nothing else — predicted beside measured
  // beside an error percentage, with no pass/fail computed at all
  // (rf2-bml5u) — so a control that had stopped seeing the change its own
  // arithmetic predicts could not stop the run. A control printed without a
  // verdict is a number beside a number.
  //
  // Exit 5, appended to the enumerated codes above rather than inserted
  // among them: a run that previously refused with 2, 3 or 4 still refuses
  // with the same code. The report and both artefacts are written well
  // above, so the evidence survives the refusal.
  const ctlFailed = all.flatMap((s) =>
    controlFailures(s.controlVerdict).map((why) => `${s.substrate}: ${why}`)
  );
  if (ctlFailed.length) {
    for (const why of ctlFailed) console.error(`[ladder] POSITIVE CONTROL — ${why}`);
    console.error(
      '[ladder] THE IN-SITU POSITIVE CONTROL DID NOT HOLD — an instrument that cannot see the ' +
        'change its own arithmetic predicts has not measured the ones it does not predict either'
    );
    process.exit(5);
  }
  console.error('[ladder] done');
}

// The decision functions are exported so `heap_control_exit_path.test.cjs`
// can exercise the control gate directly: this driver needs an `:advanced`
// release build and a headless Chromium, so its verdicts cannot be reached
// end-to-end from a unit test. Requiring this file must therefore NOT drive
// it, which is what the `require.main` guard below is for.
module.exports = { controlVerdict, controlVerdicts, controlFailures };

if (require.main === module) {
  drive().catch((e) => { console.error(e); process.exit(1); });
}
