#!/usr/bin/env node
// B8's driver — bytes ALLOCATED per write, across substrates.
//
//   node implementation/freehand/test/re_frame/freehand/bench/b8_run.cjs
//   B8_ROUNDS=5 B8_WRITES=40 node .../b8_run.cjs --kind broad
//
// THE METHOD, in one paragraph. If no garbage collection runs between two
// readings of V8's used-heap counter, their difference is the bytes
// allocated in between — INCLUDING the bytes already unreachable, because
// nothing has reclaimed them yet. That is the only way a counter of this
// kind can see garbage, and it is why the window has to be short. So:
// collect, read, run N writes, read. The middle step containing no
// collection is not assumed — the page samples the counter at every leg
// boundary of every write, and any window in which it went DOWN is thrown
// away rather than averaged in.
//
// WHY NOT THE OBVIOUS TOOL. V8's CDP *sampling* heap profiler drops the
// samples of collected objects, so it reports retention while looking
// like it reports allocation; B7's predecessor watched the same 80,000
// objects read 4.77 MB held and 0.00 MB dropped. This file runs that
// sampler ONCE, over a window whose garbage content is known by
// construction, as a NEGATIVE control — see `samplerControl` below.
//
// WHY NOT A HEAP SNAPSHOT. `takeHeapSnapshot` collects before it walks,
// so it destroys the very bytes in question. It is B7's independent
// reader and it is useless here.
//
// THE POSITIVE CONTROL is a piece of garbage of PREDICTED size: a
// `.slice()` of a prebuilt packed array of D doubles, dropped on the next
// statement, costing 8D bytes. It is read two ways — directly as its own
// leg, and as the SLOPE between two values of D, which cancels every
// constant including the instrument's own footprint. A retention
// instrument reads this control as zero.

//
// THE ARM ORDER IS A MEASURED PROPERTY OF THIS RUN, NOT AN ASSUMPTION.
// `rf2-88pie`: a figure taken from a plan run in one order has not been
// checked. `order_guard.cjs` schedules the arms so every one of them is
// measured after at least two different predecessors, labels every window
// with what preceded it and where in the run it sat, and REFUSES to let
// this driver exit 0 on a figure that either factor separates. Its
// self-test runs before anything is measured, exactly as the key-renaming
// integrity probe does.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const guard = require('./order_guard.cjs');
const { navigate, NAV_TIMEOUT_MS } = require('./navigate.cjs');
const { watchPage } = require('./sentinel.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const OUT = path.join(IMPL, 'out', 'b8');
const PORT = Number(process.env.B8_PORT || 8133);

function arg(name, dflt) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? dflt : process.argv[i + 1];
}

// SIX, not five. The guard splits an arm's samples into thirds and
// compares the first against the last; at five samples a third is ONE, and
// a single-sample stratum carries no range, so the phase question has to be
// adjudicated on a bare ratio. At six it is a range against a range, which
// is the house rule the whole instrument is built on (rf2-tb345).
const ROUNDS = Number(process.env.B8_ROUNDS || 6);
const WRITES = Number(process.env.B8_WRITES || 40);

// --- warm-up (rf2-tb345) ---------------------------------------------------
//
// A measurement site reads well above its settled value until it has run
// several FULL-SIZE windows, and this driver used to warm each (arm, D) with
// the single four-write calibration probe. Two independent readings say that
// is nowhere near enough:
//
//   plain node, packed-SMI .slice() control, nothing else varying, sixteen
//   consecutive windows:
//       42.32 | 10.32 10.26 10.26 10.26 10.33 10.28 | 8.12 8.12 ... 8.12
//   the first window 5.3x the settled value, the next SIX at +27%.
//
//   this driver's own first guarded run, B8_ROUNDS=4 --kind narrow: the
//   `instrument` arm — the pseudo-arm whose entire purpose is to price the
//   harness's own footprint as a CONSTANT — read 4,361 B/write in its first
//   window and 440 in its last. 9.9x.
//
// So each (arm, D) is now warmed at its REAL window size, and warmed until
// it has stopped TRENDING rather than for a fixed count somebody guessed: a
// floor of `B8_WARMUP` windows, then keep going while the guard's own phase
// rule still separates the first third of the trajectory from the last, to
// a ceiling of `B8_WARMUP_MAX`. The whole trajectory is recorded and
// printed, so "wide enough" is a claim the output can be checked against
// rather than an assertion.
const WARMUP = Number(process.env.B8_WARMUP || 6);
const WARMUP_MAX = Number(process.env.B8_WARMUP_MAX || 10);
const SETTLE_TOL = Number(process.env.B8_SETTLE_TOL || 0.10);
const KINDS = (arg('kind', 'broad,narrow')).split(',');
const NO_BUILD = process.argv.includes('--no-build');

// 6,250 doubles = 50,000 B; 12,500 = 100,000 B. Two rungs so the slope is
// available; small enough that the ladder does not itself provoke a
// collection inside a 40-write window.
const CTL_1 = Number(process.env.B8_CTL_1 || 6250);
const CTL_2 = Number(process.env.B8_CTL_2 || 12500);

const ARMS = ['instrument', 'floor', 'reagent', 'freehand-interpreted'];
// The control ladder runs on the instrument (where nothing else moves),
// and on both substantive arms, so the calibration is per-arm rather than
// borrowed from one arm and assumed of the others.
const LADDER_ARMS = ['instrument', 'reagent', 'freehand-interpreted'];

const CONFIG_MERGE =
  '{:output-dir "out/b8" :asset-path "." ' +
  ':modules {:main {:init-fn re-frame.freehand.bench.b8-app/-main}}}';

