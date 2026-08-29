#!/usr/bin/env node
// EP-0038 P0 — the driver. Build the `:advanced` bundle once, run both
// rows, print the table, exit on a refusal.
//
//   node implementation/core/test/re_frame/bench/p0_run.cjs
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only clock
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only heap
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only fanout
//   node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
//   P0_ROUNDS=6 P0_SAMPLES=12 node .../p0_run.cjs
//   P0_RAW_OUT=.../data/alloc-<bead>/run1.json node .../p0_run.cjs
//
// `P0_RAW_OUT` writes the whole collected record as JSON, and an allocation
// window COMMITS that file beside its studio page. See the write site at the
// foot of this file for why, and `samples` on each window for what the record
// now retains so a later estimator can be driven over it.
//
// `--only ladder` is the PER-READ row (rf2-2rtt6.34): B and the witness
// held fixed while READS walk HD-002's 1/3/7/20 at Q = E, on three
// substrates — the two donors and the Hicasso candidate — so the
// candidate is judged against donor rows taken on its own instrument
// (validation.md:180-189). Opt-in, runs nothing else, and takes
// `P0_LADDER_RUNGS` and `P0_LADDER_ROUNDS`.
//
// `--only fanout` is the CACHE-CARDINALITY row (rf2-5prok): the same page,
// readers, collector and guard as the heap row, with B and reads/boundary
// held fixed while the number of UNIQUE live query keys moves. It is
// opt-in, runs nothing else, and takes `P0_ROOTS` and `P0_FAN_ROUNDS`.
//
// ## Why the numbers have to come from here
//
// The bar's numbers are BROWSER numbers (HD-012, validation.md §P0): a
// real browser, `:advanced`, `goog.DEBUG false`. Spec 009 instrumentation,
// schema validation and trace emission are all `goog.DEBUG`-gated and all
// sit on the subscription and render paths these rows measure, so a
// development build publishes a cost no user pays — and it does so in one
// direction only, because a floor arm has no counterpart to any of them.
// `:advanced` cannot compile shadow's `:browser-test` target
// (`cljs-test-display`'s `goog.define`s collide under Closure), so the
// reading rides a plain `:browser` module.
//
// ## No shadow-cljs.edn change, deliberately
//
// The epic's SEQUENCING LAW makes `implementation/shadow-cljs.edn`
// build-id touches hot-zone sequenced. So this driver does what
// `b6_prod_run.cjs` and `b7_run.cjs` already do: it merges an output
// directory and an `:init-fn` into an EXISTING `:advanced` `:browser`
// build id, which contributes nothing but its compiler settings —
// `:target :browser`, `:optimizations :advanced`, `:infer-externs :auto`,
// `goog.DEBUG false`. The module's entry, and therefore everything that
// ends up in the bundle, is this arm's. The default id is rf2-2rtt6.2's
// measurement lane, `:hicasso-bench` — the id the lane landed for exactly
// this ride — and `P0_BUILD` overrides it. One id serves N programs, so
// the driven id's cache entry is cleared before every build
// (`lane_cache.cjs`, rf2-2rtt6.20): a sibling arm's stale `shadow-js/`
// index compiles clean and dies at runtime under `:advanced`, and the
// trap is the ride itself — a foreign `:init-fn` merged onto an existing
// id — not any one donor.
//
// ## The heap row runs HERE and the clock row runs in the page
//
// A page cannot force a garbage collection, so it cannot decide when a
// retained-heap reading is taken; a page that tried would be reading
// whatever the collector happened to have done. The clock row has the
// opposite constraint — a `flushSync` window must not have a CDP
// round-trip in it — so it runs entirely in the page and parks its
// records on `window.P0_RESULTS`.
//
// ## The arm-order guard is expressed ONCE, in CLJS
//
// `re-frame.bench.order-guard` is the rule, and this driver reaches it
// through `window.P0H.verdict` rather than carrying a JavaScript copy —
// there is already a `.cjs` copy of the same rule serving the freehand
// bench, and a third would be a third place for it to drift. Its
// self-test runs before anything is measured, in both modes. **A refusal
// exits 2, and the repair is to the ARM, never to the guard.**
//
// ## Every figure this driver prints as a check, it EXITS on (rf2-95s5b)
//
// It did not. The clock row's `N unverified of M` and BOTH positive
// controls were printed and only the heap row's read-back count was
// adjudicated, so a run in which no write reached the page, or in which
// the instrument could not see a change its own arithmetic predicted,
// printed the count beside `VERDICT: reportable` and exited 0. A count
// that is displayed and not gated is decoration. The four exit-bearing
// checks are now, in the order they are taken:
//
//   1. the arm-order guard's self-test, in the page, before anything is
//      measured — exit 1 (clock) / the page refuses to install (heap);
//   2. `N unverified of M` — clock and heap, exit 1 on any nonzero count;
//   3. the positive control — clock and heap, exit 1 when it is not `ok`.
//      The two rows are adjudicated by DIFFERENT rules of the lane's, and
//      which one applies is a property of the instrument: the clock row by
//      `lane/control-verdict` (overlap), the heap row by
//      `lane/control-verdict-strict` (every round). See below;
//   4. the arm-order verdict over the samples — exit 2, figures not
//      quotable.
//
// The positive controls are UNVERIFIABLE BY CONSTRUCTION — the clock's
// two control arms build different pages on purpose, and the heap's is a
// dense array with no DOM at all — so their windows are excluded from
// (2)'s denominator rather than counted as verified, and (3) is the gate
// they answer to instead. See `p0-harness/mount-sample!`.
//
// TWO CONTROL RULES, ONE PER ROW, AND THE INSTRUMENT PICKS (rf2-egdaq).
// The lane spells both and neither is this driver's to invent. What this
// driver decides is only which of them each row is entitled to, and it
// decides that on what the row's control leg is MADE OF:
//
//   - the CLOCK row (`re-frame.bench.p0-app`) keeps `lane/control-verdict`,
//     the overlap rule. Its control is a ratio of two mount times, and on
//     the M2 and bulk-broad rows those times are one to three of Chrome's
//     100 µs `performance.now()` quanta. The 2026-07-31 ruling measured
//     what strict would cost there over rf2-6i0i2's eighty controls: 80 of
//     80 pass under overlap and 64 of 80 under strict, every miss LOW and
//     every miss on a coarse-leg row, while the two rows measured on 20-plus
//     quanta legs pass strict 40 times out of 40. A rule that refuses a
//     fifth of its controls by landing on the clock quantum is measuring
//     RESOLUTION, not correctness. Overlap stands here.
//   - the HEAP row (`re-frame.bench.p0-heap`) takes
//     `lane/control-verdict-strict`, every round inside the band. Its
//     control is a dense array of 587,500 unboxed doubles read in BYTES off
//     CDP's heap counter — 4,700,000 B predicted, and a typical published
//     range is [4,699,074 – 4,700,974], ±0.02%. There is no quantum for a
//     low round to sit on, so the carve-out above has nothing to exempt,
//     and the ruling's own revisit trigger — a window whose legs clear the
//     quantum — is met by a leg that was never on one.
//
// AND ON THIS ROW OVERLAP IS NOT A WEAKER GATE, IT IS AN ABSENT ONE. The
// failure the heap control exists to catch is a collector that has stopped
// seeing transient garbage, and its shape is a round reading ~0 B. Under
// overlap one such round beside five good ones passes — `min` 0 sits under
// the roof, `max` 4,700,000 over the floor, so the range meets the band.
// Under strict that round is NAMED and the run is refused.
//
// THE TEN PUBLISHED HEAP-CONTROL FIGURES ARE RE-ADJUDICATED UNDER STRICT,
// AND ALL TEN PASS. That is the operator's 2026-08-21 call, taken so that
// the published evidence and the current rule agree with no two-rules
// asterisk, and it is a SEPARATE call from the strict adoption above, which
// landed earlier in PR #8574. NO WINDOW WAS RE-RUN, and none needed to be:
// a published `[min–max]` whose two ends both sit inside the band bounds
// EVERY round inside it, so the committed records settle it as they stand.
// The widest excursion either way across this row's published series is
// 4,690,838 B against a prediction of 4,700,000 B — 0.195% low, against a
// band of ±25% and better than two orders of magnitude inside it. Nothing
// flips: the strict verdict of every heap row ever published is `ok`, so the
// re-adjudication buys agreement rather than a revision, and the tightening
// still buys teeth for the next run.
//
// THAT CALL REACHES THE HEAP ROW ONLY. rf2-egdaq settled as a SPLIT — one
// rule per instrument, not one rule for both arms — and the CLOCK row's half
// REFUSED strict under the 2026-07-31 quantum ruling. THAT REFUSAL STANDS,
// and nothing here reopens it. The heap half was settleable from committed
// records, which is why a worker could take it; the clock half turned on what
// strict would cost at the instrument's own resolution, which is why it went
// to the operator.
//
// Read the two docstrings before quoting `:ok?` — each answer carries the
// `:rule` that decided it precisely so a record cannot be read under the
// other one.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../../../../implementation/core/test/re_frame/bench/lane_cache.cjs');
// The bench lane's one page-failure collector, a sibling in this same
// shared bench-helper directory (rf2-sib23).
const { watchPage } = require('../../../../../implementation/core/test/re_frame/bench/sentinel.cjs');

const PROJECT = path.resolve(__dirname, '../../..');
const IMPL = path.resolve(PROJECT, '../../implementation');

const BUILD = process.env.P0_BUILD || 'hicasso-bench';
const OUT_DIR = process.env.P0_OUT_DIR || 'out/p0-hicasso';
const OUT = path.join(PROJECT, OUT_DIR);
const INIT_FN = process.env.P0_INIT_FN || 're-frame.bench.p0-app/-main';
const PORT = Number(process.env.P0_PORT || 8149);

const ROUNDS = Number(process.env.P0_ROUNDS || 6);
const SAMPLES = Number(process.env.P0_SAMPLES || 12);
const WARMUPS = Number(process.env.P0_WARMUPS || 4);
const ROOTS = Number(process.env.P0_ROOTS || 4);
// 587,500 unboxed doubles = 4,700,000 bytes.
const CONTROL_DOUBLES = Number(process.env.P0_CONTROL_DOUBLES || 587500);
const CONTROL_PREDICTED = CONTROL_DOUBLES * 8;
const HEAP_TOLERANCE = Number(process.env.P0_HEAP_TOLERANCE || 0.25);
const CLOCK_TIMEOUT_MS = Number(process.env.P0_CLOCK_TIMEOUT_MS || 30 * 60 * 1000);
// How far a positive control's reading may sit from the prediction its own
// arithmetic made and still count as THE INSTRUMENT HAS SIGNAL. Generous on
// purpose — the claim being gated is not that the model is exact. ONE slack
// for both rows; what differs between them is not the width of the band but
// WHAT HAS TO SIT INSIDE IT — the range on the clock row, every round on the
// heap row (see the two rules at the head of this file). Both rules apply
// it; this driver only carries it.
const CONTROL_SLACK = Number(process.env.P0_CONTROL_SLACK || 0.25);

const ONLY = (() => {
  const i = process.argv.indexOf('--only');
  return i === -1 ? null : process.argv[i + 1];
})();

// ---------------------------------------------------------------------------
// WHAT THE BOX WAS DOING, WHICH NO DATASET HAS EVER SAID (rf2-24o2z)
// ---------------------------------------------------------------------------
//
// rf2-6kxub measured the floor arm's high-mode RATE tracking ELAPSED TIME
// WITHIN A SESSION — 0/6 at 8.2 min, 1/7 at 14.9, 2/19 at 19.7, 37/69 at 88.3,
// with a within-session gradient at one-tail p = 0.0198 on a boundary-free
// Mann-Whitney.
//
// THOSE ARE THE ADMISSIBLE DENOMINATORS, and the refusal basis is named here
// so the rationale does not teach a failed positive control as a low reading.
// `alloc_mode_rate_session.cjs` refuses any record whose
// `alloc.controlVerdict.ok` is not exactly true, which drops
// `alloc-9jrhi/bisect-5` and `alloc-77gz8/run12`. A REFUSED CONTROL IS NO
// READING AT ALL, never a low one: it leaves the denominator and never the
// numerator, which is why the two short sessions move 1/8 -> 1/7 and
// 2/20 -> 2/19 while 0/6 and 37/69 do not move at all.
//
// That locates the rate on elapsed-time-within-session and NAMES
// NO MECHANISM: thermal state, V8 tier accumulation and heap fragmentation
// over a long session all survive it equally, and across sessions the duration
// is confounded with the date and the clock time.
//
// IT WAS ANSWERABLE AT ALL ONLY BECAUSE `generatedAt` HAPPENED TO BE IN THE
// RECORD. Nothing else about the machine is. Three more riders of exactly that
// kind cost the runner nothing and convert the NEXT window into evidence about
// the rate rather than another instance of it:
//
//   - the CHROMIUM BUILD STRING, which playwright pins and no dataset states.
//     Two windows taken weeks apart may be two different V8s, and the tier-up
//     account is a claim about V8;
//   - the BOX'S LOAD at window open, and again at close, which is the only one
//     of the three that can be read against thermal or contention accounts;
//   - the ELAPSED TIME SINCE THE PREVIOUS RUN in the same session, which is
//     the axis the gradient was measured on and which a single record cannot
//     currently place itself on at all.
//
// THIS IS THE CHEAP MOVE AND DELIBERATELY NOT THE OTHER ONE. rf2-6kxub's own
// note holds that the same-plan cold / hot / after-an-idle-gap variation is
// the CONFIRMING second move and must not be taken first. Nothing here varies
// anything: it records.
//
// AND IT MEASURES NOTHING INSIDE A WINDOW. Every reading below is taken from
// `node:os` outside any measured window — at the first browser launch and once
// more when the record is written — so it cannot perturb a byte of what the
// row publishes.
//
// `loadavg` IS NOT ENOUGH ON ITS OWN, and the reason is the box this
// instrument runs on. Node's `os.loadavg()` is `[0, 0, 0]` on Windows, which
// is where these windows are taken, so a record carrying only that would carry
// nothing. The portable reading is the CPU time accumulated across all cores:
// a snapshot at window open gives the busy fraction since boot, and the
// difference between the open and close snapshots gives the busy fraction
// ACROSS THE RUN — which is the quantity a contention account actually wants
// and which costs no sleep to obtain.
const BOX_SESSION_GAP_MS = Number(process.env.P0_BOX_SESSION_GAP_MIN || 60) * 60 * 1000;

// Where one run leaves a note for the next. It lives in the OS temp directory
// and NOT in the repository: a session marker is machine state, a dataset is
// committed, and a path under a home directory has no business travelling into
// either. The resolved path is deliberately NOT recorded for that same reason.
const BOX_MARKER = process.env.P0_BOX_MARKER || path.join(os.tmpdir(), 'p0-run-session.json');

// One snapshot. Pure of the run: it reads the machine and nothing this process
// owns, so the pin can call it without a build, a server or a Chromium.
function boxSnapshot() {
  const cpus = os.cpus() || [];
  let idle = 0;
  let busy = 0;
  for (const c of cpus) {
    idle += c.times.idle;
    busy += c.times.user + c.times.nice + c.times.sys + c.times.irq;
  }
  return {
    at: new Date().toISOString(),
    // `[0, 0, 0]` on Windows, and the record says so rather than leaving a
    // reader to wonder whether the box was genuinely idle.
    loadavg: os.loadavg(),
    loadavgSupported: os.platform() !== 'win32',
    cpus: cpus.length,
    cpuIdleMs: idle,
    cpuBusyMs: busy,
    freeMemB: os.freemem(),
    totalMemB: os.totalmem(),
    // A box that rebooted between two runs is not the same box under the
    // thermal account, and this is the one number that says so.
    uptimeS: os.uptime(),
  };
}

// The busy fraction BETWEEN two snapshots — the load the run actually saw,
// rather than the average since boot. `null` where the two snapshots cannot
// support the arithmetic, which a reader must be able to tell from a zero.
function boxBusyFraction(open, close) {
  if (!open || !close) return null;
  const busy = close.cpuBusyMs - open.cpuBusyMs;
  const total = busy + (close.cpuIdleMs - open.cpuIdleMs);
  return total > 0 ? busy / total : null;
}

// WHAT THE PREVIOUS RUN LEFT, AND WHAT THIS ONE LEAVES. Read once at require,
// so `sinceMs` is measured from the previous run rather than from wherever in
// this one the record happens to be assembled.
//
// THE SESSION IS DEFINED BY A RECORDED THRESHOLD, not by a hunch. A run that
// starts within `P0_BOX_SESSION_GAP_MIN` of the previous run's END continues
// that session; otherwise it opens a new one. The threshold travels in the
// record, so an analysis that wants a different boundary can redraw it from
// the raw timestamps rather than inherit this one.
//
// AND IT NEVER FAILS A RUN. A missing, unreadable or malformed marker yields
// `null` — the honest answer for the first run on a box, or one whose temp
// directory was cleared — and a marker that cannot be written is recorded and
// otherwise ignored. A provenance rider may not be able to refuse a window.
//
// THE MARKER PATH IS A PARAMETER, not a read of the constant, and that is what
// makes the pin possible at all: a self-test that drove the shipped path would
// read — and then OVERWRITE — the marker of whatever real run last used this
// box, which is the one piece of state here that cannot be reconstructed.
function boxSessionRead(marker = BOX_MARKER) {
  try {
    const prev = JSON.parse(fs.readFileSync(marker, 'utf8'));
    if (!prev || typeof prev.startedAt !== 'string') return null;
    return prev;
  } catch {
    return null;
  }
}

function boxSessionOpen(now = Date.now(), marker = BOX_MARKER) {
  const prev = boxSessionRead(marker);
  const prevEnd = prev && prev.endedAt ? Date.parse(prev.endedAt) : null;
  const prevStart = prev ? Date.parse(prev.startedAt) : null;
  const sinceEndMs = Number.isFinite(prevEnd) ? now - prevEnd : null;
  const continues = prev !== null && sinceEndMs !== null && sinceEndMs <= BOX_SESSION_GAP_MS;
  return {
    startedAt: new Date(now).toISOString(),
    sessionStartedAt: continues ? prev.sessionStartedAt : new Date(now).toISOString(),
    runsInSession: continues ? (prev.runsInSession || 1) + 1 : 1,
    sessionGapMs: BOX_SESSION_GAP_MS,
    previousRun:
      prev === null
        ? null
        : {
            startedAt: prev.startedAt,
            endedAt: prev.endedAt || null,
            sinceStartMs: Number.isFinite(prevStart) ? now - prevStart : null,
            sinceEndMs,
            sameSession: continues,
          },
  };
}

function boxSessionClose(session, endedAt = new Date().toISOString(), marker = BOX_MARKER) {
  try {
    fs.writeFileSync(
      marker,
      JSON.stringify({
        startedAt: session.startedAt,
        endedAt,
        sessionStartedAt: session.sessionStartedAt,
        runsInSession: session.runsInSession,
      })
    );
    return null;
  } catch (e) {
    return String((e && e.message) || e);
  }
}

// The one place the three riders accumulate. `chromium` is filled by the first
// browser launch — see `newPage` — because the build string is a property of a
// launched browser and there is nowhere earlier to read it from.
const BOX = {
  session: boxSessionOpen(),
  chromium: null,
  open: null,
  close: null,
};

// The rider as it appears in the record, beside `generatedAt` and for the same
// reason. Pure of the module's own state — it takes the accumulator — so the
// pin can DRIVE a box with a launch and one without and read both shapes out.
//
// A RUN THAT LAUNCHED NOTHING SAYS SO. `chromium` and `load.open` are `null`
// on a run that never reached a browser, which a reader must be able to tell
// apart from a run that launched one and found the box idle. Nothing here
// substitutes a plausible value for a missing one.
function boxRecord(box = BOX) {
  return {
    bead: 'rf2-24o2z',
    chromium: box.chromium,
    node: process.version,
    platform: `${os.platform()}/${os.arch()}/${os.release()}`,
    load: {
      open: box.open,
      close: box.close,
      // The busy fraction the RUN saw, which is the reading a contention or
      // thermal account is actually about — the open snapshot alone can only
      // give the average since boot.
      busyFraction: boxBusyFraction(box.open, box.close),
    },
    session: box.session,
  };
}

// The heap families, and which segment each arm needs. `null` is the
// floor, which needs whichever adapter the segment it is being read in
// has installed — it holds no re-frame state, so it is the same work
// either side of the seam and is the calibrator that makes the
// cross-segment ratio legitimate.
const HEAP_SEGMENTS = [
  { segment: 'reagent-subs', arms: ['list/floor', 'list/reagent', 'grid/floor', 'grid/reagent'] },
  { segment: 'uix-subs', arms: ['list/floor', 'list/uix', 'grid/floor', 'grid/uix'] },
];

// ---------------------------------------------------------------------------
// The fan-out sweep (rf2-5prok)
// ---------------------------------------------------------------------------
//
// The heap-regime ruling (rf2-2rtt6.16) made cache cardinality part of the
// witness: a retained-bytes-per-boundary figure is defined only relative to
// how many boundaries share a subscription. `--only fanout` is the row that
// walks that axis and nothing else — the same page, the same readers, the
// same collector, the same guard, and B and E/B held fixed while Q moves.
//
// The rungs, per substrate, at whatever `P0_ROOTS` sets B to:
//
//   R0      0 reads          — the boundary SHELL, Q = 0
//   R1Q1    1 read,  Q = B   — fan-out 1, the distinct-query worst case
//   R1Q2    1 read,  Q = B/2 — fan-out 2
//   R1Q4    1 read,  Q = B/4 — fan-out 4, which at ROOTS=4 is exactly the
//                              regime rf2-2rtt6.4's published grid rows were
//                              measured in
//   R1Q8    1 read,  Q = B/8 — fan-out 8
//   R2Q2B   2 reads, Q = 2B  — held out of the fit
//   R2QB2   2 reads, Q = B/2 — held out of the fit
//
// and the published `grid/<substrate>` arm rides along unchanged as the
// REPRODUCTION ANCHOR: at ROOTS=4 it is the same B, E and Q as R1Q4 through
// a different query id, so the two agreeing is a same-run check that the
// fan family is measuring the published family's quantity.
//
// Why two R=2 rungs. They are the only rungs the model is not fitted to,
// and they are what turns a curve fit into a test: `R2QB2 − R1Q2` is one
// extra edge per boundary at IDENTICAL Q, which is the per-edge term with
// nothing else moving, and the R=2 pair prices the per-key term a second
// time from samples the R=1 slope never saw.
const FAN_ROUNDS = Number(process.env.P0_FAN_ROUNDS || 6);

function fanRungs(boundaries) {
  const B = boundaries;
  return [
    { rung: 'R0', reads: 0, keys: 0 },
    { rung: 'R1Q1', reads: 1, keys: B },
    { rung: 'R1Q2', reads: 1, keys: Math.round(B / 2) },
    { rung: 'R1Q4', reads: 1, keys: Math.round(B / 4) },
    { rung: 'R1Q8', reads: 1, keys: Math.round(B / 8) },
    { rung: 'R2Q2B', reads: 2, keys: 2 * B },
    { rung: 'R2QB2', reads: 2, keys: Math.round(B / 2) },
  ];
}

const FAN_SUBSTRATE = { 'reagent-subs': 'reagent', 'uix-subs': 'uix' };

function fanPlan(perRoot, roots) {
  const B = roots * perRoot.grid;
  return Object.keys(FAN_SUBSTRATE).map((segment) => {
    const sub = FAN_SUBSTRATE[segment];
    const arms = [
      { arm: 'grid/floor', key: `${segment}|grid/floor`, boundaries: B, opts: null, rung: 'floor' },
    ];
    for (const r of fanRungs(B)) {
      arms.push({
        arm: `fan/${sub}`,
        key: `${segment}|fan/${sub}#${r.rung}`,
        boundaries: B,
        opts: { reads: r.reads, keys: r.keys },
        rung: r.rung,
        reads: r.reads,
        keys: r.keys,
      });
    }
    arms.push({
      arm: `grid/${sub}`,
      key: `${segment}|grid/${sub}`,
      boundaries: B,
      opts: null,
      rung: 'anchor',
      reads: 1,
      keys: perRoot.grid,
    });
    return { segment, arms };
  });
}

// ---------------------------------------------------------------------------
// The reads ladder (rf2-2rtt6.34)
// ---------------------------------------------------------------------------
//
// `--only ladder` is the PER-READ row: B held fixed, reads walked over
// HD-002's mandated 1/3/7/20, and Q pinned to E — the distinct-query
// worst case, which is the regime both published per-read gates are
// stated in (validation.md:136-140).
//
// It carries THREE substrates where every other row here carries two.
// validation.md:180-189 judges a candidate against the donor row taken
// on its OWN instrument, and calls a margin under 5% instrument-limited
// rather than cleared; this instrument has no 3/7/20 rung on any
// substrate, so a candidate measured here against the freehand ladder's
// donors would be quoting a ~5% cross-instrument offset as a result. The
// donors are therefore re-taken here, in the same rounds, under the same
// collector and the same guard.
//
// The candidate rides BOTH segments, and that is a measurement rather
// than a duplicate. It needs neither adapter's HOOKS, but its reads go
// through `re-frame.subs`, whose reaction implementation comes from the
// installed adapter's reactive substrate — so the two candidate columns
// are one view layer over two subscription substrates. A per-read claim
// has to be stated against that pair: a view layer cannot be cheaper
// than the reactions it holds, and which donor it is being compared to
// decides which substrate is under it.
const LADDER_RUNGS = (process.env.P0_LADDER_RUNGS || '0,1,3,7,20')
  .split(',')
  .map((s) => Number(s.trim()));
const LADDER_ROUNDS = Number(process.env.P0_LADDER_ROUNDS || 6);

const LADDER_SUBSTRATES = {
  'reagent-subs': ['reagent', 'hicasso'],
  'uix-subs': ['uix', 'hicasso'],
};

// `perRoot.grid` is the PAGE, and the plan states it on every arm rather
// than letting the page's own compile-time default stand in for it. The
// retention row passes what `window.P0H.boundariesPerRoot` answers and the
// plan is the one it has always been; the allocation row passes the small
// witness its masking bound admits (rf2-2rtt6.138), and the floor moves with
// the arms because a calibrator read on a different page is not one.
function ladderPlan(perRoot, roots) {
  const cells = perRoot.grid;
  const B = roots * cells;
  return Object.keys(LADDER_SUBSTRATES).map((segment) => {
    const arms = [
      {
        arm: 'grid/floor',
        key: `${segment}|grid/floor`,
        boundaries: B,
        opts: { cells },
        rung: 'floor',
      },
    ];
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const R of LADDER_RUNGS) {
        arms.push({
          arm: `lad/${sub}`,
          key: `${segment}|lad/${sub}#R${R}`,
          boundaries: B,
          opts: { reads: R, keys: B * R, cells },
          rung: `R${R}`,
          reads: R,
          keys: B * R,
          substrate: sub,
        });
      }
    }
    return { segment, arms };
  });
}

function legacyPlan(perRoot, roots) {
  return HEAP_SEGMENTS.map(({ segment, arms }) => ({
    segment,
    arms: arms.map((arm) => ({
      arm,
      key: `${segment}|${arm}`,
      boundaries: roots * perRoot[arm.split('/')[0]],
      opts: null,
      rung: arm,
    })),
  }));
}

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

// THE WORK CENSUS (rf2-n1b9h). `P0_WORK_COUNT=1` compiles the three monotone
// work counters INTO the bundle by flipping `re-frame.bench.p0-workcount`'s
// `goog-define`; unset, Closure constant-folds every call site away and the
// bundle is the one this rig compiled before the counters existed. That is why
// the switch is a closure-define and not a runtime flag: the constancy claim
// the whole `alloc-9jrhi` series rests on is a claim about the COMPILED write
// path, and a runtime branch inside it would not have kept it.
//
// A run with the census ON is NOT comparable byte-for-byte with a published
// row — one array store per handler invocation allocates nothing, but the
// compiled shape of the write path has moved and that is exactly the axis
// `rf2-77gz8`'s surviving runtime candidate lives on. The census is read
// HIGH-MODE AGAINST LOW-MODE UNDER ONE BUILD, where the counter is a constant
// present in both arms of the comparison.
const WORK_COUNT = process.env.P0_WORK_COUNT === '1';

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and then reports `EOF while
// reading` from a fragment. The closure-define rides in the same one line for
// that reason and no other.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." :modules {:main {:init-fn ${INIT_FN}}}` +
  (WORK_COUNT
    ? ' :compiler-options {:closure-defines {re-frame.bench.p0-workcount/counting? true}}'
    : '') +
  '}';

