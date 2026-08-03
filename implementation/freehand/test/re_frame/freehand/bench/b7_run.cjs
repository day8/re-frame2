#!/usr/bin/env node
// B7's driver — build the `:advanced` bundle once, then run both rows.
//
//   node implementation/freehand/test/re_frame/freehand/bench/b7_run.cjs
//   node .../b7_run.cjs --only heap
//   node .../b7_run.cjs --only mount-frame
//   B7_ROUNDS=6 B7_ROOTS=10 B7_SNAPSHOT=1 node .../b7_run.cjs
//
// Two rows, one bundle:
//
//   `mount-frame` (rf2-prjh0) runs entirely in the page and prints the
//   published EDN records.
//
//   `heap`       (rf2-9oj7v) runs HERE, because the page cannot force a
//                garbage collection and therefore cannot decide when a
//                retained-heap reading is taken. The page only mounts and
//                holds; this driver collects and reads.
//
// THE INSTRUMENT WARNING, which is the reason this file exists at all.
// V8's CDP SAMPLING heap profiler drops the samples of collected objects,
// so pointed at a mount/unmount loop it reports the residue of a page that
// has already been discarded — the same 80,000 objects read 4.77 MB when a
// global held them and 0.00 MB when nothing did. Nothing here samples
// allocation. Every figure is a RETENTION reading: mount K roots, keep
// them, collect, read; release, collect, read again.
//
// Three readers, and an honest account of how independent they are:
//
//   A  CDP `Runtime.getHeapUsage().usedSize`, after a forced collection.
//   B  in-page `performance.memory.usedJSHeapSize`, same moment, under
//      `--enable-precise-memory-info`.
//   C  a full heap SNAPSHOT, with every node's `self_size` summed by the
//      streaming scanner below.
//
// **A and B are NOT independent of each other**, and this file says so
// rather than banking two agreeing numbers as corroboration: pointed at
// 80,000 ordinary held objects they returned the identical figure to the
// byte (3,868,954 both), because they are two doors onto one V8 counter.
// C is the reader that is genuinely independent — it walks the object
// graph and sums what is actually there. It is slow, so it runs as its
// own pass rather than in every round.
//
// And a POSITIVE CONTROL of PREDICTED size rides every round: a dense
// array of N doubles, which V8 stores as N unboxed 8-byte slots, so the
// retained cost is 8N bytes before anything is measured. Every round
// reports predicted against measured on every reader. A number nobody
// can falsify is not a measurement.
//
// The control's own first draft was a 4.7 MB one-byte string, and it
// read as SIX KILOBYTES on all three readers — V8 does not materialise
// `'x'.repeat(n)`. The instrument was fine and the control was a
// fiction. It is worth knowing that a control can fail this way.
//
// THE ARM ORDER IS A MEASURED PROPERTY OF THIS RUN, NOT AN ASSUMPTION.
// The heap row used to select the arm for slot `j` of round `r` as
// `HEAP_ARMS[(j + round) % n]` and describe it as "order rotating with the
// round". A cyclic rotation changes which arm goes FIRST; it does not
// change which arm follows which — arm `a` sits at slot `(a - r) mod n`,
// so its predecessor is `(a - 1) mod n` in EVERY round and only the round
// seam differs. It read as a mitigation and was not one (rf2-88pie,
// rf2-om73r). `order_guard.cjs` now schedules the arms so every one of
// them is measured after at least two different predecessors, labels every
// reading with what preceded it and where in the run it sat, and REFUSES to
// let this driver exit 0 on a figure that either factor separates. Its
// self-test runs before the bundle is even built.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const guard = require('./order_guard.cjs');
const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const OUT = path.join(IMPL, process.env.B7_OUT_DIR || path.join('out', 'b7'));
const PORT = Number(process.env.B7_PORT || 8131);