function build() {
  console.error('[b8] building :advanced bundle ...');
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', 'freehand-release', '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[b8] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>B8</title></head>' +
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------

async function main() {
  // --- does the arm-order guard still catch the faults it was built for? --
  // Ahead of the browser, because a broken guard makes every figure below
  // unpublishable and finding that out after a fifteen-minute run is
  // wasteful. The checks are fixtures replayed from `rf2-jr76s`'s recorded
  // readings, so this is deterministic.
  const guardSelf = guard.selfTest();
  for (const c of guardSelf.checks) {
    console.error(`[b8] order-guard ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}`);
  }
  if (!guardSelf.ok) throw new Error('order guard self-test FAILED — nothing may be measured');

  const { chromium } = require('playwright');
  const browser = await chromium.launch({
    args: [
      '--enable-precise-memory-info',
      // A larger young generation means more writes fit before a scavenge,
      // which is what makes a collection-free window of useful length
      // possible at all. It changes when V8 collects, not what it
      // allocates, and the monotonicity gate does not care either way.
      '--js-flags=--expose-gc --min-semi-space-size=32 --max-semi-space-size=64',
    ],
  });
  const page = await browser.newPage();
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; B8')) console.error(t);
  });
  // THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23). This
  // was a bare `page.on('pageerror', ...)` that logged and recorded nothing;
  // the array now reaches `verdict` below, which is the only seat this run's
  // exit code has. `sentinel.cjs`'s header carries the finding, including why
  // no page-side `try`/`catch` can close it under React 19.2. The sentinel
  // wait is deliberately NOT converted to `watch.race` — that is rf2-f5roa's
  // separate "report it promptly" remedy, and the fault here is the page that
  // throws AND STILL reaches `B8_READY`, which the wait returns from normally.
  const watch = watchPage(page, 'b8');
  // `'commit'`, not `'load'` (rf2-p9fa3). `b8-app/-main` is the bundle's
  // `:init-fn`: it mounts all four update arms and installs `window.B8`
  // synchronously inside the `<script>`, and only the seed that follows is
  // async. `load` cannot fire before any of that, so waiting for it duplicates
  // the `B8_READY` wait below against a budget ten times smaller — and a
  // driver that died there would have burned the `:advanced` build for
  // nothing, with a line naming the wrong budget. The arm-order guard has
  // already self-tested by this point, deliberately ahead of Chromium; this
  // navigation cannot reach it either way.
  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 5-minute wait for `window.B8_READY`',
  });
  await page.waitForFunction('window.B8_READY === true || window.B8_ERROR', null, {
    timeout: 5 * 60 * 1000,
  });
  const err = await page.evaluate('window.B8_ERROR || null');
  if (err) throw new Error(`page failed to initialise: ${err}`);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('Runtime.enable');

  const gc = async () => {
    for (let i = 0; i < 3; i++) {
      await cdp.send('HeapProfiler.collectGarbage');
      await sleep(60);
    }
  };
  const readCdp = async () => (await cdp.send('Runtime.getHeapUsage')).usedSize;

  // --- does the bundle read the keys it writes? -------------------------
  // Closure's advanced renaming does not touch a quoted string key, but it
  // DOES rename a `(.-foo o)` accessor — and the first draft of this
  // harness shipped both. `(.-h r)` became `k.Me` while the literal kept
  // `h`; `(.-bad a)` became `e.Mc` while the literal kept `bad`. The first
  // threw. The second would not have: `undefined + 1` is `NaN`, the
  // literal's `bad: 0` would have stood, and the driver would have read
  // ZERO unverified writes for the rest of time. `decreases` — the gate
  // that rejects a window with a collection in it — would have read a
  // constant zero and accepted every window.
  //
  // So the bundle is asked, in the build about to be measured, to write
  // known values through the measured path's own accessors and read them
  // back. Nothing is measured until it does.
  const integ = await page.evaluate(() => window.B8.integrity());
  const wantKeys = [
    'control', 'write', 'gap', 'force', 'total', 'posSum', 'negSum',
    'bad', 'decreases', 'worstDrop', 'first', 'last',
  ];
  const gotKeys = integ && typeof integ.keys === 'string' ? integ.keys.split(',') : [];
  const missing = wantKeys.filter((k) => !gotKeys.includes(k));
  if (
    !integ ||
    integ.control !== 7 ||
    integ.decreases !== 3 ||
    integ.worstDrop !== -11 ||
    integ.bad !== 5 ||
    missing.length
  ) {
    throw new Error(
      `harness integrity probe FAILED — the :advanced bundle does not read the keys it writes. ` +
        `got ${JSON.stringify(integ)}; expected control=7 decreases=3 worstDrop=-11 bad=5; ` +
        `missing keys: ${JSON.stringify(missing)}`
    );
  }
  console.error(`[b8] integrity probe ok — keys ${gotKeys.join(',')}`);

  // --- is the precise-memory flag actually on? --------------------------
  // Without it Chrome quantises usedJSHeapSize to 100 KB and every figure
  // below would be a rounding artefact. Quantised readings are exact
  // multiples of 100000; precise ones essentially never are.
  const probes = await page.evaluate(() => {
    const xs = [];
    let sink = 0;
    for (let i = 0; i < 8; i++) {
      const a = new Array(2000).fill(0.5);
      sink += a[0];
      xs.push(window.B8.mem());
    }
    window.__b8sink = sink;
    return xs;
  });
  const precise = probes.some((v) => v > 0 && v % 100000 !== 0);
  if (!precise) {
    throw new Error(
      `--enable-precise-memory-info did not take: readings ${JSON.stringify(probes)} are all ` +
        `multiples of 100000, so usedJSHeapSize is quantised and unusable`
    );
  }

  // -------------------------------------------------------------------------
  // One window
  // -------------------------------------------------------------------------

  async function window_(arm, kind, doubles, published, writes) {
    const WRITES = writes;
    await page.evaluate((d) => window.B8.prepare(d), doubles);
    await page.evaluate(() => window.B8.seed());
    await gc();
    const pre = await readCdp();
    const r = published
      ? await page.evaluate(
          ([a, k, n]) => window.B8.runPub(a, k, n),
          [arm, kind, WRITES]
        )
      : await page.evaluate(
          ([a, k, n]) => window.B8.run(a, k, n),
          [arm, kind, WRITES]
        );
    const post = await readCdp();
    await gc();
    const settled = await readCdp();
    return {
      arm,
      kind,
      doubles,
      published: !!published,
      writes: WRITES,
      cdpAllocated: post - pre,
      cdpPerWrite: (post - pre) / WRITES,
      // What survived a full collection: the retention answer to the same
      // window. If this is a rounding error against cdpAllocated then what
      // was measured was garbage, which is the whole point.
      retained: settled - pre,
      inPage: published
        ? null
        : {
            total: r.total,
            posSum: r.posSum,
            negSum: r.negSum,
            perWrite: r.posSum / WRITES,
            control: r.control,
            write: r.write,
            gap: r.gap,
            force: r.force,
            decreases: r.decreases,
            worstDrop: r.worstDrop,
            first: r.first,
            last: r.last,
          },
      bad: r.bad,
      // COLLECTION-FREE, which is not the same as usable. `posSum` is an
      // allocation total either way, because a collection is a FALLING step
      // and is accumulated separately rather than netted against the rising
      // ones. What a collection-free window buys is the identity
      // `posSum === last − first === the CDP bracket`, which is the
      // instrument's consistency check — so these windows are the
      // validation set, not the whole sample.
      collectionFree: !published && r.decreases === 0,
      accepted: r.bad === 0,
    };
  }

  // -------------------------------------------------------------------------
  // The control object, priced by RETENTION — the cross-calibration
  // -------------------------------------------------------------------------
  //
  // `8·D` is arithmetic, not a measurement: it assumes `.slice()` of a
  // double array costs one 8-byte slot per element and nothing else. The
  // allocation instrument disagrees with it, stably and reproducibly. So
  // the same object is priced a second way — hold K copies, collect, read,
  // release, read — which is B7's instrument, proven on this surface to
  // 0.17% against a control whose size WAS known.
  //
  // If retention and allocation agree on bytes-per-copy, then the object
  // costs what they both say and the arithmetic was the fiction. If they
  // disagree, one of the instruments is wrong and no arm should be quoted.
  async function controlRetention(doubles, k) {
    await page.evaluate((d) => window.B8.prepare(d), doubles);
    await gc();
    const pre = await readCdp();
    await page.evaluate((n) => window.B8.hold(n), k);
    await gc();
    const held = await readCdp();
    await page.evaluate(() => window.B8.unhold());
    await gc();
    const post = await readCdp();
    return {
      doubles,
      copies: k,
      predictedPerCopy8D: 8 * doubles,
      retainedPerCopy: (held - pre) / k,
      bytesPerDouble: (held - pre) / k / doubles,
      residue: post - pre,
    };
  }

  // -------------------------------------------------------------------------
  // KEPT vs DROPPED — does the counter see garbage as fully as it sees
  // survivors?
  // -------------------------------------------------------------------------
  //
  // The identical loop, on the instrument arm, at the same D, run twice:
  // once with each control copy kept and once with each dropped. Same
  // code path, same allocation, one difference. If the counter reports
  // the same bytes per copy either way, it does not care whether an
  // object survives — the instrument sees garbage exactly as well as it
  // sees anything, and the residual disagreement with retention is a
  // property of the counter's scale that cancels in every ratio. If
  // keeping reads higher, then garbage is partly invisible and this
  // instrument shares the sampler's disease.
  async function keptVsDropped(kind, n) {
    const out = {};
    for (const keep of [false, true]) {
      await page.evaluate((d) => window.B8.prepare(d), CTL_2);
      await page.evaluate((b) => window.B8.keep(b), keep);
      await page.evaluate(() => window.B8.seed());
      await gc();
      const pre = await readCdp();
      const r = await page.evaluate(([a, k, w]) => window.B8.run(a, k, w), ['instrument', kind, n]);
      const post = await readCdp();
      const kept = await page.evaluate(() => window.B8.kept());
      // Collect while the copies are STILL HELD. For the kept pass this is
      // the same counter's reading of the same objects' RETAINED size, in
      // the same window that just measured their allocation — so the two
      // questions are put to one reader instead of two.
      await gc();
      const held = await readCdp();
      // Now release and settle, so the next pass starts clean.
      await page.evaluate(() => window.B8.keep(false));
      await gc();
      const settled = await readCdp();
      out[keep ? 'kept' : 'dropped'] = {
        writes: n,
        copiesHeld: kept,
        counterPerCopy: r.posSum / n,
        cdpPerCopy: (post - pre) / n,
        retainedWhileHeldPerCopy: (held - pre) / n,
        residueAfterRelease: settled - pre,
        decreases: r.decreases,
      };
    }
    out.ratioKeptOverDropped = out.kept.counterPerCopy / out.dropped.counterPerCopy;
    return out;
  }

  // -------------------------------------------------------------------------
  // The sampling profiler, as a NEGATIVE control
  // -------------------------------------------------------------------------

  async function samplerControl(kind, n, bytesPerDouble) {
    await page.evaluate((d) => window.B8.prepare(d), CTL_2);
    await page.evaluate(() => window.B8.seed());
    await gc();
    await cdp.send('HeapProfiler.startSampling', { samplingInterval: 4096 });
    const pre = await readCdp();
    // The run's return value is not read here — `readCdp()` above and below is
    // what this sampler measures — but the call itself is the workload.
    await page.evaluate(
      ([a, k, w]) => window.B8.run(a, k, w),
      ['freehand-interpreted', kind, n]
    );
    const post = await readCdp();
    // COLLECT BEFORE READING THE SAMPLER, and that is the whole point.
    // Inside a collection-free window the garbage is still standing, so
    // the sampler can still see it and reports a large number — which is
    // not the fault being demonstrated. The fault is that it reports what
    // SURVIVES: after a full collection the samples of the collected
    // objects are dropped and the same window reads as almost nothing,
    // while the counter's reading of that window does not move.
    await gc();
    const { profile } = await cdp.send('HeapProfiler.stopSampling');
    let sampled = 0;
    (function walk(node) {
      for (const s of node.selfSize !== undefined ? [node] : []) sampled += s.selfSize;
      for (const c of node.children || []) walk(c);
    })(profile.head);
    return {
      // Known by MEASUREMENT, not by arithmetic: the retention pass prices
      // one control copy, and the loop drops one on every write.
      controlGarbageBytes: bytesPerDouble * CTL_2 * n,
      counterAllocated: post - pre,
      samplerTotal: sampled,
      note:
        'the sampler is pointed at a window whose garbage content is known; it reports what ' +
        'survived, not what was allocated',
    };
  }

  // -------------------------------------------------------------------------
  // Rounds
  // -------------------------------------------------------------------------

  // -------------------------------------------------------------------------
  // How many writes fit in a window before V8 collects
  // -------------------------------------------------------------------------
  //
  // A collection inside the window costs accuracy: the rising-step sum
  // nets the reclaimed bytes away inside whichever step contained the
  // collection, so it UNDER-states. The first full run measured exactly
  // that — the control's slope read 12.06 B/double where retention says
  // the object is 16.00, and the gap is the bias.
  //
  // The fix is not a bigger correction, it is a shorter window. Arms
  // differ by three orders of magnitude in what they allocate per write —
  // the instrument arm 438 B, Freehand's broad write about four
  // megabytes — so ONE write count cannot suit them all: 25 writes is
  // comfortably collection-free for Reagent and guarantees a dozen
  // collections for Freehand. So each arm, at each control level, gets
  // the number of writes that fits its own allocation into one nursery.
  const TARGET = Number(process.env.B8_TARGET_BYTES || 3.0e6);
  const nFor = {};
  const warmth = {};
  for (const kind of KINDS) {
    nFor[kind] = {};
    warmth[kind] = {};
    for (const arm of ARMS) {
      nFor[kind][arm] = {};
      warmth[kind][arm] = {};
      for (const d of arm === 'instrument' || LADDER_ARMS.includes(arm) ? [0, CTL_1, CTL_2] : [0]) {
        // A calibration window, never read as data. Four writes is enough
        // to SIZE the window and nothing like enough to warm it — which is
        // the whole of rf2-tb345 — so it no longer doubles as the warm-up.
        const probe = await window_(arm, kind, d, false, 4);
        const perWrite = Math.max(1, probe.inPage.posSum / 4);
        const n = Math.max(2, Math.min(WRITES, Math.round(TARGET / perWrite)));
        nFor[kind][arm][d] = n;

        // Now warm at the REAL window size, and keep going until the site
        // has stopped TRENDING. Nothing here is read as data either; the
        // trajectory is kept only so the claim "warm enough" can be checked.
        //
        // "Stopped trending" is asked with the GUARD'S OWN RULE rather than
        // a second one invented here — `phase`, first third against last
        // third, disjoint ranges AND medians beyond tolerance. That matters,
        // because a first draft asked whether the last three windows sat
        // within 10% of each other and could not tell a site that is still
        // warming from one that is merely noisy: `reagent/D=0` spreads
        // 23,637–54,096 B/write when fully warm and would have warmed to the
        // ceiling for ever, while `instrument/D=0` genuinely oscillates
        // 5,969 → 466 → 10,968 → 440 → 9,512 → 440 → 440 → 440 and must not
        // stop at six. Asking the question the way the guard will ask it is
        // also the only way this loop can promise anything about the guard.
        const trail = [];
        let settled = false;
        while (trail.length < WARMUP_MAX) {
          const w = await window_(arm, kind, d, false, n);
          trail.push(w.inPage.posSum / n);
          if (trail.length >= Math.max(4, WARMUP)) {
            const v = guard.verdict(
              trail.map((value, i) => ({ arm, value, predecessor: arm, position: i })),
              { tolerance: SETTLE_TOL, factors: ['phase'] }
            );
            if (!v.contaminated) {
              settled = true;
              break;
            }
          }
        }
        // The settled value is the LAST THIRD'S MEDIAN, not the last
        // window. `instrument/D=0` runs 11,824 → 6,041 → 16,372 → 440 →
        // 5,846 → 440 → 440 → 440 → 440 → 14,109: it is not trending, which
        // is what `settled` asks, but it is bimodal, and whichever window
        // the loop happens to stop on would otherwise become the
        // denominator. A median over the same third the guard adjudicates
        // is the figure that does not depend on where the loop stopped.
        const cold = trail[0];
        const settledValue = guard.median(trail.slice(-Math.max(1, Math.floor(trail.length / 3))));
        warmth[kind][arm][d] = {
          writes: n,
          windows: trail.length,
          settled,
          cold,
          settledValue,
          coldOverSettled: settledValue > 0 ? cold / settledValue : null,
          trail,
        };
        console.error(
          `[b8] ${kind}/${arm}/D=${d}: ~${Math.round(perWrite)} B per write -> ${n} writes per ` +
            `window; warmed ${trail.length} windows, ${settled ? 'SETTLED' : 'STILL MOVING'} ` +
            `[${trail.map((x) => Math.round(x)).join(' ')}]`
        );
      }
    }
  }

  // Every window carries its POSITION in the run and the LABEL of the
  // window that ran immediately before it. `guard.schedule` rotates AND
  // reflects, because the plain `(j + round) % n` rotation this loop used
  // to do changes which arm goes first without changing which arm follows
  // which — see `order_guard.cjs`.
  const rows = [];
  let position = 0;
  let previous = null;
  const take = async (round, arm, kind, doubles, published, n) => {
    const w = await window_(arm, kind, doubles, published, n);
    const label = `${arm}/D=${doubles}${published ? '/pub' : ''}`;
    rows.push({ round, position: position++, predecessor: previous, ...w });
    previous = label;
  };
  for (const kind of KINDS) {
    for (let round = 0; round < ROUNDS; round++) {
      console.error(`[b8] ${kind}: round ${round + 1}/${ROUNDS}`);
      for (const j of guard.schedule(ARMS.length, round)) {
        const arm = ARMS[j];
        const n0 = nFor[kind][arm][0];
        await take(round, arm, kind, 0, false, n0);
        await take(round, arm, kind, 0, true, n0);
        if (LADDER_ARMS.includes(arm)) {
          await take(round, arm, kind, CTL_1, false, nFor[kind][arm][CTL_1]);
          await take(round, arm, kind, CTL_2, false, nFor[kind][arm][CTL_2]);
        }
      }
    }
  }

  console.error('[b8] control retention cross-calibration ...');
  const retention = [];
  for (const d of [CTL_1, CTL_2]) {
    for (const k of [20, 40]) retention.push(await controlRetention(d, k));
  }
  // The retention reading at the larger D and larger copy count is the
  // reference: more copies means the per-copy figure is less sensitive to
  // the baseline, and a residue of zero says the release was clean.
  const refBpd = retention[retention.length - 1].bytesPerDouble;

  console.error('[b8] kept-vs-dropped self-test ...');
  const keptDropped = await keptVsDropped(KINDS[0], 12);

  console.error('[b8] sampler negative control ...');
  const sampler = await samplerControl(KINDS[0], nFor[KINDS[0]]['freehand-interpreted'][0], refBpd);

  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  return { rows, sampler, retention, keptDropped, refBytesPerDouble: refBpd, nFor, warmth,
           integrity: integ, precise: { probes }, pageErrors };
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