function build() {
  // The lane's cache rule, before anything reads the cache: this driver
  // merges its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry was
  // written by a different program. `lane_cache.cjs` carries the measured
  // fault and the rejected alternatives (rf2-2rtt6.20).
  if (resetLaneBuildCache(PROJECT, BUILD)) {
    console.error(`[p0] cleared .shadow-cljs/builds/${BUILD} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[p0] building :advanced bundle (donor build id: ${BUILD}) ...`);
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, and a shell concatenates argv, which is
  // the other way the config-merge EDN gets torn in half.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE],
    { cwd: PROJECT, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[p0] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>P0</title></head>' +
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

// `'commit'`, never `'load'`. `p0-app/-main` is this bundle's `:init-fn`,
// so the whole clock run happens INSIDE the `<script>` and the `load`
// event is downstream of it. Waiting for `load` would be waiting for the
// benchmark against a thirty-second ceiling nothing here could see.
// One entry per page this run opened, and this driver opens MANY — one per
// clock round, plus one for each of the heap, fanout and ladder rows.
// Flattened once, at the exit.
const PAGE_WATCHES = [];
const pageFailures = () =>
  PAGE_WATCHES.flatMap((w) => w.failures).map((f) => `${f.kind}: ${f.detail}`);

// `jsFlags` is parameterised but every row passes the default, and the
// allocation row's investigation (rf2-2rtt6.76) is why the obvious
// override is NOT among them.
//
// The allocation instrument's method is that no collection runs between
// two readings of the used-heap counter. Where one does, the allocation
// between the last reading before it and the collection itself is netted
// away inside a single step, and the rising-step sum under-reads. The
// obvious repair is to enlarge the young generation so a whole window's
// garbage fits — `--max-semi-space-size=N` — and it DOES NOT WORK. Two
// standalone probes measured it directly, on both kinds of garbage:
//
//   one large array per iteration (LO space), 20 iterations:
//     D=1,000 (8 KB)     8.40 B/double, 0 falls   <- the prediction, hit
//     D=100,000 (800 KB) 4.00 B/double, 10 falls
//   many small objects per iteration (new space), 20 iterations:
//     K=5,000  (~240 KB) 23.92 B/object,  3 falls
//     K=40,000 (~1.9 MB)  6.94 B/object, 10 falls
//
// and the SAME table came back at `--max-semi-space-size=128` and at
// `=512`, falls unchanged to the unit. So the reading degrades
// monotonically with the number of collections inside the window, the
// flag does not suppress them, and the bias is always DOWNWARD.
//
// That last property is what matters for what this row was built to
// measure. HD-002 predicts the candidate's steady-state allocation slope
// is FLAT AT ZERO, and an instrument that systematically under-reads
// allocation is an instrument that manufactures exactly that answer. The
// row therefore gates on the falling-step count rather than shipping a
// flag that changes nothing while looking like a repair.
async function newPage(chromium, query, jsFlags = '--expose-gc') {
  const browser = await chromium.launch({
    args: ['--enable-precise-memory-info', `--js-flags=${jsFlags}`],
  });
  // THE TWO RIDERS THAT ONLY A LAUNCH CAN SUPPLY (rf2-24o2z). The build string
  // is a property of a launched browser and there is nowhere earlier to read
  // it from; the load snapshot is taken here rather than at require because
  // "at window open" is what the mechanism question asks for, and the first
  // launch is the closest thing this driver has to one — `--only alloc` runs
  // NOTHING else, so under the selection every allocation window is taken with
  // this IS the alloc page. FIRST LAUNCH ONLY: the clock row takes one page
  // per round, and a snapshot per page would record the last round rather than
  // the open. Neither read touches a measured window.
  if (BOX.chromium === null) {
    try {
      BOX.chromium = `${browser.browserType().name()}/${browser.version()}`;
    } catch (e) {
      BOX.chromium = `unavailable: ${String((e && e.message) || e)}`;
    }
  }
  if (BOX.open === null) BOX.open = boxSnapshot();
  const page = await browser.newPage();
  // Every `;; P0` record, and EVERY warning or error the page emits. The
  // second half is not debug scaffolding: a React warning about a root
  // that failed to unmount, or a re-frame error recovered on a render
  // path, is the difference between a slow arm and a broken instrument,
  // and a driver that filtered them out would publish the number anyway.
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; P0')) console.log(t);
    else if (m.type() === 'error' || m.type() === 'warning') {
      console.error(`[p0] page ${m.type()}: ${t.slice(0, 400)}`);
    }
  });
  // AND THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23).
  // The console handler above already refuses to filter a React warning out
  // of the operator's view, for exactly the reason stated there — and one
  // line below it, an UNCAUGHT THROW was printed and recorded nowhere, so the
  // run exited 0 on top of it. `sentinel.cjs`'s header carries the finding,
  // including why no page-side `try`/`catch` can close it under React 19.2.
  // ONE PAGE PER CLOCK ROUND is the reason these are collected rather than
  // held in a local: a throw in any round has to reach the one exit. The
  // watch is also RETURNED, because every caller races its own sentinel
  // against it (rf2-qv761) — this driver's clock wait is the largest budget
  // in the fleet at thirty minutes, and a page that dies at load used to
  // spend all of it before saying so.
  const watch = watchPage(page, 'p0');
  PAGE_WATCHES.push(watch);
  await page.goto(`http://127.0.0.1:${PORT}/${query}`, {
    waitUntil: 'commit',
    timeout: 120000,
  });
  return { browser, page, watch };
}

// ---------------------------------------------------------------------------
// The clock row
// ---------------------------------------------------------------------------

// ONE ROUND PER PAGE. Run as a single page, this instrument's own probe
// measured `usedJSHeapSize` climbing 34 -> 87 MB across six segment
// entries with `body-children` pinned at 2, and the FLOOR arm — which
// cannot change — drifting 3.4 -> 7.0 ms on that heap; the arm-order
// guard refused on phase, correctly. A fresh document cannot inherit the
// previous round's heap, so a browser restart per round removes the
// factor by construction rather than by argument. The accumulation is
// itself a finding and is filed separately, not swept up here.
async function clockRow(chromium) {
  const roundEdns = [];
  let err = null;
  for (let r = 0; r < ROUNDS && !err; r++) {
    console.error(`[p0] clock round ${r + 1}/${ROUNDS} (fresh page) ...`);
    const q = `?round=${r}&samples=${SAMPLES}&warmup=${WARMUPS}`;
    const { browser, page, watch } = await newPage(chromium, q);
    // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`. `race`
    // rejects only on a failure `watch` recorded, and the exit block already
    // folds exactly those failures into `failures`, so no run that would have
    // passed is shortened. The rejection lands in the driver's existing
    // `catch`, which pushes onto `failures` — this driver's exit 1. The
    // arm-order guard keeps its 2.
    await watch.race('window.P0_DONE === true || window.P0_ERROR', {
      timeoutMs: CLOCK_TIMEOUT_MS,
      budget: `the ${Math.round(CLOCK_TIMEOUT_MS / 60000)}-minute wait for window.P0_DONE (round ${r})`,
    });
    err = await page.evaluate('window.P0_ERROR || null');
    const edn = await page.evaluate('window.P0_ROUND || null');
    const selfTest = await page.evaluate('window.P0_GUARD_SELF_TEST || null');
    if (r === 0 && selfTest && !/:ok\? true/.test(selfTest)) {
      err = 'the arm-order guard self-test failed — nothing may be measured';
    }
    await browser.close();
    if (edn) roundEdns.push(edn);
  }
  if (err) return { err, results: null };
  if (!roundEdns.length) return { err: 'no round produced a record', results: null };

  // The fold runs in a page too, so the ranges, the red-zone ratios, the
  // arm-order verdict, the summed read-back tally and the positive
  // control's verdict are computed by `re-frame.bench.order-guard`,
  // `p0-harness` and `hicasso.lane` — the same code the rounds ran under —
  // rather than by a second, drifting expression of the same arithmetic in
  // JavaScript. `adjudicate` is the ONLY door onto the fold: a driver that
  // could take the record without the verdicts is the hole this closed.
  console.error('[p0] aggregating ...');
  const { browser, page, watch } = await newPage(chromium, '?mode=aggregate');
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`.
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the aggregate page)',
  });
  const adj = await page.evaluate(
    ([e, s]) => window.P0A.adjudicate(e, s),
    [roundEdns, CONTROL_SLACK]
  );
  await browser.close();
  return {
    err: null,
    results: adj.edn,
    verification: { unverified: adj.unverified, of: adj.of, perRow: adj.perRow },
    control: { ok: adj.controlOk, why: adj.controlWhy, slack: CONTROL_SLACK },
  };
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
  // roots die in stages — fibers, then the host instances they point at —
  // and a single pass leaves the second stage standing.
  const gc = async () => {
    for (let i = 0; i < 3; i++) {
      await cdp.send('HeapProfiler.collectGarbage');
      await sleep(80);
    }
  };
  const read = async () => {
    const { usedSize } = await cdp.send('Runtime.getHeapUsage');
    const perf = await page.evaluate(() => window.P0H.perfMem());
    return { cdp: usedSize, perf };
  };
  return { cdp, gc, read };
}

// ---------------------------------------------------------------------------
// The heap row
// ---------------------------------------------------------------------------

// ONE measurement engine, two plans. The heap row's plan is the published
// four-arm one; the fan-out row's walks cache cardinality. Everything
// between them — the warm-up pass, the collector, the in-situ control, the
// slot order, the read-back gates, the arm-order verdict — is shared, so
// the two rows cannot drift into being two instruments wearing one name.
// `analyse` runs with the page STILL OPEN, because the rules it reaches
// live in ClojureScript.
async function heapPass(
  chromium,
  { benchmark, bead, plan: planOf, roots, rounds: nRounds, preflight, analyse }
) {
  const { browser, page, watch } = await newPage(chromium, '?mode=heap');
  // RACED AGAINST THE PAGE DYING (rf2-qv761) — see `sentinel.cjs`.
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the heap page)',
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`heap page failed to initialise: ${err}`);
  }
  if (preflight) {
    try {
      await preflight(page);
    } catch (e) {
      await browser.close();
      throw e;
    }
  }
  const perRoot = await page.evaluate(() => window.P0H.boundariesPerRoot);
  const plan = planOf(perRoot, roots);
  const { gc, read } = await makeReaders(page);

  let unverified = 0;
  let mounts = 0;
  const unverifiedDetail = [];
  const orderSamples = [];
  let position = 0;
  let previous = null;

  const mountOne = async (entry) => {
    const v = await page.evaluate(
      ([a, k, o]) => window.P0H.mount(a, k, o),
      [entry.arm, roots, entry.opts]
    );
    mounts++;
    if (!v.ok) {
      unverified++;
      unverifiedDetail.push(
        `${entry.key}: elements ${v.elements}/${v.expected}, keys ${v.keys}/${v.keysExpected}`
      );
    }
    return v;
  };

  // A WARM-UP PASS, mounted and released once per arm and never read. The
  // first mount of any arm allocates things that are not the page and
  // never go away: compiled code for the paths it just took, inline
  // caches, interned keywords, one-time module state. Charged to round 1
  // they read as retention per boundary, and they are not.
  console.error('[p0] heap warm-up pass ...');
  for (const { segment, arms } of plan) {
    await page.evaluate((s) => window.P0H.prepare(s), segment);
    for (const entry of arms) {
      await mountOne(entry);
      await page.evaluate(() => window.P0H.release());
    }
  }
  await page.evaluate((n) => window.P0H.control(n), CONTROL_DOUBLES);
  await page.evaluate(() => window.P0H.controlRelease());

  const rounds = [];
  for (let round = 0; round < nRounds; round++) {
    console.error(`[p0] heap round ${round + 1}/${nRounds}`);

    // --- the positive control, in situ, before this round's arms --------
    await gc();
    const ctlBefore = await read();
    const ctlLen = await page.evaluate((n) => window.P0H.control(n), CONTROL_DOUBLES);
    await gc();
    const ctlHeld = await read();
    await page.evaluate(() => window.P0H.controlRelease());
    await gc();
    const ctlAfter = await read();
    const control = {
      doubles: ctlLen,
      predictedBytes: CONTROL_PREDICTED,
      measuredCdp: ctlHeld.cdp - (ctlBefore.cdp + ctlAfter.cdp) / 2,
      measuredPerf: ctlHeld.perf - (ctlBefore.perf + ctlAfter.perf) / 2,
      baselineDriftCdp: ctlAfter.cdp - ctlBefore.cdp,
    };

    // --- the two segments, in the order this round's parity dictates ----
    // Segment order alternates with the round for the same reason the
    // clock row's does: two segments admit two orders, and a single-order
    // result has not been checked.
    const segs = round % 2 === 0 ? plan : [...plan].slice().reverse();
    const armsOut = {};
    for (const { segment, arms } of segs) {
      // `prepare` BEFORE the baseline read, so the adapter swap's own
      // residue lands in the baseline and not in the arm's delta.
      await page.evaluate((s) => window.P0H.prepare(s), segment);
      const order = await page.evaluate(
        ([n, r]) => window.P0H.slotOrder(n, r),
        [arms.length, round]
      );
      for (const j of order) {
        const entry = arms[j];
        await gc();
        const pre = await read();
        const verify = await mountOne(entry);
        await gc();
        const held = await read();
        await page.evaluate(() => window.P0H.release());
        await gc();
        const post = await read();
        // THE SURVIVAL METRIC'S STRUCTURAL HALF (rf2-2rtt6.34), read
        // here and not one line earlier: the Hicasso runtime reaps a
        // cell and a read-set entry whose last holder left on the NEXT
        // MACROTASK, so a residue read immediately after `release()`
        // would report a cache that is about to evict itself as a leak.
        // The collector above has just spent three passes with an 80 ms
        // beat between them, which is that macrotask several times over.
        const structural = await page.evaluate(() =>
          window.P0H.hicassoResidue ? window.P0H.hicassoResidue() : null
        );
        const boundaries = entry.boundaries;
        armsOut[entry.key] = {
          segment,
          arm: entry.arm,
          rung: entry.rung,
          reads: entry.reads,
          keys: entry.keys,
          verify,
          structural,
          boundaries,
          retainedCdp: held.cdp - pre.cdp,
          retainedPerf: held.perf - pre.perf,
          residueCdp: post.cdp - pre.cdp,
          bytesPerBoundaryCdp: (held.cdp - pre.cdp) / boundaries,
          bytesPerBoundaryPerf: (held.perf - pre.perf) / boundaries,
        };
        orderSamples.push({
          arm: entry.key,
          value: armsOut[entry.key].bytesPerBoundaryCdp,
          predecessor: previous,
          position: position++,
        });
        previous = entry.key;
      }
    }
    rounds.push({ round, control, arms: armsOut });
  }

  const verdictEdn = await page.evaluate(
    ([s, t]) => window.P0H.verdict(s, t),
    [orderSamples, HEAP_TOLERANCE]
  );

  // THE CONTROL IS ADJUDICATED, not printed. `predicted` is 8 bytes a
  // double, fixed before the run; the readings are this row's own, ONE PER
  // ROUND. The rule is `lane/control-verdict-strict`'s — every round inside
  // the band, not merely the range meeting it (rf2-egdaq) — and this driver
  // states the pair and reads the answer HERE because the page has to still
  // be open for the rule to be the lane's rather than a JavaScript copy.
  //
  // THE PER-ROUND ARRAY IS WHAT CROSSES, and an aggregate cannot stand in
  // for it. A `{min, max, mean}` summary has already thrown away which
  // round was which, so the rule it is handed to can only ask about the
  // range; handing over the rounds is what lets the answer name the one
  // that missed. It is also what makes the answer re-adjudicable later
  // without re-running the window — the hole rf2-egdaq's audit of PR #8326
  // found on three runs that had recorded only the summary.
  const ctlPerRound = rounds.map((r) => r.control.measuredCdp);
  const ctlStat = stat(ctlPerRound);
  const controlVerdict = await page.evaluate(
    ([p, v, s]) => window.P0H.controlVerdict(p, v, s),
    [CONTROL_PREDICTED, ctlPerRound, CONTROL_SLACK]
  );

  const extra = analyse ? await analyse(page, rounds, plan) : {};

  await browser.close();

  return Object.assign(
    {
      benchmark,
      bead,
      roots,
      perRoot,
      rounds: nRounds,
      plan: plan.map((p) => ({
        segment: p.segment,
        arms: p.arms.map((a) => ({
          key: a.key,
          arm: a.arm,
          rung: a.rung,
          boundaries: a.boundaries,
          reads: a.reads,
          keys: a.keys,
        })),
      })),
      instruments: {
        A: 'CDP Runtime.getHeapUsage().usedSize after 3x HeapProfiler.collectGarbage',
        B: 'in-page performance.memory.usedJSHeapSize, same moment, --enable-precise-memory-info',
        note:
          'A and B are two doors onto one V8 counter and are NOT independent — on the ' +
          'predecessor instrument, pointed at 80,000 held objects, they returned 3868954 both.',
      },
      control: {
        shape: 'dense JS array of doubles',
        doubles: CONTROL_DOUBLES,
        predictedBytes: CONTROL_PREDICTED,
        measured: ctlStat,
        // The rounds themselves, beside the summary of them. A record that
        // keeps only the summary cannot be re-adjudicated under the other
        // rule, which is the durability half of rf2-egdaq.
        perRound: ctlPerRound,
        slack: CONTROL_SLACK,
        verdict: controlVerdict,
      },
      verification: { mounts, unverified, detail: unverifiedDetail },
      perRound: rounds,
      orderVerdictEdn: verdictEdn,
      orderRefused: /:refuse\? true/.test(verdictEdn),
    },
    extra
  );
}

async function heapRow(chromium) {
  const row = await heapPass(chromium, {
    benchmark: 'P0:retained-heap-per-boundary',
    bead: 'rf2-2rtt6.4',
    plan: legacyPlan,
    roots: ROOTS,
    rounds: ROUNDS,
  });
  row.segments = HEAP_SEGMENTS;
  return row;
}

// ---------------------------------------------------------------------------
// The fan-out row
// ---------------------------------------------------------------------------

async function fanoutRow(chromium) {
  return heapPass(chromium, {
    benchmark: 'P0:retained-heap-fan-out-sweep',
    bead: 'rf2-5prok',
    plan: fanPlan,
    roots: ROOTS,
    rounds: FAN_ROUNDS,
    // The adjudicator's own self-test, BEFORE anything is measured, on the
    // same footing as the arm-order guard's: two synthetic pages built by
    // arithmetic, one exactly additive and one carrying a quadratic key
    // term. The first has to be priced back to its own three terms and the
    // second has to be REFUSED — and refused out of sample, since its r²
    // clears the linearity floor. A fit rule that cannot fail would make
    // every price below unfalsifiable.
    preflight: async (page) => {
      const st = await page.evaluate(() => window.P0H.fanSelfTest());
      for (const c of st.checks) {
        console.log(`;; P0 fan-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
      }
      if (!st.ok) {
        throw new Error(
          'the additive-model self-test FAILED — no fan-out figure may be priced'
        );
      }
    },
    // The fit and its refusal rule are ClojureScript
    // (`p0-heap/additive-fit`), reached through the page while it is still
    // open, for the same reason the arm-order verdict and the control
    // verdict are: a JavaScript restatement would be a second place for the
    // rule to drift, and this one decides whether a component price may be
    // quoted at all.
    analyse: async (page, rounds) => {
      const substrates = Object.keys(FAN_SUBSTRATE);
      const perRoundFits = {};
      const meanFits = {};
      for (const segment of substrates) {
        const sub = FAN_SUBSTRATE[segment];
        const rungsOf = (r) => {
          const floor = r.arms[`${segment}|grid/floor`];
          return fanRungs(floor.boundaries).map((g) => {
            const a = r.arms[`${segment}|fan/${sub}#${g.rung}`];
            return {
              rung: g.rung,
              reads: g.reads,
              keys: g.keys,
              boundaries: a.boundaries,
              y: a.bytesPerBoundaryCdp - floor.bytesPerBoundaryCdp,
            };
          });
        };
        perRoundFits[segment] = [];
        for (const r of rounds) {
          perRoundFits[segment].push(
            await page.evaluate((rs) => window.P0H.fanVerdict(rs), rungsOf(r))
          );
        }
        // The headline fit, over the per-rung MEANS. Reported beside the
        // per-round fits and never instead of them: a criterion applied
        // only to a mean is a criterion a single bad round can hide from.
        const all = rounds.map(rungsOf);
        const meanRungs = all[0].map((g, i) => ({
          ...g,
          y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
        }));
        meanFits[segment] = await page.evaluate((rs) => window.P0H.fanVerdict(rs), meanRungs);
        meanFits[segment].rungs = meanRungs;
      }
      return { fanFits: { perRound: perRoundFits, mean: meanFits } };
    },
  });
}

// ---------------------------------------------------------------------------
// The ladder row (rf2-2rtt6.34)
// ---------------------------------------------------------------------------

async function ladderRow(chromium) {
  return heapPass(chromium, {
    benchmark: 'P0:retained-heap-reads-ladder',
    bead: 'rf2-2rtt6.34',
    plan: ladderPlan,
    roots: ROOTS,
    rounds: LADDER_ROUNDS,
    // The fit rule's own control, BEFORE anything is measured, on the
    // same footing as the arm-order guard's and the additive model's:
    // an exact line has to be recovered to the byte, a QUADRATIC page
    // has to be refused by the r² floor at a value predicted in
    // advance, and a fit that used the forbidden R=0 rung has to be
    // caught. The third is the defect the audit of PR #7260 found in
    // the predecessor ladder, and it is a check of this instrument's
    // arithmetic — not corroboration of any measurement.
    preflight: async (page) => {
      const st = await page.evaluate(() => window.P0H.ladderSelfTest());
      for (const c of st.checks) {
        console.log(`;; P0 ladder-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
      }
      if (!st.ok) {
        throw new Error('the ladder-fit self-test FAILED — no per-read slope may be priced');
      }
    },
    analyse: async (page, rounds, plan) => {
      const fits = { perRound: {}, mean: {} };
      for (const { segment } of plan) {
        for (const sub of LADDER_SUBSTRATES[segment]) {
          const id = `${segment}|${sub}`;
          const rungsOf = (r) => {
            const floor = r.arms[`${segment}|grid/floor`];
            return LADDER_RUNGS.map((R) => {
              const a = r.arms[`${segment}|lad/${sub}#R${R}`];
              return {
                rung: `R${R}`,
                reads: R,
                y: a.bytesPerBoundaryCdp - floor.bytesPerBoundaryCdp,
              };
            });
          };
          fits.perRound[id] = [];
          for (const r of rounds) {
            fits.perRound[id].push(await page.evaluate((rs) => window.P0H.ladderFit(rs), rungsOf(r)));
          }
          // The headline fit is over the per-rung MEANS, reported beside
          // the per-round fits and never instead of them — a criterion
          // applied only to a mean is one a single bad round can hide from.
          const all = rounds.map(rungsOf);
          const meanRungs = all[0].map((g, i) => ({
            ...g,
            y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
          }));
          fits.mean[id] = await page.evaluate((rs) => window.P0H.ladderFit(rs), meanRungs);
          fits.mean[id].rungs = meanRungs;
        }
      }
      return { ladderFits: fits };
    },
  });
}

// ---------------------------------------------------------------------------
// The allocation row (rf2-2rtt6.76) — the survival metric's OTHER half
// ---------------------------------------------------------------------------
//
// `--only alloc` is the STEADY-STATE ALLOCATION SLOPE across warm 1/3/7/20
// reads. It is opt-in and runs nothing else, on the same terms as `ladder`
// and `fanout`.
//
// It is not a variant of the ladder and it does not re-measure it. The
// ladder prices what a boundary KEEPS per read; this prices what a warm
// re-render THROWS AWAY per read. `p0_heap.cljs`'s own header says
// "Nothing here counts allocations", and that is exactly why HD-002's
// survival metric has been half-witnessed: the zero-retained-per-
// occurrence clause is answered by the ladder's residue column and its
// structural stamp (rf2-2rtt6.9), and the allocation clause has never had
// an instrument on this rig at all.
//
// THE SHAPE OF A READING. Collect; then run N warm bulk writes with the
// used-heap counter sampled on both sides of every one of them; accumulate
// the RISING steps and the FALLING steps separately. A rising-step sum is
// an allocation total whether or not a collection intervened, because a
// collection is excluded from it rather than netted against it. Where one
// did land the sum is a slight UNDER-estimate, bounded by one leg per
// collection, and the collection count is published beside every figure.
//
// WHAT IS BEING WRITTEN. One `dispatch-sync` of `:p0/write-page` — through
// the same event pipeline and signal graph as the bulk clock arms'
// `:p0/write-all`, differing only in rebuilding the grid at the mounted
// page's own width (rf2-2rtt6.140) — followed by the substrate's own drain.
// Every boundary re-renders and every boundary's READ SET IS UNCHANGED,
// which is the steady state HD-002's cost law is stated over.
//
// `P0_ALLOC_WRITE=all` drives `:p0/write-all` in the same window instead,
// for V1's `F_old` control (rf2-gxrr). It is not a default and no published
// row is taken under it; see THE MEASUREMENT SURFACE below.
//
// WHY THE DONORS RIDE ALONG. A warm re-render at R reads allocates R query
// vectors in the arm's own body, R subscription recomputations and a React
// element tree, on EVERY substrate. HD-002's claim is about edge
// maintenance alone, so the quantity that answers it is the candidate's
// slope LESS the same-run donor's. A candidate slope quoted on its own
// would be mostly other people's allocation, which is why this row carries
// the donors in the same rounds under the same collector exactly as the
// ladder does.
//
// THE CONTROLS. Three, and the run exits on the first two:
//
//   idle       a window with no work in it at all — the sampler's own
//              footprint, which is otherwise an unexamined constant
//              sitting inside every figure in the table;
//   control    a dropped `.slice()` of D doubles per iteration, costing a
//              PREDICTED 8D bytes, read DIRECTLY;
//   control'   the same at a second D, so the per-double cost is also read
//              as a SLOPE between the two, which cancels every constant
//              including the sampler's footprint.
//
// A retention instrument reads both control figures as ZERO. That is the
// entire claim this row has to establish before any arm is quoted, and it
// is the check `b8-alloc` was built around after the sampling profiler
// produced a wrong table on this surface.
// `ALLOC_WRITES` — how many warm writes one measured window holds — is
// NOT here. It is the averaging floor, and it is stated below beside the
// floor it follows (rf2-2rtt6.140).
const ALLOC_ROUNDS = Number(process.env.P0_ALLOC_ROUNDS || 6);
const ALLOC_WARMUPS = Number(process.env.P0_ALLOC_WARMUPS || 3);
// 8 B a double, so 1,000 doubles is a predicted 8,000 B of garbage per
// iteration. SMALL on purpose, and the size was chosen by measurement
// rather than by taste: the standalone probe above walked D from 1,000 to
// 100,000 and the reading tracked the prediction only at the bottom of
// that range, where no collection fell inside the window (8.40 B/double
// at 0 falls, against 4.00 B/double at 10). A control sized like the arms
// would fail for the arms' reason and prove nothing about the arithmetic.
const ALLOC_D = Number(process.env.P0_ALLOC_D || 1000);
const ALLOC_D2 = Number(process.env.P0_ALLOC_D2 || 400);
// How far the two control readings may sit from 8 B/double and still count
// as THE INSTRUMENT CAN SEE GARBAGE. Generous on purpose: the claim being
// gated is that transient bytes are visible AT ALL, not that V8's
// bookkeeping is exactly 8 B wide. `b8-alloc` measured a stable 12.06
// B/double against the same 8 B arithmetic and could not close the gap, so
// a band that demanded 8 exactly would refuse a working instrument.
const ALLOC_CONTROL_SLACK = Number(process.env.P0_ALLOC_CONTROL_SLACK || 0.75);
// The threshold, MEASURED rather than assumed: the standalone probes put
// the first falling step at roughly 600 KB of cumulative garbage in a
// window, on both kinds of garbage and at every semi-space size tried.
//
// IT GATES NOTHING (rf2-2rtt6.140). It is a RECORDED FACT ABOUT THIS RIG,
// quoted in the summary because a window's `rise` as a fraction of it is a
// useful thing for a reader to see, and it is NOT loosened — it stays at
// the figure the probes read. What it may not do is certify a window: it is
// an UPPER bound on where the first collection runs, and the safety
// argument the retired masking budget built on it needed a LOWER one.
const ALLOC_FALL_THRESHOLD_B = 600000;

// THE OBSERVED-COLLECTION WITNESS — a certificate read off the window's own
// samples (rf2-2rtt6.140, replacing rf2-n6w7o's masking budget).
//
// THE FAULT IT ADDRESSES IS UNCHANGED. `allocSteps` below detects a
// collection by the SIGN of an adjacent step, and a sign test is blind in
// exactly one direction. Where V8 collects inside a leg that ALSO allocates
// at least as much as the collection reclaimed, the observed step is >= 0:
// `falls` stays 0, the reclaimed bytes are simply missing from `rise`, and
// the row prints a window that looks clean and reads LOW. Under-reading
// allocation is the direction that manufactures HD-002's predicted
// flat-at-zero, so it is the one direction this row may not be able to fail
// in.
//
// WHY THE ARITHMETIC THAT USED TO GUARD IT IS RETIRED. The old bound charged
// `rise + maxStep <= ALLOC_FALL_THRESHOLD_B / 2` and rested on two premises
// the merged-PR audit of #7682 refuted and rf2-2rtt6.141 accepted:
//
//   - `ALLOC_FALL_THRESHOLD_B` is where the first VISIBLE fall appeared. A
//     masked one would not have shown, so it is an UPPER bound on where the
//     first collection runs — and the safety argument needs a LOWER one.
//     Halving an upper bound does not produce a lower one.
//   - `maxStep` was taken as a bound on a masked leg's true allocation, but
//     `maxStep` sees only NET positive deltas, which is precisely what a
//     masked leg does not produce.
//
// The audit wrote two executable probes and the bound ADMITTED both, at
// `headroom = 0`, with true allocations of 300 KB and 600 KB. Retiring is
// therefore not widening: the mechanism did not do the job it named. It is
// route (b) of rf2-2rtt6.140's ruling, which sanctions exactly this
// replacement. Both probes are pinned in `p0_ladder_structural.test.cjs`.
//
// THE REPLACEMENT ASKS THE DATA A QUESTION INSTEAD OF ASSERTING A MODEL.
// The legs of one window are W repetitions of ONE work unit — the same
// event, the same page, the same drain — so absent a collection they should
// be alike. Let `m` be the MEDIAN of the window's work legs:
//
//     REFUSE the window if any leg deviates from `m` by more than τ·m.
//
// Two-sided, on purpose, and the median rather than the mean because the
// first leg of a window is the one most likely to sit high.
//
//   - A leg BELOW its cohort is a leg something removed bytes from. Nothing
//     in the work unit removes bytes; the collector does. That is the
//     observation, and it is why a masked leg — which under-reads by
//     construction — is exactly what this rule catches.
//   - A leg ABOVE its cohort is not evidence of a collection, but it is
//     evidence that the one-work-unit premise the whole witness rests on has
//     failed in this window. Refusing is then correct rather than merely
//     conservative, and refusal is the safe reading either way.
//
// WHAT AN ADMITTED WINDOW IS CERTIFIED TO BE, stated exactly, because a
// witness that oversells itself is worse than none. Under the cohort premise
// — that absent a collection each leg's true allocation lies within τ of `m`
// — a leg can sit τ·m high on its own merits and still be admitted after
// losing a further τ·m. So the certificate reads:
//
//     this window's `rise` under-reads its true allocation by at most 2τ,
//     and here is the worst leg deviation actually observed.
//
// That is a weaker claim than "no collection ran" and a far stronger one
// than the old bound could support, because it is CHECKABLE FROM THE WINDOW
// ITSELF rather than from a premise about where V8 first collects. And it is
// the right shape for this row: a bounded under-read with the bound printed
// beside the figure is the guarantee the row needs.
//
// WHAT IT DOES NOT CLOSE. A window in which EVERY leg is masked by a similar
// amount is homogeneous and passes. rf2-n6w7o already named that hole as
// unreachable in-page — closing it needs a per-leg allocation counter V8
// does not expose. Three things stand against it, none of them a proof: the
// falls gate takes it the moment one collection overshoots; this gate takes
// it the moment one leg runs unmasked; and the controls in the same round
// would read low against their own 8 B/double prediction if the collector
// were running that hard. Masking smaller than τ·m in a single leg is
// invisible BY CONSTRUCTION — that is what the 2τ statement is for. It is a
// bounded under-read, not a silent one.
//
// NOTHING ALLOCATES IN A GAP, so a collection between iterations cannot be
// masked at all: it lands as a negative step and the untouched falls gate
// takes it. `gaps` is recorded as a diagnostic and stays inside `rise`.
//
// τ IS NOT AN ENV KNOB, for `ALLOC_MASK_BUDGET_B`'s reason unchanged: every
// other `P0_ALLOC_*` constant sizes the measurement, and this one decides
// whether a measurement may be PUBLISHED. A gate with a dial on it is a gate
// that gets dialled.
//
// >>> THIS VALUE IS AN UNCALIBRATED PLACEHOLDER. <<<
//
// rf2-2rtt6.141 carried an acceptance criterion into this package from the
// audit's closing line — *a witness whose own reliability is unmeasured is a
// second thing to distrust* — so τ is not chosen by taste. It is to be
// calibrated by VALIDITY WITNESS V3 on windows that are INDEPENDENTLY
// CORROBORATED CLEAN: a dropped `.slice` of D doubles costs a PREDICTED 8D
// bytes, and a control that hit its prediction is positive evidence of no
// collection in a way a zero fall count is not. The two control sizes
// bracket the arms' own magnitude (D=1,000 is 8 KB a leg, D=400 is 3.2 KB),
// so the natural spread is measured at the scale it is applied at. V3 sets τ
// to a stated multiple of the observed worst deviation, records the observed
// figure and the margin here, and this line stops saying PLACEHOLDER.
//
// V3 HAS NOW RUN TWICE AND τ IS STILL NOT PINNED (rf2-e9wr). The placeholder
// line above stands, and what keeps it standing is a measured result rather
// than a pending one.
//
//   PRE-PRIME, 2026-08-14 (PR #8152). The controls' worst relative leg
//   deviation is 0.99%, 11 of 12 windows exactly 0.00%. V3's rule applied to
//   that lands τ near 0.02 - 0.05, and no arm certified there: the arms then
//   carried a fixed ~7 KB FIRST-LEG excess in 336 of 336 windows, which the
//   controls' work unit — a dropped `.slice` — structurally cannot have.
//
//   POST-PRIME, 2026-08-16, on the instrument rf2-oiy1 repaired. The repair
//   holds: the first-leg term reproduces at a median 6,864 B over 72 windows
//   and now sits outside every cohort, and the floor arm — which refused 6 of
//   6 at every page under both writes — certifies in 41 of 72 windows at this
//   placeholder. BUT THE ARMS' SPREAD DID NOT COLLAPSE toward the controls',
//   and that collapse was the whole of the prediction. Across the 47 floor-arm
//   windows with no observed collection the worst relative leg deviation runs
//   0.00% - 1,835.79%; discarding the five six-figure excursions leaves 42
//   windows at 0.00% - 38.91%, median 2.69%. Controls taken on the same
//   instrument in the same session read 0.99% worst, 17 of 18 exactly 0.00%.
//   And the arms' clean windows are not ONE population: in all six runs, all
//   11 round-3 windows with no collection read <= 0.19% while all 11 round-2
//   windows read 2.66% - 20.37%, so τ calibrated on the arms' own data moves
//   by two orders of magnitude with the round it is read at.
//
// SO THE WITNESS IS REPORTED UNUSABLE AS SPECIFIED, which is what V3 itself
// instructs for exactly this case: report the spread rather than picking a τ
// to suit. Taken at face value the arms' clean windows admit no τ below 1 at
// all; reaching 38.91% instead needs a cleanliness rule the arms have no
// prediction to supply, and the τ ≈ 0.8 that would follow makes the 2τ
// certificate below vacuous.
//
// 0.25 IS THEREFORE NOT THE CONSERVATIVE STAND-IN THIS COMMENT USED TO CLAIM.
// That claim assumed V3 would land ABOVE 0.25; it landed below, so 0.25 is 5x
// to 12x MORE permissive than the controls license and tighter than anything
// the arms' own spread would. It is a DECLARED PLACEHOLDER, unchanged, and no
// window is published on it. Nothing in the pinned probes depends on it: their
// offending legs read exactly zero against a strictly positive median, so they
// refuse for every τ < 1, and that property is itself a test.
const ALLOC_LEG_TOLERANCE = 0.25;

