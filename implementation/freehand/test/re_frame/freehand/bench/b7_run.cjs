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

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const IMPL = path.resolve(__dirname, '../../../../..');
const OUT = path.join(IMPL, 'out', 'b7');
const PORT = Number(process.env.B7_PORT || 8131);

const ROUNDS = Number(process.env.B7_ROUNDS || 6);
const ROOTS = Number(process.env.B7_ROOTS || 10);
// 587,500 unboxed doubles = 4,700,000 bytes — the same ~4.7 MB the
// predecessor report's failed sampler reported as 0.00 MB.
const CONTROL_DOUBLES = Number(process.env.B7_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
const WANT_SNAPSHOT = process.env.B7_SNAPSHOT !== '0';

const HEAP_ARMS = [
  'storm/floor',
  'storm/freehand',
  'storm/reagent',
  'storm/uix',
  'reactive/floor',
  'reactive/freehand',
  'reactive/reagent',
];

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
  '{:output-dir "out/b7" :asset-path "." ' +
  ':modules {:main {:init-fn re-frame.freehand.bench.b7-app/-main}}}';

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

async function newPage(chromium, query) {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
  });
  const page = await browser.newPage();
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; B7')) console.log(t);
  });
  page.on('pageerror', (e) => console.error('[b7] page error:', e.message));
  await page.goto(`http://127.0.0.1:${PORT}/${query}`, { waitUntil: 'load' });
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
  const { browser, page } = await newPage(chromium, '?mode=heap');
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

    // --- the arms, order rotating with the round -------------------------
    const arms = {};
    for (let j = 0; j < HEAP_ARMS.length; j++) {
      const arm = HEAP_ARMS[(j + round) % HEAP_ARMS.length];
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
    control: { shape: 'dense JS array of doubles', doubles: CONTROL_DOUBLES, predictedBytes: CONTROL_PREDICTED },
    verification: { mounts, unverified },
    perRound: rounds,
    snapshots,
  };
}

// ---------------------------------------------------------------------------
// The mount-frame row
// ---------------------------------------------------------------------------

async function mountFrameRow(chromium) {
  const q = process.env.B7_MOUNT_QUERY || '';
  const { browser, page } = await newPage(chromium, `?mode=mount-frame${q}`);
  await page.waitForFunction('window.B7_DONE === true || window.B7_ERROR', null, {
    timeout: 15 * 60 * 1000,
  });
  const err = await page.evaluate('window.B7_ERROR || null');
  const results = await page.evaluate('window.B7_RESULTS || {}');
  await browser.close();
  return { err, results };
}

// ---------------------------------------------------------------------------

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
}

(async () => {
  build();
  const server = serve();
  const { chromium } = require('playwright');
  const out = { generatedAt: new Date().toISOString() };
  let failed = null;
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
  console.error('[b7] ok');
})();