function stat(xs) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  return { mean, min: s[0], max: s[s.length - 1], n: s.length };
}
const fmt = (v) => (v === null || v === undefined ? '-' : Math.round(v).toLocaleString('en-US'));

// rf2-tb345. Was the warm-up wide enough? The claim is checkable, so it is
// checked rather than asserted: every (arm, D) reports how far its FIRST
// full-size window sat above the last one, how many windows it took to stop
// moving, and whether it stopped at all inside the ceiling. A combination
// that never settled is named — its arm's figures were taken on a moving
// site and the guard's phase factor is the thing that will say so.
function reportWarmth(warmth) {
  console.log('\n;; ==== WARM-UP — how long each site takes to stop moving (rf2-tb345) ====');
  console.log(
    `;;   floor ${WARMUP} windows, ceiling ${WARMUP_MAX}, settled = the guard's own phase rule ` +
      `no longer separates the trajectory's first third from its last at ` +
      `${(SETTLE_TOL * 100).toFixed(0)}%; the calibration probe is NOT counted`
  );
  console.log(
    ';;   kind/arm/D                      writes  windows   first window     settled  cold/settled  settled?'
  );
  const unsettled = [];
  let worst = null;
  for (const [kind, arms] of Object.entries(warmth || {})) {
    for (const [arm, ds] of Object.entries(arms)) {
      for (const [d, w] of Object.entries(ds)) {
        const label = `${kind}/${arm}/D=${d}`;
        if (!w.settled) unsettled.push(label);
        if (w.coldOverSettled !== null && (worst === null || w.coldOverSettled > worst.r)) {
          worst = { label, r: w.coldOverSettled };
        }
        console.log(
          `;;   ${label.padEnd(32)}${String(w.writes).padStart(6)}` +
            `${String(w.windows).padStart(9)}` +
            `${fmt(w.cold).padStart(15)}${fmt(w.settledValue).padStart(12)}` +
            `${(w.coldOverSettled === null ? '-' : w.coldOverSettled.toFixed(2) + 'x').padStart(14)}` +
            `  ${w.settled ? 'yes' : 'NO — still trending'}`
        );
      }
    }
  }
  if (worst) {
    console.log(
      `;;   worst first-window inflation: ${worst.label} read ${worst.r.toFixed(2)}x its settled ` +
        'value — that is what a single calibration window used to charge to round 1'
    );
  }
  console.log(
    unsettled.length
      ? `;;   NOT SETTLED inside the ceiling: ${unsettled.join(', ')} — raise B8_WARMUP_MAX`
      : ';;   every site settled inside the ceiling'
  );
  return unsettled;
}