// ---------------------------------------------------------------------------
// THE PAGE — MANDATORY, because no honest default survives (rf2-2rtt6.139)
// ---------------------------------------------------------------------------
//
// `ALLOC_B_PER_BOUNDARY_WRITE = 1655` sized the whole allocation arm and is
// RETIRED, effective rf2-2rtt6.139's ruling. Its stated provenance was the
// 2026-08-07 quiet-box run — and that run REFUSED, at 36 falling steps
// across 44 windows, whose own refusal text declares every figure from such
// a window an under-estimate. Sizing off a lower bound licenses a page too
// LARGE, and the 2026-08-08 window found exactly that: 3,731-23,192 B per
// boundary per write measured at B=24 against the 1,655 the arm was sized
// with, 2.3x to 14x.
//
// AND NO REPLACEMENT CONSTANT MAY BE SUBSTITUTED, because the old one
// silently mixed two terms that run then separated: a FIXED per-write cost
// F ~ 24.4 KB that does not scale with B (the floor arm reads it directly:
// 24,108 B/write on reagent-subs, 24,730 on uix-subs) and a genuine
// per-boundary term s(R) that does, and that swings 5x across the HD-002
// ladder (2,031-4,067 B/bnd/write at R=1 up to 6,800-22,174 at R=20). A
// scale-free `c` models neither and is valid only at the page it was
// measured on.
//
// SO THE PAGE IS STATED, NEVER DERIVED. With no sizing model there is no
// honest default, and inventing a literal would be substituting the number
// rf2-2rtt6.139 forbids. An unstated `P0_ALLOC_CELLS` is refused BY NAME in
// `allocArmSizing` below — which has the additional virtue of making an
// accidental publication run impossible while rf2-2rtt6.140 criterion 5's
// measurement freeze is in force. `rf2-2rtt6.139` re-derives sizing PER RUNG
// from V1's floor data and V2's per-rung signal, and may restore a derived
// default on those grounds; this package derives none.
//
// Boundaries per root, and the reason it is a parameter at all: `fx/cells-n`
// is compile-time, so `P0_ROOTS=1` floored B at 300 through the env surface.
// `p0-heap/arm-for` takes it as `:cells`, so the driver states the page.
// `null` — the env var unset or empty — is the unstated case, and it is a
// refusal rather than a default.
const ALLOC_CELLS =
  process.env.P0_ALLOC_CELLS === undefined || process.env.P0_ALLOC_CELLS === ''
    ? null
    : Number(process.env.P0_ALLOC_CELLS);

// The averaging floor. Six is the config the quiet-box run took the
// published witness in, and the smallest window the bead's own re-costing
// table carries; below two there is no averaging in a window at all.
//
// It moves the CONTROLS too, and that is checked rather than assumed: the
// same quiet-box run read 8.13 and 8.20 B/double direct and 8.08
// differential against a predicted 8 at exactly this window, so the claim
// the controls exist to establish — that transient garbage is visible to
// this counter at all — has been corroborated here as well as at the 30
// writes the retired masking budget cited.
//
// IT IS A FLOOR AND NOT ONLY A DEFAULT (rf2-2rtt6.142). It sized the page
// and adjudicated nothing, so the preflight admitted any window from one
// write up: `P0_ROOTS=50` derived a 50-boundary page whose largest legal
// window was two writes, and `P0_ALLOC_WRITES=1` set a one-write window on
// the shipped page. Both sat far under the masking budget — that budget was
// a ceiling on a window's SIZE and had nothing to say about how few writes
// are averaged inside it — so both reached every publication path. The same
// is true of the leg witness that replaced it, and for the same reason: it
// asks whether the legs are alike, not how many there are. One write is
// exactly the configuration whose four fits came back at r² 0.75 / 0.28 /
// 0.94 / 0.31, against the 0.98 floor this row publishes under.
// `allocArmSizing` refuses below this number rather than only deriving from
// it, and rf2-2rtt6.140 leaves that untouched: the averaging floor is not a
// budget question. A one-write window has no averaging in it whatever
// certifies the window.
const ALLOC_MIN_WRITES = 6;

// The window, which is now the averaging floor and nothing else. It used to
// be inverted out of the masking budget — `budget / (B.c) - 1` — and with
// the budget retired there is no bound left to invert. Taking MORE writes
// than the floor is a measurement configuration's business, not a default's.
const ALLOC_WRITES = Number(process.env.P0_ALLOC_WRITES || ALLOC_MIN_WRITES);

// ---------------------------------------------------------------------------
// THE PRIME WORK UNIT — what the driver's own collector costs the first leg
// (rf2-oiy1)
// ---------------------------------------------------------------------------
//
// THE TERM. rf2-2rtt6.140's V1/V2/V3 window measured 336 arm windows across
// eight browser runs and EVERY ONE of them carried a positive first-leg excess
// over its own cohort median — median 6,966 B, p25 6,856, p75 8,056. Constant
// in ABSOLUTE bytes across B in {4, 24, 96}, identical under `:p0/write-all`
// and `:p0/write-page` alike, and fatal: at τ = 0.25 a fixed ~7 KB excess
// refuses every window whose leg median is under ~27,900 B, which is every
// floor window at every page size. `arm − floor` is the quantity every witness
// here is stated over, so a floor that never certifies takes the ladder with
// it.
//
// WHICH OF THE TWO CAUSES IT IS, SETTLED FROM THAT WINDOW'S OWN NUMBERS rather
// than from a new one. The bead left the fork open — warm-up, or re-allocation
// of something the forced collection reclaimed — and three facts already in
// the record close it:
//
//   1. THE TAIL LEGS ARE BYTE-IDENTICAL. One B = 4 floor window read
//      [26044, 19256, 19256, 19256, 19256, 19256]. Steady state is therefore
//      reached after exactly ONE work unit inside the window.
//   2. EIGHTEEN WORK UNITS ALREADY RUN IMMEDIATELY BEFORE IT — three
//      full-size warm-up windows of six writes each — and the excess survives
//      every one of them.
//   3. The ONLY thing standing between the last warm-up work unit and leg 1 is
//      `gc()` below: three CDP `HeapProfiler.collectGarbage` calls with an
//      80 ms beat, which Blink implements as `Isolate::LowMemoryNotification()`
//      rather than as one collection.
//
// One work unit AFTER the collection clears it; eighteen BEFORE it do not. So
// the excess is created at the collection and re-cleared by the first work
// unit that follows — the bead's branch (b). Branch (a), a fourth full-size
// warm-up, is REFUTED rather than merely doubted: another warm-up lands on the
// wrong side of the collection, where three have already failed.
//
// WHAT THE COLLECTION DISCARDS IS NOT IDENTIFIED HERE, and identifying it
// would take a browser window this package may not spend. It does not have to
// be: fact 1 says one work unit restores whatever it is.
//
// SO THE WINDOW DRIVES ONE EXTRA WORK UNIT AND THE FIRST IS A PRIME. It is
// SAMPLED, REPORTED and EXCLUDED — from the leg cohort, from `rise`, from
// `falls`, from `perWrite` and from the certificate. The instrument does not
// go blind to the term: it publishes it beside the figures as a diagnostic,
// which is also what puts the number in front of rf2-e9wr's τ calibration.
//
// WHY THIS IS NOT A WIDENING, AND WHY IT IS NEITHER OF THE OTHER TWO REPAIRS:
//
//   - τ IS UNTOUCHED at its uncalibrated placeholder, and no window refused
//     today for any other reason is admitted tomorrow. A widening ADMITS the
//     observation; this removes it from the measured region.
//   - "DISCARD LEG 1, CERTIFY OVER W − 1" would leave five averaged writes,
//     under `ALLOC_MIN_WRITES`. Averaging six means driving seven.
//   - "PRIME OUTSIDE THE WINDOW, UNSAMPLED" costs exactly the same headroom —
//     the prime's garbage lands in the same uncollected heap either way — and
//     buys blindness with it. Sampling it is strictly better.
//
// WHAT IT COSTS, STATED: one more write's garbage per window, so the heap has
// ~1/6 more in it before the measured region opens. That is real against the
// 600,000 B measured collection onset and it is not hidden — a window that
// then collects is refused by the falls gate exactly as one is today.
//
// THE 2τ CERTIFICATE IS RE-DERIVED AND COMES OUT IDENTICAL. Its derivation is
// per-leg over a cohort of repetitions of one work unit and never referenced
// how many legs there are or what preceded them. What changes in the
// antecedent is which region is submitted to the rule — and the prime is what
// makes "repetitions of ONE work unit" true of that region for the first time.
// `allocSteps` is not touched: the split hands it a shorter, well-formed
// sample stream, and every one of its pins stands unedited.
const ALLOC_PRIME_WRITES = 1;

// What the window actually drives. The DIVISORS stay `ALLOC_WRITES`: the
// published quantity is per MEASURED write and the prime is not one.
const ALLOC_WINDOW_WRITES = ALLOC_WRITES + ALLOC_PRIME_WRITES;

// ---------------------------------------------------------------------------
// THE BY-SITE MODE — attributing a leg's bytes to a site in the work unit
// (rf2-rs8q6)
// ---------------------------------------------------------------------------
//
// THE OBSERVATION IT EXISTS FOR. rf2-e9wr's window measured 72 floor-arm
// windows and found the relative dispersion of a window's MEASURED work legs
// to be a function of the ROUND INDEX — not of the page, the write or the
// substrate. Restricting to windows with no observed collection: round 2
// (n=11) reads 2.66%–20.37% with none below 2.66%, and round 3 (n=11) reads
// 0.00%–0.19% with all at or below 0.19%. That holds in each of six
// independent browser launches separately, so machine load cannot be it, and
// the excesses are POSITIVE legs against a byte-identical cohort with zero
// falling steps — allocation by the page's own heap rather than a collector
// artefact or another process.
//
// WHY THE SHIPPED INSTRUMENT CANNOT IDENTIFY IT, as a matter of shape rather
// than of effort. At the shipped stride a leg is ONE step: the counter is read
// before the work unit and after it, and everything between is one number. A
// mechanism is a claim about WHICH PART of the work unit allocates, and no
// analysis of a scalar per leg can answer it. The bead says so — "identifying
// the mechanism needs an instrument a measurement window may not build
// mid-window" — and this is that instrument.
//
// WHAT IT DOES. `P0_ALLOC_BY_SITE=1` drives the window at a stride of 3: one
// extra counter reading at the work unit's ONE seam, between the
// `dispatch-sync` and the `flushSync` drain. Leg `k` then decomposes as
// `dispatch_k + drain_k`, exactly, by construction, over the same outer pair
// the shipped window reads.
//
// WHAT IT DELIBERATELY DOES NOT DO, and each is a rule this row already holds:
//
//   - IT IS OFF BY DEFAULT AND PUBLISHES NOTHING. Every row taken without the
//     switch is byte-identical to today, and `allocSiteSplit` below is the
//     identity on a stride-2 stream — which is what lets every `allocSteps`
//     and `allocPrimeSplit` pin stand unedited, exactly as rf2-oiy1's split
//     did.
//   - IT DOES NOT TOUCH τ. `ALLOC_LEG_TOLERANCE` is unmoved, in either
//     direction. rf2-e9wr established that no honest calibration exists on
//     the arms' data, and an instrument that answered the question by
//     widening the gate would be answering a different one.
//   - IT DOES NOT ADJUDICATE. The certificate is read off the COLLAPSED
//     stride-2 stream, so `allocSteps` never sees a by-site stream and the
//     gate is the same gate. A mid-leg sample would otherwise split one
//     step into two and change `rise`, `falls` and `maxStep` — published
//     quantities — for a diagnostic's convenience.
//
// WHAT IT COSTS, STATED. One more `mem` read per leg inside the measured
// region. The `idle` control is driven at the SAME stride and prices it: an
// idle leg's two site figures are one sampler read's own footprint and
// nothing else. Being a constant per leg it can neither create nor destroy
// dispersion, which is the quantity under investigation — but it does mean
// absolute leg magnitudes at stride 3 are not comparable byte for byte with
// those at stride 2, and the falsifiable form of that claim is that the
// idle control's leg difference between the two strides accounts for the
// whole of the arms'. A window can check it; this bead builds it.
const ALLOC_BY_SITE = process.env.P0_ALLOC_BY_SITE === '1';

// Samples per iteration. 2 is the shipped window and the page defaults to it;
// 3 opens the seam. Sent to `allocPrepare`, which SIZES THE BUFFER from it —
// one number, one place, so the stride the page indexes with and the stride
// the buffer was sized for cannot disagree.
const ALLOC_SITES = ALLOC_BY_SITE ? 3 : 2;

// The site names, in the order the work unit runs them, as ONE spelling the
// driver, the summary and the pins all read. `dispatch` is the `dispatch-sync`
// through the event pipeline and the signal graph; `drain` is the `flushSync`
// commit. There is no third: see `p0-heap/alloc-window!` for why a finer split
// would mean instrumenting re-frame from inside the arm, and an arm carrying
// instrumentation is not the arm whose figures are published.
const ALLOC_SITE_NAMES = ['dispatch', 'drain'];

// The sizing, as a PURE FUNCTION of the config — the same shape and for the
// same reason as `allocSteps` and `allocRefusedWindows`: it needs neither a
// release build nor a Chromium, so it is pinned on every PR by
// `p0_ladder_structural.test.cjs` instead of waiting for the next opt-in run
// of this driver to notice that an arm drifted.
//
// NOTHING HERE PREDICTS A WINDOW'S SIZE ANY MORE. `predictedWindowB`,
// `headroom`, `maxBoundaries` and `floorBoundaries` went with the budget and
// with `ALLOC_B_PER_BOUNDARY_WRITE`, because every one of them was that
// constant's arithmetic wearing a different name. What is left refuses only
// on grounds it can defend WITHOUT a sizing model — rf2-2rtt6.139's interim
// posture, stated on that bead: a window spent on a page that then refuses
// is acceptable; a gatekeeper enforcing a model the data contradicts is not.
//
// `refusals` IS THE VERDICT, one entry per thing wrong with the arm, and
// `admissible` is just "none of them" (rf2-2rtt6.142). One boolean was not
// enough because the ways an arm can be wrong want different repairs, and an
// arm can be wrong in more than one at once.
function allocArmSizing({ writes, roots, cells }) {
  // `null`/`undefined` is the page NOT STATED, which is distinct from a page
  // stated as zero: one is a missing configuration and the other is a page
  // with nothing on it. They refuse for different reasons and say so.
  const stated = cells !== null && cells !== undefined;
  const boundaries = stated ? roots * cells : null;

  const refusals = [];
  if (!stated) {
    refusals.push(
      `the allocation row's page is not derivable until rf2-2rtt6.139 re-derives sizing from ` +
        `the new instrument's own floor data: STATE P0_ALLOC_CELLS (boundaries per root; the ` +
        `page is P0_ROOTS x P0_ALLOC_CELLS). ALLOC_B_PER_BOUNDARY_WRITE was retired because it ` +
        `was read off a run that itself refused, and no replacement constant may be substituted`
    );
  } else if (boundaries < 1) {
    // No per-boundary quantity to publish. It would otherwise sail through on
    // zero bytes, which is admitting nothing measured at all.
    refusals.push(
      `a page of ${boundaries} boundaries (${roots} roots x ${cells} cells) has no ` +
        `per-boundary quantity to publish: RAISE P0_ROOTS or P0_ALLOC_CELLS`
    );
  }
  // Independent of the page, and deliberately so: with no page-size model
  // there is nothing to say about what a given page admits, so this refusal
  // no longer branches on one. rf2-2rtt6.142's endorsed property — never
  // advise the operator to configure the very shape being refused — survives
  // the collapse intact, because the collapsed message names no shape at all.
  if (writes < ALLOC_MIN_WRITES) {
    refusals.push(
      `a window of ${writes} write(s) is under the ${ALLOC_MIN_WRITES}-write averaging floor ` +
        `(ALLOC_MIN_WRITES) and has too little in it to average: at one write per window this ` +
        `row's four fits came back at r² 0.75 / 0.28 / 0.94 / 0.31, under its 0.98 floor. ` +
        `RAISE P0_ALLOC_WRITES to at least ${ALLOC_MIN_WRITES}, or unset it and take the floor`
    );
  }

  return {
    cells: stated ? cells : null,
    roots,
    boundaries,
    writes,
    refusals,
    admissible: refusals.length === 0,
  };
}

const ALLOC_ARM = allocArmSizing({ writes: ALLOC_WRITES, roots: ROOTS, cells: ALLOC_CELLS });

// ---------------------------------------------------------------------------
// THE MEASUREMENT SURFACE (rf2-gxrr) — the two switches the validity
// witnesses are configured through, and nothing else
// ---------------------------------------------------------------------------
//
// PR #7702 landed the artefacts V1-V4 judge and said honestly that neither had
// been executed against a real page. What it did not land, and did not claim
// to, is the surface that lets V1 and V3 be RUN: the granted measurement
// window for rf2-2rtt6.140 refused before a browser was launched because
// neither shape could be configured on the shipped instrument.
//
// NOTHING HERE IS A GATE, A BAND OR A THRESHOLD. `ALLOC_MIN_WRITES` stays 6,
// `ALLOC_FALL_THRESHOLD_B` stays 600,000, `ALLOC_LEG_TOLERANCE` stays the
// uncalibrated placeholder V3 exists to replace, and the preflight refusals
// above bite on every mode below exactly as they bite today. What changes is
// that the instrument can be configured into the shapes its own design brief
// specifies — and only those shapes.
//
// --- THE WRITE (V1's control, and V2's comparison) -------------------------
//
// V1 measures each page "under `:p0/write-all` and under `:p0/write-page`"
// (allocation-instrument-rework.md:256). `F_old` is not decoration: it is
// V1's CONTROL — "it says the rig has not moved under the instrument, and it
// is the only way the two writes can be compared like for like" (:260-263) —
// and criterion 6 turns on it (:631). `arms/write-all!` has been a public
// door since rf2-2rtt6.76 and the clock, bulk, fan-out and retention rows all
// drive it; what was missing was any route to it FROM THE ALLOCATION WINDOW.
//
// `page` IS THE DEFAULT AND EVERY PUBLISHED ROW IS TAKEN UNDER IT. `all` is
// reachable only by naming it here, the record carries which one was driven,
// and the summary prints it beside the page — criterion 6's separation is
// that a reader of any row can tell FROM THE ROW which write produced it, and
// a row that names its own write satisfies that more strongly than a row that
// could only ever have had one.
const ALLOC_WRITE_SPECS = {
  page: {
    kind: 'write',
    event: ':p0/write-page',
    note: 'the grid is rebuilt at the width the mounted page reads — one cell per boundary',
  },
  all: {
    kind: 'write-all',
    event: ':p0/write-all',
    note:
      "the grid is rebuilt at the fixture's fixed `cells-n` whatever is mounted — V1's F_old " +
      'control, flat in B by construction',
  },
};
// --- AND `paired`, WHICH DRIVES BOTH IN ONE PROCESS (rf2-irxrw) ------------
//
// V1/V2 require the two writes to be compared as a SAME-PAGE, SAME-RUN pair
// (allocation-instrument-rework.md:231-232), and until this switch the
// instrument could not do it: the resolved kind was ONE spec, passed into
// every arm window of the run, so a `write-all` versus `write-page`
// comparison was necessarily a difference of two sequential PROCESS runs in
// a fixed order. rf2-0gjqi's re-analysis is what that cost — eight mid-rung
// sign comparisons that share one run order, one floor per segment per
// write, and a page-global floor level (rf2-77gz8) that moves both segments
// by the same amount, so eight cells are one draw rather than eight.
//
// THE SELECTION IS THEREFORE A LIST, NOT A SPEC. There are still exactly TWO
// writes — `ALLOC_WRITE_SPECS` above is unchanged and `paired` is not a third
// one — and what `P0_ALLOC_WRITE` names is which of them this run drives, in
// what order. `page` and `all` name one each and behave exactly as they did;
// `paired` names both, and the arm pass below runs once per leg inside every
// round, on the same page, in the same process.
const ALLOC_WRITE_SELECTIONS = {
  page: ['page'],
  all: ['all'],
  paired: ['page', 'all'],
};
const ALLOC_WRITE = process.env.P0_ALLOC_WRITE || 'page';
// `undefined` for an unknown switch, and the preflight refuses on it BY NAME
// rather than this line throwing: requiring this module must never drive it
// (`p0_ladder_structural.test.cjs` requires it on every PR), so the refusal
// belongs where every other one already is — before a browser is launched.
const ALLOC_WRITE_KEYS = ALLOC_WRITE_SELECTIONS[ALLOC_WRITE];
const ALLOC_WRITE_LEGS =
  ALLOC_WRITE_KEYS &&
  ALLOC_WRITE_KEYS.map((selector) => ({ selector, spec: ALLOC_WRITE_SPECS[selector] }));
const ALLOC_WRITE_PAIRED = ALLOC_WRITE_LEGS !== undefined && ALLOC_WRITE_LEGS.length > 1;
// THE SINGLE SPEC, WHERE THERE IS ONE. `page` and `all` resolve to the same
// object they always did — every consumer below reads the LEGS, and this is
// kept because the surface's own pins read it, and because "this run drives
// exactly one write, and it is this one" is a real question with a real
// answer under two of the three selections. It is `undefined` under `paired`
// for the same reason it is `undefined` under a typo: there is no ONE write.
// The two are told apart by `ALLOC_WRITE_LEGS`, and the preflight refuses on
// that rather than on this.
const ALLOC_WRITE_SPEC =
  ALLOC_WRITE_LEGS && ALLOC_WRITE_LEGS.length === 1 ? ALLOC_WRITE_LEGS[0].spec : undefined;

// WHERE A WINDOW IS RECORDED (rf2-irxrw). Off `paired` this is the identity,
// so a published run's record is keyed exactly as it always was; on it, the
// two legs of a pair are two windows and each is keyed by the write it drove.
// The pair itself is not left to be reconstructed from the string: every
// recorded window carries `pairKey` — the arm key its legs share — beside the
// write it names, so a later estimator groups on a field rather than parsing
// one.
function allocWindowKey(armKey, selector, paired = ALLOC_WRITE_PAIRED) {
  return paired ? `${armKey}@${selector}` : armKey;
}

// --- THE PLAN (V3's controls-only, and V1's floor-only) --------------------
//
// V3 is "the controls only, at both D values, six rounds, six writes a
// window — the row's existing control path ... No arms, no browser page
// beyond the one the controls already need" (:443-445). `--only alloc` had
// exactly one shape: preflight, then the controls AND the full ladder plan
// (floor + 4 rungs x 2 substrates x 2 segments) every round. Harvesting V3's
// 18 control windows meant riding ~108 ladder-arm windows, and criterion 5
// freezes every allocation window that is not V1-V4 — which is why this is a
// mode and not a workaround.
//
// V1's "floor arm only — no ladder rungs, no fits, no candidate" (:253) is
// the same switch's middle setting rather than a second mechanism, exactly as
// rf2-gxrr directs: it is survivable without new code, but only by paying the
// whole ladder at each of three pages to obtain one arm.
//
// `full` IS THE DEFAULT AND IS TODAY'S RUN, unchanged in every particular.
// The narrower plans SUBTRACT arms; they add none, move none and reorder
// none, so a floor read under `floor` is the same floor, mounted on the same
// page, in the same round parity, as a floor read under `full`.
const ALLOC_PLAN_SHAPES = {
  full: { arms: true, rungs: true, fits: true },
  floor: { arms: true, rungs: false, fits: false },
  controls: { arms: false, rungs: false, fits: false },
};
const ALLOC_PLAN = process.env.P0_ALLOC_PLAN || 'full';
const ALLOC_PLAN_SHAPE = ALLOC_PLAN_SHAPES[ALLOC_PLAN];

// THE SEGMENT ORDER — AND THE CONFOUND IT EXISTS TO BREAK (rf2-rs8q6).
//
// The arms of a round are driven segment by segment, and under `parity` the
// segment order REVERSES on odd rounds. That flip is deliberate and it is
// what de-confounds the substrate comparison: two segments admit two orders,
// and a single-order run cannot tell a substrate apart from the slot it was
// driven in.
//
// But it builds in a second relation nobody chose. Under `parity` the window
// sequence is `A B | B A | A B | B A | ...`, so the FIRST arm window of every
// round repeats the substrate of the LAST arm window of the round before it,
// and the second always switches. `rf2-rs8q6`'s record checked this rather
// than assuming it: over the 466 adjacent window pairs in the 14 committed
// floor runs, "position in round is 0" and "substrate repeated from the
// previous window" agree 466 times out of 466. The ~748 B rider that record
// isolated sits on position 0, and no committed dataset can say which of the
// two it follows.
//
// `fixed` drives the configured order EVERY round. The sequence becomes
// `A B | A B | A B | ...`, so BOTH positions now follow a substrate switch —
// position 0 across the round boundary, position 1 within the round — while
// position 0 alone still follows the round's three control windows. The two
// relations stop coinciding, which is the whole of what this switch is for.
//
// WHAT EACH OUTCOME SETTLES, pre-registered here rather than chosen after the
// run, since the mode has three distinguishable answers and not two:
//
//   - the rider STAYS position-locked at 0  -> the carrier is the position
//     itself (the first arm after the round's three controls), not the
//     substrate relation, which is now constant across both positions;
//   - the rider appears at BOTH positions   -> the carrier is the substrate
//     SWITCH, which under `parity` was position 1's property;
//   - the rider VANISHES                    -> the carrier is the substrate
//     REPEAT, which `fixed` removes from the schedule altogether.
//
// IT IS OFF BY DEFAULT AND `parity` IS THE IDENTITY. `allocSegmentOrder` is
// the pre-bead expression verbatim under `parity`, so every published row is
// taken on the schedule it always was, and no gate, band, threshold or budget
// constant is touched in either mode. τ is untouched, in either direction.
//
// AND A `fixed` ROW IS A DIAGNOSTIC ROW, NOT A PUBLISHABLE ONE. It gives up
// exactly the property the flip was landed for: with one order the substrate
// is confounded with the slot, so a `fixed` run may not be quoted for a
// between-substrate comparison. The row states which order it was taken under
// (`segOrder`) and each round states the order it actually drove
// (`segments`), on criterion 6's rule that a reader of any row can tell FROM
// THE ROW how it was taken.
//
// THE WRITE-LEG ORDER IS NOT TOUCHED HERE. It has its own mode below
// (`ALLOC_PASS_ORDERS`, rf2-fk6pj) because it carries its own within-round
// position confound, which is a different question with a different
// discriminator; under `P0_ALLOC_WRITE=all` — the selection every floor
// corpus is taken under — there is ONE leg and any flip is inert.
//
// AND `fixed-reversed`, THE ARM `fixed` ALONE CANNOT SUPPLY (rf2-csca8).
// `fixed` drives ONE orientation, so inside a `fixed` run the substrate and
// the within-round position are perfectly confounded again — with the plan as
// shipped, `uix-subs` is position 1 in every `fixed` window there has ever
// been. The 1,050-1,224 B cluster that bead names sits at 8 of 38 such
// windows, and 8 of 38 is equally consistent with "uix under `fixed`" and
// with "the SECOND-driven arm under `fixed`". No committed run separates
// them. On the ADMISSIBLE corpus PR #8593's analysis read — 116 runs, 3,090
// positional windows, two control-refused runs excluded — POSITION came out
// UNRESOLVED (parity uix 8/733 at position 0 against 3/712 at position 1,
// Fisher two-sided p = 0.2253) and SUBSTRATE ASSOCIATED BUT NOT NECESSARY
// (reagent carries the term twice in 1,564). p = 0.2253 IS A FAILURE TO
// REJECT AND NOT A REFUTATION: on eleven in-band windows nothing there bounds
// a position effect at any useful width, so POSITION was left standing rather
// than knocked out, and NONE of the three candidates was eliminated. What
// that analysis could not supply was a CONTRAST — uix at position 0 with the
// mode held still — and the mode cannot be read against itself from one
// orientation.
//
// `fixed-reversed` drives the plan REVERSED every round, so it puts the
// second substrate at position 0 while HOLDING THE MODE CONSTANT. That is
// the one arrangement the corpus lacks. Parity already supplies uix at
// position 0 for 733 windows, but it supplies it UNDER PARITY, which is the
// term the contrast is trying to hold still.
//
// WHAT EACH OUTCOME SETTLES, pre-registered here rather than chosen after the
// run, on the same rule as the three above:
//
//   - the cluster FOLLOWS uix to position 0  -> the carrier is the SUBSTRATE
//     under `fixed`, and the position reading of 8/38 is a coincidence of
//     the shipped plan's order;
//   - the cluster STAYS at position 1        -> the carrier is the
//     SECOND-DRIVEN slot under `fixed`, whichever substrate occupies it;
//   - the cluster appears at BOTH or NEITHER -> neither property is the
//     carrier as stated, and the segment order has said all it can say.
//
// IT IS A DIAGNOSTIC ROW ON `fixed`'s OWN TERMS. Everything the paragraph
// above says about a `fixed` row — that it gives up the between-substrate
// comparison and may not be quoted for one — holds here unchanged, and the
// row states which of the three orders it was taken under (`segOrder`) for
// exactly that reason. No gate, band, threshold or budget constant moves in
// any of the three, and τ is untouched in either direction.
const ALLOC_SEG_ORDERS = ['parity', 'fixed', 'fixed-reversed'];
const ALLOC_SEG_ORDER = process.env.P0_ALLOC_SEG_ORDER || 'parity';

// THE CONTROL SLOT — THE LAST CONFOUND THE SCHEDULE BUILDS IN (rf2-rs8q6).
//
// `P0_ALLOC_SEG_ORDER=fixed` established that the ~748 B rider follows the arm
// window's POSITION IN ITS ROUND and not any relation to the substrate driven
// before it. That left exactly one pair of properties still coinciding, and
// the record said so rather than glossing it: position 0 is BOTH
//
//   (A) the first arm window after the round's THREE CONTROL WINDOWS, and
//   (B) the first arm window after the ROUND-LOOP BOUNDARY,
//
// because the loop body opens with `controlOf('idle', 0)` and nothing else
// sits at a round boundary.
//
// AND `last` ALONE DOES NOT SEPARATE THEM. This is worth stating in the source
// because the obvious reading of "move the controls after the arms" is that it
// does. The round loop is CYCLIC, so moving the three controls to the end of
// round r puts them immediately before the first arm of round r + 1 — the
// stream `I C C A0 A1 | I C C A0 A1 | ...` becomes `A0 A1 I C C | A0 A1 I C C
// | ...`, which is the SAME cyclic sequence phase-shifted. Property (A) still
// attaches to position 0 in every round but the first. `last` separates (A)
// from (B) in exactly ONE window per run — round 0's position 0, which under
// `last` is the first arm window of the whole run and has no controls before
// it at all.
//
// SO THE MODE CARRIES THREE SLOTS AND NOT TWO, and which is the discriminator
// is stated here rather than chosen after the run:
//
//   - `first` — the pre-bead schedule verbatim. (A) and (B) coincide at
//     position 0. Every published row is taken under it.
//   - `last`  — the phase shift. It is the CONSISTENCY CONTROL on the mode
//     itself: it must reproduce `first`, because it drives the same cyclic
//     stream. A schedule that merely re-orders the driver's calls and moves
//     the result would mean the carrier is a property of the run's head or
//     tail rather than of the round, which is a different finding.
//   - `mid`   — the DISCRIMINATOR. The three controls are driven after the
//     round's FIRST arm pass, so the stream is `A0 I C C A1 | A0 I C C A1 |
//     ...`. Now (A) is position 1 and (B) is position 0, at every round, at
//     full n. They are separated on every window rather than on one.
//
// WHAT EACH OUTCOME SETTLES UNDER `mid`, pre-registered:
//
//   - the rider MOVES TO POSITION 1   -> the carrier is (A), the controls: an
//     arm window that follows a run of non-dispatching windows;
//   - the rider STAYS AT POSITION 0   -> the carrier is (B), the round-opening
//     position itself, which under `mid` follows an ARM window directly;
//   - the rider appears at BOTH or at NEITHER -> neither property is the
//     carrier as stated, and the schedule has said all it can say.
//
// IT IS OFF BY DEFAULT AND `first` IS THE IDENTITY. Under `first` the driver
// makes exactly the calls it made before, in the same order — the three
// controls, then the passes — so every published row is taken on the schedule
// it always was. No gate, band, threshold or budget constant is touched in any
// slot. τ is untouched, in either direction.
//
// AND A MOVED-CONTROL ROW IS A DIAGNOSTIC ROW, NOT A PUBLISHABLE ONE. The
// controls are the row's null arm and the studio pages read them against the
// arms window for window; a row whose controls were taken at a different point
// in the round is not the row those comparisons were made on. The row states
// which slot it was taken under (`controlSlot`) and each round states the
// window order it actually drove (`windowOrder`), on criterion 6's rule that a
// reader of any row can tell FROM THE ROW how it was taken.
const ALLOC_CONTROL_SLOTS = ['first', 'mid', 'last'];
const ALLOC_CONTROL_SLOT = process.env.P0_ALLOC_CONTROL_SLOT || 'first';

