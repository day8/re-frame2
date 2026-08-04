#!/usr/bin/env node
// THE UIx SPINE PER-READ ABLATION — driver.
//
//   node implementation/freehand/test/re_frame/freehand/bench/spine_ablation_run.cjs
//   ABL_ROUNDS=6 ABL_SNAP_ROUNDS=2 node .../spine_ablation_run.cjs
//   ABL_SUBSTRATES=uix node .../spine_ablation_run.cjs      # one page only
//
// Bead rf2-2rtt6.12, epic rf2-2rtt6 (EP-0038). Reports into
// docs/design/hicasso/studio/uix-spine-per-read-decomposition.md.
//
// ## What this measures, and the fault it is built around
//
// RETENTION, never allocation. V8's CDP *sampling* heap profiler drops the
// samples of collected objects: pointed at a mount/unmount loop it reports
// the residue of a page that has already been discarded, not the cost of
// the page — the same 80,000 objects read 4.77 MB when a global held them
// and 0.00 MB when nothing did. That fault has already produced one wrong
// table on this surface. So a reading here is: MOUNT AN ARM AND KEEP IT,
// force a full collection, read the heap; release, collect, read again.
//
// ## Why an ablation needs a fidelity control, and what happens without one
//
// The whole instrument turns on subtracting one variant from another. That
// is only attributable if the transcribed-but-unablated arm (`xcript`)
// reproduces the SHIPPED hook (`uix`). If it does not, the transcription
// prices something else and every difference taken from it is void. This
// driver computes that check on BOTH readers — bytes and the headline object
// count — prints it first, and marks the decomposition NOT ATTRIBUTABLE when
// either pair of ranges fails. The table is still written to disk, because
// suppressing it would hide the evidence that says the instrument is wrong;
// but the RUN then exits 4, because a `VERDICT: 0` printed beside a void
// decomposition is exactly the fail-open shape the audit of PR #7264 found.
//
// ## What else fails the run now, and why each of them was fail-open
//
//   * THE WITNESS was printed against its prediction and never compared with
//     it, so the counting claim this bead exists to make could be falsified
//     by the run's own probe while the run exited 0.
//   * READER C was caught into `snapError` and stepped over. C is the
//     object-COUNT reader and the object count is the headline here, so a
//     run without it has no source for its central number.
//   * THE POSITIVE CONTROL and the fits were recorded and adjudicated by
//     nobody: a control out by any amount, and a "line" with any r2, both
//     passed.
//   * A FAILED UNMOUNT was swallowed by the page and reported as success,
//     which converts a live subscription into measurement state.
//   * AND THE FIRST CUT OF THESE GATES was itself fail-open at the edges
//     (PR #7272 audit): reader B's control error was printed and never
//     adjudicated; `Number.isFinite(x) && x < FLOOR` waved a NaN control or
//     fit through the band tests; and a fidelity leg the run failed to
//     measure was filtered out rather than failed, so with both legs absent
//     main iterated an empty failure list and exited 0. Now every TAKEN
//     A/B/C control must be finite and in band, both required fidelity legs
//     must be present and finite, and every required r2 must be finite and
//     at or above the floor.
//
// EXIT CODES
//   0  reportable
//   1  a gate failed before measurement, or the run could not complete
//   2  THE ARM-ORDER GUARD REFUSED. Repair the arm, never the tolerance.
//   3  a mount did not verify at the DOM
//   4  a claim this table is published on did not hold — see the list above
//
// ## Readers
//
//   A  CDP `Runtime.getHeapUsage().usedSize`, after 3x collectGarbage.
//   B  in-page `performance.memory.usedJSHeapSize`, same moment, under
//      `--enable-precise-memory-info`.
//   C  a full heap SNAPSHOT: every node's `self_size` summed AND every node
//      COUNTED by a streaming scan, as its own pass.
//
// A and B are NOT independent — two doors onto one V8 counter. C walks the
// object graph, and it is the only reader that answers the question this
// bead was filed about: `rf2-2rtt6.5` attributed the 3.77x gap to object
// COUNT (128.5/read against Reagent's 36.2), so C's node delta is the
// headline signal here and bytes are the cross-check, the reverse of the
// ladder's emphasis.
//
// ## The positive control
//
// A dense JS array of N doubles, which V8 stores as N unboxed 8-byte slots,
// so its retained size is 8N bytes PREDICTED BEFORE ANYTHING IS MEASURED.
// It rides EVERY round, in situ, read by the same instrument as the arms.
// Deliberately the same 587,500 doubles = 4,700,000 B rf2-2rtt6.5 used, so
// the error here is directly comparable with the +0.001% that page landed.
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
const { watchPage } = require('./sentinel.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
// FORWARD SLASHES, and not by accident. `:output-dir` is spliced into the
// `--config-merge` EDN string below, and on Windows `path.join('out',
// 'spine-ablation')` yields `out\spine-ablation` whose `\s` the EDN reader
// mangles: shadow-cljs then writes to a filename the OS will not accept.
// Seen once, on the reads ladder.
const OUT_DIR = process.env.ABL_OUT_DIR || 'out/spine-ablation';
const OUT = path.resolve(IMPL, OUT_DIR);
const PORT = Number(process.env.ABL_PORT || 8139);

const ROUNDS = Number(process.env.ABL_ROUNDS || 4);
const SNAP_ROUNDS = Number(process.env.ABL_SNAP_ROUNDS || 2);
const SUBSTRATES = (process.env.ABL_SUBSTRATES || 'uix,reagent')
  .split(',').map((s) => s.trim()).filter(Boolean);
const CONTROL_DOUBLES = Number(process.env.ABL_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
// The witness grid: small, because it is counting and not weighing. 200
// boundaries x 3 reads, and the two offsets below must keep every cell index
// inside the seeded `cells-needed` (1,200 x 7 = 8,400).
const WITNESS_BOUNDARIES = Number(process.env.ABL_WITNESS_BOUNDARIES || 200);
// The fidelity control passes when the shipped arm's and the transcription's
// per-read RANGES overlap, or — DECLARED HERE, BEFORE THE RUN, and not
// afterwards — when their medians are within 3%. The second clause exists
// because a range is a range across ROUNDS: at one round every range has
// zero width and can never overlap, so overlap alone would make the control
// unpassable at low round counts rather than strict. 3% is an order of
// magnitude below the 22% term the ablation is here to resolve, so a band
// this wide cannot manufacture the finding.
const FIDELITY_BAND = Number(process.env.ABL_FIDELITY_BAND || 0.03);
// A relative difference of medians, matching B7's and the ladder's. A
// retention reading taken across a mount/collect/release cycle moves several
// percent between rounds, so this sits above that and far below the 2.01x
// the recorded fault made.
const TOLERANCE = Number(process.env.ABL_TOLERANCE || 0.25);
// THE PUBLISHABILITY BANDS, declared here and therefore BEFORE the run.
//
// The audit of PR #7264 found the central checks fail-open: the witness
// counts were printed but never compared with the prediction, fidelity was
// computed for reader-A bytes only and failed nothing, a reader-C failure was
// caught and stepped over, and neither a bad control nor a curve that is not a
// line could stop the run. So `exit 0` could be reached with the counting
// claim falsified, the ablation non-fidelitous, or reader C absent.
//
// `CONTROL_BAND` — the in-situ 8N control must land within 1% of prediction on
// every reader. The ladder's worst reading on this box was 0.13%, so 1% is
// loose enough not to fire on drift and tight enough to catch a control that
// is not measuring what it says: B7's fictional control was out by orders of
// magnitude, not percent.
//
// `R2_FLOOR` — a per-read cost is only a per-read cost if the rungs carry a
// line. Every fit this instrument has ever published sits above 0.999; 0.99 is
// the floor below which the slope is not a slope.
const CONTROL_BAND = Number(process.env.ABL_CONTROL_BAND || 0.01);
const R2_FLOOR = Number(process.env.ABL_R2_FLOOR || 0.99);
const INIT_FN = 're-frame.freehand.bench.spine-ablation-app/-main';

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
  console.error('[abl] building :advanced bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[abl] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>spine ablation</title></head>' +
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
  // recorded nothing, so a throw was announced on stderr and the run exited 0
  // underneath it. One page is opened per substrate, which is why the watches
  // are collected rather than held in a local: a `pageerror` on ANY substrate
  // has to reach the one exit. `sentinel.cjs`'s header carries the finding,
  // including why no page-side `try`/`catch` can close it under React 19.2.
  // The watch is also RETURNED, because the caller races its own sentinel
  // against it (rf2-qv761).
  const watch = watchPage(page, 'abl');
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
    const perf = await page.evaluate(() => window.ABL.perfMem());
    return { cdp: usedSize, perf };
  };

  return { cdp, gc, read };
}

// Sum every node's `self_size` AND count the nodes out of a streaming heap
// snapshot. The snapshot arrives as JSON text in chunks and can be hundreds
// of megabytes, so it is never assembled or `JSON.parse`d: `nodes` is a flat
// run of integers, `node_fields` names the stride, and this consumes complete
// integers per chunk and carries the partial one. It is the only reader here
// that walks the object graph, which is what makes it a check on the other
// two rather than a restatement of them — and the node COUNT it yields is
// this bead's headline signal.
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

// `floor/r0-b1200`, `uix/r7-b1200`, `retain/r3-b1200`. The driver PARSES
// rather than reconstructs, so a rename in the CLJS cannot silently
// desynchronise the two halves of the instrument.
function parseArm(id) {
  const m = /^([a-z]+)\/r(\d+)-b(\d+)$/.exec(id);
  if (!m) throw new Error(`unparseable arm id: ${id}`);
  return { id, variant: m[1], reads: Number(m[2]), boundaries: Number(m[3]) };
}

const floorFor = (arms) => arms.find((a) => a.variant === 'floor');

// ---------------------------------------------------------------------------
// One page
// ---------------------------------------------------------------------------

async function runSubstrate(chromium, substrate) {
  const { browser, page, watch } = await newPage(
    chromium, `?adapter=${substrate}`, `the 120s wait for window.ABL_READY (${substrate})`
  );
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`. `race`
  // rejects only on a failure `watch` recorded, and the exit block already
  // refuses on exactly those failures, so no run that would have passed is
  // shortened. The rejection propagates into the top-level IIFE's existing
  // `.catch`, which is this driver's exit 1; the enumerated 2/3/4 stand.
  await watch.race('window.ABL_READY === true || window.ABL_ERROR', {
    timeoutMs: 120000,
    budget: `the 120s wait for window.ABL_READY (${substrate})`,
  });
  const err = await page.evaluate('window.ABL_ERROR || null');
  if (err) { await browser.close(); throw new Error(`${substrate} page failed to initialise: ${err}`); }

  const armIds = await page.evaluate(() => Array.from(window.ABL.arms));
  const plan = await page.evaluate(() => window.ABL.plan);
  const arms = armIds.map(parseArm);
  const { cdp, gc, read } = await makeReaders(page);

  // --- THE WITNESS, first, because it is the claim ----------------------
  // Counts, not bytes. 200 boundaries x 3 reads = 600 reads, twice, over
  // DISJOINT cells so the second call cannot be served by the first's cache
  // entries. Predicted before the run and printed against the prediction:
  //   uix      commits 600, rebuilt 600, bodyRuns 1200
  //   reagent  commits   0, rebuilt   0, bodyRuns  600
  const witness = [];
  let unverifiedWitness = 0;
  for (const offset of [0, 900]) {
    const w = await page.evaluate(
      ([n, off]) => window.ABL.witness(n, off), [WITNESS_BOUNDARIES, offset]
    );
    witness.push(w);
    if (!w.ok) { unverifiedWitness++; console.error(`[abl] UNVERIFIED witness ${substrate} offset ${offset}: ${w.elements}/${w.expected}`); }
  }

  // THE PREDICTION, CHECKED. The counts above were printed against the
  // prediction and never compared with it, so the central claim of this bead
  // — that a UIx read with no live cache entry builds TWO reactions and keeps
  // the first — could be falsified by the run's own witness while the run
  // exited 0. `EXPECTED_WITNESS` is the prediction from the driver header,
  // stated as multiples of N.
  const EXPECTED_WITNESS = {
    uix: { commits: 1, rebuilt: 1, bodyRuns: 2 },
    reagent: { commits: 0, rebuilt: 0, bodyRuns: 1 },
  };
  const witnessFailures = [];
  const expect = EXPECTED_WITNESS[substrate];
  if (!expect) {
    witnessFailures.push(`no witness prediction is recorded for substrate '${substrate}'`);
  } else {
    for (const w of witness) {
      for (const k of ['commits', 'rebuilt', 'bodyRuns']) {
        const want = expect[k] * w.reads;
        if (w[k] !== want) {
          witnessFailures.push(
            `${substrate} offset ${w.offset}: ${k} = ${w[k]}, predicted ${expect[k]}N = ${want} ` +
              `for N = ${w.reads} reads`
          );
        }
      }
    }
  }
  for (const f of witnessFailures) console.error(`[abl] WITNESS MISMATCH — ${f}`);

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
  console.error(`[abl] ${substrate}: warm-up pass over ${arms.length} arms ...`);
  for (const a of arms) {
    const v = await page.evaluate((id) => window.ABL.mount(id), a.id);
    mounts++;
    if (!v.ok) { unverified++; console.error(`[abl] UNVERIFIED warm-up ${a.id}: ${v.elements}/${v.expected}`); }
    await page.evaluate(() => window.ABL.release());
  }
  await page.evaluate((n) => window.ABL.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.ABL.controlRelease());

  for (let round = 0; round < ROUNDS; round++) {
    console.error(`[abl] ${substrate}: round ${round + 1}/${ROUNDS} (${round % 2 ? 'REVERSED' : 'FORWARD'})`);

    // --- the positive control, IN SITU, before this round's arms ---------
    await gc();
    const ctlBefore = await read();
    const ctlLen = await page.evaluate((n) => window.ABL.control(n), CONTROL_DOUBLES);
    await gc();
    const ctlHeld = await read();
    await page.evaluate(() => window.ABL.controlRelease());
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
      const verify = await page.evaluate((id) => window.ABL.mount(id), a.id);
      mounts++;
      if (!verify.ok) { unverified++; console.error(`[abl] UNVERIFIED ${a.id}: ${verify.elements}/${verify.expected}`); }
      await gc();
      const held = await read();
      await page.evaluate(() => window.ABL.release());
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
  // Reader C over EVERY arm, in its own rounds, because the node COUNT it
  // yields is the signal this bead exists to explain. Its rounds rotate and
  // reflect on the same schedule, so the count carries a range too.
  const snapRounds = [];
  let snapError = null;
  if (SNAP_ROUNDS > 0) {
    try {
      for (let round = 0; round < SNAP_ROUNDS; round++) {
        console.error(`[abl] ${substrate}: snapshot round ${round + 1}/${SNAP_ROUNDS}`);
        const byArm = {};
        for (const j of guard.schedule(arms.length, round)) {
          const a = arms[j];
          await gc();
          const pre = await snapshotTotal(cdp);
          const verify = await page.evaluate((id) => window.ABL.mount(id), a.id);
          mounts++;
          if (!verify.ok) { unverified++; console.error(`[abl] UNVERIFIED snap ${a.id}`); }
          await gc();
          const held = await snapshotTotal(cdp);
          await page.evaluate(() => window.ABL.release());
          byArm[a.id] = {
            verify,
            boundaries: a.boundaries,
            retained: held.totalSelfSize - pre.totalSelfSize,
            nodes: held.nodeCount - pre.nodeCount,
            bytesPerBoundary: (held.totalSelfSize - pre.totalSelfSize) / a.boundaries,
            nodesPerBoundary: (held.nodeCount - pre.nodeCount) / a.boundaries,
          };
        }
        // The control rides the snapshot pass too — reader C is the reader
        // that walks the graph, so it is the one whose 8N prediction most
        // needs checking, and B7's fictional control was caught exactly here.
        await gc();
        const cpre = await snapshotTotal(cdp);
        await page.evaluate((n) => window.ABL.control(n), CONTROL_DOUBLES);
        await gc();
        const cheld = await snapshotTotal(cdp);
        await page.evaluate(() => window.ABL.controlRelease());
        snapRounds.push({
          round,
          arms: byArm,
          control: {
            predictedBytes: CONTROL_PREDICTED,
            measured: cheld.totalSelfSize - cpre.totalSelfSize,
            error: (cheld.totalSelfSize - cpre.totalSelfSize) / CONTROL_PREDICTED - 1,
          },
        });
      }
    } catch (e) {
      snapError = String(e && e.message ? e.message : e);
      console.error(`[abl] snapshot pass failed: ${snapError}`);
    }
  }

  // --- what the page says about its own teardown -------------------------
  // A swallowed unmount leaves the committed subscription, its watch and its
  // cache entry live, so every later arm stops being a fresh-cache-miss arm
  // while the DOM read-back and the guard both still pass.
  const releaseErrors = await page.evaluate(() => window.ABL.releaseErrors());
  for (const e of releaseErrors) console.error(`[abl] UNMOUNT FAILED on ${e.arm}: ${e.error}`);

  await browser.close();

  // --- the gates that decide whether this table may be published ---------
  const gateFailures = [...witnessFailures];
  for (const e of releaseErrors) gateFailures.push(`unmount failed on ${e.arm}: ${e.error}`);

  // Reader C is the object-COUNT reader, and the object count is the whole
  // finding here — bytes are the cross-check, not the headline. A run that
  // asked for C and did not get it has no source for its central number.
  if (SNAP_ROUNDS > 0) {
    if (snapError) gateFailures.push(`reader C failed: ${snapError}`);
    else if (snapRounds.length !== SNAP_ROUNDS) {
      gateFailures.push(`reader C produced ${snapRounds.length} of ${SNAP_ROUNDS} requested rounds`);
    }
  }

  // The in-situ control, on EVERY reader that took one — A and B each round,
  // C each snapshot round. The first cut adjudicated A and C only, so reader
  // B's error was printed in the report and never gated exit 0; and a band
  // test alone is a bypass for a control that came back NaN, because
  // `Math.abs(NaN) > band` is false. A control that did not produce a finite
  // number has not controlled anything.
  const controlGate = (label, err) => {
    if (!Number.isFinite(err)) {
      gateFailures.push(`${label} control is not finite (${err})`);
    } else if (Math.abs(err) > CONTROL_BAND) {
      gateFailures.push(
        `${label} control off by ${(err * 100).toFixed(3)}% ` +
          `(band ${(CONTROL_BAND * 100).toFixed(1)}%)`
      );
    }
  };
  for (const r of rounds) {
    controlGate(`round ${r.round} reader-A`, r.control.errorCdp);
    controlGate(`round ${r.round} reader-B`, r.control.errorPerf);
  }
  for (const r of snapRounds) {
    controlGate(`snapshot round ${r.round} reader-C`, r.control.error);
  }

  const variants = variantCurves(arms, rounds, snapRounds);

  // A slope is only a per-read cost if the rungs carry a line — and a fit
  // that came back NaN is not a line above the floor, it is no line at all.
  // The first cut tested `Number.isFinite(x) && x < FLOOR`, which waves a
  // non-finite fit through. Bytes r2 comes from the main rounds and is always
  // required; objects r2 exists only when reader C was asked for.
  const r2Gate = (label, r2min) => {
    if (!Number.isFinite(r2min)) {
      gateFailures.push(`${label} is not finite (${r2min}) — no line was fitted`);
    } else if (r2min < R2_FLOOR) {
      gateFailures.push(`${label} ${r2min.toFixed(5)} below the ${R2_FLOOR} floor`);
    }
  };
  for (const [v, c] of Object.entries(variants)) {
    r2Gate(`variant ${v}: bytes r2`, c.r2.min);
    if (SNAP_ROUNDS > 0) r2Gate(`variant ${v}: objects r2`, c.objectsR2.min);
  }

  for (const f of gateFailures) console.error(`[abl] GATE — ${f}`);

  return {
    substrate,
    plan,
    arms: arms.map((a) => a.id),
    witness,
    witnessExpected: expect || null,
    verification: { mounts, unverified, unverifiedWitness },
    perRound: rounds,
    orderVerdict: guard.verdict(orderSamples, { tolerance: TOLERANCE }),
    snapRounds,
    snapError,
    releaseErrors,
    gateFailures,
    variants,
  };
}

// ---------------------------------------------------------------------------
// One line per variant, fitted PER ROUND and only then summarised
// ---------------------------------------------------------------------------

const median = guard.median;
const rangeOf = (xs) =>
  xs.length
    ? { n: xs.length, min: Math.min(...xs), p50: median(xs), max: Math.max(...xs) }
    : { n: 0, min: NaN, p50: NaN, max: NaN };

// Ordinary least squares. Returned with r2 so a curve that is not a line
// cannot be reported as one by accident.
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
  return { slope, intercept, r2: tot === 0 ? 1 : 1 - ss / tot };
}

function variantCurves(arms, rounds, snapRounds) {
  const floor = floorFor(arms);
  const names = [...new Set(arms.map((a) => a.variant))].filter((v) => v !== 'floor');
  const out = {};

  for (const v of names) {
    const vArms = arms.filter((a) => a.variant === v).sort((p, q) => p.reads - q.reads);

    // Reader A — bytes.
    const byteFits = { forward: [], reversed: [], all: [] };
    const perRung = {};
    for (const r of rounds) {
      const pts = vArms.map((a) => ({
        x: a.reads,
        y: (r.arms[a.id].retainedCdp - r.arms[floor.id].retainedCdp) / a.boundaries,
        arm: a.id,
      }));
      for (const p of pts) (perRung[p.arm] = perRung[p.arm] || []).push(p.y);
      const f = fit(pts);
      byteFits.all.push(f);
      byteFits[r.order].push(f);
    }

    // Reader C — bytes AND nodes, from the snapshot rounds.
    const nodeFits = [];
    const snapByteFits = [];
    const perRungNodes = {};
    for (const r of snapRounds) {
      if (!r.arms[floor.id]) continue;
      const nPts = vArms.map((a) => ({
        x: a.reads,
        y: (r.arms[a.id].nodes - r.arms[floor.id].nodes) / a.boundaries,
        arm: a.id,
      }));
      const bPts = vArms.map((a) => ({
        x: a.reads,
        y: (r.arms[a.id].retained - r.arms[floor.id].retained) / a.boundaries,
        arm: a.id,
      }));
      for (const p of nPts) (perRungNodes[p.arm] = perRungNodes[p.arm] || []).push(p.y);
      nodeFits.push(fit(nPts));
      snapByteFits.push(fit(bPts));
    }

    out[v] = {
      bytesPerRead: rangeOf(byteFits.all.map((f) => f.slope)),
      bytesPerBoundary: rangeOf(byteFits.all.map((f) => f.intercept)),
      r2: rangeOf(byteFits.all.map((f) => f.r2)),
      forward: rangeOf(byteFits.forward.map((f) => f.slope)),
      reversed: rangeOf(byteFits.reversed.map((f) => f.slope)),
      rungs: Object.fromEntries(Object.entries(perRung).map(([k, x]) => [k, rangeOf(x)])),
      objectsPerRead: rangeOf(nodeFits.map((f) => f.slope)),
      objectsPerBoundary: rangeOf(nodeFits.map((f) => f.intercept)),
      objectsR2: rangeOf(nodeFits.map((f) => f.r2)),
      snapshotBytesPerRead: rangeOf(snapByteFits.map((f) => f.slope)),
      nodeRungs: Object.fromEntries(Object.entries(perRungNodes).map(([k, x]) => [k, rangeOf(x)])),
    };
  }
  return out;
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

const b = (v) => (Number.isFinite(v) ? Math.round(v).toLocaleString('en-US') : String(v));
const n1 = (v) => (Number.isFinite(v) ? v.toFixed(1) : String(v));
const pct = (v) => (Number.isFinite(v) ? `${(v * 100).toFixed(3)}%` : String(v));
const overlap = (p, q) =>
  Number.isFinite(p.min) && Number.isFinite(q.min) && p.min <= q.max && q.min <= p.max;

// The difference of two variants' per-read lines, with a range that is the
// WIDEST the two ranges admit rather than a difference of medians alone —
// so a term whose sign is not settled by the data cannot be reported as
// though it were.
function delta(a, b_) {
  if (!a || !b_) return null;
  return {
    p50: a.p50 - b_.p50,
    min: a.min - b_.max,
    max: a.max - b_.min,
  };
}
const dstr = (d, f = b) => (d ? `${f(d.p50)}  [${f(d.min)}-${f(d.max)}]` : 'n/a');

// THE FIDELITY CONTROL, on BOTH readers.
//
// The whole instrument turns on subtracting one variant from another, and
// that is only attributable if the transcribed-but-unablated arm (`xcript`)
// reproduces the SHIPPED hook (`uix`). The first cut checked reader-A BYTES
// only — and bytes are this bead's cross-check, not its headline. The
// decomposition is quoted in OBJECTS, so the object count has to pass the
// same control, and a failure has to stop the table being treated as
// attributable rather than merely being labelled.
// EVERY REQUIRED LEG, PRESENT AND FINITE. The previous cut filtered
// non-finite legs out and accepted any one survivor, so a leg the run
// failed to measure EXEMPTED itself from the control it exists to be — and
// with both legs absent the list was empty, `ok` was false, and main's loop
// over the legs had no failure to add, so the run still exited 0. Now the
// bytes leg is always required, the objects leg is required whenever reader
// C was asked for (it is C's numbers the leg compares), and a required leg
// that is absent or non-finite FAILS rather than vanishing.
function fidelityCheck(all) {
  const byName = Object.fromEntries(all.map((s) => [s.substrate, s]));
  if (!byName.uix) return { present: false, ok: true, legs: [] };
  const U = byName.uix.variants;
  if (!U.uix || !U.xcript) {
    // The uix page ran but a whole side of the comparison is missing: there
    // is nothing to check, which is a failure, not an exemption.
    return {
      present: true, ok: false, legs: [],
      missing: 'the uix page ran without both a uix and an xcript variant — no fidelity legs exist',
    };
  }
  const leg = (name, a, b_) => {
    const finite = Number.isFinite(a.p50) && Number.isFinite(b_.p50);
    const ovl = overlap(a, b_);
    const rel = Math.abs(b_.p50 / a.p50 - 1);
    return { name, shipped: a, copy: b_, finite, ovl, rel, ok: finite && (ovl || rel <= FIDELITY_BAND) };
  };
  const legs = [leg('bytes/read', U.uix.bytesPerRead, U.xcript.bytesPerRead)];
  if (SNAP_ROUNDS > 0) {
    legs.push(leg('objects/read', U.uix.objectsPerRead, U.xcript.objectsPerRead));
  }
  return { present: true, legs, ok: legs.every((l) => l.ok) };
}

function report(all) {
  const L = [];
  const byName = Object.fromEntries(all.map((s) => [s.substrate, s]));
  L.push(';; ================================================================');
  L.push(';; UIx SPINE PER-READ ABLATION — rf2-2rtt6.12 (EP-0038 Wave 0)');
  L.push(';; retention instrument, :advanced, headless Chromium');
  L.push(';; ================================================================');

  // --- the witness first: counts, not bytes ------------------------------
  L.push('');
  L.push(';; ---- WITNESS — reactions built per read, by COUNT -----------------');
  L.push(';;   PREDICTED before the run, for N reads:');
  L.push(';;     uix      commits N, rebuilt N, bodyRuns 2N');
  L.push(';;     reagent  commits 0, rebuilt 0, bodyRuns  N');
  for (const s of all) {
    for (const w of s.witness) {
      const nn = w.reads;
      L.push(`;;   ${s.substrate.padEnd(8)} offset ${String(w.offset).padStart(4)}  N=${nn}  ` +
             `commits ${w.commits} (${(w.commits / nn).toFixed(2)}N)  ` +
             `rebuilt ${w.rebuilt} (${(w.rebuilt / nn).toFixed(2)}N)  ` +
             `bodyRuns ${w.bodyRuns} (${(w.bodyRuns / nn).toFixed(2)}N)  ` +
             `${w.ok ? '' : 'DOM UNVERIFIED'}`);
    }
  }

  for (const s of all) {
    const ctl = s.perRound.map((r) => r.control);
    L.push('');
    L.push(`;; ---- page ?adapter=${s.substrate} --------------------------------`);
    L.push(`;; verification: ${s.verification.unverified} unverified of ${s.verification.mounts}`);
    L.push(`;; IN-SITU POSITIVE CONTROL  predicted ${b(CONTROL_PREDICTED)} B`);
    L.push(`;;   reader A  measured ${b(median(ctl.map((c) => c.measuredCdp)))} B  ` +
           `[${b(Math.min(...ctl.map((c) => c.measuredCdp)))}-${b(Math.max(...ctl.map((c) => c.measuredCdp)))}]  ` +
           `error ${pct(median(ctl.map((c) => c.errorCdp)))}`);
    L.push(`;;   reader B  measured ${b(median(ctl.map((c) => c.measuredPerf)))} B  ` +
           `error ${pct(median(ctl.map((c) => c.errorPerf)))}`);
    if (s.snapRounds.length) {
      const cs = s.snapRounds.map((r) => r.control);
      L.push(`;;   reader C  measured ${b(median(cs.map((c) => c.measured)))} B  ` +
             `error ${pct(median(cs.map((c) => c.error)))}`);
    }
    if (s.snapError) L.push(`;;   reader C FAILED: ${s.snapError}`);
    L.push(`;;   ORDER GUARD: ${s.orderVerdict.refuse ? 'REFUSED' : 'clean'}` +
           `${s.orderVerdict.contaminated ? ' (contaminated)' : ''}${s.orderVerdict.unchecked ? ' (unchecked)' : ''}`);

    for (const [v, c] of Object.entries(s.variants)) {
      L.push('');
      L.push(`;;   VARIANT ${v}`);
      for (const [arm, r] of Object.entries(c.rungs)) {
        const reads = parseArm(arm).reads;
        const nr = c.nodeRungs[arm];
        L.push(`;;     R=${String(reads).padStart(2)}  ${b(r.p50).padStart(7)} B/boundary  ` +
               `[${b(r.min)}-${b(r.max)}]` +
               (nr && Number.isFinite(nr.p50) ? `   ${n1(nr.p50).padStart(7)} obj/boundary [${n1(nr.min)}-${n1(nr.max)}]` : ''));
      }
      L.push(`;;     => BYTES PER READ   ${b(c.bytesPerRead.p50)}  [${b(c.bytesPerRead.min)}-${b(c.bytesPerRead.max)}]  ` +
             `(fwd ${b(c.forward.p50)} rev ${b(c.reversed.p50)})`);
      L.push(`;;     => OBJECTS PER READ ${n1(c.objectsPerRead.p50)}  [${n1(c.objectsPerRead.min)}-${n1(c.objectsPerRead.max)}]  ` +
             `reader C`);
      L.push(`;;     => reader C bytes/read ${b(c.snapshotBytesPerRead.p50)}  ` +
             `[${b(c.snapshotBytesPerRead.min)}-${b(c.snapshotBytesPerRead.max)}]`);
      L.push(`;;     => PER-BOUNDARY SHELL ${b(c.bytesPerBoundary.p50)}  [${b(c.bytesPerBoundary.min)}-${b(c.bytesPerBoundary.max)}]`);
      L.push(`;;        r2 ${c.r2.p50.toFixed(5)} [${c.r2.min.toFixed(5)}-${c.r2.max.toFixed(5)}]` +
             (Number.isFinite(c.objectsR2.p50) ? `   obj r2 ${c.objectsR2.p50.toFixed(5)}` : ''));
    }
  }

  // --- the fidelity check, and only then the decomposition ---------------
  const U = byName.uix && byName.uix.variants;
  const R = byName.reagent && byName.reagent.variants;
  const fid = fidelityCheck(all);
  if (fid.present) {
    L.push('');
    L.push(';; ---- FIDELITY CONTROL — BOTH readers ------------------------------');
    for (const l of fid.legs) {
      const f = l.name === 'bytes/read' ? b : n1;
      L.push(`;;   ${l.name.padEnd(13)} shipped uix ${f(l.shipped.p50)} [${f(l.shipped.min)}-${f(l.shipped.max)}]  ` +
             `copy xcript ${f(l.copy.p50)} [${f(l.copy.min)}-${f(l.copy.max)}]`);
      L.push(`;;   ${''.padEnd(13)} ranges ${l.ovl ? 'OVERLAP' : 'do not overlap'};  ` +
             `medians ${pct(l.rel)} apart (band ${pct(FIDELITY_BAND)})  => ${l.ok ? 'ok' : 'FAIL'}`);
    }
    L.push(`;;   => decomposition is ${fid.ok ? 'ATTRIBUTABLE' : 'NOT ATTRIBUTABLE; every delta below is void'}`);
  }
  if (U && U.uix && U.retain && U.hooks) {
    L.push('');
    L.push(';; ---- DECOMPOSITION of the shipped per-read cost --------------------');
    L.push(`;;   retained render-phase reaction   retain - xcript   ${dstr(delta(U.retain.bytesPerRead, U.xcript.bytesPerRead))} B`);
    L.push(`;;   (SUPERSEDED by rf2-2rtt6.13;                          ${dstr(delta(U.retain.objectsPerRead, U.xcript.objectsPerRead), n1)} obj`);
    L.push(`;;    the shipped hook no longer pays it)`);
    L.push(`;;   one live subscription            xcript - hooks    ${dstr(delta(U.xcript.bytesPerRead, U.hooks.bytesPerRead))} B`);
    L.push(`;;                                                        ${dstr(delta(U.xcript.objectsPerRead, U.hooks.objectsPerRead), n1)} obj`);
    L.push(`;;   React's six-hook stack           hooks               ${b(U.hooks.bytesPerRead.p50)}  [${b(U.hooks.bytesPerRead.min)}-${b(U.hooks.bytesPerRead.max)}] B`);
    L.push(`;;                                                        ${n1(U.hooks.objectsPerRead.p50)}  [${n1(U.hooks.objectsPerRead.min)}-${n1(U.hooks.objectsPerRead.max)}] obj`);
    if (R && R.reagent) {
      L.push('');
      L.push(`;;   Reagent, same subs, same page shape                  ${b(R.reagent.bytesPerRead.p50)}  [${b(R.reagent.bytesPerRead.min)}-${b(R.reagent.bytesPerRead.max)}] B`);
      L.push(`;;                                                        ${n1(R.reagent.objectsPerRead.p50)}  [${n1(R.reagent.objectsPerRead.min)}-${n1(R.reagent.objectsPerRead.max)}] obj`);
      L.push(`;;   shipped UIx / Reagent ratio                          ${(U.uix.bytesPerRead.p50 / R.reagent.bytesPerRead.p50).toFixed(3)}x`);
      L.push(`;;   superseded retaining shape / Reagent ratio            ${(U.retain.bytesPerRead.p50 / R.reagent.bytesPerRead.p50).toFixed(3)}x`);
    }
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
    console.error('[abl] order-guard self-test FAILED — refusing to measure');
    for (const c of st.checks) if (!c.ok) console.error(`  FAIL ${c.name} — ${c.detail || ''}`);
    process.exit(1);
  }
  console.error(`[abl] order-guard self-test ok (${st.checks.length} checks)`);

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
    benchmark: 'hicasso:uix-spine-per-read-ablation',
    bead: 'rf2-2rtt6.12',
    epic: 'rf2-2rtt6',
    rounds: ROUNDS,
    snapRounds: SNAP_ROUNDS,
    tolerance: TOLERANCE,
    instruments: {
      A: 'CDP Runtime.getHeapUsage().usedSize after 3x HeapProfiler.collectGarbage',
      B: 'in-page performance.memory.usedJSHeapSize, same moment, --enable-precise-memory-info',
      C: 'full heap snapshot, node self_size summed AND nodes counted by a streaming scan',
      note: 'A and B are two doors onto one V8 counter and are NOT independent. C walks the object graph and is the object-COUNT reader.',
    },
    control: { shape: 'dense JS array of doubles', doubles: CONTROL_DOUBLES, predictedBytes: CONTROL_PREDICTED },
    substrates: all,
  };

  fs.writeFileSync(path.join(OUT, 'spine-ablation.json'), JSON.stringify(result, null, 2));
  const text = report(all);
  console.log(text);
  fs.writeFileSync(path.join(OUT, 'spine-ablation.txt'), text + '\n');
  console.error(`[abl] wrote ${path.join(OUT, 'spine-ablation.json')}`);

  // THE PAGES' OWN FAILURES, READ FIRST (rf2-sib23). A benchmark that threw
  // and kept going publishes a precise number for a page that is not the page
  // under test, so this outranks every band and count below it: those gates
  // adjudicate readings, and this one says the readings are not of the thing.
  // Exit 1 — the code this driver already uses for "the instrument is not in
  // a state to measure" — and deliberately not a new number among the
  // enumerated refusals. Both artefacts are written above, so the partial
  // evidence survives.
  const pageErrors = pageFailures();
  if (pageErrors.length) {
    for (const e of pageErrors) console.error(`[abl] PAGE ERROR — ${e}`);
    console.error(
      '[abl] THE PAGE THREW AND KEPT GOING — every figure in the table above was taken after ' +
        'an uncaught error, so no delta in the decomposition is a reading of the ablation it ' +
        'names. This is not closeable inside the app: React does not rethrow an uncaught ' +
        'render error to the caller of flushSync (see sentinel.cjs). Re-run on a clean page.'
    );
    process.exit(1);
  }

  const refused = all.filter((s) => s.orderVerdict.refuse);
  const unverified = all.reduce(
    (t, s) => t + s.verification.unverified + s.verification.unverifiedWitness, 0);
  if (unverified > 0) {
    console.error(`[abl] ${unverified} UNVERIFIED mounts — the DOM read-back did not match`);
    process.exit(3);
  }
  if (refused.length) {
    for (const s of refused) {
      for (const line of guard.format(s.orderVerdict, `${s.substrate}: arm order`)) console.error(line);
    }
    console.error('[abl] ORDER GUARD REFUSED — repair the arm, not the guard');
    process.exit(2);
  }
  // THE CLAIMS THIS TABLE IS PUBLISHED ON. The report above is written to
  // disk first, so the evidence for a refusal survives the refusal — but the
  // run does not exit 0 with its own witness falsified, its ablation
  // non-fidelitous, its control out of band, its curves not lines, reader C
  // absent, or an unmount silently converted into measurement state.
  const gateFailures = all.flatMap((s) => s.gateFailures.map((f) => `${s.substrate}: ${f}`));
  // `!fid.ok` always names its failure here: a missing side pushes one, and
  // every not-ok leg pushes one — the previous cut could reach `!fid.ok` with
  // an EMPTY leg list (both legs filtered as non-finite) and fall through
  // this loop without adding anything, which is an exit 0 on a run whose
  // fidelity control never happened.
  const fid = fidelityCheck(all);
  if (fid.present && !fid.ok) {
    if (fid.missing) gateFailures.push(`fidelity: ${fid.missing}`);
    for (const l of fid.legs) {
      if (l.ok) continue;
      gateFailures.push(
        !l.finite
          ? `fidelity ${l.name}: leg is absent or non-finite (shipped p50 ${l.shipped.p50}, ` +
              `copy p50 ${l.copy.p50}) — the control this table needs never ran`
          : `fidelity ${l.name}: shipped and transcribed ranges do not overlap and medians are ` +
              `${pct(l.rel)} apart (band ${pct(FIDELITY_BAND)}) — every delta in the decomposition is void`
      );
    }
  }
  if (gateFailures.length) {
    console.error('[abl] THIS TABLE MAY NOT BE PUBLISHED:');
    for (const f of gateFailures) console.error(`  - ${f}`);
    process.exit(4);
  }
  console.error('[abl] done');
})().catch((e) => { console.error(e); process.exit(1); });