function report(out) {
  const { rows, sampler } = out;
  // The control's per-double cost, MEASURED by the retention pass rather
  // than predicted. `8·D` was the arithmetic and it was wrong: `.slice()`
  // of a HOLEY double array does not take the packed fast path, so a copy
  // costs a 4-byte pointer slot plus a boxed 12-byte HeapNumber per
  // element, not one unboxed 8-byte slot. Retention reads 16.002 B/double
  // on two copy counts with a residue of zero.
  const REF = out.refBytesPerDouble;
  const kinds = [...new Set(rows.map((r) => r.kind))];
  const summary = {};
  const refusals = [];

  for (const kind of kinds) {
    const of = (arm, doubles, published) =>
      rows.filter(
        (r) =>
          r.kind === kind &&
          r.arm === arm &&
          r.doubles === doubles &&
          r.published === !!published &&
          r.accepted
      );

    console.log(`\n;; ==== B8 — bytes allocated per ${kind.toUpperCase()} write ====`);
    const all = rows.filter((r) => r.kind === kind);
    const acc = all.filter((r) => r.accepted);
    const mir = all.filter((r) => !r.published && r.accepted);
    const cf = mir.filter((r) => r.collectionFree);
    console.log(
      `;; windows: ${acc.length} of ${all.length} verified at the DOM; of the ${mir.length} ` +
        `mirror windows ${cf.length} were collection-free`
    );
    // THE IDENTITY. Where nothing was reclaimed, the sum of the rising
    // steps, the endpoint difference and the CDP bracket are three ways of
    // computing one number and must agree. Quoted as the worst case.
    // Two checks, and they answer different questions.
    //
    // The BOOKKEEPING identity holds in every window by construction:
    // rising steps plus falling steps must equal the difference between
    // the first and last readings. A non-zero here is an arithmetic bug in
    // the accumulator, nothing to do with V8.
    //
    // The INSTRUMENT check holds only where nothing was reclaimed: there
    // the in-page rising-step sum and the driver's CDP bracket are two
    // separate readers on one window and must agree. Restricted to arms
    // that allocate enough for a percentage to mean anything — the
    // instrument arm's own window is a few hundred bytes wide and a 1 KB
    // difference there is 100%, which says nothing about the table.
    let worstBook = 0;
    for (const r of mir) {
      worstBook = Math.max(
        worstBook,
        Math.abs(r.inPage.posSum + r.inPage.negSum - (r.inPage.last - r.inPage.first))
      );
    }
    let worstCdp = 0;
    for (const r of cf.filter((x) => x.arm !== 'instrument')) {
      worstCdp = Math.max(worstCdp, Math.abs(r.inPage.posSum / r.cdpAllocated - 1));
    }
    console.log(
      `;; bookkeeping identity (rising + falling = last − first): worst residual ${worstBook} B`
    );
    console.log(
      `;; instrument agreement on collection-free windows, in-page rising-step sum vs the CDP ` +
        `bracket: worst ${(worstCdp * 100).toFixed(2)}%`
    );
    console.log(
      `;; unverified writes: ${all.reduce((a, r) => a + (r.arm === 'instrument' ? 0 : r.bad), 0)} of ` +
        `${all.filter((r) => r.arm !== 'instrument').length * WRITES} ` +
        `(the instrument arm renders no cell and is excluded)`
    );

    // --- arm order, before any figure is read ------------------------------
    // The published per-arm figure is `inPage.perWrite` off the D=0 mirror
    // windows, so those are the samples the guard adjudicates. Tolerance
    // 25%: an allocation counter reading the same code twice is stable to
    // a fraction of a percent, but the arms differ by three orders of
    // magnitude in window sizing and a narrow arm's window is small enough
    // that a page boundary moves it several percent. The recorded fault
    // was 101%.
    const orderSamples = [];
    for (const arm of ARMS) {
      for (const r of of(arm, 0, false)) {
        orderSamples.push({
          arm,
          value: r.inPage.perWrite,
          predecessor: r.predecessor,
          position: r.position,
        });
      }
    }
    const orderVerdict = guard.verdict(orderSamples, { tolerance: 0.25 });
    console.log(';;');
    for (const line of guard.format(orderVerdict, `${kind} — the published per-arm figure`)) {
      console.log(line);
    }
    if (orderVerdict.refuse) refusals.push(kind);

    // --- positive control -------------------------------------------------
    console.log(';;');
    console.log(
      ';; POSITIVE CONTROL — garbage whose size is known from the retention pass, seen by ' +
        'the allocation instrument'
    );
    console.log(
      `;;   reference: ${REF.toFixed(3)} B/double, measured by retention on the same object`
    );
    console.log(';;   arm                 direct leg B/write        slope B/double   error');
    const ctl = {};
    for (const arm of LADDER_ARMS) {
      const d1 = of(arm, CTL_1, false);
      const d2 = of(arm, CTL_2, false);
      const b0 = stat(of(arm, 0, false).map((r) => r.inPage.perWrite));
      const b1 = stat(d1.map((r) => r.inPage.perWrite));
      const b2 = stat(d2.map((r) => r.inPage.perWrite));
      const leg2 = stat(d2.map((r) => r.inPage.control / r.writes));
      const slope = b1 && b2 ? (b2.mean - b1.mean) / (CTL_2 - CTL_1) : null;
      const slope0 = b0 && b2 ? (b2.mean - b0.mean) / CTL_2 : null;
      ctl[arm] = {
        referenceBytesPerDouble: REF,
        expectedPerWriteAtCtl2: REF * CTL_2,
        directLegAtCtl2: leg2,
        slopeBytesPerDouble: slope,
        slopeFromZero: slope0,
        atZero: b0,
        at1: b1,
        at2: b2,
      };
      const err = slope === null ? null : (slope / REF - 1) * 100;
      console.log(
        `;;   ${arm.padEnd(20)} ${fmt(leg2 && leg2.mean).padStart(12)}` +
          `  [expect ${fmt(REF * CTL_2)}]   ${slope === null ? '   -  ' : slope.toFixed(3)}` +
          `   ${err === null ? '-' : (err > 0 ? '+' : '') + err.toFixed(1) + '%'}` +
          `   (from 0: ${slope0 === null ? '-' : slope0.toFixed(3)})`
      );
    }

    // --- the table --------------------------------------------------------
    console.log(';;');
    // The legs are `timed-write!`'s own: the state install, the single
    // microtask yield, and the arm's synchronous drain. WHERE a substrate
    // does its rendering differs between arms and the split is what shows
    // it — Freehand's notification runs in the microtask, so its render
    // lands in the GAP and its `flushSync` is empty; Reagent's `r/flush`
    // runs inside the drain, so its render lands in FORCE.
    console.log(
      ';; arm                  B/write (rising-step sum)   write leg     gap leg   force leg   retained   GCs'
    );
    const per = {};
    for (const arm of ARMS) {
      const ws = of(arm, 0, false);
      const inS = stat(ws.map((r) => r.inPage.perWrite));
      const cdpS = stat(ws.map((r) => r.cdpPerWrite));
      const gcS = stat(ws.map((r) => r.inPage.decreases));
      const wS = stat(ws.map((r) => r.inPage.write / r.writes));
      const fS = stat(ws.map((r) => r.inPage.force / r.writes));
      const gS = stat(ws.map((r) => r.inPage.gap / r.writes));
      const rS = stat(ws.map((r) => r.retained / r.writes));
      const pub = stat(of(arm, 0, true).map((r) => r.cdpPerWrite));
      per[arm] = { cdp: cdpS, inPage: inS, write: wS, gap: gS, force: fS, retained: rS,
                   published: pub, gcs: gcS };
      if (!inS) continue;
      console.log(
        `;; ${arm.padEnd(20)} ${fmt(inS.mean).padStart(9)} ` +
          `[${fmt(inS.min)}–${fmt(inS.max)}]`.padEnd(23) +
          `${fmt(wS && wS.mean).padStart(9)}  ${fmt(gS && gS.mean).padStart(10)}` +
          `  ${fmt(fS && fS.mean).padStart(10)}  ${fmt(rS && rS.mean).padStart(9)}` +
          `  ${(gcS ? gcS.mean.toFixed(1) : '-').padStart(5)}`
      );
    }

    // --- mirror vs published ---------------------------------------------
    console.log(';;');
    // Does the MIRROR allocate what the PUBLISHED window allocates? The
    // mirror reads the counter five times a write and the published
    // `timed-write!` reads it not at all, so if the mirror's own footprint
    // were material this is where it would show. Restricted to
    // collection-free mirror windows, because the published loop carries no
    // in-page sampling and so cannot be gated the same way.
    console.log(';; MIRROR vs PUBLISHED window, CDP-bracketed B/write:');
    for (const arm of ARMS) {
      const p = per[arm];
      if (!p) continue;
      const mCf = stat(
        of(arm, 0, false).filter((r) => r.collectionFree).map((r) => r.cdpPerWrite)
      );
      const mirrorVsPub = mCf && p.published ? (mCf.mean / p.published.mean - 1) * 100 : null;
      console.log(
        `;;   ${arm.padEnd(20)} mirror ${fmt(mCf && mCf.mean).padStart(9)}  published ` +
          `${fmt(p.published && p.published.mean).padStart(9)}  ` +
          `mirror−published ${mirrorVsPub === null ? '-' : mirrorVsPub.toFixed(1) + '%'}` +
          `   (collection-free mirror windows: ${mCf ? mCf.n : 0})`
      );
    }

    // --- above floor, paired within round ---------------------------------
    console.log(';;');
    console.log(';; ABOVE THE FLOOR, paired within round:');
    const byRound = (arm) => {
      const m = {};
      for (const r of of(arm, 0, false)) m[r.round] = r.inPage.perWrite;
      return m;
    };
    const fl = byRound('floor');
    const above = {};
    for (const arm of ['reagent', 'freehand-interpreted']) {
      const a = byRound(arm);
      const xs = Object.keys(a)
        .filter((k) => fl[k] !== undefined)
        .map((k) => a[k] - fl[k]);
      above[arm] = stat(xs);
      console.log(
        `;;   ${arm.padEnd(20)} ${fmt(above[arm] && above[arm].mean).padStart(9)} ` +
          `[${fmt(above[arm] && above[arm].min)}–${fmt(above[arm] && above[arm].max)}]`
      );
    }
    const ra = byRound('reagent');
    const fa = byRound('freehand-interpreted');
    const ratios = Object.keys(fa)
      .filter((k) => ra[k] !== undefined && ra[k] - fl[k] > 0)
      .map((k) => (fa[k] - fl[k]) / (ra[k] - fl[k]));
    const rs = stat(ratios);
    const rawRatios = Object.keys(fa)
      .filter((k) => ra[k] > 0)
      .map((k) => fa[k] / ra[k]);
    const rrs = stat(rawRatios);
    console.log(
      `;;   Freehand ÷ Reagent, above floor: ` +
        (rs ? `${rs.mean.toFixed(3)} [${rs.min.toFixed(3)}–${rs.max.toFixed(3)}]` : '-') +
        `   absolute: ` +
        (rrs ? `${rrs.mean.toFixed(3)} [${rrs.min.toFixed(3)}–${rrs.max.toFixed(3)}]` : '-')
    );

    // THE FAIRNESS SPLIT, and it is the one `freehand-vs-reagent.md` §2a
    // insists on. The Freehand arm installs its state through
    // `frame/replace-app-db!` and a re-frame subscription graph; the
    // Reagent arm resets a bare `reagent.core/atom`. That is each
    // substrate's own idiom but it is NOT the same amount of framework,
    // and a re-frame-shaped Reagent application would pay the write leg
    // too. So the view leg — the microtask the notification runs in, plus
    // the synchronous drain — is reported on its own, and it is the
    // number that is about the VIEW SUBSTRATE rather than about re-frame.
    console.log(';;');
    console.log(';; THE VIEW LEG ALONE (gap + force), with the re-frame write leg removed:');
    const viewByRound = (arm) => {
      const m = {};
      for (const r of of(arm, 0, false)) m[r.round] = (r.inPage.gap + r.inPage.force) / r.writes;
      return m;
    };
    const vR = viewByRound('reagent');
    const vF = viewByRound('freehand-interpreted');
    const vRs = stat(Object.values(vR));
    const vFs = stat(Object.values(vF));
    const vRatio = stat(
      Object.keys(vF).filter((k) => vR[k] > 0).map((k) => vF[k] / vR[k])
    );
    console.log(`;;   reagent              ${fmt(vRs && vRs.mean).padStart(9)} [${fmt(vRs && vRs.min)}–${fmt(vRs && vRs.max)}]`);
    console.log(`;;   freehand-interpreted ${fmt(vFs && vFs.mean).padStart(9)} [${fmt(vFs && vFs.min)}–${fmt(vFs && vFs.max)}]`);
    console.log(
      `;;   Freehand ÷ Reagent on the view leg: ` +
        (vRatio ? `${vRatio.mean.toFixed(3)} [${vRatio.min.toFixed(3)}–${vRatio.max.toFixed(3)}]` : '-')
    );
    const wR = stat(of('reagent', 0, false).map((r) => r.inPage.write / r.writes));
    const wF = stat(of('freehand-interpreted', 0, false).map((r) => r.inPage.write / r.writes));
    console.log(
      `;;   write leg (re-frame vs a bare ratom): freehand ${fmt(wF && wF.mean)} B vs reagent ` +
        `${fmt(wR && wR.mean)} B`
    );

    summary[kind] = { per, above, ratioAboveFloor: rs, ratioAbsolute: rrs, control: ctl,
                      orderVerdict,
                      viewLeg: { reagent: vRs, freehand: vFs, ratio: vRatio },
                      writeLeg: { reagent: wR, freehand: wF },
                      windows: { accepted: acc.length, total: all.length,
                                 collectionFree: cf.length, mirror: mir.length } };
  }

  console.log('\n;; ==== CONTROL CROSS-CALIBRATION — the same object, priced two ways ====');
  console.log(';;   D doubles  copies   retained B/copy   B/double   residue after release');
  for (const r of out.retention || []) {
    console.log(
      `;;   ${String(r.doubles).padStart(9)}  ${String(r.copies).padStart(6)}   ` +
        `${fmt(r.retainedPerCopy).padStart(14)}   ${r.bytesPerDouble.toFixed(3).padStart(8)}   ` +
        `${fmt(r.residue).padStart(14)}`
    );
  }
  console.log(
    ';;   `8·D` predicted 8.000 and was WRONG — a .slice() of a HOLEY double array does not'
  );
  console.log(
    ';;   take the packed fast path. The instrument was fine; the arithmetic was the fiction.'
  );

  const kd = out.keptDropped;
  if (kd) {
    console.log('\n;; ==== KEPT vs DROPPED — can the counter see garbage as well as survivors? ====');
    console.log(';;   the identical loop, same D, one difference: whether the copy is kept');
    for (const k of ['dropped', 'kept']) {
      const v = kd[k];
      console.log(
        `;;   ${k.padEnd(8)} allocated ${fmt(v.counterPerCopy).padStart(9)} B/copy   CDP ` +
          `${fmt(v.cdpPerCopy).padStart(9)}   still held ${String(v.copiesHeld).padStart(3)}` +
          `   retained/copy ${fmt(v.retainedWhileHeldPerCopy).padStart(9)}` +
          `   residue ${fmt(v.residueAfterRelease).padStart(9)}   GCs ${v.decreases}`
      );
    }
    console.log(`;;   kept / dropped = ${kd.ratioKeptOverDropped.toFixed(4)}`);
  }

  console.log('\n;; ==== NEGATIVE CONTROL — the sampling heap profiler on the same window ====');
  console.log(`;;   garbage allocated by the control, by construction: ${fmt(sampler.controlGarbageBytes)} B`);
  console.log(`;;   the used-heap counter saw:                         ${fmt(sampler.counterAllocated)} B`);
  console.log(`;;   HeapProfiler sampling total, read after a collect: ${fmt(sampler.samplerTotal)} B`);
  console.log(
    `;;   -> the sampler reports ${((sampler.samplerTotal / sampler.counterAllocated) * 100).toFixed(1)}%` +
      ` of what was allocated; it is a retention instrument.`
  );

  summary.warmupUnsettled = reportWarmth(out.warmth);

  if (refusals.length) {
    console.log('\n;; ==== ARM ORDER: THESE FIGURES ARE NOT REPORTABLE ====');
    console.log(
      `;;   ${refusals.join(', ')} — at least one arm reads differently for what preceded it ` +
        'or for where in the run it was measured (rf2-88pie). The table above stands only as ' +
        'raw data; nothing in it may be quoted.'
    );
  }
  summary.orderRefusals = refusals;
  // The page's own failures ride into the summary beside the two refusal
  // lists, so `verdict` stays the ONE reading of everything the exit code
  // depends on (rf2-sib23).
  summary.pageErrors = out.pageErrors || [];
  return summary;
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// A refusal that only PRINTS is not a refusal (rf2-tb345, audit #7226).
// `reportWarmth` has always named the (arm, D) combinations that never
// stopped trending, and `report` has always persisted that list as
// `summary.warmupUnsettled` — but the exit block read the arm-order guard's
// list and nothing else, so a run could print "NOT SETTLED inside the
// ceiling" and still exit 0 whenever the measured-round guard was clean.
// An arm warmed on a site that was still moving has its figures taken off
// that trajectory; that is not a green run, and the process has to say so.
//
// Both questions are now asked HERE, in one pure function over the summary,
// which is what makes the exit path checkable without a browser — see
// `b8_exit_path.test.cjs`.
//
// The refusals are INDEPENDENT. An unsettled warm-up refuses on its own; an
// order refusal refuses on its own; an uncaught page error refuses on its
// own; when several fire every one is named. The code when both of the first
// two fire is the arm-order guard's, so no run that exited 2 before exits
// anything else now.
//
//   0  clean
//   1  the page threw and kept going (rf2-sib23)
//   2  the arm-order guard refused (rf2-88pie) — also when 3 would apply
//   3  a site never settled inside the warm-up ceiling (rf2-tb345)
//
// Exit 1 is the code `drive` already used for a run that failed outright,
// and a page that threw IS that: the third refusal is rf2-sib23's repair,
// where a bare `pageerror` handler printed the throw and the run exited 0
// beneath it. `B8_ERROR` does not cover it — React 19.2 routes an uncaught
// render error to `reportError` instead of rethrowing, so the app's own
// catch never runs and the page carries on to `B8_READY`. See `sentinel.cjs`.
//
// No refusal suppresses output: the table is printed and B8_RAW_OUT is
// written before this is consulted. A refusal is about what may be QUOTED,
// not about throwing the measurement away.
function verdict(summary) {
  const order = (summary && summary.orderRefusals) || [];
  const unsettled = (summary && summary.warmupUnsettled) || [];
  const pageErrors = (summary && summary.pageErrors) || [];
  const lines = [];
  if (pageErrors.length) {
    lines.push(
      '[b8] FAILED: the page threw and kept going, so every figure above was taken on a page ' +
        'that is not the page under test (rf2-sib23):\n  ' +
        pageErrors.join('\n  ')
    );
  }
  if (unsettled.length) {
    lines.push(
      `[b8] REFUSED — warm-up never settled inside the ceiling (rf2-tb345): ` +
        `${unsettled.join(', ')} — those arms were measured on a site that was ` +
        'still moving. Raise B8_WARMUP_MAX and re-run.'
    );
  }
  if (order.length) {
    lines.push(`[b8] REFUSED by the arm-order guard (rf2-88pie): ${order.join(', ')}`);
  }
  return {
    code: pageErrors.length ? 1 : order.length ? 2 : unsettled.length ? 3 : 0,
    lines,
  };
}

async function drive() {
  if (!NO_BUILD) build();
  const server = serve();
  let out;
  let failed = null;
  try {
    out = await main();
    out.summary = report(out);
  } catch (e) {
    failed = String(e && e.stack ? e.stack : e);
  } finally {
    server.close();
  }
  const raw = process.env.B8_RAW_OUT;
  if (raw && out) {
    fs.mkdirSync(path.dirname(raw), { recursive: true });
    fs.writeFileSync(raw, JSON.stringify(out, null, 2));
    console.error(`[b8] raw data -> ${raw}`);
  }
  if (failed) {
    console.error(`[b8] FAILED: ${failed}`);
    return 1;
  }
  const v = verdict(out && out.summary);
  for (const line of v.lines) console.error(line);
  if (v.code === 0) console.error('[b8] ok');
  return v.code;
}

module.exports = { verdict };

if (require.main === module) {
  drive().then((code) => {
    if (code !== 0) process.exit(code);
  });
}