// Pure, for `allocSegmentOrder`'s reason: it needs neither a release build nor
// a Chromium, so the pin can DRIVE all three slots over a pass count and read
// the multi-round window sequence out, rather than read the source and hope.
//
// The index is into the round's PASS sequence and names the pass the three
// controls are driven BEFORE. `passCount` is the return for `last`, which is
// "before no pass at all" — i.e. after every one of them.
function allocControlIndex(passCount, slot) {
  if (slot === 'last') return passCount;
  // `mid` is "after the round's FIRST arm pass" and not "at the halfway
  // point". The floor plan drives two passes and the two readings coincide,
  // but a `paired` write drives four, and it is the FIRST pass that carries
  // (B) — so anchoring at 1 keeps (A) and (B) separated whatever the pass
  // count, where a halfway anchor would put three arms between them.
  if (slot === 'mid') return Math.min(1, passCount);
  return 0;
}

// The flattened window sequence ONE ROUND drives, in drive order, as the kinds
// a reader cares about: `control` for each of the three, `arm` for each pass.
// Pure and driven by the pin for the reason above — the two properties this
// mode exists to separate are claims about a SEQUENCE across rounds, and
// neither is readable off a ternary in the source.
function allocRoundWindowKinds(passCount, slot) {
  const at = allocControlIndex(passCount, slot);
  const out = [];
  for (let i = 0; i < passCount; i++) {
    if (i === at) out.push('control', 'control', 'control');
    out.push('arm');
  }
  if (at >= passCount) out.push('control', 'control', 'control');
  return out;
}

// Pure, for `allocPlanArms`'s reason: it needs neither a release build nor a
// Chromium, so the pin can DRIVE both modes over a plan and read the six-round
// sequence out, rather than read the source and hope.
function allocSegmentOrder(plan, round, order) {
  if (order === 'fixed') return plan;
  // AND ITS REVERSED COUNTERPART (rf2-csca8), which is `fixed` in the other
  // orientation and nothing else: the same plan every round, so the mode is
  // held constant, with the substrate that `fixed` pins to position 1 now at
  // position 0. A fresh array per round rather than a cached one, for the
  // reason `parity`'s odd branch takes one — the plan the caller handed in is
  // the shipped ladder plan and this function may not reorder it in place.
  if (order === 'fixed-reversed') return [...plan].reverse();
  return round % 2 === 0 ? plan : [...plan].slice().reverse();
}

// ---------------------------------------------------------------------------
// THE WRITE-LEG ORDER, AND WHY IT MAY NOT KEEP RIDING ON ROUND PARITY
// (rf2-fk6pj)
// ---------------------------------------------------------------------------
//
// Under `P0_ALLOC_WRITE=paired` the two write legs of every arm run as two
// passes inside ONE round, and the round loop below alternates which leads.
// rf2-0gjqi's paired window measured that THE PASS THAT RAN SECOND READS
// LOWER — 10 of 12 round blocks, 6 of 6 in run 1 and 4 of 6 in run 2, median
// second-minus-first −0.59%, WHICHEVER WRITE OCCUPIED IT. Decomposed under an
// additive position model the pass-order half-difference reads +0.68% and
// +0.21%, the same sign on both runs, while the order-free write half-sum
// reads −0.33% and +0.24%, opposite signs. It is the class of term that
// manufactured this instrument's last inferential finding, and it is still in
// the instrument.
//
// AND THE INSTRUMENT CANNOT CURRENTLY ANSWER WHY, because the alternation is
// `round % 2`: EVERY page-first round is an even round. Any other even/odd
// property of a round that acts differently on a first and a second pass —
// and a round is a long-lived, stateful thing — reads EXACTLY the same way.
// The pass-position term and every other parity-indexed term are one column
// in the design matrix, and no estimator over a parity-driven corpus can
// split them. The other committed corpora do not break the tie either:
// `segorder-rs8q6` varies the SEGMENT order and `ctrlslot-rs8q6` varies the
// control SLOT, and neither reaches the write-leg order.
//
// `seeded` DRAWS THE LEG ORDER FROM A SEED AND THE ROUND INDEX INSTEAD, which
// separates the two in one window: the leg order still varies round to round,
// but it is no longer a function of round parity, so a term that tracks the
// pass position and a term that tracks the parity now load on different
// columns. `parity` is the pre-bead expression verbatim and stays the
// default, so every committed corpus is read under the rule it was taken
// under and no published row is reinterpreted.
//
// THE DRAW IS BALANCED, AND THAT IS NOT A REFINEMENT. `parity` guarantees
// each leg leads exactly half the rounds, and that balance is what keeps the
// WRITE from being confounded with the pass position — the defect the
// alternation was landed for. An unbalanced Bernoulli draw would break the
// parity tie by reintroducing the write/position confound, which is trading
// one column for a worse one. So the schedule is a seeded permutation of a
// BALANCED multiset: `floor(rounds / 2)` flipped rounds, exactly as parity
// gives.
//
// AND A DRAW THAT REPRODUCES PARITY IS REDRAWN. Of the twenty balanced
// six-round schedules, two ARE the parity schedules — the alternating one and
// its complement — and a window that drew one of those would separate
// nothing while costing a full allocation window. They are rejected and the
// draw repeated. This needs a third balanced schedule to exist at all, which
// it does from THREE rounds up: at three the balanced set is the three
// single-flip schedules and only one of them alternates, while at two rounds
// BOTH balanced schedules are parity schedules and the mode can separate
// nothing. `parityTied` reports that, the preflight refuses on it, and the
// pin drives both sides of the boundary rather than taking this paragraph's
// word for where it falls.
//
// THE SEED IS RECORDED (`passSeed`), and that is what makes a `seeded` window
// re-readable at all: the schedule is not recoverable from the mode name, so
// a record that stated only `seeded` would be a record whose own schedule was
// unknown. Re-passing the recorded seed reproduces the schedule exactly. The
// round record also states the order it ACTUALLY drove, in `writeLegs`, for
// the reason it always did — an estimator must never recompute the rule.
//
// IT IS `PASS` AND NOT `LEG`, AND THAT IS DELIBERATE. The obvious env name
// for a mode over the write legs would put it under the same `P0_ALLOC_`
// prefix that `ALLOC_LEG_TOLERANCE` would take if it ever acquired an env
// route — and that prefix is BANNED, in a pin that refuses any occurrence of
// it ANYWHERE in this file, comments included. τ decides whether a window may
// be PUBLISHED, and this file's standing rule is that a gate with a dial on it
// is a gate that gets dialled off; a switch sharing the prefix is one
// careless grep away from looking like exactly that dial. `pass` is also the
// more accurate word for what is ordered: the round loop drives `passes`, and
// the measured term is a PASS-POSITION term. (The ban is the reason this
// paragraph names no token — writing the forbidden one to explain the ban
// trips it, which is how it was found.)
const ALLOC_PASS_ORDERS = ['parity', 'seeded'];
const ALLOC_PASS_ORDER = process.env.P0_ALLOC_PASS_ORDER || 'parity';
// Drawn when it is not given, and recorded either way. It is INERT under
// `parity` — the default, and the mode every published row was taken under —
// and the record states the mode beside it so no reader can take a seed for
// evidence that a seeded schedule ran.
const ALLOC_PASS_SEED = process.env.P0_ALLOC_PASS_SEED || String(Date.now());

// FNV-1a over the seed text, so the seed may be any string an operator finds
// memorable rather than a number they have to invent. Pure and total.
function allocSeedHash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

// mulberry32 — a small, fully specified, deterministic 32-bit generator. The
// requirement here is REPRODUCIBILITY from a recorded seed and nothing more:
// no statistical quality claim is made or needed, because the schedule it
// draws is checked for the one property the mode exists for.
function allocSeedRandom(state) {
  let s = state >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// TRUE where the round drives its legs REVERSED. Pure, for `allocSegmentOrder`'s
// reason: it needs neither a release build nor a Chromium, so the pin can DRIVE
// the schedule over a seed and a round count and read its properties out —
// balance, and independence from parity — rather than read the source and hope.
function allocPassFlips(rounds, seed) {
  const flipped = Math.floor(rounds / 2);
  const base = Array.from({ length: rounds }, (_, i) => i < flipped);
  // Parity is what this mode exists to escape, so a schedule that is a
  // function of round parity is not a schedule this mode may return — the
  // alternating one and its complement alike. An untied one exists from THREE
  // rounds up; at two or fewer `parityTied` is the honest answer and the
  // caller's window separates nothing.
  const tiedToParity = (flips) =>
    flips.every((f, r) => f === (r % 2 === 1)) || flips.every((f, r) => f === (r % 2 === 0));
  const rand = allocSeedRandom(allocSeedHash(String(seed)));
  // Bounded, because an unbounded redraw on a round count that admits no
  // untied schedule would hang the run rather than tell the operator.
  for (let attempt = 0; attempt < 64; attempt++) {
    const flips = base.slice();
    for (let i = flips.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1));
      const t = flips[i];
      flips[i] = flips[j];
      flips[j] = t;
    }
    if (!tiedToParity(flips)) return { flips, attempts: attempt + 1, parityTied: false };
  }
  return { flips: base.slice(), attempts: 64, parityTied: true };
}

// The legs ONE ROUND drives, in drive order. `parity` is the pre-bead
// expression verbatim, to the character; `seeded` indexes the drawn schedule.
// Pure for the reason above, and total on a schedule shorter than the round
// index — a run that drove more rounds than it drew for falls back to parity
// for that round rather than driving `undefined`.
function allocPassOrder(legs, round, order, schedule) {
  const seeded = order === 'seeded' && schedule && round < schedule.flips.length;
  const flip = seeded ? schedule.flips[round] : round % 2 !== 0;
  return flip ? [...legs].reverse() : legs;
}

const ALLOC_PASS_SCHEDULE = allocPassFlips(ALLOC_ROUNDS, ALLOC_PASS_SEED);

// The plan a shape admits, as a PURE FUNCTION of the full plan and the shape
// — the same reason `allocArmSizing` and `allocSteps` are pure: it needs
// neither a release build nor a Chromium, so the pin can DRIVE it rather than
// read the source and hope.
//
// `ladderPlan` itself is untouched. It is what the same-arms-on-both-pages
// pin adjudicates, and a mode that reached inside it would be a second
// instrument rather than a narrower run of the one instrument.
function allocPlanArms(plan, shape) {
  if (!shape.arms) return [];
  if (shape.rungs) return plan;
  return plan.map(({ segment, arms }) => ({
    segment,
    arms: arms.filter((a) => a.rung === 'floor'),
  }));
}

// The median of a window's work legs. The MEDIAN and not the mean, because
// the first leg of a window is the one most likely to sit high and a mean
// would let it drag the cohort toward whichever leg is the outlier.
function median(xs) {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 === 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

// WHAT THE CENSUS PROVED ABOUT ITSELF (rf2-n1b9h). Pure, driven over a
// record's own rounds, and it adjudicates two claims that are not the same:
//
//   - EVERY CONTROL WINDOW READ ZERO. A control dispatches nothing and renders
//     nothing, so its census is the instrument's null arm, in situ and
//     interleaved with the arms in the same round on the same page.
//   - EVERY ARM WINDOW READ EXACTLY `windowWrites` HANDLER INVOCATIONS. The
//     window drives that many `dispatch-sync` calls, so anything else is
//     either a handler that ran more than once per dispatch — which is
//     candidate (a) itself, and would be the finding — or a counter that is
//     not wired to the write path.
//
// The second is stated as an EXPECTATION AND NOT A GATE, and the difference
// matters: a run whose arm windows read 14 where 7 were driven has not
// malfunctioned, it has ANSWERED. So the disagreements are counted and
// enumerated rather than exited on, and the studio page reads them.
function allocWorkVerification(rounds, counted, windowWrites) {
  if (!counted) return { counted: false };
  const controlsMoved = [];
  const armEvents = {};
  const armSubs = {};
  const armRenders = {};
  let windows = 0;
  for (const r of rounds) {
    for (const [name, c] of Object.entries(r.controls || {})) {
      const d = c.workDelta;
      if (d && (d.events || d.subs || d.renders)) {
        controlsMoved.push(`round ${r.round} ${name}: ${JSON.stringify(d)}`);
      }
    }
    for (const [key, a] of Object.entries(r.arms || {})) {
      const d = a.workDelta;
      if (!d) continue;
      windows++;
      (armEvents[key] ||= new Set()).add(d.events);
      (armSubs[key] ||= new Set()).add(d.subs);
      (armRenders[key] ||= new Set()).add(d.renders);
    }
  }
  const distinct = (m) =>
    Object.fromEntries(Object.entries(m).map(([k, v]) => [k, [...v].sort((a, b) => a - b)]));
  const events = distinct(armEvents);
  const offExpectation = Object.entries(events)
    .filter(([, vs]) => vs.length !== 1 || vs[0] !== windowWrites)
    .map(([k, vs]) => `${k}: ${vs.join(',')}`);
  return {
    counted: true,
    windows,
    windowWrites,
    controlsMoved,
    events,
    subs: distinct(armSubs),
    renders: distinct(armRenders),
    offExpectation,
    ok: controlsMoved.length === 0,
  };
}

// THE WORK A WINDOW DID (rf2-n1b9h) — the difference of two readings of three
// monotone counters, taken at the window's open and at its close.
//
// Pure, and `undefined` when the page carried no census, for `allocSteps`'s
// reason: the pin can drive it rather than read the source and hope, and a
// record from before this bead answers `undefined` rather than a fabricated
// zero. **Zero and "not counted" must not be the same value here** — the whole
// conclusion this bead reaches turns on a delta that reads zero, so a missing
// census rendering as zero would read as the finding itself.
//
// Every counter is monotone for the life of the page, so a NEGATIVE delta is
// impossible and is not defended against: it would mean the page was reloaded
// mid-window, which the driver's own structure forecloses.
function allocWorkDelta(win) {
  if (!win || !win.work || !win.work0) return undefined;
  return {
    events: win.work.events - win.work0.events,
    subs: win.work.subs - win.work0.subs,
    renders: win.work.renders - win.work0.renders,
  };
}

// Split a window's raw samples into the PRIME legs and the MEASURED sample
// stream (rf2-oiy1). Pure, for `allocSteps`'s reason: the pin can DRIVE it
// rather than read the source and hope.
//
// The stream is `[s0, pre0, post0, pre1, post1, ...]`, so dropping the leading
// `2·prime` samples leaves `[post_{p−1}, pre_p, post_p, ...]` — WHICH IS THE
// SAME SHAPE. The last prime leg's `post` becomes the measured region's `s0`,
// and the measured region's first gap is the true `pre_p − post_{p−1}`.
// Nothing is fabricated and nothing is re-based, which is exactly why
// `allocSteps` needs to know none of this and is unchanged by this bead.
//
// `prime` is clamped to the legs actually present, so a stream shorter than
// the prime yields no measured legs rather than reaching off the end. It is a
// parameter for the pin's benefit; the driver always passes the constant.
function allocPrimeSplit(samples, prime = ALLOC_PRIME_WRITES) {
  const p = Math.max(0, Math.min(prime, (samples.length - 1) >> 1));
  const primeLegs = [];
  for (let k = 0; k < p; k++) primeLegs.push(samples[2 * k + 2] - samples[2 * k + 1]);
  return { primeLegs, measured: samples.slice(2 * p) };
}

// The BY-SITE decomposition of one window's raw samples (rf2-rs8q6). Pure, for
// `allocPrimeSplit`'s reason: the pin can DRIVE it rather than read the source
// and hope.
//
// TWO ANSWERS OUT OF ONE STREAM, and the first is why every gate below stands
// unedited:
//
//   `collapsed` — `[s0, pre0, post0, pre1, post1, ...]`, WHICH IS THE STREAM
//     THE SHIPPED WINDOW WOULD HAVE FILLED from the same work. Every reading
//     in it is a reading actually taken; the mid samples are dropped, not
//     averaged, folded or synthesised. This is what `allocPrimeSplit` and
//     `allocSteps` are handed, so the certificate, the falls gate and every
//     published figure are computed by the same code over the same shape as
//     on a run that never armed the mode.
//   `siteLegs` — one `{dispatch, drain, leg}` per work unit, where
//     `dispatch + drain === leg` exactly, and `leg` is the same subtraction
//     the collapsed stream yields.
//
// AT STRIDE 2 IT IS THE IDENTITY: `collapsed` is the argument itself and
// `siteLegs` is empty. A driver that never arms the mode is running the
// pre-bead path through one function that returns what it was given, and the
// pins drive that rather than asserting it.
//
// `sites` is a parameter and the driver passes the constant, exactly as
// `allocPrimeSplit`'s `prime` is — but the DRIVER passes the stride the PAGE
// reported rather than the one it configured, so a switch that failed to reach
// the page decodes against the wrong shape and is caught, instead of silently
// misreading a well-formed stream of the other stride.
function allocSiteSplit(samples, sites = ALLOC_SITES) {
  if (sites !== 3) return { sites: 2, collapsed: samples, siteLegs: [] };
  const w = Math.max(0, Math.floor((samples.length - 1) / 3));
  const collapsed = samples.length ? [samples[0]] : [];
  const siteLegs = [];
  for (let i = 0; i < w; i++) {
    const pre = samples[3 * i + 1];
    const mid = samples[3 * i + 2];
    const post = samples[3 * i + 3];
    collapsed.push(pre, post);
    siteLegs.push({ dispatch: mid - pre, drain: post - mid, leg: post - pre });
  }
  return { sites: 3, collapsed, siteLegs };
}

// Which site a set of per-site figures sits in — the one whose magnitude is
// largest, and `null` when they are all zero, because a window with no
// deviation has no site to attribute it to and naming one would be the
// instrument answering a question nobody asked.
function allocDominantSite(bySite) {
  let owner = null;
  let best = 0;
  for (const s of ALLOC_SITE_NAMES) {
    if (Math.abs(bySite[s]) > Math.abs(best)) {
      best = bySite[s];
      owner = s;
    }
  }
  return owner;
}

// THE WITNESS THE BEAD ASKS FOR (rf2-rs8q6): the arm work unit's allocation by
// site, and where the excess sits.
//
// It is handed the whole window's site legs and splits the prime off itself,
// on `allocPrimeSplit`'s rule and clamped the same way — the prime is one work
// unit and the cohort is the rest, and a by-site cohort that included the
// prime would have the term rf2-oiy1 removed sitting back inside its medians.
//
// WHAT IT ANSWERS, and this is the whole point of the mode:
//
//   `dominant`  — which site the MEASURED legs' dispersion is in. This is the
//     mechanism question. `dispatch` says the term is in the event pipeline
//     and the signal graph; `drain` says it is in React's commit.
//   `primeSite` and `primeExcessBySite` — the same decomposition of the PRIME
//     leg's excess over the measured cohort.
//   `sameSite` — whether those two agree. THIS IS THE HYPOTHESIS THE BEAD
//     NAMES AS THE ONE TO EXCLUDE FIRST: the recurring excesses (+476 to
//     +7,456 B, median 2,640 B) overlap the prime excess's own scale (median
//     6,864 B), which would suggest THE SAME TERM RECURRING rather than a
//     distinct one.
//
// WHAT IT DOES NOT CLAIM. `sameSite` is agreement about a SITE, and a site is
// two statements wide. Two different terms can live in one site, so agreement
// is necessary for the same-term reading and not sufficient for it —
// DISAGREEMENT SETTLES THE HYPOTHESIS, agreement narrows it. The magnitudes
// are reported beside it for that reason: the prime excess's own spread is
// tight (p25 6,800, p75 6,888 — an interquartile width of 88 B, 1.3% of its
// median), so a recurring term of the same identity has a magnitude to hit,
// and the summary prints both rather than collapsing them to a verdict.
function allocSiteWitness(siteLegs, prime = ALLOC_PRIME_WRITES) {
  const p = Math.max(0, Math.min(prime, siteLegs.length));
  const primeSites = siteLegs.slice(0, p);
  const measured = siteLegs.slice(p);
  if (!measured.length) {
    return {
      sites: ALLOC_SITE_NAMES,
      primeSites,
      medians: null,
      legs: [],
      totals: null,
      dominant: null,
      primeExcessBySite: null,
      primeSite: null,
      sameSite: null,
    };
  }

  const medians = { leg: median(measured.map((l) => l.leg)) };
  for (const s of ALLOC_SITE_NAMES) medians[s] = median(measured.map((l) => l[s]));

  const totals = Object.fromEntries(ALLOC_SITE_NAMES.map((s) => [s, 0]));
  const legs = measured.map((l, k) => {
    const by = {};
    for (const s of ALLOC_SITE_NAMES) {
      by[s] = l[s] - medians[s];
      totals[s] += Math.abs(by[s]);
    }
    return { leg: k + 1, deviation: l.leg - medians.leg, by, site: allocDominantSite(by) };
  });

  const dominant = allocDominantSite(totals);
  const primeExcessBySite = p
    ? Object.fromEntries(ALLOC_SITE_NAMES.map((s) => [s, primeSites[0][s] - medians[s]]))
    : null;
  const primeSite = primeExcessBySite ? allocDominantSite(primeExcessBySite) : null;

  return {
    sites: ALLOC_SITE_NAMES,
    primeSites,
    medians,
    legs,
    totals,
    dominant,
    primeExcessBySite,
    primeSite,
    sameSite: dominant && primeSite ? dominant === primeSite : null,
  };
}

// ---------------------------------------------------------------------------
// THE INTRA-LEG RECLAMATION GATE (rf2-4ctls) — the falls gate's own rule, at
// the stride the by-site mode already opens
// ---------------------------------------------------------------------------
//
// THE GAP, MEASURED RATHER THAN REASONED ABOUT. rf2-ojehu's by-site window took
// 72 floor-arm windows on a quiet box across six runs. TWENTY-FOUR of them carry
// at least one NEGATIVE site step — a reclamation inside a single leg.
// Twenty-one are already refused by the falls gate. THREE have `falls` = 0, so
// the falls gate saw nothing at all, and TWO of those three ALSO CERTIFIED at τ:
//
//   s3-c6-page  round 0 uix-subs     leg 3: dispatch +199,980 B,
//     drain -178,496 B, leg total +21,484 B — falls 0, CERTIFIED
//   s3-c6-page  round 2 uix-subs     leg 4: dispatch +305,392 B,
//     drain -285,292 B, leg total +20,100 B — falls 0, CERTIFIED
//   s3-c24-page round 0 reagent-subs leg 2: dispatch +279,024 B,
//     drain -259,996 B, leg total +19,028 B — falls 0, refused on another leg
//
// (Those leg numbers are the WINDOW's, counting the prime as leg 1, which is how
// the measurement record quotes them. The refusals below number the MEASURED
// cohort from 1, exactly as the leg tolerance's do, so each is one lower.)
//
// NEITHER EXISTING GATE IS DEFECTIVE ON ITS OWN TERMS, and that is what decides
// the shape of the repair. The falls gate walks the COLLAPSED stride-2 stream,
// where the leg is ONE step: a reclamation bracketed by a larger allocation
// inside that step turns nothing negative, so nothing falls. The leg tolerance
// is the gate written for exactly that blind side — this file says so directly
// above `allocSteps` — and it passes these because the leg's NET sits inside τ
// of the cohort median, at +1,330 B and +2,008 B against medians of 20,154 B
// and 18,092 B. So this is a gap BETWEEN two gates rather than a bug in either,
// and TIGHTENING EITHER ONE WOULD BE THE WRONG SHAPE: it would retro-reject
// legitimate windows to catch a fault neither gate is looking at.
//
// WHAT IT COSTS. A certified window asserts its measured legs are repetitions of
// ONE work unit. A leg carrying a 285 KB collect-and-reallocate is not the same
// work unit as its siblings, and the window under-reads its true allocation by
// at least the reclaimed amount — the same failure mode the leg tolerance exists
// to catch, arriving through the seam the tolerance cannot see, on a window that
// looks clean by every published figure.
//
// SO THIS IS A THIRD GATE, READING WHAT IS ALREADY MEASURED. The by-site stream
// SEES the reclamation — that is the whole of rf2-ojehu's finding — and
// `siteLegs` has been on the window record since rf2-rs8q6 without adjudicating
// anything. The rule is the falls gate's own, unaltered, applied one stride
// finer:
//
//     REFUSE the window if any MEASURED site step is negative.
//
// Nothing in the work unit removes bytes; the collector does. That is the same
// observation the falls gate rests on and the same one the leg tolerance's "a
// leg BELOW its cohort" branch rests on, so no new premise is introduced and no
// threshold is invented — there is nothing here to calibrate, because the test
// is a SIGN.
//
// WHY THIS IS NOT A WIDENING, which is the fence this row holds every gate to.
// It can only ever ADD refusals: `allocIntraLegRefusals` returns a list and
// `allocWindowVerdict` unions it into the window's own. No window refused today
// is admitted tomorrow, at any τ, on any page.
//
// AND τ IS UNTOUCHED, in either direction. rf2-e9wr established that no honest
// calibration exists on the arms' data and rf2-rs8q6 repeats the fence. Nothing
// here is a reason to move it, and this gate does not read it.
//
// WHY THE CERTIFICATE STILL NEVER SEES A BY-SITE STREAM. rf2-rs8q6's fence is
// load-bearing and it STANDS: `allocSteps` is handed the COLLAPSED stride-2
// stream and is not touched by this bead, so `rise`, `falls`, `maxStep`, `legs`,
// `legMedian` and `legWorstDeviation` are byte-identical on every window at
// every stride, and every one of that bead's pins stands unedited. Handing the
// mid samples to `allocSteps` was the other available repair and it is REFUSED
// for exactly that reason: it would split one step into two and move three
// PUBLISHED quantities to catch a fault a separate list catches while moving
// none.
//
// INERT AT THE SHIPPED STRIDE, BY CONSTRUCTION rather than by a switch.
// `allocSiteSplit` returns an EMPTY `siteLegs` at stride 2, so there is no site
// step to be negative and this returns `[]`. Every row ever published was taken
// at stride 2, so no published figure and no published verdict moves.
//
// AND THE CONVERSE, WHICH IS THE HALF A READER NEEDS AND THE PARAGRAPH ABOVE
// DOES NOT STATE (rf2-fir5n). "No published verdict moves" is the reassurance;
// the cost is that `certified` on a stride-2 window means certified by the falls
// gate and the leg tolerance and NO MORE. That is not a defect in this gate and
// widening it would be the wrong repair — there is no site step at stride 2 to
// widen a test on, only a reading that was never taken — but it does close a
// class of question off. The note at `ALLOC_BY_SITE` records that a stride-3 leg
// magnitude is not comparable byte for byte with a stride-2 one, because each
// leg there carries one extra sampler read. So a witness whose criterion IS a
// byte-for-byte comparison against a figure published at stride 2 must run at
// stride 2, where this gate adjudicates nothing: the class of comparison that
// most wants a third gate is precisely the class that cannot use one. The
// record says so per row — see `allocInstrumentNote` — rather than leaving a
// reader to derive it. The only other way out is rf2-e3p0r, HELD FOR A RULING:
// price the extra read off the idle control (rf2-ojehu read it at 16 B per leg
// and its stride pair met its own falsifiable claim) and correct stride-3 bytes
// back to stride-2 ones, which would make a byte-for-byte witness screenable by
// all three. That is an instrument change wanting a hermetic build, and it may
// equally be judged not worth building; it is not taken here.
//
// WHAT IT DOES NOT CLOSE, stated because a gate that oversells itself is worse
// than none. A reclamation bracketed inside ONE SITE — collected and
// re-allocated between the same two readings — is invisible here for the reason
// it is invisible to the falls gate one stride up, and reaching it needs a seam
// `p0-heap/alloc-window!` deliberately does not cut. This gate MOVES the blind
// spot from the leg to the site; it does not remove it. The leg tolerance still
// runs underneath, and a masked site that costs the leg more than τ·m is taken
// there.
//
// THE PRIME IS OUT OF IT, on `allocSiteWitness`'s rule and clamped the same way.
// The prime leg is in no published figure and in no certificate, so a collection
// inside it refuses nothing — and a collection BETWEEN the prime and the
// measured region lands in the boundary gap, which `allocSteps` already walks on
// the collapsed stream.
function allocIntraLegRefusals(siteLegs, prime = ALLOC_PRIME_WRITES) {
  const p = Math.max(0, Math.min(prime, siteLegs.length));
  const measured = siteLegs.slice(p);
  const out = [];
  for (const [k, l] of measured.entries()) {
    for (const s of ALLOC_SITE_NAMES) {
      if (l[s] >= 0) continue;
      out.push(
        `leg ${k + 1} of ${measured.length} reclaimed ${-l[s]} B inside its ${s} site ` +
          `(${ALLOC_SITE_NAMES.map((n) => `${n} ${l[n] > 0 ? '+' : ''}${l[n]}`).join(', ')}; ` +
          `leg total ${l.leg > 0 ? '+' : ''}${l.leg} B): a collection ran INSIDE this leg and a ` +
          'larger allocation in the same leg bracketed it, so the collapsed step never turned ' +
          'negative and the falls gate saw nothing, while the leg tolerance reads only the NET. ' +
          'The window under-reads its true allocation by at least the reclaimed amount with both ' +
          'the other gates silent, and under-reading is the direction that manufactures ' +
          "HD-002's flat-at-zero. DO NOT WIDEN A GATE TO ADMIT IT: this is a THIRD gate and it " +
          'refuses only what the other two cannot see'
      );
    }
  }
  return out;
}

// Rising and falling steps, accumulated separately, from one window's raw
// samples — AND the leg cohort the certificate is read off.
//
// IT IS HANDED THE MEASURED REGION, NOT THE WHOLE WINDOW (rf2-oiy1). The
// window's first work unit is a prime and `allocPrimeSplit` above takes it off
// before anything here sees it. Everything below is unchanged by that bead.
//
// The samples are `[s0, pre0, post0, pre1, post1, ...]`, so leg `k` is
// `post_k - pre_k` (one per write, the work) and gap `k` is
// `pre_k - post_{k-1}` (`pre0 - s0` for the first), where nothing happens
// but a loop increment and two array stores. `rise` / `fall` / `falls` /
// `maxStep` walk EVERY step exactly as they always did, because a collection
// can fall in either kind — and note the property a gap has for free:
// nothing allocates in a gap, so a collection there cannot be masked. It
// lands as a negative step and the untouched falls gate takes it.
//
// `certified` is `refusals.length === 0`, mirroring `allocArmSizing` since
// rf2-2rtt6.142 — one verdict shape across the preflight and the window
// gate. `tolerance` is a parameter so the pinned probes can be swept across
// τ; the driver never passes it, so the shipped gate is the module constant
// and there is no dial anywhere on the measurement path.
function allocSteps(samples, tolerance = ALLOC_LEG_TOLERANCE) {
  let rise = 0;
  let fall = 0;
  let falls = 0;
  let maxStep = 0;
  for (let i = 1; i < samples.length; i++) {
    const d = samples[i] - samples[i - 1];
    if (d > 0) {
      rise += d;
      if (d > maxStep) maxStep = d;
    } else if (d < 0) {
      fall += -d;
      falls++;
    }
  }

  const w = (samples.length - 1) >> 1;
  const legs = [];
  const gaps = [];
  for (let k = 0; k < w; k++) {
    gaps.push(samples[2 * k + 1] - samples[2 * k]);
    legs.push(samples[2 * k + 2] - samples[2 * k + 1]);
  }

  const legMedian = median(legs);
  // Stated in BYTES rather than as a ratio, so the rule is well defined at a
  // zero median: an idle window is homogeneous at zero, every leg deviates
  // from it by 0, and 0 is not MORE than 0 — so it certifies, which it has
  // to, because the idle control is one of the three windows the row takes.
  const allowed = tolerance * legMedian;
  const refusals = [];
  let worst = 0;
  for (const [k, leg] of legs.entries()) {
    const dev = leg - legMedian;
    if (Math.abs(dev) > Math.abs(worst)) worst = dev;
    if (Math.abs(dev) <= allowed) continue;
    const why =
      dev < 0
        ? 'a leg BELOW its cohort is a leg something removed bytes from, and nothing in the ' +
          'work unit removes bytes — the collector does'
        : 'a leg ABOVE its cohort means the ONE WORK UNIT premise this witness rests on has ' +
          'failed in this window, so refusing is correct rather than merely conservative';
    refusals.push(
      `leg ${k + 1} of ${w} read ${leg} B against a cohort median of ${legMedian} B ` +
        `(${dev > 0 ? '+' : ''}${dev} B` +
        (legMedian > 0 ? `, ${((dev / legMedian) * 100).toFixed(1)}%` : '') +
        `), past the ±${(tolerance * 100).toFixed(0)}% leg tolerance (±${allowed} B): ${why}`
    );
  }

  return {
    rise,
    fall,
    falls,
    maxStep,
    endpoints: samples[samples.length - 1] - samples[0],
    legs,
    gaps,
    legMedian,
    // Signed and RELATIVE, so it is comparable across windows of different
    // magnitudes — and `null` at a zero median, because a relative deviation
    // from zero is not a number and reporting a fabricated 0 would say the
    // window was homogeneous when it may not have been.
    legWorstDeviation: legMedian > 0 ? worst / legMedian : null,
    legTolerance: tolerance,
    refusals,
    certified: refusals.length === 0,
  };
}

// THE WINDOW'S VERDICT — the two window gates' refusals, named apart and
// unioned (rf2-4ctls). Pure, for `allocSteps`'s reason: the pin can DRIVE it
// rather than read the source and hope.
//
// `refusals` and `certified` keep their meanings exactly — the union, and "none
// of them", which is the one verdict shape this row has carried across the
// preflight and the window gate since rf2-2rtt6.142, so `allocRefusedWindows`
// and the summary read what they always read. `legRefusals` and
// `intraLegRefusals` are the two gates' own lists, kept apart so the EXIT can
// name WHICH gate fired: a failure claiming a leg strayed past the tolerance
// when what happened was an intra-leg collection would send an operator to the
// wrong instrument, and to the one constant this row may not touch.
//
// OFF THE MODE `siteLegs` IS EMPTY, so this is the identity on `steps`: the same
// `certified`, the same `refusals`, and every published figure passed through.
function allocWindowVerdict(steps, siteLegs = [], prime = ALLOC_PRIME_WRITES) {
  const intraLegRefusals = allocIntraLegRefusals(siteLegs, prime);
  return {
    ...steps,
    legRefusals: steps.refusals,
    intraLegRefusals,
    refusals: [...steps.refusals, ...intraLegRefusals],
    certified: steps.certified && intraLegRefusals.length === 0,
  };
}

// WHICH GATES ACTUALLY SCREENED THIS ROW (rf2-fir5n) — the record's `instrument`
// string, as a pure function of the stride so a pin can DRIVE it rather than
// read the source and hope.
//
// THE DEFECT IT REPAIRS. The string was one constant, emitted on every row, and
// it closed with "All three refuse". On a stride-2 row that is at best ambiguous
// between a claim about the INSTRUMENT's design — all three gates refuse, none
// widens — and a claim about THIS ROW, and on a field whose whole stated purpose
// is criterion 6's "a reader of any row can tell FROM THE ROW how it was taken"
// the row reading is the one that governs. Two gates screened every published
// row. rf2-4ctls already made the COUNT honest by omission — off the mode the
// summary prints no intra-leg line and the record gains no
// `intraLegRefusalReasons` field, because a 0 would claim the instrument looked
// when it could not — and this closes the same hole in the prose beside it.
//
// AND IT IS NOT A SWITCH THAT COULD BE FLIPPED HERE, which is why the stride-2
// branch states a constraint rather than a TODO. `allocSiteSplit` yields no site
// legs at a stride of 2 because there is no interior reading per leg to
// subtract, so there is no site step whose sign could be read; arming the gate
// needs the stride-3 reading, and a stride-3 leg magnitude is not comparable
// byte for byte with a stride-2 one. A witness whose criterion IS a byte-for-
// byte comparison against a figure published at stride 2 must therefore run at
// stride 2, where this gate adjudicates nothing — so that class of comparison
// can never be screened by all three. Saying so on the row is the honest half of
// that; correcting stride-3 bytes back to stride-2 ones off the idle control's
// per-leg price is the other half, and it is an instrument change wanting a
// hermetic build and a ruling, not a quiet box (rf2-e3p0r).
//
// THE BY-SITE BRANCH IS UNCHANGED TO THE BYTE, on `summariseAllocFits`'s rule:
// the stride-3 text is the string this file has always emitted, lifted out whole
// rather than guarded in place.
function allocInstrumentNote(bySite = ALLOC_BY_SITE) {
  const shared =
    'in-page performance.memory.usedJSHeapSize sampled at every leg boundary, ' +
    'rising steps accumulated separately from falling ones; --enable-precise-memory-info. ' +
    'A falling step is a collection this counter SAW; a leg that deviates from its cohort ' +
    'median by more than the leg tolerance is a work unit that is not one; and under the ' +
    'by-site stride a NEGATIVE SITE STEP is a collection inside one leg that neither of the ' +
    'other two can see. ';
  if (bySite) return shared + 'All three refuse';
  return (
    shared +
    'TWO OF THE THREE RAN ON THIS ROW (rf2-fir5n): it was taken at a stride of 2, where a leg ' +
    'has no interior reading and so no site step to be negative, and the intra-leg gate ' +
    'returned an empty list BY CONSTRUCTION rather than by a switch. `certified` here means ' +
    'certified by the falling-step gate and by the leg tolerance and NO MORE. Arming the third ' +
    'needs the stride-3 reading, whose leg magnitudes are not comparable byte for byte with ' +
    'these (see `bySite`), so a witness comparing byte for byte against a figure published at ' +
    'stride 2 can never be screened by all three'
  );
}

// The witness over a whole collected row, as a PURE FUNCTION of it — the
// same shape and for the same reason as `ladderStructuralFailures` below: it
// needs neither a release build nor a Chromium to adjudicate, so it can be
// pinned on every PR by `p0_ladder_structural.test.cjs` instead of waiting
// for the next opt-in run of this driver to notice.
//
// ARM WINDOWS ONLY, exactly as the falling-step gate counts only those. The
// arms are what gets published; the controls adjudicate the arithmetic, and
// a masked control would read LOW against its own 8 B/double prediction and
// refuse through `controlVerdict` — the safe direction, already gated.
//
// EVERY REFUSED WINDOW IS NAMED WITH ITS REASON, on every round, because a
// count of refusals tells an operator nothing about which leg misbehaved.
//
// `field` READS ONE GATE'S OWN LIST (rf2-4ctls), and the default is the union a
// window was refused on. It exists so the EXIT can attribute a refusal to the
// gate that made it — `legRefusals` for the leg tolerance, `intraLegRefusals`
// for the intra-leg reclamation gate — while the SUMMARY goes on printing every
// reason together, which is what a reader of a refused window wants. The guard
// is the reason list rather than `certified` so that a named sub-list is read
// correctly; for the union the two are the same test, since `certified` is
// exactly "no refusals".
function allocRefusedWindows(row, field = 'refusals') {
  const out = [];
  for (const r of row.perRound || []) {
    for (const [key, a] of Object.entries(r.arms || {})) {
      for (const reason of a[field] || []) out.push(`round ${r.round} ${key}: ${reason}`);
    }
  }
  return out;
}

// How many DISTINCT arm windows carry at least one refusal — the numerator
// `windows refused: N of M` needs (rf2-xxeq).
//
// THE DEFECT. `allocRefusedWindows` above returns one entry per refusal REASON,
// and a window with two deviant legs contributes two of them, while the
// denominator counts WINDOWS. Every V1 run therefore printed a line of the
// shape `windows refused: 17 of 12` — 17, 14, 13, 14, 16, 13 against a
// twelve-window floor-only plan — which is not a possible ratio.
//
// Nothing was mis-gated: the exit keys off the reason list being non-empty,
// which is correct under either count, and no published figure moved. But a
// row whose entire purpose is to be believed may not print an impossible
// ratio beside its figures, and a reader who took "17 windows were refused"
// at face value would conclude the run measured more windows than it did.
//
// The reason list is unchanged and is still printed underneath, in full: this
// names the two quantities apart rather than dropping either.
function allocRefusedWindowCount(row) {
  let n = 0;
  for (const r of row.perRound || []) {
    for (const a of Object.values(r.arms || {})) if (!a.certified) n++;
  }
  return n;
}

// EVERY RECORDED WINDOW NAMES ITS OWN WRITE, AND EVERY PAIR IS WHOLE
// (rf2-irxrw). This is criterion 6's separation read at WINDOW granularity.
//
// WHY IT MOVED THERE. While one process drove one write, "a reader of any row
// can tell FROM THE ROW which write produced it" was satisfied by the row's
// own `writeSelector`: every window under that row had the same answer. A
// `paired` row has both, so a row-level field answers nothing about a given
// window and the claim has to be carried by the window. The pin over this
// asserts exactly that, which is strictly more than the row-level one asked
// and is why the row-level fields stay rather than being replaced.
//
// AND THE SECOND HALF IS WHAT MAKES THE PAIR USABLE. A record in which one
// leg of a pair refused to record, or recorded under the wrong key, still
// looks well-formed window by window — and an estimator differencing it would
// silently be back to comparing unmatched populations, which is the whole
// defect this switch exists to remove. So the pairs are counted: within each
// round, the windows sharing a `pairKey` must name EVERY leg the run drove.
// Off `paired` that is the identity — one leg, one window per key — and the
// check is the same code saying so.
//
// IT IS A RECORDED FACT AND GATES NOTHING. No figure is computed from it and
// no run exits on it; the pins are what adjudicate it, on every PR.
function allocWriteProvenance(row) {
  const legs = row.writeLegs || [];
  const known = new Set(legs);
  const unnamed = [];
  const incomplete = [];
  let windows = 0;
  let pairCount = 0;
  for (const r of row.perRound || []) {
    const pairs = new Map();
    for (const [key, a] of Object.entries(r.arms || {})) {
      windows++;
      const spec = ALLOC_WRITE_SPECS[a.writeSelector];
      if (typeof a.writeSelector !== 'string' || !known.has(a.writeSelector) || !spec) {
        unnamed.push(
          `round ${r.round} ${key}: ` +
            (typeof a.writeSelector === 'string'
              ? `names \`${a.writeSelector}\`, which is not a write this run drove`
              : 'names no write')
        );
        continue;
      }
      if (typeof a.write !== 'string' || !a.write.startsWith(spec.event)) {
        unnamed.push(
          `round ${r.round} ${key}: names \`${a.writeSelector}\` but records ` +
            `${typeof a.write === 'string' ? `\`${a.write}\`` : 'no event'}, not \`${spec.event}\``
        );
        continue;
      }
      if (typeof a.pairKey !== 'string') {
        unnamed.push(`round ${r.round} ${key}: carries no pairKey, so it belongs to no pair`);
        continue;
      }
      if (!pairs.has(a.pairKey)) pairs.set(a.pairKey, new Set());
      pairs.get(a.pairKey).add(a.writeSelector);
    }
    for (const [pairKey, got] of pairs) {
      pairCount++;
      if (got.size !== legs.length) {
        incomplete.push(
          `round ${r.round} ${pairKey}: ${[...got].sort().join(' + ') || 'nothing'} — ` +
            `this run drives ${legs.join(' + ')}`
        );
      }
    }
  }
  return {
    windows,
    pairs: pairCount,
    unnamed,
    incomplete,
    ok: unnamed.length === 0 && incomplete.length === 0,
  };
}