const ROUNDS = Number(process.env.B7_ROUNDS || 6);
const ROOTS = Number(process.env.B7_ROOTS || 10);
// 587,500 unboxed doubles = 4,700,000 bytes — the same ~4.7 MB the
// predecessor report's failed sampler reported as 0.00 MB.
const CONTROL_DOUBLES = Number(process.env.B7_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
const WANT_SNAPSHOT = process.env.B7_SNAPSHOT !== '0';
// A relative difference of medians. A retention reading taken across a
// mount/collect/release cycle moves several percent between rounds where an
// allocation counter reading the same code twice does not, so this sits
// above B8's noise floor and far below the 2.01x the recorded fault made.
const TOLERANCE = Number(process.env.B7_TOLERANCE || 0.25);
// How far the positive control's measured range may sit from the prediction
// its own arithmetic made and still count as THE INSTRUMENT HAS SIGNAL.
//
// A SEPARATE KNOB FROM `TOLERANCE` ABOVE, which it happens to equal. That is
// a coincidence and not a relationship: `TOLERANCE` is a relative difference
// of MEDIANS between strata of one arm, adjudicated by the arm-order guard;
// this is how far a control may sit from a PREDICTION. `p0_run.cjs` carries
// the same pair under these same two names for the same reason. Generous on
// purpose — the claim being gated is not that the model is exact, it is that
// the instrument can see a change it predicts. B7's predecessor reported
// ~4.7 MB of held doubles as 0.00 MB, which is orders of magnitude and not
// percent. `spine_ablation_run.cjs`'s 1% band is NOT the precedent: it is
// justified there by that ladder's own 0.13% worst reading.
const CONTROL_SLACK = Number(process.env.B7_CONTROL_SLACK || 0.25);

// The published arm set. `B7_ARMS` (comma-separated) overrides it, and
// `B7_INIT_FN` points the bundle at a different `:init-fn` — the two seams
// an ABLATION LADDER needs in order to ride this collector rather than
// grow a second one. Neither has a default that changes anything: with
// both unset this file is byte-for-byte the published instrument.
const HEAP_ARMS = (process.env.B7_ARMS || [
  'storm/floor',
  'storm/freehand',
  'storm/reagent',
  'storm/uix',
  'reactive/floor',
  'reactive/freehand',
  'reactive/reagent',
].join(',')).split(',').map((s) => s.trim()).filter(Boolean);

const INIT_FN = process.env.B7_INIT_FN || 're-frame.freehand.bench.b7-app/-main';
const OUT_DIR = process.env.B7_OUT_DIR || 'out/b7';

const ONLY = (() => {
  const i = process.argv.indexOf('--only');
  return i === -1 ? null : process.argv[i + 1];
})();

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
  console.error('[b7] building :advanced bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[b7] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>B7</title></head>' +
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

// `budget` names the sentinel wait each caller is about to make, so a
// navigation failure cannot be read as that wait. The two modes are bounded
// very differently — 120s for the heap instrument, fifteen minutes for the
// mount-frame run — which is the second reason the ceiling has to say which
// one it is NOT.
async function newPage(chromium, query, budget) {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; B7')) console.log(t);
  });
  page.on('pageerror', (e) => console.error('[b7] page error:', e.message));
  // `'commit'`, not `'load'` (rf2-p9fa3), and this is the SHARPEST instance of
  // the class in the repo. `b7-app/-main` is the bundle's `:init-fn`, and in
  // `?mode=mount-frame` it runs `run-mount-frame!` — the parity pass plus six
  // rounds over every witness — SYNCHRONOUSLY, inside the `<script>`. The
  // `load` event is therefore downstream of a run this file gives FIFTEEN
  // MINUTES, and `page.goto` was silently capping it at thirty seconds.
  // Demonstrated against exactly this shape: a page that burns 35s during
  // load dies at 30007ms bare, and resolves at 35020ms on `'commit'` plus the
  // poll that already owned the wait.
  await navigate(page, `http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget,
  });
  return { browser, page };
}

// ---------------------------------------------------------------------------
// The collector and the readers
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
    const perf = await page.evaluate(() => window.B7H.perfMem());
    return { cdp: usedSize, perf };
  };

  return { cdp, gc, read };
}

// Sum every node's `self_size` out of a streaming heap snapshot.
//
// The snapshot arrives as JSON text in chunks and can be hundreds of
// megabytes, so it is never assembled or `JSON.parse`d. The `nodes` array
// is a flat run of integers, `node_fields` names the stride, and the scan
// below consumes complete integers per chunk and carries the partial one.
// This is the only reader here that walks the object graph, which is what
// makes it a check on the other two rather than a restatement of them.
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
        if (!fm) {
          phase = 2;
          reject(new Error('node_fields absent from snapshot header'));
          return;
        }
        const fields = fm[1].split(',').map((s) => s.trim().replace(/^"|"$/g, ''));
        nFields = fields.length;
        selfIdx = fields.indexOf('self_size');
        if (selfIdx < 0) {
          phase = 2;
          reject(new Error('self_size absent from node_fields'));
          return;
        }
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
          if (c === 93) {
            done = true;
            break;
          }
        }
      }
      buf = done ? '' : buf.slice(last);
      if (done) phase = 2;
    };

    cdp.on('HeapProfiler.addHeapSnapshotChunk', onChunk);
    cdp
      .send('HeapProfiler.takeHeapSnapshot', { reportProgress: false })
      .then(() => {
        cdp.off('HeapProfiler.addHeapSnapshotChunk', onChunk);
        resolve({ totalSelfSize: total, nodeCount });
      })
      .catch((e) => {
        cdp.off('HeapProfiler.addHeapSnapshotChunk', onChunk);
        reject(e);
      });
  });
}

// ---------------------------------------------------------------------------
// The heap row
// ---------------------------------------------------------------------------

async function heapRow(chromium) {
  const { browser, page } = await newPage(
    chromium, '?mode=heap', 'the 120s wait for `window.B7_READY`'
  );
  await page.waitForFunction('window.B7_READY === true || window.B7_ERROR', null, { timeout: 120000 });
  const err = await page.evaluate('window.B7_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`heap page failed to initialise: ${err}`);
  }
  const perRoot = await page.evaluate(() => window.B7H.perRoot);
  const boundaries = ROOTS * perRoot;
  const { cdp, gc, read } = await makeReaders(page);

  const rounds = [];
  const orderSamples = [];
  let position = 0;
  let previous = null;
  let unverified = 0;
  let mounts = 0;

  // A WARM-UP PASS, mounted and released once per arm and never read.
  // The first mount of any arm allocates things that are not the page and
  // never go away: compiled code for the paths it just took, inline
  // caches, interned keywords, one-time module state. Charged to round 1
  // they read as retention per boundary, and they are not — a smoke run
  // put 400–700 KB of unreleasable residue on every arm's first mount and
  // essentially none on its later ones.
  console.error('[b7] heap warm-up pass ...');
  for (const arm of HEAP_ARMS) {
    const v = await page.evaluate(([a, k]) => window.B7H.mount(a, k), [arm, ROOTS]);
    mounts++;
    if (!v.ok) unverified++;
    await page.evaluate(() => window.B7H.release());
  }
  await page.evaluate((n) => window.B7H.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.B7H.controlRelease());

  for (let round = 0; round < ROUNDS; round++) {
    console.error(`[b7] heap round ${round + 1}/${ROUNDS}`);

    // --- the positive control, in situ, before this round's arms ---------
    await gc();
    const ctlBefore = await read();
    const ctlLen = await page.evaluate((n) => window.B7H.control(n), CONTROL_DOUBLES);
    await gc();
    const ctlHeld = await read();
    await page.evaluate(() => window.B7H.controlRelease());
    await gc();
    const ctlAfter = await read();
    const control = {
      doubles: ctlLen,
      predictedBytes: CONTROL_PREDICTED,
      measuredCdp: ctlHeld.cdp - (ctlBefore.cdp + ctlAfter.cdp) / 2,
      measuredPerf: ctlHeld.perf - (ctlBefore.perf + ctlAfter.perf) / 2,
      baselineDriftCdp: ctlAfter.cdp - ctlBefore.cdp,
    };

    // --- the arms, order rotating AND REFLECTING with the round ----------
    // `guard.schedule` reflects on odd rounds, which is what actually
    // varies which arm follows which; the bare rotation this loop used to
    // do did not. Every reading carries the label of the arm measured
    // immediately before it and its position in the whole run.
    const arms = {};
    for (const j of guard.schedule(HEAP_ARMS.length, round)) {
      const arm = HEAP_ARMS[j];
      await gc();
      const pre = await read();
      const verify = await page.evaluate(
        ([a, k]) => window.B7H.mount(a, k),
        [arm, ROOTS]
      );
      mounts++;
      if (!verify.ok) unverified++;
      await gc();
      const held = await read();
      await page.evaluate(() => window.B7H.release());
      await gc();
      const post = await read();
      arms[arm] = {
        verify,
        retainedCdp: held.cdp - pre.cdp,
        retainedPerf: held.perf - pre.perf,
        residueCdp: post.cdp - pre.cdp,
        bytesPerBoundaryCdp: (held.cdp - pre.cdp) / boundaries,
        bytesPerBoundaryPerf: (held.perf - pre.perf) / boundaries,
        raw: { pre, held, post },
      };
      orderSamples.push({
        arm,
        value: arms[arm].bytesPerBoundaryCdp,
        predecessor: previous,
        position: position++,
      });
      previous = arm;
    }
    rounds.push({ round, control, arms });
  }

  // --- the independent reader, as its own pass ---------------------------
  let snapshots = null;
  if (WANT_SNAPSHOT) {
    snapshots = {};
    try {
      for (const arm of HEAP_ARMS) {
        console.error(`[b7] snapshot pass: ${arm}`);
        await gc();
        const pre = await snapshotTotal(cdp);
        const verify = await page.evaluate(([a, k]) => window.B7H.mount(a, k), [arm, ROOTS]);
        mounts++;
        if (!verify.ok) unverified++;
        await gc();
        const held = await snapshotTotal(cdp);
        await page.evaluate(() => window.B7H.release());
        snapshots[arm] = {
          verify,
          retained: held.totalSelfSize - pre.totalSelfSize,
          bytesPerBoundary: (held.totalSelfSize - pre.totalSelfSize) / boundaries,
          nodeDelta: held.nodeCount - pre.nodeCount,
          raw: { pre, held },
        };
      }
      // The control, read the same independent way.
      await gc();
      const cpre = await snapshotTotal(cdp);
      await page.evaluate((n) => window.B7H.control(n), CONTROL_DOUBLES);
      await gc();
      const cheld = await snapshotTotal(cdp);
      await page.evaluate(() => window.B7H.controlRelease());
      snapshots.control = {
        predictedBytes: CONTROL_PREDICTED,
        measured: cheld.totalSelfSize - cpre.totalSelfSize,
      };
    } catch (e) {
      snapshots = { error: String(e && e.message ? e.message : e) };
    }
  }

  await browser.close();
  return {
    benchmark: 'B7:heap-per-boundary',
    bead: 'rf2-9oj7v',
    roots: ROOTS,
    perRoot,
    boundaries,
    rounds: ROUNDS,
    arms: HEAP_ARMS,
    instruments: {
      A: 'CDP Runtime.getHeapUsage().usedSize after 3x HeapProfiler.collectGarbage',
      B: 'in-page performance.memory.usedJSHeapSize, same moment, --enable-precise-memory-info',
      C: 'full heap snapshot, every node self_size summed by a streaming scan (own pass)',
      note:
        'A and B are two doors onto one V8 counter and are NOT independent — on 80,000 held ' +
        'objects they returned 3868954 both. C walks the object graph and is the independent one.',
    },
    control: {
      shape: 'dense JS array of doubles',
      doubles: CONTROL_DOUBLES,
      predictedBytes: CONTROL_PREDICTED,
      slack: CONTROL_SLACK,
      // The verdict is carried BESIDE the control it adjudicates, so the raw
      // record says what was decided and not only what was measured.
      verdict: controlVerdicts(rounds, snapshots, CONTROL_PREDICTED, CONTROL_SLACK),
    },
    verification: { mounts, unverified },
    perRound: rounds,
    orderVerdict: guard.verdict(orderSamples, { tolerance: TOLERANCE }),
    snapshots,
  };
}

// ---------------------------------------------------------------------------
// The mount-frame row
// ---------------------------------------------------------------------------

async function mountFrameRow(chromium) {
  const q = process.env.B7_MOUNT_QUERY || '';
  const { browser, page } = await newPage(
    chromium, `?mode=mount-frame${q}`, 'the 15-minute wait for `window.B7_DONE`'
  );
  await page.waitForFunction('window.B7_DONE === true || window.B7_ERROR', null, {
    timeout: 15 * 60 * 1000,
  });
  const err = await page.evaluate('window.B7_ERROR || null');
  const results = await page.evaluate('window.B7_RESULTS || {}');
  await browser.close();
  return { err, results };
}

// ---------------------------------------------------------------------------

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
        mean: xs.reduce((a, b) => a + b, 0) / xs.length,
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
          'must, so no figure in this row is reportable',
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

function summariseHeap(row) {
  const stat = (xs) => {
    const s = [...xs].sort((a, b) => a - b);
    const mean = s.reduce((a, b) => a + b, 0) / s.length;
    return { mean, min: s[0], max: s[s.length - 1] };
  };
  console.log('\n;; ==== B7 HEAP — retained bytes per boundary ====');
  console.log(`;; ${row.roots} roots x ${row.perRoot} boundaries = ${row.boundaries} boundaries held`);
  const ctlPred = row.perRound[0].control.predictedBytes;
  const ctlA = stat(row.perRound.map((r) => r.control.measuredCdp));
  const ctlB = stat(row.perRound.map((r) => r.control.measuredPerf));
  console.log(
    `;; positive control: predicted ${ctlPred} B  |  A measured ${Math.round(ctlA.mean)} B ` +
      `[${Math.round(ctlA.min)}–${Math.round(ctlA.max)}]  |  B measured ${Math.round(ctlB.mean)} B ` +
      `[${Math.round(ctlB.min)}–${Math.round(ctlB.max)}]`
  );
  if (row.snapshots && row.snapshots.control) {
    console.log(
      `;; positive control, instrument C: predicted ${row.snapshots.control.predictedBytes} B  |  ` +
        `measured ${row.snapshots.control.measured} B`
    );
  }
  // Published on every run, passing or not: a control quoted only when it
  // passes is not a control.
  for (const v of Object.values(row.control.verdict || {})) {
    console.log(`;; positive control ${v.ok ? 'ok  ' : 'FAIL'} ${v.why}`);
  }
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  console.log(';;');
  console.log(';; arm                 A B/boundary [min-max]        B B/bnd    C B/bnd    residue B (mean)');
  for (const arm of row.arms) {
    const a = stat(row.perRound.map((r) => r.arms[arm].bytesPerBoundaryCdp));
    const b = stat(row.perRound.map((r) => r.arms[arm].bytesPerBoundaryPerf));
    const res = stat(row.perRound.map((r) => r.arms[arm].residueCdp));
    const c =
      row.snapshots && row.snapshots[arm] ? Math.round(row.snapshots[arm].bytesPerBoundary) : null;
    console.log(
      `;; ${arm.padEnd(20)} ${String(Math.round(a.mean)).padStart(6)} ` +
        `[${Math.round(a.min)}–${Math.round(a.max)}]`.padEnd(18) +
        `${String(Math.round(b.mean)).padStart(8)}   ${String(c === null ? '-' : c).padStart(8)}   ` +
        `${String(Math.round(res.mean)).padStart(10)}`
    );
  }
  console.log(';;');
  for (const line of guard.format(row.orderVerdict, 'B7 heap — retained B/boundary, reader A')) {
    console.log(line);
  }
  if (row.orderVerdict.refuse) {
    console.log(';;');
    console.log(';; ==== ARM ORDER: THESE FIGURES ARE NOT REPORTABLE ====');
    console.log(
      ';;   at least one arm reads differently for what preceded it or for where in the run ' +
        'it was measured (rf2-88pie). The table above stands only as raw data; nothing in it ' +
        'may be quoted.'
    );
  }
}

async function drive() {
  // Does the arm-order guard still catch the faults it was built for?
  // Ahead of the build, because a broken guard makes every figure below
  // unpublishable and finding that out after a fifteen-minute run is
  // wasteful. The checks are fixtures replayed from recorded readings, so
  // this is deterministic.
  const guardSelf = guard.selfTest();
  for (const c of guardSelf.checks) {
    console.error(`[b7] order-guard ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}`);
  }
  if (!guardSelf.ok) {
    console.error('[b7] order guard self-test FAILED — nothing may be measured');
    process.exit(1);
  }

  build();
  const server = serve();
  const { chromium } = require('playwright');
  const out = { generatedAt: new Date().toISOString() };
  let failed = null;
  let refused = false;
  try {
    if (ONLY !== 'heap') {
      console.error('[b7] mount-frame row ...');
      const mf = await mountFrameRow(chromium);
      out.mountFrame = mf.results;
      for (const [k, v] of Object.entries(mf.results)) {
        console.log(`;; ==== B7 mount-frame ${k} ====`);
        console.log(v);
      }
      if (mf.err) failed = `mount-frame: ${mf.err}`;
    }
    if (ONLY !== 'mount-frame') {
      console.error('[b7] heap row ...');
      out.heap = await heapRow(chromium);
      summariseHeap(out.heap);
      if (out.heap.verification.unverified > 0) {
        failed = `heap: ${out.heap.verification.unverified} unverified mounts`;
      }
      // THE CONTROL IS ADJUDICATED, not merely printed. For this
      // instrument's whole life it was printed and nothing else — predicted
      // beside measured beside an error percentage, with no pass/fail
      // computed at all (rf2-bml5u) — so a control that had stopped seeing
      // the change its own arithmetic predicts could not stop the run. A
      // control printed without a verdict is a number beside a number.
      // Report first, gate after: `summariseHeap` has already run and the
      // raw artefact is written below, before any non-zero exit.
      const ctlFailed = controlFailures(out.heap.control.verdict);
      if (ctlFailed.length) {
        failed = [failed, `heap: positive control — ${ctlFailed.join('; ')}`]
          .filter(Boolean)
          .join(' | ');
      }
      refused = out.heap.orderVerdict.refuse;
    }
  } catch (e) {
    failed = String(e && e.stack ? e.stack : e);
  } finally {
    server.close();
  }
  const raw = process.env.B7_RAW_OUT;
  if (raw) {
    fs.mkdirSync(path.dirname(raw), { recursive: true });
    fs.writeFileSync(raw, JSON.stringify(out, null, 2));
    console.error(`[b7] raw data -> ${raw}`);
  }
  if (failed) {
    console.error(`[b7] FAILED: ${failed}`);
    process.exit(1);
  }
  // A run whose figures the arm-order guard refused is not a green run. The
  // data is printed and the raw file is written — the refusal is about what
  // may be QUOTED, not about throwing the measurement away. Exit 2, the
  // same code `b8_run.cjs` uses for the same verdict.
  if (refused) {
    console.error('[b7] REFUSED by the arm-order guard (rf2-88pie): heap');
    process.exit(2);
  }
  console.error('[b7] ok');
}

// The decision functions are exported so `heap_control_exit_path.test.cjs`
// can exercise the control gate directly: this driver needs an `:advanced`
// release build and a headless Chromium, so its verdicts cannot be reached
// end-to-end from a unit test. Requiring this file must therefore NOT drive
// it, which is what the `require.main` guard below is for.
module.exports = { controlVerdict, controlVerdicts, controlFailures };

if (require.main === module) {
  drive();
}