async function allocRow(chromium) {
  // THE PREFLIGHT REFUSAL, before a browser is launched and a byte is
  // measured. It refuses only on grounds it can defend WITHOUT a sizing model
  // — rf2-2rtt6.139's interim posture — which is now two: an unstated page,
  // and a window under the averaging floor.
  //
  // The averaging floor is the half `allocSteps` can NEVER catch up on
  // (rf2-2rtt6.142). A window under `ALLOC_MIN_WRITES` is not heterogeneous,
  // so nothing downstream refuses it, and the r² floor sees only a fit it has
  // no averaging to make. This is the one gate that can say so.
  if (!ALLOC_ARM.admissible) {
    throw new Error(
      `the allocation arm is refused by its own preflight before anything is measured — ` +
        `${ALLOC_ARM.boundaries === null ? 'no page stated' : `${ALLOC_ARM.boundaries} boundaries ` +
          `(${ROOTS} roots x ${ALLOC_CELLS} cells)`} x ${ALLOC_ARM.writes} writes:\n` +
        ALLOC_ARM.refusals.map((r) => `  - ${r}`).join('\n')
    );
  }
  // THE TWO SWITCHES, REFUSED HERE FOR THE SAME REASON AND IN THE SAME PLACE
  // (rf2-gxrr). A mistyped switch would otherwise fall back to its default
  // silently, and the run would publish a row under a configuration nobody
  // asked for — the one failure a measurement instrument may not have, since
  // the record would name the write it drove and the operator would read the
  // one they meant. Refused BY NAME, before a browser is launched, exactly as
  // the arm above.
  //
  // The arm preflight bites under EVERY plan, controls-only included, and
  // that is deliberate: the no-arms route out of a refused page is a mode
  // with a name on it, not a page of zero boundaries. V3 states its page like
  // any other run, and the averaging floor still holds its six writes.
  // REFUSED ON THE LEGS, NOT ON THE SPEC (rf2-irxrw). `ALLOC_WRITE_SPEC` is
  // `undefined` under `paired` as well as under a typo — there is no ONE
  // write in either case — so the test that separates a valid selection from
  // a mistyped one is whether the SELECTION resolved, and that is the legs.
  if (ALLOC_WRITE_LEGS === undefined) {
    throw new Error(
      `unknown P0_ALLOC_WRITE ${JSON.stringify(ALLOC_WRITE)} — the allocation window drives ` +
        `one of ${Object.keys(ALLOC_WRITE_SELECTIONS).join(' | ')}, and \`page\` is the default ` +
        'every published row is taken under'
    );
  }
  if (ALLOC_PLAN_SHAPE === undefined) {
    throw new Error(
      `unknown P0_ALLOC_PLAN ${JSON.stringify(ALLOC_PLAN)} — the allocation row runs one of ` +
        `${Object.keys(ALLOC_PLAN_SHAPES).join(' | ')}, and \`full\` is the default`
    );
  }
  // AND THE THIRD SWITCH, ON THE SAME RULE (rf2-rs8q6). A mistyped segment
  // order would otherwise fall back to `parity` silently, and this is the one
  // switch where that failure is undetectable after the fact: a `fixed` run
  // that quietly ran on `parity` produces a record whose rounds are in the
  // flipped order under a name that says they are not, and the confound the
  // mode exists to break would read as broken when it never was.
  if (!ALLOC_SEG_ORDERS.includes(ALLOC_SEG_ORDER)) {
    throw new Error(
      `unknown P0_ALLOC_SEG_ORDER ${JSON.stringify(ALLOC_SEG_ORDER)} — the allocation row ` +
        `drives the segments in one of ${ALLOC_SEG_ORDERS.join(' | ')} order, and \`parity\` ` +
        'is the default every published row is taken under'
    );
  }
  // AND THE FOURTH, ON THE SAME RULE (rf2-rs8q6). A mistyped control slot is
  // undetectable after the fact for exactly the segment order's reason and one
  // more on top of it: a `mid` run that quietly ran on `first` produces a
  // record in which "the first arm after the controls" is position 0 under a
  // name that says it is position 1 — which is the one reading the whole
  // window is taken to make, arriving pre-inverted.
  if (!ALLOC_CONTROL_SLOTS.includes(ALLOC_CONTROL_SLOT)) {
    throw new Error(
      `unknown P0_ALLOC_CONTROL_SLOT ${JSON.stringify(ALLOC_CONTROL_SLOT)} — the allocation ` +
        `row drives its three controls at one of ${ALLOC_CONTROL_SLOTS.join(' | ')}, and ` +
        '`first` is the default every published row is taken under'
    );
  }
  // AND THE FIFTH, ON THE SAME RULE (rf2-fk6pj). A mistyped leg order is
  // undetectable after the fact for the segment order's reason exactly: a
  // `seeded` run that quietly ran on `parity` produces a record carrying a
  // seed, a mode name and a set of rounds whose leg order IS parity-derived —
  // and the whole point of the window is that those two are distinguishable,
  // so the run would arrive pre-refuted under a name that says it separates
  // them.
  if (!ALLOC_PASS_ORDERS.includes(ALLOC_PASS_ORDER)) {
    throw new Error(
      `unknown P0_ALLOC_PASS_ORDER ${JSON.stringify(ALLOC_PASS_ORDER)} — the allocation row ` +
        `alternates its write legs under one of ${ALLOC_PASS_ORDERS.join(' | ')}, and \`parity\` ` +
        'is the default every published row is taken under'
    );
  }
  // AND A `seeded` RUN THAT CANNOT ESCAPE PARITY IS REFUSED RATHER THAN
  // TAKEN (rf2-fk6pj). At two rounds or fewer every balanced schedule IS a
  // parity schedule, so the draw has nothing to return and `allocPassFlips`
  // says so. The window would cost the same and separate nothing, and a record
  // stating `seeded` over a parity-derived schedule is the one artefact this
  // mode may not produce.
  if (ALLOC_PASS_ORDER === 'seeded' && ALLOC_PASS_SCHEDULE.parityTied) {
    throw new Error(
      `P0_ALLOC_PASS_ORDER=seeded over ${ALLOC_ROUNDS} round(s) has no schedule that is not a ` +
        'function of round parity — at two rounds or fewer every balanced schedule alternates. ' +
        'The mode exists to separate the pass-position term from round parity and cannot do it ' +
        'here: RAISE P0_ALLOC_ROUNDS to at least 3, or take the window under `parity`'
    );
  }
  const { browser, page, watch } = await newPage(chromium, '?mode=heap');
  await watch.race('window.P0_READY === true || window.P0_ERROR', {
    timeoutMs: 180000,
    budget: 'the 180s wait for window.P0_READY (the alloc page)',
  });
  const err = await page.evaluate('window.P0_ERROR || null');
  if (err) {
    await browser.close();
    throw new Error(`alloc page failed to initialise: ${err}`);
  }

  // The fit rule's own self-test, BEFORE anything is measured. This row
  // fits with `p0-heap/ladder-fit` — the SAME rule the reads ladder uses,
  // with the same r² floor and the same R=0 exclusion — so it inherits
  // that rule's control unchanged rather than growing a second one.
  const st = await page.evaluate(() => window.P0H.ladderSelfTest());
  for (const c of st.checks) {
    console.log(`;; P0 alloc-fit ${c.ok ? 'ok  ' : 'FAIL'} ${c.name}  — ${c.detail}`);
  }
  if (!st.ok) {
    await browser.close();
    throw new Error('the ladder-fit self-test FAILED — no allocation slope may be priced');
  }

  // The page's own `boundariesPerRoot` is the PUBLISHED size and this row
  // does not measure it: `grid` is overridden with the small witness, so the
  // plan, the floor and every rung mount the same few dozen boundaries. The
  // published figure rides along in the record, because a reader has to be
  // able to see which page this row is NOT the 1,200-boundary one.
  const published = await page.evaluate(() => window.P0H.boundariesPerRoot);
  const perRoot = { ...published, grid: ALLOC_CELLS };
  const B = ALLOC_ARM.boundaries;
  // The full plan, then whatever this run's plan shape admits of it
  // (rf2-gxrr). `full` returns it untouched.
  const plan = allocPlanArms(ladderPlan(perRoot, ROOTS), ALLOC_PLAN_SHAPE);
  const { gc, read } = await makeReaders(page);

  // --- the precise-memory flag, PROVED rather than trusted --------------
  // Without `--enable-precise-memory-info` Chrome quantises the in-page
  // counter to 100 KB buckets and every figure here would be noise. A
  // quantised counter is not a small error; it is a different instrument.
  await page.evaluate(
    ([d, n, s]) => window.P0H.allocPrepare(d, n, s),
    [ALLOC_D, ALLOC_WINDOW_WRITES, ALLOC_SITES]
  );
  const probe = await page.evaluate(
    (n) => window.P0H.allocWindow(n, 'control', 'react'),
    ALLOC_WINDOW_WRITES
  );
  // THE STRIDE, PROVED RATHER THAN TRUSTED, on the same probe and for the same
  // reason as the flag above (rf2-rs8q6). A page that ignored the third
  // argument — an older build served out of a stale cache is the way that
  // happens — would fill a stride-2 buffer and hand back a well-formed stream
  // the driver would then decode against a stride of 3, reading site figures
  // out of readings that are not at a seam. The page reports the stride it
  // used; this is where the two are made to agree, once, before anything is
  // measured.
  if (probe.sites !== ALLOC_SITES) {
    await browser.close();
    throw new Error(
      `the page filled its window at a stride of ${probe.sites} where the driver asked for ` +
        `${ALLOC_SITES} (P0_ALLOC_BY_SITE=${ALLOC_BY_SITE ? '1' : 'unset'}): nothing decoded from ` +
        'these samples would mean what it says'
    );
  }
  const rounded = probe.samples.filter((x) => x % 100000 === 0).length;
  const precise = rounded < probe.samples.length;
  if (!precise) {
    await browser.close();
    throw new Error(
      `--enable-precise-memory-info did not take: all ${probe.samples.length} readings are ` +
        'multiples of 100,000, so the counter is quantised and nothing here is measurable'
    );
  }

  // --- THE WORK CENSUS, PROVED RATHER THAN TRUSTED (rf2-n1b9h) ----------
  //
  // The closure-define rides on a `--config-merge`, and the page is the only
  // thing that knows whether it arrived. Both directions are gated, because
  // both are silent failures wearing a plausible number:
  //
  //   - asked for and ABSENT — every counter reads 0 for the whole run, which
  //     is indistinguishable from a page that did no work, and "counts
  //     identical" is exactly the reading this bead would draw a conclusion
  //     from. A census that cannot move must never be quoted as one that did
  //     not.
  //   - NOT asked for and present — a stale build directory serving a counted
  //     bundle into a run whose figures are meant to be comparable with the
  //     published series. The whole point of the compile-time gate is that
  //     THIS run's bundle is the pre-census one, so it is checked, not assumed.
  const armed = await page.evaluate(() => window.P0H.workArmed());
  if (armed !== WORK_COUNT) {
    await browser.close();
    throw new Error(
      `the work census is ${armed ? 'COMPILED IN' : 'compiled out'} where the driver asked for ` +
        `${WORK_COUNT ? 'it' : 'a bundle without it'} (P0_WORK_COUNT=` +
        `${WORK_COUNT ? '1' : 'unset'}): the closure-define did not reach the compiler, and ` +
        'a run either way would misread its own counters'
    );
  }
  // AND THE CONTROL'S OWN CENSUS. The probe window above drove `control` —
  // a dropped `.slice` and no dispatch at all — so its three deltas must be
  // exactly zero. This is the negative control on the instrument: a counter
  // that ticked here would be counting something that is not the arm's work,
  // and every arm figure below would carry it.
  const probeWork = allocWorkDelta(probe);
  if (probeWork && (probeWork.events || probeWork.subs || probeWork.renders)) {
    await browser.close();
    throw new Error(
      `the work census moved during a CONTROL window — events ${probeWork.events}, subs ` +
        `${probeWork.subs}, renders ${probeWork.renders} — where a control dispatches nothing ` +
        'and renders nothing: the counters are not counting the arm'
    );
  }

  let unverified = 0;
  const unverifiedDetail = [];
  const rounds = [];

  for (let round = 0; round < ALLOC_ROUNDS; round++) {
    console.error(`[p0] alloc round ${round + 1}/${ALLOC_ROUNDS}`);
    const armsOut = {};

    // --- the three controls, in situ, AT THIS RUN'S CONTROL SLOT --------
    // `first` is the pre-bead position — before the round's arms — and every
    // published row is taken under it. `mid` and `last` move them, which is
    // what separates "the first arm after the controls" from "the first arm
    // after the round-loop boundary" (rf2-rs8q6); see `allocControlIndex` for
    // why only `mid` separates them at full n.
    const controlOf = async (kind, d) => {
      await page.evaluate(
        ([dd, n, s]) => window.P0H.allocPrepare(dd, n, s),
        [d, ALLOC_WINDOW_WRITES, ALLOC_SITES]
      );
      await gc();
      const pre = await read();
      const w = await page.evaluate(
        ([n, k]) => window.P0H.allocWindow(n, k, 'react'),
        [ALLOC_WINDOW_WRITES, kind]
      );
      const post = await read();
      // The controls take the prime too, and that is the point: one window
      // shape, not two (rf2-oiy1). Their own first leg carries no measurable
      // excess — the studio page records the control windows' worst leg
      // deviation at <= 1% against the arms' 26-46% — so priming them changes
      // no control figure, and a control taken under a different window shape
      // from the arms would not be one.
      // THE COLLAPSE COMES FIRST AND THE STRIDE IS THE PAGE'S (rf2-rs8q6).
      // Off the mode this is the identity and the two lines below are the
      // pre-bead ones; on it, the certificate is still read off the stride-2
      // stream, and the site figures ride alongside as a diagnostic.
      const site = allocSiteSplit(w.samples, w.sites);
      const { primeLegs, measured } = allocPrimeSplit(site.collapsed);
      const s = allocSteps(measured);
      // AND THE THIRD GATE (rf2-4ctls), which reads the site legs the mode has
      // already measured and adjudicates nothing `allocSteps` adjudicates. The
      // identity off the mode, where there are no site legs to be negative.
      // ONE WINDOW SHAPE, NOT TWO: the controls take this exactly as they take
      // the prime, so a control refuses for the same reasons an arm does.
      const verdict = allocWindowVerdict(s, site.siteLegs);
      return {
        kind,
        d,
        ...verdict,
        primeLegs,
        primeExcess: primeLegs.length ? primeLegs[0] - s.legMedian : null,
        perIter: s.rise / ALLOC_WRITES,
        cdpBracket: post.cdp - pre.cdp,
        // THE CONTROL IS THE MODE'S OWN CONTROL. An idle leg's two site
        // figures are one sampler read's footprint and nothing else, which
        // is the constant sitting inside every arm site figure below.
        sites: site.sites,
        siteLegs: site.siteLegs,
        siteWitness: site.siteLegs.length ? allocSiteWitness(site.siteLegs) : null,
        // THE RAW STREAM THIS WINDOW WAS READ OFF (rf2-erre5). See the arm
        // window below for why; the controls carry it for the same reason
        // they carry the prime — one window shape, not two.
        samples: w.samples,
        // AND THE WORK CENSUS (rf2-n1b9h), for that same reason. A control
        // dispatches nothing and renders nothing, so its three deltas are
        // the census's in-situ null arm: they are recorded every round of
        // every run, and `allocWorkVerification` below refuses any run in
        // which one of them moved.
        work0: w.work0,
        work: w.work,
        workDelta: allocWorkDelta(w),
      };
    };
    // --- the arms, in the order this run's segment order dictates -------
    // `parity` is the pre-bead expression verbatim; `fixed` drives the
    // configured order every round and is what breaks the position/substrate
    // confound (rf2-rs8q6). See `allocSegmentOrder` for the three outcomes.
    const segs = allocSegmentOrder(plan, round, ALLOC_SEG_ORDER);
    // AND THE WRITE LEGS, ON THE SAME PARITY (rf2-irxrw). One pass over the
    // segment's arms per write this run drives, so a `paired` round measures
    // EVERY arm — the floor included, and the floor is the dominant shared
    // term — under both writes on the same page in the same process. Off
    // `paired` there is one leg, `passes` is `segs` with it attached, and the
    // body below runs exactly the calls it ran before, in the same order.
    //
    // THE PASS RE-SEEDS, AND IT HAS TO. `:p0/write-all` replaces `:cells`
    // with a `cells-n`-wide vector whatever is mounted, and `:p0/write-page`
    // rebuilds at `(count (:cells db))` — so a page leg that followed an all
    // leg on an unseeded frame would rebuild 300 cells and BE the bulk write,
    // reading back correctly and saying nothing. `prepare(segment, B)` is
    // already the first statement of every pass and already re-seeds; running
    // one pass per leg is what keeps that true rather than a new mechanism.
    //
    // THE ORDER ALTERNATES for the reason the segment order does: a fixed one
    // confounds the write with within-round position, which is exactly the
    // defect this switch exists to remove, and a drift within a run would be
    // read as a difference between the writes. Over the six default rounds
    // each leg leads three times.
    //
    // AND UNDER WHICH RULE IT ALTERNATES IS ITSELF A MODE NOW (rf2-fk6pj).
    // `parity` is the expression that stood here — `round % 2 === 0`, and
    // `allocPassOrder` is that expression verbatim under it, so every
    // committed corpus is read under the rule it was taken under. `seeded`
    // draws a balanced schedule from `passSeed` instead, which is the only way
    // to separate the measured pass-position term from every other even/odd
    // property of a round. See `allocPassFlips` for what the draw guarantees.
    const legs = allocPassOrder(ALLOC_WRITE_LEGS, round, ALLOC_PASS_ORDER, ALLOC_PASS_SCHEDULE);
    const passes = segs.flatMap(({ segment, arms }) =>
      legs.map((leg) => ({ segment, arms, leg }))
    );
    // WHERE THE THREE CONTROLS GO, AND THE RECORD OF WHAT WAS DRIVEN
    // (rf2-rs8q6). `controlAt` names the pass they are driven BEFORE;
    // `windowOrder` accumulates the round's window sequence in DRIVE ORDER,
    // which is what the confound reader indexes on — a reader that recomputed
    // the slot rule would mis-place every window of a moved-control run, the
    // same defect `segments` was landed to close for the segment order.
    let idle = null;
    let ctl1 = null;
    let ctl2 = null;
    const windowOrder = [];
    const controlAt = allocControlIndex(passes.length, ALLOC_CONTROL_SLOT);
    const driveControls = async () => {
      idle = await controlOf('idle', 0);
      windowOrder.push('control/idle');
      ctl1 = await controlOf('control', ALLOC_D);
      windowOrder.push('control/d1');
      ctl2 = await controlOf('control', ALLOC_D2);
      windowOrder.push('control/d2');
    };
    let passIndex = -1;
    for (const { segment, arms, leg } of passes) {
      passIndex++;
      if (passIndex === controlAt) await driveControls();
      // THE GRID WIDTH IS B, AND IT IS SEEDED WITH THE FRAME (rf2-2rtt6.140).
      // `:p0/write-page` rebuilds `:cells` at the width the mounted page
      // actually reads — one cell per boundary — so the write's own machinery
      // is O(mounted page) instead of the flat 300-element rebuild
      // `:p0/write-all` performs whether one boundary is mounted or 1,200. On
      // the 24-boundary page this row last ran, 276 of those 300 rebuilt cells
      // were read by nothing at all.
      //
      // Passed here rather than set ambiently: the width has to be seeded
      // BEFORE `make-frame`, and a parameter that is never ambient cannot be
      // set in the wrong order relative to seeding. Every OTHER row passes
      // nothing and seeds at `fx/cells-n`, so no published figure moves.
      //
      // UNDER `P0_ALLOC_WRITE=all` THE SEEDED WIDTH IS STILL B and only the
      // write differs (rf2-gxrr): `:p0/write-all` rebuilds `cells-n` of them
      // whatever is mounted, which is precisely why V1 expects `F_old` flat
      // in B. The mounted boundaries read cells 0..(B/roots − 1), so a page
      // wider than `cells-n` would have its tail read `nil` — the warm-write
      // read-back below is that guard, and V1's pages (B ∈ {4, 24, 96}) sit
      // an order of magnitude inside it.
      await page.evaluate(([s, gw]) => window.P0H.prepare(s, gw), [segment, B]);
      const drain = segment === 'reagent-subs' ? 'reagent' : 'react';
      const order = await page.evaluate(
        ([n, r]) => window.P0H.slotOrder(n, r),
        [arms.length, round]
      );
      for (const j of order) {
        const entry = arms[j];
        // Mount, and KEEP it. Nothing is released inside this row — the
        // whole quantity is what a STANDING page allocates when it is
        // written to, and an arm torn down between windows would be
        // measuring a mount.
        const v = await page.evaluate(
          ([a, k, o]) => window.P0H.mount(a, k, o),
          [entry.arm, ROOTS, entry.opts]
        );
        if (!v.ok) {
          unverified++;
          unverifiedDetail.push(
            `${allocWindowKey(entry.key, leg.selector)}: elements ${v.elements}/${v.expected}, ` +
              `keys ${v.keys}/${v.keysExpected}`
          );
        }
        // A WARM-UP PASS at the REAL window size, and not a token one. A
        // measurement site reads well above its settled value until it has
        // run several full-size windows: `b8-alloc`'s driver watched a
        // first window read 5.3x its settled value with nothing else
        // varying, and its own instrument pseudo-arm read 9.9x. Warming
        // with anything smaller than the window would leave that inside
        // the first round of every arm.
        //
        // THE WARM-UPS STILL RUN, AND THEY STILL DO NOT REACH THE FIRST LEG
        // (rf2-oiy1). They land BEFORE the `gc()` below, and the excess this
        // row carried in 336 of 336 windows survived all three of them. What
        // clears it is one work unit AFTER the collection, which is what the
        // window's prime leg is. The warm-ups are what stop the arm's own
        // cold-start from reaching the window at all, and that job is theirs
        // still.
        await page.evaluate(
          ([dd, n, s]) => window.P0H.allocPrepare(dd, n, s),
          [0, ALLOC_WINDOW_WRITES, ALLOC_SITES]
        );
        //
        // AND THEY WARM THIS PASS'S OWN WRITE (rf2-irxrw). A site warmed
        // under one write and measured under the other reads its settled
        // value for neither, and under `paired` the other write is one pass
        // away rather than one process away — so `leg` is what both the
        // warm-ups and the measured window below take, and the pin counts
        // both call sites rather than matching one.
        for (let w = 0; w < ALLOC_WARMUPS; w++) {
          await page.evaluate(
            ([n, d, k]) => window.P0H.allocWindow(n, k, d),
            [ALLOC_WINDOW_WRITES, drain, leg.spec.kind]
          );
        }
        await gc();
        const pre = await read();
        const win = await page.evaluate(
          ([n, d, k]) => window.P0H.allocWindow(n, k, d),
          [ALLOC_WINDOW_WRITES, drain, leg.spec.kind]
        );
        const post = await read();
        await page.evaluate(() => window.P0H.release());
        // THE COLLAPSE COMES FIRST AND THE STRIDE IS THE PAGE'S (rf2-rs8q6),
        // exactly as at the control site above. Off the mode `collapsed` IS
        // `win.samples` and the line below it is the pre-bead one.
        const site = allocSiteSplit(win.samples, win.sites);
        const { primeLegs, measured } = allocPrimeSplit(site.collapsed);
        const s = allocSteps(measured);
        // AND THE THIRD GATE (rf2-4ctls), at the arm window exactly as at the
        // control window above. It reads `site.siteLegs` — already measured,
        // never adjudicated until now — and unions its refusals into the
        // window's. `allocSteps` is handed the collapsed stream still, so
        // `rise`, `falls` and `maxStep` are the same numbers on the same code.
        const verdict = allocWindowVerdict(s, site.siteLegs);
        // THE WRITE READ-BACK, and the row exits on it. At R reads of a
        // page whose cells were all written to `v`, a ladder boundary's
        // text is `R·v`; the floor has no subscription and cannot move.
        // A row whose writes never reached the page is the cheapest row in
        // any table, and this is the same class of gate as the mount
        // read-back the retention rows already carry.
        const R = entry.reads || 0;
        const want = String(R * win.tick);
        if (win.text !== want) {
          unverified++;
          unverifiedDetail.push(
            `${allocWindowKey(entry.key, leg.selector)}: warm write read-back "${win.text}", ` +
              `expected "${want}"`
          );
        }
        windowOrder.push(allocWindowKey(entry.key, leg.selector));
        armsOut[allocWindowKey(entry.key, leg.selector)] = {
          segment,
          arm: entry.arm,
          rung: entry.rung,
          reads: R,
          boundaries: B,
          text: win.text,
          // WHICH WRITE THIS WINDOW DROVE, AND WHICH PAIR IT BELONGS TO
          // (rf2-irxrw). Criterion 6's separation — "a reader of any row can
          // tell FROM THE ROW which write produced it" — held at ROW
          // granularity while a process drove one write; under `paired` a row
          // carries both, so it has to hold at WINDOW granularity instead.
          // Every window names its own kind under every selection, so the
          // property is one claim rather than a mode-dependent one.
          //
          // `pairKey` IS THE ARM KEY THE LEGS SHARE, and it is what makes the
          // pairing a field rather than a parse. An estimator wanting matched
          // within-round differences groups a round's windows by it and reads
          // `writeSelector` off each leg; off `paired` it equals the key the
          // window is stored at, which is the same statement with one leg.
          writeSelector: leg.selector,
          write: `${leg.spec.event} — ${leg.spec.note}`,
          pairKey: entry.key,
          ...verdict,
          // THE PRIME LEG, RECORDED RATHER THAN DISCARDED (rf2-oiy1). It is
          // in no published quantity and in no certificate, and it is the
          // term that made every floor window uncertifiable, so the record
          // carries it and the summary prints it.
          primeLegs,
          primeExcess: primeLegs.length ? primeLegs[0] - s.legMedian : null,
          perWrite: s.rise / ALLOC_WRITES,
          perBoundaryPerWrite: s.rise / ALLOC_WRITES / B,
          cdpBracket: post.cdp - pre.cdp,
          // BY SITE, AND ACROSS THE ROUND SEQUENCE (rf2-rs8q6). `siteLegs` is
          // the raw decomposition and `siteWitness` is where the excess sits;
          // both are `null`/empty off the mode, so the record's shape is
          // unchanged for every published run.
          sites: site.sites,
          siteLegs: site.siteLegs,
          siteWitness: site.siteLegs.length ? allocSiteWitness(site.siteLegs) : null,
          // THE RAW STREAM, SO A FUTURE ESTIMATOR CAN BE APPLIED TO A PAST RUN
          // (rf2-erre5). Everything else in this object is DERIVED — `collapsed`
          // from `allocSiteSplit(samples, sites)`, `measured` and `primeLegs`
          // from `allocPrimeSplit` on that, and `rise`, `fall`, `falls`,
          // `maxStep`, `endpoints`, `legs`, `gaps` and the certificate from
          // `allocSteps` on that again. Recording the stream the three of them
          // are handed makes the record CLOSED under re-analysis: an estimator
          // written after the run can be driven over it and get the number this
          // run would have published, instead of over a reconstruction.
          //
          // WHAT IT ADDS OVER `legs` AND `gaps`, which have been recorded since
          // rf2-2rtt6.140 and already re-derive every published figure exactly.
          // Two things, and they are the two a scalar record cannot hold:
          //
          //   - THE PRIME REGION'S GAPS. `primeLegs` keeps the prime's LEGS and
          //     nothing keeps the steps between them, so "did a collection land
          //     in the prime?" is not askable of a record. The prime is exactly
          //     the term rf2-oiy1 took out of every published quantity, which
          //     makes it the term a later bead is most likely to come back to.
          //   - THE ABSOLUTE HEAP LEVEL. Every recorded quantity is a
          //     difference, so a record of them fixes the stream only up to a
          //     translation. Drift across a page's rounds is a question about
          //     the level itself and no arithmetic on the deltas reaches it.
          //
          // WHY THIS AND NOT THE COLLAPSED STREAM. `sites` is recorded beside
          // it, and `allocSiteSplit` is a pure function of the two, so the raw
          // stream determines the collapsed one and a stride-3 run keeps its
          // mid samples as well. The collapsed stream determines neither.
          //
          // IT MOVES NOTHING. No gate reads this field, no figure is computed
          // from it, and `allocSteps` is handed exactly what it was handed
          // before — rf2-rs8q6's fence stands untouched. This is retention.
          samples: win.samples,
          // WHERE THIS WINDOW SITS IN THE PAGE'S OWN WORK-UNIT SEQUENCE.
          // `alloc-tick` is monotone for the life of the page — the warm-ups
          // advance it too — so this pair is what a round index actually IS,
          // stated in the quantity the arm's own write is parameterised by.
          // A round-indexed effect is an effect indexed by these numbers, and
          // no analysis could reach for them while they were not recorded.
          tick0: win.tick0,
          tick: win.tick,
          // THE WORK INSIDE THOSE WRITES (rf2-n1b9h). `tick0`/`tick` place
          // the window in the page's sequence of writes; this pair says
          // what ran inside them. `rf2-77gz8` left two candidates for its
          // 3,792 B second mode that the byte counters cannot separate —
          // more work per write, against the same work allocating more per
          // invocation — and they differ here and nowhere else a page-side
          // instrument can reach.
          work0: win.work0,
          work: win.work,
          workDelta: allocWorkDelta(win),
        };
      }
    }
    // `writeLegs` IS THE ORDER THIS ROUND ACTUALLY DROVE THEM IN (rf2-irxrw),
    // not the configured order. The parity flip above is the whole reason the
    // pair is not order-confounded, and an estimator that wants to know which
    // leg led in a given round has to be able to read it off the round rather
    // than recompute the parity rule.
    // AND `segments` IS THE ORDER THIS ROUND ACTUALLY DROVE THEM IN
    // (rf2-rs8q6), recorded for exactly `writeLegs`'s reason and now with a
    // second one on top of it. The order was recoverable before only by
    // recomputing the parity rule — and that rule is no longer the only one a
    // record can have been taken under, so an estimator that recomputed it
    // would silently mis-position every window of a `fixed` run.
    // AND THE CONTROLS THEMSELVES, WHERE THE SLOT PUTS THEM LAST (rf2-rs8q6).
    // `controlAt === passes.length` is `last`, which is "before no pass at
    // all"; the loop above cannot fire on an index it never reaches, so the
    // tail case is driven here. Under `first` and `mid` this is inert.
    if (controlAt >= passes.length) await driveControls();
    // AND `windowOrder` IS THE WHOLE ROUND IN DRIVE ORDER (rf2-rs8q6),
    // controls included, for that same reason carried one level up. `segments`
    // says which substrate led; `windowOrder` says what ran between the arm
    // windows, which is the index the control-slot modes exist to move. A
    // reader that recomputed it from `controlSlot` would be recomputing the
    // very rule the record is being taken to test.
    rounds.push({
      round,
      controls: { idle, ctl1, ctl2 },
      arms: armsOut,
      writeLegs: legs.map((l) => l.selector),
      segments: segs.map((s) => s.segment),
      controlIndex: controlAt,
      windowOrder,
    });
  }

  // --- the fits, through the LADDER's rule, with the page still open ----
  //
  // A fit is over the RUNGS, so a plan that carries none has nothing to fit
  // and says so by carrying an empty `allocFits` (rf2-gxrr). It does not
  // fabricate a fit from the floor alone, and the summary prints no fitted
  // line — V1 and V3 both state "no fits" in their configurations, and a
  // line fitted through one point would be the instrument answering a
  // question it was not asked.
  //
  // AND ONE FIT PER WRITE (rf2-irxrw). A `paired` run has two rungs at every
  // R, taken under two different writes, and a single line through both would
  // be a slope over a mixture. So the fit id carries the write exactly as the
  // window key does — `allocWindowKey`, on the same identity off `paired`, so
  // every published run's `allocFits` is keyed as it always was.
  const fits = { perRound: {}, mean: {} };
  for (const { segment } of ALLOC_PLAN_SHAPE.fits ? plan : []) {
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const { selector } of ALLOC_WRITE_LEGS) {
        const id = allocWindowKey(`${segment}|${sub}`, selector);
        const rungsOf = (r) =>
          LADDER_RUNGS.map((R) => ({
            rung: `R${R}`,
            reads: R,
            y: r.arms[allocWindowKey(`${segment}|lad/${sub}#R${R}`, selector)].perBoundaryPerWrite,
          }));
        fits.perRound[id] = [];
        for (const r of rounds) {
          fits.perRound[id].push(await page.evaluate((rs) => window.P0H.ladderFit(rs), rungsOf(r)));
        }
        const all = rounds.map(rungsOf);
        const meanRungs = all[0].map((g, i) => ({
          ...g,
          y: all.reduce((acc, rr) => acc + rr[i].y, 0) / all.length,
        }));
        fits.mean[id] = await page.evaluate((rs) => window.P0H.ladderFit(rs), meanRungs);
        fits.mean[id].rungs = meanRungs;
      }
    }
  }

  await browser.close();

  return {
    benchmark: 'P0:steady-state-allocation-slope',
    bead: 'rf2-2rtt6.76',
    roots: ROOTS,
    perRoot,
    publishedPerRoot: published,
    arm: ALLOC_ARM,
    boundaries: B,
    writes: ALLOC_WRITES,
    // The window drives `writes + primeWrites` and publishes over `writes`
    // (rf2-oiy1). Both are in the record because a reader of any figure here
    // has to be able to tell how many work units it was divided by and how
    // many actually ran.
    primeWrites: ALLOC_PRIME_WRITES,
    windowWrites: ALLOC_WINDOW_WRITES,
    // WHETHER THIS ROW WAS TAKEN BY SITE (rf2-rs8q6), on criterion 6's rule
    // that a reader of any row can tell FROM THE ROW how it was taken. Site
    // figures carry one extra sampler read per leg, so a leg magnitude here is
    // not comparable byte for byte with one from a row where this reads 2.
    bySite: ALLOC_BY_SITE,
    sites: ALLOC_SITES,
    siteNames: ALLOC_SITE_NAMES,
    warmups: ALLOC_WARMUPS,
    rounds: ALLOC_ROUNDS,
    preciseMemory: precise,
    controlDoubles: { d1: ALLOC_D, d2: ALLOC_D2 },
    controlSlack: ALLOC_CONTROL_SLACK,
    // WHICH WRITE, AND WHICH PLAN, THIS ROW WAS TAKEN UNDER (rf2-gxrr).
    // Criterion 6's separation is that a reader of any row can tell FROM THE
    // ROW which write produced it; a hard-coded string could only ever have
    // named one, and would have gone on naming it after the selector landed.
    //
    // BOTH ARE THE SELECTION, NOT THE EVENT. The selected write is driven only
    // inside the arm loop, so a plan that mounts no arm resolves these two and
    // fires nothing. `writeDriven` is the record's answer to "did it fire?",
    // and `summariseAlloc` derives it off `perRound` — measured, not
    // configured — beside `controlVerdict` and the other verdicts this row
    // learns about itself only once its rounds are in.
    //
    // AND UNDER `paired` IT IS BOTH (rf2-irxrw), so the row states the legs
    // it drove as a list rather than a scalar. Off `paired` the list has one
    // member and `write` is the same string it always was, to the byte.
    writeSelector: ALLOC_WRITE,
    writeLegs: ALLOC_WRITE_LEGS.map((l) => l.selector),
    writePaired: ALLOC_WRITE_PAIRED,
    write: ALLOC_WRITE_LEGS.map((l) => `${l.spec.event} — ${l.spec.note}`).join('  AND  '),
    plan: { name: ALLOC_PLAN, ...ALLOC_PLAN_SHAPE },
    // AND WHICH SEGMENT ORDER (rf2-rs8q6), on the same criterion-6 rule as the
    // two above: `fixed` gives up the between-substrate comparison the parity
    // flip was landed for, so a reader has to be able to tell from the row
    // whether the row is entitled to make one. `parity` on every published row.
    segOrder: ALLOC_SEG_ORDER,
    // AND UNDER WHICH PASS-ORDER RULE ITS WRITE LEGS ALTERNATED (rf2-fk6pj),
    // on that same criterion-6 rule and with one thing on top of it that the
    // three modes above do not need. `parity` names a schedule a reader can reconstruct from the round
    // index; `seeded` names one that NOTHING recovers except the seed, so the
    // seed travels in the row. Re-passing it as `P0_ALLOC_PASS_SEED` reproduces
    // the schedule exactly, which is what makes a `seeded` window re-readable
    // rather than a one-shot.
    //
    // THE SEED IS RECORDED UNDER `parity` TOO, AND IS INERT THERE. It is
    // resolved once at require whichever mode runs, so a row states the seed
    // it HELD rather than the seed it USED; `passOrder` beside it is what says
    // whether anything was drawn from it. `parity` on every published row.
    passOrder: ALLOC_PASS_ORDER,
    passSeed: ALLOC_PASS_SEED,
    // AND WHAT THE DRAW ACTUALLY RETURNED, for the reason `windowOrder` and
    // `segments` are recorded per round: an estimator must be able to read the
    // schedule off the record rather than re-run the generator. `flips[r]` is
    // TRUE where round r drove its legs reversed. Under `parity` this is the
    // schedule that was drawn and NOT driven — `writeLegs` on each round is
    // always the order that ran.
    passSchedule: ALLOC_PASS_SCHEDULE,
    // AND WHICH CONTROL SLOT (rf2-rs8q6), on that same criterion-6 rule. The
    // controls are this row's null arm and every studio page reads them against
    // the arms window for window; a row whose controls were taken at another
    // point in the round is not the row those comparisons were made on. `first`
    // on every published row.
    controlSlot: ALLOC_CONTROL_SLOT,
    // WHICH OF THE THREE GATES SCREENED THIS ROW (rf2-fir5n), and not merely
    // which three exist. It reads `ALLOC_BY_SITE` for the same reason `bySite`
    // above does: at a stride of 2 the intra-leg gate returns `[]` by
    // construction, so a row that closed with "All three refuse" named a gate
    // that adjudicated nothing on it. See `allocInstrumentNote`.
    instrument: allocInstrumentNote(ALLOC_BY_SITE),
    fallThresholdB: ALLOC_FALL_THRESHOLD_B,
    legTolerance: ALLOC_LEG_TOLERANCE,
    verification: { unverified, detail: unverifiedDetail },
    // WHETHER THIS ROW CARRIES A WORK CENSUS, AND WHAT IT PROVED (rf2-n1b9h).
    // `workCount` is the switch; `workVerification` is what the run measured
    // about its own counters and is derived off `perRound` rather than
    // configured, beside `writeDriven` and `controlVerdict` for the same
    // reason — a row learns this about itself only once its rounds are in.
    workCount: WORK_COUNT,
    workVerification: allocWorkVerification(rounds, WORK_COUNT, ALLOC_WINDOW_WRITES),
    perRound: rounds,
    allocFits: fits,
  };
}

// THE BY-SITE REPORT (rf2-rs8q6), as LINES rather than as `console.log` calls,
// for the reason this file already gives about `summariseAlloc`: "the mode is
// defined" and "the mode runs" are different claims, and a pin that can only
// read the source can only check the first. Returning the lines lets the pin
// DRIVE a collected row through the reporter and read what an operator would
// have seen.
//
// EMPTY OFF THE MODE, so a published run's summary is unchanged to the byte.
function allocSiteReport(row) {
  const out = [];
  if (!row.bySite) return out;

  const windows = (row.perRound || []).flatMap((r) =>
    Object.entries(r.arms || {}).map(([key, a]) => ({ round: r.round, key, a }))
  );
  const idles = (row.perRound || [])
    .map((r) => (r.controls || {}).idle)
    .filter((c) => c && c.siteWitness);

  out.push(';;');
  out.push(
    ';;   BY SITE (rf2-rs8q6) — the arm work unit opened at its ONE seam, across the round sequence.'
  );
  out.push(';;     dispatch = the dispatch-sync through the event pipeline and the signal graph');
  out.push(';;     drain    = the flushSync commit that follows it');
  out.push(
    ';;   A leg is exactly `dispatch + drain`, over the same outer pair of readings the shipped'
  );
  out.push(
    ';;   stride takes — so `rise`, `falls` and `maxStep` above are unchanged, and no median or'
  );
  out.push(
    ';;   attribution below adjudicates anything. ONE THING HERE DOES (rf2-4ctls): a NEGATIVE site'
  );
  out.push(
    ';;   step is a collection inside a leg, and it refuses the window through the intra-leg gate.'
  );
  out.push(
    ';;   EVERY FIGURE HERE CARRIES ONE EXTRA SAMPLER READ PER LEG. It is a constant per leg, so it'
  );
  out.push(
    ';;   can neither create nor destroy dispersion, but no magnitude below is comparable byte for'
  );
  out.push(';;   byte with one taken at a stride of 2. The idle control is what prices it:');

  if (idles.length) {
    for (const s of row.siteNames) {
      const xs = idles.map((c) => c.siteWitness.medians[s]);
      out.push(
        `;;     idle ${s.padEnd(8)} ${n0(median(xs))} B per leg, median over ${xs.length} idle windows ` +
          `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]`
      );
    }
  } else {
    out.push(';;     no idle control window carried a site split to price it with.');
  }

  if (!windows.length) {
    out.push(';;   no arm window was measured, so there is nothing to attribute.');
    return out;
  }

  // --- where each window's dispersion sits ---------------------------------
  //
  // THE LAST COLUMN IS `dominant`, AND ITS HEADING SAYS SO (rf2-stals). It is
  // `allocSiteWitness`'s TOTALS estimator — the site holding the largest sum of
  // per-leg |deviation from that site's own median|, across every measured leg.
  // The WORST LEG's own site is a different quantity (`legs[k].site`), and its
  // decomposition prints on the line immediately below this table's row, so both
  // are on screen at once. The heading previously read `worst-dev site`, which
  // named the one the column does not carry; readers quote the heading, not the
  // estimator, so the heading is the thing that has to be true.
  out.push(';;');
  out.push(
    ';;   round  window                                  ticks        legMed  dispMed  drainMed  dominant site (total |dev|)'
  );
  for (const { round, key, a } of windows) {
    const w = a.siteWitness;
    if (!w || !w.medians) continue;
    const worst = w.legs.reduce((m, l) => (Math.abs(l.deviation) > Math.abs(m.deviation) ? l : m));
    out.push(
      `;;   ${String(round).padEnd(6)} ${key.slice(0, 40).padEnd(40)} ` +
        `${String(a.tick0 ?? '—').padStart(6)}–${String(a.tick ?? '—').padEnd(6)} ` +
        `${n0(w.medians.leg).padStart(7)} ${n0(w.medians.dispatch).padStart(8)} ` +
        `${n0(w.medians.drain).padStart(9)}  ${String(w.dominant || 'none — the legs are alike')}`
    );
    if (worst.deviation !== 0) {
      out.push(
        `;;          worst leg ${worst.leg}: ${worst.deviation > 0 ? '+' : ''}${n0(worst.deviation)} B = ` +
          row.siteNames.map((s) => `${s} ${worst.by[s] > 0 ? '+' : ''}${n0(worst.by[s])}`).join(' + ')
      );
    }
  }

  // --- the hypothesis the bead names as the one to exclude first -----------
  out.push(';;');
  out.push(
    ';;   THE SAME-TERM HYPOTHESIS (the bead\'s "exclude this first"): the recurring excesses overlap'
  );
  out.push(
    ';;   the PRIME excess\'s own scale, which would suggest the same term recurring rather than a'
  );
  out.push(';;   distinct one. Sites disagreeing SETTLES it; sites agreeing narrows it.');
  out.push(
    ';;   round  window                                  prime disp  prime drain  prime sits in  measured sits in'
  );
  let agree = 0;
  let judged = 0;
  for (const { round, key, a } of windows) {
    const w = a.siteWitness;
    if (!w || !w.primeExcessBySite) continue;
    if (typeof w.sameSite === 'boolean') {
      judged++;
      if (w.sameSite) agree++;
    }
    out.push(
      `;;   ${String(round).padEnd(6)} ${key.slice(0, 40).padEnd(40)} ` +
        `${n0(w.primeExcessBySite.dispatch).padStart(10)} ${n0(w.primeExcessBySite.drain).padStart(12)}  ` +
        `${String(w.primeSite || '—').padEnd(14)} ${String(w.dominant || '— (no dispersion to place)')}`
    );
  }
  out.push(
    judged
      ? `;;   the two sites AGREE in ${agree} of ${judged} windows that had a dispersion to place.`
      : ';;   no window had both a prime excess and a measured dispersion, so the hypothesis is untested here.'
  );
  out.push(
    ';;   A site is two statements wide, so agreement is necessary for the same-term reading and not'
  );
  out.push(
    ';;   sufficient: read the magnitudes beside it. The prime excess is TIGHT across windows (p25'
  );
  out.push(
    ';;   6,800, p75 6,888 B on rf2-e9wr\'s 72 windows), so a recurring term of the same identity has'
  );
  out.push(';;   a magnitude to hit and not merely a site to share.');
  return out;
}

function summariseAlloc(row, refused) {
  const B = row.boundaries;
  // WHAT THIS RUN ACTUALLY MOUNTED, AND WHAT IT ACTUALLY DROVE (rf2-gxrr).
  //
  // The header states the SHAPE of the measurement, and under a narrowed plan
  // the full plan's sentences are not merely uninteresting — they are FALSE of
  // the run. "held FIXED across every rung" and "Q = E on every rung" describe
  // rungs a floor-only run never mounts; "the arm stays MOUNTED" describes an
  // arm a controls-only run never mounts; and "THE WRITE IS ..." names an
  // event a controls-only run never drives, because the selected write reaches
  // the page only inside the arm loop. These modes exist to be the PROVENANCE
  // of a validity witness, and a record that misstates its own measurement
  // shape cannot be that — a reader of V3's controls artefact would take a
  // named write for an executed one.
  //
  // MEASURED OFF THE ROUNDS, NOT READ OFF THE SWITCH. `writeDriven` asks the
  // record whether any arm window ran, which is the only thing that can drive
  // the write. Deriving it from `plan.arms` would be the instrument checking
  // its own configuration against itself and agreeing.
  //
  // The `full` branches are lifted out WHOLE rather than guarded in place, on
  // `summariseAllocFits`'s rule: today's published run is unchanged to the
  // byte, and the narrow plans simply do not reach those lines.
  const armed = row.plan.arms;
  const runged = row.plan.rungs;
  const writeDriven = row.perRound.some((r) => Object.keys(r.arms || {}).length > 0);
  row.writeDriven = writeDriven;
  if (runged) {
    console.log('\n;; ==== P0 STEADY-STATE ALLOCATION — WARM 1/3/7/20 READS (rf2-2rtt6.76) ====');
    console.log(
      `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
        'held FIXED across every rung'
    );
    console.log(
      `;; ${row.rounds} rounds x ${row.writes} warm bulk writes, after ${row.warmups} full-size ` +
        'warm-up windows. Q = E on every rung.'
    );
  } else if (armed) {
    console.log('\n;; ==== P0 STEADY-STATE ALLOCATION — FLOOR ARM ONLY, NO RUNG (rf2-gxrr) ====');
    console.log(
      `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries. ` +
        'NO RUNG WAS MOUNTED:'
    );
    console.log(
      ';; this run makes no statement about 1/3/7/20 reads, fits nothing, and reports no Q = E —'
    );
    console.log(
      ';; the floor arm holds no subscription, so there is no read count for Q = E to be about.'
    );
    console.log(
      `;; ${row.rounds} rounds x ${row.writes} warm bulk writes, after ${row.warmups} full-size ` +
        'warm-up windows.'
    );
  } else {
    console.log('\n;; ==== P0 STEADY-STATE ALLOCATION — CONTROLS ONLY, NO ARM (rf2-gxrr) ====');
    console.log(';; NO ARM WAS MOUNTED AND NO WRITE EVENT WAS DRIVEN. The three control windows');
    console.log(';; below are the whole measurement: no rung, no fit, no Q = E, and no statement');
    console.log(
      `;; about any substrate. The page this run was CONFIGURED for and never mounted is B = ${B}.`
    );
    console.log(
      `;; ${row.rounds} rounds x ${row.writes} warm bulk writes, after ${row.warmups} full-size ` +
        'warm-up windows.'
    );
  }
  console.log(
    `;; The window drives ${row.windowWrites} — the first ${row.primeWrites} is a PRIME and is in ` +
      'no figure below (rf2-oiy1).'
  );
  if (armed) {
    console.log(';; The arm stays MOUNTED across the window: this is what a standing page');
    console.log(';; allocates when it is written to, not what a mount costs.');
  }

  // THE ARM'S SIZE, AND WHERE IT CAME FROM (rf2-2rtt6.140). Printed beside the
  // figures rather than left in a source comment, because the one question a
  // reader of this table has to be able to answer is which page it was taken
  // on — the published 1,200-boundary witness is outside this instrument's
  // range and its refusal is what put this arm here.
  const a = row.arm;
  const publishedB = row.roots * row.publishedPerRoot.grid;
  console.log(';;');
  console.log(';; ==== THE PAGE, AND THE WRITE (rf2-2rtt6.140) ====');
  console.log(
    `;;   ${a.cells} cells x ${a.roots} roots = ${a.boundaries} boundaries, ${a.writes} writes a ` +
      `window. STATED, never derived: rf2-2rtt6.139`
  );
  console.log(
    ';;   retired the sizing constant as read off a run that itself refused, and no replacement'
  );
  console.log(
    `;;   may be substituted, so P0_ALLOC_CELLS is mandatory. The published ${publishedB}-boundary`
  );
  console.log(';;   witness is what this row is NOT.');
  // THE WRITE AND THE PLAN THIS RUN WAS CONFIGURED INTO (rf2-gxrr), printed
  // from the record rather than asserted from a literal. `page` and `full`
  // are the defaults every published row is taken under; anything else is a
  // validity witness saying so on its own face.
  //
  // AND A SELECTED WRITE IS NOT A DRIVEN ONE. `P0_ALLOC_WRITE` names the event
  // the ARM LOOP will drive, and a controls-only plan has no arm loop — so the
  // switch resolves, the record carries it, and nothing fires. Saying "THE
  // WRITE IS `:p0/write-page`" over that run would attribute an event that did
  // not execute, which is the one failure a provenance line may not have.
  //
  // AND UNDER `paired` THE SENTENCE IS A DIFFERENT ONE (rf2-irxrw). Neither
  // branch below is true of a run that drove both writes: the first says the
  // grid was rebuilt at B, the second calls the row V1's F_old control and
  // names the switch that selected it. So the paired shape is lifted out
  // WHOLE on `summariseAllocFits`'s rule — an unpaired run does not reach a
  // line of it and its output is unchanged to the byte.
  if (writeDriven && row.writePaired) {
    console.log(';;   THE TWO WRITES ARE DRIVEN AS A MATCHED PAIR (rf2-irxrw):');
    for (const leg of row.writeLegs) {
      const spec = ALLOC_WRITE_SPECS[leg];
      console.log(`;;     \`${spec.event}\` — ${spec.note}`);
    }
    console.log(
      ';;   EVERY ARM IS MEASURED UNDER BOTH — the FLOOR included, which is the point: the floor'
    );
    console.log(
      ';;   is the dominant shared term in (arm − floor), so a pairing that left it under one'
    );
    console.log(';;   write would buy nothing. Both legs run inside the SAME round, on the same');
    console.log(';;   page, in the same process, and the leg ORDER alternates on round parity so');
    console.log(';;   that neither write leads every round.');
    console.log(
      ';;   WHAT THAT REPLACES: until this switch one process drove one write, so every write-all'
    );
    console.log(
      ';;   versus write-page comparison differenced two sequential PROCESS runs in a fixed order'
    );
    console.log(
      ';;   and its two arms shared run order, session and floor. A residual read off such a'
    );
    console.log(';;   difference could not be separated from those terms (rf2-0gjqi).');
    console.log(
      ';;   THIS IS A VALIDITY-WITNESS CONFIGURATION, NOT A PUBLISHED ROW. `page` is the default,'
    );
    console.log(';;   and every published figure is taken under it alone.');
  } else if (writeDriven) {
    console.log(`;;   THE WRITE IS \`${row.write}\`.`);
    if (row.writeSelector === 'page') {
      console.log(
        `;;   It rebuilds the grid at ${B} cells — one per mounted boundary. \`:p0/write-all\``
      );
      console.log(
        ';;   rebuilt 300 whatever was mounted, and that fixed cost was 57% of the retired budget'
      );
      console.log(';;   before a single boundary had been measured.');
    } else {
      console.log(
        ';;   THIS IS V1\'s F_old CONTROL, NOT A PUBLISHED ROW — the fixed 300-cell rebuild, which'
      );
      console.log(
        `;;   is flat in B by construction and is the only way the two writes can be compared like`
      );
      console.log(';;   for like. Selected by P0_ALLOC_WRITE=all; the default is `page`.');
    }
  } else {
    console.log(';;   NO WRITE EVENT WAS DRIVEN, and no figure below is a reading of one. The');
    console.log(
      ';;   selected write reaches the page only inside the arm loop, and this plan mounted no'
    );
    console.log(
      `;;   arm — so P0_ALLOC_WRITE resolved to \`${row.writeSelector}\`, the record carries it,`
    );
    console.log(
      ';;   and nothing fired. The control windows below allocate doubles and nothing else.'
    );
  }
  // THE PROVENANCE OF EVERY WINDOW, ADJUDICATED AND RECORDED (rf2-irxrw). It
  // is on the row under every selection, because it is one claim rather than
  // a mode-dependent one; it is PRINTED under `paired`, where a reader has to
  // be able to see that the pairs are whole before differencing them, and
  // under any selection where it FAILED. A healthy unpaired run therefore
  // prints not one line of this and its summary is unchanged to the byte.
  const provenance = allocWriteProvenance(row);
  row.writeProvenance = provenance;
  if (row.writePaired) {
    console.log(
      `;;   PROVENANCE: ${provenance.windows} recorded window` +
        `${provenance.windows === 1 ? '' : 's'} across ${provenance.pairs} pair` +
        `${provenance.pairs === 1 ? '' : 's'} — ` +
        (provenance.ok
          ? 'every window names its own write and every pair is whole.'
          : `${provenance.unnamed.length} unnamed, ${provenance.incomplete.length} incomplete.`)
    );
  }
  for (const m of provenance.unnamed) console.log(`;;   WRITE UNNAMED ${m}`);
  for (const m of provenance.incomplete) console.log(`;;   PAIR INCOMPLETE ${m}`);
  console.log(';;   The clock, bulk, fan-out and retention rows drive `:p0/write-all` unchanged,');
  console.log(';;   at the published width, whatever this switch says.');
  console.log(
    `;;   THE PLAN IS \`${row.plan.name}\` — ` +
      `${row.plan.arms ? (row.plan.rungs ? 'controls, floor and every rung' : 'controls and the floor arm only') : 'the controls only, no arm mounted'}` +
      `${row.plan.fits ? ', fitted' : ', no fit'}.`
  );

  // --- the controls, adjudicated ----------------------------------------
  console.log(';;');
  console.log(';; ==== THE CONTROLS ====');
  const cstat = (f) => stat(row.perRound.map(f));
  const idle = cstat((r) => r.controls.idle.perIter);
  const c1 = cstat((r) => r.controls.ctl1.perIter);
  const c2 = cstat((r) => r.controls.ctl2.perIter);
  const d1 = row.controlDoubles.d1;
  const d2 = row.controlDoubles.d2;
  console.log(
    `;;   idle window (the sampler's own footprint): ${n0(idle.mean)} B/iteration ` +
      `[${n0(idle.min)}–${n0(idle.max)}]`
  );
  console.log(
    `;;   control D=${d1}: predicted ${8 * d1} B  |  measured ${n0(c1.mean)} B ` +
      `[${n0(c1.min)}–${n0(c1.max)}]  = ${(c1.mean / d1).toFixed(2)} B/double`
  );
  console.log(
    `;;   control D=${d2}: predicted ${8 * d2} B  |  measured ${n0(c2.mean)} B ` +
      `[${n0(c2.min)}–${n0(c2.max)}]  = ${(c2.mean / d2).toFixed(2)} B/double`
  );
  // The DIFFERENTIAL reading, which cancels every constant including the
  // sampler's own footprint. It is the one of the two that a residual
  // per-window overhead cannot flatter.
  const slopePerDouble = (c1.mean - c2.mean) / (d1 - d2);
  console.log(
    `;;   DIFFERENTIAL (D=${d1} less D=${d2}): ${slopePerDouble.toFixed(2)} B/double ` +
      '— cancels the sampler footprint and every other constant'
  );
  const within = (x) => Math.abs(x - 8) / 8 <= row.controlSlack;
  const controlOk = within(c1.mean / d1) && within(slopePerDouble);
  console.log(
    `;;   VERDICT (slack ±${(row.controlSlack * 100).toFixed(0)}% around 8 B/double): ` +
      `${controlOk ? 'OK — transient garbage IS visible to this counter' : 'FAILED'}`
  );
  console.log(
    ';;   A RETENTION instrument reads both control figures as ZERO. That is what the'
  );
  console.log(
    ';;   CDP sampling profiler does on this surface, and why it is not used here.'
  );
  row.controlVerdict = { ok: controlOk, perDouble: c1.mean / d1, differential: slopePerDouble };

  const falls = row.perRound.reduce(
    (a, r) => a + Object.values(r.arms).reduce((b, x) => b + x.falls, 0),
    0
  );
  const wins = row.perRound.reduce((a, r) => a + Object.keys(r.arms).length, 0);
  console.log(';;');
  // A RECORDED FACT, GATING NOTHING (rf2-2rtt6.140). It is an UPPER bound on
  // where the first collection runs and the retired masking budget needed a
  // LOWER one; it stays at its measured value and is printed because a
  // window's rise as a fraction of it is a useful thing for a reader to see.
  const risesSeen = row.perRound.flatMap((r) => Object.values(r.arms).map((x) => x.rise));
  console.log(
    `;;   measured fall threshold: ~${row.fallThresholdB} B of cumulative garbage per window ` +
      '(RECORDED, gates nothing). Enlarging the young generation does NOT move it.'
  );
  if (risesSeen.length) {
    console.log(
      `;;   largest arm-window rise seen: ${n0(Math.max(...risesSeen))} B = ` +
        `${((Math.max(...risesSeen) / row.fallThresholdB) * 100).toFixed(0)}% of that threshold`
    );
  }
  console.log(
    `;;   collections seen inside arm windows: ${falls} falling steps across ${wins} windows ` +
      '(a fall is EXCLUDED from the rising sum, never netted against it)'
  );
  // A FALL INSIDE A MEASURED WINDOW IS AN EXIT CONDITION, and the probe run
  // is why. Where a collection lands, the allocation between the last
  // reading before it and the collection itself is netted away inside that
  // single step, so the rising sum is an UNDER-estimate — and the probe
  // measured how large that can get: the same control object read 6.67
  // B/double at 320 KB per iteration and 1.38 B/double at 800 KB. A row
  // that reported the second figure would be publishing the collector's
  // schedule as a property of the arm, and the arms all read LOW under
  // that fault, which is the direction that flatters a candidate whose
  // predicted answer is zero.
  row.fallsInMeasuredWindows = falls;

  // THE FALLS GATE'S OTHER BLIND SIDE, NOW READ (rf2-4ctls). A collection inside
  // ONE LEG, bracketed by a larger allocation in the same leg, turns no step
  // negative on the collapsed stream — so the line above reports 0 — and leaves
  // the leg's NET inside τ, so the certificate below reports clean. The by-site
  // stride sees it directly, and this counts what it saw.
  //
  // RECORDED AND PRINTED ONLY UNDER THE MODE, on `allocSiteReport`'s rule and
  // `siteWitness`'s: off it there are no site legs, so a 0 here — in the line
  // or in the record — would claim the instrument looked when it could not.
  // Every published row is a stride-2 row, so neither the summary nor the raw
  // record's shape moves on one.
  if (row.bySite) {
    const intraLegReasons = allocRefusedWindows(row, 'intraLegRefusals');
    row.intraLegRefusalReasons = intraLegReasons.length;
    const intraWindows = row.perRound.reduce(
      (a, r) => a + Object.values(r.arms).filter((x) => (x.intraLegRefusals || []).length).length,
      0
    );
    console.log(
      `;;   intra-leg reclamations: ${intraLegReasons.length} negative site step` +
        `${intraLegReasons.length === 1 ? '' : 's'} across ${intraWindows} of ${wins} windows ` +
        '(a collection INSIDE a leg, which turns no step negative on the collapsed stream and ' +
        'leaves the leg NET inside τ — invisible to both the other gates, and REFUSED here)'
    );
  }

  // THE OBSERVED-COLLECTION WITNESS (rf2-2rtt6.140), which is the fall gate's
  // blind side and a SECOND exit, not a softening of the first. A collection
  // that runs inside a leg allocating at least as much as it reclaims never
  // turns a step negative, so the line above reports 0 and the window
  // under-reads with nothing to say so. This one reads the window's own legs
  // and asks whether they look like repetitions of one work unit.
  const deviations = row.perRound.flatMap((r) =>
    Object.values(r.arms)
      .map((x) => x.legWorstDeviation)
      .filter((x) => typeof x === 'number')
  );
  // TWO QUANTITIES, NAMED APART (rf2-xxeq). `refusedWindows` is windows and is
  // what the denominator below is comparable to; `refusalReasons` is the length
  // of the list printed underneath, which is what the exit code keys off.
  const refusedCount = allocRefusedWindowCount(row);
  row.refusedWindows = refusedCount;
  row.refusalReasons = refused.length;
  console.log(
    `;;   leg tolerance τ = ${(row.legTolerance * 100).toFixed(0)}% of the window's own leg ` +
      'MEDIAN, two-sided. The legs of a window are W repetitions of ONE'
  );
  console.log(
    ';;   work unit, so a leg below its cohort is a leg something removed bytes from and a leg'
  );
  console.log(
    ';;   above it is a window whose one-work-unit premise failed. An ADMITTED window under-reads'
  );
  console.log(`;;   its true allocation by AT MOST 2τ = ${(row.legTolerance * 200).toFixed(0)}%.`);

  // THE PRIME LEG, PUBLISHED AS A DIAGNOSTIC (rf2-oiy1). It is in no figure
  // above or below and in no certificate, and printing it is the whole reason
  // this repair is not "discard leg 1": the term that made every floor window
  // uncertifiable is still measured, on every window, and is now readable
  // beside the cohort it used to contaminate. rf2-e9wr's τ calibration wants
  // exactly this number.
  const primes = row.perRound.flatMap((r) => {
    const arms = Object.values(r.arms || {});
    const from = arms.length ? arms : Object.values(r.controls || {});
    return from.map((x) => x.primeExcess).filter((x) => typeof x === 'number');
  });
  // THE WORK CENSUS (rf2-n1b9h) — three monotone counters read at every
  // window's open and close, printed BESIDE that window's `legMedian` so the
  // two quantities the bead compares are on one line.
  //
  // `rf2-77gz8` left two candidates for its 3,792 B second mode: more work per
  // write, against the same work allocating more per invocation. `legMedian`
  // says which MODE a window sits in; these three say what RAN inside it. The
  // table is the whole reading, and it is printed for every window rather than
  // for a chosen pair, because which windows are high is not known until the
  // run is over.
  const wv = row.workVerification;
  if (wv && wv.counted) {
    console.log(';;');
    console.log(
      ';;   THE WORK CENSUS (rf2-n1b9h): event-handler invocations, subscription recomputations'
    );
    console.log(
      `;;   and boundary renders, per window. The window drives ${wv.windowWrites} writes, so`
    );
    console.log(';;   `events` is the count a single-invocation-per-dispatch pipeline predicts.');
    console.log(';;');
    console.log(
      ';;   round  window                                  ticks        legMedian  events  subs  renders'
    );
    for (const r of row.perRound) {
      for (const [key, a] of Object.entries(r.arms || {})) {
        const d = a.workDelta;
        if (!d) continue;
        console.log(
          `;;   ${String(r.round).padEnd(6)} ${key.slice(0, 40).padEnd(40)} ` +
            `${String(a.tick0 ?? '-').padStart(6)}-${String(a.tick ?? '-').padEnd(6)} ` +
            `${String(a.legMedian ?? '-').padStart(9)}  ${String(d.events).padStart(6)}  ` +
            `${String(d.subs).padStart(4)}  ${String(d.renders).padStart(7)}`
        );
      }
    }
    console.log(';;');
    console.log(
      `;;   CONTROLS: ${wv.controlsMoved.length === 0 ? 'every control window read 0/0/0 — the census does not move where nothing is dispatched' : 'MOVED, which is an instrument fault: ' + wv.controlsMoved.join('; ')}`
    );
    for (const [key, vs] of Object.entries(wv.events)) {
      console.log(
        `;;   ${key}: events ${vs.join(',')} · subs ${(wv.subs[key] || []).join(',')} · ` +
          `renders ${(wv.renders[key] || []).join(',')}  (distinct values over ${row.rounds} rounds)`
      );
    }
    if (wv.offExpectation.length) {
      console.log(
        `;;   OFF EXPECTATION (events != ${wv.windowWrites}): ${wv.offExpectation.join('; ')} — this` +
          ' is a FINDING, not a fault: more handler invocations per dispatch IS candidate (a).'
      );
    }
  }

  console.log(';;');
  console.log(
    `;;   THE PRIME LEG (excluded from every figure): the window's first work unit runs AFTER the`
  );
  console.log(
    `;;   forced collection and before the ${row.writes} measured ones. 336 of 336 windows in the`
  );
  console.log(
    ';;   rf2-2rtt6.140 measurement carried a ~7 KB first-leg excess that three full-size warm-ups'
  );
  console.log(
    ';;   could not reach, because every warm-up lands on the far side of that collection and the'
  );
  console.log(
    ';;   window\'s own tail legs are byte-identical — so ONE work unit after it is what clears it.'
  );
  if (primes.length) {
    const p = stat(primes);
    console.log(
      `;;   prime excess over the measured cohort median: ${n0(p.mean)} B mean ` +
        `[${n0(p.min)}–${n0(p.max)}] across ${primes.length} windows`
    );
  } else {
    console.log(';;   no window carried a prime leg to report.');
  }

  // THE BY-SITE REPORT (rf2-rs8q6). Empty off the mode, so nothing above or
  // below moves on a published run.
  for (const line of allocSiteReport(row)) console.log(line);

  console.log(
    `;;   windows refused: ${refusedCount} of ${wins}, ${refused.length} refusal reason` +
      `${refused.length === 1 ? '' : 's'} in all (a window can fail on more than one leg)` +
      (deviations.length
        ? `  (worst leg deviation observed ${(Math.max(...deviations.map(Math.abs)) * 100).toFixed(1)}%)`
        : '')
  );
  for (const m of refused) console.log(`;;   REFUSED ${m}`);

  console.log(
    `;;   verification: ${row.verification.unverified} unverified ` +
      '(mount read-backs AND the warm-write read-back)'
  );
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  // --- the rows ----------------------------------------------------------
  //
  // ONLY WHAT WAS MOUNTED (rf2-gxrr). A controls-only plan prints no arm
  // table and a floor-only plan prints no rung, because a table of absent
  // arms invites a reader to take absence for zero — which on this row is
  // the very answer HD-002 predicts.
  if (!row.plan.arms) {
    console.log(';;');
    console.log(';; ---- NO ARM WAS MOUNTED: this run is the CONTROL PATH ONLY ----');
    console.log(';;   The three controls above are the whole measurement. Nothing below this');
    console.log(';;   line is a statement about any substrate.');
  }
  //
  // ONE TABLE PER WRITE (rf2-irxrw). A `paired` run measured every arm twice
  // and a single table would have to pick one or average them; both would be
  // a table that cannot be read back to a window. `allocWindowKey` is the
  // identity off `paired` and the sub-header is printed only on it, so an
  // unpaired run's table is the table it always was, to the byte.
  const paired = Boolean(row.writePaired);
  const tableLegs = row.writeLegs || [row.writeSelector];
  for (const segment of row.plan.arms ? Object.keys(LADDER_SUBSTRATES) : []) {
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    for (const selector of tableLegs) {
      if (paired) {
        console.log(`;;   under \`${ALLOC_WRITE_SPECS[selector].event}\` (P0_ALLOC_WRITE=paired)`);
      }
      console.log(
        ';; arm            reads        B/boundary/write [min–max]        B/write        falls'
      );
      const floorKey = allocWindowKey(`${segment}|grid/floor`, selector, paired);
      const fl = stat(row.perRound.map((r) => r.arms[floorKey].perBoundaryPerWrite));
      const flw = stat(row.perRound.map((r) => r.arms[floorKey].perWrite));
      console.log(
        `;; floor              — ${(n0(fl.mean) + ' [' + n0(fl.min) + '–' + n0(fl.max) + ']').padStart(30)}` +
          `${n0(flw.mean).padStart(15)}   (no subscription: the WRITE's own cost)`
      );
      for (const sub of row.plan.rungs ? LADDER_SUBSTRATES[segment] : []) {
        for (const R of LADDER_RUNGS) {
          const key = allocWindowKey(`${segment}|lad/${sub}#R${R}`, selector, paired);
          const s = stat(row.perRound.map((r) => r.arms[key].perBoundaryPerWrite));
          const w = stat(row.perRound.map((r) => r.arms[key].perWrite));
          const f = row.perRound.reduce((a, r) => a + r.arms[key].falls, 0);
          console.log(
            `;; ${sub.padEnd(11)}${String(R).padStart(6)} ` +
              `${(n0(s.mean) + ' [' + n0(s.min) + '–' + n0(s.max) + ']').padStart(30)}` +
              `${n0(w.mean).padStart(15)}${String(f).padStart(9)}` +
              (R === 0 ? '   (anchor — regressed nowhere; cannot re-render)' : '')
          );
        }
      }
    }
  }

  // --- the fitted lines ---------------------------------------------------
  //
  // A fit is over the RUNGS (rf2-gxrr). A plan that carries none has nothing
  // to regress and prints nothing to regress it from — lifted out whole
  // rather than guarded in place, so the published `full` run's output is
  // unchanged to the byte and the narrow plans simply do not reach it.
  if (row.plan.fits) {
    summariseAllocFits(row);
  } else {
    console.log(';;');
    console.log(';; ==== NO FITTED LINE: THIS PLAN CARRIES NO RUNGS ====');
    console.log(
      `;;   \`${row.plan.name}\` mounts no 1/3/7/20 rung, so there is no slope to fit and none is`
    );
    console.log(';;   reported. V1 and V3 both state "no fits" in their own configurations, and');
    console.log(';;   a line through one point would be the instrument answering a question it');
    console.log(';;   was not asked.');
  }

  console.log(';;');
  console.log(';; ==== ARM-ORDER NOTE (alloc) ====');
  console.log(';;   This row mounts each arm and keeps it for the whole window, so it produces');
  console.log(";;   no mount/release sample stream for `order-guard`'s phase test. Segment order");
  console.log(';;   still alternates by round parity and slot order is still the guard\'s, so the');
  console.log(';;   ranges below are across BOTH orders — but the guard itself does not');
  console.log(';;   adjudicate this row and no figure here claims its verdict.');
}

// The fitted lines and HD-002's own question — the tail of `summariseAlloc`,
// which runs only under a plan that carried rungs (rf2-gxrr).
function summariseAllocFits(row) {
  console.log(';;');
  console.log(';; ==== THE FITTED LINES —  y = intercept + slope·R,  over 1/3/7/20 ONLY ====');
  console.log(';;   y is bytes ALLOCATED per boundary per warm write. The slope is what one');
  console.log(';;   more read costs a boundary that already reads, on a re-render whose READ');
  console.log(';;   SET DID NOT CHANGE. R=0 is the anchor and is regressed nowhere.');
  const fits = row.allocFits;
  for (const id of Object.keys(fits.mean)) {
    const m = fits.mean[id];
    const per = fits.perRound[id];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${id.padEnd(22)} slope ${n0(m.slope).padStart(6)} B/read ${rng((f) => f.slope)}` +
        `   intercept ${n0(m.intercept)} B ${rng((f) => f.intercept)}`
    );
    console.log(
      `;;     shell (R=0, measured) ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   ·   first read ${n0(m.firstRead)} B ${rng((f) => f.firstRead)}` +
        `   ·   r² ${m.r2.toFixed(5)}`
    );
    const nonlinear = per.filter((f) => !f.linear).length;
    console.log(
      `;;     ${m.linear ? 'LINE' : 'NOT A LINE'} — ${m.why}` +
        `  (per-round: ${per.length - nonlinear} of ${per.length} rounds linear)`
    );
  }

  // --- HD-002's own question ---------------------------------------------
  console.log(';;');
  console.log(';; ==== WHAT HD-002 PREDICTED, AND WHAT THE ROW SAYS ====');
  console.log(';;   HD-002 (hd-002-adjudication.md): "allocation is proportional to the CHANGE,');
  console.log(';;   not to the read count... the allocation slope across warm 1/3/7/20 reads is');
  console.log(';;   FLAT AT ZERO", falsified by a non-flat slope. That is a claim about EDGE');
  console.log(';;   MAINTENANCE, and every substrate here also allocates R query vectors, R sub');
  console.log(';;   recomputations and a React element tree. So the quantity that answers it is');
  console.log(';;   the candidate slope LESS the same-run donor slope, in the SAME segment.');
  const slopeOf = (id) => fits.mean[id] && fits.mean[id].slope;
  // AND ONCE PER WRITE UNDER `paired` (rf2-irxrw). The difference is stated
  // "in the SAME segment" because a cross-segment one would compare two
  // pages; a cross-WRITE one would compare two writes, which is the same
  // error one axis over. `allocWindowKey` is the identity off `paired`, so
  // the published run prints the two lines it always did.
  const paired = Boolean(row.writePaired);
  for (const seg of Object.keys(LADDER_SUBSTRATES)) {
    for (const selector of row.writeLegs || [row.writeSelector]) {
      const donor = LADDER_SUBSTRATES[seg][0];
      const hc = slopeOf(allocWindowKey(`${seg}|hicasso`, selector, paired));
      const dn = slopeOf(allocWindowKey(`${seg}|${donor}`, selector, paired));
      if (typeof hc !== 'number' || typeof dn !== 'number') continue;
      console.log(
        `;;   ${seg.padEnd(13)} candidate ${n0(hc)} − ${donor} ${n0(dn)} = ` +
          `${n0(hc - dn)} B/read of EXCESS steady-state allocation` +
          (paired ? `   (under \`${ALLOC_WRITE_SPECS[selector].event}\`)` : '')
      );
    }
  }
}

// The candidate's structural claim, as numbers the run exits on. The
// arm IS the package's own collector, so its index and cell tables can be
// counted, and "one subscription/epoch hook per boundary plus N edges
// in a shared index" stops being a sentence in a docstring:
//
//   boundaries === B     one registration per boundary THAT READS —
//                        0 at R=0 (see below)
//   edges      === B·R   the reads live as index edges, not as hooks
//   cells      === Q     one cell per unique (frame, query) — Q = E here
//   entries    === B     one read-set entry per boundary AT Q = E, because
//                        no two boundaries read the same SET; the entry
//                        cache's sharing buys nothing on this witness and
//                        the row says so rather than claiming it does
//
// `boundaries` IS 0 AT R=0, AND THE ROW ASSERTS IT RATHER THAN EXCUSING
// IT. Since rf2-dabt3 fused the sub-index into the cell table there is no
// per-boundary registry to count: the runtime knows a boundary only
// through the reader lists of the cells it reads, so an edgeless boundary
// retains no membership anywhere and is correctly absent. That is the
// property the fusion was taken FOR — one reader list on the cell that
// already existed, in place of a map entry per mounted boundary whether
// or not it read — so the ladder pins it as a positive claim: at R=0 the
// count must be 0, and any non-zero reading means a mounted boundary is
// being retained by something. (`entries` says the same thing from the
// other side: 1 at R=0, the empty read-set, not B.)
//
// and on a DONOR arm every one of them must be 0 — the check that the
// candidate's runtime is not standing behind the rows it is compared to.
function ladderStructuralFailures(row) {
  const out = [];
  const B = row.plan[0].arms[0].boundaries;
  for (const r of row.perRound) {
    for (const [key, a] of Object.entries(r.arms)) {
      const h = a.verify && a.verify.hicasso;
      if (!h) continue;
      const hicasso = a.arm === 'lad/hicasso';
      const R = a.reads || 0;
      const want = hicasso
        ? {
            boundaries: R === 0 ? 0 : B,
            edges: B * R,
            cells: B * R,
            entries: R === 0 ? 1 : B,
          }
        : { boundaries: 0, edges: 0, cells: 0, entries: 0 };
      for (const f of Object.keys(want)) {
        if (h[f] !== want[f]) {
          out.push(`round ${r.round} ${key}: hicasso ${f} ${h[f]}, expected ${want[f]}`);
        }
      }
      const res = a.structural;
      if (res) {
        for (const f of ['cells', 'cellRefs', 'boundaries', 'edges', 'entries']) {
          if (res[f] !== 0) {
            out.push(
              `round ${r.round} ${key}: residue after teardown — hicasso ${f} ${res[f]}, expected 0`
            );
          }
        }
      }
    }
  }
  return out;
}

// THE HEAP ROW'S POSITIVE CONTROL, PRINTED ONCE (rf2-egdaq). All three heap
// summaries below publish the same control taken the same way, and they used
// to say so in three copies that had to be edited together — which is how two
// of them could have kept naming the overlap rule after the row had stopped
// using it. A published record that names the wrong adjudicator is worse than
// one that names none: it invites a reader to apply the other rule's reading
// to a number it never adjudicated.
//
// The verdict NAMES ITS OWN RULE (`verdict.rule`) rather than this printer
// asserting one, for that same reason — the string comes from the answer.
//
// So the VERDICT line below names no adjudicator of its own. It used to
// carry the literal `lane/control-verdict-strict` beside the dynamic
// `rule`, which the merged-PR audit of #8574 caught: a caller moved back
// to the overlap rule would have printed both names in one sentence, and
// the hardcoded half is the one a reader believes. `:rule` is the lane's
// own identifier for which of its two rules answered — `every-round` here,
// `overlap` there — so it is the whole label this record needs.
function printHeapControl(row) {
  const c = row.control.measured;
  console.log(
    `;; positive control: predicted ${CONTROL_PREDICTED} B  |  measured ${Math.round(c.mean)} B ` +
      `[${Math.round(c.min)}–${Math.round(c.max)}]  (ratio ${(c.mean / CONTROL_PREDICTED).toFixed(4)})`
  );
  const v = row.control.verdict;
  console.log(
    `;;   VERDICT (rule ${v.rule}, ` +
      `slack ±${(row.control.slack * 100).toFixed(0)}%): ${v.ok ? 'OK' : 'FAILED'}`
  );
  console.log(`;;     ${v.why}`);
  console.log(';;     (the shared rule words its figures with an "x"; this control\'s unit is BYTES)');
  // EVERY ROUND, PASS OR FAIL. The rule adjudicates per round, so a record
  // that printed only the range would be quoting a summary the rule did not
  // read — and the rounds are what a later reader needs to re-adjudicate
  // under the other rule without re-running the window.
  const per = row.control.perRound || [];
  if (per.length) {
    console.log(`;;     rounds: ${per.map((x) => Math.round(x)).join(', ')} B`);
  }
  for (const o of v.outside || []) {
    console.log(
      `;;     OUTSIDE round ${o.round}: ${Math.round(o.measured)} B, ` +
        `off by ${(o.offBy * 100).toFixed(2)}% of the prediction`
    );
  }
}

function summariseLadder(row, structuralFailures) {
  const B = row.plan[0].arms[0].boundaries;
  console.log('\n;; ==== P0 RETAINED HEAP — THE READS LADDER (rf2-2rtt6.34) ====');
  console.log(
    `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
      `held FIXED across every rung`
  );
  console.log(`;; ${row.rounds} rounds. Reads walk ${LADDER_RUNGS.join('/')}; Q = E on every rung`);
  console.log(';; (distinct-query, the mandatory worst-case witness — every read its own key).');
  console.log(';; Q is COUNTED off the frame\'s own sub-cache on every mount, not asserted.');
  printHeapControl(row);
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  for (const { segment } of row.plan) {
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    const floorKey = `${segment}|grid/floor`;
    const floorStat = stat(row.perRound.map((r) => r.arms[floorKey].bytesPerBoundaryCdp));
    console.log(
      ';; arm            reads      B        E        Q     exclusive B/boundary [min–max]   residue B/bdy'
    );
    const floorRes = stat(row.perRound.map((r) => r.arms[floorKey].residueCdp / B));
    console.log(
      `;; floor              0 ${String(B).padStart(7)} ${String(0).padStart(8)} ${String(0).padStart(8)}   ` +
        `${n0(floorStat.mean).padStart(8)} [${n0(floorStat.min)}–${n0(floorStat.max)}]`.padEnd(26) +
        `${n0(floorRes.mean).padStart(6)} [${n0(floorRes.min)}–${n0(floorRes.max)}]` +
        '  (absolute, the calibrator)'
    );
    for (const sub of LADDER_SUBSTRATES[segment]) {
      for (const R of LADDER_RUNGS) {
        const key = `${segment}|lad/${sub}#R${R}`;
        const excl = stat(
          row.perRound.map(
            (r) => r.arms[key].bytesPerBoundaryCdp - r.arms[floorKey].bytesPerBoundaryCdp
          )
        );
        // Residue PER BOUNDARY, so it is on the same axis as the
        // exclusive column beside it and comparable with the published
        // ±11 B/boundary the predecessor ladder reported. As a total it
        // reads in the tens of thousands on a 71 MB arm and looks like a
        // leak; divided by B it is the width of this instrument's zero.
        const res = stat(row.perRound.map((r) => r.arms[key].residueCdp / B));
        console.log(
          `;; ${sub.padEnd(11)}${String(R).padStart(6)} ${String(B).padStart(7)} ` +
            `${String(B * R).padStart(8)} ${String(B * R).padStart(8)}   ` +
            `${n0(excl.mean).padStart(8)} [${n0(excl.min)}–${n0(excl.max)}]`.padEnd(26) +
            `${n0(res.mean).padStart(6)} [${n0(res.min)}–${n0(res.max)}]` +
            (R === 0 ? '   (anchor — regressed nowhere)' : '')
        );
      }
    }
  }

  console.log(';;');
  console.log(';; ==== THE FITTED LINES —  y = intercept + slope·R,  over 1/3/7/20 ONLY ====');
  console.log(';;   The slope is MARGINAL: what the next read costs once a boundary already');
  console.log(';;   reads. The first read is a separate quantity and is printed beside it.');
  console.log(';;   `shell` is the DIRECTLY MEASURED R=0 rung, never the fitted intercept.');
  const fits = row.ladderFits;
  for (const id of Object.keys(fits.mean)) {
    const m = fits.mean[id];
    const per = fits.perRound[id];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${id.padEnd(22)} slope ${n0(m.slope).padStart(6)} B/read ${rng((f) => f.slope)}` +
        `   intercept ${n0(m.intercept)} B ${rng((f) => f.intercept)}`
    );
    console.log(
      `;;     shell (R=0, measured) ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   ·   first read ${n0(m.firstRead)} B ${rng((f) => f.firstRead)}` +
        `   ·   r² ${m.r2.toFixed(5)}`
    );
    const nonlinear = per.filter((f) => !f.linear).length;
    console.log(
      `;;     ${m.linear ? 'LINE' : 'NOT A LINE'} — ${m.why}` +
        `  (per-round: ${per.length - nonlinear} of ${per.length} rounds linear)`
    );
  }

  console.log(';;');
  console.log(';; ==== THE CANDIDATE, AGAINST THE DONORS TAKEN IN THE SAME RUN ====');
  console.log(';;   Same instrument, same rounds, same collector, same guard — which is what');
  console.log(';;   validation.md:180-189 requires, and what makes the margin quotable at all.');
  console.log(';;   A margin under 5% is INSTRUMENT-LIMITED and is not a pass.');
  const slopeOf = (id) => fits.mean[id] && fits.mean[id].slope;
  const rg = slopeOf('reagent-subs|reagent');
  const ux = slopeOf('uix-subs|uix');
  for (const seg of Object.keys(LADDER_SUBSTRATES)) {
    const hc = slopeOf(`${seg}|hicasso`);
    if (typeof hc !== 'number') continue;
    const line = (name, donor) =>
      typeof donor === 'number'
        ? `${name} ${(hc / donor).toFixed(4)}x (${n0(hc)} vs ${n0(donor)} B/read, ` +
          `margin ${(100 * (donor - hc) / donor).toFixed(1)}%)`
        : `${name} —`;
    console.log(`;;   hicasso in ${seg.padEnd(13)} ${line('vs Reagent', rg)}   ${line('vs UIx', ux)}`);
  }
  if (typeof slopeOf('reagent-subs|hicasso') === 'number' &&
      typeof slopeOf('uix-subs|hicasso') === 'number') {
    const a = slopeOf('reagent-subs|hicasso');
    const b = slopeOf('uix-subs|hicasso');
    console.log(
      `;;   the candidate's two segments: ${n0(a)} and ${n0(b)} B/read — ` +
        `${(100 * Math.abs(a - b) / ((a + b) / 2)).toFixed(2)}% apart. NOT a seam figure: the`
    );
    console.log(
      ';;   arm needs neither adapter\'s hooks, but `subs/subscribe` builds a reaction whose'
    );
    console.log(
      ';;   implementation is the INSTALLED adapter\'s, so these are one view layer over two'
    );
    console.log(
      ';;   subscription substrates. Compare each against the donor measured beside it.'
    );
  }

  console.log(';;');
  console.log(';; ==== THE STRUCTURAL WITNESS (counted, and exited on) ====');
  console.log(';;   boundaries = B (0 at R=0) · edges = B·R · cells = Q · entries = B (1 at R=0)');
  console.log(';;   on the candidate; all four ZERO on every donor arm; and every field zero');
  console.log(';;   again after teardown — HD-002 clause (d) in objects rather than in bytes.');
  console.log(';;   The R=0 zero is the fused design\'s own claim (rf2-dabt3): with the');
  console.log(';;   sub-index living on the cell table, an edgeless boundary retains no');
  console.log(';;   membership, so a NON-zero reading there is a retention bug.');
  if (structuralFailures.length === 0) {
    console.log(';;   VERDICT: every arm of every round answered its expected counts.');
  } else {
    for (const f of structuralFailures.slice(0, 40)) console.log(`;;   FAILED ${f}`);
    if (structuralFailures.length > 40) {
      console.log(`;;   … and ${structuralFailures.length - 40} more`);
    }
  }

  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (ladder) ====');
  console.log(
    `;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`
  );
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
}

// ---------------------------------------------------------------------------
// The summary
// ---------------------------------------------------------------------------

const stat = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  return { mean, min: s[0], max: s[s.length - 1] };
};

// The clock row's two gates, stated where a reader will look for them
// rather than buried inside the record's EDN. Both were printed and
// neither was adjudicated until rf2-95s5b; the exit logic below reads
// exactly these figures.
function summariseClock(c) {
  console.log(';;');
  console.log(';; ==== P0 CLOCK — VERIFICATION AND POSITIVE CONTROL ====');
  console.log(`;;   verification: ${c.verification.unverified} unverified of ${c.verification.of} windows`);
  console.log(`;;                 ${c.verification.perRow}`);
  console.log(';;   The positive control is NOT in that denominator and cannot be: its two');
  console.log(';;   arms build DIFFERENT pages on purpose, so no window of it has anything to');
  console.log(';;   read back. It is reported and adjudicated separately, here:');
  console.log(
    `;;   positive control (slack ±${(c.control.slack * 100).toFixed(0)}%): ` +
      `${c.control.ok ? 'OK' : 'FAILED'} — ${c.control.why}`
  );
}

function summariseHeap(row) {
  console.log('\n;; ==== P0 RETAINED HEAP — bytes per boundary ====');
  console.log(`;; ${row.roots} roots held per arm; list=${row.perRoot.list} rows, grid=${row.perRoot.grid} cells`);
  printHeapControl(row);
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  console.log(';;   (a mount is verified on TWO read-backs: the boundary elements it produced,');
  console.log(";;    and the unique query keys the frame's sub-cache is holding — B and Q)");
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);
  console.log(';;');
  console.log(';; arm                              B/boundary (mean) [min-max]     residue B');
  const keys = Object.keys(row.perRound[0].arms);
  const byKey = {};
  for (const k of keys) {
    const a = stat(row.perRound.map((r) => r.arms[k].bytesPerBoundaryCdp));
    const res = stat(row.perRound.map((r) => r.arms[k].residueCdp));
    byKey[k] = a;
    console.log(
      `;; ${k.padEnd(32)} ${String(Math.round(a.mean)).padStart(8)} ` +
        `[${Math.round(a.min)}–${Math.round(a.max)}]`.padEnd(20) +
        `${String(Math.round(res.mean)).padStart(10)}`
    );
  }

  // --- the red-zone figure, per family -----------------------------------
  console.log(';;');
  console.log(';; ==== P0 RED-ZONE (retained heap) — UIx over Reagent, per witness family ====');
  console.log(';;   EXCLUSIVE = arm - floor, the substrate\'s OWN standing cost, measured');
  console.log(';;   in the same segment. That is the axis validation.md states the budget on.');
  const redZone = {};
  for (const family of ['list', 'grid']) {
    const perRound = row.perRound.map((r) => {
      const rf = r.arms[`reagent-subs|${family}/floor`].bytesPerBoundaryCdp;
      const rs = r.arms[`reagent-subs|${family}/reagent`].bytesPerBoundaryCdp;
      const uf = r.arms[`uix-subs|${family}/floor`].bytesPerBoundaryCdp;
      const us = r.arms[`uix-subs|${family}/uix`].bytesPerBoundaryCdp;
      return { exclusive: (us - uf) / (rs - rf), absolute: us / rs };
    });
    const ex = stat(perRound.map((p) => p.exclusive));
    const ab = stat(perRound.map((p) => p.absolute));
    redZone[family] = { exclusive: ex, absolute: ab, perRound };
    const straddles = ex.min <= 1.0 && ex.max >= 1.0;
    console.log(
      `;;   ${family.padEnd(6)} EXCLUSIVE ${ex.mean.toFixed(4)}x [${ex.min.toFixed(4)}–${ex.max.toFixed(4)}]` +
        `   absolute ${ab.mean.toFixed(4)}x [${ab.min.toFixed(4)}–${ab.max.toFixed(4)}]` +
        (straddles ? '   RANGE STRADDLES 1.0 — INDISTINGUISHABLE' : '')
    );
  }
  row.redZone = redZone;
  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (heap) ====');
  console.log(`;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`);
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
}

// ---------------------------------------------------------------------------

const n0 = (x) => (typeof x === 'number' && isFinite(x) ? String(Math.round(x)) : '—');

function summariseFanout(row) {
  const B = row.plan[0].arms[0].boundaries;
  console.log('\n;; ==== P0 RETAINED HEAP — THE FAN-OUT SWEEP (rf2-5prok) ====');
  console.log(
    `;; ${row.roots} root(s) held per arm, ${row.perRoot.grid} cells each — B = ${B} boundaries, ` +
      `held FIXED across every rung`
  );
  console.log(`;; ${row.rounds} rounds. Q is COUNTED off the frame's own sub-cache on every mount,`);
  console.log(';; not asserted by the plan — an unstamped or mis-stamped rung is an unverified mount.');
  printHeapControl(row);
  console.log(`;; verification: ${row.verification.unverified} unverified of ${row.verification.mounts} mounts`);
  for (const d of row.verification.detail || []) console.log(`;;   UNVERIFIED ${d}`);

  for (const segment of Object.keys(FAN_SUBSTRATE)) {
    const sub = FAN_SUBSTRATE[segment];
    console.log(';;');
    console.log(`;; ---- ${segment} ----`);
    console.log(
      ';; rung    reads    B      E      Q     E/B    E/Q    exclusive B/boundary [min–max]   residue'
    );
    const floorKey = `${segment}|grid/floor`;
    const line = (label, key, reads, keys) => {
      const excl = stat(
        row.perRound.map((r) => r.arms[key].bytesPerBoundaryCdp - r.arms[floorKey].bytesPerBoundaryCdp)
      );
      const res = stat(row.perRound.map((r) => r.arms[key].residueCdp));
      const E = B * reads;
      console.log(
        `;; ${label.padEnd(8)}${String(reads).padStart(3)}  ${String(B).padStart(6)} ` +
          `${String(E).padStart(6)} ${String(keys).padStart(6)} ` +
          `${reads.toFixed(2).padStart(5)}  ${(keys ? (E / keys).toFixed(2) : '—').padStart(5)}   ` +
          `${n0(excl.mean).padStart(8)} [${n0(excl.min)}–${n0(excl.max)}]`.padEnd(24) +
          `${n0(res.mean).padStart(9)}`
      );
    };
    const floorStat = stat(row.perRound.map((r) => r.arms[floorKey].bytesPerBoundaryCdp));
    console.log(
      `;; floor     0  ${String(B).padStart(6)} ${String(0).padStart(6)} ${String(0).padStart(6)} ` +
        ` 0.00      —   ${n0(floorStat.mean).padStart(8)} [${n0(floorStat.min)}–${n0(floorStat.max)}]` +
        '   (absolute, the calibrator)'
    );
    for (const g of fanRungs(B)) line(g.rung, `${segment}|fan/${sub}#${g.rung}`, g.reads, g.keys);
    line('anchor', `${segment}|grid/${sub}`, 1, row.perRoot.grid);
    console.log(
      `;;   'anchor' is the PUBLISHED rf2-2rtt6.4 ${sub} grid arm, unchanged — same B/E/Q as ` +
        `R1Q${Math.round(B / row.perRoot.grid)} at these roots, through :p0/cell instead of :p0/fan.`
    );
  }

  // --- the additive model -------------------------------------------------
  console.log(';;');
  console.log(';; ==== THE ADDITIVE MODEL ====');
  console.log(';;   M3   y = shell + (E/B)·edge + (Q/B)·key            (the ruling\'s shape)');
  console.log(';;   M4   y = shell + [E>0]·step + (E/B)·edge + (Q/B)·key');
  console.log(';;   Each term from one contrast: shell is the R=0 rung; key is the R=1 slope in');
  console.log(';;   Q/B; edge is R2QB2 − R1Q2 (same Q, one more read); step is what is left of');
  console.log(';;   the R=1 intercept. R2Q2B is HELD OUT of all of that and predicted by both.');
  console.log(";;   The rule is p0-heap/additive-fit's — this driver states the rungs and reads");
  console.log(';;   the answer. It is a verdict about what may be PRICED, not an instrument gate.');
  const fits = row.fanFits;
  for (const segment of Object.keys(FAN_SUBSTRATE)) {
    const m = fits.mean[segment];
    const per = fits.perRound[segment];
    const rng = (f) => {
      const xs = per.map(f).filter((x) => typeof x === 'number' && isFinite(x));
      return xs.length ? `[${n0(Math.min(...xs))}–${n0(Math.max(...xs))}]` : '[—]';
    };
    console.log(';;');
    console.log(
      `;;   ${segment}:  shell ${n0(m.shell)} B ${rng((f) => f.shell)}` +
        `   step ${n0(m.step)} B ${rng((f) => f.step)}` +
        `   edge ${n0(m.edgeContrast)} B ${rng((f) => f.edgeContrast)}` +
        `   key ${n0(m.key)} B ${rng((f) => f.key)}`
    );
    console.log(
      `;;     r² ${m.r2.toFixed(5)}  ·  edge from the intercept ${n0(m.edgeIntercept)} B` +
        `  ·  key from the R=2 pair ${n0(m.keyAlt)} B`
    );
    console.log(
      `;;     held out ${m.heldOut.rung} = ${n0(m.heldOut.measured)} B  ·  M3 says ${n0(m.heldOut.m3)} B ` +
        `(${(100 * m.heldOut.m3Error).toFixed(2)}%)  ·  M4 says ${n0(m.heldOut.m4)} B ` +
        `(${(100 * m.heldOut.m4Error).toFixed(2)}%)`
    );
    for (const c of m.checks) {
      console.log(`;;     [${c.ok ? 'ok  ' : 'FAIL'}] ${c.name}`);
      console.log(`;;              ${c.detail}`);
    }
    const models = per.map((f) => f.model);
    const agreed = models.filter((x) => x === m.model).length;
    console.log(
      `;;     per-round: ${agreed} of ${per.length} rounds reach the same verdict ` +
        `(${models.map((x) => x || 'refused').join(', ')})`
    );
    console.log(`;;     VERDICT: ${m.why}`);
    row.fanFits.mean[segment].roundsAgreeing = agreed;
    row.fanFits.mean[segment].roundModels = models;
  }

  console.log(';;');
  console.log(';; ==== ARM-ORDER GUARD (fan-out) ====');
  console.log(
    `;;   ${row.orderRefused ? 'VERDICT: REFUSE — no figure above may be published as measured' : 'VERDICT: reportable'}`
  );
  if (row.orderRefused) console.log(`;;   ${row.orderVerdictEdn}`);
}

// ---------------------------------------------------------------------------

// The structural witness is the one gate here that needs neither a release
// build nor a Chromium to adjudicate — it is a pure function of the row —
// so it is exported and pinned directly by `p0_ladder_structural.test.cjs`
// (`test:script-helpers`). `--only ladder` is opt-in and in no gate, which
// is how the R=0 expectation sat stale from rf2-dabt3 until rf2-zei9w ran
// the driver; the unit pin is what stops the next such drift being found
// by the next measurement instead of by CI.
module.exports = {
  ladderStructuralFailures,
  allocSteps,
  allocRefusedWindows,
  // The window count behind the summary's numerator (rf2-xxeq), pure and
  // exported for `allocRefusedWindows`'s reason: the pin can DRIVE the ratio
  // rather than assert that it is possible.
  allocRefusedWindowCount,
  ALLOC_LEG_TOLERANCE,
  ALLOC_FALL_THRESHOLD_B,
  ladderPlan,
  allocArmSizing,
  ALLOC_MIN_WRITES,
  ALLOC_ARM,
  // The prime work unit (rf2-oiy1) — the split as a pure function, and both
  // counts, so the pin can DRIVE the exclusion rather than read the source and
  // hope. `ALLOC_WINDOW_WRITES` is what the window drives; `ALLOC_WRITES` is
  // what every published figure is divided by, and the pin checks they differ
  // by exactly the prime.
  allocPrimeSplit,
  ALLOC_PRIME_WRITES,
  ALLOC_WRITES,
  ALLOC_WINDOW_WRITES,
  // The by-site instrument (rf2-rs8q6), exported on the same rule as the prime
  // split above: the decomposition, the attribution and the reporter are all
  // pure, so the pins DRIVE them rather than read the source and hope. The two
  // constants come too, because the mode's most important property — that it
  // is OFF and the row is byte-identical without it — is a claim about the
  // resolved constants and can only be pinned from outside the process.
  allocSiteSplit,
  allocSiteWitness,
  allocSiteReport,
  ALLOC_BY_SITE,
  ALLOC_SITES,
  ALLOC_SITE_NAMES,
  // The intra-leg reclamation gate (rf2-4ctls), exported on the same rule: both
  // halves are pure, so the pin can REPLAY the three windows rf2-ojehu measured
  // through the real gate rather than assert that it would refuse them. The
  // property that matters most — that `allocSteps` is untouched and every
  // published figure is byte-identical — is likewise something a pin can drive
  // by running both functions over one window and comparing.
  allocIntraLegRefusals,
  allocWindowVerdict,
  // And the row's own statement of WHICH of the three screened it (rf2-fir5n),
  // exported on the same rule again: it is a pure function of the stride, so the
  // pin drives both branches instead of matching the source for a phrase. The
  // property that matters is a claim about what the string says at stride 2,
  // which a source match cannot make without restating the string.
  allocInstrumentNote,
  // The measurement surface (rf2-gxrr), exported so the structural pin can
  // DRIVE it rather than read its source: the tables as values, the plan
  // filter as a pure function, and the two resolved selections so the env
  // route can be pinned from outside the process exactly as `ALLOC_ARM` is.
  ALLOC_WRITE_SPECS,
  ALLOC_WRITE,
  ALLOC_WRITE_SPEC,
  // The paired selection (rf2-irxrw), exported on exactly the rule above: the
  // selection table as a value and the RESOLVED legs, so the env route to
  // `paired` is pinned from outside the process the way `all` already is —
  // configuration is read once, at require, and no in-process assignment can
  // reach it. `allocWindowKey` and `allocWriteProvenance` come too because
  // they are pure: the pin can DRIVE a paired-shaped record and an
  // unpaired-shaped one through the shipped adjudicator rather than restate
  // what it would say.
  ALLOC_WRITE_SELECTIONS,
  ALLOC_WRITE_LEGS,
  ALLOC_WRITE_PAIRED,
  allocWindowKey,
  allocWriteProvenance,
  ALLOC_PLAN_SHAPES,
  ALLOC_PLAN,
  ALLOC_PLAN_SHAPE,
  allocPlanArms,
  // The confound-breaking segment order (rf2-rs8q6), exported on exactly the
  // rule above: the order is a pure function of the plan, the round and the
  // mode, so the pin can DRIVE both modes over a plan and read the round
  // sequence out — including the property that matters most, that `parity` is
  // the pre-bead expression and reverses on odd rounds still. The resolved
  // constant comes too, so the env route is pinned from outside the process.
  allocSegmentOrder,
  ALLOC_SEG_ORDERS,
  ALLOC_SEG_ORDER,
  // The write-leg order's three pure functions (rf2-fk6pj), exported on the
  // rule above and with one more reason than the segment order has: the
  // properties that matter — that `parity` is the pre-bead expression to the
  // character, that a `seeded` schedule is BALANCED, and that it is not a
  // function of round parity — are claims about a whole run's schedule, and
  // none of the three is readable off a ternary in the source.
  allocSeedHash,
  allocSeedRandom,
  allocPassFlips,
  allocPassOrder,
  ALLOC_PASS_ORDERS,
  ALLOC_PASS_ORDER,
  ALLOC_PASS_SEED,
  ALLOC_PASS_SCHEDULE,
  // The box riders (rf2-24o2z), pure of the run for the same reason: the pin
  // can DRIVE a session over a marker it wrote itself and read the gap and the
  // session arithmetic out, with no build, no server and no Chromium.
  boxSnapshot,
  boxBusyFraction,
  boxSessionRead,
  boxSessionOpen,
  boxSessionClose,
  boxRecord,
  // The control slot's two pure functions, so the pin can DRIVE the multi-round
  // window sequence and read the two properties this mode separates off it,
  // rather than match the source for a ternary (rf2-rs8q6).
  allocControlIndex,
  allocRoundWindowKinds,
  ALLOC_CONTROL_SLOTS,
  ALLOC_CONTROL_SLOT,
  // The row's own table reader, so the pin can DRIVE a narrowed plan through
  // it. A mode that collects V3's controls and then throws in the summariser
  // has not delivered V3, and "the mode is defined" and "the mode runs" are
  // different claims.
  summariseAlloc,
};

if (require.main === module) (async () => {
  build();
  const server = serve();
  const { chromium } = require(path.join(__dirname, '../../../../..', 'implementation', 'node_modules', 'playwright'));
  const out = { generatedAt: new Date().toISOString(), build: BUILD, initFn: INIT_FN };
  // EVERY failed gate, not the last one. A single `failed` slot let a
  // later gate's silence overwrite an earlier gate's refusal, and a run
  // that failed two things would name one of them.
  const failures = [];
  let refused = false;
  // `--only fanout` is opt-in and runs NOTHING ELSE. It is 5x the arms of
  // the published heap row and it answers a different question, so folding
  // it into a default run would both cost every run five times over and
  // change the sample stream the heap row's arm-order guard adjudicates.
  const wantClock = ONLY === null || ONLY === 'clock';
  const wantHeap = ONLY === null || ONLY === 'heap';
  const wantFanout = ONLY === 'fanout';
  // `--only ladder` is opt-in on the same terms as `--only fanout`, and
  // for the same two reasons: it is 5x the arms of the published heap
  // row, and folding it into a default run would change the sample
  // stream that row's arm-order guard adjudicates.
  const wantLadder = ONLY === 'ladder';
  // `--only alloc` is opt-in on the same terms, and for a third reason as
  // well as the two the ladder gives: it is the only row here that keeps
  // an arm mounted across a measured window, so its sample stream is not
  // the mount/release one the arm-order guard adjudicates.
  const wantAlloc = ONLY === 'alloc';
  if (ONLY !== null && !wantClock && !wantHeap && !wantFanout && !wantLadder && !wantAlloc) {
    console.error(`[p0] unknown --only ${ONLY} (clock | heap | fanout | ladder | alloc)`);
    process.exit(1);
  }
  try {
    if (wantClock) {
      console.error('[p0] clock row ...');
      const c = await clockRow(chromium);
      out.clock = c.results;
      if (c.err) {
        failures.push(`clock: ${c.err}`);
      } else {
        out.clockGates = { verification: c.verification, control: c.control };
        console.log(';; ==== P0 CLOCK ====');
        console.log(c.results);
        summariseClock(c);
        // A row whose writes never reached the page is the cheapest row in
        // any table. The count was printed inside the record from the
        // first run; nothing exited on it until rf2-95s5b.
        if (c.verification.unverified > 0) {
          failures.push(
            `clock: ${c.verification.unverified} unverified of ${c.verification.of} windows ` +
              `— ${c.verification.perRow}`
          );
        }
        if (!c.control.ok) failures.push(`clock: positive control — ${c.control.why}`);
        const m = /:refused \[([^\]]*)\]/.exec(c.results);
        if (m && m[1].trim()) {
          refused = true;
          console.log(';;');
          console.log(';; ==== ARM ORDER: THESE CLOCK ROWS ARE NOT REPORTABLE ====');
          console.log(`;;   ${m[1].trim()}`);
        }
      }
    }
    if (wantHeap) {
      console.error('[p0] heap row ...');
      out.heap = await heapRow(chromium);
      summariseHeap(out.heap);
      if (out.heap.verification.unverified > 0) {
        failures.push(`heap: ${out.heap.verification.unverified} unverified mounts`);
      }
      if (!out.heap.control.verdict.ok) {
        failures.push(`heap: positive control — ${out.heap.control.verdict.why}`);
      }
      refused = refused || out.heap.orderRefused;
    }
    if (wantFanout) {
      console.error('[p0] fan-out sweep ...');
      out.fanout = await fanoutRow(chromium);
      summariseFanout(out.fanout);
      if (out.fanout.verification.unverified > 0) {
        failures.push(
          `fanout: ${out.fanout.verification.unverified} unverified mounts — ` +
            out.fanout.verification.detail.join(' | ')
        );
      }
      if (!out.fanout.control.verdict.ok) {
        failures.push(`fanout: positive control — ${out.fanout.control.verdict.why}`);
      }
      refused = refused || out.fanout.orderRefused;
      // The additive verdict is NOT an exit code. A model that does not
      // hold is a finding about the substrate, not a fault in the
      // instrument that measured it — the rows stay quotable and only the
      // component PRICES do not. It is adjudicated, printed as a verdict
      // and carried in the raw record; what it gates is what may be
      // written into validation.md.
    }
    if (wantLadder) {
      console.error('[p0] reads ladder ...');
      out.ladder = await ladderRow(chromium);
      const structural = ladderStructuralFailures(out.ladder);
      out.ladder.structuralFailures = structural;
      summariseLadder(out.ladder, structural);
      if (out.ladder.verification.unverified > 0) {
        failures.push(
          `ladder: ${out.ladder.verification.unverified} unverified mounts — ` +
            out.ladder.verification.detail.join(' | ')
        );
      }
      if (!out.ladder.control.verdict.ok) {
        failures.push(`ladder: positive control — ${out.ladder.control.verdict.why}`);
      }
      // The structural witness IS an exit code, unlike the additive
      // verdict, and the difference is what each one decides. The
      // additive model failing is a finding about a substrate. The
      // structural counts failing means the arm on the page is not the
      // arm the row claims to have measured — "one hook plus N edges in
      // a shared index" would be a description of something else — or
      // that a released arm is still holding objects, which is HD-002
      // clause (d)'s own failure. Neither is quotable.
      if (structural.length > 0) {
        failures.push(
          `ladder: ${structural.length} structural read-back failures — ${structural[0]}`
        );
      }
      refused = refused || out.ladder.orderRefused;
    }
    if (wantAlloc) {
      console.error('[p0] steady-state allocation row ...');
      out.alloc = await allocRow(chromium);
      const refusedWindows = allocRefusedWindows(out.alloc);
      summariseAlloc(out.alloc, refusedWindows);
      if (out.alloc.verification.unverified > 0) {
        failures.push(
          `alloc: ${out.alloc.verification.unverified} unverified — ` +
            out.alloc.verification.detail.join(' | ')
        );
      }
      // The controls ARE an exit code. Everything this row prints rests on
      // one claim — that transient garbage is visible to the counter at
      // all — and a run whose control read zero would be a retention
      // instrument publishing an allocation table, which is the exact
      // fault that produced a wrong table on this surface before.
      if (!out.alloc.controlVerdict.ok) {
        failures.push(
          `alloc: positive control — ${out.alloc.controlVerdict.perDouble.toFixed(2)} B/double ` +
            `direct, ${out.alloc.controlVerdict.differential.toFixed(2)} B/double differential, ` +
            'against a predicted 8'
        );
      }
      if (out.alloc.fallsInMeasuredWindows > 0) {
        failures.push(
          `alloc: ${out.alloc.fallsInMeasuredWindows} collections fell inside measured windows ` +
            '— every arm figure in this run is an UNDER-estimate, and under-reading allocation ' +
            'is the direction that manufactures the flat-at-zero answer HD-002 predicts, so no ' +
            'slope here is quotable. The controls above still adjudicate the arithmetic; what ' +
            'refuses is the arms\' scale, not the method'
        );
      }
      // AND ITS BLIND SIDE (rf2-2rtt6.140, superseding rf2-n6w7o). The gate
      // above sees a collection only when it turns a step negative. One that
      // runs inside a leg allocating at least as much as it reclaimed turns
      // nothing negative, so that gate reports a clean window while the
      // reclaimed bytes are missing from `rise`. This one reads the window's
      // OWN legs — W repetitions of one work unit — and refuses the window
      // where one of them does not look like the others. It is additional to
      // the falling-step gate and replaces none of it.
      //
      // THE TWO GATES ARE NAMED APART IN THE EXIT (rf2-4ctls), because a
      // failure line is what an operator repairs against and the two send them
      // to different places: one to the arm, one to the collector's schedule.
      // The summary above still prints every reason together.
      const legRefused = allocRefusedWindows(out.alloc, 'legRefusals');
      if (legRefused.length > 0) {
        failures.push(
          `alloc: ${legRefused.length} windows have a work leg that deviates from its own ` +
            'cohort median by more than the leg tolerance. A leg BELOW its cohort is a leg ' +
            'something removed bytes from and nothing in the work unit removes bytes; a leg ' +
            'ABOVE it is a window whose ONE WORK UNIT premise failed. Either way the window ' +
            "under-reads by an unknown amount, and under-reading manufactures HD-002's " +
            `flat-at-zero. DO NOT WIDEN THE TOLERANCE. First: ${legRefused[0]}`
        );
      }
      // AND THE SEAM BETWEEN THEM (rf2-4ctls). rf2-ojehu measured 72 arm
      // windows and found 24 carrying a reclamation INSIDE one leg; three were
      // invisible to the falls gate and two of those certified at τ. The falls
      // gate is defined on the collapsed stream, where such a leg is one
      // non-negative step; the leg tolerance reads that leg's NET, which sits
      // inside τ. Neither is defective — the fault lives in the gap, and this
      // reads the by-site stream that already saw it. REPAIR NOTHING BY
      // WIDENING: this gate only ever adds refusals, and τ is not its dial.
      const intraRefused = allocRefusedWindows(out.alloc, 'intraLegRefusals');
      if (intraRefused.length > 0) {
        failures.push(
          `alloc: ${intraRefused.length} intra-leg reclamations — a collection ran INSIDE a ` +
            'measured leg and a larger allocation in the same leg bracketed it, so no step fell ' +
            'and the leg NET stayed inside τ. The window under-reads by at least the reclaimed ' +
            'amount with both other gates silent, which is exactly the direction that ' +
            `manufactures HD-002's flat-at-zero. First: ${intraRefused[0]}`
        );
      }
    }
  } catch (e) {
    failures.push(String(e && e.stack ? e.stack : e));
  } finally {
    server.close();
  }
  // THE RAW RECORD, AND WHAT AN ALLOCATION WINDOW IS EXPECTED TO DO WITH IT
  // (rf2-erre5). **An allocation window commits this file beside its studio
  // page**, under
  // `implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-<bead>/`,
  // exactly as rf2-2rtt6.138 did — and that is a CONVENTION rather than a
  // mechanism, because nothing here can tell whether an operator committed a
  // file, and a gate that guessed would go red on every run that was not a
  // published window.
  //
  // WHY IT IS WORTH THE HABIT. The 2026-08-08 window is the only allocation
  // window that ever did it, and it is the only one that has since been
  // re-analysed: rf2-nkeba re-derived its figures under an estimator that did
  // not exist when it was taken, and settled that the published values were the
  // MEDIAN of rise/W rather than the mean the summary printed. The 2026-08-13,
  // 2026-08-16 and 2026-08-17 windows published records and no dataset, so the
  // same question is not askable of them at all. The convention has paid for
  // itself once; the three windows that skipped it cannot be made to pay later.
  // AND WHAT THE BOX WAS DOING WHILE IT RAN (rf2-24o2z), closed here rather
  // than in the try above so a REFUSED run carries it too — the evidence
  // surviving the refusal is this file's own rule, and a window that refused
  // is exactly the kind whose machine conditions a later reader will want.
  BOX.close = boxSnapshot();
  out.box = boxRecord();
  // AND THE NOTE THIS RUN LEAVES FOR THE NEXT ONE. It is the only write here
  // that is not into the record, and it is what makes `session.previousRun`
  // answerable at all. A failure to write it is recorded and never thrown:
  // this rider may not refuse a window.
  const markerErr = boxSessionClose(BOX.session);
  if (markerErr) out.box.session.markerError = markerErr;
  const raw = process.env.P0_RAW_OUT;
  if (raw) {
    fs.mkdirSync(path.dirname(raw), { recursive: true });
    fs.writeFileSync(raw, JSON.stringify(out, null, 2));
    console.error(`[p0] raw data -> ${raw}`);
    console.error('[p0] an allocation window COMMITS this file beside its studio page');
  }
  // THE PAGES' OWN FAILURES, JOINING THE LIST THE EXIT ALREADY READS
  // (rf2-sib23). It joins `failures` rather than taking a code of its own
  // because it is the same class as every other entry there — the run did not
  // measure what it says it measured — and because that list is already the
  // one place this driver decides on. Every raw artefact is written above, so
  // the evidence survives the refusal.
  for (const e of pageFailures()) {
    failures.push(
      `the page threw and kept going — ${e}. Every figure above was taken after an uncaught ` +
        'error, and no window.P0_ERROR is set for one: React does not rethrow an uncaught ' +
        'render error to the caller of flushSync (see sentinel.cjs).'
    );
  }
  if (failures.length) {
    for (const f of failures) console.error(`[p0] FAILED: ${f}`);
    process.exit(1);
  }
  // A run whose figures the arm-order guard refused is not a green run.
  // The data is printed and the raw file written — the refusal is about
  // what may be QUOTED, not about throwing the measurement away. Exit 2,
  // the same code the sibling harnesses use for the same verdict. REPAIR
  // THE ARM, NOT THE GUARD.
  if (refused) {
    console.error('[p0] arm-order guard REFUSED — figures are not reportable');
    process.exit(2);
  }
  console.error('[p0] done');
})();
